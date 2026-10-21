package app.thebox.guide

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.MainActivity
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import app.thebox.player.api.PlaybackState
import android.graphics.Color
import app.thebox.core.guide.RowStatus
import app.thebox.debug.GuideDebugEntryPoint
import app.thebox.debug.GuideDebugReceiver
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.StopReason
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.io.FileOutputStream

/**
 * Base of the M3 instrumented suite (docs/design/m3-guide.md §5.5) against the fake panel (`--synthetic 2000`).
 *
 * - Panel URL from the `fakepanel` instrumentation argument; without it, or when the panel does not answer, every
 *   test is skipped (`assumeTrue`), exactly like the M2 `PanelTestBase`.
 * - Seeding goes through the real debug deep link `thebox-debug://guide?seed=…` ([GuideDebugReceiver]), once per
 *   instrumentation process (`reset=1`: the panel's playlist is the only one); later tests re-send the link with
 *   `refresh=0` so the per-test [switches] are applied to the in-memory guide settings before the guide starts.
 * - The guide is [MainActivity] launched with [ActivityScenario]; its state machine is read through
 *   `GuideDebugProbe.current` (the live `GuideViewModel` registers it, B7). No machine within [MACHINE_WAIT_MS] fails
 *   fast with a message naming WP-C. `frame` is main-thread state: it is only read inside [onFrame].
 * - Keys go through `Instrumentation.sendKeySync` with real down/event times, so the app's `KeyRouter` sees genuine
 *   repeat counts and held times (long press = DOWN, ~550 ms, DOWN repeat 1 with FLAG_LONG_PRESS, UP).
 * - `@After`: no `Range` header on any live request (M2 I-9 carried over), playback stopped.
 * - Timings are logged under [TAG], never asserted (plan §7), except where §5.5 states the criterion.
 */
