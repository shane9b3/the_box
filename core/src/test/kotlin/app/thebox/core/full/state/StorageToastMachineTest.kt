package app.thebox.core.full.state

import app.thebox.core.full.PLAYER_TOAST_ARG_SEP
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.input.BoxAction.PLAY_PAUSE
import app.thebox.core.input.BoxAction.TIME_SHIFT
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The machine's storage-toast rows (docs/design/m6-storage-locations.md section 5.1, 10, 16.2): SAVE_LOCATION_FALLBACK when a
 * cycle's fallback first appears (once per cycle and reason; the host decides whether it is due), and the arg of the
 * storage-stop toasts. The once-per-process and three-processes rules are `:app`'s `FallbackToastPolicy` (its own test).
 */
class StorageToastMachineTest {
    private val sep = PLAYER_TOAST_ARG_SEP
    private fun PlayerWorld.toasts(mark: Int) = callsSince(mark).filter { it.name == "toast" }.map { it.args[0] as PlayerToast to it.args.getOrNull(1) as String? }

    private fun PlayerWorld.nasFallback(reason: LocationFallback, cycle: Long = 1) =
        buffer(cycleId = cycle, fallback = reason, storageName = "NAS", locationId = "smb-1")

    @Test fun theToastComesWhenAFallbackFirstAppearsAndCarriesKindReasonAndName() {
        val w = liveWithoutArchive()
        val mark = w.fx.calls.size
        w.nasFallback(LocationFallback.UNREACHABLE)
        assertEquals(listOf(PlayerToast.SAVE_LOCATION_FALLBACK to "SMB${sep}UNREACHABLE${sep}NAS${sep}"), w.toasts(mark))
        assertEquals(listOf("smb-1" to LocationFallback.UNREACHABLE), w.fallbackAsked)
    }

    @Test fun laterSnapshotsOfTheSameCycleAndReasonDoNotAskAgain() {
        val w = liveWithoutArchive()
        w.nasFallback(LocationFallback.UNREACHABLE)
        val mark = w.fx.calls.size
        repeat(3) { w.nasFallback(LocationFallback.UNREACHABLE) }
        assertEquals(emptyList(), w.toasts(mark))
        assertEquals(1, w.fallbackAsked.size)
    }

    @Test fun aNewCycleOrANewReasonAsksAgain() {
        val w = liveWithoutArchive()
        w.nasFallback(LocationFallback.UNREACHABLE, cycle = 1)
        w.nasFallback(LocationFallback.UNREACHABLE, cycle = 2)
        w.nasFallback(LocationFallback.SIGN_IN_NEEDED, cycle = 2)
        assertEquals(
            listOf(LocationFallback.UNREACHABLE, LocationFallback.UNREACHABLE, LocationFallback.SIGN_IN_NEEDED),
            w.fallbackAsked.map { it.second },
        )
    }

    @Test fun aToastTheHostSaysIsNotDueIsNotShownButIsNotAskedAgain() {
        val w = liveWithoutArchive()
        w.fallbackDue = { _, _ -> false }
        val mark = w.fx.calls.size
        w.nasFallback(LocationFallback.UNSTABLE)
        w.nasFallback(LocationFallback.UNSTABLE)
        assertEquals(emptyList(), w.toasts(mark))
        assertEquals(1, w.fallbackAsked.size)
    }

    @Test fun noFallbackNeverAsks() {
        val w = liveWithoutArchive()
        w.buffer(cycleId = 1)
        w.buffer(cycleId = 2, storage = StorageKind.SMB, storageName = "NAS", locationId = "smb-1")
        assertTrue(w.fallbackAsked.isEmpty())
    }

    @Test fun aUsbDriveIsNamedByItsKind() {
        val w = liveWithoutArchive()
        val mark = w.fx.calls.size
        w.buffer(cycleId = 1, fallback = LocationFallback.NOT_CONNECTED, storageName = "SanDisk", locationId = "vol-1234")
        assertEquals(listOf(PlayerToast.SAVE_LOCATION_FALLBACK to "VOLUME${sep}NOT_CONNECTED${sep}SanDisk${sep}"), w.toasts(mark))
    }

    @Test fun aStorageStopOnANetworkFolderCarriesKindFailureNameAndNoJump() {
        val w = liveWithoutArchive()
        w.buffer(available = false, off = BufferOff.STORAGE_ERROR, storage = StorageKind.SMB, storageFailure = StorageFailure.FULL, storageName = "NAS", locationId = "smb-1")
        var mark = w.fx.calls.size
        w.press(PLAY_PAUSE)
        assertEquals(listOf(PlayerToast.PAUSE_NO_REWIND_STORAGE to "SMB${sep}FULL${sep}NAS${sep}"), w.toasts(mark))
        mark = w.fx.calls.size
        w.press(TIME_SHIFT)
        assertEquals(listOf(PlayerToast.REWIND_STOPPED_STORAGE to "SMB${sep}FULL${sep}NAS${sep}"), w.toasts(mark))
    }

    @Test fun aStorageStopOnThisDeviceKeepsTodaysTextAndNoArg() {
        val w = liveWithoutArchive()
        w.buffer(available = false, off = BufferOff.STORAGE_ERROR)
        val mark = w.fx.calls.size
        w.press(TIME_SHIFT)
        val t = w.toasts(mark).single()
        assertEquals(PlayerToast.REWIND_STOPPED_STORAGE, t.first)
        assertNull(t.second)
    }

    @Test fun afterTheEarlySwitchTheCycleIsOnThisDeviceAndItsStopsAreTodays() {
        val w = liveWithoutArchive()
        w.buffer(available = false, off = BufferOff.STORAGE_ERROR, storage = StorageKind.INTERNAL, fallback = LocationFallback.UNREACHABLE, storageName = "NAS", locationId = "smb-1")
        val mark = w.fx.calls.size
        w.press(TIME_SHIFT)
        assertNull(w.toasts(mark).single().second)
    }
}
