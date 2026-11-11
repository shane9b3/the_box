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
import app.thebox.ui.sports.COMPOSITIONS_STACKED_BUG
import app.thebox.ui.sports.DRAWS_STACKED_BUG
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
 * Stacked score bug, docs/design/m6-score-bug-stacked-theme.md §6 and §10.4 (S11): frame cost over live video in FULL
 * while the Stacked takeover plays, and while a power play opens the Stacked bar and SOG column, with the GA §8.4 gates.
 * Beside [GoalFramesTest], whose anchoring it shares.
 *
 * **Not run by the agent that wrote it** (instrumented tests wipe the app on the attached device); the lead runs it.
 * Prerequisites, since the test cannot inject a score source into the real activity:
 * - the fake panel runs with `--sports --sports-pp`, the debug build points at it (`files/debug/sports_base`), and the
 *   featured game (CGY @ EDM) is followed with Show Sports on;
 * - the **size and corner** under test are set by hand (Settings → Sports, or the sheet's Corner row); the test sets
 *   **Style → Stacked** itself through the quick bar's Score bug sheet (Stacked §1.1), and restores the style the sheet
 *   showed before in a `finally` (fix pass 2026-11-11: app data persists between instrumented tests);
 * - run it with `-e stackedFrames 1`, and:
 *   - `-e stackedWhat goal` (default) for the takeover, or `pp` for a power play's open (SOG column, bar, stripes, text);
 *   - `-e stackedQuick 1` to hold the quick bar open over the window (a bottom corner is then lifted above it, S11);
 *   - the panel is the test's own fake panel unless `-e sportsPanel <base>` names another.
 *   The §10.4 matrix is: goal and pp, each at Standard and Large; and goal in a bottom corner with `stackedQuick 1`.
 *
 * Anchoring (as [GoalFramesTest]): the test re-anchors the panel's sports timeline (`POST /admin/sports {"restart": true}`)
 * and arms on the featured game's **state**, never on a draw burst: a score rise for `goal`, a power-play
 * `situationCode` for `pp`. It then waits for the burst of the Stacked bug's own draws ([DRAWS_STACKED_BUG], debug
 * builds) and records, over [WINDOW_MS] from its start: the P90 `TOTAL_DURATION`, the frames over 33 ms and the frame
 * count. Gates: emulator ≥ 200 frames in 5 s; device P90 < 16.7 ms and ≤ 3 frames over 33 ms. Afterwards (quick bar
 * closed) the window must go idle: ≤ 1 frame per 10 s within 1 s of the end of the burst.
 */
@RunWith(AndroidJUnit4::class)
class StackedFramesTest : PlayerTestBase() {

