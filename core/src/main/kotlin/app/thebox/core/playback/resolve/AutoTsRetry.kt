package app.thebox.core.playback.resolve

import app.thebox.core.playback.XtreamOutput
import app.thebox.core.xtream.XtreamAccountInfo
import java.util.Locale

/**
 * The stored form of `playlist.xtream_output_learned` (docs/decisions/2026-11-25-auto-format-retries-ts.md).
 *
 * The column keeps the output name first, so a value written before the TS retry (`"HLS"`, `"TS"`) still reads: an
 * HLS learned that way has no time and counts as learned long ago. The TS-retry facts ride behind it:
 * `HLS;t=<learned at, epoch ms>;n=<back-off step>;h=<hold-off until, epoch ms>`. Reading any other text gives null.
 */
data class LearnedOutput(
    val output: XtreamOutput,
    /** Wall-clock epoch ms when HLS was (re)learned; null for a value stored before the retry existed. */
    val learnedAtMs: Long? = null,
    /** Index into [AutoTsRetry.BACKOFF_MS]: 0 after the first refusal, 1 and 2 after refused retries (capped). */
    val step: Int = 0,
    /** No retry before this wall-clock time (an inconclusive retry, [AutoTsRetry.HOLD_OFF_MS]). */
    val holdUntilMs: Long? = null,
) {
    fun encode(): String {
        if (output != XtreamOutput.HLS || (learnedAtMs == null && step == 0 && holdUntilMs == null)) return output.name
        return buildString {
            append(output.name)
            learnedAtMs?.let { append(";t=").append(it) }
            if (step != 0) append(";n=").append(step)
            holdUntilMs?.let { append(";h=").append(it) }
        }
    }

    companion object {
        fun parse(raw: String?): LearnedOutput? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(';')
            val name = parts[0].trim().uppercase(Locale.ROOT)
            val output = XtreamOutput.entries.firstOrNull { it.name == name }?.takeIf { it != XtreamOutput.AUTO } ?: return null
            var at: Long? = null
            var step = 0
            var hold: Long? = null
            for (p in parts.drop(1)) {
                val v = p.substringAfter('=', "").trim()
                when (p.substringBefore('=').trim()) {
                    "t" -> at = v.toLongOrNull()
                    "n" -> step = (v.toIntOrNull() ?: 0).coerceIn(0, AutoTsRetry.BACKOFF_MS.size - 1)
                    "h" -> hold = v.toLongOrNull()
                }
            }
            return LearnedOutput(output, at, step, hold)
        }
    }
}

/**
 * (Owner ruling 2026-11-25, docs/decisions/2026-11-25-auto-format-retries-ts.md) A playlist whose Stream format is
 * Auto and that learned HLS after a 405/406 tries MPEG-TS again at a user tune, now and then. Pure: every wall-clock
 * time comes in as an argument.
 */
object AutoTsRetry {
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Wait after HLS was (re)learned before the next retry: 24 h, then 3 days, then 7 days (the cap). */
    val BACKOFF_MS = longArrayOf(DAY_MS, 3 * DAY_MS, 7 * DAY_MS)

    /** An inconclusive retry (it failed for another reason) waits this long before the next one; the step stays. */
    const val HOLD_OFF_MS = HOUR_MS

    /** A TS retry that reached first media and plays this long in the same tune without a refusal clears HLS. */
    const val CONFIRM_MS = 30_000L

    /**
     * A TS retry with no first media and no failure after this long falls back to HLS for the same tune (inconclusive):
     * a TS request that neither fails nor plays never reaches the 15 s switch window. 8 s = LiveTsDataSource's
     * first-byte timeout, so a silent server costs the same either way; this is the worst-case delay the retry adds.
     */
    const val FIRST_MEDIA_BUDGET_MS = 8_000L

    /**
     * (Code review 2026-11-26, B5) A refusal stored less than this long after the previous (re)learn of HLS keeps the
     * step: two tunes refused in quick succession (a fast zap that read the playlist before the first refusal was
     * written) are one refusal, not two steps of the back-off.
     */
    const val REFUSAL_DEDUP_MS = 60_000L

