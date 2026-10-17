package app.thebox.ui.settings.vm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.AuthReason
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.SourceKind
import app.thebox.core.setup.SetupDraftStore
import app.thebox.core.setup.SourceInputNormalizerImpl
import app.thebox.core.setup.SourceSetup
import app.thebox.core.setup.XtreamForm
import app.thebox.core.setup.fakes.FakePhoneLink
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.ui.settings.FakeSettingsUiMachine
import app.thebox.ui.setup.AndroidSetupStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M5 code review 2026-10-16 (U-S4, U-S5 adopted from the shell reviewer's `ReviewPhoneAndDiagTest`; U-S15): the phone
 * page's setup submissions on the TV. Fake logins only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PhoneSetupBridgeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Rig(context: Context, val setup: SourceSetup = FakeSourceSetup()) {
        val test = TestScope()
        private val d = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(Job() + d)
        val phone = FakePhoneLink()
        val shell = FakeSettingsUiMachine(SettingsUiState(route = SettingsRoute.PhoneSetup(purpose = PhonePurpose.SETUP, firstRun = true)))
        val drafts = SetupDraftStore()
        val connected = ArrayList<SettingsRoute>()
        val bridge = PhoneSetupBridge(
            context, phone, setup, SourceInputNormalizerImpl(), AndroidSetupStrings(context), { shell }, scope, d, d, drafts,
            connect = { connected += it },
        )
        init { bridge.onRoute(shell.state.value.route); test.runCurrent() }
        fun submit(s: PhoneSubmission) { phone.submit(s); test.advanceUntilIdle() }
    }

    /** U-S4: §3.5.3 "POST /api/xtream … the TV runs Connect (§3.4.4)"; I-M5-2 "no playlist row" on a rejected login. */
    @Test
    fun phoneXtreamWithRejectedLoginCreatesNoPlaylist() {
        val setup = FakeSourceSetup()
        val r = Rig(context, setup)
        setup.loginResult = Outcome.Err(SourceError.Auth(AuthReason.BAD_CREDENTIALS))
        r.submit(PhoneSubmission.Xtream(XtreamForm("Main", "http://panel.example.com:8080", "user1", "not-a-real-pass")))
        assertFalse("saved without a login test: ${setup.calls}; phone saw ${r.phone.statuses}", setup.calls.any { it.startsWith("saveXtream") })
        assertTrue(setup.calls.any { it.startsWith("testXtream") })
        val (text, done) = r.phone.statuses.last()
        assertFalse("a failure keeps the phone page open (done = false): $text", done)
        // The TV opens the Xtream form filled from the phone and runs Connect there (its card offers the choices).
        val form = SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)
        assertEquals(listOf<SettingsRoute>(form), r.shell.replaced)
        assertEquals(listOf<SettingsRoute>(form), r.connected)
        assertEquals("user1", r.drafts.xtream.username)
        assertEquals("http://panel.example.com:8080", r.drafts.xtream.server)
    }

    /** U-S4: a valid login is tested, then saved; `done = true` only after the save succeeded. */
    @Test
    fun phoneXtreamWithAValidLoginIsTestedThenSaved() {
        val setup = FakeSourceSetup().apply { loginResult = Outcome.Ok(XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, measuredAtDeviceMs = 0)) }
        val r = Rig(context, setup)
        r.submit(PhoneSubmission.Xtream(XtreamForm("Main", "http://panel.example.com:8080", "user1", "pass1")))
        assertEquals(listOf("testXtream(http://panel.example.com:8080)", "saveXtream(http://panel.example.com:8080,null)"), setup.calls)
        assertEquals(false, r.phone.statuses.first().second)
        assertEquals(true, r.phone.statuses.last().second)
        assertEquals(listOf<SettingsRoute>(SettingsRoute.ImportProgress(1L, true)), r.shell.replaced)
    }

    /** U-S4: a pasted `get.php` link that carries a login is that login: tested before anything is saved. */
    @Test
    fun linkWithAnXtreamLoginRunsConnectFirst() {
        val setup = FakeSourceSetup().apply { loginResult = Outcome.Err(SourceError.Auth(AuthReason.BAD_CREDENTIALS)) }
        val r = Rig(context, setup)
        r.submit(PhoneSubmission.Link("http://panel.example.com:8080/get.php?username=user1&password=fake-pass&type=m3u_plus", "Main"))
        assertEquals(listOf("testXtream(http://panel.example.com:8080)"), setup.calls)
        assertFalse(r.phone.statuses.last().second)
    }

    /** U-S5: CLAUDE.md "errors carry a reason and a next step": the phone page got only the card's headline. */
    @Test
    fun phoneStatusCarriesTheNextStep() {
        val setup = FakeSourceSetup()
        val r = Rig(context, setup)
        setup.saveResult = Outcome.Err(SourceError.Network(NetworkKind.TIMEOUT))
        r.submit(PhoneSubmission.Link("http://panel.example.com:8080/list.m3u", "Main"))
        val (text, done) = r.phone.statuses.last()
        assertFalse(done)
        assertTrue("a next step after the reason: \"$text\"", text.contains("Check the address"))
    }

    /** U-S5: an Xtream-shaped link without its login gets a specific text, not "This is not a web address". */
    @Test
    fun linkWithoutALoginSaysUseTheXtreamTab() {
        val r = Rig(context)
        r.submit(PhoneSubmission.Link("http://panel.example.com:8080/get.php?type=m3u_plus", "Main"))
        val (text, done) = r.phone.statuses.last()
        assertFalse(done)
        assertTrue(text, text.contains("Xtream tab"))
    }

    /** U-S15: BACK while the save is in flight: the save finishes and is reported; the TV does not move to the import. */
    @Test
    fun leavingDuringSaveFinishesTheSave() {
        val gate = CompletableDeferred<Unit>()
        val inner = FakeSourceSetup().apply { loginResult = Outcome.Ok(XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, measuredAtDeviceMs = 0)) }
        var saved = false
        val slow = object : SourceSetup by inner {
            override suspend fun saveXtream(form: XtreamForm, existingId: Long?): Outcome<Long> {
                gate.await()
                saved = true
                return inner.saveXtream(form, existingId)
            }
        }
        val r = Rig(context, slow)
        r.phone.submit(PhoneSubmission.Xtream(XtreamForm("Main", "http://panel.example.com:8080", "user1", "pass1")))
        r.test.runCurrent()
        // BACK: the shell leaves the phone page while saveXtream suspends.
        r.shell.state.value = r.shell.state.value.copy(route = SettingsRoute.Section(app.thebox.core.settings.SectionId.PLAYLISTS))
        r.bridge.onRoute(r.shell.state.value.route)
        gate.complete(Unit)
        r.test.advanceUntilIdle()
        assertTrue("the save ran to the end", saved)
        assertEquals("the phone heard the result: ${r.phone.statuses}", true, r.phone.statuses.last().second)
        assertTrue("the TV stays where the user went: ${r.shell.replaced}", r.shell.replaced.isEmpty())
    }

    /** U-S15: an uploaded M3U whose save fails does not leave its kept copy in filesDir/playlists. */
    @Test
    fun aFailedUploadSaveDeletesTheKeptFile() {
        val setup = FakeSourceSetup().apply { saveResult = Outcome.Err(SourceError.Network(NetworkKind.TIMEOUT)) }
        val r = Rig(context, setup)
        val spool = java.io.File(context.cacheDir, "phone").apply { mkdirs() }.let { java.io.File(it, "up.m3u") }.apply { writeText("#EXTM3U\n") }
        val dir = java.io.File(context.filesDir, "playlists")
        dir.listFiles()?.forEach { it.delete() }
        r.submit(PhoneSubmission.M3uFileUploaded(spool.path, spool.length(), "list.m3u"))
        assertTrue(setup.calls.any { it.startsWith("saveM3u") })
        assertTrue("kept copies left: ${dir.list()?.toList()}", dir.listFiles().isNullOrEmpty())
    }
}
