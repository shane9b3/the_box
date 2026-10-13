package app.thebox.core.xtream

import app.thebox.core.source.XtreamStreamUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XtreamUrlsTest {
    private val u = XtreamUrls("http://panel.example:8080", "user", "pass")
    private val weird = XtreamUrls("panel.example:8080/", "j@x.com", "p#w&+/ é")

    @Test
    fun templates() {
        assertEquals("http://panel.example:8080/player_api.php?username=user&password=pass", u.playerApi(null))
        assertEquals(
            "http://panel.example:8080/player_api.php?username=user&password=pass&action=get_series_info&series_id=7",
            u.playerApi("get_series_info", mapOf("series_id" to "7")),
        )
        assertEquals("http://panel.example:8080/get.php?username=user&password=pass&type=m3u_plus&output=ts", u.getPhp())
        assertEquals("http://panel.example:8080/get.php?username=user&password=pass&type=m3u&output=m3u8", u.getPhp("m3u", "m3u8"))
        assertEquals("http://panel.example:8080/xmltv.php?username=user&password=pass", u.xmltv())
        assertEquals("http://panel.example:8080/live/user/pass/101.ts", u.live(101, "ts"))
        assertEquals("http://panel.example:8080/live/user/pass/101.m3u8", u.live(101, ".m3u8"))
        assertEquals("http://panel.example:8080/movie/user/pass/2001.mkv", u.movie(2001, "mkv"))
        assertEquals("http://panel.example:8080/series/user/pass/3001.mp4", u.episode(3001, "mp4"))
        assertEquals("http://panel.example:8080/timeshift/user/pass/60/2026-10-13:21-30/101.ts", u.timeshiftPath(60, "2026-10-13:21-30", 101, "ts"))
        assertEquals(
            "http://panel.example:8080/streaming/timeshift.php?username=user&password=pass&stream=101&start=2026-10-13:21-30&duration=60",
            u.timeshiftPhp(101, "2026-10-13:21-30", 60),
        )
    }

    @Test
    fun encoding() {
        // Query values: everything outside unreserved.
        assertEquals("http://panel.example:8080/xmltv.php?username=j%40x.com&password=p%23w%26%2B%2F%20%C3%A9", weird.xmltv())
        // Path segments: pchar keeps @ & + ; / # space and non-ASCII are encoded.
        val live = weird.live(5, "ts")
        assertEquals("http://panel.example:8080/live/j@x.com/p%23w&+%2F%20%C3%A9/5.ts", live)
        // Round trip through the shared parser.
        val parsed = XtreamStreamUrl.parse(live, allowShort = false)!!
        assertEquals("j@x.com", parsed.username)
        assertEquals("p#w&+/ é", parsed.password)
        assertEquals("a%25b%5B%5D%3F", XtreamUrls.pathSegment("a%b[]?"))
        assertEquals("a-b_c.d~e", XtreamUrls.query("a-b_c.d~e"))
    }

    @Test
    fun baseNormalisation() {
        assertEquals("http://h:8080", XtreamUrls.normalizeBase("  h:8080/  "))
        assertEquals("http://h:8080", XtreamUrls.normalizeBase("http://h:8080/player_api.php?username=a&password=b"))
        assertEquals("https://h", XtreamUrls.normalizeBase("HTTPS://h/some/path"))
        assertEquals("http://h", XtreamUrls.normalizeBase("http://h#frag"))
    }
}

class XtreamLoginUrlTest {
    @Test
    fun getPhp() {
        assertEquals(
            XtreamLoginInput("http://h.example:8080", "u@x", "p+q", "m3u8"),
            XtreamLoginUrl.parse("http://h.example:8080/get.php?username=u%40x&password=p%2Bq&type=m3u_plus&output=m3u8"),
        )
        assertEquals(
            XtreamLoginInput("http://h", "a", "b+c", "ts"),
            XtreamLoginUrl.parse("  HTTP://h/Get.php?USERNAME=a&Password=b+c&output=ts  "),
        )
    }

    @Test
    fun playerApiAndXmltv() {
        assertEquals(XtreamLoginInput("http://h.example", "u", "p", null), XtreamLoginUrl.parse("h.example/player_api.php?username=u&password=p"))
        assertEquals(XtreamLoginInput("https://h:443", "u", "p", null), XtreamLoginUrl.parse("https://h:443/xmltv.php?password=p&username=u"))
        assertEquals(XtreamLoginInput("http://h", "u", "", null), XtreamLoginUrl.parse("http://h/sub/player_api.php?username=u&password="))
    }

    @Test
    fun streamUrls() {
        assertEquals(XtreamLoginInput("http://h:8080", "user", "pass", "ts"), XtreamLoginUrl.parse("http://h:8080/live/user/pass/123.ts"))
        assertEquals(XtreamLoginInput("http://h:8080", "user", "pass", "m3u8"), XtreamLoginUrl.parse("http://h:8080/live/user/pass/123.m3u8?token=x"))
        assertEquals(XtreamLoginInput("http://h:8080", "u", "p", null), XtreamLoginUrl.parse("http://h:8080/movie/u/p/1.mkv"))
    }

    @Test
    fun rejects() {
        assertNull(XtreamLoginUrl.parse("http://h/get.php?username=u"))
        assertNull(XtreamLoginUrl.parse("http://h/get.php?password=p"))
        assertNull(XtreamLoginUrl.parse("http://example.com/list.m3u"))
        assertNull(XtreamLoginUrl.parse("http://h/u/p/123"), "the short form is never a login without the playlist vote")
        assertNull(XtreamLoginUrl.parse(""))
    }
}
