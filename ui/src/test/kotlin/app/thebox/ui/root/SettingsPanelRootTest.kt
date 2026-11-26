package app.thebox.ui.root

import app.thebox.core.full.PlayerUiState
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.sports.ScoreBugHostFacts
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.full.scorePlacementInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (Settings panel A1, docs/design/m6-player-settings-panel.md §2.6, §5.9, §5.11) The root glue that is plain Kotlin:
 * the score bug avoids the panel's box, the reminder card moves beside it, and the posted expand collector (S10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPanelRootTest {

    @Test
    fun theScoreBugAvoidsThePanelAsItAvoidsThePicker() {
        val prefs = SportsPrefs()
        val facts = ScoreBugHostFacts()
        assertFalse(scorePlacementInput(PlayerUiState(), GuideUiState(mode = ScreenMode.FULL), prefs, facts, textTrackOn = false).picker)
        assertTrue(scorePlacementInput(PlayerUiState(), GuideUiState(mode = ScreenMode.FULL, settingsPanel = true), prefs, facts, textTrackOn = false).picker)
    }

    @Test
    fun theReminderCardSitsBesideThePanel() {
        assertEquals(PlayerUi.REMINDER_R, reminderEndFor(ScreenMode.FULL, pickerOpen = false), 0f)
        // AppRoot passes `pickerOpen || panelShown` (the panel shown over FULL).
        assertEquals(PlayerUi.REMINDER_R_BESIDE_PICKER, reminderEndFor(ScreenMode.FULL, pickerOpen = true), 0f)
        assertEquals(PlayerUi.REMINDER_R, reminderEndFor(ScreenMode.GUIDE, pickerOpen = true), 0f)
    }

    @Test
    fun theExpandCollectorDispatchesOncePerExpandingPanelRoute() = runTest(UnconfinedTestDispatcher()) {
        val ui = MutableStateFlow(SettingsUiState())
        val sent = ArrayList<GuideEvent>()
        val job = launch { collectPanelExpands(ui) { sent += it } }
        // FULL_SCREEN never expands; nor does a panel route the panel can show.
        ui.value = SettingsUiState(route = SettingsRoute.AddSource())
        ui.value = SettingsUiState(route = SettingsRoute.Root, presentation = SettingsPresentation.PANEL)
        ui.value = ui.value.copy(route = SettingsRoute.Section(SectionId.PLAYLISTS))
        assertTrue(sent.isEmpty())
        // A page source pushes AddSource while the panel is shown: one ExpandSettings.
        ui.value = ui.value.copy(route = SettingsRoute.AddSource())
        assertEquals(listOf<GuideEvent>(GuideEvent.ExpandSettings), sent)
        // Unrelated changes on the same route do not repeat it.
        ui.value = ui.value.copy(cursor = 3)
        assertEquals(1, sent.size)
        // The guide expands (FULL_SCREEN), then a later visit's panel reaches another expanding route: one more.
        ui.value = ui.value.copy(presentation = SettingsPresentation.FULL_SCREEN)
        ui.value = SettingsUiState(route = SettingsRoute.Playlist(1), presentation = SettingsPresentation.PANEL)
        ui.value = ui.value.copy(route = SettingsRoute.PhoneSetup())
        assertEquals(2, sent.size)
        assertTrue(sent.all { it == GuideEvent.ExpandSettings })
        job.cancel()
    }
}
