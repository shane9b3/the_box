package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** docs/design/m6-score-bug.md §2.3 day list, §2.4 back-off (A-S7). */
class DayPollPolicyTest {
    private val r = 1_000_000L
    private val phase = 1_500L
    private val date = 1_793_923_200_000L

    private fun ok(vararg phases: GamePhase, ageS: Int = 5, withDate: Boolean = true) = Fetch.Ok(
        SportsFixtures.board(*phases.mapIndexed { i, p -> SportsFixtures.game(id = "20260209$i", phase = p) }.toTypedArray()),
        receivedMono = r,
        serverDateMs = if (withDate) date else null,
        ageS = ageS,
    )

    private fun next(ok: Fetch.Ok<DayBoard>?, demand: Demand = Demand.SEEN, failures: Int = 0, failAt: Long? = null, retry: Int? = null, jitter: Double = 0.0, now: Long = r) =
        DayPollPolicy.nextAt(ok, demand, failures, failAt, retry, phase, jitter, now)

    @Test
    fun liveOrPreGameIsThirtySecondsBucketAligned() {
        // Age 5: the bucket ends at r + 15 s; aligned instants r + 16.5 s, r + 36.5 s: the nearer to r + 30 s wins.
        assertEquals(r + 36_500, next(ok(GamePhase.LIVE, GamePhase.FINAL, ageS = 5)))
        // Age 15: bucket end r + 5 s → r + 6.5, r + 26.5 s.
        assertEquals(r + 26_500, next(ok(GamePhase.INTERMISSION, ageS = 15)))
        assertEquals(r + 26_500, next(ok(GamePhase.PRE, GamePhase.SCHEDULED, ageS = 15)))
        // No Date: plain 30 s.
        assertEquals(r + 30_000, next(ok(GamePhase.LIVE, withDate = false)))
    }

    @Test
    fun otherwiseFiveMinutes() {
        assertEquals(r + 300_000, next(ok(GamePhase.SCHEDULED, GamePhase.FINAL)))
    }

    @Test
    fun allFinalOrEmptyDayLoadsOnce() {
        assertNull(next(ok(GamePhase.FINAL, GamePhase.FINAL, GamePhase.POSTPONED)))
        assertNull(next(ok()))
        assertEquals(r, next(null)) // never loaded: now
    }

    @Test
    fun nothingWhenTheTabAndPickerAreClosed() {
        for (d in listOf(Demand.NONE, Demand.UNSEEN)) {
            assertNull(next(ok(GamePhase.LIVE), demand = d))
            assertNull(next(null, demand = d))
            assertNull(next(null, demand = d, failures = 3, failAt = r))
        }
    }

    @Test
    fun backoffAfterFailures() {
        val f = r + 5_000
        assertEquals(f + 20_000, next(ok(GamePhase.LIVE), failures = 1, failAt = f))
        assertEquals(f + 40_000, next(ok(GamePhase.LIVE), failures = 2, failAt = f))
        assertEquals(f + 160_000, next(ok(GamePhase.LIVE), failures = 4, failAt = f))
        assertEquals(f + 300_000, next(ok(GamePhase.LIVE), failures = 9, failAt = f))
        // ±20 % jitter
        assertEquals(f + 24_000, next(ok(GamePhase.LIVE), failures = 1, failAt = f, jitter = 1.0))
        assertEquals(f + 16_000, next(null, failures = 1, failAt = f, jitter = -1.0))
        assertEquals(f + 360_000, next(ok(GamePhase.LIVE), failures = 12, failAt = f, jitter = 1.0))
        // Retry-After is a floor on the back-off (C-S4): a longer one wins, a shorter one (or 0) never brings it closer.
        assertEquals(f + 300_000, next(ok(GamePhase.LIVE), failures = 1, failAt = f, retry = 300))
        assertEquals(f + 80_000, next(ok(GamePhase.LIVE), failures = 3, failAt = f, retry = 7))
        assertEquals(f + 20_000, next(ok(GamePhase.LIVE), failures = 1, failAt = f, retry = 0))
    }
}
