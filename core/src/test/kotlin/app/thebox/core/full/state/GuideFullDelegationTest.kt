package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.EnterReason
import app.thebox.core.full.PillKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.state.PlayerWorld.Companion.pastProgramme
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.state.GuideWorld
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m4-player-ux.md §3.1.1 (guide machine + real player machine + fakes), §5.1 `GuideFullDelegationTest`. */
class GuideFullDelegationTest {
    private fun full(): PlayerWorld = PlayerWorld(withGuide = true)

    @Test fun playerTakesTheActionFirstAndTheGuideDoesNothing() {
        val w = full()
        val g = w.gx.calls.size
        w.press(UP)
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        assertEquals(ScreenMode.FULL, w.gui.mode)
        assertTrue(w.gx.calls.drop(g).none { it.name == "zap" }, "M3's FULL zap on UP is gone (owner ruling)")
    }

    @Test fun chPlusZapsToTheHigherNumber() {
        val w = full()
        w.press(ZAP_UP)
        assertEquals(1, w.fx.last("zap")!!.args[0])
        assertEquals(w.channels[1].identityHash, w.gui.tuned.identityHash)
    }

    @Test fun reminderCardThenGuideOverlayThenPlayer() {
        val w = full()
        w.press(DIGIT, digit = 1)
        assertIs<Overlay.Number>(w.gui.overlay)
        val before = w.ui
        w.press(UP)
        assertEquals("2", (w.gui.overlay as Overlay.Number).digits.takeLast(1).let { it }, "UP adjusts the digit (guide overlay first)")
        assertEquals(before, w.ui, "the player saw nothing")
        w.press(BACK)
        assertNull(w.gui.overlay)
        w.alert(); w.advance(600)
        val mark = w.mark()
        val ui = w.ui
        for (a in listOf(UP, DOWN, ZAP_UP, PLAY_PAUSE, INFO_BAR)) w.press(a)
        assertEquals(ui.layer, w.ui.layer, "the modal card takes every key")
        assertEquals(emptyList(), w.effectsSince(mark))
    }

