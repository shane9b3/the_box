package app.thebox.core.sports

import app.thebox.core.sports.ChlFixtures.CLIENTS
import app.thebox.core.sports.ChlFixtures.REG_CLOCK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (CHL §5.8, §7.2, S8) The derivation on the 42 recorded finished games (`firebase/finals-2026-11-13`): the goal-instant
 * replay, the duration audit and second-by-second parity with the reference model `tools/chl/pp_replay.py`.
 */
class ChlPowerPlayReplayTest {

    // ------------------------------------------------------------------ §5.8 goal instants

    private data class Miss(val client: String, val gameId: String, val period: Int?, val timeS: Int?, val kind: String)

    @Test
    fun goalInstantReplayMeetsTheDesignFloor() {
        val ok = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        val misses = ArrayList<Miss>()
        var games = 0
        var noPenaltiesNode = 0
        var hiddenByH9 = 0
        for (client in CLIENTS) {
            val pens = ChlFixtures.finals(client, "penalties")
            for (gid in ChlFixtures.finals(client, "goals").keys) {
                games++
                if (gid !in pens) { noPenaltiesNode++; continue }
                val r = ChlFixtures.Replay(client, gid)
                for (mg in r.placed) {
                    val side = if (mg.goal.home) Side.HOME else Side.AWAY
                    val kind = when { mg.goal.pp -> "PP"; mg.goal.sh -> "SH"; else -> "EV" }
                    val agreed = when (kind) {
                        "PP" -> mg.adv == side
                        "SH" -> mg.adv != null && mg.adv != side
                        else -> mg.adv == null
                    }
                    total.merge(kind, 1, Int::plus)
                    if (agreed) ok.merge(kind, 1, Int::plus) else misses += Miss(client, gid, mg.goal.period, mg.goal.timeS, kind)
                    if (r.model.hidden(mg.t)) hiddenByH9++
                }
            }
        }
        assertEquals(42, games)
        assertEquals(2, noPenaltiesNode)
        // The regression floor (design §5.8): fewer agreements fail.
        assertTrue(ok.getValue("PP") >= 56, "PP ${ok["PP"]}/${total["PP"]}")
        assertTrue(ok.getValue("SH") >= 5, "SH ${ok["SH"]}/${total["SH"]}")
        assertTrue(ok.getValue("EV") >= 161, "EV ${ok["EV"]}/${total["EV"]}")
        assertEquals(mapOf("PP" to 57, "SH" to 5, "EV" to 161), total)
        // The one documented miss: OHL 29182 P3 00:41, a PP goal with no penalty on record (H5 hides it, E21).
        assertEquals(listOf(Miss("ohl", "29182", 3, 41, "PP")), misses)
        assertEquals(0, hiddenByH9, "no goal second falls inside an H9 window")
    }

    @Test
    fun theDocumentedMissIsHiddenByH5() {
        val pens = ChlFixtures.penalties("ohl", "29182")!!
        val goal = ChlFixtures.goals("ohl", "29182").single { it.period == 3 && it.timeS == 41 }
        val nowG = REG_CLOCK.g(3, 41 + 30)!!
        val known = pens.filter { REG_CLOCK.g(it.period, it.timeS ?: 0)!! <= nowG }
        val goals = ChlFixtures.goals("ohl", "29182").filter { REG_CLOCK.g(it.period!!, it.timeS!!)!! <= nowG }
        assertTrue(goal in goals)
        val pp = ChlPowerPlay.derive(known, goals, goals.size, 9, ChlNow(3, 1200 - 71, 3, GamePhase.LIVE, false, false), ::regLength)
        assertEquals(ChlPp.Unknown, pp)
    }

    // ------------------------------------------------------------------ S8 the duration audit

    private data class Diff(val client: String, val gameId: String, val period: Int, val time: String)

