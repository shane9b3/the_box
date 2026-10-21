package app.thebox.player.timeshift

import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.PinOwner
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.player.api.PlaybackSettings
import app.thebox.core.settings.TimeshiftLength
import app.thebox.player.engine.EngineTestSupport
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TransportFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Provider

/** The session life cycle (§2 "Ownership", §7.1, §8.3, A-C1, A-C2, A-S1): prepare/start/stop ordering and discard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeshiftSessionTest {
    private val root: File = Files.createTempDirectory("ts-session").toFile()
    private var now = 10_000L
    private val clock = MonoClock { now }
    private val ring = FakeTsRing()
    private var ringFactory: TsRingFactory = FakeRingFactory(ring)
    private val writers = FakeWriterFactory()
    private val writerTransport = FakeWriterTransport()
    private val policy = FakePolicy()
    private val order = mutableListOf<String>()
    private val indexers = mutableListOf<FakeIndexer>()
    private var failIndexer = false
    private var failWriterFactory = false
    private var failTransport = false
    private var failEndSession = false
    private val transport = object : TransportFactory {
        override fun liveTs(ctx: StreamSessionContext) = error("unused")
        override fun http(ctx: StreamSessionContext) = error("unused")
        override fun ring(ctx: StreamSessionContext, open: RingOpen) = error("unused")
        override fun endSession(tuneId: Long) { check(!failEndSession) { "transport close failed" }; order += "endSession:$tuneId" }
        override fun writer(ctx: StreamSessionContext, hooks: app.thebox.player.internal.WriterHooks): Pair<app.thebox.core.playback.timeshift.TsUpstream, app.thebox.player.internal.StreamTelemetry> {
            check(!failTransport) { "transport construction failed" }
            return writerTransport.writer(ctx, hooks)
        }
    }
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/w"), 3, clock,
        posix = FakePosix(), statFs = { TimeshiftStorageImpl.Space(32_000_000_000L, 10_000_000_000L) },
    )
    private val factory get() = TimeshiftSessionFactoryImpl(
        storage, policy, ringFactory, app.thebox.core.playback.timeshift.RingWriterFactory { r, i, c, tag ->
            check(!failWriterFactory) { "writer construction failed" }
            writers.create(r, i, c, tag)
        }, Provider { check(!failIndexer) { "indexer construction failed" }; FakeIndexer().also { indexers += it } }, transport, clock,
        lowRam = false, background = { it.run() },
    )
    private val events = mutableListOf<String>()
    private val listener = object : RingWriterListener {
        override fun onFirstBytes() { events += "first" }
        override fun onFailure(e: IOException) { events += "failure" }
        override fun onStopped(reason: RecordStop) { events += "stopped:$reason" }
        override fun onSegment(reason: SegmentReason, gapMs: Long) { events += "segment:$reason" }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun prepare(f: TimeshiftSessionFactoryImpl, prefs: PlaybackSettings = PlaybackSettings()) = runBlocking { f.prepare(prefs, 1) }

    private fun grant(f: TimeshiftSessionFactoryImpl): TimeshiftGrant = (prepare(f) as TimeshiftPrepared.Granted).grant

    private fun ctx(id: Long) = EngineTestSupport.context(tuneId = id)

    @Test
    fun prepareSizesTheRingAllocatesTheFirstExtentAndResetsIt() {
        val f = factory
        val g = grant(f)
        assertEquals(policy.capacity / TimeshiftConstants.RING_BLOCK_BYTES * TimeshiftConstants.RING_BLOCK_BYTES, g.capacityBytes)
        assertEquals(1, (ringFactory as FakeRingFactory).creates)
        assertEquals(1, ring.resets)
        val extent = minOf(g.capacityBytes, TimeshiftConstants.RING_EXTENT_BLOCKS.toLong() * TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals(TimeshiftConstants.RING_HEADER_BYTES + extent, storage.ringFile.length())
    }

    @Test
    fun supersededPreparationDoesNotAllocateStorage() = runBlocking {
        val f = factory
        f.prepare(PlaybackSettings(), 1) { false }
        assertEquals(0, (ringFactory as FakeRingFactory).creates)
        assertFalse(storage.ringFile.exists())
    }

    @Test fun indexerConstructionFailureReturnsTheOutstandingGrant() {
        failIndexer = true
        assertFailedStartDisposesStorage()
    }

    @Test fun writerConstructionFailureReturnsTheOutstandingGrant() {
        failWriterFactory = true
        assertFailedStartDisposesStorage()
    }

    @Test fun transportConstructionFailureClosesThePartiallyRegisteredSession() {
        failTransport = true
        assertFailedStartDisposesStorage()
        assertTrue(writers.last.calls.contains("close"))
    }

    @Test fun failedPartialSessionCloseStillClosesItsWriterAndReturnsOwnership() {
        failTransport = true
        failEndSession = true
        assertFailedStartDisposesStorage()
        assertTrue(writers.last.calls.contains("close"))
    }

    private fun assertFailedStartDisposesStorage() {
        val f = factory
        val g = grant(f)
        try {
            f.start(g, ctx(41), listener)
            throw AssertionError("start should fail")
        } catch (_: IllegalStateException) {
        }
        assertNull(f.currentRing())
        assertFalse(storage.ringFile.exists())
        for (name in listOf("lengths", "limits", "open", "granted")) {
            val state = f.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(f)
            assertTrue("$name must be released after start fails", if (state is Map<*, *>) state.isEmpty() else (state as Collection<*>).isEmpty())
        }
    }

    @Test
    fun startOpensTheWriterUnderTheWriterTuneIdAndCloseCancelsTheCallBeforeClosing() {
        val f = factory
        val s = f.start(grant(f), ctx(41), listener) as TimeshiftSessionImpl
        assertEquals(41L, s.writerTuneId)
        assertEquals(41L, writerTransport.last.ctx.tuneId)
        assertEquals(listOf("start"), writers.last.calls)
        ring.setPin(PinOwner.PLAYHEAD, 100)
        s.close(discardFile = false)
        order.addAll(0, writers.last.calls.map { "writer:$it" })
        // writer told to stop, then the transport session ends (call cancelled, lease released), then the writer closes.
        val calls = writers.last.calls
        assertTrue(calls.indexOf("stop:SESSION_END") < calls.indexOf("close"))
        assertTrue(order.contains("endSession:41"))
        assertFalse(ring.pins.containsKey(PinOwner.PLAYHEAD))
        assertTrue("the file stays for the next tune", storage.ringFile.exists())
    }

    @Test
    fun theNextPrepareJoinsThePreviousPumpBeforeTheLogicalReset() {
        val f = factory
        val s = f.start(grant(f), ctx(41), listener)
        s.close()
        val firstWriter = writers.last
        val resets = ring.resets
        grant(f)
        assertTrue(firstWriter.calls.contains("awaitExit"))
        assertEquals(resets + 1, ring.resets)
        assertEquals("a zap reuses the file and ring", 1, (ringFactory as FakeRingFactory).creates)
    }

    @Test
    fun repeatedZapsAndAbandonedPreparesKeepOnlyOutstandingGrantMetadata() {
        val f = factory
        fun metadataSize(name: String): Int {
            val field = f.javaClass.getDeclaredField(name).apply { isAccessible = true }
            return (field.get(f) as Map<*, *>).size
        }
        repeat(100) {
            val g = grant(f)
            assertEquals(1, metadataSize("lengths"))
            assertEquals(1, metadataSize("limits"))
            if (it % 2 == 0) f.start(g, ctx(it.toLong()), listener).close() else f.abandon(g, false)
            assertEquals(0, metadataSize("lengths"))
            assertEquals(0, metadataSize("limits"))
        }
        val superseded = grant(f)
        grant(f)
        assertEquals(1, metadataSize("lengths"))
        f.abandon(superseded, false)
        assertEquals("late disposal cannot remove the newer grant's metadata", 1, metadataSize("lengths"))
    }

    @Test
    fun stopRemembersDiscardUntilAnEarlierPreparationReturnsItsGrant() {
        val f = factory
        val g = grant(f)
        f.discardUnused()
        assertNotNull("a grant still protects its ring", f.currentRing())
        f.abandon(g, discardFile = false) // the old cycle released it for a zap before the later stop
        assertNull("the later stop upgrades that cleanup to deletion", f.currentRing())
        assertFalse(storage.ringFile.exists())
    }

    @Test
    fun aNewPreparationSupersedesAnOlderDeferredDiscardRequest() {
        val f = factory
        val old = grant(f)
        f.discardUnused()
        val next = grant(f)
        f.abandon(old, discardFile = true)
        f.start(next, ctx(41), listener).close(discardFile = false)
        assertNotNull("a new cycle may retain its own reusable ring", f.currentRing())
        assertTrue(storage.ringFile.exists())
    }

    @Test
    fun aCapacityChangeRebuildsTheRing() {
        val f = factory
        grant(f)
        policy.capacity = 512L shl 20
        grant(f)
        assertEquals(2, (ringFactory as FakeRingFactory).creates)
    }

    @Test
    fun offNoSpaceAndStorageErrorsAreOffReasons() {
        val f = factory
        grant(f)
        assertTrue(storage.ringFile.exists())
        val off = prepare(f, PlaybackSettings(timeshift = TimeshiftLength.OFF))
        assertEquals(BufferOff.SETTING_OFF, (off as TimeshiftPrepared.Off).reason)
        assertFalse("Off deletes the file (§8.3)", storage.ringFile.exists())
        assertTrue(ring.closed)

        policy.capacity = TimeshiftConstants.MIN_RING_BYTES - 1
        assertEquals(BufferOff.NO_SPACE, (prepare(f) as TimeshiftPrepared.Off).reason)

        policy.capacity = 256L shl 20
        ringFactory = TsRingFactory { _: RingStorage, _, _, _, _ -> throw IOException("EIO") }
        assertEquals(BufferOff.STORAGE_ERROR, (prepare(factory) as TimeshiftPrepared.Off).reason)
    }

    @Test
    fun discardOnStopClosesThenDeletesTheFileButNeverUnderAnOpenSession() {
        val f = factory
        val g = grant(f)
        val s1 = f.start(g, ctx(41), listener) as TimeshiftSessionImpl
        f.discardIfIdle()
        assertTrue("an open session keeps its file", storage.ringFile.exists())
        s1.close(discardFile = true)
        assertFalse(storage.ringFile.exists())
        assertTrue(ring.closed)
    }

    @Test
    fun theListenerWrapperReleasesTheConnectionOnlyForConnectionStops() {
        val f = factory
        val s = f.start(grant(f), ctx(41), listener) as TimeshiftSessionImpl
        val l = writers.last.listener!!
        l.onFirstBytes()
        assertTrue(s.firstBytes)
        l.onStopped(RecordStop.DISK_ERROR)
        assertFalse("disk stops keep the connection (§4.4)", order.contains("endSession:41"))
        assertFalse(s.snapshot(now, 0).available)
        l.onStopped(RecordStop.FULL_WHILE_PAUSED)
        assertTrue(order.contains("endSession:41"))
        assertEquals(listOf("first", "stopped:DISK_ERROR", "stopped:FULL_WHILE_PAUSED"), events)
    }

    @Test
    fun pauseCarriesTheHoldLimitAndRestartUsesANewUpstreamUnderTheSameId() {
        policy.hold = 600_000L
        val f = factory
        val s = f.start(grant(f), ctx(41), listener) as TimeshiftSessionImpl
        s.onPause(now)
        assertEquals(600_000L, writers.last.pausedHold)
        s.onResume()
        s.restartWriter(EngineTestSupport.resolved(), SegmentReason.GAP)
        assertTrue(s.restartPending)
        assertEquals(2, writerTransport.opened.size)
        assertEquals(41L, writerTransport.last.ctx.tuneId)
        assertTrue(writers.last.calls.contains("restart:GAP"))
        assertTrue("the old upstream was terminated first", writerTransport.opened[0].upstream.isTerminated)
        writers.last.listener!!.onFirstBytes()
        assertFalse(s.restartPending)
    }

    @Test
    fun theSpliceHookReachesTheWritersIndexer() {
        val f = factory
        f.start(grant(f), ctx(41), listener)
        writerTransport.last.hooks.onSplice()
        assertEquals(1, indexers.last().splices)
    }

    @Test
    fun theSnapshotReportsTheWindowGapsAndPauseRemaining() {
        val f = factory
        val s = f.start(grant(f), ctx(41), listener) as TimeshiftSessionImpl
        ring.append(nullPackets(10))
        ring.addRap(0, 100_000)
        ring.addRap(940, 160_000)
        now = 170_000
        writers.last.listener!!.onSegment(SegmentReason.GAP, 5_000)
        s.onPause(now)
        val b = s.snapshot(now, 1_000)
        assertTrue(b.available)
        assertEquals(100_000L, b.fromMonoMs)
        assertEquals(160_000L, b.liveMonoMs)
        assertEquals(1, b.notSavedMono.count)
        assertEquals(165_000L, b.notSavedMono.startAt(0))
        assertEquals(170_000L, b.notSavedMono.endAt(0))
        assertEquals(17 * 60_000L, b.pauseRemainingMs)
        assertEquals(1_000L, b.wallMinusMonoMs)
        assertNotNull(b.capacityBytes)
    }
}
