package app.thebox.core.update

/**
 * How a verified update reaches the platform (docs/design/m5-update-visible-install.md §2, §5; review B1, §3). Pure.
 *
 * The primary route is the **system installer activity**: `ACTION_VIEW` of our `FileProvider` URI with the APK MIME
 * type, pinned with `setPackage` to a system app that holds `INSTALL_PACKAGES`. Only that route makes the platform draw
 * "Installing…" and "App installed: Done / Open". The `PackageInstaller` session (the 0.1.4 flow) is the fallback when
 * no such installer is found. Never an un-pinned implicit `VIEW` (any APK handler could take it).
 */
object InstallRoute {
    /**
     * The lowest API level that uses the system installer activity (review §3, Q3). One line to change: 26 moves Fire
     * OS 6 (API 25) back to the session path (no Open button) if the device check of the content URI fails there.
     */
    const val VIEW_INSTALL_MIN_SDK = 25

    enum class Route { SYSTEM_INSTALLER, SESSION }

    /** One activity that resolved the APK `VIEW` intent (queried with `MATCH_SYSTEM_ONLY`). */
    data class Candidate(val packageName: String, val system: Boolean, val holdsInstallPackages: Boolean)

    /** The installer to pin: the first system app holding `INSTALL_PACKAGES`; null when there is none. */
    fun pinnedInstaller(candidates: List<Candidate>): String? = candidates.firstOrNull { it.system && it.holdsInstallPackages }?.packageName

    /**
     * One choice for every API level, by capability (review §3). [forceSession]: the debug switch (review S8).
     * [installerRouteFailed] (code review B1, [installerRouteFailed] below): the installer activity already failed for
     * this versionCode on this device, so the session is used; a device stuck on a broken installer route corrects
     * itself without a new release.
     */
    fun choose(sdk: Int, pinnedInstaller: String?, forceSession: Boolean, minSdk: Int = VIEW_INSTALL_MIN_SDK, installerRouteFailed: Boolean = false): Route =
        if (!forceSession && !installerRouteFailed && sdk >= minSdk && pinnedInstaller != null) Route.SYSTEM_INSTALLER else Route.SESSION

    // ---------------------------------------------------------------- route memory (code review 2026-11-10, B1)

    /** Misses of the installer route for one versionCode before its next Update uses the session. */
    const val MISSES_BEFORE_SWITCH = 2

    /** Why a hand-off through the installer activity ended without the update. */
    enum class Miss {
        /** The deadline: the installer never came to the front (silent abort, blocked start, a bounce under 700 ms). */
        NEVER_APPEARED,
        /** The app was left for the installer and came back: an honest Cancel / Back / Home, or an installer error screen. */
        LEFT_INSTALLER,
    }

    /**
     * [prefs] after a miss of the installer route for [versionCode]. NEVER_APPEARED switches at once. LEFT_INSTALLER
     * counts one: a first Cancel keeps the installer route (so Update → Install still ends on Done / Open), a second
     * return without the update for the same version switches (an installer that fails with its own error screen looks
     * exactly like a Cancel from here).
     */
    fun recordMiss(prefs: UpdatePrefs, versionCode: Long, miss: Miss): UpdatePrefs {
        val prior = if (prefs.installerRouteVersionCode == versionCode) prefs.installerRouteMisses else 0
        val misses = if (miss == Miss.NEVER_APPEARED) MISSES_BEFORE_SWITCH else (prior + 1).coerceAtMost(MISSES_BEFORE_SWITCH)
        return prefs.copy(installerRouteVersionCode = versionCode, installerRouteMisses = misses)
    }

    /** Whether the next Update of [versionCode] skips the installer activity. */
    fun installerRouteFailed(prefs: UpdatePrefs, versionCode: Long): Boolean =
        prefs.installerRouteVersionCode == versionCode && prefs.installerRouteMisses >= MISSES_BEFORE_SWITCH

    /** The memory is dropped once a version at least as new as the remembered one runs. */
    fun clearedOnStart(prefs: UpdatePrefs, runningVersionCode: Long): UpdatePrefs {
        val vc = prefs.installerRouteVersionCode ?: return prefs
        return if (runningVersionCode >= vc) prefs.copy(installerRouteVersionCode = null, installerRouteMisses = 0) else prefs
    }

    /** A `PackageInstaller.SessionInfo`, reduced to what [installerSessionInProgress] reads. */
    data class SessionFacts(val sessionId: Int, val appPackageName: String?, val active: Boolean, val committed: Boolean)

    /**
     * (review S5) Another installer is still writing or committing an update of [packageName] (the system installer's
     * own session while it shows "Installing…"). Our own session ([ownSessionId], the fallback route) does not count.
     */
    fun installerSessionInProgress(sessions: List<SessionFacts>, packageName: String, ownSessionId: Int?): Boolean =
        sessions.any { it.sessionId != ownSessionId && it.appPackageName == packageName && (it.active || it.committed) }
}

