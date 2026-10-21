// M6 time-shift, WP-B (docs/design/m6-timeshift.md §2 "Ownership", §4.2, §4.4, §5.2, §7, A-C1, A-C4).
package app.thebox.player.timeshift

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.PinOwner
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.core.playback.timeshift.RingWriter
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TsIndexer
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.player.api.LocalBufferState
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftSession
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.WriterHooks
import java.io.IOException

/**
 * One controller cycle's time-shift (§2 "Ownership"): the `box-ts-pump` writer under the cycle's [writerTuneId], the ring
 * generation it writes, and the stashed ladder error (§7.4).
 *
 * Threads: [start], [onPause]/[onResume], [restartWriter], [stopSaving] and [close] on main; the [RingWriterListener]
 * calls and the [WriterHooks] arrive on the pump thread and only update volatile state before the controller's listener
 * (which posts to main, A-C1). Close order (M2 §3.3.7, A-S1): the writer is told to stop, then the transport session of
 * [writerTuneId] ends (the call is cancelled, then the lease released), then the writer is closed; the pump's exit is
 * joined on IO by the next `prepare()` before the ring is reset.
 */
internal class TimeshiftSessionImpl(
    override val writerTuneId: Long,
    override val grant: TimeshiftGrant,
    private val writer: RingWriter,
    private val indexer: TsIndexer,
    private val writerCtx: StreamSessionContext,
    private val transport: TransportFactory,
    private val policy: TimeshiftPolicy,
    private val clock: MonoClock,
    private val lengthMs: Long,
    private val onClosed: (session: TimeshiftSessionImpl, writer: RingWriter, discardFile: Boolean) -> Unit,
    /** Daily-cap state for `limitedToday` (§8.4). */
    private val limitedToday: () -> Boolean = { false },
) : TimeshiftSession {
    override val ring: TsRing get() = grant.ring
    private val generation: Long = grant.ring.generation

    @Volatile private var telemetry: StreamTelemetry = NoTelemetry
    override val writerTelemetry: StreamTelemetry get() = telemetry

    override var stashed: Pair<PlaybackError, FailureSignal>? = null

    @Volatile override var firstBytes = false
        private set
    @Volatile override var restartPending = false
        private set
    @Volatile override var paused = false
        private set
    @Volatile override var stopReason: RecordStop? = null
        private set
    @Volatile override var stoppedAtMonoMs: Long? = null
        private set
    @Volatile private var closed = false
    @Volatile private var pausedAtMs = 0L

    /** Not-saved ranges in mono ms (gaps and stops), oldest first; main and pump threads under the lock. */
    private val gaps = ArrayList<LongArray>()
    private val gapLock = Any()

    private lateinit var listener: RingWriterListener

    private val hooks = WriterHooks(
        onPcrJump = { delta -> BoxLog.i(TAG) { "writer $writerTuneId: PCR jump of $delta ms kept (boundary in the ring)" } },
        // A-C4 (lead reconciliation R3): the writer owns the cap; a refusal stops it with SLOT_LOST (onStopped).
        // Fix pass B1: the cause and "first of this loss" decide whether the attempt counts.
        reconnectAllowed = { cause, firstOfLoss -> !closed && writer.reconnectAllowed(cause, firstOfLoss) },
        onSplice = { indexer.onSplice() },
    )

    private val wrapped = object : RingWriterListener {
        override fun onFirstBytes() {
            firstBytes = true
            restartPending = false
            stopReason = null
            stoppedAtMonoMs = null
            listener.onFirstBytes()
        }

        override fun onFailure(e: IOException) = listener.onFailure(e)

        override fun onStopped(reason: RecordStop) {
            if (reason != RecordStop.SESSION_END) {
                stopReason = reason
                stoppedAtMonoMs = clock.nowMs()
            }
            restartPending = false
            // FULL / HOLD / SLOT_LOST / PREEMPTED / UPSTREAM_FAILED release the connection (§4.4); disk stops keep it.
            if (reason in RELEASES_CONNECTION) transport.endSession(writerTuneId)
            listener.onStopped(reason)
        }

        override fun onSegment(reason: SegmentReason, gapMs: Long) {
            if (reason == SegmentReason.WRITER_RESTART || reason == SegmentReason.GAP) restartPending = false
            if (gapMs >= TimeshiftConstants.GAP_MIN_MS) {
                val end = clock.nowMs()
                synchronized(gapLock) {
                    if (gaps.size >= TimeshiftConstants.MAX_SEGMENTS) gaps.removeAt(0)
                    gaps += longArrayOf(end - gapMs, end)
                }
            }
            listener.onSegment(reason, gapMs)
        }
    }

    /** Main thread: opens the first upstream on the pump (the writer's first byte never waits for storage, A-S9). */
    fun start(listener: RingWriterListener) {
        this.listener = listener
        val (upstream, t) = transport.writer(writerCtx, hooks)
        telemetry = t
        writer.start(upstream, wrapped)
    }

    override fun onPause(nowMonoMs: Long) {
        if (closed) return
        paused = true
        pausedAtMs = nowMonoMs
        writer.onPause(nowMonoMs, grant.holdLimitMs)
    }

    override fun onResume() {
        if (closed) return
        paused = false
        writer.onResume()
    }

    override fun restartWriter(resolved: ResolvedStream, reason: SegmentReason) {
        if (closed) return
        restartPending = true
        // The writer reports the not-saved stretch as the new segment's gap (onSegment, GAP / WRITER_RESTART).
        stopReason = null
        stoppedAtMonoMs = null
        val ctx = StreamSessionContext(
            tuneId = writerTuneId,
            resolved = resolved,
            broker = writerCtx.broker,
            leaseKind = writerCtx.leaseKind,
            sinkFactory = writerCtx.sinkFactory,
            onEvent = writerCtx.onEvent,
        )
        val (upstream: TsUpstream, t) = transport.writer(ctx, hooks)
        telemetry = t
        writer.restart(upstream, reason)
    }

    override fun markRestartPending() {
        if (!closed) restartPending = true
    }

    override fun stopSaving(reason: RecordStop) {
        if (closed) return
        if (stopReason == null) {
            stopReason = reason
            stoppedAtMonoMs = clock.nowMs()
        }
        restartPending = false
        writer.stop(reason)
        if (reason in RELEASES_CONNECTION) transport.endSession(writerTuneId)
    }

    override fun readerTelemetry(reader: () -> RingReader?): StreamTelemetry =
        RingReaderTelemetry(writer = ProxyTelemetry(), writerBusy = { restartPending }, clock = clock, readerFn = reader)

    override fun snapshot(nowMonoMs: Long, wallMinusMonoMs: Long): LocalBufferState {
        val r = ring
        val storageFailed = stopReason in STORAGE_STOPS
        if (closed) return LocalBufferState(identity = null, available = false, off = BufferOff.STORAGE_ERROR, wallMinusMonoMs = wallMinusMonoMs)
        val index = r.index
        val entry = LongArray(2)
        val oldest = index.oldestRapEntry(entry) // R1: slot and PTS mono from one read
        val from = if (oldest >= 0) entry[1] else 0L
        val live = if (oldest >= 0) index.newestPtsMono() else 0L
        val stop = stopReason
        val saving = stop == null && r.saving
        val rate = rateBytesPerSec()
        val used = (r.head - r.tail).coerceAtLeast(0L)
        val free = (r.capacityBytes - used).coerceAtLeast(0L)
        val pauseRemaining = if (paused && saving) {
            val hold = grant.holdLimitMs?.let { (it - (nowMonoMs - pausedAtMs)).coerceAtLeast(0L) }
            policy.pauseRemainingMs(hold, free, rate)
        } else null
        // UX-S1: the window's width is stable for the session (no rescale with the measured rate): the length, or less
        // when the storage was limited, at the rate the capacity was sized for (§8.2, the setting row's "N min").
        val window = minOf(lengthMs, r.capacityBytes * 1000 / TimeshiftConstants.ASSUMED_BYTES_PER_SEC)
        return LocalBufferState(
            identity = null,
            available = !storageFailed,
            off = if (storageFailed) BufferOff.STORAGE_ERROR else null,
            fromMonoMs = from,
            liveMonoMs = live,
            atMonoMs = live,
            wallMinusMonoMs = wallMinusMonoMs,
            saving = saving,
            stop = stop,
            stoppedAtMonoMs = stoppedAtMonoMs,
            notSavedMono = notSaved(from, live),
            mode = r.mode,
            windowMs = window,
            holdMs = grant.holdLimitMs,
            pauseRemainingMs = pauseRemaining,
            usedBytes = used,
            capacityBytes = r.capacityBytes,
            limitedToday = r.mode == BufferMode.PAUSE_ONLY && limitedToday(),
        )
    }

    private fun notSaved(from: Long, live: Long): TimeRanges {
        val out = ArrayList<Long>(8)
        synchronized(gapLock) {
            for (g in gaps) {
                if (g[1] <= from) continue
                out += maxOf(g[0], from)
                out += g[1]
            }
        }
        // Saving stopped and not restarted: from the stop to live is not saved.
        val stoppedAt = stoppedAtMonoMs
        if (stoppedAt != null && stopReason != null && live > stoppedAt) {
            out += maxOf(stoppedAt, from)
            out += live
        }
        return if (out.isEmpty()) TimeRanges.EMPTY else TimeRanges(out.toLongArray())
    }

    /** Measured stream rate (bytes/s), else the assumed one (§8.2). */
    fun rateBytesPerSec(): Long = (telemetry.bitrateBps / 8).takeIf { it > 0 } ?: TimeshiftConstants.ASSUMED_BYTES_PER_SEC

    override fun close() = close(discardFile = false)

    override fun close(discardFile: Boolean) {
        if (closed) return
        closed = true
        try {
            writer.stop(RecordStop.SESSION_END)
        } catch (t: Throwable) {
            BoxLog.w(TAG, "writer stop failed", t)
        }
        // Cancel the recorder's call first, then its lease goes (TransportSession.end, §3.3.7).
        transport.endSession(writerTuneId)
        try {
            ring.clearPin(PinOwner.PLAYHEAD)
        } catch (t: Throwable) {
            BoxLog.w(TAG, "pin clear failed", t)
        }
        try {
            writer.close()
        } catch (t: Throwable) {
            BoxLog.w(TAG, "writer close failed", t)
        }
        onClosed(this, writer, discardFile)
    }

    /** The writer's telemetry as it is now (a restart swaps the transport session's telemetry). */
    private inner class ProxyTelemetry : StreamTelemetry {
        override val bytesTotal: Long get() = telemetry.bytesTotal
        override val lastByteAtMs: Long get() = telemetry.lastByteAtMs
        override val firstByteAtMs: Long get() = telemetry.firstByteAtMs
        override val bitrateBps: Long get() = telemetry.bitrateBps
        override val httpStatus: Int get() = telemetry.httpStatus
        override val finalHost: String? get() = telemetry.finalHost
        override val redirects: Int get() = telemetry.redirects
        override val inReconnect: Boolean get() = telemetry.inReconnect
        override val reconnects: Int get() = telemetry.reconnects
        override val sniffed get() = telemetry.sniffed
        override val waitingForLease: Boolean get() = telemetry.waitingForLease
        override val readPendingSinceMs: Long get() = telemetry.readPendingSinceMs
    }

    private object NoTelemetry : StreamTelemetry {
        override val bytesTotal = 0L
        override val lastByteAtMs = 0L
        override val firstByteAtMs = 0L
        override val bitrateBps = 0L
        override val httpStatus = 0
        override val finalHost: String? = null
        override val redirects = 0
        override val inReconnect = false
        override val reconnects = 0
        override val sniffed = app.thebox.core.playback.StreamFormat.TS
    }

    override fun toString(): String = "TimeshiftSession(writer=$writerTuneId, gen=$generation)"

    companion object {
        private const val TAG = "TimeshiftSession"

        /** Stops after which the recorder holds no connection (§4.4). */
        val RELEASES_CONNECTION = setOf(
            RecordStop.FULL_WHILE_PAUSED, RecordStop.HOLD_LIMIT, RecordStop.SLOT_LOST, RecordStop.PREEMPTED,
            RecordStop.UPSTREAM_FAILED, RecordStop.SESSION_END,
        )

        /** Storage stops (§4.6): the buffer is unavailable for the rest of the cycle. */
        val STORAGE_STOPS = setOf(RecordStop.DISK_ERROR, RecordStop.STORAGE_EVICTED, RecordStop.DISK_TOO_SLOW)
    }
}
