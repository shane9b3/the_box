package app.thebox.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.MainActivity
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.PillKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.guide.ScreenMode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-TS-1…30: the M6 time-shift buffer on the device (docs/design/m6-timeshift.md §17.4; owner WP-D). One method per case
 * (I-TS-20 and I-TS-29 are split into a…c / a…d); each logs `I-TS-n: ok` like `KeysOnDeviceTest`'s I-4 lines and runs
 * alone with `--class app.thebox.player.TimeShiftBufferTest#<method>`.
 *
 * Class run (the baseline every case assumes; the panel must be synthetic with the M4 fixtures):
 * ```
 * tools/fakepanel/run-instrumented.sh --synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"' \
 *     --class app.thebox.player.TimeShiftBufferTest
 * ```
 * A case that needs other panel config POSTs it itself after the per-test `/admin/reset`, so the class run needs no extra
 * `--set`; its KDoc gives the equivalent `--set` line for a single-method run (`--set` values survive `/admin/reset`, so
 * never add one to a class run: every case would get it). I-TS-21 (2 h soak) runs only with
 * `-Pandroid.testInstrumentationRunnerArguments.soak=1` (extra Gradle argument of run-instrumented.sh).
 *
 * Channels (`--synthetic 2000 --set player_fixtures=true`): 13 = TS without catch-up (neighbours 12 and 14 too), 1 = TS
 * with a 7-day archive, `fx.multiHls` (8) = live HLS without catch-up. The clip is 2 Mbit/s with a 2 s GOP, so one GOP
 * is the seek tolerance; a "1-min ring" is measured (bytes/s × 60) and set with `ts_ring_bytes`.
 *
 * Manual cases (flash, screensaver on hardware, process death) are in the design's §17.4 manual table and the WP-D
 * implementation record; I-TS-14 here checks only the in-process half.
 */
@RunWith(AndroidJUnit4::class)
class TimeShiftBufferTest : TimeShiftBufferBase() {

    // ------------------------------------------------------------------ I-TS-1…3: pause, rewind, back to live

