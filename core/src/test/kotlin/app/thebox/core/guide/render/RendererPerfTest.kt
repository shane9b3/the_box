package app.thebox.core.guide.render

import app.thebox.core.guide.fakes.FakeGuideImage
import app.thebox.core.guide.fakes.FakeTextMeasurer
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §4.1, §5.1 "RendererPerfTest": 1,000 reference frames ≤ 0.3 ms each on the JVM. Tag `perf`. */
@Tag("perf")
class RendererPerfTest {
    @Test
    fun thousandFramesUnderBudget() {
        val measurer = FakeTextMeasurer()
        val renderer = GuideRendererImpl(measurer)
        val frame = GuideReferenceFrame.build(measurer, images = StaticGuideImages(FakeGuideImage(160, 90, "logo")))
        val canvas = CountingGuideCanvas()
        val ws = frame.windowStartMin
        repeat(5_000) { canvas.reset(); renderer.draw(canvas, frame, ws, (it % 40) * 0.1f, GuideReferenceFrame.SCROLL_ROW) }
        val t0 = System.nanoTime()
        for (i in 0 until 1_000) { canvas.reset(); renderer.draw(canvas, frame, ws, (i % 40) * 0.1f, GuideReferenceFrame.SCROLL_ROW) }
        val perFrameMs = (System.nanoTime() - t0) / 1_000 / 1e6
        println("guide renderer: %.4f ms/frame over 1,000 frames, text-fit hits %d misses %d".format(perFrameMs, renderer.textFitHits, renderer.textFitMisses))
        assertTrue(perFrameMs <= 0.3, "per-frame %.4f ms".format(perFrameMs))
    }
}
