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
 * Time (security review 2026-11-09 F7, owner decision 2026-11-11): the wait is measured on **elapsed realtime only**;
 * the wall clock never shortens or lengthens it. Within one boot the wait continues across process death. After a
 * reboot it **restarts with its full length** (same ladder step): elapsed realtime starts again from 0 at boot, so
 * time from the previous boot cannot be credited without trusting the wall clock. Accepted cost: a TV restarted
 * during a wait waits the full step again (at most 30 minutes).
 *
 * A reboot is seen when `Settings.Global.BOOT_COUNT` differs from the one stored at the start of the wait, or when
 * elapsed realtime is below the stored anchor (it went backwards). When the boot count is unknown on either side only
 * the second test applies; an undetected reboot then credits at most `now.elapsed − anchor`, which is less than the
 * real time since the wait started (the new boot's whole uptime is shorter than that), so it can never end a wait early.
 *
 * Callers persist [resolve]'s answer whenever it differs from what they read: a reboot re-anchors the wait at the new
 * boot, and a finished wait is cleared (so a later reboot cannot restart a wait that was already served).
 */
object LockoutPolicy {
    /**
     * What is persisted. [lockedAtElapsedMs] and [lockedAtBoot] anchor the running wait of [waitMs]; [waitMs] is 0 while
     * no wait is running. [failures] (the ladder step) survives everything except a correct PIN.
     */
    data class Attempts(
        val failures: Int = 0,
        val lockedAtElapsedMs: Long? = null,
        /** BOOT_COUNT when the wait was anchored; null when the platform does not report it. */
        val lockedAtBoot: Int? = null,
        val waitMs: Long = 0,
    ) {
        val waiting: Boolean get() = waitMs > 0
    }

    /**
     * Now, as the store reads it. [elapsedMs] is elapsed realtime (monotonic within a boot); [wallMs] is only used to
     * turn the remaining wait into a wall-clock end for the countdown display. [bootCount] null = unknown.
     */
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

    /** True when [a]'s anchor belongs to the boot [now] was read in (see the class comment). */
    fun sameBoot(a: Attempts, now: Clock): Boolean {
        val at = a.lockedAtElapsedMs ?: return false
        if (now.elapsedMs < at) return false
        val then = a.lockedAtBoot
        val current = now.bootCount
        return then == null || current == null || then == current
    }

    /**
     * The attempts as they stand at [now]: unchanged while a wait runs on this boot or none runs; re-anchored at [now]
     * with the full [Attempts.waitMs] after a reboot (or with no usable anchor); cleared (wait 0, counter kept) once the
     * wait has run out on this boot. The caller persists the answer when it differs from [a].
     */
    fun resolve(a: Attempts, now: Clock): Attempts {
        if (!a.waiting) {
            return if (a.lockedAtElapsedMs == null && a.lockedAtBoot == null && a.waitMs == 0L) a else Attempts(a.failures)
        }
        if (!sameBoot(a, now)) return a.copy(lockedAtElapsedMs = now.elapsedMs, lockedAtBoot = now.bootCount)
        val passed = now.elapsedMs - a.lockedAtElapsedMs!!
        return if (passed >= a.waitMs) Attempts(a.failures) else a
    }

    /** Remaining wait at [now] (0 when none runs), after [resolve]. */
    fun remainingMs(a: Attempts, now: Clock): Long {
        val r = resolve(a, now)
        if (!r.waiting) return 0L
        val passed = now.elapsedMs - r.lockedAtElapsedMs!!
        return (r.waitMs - passed).coerceIn(0L, r.waitMs)
    }

    /** Wall-clock end of the running wait, for the prompt's countdown only (never for the decision), or null. */
    fun lockedUntilWallMs(a: Attempts, now: Clock): Long? = remainingMs(a, now).takeIf { it > 0 }?.let { now.wallMs + it }

    /** A wrong PIN at [now]: the new attempts and the answer for the prompt. Call only when no wait is running. */
    fun onWrong(a: Attempts, now: Clock): Pair<Attempts, PinCheck> {
        val failures = a.failures + 1
        val wait = waitAfter(failures)
        return if (wait > 0) {
            val next = Attempts(failures, now.elapsedMs, now.bootCount, wait)
            next to PinCheck.LockedOut(now.wallMs + wait)
        } else {
            Attempts(failures) to PinCheck.Wrong(attemptsBeforeLockout(failures))
        }
    }

    /** A correct PIN resets the counter. */
    fun onCorrect(): Attempts = Attempts()
}
