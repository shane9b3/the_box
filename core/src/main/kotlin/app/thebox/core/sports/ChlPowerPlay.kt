// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §5.1): the input / output types and the [ChlPowerPlay.derive]
// signature are fixed for WP-1 (the derivation) and the mapper; changes go through the lead.
package app.thebox.core.sports

/**
 * (CHL §5.1) One `penalties` row, as the mapper reads it (Appendix B field names). Game times are in s.
 * - [home]: `Home == 1`, the penalised team is the home team;
 * - [timeS]: `Time`, elapsed in [period] when called; [startS]: `TimeStart` when parsable and `TimeStartSet != 0`;
 * - [onS]: `TimeOn` when `TimeOnSet == 1` and parsable;
 * - [ppFlag]: `PowerPlay == 1`; [penaltyShot]: `PenaltyShot == 1`;
 * - [playerId]: `PenalizedPlayerId`, 0 → null (B2; bench minors).
 */
data class ChlPenalty(
    val id: Long,
    val home: Boolean,
    val period: Int,
    val timeS: Int?,
    val startS: Int?,
    val onS: Int?,
    val minutes: Int?,
    val ppFlag: Boolean,
    val penaltyShot: Boolean,
    val playerId: Long?,
)

/** (CHL §5.1) One `goals` row: `IsHome`, `Period`, `Time` (elapsed s), `PowerPlay`, `ShortHanded`, `PenaltyShot`. */
data class ChlGoal(
    val id: Long,
    val home: Boolean,
    val period: Int?,
    val timeS: Int?,
    val pp: Boolean,
    val sh: Boolean,
    val penaltyShot: Boolean,
)

/** (CHL §5.1) [period] / [remainingS] after §3.4's adjustment; [feedPeriod] the raw feed period (S12). */
data class ChlNow(
    val period: Int,
    val remainingS: Int,
    val feedPeriod: Int,
    val phase: GamePhase,
    val shootout: Boolean,
    val decided: Boolean,
)

/** (CHL §5.1) The derivation's answer: no power play, unknown (a §5.6 hide rule fired), or one to show. */
sealed interface ChlPp {
    data object None : ChlPp
    data object Unknown : ChlPp
    data class Shown(val pp: PowerPlay) : ChlPp
}

/**
 * (CHL §5) The derived power play: pure, no I/O, no clock. The executable reference is `tools/chl/pp_replay.py`; the
 * mapper sets `GameSnapshot.powerPlay` from [ChlPp.Shown] and `powerPlayUnknown = (result == ChlPp.Unknown)`.
 *
 * The model ([Model]) follows the reference's one-second rules exactly, but steps from event to event (a row's start, a
 * seat's end, a goal): nothing changes between two events, so the state at any second is the state after the last
 * event at or before it. `ChlPowerPlayReplayTest` checks it against the reference at every second of the recorded games.
 */
