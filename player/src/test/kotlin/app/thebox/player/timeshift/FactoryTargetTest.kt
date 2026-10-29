// WP-S2 (docs/design/m6-storage-locations.md §5.1, §5.2, §5.4, §5.5, §6, §16.2 FactoryTargetTest).
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TimeshiftPolicyImpl
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.storage.FallbackRingStorage
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.fakes.FakeRingTargetSource
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.SmbRingStorage
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackSettings
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FactoryTargetTest {
    private val root: File = Files.createTempDirectory("ts-targets").toFile()
    private var now = 50_000L
    private val clock = MonoClock { now }
    private val link = FakeRemoteLink(MonoClock { System.nanoTime() / 1_000_000L }).apply { queuePosted = true }
    private val opened = mutableListOf<RemoteRingStorage>()
    private val posix = FakePosix()
    private var free = 10_000_000_000L
    private var volFree = 32L shl 30
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/w"), 3, clock,
        posix = posix, statFs = { d -> TimeshiftStorageImpl.Space(32_000_000_000L, if (d.path.contains("vol")) volFree else free) }, counterIo = { it.run() },
    )
    private val targets = FakeRingTargetSource(choice = internalChoice(File(root, "cache/timeshift")))
    private val blocks = mutableListOf<Int>()
    private val ringFactory = TsRingFactory { st, c, b, e, len -> blocks += b; TsRingImpl(st, c, b, e, len) }
    private val writers = FakeWriterFactory()
    private val writerTransport = FakeWriterTransport()
    private val transport = object : TransportFactory {
        override fun liveTs(ctx: StreamSessionContext) = error("unused")
        override fun http(ctx: StreamSessionContext) = error("unused")
        override fun ring(ctx: StreamSessionContext, open: RingOpen) = error("unused")
        override fun endSession(tuneId: Long) = Unit
        override fun writer(ctx: StreamSessionContext, hooks: app.thebox.player.internal.WriterHooks) = writerTransport.writer(ctx, hooks)
    }
    private val posted = mutableListOf<Runnable>()

    private fun factory(
        memoryClassMb: Int = 256, lowRam: Boolean = false, policy: TimeshiftPolicy = FakePolicy(),
        source: RingTargetSource = targets, background: (Runnable) -> Unit = { it.run() },
    ) = TimeshiftSessionFactoryImpl(
        storage, policy, ringFactory, writers, Provider { FakeIndexer() }, transport, clock,
        lowRam = lowRam, background = background, targets = source, memoryClassMb = memoryClassMb,
    )

    private var made: TimeshiftSessionFactoryImpl? = null

    @After fun tearDown() {
        made?.currentRing()?.close()
        root.deleteRecursively()
    }

    private fun grant(f: TimeshiftSessionFactoryImpl, settings: PlaybackSettings = PlaybackSettings()): TimeshiftGrant {
        made = f
        val r = runBlocking { f.prepare(settings, 1) }
        assertTrue("granted", r is TimeshiftPrepared.Granted)
        return (r as TimeshiftPrepared.Granted).grant
    }

    private fun smb(ready: Boolean = true, id: String = "smb-1") = smbChoice(link, MonoClock { System.nanoTime() / 1_000_000L }, opened, id = id, ready = ready)

    private fun snap(stop: RecordStop? = null) = LocalBufferState(identity = null, available = stop == null, stop = stop)

    // ------------------------------------------------------------------ pool size: one per cycle, by the chosen kind

    @Test
    fun poolSizeFollowsTheChosenKindAndMemoryClass() {
        targets.choice = smb()
        val big = factory(memoryClassMb = 256)
        grant(big)
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL, blocks.last())
        assertEquals(StorageConstants.SMB_OPEN_DEADLINE_MS, (big.currentStorage() as FallbackRingStorage).deadlineMs)
        big.currentRing()?.close()

        val small = factory(memoryClassMb = 128)
        grant(small)
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL_SMALL, blocks.last())
        assertEquals(StorageConstants.SMB_OPEN_DEADLINE_SMALL_POOL_MS, (small.currentStorage() as FallbackRingStorage).deadlineMs)
        small.currentRing()?.close()

        targets.choice = internalChoice(storage.dir)
        grant(factory(memoryClassMb = 512))
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS, blocks.last())
        made?.currentRing()?.close()
        grant(factory(lowRam = true))
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS_LOW_RAM, blocks.last())
    }

    @Test
    fun aTuneTimeFallbackKeepsTheRemotePoolAndSavesHere() {
        targets.choice = smbFellBackChoice(storage.dir, LocationFallback.UNREACHABLE)
        val f = factory()
        val g = grant(f)
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL, blocks.last())
        assertTrue(f.currentStorage() is AndroidRingStorage)
        val s = f.storageFields(g, snap())
        assertEquals(StorageKind.INTERNAL, s.storage)
        assertEquals(LocationFallback.UNREACHABLE, s.fallback)
        assertEquals("smb-1", s.locationId)
        assertEquals("NAS", s.storageName)
    }

    // ------------------------------------------------------------------ reuse and rebuild (§5.1)

    @Test
    fun reuseNeedsTheSameLocationPoolAndAHealthyStorage() {
        val f = factory()
        targets.choice = smb()
        val g1 = grant(f)
        assertSame(g1.ring, grant(f).ring) // same folder, same pool
        assertEquals(1, opened.size)

        targets.choice = smb(id = "smb-2")
        val g3 = grant(f)
        assertNotSame(g1.ring, g3.ring) // another location
        assertEquals(2, opened.size)

        targets.choice = internalChoice(storage.dir)
        val g4 = grant(f)
        targets.choice = smbFellBackChoice(storage.dir, LocationFallback.UNREACHABLE)
        val g5 = grant(f)
        assertNotSame("same location (this device), another pool size", g4.ring, g5.ring)

        targets.choice = smb()
        val g6 = grant(f)
        // Q-D9: the real storage sets needsFreshFile only after a reconnect or a timeout on the flusher; a storage that is
        // gone is the same "never reuse" case for the factory (storageUnusable) and needs no I/O here.
        opened.last().close()
        assertTrue(opened.last().gone)
        val g7 = grant(f)
        assertNotSame("a gone storage (A-B4)", g6.ring, g7.ring)
        // A fresh name on every rebuild at one location (§4.5): smb-1's files were n = 1, 2, 3.
        val names = (opened.take(1) + opened.takeLast(2)).map { (it as SmbRingStorage).relativePath }
        assertEquals(3, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun aSwitchedCycleIsNeverReusedAndSavesOnThisDeviceWithTheWearCounter() {
        val f = factory()
        targets.choice = smbChoice(link, clock, opened) // the storage on the factory clock (deadlines compare)
        val g = grant(f)
        val fb = f.currentStorage() as FallbackRingStorage
        assertTrue("the real storage (Q-D9)", fb.primary is SmbRingStorage)
        // The folder fails before its first write: the §5.2 early switch, as the flusher triggers it. The real create sees
        // the dead transport, reconnects once (refused) and answers false at once, so the switch is synchronous.
        link.dropTransport()
        link.reconnectFails = true
        val before = storage.writtenToday()
        fb.pwrite(ByteBuffer.wrap(ByteArray(1000)), TimeshiftConstants.RING_HEADER_BYTES.toLong())
        assertEquals(StorageKind.INTERNAL, fb.switchedTo)
        assertEquals(LocationFallback.UNREACHABLE, fb.fallbackReason)
        assertEquals("§5.5: the secondary counts wear", before + 1000, storage.writtenToday())
        assertEquals("nothing reached the folder", 0L, fb.primary.remoteBytesWritten)
        val fbFile = File(storage.dir, "ring-3-fb1.bin")
        assertTrue(fbFile.exists())
        val s = f.storageFields(g, snap())
        assertEquals(StorageKind.INTERNAL, s.storage)
        assertEquals(LocationFallback.UNREACHABLE, s.fallback)
        assertNull("a switched cycle's stop is this device's (today's wording)", f.storageFields(g, snap(RecordStop.DISK_ERROR)).storageFailure)
        link.reconnectFails = false
        val g2 = grant(f)
        assertNotSame("a switched cycle is never reused for the folder", g.ring, g2.ring)
    }

    /** Q-D1 (lead ruling R2): the preview start reaches the target source's warm(), and asks for no target (no prepare). */
    @Test
    fun thePreviewStartWarmsTheTargetSource() {
        val f = factory()
        f.warmSaveLocation()
        assertEquals(1L, targets.warms.get())
        assertEquals(0L, targets.currentCalls.get())
    }

    @Test
    fun theFallbackSecondaryKeepsTheInternalReserve() {
        val local = internalChoice(storage.dir).target as app.thebox.core.playback.timeshift.RingTarget.Local
        val st = storage.openLocal(local, 1L shl 27, preallocate = true, countsWear = true, asFallback = true)
        val reserve = app.thebox.core.storage.StorageLimits.internal(32_000_000_000L)
        assertEquals(reserve.reportedAvailableBytes(free), st.availableBytes())
        assertTrue(st.availableBytes() < free)
        st.delete()
    }

    // ------------------------------------------------------------------ sizing per target (§5.1)

    @Test
    fun aRemoteTargetWithoutRoomFallsBackWithNoSpaceThere() {
        val f = factory(policy = TimeshiftPolicyImpl())
        targets.choice = smb(available = 1L shl 30) // < 64 MiB + 2 GiB reserve
        val g = grant(f)
        assertEquals(LocationFallback.NO_SPACE_THERE, g.choice.fallback)
        assertEquals(StorageKind.INTERNAL, g.choice.target.kind)
        assertTrue(f.currentStorage() is AndroidRingStorage)
        assertEquals(TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL, blocks.last())
    }

    private fun smb(available: Long) = smbChoice(link, MonoClock { System.nanoTime() / 1_000_000L }, opened, available = available)

    @Test
    fun lastKnownGoodSizesForASwitchToThisDevice() {
        free = 3_200_000_000L + (512L shl 20) // internal: total 32 GB → reserve 3.2 GB; ~512 MiB usable
        val f = factory(policy = TimeshiftPolicyImpl())
        targets.choice = smb(ready = true)
        val ready = grant(f).capacityBytes
        targets.choice = smb(ready = false)
        val lkg = grant(f).capacityBytes
        assertTrue("ready $ready > last-known-good $lkg", ready > lkg)
        assertTrue(lkg <= (512L shl 20))
    }

    @Test
    fun theDailyCapAppliesOnlyToTargetsThatCountWear() {
        val policy = object : TimeshiftPolicy by FakePolicy(daily = 1L) {
            override fun mode(setting: app.thebox.core.settings.TimeshiftLength, writtenTodayBytes: Long, dailyLimitBytes: Long?, needsPersist: Boolean) =
                TimeshiftPolicyImpl().mode(setting, writtenTodayBytes, dailyLimitBytes, needsPersist)
        }
        storage.addWritten(10)
        val f = factory(policy = policy)
        targets.choice = internalChoice(storage.dir)
        assertEquals(BufferMode.PAUSE_ONLY, grant(f).mode)
        targets.choice = smb()
        assertEquals(BufferMode.PERSIST, grant(f).mode)
        f.currentRing()?.close()
        val v = File(root, "vol/files")
        targets.choice = volumeChoice(v)
        assertEquals(BufferMode.PERSIST, grant(factory(policy = policy)).mode)
    }

    // ------------------------------------------------------------------ onStorageFailure before the publish (§5.4, A-N5)

    @Test
    fun aDiskStopReachesTheTargetSourceBeforeTheSessionSeesIt() {
        var session: app.thebox.player.internal.TimeshiftSession? = null
        val stopsSeenBySource = mutableListOf<RecordStop?>()
        val source = object : RingTargetSource by targets {
            override fun onStorageFailure(locationId: String, stop: RecordStop, failure: StorageFailure?, sinceFirstWriteMs: Long) {
                stopsSeenBySource += session?.snapshot(now, 0L)?.stop
                targets.onStorageFailure(locationId, stop, failure, sinceFirstWriteMs)
            }
        }
        val f = factory(source = source)
        targets.choice = smbChoice(link, clock, opened) // the storage on the factory clock (deadlines compare)
        val g = grant(f)
        // C-B1: only a decided cycle reports (the folder took the file); see aStopBeforeTheDecisionIsNotTheFoldersFailure.
        link.queuePosted = false
        (f.currentStorage() as FallbackRingStorage).pwrite(ByteBuffer.wrap(ByteArray(100)), 4096L)
        val events = mutableListOf<String>()
        val outer = object : RingWriterListener {
            override fun onFirstBytes() = Unit
            override fun onFailure(e: IOException) = Unit
            override fun onStopped(reason: RecordStop) { events += "stopped:$reason failures=${targets.failures.size}" }
            override fun onSegment(reason: SegmentReason, gapMs: Long) = Unit
        }
        session = f.start(g, EngineTestSupport.context(tuneId = 7), outer)
        val w = writers.last
        w.listener!!.onStopped(RecordStop.DISK_TOO_SLOW)
        assertEquals(listOf("stopped:DISK_TOO_SLOW failures=1"), events)
        assertEquals(listOf<RecordStop?>(null), stopsSeenBySource) // the session had not recorded the stop yet
        val fail = targets.failures.single()
        assertEquals("smb-1", fail.locationId)
        assertEquals(RecordStop.DISK_TOO_SLOW, fail.stop)
        assertEquals(StorageFailure.SLOW, fail.failure)
        assertEquals("the stop came with the first write: inside the grace", 0L, fail.sinceFirstWriteMs)
        w.listener!!.onStopped(RecordStop.DISK_ERROR)
        assertEquals("once per cycle", 1, targets.failures.size)
        assertEquals(StorageFailure.SLOW, f.storageFields(g, snap(RecordStop.DISK_TOO_SLOW)).storageFailure)
        session.close()
    }

    /**
     * C-B1: a disk stop while the folder has not answered yet (4K, a sleeping disk) is not the folder's failure: nothing
     * is reported, and the late decision announces no fallback, so the viewer gets one toast (the stop's), not two.
     */
    @Test
    fun aStopBeforeTheDecisionIsNotTheFoldersFailure() {
        val f = factory()
        targets.choice = smbChoice(link, clock, opened)
        val g = grant(f)
        val fb = f.currentStorage() as FallbackRingStorage
        assertTrue(fb.undecided)
        val s = f.start(g, EngineTestSupport.context(tuneId = 10), RecordingListener())
        writers.last.listener!!.onStopped(RecordStop.DISK_TOO_SLOW)
        assertTrue("not reported", targets.failures.isEmpty())
        link.queuePosted = false
        try { fb.pwrite(ByteBuffer.wrap(ByteArray(100)), 4096L) } catch (_: IOException) { }
        assertNull("no switch announced", fb.fallbackReason)
        assertNull(f.storageFields(g, snap(RecordStop.DISK_TOO_SLOW)).fallback)
        assertEquals(StorageFailure.SLOW, f.storageFields(g, snap(RecordStop.DISK_TOO_SLOW)).storageFailure)
        s.close()
    }

    /**
     * C-S3: the folder is READY but refuses the ring file (write access removed). The switch reaches the target source
     * (which demotes the folder: the next `current()` is this device with SIGN_IN_NEEDED), and that next zap keeps the
     * switched ring and its local file: no new 10 MiB pool, no new file.
     */
    @Test
    fun aCreateRefusedOnAReadyFolderDemotesItAndTheNextZapIsInternalWithoutRebuild() {
        val f = factory()
        targets.choice = smbChoice(link, clock, opened)
        val g1 = grant(f)
        val fb = f.currentStorage() as FallbackRingStorage
        link.queuePosted = false
        link.denyWrites()
        fb.pwrite(ByteBuffer.wrap(ByteArray(100)), 4096L)
        assertEquals(StorageKind.INTERNAL, fb.switchedTo)
        assertEquals(listOf("smb-1" to StorageFailure.ACCESS), targets.createFailures)
        val ringsBuilt = blocks.size
        // What the demoted keeper answers now (StorageTargetsImpl; RingTargetSourceTest covers that side).
        targets.choice = smbFellBackChoice(storage.dir, LocationFallback.SIGN_IN_NEEDED)
        val g2 = grant(f)
        assertSame("the switched ring is kept", g1.ring, g2.ring)
        assertEquals(ringsBuilt, blocks.size)
        assertSame(fb, f.currentStorage())
        val s = f.storageFields(g2, snap())
        assertEquals(StorageKind.INTERNAL, s.storage)
        assertEquals(LocationFallback.SIGN_IN_NEEDED, s.fallback)
        // Back on the folder (it took sign-in again): a rebuild on it.
        link.denyWrites(false)
        targets.choice = smb()
        assertNotSame(g1.ring, grant(f).ring)
    }

    @Test
    fun anInternalCycleReportsNothing() {
        val f = factory()
        targets.choice = internalChoice(storage.dir)
        val g = grant(f)
        val outer = RecordingListener()
        val s = f.start(g, EngineTestSupport.context(tuneId = 8), outer)
        writers.last.listener!!.onStopped(RecordStop.DISK_ERROR)
        assertTrue(targets.failures.isEmpty())
        assertNull(f.storageFields(g, snap(RecordStop.DISK_ERROR)).storageFailure)
        s.close()
    }

    @Test
    fun theFirstWriteOfTheCycleDrivesTheGrace() {
        val f = factory()
        targets.choice = smbChoice(link, clock, opened) // the storage on the factory clock (deadlines compare)
        val g = grant(f)
        val fb = f.currentStorage() as FallbackRingStorage
        assertTrue("the real storage (Q-D9)", fb.primary is SmbRingStorage)
        link.queuePosted = false
        fb.pwrite(ByteBuffer.wrap(ByteArray(100)), 4096L) // the real create + the first remote write, at `now`
        assertNull(fb.switchedTo)
        assertTrue("the write reached the folder", fb.primary.remoteBytesWritten > 0)
        assertEquals(now, fb.firstWriteMonoMs)
        val s = f.start(g, EngineTestSupport.context(tuneId = 9), RecordingListener())
        now += 25_000
        writers.last.listener!!.onStopped(RecordStop.DISK_ERROR)
        assertEquals(25_000L, targets.failures.single().sinceFirstWriteMs)
        s.close()
    }

    // ------------------------------------------------------------------ USB (§3.2, §6)

    @Test
    fun aVolumeRingIsNotPreallocatedCountsNoWearAndEjectsOffTheCallersThread() {
        val v = File(root, "storage/ABCD-1234/Android/data/app/files")
        targets.choice = volumeChoice(v)
        val f = factory(background = { posted += it })
        val g = grant(f)
        val vs = f.currentStorage() as VolumeStorage
        assertEquals(File(v, "timeshift"), vs.file.parentFile)
        assertTrue(vs.blocking)
        vs.allocate(TimeshiftConstants.RING_HEADER_BYTES + 4L * TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals("no preallocation on USB", 0, posix.fallocates)
        val before = storage.writtenToday()
        vs.pwrite(ByteBuffer.wrap(ByteArray(2000)), TimeshiftConstants.RING_HEADER_BYTES.toLong())
        assertEquals("USB wear is not counted", before, storage.writtenToday())
        assertTrue(vs.nlinkReliable)
        assertEquals(StorageKind.VOLUME, f.storageFields(g, snap()).storage)

        assertFalse(f.onVolumeGone("OTHER-0000"))
        assertTrue(f.onVolumeGone("ABCD-1234"))
        assertEquals(RecordStop.STORAGE_EVICTED, g.ring.stop)
        assertTrue(vs.isUnlinked())
        assertTrue("the close is posted", posted.isNotEmpty())
        posted.toList().forEach { it.run() }
        assertEquals(StorageFailure.REMOVED, f.storageFields(g, snap(RecordStop.STORAGE_EVICTED)).storageFailure)
        // The mount path matches too.
        assertFalse(f.onVolumeGone(null, "/somewhere/else"))
    }

    @Test
    fun aVolumeWithoutRoomFallsBackWithNoSpaceThere() {
        volFree = 300L shl 20 // < 64 MiB + 512 MiB reserve
        val f = factory(policy = TimeshiftPolicyImpl())
        targets.choice = volumeChoice(File(root, "vol2/files").apply { mkdirs() })
        val g = grant(f)
        assertTrue(f.currentStorage() is AndroidRingStorage)
        assertEquals(LocationFallback.NO_SPACE_THERE, g.choice.fallback)
    }

    private class RecordingListener : RingWriterListener {
        val stops = mutableListOf<RecordStop>()
        override fun onFirstBytes() = Unit
        override fun onFailure(e: IOException) = Unit
        override fun onStopped(reason: RecordStop) { stops += reason }
        override fun onSegment(reason: SegmentReason, gapMs: Long) = Unit
    }
}
