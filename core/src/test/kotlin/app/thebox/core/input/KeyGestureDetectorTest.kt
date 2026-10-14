package app.thebox.core.input

import app.thebox.core.input.KeyGesture.LongPress
import app.thebox.core.input.KeyGesture.Press
import app.thebox.core.input.KeyGesture.ReleaseAfterRepeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §3.18.1 timing table (docs/design/m3-guide.md §5.4 `KeyGestureDetectorTest`). */
class KeyGestureDetectorTest {
    private val d = KeyGestureDetectorImpl()
    private val ok = Keys.DPAD_CENTER
    private val down = Keys.DPAD_DOWN

    private fun key(code: Int, isDown: Boolean, repeat: Int, at: Long, hasLong: Boolean, canceled: Boolean = false): List<KeyGesture> {
        val out = ArrayList<KeyGesture>()
        d.onKey(KeyInput(code, isDown, repeat, downTimeMs = 1_000, eventTimeMs = at, canceled = canceled), hasLong, out)
        return out
    }

    private fun timer(code: Int, at: Long): List<KeyGesture> = ArrayList<KeyGesture>().also { d.onTimer(code, at, it) }

    @Test fun shortTapWithoutLongBindingFiresOnDown() {
        assertEquals(listOf(Press(down, 0)), key(down, true, 0, 1_000, hasLong = false))
        assertEquals(emptyList(), key(down, false, 0, 1_080, hasLong = false))
    }

    @Test fun shortTapWithLongBindingFiresOnUp() {
        assertEquals(emptyList(), key(ok, true, 0, 1_000, hasLong = true))
        assertEquals(listOf(Press(ok, 0)), key(ok, false, 0, 1_120, hasLong = true))
    }

    @Test fun hold499MsTimerEarlyIsStillShort() {
        key(ok, true, 0, 1_000, hasLong = true)
        assertEquals(emptyList(), timer(ok, 1_499))
        assertEquals(listOf(Press(ok, 0)), key(ok, false, 0, 1_499, hasLong = true))
    }

    @Test fun hold500MsByTimerIsLongAndUpIsSwallowed() {
        key(ok, true, 0, 1_000, hasLong = true)
        assertEquals(listOf(LongPress(ok)), timer(ok, 1_500))
        assertEquals(emptyList(), timer(ok, 1_600), "fires once")
        assertEquals(emptyList(), key(ok, false, 0, 1_700, hasLong = true), "UP swallowed after a long press")
    }

    @Test fun firstRepeatIsLongEvenBeforeTheTimer() {
        key(ok, true, 0, 1_000, hasLong = true)
        assertEquals(listOf(LongPress(ok)), key(ok, true, 1, 1_400, hasLong = true))
        assertEquals(emptyList(), key(ok, true, 2, 1_450, hasLong = true))
        assertEquals(emptyList(), timer(ok, 1_500))
        assertEquals(emptyList(), key(ok, false, 0, 1_600, hasLong = true))
    }

    @Test fun repeatsWithoutLongBindingAndRelease() {
        key(down, true, 0, 1_000, hasLong = false)
        assertEquals(listOf(Press(down, 1)), key(down, true, 1, 1_500, hasLong = false))
        assertEquals(listOf(Press(down, 2)), key(down, true, 2, 1_550, hasLong = false))
        assertEquals(listOf(ReleaseAfterRepeat(down)), key(down, false, 0, 1_600, hasLong = false))
    }

    @Test fun canceledUpProducesNothing() {
        key(ok, true, 0, 1_000, hasLong = true)
        assertEquals(emptyList(), key(ok, false, 0, 1_100, hasLong = true, canceled = true))
        key(down, true, 0, 1_000, hasLong = false)
        key(down, true, 1, 1_500, hasLong = false)
        assertEquals(emptyList(), key(down, false, 0, 1_600, hasLong = false, canceled = true))
    }

    @Test fun midPressFocusRepeatAndUpWithoutStateAreIgnored() {
        assertEquals(emptyList(), key(down, true, 3, 1_000, hasLong = false))
        assertEquals(emptyList(), key(ok, false, 0, 1_000, hasLong = true))
        assertEquals(emptyList(), timer(ok, 9_999))
    }

    @Test fun resetForgetsKeys() {
        key(ok, true, 0, 1_000, hasLong = true)
        d.reset()
        assertEquals(emptyList(), timer(ok, 1_600))
        assertEquals(emptyList(), key(ok, false, 0, 1_700, hasLong = true))
        assertTrue(!d.isDown(ok))
    }

    @Test fun keysAreIndependent() {
        key(ok, true, 0, 1_000, hasLong = true)
        assertEquals(listOf(Press(down, 0)), key(down, true, 0, 1_100, hasLong = false))
        assertEquals(listOf(LongPress(ok)), timer(ok, 1_500))
    }
}
