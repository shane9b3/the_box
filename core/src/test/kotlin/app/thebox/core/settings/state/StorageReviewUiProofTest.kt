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
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.fakes.FakeNetworkFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests of review U (docs/review/2026-10-24-storage-locations-review-ui.md), one or more per finding, named
 * after it. They started as the review's proof tests (each failed on c727b1d) and pass after the fix pass, UI half.
 */
class StorageReviewUiProofTest {
    private fun world(foldersWrap: (FakeNetworkFolders) -> NetworkFolders = { it }): SettingsWorld =
        SettingsWorld(applyIntents = true, foldersWrap = foldersWrap)
    private fun SettingsWorld.rows(): List<SettingsRow> = ui.page!!.rows
    private fun SettingsWorld.row(id: RowId): SettingsRow = assertNotNull(rows().firstOrNull { it.id == id }, "row ${id.key}: ${rows().map { it.id.key }}")
    private fun SettingsWorld.refresh() { ui.page?.route?.let { effects.loads += it }; pump() }
    private fun SettingsWorld.press(id: RowId) { focus(id); act(BoxAction.SELECT) }
    private fun SettingsWorld.type(id: RowId, text: String) {
        focus(id); act(BoxAction.SELECT)
        machine.dispatch(SettingsEvent.EditorClosed(ui.editor!!.row, text, committed = true)); pump()
    }
    private fun found(vararg c: FoundComputer, searching: Boolean = true) = FindState(c.toList(), searching, "192.168.1.x", false, null)
    private val nasFound = FoundComputer("NAS", "192.168.1.20", NameSource.NSD)
    private fun SettingsWorld.openNas() {
        folders.findStates = listOf(found(nasFound, searching = false))
        open(SettingsRoute.FindComputers)
        press(RowId("storage.find.pc.192.168.1.20"))
    }
    private fun share(name: String) = RowId(StoragePages.SHARE_ITEM + name)
    private fun folder(name: String) = RowId(StoragePages.FOLDER_ITEM + name)

    /** A finder whose results the test sets while the page collects it (the real one is a live flow). */
    private class LiveFinder(val inner: FakeNetworkFolders) : NetworkFolders by inner {
        val state = MutableStateFlow(FindState(emptyList(), true, "192.168.1.x", false, null))
        override fun find(): Flow<FindState> { inner.calls += "find"; return state }
    }

    /** A typed share whose answer waits for [gate]. */
    private class GatedShare(val inner: FakeNetworkFolders) : NetworkFolders by inner {
        val gate = CompletableDeferred<Unit>()
        override suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing {
            if (share == "Late") gate.await()
            return inner.folders(session, share, path)
        }
    }

    // ------------------------------------------------------------------------------------------ U-B1

    private fun mistypedWorld(): SettingsWorld = world().also { w ->
        w.folders.sharesAnswer = { ShareListing(listOf("TV")) }
        w.folders.foldersAnswer = { _, share, path ->
            if (share == "Nope") FolderListing(share, path, emptyList(), error = BrowseError(BrowseErrorKind.NOT_FOUND))
            else FolderListing(share, path, listOf("Rewind"))
        }
    }

