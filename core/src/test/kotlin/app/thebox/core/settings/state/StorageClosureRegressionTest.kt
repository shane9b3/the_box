package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.tree.PauseRewindRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.setup.lan.PhoneApi
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FoundComputer
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.TestStopReason
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.fakes.FakeNetworkFolders
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests of the save-location closure fix pass, half A (lead rulings 2026-10-28): SEC-S3, SEC-S4, O-6, C-N7
 * (the page), C-N4 (the editor), Q-D7. One or more per finding, named after it.
 */
class StorageClosureRegressionTest {
    private val nas = StorageLocation(
        "smb-1", StorageKind.SMB, "NAS", smb = SmbAddress("nas", 445, "TV", "Rewind", user = "tv"),
        lastTest = LocationTestResult(TestVerdict.GOOD_4K, 10_500_000, 11_000_000, 4, 0, 1L shl 40, true, 1_000_000L - 3 * 86_400_000L),
    )

    /** Keeps each password array StoragePages hands to `open` (the real `open` never zeroes it, SEC-S4). */
    private class KeepingOpen(val inner: FakeNetworkFolders) : NetworkFolders by inner {
        val given = CopyOnWriteArrayList<CharArray>()
        override suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult {
            if (password != null) given += password
            return inner.open(address, password?.copyOf())
        }
    }

    private fun world(initial: List<StorageLocation> = emptyList(), wrap: (FakeNetworkFolders) -> NetworkFolders = { it }): SettingsWorld {
        val w = SettingsWorld(applyIntents = true, foldersWrap = wrap)
        initial.forEach { l -> runBlocking { w.locations.saveSmb(l.smb!!, l.name, null, l.lastTest) } }
        return w
    }

    private fun SettingsWorld.rows(): List<SettingsRow> = ui.page!!.rows
    private fun SettingsWorld.row(id: RowId): SettingsRow = assertNotNull(rows().firstOrNull { it.id == id }, "row ${id.key}: ${rows().map { it.id.key }}")
    private fun SettingsWorld.press(id: RowId) { focus(id); act(BoxAction.SELECT) }
    private fun SettingsWorld.type(id: RowId, text: String) {
        focus(id); act(BoxAction.SELECT)
        machine.dispatch(SettingsEvent.EditorClosed(ui.editor!!.row, text, committed = true)); pump()
    }
    private fun SettingsWorld.lastOpenHost(): String = folders.calls.last { it.startsWith("open:") }.split(':')[1]

    private fun SettingsWorld.phoneOnFoundNas(): SettingsRoute.PhoneSetup {
        folders.openAnswer = { a, hasPw -> if (hasPw) BrowseResult.Opened(app.thebox.core.storage.BrowseHandle(7, a, SignInMode.USER)) else BrowseResult.NeedsSignIn() }
        folders.findStates = listOf(FindState(listOf(FoundComputer("DESKTOP-7F3K2LQ", "192.168.1.20", NameSource.NETBIOS)), false, "192.168.1.x", false))
        open(SettingsRoute.FindComputers)
        press(RowId("storage.find.pc.192.168.1.20"))
        press(StoragePages.ROW_SI_PHONE)
        return assertIs(ui.route)
    }

    // ------------------------------------------------------------------------------------------ SEC-S3

    /** SEC-S3: the phone is told the picked address (its name for display only) and the TV keeps signing in to it. */
    @Test fun secS3_thePhoneNeverSwapsThePickedAddressForADiscoveredName() {
        val w = world()
        val phone = w.phoneOnFoundNas()
        val ctx = assertNotNull(w.storage.context(phone.browse!!))
        assertEquals("192.168.1.20", ctx.host)
        assertEquals("DESKTOP-7F3K2LQ", ctx.name)
        // An older page that sent the name back: still the same computer, still the picked address.
        w.phoneLink.submit(PhoneSubmission.NetworkFolder("DESKTOP-7F3K2LQ", "TV", null, "shawn", null, "pw".toCharArray(), false))
        w.pump()
        assertEquals("192.168.1.20", w.lastOpenHost())
    }

