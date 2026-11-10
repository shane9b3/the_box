package app.thebox.core.update

import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain

/** Named constants of the updater (docs/design/m5-setup-settings.md Appendix B, §3.12). */
object UpdateTimings {
    /** At most one automatic check per 24 h (§3.12.2). */
    const val UPDATE_CHECK_INTERVAL_MS = 86_400_000L
    /** The first automatic check of a process waits 60 s after the first frame (§3.12.2). */
    const val UPDATE_FIRST_CHECK_DELAY_MS = 60_000L
    /**
     * (review S3) A start that finds a downloaded update newer than the running version checks this long after the
     * first frame (the network may still be coming up), whatever the 24 h cadence.
     */
    const val UPDATE_RESUME_CHECK_DELAY_MS = 2_000L
    /** `update.json` is a few hundred bytes; anything larger is refused before parsing. */
    const val MANIFEST_MAX_BYTES = 65_536
    /** Raw Ed25519 signature (§3.12.1). */
    const val SIGNATURE_BYTES = 64
    /** Free space needed before a download starts: twice the APK (§3.12.3). */
    const val FREE_SPACE_FACTOR = 2L
    /** The only manifest schema this client understands (§3.12.2 step 2). */
    const val SCHEMA = 1
    /** Redirects of the release download are followed only to these hosts (§3.12.2, F13; S7). */
    val REDIRECT_HOSTS: Set<String> = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
}

/**
 * The `update` settings domain (§2.2 `Domains.UPDATE`, §4.1): device-bound, never backed up. Declared here because the
 * updater is its only writer (the About row `about.auto_check` writes `checkAutomatically` through the same object).
 * WP-A may fold it into its domain registry, keeping the name.
 */
val UpdateDomain: SettingsDomain<UpdatePrefs> = SettingsDomain(
    name = Domains.UPDATE,
    serializer = UpdatePrefs.serializer(),
    default = UpdatePrefs(),
    backedUp = false,
)

/**
 * When the updater checks (§3.12.2). Pure: every decision takes the clock as an argument (`UpdatePolicyTest`).
 *
 * - Automatic checks run only while `checkAutomatically` is on, and only when the last check is at least
 *   [UpdateTimings.UPDATE_CHECK_INTERVAL_MS] old, never sooner than [UpdateTimings.UPDATE_FIRST_CHECK_DELAY_MS] after
 *   the first frame. A clock that moved backwards (last check "in the future") counts as due, so a wrong clock cannot
 *   silence updates for ever.
 * - A manual check always runs.
 * - At most one automatic check per process start ([checkedThisProcess]), so a long-running box asks once a day through
 *   the periodic worker, never in a loop.
 */
object UpdatePolicy {
    fun isDue(prefs: UpdatePrefs, nowMs: Long): Boolean {
        if (!prefs.checkAutomatically) return false
        val last = prefs.lastCheckMs ?: return true
        return nowMs < last || nowMs - last >= UpdateTimings.UPDATE_CHECK_INTERVAL_MS
    }

    /**
     * Whether a check requested now runs. [resume] (review S3): a verified download newer than the running version is
     * on disk, so the user pressed Update and was interrupted (the API 30 "Install unknown apps" grant kills the
     * process): the check runs whatever the cadence, once per process.
     */
    fun shouldRun(prefs: UpdatePrefs, nowMs: Long, manual: Boolean, checkedThisProcess: Boolean, resume: Boolean = false): Boolean =
        manual || (!checkedThisProcess && (resume || isDue(prefs, nowMs)))

    /**
     * (review S3, N7) What a start does with `filesDir/update`: [keepVersionCode] is the highest `{versionCode}.apk`
     * newer than [runningVersionCode] (kept, and the start checks again soon so Update can reuse it after re-verifying
     * it); every other file (older or equal APKs, `.part` leftovers, anything else) is in [delete].
     */
    fun startupFiles(names: List<String>, runningVersionCode: Long): StartupFiles {
        val keep = names.mapNotNull { n -> n.removeSuffix(".apk").takeIf { n.endsWith(".apk") }?.toLongOrNull() }.filter { it > runningVersionCode }.maxOrNull()
        return StartupFiles(keep, names.filter { keep == null || it != "$keep.apk" })
    }

    data class StartupFiles(val keepVersionCode: Long?, val delete: List<String>)

