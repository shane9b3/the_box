package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.state.PlayerStateMachineImpl.Companion.INFO_AFTER_ZAP_MS
import app.thebox.core.full.state.PlayerStateMachineImpl.Companion.QUICK_BAR_IDLE_MS
import app.thebox.core.full.state.PlayerStateMachineImpl.Companion.SCRUB_AUTO_SHOW_MS
import app.thebox.core.full.state.PlayerWorld.Companion.archive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithArchive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3.2.1 info-bar timing with a fake clock (§5.1 `InfoBarTimingTest`). */
class InfoBarTimingTest {
    @Test fun passiveAfterZapUntilPlayingPlusFourSeconds() {
        val w = liveWithArchive()
        w.advance(10_000)
        assertNull(w.ui.info, "the enter bar hid")
        w.autoPlay = false
        w.press(ZAP_UP)
        assertFalse(assertNotNull(w.ui.info).active)
        assertEquals(102, w.ui.info!!.channel?.number, "the zap target at once")
        w.advance(3_000)
        assertNotNull(w.ui.info, "still connecting: the bar waits")
        w.play()
        w.advance(INFO_AFTER_ZAP_MS - 1)
        assertNotNull(w.ui.info)
        w.advance(1)
        assertNull(w.ui.info)
        assertEquals(Long.MAX_VALUE, w.player.nextDeadlineMs.value)
    }

    @Test fun passiveOnEnterAndOnAGuideOriginatedTune() {
        val w = liveWithArchive()
        assertFalse(assertNotNull(w.ui.info, "enterFull(USER) on live").active)
        w.advance(INFO_AFTER_ZAP_MS)
        assertNull(w.ui.info)
        w.externalTune(w.channels[3]) // S7: number entry / reminder switch go through the guide machine
        assertEquals(104, assertNotNull(w.ui.info).channel?.number)
        w.advance(INFO_AFTER_ZAP_MS)
        assertNull(w.ui.info)
    }

    @ParameterizedTest
    @ValueSource(longs = [3_000, 5_000, 8_000])
    fun activeHidesAfterTheSettingWithKeyResets(ms: Long) {
        val w = PlayerWorld(settings = PlayerSettings(infoBarMs = ms))
        w.press(DOWN)
        assertEquals(PlayerLayer.INFO, w.ui.layer)
        w.advance(ms - 1_000)
        w.press(DOWN) // every key restarts the timer
        w.advance(ms - 1)
        assertEquals(PlayerLayer.INFO, w.ui.layer)
        w.advance(1)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertNull(w.ui.info)
    }

    @Test fun replacedByTheNextZap() {
        val w = liveWithArchive()
        w.press(ZAP_UP)
        w.advance(3_000)
        w.press(ZAP_UP)
        assertEquals(103, w.ui.info!!.channel?.number)
        w.advance(3_999)
        assertNotNull(w.ui.info, "re-armed by the second zap")
        w.advance(1)
        assertNull(w.ui.info)
    }

    @Test fun errorDuringPassive() {
        val w = liveWithArchive()
        w.advance(10_000)
        w.autoPlay = false
        w.press(ZAP_UP)
        w.fail(PlayerFixtures.error(PlaybackErrorCode.CHANNEL_OFFLINE))
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
        assertNotNull(w.ui.info, "the passive bar stays over the card")
        w.advance(INFO_AFTER_ZAP_MS)
        assertNull(w.ui.info)
        assertEquals(PlayerLayer.ERROR, w.ui.layer)
    }

    @Test fun quickBarIdle() {
        val w = liveWithArchive()
        w.press(UP)
        w.advance(QUICK_BAR_IDLE_MS - 1)
        w.press(RIGHT)
        w.advance(QUICK_BAR_IDLE_MS - 1)
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        w.advance(1)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
    }

