package app.thebox.ui.setup

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.SourceError
import app.thebox.core.setup.SetupAction
import app.thebox.core.setup.SetupResultMapper
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.pages.PageAction
import app.thebox.core.setup.pages.PageHost
import app.thebox.core.setup.pages.WelcomePage
import app.thebox.core.settings.PageModel
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.ui.theme.BoxDesignScale
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `strings_setup.xml` covers every [SetupText] key and formats its arguments; Welcome draws its four cards (§3.4.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SetupUiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val strings = AndroidSetupStrings(ctx)

    @Test
    fun everyKeyResolvesAndFormatsItsArguments() {
        val ids = HashSet<Int>()
        for (key in SetupText.entries) {
            ids += AndroidSetupStrings.resId(key)
            val plain = strings.text(key)
            assertTrue("$key", plain.isNotBlank() || key == SetupText.CARD_TRIAL_SUFFIX)
            val formatted = strings.text(key, "A", "B", "C")
            assertFalse("$key keeps no raw placeholder: $formatted", Regex("%\\d\\$").containsMatchIn(formatted))
        }
        assertEquals("one string per key", SetupText.entries.size, ids.size)
        assertEquals("Can't find the server panel.example.com", strings.text(SetupText.CARD_DNS_HOST, "panel.example.com"))
        assertEquals("1,912 of 2,140 matched (89 %)", strings.text(SetupText.STAGE_COUNT_MATCHED, "1,912", "2,140", 89))
    }

    @Test
    fun everyResultCardReadsAsEnglishWithAReasonAndANextStep() {
        val m = SetupResultMapper(strings)
        for (e in listOf(SourceError.Http(403), SourceError.Network(app.thebox.core.common.NetworkKind.DNS), SourceError.Auth(app.thebox.core.common.AuthReason.BAD_CREDENTIALS))) {
            val c = m.error(e, SetupResultMapper.Context(host = "panel.example.com"))
            assertFalse(c.headline.contains("CARD_"))
            assertTrue(c.lines.single().endsWith("."))
        }
        for (a in SetupAction.entries) assertTrue(m.actionLabel(a).isNotBlank())
    }

    @Test
    fun welcomeDrawsTheFourCardsAndTheNote() {
        var page: PageModel? = null
        WelcomePage(depsForWelcome()).apply {
            host = object : PageHost {
                override fun publish(page0: PageModel) { page = page0 }
                override fun act(action: PageAction) = Unit
            }
            start()
        }
        rule.setContent { BoxTheme { BoxDesignScale { WelcomeScreen(page!!, cursor = 0) } } }
        rule.onNodeWithTag(WELCOME_TAG).assertExists()
        rule.onNodeWithText("Welcome to The Box").assertExists()
        for (i in 0 until 4) rule.onNodeWithTag("$WELCOME_CARD_TAG$i").assertExists()
        rule.onNodeWithText("Set up from phone").assertExists()
        rule.onNodeWithText("Restore backup").assertExists()
        rule.onNodeWithText("The Box ships no channels or playlists. Use it with a service you already have.").assertExists()
    }

    private fun depsForWelcome() = SetupPageDeps(
        strings = strings, normalizer = app.thebox.core.setup.SourceInputNormalizerImpl(),
        setup = app.thebox.core.setup.fakes.FakeSourceSetup(), admin = Unused.admin, groups = Unused.groups, matching = Unused.matching,
        store = app.thebox.core.settings.fakes.FakeSettingsStore(),
        parental = kotlinx.coroutines.flow.MutableStateFlow(
            app.thebox.core.parental.ParentalState(false, false, app.thebox.core.parental.ParentalPolicy()),
        ),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), nowMs = { 0L },
    )

    /** Ports the Welcome page never touches. */
    private object Unused {
        val admin: app.thebox.core.setup.PlaylistAdmin = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(app.thebox.core.setup.PlaylistAdmin::class.java),
        ) { _, _, _ -> error("unused") } as app.thebox.core.setup.PlaylistAdmin
        val groups = object : app.thebox.core.setup.GroupsReviewSource { override suspend fun groups(playlistId: Long) = emptyList<app.thebox.core.setup.ReviewGroup>() }
        val matching: app.thebox.core.setup.GuideMatching = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(app.thebox.core.setup.GuideMatching::class.java),
        ) { _, _, _ -> error("unused") } as app.thebox.core.setup.GuideMatching
    }
}
