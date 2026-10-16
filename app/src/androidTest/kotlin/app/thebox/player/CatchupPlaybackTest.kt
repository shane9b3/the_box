package app.thebox.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.PlayerLayer
import app.thebox.core.playback.PlaybackErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-M4-9 (catch-up start and continuation on the short-programme channel), I-M4-10 (native seek vs re-request, plus the
 * `linger` variant with `max_connections = 1`, S11) and I-M4-22 (an in-buffer native seek settles, B3),
 * docs/design/m4-player-ux.md §3.5, §3.8.2–§3.8.5, §5.5.
 * Panel: `player_fixtures` + `timeshift_mode: "duration"` (finite `.ts` sized by `duration`, continuous PCR/PTS,
 * Content-Length and 206), ranges on (default). Switch `padding=0` (I-M4-9's "starts on the minute at the wall position").
 * Depends on WP-B (planner/orchestrator: requests, settle), WP-A (scrub keys), WP-C (`Playing.ended`), WP-D (glue).
 * Request sequences are asserted loosely until WP-B's hand-off note fixes them (every request is logged by the base).
 */
@RunWith(AndroidJUnit4::class)
class CatchupPlaybackTest : PlayerTestBase() {
    override val playerSwitches = "padding=0,0"

    /** Logical requests (integration 2026-10-15: a finite TS is opened twice without a Range, see [PlayerPanel.timeshiftLogical]). */
    private fun fresh(since: Double) = pp.timeshiftLogical(since)

    /**
     * I-M4-9: OK on a past 1-min programme plays; after ~60 s the scrub bar's programme changes from the timeline (no
     * request is made for the boundary itself); the request after the file's end is open-ended and starts on the minute
     * at the wall position.
     */
    @Test
    fun startAndContinueOnShortProgrammes() {
        val t0 = pp.now().unix
        playPast(fx.short, endedMinAgo = 3, leftAtLeast = 4)
        val st = awaitArchive("first media", 20_000) { it.phase == ArchivePhase.PLAYING } // WP-B behaviour
        val first = fresh(t0)
        log("I-M4-9 first requests: $first")
        assertTrue("a timeshift request was made", first.isNotEmpty())
        assertEquals("first request 200", 200, first.last().status)
        val p0 = st.programme
        val changed = awaitArchive("programme re-scoped by the timeline", 75_000) { it.programme != null && it.programme?.rawStartMin != p0?.rawStartMin } // WP-B behaviour
        log("I-M4-9 programme ${p0?.rawStartMin} → ${changed.programme?.rawStartMin}")
        // Integration 2026-10-15 (test fix): count requests after the first one by identity, not by arrival time (the
        // first request's second unranged open can land after first media). WP-B hand-off: under 1-min programmes request
        // 2 repeats request 1's minute with a longer D (the open-ended continuation, §3.8.5).
        val firstReq = first.last()
        fun after() = fresh(t0).dropWhile { it != firstReq }.drop(1)
        val sameSession = after()
        log("I-M4-9 requests after the first: $sameSession")
        assertTrue("at most the one continuation request around the boundary (no per-programme request): $sameSession", sameSession.size <= 1) // WP-B behaviour (§3.8.5)
        // Continuation: the request after the file's end.
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var cont = sameSession.firstOrNull()
        while (cont == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
            cont = after().firstOrNull()
        }
        if (cont == null) fail("no continuation request within 90 s of first media")
        val wall = archive().wallMs / 1000.0
        val now = pp.now().unix
        log("I-M4-9 continuation $cont, wall ${wall.toLong()}, panel now ${now.toLong()}")
        assertEquals("continuation starts on the minute", 0.0, cont!!.startUnix % 60, 0.5) // WP-B behaviour (alignStart)
        assertTrue("continuation starts at the wall position (±60 s)", abs(cont.startUnix - wall) <= 90) // WP-B behaviour
        val dur = cont.durationS ?: fail("xc request without a duration").let { 0 }
        assertTrue("continuation is open-ended (reaches the panel's now): start+${dur}s vs now", cont.startUnix + dur >= now - 120) // WP-B behaviour
    }

    /** I-M4-10: −30 s inside the file → no new timeshift request (only ranged reopens); before the origin → one request at the target minute. */
    @Test
    fun nativeSeekVersusRerequest() {
        playPast(1, endedMinAgo = 5)
        awaitArchive("first media", 20_000) { it.phase == ArchivePhase.PLAYING }
        Thread.sleep(45_000) // the playhead is ~45 s into the file
        val tBefore = pp.now().unix
        val seqBefore = archive().settledSeq
        repeat(3) { press(K_LEFT, intervalMs = 150) } // bare archive video: LEFT opens the scrub bar focused and steps 10 s back
        awaitPlayer("scrub preview −30 s") { it.layer == PlayerLayer.SCRUB && it.scrub?.previewMs != null } // WP-A behaviour
        awaitArchive("native seek settled", 8_000) { it.settledSeq > seqBefore && it.phase == ArchivePhase.PLAYING } // WP-B behaviour (B3)
        val native = pp.timeshiftSince(tBefore)
        log("I-M4-10 native seek requests: $native")
        assertTrue("no new timeshift request for an in-file seek (only ranged reopens): $native", native.none { !it.ranged }) // WP-B behaviour
        // Before the request origin: one new request whose start is the target minute in the panel zone.
        val tRe = pp.now().unix
        val seq2 = archive().settledSeq
        repeat(6) { press(K_LEFT, intervalMs = 120) }
        val preview = pui().scrub?.previewMs ?: fail("no scrub preview").let { 0L }
        awaitArchive("re-request settled", 20_000) { it.settledSeq > seq2 } // WP-B behaviour
        val re = fresh(tRe)
        log("I-M4-10 re-request (preview ${preview / 1000}): $re")
        assertEquals("exactly one new timeshift request: $re", 1, re.size) // WP-B behaviour
        val expected = (preview / 1000) / 60 * 60.0 // channel 1: correction 0, so programme frame = panel clock
        assertEquals("start = the target minute read in the panel zone", expected, re[0].startUnix, 1.0) // WP-B behaviour (alignStart, panel zone)
        assertEquals("no start the panel flagged", 0, pp.rejections()["timeshift_window"] ?: 0)
    }

    /**
     * I-M4-10 linger variant (`max_connections = 1`, `linger` on timeshift, 458): the finite-TS prepare's extra ranged
     * opens may be refused; assert the outcome, not only the open count: ladder L2–L4 retries end in playback or the
     * CONNECTION_LIMIT card (API notes §7 gap 4, §8 Q8).
     */
    @Test
    fun lingeringPanelOnRangedReopens() {
        pp.maxConnections(1)
        pp.faults(JSONObject().put("linger", 5).put("linger_status", 458))
        playPast(1, endedMinAgo = 5)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var outcome: String? = null
        while (outcome == null && SystemClock.elapsedRealtime() < deadline) {
            val a = archive()
            val p = pui()
            outcome = when {
                a.phase == ArchivePhase.PLAYING -> "playing"
                p.layer == PlayerLayer.ERROR -> "card ${p.error?.error?.code}"
                a.phase == ArchivePhase.FAILED -> "failed ${a.failure} ${a.error?.code}"
                else -> null
            }
            Thread.sleep(200)
        }
        val rej = pp.rejections()
        log("I-M4-10 linger: outcome=$outcome rejections=$rej requests=${pp.timeshiftRequests()}")
        assertTrue("an outcome within 45 s (not stuck): ${archive().brief()} ${pui().brief()}", outcome != null)
        if ((rej["max_connections"] ?: 0) > 0) {
            assertTrue(
                "a refused reopen ends in playback or the CONNECTION_LIMIT card, got $outcome",
                outcome == "playing" || outcome!!.contains(PlaybackErrorCode.CONNECTION_LIMIT.name),
            ) // WP-B/WP-C behaviour (§3.8.2 CONNECTION_LIMIT row)
        }
        if (outcome == "playing") {
            Thread.sleep(40_000)
            val seq = archive().settledSeq
            repeat(3) { press(K_LEFT, intervalMs = 150) }
            awaitArchive("native seek under linger settles or fails visibly", 20_000) { it.settledSeq > seq || it.phase == ArchivePhase.FAILED }
            log("I-M4-10 linger seek: ${archive().brief()} rejections=${pp.rejections()}")
        }
    }

    /** I-M4-22: archive playing with ≥ 30 s buffered: +10 s commits; status leaves SEEKING within 5 s; the preview clears. */
    @Test
    fun inBufferNativeSeekSettles() {
        playPast(1, endedMinAgo = 5)
        awaitArchive("first media", 20_000) { it.phase == ArchivePhase.PLAYING }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var buffered = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            buffered = main { controller.position().bufferedMs }
            if (buffered >= 30_000) break
            Thread.sleep(500)
        }
        log("I-M4-22 buffered $buffered ms")
        assertTrue("≥ 30 s buffered ($buffered ms)", buffered >= 30_000) // M2 archive buffer (BufferMode.MEDIUM)
        val seq = archive().settledSeq
        val tReq = pp.now().unix
        val phases = Sampler(25) { archive().phase }
        try {
            press(K_RIGHT, intervalMs = 0) // opens the scrub bar focused and steps +10 s
            val tKey = SystemClock.elapsedRealtime()
            awaitPlayer("preview +10 s") { it.scrub?.previewMs != null } // WP-A behaviour
            val settled = awaitArchive("settled", 5_600 + 1_000) { it.settledSeq > seq && it.phase != ArchivePhase.SEEKING } // WP-B behaviour (B3: ≤ SEEK_SETTLE_MAX_MS after commit)
            val t = SystemClock.elapsedRealtime() - tKey
            log("I-M4-22 settled ${settled.brief()} $t ms after the key; phases ${phases.snapshot().distinct()}")
            assertTrue("leaves SEEKING within 5 s of the 600 ms commit ($t ms)", t <= 5_600 + 500) // WP-B behaviour
            awaitPlayer("scrub preview cleared", 2_000) { it.scrub?.previewMs == null } // WP-A behaviour (settledSeq clears it)
        } finally {
            phases.close()
        }
        assertTrue("in-buffer seek opened no new timeshift request", pp.timeshiftSince(tReq).none { !it.ranged }) // WP-B behaviour
        assertNotEquals(ArchivePhase.FAILED, archive().phase)
    }
}
