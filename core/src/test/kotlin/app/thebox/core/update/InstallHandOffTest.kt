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

    /** B1: never appeared → switch at once; an honest Cancel keeps the route once (Done / Open), a second miss switches. */
    @Test
    fun routeMemory() {
        val p0 = UpdatePrefs()
        assertFalse(InstallRoute.installerRouteFailed(p0, 7))
        val never = InstallRoute.recordMiss(p0, 7, InstallRoute.Miss.NEVER_APPEARED)
        assertTrue(InstallRoute.installerRouteFailed(never, 7))
        assertFalse(InstallRoute.installerRouteFailed(never, 8), "another version starts on the installer route again")
        val cancel1 = InstallRoute.recordMiss(p0, 7, InstallRoute.Miss.LEFT_INSTALLER)
        assertFalse(InstallRoute.installerRouteFailed(cancel1, 7), "a first Cancel keeps the Open screen")
        val cancel2 = InstallRoute.recordMiss(cancel1, 7, InstallRoute.Miss.LEFT_INSTALLER)
        assertTrue(InstallRoute.installerRouteFailed(cancel2, 7))
        // A miss for a new version restarts the count.
        assertEquals(1, InstallRoute.recordMiss(cancel2, 8, InstallRoute.Miss.LEFT_INSTALLER).installerRouteMisses)
        // Cleared once that version (or newer) runs; kept while an older one runs.
        assertEquals(cancel2, InstallRoute.clearedOnStart(cancel2, 6))
        val cleared = InstallRoute.clearedOnStart(cancel2, 7)
        assertNull(cleared.installerRouteVersionCode)
        assertEquals(0, cleared.installerRouteMisses)
    }

    /** B1: the prefs round-trip, and an older stored document (without the new fields) still reads. */
    @Test
    fun routeMemorySurvivesThePrefsDocument() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val p = InstallRoute.recordMiss(UpdatePrefs(highestSequence = 7), 7, InstallRoute.Miss.NEVER_APPEARED)
        assertEquals(p, json.decodeFromString(UpdatePrefs.serializer(), json.encodeToString(UpdatePrefs.serializer(), p)))
        val old = json.decodeFromString(UpdatePrefs.serializer(), """{"checkAutomatically":true,"lastCheckMs":5,"highestSequence":6}""")
        assertNull(old.installerRouteVersionCode)
        assertEquals(0, old.installerRouteMisses)
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
     * S1: the session route once its confirmation is up has no deadline (Install may be running). A dialog confirmation
     * only pauses us: Install or BACK both look like a pause → resume return; the caller then waits with a fresh
     * deadline (keepWaiting) and re-reads the versionCode before it abandons its own session.
     */
    @Test
    fun sessionRouteReturn() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0, withDeadline = false)
        assertNull(w.deadlineAtMs())
        assertFalse(w.onTick(60_000), "no deadline while the confirmation may be answered")
        w.onActivityPaused(); w.onProcessPaused() // the dialog confirmation
        assertFalse(w.onActivityResumed(70_000))
        assertNull(w.deadlineAtMs(), "no fresh deadline either")
        assertTrue(w.onProcessResumed(70_001), "Install or BACK: back in front")
        assertEquals(HandOffWatcher.Trigger.RETURN, w.firedBy)
        // The grace: a fresh 15 s before the caller may abandon its session.
        assertTrue(w.keepWaiting(70_001))
        assertEquals(85_001, w.deadlineAtMs())
        assertTrue(w.onTick(85_001))
    }

    /** A quick bounce on the session route (no process-level report) neither fires nor starts a deadline. */
    @Test
    fun sessionRouteBounceDoesNothing() {
        val w = HandOffWatcher()
        w.onActivityResumed(0)
        w.arm(0, withDeadline = false)
        w.onActivityPaused()
        assertFalse(w.onActivityResumed(300))
        assertNull(w.deadlineAtMs())
        assertTrue(w.armed)
    }
}
