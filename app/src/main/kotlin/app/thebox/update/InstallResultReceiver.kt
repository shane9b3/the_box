package app.thebox.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.thebox.core.common.BoxLog
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
    /** Session installs were unavailable; the `ACTION_VIEW` fallback (Fire OS, §9 F14) was started. */
    data object FallbackStarted : InstallStatus
}

/**
 * The `PackageInstaller` session and its status receiver (§3.12.4): `MODE_FULL_INSTALL`, `setAppPackageName`, size;
 * `commit` with a broadcast `PendingIntent` (mutable on API 31+, F12) to this receiver, registered at run time
 * (not exported) so the manifest needs no entry. `STATUS_PENDING_USER_ACTION` starts the system confirmation
 * (`EXTRA_INTENT`); there is no silent install (`USER_ACTION_NOT_REQUIRED` is never requested).
 * If a session cannot be created, the fallback is `ACTION_VIEW` of the `FileProvider` URI (`${applicationId}.files`,
 * `filesDir/update` only, N14).
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
     * Creates, writes and commits the session. A4 (code review 2026-11-09): the session work (the APK copy and its fsync)
     * runs on [io]; the caller's thread (main) keeps the receiver registration, the fallback's `startActivity` and every
     * [onStatus] call.
     */
    suspend fun install(apk: File, packageName: String, io: CoroutineContext, onStatus: (InstallStatus) -> Unit) {
        listener = onStatus
        register()
        when (val r = withContext(io) { commitSession(apk, packageName) }) {
            SessionResult.Committed -> Unit
            SessionResult.NoSession -> fallback(apk, onStatus)
            is SessionResult.Failed -> onStatus(InstallStatus.Failed(PackageInstaller.STATUS_FAILURE, r.reason))
        }
    }

    private sealed interface SessionResult {
        data object Committed : SessionResult
        /** Session installs are unavailable (Fire OS, §9 F14): use the `ACTION_VIEW` fallback. */
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
            BoxLog.w(TAG, "session not created (${e.javaClass.simpleName}); ACTION_VIEW fallback")
            return SessionResult.NoSession
        } catch (e: SecurityException) {
            BoxLog.w(TAG, "session not allowed (${e.javaClass.simpleName}); ACTION_VIEW fallback")
            return SessionResult.NoSession
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

    private fun fallback(apk: File, onStatus: (InstallStatus) -> Unit) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
            @Suppress("DEPRECATION")
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            onStatus(InstallStatus.FallbackStarted)
        } catch (e: Exception) {
            onStatus(InstallStatus.Failed(PackageInstaller.STATUS_FAILURE, e.javaClass.simpleName))
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
        statusesSeen += status
        BoxLog.i(TAG) { "status $status" }
        val l = listener
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        // A6 (code review 2026-11-09): nobody can answer this session; abandon it rather than leak it.
                        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, pendingSession ?: -1)
                        pendingSession = null
                        if (id >= 0) runCatching { ctx.packageManager.packageInstaller.abandonSession(id) }
                        l?.invoke(InstallStatus.Blocked(e.javaClass.simpleName))
                        return
                    }
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
    }
}
