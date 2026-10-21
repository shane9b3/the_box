package app.thebox.core.guide.render

import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.fakes.RecordingGuideCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code review 2026-10-14, O-2: the per-row recording protocol. A canvas that "records" every key it was asked for and
 * replays a known one: a vertical scroll frame (only the rows' positions change) replays every row; a focus move
 * re-records only the two rows whose focus state changed; anything a row's ops depend on changes its key.
 */
class RowRecordingTest {
    private class Replaying(private val inner: RecordingGuideCanvas = RecordingGuideCanvas()) : GuideCanvas by inner {
        val known = HashSet<Long>()
        var replayed = 0
        var recorded = 0
        var open = 0
        val opsCount get() = inner.ops.size
        override fun beginRow(key: Long, top: Float): Boolean {
            if (key in known) { replayed++; return true }
            known += key; recorded++; open++
            return false
        }
        override fun endRow() { open-- }
    }

    private val renderer = GuideRendererImpl(FakeTextMeasurer())
    private val frame = GuideReferenceFrame.build(FakeTextMeasurer())

    @Test
    fun verticalScrollFramesReplayEveryRow() {
        val c = Replaying()
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val firstRecorded = c.recorded
        assertTrue(firstRecorded >= 12, "two passes over the visible rows recorded ($firstRecorded)")
        assertEquals(0, c.open, "every beginRow that drew is closed")
        c.recorded = 0
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW + 0.25f)
        assertEquals(0, c.recorded, "same content, other positions: nothing re-recorded")
        assertTrue(c.replayed >= firstRecorded - 2)
    }

    @Test
    fun aFocusMoveReRecordsOnlyTheRowsWhoseFocusChanged() {
        val c = Replaying()
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        c.recorded = 0
        val f = frame.focus!!
        frame.focus = f.copy(row = f.row + 1)
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        assertEquals(4, c.recorded, "old and new focus row, two passes each")
    }

    @Test
    fun theLeftCuesChangeNoRowKey() {
        // Decision 2026-10-19: both chevrons are drawn outside every row recording (the edge cue after the content clip,
        // the clip cue inside the never-recorded focused cell), so toggling them re-records nothing.
        val c = Replaying()
        frame.gridFocused = true
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val rowPasses = c.recorded
        c.recorded = 0
        for ((edge, clip) in listOf(true to false, false to true, true to true, false to false)) {
            frame.leftEdgeCue = edge; frame.clipCue = clip
            c.replayed = 0
            renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
            assertEquals(0, c.recorded, "edge=$edge clip=$clip: nothing re-recorded")
            assertEquals(rowPasses, c.replayed, "edge=$edge clip=$clip: every row pass replays")
        }
    }

    @Test
    fun timeAndHorizontalScrollChangeEveryKey() {
        val c = Replaying()
        renderer.draw(c, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val n = c.recorded
        c.recorded = 0
        renderer.draw(c, frame, frame.windowStartMin, 7.5f, GuideReferenceFrame.SCROLL_ROW)
        assertEquals(n, c.recorded, "a horizontal offset moves every cell")
        c.recorded = 0
        frame.nowMin += 1
        renderer.draw(c, frame, frame.windowStartMin, 7.5f, GuideReferenceFrame.SCROLL_ROW)
        assertEquals(n, c.recorded, "a minute tick changes colours and progress")
    }
}
