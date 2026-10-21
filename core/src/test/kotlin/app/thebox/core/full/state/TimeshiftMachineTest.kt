package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.PausedHeader
import app.thebox.core.full.PillKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.ScrubScope
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithArchive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.guide.Overlay
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.TimeshiftConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The time-shift rows of the player machine (docs/design/m6-timeshift.md §11.1–§11.6, §17.3) beyond the key table:
 * the rewind-window bar, the paused header, the hint row, the idle hide, the dim card, the zap confirm (single keys
 * and number entry through the guide machine), the clamp toasts and the per-channel buffer.
 */
class TimeshiftMachineTest {
    private fun buffered(): PlayerWorld = liveWithoutArchive().buffer()

    private fun PlayerWorld.toasts(mark: Int): List<PlayerToast> = callsSince(mark).filter { it.name == "toast" }.map { it.args[0] as PlayerToast }

    /** What the key did, without its toasts. */
    private fun PlayerWorld.did(mark: Int): List<String> = effectsSince(mark).filter { it != "toast" }

    @Test fun pauseOnABufferedChannelIsLocalWithAGrowingPausedPill() {
        val w = buffered().keys(PLAY_PAUSE)
        assertEquals(TimelineSource.LOCAL, w.status.source)
        assertEquals(ArchivePhase.PAUSED, w.status.phase)
        w.advance(30_000)
        val pill = w.ui.pill
        assertEquals(PillKind.PAUSED, pill.kind)
        assertTrue(pill.behindGrows, "PAUSED · 0:30 behind grows")
        assertEquals(30_000L, pill.behindMs + (w.nowMs - pill.atMs))
        w.press(PLAY_PAUSE)
        assertEquals(PillKind.BEHIND, w.ui.pill.kind, "resume continues behind live")
        assertFalse(w.ui.pill.behindGrows)
    }

    /** §11.4 (U-S3): no catch-up → the bar spans the rewind window, the saved part from the right. */
    @Test fun withoutCatchUpTheBarSpansTheRewindWindow() {
        val w = buffered().keys(PLAY_PAUSE, INFO_BAR)
        val s = assertNotNull(w.ui.scrub)
        assertEquals(ScrubScope.REWIND_WINDOW, s.scope)
        val from = w.bufferInfo!!.fromMs
        assertEquals(from, s.savedFromMs)
        assertEquals(s.liveEdgeMs - 30 * 60_000L, s.windowFromMs, "the cycle's own 30 min window")
        assertEquals(s.windowFromMs, s.barFromMs)
        assertEquals(s.liveEdgeMs, s.barToMs)
        assertEquals(PausedHeader.SAVING, s.header)
    }

    /** Fix pass S1: the width is the cycle's [LocalBufferInfo.windowMs]; it never rescales as more is saved. */
    @Test fun theWindowWidthComesFromTheBufferAndNeverRescales() {
        val w = liveWithoutArchive().buffer(savedMs = 60_000, windowMs = 22 * 60_000L).keys(PLAY_PAUSE, INFO_BAR)
        assertEquals(22 * 60_000L, w.ui.scrub!!.barToMs - w.ui.scrub!!.barFromMs, "\"Auto (22 min: storage is limited)\" is a 22 min bar")
        w.advance(15 * 60_000L)
        w.buffer(fromMs = w.bufferInfo!!.fromMs, windowMs = 22 * 60_000L)
        assertEquals(22 * 60_000L, w.ui.scrub!!.barToMs - w.ui.scrub!!.barFromMs, "16 min saved: still 22 min")
    }

    @Test fun withCatchUpTheBarStaysTheProgramme() {
        val w = liveWithArchive().buffer().keys(PLAY_PAUSE, INFO_BAR)
        val s = assertNotNull(w.ui.scrub)
        assertEquals(ScrubScope.PROGRAMME, s.scope)
        assertEquals(TimelineSource.LOCAL, w.status.source, "local first on a catch-up channel too")
    }

