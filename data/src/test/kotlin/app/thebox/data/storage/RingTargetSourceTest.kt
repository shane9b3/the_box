package app.thebox.data.storage

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.VolumeCatalog
import app.thebox.core.storage.smb.FakeStatus
import app.thebox.core.storage.smb.FakeStatusException
import app.thebox.core.storage.smb.FakeTransportException
import app.thebox.data.storage.smb.KeeperState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** §16.2 `RingTargetSourceTest` (WP-S1): every row of §5.1, last-known-good, the grace and demotion doubling. */
class RingTargetSourceTest {
    private fun ready(w: StorageWorld) {
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        quiet(w)
    }

    /** Waits until the keeper's after-connect work (wake, sweep, probe) is done, so I/O counts are ours alone. */
    private fun quiet(w: StorageWorld) {
        var last = -1
        eventually("keeper idle") {
            val n = w.server.ioCalls.get()
            val same = n == last
            last = n
            if (!same) Thread.sleep(150)
            same
        }
    }

    @Test fun internalIsTodaysDirectory() {
        val w = StorageWorld()
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageConstants.INTERNAL_ID, c.chosenId)
        assertEquals(StorageKind.INTERNAL, c.target.kind)
        assertNull(c.fallback)
        assertEquals(File(w.dir, "timeshift"), (c.target as RingTarget.Local).dir)
    }

    @Test fun aMountedVolumeIsChosenAndAMissingOneFallsBack() {
        val w = StorageWorld()
        val vol = VolumeCatalog.Volume("1234-ABCD", "SanDisk", File(w.dir, "usb"), 64L shl 30, 40L shl 30)
        w.volumes.volumes.value = listOf(vol)
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "vol-1234-ABCD") }
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageKind.VOLUME, c.target.kind)
        assertEquals(vol.dir, (c.target as RingTarget.Local).dir)
        assertNull(c.fallback)
        w.volumes.volumes.value = emptyList()
        val gone = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageKind.INTERNAL, gone.target.kind)
        assertEquals(StorageKind.VOLUME, gone.chosenKind)
        assertEquals(LocationFallback.NOT_CONNECTED, gone.fallback)
        assertEquals("SanDisk", gone.target.name) // the chosen place's name for the toast
        w.volumes.volumes.value = listOf(vol.copy(freeBytes = 100L shl 20))
        assertEquals(LocationFallback.NO_SPACE_THERE, w.targets.current(StoragePurpose.TIMESHIFT).fallback)
    }

    @Test fun smbReadyIsChosenAndCurrentDoesNoIo() {
        val w = StorageWorld()
        val id = w.addSmb()
        ready(w)
        val before = w.server.ioCalls.get()
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(before, w.server.ioCalls.get()) // no link call in current() (A-B1)
        assertEquals(StorageKind.SMB, c.target.kind)
        assertNull(c.fallback)
        assertFalse(c.lastKnownGood)
        val t = c.target as RingTarget.Remote
        assertTrue(t.ready)
        // Opening the storage does no I/O either; the file name follows §3.5.
        val name = w.targets.nextRemoteFileName(id)
        assertTrue(name, name.matches(Regex("ring-[0-9a-f]{12}-4242-1\\.bin")))
        assertEquals("ring-${w.deviceId.id}-4242-2.bin", w.targets.nextRemoteFileName(id))
        val st = t.open(name, 64L * TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals(before, w.server.ioCalls.get())
        assertEquals("Rewind/TheBox-rewind/$name", (st as app.thebox.core.storage.smb.SmbRingStorage).relativePath)
    }

    @Test fun lastKnownGoodChoosesSmbBeforeTheFirstConnect() {
        val w = StorageWorld() // the keeper is not started: a fresh process before its first connect
        val id = w.addSmb()
        assertEquals(LocationFallback.NOT_READY_YET, w.targets.current(StoragePurpose.TIMESHIFT).fallback) // no LKG: silent fallback
        w.lastGood.put(id, w.wall - 86_400_000L)
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageKind.SMB, c.target.kind)
        assertTrue(c.lastKnownGood)
        assertFalse((c.target as RingTarget.Remote).ready)
        assertEquals(Long.MAX_VALUE, (c.target as RingTarget.Remote).availableBytes) // unknown space
        // Older than SMB_LAST_GOOD_MS: no longer counts.
        w.lastGood.put(id, w.wall - StorageConstants.SMB_LAST_GOOD_MS - 1)
        assertEquals(LocationFallback.NOT_READY_YET, w.targets.current(StoragePurpose.TIMESHIFT).fallback)
        // Persisted across processes (the first write; later ones within WRITE_EVERY_MS are throttled).
        assertEquals(w.wall - 86_400_000L, app.thebox.data.storage.smb.LastGoodStore(File(w.dir, app.thebox.data.storage.smb.LastGoodStore.FILE_NAME)).get(id))
    }

    @Test fun aFailureInThisProcessIsUnreachable() {
        val w = StorageWorld()
        val id = w.addSmb()
        w.lastGood.put(id, w.wall - 1_000)
        w.opener.fail = { FakeTransportException("refused") }
        w.keeper.startNow()
        eventually("BACKOFF") { w.keeper.snapshot.state == KeeperState.BACKOFF }
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageKind.INTERNAL, c.target.kind)
        assertEquals(LocationFallback.UNREACHABLE, c.fallback) // last-known-good no longer applies (failure seen)
        assertEquals("NAS", c.target.name)
        assertEquals(id, c.chosenId)
        assertEquals(StorageKind.SMB, c.chosenKind)
    }

    @Test fun signInNeededOnAuthFailureAndOnAMissingPassword() {
        val w = StorageWorld()
        w.addSmb()
        w.opener.fail = { FakeStatusException(FakeStatus.LOGON_FAILURE) }
        w.keeper.startNow()
        eventually("AUTH_FAILED") { w.keeper.snapshot.state == KeeperState.AUTH_FAILED }
        assertEquals(LocationFallback.SIGN_IN_NEEDED, w.targets.current(StoragePurpose.TIMESHIFT).fallback)

        // A restore: the location arrives in the backed-up domain, its password never does.
        val w2 = StorageWorld()
        val loc = app.thebox.core.storage.StorageLocation("smb-restored", StorageKind.SMB, "NAS",
            smb = app.thebox.core.storage.SmbAddress("nas", share = "TV", path = "Rewind", user = "shawn"))
        w2.store.update(app.thebox.core.settings.state.StorageDomain) { it.copy(locations = listOf(loc), timeshiftLocationId = loc.id) }
        eventually("sign-in needed") { w2.locations.signInNeeded(loc.id) }
        assertEquals(LocationFallback.SIGN_IN_NEEDED, w2.targets.current(StoragePurpose.TIMESHIFT).fallback)
    }

    @Test fun graceAndDemotionDoubling() {
        val w = StorageWorld()
        val id = w.addSmb()
        ready(w)
        // Within the grace: no demotion; the next zap tries SMB again.
        w.targets.onStorageFailure(id, RecordStop.DISK_ERROR, StorageFailure.SLOW, 5_000L)
        assertEquals(StorageKind.SMB, w.targets.current(StoragePurpose.TIMESHIFT).target.kind)
        // No write yet (−1): no demotion either.
        w.targets.onStorageFailure(id, RecordStop.DISK_TOO_SLOW, StorageFailure.SLOW, -1L)
        assertEquals(StorageKind.SMB, w.targets.current(StoragePurpose.TIMESHIFT).target.kind)
        // Outside: demoted synchronously, before the stop is published (A-N5).
        val t0 = w.clock.nowMs()
        w.targets.onStorageFailure(id, RecordStop.DISK_ERROR, StorageFailure.UNREACHABLE, 60_000L)
        assertEquals(LocationFallback.UNSTABLE, w.targets.current(StoragePurpose.TIMESHIFT).fallback)
        val first = w.keeper.snapshot.demotedUntilMono - t0
        assertTrue("$first", first in StorageConstants.SMB_DEMOTE_MS - 50..StorageConstants.SMB_DEMOTE_MS + 50)
        // A repeat within the window doubles it.
        val t1 = w.clock.nowMs()
        w.targets.onStorageFailure(id, RecordStop.STORAGE_EVICTED, StorageFailure.REMOVED, 60_000L)
        val second = w.keeper.snapshot.demotedUntilMono - t1
        assertTrue("$second", second in 2 * StorageConstants.SMB_DEMOTE_MS - 50..2 * StorageConstants.SMB_DEMOTE_MS + 50)
        // Other stops and internal locations are ignored.
        w.targets.onStorageFailure(StorageConstants.INTERNAL_ID, RecordStop.DISK_ERROR, null, 60_000L)
        w.targets.onStorageFailure(id, RecordStop.HOLD_LIMIT, null, 60_000L)
        assertEquals(second, w.keeper.snapshot.demotedUntilMono - t1)
    }

    @Test fun noSpaceThereFromTheCachedFreeSpace() {
        val w = StorageWorld()
        w.addSmb()
        w.server.fullAt(StorageConstants.NETWORK_FREE_SPACE_RESERVE) // < MIN_RING_BYTES + reserve
        ready(w)
        assertEquals(LocationFallback.NO_SPACE_THERE, w.targets.current(StoragePurpose.TIMESHIFT).fallback)
    }

    @Test fun rewindOffKeepsTheKeeperIdle() {
        val w = StorageWorld()
        w.addSmb()
        w.store.update(app.thebox.core.settings.state.PlaybackDomain) { it.copy(timeshift = app.thebox.core.settings.TimeshiftLength.OFF) }
        w.keeper.startNow()
        Thread.sleep(200)
        assertEquals(KeeperState.IDLE, w.keeper.snapshot.state)
        assertEquals(0, w.opener.opens.get())
    }

    /**
     * C-S3: the folder refuses the ring file (write access removed) while its keepalive still succeeds. The switch is
     * reported: the folder is demoted at once (nothing was written: no grace), the next tune saves on this device with
     * the matching reason, and the page says why.
     */
    @Test fun aCreateRefusedOnAReadyFolderDemotesItWithThatReason() {
        val w = StorageWorld()
        val id = w.addSmb()
        ready(w)
        assertEquals(StorageKind.SMB, w.targets.current(StoragePurpose.TIMESHIFT).target.kind)
        w.targets.onCreateFailed(id, StorageFailure.ACCESS)
        val c = w.targets.current(StoragePurpose.TIMESHIFT)
        assertEquals(StorageKind.INTERNAL, c.target.kind)
        assertEquals(LocationFallback.SIGN_IN_NEEDED, c.fallback)
        eventually("the page says why") { w.locations.status.value.single().failure == StorageFailure.ACCESS }
        // FULL names the space; an unreachable create only marks a failure and re-checks the link.
        val w2 = StorageWorld()
        val id2 = w2.addSmb()
        ready(w2)
        w2.targets.onCreateFailed(id2, StorageFailure.FULL)
        assertEquals(LocationFallback.NO_SPACE_THERE, w2.targets.current(StoragePurpose.TIMESHIFT).fallback)
        val w3 = StorageWorld()
        val id3 = w3.addSmb()
        ready(w3)
        w3.targets.onCreateFailed(id3, StorageFailure.UNREACHABLE)
        assertTrue(w3.keeper.snapshot.failureSeen)
        assertEquals("READY and fresh: the next zap still tries the folder", StorageKind.SMB, w3.targets.current(StoragePurpose.TIMESHIFT).target.kind)
        eventually("the link was checked now") { w3.server.log.count { it == "freeBytes" } >= 2 }
    }
}
