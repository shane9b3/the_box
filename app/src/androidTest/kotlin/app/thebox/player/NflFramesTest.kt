package app.thebox.player

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.full.PlayerLayer
import app.thebox.player.api.PlaybackState
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.sports.DRAWS_NFL_BUG
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
 * NFL score bug, docs/design/m6-nfl.md §10.4 (N9): frame cost while an NFL scoring takeover plays over live video in FULL,
 * with the GA §8.4 gates. Beside [GoalFramesTest].
 *
 * **Not run by the agent that wrote it** (instrumented tests wipe the app on the attached device); the lead runs it.
 * Prerequisites, since the test cannot inject a score source into the real activity:
 * - the fake panel runs with `--sports --sports-nfl`, the debug build points at it (`files/debug/sports_base`), and the
 *   featured NFL game (KC @ BUF, `401899001`) is followed with Show Sports on;
 * - run it with `-e nflFrames 1`; add `-e nflPair 1` to measure the Q2 +7 pair (ruling 25) instead of the first
 *   touchdown. The panel is the test's own fake panel unless `-e sportsPanel <base>` names another.
 *
 * Anchoring (N9): the test re-anchors only the NFL timeline (`POST /admin/sports-nfl {"restart": true}`) and arms on the
 * featured game's **score change** read from `GET /admin/sports-nfl` (a rise of 7 or more in one read for the pair), never
 * on a draw burst: the tab's CLOSE / OPEN at every kick-off is not a takeover. Then it waits for the takeover's start (a
 * burst of [DRAWS_NFL_BUG]) and records, over the window [start, start + 4.5 s] (+ 9 s for the pair): the P90
 * `TOTAL_DURATION`, the frames over 33 ms and the frame count. Gates: emulator ≥ 60 frames per second of window; device
 * P90 < 16.7 ms and ≤ 3 frames over 33 ms. Afterwards the window must go idle (≤ 1 frame per 10 s within 1 s of the end).
 */
@RunWith(AndroidJUnit4::class)
class NflFramesTest : PlayerTestBase() {

    @Test
    fun nflTakeoverFrameCost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "pass -e nflFrames 1 with the fake panel's NFL game followed and Show Sports on",
            args.getString("nflFrames") == "1",
        )
        assumeTrue("the draw counter is debug-only", GuideDebugCounters.ENABLED)
        val pair = args.getString("nflPair") == "1"
        val windowMs = if (pair) PAIR_WINDOW_MS else WINDOW_MS
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }

        val sportsBase = args.getString("sportsPanel") ?: pp.base
        val restarted = restartNfl(sportsBase)
        assumeTrue("the panel at $sportsBase serves no NFL (start it with --sports --sports-nfl)", restarted.optBoolean("enabled", true))
        log("NFL re-anchored; waiting for the ${if (pair) "+7 pair" else "first touchdown"}")
        val startedAt = awaitTakeoverStart(sportsBase, if (pair) 7 else 1, if (pair) PAIR_TIMEOUT_MS else SCORE_TIMEOUT_MS)

        val totals = Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("nfl-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val opened = SystemClock.uptimeMillis()
        var lastDraws = draws()
        var lastDrawAt = opened
        try {
            while (SystemClock.uptimeMillis() - opened < windowMs) {
                Thread.sleep(POLL_MS)
                val n = draws()
                if (n != lastDraws) { lastDraws = n; lastDrawAt = SystemClock.uptimeMillis() }
            }
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(listener) }
        }
        val frames = totals.toList().sorted()
        val p90Ms = if (frames.isEmpty()) Double.NaN else frames[((frames.size - 1) * 0.9).toInt()] / 1e6
        val slow = frames.count { it > 33_000_000L }
        log("NFL takeover ${windowMs} ms (opened ${opened - startedAt} ms after the burst): ${frames.size} frames, P90 total $p90Ms ms, $slow over 33 ms")

        if (isEmulator()) {
            val gate = (windowMs / 1000.0 * 40).toInt()
            assertTrue("emulator: ${frames.size} frames in $windowMs ms (gate ≥ $gate)", frames.size >= gate)
        } else {
            assertTrue("device P90 $p90Ms ms (gate < 16.7)", p90Ms < 16.7)
            assertTrue("device: $slow frames over 33 ms (gate ≤ 3)", slow <= 3)
        }

        val endedAt = awaitTakeoverEnd(lastDraws, lastDrawAt, 12_000)
        log("takeover ran ${endedAt - startedAt} ms by the draw counter")
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
        assertTrue("idle after the takeover drew ${idle.size} frames in 10 s (gate ≤ 1)", idle.size <= 1)
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

    private fun draws() = GuideDebugCounters.count(DRAWS_NFL_BUG)

    /**
     * The featured NFL game's score total from `GET /admin/sports-nfl` (WP-5's slate: `featured` and `games[]` with
     * `state.away` / `state.home`, as the NHL `/admin/sports`).
     */
    private fun featuredScore(base: String): Int =
        http.newCall(Request.Builder().url("$base/admin/sports-nfl").get().build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports-nfl -> ${r.code}: $text" }
            val json = JSONObject(text)
            val id = json.optString("featured", FEATURED)
            val games = json.getJSONArray("games")
            for (i in 0 until games.length()) {
                val g = games.getJSONObject(i)
                if (g.optString("id") != id) continue
                val st = g.getJSONObject("state")
                return@use st.optInt("away", 0) + st.optInt("home", 0)
            }
            error("the featured game $id is not in the panel's NFL slate")
        }

    /** Arms on a score rise of at least [rise] in one read (N9), then waits for the draw burst; returns its first sample. */
    private fun awaitTakeoverStart(base: String, rise: Int, timeoutMs: Long): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = featuredScore(base)
        while (true) {
            if (SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError("no NFL score rise of $rise within ${timeoutMs / 1000} s of the re-anchor")
            }
            Thread.sleep(SCORE_POLL_MS)
            val now = featuredScore(base)
            if (now - last >= rise) break
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
        throw AssertionError("no NFL takeover after the score rise (is KC @ BUF followed?)")
    }

    private fun awaitTakeoverEnd(seen: Int, seenAt: Long, timeoutMs: Long): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = seen
        var lastAt = seenAt
        if (draws() == last && SystemClock.uptimeMillis() - lastAt >= QUIET_MS) return lastAt
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val n = draws()
            val now = SystemClock.uptimeMillis()
            if (n != last) { last = n; lastAt = now } else if (now - lastAt >= QUIET_MS) return lastAt
        }
        throw AssertionError("the NFL bug kept drawing ${timeoutMs / 1000} s after the window: the takeover never ended")
    }

    private companion object {
        const val FEATURED = "401899001"
        /** One score: the 3 600 ms takeover and the 900 ms pop. */
        const val WINDOW_MS = 4_500L
        /** The §5.7 pair: `NFL_DOUBLE_MARK_MS`. */
        const val PAIR_WINDOW_MS = 9_000L
        /** pre 40 s + Q1 125 s to the first touchdown, a CDN bucket, the app's poll and slack. */
        const val SCORE_TIMEOUT_MS = 300_000L
        /** The Q2 pair at about 40 + 235 + 25 + 100 s, plus slack. */
        const val PAIR_TIMEOUT_MS = 600_000L
        const val POLL_MS = 50L
        const val SCORE_POLL_MS = 500L
        const val BURST_WINDOW_MS = 500L
        /** A takeover draws about 30 times in 500 ms at 60 Hz; a tab CLOSE / OPEN is not counted: the arm is the score. */
        const val BURST_DRAWS = 20
        const val QUIET_MS = 500L
    }
}