    @Test fun pausedHeaderLines() {
        assertEquals(PausedHeader.SAVING, ScrubModel.pausedHeader(null, catchUp = false))
        assertEquals(PausedHeader.FULL, ScrubModel.pausedHeader(RecordStop.FULL_WHILE_PAUSED, catchUp = false))
        assertEquals(PausedHeader.HOLD_LIMIT, ScrubModel.pausedHeader(RecordStop.HOLD_LIMIT, catchUp = false))
        assertEquals(PausedHeader.SLOT_LOST, ScrubModel.pausedHeader(RecordStop.SLOT_LOST, catchUp = false))
        assertEquals(PausedHeader.SLOT_LOST, ScrubModel.pausedHeader(RecordStop.PREEMPTED, catchUp = false))
        for (stop in listOf(RecordStop.FULL_WHILE_PAUSED, RecordStop.HOLD_LIMIT, RecordStop.SLOT_LOST)) {
            assertEquals(PausedHeader.CATCHUP_COVERS, ScrubModel.pausedHeader(stop, catchUp = true), "$stop on a catch-up channel")
        }
        assertNull(ScrubModel.pausedHeader(RecordStop.DISK_ERROR, catchUp = false), "storage stops toast instead")
        // Playing behind live: no header.
        val w = buffered().keys(PLAY_PAUSE, INFO_BAR, SELECT)
        assertNull(w.ui.scrub?.header)
    }

    /** §11.6: the hint row the first HINT_SHOW_COUNT times the bar takes focus. */
    @Test fun hintRowFirstThreeOpeningsOnly() {
        val w = buffered().keys(PLAY_PAUSE)
        val shown = (1..5).map {
            w.press(INFO_BAR)
            val h = w.ui.scrub?.hint == true
            w.press(BACK)
            h
        }
        assertEquals(listOf(true, true, true, false, false), shown)
    }

    /** §11.6: a SELECT that resumes arms SCRUB_FOCUSED_IDLE_MS; then the bar hides and OK opens the channel list. */
    @Test fun focusedBarHidesAfterAnIdleResume() {
        val w = buffered().keys(PLAY_PAUSE, INFO_BAR, SELECT)
        assertEquals(PlayerLayer.SCRUB, w.ui.layer)
        w.advance(TimeshiftConstants.SCRUB_FOCUSED_IDLE_MS - 1)
        assertEquals(PlayerLayer.SCRUB, w.ui.layer)
        w.advance(1)
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        w.press(SELECT)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
    }

    @Test fun aKeyCancelsTheIdleHide() {
        val w = buffered().keys(PLAY_PAUSE, INFO_BAR, SELECT)
        w.advance(4_000)
        w.press(DOWN) // chips
        w.advance(10_000)
        assertEquals(PlayerLayer.SCRUB, w.ui.layer)
    }

    /** §11.5: after PAUSED_DIM_AFTER_MS without a key the frame dims; the next key only restores it. */
    @Test fun dimCardAndTheConsumedKey() {
        val w = buffered().keys(PLAY_PAUSE)
        assertNotNull(w.ui.paused?.pausedSinceMs)
        w.advance(TimeshiftConstants.PAUSED_DIM_AFTER_MS - 1)
        assertFalse(w.ui.paused!!.dimmed)
        w.advance(1)
        assertTrue(w.ui.paused!!.dimmed)
        assertEquals(w.nowMs, w.ui.paused!!.dimSinceMs)
        val mark = w.mark()
        w.press(SELECT)
        assertTrue(w.callsSince(mark).isEmpty(), "the key only wakes")
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertFalse(w.ui.paused!!.dimmed)
        // Fix pass B1: the screen says "OK or ▶ to continue", so the next OK continues (it never opens the list).
        val m2 = w.mark()
        w.press(SELECT)
        assertEquals(listOf("archiveResume"), w.did(m2), "the next OK resumes")
        assertEquals(PlayerLayer.NONE, w.ui.layer)
        assertEquals(ArchivePhase.PLAYING, w.status.phase)
        // Resumed: no dim, whatever the time.
        w.advance(10 * 60_000L)
        assertNull(w.ui.paused)
        // Playing behind live, OK on bare video is the channel list again (§11.6).
        w.press(SELECT)
        assertEquals(PlayerLayer.CHANNELS, w.ui.layer)
    }

