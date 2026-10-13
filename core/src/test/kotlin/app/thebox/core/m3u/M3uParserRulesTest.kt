package app.thebox.core.m3u

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.source.EntryKind
import app.thebox.core.source.WarningCode
import okio.Buffer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inline cases for every rule of docs/design/m1-data-layer.md §3.4 (M1–M30). */
class M3uParserRulesTest {
    private fun one(extinf: String, url: String = "http://h/x.ts", header: String = "#EXTM3U", options: M3uParseOptions = M3uParseOptions()) =
        M3u.parseText("$header\n$extinf\n$url\n", options).entries.single()

    // M1
    @Test fun `M1 UTF-32 BOM is binary`() {
        val e = assertFailsWith<app.thebox.core.common.SourceException> {
            M3u.parseBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + "#EXTM3U".toByteArray(Charsets.UTF_32LE))
        }
        assertEquals(SourceError.NotASource(NotASourceKind.BINARY), e.error)
    }

    @Test fun `M1 UTF-8 BOM is stripped before the header`() {
        val r = M3u.parseBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\n#EXTINF:-1,A\nhttp://h/a\n".toByteArray())
        assertTrue(r.summary.hadHeader)
        assertEquals("A", r.entries.single().name)
    }

    // M2
    @ParameterizedTest
    @CsvSource(
        "'<tv generator-info-name=\"x\">', WRONG_FORMAT",
        "'<?xml version=\"1.0\"?> <!DOCTYPE tv SYSTEM \"xmltv.dtd\"> <tv>', WRONG_FORMAT",
        "'   <html><body>expired</body></html>', HTML_PAGE",
        "'<?xml version=\"1.0\"?><html><p>tv</p></html>', HTML_PAGE",
        "'{\"user_info\":{}}', WRONG_FORMAT",
        "'[]', WRONG_FORMAT",
    )
    fun `M2 not a playlist`(text: String, kind: NotASourceKind) {
        assertEquals(SourceError.NotASource(kind), M3u.failure(text).error)
    }

    @Test fun `M2 NUL in the first KB is binary, later NULs are not inspected`() {
        assertEquals(SourceError.NotASource(NotASourceKind.BINARY), M3u.failure("#EXTM3U\n\u0000\n").error)
        val late = "#EXTM3U\n#EXTINF:-1,A\nhttp://h/a\n" + "#".repeat(2000) + "\n"
        assertEquals(1, M3u.parseText(late).entries.size)
    }

    // M3
    @Test fun `M3 missing header still calls onHeader once before the first entry`() {
        val r = M3u.parseText("#EXTINF:-1,A\nhttp://h/a\n")
        assertEquals(false, r.summary.hadHeader)
        assertEquals(1, r.sink.headerCalls)
        assertTrue(r.sink.headerBeforeFirstEntry)
    }

    @Test fun `header-only playlist parses to zero entries (the differ reports EMPTY)`() {
        val r = M3u.parseText("#EXTM3U x-tvg-url=\"http://e/x.xml\"\n")
        assertEquals(0, r.summary.entries)
        assertEquals(1, r.sink.headerCalls)
        assertEquals(listOf("http://e/x.xml"), r.header.epgUrls)
    }

    // M4
    @Test fun `M4 NBSP around names and URLs is trimmed`() {
        val e = M3u.parseText("#EXTM3U\n#EXTINF:-1,\u00A0Name\u00A0\n\u00A0http://h/a.ts\u00A0\n").entries.single()
        assertEquals("Name", e.name)
        assertEquals("http://h/a.ts", e.url)
    }

    // M5
    @Test fun `M5 over-long EXTINF drops the pending entry and its URL`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-logo=\"" + "x".repeat(2000) + "\",Big\nhttp://h/big.ts\n#EXTINF:-1,Small\nhttp://h/small.ts\n"
        val r = M3u.parseText(text, M3uParseOptions(maxLineBytes = 1024))
        assertEquals(listOf("Small"), r.entries.map { it.name })
        assertEquals(listOf(WarningCode.LINE_TOO_LONG), r.sink.codes())
        assertEquals(2, r.summary.skippedLines)
    }

    @Test fun `M5 over-long URL drops its entry`() {
        val text = "#EXTM3U\n#EXTINF:-1,Big\nhttp://h/" + "a".repeat(2000) + "\n#EXTINF:-1,Small\nhttp://h/small.ts\n"
        val r = M3u.parseText(text, M3uParseOptions(maxLineBytes = 1024))
        assertEquals(listOf("Small"), r.entries.map { it.name })
    }

    // M7
    @Test fun `M7 directive is case-insensitive, duration over zero becomes durationSec`() {
        val r = M3u.parseText("#extm3u\n#extinf:5400 tvg-type=\"movie\",Film\nhttp://h/f.mp4\n")
        assertEquals(5400L, r.entries.single().durationSec)
        assertTrue(r.summary.hadHeader)
        assertNull(one("#EXTINF:-1,Live").durationSec)
        assertNull(one("#EXTINF:0,Zero").durationSec)
    }

    @Test fun `M7 name fallback - tvg-name, then decoded last URL segment without extension`() {
        assertEquals("From Tvg", one("#EXTINF:-1 tvg-name=\"From Tvg\",").name)
        assertEquals("My Stream", one("#EXTINF:-1,", url = "http://h/dir/My%20Stream.m3u8?x=1").name)
        assertEquals("chan", one("#EXTINF:-1 tvg-id=\"x\"", url = "http://h/chan/").name)
    }

    // M8 is covered by ExtinfLexerTest; one end-to-end check:
    @Test fun `M8 comma in quoted attribute keeps the display name intact`() {
        val e = one("#EXTINF:-1 tvg-name=\"A, B\" group-title=\"News, UK\",Display, Name")
        assertEquals("Display, Name", e.name)
        assertEquals("A, B", e.tvgName)
        assertEquals("News, UK", e.groupTitle)
    }

    @Test fun `M8 unterminated quote is a warning, not a failure`() {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1 tvg-logo=\"http://l/x.png,Name\nhttp://h/a\n")
        assertEquals("Name", r.entries.single().name)
        assertTrue(WarningCode.BAD_ATTRIBUTE in r.sink.codes())
    }

    // M10
    @Test fun `M10 precedence table`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 user-agent="ExtinfUA" http-referrer="ExtinfRef" http-origin="ExtinfOrigin" http-cookie="c=1",N
            #EXTHTTP:{"Referer":"HttpRef","X-Custom":"1"}
            #EXTVLCOPT:http-origin=VlcOrigin
            http://h/a.ts|Referer=PipeRef
        """.trimIndent()
        val e = M3u.parseText(text).entries.single()
        assertEquals(
            mapOf("User-Agent" to "ExtinfUA", "Referer" to "PipeRef", "Origin" to "VlcOrigin", "Cookie" to "c=1", "X-Custom" to "1"),
            e.headers,
        )
        assertEquals(mapOf("http-origin" to "VlcOrigin"), e.vlcOpts)
    }

    // M11
    @Test fun `M11 every VLC option is kept, http keys also become headers`() {
        val e = M3u.parseText("#EXTM3U\n#EXTINF:-1,N\n#EXTVLCOPT:network-caching=1000\n#EXTVLCOPT:http-user-agent=UA\nhttp://h/a\n").entries.single()
        assertEquals(mapOf("network-caching" to "1000", "http-user-agent" to "UA"), e.vlcOpts)
        assertEquals(mapOf("User-Agent" to "UA"), e.headers)
    }

    @Test fun `aux lines reset after each entry`() {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=UA\nhttp://h/a\n#EXTINF:-1,B\nhttp://h/b\n")
        assertTrue(r.byName("B").headers.isEmpty())
        assertTrue(r.byName("B").vlcOpts.isEmpty())
    }

    // M14
    @Test fun `M14 sticky EXTGRP is not reset by an explicit group-title`() {
        val text = "#EXTM3U\n#EXTGRP:Kids\n#EXTINF:-1 group-title=\"Own\",A\nhttp://h/a\n#EXTINF:-1,B\nhttp://h/b\n"
        assertEquals(listOf("Own", "Kids"), M3u.parseText(text, M3uParseOptions(extgrpSticky = true)).entries.map { it.groupTitle })
        assertEquals(listOf("Own", null), M3u.parseText(text).entries.map { it.groupTitle })
    }

    // M15
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "none", "NULL", "nil", "0", "-", "n/a", "NA", "unknown", "noepg", "no-epg", "No_EPG", "undefined"])
    fun `M15 junk tvg-id becomes null`(junk: String) {
        assertNull(one("#EXTINF:-1 tvg-id=\"$junk\",N").tvgId)
    }

    @Test fun `M15 real ids are trimmed and keep their case`() {
        assertEquals("BBCOne.uk", one("#EXTINF:-1 tvg-id=\" BBCOne.uk \",N").tvgId)
        assertEquals("00", one("#EXTINF:-1 tvg-id=\"00\",N").tvgId)
    }

    // M16
    @ParameterizedTest
    @CsvSource("tvg-chno,101,101", "ch-number,7.00,7", "channel-number,123456,123456", "tvg-num,0,0")
    fun `M16 channel number aliases and format`(attr: String, value: String, expected: Int) {
        assertEquals(expected, one("#EXTINF:-1 $attr=\"$value\",N").number)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1234567", "1.5", "12a", "-3", "", "1."])
    fun `M16 invalid numbers are null`(value: String) {
        assertNull(one("#EXTINF:-1 tvg-chno=\"$value\",N").number)
    }

    @Test fun `M16 first present alias wins`() {
        assertEquals(5, one("#EXTINF:-1 tvg-chno=\"5\" tvg-num=\"9\",N").number)
    }

    // M17
    @ParameterizedTest
    @CsvSource("2,120", "-1.5,-90", "'-1,5',-90", "24,1440", "3600,60", "-7200,-120", "0.25,15")
    fun `M17 tvg-shift`(value: String, minutes: Int) {
        assertEquals(minutes, one("#EXTINF:-1 tvg-shift=\"$value\",N").epgShiftMinutes)
    }

    @Test fun `M17 out of range or unparsable is null with BAD_ATTRIBUTE`() {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1 tvg-shift=\"100000\",A\nhttp://h/a\n#EXTINF:-1 tvg-shift=\"x\",B\nhttp://h/b\n")
        assertTrue(r.entries.all { it.epgShiftMinutes == null })
        assertEquals(2, r.sink.codes().count { it == WarningCode.BAD_ATTRIBUTE })
    }

    @Test fun `M17 header tvg-shift in seconds is stored as hours`() {
        assertEquals(-1.0, M3u.parseText("#EXTM3U tvg-shift=\"-3600\"\n#EXTINF:-1,A\nhttp://h/a\n").header.tvgShiftHours)
    }

    // M18
    @Test fun `M18 group-title split on semicolons, empty parts dropped`() {
        val e = one("#EXTINF:-1 group-title=\" ;News; ;UK \",N")
        assertEquals("News", e.groupTitle)
        assertEquals(listOf("UK"), e.extraGroups)
        assertNull(one("#EXTINF:-1 group-title=\"\",N").groupTitle)
    }

    // M20
    @ParameterizedTest
    @ValueSource(strings = ["http://h/a", "https://h/a", "rtmp://h/a", "rtmps://h/a", "rtmpt://h/a", "rtmpe://h/a", "rtsp://h/a", "udp://@239.0.0.1:1234", "rtp://239.0.0.1:5000", "mms://h/a", "HTTP://H/A"])
    fun `M20 accepted schemes`(url: String) {
        assertEquals(url, one("#EXTINF:-1,N", url = url).url)
    }

    @ParameterizedTest
    @ValueSource(strings = ["file:///sdcard/a.ts", "content://media/1", "javascript:alert(1)", "ftp://h/a", "C:\\videos\\a.ts"])
    fun `M20 rejected schemes are skipped with UNSUPPORTED_SCHEME`(url: String) {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1,N\n$url\n#EXTINF:-1,Ok\nhttp://h/ok\n")
        assertEquals(listOf("Ok"), r.entries.map { it.name })
        assertEquals(listOf(WarningCode.UNSUPPORTED_SCHEME), r.sink.codes())
        assertEquals(0, r.entries.single().position, "positions count emitted entries only")
    }

    // M21
    @Test fun `M21 relative URLs resolve against the base, or are skipped without one`() {
        val opts = M3uParseOptions(baseUrl = "https://p.example.com/a/b/list.m3u")
        assertEquals("https://p.example.com/a/b/c/1.ts", one("#EXTINF:-1,N", url = "c/1.ts", options = opts).url)
        assertEquals("https://p.example.com/root.ts", one("#EXTINF:-1,N", url = "/root.ts", options = opts).url)
        assertEquals("https://p.example.com/a/up.ts", one("#EXTINF:-1,N", url = "../up.ts", options = opts).url)
        assertEquals("https://cdn.example.com/x.ts", one("#EXTINF:-1,N", url = "//cdn.example.com/x.ts", options = opts).url)
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1,N\nc/1.ts\n")
        assertTrue(r.entries.isEmpty())
        assertEquals(listOf(WarningCode.RELATIVE_URL_UNRESOLVED), r.sink.codes())
    }

    // M22
    @Test fun `M22 logos`() {
        val h = "#EXTM3U url-logo=\"http://logos/\""
        assertEquals("http://logos/a.png", one("#EXTINF:-1 tvg-logo=\"a\",N", header = h).logoUrl)
        assertEquals("http://logos/a.jpg", one("#EXTINF:-1 tvg-logo=\"a.jpg\",N", header = h).logoUrl)
        assertEquals("https://abs/a", one("#EXTINF:-1 tvg-logo=\"https://abs/a\",N", header = h).logoUrl)
        val base = M3uParseOptions(baseUrl = "http://p/list/x.m3u")
        assertEquals("http://p/list/img/a.png", one("#EXTINF:-1 tvg-logo=\"img/a.png\",N", options = base).logoUrl)
        assertNull(one("#EXTINF:-1 tvg-logo=\"\",N").logoUrl)
    }

    // M23
    @Test fun `M23 EXTINF followed by EXTINF drops the first`() {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1,Lost\n#EXTINF:-1,Kept\nhttp://h/k\n")
        assertEquals(listOf("Kept"), r.entries.map { it.name })
        assertEquals(listOf(2L), r.sink.warnings.filter { it.code == WarningCode.EXTINF_WITHOUT_URL }.map { it.line })
    }

    @Test fun `M23 free text without EXTINF is not a URL`() {
        val r = M3u.parseText("Your account has expired\n#EXTINF:-1,A\nhttp://h/a\n")
        assertEquals(listOf("A"), r.entries.map { it.name })
    }

    // M24
    @Test fun `M24 HLS tags before the first EXTINF reject, after it they are ignored`() {
        listOf("#EXT-X-TARGETDURATION:10", "#EXT-X-MEDIA-SEQUENCE:1", "#EXT-X-PLAYLIST-TYPE:VOD", "#ext-x-stream-inf:BANDWIDTH=1").forEach {
            assertEquals(SourceError.NotASource(NotASourceKind.HLS_MANIFEST), M3u.failure("#EXTM3U\n$it\nseg.ts\n").error)
        }
        val ok = M3u.parseText("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:-1,A\nhttp://h/a\n#EXT-X-TARGETDURATION:10\n#EXTINF:-1,B\nhttp://h/b\n")
        assertEquals(2, ok.entries.size)
    }

    // M25
    @Test fun `M25 header attributes, aliases, several EXTM3U lines`() {
        val text = """
            #EXTM3U x-tvg-url="http://a/1.xml, http://a/2.xml" url-tvg="http://a/2.xml http://a/3.xml" refresh="3600" max-conn="2" m3uautoload=1 deinterlace=auto
            #EXTM3U url-tvg="http://a/4.xml" m3uautoload=0 cache=500
            #EXTINF:-1,A
            http://h/a
        """.trimIndent()
        val h = M3u.parseText(text).header
        assertEquals(listOf("http://a/1.xml", "http://a/2.xml", "http://a/3.xml", "http://a/4.xml"), h.epgUrls)
        assertEquals(3600L, h.refreshSec)
        assertEquals(2, h.maxConnections)
        assertEquals(mapOf("m3uautoload" to "1", "deinterlace" to "auto", "cache" to "500"), h.attributes)
    }

    // M26/M27/M28/M29/M30
    @Test fun `M26 catch-up days take leading digits`() {
        assertEquals(7, one("#EXTINF:-1 catchup-days=\"7d\",N").catchup.days)
        assertEquals(14, one("#EXTINF:-1 catchup-days=14,N").catchup.days)
    }

    @ParameterizedTest
    @CsvSource("'adult=\"1\"',true", "'adult=\"TRUE\"',true", "'censored=\"1\"',true", "'parent-code=\"0000\"',true", "'adult=\"0\"',false", "'parent-code=\"\"',false")
    fun `M27 adult flags`(attr: String, expected: Boolean) {
        assertEquals(expected, one("#EXTINF:-1 $attr,N").adult)
    }

    @Test fun `M28 nested playlist is skipped`() {
        val r = M3u.parseText("#EXTM3U\n#EXTINF:-1 type=\"playlist\",Sub\nhttp://h/sub.m3u\n")
        assertTrue(r.entries.isEmpty())
        assertEquals(listOf(WarningCode.UNSUPPORTED_SCHEME), r.sink.codes())
    }

    @Test fun `M29 unknown hash lines are ignored`() {
        val r = M3u.parseText("#EXTM3U\n#PLAYLIST:Mine\n#EXTIMG:x\n#EXTALB:y\n# a comment\n#EXTINF:-1,A\n#EXTART:z\nhttp://h/a\n")
        assertEquals(1, r.entries.size)
        assertEquals(0, r.summary.warnings)
    }

    @Test fun `M30 unknown attributes are kept, typed ones are not duplicated`() {
        val e = one("#EXTINF:-1 tvg-id=\"x\" tvg-language=\"en;fr\" provider=\"P\" audio-track=\"eng\" tvg-type=\"live\" radio=\"false\",N")
        assertEquals(mapOf("tvg-language" to "en;fr", "provider" to "P", "audio-track" to "eng", "tvg-type" to "live", "radio" to "false"), e.attributes)
    }

    @Test fun `kinds are counted in the summary`() {
        val text = "#EXTM3U\n#EXTINF:-1,L\nhttp://h/l\n#EXTINF:-1 radio=\"1\",R\nhttp://h/r\n#EXTINF:-1,M\nhttp://h/m.mp4\n#EXTINF:-1,S S01E01\nhttp://h/s.mkv\n"
        val s = M3u.parseText(text).summary
        assertEquals(listOf(4, 1, 1, 1, 1), listOf(s.entries, s.live, s.radio, s.movies, s.series))
        assertEquals(EntryKind.SERIES, M3u.parseText(text).byName("S S01E01").kind)
    }

    @Test fun `cancellation is polled while parsing`() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(5000) { sb.append("#EXTINF:-1,C$it\nhttp://h/$it\n") }
        var polls = 0
        assertFailsWith<CancellationException> {
            M3uParserImpl().parse(Buffer().writeUtf8(sb.toString()), M3uParseOptions(), CollectingSink(), CancelCheck { ++polls > 2 })
        }
    }
}
