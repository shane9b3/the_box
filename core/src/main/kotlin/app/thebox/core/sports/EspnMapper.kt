// Contract (NFL commit 0, docs/design/m6-nfl.md §1.3, B3): the signatures are fixed; WP-1 writes the bodies.
package app.thebox.core.sports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.floor

/**
 * ESPN site scoreboard to snapshots (docs/design/m6-nfl.md §1.3; WP-1). Pure: no I/O, no clock. [serverDateMs] is the
 * body's `Date` (the PRE window); [logoBase] is the ESPN logo base (`https://a.espncdn.com/`, or the debug override).
 * A board fails (null) only when `events` is missing; an event that fails to map is dropped from it.
 *
 * How the rules land (WP-1 reading of §1.3):
 * - **Leaves are lenient** (ruling 21): a string, a number or a missing value all work; anything else is null for that
 *   field only. An event needs `id` (digits), `date`, `status.type.state` (`pre`/`in`/`post`) and two competitors with
 *   `homeAway` and a `team.abbreviation`; without them it is dropped (and `game()` answers null).
 * - **Week or date.** A body with a top-level `season {year, type}` and `week.number` is a week body (the undated
 *   `scoreboard` and `?seasontype=&week=`): its key is [weekKey] and its neighbours come from the calendar. Any other
 *   body is a dated one (`?dates=`, used only by `game()`): its key is the ET date of its first event (the DTO has no
 *   `day` field), else of [serverDateMs], and it has no neighbours (an NFL request is never a single date, §7.2).
 * - **Scores** are null before `in` (ESPN sends "0" pre-game). So an `in` body that the S8 coin-toss rule maps to
 *   [GamePhase.PRE] is the one PRE snapshot with both scores set; the machine polls it at the LIVE cadence (lead
 *   sign-off item 1).
 * - [GameSnapshot.dayKey] is the event's own ET date; [TeamLine.logo] the feed's light logo when it is under
 *   [logoBase] (the source derives the dark combiner URL, §1.5), else one derived from the abbreviation.
 * - [GameSnapshot.detail] is always a [LeagueDetail.Nfl]; with no `situation` its play is [NflPlay.None].
 */
object EspnMapper {
    /** The week or a dated board. [DayBoard.dayKey] is the week key (`w2026-2-9`) or the ET date ("2026-11-08"). */
    fun board(dto: EspnBoardDto, serverDateMs: Long?, logoBase: String): DayBoard? {
        val events = dto.events ?: return null
        val games = events.mapNotNull { event(it, serverDateMs, logoBase) }
        val week = weekOf(dto)
        if (week != null) {
            val (prev, next) = neighbours(dto, week)
            return DayBoard(weekKey(week.first, week.second, week.third), prev, next, games)
        }
        val day = games.firstOrNull()?.dayKey
            ?: events.firstNotNullOfOrNull { it.date.str()?.let(::parseUtc) }?.let(::easternDay)
            ?: serverDateMs?.let(::easternDay)
            ?: return null
        return DayBoard(day, null, null, games)
    }

    /** The event [id] of a dated (or week) board; null when it is absent or fails to map. */
    fun game(dto: EspnGameDto, id: GameId, serverDateMs: Long?, logoBase: String): GameSnapshot? {
        val e = dto.events?.firstOrNull { it.id.str() == id.id } ?: return null
        return event(e, serverDateMs, logoBase)
    }

    /** "w{year}-{seasontype}-{week}", e.g. "w2026-2-9" (S6): never readable as a date. */
    fun weekKey(year: Int, type: Int, week: Int): String = "w$year-$type-$week"

    /** The one week-key parser (S6): (year, seasontype, week), or null for anything else (a date key included). */
    fun parseWeekKey(key: String): Triple<Int, Int, Int>? {
        val m = WEEK_KEY.matchEntire(key) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val t = m.groupValues[2].toIntOrNull() ?: return null
        val w = m.groupValues[3].toIntOrNull() ?: return null
        if (t < 1 || w < 1) return null
        return Triple(y, t, w)
    }

