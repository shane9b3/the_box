package app.thebox.core.sports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * (CHL §3) HockeyTech bodies → the normalised types. Pure: no I/O, no clock. Every field is optional and lenient (§3.1);
 * an unmappable body yields null, which the source turns into `FailKind.BAD_BODY`. The signatures are the commit-0
 * contract (with the types in `HockeyTechContracts.kt`); WP-1 fills the bodies without changing them.
 *
 * Leniency (§3.1): ints may be JSON numbers or strings (`2`, `"2"`, `"02"`); booleans `true`/`false`, `1`/`0`,
 * `"1"`/`"0"`; clocks `"mm:ss"` or `"hh:mm:ss"`, `"-"` = unknown; codes `trim().uppercase(Locale.ROOT)` gated to
 * `[A-Z0-9]{2,4}`. No English text is read (no `ProgressString`, `game_status`, `PeriodLongName`). Malformed input never
 * throws: a field that does not parse is absent.
 */
object HockeyTechMapper {
    /**
     * (§3.2, §2.3) A `gamesbydate` body for [dayKey] of [league]: the board (card names from [teams] by team id, else the
     * body's nickname; logos from [teams]), each game's facts, with [playoffBySeason] (`seasons`, season id → playoff) when
     * known. Null = unmappable.
     */
    fun board(
        league: League,
        dayKey: String,
        body: JsonObject,
        teams: Map<String, ChlTeam>?,
        playoffBySeason: Map<String, Boolean>?,
    ): ChlBoard? {
        val kit = body["SiteKit"] as? JsonObject ?: return null
        val day = parseDate(dayKey) ?: return null
        // An absent or null list is a day without games; any other shape is unmappable.
        val list = when (val g = kit["Gamesbydate"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> g
            else -> return null
        }
        val games = ArrayList<GameSnapshot>()
        val facts = LinkedHashMap<String, ChlGameFacts>()
        val seasons = LinkedHashSet<String>()
        for (el in list) {
            val e = el as? JsonObject ?: continue
            val mapped = boardGame(league, day, e, teams, playoffBySeason) ?: continue
            if (facts.containsKey(mapped.second.gameId)) continue
            games += mapped.first
            facts[mapped.second.gameId] = mapped.second
            mapped.second.seasonId?.let(seasons::add)
        }
        return ChlBoard(
            DayBoard(day.toString(), day.minusDays(1).toString(), day.plusDays(1).toString(), games, league),
            facts,
            seasons,
        )
    }

    /**
     * (§3.5, §6.1) A `teamsbyseason` body → teams by team id. [logoPrefix] is the league's validated logo folder
     * (`{logoBase}{client}/logos/`); a logo URL outside it, or not matching `[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)`, is no logo;
     * a JPEG becomes `{logoPrefix}{teamId}.png`. Null = unmappable.
     */
    fun teams(body: JsonObject, logoPrefix: String): Map<String, ChlTeam>? {
        val kit = body["SiteKit"] as? JsonObject ?: return null
        val list = kit["Teamsbyseason"] as? JsonArray ?: return null
        val out = LinkedHashMap<String, ChlTeam>()
        for (el in list) {
            val t = el as? JsonObject ?: continue
            val id = t.str("id")?.takeIf { DIGITS.matches(it) } ?: continue
            val code = code(t.str("code")) ?: continue
            val nickname = t.str("nickname")
            val full = fullName(t.str("city"), nickname, t.str("name"), code)
            out[id] = ChlTeam(id, code, full, nickname ?: full, logo(t.str("team_logo_url"), logoPrefix, id))
        }
        return out
    }

    /** (§2.3) A `seasons` body → season id → playoff (`playoff == "1"`). Null = unmappable. */
    fun seasons(body: JsonObject): Map<String, Boolean>? {
        val kit = body["SiteKit"] as? JsonObject ?: return null
        val list = kit["Seasons"] as? JsonArray ?: return null
        val out = LinkedHashMap<String, Boolean>()
        for (el in list) {
            val s = el as? JsonObject ?: continue
            val id = s.str("season_id")?.takeIf { DIGITS.matches(it) } ?: continue
            // (WP-1 fix pass L7) A missing flag is unknown: no entry, so the board entry's own `playoff` decides.
            out[id] = s.bool("playoff") ?: continue
        }
        return out
    }

    /**
     * (§3.3, §3.4, §5) The followed game of [league] from one live poll's [nodes] and the board's [facts]: scores, shots,
     * period, clock (incl. the "20:00" break form, S12, and "End 3rd", S3), shootout, final kind, `lastGoal`, and the
     * derived `powerPlay` / `powerPlayUnknown` (`ChlPowerPlay.derive`). Null = unmappable (the code gate, a required node
     * unmappable): the source answers `BAD_BODY`.
     *
     * `runningclock` is required; `goalssummary` is required once the game has started (before that, a `null` summary
     * maps to a SCHEDULED snapshot with no score). [ChlGameFacts] carries no "Time TBD" flag, so a followed snapshot's
     * `startTbd` is always false (the board's own snapshot carries it).
     */
    fun followed(league: League, facts: ChlGameFacts, nodes: ChlNodes): GameSnapshot? {
        val gid = facts.gameId.takeIf { DIGITS.matches(it) } ?: return null
        val awayCode = code(facts.away.abbrev) ?: return null
        val homeCode = code(facts.home.abbrev) ?: return null
        val rc = nodes.runningclock ?: return null
        val pc = nodes.publishedclock
        val gs = nodes.goalssummary
        val clockObj = rc["Clock"] as? JsonObject

        val rcStatus = rc.int("status_id")
        val pcStatus = pc?.int("StatusId")
        val status = pcStatus ?: rcStatus ?: facts.status
        val final = pc?.bool("Final") == true || facts.final
        val awayScore = gs?.int("VisitorGoalTotal")?.takeIf { it >= 0 }
        val homeScore = gs?.int("HomeGoalTotal")?.takeIf { it >= 0 }
        val started = pc?.bool("Started") == true || (awayScore ?: 0) + (homeScore ?: 0) > 0 ||
            listOf(pcStatus, rcStatus, facts.status).any(::startedStatus)

        val feedPeriod = clockObj?.int("period") ?: pc?.int("PeriodId")
        val remaining = clockObj?.let { remaining(it["Minutes"], it["Seconds"]) }
        val running = clockObj?.bool("Running") ?: false
        val shootoutFlag = gs?.bool("Shootout") == true || attempts(nodes.shootouts)
        val lengthOf = periodLengths(facts)

        val c = classify(
            status, final, started, feedPeriod, remaining, running, awayScore, homeScore, shootoutFlag, lengthOf,
            regularSeason(facts),
        )
        val scheduled = c.phase == GamePhase.SCHEDULED
        if (!scheduled && (awayScore == null || homeScore == null)) return null

        val aScore = if (scheduled) null else awayScore
        val hScore = if (scheduled) null else homeScore
        val shots = nodes.shotssummary
        val away = TeamLine(
            awayCode, facts.away.name, aScore, facts.away.logo, shortName = null,
            sog = if (scheduled) null else shots?.int("VisitorShotTotal")?.takeIf { it in 0..SportsConstants.SOG_MAX },
        )
        val home = TeamLine(
            homeCode, facts.home.name, hScore, facts.home.logo, shortName = null,
            sog = if (scheduled) null else shots?.int("HomeShotTotal")?.takeIf { it in 0..SportsConstants.SOG_MAX },
        )

        val pp = powerPlay(c, feedPeriod, awayScore, homeScore, gs, nodes, lengthOf)
        return GameSnapshot(
            id = GameId(league, gid),
            dayKey = facts.dayKey,
            startUtcMs = facts.startUtcMs,
            phase = c.phase,
            away = away,
            home = home,
            period = c.period,
            periodKind = c.periodKind,
            otNumber = c.otNumber,
            clock = c.clock,
            finalKind = c.finalKind,
            powerPlay = (pp as? ChlPp.Shown)?.pp,
            lastGoal = if (scheduled) null else lastGoal(nodes.goals, aScore, hScore),
            detail = null,
            powerPlayUnknown = pp == ChlPp.Unknown,
        )
    }

    /**
     * (§2.3 step 4; CHL fix pass B4) Today's overlay: the `runningclock` and `goalssummary` date queries
     * (`DatePlayed == board.board.dayKey`) replace the clock, status and scores of the matching games, through §3.4's
     * clock table with each game's facts (the playoff flag for a tied 0:00 period 4, the OT length for the "20:00" break
     * form). Either query may be null (that part is skipped). The facts and season ids are returned unchanged.
     */
    fun overlay(board: ChlBoard, runningclock: JsonObject?, goalssummary: JsonObject?): ChlBoard {
        if (runningclock == null && goalssummary == null) return board
        val games = board.board.games.map { g ->
            val f = board.facts[g.id.id] ?: return@map g
            val rc = (runningclock?.get(g.id.id) as? JsonObject)?.takeIf { sameDay(it, f) }
            val gs = (goalssummary?.get(g.id.id) as? JsonObject)?.takeIf { sameDay(it, f) }
            if (rc == null && gs == null) g else overlayGame(g, f, rc, gs)
        }
        return board.copy(board = board.board.copy(games = games))
    }

    /**
     * (§2.4, §3.3 (b)/(c); CHL fix pass L6) Whether the next live poll also requests the `shootouts` node: [prev] (the
     * previous snapshot of this follow, null = none yet) was a possible shootout: `periodKind == SO`, or regular season
     * ([facts]`.playoff == false`), period 4, 0:00 stopped, LIVE, scores tied. The source adds the node only for the WHL and
     * the QMJHL (the OHL has none). Pure, so `:data` never re-implements §3.3 (c).
     */
    fun wantsShootouts(prev: GameSnapshot?, facts: ChlGameFacts): Boolean {
        prev ?: return false
        if (prev.phase == GamePhase.FINAL) return false
        if (prev.periodKind == PeriodKind.SO) return true
        val clock = prev.clock ?: return false
        val a = prev.away.score
        val h = prev.home.score
        return regularSeason(facts) && prev.phase == GamePhase.LIVE && prev.period == 4 &&
            clock.secondsRemaining == 0 && !clock.running && a != null && a == h
    }

    // ------------------------------------------------------------------ §3.2 one board game

    private fun boardGame(
        league: League,
        boardDay: LocalDate,
        e: JsonObject,
        teams: Map<String, ChlTeam>?,
        playoffBySeason: Map<String, Boolean>?,
    ): Pair<GameSnapshot, ChlGameFacts>? {
        val gid = e.str("game_id")?.takeIf { DIGITS.matches(it) } ?: return null
        val day = e.str("date_played")?.let(::parseDate) ?: boardDay
        val zone = zone(league)
        val tbd = e.bool("time_tbd") == true
        val time = if (tbd) null else e.str("schedule_time")?.let(::parseTime)
        val gameZone = e.str("timezone")?.let(::zoneOf) ?: zone
        val startTbd = time == null
        val start = if (time == null) day.atStartOfDay(zone) else day.atTime(time).atZone(gameZone)

        val seasonId = e.str("season_id")?.takeIf { DIGITS.matches(it) }
        val playoff = seasonId?.let { playoffBySeason?.get(it) } ?: e.bool("playoff")
        val lengths = periodsOf(e["periods"])
        val status = e.int("status")
        val final = e.bool("final") == true
        val awayGoals = e.int("visiting_goal_count")?.takeIf { it >= 0 }
        val homeGoals = e.int("home_goal_count")?.takeIf { it >= 0 }
        val started = e.bool("started") == true || (awayGoals ?: 0) + (homeGoals ?: 0) > 0 || startedStatus(status)

        val awayIds = side(e, "visiting_team", teams) ?: return null
        val homeIds = side(e, "home_team", teams) ?: return null

        val factsNoTeams = ChlGameFacts(
            gid, seasonId, day.toString(), start.toInstant().toEpochMilli(), awayIds.full, homeIds.full, playoff, lengths,
            status, final,
        )
        val c = classify(
            status, final, started, e.int("period"), e.str("game_clock")?.let(::parseClock), running = false,
            awayGoals, homeGoals, e.bool("shootout") == true, periodLengths(factsNoTeams), regularSeason(factsNoTeams),
        )
        val scheduled = c.phase == GamePhase.SCHEDULED
        val aScore = if (scheduled) null else awayGoals
        val hScore = if (scheduled) null else homeGoals
        val aSog = if (scheduled) null else e.int("visiting_shots")?.takeIf { it in 0..SportsConstants.SOG_MAX }
        val hSog = if (scheduled) null else e.int("home_shots")?.takeIf { it in 0..SportsConstants.SOG_MAX }
        val facts = factsNoTeams.copy(
            away = awayIds.full.copy(score = aScore, sog = aSog),
            home = homeIds.full.copy(score = hScore, sog = hSog),
        )
        val snap = GameSnapshot(
            id = GameId(league, gid),
            dayKey = facts.dayKey,
            startUtcMs = facts.startUtcMs,
            phase = c.phase,
            away = awayIds.card.copy(score = aScore, sog = aSog),
            home = homeIds.card.copy(score = hScore, sog = hSog),
            period = c.period,
            periodKind = c.periodKind,
            otNumber = c.otNumber,
            clock = c.clock,
            finalKind = c.finalKind,
            startTbd = startTbd,
        )
        return snap to facts
    }

    private class SideLines(val card: TeamLine, val full: TeamLine)

    /** A board side: the code (gated), the card name and the full name, the logo from [teams] by team id. */
    private fun side(e: JsonObject, prefix: String, teams: Map<String, ChlTeam>?): SideLines? {
        val teamId = e.str(prefix)
        val known = teamId?.let { teams?.get(it) }
        val code = code(e.str("${prefix}_code") ?: known?.code) ?: return null
        val nickname = e.str("${prefix}_nickname")
        val full = known?.fullName ?: fullName(e.str("${prefix}_city"), nickname, e.str("${prefix}_name"), code)
        val card = known?.cardName ?: nickname ?: full
        val logo = known?.logo
        return SideLines(TeamLine(code, card, null, logo), TeamLine(code, full, null, logo))
    }

    private fun overlayGame(g: GameSnapshot, f: ChlGameFacts, rc: JsonObject?, gs: JsonObject?): GameSnapshot {
        val clockObj = rc?.get("Clock") as? JsonObject
        val rcStatus = rc?.int("status_id")
        val status = rcStatus ?: f.status
        val awayGoals = gs?.int("VisitorGoalTotal")?.takeIf { it >= 0 } ?: g.away.score ?: f.away.score
        val homeGoals = gs?.int("HomeGoalTotal")?.takeIf { it >= 0 } ?: g.home.score ?: f.home.score
        // (WP-1 fix pass S3) A game the board already shows in play stays started (status 3 is unverified).
        val started = (awayGoals ?: 0) + (homeGoals ?: 0) > 0 || listOf(rcStatus, f.status).any(::startedStatus) ||
            g.phase != GamePhase.SCHEDULED
        val period: Int?
        val remaining: Int?
        val running: Boolean
        if (clockObj != null) {
            period = clockObj.int("period")
            remaining = remaining(clockObj["Minutes"], clockObj["Seconds"])
            running = clockObj.bool("Running") ?: false
        } else {
            // Scores only: the board's clock stays (already adjusted for the "20:00" form, which re-reads the same).
            period = g.period
            remaining = g.clock?.secondsRemaining
            running = g.clock?.running ?: false
        }
        val shootout = gs?.bool("Shootout") == true || (g.periodKind == PeriodKind.SO && clockObj == null)
        val c = classify(
            status, f.final, started, period, remaining, running, awayGoals, homeGoals, shootout, periodLengths(f),
            regularSeason(f),
        )
        val scheduled = c.phase == GamePhase.SCHEDULED
        return g.copy(
            phase = c.phase,
            away = g.away.copy(score = if (scheduled) null else awayGoals),
            home = g.home.copy(score = if (scheduled) null else homeGoals),
            period = c.period,
            periodKind = c.periodKind,
            otNumber = c.otNumber,
            clock = c.clock,
            finalKind = c.finalKind,
        )
    }

    private fun sameDay(o: JsonObject, f: ChlGameFacts): Boolean = o.str("DatePlayed")?.let { it == f.dayKey } ?: true

    // ------------------------------------------------------------------ §3.4 status, intermission and phase

    /** The §3.4 result: [period] after the S12 adjustment, [decided] = "End 3rd" / "End OT" (S3). */
    internal data class Classified(
        val phase: GamePhase,
        val period: Int?,
        val clock: GameClock?,
        val periodKind: PeriodKind?,
        val otNumber: Int?,
        val finalKind: PeriodKind?,
        val decided: Boolean = false,
        val shootout: Boolean = false,
    )

    /** Statuses that mean the game has started: in progress, final, forfeit. */
    private fun startedStatus(s: Int?): Boolean = s == 2 || s == 4 || s == 5

    internal fun classify(
        status: Int?,
        final: Boolean,
        started: Boolean,
        feedPeriod: Int?,
        remainingS: Int?,
        running: Boolean,
        away: Int?,
        home: Int?,
        shootout: Boolean,
        lengthOf: (Int) -> Int?,
        regularSeason: Boolean,
    ): Classified {
        val p = feedPeriod?.takeIf { it >= 1 }
        val len = p?.let(lengthOf)
        val r = remainingS?.takeIf { it >= 0 && it <= (len ?: SportsConstants.CHL_REG_PERIOD_S) }
        fun kind(q: Int) = if (q >= 4) PeriodKind.OT else PeriodKind.REG
        fun ot(q: Int) = if (q >= 4) q - 3 else null

        if (final || status == 4 || status == 5) {
            return Classified(
                GamePhase.FINAL, p, r?.let { GameClock(it, running = false, inIntermission = false) },
                if (shootout) PeriodKind.SO else p?.let(::kind),
                if (shootout) null else p?.let(::ot),
                when {
                    shootout -> PeriodKind.SO
                    (p ?: 0) >= 4 -> PeriodKind.OT
                    else -> PeriodKind.REG
                },
            )
        }
        val scheduled = Classified(GamePhase.SCHEDULED, null, null, null, null, null)
        if (status == 1) return scheduled
        if (status != 2 && !started) return scheduled

        // In play (status 2, or any other status once started: S6).
        if (p == null) return Classified(GamePhase.LIVE, null, null, null, null, null, shootout = shootout)
        if (shootout) {
            return Classified(
                GamePhase.LIVE, p, r?.let { GameClock(it, running, inIntermission = false) }, PeriodKind.SO, null, null,
                shootout = true,
            )
        }
        if (r == null) return Classified(GamePhase.LIVE, p, null, kind(p), ot(p), null)
        if (running) return Classified(GamePhase.LIVE, p, GameClock(r, running = true, inIntermission = false), kind(p), ot(p), null)
        val tied = away != null && home != null && away == home
        val differ = away != null && home != null && away != home
        return when {
            r == 0 && p >= 3 && differ ->
                Classified(GamePhase.LIVE, p, GameClock(0, running = false, inIntermission = false), kind(p), ot(p), null, decided = true)
            r == 0 && regularSeason && p == 4 && tied ->
                Classified(GamePhase.LIVE, p, GameClock(0, running = false, inIntermission = false), PeriodKind.SO, null, null, shootout = true)
            r == 0 ->
                Classified(GamePhase.INTERMISSION, p, GameClock(0, running = false, inIntermission = true), kind(p), ot(p), null)
            len != null && r == len && p >= 2 ->
                Classified(GamePhase.INTERMISSION, p - 1, GameClock(0, running = false, inIntermission = true), kind(p - 1), ot(p - 1), null)
            else -> Classified(GamePhase.LIVE, p, GameClock(r, running = false, inIntermission = false), kind(p), ot(p), null)
        }
    }

    // ------------------------------------------------------------------ §3.3 period lengths, playoff

    /** (§3.3) Periods 1–3: the board's length, else 1200. OT: the board's, else 300 (regular season) / 1200 (playoffs). */
    internal fun periodLengths(f: ChlGameFacts): (Int) -> Int? = { q ->
        val listed = f.periodLengthsS[q]?.takeIf { it in 1..MAX_PERIOD_S }
        when {
            q < 1 -> null
            listed != null -> listed
            q <= 3 -> SportsConstants.CHL_REG_PERIOD_S
            else -> when (f.playoff) {
                false -> SportsConstants.CHL_OT_REG_S
                true -> SportsConstants.CHL_OT_PLAYOFF_S
                null -> null
            }
        }
    }

    /** Regular season: the season says so, or (unknown) the board lists a 300 s OT. */
    internal fun regularSeason(f: ChlGameFacts): Boolean = when (f.playoff) {
        false -> true
        true -> false
        null -> f.periodLengthsS[4] == SportsConstants.CHL_OT_REG_S
    }

    private const val MAX_PERIOD_S = 3_600

    private fun periodsOf(el: JsonElement?): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        when (el) {
            is JsonObject -> for ((k, v) in el) {
                val n = k.trim().toIntOrNull() ?: (v as? JsonObject)?.int("id") ?: continue
                val len = (v as? JsonObject)?.int("length")?.takeIf { it in 1..MAX_PERIOD_S } ?: continue
                if (n >= 1) out[n] = len
            }
            is JsonArray -> for (v in el) {
                val o = v as? JsonObject ?: continue
                val n = o.int("id") ?: continue
                val len = o.int("length")?.takeIf { it in 1..MAX_PERIOD_S } ?: continue
                if (n >= 1) out[n] = len
            }
            else -> Unit
        }
        return out
    }

