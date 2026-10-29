package app.thebox.data.storage

import app.thebox.core.settings.state.StorageDomain
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.VolumeCatalog
import app.thebox.data.storage.smb.PasswordRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** §16.2 `StorageLocationsImplTest` (WP-S1, §8, A-S11, A-N7). */
class StorageLocationsImplTest {
    private val address = SmbAddress("nas", share = "TV", path = "Rewind", user = "shawn", domain = "HOME")

    @Test fun thePasswordLivesOnlyInTheBlobStoreAndEveryArrayIsZeroed() = runBlocking {
        val w = StorageWorld()
        val pw = "s3cret!".toCharArray()
        val loc = w.locations.saveSmb(address, "NAS", pw, null)
        assertTrue("the caller's array is zeroed", pw.all { it == '\u0000' })
        assertTrue("the UTF-8 copy handed to the store is zeroed", w.blobs.given.single().all { it == 0.toByte() })
        assertArrayEquals("s3cret!".toByteArray(), w.blobs.map[StorageLocationsImpl.ref(loc.id)])
        assertEquals("smb-" + loc.id, StorageLocationsImpl.ref(loc.id))
        // Never in the backed-up domain, nor in any toString.
        val export = w.store.export().toString()
        assertFalse(export, export.contains("s3cret"))
        assertTrue(export.contains("shawn")) // the user name is backed up (§8)
        assertFalse(loc.toString().contains("shawn"))
        // Reading it back: a CharArray; the UTF-8 copy is zeroed.
        val r = w.locations.readPassword(loc.id) as PasswordRead.Ok
        assertEquals("s3cret!", r.chars!!.concatToString())
        assertTrue(w.blobs.handedOut.last().all { it == 0.toByte() })
        assertFalse(r.toString().contains("s3cret"))
    }

    @Test fun theAuditGetsUserNameAndPassword() = runBlocking {
        val w = StorageWorld()
        w.locations.saveSmb(address, "NAS", "s3cret!".toCharArray(), null)
        val secrets = w.locations.auditSecrets()
        assertTrue(secrets.containsAll(listOf("shawn", "s3cret!", "HOME")))
    }

    @Test fun updateRenameRemove() = runBlocking {
        val w = StorageWorld()
        val loc = w.locations.saveSmb(address, "NAS", "a".toCharArray(), null)
        w.locations.choose(StoragePurpose.TIMESHIFT, loc.id)
        assertEquals(loc.id, w.locations.chosen(StoragePurpose.TIMESHIFT))
        val v = w.locations.signInVersion.value
        val pw = "b".toCharArray()
        w.locations.updateSignIn(loc.id, "other", null, pw, guest = false)
        assertTrue(pw.all { it == '\u0000' })
        assertTrue(w.locations.signInVersion.value > v)
        assertEquals("other", w.locations.location(loc.id)!!.smb!!.user)
        assertEquals("b", (w.locations.readPassword(loc.id) as PasswordRead.Ok).chars!!.concatToString())
        w.locations.updateSignIn(loc.id, null, null, null, guest = true)
        assertFalse(w.blobs.map.containsKey(StorageLocationsImpl.ref(loc.id)))
        assertEquals(null, (w.locations.readPassword(loc.id) as PasswordRead.Ok).chars)
        w.locations.rename(loc.id, "  A very long name for the network folder  ")
        assertEquals(StorageConstants.LABEL_MAX_CHARS, w.locations.location(loc.id)!!.name.length)
        w.blobs.map[StorageLocationsImpl.ref(loc.id)] = byteArrayOf(1)
        w.locations.remove(loc.id)
        assertFalse(w.blobs.map.containsKey(StorageLocationsImpl.ref(loc.id)))
        assertEquals(StorageConstants.INTERNAL_ID, w.locations.chosen(StoragePurpose.TIMESHIFT))
        assertTrue(w.store.flow(StorageDomain).value.locations.isEmpty())
    }

    /** Q-E2 (closure fix pass 2026-10-28): a `storage` settings write decrypts no stored password. */
    @Test fun aSettingsWriteDecryptsNoPassword() = runBlocking {
        val w = StorageWorld()
        val loc = w.locations.saveSmb(address, "NAS", "s3cret!".toCharArray(), null)
        eventually("listed") { w.locations.status.value.any { it.location.id == loc.id } }
        val gets = w.blobs.gets.get()
        for (n in 1..3) w.locations.rename(loc.id, "NAS $n")
        w.locations.choose(StoragePurpose.TIMESHIFT, loc.id)
        eventually("renamed") { w.locations.status.value.single().location.name == "NAS 3" }
        assertEquals("no decrypt per write", gets, w.blobs.gets.get())
        assertFalse(w.locations.signInNeeded(loc.id))
        // A missing password (a restore) still reads as a sign-in needed.
        w.blobs.map.clear()
        w.locations.rename(loc.id, "NAS 4")
        eventually("sign-in needed") { w.locations.status.value.single().state == LocationState.SIGN_IN_NEEDED }
    }

