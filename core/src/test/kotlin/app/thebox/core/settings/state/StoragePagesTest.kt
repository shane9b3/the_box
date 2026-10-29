package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.settings.Badge
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Tone
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.settings.tree.PauseRewindRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.BrowseHandle
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FolderListing
import app.thebox.core.storage.FoundComputer
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.VolumeCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-S4's pages (docs/design/m6-storage-locations.md section 9, 7.2-7.8, 16.2 `StoragePagesTest`), through the real
 * settings machine over fake `StorageLocations`, `NetworkFolders` and `PhoneLink`: rows and texts, the D-pad journeys of
 * section 9.3 (TV and phone sign-in, a typed path, a missing folder, up, BACK out), the credential rules (no password in a
 * route or a `toString`) and the focus rules.
 */
class StoragePagesTest {
    private val nas = StorageLocation(
        "smb-1", StorageKind.SMB, "NAS", smb = SmbAddress("nas", 445, "TV", "Rewind", user = "tv"),
        lastTest = LocationTestResult(TestVerdict.GOOD_4K, 10_500_000, 11_000_000, 4, 0, 1L shl 40, true, 1_000_000L - 3 * 86_400_000L),
    )

    private fun world(initial: List<StorageLocation> = emptyList(), keyboard: Boolean = true): SettingsWorld {
        val w = SettingsWorld(applyIntents = true)
        w.storageEnv.keyboard = keyboard
        initial.forEach { l -> runBlocking { w.locations.saveSmb(l.smb!!, l.name, null, l.lastTest) } }
        return w
    }

    private fun SettingsWorld.rows(): List<SettingsRow> = ui.page!!.rows
    private fun SettingsWorld.row(id: RowId): SettingsRow = assertNotNull(rows().firstOrNull { it.id == id }, "row ${id.key} on ${ui.page?.route}: ${rows().map { it.id.key }}")
    private fun SettingsWorld.refresh() { ui.page?.route?.let { effects.loads += it }; pump() }
    private fun SettingsWorld.press(id: RowId, action: BoxAction = BoxAction.SELECT) { focus(id); act(action) }
    private fun SettingsWorld.type(id: RowId, text: String) {
        focus(id); act(BoxAction.SELECT)
        machine.dispatch(SettingsEvent.EditorClosed(ui.editor!!.row, text, committed = true)); pump()
    }
    private fun SettingsWorld.routes(): List<SettingsRoute> = ui.stack + ui.route

    private fun opened(a: SmbAddress, mode: SignInMode = SignInMode.GUEST) = BrowseResult.Opened(BrowseHandle(7, a, mode))

    // ------------------------------------------------------------------------------------------ Pause and rewind

    @Test fun pauseRewindPageIsLengthSaveToAndTheTwoAdvancedRows() {
        val w = world().open(SettingsRoute.PauseRewind)
        assertEquals(listOf(PauseRewindRows.TIMESHIFT, PauseRewindRows.TIMESHIFT_LOCATION), w.rows().map { it.id }, "simple view")
        val length = w.row(PauseRewindRows.TIMESHIFT)
        assertEquals("PB_TIMESHIFT_LENGTH", length.label)
        val save = w.row(PauseRewindRows.TIMESHIFT_LOCATION)
        assertEquals(RowKind.Link(SettingsRoute.SaveLocation()), save.kind)
        assertEquals("SAVE_TO", save.label)
        assertEquals("THIS_DEVICE", save.value)
        assertEquals("SAVE_TO_DESC", save.description)
        assertEquals(EffectTiming.NEXT_TUNE, save.effect)
        assertTrue(save.enabled)
        w.machine.dispatch(SettingsEvent.Advanced(true)); w.advanced = true; w.pump()
        assertEquals(
            listOf(PauseRewindRows.TIMESHIFT, PauseRewindRows.TIMESHIFT_LOCATION, PauseRewindRows.TIMESHIFT_HOLD, PauseRewindRows.TIMESHIFT_PROTECT),
            w.rows().map { it.id },
        )
    }

    @Test fun saveToIsDisabledWhileRewindIsOff() {
        val w = world()
        w.store.update(PlaybackDomain) { it.copy(timeshift = TimeshiftLength.OFF) }
        w.open(SettingsRoute.PauseRewind)
        val save = w.row(PauseRewindRows.TIMESHIFT_LOCATION)
        assertFalse(save.enabled)
        assertEquals("SAVE_TO_OFF_REASON", save.disabledReason)
        w.press(PauseRewindRows.TIMESHIFT_LOCATION)
        assertEquals(SettingsRoute.PauseRewind, w.ui.route, "a disabled row does not open")
        // Off on the Playback link: no place.
        w.open(SettingsRoute.Section(SectionId.PLAYBACK))
        assertEquals("OFF", w.row(PlaybackRows.PAUSE_REWIND).value)
    }

    @Test fun theLinkReadsLengthAndPlaceAndTurnsAmberWhileFallingBack() {
        val w = world(listOf(nas))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.locations.setState("smb-1", LocationState.READY, freeBytes = 1L shl 40)
        w.open(SettingsRoute.Section(SectionId.PLAYBACK))
        assertEquals("PB_PAUSE_REWIND_VALUE(TS_AUTO(TS_HOUR),NAS)", w.row(PlaybackRows.PAUSE_REWIND).value)
        assertEquals("PB_PAUSE_REWIND_DESC", w.row(PlaybackRows.PAUSE_REWIND).description)
        w.locations.setState("smb-1", LocationState.UNREACHABLE)
        w.refresh()
        val v = w.row(PlaybackRows.PAUSE_REWIND).value
        assertTrue(StorageMarks.isWarn(v), "amber while NAS is offline")
        assertEquals("PB_PAUSE_REWIND_VALUE_FALLBACK(TS_AUTO(TS_HOUR),NAS)", StorageMarks.plain(v!!))
        // The page says so too, in amber, and still offers Save to.
        w.open(SettingsRoute.PauseRewind)
        val save = w.row(PauseRewindRows.TIMESHIFT_LOCATION)
        assertEquals("NAS", save.value)
        assertEquals("SAVE_TO_DESC_UNREACHABLE(NAS)", StorageMarks.plain(save.description!!))
        assertTrue(StorageMarks.isWarn(save.description))
        w.locations.setState("smb-1", LocationState.SIGN_IN_NEEDED)
        w.refresh()
        assertEquals("SAVE_TO_DESC_SIGN_IN(NAS)", StorageMarks.plain(w.row(PauseRewindRows.TIMESHIFT_LOCATION).description!!))
    }

