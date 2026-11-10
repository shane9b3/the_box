package app.thebox.core.update

import app.thebox.core.update.InstallRoute.Candidate
import app.thebox.core.update.InstallRoute.Route
import app.thebox.core.update.InstallRoute.SessionFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review B1 (the pinned installer, the route) and S5 (the session look-up, the watcher), all pure. */
class InstallHandOffTest {
    // ---------------------------------------------------------------- B1

    @Test
    fun onlyASystemAppHoldingInstallPackagesIsPinned() {
        val fileManager = Candidate("com.example.files", system = false, holdsInstallPackages = false)
        val hostile = Candidate("com.example.apkinstaller", system = false, holdsInstallPackages = true)
        val systemViewer = Candidate("com.vendor.viewer", system = true, holdsInstallPackages = false)
        val installer = Candidate("com.google.android.packageinstaller", system = true, holdsInstallPackages = true)
        assertEquals("com.google.android.packageinstaller", InstallRoute.pinnedInstaller(listOf(fileManager, hostile, systemViewer, installer)))
        assertNull(InstallRoute.pinnedInstaller(listOf(fileManager, hostile, systemViewer)))
        assertNull(InstallRoute.pinnedInstaller(emptyList()))
        val second = Candidate("com.amazon.installer", system = true, holdsInstallPackages = true)
        assertEquals("com.google.android.packageinstaller", InstallRoute.pinnedInstaller(listOf(installer, second)), "several: the first")
    }

    @Test
    fun theSystemInstallerOnEveryApiTheSessionAsTheFallback() {
        for (sdk in listOf(25, 28, 30, 34, 35)) assertEquals(Route.SYSTEM_INSTALLER, InstallRoute.choose(sdk, "pkg", forceSession = false), "API $sdk")
        assertEquals(Route.SESSION, InstallRoute.choose(30, null, forceSession = false), "no pinned installer")
        assertEquals(Route.SESSION, InstallRoute.choose(30, "pkg", forceSession = true), "the debug switch")
        assertEquals(25, InstallRoute.VIEW_INSTALL_MIN_SDK)
        // The one-line move of Fire OS 6 back to the session (review Q3).
        assertEquals(Route.SESSION, InstallRoute.choose(25, "pkg", forceSession = false, minSdk = 26))
        assertEquals(Route.SYSTEM_INSTALLER, InstallRoute.choose(26, "pkg", forceSession = false, minSdk = 26))
    }

    @Test
    fun anotherInstallersSessionForUsCountsOursDoesNot() {
        val ours = SessionFacts(7, "app.thebox", active = false, committed = true)
        assertFalse(InstallRoute.installerSessionInProgress(listOf(ours), "app.thebox", ownSessionId = 7))
        assertTrue(InstallRoute.installerSessionInProgress(listOf(ours.copy(sessionId = 8)), "app.thebox", ownSessionId = 7))
        assertTrue(InstallRoute.installerSessionInProgress(listOf(SessionFacts(9, "app.thebox", active = true, committed = false)), "app.thebox", null))
        assertFalse(InstallRoute.installerSessionInProgress(listOf(SessionFacts(9, "app.thebox", active = false, committed = false)), "app.thebox", null), "neither open nor committed")
        assertFalse(InstallRoute.installerSessionInProgress(listOf(SessionFacts(9, "com.other", active = true, committed = true)), "app.thebox", null))
    }

    // ---------------------------------------------------------------- S5 watcher

    /** The usual return: the installer covers us (activity pause, process stop), Cancel brings us back. */
    @Test
    fun returnFromTheInstallerChecksOnceWhenTheActivityIsResumed() {
        val w = HandOffWatcher()
        assertFalse(w.onActivityResumed(0))
        w.arm(1_000)
        assertEquals(16_000, w.deadlineAtMs())
        w.onActivityPaused()
        assertNull(w.deadlineAtMs(), "a pause cancels the deadline")
        w.onProcessPaused(); w.onProcessStopped()
        assertFalse(w.onProcessStarted(30_000), "started, but the activity is not resumed yet")
        assertTrue(w.onActivityResumed(30_010))
        assertFalse(w.armed)
        // Once only.
        w.onActivityPaused(); w.onProcessStopped()
        assertFalse(w.onProcessStarted(40_000)); assertFalse(w.onActivityResumed(40_010))
    }