    @Test fun scrubAutoShowAfterArchiveStartAndStaysWhilePaused() {
        val w = archive()
        assertFalse(assertNotNull(w.ui.scrub).focused)
        w.advance(SCRUB_AUTO_SHOW_MS)
        assertNull(w.ui.scrub)
        val p = liveWithArchive()
        p.press(PLAY_PAUSE)
        assertEquals(ArchivePhase.PAUSED_LIVE, p.status.phase)
        p.advance(60_000)
        assertNotNull(p.ui.scrub, "stays while paused")
    }
}

/** §3.5.2 scrub interaction on the machine (§5.1 `ScrubModelTest` items that need the machine). */
class ScrubInteractionTest {
    @Test fun commitSixHundredMsAfterTheLastStepReArmedByRelease() {
        val w = archive()
        w.settleSeeks = false
        w.press(RIGHT)
        val playhead = w.ui.scrub!!.playheadMs
        w.press(LEFT, repeat = 1, heldMs = 100); w.press(LEFT, repeat = 2, heldMs = 1_200)
        w.advance(500)
        w.release(LEFT)
        w.advance(599)
        assertNull(w.fx.last("archiveSeek"), "Release re-armed the 600 ms")
        w.advance(1)
        val seek = assertNotNull(w.fx.last("archiveSeek"))
        assertEquals(playhead + 10_000 - 10_000 - 30_000, seek.args[0], "one commit for the whole gesture")
        assertEquals(1, w.fx.calls.count { it.name == "archiveSeek" })
        assertTrue(w.ui.scrub!!.seeking)
    }

    /**
     * (M4 integration 2026-10-15) A new machine (new PlayerViewModel) over an app-wide session that already settled seq 7:
     * its first seek must use seq 8, so the preview stays until the session echoes it (B3; I-M4-10 on the device).
     */
    @Test fun firstSeekOfANewMachineContinuesAfterTheSessionsSettledSeq() {
        val w = archive()
        w.settleSeeks = false
        w.send(PlayerEvent.Archive(w.archive(w.channels[0], ArchivePhase.PLAYING, w.status.wallMs, false, settledSeq = 7)))
        w.press(RIGHT)
        w.press(SELECT)
        val seq = w.fx.last("archiveSeek")!!.args[1] as Long
        assertEquals(8L, seq)
        assertNotNull(w.ui.scrub!!.previewMs, "seq 8 is not settled yet")
        assertTrue(w.ui.scrub!!.seeking)
        w.send(PlayerEvent.Archive(w.archive(w.channels[0], ArchivePhase.PLAYING, w.status.wallMs, false, settledSeq = 8)))
        assertNull(w.ui.scrub!!.previewMs)
    }

    /** B3: the preview clears when settledSeq reaches our seq, wherever the playhead landed (45 s before the target). */
    @Test fun previewClearsOnSettledSeq() {
        val w = archive()
        w.settleSeeks = false
        w.press(RIGHT); w.press(RIGHT, repeat = 5, heldMs = 1_500)
        w.press(SELECT)
        val target = w.fx.last("archiveSeek")!!.args[0] as Long
        val seq = w.fx.last("archiveSeek")!!.args[1] as Long
        assertNotNull(w.ui.scrub!!.previewMs)
        w.send(PlayerEvent.Archive(w.archive(w.channels[0], ArchivePhase.PLAYING, target - 45_000, false, settledSeq = seq - 1)))
        assertNotNull(w.ui.scrub!!.previewMs, "an older seq does not clear it")
        w.send(PlayerEvent.Archive(w.archive(w.channels[0], ArchivePhase.PLAYING, target - 45_000, false, settledSeq = seq)))
        assertNull(w.ui.scrub!!.previewMs)
        assertFalse(w.ui.scrub!!.seeking)
        w.press(RIGHT)
        assertEquals(target - 45_000 + 10_000, w.ui.scrub!!.previewMs, "the next step starts from the playhead")
    }

    @Test fun backCancelsWithoutAnEffectAndSelectCommitsAtOnce() {
        val w = archive()
        w.press(LEFT)
        val mark = w.mark()
        w.press(BACK)
        w.advance(1_000)
        assertEquals(emptyList(), w.effectsSince(mark))
        assertNull(w.ui.scrub!!.previewMs)
        w.press(LEFT); w.press(SELECT)
        assertNotNull(w.fx.last("archiveSeek"))
    }

