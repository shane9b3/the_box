package app.thebox.core.sports

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test

/** (NFL YDS §1.6; B1, S4, S10, N7) The planner: one fetch and one confirmed refresh per stoppage, retries and blocks. */
class NflYardsPlannerTest {
    private val c = SportsConstants

    private fun timeout(playId: String = "500", type: String = c.NFL_TYPE_TIMEOUT): GameSnapshot =
        SportsFixtures.nflGame(detail = SportsFixtures.nflDetail(lastPlay = NflLastPlay(playId, type, "Timeout", Side.HOME, 0)))

    private val play = SportsFixtures.nflGame(detail = SportsFixtures.nflDetail(lastPlay = NflLastPlay("501", "5", "Rush", Side.AWAY, 0)))
    private val final = SportsFixtures.nflGame(phase = GamePhase.FINAL, finalKind = PeriodKind.REG)

    private fun yards(s: GameSnapshot): Pair<Int?, Int?> = (s.detail as LeagueDetail.Nfl).let { it.awayYards to it.homeYards }

    private val none = null to null

    @Test fun theFirstPollIsDueAndCarriesTheValuesAtOnce() {
        val p = NflYardsPlanner()
        val s = timeout()
        assertEquals("T500", p.due(s, 1_000))
        p.fetched("T500", 1_800, YardsResult.Ok(142, 51))
        assertEquals(142 to 51, yards(p.attach(s, 1_000)))
        // The same stoppage on the next poll: not due, still carried.
        assertNull(p.due(s, 11_000))
        assertEquals(142 to 51, yards(p.attach(s, 11_000)))
    }

