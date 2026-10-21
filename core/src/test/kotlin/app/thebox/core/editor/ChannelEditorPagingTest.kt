package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-channel-editor.md §3.3 "Stale events", "Deadlines", §4 items 2–4: windows, versions, cursor restore. */
class ChannelEditorPagingTest {
    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun theWindowKeepsAtMostSixPages() {
        val w = EditorWorld(groups = EditorWorld.big(2_000)).open("g:0")
        assertEquals(setOf(0, 1), w.ui.channels!!.pages.keys, "[0, 64) on open")
        repeat(100) { w.k(BoxAction.ZAP_DOWN) }
        val pages = w.ui.channels!!.pages
        assertTrue(pages.size <= EditorWindow.PAGES_KEPT, "kept ${pages.keys}")
        assertEquals(800, w.ui.channels!!.cursor)
        // The pages around the cursor are loaded: [768, 864) → pages 24..26.
        assertTrue(pages.keys.containsAll(listOf(24, 25, 26)), "${pages.keys}")
        // Every request is a whole, aligned page.
        assertTrue(w.channelRowLoads.all { (f, t) -> f % EditorWindow.PAGE == 0 && (t - f == EditorWindow.PAGE || t == 2_000) })
        // Going back reloads an evicted page once.
        val loads = w.channelRowLoads.size
        repeat(100) { w.k(BoxAction.ZAP_UP) }
        assertTrue(0 in w.ui.channels!!.pages.keys)
        assertTrue(w.channelRowLoads.size > loads)
    }

    @Test fun holdingDownAcceleratesAfterHoldAccelMs() {
        val w = EditorWorld(groups = EditorWorld.big(50)).open("g:0")
        w.press(BoxAction.DOWN, repeat = 1, heldMs = 100); assertEquals(1, w.ui.channels!!.cursor)
        w.press(BoxAction.DOWN, repeat = 5, heldMs = ChannelEditorMachine.HOLD_ACCEL_MS); assertEquals(4, w.ui.channels!!.cursor)
    }

    @Test fun staleVersionsAndTokensAreDropped() {
        val w = EditorWorld(groups = EditorWorld.big(100)).open("g:0")
        val v = w.ui.channels!!.version
        // A late page of an older version (the list was reloaded meanwhile).
        w.send(ChannelEditorEvent.ChannelRows(v - 1, 64, List(32) { w.channelRow("c0-0") }))
        assertTrue(2 !in w.ui.channels!!.pages.keys)
        // A Channels event for another list is dropped.
        w.send(ChannelEditorEvent.Channels(EditorList.All, EditorFilter.ALL, v + 50, 5, 0, 0, false, null))
        assertEquals(v, w.ui.channels!!.version)
        // An older Channels event for this list is dropped too.
        w.send(ChannelEditorEvent.Channels(EditorList.Group("g:0"), EditorFilter.ALL, v - 1, 5, 0, 0, false, null))
        assertEquals(100, w.ui.channels!!.count)
        // Groups: an older version is dropped; rows of another version are dropped.
        val gv = w.ui.groups.groups!!.version
        w.send(ChannelEditorEvent.Groups(gv - 1, 9, 0, null))
        assertEquals(1, w.ui.groups.groups!!.count)
        w.send(ChannelEditorEvent.GroupRows(gv + 3, 0, listOf(w.groupRow(w.groups[0]))))
        assertEquals(gv, w.ui.groups.groups!!.version)
        // A Summary with an old token is ignored (ChannelEditorSummaryTest covers the menu).
        w.send(ChannelEditorEvent.Summary(-5, SelectionSummary(1, 0, 0, 0, 0, 0, 0)))
        assertNull(w.ui.menu)
    }

    @Test fun aLatePageForAnOldGroupIsDropped() {
        val w = EditorWorld(groups = EditorWorld.big(40, groupsN = 3)).open()
        w.hold += "loadChannelRows"
        w.k(BoxAction.DOWN) // follow → Group 1 (its rows held)
        w.k(BoxAction.DOWN) // follow → Group 2 (rows held)
        w.release("loadChannelRows")
        assertEquals("G:g:2", w.listName())
        assertTrue(w.ui.channels!!.pages.values.flatten().all { it.stableKey.startsWith("c2-") })
    }

    @Test fun theCursorIsRestoredByTheReturnedIndexElseKept() {
        val w = EditorWorld(groups = EditorWorld.big(10)).open("g:0").k(BoxAction.DOWN, BoxAction.DOWN, BoxAction.DOWN)
        val v = w.ui.channels!!.version
        // The view model found the kept key at 7 (after a sort).
        w.send(ChannelEditorEvent.Channels(EditorList.Group("g:0"), EditorFilter.ALL, v + 1, 10, 0, 0, false, cursorIndex = 7))
        assertEquals(7, w.ui.channels!!.cursor)
        // Not found (it left the filter): the index stays.
        w.send(ChannelEditorEvent.Channels(EditorList.Group("g:0"), EditorFilter.ALL, v + 2, 10, 0, 0, false, cursorIndex = null))
        assertEquals(7, w.ui.channels!!.cursor)
        // Fewer rows: clamped.
        w.send(ChannelEditorEvent.Channels(EditorList.Group("g:0"), EditorFilter.ALL, v + 3, 4, 0, 0, false, cursorIndex = null))
        assertEquals(3, w.ui.channels!!.cursor)
    }

