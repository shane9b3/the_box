package app.thebox.core.playback.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CredentialExpanderTest {
    private val x = CredentialExpanderImpl()

    // Every character the matrix names: / ? # % space @ + & and non-ASCII.
    private val nasty = "a/b?c#d%e f@g+h&i€"
    private val nastyPath = "a%2Fb%3Fc%23d%25e%20f@g+h&i%E2%82%AC"
    private val nastyQuery = "a%2Fb%3Fc%23d%25e%20f%40g%2Bh%26i%E2%82%AC"

    @Test
    fun pathPositionUsesPathSegmentEncoding() {
        assertEquals("http://h/live/$nastyPath/$nastyPath/1.ts", x.expandCredentials("http://h/live/{{u}}/{{p}}/1.ts", nasty, nasty))
    }

    @Test
    fun queryPositionUsesQueryEncoding() {
        assertEquals(
            "http://h/get.php?username=$nastyQuery&password=$nastyQuery",
            x.expandCredentials("http://h/get.php?username={{u}}&password={{p}}", nasty, nasty),
        )
    }

    @Test
    fun positionIsDecidedByTheFirstLiteralQuestionMark() {
        // The '?' inside the substituted value does not switch later tokens to query encoding.
        assertEquals("http://h/a%3Fb/x@y?p=x%40y", x.expandCredentials("http://h/{{u}}/{{p}}?p={{p}}", "a?b", "x@y"))
    }

    @Test
    fun plainValuesAreTheSameInBothPositions() {
        assertEquals("http://h/user/pass?u=user&p=pass", x.expandCredentials("http://h/{{u}}/{{p}}?u={{u}}&p={{p}}", "user", "pass"))
    }

    @Test
    fun singlePassNeverRescansSubstitutedValues() {
        // A password that looks like placeholders stays literal (encoded for its position, never expanded).
        val pass = "{utc}{{p}}}}"
        val url = x.expandCredentials("http://h/live/{{u}}/{{p}}/1.ts?k={{p}}", "{{p}}", pass)
        assertEquals("http://h/live/%7B%7Bp%7D%7D/%7Butc%7D%7B%7Bp%7D%7D%7D%7D/1.ts?k=%7Butc%7D%7B%7Bp%7D%7D%7D%7D", url)
        assertEquals("Bearer {utc}{{p}}}} {{p}}", x.expandHeaderValue("Bearer {{p}} {{u}}", "{{p}}", pass))
    }

    @Test
    fun headerValuesAreRaw() {
        assertEquals("user=$nasty;pass=$nasty", x.expandHeaderValue("user={{u}};pass={{p}}", nasty, nasty))
    }

    @Test
    fun unknownAndUnclosedPlaceholdersAreLeftAsWritten() {
        assertEquals("http://h/{{x}}/user/{{p", x.expandCredentials("http://h/{{x}}/{{u}}/{{p", "user", "pass"))
        assertEquals("{{x}} {utc}", x.expandHeaderValue("{{x}} {utc}", "u", "p"))
    }

    @Test
    fun nullCredentialsLeaveTheTokens() {
        assertEquals("http://h/{{u}}/{{p}}/1", x.expandCredentials("http://h/{{u}}/{{p}}/1", null, null))
    }

    @Test
    fun nonSecretPassExpandsBaseAndExtOnly() {
        val t = "{{base}}/live/{{u}}/{{p}}/42.{{ext}}"
        assertEquals("http://host.example:8080/live/{{u}}/{{p}}/42.m3u8", x.expandNonSecret(t, " host.example:8080/player_api.php ", "m3u8"))
        assertEquals("https://h/live/{{u}}/{{p}}/42.ts", x.expandNonSecret(t, "HTTPS://h", ".ts"))
        assertEquals("{{base}}/live/{{u}}/{{p}}/42.{{ext}}", x.expandNonSecret(t, null, null))
    }

    @Test
    fun nonSecretPassDoesNotTouchCredentialValuesThatContainPlaceholders() {
        // Order rule: {{base}}/{{ext}} first, credentials last. A base value is never re-scanned either.
        val once = x.expandNonSecret("{{base}}/{{u}}", "http://h", null)
        val url = x.expandCredentials(once, "{{base}}", "p")
        assertEquals("http://h/%7B%7Bbase%7D%7D", url)
        assertFalse(url.contains("http://h/http"))
    }
}
