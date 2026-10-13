package app.thebox.core.m3u

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.source.EntryKind
import app.thebox.core.source.WarningCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One test (or more) per fixture in core/src/test/resources/m3u (docs/design/m1-data-layer.md §5.2). */
class M3uParserFixturesTest {
    @Test fun `iptv_org - header EPG list, quoted commas, groups, http headers`() {
        val r = M3u.parseFixture("m3u/iptv_org.m3u")
        assertEquals(30, r.summary.entries)
        assertEquals(30, r.summary.live)
        assertTrue(r.summary.hadHeader)
        assertEquals("UTF-8", r.summary.charset)
        assertEquals(listOf("https://iptv-org.github.io/epg/a.xml.gz", "https://iptv-org.github.io/epg/b.xml"), r.header.epgUrls)
        val first = r.entries[0]
        assertEquals("2M Monde (720p)", first.name)
        assertEquals("2MMonde.ma@SD", first.tvgId)
        assertEquals("News", first.groupTitle)
        assertEquals(listOf("Morocco"), first.extraGroups)
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "https://www.example-referrer.com/"),
            first.headers,
        )
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", first.vlcOpts["http-user-agent"])
        assertEquals("INT", first.attributes["tvg-country"])
        assertFalse(first.attributes.containsKey("http-user-agent"), "header aliases are typed, not attributes")
        val bbc = r.byName("BBC News (720p)")
        assertEquals("News, UK", bbc.tvgName)
        assertEquals("https://i.imgur.com/logo02.png", bbc.logoUrl)
        assertEquals((0 until 30).toList(), r.entries.map { it.position })
        assertEquals(0, r.summary.warnings)
        assertEquals(1, r.sink.headerCalls)
        assertTrue(r.sink.headerBeforeFirstEntry)
    }

    @ParameterizedTest
    @ValueSource(strings = ["m3u/iptv_org.m3u.gz", "m3u/iptv_org.m3u.gz.gz", "m3u/iptv_org.zip"])
    fun `compressed copies parse identically`(path: String) {
        val plain = M3u.parseFixture("m3u/iptv_org.m3u").entries
        val packed = M3u.parseFixture(path).entries
        assertEquals(plain, packed)
    }

    @Test fun `xtream_get_php - kinds, catch-up attributes, raw URLs (templating is the differ's job)`() {
        val r = M3u.parseFixture("m3u/xtream_get_php.m3u")
        assertEquals(17, r.summary.entries)
        assertEquals(12, r.summary.live)
        assertEquals(3, r.summary.movies)
        assertEquals(2, r.summary.series)
        val bbc = r.byName("UK: BBC One HD")
        assertEquals("http://line.example-panel.tv:8080/live/u%40x/p%23w/101.ts", bbc.url)
        assertEquals("xc", bbc.catchup.type)
        assertEquals(3, bbc.catchup.days)
        assertEquals("101", bbc.attributes["xui-id"])
        assertNull(bbc.xtreamStreamId, "the parser never sets the id: only the differ, after the vote (B6)")
        assertEquals(EntryKind.LIVE, r.byName("UK: BBC Two HD").kind)
        assertEquals("http://line.example-panel.tv:8080/u%40x/p%23w/102", r.byName("UK: BBC Two HD").url)
        assertEquals(2, r.byName("UK: Channel 4 HD").catchup.timeshiftDays)
        assertEquals(EntryKind.MOVIE, r.byName("Inception (2010)").kind)
        assertEquals(EntryKind.SERIES, r.byName("Breaking Bad S01 E01").kind)
        assertEquals("Series | Drama", r.byName("Breaking Bad S01 E02").groupTitle)
    }

    @Test fun `pipe_headers - M9 and M10`() {
        val r = M3u.parseFixture("m3u/pipe_headers.m3u")
        assertEquals(6, r.entries.size)
        r.byName("Foo Bar").let {
            assertEquals("https://cdn.example.com/foo/index.m3u8", it.url)
            assertEquals(mapOf("User-Agent" to "Foo Bar", "Referer" to "https://x/"), it.headers)
        }
        assertEquals(mapOf("X-Token" to "abc", "Origin" to "https://origin.example"), r.byName("Token Channel").headers)
        assertEquals("http://h.example.com/play?ids=1|2|3", r.byName("Query With Pipe").url)
        assertEquals("http://h.example.com/empty/stream.m3u8", r.byName("Empty Pipe").url)
        r.byName("Conflict").let {
            assertEquals("PipeUA", it.headers["User-Agent"], "pipe beats EXTVLCOPT beats EXTINF")
            assertEquals("https://vlc.example/", it.headers["Referer"])
            assertEquals("VlcUA", it.vlcOpts["http-user-agent"])
        }
        assertEquals(mapOf("User-Agent" to "A+B"), r.byName("Plus And Bad Pair").headers)
        assertTrue(WarningCode.BAD_ATTRIBUTE in r.sink.codes())
    }

    @Test fun `kodi_props - M12 and M13`() {
        val r = M3u.parseFixture("m3u/kodi_props.m3u")
        val drm = r.byName("DRM One")
        assertEquals("mpd", drm.kodiProps["inputstream.adaptive.manifest_type"], "KODIPROP before EXTINF attaches to the next entry")
        assertEquals("inputstream.adaptive", drm.kodiProps["inputstream"])
        assertEquals("com.widevine.alpha", drm.kodiProps["inputstream.adaptive.license_type"])
        assertEquals("https://lic.example.com/wv|Content-Type=application/octet-stream|R{SSM}|", drm.kodiProps["inputstream.adaptive.license_key"])
        assertEquals(mapOf("User-Agent" to "X", "Referer" to "Y"), drm.headers)
        assertEquals("https://dash.example.com/drm1/manifest.mpd", drm.url)
        val ck = r.byName("ClearKey Two")
        assertEquals("clearkey", ck.kodiProps["inputstream.adaptive.license_type"])
        assertEquals(mapOf("Cookie" to "a=b"), ck.headers)
        assertTrue(r.byName("Bad Json").headers.isEmpty())
        assertTrue(r.sink.warnings.any { it.code == WarningCode.BAD_ATTRIBUTE && it.detail == "EXTHTTP" })
        r.byName("Plain After").let {
            assertTrue(it.kodiProps.isEmpty())
            assertTrue(it.headers.isEmpty())
        }
    }

    @Test fun `bom_crlf - M1 and M4`() {
        val r = M3u.parseFixture("m3u/bom_crlf.m3u")
        assertTrue(r.summary.hadHeader)
        assertEquals(listOf("Channel One", "Channel Two", "Café Three"), r.entries.map { it.name })
        assertEquals(listOf("http://h.example.com/one.ts", "http://h.example.com/two.ts", "http://h.example.com/three.ts"), r.entries.map { it.url })
        assertTrue(r.entries.none { e -> e.url.any { it.isWhitespace() } || e.name.any { it == '\r' } })
    }

    @Test fun `no_header - M3 and M23`() {
        val r = M3u.parseFixture("m3u/no_header.m3u")
        assertFalse(r.summary.hadHeader)
        assertEquals(1, r.sink.headerCalls)
        assertEquals(app.thebox.core.source.M3uHeader(), r.header)
        assertEquals(listOf("First", "Orphan Stream"), r.entries.map { it.name })
        assertTrue(WarningCode.URL_WITHOUT_EXTINF in r.sink.codes())
        assertTrue(WarningCode.EXTINF_WITHOUT_URL in r.sink.codes())
    }

    @Test fun `latin1 - mid-file fallback to windows-1252`() {
        val r = M3u.parseFixture("m3u/latin1.m3u")
        assertEquals(listOf("Ascii One", "Ascii Two", "Télé Québec", "Zürich € TV", "EspaÑa", "Plain After"), r.entries.map { it.name })
        assertEquals("Français", r.byName("Télé Québec").groupTitle)
        assertEquals("windows-1252", r.summary.charset)
        assertEquals(listOf(7L), r.sink.warnings.filter { it.code == WarningCode.CHARSET_FALLBACK }.map { it.line })
    }

    @Test fun `cp1251 - Cyrillic fallback`() {
        val r = M3u.parseFixture("m3u/cp1251.m3u")
        assertEquals(listOf("Первый канал", "Россия 1 HD", "НТВ"), r.entries.map { it.name })
        assertEquals("Россия", r.entries[0].groupTitle)
        assertEquals("windows-1251", r.summary.charset)
    }

    @Test fun `cp1251 - forced charset and charset hint`() {
        val forced = M3u.parseFixture("m3u/cp1251.m3u", M3uParseOptions(forcedCharset = "windows-1251"))
        assertEquals("Первый канал", forced.entries[0].name)
        assertTrue(WarningCode.CHARSET_FALLBACK !in forced.sink.codes())
        val hinted = M3u.parseFixture("m3u/cp1251.m3u", M3uParseOptions(charsetHint = "audio/x-mpegurl; charset=windows-1251"))
        assertEquals("НТВ", hinted.entries[2].name)
    }

    @Test fun `utf16le_bom - transcoded`() {
        val r = M3u.parseFixture("m3u/utf16le_bom.m3u")
        assertEquals(listOf("München TV", "日本 📺", "Third"), r.entries.map { it.name })
        assertEquals("A.de", r.entries[0].tvgId)
        assertTrue(r.summary.hadHeader)
    }

    @Test fun `utf16 big-endian with BOM`() {
        val text = "#EXTM3U\n#EXTINF:-1,Grüße\nhttp://h/g.ts\n"
        val r = M3u.parseBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE))
        assertEquals("Grüße", r.entries.single().name)
    }

    @Test fun `extgrp - per-entry default`() {
        val r = M3u.parseFixture("m3u/extgrp.m3u")
        assertEquals(listOf("News", null, "Football", null, null), r.entries.map { it.groupTitle })
    }

    @Test fun `extgrp - sticky option`() {
        val r = M3u.parseFixture("m3u/extgrp.m3u", M3uParseOptions(extgrpSticky = true))
        assertEquals(listOf("News", "News", "Football", "Sports", null), r.entries.map { it.groupTitle })
    }

    @Test fun `catchup_variants - M25 header defaults and M26 per channel`() {
        val r = M3u.parseFixture("m3u/catchup_variants.m3u")
        val h = r.header.catchup
        assertEquals("append", h.type)
        assertEquals("?utc={utc}&lutc={lutc}", h.source)
        assertEquals(5, h.days)
        assertEquals(-1.5, h.correctionHours)
        assertEquals(2.0, r.header.tvgShiftHours)
        assertEquals(listOf("http://epg.example.com/guide.xml.gz"), r.header.epgUrls)
        assertTrue(r.header.attributes.isEmpty())
        r.byName("Default Mode").catchup.let {
            assertEquals("default", it.type)
            assertEquals(7, it.days)
            assertEquals("http://archive.example.com/def/{Y}-{m}-{d}/{H}{M}.ts", it.source)
        }
        assertEquals("shift", r.byName("Shift Alias").catchup.type)
        assertEquals(0.5, r.byName("Flussonic").catchup.correctionHours)
        assertEquals(4, r.byName("Legacy Timeshift").catchup.timeshiftDays)
        r.byName("Tvg Rec").catchup.let {
            assertEquals(2, it.tvgRecDays)
            assertEquals(86_400L, it.catchupTimeSec)
        }
        assertEquals("1", r.byName("Unrecognised Mode").catchup.type, "kept raw; CatchupResolver decides")
    }

    @Test fun `edge_cases - without base URL`() {
        val r = M3u.parseFixture("m3u/edge_cases.m3u")
        assertEquals(
            listOf(
                "Upper Keys", "Empty Id", "Junk Id", "RTMP Stream", "Multicast", "Duplicate Name", "Duplicate Name", "Radio One",
                "Locked", "Name From Tvg", "From Url",
            ),
            r.entries.map { it.name },
        )
        r.byName("Upper Keys").let {
            assertEquals("Upper.us", it.tvgId)
            assertEquals("Single Quoted", it.groupTitle)
            assertEquals(101, it.number)
            assertEquals(-90, it.epgShiftMinutes)
        }
        r.byName("Empty Id").let {
            assertNull(it.tvgId)
            assertEquals(60, it.epgShiftMinutes)
            assertEquals("http://logos.example.com/img/bbc_one.png", it.logoUrl)
        }
        r.byName("Junk Id").let {
            assertNull(it.tvgId)
            assertNull(it.number)
        }
        assertEquals("udp://@239.1.1.1:1234", r.byName("Multicast").url)
        assertEquals("rtmp://live.example.com/app/stream", r.byName("RTMP Stream").url)
        assertEquals(EntryKind.RADIO, r.byName("Radio One").kind)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", r.byName("Radio One").logoUrl)
        assertTrue(r.byName("Locked").adult)
        assertEquals("http://h.example.com/noname.ts", r.byName("Name From Tvg").url)
        assertEquals(listOf("Sports", "Movies"), r.entries.filter { it.name == "Duplicate Name" }.map { it.groupTitle })
        val codes = r.sink.codes()
        assertEquals(1, codes.count { it == WarningCode.LINE_TOO_LONG })
        assertEquals(1, codes.count { it == WarningCode.RELATIVE_URL_UNRESOLVED })
        assertEquals(2, codes.count { it == WarningCode.UNSUPPORTED_SCHEME }) // file: and type="playlist"
        assertTrue(r.entries.none { it.url.contains("huge") }, "the URL of a dropped over-long EXTINF never becomes an orphan entry")
        assertTrue(r.entries.none { it.url.startsWith("file:") })
    }

    @Test fun `edge_cases - relative URL resolved against the base`() {
        val r = M3u.parseFixture("m3u/edge_cases.m3u", M3uParseOptions(baseUrl = "http://pl.example.com/lists/main.m3u?u=1"))
        assertEquals("http://pl.example.com/lists/streams/rel.m3u8", r.byName("Relative URL").url)
    }

    @ParameterizedTest
    @CsvSource(
        "m3u/hls_manifest.m3u8, HLS_MANIFEST",
        "m3u/html_expired.m3u, HTML_PAGE",
        "m3u/xmltv_as_playlist.xml, WRONG_FORMAT",
        "m3u/api_response.json, WRONG_FORMAT",
        "m3u/empty.m3u, EMPTY",
        "m3u/whitespace.m3u, EMPTY",
        "m3u/binary.ts, BINARY",
    )
    fun `rejections - M2 and M24`(path: String, kind: NotASourceKind) {
        val sink = CollectingSink()
        val e = assertFailsWith<SourceException> { M3uParserImpl().parse(M3u.fixture(path), M3uParseOptions(), sink) }
        assertEquals(SourceError.NotASource(kind), e.error)
        assertTrue(sink.entries.isEmpty())
        assertEquals(0, sink.headerCalls, "nothing is emitted before a rejection")
    }
}
