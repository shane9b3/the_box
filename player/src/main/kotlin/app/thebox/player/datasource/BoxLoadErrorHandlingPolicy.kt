package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.LoadRetryRules
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/**
 * Loader-level policy (§4.2). One instance per `MediaSource`, constructed with the session's [LoadOrigin] family
 * (reviewed S3: progressive errors all report `C.DATA_TYPE_MEDIA`).
 *
 * The policy only classifies; the numbers come from WP-A's [LoadRetryRules]:
 * - any [LiveStreamException] → fatal (the source already retried, or the ladder must decide at once);
 * - Media3's non-retriable causes (`ParserException`, `FileNotFoundException`, `CleartextNotPermittedException`,
 *   `Loader.UnexpectedLoaderException`, a position-out-of-range 416) → fatal;
 * - 401, 403, 405, 406, 410, 416, 451, 458, 509 → fatal on every origin (§4.2 "surface fast; the ladder decides");
 * - otherwise `rules.retryDelayMs(status, origin, transient, errorCount, random)` with `status` from an
 *   `InvalidResponseCodeException` in the chain (`transient = false`), or `status = null, transient = true` for any
 *   other `IOException`; `null` from the rules → fatal.
 *
 * Origin: [origin] for progressive and archive sessions; inside an HLS session (constructed with [LoadOrigin.HLS_MANIFEST]
 * or [LoadOrigin.HLS_SEGMENT]) `C.DATA_TYPE_MANIFEST` → [LoadOrigin.HLS_MANIFEST] and every other data type (media,
 * init segment, key) → [LoadOrigin.HLS_SEGMENT].
 */
@OptIn(UnstableApi::class)
internal class BoxLoadErrorHandlingPolicy(
    private val origin: LoadOrigin,
    private val rules: LoadRetryRules,
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : LoadErrorHandlingPolicy {

    /** No location/track fallback in v1: excluding an HLS variant on a 403 would hide a connection-limit problem. */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (findInChain<LiveStreamException>(e) != null) return C.TIME_UNSET
        if (isNonRetriable(e)) return C.TIME_UNSET
        val status = findInChain<HttpDataSource.InvalidResponseCodeException>(e)?.responseCode
        if (status != null && status in SURFACE_FAST_STATUSES) return C.TIME_UNSET
        val delay = rules.retryDelayMs(
            status = status,
            origin = originFor(loadErrorInfo.mediaLoadData.dataType),
            transient = status == null,
            errorCount = loadErrorInfo.errorCount,
            random = random(),
        )
        return delay ?: C.TIME_UNSET
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE) MIN_RETRY_PROGRESSIVE_LIVE else MIN_RETRY_DEFAULT

    override fun onLoadTaskConcluded(loadTaskId: Long) = Unit

    /** The [LoadOrigin] a failure of [dataType] is reported under (see the class KDoc). */
    fun originFor(dataType: Int): LoadOrigin = when (origin) {
        LoadOrigin.HLS_MANIFEST, LoadOrigin.HLS_SEGMENT ->
            if (dataType == C.DATA_TYPE_MANIFEST) LoadOrigin.HLS_MANIFEST else LoadOrigin.HLS_SEGMENT
        else -> origin
    }

    companion object {
        /** `DATA_TYPE_MEDIA_PROGRESSIVE_LIVE`: the live source retried already; surface after one more attempt. */
        const val MIN_RETRY_PROGRESSIVE_LIVE = 1
        const val MIN_RETRY_DEFAULT = 3

        /**
         * §4.2 row 2 (plus 416): statuses the ladder must see at once on every origin. Enforced here as well as in the
         * rules so that 509 (inside the 5xx range the rules back off on) can never be retried by an ordering slip.
         */
        private val SURFACE_FAST_STATUSES = intArrayOf(401, 403, 405, 406, 410, 416, 451, 458, 509)

        private inline fun <reified T : Throwable> findInChain(e: Throwable): T? {
            var cur: Throwable? = e
            var guard = 0
            while (cur != null && guard++ < 16) {
                if (cur is T) return cur
                cur = if (cur.cause === cur) null else cur.cause
            }
            return null
        }

        /** Media3's own non-retriable set (`DefaultLoadErrorHandlingPolicy.isNonRetriableException`). */
        private fun isNonRetriable(e: IOException): Boolean {
            var cur: Throwable? = e
            var guard = 0
            while (cur != null && guard++ < 16) {
                if (cur is ParserException || cur is FileNotFoundException ||
                    cur is HttpDataSource.CleartextNotPermittedException || cur is Loader.UnexpectedLoaderException
                ) return true
                if (cur is DataSourceException && cur.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) return true
                cur = if (cur.cause === cur) null else cur.cause
            }
            return false
        }
    }
}
