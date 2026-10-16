package app.thebox.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.state.SettingsShellEvent
import app.thebox.ui.root.AppRoot
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.root.SETTINGS_TAG
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AppRoot's SETTINGS slot (docs/design/m5-setup-settings.md §3.1, §3.2): the screen, the guide link and shell events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SettingsRootTest {
    @get:Rule val rule = createComposeRule()

    private val route = SettingsRoute.Section(SectionId.GUIDE)
    private val ui = SettingsUiState(
        route = route,
        stack = listOf(SettingsRoute.Root, route),
        sections = SectionId.entries.toList(),
        page = PageModel(route, "TV guide", rows = listOf(SettingsRow(RowId("guide.interval"), "Guide update", kind = RowKind.Info(emptyList()))), version = 1),
    )

    @Test
    fun settingsScreenDrawsLinksTheGuideAndForwardsShellEvents() {
        val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.SETTINGS))
        val sm = FakeSettingsUiMachine(ui)
        guide.settings = sm
        var exits = 0
        rule.setContent { BoxTheme { AppRoot(FakeShellHost(guide), onExit = { exits++ }, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SETTINGS_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_PAGE_TAG).assertExists()
        assertNotNull("AppRoot links the guide's dispatch", sm.guide)

        rule.runOnIdle { sm.post(SettingsShellEvent.Toast("Secure DNS is not reachable; using your normal DNS")) }
        rule.onNodeWithText("Secure DNS is not reachable; using your normal DNS", useUnmergedTree = true).assertExists()
        rule.runOnIdle { sm.post(SettingsShellEvent.Exit) }
        rule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun withoutASettingsMachineTheLayerStillCoversTheHole() {
        val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.SETTINGS))
        rule.setContent { BoxTheme { AppRoot(FakeShellHost(guide), grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SETTINGS_TAG).assertExists()
        rule.onNodeWithTag(SETTINGS_PAGE_TAG).assertDoesNotExist()
        assertNull(guide.settings)
    }
}