    @Test fun backWithNothingOpenFallsThroughToReturnToGuide() {
        val w = full()
        w.press(STATS_OVERLAY); w.press(DOWN)
        w.press(BACK) // info
        w.press(BACK) // stats
        assertEquals(ScreenMode.FULL, w.gui.mode)
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.gui.mode)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertNull(w.ui.stats)
        assertEquals(false, w.fx.last("setStatsEnabled")!!.args[0])
    }

    @Test fun numberEntryOverAnOpenChannelListAndThePassiveBarAfterItsTune() {
        val w = full()
        w.press(SELECT)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
        w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 4)
        assertIs<Overlay.Number>(w.gui.overlay)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer, "the list stays under the number overlay")
        w.press(SELECT)
        assertEquals(w.channels[3].identityHash, w.gui.tuned.identityHash, "104 tuned through the guide")
        w.press(BACK) // close the list
        // S7: a tune from number entry shows the passive bar in FULL (here after the list closed, the next tune).
        w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 2); w.press(SELECT)
        val info = assertNotNull(w.ui.info, "passive bar after a guide-originated tune")
        assertFalse(info.active)
        assertEquals(102, info.channel?.number)
    }

    @Test fun exitInFullDrawsTheExitOverlay() {
        val w = full()
        w.press(EXIT)
        assertEquals(ScreenMode.FULL, w.gui.mode)
        assertIs<Overlay.Exit>(w.gui.overlay)
        assertFalse(w.guide!!.horizontalRepeat, "a guide overlay owns the keys")
    }

    @Test fun groupCycleStaysTheGuides() {
        val w = full()
        w.press(GROUP_NEXT)
        assertNotNull(w.gui.groupCard)
        assertEquals(ScreenMode.FULL, w.gui.mode)
    }

    @Test fun archiveFailedMarksSuspectAndProbingDoesNotEndThePendingFull() {
        val g = PlayerWorld(withGuide = true, enter = false)
        val c2 = g.channels[2]
        g.sendGuide(GuideEvent.Archive(ArchiveStatus(phase = ArchivePhase.PROBING, identityHash = c2.identityHash)))
        g.guide!!.dispatch(GuideEvent.SearchClosed(c2, tuned = true, archive = true))
        g.sendGuide(GuideEvent.Tuned(TunedState(c2.identity, c2.rowId, c2.identityHash, MiniPhase.ERROR, isArchive = true, error = PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE), c2))
        assertEquals(ScreenMode.GUIDE, g.gui.mode)
        assertFalse(c2.identityHash in g.guide!!.frame.suspectArchive.toList(), "a probe error marks nothing")
        g.sendGuide(GuideEvent.Tuned(TunedState(c2.identity, c2.rowId, c2.identityHash, MiniPhase.CONNECTING, isArchive = true), c2))
        assertEquals(ScreenMode.FULL, g.gui.mode, "PROBING kept the pending catch-up FULL")
        g.sendGuide(GuideEvent.Archive(ArchiveStatus(phase = ArchivePhase.FAILED, identityHash = c2.identityHash, failure = ArchiveFailure.NOT_IN_ARCHIVE)))
        assertTrue(c2.identityHash in g.guide!!.frame.suspectArchive.toList(), "FAILED(NOT_IN_ARCHIVE) → hollow ↺")
        assertEquals(ArchivePhase.FAILED, g.gui.archive?.phase, "GuideUiState.archive for the mini player")
    }

    @Test fun archiveFailedEndsThePendingFull() {
        val g = PlayerWorld(withGuide = true, enter = false)
        val c2 = g.channels[2]
        g.guide!!.dispatch(GuideEvent.SearchClosed(c2, tuned = true, archive = true))
        g.sendGuide(GuideEvent.Archive(ArchiveStatus(phase = ArchivePhase.FAILED, identityHash = c2.identityHash, failure = ArchiveFailure.UNSUPPORTED_MODE)))
        g.sendGuide(GuideEvent.Tuned(TunedState(c2.identity, c2.rowId, c2.identityHash, MiniPhase.CONNECTING, isArchive = true), c2))
        assertEquals(ScreenMode.GUIDE, g.gui.mode, "a FAILED session ended the wait")
    }

    @Test fun startFullEntersWithLaunch() {
        val g = PlayerWorld(withGuide = true, enter = false)
        g.sendGuide(GuideEvent.StartFull)
        assertEquals(ScreenMode.FULL, g.gui.mode, "something already plays: FULL at once")
        assertNotNull(g.ui.info, "LAUNCH shows the passive bar")
        assertFalse(g.ui.info!!.active)
    }

    @Test fun attachWhileFullCallsEnterFullWithAFreshClock() {
        val w = GuideWorld()
        w.press(SELECT)
        assertEquals(ScreenMode.FULL, w.ui.mode)
        w.nowMs += 90_000 // the host attaches between events: the machine's last curMs is stale
        val player = FakePlayerStateMachine()
        var enteredAt = 0L
        val spy = object : app.thebox.core.full.PlayerStateMachine by player {
            override fun enterFull(nowMs: Long, reason: EnterReason) { enteredAt = nowMs; player.enterFull(nowMs, reason) }
        }
        w.machine.full = spy
        assertEquals(listOf(EnterReason.LAUNCH), player.entered)
        assertEquals(w.nowMs, enteredAt)
    }

    /** U-L7: re-attaching the same player machine (activity recreation in FULL) does not re-enter FULL. */
    @Test fun reattachingTheSameMachineDoesNotReEnterFull() {
        val w = GuideWorld()
        w.press(SELECT)
        val player = FakePlayerStateMachine()
        w.machine.full = player
        assertEquals(listOf(EnterReason.LAUNCH), player.entered)
        w.machine.full = player
        assertEquals(listOf(EnterReason.LAUNCH), player.entered, "no second enterFull: open layers survive")
    }

    @Test fun openSettingsRoundTripFromTheQuickBar() {
        val w = full()
        w.press(UP)
        repeat(20) { if (w.ui.quick?.let { it.items[it.focused] } != app.thebox.core.full.QuickItem.SETTINGS) w.press(RIGHT) }
        w.press(SELECT)
        assertEquals(ScreenMode.SETTINGS, w.gui.mode)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        w.press(BACK)
        assertEquals(ScreenMode.FULL, w.gui.mode, "BACK from Settings opened in FULL returns to FULL")
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.gui.mode)
    }

    @Test fun horizontalRepeatPerState() {
        val w = full()
        assertFalse(w.guide!!.horizontalRepeat, "bare live video: long ←/→ cycle groups")
        w.press(UP)
        assertTrue(w.guide!!.horizontalRepeat, "a layer is open")
        w.press(BACK); w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.gui.mode)
        assertFalse(w.guide!!.horizontalRepeat, "never in GUIDE")
        val a = full()
        a.startArchive(a.channels[0], a.pastProgramme(a.channels[0]).rawStartMin * 60_000L, timeShift = false); a.flush()
        assertTrue(a.guide!!.horizontalRepeat, "archive video on screen")
    }

    @Test fun handlesMediaKeysInFull() {
        val w = full()
        for (a in listOf(PLAY_PAUSE, PLAY, PAUSE, SEEK_BACK, SEEK_FORWARD, JUMP_TO_LIVE)) assertTrue(w.guide!!.handles(a), "$a")
    }

    @Test fun errorCardGuideButtonReturnsToTheGuide() {
        val w = PlayerWorld(withGuide = true, tunedIndex = 1)
        w.fail(PlayerFixtures.error(PlaybackErrorCode.DNS_FAILURE))
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
        w.press(SELECT) // the only button: Guide
        assertEquals(ScreenMode.GUIDE, w.gui.mode)
    }

    /** WP-B: a FAILED session recovers to PLAYING behind the card when an owned Playing arrives: the card closes. */
    @Test fun failedArchiveRecoveryClosesTheCard() {
        val w = full()
        w.startArchive(w.channels[0], w.pastProgramme(w.channels[0]).rawStartMin * 60_000L, timeShift = false); w.flush()
        val ch = w.channels[0]
        val err = PlayerFixtures.error(PlaybackErrorCode.NO_DATA_TIMEOUT)
        w.setStatus(w.status.copy(phase = ArchivePhase.FAILED, failure = ArchiveFailure.PLAYBACK, error = err))
        w.fail(err, archive = true)
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
        val mark = w.mark()
        w.press(SELECT) // Retry on an archive error: ArchivePlayback.resume(), never controller.retry()
        assertEquals(listOf("archiveResume"), w.effectsSince(mark))
        w.setStatus(w.status.copy(phase = ArchivePhase.PLAYING, failure = null, error = null))
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.PLAYING, true, tuneId = w.status.ownedTuneId), ch); w.flush()
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertNull(w.ui.error)
    }

    /** B1: an owned archive Error never opens the ERROR layer, in either machine (hand-off hidden by ArchiveVisibility). */
    @Test fun handoffKeepsTunedPlaying() {
        val w = full()
        w.startArchive(w.channels[0], w.pastProgramme(w.channels[0]).rawStartMin * 60_000L, timeShift = false); w.flush()
        val owned = w.status.ownedTuneId
        w.press(RIGHT) // scrub bar open, mid-gesture
        assertEquals(PlayerLayer.SCRUB, w.ui.layer)
        // The controller's error reaches the VMs one emission before the session moves to PROBING (review B1 ordering).
        val ch = w.channels[0]
        val err = PlayerFixtures.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.ERROR, true, err.code, errorDetail = err, tuneId = owned), ch); w.flush()
        assertNotEquals(PlayerLayer.ERROR, w.ui.layer)
        assertEquals(PlayerLayer.SCRUB, w.ui.layer, "the scrub bar stays open")
        assertNotEquals(MiniPhase.ERROR, w.gui.tuned.phase)
        w.setStatus(w.status.copy(phase = ArchivePhase.PROBING, attempt = 2, attempts = 3, ownedTuneId = owned + 1))
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.CONNECTING, true, tuneId = owned + 1), ch); w.flush()
        assertNotEquals(PillKind.CONNECTING, w.ui.pill.kind)
        assertEquals(2 to 3, w.ui.probing)
        w.setStatus(w.status.copy(phase = ArchivePhase.PLAYING))
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.PLAYING, true, tuneId = owned + 1), ch); w.flush()
        assertEquals(PillKind.CATCH_UP, w.ui.pill.kind)
        assertEquals(PlayerLayer.SCRUB, w.ui.layer)
        assertFalse(w.channels[0].identityHash in w.guide!!.frame.suspectArchive.toList())
    }
}
