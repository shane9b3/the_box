package app.thebox.core.setup.fakes

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.ProbeResult
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.SetupProgress
import app.thebox.core.setup.SourceSetup
import app.thebox.core.setup.StreamProbe
import app.thebox.core.setup.XtreamForm
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.core.setup.lan.ServerPhase
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * [SourceSetup] for JVM tests (WP-B's machine and page tests): answers are set per call ([loginResult], [saveResult]),
 * [progressOf] is a hot flow the test emits on, every call is recorded.
 */
class FakeSourceSetup : SourceSetup {
    var loginResult: Outcome<XtreamAccountInfo> = Outcome.Err(SourceError.Unsupported("not set"))
    var saveResult: Outcome<Long> = Outcome.Ok(1L)
    val calls = ArrayList<String>()
    val progressOf = HashMap<Long, MutableSharedFlow<SetupProgress>>()

    override suspend fun testXtream(form: XtreamForm): Outcome<XtreamAccountInfo> { calls += "testXtream(${form.server})"; return loginResult }
    override suspend fun saveXtream(form: XtreamForm, existingId: Long?): Outcome<Long> { calls += "saveXtream(${form.server},$existingId)"; return saveResult }
    override suspend fun saveM3u(form: M3uForm, existingId: Long?): Outcome<Long> { calls += "saveM3u(${form.url ?: form.fileUri},$existingId)"; return saveResult }
    override fun progress(playlistId: Long): Flow<SetupProgress> = progressOf.getOrPut(playlistId) { MutableSharedFlow(replay = 16) }
    override suspend fun applyGroupsReview(playlistId: Long, hidden: Set<String>, locked: Set<String>, unlocked: Set<String>) {
        calls += "applyGroupsReview($playlistId,h=${hidden.size},l=${locked.size},u=${unlocked.size})"
    }
    override suspend fun cancelImport(playlistId: Long) { calls += "cancelImport($playlistId)" }
}

/** [StreamProbe] with scripted answers per URL ([results]; default [fallback]). */
class FakeStreamProbe(var fallback: ProbeResult = ProbeResult(ProbeVerdict.MEDIA, 200, 262_144, 120)) : StreamProbe {
    val results = HashMap<String, ProbeResult>()
    val probes = ArrayList<Pair<String, String?>>()
    override suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult {
        probes += url to userAgent
        return results[url] ?: fallback
    }
}

/** [PhoneLink] for TV-side tests: [state] is settable, [submit] delivers a phone submission. */
class FakePhoneLink : PhoneLink {
    override val state = MutableStateFlow(LanServerState(ServerPhase.CLOSED))
    private val channel = Channel<PhoneSubmission>(Channel.UNLIMITED)
    override val submissions: Flow<PhoneSubmission> = channel.receiveAsFlow()
    val started = ArrayList<PhonePurpose>()
    val offered = ArrayList<String>()
    val statuses = ArrayList<Pair<String, Boolean>>()
    var stops = 0

    fun submit(s: PhoneSubmission) { channel.trySend(s) }
    /** SEC-S1 test: when set, the NETWORK_FOLDER collector throws this as soon as it collects. */
    var collectFailure: Throwable? = null
    override fun networkFolderSubmissions(): Flow<PhoneSubmission.NetworkFolder> =
        collectFailure?.let { f -> kotlinx.coroutines.flow.flow { throw f } } ?: super.networkFolderSubmissions()
    /** (Storage locations commit 0) The context of each start, in order (null for the M5 purposes). */
    val contexts = ArrayList<app.thebox.core.setup.lan.PhoneContext?>()
    override fun start(purpose: PhonePurpose, context: app.thebox.core.setup.lan.PhoneContext?) {
        started += purpose
        contexts += context
        state.value = LanServerState(ServerPhase.WAITING, baseUrl = "http://192.168.1.20:47100", qrUrl = "http://192.168.1.20:47100/s/token", code = "123456")
    }
    override fun stop() { stops++; state.value = LanServerState(ServerPhase.CLOSED) }
    override fun offerDownload(name: String, path: String, contentType: String) { offered += name }
    override fun reportStatus(text: String, done: Boolean) { statuses += text to done }
}