    // ------------------------------------------------------------------ §5 the power play

    private fun powerPlay(
        c: Classified,
        feedPeriod: Int?,
        away: Int?,
        home: Int?,
        gs: JsonObject?,
        nodes: ChlNodes,
        lengthOf: (Int) -> Int?,
    ): ChlPp {
        val inPlay = (c.phase == GamePhase.LIVE || c.phase == GamePhase.INTERMISSION) && !c.shootout &&
            c.periodKind != PeriodKind.SO
        // S3, and sudden death: once a period ≥ 4 has a different score the game is decided.
        val decided = c.decided || ((c.period ?: 0) >= 4 && away != null && home != null && away != home)
        if (!inPlay || decided) return ChlPp.None
        if (nodes.penaltiesFailed || nodes.goalsFailed) return ChlPp.Unknown // H8
        val period = c.period ?: return ChlPp.Unknown // H1 (the mapper's part, L7)
        val clock = c.clock ?: return ChlPp.Unknown
        if (period < 1 || feedPeriod == null) return ChlPp.Unknown
        val pens = nodes.penalties?.let { parsePenalties(it) }
        if (pens?.unplaceable == true) return ChlPp.Unknown // H2: a row the model needs, with no side or period
        val goals = nodes.goals?.let { parseGoals(it) }
        val total = (away ?: 0) + (home ?: 0)
        val goalTotal = if (gs?.bool("Shootout") == true && total > 0) total - 1 else total
        val ha = gs?.int("HomePowerPlayAttempts")?.takeIf { it >= 0 }
        val va = gs?.int("VisitorPowerPlayAttempts")?.takeIf { it >= 0 }
        val attempts = if (ha != null && va != null) ha + va else null
        val now = ChlNow(period, clock.secondsRemaining, feedPeriod, c.phase, shootout = false, decided = false)
        return ChlPowerPlay.derive(pens?.rows, goals, goalTotal, attempts, now, lengthOf)
    }

