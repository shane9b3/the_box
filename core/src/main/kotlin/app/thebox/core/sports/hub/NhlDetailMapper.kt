package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.NhlMapper
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.1, §4.2; WP-1) Maps `gamecenter/{id}/right-rail`, `/landing` and
 * `/boxscore` bodies, every field optional (a missing or odd field never fails the detail). Pure.
 *
 * Shapes (recorded 2026-11-23/24, `HubFixtures`):
 * - right-rail: `linescore.byPeriod[] {periodDescriptor, away, home}`, `teamGameStats[] {category, awayValue, homeValue}`
 *   (live and final), `teamSeasonStats.{away,home}Team` and `last10Record.{away,home}Team` (before the game only),
 *   `seasonSeries[]` + `seasonSeriesWins {awayTeamWins, homeTeamWins}`, `gameVideo {threeMinRecap, condensedGame}`.
 * - landing: the game (`gameState`, teams with `score` / `record`, `venue`, `tvBroadcasts`), `summary.scoring[].goals[]`,
 *   `summary.threeStars[]`, `matchup.skaterComparison.leaders[]`, `matchup.goalieComparison.{away,home}Team.leaders[]`.
 * - boxscore: `playerByGameStats.{away,home}Team.{forwards, defense, goalies}[]`.
 */
object NhlDetailMapper {
    /**
     * The detail of [id] from its [rightRail] (required) and [landing] (null when that call failed: goals, stars,
     * leaders, goalies, venue, the landing scores and the broadcasts stay empty). [serverDateMs] is the newer of the two
     * answers' `Date`. The phase is the landing's, else this game's `seasonSeries` entry's, else [phaseHint] (the board's),
     * else SCHEDULED. Null = unmappable (a right-rail that is not this game's detail: no known section at all).
     */
    fun detail(
        id: GameId,
        rightRail: JsonObject,
        landing: JsonObject?,
        serverDateMs: Long?,
        phaseHint: GamePhase? = null,
    ): GameDetail? {
        if (KNOWN_RIGHT_RAIL.none { it in rightRail }) return null
        val landingGame = landing?.let { NhlMapper.landing(it, "") }?.takeIf { it.id.id == id.id }
        val seriesEntry = (rightRail["seasonSeries"] as? JsonArray)
            ?.firstOrNull { (it as? JsonObject)?.primitive("id")?.content == id.id } as? JsonObject
        val phase = landingGame?.phase
            ?: seriesEntry?.let { NhlMapper.landing(it, "")?.phase }
            ?: phaseHint
            ?: GamePhase.SCHEDULED
        val video = rightRail.obj("gameVideo")
        return GameDetail(
            id = id,
            fetchedPhase = phase,
            serverDateMs = serverDateMs,
            awayScore = landingGame?.away?.score,
            homeScore = landingGame?.home?.score,
            venue = landing?.localised("venue"),
            gameStats = gameStats(rightRail["teamGameStats"]),
            season = season(rightRail, landing),
            periods = periods(rightRail.obj("linescore")?.get("byPeriod")),
            goals = goals(landing?.obj("summary")?.get("scoring")),
            stars = stars(landing?.obj("summary")?.get("threeStars")),
            leaders = leaders(landing?.obj("matchup")?.obj("skaterComparison")?.get("leaders")),
            goalies = goalies(landing?.obj("matchup")?.obj("goalieComparison")),
            series = series(rightRail),
            recapId = NhlMapper.videoId(video?.get("threeMinRecap")),
            condensedId = NhlMapper.videoId(video?.get("condensedGame")),
            broadcasts = NhlMapper.broadcasts(landing?.get("tvBroadcasts")),
        )
    }

    /**
     * The box score from a `/boxscore` body (`playerByGameStats`). A body of a game not started yet (no
     * `playerByGameStats`, but the game's `id`) is an empty box score; null = unmappable.
     */
    fun boxScore(body: JsonObject): BoxScore? {
        val stats = body.obj("playerByGameStats")
        if (stats == null) {
            return if (body.primitive("id") != null) BoxScore(TeamBox(emptyList(), emptyList()), TeamBox(emptyList(), emptyList())) else null
        }
        return BoxScore(teamBox(stats.obj("awayTeam")), teamBox(stats.obj("homeTeam")))
    }

