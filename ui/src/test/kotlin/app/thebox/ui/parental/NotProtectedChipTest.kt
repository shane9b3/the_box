package app.thebox.ui.parental

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import app.thebox.core.full.ListColumn
import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.SourceSection
import app.thebox.ui.full.ChannelListPanel
import app.thebox.ui.full.PlayerOverlayStates
import app.thebox.ui.guide.overlay.GroupsPanel
import app.thebox.ui.shelltest.FakePlayerHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U-S11 (M5 code review 2026-10-16): §3.9.1, in FILTERED_NO_PIN a locked group shows the amber `18+` chip (its door is
 * the not-protected card), and the padlock only while a PIN protects it. Groups panel and the player's group column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-xhdpi")
class NotProtectedChipTest {
    @get:Rule val rule = createComposeRule()

    private val entries = listOf(
        GuideSourceEntry(GuideSource.Group(1, "news"), "News", 10, SourceSection.GROUP, null),
        GuideSourceEntry(GuideSource.Group(1, "late"), "Late", 4, SourceSection.GROUP, null, locked = true, notProtected = true),
        GuideSourceEntry(GuideSource.Group(1, "kept"), "Kept", 3, SourceSection.GROUP, null, locked = true),
    )

    @Test
    fun filteredNoPinShowsTheAmber18BadgeInTheGroupsPanel() {
        val o = Overlay.Groups(listOf(GroupsShortcut.SEARCH), entries, focused = 0, current = 0, menu = null, menuFocused = 0)
        rule.setContent { BoxTheme { GroupsPanel(GuideUiState(overlay = o)) } }
        rule.onAllNodesWithTag(NOT_PROTECTED_CHIP_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(LOCK_GLYPH_TAG).assertCountEquals(1)
    }

    @Test
    fun filteredNoPinShowsTheAmber18BadgeInTheChannelList() {
        val s = PlayerOverlayStates.channels().copy(column = ListColumn.GROUPS, groups = entries)
        rule.setContent { BoxTheme { ChannelListPanel(s, FakePlayerHost(), nowMs = 0L) } }
        rule.onAllNodesWithTag(NOT_PROTECTED_CHIP_TAG).assertCountEquals(1)
        rule.onAllNodesWithTag(LOCK_GLYPH_TAG).assertCountEquals(1)
    }
}