object ChlPowerPlay {
    /**
     * [pens] / [goals]: the nodes' rows (null = the node is `null`); [goalTotal]: both teams' goals, the shootout +1
     * excluded; [ppAttempts]: both teams' `*PowerPlayAttempts` (null = unknown); [periodLength]: a period's length in s
     * (null = unknown, H1).
     *
     * (CHL fix pass L7) [now] is non-null by construction, so H1's "no clock" and "period < 1" are the **mapper's**
     * checks: with no clock or no period it does not call [derive] and sets `powerPlayUnknown = true` itself. [derive]
     * checks only the period lengths part of H1.
     */
    fun derive(
        pens: List<ChlPenalty>?,
        goals: List<ChlGoal>?,
        goalTotal: Int,
        ppAttempts: Int?,
        now: ChlNow,
        periodLength: (Int) -> Int?,
    ): ChlPp {
        // §5.3 step 10: only in play.
        if (now.phase != GamePhase.LIVE && now.phase != GamePhase.INTERMISSION) return ChlPp.None
        if (now.shootout || now.decided) return ChlPp.None

        // H1: the clock must be placeable (the mapper has already checked "no clock" / "period < 1").
        if (now.period < 1) return ChlPp.Unknown
        val clock = GameClockMap.of(periodLength, maxOf(now.period, now.feedPeriod) + 1)
        for (q in 1..now.period) if (clock.length(q) == null) return ChlPp.Unknown
        val len = clock.length(now.period) ?: return ChlPp.Unknown
        if (now.remainingS !in 0..len) return ChlPp.Unknown
        val nowG = clock.g(now.period, len - now.remainingS) ?: return ChlPp.Unknown

        // H7: no penalties node while the summary counts power plays (or cannot say).
        if (pens == null && (ppAttempts == null || ppAttempts > 0)) return ChlPp.Unknown

        // (WP-1 fix pass L3) A hostile-size node: hide instead of paying for the model (real games have ≤ 40 rows).
        if ((goals?.size ?: 0) > MAX_GOALS) return ChlPp.Unknown
        val built = buildRows(pens.orEmpty(), clock, useTimeOn = true, feedPeriod = now.feedPeriod)
        if (built.rows.size > MAX_COUNTED_ROWS) return ChlPp.Unknown
        // H2 (rows): a counted row with an unparsable time, minutes null, a period after the feed's, or (fix pass L2) a
        // `Time` / `TimeStart` past its period's length.
        if (built.unplaceable) return ChlPp.Unknown
        // H3: a counted row (the first of its chain; later links start where the chain puts them) starts in the future.
        if (built.rows.any { it.firstLink && it.s > nowG + SportsConstants.CHL_PP_FUTURE_TOLERANCE_S }) return ChlPp.Unknown

        val placed = ArrayList<ModelGoal>()
        var unplacedGoal = false
        goals?.forEach { gl ->
            val t = if (gl.period != null && gl.period >= 1 && gl.timeS != null) clock.g(gl.period, gl.timeS) else null
            if (t == null) unplacedGoal = true else placed += ModelGoal(gl, t)
        }
        val model = Model(built.rows, placed, h9Windows(pens.orEmpty(), clock), clock)
        model.run(nowG)
        val st = model.stateAt(nowG)
        val serving = st.away + st.home > 0

        // H2 (goals, S13): a goal that cannot be placed while a row serves (its release cannot be placed).
        if (unplacedGoal && serving) return ChlPp.Unknown
        // H6: the goals node is missing or behind the summary while a row serves.
        if (serving && (goals == null || goals.size != goalTotal)) return ChlPp.Unknown
        // H5: a recent goal's own flag contradicts the model.
        for (mg in model.goals) {
            if (mg.t > nowG || nowG - mg.t > SportsConstants.CHL_PP_CONSISTENCY_WINDOW_S) continue
            val side = if (mg.goal.home) Side.HOME else Side.AWAY
            if (mg.goal.pp && mg.adv != side) return ChlPp.Unknown
            if (mg.goal.sh && (mg.adv == null || mg.adv == side)) return ChlPp.Unknown
        }
        // H9 (S1): an unpaired unflagged minor or major against a counted row at the same stoppage.
        if (model.hidden(nowG)) return ChlPp.Unknown

        if (st.away == st.home) return ChlPp.None
        val side = if (st.away > st.home) Side.HOME else Side.AWAY
        val kind = if (kotlin.math.abs(st.away - st.home) >= 2) PowerPlayKind.TWO_MAN else PowerPlayKind.ONE_MAN
        val shortEnds = if (side == Side.HOME) st.awayEnds else st.homeEnds
        val secs = (shortEnds.minOrNull() ?: return ChlPp.Unknown) - nowG
        // H4: seconds out of range, or a serving row longer than its penalty. (Fix pass L6) The second clause cannot fire
        // today (a row's end is at most start + its duration, and TimeOn only caps it); it stays as a guard.
        if (secs <= 0 || secs > SportsConstants.PP_SECS_MAX) return ChlPp.Unknown
        if (model.servingRows().any { it.e - it.start > 60 * it.minutes }) return ChlPp.Unknown
        return ChlPp.Shown(PowerPlay(side, kind, secs))
    }

    // ------------------------------------------------------------------ §5.2 game time