    @Test fun previewAtTheLiveEdgeGoesLive() {
        val w = PlayerWorld(tunedIndex = 0).also { it.startArchive(it.channels[0], it.nowMs - 20_000, timeShift = true); it.flush() }
        w.press(RIGHT, repeat = 3, heldMs = 1_500)
        w.advance(600)
        assertNotNull(w.fx.last("jumpToLive"))
        assertNull(w.fx.last("archiveSeek"))
    }

    @Test fun clampsAtTheArchiveStart() {
        val w = archive()
        w.press(LEFT, repeat = 99, heldMs = 999_999_999)
        repeat(3_000) { w.press(LEFT, repeat = 100 + it, heldMs = 999_999_999) }
        val s = w.ui.scrub!!
        assertEquals(s.archiveFromMs, s.previewMs)
    }

    /** P-B1: a chip on the at-live bar starts the programme EPG-anchored, never as a device-clock rewind distance. */
    @Test fun chipFromTheAtLiveBarIsEpgAnchored() {
        val w = liveWithArchive()
        w.press(TIME_SHIFT)
        w.press(DOWN)
        val prev = assertNotNull(w.ui.scrub!!.prev, "the previous-programme chip")
        val mark = w.mark()
        w.press(SELECT)
        val names = w.effectsSince(mark)
        assertFalse("rewindLive" in names, "no rewindLive(now − start): $names")
        val call = assertNotNull(w.callsSince(mark).firstOrNull { it.name == "playProgramme" }, "playProgramme: $names")
        assertEquals(w.channels[0].identityHash, (call.args[0] as app.thebox.core.guide.GuideChannel).identityHash)
        assertEquals(prev.rawStartMin, (call.args[1] as app.thebox.core.guide.ProgrammeInfo).rawStartMin)
        assertEquals(true, call.args[2], "a time-shift session (continues past the programme)")
    }

    /** P-B1: PREV_PROGRAMME on the at-live bar takes the same EPG-anchored path. */
    @Test fun prevProgrammeFromTheAtLiveBarIsEpgAnchored() {
        val w = liveWithArchive()
        w.press(TIME_SHIFT)
        val mark = w.mark()
        w.press(PREV_PROGRAMME)
        val names = w.effectsSince(mark)
        assertFalse("rewindLive" in names, "no rewindLive(now − start): $names")
        val call = assertNotNull(w.callsSince(mark).firstOrNull { it.name == "playProgramme" }, "playProgramme: $names")
        assertTrue((call.args[1] as app.thebox.core.guide.ProgrammeInfo).endMin <= w.nowMin(), "a past programme")
        assertEquals(true, call.args[2])
    }

    /** U-S7 = N17: a synchronous failure of playProgramme from the info bar brings the bar back with the focus on now. */
    @Test fun syncArchiveFailureReturnsFocusToNow() {
        val w = liveWithArchive()
        w.failArchiveStarts = true
        w.press(RIGHT)
        assertEquals(PlayerLayer.INFO, w.ui.layer)
        val nowIndex = w.ui.info!!.nowIndex
        w.press(UP)
        assertEquals(nowIndex - 1, w.ui.info!!.focused, "focus on the past programme")
        w.press(SELECT)
        assertNotNull(w.fx.last("playProgramme"))
        assertEquals(PlayerLayer.NONE, w.ui.layer, "the bar closed at the launch")
        w.send(PlayerEvent.ArchiveStartFailed(app.thebox.core.catchup.archive.ArchiveFailure.OUTSIDE_ARCHIVE))
        assertEquals(PlayerLayer.INFO, w.ui.layer, "the bar is back")
        assertEquals(w.ui.info!!.nowIndex, w.ui.info!!.focused, "focus returned to now")
        assertFalse(w.ui.info!!.browsing)
        // A failure of a start that did not come from the bar (or after another key) changes nothing.
        w.press(BACK)
        w.send(PlayerEvent.ArchiveStartFailed(app.thebox.core.catchup.archive.ArchiveFailure.OUTSIDE_ARCHIVE))
        assertEquals(PlayerLayer.NONE, w.ui.layer)
    }

