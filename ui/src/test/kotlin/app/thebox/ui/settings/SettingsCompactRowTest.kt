package app.thebox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ChoiceState
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.Tone
import app.thebox.core.settings.UiTextScale
import app.thebox.core.settings.tree.AboutRows
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Settings panel A5, §5.4, §5.5, §10 `SettingsCompactRowTest`) `SettingsRowView(…, compact = true)` at the panel's 464:
 * the value under the label, a toggle without a second line, masked dots, badges leading the value line, About without
 * the wordmark, option labels on 2 lines (N7), the account line wrapping (N8), `MoveModeRow` at 464 without its inline
 * hint (S9), the band's description-drop rule; and the full-screen row unchanged (76 / 104).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SettingsCompactRowTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() { UiTextScale.running = TextSize.DEFAULT }

    private val textSize = mutableStateOf(TextSize.DEFAULT)
    private val current = mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private fun str(id: Int) = rule.activity.resources.getString(id)

    private fun showRow(row: SettingsRow, focused: Boolean = false, choice: ChoiceState? = null, moving: Boolean = false, compact: Boolean = true) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            Box(Modifier.width(if (compact) SettingsPanelUi.INNER_W.dp else 1276.dp)) { SettingsRowView(row, focused, choice, moving, compact = compact) }
        }
        textSize.value = UiTextScale.running
        if (current.value == null) {
            current.value = content
            rule.setContent { BoxTheme { androidx.compose.runtime.key(textSize.value) { current.value?.invoke() } } }
        } else current.value = content
        rule.waitForIdle()
    }

    private fun node(tag: String): SemanticsNode = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
    private fun nodes(tag: String) = rule.onAllNodes(SemanticsMatcher("tag $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, true).fetchSemanticsNodes()
    private fun layoutOf(n: SemanticsNode): TextLayoutResult {
        val r = ArrayList<TextLayoutResult>(); n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(r); return r.single()
    }
    private fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
    private fun rowBounds(row: SettingsRow) = node(SETTINGS_ROW_TAG + row.id.key).boundsInRoot

    @Test
    fun valueUnderTheLabelAndTwoLineHeight() {
        for (size in listOf(TextSize.DEFAULT, TextSize.LARGEST)) {
            UiTextScale.running = size
            val row = SettingsRow(RowId("playback.buffer"), "Buffer", value = "Automatic", description = "How much is held", kind = RowKind.Choice(listOf(ChoiceOption("a", "Automatic")), 0), effect = EffectTiming.REPREPARE)
            showRow(row)
            val label = node(SETTINGS_PANEL_LABEL_TAG).boundsInRoot
            val value = node(SETTINGS_PANEL_VALUE_TAG)
            assertEquals("Automatic", text(value))
            assertTrue("$size: value under the label", value.boundsInRoot.top >= label.bottom - 0.5f)
            assertTrue("$size: value starts at the label's x", kotlin.math.abs(value.boundsInRoot.left - label.left) < 0.5f)
            val b = rowBounds(row)
            assertTrue("$size: at least 92 with a value line (${b.height})", b.height >= 91.5f)
            assertEquals("$size: 464 wide", 464f, b.width, 0.5f)
            // The description is not drawn in the row (it is the band's).
            assertTrue(rule.onAllNodes(SemanticsMatcher("desc") { text(it) == "How much is held" }, true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun toggleHasNoSecondLineAnd64() {
        val row = SettingsRow(RowId("playback.tunneling"), "Tunneled playback", description = "Can cause a black screen", kind = RowKind.Toggle(true), effect = EffectTiming.REPREPARE)
        showRow(row, focused = true)
        assertTrue(nodes(SETTINGS_PANEL_VALUE_TAG).isEmpty())
        assertEquals(64f, rowBounds(row).height, 0.5f)
        assertEquals(true, node(SETTINGS_ROW_TAG + row.id.key).config[SemanticsProperties.Selected])
    }

    @Test
    fun maskedTextShowsDots() {
        val row = SettingsRow(RowId("x.password"), "Password", kind = RowKind.Text("hunter2", TextHint.PASSWORD, masked = true))
        showRow(row)
        assertEquals(str(R.string.settings_ui_masked), text(node(SETTINGS_PANEL_VALUE_TAG)))
        assertTrue(rule.onAllNodes(SemanticsMatcher("secret") { text(it).contains("hunter2") }, true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun badgesLeadTheValueLine() {
        val row = SettingsRow(RowId("groups.g.1"), "Adult", value = "370 channels", kind = RowKind.Item(route = SettingsRoute.Groups(1), badges = setOf(Badge.HIDDEN, Badge.ADULT)))
        showRow(row)
        val value = node(SETTINGS_PANEL_VALUE_TAG).boundsInRoot
        val label = node(SETTINGS_PANEL_LABEL_TAG).boundsInRoot
        for (id in listOf(R.string.settings_ui_badge_hidden, R.string.settings_ui_badge_adult)) {
            val badge = rule.onAllNodes(SemanticsMatcher("badge") { text(it) == str(id) }, true).fetchSemanticsNodes().single().boundsInRoot
            assertTrue("badge before the value", badge.right <= value.left + 0.5f)
            assertTrue("badge under the label", badge.top >= label.bottom - 0.5f)
        }
    }

    @Test
    fun aboutHeaderHasNoWordmarkInThePanel() {
        val row = SettingsRow(AboutRows.HEADER, "The Box", value = "0.1.21 (121)", kind = RowKind.Info(emptyList()))
        showRow(row)
        val b = rowBounds(row)
        val label = node(SETTINGS_PANEL_LABEL_TAG).boundsInRoot
        assertEquals("the label starts at the row's padding: no wordmark before it", b.left + SettingsPanelUi.ROW_PAD_H, label.left, 0.5f)
    }

    @Test
    fun optionLabelsWrapToTwoLines() {
        val long = "Automatic (MPEG-TS first, then HLS when the panel offers it)"
        val row = SettingsRow(RowId("playlist.stream_format"), "Stream format", kind = RowKind.Choice(listOf(ChoiceOption("auto", long), ChoiceOption("ts", "MPEG-TS", detail = "The usual for Xtream panels")), 0))
        showRow(row, focused = true, choice = ChoiceState(row.id, 0))
        val labels = nodes(SETTINGS_PANEL_LABEL_TAG).filter { text(it) == long }
        val l = layoutOf(labels.single())
        assertEquals("N7: two lines", 2, l.lineCount)
        assertFalse("N7: not ellipsised", l.isLineEllipsized(1))
        val o0 = node("${SETTINGS_OPTION_TAG}${row.id.key}:0").boundsInRoot
        assertTrue("the option is indented 24", o0.left >= rowBounds(row).left + SettingsPanelUi.CHOICE_INDENT - 0.5f)
        assertTrue("with a detail: at least 76", node("${SETTINGS_OPTION_TAG}${row.id.key}:1").boundsInRoot.height >= 75.5f)
    }

    @Test
    fun accountLineWrapsToTwoLines() {
        val row = SettingsRow(
            RowId(ACCOUNT_ROW), "Account",
            kind = RowKind.Info(listOf("Active · expires in 212 days", "1 of 2 connections in use", "Panel time zone Europe/London, in sync"), Tone.GOOD),
        )
        showRow(row)
        val l = layoutOf(node(SETTINGS_PANEL_INFO_TAG))
        assertEquals("N8: the account line wraps to 2 lines", 2, l.lineCount)
        assertEquals(2, compactInfoLines(row))
        assertEquals(3, compactInfoLines(row.copy(id = RowId("diagnostics.device"))))
    }

    @Test
    fun moveModeRowAt464WithoutItsInlineHint() {
        val row = SettingsRow(RowId("groups.g.2"), "UK | Sports", value = "185 channels", kind = RowKind.Item(movable = true))
        showRow(row, moving = true)
        val m = node(MOVE_ROW_TAG)
        assertEquals(464, m.size.width)
        val hint = str(R.string.backup_move_hint)
        assertTrue("S9: no inline hint in the panel", rule.onAllNodes(SemanticsMatcher("hint") { text(it) == hint }, true).fetchSemanticsNodes().isEmpty())
        assertTrue("TalkBack still hears it", m.config[SemanticsProperties.ContentDescription].single().endsWith(hint))
        // Full screen: 1276 with the hint inline, as before.
        showRow(row, moving = true, compact = false)
        assertEquals(1276, node(SETTINGS_ROW_TAG + row.id.key).size.width) // full screen: the row tag and the move tag share one node
        assertEquals(1, rule.onAllNodes(SemanticsMatcher("hint") { text(it) == hint }, true).fetchSemanticsNodes().size)
    }

    @Test
    fun fullScreenRowsAreUnchanged() {
        val plain = SettingsRow(RowId("playback.afr"), "Match frame rate", kind = RowKind.Toggle(true))
        showRow(plain, compact = false)
        assertEquals(SettingsUi.ROW_H.toFloat(), rowBounds(plain).height, 0.5f)
        val described = plain.copy(description = "Switches the TV's refresh rate")
        showRow(described, compact = false)
        assertEquals(SettingsUi.ROW_H_DESC.toFloat(), rowBounds(described).height, 0.5f)
        assertTrue("full screen draws the description inline", rule.onAllNodes(SemanticsMatcher("d") { text(it) == "Switches the TV's refresh rate" }, true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun bandDropsATimingSentence() {
        val sep = " · "
        val sentences = listOf("Takes effect at the next channel change.", "Takes effect the next time you tune a channel")
        assertNull("a description that is only the NEXT_TUNE sentence", bandDescriptionOf("Takes effect the next time you tune a channel", sentences, sep))
        assertEquals("Sent with every request", bandDescriptionOf("Sent with every request · Takes effect at the next channel change.", sentences, sep))
        assertEquals("Sent with every request", bandDescriptionOf("Sent with every request", sentences, sep))
        assertEquals(str(R.string.setup_effect_next_tune), "Takes effect the next time you tune a channel")
    }
}
