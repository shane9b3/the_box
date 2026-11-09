package app.thebox.core.guide.layout

import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.GuideTextStyle
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
        // Decision 2026-11-09 (bigger grid, no footer): panel 500 → 1024, ruler 57, rows 85 at pitch 93, label 280.
        assertEquals(64f, m.panelLeft); assertEquals(500f, m.panelTop); assertEquals(1856f, m.panelRight); assertEquals(1024f, m.panelBottom)
        assertEquals(81f, m.labelLeft); assertEquals(280f, m.labelWidth)
        assertEquals(361f, m.timelineLeft); assertEquals(1478f, m.timelineWidth)
        assertEquals(501f, m.headerTop); assertEquals(558f, m.headerBottom)
        assertEquals(558f, m.rowsTop); assertEquals(93f, m.rowPitch); assertEquals(85f, m.rowHeight)
        assertEquals(1023f, m.rowsBottom)
        near(8.2111f, m.pxPerMin, 1e-3f)
        near(246.33f, 30 * m.pxPerMin, 0.01f, "30-min tick")
    }

    @Test
    fun table720pAnd4k() {
        val m720 = factory.create(1280, 720)
        near(0.6667f, m720.scale, 1e-3f)
        assertEquals((64 * m720.scale).roundToInt().toFloat(), m720.panelLeft)
        assertEquals((361 * m720.scale).roundToInt().toFloat(), m720.timelineLeft)
        assertEquals((1839 * m720.scale).roundToInt().toFloat(), m720.timelineLeft + m720.timelineWidth)
        assertEquals((85 * m720.scale).roundToInt().toFloat(), m720.rowHeight)
        assertEquals((1024 * m720.scale).roundToInt().toFloat(), m720.panelBottom)
        near(m720.timelineWidth / 180f, m720.pxPerMin, 1e-4f)

        val m4k = factory.create(3840, 2160)
        assertEquals(2f, m4k.scale)
        assertEquals(722f, m4k.timelineLeft); assertEquals(2956f, m4k.timelineWidth)
        assertEquals(1116f, m4k.rowsTop); assertEquals(170f, m4k.rowHeight); assertEquals(186f, m4k.rowPitch)
        assertEquals(2048f, m4k.panelBottom)
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
            for (i in 0..5) {
                val top = m.rowTopPx(i.toFloat())
                assertEquals(top, top.roundToInt().toFloat(), "row $i at s=$s is pixel-snapped")
                val ideal = (558 + 93 * i) * s
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
        near(361f + 47 * 1478f / 180f, m.xOf(now, ws, 0f), 0.05f, "now line") // 746.92
        // Epoch minutes stay Int: odd minutes are exact (a Float epoch minute has 2-minute spacing).
        near(m.pxPerMin, m.xOf(ws + 1, ws, 0f) - m.xOf(ws, ws, 0f), 1e-3f)
    }

    @Test
    fun scrollOffsetHalfMinuteShiftsBy411() {
        val m = factory.create(1920, 1080)
        val ws = GuideFixtures.floor30(GuideFixtures.NOW_MIN)
        near(4.1056f, m.xOf(ws, ws, 0f) - m.xOf(ws, ws, 0.5f), 0.01f)
        near(G.TIMELINE_W, m.xOf(ws + 180, ws, 0f) - m.xOf(ws, ws, 0f), 0.01f)
    }

    /**
     * Decision 2026-11-09: the footer hint row is gone and the grid panel reaches the bottom safe margin, the same 56
     * design px the layout keeps at the top; five whole rows plus the ruler fit inside it.
     */
    @Test
    fun gridReachesTheBottomSafeMargin() {
        assertEquals(G.HOLE_T, G.SAFE_B, "bottom margin = top margin")
        assertEquals(G.CANVAS_H - G.SAFE_B, G.PANEL_T + G.PANEL_H, "panel bottom = 1080 − 56")
        assertEquals(1024f, G.PANEL_T + G.PANEL_H)
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 3840 to 2160)) {
            val m = factory.create(w, h)
            assertEquals(((G.CANVAS_H - G.SAFE_B) * m.scale).roundToInt().toFloat(), m.panelBottom, "$w×$h")
            // Five whole rows fit inside the inner panel bottom.
            val lastBottom = m.rowTopPx((G.VISIBLE_ROWS - 1).toFloat()) + m.rowHeight
            assertTrue(lastBottom <= m.rowsBottom, "$w×$h: row 5 ends at $lastBottom ≤ ${m.rowsBottom}")
            // And no sixth row would: the rows fill the panel rather than leaving a row's worth of space.
            assertTrue(m.rowsBottom - lastBottom < m.rowPitch, "$w×$h: no row-sized gap under row 5")
            // No sliver of a sixth row shows at rest: its top is at or below the rows clip.
            assertTrue(m.rowTopPx(G.VISIBLE_ROWS.toFloat()) >= m.rowsBottom, "$w×$h: row 6 hidden")
        }
        // 1 border + ruler + 5 pitches + 1 border fits the panel.
        assertEquals(G.PANEL_H, 2 * G.PANEL_BORDER + G.HEADER_H + G.VISIBLE_ROWS * G.ROW_PITCH)
        assertEquals(G.ROW_H + G.ROW_GAP, G.ROW_PITCH)
        assertEquals(G.PANEL_W - 2 * (G.PANEL_BORDER + G.PANEL_PAD_X) - G.LABEL_W, G.TIMELINE_W)
        // At least about two hours stay visible (the window is still 180 minutes).
        assertTrue(G.TIMELINE_W / G.PX_PER_MIN >= 120f)
    }

    /**
     * Decision 2026-11-09: every grid type size is about 1.2× its 2026-10-17 size, rounded to a whole design px, and
     * none is under the 18 px floor; the marks grow with it.
     */
    @Test
    fun typeSizesMeetTheBiggerGridTargets() {
        val before = mapOf(
            GuideTextStyle.CELL_TITLE to 30f, GuideTextStyle.CELL_TIME to 20f, GuideTextStyle.RULER to 20f,
            GuideTextStyle.RULER_CONTEXT to 18f, GuideTextStyle.CH_NUMBER to 20f, GuideTextStyle.CH_NAME to 26f,
            GuideTextStyle.NO_INFO to 22f,
        )
        for ((style, old) in before) {
            assertTrue(style.sizePx >= Math.round(old * 1.2f) - 1f, "$style ${style.sizePx} ≥ ≈1.2 × $old")
            assertTrue(style.sizePx >= 18f, "$style above the 18 px floor")
            assertEquals(style.sizePx.roundToInt().toFloat(), style.sizePx, "$style whole design px")
        }
        assertEquals(36f, GuideTextStyle.CELL_TITLE.sizePx)
        assertEquals(31f, GuideTextStyle.CH_NAME.sizePx)
        assertEquals(24f, GuideTextStyle.RULER.sizePx)
        assertEquals(22f, GuideTextStyle.RULER_CONTEXT.sizePx)
        // The title still fits its row with room to spare, and the logo slot keeps the row's proportion (64 / 74).
        assertTrue(GuideTextStyle.CELL_TITLE.sizePx <= G.ROW_H / 2)
        assertTrue(GridConstants.LOGO_H < G.ROW_H)
        assertTrue(abs(GridConstants.LOGO_H / G.ROW_H - 64f / 74f) < 0.01f)
        // Chips (Compose, design px) and marks grew too.
        for (t in floatArrayOf(G.CHIP_GROUP_TEXT, G.CHIP_TEXT, G.CHIP_CARET_TEXT)) assertTrue(t >= 18f)
        assertTrue(G.CHIP_GROUP_TEXT >= 29f && G.CHIP_TEXT >= 26f)
        assertTrue(G.CHIP_H < G.HEADER_H, "chips sit inside the ruler row")
        assertEquals(G.PANEL_T + G.PANEL_BORDER + (G.HEADER_H - G.CHIP_H) / 2, G.CHIP_T)
        assertTrue(G.LABEL_GLYPH >= 24f && G.CELL_GLYPH >= 14f && G.REMINDER_DOT >= 10f && G.NOW_DOT >= 12f)
    }
}
