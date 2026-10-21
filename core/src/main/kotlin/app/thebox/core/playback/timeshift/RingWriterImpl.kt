// WP-A (docs/design/m6-timeshift.md §4.1, §4.2, §4.4, §4.5, A-S1, A-S2, A-S3, A-C2, A-C4).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSED_RECONNECTS_MAX
import app.thebox.core.playback.timeshift.TimeshiftConstants.PAUSED_RECONNECT_WINDOW_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.PIN_STALL_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.SEGMENT_STORM_COUNT
import app.thebox.core.playback.timeshift.TimeshiftConstants.SEGMENT_STORM_WINDOW_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.locks.LockSupport

/**
 * The `box-ts-pump` writer (§4.1): one long-lived thread per session that owns its upstream's lifecycle and loops
 * `claim → upstream.read(slot) → indexer.scan → commit`, so the bytes land directly in the ring's RAM block.
 *
 * - **Commands** ([restart], [stop], [close]) are posted; the caller's thread `terminate()`s the current upstream so a
 *   blocked `read` returns, and the pump opens the next upstream only after the old one returned and was closed (A-S1).
 * - **Listener** calls all run on the pump thread (A-C1). [RingWriterListener.onFirstBytes] once per writer;
 *   [RingWriterListener.onFailure] for every upstream failure (open or read) the writer did not resolve itself, after
 *   which the pump idles until [restart] or [stop] (the controller's ladder decides, A-C2);
 *   [RingWriterListener.onStopped] for the stops the writer decides (FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST, and the
 *   disk stops seen on the ring), never for a [stop] the controller asked for;
 *   [RingWriterListener.onSegment] for every new segment.
 * - **FULL (A-S3).** Paused: stop with `FULL_WHILE_PAUSED` and release the connection. Playing: wait (backpressure
 *   reaches TCP); after [PIN_STALL_MS] without space, keep draining the upstream into a scratch buffer and start a `GAP`
 *   segment once space returns.
 * - **Hold limit** on the mono clock from [onPause] → `HOLD_LIMIT`.
 * - **Reconnect cap (A-C4; fix pass B1).** [reconnectAllowed] is the `LiveTsDataSource.reconnectAllowed` hook: while
 *   paused or behind live, more than [PAUSED_RECONNECTS_MAX] server-initiated losses (a close or reset, once per loss)
 *   or refusals (every attempt) within [PAUSED_RECONNECT_WINDOW_MS] answer false, and the read's failure then stops the
 *   writer with `SLOT_LOST` instead of reaching the ladder. Our own watchdog cancels and network errors never count; at
 *   the head the history is cleared, and so it is on pause, resume and restart.
 * - **Ownership fence (fix pass B2, L3).** The writer marks itself the ring's owner at [start]; its exit ends the
 *   generation (`SESSION_END`) and clears the ring's segment listener only while it is still that owner, so a late
 *   pump never ends or deafens a newer writer's recording.
 * - **Daily counter.** [writtenBytes] counts bytes committed; the ring's `persistedBytes` counts bytes written to disk.
 */
