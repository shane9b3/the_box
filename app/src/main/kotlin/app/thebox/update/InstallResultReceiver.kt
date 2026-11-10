package app.thebox.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.FileProvider
import app.thebox.core.common.BoxLog
import app.thebox.core.update.InstallRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** What the installer reported (docs/design/m5-setup-settings.md §3.12.4). */
sealed interface InstallStatus {
    /** `STATUS_PENDING_USER_ACTION`: the system confirmation was started. */
    data object PendingUserAction : InstallStatus
    /** The user cancelled the confirmation (`STATUS_FAILURE_ABORTED`). */
    data object Cancelled : InstallStatus
    data class Blocked(val message: String?) : InstallStatus
    data class Failed(val status: Int, val message: String?) : InstallStatus
    data object Success : InstallStatus
    /**
     * (docs/design/m5-update-visible-install.md §2b, review B1) The system installer activity [installerPackage] was
     * started with our content URI. It sends no result: the updater's hand-off watcher decides.
     */
    data class SystemInstallerStarted(val installerPackage: String) : InstallStatus
    /** (code review 2026-11-10) The fallback session was committed; its confirmation status may follow. */
    data object SessionCommitted : InstallStatus
}

/**
 * The hand-off of a verified APK (docs/design/m5-update-visible-install.md §2, §5; amends m5-setup-settings.md §3.12.4;
 * the 0.1.4 order is reversed).
 *
 * 1. **System installer activity (primary, [InstallRoute.Route.SYSTEM_INSTALLER]).** `ACTION_VIEW` of the
 *    `FileProvider` URI (`${applicationId}.files`, `filesDir/update` only, N14), type
 *    `application/vnd.android.package-archive`, read grant, `NEW_TASK`, **pinned** with `setPackage` to a system app
 *    that holds `INSTALL_PACKAGES` (review B1: queried with `MATCH_SYSTEM_ONLY`; the manifest's `<queries>` makes a
 *    vendor installer visible on API 30+). The platform then draws "Installing…" and "App installed: Done / Open".
 *    No `EXTRA_RETURN_RESULT` (it removes Done / Open), no `EXTRA_NOT_UNKNOWN_SOURCE`. There is no result.
 * 2. **Session (fallback, [InstallRoute.Route.SESSION]).** When no pinned installer is found (or the debug switch
 *    [forceSession] is on): `MODE_FULL_INSTALL`, `setAppPackageName`, size; `commit` with a broadcast `PendingIntent`
 *    (mutable on API 31+, F12) to this receiver, registered at run time (not exported). `STATUS_PENDING_USER_ACTION`
 *    starts the system confirmation (`EXTRA_INTENT`); there is no silent install (`USER_ACTION_NOT_REQUIRED` is never
 *    requested). Never an un-pinned implicit `VIEW`.
 */
@Singleton
class InstallResultReceiver @Inject constructor(@ApplicationContext private val context: Context) : BroadcastReceiver() {
    @Volatile private var listener: ((InstallStatus) -> Unit)? = null
    @Volatile private var registered = false
    @Volatile private var pendingSession: Int? = null

    /**
     * The user left the system confirmation without answering (BACK, Home): the platform keeps the session open and
     * sends nothing, so the caller abandons it and offers Update again. True when a session was pending.
     */
    fun abandonPending(): Boolean {
        val id = pendingSession ?: return false
        pendingSession = null
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        BoxLog.i(TAG) { "session $id abandoned (confirmation left unanswered)" }
        return true
    }

