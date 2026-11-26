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
    // (NFL hub commit 0) The signatures take a cadence and the FINAL facts; these cases are the NHL's, unchanged.
    private val nhl = DetailCadence.NHL

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
    ) = DetailPollPolicy.nextAt(
        nhl, gate, lastOk, phase, null, FinalFacts(recap, false, endedAgoMs), failures, lastFail, retryAfterS, refused, 1_500L, jitter, now,
    )

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
        assertFalse(DetailPollPolicy.isStale(nhl, 0L, GamePhase.LIVE, FinalFacts(false, false, null), 60_000L))
        assertTrue(DetailPollPolicy.isStale(nhl, 0L, GamePhase.LIVE, FinalFacts(false, false, null), 60_001L))
        assertEquals(60_001L, DetailPollPolicy.staleAt(nhl, 0L, GamePhase.LIVE, FinalFacts(false, false, null)))
        assertFalse(DetailPollPolicy.isStale(nhl, 0L, GamePhase.PRE, FinalFacts(false, false, null), 2 * idle))
        assertTrue(DetailPollPolicy.isStale(nhl, 0L, GamePhase.PRE, FinalFacts(false, false, null), 2 * idle + 1))
        assertFalse(DetailPollPolicy.isStale(nhl, 0L, GamePhase.FINAL, FinalFacts(true, false, null), Long.MAX_VALUE / 2), "a done final is never stale")
        assertNull(DetailPollPolicy.staleAt(nhl, 0L, GamePhase.POSTPONED, FinalFacts(false, false, null)))
        assertTrue(DetailPollPolicy.isStale(nhl, 0L, GamePhase.FINAL, FinalFacts(false, false, 1_000L), 2 * idle + 1), "waiting for the recap")
    }

    @Test fun nhlIgnoresTheFetchedPhase() {
        // (NFL hub P1) The phase-change rule is the NFL's only: an NHL detail fetched at PRE on a LIVE board keeps the
        // aligned 30 s loop, and a FINAL board with a recap posted asks for nothing.
        val r = 1_000_000L
        val live = DetailPollPolicy.nextAt(nhl, open, ok(r), GamePhase.LIVE, GamePhase.PRE, FinalFacts(false, false, null), 0, null, null, false, 1_500L, 0.0, r + 1)
        assertEquals(r + 30_000L, live)
        assertNull(DetailPollPolicy.nextAt(nhl, open, ok(r), GamePhase.FINAL, GamePhase.LIVE, FinalFacts(true, true, 1_000L), 0, null, null, false, 1_500L, 0.0, r + 1))
        assertNull(DetailPollPolicy.intervalMs(nhl, GamePhase.FINAL, FinalFacts(recapPosted = true, playClips = true, endedAgoMs = 1_000L)), "no plays wait")
    }

    // ------------------------------------------------------------------ (NFL hub §5.2, P1) DetailCadence.NFL

    private val nfl = DetailCadence.NFL

    private fun nflNext(
        phase: GamePhase,
        fetched: GamePhase? = phase,
        lastOk: Fetch.Ok<*>? = ok(1_000_000L, dateMs = 1L, ageS = 5),
        highlight: Boolean = false,
        playClips: Boolean = false,
        endedAgoMs: Long? = null,
        failures: Int = 0,
        lastFail: Long? = null,
        retryAfterS: Int? = null,
        refused: Boolean = false,
        now: Long = 1_000_000L,
    ) = DetailPollPolicy.nextAt(
        nfl, open, lastOk, phase, fetched, FinalFacts(highlight, playClips, endedAgoMs), failures, lastFail, retryAfterS, refused, 1_500L, 0.0, now,
    )

    @Test fun nflCadenceValues() {
        assertEquals(SportsConstants.HUB_NFL_PRE_MS, 1_800_000L)
        assertEquals(SportsConstants.HUB_NFL_LIVE_MS, 60_000L)
        assertEquals(11_700_000L, nfl.gameLengthMs, "3 h 15 min")
        assertFalse(nfl.liveAligned)
        assertEquals(15_000L, nfl.phaseChangeFloorMs)
    }

    @Test fun nflFirstRequestIsAtOnce() {
        for (p in GamePhase.entries) assertEquals(5L, nflNext(p, fetched = null, lastOk = null, now = 5L), p.name)
    }

    @Test fun nflPreGameReReadsEveryThirtyMinutes() {
        assertEquals(1_000_000L + 1_800_000L, nflNext(GamePhase.SCHEDULED))
        assertEquals(1_000_000L + 1_800_000L, nflNext(GamePhase.PRE))
        // The board entering the pre-game window is no phase change for the detail: the 30 min answer is reused.
        assertEquals(1_000_000L + 1_800_000L, nflNext(GamePhase.PRE, fetched = GamePhase.SCHEDULED, now = 1_000_001L))
    }

    @Test fun nflLiveAndBothBreaksArePlainSixtySeconds() {
        // Not bucket-aligned, whatever Date and Age say (the NHL's would be +36.5 s here).
        assertEquals(1_060_000L, nflNext(GamePhase.LIVE))
        assertEquals(1_060_000L, nflNext(GamePhase.INTERMISSION))
        assertEquals(1_060_000L, nflNext(GamePhase.LIVE, lastOk = ok(1_000_000L)))
        // A break and the play around it are one group: no forced re-read either way.
        assertEquals(1_060_000L, nflNext(GamePhase.INTERMISSION, fetched = GamePhase.LIVE, now = 1_020_000L))
        assertEquals(1_060_000L, nflNext(GamePhase.LIVE, fetched = GamePhase.INTERMISSION, now = 1_020_000L))
        assertEquals(60_000L, DetailPollPolicy.intervalMs(nfl, GamePhase.INTERMISSION, FinalFacts(false, false, null)))
    }

    @Test fun nflPhaseChangeIsDueAtOnceWithTheFloor() {
        val r = 1_000_000L
        // PRE → LIVE: due now, but no earlier than 15 s after the last answer.
        assertEquals(r + 15_000L, nflNext(GamePhase.LIVE, fetched = GamePhase.SCHEDULED, now = r + 2_000L))
        assertEquals(r + 600_000L, nflNext(GamePhase.LIVE, fetched = GamePhase.PRE, now = r + 600_000L))
        // LIVE → FINAL: the final totals and the recap must not wait for the 5 min loop, nor for the highlight rule.
        assertEquals(r + 20_000L, nflNext(GamePhase.FINAL, fetched = GamePhase.LIVE, highlight = true, endedAgoMs = 1_000L, now = r + 20_000L))
        assertEquals(r + 15_000L, nflNext(GamePhase.FINAL, fetched = GamePhase.INTERMISSION, now = r + 1L), "even with no final interval")
        // → POSTPONED: one more read.
        assertEquals(r + 15_000L, nflNext(GamePhase.POSTPONED, fetched = GamePhase.PRE, now = r))
        // A detail already further along than the board is not a change (effectivePhase takes the further one).
        assertNull(nflNext(DetailPollPolicy.effectivePhase(GamePhase.LIVE, GamePhase.FINAL), fetched = GamePhase.FINAL, highlight = true, endedAgoMs = 1_000L))
    }

    @Test fun nflBackOffWinsOverThePhaseChange() {
        assertEquals(500L + 20_000L, nflNext(GamePhase.FINAL, fetched = GamePhase.LIVE, failures = 1, lastFail = 500L))
        assertEquals(500L + SportsConstants.BACKOFF_MAX_MS, nflNext(GamePhase.LIVE, fetched = GamePhase.PRE, failures = 1, lastFail = 500L, refused = true))
        assertEquals(500L + 90_000L, nflNext(GamePhase.LIVE, failures = 1, lastFail = 500L, retryAfterS = 90))
    }

    @Test fun nflFinalWaitsForTheHighlightUpToTwoHours() {
        val r = 1_000_000L
        assertEquals(r + idle, nflNext(GamePhase.FINAL, endedAgoMs = 60_000L), "no highlight yet: every 5 min")
        assertEquals(r + idle, nflNext(GamePhase.FINAL, endedAgoMs = SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS - 1))
        assertNull(nflNext(GamePhase.FINAL, endedAgoMs = SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS), "2 h after the end: done")
        assertNull(nflNext(GamePhase.FINAL, highlight = true, endedAgoMs = 60_000L), "highlight offered, no single plays: done")
        assertNull(nflNext(GamePhase.FINAL, endedAgoMs = null), "unknown end: done")
        assertNull(nflNext(GamePhase.POSTPONED))
    }

    @Test fun nflFinalKeepsReadingThirtyMinutesForLateSinglePlays() {
        // (arch m6) An ESPN-aired game: the highlight is up, but a one-play clip may still come (MNF: 7 min later).
        val r = 1_000_000L
        assertEquals(r + idle, nflNext(GamePhase.FINAL, highlight = true, playClips = true, endedAgoMs = 20 * 60_000L))
        assertEquals(r + idle, nflNext(GamePhase.FINAL, highlight = true, playClips = true, endedAgoMs = SportsConstants.HUB_NFL_PLAYS_WAIT_MS - 1))
        assertNull(nflNext(GamePhase.FINAL, highlight = true, playClips = true, endedAgoMs = SportsConstants.HUB_NFL_PLAYS_WAIT_MS), "30 min: done")
        // Without the highlight the 2 h wait still governs past 30 min.
        assertEquals(r + idle, nflNext(GamePhase.FINAL, highlight = false, playClips = true, endedAgoMs = 90 * 60_000L))
    }

    @Test fun nflStalenessIsTwiceThePhaseInterval() {
        val none = FinalFacts(false, false, null)
        assertFalse(DetailPollPolicy.isStale(nfl, 0L, GamePhase.LIVE, none, 120_000L))
        assertTrue(DetailPollPolicy.isStale(nfl, 0L, GamePhase.LIVE, none, 120_001L), "\"Stats from …\" after 2 min")
        assertEquals(3_600_001L, DetailPollPolicy.staleAt(nfl, 0L, GamePhase.PRE, none))
        assertNull(DetailPollPolicy.staleAt(nfl, 0L, GamePhase.FINAL, FinalFacts(true, false, 1_000L)))
        assertEquals(2 * idle + 1, DetailPollPolicy.staleAt(nfl, 0L, GamePhase.FINAL, FinalFacts(true, true, 1_000L)))
    }
}