    @Test
    fun durationAuditMatchesTheDesignWithTheFiveListedArtifacts() {
        var compared = 0
        var matched = 0
        var notStarted = 0
        val diffs = ArrayList<Diff>()
        for (client in CLIENTS) {
            for (gid in ChlFixtures.finals(client, "penalties").keys) {
                // The model without TimeOn, so it is independent of the recorded ends it is checked against.
                val r = ChlFixtures.Replay(client, gid, useTimeOn = false)
                for (p in r.pens) {
                    val on = p.onS ?: continue // TimeOnSet == 1 and parsable
                    if (!p.ppFlag || p.minutes != 2) continue
                    val last = r.rows.lastOrNull { it.id == p.id }
                    if (last == null || !last.started) { notStarted++; continue }
                    compared++
                    val d = listOf(p.period, p.period + 1).minOf { q -> kotlin.math.abs(last.e - REG_CLOCK.g(q, on)!!) }
                    if (d == 0) matched++ else diffs += Diff(client, gid, p.period, mmss(p.timeS!!))
                }
            }
        }
        assertEquals(0, notStarted)
        assertEquals(129, compared)
        assertEquals(124, matched)
        // Design §5.8: the scorekeeper artifacts. Any other difference fails.
        assertEquals(
            setOf(
                Diff("ohl", "29179", 2, "15:07"), // chain end copied onto link 1
                Diff("ohl", "29179", 2, "15:11"), // chain end copied onto link 1
                Diff("ohl", "29187", 1, "10:10"), // chain end copied onto link 1
                Diff("ohl", "29179", 3, "06:29"), // TimeOn not updated after the 07:40 PP goal
                Diff("ohl", "29179", 3, "19:08"), // game end at 20:00
            ),
            diffs.toSet(),
        )
        assertEquals(5, diffs.size)
    }

    // ------------------------------------------------------------------ parity with tools/chl/pp_replay.py

    /**
     * Every second of every recorded game with a penalties node (40 games, 0..`Sim.end`): the Kotlin [ChlPowerPlay.Model]
     * against `tools/chl/pp_parity.py`'s dump of the reference `Sim.state(t)`: the advantaged side, the kind, the seat
     * end (so the seconds) and the H9 hidden flag.
     */
    @Test
    fun theModelAgreesWithTheReferenceAtEverySecond() {
        val lines = SportsFixtures.text(ChlFixtures.PARITY).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        var replay: ChlFixtures.Replay? = null
        var games = 0
        var seconds = 0L
        var advantageSeconds = 0L
        var expectedEnd = -1
        var covered = -1
        for (line in lines) {
            val f = line.split(' ')
            when (f[0]) {
                "G" -> {
                    replay?.let { assertEquals(expectedEnd, covered, "${it.client} ${it.gameId} covered") }
                    val r = ChlFixtures.Replay(f[1], f[2])
                    assertEquals(f[3].toInt(), r.end, "${f[1]} ${f[2]} end")
                    replay = r
                    expectedEnd = r.end
                    covered = -1
                    games++
                }
                "S" -> {
                    val r = replay!!
                    val from = f[1].toInt()
                    val to = f[2].toInt()
                    assertEquals(covered + 1, from)
                    covered = to
                    for (t in from..to) {
                        val st = r.model.stateAt(t)
                        val got = if (st.away == st.home) {
                            "- - - -"
                        } else {
                            val adv = if (st.away > st.home) "H" else "A"
                            val kind = if (kotlin.math.abs(st.away - st.home) >= 2) "TWO_MAN" else "ONE_MAN"
                            val ends = if (adv == "H") st.awayEnds else st.homeEnds
                            advantageSeconds++
                            "$adv $kind ${ends.min()} ${if (r.model.hidden(t)) 1 else 0}"
                        }
                        assertEquals(f.subList(3, 7).joinToString(" "), got, "${r.client} ${r.gameId} at game second $t")
                        seconds++
                    }
                }
                else -> error("bad parity line $line")
            }
        }
        replay?.let { assertEquals(expectedEnd, covered, "${it.client} ${it.gameId} covered") }
        println("parity: $games games, $seconds seconds, $advantageSeconds advantage seconds")
        assertEquals(40, games)
        assertTrue(seconds > 150_000, "$seconds seconds compared")
        assertTrue(advantageSeconds > 10_000, "$advantageSeconds advantage seconds compared")
    }

    /** `pp_replay.py all`'s probes (E29–E32 and QMJHL 32721), through [ChlPowerPlay.derive] with the data known then. */
    @Test
    fun theReferenceProbesThroughDerive() {
        val cases = listOf(
            Triple("lhjmq", "32755", "1:17:30") to shown(Side.HOME, PowerPlayKind.ONE_MAN, 198),
            Triple("lhjmq", "32755", "1:19:30") to shown(Side.HOME, PowerPlayKind.ONE_MAN, 78),
            Triple("lhjmq", "32721", "3:04:00") to shown(Side.AWAY, PowerPlayKind.ONE_MAN, 201),
            Triple("ohl", "29185", "1:06:30") to shown(Side.AWAY, PowerPlayKind.ONE_MAN, 186),
            Triple("ohl", "29185", "1:10:30") to ChlPp.None,
            Triple("whl", "1023281", "2:05:30") to ChlPp.Unknown, // H9 (the reference: AWAY ONE_MAN 286 s, HIDDEN)
            Triple("whl", "1023281", "2:08:00") to shown(Side.AWAY, PowerPlayKind.ONE_MAN, 136),
            Triple("ohl", "29179", "2:15:10") to shown(Side.HOME, PowerPlayKind.ONE_MAN, 237),
            Triple("ohl", "29179", "2:16:00") to shown(Side.HOME, PowerPlayKind.TWO_MAN, 76),
            Triple("ohl", "29179", "2:18:00") to shown(Side.HOME, PowerPlayKind.ONE_MAN, 50),
        )
        for ((probe, expected) in cases) {
            val (client, gid, at) = probe
            assertEquals(expected, deriveKnownAt(client, gid, at), "$client $gid at P$at")
        }
    }

