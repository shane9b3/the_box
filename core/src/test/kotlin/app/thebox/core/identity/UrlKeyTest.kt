package app.thebox.core.identity

import app.thebox.core.refresh.DetectedCredentials
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UrlKeyTest {
    @ParameterizedTest
    @CsvSource(
        // §3.2.2 examples
        "http://lb3.x.tv:8080/hls/bbc1/index.m3u8?token=abc, bbc1/index.m3u8",
        "udp://@239.1.1.5:1234, udp/239.1.1.5:1234",
        "http://a.tv/play.php?id=55&token=zz, play.php?id=55",
        // host, port, scheme, userinfo and fragment are dropped
        "https://user:pw@other.cdn:443/hls/bbc1/index.m3u8#frag, bbc1/index.m3u8",
        "rtmp://live.example.com/app/stream1, app/stream1",
        "rtsp://cam.local:554/h264/ch1, h264/ch1",
        "rtp://10.0.0.1@232.0.0.9:5000, rtp/232.0.0.9:5000",
        // only the last two segments, empty segments dropped
        "http://h/a/b//c/d.ts, c/d.ts",
        "http://h/only.ts, only.ts",
        // query: drop tokens case-insensitively, sort the rest
        "http://h/p.php?b=2&TOKEN=x&a=1&Expires=9, p.php?a=1&b=2",
        "http://h/x/chan.m3u8?wmsAuthSign=abc&e=1&st=2&hdnts=3&hdnea=4&sig=5, x/chan.m3u8",
        "http://h/x/chan.m3u8?u=a&p=b&user=c&pass=d&username=e&password=f&session=g&sid=h&t=1&ts=2&time=3&nonce=4&key=5&auth=6&hash=7&signature=8&exp=9, x/chan.m3u8",
        // percent escapes upper-cased, case otherwise kept
        "http://h/Dir/Caf%c3%a9.m3u8, Dir/Caf%C3%A9.m3u8",
        // generic single segment survives when a query survives
        "http://h/play?id=9, play?id=9",
    )
    fun `examples`(url: String, key: String) {
        assertEquals(key, UrlKey.of(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://h/index.m3u8", "http://h/playlist.m3u8?token=1", "http://h/mono.m3u8", "http://h/video.m3u8", "http://h/chunklist.m3u8",
            "http://h/mpegts", "http://h/live", "http://h/stream", "http://h/play", "http://h/index.html", "http://h/", "http://h",
            "not a url", "relative/path.ts", "udp://", "http://h/?token=1",
        ],
    )
    fun `generic or unusable URLs give null (rule 4 applies)`(url: String) {
        assertNull(UrlKey.of(url))
    }

    @Test fun `B3 - credentials of an Xtream-shaped URL never reach the key, voted or not`() {
        assertEquals("102", UrlKey.of("http://h/u/p/102"))
        assertEquals("live/101.ts", UrlKey.of("http://h:8080/live/myuser/s3cret/101.ts"))
        assertEquals("movie/7.mkv", UrlKey.of("http://h/movie/u%40x/p%23w/7.mkv"))
        val key = UrlKey.of("http://h/u/p/102")!!
        assertFalse(key.split('/').contains("u") || key.split('/').contains("p"))
    }

    @Test fun `detected credentials are dropped from any path`() {
        val cred = DetectedCredentials("http://h", "alice", "pa ss")
        assertEquals("hls/1.m3u8", UrlKey.of("http://h/alice/pa%20ss/hls/1.m3u8", cred))
        assertEquals("y/1.m3u8", UrlKey.of("http://h/x/y/alice/1.m3u8", cred))
        // Three segments with a numeric last one is the short Xtream form: both leading segments go (B3).
        assertEquals("1.m3u8", UrlKey.of("http://h/x/alice/1.m3u8", cred))
    }

    @Test fun `host rotation keeps the key`() {
        assertEquals(
            UrlKey.of("http://lb1.cdn.example.net:8000/live/stream3/index.m3u8?token=abc3"),
            UrlKey.of("http://lb7.cdn.example.net:8000/live/stream3/index.m3u8?token=zzz"),
        )
    }
}