    /** Fix pass B1: a media-key pause shows the unfocused bar; OK continues (LOCAL and a provider pause alike). */
    @Test fun okOnAPausedTimeShiftWithTheBarUnfocusedOrClosedContinues() {
        val media = buffered().keys(PLAY_PAUSE)
        assertFalse(media.ui.scrub!!.focused, "a media-key pause: the unfocused bar, layer NONE")
        var mark = media.mark()
        media.press(SELECT)
        assertEquals(listOf("archiveResume"), media.did(mark))
        // The focused bar (↑ OK), closed with Back: OK continues.
        val closed = buffered().keys(UP, SELECT, BACK)
        assertEquals(PlayerLayer.NONE, closed.ui.layer)
        assertEquals(ArchivePhase.PAUSED, closed.status.phase)
        mark = closed.mark()
        closed.press(SELECT)
        assertEquals(listOf("archiveResume"), closed.did(mark))
        // A provider pause (catch-up, PAUSED) with the bar closed.
        val provider = PlayerWorld.archive().keys(PLAY_PAUSE)
        provider.advance(10_000)
        assertEquals(ArchivePhase.PAUSED, provider.status.phase)
        mark = provider.mark()
        provider.press(SELECT)
        assertEquals(listOf("archiveResume"), provider.did(mark))
    }

    @Test fun mediaKeysAreRemembered() {
        val w = buffered()
        w.keys(UP, SELECT) // ↑ OK: Pause
        assertFalse(w.ui.paused!!.mediaKeys)
        w.press(PLAY_PAUSE); w.press(PLAY_PAUSE)
        assertTrue(w.ui.paused!!.mediaKeys)
    }

    /** UX review §7, S4: the header comes with the paused state, not with the scrub bar. */
    @Test fun thePausedHeaderIsThereWithTheBarClosed() {
        val w = liveWithoutArchive().buffer(stop = RecordStop.HOLD_LIMIT, holdMs = 10 * 60_000L).keys(UP, SELECT, BACK)
        assertNull(w.ui.scrub)
        assertEquals(PausedHeader.HOLD_LIMIT, w.ui.paused?.header)
    }

    /** Fix pass S2: a seek while paused (PAUSED → SEEKING → PAUSED) keeps the pause's start: the confirm still applies. */
    @Test fun aSeekWhilePausedKeepsThePauseClock() {
        val w = buffered().keys(PLAY_PAUSE)
        val since = w.ui.paused!!.pausedSinceMs
        w.advance(100_000)
        w.press(LEFT)
        w.advance(ScrubModel.SCRUB_COMMIT_MS)
        assertEquals(ArchivePhase.PAUSED, w.status.phase, "the commit stays paused (M4 E26)")
        assertEquals(since, w.ui.paused!!.pausedSinceMs, "the original pause start")
        w.advance(30_000)
        val mark = w.mark()
        w.press(ZAP_UP)
        assertTrue(w.did(mark).isEmpty(), "2:10 paused with a nudge: still asks")
        assertEquals(listOf(PlayerToast.ZAP_CONFIRM), w.toasts(mark))
    }

