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
        assertEquals(20_000, LockoutPolicy.remainingMs(a, clock(20_000, wall = Long.MAX_VALUE / 2)), "clock moved years forward")
        assertEquals(a, LockoutPolicy.resolve(a, clock(20_000, wall = Long.MAX_VALUE / 2)), "nothing re-anchored")
        // The countdown's wall end follows whatever the wall clock says now (display only).
        assertEquals(1_000 + 20_000L, LockoutPolicy.lockedUntilWallMs(a, clock(20_000, wall = 1_000)))
    }

    /**
     * (F7, 2026-11-11; replaces `rebootUsesTheWallClockCappedAtTheStep`, which pinned the old wall-clock fallback that
     * F7 found weak) Another boot: the wait restarts in full at the same step, whatever the wall clock says.
     */
    @Test fun rebootRestartsTheFullWaitWhateverTheWallClock() {
        val (a, _) = LockoutPolicy.onWrong(Attempts(failures = 2), clock(10_000, wall = 5_000_000))
        for (wall in listOf(5_010_000L, 5_000_000L - 3_600_000L, 5_000_000L + 3_600_000L, 5_000_000L + 365L * 86_400_000L)) {
            val after = Clock(500, wall, boot + 1)
            assertEquals(30_000, LockoutPolicy.remainingMs(a, after), "wall $wall")
            val r = LockoutPolicy.resolve(a, after)
            assertEquals(Attempts(3, 500, boot + 1, 30_000), r, "re-anchored on the new boot, same step")
        }
        // Once re-anchored, the new boot's elapsed realtime runs the wait down and ends it normally.
        val r = LockoutPolicy.resolve(a, Clock(500, 0, boot + 1))
        assertEquals(10_000, LockoutPolicy.remainingMs(r, Clock(20_500, 0, boot + 1)))
        assertEquals(0, LockoutPolicy.remainingMs(r, Clock(30_500, 0, boot + 1)))
        assertEquals(Attempts(3), LockoutPolicy.resolve(r, Clock(30_500, 0, boot + 1)), "served: cleared, counter kept")
    }

    /** Boot count unknown (F26 unverified): elapsed realtime going backwards is a reboot; going forward is credited. */
    @Test fun unknownBootCountFallsBackToElapsedGoingBackwards() {
        val (a, _) = LockoutPolicy.onWrong(Attempts(failures = 2), Clock(10_000, 5_000_000, null))
        assertEquals(20_000, LockoutPolicy.remainingMs(a, Clock(20_000, 0, null)), "same boot (elapsed went on)")
        assertEquals(30_000, LockoutPolicy.remainingMs(a, Clock(500, 9_000_000_000, null)), "elapsed went back: reboot, full wait")
        // Known at the start, unknown now (or the other way round): the elapsed test alone.
        val (b, _) = LockoutPolicy.onWrong(Attempts(failures = 2), clock(10_000))
        assertEquals(20_000, LockoutPolicy.remainingMs(b, Clock(20_000, 0, null)))
        assertEquals(30_000, LockoutPolicy.remainingMs(b, Clock(9_000, 0, null)))
        // Same boot count but elapsed below the anchor cannot be the same boot: restart, never end.
        assertEquals(30_000, LockoutPolicy.remainingMs(b, clock(9_000)))
    }

    /** Within one boot the wait continues across process death (the persisted anchor is read back as is). */
    @Test fun processDeathWithinOneBootContinuesTheWait() {
        val (a, _) = LockoutPolicy.onWrong(Attempts(failures = 8), clock(100_000))
        assertEquals(600_000, a.waitMs)
        val reread = a.copy() // a new process reads the same prefs
        assertEquals(a, LockoutPolicy.resolve(reread, clock(400_000)), "nothing to write while the wait runs")
        assertEquals(300_000, LockoutPolicy.remainingMs(reread, clock(400_000)))
        assertEquals(0, LockoutPolicy.remainingMs(reread, clock(700_000)))
    }

    /** The ladder step (the counter) survives a reboot and a served wait; the next wrong PIN climbs from it. */
    @Test fun ladderStepSurvivesRebootAndServedWait() {
        var a = Attempts()
        repeat(12) { i -> a = LockoutPolicy.onWrong(LockoutPolicy.resolve(a, clock(i * 2_000_000L)), clock(i * 2_000_000L)).first }
        assertEquals(12, a.failures)
        assertEquals(LockoutPolicy.WAIT_MAX_MS, a.waitMs)
        // Reboot: the top step again, in full.
        val rebooted = LockoutPolicy.resolve(a, Clock(1_000, 0, boot + 1))
        assertEquals(12, rebooted.failures)
        assertEquals(LockoutPolicy.WAIT_MAX_MS, LockoutPolicy.remainingMs(rebooted, Clock(1_000, 0, boot + 1)))
        // Served on the new boot, then a second reboot: nothing to wait for, the counter is kept.
        val served = LockoutPolicy.resolve(rebooted, Clock(1_000 + LockoutPolicy.WAIT_MAX_MS, 0, boot + 1))
        assertEquals(Attempts(12), served)
        assertEquals(0, LockoutPolicy.remainingMs(served, Clock(5, 0, boot + 2)))
        assertEquals(Attempts(12), LockoutPolicy.resolve(served, Clock(5, 0, boot + 2)))
        // Two more wrong PINs, then the 15th: 30 min again.
        var b = served
        repeat(2) { b = LockoutPolicy.onWrong(b, Clock(10, 0, boot + 2)).first }
        assertEquals(14, b.failures)
        assertEquals(LockoutPolicy.WAIT_MAX_MS, LockoutPolicy.onWrong(b, Clock(10, 0, boot + 2)).first.waitMs)
    }

    /**
     * Pre-F7 records (written with a `locked_wall` the store now ignores) and damaged ones: a running wait never ends
     * earlier than before; without a usable anchor it restarts.
     */
    @Test fun oldFormatAndDamagedRecordsNeverUnlockEarlier() {
        // Old record of this boot: continues on elapsed exactly as before.
        val old = Attempts(failures = 3, lockedAtElapsedMs = 10_000, lockedAtBoot = boot, waitMs = 30_000)
        assertEquals(20_000, LockoutPolicy.remainingMs(old, clock(20_000, wall = Long.MAX_VALUE / 2)))
        // Old record of a previous boot (the old code would have credited wall time): full wait.
        assertEquals(30_000, LockoutPolicy.remainingMs(old, Clock(5_000, Long.MAX_VALUE / 2, boot + 1)))
        // A wait with no elapsed anchor (only possible if damaged): restarts in full.
        val noAnchor = Attempts(failures = 3, waitMs = 30_000)
        assertEquals(30_000, LockoutPolicy.remainingMs(noAnchor, clock(5_000)))
        assertEquals(Attempts(3, 5_000, boot, 30_000), LockoutPolicy.resolve(noAnchor, clock(5_000)))
        // A stale anchor with no wait is tidied, never turned into a wait.
        assertEquals(Attempts(4), LockoutPolicy.resolve(Attempts(4, 10_000, boot, 0), clock(5_000)))
    }

    @Test fun noWaitRunning() {
        assertEquals(0, LockoutPolicy.remainingMs(Attempts(failures = 1), clock(0)))
        assertNull(LockoutPolicy.lockedUntilWallMs(Attempts(), clock(0)))
    }
}