    /** What the last check ended in, as far as a waiting download is concerned (code review 2026-11-10, S4). */
    sealed interface CheckOutcome {
        data class Offered(val versionCode: Long) : CheckOutcome
        /** A valid manifest with nothing for this install (up to date, skipped, withdrawn, rollout). */
        data object Nothing : CheckOutcome
        /** The manifest was refused, or updates are disabled for this build. */
        data object Rejected : CheckOutcome
        /** No answer (network). */
        data object Unreachable : CheckOutcome
        /** The check did not run (busy, or another check ran first this process). */
        data object NotRun : CheckOutcome
    }

    /**
     * (code review S4) Whether the waiting download [versionCode] stays after the restart check ended in [outcome]: only
     * while that version is still offered, or the check could not tell. Otherwise it is deleted, so it cannot make every
     * cold start check again for ever.
     */
    fun keepsWaitingFile(versionCode: Long, outcome: CheckOutcome): Boolean = when (outcome) {
        is CheckOutcome.Offered -> outcome.versionCode == versionCode
        CheckOutcome.Nothing, CheckOutcome.Rejected -> false
        CheckOutcome.Unreachable, CheckOutcome.NotRun -> true
    }

    /** (review S5) Back from the installer: did the update land (our process simply was not killed)? */
    fun landed(installedVersionCode: Long, targetVersionCode: Long): Boolean = installedVersionCode >= targetVersionCode

    /**
     * Delay from the first frame to the first automatic check of this process, or null when none is planned (automatic
     * checks off). Never below [UpdateTimings.UPDATE_FIRST_CHECK_DELAY_MS].
     */
    fun firstCheckDelayMs(prefs: UpdatePrefs, nowMs: Long): Long? {
        if (!prefs.checkAutomatically) return null
        val last = prefs.lastCheckMs
        val untilDue = if (last == null || nowMs < last) 0L else (last + UpdateTimings.UPDATE_CHECK_INTERVAL_MS - nowMs).coerceAtLeast(0L)
        return untilDue.coerceAtLeast(UpdateTimings.UPDATE_FIRST_CHECK_DELAY_MS)
    }

    /** The install's rollout bucket, drawn once (0–99) and stored (§2.10 `rolloutPercent`). */
    fun bucketOf(prefs: UpdatePrefs, random: () -> Int): Pair<Int, UpdatePrefs> {
        prefs.bucket?.takeIf { it in 0..99 }?.let { return it to prefs }
        val b = Math.floorMod(random(), 100)
        return b to prefs.copy(bucket = b)
    }

    /** Failures of automatic checks stay silent (§3.12.2 step 1: BAD_SIGNATURE is shown for manual checks only). */
    fun showsFailure(failure: UpdateFailure, manual: Boolean): Boolean = manual || when (failure) {
        UpdateFailure.NETWORK, UpdateFailure.BAD_SIGNATURE, UpdateFailure.BAD_MANIFEST, UpdateFailure.REPLAYED,
        UpdateFailure.NOT_NEWER, UpdateFailure.DISABLED -> false
        // Failures after the user pressed Update are always shown.
        UpdateFailure.HASH_MISMATCH, UpdateFailure.WRONG_PACKAGE, UpdateFailure.WRONG_CERT, UpdateFailure.NO_SPACE,
        UpdateFailure.INSTALL_BLOCKED, UpdateFailure.INSTALL_FAILED, UpdateFailure.INSTALL_RESTRICTED -> true
    }
}

/**
 * (review S1; code review 2026-11-10 S5 adds ReadyToInstall: it shows no buttons, the hand-off is under way or waits for
 * the next foreground) One "busy" rule for every entry point: a check ([UpdatePolicy] callers), a second download and the About
 * row's enabled state. While busy, nothing may overwrite the state or start another download (the download coroutine
 * is the only writer of `filesDir/update`, so this also keeps the file the installer reads unchanged).
 */
val UpdateState.isBusy: Boolean get() = this is UpdateState.Downloading || this is UpdateState.Verifying || this is UpdateState.ReadyToInstall || this == UpdateState.Installing

/**
 * (review S6) The hand-off never starts from the background: a file verified there waits for the next process start.
 * Pure; main thread in the updater.
 */
class DeferredHandOff {
    var pending = false
        private set

    /** The file is verified: true = hand it off now; false = it waits for [onStart]. */
    fun onVerified(inForeground: Boolean): Boolean {
        pending = !inForeground
        return inForeground
    }

    /** The process came to the front: true once when a verified file waits. */
    fun onStart(): Boolean = pending.also { pending = false }

    fun clear() { pending = false }
}
