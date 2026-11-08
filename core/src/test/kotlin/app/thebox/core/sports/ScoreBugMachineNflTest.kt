package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl.md §3, §10.1: the NFL branch of the machine. A fake mono clock ([Rig.now]); polls 20 s apart, so
 * every comparison is fresh (C-N1). The fixture tests map the recorded ESPN bodies through [EspnMapper].
 */
class ScoreBugMachineNflTest {
    private class Rig(start: Long, startOffsetMs: Long = 30 * 60_000L, phaseMs: Long = 1_500L) {
        var now = 0L
        val wall0 = start + startOffsetMs
        val m = ScoreBugMachine({ now }, { wall0 + now }, phaseMs, Random(7))

        fun follow(g: GameSnapshot, demand: Demand = Demand.SEEN) {
            m.follow(SportsFixtures.followedOf(g))
            m.demand(demand)
        }

        fun ok(s: GameSnapshot, ageS: Int = 0) = m.fetched(Fetch.Ok(s, now, wall0 + now, ageS))

        fun at(t: Long) {
            now = t
            m.step()
        }

        /** Feeds [s] 20 s after the previous one and returns the view. */
        fun next(s: GameSnapshot): ScoreBugView? {
            at(now + 20_000)
            ok(s)
            return m.view()
        }
    }

    private val kc = Side.AWAY
    private val buf = Side.HOME

