package app.thebox.core.update

import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain

/** Named constants of the updater (docs/design/m5-setup-settings.md Appendix B, §3.12). */
object UpdateTimings {
    /** At most one automatic check per 24 h (§3.12.2). */
    const val UPDATE_CHECK_INTERVAL_MS = 86_400_000L
    /** The first automatic check of a process waits 60 s after the first frame (§3.12.2). */
    const val UPDATE_FIRST_CHECK_DELAY_MS = 60_000L
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

    /** Whether a check requested now runs. */
    fun shouldRun(prefs: UpdatePrefs, nowMs: Long, manual: Boolean, checkedThisProcess: Boolean): Boolean =
        manual || (!checkedThisProcess && isDue(prefs, nowMs))

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
        UpdateFailure.INSTALL_BLOCKED, UpdateFailure.INSTALL_FAILED -> true
    }
}