    /** Period lengths 1..[maxPeriod] (null = unknown) and `g(period, elapsed)`. */
    internal class GameClockMap private constructor(private val lengths: Array<Int?>) {
        fun length(period: Int): Int? = lengths.getOrNull(period)

        /** `Σ_{q < period} L(q) + elapsed`; null when a length before [period] is unknown. */
        fun g(period: Int, elapsed: Int): Int? {
            if (period < 1 || period >= lengths.size + 1) return null
            var sum = 0
            for (q in 1 until period) sum += lengths.getOrNull(q) ?: return null
            return sum + elapsed
        }

        companion object {
            fun of(periodLength: (Int) -> Int?, maxPeriod: Int): GameClockMap {
                val n = maxPeriod.coerceIn(1, MAX_PERIODS)
                return GameClockMap(Array(n + 1) { q -> if (q == 0) null else periodLength(q)?.takeIf { it > 0 } })
            }

            /** The reference script's fixed lengths: 1200 s, every overtime 300 s (regular season). */
            fun regularSeason(maxPeriod: Int = MAX_PERIODS): GameClockMap = of(
                { q -> if (q <= 3) SportsConstants.CHL_REG_PERIOD_S else SportsConstants.CHL_OT_REG_S }, maxPeriod,
            )
        }
    }

    /** No game has more periods than this (5 overtimes past regulation, and some slack). */
    private const val MAX_PERIODS = 12

    /** (WP-1 fix pass L3) Above these the node is not a real game's (≤ ~40 rows, ≤ ~15 goals): Unknown. */
    internal const val MAX_COUNTED_ROWS = 80
    internal const val MAX_GOALS = 80

    // ------------------------------------------------------------------ §5.3 steps 1–6: counted rows

    /** A chain key (B2): the same side, period, call time and player; a null player (bench minor) never chains. */
    private data class ChainKey(val home: Boolean, val period: Int, val timeS: Int?, val playerId: Long)
    private data class SoloKey(val id: Long)

    /** One 2-, 4- (as two links) or 5-minute seat request. Mutable while a [Model] runs. */
    internal class Row(
        val home: Boolean,
        /**
         * Game time of the recorded start (§5.3 step 2). (WP-1 fix pass S1) For a later link of a chain it only orders the
         * links: that link starts at its predecessor's end, whatever its own `TimeStart` says.
         */
        val s: Int,
        val dur: Int,
        val minor: Boolean,
        val key: Any,
        val id: Long,
        val link: Int,
        /** `TimeOn` (elapsed in the row's period or the next), when used. */
        val on: Int?,
        val period: Int,
        /** The row's `Minutes` (4 for both links of a double minor). */
        val minutes: Int,
    ) {
        /** The first link of its chain in [ORDER] (H3 and the start time apply to it only). */
        var firstLink: Boolean = true
        var start: Int = -1
        var e: Int = -1
        val started: Boolean get() = start >= 0

        internal fun before(o: Row): Boolean = ORDER.compare(this, o) < 0
    }

    /** (§5.3 step 3; WP-1 fix pass S2) Rows by start, then a major before a minor (one player's major is served first), then id, link. */
    internal val ORDER: Comparator<Row> = compareBy<Row>({ it.s }, { it.minor }, { it.id }, { it.link })

    internal class Rows(val rows: List<Row>, val unplaceable: Boolean)

