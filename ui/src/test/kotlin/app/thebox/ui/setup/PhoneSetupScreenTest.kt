package app.thebox.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.backup.BackupHeader
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.setup.lan.LanFailure
import app.thebox.core.setup.lan.LanLimits
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneScreenState
import app.thebox.core.setup.lan.ServerPhase
import app.thebox.ui.backup.BackupTextsImpl
import app.thebox.ui.theme.BoxDesignScale
import app.thebox.ui.theme.BoxTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m5-setup-settings.md §3.5.1, §6.2 `PhoneSetupScreenTest`: QR present, isolation hint after the timeout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class PhoneSetupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val t0 = 1_800_000_000_000L
    private val waiting = LanServerState(
        ServerPhase.WAITING, baseUrl = "http://192.168.1.20:47100", qrUrl = "http://192.168.1.20:47100/s/AbCdEfGhIjKlMnOpQrStUv", code = "482913",
    )

    private fun ui(server: LanServerState = waiting, now: Long = t0, vpn: Boolean = false, purpose: PhonePurpose = PhonePurpose.SETUP, screen: PhoneScreenState = PhoneScreenState()) =
        PhoneSetupUi(purpose, server, screen, shownAtMs = t0, nowMs = now, vpn = vpn)

    @Test
    fun showsTheQrTheTypedUrlAndTheCodeWithThePlainHttpFooter() {
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(ui()) } } }
        rule.onNodeWithTag(PHONE_QR_TAG).assertExists()
        rule.onNodeWithText("http://192.168.1.20:47100").assertExists()
        rule.onNodeWithText("482 913").assertExists()
        rule.onNodeWithText("Waiting for your phone…").assertExists()
        rule.onNodeWithTag(PHONE_FOOTER_TAG).assertExists()
        rule.onNodeWithText("not encrypted", substring = true).assertExists()
        rule.onNodeWithTag(PHONE_HINT_TAG).assertDoesNotExist()
    }

    @Test
    fun isolationHintReplacesTheFooterAfterTheTimeoutWithAFakeClock() {
        var state by mutableStateOf(ui(now = t0 + LanLimits.NO_REQUEST_HINT_MS - 1))
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(state) } } }
        rule.onNodeWithTag(PHONE_HINT_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PHONE_TYPE_ON_TV_TAG).assertDoesNotExist()
        state = ui(now = t0 + LanLimits.NO_REQUEST_HINT_MS)
        rule.onNodeWithTag(PHONE_HINT_TAG).assertExists()
        rule.onNodeWithTag(PHONE_FOOTER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PHONE_TYPE_ON_TV_TAG).assertExists()
        // Any request at all means the network works: no hint.
        state = ui(server = waiting.copy(lastRequestAtMs = t0 + 5), now = t0 + LanLimits.NO_REQUEST_HINT_MS * 2)
        rule.onNodeWithTag(PHONE_HINT_TAG).assertDoesNotExist()
    }

    @Test
    fun vpnShowsItsLineAndTheTypeOnTvRowAtOnce() {
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(ui(vpn = true)) } } }
        rule.onNodeWithText("A VPN app is on", substring = true).assertExists()
        rule.onNodeWithTag(PHONE_TYPE_ON_TV_TAG).assertExists()
    }

    @Test
    fun pairedAndFailedStates() {
        var state by mutableStateOf(ui(server = waiting.copy(phase = ServerPhase.PAIRED, pairedFrom = "192.168.1.34", lastRequestAtMs = t0)))
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(state) } } }
        rule.onNodeWithText("Phone connected: 192.168.1.34").assertExists()
        state = ui(server = LanServerState(ServerPhase.FAILED, failure = LanFailure.NO_LAN_ADDRESS))
        rule.onNodeWithText("not on a home network", substring = true).assertExists()
        rule.onNodeWithTag(PHONE_QR_TAG).assertDoesNotExist()
    }

    /** U-N1 (closure fix pass): a failed phone sign-in is drawn on the TV under the status line; a success is not. */
    @Test
    fun aFailedPhoneSignInShowsOnTheTv() {
        val refused = "NAS didn't accept that user name or password. Check them and send again."
        val paired = waiting.copy(phase = ServerPhase.PAIRED, pairedFrom = "192.168.1.34", lastRequestAtMs = t0)
        var state by mutableStateOf(ui(server = paired.copy(lastSignInStatus = refused, lastSignInOk = false), purpose = PhonePurpose.NETWORK_FOLDER))
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(state) } } }
        rule.onNodeWithTag(PHONE_SIGN_IN_FAILED_TAG).assertExists()
        rule.onNodeWithText(refused).assertExists()
        // The phone sent again: the TV is signing in, the old reason goes.
        state = ui(server = paired.copy(phase = ServerPhase.RECEIVED, lastSignInStatus = refused, lastSignInOk = false), purpose = PhonePurpose.NETWORK_FOLDER)
        rule.onNodeWithTag(PHONE_SIGN_IN_FAILED_TAG).assertDoesNotExist()
        state = ui(server = paired.copy(lastSignInStatus = "Signed in to NAS.", lastSignInOk = true), purpose = PhonePurpose.NETWORK_FOLDER)
        rule.onNodeWithTag(PHONE_SIGN_IN_FAILED_TAG).assertDoesNotExist()
        // Too many failed sign-ins: the reason stays under the ending.
        state = ui(
            server = LanServerState(ServerPhase.FAILED, pairedFrom = "192.168.1.34", failure = LanFailure.TOO_MANY_WRONG_CODES, lastSignInStatus = refused, lastSignInOk = false),
            purpose = PhonePurpose.NETWORK_FOLDER,
        )
        rule.onNodeWithText(refused).assertExists()
        // Other purposes never show it.
        state = ui(server = paired.copy(lastSignInStatus = refused, lastSignInOk = false), purpose = PhonePurpose.SETUP)
        rule.onNodeWithTag(PHONE_SIGN_IN_FAILED_TAG).assertDoesNotExist()
    }

    @Test
    fun cantScanDrawsTheUrlAndCodeLargeInsteadOfTheQr() {
        rule.setContent { BoxTheme { BoxDesignScale { PhoneSetupScreen(ui(screen = PhoneScreenState(large = true))) } } }
        rule.onNodeWithTag(PHONE_QR_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PHONE_CODE_TAG).assertExists()
        rule.onNodeWithText("http://192.168.1.20:47100").assertExists()
    }

    @Test
    fun qrBitmapEncodesWithSharpModules() {
        val b = qrBitmap(waiting.qrUrl!!, 400)
        assertEquals(400, b.width)
        // Corner of the quiet zone is white, a finder pattern pixel is black.
        assertEquals(-0x1, b.getPixel(0, 0))
        val dark = (0 until 400).any { x -> b.getPixel(x, 200) == -0x1000000 }
        assertTrue(dark)
    }

    @Test
    fun everyBackupTextHasAResourceThatFormats() {
        val texts = BackupTextsImpl(ApplicationProvider.getApplicationContext())
        for (k in BackupText.entries) {
            val s = texts.get(k, 1, 2, 3, 4, 5)
            assertTrue("$k", s.isNotBlank())
            assertTrue("$k left a format specifier: $s", "%1$" !in s)
        }
        assertNotEquals(BackupHeader.FORMAT, texts.get(BackupText.FAIL_NOT_A_BACKUP))
    }
}
