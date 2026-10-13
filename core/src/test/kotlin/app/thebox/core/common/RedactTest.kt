package app.thebox.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactTest {
    @Test
    fun xtreamPathCredentialsAreMasked() {
        assertEquals("GET http://h:8080/live/***/***/102.ts", Redact.text("GET http://h:8080/live/user1/pass1/102.ts"))
        assertEquals("http://h/movie/***/***/7.mkv", Redact.text("http://h/movie/u/p/7.mkv"))
        assertEquals("http://h/series/***/***/9.mp4", Redact.text("http://h/series/u/p/9.mp4"))
        assertEquals(
            "http://h/timeshift/***/***/120/2026-10-13:19-30/102.ts",
            Redact.text("http://h/timeshift/u/p/120/2026-10-13:19-30/102.ts"),
        )
    }

    @Test
    fun queryStringsAreMasked() {
        assertEquals("fetch http://h/get.php?*** done", Redact.text("fetch http://h/get.php?username=U&password=P&type=m3u_plus done"))
        assertEquals("http://h/xmltv.php?***", Redact.text("http://h/xmltv.php?username=U&password=P"))
        // A URL without a query is left alone.
        assertEquals("http://h/epg.xml.gz", Redact.text("http://h/epg.xml.gz"))
    }

    @Test
    fun userInfoIsMasked() {
        assertEquals("http://***@h.example/list.m3u", Redact.text("http://bob:s3cret@h.example/list.m3u"))
    }

    @Test
    fun headersAreMasked() {
        assertEquals("Authorization=*** next", Redact.text("Authorization: Bearer-abc123 next"))
        assertEquals("cookie=***; other", Redact.text("cookie=sid123; other"))
        assertEquals("token=***", Redact.text("token=abcdef"))
    }

    @Test
    fun registeredSecretsAreMaskedAnywhere() {
        val secret = "zq-unique-secret-91"
        assertFalse(Redact.text("short-form http://h/$secret/x/102").contains("***"))
        Redact.registerSecret(secret)
        val out = Redact.text("short-form http://h/$secret/x/102 and again $secret")
        assertFalse(out.contains(secret))
        assertTrue(out.contains("http://h/***/x/102"))
    }

    @Test
    fun tooShortSecretsAreIgnored() {
        Redact.registerSecret("ab")
        Redact.registerSecret(null)
        assertEquals("tab cab", Redact.text("tab cab"))
    }
}
