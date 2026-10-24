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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
        // (Lead, fix pass) READY again after AUTH_FAILED: the sign-in toast's counter is reset, once.
        eventually("signed in") { w.signedIn.toList() == listOf(id) }
        w.keeper.onNetwork(true); Thread.sleep(150)
        assertEquals(listOf(id), w.signedIn.toList())
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

    /** C-S1: a rename (or a Test result) keeps the link: the ring's connection is never cut mid-cycle. */
    @Test fun aRenameDoesNotRelink() {
        val w = StorageWorld()
        val id = w.addSmb()
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        val link = w.keeper.linkIfCurrent(id)!!
        val opens = w.opener.opens.get()
        runBlocking { w.locations.rename(id, "Office NAS") }
        eventually("renamed") { w.locations.status.value.single().location.name == "Office NAS" }
        Thread.sleep(300)
        assertSame("same link object", link, w.keeper.linkIfCurrent(id))
        assertTrue(link.isReady)
        assertEquals("no extra connect", opens, w.opener.opens.get())
        assertEquals(1, link.connects.get())
    }

    /**
     * C-S1 (the review probe): a sign-in change relinks; the old link is retired. A ring still holding it cannot
     * reconnect it (it fails over, UNREACHABLE), and nothing keeps its connection once the location goes.
     */
    @Test fun aRetiredLinkNeverKeepsAConnection() {
        val w = StorageWorld()
        val id = w.addSmb()
        w.keeper.startNow()
        eventually("READY") { w.keeper.snapshot.state == KeeperState.READY }
        val old = w.keeper.linkIfCurrent(id)!!
        old.retain() // a ring file is open on it
        runBlocking { w.locations.updateSignIn(id, "shawn", null, "n3w!".toCharArray(), guest = false) }
        eventually("relinked") { w.keeper.linkIfCurrent(id) !== old }
        assertTrue(old.isRetired)
        eventually("old link dropped") { !old.isReady }
        // The ring's SmbRingStorage saw its WRITE fail and asks its own link to reconnect: refused.
        assertThrows(java.io.IOException::class.java) { old.reconnect(old.epoch, w.clock.nowMs() + 2_000) }
        assertThrows(java.io.IOException::class.java) { old.share(w.clock.nowMs() + 100) }
        assertFalse(old.isReady)
        old.release() // the ring closes; still nothing connects
        Thread.sleep(200)
        assertFalse("the retired link holds no connection", old.isReady)
        eventually("new link READY") { w.keeper.linkIfCurrent(id)?.isReady == true }
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, StorageWorld.INTERNAL) }
        eventually("IDLE") { w.keeper.snapshot.state == KeeperState.IDLE && w.keeper.snapshot.locationId == null }
        assertFalse(old.isReady)
    }
}
