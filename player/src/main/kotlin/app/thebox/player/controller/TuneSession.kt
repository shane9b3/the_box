package app.thebox.player.controller

import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory

/**
 * Per-tune context (§3.1.1 step 6): one resolved stream, one [StreamSessionContext], one `MediaSource`. Every ladder
 * re-open creates a new session with a new [tuneId]; the player, renderers and surface are reused.
 *
 * The broker lease is acquired by WP-B's data sources on the loader threads through [ctx] (`ctx.broker.acquire`) and is
 * released by the transport after its calls are cancelled and closed, never here (§3.5.2). [end] asks the transport to
 * do that (synchronous `Call.cancel()`), which is the "cancel the old call before opening the next" step of a zap.
 *
 * Main thread only, except [telemetry], whose fields the data sources write.
 */
internal class TuneSession(
    val tuneId: Long,
    val resolved: ResolvedStream,
    val ctx: StreamSessionContext,
    private val transport: TransportFactory,
    /** The format the `MediaSource` was built for (hint, sniff switch or TS default). */
    val format: StreamFormat,
    /** Monotonic ms at which the session was prepared (stale-error and seek-grace reference). */
    val startedAtMs: Long,
) {
    /** Set once the `MediaSource` is built. */
    var telemetry: StreamTelemetry? = null

    /** Last prepare / seek / track change (rebuffers within SEEK_GRACE_MS of it are not counted). */
    var lastDisruptionAtMs: Long = startedAtMs

    /** Which first-media milestone this session waits for (§0.16): decided from the first non-empty onTracksChanged. */
    var milestoneVideo: Boolean? = null
    var audioAdvancingSeen = false
    var firstMedia = false
    var readyAtMs = 0L

    /** `C.DATA_TYPE_*` of the last load error (tells an HLS manifest from a segment, §4.1). */
    var lastLoadDataType: Int? = null

    var ended = false
        private set

    /** Ends the transport of this session (pre-empts its calls, the transport releases the lease). Idempotent. */
    fun end() {
        if (ended) return
        ended = true
        transport.endSession(tuneId)
    }

    override fun toString(): String = "TuneSession($tuneId, $format, ${resolved.host})"
}
