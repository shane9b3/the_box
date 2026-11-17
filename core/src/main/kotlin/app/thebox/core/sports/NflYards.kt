// Contract (NFL YDS foundation, docs/design/m6-nfl-stacked-yds.md §1.4): the signatures are fixed for WP-1 (the bodies)
// and the drawing (WP-2 reads [NflYards.text]); changes go through the lead.
package app.thebox.core.sports

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * (NFL YDS §1) Team total yards for the Stacked NFL bug's YDS column: the stoppage a snapshot is in, the competitor ids
 * the statistics URL needs, the value read from ESPN's per-team statistics, and the digits as drawn. Pure.
 */
object NflYards {
    /**
     * (§1.5) The stoppage [s] is in, or null: `"F"` at FINAL; `"H" + period` at halftime; `"E" + period` at the end of a
     * quarter (`STATUS_END_PERIOD`, and the change-of-ends shape `EspnMapper` maps to it, S1); in a LIVE snapshot
     * `"T" + lastPlay.id` for a team timeout (type 21) and `"O" + lastPlay.id` for an official timeout (74) or the
     * two-minute warning (75) (S2); null otherwise (in play, PRE, not the NFL).
     */
    fun stoppageKey(s: GameSnapshot): String? {
        val d = s.detail as? LeagueDetail.Nfl ?: return null
        if (s.id.league != League.NFL) return null
        return when (s.phase) {
            GamePhase.FINAL -> KEY_FINAL
            GamePhase.INTERMISSION -> (if (d.breakKind == NflBreak.HALFTIME) "H" else "E") + (s.period?.toString() ?: "")
            GamePhase.LIVE -> {
                val lp = d.lastPlay ?: return null
                when (lp.typeId) {
                    SportsConstants.NFL_TYPE_TIMEOUT -> KEY_TEAM_TIMEOUT + lp.id
                    SportsConstants.NFL_TYPE_OFFICIAL_TIMEOUT, SportsConstants.NFL_TYPE_TWO_MINUTE_WARNING -> "O" + lp.id
                    else -> null
                }
            }
            else -> null
        }
    }

    /** The FINAL stoppage's key: exactly one fetch, no refresh (S10). */
    const val KEY_FINAL = "F"

    /** A team timeout's key prefix: the faster refresh and confirm (integration ruling, live2 2026-11-16). */
    const val KEY_TEAM_TIMEOUT = "T"

    /**
     * (§1.7) The (away, home) competitor ids of [e] for the statistics URL: `competitions[0].competitors[].id`, else
     * `team.id`, 1–6 digits; null when either is missing.
     */
    fun teamIds(e: EspnEventDto): Pair<String, String>? {
        val cs = e.competitions?.firstOrNull()?.competitors ?: return null
        fun idOf(side: String): String? {
            val c = cs.firstOrNull { it.homeAway.leaf() == side } ?: return null
            val id = c.id.leaf() ?: (c.team as? JsonObject)?.get("id").leaf()
            return id?.takeIf { TEAM_ID.matches(it) }
        }
        val away = idOf("away") ?: return null
        val home = idOf("home") ?: return null
        return away to home
    }

    /**
     * (§1.7, review S3) The team's total yards from one statistics body: the first stat named `totalYards` in any
     * category (ESPN copies the team value into passing, rushing and receiving; net of sacks, the broadcast "total
     * yards", review N1), `value` when finite and integral, else `displayValue` matching `-?\d{1,4}`; null when absent,
     * unreadable or outside `NFL_YARDS_MIN..NFL_YARDS_MAX`.
     */
    fun totalYards(dto: EspnStatsDto): Int? {
        val stat = dto.splits?.categories.orEmpty().asSequence()
            .flatMap { it.stats.orEmpty().asSequence() }
            .firstOrNull { it.name.leaf() == TOTAL_YARDS }
            ?: return null
        val fromValue = stat.value.leaf()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it == floor(it) && it in -1e6..1e6 }
            ?.toInt()
        val v = fromValue ?: stat.displayValue.leaf()?.takeIf { DISPLAY.matches(it) }?.toIntOrNull() ?: return null
        return v.takeIf { it in SportsConstants.NFL_YARDS_MIN..SportsConstants.NFL_YARDS_MAX }
    }

    private const val TOTAL_YARDS = "totalYards"
    private val TEAM_ID = Regex("\\d{1,6}")
    private val DISPLAY = Regex("-?\\d{1,4}")

    /** A lenient leaf: a string or a number's text, trimmed; anything else (null, an object, an array) is null. */
    private fun JsonElement?.leaf(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** (§3.4) The digits as drawn and spoken: "142", "0", "−2" (U+2212 MINUS SIGN), ASCII digits in every locale. */
    fun text(yards: Int): String = if (yards < 0) MINUS + (-yards.toLong()).toString() else yards.toString()

    /** U+2212, in the bundled Instrument Sans Bold. */
    const val MINUS = "−"
}

/** (§1.6) One yards attempt's outcome: both teams' values, or a failure of [kind] (a 403 is reported as `HTTP_429`). */
sealed interface YardsResult {
    data class Ok(val away: Int, val home: Int) : YardsResult
    data class Failed(val kind: FailKind) : YardsResult
}

/**
 * (§1.6) One follow's yards state, owned by `EspnScoreSource` and used only on the score actor (single-threaded): which
 * stoppage to fetch on this poll ([due]), what came back ([fetched]), and the snapshot with the usable yards attached
 * ([attach]). Holds a single current stoppage key (review N7), the confirm rule for the refresh (B1; 12 s / 5 s at a team
 * timeout, 30 s / 20 s otherwise) and the follow-wide block (429 / 403, or two timeout / IO failures in a row, S4). A stall ends its stoppage; the stall count resets after a block.
 */
