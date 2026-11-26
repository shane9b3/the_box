package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (docs/decisions/2026-11-25-auto-format-retries-ts.md) Picking the selected option of a choice emits nothing, except an
 * option marked `reselectable` (Stream format's Auto while HLS is learned: picking it again forgets HLS).
 */
class SettingsChoiceReselectTest {
    private val effects = QueueEffects()
    private val m = SettingsMachineImpl(effects, { 0L }, SettingsTexts.KEYS, HintValidator(SettingsTexts.KEYS, WpaPages.rowRules(SettingsTexts.KEYS)))
    private val route = SettingsRoute.Playlist(1)

    private fun show(auto: ChoiceOption) {
        m.open(route, fromFull = false)
        val row = SettingsRow(RowId("playlist.stream_format"), "Stream format", kind = RowKind.Choice(listOf(auto, ChoiceOption("TS", "MPEG-TS"), ChoiceOption("HLS", "HLS")), 0))
        m.dispatch(SettingsEvent.Page(PageModel(route, "t", rows = listOf(row), version = 1)))
    }

    private fun pickTheSelectedOption() {
        m.act(GuideEvent.Act(BoxAction.SELECT)) // open the inline list, focused on the selected option
        m.act(GuideEvent.Act(BoxAction.SELECT))
    }

    @Test
    fun theSelectedOptionEmitsNothing() {
        show(ChoiceOption("AUTO", "Auto"))
        pickTheSelectedOption()
        assertTrue(effects.intents.isEmpty(), "${effects.intents}")
    }

    @Test
    fun aReselectableSelectedOptionEmitsItsChoice() {
        show(ChoiceOption("AUTO", "Auto", detail = "again", reselectable = true))
        pickTheSelectedOption()
        assertEquals(listOf<RowIntent>(RowIntent.Choose(RowId("playlist.stream_format"), "AUTO")), effects.intents)
    }
}
