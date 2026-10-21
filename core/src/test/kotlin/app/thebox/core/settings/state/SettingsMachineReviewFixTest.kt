package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.EditorState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakePinGate
import app.thebox.core.settings.tree.BackupPageState
import app.thebox.core.settings.tree.BackupPages
import app.thebox.core.settings.tree.BackupRows
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.NetworkRows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings machine fixes of the M5 code review (2026-10-16); each test names its finding. */
class SettingsMachineReviewFixTest {
    private val effects = QueueEffects()
    private val gate = FakePinGate(pinSet = true)
    private val m = SettingsMachineImpl(effects, { 0L }, SettingsTexts.KEYS, HintValidator(SettingsTexts.KEYS, WpaPages.rowRules(SettingsTexts.KEYS))).also { it.pinGate = gate }

    private fun item(id: String, menu: List<ChoiceOption>, route: SettingsRoute? = null, primary: String? = null) =
        SettingsRow(RowId(id), id, kind = RowKind.Item(route = route, primary = primary, menu = menu, movable = true))

    private fun show(route: SettingsRoute, rows: List<SettingsRow>, version: Long = 1, focus: RowId? = null) =
        m.dispatch(SettingsEvent.Page(PageModel(route, "t", rows = rows, version = version, focus = focus)))

    private fun act(a: BoxAction) = m.act(GuideEvent.Act(a))

