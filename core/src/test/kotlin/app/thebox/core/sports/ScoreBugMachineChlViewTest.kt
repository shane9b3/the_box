package app.thebox.core.sports

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * (CHL commit 0, docs/design/m6-chl-leagues.md §1.6 #13a / #13b, §4.1; review B1, B3) The CHL rows of the machine that
 * land in commit 0: hockey views carry their league, and the unknown-power-play hold. Snapshots arrive 5 s apart (the
 * CHL floor) with `Age` 0, so each anchor is its arrival; the poll timing is WP-2's and is not asserted here.
 */
class ScoreBugMachineChlViewTest {
    private class Rig(delaySec: Int = 0) {
        var now = 0L
        val start = 1_794_612_600_000L // SportsFixtures.chlGame()'s start
        val wall0 = start + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))

        init {
            m.prefs(delaySec)
        }

        fun follow(g: GameSnapshot) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(Demand.SEEN)
        }

        fun ok(s: GameSnapshot, ageS: Int = 0) = m.fetched(Fetch.Ok(s, now, wall0 + now, ageS))

        fun at(t: Long) {
            now = t
            m.step()
        }

        /** Feeds [s] at [t] and returns the view. */
        fun feed(t: Long, s: GameSnapshot, ageS: Int = 0): ScoreBugView? {
            at(t)
            ok(s, ageS)
            return m.view()
        }
    }

    private val ppHome = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 79)

    private fun g(
        pp: PowerPlay? = null,
        unknown: Boolean = false,
        secs: Int = 622,
        away: Int = 1,
        home: Int = 2,
        league: League = League.OHL,
        lastGoal: GoalFact? = null,
    ) = SportsFixtures.chlGame(
        league = league,
        away = SportsFixtures.chlTeam("LDN", "London Knights", away),
        home = SportsFixtures.chlTeam("KIT", "Kitchener Rangers", home, teamId = 10),
        clock = GameClock(secs, running = true, inIntermission = false),
        powerPlay = if (unknown) null else pp,
        powerPlayUnknown = unknown,
        lastGoal = lastGoal,
    )

    // ------------------------------------------------------------------ B1: the league on hockey views

    @Test
    fun aChlSnapshotsViewAndKnownViewCarryItsLeague() {
        for (league in CHL_LEAGUES) {
            val r = Rig()
            val s = g(league = league)
            r.follow(s)
            val v = assertNotNull(r.feed(0, s))
            assertEquals(league, v.league, "view() of $league")
            assertEquals(SportsFixtures.liveView(s), v, "otherwise the NHL's view")
            // Hidden for want of updates (stale + 5 min): view() is null, knownView() keeps the last score.
            r.at(SportsConstants.STALE_AFTER_MS + SportsConstants.HIDE_AFTER_MS + 1_000)
            assertNull(r.m.view())
            val known = assertNotNull(r.m.knownView())
            assertEquals(league, known.league, "knownView() of $league")
            assertEquals(CentreText.NoUpdate, known.bottom)
        }
    }

    @Test
    fun anNhlSnapshotsViewsStillSayNhl() {
        val r = Rig()
        val s = SportsFixtures.game(startUtcMs = r.start)
        r.follow(s)
        r.at(0)
        r.ok(s)
        assertEquals(League.NHL, r.m.view()!!.league)
        r.at(SportsConstants.STALE_AFTER_MS + SportsConstants.HIDE_AFTER_MS + 1_000)
        assertEquals(League.NHL, r.m.knownView()!!.league)
    }

    // ------------------------------------------------------------------ B3: the unknown-power-play hold

    @Test
    fun ppThenUnknownThenPpSaysNothingAndTheTabNeverCloses() {
        val r = Rig()
        r.follow(g(ppHome))
        val views = listOf(
            r.feed(0, g(ppHome)),
            r.feed(5_000, g(unknown = true, secs = 617)),
            r.feed(10_000, g(ppHome.copy(secondsRemaining = 69), secs = 612)),
        ).map { assertNotNull(it) }
        assertEquals(ppHome, views[0].powerPlay)
        assertEquals(ppHome, views[1].powerPlay, "held: the last label and its frozen seconds")
        assertEquals(ppHome.copy(secondsRemaining = 69), views[2].powerPlay)
        assertTrue(views.none { it.ppEnded }, "nothing spoken")
    }

    @Test
    fun sevenUnknownPollsCloseTheTabSilentlyAtTheThirtySecondAnchor() {
        val r = Rig()
        r.follow(g(ppHome))
        assertEquals(ppHome, r.feed(0, g(ppHome))!!.powerPlay)
        // Unknown × 7 at 5 s: anchors 5 … 35 s; the run starts at 5 s, so the 7th (35 s) is 30 s into it.
        val held = (1..7).map { i -> assertNotNull(r.feed(i * 5_000L, g(unknown = true, secs = 622 - 5 * i))) }
        for (i in 0 until 6) assertEquals(ppHome, held[i].powerPlay, "unknown #${i + 1}: held")
        assertNull(held[6].powerPlay, "closed at the 30 s anchor")
        assertTrue(held.none { it.ppEnded }, "a hide is never announced as over")
        // A known "none" after the silent close says nothing either; a known power play shows again.
        assertFalse(r.feed(40_000, g(secs = 582))!!.ppEnded)
        assertEquals(ppHome, r.feed(45_000, g(ppHome, secs = 577))!!.powerPlay)
    }

    @Test
    fun ppThenUnknownThenKnownNoneAnnouncesTheEndOnce() {
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        val held = assertNotNull(r.feed(5_000, g(unknown = true)))
        assertFalse(held.ppEnded)
        val over = assertNotNull(r.feed(10_000, g()))
        assertNull(over.powerPlay)
        assertTrue(over.ppEnded, "\"Power play over.\": true, the source now says none")
        assertFalse(r.feed(15_000, g())!!.ppEnded, "once")
    }

    @Test
    fun theHoldRunsOnTheDelayedTimeline() {
        // (Fix pass L2) Age 3 s: each anchor is 3 s before its arrival, so a hold timed on arrivals would end 3 s later
        // than one timed on anchors. Delay 10 s: anchor a shows at a + 10.
        val r = Rig(delaySec = 10)
        r.follow(g(ppHome))
        r.feed(0, g(ppHome), ageS = 3) // anchor −3
        for (i in 1..7) r.feed(i * 5_000L, g(unknown = true), ageS = 3) // anchors 2 … 32; the run starts at 2
        r.at(41_999)
        assertEquals(ppHome, r.m.view()!!.powerPlay, "2 + 10 + 30 = 42 s: still held")
        r.at(42_000)
        val v = assertNotNull(r.m.view())
        assertNull(v.powerPlay, "closed at the anchor-timed end (an arrival-timed hold would run to 45 s)")
        assertFalse(v.ppEnded)
    }

    @Test
    fun theHoldEndsByElapsedTimeWhenPollsSlowDown() {
        // (Fix pass S4) One unknown poll, then none for a while (a break, a back-off): the hold still ends 30 s after the
        // run began, silently, and the machine wakes for it.
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        r.feed(5_000, g(unknown = true))
        assertEquals(ppHome, r.m.view()!!.powerPlay)
        r.at(20_000)
        assertEquals(ppHome, r.m.view()!!.powerPlay)
        val wake = assertNotNull(r.m.nextWakeAtMono())
        assertTrue(wake <= 35_000, "a wake at the hold's end, not later: $wake")
        r.at(34_999)
        assertEquals(ppHome, r.m.view()!!.powerPlay)
        r.at(35_000)
        val v = assertNotNull(r.m.view())
        assertNull(v.powerPlay, "closed by time, with no new snapshot")
        assertFalse(v.ppEnded)
        // The next known "none" says nothing: the tab is already closed.
        assertFalse(r.feed(40_000, g())!!.ppEnded)
    }

    @Test
    fun aPowerPlayGoalInsideAnUnknownRunNeverSaysOverAndDropsTheBar() {
        // (Fix pass B1) The `goals` node lags the score: the poll with the goal is unknown (H6), the next is known "none".
        val r = Rig()
        r.follow(g(ppHome))
        val views = mutableListOf(assertNotNull(r.feed(0, g(ppHome))))
        val goal = assertNotNull(r.feed(5_000, g(unknown = true, home = 3)))
        views += goal
        assertEquals(Side.HOME, goal.goalSide)
        assertTrue(goal.goalPp, "PP GOAL (Stacked: \"KIT POWER PLAY GOAL\"; Pill: the PP GOAL label)")
        assertNull(goal.powerPlay, "the minor is released by the goal: no \"KIT POWER PLAY\" bar under the mark")
        // 10 s is after the goal mark (4.5 s from 5 s): the bar stays closed, and the known "none" is silent.
        views += assertNotNull(r.feed(10_000, g(home = 3))).also { assertNull(it.goalSide) }
        views += assertNotNull(r.feed(15_000, g(home = 3)))
        assertTrue(views.none { it.ppEnded }, "a power play ended by a goal is never \"Power play over.\"")
        assertTrue(views.drop(1).all { it.powerPlay == null })
    }

    @Test
    fun aShortHandedGoalInsideAnUnknownRunKeepsTheHold() {
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        val sh = assertNotNull(r.feed(5_000, g(unknown = true, away = 2)))
        assertEquals(Side.AWAY, sh.goalSide)
        assertFalse(sh.goalPp)
        assertEquals(ppHome, sh.powerPlay, "a short-handed goal does not end the power play")
        // The known "none" after a goal in the run is treated as a score change: silent.
        assertFalse(r.feed(10_000, g(away = 2))!!.ppEnded)
    }

    @Test
    fun aDelayGrownInsideARunHoldsTheLastKnownPowerPlay() {
        // (Fix pass L1) The delay grows while an unknown snapshot is shown: the earlier unknown snapshot shows again and
        // holds the power play known before the run, silently; the next known PP is not announced again.
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        r.feed(5_000, g(unknown = true))
        r.feed(10_000, g(unknown = true))
        r.at(12_000)
        r.m.prefs(5) // back to the 5 s snapshot (anchor 5 + 5 ≤ 12; 10 + 5 > 12)
        val v = assertNotNull(r.m.view())
        assertEquals(ppHome, v.powerPlay, "the last known power play before the run, held")
        assertFalse(v.ppEnded)
        r.at(15_000) // the 10 s snapshot shows again
        assertEquals(ppHome, r.m.view()!!.powerPlay)
        val back = assertNotNull(r.feed(20_000, g(ppHome.copy(secondsRemaining = 59))).let { r.at(25_000); r.m.view() })
        assertEquals(ppHome.copy(secondsRemaining = 59), back.powerPlay)
        assertFalse(back.ppEnded)
    }

    @Test
    fun aGoalInsideTheHoldIsJudgedByTheHeldPowerPlay() {
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        r.feed(5_000, g(unknown = true))
        // KIT (home, on the held power play) scores; the goals node is behind (no lastGoal).
        val goal = assertNotNull(r.feed(10_000, g(unknown = true, home = 3)))
        assertEquals(Side.HOME, goal.goalSide)
        assertTrue(goal.goalPp, "PP GOAL from the held power play")
        // The same goal after a known "none" is an even-strength goal.
        val r2 = Rig()
        r2.follow(g(ppHome))
        r2.feed(0, g(ppHome))
        r2.feed(5_000, g())
        val even = assertNotNull(r2.feed(10_000, g(unknown = true, home = 3)))
        assertEquals(Side.HOME, even.goalSide)
        assertFalse(even.goalPp)
        // lastGoal still decides first.
        val r3 = Rig()
        r3.follow(g(ppHome))
        r3.feed(0, g(ppHome))
        r3.feed(5_000, g(unknown = true))
        val flagged = assertNotNull(r3.feed(10_000, g(unknown = true, home = 3, lastGoal = GoalFact(Side.HOME, 1, 3, pp = false))))
        assertFalse(flagged.goalPp)
    }

    @Test
    fun aFollowChangeResetsTheHold() {
        val r = Rig()
        r.follow(g(ppHome))
        r.feed(0, g(ppHome))
        r.feed(5_000, g(unknown = true))
        val other = g(unknown = true).copy(id = GameId(League.OHL, "29192"))
        r.follow(other)
        assertNull(r.feed(10_000, other)!!.powerPlay, "a new follow holds nothing")
    }
}
