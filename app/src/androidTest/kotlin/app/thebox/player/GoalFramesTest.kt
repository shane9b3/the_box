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
import app.thebox.ui.sports.DRAWS_SCORE_BUG
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
 * Goal animation docs/design/m6-goal-animation.md §8.4: frame cost while the takeover plays over live video in FULL.
 *
 * **Not run by the agent that wrote it** (instrumented tests wipe the app on the attached device); the lead runs it.
 * Prerequisites, since the test cannot inject a score source into the real activity:
 * - the debug build points at the fake panel's sports API (`files/debug/sports_base`, tools/fakepanel/README.md) with
 *   `--sports`, and the featured game (CGY @ EDM) is followed with Show Sports on;
 * - run it with `-e goalFrames 1`. The sports panel is the test's own fake panel unless `-e sportsPanel <base>` names
 *   another (e.g. `http://10.0.2.2:8080`).
 * Without `goalFrames=1` the test is skipped.
 *
 * Anchoring (code review 2026-11-07 S3): the test re-anchors the featured game itself (`POST /admin/sports
 * {"restart": true}`: the CGY goal lands about 165 s later, plus the 20 s CDN bucket and the poll), then waits for the
 * takeover to **start** before it opens the 5 s window. The start is a burst of the bug's own draws
 * ([DRAWS_SCORE_BUG], debug builds): at rest the bug draws once per snapshot and its fade and slide never redraw it, so
 * [BURST_DRAWS] draws inside [BURST_WINDOW_MS] is only the takeover. The idle window then starts 1 s after the
 * takeover's measured **end** (the last draw before [QUIET_MS] without one), not at a fixed offset.
 *
 * Records, over 5 s from the start: the P90 `TOTAL_DURATION`, the count of frames over 33 ms, and the frame count. Gates:
 * emulator ≥ 200 frames; device P90 < 16.7 ms and ≤ 3 frames over 33 ms. Damage area is read from `dumpsys gfxinfo` by
 * hand (S3). Afterwards the window must go idle: ≤ 1 frame per 10 s within 1 s of the end of the takeover.
 */
@RunWith(AndroidJUnit4::class)
class GoalFramesTest : PlayerTestBase() {

    @Test
    fun goalTakeoverFrameCost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "pass -e goalFrames 1 with the fake panel's sports game followed and Show Sports on",
            args.getString("goalFrames") == "1",
        )
        assumeTrue("the draw counter is debug-only", GuideDebugCounters.ENABLED)
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }

        val sportsBase = args.getString("sportsPanel") ?: pp.base
        val restarted = restartSports(sportsBase)
        assumeTrue("the panel at $sportsBase serves no sports (start it with --sports)", restarted.optBoolean("enabled"))
        log("sports re-anchored at ${restarted.optDouble("anchor_unix")}; waiting for the CGY goal's takeover")
        val startedAt = awaitTakeoverStart(GOAL_TIMEOUT_MS)

        val totals = Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("goal-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        // The window sleeps in draw-counter polls, so the takeover's end is known even when it falls inside the window.
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
        log("goal takeover 5 s (opened ${opened - startedAt} ms after the burst's first sample): ${frames.size} frames, P90 total $p90Ms ms, $slow frames over 33 ms")

        if (isEmulator()) {
            assertTrue("emulator: ${frames.size} frames in 5 s (gate ≥ 200)", frames.size >= 200)
        } else {
            assertTrue("device P90 $p90Ms ms (gate < 16.7)", p90Ms < 16.7)
            assertTrue("device: $slow frames over 33 ms (gate ≤ 3)", slow <= 3)
        }

        // Idle again: the takeover must leave nothing ticking (≤ 1 frame per 10 s within 1 s of its end, §8.4).
        val endedAt = awaitTakeoverEnd(lastDraws, lastDrawAt, 10_000)
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

    private fun restartSports(base: String): JSONObject {
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val body = JSONObject().put("restart", true).toString().toRequestBody("application/json".toMediaType())
        return http.newCall(Request.Builder().url("$base/admin/sports").post(body).build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "panel /admin/sports → ${r.code}: $text" }
            JSONObject(text)
        }
    }

    private fun draws() = GuideDebugCounters.count(DRAWS_SCORE_BUG)

    /** Polls the draw counter until [BURST_DRAWS] draws land inside [BURST_WINDOW_MS]; returns the burst's first sample. */
    private fun awaitTakeoverStart(timeoutMs: Long): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val times = ArrayDeque<Long>()
        val counts = ArrayDeque<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val now = SystemClock.uptimeMillis()
            times.addLast(now); counts.addLast(draws())
            while (now - times.first() > BURST_WINDOW_MS) { times.removeFirst(); counts.removeFirst() }
            if (counts.last() - counts.first() >= BURST_DRAWS) return times.first()
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no goal takeover within ${timeoutMs / 1000} s of the re-anchor (is CGY @ EDM followed?)")
    }

    /** The time of the last draw before [QUIET_MS] passed without one, continuing from the window's [seen] at [seenAt]. */
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
        throw AssertionError("the bug kept drawing ${timeoutMs / 1000} s after the window: the takeover never ended")
    }

    private companion object {
        const val WINDOW_MS = 5_000L
        /** 165 s to the goal after the re-anchor, a 20 s CDN bucket, the app's poll, and slack. */
        const val GOAL_TIMEOUT_MS = 300_000L
        const val POLL_MS = 50L
        const val BURST_WINDOW_MS = 500L
        /** A 60 Hz takeover draws about 30 times in 500 ms; a fade (≤ 300 ms) could not reach this even if it redrew. */
        const val BURST_DRAWS = 20
        const val QUIET_MS = 500L
    }
}