    /**
     * I-TS-1: pause 60 s, resume → same tuneId, no new connection, BEHIND 60 s ± 2 s, continues from the pause point.
     * `--set`: none. The burnt-in-counter check of the design is a pixel check the testsrc2 clip cannot give; the playhead's
     * mono position (`position().localMonoMs`) before the pause and after the resume stands in for it.
     */
    @Test
    fun its01_pauseSixtySecondsResumesInPlace() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(10_000)
        val tune0 = tuneId()
        val at0 = main { controller.position().localMonoMs }
        val pauseUnix = pauseByKey()
        val opensAtPause = tp.clientOpensSince(pauseUnix - 1).size
        Thread.sleep(60_000)
        assertBehind(60_000, 2_000 + emuSlack, "I-TS-1 paused 60 s")
        resumeByKey()
        Thread.sleep(500)
        val at1 = main { controller.position().localMonoMs }
        log("I-TS-1 playhead mono at the pause $at0, after resume $at1; tuneId $tune0 → ${tuneId()}")
        assertEquals("I-TS-1 same tuneId (no re-prepare on resume)", tune0, tuneId()) // WP-B behaviour (§7.2)
        assertEquals("I-TS-1 no new panel connection across the pause", opensAtPause, tp.clientOpensSince(pauseUnix - 1).size)
        if (at0 != null && at1 != null) assertTrue("I-TS-1 continues from the pause point ($at0 → $at1)", abs(at1 - at0) <= 1_500 + emuSlack)
        Thread.sleep(2_000)
        assertBehind(60_000, 2_000 + emuSlack, "I-TS-1 after resume")
        pass("I-TS-1")
    }

    /** I-TS-2: watch 3 min, 4 × RW → BEHIND 2:00 ± one GOP (2 s); seek latency logged (P50 ≤ 600 ms is a hardware gate). `--set`: none. */
    @Test
    fun its02_rewindTwoMinutesAfterThreeMinutes() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(180_000)
        val t0 = SystemClock.elapsedRealtime()
        rewind(4)
        Thread.sleep(1_000)
        assertBehind(120_000, 2_000 + 1_000 + emuSlack, "I-TS-2 after 4 × RW")
        val lat = seekLatencies(t0)
        log("I-TS-2 seek commit → first frame: $lat ms (P50 ${percentile(lat, 0.5)}, design P50 ≤ 600, P90 ≤ 1,000 on hardware)")
        assertTrue("I-TS-2 at least one local seek reached a first frame", lat.isNotEmpty()) // WP-B behaviour
        if (!isEmulator()) assertTrue("I-TS-2 seek P50 ≤ 600 ms: $lat", (percentile(lat, 0.5) ?: Long.MAX_VALUE) <= 600)
        pass("I-TS-2", "latencies $lat")
    }

    /**
     * I-TS-3: from 2:00 behind, quick bar "Back to live" (first while behind, §11.3) → LIVE; behind ≤ `liveLandingMs` + 1 s
     * (A-C3), with `liveLandingMs` taken as the latency right after the tune; no new connection. `--set`: none.
     */
    @Test
    fun its03_backToLiveFromTwoMinutesBehind() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(5_000)
        val latencyAtTune = monoBehindMs() ?: 0
        Thread.sleep(145_000)
        rewind(4)
        val since = pp.now().unix
        openQuickBar()
        val items = pui().quick!!.items
        log("I-TS-3 quick bar behind live: $items")
        assertEquals("I-TS-3 Back to live is first while behind (§11.3)", QuickItem.JUMP_TO_LIVE, items.first()) // WP-C behaviour
        val t0 = SystemClock.elapsedRealtime()
        quickSelect(QuickItem.JUMP_TO_LIVE)
        awaitArchive("I-TS-3 session ended at live", 15_000) { it.phase == ArchivePhase.IDLE }
        awaitPlayer("I-TS-3 pill LIVE", 5_000) { it.pill.kind == PillKind.LIVE }
        Thread.sleep(2_000)
        val behind = monoBehindMs()
        log("I-TS-3 back to live in ${SystemClock.elapsedRealtime() - t0} ms; behind $behind ms (latency at tune $latencyAtTune ms); latencies ${seekLatencies(t0)}")
        assertTrue("I-TS-3 behind $behind ≤ liveLanding ($latencyAtTune) + 1 s", behind != null && behind <= maxOf(latencyAtTune, 1_000) + 1_000 + 1_000 + emuSlack) // WP-B behaviour (A-C3)
        assertTrue("I-TS-3 no new connection: ${tp.clientOpensSince(since)}", tp.clientOpensSince(since).isEmpty())
        pass("I-TS-3")
    }

    // ------------------------------------------------------------------ I-TS-4, 5: the connection while paused

    /**
     * I-TS-4: one-connection account, hold 30 s (debug `ts_hold_ms=30000`): one connection for 30 s, then 0;
     * `/admin/occupy` refused during the hold, accepted after.
     * `--set max_connections=1` (the case POSTs it).
     */
    @Test
    fun its04_holdLimitReleasesTheOnlySlot() {
        // Integration 2026-10-19 (test fix, class order): after I-TS-11 the app's launch resumes the HLS fixture, and the
        // panel counts an HLS session against max_connections until `hls_timeout` (20 s) after its last request, so the
        // TS tune got three 403s and CONNECTION_LIMIT. A 1 s HLS timeout frees the slot before the ladder's retry.
        tp.config(JSONObject().put("max_connections", 1).put("hls_timeout", 1))
        applyTs("ts_hold_ms=30000")
        bufferedOn(TS_PLAIN)
        Thread.sleep(5_000)
        val pausedAt = SystemClock.elapsedRealtime()
        pauseByKey()
        Thread.sleep(15_000)
        assertEquals("I-TS-4 still one connection 15 s into the pause (M2 would have released at 10 s)", 1, tp.openClientStreams()) // WP-B behaviour
        val (codeDuring, bodyDuring) = tp.occupy("hold")
        log("I-TS-4 occupy during the hold: $codeDuring $bodyDuring")
        assertEquals("I-TS-4 another device is refused during the hold", 409, codeDuring)
        awaitBuffer("I-TS-4 HOLD_LIMIT", 30_000) { it.stop == RecordStop.HOLD_LIMIT }
        val heldMs = SystemClock.elapsedRealtime() - pausedAt
        log("I-TS-4 saving stopped after $heldMs ms (hold 30,000)")
        assertTrue("I-TS-4 the hold lasted ≈ 30 s ($heldMs ms)", heldMs in 28_000..(36_000 + emuSlack))
        awaitTrue("I-TS-4 connection released", 5_000) { tp.openClientStreams() == 0 }
        val (codeAfter, bodyAfter) = tp.occupy("hold")
        log("I-TS-4 occupy after the hold: $codeAfter $bodyAfter")
        assertEquals("I-TS-4 another device gets the slot after the hold", 200, codeAfter)
        assertEquals("I-TS-4 header HOLD_LIMIT", RecordStop.HOLD_LIMIT, archive().savingStop) // WP-C behaviour (§11.5)
        log("I-TS-4 scrub header ${pui().scrub?.header}")
        pass("I-TS-4", "held $heldMs ms")
    }

    /**
     * I-TS-5: pause until a 1-min ring is full, no catch-up → saving stops (FULL_WHILE_PAUSED), connection released, header
     * FULL; resume plays the saved minute, then GAP_SKIPPED_PAUSED. `--set`: none (the ring is `ts_ring_bytes`, measured).
     */
    @Test
    fun its05_pauseUntilTheOneMinuteRingIsFull() {
        retuneWithRingSeconds(TS_PLAIN, 60)
        Thread.sleep(3_000)
        pauseByKey()
        awaitBuffer("I-TS-5 FULL_WHILE_PAUSED", 120_000) { it.stop == RecordStop.FULL_WHILE_PAUSED } // WP-A/WP-B behaviour
        awaitTrue("I-TS-5 connection released", 5_000) { tp.openClientStreams() == 0 }
        assertEquals("I-TS-5 header FULL", RecordStop.FULL_WHILE_PAUSED, archive().savingStop)
        log("I-TS-5 scrub header ${pui().scrub?.header}")
        Thread.sleep(30_000) // a gap that was not saved
        val resumed = SystemClock.elapsedRealtime()
        resumeByKey()
        assertTrue("I-TS-5 GAP_SKIPPED_PAUSED after the saved minute", awaitToast("weren't saved while paused", 110_000)) // WP-C behaviour
        val gaps = noticesSince(resumed).filterIsInstance<PlaybackNotice.GapSkipped>()
        log("I-TS-5 GapSkipped notices: $gaps; boundaries ${decisions("ring_boundary", resumed)}")
        assertTrue("I-TS-5 a GapSkipped(stream = false) notice", gaps.any { !it.stream })
        pass("I-TS-5")
    }

    // ------------------------------------------------------------------ I-TS-6…8: upstream discontinuities

    /**
     * I-TS-6: the server closes the connection every 35 s while paused 2 min → spliced without a segment; resume plays
     * across the splices. `--set 'faults={"close_every":35}'`. Deviation: the design's 15 s would be 4 reconnects a minute,
     * which the A-C4 cap (`PAUSED_RECONNECTS_MAX` = 2 per 60 s while paused) rightly turns into SLOT_LOST; 35 s stays under it.
     */
    @Test
    fun its06_serverClosesWhilePausedSpliceWithoutSegments() {
        tp.config(JSONObject().put("faults", JSONObject().put("close_every", 35)))
        bufferedOn(TS_PLAIN)
        Thread.sleep(5_000)
        val t0 = SystemClock.elapsedRealtime()
        val pauseUnix = pauseByKey()
        Thread.sleep(120_000)
        val reopens = tp.clientOpensSince(pauseUnix)
        val segs = decisions("segment", t0).filterNot { it.detail.startsWith("FIRST") }
        log("I-TS-6 reconnects while paused: ${reopens.size} $reopens; segments $segs; stops ${decisions("saving_stopped", t0)}")
        assertTrue("I-TS-6 the panel closed and the writer reconnected (≥ 2 opens): $reopens", reopens.size >= 2)
        assertTrue("I-TS-6 no segment from a continuous splice: $segs", segs.isEmpty()) // WP-A behaviour
        assertTrue("I-TS-6 still saving", lb()?.saving == true && lb()?.stop == null)
        val resumed = SystemClock.elapsedRealtime()
        resumeByKey()
        Thread.sleep(60_000)
        assertTrue("I-TS-6 no boundary while playing across the splices", decisions("ring_boundary", resumed).isEmpty())
        assertTrue("I-TS-6 no stall", eventsSince(resumed, PlaybackEventType.STALL).isEmpty())
        assertBehind(120_000, 3_000 + emuSlack, "I-TS-6 after 60 s of play")
        pass("I-TS-6")
    }

    /**
     * I-TS-7: `reconnect_mode: reset` while behind → a boundary, ≤ 1 s hold, no card, BEHIND correct.
     * `--set 'faults={"close_every":40,"reconnect_mode":"reset"}'`.
     */
    @Test
    fun its07_resetReconnectWhileBehindIsABoundary() {
        tp.config(JSONObject().put("faults", JSONObject().put("close_every", 40).put("reconnect_mode", "reset")))
        bufferedOn(TS_PLAIN)
        Thread.sleep(35_000)
        val t0 = SystemClock.elapsedRealtime()
        rewind(1)
        Thread.sleep(1_000)
        val behind0 = behindNowMs()
        val cards = Sampler(200) { pui().error != null || controller.state.value is PlaybackState.Error }
        try {
            Thread.sleep(100_000)
        } finally {
            cards.close()
        }
        val bounds = decisions("ring_boundary", t0)
        val segs = decisions("segment", t0)
        val behind1 = behindNowMs()
        log("I-TS-7 boundaries $bounds; segments $segs; behind $behind0 → $behind1 ms; rebuffers ${eventsSince(t0, PlaybackEventType.REBUFFER).size}")
        assertTrue("I-TS-7 the reader crossed at least one boundary", bounds.isNotEmpty()) // WP-A/WP-B behaviour
        assertFalse("I-TS-7 no error card", cards.snapshot().any { it })
        // Each hand-off holds ≤ 1 s, which adds to the distance behind.
        assertTrue("I-TS-7 behind grew ≤ 1 s per boundary (+ 2 s): $behind0 → $behind1", behind1 - behind0 <= bounds.size * 1_000L + 2_000 + emuSlack)
        assertTrue("I-TS-7 still behind live (not pushed to live)", pui().pill.kind == PillKind.BEHIND)
        pass("I-TS-7", "${bounds.size} boundaries")
    }

    /**
     * I-TS-8: a PTS/PCR step inside one connection → a boundary hand-off and no stall-detector re-prepare.
     * `--set 'faults={"inline_jump_after":[40,5000]}'`.
     */
    @Test
    fun its08_inlineJumpIsABoundaryWithoutStallReprepare() {
        tp.config(JSONObject().put("faults", JSONObject().put("inline_jump_after", JSONArray().put(40).put(5000))))
        val tuned = SystemClock.elapsedRealtime()
        bufferedOn(TS_PLAIN)
        Thread.sleep(50_000)
        val t0 = SystemClock.elapsedRealtime()
        rewind(1) // ~30 s behind: the reader reaches the step (40 s into the connection) about 20 s later
        val errors = Sampler(200) { controller.state.value is PlaybackState.Error || controller.state.value is PlaybackState.Stalled }
        try {
            Thread.sleep(45_000)
        } finally {
            errors.close()
        }
        val segs = decisions("segment", tuned)
        val bounds = decisions("ring_boundary", t0)
        val stalls = eventsSince(tuned, PlaybackEventType.STALL)
        log("I-TS-8 segments $segs; boundaries $bounds; stalls $stalls")
        assertTrue("I-TS-8 an inline jump segment: $segs", segs.any { it.detail.startsWith("INLINE_PCR_JUMP") }) // WP-A behaviour
        assertTrue("I-TS-8 the reader handed off at the boundary", bounds.isNotEmpty())
        assertTrue("I-TS-8 no stall detector verdict: $stalls", stalls.isEmpty()) // WP-B behaviour (A-B3)
        assertFalse("I-TS-8 never Stalled or Error", errors.snapshot().any { it })
        pass("I-TS-8")
    }

    // ------------------------------------------------------------------ I-TS-9, 10: zap, archive channel

    /** I-TS-9: zap while behind → the buffer is discarded; RW on the new channel → REWIND_NOTHING_YET. `--set`: none. */
    @Test
    fun its09_zapWhileBehindDiscardsTheBuffer() {
        val ch = bufferedOn(TS_PLAIN)
        Thread.sleep(40_000)
        rewind(1)
        val t0 = SystemClock.elapsedRealtime()
        press(K_CH_UP, intervalMs = 0)
        awaitController("I-TS-9 zapped", 15_000) { it is PlaybackState.Playing && it.target.identity != ch.identity && !it.target.isArchive }
        val st = awaitBuffer("I-TS-9 new channel's buffer", 15_000) { it.identity != ch.identity && it.available }
        // Integration 2026-10-19 (test fix): the old LOCAL session ends with the zap, so the archive is IDLE *before* the
        // RW. The RW itself is §9.1's "older than the buffer, no archive → Clamp to localFrom": the router lands the
        // playhead on the new buffer's oldest point (a LOCAL session of the new channel) and toasts REWIND_NOTHING_YET.
        assertEquals("I-TS-9 the archive session ended with the zap", ArchivePhase.IDLE, archive().phase)
        press(K_RW, intervalMs = 0)
        assertTrue("I-TS-9 REWIND_NOTHING_YET", awaitToast("Nothing to rewind yet", 5_000)) // WP-C behaviour
        val ends = decisions("timeshift_end", t0)
        log("I-TS-9 timeshift_end $ends; new buffer ${st.brief()}; archive after RW ${archive().brief()}")
        assertTrue("I-TS-9 the old cycle's session ended", ends.isNotEmpty())
        assertTrue("I-TS-9 new buffer holds only the new tune (< 10 s)", st.liveMonoMs - st.fromMonoMs < 10_000)
        val after = archive()
        assertTrue("I-TS-9 after RW: idle, or the clamp's LOCAL session on the new channel (${after.brief()})",
            after.phase == ArchivePhase.IDLE || (after.source == TimelineSource.LOCAL && after.channel?.identity != ch.identity))
        pass("I-TS-9")
    }

    /**
     * I-TS-10: archive channel: pause 2 min → no timeshift request; resume local; a target 10 min back (older than the
     * buffer) → exactly one provider request, and the ring ends. `--set`: none. The 10-min target is committed through
     * `ArchivePlayback.seekTo` (what a scrub commit calls): the PROGRAMME-scope bar can clamp ← at the programme start.
     * The buffer holds what was saved since the tune (≈ 2.5 min), standing in for the design's 5-min buffer.
     */
    @Test
    fun its10_archiveChannelPausesLocallyAndRewindsBeyondToTheProvider() {
        val ch = bufferedOn(TS_ARCHIVE)
        assertTrue("${ch.displayName} has archive", ch.archiveDays > 0)
        Thread.sleep(10_000)
        val pauseUnix = pauseByKey()
        Thread.sleep(120_000)
        assertTrue("I-TS-10 no timeshift request for a pause: ${pp.timeshiftSince(pauseUnix)}", pp.timeshiftSince(pauseUnix).isEmpty()) // WP-C behaviour (§9.1)
        resumeByKey()
        Thread.sleep(3_000)
        assertTrue("I-TS-10 still no request after the resume", pp.timeshiftSince(pauseUnix).isEmpty())
        val before = pp.now().unix
        val t0 = SystemClock.elapsedRealtime()
        val a = archive()
        val target = a.liveEdgeMs - 10 * 60_000
        onMain { (probe.archive ?: ep.archivePlayback()).seekTo(target, a.settledSeq + 100) }
        awaitArchive("I-TS-10 provider playing", 30_000) { it.source == TimelineSource.PROVIDER && it.phase == ArchivePhase.PLAYING }
        Thread.sleep(3_000)
        val reqs = pp.timeshiftLogical(before)
        log("I-TS-10 requests $reqs; ends ${decisions("timeshift_end", t0)}")
        assertEquals("I-TS-10 one provider request: $reqs", 1, reqs.size) // WP-C behaviour (TimelineRouter)
        assertTrue("I-TS-10 the controller plays the archive", controller.state.value.target?.isArchive == true)
        assertTrue("I-TS-10 the ring ended", decisions("timeshift_end", t0).isNotEmpty())
        assertTrue("I-TS-10 the ring file is gone (catch-up discards it, §8.3)", !ringFile().exists())
        pass("I-TS-10")
    }

    // ------------------------------------------------------------------ I-TS-11, 12: unbuffered channels

    /**
     * I-TS-11: HLS without catch-up: "Pause" present and first; ↑ OK freezes with PAUSE_NO_REWIND_HLS; M2 release after
     * 10 s; resume → BACK_TO_LIVE_UNBUFFERED. `--set`: none (fixture channel `multi_hls`).
     */
    @Test
    fun its11_hlsWithoutCatchupFreezesWithTheReason() {
        bufferedOn(fx.multiHls, expectBuffered = false)
        log("I-TS-11 buffer ${lb()?.brief()}")
        assertTrue("I-TS-11 HLS is not buffered", lb()?.available != true)
        openQuickBar()
        assertEquals("I-TS-11 Pause first (§11.3, U-B3)", QuickItem.TIME_SHIFT, pui().quick!!.items.first()) // WP-C behaviour
        quickSelect(QuickItem.TIME_SHIFT)
        assertTrue("I-TS-11 PAUSE_NO_REWIND_HLS", awaitToast("can't be rewound", 5_000))
        awaitController("I-TS-11 frozen", 5_000) { it is PlaybackState.Playing && it.paused }
        awaitController("I-TS-11 M2 release after 10 s", 15_000) { it is PlaybackState.Stopped && it.reason == StopReason.LIVE_PAUSE_TIMEOUT }
        toBareVideo()
        press(K_PLAY_PAUSE, intervalMs = 0)
        assertTrue("I-TS-11 BACK_TO_LIVE_UNBUFFERED", awaitToast("the paused part was skipped", 8_000))
        awaitController("I-TS-11 live again", 15_000) { it is PlaybackState.Playing && !it.paused }
        pass("I-TS-11")
    }

    /** I-TS-12: low space (debug `ts_free_bytes=0`) → NO_SPACE; PLAY_PAUSE → PAUSE_NO_REWIND_SPACE and M2's freeze. `--set`: none. */
    @Test
    fun its12_lowSpaceFreezesWithTheReason() {
        applyTs("ts_free_bytes=0")
        bufferedOn(TS_PLAIN, expectBuffered = false)
        awaitBuffer("I-TS-12 NO_SPACE", 10_000) { it.off == BufferOff.NO_SPACE } // WP-B behaviour
        awaitTrue("I-TS-12 the machine knows", 5_000) { info()?.off == BufferOff.NO_SPACE }
        press(K_PLAY_PAUSE, intervalMs = 0)
        assertTrue("I-TS-12 PAUSE_NO_REWIND_SPACE", awaitToast("Not enough free storage", 5_000)) // WP-C behaviour
        awaitController("I-TS-12 frozen", 5_000) { it is PlaybackState.Playing && it.paused }
        awaitController("I-TS-12 M2 release", 15_000) { it is PlaybackState.Stopped && it.reason == StopReason.LIVE_PAUSE_TIMEOUT }
        assertFalse("I-TS-12 no ring file", ringFile().exists())
        pass("I-TS-12")
    }

    // ------------------------------------------------------------------ I-TS-13, 14: leaving the app

    /** I-TS-13: Home while paused, return → stopped, file deleted, live, LEFT_APP_CLEARED once. `--set`: none. */
    @Test
    fun its13_homeWhilePausedClearsAndToastsOnce() {
        val ch = bufferedOn(TS_PLAIN)
        Thread.sleep(10_000)
        pauseByKey()
        Thread.sleep(20_000)
        assertTrue("I-TS-13 ring file exists while paused", ringFile().exists())
        val t0 = SystemClock.elapsedRealtime()
        device.pressHome()
        awaitController("I-TS-13 stopped at once", 5_000) { it is PlaybackState.Stopped && it.reason == StopReason.BACKGROUND }
        awaitTrue("I-TS-13 ring file deleted", 5_000) { !ringFile().exists() }
        val watch = ToastWatch("leaving The Box clears")
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            awaitController("I-TS-13 live again", 20_000) { it is PlaybackState.Playing && !it.paused && it.target.identity == ch.identity }
            Thread.sleep(8_000)
        } finally {
            watch.close()
        }
        val cleared = noticesSince(t0).count { it is PlaybackNotice.LeftAppCleared }
        log("I-TS-13 LeftAppCleared notices: $cleared; archive ${archive().brief()}")
        assertEquals("I-TS-13 LEFT_APP_CLEARED once", 1, cleared) // WP-B behaviour (§7.5)
        assertTrue("I-TS-13 the toast showed", watch.seen("leaving The Box clears"))
        assertEquals("I-TS-13 at live", ArchivePhase.IDLE, archive().phase)
        pass("I-TS-13")
    }

    /**
     * I-TS-14 (in-process half): the ring file is `ring-<this pid>.bin` and the only file in `cacheDir/timeshift/`. The
     * process-kill half (a stale file swept at the next start, an auto-tune's file surviving the sweep) is a manual step
     * (design §17.4 manual table): this process is the instrumentation's and cannot be killed by its own test.
     */
    @Test
    fun its14_ringFileIsNamedForThisProcess() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(3_000)
        val files = ringDir().listFiles()?.map { it.name }.orEmpty()
        log("I-TS-14 timeshift dir: $files (pid ${android.os.Process.myPid()})")
        assertEquals("I-TS-14 one ring file named for this pid", listOf(ringFile().name), files)
        pass("I-TS-14", "in-process half; kill/relaunch is manual")
    }

    // ------------------------------------------------------------------ I-TS-15, 16, 17, 18: ring and index under stress

    /**
     * I-TS-15: a 1-min ring, playing behind with the ring full for 4 min → no stall, `bufferFrom` advances, no pin
     * violation (no position lost, no gap). Read as "4 min of play behind live with a 1-min ring": a pause of ~45 s puts
     * the reader ~45 s behind in a ring that holds 60 s, so the pump waits for the reader (A-S3). `--set`: none.
     */
    @Test
    fun its15_oneMinuteRingFourMinutesBehind() {
        retuneWithRingSeconds(TS_PLAIN, 60)
        Thread.sleep(10_000)
        pauseByKey()
        Thread.sleep(35_000) // ~45 s saved, the reader 35 s behind: the ring fills ~15 s after the resume
        assertTrue("I-TS-15 not full while paused", lb()?.stop == null)
        resumeByKey()
        val t0 = SystemClock.elapsedRealtime()
        val from0 = lb()!!.fromMonoMs
        val behind0 = behindNowMs()
        repeat(8) {
            Thread.sleep(30_000)
            val b = lb()
            log("I-TS-15 +${(it + 1) * 30} s: pill ${behindNowMs()} ms, mono behind ${monoBehindMs()} ms, live ${b?.liveMonoMs} from ${b?.fromMonoMs} used ${b?.usedBytes} stop ${b?.stop} wall ${System.currentTimeMillis()} elapsed ${SystemClock.elapsedRealtime()}")
        }
        val from1 = lb()!!.fromMonoMs
        val behind1 = behindNowMs()
        val stalls = eventsSince(t0, PlaybackEventType.STALL)
        val lost = decisions("ring_position_lost", t0)
        val bounds = decisions("ring_boundary", t0)
        log("I-TS-15 from $from0 → $from1; behind $behind0 → $behind1; stalls $stalls; lost $lost; boundaries $bounds; stop ${lb()?.stop}")
        assertTrue("I-TS-15 bufferFrom advanced", from1 > from0 + 120_000)
        assertTrue("I-TS-15 no stall", stalls.isEmpty())
        assertTrue("I-TS-15 no pin violation (position lost)", lost.isEmpty())
        assertTrue("I-TS-15 no gap while the reader keeps up", bounds.isEmpty())
        // Integration 2026-10-19 (fake-panel tolerance): the synthetic clip's audio PTS steps +213 ms at every 10 s loop
        // (logcat: "Unexpected audio track timestamp discontinuity: expected …085329, got …298666" every ~10.03 s), and
        // the audio sink resyncs forward each time, so the position runs ~2.1 % ahead of real time (30.64 s per 30.00 s
        // measured) and 240 s of play behind live closes ~5 s. Any device on the fake panel sees it: ±3 s + 2.5 % of the
        // play time (+ emuSlack on the emulator). A real stream does not loop.
        val drift = emuSlack + 240_000L * 25 / 1000
        assertTrue("I-TS-15 behind steady ($behind0 → $behind1, tolerance ${3_000 + drift} ms)", abs(behind1 - behind0) <= 3_000 + drift)
        pass("I-TS-15")
    }

    /**
     * I-TS-16: PTS/PCR wrap at 2³³ about 60 s into the connection → BEHIND correct across the wrap and a rewind across it
     * lands correctly (A-S11). `--set 'faults={"pts_offset":8584534592}'` (2³³ − 60 s × 90 kHz).
     */
    @Test
    fun its16_ptsWrapKeepsBehindAndSeeks() {
        tp.config(JSONObject().put("faults", JSONObject().put("pts_offset", 8_584_534_592L)))
        bufferedOn(TS_PLAIN)
        // Integration 2026-10-19 (test fix): the baseline is taken 10 s in, before the wrap at 60 s. Taken at the tune
        // (14 KiB saved) it counted the panel's 1 s connect burst (`fakepanel.py`: "1 s of burst so zaps start fast")
        // and the first RAP's arrival as growth: 103.8 s "grown" in 100.0 s on the class run.
        Thread.sleep(10_000)
        val s0 = lb()!!
        val tuned = SystemClock.elapsedRealtime()
        Thread.sleep(100_000) // the wrap is behind us
        val s = lb()!!
        val grew = (s.liveMonoMs - s.fromMonoMs) - (s0.liveMonoMs - s0.fromMonoMs)
        val since = SystemClock.elapsedRealtime() - tuned
        log("I-TS-16 saved grew $grew ms in $since ms (${s.brief()})")
        // Integration 2026-10-19 (fake-panel tolerance): the panel's live PCR advances 1.0276 × wall time (measured over
        // 65 s with and without pts_offset, identical), so 100 s saves ~102.8 s. A wrap fault would be a 2³³ step or a
        // segment, far outside ±3 s + 3 % of the elapsed time.
        assertTrue("I-TS-16 the mono timeline is continuous across the wrap ($grew vs $since ms)", abs(grew - since) <= 3_000 + since * 3 / 100)
        rewind(1)
        Thread.sleep(1_000)
        assertBehind(30_000, 3_000 + emuSlack, "I-TS-16 RW after the wrap")
        rewind(2) // −90 s: before the wrap
        Thread.sleep(1_000)
        assertBehind(90_000, 3_000 + emuSlack, "I-TS-16 RW across the wrap")
        val t0 = SystemClock.elapsedRealtime()
        Thread.sleep(60_000) // from ~23 s into the connection across the wrap at 60 s
        assertTrue("I-TS-16 no boundary at the wrap", decisions("ring_boundary", t0).isEmpty()) // WP-A behaviour (PcrUnwrap)
        assertBehind(90_000, 3_000 + emuSlack, "I-TS-16 after playing across the wrap")
        pass("I-TS-16")
    }

    /** I-TS-17: no RAI flags → seeks still reach a first frame ≤ 1 s (NAL fallback). `--set 'faults={"strip_rai":true}'`. */
    @Test
    fun its17_stripRaiSeeksReachAFrame() {
        tp.config(JSONObject().put("faults", JSONObject().put("strip_rai", true)))
        bufferedOn(TS_PLAIN)
        Thread.sleep(45_000) // > RAI_FALLBACK_MS
        val t0 = SystemClock.elapsedRealtime()
        rewind(1)
        Thread.sleep(5_000)
        rewind(1)
        Thread.sleep(5_000)
        press(K_FF, intervalMs = 0)
        Thread.sleep(SCRUB_SETTLE_MS + 3_000)
        val lat = seekLatencies(t0)
        log("I-TS-17 seek latencies $lat ms")
        assertTrue("I-TS-17 three seeks reached a first frame: $lat", lat.size >= 3)
        val ceiling = if (isEmulator()) 2_500L else 1_000L
        assertTrue("I-TS-17 every seek ≤ $ceiling ms: $lat", lat.all { it <= ceiling })
        pass("I-TS-17", "$lat")
    }

    /**
     * I-TS-18: PAT/PMT every 5 s and a PMT version change (new audio PID) at 60 s → seeks ≤ 1 s, no dead track after the
     * change. `--set 'faults={"psi_every_s":5,"pmt_change_after":60}'`.
     */
    @Test
    fun its18_sparsePsiAndPmtChange() {
        tp.config(JSONObject().put("faults", JSONObject().put("psi_every_s", 5).put("pmt_change_after", 60)))
        val tuned = SystemClock.elapsedRealtime()
        bufferedOn(TS_PLAIN)
        Thread.sleep(90_000)
        val t0 = SystemClock.elapsedRealtime()
        rewind(2) // ~60 s behind: before the PMT change, which the reader crosses ~30 s later
        Thread.sleep(50_000)
        val lat = seekLatencies(t0)
        val segs = decisions("segment", tuned)
        val stalls = eventsSince(tuned, PlaybackEventType.STALL)
        val audio = controller.tracks.value.audio
        log("I-TS-18 seeks $lat; segments $segs; stalls $stalls; audio $audio")
        assertTrue("I-TS-18 a PSI_CHANGE segment: $segs", segs.any { it.detail.startsWith("PSI_CHANGE") }) // WP-A behaviour
        assertTrue("I-TS-18 seeks ≤ ${if (isEmulator()) 2_500 else 1_000} ms: $lat", lat.isNotEmpty() && lat.all { it <= if (isEmulator()) 2_500 else 1_000 })
        assertTrue("I-TS-18 no stall (dead track) after the change: $stalls", stalls.isEmpty())
        assertTrue("I-TS-18 an audio track after the change", audio.isNotEmpty())
        assertTrue("I-TS-18 no error", controller.state.value !is PlaybackState.Error)
        pass("I-TS-18")
    }

    // ------------------------------------------------------------------ I-TS-19: re-prepare while behind

    /**
     * I-TS-19: a re-prepare while behind → it re-prepares at the pin and BEHIND is unchanged. Deviation: :player has no
     * debug hook that injects `DECODER_STUCK`, so the re-prepare is forced through two real `launchSession` call sites
     * (`reapplySettings`, the decoder override; A-S6 says every one keeps the place) and any stall-detector re-prepare the
     * emulator produces during 2 min behind is checked the same way. `--set`: none.
     */
    @Test
    fun its19_reprepareWhileBehindKeepsThePosition() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(70_000)
        rewind(2)
        Thread.sleep(2_000)
        val b0 = behindNowMs()
        val tune0 = tuneId()
        onMain { controller.reapplySettings() }
        awaitTrue("I-TS-19 re-prepared (new tuneId)", 10_000) { tuneId() != tune0 && controller.state.value.let { it is PlaybackState.Playing && !it.paused } }
        Thread.sleep(2_000)
        val b1 = behindNowMs()
        log("I-TS-19 reapplySettings: behind $b0 → $b1 ms; archive ${archive().brief()}")
        assertTrue("I-TS-19 still LOCAL behind live", archive().source == TimelineSource.LOCAL && pui().pill.kind == PillKind.BEHIND)
        assertTrue("I-TS-19 BEHIND unchanged after reapplySettings ($b0 → $b1)", abs(b1 - b0) <= 3_000 + emuSlack) // WP-B behaviour (A-S6)
        val tune1 = tuneId()
        onMain { controller.setVideoDecoderOverride(null, remember = false) }
        Thread.sleep(5_000)
        val b2 = behindNowMs()
        log("I-TS-19 decoder override: tuneId $tune1 → ${tuneId()}, behind $b1 → $b2 ms")
        assertTrue("I-TS-19 BEHIND unchanged after the decoder re-prepare ($b1 → $b2)", abs(b2 - b1) <= 3_000 + emuSlack)
        val t0 = SystemClock.elapsedRealtime()
        Thread.sleep(120_000)
        val stalls = eventsSince(t0, PlaybackEventType.STALL)
        log("I-TS-19 stall-detector verdicts behind live in 2 min: $stalls; behind ${behindNowMs()} ms")
        if (stalls.isNotEmpty()) assertTrue("I-TS-19 BEHIND unchanged after detector re-prepares", abs(behindNowMs() - b2) <= stalls.size * 1_500L + 3_000 + emuSlack)
        pass("I-TS-19", "${stalls.size} natural stalls")
    }

    // ------------------------------------------------------------------ I-TS-20: settings

    /** I-TS-20a: "Pause and rewind" Off → the file is deleted at the next tune; PLAY_PAUSE → PAUSE_NO_REWIND_OFF. `--set`: none. */
    @Test
    fun its20a_offDeletesTheFile() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(5_000)
        assertTrue("I-TS-20a ring file while buffered", ringFile().exists())
        applyTs("timeshift=off")
        press(K_CH_UP, intervalMs = 0) // a zap keeps the file when buffered; Off makes the next tune unbuffered
        awaitBuffer("I-TS-20a SETTING_OFF", 15_000) { it.off == BufferOff.SETTING_OFF } // WP-B behaviour
        awaitTrue("I-TS-20a ring file deleted", 5_000) { !ringFile().exists() }
        awaitController("I-TS-20a playing", 15_000) { it is PlaybackState.Playing }
        awaitTrue("I-TS-20a the machine knows", 5_000) { info()?.off == BufferOff.SETTING_OFF }
        log("I-TS-20a before PLAY_PAUSE: player ${pui().brief()} info ${info()} ui ${ui().brief()}")
        press(K_PLAY_PAUSE, intervalMs = 0)
        Thread.sleep(300)
        log("I-TS-20a after PLAY_PAUSE: player ${pui().brief()} controller ${controller.state.value}")
        assertTrue("I-TS-20a PAUSE_NO_REWIND_OFF", awaitToast("Rewind is turned off", 5_000))
        pass("I-TS-20a")
    }

    /** I-TS-20b: Pause only writes ≈ 0 at live (`wchar` of this process over 30 s vs the stream rate). `--set`: none. */
    @Test
    fun its20b_pauseOnlyWritesNothingAtLive() {
        bufferedOn(TS_PLAIN)
        val rate = measureRateBps()
        val w0 = wchar()
        Thread.sleep(30_000)
        val persist = wchar() - w0
        applyTs("timeshift=pause_only")
        bufferedOn(TS_PLAIN)
        assertEquals("I-TS-20b PAUSE_ONLY", BufferMode.PAUSE_ONLY, lb()?.mode)
        Thread.sleep(5_000)
        val w1 = wchar()
        Thread.sleep(30_000)
        val pauseOnly = wchar() - w1
        log("I-TS-20b process writes over 30 s: PERSIST $persist B, PAUSE_ONLY $pauseOnly B (stream ${rate * 30} B)")
        assertTrue("I-TS-20b PERSIST writes the stream (≥ half of ${rate * 30} B): $persist", persist >= rate * 15)
        assertTrue("I-TS-20b PAUSE_ONLY writes ≈ 0 at live (< 10 % of the stream): $pauseOnly", pauseOnly < rate * 3)
        pauseByKey()
        Thread.sleep(10_000)
        val w2 = wchar()
        Thread.sleep(10_000)
        log("I-TS-20b PAUSE_ONLY while paused: ${wchar() - w2} B in 10 s")
        assertTrue("I-TS-20b PAUSE_ONLY persists while paused", wchar() - w2 >= rate * 5)
        pass("I-TS-20b")
    }

    /**
     * I-TS-20c: daily cap (debug `ts_cap_bytes=50000000`) → after 50 MB the next tune is PAUSE_ONLY and a rewind that hits
     * the start shows REWIND_LIMITED_TODAY (once a day; the app is reinstalled per Gradle run). `--set`: none.
     */
    @Test
    fun its20c_dailyCapLimitsRewind() {
        applyTs("ts_cap_bytes=50000000")
        bufferedOn(TS_PLAIN)
        val first = lb()!!
        // Integration 2026-10-19 (test fix, class order): the daily counter counts every case's writes today, so in a
        // class run the 50 MB debug cap is already used up and the first tune is PAUSE_ONLY (saving 52 MB would never
        // happen). Alone (fresh counter) the case fills the cap itself first.
        if (first.mode == BufferMode.PAUSE_ONLY && first.limitedToday) {
            log("I-TS-20c the cap was already reached today by earlier cases: ${first.brief()}")
        } else {
            awaitTrue("I-TS-20c 50 MB saved today", 420_000, periodMs = 2_000) { (lb()?.usedBytes ?: 0) >= 52_000_000 }
            bufferedOn(TS_PLAIN) // the cap is decided at prepare (§8.4: never cut a running time-shift)
        }
        val s = lb()!!
        log("I-TS-20c after the cap: ${s.brief()}")
        assertEquals("I-TS-20c PAUSE_ONLY after the cap", BufferMode.PAUSE_ONLY, s.mode) // WP-A/WP-B behaviour
        assertTrue("I-TS-20c limitedToday", s.limitedToday)
        Thread.sleep(30_000)
        press(K_RW, intervalMs = 150)
        press(K_RW, intervalMs = 150)
        assertTrue("I-TS-20c REWIND_LIMITED_TODAY", awaitToast("Rewind is shorter for the rest of today", 8_000)) // WP-C behaviour
        pass("I-TS-20c")
    }

    // ------------------------------------------------------------------ I-TS-21, 22: soak and zap gate

    /**
     * I-TS-21: 2 h behind with a 15-min ring → heap flat, file = capacity; writer CPU, PSI logged. Runs only with
     * `-Pandroid.testInstrumentationRunnerArguments.soak=1` (`soakMinutes=N` shortens it). `--set`: none. LMK kills are a
     * manual check (`adb logcat -b events | grep am_kill`), on the Fire TV in M6.
     */
    @Test
    fun its21_soakTwoHoursBehind() {
        val args = InstrumentationRegistry.getArguments()
        // Integration 2026-10-19: AGP's connected runner reports an assumption failure as a failed test (and fails the
        // Gradle task), so the opt-in case returns with a log line instead of assumeTrue. It runs only with soak=1.
        if (args.getString("soak") != "1") {
            log("I-TS-21: not run (opt-in: -Pandroid.testInstrumentationRunnerArguments.soak=1)")
            return
        }
        val minutes = args.getString("soakMinutes")?.toIntOrNull() ?: 120
        retuneWithRingSeconds(TS_PLAIN, 15 * 60)
        Thread.sleep(30_000)
        pauseByKey()
        Thread.sleep(10 * 60_000L) // 10 min behind, in a ring that holds 15
        resumeByKey()
        val heap = ArrayList<Long>()
        var cpuMax = 0.0
        for (m in 1..minutes) {
            val ticks0 = threadTicks("box-ts-pump") + threadTicks("box-ts-flush")
            Thread.sleep(60_000)
            val cpu = (threadTicks("box-ts-pump") + threadTicks("box-ts-flush") - ticks0) / 100.0 / 60.0
            cpuMax = maxOf(cpuMax, cpu)
            Runtime.getRuntime().gc()
            heap += heapUsedBytes()
            val psi = runCatching { java.io.File("/proc/pressure/memory").readText().lineSequence().firstOrNull() }.getOrNull()
            log("I-TS-21 minute $m: heap ${heap.last() shr 10} KiB native ${android.os.Debug.getNativeHeapAllocatedSize() shr 10} KiB writer CPU ${"%.3f".format(cpu)} file ${ringFile().length()} B behind ${behindNowMs()} ms psi $psi")
            if (controller.state.value is PlaybackState.Error) fail("I-TS-21 error at minute $m: ${controller.state.value}")
        }
        val cap = lb()?.capacityBytes ?: 0
        val warm = heap.drop(minOf(5, heap.size - 1))
        val growth = warm.last() - warm.first()
        log("I-TS-21 heap growth after warm-up ${growth shr 10} KiB; writer CPU max ${"%.3f".format(cpuMax)}; file ${ringFile().length()} B vs capacity $cap B")
        assertTrue("I-TS-21 heap flat (≤ 8 MiB growth after warm-up): ${growth shr 10} KiB", growth <= 8L shl 20)
        assertTrue("I-TS-21 file = capacity (+ header)", ringFile().length() in cap..(cap + 65_536 + 4_096))
        if (!isEmulator()) assertTrue("I-TS-21 writer ≤ 2 % of a core (WRITER_CPU_MAX at 1 MB/s; this clip is ~0.25 MB/s): $cpuMax", cpuMax <= 0.02)
        pass("I-TS-21", "$minutes min")
    }

    /**
     * I-TS-22: 50 zaps (CH+) with the buffer on, then 50 with it Off: first-frame P50/P90 within 5 % (a hardware gate;
     * logged on the emulator). `--set`: none.
     */
    @Test
    fun its22_zapTimeBufferOnVersusOff() {
        fun run(label: String): List<Long> {
            bufferedOn(100, expectBuffered = label == "on")
            val out = ArrayList<Long>()
            repeat(50) { i ->
                // Integration 2026-10-19 (test fix): the zap history is a 50-entry ring (DiagConstants.ZAP_HISTORY), so its
                // size stops growing at the 50th record; a new record is a new last tuneId.
                val before = main { controller.diagnostics.zaps() }.lastOrNull()?.tuneId
                val from = controller.state.value.target?.identity
                press(K_CH_UP, intervalMs = 0)
                awaitTrue("I-TS-22 $label zap ${i + 1}", 20_000, periodMs = 100) {
                    controller.state.value.target?.identity != from && main { controller.diagnostics.zaps() }.lastOrNull().let { z -> z != null && z.tuneId != before && z.firstFrameMs != null }
                }
                main { controller.diagnostics.zaps() }.last().firstFrameMs?.let { out += it }
                Thread.sleep(1_000)
            }
            log("I-TS-22 buffer $label: first frame P50 ${percentile(out, 0.5)} P90 ${percentile(out, 0.9)} ms over ${out.size} zaps")
            return out
        }
        val on = run("on")
        applyTs("timeshift=off")
        val off = run("off")
        val p50 = percentile(on, 0.5)!! to percentile(off, 0.5)!!
        val p90 = percentile(on, 0.9)!! to percentile(off, 0.9)!!
        log("I-TS-22 P50 on/off ${p50.first}/${p50.second} ms, P90 ${p90.first}/${p90.second} ms")
        if (!isEmulator()) {
            assertTrue("I-TS-22 P50 regression ≤ 5 %: $p50", p50.first <= p50.second * 1.05)
            assertTrue("I-TS-22 P90 regression ≤ 5 %: $p90", p90.first <= p90.second * 1.05)
        }
        pass("I-TS-22", "P50 $p50 P90 $p90")
    }

    // ------------------------------------------------------------------ I-TS-23, 24: long pauses

    /**
     * I-TS-23 (U-B1): screen timeout 1 min; pause 8 min; resume → the screen stays on, the dim card after 2 min, the
     * first key only wakes, same tuneId, BEHIND ≈ 8 min. `--set`: none. The case sets `screen_off_timeout` to 60 s and
     * restores it; the Fire TV screensaver and Google TV ambient mode are the manual half (§22).
     */
    @Test
    fun its23_screenStaysOnWhilePausedAndDims() {
        val oldTimeout = shell("settings get system screen_off_timeout").trim()
        shell("settings put system screen_off_timeout 60000")
        try {
            bufferedOn(TS_PLAIN)
            Thread.sleep(5_000)
            val tune0 = tuneId()
            pauseByKey()
            assertTrue("I-TS-23 keepScreenOn while paused and saving", controller.keepScreenOn.value) // WP-B behaviour (§11.5)
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            val asleep = Sampler(1_000) { !pm.isInteractive }
            try {
                Thread.sleep(125_000)
                assertTrue("I-TS-23 dimmed after 2 min: ${tsUi()}", tsUi()?.dimmed == true) // WP-C behaviour
                Thread.sleep(355_000)
            } finally {
                asleep.close()
            }
            val power = shell("dumpsys power").lineSequence().firstOrNull { it.contains("mWakefulness=") }?.trim()
            log("I-TS-23 after 8 min: $power")
            assertFalse("I-TS-23 the screen never went off", asleep.snapshot().any { it })
            assertTrue("I-TS-23 still paused", isLocal(archive(), ArchivePhase.PAUSED))
            press(K_PLAY_PAUSE, intervalMs = 300)
            awaitTrue("I-TS-23 the first key only wakes", 3_000) { tsUi()?.dimmed != true }
            assertTrue("I-TS-23 still paused after the waking key", isLocal(archive(), ArchivePhase.PAUSED))
            resumeByKey()
            assertEquals("I-TS-23 same tuneId", tune0, tuneId())
            Thread.sleep(1_000)
            assertBehind(480_000, 3_000 + emuSlack, "I-TS-23 after resume")
        } finally {
            shell("settings put system screen_off_timeout ${oldTimeout.toLongOrNull() ?: 600000}")
            device.wakeUp()
        }
        pass("I-TS-23")
    }

    /**
     * I-TS-24 (U-B2): archive channel, 2-min ring, pause 4 min, resume → the 2 saved minutes, then one provider request at
     * the gap (no toast), continuous. `--set`: none (ring by `ts_ring_bytes`).
     */
    @Test
    fun its24_archiveGapGoesToTheProviderWithoutToast() {
        retuneWithRingSeconds(TS_ARCHIVE, 120)
        Thread.sleep(3_000)
        pauseByKey()
        Thread.sleep(240_000)
        assertEquals("I-TS-24 saving stopped when the ring filled", RecordStop.FULL_WHILE_PAUSED, lb()?.stop)
        val before = pp.now().unix
        val watch = ToastWatch("Skipped")
        try {
            resumeByKey()
            awaitArchive("I-TS-24 provider takes over at the gap", 200_000) { it.source == TimelineSource.PROVIDER && it.phase == ArchivePhase.PLAYING } // WP-C behaviour (§9.1)
            Thread.sleep(10_000)
        } finally {
            watch.close()
        }
        val reqs = pp.timeshiftLogical(before)
        log("I-TS-24 requests $reqs; gaps ${noticesSince(0).filterIsInstance<PlaybackNotice.LocalGap>()}")
        assertEquals("I-TS-24 one provider request", 1, reqs.size)
        assertFalse("I-TS-24 no GAP_SKIPPED toast", watch.seen("Skipped"))
        assertBehind(240_000, 10_000 + emuSlack, "I-TS-24 continuous across the gap")
        pass("I-TS-24")
    }

    // ------------------------------------------------------------------ I-TS-25: another device takes the slot

    /**
     * I-TS-25 (A-S2): one connection; another device kicks the oldest while paused → SLOT_LOST after at most
     * `PAUSED_RECONNECTS_MAX` (2) reconnects; the other device keeps its slot; the saved part plays.
     * `--set max_connections=1` (the case POSTs it).
     */
    @Test
    fun its25_kickOldestWhilePausedIsSlotLost() {
        tp.config(JSONObject().put("max_connections", 1).put("hls_timeout", 1)) // see I-TS-4 (class order)
        bufferedOn(TS_PLAIN)
        Thread.sleep(20_000)
        val pauseUnix = pauseByKey()
        Thread.sleep(3_000)
        var kicks = 0
        while (lb()?.stop != RecordStop.SLOT_LOST && kicks < 6) {
            val (code, body) = tp.occupy("kick_oldest")
            kicks++
            log("I-TS-25 occupy kick_oldest #$kicks: $code $body")
            Thread.sleep(4_000)
        }
        val reopens = tp.clientOpensSince(pauseUnix)
        log("I-TS-25 stop ${lb()?.stop} after $kicks kicks; client reopens $reopens")
        assertEquals("I-TS-25 SLOT_LOST", RecordStop.SLOT_LOST, lb()?.stop) // WP-A/WP-B behaviour (A-C4)
        assertTrue("I-TS-25 at most 2 reconnects: $reopens", reopens.size <= 2)
        Thread.sleep(5_000)
        assertEquals("I-TS-25 the other device keeps its slot", 1, tp.openOccupiers())
        assertEquals("I-TS-25 the client does not fight", 0, tp.openClientStreams())
        assertEquals("I-TS-25 header SLOT_LOST", RecordStop.SLOT_LOST, archive().savingStop)
        resumeByKey()
        Thread.sleep(10_000)
        assertTrue("I-TS-25 the saved part plays", isLocal(archive(), ArchivePhase.PLAYING) && controller.state.value !is PlaybackState.Error)
        pass("I-TS-25", "$kicks kicks, ${reopens.size} reconnects")
    }

    // ------------------------------------------------------------------ I-TS-26, 27, 28

    /** I-TS-26 (A-S4): after Back to live, 5 min of play rebuffer no more than a fresh tune does. `--set`: none. */
    @Test
    fun its26_backToLiveRebuffersNoMoreThanAFreshTune() {
        bufferedOn(TS_PLAIN)
        val f0 = SystemClock.elapsedRealtime()
        Thread.sleep(300_000)
        val fresh = eventsSince(f0, PlaybackEventType.REBUFFER).size
        rewind(1)
        Thread.sleep(5_000)
        toBareVideo()
        openQuickBar()
        quickSelect(QuickItem.JUMP_TO_LIVE)
        awaitArchive("I-TS-26 at live", 15_000) { it.phase == ArchivePhase.IDLE }
        val b0 = SystemClock.elapsedRealtime()
        Thread.sleep(300_000)
        val after = eventsSince(b0, PlaybackEventType.REBUFFER).size
        log("I-TS-26 rebuffers in 5 min: fresh tune $fresh, after Back to live $after")
        assertTrue("I-TS-26 rebuffers after Back to live ($after) ≤ fresh ($fresh)${if (isEmulator()) " + 1 (emulator)" else ""}", after <= fresh + if (isEmulator()) 1 else 0)
        pass("I-TS-26", "$after vs $fresh")
    }

    /**
     * I-TS-27 (A-B2): rewind 10 min, then 60 s of play → no DISK_TOO_SLOW, no pool wait. `--set`: none. The pool-wait
     * counter is not exposed; DISK_TOO_SLOW (the pool's timeout) and a stall-free minute stand for it.
     */
    @Test
    fun its27_rewindTenMinutesThenPlayNoDiskTooSlow() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(11 * 60_000L)
        val t0 = SystemClock.elapsedRealtime()
        rewind(20)
        Thread.sleep(60_000)
        val stops = decisions("saving_stopped", t0)
        log("I-TS-27 behind ${behindNowMs()} ms; stops $stops; stalls ${eventsSince(t0, PlaybackEventType.STALL)}")
        assertTrue("I-TS-27 ~10 min behind", behindNowMs() >= 540_000)
        assertTrue("I-TS-27 no DISK_TOO_SLOW: $stops", stops.none { it.detail == RecordStop.DISK_TOO_SLOW.name } && lb()?.stop != RecordStop.DISK_TOO_SLOW)
        assertTrue("I-TS-27 no stall", eventsSince(t0, PlaybackEventType.STALL).isEmpty())
        pass("I-TS-27")
    }

    /**
     * I-TS-28 (A-S7): the ring file is deleted while saving → STORAGE_EVICTED, REWIND_STOPPED_STORAGE, live continues,
     * the space is freed (no fd left on the deleted file). Also checks §22's `st_nlink` claim. `--set`: none.
     */
    @Test
    fun its28_deletedRingFileIsStorageEvicted() {
        bufferedOn(TS_PLAIN)
        Thread.sleep(40_000)
        rewind(1) // behind live, where the toast is shown (§13)
        assertTrue("I-TS-28 ring file present", ringFile().exists())
        assertTrue("I-TS-28 deleted", ringFile().delete())
        awaitBuffer("I-TS-28 STORAGE_EVICTED", 10_000) { it.stop == RecordStop.STORAGE_EVICTED } // WP-A/WP-B behaviour (st_nlink)
        assertTrue("I-TS-28 REWIND_STOPPED_STORAGE", awaitToast("saving to storage failed", 8_000))
        awaitTrue("I-TS-28 space freed (no fd on the deleted file)", 10_000) { deletedRingFds().isEmpty() }
        val t0 = SystemClock.elapsedRealtime()
        Thread.sleep(20_000)
        assertTrue("I-TS-28 playback continues", controller.state.value is PlaybackState.Playing && eventsSince(t0, PlaybackEventType.ERROR).isEmpty())
        pass("I-TS-28")
    }

    // ------------------------------------------------------------------ I-TS-29: zap while paused ≥ 2 min

    /** Tunes [first] then [id] (so Last goes to [first]), pauses 2:05 and wakes the dimmed picture. */
    private fun pausedTwoMinutesOn(id: Int, first: Int = TS_PLAIN_2): app.thebox.core.refresh.ChannelIdentity {
        val firstIdentity = bufferedOn(first).identity
        Thread.sleep(3_000)
        bufferedOn(id)
        Thread.sleep(3_000)
        pauseByKey()
        Thread.sleep(125_000)
        toBareVideo()
        wakeIfDimmed()
        // Integration 2026-10-19 (test fix): `zapNeedsConfirm` is the number entry's commit hook, not a query: polling it
        // held a zap back (toast) and armed the confirm window, so the case's own first press zapped at once. Wait on the
        // pure fact instead: the LOCAL pause began at least ZAP_CONFIRM_AFTER_PAUSE_MS ago.
        awaitTrue("zap confirm due (paused ≥ 2 min)", 3_000) {
            tsUi()?.pausedSinceMs?.let { System.currentTimeMillis() - it >= app.thebox.core.playback.timeshift.TimeshiftConstants.ZAP_CONFIRM_AFTER_PAUSE_MS } == true
        } // WP-C behaviour
        return firstIdentity
    }

    /** First [key] → ZAP_CONFIRM and no zap; the same key within 3 s zaps. */
    private fun assertConfirmedZap(case: String, key: Int) {
        val identity = controller.state.value.target?.identity
        press(key, intervalMs = 0)
        assertTrue("$case ZAP_CONFIRM", awaitToast("Changing channel ends your pause", 3_000))
        assertEquals("$case no zap on the first press", identity, controller.state.value.target?.identity)
        assertTrue("$case still paused", isLocal(archive(), ArchivePhase.PAUSED))
        press(key, intervalMs = 0)
        awaitController("$case second press zaps", 15_000) { it.target?.identity != identity && it.target != null }
    }

    /** I-TS-29a (lead ruling 6): paused 2 min, CH+ → toast and no zap; CH+ again within 3 s → zap. `--set`: none. */
    @Test
    fun its29a_zapConfirmChannelUp() {
        pausedTwoMinutesOn(TS_PLAIN)
        assertConfirmedZap("I-TS-29a", K_CH_UP)
        pass("I-TS-29a")
    }

    /** I-TS-29b: paused 2 min, Last → toast and no zap; Last again → zap to the previous channel. `--set`: none. */
    @Test
    fun its29b_zapConfirmLast() {
        val previous = pausedTwoMinutesOn(TS_PLAIN, first = TS_PLAIN_2)
        assertConfirmedZap("I-TS-29b", K_LAST)
        awaitTrue("I-TS-29b on the previous channel", 5_000) { controller.state.value.target?.identity == previous }
        pass("I-TS-29b")
    }

    /** I-TS-29c: paused 2 min, digits → ZAP_CONFIRM_NUMBER (the number stays); OK → zap. `--set`: none. */
    @Test
    fun its29c_zapConfirmDigits() {
        pausedTwoMinutesOn(TS_PLAIN)
        val identity = controller.state.value.target?.identity
        digits(TS_PLAIN_2.toString())
        assertTrue("I-TS-29c ZAP_CONFIRM_NUMBER", awaitToast("Press OK to change", 5_000)) // WP-C behaviour (NumberEntryImpl)
        assertEquals("I-TS-29c no zap yet", identity, controller.state.value.target?.identity)
        press(K_OK, intervalMs = 0)
        awaitController("I-TS-29c OK zaps", 15_000) { it.target?.identity != identity && it.target != null }
        pass("I-TS-29c")
    }

    /** I-TS-29d: paused 2 min, Back to the guide and OK on another row → zaps at once (no confirm). `--set`: none. */
    @Test
    fun its29d_guideZapsAtOnce() {
        pausedTwoMinutesOn(TS_PLAIN)
        val identity = controller.state.value.target?.identity
        press(K_BACK) // bare video → GUIDE (back=guide)
        awaitUi("I-TS-29d guide", 5_000) { it.mode == ScreenMode.GUIDE }
        val watch = ToastWatch("Changing channel ends your pause")
        try {
            press(K_DOWN)
            press(K_OK, intervalMs = 0)
            awaitController("I-TS-29d the guide zaps at once", 15_000) { it.target?.identity != identity && it.target != null }
            Thread.sleep(1_000)
        } finally {
            watch.close()
        }
        assertFalse("I-TS-29d no ZAP_CONFIRM from the guide", watch.seen("Changing channel ends your pause"))
        pass("I-TS-29d")
    }

    // ------------------------------------------------------------------ I-TS-30: the Onn key set

    /**
     * I-TS-30 (U-B3): remote without media keys: ↑ OK on a TS, an HLS and an Off channel always pauses (TS: LOCAL, focused
     * scrub bar at the pause point) or freezes with the reason toast. `--set`: none.
     */
    @Test
    fun its30_upOkPausesOnTsHlsAndOff() {
        fun upOk(label: String) {
            openQuickBar()
            val items = pui().quick!!.items
            log("I-TS-30 $label quick bar: $items")
            assertEquals("I-TS-30 $label: Pause first", QuickItem.TIME_SHIFT, items.first()) // WP-C behaviour
            quickSelect(QuickItem.TIME_SHIFT)
        }
        bufferedOn(TS_PLAIN)
        Thread.sleep(5_000)
        upOk("TS")
        awaitArchive("I-TS-30 TS paused LOCAL", 5_000) { isLocal(it, ArchivePhase.PAUSED) }
        awaitPlayer("I-TS-30 focused scrub bar at the pause point", 3_000) { it.layer == PlayerLayer.SCRUB && it.scrub?.focused == true }

        bufferedOn(fx.multiHls, expectBuffered = false)
        upOk("HLS")
        assertTrue("I-TS-30 HLS reason", awaitToast("can't be rewound", 5_000))
        awaitController("I-TS-30 HLS frozen", 5_000) { it is PlaybackState.Playing && it.paused }

        applyTs("timeshift=off")
        bufferedOn(TS_PLAIN, expectBuffered = false)
        awaitTrue("I-TS-30 Off known", 5_000) { info()?.off == BufferOff.SETTING_OFF }
        upOk("Off")
        assertTrue("I-TS-30 Off reason", awaitToast("Rewind is turned off", 5_000))
        awaitController("I-TS-30 Off frozen", 5_000) { it is PlaybackState.Playing && it.paused }
        pass("I-TS-30")
    }
}
