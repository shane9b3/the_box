package app.thebox.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.PlayerLayer
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ScreenMode
import app.thebox.player.api.PlaybackState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-13 (pause live, resume from the archive, 150 s run, S1), I-M4-13b (the same on the `catchup-correction="+2"`
 * channel, B2), I-M4-14 (archive lag → one step back, then live), I-M4-15 (jump to live and the 30 s live snap),
 * docs/design/m4-player-ux.md §3.8.1, §3.8.5, §3.8.7, §5.5.
 * Panel: `player_fixtures` (channel 3 = +2 h correction), panel zone Europe/Berlin (≠ UTC), `timeshift_mode: "duration"`;
 * I-M4-14 sets `archive_lag_sec: 180` (WP-B hand-off; the design said 120). Expected starts are computed on the **panel's** clock (`/admin/now`): a live-anchored
 * request has no EPG component, so its A-frame start is the panel's now minus the pause (B2/F1).
 * Depends on WP-B (pauseLive/resume, frames, lag rule, continuation), WP-A (PLAY_PAUSE, scrub keys, pill), WP-C (pause
 * release, E-L2), WP-D (glue, ARCHIVE_NOT_READY toast — not observable here, logged only).
 */
@RunWith(AndroidJUnit4::class)
class TimeShiftTest : PlayerTestBase() {

    /** [bufferedMs]: the live buffer at the pause; N3 resumes at the pause point minus it, so the pill reads pause + it. */
    private class Paused(val channel: GuideChannel, val pauseUnix: Double, val resumeUnix: Double, val bufferedMs: Long)

    /** FULL on [id] live, PLAY_PAUSE, wait [pauseMs], PLAY_PAUSE. */
    private fun pauseAndResume(id: Int, pauseMs: Long, checkRelease: Boolean = false, beforeResume: (pauseUnix: Double) -> Unit = {}): Paused {
        val ch = fullOn(id)
        awaitController("live playing") { it is PlaybackState.Playing && !it.target.isArchive }
        assertTrue("${ch.displayName} has archive", ch.archiveDays > 0)
        Thread.sleep(3_000)
        val bufferedMs = main { controller.position().bufferedMs }.coerceAtLeast(0)
        val pauseUnix = pp.now().unix
        press(K_PLAY_PAUSE, intervalMs = 0)
        awaitArchive("PAUSED_LIVE", 5_000) { it.phase == ArchivePhase.PAUSED_LIVE } // WP-B behaviour
        log("live buffer at the pause: $bufferedMs ms")
        if (checkRelease) {
            Thread.sleep(12_000)
            val open = pp.openConnections()
            log("I-M4-13 12 s into the pause: controller=${controller.state.value} panel open=$open")
            assertEquals("live connection released after 10 s", 0, open) // M2 LIVE_PAUSE_RELEASE_MS (WP-C E-L2)
            Thread.sleep((pauseMs - 12_000).coerceAtLeast(0))
        } else {
            Thread.sleep(pauseMs)
        }
        beforeResume(pauseUnix)
        val resumeUnix = pp.now().unix
        press(K_PLAY_PAUSE, intervalMs = 0)
        return Paused(ch, pauseUnix, resumeUnix, bufferedMs)
    }

