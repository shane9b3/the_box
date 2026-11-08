package app.thebox.core.sports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * NHL JSON to snapshots (docs/design/m6-score-bug.md §2.1; WP-1). Pure: no I/O, no clock. Reads `default` for every
 * localised field, ignores unknown keys, treats every field as optional. A body it cannot map yields null (the source
 * turns that into `Failed(BAD_BODY)`). [logoBase] is `SportsEndpoints.logoBase`: a landing `darkLogo` outside it is
 * replaced by the URL derived from the abbreviation (A-N2).
 *
 * A game needs an `id`, both team abbreviations and `startTimeUTC` (a missing `gameDate` falls back to the start's
 * Eastern date); a day-list game without them is dropped, a landing
 * without them is null. An abbreviation that is not 2–4 letters/digits gets no logo URL (it would be spliced into one).
 *
 * Fixtures: `core/src/testFixtures/resources/sports/nhl/` (on the classpath of `:core`, `:data` and `:ui` tests).
 */
object NhlMapper {
    /** `/v1/score/now` or `/v1/score/{date}`: `currentDate` is the day (A-N10); games are grouped by `gameDate`. */
    fun day(body: JsonObject, logoBase: String): DayBoard? {
        val dayKey = body.str("currentDate") ?: return null
        val games = (body["games"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { g -> game(g, dayKey, logoBase, landing = false) } }
            ?: emptyList()
        return DayBoard(dayKey, body.str("prevDate"), body.str("nextDate"), games)
    }

    /** `/v1/gamecenter/{id}/landing`: names are `placeName + commonName`; the period is `periodDescriptor.number`. */
    fun landing(body: JsonObject, logoBase: String): GameSnapshot? = game(body, null, logoBase, landing = true)

    /** `{logoBase}{ABBR}_dark.svg`. */
    fun darkLogoUrl(abbrev: String, logoBase: String): String = "$logoBase${abbrev}_dark.svg"

    // ------------------------------------------------------------------ one game

    private val ABBREV = Regex("[A-Z0-9]{2,4}")

    private fun game(g: JsonObject, fallbackDay: String?, logoBase: String, landing: Boolean): GameSnapshot? {
        val id = g.primitive("id")?.content?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        val start = g.str("startTimeUTC")?.let(::parseUtc) ?: return null
        val dayKey = g.str("gameDate") ?: fallbackDay ?: easternDay(start)
        val away = team(g.obj("awayTeam"), logoBase, landing) ?: return null
        val home = team(g.obj("homeTeam"), logoBase, landing) ?: return null

        val pd = g.obj("periodDescriptor")
        val period = g.int("period") ?: pd?.int("number")
        val periodKind = periodKind(pd?.str("periodType"))
        val regPeriods = pd?.int("maxRegulationPeriods") ?: g.int("regPeriods") ?: 3
        val outcome = g.obj("gameOutcome")

        val clockObj = g.obj("clock")
        val clock = clockObj?.let { c ->
            val secs = c.int("secondsRemaining") ?: c.str("timeRemaining")?.let(::parseMmSs) ?: return@let null
            GameClock(secs.coerceAtLeast(0), running = c.bool("running") ?: false, inIntermission = c.bool("inIntermission") ?: false)
        }

        val schedule = g.str("gameScheduleState")
        val phase = when {
            schedule != null && schedule != "OK" -> GamePhase.POSTPONED
            else -> when (g.str("gameState")) {
                "PRE" -> GamePhase.PRE
                "LIVE", "CRIT" -> if (clock?.inIntermission == true) GamePhase.INTERMISSION else GamePhase.LIVE
                // OVER: the game has just ended, before FINAL/OFF (seen for ~20 s up to half an hour). The score is final;
                // treating it as unknown hid the bug in the closing minutes (owner report, 2026-11-08).
                "OVER", "OFF", "FINAL" -> GamePhase.FINAL
                else -> GamePhase.SCHEDULED // FUT and anything unknown
            }
        }

        val finalKind = if (phase == GamePhase.FINAL) {
            periodKind(outcome?.str("lastPeriodType")) ?: periodKind ?: PeriodKind.REG
        } else {
            null
        }
        val otKind = if (phase == GamePhase.FINAL) finalKind else periodKind
        val otNumber = if (otKind == PeriodKind.OT) {
            outcome?.int("otPeriods")?.takeIf { phase == GamePhase.FINAL }
                ?: period?.let { (it - regPeriods).coerceAtLeast(1) }
                ?: 1
        } else {
            null
        }

        return GameSnapshot(
            id = GameId(League.NHL, id),
            dayKey = dayKey,
            startUtcMs = start,
            phase = phase,
            away = away,
            home = home,
            period = period,
            periodKind = periodKind,
            otNumber = otNumber,
            clock = clock,
            finalKind = finalKind,
            powerPlay = powerPlay(g.obj("situation")),
            lastGoal = if (landing) lastGoal(g.obj("summary")) else null,
        )
    }

    // ------------------------------------------------------------------ power play (docs/design/m6-power-play.md §1.2)

    private val SITUATION_CODE = Regex("[01][3-6][3-6][01]")
    private val DIGITS = Regex("[0-9]{3,4}")

    /**
     * (Power play §1.2) The `situation` object's power play, decoded from `situationCode` alone (S9: no strength
     * fallback). The code is away goalie, away skaters, home skaters, home goalie; a 4-character string, or a JSON
     * integer of 3–4 digits left-padded to 4 (`651` → `"0651"`). A pulled goalie's extra attacker is discounted, so an
     * empty net at even strength, 4-on-4 and a delayed penalty (`0651`) give none and a 6-on-4 (`0641`) keeps its power
     * play (owner ruling 18). Both effective counts must be 3..5 (rejects `0331`, `1361`, penalty-shot codes). A "PP" in
     * the *other* side's `situationDescriptions` contradicts the code: none. The seconds are `secondsRemaining`, else
     * `timeRemaining`, kept only in `0..PP_SECS_MAX`.
     */
    internal fun powerPlay(sit: JsonObject?): PowerPlay? {
        sit ?: return null
        val raw = sit.primitive("situationCode") ?: return null
        val code = when {
            raw.isString -> raw.content.takeIf { it.length == 4 }
            DIGITS.matches(raw.content) -> raw.content.padStart(4, '0')
            else -> null
        } ?: return null
        if (!SITUATION_CODE.matches(code)) return null
        val d = IntArray(4) { code[it] - '0' }
        val away = d[1] - (1 - d[0])
        val home = d[2] - (1 - d[3])
        if (away !in 3..5 || home !in 3..5 || away == home) return null
        val side = if (away > home) Side.AWAY else Side.HOME
        val kind = if (kotlin.math.abs(away - home) == 2) PowerPlayKind.TWO_MAN else PowerPlayKind.ONE_MAN
        val other = sit.obj(if (side == Side.AWAY) "homeTeam" else "awayTeam")
        val otherDescs = other?.get("situationDescriptions") as? JsonArray
        if (otherDescs != null && otherDescs.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "PP" }) return null
        val secs = (sit.int("secondsRemaining") ?: sit.str("timeRemaining")?.let(::parseMmSs))
            ?.takeIf { it in 0..SportsConstants.PP_SECS_MAX }
        return PowerPlay(side, kind, secs)
    }