    /** Every status code seen this process (the instrumented suite reads it, I-M5-19). */
    val statusesSeen: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList())

    /**
     * Hands [apk] (verified by the caller) to the platform: the pinned system installer, else the session. A4 (code
     * review 2026-11-09): the installer query and the session work (the APK copy and its fsync) run on [io]; the
     * caller's thread (main) keeps the receiver registration, the installer's `startActivity` and every [onStatus] call.
     */
    /**
     * [beforeSystemInstaller] runs right before the installer activity is started (second code review S-2: the
     * updater records the attempt there).
     */
    suspend fun install(
        apk: File, packageName: String, io: CoroutineContext, installerRouteFailed: Boolean,
        beforeSystemInstaller: () -> Unit, onStatus: (InstallStatus) -> Unit,
    ) {
        listener = onStatus
        register()
        val pinned = withContext(io) { pinnedInstaller(apk) }
        val route = InstallRoute.choose(Build.VERSION.SDK_INT, pinned, forceSession, installerRouteFailed = installerRouteFailed)
        BoxLog.i(TAG) { "install route $route (installer ${pinned ?: "none"}${if (forceSession) ", session forced" else ""}${if (installerRouteFailed) ", installer route failed before" else ""})" }
        val triedPinned = route == InstallRoute.Route.SYSTEM_INSTALLER && pinned != null
        if (triedPinned && startSystemInstaller(apk, pinned!!, beforeSystemInstaller, onStatus)) return
        when (val r = withContext(io) { commitSession(apk, packageName) }) {
            SessionResult.Committed -> onStatus(InstallStatus.SessionCommitted)
            // The forced session (debug) may still find the pinned installer; never an un-pinned VIEW (review §5).
            SessionResult.NoSession -> if (triedPinned || pinned == null || !startSystemInstaller(apk, pinned, beforeSystemInstaller, onStatus)) onStatus(InstallStatus.Failed(PackageInstaller.STATUS_FAILURE, "no installer"))
            is SessionResult.Failed -> onStatus(InstallStatus.Failed(PackageInstaller.STATUS_FAILURE, r.reason))
        }
    }

    /**
     * Debug switch (review S8): always use the session path, so the fallback stays testable on a device that has a
     * system installer. Set only by the debug harness (`thebox-debug://guide?update_route=session`) and the instrumented
     * suite; release builds never write it.
     */
    @Volatile var forceSession: Boolean = false

    /** Every installer the APK `VIEW` intent resolves to, as [InstallRoute.Candidate]s (review B1). */
    fun installerCandidates(apk: File): List<InstallRoute.Candidate> {
        val pm = context.packageManager
        val probe = viewIntent(apk)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        val found = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(flags.toLong())) else pm.queryIntentActivities(probe, flags)
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "installer query failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        return found.mapNotNull { r ->
            val ai = r.activityInfo ?: return@mapNotNull null
            InstallRoute.Candidate(
                packageName = ai.packageName,
                system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                holdsInstallPackages = pm.checkPermission(INSTALL_PACKAGES, ai.packageName) == PackageManager.PERMISSION_GRANTED,
            )
        }.distinctBy { it.packageName }
    }

    private fun pinnedInstaller(apk: File): String? {
        val candidates = installerCandidates(apk)
        val pinned = InstallRoute.pinnedInstaller(candidates)
        if (candidates.count { it.system && it.holdsInstallPackages } > 1) BoxLog.i(TAG) { "several system installers; using $pinned" }
        return pinned
    }

    private fun viewIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
    }

    /**
     * Starts the pinned installer; false when it could not be started (the caller tries the session). Main thread.
     * [onStatus] runs outside the try (code review nit): a throwing listener must not look like a failed start and
     * cause a second hand-off.
     */
    private fun startSystemInstaller(apk: File, installerPackage: String, before: () -> Unit, onStatus: (InstallStatus) -> Unit): Boolean {
        before()
        val started = try {
            val view = viewIntent(apk).setPackage(installerPackage)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            BoxLog.i(TAG) { "system installer $installerPackage started" }
            true
        } catch (e: Exception) {
            BoxLog.w(TAG, "system installer not started (${e.javaClass.simpleName}); session fallback")
            false
        }
        if (started) onStatus(InstallStatus.SystemInstallerStarted(installerPackage))
        return started
    }

    /**
     * Sessions of other installers for [packageName] (review S5: the system installer's own session while it shows
     * "Installing…"); our own pending session is left out. Visibility of other installers' sessions is per platform.
     */
    fun installerSessionInProgress(packageName: String): Boolean {
        val sessions = try {
            context.packageManager.packageInstaller.allSessions.map { info ->
                InstallRoute.SessionFacts(info.sessionId, info.appPackageName, info.isActive, Build.VERSION.SDK_INT >= 29 && info.isCommitted)
            }
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "sessions not readable: ${e.javaClass.simpleName}")
            emptyList()
        }
        BoxLog.i(TAG) { "sessions for us: ${sessions.filter { it.appPackageName == packageName }}" }
        return InstallRoute.installerSessionInProgress(sessions, packageName, pendingSession)
    }

    private sealed interface SessionResult {
        data object Committed : SessionResult
        /** Session installs are unavailable (§9 F14). */
        data object NoSession : SessionResult
        data class Failed(val reason: String) : SessionResult
    }

    private fun commitSession(apk: File, packageName: String): SessionResult {
        val installer = context.packageManager.packageInstaller
        val sessionId = try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apk.length())
            }
            installer.createSession(params)
        } catch (e: IOException) {
            BoxLog.w(TAG, "session not created (${e.javaClass.simpleName})")
            return SessionResult.NoSession
        } catch (e: SecurityException) {
            BoxLog.w(TAG, "session not allowed (${e.javaClass.simpleName})")
            return SessionResult.NoSession
        } catch (e: IllegalStateException) {
            // Too many sessions, or the installer service refused: a failure with a next step, never a crash.
            BoxLog.w(TAG, "session refused (${e.javaClass.simpleName})")
            return SessionResult.Failed(e.javaClass.simpleName)
        }
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                // Set before commit: with the commit off the main thread, its status broadcast could otherwise come first.
                pendingSession = sessionId
                session.commit(pending.intentSender)
            }
            BoxLog.i(TAG) { "session $sessionId committed (${apk.length()} bytes)" }
            return SessionResult.Committed
        } catch (e: Exception) {
            if (pendingSession == sessionId) pendingSession = null
            runCatching { installer.abandonSession(sessionId) }
            BoxLog.w(TAG, "session $sessionId failed before commit: ${e.javaClass.simpleName}")
            return SessionResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun register() {
        if (registered) return
        ContextCompat.registerReceiver(context, this, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        statusesSeen += status
        // Second code review S-3: only the session in flight; a late status of an abandoned one changes nothing.
        if (!InstallRoute.acceptsStatus(id, pendingSession)) {
            BoxLog.i(TAG) { "status $status of session $id ignored (current ${pendingSession ?: "none"})" }
            return
        }
        BoxLog.i(TAG) { "status $status" }
        val l = listener
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                // S-1: nothing to show is a failure with a reason, never a confirmation that is not there.
                val failure = InstallRoute.confirmationFailure(confirm != null) {
                    try {
                        ctx.startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        null
                    } catch (e: Exception) {
                        e.javaClass.simpleName
                    }
                }
                if (failure != null) {
                    // A6 (code review 2026-11-09): nobody can answer this session; abandon it rather than leak it.
                    val sid = pendingSession
                    pendingSession = null
                    if (sid != null) runCatching { ctx.packageManager.packageInstaller.abandonSession(sid) }
                    l?.invoke(InstallStatus.Failed(PackageInstaller.STATUS_FAILURE, failure))
                    return
                }
                l?.invoke(InstallStatus.PendingUserAction)
            }
            PackageInstaller.STATUS_SUCCESS -> { pendingSession = null; l?.invoke(InstallStatus.Success) }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { pendingSession = null; l?.invoke(InstallStatus.Cancelled) }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> { pendingSession = null; l?.invoke(InstallStatus.Blocked(message)) }
            else -> { pendingSession = null; l?.invoke(InstallStatus.Failed(status, message)) }
        }
    }

    companion object {
        const val TAG = "Updater"
        const val ACTION = "app.thebox.update.INSTALL_STATUS"
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val INSTALL_PACKAGES = "android.permission.INSTALL_PACKAGES"
    }
}
