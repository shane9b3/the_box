package app.thebox.player

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.full.PlayerLayer
import app.thebox.core.sports.NhlBugStyle
import app.thebox.player.api.PlaybackState
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.sports.COMPOSITIONS_STACKED_NFL_BUG
import app.thebox.ui.sports.DRAWS_STACKED_NFL_BUG
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * NFL Stacked score bug, docs/design/m6-nfl-stacked.md §4.5 and §9.5 item 9: frame cost over live video in FULL while the
 * Stacked NFL takeover plays (one score, or the +7 pair), and while the flag bar shakes and waves (the one open-ended
 * motion, bounded by the 8-s hold), with the GA §8.4 gates; then the idle budget. Beside [NflFramesTest] and
 * [StackedFramesTest], whose anchoring it shares.
 *
 * **Not run by the agent that wrote it** (instrumented tests wipe the app on the attached device); the lead runs it.
 * Prerequisites, since the test cannot inject a score source into the real activity:
 * - the fake panel runs with `--sports --sports-nfl`, the debug build points at it (`files/debug/sports_base`), and the
 *   featured NFL game (KC @ BUF, `401899001`) is followed with Show Sports on;
 * - the **size and corner** under test are set by hand; the test sets **Style → Stacked** itself through the quick bar's
 *   Score bug sheet (the Style row shows for an NFL follow, §7) and restores the previous style in a `finally`;
 * - run it with `-e stackedNflFrames 1` and `-e stackedNflWhat score` (default: the first touchdown), `pair` (the Q2 +7
 *   pair) or `flag` (the first Q1 flag, WP-5's type-8 window). The panel is the test's own unless `-e sportsPanel <base>`.
 *   The §9.5 matrix: score, pair and flag, each at Standard and Large.
 *
 * Anchoring: the test re-anchors the NFL timeline (`POST /admin/sports-nfl {"restart": true}`) and arms on the featured
 * game's **state** read from `GET /admin/sports-nfl`, never on a draw burst (every play's 550-ms REKEY is a burst): a score
 * rise (≥ 7 in one read for the pair) or a `lastPlayType` "8" / a `lastPlayText` with "PENALTY on" for the flag (WP-5's
 * state keys). It then waits for the burst of the bug's own draws ([DRAWS_STACKED_NFL_BUG], debug builds) and records,
 * over the window (4.8 s, 9.6 s for the pair, 8 s for the flag hold): the P90 `TOTAL_DURATION`, the frames over 33 ms and
 * the frame count. Gates: emulator ≥ 40 frames per second of window; device P90 < 16.7 ms and ≤ 3 frames over 33 ms.
 * Afterwards the window must go idle: ≤ 1 frame per 10 s within 1 s of the end of the burst.
 */
@RunWith(AndroidJUnit4::class)
class StackedNflFramesTest : PlayerTestBase() {

