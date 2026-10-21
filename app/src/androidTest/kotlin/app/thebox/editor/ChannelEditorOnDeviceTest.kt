package app.thebox.editor

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.editor.ChannelEditorUiState
import app.thebox.core.editor.EditorAction
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorPane
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorWindow
import app.thebox.core.editor.Selection
import app.thebox.core.guide.GroupMenuItem
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.settings.SettingsTestBase
import app.thebox.ui.editor.ChannelEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M6-CE-1…8 (docs/design/m6-channel-editor.md §6.3): the channel editor on the API 30 TV emulator against the fake
 * panel with 2,000 synthetic channels (`python3 tools/fakepanel/fakepanel.py --port 8080 --synthetic 2000`; 40 groups of
 * 50). Keys go through the real router; the editor's state is read from the activity's `ChannelEditorViewModel`, the
 * guide's lineups through `SettingsDebugProbe.library`. Each test starts from wiped data and a fresh import.
 */
@RunWith(AndroidJUnit4::class)
class ChannelEditorOnDeviceTest : SettingsTestBase() {

    private var pid = 0L

    private fun fresh(adult: Boolean = false): Long {
        wipe()
        if (adult) panel.adultCategory(true)
        assertTrue("the panel must run with --synthetic 2000", panel.statsJson().optJSONObject("synthetic") != null)
        pid = seedXtream(timeoutMs = 600_000)
        return pid
    }

    // ------------------------------------------------------------------ editor state

    private fun editorVm(): ChannelEditorViewModel {
        var vm: ChannelEditorViewModel? = null
        scenario!!.onActivity { vm = it.channelEditor }
        return vm!!
    }

    private fun eui(): ChannelEditorUiState = editorVm().machine.ui.value

    private fun awaitEditor(what: String, timeoutMs: Long = 15_000, p: (ChannelEditorUiState) -> Boolean): ChannelEditorUiState {
        val t0 = SystemClock.elapsedRealtime()
        val m = editorVm().machine
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { m.ui.first(p) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; editor=${brief(m.ui.value)}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
        return st
    }

    private fun brief(u: ChannelEditorUiState) =
        "pane=${u.pane} toolbar=${u.toolbar} gCursor=${u.groups.cursor} groups=${u.groups.groups?.count} list=${u.list} filter=${u.filter} " +
            "channels=${u.channels?.count} cCursor=${u.channels?.cursor} sel=${u.channelSelection} gsel=${u.groupSelection} menu=${u.menu?.title}/${u.menu?.lines?.size}/${u.menu?.loading} " +
            "picker=${u.picker} move=${u.move != null} confirm=${u.confirm?.kind} empty=${u.empty} locked=${u.lockedList} out=${u.lockedOut} saving=${u.pendingWrites} notice=${u.notice}"

    private fun <T> rowOf(w: EditorWindow<T>?, i: Int): T? = w?.pages?.get(i / EditorWindow.PAGE)?.getOrNull(i % EditorWindow.PAGE)

    /** Settings › Channels and groups › Channel editor (its first row), as a user gets there. */
    private fun openEditorFromSettings() {
        openSettings("channels")
        awaitSettings("the editor row") { s -> rows(s).any { it.id == RowId("channels.editor") } }
        activate("channels.editor")
        awaitSettings("editor route") { it.route is SettingsRoute.ChannelEditor }
        awaitEditor("groups and the first group's channels") { u ->
            (u.groups.groups?.count ?: 0) > 0 && u.list is EditorList.Group && (u.channels?.count ?: 0) > 0 && rowOf(u.channels, 0) != null
        }
    }

    private fun enterChannels() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitEditor("channels pane") { it.pane == EditorPane.CHANNELS && it.toolbar == null }
    }

    /** Opens the channel menu (→) and waits for its lines (the summary for ticked rows arrives from IO). */
    private fun openChannelMenu(): ChannelEditorUiState {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        return awaitEditor("channel menu with lines") { it.menu?.loading == false && it.menu?.lines?.isNotEmpty() == true }
    }