    /** (§5.3 steps 1–3) The counted rows in [ORDER]; [Rows.unplaceable] = H2 for a counted row. */
    internal fun buildRows(pens: List<ChlPenalty>, clock: GameClockMap, useTimeOn: Boolean, feedPeriod: Int): Rows {
        val out = ArrayList<Row>()
        var unplaceable = false
        for (p in pens) {
            if (!p.ppFlag || p.penaltyShot) continue
            val minutes = p.minutes
            if (minutes == null) { unplaceable = true; continue }
            if (minutes != 2 && minutes != 4 && minutes != 5) continue
            if (p.period < 1 || p.period > feedPeriod || p.timeS == null) { unplaceable = true; continue }
            var st = p.startS
            val len = clock.length(p.period)
            // (Fix pass L2) A call or start past the period's end cannot be placed.
            if (len != null && (p.timeS > len || (st != null && st > len))) { unplaceable = true; continue }
            // S2: a recorded end not after the start, for a penalty that would end inside its period: start at the call.
            if (st != null && p.onS != null && p.onS <= st && len != null && st + 60 * minutes <= len) st = null
            val start = st ?: p.timeS
            val s = clock.g(p.period, start)
            if (s == null) { unplaceable = true; continue }
            val key: Any = if (p.playerId != null) ChainKey(p.home, p.period, p.timeS, p.playerId) else SoloKey(p.id)
            val links = if (minutes == 4) listOf(2, 2) else listOf(minutes)
            links.forEachIndexed { i, m ->
                out += Row(p.home, s, m * 60, m == 2, key, p.id, i, if (useTimeOn) p.onS else null, p.period, minutes)
            }
        }
        out.sortWith(ORDER)
        val seen = HashSet<Any>()
        for (r in out) r.firstLink = seen.add(r.key)
        return Rows(out, unplaceable)
    }

    // ------------------------------------------------------------------ H9 (S1)

