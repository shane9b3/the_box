package app.thebox.core.playback.surface

import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.VideoPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoRectCalculatorTest {
    private val c = VideoRectCalculatorImpl()
    private val screen = RectPx(0, 0, 1920, 1080)

    @Test
    fun fitSixteenNineFillsAFullHdScreen() {
        assertEquals(VideoPlacement(screen, false), c.place(screen, 1280, 720, 1f, AspectMode.FIT))
    }

    @Test
    fun fitPillarboxesFourThree() {
        assertEquals(RectPx(240, 0, 1680, 1080), c.place(screen, 640, 480, 1f, AspectMode.FIT).videoRect)
    }

    @Test
    fun fitLetterboxesScope() {
        assertEquals(RectPx(0, 131, 1920, 949), c.place(screen, 1920, 818, 1f, AspectMode.FIT).videoRect)
    }

    @Test
    fun anamorphicPixelRatioWidensThePicture() {
        // 720×576 PAL with 64:45 pixels displays as 16:9.
        assertEquals(screen, c.place(screen, 720, 576, 64f / 45f, AspectMode.FIT).videoRect)
        // Without the ratio it would be 5:4.
        assertEquals(RectPx(285, 0, 1635, 1080), c.place(screen, 720, 576, 1f, AspectMode.FIT).videoRect)
        assertEquals(RectPx(285, 0, 1635, 1080), c.place(screen, 720, 576, 0f, AspectMode.FIT).videoRect, "invalid ratio = square pixels")
    }

    @Test
    fun forcedRatiosIgnoreTheVideo() {
        assertEquals(RectPx(240, 0, 1680, 1080), c.place(screen, 1920, 1080, 1f, AspectMode.RATIO_4_3).videoRect)
        val hole = RectPx(100, 100, 900, 700) // 4:3 hole
        assertEquals(RectPx(100, 175, 900, 625), c.place(hole, 720, 576, 1f, AspectMode.RATIO_16_9).videoRect)
        assertEquals(RectPx(100, 175, 900, 625), c.place(hole, 0, 0, 1f, AspectMode.RATIO_16_9).videoRect)
    }

    @Test
    fun fillAndZoomCropTheWholeHole() {
        val hole = RectPx(10, 20, 410, 320)
        assertEquals(VideoPlacement(hole, true), c.place(hole, 640, 480, 1f, AspectMode.FILL))
        assertEquals(VideoPlacement(hole, true), c.place(hole, 1920, 1080, 1f, AspectMode.ZOOM))
    }

    @Test
    fun oddPixelsRoundInsideTheHole() {
        val hole = RectPx(101, 51, 101 + 333, 51 + 101) // odd sizes and offsets
        for (mode in listOf(AspectMode.FIT, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3)) {
            for ((w, h) in listOf(1920 to 1080, 640 to 480, 1000 to 1001, 1 to 1000, 1000 to 1)) {
                val r = c.place(hole, w, h, 1f, mode).videoRect
                assertTrue(r.left >= hole.left && r.top >= hole.top && r.right <= hole.right && r.bottom <= hole.bottom, "$mode $w×$h → $r")
                assertTrue(r.width >= 1 && r.height >= 1)
                assertTrue(r.width == hole.width || r.height == hole.height, "touches two sides: $r")
                // centred within a pixel
                assertTrue(kotlin.math.abs((r.left - hole.left) - (hole.right - r.right)) <= 1)
                assertTrue(kotlin.math.abs((r.top - hole.top) - (hole.bottom - r.bottom)) <= 1)
            }
        }
    }

    @Test
    fun unknownSizeOrEmptyHoleYieldsTheHole() {
        assertEquals(VideoPlacement(screen, false), c.place(screen, 0, 0, 1f, AspectMode.FIT))
        val empty = RectPx(5, 5, 5, 5)
        assertEquals(VideoPlacement(empty, false), c.place(empty, 1920, 1080, 1f, AspectMode.FIT))
    }
}