    @Test
    fun stackedNflFrameCost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "pass -e stackedNflFrames 1 with the fake panel's NFL game followed and Show Sports on",
            args.getString("stackedNflFrames") == "1",
        )
        assumeTrue("the draw counter is debug-only", GuideDebugCounters.ENABLED)
        val what = args.getString("stackedNflWhat") ?: "score"
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }

        val previous = setNhlStyle(NhlBugStyle.STACKED)
        assumeTrue("the sheet has a Style row (a game followed)", previous != null)
        try {
            measure(args, what)
        } finally {
            if (previous != NhlBugStyle.STACKED) setNhlStyle(previous!!)
        }
    }

    private fun measure(args: android.os.Bundle, what: String) {
        Thread.sleep(2_000)
        assumeTrue("the NFL Stacked bug is composed (is KC @ BUF followed?)", GuideDebugCounters.count(COMPOSITIONS_STACKED_NFL_BUG) > 0)
        val sportsBase = args.getString("sportsPanel") ?: pp.base
        val restarted = restartNfl(sportsBase)
        assumeTrue("the panel at $sportsBase serves no NFL (start it with --sports --sports-nfl)", restarted.optBoolean("enabled", true))
        val windowMs = when (what) {
            "pair" -> PAIR_WINDOW_MS
            "flag" -> FLAG_WINDOW_MS
            else -> WINDOW_MS
        }
        log("NFL re-anchored; waiting for the $what")
        val startedAt = when (what) {
            "flag" -> awaitState(sportsBase, FLAG_TIMEOUT_MS, "no flag in the panel's NFL timeline") { _, s -> isFlag(s) }
            "pair" -> awaitState(sportsBase, PAIR_TIMEOUT_MS, "no +7 rise") { last, s -> score(s) - score(last) >= 7 }
            else -> awaitState(sportsBase, SCORE_TIMEOUT_MS, "no score rise") { last, s -> score(s) > score(last) }
        }

        val totals = Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("stacked-nfl-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val opened = SystemClock.uptimeMillis()
        try {
            while (SystemClock.uptimeMillis() - opened < windowMs) Thread.sleep(POLL_MS)
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(listener) }
        }
        val frames = totals.toList().sorted()
        val p90Ms = if (frames.isEmpty()) Double.NaN else frames[((frames.size - 1) * 0.9).toInt()] / 1e6
        val slow = frames.count { it > 33_000_000L }
        log("stacked NFL $what $windowMs ms (opened ${opened - startedAt} ms after the burst): ${frames.size} frames, P90 total $p90Ms ms, $slow over 33 ms")

        if (isEmulator()) {
            val gate = (windowMs / 1000.0 * 40).toInt()
            assertTrue("emulator: ${frames.size} frames in $windowMs ms (gate ≥ $gate)", frames.size >= gate)
        } else {
            assertTrue("device P90 $p90Ms ms (gate < 16.7)", p90Ms < 16.7)
            assertTrue("device: $slow frames over 33 ms (gate ≤ 3)", slow <= 3)
        }

        // Idle again (R6): outside the flag hold nothing ticks after the burst.
        val endedAt = awaitQuiet(draws(), SystemClock.uptimeMillis(), 14_000)
        Thread.sleep((endedAt + 1_000 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        val idle = Collections.synchronizedList(ArrayList<Long>())
        val idleListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) idle.add(1L)
        }
        main { activity().window.addOnFrameMetricsAvailableListener(idleListener, Handler(thread.looper)) }
        try {
            Thread.sleep(10_000)
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(idleListener) }
            thread.quitSafely()
        }
        assertTrue("idle after the burst drew ${idle.size} frames in 10 s (gate ≤ 1)", idle.size <= 1)
    }

    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun restartNfl(base: String): JSONObject {
        val body = JSONObject().put("restart", true).toString().toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("$base/admin/sports-nfl").post(body).build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports-nfl → ${r.code}: $text" }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun draws() = GuideDebugCounters.count(DRAWS_STACKED_NFL_BUG)

    /** The featured NFL game's state object from `GET /admin/sports-nfl`. */
    private fun featuredState(base: String): JSONObject =
        http.newCall(Request.Builder().url("$base/admin/sports-nfl").get().build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports-nfl -> ${r.code}: $text" }
            val json = JSONObject(text)
            val id = json.optString("featured", FEATURED)
            val games = json.getJSONArray("games")
            for (i in 0 until games.length()) {
                val g = games.getJSONObject(i)
                if (g.optString("id") == id) return@use g.getJSONObject("state")
            }
            error("the featured game $id is not in the panel's NFL slate")
        }

    private fun score(s: JSONObject) = s.optInt("away", 0) + s.optInt("home", 0)

    /** The panel's penalty play (§9.4): type 8, or an accepted foul's "PENALTY on" (never the declined "Penalty on"). */
    private fun isFlag(s: JSONObject) = s.optString("lastPlayType") == "8" || s.optString("lastPlayText").contains("PENALTY on")

    /** Arms on [armed] (the previous and current state) and then waits for the draw burst; returns its first sample. */
    private fun awaitState(base: String, timeoutMs: Long, failure: String, armed: (JSONObject, JSONObject) -> Boolean): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = featuredState(base)
        while (true) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("$failure within ${timeoutMs / 1000} s of the re-anchor")
            Thread.sleep(STATE_POLL_MS)
            val now = featuredState(base)
            if (armed(last, now)) break
            last = now
        }
        val times = ArrayDeque<Long>()
        val counts = ArrayDeque<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val now = SystemClock.uptimeMillis()
            times.addLast(now); counts.addLast(draws())
            while (now - times.first() > BURST_WINDOW_MS) { times.removeFirst(); counts.removeFirst() }
            if (counts.last() - counts.first() >= BURST_DRAWS) return times.first()
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no NFL Stacked draw burst after the state change (is KC @ BUF followed?)")
    }

    /** The time of the last draw before [QUIET_MS] passed without one. */
    private fun awaitQuiet(seen: Int, seenAt: Long, timeoutMs: Long): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = seen
        var lastAt = seenAt
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val n = draws()
            val now = SystemClock.uptimeMillis()
            if (n != last) { last = n; lastAt = now } else if (now - lastAt >= QUIET_MS) return lastAt
        }
        throw AssertionError("the NFL Stacked bug kept drawing ${timeoutMs / 1000} s after the window")
    }

    private companion object {
        const val FEATURED = "401899001"
        /** One score: the 3 600 takeover and the STACKED pop and row flash to 4 800. */
        const val WINDOW_MS = 4_800L
        /** The +7 pair: `NflScoreSpec.STACKED.pairEndMs`. */
        const val PAIR_WINDOW_MS = 9_600L
        /** The flag hold (`NFL_FLAG_MARK_MS`): the wave runs while the flag bar shows. */
        const val FLAG_WINDOW_MS = 8_000L
        const val SCORE_TIMEOUT_MS = 300_000L
        const val PAIR_TIMEOUT_MS = 600_000L
        const val FLAG_TIMEOUT_MS = 300_000L
        const val POLL_MS = 50L
        const val STATE_POLL_MS = 500L
        const val BURST_WINDOW_MS = 500L
        const val BURST_DRAWS = 20
        const val QUIET_MS = 500L
    }
}
