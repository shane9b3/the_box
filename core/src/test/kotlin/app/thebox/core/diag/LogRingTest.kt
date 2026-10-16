package app.thebox.core.diag

import app.thebox.core.common.BoxLog
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.11.3 (incl. S13: the throwable is redacted once for every sink). */
class LogRingTest {
    private val savedSink = BoxLog.sink
    private val savedLevel = BoxLog.minLevel

    @AfterTest
    fun restore() {
        BoxLog.sink = savedSink
        BoxLog.minLevel = savedLevel
    }

    @Test
    fun ringIsBoundedAndKeepsTheNewest() {
        val ring = LogRingBuffer(capacity = 5, maxChars = 512)
        (1..12).forEach { ring.add("line $it") }
        assertEquals((8..12).map { "line $it" }, ring.snapshot())
    }

    @Test
    fun linesAreCutTo512Chars() {
        val ring = LogRingBuffer()
        ring.add("x".repeat(2_000))
        val l = ring.snapshot().single()
        assertEquals(LogLimits.LOG_LINE_MAX_CHARS, l.length)
        assertTrue(l.endsWith("…"))
    }

    @Test
    fun defaultCapacityIsFourThousand() {
        val ring = LogRingBuffer()
        repeat(4_100) { ring.add("l$it") }
        val s = ring.snapshot()
        assertEquals(4_000, s.size)
        assertEquals("l100", s.first())
        assertEquals("l4099", s.last())
    }

    @Test
    fun throwableWithAnXtreamPathIsRedactedForEverySink() {
        // S13: OkHttp exceptions carry full URLs; BoxLog passes the throwable unredacted.
        val ring = LogRingBuffer()
        val file = ArrayList<String>()
        var platformText: String? = null
        var platformError: Throwable? = Throwable("sentinel")
        val sink = CompositeLogSink(
            platform = BoxLog.Sink { _, _, m, e -> platformText = m; platformError = e },
            ring = ring, file = { file += it }, clock = { 0L },
        )
        BoxLog.sink = sink
        BoxLog.minLevel = BoxLog.DEBUG
        BoxLog.e("Fetch", "open failed for http://h:8080/live/u1/p1/1.ts", IOException("unexpected end of stream on http://h:8080/live/u1/p1/1.ts?token=abc"))
        for (text in listOf(platformText!!, ring.snapshot().single(), file.single())) {
            assertFalse(text.contains("/u1/"), text)
            assertFalse(text.contains("p1/"), text)
            assertFalse(text.contains("token=abc"), text)
            assertTrue(text.contains("/live/***/***/"), text)
            assertTrue(text.contains("IOException"), text)
        }
        assertNull(platformError, "the platform sink gets the formatted text, never the raw throwable")
        assertTrue(ring.snapshot().single().contains(" E/Fetch: "))
    }

    @Test
    fun formatHasTimeLevelAndTag() {
        val line = CompositeLogSink.format(0L, BoxLog.WARN, "Tag", "hello")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} W/Tag: hello""").matches(line), line)
    }

    @Test
    fun messageWithoutThrowableIsPassedThrough() {
        val ring = LogRingBuffer()
        val sink = CompositeLogSink(null, ring, null, clock = { 0L })
        sink.log(BoxLog.INFO, "T", "plain", null)
        assertTrue(ring.snapshot().single().endsWith("I/T: plain"))
    }
}
