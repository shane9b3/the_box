package app.thebox.core.guide.state

import app.thebox.core.guide.NumberEntry

/**
 * Number entry (docs/design/m3-guide.md §3.9.4): at most [MAX_DIGITS] digits; every digit restarts the
 * [NUMBER_COMMIT_MS] deadline. D-pad entry (NUMBER_ENTRY, the five-key path): the buffer starts with a cursor digit
 * "0"; [adjust] changes the digit under the cursor (wrapping 0–9), [advance] appends a new cursor digit. D-pad entry has
 * no auto-commit (the user commits with OK), so [due] is false until a real digit key is typed.
 */
class NumberEntryImpl : NumberEntry {
    private val sb = StringBuilder(MAX_DIGITS)
    private var lastDigitMs = NO_DEADLINE
    /** (M4) PlayerSettings.numberEntryWaitMs (1 / 1.5 / 2.5 s); [NUMBER_COMMIT_MS] until the host sends one. */
    private var commitAfterMs = NUMBER_COMMIT_MS
    /** Index of the D-pad cursor digit, −1 when not in D-pad mode. */
    var cursor = -1
        private set

    override val digits: String get() = sb.toString()

    /** The digit under the D-pad cursor (for `Overlay.Number.cursorDigit`), −1 when none. */
    val cursorDigit: Int get() = if (cursor in sb.indices) sb[cursor] - '0' else -1

    override fun digit(d: Int, nowMs: Long): Boolean {
        require(d in 0..9) { "digit $d" }
        if (cursor >= 0) {
            // A digit key while in D-pad mode replaces the cursor digit and leaves D-pad mode.
            sb.setCharAt(cursor, '0' + d)
            cursor = -1
            lastDigitMs = nowMs
            return true
        }
        if (sb.length >= MAX_DIGITS) return false
        sb.append('0' + d)
        lastDigitMs = nowMs
        return true
    }

    /** Opens D-pad entry: an empty buffer with one cursor digit (§3.9.4 NUMBER_ENTRY). */
    fun startCursor() {
        sb.setLength(0)
        sb.append('0')
        cursor = 0
        lastDigitMs = NO_DEADLINE
    }

    override fun adjust(delta: Int) {
        if (cursor < 0) {
            if (sb.isEmpty()) startCursor() else cursor = sb.length - 1
        }
        val c = sb[cursor] - '0'
        sb.setCharAt(cursor, '0' + Math.floorMod(c + delta, 10))
    }

    override fun advance() {
        if (sb.isEmpty()) { startCursor(); return }
        if (sb.length >= MAX_DIGITS) return
        sb.append('0')
        cursor = sb.length - 1
    }

    override fun commitValue(): Int? = if (sb.isEmpty()) null else sb.toString().toInt()

    override fun due(nowMs: Long): Boolean = sb.isNotEmpty() && lastDigitMs != NO_DEADLINE && nowMs - lastDigitMs >= commitAfterMs

    /** True when the live preview is due: [NUMBER_PREVIEW_MS] after the last digit. */
    fun previewDue(nowMs: Long): Boolean = sb.isNotEmpty() && lastDigitMs != NO_DEADLINE && nowMs - lastDigitMs >= NUMBER_PREVIEW_MS

    /** Wall ms of the auto-commit, or [Long.MAX_VALUE] when none is armed. */
    fun commitAtMs(): Long = if (sb.isEmpty() || lastDigitMs == NO_DEADLINE) Long.MAX_VALUE else lastDigitMs + commitAfterMs

    fun previewAtMs(): Long = if (sb.isEmpty() || lastDigitMs == NO_DEADLINE) Long.MAX_VALUE else lastDigitMs + NUMBER_PREVIEW_MS

    /** (M4) The auto-commit wait; values below the preview delay are raised to it (the preview must come first). */
    override fun setCommitAfterMs(ms: Long) {
        commitAfterMs = maxOf(ms, NUMBER_PREVIEW_MS + 1)
    }

    override fun clear() {
        sb.setLength(0)
        cursor = -1
        lastDigitMs = NO_DEADLINE
    }

    companion object {
        const val MAX_DIGITS = 4
        const val NUMBER_COMMIT_MS = 1_500L
        const val NUMBER_PREVIEW_MS = 150L
        private const val NO_DEADLINE = Long.MIN_VALUE
    }
}
