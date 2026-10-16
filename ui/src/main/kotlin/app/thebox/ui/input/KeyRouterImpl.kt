package app.thebox.ui.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyGesture
import app.thebox.core.input.KeyGestureDetector
import app.thebox.core.input.KeyInput
import app.thebox.core.input.KeyMap
import app.thebox.core.input.Keys
import app.thebox.core.settings.SettingsEvent
import android.view.KeyEvent as AndroidKeyEvent

/**
 * The one key path (docs/design/m3-guide.md §2.10, §3.18.1; reviewed 2026-10-14, B1/F5):
 * Compose `KeyEvent.nativeKeyEvent` → [KeyInput] → [KeyGestureDetector] → [KeyMap] → `GuideEvent.Act`/`Release`,
 * dispatched to the attached machine. Main thread only.
 *
 * - Context PLAYER in `ScreenMode.FULL`, else GUIDE; `hasLong = keyMap.long(context, code) != null`.
 * - A key with a long binding arms a [LONG_PRESS_MS] timer on its first DOWN (remotes that never auto-repeat OK);
 *   the UP cancels it.
 * - Returns true for every key it handled including the matching UP; keys with no binding (volume, Home) return
 *   false. In SEARCH every key returns false except BACK while [searchFieldIdle] (empty field, IME hidden).
 * - [KeyCapabilities.record] runs for every bound or unbound DOWN with repeat 0 (the impl writes only new codes, N7).
 * - (code review 2026-10-14, H-S4) A key whose action the machine would ignore (`GuideStateMachine.handles` false:
 *   M3 FULL passed Play/Pause and FF/RW; M4 FULL handles them, §7 item 15) returns false, so the framework hands it
 *   to M2's media session.
 * - (code review 2026-10-14, H-S11) A held (repeating) key whose UP is lost still ends its hold: focus loss, window
 *   detach ([onFocusLost]) and a [HOLD_WATCHDOG_MS] gap without a repeat dispatch `Release` for it.
 * - (code review 2026-10-14, H-S5) [detach] clears the machine only if it is still the attached one.
 * - (M4, docs/design/m4-player-ux.md §3.1.7, reviewed 2026-10-15, S5) While `GuideStateMachine.horizontalRepeat` is set
 *   on a key's first DOWN, D-pad LEFT/RIGHT ignore their long binding for that press (they act on DOWN with
 *   auto-repeat). The decision is stored per held key next to [heldContext], so a change mid-hold (the scrub bar
 *   opening on LEFT) never turns the held key into a long press.
 * - (M5, docs/design/m5-setup-settings.md §2.12 item 5, §3.8.4; reviewed 2026-10-16, S14) [capture]: while armed, the
 *   next DOWN with repeat 0 of any key (BACK/ESCAPE/BUTTON_B included: the screen treats them as cancel) goes to the
 *   listener with its `InputDevice` name instead of the key map; the router disarms itself, consumes that DOWN and
 *   then **every** event of the captured code until its UP (a held key's repeats never re-enter routing as a fresh
 *   press). Keys held from before arming finish on the normal path. Focus loss disarms. The key map is never asked
 *   while armed: capture is a mode of the one router, not a second key path.
 */
