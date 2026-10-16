package app.thebox.core.parental

import app.thebox.core.parental.LockoutPolicy.Attempts
import app.thebox.core.parental.LockoutPolicy.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** docs/design/m5-setup-settings.md §3.9.4: the table, the persisted counter, the clock moved back and a reboot. */
class LockoutPolicyTest {
    private val boot = 7
    private fun clock(elapsed: Long, wall: Long = 1_000_000_000L + elapsed, bootCount: Int? = boot) = Clock(elapsed, wall, bootCount)

    @ParameterizedTest
    @CsvSource("1,0", "2,0", "3,30000", "4,0", "5,0", "6,120000", "8,0", "9,600000", "12,1800000", "15,1800000", "18,1800000", "13,0")
    fun table(failures: Int, waitMs: Long) = assertEquals(waitMs, LockoutPolicy.waitAfter(failures))

    @ParameterizedTest
    @CsvSource("0,3", "1,2", "2,1", "3,3", "4,2", "5,1")
    fun attemptsBeforeLockout(failures: Int, left: Int) = assertEquals(left, LockoutPolicy.attemptsBeforeLockout(failures))

    /** Wrong, wrong, wrong → 30 s; the counter is what the caller persists, so a "restart" (a new run with it) keeps it. */
    @Test fun thirdWrongPinLocksForThirtySeconds() {
        var a = Attempts()
        val (a1, c1) = LockoutPolicy.onWrong(a, clock(0)); a = a1
        assertEquals(PinCheck.Wrong(2), c1)
        val (a2, c2) = LockoutPolicy.onWrong(a, clock(1_000)); a = a2
        assertEquals(PinCheck.Wrong(1), c2)
        val (a3, c3) = LockoutPolicy.onWrong(a, clock(2_000)); a = a3
        val locked = assertIs<PinCheck.LockedOut>(c3)
        assertEquals(clock(2_000).wallMs + 30_000, locked.untilMs)
        // "Restart": the persisted attempts are read back unchanged.
        val restored = a.copy()
        assertEquals(29_000, LockoutPolicy.remainingMs(restored, clock(3_000)))
        assertEquals(0, LockoutPolicy.remainingMs(restored, clock(32_000)))
        assertNull(LockoutPolicy.lockedUntilWallMs(restored, clock(32_001)))
        // The counter carries on after the wait: three more give the 2-minute step.
        var b = restored
        repeat(2) { b = LockoutPolicy.onWrong(b, clock(40_000)).first }
        val (b3, c6) = LockoutPolicy.onWrong(b, clock(41_000))
        assertEquals(6, b3.failures)
        assertEquals(clock(41_000).wallMs + 120_000, assertIs<PinCheck.LockedOut>(c6).untilMs)
    }

    @Test fun correctPinResetsTheCounter() {
        assertEquals(Attempts(), LockoutPolicy.onCorrect())
        val (a, _) = LockoutPolicy.onWrong(LockoutPolicy.onCorrect(), clock(0))
        assertEquals(1, a.failures)
    }

    /** Same boot: elapsed realtime decides; the wall clock moved back or forward changes nothing. */
    @Test fun wallClockChangesDoNotMatterOnTheSameBoot() {
        val (a, _) = LockoutPolicy.onWrong(Attempts(failures = 2), clock(10_000, wall = 5_000_000))
        assertEquals(20_000, LockoutPolicy.remainingMs(a, clock(20_000, wall = 1_000)), "clock moved back")
        assertEquals(20_000, LockoutPolicy.remainingMs(a, clock(20_000, wall = 9_000_000_000)), "clock moved forward")
    }

    /** Another boot: wall clock, capped at the step, so a clock moved back cannot extend the wait. */
    @Test fun rebootUsesTheWallClockCappedAtTheStep() {
        val (a, _) = LockoutPolicy.onWrong(Attempts(failures = 2), clock(10_000, wall = 5_000_000))
        // Rebooted (elapsed restarted, boot count +1) 10 s later.
        assertEquals(20_000, LockoutPolicy.remainingMs(a, Clock(500, 5_010_000, boot + 1)))
        // Clock moved back an hour after the reboot: at most one full step, never more.
        assertEquals(30_000, LockoutPolicy.remainingMs(a, Clock(500, 5_000_000 - 3_600_000, boot + 1)))
        // Clock moved far forward: the wait is over (skips at most the one step).
        assertEquals(0, LockoutPolicy.remainingMs(a, Clock(500, 5_000_000 + 3_600_000, boot + 1)))
        // Unknown boot count (F26 unverified): treated as another boot.
        assertEquals(20_000, LockoutPolicy.remainingMs(a, Clock(99_999, 5_010_000, null)))
    }

    @Test fun noWaitRunning() {
        assertEquals(0, LockoutPolicy.remainingMs(Attempts(failures = 1), clock(0)))
        assertNull(LockoutPolicy.lockedUntilWallMs(Attempts(), clock(0)))
    }
}
