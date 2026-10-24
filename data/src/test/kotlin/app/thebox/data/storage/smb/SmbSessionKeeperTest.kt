package app.thebox.data.storage.smb

import app.thebox.core.storage.LocationState
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.smb.FakeStatus
import app.thebox.core.storage.smb.FakeStatusException
import app.thebox.core.storage.smb.FakeTransportException
import app.thebox.data.storage.StorageWorld
import app.thebox.data.storage.eventually
import app.thebox.data.storage.mkdirsDirect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.3 (WP-S1): connect, the wake write, last-known-good, the first-READY sweep, AUTH_FAILED, BACKOFF, background. */
class SmbSessionKeeperTest {
    @Test fun firstReadyWritesTheWakeFileThenSweepsAndRecordsLastGood() {
        val w = StorageWorld()
        val id = w.addSmb()
        // A stale file of this device from an earlier process, and a foreign live one.
        val dir = "Rewind/TheBox-rewind"
        w.server.mkdirsDirect(dir)
        w.server.putFile("$dir/ring-${w.deviceId.id}-1-1.bin", ByteArray(10))
        w.server.putFile("$dir/ring-0123456789ab-9-1.bin", ByteArray(10))
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        assertTrue(w.server.exists("$dir/.wake-${w.deviceId.id}"))
        assertEquals(4_096, w.server.contents("$dir/.wake-${w.deviceId.id}")!!.size)
        eventually("swept") { !w.server.exists("$dir/ring-${w.deviceId.id}-1-1.bin") }
        assertTrue("another box's live file is kept", w.server.exists("$dir/ring-0123456789ab-9-1.bin"))
        assertEquals("s3cret!", w.opener.lastPassword)
        assertNotNull(w.lastGood.get(id))
        eventually("status READY") { w.locations.status.value.single().state == LocationState.READY }
        // The wake write precedes the sweep's list (§3.5: the sweep's server clock is our .wake).
        val log = w.server.log
        assertTrue(log.indexOfFirst { it.startsWith("open:$dir/.wake-") } < log.indexOfFirst { it == "list:$dir" })
    }

    @Test fun aLogonRefusalIsAuthFailedUntilTheSignInChanges() {
        val w = StorageWorld()
        val id = w.addSmb()
        w.opener.fail = { FakeStatusException(FakeStatus.LOGON_FAILURE) }
        w.keeper.startNow()
        eventually("AUTH_FAILED") { w.keeper.snapshot.state == KeeperState.AUTH_FAILED }
        eventually("status") { w.locations.status.value.single().state == LocationState.SIGN_IN_NEEDED }
        val opens = w.opener.opens.get()
        w.keeper.warm(); Thread.sleep(150)
        assertEquals("no retry while the sign-in is wrong", opens, w.opener.opens.get())
        w.opener.fail = null
        runBlocking { w.locations.updateSignIn(id, "shawn", null, "right".toCharArray(), guest = false) }
        eventually("READY after the new sign-in") { w.keeper.snapshot.state == KeeperState.READY }
        assertEquals("right", w.opener.lastPassword)
    }

    @Test fun aMissingPasswordIsAuthFailed() {
        val w = StorageWorld()
        w.addSmb()
        w.blobs.map.clear()
        w.keeper.startNow()
        eventually("AUTH_FAILED") { w.keeper.snapshot.state == KeeperState.AUTH_FAILED }
        assertEquals(0, w.opener.opens.get())
    }

    @Test fun aTransportFailureBacksOffAndAnyTriggerRetries() {
        val w = StorageWorld()
        w.addSmb()
        w.opener.fail = { FakeTransportException("refused") }
        w.keeper.startNow()
        eventually("BACKOFF") { w.keeper.snapshot.state == KeeperState.BACKOFF }
        assertTrue(w.keeper.snapshot.failureSeen)
        eventually("status") { w.locations.status.value.single().state == LocationState.UNREACHABLE }
        w.opener.fail = null
        w.keeper.onNetwork(true) // a trigger resets the backoff
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
    }

    @Test fun choosingThisDeviceDisconnects() {
        val w = StorageWorld()
        w.addSmb()
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, StorageWorld.INTERNAL) }
        eventually("IDLE") { w.keeper.snapshot.state == KeeperState.IDLE && w.keeper.snapshot.locationId == null }
        eventually("closed") { w.opener.closes.get() >= 1 }
    }

    @Test fun theStoragesReconnectGoesThroughTheKeepersLink() {
        val w = StorageWorld()
        val id = w.addSmb()
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        val link = w.keeper.linkIfCurrent(id)!!
        val e = link.epoch
        w.server.dropTransport()
        val s = link.reconnect(e, w.clock.nowMs() + 5_000)
        assertTrue(s.epoch > e)
        assertEquals(2, link.connects.get())
        eventually("READY again") { w.keeper.snapshot.state == KeeperState.READY }
        // The reconnect wrote the wake file again (on every (re)connect, A-B4).
        eventually("second wake") { w.server.log.count { it.startsWith("open:Rewind/TheBox-rewind/.wake-") } >= 2 }
    }
}
