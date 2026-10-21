package app.thebox.player

import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import app.thebox.MainActivity
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.QuickItem
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ScreenMode
import app.thebox.guide.GuideTestBase
import app.thebox.player.api.PlaybackState
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.full.vm.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.util.Collections
import kotlin.math.abs

/**
 * Base of the M4 instrumented suite (docs/design/m4-player-ux.md §5.5; owner WP-E) against the fake panel started with
 * `--synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"'` (run-instrumented.sh `--synthetic 2000`
 * plus the two `--set` flags; the base also POSTs both keys before the first seed, so a panel started without them works).
 *
 * - Seeding reuses the M3 [GuideTestBase] route (`thebox-debug://guide?seed=…`), with `m3u=1`: the panel is imported as
 *   an **M3U URL** playlist because `catchup-correction` exists only in the M3U (channel 3 `+2`, channel 4 `-1.5`,
 *   I-M4-13b). [playerSwitches] are appended after [PLAYER_DEFAULTS] (last occurrence wins, M3 G2).
 * - The player machine is read through `PlayerDebugProbe.current` (WP-D's `PlayerViewModel` registers it) and the archive
 *   session through `PlayerDebugProbe.archive`. No machine within 10 s fails fast naming WP-D.
 * - Fixture channels come from `/admin/stats.synthetic.player_fixtures` ([fx]); channel `N` is found by name (`… N`).
 * - Expected archive starts use the **panel's** clock (`/admin/now`) for live-anchored instants (pause, RW).
 * - `@After` (base): `range_requests_seen == 0` on live TS (M2/M3); here: the timeshift log is written to logcat.
 * Lines whose expectation is another work package's behaviour carry a `// WP-x behaviour` tag for the integration pass.
 */
abstract class PlayerTestBase : GuideTestBase() {
    protected lateinit var pp: PlayerPanel

    /** Per-class deep-link switches on top of [PLAYER_DEFAULTS] (e.g. `infobar=3000`, `padding=0`). */
    protected open val playerSwitches: String = ""

    /** Evaluated once by [GuideTestBase.setUpGuide] right after `/admin/reset`: enables the fixtures before the seed. */
    override val switches: String
        get() {
            pp = PlayerPanel(panel)
            pp.enableFixtures()
            return "m3u=1&$PLAYER_DEFAULTS" + if (playerSwitches.isNotBlank()) "&$playerSwitches" else ""
        }

    /** The fixture channel ids (skips the test when the panel has no `player_fixtures`). */
    protected val fx: PlayerPanel.Fixtures by lazy {
        val f = pp.fixtures()
        assumeTrue("the fake panel has no player_fixtures (start it with --set player_fixtures=true)", f != null)
        f!!
    }

    @After
    fun logTimeshift() {
        if (!::pp.isInitialized) return
        runCatching {
            val reqs = pp.timeshiftRequests()
            if (reqs.isNotEmpty()) log("timeshift log (${reqs.size}): ${reqs.joinToString("; ")}")
            val r = pp.rejections()
            log("panel rejections: $r")
        }
    }

    // ------------------------------------------------------------------ player machine and archive

    protected val probe: PlayerDebugProbe get() = ep.playerProbe()

