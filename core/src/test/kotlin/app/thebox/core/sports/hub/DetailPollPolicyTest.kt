package app.thebox.core.sports.hub

import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.DetailPollPolicy.DetailGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §4.3, §10.2: every phase, alignment, recap wait, back-off, the gate, staleness. */
class DetailPollPolicyTest {
    private val open = DetailGate(sportsOn = true, awake = true, foreground = true, online = true, paused = false)
    private val idle = SportsConstants.HUB_DETAIL_IDLE_MS

    private fun ok(receivedMono: Long, dateMs: Long? = null, ageS: Int = 0) = Fetch.Ok(Unit, receivedMono, dateMs, ageS)

    private fun next(
        phase: GamePhase,
        lastOk: Fetch.Ok<*>? = ok(1_000_000L),
        gate: DetailGate = open,
        recap: Boolean = false,
        endedAgoMs: Long? = null,
        failures: Int = 0,
        lastFail: Long? = null,
        retryAfterS: Int? = null,
        refused: Boolean = false,
        jitter: Double = 0.0,
        now: Long = 1_000_000L,
    ) = DetailPollPolicy.nextAt(gate, lastOk, phase, recap, endedAgoMs, failures, lastFail, retryAfterS, refused, 1_500L, jitter, now)

    @Test fun firstRequestIsAtOnceInEveryPhase() {
        for (p in GamePhase.entries) assertEquals(5L, next(p, lastOk = null, now = 5L), p.name)
    }

    @Test fun scheduledAndPreReReadEveryFiveMinutes() {
        assertEquals(1_000_000L + idle, next(GamePhase.SCHEDULED))
        assertEquals(1_000_000L + idle, next(GamePhase.PRE))
    }

    @Test fun liveIsThirtySecondsBucketAligned() {
        // Date − Age: the bucket ends 20 s − 5 s = 15 s after the answer; + phase 1.5 s; the aligned instant nearest
        // received + 30 s is +36.5 s (16.5, 36.5, 56.5 …).
        val r = 1_000_000L
        assertEquals(r + 36_500L, next(GamePhase.LIVE, lastOk = ok(r, dateMs = 1L, ageS = 5)))
        assertEquals(r + 36_500L, next(GamePhase.INTERMISSION, lastOk = ok(r, dateMs = 1L, ageS = 5)))
        // Without Date: received + 30 s.
        assertEquals(r + 30_000L, next(GamePhase.LIVE))
    }

    @Test fun finalOnceThenRecapWait() {
        assertNull(next(GamePhase.FINAL, recap = true, endedAgoMs = 60_000L), "recap posted: done")
        assertEquals(1_000_000L + idle, next(GamePhase.FINAL, recap = false, endedAgoMs = 60_000L))
        assertNull(next(GamePhase.FINAL, recap = false, endedAgoMs = SportsConstants.HUB_RECAP_WAIT_MS), "3 h after the end: done")
        assertNull(next(GamePhase.FINAL, recap = false, endedAgoMs = null), "unknown end: done")
        assertNull(next(GamePhase.POSTPONED))
    }

    @Test fun failuresBackOffWithRetryAfterAsAFloor() {
        assertEquals(500L + 20_000L, next(GamePhase.LIVE, failures = 1, lastFail = 500L))
        assertEquals(500L + 40_000L, next(GamePhase.LIVE, failures = 2, lastFail = 500L))
        assertEquals(500L + 300_000L, next(GamePhase.LIVE, failures = 9, lastFail = 500L))
        assertEquals(500L + 24_000L, next(GamePhase.LIVE, failures = 1, lastFail = 500L, jitter = 1.0))
        assertEquals(500L + 90_000L, next(GamePhase.LIVE, failures = 1, lastFail = 500L, retryAfterS = 90))
        assertEquals(500L + 20_000L, next(GamePhase.LIVE, failures = 1, lastFail = 500L, retryAfterS = 0))
        // A final game that failed is retried on the back-off too (no answer yet).
        assertEquals(500L + 20_000L, next(GamePhase.FINAL, lastOk = null, failures = 1, lastFail = 500L))
    }

    @Test fun refusedWaitsAtLeastTheBackOffCap() {
        assertEquals(500L + SportsConstants.BACKOFF_MAX_MS, next(GamePhase.LIVE, failures = 1, lastFail = 500L, refused = true))
        assertEquals(500L + 400_000L, next(GamePhase.LIVE, failures = 1, lastFail = 500L, retryAfterS = 400, refused = true))
    }

    @Test fun everyNoRequestCondition() {
        val closed = listOf(
            open.copy(sportsOn = false), open.copy(awake = false), open.copy(foreground = false), open.copy(online = false),
            open.copy(paused = true),
        )
        for (g in closed) {
            assertFalse(g.open)
            for (p in GamePhase.entries) {
                assertNull(next(p, lastOk = null, gate = g), "$g $p")
                assertNull(next(p, gate = g, failures = 1, lastFail = 1L), "$g $p")
            }
        }
        assertTrue(open.open)
    }

    @Test fun effectivePhaseTakesTheFurtherAlong() {
        assertEquals(GamePhase.LIVE, DetailPollPolicy.effectivePhase(GamePhase.LIVE, GamePhase.PRE))
        assertEquals(GamePhase.FINAL, DetailPollPolicy.effectivePhase(GamePhase.LIVE, GamePhase.FINAL))
        assertEquals(GamePhase.INTERMISSION, DetailPollPolicy.effectivePhase(GamePhase.INTERMISSION, GamePhase.LIVE))
        assertEquals(GamePhase.PRE, DetailPollPolicy.effectivePhase(GamePhase.PRE, null))
        assertEquals(GamePhase.POSTPONED, DetailPollPolicy.effectivePhase(GamePhase.SCHEDULED, GamePhase.POSTPONED))
        assertEquals(GamePhase.POSTPONED, DetailPollPolicy.effectivePhase(GamePhase.POSTPONED, GamePhase.FINAL))
    }

    @Test fun staleAtTwiceThePhaseInterval() {
        assertFalse(DetailPollPolicy.isStale(0L, GamePhase.LIVE, false, null, 60_000L))
        assertTrue(DetailPollPolicy.isStale(0L, GamePhase.LIVE, false, null, 60_001L))
        assertEquals(60_001L, DetailPollPolicy.staleAt(0L, GamePhase.LIVE, false, null))
        assertFalse(DetailPollPolicy.isStale(0L, GamePhase.PRE, false, null, 2 * idle))
        assertTrue(DetailPollPolicy.isStale(0L, GamePhase.PRE, false, null, 2 * idle + 1))
        assertFalse(DetailPollPolicy.isStale(0L, GamePhase.FINAL, true, null, Long.MAX_VALUE / 2), "a done final is never stale")
        assertNull(DetailPollPolicy.staleAt(0L, GamePhase.POSTPONED, false, null))
        assertTrue(DetailPollPolicy.isStale(0L, GamePhase.FINAL, false, 1_000L, 2 * idle + 1), "waiting for the recap")
    }
}
