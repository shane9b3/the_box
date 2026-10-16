package app.thebox.ui.backup

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.tree.PhoneBackupFlow
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneScreenEffect
import app.thebox.core.setup.lan.PhoneScreenModel
import app.thebox.core.setup.lan.PhoneScreenState
import app.thebox.ui.setup.PhoneSetupScreen
import app.thebox.ui.setup.PhoneSetupUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The phone screen's session (docs/design/m5-setup-settings.md §3.5): starts the server for a purpose, attaches the
 * backup side of the page (RESTORE / BACKUP_DOWNLOAD / SETUP's Restore tab), and stops both when the screen goes.
 * Keys: [act] runs [PhoneScreenModel] and returns the effect WP-A's machine performs (TYPE_ON_TV → AddSource, LEAVE →
 * pop the route). WP-B's setup flow collects `PhoneLink.submissions` for the setup kinds itself.
 */
@HiltViewModel
class PhoneSetupViewModel @Inject constructor(
    private val phone: PhoneLink,
    private val backupFlow: PhoneBackupFlow,
    private val finder: LanAddressFinder,
) : ViewModel() {
    val server: StateFlow<LanServerState> get() = phone.state
    private val _screen = MutableStateFlow(PhoneScreenState())
    val screen: StateFlow<PhoneScreenState> = _screen.asStateFlow()
    private var purpose: PhonePurpose? = null

    /** Shown at (the isolation hint's clock) and whether a VPN is on, read once per opening. */
    var shownAtMs: Long = 0L
        private set
    var vpn: Boolean = false
        private set

    fun open(purpose: PhonePurpose, nowMs: Long) {
        if (this.purpose == purpose) return
        this.purpose = purpose
        shownAtMs = nowMs
        vpn = runCatching { finder.vpnActive() }.getOrDefault(false)
        _screen.value = PhoneScreenState()
        backupFlow.attach(purpose)
        phone.start(purpose)
    }

    fun close() {
        if (purpose == null) return
        purpose = null
        backupFlow.detach()
        phone.stop()
    }

    fun act(action: BoxAction, typeOnTvShown: Boolean): PhoneScreenEffect? {
        val (next, effect) = PhoneScreenModel.act(_screen.value, action, typeOnTvShown)
        _screen.value = next
        if (effect == PhoneScreenEffect.LEAVE) close()
        return effect
    }

    override fun onCleared() = close()
}

/**
 * Draws [PhoneSetupScreen] for [purpose] while composed: starts/stops the session, keeps the screen on (S4) and ticks
 * a 1 s clock for the isolation hint. WP-A's settings screen places it for `SettingsRoute.PhoneSetup`.
 */
@Composable
fun PhoneSetupHost(vm: PhoneSetupViewModel, purpose: PhonePurpose, modifier: Modifier = Modifier, clock: () -> Long = System::currentTimeMillis) {
    val view: View = LocalView.current
    DisposableEffect(vm, purpose) {
        vm.open(purpose, clock())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            vm.close()
        }
    }
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(vm) {
        while (true) {
            delay(1_000)
            now = clock()
        }
    }
    val server by vm.server.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    PhoneSetupScreen(PhoneSetupUi(purpose, server, screen, vm.shownAtMs, now, vm.vpn), modifier)
}
