// docs/design/m6-event-channels.md §4.3, §8.4 (WP-1).
package app.thebox.core.sports.hub

import app.thebox.core.events.EventMatchup
import app.thebox.core.events.EventText
import app.thebox.core.events.EventZoneSource
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TeamName
import app.thebox.core.sports.TeamNames
import kotlin.math.abs

/**
 * What stage E knows of one indexed event. [name]: the whole provider name (the hints read it, with [group]); [title]:
 * the cleaned title the sides come from; [startUtcMs]: the event's start; [zone]: how the start's zone was found.
 */
data class EventFacts(val name: String, val title: String, val group: String?, val startUtcMs: Long, val zone: EventZoneSource)

object EventGameRule {
    /** [citiesOnly]: both sides matched by place only; [zoneAssumed]: [EventFacts.zone] is ASSUMED. */
    data class Grade(val confidence: MatchConfidence, val citiesOnly: Boolean, val zoneAssumed: Boolean)

    private const val MIN_MS = 60_000L

    /** §8.4 step 3: any of these in the name or the group means another sport, level or league. */
    private val NEGATIVE: Set<String> = setOf(
        "nba", "wnba", "basketball", "ncaa", "ncaab", "ncaaw", "ncaaf", "college", "mlb", "baseball", "mls", "soccer", "ahl",
        "echl", "ohl", "whl", "qmjhl", "chl", "usports", "u17", "u18", "u19", "u20", "u21", "junior", "women", "womens", "cfl",
    )
    private val NEGATIVE_NHL: Set<String> = setOf("nfl", "football")
    private val NEGATIVE_NFL: Set<String> = setOf("nhl", "hockey")
    private val POSITIVE_NHL: Set<String> = setOf("nhl", "hockey", "lnh")
    private val POSITIVE_NFL: Set<String> = setOf("nfl")

    /**
     * §8.4. [board]: the other games the hub has loaded for the day (the cities-only upgrade). Null: not this game.
     * Never throws.
     */
    fun grade(event: EventFacts, game: GameSnapshot, board: List<GameSnapshot>): Grade? = try {
        gradeImpl(event, game, board)
    } catch (e: Exception) {
        null
    }

    private fun gradeImpl(event: EventFacts, game: GameSnapshot, board: List<GameSnapshot>): Grade? {
        val league = game.id.league
        if (league != League.NHL && league != League.NFL) return gradeByOwnNames(event, game)
        val away = TeamNames.of(league, game.away.abbrev) ?: return null
        val home = TeamNames.of(league, game.home.abbrev) ?: return null
        if (away.abbrev == home.abbrev) return null

        // 1. Time.
        val delta = event.startUtcMs - game.startUtcMs
        if (delta < -SportsConstants.HUB_EVENT_BEFORE_MIN * MIN_MS || delta > SportsConstants.HUB_EVENT_AFTER_MIN * MIN_MS) return null

        // 3. Hints over the whole name and the group.
        val hints = hintsOf(event.name, event.group)
        if (hints.negative(league)) return null
        val positive = hints.positive(league)

        // 2 and 4. Sides by equality, in order when the separator is ordered.
        val m = EventMatchup.of(event.title) ?: return null
        val sa = sideOf(m.first, away, league)
        val sb = sideOf(m.second, home, league)
        val first: TeamNames.Strength
        val second: TeamNames.Strength
        if (sa != null && sb != null) {
            first = sa; second = sb
        } else {
            if (m.ordered) return null
            first = sideOf(m.first, home, league) ?: return null
            second = sideOf(m.second, away, league) ?: return null
        }

        // 5. Level.
        val assumed = event.zone == EventZoneSource.ASSUMED
        val place = TeamNames.Strength.PLACE
        val citiesOnly = first == place && second == place
        val confidence: MatchConfidence = when {
            first != place && second != place -> {
                val cross = (first == TeamNames.Strength.NICKNAME && TeamNames.crossLeagueNickname(league, m.first)) ||
                    (second == TeamNames.Strength.NICKNAME && TeamNames.crossLeagueNickname(league, m.second))
                when {
                    assumed -> MatchConfidence.LIKELY
                    !cross || positive -> MatchConfidence.CONFIRMED
                    else -> MatchConfidence.LIKELY
                }
            }
            citiesOnly -> when {
                positive -> if (assumed) MatchConfidence.LIKELY else MatchConfidence.CONFIRMED
                m.ordered -> {
                    val tight = abs(delta) <= SportsConstants.HUB_CITIES_CONFIRM_MIN * MIN_MS &&
                        league == League.NHL && !boardClash(game, away, home, board, league)
                    when {
                        tight && !assumed -> MatchConfidence.CONFIRMED
                        tight -> MatchConfidence.LIKELY
                        assumed -> return null
                        else -> MatchConfidence.LIKELY
                    }
                }
                else -> return null
            }
            else -> if (positive && !assumed) MatchConfidence.CONFIRMED else MatchConfidence.LIKELY
        }
        return Grade(confidence, citiesOnly, assumed)
    }

    /**
     * (Code review 2026-11-25, search M1) True when [name] or [group] carries one of step 3's negative hints for
     * [league]: another sport, level or league ("NBA", "College", "(W)"). Search applies it to a place-only hit.
     * Never throws.
     */
    fun otherSport(name: String, group: String?, league: League): Boolean = try {
        hintsOf(name, group).negative(league)
    } catch (e: Exception) {
        false
    }

