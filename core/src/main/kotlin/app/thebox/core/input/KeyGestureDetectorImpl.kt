package app.thebox.core.input

/**
 * Short/long/repeat detection (docs/design/m3-guide.md §3.18.1; API notes §2.2). Per key code: `{downAt, longFired,
 * repeats}`.
 *
 * - DOWN, repeat 0: remember it. No long binding → `Press(0)` now. With one → nothing yet; the router arms a
 *   [LONG_PRESS_MS] timer that calls [onTimer] (remotes that never auto-repeat OK).
 * - DOWN, repeat > 0: no long binding → `Press(repeat)`; with one → `LongPress` once, on the first repeat or when
 *   `eventTime − downAt ≥ LONG_PRESS_MS`, whichever comes first.
 * - UP: long binding and no `LongPress` yet → `Press(0)`; after a `LongPress` the UP is swallowed (OK must not also
 *   tune); no long binding and repeats seen → `ReleaseAfterRepeat`. A canceled UP produces nothing.
 * - A repeat or UP for a key without state (focus arrived mid-press) is ignored. Not thread-safe: main thread only.
 */
class KeyGestureDetectorImpl : KeyGestureDetector {
    private class State(var downAt: Long, var hasLong: Boolean, var longFired: Boolean = false, var repeats: Int = 0)

    private val keys = HashMap<Int, State>()

    override fun onKey(input: KeyInput, hasLong: Boolean, out: MutableList<KeyGesture>) {
        val code = input.keyCode
        if (input.isDown) {
            if (input.repeatCount == 0) {
                keys[code] = State(input.eventTimeMs, hasLong)
                if (!hasLong) out += KeyGesture.Press(code, 0)
                return
            }
            val s = keys[code] ?: return
            s.repeats = input.repeatCount
            if (!s.hasLong) {
                out += KeyGesture.Press(code, input.repeatCount)
            } else if (!s.longFired) {
                // First repeat (tv-material's long click, FLAG_LONG_PRESS) or the hold time, whichever comes first.
                s.longFired = true
                out += KeyGesture.LongPress(code)
            }
            return
        }
        val s = keys.remove(code) ?: return
        if (input.canceled) return
        when {
            s.hasLong && !s.longFired -> out += KeyGesture.Press(code, 0)
            s.hasLong -> Unit // swallowed after a long press
            s.repeats > 0 -> out += KeyGesture.ReleaseAfterRepeat(code)
        }
    }

    override fun onTimer(keyCode: Int, nowMs: Long, out: MutableList<KeyGesture>) {
        val s = keys[keyCode] ?: return
        if (!s.hasLong || s.longFired) return
        if (nowMs - s.downAt < LONG_PRESS_MS) return
        s.longFired = true
        out += KeyGesture.LongPress(keyCode)
    }

    override fun reset() = keys.clear()

    /** True while [keyCode] is down (a DOWN was seen and no UP yet). */
    fun isDown(keyCode: Int): Boolean = keys.containsKey(keyCode)

    companion object {
        const val LONG_PRESS_MS = 500L
    }
}