    /** U-B1: a mistyped share name says why and what to do, on the shares page, above the row to type it again. */
    @Test fun uB1_aMistypedShareNameSaysWhyAndDoesNotPoisonTheNextShare() {
        val w = mistypedWorld()
        w.openNas()
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        w.type(StoragePages.ROW_S_TYPE, "Nope")
        w.refresh()
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        val note = w.row(StoragePages.ROW_S_NOTE)
        assertEquals("SHARE_NOT_FOUND(Nope,NAS)", note.label)
        assertEquals(RowKind.Info(listOf("SHARE_NOT_FOUND_STEP"), app.thebox.core.settings.Tone.WARN), note.kind)
        val ids = w.rows().map { it.id }
        assertEquals(ids.indexOf(StoragePages.ROW_S_TYPE) - 1, ids.indexOf(StoragePages.ROW_S_NOTE), "the reason sits above the Type row")
        // Then the listed share opens cleanly.
        w.press(share("TV"))
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route)
        assertNull(w.rows().firstOrNull { it.id == StoragePages.ROW_F_ERROR }, "stale typed-share error on a good share: ${w.rows().map { it.label }}")
        assertTrue(w.row(StoragePages.ROW_F_USE).enabled, "Use this folder disabled by a stale error")
    }

    /** U-B1 (second half): after the mistyped name, a listed share opens with its folders and Use enabled. */
    @Test fun uB1b_aListedShareAfterAMistypedNameOpensCleanly() {
        val w = mistypedWorld()
        w.openNas()
        w.type(StoragePages.ROW_S_TYPE, "Nope")
        w.refresh()
        w.press(share("TV"))
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route)
        assertEquals(listOf("storage.folder.use", "storage.folder.item.Rewind"), w.rows().map { it.id.key }, "rows: ${w.rows().map { it.label }}")
        assertTrue(w.row(StoragePages.ROW_F_USE).enabled)
    }

    /** U-B1: the typed share's job is tracked: BACK before the answer ends it, and a late answer pushes nothing. */
    @Test fun uB1c_backBeforeTheTypedShareAnswersPushesNothing() {
        lateinit var g: GatedShare
        val w = world { GatedShare(it).also { x -> g = x } }
        w.openNas()
        w.type(StoragePages.ROW_S_TYPE, "Late")
        assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        w.act(BoxAction.BACK)
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
        g.gate.complete(Unit)
        w.pump()
        assertEquals(SettingsRoute.SaveLocation(), w.ui.route)
        assertTrue((w.ui.stack + w.ui.route).none { it is SettingsRoute.NetworkFolder }, "a late typed share pushed a folder: ${w.ui.stack}")
        assertEquals(0, w.storage.openSessionCount())
    }

    /** U-B1: a folder list that fails reads as a reason and a next step, not "Something went wrong". */
    @Test fun uB1d_aFailedFolderListSaysWhyAndWhatToDo() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, emptyList(), error = BrowseError(BrowseErrorKind.LIST_FAILED)) }
        w.openNas()
        w.type(StoragePages.ROW_S_TYPE, "Media")
        w.refresh()
        assertEquals("SHARE_LIST_FAILED(NAS)", w.row(StoragePages.ROW_S_NOTE).label)
        assertEquals(listOf("SHARE_LIST_FAILED_STEP"), (w.row(StoragePages.ROW_S_NOTE).kind as RowKind.Info).lines)
        // A share list that fails points at the typed name (the way through, §7.5).
        val w2 = world().also { it.folders.sharesAnswer = { ShareListing(emptyList(), BrowseError(BrowseErrorKind.LIST_FAILED)) } }
        w2.openNas()
        assertEquals(listOf("SHARES_LIST_FAILED_STEP"), (w2.row(StoragePages.ROW_S_STATUS).kind as RowKind.Info).lines)
    }

    // ------------------------------------------------------------------------------------------ U-S1

    /** U-S1: a folder named like a fixed row ("use", "up") has its own id, and OK on it enters it. */
    @Test fun uS1_aFolderNamedUseIsAFolderNotTheUseButton() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, if (path.isEmpty()) listOf("use", "up") else emptyList()) }
        w.openNas()
        w.press(share("TV"))
        val keys = w.rows().map { it.id.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate row ids on the folder page: $keys")
        w.press(folder("use"))
        assertIs<SettingsRoute.NetworkFolder>(w.ui.route, "OK on the folder \"use\" must not start the Test")
        assertEquals("\\\\NAS\\TV\\use", w.ui.page!!.subtitle)
    }

    // ------------------------------------------------------------------------------------------ U-S2

    /** U-S2: once the viewer moved, a later computer that sorts first does not take the cursor. */
    @Test fun uS2_findFocusDoesNotJumpAfterTheViewerMoved() {
        lateinit var f: LiveFinder
        val w = world { LiveFinder(it).also { x -> f = x } }
        f.state.value = found(FoundComputer(null, "192.168.1.50", NameSource.NONE))
        w.open(SettingsRoute.FindComputers)
        assertEquals("storage.find.pc.192.168.1.50", w.focusedRow()?.id?.key)
        w.focus(StoragePages.ROW_ENTER) // the viewer heads for "Enter a name or address"
        // The NetBIOS name of another computer arrives; it sorts first.
        f.state.value = found(FoundComputer(null, "192.168.1.50", NameSource.NONE), FoundComputer("DESKTOP-1", "192.168.1.30", NameSource.NETBIOS))
        w.refresh()
        assertEquals("storage.find.pc.192.168.1.30", w.rows()[1].id.key)
        assertEquals(StoragePages.ROW_ENTER, w.focusedRow()?.id, "focus was taken from the row the viewer chose")
    }

    /** U-S2: a key pressed before the first computer arrives keeps the cursor where the viewer put it (§9.2). */
    @Test fun uS2c_aKeyBeforeTheFirstComputerKeepsTheCursor() {
        lateinit var f: LiveFinder
        val w = world { LiveFinder(it).also { x -> f = x } }
        w.open(SettingsRoute.FindComputers)
        assertEquals(StoragePages.ROW_ENTER, w.focusedRow()?.id)
        w.act(BoxAction.DOWN)
        assertEquals(StoragePages.ROW_PHONE_FIND, w.focusedRow()?.id)
        f.state.value = found(nasFound)
        w.refresh()
        assertEquals(StoragePages.ROW_PHONE_FIND, w.focusedRow()?.id)
    }

    /** U-S2 (second case): after a wrong password, typing the new one moves the cursor to Sign in, never up to User name. */
    @Test fun uS2b_typingTheNewPasswordMovesTheCursorToSignIn() {
        val w = world()
        w.folders.openAnswer = { a, _ -> if (a.user == null) BrowseResult.NeedsSignIn() else BrowseResult.NeedsSignIn(BrowseError(BrowseErrorKind.AUTH)) }
        w.openNas()
        w.type(StoragePages.ROW_SI_USER, "tv")
        w.type(StoragePages.ROW_SI_PASSWORD, "wrong")
        w.press(StoragePages.ROW_SI_GO)
        assertEquals(StoragePages.ROW_SI_PASSWORD, w.focusedRow()?.id)
        // The viewer is on Password: OK, type, Done (no explicit move).
        w.act(BoxAction.SELECT)
        w.machine.dispatch(SettingsEvent.EditorClosed(w.ui.editor!!.row, "right", committed = true)); w.pump()
        assertEquals(StoragePages.ROW_SI_GO, w.focusedRow()?.id, "the cursor jumped after the password was typed")
        // A second wrong password focuses Password again (the request changed back after the submit).
        w.act(BoxAction.SELECT)
        assertEquals(StoragePages.ROW_SI_PASSWORD, w.focusedRow()?.id)
    }

    // ------------------------------------------------------------------------------------------ U-S3, U-S4

    /** U-S3: "Use as guest" stays in its place but is disabled, with the reason, after the computer refused guests. */
    @Test fun uS3_useAsGuestIsNotOfferedAfterGuestsWereRefused() {
        val w = world()
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn(BrowseError(BrowseErrorKind.GUEST_REFUSED)) }
        w.openNas()
        assertIs<SettingsRoute.ComputerSignIn>(w.ui.route)
        val guest = w.row(StoragePages.ROW_SI_GUEST)
        assertFalse(guest.enabled, "Use as guest after GUEST_REFUSED")
        assertEquals("ERR_GUEST(NAS)", guest.disabledReason)
        assertEquals(StoragePages.ROW_SI_USER, w.focusedRow()?.id)
    }

    /** U-S3: when guests were never tried, the row is offered as before. */
    @Test fun uS3b_useAsGuestIsOfferedWhenGuestsWereNotRefused() {
        val w = world()
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn() }
        w.openNas()
        assertTrue(w.row(StoragePages.ROW_SI_GUEST).enabled)
    }

    /** U-S4: "Workgroup or domain" ("Leave empty unless you know you need it") can be emptied again. */
    @Test fun uS4_theDomainCanBeCleared() {
        val w = world()
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn() }
        w.openNas()
        w.type(StoragePages.ROW_SI_DOMAIN, "WORKGROUP")
        assertEquals("WORKGROUP", w.row(StoragePages.ROW_SI_DOMAIN).value)
        w.type(StoragePages.ROW_SI_DOMAIN, "")
        assertNull(w.ui.editor?.error, "the editor refused an empty domain: ${w.ui.editor?.error}")
        assertEquals("NOT_SET", w.row(StoragePages.ROW_SI_DOMAIN).value)
    }

    // ------------------------------------------------------------------------------------------ U-S5

    private val nas = StorageLocation(
        "smb-1", StorageKind.SMB, "NAS", smb = SmbAddress("nas", 445, "TV", "Rewind", user = "tv"),
        lastTest = LocationTestResult(TestVerdict.GOOD_4K, 10_500_000, 11_000_000, 4, 0, 1L shl 40, true, 0L),
    )
    private val tooSlow = LocationTestResult(TestVerdict.TOO_SLOW, 900_000, 900_000, 35, 0, 1L shl 40, true, 5_000L)

    /** U-S5: a stored folder's Test is written to lastTest (Status shows the new verdict and date). */
    @Test fun uS5_aStoredFoldersTestUpdatesItsStatus() {
        val w = world()
        runBlocking { w.locations.saveSmb(nas.smb!!, nas.name, null, nas.lastTest) }
        val id = w.locations.status.value.single().location.id
        w.folders.openAnswer = { a, _ -> BrowseResult.Opened(BrowseHandle(9, a, SignInMode.USER)) }
        w.folders.testAnswer = { _, _, _ -> tooSlow }
        w.open(SettingsRoute.LocationDetail(id))
        w.press(StoragePages.ROW_D_TEST)
        assertIs<SettingsRoute.LocationTest>(w.ui.route)
        assertNotNull(w.rows().firstOrNull { it.id == StoragePages.ROW_T_VERDICT })
        assertEquals(tooSlow, w.locations.status.value.single().location.lastTest, "lastTest not updated by the Test")
    }

    /** U-S5: a new walk to a folder that is already stored updates that folder's lastTest. */
    @Test fun uS5b_aWalkToAStoredFolderStoresItsTest() {
        val w = world()
        runBlocking { w.locations.saveSmb(SmbAddress("192.168.1.20", 445, "TV", "", null, null, true, "192.168.1.20"), "NAS", null, nas.lastTest) }
        w.folders.testAnswer = { _, _, _ -> tooSlow }
        w.openNas()
        w.press(share("TV"))
        w.press(StoragePages.ROW_F_USE)
        assertIs<SettingsRoute.LocationTest>(w.ui.route)
        w.press(StoragePages.ROW_T_ANYWAY)
        assertEquals(tooSlow, w.locations.status.value.single().location.lastTest)
    }

    // ------------------------------------------------------------------------------------------ U-S8

    /** U-S8: → on a folder opens it and → on ".. Up one folder" goes up (§9.3 "OK (or →)"). */
    @Test fun uS8_rightOnAFolderOpensIt() {
        val w = world()
        w.folders.foldersAnswer = { _, share, path -> FolderListing(share, path, if (path.isEmpty()) listOf("Recordings") else emptyList()) }
        w.openNas()
        w.press(share("TV"))
        val route = w.ui.route
        w.focus(folder("Recordings"))
        w.act(BoxAction.RIGHT)
        w.refresh() // the source's `changes` rebuild the open page
        assertEquals(route, w.ui.route)
        assertEquals("\\\\NAS\\TV\\Recordings", w.ui.page!!.subtitle)
        w.focus(StoragePages.ROW_F_UP)
        w.act(BoxAction.RIGHT)
        w.refresh()
        assertEquals("FOLDER_SUBTITLE_ROOT", w.ui.page!!.subtitle)
    }

    /** U-S8: → on Save to's "This device" (a primary Item) does not choose it. */
    @Test fun uS8b_rightOnThisDeviceDoesNotChooseIt() {
        val w = world()
        runBlocking { w.locations.saveSmb(nas.smb!!, nas.name, null, nas.lastTest) }
        val id = w.locations.status.value.single().location.id
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, id) }
        w.open(SettingsRoute.SaveLocation())
        w.focus(StoragePages.ROW_DEVICE)
        w.act(BoxAction.RIGHT)
        assertEquals(id, w.locations.chosen(StoragePurpose.TIMESHIFT))
    }

    // ------------------------------------------------------------------------------------------ nice-to-haves

    /** U-N2: a folder that needs a sign-in is not "offline"; a demoted one "kept dropping out". */
    @Test fun uN2_signInAndDemotionAreNotOffline() {
        val w = world()
        runBlocking { w.locations.saveSmb(nas.smb!!, nas.name, null, nas.lastTest) }
        val id = w.locations.status.value.single().location.id
        runBlocking { w.locations.choose(StoragePurpose.TIMESHIFT, id) }
        w.locations.setState(id, LocationState.SIGN_IN_NEEDED)
        w.open(SettingsRoute.Section(SectionId.PLAYBACK))
        val v = w.row(PlaybackRows.PAUSE_REWIND).value!!
        assertTrue(StorageMarks.isWarn(v))
        assertEquals("PB_PAUSE_REWIND_VALUE_SIGN_IN(TS_AUTO(TS_HOUR),NAS)", StorageMarks.plain(v))
        w.locations.setState(id, LocationState.DEMOTED)
        w.open(SettingsRoute.PauseRewind)
        assertEquals("STATUS_DEMOTED(NAS)", StorageMarks.plain(w.row(PauseRewindRows.TIMESHIFT_LOCATION).description!!))
    }

    /** U-N9: the replace by the sign-in page is decided on the main thread, after the posted push of the shares page. */
    @Test fun uN9_aFastSignInRefusalStillReplacesTheShares() {
        val w = world()
        w.storageEnv.deferMain = true
        w.folders.openAnswer = { _, _ -> BrowseResult.NeedsSignIn() }
        w.folders.findStates = listOf(found(nasFound, searching = false))
        w.open(SettingsRoute.FindComputers)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        // The connect answered before the main thread ran the push; both are queued for it, in order.
        assertEquals(SettingsRoute.FindComputers, w.ui.route)
        w.storageEnv.runMain(); w.pump()
        assertIs<SettingsRoute.ComputerSignIn>(w.ui.route)
    }

    /** U-N10: the finders stop while a browse page is shown over Find, and resume, with the last results, on the way back. */
    @Test fun uN10_findIsPausedUnderTheBrowse() {
        lateinit var f: LiveFinder
        val w = world { LiveFinder(it).also { x -> f = x } }
        f.state.value = found(nasFound)
        w.open(SettingsRoute.FindComputers)
        assertEquals(1, f.state.subscriptionCount.value)
        w.press(RowId("storage.find.pc.192.168.1.20"))
        val shares = assertIs<SettingsRoute.ComputerShares>(w.ui.route)
        assertEquals(0, f.state.subscriptionCount.value, "the multicast lock and NSD are held while Find is not shown")
        w.act(BoxAction.LEFT)
        assertEquals(SettingsRoute.FindComputers, w.ui.route)
        // The shell tells the source the shares page closed; its `changes` rebuild the open page (the world does it by hand).
        w.storage.closed(shares)
        w.refresh()
        assertEquals(1, f.state.subscriptionCount.value)
        assertNotNull(w.rows().firstOrNull { it.id.key == "storage.find.pc.192.168.1.20" })
    }
}
