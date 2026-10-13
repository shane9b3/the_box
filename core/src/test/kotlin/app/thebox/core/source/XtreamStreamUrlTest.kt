package app.thebox.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamStreamUrlTest {
    @Test
    fun longFormLive() {
        val u = assertNotNull(XtreamStreamUrl.parse("http://panel.example:8080/live/user1/pass1/102.ts", allowShort = false))
        assertEquals(XtreamStreamUrl("http://panel.example:8080", EntryKind.LIVE, "user1", "pass1", 102, "ts", false), u)
    }

    @Test
    fun longFormMovieSeriesAndNoExtension() {
        assertEquals(EntryKind.MOVIE, XtreamStreamUrl.parse("https://h/movie/u/p/77.mkv", false)?.kind)
        assertEquals(EntryKind.SERIES, XtreamStreamUrl.parse("http://h/series/u/p/9.mp4", false)?.kind)
        assertNull(XtreamStreamUrl.parse("http://h/live/u/p/5", false)?.extension)
        assertEquals("m3u8", XtreamStreamUrl.parse("http://h/live/u/p/5.m3u8?token=x", false)?.extension)
    }

    @Test
    fun credentialsArePercentDecodedButPlusIsLiteral() {
        val u = assertNotNull(XtreamStreamUrl.parse("http://h/live/john%40mail.com/p%2Bq+r/1.ts", false))
        assertEquals("john@mail.com", u.username)
        assertEquals("p+q+r", u.password)
        // A malformed escape keeps the raw segment.
        assertEquals("100%", XtreamStreamUrl.parse("http://h/live/100%/p/1.ts", false)?.username)
    }

    @Test
    fun shortFormOnlyWhenAllowed() {
        val url = "http://h:8000/user1/pass1/102"
        assertNull(XtreamStreamUrl.parse(url, allowShort = false))
        val u = assertNotNull(XtreamStreamUrl.parse(url, allowShort = true))
        assertTrue(u.shortForm)
        assertEquals(102, u.streamId)
        assertEquals("ts", XtreamStreamUrl.parse("http://h/u/p/102.ts", true)?.extension)
        // Ordinary HLS paths look like the short form: never Xtream without the playlist vote.
        assertNull(XtreamStreamUrl.parse("http://h/hls/chan/123", allowShort = false))
        assertNull(XtreamStreamUrl.parse("http://h/u/p/102.mp4", allowShort = true))
    }

    @Test
    fun cdnPathIsOnlyEvidenceWithoutTheVote() {
        // Review blocker B6: the LONG form also matches non-Xtream CDN paths. parse() returns evidence;
        // StableKeyDeriver/CredentialTemplater/CatchupResolver must additionally require the playlist vote
        // (KeyContext.xtreamShaped + the pair equal to the detected credentials, PlaylistCatchupDefaults.xtreamShaped).
        val u = assertNotNull(XtreamStreamUrl.parse("http://cdn/live/bbc/hd/1.m3u8", allowShort = false))
        assertEquals("bbc", u.username)
        assertEquals("hd", u.password)
        assertFalse(u.shortForm)
    }

    @Test
    fun negatives() {
        assertNull(XtreamStreamUrl.parse("rtmp://h/live/u/p/1.ts", true))
        assertNull(XtreamStreamUrl.parse("http://h/live/u/p/abc.ts", true))
        assertNull(XtreamStreamUrl.parse("http://h/live/u/1.ts", false))
        assertNull(XtreamStreamUrl.parse("http://h/live/u/p/1.ts/extra", false))
        // The kind segment is case-sensitive (Kodi's xc regex).
        assertNull(XtreamStreamUrl.parse("http://h/LIVE/u/p/5", false))
        assertNull(XtreamStreamUrl.parse("http://h/play/u/p/1.ts", false))
        assertNull(XtreamStreamUrl.parse("http://h/a/b/c/1.ts", true))
        assertNull(XtreamStreamUrl.parse("http://h/live/u/p/99999999999999999999.ts", false))
    }
}