    protected fun awaitPlayerMachine(timeoutMs: Long = 10_000): PlayerStateMachine {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            probe.current?.let { return it }
            Thread.sleep(100)
        }
        fail("PlayerDebugProbe.current is null after $timeoutMs ms: WP-D PlayerViewModel not created by MainActivity or it did not register its machine")
        error("unreachable")
    }

    protected val player: PlayerStateMachine get() = probe.current ?: awaitPlayerMachine()
    protected fun pui(): PlayerUiState = player.ui.value

    protected fun awaitPlayer(what: String, timeoutMs: Long = 5_000, predicate: (PlayerUiState) -> Boolean): PlayerUiState {
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { player.ui.first(predicate) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; player=${pui().brief()} guide=${ui().brief()}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
        return st
    }

    protected val archiveFlow: StateFlow<ArchiveStatus> get() = (probe.archive ?: ep.archivePlayback()).status
    protected fun archive(): ArchiveStatus = archiveFlow.value

    protected fun awaitArchive(what: String, timeoutMs: Long = 15_000, predicate: (ArchiveStatus) -> Boolean): ArchiveStatus {
        val t0 = SystemClock.elapsedRealtime()
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { archiveFlow.first(predicate) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; archive=${archive().brief()} controller=${controller.state.value}"); error("unreachable") }
        log("$what after ${SystemClock.elapsedRealtime() - t0} ms")
        return st
    }

    protected fun awaitController(what: String, timeoutMs: Long = 15_000, predicate: (PlaybackState) -> Boolean): PlaybackState {
        val st = runBlocking { withTimeoutOrNull(timeoutMs) { controller.state.first(predicate) } }
            ?: run { fail("timed out after $timeoutMs ms waiting for $what; controller=${controller.state.value}"); error("unreachable") }
        log("$what: $st")
        return st
    }

    protected fun isPlaying(identityOf: GuideChannel): Boolean =
        controller.state.value.let { it is PlaybackState.Playing && it.target.identity == identityOf.identity }

    // ------------------------------------------------------------------ navigation

    /** Lineup row of synthetic channel [id] (names end in " id"). */
    protected fun rowOf(id: Int): Int = findRow(0, 400) { it.displayName.endsWith(" $id") }

    /** Guide → tune channel [id] live → OK → FULL (USER). Returns the channel. */
    protected fun fullOn(id: Int): GuideChannel {
        launchGuide()
        awaitGuideReady()
        goHome()
        stopPlayback()
        toRow(rowOf(id))
        val ch = tuneFocusedRow()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("FULL on ${ch.displayName}", 5_000) { it.mode == ScreenMode.FULL }
        awaitPlayerMachine()
        return ch
    }

    /**
     * Guide → channel [id] → LEFT until the focused programme ended at least [endedMinAgo] minutes ago → OK (M3 catch-up
     * launch; FULL follows the first archive tune). Returns the channel and the programme cell.
     */
    protected fun playPast(id: Int, endedMinAgo: Int = 0, leftAtLeast: Int = 1): Pair<GuideChannel, GridFocus> {
        launchGuide()
        awaitGuideReady()
        goHome()
        stopPlayback()
        val row = rowOf(id)
        toRow(row)
        val ch = channelAt(row)!!
        assumeTrue("${ch.displayName} has no archive", ch.archiveDays > 0)
        var n = 0
        while (n < leftAtLeast || focus()!!.cellEnd > nowMin() - endedMinAgo) {
            press(KeyEvent.KEYCODE_DPAD_LEFT, intervalMs = 150)
            if (++n > 60) fail("no programme ended $endedMinAgo min ago on ${ch.displayName}; focus=${focus()}")
        }
        val f = focus()!!
        log("catch-up of ${ch.displayName} cell [${f.cellStart}, ${f.cellEnd}) (now ${nowMin()})")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("FULL with an archive tune", 15_000) { it.mode == ScreenMode.FULL && it.tuned.isArchive }
        awaitPlayerMachine()
        return ch to f
    }

    /** Closes whatever player layer is open with BACK (never BACK on bare video, which leaves FULL). */
    protected fun toBareVideo() {
        repeat(6) {
            if (pui().layer == PlayerLayer.NONE) return
            press(KeyEvent.KEYCODE_BACK, intervalMs = 250)
        }
        fail("could not return to bare video: ${pui().brief()}")
    }

    /** UP on bare video → quick bar (owner ruling 2026-10-15). */
    protected fun openQuickBar() {
        toBareVideo()
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitPlayer("quick bar open") { it.layer == PlayerLayer.QUICK_BAR && it.quick != null } // WP-A behaviour
    }

    /** Moves the quick bar's focus to [item] with LEFT/RIGHT and presses OK. */
    protected fun quickSelect(item: QuickItem) {
        awaitPlayer("quick bar shows $item") { it.layer == PlayerLayer.QUICK_BAR && it.quick?.items?.contains(item) == true }
        // Integration 2026-10-15 (test fix): steer by the live state; the item list can change while the bar is open (a
        // Previous channel or favourite answer arriving), so counting presses from one snapshot can overshoot.
        var guard = 0
        while (true) {
            val q = pui().quick ?: break
            val target = q.items.indexOf(item)
            if (target < 0 || target == q.focused || ++guard > 30) break
            press(if (target > q.focused) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, intervalMs = 120)
        }
        awaitPlayer("quick focus on $item") { s -> s.quick?.let { it.items.getOrNull(it.focused) == item } == true }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** In an open picker of [kind]: moves to the first choice matching [p] and presses OK. Returns the choice. */
    protected fun pickerSelect(kind: PickerKind, p: (PickerChoice) -> Boolean): PickerChoice {
        val st = awaitPlayer("$kind picker") { it.layer == PlayerLayer.PICKER && it.picker?.kind == kind }.picker!!
        val target = st.choices.indexOfFirst(p)
        if (target < 0) fail("$kind picker has no matching choice: ${st.choices}")
        var cur = st.focused
        while (cur != target) {
            val step = if (target > cur) 1 else -1
            press(if (step > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, intervalMs = 80)
            cur += step
        }
        awaitPlayer("picker focus $target") { it.picker?.focused == target }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        return st.choices[target]
    }

    // ------------------------------------------------------------------ pills, clocks

    /** The pill's −MM:SS value now (device wall clock), extrapolated like the UI (§2.1 PillState). */
    protected fun behindNowMs(p: PillState = pui().pill): Long =
        p.behindMs + if (p.behindGrows) System.currentTimeMillis() - p.atMs else 0

    protected fun assertBehind(expectedMs: Long, toleranceMs: Long, what: String) {
        val p = pui().pill
        val b = behindNowMs(p)
        log("$what: pill=$p behind=$b ms (expected $expectedMs ± $toleranceMs)")
        if (p.kind != PillKind.BEHIND && p.kind != PillKind.PAUSED) fail("$what: pill ${p.kind}, expected BEHIND") // WP-A behaviour
        if (abs(b - expectedMs) > toleranceMs) fail("$what: behind $b ms, expected $expectedMs ± $toleranceMs") // WP-A/WP-B behaviour
    }

    /** Polls [block] every [periodMs] in the background; for "never" assertions (never an ERROR layer, never live). */
    protected class Sampler<T>(periodMs: Long = 50, block: () -> T) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val samples: MutableList<T> = Collections.synchronizedList(ArrayList())
        init {
            scope.launch {
                while (true) {
                    runCatching(block).onSuccess { samples += it }
                    kotlinx.coroutines.delay(periodMs)
                }
            }
        }
        fun snapshot(): List<T> = synchronized(samples) { samples.toList() }
        override fun close() = scope.cancel()
    }

    // ------------------------------------------------------------------ views

    protected fun activity(): MainActivity {
        var a: MainActivity? = null
        scenario!!.onActivity { a = it }
        return a!!
    }

    /** WP-D's PlayerViewModel for this activity (the same instance MainActivity holds). */
    protected fun playerVm(): PlayerViewModel = main { ViewModelProvider(activity())[PlayerViewModel::class.java] }

    protected fun allViews(): List<View> = main {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity().window.decorView)
        out
    }

    protected fun surfaceViews(): List<SurfaceView> = allViews().filterIsInstance<SurfaceView>()

    /** media3-ui's SubtitleView (`:ui` keeps `media3-ui` an implementation dependency, so by class name). */
    protected fun subtitleViews(): List<View> = allViews().filter { it.javaClass.name == SUBTITLE_VIEW }

    protected fun screenRect(v: View): IntArray = main {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // ------------------------------------------------------------------ misc

    protected fun PlayerUiState.brief(): String =
        "layer=$layer info=${info?.let { "active=${it.active} ch=${it.channel?.number} n=${it.programmes.size} f=${it.focused}" }} " +
            "scrub=${scrub?.let { "focused=${it.focused} preview=${it.previewMs} seeking=${it.seeking} atLive=${it.atLive}" }} " +
            "channels=${channels?.let { "col=${it.column} focused=${it.focused} size=${it.size}" }} quick=${quick?.items} " +
            "picker=${picker?.kind} error=${error?.error?.code} pill=${pill.kind} stats=${stats?.size}"

    protected fun ArchiveStatus.brief(): String =
        "phase=$phase ch=${channel?.number} wall=$wallMs live=$liveEdgeMs timeShift=$timeShift variant=$variant " +
            "attempt=$attempt/$attempts failure=$failure settled=$settledSeq owned=$ownedTuneId"

    protected fun ArchiveStatus.active(): Boolean = phase != ArchivePhase.IDLE && phase != ArchivePhase.FAILED

    /**
     * True on the Android emulator (timing criteria that §5.9 leaves to hardware get an emulator ceiling). U-S8 (f): by
     * `Build.HARDWARE` only; no-name AOSP TV boxes carry a `generic` fingerprint and must get the hardware criteria.
     */
    protected fun isEmulator(): Boolean =
        (android.os.Build.HARDWARE in EMULATOR_HARDWARE).also { log("isEmulator: HARDWARE=${android.os.Build.HARDWARE} → $it") }

    companion object {
        /** Deterministic player settings for every test (§2.2 defaults spelled out), then default padding 60/300. */
        // (time-shift, docs/design/m6-timeshift.md §17.4) every class starts from a cleared ring and the Auto setting.
        const val PLAYER_DEFAULTS = "infobar=5000&numwait=1500&updown=quick&invert=0&back=guide&padding=60,300&ts_clear=1&timeshift=auto"
        const val SUBTITLE_VIEW = "androidx.media3.ui.SubtitleView"
        /** U-S8 (f): the emulator's `Build.HARDWARE` values. */
        val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")
        const val K_UP = KeyEvent.KEYCODE_DPAD_UP
        const val K_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        const val K_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val K_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val K_OK = KeyEvent.KEYCODE_DPAD_CENTER
        const val K_BACK = KeyEvent.KEYCODE_BACK
        const val K_CH_UP = KeyEvent.KEYCODE_CHANNEL_UP
        const val K_CH_DOWN = KeyEvent.KEYCODE_CHANNEL_DOWN
        const val K_PLAY_PAUSE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        const val K_FF = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        const val K_RW = KeyEvent.KEYCODE_MEDIA_REWIND
    }
}
