package app.thebox.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.MainActivity
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.debug.GuideDebugEntryPoint
import app.thebox.debug.GuideDebugReceiver
import app.thebox.debug.SettingsDebugEntryPoint
import app.thebox.debug.SettingsDebugProbe
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

/**
 * Base of the M5 instrumented suite (docs/design/m5-setup-settings.md §6.3; API 30 TV emulator, fake panel).
 *
 * - Panel URL from the `fakepanel` instrumentation argument; without it, or when the panel does not answer, every test
 *   is skipped (`assumeTrue`), as in M2–M4. The panel is reset before each test.
 * - State through [SettingsDebugProbe] (debug only: the settings machine via the guide machine, LAN server state with
 *   the token, parental state, store documents, the updater) and the M3/M4 [GuideDebugEntryPoint].
 * - Switches through the debug links of §6.5 on the `guide` host (`thebox-debug://guide?wipe=1…`, see
 *   `GuideDebugReceiver.handleM5`); the outcome is awaited on `GuideDebugReceiver.m5Status`.
 * - Text entry: `Instrumentation.sendStringSync` into the focused editor field (the app's androidTest has no Compose
 *   test artefact; IME behaviour itself is manual, §6.8).
 * - `@After`: no `Range` header on any live request (as M2–M4), playback stopped.
 * - A missing settings machine or page fails fast naming the WP that provides it, so the integration pass can tell a
 *   wiring gap from a regression.
 */
