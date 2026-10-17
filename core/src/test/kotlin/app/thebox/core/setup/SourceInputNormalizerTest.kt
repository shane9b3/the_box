package app.thebox.core.setup

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3.4.3 table, 40 pasted-URL fixtures, invisible characters and `+` in passwords (§6.1 WP-B). */
class SourceInputNormalizerTest {
    private val n = SourceInputNormalizerImpl()

    // ------------------------------------------------------------------ §3.4.3 table

    @Test
    fun spacesTabsNbspAndZeroWidthAreRemovedFromTheServerWithATrailingSlash() {
        val s = n.server(" \t ​panel.example.com:8080/‍﻿  ")
        assertEquals(NormalizedInput.Xtream("http://panel.example.com:8080", null, null, null, false), s)
    }

    @Test
    fun aMissingSchemeGetsHttp() {
        assertEquals("http://panel.example.com:8080", (n.server("panel.example.com:8080") as NormalizedInput.Xtream).server)
        assertEquals("https://panel.example.com", (n.server("HTTPS://Panel.Example.com/") as NormalizedInput.Xtream).server)
    }

    @Test
    fun aGetPhpLinkInServerIsSplitAndSaysSo() {
        val s = n.server("http://panel.example.com:8080/get.php?username=u1&password=p1&type=m3u_plus&output=ts")
        assertEquals(NormalizedInput.Xtream("http://panel.example.com:8080", "u1", "p1", "TS", filledFromLink = true), s)
    }

    @Test
    fun playerApiXmltvAndStreamUrlsAreSplitToo() {
        assertEquals("u", (n.server("http://h:80/player_api.php?username=u&password=p") as NormalizedInput.Xtream).username)
        assertEquals("p", (n.server("h.tv/xmltv.php?username=u&password=p") as NormalizedInput.Xtream).password)
        val live = n.server("http://h.tv:25461/live/u/p/1234.m3u8") as NormalizedInput.Xtream
        assertEquals(NormalizedInput.Xtream("http://h.tv:25461", "u", "p", "HLS", true), live)
    }

    @Test
    fun theSameLinkInTheM3uFieldOffersXtream() {
        val m = n.m3uUrl("http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus") as NormalizedInput.M3u
        assertEquals("http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus", m.url)
        assertEquals(NormalizedInput.Xtream("http://panel.example.com:8080", "u", "p", null, true), m.looksLikeXtream)
    }

    @Test
    fun aPlainM3uUrlKeepsItsPathAndQuery() {
        val m = n.m3uUrl("  example.org/lists/My List.m3u?x=1 ") as NormalizedInput.M3u
        assertEquals("http://example.org/lists/My%20List.m3u?x=1", m.url)
        assertNull(m.looksLikeXtream)
    }

    @Test
    fun stalkerPortalsAreRefused() {
        for (s in listOf("http://h.tv/portal.php", "http://h.tv:8080/c/", "h.tv/stalker_portal/c/", "http://h.tv/stalker_portal/server/load.php", "http://h.tv:80/c")) {
            assertEquals(NormalizedInput.Invalid(InputProblem.STALKER_PORTAL), n.server(s), s)
            assertEquals(NormalizedInput.Invalid(InputProblem.STALKER_PORTAL), n.m3uUrl(s), s)
        }
    }