/**
 * (review S5) The hand-off watcher, pure and clock-driven (the caller feeds lifecycle events and an elapsed clock; the
 * updater adapts `ProcessLifecycleOwner` and `MainActivity`'s own callbacks). After a hand-off to the installer the app
 * gets no result, so it decides on its own when to look at the installed version again. Each event returns true when the caller must check **now** (installed versionCode, then an installer session in progress,
 * then "Not installed"). It returns true at most once per [arm] / [keepWaiting].
 *
 * - **Return trigger:** a process ON_START after a process ON_STOP, or a process ON_RESUME after a process ON_PAUSE,
 *   seen after the hand-off. The replayed events of `addObserver` are not a return (nothing left before them).
 *   (code review S1) On the session route a dialog-themed confirmation only pauses us, so pressing Install there also
 *   looks like a return; the caller therefore never abandons its own session on the first check, it waits (bounded)
 *   and re-checks the versionCode first.
 * - **Deadline:** starts at the hand-off and runs only while `MainActivity` itself is resumed; an activity pause
 *   after the hand-off cancels it. [deadlineMs] of continuous resumed time with no installer in front means the
 *   installer never appeared (a silent abort, a blocked start, a vendor no-op). One addition to the review's rule: a
 *   resume after a pause that the process level never reported (`ProcessLifecycleOwner` drops a bounce under 700 ms,
 *   which is what `InstallStart`'s silent abort looks like) starts a fresh deadline instead of leaving none.
 * - Never while the activity is not resumed: a trigger seen while it is paused or stopped waits for its resume.
 */
class HandOffWatcher(private val deadlineMs: Long = HAND_OFF_DEADLINE_MS, private val maxWaits: Int = MAX_WAITS) {
    var armed = false
        private set
    /** What made the last check fire (code review B1: a deadline means the installer never appeared). */
    var firedBy: Trigger? = null
        private set
    private var activityResumed = false
    private var leftStop = false
    private var leftPause = false
    private val left get() = leftStop || leftPause
    private var returned = false
    private var deadlineLive = false
    private var resumedSinceMs: Long? = null
    private var waits = 0

    enum class Trigger { RETURN, DEADLINE }

    /**
     * A hand-off happened at [nowMs]. [withDeadline] false: no deadline (the session route once its confirmation is
     * up: a return or its own statuses end it, and a deadline could fire while the user's Install runs).
     */
    fun arm(nowMs: Long, withDeadline: Boolean = true) {
        waits = 0
        this.withDeadline = withDeadline
        start(nowMs)
    }

    private var withDeadline = true

    /**
     * The check found the installer still at work: wait again (a fresh deadline and trigger). False when it has waited
     * [maxWaits] times already: then the caller shows "Not installed" (no stuck "Installing…", A3).
     */
    fun keepWaiting(nowMs: Long): Boolean {
        if (waits >= maxWaits) return false
        waits++
        withDeadline = true
        start(nowMs)
        return true
    }

    private fun start(nowMs: Long) {
        armed = true
        leftStop = false
        leftPause = false
        returned = false
        firedBy = null
        deadlineLive = withDeadline
        resumedSinceMs = if (activityResumed && withDeadline) nowMs else null
    }

    fun disarm() {
        armed = false
        deadlineLive = false
        resumedSinceMs = null
    }

    fun onActivityResumed(nowMs: Long): Boolean {
        activityResumed = true
        // A short bounce (no process-level leave): the installer is not in front; give it a fresh deadline.
        if (armed && withDeadline && !deadlineLive && !left) deadlineLive = true
        if (deadlineLive && resumedSinceMs == null) resumedSinceMs = nowMs
        return fire(nowMs)
    }

    fun onActivityPaused() {
        activityResumed = false
        if (armed) {
            deadlineLive = false
            resumedSinceMs = null
        }
    }

    fun onProcessStopped() { if (armed) leftStop = true }
    fun onProcessPaused() { if (armed) leftPause = true }

    fun onProcessStarted(nowMs: Long): Boolean {
        if (armed && leftStop) returned = true
        return fire(nowMs)
    }

    fun onProcessResumed(nowMs: Long): Boolean {
        if (armed && leftPause) returned = true
        return fire(nowMs)
    }

    /** The clock moved (the caller's timer at [deadlineAtMs]). */
    fun onTick(nowMs: Long): Boolean = fire(nowMs)

    /** When the deadline expires if the activity stays resumed; null while none runs. */
    fun deadlineAtMs(): Long? = resumedSinceMs?.takeIf { armed && deadlineLive }?.plus(deadlineMs)

    private fun fire(nowMs: Long): Boolean {
        if (!armed || !activityResumed) return false
        val expired = deadlineAtMs()?.let { nowMs >= it } == true
        if (!returned && !expired) return false
        disarm()
        firedBy = if (returned) Trigger.RETURN else Trigger.DEADLINE
        return true
    }

    companion object {
        /** Review Q5: 15 s of `MainActivity` resumed, never paused since the hand-off. */
        const val HAND_OFF_DEADLINE_MS = 15_000L
        /** How many times a check that finds the installer still at work waits again before "Not installed". */
        const val MAX_WAITS = 3
    }
}