    /** Q-E2: a password that does not decrypt stays SIGN_IN_NEEDED across settings writes until the sign-in changes. */
    @Test fun anUndecryptablePasswordStaysSignInNeededUntilANewSignIn() = runBlocking {
        val w = StorageWorld()
        val loc = w.locations.saveSmb(address, "NAS", "s3cret!".toCharArray(), null)
        w.blobs.undecryptable = true
        assertEquals(PasswordRead.NeedsSignIn, w.locations.readPassword(loc.id))
        w.locations.rename(loc.id, "Office NAS")
        eventually("renamed") { w.locations.status.value.single().location.name == "Office NAS" }
        assertEquals(LocationState.SIGN_IN_NEEDED, w.locations.status.value.single().state)
        w.blobs.undecryptable = false
        w.locations.updateSignIn(loc.id, "shawn", "HOME", "n3w!".toCharArray(), guest = false)
        w.locations.rename(loc.id, "NAS")
        eventually("signed in") { w.locations.status.value.single().let { it.location.name == "NAS" && it.state != LocationState.SIGN_IN_NEEDED } }
    }

    /** SEC-N1 (closure fix pass 2026-10-28): an SMB user name and domain are masked in every log line (smbj's auth message). */
    @Test fun theSmbUserAndDomainAreRedacted() = runBlocking {
        val w = StorageWorld()
        val loc = w.locations.saveSmb(SmbAddress("nas", share = "TV", user = "kitchen-tv", domain = "WORKGRP"), "NAS", "pw!".toCharArray(), null)
        eventually("listed") { w.locations.status.value.any { it.location.id == loc.id } }
        val line = app.thebox.core.common.Redact.text("Authentication failed for 'kitchen-tv' using WORKGRP\\kitchen-tv")
        assertFalse(line, line.contains("kitchen-tv"))
        assertFalse(line, line.contains("WORKGRP"))
    }

    /** U-S5 (save-location review 2026-10-24): a stored folder's Test is written as its lastTest. */
    @Test fun recordTestStoresTheLastTest() = runBlocking {
        val w = StorageWorld()
        val loc = w.locations.saveSmb(address, "NAS", null, null)
        val t = app.thebox.core.storage.LocationTestResult(app.thebox.core.storage.TestVerdict.TOO_SLOW, 900_000, 900_000, 35, 0, 1L shl 40, true, 5_000L)
        w.locations.recordTest(loc.id, t)
        assertEquals(t, w.locations.location(loc.id)!!.lastTest)
        assertEquals(t, w.store.flow(StorageDomain).value.locations.single().lastTest)
    }

    @Test fun aRestoreReadsAsSignInNeeded() {
        val w = StorageWorld()
        val loc = StorageLocation("smb-r", StorageKind.SMB, "NAS", smb = address)
        val guest = StorageLocation("smb-g", StorageKind.SMB, "Guest NAS", smb = address.copy(user = null, guest = true))
        w.store.update(StorageDomain) { it.copy(locations = listOf(loc, guest), timeshiftLocationId = loc.id) }
        eventually("statuses") { w.locations.status.value.size == 2 && w.locations.status.value[0].state == LocationState.SIGN_IN_NEEDED }
        assertEquals(LocationState.UNTESTED, w.locations.status.value[1].state)
        assertEquals(PasswordRead.NeedsSignIn, runBlocking { w.locations.readPassword("smb-r") })
    }

    @Test fun aChosenUsbDriveThisBoxNeverSawResetsToThisDevice() {
        val w = StorageWorld()
        val vol = StorageLocation("vol-AAAA-1111", StorageKind.VOLUME, "Stick", volumeUuid = "AAAA-1111")
        w.store.update(StorageDomain) { it.copy(locations = listOf(vol), timeshiftLocationId = vol.id) }
        eventually("reset") { w.chosen() == StorageConstants.INTERNAL_ID }
        assertEquals(1, w.store.flow(StorageDomain).value.locations.size) // the location stays in the list
        eventually("status") { w.locations.status.value.singleOrNull()?.state == LocationState.NOT_CONNECTED }
    }

    @Test fun choosingAMountedDriveRemembersItAndSurvivesTheCheck() {
        val w = StorageWorld()
        w.volumes.volumes.value = listOf(VolumeCatalog.Volume("BBBB-2222", "SanDisk", File(w.dir, "usb"), 64L shl 30, 40L shl 30))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "vol-BBBB-2222") }
        Thread.sleep(150)
        assertEquals("vol-BBBB-2222", w.chosen())
        assertEquals("SanDisk", w.locations.location("vol-BBBB-2222")!!.name)
        eventually("READY") { w.locations.status.value.single().state == LocationState.READY }
        assertTrue(File(w.dir, StorageLocationsImpl.SEEN_VOLUMES_FILE).readText().contains("BBBB-2222"))
    }
}