    private fun firstRequestAfter(sinceUnix: Double, timeoutMs: Long = 15_000): PlayerPanel.TsRequest {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            pp.timeshiftSince(sinceUnix).firstOrNull { !it.ranged }?.let { return it }
            Thread.sleep(200)
        }
        fail("no timeshift request after resume within $timeoutMs ms")
        error("unreachable")
    }

    /** Pause point on the panel clock minus the live buffer (N3) and the minute floor (xc): start ∈ [pause − 75 s, pause + 5 s]. */
    private fun assertStartAtPause(req: PlayerPanel.TsRequest, pauseUnix: Double, what: String) {
        log("$what: request $req, pause at ${pauseUnix.toLong()} (panel clock)")
        assertTrue(
            "$what: start ${req.startUnix.toLong()} is the pause instant ${pauseUnix.toLong()} on the panel's clock (±60 s alignment + live buffer)",
            req.startUnix in (pauseUnix - 75)..(pauseUnix + 5),
        ) // WP-B behaviour
    }

    /**
     * I-M4-13: PLAY_PAUSE on an archive channel, 20 s: live connection released after 10 s; resume → a timeshift request at
     * the pause instant (± 60 s); then 150 s: the pill stays −00:20 ± 3 s, 2–3 continuation requests (WP-B hand-off), never live.
     */
    @Test
    fun pauseLiveAndResume150s() {
        val p = pauseAndResume(1, 20_000, checkRelease = true)
        val req = firstRequestAfter(p.resumeUnix - 1)
        assertStartAtPause(req, p.pauseUnix, "I-M4-13")
        awaitArchive("time-shift playing", 20_000) { it.phase == ArchivePhase.PLAYING && it.timeShift } // WP-B behaviour
        Thread.sleep(1_500)
        // Integration 2026-10-15: on the emulator the time-shift start-up (resume key → first media, 1–1.3 s on
        // SwiftShader) adds to the offset; 22.9 and 23.1 s measured. The design's ± 3 s holds on hardware (M6).
        val tol = if (isEmulator()) 4_000L else 3_000L
        // Integration 2026-10-15 (test fix): N3 resumes at the pause point minus the live buffer, so −00:20 + that buffer.
        assertBehind(20_000 + p.bufferedMs, tol, "I-M4-13 after resume")
        val behindAfterResume = behindNowMs()
        val live = Sampler(500) { controller.state.value.let { it.target?.isArchive == false && it is PlaybackState.Playing } }
        try {
            Thread.sleep(150_000)
        } finally {
            live.close()
        }
        // Requests after the resume request, by identity (its second unranged open, after the duration read, can land
        // after the run starts and is not a continuation).
        val conts = pp.timeshiftLogical(p.resumeUnix - 1).dropWhile { it != req }.drop(1)
        log("I-M4-13 continuations in 150 s: $conts")
        assertFalse("never live during the run", live.snapshot().any { it }) // WP-B behaviour (CONTINUE_SNAP_MS = 5 s)
        // WP-B hand-off (integration 2026-10-15): each xc open request is ceil((now − S)/60) minutes and the user is ~20 s
        // behind, so a 150 s run makes 2–3 continuations (the design's "exactly one" assumed a longer D). S1's intent is
        // "never live, the offset holds", asserted by the pill and the live sampler.
        assertTrue("2–3 continuation requests in 150 s: $conts", conts.size in 2..3) // WP-B behaviour (S1; xc open D)
        if (isEmulator()) {
            // Integration 2026-10-15: on the emulator the audio HAL's underrun storms trigger L32 re-prepares (M2 §5.13) that
            // each add ~1–1.5 s to the offset (22.6 → 29.7 s measured over three of them); the offset may grow by stall
            // time but must never move towards live (S1). The design's ± 3 s over 150 s is a hardware check (M6).
            val b = behindNowMs()
            log("I-M4-13 after 150 s: behind $b ms (after resume $behindAfterResume ms)")
            assertTrue("I-M4-13 after 150 s: never closer to live than after resume ($b vs $behindAfterResume ms)", b >= behindAfterResume - 3_000)
            assertTrue("I-M4-13 after 150 s: growth bounded by stalls ($b ms)", b <= behindAfterResume + 15_000)
        } else {
            assertBehind(20_000 + p.bufferedMs, 3_000, "I-M4-13 after 150 s")
        }
    }

    /** I-M4-13b: `catchup-correction="+2"`, panel zone ≠ UTC: pause 20 s, resume → start = the pause instant on the panel's clock; pill −00:20. */
    @Test
    fun positiveCorrectionResumesAtThePanelClock() {
        val zone = pp.now().timezone
        assertTrue("panel zone is not UTC ($zone)", zone != "UTC" && zone != "Etc/UTC")
        val ch = fx.plus2
        // Live-anchored: no EPG component, so no correction is applied to the expected instant (B2/F1).
        val warnings0 = pp.timeshiftWarnings()
        val p = pauseAndResume(ch, 20_000) { pauseUnix ->
            val exp = pp.expectTimeshift(pauseUnix.toLong(), channel = ch, applyCorrection = false)
            log("I-M4-13b expect $exp")
        }
        val req = firstRequestAfter(p.resumeUnix - 1)
        assertStartAtPause(req, p.pauseUnix, "I-M4-13b")
        assertTrue("not two hours in the past (B2)", p.pauseUnix - req.startUnix < 3_600) // WP-B behaviour
        awaitArchive("time-shift playing", 20_000) { it.phase == ArchivePhase.PLAYING }
        Thread.sleep(1_500)
        // Integration 2026-10-15 (test fix): pause + live buffer at the pause (N3); emulator start-up as in I-M4-13.
        assertBehind(20_000 + p.bufferedMs, if (isEmulator()) 4_000L else 3_000L, "I-M4-13b")
        assertEquals("the panel matched the start against timeshift_expect (no TZ warning)", warnings0, pp.timeshiftWarnings()) // WP-B behaviour
    }

    /**
     * I-M4-14: `archive_lag_sec: 180` (design text: 120; WP-B hand-off: the first request floors the pause instant to the
     * minute, up to ~80 s behind now, so with 120 the step back 60 s lands outside the lag and plays): pause 20 s, resume →
     * one step back (60 s), then live with ARCHIVE_NOT_READY; no winner, no ↺.
     */
    @Test
    fun archiveLagStepsBackOnceThenLive() {
        pp.archiveLag(180)
        val winner0 = runBlocking { ep.playlists().get(playlistId)?.catchupProbeWinner }
        val p = pauseAndResume(1, 20_000)
        awaitController("back to live", 30_000) { it is PlaybackState.Playing && !it.target.isArchive } // WP-B behaviour (LiveNotReady)
        val reqs = pp.timeshiftLogical(p.resumeUnix - 1)
        log("I-M4-14 requests $reqs rejections ${pp.rejections()}")
        assertEquals("two archive requests: the first and one step back: $reqs", 2, reqs.size) // WP-B behaviour
        assertTrue("both refused as lag", reqs.all { it.status == 404 })
        assertEquals("step back of 60 s (same variant)", reqs[0].startUnix - 60, reqs[1].startUnix, 1.0) // WP-B behaviour (ARCHIVE_LAG_STEP_MS)
        assertEquals("same variant", reqs[0].form, reqs[1].form)
        assertEquals("no winner change", winner0, runBlocking { ep.playlists().get(playlistId)?.catchupProbeWinner })
        assertTrue("archive session idle", archive().phase == ArchivePhase.IDLE) // WP-B behaviour
        toBareVideo()
        press(K_BACK)
        awaitUi("guide", 5_000) { it.mode == ScreenMode.GUIDE }
        assertFalse("no hollow ↺ for a lag", onFrame { f -> f.suspectArchive.contains(p.channel.identityHash) }) // WP-A behaviour
    }

    /** I-M4-15: 40 s behind, UP in the scrub bar → live; scrub to 10 s behind → live (30 s snap). */
    @Test
    fun jumpToLiveAndLiveSnap() {
        pauseAndResume(1, 40_000)
        awaitArchive("time-shift playing", 20_000) { it.phase == ArchivePhase.PLAYING && it.timeShift }
        toBareVideo()
        press(K_DOWN) // archive bare video: DOWN = scrub bar focused
        awaitPlayer("scrub focused") { it.layer == PlayerLayer.SCRUB && it.scrub?.focused == true } // WP-A behaviour
        press(K_UP) // owner ruling: Up in the scrub bar = jump to live
        awaitController("UP jumps to live", 15_000) { it is PlaybackState.Playing && !it.target.isArchive } // WP-A/WP-B behaviour
        awaitArchive("session ended", 5_000) { it.phase == ArchivePhase.IDLE }

        // Second part: 40 s behind again, scrub forward to ~10 s behind → the commit snaps to live.
        toBareVideo()
        Thread.sleep(2_000)
        val pauseUnix = pp.now().unix
        press(K_PLAY_PAUSE, intervalMs = 0)
        awaitArchive("PAUSED_LIVE", 5_000) { it.phase == ArchivePhase.PAUSED_LIVE }
        Thread.sleep(40_000)
        press(K_PLAY_PAUSE, intervalMs = 0)
        awaitArchive("time-shift playing again", 20_000) { it.phase == ArchivePhase.PLAYING && it.timeShift }
        log("I-M4-15 paused at ${pauseUnix.toLong()}")
        toBareVideo()
        repeat(3) { press(K_RIGHT, intervalMs = 120) } // +30 s → ~10 s behind
        awaitController("preview within 30 s of the edge commits to live", 15_000) { it is PlaybackState.Playing && !it.target.isArchive } // WP-A/WP-B behaviour (LIVE_SNAP_MS)
    }
}