    /**
     * (Power play §1.2) The landing's latest `summary.scoring` goal: the entry with the highest `awayScore + homeScore`
     * (the later one on a tie). An entry needs `isHome`, both scores (≥ 0) and a string `strength`; odd entries are
     * skipped, and none left gives null.
     */
    internal fun lastGoal(summary: JsonObject?): GoalFact? {
        val periods = summary?.get("scoring") as? JsonArray ?: return null
        var best: GoalFact? = null
        var bestTotal = -1
        for (p in periods) {
            val goals = (p as? JsonObject)?.get("goals") as? JsonArray ?: continue
            for (e in goals) {
                val goal = e as? JsonObject ?: continue
                val isHome = goal.bool("isHome") ?: continue
                val a = goal.int("awayScore")?.takeIf { it >= 0 } ?: continue
                val h = goal.int("homeScore")?.takeIf { it >= 0 } ?: continue
                val strength = goal.str("strength") ?: continue
                if (a + h >= bestTotal) {
                    bestTotal = a + h
                    best = GoalFact(if (isHome) Side.HOME else Side.AWAY, a, h, pp = strength == "pp")
                }
            }
        }
        return best
    }

    private fun team(t: JsonObject?, logoBase: String, landing: Boolean): TeamLine? {
        t ?: return null
        val abbrev = t.str("abbrev")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val common = t.localised("commonName") ?: t.localised("name")
        val place = t.localised("placeName")
        val name = if (landing && place != null && common != null) "$place $common" else common ?: place ?: abbrev
        val dark = t.str("darkLogo")
        val logo = when {
            dark != null && dark.startsWith(logoBase) && !dark.contains("..") -> dark
            ABBREV.matches(abbrev) -> darkLogoUrl(abbrev, logoBase)
            else -> null
        }
        return TeamLine(abbrev, name, t.int("score")?.coerceAtLeast(0), logo)
    }

    private fun periodKind(s: String?): PeriodKind? = when (s) {
        "REG" -> PeriodKind.REG
        "OT" -> PeriodKind.OT
        "SO" -> PeriodKind.SO
        else -> null
    }

    /** The NHL game day is Eastern; a body without `gameDate` falls back to the start's Eastern date. */
    private fun easternDay(utcMs: Long): String =
        Instant.ofEpochMilli(utcMs).atZone(ZoneId.of("America/New_York")).toLocalDate().toString()

    private fun parseUtc(s: String): Long? = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** "15:58" → 958. */
    private fun parseMmSs(s: String): Int? {
        val parts = s.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val sec = parts[1].toIntOrNull() ?: return null
        return m * 60 + sec
    }

    // ------------------------------------------------------------------ lenient JSON access

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it.isNull() }
    private fun JsonObject.str(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = primitive(key)?.content?.toIntOrNull()
    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.content?.toBooleanStrictOrNull()

    /** `{"default": "...", "fr": "..."}` → default; a bare string is accepted too. */
    private fun JsonObject.localised(key: String): String? {
        val e: JsonElement = this[key] ?: return null
        return when (e) {
            is JsonObject -> e.str("default")
            is JsonPrimitive -> e.takeIf { it.isString }?.content
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun JsonPrimitive.isNull(): Boolean = this is kotlinx.serialization.json.JsonNull
}
