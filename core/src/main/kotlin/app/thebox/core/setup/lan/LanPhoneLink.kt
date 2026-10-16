package app.thebox.core.setup.lan

import app.thebox.core.common.BoxLog
import app.thebox.core.settings.PhonePurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * [PhoneLink] over [LanHttpServerImpl] + [PairingSession] + [PhoneApi] (docs/design/m5-setup-settings.md §3.5), pure
 * JVM so the whole session is testable with a fake transport and clock; `:data`'s `PhoneLinkImpl` supplies the address
 * finder, the socket transport, the assets and the spool directory. One server per session: [start] stops a running
 * one first. The session ends on [stop], on expiry (idle/absolute, checked every [tickMs]), on the second wrong-code
 * exhaustion, and [LanLimits.DONE_GRACE_MS] after a successful SETUP report. Uploads left in [spoolDir] are deleted on
 * stop, so a consumer must move a `BackupUploaded`/`M3uFileUploaded` file out before the screen closes.
 */
class LanPhoneLink(
    private val finder: LanAddressFinder,
    private val transport: LanTransport,
    private val assets: (String) -> ByteArray?,
    private val spoolDir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val serverConfig: LanHttpServerImpl.Config = LanHttpServerImpl.Config(),
    private val randomBytes: ((ByteArray) -> Unit)? = null,
    private val tickMs: Long = 1_000L,
    private val freeBytes: (File) -> Long = { it.usableSpace },
) : PhoneLink {
    private val _state = MutableStateFlow(LanServerState(ServerPhase.CLOSED))
    override val state: StateFlow<LanServerState> = _state.asStateFlow()
    private val _submissions = MutableSharedFlow<PhoneSubmission>(extraBufferCapacity = 16)
    override val submissions: Flow<PhoneSubmission> = _submissions.asSharedFlow()

    /** Fixed port for tests and the debug route (`thebox-debug://phone?port=N`); null = [LanLimits.PHONE_PORT_PREFERRED]. */
    @Volatile var portOverride: Int? = null

    private val lock = Any()
    private var server: LanHttpServerImpl? = null
    private var session: PairingSession? = null
    private var api: PhoneApi? = null
    private var ticker: Job? = null
    private var baseUrl: String? = null
    private var purpose: PhonePurpose = PhonePurpose.SETUP
    private var received = false

    override fun start(purpose: PhonePurpose) {
        stop()
        synchronized(lock) {
            this.purpose = purpose
            received = false
            _state.value = LanServerState(ServerPhase.STARTING)
            val address = finder.find()
            if (address == null || !LanAddressPolicy.isUsable(address)) {
                _state.value = LanServerState(ServerPhase.FAILED, failure = LanFailure.NO_LAN_ADDRESS)
                return
            }
            val s = PairingSession(clock, randomBytes ?: { java.security.SecureRandom().nextBytes(it) }, onChange = ::publish)
            val srv = LanHttpServerImpl(transport, serverConfig)
            // The handler needs the bound port for its Host check, so it is created lazily on the first request.
            var handler: PhoneApi? = null
            val lazyHandler = LanHandler { req ->
                val h = synchronized(lock) { handler } ?: return@LanHandler LanHttpServerImpl.plain(503, "Starting")
                h.handle(req).also { publish() } // lastRequestAtMs drives the TV's "nothing reached the TV" hint
            }
            try {
                srv.start(LanBind(address, portOverride ?: app.thebox.core.diag.DebugOverrides.phonePort ?: LanLimits.PHONE_PORT_PREFERRED, LanLimits.HTTP_MAX_SOCKETS), lazyHandler)
            } catch (e: IOException) {
                BoxLog.w(TAG, "bind failed: ${e.javaClass.simpleName}")
                _state.value = LanServerState(ServerPhase.FAILED, failure = LanFailure.BIND_FAILED)
                return
            }
            val hostPort = "$address:${srv.port}"
            spoolDir.mkdirs()
            val a = PhoneApi(s, purpose, hostPort, assets, spoolDir, freeBytes, onSubmission = ::submitted)
            handler = a
            server = srv
            session = s
            api = a
            baseUrl = "http://$hostPort"
            ticker = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    val failure = s.checkExpiry()
                    if (failure != null) fail(s, failure)
                }
            }
        }
        publish()
    }

    override fun stop() {
        val srv: LanHttpServerImpl?
        synchronized(lock) {
            srv = server
            server = null
            session?.end(null)
            session = null
            api = null
            ticker?.cancel()
            ticker = null
        }
        srv?.stop()
        spoolDir.listFiles()?.forEach { it.delete() }
        if (_state.value.phase != ServerPhase.FAILED) _state.value = LanServerState(ServerPhase.CLOSED)
    }

    override fun offerDownload(name: String, path: String, contentType: String) {
        api?.offerDownload(name, File(path), contentType)
    }

    override fun reportStatus(text: String, done: Boolean) {
        val a = api ?: return
        a.reportStatus(text, done)
        if (done && purpose == PhonePurpose.SETUP) {
            val s = session
            scope.launch {
                delay(LanLimits.DONE_GRACE_MS)
                if (session === s) stop()
            }
        }
    }

    private fun submitted(s: PhoneSubmission) {
        received = true
        publish()
        if (!_submissions.tryEmit(s)) BoxLog.w(TAG, "submission dropped: no collector kept up")
    }

    private fun fail(s: PairingSession, failure: LanFailure) {
        val srv = synchronized(lock) {
            if (session !== s) return
            server.also { server = null; session = null; api = null; ticker?.cancel(); ticker = null }
        }
        srv?.stop()
        spoolDir.listFiles()?.forEach { it.delete() }
        _state.value = LanServerState(ServerPhase.FAILED, failure = failure)
    }

    private fun publish() {
        val s = session ?: return
        if (s.ended) {
            val f = s.failure
            // Let the worker answer the request that ended the session (410) before the server goes.
            if (f != null) scope.launch { delay(STOP_GRACE_MS); fail(s, f) }
            return
        }
        val base = baseUrl ?: return
        _state.value = LanServerState(
            phase = when {
                received -> ServerPhase.RECEIVED
                s.pairedFrom != null -> ServerPhase.PAIRED
                else -> ServerPhase.WAITING
            },
            baseUrl = base,
            qrUrl = "$base/s/${s.token}",
            code = s.code,
            pairedFrom = s.pairedFrom,
            lastRequestAtMs = s.lastRequestAtMs,
            wrongCodes = s.wrongCodes + if (s.rotated) LanLimits.PAIR_MAX_WRONG else 0,
        )
    }

    private companion object {
        const val TAG = "PhoneLink"
        const val STOP_GRACE_MS = 250L
    }
}