    internal class Penalties(val rows: List<ChlPenalty>, val unplaceable: Boolean)

    /**
     * A `penalties` node (`GamePenalties`, an object by `LSEventId` or an array) → rows. A row the derivation needs (flagged,
     * or unflagged 2 / 5 minutes for H9, or with unparsable minutes) without a parsable `Home` or `Period` makes the node
     * [Penalties.unplaceable] (H2); other unparsable rows are dropped.
     */
    internal fun parsePenalties(node: JsonObject): Penalties {
        val out = ArrayList<ChlPenalty>()
        var unplaceable = false
        for ((ordinal, entry) in entries(node["GamePenalties"]).withIndex()) {
            val (key, o) = entry
            val id = rowId(o, key, ordinal)
            val minutes = o.int("Minutes")
            val ppFlag = o.bool("PowerPlay") == true
            val penaltyShot = o.bool("PenaltyShot") == true
            val home = o.bool("Home")
            val period = o.int("Period")
            if (home == null || period == null) {
                if (!penaltyShot && (ppFlag || minutes == null || minutes == 2 || minutes == 5)) unplaceable = true
                continue
            }
            val startSet = o.bool("TimeStartSet") ?: true
            out += ChlPenalty(
                id = id,
                home = home,
                period = period,
                timeS = elapsed(o["Time"]),
                startS = if (startSet) elapsed(o["TimeStart"]) else null,
                onS = if (o.bool("TimeOnSet") == true) elapsed(o["TimeOn"]) else null,
                minutes = minutes,
                ppFlag = ppFlag,
                penaltyShot = penaltyShot,
                playerId = o.long("PenalizedPlayerId")?.takeIf { it != 0L },
            )
        }
        return Penalties(out, unplaceable)
    }

