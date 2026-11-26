package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.EnterReason
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.QuickItem
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.fakes.RecordingPlayerEffects
import app.thebox.core.full.state.PlayerKeysTableTest.Companion.panel
import app.thebox.core.full.state.PlayerWorld.Companion.archive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithArchive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.full.state.PlayerWorld.Companion.pastProgramme
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideRowsCache
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.sports.DayState
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The player's passive SETTINGS_PANEL layer (docs/design/m6-player-settings-panel.md §2.2, §5.8, §5.12, S2, N9): the
 * quick bar stays on Settings with `panelOpen` and no auto-hide, no info bar, no dim, the error card unfocused, PLAY_PAUSE
 * only, the layer survives `syncError` / `enterFull` / `closeAll`, and closing returns to the quick bar (8 s) or the card.
 */
class PlayerSettingsPanelLayerTest {

    private fun PlayerWorld.quickFocus(): QuickItem = ui.quick!!.let { it.items[it.focused] }

    private fun assertPanel(w: PlayerWorld) {
        assertEquals(PlayerLayer.SETTINGS_PANEL, w.ui.layer, "layer")
        val q = assertNotNull(w.ui.quick, "the quick bar stays under the panel")
        assertTrue(q.panelOpen, "panelOpen")
        assertEquals(QuickItem.SETTINGS, q.items[q.focused], "focus on Settings")
        assertNull(w.ui.info, "no info bar")
        assertNull(w.ui.scrub, "no scrub bar")
        assertNull(w.ui.picker, "no picker")
        assertNull(w.ui.channels, "no channel list")
    }

    @Test
    fun openFromQuickBarPublishesTheBarOnSettingsWithNoAutoHide() {
        val w = panel(liveWithArchive())
        assertPanel(w)
        assertEquals(Long.MAX_VALUE, w.player.nextDeadlineMs.value, "no quick-bar timer")
        w.advance(60_000)
        assertPanel(w)
    }

    @Test
    fun openFromBareVideoAsksTheBarsValues() {
        val w = liveWithArchive()
        w.favourites += w.tunedChannel!!.identityHash
        val mark = w.mark()
        w.player.settingsPanel(true) // the Settings key in FULL
        w.flush()
        assertPanel(w)
        assertTrue(w.fx.calls.drop(mark).any { it.name == "queryFavourite" }, "favourite asked")
        assertTrue(QuickItem.FAVOURITE_REMOVE in w.ui.quick!!.items, "the answer is accepted under the panel")
    }

    @Test
    fun openClosesPickerAndPlaceModeKeepingTheCorner() {
        val w = liveWithArchive().keys(ASPECT)
        assertEquals(PlayerLayer.PICKER, w.ui.layer)
        w.player.settingsPanel(true); w.flush()
        assertPanel(w)

        val p = liveWithArchive()
        p.player.scores(ScoreFacts())
        p.keys(UP)
        repeat(QuickItem.entries.size) { if (p.quickFocus() != QuickItem.SCORE_BUG) p.press(RIGHT) }
        p.keys(SELECT, SELECT) // the sheet (no game: Corner first), then place mode
        assertEquals(PlayerLayer.PLACE_SCORE, p.ui.layer)
        p.keys(LEFT)
        val mark = p.mark()
        p.player.settingsPanel(true); p.flush()
        assertPanel(p)
        assertTrue(p.fx.calls.drop(mark).any { it.name == "setScoreCorner" }, "the moved corner is kept (U-N2)")
    }

    @Test
    fun statsOverlayIsKept() {
        val w = panel(liveWithArchive().keys(STATS_OVERLAY).also { it.send(PlayerEvent.Stats(PlayerFixtures.stats())) })
        assertNotNull(w.ui.stats, "stats kept")
        assertTrue(w.ui.quick!!.statsOn)
    }

    @Test
    fun scoresReachTheBarUnderThePanel() {
        val w = panel(liveWithArchive())
        val facts = ScoreFacts(day = DayState.Board(SportsFixtures.board(SportsFixtures.game()), 0L, false))
        w.player.scores(facts)
        assertEquals(facts, w.ui.quick!!.score)
    }

    // ------------------------------------------------------------------ keys

    @Test
    fun onlyPlayPauseIsHandled() {
        for (a in BoxAction.entries - PLAY_PAUSE) {
            val w = panel(liveWithArchive())
            val before = w.ui
            val mark = w.mark()
            w.press(a, digit = if (a == DIGIT) 1 else -1)
            assertFalse(w.lastConsumed, "$a is not consumed")
            assertEquals(emptyList(), w.effectsSince(mark), "$a does nothing")
            assertEquals(before, w.ui, "$a changes nothing")
        }
        val w = panel(liveWithArchive())
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertTrue(w.lastConsumed)
        assertEquals(listOf("pauseLive"), w.effectsSince(mark))
        assertPanel(w) // no scrub bar under the panel
    }

