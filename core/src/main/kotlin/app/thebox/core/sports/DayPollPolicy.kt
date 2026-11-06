package app.thebox.core.sports

import kotlin.math.roundToLong

/**
 * When the day list polls (docs/design/m6-score-bug.md §2.3, A-S7; WP-1): 30 s, bucket-aligned from [lastOk]'s
 * `Date − Age`, while any game that day is live or pre-game; 5 min otherwise; once for an all-final day; nothing while
 * [demand] is not SEEN (the tab and the picker closed). After [failures] consecutive `Failed`s since [lastOk] the §2.4
 * back-off runs from [lastFailMono] ([retryAfterS] wins); [jitter] ∈ [-1, 1] scales its ±20 %.
 *
 * Returns the `MonoClock` instant of the next request, or null for none. [lastOk] null = never loaded: [nowMono].
 *
 * "30 s, bucket-aligned": the bucket boundary + [pollPhaseMs] nearest to `received + 30 s` (so 20 or 40 s, about
 * 30 s on average; the CDN only has a new body every 20 s). Without `Date`: `received + 30 s`. An empty day counts as
 * all-final. "Pre-game" is `PRE` (the hour before puck drop); a `SCHEDULED` day re-polls every 5 min.
 */
object DayPollPolicy {
    fun nextAt(
        lastOk: Fetch.Ok<DayBoard>?,
        demand: Demand,
        failures: Int,
        lastFailMono: Long?,
        retryAfterS: Int?,
        pollPhaseMs: Long,
        jitter: Double,
        nowMono: Long,
    ): Long? {
        if (demand != Demand.SEEN) return null
        if (failures > 0 && lastFailMono != null) return SportsTiming.backoffAt(lastFailMono, failures, retryAfterS, jitter)
        lastOk ?: return nowMono
        val games = lastOk.value.games
        val active = games.any { it.phase == GamePhase.LIVE || it.phase == GamePhase.INTERMISSION || it.phase == GamePhase.PRE }
        return when {
            active -> SportsTiming.nearestAligned(lastOk, pollPhaseMs, lastOk.receivedMono + SportsConstants.DAY_POLL_LIVE_MS)
            games.isEmpty() || games.all { it.phase == GamePhase.FINAL || it.phase == GamePhase.POSTPONED } -> null
            else -> lastOk.receivedMono + SportsConstants.DAY_POLL_IDLE_MS
        }
    }
}

/** The CDN bucket arithmetic and the back-off, shared by [DayPollPolicy] and [ScoreBugMachine] (§2.3, §2.4). */
internal object SportsTiming {
    private const val BUCKET = SportsConstants.CDN_BUCKET_MS

    /** The `MonoClock` instant the response's CDN bucket ends: `received + (Date − Age + 20 s − Date)`; null without `Date`. */
    fun bucketEndMono(ok: Fetch.Ok<*>): Long? =
        ok.serverDateMs?.let { ok.receivedMono + BUCKET - ok.ageS.coerceIn(0, SportsConstants.AGE_MAX_S) * 1000L }

    /**
     * The first `bucket end + phase + k·20 s` (k ≥ [skip]) at or after [notBefore] and after the response. Without
     * `Date`: `received + 20 s · (1 + skip)`, then whole buckets until [notBefore].
     */
    fun nextAligned(ok: Fetch.Ok<*>, phaseMs: Long, skip: Int, notBefore: Long): Long {
        val end = bucketEndMono(ok)
        var t = if (end != null) end + phaseMs + skip * BUCKET else ok.receivedMono + BUCKET * (1 + skip)
        val floor = maxOf(notBefore, ok.receivedMono + 1)
        if (t < floor) t += ceilDiv(floor - t, BUCKET) * BUCKET
        return t
    }

    /** The aligned instant nearest to [target] (ties later), never at or before the response. Without `Date`: [target]. */
    fun nearestAligned(ok: Fetch.Ok<*>, phaseMs: Long, target: Long): Long {
        val end = bucketEndMono(ok) ?: return target
        val base = end + phaseMs
        var t = base + Math.floorDiv(target - base + BUCKET / 2, BUCKET) * BUCKET
        while (t <= ok.receivedMono) t += BUCKET
        return t
    }

    /** §2.4: `20 s × 2^(n−1)`, capped at 300 s, ±20 % by [jitter] ∈ [-1, 1]; `Retry-After` wins. */
    fun backoffAt(failMono: Long, failures: Int, retryAfterS: Int?, jitter: Double): Long {
        if (retryAfterS != null && retryAfterS >= 0) return failMono + retryAfterS * 1000L
        return failMono + backoffMs(failures, jitter)
    }

    fun backoffMs(failures: Int, jitter: Double): Long {
        val n = (failures - 1).coerceIn(0, 16)
        val base = minOf(SportsConstants.BACKOFF_BASE_MS shl n, SportsConstants.BACKOFF_MAX_MS)
        return (base * (1.0 + SportsConstants.BACKOFF_JITTER * jitter.coerceIn(-1.0, 1.0))).roundToLong()
    }

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}
