package app.thebox.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.MainActivity
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.SettingsStore
import app.thebox.core.update.ApkEntry
import app.thebox.core.update.ApkVerdict
import app.thebox.core.update.ApkVerifier
import app.thebox.core.update.DeferredHandOff
import app.thebox.core.update.InstallRoute
import app.thebox.core.update.HandOffWatcher
import app.thebox.core.update.SingleFlight
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
import app.thebox.core.update.isBusy
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.DownloadClient
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.StartupGate
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.StopReason
import app.thebox.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * The sideload updater (docs/design/m5-setup-settings.md §3.12; the hand-off as amended by
 * docs/design/m5-update-visible-install.md): check → card → download → verify → hand-off.
 *
 * - **Check** (§3.12.2): `update.json` + `update.json.sig` from the build type's [UpdateEndpoint], `User-Agent:
 *   TheBox/{versionName}` and nothing else identifying; redirects only to the GitHub hosts of
 *   [UpdateTimings.REDIRECT_HOSTS] (release endpoints); [ManifestVerifier] in the spec's order; the accepted sequence and
 *   the check time are stored in the `update` domain. Automatic-check failures are silent ([UpdatePolicy.showsFailure]).
 * - **Download** (§3.12.3): to `filesDir/update/{versionCode}.apk.part`, free space ≥ 2 × size, SHA-256 while
 *   streaming, then renamed and checked by [ApkVerifier] (package, versionCode, signer in the manifest **and** equal to
 *   the installed signer; API 25–27 without signatures: skipped and logged, S7). Any failure deletes the file. A file
 *   already there for the offered versionCode is reused only after the same checks (hash and size of the whole file,
 *   then the archive): every hand-off follows a full check of the exact file, in this process.
 * - **Hand-off** (§3.12.4 as amended): `REQUEST_INSTALL_PACKAGES` pre-check (API 26+ `canRequestPackageInstalls`,
 *   else the settings screen; a user restriction or a settings screen that cannot open is INSTALL_BLOCKED, review S4),
 *   never from the background (review S6), playback stopped, then [InstallResultReceiver]: the pinned system installer
 *   activity, else the session. No result comes back from the installer: [HandOffWatcher] decides when to look at the
 *   installed versionCode again (review S5); the old version still there means "Not installed".
 * - **Start** (review S3): a verified-looking `{versionCode}.apk` newer than the running version (the user pressed
 *   Update, then the process died, e.g. on the API 30 permission grant) makes the app check again right after the first
 *   frame; Update then reuses the file.
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
    private val gate: StartupGate,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : Updater, UpdateCardSource {
    // Every field is declared before `init` (code review S2): init starts the lifecycle observers, whose replayed
    // events may run synchronously and read these.
    private val verifier = ManifestVerifier(signatures)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    /** Guards "is it busy?" + the state change as one step (code review S5): download() and runCheck cannot interleave. */
    private val stateLock = Any()

    private val mutex = Mutex()
    @Volatile private var checkedThisProcess = false
    @Volatile private var offer: Pair<UpdateManifest, ApkEntry>? = null
    @Volatile private var lastManual = false
    private var work: Job? = null
    /** Second code review S-3: one update job at a time. */
    private val flight = SingleFlight()
    private var jobSeq = 0
    private val fingerprint: String = Build.FINGERPRINT.orEmpty()

    /**
     * Second code review nit: `appScope` has no handler, and a dead package manager (or any bug) in the updater must
     * not take the app down. Logged; a busy state ends in a failure with a next step, a check in Idle.
     */
    private val guard = CoroutineExceptionHandler { _, e ->
        BoxLog.w(TAG, "updater error: ${e.javaClass.simpleName}")
        val s = _state.value
        when {
            s == UpdateState.Checking -> _state.value = UpdateState.Idle
            s.isBusy -> fail(UpdateFailure.INSTALL_FAILED, e.javaClass.simpleName)
        }
    }

    private val laterThisProcess = MutableStateFlow(false)
    private val _notice = MutableStateFlow<String?>(null)
    override val updatedNotice: StateFlow<String?> = _notice.asStateFlow()

    // Hand-off (main thread).
    private val watcher = HandOffWatcher()
    private val deferred = DeferredHandOff()
    /** The current hand-off went through our own session (the fallback). */
    private var handOffSession = false
    /** The session's confirmation was started (no deadline from then on, code review S1). */
    private var sessionConfirmationShown = false
    /** The installer route's installer was seen (a return, or its session at work): a miss is then not "never appeared". */
    private var installerSeen = false
    private var tickJob: Job? = null
    /** A hand-off is being started (A4: the session work runs on [io]); a second one must not start. Main. */
    private var installStarting = false

    private val fold = UpdateCardModel.Fold { f -> if (UpdatePolicy.showsFailure(f, lastManual)) failureText(f) else null }
    override val card: StateFlow<UpdateCardState?> =
        combine(_state, laterThisProcess) { s, later -> fold.next(s, later) }.stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        appScope.launch(io + guard) {
            checkUpdatedNotice()
            resumeAfterRestart()
        }
        // Immediate when built on the main thread (MainActivity's view models): the callbacks are in place before its
        // onResume. Built elsewhere, observeLifecycle starts from the process state, so nothing depends on immediacy.
        appScope.launch(Dispatchers.Main.immediate + guard) { observeLifecycle() }
    }

    /** Sets [next] unless the state is one [blockedBy] refuses; one step under [stateLock]. */
    private fun claim(next: UpdateState, blockedBy: (UpdateState) -> Boolean = { it.isBusy }): Boolean = synchronized(stateLock) {
        if (blockedBy(_state.value)) false else { _state.value = next; true }
    }

    // ---------------------------------------------------------------- Updater

    override fun check(manual: Boolean) {
        // Asking by hand brings the card back after Later / BACK (not while busy: the check would not run).
        if (manual && !_state.value.isBusy) laterThisProcess.value = false
        appScope.launch(io + guard) { runCheck(manual) }
    }

    /**
     * Downloads the offer (or re-checks the file already downloaded for it) and opens the installer as soon as the file
     * is verified (the card's Update, the About row). Refused while busy or while a check runs.
     */
    override fun download() {
        // The offer, the busy rule, the single-flight slot and the state change in one step (second code review).
        val o = synchronized(stateLock) {
            val o = offer ?: return
            val s = _state.value
            if (s.isBusy || s == UpdateState.Checking || !flight.tryStart()) return
            // "Downloading 0 %" until the job knows (off the main thread) whether a stored file can be reused.
            _state.value = UpdateState.Downloading(o.first.versionName, 0, o.second.sizeBytes)
            o
        }
        startWork(o.first, o.second)
    }

    /** Starts the job; the caller holds the [flight] slot. */
    private fun startWork(m: UpdateManifest, apk: ApkEntry) {
        // Pressing Update on a version skipped earlier un-skips it (the restart check of review S3 must not drop it).
        if (store.flow(UpdateDomain).value.skippedVersionCode == m.versionCode) store.update(UpdateDomain) { it.copy(skippedVersionCode = null) }
        val seq = ++jobSeq
        work = appScope.launch(io + guard) {
            try {
                runDownload(m, apk, seq)
            } finally {
                flight.done()
            }
        }
    }

    /** Hands the offer's file to the installer, after checking it again (a missing file is downloaded again). */
    override fun install() = download()

    override fun skip(versionCode: Long) {
        store.update(UpdateDomain) { it.copy(skippedVersionCode = versionCode) }
        if (offer?.first?.versionCode == versionCode && !flight.busy && claim(UpdateState.Idle)) {
            appScope.launch(io + guard) { if (!flight.busy) updateDir().listFiles()?.forEach { it.delete() } }
        }
    }

    /** The versionCode of the release on offer (review S7: Skip on any card with buttons), null when none. */
    fun offeredVersionCode(): Long? = offer?.first?.versionCode

    /** The card's Later / BACK: the card stays away until the next start (busy phases too; the work goes on). */
    fun later() {
        laterThisProcess.value = true
    }

    /** The card's Update: download (or re-check the file), then open the installer as soon as the file is verified. */
    fun updateNow() = download()

    override fun noticeShown() {
        _notice.value = null
    }

    /** Instrumented suite only (I-M5-19): forget this process's check, offer and card state between cases. */
    @androidx.annotation.VisibleForTesting
    fun resetSession() {
        work?.cancel()
        checkedThisProcess = false
        offer = null
        deferred.clear()
        watcher.disarm()
        tickJob?.cancel()
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

    /**
     * One check (the worker awaits it). Returns the state it ended in. [resume] (review S3): the restart check for a
     * download already on disk; it runs whatever the cadence and stays silent on failure.
     */
    suspend fun runCheck(manual: Boolean, resume: Boolean = false): UpdateState = runCheckWithOutcome(manual, resume).first

    /** [runCheck] plus what it ended in for a waiting download (code review S4), returned rather than shared. */
    private suspend fun runCheckWithOutcome(manual: Boolean, resume: Boolean): Pair<UpdateState, UpdatePolicy.CheckOutcome> = mutex.withLock {
        var outcome: UpdatePolicy.CheckOutcome = UpdatePolicy.CheckOutcome.NotRun
        val end = withContext(io) {
            store.awaitReady()
            val now = System.currentTimeMillis()
            var prefs = store.flow(UpdateDomain).value
            if (!UpdatePolicy.shouldRun(prefs, now, manual, checkedThisProcess, resume)) return@withContext _state.value
            // S1 (review) + S5 (code review): one busy rule, checked and claimed in one step.
            if (!claim(UpdateState.Checking)) return@withContext _state.value
            checkedThisProcess = true
            lastManual = manual
            val endpoint = endpoints.current()
            if (!endpoint.enabled) {
                BoxLog.i(TAG) { "updates disabled for ${endpoint.label}" }
                outcome = UpdatePolicy.CheckOutcome.Rejected
                return@withContext publish(if (manual) UpdateState.Failed(UpdateFailure.DISABLED, null) else UpdateState.Idle)
            }
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
                outcome = UpdatePolicy.CheckOutcome.Unreachable
                return@withContext publish(if (manual) UpdateState.Failed(UpdateFailure.NETWORK, null) else UpdateState.Idle)
            }
            val verdict = verifier.verify(fetched.first, fetched.second, endpoint, running(), prefs, bucket, manual)
            when (verdict) {
                is ManifestVerdict.Offer -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now, highestSequence = maxOf(it.highestSequence, verdict.manifest.sequence)) }
                    offer = verdict.manifest to verdict.apk
                    outcome = UpdatePolicy.CheckOutcome.Offered(verdict.manifest.versionCode)
                    BoxLog.i(TAG) { "offer ${verdict.manifest.versionName} (${verdict.manifest.versionCode}) ${verdict.apk.abi}, seq ${verdict.manifest.sequence}" }
                    publish(UpdateState.Available(verdict.manifest, verdict.apk))
                }
                is ManifestVerdict.Nothing -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now, highestSequence = maxOf(it.highestSequence, verdict.manifest.sequence)) }
                    offer = null
                    outcome = UpdatePolicy.CheckOutcome.Nothing
                    BoxLog.i(TAG) { "no offer: ${verdict.reason}" }
                    publish(UpdateState.UpToDate(now))
                }
                is ManifestVerdict.Rejected -> {
                    store.update(UpdateDomain) { it.copy(lastCheckMs = now) }
                    outcome = UpdatePolicy.CheckOutcome.Rejected
                    BoxLog.w(TAG, "manifest rejected: ${verdict.failure} ${verdict.detail}")
                    publish(if (manual) UpdateState.Failed(verdict.failure, null) else UpdateState.Idle)
                }
            }
        }
        end to outcome
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

    /** Code review nit: an unexpected runtime error ends in a failure with a next step, never a crash. */
    private suspend fun runDownload(m: UpdateManifest, apk: ApkEntry, seq: Int) {
        try {
            download(m, apk, seq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "update work failed: ${e.javaClass.simpleName}")
            fail(UpdateFailure.INSTALL_FAILED, e.javaClass.simpleName)
        }
    }

    private suspend fun download(m: UpdateManifest, apk: ApkEntry, seq: Int) {
        val dir = updateDir().apply { mkdirs() }
        dir.listFiles()?.filter { it.name != "${m.versionCode}.apk" }?.forEach { it.delete() }
        val final = File(dir, "${m.versionCode}.apk")
        if (final.isFile) {
            // Downloaded before (put off with Back, not installed, or a process that died, review S3): the whole check
            // runs again on the file; only then is the download skipped.
            _state.value = UpdateState.Verifying(m.versionName)
            val hash = Sha256Counter()
            val read = try {
                final.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        hash.update(buf, 0, n)
                    }
                }
                true
            } catch (e: IOException) {
                false
            }
            if (read && verify(final, m, apk, hash.hex(), hash.bytes, quietly = true) == null) {
                BoxLog.i(TAG) { "APK ${m.versionCode} already downloaded and verified again (${hash.bytes} bytes)" }
                return ready(m, final)
            }
            BoxLog.i(TAG) { "the stored APK ${m.versionCode} did not pass the checks; downloading again" }
            final.delete()
        }
        if (dir.usableSpace < UpdateTimings.FREE_SPACE_FACTOR * apk.sizeBytes) return fail(UpdateFailure.NO_SPACE)
        // Each job writes its own part file (second code review S-3).
        val part = File(dir, "${m.versionCode}.apk.$seq.part")
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
                        currentCoroutineContext().ensureActive() // a cancelled job stops between reads
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
        // The last bytes are published too ("Downloading 100 %"), then "Checking the download…".
        _state.value = UpdateState.Downloading(m.versionName, hash.bytes, apk.sizeBytes)
        _state.value = UpdateState.Verifying(m.versionName)
        val hex = hash.hex()
        ApkVerifier.verifyDownload(apk.sha256, apk.sizeBytes, hex, hash.bytes)?.let { v ->
            part.delete()
            return fail((v as ApkVerdict.Rejected).failure, v.detail)
        }
        final.delete()
        if (!part.renameTo(final)) {
            part.delete()
            return fail(UpdateFailure.NO_SPACE)
        }
        verify(final, m, apk, hex, hash.bytes, quietly = false)?.let { return fail(it.failure, it.detail) }
        BoxLog.i(TAG) { "APK ${m.versionCode} verified (${hash.bytes} bytes)" }
        ready(m, final)
    }

    /**
     * Hash ([hex]) and size ([bytes]) of the whole [file] against the manifest, then the archive (package,
     * versionCode, signer). Null when it passes; a rejection deletes the file. [quietly]: a reused file that fails is
     * simply downloaded again.
     */
    private fun verify(file: File, m: UpdateManifest, apk: ApkEntry, hex: String, bytes: Long, quietly: Boolean): ApkVerdict.Rejected? {
        ApkVerifier.verifyDownload(apk.sha256, apk.sizeBytes, hex, bytes)?.let { v ->
            file.delete()
            return v as ApkVerdict.Rejected
        }
        return when (val v = ApkVerifier.verifyArchive(inspector.archive(file.absolutePath), m, inspector.installedSigners(), Build.VERSION.SDK_INT)) {
            is ApkVerdict.Rejected -> {
                file.delete()
                if (!quietly) BoxLog.w(TAG, "APK refused: ${v.failure} ${v.detail}")
                v
            }
            is ApkVerdict.Ok -> {
                if (!v.certChecked) BoxLog.w(TAG, "API ${Build.VERSION.SDK_INT}: the archive reported no signatures; certificate check skipped (S7)")
                null
            }
        }
    }

    /** The file is verified: hand it off now, or (in the background, review S6) once the app is in front again. */
    private suspend fun ready(m: UpdateManifest, final: File) = withContext(Dispatchers.Main) {
        if (deferred.onVerified(inForeground())) {
            runInstall(m, final)
        } else {
            // An activity start from the background is dropped silently on API 29+ (BAL): wait for the next ON_START.
            BoxLog.i(TAG) { "verified in the background; the installer opens when The Box is back in front" }
            _state.value = UpdateState.ReadyToInstall(m.versionName)
        }
    }

    private class SizeException : IOException()

    private fun fail(f: UpdateFailure, detail: String? = null) {
        lastManual = true // a failure after the user pressed Update is always shown
        _state.value = UpdateState.Failed(f, detail)
        laterThisProcess.value = false // after the state: no flicker of the old busy card (review S2)
    }

    /** Main thread (installer statuses and the watcher). The card text is the same whatever the route. */
    private fun notInstalled(m: UpdateManifest) {
        watcher.disarm()
        tickJob?.cancel()
        _state.value = UpdateState.NotInstalled(m.versionName)
        laterThisProcess.value = false // review S2
        // Review N3: playback stopped for the hand-off stays stopped; the user picks a channel (or presses Update).
    }

    // ---------------------------------------------------------------- install

    private fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    /** Review S4: installs from unknown sources are forbidden for this user (operator or managed TVs). */
    private fun installsRestricted(): Boolean {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        return um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            (Build.VERSION.SDK_INT >= 29 && um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
    }

    private fun inForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Main thread, in the foreground, with [file] just verified for [m]. */
    private suspend fun runInstall(m: UpdateManifest, file: File) {
        if (installStarting) {
            // Not reachable while the busy rule holds; never leave a card without buttons (code review S5).
            BoxLog.w(TAG, "a hand-off is already starting")
            return notInstalled(m)
        }
        try {
            handOff(m, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "hand-off failed: ${e.javaClass.simpleName}")
            watcher.disarm()
            fail(UpdateFailure.INSTALL_FAILED, e.javaClass.simpleName)
        }
    }

    private suspend fun handOff(m: UpdateManifest, file: File) {
        if (needsPermission()) {
            if (installsRestricted()) return fail(UpdateFailure.INSTALL_RESTRICTED, "user restriction")
            _state.value = UpdateState.NeedsPermission(m.versionName)
            laterThisProcess.value = false // code review S3: the hint must be seen on the way back
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                BoxLog.w(TAG, "unknown-sources screen not available: ${e.javaClass.simpleName}")
                fail(UpdateFailure.INSTALL_BLOCKED, "no unknown-sources screen")
            }
            return
        }
        controller.get().stop(StopReason.USER)
        _state.value = UpdateState.Installing
        store.update(UpdateDomain) { it.copy(lastInstalledNotice = m.versionCode) }
        appScope.launch(Dispatchers.Main + guard) { store.flush() }
        val routeFailed = InstallRoute.installerRouteFailed(store.flow(UpdateDomain).value, m.versionCode, fingerprint)
        handOffSession = false
        sessionConfirmationShown = false
        installerSeen = false
        installStarting = true
        try {
            val attempt = {
                // Second code review S-2: written (and flushed) before the installer starts, so a miss counts even when
                // this process dies while the installer is up.
                store.update(UpdateDomain) { InstallRoute.recordAttempt(it, m.versionCode) }
                appScope.launch(Dispatchers.Main + guard) { store.flush() }
                Unit
            }
            installer.install(file, context.packageName, io, routeFailed, attempt) { status ->
                // Second code review S-3: a status changes the state only while this hand-off is in progress.
                if (_state.value != UpdateState.Installing) {
                    BoxLog.i(TAG) { "installer status $status ignored in ${_state.value.javaClass.simpleName}" }
                    return@install
                }
                when (status) {
                    // Armed at commit, so a confirmation status that never comes still has a deadline.
                    InstallStatus.SessionCommitted -> {
                        store.update(UpdateDomain) { InstallRoute.clearAttempt(it) } // the session route took over
                        if (!sessionConfirmationShown) watchHandOff(session = true)
                    }
                    // Second code review S-1: with a deadline too; it runs only while MainActivity is resumed, so not
                    // while the confirmation is up, and an Install that brings us back is covered by keepWaiting.
                    InstallStatus.PendingUserAction -> {
                        sessionConfirmationShown = true
                        watchHandOff(session = true)
                    }
                    // The system installer sends no result; the watcher decides (review S5).
                    is InstallStatus.SystemInstallerStarted -> watchHandOff(session = false)
                    InstallStatus.Success -> _state.value = UpdateState.Installing
                    InstallStatus.Cancelled -> notInstalled(m)
                    is InstallStatus.Blocked -> { watcher.disarm(); fail(UpdateFailure.INSTALL_BLOCKED, status.message) }
                    is InstallStatus.Failed -> { watcher.disarm(); fail(UpdateFailure.INSTALL_FAILED, status.message) }
                }
            }
        } finally {
            installStarting = false
        }
    }

    // ---------------------------------------------------------------- hand-off watcher (review S5, code review S1)

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun watchHandOff(session: Boolean) {
        handOffSession = session
        watcher.arm(now())
        scheduleTick()
    }

    /** A timer at the watcher's deadline (only while `MainActivity` is resumed). Main thread. */
    private fun scheduleTick() {
        tickJob?.cancel()
        val at = watcher.deadlineAtMs() ?: return
        tickJob = appScope.launch(Dispatchers.Main + guard) {
            delay((at - now()).coerceAtLeast(0))
            if (watcher.onTick(now())) onHandOffCheck()
        }
    }

    /**
     * Back from the installer, or the deadline passed with no installer in front. Main thread. The new version there:
     * the update landed and this process was not killed (notice). Otherwise:
     * - session route: a dialog confirmation only pauses us, so pressing Install there also comes back here. Our own
     *   session is never abandoned on that: the check waits again (a fresh 15 s while resumed, at most
     *   [HandOffWatcher.MAX_WAITS] times) and re-reads the versionCode; an approved install ends this process long
     *   before. Only then is the session abandoned and "Not installed" shown (code review S1);
     * - installer route: another installer session at work → wait again (bounded); else remember the miss for the route
     *   choice (code review B1) and show "Not installed".
     */
    private fun onHandOffCheck() {
        tickJob?.cancel()
        if (_state.value != UpdateState.Installing) return
        val m = offer?.first ?: return
        val trigger = watcher.firedBy
        if (trigger == HandOffWatcher.Trigger.RETURN) installerSeen = true
        val installed = runCatching { inspector.installedVersionCode() }.getOrNull()
        if (installed != null && UpdatePolicy.landed(installed, m.versionCode)) {
            BoxLog.i(TAG) { "update ${m.versionCode} landed while this process lived" }
            _state.value = UpdateState.Idle
            store.update(UpdateDomain) { InstallRoute.clearAttempt(it) }
            appScope.launch(io + guard) { checkUpdatedNotice() }
            return
        }
        if (handOffSession) {
            if (watcher.keepWaiting(now())) {
                BoxLog.i(TAG) { "back from the session confirmation; re-checking before abandoning it" }
                scheduleTick()
                return
            }
            installer.abandonPending()
            BoxLog.i(TAG) { "the session was not answered; not installed" }
            return notInstalled(m)
        }
        if (installer.installerSessionInProgress(context.packageName)) {
            installerSeen = true
            if (watcher.keepWaiting(now())) {
                BoxLog.i(TAG) { "an installer session for us is still at work; waiting" }
                scheduleTick()
                return
            }
        }
        val miss = if (installerSeen) InstallRoute.Miss.LEFT_INSTALLER else InstallRoute.Miss.NEVER_APPEARED
        store.update(UpdateDomain) { InstallRoute.recordMiss(it, m.versionCode, miss, fingerprint) }
        BoxLog.i(TAG) { "back from the installer without the update ($miss)" }
        notInstalled(m)
    }

    /** Abandons a session whose confirmation was not answered: "Not installed" (instrumented suite, session route). */
    fun cancelPendingInstall() {
        val m = offer?.first ?: return
        if (installer.abandonPending()) notInstalled(m)
    }

    /** Feeds [watcher] from the process lifecycle and `MainActivity`'s own callbacks; the deferred hand-off (S6). */
    private fun observeLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (watcher.onProcessStarted(now())) onHandOffCheck()
                if (deferred.onStart()) resumeDeferredHandOff()
            }
            override fun onResume(owner: LifecycleOwner) { if (watcher.onProcessResumed(now())) onHandOffCheck() }
            override fun onPause(owner: LifecycleOwner) = watcher.onProcessPaused()
            override fun onStop(owner: LifecycleOwner) = watcher.onProcessStopped()
        })
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                if (watcher.onActivityResumed(now())) onHandOffCheck() else scheduleTick()
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity !is MainActivity) return
                watcher.onActivityPaused()
                tickJob?.cancel()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Registered after MainActivity resumed (the updater was built off the main thread): start from the process state.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) watcher.onActivityResumed(now())
    }

    /** The verified file waited for the foreground (S6): check it again and hand it off. Main thread. */
    private fun resumeDeferredHandOff() {
        val (m, apk) = offer ?: return
        val claimed = synchronized(stateLock) {
            if (_state.value is UpdateState.ReadyToInstall && flight.tryStart()) { _state.value = UpdateState.Verifying(m.versionName); true } else false
        }
        if (claimed) startWork(m, apk)
    }

    // ---------------------------------------------------------------- start

    /** "Updated to 1.0.4" once on the first start of the new version (§3.12.4 step 2); route memory dropped (B1). */
    private suspend fun checkUpdatedNotice() {
        store.awaitReady()
        val running = runCatching { inspector.installedVersionCode() }.getOrNull() ?: return
        store.update(UpdateDomain) { InstallRoute.onStart(it, running, fingerprint) }
        val prefs = store.flow(UpdateDomain).value
        val target = prefs.lastInstalledNotice ?: return
        if (running >= target) {
            val name = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
            _notice.value = context.getString(R.string.update_updated_to, name)
            store.update(UpdateDomain) { it.copy(lastInstalledNotice = null) }
            updateDir().listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Review S3 (and N7): on start, files of `filesDir/update` that are not a newer APK are deleted; a newer
     * `{versionCode}.apk` is kept and a silent check runs right after the first frame so the card comes back and Update
     * reuses the file (after checking it again). That one check ignores the daily cadence and "check automatically":
     * the user pressed Update for this version and was interrupted (code review S4). If the check does not offer that
     * version any more (nothing to offer, or a refused manifest), the file is deleted, so it cannot trigger a check on
     * every cold start for ever; it stays only while the version is offered or the server could not be reached.
     */
    private suspend fun resumeAfterRestart() {
        val dir = updateDir()
        val names = dir.list()?.toList().orEmpty()
        if (names.isEmpty()) return
        val running = runCatching { inspector.installedVersionCode() }.getOrNull() ?: return
        val plan = UpdatePolicy.startupFiles(names, running)
        plan.delete.forEach { File(dir, it).delete() }
        val keep = plan.keepVersionCode ?: return
        BoxLog.i(TAG) { "downloaded update $keep is waiting; checking again after the first frame" }
        gate.awaitOpen()
        delay(UpdateTimings.UPDATE_RESUME_CHECK_DELAY_MS)
        val outcome = runCheckWithOutcome(manual = false, resume = true).second
        if (!UpdatePolicy.keepsWaitingFile(keep, outcome)) {
            BoxLog.i(TAG) { "the waiting update $keep is no longer offered ($outcome); deleted" }
            if (!_state.value.isBusy && !flight.busy) File(dir, "$keep.apk").delete()
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
            // The Fire OS developer-option path only where it exists (Fire OS 6, API 25).
            UpdateFailure.INSTALL_BLOCKED -> if (Build.VERSION.SDK_INT < 26 && Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) R.string.update_fire_os_unknown_sources else R.string.update_failed_blocked
            UpdateFailure.INSTALL_RESTRICTED -> R.string.update_failed_restricted
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
