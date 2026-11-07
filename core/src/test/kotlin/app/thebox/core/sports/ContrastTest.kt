package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/design/m6-goal-animation.md §8.1: WCAG 2.x relative luminance and contrast. */
class ContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun whiteOnBlackIs21() {
        assertEquals(21.0, Contrast.ratio(white, black), 1e-9)
        assertEquals(21.0, Contrast.ratio(black, white), 1e-9, "symmetric: lighter over darker")
    }

    @Test
    fun grey767676OnWhiteIs454() {
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), white), 0.005)
    }

    @Test
    fun luminanceEndsAndAlphaIgnored() {
        assertEquals(1.0, Contrast.luminance(white), 1e-9)
        assertEquals(0.0, Contrast.luminance(black), 1e-9)
        assertEquals(Contrast.luminance(0xFF336699.toInt()), Contrast.luminance(0x00336699), 0.0)
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0x80336699.toInt()), 1e-9)
        // Pure primaries carry the Rec. 709 weights.
        assertEquals(0.2126, Contrast.luminance(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, Contrast.luminance(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, Contrast.luminance(0xFF0000FF.toInt()), 1e-9)
    }
}