    /** C-B2: a menu option marked needsPin asks first; its intent is emitted only after PinResult(granted). */
    @Test
    fun menuOptionWithNeedsPinRunsOnlyAfterTheGrant() {
        val route = SettingsRoute.Groups(1)
        m.open(route, fromFull = false)
        show(route, listOf(item("g", listOf(ChoiceOption("show", "Show"), ChoiceOption("unlock", "Unlock", needsPin = true)))))
        act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN); act(BoxAction.SELECT)
        assertTrue(effects.intents.isEmpty(), "no intent before the PIN")
        val purpose = gate.requests.single() as PinPurpose.SettingsAction
        m.dispatch(SettingsEvent.PinResult(purpose, granted = false))
        assertTrue(effects.intents.isEmpty(), "a refused PIN runs nothing")
        act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN); act(BoxAction.SELECT)
        m.dispatch(SettingsEvent.PinResult(gate.requests.last(), granted = true))
        assertEquals(listOf<RowIntent>(RowIntent.Menu(RowId("g"), "unlock")), effects.intents)
        // An option without needsPin is not gated.
        act(BoxAction.CONTEXT_MENU); act(BoxAction.SELECT)
        assertEquals(RowIntent.Menu(RowId("g"), "show"), effects.intents.last())
        assertEquals(2, gate.requests.size)
    }

    /** C-S4 (§3.15 d): OK on an item whose actions are all in its menu opens the menu (no long OK needed). */
    @Test
    fun okOnAMenuOnlyItemOpensTheMenu() {
        val route = SettingsRoute.HideRules(1)
        m.open(route, fromFull = false)
        show(route, listOf(item("rule/1", listOf(ChoiceOption("disable", "Disable"), ChoiceOption("delete", "Delete")))))
        act(BoxAction.SELECT)
        assertEquals(listOf("disable", "delete"), m.ui.value.menu?.options?.map { it.id })
        assertTrue(effects.intents.isEmpty())
    }

    /** C-S5 (§3.2.3): ADVANCED rows are absent in the simple view, except the route's own focus row (error-card routes). */
    @Test
    fun advancedRowsAbsentInSimpleViewExceptTheRouteFocus() {
        val focus = RowId("playlist.user_agent")
        val route = SettingsRoute.Playlist(3, focus = focus)
        m.open(route, fromFull = false)
        val rows = listOf(
            SettingsRow(RowId("playlist.name"), "Name", kind = RowKind.Text("x", TextHint.NAME)),
            SettingsRow(RowId("playlist.stream_format"), "Format", kind = RowKind.Info(emptyList()), visibility = Visibility.ADVANCED),
            SettingsRow(focus, "UA", kind = RowKind.Info(emptyList()), visibility = Visibility.ADVANCED),
        )
        show(route, rows)
        assertEquals(listOf("playlist.name", "playlist.user_agent"), m.ui.value.page?.rows?.map { it.id.key })
        assertEquals(1, m.ui.value.cursor, "the route's focus row is focused")
        m.dispatch(SettingsEvent.Advanced(true))
        show(route, rows, version = 2)
        assertEquals(3, m.ui.value.page?.rows?.size, "the advanced view shows every row")
    }

    /** C-L5: BACK in move mode puts the row back and the cursor with it. */
    @Test
    fun moveModeBackRestoresTheCursor() {
        val route = SettingsRoute.FavouriteLists
        m.open(route, fromFull = false)
        val menu = listOf(ChoiceOption(SettingsMachineImpl.MOVE_OPTION, "Move"))
        show(route, (1..4).map { item("l$it", menu) })
        act(BoxAction.CONTEXT_MENU); act(BoxAction.SELECT)
        assertNotNull(m.ui.value.move)
        act(BoxAction.DOWN); act(BoxAction.DOWN)
        assertEquals(2, m.ui.value.cursor)
        act(BoxAction.BACK)
        assertNull(m.ui.value.move)
        assertEquals(0, m.ui.value.cursor, "the cursor returns with the row")
        assertTrue(effects.intents.isEmpty())
    }

    /** C-L6: a route's focus row wins over the cursor memory of an earlier visit (the second error-card visit). */
    @Test
    fun aRouteFocusWinsOverCursorMemory() {
        val rows = (0..5).map { SettingsRow(RowId("r$it"), "r$it", kind = RowKind.Toggle(false)) }
        val route = SettingsRoute.Playlist(3, focus = RowId("r1"))
        m.open(route, fromFull = true, detached = true)
        show(route, rows)
        assertEquals(1, m.ui.value.cursor)
        repeat(3) { act(BoxAction.DOWN) }
        m.push(SettingsRoute.PlaylistSource(3)) // the cursor (row r4) is remembered for the route
        m.onLeave()
        m.open(route, fromFull = true, detached = true)
        show(route, rows)
        assertEquals(1, m.ui.value.cursor, "the second visit lands on the route's row, not the remembered one")
    }

    /** U-S3: a page that lands after Settings closed (a slow build) is dropped. */
    @Test
    fun aPageAfterLeaveIsDropped() {
        m.open(SettingsRoute.Root, fromFull = false)
        m.onLeave()
        show(SettingsRoute.Section(SectionId.PLAYLISTS), listOf(SettingsRow(RowId("x"), "x", kind = RowKind.Toggle(false))))
        assertNull(m.ui.value.page, "no page published after onLeave")
    }

    /** U-S6 (S2): a page source's own navigation into a gated route asks for the PIN first. */
    @Test
    fun pushGatedAsksForThePin() {
        m.open(SettingsRoute.SyncStatus, fromFull = true, detached = true)
        m.pushGated(SettingsRoute.PlaylistSource(3))
        assertEquals(SettingsRoute.SyncStatus, m.ui.value.route, "nothing pushed before the PIN")
        m.dispatch(SettingsEvent.PinResult(gate.requests.single(), granted = true))
        assertEquals(SettingsRoute.PlaylistSource(3), m.ui.value.route)
    }

    /** C-S1: openFromFull hands a detached route to the guide's ungated event and does not open the route twice. */
    @Test
    fun openFromFullSendsTheDetachedEventOnly() {
        val sent = ArrayList<GuideEvent>()
        m.attachGuide { sent += it }
        m.openFromFull(SettingsRoute.PlaylistSource(3), detached = true)
        assertEquals(listOf<GuideEvent>(GuideEvent.OpenSettingsDetached(SettingsRoute.PlaylistSource(3))), sent)
        assertTrue(effects.loads.isEmpty(), "the guide's event opens the machine, not openFromFull")
    }

    /** C-S7 (§3.3.9 "remove"): with a stored passphrase, its own destructive row removes it after the confirm. */
    @Test
    fun removePassphraseRowConfirmsThenRuns() {
        val t = BackupTexts { k, _ -> k.name }
        m.open(SettingsRoute.Backup, fromFull = false)
        m.dispatch(SettingsEvent.Advanced(true))
        val page = BackupPages.backup(BackupPageState(passphraseStored = true), t, advanced = true, version = 1)
        m.dispatch(SettingsEvent.Page(page))
        val i = page.rows.indexOfFirst { it.id == BackupRows.PASSPHRASE_REMOVE }
        assertTrue(i >= 0, "a Remove row while a passphrase is stored")
        repeat(i) { act(BoxAction.DOWN) }
        act(BoxAction.SELECT)
        assertNotNull(m.ui.value.confirm)
        act(BoxAction.LEFT); act(BoxAction.SELECT)
        assertEquals(listOf<RowIntent>(RowIntent.Run(BackupRows.PASSPHRASE_REMOVE)), effects.intents)
        assertFalse(BackupPages.backup(BackupPageState(passphraseStored = false), t, true, 2).rows.any { it.id == BackupRows.PASSPHRASE_REMOVE })
    }

    /** C-L7: a pattern is compiled only in its `/regex/` form; Contains text with "(" and "" (clear the prefix) are valid. */
    @Test
    fun patternsAreValidatedByTheirMode() {
        val v = HintValidator(SettingsTexts.KEYS)
        val row = RowId("rules.pattern")
        assertNull(v.validate(row, TextHint.PATTERN, "Sports (HD"))
        assertNull(v.validate(row, TextHint.PATTERN, ""))
        assertNull(v.validate(row, TextHint.PATTERN, "/^UK: /"))
        assertEquals(SettingsText.ERR_PATTERN.name, v.validate(row, TextHint.PATTERN, "/(unclosed/"))
        assertEquals(SettingsText.ERR_PATTERN.name, v.validate(row, TextHint.PATTERN, "/(?<=UK):/"))
        assertEquals(SettingsText.ERR_PATTERN.name, v.validate(row, TextHint.PATTERN, "/(UK)\\1/"))
        assertNull(v.validate(row, TextHint.PATTERN, "/(?:UK|US):\\s*/"))
    }

    /** C-L8: the custom DoH row accepts exactly what BoxDnsPolicy will use (no userinfo, no bracketed host). */
    @Test
    fun customDohValidatorMatchesThePolicy() {
        val v = HintValidator(SettingsTexts.KEYS, WpaPages.rowRules(SettingsTexts.KEYS))
        assertNull(v.validate(NetworkRows.CUSTOM_DOH, TextHint.URL, "https://dns.example/dns-query"))
        // Both pass the generic https check, and BoxDnsPolicy would ignore both (userinfo, a host it cannot use).
        assertEquals(SettingsText.ERR_HTTPS.name, v.validate(NetworkRows.CUSTOM_DOH, TextHint.URL, "https://user@dns.example/dns-query"))
        assertEquals(SettingsText.ERR_HTTPS.name, v.validate(NetworkRows.CUSTOM_DOH, TextHint.URL, "https://dns_resolver.example/dns-query"))
    }

    /** U-L10 / C-L14: masked values never print in state dumps or assertion messages. */
    @Test
    fun maskedValuesNeverPrint() {
        val secret = "fake-secret-value"
        val printed = listOf(
            RowKind.Text(secret, TextHint.PASSWORD, masked = true).toString(),
            EditorState(RowId("p"), "Password", secret, TextHint.PASSWORD, masked = true, chips = emptyList()).toString(),
            RowIntent.Edit(RowId("p"), secret).toString(),
            SettingsEvent.EditorClosed(RowId("p"), secret, committed = true).toString(),
        )
        for (p in printed) assertFalse(secret in p, p)
        assertTrue("visible" in RowKind.Text("visible", TextHint.NAME).toString(), "unmasked text still prints")
    }

    /** C-L2: no `capture:` row path: an Action row's Run goes to its source even with that id shape. */
    @Test
    fun captureRowsAreOrdinaryActions() {
        m.open(SettingsRoute.ButtonMapping, fromFull = false)
        show(SettingsRoute.ButtonMapping, listOf(SettingsRow(RowId("capture:open_search:SHORT"), "Bind", kind = RowKind.Action())))
        act(BoxAction.SELECT)
        assertNull(m.ui.value.capture)
        assertEquals(listOf<RowIntent>(RowIntent.Run(RowId("capture:open_search:SHORT"))), effects.intents)
    }
}