    @Test fun aUsbDriveShowsItsCapUpFront() {
        val w = world()
        val vol = VolumeCatalog.Volume("1234-ABCD", "SanDisk", java.io.File("x"), 64L shl 30, 41L shl 30)
        w.volumes.volumes.value = listOf(vol)
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "vol-1234-ABCD") }
        w.open(SettingsRoute.PauseRewind)
        val length = w.row(PauseRewindRows.TIMESHIFT)
        val options = (length.kind as RowKind.Choice).options
        assertEquals("TS_UP_TO_ON_DRIVE(TS_HOURS_MIN(1,11))", options.first { it.id == TimeshiftLength.H_2.name }.detail)
        assertNull(options.first { it.id == TimeshiftLength.H_1.name }.detail)
        assertEquals("TS_AUTO(TS_HOUR)", length.value, "Auto is 1 h on a drive and fits")
        assertTrue(length.description!!.startsWith("PB_TIMESHIFT_DESC_AT(TS_HOUR,TS_GB("), length.description)
        assertTrue(length.description!!.contains("the USB drive") || length.description!!.contains("PLACE_USB_DRIVE"))
        w.machine.dispatch(SettingsEvent.Advanced(true)); w.advanced = true; w.pump()
        assertTrue(w.row(PauseRewindRows.TIMESHIFT_PROTECT).description!!.contains("PB_TIMESHIFT_PROTECT_ELSEWHERE(PLACE_USB_DRIVE)"))
    }

    // ------------------------------------------------------------------------------------------ Save to

    @Test fun saveToListsThisDeviceDrivesAndFolders() {
        val w = world(listOf(nas))
        w.volumes.volumes.value = listOf(VolumeCatalog.Volume("U1", "SanDisk", java.io.File("x"), 64L shl 30, 41L shl 30))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.open(SettingsRoute.SaveLocation())
        assertEquals(
            listOf(StoragePages.ROW_DEVICE.key, "storage.loc.vol-U1", "storage.loc.smb-1", StoragePages.ROW_ADD.key),
            w.rows().map { it.id.key },
        )
        assertEquals("SAVE_TO", w.ui.page!!.title)
        val device = w.row(StoragePages.ROW_DEVICE)
        assertEquals(emptySet(), (device.kind as RowKind.Item).badges)
        assertEquals("THIS_DEVICE_DESC_UNKNOWN", device.description) // the fake env knows no storage
        val usb = w.row(RowId("storage.loc.vol-U1"))
        assertEquals("USB_DRIVE_NAMED(SanDisk)", usb.label)
        assertEquals("USB_DRIVE_DESC(SIZE_GB(41.0))", usb.description)
        val folder = w.row(RowId("storage.loc.smb-1"))
        assertEquals(setOf(Badge.IN_USE), (folder.kind as RowKind.Item).badges)
        assertEquals(SettingsRoute.LocationDetail("smb-1"), folder.kind.route)
        assertEquals(listOf("test_folder", "signin", "remove"), folder.kind.menu.map { it.id }, "every option is also a row of LocationDetail")
        assertEquals(RowKind.Link(SettingsRoute.FindComputers), w.row(StoragePages.ROW_ADD).kind)
        assertEquals("ADD_NETWORK_FOLDER_DESC", w.row(StoragePages.ROW_ADD).description)
    }

    @Test fun okOnThisDeviceOrADriveChoosesAtOnce() {
        val w = world()
        w.volumes.volumes.value = listOf(VolumeCatalog.Volume("U1", "SanDisk", java.io.File("x"), 64L shl 30, 41L shl 30))
        w.open(SettingsRoute.SaveLocation())
        w.press(RowId("storage.loc.vol-U1"))
        assertEquals("vol-U1", w.locations.chosen(StoragePurpose.TIMESHIFT))
        w.refresh()
        assertEquals(setOf(Badge.IN_USE), (w.row(RowId("storage.loc.vol-U1")).kind as RowKind.Item).badges)
        w.press(StoragePages.ROW_DEVICE)
        assertEquals(StorageConstants.INTERNAL_ID, w.locations.chosen(StoragePurpose.TIMESHIFT))
    }

    @Test fun aRememberedDriveThatIsNotPluggedInOpensItsDetail() {
        val w = world()
        runBlocking {
            w.locations.saveSmb(SmbAddress("h", share = "s"), "x", null, null) // just to have a row, then replace it with a VOLUME one
        }
        val w2 = SettingsWorld(); w2.storageEnv.keyboard = true
        // A VOLUME location in the list that is not mounted.
        val usb = StorageLocation("vol-U9", StorageKind.VOLUME, "Old stick", volumeUuid = "U9")
        val locs = app.thebox.core.storage.fakes.FakeStorageLocations(listOf(usb))
        val pages = StoragePages(
            w2.store, SettingsTexts.KEYS, StorageTexts.KEYS, w2.wpa, w2.env, TimeshiftPlaces(locs, w2.volumes, StorageTexts.KEYS), locs, w2.folders, w2.volumes,
            w2.phoneLink, w2.storageEnv, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), reapGraceMs = 0L,
        )
        locs.setState("vol-U9", LocationState.NOT_CONNECTED)
        val detail = pages.build(SettingsRoute.LocationDetail("vol-U9"), PageContext(false, 1_000_000L))
        assertEquals(listOf(StoragePages.ROW_D_STATUS, StoragePages.ROW_D_USE, StoragePages.ROW_D_REMOVE), detail.rows.map { it.id })
        val use = detail.rows[1]
        assertFalse(use.enabled)
        assertEquals("DETAIL_CONNECT_DRIVE", use.disabledReason)
    }

    // ------------------------------------------------------------------------------------------ one location

    @Test fun locationDetailRowsAndStatus() {
        val w = world(listOf(nas))
        w.open(SettingsRoute.LocationDetail("smb-1"))
        assertEquals(
            listOf(
                StoragePages.ROW_D_STATUS, StoragePages.ROW_D_ADDRESS, StoragePages.ROW_D_USE, StoragePages.ROW_D_TEST,
                StoragePages.ROW_D_NAME, StoragePages.ROW_D_SIGNIN, StoragePages.ROW_D_REMOVE,
            ),
            w.rows().map { it.id },
        )
        assertEquals(RowKind.Info(listOf("STATUS_TESTED_AGO(STATUS_GOOD_4K,AGO_DAYS(3))"), Tone.NORMAL), w.row(StoragePages.ROW_D_STATUS).kind)
        assertEquals("\\\\nas\\TV\\Rewind", w.row(StoragePages.ROW_D_ADDRESS).value)
        assertEquals("DETAIL_CHANGE_SIGN_IN", w.row(StoragePages.ROW_D_SIGNIN).label)
        val remove = w.row(StoragePages.ROW_D_REMOVE).kind as RowKind.Action
        assertTrue(remove.destructive)
        assertEquals("DETAIL_REMOVE_TITLE(NAS › TV › Rewind)", remove.confirm!!.title)
        assertEquals("DETAIL_REMOVE_BODY", remove.confirm.body)
        // In use: no "Use for rewind"; a sign-in problem says so and offers "Sign in again".
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.locations.setState("smb-1", LocationState.SIGN_IN_NEEDED)
        w.refresh()
        assertNull(w.rows().firstOrNull { it.id == StoragePages.ROW_D_USE })
        assertEquals("DETAIL_SIGN_IN_AGAIN", w.row(StoragePages.ROW_D_SIGNIN).label)
        assertEquals(Tone.WARN, (w.row(StoragePages.ROW_D_STATUS).kind as RowKind.Info).tone)
    }

    @Test fun useForRewindChoosesAtOnceWhenTheLastTestIsFreshAndGood() {
        val w = world(listOf(nas))
        w.now = 1_000_000L
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_USE)
        assertEquals("smb-1", w.locations.chosen(StoragePurpose.TIMESHIFT))
    }

    @Test fun useForRewindRunsTheTestFirstWhenItIsOldOrSlow() {
        val w = world(listOf(nas.copy(lastTest = nas.lastTest!!.copy(atWallMs = 0L))))
        w.now = StorageConstants.TEST_FRESH_MS + 5L
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_USE)
        assertEquals(StorageConstants.INTERNAL_ID, w.locations.chosen(StoragePurpose.TIMESHIFT), "not yet")
        assertEquals(SettingsRoute.LocationTest(locationId = "smb-1"), w.ui.route)
        assertTrue(w.folders.calls.any { it.startsWith("test:") })
        // The Test finished GOOD: "Use for rewind" is the focused button.
        assertEquals(StoragePages.ROW_T_USE, w.focusedRow()?.id)
        w.act(BoxAction.SELECT)
        assertEquals("smb-1", w.locations.chosen(StoragePurpose.TIMESHIFT))
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
    }

    @Test fun renameWritesTheName() {
        val w = world(listOf(nas))
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.type(StoragePages.ROW_D_NAME, "Basement NAS")
        assertEquals("Basement NAS", w.locations.status.value.single().location.name)
    }

    @Test fun removeAsksFirstWithTheSafeButtonFocusedThenReturnsToSaveTo() {
        val w = world(listOf(nas))
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, "smb-1") }
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_REMOVE)
        val c = assertNotNull(w.ui.confirm)
        assertEquals(1, c.focused, "Cancel is focused")
        w.act(BoxAction.LEFT); w.act(BoxAction.SELECT)
        assertTrue(w.locations.status.value.isEmpty())
        assertEquals(StorageConstants.INTERNAL_ID, w.locations.chosen(StoragePurpose.TIMESHIFT))
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
    }

    @Test fun removeFromTheItemMenuAsksToo() {
        val w = world(listOf(nas))
        w.open(SettingsRoute.SaveLocation())
        w.focus(RowId("storage.loc.smb-1")); w.act(BoxAction.CONTEXT_MENU)
        w.act(BoxAction.DOWN); w.act(BoxAction.DOWN); w.act(BoxAction.SELECT) // Remove
        assertNotNull(w.ui.confirm, "the confirm card, never a silent delete")
        w.act(BoxAction.LEFT); w.act(BoxAction.SELECT)
        assertTrue(w.locations.status.value.isEmpty())
    }

    @Test fun testThisFolderFromTheMenuOpensTheTest() {
        val w = world(listOf(nas))
        w.open(SettingsRoute.SaveLocation())
        w.focus(RowId("storage.loc.smb-1")); w.act(BoxAction.CONTEXT_MENU); w.act(BoxAction.SELECT)
        assertEquals(SettingsRoute.LocationTest(locationId = "smb-1"), w.ui.route)
    }

    // ------------------------------------------------------------------------------------------ Find computers

    private fun findState(vararg c: FoundComputer, searching: Boolean = false, vpn: Boolean = false, error: BrowseError? = null) =
        FindState(c.toList(), searching, "192.168.1.x", vpn, error)

    @Test fun findComputersOrdersAndLabelsTheResults() {
        val w = world(listOf(nas))
        // Q-U2 (closure fix pass): the finder's merge orders and marks saved (DiscoveryMergeTest); the page keeps both.
        val found = listOf(
            FoundComputer(null, "192.168.1.50", NameSource.NONE),
            FoundComputer("DESKTOP-7F3K2LQ", "192.168.1.30", NameSource.NETBIOS),
            FoundComputer("NAS", "192.168.1.20", NameSource.NSD, saved = true),
            FoundComputer("mac", "192.168.1.9", NameSource.NSD),
        ).sortedWith(app.thebox.core.storage.discovery.DiscoveryMerge.ORDER)
        w.folders.findStates = listOf(findState(*found.toTypedArray()))
        w.open(SettingsRoute.FindComputers)
        val ids = w.rows().map { it.id.key }
        assertEquals(
            listOf("storage.find.status", "storage.find.pc.192.168.1.20", "storage.find.pc.192.168.1.30", "storage.find.pc.192.168.1.9", "storage.find.pc.192.168.1.50", "storage.find.enter", "storage.find.phone", "storage.find.again"),
            ids, "the finder's order (saved first, then named A-Z, then unnamed by address); Enter, Phone, Search again last",
        )
        assertEquals("FIND_DONE(4)", w.row(StoragePages.ROW_STATUS).label)
        assertEquals(setOf(Badge.SAVED), (w.row(RowId("storage.find.pc.192.168.1.20")).kind as RowKind.Item).badges)
        assertEquals("192.168.1.30", w.row(RowId("storage.find.pc.192.168.1.30")).value)
        assertEquals("COMPUTER_NO_NAME", w.row(RowId("storage.find.pc.192.168.1.50")).value)
        assertEquals("192.168.1.50", w.row(RowId("storage.find.pc.192.168.1.50")).label, "an unnamed computer's label is its address")
        val enter = w.row(StoragePages.ROW_ENTER)
        assertEquals(RowKind.Text("", app.thebox.core.settings.TextHint.HOST, chips = StoragePages.HOST_CHIPS), enter.kind)
        assertEquals("FIND_ENTER_DESC", enter.description)
    }

    @Test fun findFocusIsEnterUntilTheFirstComputerArrives() {
        val w = world()
        w.folders.findStates = listOf(findState(searching = true))
        w.open(SettingsRoute.FindComputers)
        assertEquals("storage.find.searching", w.rows().first().id.key)
        assertEquals(StoragePages.ROW_ENTER, w.focusedRow()?.id)
        assertEquals("FIND_SEARCHING(192.168.1.x)", w.row(StoragePages.ROW_SEARCHING).label)
        // The first computer arrives: the page asks for focus on it (once).
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD), searching = true))
        w.storage.closed(SettingsRoute.FindComputers)
        w.refresh()
        assertEquals("storage.find.pc.192.168.1.20", w.focusedRow()?.id?.key)
    }

    @Test fun findShowsTheVpnAndNoResultAndNoNetworkRowsWithReasonAndNextStep() {
        val w = world()
        w.folders.findStates = listOf(findState(vpn = true))
        w.open(SettingsRoute.FindComputers)
        val none = w.row(StoragePages.ROW_STATUS)
        assertEquals("FIND_NONE_TITLE", none.label)
        assertEquals(Tone.WARN, (none.kind as RowKind.Info).tone)
        assertEquals(listOf("FIND_NONE_STEP"), none.kind.lines, "the next steps are the row's own lines (the string has a line break)")
        val vpn = w.row(StoragePages.ROW_VPN)
        assertEquals(Tone.WARN, (vpn.kind as RowKind.Info).tone)
        assertEquals("FIND_VPN_TITLE", vpn.label)
        // No network at all.
        w.folders.findStates = listOf(findState(error = BrowseError(BrowseErrorKind.NO_NETWORK)))
        w.storage.closed(SettingsRoute.FindComputers)
        w.refresh()
        assertEquals("ERR_NO_NETWORK", w.row(StoragePages.ROW_STATUS).label)
    }

    @Test fun findStopsWhenThePageCloses() {
        val w = world()
        w.open(SettingsRoute.FindComputers)
        assertEquals(1, w.folders.calls.count { it == "find" })
        w.storage.closed(SettingsRoute.FindComputers)
        w.act(BoxAction.LEFT)
        w.open(SettingsRoute.FindComputers)
        assertEquals(2, w.folders.calls.count { it == "find" }, "a new page starts a new search; the old one is cancelled")
    }

    // ------------------------------------------------------------------------------------------ the folder walk (9.3)

    private fun walkToFolder(w: SettingsWorld, computer: String = "192.168.1.20"): Long {
        w.folders.findStates = listOf(findState(FoundComputer("NAS", computer, NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.$computer"))
        val shares = assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        w.press(RowId("storage.share.item.TV"))
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route)
        return shares.browse
    }

    @Test fun aGuestNasWalkEndsWithTheLocationSavedAndChosen() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path ->
            FolderListing(share, path, if (path.isEmpty()) listOf("Recordings", "Photos") else if (path == "Recordings") listOf("Rewind") else emptyList())
        }
        walkToFolder(w)
        assertEquals("\\\\NAS\\TV", w.ui.page!!.title)
        assertEquals(
            listOf("storage.folder.use", "storage.folder.item.Recordings", "storage.folder.item.Photos"),
            w.rows().map { it.id.key }, "no Up row at the share root",
        )
        // Into a folder pushes nothing: the same route, the path changes.
        val route = w.ui.route
        val depth = w.ui.stack.size
        w.press(RowId("storage.folder.item.Recordings"))
        assertEquals(route, w.ui.route)
        assertEquals(depth, w.ui.stack.size)
        assertEquals("\\\\NAS\\TV\\Recordings", w.ui.page!!.subtitle)
        assertEquals(listOf("storage.folder.use", "storage.folder.up", "storage.folder.item.Rewind"), w.rows().map { it.id.key })
        w.press(RowId("storage.folder.item.Rewind"))
        assertEquals("\\\\NAS\\TV\\Recordings\\Rewind", w.row(StoragePages.ROW_F_USE).value)
        // Up one folder.
        w.press(StoragePages.ROW_F_UP)
        assertEquals("\\\\NAS\\TV\\Recordings", w.ui.page!!.subtitle)
        w.press(RowId("storage.folder.item.Rewind"))
        // Use this folder: the Test runs and ends GOOD, with "Use this folder" focused.
        w.press(StoragePages.ROW_F_USE)
        assertIs<SettingsRoute.LocationTest>(w.ui.route)
        assertEquals(StoragePages.ROW_T_USE, w.focusedRow()?.id)
        val verdict = w.row(StoragePages.ROW_T_VERDICT)
        assertEquals("STATUS_GOOD_4K", verdict.label)
        // U-S6: the details are the row's last line (the value column cut the next step).
        assertNull(verdict.value)
        assertEquals(listOf("VERDICT_STEP_GOOD_4K(SIZE_TB(1.0),NAS)", "VERDICT_DETAILS(10.5,4,WIFI)"), (verdict.kind as RowKind.Info).lines)
        assertEquals(Tone.GOOD, (verdict.kind as RowKind.Info).tone)
        w.act(BoxAction.SELECT)
        val saved = w.locations.status.value.single().location
        assertEquals(SmbAddress("192.168.1.20", 445, "TV", "Recordings/Rewind", null, null, false, "192.168.1.20"), saved.smb)
        assertEquals("NAS", saved.name)
        assertEquals(saved.id, w.locations.chosen(StoragePurpose.TIMESHIFT))
        assertNotNull(saved.lastTest)
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route, "popTo(Save to)")
        assertEquals(0, w.storage.openSessionCount(), "the session ended")
        assertEquals(listOf(1L), w.folders.closed)
    }

    @Test fun backAnywhereInTheBrowseLeavesToSaveToAndEndsTheSession() {
        val w = world()
        walkToFolder(w)
        w.act(BoxAction.BACK)
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
        assertEquals(0, w.storage.openSessionCount())
        assertEquals(listOf(1L), w.folders.closed, "the connection closed with the session")
    }

    @Test fun leftOnAFolderPageGoesBackOneLevelAndTheRowOffersUp() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, if (path.isEmpty()) listOf("A") else emptyList()) }
        walkToFolder(w)
        w.press(RowId("storage.folder.item.A"))
        assertNotNull(w.rows().firstOrNull { it.id == StoragePages.ROW_F_UP }, "the Up row is the in-page way up")
        assertEquals("FOLDER_NONE_BELOW", (w.row(StoragePages.ROW_F_STATUS).label))
        // (Save-location integration, S4-Q2) ← = up one folder in place (§7.6); at the share root ← pops one page.
        val route = w.ui.route
        val depth = w.ui.stack.size
        w.act(BoxAction.LEFT)
        // The source emits `changes` (the view model rebuilds the open page); the world rebuilds it by hand.
        w.machine.dispatch(SettingsEvent.Page(w.page(w.ui.route)))
        assertEquals(route, w.ui.route, "← below the share root stays on the folder route")
        assertEquals(depth, w.ui.stack.size)
        assertEquals("FOLDER_SUBTITLE_ROOT", w.ui.page!!.subtitle, "back at the share root")
        assertNull(w.rows().firstOrNull { it.id == StoragePages.ROW_F_UP }, "at the share root there is no Up row")
        w.act(BoxAction.LEFT)
        assertTrue(w.ui.route !is SettingsRoute.NetworkFolder, "← at the share root pops the folder page")
        assertEquals(depth - 1, w.ui.stack.size)
    }

    @Test fun backOnAFolderPageFromADeepWalkLeavesWithinTwelveBacks() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, if (path.split('/').size < 8) listOf("d") else emptyList()) }
        walkToFolder(w)
        repeat(7) { w.press(RowId("storage.folder.item.d")) }
        assertEquals("d/d/d/d/d/d/d", (w.storage.build(w.ui.route, PageContext(false, 0L)).subtitle?.let { it.removePrefix("\\\\NAS\\TV\\").replace('\\', '/') }))
        var backs = 0
        while (w.ui.route != SettingsRoute.SaveLocation() && backs < 12) { w.act(BoxAction.BACK); backs++ }
        assertTrue(backs <= 1, "BACK leaves the whole browse at once ($backs)")
        repeat(12) { w.act(BoxAction.BACK) }
        assertTrue(w.effects.exits >= 0)
    }

    @Test fun aSignInIsAskedForAndAWrongPasswordClearsItAndFocusesIt() {
        val w = world()
        var attempts = 0
        w.folders.openAnswer = { a, hasPw ->
            attempts++
            when {
                a.user == null && !a.guest -> BrowseResult.NeedsSignIn()
                hasPw && attempts >= 3 -> opened(a, SignInMode.USER)
                else -> BrowseResult.NeedsSignIn(BrowseError(BrowseErrorKind.AUTH))
            }
        }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD, apple = true)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        val signIn = assertIs<SettingsRoute.ComputerSignIn>(w.ui.route)
        assertEquals(listOf(SettingsRoute.FindComputers), w.ui.stack.takeLast(1), "the sign-in replaced the share list")
        assertEquals(
            listOf("storage.signin.phone", "storage.signin.user", "storage.signin.password", "storage.signin.go", "storage.signin.guest", "storage.signin.domain", "storage.signin.info"),
            w.rows().map { it.id.key },
        )
        assertFalse(w.row(StoragePages.ROW_SI_GO).enabled, "no user name yet")
        assertEquals("SIGN_IN_GO_NEEDS_USER", w.row(StoragePages.ROW_SI_GO).disabledReason)
        w.type(StoragePages.ROW_SI_USER, "tv")
        w.type(StoragePages.ROW_SI_PASSWORD, "hunter2")
        val pw = w.row(StoragePages.ROW_SI_PASSWORD)
        assertTrue((pw.kind as RowKind.Text).masked)
        assertEquals("", pw.kind.value, "the password never sits in the page model")
        assertFalse(pw.toString().contains("hunter2"))
        w.press(StoragePages.ROW_SI_GO)
        // Wrong password: the password is cleared and focused, the reason is on top with its next step (and the Mac hint).
        assertEquals("NOT_SET", w.row(StoragePages.ROW_SI_PASSWORD).value)
        assertEquals(StoragePages.ROW_SI_PASSWORD, w.focusedRow()?.id)
        val err = w.row(StoragePages.ROW_SI_ERROR)
        assertEquals("ERR_AUTH(NAS)", err.label)
        assertEquals(listOf("ERR_AUTH_STEP", "ERR_AUTH_MAC_APPEND"), (err.kind as RowKind.Info).lines)
        assertEquals(Tone.BAD, err.kind.tone)
        assertEquals(signIn, w.ui.route)
        // The right one.
        w.type(StoragePages.ROW_SI_PASSWORD, "correct")
        w.press(StoragePages.ROW_SI_GO)
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        assertEquals(listOf(SettingsRoute.FindComputers), w.ui.stack.takeLast(1))
    }

    @Test fun noCredentialAppearsInARouteOrAToString() {
        val w = world()
        w.folders.openAnswer = { a, hasPw -> if (hasPw) opened(a, SignInMode.USER) else BrowseResult.NeedsSignIn() }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        w.type(StoragePages.ROW_SI_USER, "tv")
        w.type(StoragePages.ROW_SI_PASSWORD, "s3cret-pass")
        w.press(StoragePages.ROW_SI_GO)
        val everything = w.routes().joinToString { it.toString() } + w.ui.toString() + w.effects.intents.joinToString { it.toString() } +
            w.storage.toString() + w.rows().joinToString { it.toString() }
        assertFalse(everything.contains("s3cret-pass"), "route, state, intent and page strings carry no password")
        assertFalse(everything.contains("tv\"") && everything.contains("user=tv"))
    }

    @Test fun aSignInUserIsNeededBeforeSignInRuns() {
        val w = world()
        w.folders.openAnswer = { a, _ -> if (a.user == null && !a.guest) BrowseResult.NeedsSignIn() else opened(a, SignInMode.USER) }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        w.press(StoragePages.ROW_SI_GUEST)
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        assertTrue(w.folders.calls.any { it == "open:192.168.1.20::false" })
    }

    @Test fun noKeyboardPutsThePhoneRowFirstWithAFocusAndANote() {
        val w = world(keyboard = false)
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn() }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        assertEquals("SIGN_IN_NO_KEYBOARD", w.row(StoragePages.ROW_SI_NOKEYS).label)
        assertEquals(listOf("SIGN_IN_NO_KEYBOARD_STEP"), (w.row(StoragePages.ROW_SI_NOKEYS).kind as RowKind.Info).lines) // U-S6
        assertEquals(StoragePages.ROW_SI_PHONE, w.focusedRow()?.id)
        assertEquals("SIGN_IN_PHONE", w.row(StoragePages.ROW_SI_PHONE).label)
        assertEquals("SIGN_IN_PHONE_DESC", w.row(StoragePages.ROW_SI_PHONE).description)
    }

    @Test fun unreachableAndSmb1AndVpnTextsCarryReasonAndNextStep() {
        val w = world()
        w.folders.openAnswer = { _, _ -> BrowseResult.Failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE, vpnUp = true)) }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        val e = w.row(StoragePages.ROW_S_ERROR)
        assertEquals("ERR_UNREACHABLE(NAS,192.168.1.20)", e.label)
        assertEquals(listOf("ERR_UNREACHABLE_STEP", "ERR_VPN_APPEND"), (e.kind as RowKind.Info).lines.flatMap { it.split("\n") }.let { listOf(it[0], it[1]) })
        assertEquals("TRY_AGAIN", w.row(StoragePages.ROW_S_RETRY).label)
        w.folders.openAnswer = { a, _ -> BrowseResult.Failed(BrowseError(BrowseErrorKind.SMB1)) }
        w.press(StoragePages.ROW_S_RETRY)
        assertEquals("ERR_SMB1(NAS)", w.row(StoragePages.ROW_S_ERROR).label)
        assertEquals(listOf("ERR_SMB1_STEP"), (w.row(StoragePages.ROW_S_ERROR).kind as RowKind.Info).lines)
    }

    @Test fun sharesListsTheDiskSharesAndAlwaysOffersATypedName() {
        val w = world()
        w.folders.sharesAnswer = { ShareListing(listOf("TV", "Music")) }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        assertEquals(
            listOf("storage.share.item.TV", "storage.share.item.Music", "storage.shares.type", "storage.shares.signin"),
            w.rows().map { it.id.key },
        )
        assertEquals("SIGN_IN", w.row(StoragePages.ROW_S_SIGNIN).label, "signed in as a guest: Sign in")
        // The listing failed: say so, keep the typed name.
        w.folders.sharesAnswer = { ShareListing(emptyList(), BrowseError(BrowseErrorKind.LIST_FAILED)) }
        w.act(BoxAction.BACK)
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        assertEquals("SHARES_LIST_FAILED", w.row(StoragePages.ROW_S_STATUS).label)
        assertNotNull(w.rows().firstOrNull { it.id == StoragePages.ROW_S_TYPE })
        // A typed name is checked, then opened.
        w.type(StoragePages.ROW_S_TYPE, "Media")
        assertEquals(SettingsRoute.NetworkFolder(assertIs<SettingsRoute.NetworkFolder>(w.ui.route).browse, "Media"), w.ui.route)
    }

    // ------------------------------------------------------------------------------------------ typed address (7.2)

    @Test fun aTypedPathLandsOnTheFolderWithUseThisFolderFocused() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, listOf("x")) }
        w.open(SettingsRoute.FindComputers)
        w.type(StoragePages.ROW_ENTER, "\\\\NAS\\TV\\Rewind")
        val r = assertIs<SettingsRoute.NetworkFolder>(w.ui.route)
        assertEquals("TV", r.share)
        assertEquals("\\\\NAS\\TV\\Rewind", w.row(StoragePages.ROW_F_USE).value)
        assertEquals(StoragePages.ROW_F_USE, w.focusedRow()?.id)
        // U-N6: the shares page sits under the typed folder, so ← at the share root goes to the shares (§7.6).
        assertEquals(listOf(SettingsRoute.FindComputers, SettingsRoute.ComputerShares(r.browse)), w.ui.stack.takeLast(2))
        w.act(BoxAction.LEFT) // up to the share root, in place
        w.refresh()
        w.act(BoxAction.LEFT)
        assertEquals(SettingsRoute.ComputerShares(r.browse), w.ui.route)
    }

    @Test fun aHostOnlyLandsOnTheShares() {
        val w = world()
        w.open(SettingsRoute.FindComputers)
        w.type(StoragePages.ROW_ENTER, "nas")
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        assertEquals("nas", w.ui.page!!.title)
    }

    @Test fun aMissingFolderLandsOnTheDeepestOneThatExists() {
        val w = world()
        var calls = 0
        w.folders.foldersAnswer = { _, share, path ->
            calls++
            if (path == "Recordings/Rewind") FolderListing(share, path, emptyList(), error = BrowseError(BrowseErrorKind.NOT_FOUND, deepestPath = "Recordings"))
            else FolderListing(share, path, listOf("Other"))
        }
        w.open(SettingsRoute.FindComputers)
        w.type(StoragePages.ROW_ENTER, "nas/TV/Recordings/Rewind")
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route)
        assertEquals("\\\\nas\\TV\\Recordings", w.row(StoragePages.ROW_F_USE).value)
        // U-S6: a short reason label (the path is the subtitle) and the next step as its line.
        assertEquals("FOLDER_MISSING(Rewind)", w.row(StoragePages.ROW_F_NOTE).label)
        assertEquals(listOf("FOLDER_MISSING_STEP"), (w.row(StoragePages.ROW_F_NOTE).kind as RowKind.Info).lines)
        assertEquals(Tone.WARN, (w.row(StoragePages.ROW_F_NOTE).kind as RowKind.Info).tone)
    }

    // ------------------------------------------------------------------------------------------ the Test

    @Test fun slowVerdictsFocusChooseAnotherAndOfferUseAnyway() {
        val w = world()
        w.folders.testAnswer = { _, _, _ ->
            LocationTestResult(TestVerdict.SLOW, 2_100_000, 2_500_000, 18, 9_000, 1L shl 40, true, 0L)
        }
        walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        assertEquals(
            listOf("storage.test.verdict", "storage.test.wake", "storage.test.choose", "storage.test.anyway", "storage.test.again"),
            w.rows().map { it.id.key },
        )
        assertEquals(StoragePages.ROW_T_CHOOSE, w.focusedRow()?.id)
        assertEquals("STATUS_SLOW", w.row(StoragePages.ROW_T_VERDICT).label)
        assertEquals("TEST_WAKE_NOTE(9)", w.row(StoragePages.ROW_T_WAKE).label)
        assertEquals(listOf("TEST_WAKE_NOTE_STEP"), (w.row(StoragePages.ROW_T_WAKE).kind as RowKind.Info).lines) // U-S6
        assertEquals("TEST_USE_ANYWAY", w.row(StoragePages.ROW_T_ANYWAY).label)
        w.press(StoragePages.ROW_T_ANYWAY)
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
        assertEquals(1, w.locations.status.value.size)
        assertEquals(w.locations.status.value.single().location.id, w.locations.chosen(StoragePurpose.TIMESHIFT))
    }

    @Test fun chooseAnotherFolderGoesBackToTheFolderPage() {
        val w = world()
        w.folders.testAnswer = { _, _, _ -> LocationTestResult(TestVerdict.TOO_SLOW, 900_000, 900_000, 35, 0, 1L shl 40, true, 0L) }
        val token = walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        assertEquals("STATUS_TOO_SLOW", w.row(StoragePages.ROW_T_VERDICT).label)
        w.press(StoragePages.ROW_T_CHOOSE)
        assertEquals(SettingsRoute.NetworkFolder(token, "TV"), w.ui.route)
        assertTrue(w.locations.status.value.isEmpty(), "nothing saved")
    }

    @Test fun aSmallPoolDeviceSaysShortDropsStopRewind() {
        val w = world()
        w.storageEnv.smallPool = true
        walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        assertEquals("TEST_SMALL_POOL_NOTE", w.row(StoragePages.ROW_T_POOL).label)
    }

    @Test fun aFailedTestSaysWhyAndWhatToDo() {
        val w = world()
        w.folders.testAnswer = { _, _, _ ->
            LocationTestResult(TestVerdict.FAILED, 0, 0, 0, 0, 0, true, 0L, error = BrowseError(BrowseErrorKind.WRITE_REFUSED))
        }
        walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        val v = w.row(StoragePages.ROW_T_VERDICT)
        assertEquals("ERR_WRITE_USER", v.label)
        assertEquals(listOf("ERR_WRITE_USER_STEP(NAS)"), (v.kind as RowKind.Info).lines)
        assertEquals(StoragePages.ROW_T_CHOOSE, w.focusedRow()?.id)
        assertNull(w.rows().firstOrNull { it.id == StoragePages.ROW_T_USE }, "no Use on a failed test")
    }

    /** A Test that does not end until [gate] opens (a Test is time-bounded at 6 s in the real thing). */
    private class GatedFolders(val inner: app.thebox.core.storage.fakes.FakeNetworkFolders) : app.thebox.core.storage.NetworkFolders by inner {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun test(session: BrowseHandle, share: String, path: String, progress: (app.thebox.core.storage.TestProgress) -> Unit): LocationTestResult {
            progress(app.thebox.core.storage.TestProgress(app.thebox.core.storage.TestPhase.WRITING, 9_000_000, 16_000_000))
            gate.await()
            return inner.test(session, share, path, progress)
        }
    }

    @Test fun backWhileTheTestRunsStopsItAndSavesNothing() {
        lateinit var gated: GatedFolders
        val w = SettingsWorld(applyIntents = true, foldersWrap = { GatedFolders(it).also { g -> gated = g } })
        walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        val test = assertIs<SettingsRoute.LocationTest>(w.ui.route)
        // Running: the progress row (label from the phase, "9 of 16 MB") and one focusable Stop test.
        assertEquals(listOf(StoragePages.ROW_T_PROGRESS, StoragePages.ROW_T_STOP), w.rows().map { it.id })
        assertEquals("TEST_PHASE_WRITING", w.row(StoragePages.ROW_T_PROGRESS).label)
        assertEquals("TEST_PROGRESS_MB(9,16)", w.row(StoragePages.ROW_T_PROGRESS).value)
        assertEquals(StoragePages.ROW_T_STOP, w.focusedRow()?.id)
        // BACK = Stop test; nothing is saved, and the page says so.
        assertTrue(w.act(BoxAction.BACK))
        w.refresh()
        assertEquals(test, w.ui.route)
        assertEquals("TEST_STOPPED", w.row(StoragePages.ROW_T_VERDICT).label)
        assertEquals(StoragePages.ROW_T_AGAIN, w.focusedRow()?.id)
        assertTrue(w.locations.status.value.isEmpty())
        // A second BACK leaves the browse for Save to.
        w.act(BoxAction.BACK)
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
        gated.gate.complete(Unit)
    }

    @Test fun theStopTestRowStopsToo() {
        val w = SettingsWorld(applyIntents = true, foldersWrap = { GatedFolders(it) })
        walkToFolder(w)
        w.press(StoragePages.ROW_F_USE)
        w.press(StoragePages.ROW_T_STOP)
        assertEquals("TEST_STOPPED", w.row(StoragePages.ROW_T_VERDICT).label)
        assertTrue(w.locations.status.value.isEmpty())
    }

    // ------------------------------------------------------------------------------------------ the phone (7.4)

    @Test fun signInFromThePhoneReplacesTheQrPageWithTheShares() {
        val w = world()
        w.folders.openAnswer = { a, hasPw -> if (hasPw) opened(a, SignInMode.USER) else BrowseResult.NeedsSignIn() }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        val signIn = assertIs<SettingsRoute.ComputerSignIn>(w.ui.route)
        w.press(StoragePages.ROW_SI_PHONE)
        val phone = assertIs<SettingsRoute.PhoneSetup>(w.ui.route)
        assertEquals(PhonePurpose.NETWORK_FOLDER, phone.purpose)
        assertEquals(signIn.browse, phone.browse)
        // What the phone page is told: the host and the shares, never a credential. SEC-S3: the host is the address the
        // viewer picked; the name is for display only.
        val ctx = assertNotNull(w.storage.context(phone.browse!!))
        assertEquals("192.168.1.20", ctx.host)
        assertEquals("NAS", ctx.name)
        // The phone sends (the password is the only copy; it is zeroed once used).
        val pw = "from-phone".toCharArray()
        w.phoneLink.submit(PhoneSubmission.NetworkFolder("192.168.1.20", "TV", "Rewind", "shawn", null, pw, false))
        w.pump()
        assertTrue(pw.all { it == '\u0000' }, "the submission's password was zeroed")
        assertEquals(listOf("PHONE_SIGNED_IN(NAS)" to true), w.phoneLink.statuses, "exactly one answer per submission")
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route, "a path was given: the folder, not the share list")
        assertEquals(StoragePages.ROW_F_USE, w.focusedRow()?.id)
        assertFalse(w.routes().any { it is SettingsRoute.PhoneSetup }, "BACK never returns to the QR")
        assertFalse(w.phoneLink.statuses.joinToString().contains("from-phone"))
    }

    @Test fun aWrongPasswordFromThePhoneIsMirroredAndTheTvStaysOnTheQrPage() {
        val w = world()
        w.folders.openAnswer = { a, hasPw -> if (hasPw) BrowseResult.NeedsSignIn(BrowseError(BrowseErrorKind.AUTH)) else BrowseResult.NeedsSignIn() }
        w.folders.findStates = listOf(findState(FoundComputer("NAS", "192.168.1.20", NameSource.NSD)))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        w.press(StoragePages.ROW_SI_PHONE)
        w.phoneLink.submit(PhoneSubmission.NetworkFolder("192.168.1.20", null, null, "tv", null, "bad".toCharArray(), false))
        w.pump()
        assertIs<SettingsRoute.PhoneSetup>(w.ui.route)
        assertEquals(listOf("PHONE_REFUSED(NAS)" to false), w.phoneLink.statuses, "one failed try, one answer")
    }

    @Test fun addFromYourPhoneNeedsNoHost() {
        val w = world()
        w.open(SettingsRoute.FindComputers)
        w.press(StoragePages.ROW_PHONE_FIND)
        val phone = assertIs<SettingsRoute.PhoneSetup>(w.ui.route)
        assertEquals(PhonePurpose.NETWORK_FOLDER, phone.purpose)
        val ctx = assertNotNull(w.storage.context(phone.browse!!))
        assertNull(ctx.host)
        assertEquals(emptyList(), ctx.shares)
        assertNull(w.storage.context(999L))
    }

    @Test fun aStoredLocationsSignInFromTheTvUpdatesItAndReturnsToTheDetail() {
        val w = world(listOf(nas))
        w.folders.openAnswer = { a, hasPw -> if (hasPw) opened(a, SignInMode.USER) else BrowseResult.NeedsSignIn() }
        w.locations.setState("smb-1", LocationState.SIGN_IN_NEEDED)
        w.open(SettingsRoute.LocationDetail("smb-1"))
        w.press(StoragePages.ROW_D_SIGNIN)
        assertEquals(SettingsRoute.LocationSignIn("smb-1"), w.ui.route)
        assertEquals("tv", (w.row(StoragePages.ROW_SI_USER).kind as RowKind.Text).value, "the stored user name is the starting point")
        w.type(StoragePages.ROW_SI_PASSWORD, "new-pass")
        w.press(StoragePages.ROW_SI_GO)
        assertEquals(SettingsRoute.LocationDetail("smb-1"), w.ui.route)
        assertEquals("new-pass", w.locations.passwords["smb-1"])
    }

    // ------------------------------------------------------------------------------------------ sessions

    @Test fun aSessionEndsWhenItsLastRouteCloses() {
        val w = world()
        walkToFolder(w)
        assertEquals(1, w.storage.openSessionCount())
        val r = w.ui.route as SettingsRoute.NetworkFolder
        // Settings closes: every open route is closed; with no grace the session ends and its connection closes.
        for (route in w.routes()) w.storage.closed(route)
        assertEquals(0, w.storage.openSessionCount())
        assertTrue(w.folders.closed.isNotEmpty())
        assertEquals(r.browse, (w.ui.route as SettingsRoute.NetworkFolder).browse)
    }
}
