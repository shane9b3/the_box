package app.thebox.core.setup

import app.thebox.core.setup.lan.PhoneSubmission
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-L14 / D-L2 (M5 code review 2026-10-16): credential-bearing values print hosts only. Fake values. */
class CredentialToStringTest {
    @Test
    fun loginsNeverPrint() {
        val user = "fake-user-1"
        val pass = "fake-pass-2"
        val form = XtreamForm("Main", "http://panel.example.com:8080", user, pass)
        val printed = listOf(
            form.toString(),
            PhoneSubmission.Xtream(form).toString(),
            NormalizedInput.Xtream("http://panel.example.com:8080", user, pass, null, filledFromLink = true).toString(),
            PhoneSubmission.M3u(M3uForm("List", "http://panel.example.com/get.php?username=$user&password=$pass", null, emptyList())).toString(),
        )
        for (p in printed) {
            assertFalse(user in p || pass in p, p)
            assertTrue("panel.example.com" in p, p)
        }
    }

    /** Phase 1 flag 7 (M5 code review 2026-10-16): a link pasted on the phone can be a `get.php` URL with a login. */
    @Test
    fun phoneLinkSubmissionNeverPrintsItsLogin() {
        val user = "fake-user-3"
        val pass = "fake-pass-4"
        val p = PhoneSubmission.Link("http://panel.example.com:8080/get.php?username=$user&password=$pass&type=m3u_plus", "Main").toString()
        assertFalse(user in p || pass in p, p)
        assertTrue("panel.example.com" in p, p)
    }
}
