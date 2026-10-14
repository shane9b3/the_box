package app.thebox.core.playback.resolve

import app.thebox.core.playback.StreamFormat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeaderPolicyTest {
    private fun merge(
        channel: Map<String, String> = emptyMap(),
        secret: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        override: String? = null,
        playlistUa: String? = null,
    ) = HeaderPolicy.merge(extra, channel, playlistUa, "Default/1", override, secret)

    @Test
    fun sixLevelsOfUserAgentPrecedence() {
        assertEquals("Default/1", merge()["User-Agent"])
        assertEquals("Playlist/5", merge(playlistUa = "Playlist/5")["User-Agent"])
        assertEquals("Override/4", merge(playlistUa = "Playlist/5", override = "Override/4")["User-Agent"])
        assertEquals("Extra/3", merge(extra = mapOf("user-agent" to "Extra/3"), playlistUa = "Playlist/5", override = "Override/4")["User-Agent"])
        assertEquals(
            "Secret/2",
            merge(secret = mapOf("USER-AGENT" to "Secret/2"), extra = mapOf("User-Agent" to "Extra/3"), override = "Override/4")["User-Agent"],
        )
        assertEquals(
            "Channel/1",
            merge(channel = mapOf("http-user-agent" to "Channel/1"), secret = mapOf("User-Agent" to "Secret/2"), override = "Override/4")["User-Agent"],
        )
    }

    @Test
    fun sanitizeDropsControlCharactersNonAsciiAndBadNames() {
        // Code review 2026-10-14, N-S1: OkHttp would throw IllegalArgumentException with the value in the message.
        val (kept, dropped) = HeaderPolicy.sanitize(
            linkedMapOf(
                "User-Agent" to "Box/1",
                "X-Crlf" to "a\r\nX-Injected: 1",
                "X-Nul" to "a\u0000b",
                "X-Token" to "s3cr\u00e9t",
                "Bad Name" to "v",
                "X-Tab" to "a\tb",
                "" to "empty",
            ),
        )
        assertEquals(linkedMapOf("User-Agent" to "Box/1", "X-Tab" to "a\tb"), kept)
        assertEquals(listOf("X-Crlf", "X-Nul", "X-Token", "Bad?Name", ""), dropped)
        assertEquals(false, dropped.any { it.contains("s3cr") }, "names only, never a value")
    }

    @Test
    fun sanitizeKeepsAValidMapAsIs() {
        val h = mapOf("User-Agent" to "VLC/3.0.20 LibVLC/3.0.20", "Referer" to "http://x/?a=b&c=~d")
        val (kept, dropped) = HeaderPolicy.sanitize(h)
        assertEquals(h, kept)
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun overrideNeverBeatsAChannelUserAgent() {
        val h = merge(channel = mapOf("user-agent" to "Chan"), override = "VLC/3.0.20")
        assertEquals("Chan", h["User-Agent"])
        assertEquals(1, h.keys.count { it.equals("user-agent", ignoreCase = true) })
    }

    @Test
    fun keysAreCaseInsensitiveAndCanonical() {
        val h = merge(
            channel = mapOf("referrer" to "https://c/", "x-custom" to "chan"),
            secret = mapOf("COOKIE" to "s=1", "X-Custom" to "secret"),
            extra = mapOf("http-origin" to "https://o", "referer" to "https://p/"),
        )
        assertEquals("https://c/", h["Referer"])
        assertEquals("s=1", h["Cookie"])
        assertEquals("https://o", h["Origin"])
        assertEquals("chan", h["x-custom"])
        assertNull(h["X-Custom"])
        assertEquals(listOf("Referer", "x-custom", "Cookie", "Origin", "User-Agent"), h.keys.toList())
    }

    @Test
    fun transportOwnedHeadersAreDropped() {
        val h = merge(
            channel = mapOf("Range" to "bytes=0-", "Host" to "evil"),
            secret = mapOf("Accept-Encoding" to "gzip"),
            extra = mapOf(
                "connection" to "close", "Icy-MetaData" to "1", "X-Keep" to "1",
                "Content-Length" to "5", "Transfer-Encoding" to "chunked", "TE" to "trailers", "Upgrade" to "h2c", "Expect" to "100-continue",
            ),
        )
        assertEquals(setOf("X-Keep", "User-Agent"), h.keys)
    }

    @Test
    fun blankUserAgentsFallThrough() {
        assertEquals("Default/1", merge(playlistUa = " ", override = "")["User-Agent"])
    }
}

class FormatHintsTest {
    @ParameterizedTest
    @CsvSource(
        "http://h/live/u/p/1.m3u8, HLS",
        "http://h/a/index.M3U8?token=x.ts, HLS",
        "http://h/list.m3u#frag.ts, HLS",
        "http://h/live/u/p/1.ts, TS",
        "http://h/live/u/p/1.TS?x=y.m3u8, TS",
        "http://h/stream.mpegts, TS",
        "http://h/play/123/mpegts, TS",
        "http://h/u/p/123, TS",
        "http://h:8080, TS",
        "http://h.example.com/, TS",
        "http://h/manifest.mpd, DASH",
        "http://h/movie/u/p/9.mp4, PROGRESSIVE_OTHER",
        "http://h/v.MKV, PROGRESSIVE_OTHER",
        "http://h/v.flv, PROGRESSIVE_OTHER",
        "http://h/radio.aac, PROGRESSIVE_OTHER",
        "http://h/radio.mp3?x=1, PROGRESSIVE_OTHER",
        "http://h/watch.php, UNKNOWN",
        "http://h/a.b/c.xyz#x, UNKNOWN",
    )
    fun hintTable(url: String, expected: StreamFormat) {
        assertEquals(expected, FormatHints.hint(url))
    }

    @Test
    fun hostIsLowerCasedWithoutUserInfoOrPath() {
        assertEquals("h.example:8080", FormatHints.host("http://user:pw@H.Example:8080/live/x?y=1"))
        assertEquals("h", FormatHints.host("https://h?x"))
        assertEquals("", FormatHints.host("not a url"))
    }
}