    /**
     * Sports with no other-sport words in the name or group of a junior league's event (code review 2026-11-25, owner
     * item 17). Not the league words: a slot prefix ("CHL 01" carries AHL games, "OHL 03") is never evidence either way.
     */
    private val NEGATIVE_JUNIOR: Set<String> = setOf(
        "nba", "wnba", "basketball", "mlb", "baseball", "mls", "soccer", "nfl", "football", "cfl", "women", "womens",
    )

    /**
     * (Code review 2026-11-25, owner item 17) A league without a [TeamNames] table (OHL, WHL, QMJHL): the event's two
     * sides against the game's own team names, from the feed (`TeamLine.name`) and `ChlTeamPlaces` (place, nickname,
     * "place nickname"), folded by [TeamNames.tokens]. Both sides must match (in order for "@" / "at"); one side alone is
     * no match. Both full names (or the feed's names) → CONFIRMED; any place-only or nickname-only side → LIKELY at most
     * (AHL and junior clubs share cities and nicknames). An assumed zone → LIKELY. The slot prefix or group label is
     * never evidence; only other-sport words rule an event out.
     */
    private fun gradeByOwnNames(event: EventFacts, game: GameSnapshot): Grade? {
        val league = game.id.league
        val delta = event.startUtcMs - game.startUtcMs
        if (delta < -SportsConstants.HUB_EVENT_BEFORE_MIN * MIN_MS || delta > SportsConstants.HUB_EVENT_AFTER_MIN * MIN_MS) return null
        val hints = hintsOf(event.name, event.group)
        if (hints.bracketed || hints.tokens.any { it in NEGATIVE_JUNIOR }) return null
        val away = ownName(league, game.away) ?: return null
        val home = ownName(league, game.home) ?: return null
        val m = EventMatchup.of(event.title) ?: return null
        var first = TeamNames.sideMatch(m.first, away)
        var second = TeamNames.sideMatch(m.second, home)
        if (first == null || second == null) {
            if (m.ordered) return null
            first = TeamNames.sideMatch(m.first, home) ?: return null
            second = TeamNames.sideMatch(m.second, away) ?: return null
        }
        val place = TeamNames.Strength.PLACE
        val citiesOnly = first == place && second == place
        val assumed = event.zone == EventZoneSource.ASSUMED
        val full = first == TeamNames.Strength.FULL && second == TeamNames.Strength.FULL
        val confidence = if (full && !assumed) MatchConfidence.CONFIRMED else MatchConfidence.LIKELY
        return Grade(confidence, citiesOnly, assumed)
    }

    /** The game's own names for one side as a [TeamName] (no table row): the feed's name and the CHL place table. */
    private fun ownName(league: League, line: app.thebox.core.sports.TeamLine): TeamName? {
        val entry = ChlTeamPlaces.of(league, line.abbrev)
        val feed = line.name.trim()
        val full = LinkedHashSet<String>()
        val nick = ArrayList<String>(1)
        val places = ArrayList<String>(1)
        if (entry != null) {
            full += "${entry.place} ${entry.nickname}"
            nick += entry.nickname
            places += entry.place
            if (entry.cardPlace != entry.place) places += entry.cardPlace
        }
        val feedKey = TeamNames.tokens(feed)
        if (feedKey.isNotEmpty() && nick.none { TeamNames.tokens(it) == feedKey } && places.none { TeamNames.tokens(it) == feedKey }) full += feed
        if (full.isEmpty()) return null
        return TeamName(league, line.abbrev, full.toList(), nick, places, emptyList())
    }

    /** A side's strength against [team]; a PLACE two clubs share ("New York") is unusable. */
    private fun sideOf(side: List<String>, team: TeamName, league: League): TeamNames.Strength? {
        val s = TeamNames.sideMatch(side, team) ?: return null
        if (s == TeamNames.Strength.PLACE && TeamNames.placeShared(league, side.joinToString(" "))) return null
        return s
    }

    /** Another game on [board] within ±[SportsConstants.HUB_BOARD_CLASH_MIN] involving either team's place. */
    private fun boardClash(game: GameSnapshot, away: TeamName, home: TeamName, board: List<GameSnapshot>, league: League): Boolean {
        val places = TeamNames.foldedPlaces(away) + TeamNames.foldedPlaces(home)
        val window = SportsConstants.HUB_BOARD_CLASH_MIN * MIN_MS
        for (other in board) {
            if (other.id == game.id) continue
            if (abs(other.startUtcMs - game.startUtcMs) > window) continue
            for (abbrev in arrayOf(other.away.abbrev, other.home.abbrev)) {
                val t = TeamNames.of(league, abbrev) ?: continue
                if (TeamNames.foldedPlaces(t).any { it in places }) return true
            }
        }
        return false
    }

    private class Hints(val tokens: Set<String>, val bracketed: Boolean) {
        fun negative(league: League): Boolean {
            if (bracketed) return true
            if (tokens.any { it in NEGATIVE }) return true
            val extra = if (league == League.NHL) NEGATIVE_NHL else NEGATIVE_NFL
            return tokens.any { it in extra }
        }

        fun positive(league: League): Boolean =
            tokens.any { it in (if (league == League.NHL) POSITIVE_NHL else POSITIVE_NFL) }
    }

    /** Folded tokens of the name and group ("NHL.TV" gives "nhl" and "tv"), plus the "(w)" / "(m)" markers. */
    private fun hintsOf(name: String, group: String?): Hints {
        val text = if (group.isNullOrEmpty()) name else "$name | $group"
        val tokens = EventText.tokens(text).toHashSet()
        val folded = EventText.fold(text)
        val bracketed = folded.contains("(w)") || folded.contains("(m)")
        return Hints(tokens, bracketed)
    }
}