    @Test
    fun pausedFrameIsNotDimmed() {
        val w = liveWithoutArchive().buffer()
        w.press(PLAY_PAUSE)
        check(w.status.source == TimelineSource.LOCAL && w.status.phase == ArchivePhase.PAUSED)
        panel(w)
        w.advance(10 * 60_000L)
        assertFalse(assertNotNull(w.ui.paused, "the paused card draws under the panel").dimmed, "not dimmed")
        // PLAY_PAUSE reaches the player (no wake-only press) and resumes.
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertEquals(listOf("archiveResume"), w.effectsSince(mark))
        // Closing counts as a key: the dim timer starts again, not at once.
        w.press(PLAY_PAUSE) // paused again
        w.advance(10 * 60_000L)
        w.player.settingsPanel(false); w.flush()
        assertFalse(w.ui.paused?.dimmed == true, "not dimmed right after closing")
    }

    // ------------------------------------------------------------------ survives (S2)

    @Test
    fun playbackFailureKeepsTheLayerWithAnUnfocusedCard() {
        val w = panel(liveWithoutArchive())
        w.fail(PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED))
        assertPanel(w)
        assertEquals(-1, assertNotNull(w.ui.error, "the card shows").focused, "unfocused")
        // Another code while open: still the panel.
        w.fail(PlayerFixtures.error(PlaybackErrorCode.CONNECTION_LIMIT))
        assertPanel(w)
        assertEquals(-1, w.ui.error!!.focused)
        // BACK out of the panel focuses the card on its first button.
        w.player.settingsPanel(false); w.flush()
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
        assertEquals(0, w.ui.error!!.focused)
        assertNull(w.ui.quick)
    }

    @Test
    fun openedFromTheErrorCardClosesBackToIt() {
        val w = liveWithoutArchive().also { it.fail(PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED)) }
        w.press(RIGHT) // a second button focused
        w.player.settingsPanel(true); w.flush()
        assertPanel(w)
        assertEquals(-1, w.ui.error!!.focused)
        w.player.settingsPanel(false); w.flush()
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
        assertEquals(1, w.ui.error!!.focused, "the card keeps its button")
    }

    @Test
    fun errorClearedWhileOpenClosesToTheQuickBar() {
        val w = liveWithoutArchive().also { it.fail(PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED)) }
        w.player.settingsPanel(true); w.flush()
        w.externalTune(w.channels[3]) // a reminder's switch, a PIN-granted tune
        assertPanel(w)
        assertNull(w.ui.error)
        w.player.settingsPanel(false); w.flush()
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        assertEquals(QuickItem.SETTINGS, w.quickFocus())
    }

    @Test
    fun enterFullKeepsTheLayer() {
        val w = panel(liveWithArchive())
        w.player.enterFull(w.nowMs, EnterReason.USER); w.flush()
        assertPanel(w)
        w.player.enterFull(w.nowMs, EnterReason.ARCHIVE); w.flush()
        assertPanel(w)
    }

    @Test
    fun channelChangeShowsNoPassiveBarAndRefreshesTheBar() {
        val w = panel(liveWithArchive())
        val next = w.channels[3]
        w.favourites += next.identityHash
        val mark = w.mark()
        w.externalTune(next)
        assertPanel(w)
        assertTrue(w.fx.calls.drop(mark).any { it.name == "queryFavourite" && it.args.getOrNull(1) == next }, "favourite asked for the new channel")
        assertTrue(QuickItem.FAVOURITE_REMOVE in w.ui.quick!!.items, "the bar's values follow the new channel")
        // Closing later shows no stale passive bar either.
        w.player.settingsPanel(false); w.flush()
        assertNull(w.ui.info)
    }

    @Test
    fun archiveSessionStartShowsNoScrubBar() {
        val w = panel(liveWithArchive())
        val ch = w.channels[0]
        w.startArchive(ch, w.pastProgramme(ch).rawStartMin * 60_000L, timeShift = false); w.flush()
        assertPanel(w)
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertEquals(listOf("archivePause"), w.effectsSince(mark))
        assertPanel(w)
    }

    @Test
    fun playPauseOverAnErrorCardDoesNothing() {
        val w = panel(liveWithoutArchive().also { it.fail(PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED)) }, fromQuick = false)
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertTrue(w.lastConsumed)
        assertEquals(emptyList(), w.effectsSince(mark))
    }

    @Test
    fun leaveFullEndsThePanel() {
        val w = panel(liveWithArchive())
        w.player.leaveFull()
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertNull(w.ui.quick)
        w.player.settingsPanel(false) // the guide's publish after the mode change: nothing more
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        w.player.enterFull(w.nowMs, EnterReason.USER); w.flush()
        assertEquals(PlayerLayer.NONE, w.ui.layer, "a later entry is not the panel")
    }

    // ------------------------------------------------------------------ close and idempotence

    @Test
    fun closeReturnsToTheQuickBarOnSettingsWithItsIdleTimer() {
        val w = panel(liveWithArchive())
        w.player.settingsPanel(false); w.flush()
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        val q = w.ui.quick!!
        assertFalse(q.panelOpen)
        assertEquals(QuickItem.SETTINGS, q.items[q.focused])
        assertEquals(w.nowMs + PlayerStateMachineImpl.QUICK_BAR_IDLE_MS, w.player.nextDeadlineMs.value)
        w.advance(PlayerStateMachineImpl.QUICK_BAR_IDLE_MS - 1)
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        w.advance(1)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
    }

    @Test
    fun repeatedFlagsChangeNothing() {
        val w = panel(liveWithArchive())
        val before = w.ui
        val mark = w.mark()
        repeat(3) { w.player.settingsPanel(true) }
        w.flush()
        assertEquals(before, w.ui)
        assertEquals(emptyList(), w.effectsSince(mark))
        w.player.settingsPanel(false)
        val closed = w.ui
        repeat(3) { w.player.settingsPanel(false) }
        assertEquals(closed, w.ui)
        // false on bare video (every guide dispatch) changes nothing.
        val bare = liveWithArchive()
        val b = bare.ui
        bare.player.settingsPanel(false)
        assertEquals(b, bare.ui)
    }

    // ------------------------------------------------------------------ N9: the guide answers from inside act

    /** A machine whose host opens the panel synchronously from `openSettings`, as PlayerEffectsImpl → guide does. */
    private class Reentrant {
        val fx = RecordingPlayerEffects()
        var now = PlayerWorld.NOW_MS
        lateinit var machine: PlayerStateMachineImpl
        val ch = PlayerWorld.DEFAULT_CHANNELS[1]
        init {
            val effects = object : PlayerEffects by fx {
                override fun openSettings() { fx.openSettings(); machine.settingsPanel(true) }
                override fun openSettings(route: SettingsRoute) { fx.calls += RecordingPlayerEffects.Call("openSettingsRoute", listOf(route)); machine.settingsPanel(true) }
            }
            machine = PlayerStateMachineImpl(effects, FakeGuideRowsCache(PlayerWorld.DEFAULT_CHANNELS), { now })
            machine.dispatch(PlayerEvent.Settings(PlayerSettings(), lowEnd = false))
            machine.dispatch(PlayerEvent.Tracks(PlayerFixtures.trackMenu()))
            machine.dispatch(PlayerEvent.Tuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.PLAYING), ch))
            machine.enterFull(now, EnterReason.USER)
        }
        fun press(a: BoxAction): Boolean = machine.act(GuideEvent.Act(a, 0, -1, 0))
    }

    @Test
    fun quickBarSettingsReentrantOpenLeavesThePanelWithNoTimer() {
        val r = Reentrant()
        r.press(QUICK_MENU)
        repeat(QuickItem.entries.size) { r.machine.ui.value.quick!!.let { q -> if (q.items[q.focused] != QuickItem.SETTINGS) r.press(RIGHT) } }
        assertTrue(r.press(SELECT))
        val ui = r.machine.ui.value
        assertEquals(PlayerLayer.SETTINGS_PANEL, ui.layer)
        assertTrue(ui.quick!!.panelOpen)
        assertEquals(QuickItem.SETTINGS, ui.quick!!.let { it.items[it.focused] })
        assertEquals(Long.MAX_VALUE, r.machine.nextDeadlineMs.value, "no quick-bar timer after act returns")
        assertTrue(r.fx.calls.any { it.name == "openSettings" })
    }

    @Test
    fun errorCardSettingsRouteReentrantOpenLeavesThePanel() {
        val r = Reentrant()
        val e = PlayerFixtures.error(PlaybackErrorCode.STREAM_INTERRUPTED)
        r.machine.dispatch(PlayerEvent.Tuned(TunedState(r.ch.identity, r.ch.rowId, r.ch.identityHash, MiniPhase.ERROR, error = e.code, errorDetail = e), r.ch))
        val card = assertNotNull(r.machine.ui.value.error)
        val i = card.buttons.indexOf(ErrorButton.OPEN_SETTINGS)
        check(i >= 0) { "STREAM_INTERRUPTED offers Settings: ${card.buttons}" }
        repeat(i) { r.press(RIGHT) }
        assertTrue(r.press(SELECT))
        val ui = r.machine.ui.value
        assertEquals(PlayerLayer.SETTINGS_PANEL, ui.layer)
        assertEquals(-1, ui.error!!.focused)
        r.machine.settingsPanel(false)
        assertEquals(PlayerLayer.ERROR, r.machine.ui.value.layer)
    }
}