abstract class GuideTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context = instrumentation.targetContext
    protected val ep: GuideDebugEntryPoint = EntryPointAccessors.fromApplication(context, GuideDebugEntryPoint::class.java)
    protected val controller: PlaybackController get() = ep.controller()

    protected lateinit var panel: GuidePanel
    protected lateinit var base: String
    protected var playlistId = 0L
    protected var scenario: ActivityScenario<MainActivity>? = null
        private set

    /** Deep-link switches for this class (§5.6), on top of [DEFAULT_SWITCHES]. */
    protected open val switches: String = ""
    /** True for classes that need `--synthetic N` (most of the suite: row 123, prefixes, the reminder programme). */
    protected open val needsSynthetic: Boolean = true

    @Before
    fun setUpGuide() {
        val arg = InstrumentationRegistry.getArguments().getString(ARG)
        assumeTrue("no -e $ARG argument: fake-panel suite skipped", !arg.isNullOrBlank())
        base = arg!!.trimEnd('/')
        panel = GuidePanel(base)
        assumeTrue("fake panel at $base not reachable", panel.reachable())
        panel.reset()
        if (needsSynthetic) {
            assumeTrue("the fake panel was not started with --synthetic N (python3 tools/fakepanel/fakepanel.py --synthetic 2000)", panel.synthetic() != null)
        }
        val st = seed(switches)
        playlistId = st.playlistId
        log("seeded: $st")
    }

    @After
    fun tearDownGuide() {
        if (!::panel.isInitialized) return
        try {
            onMain { controller.stop(StopReason.USER) }
            scenario?.close()
            val stats = panel.stats()
            log("panel stats: requests=${stats.requests} opens=${stats.streamOpens} maxOpen=${stats.maxOpenSeen}")
            assertEquals("range requests seen by the panel", 0, stats.rangeRequestsSeen)
        } finally {
            scenario = null
        }
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Sends `thebox-debug://guide?seed=<panel>&…` and waits for [GuideDebugReceiver.status]. The first call in the
     * process imports (`reset=1`; 2,000 channels × 10 days takes tens of seconds on the emulator); later ones only
     * apply [extra] switches (`refresh=0`).
     */
    protected fun seed(extra: String = "", forceRefresh: Boolean = false, timeoutMs: Long = SEED_TIMEOUT_MS): GuideDebugReceiver.SeedStatus {
        val first = seededBase != base
        val q = StringBuilder("thebox-debug://guide?seed=").append(Uri.encode(base)).append("&open=0&").append(DEFAULT_SWITCHES)
        if (first) q.append("&reset=1") else if (!forceRefresh) q.append("&refresh=0")
        if (extra.isNotBlank()) q.append('&').append(extra)
        val before = GuideDebugReceiver.status.value.seq
        val t0 = SystemClock.elapsedRealtime()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.toString())).setClass(context, GuideDebugReceiver::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val st = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                GuideDebugReceiver.status.first { it.seq > before && it.state != GuideDebugReceiver.SeedState.RUNNING }
            }
        } ?: run { fail("seed did not finish within $timeoutMs ms: ${GuideDebugReceiver.status.value}"); error("unreachable") }
        log("seed (${if (first) "import" else "switches"}) took ${SystemClock.elapsedRealtime() - t0} ms")
        if (st.state != GuideDebugReceiver.SeedState.DONE) fail("seed failed: ${st.error}")
        if (!st.settingsApplied) log("WARNING switches not applied (WP-B InMemoryGuideSettings stub?): ${st.settingsError}")
        seededBase = base
        return st
    }

    // ------------------------------------------------------------------ guide

    /** Launches MainActivity and returns the live machine, or fails naming the missing WP. */
    protected fun launchGuide(): GuideStateMachine {
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }
        return awaitMachine()
    }

    /** Closes MainActivity (the process goes to the background); [launchGuide] starts a new one. */
    protected fun closeGuide() {
        scenario?.close()
        scenario = null
    }

    /** Closes MainActivity and launches a new one; returns the new machine (never the old instance). */
    protected fun relaunchGuide(): GuideStateMachine {
        val old = ep.probe().current
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        return awaitMachine(not = old)
    }

    protected fun awaitMachine(timeoutMs: Long = MACHINE_WAIT_MS, not: GuideStateMachine? = null): GuideStateMachine {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            ep.probe().current?.takeIf { it !== not }?.let { return it }
            Thread.sleep(100)
        }
        fail("GuideDebugProbe.current is null after $timeoutMs ms: WP-C GuideViewModel not wired (MainActivity still the M0 placeholder?) or it did not register its machine")
        error("unreachable")
    }

    protected val machine: GuideStateMachine get() = ep.probe().current ?: awaitMachine()
    protected fun ui(): GuideUiState = machine.ui.value

    /** Reads main-thread frame state. */
    protected fun <T> onFrame(block: (GuideFrame) -> T): T = main { block(machine.frame) }
    protected fun focus(): GridFocus? = onFrame { it.focus }
    protected fun rowCount(): Int = onFrame { it.rows.size }
    protected fun channelAt(row: Int): GuideChannel? = onFrame { it.rows.channel(row) }
    protected fun nowMin(): Int = (System.currentTimeMillis() / 60_000).toInt()

    /** Waits for [predicate] on `ui`, returns the matching state; fails with [what] and the last state. */
    protected fun awaitUi(what: String, timeoutMs: Long = 5_000, predicate: (GuideUiState) -> Boolean): GuideUiState {
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { machine.ui.first(predicate) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; ui=${ui().brief()} focus=${focus()}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
        return st
    }

    /** Polls a frame condition (frame changes are not a flow the test can collect from main-thread state). */
    protected fun awaitFrame(what: String, timeoutMs: Long = 5_000, predicate: (GuideFrame) -> Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (onFrame(predicate)) {
                log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
                return
            }
            Thread.sleep(50)
        }
        fail("timed out after $timeoutMs ms waiting for $what; ui=${ui().brief()} focus=${focus()}")
    }

    /** Lineup shown, focus set, no empty state. */
    protected fun awaitGuideReady(timeoutMs: Long = 15_000) {
        awaitUi("guide not empty", timeoutMs) { it.empty == null && it.panel != null }
        awaitFrame("lineup and focus", timeoutMs) { it.rows.size > 0 && it.focus != null && it.metrics != null }
    }

    /** True when every visible row has data for the visible span (no skeleton/PENDING rows). */
    protected fun visibleLoaded(): Boolean = onFrame { f ->
        val m = f.metrics ?: return@onFrame false
        val d = f.data ?: return@onFrame false
        val visible = ((m.rowsBottom - m.rowsTop) / m.rowPitch).toInt().coerceAtLeast(1)
        val last = minOf(f.topRow + visible, f.rows.size) - 1
        (f.topRow..last).all { r ->
            f.rows.channel(r) != null && d.covers(r, f.windowStartMin) && d.covers(r, f.windowStartMin + 179) &&
                d.rowStatus[r - d.firstRow] != RowStatus.PENDING
        }
    }

    // ------------------------------------------------------------------ keys

    protected fun press(keyCode: Int, times: Int = 1, intervalMs: Long = 120) {
        repeat(times) {
            val t = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            instrumentation.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
            if (intervalMs > 0) Thread.sleep(intervalMs)
        }
    }

    /** Long press: DOWN, ~550 ms, DOWN repeat 1 with FLAG_LONG_PRESS, ~50 ms, UP (the router's timer or the repeat wins). */
    protected fun longPress(keyCode: Int) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
        Thread.sleep(550)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        Thread.sleep(50)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** Auto-repeat hold: DOWN then [repeats] repeat DOWNs every [periodMs], then UP (a held D-pad key). */
    protected fun hold(keyCode: Int, repeats: Int, periodMs: Long = 50) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
        for (i in 1..repeats) {
            Thread.sleep(periodMs)
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, i))
        }
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    protected fun digits(text: String, intervalMs: Long = 200) =
        text.forEach { press(KeyEvent.KEYCODE_0 + (it - '0'), intervalMs = intervalMs) }

    // ------------------------------------------------------------------ navigation helpers

    /** Stops playback and waits until the machine sees it (the back ladder depends on "something is tuned"). */
    protected fun stopPlayback() {
        onMain { controller.stop(StopReason.USER) }
        awaitUi("tuned idle/stopped", 5_000) { it.tuned.phase == MiniPhase.IDLE || it.tuned.phase == MiniPhase.STOPPED }
    }

    /**
     * Back to GUIDE / GRID with no overlay, without ever exiting: the exit hint is left to time out (a second BACK
     * would exit), the ASK dialog is cancelled with BACK, FULL/SEARCH/SETTINGS are left with BACK.
     */
    protected fun goHome() {
        repeat(16) {
            val s = ui()
            val o = s.overlay
            when {
                // Decision 2026-10-19: Back from home opens Groups and Back again walks to the top bar, so the reset
                // closes Groups with Right and leaves the top bar with OK on the Guide tab instead of pressing Back.
                o is Overlay.Groups && o.menu == null -> press(KeyEvent.KEYCODE_DPAD_RIGHT, intervalMs = 300)
                o is Overlay.Exit && !o.dialog -> Thread.sleep(2_300)
                o == null && s.reminder == null && s.mode == ScreenMode.GUIDE && s.zone == Zone.TABS -> {
                    press(KeyEvent.KEYCODE_DPAD_LEFT, s.tabFocused)
                    press(KeyEvent.KEYCODE_DPAD_CENTER, intervalMs = 300)
                }
                s.reminder != null || o != null || s.mode != ScreenMode.GUIDE || s.zone != Zone.GRID -> press(KeyEvent.KEYCODE_BACK, intervalMs = 300)
                else -> return
            }
        }
        fail("could not return to GUIDE/GRID: ${ui().brief()}")
    }

    /** Moves focus to lineup row [target] with PAGE± and UP/DOWN (UP never leaves the grid above row 0). */
    protected fun toRow(target: Int) {
        var guard = 0
        while (guard++ < 3_000) {
            val r = focus()?.row ?: error("no focus")
            when {
                r == target -> return
                r - target >= 5 -> press(KeyEvent.KEYCODE_PAGE_UP, intervalMs = 60)
                target - r >= 5 -> press(KeyEvent.KEYCODE_PAGE_DOWN, intervalMs = 60)
                r > target -> press(KeyEvent.KEYCODE_DPAD_UP, intervalMs = 60)
                else -> press(KeyEvent.KEYCODE_DPAD_DOWN, intervalMs = 60)
            }
        }
        fail("could not reach row $target; focus=${focus()}")
    }

    /** First lineup row (from [from]) whose channel satisfies [p]; pages focus there when the block is not loaded. */
    protected fun findRow(from: Int = 0, limit: Int = 200, p: (GuideChannel) -> Boolean): Int {
        for (r in from until minOf(rowCount(), from + limit)) {
            val c = channelAt(r) ?: run {
                toRow(r)
                awaitFrame("row $r loaded") { it.rows.channel(r) != null }
                channelAt(r)!!
            }
            if (p(c)) return r
        }
        fail("no row in [$from, ${from + limit}) matches")
        error("unreachable")
    }

    /** OK on the focused on-now cell and wait until the controller plays that row's channel. */
    protected fun tuneFocusedRow(timeoutMs: Long = 15_000): GuideChannel {
        val ch = channelAt(focus()!!.row) ?: error("focused row not loaded")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { controller.state.first { it is PlaybackState.Playing && it.target?.identity == ch.identity } } }
        if (st == null) fail("OK did not tune ${ch.displayName} within $timeoutMs ms; controller=${controller.state.value} ui=${ui().brief()}")
        log("tuned ${ch.displayName} in ${SystemClock.elapsedRealtime() - t0} ms")
        return ch
    }

    /** The focused cell contains now. */
    protected fun focusContainsNow(): Boolean = focus()?.let { nowMin() in it.cellStart until it.cellEnd } ?: false

    // ------------------------------------------------------------------ pixels

    protected fun lum(c: Int): Double = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    /** Spread of colours over a 5 × 5 grid inside [r] (l, t, r, b): high for the testsrc2 clip, near 0 for flat chrome. */
    protected fun colourfulness(bmp: Bitmap, r: IntArray): Double {
        val samples = ArrayList<Int>()
        for (i in 1..5) for (j in 1..5) {
            val x = r[0] + (r[2] - r[0]) * i / 6
            val y = r[1] + (r[3] - r[1]) * j / 6
            if (x in 0 until bmp.width && y in 0 until bmp.height) samples += bmp.getPixel(x, y)
        }
        if (samples.isEmpty()) return 0.0
        val ls = samples.map { lum(it) }
        val mean = ls.average()
        val hue = samples.map { Color.red(it) - Color.blue(it) }
        return kotlin.math.sqrt(ls.sumOf { (it - mean) * (it - mean) } / ls.size) + (hue.max() - hue.min()) / 4.0
    }

    protected fun holeRect(): IntArray = onFrame { f -> IntArray(4).also { out -> (f.metrics ?: error("no metrics")).holeRect(out) } }

    // ------------------------------------------------------------------ misc

    protected fun screenshot(name: String): Bitmap {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: error("takeScreenshot returned null")
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "guide-it").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bmp
    }

    protected fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

    protected fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    protected fun <T> main(block: () -> T): T {
        var out: Result<T>? = null
        onMain { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    protected fun percentile(values: List<Long>, p: Double): Long? =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p).toInt()] }

    protected fun log(msg: String) {
        Log.i(TAG, "${javaClass.simpleName}: $msg")
    }

    protected fun GuideUiState.brief(): String =
        "mode=$mode zone=$zone overlay=${overlay?.javaClass?.simpleName} reminder=${reminder != null} empty=$empty tuned=${tuned.phase}"

    companion object {
        const val ARG = "fakepanel"
        const val TAG = "GuideIT"
        const val MACHINE_WAIT_MS = 10_000L
        const val SEED_TIMEOUT_MS = 300_000L
        /** Deterministic baseline for every test: not low-end, video in the mini player, 24 h clock, Back twice, locked groups hidden. */
        const val DEFAULT_SWITCHES = "lowend=0&still=0&clock24=1&exit=twice&unlocked=0"

        /** The panel this process already imported (the app is reinstalled by every Gradle connected run). */
        @Volatile private var seededBase: String? = null
    }
}