    /** A `goals` node (`GameGoals`) → rows; a goal without a parsable `IsHome` is dropped (H6 then sees the gap). */
    internal fun parseGoals(node: JsonObject): List<ChlGoal> {
        val out = ArrayList<ChlGoal>()
        for ((ordinal, entry) in entries(node["GameGoals"]).withIndex()) {
            val (key, o) = entry
            val home = o.bool("IsHome") ?: continue
            out += ChlGoal(
                id = rowId(o, key, ordinal),
                home = home,
                period = o.int("Period"),
                timeS = elapsed(o["Time"]),
                pp = o.bool("PowerPlay") == true,
                sh = o.bool("ShortHanded") == true,
                penaltyShot = o.bool("PenaltyShot") == true,
            )
        }
        return out
    }

    /**
     * (§3.3) The goal with the greatest `(Period, Time, LSEventId)`, if every goal parses and the per-side counts equal
     * the snapshot score (so a shootout final's +1, or a node behind the summary, gives null).
     */
    internal fun lastGoal(node: JsonObject?, away: Int?, home: Int?): GoalFact? {
        node ?: return null
        if (away == null || home == null) return null
        var best: Triple<Int, Int, Long>? = null
        var bestGoal: JsonObject? = null
        var a = 0
        var h = 0
        for ((key, o) in entries(node["GameGoals"])) {
            val isHome = o.bool("IsHome") ?: return null
            val period = o.int("Period") ?: return null
            val time = elapsed(o["Time"]) ?: return null
            val id = o.long("LSEventId") ?: key?.trim()?.toLongOrNull() ?: return null
            if (isHome) h++ else a++
            val k = Triple(period, time, id)
            val b = best
            if (b == null || compareValuesBy(k, b, { it.first }, { it.second }, { it.third }) > 0) {
                best = k
                bestGoal = o
            }
        }
        val g = bestGoal ?: return null
        if (a != away || h != home) return null
        return GoalFact(if (g.bool("IsHome") == true) Side.HOME else Side.AWAY, a, h, pp = g.bool("PowerPlay") == true)
    }

