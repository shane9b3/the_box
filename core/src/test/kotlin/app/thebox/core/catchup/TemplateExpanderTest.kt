package app.thebox.core.catchup

import app.thebox.core.model.CatchupMode
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateExpanderTest {
    private val s = 1_791_919_800L // 2026-10-13T19:30:00Z
    private val v = TemplateExpander.Values(start = s, end = s + 3600, now = s + 9000, zone = ZoneId.of("Europe/London"), catchupId = "a b/c")

    private fun x(t: String) = TemplateExpander.expand(t, v).url

    @Test
    fun unixTokens() {
        assertEquals("$s $s $s", x("{utc} \${start} {start}"))
        assertEquals("${s + 3600} ${s + 3600} ${s + 3600}", x("{utcend} \${end} {end}"))
        assertEquals("${s + 9000} ${s + 9000} ${s + 9000} ${s + 9000}", x("{lutc} \${now} \${timestamp} {timestamp}"))
        assertEquals("3600 3600 60 1", x("{duration} \${duration} {duration:60} {duration:3600}"))
        assertEquals("9000 150 9000", x("{offset:1} {offset:60} \${offset}"))
    }

    @Test
    fun dateTokensInTheZone() {
        assertEquals("2026/10/13 20:30:00", x("{Y}/{m}/{d} {H}:{M}:{S}"))
        assertEquals("2026-10-13:20-30 21h30 23.00 2026", x("{utc:Y-m-d:H-M} \${end:H}h\${end:M} {lutc:H.M} \${start:Y}"))
        assertEquals("20261013213000", x("{utcend:YmdHMS}"))
        assertEquals("2026-10-13T20:30:00+01:00/2026-10-13T21:30:00+01:00", x("\${start-iso}/\${end-iso}"))
        val utc = v.copy(zone = ZoneOffset.UTC)
        assertEquals("2026-10-13T19:30:00+00:00", TemplateExpander.expand("\${start-iso}", utc).url)
    }

    @Test
    fun catchupIdIsQueryEncoded() {
        val r = TemplateExpander.expand("http://v/{catchup-id}", v)
        assertEquals("http://v/a%20b%2Fc", r.url)
        assertTrue(r.usesCatchupId)
    }

    @Test
    fun unknownTokensStayAndSinglePass() {
        val r = TemplateExpander.expand("{foo}/\${bar}/{duration:x}/{utc", v)
        assertEquals("{foo}/\${bar}/{duration:x}/{utc", r.url)
        assertEquals(listOf("{foo}", "\${bar}", "{duration:x}"), r.unknown)
        // A substituted value is never re-scanned.
        val id = TemplateExpander.expand("{catchup-id}", v.copy(catchupId = "{utc}"))
        assertEquals("%7Butc%7D", id.url)
        assertEquals("plain", x("plain"))
    }

    @Test
    fun doubleBracePlaceholdersPassThroughUntouched() {
        // docs/design/m2-playback-core.md §2.9: {{u}}/{{p}}/{{base}}/{{ext}} survive the catch-up pass and are not unknown.
        val r = TemplateExpander.expand("http://h/{{u}}/{{p}}/{utc}.ts", v)
        assertEquals("http://h/{{u}}/{{p}}/$s.ts", r.url)
        assertEquals(emptyList<String>(), r.unknown)
        assertEquals("{{base}}/timeshift/{{u}}/{{p}}/60/x/$s.{{ext}}", x("{{base}}/timeshift/{{u}}/{{p}}/60/x/\${start}.{{ext}}"))
        assertEquals("\${{u}}/$s", x("\${{u}}/{utc}"))
        // Unterminated "{{" is copied as written (no "}}" → rest of the template).
        assertEquals("a/{{u/{utc}", x("a/{{u/{utc}"))
        // M2 substitutes credentials afterwards: a password containing "{utc}" stays literal (never re-scanned).
        val withPassword = r.url.replace("{{u}}", "user").replace("{{p}}", "pa{utc}ss")
        assertEquals("http://h/user/pa{utc}ss/$s.ts", withPassword)
    }

    @Test
    fun tokenFormatterPadding() {
        assertEquals("0099-01-02 03:04:05", TokenFormatter.format(-59_042_897_755L, ZoneOffset.UTC, "Y-m-d H:M:S"))
        assertEquals("2026-10-14T01:15:00+05:45", TokenFormatter.iso(s, ZoneId.of("Asia/Kathmandu")))
        assertEquals("2026-10-13T15:30:00-04:00", TokenFormatter.iso(s, ZoneId.of("America/New_York")))
    }
}