    @Test fun aReloadAsksToKeepTheFocusedKey() {
        val w = EditorWorld().open("g:news").k(BoxAction.DOWN, BoxAction.DOWN)
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        assertEquals(Triple(EditorList.Group("g:news"), EditorFilter.ALL, "n3"), w.channelLoads.last())
        assertEquals("g:news", w.groupLoads.last().second)
    }

    @Test fun theFollowDebounceRunsThroughTheDeadline() {
        val w = EditorWorld().open()
        assertEquals("G:g:news", w.listName())
        w.machine.act(app.thebox.core.guide.GuideEvent.Act(BoxAction.DOWN)); w.pump()
        assertEquals(w.now + ChannelEditorMachine.FOLLOW_DEBOUNCE_MS, w.machine.nextDeadlineMs.value)
        assertEquals("G:g:news", w.listName(), "not before the debounce")
        // Holding ↓ through two more groups restarts the debounce: one list load at the end.
        val loads = w.channelLoads.size
        w.now += 100; w.machine.act(app.thebox.core.guide.GuideEvent.Act(BoxAction.UP)); w.pump()
        w.now += 100; w.machine.dispatch(ChannelEditorEvent.Tick(w.now)); w.pump()
        assertEquals(loads, w.channelLoads.size)
        w.now += 60; w.machine.dispatch(ChannelEditorEvent.Tick(w.now)); w.pump()
        w.now += 200; w.machine.act(app.thebox.core.guide.GuideEvent.Act(BoxAction.DOWN)); w.pump()
        w.tick(ChannelEditorMachine.FOLLOW_DEBOUNCE_MS)
        assertEquals("G:g:sport", w.listName())
        assertEquals(Long.MAX_VALUE, w.machine.nextDeadlineMs.value)
    }

    @Test fun rightFollowsAtOnce() {
        val w = EditorWorld().open()
        w.machine.act(app.thebox.core.guide.GuideEvent.Act(BoxAction.DOWN)); w.pump()
        w.machine.act(app.thebox.core.guide.GuideEvent.Act(BoxAction.RIGHT)); w.pump()
        assertEquals("G:g:sport", w.listName())
        assertEquals(EditorPane.CHANNELS, w.ui.pane)
    }

    @Test fun cursorMemoryPerList() {
        val w = EditorWorld().open().k(BoxAction.RIGHT, BoxAction.DOWN, BoxAction.DOWN) // News, row 2
        w.k(BoxAction.LEFT, BoxAction.DOWN, BoxAction.RIGHT) // Sport
        assertEquals(0, w.ui.channels!!.cursor)
        w.k(BoxAction.LEFT, BoxAction.UP, BoxAction.RIGHT) // back to News: row 2, asked by key
        assertEquals(2, w.ui.channels!!.cursor)
        assertEquals("n3", w.channelLoads.last().third)
    }

    @Test fun noticesFadeAtTheirDeadline() {
        val w = EditorWorld().open("g:news").k(BoxAction.SELECT, BoxAction.BACK)
        assertEquals("N_UNSELECTED[1]", w.ui.notice)
        val d = w.machine.nextDeadlineMs.value
        w.now = d; w.machine.dispatch(ChannelEditorEvent.Tick(d))
        assertNull(w.ui.notice)
    }

    @Test fun digitsJumpAfterTheTimeout() {
        val w = EditorWorld().open("g:news")
        w.press(BoxAction.DIGIT, digit = 1); w.press(BoxAction.DIGIT, digit = 0); w.press(BoxAction.DIGIT, digit = 3)
        assertEquals("103", w.ui.digits)
        assertEquals(w.now + ChannelEditorMachine.DIGIT_TIMEOUT_MS, w.machine.nextDeadlineMs.value)
        w.tick(ChannelEditorMachine.DIGIT_TIMEOUT_MS)
        assertNull(w.ui.digits)
        assertEquals(2, w.ui.channels!!.cursor)
        w.press(BoxAction.DIGIT, digit = 5); w.tick(ChannelEditorMachine.DIGIT_TIMEOUT_MS)
        assertEquals("N_NO_NUMBER(5)", w.ui.notice)
        assertEquals(2, w.ui.channels!!.cursor)
        // A custom number is found too.
        w.press(BoxAction.DIGIT, digit = 9); w.press(BoxAction.DIGIT, digit = 0); w.press(BoxAction.DIGIT, digit = 0)
        w.tick(ChannelEditorMachine.DIGIT_TIMEOUT_MS)
        assertEquals(3, w.ui.channels!!.cursor)
        // OK commits at once and ticks nothing.
        w.press(BoxAction.DIGIT, digit = 1); w.press(BoxAction.DIGIT, digit = 0); w.press(BoxAction.DIGIT, digit = 1)
        w.press(BoxAction.SELECT)
        assertNull(w.ui.digits)
        assertEquals(0, w.ui.channels!!.cursor)
        assertEquals(Selection.None, w.ui.channelSelection)
    }

    @Test fun aGoneRowReloadsTheList() {
        val w = EditorWorld().open("g:news")
        val loads = w.channelLoads.size
        val v = w.ui.channels!!.version
        w.send(ChannelEditorEvent.ChannelRows(v, 0, listOf(w.channelRow("n1"), null, w.channelRow("n3"), w.channelRow("n4"))))
        assertEquals(loads + 1, w.channelLoads.size)
    }
}