    /** `[start, end)` game-time windows in which the bar is hidden (§5.6 H9). */
    internal fun h9Windows(pens: List<ChlPenalty>, clock: GameClockMap): List<IntArray> {
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<ChlPenalty>>()
        for (p in pens) {
            if (p.period < 1 || p.penaltyShot) continue
            val t = p.timeS ?: continue
            groups.getOrPut(p.period to t) { ArrayList() } += p
        }
        val out = ArrayList<IntArray>()
        for ((k, ps) in groups) {
            val counted = ps.filter { it.ppFlag && (it.minutes == 2 || it.minutes == 4 || it.minutes == 5) }
            if (counted.isEmpty()) continue
            val unflagged = ps.filter { !it.ppFlag && (it.minutes == 2 || it.minutes == 5) }
            val left = ArrayList<ChlPenalty>()
            for (m in intArrayOf(2, 5)) {
                val hs = unflagged.filter { it.minutes == m && it.home }
                val aw = unflagged.filter { it.minutes == m && !it.home }
                val n = minOf(hs.size, aw.size)
                left += hs.drop(n)
                left += aw.drop(n)
            }
            val s = clock.g(k.first, k.second) ?: continue
            for (u in left) {
                if (counted.any { it.home != u.home }) out += intArrayOf(s, s + 60 * (u.minutes ?: 0))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ §5.3 steps 4–9: the seats

    internal class ModelGoal(val goal: ChlGoal, val t: Int) {
        /** The advantaged side just before this goal (before its release and the expiries at [t]); null = even. */
        var adv: Side? = null
        var done: Boolean = false
    }

    /** The state after the last event at or before a second: serving counts and each serving row's seat end. */
    internal class State(val away: Int, val home: Int, val awayEnds: List<Int>, val homeEnds: List<Int>)

    private class Snap(val t: Int, val state: State)

    /**
     * The reference model of `tools/chl/pp_replay.py` (`Sim`), event to event. [rows] in `(s, id)` order (as
     * [buildRows] gives them); [goals] with their game times; [hides] the H9 windows.
     */
    internal class Model(
        val rows: List<Row>,
        goals: List<ModelGoal>,
        private val hides: List<IntArray>,
        private val clock: GameClockMap,
    ) {
        val goals: List<ModelGoal> = goals.sortedWith(compareBy<ModelGoal>({ it.t }, { it.goal.id }))
        private val pending = ArrayList(rows)
        private val servingA = ArrayList<Row>()
        private val servingH = ArrayList<Row>()
        private val busy = HashMap<Any, Int>()
        private var gi = 0
        private val snaps = ArrayList<Snap>()
        private var at = -1

        fun servingRows(): List<Row> = servingA + servingH

        fun hidden(t: Int): Boolean = hides.any { t >= it[0] && t < it[1] }

        /** Runs every event up to and including game second [until]. */
        fun run(until: Int) {
            var t = nextEvent(at) ?: return
            while (t <= until) {
                step(t)
                at = t
                t = nextEvent(t) ?: return
            }
        }

        /** The state at second [t] (after its events), for any [t] up to the last [run]. */
        fun stateAt(t: Int): State {
            var lo = 0
            var hi = snaps.size - 1
            var best: Snap? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (snaps[mid].t <= t) { best = snaps[mid]; lo = mid + 1 } else hi = mid - 1
            }
            return best?.state ?: EMPTY
        }

        private fun nextEvent(after: Int): Int? {
            var n = Int.MAX_VALUE
            if (gi < goals.size) n = minOf(n, goals[gi].t)
            for (r in pending) if (r.s > after) n = minOf(n, r.s)
            for (r in servingA) n = minOf(n, r.e)
            for (r in servingH) n = minOf(n, r.e)
            if (n == Int.MAX_VALUE) return null
            return maxOf(n, after + 1)
        }

        private fun serving(home: Boolean) = if (home) servingH else servingA

        private fun step(t: Int) {
            // Step 7: goals first, before the expiries at t.
            while (gi < goals.size && goals[gi].t <= t) {
                val gl = goals[gi++]
                gl.done = true
                val a = servingA.size
                val h = servingH.size
                gl.adv = if (a == h) null else if (a > h) Side.HOME else Side.AWAY
                val scorer = serving(gl.goal.home)
                val opp = serving(!gl.goal.home)
                if (!gl.goal.penaltyShot && opp.size > scorer.size) {
                    opp.filter { it.minor }.minWithOrNull(compareBy<Row>({ it.start }, { it.id }, { it.link }))?.e = t
                }
            }
            // Expiry is half-open.
            for (list in arrayOf(servingA, servingH)) {
                val it = list.iterator()
                while (it.hasNext()) {
                    val r = it.next()
                    if (r.e <= t) {
                        it.remove()
                        busy[r.key] = (busy[r.key] ?: 0) - 1
                    }
                }
            }
            // Steps 3–6: start every row that can (its chain's earlier links done, a seat free), in (s, id) order.
            var progressed = true
            while (progressed) {
                progressed = false
                for (r in pending) {
                    // (Fix pass S1) Only a chain's first link waits for its own start; a later one follows its predecessor.
                    if ((r.firstLink && r.s > t) || serving(r.home).size >= 2 || (busy[r.key] ?: 0) != 0) continue
                    val prevOk = rows.all { x -> x.key != r.key || !x.before(r) || (x.started && x.e <= t) }
                    if (!prevOk) continue
                    r.start = t
                    var e = t + r.dur
                    if (r.on != null) {
                        for (pp in intArrayOf(r.period, r.period + 1)) {
                            val x = clock.g(pp, r.on) ?: continue
                            if (t < x && x <= e) { e = x; break }
                        }
                    }
                    r.e = e
                    serving(r.home) += r
                    busy[r.key] = (busy[r.key] ?: 0) + 1
                    pending.remove(r)
                    progressed = true
                    break
                }
            }
            snaps += Snap(t, State(servingA.size, servingH.size, servingA.map { seatEnd(it) }, servingH.map { seatEnd(it) }))
        }

        /**
         * (§5.3 step 9) The seat's end as computed now: the serving row's end, extended by every row that follows on in
         * the same seat with no gap (the next link of its chain, else a waiting row of the side while the other seat is
         * still busy).
         */
        private fun seatEnd(r: Row): Int {
            var end = r.e
            var key = r.key
            val others = serving(r.home).filter { it !== r }.map { it.e }
            val minOther = others.minOrNull()
            val queue = pending.filter { it.home == r.home } // already in ORDER
            val used = HashSet<Row>()
            while (true) {
                var nxt = queue.firstOrNull { it !in used && it.key == key && (it.s <= end || !it.firstLink) }
                if (nxt == null && minOther != null && minOther > end) {
                    nxt = queue.firstOrNull { x ->
                        x !in used && x.key != key && x.s <= end &&
                            queue.all { y -> y.key != x.key || y in used || !y.before(x) }
                    }
                }
                if (nxt == null) return end
                used += nxt
                key = nxt.key
                end += nxt.dur
            }
        }

        private companion object {
            val EMPTY = State(0, 0, emptyList(), emptyList())
        }
    }
}
