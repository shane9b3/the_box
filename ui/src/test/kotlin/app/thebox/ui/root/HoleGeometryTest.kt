package app.thebox.ui.root

import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/m3-guide.md §3.1 (hole row), §3.13.2. */
class HoleGeometryTest {
    @Test
    fun designHoleTable() {
        assertEquals(RectPx(64, 56, 784, 461), HoleGeometry.designHole(1920, 1080))
        assertEquals(RectPx(43, 37, 523, 307), HoleGeometry.designHole(1280, 720))
        assertEquals(RectPx(128, 112, 1568, 922), HoleGeometry.designHole(3840, 2160))
        // Non-16:9 window: the canvas fits inside (min of both scales), anchored at the origin.
        assertEquals(HoleGeometry.designHole(1920, 1080), HoleGeometry.designHole(2400, 1080))
    }

    @Test
    fun miniWith16x9VideoFillsTheHole() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        val r = g.rects(HoleMode.MINI, 1920, 1080, 1f, AspectMode.FIT)
        assertEquals(RectPx(64, 56, 784, 461), r.hole)
        assertEquals(RectPx(64, 56, 784, 461), r.video)
    }

    @Test
    fun miniWith4x3VideoIsPillarboxedAndCentred() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        val r = g.rects(HoleMode.MINI, 720, 576, 16f / 15f, AspectMode.FIT) // PAL 4:3
        val v = r.video!!
        assertEquals(405, v.height)
        assertEquals(540, v.width)
        assertEquals(64 + (720 - 540) / 2, v.left)
        assertEquals(56, v.top)
    }

    @Test
    fun unknownVideoSizeUsesTheWholeHole() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        assertEquals(g.miniHole, g.rects(HoleMode.MINI, 0, 0, 1f, AspectMode.FIT).video)
    }

    @Test
    fun fullIsTheWholeWindow() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        val r = g.rects(HoleMode.FULL, 1920, 1080, 1f, AspectMode.FIT)
        assertEquals(RectPx(0, 0, 1920, 1080), r.hole)
        assertEquals(RectPx(0, 0, 1920, 1080), r.video)
        val sd = g.rects(HoleMode.FULL, 720, 576, 16f / 15f, AspectMode.FIT).video!!
        assertEquals(1080, sd.height)
        assertEquals(1440, sd.width)
        assertEquals(240, sd.left)
    }

    @Test
    fun stillAndCoveredParkTheSurface() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        val still = g.rects(HoleMode.STILL, 1920, 1080, 1f, AspectMode.FIT)
        assertEquals(RectPx(64, 56, 784, 461), still.hole)
        assertNull("viewport HIDDEN: VideoLayer parks at 1×1", still.video)
        val covered = g.rects(HoleMode.COVERED, 1920, 1080, 1f, AspectMode.FIT)
        assertNull(covered.hole)
        assertNull(covered.video)
    }

    @Test
    fun measuredRectWinsAndIsDeduped() {
        val g = HoleGeometry().apply { update(1920, 1080) }
        val measured = RectPx(64, 57, 784, 462)
        assertTrue(g.publishMeasured(measured))
        assertFalse("same rect again is not a change", g.publishMeasured(measured))
        assertFalse("empty rects are ignored", g.publishMeasured(RectPx(0, 0, 0, 0)))
        assertEquals(measured, g.miniHole)
        assertEquals(measured, g.rects(HoleMode.MINI, 1920, 1080, 1f, AspectMode.FIT).video)
        g.update(1280, 720)
        assertNull("a new window size drops the old layout rect", g.measuredHole)
        assertEquals(RectPx(43, 37, 523, 307), g.miniHole)
    }

    @Test
    fun layoutRectsRoundToWholePixels() {
        assertEquals(RectPx(43, 37, 523, 307), HoleGeometry.round(42.67f, 37.33f, 522.67f, 307.33f))
    }

    @Test
    fun viewportFollowsTheHoleMode() {
        assertEquals(app.thebox.core.playback.Viewport.MINI, viewportFor(HoleMode.MINI))
        assertEquals(app.thebox.core.playback.Viewport.FULL, viewportFor(HoleMode.FULL))
        assertEquals("still disables the video track", app.thebox.core.playback.Viewport.HIDDEN, viewportFor(HoleMode.STILL))
        assertEquals(app.thebox.core.playback.Viewport.HIDDEN, viewportFor(HoleMode.COVERED))
    }
}
