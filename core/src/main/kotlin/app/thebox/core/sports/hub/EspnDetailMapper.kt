package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.floor

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §2, §3.5, §3.6, §5.3, §6.1; P1) ESPN's game summary → the NFL [GameDetail] and
 * [BoxScore]. Pure: no I/O, no clock, never throws on a partial or odd body.
 *
 * - **Sides by team id** (§1, arch m1). The header's `competitors[].homeAway` names the two team ids; every other section
 *   (`boxscore.teams` / `players`, `leaders`, `injuries`, `lastFiveGames`, `scoringPlays`) is mapped through that map and
 *   never by index, because the sections disagree on order (header, leaders and injuries home first; boxscore away
 *   first). An entry whose team id is neither side is dropped. No code depends on key order.
 * - **Partial is fine.** Only the header is required ([detail] / [box] answer null without two distinct sides or a state);
 *   every other section that is absent or odd leaves its field empty, and the caller decides (§5.3: null is `BAD_BODY`).
 * - **What the NFL detail leaves empty** (§6.1): `awayScore` / `homeScore` (the board's score is the headline), `recapId`
 *   / `condensedId` (the highlight is in [GameDetail.clips], arch M7), `goals`, `stars`, `goalies`, `series`, `season`,
 *   `periods` (the box score uses the board's quarters) and `broadcasts`. `serverDateMs` and `clockSkewMs` are the
 *   transport's (`EspnDetailSource` sets them); the mapper leaves them null / 0.
 * - No odds, spread, win probability or predictor field is read (§2.4); the DTO does not declare them.
 */
object EspnDetailMapper {
    /**
     * The detail of [id] from [dto]; clips are listed while not expired at [atMs] (`now + skew`; null skips the time
     * check, §4.3; an embargoed clip is kept, [videoEntries]). Null
     * when the summary is not usable (no header, no two sides by team id, no status state, or a header naming another
     * event). Media hosts must be https (the production rule); see the overload for the debug origin.
     */
    fun detail(dto: EspnSummaryDto, id: GameId, atMs: Long?): GameDetail? = detail(dto, id, atMs, httpOk = false)

    /**
     * [detail] with [httpOk]: plain-http clip and image URLs are accepted, as `EspnVideoMapper` does for the debug
     * override origin (the fake panel). (§17.1 P1 note 2: the frozen three-argument form cannot say so.)
     */
    fun detail(dto: EspnSummaryDto, id: GameId, atMs: Long?, httpOk: Boolean): GameDetail? {
        val header = dto.header ?: return null
        header.id.str()?.let { if (it != id.id) return null }
        val comp = header.competitions?.firstOrNull() ?: return null
        val sides = sides(comp) ?: return null
        val phase = phase(comp.status?.type) ?: return null
        val pre = phase == GamePhase.SCHEDULED || phase == GamePhase.PRE
        val recent = recent(dto.lastFiveGames, sides)
        val (injuries, injuryCounts) = injuries(dto.injuries, sides)
        return GameDetail(
            id = id,
            fetchedPhase = phase,
            serverDateMs = null,
            awayScore = null,
            homeScore = null,
            venue = dto.gameInfo?.venue?.fullName?.trim()?.takeIf { it.isNotEmpty() },
            gameStats = if (pre) emptyList() else gameStats(dto.boxscore?.teams, sides),
            season = null,
            periods = emptyList(),
            goals = emptyList(),
            stars = emptyList(),
            leaders = leaders(dto.leaders, sides, httpOk),
            goalies = null,
            series = null,
            recapId = null,
            condensedId = null,
            broadcasts = emptyList(),
            plays = plays(dto.scoringPlays, sides),
            seasonStats = if (pre) seasonStats(comp, dto.boxscore?.teams, recent, sides) else emptyList(),
            clips = clips(dto.videos, atMs, httpOk),
            recapHeadline = dto.article?.takeIf { it.type?.trim().equals(RECAP_ARTICLE, ignoreCase = true) }
                ?.headline?.trim()?.takeIf { it.isNotEmpty() },
            recent = recent,
            injuries = injuries,
            injuryCounts = injuryCounts,
        )
    }

    /**
     * The box score (§3.5): per team, the Passing, Rushing and Receiving tables in that order, each present only when
     * ESPN lists the category, with at most `HUB_NFL_BOX_LINES` athletes in ESPN's order. [BoxTable.columns] are ESPN's
     * stat keys (`completions/passingAttempts`, `QBRating`, …), the one representation; the sheet's words are UI
     * resources (`boxColumnLabel`, integration §18). Sides from the header by team
     * id. Null only when the header has no two sides; a summary without `boxscore.players` (pre-game, the first snap)
     * answers empty tables, not null.
     */
    fun box(dto: EspnSummaryDto): BoxScore? {
        val comp = dto.header?.competitions?.firstOrNull() ?: return null
        val sides = sides(comp) ?: return null
        val away = ArrayList<BoxTable>()
        val home = ArrayList<BoxTable>()
        val seen = HashSet<Side>()
        for (team in dto.boxscore?.players.orEmpty()) {
            val side = sides[team.team?.id.str()] ?: continue
            if (!seen.add(side)) continue // a duplicated team entry: the first wins
            val tables = BOX_SPECS.mapNotNull { spec -> table(spec, team.statistics) }
            (if (side == Side.AWAY) away else home) += tables
        }
        return BoxScore(TeamBox(emptyList(), emptyList(), away), TeamBox(emptyList(), emptyList(), home))
    }

    /**
     * (§2.4) The name drawn for a leader: ESPN's `shortName` after the first ". ", which keeps suffixes and particles
     * ("J. Cook III" → "Cook III", "A. St. Brown" → "St. Brown", "C.J. Stroud" → "Stroud"); the whole name without one.
     * Never `substringAfterLast(' ')`. [Leader.name] holds ESPN's `shortName` as sent, so this is applied exactly once,
     * where the name is drawn (§17.1 P1 note 3).
     */
    fun leaderSurname(shortName: String): String {
        val s = shortName.trim()
        val i = s.indexOf(". ")
        return if (i < 0) s else s.substring(i + 2).trim().ifEmpty { s }
    }

    // ------------------------------------------------------------------ header: sides and phase

    /** team id → side, from the header's `homeAway`; null unless exactly one distinct away and one home id. */
    private fun sides(comp: EspnSumCompetitionDto): Map<String, Side>? {
        var away: String? = null
        var home: String? = null
        for (c in comp.competitors.orEmpty()) {
            val teamId = c.team?.id.str() ?: c.id.str() ?: continue
            when (c.homeAway?.trim()?.lowercase()) {
                "away" -> if (away == null) away = teamId else return null
                "home" -> if (home == null) home = teamId else return null
            }
        }
        if (away == null || home == null || away == home) return null
        return mapOf(away to Side.AWAY, home to Side.HOME)
    }

    /**
     * The summary header's phase: "pre" → SCHEDULED (the board decides PRE; the policy takes the further along of the
     * two), "in" → LIVE, or INTERMISSION for `STATUS_HALFTIME` / `STATUS_END_PERIOD`, "post" → FINAL; a postponed or
     * cancelled name → POSTPONED. Null without a known state.
     */
    private fun phase(type: EspnSumStatusTypeDto?): GamePhase? {
        val name = type?.name?.trim()?.uppercase()
        if (name == "STATUS_POSTPONED" || name == "STATUS_CANCELED" || name == "STATUS_CANCELLED") return GamePhase.POSTPONED
        return when (type?.state?.trim()?.lowercase()) {
            "pre" -> GamePhase.SCHEDULED
            "in" -> if (name == "STATUS_HALFTIME" || name == "STATUS_END_PERIOD") GamePhase.INTERMISSION else GamePhase.LIVE
            "post" -> GamePhase.FINAL
            else -> null
        }
    }

    // ------------------------------------------------------------------ stat rows (§2.2)

    private enum class Parse { COUNT, FRACTION_DASH, CLOCK, DECIMAL }

    /** Live and final team totals, in the panel's order; first downs and possession are drawn on finals only (UI). */
    private val GAME_STATS = listOf(
        "totalYards" to (StatKind.TOTAL_YARDS to Parse.COUNT),
        "netPassingYards" to (StatKind.PASS_YARDS to Parse.COUNT),
        "rushingYards" to (StatKind.RUSH_YARDS to Parse.COUNT),
        "firstDowns" to (StatKind.FIRST_DOWNS to Parse.COUNT),
        "thirdDownEff" to (StatKind.THIRD_DOWN to Parse.FRACTION_DASH),
        "turnovers" to (StatKind.TURNOVERS to Parse.COUNT),
        "possessionTime" to (StatKind.POSSESSION to Parse.CLOCK),
    )

    private val SEASON_AVERAGES = listOf(
        "totalPointsPerGame" to StatKind.POINTS_PG,
        "totalPointsPerGameAllowed" to StatKind.POINTS_ALLOWED_PG,
        "yardsPerGame" to StatKind.YARDS_PG,
        "yardsPerGameAllowed" to StatKind.YARDS_ALLOWED_PG,
    )

    /**
     * A row is emitted only when both sides parse; the UI draws "–" for a kind the list lacks, so each phase keeps its
     * row count (§2.2). A duplicated stat name (the final's two `interceptions`) is read once: the first wins.
     */
    private fun gameStats(teams: List<EspnBoxTeamDto>?, sides: Map<String, Side>): List<StatRow> {
        val (away, home) = statsBySide(teams, sides)
        return GAME_STATS.mapNotNull { (name, kp) ->
            val a = away[name]?.let { parse(it, kp.second) } ?: return@mapNotNull null
            val h = home[name]?.let { parse(it, kp.second) } ?: return@mapNotNull null
            StatRow(kp.first, a, h)
        }
    }

    /** Pre-game rows in order: Record, Last 5, then the four per-game averages. */
    private fun seasonStats(
        comp: EspnSumCompetitionDto,
        teams: List<EspnBoxTeamDto>?,
        recent: List<RecentGame>,
        sides: Map<String, Side>,
    ): List<StatRow> {
        val out = ArrayList<StatRow>()
        val records = HashMap<Side, String>()
        for (c in comp.competitors.orEmpty()) {
            val side = sides[c.team?.id.str() ?: c.id.str()] ?: continue
            val recs = c.record.orEmpty()
            val total = recs.firstOrNull { it.type?.trim() == "total" } ?: recs.firstOrNull()
            total?.summary?.trim()?.takeIf { RECORD_TEXT.matches(it) }?.let { records.putIfAbsent(side, it) }
        }
        row(StatKind.RECORD, records[Side.AWAY], records[Side.HOME])?.let(out::add)
        row(StatKind.LAST_FIVE, lastFive(recent, Side.AWAY), lastFive(recent, Side.HOME))?.let(out::add)
        val (away, home) = statsBySide(teams, sides)
        for ((name, kind) in SEASON_AVERAGES) {
            val a = away[name]?.let { parse(it, Parse.DECIMAL) } ?: continue
            val h = home[name]?.let { parse(it, Parse.DECIMAL) } ?: continue
            out += StatRow(kind, a, h)
        }
        return out
    }

    private fun row(kind: StatKind, away: String?, home: String?): StatRow? =
        if (away == null || home == null) null else StatRow(kind, StatValue.Text(away), StatValue.Text(home))

    /** "3-2", or "2-2-1" with a tie (§2.2), from the team's recorded results; null without any. */
    private fun lastFive(recent: List<RecentGame>, side: Side): String? {
        val mine = recent.filter { it.side == side }
        if (mine.isEmpty()) return null
        val w = mine.count { it.result == GameResult.W }
        val l = mine.count { it.result == GameResult.L }
        val t = mine.count { it.result == GameResult.T }
        return if (t > 0) "$w-$l-$t" else "$w-$l"
    }

    private fun statsBySide(teams: List<EspnBoxTeamDto>?, sides: Map<String, Side>): Pair<Map<String, String>, Map<String, String>> {
        val by = HashMap<Side, HashMap<String, String>>()
        for (t in teams.orEmpty()) {
            val side = sides[t.team?.id.str()] ?: continue
            if (side in by) continue
            val m = HashMap<String, String>()
            for (s in t.statistics.orEmpty()) {
                val name = s.name?.trim() ?: continue
                val v = s.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                m.putIfAbsent(name, v)
            }
            by[side] = m
        }
        return (by[Side.AWAY].orEmpty()) to (by[Side.HOME].orEmpty())
    }

    private fun parse(text: String, how: Parse): StatValue? = when (how) {
        Parse.COUNT -> text.replace(",", "").toIntOrNull()?.let { StatValue.Count(it) }
        Parse.FRACTION_DASH -> {
            val parts = text.split('-')
            val made = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val of = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parts.size == 2 && made != null && of != null && made >= 0 && of >= made) StatValue.Fraction(made, of, dash = true) else null
        }
        Parse.CLOCK -> {
            val parts = text.split(':')
            val m = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val s = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parts.size == 2 && m != null && s != null && m >= 0 && s in 0..59) StatValue.Clock(m * 60 + s) else null
        }
        Parse.DECIMAL -> {
            val clean = text.replace(",", "")
            val v = clean.toDoubleOrNull()?.takeIf { it.isFinite() }
            val dot = clean.indexOf('.')
            v?.let { StatValue.Decimal(it, if (dot < 0) 0 else clean.length - dot - 1) }
        }
    }

    // ------------------------------------------------------------------ scoring plays (§3.1, §6.1)

    /**
     * `scoringPlays[]` in game order, at most `HUB_NFL_MAX_PLAYS` (the latest kept). The side is the play's `team.id`;
     * without one, the side whose score went up since the previous play. A play with no side, period or scores is
     * dropped. [PlayScore]: "FG" → FG; "SF" → SAFETY; "TD" → DEF_TD for type 36 or a text naming an interception or
     * fumble return, else TD; anything else → OTHER (§16 #1).
     */
    private fun plays(list: List<EspnScoringPlayDto>?, sides: Map<String, Side>): List<ScoringPlay> {
        val out = ArrayList<ScoringPlay>()
        var lastAway = 0
        var lastHome = 0
        list.orEmpty().forEachIndexed { i, p ->
            val away = p.awayScore.int()?.takeIf { it >= 0 } ?: return@forEachIndexed
            val home = p.homeScore.int()?.takeIf { it >= 0 } ?: return@forEachIndexed
            val side = sides[p.team?.id.str()] ?: when {
                away > lastAway && home == lastHome -> Side.AWAY
                home > lastHome && away == lastAway -> Side.HOME
                else -> null
            }
            lastAway = away
            lastHome = home
            side ?: return@forEachIndexed
            val n = p.period?.number.int()?.takeIf { it >= 1 } ?: return@forEachIndexed
            val period = if (n > REG_PERIODS) PeriodLabel(PeriodKind.OT, n - REG_PERIODS) else PeriodLabel(PeriodKind.REG, n)
            out += ScoringPlay(
                id = p.id.str() ?: "play-$i",
                period = period,
                clock = p.clock?.displayValue?.trim().orEmpty(),
                side = side,
                kind = playScore(p.type),
                text = p.text?.trim().orEmpty(),
                away = away,
                home = home,
            )
        }
        return if (out.size > SportsConstants.HUB_NFL_MAX_PLAYS) out.takeLast(SportsConstants.HUB_NFL_MAX_PLAYS) else out
    }

    private fun playScore(t: EspnPlayTypeDto?): PlayScore = when (t?.abbreviation?.trim()?.uppercase()) {
        "FG" -> PlayScore.FG
        "SF" -> PlayScore.SAFETY
        "TD" -> {
            val text = t.text.orEmpty()
            val defensive = t.id.str() == TYPE_INTERCEPTION_RETURN_TD ||
                text.contains("Interception Return", ignoreCase = true) || text.contains("Fumble Return", ignoreCase = true)
            if (defensive) PlayScore.DEF_TD else PlayScore.TD
        }
        else -> PlayScore.OTHER
    }

    // ------------------------------------------------------------------ leaders (§2.4, §3.6)

    private val LEADER_CATEGORIES = listOf("passingYards", "rushingYards", "receivingYards", "sacks", "totalTackles")

    /**
     * One [LeaderPair] per known category that has a leader on either side, in [LEADER_CATEGORIES] order. Game leaders
     * after kick-off, season leaders before (the same section). [Leader.value] is the whole part of ESPN's `value`;
     * [Leader.line] is ESPN's `displayValue`, the only source of a fractional value (5.5 sacks, arch m4).
     */
    private fun leaders(list: List<EspnTeamLeadersDto>?, sides: Map<String, Side>, httpOk: Boolean): List<LeaderPair> {
        val by = HashMap<Side, Map<String, Leader>>()
        for (team in list.orEmpty()) {
            val side = sides[team.team?.id.str()] ?: continue
            if (side in by) continue
            val m = HashMap<String, Leader>()
            for (cat in team.leaders.orEmpty()) {
                val name = cat.name?.trim()?.takeIf { it in LEADER_CATEGORIES } ?: continue
                val l = cat.leaders?.firstOrNull() ?: continue
                val who = l.athlete?.shortName?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val line = l.displayValue?.trim()?.takeIf { it.isNotEmpty() }
                // (Integration §18) Live values can be negative ("3 CAR, - YDS" with value -8.0, recorded 2026-11-25):
                // kept, not dropped; ESPN's displayValue then has no number, so the value is the only source.
                val value = l.value.double()?.let { floor(it) }?.takeIf { it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
                    ?: line?.substringBefore(',')?.replace(",", "")?.toDoubleOrNull()?.let { floor(it).toInt() }
                    ?: continue
                m.putIfAbsent(name, Leader(who, value, l.athlete?.headshot?.href?.trim()?.takeIf { usable(it, httpOk) }, line))
            }
            by[side] = m
        }
        return LEADER_CATEGORIES.mapNotNull { c ->
            val a = by[Side.AWAY]?.get(c)
            val h = by[Side.HOME]?.get(c)
            if (a == null && h == null) null else LeaderPair(c, a, h)
        }
    }

    // ------------------------------------------------------------------ clips (§4.2, §4.3)

    /**
     * The RECAP and PLAY clips through [EspnVideoMapper], one per video id: the highlight first, then the single plays
     * oldest first (`originalPublishDate`), at most `HUB_NFL_MAX_CLIPS`. (Code review 2026-11-26, data #3) At [atMs] only
     * the **expiry** is checked ([ClipOffer.listed]): a clip still under embargo is kept, so the view model's offer tick
     * can reveal it the moment the embargo lifts; every UI use and the resolver check [ClipOffer.offered] (embargo too).
     * An expired, non-settop or non-https clip is never listed. (Data #5) `EspnDetailSource` fills the catalog from this
     * same selection, so the highlight is always in both.
     */
    fun videoEntries(list: List<EspnVideoDto>?, atMs: Long?, httpOk: Boolean): List<VideoEntry> {
        val seen = HashSet<Long>()
        val all = list.orEmpty().asSequence()
            .mapNotNull { EspnVideoMapper.entry(it, null, httpOk) }
            .filter { atMs == null || ClipOffer.listed(it.clip.expiresMs, atMs) }
            .filter { seen.add(it.clip.videoId) }
            .toList()
        val ordered = all.filter { it.clip.kind == ClipKind.RECAP } +
            all.filter { it.clip.kind != ClipKind.RECAP }.sortedBy { it.clip.publishedMs ?: Long.MAX_VALUE }
        return ordered.take(SportsConstants.HUB_NFL_MAX_CLIPS)
    }

    private fun clips(list: List<EspnVideoDto>?, atMs: Long?, httpOk: Boolean): List<GameClip> =
        videoEntries(list, atMs, httpOk).map { it.clip }

    // ------------------------------------------------------------------ pre-game: last five, injuries (§3.6)

    /** Both teams' `lastFiveGames`, away first, each oldest first as ESPN lists it; an event without a result or opponent is dropped. */
    private fun recent(list: List<EspnLastFiveDto>?, sides: Map<String, Side>): List<RecentGame> {
        val by = HashMap<Side, List<RecentGame>>()
        for (team in list.orEmpty()) {
            val side = sides[team.team?.id.str()] ?: continue
            if (side in by) continue
            by[side] = team.events.orEmpty().mapNotNull { e ->
                val result = when (e.gameResult?.trim()?.uppercase()) {
                    "W" -> GameResult.W
                    "L" -> GameResult.L
                    "T" -> GameResult.T
                    else -> return@mapNotNull null
                }
                val opp = e.opponent?.abbreviation?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RecentGame(side, e.atVs?.trim() == "@", opp, result, e.score?.trim().orEmpty(), e.gameDate?.let(::parseUtc))
            }.take(RECENT_MAX)
        }
        return by[Side.AWAY].orEmpty() + by[Side.HOME].orEmpty()
    }

    /**
     * Out, Doubtful and Questionable lines (Injured Reserve and any other status are left out), away first, in ESPN's
     * order. Each team keeps the `HUB_NFL_INJURIES_PER_TEAM` lines the sheet draws; the second value counts every
     * qualifying line per side, for its "+n more" (§5.3 "all counted"; integration §18, replacing §17.1 P1 note 4's floor).
     */
    private fun injuries(list: List<EspnTeamInjuriesDto>?, sides: Map<String, Side>): Pair<List<InjuryLine>, Map<Side, Int>> {
        val by = HashMap<Side, List<InjuryLine>>()
        val counts = HashMap<Side, Int>()
        for (team in list.orEmpty()) {
            val side = sides[team.team?.id.str()] ?: continue
            if (side in by) continue
            by[side] = team.injuries.orEmpty().mapNotNull { inj ->
                val status = when (inj.status?.trim()?.lowercase()) {
                    "out" -> InjuryStatus.OUT
                    "doubtful" -> InjuryStatus.DOUBTFUL
                    "questionable" -> InjuryStatus.QUESTIONABLE
                    else -> return@mapNotNull null
                }
                val name = inj.athlete?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                InjuryLine(
                    side, name,
                    inj.athlete?.position?.abbreviation?.trim()?.takeIf { it.isNotEmpty() },
                    status,
                    inj.details?.type?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.also { counts[side] = it.size }.take(SportsConstants.HUB_NFL_INJURIES_PER_TEAM)
        }
        return (by[Side.AWAY].orEmpty() + by[Side.HOME].orEmpty()) to counts
    }

    // ------------------------------------------------------------------ box tables (§3.5)

    /** [keys]: ESPN's stat keys, which are also [BoxTable.columns] (stable keys; the UI words them, integration §18). */
    private class BoxSpec(val category: BoxCategory, val espnName: String, val keys: List<String>)

    private val BOX_SPECS = listOf(
        BoxSpec(
            BoxCategory.PASSING, "passing",
            listOf("completions/passingAttempts", "passingYards", "passingTouchdowns", "interceptions", "QBRating"),
        ),
        BoxSpec(
            BoxCategory.RUSHING, "rushing",
            listOf("rushingAttempts", "rushingYards", "yardsPerRushAttempt", "rushingTouchdowns", "longRushing"),
        ),
        BoxSpec(
            BoxCategory.RECEIVING, "receiving",
            listOf("receptions", "receivingYards", "yardsPerReception", "receivingTouchdowns", "longReception"),
        ),
    )

    /** A value ESPN did not send for a column. */
    private const val NO_VALUE = "–"

    /** The table of [spec] from the team's categories; null when ESPN lists no such category. Values picked by key. */
    private fun table(spec: BoxSpec, cats: List<EspnBoxCategoryDto>?): BoxTable? {
        val cat = cats.orEmpty().firstOrNull { it.name?.trim() == spec.espnName } ?: return null
        val keys = cat.keys.orEmpty().map { it.trim() }
        val idx = spec.keys.map { keys.indexOf(it) }
        val lines = cat.athletes.orEmpty().mapNotNull { a ->
            val name = a.athlete?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val stats = a.stats.orEmpty()
            BoxLine(name, idx.map { i -> stats.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: NO_VALUE })
        }.take(SportsConstants.HUB_NFL_BOX_LINES)
        return BoxTable(spec.category, spec.keys, lines)
    }

    // ------------------------------------------------------------------ helpers

    private const val REG_PERIODS = 4
    private const val RECAP_ARTICLE = "Recap"
    private const val TYPE_INTERCEPTION_RETURN_TD = "36"
    private const val RECENT_MAX = 5
    private val RECORD_TEXT = Regex("\\d{1,2}-\\d{1,2}(-\\d{1,2})?")

    private fun usable(url: String, httpOk: Boolean): Boolean =
        url.startsWith("https://", ignoreCase = true) || (httpOk && url.startsWith("http://", ignoreCase = true))

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

    /** A string, or a number's text ("28" for `28`). */
    private fun JsonElement?.str(): String? = prim()?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.double(): Double? = prim()?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A whole number from a number or a string (7, "7", 7.0); else null. */
    private fun JsonElement?.int(): Int? {
        val d = double() ?: return null
        return if (d == floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) d.toInt() else null
    }

    private fun parseUtc(s: String): Long? = try {
        OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(s.trim()).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