    /**
     * WP-D question: the dim and the zap confirm both start at 2 min. The zap key that wakes the picture also asks, so
     * the second press zaps (never three presses).
     */
    @Test fun theZapKeyThatWakesThePictureAlsoAsks() {
        for (key in listOf(ZAP_UP, PREVIOUS_CHANNEL)) {
            val w = buffered().keys(PLAY_PAUSE)
            w.advance(TimeshiftConstants.PAUSED_DIM_AFTER_MS + 5_000)
            assertTrue(w.ui.paused!!.dimmed)
            var mark = w.mark()
            w.press(key)
            assertTrue(w.did(mark).isEmpty(), "$key: wakes, no zap")
            assertEquals(listOf(if (key == ZAP_UP) PlayerToast.ZAP_CONFIRM else PlayerToast.ZAP_CONFIRM), w.toasts(mark))
            assertFalse(w.ui.paused!!.dimmed)
            mark = w.mark()
            w.press(key)
            assertEquals(listOf(if (key == ZAP_UP) "zap" else "previousChannel"), w.did(mark), "$key: the second press zaps")
        }
        // Up under "Change channel" too; any other key only wakes, with no toast.
        val z = liveWithoutArchive(app.thebox.core.full.PlayerSettings(upDown = app.thebox.core.full.UpDownInPlayer.ZAP)).buffer().keys(PLAY_PAUSE)
        z.advance(TimeshiftConstants.PAUSED_DIM_AFTER_MS)
        var mark = z.mark()
        z.press(UP)
        assertEquals(listOf(PlayerToast.ZAP_CONFIRM), z.toasts(mark))
        val other = buffered().keys(PLAY_PAUSE).also { it.advance(TimeshiftConstants.PAUSED_DIM_AFTER_MS) }
        mark = other.mark()
        other.press(BACK)
        assertTrue(other.callsSince(mark).isEmpty())
    }

    /** Lead ruling 6: under 2 min no confirm; at 2 min the first zap asks, a second within 3 s zaps; the list zaps at once. */
    @Test fun zapConfirmAfterTwoMinutes() {
        val w = buffered().keys(PLAY_PAUSE)
        w.advance(110_000); w.press(REMIND); w.advance(9_999) // keep the frame lit
        var mark = w.mark()
        w.press(ZAP_UP)
        assertEquals(listOf("zap"), w.effectsSince(mark), "1:59 paused: zaps at once")

        val v = buffered().keys(PLAY_PAUSE)
        v.advance(110_000); v.press(REMIND); v.advance(10_000)
        mark = v.mark()
        v.press(ZAP_UP)
        assertTrue(v.did(mark).isEmpty())
        val toast = v.callsSince(mark).single { it.name == "toast" }
        assertEquals(PlayerToast.ZAP_CONFIRM, toast.args[0])
        assertEquals((20 * 60_000L + 120_000L).toString(), toast.args[1], "the saved length (20 min + the 2 min paused)")
        v.advance(TimeshiftConstants.ZAP_CONFIRM_WINDOW_MS)
        mark = v.mark()
        v.press(ZAP_UP)
        assertEquals(listOf("zap"), v.effectsSince(mark), "the second press within 3 s zaps")
    }

