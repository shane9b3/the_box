package app.thebox.core.guide.render

import app.thebox.core.guide.CellFlags
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideColors as C
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.GuideFixtures.Cell
import app.thebox.core.guide.fakes.RecordingGuideCanvas
import app.thebox.core.guide.fakes.RecordingGuideCanvas.Op
import app.thebox.core.guide.layout.GuideMetricsImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Event channels, docs/design/m6-event-channels.md §6.4 (arch S6): the renderer's one change. A past cell read from a
 * channel name ([CellFlags.EVENT_NAME]) on an archive channel gets no catch-up mark (no ↺, the past colour, no
 * "catch-up available" in the focus description); an ordinary past programme on the same row keeps it.
 */
class GuideRendererEventTest {
    private val now = GuideFixtures.NOW_MIN
    private val h19 = GuideFixtures.floor30(now) - 30
    private fun at(h: Int, m: Int = 0) = h19 + (h - 19) * 60 + m
    private val renderer = GuideRendererImpl(FakeTextMeasurer())
    private val canvas = RecordingGuideCanvas()

    private fun frame(row: List<Cell>, focus: Cell? = null): GuideFrame {
        val f = GuideFrame()
        f.metrics = GuideMetricsImpl(1f)
        f.rows = FakeGuideRows(listOf(GuideFixtures.channel(0, archiveDays = 7)))
        f.data = GuideFixtures.windowData(listOf(row), fromMin = at(15), toMin = at(23))
        f.labels = GuideReferenceFrame.labels()
        f.nowMin = now
        f.windowStartMin = h19
        f.showLogos = false
        if (focus != null) f.focus = GridFocus(0, focus.start, focus.end, focus.kind, focus.start, h19, 0)
        return f
    }

    private fun draw(f: GuideFrame): List<Op> {
        canvas.clear()
        renderer.draw(canvas, f, f.windowStartMin, 0f, 0f)
        return canvas.ops.toList()
    }

    @Test
    fun aPastEventCellHasNoCatchUpMark() {
        // On screen from 19:00, now 19:47: both past cells are visible.
        val event = Cell(at(19, 20), at(19, 45), title = "Ev", flags = CellFlags.EVENT_NAME)
        val row = listOf(Cell(at(15), at(19, 0)), Cell(at(19, 0), at(19, 20), title = "Ab"), event, Cell(at(19, 45), at(23), title = "Next"))
        val ops = draw(frame(row))
        val texts = ops.filterIsInstance<Op.Text>()
        assertEquals(C.TEXT_PAST, texts.first { it.text == "Ev" }.argb)
        assertEquals(C.TEXT_ARCHIVE, texts.first { it.text == "Ab" }.argb, "an ordinary past programme keeps it")
        val glyphs = ops.filterIsInstance<Op.Asset>().filter { it.asset == GuideAsset.ARCHIVE_GLYPH && it.argb == C.TEXT_ARCHIVE }
        assertEquals(1, glyphs.size, "one ↺: the ordinary programme's")
    }

    @Test
    fun theFocusDescriptionDoesNotOfferCatchUpOnAnEventCell() {
        val event = Cell(at(19, 20), at(19, 45), title = "Ev", flags = CellFlags.EVENT_NAME)
        val plain = Cell(at(19, 0), at(19, 20), title = "Ab")
        val row = listOf(Cell(at(15), at(19, 0)), plain, event, Cell(at(19, 45), at(23), title = "Next"))
        assertFalse(renderer.describeFocus(frame(row, focus = event)).contains("catch-up available"))
        assertTrue(renderer.describeFocus(frame(row, focus = plain)).contains("catch-up available"))
    }
}