abstract class SettingsTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context = instrumentation.targetContext
    protected val ep: GuideDebugEntryPoint = EntryPointAccessors.fromApplication(context, GuideDebugEntryPoint::class.java)
    protected val probe: SettingsDebugProbe = EntryPointAccessors.fromApplication(context, SettingsDebugEntryPoint::class.java).settingsProbe()
    protected val controller: PlaybackController get() = ep.controller()

    protected lateinit var panel: SettingsPanel
    protected lateinit var base: String
    protected var scenario: ActivityScenario<MainActivity>? = null
        private set

    @Before
    fun setUpSettings() {
        val arg = InstrumentationRegistry.getArguments().getString(ARG)
        assumeTrue("no -e $ARG argument: fake-panel suite skipped", !arg.isNullOrBlank())
        base = arg!!.trimEnd('/')
        panel = SettingsPanel(base)
        assumeTrue("fake panel at $base not reachable", panel.reachable())
        panel.reset()
    }

    @After
    fun tearDownSettings() {
        if (!::panel.isInitialized) return
        try {
            onMain { controller.stop(StopReason.USER) }
            scenario?.close()
            val stats = panel.stats()
            log("panel stats: requests=${stats.requests} opens=${stats.streamOpens}")
            assertEquals("range requests seen by the panel", 0, stats.rangeRequestsSeen)
        } finally {
            scenario = null
        }
    }

    // ------------------------------------------------------------------ debug links

    /** Sends `thebox-debug://guide?<query>` (M5 switches) and waits until the receiver reports done. */
    protected fun debugLink(query: String, timeoutMs: Long = 30_000): GuideDebugReceiver.M5Status {
        val before = GuideDebugReceiver.m5Status.value.seq
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("thebox-debug://guide?$query")).setClass(context, GuideDebugReceiver::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { GuideDebugReceiver.m5Status.first { it.seq > before && it.done } } }
            ?: run { fail("debug link '$query' did not finish within $timeoutMs ms"); error("unreachable") }
        if (st.error != null) fail("debug link '$query' failed: ${st.error}")
        return st
    }

    /** Fresh data (§6.5 wipe): library rows, secrets, settings, parental prefs; the activity is not restarted. */
    protected fun wipe() {
        closeApp()
        debugLink("wipe=1&restart=0&open=0")
    }

    /** Seeds the panel's Xtream playlist through the M3 seed link (catalog mode, `reset=1`); returns the playlist id. */
    protected fun seedXtream(extra: String = "", timeoutMs: Long = 120_000): Long {
        val q = StringBuilder("thebox-debug://guide?seed=").append(Uri.encode(base)).append("&open=0&reset=1")
        if (extra.isNotBlank()) q.append('&').append(extra)
        val before = GuideDebugReceiver.status.value.seq
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.toString())).setClass(context, GuideDebugReceiver::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { GuideDebugReceiver.status.first { it.seq > before && it.state != GuideDebugReceiver.SeedState.RUNNING } } }
            ?: run { fail("seed did not finish: ${GuideDebugReceiver.status.value}"); error("unreachable") }
        if (st.state != GuideDebugReceiver.SeedState.DONE) fail("seed failed: ${st.error}")
        log("seeded playlist ${st.playlistId}: ${st.channels} channels")
        return st.playlistId
    }

    // ------------------------------------------------------------------ app and machines

    protected fun launch(): GuideStateMachine {
        if (scenario == null) scenario = ActivityScenario.launch(MainActivity::class.java)
        return awaitGuideMachine()
    }

    protected fun closeApp() {
        scenario?.close()
        scenario = null
    }

    protected fun relaunch(): GuideStateMachine {
        val old = ep.probe().current
        closeApp()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        return awaitGuideMachine(not = old)
    }

    protected fun awaitGuideMachine(timeoutMs: Long = 15_000, not: GuideStateMachine? = null): GuideStateMachine {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            ep.probe().current?.takeIf { it !== not }?.let { return it }
            Thread.sleep(100)
        }
        fail("GuideDebugProbe.current is null after $timeoutMs ms (GuideViewModel did not register its machine)")
        error("unreachable")
    }

    protected val guide: GuideStateMachine get() = ep.probe().current ?: awaitGuideMachine()
    protected fun gui(): GuideUiState = guide.ui.value

    /** The settings machine (attached by WP-A's SettingsViewModel through MainActivity, S11), or a failure naming WP-A. */
    protected fun settingsMachine(timeoutMs: Long = 10_000): SettingsMachine {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            probe.settings?.let { return it }
            Thread.sleep(100)
        }
        fail("no settings machine attached to the guide machine after $timeoutMs ms: awaits WP-A (SettingsViewModel / MainActivity attachSettings, S11)")
        error("unreachable")
    }

    protected fun sui(): SettingsUiState = settingsMachine().ui.value

    /** Opens Settings on [route] (`SettingsDebugProbe.parseRoute` spec) through the guide machine and waits for its page. */
    protected fun openSettings(route: String, timeoutMs: Long = 10_000): PageModel {
        launch()
        debugLink("settings=$route&open=0")
        awaitGui("mode SETTINGS") { it.mode == ScreenMode.SETTINGS }
        return awaitPage("page of $route (awaits the page builder's WP and WP-A's SettingsEffectsImpl.loadPage)", timeoutMs) { true }
    }

    protected fun awaitGui(what: String, timeoutMs: Long = 10_000, p: (GuideUiState) -> Boolean): GuideUiState {
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { guide.ui.first(p) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; mode=${gui().mode}"); error("unreachable") }
        return st
    }

    protected fun awaitSettings(what: String, timeoutMs: Long = 10_000, p: (SettingsUiState) -> Boolean): SettingsUiState {
        val m = settingsMachine()
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { m.ui.first(p) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; settings=${brief(m.ui.value)}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
        return st
    }

    protected fun awaitPage(what: String, timeoutMs: Long = 10_000, p: (PageModel) -> Boolean): PageModel =
        awaitSettings(what, timeoutMs) { s -> s.page?.let(p) == true }.page!!

    /** The rows of the current page, fixed rows then the loaded list rows, in focus order. */
    protected fun rows(s: SettingsUiState = sui()): List<SettingsRow> {
        val page = s.page ?: return emptyList()
        val list = page.list ?: return page.rows
        val items = (0 until list.count).mapNotNull { i -> list.loaded[i / list.pageSize]?.getOrNull(i % list.pageSize) }
        return page.rows + items
    }

    protected fun row(id: String): SettingsRow? = rows().firstOrNull { it.id == RowId(id) }
    protected fun focusedRow(): SettingsRow? = sui().let { s -> if (s.pane == Pane.PAGE) rows(s).getOrNull(s.cursor) else null }

    /** Moves the page cursor to row [id] with ↑/↓ (focus in PAGE first). Fails when the row is not on the page. */
    protected fun moveTo(id: String, timeoutMs: Long = 15_000) {
        if (sui().pane != Pane.PAGE) press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitSettings("row $id on the page", 5_000) { s -> rows(s).any { it.id == RowId(id) } }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val s = sui()
            val target = rows(s).indexOfFirst { it.id == RowId(id) }
            when {
                s.pane == Pane.PAGE && s.cursor == target -> return
                target < s.cursor -> press(KeyEvent.KEYCODE_DPAD_UP, intervalMs = 80)
                else -> press(KeyEvent.KEYCODE_DPAD_DOWN, intervalMs = 80)
            }
        }
        fail("could not focus row $id; ${brief(sui())}")
    }

    /** Focus row [id] and press OK. */
    protected fun activate(id: String) {
        moveTo(id)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Opens the inline choice of row [id] and selects the option with [optionId]. */
    protected fun choose(id: String, optionId: String) {
        activate(id)
        awaitSettings("choice list of $id") { it.choice?.row == RowId(id) }
        val options = (row(id)?.kind as? app.thebox.core.settings.RowKind.Choice)?.options ?: error("row $id is not a Choice")
        val target = options.indexOfFirst { it.id == optionId }
        check(target >= 0) { "option $optionId not in $id: ${options.map { it.id }}" }
        var guard = 0
        while (sui().choice?.focused != target && guard++ < 40) press(if ((sui().choice?.focused ?: 0) < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 80)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("choice of $id closed") { it.choice == null }
    }

    /** The first row of the current page matching [p], waiting up to [timeoutMs]. */
    protected fun rowWhere(what: String, timeoutMs: Long = 10_000, p: (SettingsRow) -> Boolean): SettingsRow {
        awaitSettings(what, timeoutMs) { s -> rows(s).any(p) }
        return rows().first(p)
    }

    protected fun isLinkTo(r: SettingsRow, p: (app.thebox.core.settings.SettingsRoute) -> Boolean): Boolean =
        (r.kind as? app.thebox.core.settings.RowKind.Link)?.route?.let(p) == true ||
            (r.kind as? app.thebox.core.settings.RowKind.Item)?.route?.let(p) == true

    protected fun textRow(hint: app.thebox.core.settings.TextHint): (SettingsRow) -> Boolean =
        { r -> (r.kind as? app.thebox.core.settings.RowKind.Text)?.hint == hint }

    /** Opens the editor on [r], types [text] (replacing nothing: the field starts with the row's value) and presses Done (ENTER). */
    protected fun editText(r: SettingsRow, text: String) {
        activate(r.id.key)
        awaitSettings("editor of ${r.id.key}") { it.editor?.row == r.id }
        Thread.sleep(300)
        typeText(text)
        press(KeyEvent.KEYCODE_ENTER)
        awaitSettings("editor of ${r.id.key} closed", 5_000) { it.editor == null }
    }

    /** Every user-visible text of the current page (title, subtitle, notice, labels, values, descriptions, Info lines). */
    protected fun pageText(s: SettingsUiState = sui()): String = buildString {
        val p = s.page ?: return@buildString
        append(p.title).append('\n').append(p.subtitle.orEmpty()).append('\n').append(p.placeholder.orEmpty()).append('\n').append(s.notice.orEmpty()).append('\n')
        for (r in rows(s)) {
            append(r.label).append(" | ").append(r.value.orEmpty()).append(" | ").append(r.description.orEmpty()).append(" | ").append(r.disabledReason.orEmpty())
            (r.kind as? app.thebox.core.settings.RowKind.Info)?.lines?.forEach { append(" | ").append(it) }
            append('\n')
        }
    }

    protected fun awaitText(what: String, timeoutMs: Long = 15_000, p: (String) -> Boolean): String =
        pageText(awaitSettings(what, timeoutMs) { s -> p(pageText(s)) })

    /** Types [text] into the focused editor field (IME-free: key events into the focused view). */
    protected fun typeText(text: String) {
        instrumentation.sendStringSync(text)
        instrumentation.waitForIdleSync()
    }

    /** Back until the guide machine leaves SETTINGS (at most [max] presses). */
    protected fun leaveSettings(max: Int = 14) {
        repeat(max) {
            if (gui().mode != ScreenMode.SETTINGS) return
            press(KeyEvent.KEYCODE_BACK, intervalMs = 250)
        }
        if (gui().mode == ScreenMode.SETTINGS) fail("still in SETTINGS after $max BACKs; ${brief(sui())}")
    }

    // ------------------------------------------------------------------ keys

    protected fun press(keyCode: Int, times: Int = 1, intervalMs: Long = 150) {
        repeat(times) {
            val t = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            instrumentation.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
            if (intervalMs > 0) Thread.sleep(intervalMs)
        }
    }

    protected fun longPress(keyCode: Int) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
        Thread.sleep(550)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        Thread.sleep(50)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    protected fun digits(text: String, intervalMs: Long = 200) = text.forEach { press(KeyEvent.KEYCODE_0 + (it - '0'), intervalMs = intervalMs) }

    protected fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        instrumentation.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    protected fun log(msg: String) = Log.i(TAG, "${javaClass.simpleName}: $msg")

    protected fun brief(s: SettingsUiState): String =
        "route=${s.route} pane=${s.pane} cursor=${s.cursor} page=${s.page?.title}/${s.page?.rows?.size} choice=${s.choice} confirm=${s.confirm != null} " +
            "editor=${s.editor?.row} pad=${s.numberPad?.row} capture=${s.capture != null} notice=${s.notice}"

    companion object {
        const val ARG = "fakepanel"
        const val TAG = "M5Suite"
    }
}
