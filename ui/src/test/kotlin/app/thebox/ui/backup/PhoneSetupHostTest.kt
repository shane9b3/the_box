package app.thebox.ui.backup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.tree.PhoneBackupFlow
import app.thebox.core.setup.fakes.FakePhoneLink
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.ServerPhase
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U-S7 (M5 code review 2026-10-16, Blocker; adopted from the shell reviewer's `phoneServerStopsOnStop`): §3.5.2 lifetime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-xhdpi")
class PhoneSetupHostTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(phone: FakePhoneLink) = PhoneSetupViewModel(
        phone,
        object : PhoneBackupFlow { override fun attach(purpose: PhonePurpose) = Unit; override fun detach() = Unit },
        object : LanAddressFinder { override fun find() = "192.168.1.20"; override fun vpnActive() = false },
    )

    /** "Lifetime … stops … on app stop (ON_STOP)", and opens again (a fresh code) on ON_START. */
    @Test
    fun phoneServerStopsOnStop() {
        val phone = FakePhoneLink()
        val vm = vm(phone)
        rule.setContent { BoxTheme { PhoneSetupHost(vm, PhonePurpose.SETUP, clock = { 0L }) } }
        rule.waitForIdle()
        assertEquals(listOf(PhonePurpose.SETUP), phone.started)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // Home, standby, HDMI off
        rule.waitForIdle()
        assertTrue("the LAN server still runs after ON_STOP (stops=${phone.stops}, state=${phone.state.value.phase})", phone.stops >= 1)
        assertEquals(ServerPhase.CLOSED, phone.state.value.phase)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals("ON_START opens the server again (new code)", listOf(PhonePurpose.SETUP, PhonePurpose.SETUP), phone.started)
    }
}