    enum class Result { REFUSED, CONFIRMED, INCONCLUSIVE }

    /** The account allows TS, or its formats are unknown (no card, or an empty list). */
    fun tsAllowed(account: XtreamAccountInfo?): Boolean {
        val allowed = account?.allowedOutputFormats.orEmpty().map { it.trim().lowercase(Locale.ROOT) }
        return allowed.isEmpty() || ResolveConstants.EXT_TS in allowed || "mpegts" in allowed
    }

    /** When the next retry may run (epoch ms), or null when no retry applies to [learned]. */
    fun nextRetryAtMs(learned: LearnedOutput?, nowMs: Long): Long? {
        if (learned?.output != XtreamOutput.HLS) return null
        // A time further ahead than any wait means the device clock was wrong when it was stored: ignore it.
        val at = learned.learnedAtMs?.takeIf { it <= nowMs + BACKOFF_MS.last() } ?: return learned.holdUntil(nowMs) ?: 0L
        val byStep = at + BACKOFF_MS[learned.step.coerceIn(0, BACKOFF_MS.size - 1)]
        return maxOf(byStep, learned.holdUntil(nowMs) ?: 0L)
    }

    private fun LearnedOutput.holdUntil(nowMs: Long): Long? = holdUntilMs?.takeIf { it <= nowMs + HOLD_OFF_MS }

    /** A TS retry is due at this user tune: the setting is Auto, HLS was learned, the account allows TS, the wait is over. */
    fun due(setting: XtreamOutput, learned: LearnedOutput?, account: XtreamAccountInfo?, nowMs: Long): Boolean {
        if (setting != XtreamOutput.AUTO || !tsAllowed(account)) return false
        val next = nextRetryAtMs(learned, nowMs) ?: return false
        return nowMs >= next
    }

    /**
     * `saveLearnedOutput` (the flip reached first media): HLS learned now with a fresh back-off, unless HLS is already
     * stored (a retry that was refused has written its own step first); TS is stored plainly.
     */
    fun afterLearn(previous: LearnedOutput?, to: XtreamOutput?, nowMs: Long): LearnedOutput? = when (to) {
        null, XtreamOutput.AUTO -> null
        XtreamOutput.TS -> LearnedOutput(XtreamOutput.TS)
        XtreamOutput.HLS -> previous?.takeIf { it.output == XtreamOutput.HLS } ?: LearnedOutput(XtreamOutput.HLS, learnedAtMs = nowMs)
    }

    /** The stored value after a retry's [result]; null clears it (plain Auto again). */
    fun afterResult(previous: LearnedOutput?, result: Result, nowMs: Long): LearnedOutput? = when (result) {
        Result.CONFIRMED -> null
        Result.REFUSED -> if (previous?.output == XtreamOutput.HLS && previous.learnedAtMs?.let { nowMs - it in 0 until REFUSAL_DEDUP_MS } == true) {
            previous.copy(holdUntilMs = null)
        } else {
            LearnedOutput(
                XtreamOutput.HLS,
                learnedAtMs = nowMs,
                step = if (previous?.output == XtreamOutput.HLS) (previous.step + 1).coerceAtMost(BACKOFF_MS.size - 1) else 0,
            )
        }
        Result.INCONCLUSIVE -> (previous?.takeIf { it.output == XtreamOutput.HLS } ?: LearnedOutput(XtreamOutput.HLS, learnedAtMs = nowMs))
            .copy(holdUntilMs = nowMs + HOLD_OFF_MS)
    }

    /** Whole days until the next retry of [learned] (rounded up), for the log line. */
    fun daysUntilNext(learned: LearnedOutput?, nowMs: Long): Long {
        val next = nextRetryAtMs(learned, nowMs) ?: return 0
        return ((next - nowMs).coerceAtLeast(0) + DAY_MS - 1) / DAY_MS
    }
}
