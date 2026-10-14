package app.thebox.core.playback.afr

import app.thebox.core.playback.DisplayModeInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayModePickerTest {
    private val p = DisplayModePickerImpl()
    private val ntsc24 = 24_000f / 1_001f
    private val ntsc30 = 30_000f / 1_001f
    private val ntsc48 = 48_000f / 1_001f
    private val ntsc60 = 60_000f / 1_001f

    private var nextId = 1
    private fun m(hz: Float, w: Int = 1920, h: Int = 1080) = DisplayModeInfo(nextId++, w, h, hz)

    // A typical 1080p TV: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60 + the same rates at 4K.
    private val tv1080 = listOf(m(ntsc24), m(24f), m(25f), m(ntsc30), m(30f), m(50f), m(ntsc60), m(60f))
    private val tv4k = listOf(m(24f, 3840, 2160), m(50f, 3840, 2160), m(60f, 3840, 2160))
    private val all = tv1080 + tv4k
    private fun hz(r: Float) = tv1080.first { it.refreshRate == r }

    @Test
    fun twentyFiveGoesToFiftyEvenWithATwentyFiveHzMode() {
        assertEquals(hz(50f), p.pick(hz(60f), all, 25f))
    }

    @Test
    fun fiftyOnFiftyIsANoOp() {
        assertNull(p.pick(hz(50f), all, 50f))
        assertNull(p.pick(hz(50f), all, 25f), "25 fps already fits 50 Hz")
    }

    @Test
    fun ntscThirtyGoesToFiftyNineNineFour() {
        assertEquals(hz(ntsc60), p.pick(hz(50f), all, ntsc30))
        assertEquals(hz(ntsc60), p.pick(hz(60f), all, ntsc60))
    }

    @Test
    fun filmUsesTwentyFourPWhenOffered() {
        assertEquals(hz(ntsc24), p.pick(hz(60f), all, ntsc24))
        assertEquals(hz(24f), p.pick(hz(60f), all, 24f))
        assertNull(p.pick(hz(ntsc24), all, ntsc24))
    }

    @Test
    fun filmWithoutTwentyFourPFallsBackToDoubleThenNone() {
        val noFilm = listOf(m(25f), m(50f), m(ntsc60), m(60f))
        assertNull(p.pick(noFilm[3], noFilm, ntsc24))
        val withDouble = noFilm + m(ntsc48)
        assertEquals(withDouble.last(), p.pick(noFilm[3], withDouble, ntsc24))
        val withHigh = noFilm + m(120_000f / 1_001f)
        assertEquals(withHigh.last(), p.pick(noFilm[3], withHigh, ntsc24))
    }

    @Test
    fun lowestRefreshAmongTheHighMultiples() {
        val modes = listOf(m(60f), m(72f), m(120f))
        assertEquals(modes[1], p.pick(modes[0], modes, 24f))
    }

    @Test
    fun noMatchingModeIsNull() {
        val modes = listOf(m(60f), m(70f))
        assertNull(p.pick(modes[0], modes, 25f))
        assertNull(p.pick(modes[0], modes, 0f))
        assertNull(p.pick(modes[0], modes, Float.NaN))
    }

    @Test
    fun sixtyAndFiftyNineNineFourNeverMatchEachOther() {
        val modes = listOf(m(60f), m(ntsc60))
        assertEquals(modes[1], p.pick(modes[0], modes, ntsc60))
        assertEquals(modes[0], p.pick(modes[1], modes, 60f))
        assertEquals(modes[1], p.pick(modes[0], modes, 59.940063f), "reported-rate float noise still matches")
    }

    @Test
    fun resolutionNeverChanges() {
        val current = tv4k[2] // 4K 60
        val picked = p.pick(current, all, 25f)
        assertEquals(tv4k[1], picked)
        assertEquals(3840, picked!!.width)
        assertNull(p.pick(current, all, ntsc30), "no 4K 59.94 mode; 1080p 59.94 is not a candidate")
    }

    @ParameterizedTest
    @CsvSource(
        "23.976, 23.976025", "23.9, 23.976025", "24.05, 24.0", "25.4, 25.0", "29.97, 29.97003", "30.2, 30.0",
        "47.95, 47.952045", "48.3, 48.0", "49.1, 50.0", "59.94, 59.94006", "60.5, 60.0", "61.1, 60.0",
    )
    fun snapsToStandardRates(measured: Float, expected: Float) {
        assertEquals(expected, p.snapFps(measured)!!, 0.0001f)
    }

    @ParameterizedTest
    @CsvSource("12.0", "27.5", "40.0", "55.0", "62.0", "0", "-25", "100")
    fun outsideTheToleranceIsNull(measured: Float) {
        assertNull(p.snapFps(measured))
    }
}
