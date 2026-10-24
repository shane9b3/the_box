package app.thebox.ui.full

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PlayerToast
import app.thebox.core.settings.state.StorageMarks
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageKind
import app.thebox.ui.settings.SettingsUi
import app.thebox.ui.settings.rowHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The viewer strings of the save location (docs/design/m6-storage-locations.md section 10), verbatim, and the settings
 * kit's helpers for it (section 9.4): reason, then next step; "rewind", never "buffer"; no path to a row the simple view hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageTextTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val sep = PlayerText.ARG_SEP
    private fun arg(kind: StorageKind, second: String, name: String, jumped: Boolean = false) = listOf(kind.name, second, name, if (jumped) "1" else "").joinToString(sep.toString())
    private val where = "Settings → Playback → Pause and rewind → Save to"

    @Test fun theFallbackToastsReadReasonThenNextStep() {
        fun t(reason: LocationFallback, kind: StorageKind = StorageKind.SMB, name: String = "NAS") =
            PlayerText.toast(res, PlayerToast.SAVE_LOCATION_FALLBACK, arg(kind, reason.name, name))
        assertEquals("Rewind is saving on this device: NAS isn't answering. Check it's on, or change $where.", t(LocationFallback.UNREACHABLE))
        assertEquals("Rewind is saving on this device: NAS needs you to sign in again. Sign in at $where → NAS.", t(LocationFallback.SIGN_IN_NEEDED))
        assertEquals("Rewind is saving on this device for now: NAS kept dropping out. The Box tries NAS again in a few minutes.", t(LocationFallback.UNSTABLE))
        assertEquals("Rewind is saving on this device: the USB drive isn't connected. Connect it, or change $where.", t(LocationFallback.NOT_CONNECTED, StorageKind.VOLUME, "SanDisk"))
        assertEquals("Rewind is saving on this device: NAS is almost full. Free up space there, or change $where.", t(LocationFallback.NO_SPACE_THERE))
        assertEquals("Rewind is saving on this device: the USB drive is full. Free up space there, or change $where.", t(LocationFallback.NO_SPACE_THERE, StorageKind.VOLUME, "SanDisk"))
    }

    @Test fun theStopToastsNameTheFolderAndWhatHappensNext() {
        fun stopped(failure: String, kind: StorageKind = StorageKind.SMB, jumped: Boolean = false) =
            PlayerText.toast(res, PlayerToast.REWIND_STOPPED_STORAGE, arg(kind, failure, "NAS", jumped))
        assertEquals("Rewind stopped: NAS stopped answering. Live TV continues; from your next channel change, rewind saves on this device until NAS is back.", stopped("UNREACHABLE"))
        assertEquals("NAS stopped answering, so you're back at live. Live TV continues; rewind saves on this device from your next channel change.", stopped("UNREACHABLE", jumped = true))
        assertEquals("Rewind stopped: NAS is full. Live TV continues. Free up space there, or change $where.", stopped("FULL"))
        assertEquals(
            "Rewind stopped: NAS no longer lets The Box save there. Live TV continues. Check the folder's permissions, then choose Test this folder in $where → NAS.",
            stopped("ACCESS"),
        )
        assertEquals("Rewind stopped: the saved file on NAS was deleted. Live TV continues; change channel and back to start again.", stopped("REMOVED"))
        assertEquals("Rewind stopped: the USB drive was removed. Live TV continues; rewind saves on this device from your next channel change.", stopped("REMOVED", StorageKind.VOLUME))
        assertEquals(
            "Rewind stopped: NAS couldn't keep up over your network. Live TV continues; from your next channel change, rewind saves on this device for a while.",
            PlayerText.toast(res, PlayerToast.REWIND_STOPPED_SLOW, arg(StorageKind.SMB, "SLOW", "NAS")),
        )
        assertEquals(
            "Rewind stopped: the USB drive can't keep up with this channel. Live TV continues. A faster drive helps, or choose This device in $where.",
            PlayerText.toast(res, PlayerToast.REWIND_STOPPED_SLOW, arg(StorageKind.VOLUME, "SLOW", "SanDisk")),
        )
    }

    @Test fun pauseAndResumeToasts() {
        assertEquals(
            "Saving for rewind failed on NAS, so Play returns to live. Change channel and back to try again.",
            PlayerText.toast(res, PlayerToast.PAUSE_NO_REWIND_STORAGE, arg(StorageKind.SMB, "UNREACHABLE", "NAS")),
        )
        assertEquals(
            "Saving for rewind failed on the USB drive, so Play returns to live. Change channel and back to try again.",
            PlayerText.toast(res, PlayerToast.PAUSE_NO_REWIND_STORAGE, arg(StorageKind.VOLUME, "REMOVED", "SanDisk")),
        )
        assertEquals(
            "NAS stopped answering while you were paused, so you're back at live. Rewind saves on this device from your next channel change.",
            PlayerText.toast(res, PlayerToast.PAUSE_RESUME_LOST_STORAGE, arg(StorageKind.SMB, "", "NAS")),
        )
    }

    @Test fun aNullArgIsTodaysTextForThisDevice() {
        assertEquals(
            "Rewind stopped: saving to storage failed. Live TV continues; change channel and back to try again.",
            PlayerText.toast(res, PlayerToast.REWIND_STOPPED_STORAGE, null),
        )
        assertNull(PlayerText.storageArg(arg(StorageKind.INTERNAL, "", "")))
        assertEquals("Saving for rewind failed on this device's storage, so Play returns to live. Change channel and back to try again.", PlayerText.toast(res, PlayerToast.PAUSE_NO_REWIND_STORAGE, null))
    }

    @Test fun theInternalTextsNameTheSubPageAndTheViewerNeverReadsBuffer() {
        assertTrue(PlayerText.toast(res, PlayerToast.REWIND_STOPPED_SLOW, null).endsWith("Settings → Playback → Pause and rewind → Rewind length."))
        assertTrue(PlayerText.toast(res, PlayerToast.REWIND_LIMITED_TODAY, null).endsWith("Playback → Pause and rewind → Protect built-in storage."))
        val all = listOf(
            LocationFallback.entries.map { PlayerText.toast(res, PlayerToast.SAVE_LOCATION_FALLBACK, arg(StorageKind.SMB, it.name, "NAS")) },
            listOf("UNREACHABLE", "FULL", "ACCESS", "REMOVED", "SLOW").map { PlayerText.toast(res, PlayerToast.REWIND_STOPPED_STORAGE, arg(StorageKind.SMB, it, "NAS")) },
        ).flatten()
        for (s in all) {
            assertFalse(s, s.contains("buffer", ignoreCase = true) || s.contains("ring ", ignoreCase = true) || s.contains("segment", ignoreCase = true))
        }
    }

    @Test fun aLongNameIsEllipsisedInTheMiddle() {
        val s = PlayerText.toast(res, PlayerToast.SAVE_LOCATION_FALLBACK, arg(StorageKind.SMB, "UNREACHABLE", "A very long network folder name indeed"))
        assertTrue(s, s.contains("…"))
        assertEquals(24, PlayerText.ellipsize("A very long network folder name indeed", 24).length)
    }

    @Test fun theStatsLineNamesThePlaceAndTheFallback() {
        assertNull(PlayerText.rewindPlaceStat(res, StorageKind.INTERNAL, null, null, null))
        assertEquals("NAS 3.4 MB/s", PlayerText.rewindPlaceStat(res, StorageKind.SMB, "NAS", 3_400_000, null))
        assertEquals("NAS", PlayerText.rewindPlaceStat(res, StorageKind.SMB, "NAS", null, null))
        assertEquals("this device (NAS offline)", PlayerText.rewindPlaceStat(res, StorageKind.INTERNAL, "NAS", null, LocationFallback.UNREACHABLE))
        assertEquals("this device (NAS connecting)", PlayerText.rewindPlaceStat(res, StorageKind.INTERNAL, "NAS", null, LocationFallback.NOT_READY_YET))
    }

    /** Every key of the page texts has its string, and each string formats with positional arguments (no bad `%d`). */
    @Test fun everyStorageTextHasAStringThatFormats() {
        for (k in app.thebox.core.settings.state.StorageText.entries) {
            val id = app.thebox.ui.R.string::class.java.getField("storage_" + k.name.lowercase()).getInt(null)
            val text = res.getString(id, 1, 2, 3)
            assertFalse("${k.name}: $text", text.contains("%"))
            assertFalse("${k.name}: no \"buffer\" in viewer text", text.contains("buffer", ignoreCase = true))
        }
    }

    /** The long next steps are written as lines (the storage Info rows draw one per line); none is longer than a row. */
    @Test fun longNextStepsAreWrittenAsShortLines() {
        for (name in listOf("storage_find_none_step", "storage_find_vpn_step", "storage_sign_in_info")) {
            val id = app.thebox.ui.R.string::class.java.getField(name).getInt(null)
            val lines = res.getString(id).split('\n')
            assertTrue("$name has several lines", lines.size >= 2)
            for (l in lines) assertTrue("$name: a line of ${l.length} characters", l.length <= 80) // U-S6
        }
    }

    /**
     * U-S6 (save-location review 2026-10-24): every line a storage page draws (split on line breaks) is at most 80
     * characters, and every Info row label at most 60, so a host name or a 1.3x translation still fits one line.
     * The phone's results (sent to the phone) and the notices (the notice line) are not row lines.
     */
    @Test fun uS6_everyStorageLineFitsARow() {
        val labels = setOf(
            "FIND_NONE_TITLE", "FIND_VPN_TITLE", "SIGN_IN_INFO_TITLE", "SIGN_IN_NO_KEYBOARD", "TEST_WAKE_NOTE", "TEST_SMALL_POOL_NOTE",
            "FOLDER_MISSING", "SHARE_NOT_FOUND", "SHARE_LIST_FAILED", "SHARES_LIST_FAILED", "SHARES_NONE", "TEST_STOPPED", "FOLDER_TRUNCATED",
            "FOLDER_NONE_BELOW", "FOLDER_LOADING", "CONNECTING", "FIND_SEARCHING", "FIND_DONE", "STATUS_GOOD_4K", "STATUS_GOOD_HD", "STATUS_SLOW",
            "STATUS_TOO_SLOW", "ERR_UNREACHABLE", "ERR_UNREACHABLE_NOADDR", "ERR_SMB1", "ERR_AUTH", "ERR_GUEST", "ERR_ACCESS", "ERR_WRITE_USER",
            "ERR_WRITE_GUEST", "ERR_NO_NETWORK", "ERR_DATA", "ERR_FULL",
        )
        for (k in app.thebox.core.settings.state.StorageText.entries) {
            if (k.name.startsWith("PHONE_") || k.name.startsWith("NOTICE_")) continue
            val id = app.thebox.ui.R.string::class.java.getField("storage_" + k.name.lowercase()).getInt(null)
            val raw = res.getText(id).toString()
            for (l in raw.split('\n')) assertTrue("${k.name}: a line of ${l.length} characters: $l", l.length <= 80)
            if (k.name in labels) assertTrue("${k.name}: a label of ${raw.length} characters", raw.length <= 60 && !raw.contains('\n'))
        }
    }

    // ------------------------------------------------------------------------------------------ the kit (section 9.4)

    @Test fun theKitKnowsPathsProgressAndAmber() {
        assertTrue(SettingsUi.isMonoValue("\\\\NAS\\TV\\Rewind"))
        assertTrue(SettingsUi.isMonoValue("9 of 16 MB"))
        assertTrue(SettingsUi.isMonoValue("10.5 MB/s · 4 ms · Wi-Fi"))
        assertFalse(SettingsUi.isMonoValue("Good for HD channels"))
        assertEquals(0.5625f, SettingsUi.progressFraction("9 of 16 MB")!!, 0.0001f)
        assertNull(SettingsUi.progressFraction("Connecting"))
        assertNull(SettingsUi.progressFraction("0 of 0 MB"))
        assertTrue(SettingsUi.isWarnText(StorageMarks.warn("1 hour · This device (NAS offline)")))
        assertFalse(SettingsUi.isWarnText("1 hour · NAS"))
        assertTrue(SettingsUi.isAccentBadge(app.thebox.core.settings.Badge.IN_USE) && SettingsUi.isAccentBadge(app.thebox.core.settings.Badge.SAVED))
        assertFalse(SettingsUi.isAccentBadge(app.thebox.core.settings.Badge.NEW))
        // The badge text is the canvas colour on a focused (FG-filled) row, FG .9 otherwise.
        assertEquals(SettingsUi.Canvas, SettingsUi.accentBadgeText(true))
        assertEquals(SettingsUi.Fg.copy(alpha = 0.9f), SettingsUi.accentBadgeText(false))
        assertNotNull(SettingsUi.Amber)
    }

    @Test fun storageInfoRowsShowEveryNextStepLine() {
        val id = app.thebox.core.settings.RowId("storage.find.status")
        val two = app.thebox.core.settings.SettingsRow(id, "No computers found.", kind = app.thebox.core.settings.RowKind.Info(listOf("a", "b")))
        assertEquals(112, rowHeight(two))
        val three = two.copy(kind = app.thebox.core.settings.RowKind.Info(listOf("a", "b", "c", "d")))
        assertEquals(56 + SettingsUi.STORAGE_INFO_LINE_H * 4, rowHeight(three))
        val other = two.copy(id = app.thebox.core.settings.RowId("about.x"))
        assertEquals("other pages Info rows are unchanged", SettingsUi.ROW_H, rowHeight(other))
        val progress = two.copy(id = app.thebox.core.settings.RowId("storage.test.progress"), kind = app.thebox.core.settings.RowKind.Info(emptyList()), value = "9 of 16 MB")
        assertEquals(SettingsUi.PROGRESS_ROW_H, rowHeight(progress))
    }
}
