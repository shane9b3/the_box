package app.thebox.core.storage

import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.state.SettingsMachineImpl
import app.thebox.core.settings.state.StorageDomain
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.RemoteFault
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Commit-0 contract checks (docs/design/m6-storage-locations.md §8, §9.2, §12, Appendix A). */
class StorageContractsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** §8 / §12.4: a store without the `storage` document (every install before this feature) reads today's behaviour. */
    @Test
    fun storageDomainDefaultsKeepOldDocumentsValid() {
        assertEquals("storage", StorageDomain.name)
        assertTrue(StorageDomain.backedUp)
        val d = StorageDomain.default
        assertEquals(StoragePrefs(), d)
        assertEquals(StorageConstants.INTERNAL_ID, d.timeshiftLocationId)
        assertTrue(d.locations.isEmpty())
        assertNull(d.recordingLocationId)
        assertEquals(d, json.decodeFromString(StoragePrefs.serializer(), "{}"))
        // A document from a later version with unknown keys still reads.
        assertEquals(d, json.decodeFromString(StoragePrefs.serializer(), """{"future":1}"""))
    }

    @Test
    fun aStoredLocationRoundTripsWithoutAPassword() {
        val loc = StorageLocation(
            id = "smb-1", kind = StorageKind.SMB, name = "NAS",
            smb = SmbAddress(host = "nas", share = "TV", path = "Rewind", user = "shawn", domain = "WORKGROUP"),
            lastTest = LocationTestResult(TestVerdict.GOOD_HD, 4_100_000, 4_200_000, 6, 0, 1L shl 40, wifi = true, atWallMs = 1L),
        )
        val prefs = StoragePrefs(locations = listOf(loc), timeshiftLocationId = loc.id)
        val text = json.encodeToString(StoragePrefs.serializer(), prefs)
        assertEquals(prefs, json.decodeFromString(StoragePrefs.serializer(), text))
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("\"port\""), "the default port is not stored")
    }

    @Test
    fun credentialsNeverPrint() {
        val a = SmbAddress(host = "nas", share = "TV", user = "shawn")
        assertFalse(a.toString().contains("shawn"))
        assertTrue(a.toString().contains("user=<set>"))
        val s = PhoneSubmission.NetworkFolder("nas", "TV", "Rewind", "shawn", "WG", "hunter2".toCharArray(), guest = false)
        assertFalse(s.toString().contains("shawn"))
        assertFalse(s.toString().contains("hunter2"))
        assertTrue(s.toString().contains("password=****"))
        assertFalse(BrowseHandle(1, a, SignInMode.USER).toString().contains("shawn"))
    }

    @Test
    fun remoteFaultFailures() {
        assertNull(RemoteFault(FaultClass.CANCEL).failure, "a cancel is never a storage failure (A-B3)")
        assertEquals(StorageFailure.UNREACHABLE, RemoteFault(FaultClass.LINK_LOST).failure)
        assertEquals(StorageFailure.SLOW, RemoteFault(FaultClass.SLOW).failure)
        assertEquals(StorageFailure.FULL, RemoteFault(FaultClass.FULL).failure)
        assertEquals(StorageFailure.ACCESS, RemoteFault(FaultClass.ACCESS).failure)
        assertEquals(StorageFailure.REMOVED, RemoteFault(FaultClass.REMOVED).failure)
        assertEquals(listOf("CANCEL", "LINK_LOST", "SLOW", "FULL", "ACCESS", "REMOVED"), FaultClass.entries.map { it.name }, "§4.3 order")
    }

    /** A-B2: storages are non-blocking unless they say so; the remote stubs say so. */
    @Test
    fun blockingDefaultsToFalse() {
        val plain = object : RingStorage {
            override val capacityBytes = 0L
            override fun allocate(upToBytes: Long) = 0L
            override fun pwrite(src: ByteBuffer, filePosition: Long) = 0
            override fun pread(dst: ByteBuffer, filePosition: Long) = 0
            override fun isUnlinked() = false
            override fun availableBytes() = 0L
            override fun writeHeader(generation: Long, capacity: Long) = Unit
            override fun delete() = Unit
            override fun close() = Unit
        }
        assertFalse(plain.blocking)
    }

    @Test
    fun appendixAConstants() {
        assertEquals(160, TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL)
        assertEquals(96, TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL_SMALL)
        assertEquals(192, TimeshiftConstants.REMOTE_POOL_MEMORY_CLASS_MB)
        assertEquals(4_294_954_752L, StorageConstants.VOLUME_MAX_RING_BYTES)
        assertEquals(48L * 3_600_000L, StorageConstants.FOREIGN_STALE_MS)
        assertEquals(7L * 86_400_000L, StorageConstants.SMB_LAST_GOOD_MS)
        assertEquals(30L * 86_400_000L, StorageConstants.TEST_FRESH_MS)
        assertEquals(TimeshiftConstants.ASSUMED_BYTES_PER_SEC, StorageConstants.HD_BPS)
        assertTrue(StorageConstants.SMB_SO_TIMEOUT_MS > StorageConstants.SMB_KEEPALIVE_MS)
        assertTrue(StorageConstants.SMB_RECONNECT_BUDGET_MS < StorageConstants.SMB_IO_TIMEOUT_MS)
        assertEquals(listOf(5_000L, 15_000L, 60_000L, 300_000L), StorageConstants.SMB_BACKOFF_MS)
    }

    /** §9.2: every new route is section PLAYBACK; BACK from a deep open walks back to Save to / Pause and rewind. */
    @Test
    fun newRoutesBelongToPlayback() {
        val routes = listOf(
            SettingsRoute.PauseRewind, SettingsRoute.SaveLocation(), SettingsRoute.LocationDetail("smb-1"), SettingsRoute.FindComputers,
            SettingsRoute.ComputerShares(1), SettingsRoute.ComputerSignIn(1), SettingsRoute.LocationSignIn("smb-1"),
            SettingsRoute.NetworkFolder(1, "TV"), SettingsRoute.LocationTest(browse = 1), SettingsRoute.LocationTest(locationId = "smb-1"),
            SettingsRoute.PhoneSetup(purpose = PhonePurpose.NETWORK_FOLDER, browse = 1),
        )
        for (r in routes) assertEquals(SectionId.PLAYBACK, SettingsTree.sectionOf(r), "$r")
        assertEquals(SectionId.PLAYLISTS, SettingsTree.sectionOf(SettingsRoute.PhoneSetup()))
        assertEquals(SettingsRoute.Section(SectionId.PLAYBACK), SettingsMachineImpl.parentOf(SettingsRoute.PauseRewind))
        assertEquals(SettingsRoute.PauseRewind, SettingsMachineImpl.parentOf(SettingsRoute.SaveLocation()))
        assertEquals(SettingsRoute.SaveLocation(), SettingsMachineImpl.parentOf(SettingsRoute.FindComputers))
        assertEquals(SettingsRoute.LocationDetail("smb-1"), SettingsMachineImpl.parentOf(SettingsRoute.LocationTest(locationId = "smb-1")))
        assertEquals(SettingsRoute.FindComputers, SettingsMachineImpl.parentOf(SettingsRoute.PhoneSetup(purpose = PhonePurpose.NETWORK_FOLDER)))
    }
}