    @Test fun reScopesWithThePreviewAndHasTwoChipsOnly() {
        val w = archive()
        w.press(INFO_BAR)
        val first = w.ui.scrub!!.programme!!
        assertNotNull(w.ui.scrub!!.prev); assertNotNull(w.ui.scrub!!.next)
        w.press(RIGHT, repeat = 30, heldMs = 12_000) // +300 s
        w.press(RIGHT, repeat = 31, heldMs = 12_100)
        w.press(RIGHT, repeat = 32, heldMs = 12_200)
        w.press(RIGHT, repeat = 33, heldMs = 12_300)
        w.press(RIGHT, repeat = 34, heldMs = 12_400)
        w.press(RIGHT, repeat = 35, heldMs = 12_500) // +30 min: the next 30-min programme
        assertTrue(w.ui.scrub!!.programme!!.rawStartMin > first.rawStartMin, "re-scoped to the programme under the preview")
        w.press(DOWN)
        assertEquals(1, w.ui.scrub!!.chipFocused)
        w.press(RIGHT); assertEquals(1, w.ui.scrub!!.chipFocused, "no third chip")
    }
}

/** Remaining §5.1 / §5.0 rows: S2, S7, N12, list holds, pickers. */
class PlayerStateMachineTest {
    /** S2: during an owned hand-off an open picker keeps its choices and focus. */
    @Test fun pickerSurvivesHandoff() {
        val w = archive()
        w.press(AUDIO); w.press(DOWN)
        val ch = w.channels[0]
        w.setStatus(w.status.copy(phase = ArchivePhase.CONTINUING, ownedTuneId = w.status.ownedTuneId + 1))
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.CONNECTING, true, tuneId = w.status.ownedTuneId), ch)
        w.flush()
        assertTrue(w.player.ui.value.pill.kind != app.thebox.core.full.PillKind.CONNECTING, "the hand-off is hidden (B1)")
        w.send(PlayerEvent.Tracks(TrackMenu()))
        assertEquals(PlayerLayer.PICKER, w.ui.layer)
        assertEquals(2, w.ui.picker!!.choices.size)
        assertEquals(1, w.ui.picker!!.focused)
        w.send(PlayerEvent.Tracks(PlayerFixtures.trackMenu().let { m -> m.copy(audio = m.audio.reversed()) }))
        assertEquals("de", (w.ui.picker!!.choices[w.ui.picker!!.focused] as app.thebox.core.full.PickerChoice.Audio).track.language, "focus kept by TrackKey")
    }

    @Test fun pickerClosesWhenTheIdentityChanges() {
        val w = liveWithArchive()
        w.press(SUBTITLES)
        assertEquals(PickerKind.SUBTITLES, w.ui.picker?.kind)
        w.externalTune(w.channels[1])
        assertNull(w.ui.picker)
    }

    @Test fun subtitlesPickerListsSpeculativeCaptionsLastAfterOff() {
        val w = liveWithArchive()
        w.press(SUBTITLES)
        val c = w.ui.picker!!.choices
        assertEquals(app.thebox.core.full.PickerChoice.TextOff, c[0])
        assertTrue((c.last() as app.thebox.core.full.PickerChoice.Text).track.speculative)
    }

    /** S7: OK on a future programme in the info bar sets a reminder. */
    @Test fun okOnFutureSetsReminder() {
        val w = liveWithArchive()
        w.press(DOWN); w.press(DOWN)
        w.press(SELECT)
        val call = assertNotNull(w.fx.last("toggleReminder"))
        assertTrue((call.args[1] as app.thebox.core.guide.ProgrammeInfo).startMin > w.nowMin())
    }

    @Test fun infoEarlierStopsAtTheArchiveOrAtNow() {
        val plain = liveWithoutArchive().keys(DOWN)
        val now = plain.ui.info!!.focused
        plain.press(UP)
        assertEquals(now, plain.ui.info!!.focused, "no archive: stops at now")
        val arch = liveWithArchive().keys(DOWN)
        repeat(30) { arch.press(UP) }
        val p = arch.ui.info!!.programmes[arch.ui.info!!.focused]
        assertTrue(p.rawStartMin >= arch.nowMin() - 7 * 1440, "inside the archive")
        assertTrue(arch.fx.calls.count { it.name == "loadProgrammes" } >= 2, "paged at the edge (INFO_EDGE)")
    }

    @Test fun noEpgInfoBarOkOpensTheQuickBar() {
        val w = PlayerWorld(tunedIndex = 5)
        w.press(DOWN)
        assertTrue(w.ui.info!!.programmes.isEmpty())
        assertEquals(-1, w.ui.info!!.focused)
        w.press(SELECT)
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
    }

    /** N12: Stop on the card, then OK plays again. */
    @Test fun stopThenOkRetries() {
        val w = liveWithoutArchive()
        w.fail(PlayerFixtures.error(PlaybackErrorCode.CONNECTION_LIMIT))
        w.press(RIGHT); w.press(SELECT)
        assertEquals(MiniPhase.STOPPED, w.tunedRaw.phase)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        w.press(SELECT)
        assertNotNull(w.fx.last("retry"))
    }

    @Test fun listHoldAccelerates() {
        val w = PlayerWorld(channels = List(60) { app.thebox.core.guide.fakes.GuideFixtures.channel(it, number = 101 + it) })
        w.press(SELECT)
        for (r in 0 until 8) w.press(DOWN, repeat = r)
        assertEquals(8, w.ui.channels!!.focused)
        w.press(DOWN, repeat = 8); assertEquals(11, w.ui.channels!!.focused)
        w.press(DOWN, repeat = 20); assertEquals(21, w.ui.channels!!.focused)
        val ensure = w.fx.last("ensureListRows")!!
        assertEquals(w.ui.channels!!.top - 6, ensure.args[0])
        assertEquals(w.ui.channels!!.top + 18, ensure.args[1])
    }

    @Test fun favouritesListOpensOnTheFavouritesLineup() {
        val w = liveWithArchive()
        w.favourites += w.channels[3].identityHash
        w.press(FAVOURITES)
        assertEquals(w.favSource, w.ui.channels!!.source)
        assertEquals(1, w.ui.channels!!.size)
        assertEquals(0, w.ui.channels!!.focused)
    }

    @Test fun statsOffWhenLeavingFull() {
        val w = liveWithArchive()
        w.press(STATS_OVERLAY)
        assertNotNull(w.ui.stats)
        w.player.leaveFull()
        assertNull(w.ui.stats)
        assertEquals(false, w.fx.last("setStatsEnabled")!!.args[0])
    }
}

/** P-S4 = U-B3: after M2's 10 s live-pause release the visible phase is PAUSED (the mapper), so the keys resume. */
class LivePauseReleaseTest {
    private fun released(w: PlayerWorld) {
        val ch = w.tunedChannel!!
        w.setTuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.PAUSED, false), ch)
        w.flush()
    }

    @Test fun playPauseAfterTheReleaseResumesPlain() {
        val w = liveWithoutArchive()
        w.press(PLAY_PAUSE)
        assertNotNull(w.fx.last("pausePlain"))
        w.advance(12_000)
        released(w)
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertEquals(listOf("resumePlain"), w.effectsSince(mark).filter { it == "resumePlain" || it == "retry" || it == "pausePlain" })
        released(w)
        val m2 = w.mark()
        w.press(PLAY)
        assertTrue("resumePlain" in w.effectsSince(m2))
    }

    @Test fun selectAfterTheReleaseIsNotTheStoppedHint() {
        val w = liveWithoutArchive()
        w.press(PLAY_PAUSE)
        w.advance(12_000)
        released(w)
        val mark = w.mark()
        w.press(SELECT)
        assertFalse("retry" in w.effectsSince(mark), "the N12 'Press OK to play again' path is for real stops only")
    }
}
