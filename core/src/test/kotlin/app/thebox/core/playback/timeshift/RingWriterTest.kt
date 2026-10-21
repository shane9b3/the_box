package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** §17.1 `RingWriterTest` (fake `TsUpstream`): failures, hold timer, restart order, reconnect cap, FULL, storms, exit. */
class RingWriterTest {
    private val clock = FakeMonoClock()
    private val flusher = RingFlusher("test-flush-writer")
    private val writers = ArrayList<RingWriterImpl>()

    /** Rings a test closes itself (the flusher holds a ring until it is closed). */
    private val rings = ArrayList<TsRing>()

    @AfterTest fun tearDown() {
        writers.forEach { it.close(); it.awaitExit(2_000) }
        rings.forEach { it.close() }
    }

    private fun ring(capBlocks: Int = 400, pool: Int = 8) = TsRingImpl(MemoryRingStorage(capBlocks * BLOCK), clock, pool, 4_096, flusher)
    private fun writer(ring: TsRing, tag: String = "w") = RingWriterImpl(ring, TsIndexerImpl(), clock, tag).also { writers += it }

    private fun waitFor(what: String, ms: Long = 3_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(1) }
    }

    @Test
    fun firstBytesOnceAndEveryCallbackOnThePumpThread() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream()
        writer(ring, "cb").start(up, l)
        waitFor("bytes") { ring.head > 2 * BLOCK }
        assertEquals(1, l.count("firstBytes"))
        assertTrue(ring.saving)
        assertTrue(l.threads.all { it == "box-ts-pump-cb" }, "${l.threads}")
    }

    @Test
    fun anOpenFailureIsReportedOnceThenTheWriterWaitsForTheLadder() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.openFailure = IOException("403") }
        val w = writer(ring)
        w.start(up, l)
        waitFor("failure") { l.count("failure") == 1 }
        Thread.sleep(100)
        assertEquals(1, l.count("failure"))
        assertEquals(0, l.count("firstBytes"))
        assertEquals(0L, ring.head)
        // The ladder decides RetrySame: restart with a new upstream; the cycle stays buffered.
        val up2 = FakeTsUpstream(name = "up2")
        w.restart(up2, SegmentReason.WRITER_RESTART)
        waitFor("bytes after restart") { ring.head > 0 }
        assertEquals(1, l.count("firstBytes"))
    }

    @Test
    fun aReadFailureIsReportedOnceAndReadingStops() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.readFailure = IOException("reset"); it.failAfterBytes = 100L * 188 }
        writer(ring).start(up, l)
        waitFor("failure") { l.count("failure") == 1 }
        val reads = up.reads.get()
        Thread.sleep(100)
        assertEquals(reads, up.reads.get(), "no read after the failure")
        assertTrue(up.log.contains("close:up"))
        assertEquals(1, l.count("failure"))
    }

    @Test
    fun restartTerminatesTheOldUpstreamBeforeOpeningTheNext() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up1 = FakeTsUpstream(name = "a").also { it.pacing = { Thread.sleep(5) } }
        val w = writer(ring)
        w.start(up1, l)
        waitFor("bytes") { ring.head > 0 }
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val up2 = object : TsUpstream by FakeTsUpstream(name = "b") {
            override fun open() { log += "open:b"; log.addAll(up1.log) }
        }
        clock.advance(3_000)
        w.restart(up2, SegmentReason.WRITER_RESTART)
        waitFor("open b") { log.contains("open:b") }
        val i1 = log.indexOf("terminate:a"); val i2 = log.indexOf("close:a")
        assertTrue(i1 in 0 until i2, "terminate then close: $log")
        assertTrue(log.indexOf("open:b") == 0 && i2 > 0, "open b only after a was closed: $log")
        waitFor("segment") { l.has("segment:WRITER_RESTART") }
        assertTrue(ring.index.segmentGapMs(ring.index.currentSegment) >= 0)
    }

    @Test
    fun theHoldLimitRunsOnTheMonoClock() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.pacing = { Thread.sleep(2) } }
        val w = writer(ring)
        w.start(up, l)
        waitFor("bytes") { ring.head > 0 }
        w.onPause(clock.nowMs(), 5_000)
        clock.advance(4_999)
        Thread.sleep(50)
        assertTrue(ring.saving && ring.stop == null)
        clock.advance(2)
        waitFor("hold limit") { l.has("stopped:HOLD_LIMIT") }
        assertEquals(RecordStop.HOLD_LIMIT, ring.stop)
        assertTrue(up.log.contains("close:up"), "the connection is released")
    }

    @Test
    fun fullWhilePausedStopsAndReleasesTheConnectionThenResumeRestartsWithAGap() {
        val ring = ring(capBlocks = 20, pool = 4)
        val l = RecordingWriterListener()
        val up = FakeTsUpstream()
        val w = writer(ring)
        ring.setPin(PinOwner.PLAYHEAD, 0)
        w.onPause(clock.nowMs(), null)
        w.start(up, l)
        waitFor("full") { l.has("stopped:FULL_WHILE_PAUSED") }
        assertEquals(RecordStop.FULL_WHILE_PAUSED, ring.stop)
        assertTrue(up.log.contains("close:up"))
        assertTrue(ring.head <= ring.capacityBytes)
        assertEquals(0L, ring.tail)
        // Resume: the controller restarts the writer; the new segment is a GAP with the time it was stopped.
        clock.advance(7_000)
        w.onResume()
        ring.clearPin(PinOwner.PLAYHEAD)
        w.restart(FakeTsUpstream(name = "again"), SegmentReason.GAP)
        waitFor("gap segment") { l.has("segment:GAP") }
        assertTrue(ring.index.segmentGapMs(ring.index.currentSegment) >= 7_000)
        assertEquals(null, ring.stop)
    }

    @Test
    fun theReconnectCapWhilePausedStopsWithSlotLostAndNoLadder() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.pacing = { Thread.sleep(1) } }
        val w = writer(ring)
        // Every read is preceded by a reconnect attempt, as LiveTsDataSource would on a kick-oldest panel.
        up.beforeRead = { if (!w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true)) throw IOException("RECONNECT_EXHAUSTED") }
        w.onPause(clock.nowMs(), null)
        w.start(up, l)
        waitFor("slot lost") { l.has("stopped:SLOT_LOST") }
        assertEquals(0, l.count("failure"), "no ladder")
        assertEquals(RecordStop.SLOT_LOST, ring.stop)
        assertTrue(up.log.contains("close:up"))
    }

    @Test
    fun atTheHeadReconnectsAreAlwaysAllowed() {
        val ring = ring()
        val w = writer(ring)
        val r = ring.openReader(0, ring.generation) // a reader at the head
        repeat(10) { assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = true)) }
        r.close()
    }

    // ------------------------------------------------------------------ fix pass B1: what the cap counts

    @Test
    fun reconnectsAtTheHeadAreNotCarriedIntoAPause() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        val r = ring.openReader(0, ring.generation) // at the head
        repeat(5) { // a provider that drops us every 15 s, for a minute, while the viewer is at live
            assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true))
            clock.advance(15_000)
        }
        w.onPause(clock.nowMs(), null)
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true), "the field finding: no SLOT_LOST after a 20 s pause")
        clock.advance(5_000)
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true))
        r.close()
    }

    @Test
    fun watchdogReconnectsWhilePausedNeverCount() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        w.onPause(clock.nowMs(), null)
        var t = 0L
        while (t < 300_000L) {
            assertTrue(w.reconnectAllowed(ReconnectCause.WATCHDOG, firstOfLoss = true), "at $t ms")
            val step = if ((t / 1_000) % 2 == 0L) 10_000L else 20_000L
            clock.advance(step); t += step
        }
    }

    @Test
    fun threeServerLossesWithinAMinuteWhilePausedAreSlotLost() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        w.onPause(clock.nowMs(), null)
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true))
        clock.advance(20_000)
        assertTrue(w.reconnectAllowed(ReconnectCause.RESET, firstOfLoss = true))
        clock.advance(20_000)
        kotlin.test.assertFalse(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true))
    }

    @Test
    fun oneBurstCountsOnceAndNetworkErrorsNever() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        w.onPause(clock.nowMs(), null)
        // One loss: the close opens it, the next two attempts of the burst fail on the network.
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true))
        assertTrue(w.reconnectAllowed(ReconnectCause.NETWORK, firstOfLoss = false))
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = false))
        repeat(5) { assertTrue(w.reconnectAllowed(ReconnectCause.NETWORK, firstOfLoss = true)) }
        assertTrue(w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true), "only the second counted loss")
    }

    @Test
    fun aRefusalOnEveryAttemptIsSlotLostOnTheThird() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        w.onPause(clock.nowMs(), null)
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = true))
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = false))
        kotlin.test.assertFalse(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = false))
    }

    @Test
    fun pauseAndResumeClearTheHistory() {
        val ring = ring(capBlocks = 4, pool = 2).also { rings += it }
        val w = writer(ring)
        w.onPause(clock.nowMs(), null)
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = true))
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = false))
        w.onResume()
        w.onPause(clock.nowMs(), null)
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = true))
        assertTrue(w.reconnectAllowed(ReconnectCause.REFUSED, firstOfLoss = false))
    }

    /** Drives the writer into [stop] by itself (FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST); returns the head at the stop. */
    private fun selfStop(stop: RecordStop, ring: TsRingImpl, w: RingWriterImpl, l: RecordingWriterListener): Long {
        when (stop) {
            RecordStop.FULL_WHILE_PAUSED -> {
                ring.setPin(PinOwner.PLAYHEAD, 0)
                w.onPause(clock.nowMs(), null)
                w.start(FakeTsUpstream(), l)
            }
            RecordStop.HOLD_LIMIT -> {
                w.start(FakeTsUpstream().also { it.pacing = { Thread.sleep(2) } }, l)
                waitFor("bytes") { ring.head > 0 }
                w.onPause(clock.nowMs(), 1_000)
                clock.advance(1_001)
            }
            RecordStop.SLOT_LOST -> {
                val up = FakeTsUpstream().also { it.pacing = { Thread.sleep(1) } }
                up.beforeRead = { if (!w.reconnectAllowed(ReconnectCause.SERVER_CLOSED, firstOfLoss = true)) throw IOException("RECONNECT_EXHAUSTED") }
                w.onPause(clock.nowMs(), null)
                w.start(up, l)
            }
            else -> error("not a self-stop: $stop")
        }
        waitFor("stopped:$stop") { l.has("stopped:$stop") }
        assertEquals(stop, ring.stop)
        assertTrue(!ring.saving)
        return ring.head
    }

    /**
     * Lead reconciliation R13: after each self-stop, ▶ (onResume + restart GAP, §7.2) and Back to live (the same restart,
     * then a reader at the first RAP after the old head, §7.4) resume saving with a GAP segment carrying the stopped time.
     */
    @Test
    fun resumeAndBackToLiveAfterEachSelfStopResumeSaving() {
        for (stop in listOf(RecordStop.FULL_WHILE_PAUSED, RecordStop.HOLD_LIMIT, RecordStop.SLOT_LOST)) {
            for (backToLive in listOf(false, true)) {
                val what = "$stop, ${if (backToLive) "back to live" else "resume"}"
                val ring = if (stop == RecordStop.FULL_WHILE_PAUSED) ring(capBlocks = 20, pool = 4) else ring()
                val l = RecordingWriterListener()
                val w = writer(ring, "r13")
                val oldHead = selfStop(stop, ring, w, l)
                clock.advance(5_000)
                w.onResume()
                ring.clearPin(PinOwner.PLAYHEAD)
                w.restart(FakeTsUpstream(name = "again").also { it.pacing = { Thread.sleep(1) } }, SegmentReason.GAP)
                waitFor("gap segment ($what)") { l.has("segment:GAP") }
                waitFor("saving again ($what)") { ring.saving && ring.head > oldHead + 4 * BLOCK }
                assertEquals(null, ring.stop, what)
                assertTrue(ring.index.segmentGapMs(ring.index.currentSegment) >= 5_000, what)
                assertEquals(1, l.count("firstBytes"), "onFirstBytes is not repeated ($what)")
                assertEquals(0, l.count("failure"), what)
                if (backToLive) {
                    val e = LongArray(2)
                    waitFor("a RAP after the old head ($what)") { ring.index.newestRapEntry(e) >= 0 && e[0] >= oldHead }
                    val r = ring.openReader(e[0], ring.generation)
                    val buf = ByteArray(16 * 188)
                    assertTrue(r.read(buf, 0, buf.size) > 0, what)
                    r.close()
                }
                w.close(); w.awaitExit(2_000)
            }
        }
    }

    @Test
    fun aStopTheControllerAsksForIsNotEchoed() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.pacing = { Thread.sleep(2) } }
        val w = writer(ring)
        w.start(up, l)
        waitFor("bytes") { ring.head > 0 }
        w.stop(RecordStop.UPSTREAM_FAILED)
        waitFor("stopped") { ring.stop == RecordStop.UPSTREAM_FAILED }
        waitFor("closed") { up.log.contains("close:up") }
        assertEquals(0, l.count("stopped"))
    }

    @Test
    fun aSegmentStormIsReportedOnce() {
        val ring = ring()
        val l = RecordingWriterListener()
        val ts = SyntheticTs()
        val up = FakeTsUpstream(ts, chunk = 40 * 188)
        var jumps = 0
        up.beforeRead = {
            clock.advance(40)
            if (ts.frame > 0 && ts.frame % 50 == 0L && jumps < 8 && ts.position > jumps * 2_000_000L) { ts.jump(30L * 90_000, ptsFirst = false); jumps++ }
        }
        writer(ring).start(up, l)
        waitFor("storm", 10_000) { l.failures.isNotEmpty() }
        assertIs<SegmentStormException>(l.failures[0])
        Thread.sleep(50)
        assertTrue(l.failures.size <= 2)
    }

    @Test
    fun closeEndsTheSessionAndTheThreadExits() {
        val ring = ring()
        val l = RecordingWriterListener()
        val up = FakeTsUpstream().also { it.pacing = { Thread.sleep(2) } }
        val w = writer(ring)
        w.start(up, l)
        waitFor("bytes") { ring.head > 0 }
        w.close()
        assertTrue(w.awaitExit(TimeshiftConstants.PUMP_JOIN_MAX_MS))
        assertEquals(RecordStop.SESSION_END, ring.stop)
        assertTrue(up.log.contains("terminate:up") && up.log.contains("close:up"))
    }

    @Test
    fun aSpliceThenAJumpIsAPcrJumpSegmentWithItsGap() {
        val ring = ring()
        val l = RecordingWriterListener()
        val ts = SyntheticTs()
        val up = FakeTsUpstream(ts, chunk = 20 * 188)
        val indexer = TsIndexerImpl()
        var spliced = false
        up.beforeRead = {
            clock.advance(20)
            if (!spliced && ts.frame >= 75) {
                spliced = true
                clock.advance(3_000) // the reconnect took 3 s
                indexer.onSplice()    // LiveTsUpstream: RECONNECT_OK
                ts.jump(600L * 90_000, ptsFirst = false)
            }
        }
        RingWriterImpl(ring, indexer, clock, "splice").also { writers += it }.start(up, l)
        waitFor("pcr jump") { l.has("segment:PCR_JUMP") }
        assertTrue(ring.index.segmentGapMs(1) >= 3_000, "gap ${ring.index.segmentGapMs(1)}")
    }
}