    /** Moves the open menu's focus to [action] and presses OK. */
    private fun runLine(action: EditorAction) {
        val lines = eui().menu?.lines ?: error("no menu")
        val i = lines.indexOfFirst { it.action == action }
        assertTrue("menu has $action: ${lines.map { it.action }}", i >= 0)
        assertTrue("$action is enabled (${lines[i].reason})", lines[i].enabled)
        while ((eui().menu?.focused ?: i) < i) press(KeyEvent.KEYCODE_DPAD_DOWN, intervalMs = 80)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun awaitSaved(what: String) = awaitEditor("$what saved", 20_000) { it.pendingWrites == 0 && it.menu == null }

    private fun lineup(source: GuideSource, access: ParentalAccess = ParentalAccess.OPEN): LongArray = runBlocking {
        withTimeoutOrNull(10_000) { probe.library.lineup(source, access).first() }!!.rowIds
    }

    private fun awaitLineup(what: String, source: GuideSource, timeoutMs: Long = 20_000, p: (LongArray) -> Boolean): LongArray {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = LongArray(0)
        while (SystemClock.elapsedRealtime() < deadline) {
            last = lineup(source)
            if (p(last)) return last
            Thread.sleep(300)
        }
        fail("guide lineup: timed out waiting for $what (size ${last.size})")
        error("unreachable")
    }

    private fun guideGroupKeys(): List<String> = runBlocking {
        withTimeoutOrNull(10_000) { probe.library.sources(ParentalAccess.OPEN).first() }!!
            .filter { it.section == SourceSection.GROUP }.mapNotNull { (it.source as? GuideSource.Group)?.groupKey }
    }

    private fun stableKeysOf(rowIds: LongArray): List<String> = runBlocking {
        probe.library.channels(rowIds, 0, rowIds.size).mapNotNull { it?.identity?.stableKey?.value }
    }

    // ------------------------------------------------------------------ I-M6-CE-1

    /** I-M6-CE-1: open from Settings (Groups pane, first group followed) and from the guide's group menu (that group, channels pane). */
    @Test
    fun opensFromSettingsAndFromTheGroupMenu() {
        fresh()
        launch()
        openEditorFromSettings()
        val u = eui()
        assertEquals(SettingsRoute.ChannelEditor(pid), sui().route)
        assertEquals(EditorPane.GROUPS, u.pane)
        assertEquals("the cursor starts on the first real group (row 0 is All channels)", 1, u.groups.cursor)
        assertEquals(EditorList.All, u.groups.head.first())
        assertEquals(40, u.groups.groups!!.count)
        assertEquals(50, u.channels!!.count)
        assertTrue("the breadcrumb names the editor", sui().page?.title?.startsWith("Channel editor") == true)
        leaveSettings()

        // Guide › Groups panel › long OK on a group › Edit channels…
        onMain { guide.dispatch(GuideEvent.Act(BoxAction.GROUPS_PANEL)) }
        val g = awaitGui("Groups panel") { it.overlay is Overlay.Groups }.overlay as Overlay.Groups
        val entry = g.entries.withIndex().filter { it.value.section == SourceSection.GROUP }.drop(2).first()
        val target = g.shortcuts.size + entry.index
        var guard = 0
        while ((gui().overlay as? Overlay.Groups)?.focused != target && guard++ < 80) {
            press(if (((gui().overlay as Overlay.Groups).focused) < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 80)
        }
        longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        val menu = awaitGui("group menu") { (it.overlay as? Overlay.Groups)?.menu != null }.overlay as Overlay.Groups
        val edit = menu.menu!!.indexOf(GroupMenuItem.EDIT)
        assertEquals("Edit channels… is the last item", menu.menu!!.size - 1, edit)
        while (((gui().overlay as? Overlay.Groups)?.menuFocused ?: edit) < edit) press(KeyEvent.KEYCODE_DPAD_DOWN, intervalMs = 80)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitGui("SETTINGS") { it.mode == ScreenMode.SETTINGS }
        val key = (entry.value.source as GuideSource.Group).groupKey
        awaitSettings("editor for the group") { it.route == SettingsRoute.ChannelEditor(pid, key) }
        val e = awaitEditor("that group's channels") { it.list == EditorList.Group(key) && (it.channels?.count ?: 0) > 0 }
        assertEquals(EditorPane.CHANNELS, e.pane)
        // The focused group's row arrives with its page (the cursor is set from keepKey first).
        awaitEditor("the Groups pane focuses that group") { u -> rowOf(u.groups.groups, u.groups.cursor - u.groups.head.size)?.groupKey == key }
    }

    // ------------------------------------------------------------------ I-M6-CE-2

    /** I-M6-CE-2: tick 3 with OK, hide them in 2 presses; the guide's group drops them. */
    @Test
    fun hideThreeAndTheGuideDropsThem() {
        fresh()
        launch()
        openEditorFromSettings()
        val key = (eui().list as EditorList.Group).groupKey
        val before = lineup(GuideSource.Group(pid, key)).size
        enterChannels()
        val keys = ArrayList<String>()
        repeat(3) { i ->
            if (i > 0) press(KeyEvent.KEYCODE_DPAD_DOWN)
            val u = awaitEditor("row $i focused") { it.channels?.cursor == i && rowOf(it.channels, i) != null }
            keys += rowOf(u.channels, i)!!.stableKey
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitEditor("${i + 1} ticked") { (it.channelSelection as? Selection.Keys)?.keys?.size == i + 1 }
        }
        val m = openChannelMenu()
        assertEquals(EditorAction.HIDE, m.menu!!.lines.first().action)
        assertEquals(3, m.menu!!.lines.first().count)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSaved("hide 3")
        awaitEditor("ticks cleared") { it.channelSelection == Selection.None }
        val after = awaitLineup("3 fewer channels in the group", GuideSource.Group(pid, key)) { it.size == before - 3 }
        assertTrue("none of the hidden channels is in the guide", stableKeysOf(after).none { it in keys })
        // The editor still lists them (Show: All), as HIDDEN.
        awaitEditor("rows show hidden") { u -> (0..2).all { rowOf(u.channels, it)?.hidden == true } }
    }

    // ------------------------------------------------------------------ I-M6-CE-3

    /** I-M6-CE-3: Select all in this list + Hide hides the whole group in the guide. */
    @Test
    fun selectAllAndHide() {
        fresh()
        launch()
        openEditorFromSettings()
        val key = (eui().list as EditorList.Group).groupKey
        assertEquals(50, lineup(GuideSource.Group(pid, key)).size)
        enterChannels()
        openChannelMenu()
        runLine(EditorAction.SELECT_ALL)
        awaitEditor("select all") { it.channelSelection is Selection.AllExcept && it.menu == null }
        val m = openChannelMenu()
        assertEquals("Hide 50", 50, m.menu!!.lines.first().count)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSaved("hide all")
        awaitLineup("the group is empty in the guide", GuideSource.Group(pid, key)) { it.isEmpty() }
    }

    // ------------------------------------------------------------------ I-M6-CE-4

    /** I-M6-CE-4: Reorder a group up two places (move mode, one write); the guide's Groups order follows. */
    @Test
    fun reorderGroupsAndTheGuideFollows() {
        fresh()
        launch()
        val order0 = guideGroupKeys()
        openEditorFromSettings()
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2) // the third group (pane index 3)
        val u = awaitEditor("third group focused") { it.groups.cursor == 3 && rowOf(it.groups.groups, 2) != null }
        val moving = rowOf(u.groups.groups, 2)!!.groupKey
        assertEquals(order0[2], moving)
        press(KeyEvent.KEYCODE_MENU)
        awaitEditor("group menu") { it.menu?.pane == EditorPane.GROUPS }
        runLine(EditorAction.REORDER)
        awaitEditor("move mode") { it.move != null }
        press(KeyEvent.KEYCODE_DPAD_UP, 2)
        awaitEditor("block at the top") { it.move?.index == 1 }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitEditor("dropped") { it.move == null }
        awaitSaved("reorder")
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (guideGroupKeys().firstOrNull() != moving && SystemClock.elapsedRealtime() < deadline) Thread.sleep(300)
        val order1 = guideGroupKeys()
        assertEquals("the moved group leads the guide's groups", moving, order1.first())
        assertEquals(listOf(moving, order0[0], order0[1]) + order0.drop(3), order1)
    }

    // ------------------------------------------------------------------ I-M6-CE-5

    /** I-M6-CE-5: Move to group… puts the channel in the target group in the guide (and out of its own). */
    @Test
    fun moveToGroupShowsInTheTargetGroup() {
        fresh()
        launch()
        openEditorFromSettings()
        val from = (eui().list as EditorList.Group).groupKey
        enterChannels()
        val ch = rowOf(eui().channels, 0)!!.stableKey
        openChannelMenu()
        runLine(EditorAction.MOVE_TO_GROUP)
        val p = awaitEditor("picker") { it.picker != null && rowOf(it.groups.groups, it.picker!!.cursor) != null }
        val to = rowOf(p.groups.groups, p.picker!!.cursor)!!.groupKey
        assertFalse("the picker starts past the current group", to == from)
        val toBefore = lineup(GuideSource.Group(pid, to)).size
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitEditor("picker closed") { it.picker == null }
        awaitSaved("move")
        val target = awaitLineup("the channel in the target group", GuideSource.Group(pid, to)) { it.size == toBefore + 1 }
        assertTrue(ch in stableKeysOf(target))
        val source = awaitLineup("the channel out of its group", GuideSource.Group(pid, from)) { it.size == 49 }
        assertFalse(ch in stableKeysOf(source))
    }

    // ------------------------------------------------------------------ I-M6-CE-6

    /** I-M6-CE-6: Rename… through the settings text editor; the row and the guide show the new name. */
    @Test
    fun renameAChannel() {
        fresh()
        launch()
        openEditorFromSettings()
        enterChannels()
        val row = rowOf(eui().channels, 0)!!
        openChannelMenu()
        runLine(EditorAction.RENAME)
        val ed = awaitSettings("the text editor") { it.editor != null }.editor!!
        assertEquals(row.name, ed.initial)
        Thread.sleep(300)
        onMain { settingsMachine().dispatch(SettingsEvent.EditorClosed(ed.row, "My Channel", committed = true)) }
        awaitSettings("editor closed") { it.editor == null }
        awaitImeHidden()
        awaitSaved("rename")
        awaitEditor("renamed row") { u -> rowOf(u.channels, 0)?.let { it.stableKey == row.stableKey && it.name == "My Channel" && it.renamed } == true }
        val key = (eui().list as EditorList.Group).groupKey
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var name: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val ids = lineup(GuideSource.Group(pid, key))
            name = runBlocking { probe.library.channels(ids, 0, ids.size) }.firstOrNull { it?.identity?.stableKey?.value == row.stableKey }?.displayName
            if (name == "My Channel") break
            Thread.sleep(300)
        }
        assertEquals("the guide shows the custom name", "My Channel", name)
    }