    /** Lead ruling 6, number entry: the guide asks `zapNeedsConfirm`; the number stays; OK within 3 s zaps. */
    @Test fun numberEntryConfirmThroughTheGuide() {
        val w = PlayerWorld(withGuide = true, tunedIndex = 1)
        w.buffer()
        w.press(PLAY_PAUSE)
        assertEquals(TimelineSource.LOCAL, w.status.source)
        w.advance(110_000); w.press(REMIND); w.advance(10_000)
        val tunedBefore = w.gui.tuned.identityHash
        var mark = w.mark()
        w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 4); w.press(SELECT)
        assertEquals(tunedBefore, w.gui.tuned.identityHash, "held back")
        assertIs<Overlay.Number>(w.gui.overlay, "the number stays on screen")
        assertEquals(listOf(PlayerToast.ZAP_CONFIRM_NUMBER), w.toasts(mark))
        mark = w.mark()
        w.press(SELECT)
        assertEquals(w.channels[3].identityHash, w.gui.tuned.identityHash, "OK within 3 s tunes 104")
        assertTrue(w.toasts(mark).isEmpty())
    }

    @Test fun numberEntryConfirmWindowCloses() {
        val w = PlayerWorld(withGuide = true, tunedIndex = 1)
        w.buffer()
        w.press(PLAY_PAUSE)
        w.advance(110_000); w.press(REMIND); w.advance(10_000)
        w.press(DIGIT, digit = 1); w.press(DIGIT, digit = 0); w.press(DIGIT, digit = 4); w.press(SELECT)
        assertIs<Overlay.Number>(w.gui.overlay)
        w.advance(TimeshiftConstants.ZAP_CONFIRM_WINDOW_MS)
        assertNull(w.gui.overlay, "no confirm: the entry closes without a zap")
        assertEquals(w.channels[1].identityHash, w.gui.tuned.identityHash)
    }

    /** §9.1: RW from live lands on the oldest saved point with REWIND_START once the buffer is shorter than 30 s. */
    @Test fun clampToastsOnAChannelWithoutCatchUp() {
        val w = liveWithoutArchive()
        w.advance(10_000)
        w.buffer(savedMs = 20_000)
        val mark = w.mark()
        w.press(SEEK_BACK)
        val toast = w.callsSince(mark).single { it.name == "toast" }
        assertEquals(PlayerToast.REWIND_START, toast.args[0])
        assertEquals(w.bufferInfo!!.fromMs.toString(), toast.args[1], "the oldest saved instant (display frame)")
        val limited = liveWithoutArchive().also { it.advance(10_000) }.buffer(savedMs = 20_000, limitedToday = true)
        val m2 = limited.mark()
        limited.press(SEEK_BACK)
        assertEquals(listOf(PlayerToast.REWIND_LIMITED_TODAY), limited.toasts(m2))
    }

    /** Fix pass S8: once the oldest point is no longer where the cycle began, REWIND_START names the window instead. */
    @Test fun rewindStartSaysWhenYouTunedInOnlyWhenTrue() {
        val w = liveWithoutArchive()
        w.advance(10_000)
        w.buffer(savedMs = 20_000)
        var mark = w.mark()
        w.press(SEEK_BACK)
        assertEquals((w.bufferInfo!!.fromMs).toString(), w.callsSince(mark).single { it.name == "toast" }.args[1])
        // Later the window is full and the oldest point moves on (the ring wrapped).
        val v = liveWithoutArchive()
        v.advance(10_000)
        v.buffer(savedMs = 20_000)
        v.advance(40 * 60_000L)
        v.buffer(savedMs = 20_000)
        mark = v.mark()
        v.press(SEEK_BACK)
        val arg = v.callsSince(mark).single { it.name == "toast" }.args[1] as String
        assertEquals(v.bufferInfo!!.fromMs.toString() + app.thebox.core.full.PLAYER_TOAST_ARG_SEP + (30 * 60_000L), arg)
    }

    /** Fix pass S9: re-tuning the same channel is a new cycle: REWIND_NOTHING_YET within 5 s of it. */
    @Test fun aNewCycleOnTheSameChannelRestartsTheTuneClock() {
        fun after(nextCycle: Long): List<PlayerToast> {
            val w = liveWithoutArchive()
            w.buffer(savedMs = 2_000, cycleId = 1)
            w.advance(60_000)
            w.buffer(savedMs = 2_000, cycleId = nextCycle) // Back to live, Retry, a restart: the same identity
            val mark = w.mark()
            w.press(SEEK_BACK)
            return w.toasts(mark)
        }
        assertEquals(listOf(PlayerToast.REWIND_START), after(nextCycle = 1), "a minute into the same cycle")
        assertEquals(listOf(PlayerToast.REWIND_NOTHING_YET), after(nextCycle = 2), "a new cycle of the same channel")
    }

    /** Fix pass S3: a scrub into a not-saved range lands at its end with GAP_SKIPPED (no catch-up). */
    @Test fun aScrubIntoANotSavedRangeToastsTheSkip() {
        val w = liveWithoutArchive()
        val gapFrom = w.nowMs - 15 * 60_000L
        val gapTo = w.nowMs - 5 * 60_000L
        w.buffer(notSaved = app.thebox.core.playback.timeshift.TimeRanges(longArrayOf(gapFrom, gapTo)))
        w.press(PLAY_PAUSE)
        repeat(8) { w.press(SEEK_BACK) } // −4 min… the preview at −4 min is saved; go further
        w.press(LEFT, heldMs = 5_000) // −2 min more: −6 min, inside the gap
        val mark = w.mark()
        w.advance(ScrubModel.SCRUB_COMMIT_MS)
        val toast = w.callsSince(mark).single { it.name == "toast" }
        assertEquals(PlayerToast.GAP_SKIPPED_PAUSED, toast.args[0])
        assertEquals("${gapTo - gapFrom}${app.thebox.core.full.PLAYER_TOAST_ARG_SEP}${gapTo + w.status.displayShiftMs}", toast.args[1])
    }

    /** Fix pass S7: every buffer state answers a pause and a rewind with its reason; NO_CATCHUP never does. */
    @Test fun everyBufferStateHasItsReason() {
        fun toastFor(w: PlayerWorld, key: app.thebox.core.input.BoxAction): List<PlayerToast> { val m = w.mark(); w.press(key); return w.toasts(m) }
        val off = app.thebox.core.playback.timeshift.BufferOff.entries
        val notTs = liveWithoutArchive().buffer(available = false, off = app.thebox.core.playback.timeshift.BufferOff.NOT_TS)
        assertEquals(listOf(PlayerToast.PAUSE_NO_REWIND_FORMAT), toastFor(notTs, PLAY_PAUSE))
        assertEquals(listOf(PlayerToast.PAUSE_NO_REWIND_FORMAT), toastFor(liveWithoutArchive().buffer(available = false, off = app.thebox.core.playback.timeshift.BufferOff.NOT_TS), SEEK_BACK))
        val storage = { liveWithoutArchive().buffer(available = false, off = app.thebox.core.playback.timeshift.BufferOff.STORAGE_ERROR) }
        assertEquals(listOf(PlayerToast.PAUSE_NO_REWIND_STORAGE), toastFor(storage(), PLAY_PAUSE))
        assertEquals(listOf(PlayerToast.REWIND_STOPPED_STORAGE), toastFor(storage(), SEEK_BACK))
        assertEquals(listOf(PlayerToast.REWIND_NOTHING_YET), toastFor(liveWithoutArchive(), SEEK_BACK), "unknown state")
        for (o in off) for (k in listOf(PLAY_PAUSE, SEEK_BACK)) {
            val t = toastFor(liveWithoutArchive().buffer(available = false, off = o), k)
            assertFalse(PlayerToast.NO_CATCHUP in t, "$o + $k: $t")
        }
    }

    /** The buffer belongs to the tuned channel: a zap forgets it (the next channel's arrives with its frames). */
    @Test fun aZapForgetsTheBuffer() {
        val w = buffered()
        w.press(ZAP_UP) // channel 2 (archive)… then to 3 (no archive, no buffer yet)
        w.press(ZAP_UP)
        val mark = w.mark()
        w.press(PLAY_PAUSE)
        assertEquals(listOf("pausePlain"), w.did(mark))
        assertTrue(w.toasts(mark).isEmpty(), "unknown buffer state: no reason toast for a pause")
    }

    /** §10 (U-B3): HLS freezes with the reason, and the quick bar still starts with Pause. */
    @Test fun hlsPauseIsHonest() {
        val w = liveWithoutArchive().buffer(available = false, hls = true, off = app.thebox.core.playback.timeshift.BufferOff.NOT_TS)
        w.press(UP)
        assertEquals(app.thebox.core.full.QuickItem.TIME_SHIFT, w.ui.quick!!.items.first())
        val mark = w.mark()
        w.press(SELECT)
        assertEquals(listOf("pausePlain"), w.did(mark))
        assertEquals(listOf(PlayerToast.PAUSE_NO_REWIND_HLS), w.toasts(mark))
        assertEquals("", w.callsSince(mark).single { it.name == "toast" }.args[1], "an Xtream channel without a playlist name: the VM says \"your playlist\"")
    }
}
