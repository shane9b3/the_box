package app.thebox.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ChoiceState
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.ConfirmState
import app.thebox.core.settings.EditorState
import app.thebox.core.settings.MenuState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings shell as drawn from `SettingsUiState` (docs/design/m5-setup-settings.md §3.1, §6.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val route = SettingsRoute.Section(SectionId.PLAYBACK)
    private val rows = listOf(
        SettingsRow(RowId("playback.tunneling"), "Tunneled playback", description = "Can cause a black screen on some TVs", kind = RowKind.Toggle(on = true)),
        SettingsRow(
            RowId("playback.audio_output"), "Audio output",
            kind = RowKind.Choice(listOf(ChoiceOption("auto", "Auto"), ChoiceOption("pcm", "Always decode (PCM)"), ChoiceOption("force", "Always passthrough")), selected = 0),
        ),
        SettingsRow(RowId("guide.sources"), "Guide sources", kind = RowKind.Link(SettingsRoute.EpgSources)),
        SettingsRow(RowId("x.password"), "Password", kind = RowKind.Text("secret", TextHint.PASSWORD, masked = true)),
        SettingsRow(RowId("x.days"), "Catch-up days", kind = RowKind.Number(3, 0, 30, unit = "days")),
        SettingsRow(RowId("x.info"), "Account", kind = RowKind.Info(listOf("Active", "1 / 2"), Tone.WARN)),
        SettingsRow(RowId("x.disabled"), "Invert channel direction", description = "never shown", disabledReason = "Only when Up/Down change channel", kind = RowKind.Toggle(false), enabled = false),
    )
    private val page = PageModel(route, "Playback", rows = rows, version = 1)

    private fun state(
        pane: Pane = Pane.PAGE,
        cursor: Int = 0,
        sections: List<SectionId> = SectionId.entries.filter { it != SectionId.NETWORK },
        page: PageModel? = this.page,
    ) = SettingsUiState(route = route, stack = listOf(SettingsRoute.Root, route), pane = pane, sections = sections, sectionCursor = 2, page = page, cursor = cursor)

    private fun show(ui: SettingsUiState) = rule.setContent { BoxTheme { SettingsContent(ui, clock24 = true) } }

    private fun isSelected(selected: Boolean) = SemanticsMatcher("selected=$selected") { it.config.getOrNull(SemanticsProperties.Selected) == selected }

    @Test
    fun twoPanesWithSectionsAndPageRows() {
        show(state())
        rule.onNodeWithTag(SETTINGS_SECTIONS_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_PAGE_TAG).assertExists()
        rule.onAllNodesWithText("Playback", useUnmergedTree = true).assertCountEquals(2) // section row + page title
        rule.onNodeWithText("Show advanced settings", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SETTINGS_BREADCRUMB_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Settings › Playback", useUnmergedTree = true).assertExists()
        for (r in rows) rule.onNodeWithTag(SETTINGS_ROW_TAG + r.id.key).assertExists()
    }

    @Test
    fun noSectionsPaneWhenDetachedOrFirstRun() {
        show(state(sections = emptyList()))
        rule.onNodeWithTag(SETTINGS_SECTIONS_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SETTINGS_PAGE_TAG).assertExists()
    }

    @Test
    fun rowKindsDrawTheirValues() {
        show(state())
        rule.onNodeWithText("Auto", useUnmergedTree = true).assertExists() // the Choice's selected option as value
        rule.onNodeWithText("••••••••", useUnmergedTree = true).assertExists() // masked text never shows the secret
        rule.onNodeWithText("secret", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("3 days", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Active  ·  1 / 2", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Can cause a black screen on some TVs", useUnmergedTree = true).assertExists()
    }

    @Test
    fun disabledRowShowsItsReasonInsteadOfTheDescription() {
        show(state())
        rule.onNodeWithText("Only when Up/Down change channel", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("never shown", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun focusFollowsTheCursorAndThePane() {
        show(state(cursor = 1))
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.audio_output").assert(isSelected(true))
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.tunneling").assert(isSelected(false))
    }

    @Test
    fun sectionsPaneFocusLeavesThePageUnfocused() {
        show(state(pane = Pane.SECTIONS, cursor = 1))
        rule.onNodeWithTag(SETTINGS_SECTION_TAG + 2).assert(isSelected(true))
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.audio_output").assert(isSelected(false))
    }

    @Test
    fun advancedToggleFocusedAfterTheLastSection() {
        val s = state(pane = Pane.SECTIONS)
        show(s.copy(sectionCursor = s.sections.size))
        rule.onNodeWithTag(SETTINGS_ADVANCED_TAG).assert(isSelected(true))
    }

    @Test
    fun inlineChoiceListsTheOptionsUnderTheRow() {
        show(state(cursor = 1).copy(choice = ChoiceState(RowId("playback.audio_output"), focused = 2)))
        rule.onNodeWithTag(SETTINGS_OPTION_TAG + "playback.audio_output:0").assertExists()
        rule.onNodeWithTag(SETTINGS_OPTION_TAG + "playback.audio_output:2").assert(isSelected(true)).assertIsDisplayed()
        // The option list replaces the row's own focus style.
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.audio_output").assert(isSelected(false))
    }

    /**
     * Device finding 2026-10-17: with the machine's `top` at 0 and the cursor on the 8th row, eight 104 px rows ran the
     * focused row past the page pane (the machine budgets 8 rows of 76 px). The page scrolls on until it is whole.
     */
    @Test
    fun focusedTallRowBeyondTheEightRowBudgetIsScrolledIntoThePane() {
        val tall = (0 until 9).map { i ->
            SettingsRow(RowId("x.tall$i"), "Row $i", description = "Second line $i", kind = RowKind.Toggle(on = i % 2 == 0))
        }
        show(state(cursor = 7, page = PageModel(route, "Playback", rows = tall, version = 1)))
        rule.waitForIdle()
        val pane = rule.onNodeWithTag(SETTINGS_PAGE_TAG).getBoundsInRoot()
        val row = rule.onNodeWithTag(SETTINGS_ROW_TAG + "x.tall7").assertIsDisplayed().getBoundsInRoot()
        assertTrue("row bottom ${row.bottom} inside the pane ${pane.bottom}", row.bottom <= pane.bottom)
        assertTrue("row top ${row.top} inside the pane ${pane.top}", row.top >= pane.top)
        // The row below is cut, the rows above are whole: the first row shown is still a whole row.
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "x.tall0").assertDoesNotExist()
    }

    @Test
    fun noticeLineShows() {
        show(state().copy(notice = "Saved"))
        rule.onNodeWithTag(SETTINGS_NOTICE_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Saved", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pagePlaceholderReplacesTheRows() {
        show(state(page = page.copy(rows = emptyList(), placeholder = "No hidden channels")))
        rule.onNodeWithText("No hidden channels", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pagedListDrawsLoadedRowsAndPlaceholders() {
        val items = (0 until 32).map { SettingsRow(RowId("hidden.$it"), "Channel $it", kind = RowKind.Item(primary = "Show", badges = setOf(Badge.HIDDEN, Badge.ADULT))) }
        val paged = page.copy(rows = rows.take(1), list = PagedList(count = 100, loaded = mapOf(0 to items)))
        show(state(page = paged))
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "hidden.0").assertExists()
        assertTrue(rule.onAllNodesWithText("HIDDEN", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("18+", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(101, rowCount(paged))
        assertEquals("hidden.31", rowAt(paged, 32)?.id?.key)
        assertEquals(null, rowAt(paged, 33)) // page 1 not loaded → placeholder
    }

    @Test
    fun unloadedRowIsAPlaceholder() {
        val paged = page.copy(rows = emptyList(), list = PagedList(count = 3, loaded = emptyMap()))
        show(state(page = paged))
        rule.onNodeWithTag(SETTINGS_PLACEHOLDER_ROW_TAG + 0).assertExists()
    }

    @Test
    fun confirmCardFocusesTheSafeButton() {
        val spec = ConfirmSpec("Remove Provider A?", "2,140 channels go with it.", "Remove", "Cancel")
        show(state().copy(confirm = ConfirmState(spec, RowIntent.Run(RowId("playlist.remove")))))
        rule.onNodeWithTag(SETTINGS_CONFIRM_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_CONFIRM_BUTTON_TAG + 1).assert(isSelected(true))
        rule.onNodeWithTag(SETTINGS_CONFIRM_BUTTON_TAG + 0).assert(isSelected(false))
        // An overlay takes the row focus away.
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.tunneling").assert(isSelected(false))
    }

    @Test
    fun contextMenuDraws() {
        show(state().copy(menu = MenuState(RowId("playback.tunneling"), listOf(ChoiceOption("a", "Show"), ChoiceOption("b", "Remove")), focused = 1)))
        rule.onNodeWithTag(SETTINGS_MENU_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_MENU_OPTION_TAG + 1).assert(isSelected(true))
    }

    @Test
    fun screenDispatchesEditorResultsToTheMachine() {
        val editor = EditorState(RowId("x.name"), "Name", "Provider A", TextHint.NAME, masked = false, chips = emptyList())
        val machine = FakeSettingsUiMachine(state().copy(editor = editor))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalKeyboardCheck provides { true }) {
                BoxTheme { SettingsScreen(machine, clock24 = true) }
            }
        }
        rule.onNodeWithTag(EDITOR_DONE_TAG).performClick()
        rule.onNodeWithTag(EDITOR_CANCEL_TAG).performClick()
        rule.runOnIdle {
            val closed = machine.events.filterIsInstance<SettingsEvent.EditorClosed>()
            assertEquals(2, closed.size)
            assertEquals(SettingsEvent.EditorClosed(editor.row, "Provider A", committed = true), closed[0])
            assertEquals(SettingsEvent.EditorClosed(editor.row, null, committed = false), closed[1])
        }
    }

    @Test
    fun screenFollowsTheMachineState() {
        val machine = FakeSettingsUiMachine(state(cursor = 0))
        rule.setContent { BoxTheme { SettingsScreen(machine, clock24 = false) } }
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.tunneling").assert(isSelected(true))
        rule.runOnIdle { machine.state.value = machine.state.value.copy(cursor = 2) }
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "guide.sources").assert(isSelected(true))
        rule.onNodeWithTag(SETTINGS_ROW_TAG + "playback.tunneling").assert(isSelected(false))
    }

    @Test
    fun rowHeightsFollowTheSpec() {
        assertEquals(SettingsUi.ROW_H_DESC, rowHeight(rows[0]))
        assertEquals(SettingsUi.ROW_H, rowHeight(rows[1]))
        assertEquals(SettingsUi.ITEM_ROW_H, rowHeight(SettingsRow(RowId("i"), "Item", kind = RowKind.Item())))
        assertTrue(SettingsUi.isMonoValue("14:02"))
        assertTrue(SettingsUi.isMonoValue("panel.example.com:8080"))
    }

    /** U-S9 (M5 code review 2026-10-16): an Off toggle stays visible on a focused (light) row; On keeps its colours. */
    @Test
    fun offToggleIsVisibleOnAFocusedRow() {
        val row = SettingsUi.Fg.luminance()
        val (fill, knob) = togglePillColors(on = false, onLight = true)
        assertTrue("the knob vanishes into the focused row", kotlin.math.abs(knob.luminance() - row) > 0.5f)
        val blended = fill.compositeOver(SettingsUi.Fg).luminance()
        assertTrue("the Off fill is not visible on the focused row", kotlin.math.abs(blended - row) > 0.05f)
        assertEquals(togglePillColors(on = true, onLight = false), togglePillColors(on = true, onLight = true))
    }

    /** U-L8 (M5 code review 2026-10-16): "Set up from phone" inside a first-run form keeps the first-run review. */
    @Test
    fun phoneSetupFromAFirstRunFormKeepsFirstRun() {
        val editor = EditorState(RowId("setup.xtream.server"), "Server", "", TextHint.URL, masked = false, chips = emptyList())
        val form = SettingsRoute.AddSource(app.thebox.core.settings.SourceKind.XTREAM, firstRun = true)
        val machine = FakeSettingsUiMachine(state().copy(route = form, editor = editor))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalKeyboardCheck provides { false }) {
                BoxTheme { SettingsScreen(machine, clock24 = true) }
            }
        }
        rule.onNodeWithTag(EDITOR_PHONE_TAG).performClick()
        rule.runOnIdle { assertEquals(listOf<SettingsRoute>(SettingsRoute.PhoneSetup(firstRun = true)), machine.pushed) }
    }

    /** U-L9 (M5 code review 2026-10-16): a context menu opened on a low row stays inside the pane (it ran past y 1080). */
    @Test
    fun contextMenuIsClampedToThePane() {
        val low = SettingsUi.PANE_H - 80
        val top = menuTop(low, options = 4)
        assertTrue("menu bottom ${top + 4 * SettingsUi.MENU_ROW_H + 24} past the pane", top + 4 * SettingsUi.MENU_ROW_H + 24 <= SettingsUi.PANE_Y + SettingsUi.PANE_H)
        assertEquals(SettingsUi.PANE_Y + 200, menuTop(200, options = 2))
    }
}