    // ------------------------------------------------------------------ I-M6-CE-7

    /** I-M6-CE-7: with a PIN set and the session locked, a locked group shows E7; OK asks for the PIN and then lists it. */
    @Test
    fun lockedGroupAsksForThePin() {
        fresh(adult = true)
        launch()
        setPin("1234")
        relaunch() // the session relocks when the app closes
        assertTrue(probe.parental().pinSet)
        openEditorFromSettings()
        // The last group is "Adult 18+" (auto-locked).
        var guard = 0
        while (eui().groups.cursor < eui().groups.head.size + 39 && guard++ < 20) press(KeyEvent.KEYCODE_PAGE_DOWN, intervalMs = 120)
        val u = awaitEditor("E7 on the adult group") { it.lockedList && it.empty == EditorText.E_LOCKED }
        assertTrue(rowOf(u.groups.groups, u.groups.cursor - u.groups.head.size)?.locked == true)
        enterChannels()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitGui("the guide's PIN prompt") { it.pin != null }
        digits("1234")
        awaitGui("PIN accepted", 10_000) { it.pin == null }
        val open = awaitEditor("the group's channels after the PIN") { !it.lockedList && (it.channels?.count ?: 0) > 0 }
        assertEquals(null, open.empty)
    }

    // ------------------------------------------------------------------ I-M6-CE-8