    // ------------------------------------------------------------------ right-rail

    private val KNOWN_RIGHT_RAIL = listOf("seasonSeries", "seasonSeriesWins", "linescore", "teamGameStats", "teamSeasonStats", "last10Record", "gameVideo", "gameInfo")
    private val FRACTION = Regex("(\\d{1,4})/(\\d{1,4})")
    private val COMPLETED = setOf("OFF", "FINAL", "OVER")

    private fun gameStats(el: JsonElement?): List<StatRow> {
        val arr = el as? JsonArray ?: return emptyList()
        val rows = HashMap<StatKind, StatRow>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val kind = when (o.str("category")) {
                "sog" -> StatKind.SOG
                "powerPlay" -> StatKind.POWER_PLAY
                "hits" -> StatKind.HITS
                "blockedShots" -> StatKind.BLOCKED
                "faceoffWinningPctg" -> StatKind.FACEOFF_PCT
                "pim" -> StatKind.PIM
                "giveaways" -> StatKind.GIVEAWAYS
                "takeaways" -> StatKind.TAKEAWAYS
                else -> continue
            }
            val away = statValue(kind, o["awayValue"]) ?: continue
            val home = statValue(kind, o["homeValue"]) ?: continue
            rows.putIfAbsent(kind, StatRow(kind, away, home))
        }
        return StatKind.entries.mapNotNull { rows[it] }
    }

    private fun statValue(kind: StatKind, el: JsonElement?): StatValue? = when (kind) {
        StatKind.POWER_PLAY -> el.str()?.trim()?.let { FRACTION.matchEntire(it) }?.let {
            val made = it.groupValues[1].toInt()
            val of = it.groupValues[2].toInt()
            if (made <= of) StatValue.Fraction(made, of) else null
        }
        StatKind.FACEOFF_PCT -> el.double()?.takeIf { it in 0.0..1.0 }?.let { StatValue.Pct(it) }
        else -> el.int()?.takeIf { it in 0..COUNT_MAX }?.let { StatValue.Count(it) }
    }

    private fun season(rightRail: JsonObject, landing: JsonObject?): SeasonComparison? {
        val stats = rightRail.obj("teamSeasonStats")
        val a = stats?.obj("awayTeam")
        val h = stats?.obj("homeTeam")
        val last10 = rightRail.obj("last10Record")
        val c = SeasonComparison(
            awayRecord = landing?.obj("awayTeam")?.str("record")?.trim()?.takeIf { it.isNotEmpty() },
            homeRecord = landing?.obj("homeTeam")?.str("record")?.trim()?.takeIf { it.isNotEmpty() },
            awayLast10 = last10(last10?.obj("awayTeam")),
            homeLast10 = last10(last10?.obj("homeTeam")),
            awayGf = ranked(a, "goalsForPerGamePlayed"),
            homeGf = ranked(h, "goalsForPerGamePlayed"),
            awayGa = ranked(a, "goalsAgainstPerGamePlayed"),
            homeGa = ranked(h, "goalsAgainstPerGamePlayed"),
            awayPp = ranked(a, "ppPctg"),
            homePp = ranked(h, "ppPctg"),
            awayPk = ranked(a, "pkPctg"),
            homePk = ranked(h, "pkPctg"),
        )
        return c.takeUnless { it == EMPTY_SEASON }
    }

    private val EMPTY_SEASON = SeasonComparison(null, null, null, null, null, null, null, null, null, null, null, null)

    private fun ranked(o: JsonObject?, key: String): Ranked? {
        val v = o?.get(key).double()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return Ranked(v, o?.get(key + "Rank").int()?.takeIf { it in 1..RANK_MAX })
    }

    private fun last10(o: JsonObject?): Last10? {
        val record = o?.str("record")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Last10(record, o.str("streakType")?.trim()?.takeIf { it.isNotEmpty() }, o["streak"].int()?.takeIf { it >= 0 })
    }

    private fun periods(el: JsonElement?): List<PeriodScore> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val label = label(o.obj("periodDescriptor")) ?: return@mapNotNull null
            val away = o["away"].int()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val home = o["home"].int()?.takeIf { it >= 0 } ?: return@mapNotNull null
            PeriodScore(label, away, home)
        }
    }

    /** `periodDescriptor` → "1st" (REG n), "OT" / "2OT" (OT, number − regulation periods), "SO". */
    private fun label(pd: JsonObject?): PeriodLabel? {
        pd ?: return null
        val number = pd["number"].int()?.takeIf { it >= 1 } ?: return null
        val reg = pd["maxRegulationPeriods"].int()?.takeIf { it >= 1 } ?: REG_PERIODS
        return when (pd.str("periodType")) {
            "REG" -> PeriodLabel(PeriodKind.REG, number)
            "OT" -> PeriodLabel(PeriodKind.OT, (number - reg).coerceAtLeast(1))
            "SO" -> PeriodLabel(PeriodKind.SO, 1)
            else -> null
        }
    }

    private fun series(rr: JsonObject): SeriesFacts? {
        val wins = rr.obj("seasonSeriesWins") ?: return null
        val a = wins["awayTeamWins"].int()?.takeIf { it >= 0 } ?: return null
        val h = wins["homeTeamWins"].int()?.takeIf { it >= 0 } ?: return null
        val played = (rr["seasonSeries"] as? JsonArray)?.count { (it as? JsonObject)?.str("gameState") in COMPLETED } ?: 0
        return SeriesFacts(a, h, played)
    }

    // ------------------------------------------------------------------ landing

    private fun goals(el: JsonElement?): List<GoalItem> {
        val periods = el as? JsonArray ?: return emptyList()
        val out = ArrayList<GoalItem>()
        for (p in periods) {
            val po = p as? JsonObject ?: continue
            val label = label(po.obj("periodDescriptor")) ?: continue
            val goals = po["goals"] as? JsonArray ?: continue
            for (g in goals) {
                val o = g as? JsonObject ?: continue
                val isHome = o["isHome"].bool() ?: continue
                val away = o["awayScore"].int()?.takeIf { it >= 0 } ?: continue
                val home = o["homeScore"].int()?.takeIf { it >= 0 } ?: continue
                val last = o.localised("lastName")
                val scorer = o.localised("name") ?: last ?: continue
                out += GoalItem(
                    period = label,
                    timeInPeriod = o.str("timeInPeriod")?.trim().orEmpty(),
                    side = if (isHome) Side.HOME else Side.AWAY,
                    scorer = scorer,
                    scorerLast = last ?: lastOf(scorer),
                    scorerTotal = o["goalsToDate"].int()?.takeIf { it >= 0 },
                    assistsLast = (o["assists"] as? JsonArray)?.mapNotNull { a ->
                        (a as? JsonObject)?.let { it.localised("lastName") ?: it.localised("name")?.let(::lastOf) }
                    }.orEmpty(),
                    strength = when (o.str("strength")) {
                        "pp" -> Strength.PP
                        "sh" -> Strength.SH
                        else -> Strength.EV
                    },
                    emptyNet = o.str("goalModifier") == "empty-net",
                    away = away,
                    home = home,
                    clipId = NhlMapper.videoId(o["highlightClip"]),
                    headshot = o.str("headshot")?.takeIf { it.startsWith("https://") },
                )
            }
        }
        return out
    }

    private fun stars(el: JsonElement?): List<Star> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val rank = o["star"].int()?.takeIf { it in 1..3 } ?: return@mapNotNull null
            val name = o.localised("name") ?: return@mapNotNull null
            val team = o.localised("teamAbbrev") ?: return@mapNotNull null
            Star(rank, name, o.localised("lastName") ?: lastOf(name), team, o.str("headshot")?.takeIf { it.startsWith("https://") })
        }.sortedBy { it.rank }
    }

    private fun leaders(el: JsonElement?): List<LeaderPair> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val category = o.str("category")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val away = leader(o.obj("awayLeader"))
            val home = leader(o.obj("homeLeader"))
            if (away == null && home == null) null else LeaderPair(category, away, home)
        }
    }

    private fun leader(o: JsonObject?): Leader? {
        o ?: return null
        val name = o.localised("name") ?: return null
        val value = o["value"].int()?.takeIf { it >= 0 } ?: return null
        return Leader(name, value, o.str("headshot")?.takeIf { it.startsWith("https://") })
    }

    private fun goalies(o: JsonObject?): GoaliePair? {
        o ?: return null
        val away = goalieList(o.obj("awayTeam")?.get("leaders"))
        val home = goalieList(o.obj("homeTeam")?.get("leaders"))
        return if (away.isEmpty() && home.isEmpty()) null else GoaliePair(away, home)
    }

    private fun goalieList(el: JsonElement?): List<Goalie> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.localised("name") ?: return@mapNotNull null
            Goalie(
                name,
                o.str("record")?.trim()?.takeIf { it.isNotEmpty() },
                o["gaa"].double()?.takeIf { it.isFinite() && it >= 0.0 },
                o["savePctg"].double()?.takeIf { it in 0.0..1.0 },
                o.str("headshot")?.takeIf { it.startsWith("https://") },
            )
        }
    }

    // ------------------------------------------------------------------ box score

    private fun teamBox(o: JsonObject?): TeamBox {
        o ?: return TeamBox(emptyList(), emptyList())
        val skaters = listOf("forwards", "defense").flatMap { key ->
            (o[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::skater) }.orEmpty()
        }
        val goalies = (o["goalies"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::goalieLine) }.orEmpty()
        return TeamBox(skaters, goalies)
    }

    private fun skater(o: JsonObject): SkaterLine? {
        val name = o.localised("name") ?: return null
        val g = o["goals"].int()?.coerceAtLeast(0) ?: 0
        val a = o["assists"].int()?.coerceAtLeast(0) ?: 0
        return SkaterLine(
            name = name,
            pos = o.str("position")?.trim().orEmpty(),
            g = g,
            a = a,
            p = o["points"].int()?.coerceAtLeast(0) ?: (g + a),
            plusMinus = o["plusMinus"].int(),
            sog = o["sog"].int()?.takeIf { it >= 0 },
            toi = o.str("toi")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun goalieLine(o: JsonObject): GoalieLine? {
        val name = o.localised("name") ?: return null
        return GoalieLine(
            name = name,
            sa = o["shotsAgainst"].int()?.takeIf { it >= 0 },
            saves = o["saves"].int()?.takeIf { it >= 0 },
            svPct = o["savePctg"].double()?.takeIf { it in 0.0..1.0 },
            toi = o.str("toi")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------ helpers

    private const val REG_PERIODS = 3
    private const val COUNT_MAX = 999
    private const val RANK_MAX = 64

    /** "S. Skinner" → "Skinner"; "J. van Riemsdyk" → "van Riemsdyk"; a name without an initial is kept. */
    private fun lastOf(name: String): String {
        val parts = name.trim().split(' ').filter { it.isNotEmpty() }
        val rest = parts.dropWhile { it.length <= 3 && it.endsWith('.') }
        return rest.joinToString(" ").ifEmpty { name.trim() }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key].prim()
    private fun JsonObject.str(key: String): String? = this[key].str()

    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.str(): String? = prim()?.takeIf { it.isString }?.content
    private fun JsonElement?.int(): Int? {
        val c = prim()?.content?.trim() ?: return null
        c.toIntOrNull()?.let { return it }
        val d = c.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < Int.MAX_VALUE) d.toInt() else null
    }
    private fun JsonElement?.double(): Double? = prim()?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JsonElement?.bool(): Boolean? = prim()?.content?.toBooleanStrictOrNull()

    /** `{"default": "..."}` → default; a bare string is accepted too. */
    private fun JsonObject.localised(key: String): String? = when (val e = this[key]) {
        is JsonObject -> e.str("default")
        is JsonPrimitive -> e.str()
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}