class RingWriterImpl(
    private val ring: TsRing,
    private val indexer: TsIndexer,
    private val clock: MonoClock,
    private val tag: String,
) : RingWriter {
    private val impl: TsRingImpl? = ring as? TsRingImpl
    private val sink: IndexSink = impl?.sink ?: NO_SINK
    private val lock = Object()

    // commands (guarded by lock)
    private var cmdRestart: TsUpstream? = null
    private var cmdRestartReason: SegmentReason? = null
    private var cmdStop: RecordStop? = null
    private var cmdClose = false

    @Volatile private var current: TsUpstream? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var listener: RingWriterListener? = null
    @Volatile private var paused = false
    @Volatile private var holdDeadline = Long.MAX_VALUE
    @Volatile private var slotLostPending = false
    /** The cap's history (B1): pump thread and main ([onPause]/[onResume]), under [capLock]; rare, never per packet. */
    private val capLock = Any()
    private val reconnectTimes = LongArray(PAUSED_RECONNECTS_MAX + 1) { Long.MIN_VALUE }
    private var reconnectIdx = 0
    /** Bytes were committed by this writer (a restart before the first byte reuses the empty segment: no storm count). */
    @Volatile private var committedAny = false
    /** The ring's segment listener this writer installed (L3: cleared on exit only if still this one). */
    private val segmentHook: (SegmentReason, Long) -> Unit = { reason, gap -> onSegment(reason, gap) }
    private val stormTimes = LongArray(SEGMENT_STORM_COUNT) { Long.MIN_VALUE }
    private var stormIdx = 0
    private var stormReported = false

    /** Bytes committed to the ring by this writer. */
    @Volatile var writtenBytes = 0L; private set

    // ================================================================ RingWriter (any thread)

    override fun start(upstream: TsUpstream, listener: RingWriterListener) {
        synchronized(lock) {
            check(thread == null) { "writer already started" }
            this.listener = listener
            current = upstream
            val gen = ring.generation
            impl?.owner = this
            val t = Thread({ pump(upstream, gen) }, if (tag.isEmpty()) "box-ts-pump" else "box-ts-pump-$tag")
            t.isDaemon = true
            thread = t
            t.start()
        }
    }

    override fun restart(upstream: TsUpstream, reason: SegmentReason) {
        val old: TsUpstream?
        synchronized(lock) {
            if (cmdClose) { upstream.close(); return }
            cmdRestart?.close()
            cmdRestart = upstream
            cmdRestartReason = reason
            cmdStop = null
            old = current
        }
        old?.terminate()
        wakePump()
    }

    override fun stop(reason: RecordStop) {
        val old: TsUpstream?
        synchronized(lock) {
            if (cmdClose) return
            cmdStop = reason
            cmdRestart?.close(); cmdRestart = null
            old = current
        }
        old?.terminate()
        wakePump()
    }

    override fun onPause(nowMonoMs: Long, holdLimitMs: Long?) {
        holdDeadline = if (holdLimitMs == null) Long.MAX_VALUE else nowMonoMs + holdLimitMs
        paused = true
        clearReconnectHistory() // B1: nothing from live is carried into the pause
    }

    override fun onResume() {
        paused = false
        holdDeadline = Long.MAX_VALUE
        clearReconnectHistory()
    }

    override fun awaitExit(timeoutMs: Long): Boolean {
        val t = thread ?: return true
        if (t === Thread.currentThread()) return false
        t.join(timeoutMs.coerceAtLeast(1))
        return !t.isAlive
    }

    override fun close() {
        val old: TsUpstream?
        synchronized(lock) {
            if (cmdClose) return
            cmdClose = true
            cmdRestart?.close(); cmdRestart = null
            old = current
        }
        old?.terminate()
        wakePump()
        if (thread == null && ownsRing()) ring.stopRecording(RecordStop.SESSION_END)
    }

    /** B2 "also": this writer is the ring's current writer (fakes and foreign rings: always). */
    private fun ownsRing(): Boolean = impl == null || impl.owner == null || impl.owner === this

    /**
     * `LiveTsDataSource.reconnectAllowed` (A-C4; fix pass B1), on the pump thread before each in-source reconnect
     * attempt. At the head it always answers true and clears the history. While paused or behind, only a server close
     * or reset (once per loss, [firstOfLoss]) and a refusal (every attempt) are recorded; the third within the window
     * answers false and arms `SLOT_LOST`. Our own watchdog cancels and network errors are never a slot fight.
     */
    override fun reconnectAllowed(cause: ReconnectCause, firstOfLoss: Boolean): Boolean {
        val behind = paused || impl?.anyReaderAtHead() == false
        if (!behind) { clearReconnectHistory(); return true }
        val counted = cause == ReconnectCause.REFUSED ||
            (firstOfLoss && (cause == ReconnectCause.SERVER_CLOSED || cause == ReconnectCause.RESET))
        if (!counted) return true
        val now = clock.nowMs()
        synchronized(capLock) {
            reconnectTimes[reconnectIdx] = now
            reconnectIdx = (reconnectIdx + 1) % reconnectTimes.size
            var n = 0
            for (t in reconnectTimes) if (t != Long.MIN_VALUE && now - t < PAUSED_RECONNECT_WINDOW_MS) n++
            if (n > PAUSED_RECONNECTS_MAX) { slotLostPending = true; return false }
        }
        return true
    }

    private fun clearReconnectHistory() = synchronized(capLock) {
        java.util.Arrays.fill(reconnectTimes, Long.MIN_VALUE)
        reconnectIdx = 0
    }

    // ================================================================ pump

    private fun pump(first: TsUpstream, generation: Long) {
        val slot = RingSlot().also { it.generation = generation }
        var up: TsUpstream? = first
        var opened = false
        var carry = 0
        var firstBytes = false
        var fullSince = Long.MIN_VALUE
        var discarding = false
        var discardStart = 0L
        var scratch: ByteArray? = null
        var lastStop: RecordStop? = null
        var stoppedAt = Long.MIN_VALUE
        var lastArrival = Long.MIN_VALUE
        impl?.segmentListener = segmentHook

        fun closeUp() {
            val u = up ?: return
            up = null; opened = false; carry = 0
            current = null
            try { u.close() } catch (_: RuntimeException) {}
        }

        fun stopSelf(reason: RecordStop) {
            ring.stopRecording(reason)
            closeUp()
            if (stoppedAt == Long.MIN_VALUE) stoppedAt = clock.nowMs()
            lastStop = ring.stop
            listener?.onStopped(reason)
        }

        try {
            while (true) {
                // ------------------------------------------------ commands
                if (takeCommands()) {
                    closeUp()
                    // B2 "also": only the ring's current writer ends its generation.
                    // (Integration 2026-10-19) generation-checked under the ring's lock (a reset can come in between).
                    if (ownsRing()) {
                        if (impl != null) impl.stopRecordingFor(generation, RecordStop.SESSION_END)
                        else if (ring.generation == generation) ring.stopRecording(RecordStop.SESSION_END)
                    }
                    return
                }
                val stopReason = tkStop
                val restartUp = tkRestart
                val restartReason = tkReason
                tkStop = null; tkRestart = null; tkReason = null
                val sr = stopReason
                if (sr != null) {
                    ring.stopRecording(sr)
                    closeUp()
                    if (stoppedAt == Long.MIN_VALUE) stoppedAt = clock.nowMs()
                    lastStop = ring.stop
                }
                val ru = restartUp
                if (ru != null) {
                    closeUp()
                    up = ru
                    synchronized(lock) { current = ru }
                    slotLostPending = false
                    // A fresh upstream (R13): the reconnect cap counts this connection's reconnects only.
                    clearReconnectHistory()
                    discarding = false; fullSince = Long.MIN_VALUE
                    val now = clock.nowMs()
                    val since = if (stoppedAt != Long.MIN_VALUE) stoppedAt else lastArrival
                    val gap = if (since == Long.MIN_VALUE) 0L else maxOf(0L, now - since)
                    if (ring.generation != generation) return
                    ring.startSegment(restartReason ?: SegmentReason.WRITER_RESTART, gap)
                    indexer.reset()
                    stoppedAt = Long.MIN_VALUE
                    lastStop = ring.stop
                }
                val u = up
                if (u == null) { idle(); continue }

                // ------------------------------------------------ open
                if (!opened) {
                    try {
                        u.open(); opened = true
                    } catch (e: IOException) {
                        if (commandPending()) continue
                        closeUp()
                        if (slotLostPending) { slotLostPending = false; stopSelf(RecordStop.SLOT_LOST) } else listener?.onFailure(e)
                        continue
                    }
                }

                // ------------------------------------------------ disk stops seen on the ring (flusher thread)
                val rs = ring.stop
                if (rs != null && rs != lastStop && TsRingImpl.isDiskStop(rs)) { lastStop = rs; listener?.onStopped(rs) }

                // ------------------------------------------------ hold limit
                if (paused && clock.nowMs() >= holdDeadline) { stopSelf(RecordStop.HOLD_LIMIT); continue }

                // ------------------------------------------------ claim
                val r = ring.claim(TS_PACKET, CLAIM_TIMEOUT_MS, paused, slot)
                when (r) {
                    ClaimResult.OK -> {
                        fullSince = Long.MIN_VALUE
                        if (discarding) {
                            discarding = false
                            ring.startSegment(SegmentReason.GAP, maxOf(0L, clock.nowMs() - discardStart))
                            indexer.reset()
                            carry = 0
                        }
                    }
                    ClaimResult.FULL -> {
                        if (paused) { stopSelf(RecordStop.FULL_WHILE_PAUSED); continue }
                        val now = clock.nowMs()
                        if (fullSince == Long.MIN_VALUE) fullSince = now
                        if (now - fullSince >= PIN_STALL_MS) {
                            if (!discarding) { discarding = true; discardStart = now; carry = 0 }
                            val buf = scratch ?: ByteArray(TimeshiftConstants.READER_STAGING_BYTES).also { scratch = it }
                            try {
                                val n = u.read(buf, 0, buf.size)
                                if (n < 0) throw EOFException("upstream ended")
                            } catch (e: IOException) { readFailed(e, ::closeUp, ::stopSelf) }
                        }
                        continue
                    }
                    ClaimResult.DISK_STALLED -> {
                        ring.stopRecording(RecordStop.DISK_TOO_SLOW)
                        continue // the next claim drops the unflushed block (RAM only) or reports FULL
                    }
                    ClaimResult.STOPPED -> {
                        closeUp()
                        if (stoppedAt == Long.MIN_VALUE) stoppedAt = clock.nowMs()
                        lastStop = ring.stop
                        continue
                    }
                    ClaimResult.STALE_GENERATION -> { closeUp(); return }
                }

                // ------------------------------------------------ read
                val n: Int
                try {
                    n = u.read(slot.array, slot.offset + carry, slot.length - carry)
                    if (n < 0) throw EOFException("upstream ended")
                } catch (e: IOException) {
                    ring.commit(slot, 0, clock.nowMs())
                    readFailed(e, ::closeUp, ::stopSelf)
                    continue
                }
                val arrival = clock.nowMs()
                lastArrival = arrival
                val total = carry + n
                val whole = total - total % TS_PACKET
                if (whole > 0) {
                    impl?.beginScan(arrival, generation) // L4: the index sink drops a stale generation's calls
                    indexer.scan(slot.array, slot.offset, whole, ring.head, arrival, sink)
                    ring.commit(slot, whole, arrival)
                    writtenBytes += whole
                    committedAny = true
                    if (!firstBytes) { firstBytes = true; listener?.onFirstBytes() }
                } else {
                    ring.commit(slot, 0, arrival)
                }
                carry = total - whole
            }
        } finally {
            // L3: a late pump never clears a newer writer's listener.
            if (impl != null && impl.segmentListener === segmentHook) impl.segmentListener = null
            closeUp()
        }
    }

    private fun readFailed(e: IOException, closeUp: () -> Unit, stopSelf: (RecordStop) -> Unit) {
        if (commandPending()) return
        if (slotLostPending) { slotLostPending = false; stopSelf(RecordStop.SLOT_LOST); return }
        closeUp()
        listener?.onFailure(e)
    }

    private fun onSegment(reason: SegmentReason, gapMs: Long) {
        listener?.onSegment(reason, gapMs)
        // A pre-first-byte restart reuses the empty first segment (B2): not a storm.
        if (reason == SegmentReason.FIRST || !committedAny) return
        val now = clock.nowMs()
        stormTimes[stormIdx] = now
        stormIdx = (stormIdx + 1) % stormTimes.size
        var n = 0
        for (t in stormTimes) if (t != Long.MIN_VALUE && now - t < SEGMENT_STORM_WINDOW_MS) n++
        if (n >= SEGMENT_STORM_COUNT) {
            if (!stormReported) { stormReported = true; listener?.onFailure(SegmentStormException(n, SEGMENT_STORM_WINDOW_MS)) }
        } else stormReported = false
    }

    // Commands taken by the pump (pump thread only; fields, not locals, so the loop allocates no Ref per turn).
    private var tkRestart: TsUpstream? = null
    private var tkReason: SegmentReason? = null
    private var tkStop: RecordStop? = null

    /** Pump only: moves the posted commands into the tk* fields; true when the writer is closed. */
    private fun takeCommands(): Boolean = synchronized(lock) {
        if (cmdClose) return true
        tkRestart = cmdRestart; tkReason = cmdRestartReason; cmdRestart = null; cmdRestartReason = null
        tkStop = cmdStop; cmdStop = null
        false
    }

    private fun commandPending(): Boolean = synchronized(lock) { cmdClose || cmdRestart != null || cmdStop != null }

    private fun idle() {
        if (commandPending()) return
        LockSupport.parkNanos(this, IDLE_PARK_NS)
    }

    private fun wakePump() { thread?.let { LockSupport.unpark(it) } }

    companion object {
        /** One claim waits at most this long for space before the pump rechecks its commands and timers. */
        const val CLAIM_TIMEOUT_MS = 250L
        private const val IDLE_PARK_NS = 250_000_000L

        private val NO_SINK = object : IndexSink {
            override fun onRap(offset: Long, pts90k: Long, pcr90k: Long, flags: Int) = Unit
            override fun onAnchor(offset: Long, pcr90k: Long, flags: Int) = Unit
            override fun onBoundary(offset: Long, reason: SegmentReason) = Unit
            override fun onPsi(packets: ByteArray, count: Int) = Unit
        }

        /** The [RingWriterFactory] WP-B's Hilt module binds. */
        val FACTORY = RingWriterFactory { ring, indexer, clock, tag -> RingWriterImpl(ring, indexer, clock, tag) }
    }
}