    /** I-M6-CE-8: Back leaves a mode before the page: menu → selection → Groups pane → Channels and groups (≤ 7 Backs). */
    @Test
    fun backLadderLeavesToChannelsAndGroups() {
        fresh()
        launch()
        openEditorFromSettings()
        enterChannels()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitEditor("ticked") { it.channelSelection != Selection.None }
        openChannelMenu()
        var backs = 0
        fun back() { press(KeyEvent.KEYCODE_BACK, intervalMs = 250); backs++ }
        back(); awaitEditor("menu closed") { it.menu == null }
        back(); awaitEditor("selection cleared, with the notice") { it.channelSelection == Selection.None && it.notice != null }
        back(); awaitEditor("Groups pane") { it.pane == EditorPane.GROUPS }
        back()
        awaitSettings("Channels and groups") { it.route == SettingsRoute.Section(SectionId.CHANNELS) }
        assertEquals(ScreenMode.SETTINGS, gui().mode)
        assertTrue("at most 7 Backs ($backs)", backs <= 7)
        // The Guide key leaves to the guide from the editor at any depth.
        activate("channels.editor")
        awaitSettings("editor again") { it.route is SettingsRoute.ChannelEditor }
        press(KeyEvent.KEYCODE_GUIDE)
        awaitGui("back in the guide") { it.mode != ScreenMode.SETTINGS }
    }
}