class NflYardsPlanner {
    private enum class Last { NONE, OK, RETRYABLE, STALL }

    // ---- the current stoppage (a new key replaces it, review N7)
    private var key: String? = null
    private var attempts = 0
    private var successes = 0
    private var lastAttemptAt = 0L
    private var last = Last.NONE
    private var firstOkAt = 0L
    private var away: Int? = null
    private var home: Int? = null
    private var pendingAway: Int? = null
    private var pendingHome: Int? = null
    private var pendingAt = 0L

    // ---- follow-wide (S4)
    private var blockedUntil: Long? = null
    private var stalls = 0

    /** The stoppage key to fetch for [s], received at [nowMono], or null (counts the attempt when non-null). */
    fun due(s: GameSnapshot, nowMono: Long): String? {
        val k = NflYards.stoppageKey(s) ?: return null
        if (k != key) start(k)
        blockedUntil?.let { if (nowMono < it) return null }
        if (attempts >= SportsConstants.NFL_YDS_ATTEMPTS_MAX) return null
        val due = when (last) {
            Last.NONE -> attempts == 0
            Last.RETRYABLE -> nowMono - lastAttemptAt >= SportsConstants.NFL_YDS_RETRY_MS
            // The one refresh; FINAL gets exactly one fetch (S10).
            Last.OK -> successes < SportsConstants.NFL_YDS_SUCCESSES_MAX && k != NflYards.KEY_FINAL &&
                nowMono - firstOkAt >= refreshMs(k)
            Last.STALL -> false // (S4b) a timeout / IO failure ends this stoppage's attempts
        }
        if (!due) return null
        attempts++
        lastAttemptAt = nowMono
        return k
    }

    /**
     * The outcome of the attempt [due] returned [key] for, completed at [atMono] (which only times a block; the refresh and
     * the confirm count from the attempt's poll).
     */
    fun fetched(key: String, atMono: Long, r: YardsResult) {
        if (key != this.key) return
        when (r) {
            is YardsResult.Ok -> {
                stalls = 0
                successes++
                last = Last.OK
                // (Code review S1) Both times are the attempt's poll (`receivedMono`), not the fetch's completion, so the
                // refresh and confirm delays mean what they say on a poll grid (a completion time slips each by a poll).
                if (successes == 1) {
                    away = r.away
                    home = r.home
                    firstOkAt = lastAttemptAt
                } else {
                    pendingAway = r.away
                    pendingHome = r.home
                    pendingAt = lastAttemptAt
                }
            }
            is YardsResult.Failed -> when (r.kind) {
                FailKind.HTTP_429 -> {
                    last = Last.RETRYABLE
                    blockedUntil = atMono + SportsConstants.NFL_YDS_BLOCK_MS
                }
                FailKind.TIMEOUT, FailKind.IO -> {
                    last = Last.STALL
                    stalls++
                    if (stalls >= SportsConstants.NFL_YDS_STALLS_TO_BLOCK) {
                        blockedUntil = atMono + SportsConstants.NFL_YDS_BLOCK_MS
                        stalls = 0
                    }
                }
                else -> {
                    last = Last.RETRYABLE
                    stalls = 0
                }
            }
        }
    }

    /**
     * [s] (received at [receivedMono]) with the yards usable for its stoppage, or [s] unchanged. Only a snapshot in the
     * current stoppage carries them; a refresh is used only from a poll received at least `NFL_YDS_CONFIRM_MS`
     * (`NFL_YDS_TIMEOUT_CONFIRM_MS` at a team timeout) after the poll that carried it (B1; code review S1).
     */
    fun attach(s: GameSnapshot, receivedMono: Long): GameSnapshot {
        val k = NflYards.stoppageKey(s) ?: return s
        if (k != key) return s
        val pa = pendingAway
        val ph = pendingHome
        if (pa != null && ph != null && receivedMono >= pendingAt + confirmMs(k)) {
            away = pa
            home = ph
            pendingAway = null
            pendingHome = null
        }
        val a = away ?: return s
        val h = home ?: return s
        val d = s.detail as? LeagueDetail.Nfl ?: return s
        return s.copy(detail = d.copy(awayYards = a, homeYards = h))
    }

    /**
     * (Integration ruling, live2 2026-11-16) A team timeout's refresh is due `NFL_YDS_TIMEOUT_REFRESH_MS` after the first
     * good fetch and confirmed after `NFL_YDS_TIMEOUT_CONFIRM_MS`: the previous play's yards often reach the feed 10–20 s
     * after the timeout is on the board, no play runs during it, and it lasts at least 30 s. Every other key: 30 s / 20 s.
     */
    private fun refreshMs(k: String): Long =
        if (k.startsWith(NflYards.KEY_TEAM_TIMEOUT)) SportsConstants.NFL_YDS_TIMEOUT_REFRESH_MS else SportsConstants.NFL_YDS_REFRESH_MS

    private fun confirmMs(k: String): Long =
        if (k.startsWith(NflYards.KEY_TEAM_TIMEOUT)) SportsConstants.NFL_YDS_TIMEOUT_CONFIRM_MS else SportsConstants.NFL_YDS_CONFIRM_MS

    private fun start(k: String) {
        key = k
        attempts = 0
        successes = 0
        lastAttemptAt = 0L
        last = Last.NONE
        firstOkAt = 0L
        away = null
        home = null
        pendingAway = null
        pendingHome = null
        pendingAt = 0L
    }
}