    // ------------------------------------------------------------------ the week and its neighbours

    private val WEEK_KEY = Regex("w(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    /** (year, type, week) of a week body: the top-level `season` and `week`; null for a dated body. */
    private fun weekOf(dto: EspnBoardDto): Triple<Int, Int, Int>? {
        val season = dto.season as? JsonObject ?: return null
        val year = season.int("year") ?: return null
        val type = season.int("type") ?: return null
        val week = (dto.week as? JsonObject)?.int("number") ?: return null
        if (type < 1 || week < 1) return null
        return Triple(year, type, week)
    }

    /**
     * The calendar entries before and after [week], across season types in the calendar's order (Week 18 → Wild Card,
     * Preseason Week 3 → Week 1). Null at either edge, and both null when the week is not in the calendar (the
     * off-season's entry has no weeks).
     */
    private fun neighbours(dto: EspnBoardDto, week: Triple<Int, Int, Int>): Pair<String?, String?> {
        val cal = dto.leagues?.firstOrNull()?.calendar as? JsonArray ?: return null to null
        val flat = ArrayList<Pair<Int, Int>>() // (type, week) in calendar order
        for (t in cal) {
            val type = (t as? JsonObject)?.int("value") ?: continue
            val entries = t["entries"] as? JsonArray ?: continue
            for (e in entries) {
                val w = (e as? JsonObject)?.int("value") ?: continue
                flat += type to w
            }
        }
        val i = flat.indexOf(week.second to week.third)
        if (i < 0) return null to null
        val prev = flat.getOrNull(i - 1)?.let { weekKey(week.first, it.first, it.second) }
        val next = flat.getOrNull(i + 1)?.let { weekKey(week.first, it.first, it.second) }
        return prev to next
    }

    // ------------------------------------------------------------------ one event

    private fun event(e: EspnEventDto, serverDateMs: Long?, logoBase: String): GameSnapshot? {
        val id = e.id.str()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val start = e.date.str()?.let(::parseUtc) ?: return null
        val comp = e.competitions?.firstOrNull() ?: return null
        val status = comp.status as? JsonObject ?: return null
        val type = status.obj("type") ?: return null
        val state = type.str("state") ?: return null
        if (state != "pre" && state != "in" && state != "post") return null
        val name = type.str("name")

        val competitors = comp.competitors ?: return null
        val awayC = competitors.firstOrNull { it.homeAway.str() == "away" } ?: return null
        val homeC = competitors.firstOrNull { it.homeAway.str() == "home" } ?: return null
        val scored = state != "pre"
        val away = team(awayC, scored, logoBase) ?: return null
        val home = team(homeC, scored, logoBase) ?: return null
        val awayId = awayC.id.str() ?: (awayC.team as? JsonObject)?.str("id")
        val homeId = homeC.id.str() ?: (homeC.team as? JsonObject)?.str("id")
        fun sideOfId(teamId: String?): Side? = when {
            teamId == null -> null
            teamId == awayId && teamId != homeId -> Side.AWAY
            teamId == homeId && teamId != awayId -> Side.HOME
            else -> null
        }

        val period = status.int("period")?.takeIf { it >= 1 }
        val sit = comp.situation as? JsonObject
        val lastPlay = sit?.obj("lastPlay")?.let { lastPlay(it, ::sideOfId) }

        val phase = when {
            name == "STATUS_POSTPONED" || name == "STATUS_CANCELED" || name == "STATUS_CANCELLED" ->
                if (state == "in") GamePhase.LIVE else GamePhase.POSTPONED
            state == "pre" ->
                if (serverDateMs != null && serverDateMs >= start - SportsConstants.NFL_PRE_WINDOW_MS) GamePhase.PRE else GamePhase.SCHEDULED
            state == "post" -> GamePhase.FINAL
            // S8 (ruling 4): ESPN goes `in` at the coin toss; the bug appears with the first logged play after it.
            beforeKickoff(period, away.score, home.score, sit) -> GamePhase.PRE
            name == "STATUS_END_PERIOD" || name == "STATUS_HALFTIME" -> GamePhase.INTERMISSION
            else -> GamePhase.LIVE // STATUS_IN_PROGRESS and any unknown `in` name (a weather delay)
        }
        val breakKind = when {
            phase != GamePhase.INTERMISSION -> null
            name == "STATUS_HALFTIME" -> NflBreak.HALFTIME
            else -> NflBreak.END_PERIOD
        }

        val periodKind = period?.let { if (it >= OT_FIRST) PeriodKind.OT else PeriodKind.REG }
        val otNumber = period?.takeIf { it >= OT_FIRST }?.let { it - REG_PERIODS }
        val finalKind = if (phase == GamePhase.FINAL) {
            if ((period ?: 0) >= OT_FIRST) PeriodKind.OT else PeriodKind.REG
        } else {
            null
        }
        val secs = status.double("clock")?.takeIf { it.isFinite() }?.let { floor(it).toInt() }
            ?: status.str("displayClock")?.let(::parseMmSs)
        val clock = secs?.let { GameClock(it.coerceAtLeast(0), running = false, inIntermission = phase == GamePhase.INTERMISSION) }

        return GameSnapshot(
            id = GameId(League.NFL, id),
            dayKey = easternDay(start),
            startUtcMs = start,
            phase = phase,
            away = away,
            home = home,
            period = period,
            periodKind = periodKind,
            otNumber = otNumber,
            clock = clock,
            finalKind = finalKind,
            detail = detail(sit, away, home, ::sideOfId, breakKind, lastPlay),
        )
    }

    /** (S8) `in`, period 1, 0–0, and `lastPlay` absent or the coin toss: still before the opening kick-off. */
    private fun beforeKickoff(period: Int?, away: Int?, home: Int?, sit: JsonObject?): Boolean {
        if ((period ?: 1) > 1) return false
        if ((away ?: 0) != 0 || (home ?: 0) != 0) return false
        // A lastPlay that is absent, re-typed (not an object) or has no type id is no logged play.
        val type = sit?.obj("lastPlay")?.obj("type")?.str("id") ?: return true
        return type == SportsConstants.NFL_COIN_TOSS_TYPE
    }

    private fun team(c: EspnCompetitorDto, scored: Boolean, logoBase: String): TeamLine? {
        val t = c.team as? JsonObject ?: return null
        val abbrev = t.str("abbreviation")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = t.str("displayName")?.takeIf { it.isNotBlank() } ?: t.str("name")?.takeIf { it.isNotBlank() } ?: abbrev
        val short = t.str("shortDisplayName")?.takeIf { it.isNotBlank() }
        val score = if (scored) c.score.int()?.takeIf { it >= 0 } else null
        val feed = t.str("logo")
        val logo = when {
            feed != null && feed.startsWith(logoBase) && !feed.contains("..") -> feed
            LOGO_ABBREV.matches(abbrev) -> "${logoBase}i/teamlogos/nfl/500/scoreboard/${abbrev.lowercase()}.png"
            else -> null
        }
        return TeamLine(abbrev, name, score, logo, short)
    }

    private val LOGO_ABBREV = Regex("[A-Za-z]{2,3}")

    private fun lastPlay(lp: JsonObject, sideOf: (String?) -> Side?): NflLastPlay? {
        val id = lp.str("id") ?: return null
        val type = lp.obj("type")
        return NflLastPlay(
            id = id,
            typeId = type?.str("id"),
            typeText = type?.str("text"),
            side = sideOf(lp.obj("team")?.str("id")),
            scoreValue = lp.int("scoreValue"),
        )
    }

    // ------------------------------------------------------------------ the situation (§1.3)

    private val POSSESSION_TEXT = Regex("([A-Z]{2,3}) (\\d{1,2})")
    private val GOAL_TEXT = Regex("(?i)(1st|2nd|3rd|4th) & goal")

    private fun detail(
        sit: JsonObject?,
        away: TeamLine,
        home: TeamLine,
        sideOf: (String?) -> Side?,
        breakKind: NflBreak?,
        lastPlay: NflLastPlay?,
    ): LeagueDetail.Nfl {
        if (sit == null) return LeagueDetail.Nfl(NflPlay.None, null, redZone = false, breakKind = breakKind, lastPlay = null)
        val possession = sideOf(sit.str("possession"))
        val down = sit.int("down")
        val distance = sit.int("distance")
        val field = field(sit, away, home)
        val play: NflPlay = when {
            down != null && down in 1..4 -> {
                val dist = distance?.takeIf { it in 1..99 }
                val goalText = sit.str("shortDownDistanceText")?.trim()?.let { GOAL_TEXT.matches(it) } == true
                // Goal to go from the numbers: the distance equals the yards to the goal line, which is the field's
                // yard when the ball is in the other side's half.
                val goalNumbers = dist != null && field != null && possession != null &&
                    field.territory != null && field.territory != possession && dist == field.yard
                NflPlay.Down(down, dist, goalText || goalNumbers, field)
            }
            distance == 0 && possession != null -> NflPlay.Kickoff(possession, field)
            distance != null && distance > 0 && possession == null -> {
                val scoring = lastPlay?.takeIf { (it.scoreValue ?: 0) > 0 }
                NflPlay.Dead(scoring?.side, scoring?.scoreValue)
            }
            else -> NflPlay.None
        }
        val redZone = sit.bool("isRedZone") == true && play is NflPlay.Down
        return LeagueDetail.Nfl(play, possession, redZone, breakKind, lastPlay)
    }

    /** (B1) Whose half the ball is in: from `possessionText`'s prefix, else from the home-relative `yardLine`. */
    private fun field(sit: JsonObject, away: TeamLine, home: TeamLine): FieldPos? {
        sit.str("possessionText")?.trim()?.let { POSSESSION_TEXT.matchEntire(it) }?.let { m ->
            val yard = m.groupValues[2].toIntOrNull()
            val abbr = m.groupValues[1]
            val side = when (abbr) {
                away.abbrev.uppercase() -> Side.AWAY
                home.abbrev.uppercase() -> Side.HOME
                else -> null
            }
            if (yard != null && yard in 1..50 && (side != null || yard == 50)) {
                return FieldPos(if (yard == 50) null else side, yard)
            }
        }
        val yl = sit.int("yardLine") ?: return null
        return when {
            yl in 1..49 -> FieldPos(Side.HOME, yl)
            yl == 50 -> FieldPos(null, 50)
            yl in 51..99 -> FieldPos(Side.AWAY, 100 - yl)
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    private const val REG_PERIODS = 4
    private const val OT_FIRST = 5

    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    private fun easternDay(utcMs: Long): String = Instant.ofEpochMilli(utcMs).atZone(EASTERN).toLocalDate().toString()

    /** ESPN writes "2026-11-08T14:30Z" (no seconds); both forms are accepted. */
    private fun parseUtc(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(s).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** "14:54" → 894. */
    private fun parseMmSs(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        if (m < 0 || sec !in 0..59) return null
        return m * 60 + sec
    }

    // ---- lenient leaves (ruling 21): a string or a number; anything else is null for that field only.

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

    /** A string, or a number's text ("1" for `1`). */
    private fun JsonElement?.str(): String? = prim()?.content?.takeIf { it.isNotEmpty() }

    /** An integer from a number or a string ("7", 7, 7.0). */
    private fun JsonElement?.int(): Int? {
        val c = prim()?.content?.trim() ?: return null
        c.toIntOrNull()?.let { return it }
        val d = c.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt() else null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = this[key].str()
    private fun JsonObject.int(key: String): Int? = this[key].int()
    private fun JsonObject.double(key: String): Double? = this[key].prim()?.content?.trim()?.toDoubleOrNull()
    private fun JsonObject.bool(key: String): Boolean? = this[key].prim()?.content?.toBooleanStrictOrNull()
}