    /** SEC-S3: the address the page sends back is the picked one; a computer the viewer changed on the phone wins. */
    @Test fun secS3b_theAddressIsKeptAndAChangedComputerWins() {
        val w = world()
        w.phoneOnFoundNas()
        // Every phone sign-in is refused, so the page stays and takes the next submission.
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn(app.thebox.core.storage.BrowseError(app.thebox.core.storage.BrowseErrorKind.AUTH)) }
        assertIs<SettingsRoute.PhoneSetup>(w.ui.route)
        w.phoneLink.submit(PhoneSubmission.NetworkFolder("192.168.1.20", "TV", null, "shawn", null, "pw".toCharArray(), false))
        w.pump()
        assertEquals("192.168.1.20", w.lastOpenHost())
        w.phoneLink.submit(PhoneSubmission.NetworkFolder("192.168.1.30", "TV", null, "shawn", null, "pw".toCharArray(), false))
        w.pump()
        assertEquals("192.168.1.30", w.lastOpenHost(), "the viewer chose Change on the phone")
    }

    /** SEC-S3: a stored location's re-sign-in from the phone sends its host, not its label (which fails validation). */
    @Test fun secS3c_aStoredLocationsPhoneContextPassesTheServersCheck() {
        val labelled = nas.copy(name = "Living room NAS")
        val w = world(listOf(labelled))
        w.locations.setState("smb-1", LocationState.SIGN_IN_NEEDED)
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_SIGNIN)
        w.press(StoragePages.ROW_SI_PHONE)
        val phone = assertIs<SettingsRoute.PhoneSetup>(w.ui.route)
        val ctx = assertNotNull(w.storage.context(phone.browse!!))
        assertEquals("nas", ctx.host)
        assertEquals("Living room NAS", ctx.name)
        assertNotNull(PhoneApi.splitFolder(ctx.host, ctx.share, null), "the page's unchanged fields pass the server's check")
        assertNull(PhoneApi.splitFolder(ctx.name, ctx.share, null), "the label would have been refused (400)")
    }

    /** SEC-S3: a port other than 445 goes to the phone with the address, so an unchanged page keeps it. */
    @Test fun secS3d_aTypedPortGoesToThePhone() {
        val w = world()
        w.open(SettingsRoute.FindComputers)
        w.type(StoragePages.ROW_ENTER, "192.168.1.20:4445")
        val shares = assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        assertEquals("192.168.1.20:4445", w.storage.context(shares.browse)?.host)
    }

    // ------------------------------------------------------------------------------------------ SEC-S4

    /** SEC-S4: every password copy handed to `NetworkFolders.open` is zeroed after the call (TV sign-in and Test). */
    @Test fun secS4_everyOpenCopyIsZeroed() {
        lateinit var k: KeepingOpen
        val w = world(listOf(nas)) { KeepingOpen(it).also { x -> k = x } }
        w.folders.openAnswer = { a, hasPw -> if (hasPw) BrowseResult.Opened(app.thebox.core.storage.BrowseHandle(7, a, SignInMode.USER)) else BrowseResult.NeedsSignIn() }
        w.locations.setState("smb-1", LocationState.SIGN_IN_NEEDED)
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_SIGNIN)
        w.type(StoragePages.ROW_SI_PASSWORD, "tv-side-secret")
        w.press(StoragePages.ROW_SI_GO)
        // A walk's sign-in.
        w.folders.findStates = listOf(FindState(listOf(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)), false, "192.168.1.x", false))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        w.type(StoragePages.ROW_SI_USER, "tv")
        w.type(StoragePages.ROW_SI_PASSWORD, "walk-secret")
        w.press(StoragePages.ROW_SI_GO)
        assertTrue(k.given.size >= 2, "opens with a password: ${k.given.size}")
        for (pw in k.given) assertTrue(pw.all { it == '\u0000' }, "a copy given to open() was left: ${String(pw)}")
    }

    // ------------------------------------------------------------------------------------------ O-6

    private fun demoted(failure: StorageFailure?, freeBytes: Long? = null): SettingsWorld {
        val w = world(listOf(nas))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.locations.setState("smb-1", LocationState.DEMOTED, failure = failure, freeBytes = freeBytes)
        return w
    }

    /** O-6: a folder demoted because it refused the ring file (ACCESS) says so everywhere, not "kept dropping out". */
    @Test fun o6_aRefusedRingFileReadsAsNoAccess() {
        val w = demoted(StorageFailure.ACCESS)
        w.open(SettingsRoute.SaveLocation())
        assertEquals("ERR_ACCESS(NAS)", StorageMarks.plain(w.row(RowId("storage.loc.smb-1")).description!!))
        w.open(SettingsRoute.LocationDetail("smb-1"))
        assertEquals(RowKind.Info(listOf("ERR_ACCESS(NAS)", "ERR_ACCESS_STEP(NAS)"), app.thebox.core.settings.Tone.WARN), w.row(StoragePages.ROW_D_STATUS).kind)
        w.open(SettingsRoute.PauseRewind)
        val save = w.row(PauseRewindRows.TIMESHIFT_LOCATION)
        assertTrue(StorageMarks.isWarn(save.description))
        assertEquals("ERR_ACCESS(NAS)", StorageMarks.plain(save.description!!))
        // The link says what the toast says (ACCESS falls back as "sign in", StorageTargetsImpl).
        w.open(SettingsRoute.Section(SectionId.PLAYBACK))
        assertEquals("PB_PAUSE_REWIND_VALUE_SIGN_IN(TS_AUTO(TS_HOUR),NAS)", StorageMarks.plain(w.row(PlaybackRows.PAUSE_REWIND).value!!))
    }

    /** O-6: FULL reads the space texts; REMOVED the missing folder; no failure keeps "kept dropping out". */
    @Test fun o6b_fullRemovedAndUnknown() {
        demoted(StorageFailure.FULL, freeBytes = 1L shl 30).let { w ->
            w.open(SettingsRoute.LocationDetail("smb-1"))
            val lines = (w.row(StoragePages.ROW_D_STATUS).kind as RowKind.Info).lines
            assertTrue(lines[0].startsWith("ERR_FULL(NAS,SIZE_GB(1.0),"), lines[0])
            assertEquals("ERR_FULL_STEP", lines[1])
        }
        demoted(StorageFailure.REMOVED).let { w ->
            w.open(SettingsRoute.LocationDetail("smb-1"))
            assertEquals(listOf("FOLDER_MISSING(Rewind)", "ERR_DATA_STEP"), (w.row(StoragePages.ROW_D_STATUS).kind as RowKind.Info).lines)
            w.open(SettingsRoute.PauseRewind)
            assertEquals("FOLDER_MISSING(Rewind)", StorageMarks.plain(w.row(PauseRewindRows.TIMESHIFT_LOCATION).description!!))
        }
        demoted(null).let { w ->
            w.open(SettingsRoute.SaveLocation())
            assertEquals("STATUS_DEMOTED(NAS)", StorageMarks.plain(w.row(RowId("storage.loc.smb-1")).description!!))
            w.open(SettingsRoute.PauseRewind)
            assertEquals("STATUS_DEMOTED(NAS)", StorageMarks.plain(w.row(PauseRewindRows.TIMESHIFT_LOCATION).description!!))
            w.open(SettingsRoute.Section(SectionId.PLAYBACK))
            assertEquals("PB_PAUSE_REWIND_VALUE_FALLBACK(TS_AUTO(TS_HOUR),NAS)", StorageMarks.plain(w.row(PlaybackRows.PAUSE_REWIND).value!!))
        }
    }

    // ------------------------------------------------------------------------------------------ C-N7 (the page)

    /** C-N7: a Test that stopped at its deadline says "Stopped: NAS took too long" with a next step, and saves nothing. */
    @Test fun cN7_aTestThatTookTooLongSaysSoAndSavesNothing() {
        val w = world()
        w.folders.testAnswer = { _, _, _ ->
            LocationTestResult(TestVerdict.FAILED, 1_000_000, 0, 0, 0, -1, true, 0L, stop = TestStopReason.TOOK_TOO_LONG)
        }
        w.folders.findStates = listOf(FindState(listOf(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)), false, "192.168.1.x", false))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        w.press(RowId(StoragePages.SHARE_ITEM + "TV"))
        w.press(StoragePages.ROW_F_USE)
        val v = w.row(StoragePages.ROW_T_VERDICT)
        assertEquals("TEST_TOOK_TOO_LONG(NAS)", v.label)
        assertEquals(RowKind.Info(listOf("TEST_TOOK_TOO_LONG_STEP(NAS)"), app.thebox.core.settings.Tone.WARN), v.kind)
        assertEquals(StoragePages.ROW_T_AGAIN, w.focusedRow()?.id)
        assertNull(w.rows().firstOrNull { it.id == StoragePages.ROW_T_USE || it.id == StoragePages.ROW_T_ANYWAY })
        assertTrue(w.locations.status.value.isEmpty(), "nothing saved")
    }

    // ------------------------------------------------------------------------------------------ C-N4 (the editor)

    /** C-N4: "Enter a name or address" with a `..` folder or a control character has its own reason and next step. */
    @Test fun cN4_aDotDotFolderHasItsOwnReason() {
        val rule = assertNotNull(WpaPages.rowRules(SettingsTexts.KEYS)[StoragePages.ROW_ENTER])
        assertNull(rule("\\\\NAS\\TV\\Rewind"))
        assertEquals("ERR_HOST_PATH", rule("\\\\NAS\\TV\\..\\Rewind"))
        assertEquals("ERR_HOST_PATH", rule("smb://nas/TV/%00"))
        assertEquals("ERR_HOST", rule("na s"))
        assertEquals("ERR_HOST", rule("[fe80::1]"))
        assertTrue(!StoragePages.validHost("") && !StoragePages.validHost("   "))
    }

    // ------------------------------------------------------------------------------------------ Q-D7

    /** Q-D7: the Playback link's place is at most LABEL_LINK_MAX_CHARS, with "…" when cut. */
    @Test fun qD7_aLongNameIsCutInThePlaybackLink() {
        val w = world(listOf(nas.copy(name = "Living room NAS upstairs")))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.locations.setState("smb-1", LocationState.READY, freeBytes = 1L shl 40)
        w.open(SettingsRoute.Section(SectionId.PLAYBACK))
        assertEquals("PB_PAUSE_REWIND_VALUE(TS_AUTO(TS_HOUR),Living room NAS…)", w.row(PlaybackRows.PAUSE_REWIND).value)
        w.locations.setState("smb-1", LocationState.UNREACHABLE)
        w.ui.page?.route?.let { w.effects.loads += it }; w.pump()
        assertEquals("PB_PAUSE_REWIND_VALUE_FALLBACK(TS_AUTO(TS_HOUR),Living room NAS…)", StorageMarks.plain(w.row(PlaybackRows.PAUSE_REWIND).value!!))
    }
}
