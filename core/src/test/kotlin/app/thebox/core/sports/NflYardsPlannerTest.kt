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

    private fun brk(kind: NflBreak?, period: Int = 1): GameSnapshot = SportsFixtures.nflGame(
        phase = GamePhase.INTERMISSION, period = period,
        detail = SportsFixtures.nflDetail(breakKind = kind),
    )

    /** The keys that keep the 30 s refresh and the 20 s confirm: official timeout, end of a quarter, halftime. */
    private val slowKeys: List<Pair<String, GameSnapshot>> by lazy {
        listOf(
            "O700" to timeout("700", c.NFL_TYPE_OFFICIAL_TIMEOUT),
            "O701" to timeout("701", c.NFL_TYPE_TWO_MINUTE_WARNING),
            "E1" to brk(NflBreak.END_PERIOD),
            "H2" to brk(NflBreak.HALFTIME, period = 2),
        )
    }

    @Test fun theRefreshIsDueAtThirtySecondsAndNotBeforeOutsideATeamTimeout() {
        for ((k, s) in slowKeys) {
            val p = NflYardsPlanner()
            assertEquals(k, p.due(s, 0))
            p.fetched(k, 1_000, YardsResult.Ok(10, 20)) // completed 1 s after its poll: the delay counts from the poll (S1)
            assertNull(p.due(s, c.NFL_YDS_TIMEOUT_REFRESH_MS), k) // the team-timeout figure does not apply
            assertNull(p.due(s, c.NFL_YDS_REFRESH_MS - 1), k)
            assertEquals(k, p.due(s, c.NFL_YDS_REFRESH_MS), k)
        }
    }

    @Test fun theRefreshIsUsedOnlyFromAPollTwentySecondsAfterItInTheSameStoppageOutsideATeamTimeout() {
        for ((k, s) in slowKeys) {
            val p = NflYardsPlanner()
            p.due(s, 0)
            p.fetched(k, 1_000, YardsResult.Ok(10, 20))
            assertEquals(k, p.due(s, 30_000))
            p.fetched(k, 32_000, YardsResult.Ok(15, 20)) // read on the 30 000 poll, completed at 32 000
            assertEquals(10 to 20, yards(p.attach(s, 30_000)), k) // the refresh's own poll
            assertEquals(10 to 20, yards(p.attach(s, 30_000 + c.NFL_YDS_TIMEOUT_CONFIRM_MS)), k)
            assertEquals(10 to 20, yards(p.attach(s, 30_000 + c.NFL_YDS_CONFIRM_MS - 1)), k)
            assertEquals(15 to 20, yards(p.attach(s, 30_000 + c.NFL_YDS_CONFIRM_MS)), k)
            assertEquals(15 to 20, yards(p.attach(s, 90_000)), k)
        }
    }

    /**
     * (Integration ruling, live2 2026-11-16 21:25–21:45) At a team timeout the previous play's yards reached the feed
     * 10–20 s after the type 21, and the timeout lasted 40–60 s: the refresh is due 12 s after the poll of the first good
     * fetch and used from the first poll at least 5 s after the refresh's poll that is still in the timeout.
     */
    @Test fun aTeamTimeoutsRefreshIsDueAtTwelveSecondsAndUsedFiveSecondsAfterIt() {
        assertEquals(12_000L, c.NFL_YDS_TIMEOUT_REFRESH_MS)
        assertEquals(5_000L, c.NFL_YDS_TIMEOUT_CONFIRM_MS)
        val p = NflYardsPlanner()
        val s = timeout()
        assertEquals("T500", p.due(s, 0))
        p.fetched("T500", 1_000, YardsResult.Ok(110, 20))
        assertNull(p.due(s, 11_999))
        assertEquals("T500", p.due(s, 12_000))
        p.fetched("T500", 13_000, YardsResult.Ok(118, 20))
        assertEquals(110 to 20, yards(p.attach(s, 12_000))) // the refresh's own poll
        assertEquals(110 to 20, yards(p.attach(s, 12_000 + c.NFL_YDS_TIMEOUT_CONFIRM_MS - 1))) // +4.999 s
        assertEquals(118 to 20, yards(p.attach(s, 12_000 + c.NFL_YDS_TIMEOUT_CONFIRM_MS))) // +5 s
        assertEquals(118 to 20, yards(p.attach(s, 60_000)))
        assertNull(p.due(s, 60_000)) // still one refresh only
    }

    /**
     * (Code review S1) A poll grid: a team timeout seen on the board from T0 for [lengthMs], polled every [gridMs], whose
     * statistics change [lateMs] after T0 (the previous play's yards arriving late, as live2 21:39:14 → 21:39:34). Returns
     * the yards each poll in the timeout shows.
     */
    private fun grid(gridMs: Long, lengthMs: Long, lateMs: Long): List<Pair<Long, Pair<Int?, Int?>>> {
        val p = NflYardsPlanner()
        val s = timeout()
        val shown = mutableListOf<Pair<Long, Pair<Int?, Int?>>>()
        var t = 0L
        while (t < lengthMs) {
            val key = p.due(s, t)
            // The step's side time (≤ 2.5 s) passes between the poll and the answer.
            if (key != null) p.fetched(key, t + 2_400, YardsResult.Ok(175, if (t < lateMs) 110 else 118))
            shown += t to yards(p.attach(s, t))
            t += gridMs
        }
        return shown
    }

    @Test fun onATenSecondGridAFiftySecondTimeoutShowsTheLateYardsBeforeItEnds() {
        val shown = grid(gridMs = 10_000, lengthMs = 50_000, lateMs = 15_000)
        assertEquals(175 to 110, shown.first().second)
        // Polls 0, 10, 20 (the refresh), 30 (used: 5 s after 20), 40.
        assertEquals(listOf(0L, 10_000L, 20_000L), shown.filter { it.second == 175 to 110 }.map { it.first })
        assertEquals(listOf(30_000L, 40_000L), shown.filter { it.second == 175 to 118 }.map { it.first })
    }

    @Test fun onAFiveSecondGridAFiftySecondTimeoutShowsTheLateYardsBeforeItEnds() {
        val shown = grid(gridMs = 5_000, lengthMs = 50_000, lateMs = 15_000)
        // Polls 0…10 show the first read; 15 refreshes (≥ 12 s, past the 15 s change); 20 (5 s later) uses it.
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), shown.filter { it.second == 175 to 110 }.map { it.first })
        assertEquals(20_000L, shown.first { it.second == 175 to 118 }.first)
        assertEquals(175 to 118, shown.last().second)
    }

    @Test fun aTeamTimeoutsRefreshIsDroppedWhenThePlayComesFirst() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(110, 20))
        p.due(s, 12_000)
        p.fetched("T500", 12_500, YardsResult.Ok(125, 20)) // already the next play's
        assertSame(play, p.attach(play, 17_500)) // the board shows the play first: never 125
        assertEquals(none, yards(p.attach(timeout("600"), 40_000)))
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
        assertEquals("H2", p.due(brk(NflBreak.HALFTIME, period = 2), 10_000))
    }

    @Test fun aStaleGapKeepsTheKeyAndTheValuesAttachAgainOnRecovery() {
        val p = NflYardsPlanner()
        val s = timeout()
        p.due(s, 0)
        p.fetched("T500", 0, YardsResult.Ok(5, 6))
        // No Ok for a while (no step), then the same stoppage again.
        // 25 s on: past a team timeout's 12 s, so the recovery poll is also its refresh; the first values still attach.
        assertEquals("T500", p.due(s, 25_000))
        assertEquals(5 to 6, yards(p.attach(s, 25_000)))
        // An official timeout's (30 s refresh) is not due at 25 s.
        val q = NflYardsPlanner()
        val o = timeout("700", c.NFL_TYPE_OFFICIAL_TIMEOUT)
        q.due(o, 0)
        q.fetched("O700", 0, YardsResult.Ok(5, 6))
        assertNull(q.due(o, 25_000))
        assertEquals(5 to 6, yards(q.attach(o, 25_000)))
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
