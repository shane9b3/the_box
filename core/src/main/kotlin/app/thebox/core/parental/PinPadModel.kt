package app.thebox.core.parental

/**
 * The PIN pad's logic (docs/design/m5-setup-settings.md §3.9.3), pure and main-thread only. The digits live only in
 * this object's [IntArray]s and are zeroed on every exit path ([clear], a submit, a mismatch, cancel); the UI state
 * carries the count ([filled]) and the focused key, never a digit.
 *
 * Grid (3 × 4, no wrap): keys 0–8 = digits 1–9, 9 = ⌫, 10 = 0, 11 = OK (`PinPromptState.focusedKey`).
 *
 * In VERIFY the fourth digit submits ([Result.Submit]). In CREATE_FIRST the fourth digit moves to CREATE_CONFIRM, or
 * to the soft "Easy to guess" confirm ([easyConfirm]) for 0000, 1111 … 9999, 1234 and 4321. In CREATE_CONFIRM the
 * fourth digit compares: [Result.Created] or [Result.Mismatch] (back to CREATE_FIRST).
 */
class PinPadModel {
    sealed interface Result {
        /** Nothing for the caller to do (a key moved the focus, a digit was typed or deleted). */
        data object None : Result
        /** VERIFY: check [digits] (the caller hands them to the effect, which zeroes them). */
        class Submit(val digits: CharArray) : Result
        /** CREATE_CONFIRM matched: store [digits] (the caller hands them on; the effect zeroes them). */
        class Created(val digits: CharArray) : Result
        /** CREATE_CONFIRM did not match: back to CREATE_FIRST, nothing kept. */
        data object Mismatch : Result
        /** BACK with no digit typed: the prompt closes, the owner stays where it was. */
        data object Cancel : Result
    }

    var mode: PinPadMode = PinPadMode.VERIFY
        private set
    var focusedKey: Int = KEY_FIVE
        private set
    /** The "Easy to guess. Use it anyway?" card is up (CREATE_FIRST only); [easyFocused] 0 = Use it, 1 = Choose another. */
    var easyConfirm: Boolean = false
        private set
    var easyFocused: Int = 1
        private set
    /** Digits are refused (lockout countdown or a check in flight); BACK still cancels. */
    var inputDisabled: Boolean = false

    private val digits = IntArray(PIN_LENGTH)
    private val first = IntArray(PIN_LENGTH)
    private var count = 0
    private var firstSet = false

    val filled: Int get() = count

    /** Opens the pad in [mode] with nothing typed. */
    fun reset(mode: PinPadMode) {
        clear()
        this.mode = mode
        focusedKey = KEY_FIVE
        inputDisabled = false
    }

    /** Zeroes both digit buffers and closes the soft confirm. Called on every exit path. */
    fun clear() {
        digits.fill(0)
        first.fill(0)
        count = 0
        firstSet = false
        easyConfirm = false
        easyFocused = 1
    }

    /** A digit key (0–9) of the remote. */
    fun digit(d: Int): Result {
        if (d !in 0..9 || inputDisabled || easyConfirm) return Result.None
        if (count >= PIN_LENGTH) return Result.None
        digits[count++] = d
        return if (count == PIN_LENGTH) complete() else Result.None
    }

    /** D-pad on the grid (no wrap). dx/dy ∈ {−1, 0, 1}. In the soft confirm LEFT/RIGHT move between its two buttons. */
    fun move(dx: Int, dy: Int) {
        if (easyConfirm) { if (dx != 0) easyFocused = (easyFocused + dx).coerceIn(0, 1); return }
        val col = focusedKey % COLS
        val row = focusedKey / COLS
        val nc = (col + dx).coerceIn(0, COLS - 1)
        val nr = (row + dy).coerceIn(0, ROWS - 1)
        focusedKey = nr * COLS + nc
    }

    /** OK on the focused key (or the focused soft-confirm button). */
    fun press(): Result {
        if (easyConfirm) {
            return if (easyFocused == 0) {
                easyConfirm = false
                toConfirm()
                Result.None
            } else {
                clear()
                Result.None
            }
        }
        return when (focusedKey) {
            KEY_DELETE -> { deleteLast(); Result.None }
            KEY_ZERO -> digit(0)
            KEY_OK -> if (count == PIN_LENGTH && !inputDisabled) complete() else Result.None
            else -> digit(focusedKey + 1)
        }
    }

    /** BACK: delete the last digit; with none, cancel (the soft confirm's BACK = Choose another). */
    fun back(): Result {
        if (easyConfirm) { clear(); return Result.None }
        if (count > 0) { deleteLast(); return Result.None }
        clear()
        return Result.Cancel
    }

    private fun deleteLast() {
        if (count == 0) return
        digits[--count] = 0
    }

    private fun complete(): Result = when (mode) {
        PinPadMode.VERIFY -> Result.Submit(take(digits)).also { clear() }
        PinPadMode.CREATE_FIRST -> {
            if (isEasy(digits)) { easyConfirm = true; easyFocused = 1 } else toConfirm()
            Result.None
        }
        PinPadMode.CREATE_CONFIRM -> {
            val same = digits.contentEquals(first)
            if (same) Result.Created(take(digits)).also { clear(); mode = PinPadMode.CREATE_CONFIRM }
            else { clear(); mode = PinPadMode.CREATE_FIRST; Result.Mismatch }
        }
    }

    private fun toConfirm() {
        digits.copyInto(first)
        firstSet = true
        digits.fill(0)
        count = 0
        mode = PinPadMode.CREATE_CONFIRM
        focusedKey = KEY_FIVE
    }

    private fun take(src: IntArray): CharArray = CharArray(PIN_LENGTH) { ('0'.code + src[it]).toChar() }

    companion object {
        const val PIN_LENGTH = 4
        const val COLS = 3
        const val ROWS = 4
        const val KEY_FIVE = 4
        const val KEY_DELETE = 9
        const val KEY_ZERO = 10
        const val KEY_OK = 11

        /** 0000, 1111 … 9999, 1234 and 4321 (§3.9.3). */
        fun isEasy(d: IntArray): Boolean {
            if (d.size != PIN_LENGTH) return false
            if (d.all { it == d[0] }) return true
            val asc = (1 until d.size).all { d[it] == d[it - 1] + 1 }
            val desc = (1 until d.size).all { d[it] == d[it - 1] - 1 }
            return (asc && d[0] == 1) || (desc && d[0] == 4)
        }
    }
}
