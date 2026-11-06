package app.thebox.core.full.state

import app.thebox.core.full.BackInPlayer
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.state.PlayerWorld.Companion.pastProgramme
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (reviewed 2026-10-15, S10) The composed guide + player machine has no dead ends: over {no overlay, Number(preview),
 * Exit hint, Exit dialog} × {reminder card, none} × every [PlayerLayer] × {live, archive} × [BackInPlayer] (with stats on),
 * BACK reaches `mode == GUIDE` (or the exit dialog, or exits from the Exit hint) within 6 presses; with the card shown
 * no key reaches the player (neither `PlayerUiState` nor player effects change); after `returnToGuide` the player is at
 * layer NONE with stats off. Combinations a remote cannot build (INFO on archive video: the bar there is the scrub bar;
 * a guide overlay over SCREEN_OFF: any key only wakes) are skipped and counted.
 */
class ComposedFullBackLadderTest {
    enum class GuideOverlay { NONE, NUMBER, EXIT_HINT, EXIT_DIALOG }

    private fun setUp(o: GuideOverlay, card: Boolean, layer: PlayerLayer, archive: Boolean, back: BackInPlayer): PlayerWorld? {
        val gs = GuideSettings(exitConfirmation = if (o == GuideOverlay.EXIT_DIALOG) ExitConfirmation.ASK else ExitConfirmation.BACK_TWICE)
        val w = PlayerWorld(withGuide = true, tunedIndex = if (layer == PlayerLayer.ERROR) 1 else 0, settings = PlayerSettings(back = back), guideSettings = gs)
        if (archive) {
            val ch = w.tunedChannel!!
            if (ch.archiveDays == 0) return null
            w.startArchive(ch, w.pastProgramme(ch).rawStartMin * 60_000L, timeShift = false); w.flush()
        }
        w.press(STATS_OVERLAY)
        when (layer) {
            PlayerLayer.NONE -> Unit
            PlayerLayer.INFO -> if (archive) return null else w.press(DOWN)
            PlayerLayer.SCRUB -> w.press(if (archive) INFO_BAR else TIME_SHIFT)
            PlayerLayer.CHANNELS -> w.press(SELECT)
            PlayerLayer.QUICK_BAR -> w.press(UP)
            PlayerLayer.PICKER -> w.press(ASPECT)
            PlayerLayer.ERROR -> w.fail(PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED), archive = false)
            PlayerLayer.SCREEN_OFF -> w.press(BoxAction.SCREEN_OFF)
            PlayerLayer.PLACE_SCORE -> return null // (score bug commit 0) not reachable until WP-4b's place mode
        }
        if (w.ui.layer != layer) return null
        when (o) {
            GuideOverlay.NONE -> Unit
            GuideOverlay.NUMBER -> { w.press(DIGIT, digit = 1); w.advance(200) }
            GuideOverlay.EXIT_HINT, GuideOverlay.EXIT_DIALOG -> w.press(EXIT)
        }
        val ov = w.gui.overlay
        val ok = when (o) {
            GuideOverlay.NONE -> ov == null
            GuideOverlay.NUMBER -> ov is Overlay.Number && ov.preview != null
            GuideOverlay.EXIT_HINT -> ov is Overlay.Exit && !ov.dialog
            GuideOverlay.EXIT_DIALOG -> ov is Overlay.Exit && ov.dialog
        }
        if (!ok || w.ui.layer != layer || w.gui.mode != ScreenMode.FULL) return null
        if (card) { w.alert(); w.advance(600) }
        return w
    }

    @Test
    fun backReachesTheGuideWithinSixPresses() {
        var tested = 0
        var skipped = 0
        val failures = ArrayList<String>()
        for (o in GuideOverlay.entries) for (card in listOf(false, true)) for (layer in PlayerLayer.entries) for (archive in listOf(false, true)) for (back in BackInPlayer.entries) {
            val name = "$o/card=$card/$layer/archive=$archive/$back"
            val w = setUp(o, card, layer, archive, back)
            if (w == null) { skipped++; continue }
            tested++
            if (card) {
                val ui = w.ui
                val mark = w.mark()
                for (a in listOf(UP, DOWN, ZAP_UP, PLAY_PAUSE, INFO_BAR, DIGIT, SEEK_BACK, STATS_OVERLAY)) w.press(a, digit = 1)
                if (w.ui != ui || w.effectsSince(mark).isNotEmpty()) failures += "$name: a key reached the player under the card"
            }
            var presses = 0
            while (presses < 6 && w.gui.mode != ScreenMode.GUIDE && !w.exited && !(w.gui.overlay as? Overlay.Exit)?.dialog.let { it == true }) {
                w.press(BACK); presses++
            }
            val ended = w.gui.mode == ScreenMode.GUIDE || w.exited || (w.gui.overlay as? Overlay.Exit)?.dialog == true
            if (!ended) { failures += "$name: still ${w.gui.mode}/${w.ui.layer}/${w.gui.overlay} after 6 BACKs"; continue }
            if (w.gui.mode == ScreenMode.GUIDE) {
                if (w.ui.layer != PlayerLayer.NONE) failures += "$name: player layer ${w.ui.layer} after returnToGuide"
                if (w.ui.stats != null || w.fx.last("setStatsEnabled")?.args?.get(0) != false) failures += "$name: stats left on"
            }
        }
        assertTrue(failures.isEmpty(), "composed back ladder: ${failures.size} failures, e.g. ${failures.take(8)}")
        assertTrue(tested >= 150, "tested $tested combinations (skipped $skipped)")
    }

    @Test
    fun settingsFromTheQuickBarReturnsToFull() {
        val w = PlayerWorld(withGuide = true)
        w.press(UP)
        repeat(20) { if (w.ui.quick?.let { it.items[it.focused] } != app.thebox.core.full.QuickItem.SETTINGS) w.press(RIGHT) }
        w.press(SELECT)
        assertEquals(ScreenMode.SETTINGS, w.gui.mode)
        w.press(BACK)
        assertEquals(ScreenMode.FULL, w.gui.mode)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertTrue(w.ui.info?.active == false, "re-entered FULL with the passive bar (USER)")
    }
}
