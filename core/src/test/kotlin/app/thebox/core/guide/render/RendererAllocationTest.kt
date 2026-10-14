package app.thebox.core.guide.render

import app.thebox.core.guide.fakes.FakeGuideImage
import app.thebox.core.guide.fakes.FakeGuideRows
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.layout.TimeTables
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/design/m3-guide.md §4.1, §4.2, §5.1 "RendererAllocationTest".
 *
 * What is asserted:
 * - **Bytes:** `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` grows by < 1 KB over 1,000 draws of the
 *   §4.1 reference frame (after a warm-up, so class init and the one-time number strings are excluded), with
 *   `focusedCellBounds` on every frame and the scroll offset/row animating. This counts every heap allocation on
 *   the drawing thread, including ones escape analysis would not remove in the interpreter.
 * - **Structure:** every `String` passed to `drawText` is an instance that existed before the frame (snapshot
 *   titles and time texts, `TimeTables` entries, channel names, renderer constants, the cached number strings),
 *   checked by identity across two frames; every range is inside its string.
 *
 * What is not: allocations inside the Android adapter (`AndroidGuideCanvas`/`Paint`, checked by review and the
 * Robolectric tests only) and the `TextFit` miss path for complex strings (`BreakIterator`, allocates by design,
 * once per cached entry).
 */
class RendererAllocationTest {
    private val measurer = FakeTextMeasurer()

    private fun referenceFrame() = GuideReferenceFrame.build(measurer, images = StaticGuideImages(FakeGuideImage(160, 90, "logo")))

    @Test
    fun referenceFrameAllocatesNothing() {
        val renderer = GuideRendererImpl(measurer)
        val frame = referenceFrame()
        val canvas = CountingGuideCanvas()
        val out = FloatArray(4)
        val ws = frame.windowStartMin
        fun frameAt(i: Int) {
            canvas.reset()
            val off = (i % 40) * 0.1f - 2f
            val row = GuideReferenceFrame.SCROLL_ROW + (i % 10) * 0.05f
            renderer.draw(canvas, frame, ws, off, row)
            renderer.focusedCellBounds(frame, ws, off, row, out)
        }
        repeat(3_000) { frameAt(it) }   // warm-up: JIT, number strings, text-fit entries
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 1_000) frameAt(i)
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1024, "1,000 frames allocated ${after - before} bytes")
    }

    @Test
    fun everyDrawnStringPreExists() {
        val renderer = GuideRendererImpl(measurer)
        val frame = referenceFrame()
        val data = frame.data!!
        val labels = frame.labels as TimeTables
        val known = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
        data.cellTitle.filterNotNull().forEach { known += it }
        data.cellTimeText.filterNotNull().forEach { known += it }
        TimeTables.HM24.forEach { known += it }
        (frame.rows as FakeGuideRows).rows.filterNotNull().forEach { known += it.displayName }
        known += GuideRendererImpl.ELLIPSIS; known += GuideRendererImpl.NO_INFO; known += GuideRendererImpl.TIME_DASH
        known += frame.contextLabel
        assertTrue(labels.clock24)

        val canvas = CountingGuideCanvas()
        canvas.recordTexts = true
        renderer.draw(canvas, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val first = canvas.textRefs.copyOf(canvas.texts)
        // Number strings are built once per distinct number; the second frame must reuse them.
        first.filterNotNull().filter { it !in known }.forEach { assertTrue(it.all(Char::isDigit), "unexpected string '$it'"); known += it }
        canvas.reset()
        renderer.draw(canvas, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        for (i in 0 until canvas.texts) assertTrue(canvas.textRefs[i] in known, "string '${canvas.textRefs[i]}' built during the frame")
    }

    @Test
    fun referenceFrameOpCountWithinBudget() {
        val renderer = GuideRendererImpl(measurer)
        val canvas = CountingGuideCanvas()
        val frame = referenceFrame()
        renderer.draw(canvas, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val withLogos = canvas.ops
        assertEquals(canvas.depth, 0)
        val noLogos = GuideReferenceFrame.build(measurer)
        canvas.reset()
        renderer.draw(canvas, noLogos, noLogos.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        println("guide reference frame: $withLogos ops with logos, ${canvas.ops} with names, ${canvas.texts} texts, ${canvas.saves} saves")
        assertTrue(withLogos <= 387, "§4.1 budget: $withLogos ops")
        assertTrue(canvas.ops <= 387, "§4.1 budget: ${canvas.ops} ops")
    }
}
