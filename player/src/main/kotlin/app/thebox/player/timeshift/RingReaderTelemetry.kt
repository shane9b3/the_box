// M6 time-shift, WP-B (docs/design/m6-timeshift.md §5.2, reviewed 2026-10-19, A-B3).
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.player.internal.StreamTelemetry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The telemetry the stall detectors, stats and the controller see for one **reader** (player) session (§5.2, A-B3).
 *
 * | Field | Value |
 * |---|---|
 * | `bytesTotal`, `lastByteAtMs`, `firstByteAtMs` | bytes delivered **to the player** by this reader |
 * | `readPendingSinceMs` | the writer's, only while this reader's `read()` is blocked at the head; else 0 |
 * | `inReconnect` | (writer reconnecting ∥ writer restart pending ∥ writer waiting for a lease) **and** the reader is at the head |
 * | `waitingForLease` | the writer's, only at the head and before this reader delivered a byte |
 * | `httpStatus`, `finalHost`, `redirects`, `reconnects`, `bitrateBps` | the writer's (stats only) |
 *
 * Behind live the writer's state shows only in `localBuffer`, so a recorder reconnect never looks like a player stall.
 * Written by [RingDataSource] on the loader thread; read on main. Nothing here allocates per read.
 */
internal class RingReaderTelemetry(
    private val writer: StreamTelemetry,
    /** Writer restart pending or the recorder has no connection right now (the session's view). */
    private val writerBusy: () -> Boolean,
    private val clock: MonoClock,
    /** Fallback reader lookup (`TimeshiftSession.readerTelemetry(reader)`); the data source normally [attach]es. */
    private val readerFn: () -> RingReader? = { null },
) : StreamTelemetry {
    private val bytes = AtomicLong()
    @Volatile private var attached: RingReader? = null
    @Volatile private var readPending = false

    /** Times Media3 asked the ring reader to open at a non-zero position (§5.1: ignored, counted). */
    val positionIgnoredCount = AtomicInteger()

    override val bytesTotal: Long get() = bytes.get()
    @Volatile override var lastByteAtMs: Long = 0L
        private set
    @Volatile override var firstByteAtMs: Long = 0L
        private set

    override val bitrateBps: Long get() = writer.bitrateBps
    override val httpStatus: Int get() = writer.httpStatus
    override val finalHost: String? get() = writer.finalHost
    override val redirects: Int get() = writer.redirects
    override val reconnects: Int get() = writer.reconnects
    override val sniffed: StreamFormat get() = StreamFormat.TS

    override val inReconnect: Boolean
        get() = (writer.inReconnect || writer.waitingForLease || writerBusy()) && atHead()

    override val waitingForLease: Boolean
        get() = writer.waitingForLease && bytes.get() == 0L && atHead()

    override val readPendingSinceMs: Long
        get() = if (readPending && atHead()) writer.readPendingSinceMs else 0L

    /** The reader currently open for this session (null after close). */
    val reader: RingReader? get() = attached ?: readerFn()

    /** The reader is at the ring's head (live edge); false while no reader is open. */
    fun atHead(): Boolean = (attached ?: readerFn())?.atHead == true

    internal fun attach(reader: RingReader?) {
        attached = reader
    }

    internal fun onReadStarted() {
        readPending = true
    }

    internal fun onReadEnded() {
        readPending = false
    }

    internal fun onBytes(n: Int) {
        if (n <= 0) return
        val now = clock.nowMs()
        if (bytes.getAndAdd(n.toLong()) == 0L) firstByteAtMs = now
        lastByteAtMs = now
    }
}
