package app.thebox.data.setup

import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.lan.LanFailure
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.setup.lan.ServerPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone page's server session (docs/design/m5-setup-settings.md §2.5, §3.5). **Commit-0 stub (WP-D replaces the
 * body):** [start] reports FAILED with NO_LAN_ADDRESS; no socket is ever opened.
 */
@Singleton
class PhoneLinkImpl @Inject constructor() : PhoneLink {
    private val _state = MutableStateFlow(LanServerState(ServerPhase.CLOSED))
    override val state: StateFlow<LanServerState> = _state.asStateFlow()
    override val submissions: Flow<PhoneSubmission> = emptyFlow()
    override fun start(purpose: PhonePurpose) {
        _state.value = LanServerState(ServerPhase.FAILED, failure = LanFailure.NO_LAN_ADDRESS)
    }
    override fun stop() {
        _state.value = LanServerState(ServerPhase.CLOSED)
    }
    override fun offerDownload(name: String, path: String, contentType: String) = Unit
    override fun reportStatus(text: String, done: Boolean) = Unit
}