class KeyRouterImpl(
    private val keyMap: KeyMap,
    private val detector: KeyGestureDetector,
    private val capabilities: KeyCapabilities,
    private val timers: Timers = HandlerTimers(),
    private val uptimeMs: () -> Long = SystemClock::uptimeMillis,
) : KeyRouter {

    /** Delayed main-thread callbacks; injectable so tests run without a Looper. */
    interface Timers {
        fun post(delayMs: Long, block: () -> Unit): Any
        fun cancel(token: Any)
    }

    private class HandlerTimers : Timers {
        private val handler = Handler(Looper.getMainLooper())
        override fun post(delayMs: Long, block: () -> Unit): Any = Runnable(block).also { handler.postDelayed(it, delayMs) }
        override fun cancel(token: Any) = handler.removeCallbacks(token as Runnable)
    }

    private var machine: GuideStateMachine? = null
    private var searchFieldIdle: () -> Boolean = { true }
    /** Codes whose DOWN we consumed, so the matching UP is consumed too (and only then). */
    private val consumedDown = HashSet<Int>()
    /** Context of each held key, fixed at its first DOWN (a mode change mid-press must not remap the UP). */
    private val heldContext = HashMap<Int, KeyContext>()
    /** Held D-pad LEFT/RIGHT whose long binding is ignored for this press (S5), fixed at its first DOWN. */
    private val heldNoLong = HashSet<Int>()
    private val longTimers = HashMap<Int, Any>()
    /** Repeating keys without a long binding (holds) → their watchdog timer token. */
    private val holdWatchdogs = HashMap<Int, Any>()
    private val out = ArrayList<KeyGesture>(2)
    /** (M5, S14) The armed capture listener, or null. */
    private var captureListener: ((CapturedKey) -> Unit)? = null
    /** (M5, S14) The captured code, swallowed until its UP; [NO_CODE] when none. */
    private var swallowCode = NO_CODE

    override fun capture(listener: ((CapturedKey) -> Unit)?) {
        captureListener = listener
    }

    /** True while a capture listener waits for a key (tests). */
    val captureArmed: Boolean get() = captureListener != null

    override fun attach(machine: GuideStateMachine?, searchFieldIdle: () -> Boolean) {
        onFocusLost() // ends the holds of the machine that was attached
        this.machine = machine
        this.searchFieldIdle = searchFieldIdle
    }

    override fun detach(machine: GuideStateMachine) {
        if (this.machine !== machine) return // a newer view model owns the router (H-S5)
        onFocusLost()
        this.machine = null
        this.searchFieldIdle = { true }
    }

    /** The attached machine (tests). */
    val attached: GuideStateMachine? get() = machine

    override fun onKey(event: KeyEvent): Boolean {
        val n = event.nativeKeyEvent
        val isDown = when (n.action) {
            AndroidKeyEvent.ACTION_DOWN -> true
            AndroidKeyEvent.ACTION_UP -> false
            else -> return false
        }
        val device = if (captureListener != null) n.device else null
        return onNative(
            n.keyCode, isDown, n.repeatCount, n.downTime, n.eventTime, (n.flags and AndroidKeyEvent.FLAG_CANCELED) != 0,
            deviceId = n.deviceId, deviceName = device?.name, isVirtual = device?.isVirtual ?: true,
        )
    }

    /** The routing without Compose/Android event types (tests). [deviceName]/[isVirtual] matter only to a capture (S14). */
    fun onNative(
        code: Int, isDown: Boolean, repeatCount: Int, downTimeMs: Long, eventTimeMs: Long, canceled: Boolean = false,
        deviceId: Int = -1, deviceName: String? = null, isVirtual: Boolean = deviceName == null,
    ): Boolean {
        // (M5, S14) A captured key is swallowed until its UP, even after the capture disarmed.
        if (swallowCode != NO_CODE && code == swallowCode) {
            if (!isDown) swallowCode = NO_CODE
            return true
        }
        val listener = captureListener
        if (listener != null && code !in consumedDown) {
            if (isDown && repeatCount == 0) {
                captureListener = null
                swallowCode = code
                listener(CapturedKey(code, keyName(code), deviceName, deviceId, isVirtual))
            }
            return true // repeats and stray UPs are ignored while armed
        }
        val m = machine ?: return false
        // (M5 commit 0, docs/design/m5-setup-settings.md §2.12 item 5, S3) The settings editor owns every key but BACK:
        // BACK never reaches the Activity (no BackHandler exists) and becomes SettingsEvent.EditorBack. Keys held from
        // before the editor opened finish on the normal path.
        if (m.textInputActive && code !in consumedDown) {
            if (code != Keys.BACK && code != Keys.ESCAPE && code != Keys.BUTTON_B) return false
            if (isDown && repeatCount == 0) m.settings?.dispatch(SettingsEvent.EditorBack)
            return true
        }
        if (!isDown) {
            if (!consumedDown.remove(code)) return false
        } else if (repeatCount == 0) {
            capabilities.record(code)
            val mode = m.ui.value.mode
            val context = if (mode == ScreenMode.FULL) KeyContext.PLAYER else KeyContext.GUIDE
            val short = keyMap.short(context, code)
            val dropLong = m.horizontalRepeat && (code == Keys.DPAD_LEFT || code == Keys.DPAD_RIGHT)
            val long = if (dropLong) null else keyMap.long(context, code)
            if (short == null && long == null) return false
            if (mode == ScreenMode.SEARCH && !(short == BoxAction.BACK && searchFieldIdle())) return false
            if (long == null && short != null && !m.handles(short)) return false // H-S4: on to the media session
            consumedDown += code
            heldContext[code] = context
            if (dropLong) heldNoLong += code else heldNoLong -= code
        } else if (code !in consumedDown) {
            return false
        }
        val context = heldContext[code] ?: KeyContext.GUIDE
        val hasLong = longOf(context, code) != null
        if (!isDown) { heldContext.remove(code); heldNoLong.remove(code) }
        out.clear()
        detector.onKey(KeyInput(code, isDown, repeatCount, downTimeMs, eventTimeMs, canceled), hasLong, out)
        if (isDown && repeatCount == 0 && hasLong) armLong(code, context)
        if (!isDown || out.any { it is KeyGesture.LongPress }) longTimers.remove(code)?.let { timers.cancel(it) }
        holdWatchdogs.remove(code)?.let { timers.cancel(it) }
        if (isDown && repeatCount > 0 && !hasLong) armWatchdog(code, context)
        deliver(context, if (isDown) eventTimeMs - downTimeMs else 0L)
        return true
    }

    /** H-S11: no repeat for [HOLD_WATCHDOG_MS] on a held key means its UP was lost: end the hold. */
    private fun armWatchdog(code: Int, context: KeyContext) {
        holdWatchdogs[code] = timers.post(HOLD_WATCHDOG_MS) {
            holdWatchdogs.remove(code)
            if (code in consumedDown) endHold(code, context)
        }
    }

    /** Forgets a held key as if its UP had been canceled and tells the machine the hold ended. */
    private fun endHold(code: Int, context: KeyContext) {
        consumedDown.remove(code)
        heldContext.remove(code)
        val hasLong = longOf(context, code) != null
        heldNoLong.remove(code)
        out.clear()
        detector.onKey(KeyInput(code, false, 0, 0L, uptimeMs(), canceled = true), hasLong, out)
        out.clear()
        keyMap.short(context, code)?.let { a -> machine?.dispatch(GuideEvent.Release(a)) }
    }

    private fun armLong(code: Int, context: KeyContext) {
        longTimers.remove(code)?.let { timers.cancel(it) }
        longTimers[code] = timers.post(LONG_PRESS_MS) {
            longTimers.remove(code)
            out.clear()
            detector.onTimer(code, uptimeMs(), out)
            deliver(context, LONG_PRESS_MS)
        }
    }

    private fun deliver(context: KeyContext, heldMs: Long) {
        val m = machine ?: return
        for (g in out) {
            when (g) {
                is KeyGesture.Press -> keyMap.short(context, g.keyCode)?.let { a ->
                    m.dispatch(GuideEvent.Act(a, g.repeat, if (a == BoxAction.DIGIT) keyMap.digit(g.keyCode) else -1, heldMs))
                }
                is KeyGesture.LongPress -> longOf(context, g.keyCode)?.let { m.dispatch(GuideEvent.Act(it, 0, -1, heldMs)) }
                is KeyGesture.ReleaseAfterRepeat -> keyMap.short(context, g.keyCode)?.let { m.dispatch(GuideEvent.Release(it)) }
            }
        }
        out.clear()
    }

    override fun onFocusLost() {
        // (M5, S14) Focus loss disarms a capture and forgets a swallowed key (its UP will not arrive).
        captureListener = null
        swallowCode = NO_CODE
        // H-S11: Compose drops the UPs of keys held across a focus change; end their holds in the machine.
        val held = holdWatchdogs.keys.toList()
        for (t in holdWatchdogs.values) timers.cancel(t)
        holdWatchdogs.clear()
        for (code in held) if (code in consumedDown) endHold(code, heldContext[code] ?: KeyContext.GUIDE)
        detector.reset()
        consumedDown.clear()
        heldContext.clear()
        heldNoLong.clear()
        for (t in longTimers.values) timers.cancel(t)
        longTimers.clear()
    }

    /** The long binding of a held key, or null when S5 dropped it for this press. */
    private fun longOf(context: KeyContext, code: Int): BoxAction? = if (code in heldNoLong) null else keyMap.long(context, code)

    override fun sinkModifier(requester: FocusRequester): Modifier =
        Modifier
            .focusRequester(requester)
            .onFocusChanged { if (!it.isFocused) onFocusLost() }
            .onPreviewKeyEvent(::onKey)
            .focusable()

    companion object {
        private const val NO_CODE = Int.MIN_VALUE

        /** `KEYCODE_…` names (§9 F2); the overlay shows its own table first. */
        fun keyName(code: Int): String = try {
            AndroidKeyEvent.keyCodeToString(code)
        } catch (e: RuntimeException) {
            code.toString()
        }

        const val LONG_PRESS_MS = 500L
        /** H-S11: a held key that has not repeated for this long lost its UP. Key repeat runs at ~20 Hz. */
        const val HOLD_WATCHDOG_MS = 1_500L
    }
}
