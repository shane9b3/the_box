package app.thebox.core.parental

/**
 * Wrong-PIN lockout (docs/design/m5-setup-settings.md §3.9.4), pure. The caller persists [Attempts] (SharedPreferences
 * `parental_attempts`, not backed up), so a restart does not reset the counter.
 *
 * | Consecutive wrong PINs | Wait |
 * |---|---|
 * | 1–2 | none |
 * | 3 | 30 s |
 * | 6 | 2 min |
 * | 9 | 10 min |
 * | 12 and every 3 after | 30 min |
 *
 * Time: elapsed-realtime when the boot is the same (`Settings.Global.BOOT_COUNT`), else the wall clock **capped at the
 * step's length**, so moving the clock back (or a reboot) can neither extend nor skip a wait by more than one step.
 */
object LockoutPolicy {
    /** What is persisted. [lockedAt*] are null while no wait is running. */
    data class Attempts(
        val failures: Int = 0,
        val lockedAtElapsedMs: Long? = null,
        val lockedAtWallMs: Long? = null,
        /** BOOT_COUNT when the wait started; null when the platform does not report it. */
        val lockedAtBoot: Int? = null,
        val waitMs: Long = 0,
    )

    /** Now, as the store reads it. [bootCount] null = unknown (always treated as another boot). */
    data class Clock(val elapsedMs: Long, val wallMs: Long, val bootCount: Int?)

    const val STEP = 3
    const val WAIT_3_MS = 30_000L
    const val WAIT_6_MS = 120_000L
    const val WAIT_9_MS = 600_000L
    const val WAIT_MAX_MS = 1_800_000L

    /** The wait that starts when the [failures]-th consecutive wrong PIN arrives (0 = none). */
    fun waitAfter(failures: Int): Long = when {
        failures <= 0 || failures % STEP != 0 -> 0L
        failures == 3 -> WAIT_3_MS
        failures == 6 -> WAIT_6_MS
        failures == 9 -> WAIT_9_MS
        else -> WAIT_MAX_MS
    }

    /** Wrong PINs left before the next wait (1…3). */
    fun attemptsBeforeLockout(failures: Int): Int = STEP - (failures.coerceAtLeast(0) % STEP)

    /** Remaining wait at [now], 0 when none is running. */
    fun remainingMs(a: Attempts, now: Clock): Long {
        if (a.waitMs <= 0 || (a.lockedAtElapsedMs == null && a.lockedAtWallMs == null)) return 0L
        val sameBoot = a.lockedAtBoot != null && now.bootCount != null && a.lockedAtBoot == now.bootCount && a.lockedAtElapsedMs != null
        val passed = if (sameBoot) {
            now.elapsedMs - a.lockedAtElapsedMs!!
        } else {
            val wall = a.lockedAtWallMs ?: return 0L
            now.wallMs - wall
        }
        // A clock moved back (negative passed) restarts at most one full step; never more than the step itself.
        val p = passed.coerceAtLeast(0L)
        return (a.waitMs - p).coerceIn(0L, a.waitMs)
    }

    /** Wall-clock end of the running wait (for the prompt's countdown), or null. */
    fun lockedUntilWallMs(a: Attempts, now: Clock): Long? = remainingMs(a, now).takeIf { it > 0 }?.let { now.wallMs + it }

    /** A wrong PIN at [now]: the new attempts and the answer for the prompt. Call only when no wait is running. */
    fun onWrong(a: Attempts, now: Clock): Pair<Attempts, PinCheck> {
        val failures = a.failures + 1
        val wait = waitAfter(failures)
        return if (wait > 0) {
            val next = Attempts(failures, now.elapsedMs, now.wallMs, now.bootCount, wait)
            next to PinCheck.LockedOut(now.wallMs + wait)
        } else {
            Attempts(failures) to PinCheck.Wrong(attemptsBeforeLockout(failures))
        }
    }

    /** A correct PIN resets the counter. */
    fun onCorrect(): Attempts = Attempts()
}
