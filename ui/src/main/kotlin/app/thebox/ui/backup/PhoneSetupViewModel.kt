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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.tree.PhoneBackupFlow
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneContext
import app.thebox.core.setup.lan.PhoneContextProvider
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
    /** WP-S6 (docs/design/m6-storage-locations.md §7.4): what a NETWORK_FOLDER page knows for its browse token. */
    private val folderContext: PhoneContextProvider = PhoneContextProvider { null },
) : ViewModel() {
    val server: StateFlow<LanServerState> get() = phone.state
    private val _screen = MutableStateFlow(PhoneScreenState())
    val screen: StateFlow<PhoneScreenState> = _screen.asStateFlow()
    private var purpose: PhonePurpose? = null
    private var browse: Long? = null

    /** Shown at (the isolation hint's clock) and whether a VPN is on, read once per opening. */
    var shownAtMs: Long = 0L
        private set
    var vpn: Boolean = false
        private set

    /**
     * [browse] (WP-S6, §7.4): the `StoragePages` browse token of a NETWORK_FOLDER sign-in. The session is bound to it:
     * the page shows that browse's [PhoneContext] (never a credential), and another token starts a new session.
     */
    fun open(purpose: PhonePurpose, nowMs: Long, browse: Long? = null) {
        if (this.purpose == purpose && this.browse == browse) return
        this.purpose = purpose
        this.browse = browse
        shownAtMs = nowMs
        vpn = runCatching { finder.vpnActive() }.getOrDefault(false)
        _screen.value = PhoneScreenState()
        backupFlow.attach(purpose)
        val context = if (purpose == PhonePurpose.NETWORK_FOLDER && browse != null) {
            runCatching { folderContext.context(browse) }.getOrNull()
        } else {
            null
        }
        phone.start(purpose, context)
    }

    fun close() {
        if (purpose == null) return
        purpose = null
        browse = null
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
 * Draws [PhoneSetupScreen] for [purpose] while composed: starts the session on ON_START and stops it on ON_STOP or
 * when the screen leaves (U-S7), keeps the screen on (S4) and ticks
 * a 1 s clock for the isolation hint. WP-A's settings screen places it for `SettingsRoute.PhoneSetup`.
 */
@Composable
fun PhoneSetupHost(
    vm: PhoneSetupViewModel,
    purpose: PhonePurpose,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
    /** WP-S6: `SettingsRoute.PhoneSetup.browse` (NETWORK_FOLDER); null for every other purpose. */
    browse: Long? = null,
) {
    val view: View = LocalView.current
    DisposableEffect(vm, purpose, browse) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // U-S7 (code review 2026-10-16, Blocker): §3.5.2 lifetime: the LAN server runs only while the screen is shown and
    // the app is started. ON_STOP (Home, standby, HDMI off) or leaving the screen stops it; ON_START opens it again,
    // which shows a fresh pairing code (no socket stays bound on the LAN while the app is in the background).
    LifecycleStartEffect(vm, purpose, browse) {
        vm.open(purpose, clock(), browse)
        onStopOrDispose { vm.close() }
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
