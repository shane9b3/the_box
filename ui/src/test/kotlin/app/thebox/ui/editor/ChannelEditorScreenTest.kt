package app.thebox.ui.editor

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.editor.ChannelEditorEvent
import app.thebox.core.editor.ChannelEditorMachineImpl
import app.thebox.core.editor.ChannelEditorUiState
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorWindow
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupsPane
import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.SettingsRoute
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The channel editor screen (docs/design/m6-channel-editor.md §2.2, §2.3, §6.3) driven end to end: the real machine
 * (`ChannelEditorMachineImpl.FACTORY`) inside the real view model over [FakeEditorRepository], drawn by
 * [ChannelEditorHost] at 1920 × 1080 (1 design px = 1 px). Keys go in through the view model's `act`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ChannelEditorScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val texts = ResourceEditorTexts(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var now = 10_000L
    private lateinit var vm: ChannelEditorViewModel

    @After fun tearDown() = scope.cancel()

    private fun open(repo: FakeEditorRepository, route: SettingsRoute.ChannelEditor, parental: FakeParentalSession = FakeParentalSession()): ChannelEditorViewModel {
        vm = ChannelEditorViewModel(
            ChannelEditorMachineImpl.FACTORY, repo, parental, texts, EditorWriteRunner(repo, scope, Dispatchers.Unconfined),
            store = null, scope = scope, main = Dispatchers.Main, io = Dispatchers.Unconfined, nowMs = { now },
        )
        rule.setContent { BoxTheme { ChannelEditorHost(vm, route) } }
        rule.waitForIdle()
        return vm
    }

    private fun key(a: BoxAction, n: Int = 1) {
        repeat(n) { rule.runOnIdle { vm.act(GuideEvent.Act(a)) } }
        rule.waitForIdle()
    }

    private fun ui(): ChannelEditorUiState = vm.machine.ui.value
    private fun isSelected(selected: Boolean) = SemanticsMatcher("selected=$selected") { it.config.getOrNull(SemanticsProperties.Selected) == selected }
    private fun checked(on: Boolean) = SemanticsMatcher("checked=$on") { it.config.getOrNull(SemanticsProperties.StateDescription) == if (on) "checked" else "unchecked" }
    private fun tick(rowTag: String) = rule.onNode(hasTestTag(EDITOR_TICK_TAG) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)
    private fun near(a: Dp, b: Int) = abs(a.value - b) <= 1.5f

    private val fromSettings = SettingsRoute.ChannelEditor(1L)
    private val fromGuide = SettingsRoute.ChannelEditor(1L, "ent")

    @Test
    fun bothPanesAtTheDesignGeometryWithTheFixedRowsAndCounts() {
        open(FakeEditorRepository.sample(), fromSettings)
        val groups = rule.onNodeWithTag(EDITOR_GROUPS_PANE_TAG).getBoundsInRoot()
        val channels = rule.onNodeWithTag(EDITOR_CHANNELS_PANE_TAG).getBoundsInRoot()
        assertTrue("groups pane $groups", near(groups.left, 64) && near(groups.top, 144) && near(groups.right, 624) && near(groups.bottom, 944))
        assertTrue("channels pane $channels", near(channels.left, 648) && near(channels.right, 1856) && near(channels.bottom, 944))
        // Row 0 is the fixed "All channels" row (no tick box); the real groups follow.
        rule.onNodeWithTag(EDITOR_HEAD_ROW_TAG + 0).assertExists()
        rule.onNode(hasTestTag(EDITOR_TICK_TAG) and hasAnyAncestor(hasTestTag(EDITOR_HEAD_ROW_TAG + 0)), useUnmergedTree = true).assertDoesNotExist()
        for (k in listOf("ent", "sport", "news", "adult", "vod", "kids")) rule.onNodeWithTag(EDITOR_GROUP_ROW_TAG + k).assertExists()
        rule.onNodeWithText("6 groups · 2 hidden", useUnmergedTree = true).assertExists()
        // The first group is focused and followed by the channels pane.
        rule.onNodeWithTag(EDITOR_GROUP_ROW_TAG + "ent").assert(isSelected(true))
        rule.onNodeWithText("6 channels · 1 hidden", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("6 · 1 hidden", useUnmergedTree = true).assertExists() // the group's value: channels · hidden
        rule.onNodeWithText("2", useUnmergedTree = true).assertExists() // a group with nothing hidden: the plain count
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "bbc1").assertExists()
        // Toolbars and footer.
        rule.onNodeWithText("Hidden groups: Shown", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Show: All", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_FOOTER_TAG).assertExists()
        // Rows: 72 high, the first at pane top + 128.
        val first = rule.onNodeWithTag(EDITOR_HEAD_ROW_TAG + 0).getBoundsInRoot()
        assertTrue("first row $first", near(first.top, 144 + 128) && near(first.bottom - first.top, 72))
        // Focus on the channels pane: the current group gets the .08 fill and the accent bar.
        key(BoxAction.RIGHT)
        rule.onNode(hasTestTag(EDITOR_CURRENT_BAR_TAG) and hasAnyAncestor(hasTestTag(EDITOR_GROUP_ROW_TAG + "ent")), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "bbc1").assert(isSelected(true))
    }

    @Test
    fun ticksBadgesAndDetails() {
        open(FakeEditorRepository.sample(), fromGuide)
        key(BoxAction.SELECT)
        key(BoxAction.DOWN, 2)
        key(BoxAction.SELECT)
        tick(EDITOR_CHANNEL_ROW_TAG + "bbc1").assert(checked(true))
        tick(EDITOR_CHANNEL_ROW_TAG + "bbc2").assert(checked(false))
        tick(EDITOR_CHANNEL_ROW_TAG + "itv1").assert(checked(true))
        rule.onNodeWithText("6 channels · 1 hidden · 2 selected", useUnmergedTree = true).assertExists()
        // Badges: HIDDEN, ★, and the details "was …" / "from …".
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "hidden") and hasAnyAncestor(hasTestTag(EDITOR_CHANNEL_ROW_TAG + "ch4")), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "favorite") and hasAnyAncestor(hasTestTag(EDITOR_CHANNEL_ROW_TAG + "sky")), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("was ITV HD UK", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("from UK | Sport", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("101", useUnmergedTree = true).assertExists()
        // Group badges: HIDDEN, RULE, the lock and 18+.
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "hidden") and hasAnyAncestor(hasTestTag(EDITOR_GROUP_ROW_TAG + "news")), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "rule") and hasAnyAncestor(hasTestTag(EDITOR_GROUP_ROW_TAG + "vod")), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "locked") and hasAnyAncestor(hasTestTag(EDITOR_GROUP_ROW_TAG + "adult")), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "adult") and hasAnyAncestor(hasTestTag(EDITOR_GROUP_ROW_TAG + "adult")), useUnmergedTree = true).assertExists()
        // Back clears the selection in one press, with the notice.
        key(BoxAction.BACK)
        tick(EDITOR_CHANNEL_ROW_TAG + "bbc1").assert(checked(false))
        rule.onNodeWithText("2 channels unselected", useUnmergedTree = true).assertExists()
    }

    @Test
    fun ruleHiddenChannelNamesItsRule() {
        open(FakeEditorRepository.sample(), SettingsRoute.ChannelEditor(1L, "vod"))
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "rule") and hasAnyAncestor(hasTestTag(EDITOR_CHANNEL_ROW_TAG + "v1")), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Hidden by the rule “XXX*”", useUnmergedTree = true).assertExists()
    }

    @Test
    fun unloadedRowsArePlaceholdersUntilTheirPageLands() {
        val repo = FakeEditorRepository.sample()
        val gate = CompletableDeferred<Unit>()
        repo.rowsGate = gate
        open(repo, fromGuide)
        rule.onNodeWithTag(EDITOR_PLACEHOLDER_TAG + "c0").assertExists()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "bbc1").assertDoesNotExist()
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag(EDITOR_PLACEHOLDER_TAG + "c0").assertDoesNotExist()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "bbc1").assertExists()
    }

    @Test
    fun menuOpensLoadingThenFillsAnchoredToTheFocusedRow() {
        val repo = FakeEditorRepository.sample()
        val gate = CompletableDeferred<Unit>()
        repo.summaryGate = gate
        open(repo, fromGuide)
        key(BoxAction.SELECT)
        key(BoxAction.DOWN, 2)
        key(BoxAction.SELECT)
        key(BoxAction.RIGHT)
        // Two ticked: the summary is on its way, so "…" and no lines.
        rule.onNodeWithTag(EDITOR_MENU_TITLE_TAG, useUnmergedTree = true).assert(hasText("…"))
        rule.onAllNodesWithTag(EDITOR_MENU_LINE_TAG + 0).assertCountEquals(0)
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("2 channels selected", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_MENU_LINE_TAG + 0).assert(isSelected(true))
        rule.onNodeWithText("Hide", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Reset to provider settings…", useUnmergedTree = true).assertExists()
        // Anchored to the focused row's right edge inside the channels pane, at the row (clamped to the pane).
        val m = ui().menu!!
        val card = rule.onNodeWithTag(EDITOR_MENU_TAG).getBoundsInRoot()
        val rowY = 144 + 128 + (ui().channels!!.cursor - ui().channels!!.top) * 80
        assertTrue("menu left ${card.left}", near(card.left, 648 + 1208 - 16 - 480))
        assertTrue("menu top ${card.top}", near(card.top, editorMenuTop(rowY, menuHeight(m))))
        assertTrue("menu width", near(card.right - card.left, 480))
        // Back closes it.
        key(BoxAction.BACK)
        rule.onNodeWithTag(EDITOR_MENU_TAG).assertDoesNotExist()
    }

    @Test
    fun singleRowMenuIsTitledWithTheChannelAndShowsDisabledReasons() {
        open(FakeEditorRepository.sample(), SettingsRoute.ChannelEditor(1L, "adult"))
        key(BoxAction.CONTEXT_MENU)
        rule.onNodeWithTag(EDITOR_MENU_TITLE_TAG, useUnmergedTree = true).assert(hasText("Late"))
        rule.onNodeWithText("Rename…", useUnmergedTree = true).assertExists()
        // Unlock inside a locked group is disabled, with its reason and the group's name filled in.
        rule.onNodeWithText("Locked by its group, Adult. Unlock the group to unlock this channel.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun reorderLiftsTheTickedRowsIntoOneBlock() {
        open(FakeEditorRepository.sample(), fromGuide)
        key(BoxAction.SELECT) // bbc1
        key(BoxAction.DOWN, 2)
        key(BoxAction.SELECT) // itv1
        key(BoxAction.DOWN) // ch4, list index 3
        key(BoxAction.RIGHT)
        rule.waitForIdle()
        val lines = ui().menu!!.lines
        val reorder = lines.indexOfFirst { it.action == app.thebox.core.editor.EditorAction.REORDER }
        key(BoxAction.DOWN, reorder)
        key(BoxAction.SELECT)
        assertTrue(ui().move != null)
        rule.onAllNodesWithTag(EDITOR_LIFT_TAG, useUnmergedTree = true).assertCountEquals(2)
        // The block sits where the focus was, in list order: bbc2, [bbc1, itv1], ch4 …
        rule.onNodeWithText("Moving 2 · position 2 of 6", useUnmergedTree = true).assertExists()
        val top = { k: String -> rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + k).getBoundsInRoot().top.value }
        assertTrue(top("bbc2") < top("bbc1") && top("bbc1") < top("itv1") && top("itv1") < top("ch4"))
        // ↓ moves the block, the rows it passes shift; Back cancels.
        key(BoxAction.DOWN)
        assertTrue(top("ch4") < top("bbc1"))
        rule.onNodeWithText("Moving 2 · position 3 of 6", useUnmergedTree = true).assertExists()
        key(BoxAction.BACK)
        rule.onAllNodesWithTag(EDITOR_LIFT_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun lockedGroupShowsE7AndTheLockedOutNoteHeadsMixedLists() {
        val parental = FakeParentalSession(pinSet = true, unlocked = false)
        open(FakeEditorRepository.sample(), SettingsRoute.ChannelEditor(1L, "adult"), parental)
        rule.onNodeWithText("Adult is locked. Press OK to enter your PIN.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "x1").assertDoesNotExist()
        // ← to the Groups pane, ↑ to All channels: the locked channel is left out, and the note heads the list.
        key(BoxAction.LEFT)
        key(BoxAction.UP, 4)
        key(BoxAction.RIGHT)
        assertEquals(EditorList.All, ui().list)
        rule.onNodeWithTag(EDITOR_LOCKED_NOTE_TAG).assertExists()
        rule.onNodeWithText("Locked channels are not shown. Press OK to enter your PIN.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "x1").assertDoesNotExist()
    }

    @Test
    fun emptyTextsNameTheListAndTheNextStep() {
        open(FakeEditorRepository.sample(), SettingsRoute.ChannelEditor(1L, "kids"))
        rule.onNodeWithText("UK | Kids has no channels in the latest update. They come back with the next refresh, or you can hide the group.", useUnmergedTree = true).assertExists()
        // Show: Hidden only on a group without hidden channels (the toolbar's choice list, E6).
        key(BoxAction.LEFT)
        key(BoxAction.UP, 5) // back to UK | Entertainment
        now += 1_000; rule.runOnIdle { vm.machine.dispatch(ChannelEditorEvent.Tick(now)) }; rule.waitForIdle()
        key(BoxAction.RIGHT)
        key(BoxAction.UP) // toolbar
        key(BoxAction.RIGHT) // Show: All
        key(BoxAction.SELECT)
        rule.onNodeWithTag(EDITOR_CHOICE_TAG).assertExists()
        rule.onNodeWithTag(EDITOR_CHOICE_OPTION_TAG + 0).assert(checked(true))
        key(BoxAction.DOWN)
        key(BoxAction.SELECT) // Shown only
        assertEquals(EditorFilter.SHOWN, ui().filter)
        rule.onNodeWithText("Show: Shown only", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "ch4").assertDoesNotExist()
        // The Find and filter texts carry their argument.
        val base = ui()
        assertEquals("No channel matches “zzz”. Check the spelling or try part of the name.",
            emptyText(base.copy(list = EditorList.Find("zzz"), empty = EditorText.E_FIND_NONE), texts))
        assertEquals("No hidden channels in UK | Entertainment. Set Show to All to see every channel.",
            emptyText(base.copy(empty = EditorText.E_EMPTY_HIDDEN), texts))
        assertEquals("Couldn't read the channel list (storage error). Press OK to try again; if it keeps failing, restart the app.",
            emptyText(base.copy(empty = EditorText.E_LOAD), texts))
    }

    @Test
    fun noticeSitsUnderThePanesAtY956() {
        open(FakeEditorRepository.sample(), fromGuide)
        key(BoxAction.SELECT)
        key(BoxAction.BACK)
        val notice = rule.onNodeWithTag(EDITOR_NOTICE_TAG, useUnmergedTree = true).getBoundsInRoot()
        val box = notice.top - ((40 - (notice.bottom - notice.top).value) / 2).dp
        assertTrue("notice line top ≈ 956 (${box})", abs(box.value - 956) <= 2)
        // Row 8 (the last whole row) ends at 144 + 128 + 8 × 80 − 8 = 904, above the panes' bottom 944 and the notice.
        assertTrue(notice.top.value >= 944)
    }

    @Test
    fun aWriteReloadsTheListAndTheRowShowsItsNewState() {
        val repo = FakeEditorRepository.sample()
        open(repo, fromGuide)
        key(BoxAction.RIGHT) // single-row menu on BBC One HD
        key(BoxAction.SELECT) // Hide
        rule.waitForIdle()
        assertTrue(repo.writes.single() is EditorWrite.HideChannels)
        rule.onNode(hasTestTag(EDITOR_BADGE_TAG + "hidden") and hasAnyAncestor(hasTestTag(EDITOR_CHANNEL_ROW_TAG + "bbc1")), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 channel hidden", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pickerListsTheGroupsWithAlreadyHere() {
        open(FakeEditorRepository.sample(), fromGuide)
        key(BoxAction.RIGHT)
        key(BoxAction.DOWN) // Move to group…
        key(BoxAction.SELECT)
        rule.onNodeWithTag(EDITOR_PICKER_TAG).assertExists()
        rule.onNodeWithText("Move 1 channel to", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Already here", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_PICKER_ROW_TAG + 1).assert(isSelected(true)) // the cursor starts past the current group
        rule.onNodeWithText("Hidden: the channels won't show until you show the group", useUnmergedTree = true).assertExists()
        val card = rule.onNodeWithTag(EDITOR_PICKER_TAG).getBoundsInRoot()
        assertTrue(near(card.left, 600) && near(card.top, 160) && near(card.right - card.left, 720))
    }

    @Test
    fun countsLeaveOutZeroHiddenAndTheRowsAreaShowsOnlyWholeRows() {
        val repo = FakeEditorRepository.sample().apply {
            group(FakeEditorRepository.group("many", "Many", channels = 12),
                *(1..12).map { FakeEditorRepository.channel("m$it", "Many $it", 500 + it) }.toTypedArray())
        }
        open(repo, SettingsRoute.ChannelEditor(1L, "many"))
        rule.onNodeWithText("12 channels", useUnmergedTree = true).assertExists()
        // Rows 0–7 are whole inside the area; row 8 (it would start at 640) is clipped away, not peeking under row 7.
        val area = rule.onNodeWithTag(EDITOR_CHANNELS_PANE_TAG).getBoundsInRoot()
        val r7 = rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "m8").getBoundsInRoot()
        assertTrue(near(r7.bottom, 144 + 128 + 8 * 80 - 8) && r7.bottom < area.bottom)
        rule.onNodeWithTag(EDITOR_CHANNEL_ROW_TAG + "m9").assertDoesNotExist()
    }

    @Test
    fun aGroupsLoadFailureIsToldInTheGroupsPane() {
        val repo = FakeEditorRepository.sample().apply { failGroups = true }
        open(repo, fromSettings)
        assertTrue(ui().groupsFailed)
        rule.onNodeWithTag(EDITOR_GROUPS_MESSAGE_TAG).assertExists()
        rule.onNodeWithText("Couldn't read the channel list (storage error). Press OK to try again; if it keeps failing, restart the app.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(EDITOR_HEAD_ROW_TAG + 0).assertExists()
        // OK retries; with the library readable again, the groups come back.
        repo.failGroups = false
        key(BoxAction.SELECT)
        rule.onNodeWithTag(EDITOR_GROUPS_MESSAGE_TAG).assertDoesNotExist()
        rule.onNodeWithTag(EDITOR_GROUP_ROW_TAG + "ent").assertExists()
    }

    @Test
    fun groupSortConfirmsHaveTheirOwnTexts() {
        val sort = app.thebox.core.editor.EditorConfirm(app.thebox.core.editor.ConfirmKind.SORT_REPLACES_ORDER, listOf("Groups"),
            EditorWrite.SortGroups(1L, app.thebox.core.editor.GroupSort.NAME_AZ))
        val (t1, b1, _) = confirmTexts(sort, texts)
        assertEquals("Sort groups?", t1)
        assertEquals("Your own group order is replaced. You can reorder again at any time.", b1)
        val provider = sort.copy(kind = app.thebox.core.editor.ConfirmKind.PROVIDER_ORDER, pending = EditorWrite.SortGroups(1L, app.thebox.core.editor.GroupSort.PROVIDER))
        val (t2, b2, _) = confirmTexts(provider, texts)
        assertEquals("Go back to the provider's order?", t2)
        assertEquals("Your own group order is removed.", b2)
        val channels = app.thebox.core.editor.EditorConfirm(app.thebox.core.editor.ConfirmKind.PROVIDER_ORDER, listOf("UK | Sport"),
            EditorWrite.SortChannels(1L, "sport", app.thebox.core.editor.ChannelSort.PROVIDER))
        assertEquals("Your own order for UK | Sport is removed.", confirmTexts(channels, texts).second)
    }

    @Test
    fun confirmCardTextsAndSafeFocus() {
        val c = app.thebox.core.editor.EditorConfirm(
            app.thebox.core.editor.ConfirmKind.RESET_CHANNELS, listOf("3"),
            EditorWrite.ResetChannels(1L, app.thebox.core.editor.ChannelTarget.Keys(setOf("a"))),
        )
        val (title, body, buttons) = confirmTexts(c, texts)
        assertEquals("Reset 3 channels?", title)
        assertTrue(body.startsWith("Names, numbers, group moves"))
        assertEquals(listOf("Reset", "Cancel"), buttons)
        assertEquals(1, c.focused)
        val state = ChannelEditorUiState(1L, "", groups = GroupsPane(listOf(EditorList.All), null, 0, 0), list = EditorList.All,
            channels = EditorWindow(1, 0, 0, emptyMap(), 0, 0), confirm = c)
        rule.setContent { BoxTheme { ChannelEditorContent(state, texts, showLogos = true) } }
        rule.onNodeWithTag(app.thebox.ui.settings.SETTINGS_CONFIRM_BUTTON_TAG + 1).assert(isSelected(true))
        rule.onNodeWithText("Reset 3 channels?", useUnmergedTree = true).assertExists()
    }
}