class FlussonicUrlTest {
    @Test
    fun parseLiveForms() {
        val i = FlussonicUrl.parse("http://fl.example:8080/ch1/index.m3u8?token=abc")!!
        assertEquals(FlussonicUrl("http://fl.example:8080", "ch1", "index", FlussonicUrl.Container.HLS, "?token=abc"), i)
        assertEquals("video", FlussonicUrl.parse("http://fl/a/b/video.m3u8")!!.name)
        assertEquals("a/b", FlussonicUrl.parse("http://fl/a/b/video.m3u8")!!.stream)
        val ts = FlussonicUrl.parse("http://fl/ch1/mpegts")!!
        assertEquals("", ts.name)
        assertEquals(FlussonicUrl.Container.TS, ts.liveContainer)
        assertEquals(FlussonicUrl.Container.DASH, FlussonicUrl.parse("http://fl/ch1/index.mpd")!!.liveContainer)
        assertEquals("index", FlussonicUrl.parse("http://fl/ch1/playlist.m3u8")!!.hlsType)
        assertNull(FlussonicUrl.parse("http://fl/ch1/index.m3u8?token"), "Kodi's regex needs k=v in the query")
        assertNull(FlussonicUrl.parse("http://fl/index.m3u8"))
        assertNull(FlussonicUrl.parse("rtmp://fl/ch1/index.m3u8"))
    }

    @Test
    fun derivedUrls() {
        val f = FlussonicUrl.parse("http://fl/ch1/mono.m3u8?token=abc")!!
        val h = FlussonicUrl.Container.HLS
        val t = FlussonicUrl.Container.TS
        assertEquals("http://fl/ch1/mono-100-3600.m3u8?token=abc", f.range(h, 100, 3600))
        assertEquals("http://fl/ch1/archive-100-3600.ts?token=abc", f.range(t, 100, 3600))
        assertEquals("http://fl/ch1/mono-100-now.m3u8?token=abc", f.rangeOpen(h, 100))
        assertEquals("http://fl/ch1/timeshift_abs-100.ts?token=abc", f.rangeOpen(t, 100))
        assertEquals("http://fl/ch1/timeshift_abs-100.m3u8?token=abc", f.absolute(h, 100))
        assertEquals("http://fl/ch1/mono-timeshift_rel-50.m3u8?token=abc", f.kodi(h, 100, 150))
        assertEquals("http://fl/ch1/timeshift_rel-50.m3u8", FlussonicUrl.parse("http://fl/ch1/index.m3u8")!!.kodi(h, 100, 150))
        assertEquals("http://fl/ch1/timeshift_abs-100.ts", FlussonicUrl.parse("http://fl/ch1/mpegts")!!.kodi(t, 100, 150))
        assertEquals("http://fl/ch1/index-100-60.mpd", FlussonicUrl.parse("http://fl/ch1/index.mpd")!!.range(FlussonicUrl.Container.DASH, 100, 60))
        assertEquals(FlussonicUrl.Container.HLS, f.containerFor(CatchupMode.FLUSSONIC_HLS))
        assertEquals(FlussonicUrl.Container.TS, f.containerFor(CatchupMode.FLUSSONIC_TS))
        assertEquals(FlussonicUrl.Container.HLS, f.containerFor(CatchupMode.FLUSSONIC))
    }
}
