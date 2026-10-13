package app.thebox.core.refresh

import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.RawCatchup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CredentialTemplaterTest {
    private val cred = DetectedCredentials("http://h:8080", "u@x", "p#w")

    private fun vote(xc: Int, other: Int, minority: Int = 0, base: String = "http://h:8080"): DetectedCredentials? {
        val v = CredentialVote()
        repeat(xc) { v.offer("$base/live/u%40x/p%23w/${100 + it}.ts") }
        repeat(minority) { v.offer("$base/live/other/pw/${900 + it}.ts") }
        repeat(other) { v.offer("http://cdn.example.com/hls/c$it/index.m3u8") }
        repeat(5) { v.offer("udp://@239.0.0.$it:1234") } // non-http entries are not voters
        return v.result()
    }

    @Test fun `vote - 80 percent Xtream-shaped is enough, 79 is not`() {
        assertNotNull(vote(xc = 80, other = 20))
        assertNull(vote(xc = 79, other = 21))
    }

    @Test fun `vote - the pair must hold 95 percent of the parsed URLs`() {
        assertEquals(DetectedCredentials("http://h:8080", "u@x", "p#w"), vote(xc = 95, other = 0, minority = 5))
        assertNull(vote(xc = 94, other = 0, minority = 6))
    }

    @Test fun `vote - majority base of the winning pair, short form counts`() {
        val v = CredentialVote()
        repeat(3) { v.offer("http://a:80/u/p/$it") }
        repeat(5) { v.offer("http://b:80/live/u/p/$it.ts") }
        assertEquals(DetectedCredentials("http://b:80", "u", "p"), v.result())
        assertNull(CredentialVote().result())
    }

    @Test fun `stream URLs with the pair get u and p templates, host and extension stay`() {
        assertEquals("http://h:8080/live/{{u}}/{{p}}/101.ts", CredentialTemplater.templateUrl("http://h:8080/live/u%40x/p%23w/101.ts", cred))
        assertEquals("http://other:80/{{u}}/{{p}}/102", CredentialTemplater.templateUrl("http://other:80/u%40x/p%23w/102", cred))
        assertEquals("http://h:8080/movie/{{u}}/{{p}}/7.mkv?x=1", CredentialTemplater.templateUrl("http://h:8080/movie/u%40x/p%23w/7.mkv?x=1", cred))
    }

    @Test fun `other URLs are stored as they are`() {
        val minority = "http://h:8080/live/other/pw/101.ts"
        assertEquals(minority, CredentialTemplater.templateUrl(minority, cred))
        assertEquals("http://cdn/x/index.m3u8", CredentialTemplater.templateUrl("http://cdn/x/index.m3u8", cred))
    }

    @Test fun `B3 - catchup-source and attribute values are templated too`() {
        val c = ParsedChannel(
            kind = EntryKind.LIVE, name = "N", url = "http://h:8080/live/u%40x/p%23w/101.ts", position = 0,
            catchup = RawCatchup(type = "xc", source = "http://h:8080/timeshift/u%40x/p%23w/{duration}/{Y}-{m}-{d}:{H}-{M}/101.ts"),
            attributes = mapOf(
                "epg" to "http://h:8080/xmltv.php?username=u%40x&password=p%23w&x=1",
                "plain" to "nothing here",
                "dir" to "http://h/u@x/p#w/file",
            ),
        )
        val t = CredentialTemplater.apply(c, cred)
        assertEquals("http://h:8080/live/{{u}}/{{p}}/101.ts", t.url)
        assertEquals("http://h:8080/timeshift/{{u}}/{{p}}/{duration}/{Y}-{m}-{d}:{H}-{M}/101.ts", t.catchup.source)
        assertEquals("http://h:8080/xmltv.php?username={{u}}&password={{p}}&x=1", t.attributes["epg"])
        assertEquals("nothing here", t.attributes["plain"])
        assertEquals(101L, t.xtreamStreamId, "the id of a voted Xtream URL is recorded for catch-up")
        assertEquals("ts", t.containerExtension)
        assertFalse(CredentialTemplater.leaks(t.copy(attributes = t.attributes - "dir"), cred))
    }

    @Test fun `no credentials - the channel is returned untouched`() {
        val c = ParsedChannel(kind = EntryKind.LIVE, name = "N", url = "http://h/live/a/b/1.ts", position = 0)
        assertSame(c, CredentialTemplater.apply(c, null))
        val unrelated = ParsedChannel(kind = EntryKind.LIVE, name = "N", url = "http://cdn/x.m3u8", position = 0)
        assertSame(unrelated, CredentialTemplater.apply(unrelated, cred))
        assertTrue(CredentialTemplater.templateValue("http://h/a/u%40x/p%23w/b", cred).contains("{{u}}/{{p}}"))
    }
}