    @Test
    fun aDialogInstallerOnlyPausesUs() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.onActivityPaused(); w.onProcessPaused()
        assertTrue(w.onActivityResumed(5_000) || w.onProcessResumed(5_001))
    }

    /** `addObserver` replays ON_START / ON_RESUME at once: nothing left before them, so they are no return. */
    @Test
    fun theReplayedEventsAreNoReturn() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        assertFalse(w.onProcessStarted(1))
        assertFalse(w.onProcessResumed(2))
        assertTrue(w.armed)
    }

    /** A silent abort: the installer never appears and we are never paused; the deadline fires after 15 s resumed. */
    @Test
    fun theDeadlineFiresAfterFifteenSecondsResumedNeverPaused() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(10_000)
        assertFalse(w.onTick(24_999))
        assertTrue(w.onTick(25_000))
        assertFalse(w.onTick(40_000), "once")
    }

    @Test
    fun theDeadlineStartsAtTheFirstResumeWhenHandedOffBeforeIt() {
        val w = HandOffWatcher()
        w.arm(0) // not resumed yet
        assertNull(w.deadlineAtMs())
        assertFalse(w.onActivityResumed(2_000))
        assertEquals(17_000, w.deadlineAtMs())
        assertTrue(w.onTick(17_000))
    }

    /** `InstallStart` aborts silently: a pause and resume under 700 ms that the process level never reports. */
    @Test
    fun aShortBounceStartsAFreshDeadline() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.onActivityPaused()
        assertFalse(w.onActivityResumed(300))
        assertEquals(15_300, w.deadlineAtMs())
        assertFalse(w.onTick(15_299))
        assertTrue(w.onTick(15_300))
    }

    /** Between installer screens the activity may resume briefly: the next pause cancels the fresh deadline again. */
    @Test
    fun aBounceThenTheInstallerCoversUsAgain() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.onActivityPaused()
        w.onActivityResumed(300)
        w.onActivityPaused()
        w.onProcessPaused(); w.onProcessStopped()
        assertFalse(w.onTick(60_000))
        assertFalse(w.onProcessStarted(70_000))
        assertTrue(w.onActivityResumed(70_010))
    }

    @Test
    fun noCheckWhileTheActivityIsNotResumed() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.onActivityPaused()
        assertFalse(w.onTick(60_000))
        w.onProcessStopped()
        assertFalse(w.onProcessStarted(61_000))
        assertTrue(w.onActivityResumed(61_100))
    }

    /** The installer still at work: wait again (fresh deadline), at most MAX_WAITS times, then "Not installed". */
    @Test
    fun keepWaitingIsBounded() {
        val w = HandOffWatcher(deadlineMs = 15_000, maxWaits = 2)
        w.onActivityResumed(0)
        w.arm(0)
        assertTrue(w.onTick(15_000))
        assertTrue(w.keepWaiting(15_000))
        assertEquals(30_000, w.deadlineAtMs())
        assertTrue(w.onTick(30_000))
        assertTrue(w.keepWaiting(30_000))
        assertTrue(w.onTick(45_000))
        assertFalse(w.keepWaiting(45_000), "bounded: no stuck Installing")
        assertFalse(w.armed)
        // A new hand-off starts the count again.
        w.arm(50_000)
        assertTrue(w.onTick(65_000))
        assertTrue(w.keepWaiting(65_000))
    }

    @Test
    fun disarmedNeverFires() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.disarm()
        assertFalse(w.onTick(100_000))
        assertNull(w.deadlineAtMs())
    }

    // ---------------------------------------------------------------- code review 2026-11-10

    /** B1: a route that failed for this versionCode on this device is skipped. */
    @Test
    fun aFailedInstallerRouteUsesTheSession() {
        assertEquals(Route.SESSION, InstallRoute.choose(30, "pkg", forceSession = false, installerRouteFailed = true))
        assertEquals(Route.SYSTEM_INSTALLER, InstallRoute.choose(30, "pkg", forceSession = false, installerRouteFailed = false))
    }

    private val fp = "google/atv/generic_x86:11/RSR1/1:user/release-keys"

    /** B1: never appeared → switch at once; an honest Cancel keeps the route once (Done / Open), a second miss switches. */
    @Test
    fun routeMemory() {
        val p0 = UpdatePrefs()
        assertFalse(InstallRoute.installerRouteFailed(p0, 7, fp))
        val cancel1 = InstallRoute.recordMiss(p0, 7, InstallRoute.Miss.LEFT_INSTALLER, fp)
        assertFalse(InstallRoute.installerRouteFailed(cancel1, 7, fp), "a first Cancel keeps the Open screen")
        val cancel2 = InstallRoute.recordMiss(cancel1, 7, InstallRoute.Miss.LEFT_INSTALLER, fp)
        assertTrue(InstallRoute.installerRouteFailed(cancel2, 7, fp))
        assertFalse(InstallRoute.installerRouteFailed(cancel2, 8, fp), "two hand Cancels on one version do not mark the device")
        assertNull(cancel2.installerRouteBrokenOn)
        // A miss for a new version restarts the count.
        assertEquals(1, InstallRoute.recordMiss(cancel2, 8, InstallRoute.Miss.LEFT_INSTALLER, fp).installerRouteMisses)
        // Dropped once that version (or newer) runs; kept while an older one runs.
        assertEquals(cancel2, InstallRoute.onStart(cancel2, 6, fp))
        val cleared = InstallRoute.onStart(cancel2, 7, fp)
        assertNull(cleared.installerRouteVersionCode)
        assertEquals(0, cleared.installerRouteMisses)
    }

    /** S-2 (b): NEVER_APPEARED marks this system broken for every later version, until the fingerprint changes. */
    @Test
    fun neverAppearedMarksTheDeviceUntilASystemUpdate() {
        val never = InstallRoute.recordMiss(UpdatePrefs(), 7, InstallRoute.Miss.NEVER_APPEARED, fp)
        assertTrue(InstallRoute.installerRouteFailed(never, 7, fp))
        val later = InstallRoute.onStart(never, 7, fp) // 7 installed through the session
        assertNull(later.installerRouteVersionCode)
        assertTrue(InstallRoute.installerRouteFailed(later, 8, fp), "the next release starts on the session route")
        val updated = InstallRoute.onStart(later, 7, "$fp-new")
        assertNull(updated.installerRouteBrokenOn, "a system update gives the installer route another chance")
        assertFalse(InstallRoute.installerRouteFailed(updated, 8, "$fp-new"))
    }

    /** S-2 (b): switching on two different versions marks the device too. */
    @Test
    fun switchesOnTwoVersionsMarkTheDevice() {
        var p = UpdatePrefs()
        repeat(2) { p = InstallRoute.recordMiss(p, 7, InstallRoute.Miss.LEFT_INSTALLER, fp) }
        assertEquals(7L, p.installerRouteSwitchedVersionCode)
        p = InstallRoute.recordMiss(p, 7, InstallRoute.Miss.LEFT_INSTALLER, fp) // a third miss on 7 is not a second version
        assertNull(p.installerRouteBrokenOn)
        p = InstallRoute.onStart(p, 7, fp)
        assertEquals(7L, p.installerRouteSwitchedVersionCode, "survives the per-version reset")
        repeat(2) { p = InstallRoute.recordMiss(p, 8, InstallRoute.Miss.LEFT_INSTALLER, fp) }
        assertEquals(fp, p.installerRouteBrokenOn)
        assertTrue(InstallRoute.installerRouteFailed(p, 9, fp))
    }

    /** S-2 (a): the attempt is written before the hand-off; a cold start still on the old version counts the miss. */
    @Test
    fun attemptAccounting() {
        val tried = InstallRoute.recordAttempt(UpdatePrefs(), 7)
        assertEquals(7L, tried.installerRouteAttemptVersionCode)
        // The process died while the installer was up; the user came back without the update.
        val miss = InstallRoute.onStart(tried, 6, fp)
        assertNull(miss.installerRouteAttemptVersionCode)
        assertEquals(7L, miss.installerRouteVersionCode)
        assertEquals(1, miss.installerRouteMisses)
        // An honest successful install: the start runs the new version → nothing counted.
        val ok = InstallRoute.onStart(tried, 7, fp)
        assertNull(ok.installerRouteAttemptVersionCode)
        assertNull(ok.installerRouteVersionCode)
        assertEquals(0, ok.installerRouteMisses)
        // An in-process miss consumes the attempt (no double count on the next start).
        val inProcess = InstallRoute.recordMiss(tried, 7, InstallRoute.Miss.LEFT_INSTALLER, fp)
        assertNull(inProcess.installerRouteAttemptVersionCode)
        assertEquals(1, InstallRoute.onStart(inProcess, 6, fp).installerRouteMisses)
        // Landed in process.
        assertNull(InstallRoute.clearAttempt(tried).installerRouteAttemptVersionCode)
        // Two cold-start misses switch the route for that version.
        val twice = InstallRoute.onStart(InstallRoute.recordAttempt(miss, 7), 6, fp)
        assertTrue(InstallRoute.installerRouteFailed(twice, 7, fp))
    }

    /** S-3: only the session in flight; an abandoned one's late status is ignored. */
    @Test
    fun statusesBelongToTheSessionInFlight() {
        assertTrue(InstallRoute.acceptsStatus(12, current = 12))
        assertFalse(InstallRoute.acceptsStatus(11, current = 12), "an older session")
        assertFalse(InstallRoute.acceptsStatus(12, current = null), "abandoned: nothing in flight")
        assertTrue(InstallRoute.acceptsStatus(-1, current = 12), "a platform without the id")
        assertFalse(InstallRoute.acceptsStatus(-1, current = null))
    }

    /** S-3: one update job at a time. */
    @Test
    fun singleFlight() {
        val f = SingleFlight()
        assertTrue(f.tryStart())
        assertTrue(f.busy)
        assertFalse(f.tryStart(), "a second job while one runs")
        f.done()
        assertFalse(f.busy)
        assertTrue(f.tryStart())
    }

    /** B1: the prefs round-trip, and an older stored document (without the new fields) still reads. */
    @Test
    fun routeMemorySurvivesThePrefsDocument() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val p = InstallRoute.recordAttempt(InstallRoute.recordMiss(UpdatePrefs(highestSequence = 7), 7, InstallRoute.Miss.NEVER_APPEARED, "fp"), 8)
        assertEquals(p, json.decodeFromString(UpdatePrefs.serializer(), json.encodeToString(UpdatePrefs.serializer(), p)))
        val old = json.decodeFromString(UpdatePrefs.serializer(), """{"checkAutomatically":true,"lastCheckMs":5,"highestSequence":6}""")
        assertNull(old.installerRouteVersionCode)
        assertEquals(0, old.installerRouteMisses)
        assertNull(old.installerRouteAttemptVersionCode)
        assertNull(old.installerRouteSwitchedVersionCode)
        assertNull(old.installerRouteBrokenOn)
    }

    @Test
    fun theWatcherSaysWhatFired() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        assertTrue(w.onTick(15_000))
        assertEquals(HandOffWatcher.Trigger.DEADLINE, w.firedBy)
        w.arm(20_000)
        w.onActivityPaused(); w.onProcessPaused(); w.onProcessStopped()
        w.onProcessStarted(30_000)
        assertTrue(w.onActivityResumed(30_001))
        assertEquals(HandOffWatcher.Trigger.RETURN, w.firedBy)
    }

    /**
     * Second code review S-1: the session route has a deadline too. It does not run while the (dialog) confirmation
     * pauses us; Install or BACK bring us back (a return), and the caller waits again with a fresh deadline
     * (keepWaiting) and re-reads the versionCode before it abandons its own session.
     */
    @Test
    fun sessionRouteReturn() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0) // PENDING_USER_ACTION
        w.onActivityPaused(); w.onProcessPaused() // the confirmation is up
        assertNull(w.deadlineAtMs())
        assertFalse(w.onTick(60_000), "no deadline while the confirmation covers us")
        assertFalse(w.onActivityResumed(70_000))
        assertTrue(w.onProcessResumed(70_001), "Install or BACK: back in front")
        assertEquals(HandOffWatcher.Trigger.RETURN, w.firedBy)
        assertTrue(w.keepWaiting(70_001))
        assertEquals(85_001, w.deadlineAtMs())
        assertTrue(w.onTick(85_001))
    }

    /** S-1: a confirmation dismissed in under 700 ms (no process-level report) ends in a check after the deadline. */
    @Test
    fun sessionRouteBounceEndsAfterTheDeadline() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        w.onActivityPaused()
        assertFalse(w.onActivityResumed(300))
        assertEquals(15_300, w.deadlineAtMs())
        assertTrue(w.onTick(15_300))
        assertEquals(HandOffWatcher.Trigger.DEADLINE, w.firedBy)
    }

    /** S-1: a confirmation that never comes up (or no EXTRA_INTENT reported as one): the deadline still fires. */
    @Test
    fun sessionRouteNoShowEndsAfterTheDeadline() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0)
        assertTrue(w.onTick(15_000))
    }

    /** S-1: PENDING_USER_ACTION without EXTRA_INTENT is a failure with a reason; nothing is started. */
    @Test
    fun pendingUserActionWithoutAnIntentFails() {
        var started = 0
        assertEquals(InstallRoute.NO_CONFIRMATION, InstallRoute.confirmationFailure(hasIntent = false) { started++; null })
        assertEquals(0, started)
        assertNull(InstallRoute.confirmationFailure(hasIntent = true) { started++; null })
        assertEquals(1, started)
        assertEquals("ActivityNotFoundException", InstallRoute.confirmationFailure(hasIntent = true) { "ActivityNotFoundException" })
    }
}
