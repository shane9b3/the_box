package app.thebox.core.refresh

import app.thebox.core.m3u.M3uParseSummary
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParseWarning
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.WarningCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshReportTest {
    @Test fun `JSON round trip`() {
        val lib = FakeLibrary()
        lib.refresh("refresh/basic_v1.m3u")
        val r = lib.refresh("refresh/basic_v2.m3u")
        val json = Json.encodeToString(RefreshReport.serializer(), r)
        assertEquals(r, Json.decodeFromString(RefreshReport.serializer(), json))
    }

    @Test fun `duplicate warnings are capped at 50`() {
        val entries = (0 until 120).map { ParsedChannel(kind = EntryKind.LIVE, name = "Same", url = "http://h/index.m3u8", position = it) }
        val r = FakeLibrary().refreshProducer(FakeLibrary.listProducer(entries))
        assertEquals(119, r.duplicateKeys)
        assertEquals(50, r.warnings.size)
        assertEquals("DUPLICATE_KEY@1", r.warnings.first())
        assertEquals(10, r.addedSample.size)
    }

    @Test fun `withParse adds charset and parse warnings first, still capped`() {
        val base = RefreshReport(1, 1, 0, 1, RefreshOutcome.SUCCESS, warnings = List(45) { "DUPLICATE_KEY@$it" })
        val summary = M3uParseSummary(10, 10, 0, 0, 0, 0, 7, "windows-1252", true)
        val collector = RefreshReports.WarningCollector()
        repeat(7) { collector.add(ParseWarning(it + 1L, WarningCode.LINE_TOO_LONG)) }
        val merged = RefreshReports.withParse(base, summary, collector.warnings)
        assertEquals("windows-1252", merged.charset)
        assertEquals(50, merged.warnings.size)
        assertEquals("LINE_TOO_LONG@1", merged.warnings.first())
        assertEquals(7, collector.total)
    }
}
