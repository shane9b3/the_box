// WP-S2 (docs/design/m6-storage-locations.md §5.5 SmbPrepareNoIoTest, §14 "Link calls on the prepare path"; Must, A-B1).
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.storage.FallbackRingStorage
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.fakes.FakeRingTargetSource
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TransportFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import javax.inject.Provider

/**
 * The **real** [TimeshiftSessionFactoryImpl] (and the real `TsRingImpl` with its dedicated flusher) over a
 * [FakeRemoteLink] whose share is unreachable and stalled: every case of §5.5 makes **zero** link calls on the
 * `prepare` path and returns at once (the tune never waits, lead ruling 2). Posted work (CLOSE, `rm`) is queued, never
 * run, and is not a call on the path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmbPrepareNoIoTest {
    private val root: File = Files.createTempDirectory("ts-smb-noio").toFile()
    private val clock = MonoClock { System.nanoTime() / 1_000_000L }
    private val link = FakeRemoteLink(clock).apply {
        queuePosted = true
        ready = false
        stallUntil(Long.MAX_VALUE / 4)
    }
    private val opened = mutableListOf<RemoteRingStorage>()
    private val policy = FakePolicy()
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/w"), 3, clock,
        posix = FakePosix(), statFs = { TimeshiftStorageImpl.Space(32_000_000_000L, 10_000_000_000L) },
        counterIo = { it.run() },
    )
    private val targets = FakeRingTargetSource(choice = smbChoice(link, clock, opened))
    private val transport = object : TransportFactory {
        override fun liveTs(ctx: StreamSessionContext) = error("unused")
        override fun http(ctx: StreamSessionContext) = error("unused")
        override fun ring(ctx: StreamSessionContext, open: RingOpen) = error("unused")
        override fun endSession(tuneId: Long) = Unit
        override fun writer(ctx: StreamSessionContext, hooks: app.thebox.player.internal.WriterHooks) = error("unused")
    }
    private val factory = TimeshiftSessionFactoryImpl(
        storage, policy, TsRingFactory { st, c, blocks, entries, len -> TsRingImpl(st, c, blocks, entries, len) },
        FakeWriterFactory(), Provider { FakeIndexer() }, transport, clock,
        lowRam = false, background = { it.run() }, targets = targets, memoryClassMb = 256,
    )

    @After fun tearDown() {
        factory.currentRing()?.close()
        root.deleteRecursively()
    }

    /** One prepare: granted, fast, and no link call at all. */
    private fun prepareNoIo(what: String): TimeshiftGrant {
        val callsBefore = link.calls.get()
        val ioBefore = link.ioCalls.get()
        val t0 = System.nanoTime()
        val r = runBlocking { factory.prepare(PlaybackSettings(), 1) }
        val ms = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("$what: granted", r is TimeshiftPrepared.Granted)
        assertEquals("$what: link I/O calls on prepare", ioBefore, link.ioCalls.get())
        assertEquals("$what: link calls on prepare (${link.log})", callsBefore, link.calls.get())
        assertTrue("$what: prepare took $ms ms with the folder stalled", ms < 2_000L)
        return (r as TimeshiftPrepared.Granted).grant
    }

    @Test
    fun everyPrepareCaseMakesNoLinkCall() {
        // First build.
        val g1 = prepareNoIo("first build")
        assertTrue(factory.currentStorage() is FallbackRingStorage)
        assertEquals(1, opened.size)

        // Reuse (a zap on the same folder).
        val g2 = prepareNoIo("reuse")
        assertSame(g1.ring, g2.ring)
        assertEquals(1, opened.size)

        // Rebuild (the capacity changed): the old storage's close/delete is posted, never run here.
        policy.capacity = 128L shl 20
        val g3 = prepareNoIo("rebuild")
        assertNotSame(g2.ring, g3.ring)
        assertEquals(2, opened.size)

        // SMB → INTERNAL.
        targets.choice = internalChoice(storage.dir)
        prepareNoIo("SMB to internal")

        // INTERNAL → SMB.
        targets.choice = smbChoice(link, clock, opened)
        val g5 = prepareNoIo("internal to SMB")
        assertEquals(3, opened.size)

        // SMB → SMB with needsFreshFile (A-B4): a fresh name, n + 1.
        markNeedsFreshFile(opened.last())
        val g6 = prepareNoIo("needsFreshFile")
        assertNotSame(g5.ring, g6.ring)
        assertEquals(4, opened.size)

        // A broken ring (a disk stop made it RAM-only).
        g6.ring.stopRecording(RecordStop.DISK_ERROR)
        val g7 = prepareNoIo("broken ring")
        assertNotSame(g6.ring, g7.ring)

        // SMB chosen, unreachable at tune: this device, the remote pool kept.
        targets.choice = smbFellBackChoice(storage.dir, LocationFallback.UNREACHABLE)
        prepareNoIo("tune-time fallback")

        // Last-known-good without READY.
        targets.choice = smbChoice(link, clock, opened, ready = false)
        val g9 = prepareNoIo("last-known-good")

        // Discard (stop, lifecycle): no call either.
        factory.abandon(g9, discardFile = true)
        val calls = link.calls.get()
        factory.discardNow()
        assertEquals("discard", calls, link.calls.get())
        // C-N2: none of these storages ever sent a CREATE, so there is no CLOSE or rm to post at all.
        assertEquals("nothing on the server to remove", 0, link.log.count { it.startsWith("delete:") })
    }

    private fun markNeedsFreshFile(st: RemoteRingStorage) {
        when (st) {
            is NoIoRemoteStorage -> st.needsFreshFile = true
            // WP-S1's real storage sets it after a reconnect or a write timeout; make one happen on the flusher's path
            // only when the real class is present (its own tests cover the flag).
            else -> st.close()
        }
    }
}