    @Test
    fun stackedFrameCost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "pass -e stackedFrames 1 with the fake panel's sports game followed and Show Sports on",
            args.getString("stackedFrames") == "1",
        )
        assumeTrue("the draw counter is debug-only", GuideDebugCounters.ENABLED)
        val what = args.getString("stackedWhat") ?: "goal"
        val quick = args.getString("stackedQuick") == "1"
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }

        // (Fix pass 2026-11-11, review C S3) The style is persisted app data: whatever the sheet showed is restored after.
        val previous = setNhlStyle(NhlBugStyle.STACKED)
        assumeTrue("the sheet has a Style row (an NHL game followed)", previous != null)
        try {
            measure(args, what, quick)
        } finally {
            if (previous != NhlBugStyle.STACKED) setNhlStyle(previous!!)
        }
    }

    private fun measure(args: android.os.Bundle, what: String, quick: Boolean) {
        Thread.sleep(2_000)
        assumeTrue("the Stacked bug is composed (is a game followed?)", GuideDebugCounters.count(COMPOSITIONS_STACKED_BUG) > 0)

        val sportsBase = args.getString("sportsPanel") ?: pp.base
        val restarted = restartSports(sportsBase)
        assumeTrue("the panel at $sportsBase serves no sports (start it with --sports --sports-pp)", restarted.optBoolean("enabled"))
        log("sports re-anchored; waiting for the ${if (what == "pp") "power play" else "goal"} (quick bar ${if (quick) "up" else "down"})")
        val startedAt = if (what == "pp") awaitPowerPlay(sportsBase, quick) else awaitGoal(sportsBase, quick)

        val totals = Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("stacked-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val opened = SystemClock.uptimeMillis()
        var lastDraws = draws()
        var lastDrawAt = opened
        try {
            while (SystemClock.uptimeMillis() - opened < WINDOW_MS) {
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
        log("stacked $what ${WINDOW_MS} ms (quick $quick; opened ${opened - startedAt} ms after the burst): ${frames.size} frames, P90 total $p90Ms ms, $slow over 33 ms")

        if (isEmulator()) {
            assertTrue("emulator: ${frames.size} frames in 5 s (gate ≥ 200)", frames.size >= 200)
        } else {
            assertTrue("device P90 $p90Ms ms (gate < 16.7)", p90Ms < 16.7)
            assertTrue("device: $slow frames over 33 ms (gate ≤ 3)", slow <= 3)
        }

        // Idle again (the quick bar's own frames are not the bug's): nothing ticks after the burst (§6, ruling 6).
        if (quick) toBareVideo()
        val endedAt = awaitQuiet(draws(), SystemClock.uptimeMillis(), 12_000)
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

    private fun restartSports(base: String): JSONObject {
        val body = JSONObject().put("restart", true).toString().toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("$base/admin/sports").post(body).build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports → ${r.code}: $text" }
            JSONObject(text)
        }
    }

    private fun draws() = GuideDebugCounters.count(DRAWS_STACKED_BUG)

    /** The featured game's state object from `GET /admin/sports`, as the panel serves it for this CDN bucket. */
    private fun featuredState(base: String): JSONObject =
        http.newCall(Request.Builder().url("$base/admin/sports").get().build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports -> ${r.code}: $text" }
            val json = JSONObject(text)
            val id = json.optString("featured")
            val games = json.getJSONArray("games")
            for (i in 0 until games.length()) {
                val g = games.getJSONObject(i)
                if (g.optString("id") == id) return@use g.getJSONObject("state")
            }
            error("the featured game $id is not in the panel's slate")
        }

    private fun score(s: JSONObject) = s.optInt("away", 0) + s.optInt("home", 0)

    /** A power-play situation: the skater digits differ (`situationCode` "1451" = home on the power play). */
    private fun powerPlay(s: JSONObject): Boolean {
        val code = s.optString("situationCode", "")
        return code.length == 4 && code[1] != code[2]
    }

    private fun awaitGoal(base: String, quick: Boolean): Long {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val baseline = score(featuredState(base))
        while (score(featuredState(base)) <= baseline) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("the featured score never rose within ${TIMEOUT_MS / 1000} s")
            Thread.sleep(STATE_POLL_MS)
        }
        if (quick) openQuickBar()
        return awaitBurst(deadline, "no Stacked takeover after the score rise (is CGY @ EDM followed?)")
    }

    private fun awaitPowerPlay(base: String, quick: Boolean): Long {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (powerPlay(featuredState(base))) { // wait out a power play already on at the re-anchor
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("a power play never ended")
            Thread.sleep(STATE_POLL_MS)
        }
        while (!powerPlay(featuredState(base))) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("no power play within ${TIMEOUT_MS / 1000} s (start the panel with --sports-pp)")
            Thread.sleep(STATE_POLL_MS)
        }
        if (quick) openQuickBar()
        return awaitBurst(deadline, "no Stacked power-play open after the situation changed")
    }

    /** Polls the draw counter until [BURST_DRAWS] draws land inside [BURST_WINDOW_MS]; returns the burst's first sample. */
    private fun awaitBurst(deadline: Long, failure: String): Long {
        val times = ArrayDeque<Long>()
        val counts = ArrayDeque<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val now = SystemClock.uptimeMillis()
            times.addLast(now); counts.addLast(draws())
            while (now - times.first() > BURST_WINDOW_MS) { times.removeFirst(); counts.removeFirst() }
            if (counts.last() - counts.first() >= BURST_DRAWS) return times.first()
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(failure)
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
        throw AssertionError("the Stacked bug kept drawing ${timeoutMs / 1000} s after the window")
    }

    private companion object {
        const val WINDOW_MS = 5_000L
        /** 165 s to the goal after the re-anchor, a 20 s CDN bucket, the app's poll, and slack. */
        const val TIMEOUT_MS = 300_000L
        const val POLL_MS = 50L
        const val STATE_POLL_MS = 500L
        const val BURST_WINDOW_MS = 500L
        /** A 60 Hz takeover or bar open draws about 30 times in 500 ms; a fade (≤ 300 ms) could not reach this. */
        const val BURST_DRAWS = 20
        const val QUIET_MS = 500L
    }
}
