package app.thebox.core.guide.layout

import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.fakes.GuideFixtures
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.1 and §5.1 "GuideMetricsTest". */
class GuideMetricsTest {
    private val factory = GuideMetricsFactoryImpl()

    private fun near(expected: Float, actual: Float, eps: Float = 0.01f, msg: String = "") =
        assertTrue(abs(expected - actual) <= eps, "$msg expected $expected, was $actual")

    @Test
    fun table1080p() {
        val m = factory.create(1920, 1080)
        assertEquals(1f, m.scale)
        assertEquals(64f, m.panelLeft); assertEquals(500f, m.panelTop); assertEquals(1856f, m.panelRight); assertEquals(960f, m.panelBottom)
        assertEquals(81f, m.labelLeft); assertEquals(200f, m.labelWidth)
        assertEquals(281f, m.timelineLeft); assertEquals(1558f, m.timelineWidth)
        assertEquals(501f, m.headerTop); assertEquals(549f, m.headerBottom)
        assertEquals(549f, m.rowsTop); assertEquals(68f, m.rowPitch); assertEquals(60f, m.rowHeight)
        assertEquals(959f, m.rowsBottom)
        near(8.6556f, m.pxPerMin, 1e-3f)
        near(259.67f, 30 * m.pxPerMin, 0.01f, "30-min tick")
    }

    @Test
    fun table720pAnd4k() {
        val m720 = factory.create(1280, 720)
        near(0.6667f, m720.scale, 1e-3f)
        assertEquals((64 * m720.scale).roundToInt().toFloat(), m720.panelLeft)
        assertEquals((281 * m720.scale).roundToInt().toFloat(), m720.timelineLeft)
        assertEquals((1839 * m720.scale).roundToInt().toFloat(), m720.timelineLeft + m720.timelineWidth)
        assertEquals((60 * m720.scale).roundToInt().toFloat(), m720.rowHeight)
        near(m720.timelineWidth / 180f, m720.pxPerMin, 1e-4f)

        val m4k = factory.create(3840, 2160)
        assertEquals(2f, m4k.scale)
        assertEquals(562f, m4k.timelineLeft); assertEquals(3116f, m4k.timelineWidth)
        assertEquals(1098f, m4k.rowsTop); assertEquals(120f, m4k.rowHeight); assertEquals(136f, m4k.rowPitch)
    }

    @Test
    fun nonSixteenByNineFitsInside() {
        // Ultrawide: height binds (BoxDesignScale rule).
        assertEquals(1f, factory.create(2560, 1080).scale)
        assertEquals(0.5f, factory.create(960, 1080).scale)
    }

    @Test
    fun perRowRoundingNeverDrifts() {
        for (s in floatArrayOf(1f, 2f / 3f, 2f, 0.75f, 1.5f, 1.3333f)) {
            val m = GuideMetricsImpl(s)
            for (i in 0..6) {
                val top = m.rowTopPx(i.toFloat())
                assertEquals(top, top.roundToInt().toFloat(), "row $i at s=$s is pixel-snapped")
                val ideal = (549 + 68 * i) * s
                assertTrue(abs(top - ideal) <= 1.0f, "row $i at s=$s: $top vs $ideal")
                // Bottom of row i never overlaps the next row's top.
                assertTrue(top + m.rowHeight <= m.rowTopPx(i + 1f), "row $i bottom")
            }
        }
    }

    @Test
    fun holeRects() {
        val out = IntArray(4)
        factory.create(1920, 1080).holeRect(out)
        assertContentEquals(intArrayOf(64, 56, 784, 461), out)
        factory.create(1280, 720).holeRect(out)
        assertContentEquals(intArrayOf(43, 37, 523, 307), out)
        factory.create(3840, 2160).holeRect(out)
        assertContentEquals(intArrayOf(128, 112, 1568, 922), out)
    }

    @Test
    fun prototypeNowLineAt1947() {
        val m = factory.create(1920, 1080)
        val now = GuideFixtures.NOW_MIN // 19:47
        val ws = GuideFixtures.floor30(now) - 30 // 19:00
        near(687.8f, m.xOf(now, ws, 0f), 0.05f, "now line")
        // Epoch minutes stay Int: odd minutes are exact (a Float epoch minute has 2-minute spacing).
        near(m.pxPerMin, m.xOf(ws + 1, ws, 0f) - m.xOf(ws, ws, 0f), 1e-3f)
    }

    @Test
    fun scrollOffsetHalfMinuteShiftsBy433() {
        val m = factory.create(1920, 1080)
        val ws = GuideFixtures.floor30(GuideFixtures.NOW_MIN)
        near(4.33f, m.xOf(ws, ws, 0f) - m.xOf(ws, ws, 0.5f), 0.01f)
        near(G.TIMELINE_W, m.xOf(ws + 180, ws, 0f) - m.xOf(ws, ws, 0f), 0.01f)
    }
}
