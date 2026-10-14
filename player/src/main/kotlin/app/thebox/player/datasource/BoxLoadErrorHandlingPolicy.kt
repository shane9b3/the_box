package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.LoadRetryRules

/**
 * Loader-level policy (§4.2). One instance per `MediaSource`, constructed with the session's [LoadOrigin] family
 * (reviewed S3: progressive errors all report `C.DATA_TYPE_MEDIA`).
 */
@OptIn(UnstableApi::class)
internal class BoxLoadErrorHandlingPolicy(
    private val origin: LoadOrigin,
    private val rules: LoadRetryRules,
) : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = TODO("WP-B")

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = TODO("WP-B")

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = TODO("WP-B")
}