    /** (§3.3 (b)) A `shootouts` node with any attempt. */
    private fun attempts(node: JsonObject?): Boolean = when (val a = node?.get("ShootoutAttempts")) {
        is JsonObject -> a.values.any { it is JsonObject }
        is JsonArray -> a.any { it is JsonObject }
        else -> false
    }

    /**
     * A Firebase collection: an object keyed by id, or (Firebase's array form) a list with null holes, keyed by its index
     * (Firebase stores small integer keys as an array). (WP-1 fix pass L1) The key is the id when `LSEventId` is absent,
     * so two rows without one never share an id (and bench minors never chain).
     */
    private fun entries(el: JsonElement?): List<Pair<String?, JsonObject>> = when (el) {
        is JsonObject -> el.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
        is JsonArray -> el.mapIndexedNotNull { i, v -> (v as? JsonObject)?.let { i.toString() to it } }
        else -> emptyList()
    }

    /** `LSEventId`, else the collection key, else a negative ordinal: distinct for every row of a node. */
    private fun rowId(o: JsonObject, key: String?, ordinal: Int): Long =
        o.long("LSEventId") ?: key?.trim()?.toLongOrNull() ?: -(ordinal + 1L)

    // ------------------------------------------------------------------ §3.1 names, codes, logos

    private val CODE = Regex("[A-Z0-9]{2,4}")
    private val DIGITS = Regex("[0-9]{1,18}")
    private val LOGO_FILE = Regex("[0-9]+(_[0-9]+)?\\.(png|jpg|jpeg)")

