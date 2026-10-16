package app.thebox.ui.full

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.ScrubFocus
import app.thebox.core.guide.fakes.GuideFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.management.ManagementFactory
import java.util.TimeZone

/**
 * `ScrubBarRenderer` against a recording canvas (docs/design/m4-player-ux.md §4.1, §4.2, §5.4): ≤ 14 ops in every
 * state, zero allocation per draw (thread allocated bytes over 1,000 draws, as M3's `RendererAllocationTest`), and the
 * label buffers' content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrubBarRendererTest {
    private class RecordingCanvas : Canvas() {
        val ops = ArrayList<String>()
        val texts = ArrayList<String>()
        var record = true
        var count = 0
        private fun op(name: String) { count++; if (record) ops += name }
        override fun drawRect(rect: RectF, paint: Paint) = op("rect")
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) = op("roundRect")
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = op("circle")
        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            op("text")
            if (record) texts += String(text, index, count)
        }
    }

    private val ch = GuideFixtures.channel(1, archiveDays = 7)
    private val now = PlayerOverlayStates.NOW_MS

    private fun renderer() = ScrubBarRenderer(1f).apply {
        zone = TimeZone.getTimeZone("UTC")
        clock24 = true
        setLabels("Catch-up", "Live")
    }

    private fun draw(r: ScrubBarRenderer, s: app.thebox.core.full.ScrubState): RecordingCanvas =
        RecordingCanvas().also { r.draw(it, s, now, 1792f, 80f) }

    @Test
    fun opCountStaysInBudgetInEveryState() {
        val r = renderer()
        val states = listOf(
            PlayerOverlayStates.scrub(ch),
            PlayerOverlayStates.scrub(ch, preview = now - 1_500_000),
            PlayerOverlayStates.scrub(ch, focus = ScrubFocus.CHIPS),
            PlayerOverlayStates.scrub(ch, pill = PillState(PillKind.CATCH_UP)),
            PlayerOverlayStates.scrub(ch).copy(atLive = true, previewMs = now - 30_000),
            PlayerOverlayStates.scrub(ch, pill = PillState(PillKind.PAUSED, behindMs = 60_000, atMs = now, behindGrows = true)).copy(advancing = false),
        )
        for (s in states) {
            val c = draw(r, s)
            assertTrue("≤ 14 ops: ${c.ops}", c.ops.size <= 14)
            assertEquals("three label runs, plus the bubble with a preview", if (s.previewMs != null) 4 else 3, c.ops.count { it == "text" })
        }
        val full = draw(r, PlayerOverlayStates.scrub(ch, preview = now - 1_500_000))
        assertEquals("before-archive, archive, base, played, live mark, playhead, ring, preview, bubble, 4 texts", 13, full.ops.size)
    }

    @Test
    fun labelsAreWrittenFromTheBuffers() {
        val r = renderer()
        val s = PlayerOverlayStates.scrub(ch)
        val c = draw(r, s)
        val from = s.barFromMs
        fun hm(ms: Long) = String.format(java.util.Locale.ROOT, "%02d:%02d", (ms / 3_600_000) % 24, (ms / 60_000) % 60)
        assertEquals(hm(from), c.texts[0])
        assertEquals(hm(s.barToMs), c.texts[1])
        // Behind 17:50 at 1×: the centre reads the playhead clock and −17:50.
        val play = s.playheadMs
        val clock = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", (play / 3_600_000) % 24, (play / 60_000) % 60, (play / 1000) % 60)
        assertEquals("$clock  −17:50", c.texts[2])
        val cu = draw(r, PlayerOverlayStates.scrub(ch, pill = PillState(PillKind.CATCH_UP)))
        assertTrue(cu.texts[2].endsWith("  Catch-up"))
    }

    @Test
    fun zeroAllocationPerDraw() {
        val r = renderer()
        val s = PlayerOverlayStates.scrub(ch, preview = now - 1_500_000)
        val c = RecordingCanvas().apply { record = false }
        repeat(2_000) { r.draw(c, s, now + it, 1792f, 80f) } // warm up (JIT, class init)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        // U-L2 (L-5): a per-draw bound, best of three batches. Any object allocated per draw is ≥ 16 B per draw; the
        // 2,088 B seen once over 1,000 draws (~2 B per draw) was JIT/OSR noise of a shared JVM, not the renderer.
        val perDraw = (0 until 3).minOf { _ ->
            val before = bean.getThreadAllocatedBytes(id)
            repeat(1_000) { r.draw(c, s, now + it * 1_000L, 1792f, 80f) }
            (bean.getThreadAllocatedBytes(id) - before) / 1_000.0
        }
        assertEquals("every draw ran its 13 ops", 5_000 * 13, c.count)
        assertTrue("allocated $perDraw bytes per draw (best of three batches of 1,000)", perDraw < 16.0)
    }

    @Test
    fun timeCharsBehindAndClock() {
        val buf = CharArray(16)
        assertEquals("−00:05", String(buf, 0, TimeChars.behind(buf, 0, 5_000)))
        assertEquals("−1:00:00", String(buf, 0, TimeChars.behind(buf, 0, 3_600_000)))
        assertEquals("−00:00", String(buf, 0, TimeChars.behind(buf, 0, -5)))
        assertEquals("19:42:10", String(buf, 0, TimeChars.clock(buf, 0, (19 * 3600 + 42 * 60 + 10) * 1000L, 0, true, true)))
        assertEquals("7:42", String(buf, 0, TimeChars.clock(buf, 0, (19 * 3600 + 42 * 60) * 1000L, 0, false, false)))
        assertEquals("12:05", String(buf, 0, TimeChars.clock(buf, 0, 5 * 60_000L, 0, false, false)))
        assertEquals("01:05", String(buf, 0, TimeChars.clock(buf, 0, 5 * 60_000L, 3_600_000, true, false)))
    }
}
