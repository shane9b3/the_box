package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * (CHL WP-2, docs/design/m6-chl-leagues.md §1.6 #11, #12, #14, #15, §2.5, §2.6, S7) The timing and back-off rows of the
 * machine for OHL, WHL and QMJHL: polls timed from completion, the 5 s fast back-off, the REFUSED back-off at 300 s,
 * the CHL pre-game cadence, and breaks at `breakMs()`. The NFL-only older-body guard never runs for a CHL game.
 */
class ScoreBugMachineChlTest {
    private class Rig(start: Long, wallOffsetMs: Long = 30 * 60_000L, liveMs: Long? = 10_000L) {
        var now = 0L
        val wall0 = start + wallOffsetMs
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))

        init {
            m.interval(liveMs)
        }

        fun follow(g: GameSnapshot) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(Demand.SEEN)
        }

        fun ok(s: GameSnapshot, received: Long = now) = m.fetched(Fetch.Ok(s, received, wall0 + received, 0))
        fun fail(kind: FailKind, retryAfterS: Int? = null) = m.fetched(Fetch.Failed(kind, retryAfterS, now))

        fun at(t: Long) {
            now = t
            m.step()
        }

        fun nextPoll(limit: Long = now + 3_600_000L): Long? {
            if (m.pollDue()) return now
            while (true) {
                val w = m.nextWakeAtMono() ?: return null
                if (w > limit) return null
                at(w)
                if (m.pollDue()) return w
            }
        }
    }

    private fun g(league: League, phase: GamePhase = GamePhase.LIVE, secs: Int = 622) =
        SportsFixtures.chlGame(
            league = league,
            phase = phase,
            clock = GameClock(secs, running = phase == GamePhase.LIVE, inIntermission = phase == GamePhase.INTERMISSION),
        )

    private val start = SportsFixtures.chlGame().startUtcMs

    @Test
    fun aLivePollIsTimedFromCompletionNotFromTheBucket() {
        for (league in CHL_LEAGUES) {
            val r = Rig(start)
            r.follow(g(league))
            r.at(137)
            r.ok(g(league))
            assertEquals(10_137L, r.nextPoll(), "$league: doneMono + the 10 s interval, not a bucket instant")
            r.ok(g(league, secs = 600))
            assertEquals(r.now + 10_000L, r.nextPoll(), "$league: from completion again")
        }
    }

    @Test
    fun aFailureBacksOffFromFiveSecondsNotTwenty() {
        for (league in CHL_LEAGUES) {
            val r = Rig(start)
            r.follow(g(league))
            r.ok(g(league))
            r.at(5_000L)
            r.fail(FailKind.TIMEOUT)
            val first = r.nextPoll()!! - 5_000L
            assertTrue(first in 4_000L..6_000L, "$league: 5 s +/- 20 %: $first")
            val failedAt = r.now
            r.fail(FailKind.IO)
            val second = r.nextPoll()!! - failedAt
            assertTrue(second in 8_000L..12_000L, "$league: 10 s +/- 20 %: $second")
        }
    }

    @Test
    fun aRateLimitKeepsTheTwentySecondBase() {
        for (league in CHL_LEAGUES) {
            val r = Rig(start)
            r.follow(g(league))
            r.ok(g(league))
            r.at(5_000L)
            r.fail(FailKind.HTTP_429)
            val gap = r.nextPoll()!! - 5_000L
            assertTrue(gap in 16_000L..24_000L, "$league: 429 on the 20 s base: $gap")
        }
    }

    @Test
    fun refusedIsRetriedOnlyAtThreeHundredSecondsWhateverRetryAfterSays() {
        for (league in CHL_LEAGUES) {
            for (retryAfter in listOf<Int?>(null, 5, 30, 900)) {
                val r = Rig(start)
                r.follow(g(league))
                r.ok(g(league))
                r.at(5_000L)
                r.fail(FailKind.REFUSED, retryAfter)
                assertEquals(5_000L + SportsConstants.BACKOFF_MAX_MS, r.nextPoll(), "$league retryAfter=$retryAfter")
                // A second refusal neither speeds it up nor slows it down.
                val again = r.now
                r.fail(FailKind.REFUSED, retryAfter)
                assertEquals(again + SportsConstants.BACKOFF_MAX_MS, r.nextPoll(), "$league: still 300 s on the second refusal")
            }
        }
    }

    @Test
    fun otherFailuresKeepTheirOwnBases() {
        val r = Rig(start)
        r.follow(g(League.OHL))
        r.ok(g(League.OHL))
        r.at(5_000L)
        r.fail(FailKind.HTTP_5XX)
        assertTrue(r.nextPoll()!! - 5_000L < 10_000L, "a 5xx keeps the 5 s base")
    }

    @Test
    fun preGameSleepsUntilTwoMinutesBeforeStartThenPollsEveryFortySeconds() {
        for (league in CHL_LEAGUES) {
            val r = Rig(start, wallOffsetMs = -10 * 60_000L)
            val pre = g(league, phase = GamePhase.SCHEDULED)
            r.follow(pre)
            r.ok(pre)
            val wake = 8 * 60_000L // start - 2 min, 10 min ahead of "now"
            assertEquals(wake, r.nextPoll(), "$league: asleep until start - PRE_WAKE_MS")
            r.at(wake + 100)
            r.ok(pre)
            assertEquals(wake + 100 + SportsConstants.PRE_POLL_MS, r.nextPoll(), "$league: 40 s from completion")
        }
    }

    @Test
    fun breaksPollAtTwiceTheIntervalNeverUnderTwentySeconds() {
        for (league in CHL_LEAGUES) {
            for ((liveMs, breakMs) in listOf(5_000L to 20_000L, 15_000L to 30_000L)) {
                val r = Rig(start, liveMs = liveMs)
                val brk = g(league, GamePhase.INTERMISSION, secs = 0)
                r.follow(brk)
                r.at(137)
                r.ok(brk)
                assertEquals(137 + breakMs, r.nextPoll(), "$league at ${liveMs / 1000} s")
            }
        }
    }

    @Test
    fun anOlderBodyIsNotHeldForAChlGame() {
        for (league in CHL_LEAGUES) {
            val r = Rig(start)
            val a = SportsFixtures.chlGame(league = league, clock = GameClock(600, true, false))
            val older = SportsFixtures.chlGame(league = league, clock = GameClock(620, true, false))
            r.follow(a)
            r.ok(a)
            r.at(10_000L)
            r.ok(older)
            assertEquals(620, assertNotNull(r.m.knownView()).clockSecs, "$league: the NFL's older-body guard is not run")
        }
    }
}