    /** `trim().uppercase(Locale.ROOT)`, gated to `[A-Z0-9]{2,4}` (L2: a code is never a league). */
    internal fun code(raw: String?): String? = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { CODE.matches(it) }

    /** "City Nickname", else `name` with ", " → " " (the QMJHL's "Cape Breton, Eagles"), else the code. */
    internal fun fullName(city: String?, nickname: String?, name: String?, code: String): String = when {
        city != null && nickname != null -> "$city $nickname"
        name != null -> name.replace(", ", " ").replace(",", " ").trim().replace(Regex(" {2,}"), " ").ifEmpty { code }
        else -> code
    }

    /** (§3.5) The validated logo URL after the JPEG swap, or null. */
    internal fun logo(url: String?, prefix: String, teamId: String): String? {
        url ?: return null
        if (prefix.isEmpty() || !url.startsWith(prefix)) return null
        val file = url.substring(prefix.length)
        if (file.contains("..") || file.contains('?') || file.contains('#') || !LOGO_FILE.matches(file)) return null
        return if (file.endsWith(".png")) url else "$prefix$teamId.png"
    }

    // ------------------------------------------------------------------ dates and clocks

    private fun zone(league: League): ZoneId = SportsDays.zone(league)

    private fun zoneOf(id: String): ZoneId? = try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim())
    } catch (_: DateTimeException) {
        null
    }

    private fun parseTime(s: String): LocalTime? = try {
        LocalTime.parse(s.trim())
    } catch (_: DateTimeException) {
        null
    }

    /** `"mm:ss"` → s; minutes ≥ 0, seconds 0..59. */
    internal fun elapsed(el: JsonElement?): Int? {
        val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        val parts = s.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull()?.takeIf { it in 0..999 } ?: return null
        val sec = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return m * 60 + sec
    }

    /** A `game_clock`: `"00:02:04"` or `"02:04"` → s remaining. */
    internal fun parseClock(s: String): Int? {
        val parts = s.trim().split(':')
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        return when (nums.size) {
            2 -> if (nums[1] <= 59 && nums[0] <= 999) nums[0] * 60 + nums[1] else null
            3 -> if (nums[1] <= 59 && nums[2] <= 59 && nums[0] <= 9) nums[0] * 3600 + nums[1] * 60 + nums[2] else null
            else -> null
        }
    }

    /** `Clock.Minutes` / `Clock.Seconds` (two-digit strings or ints; `"-"` = unknown) → s remaining. */
    private fun remaining(minutes: JsonElement?, seconds: JsonElement?): Int? {
        val m = intOf(minutes)?.takeIf { it in 0..999 } ?: return null
        val s = intOf(seconds)?.takeIf { it in 0..59 } ?: return null
        return m * 60 + s
    }

    // ------------------------------------------------------------------ lenient JSON access

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = intOf(this[key])

    private fun JsonObject.long(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.trim().toLongOrNull()
    }

    private fun JsonObject.bool(key: String): Boolean? = boolOf(this[key])

    /** `2`, `"2"`, `"02"`, `2.0` → 2; anything else (null, `"-"`, objects, `2.5`) → null. */
    internal fun intOf(el: JsonElement?): Int? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val c = p.content.trim()
        c.toIntOrNull()?.let { return it }
        val d = c.toDoubleOrNull() ?: return null
        return if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** `true`/`false`, `1`/`0`, `"1"`/`"0"`, `"true"`/`"false"` → the boolean; anything else → null. */
    internal fun boolOf(el: JsonElement?): Boolean? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return when (p.content.trim().lowercase(Locale.ROOT)) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}