    private fun g(
        away: Int = 0,
        home: Int = 0,
        play: NflPlay = NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(Side.AWAY, 35)),
        possession: Side? = Side.AWAY,
        secs: Int = 600,
        period: Int = 1,
        phase: GamePhase = GamePhase.LIVE,
        redZone: Boolean = false,
        breakKind: NflBreak? = null,
        lastPlay: NflLastPlay? = null,
        finalKind: PeriodKind? = null,
    ): GameSnapshot = SportsFixtures.nflGame(
        phase = phase,
        away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", away),
        home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", home),
        period = period,
        periodKind = if (period >= 5) PeriodKind.OT else PeriodKind.REG,
        otNumber = if (period >= 5) period - 4 else null,
        clock = GameClock(secs, running = false, inIntermission = phase == GamePhase.INTERMISSION),
        finalKind = finalKind,
        detail = SportsFixtures.nflDetail(play, possession, redZone, breakKind, lastPlay),
    )

    private fun down(d: Int = 1, dist: Int? = 10, goal: Boolean = false, field: FieldPos? = FieldPos(kc, 35)) =
        NflPlay.Down(d, dist, goal, field)

    private fun rig(first: GameSnapshot = g(), demand: Demand = Demand.SEEN): Rig =
        Rig(first.startUtcMs).also { it.follow(first, demand); it.ok(first) }

    private fun tab(side: Side, lead: TabLead, tail: TabTail?) = NflTab(side, lead, tail)
    private fun field(zone: FieldZone, yard: Int) = TabTail.Field(zone, yard)

    // ------------------------------------------------------------------ §3.1 the centre table

    @Test
    fun theCentreTableInPlayBreaksAndFinal() {
        val r = rig(g(secs = 222, period = 4))
        val v = assertNotNull(r.m.view())
        assertEquals(League.NFL, v.league)
        assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 4)), v.top, "the quarter on top (the mock's order)")
        assertEquals(CentreText.Clock, v.bottom)
        assertEquals(222, v.clockSecs, "S3: clockSecs with the clock on the second line")
        assertEquals(false, v.dim)
        assertNull(v.powerPlay)
        assertEquals(r.m.view()?.gameId, GameId(League.NFL, "401899001"))
        // 0:00 in play is not "End": ESPN shows 0:00 during untimed downs.
        r.next(g(secs = 0, period = 4)).let {
            assertEquals(CentreText.Period(PeriodLabel(PeriodKind.REG, 4)), it?.top)
            assertEquals(CentreText.Clock, it?.bottom)
            assertEquals(0, it?.clockSecs)
        }
        r.next(g(secs = 0, period = 1, phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD)).let {
            assertEquals(CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1)), it?.top)
            assertNull(it?.bottom, "one line")
            assertNull(it?.clockSecs)
        }
        r.next(g(secs = 0, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME)).let {
            assertEquals(CentreText.Halftime, it?.top)
            assertNull(it?.bottom)
        }
        r.next(g(secs = 300, period = 5)).let { assertEquals(CentreText.Period(PeriodLabel(PeriodKind.OT, 1)), it?.top) }
        r.next(g(secs = 0, period = 5, phase = GamePhase.FINAL, finalKind = PeriodKind.OT)).let {
            assertEquals(CentreText.Final(PeriodKind.OT), it?.top)
            assertNull(it?.bottom)
            assertNull(it?.nfl?.down)
            assertNull(it?.nfl?.tab)
        }
    }

    @Test
    fun staleWaitingAndKnownView() {
        val r = rig(g(secs = 222, period = 4))
        // Stale (no Ok for 60 s): "No update" over the old clock, dimmed; no down column, no tab.
        r.at(r.now + 61_000)
        val stale = assertNotNull(r.m.view())
        assertEquals(CentreText.NoUpdate, stale.top)
        assertEquals(CentreText.Clock, stale.bottom)
        assertEquals(222, stale.clockSecs)
        assertTrue(stale.dim)
        assertNull(stale.nfl?.down)
        assertNull(stale.nfl?.tab)
        assertNotNull(stale.nfl, "non-null for an NFL game")
        // Hidden after 5 min stale: knownView keeps the NFL order.
        r.at(r.now + SportsConstants.HIDE_AFTER_MS)
        assertNull(r.m.view())
        val known = assertNotNull(r.m.knownView())
        assertEquals(League.NFL, known.league)
        assertEquals(CentreText.NoUpdate, known.top)
        assertEquals(CentreText.Clock, known.bottom)
        assertEquals(222, known.clockSecs)
        assertNull(known.nfl)

        // In a break: one line, never a frozen 0:00.
        val b = rig(g(secs = 0, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME))
        b.at(b.now + 81_000) // the intermission cadence is 40 s: stale after 80 s
        assertEquals(CentreText.NoUpdate, b.m.view()?.top)
        assertNull(b.m.view()?.bottom)
        b.at(b.now + SportsConstants.HIDE_AFTER_MS)
        assertNull(b.m.view())
        assertEquals(CentreText.NoUpdate, b.m.knownView()?.top)
        assertNull(b.m.knownView()?.bottom)
        assertNull(b.m.knownView()?.clockSecs)

        // Waiting on the delay: "Delayed" over the oldest clock; in a break, one line.
        val w = Rig(g().startUtcMs)
        w.m.prefs(30)
        w.follow(g(secs = 400))
        w.ok(g(secs = 400))
        val wait = assertNotNull(w.m.view())
        assertEquals(CentreText.Delayed, wait.top)
        assertEquals(CentreText.Clock, wait.bottom)
        assertEquals(400, wait.clockSecs)
        assertTrue(wait.dim)
        assertNull(wait.nfl?.tab)
        val wb = Rig(g().startUtcMs)
        wb.m.prefs(30)
        val half = g(secs = 0, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME)
        wb.follow(half)
        wb.ok(half)
        assertEquals(CentreText.Delayed, wb.m.view()?.top)
        assertNull(wb.m.view()?.bottom)
        // Once shown: the tab appears with the fresh view.
        w.at(30_000)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OWN, 35)), w.m.view()?.nfl?.tab)
    }

    // ------------------------------------------------------------------ S8 and lead sign-off 1

    private val cinAtl = GameId(League.NFL, "401873038")

    private fun fixture(name: String): GameSnapshot = assertNotNull(
        EspnMapper.game(
            Json { ignoreUnknownKeys = true }.decodeFromString(EspnGameDto.serializer(), SportsFixtures.text("sports/nfl/$name-2026-11-08.json")),
            cinAtl, null, "https://a.espncdn.com/",
        ),
        name,
    )

    @Test
    fun theCoinTossShowsNothingAndPollsAtTheLiveCadence() {
        val toss = fixture("scoreboard-live-coin-toss")
        assertEquals(GamePhase.PRE, toss.phase)
        val r = Rig(toss.startUtcMs, startOffsetMs = 5 * 60_000L)
        r.follow(toss)
        r.ok(toss) // received 0, no Age: the next live poll is the bucket end + phase
        assertNull(r.m.view(), "ruling 4: no bug at the coin toss")
        assertEquals(21_500L, r.m.nextWakeAtMono(), "lead sign-off 1: an `in` PRE skips no bucket")
        // A genuine `pre` body (scores null) after the start keeps the pre-game cadence (one bucket skipped).
        val pre = toss.copy(away = toss.away.copy(score = null), home = toss.home.copy(score = null))
        val p = Rig(pre.startUtcMs, startOffsetMs = 5 * 60_000L)
        p.follow(pre)
        p.ok(pre)
        assertEquals(41_500L, p.m.nextWakeAtMono())
        // The first logged play: the bug appears.
        r.at(21_500)
        r.ok(fixture("scoreboard-live-first-down"))
        val v = assertNotNull(r.m.view())
        assertEquals(DownText.Down(1, 10, goal = false), v.nfl?.down)
        assertEquals(tab(Side.HOME, TabLead.BALL, field(FieldZone.OWN, 32)), v.nfl?.tab)
        // A failed poll at the coin toss backs off without the pre-game floor, and a rise to SEEN may poll.
        val f = Rig(toss.startUtcMs, startOffsetMs = 5 * 60_000L)
        f.follow(toss, Demand.UNSEEN)
        f.ok(toss)
        f.at(150_000)
        f.m.demand(Demand.SEEN)
        assertTrue(f.m.pollDue(), "a rise to SEEN polls an `in` PRE at once")
    }

    // ------------------------------------------------------------------ §3.2 the score kind

    @Test
    fun eachRiseGivesItsKind() {
        fun kindsFor(from: GameSnapshot, to: GameSnapshot): ScoreBugView? {
            val r = rig(from)
            return r.next(to)
        }
        kindsFor(g(0, 0), g(6, 0)).let {
            assertEquals(Side.AWAY, it?.goalSide)
            assertEquals(1, it?.goalSeq)
            assertEquals(listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 6, 0)), it?.nfl?.scores)
        }
        assertEquals(listOf(NflScore(NflScoreKind.FIELD_GOAL, 3, 7, 3)), kindsFor(g(7, 0), g(7, 3))?.nfl?.scores)
        assertEquals(listOf(NflScore(NflScoreKind.EXTRA_POINT, 1, 7, 0)), kindsFor(g(6, 0), g(7, 0))?.nfl?.scores)
        assertEquals(listOf(NflScore(NflScoreKind.SAFETY, 2, 0, 2)), kindsFor(g(0, 0), g(0, 2))?.nfl?.scores, "no try pending")
        for (rise in listOf(4, 5, 9, 12)) {
            val v = kindsFor(g(10, 3), g(10 + rise, 3))
            assertNull(v?.goalSide, "+$rise is silent")
            assertEquals(0, v?.goalSeq)
            assertEquals(emptyList(), v?.nfl?.scores)
            assertEquals(10 + rise, v?.away?.score, "the score just updates")
        }
        assertNull(kindsFor(g(7, 3), g(6, 3))?.goalSide, "a fall")
        assertNull(kindsFor(g(0, 0), g(6, 3))?.goalSide, "both sides rose")
    }

    @Test
    fun plus7AndPlus8AreTwoTakeoversWithTheirTalliesAndThe9sMark() {
        val r = rig(g(7, 3))
        val v = assertNotNull(r.next(g(14, 3, play = NflPlay.Kickoff(kc, FieldPos(kc, 35)))))
        assertEquals(
            listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 13, 3), NflScore(NflScoreKind.EXTRA_POINT, 1, 14, 3)),
            v.nfl?.scores,
        )
        assertEquals(Side.AWAY, v.goalSide)
        assertEquals(1, v.goalSeq, "one mark for the pair")
        r.at(r.now + SportsConstants.GOAL_MARK_MS)
        assertEquals(Side.AWAY, r.m.view()?.goalSide, "still marked after 4.5 s")
        r.at(r.now + SportsConstants.NFL_DOUBLE_MARK_MS - SportsConstants.GOAL_MARK_MS)
        assertNull(r.m.view()?.goalSide, "cleared at 9 s")
        assertEquals(emptyList(), r.m.view()?.nfl?.scores)
        // The kick-off body after the +7: "KC KICKOFF | OWN 35", and no try pending (a +7 sets nothing).
        assertEquals(tab(kc, TabLead.KICKOFF, field(FieldZone.OWN, 35)), r.m.view()?.nfl?.tab)

        val e = rig(g(0, 7))
        val v8 = e.next(g(0, 15, play = NflPlay.Kickoff(buf, FieldPos(buf, 35)), possession = buf))
        assertEquals(
            listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 0, 13), NflScore(NflScoreKind.TWO_POINT, 2, 0, 15)),
            v8?.nfl?.scores,
        )
        assertEquals(Side.HOME, v8?.goalSide)
        // A single score keeps the 4.5 s mark.
        val one = rig(g(0, 0))
        one.next(g(3, 0))
        one.at(one.now + SportsConstants.GOAL_MARK_MS)
        assertNull(one.m.view()?.goalSide)
    }

    @Test
    fun theCorrectionGuardNeverReplaysARestoredScore() {
        val r = rig(g(7, 0))
        assertEquals(NflScoreKind.TOUCHDOWN, r.next(g(13, 0))?.nfl?.scores?.single()?.kind)
        assertEquals(1, r.m.view()?.goalSeq)
        r.next(g(7, 0)) // reversed on review
        assertEquals(1, r.m.view()?.goalSeq)
        val restored = r.next(g(13, 0)) // restored 40 s later
        assertEquals(1, restored?.goalSeq, "N5: a rise back to a score shown within QUEUE_KEEP_MS is not flagged")
        assertEquals(13, restored?.away?.score)
        // Long after: a genuine new score to an old value cannot happen, but an old one is forgotten after 130 s.
        val late = rig(g(7, 0))
        late.next(g(13, 0))
        late.next(g(7, 0))
        late.at(late.now + SportsConstants.QUEUE_KEEP_MS + 1_000)
        late.ok(g(7, 0))
        late.next(g(13, 0))
        assertEquals(2, late.m.view()?.goalSeq)
    }

    @Test
    fun theTwoPointTieBreak() {
        val lp = { id: String, text: String, side: Side -> NflLastPlay(id, "99", text, side, 2) }
        // No try pending (the app started during it): a new lastPlay of the scorer decides.
        val a = rig(g(6, 0, play = NflPlay.None, possession = null, lastPlay = lp("1", "Rush", kc)))
        assertEquals(NflScoreKind.TWO_POINT, a.next(g(8, 0, play = NflPlay.None, possession = null, lastPlay = lp("2", "Two-Point Rush", kc)))?.nfl?.scores?.single()?.kind)
        val b = rig(g(6, 0, play = NflPlay.None, possession = null, lastPlay = lp("1", "Rush", kc)))
        assertEquals(NflScoreKind.TWO_POINT, b.next(g(8, 0, play = NflPlay.None, possession = null, lastPlay = lp("2", "2pt Pass", kc)))?.nfl?.scores?.single()?.kind)
        val c = rig(g(0, 0, play = NflPlay.None, possession = null))
        assertEquals(NflScoreKind.SAFETY, c.next(g(0, 2, play = NflPlay.None, possession = null, lastPlay = lp("2", "Safety", buf)))?.nfl?.scores?.single()?.kind)
        // The old lastPlay, or the other side's, does not decide: SAFETY.
        val d = rig(g(6, 0, play = NflPlay.None, possession = null, lastPlay = lp("1", "Two-Point Rush", kc)))
        assertEquals(NflScoreKind.SAFETY, d.next(g(8, 0, play = NflPlay.None, possession = null, lastPlay = lp("1", "Two-Point Rush", kc)))?.nfl?.scores?.single()?.kind)
        val e = rig(g(6, 0, play = NflPlay.None, possession = null))
        assertEquals(NflScoreKind.SAFETY, e.next(g(8, 0, play = NflPlay.None, possession = null, lastPlay = lp("2", "Two-Point Rush", buf)))?.nfl?.scores?.single()?.kind)
    }

    // ------------------------------------------------------------------ §3.3 the try bookkeeping (B2)

    private val td = NflLastPlay("t1", "67", "Passing Touchdown", kc, 6)

    /** The log's 14:40:27 → 14:41:58 rows for KC: a 1st & Goal at BUF 4, then the score on the stale body. */
    private fun tdRig(): Rig {
        val goal = down(1, 4, goal = true, field = FieldPos(buf, 4))
        val r = rig(g(0, 0, play = goal, secs = 672, redZone = true))
        val v = assertNotNull(r.next(g(6, 0, play = goal, secs = 672, redZone = true)))
        assertEquals(NflScoreKind.TOUCHDOWN, v.nfl?.scores?.single()?.kind)
        assertEquals(DownText.Pat, v.nfl?.down, "PAT from the TD snapshot on (the stale body renders as the try)")
        assertEquals(tab(kc, TabLead.BALL, TabTail.ExtraPoint), v.nfl?.tab, "no RED ZONE on the try")
        return r
    }

    @Test
    fun theTryAsADeadBallThenTwoPoint() {
        val r = tdRig()
        val dead = r.next(g(6, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, redZone = false, lastPlay = td))
        assertEquals(DownText.Pat, dead?.nfl?.down)
        assertEquals(tab(kc, TabLead.BALL, TabTail.ExtraPoint), dead?.nfl?.tab)
        val two = r.next(g(8, 0, play = NflPlay.Kickoff(kc, FieldPos(kc, 35)), secs = 672))
        assertEquals(listOf(NflScore(NflScoreKind.TWO_POINT, 2, 8, 0)), two?.nfl?.scores)
        assertEquals(tab(kc, TabLead.KICKOFF, field(FieldZone.OWN, 35)), two?.nfl?.tab)
        assertEquals(DownText.Kickoff, two?.nfl?.down)
        // After the kick-off nothing is pending: a later +2 for KC is a safety.
        r.next(g(8, 0, possession = buf, play = down(field = FieldPos(buf, 25)), secs = 600))
        assertEquals(NflScoreKind.SAFETY, r.next(g(10, 0, possession = buf, play = down(field = FieldPos(buf, 2)), secs = 590))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun theTryAsADownAtTheTwoWithTheClockUnchangedThenTwoPoint() {
        val r = tdRig()
        val asDown = r.next(g(6, 0, play = down(1, 2, goal = true, field = FieldPos(buf, 2)), secs = 672, redZone = true))
        assertEquals(DownText.Pat, asDown?.nfl?.down, "a Down at tryAt renders as the try")
        assertEquals(tab(kc, TabLead.BALL, TabTail.ExtraPoint), asDown?.nfl?.tab)
        assertEquals(NflScoreKind.TWO_POINT, r.next(g(8, 0, play = NflPlay.Kickoff(kc, FieldPos(kc, 35)), secs = 672))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun aDownWithAnotherClockClearsTheTry() {
        val r = tdRig()
        val moved = r.next(g(6, 0, play = down(1, 10, field = FieldPos(kc, 25)), possession = buf, secs = 660))
        assertEquals(DownText.Down(1, 10, goal = false), moved?.nfl?.down)
        assertEquals(tab(buf, TabLead.BALL, field(FieldZone.OPP, 25)), moved?.nfl?.tab)
        assertEquals(NflScoreKind.SAFETY, r.next(g(6, 2, play = NflPlay.None, possession = null, secs = 650))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun aPhaseChangeAloneDoesNotClearTheTry() {
        // A TD on the last play of the half; ESPN is already at HALFTIME for the try.
        val goal = down(1, 3, goal = true, field = FieldPos(buf, 3))
        val r = rig(g(0, 0, play = goal, secs = 2, period = 2))
        r.next(g(6, 0, play = goal, secs = 0, period = 2))
        val half = r.next(g(6, 0, play = goal, secs = 0, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME))
        assertEquals(CentreText.Halftime, half?.top)
        assertEquals(DownText.Pat, half?.nfl?.down, "the pending try keeps PAT through the break")
        assertEquals(tab(kc, TabLead.BALL, TabTail.ExtraPoint), half?.nfl?.tab)
        val xp = r.next(g(7, 0, play = goal, secs = 0, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME))
        assertEquals(NflScoreKind.EXTRA_POINT, xp?.nfl?.scores?.single()?.kind)
        assertNull(xp?.nfl?.tab, "the try is over: the break closes the tab")
        assertNull(xp?.nfl?.down)
    }

    @Test
    fun anUnflaggedSixStillSetsTheTry() {
        val goal = down(1, 4, goal = true, field = FieldPos(buf, 4))
        val r = rig(g(0, 0, play = goal, secs = 672), demand = Demand.UNSEEN)
        r.next(g(6, 0, play = goal, secs = 672))
        assertEquals(0, r.m.view()?.goalSeq, "UNSEEN: no flag")
        r.m.demand(Demand.SEEN)
        assertEquals(DownText.Pat, r.m.view()?.nfl?.down)
        assertEquals(NflScoreKind.TWO_POINT, r.next(g(8, 0, play = NflPlay.Kickoff(kc, FieldPos(kc, 35)), secs = 672))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun aDeadBallWithATdLastPlaySetsTheTryWhenStartedDuringIt() {
        val r = rig(g(6, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))
        assertEquals(DownText.Pat, r.m.view()?.nfl?.down)
        assertEquals(tab(kc, TabLead.BALL, TabTail.ExtraPoint), r.m.view()?.nfl?.tab)
        assertEquals(NflScoreKind.TWO_POINT, r.next(g(8, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))?.nfl?.scores?.single()?.kind)
        // The XP's body still names the TD: the try is over and is never set again from it.
        val x = rig(g(6, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))
        val xp = x.next(g(7, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))
        assertEquals(NflScoreKind.EXTRA_POINT, xp?.nfl?.scores?.single()?.kind)
        assertNull(xp?.nfl?.down, "no PAT after the try")
        x.next(g(7, 0, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))
        assertNull(x.m.view()?.nfl?.down)
        assertEquals(NflScoreKind.SAFETY, x.next(g(7, 2, play = NflPlay.Dead(kc, 6), possession = null, secs = 672, lastPlay = td))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun aDefensiveTdRendersTheScorersTryAtOnce() {
        // §9.2 Q3: KC 1st & 10 at KC 40; BUF scores on the stale body.
        val kc40 = down(1, 10, field = FieldPos(kc, 40))
        val r = rig(g(14, 5, play = kc40, secs = 500, period = 3))
        val v = r.next(g(14, 11, play = kc40, secs = 500, period = 3, lastPlay = NflLastPlay("d", "36", "Interception Return Touchdown", buf, 6)))
        assertEquals(Side.HOME, v?.goalSide)
        assertEquals(NflScoreKind.TOUCHDOWN, v?.nfl?.scores?.single()?.kind)
        assertEquals(tab(buf, TabLead.BALL, TabTail.ExtraPoint), v?.nfl?.tab, "\"BUF BALL | EXTRA POINT\" from the first snapshot")
        assertEquals(DownText.Pat, v?.nfl?.down)
        // The two-point try drawn as a real down, clock unchanged: still PAT; then TWO-POINT.
        val asDown = r.next(g(14, 11, play = down(1, 2, goal = true, field = FieldPos(kc, 2)), possession = buf, secs = 500, period = 3))
        assertEquals(DownText.Pat, asDown?.nfl?.down)
        val two = r.next(g(14, 13, play = NflPlay.Kickoff(buf, FieldPos(buf, 35)), possession = buf, secs = 500, period = 3))
        assertEquals(NflScoreKind.TWO_POINT, two?.nfl?.scores?.single()?.kind)
        assertEquals(tab(buf, TabLead.KICKOFF, field(FieldZone.OWN, 35)), two?.nfl?.tab)
    }

    // ------------------------------------------------------------------ §3.4 gating, inference, field

    @Test
    fun theLogReplaysWithTheRightTabs() {
        // CIN @ ATL, through the recorded bodies: 14:36:52 → 15:07:25.
        val first = fixture("scoreboard-live-first-down")
        val r = Rig(first.startUtcMs)
        r.follow(first)
        r.ok(first)
        assertEquals(tab(Side.HOME, TabLead.BALL, field(FieldZone.OWN, 32)), r.m.view()?.nfl?.tab)
        val score = r.next(fixture("scoreboard-live-td-score-first"))
        assertEquals(NflScoreKind.TOUCHDOWN, score?.nfl?.scores?.single()?.kind)
        assertEquals(DownText.Pat, score?.nfl?.down)
        assertEquals(tab(Side.HOME, TabLead.BALL, TabTail.ExtraPoint), score?.nfl?.tab)
        val tryV = r.next(fixture("scoreboard-live-try"))
        assertEquals(DownText.Pat, tryV?.nfl?.down, "isRedZone is true but the try is never RED ZONE")
        assertEquals(tab(Side.HOME, TabLead.BALL, TabTail.ExtraPoint), tryV?.nfl?.tab)
        val ko = r.next(fixture("scoreboard-live-kickoff"))
        assertEquals(NflScoreKind.EXTRA_POINT, ko?.nfl?.scores?.single()?.kind)
        assertEquals(DownText.Kickoff, ko?.nfl?.down)
        assertEquals(tab(Side.HOME, TabLead.KICKOFF, field(FieldZone.OWN, 35)), ko?.nfl?.tab, "isRedZone true, no RED ZONE")
        // The +7 body (TD and XP together), already in the kick-off state.
        val p7 = r.next(fixture("scoreboard-live-plus7-away-kickoff"))
        assertEquals(
            listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 6, 7), NflScore(NflScoreKind.EXTRA_POINT, 1, 7, 7)),
            p7?.nfl?.scores,
        )
        assertEquals(tab(Side.AWAY, TabLead.KICKOFF, field(FieldZone.OWN, 35)), p7?.nfl?.tab)
        // B1/N6: no possession after the kick-off; lastPlay 53's team (ATL) is the receiver; the field ATL 40 is OWN.
        val noPoss = r.next(fixture("scoreboard-live-no-possession-after-kickoff"))
        assertEquals(tab(Side.HOME, TabLead.BALL, field(FieldZone.OWN, 40)), noPoss?.nfl?.tab, "\"ATL BALL | OWN 40\"")
        assertEquals(DownText.Down(1, 10, goal = false), noPoss?.nfl?.down)
    }

    @Test
    fun aFieldGoalDeadBallIsNoTryAndHoldsTheTab() {
        val r = Rig(fixture("scoreboard-live-first-down").startUtcMs)
        val before = fixture("scoreboard-live-end-period").let {
            // CIN ball at ATL 30 in play (the fixture's situation, LIVE in the 1st), 7–7.
            it.copy(phase = GamePhase.LIVE, detail = (it.detail as LeagueDetail.Nfl).copy(breakKind = null), home = it.home.copy(score = 7))
        }
        r.follow(before)
        r.ok(before)
        val held = tab(Side.AWAY, TabLead.BALL, field(FieldZone.OPP, 30))
        assertEquals(held, r.m.view()?.nfl?.tab)
        val fg = r.next(fixture("scoreboard-live-fg-dead-ball"))
        assertEquals(NflScoreKind.FIELD_GOAL, fg?.nfl?.scores?.single()?.kind)
        assertNull(fg?.nfl?.down, "not PAT: the down column is blank")
        assertEquals(held, fg?.nfl?.tab, "the tab is held (no close/open before the kick-off body)")
        val ko = r.next(fixture("scoreboard-live-kickoff-after-fg"))
        assertEquals(tab(Side.HOME, TabLead.KICKOFF, field(FieldZone.OWN, 35)), ko?.nfl?.tab)
    }

    @Test
    fun theInferenceOrder() {
        // 2. The other side of the previously shown kick-off (no lastPlay).
        val r = rig(g(play = NflPlay.Kickoff(kc, FieldPos(kc, 35))))
        assertEquals(tab(kc, TabLead.KICKOFF, field(FieldZone.OWN, 35)), r.m.view()?.nfl?.tab)
        val a = r.next(g(play = down(field = FieldPos(buf, 25)), possession = null))
        assertEquals(tab(buf, TabLead.BALL, field(FieldZone.OWN, 25)), a?.nfl?.tab)
        // 3. Then the previously shown Down's possession (two polls with none, no flicker).
        val b = r.next(g(play = down(2, 7, field = FieldPos(buf, 28)), possession = null))
        assertEquals(tab(buf, TabLead.BALL, field(FieldZone.OWN, 28)), b?.nfl?.tab)
        // 1. The lastPlay kick-off's team beats the kick-off memory (an onside recovery).
        val o = rig(g(play = NflPlay.Kickoff(kc, FieldPos(kc, 35))))
        val onside = o.next(g(play = down(field = FieldPos(buf, 45)), possession = null, lastPlay = NflLastPlay("k", "53", "Kickoff", kc, 0)))
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OPP, 45)), onside?.nfl?.tab)
        // 4. Nothing to infer from: no tab, but the down column shows.
        val n = rig(g(play = NflPlay.None, possession = null))
        val none = n.next(g(play = down(field = FieldPos(buf, 25)), possession = null))
        assertNull(none?.nfl?.tab)
        assertEquals(DownText.Down(1, 10, goal = false), none?.nfl?.down)
        // None → nothing; a field-less down shows the lead alone; midfield.
        assertNull(n.next(g(play = NflPlay.None))?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, null), n.next(g(play = down(field = null)))?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.MIDFIELD, 50)), n.next(g(play = down(field = FieldPos(null, 50))))?.nfl?.tab)
        assertNull(n.next(g(play = NflPlay.Kickoff(null, FieldPos(kc, 35)), possession = null))?.nfl?.tab, "a kick-off with no kicker")
    }

    // ------------------------------------------------------------------ §3.5 red zone (ruling 26)

    @Test
    fun theRedZoneGating() {
        val r = rig(g(play = down(1, 10, field = FieldPos(buf, 12)), redZone = true))
        assertEquals(tab(kc, TabLead.BALL, TabTail.RedZone), r.m.view()?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, TabTail.RedZone), r.next(g(play = down(1, 10, field = FieldPos(buf, 20)), redZone = true))?.nfl?.tab, "at the 20")
        // isRedZone with the ball outside the opponent's 20, or in its own half: the plain field.
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OPP, 27)), r.next(g(play = down(1, 10, field = FieldPos(buf, 27)), redZone = true))?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OWN, 12)), r.next(g(play = down(1, 10, field = FieldPos(kc, 12)), redZone = true))?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.MIDFIELD, 50)), r.next(g(play = down(1, 10, field = FieldPos(null, 50)), redZone = true))?.nfl?.tab)
        // The feed's flag off: never red.
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OPP, 5)), r.next(g(play = down(1, 5, field = FieldPos(buf, 5)), redZone = false))?.nfl?.tab)
        // No field: the flag decides.
        assertEquals(tab(kc, TabLead.BALL, TabTail.RedZone), r.next(g(play = down(1, 5, field = null), redZone = true))?.nfl?.tab)
        // An inferred side counts.
        val k = rig(g(play = NflPlay.Kickoff(buf, FieldPos(buf, 35)), possession = buf))
        assertEquals(tab(kc, TabLead.BALL, TabTail.RedZone), k.next(g(play = down(1, 10, field = FieldPos(buf, 15)), possession = null, redZone = true))?.nfl?.tab)
        // The held tab after a FG from the red zone is the plain one.
        val h = rig(g(play = down(4, 3, field = FieldPos(buf, 8)), redZone = true))
        assertEquals(tab(kc, TabLead.BALL, TabTail.RedZone), h.m.view()?.nfl?.tab)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OPP, 8)), h.next(g(3, 0, play = NflPlay.Dead(kc, 3), possession = null))?.nfl?.tab)
    }

    // ------------------------------------------------------------------ §3.6 the delay and reset

    @Test
    fun theDelayAndTheBackInTimePathResetTheNflState() {
        val goal = down(1, 4, goal = true, field = FieldPos(buf, 4))
        val r = rig(g(0, 0, play = goal, secs = 672))
        r.next(g(6, 0, play = goal, secs = 672))
        assertEquals(DownText.Pat, r.m.view()?.nfl?.down)
        // The delay grows: the earlier snapshot is shown again, with the try, memory and held tab reset.
        r.m.prefs(15)
        val back = assertNotNull(r.m.view())
        assertEquals(0, back.away.score)
        assertEquals(DownText.Down(1, 4, goal = true), back.nfl?.down)
        assertEquals(tab(kc, TabLead.BALL, field(FieldZone.OPP, 4)), back.nfl?.tab)
        // Forward again on the delayed timeline: the TD snapshot sets the try again (no second takeover: N5).
        r.at(r.now + 15_000)
        assertEquals(6, r.m.view()?.away?.score)
        assertEquals(DownText.Pat, r.m.view()?.nfl?.down)
        assertEquals(1, r.m.view()?.goalSeq)

        // reset() (a re-follow) clears it: a +2 after the re-follow is a safety.
        r.m.follow(null)
        r.m.prefs(0)
        r.follow(g(6, 0, play = goal, secs = 672))
        r.ok(g(6, 0, play = goal, secs = 672))
        assertEquals(DownText.Down(1, 4, goal = true), r.m.view()?.nfl?.down, "nothing pending after the reset")
        assertEquals(NflScoreKind.SAFETY, r.next(g(8, 0, play = NflPlay.None, possession = null, secs = 660))?.nfl?.scores?.single()?.kind)
    }

    @Test
    fun anNhlGameNeverCarriesAnNflView() {
        val r = Rig(SportsFixtures.game().startUtcMs)
        r.follow(SportsFixtures.game())
        r.ok(SportsFixtures.game())
        assertEquals(SportsFixtures.liveView(), r.m.view())
        assertNull(r.m.view()?.nfl)
        assertEquals(League.NHL, r.m.view()?.league)
    }
}
