package app.thebox.player.datasource

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.MonoClock
import app.thebox.player.internal.StreamSessionContext
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException

/**
 * HLS and archive (finite) HTTP sources over the brokered call factory (§3.4). Range allowed, no reconnect.
 *
 * - Headers: `setDefaultRequestProperties(resolved.headers)` and never `setUserAgent` (it would add a second UA).
 * - Lease: one per session, acquired at the first open (the manifest) through [SessionScope.httpCalls] and kept
 *   until `endSession`; every manifest/segment/key call attaches to it, so pre-emption cancels all of them.
 * - Range (reviewed S8): passed through as Media3 asks (HLS byte ranges, archive seeks).
 * - A `thebox-session://` URI (an archive `MediaItem` that hides its URL, review N5) is replaced by
 *   `ctx.resolved.url`; failures then carry the placeholder `DataSpec`, and [BrokeredHttpDataSource.getUri] stays the
 *   placeholder. Real URIs pass through untouched (HLS resolves relative segment URLs against them).
 */
@OptIn(UnstableApi::class)
internal class StreamHttpDataSourceFactory(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
    private val telemetry: SessionTelemetry = SessionTelemetry(DEFAULT_CLOCK),
    private val scope: SessionScope = StandaloneScope(),
    private val clock: MonoClock = DEFAULT_CLOCK,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = BrokeredHttpDataSource(ctx, callFactory, telemetry, scope, clock)

    private companion object {
        val DEFAULT_CLOCK = MonoClock { SystemClock.elapsedRealtime() }
    }
}

/** One HLS/archive request at a time (Media3 creates one per loadable). */
@OptIn(UnstableApi::class)
internal class BrokeredHttpDataSource(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
    private val telemetry: SessionTelemetry,
    private val scope: SessionScope,
    private val clock: MonoClock,
) : BaseDataSource(/* isNetwork = */ true) {
    private val sessionSpecUri = "${LiveTsDataSource.SESSION_SCHEME}://${ctx.tuneId}".toUri()
    private var inner: OkHttpDataSource? = null
    private var calls: BrokeredCallFactory? = null
    @Volatile private var currentCall: Call? = null
    private var outerSpec: DataSpec? = null
    private var placeholder = false
    private var reachedEnd = false
    private var opened = false
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    /** Records this source's own call so [close] can cancel it without touching the session's other loads. */
    private val recordingFactory = Call.Factory { request: Request ->
        val factory = calls ?: throw IOException("no lease")
        factory.newCall(request).also { currentCall = it }
    }

    override fun open(dataSpec: DataSpec): Long {
        outerSpec = dataSpec
        reachedEnd = false
        transferInitializing(dataSpec)
        val factory = try {
            scope.httpCalls(ctx, callFactory, telemetry)
        } catch (e: ConnectionLimitException) {
            throw failure(LiveFailureKind.LOCAL_LIMIT, dataSpec).apply { addSuppressed(e) }
        } catch (e: IllegalStateException) {
            throw failure(LiveFailureKind.PREEMPTED, dataSpec)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("lease wait interrupted")
        } catch (e: RuntimeException) {
            throw failure(LiveFailureKind.LOCAL_LIMIT, dataSpec).apply { addSuppressed(e) }
        }
        calls = factory
        if (factory.leaseIsPreempted) throw failure(LiveFailureKind.PREEMPTED, dataSpec)
        placeholder = dataSpec.uri.scheme == LiveTsDataSource.SESSION_SCHEME
        val spec = if (placeholder) dataSpec.withUri(ctx.resolved.url.toUri()) else dataSpec
        val ds = OkHttpDataSource.Factory(recordingFactory)
            .setDefaultRequestProperties(ctx.resolved.headers)
            .createDataSource()
        inner = ds
        val length = try {
            ds.open(spec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            closeInner()
            if (factory.leaseIsPreempted || scope.isEnded) throw failure(LiveFailureKind.PREEMPTED, dataSpec)
            throw if (placeholder) hide(e, dataSpec) else e
        } catch (e: IllegalArgumentException) {
            // (code review 2026-10-14, N-S1) OkHttp refused a header; its message carries the value. Typed, no cause.
            closeInner()
            throw failure(LiveFailureKind.NETWORK, dataSpec)
        }
        bytesRemaining = length
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ds = inner ?: throw IOException("not open")
        val n = try {
            ds.read(buffer, offset, length)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            if (calls?.leaseIsPreempted == true || scope.isEnded) throw failure(LiveFailureKind.PREEMPTED, outerSpec ?: DataSpec(sessionSpecUri))
            throw if (placeholder) hide(e, outerSpec!!) else e
        }
        if (n == C.RESULT_END_OF_INPUT) {
            reachedEnd = true
            return n
        }
        if (n > 0) {
            telemetry.onBytes(n, clock.nowMs())
            bytesTransferred(n)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= n
                if (bytesRemaining <= 0) reachedEnd = true
            }
        }
        return n
    }

    override fun getUri(): Uri? = if (placeholder) sessionSpecUri else inner?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (placeholder) emptyMap() else inner?.responseHeaders ?: emptyMap()

    override fun close() {
        val wasOpen = opened
        opened = false
        closeInner()
        if (wasOpen) transferEnded()
    }

    /**
     * A body not read to its end is cancelled before it is closed (no 100 ms drain, S2). A fully read body is just
     * closed, which keeps the keep-alive connection for the next segment.
     */
    private fun closeInner() {
        val ds = inner ?: return
        inner = null
        if (!reachedEnd) currentCall?.cancel()
        currentCall = null
        try {
            ds.close()
        } catch (_: Throwable) {
            // OkHttpDataSource.close never throws; nothing to clean up beyond the cancel above.
        }
    }

    private fun failure(kind: LiveFailureKind, spec: DataSpec): LiveStreamException {
        val info = FailureInfo(
            status = telemetry.httpStatus.takeIf { it != 0 },
            host = telemetry.finalHost ?: ctx.resolved.host,
            bytesRead = telemetry.bytesTotal,
            attempt = 0,
            phase = FailureInfo.Phase.OPEN,
            networkKind = null,
            deltaMs = null,
            elapsedMs = 0,
        )
        return LiveStreamException(kind, info, if (placeholder || spec.uri.scheme == LiveTsDataSource.SESSION_SCHEME) DataSpec(sessionSpecUri) else spec, null)
    }

    /** Re-throws [e] against the placeholder spec: no URL, response headers or error body leave this class. */
    private fun hide(e: HttpDataSource.HttpDataSourceException, spec: DataSpec): HttpDataSource.HttpDataSourceException {
        val safe = spec.withUri(sessionSpecUri)
        return if (e is HttpDataSource.InvalidResponseCodeException) {
            HttpDataSource.InvalidResponseCodeException(e.responseCode, null, e.cause?.let(::safeCause), emptyMap(), safe, ByteArray(0))
        } else {
            // (code review 2026-10-14, N-S3) Never keep a DataSourceException (it holds the real DataSpec) as the cause.
            val cause = safeCause(e)
            if (cause != null) {
                HttpDataSource.HttpDataSourceException(cause, safe, e.reason, e.type)
            } else {
                HttpDataSource.HttpDataSourceException(safe, e.reason, e.type)
            }
        }
    }
}

