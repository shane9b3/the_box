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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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
    /** WP-S6: how long a finished NETWORK_FOLDER session stays readable ([LanLimits.DONE_GRACE_MS]; shorter in tests). */
    private val doneGraceMs: Long = LanLimits.DONE_GRACE_MS,
) : PhoneLink {
    private val _state = MutableStateFlow(LanServerState(ServerPhase.CLOSED))
    override val state: StateFlow<LanServerState> = _state.asStateFlow()
    private val _submissions = MutableSharedFlow<PhoneSubmission>(extraBufferCapacity = 16)
    override val submissions: Flow<PhoneSubmission> = Submissions()

    /** C-N5: collectors of [networkFolderSubmissions] (StoragePages' `phoneJob`); a NETWORK_FOLDER session accepts only with one. */
    private val folderReceivers = AtomicInteger(0)
    /** C-N5: NETWORK_FOLDER submissions handed to the flow that no receiver took yet; [stop] zeroes their passwords. */
    private val untaken: MutableSet<PhoneSubmission.NetworkFolder> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private inner class Submissions : NetworkFolderSubmissions, Flow<PhoneSubmission> by _submissions.asSharedFlow() {
        override fun networkFolders(): Flow<PhoneSubmission.NetworkFolder> = flow {
            var registered = false
            try {
                // Registered once the subscription exists, so nothing accepted in between can be missed.
                _submissions.onSubscription { folderReceivers.incrementAndGet(); registered = true }
                    .filterIsInstance<PhoneSubmission.NetworkFolder>()
                    .collect { sub -> if (take(sub)) emit(sub) }
            } finally {
                if (registered) folderReceivers.decrementAndGet()
            }
        }
    }

    /** True for the first receiver of [sub] (it now owns the password); false when another one took it. */
    private fun take(sub: PhoneSubmission.NetworkFolder): Boolean = synchronized(lock) { untaken.remove(sub) }

    /** C-N5: a NETWORK_FOLDER submission nobody took still holds the only copy of its password: zero it. */
    private fun zeroUntaken() = synchronized(lock) {
        untaken.forEach { it.password?.fill('\u0000') }
        untaken.clear()
    }

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
    /**
     * D-B3: files offered while no server runs (an export that finishes before the phone screen starts the server);
     * handed to the next session's [PhoneApi], deleted by [stop] when no session takes them.
     */
    private val pendingOffers = LinkedHashMap<String, Pair<File, String>>()

    override fun start(purpose: PhonePurpose, context: PhoneContext?) {
        // D-B3: offers made before this start survive the stop of the previous session and go to the new one.
        val carried = synchronized(lock) { LinkedHashMap(pendingOffers).also { pendingOffers.clear() } }
        stopNow()
        synchronized(lock) {
            this.purpose = purpose
            received = false
            signInStatus = null; signInOk = null
            _state.value = LanServerState(ServerPhase.STARTING)
            val address = finder.find()
            if (address == null || !LanAddressPolicy.isUsable(address)) {
                pendingOffers.putAll(carried) // kept for a retry of the screen; stop() deletes them
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
                pendingOffers.putAll(carried)
                _state.value = LanServerState(ServerPhase.FAILED, failure = LanFailure.BIND_FAILED)
                return
            }
            val hostPort = "$address:${srv.port}"
            spoolDir.mkdirs()
            // D-L4: a submission nobody collects is refused (503) instead of being dropped after a 202.
            // WP-S6 (§7.4): one session = one browse context; a new context is a new start (new server, new code).
            // C-N5 (closure fix pass): a NETWORK_FOLDER session counts only its own receivers, not any collector.
            val canAccept: () -> Boolean = if (purpose == PhonePurpose.NETWORK_FOLDER) {
                { folderReceivers.get() > 0 }
            } else {
                { _submissions.subscriptionCount.value > 0 }
            }
            val a = PhoneApi(
                s, purpose, hostPort, assets, spoolDir, freeBytes, onSubmission = ::submitted,
                canAccept = canAccept,
                folderContext = if (purpose == PhonePurpose.NETWORK_FOLDER) context else null,
            )
            for ((name, offer) in carried) a.offerDownload(name, offer.first, offer.second)
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

    /**
     * WP-S6 (§7.4): once a NETWORK_FOLDER sign-in succeeded (or failed for the last time) the TV leaves this screen at
     * once, but the phone should still read the result: the server then stays up until the grace that [reportStatus]
     * scheduled ends ([LanLimits.DONE_GRACE_MS]). It accepts no submission in that time (410).
     */
    override fun stop() {
        zeroUntaken() // C-N5: the screen is gone, so nobody will take it now
        val linger = synchronized(lock) { server != null && purpose == PhonePurpose.NETWORK_FOLDER && api?.folderFinished == true }
        if (!linger) stopNow()
    }

    private fun stopNow() {
        zeroUntaken()
        val srv: LanHttpServerImpl?
        synchronized(lock) {
            srv = server
            server = null
            session?.end(null)
            session = null
            // D-S2: files still on offer go with the session (and offers that never reached one).
            api?.dropOffers()
            api = null
            pendingOffers.values.forEach { it.first.delete() }
            pendingOffers.clear()
            ticker?.cancel()
            ticker = null
        }
        srv?.stop()
        spoolDir.listFiles()?.forEach { it.delete() }
        if (_state.value.phase != ServerPhase.FAILED) _state.value = LanServerState(ServerPhase.CLOSED)
    }

    override fun offerDownload(name: String, path: String, contentType: String) {
        synchronized(lock) {
            val a = api
            // D-B3: no server yet (the export finished before the screen started it): kept for the next start.
            if (a != null) a.offerDownload(name, File(path), contentType) else pendingOffers[name] = File(path) to contentType
        }
    }

    override fun reportStatus(text: String, done: Boolean) {
        val a = api ?: return
        if (purpose == PhonePurpose.NETWORK_FOLDER) {
            reportSignIn(a, text, done)
            return
        }
        a.reportStatus(text, done)
        if (done && purpose == PhonePurpose.SETUP) {
            val s = session
            scope.launch {
                delay(LanLimits.DONE_GRACE_MS)
                if (session === s) stopNow()
            }
        }
    }

    /**
     * WP-S6 (§7.4): for NETWORK_FOLDER, [reportStatus] is the sign-in result. Success ends the session after the grace
     * (one shot); a failure lets the phone send again, up to [LanLimits.PAIR_MAX_WRONG], after which the session ends as
     * [LanFailure.TOO_MANY_WRONG_CODES] with [LanServerState.signInsExhausted] set (2026-11-01: that flag, not
     * [LanServerState.pairedFrom], tells it from wrong pairing codes, which a paired phone can now enter too).
     */
    private fun reportSignIn(a: PhoneApi, text: String, done: Boolean) {
        if (a.folderFinished) return
        a.reportStatus(text, done)
        signInStatus = text; signInOk = done // U-N1: the TV can show it too
        val s = session ?: return
        when {
            a.folderSignedIn -> scope.launch {
                delay(doneGraceMs)
                if (session === s) stopNow()
            }
            a.folderExhausted -> scope.launch {
                delay(doneGraceMs)
                if (session === s) s.end(LanFailure.TOO_MANY_WRONG_CODES)
            }
            else -> {
                received = false // back to "connected": the phone may send again
                publish()
            }
        }
    }

    /** (U-N1) The last NETWORK_FOLDER sign-in result of this session; reset with each session. */
    @Volatile private var signInStatus: String? = null
    @Volatile private var signInOk: Boolean? = null

    private fun submitted(s: PhoneSubmission) {
        received = true
        if (s is PhoneSubmission.NetworkFolder) synchronized(lock) { untaken += s } // C-N5: until a receiver takes it
        publish()
        if (!_submissions.tryEmit(s)) BoxLog.w(TAG, "submission dropped: no collector kept up")
    }

    private fun fail(s: PairingSession, failure: LanFailure) {
        var signInsExhausted = false
        val srv = synchronized(lock) {
            if (session !== s) return
            signInsExhausted = failure == LanFailure.TOO_MANY_WRONG_CODES && api?.folderExhausted == true
            api?.dropOffers() // D-S2
            server.also { server = null; session = null; api = null; ticker?.cancel(); ticker = null }
        }
        srv?.stop()
        spoolDir.listFiles()?.forEach { it.delete() }
        // WP-S6: pairedFrom stays (the TV shows who was connected); signInsExhausted (2026-11-01) tells a NETWORK_FOLDER
        // page's "too many failed sign-ins" from too many wrong pairing codes (PhoneScreenModel.tooManySignIns).
        _state.value = LanServerState(
            ServerPhase.FAILED, pairedFrom = s.pairedFrom, failure = failure, lastSignInStatus = signInStatus, lastSignInOk = signInOk,
            signInsExhausted = signInsExhausted,
        )
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
            lastSignInStatus = signInStatus,
            lastSignInOk = signInOk,
        )
    }

    private companion object {
        const val TAG = "PhoneLink"
        const val STOP_GRACE_MS = 250L
    }
}