    /**
     * The derivation polled every 5 s through each recorded game with the rows and goals known by then (called at or
     * before the clock). Wherever it shows a bar, the side and kind are the reference model's at that second (with
     * full knowledge); the seconds may differ only by what was not yet known.
     */
    @Test
    fun aPolledReplayNeverShowsAWrongSideOrKind() {
        var shownPolls = 0
        var secondsDiffer = 0
        for (client in CLIENTS) {
            for (gid in ChlFixtures.finals(client, "penalties").keys) {
                val full = ChlFixtures.Replay(client, gid)
                val pens = full.pens
                val goals = full.goals
                val lastPeriod = (pens.map { it.period } + goals.mapNotNull { it.period } + 3).max()
                for (p in 1..lastPeriod) {
                    val len = regLength(p)!!
                    for (el in 0..len step 5) {
                        val nowG = REG_CLOCK.g(p, el)!!
                        if (nowG > full.end) break
                        val knownPens = pens.filter { (it.timeS?.let { t -> REG_CLOCK.g(it.period, t) } ?: 0) <= nowG }
                        val knownGoals = goals.filter { g -> REG_CLOCK.g(g.period ?: 99, g.timeS ?: 0)?.let { it <= nowG } == true }
                        val pp = ChlPowerPlay.derive(
                            knownPens, knownGoals, knownGoals.size, 1, ChlNow(p, len - el, p, GamePhase.LIVE, false, false),
                            ::regLength,
                        )
                        if (pp !is ChlPp.Shown) continue
                        shownPolls++
                        val st = full.model.stateAt(nowG)
                        assertTrue(st.away != st.home, "$client $gid P$p ${mmss(el)}: shown $pp, the reference is even")
                        val adv = if (st.away > st.home) Side.HOME else Side.AWAY
                        val kind = if (kotlin.math.abs(st.away - st.home) >= 2) PowerPlayKind.TWO_MAN else PowerPlayKind.ONE_MAN
                        assertEquals(adv, pp.pp.side, "$client $gid P$p ${mmss(el)}")
                        assertEquals(kind, pp.pp.kind, "$client $gid P$p ${mmss(el)}")
                        val ends = if (adv == Side.HOME) st.awayEnds else st.homeEnds
                        if (ends.min() - nowG != pp.pp.secondsRemaining) secondsDiffer++
                    }
                }
            }
        }
        println("polled replay: $shownPolls polls showed a bar, $secondsDiffer with other seconds")
        assertTrue(shownPolls > 1_500, "$shownPolls polls showed a bar")
        assertEquals(0, secondsDiffer, "seconds that differ from the full-knowledge reference")
    }

    // ------------------------------------------------------------------ helpers

    private fun shown(side: Side, kind: PowerPlayKind, secs: Int): ChlPp = ChlPp.Shown(PowerPlay(side, kind, secs))

    private fun deriveKnownAt(client: String, gid: String, at: String): ChlPp {
        val nowG = ChlFixtures.at(at)
        val period = at.substringBefore(':').toInt()
        val elapsed = nowG - REG_CLOCK.g(period, 0)!!
        val pens = ChlFixtures.penalties(client, gid)!!.filter { REG_CLOCK.g(it.period, it.timeS!!)!! <= nowG }
        val goals = ChlFixtures.goals(client, gid).filter { REG_CLOCK.g(it.period!!, it.timeS!!)!! <= nowG }
        val now = ChlNow(period, regLength(period)!! - elapsed, period, GamePhase.LIVE, shootout = false, decided = false)
        return ChlPowerPlay.derive(pens, goals, goals.size, 1, now, ::regLength)
    }

    private fun mmss(s: Int): String = "%02d:%02d".format(s / 60, s % 60)

    companion object {
        fun regLength(q: Int): Int? = if (q <= 3) 1200 else 300
    }
}
