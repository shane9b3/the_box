package app.thebox.core.playback.errors

import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.LoadRetryRules
import kotlin.math.roundToLong

/**
 * Pure status/exception-kind → retry delay rules used by WP-B's policy (§4.2). `null` = do not retry
 * (`C.TIME_UNSET`).
 *
 * - [status] non-null: an `InvalidResponseCodeException` status.
 * - [status] null: an `IOException`; [transient] = reset/timeout/DNS (retryable), false = parser/cleartext/
 *   file-not-found/unexpected-loader (never retried). `LiveStreamException`s are WP-B's: they never reach here.
 * - [errorCount] is Media3's `LoadErrorInfo.errorCount` (1 for the first error). [random] in [0, 1] sets the jitter.
 */
class LoadRetryRulesImpl : LoadRetryRules {
    override fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long? {
        val n = errorCount.coerceAtLeast(1)
        if (status != null) {
            return when {
                status in ErrorConstants.NON_RETRYABLE_STATUSES -> null
                status == HTTP_NOT_FOUND ->
                    if (origin == LoadOrigin.HLS_SEGMENT && n <= ErrorConstants.HLS_SEGMENT_404_RETRIES) ErrorConstants.HLS_SEGMENT_404_DELAY_MS else null
                status == HTTP_RANGE_NOT_SATISFIABLE -> null
                status == HTTP_REQUEST_TIMEOUT || status == HTTP_TOO_MANY_REQUESTS || status in SERVER_ERRORS -> backoff(n, random)
                else -> null
            }
        }
        if (!transient) return null
        return when (origin) {
            LoadOrigin.HLS_MANIFEST, LoadOrigin.HLS_SEGMENT, LoadOrigin.ARCHIVE -> backoff(n, random)
            // Live TS retries inside LiveTsDataSource; an unknown origin follows the conservative path.
            LoadOrigin.PROGRESSIVE, LoadOrigin.UNKNOWN -> null
        }
    }

    private fun backoff(errorCount: Int, random: Double): Long? {
        if (errorCount > ErrorConstants.LOAD_RETRY_MAX) return null
        val base = ErrorConstants.LOAD_RETRY_BASE_DELAYS_MS[errorCount - 1]
        return jitter(base, ErrorConstants.LOAD_RETRY_JITTER, random)
    }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = 500..599

        /** [base] × (1 ± [fraction]); [random] 0 → −fraction, 0.5 → exact, 1 → +fraction. */
        fun jitter(base: Long, fraction: Double, random: Double): Long {
            val r = random.coerceIn(0.0, 1.0)
            return (base * (1.0 + fraction * (2.0 * r - 1.0))).roundToLong()
        }
    }
}
