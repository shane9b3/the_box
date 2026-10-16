package app.thebox.core.parental

import app.thebox.core.parental.PinPadModel.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.9.3: every key × mode, auto-submit, digits cleared on every exit path. */
class PinPadModelTest {
    private fun model(mode: PinPadMode) = PinPadModel().also { it.reset(mode) }

    /** The digits the model holds, through reflection (they never leave it otherwise). */
    private fun held(m: PinPadModel): List<Int> {
        val out = ArrayList<Int>()
        for (name in listOf("digits", "first")) {
            val f = PinPadModel::class.java.getDeclaredField(name).also { it.isAccessible = true }
            out += (f.get(m) as IntArray).toList()
        }
        return out
    }

    @Test fun verifyAutoSubmitsOnTheFourthDigitAndClears() {
        val m = model(PinPadMode.VERIFY)
        for (d in listOf(1, 2, 3)) assertEquals(Result.None, m.digit(d))
        assertEquals(3, m.filled)
        val r = assertIs<Result.Submit>(m.digit(4))
        assertContentEquals(charArrayOf('1', '2', '3', '4'), r.digits)
        assertEquals(0, m.filled)
        assertTrue(held(m).all { it == 0 }, "digits cleared after the submit")
    }

    @Test fun okKeySubmitsOnlyWithFourDigits() {
        val m = model(PinPadMode.VERIFY)
        m.digit(5)
        repeat(3) { m.move(1, 0) }; repeat(4) { m.move(0, 1) } // to OK (bottom right)
        assertEquals(PinPadModel.KEY_OK, m.focusedKey)
        assertEquals(Result.None, m.press(), "OK with one digit does nothing")
        assertEquals(1, m.filled)
    }

    @Test fun gridMovesWithoutWrapAndKeysType() {
        val m = model(PinPadMode.VERIFY)
        assertEquals(PinPadModel.KEY_FIVE, m.focusedKey)
        m.move(-1, 0); m.move(-1, 0); m.move(0, -1); m.move(0, -1)
        assertEquals(0, m.focusedKey, "top left, no wrap")
        m.press() // "1"
        m.move(0, 3); m.move(1, 0) // 0 key (row 4, col 2)
        assertEquals(PinPadModel.KEY_ZERO, m.focusedKey)
        m.press() // "0"
        m.move(-1, 0) // ⌫
        assertEquals(PinPadModel.KEY_DELETE, m.focusedKey)
        m.press()
        assertEquals(1, m.filled, "⌫ deleted the 0")
        m.move(2, 0); m.move(5, 5)
        assertEquals(PinPadModel.KEY_OK, m.focusedKey, "clamped")
    }

    @Test fun backDeletesThenCancelsAndClears() {
        val m = model(PinPadMode.VERIFY)
        m.digit(7); m.digit(8)
        assertEquals(Result.None, m.back()); assertEquals(1, m.filled)
        assertEquals(Result.None, m.back()); assertEquals(0, m.filled)
        assertEquals(Result.Cancel, m.back())
        assertTrue(held(m).all { it == 0 })
    }

    @Test fun createTwiceMatches() {
        val m = model(PinPadMode.CREATE_FIRST)
        for (d in listOf(2, 5, 8, 0)) m.digit(d)
        assertEquals(PinPadMode.CREATE_CONFIRM, m.mode)
        assertEquals(0, m.filled)
        for (d in listOf(2, 5, 8)) m.digit(d)
        val r = assertIs<Result.Created>(m.digit(0))
        assertContentEquals(charArrayOf('2', '5', '8', '0'), r.digits)
        assertTrue(held(m).all { it == 0 }, "both buffers cleared")
    }

    @Test fun createMismatchGoesBackToFirstAndClears() {
        val m = model(PinPadMode.CREATE_FIRST)
        for (d in listOf(2, 5, 8, 0)) m.digit(d)
        for (d in listOf(2, 5, 8)) m.digit(d)
        assertEquals(Result.Mismatch, m.digit(1))
        assertEquals(PinPadMode.CREATE_FIRST, m.mode)
        assertTrue(held(m).all { it == 0 })
    }

    @Test fun easyPinsAskForASoftConfirm() {
        for (pin in listOf("0000", "1111", "9999", "1234", "4321")) {
            val m = model(PinPadMode.CREATE_FIRST)
            pin.forEach { m.digit(it - '0') }
            assertTrue(m.easyConfirm, "$pin is easy")
            assertEquals(PinPadMode.CREATE_FIRST, m.mode)
            assertEquals(Result.None, m.digit(3), "digits ignored while the confirm is up")
            assertEquals(1, m.easyFocused, "focus on Choose another (the safe button)")
            m.press()
            assertFalse(m.easyConfirm); assertEquals(0, m.filled); assertEquals(PinPadMode.CREATE_FIRST, m.mode)
            pin.forEach { m.digit(it - '0') }
            m.move(-1, 0); m.press() // Use it
            assertEquals(PinPadMode.CREATE_CONFIRM, m.mode)
        }
        for (pin in listOf("1235", "2468", "5432", "0123")) {
            val m = model(PinPadMode.CREATE_FIRST)
            pin.forEach { m.digit(it - '0') }
            assertFalse(m.easyConfirm, "$pin is not on the list")
        }
    }

    @Test fun backInTheSoftConfirmChoosesAnother() {
        val m = model(PinPadMode.CREATE_FIRST)
        "1111".forEach { m.digit(it - '0') }
        assertEquals(Result.None, m.back())
        assertFalse(m.easyConfirm)
        assertTrue(held(m).all { it == 0 })
    }

    @ParameterizedTest
    @EnumSource(PinPadMode::class)
    fun inputDisabledRefusesDigitsButBackStillCancels(mode: PinPadMode) {
        val m = model(mode)
        m.inputDisabled = true
        assertEquals(Result.None, m.digit(1))
        assertEquals(Result.None, m.press())
        assertEquals(0, m.filled)
        assertEquals(Result.Cancel, m.back())
    }

    @ParameterizedTest
    @EnumSource(PinPadMode::class)
    fun resetClearsEverything(mode: PinPadMode) {
        val m = model(PinPadMode.CREATE_FIRST)
        for (d in listOf(3, 6, 9, 2)) m.digit(d)
        m.digit(3)
        m.reset(mode)
        assertEquals(mode, m.mode); assertEquals(0, m.filled); assertEquals(PinPadModel.KEY_FIVE, m.focusedKey)
        assertTrue(held(m).all { it == 0 })
    }

    @Test fun moreThanFourDigitsAreIgnored() {
        val m = model(PinPadMode.CREATE_CONFIRM)
        // CREATE_CONFIRM without a first entry compares with zeros: 0000 matches, nothing else.
        for (d in listOf(1, 2, 3)) m.digit(d)
        assertIs<Result.Mismatch>(m.digit(4))
        assertEquals(Result.None, m.digit(-1)); assertEquals(Result.None, m.digit(10))
    }
}