    @Test fun nothingIsDueOrAttachedOutsideAStoppage() {
        val p = NflYardsPlanner()
        assertNull(p.due(play, 0))
        assertSame(play, p.attach(play, 0))
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(1, 2))
        // The next play keeps no yards (no hold across snapshots).
        assertNull(p.due(play, 10_000))
        assertSame(play, p.attach(play, 10_000))
    }

    @Test fun theRefreshIsDueAtThirtySecondsAndNotBefore() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 1_000, YardsResult.Ok(10, 20))
        assertNull(p.due(s, 30_999))
        assertEquals("T500", p.due(s, 31_000))
    }

    @Test fun theRefreshIsUsedOnlyFromAPollTwentySecondsAfterItInTheSameStoppage() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 1_000, YardsResult.Ok(10, 20))
        assertEquals("T500", p.due(s, 31_000))
        p.fetched("T500", 32_000, YardsResult.Ok(15, 20))
        assertEquals(10 to 20, yards(p.attach(s, 31_000))) // the refresh's own poll
        assertEquals(10 to 20, yards(p.attach(s, 32_000 + c.NFL_YDS_CONFIRM_MS - 1)))
        assertEquals(15 to 20, yards(p.attach(s, 32_000 + c.NFL_YDS_CONFIRM_MS)))
        assertEquals(15 to 20, yards(p.attach(s, 90_000)))
    }

    @Test fun aKeyChangeDropsThePendingRefresh() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(10, 20))
        p.due(s, 30_000)
        p.fetched("T500", 30_000, YardsResult.Ok(99, 20))
        // The next play (no key), then a new stoppage: neither ever shows 99.
        assertSame(play, p.attach(play, 50_000))
        val next = timeout("600")
        assertEquals("T600", p.due(next, 60_000))
        assertEquals(none, yards(p.attach(next, 60_000))) // the new key has nothing yet
        p.fetched("T500", 60_500, YardsResult.Ok(7, 7)) // a late answer for the old key is ignored
        assertEquals(none, yards(p.attach(next, 61_000)))
    }

    @Test fun aRetryableFailureIsDueAgainAfterFifteenSeconds() {
        for (kind in listOf(FailKind.HTTP_OTHER, FailKind.HTTP_5XX, FailKind.BAD_BODY)) {
            val p = NflYardsPlanner()
            val s = timeout()
            assertEquals("T500", p.due(s, 0))
            p.fetched("T500", 500, YardsResult.Failed(kind))
            assertEquals(none, yards(p.attach(s, 0)))
            assertNull(p.due(s, c.NFL_YDS_RETRY_MS - 1), kind.name)
            assertEquals("T500", p.due(s, c.NFL_YDS_RETRY_MS), kind.name)
        }
    }

    @Test fun aTimeoutOrIoFailureIsNeverDueAgainInThatStoppage() {
        for (kind in listOf(FailKind.TIMEOUT, FailKind.IO)) {
            val p = NflYardsPlanner()
            val s = timeout()
            p.due(s, 0)
            p.fetched("T500", 2_500, YardsResult.Failed(kind))
            for (t in listOf(15_000L, 30_000L, 120_000L)) assertNull(p.due(s, t), kind.name)
            // The next stoppage fetches again (one stall does not block).
            assertEquals("T600", p.due(timeout("600"), 130_000))
        }
    }

    @Test fun twoStallsInARowBlockForTenMinutes() {
        val p = NflYardsPlanner()
        p.due(timeout("1"), 0)
        p.fetched("T1", 2_500, YardsResult.Failed(FailKind.TIMEOUT))
        p.due(timeout("2"), 60_000)
        p.fetched("T2", 62_500, YardsResult.Failed(FailKind.IO))
        assertNull(p.due(timeout("3"), 62_500 + c.NFL_YDS_BLOCK_MS - 1))
        assertEquals("T4", p.due(timeout("4"), 62_500 + c.NFL_YDS_BLOCK_MS))
    }

    @Test fun aSuccessBetweenStallsResetsTheCount() {
        val p = NflYardsPlanner()
        p.due(timeout("1"), 0)
        p.fetched("T1", 0, YardsResult.Failed(FailKind.TIMEOUT))
        p.due(timeout("2"), 60_000)
        p.fetched("T2", 60_000, YardsResult.Ok(1, 1))
        p.due(timeout("3"), 120_000)
        p.fetched("T3", 120_000, YardsResult.Failed(FailKind.TIMEOUT))
        assertEquals("T4", p.due(timeout("4"), 180_000))
        // A retryable failure resets it too.
        p.fetched("T4", 180_000, YardsResult.Failed(FailKind.HTTP_5XX))
        p.due(timeout("5"), 240_000)
        p.fetched("T5", 240_000, YardsResult.Failed(FailKind.IO))
        assertEquals("T6", p.due(timeout("6"), 300_000))
    }

    @Test fun a429BlocksForTenMinutes() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 1_000, YardsResult.Failed(FailKind.HTTP_429))
        assertNull(p.due(s, 16_000))
        assertNull(p.due(timeout("600"), 1_000 + c.NFL_YDS_BLOCK_MS - 1))
        assertEquals("T700", p.due(timeout("700"), 1_000 + c.NFL_YDS_BLOCK_MS))
    }

    @Test fun atMostThreeAttemptsAndTwoSuccesses() {
        val p = NflYardsPlanner()
        val s = timeout()
        assertEquals("T500", p.due(s, 0))
        p.fetched("T500", 0, YardsResult.Failed(FailKind.HTTP_5XX))
        assertEquals("T500", p.due(s, 15_000))
        p.fetched("T500", 15_000, YardsResult.Ok(1, 2))
        assertEquals("T500", p.due(s, 45_000))
        p.fetched("T500", 45_000, YardsResult.Failed(FailKind.HTTP_5XX))
        assertNull(p.due(s, 60_000)) // three attempts
        assertNull(p.due(s, 600_000))

        val q = NflYardsPlanner()
        q.due(s, 0)
        q.fetched("T500", 0, YardsResult.Ok(1, 2))
        q.due(s, 30_000)
        q.fetched("T500", 30_000, YardsResult.Ok(3, 4))
        assertNull(q.due(s, 60_000)) // two successes
        assertNull(q.due(s, 600_000))
        assertEquals(3 to 4, yards(q.attach(s, 60_000)))
    }

    @Test fun finalGetsExactlyOneFetch() {
        val p = NflYardsPlanner()
        assertEquals("F", p.due(final, 0))
        p.fetched("F", 0, YardsResult.Ok(389, 312))
        assertEquals(389 to 312, yards(p.attach(final, 0)))
        assertNull(p.due(final, 30_000))
        assertNull(p.due(final, 600_000))
    }

    @Test fun anOfficialTimeoutAndABreakAreStoppagesToo() {
        val p = NflYardsPlanner()
        val o = timeout("700", c.NFL_TYPE_OFFICIAL_TIMEOUT)
        assertEquals("O700", p.due(o, 0))
        val brk = SportsFixtures.nflGame(
            phase = GamePhase.INTERMISSION, period = 2,
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.HALFTIME),
        )
        assertEquals("H2", p.due(brk, 10_000))
    }

    @Test fun aStaleGapKeepsTheKeyAndTheValuesAttachAgainOnRecovery() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(5, 6))
        // No Ok for a while (no step), then the same stoppage again.
        assertNull(p.due(s, 25_000))
        assertEquals(5 to 6, yards(p.attach(s, 25_000)))
    }

    @Test fun theAttachedSnapshotIsOtherwiseUnchanged() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(-2, 999))
        val a = p.attach(s, 0)
        assertEquals(s.copy(detail = (s.detail as LeagueDetail.Nfl).copy(awayYards = -2, homeYards = 999)), a)
    }
}