    @Test
    fun spacesInTheHostAndOtherSchemesAreRefusedWithTheReason() {
        assertEquals(NormalizedInput.Invalid(InputProblem.SPACES_IN_HOST), n.server("panel example.com"))
        assertEquals(NormalizedInput.Invalid(InputProblem.SPACES_IN_HOST), n.server("http://panel example.com:8080"))
        assertEquals(NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME), n.server("ftp://panel.example.com"))
        assertEquals(NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME), n.m3uUrl("rtsp://cam.local/stream"))
        assertEquals(NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME), n.m3uUrl("rtsp:cam.local"))
        assertEquals(NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME), n.m3uUrl("udp://@239.0.0.1:1234"))
    }

    @Test
    fun emptyAndNotAUrl() {
        assertEquals(NormalizedInput.Invalid(InputProblem.EMPTY), n.server(" ​ \t"))
        assertEquals(NormalizedInput.Invalid(InputProblem.EMPTY), n.m3uUrl(""))
        assertEquals(NormalizedInput.Invalid(InputProblem.NOT_A_URL), n.server("http://"))
        assertEquals(NormalizedInput.Invalid(InputProblem.NOT_A_URL), n.server("http://panel.example.com:99999"))
        assertEquals(NormalizedInput.Invalid(InputProblem.NOT_A_URL), n.server("http://panel.example.com:abc"))
        assertEquals(NormalizedInput.Invalid(InputProblem.NOT_A_URL), n.server("pa\$nel!.com"))
        assertEquals(NormalizedInput.Invalid(InputProblem.NOT_A_URL), n.server("http://panel..com"))
    }

    // ------------------------------------------------------------------ fields and passwords

    @Test
    fun fieldTrimsOnlyInvisibleCharactersAtTheEnds() {
        assertEquals("ab c", n.field("​ ab c  ﻿"))
        assertEquals("p+ss w​ord", n.field("p+ss w​ord"), "inner characters of a password are never changed")
        assertEquals("", n.field("\t\n ⁠"))
        assertEquals("+", n.field(" + "))
    }

    @Test
    fun plusInAPastedPasswordStaysLiteralAndEscapesAreDecoded() {
        val s = n.server("http://h.tv/get.php?username=me&password=a+b%2Bc%20d") as NormalizedInput.Xtream
        assertEquals("a+b+c d", s.password)
        val raw = n.server("http://h.tv/get.php?username=me&password=+%2B+") as NormalizedInput.Xtream
        assertEquals("+++", raw.password)
    }

    @Test
    fun pastedLinkWithZeroWidthCharactersInsideStillSplits() {
        val s = n.server("​http://h.tv:8080/get.php?user​name=u&password=p‌") as NormalizedInput.Xtream
        assertTrue(s.filledFromLink)
        assertEquals("u", s.username)
        assertEquals("p", s.password)
    }

    // ------------------------------------------------------------------ 40 pasted-URL fixtures

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun pastedServerFixtures(raw: String, expected: Expected) {
        val r = n.server(raw)
        when (expected) {
            is Expected.Server -> {
                assertIs<NormalizedInput.Xtream>(r, raw)
                assertEquals(expected.server, r.server, raw)
                assertEquals(expected.user, r.username, raw)
                assertEquals(expected.pass, r.password, raw)
                assertEquals(expected.output, r.output, raw)
                assertEquals(expected.user != null, r.filledFromLink, raw)
            }
            is Expected.Bad -> assertEquals(NormalizedInput.Invalid(expected.problem), r, raw)
        }
    }

    sealed interface Expected {
        data class Server(val server: String, val user: String? = null, val pass: String? = null, val output: String? = null) : Expected
        data class Bad(val problem: InputProblem) : Expected
    }

    companion object {
        private fun ok(server: String, user: String? = null, pass: String? = null, output: String? = null) = Expected.Server(server, user, pass, output)
        private fun bad(p: InputProblem) = Expected.Bad(p)

        @JvmStatic
        fun fixtures(): List<Array<Any>> = listOf(
            arrayOf("panel.example.com", ok("http://panel.example.com")),
            arrayOf("panel.example.com:8080", ok("http://panel.example.com:8080")),
            arrayOf("http://panel.example.com:8080/", ok("http://panel.example.com:8080")),
            arrayOf("https://panel.example.com:443", ok("https://panel.example.com:443")),
            arrayOf("HTTP://PANEL.EXAMPLE.COM:8080", ok("http://panel.example.com:8080")),
            arrayOf("http://192.168.1.10:25461", ok("http://192.168.1.10:25461")),
            arrayOf("10.0.0.5", ok("http://10.0.0.5")),
            arrayOf("http://[2001:db8::1]:8080", ok("http://[2001:db8::1]:8080")),
            arrayOf("http://panel.example.com:8080/player_api.php", ok("http://panel.example.com:8080")),
            arrayOf("http://panel.example.com:8080/c", bad(InputProblem.STALKER_PORTAL)),
            arrayOf("http://tv.example.net/get.php?username=john&password=secret&type=m3u_plus&output=ts", ok("http://tv.example.net", "john", "secret", "TS")),
            arrayOf("http://tv.example.net/get.php?username=john&password=secret&type=m3u_plus&output=m3u8", ok("http://tv.example.net", "john", "secret", "HLS")),
            arrayOf("http://tv.example.net/get.php?username=john&password=secret&type=m3u_plus&output=hls", ok("http://tv.example.net", "john", "secret", "HLS")),
            arrayOf("http://tv.example.net/get.php?username=john&password=secret&type=m3u_plus", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net/get.php?type=m3u_plus&password=secret&username=john", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("tv.example.net:8000/get.php?username=john&password=secret", ok("http://tv.example.net:8000", "john", "secret", null)),
            arrayOf("http://tv.example.net/get.php?username=john&password=", ok("http://tv.example.net", "john", "", null)),
            arrayOf("http://tv.example.net/get.php?username=&password=x", ok("http://tv.example.net")),
            arrayOf("http://tv.example.net/get.php?username=j%40mail.com&password=p%26q", ok("http://tv.example.net", "j@mail.com", "p&q", null)),
            arrayOf("http://tv.example.net/get.php?username=john&password=pa+ss", ok("http://tv.example.net", "john", "pa+ss", null)),
            arrayOf("http://tv.example.net/get.php?username=john&password=pa%2Bss", ok("http://tv.example.net", "john", "pa+ss", null)),
            arrayOf("http://tv.example.net/get.php?username=JOHN&password=Secret123#frag", ok("http://tv.example.net", "JOHN", "Secret123", null)),
            arrayOf("http://tv.example.net/player_api.php?username=john&password=secret", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net/player_api.php?username=john&password=secret&action=get_live_streams", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net/xmltv.php?username=john&password=secret", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net/panel_api.php?username=john&password=secret", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net/enigma2.php?username=john&password=secret&type=get_live_categories", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("http://tv.example.net:8080/live/john/secret/12345.ts", ok("http://tv.example.net:8080", "john", "secret", "TS")),
            arrayOf("http://tv.example.net:8080/live/john/secret/12345.m3u8", ok("http://tv.example.net:8080", "john", "secret", "HLS")),
            arrayOf("http://tv.example.net:8080/movie/john/secret/99.mkv", ok("http://tv.example.net:8080", "john", "secret", null)),
            arrayOf("http://tv.example.net:8080/series/john/secret/7.mp4", ok("http://tv.example.net:8080", "john", "secret", null)),
            arrayOf("  http://tv.example.net/get.php?username=john&password=secret  ", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf("﻿http://tv.example.net/get.php?username=john&password=secret​", ok("http://tv.example.net", "john", "secret", null)),
            arrayOf(" tv.example.net:8080 ", ok("http://tv.example.net:8080")),
            arrayOf("http://tv.example.net/portal.php", bad(InputProblem.STALKER_PORTAL)),
            arrayOf("http://tv.example.net/stalker_portal/c/index.html", bad(InputProblem.STALKER_PORTAL)),
            arrayOf("rtmp://tv.example.net/live", bad(InputProblem.UNSUPPORTED_SCHEME)),
            arrayOf("tv example.net", bad(InputProblem.SPACES_IN_HOST)),
            arrayOf("http://tv.example.net:0", bad(InputProblem.NOT_A_URL)),
            arrayOf("", bad(InputProblem.EMPTY)),
        )
    }

    /** C-L10 (M5 code review 2026-10-16): only the host is lowercased; a login in the authority keeps its case. Fake values. */
    @Test
    fun onlyTheHostIsLowercased() {
        val m = assertIs<NormalizedInput.M3u>(SourceInputNormalizerImpl().m3uUrl("http://User:PaSs@Panel.Example.COM:8080/List.m3u"))
        assertEquals("http://User:PaSs@panel.example.com:8080/List.m3u", m.url)
        val x = assertIs<NormalizedInput.Xtream>(SourceInputNormalizerImpl().server("HTTP://Panel.Example.com:8080"))
        assertEquals("http://panel.example.com:8080", x.server)
    }
}
