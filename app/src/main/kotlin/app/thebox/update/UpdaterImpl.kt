package app.thebox.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.SettingsStore
import app.thebox.core.update.ApkEntry
import app.thebox.core.update.ApkVerdict
import app.thebox.core.update.ApkVerifier
import app.thebox.core.update.ManifestVerdict
import app.thebox.core.update.ManifestVerifier
import app.thebox.core.update.RunningBuild
import app.thebox.core.update.Sha256Counter
import app.thebox.core.update.SignatureVerifier
import app.thebox.core.update.UpdateCardModel
import app.thebox.core.update.UpdateCardSource
import app.thebox.core.update.UpdateCardState
import app.thebox.core.update.UpdateDomain
import app.thebox.core.update.UpdateEndpoint
import app.thebox.core.update.UpdateFailure
import app.thebox.core.update.UpdateManifest
import app.thebox.core.update.UpdatePolicy
import app.thebox.core.update.UpdateState
import app.thebox.core.update.UpdateTimings
import app.thebox.core.update.Updater
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.DownloadClient
import app.thebox.data.di.IoDispatcher
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.StopReason
import app.thebox.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The sideload updater (docs/design/m5-setup-settings.md §3.12): check → card → download → verify → install.
 *
 * - **Check** (§3.12.2): `update.json` + `update.json.sig` from the build type's [UpdateEndpoint], `User-Agent:
 *   TheBox/{versionName}` and nothing else identifying; redirects only to the GitHub hosts of
 *   [UpdateTimings.REDIRECT_HOSTS] (release endpoints); [ManifestVerifier] in the spec's order; the accepted sequence and
 *   the check time are stored in the `update` domain. Automatic-check failures are silent ([UpdatePolicy.showsFailure]).
 * - **Download** (§3.12.3): to `filesDir/update/{versionCode}.apk.part`, free space ≥ 2 × size, SHA-256 while
 *   streaming, then renamed and checked by [ApkVerifier] (package, versionCode, signer in the manifest **and** equal to
 *   the installed signer; API 25–27 without signatures: skipped and logged, S7). Any failure deletes the file.
 * - **Install** (§3.12.4): `REQUEST_INSTALL_PACKAGES` flow (API 26+ `canRequestPackageInstalls`, else the settings
 *   screen), playback stopped, then [InstallResultReceiver]'s session.
 *
 * No URL is ever logged (the release host is public, but the debug panel URL carries no secret either: still, only
 * hosts are printed). Main-safe: every member returns at once; work runs on [appScope] with IO.
 */
@Singleton
class UpdaterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val client: OkHttpClient,
    private val store: SettingsStore,
    private val endpoints: UpdateEndpoints,
    signatures: SignatureVerifier,
    private val inspector: ApkInspector,
    private val installer: InstallResultReceiver,
    private val controller: dagger.Lazy<PlaybackController>,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : Updater, UpdateCardSource {
    private val verifier = ManifestVerifier(signatures)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var checkedThisProcess = false
    @Volatile private var offer: Pair<UpdateManifest, ApkEntry>? = null
    @Volatile private var lastManual = false
    @Volatile private var installWhenReady = false
    private var work: Job? = null

    private val laterThisProcess = MutableStateFlow(false)
    private val _notice = MutableStateFlow<String?>(null)
    override val updatedNotice: StateFlow<String?> = _notice.asStateFlow()

    override val card: StateFlow<UpdateCardState?> = run {
        var previous: UpdateCardState? = null
        combine(_state, laterThisProcess) { s, later ->
            UpdateCardModel.cardFor(s, later, previous) { f -> if (UpdatePolicy.showsFailure(f, lastManual)) failureText(f) else null }.also { previous = it }
        }.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    init {
        appScope.launch(io) { checkUpdatedNotice() }
    }

    // ---------------------------------------------------------------- Updater

    override fun check(manual: Boolean) {
        appScope.launch(io) { runCheck(manual) }
    }

    override fun download() {
        val o = offer ?: return
        if (_state.value is UpdateState.Downloading || _state.value == UpdateState.Installing) return
        work?.cancel()
        work = appScope.launch(io) { runDownload(o.first, o.second) }
    }

    override fun install() {
        val file = readyFile() ?: return
        appScope.launch(Dispatchers.Main) { runInstall(file) }
    }

    override fun skip(versionCode: Long) {
        store.update(UpdateDomain) { it.copy(skippedVersionCode = versionCode) }
        if ((_state.value as? UpdateState.Available)?.manifest?.versionCode == versionCode) _state.value = UpdateState.Idle
    }

    /** The card's Later: the card stays away until the next start; the About row still offers the release. */
    fun later() {
        laterThisProcess.value = true
    }

    /** The card's Update: download, then open the installer as soon as the file is verified. */
    fun updateNow() {
        installWhenReady = true
        when (_state.value) {
            is UpdateState.ReadyToInstall, is UpdateState.NeedsPermission -> install()
            else -> download()
        }
    }

    override fun noticeShown() {
        _notice.value = null
    }

    /** Instrumented suite only (I-M5-19): forget this process's check, offer and card state between cases. */
    @androidx.annotation.VisibleForTesting
    fun resetSession() {
        work?.cancel()
        checkedThisProcess = false
        offer = null
        installWhenReady = false
        laterThisProcess.value = false
        _state.value = UpdateState.Idle
        updateDir().listFiles()?.forEach { it.delete() }
    }

    /** Instrumented suite only: the downloaded file of the current offer (null when none or deleted). */
    @androidx.annotation.VisibleForTesting
    fun downloadedFile(): File? = offer?.let { File(updateDir(), "${it.first.versionCode}.apk") }

    /** Instrumented suite only: every file left in `filesDir/update`. */
    @androidx.annotation.VisibleForTesting
    fun updateFiles(): List<String> = updateDir().listFiles()?.map { it.name }.orEmpty()

    // ---------------------------------------------------------------- check

    /** One check (the worker awaits it). Returns the state it ended in. */
    suspend fun runCheck(manual: Boolean): UpdateState = mutex.withLock {
        withContext(io) {
            store.awaitReady()
            val now = System.currentTimeMillis()
            var prefs = store.flow(UpdateDomain).value
            if (!UpdatePolicy.shouldRun(prefs, now, manual, checkedThisProcess)) return@withContext _state.value
            val busy = _state.value
            if (busy is UpdateState.Downloading || busy == UpdateState.Installing) return@withContext busy
            checkedThisProcess = true
            lastManual = manual
            val endpoint = endpoints.current()
            if (!endpoint.enabled) {
                BoxLog.i(TAG) { "updates disabled for ${endpoint.label}" }
                return@withContext publish(if (manual) UpdateState.Failed(UpdateFailure.DISABLED, null) else UpdateState.Idle)
            }
            _state.value = UpdateState.Checking
            val (bucket, withBucket) = UpdatePolicy.bucketOf(prefs) { Random.nextInt(0, 100) }
            if (withBucket != prefs) {
                prefs = withBucket
                store.update(UpdateDomain) { it.copy(bucket = bucket) }
            }
            val fetched = try {
                val manifest = get(endpoint.manifestUrl!!, endpoint, UpdateTimings.MANIFEST_MAX_BYTES)
                val sig = get(endpoint.signatureUrl!!, endpoint, UpdateTimings.SIGNATURE_BYTES + 1)
                manifest to sig
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "manifest fetch failed: ${e.javaClass.simpleName}")
                store.update(UpdateDomain) { it.copy(lastCheckMs = now) }
                return@withContext publish(if (manual) UpdateState.Failed(UpdateFailure.NETWORK, null) else UpdateState.Idle)
            }
            val verdict = verifier.verify(fetched.first, fetched.second, endpoint, running(), prefs, bucket, manual)
            when (verdict) {
                is ManifestVerdict.Offer -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now, highestSequence = maxOf(it.highestSequence, verdict.manifest.sequence)) }
                    offer = verdict.manifest to verdict.apk
                    BoxLog.i(TAG) { "offer ${verdict.manifest.versionName} (${verdict.manifest.versionCode}) ${verdict.apk.abi}, seq ${verdict.manifest.sequence}" }
                    publish(UpdateState.Available(verdict.manifest, verdict.apk))
                }
                is ManifestVerdict.Nothing -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now, highestSequence = maxOf(it.highestSequence, verdict.manifest.sequence)) }
                    offer = null
                    BoxLog.i(TAG) { "no offer: ${verdict.reason}" }
                    publish(UpdateState.UpToDate(now))
                }
                is ManifestVerdict.Rejected -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now) }
                    BoxLog.w(TAG, "manifest rejected: ${verdict.failure} ${verdict.detail}")
                    publish(if (manual) UpdateState.Failed(verdict.failure, null) else UpdateState.Idle)
                }
            }
        }
    }

    private fun publish(s: UpdateState): UpdateState {
        _state.value = s
        return s
    }

    private fun running(): RunningBuild = RunningBuild(context.packageName, inspector.installedVersionCode(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    /** GET with the bare UA and the redirect allowlist; at most [max] bytes. */
    private fun get(url: String, endpoint: UpdateEndpoint, max: Int): ByteArray {
        val r = open(url, endpoint)
        r.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            // `:app` compiles against the OkHttp 4 API (via Coil), where the body is nullable; the runtime is OkHttp 5.
            val src = (it.body ?: throw IOException("no body")).source()
            src.request(max.toLong() + 1)
            if (src.buffer.size > max) throw IOException("body over $max bytes")
            return src.readByteArray()
        }
    }

    private fun open(url: String, endpoint: UpdateEndpoint): Response {
        val c = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = url.toHttpUrlOrNull() ?: throw IOException("bad URL")
        repeat(MAX_REDIRECTS) {
            if (!endpoint.anyHost && (current.scheme != "https" || current.host !in UpdateTimings.REDIRECT_HOSTS)) throw IOException("host ${current.host} not allowed")
            val resp = c.newCall(Request.Builder().url(current).header("User-Agent", userAgent()).build()).execute()
            if (!resp.isRedirect) return resp
            val next = resp.header("Location")?.let { current.resolve(it) }
            resp.close()
            current = next ?: throw IOException("redirect without Location")
        }
        throw IOException("too many redirects")
    }

    private fun userAgent(): String = "TheBox/" + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?")

    // ---------------------------------------------------------------- download

    private fun updateDir(): File = File(context.filesDir, UPDATE_DIR)

    private fun readyFile(): File? {
        val o = offer ?: return null
        return File(updateDir(), "${o.first.versionCode}.apk").takeIf { it.isFile }
    }

    private suspend fun runDownload(m: UpdateManifest, apk: ApkEntry) {
        val dir = updateDir().apply { mkdirs() }
        dir.listFiles()?.filter { it.name != "${m.versionCode}.apk" }?.forEach { it.delete() }
        if (dir.usableSpace < UpdateTimings.FREE_SPACE_FACTOR * apk.sizeBytes) return fail(UpdateFailure.NO_SPACE)
        val part = File(dir, "${m.versionCode}.apk.part")
        val final = File(dir, "${m.versionCode}.apk")
        _state.value = UpdateState.Downloading(m.versionName, 0, apk.sizeBytes)
        val hash = Sha256Counter()
        try {
            open(apk.url, endpoints.current()).use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val input = (r.body ?: throw IOException("no body")).byteStream()
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (hash.bytes + n > apk.sizeBytes) throw SizeException()
                        out.write(buf, 0, n)
                        hash.update(buf, 0, n)
                        if (hash.bytes - lastPublish >= PROGRESS_STEP_BYTES) {
                            lastPublish = hash.bytes
                            _state.value = UpdateState.Downloading(m.versionName, hash.bytes, apk.sizeBytes)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: SizeException) {
            part.delete()
            return fail(UpdateFailure.HASH_MISMATCH, "larger than the manifest says")
        } catch (e: IOException) {
            part.delete()
            BoxLog.w(TAG, "download failed: ${e.javaClass.simpleName}")
            return fail(UpdateFailure.NETWORK)
        }
        ApkVerifier.verifyDownload(apk.sha256, apk.sizeBytes, hash.hex(), hash.bytes)?.let { v ->
            part.delete()
            return fail((v as ApkVerdict.Rejected).failure, v.detail)
        }
        final.delete()
        if (!part.renameTo(final)) {
            part.delete()
            return fail(UpdateFailure.NO_SPACE)
        }
        when (val v = ApkVerifier.verifyArchive(inspector.archive(final.absolutePath), m, inspector.installedSigners(), Build.VERSION.SDK_INT)) {
            is ApkVerdict.Rejected -> {
                final.delete()
                BoxLog.w(TAG, "APK refused: ${v.failure} ${v.detail}")
                return fail(v.failure, v.detail)
            }
            is ApkVerdict.Ok -> if (!v.certChecked) BoxLog.w(TAG, "API ${Build.VERSION.SDK_INT}: the archive reported no signatures; certificate check skipped (S7)")
        }
        BoxLog.i(TAG) { "APK ${m.versionCode} verified (${hash.bytes} bytes)" }
        _state.value = if (needsPermission()) UpdateState.NeedsPermission(m.versionName) else UpdateState.ReadyToInstall(m.versionName)
        if (installWhenReady) withContext(Dispatchers.Main) { runInstall(final) }
    }

    private class SizeException : IOException()

    private fun fail(f: UpdateFailure, detail: String? = null) {
        lastManual = true // a failure after the user pressed Update is always shown
        _state.value = UpdateState.Failed(f, detail)
    }

    // ---------------------------------------------------------------- install

    private fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    private fun runInstall(file: File) {
        val m = offer?.first ?: return
        if (needsPermission()) {
            _state.value = UpdateState.NeedsPermission(m.versionName)
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                BoxLog.w(TAG, "unknown-sources screen not available: ${e.javaClass.simpleName}")
            }
            return
        }
        installWhenReady = false
        controller.get().stop(StopReason.USER)
        _state.value = UpdateState.Installing
        store.update(UpdateDomain) { it.copy(lastInstalledNotice = m.versionCode) }
        appScope.launch(Dispatchers.Main) { store.flush() }
        installer.install(file, context.packageName) { status ->
            when (status) {
                InstallStatus.PendingUserAction -> {
                    _state.value = UpdateState.Installing
                    watchUnansweredConfirmation()
                }
                InstallStatus.FallbackStarted -> _state.value = UpdateState.Installing
                InstallStatus.Success -> _state.value = UpdateState.Installing
                InstallStatus.Cancelled -> _state.value = UpdateState.ReadyToInstall(m.versionName)
                is InstallStatus.Blocked -> fail(UpdateFailure.INSTALL_BLOCKED, status.message)
                is InstallStatus.Failed -> fail(UpdateFailure.INSTALL_FAILED, status.message)
            }
        }
    }

    /**
     * The system confirmation is another app's activity: when ours starts again while the session is still pending,
     * the user left it unanswered (BACK/Home). The session is abandoned and Update is offered again.
     */
    private fun watchUnansweredConfirmation() {
        // D-B4: the app is still in front when PENDING_USER_ACTION arrives; the watcher acts only on a start that
        // follows a stop (a real return from the confirmation), never on the synchronous catch-up of addObserver.
        confirmationWatcher?.disarm()
        confirmationWatcher = UnansweredConfirmationWatcher(androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle) {
            confirmationWatcher = null
            if (_state.value == UpdateState.Installing) cancelPendingInstall()
        }.also { it.arm() }
    }

    private var confirmationWatcher: UnansweredConfirmationWatcher? = null

    /** Abandons a session whose confirmation was not answered; back to ReadyToInstall. Main thread. */
    fun cancelPendingInstall() {
        val m = offer?.first ?: return
        if (installer.abandonPending()) _state.value = UpdateState.ReadyToInstall(m.versionName)
    }

    /** "Updated to 1.0.4" once on the first start of the new version (§3.12.4 step 2). */
    private suspend fun checkUpdatedNotice() {
        store.awaitReady()
        val prefs = store.flow(UpdateDomain).value
        val target = prefs.lastInstalledNotice ?: return
        val running = runCatching { inspector.installedVersionCode() }.getOrNull() ?: return
        if (running >= target) {
            val name = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
            _notice.value = context.getString(R.string.update_updated_to, name)
            store.update(UpdateDomain) { it.copy(lastInstalledNotice = null) }
            updateDir().listFiles()?.forEach { it.delete() }
        }
    }

    fun failureText(f: UpdateFailure): String = context.getString(
        when (f) {
            UpdateFailure.NETWORK -> R.string.update_failed_network
            UpdateFailure.BAD_SIGNATURE, UpdateFailure.WRONG_CERT -> R.string.update_failed_signature
            UpdateFailure.BAD_MANIFEST -> R.string.update_failed_manifest
            UpdateFailure.REPLAYED -> R.string.update_failed_replayed
            UpdateFailure.NOT_NEWER -> R.string.update_failed_manifest
            UpdateFailure.HASH_MISMATCH -> R.string.update_failed_hash
            UpdateFailure.WRONG_PACKAGE -> R.string.update_failed_package
            UpdateFailure.NO_SPACE -> R.string.update_failed_space
            UpdateFailure.INSTALL_BLOCKED -> if (Build.VERSION.SDK_INT < 26) R.string.update_fire_os_unknown_sources else R.string.update_failed_blocked
            UpdateFailure.INSTALL_FAILED -> R.string.update_failed_install_plain
            UpdateFailure.DISABLED -> R.string.update_failed_disabled
        },
    )

    companion object {
        const val TAG = "Updater"
        const val UPDATE_DIR = "update"
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}

/**
 * D-B4 (code review 2026-10-16): calls [onReturn] once, on the first ON_START that follows an ON_STOP of [lifecycle]
 * (the user came back from the system confirmation without answering it). `addObserver` replays ON_START at once
 * while the app is in front; that replay is not a return and is ignored. Main thread.
 */
internal class UnansweredConfirmationWatcher(
    private val lifecycle: androidx.lifecycle.Lifecycle,
    private val onReturn: () -> Unit,
) : androidx.lifecycle.DefaultLifecycleObserver {
    /** Armed in the background already: the next start is a return. */
    private var sawStop = false

    fun arm() {
        sawStop = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        lifecycle.addObserver(this)
    }

    fun disarm() = lifecycle.removeObserver(this)

    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { sawStop = true }

    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
        if (!sawStop) return
        disarm()
        onReturn()
    }
}
