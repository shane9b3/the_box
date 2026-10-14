package app.thebox.core.playback.resolve

import app.thebox.core.common.Redact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Code review 2026-10-14, N-S5: what a resolution registers with [Redact], and that the set stays bounded. */
class ResolveSecretsTest {
    @Test
    fun tenThousandResolvedArchiveUrlsKeepTheSecretSetBounded() {
        val before = Redact.secretCount()
        repeat(10_000) { i ->
            ResolveSecrets.registerUrl("http://h.example/timeshift/u/p/120/2026-10-13:19-30/$i.ts?token=tok-$i-0123456789abcdef")
        }
        val after = Redact.secretCount()
        assertTrue(after - before <= Redact.TRANSIENT_CAP, "grew by ${after - before}")
        assertTrue(after <= Redact.CREDENTIAL_CAP + Redact.TRANSIENT_CAP)
        // The most recent token is still masked; the URL as a whole was never registered.
        assertFalse(Redact.text("echo tok-9999-0123456789abcdef").contains("tok-9999"))
    }

    @Test
    fun registerUrlTakesUserinfoAndTokenLikeQueryValuesOnly() {
        val url = "http://bob:pw-123@h.example:8080/live/a.ts?token=xyz&channel=1&sig=0123456789abcdef0&quality=hd"
        assertEquals(listOf("bob", "pw-123", "xyz", "0123456789abcdef0"), ResolveSecrets.tokenComponents(url))
        assertEquals(emptyList(), ResolveSecrets.tokenComponents("http://h.example/live/u/p/1.ts"))
    }

    @Test
    fun credentialsSurviveTokenChurn() {
        ResolveSecrets.registerCredentials("churn-user-77", "churn-pass-77")
        repeat(Redact.TRANSIENT_CAP * 3) { i -> ResolveSecrets.registerUrl("http://h/x.ts?token=churn-token-$i") }
        val out = Redact.text("login churn-user-77 / churn-pass-77")
        assertFalse(out.contains("churn-user-77") || out.contains("churn-pass-77"), out)
    }
}
