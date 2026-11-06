package app.thebox.core.sports

import app.thebox.core.playback.MonoClock
import kotlin.random.Random

/**
 * The followed game's pure machine (docs/design/m6-score-bug.md §2.3–§2.7; WP-1). Single-threaded: the repository's
 * actor serialises every input (A-S8). [mono] is the one time base (`elapsedRealtime`, A-N7); [wall] is device wall
 * time, corrected by the server `Date` skew for expiry (A-N4). [pollPhaseMs] is `SportsFollowDoc.pollPhaseMs` (drawn
 * once per install); [random] draws the back-off jitter.
 *
 * How the rules land (WP-1 reading of the design):
 * - **Delayed timeline.** Each `Ok` enters the queue at `anchor = received − Age`; it is shown from
 *   `max(anchor + delay, received)`. Goals, "Final", the final's expiry, stale/hide and the extrapolation cap all run
 *   on that timeline (stale, hide and the cap are measured from `lastReceived + delay`).
 * - **Polls** follow the latest *received* snapshot (not the shown one): live = next bucket end + phase; intermission
 *   and pre-game skip one bucket; pre-game first sleeps until `start − 2 min`; `UNSEEN` adds a floor of
 *   `received + 120 s`; FINAL and POSTPONED never poll. A follow, and a rise to `SEEN` whose last `Ok` is older than
 *   20 s, poll at once (the latter suppresses "No update" until the answer).
 * - **Removal:** POSTPONED (event), two NotFounds ≥ 20 s apart (`NOT_LISTED` event). Expiry (final + 5 min, start +
 *   8 h) is silent; `RemoveReason` has no value for the hard expiry, so it is silent too.
 * - `FollowedFinal` / `Followed` are raised by the repository (it has the snapshot and the first-follow flag), not here.
 * - `goalSide` is non-null for the 8 s after a goal; the UI's 3 s box keys on `goalSeq`. The bottom line reads
 *   "GOAL" then, except in the one-line states (Final, SO), which stay one line.
 */
class ScoreBugMachine(
    private val mono: MonoClock,
    private val wall: () -> Long,
    private val pollPhaseMs: Long = SportsConstants.POLL_PHASE_MIN_MS,
    private val random: Random = Random.Default,
) {
    private class Entry(val snap: GameSnapshot, val anchorMono: Long, val receivedMono: Long)
    private class Hold(val secs: Int, val atMono: Long, val untilMono: Long)

    private var game: FollowedGame? = null
    private var delayMs = 0L
    private var demand = Demand.NONE
    private var online = true

    private val queue = ArrayList<Entry>() // ascending anchorMono
    private var shown: Entry? = null
    private var lastOk: Fetch.Ok<GameSnapshot>? = null
    /** The `Ok` before [lastOk]: the cap the shown clock had until the newest answer arrived. */
    private var prevOkReceived: Long? = null
    private var skewMs = 0L

    private var followAt = 0L
    private var eagerAt: Long? = null
    private var suppressStale = false

    private var failures = 0
    private var failAt: Long? = null
    private var backoffAt: Long? = null
    private var notFoundFirst: Long? = null

    private var hold: Hold? = null
    private var goalSeq = 0
    private var goalSide: Side? = null
    private var goalUntil: Long? = null

    private val events = ArrayList<ScoreEvent>()

    // ------------------------------------------------------------------ inputs

    fun follow(game: FollowedGame?) {
        val now = mono.nowMs()
        val cur = this.game
        if (game != null && cur != null && game.id == cur.id) {
            if (cur.finalSeenUtcMs == null && game.finalSeenUtcMs != null) this.game = cur.copy(finalSeenUtcMs = game.finalSeenUtcMs)
            step()
            return
        }
        reset()
        this.game = game
        if (game != null) {
            followAt = now
            eagerAt = now
            step()
        }
    }

    fun prefs(delaySec: Int) {
        delayMs = delaySec.coerceIn(0, SportsConstants.DELAY_STEPS_S.last()) * 1000L
        step()
    }

    fun demand(d: Demand) {
        val now = mono.nowMs()
        val was = demand
        demand = d
        if (d == Demand.SEEN && was != Demand.SEEN && game != null) {
            val ok = lastOk
            val old = ok == null || now - ok.receivedMono > SportsConstants.CDN_BUCKET_MS
            if (old && pollsAtAll(now)) {
                eagerAt = now
                if (ok != null) suppressStale = true
            }
        }
        step()
    }

    /** (Commit 0 addition) A default network exists (§2.4). Offline: nothing is due, but stale and hide still advance. */
    fun online(on: Boolean) {
        online = on
        step()
    }

    fun fetched(f: Fetch<GameSnapshot>) {
        val now = mono.nowMs()
        val g = game ?: return
        if (f is Fetch.Ok && f.value.id != g.id) return // an answer for a previous follow
        eagerAt = null
        suppressStale = false
        when (f) {
            is Fetch.Ok -> {
                failures = 0; failAt = null; backoffAt = null; notFoundFirst = null
                prevOkReceived = lastOk?.receivedMono
                lastOk = f
                f.serverDateMs?.let { date -> skewMs = date - (wall() - (now - f.receivedMono)) }
                if (f.value.startUtcMs != g.startUtcMs) game = g.copy(startUtcMs = f.value.startUtcMs)
                if (f.value.phase == GamePhase.POSTPONED) {
                    remove(RemoveReason.POSTPONED)
                    return
                }
                val age = f.ageS.coerceIn(0, SportsConstants.AGE_MAX_S) * 1000L
                val e = Entry(f.value, f.receivedMono - age, f.receivedMono)
                var i = queue.size
                while (i > 0 && queue[i - 1].anchorMono > e.anchorMono) i--
                queue.add(i, e)
            }
            is Fetch.NotFound -> {
                val first = notFoundFirst
                if (first != null && now - first >= SportsConstants.NOT_FOUND_CONFIRM_MS) {
                    remove(RemoveReason.NOT_LISTED)
                    return
                }
                if (first == null) notFoundFirst = now
                failures++
                failAt = now
                backoffAt = maxOf(
                    SportsTiming.backoffAt(now, failures, null, jitter()),
                    notFoundFirst!! + SportsConstants.NOT_FOUND_CONFIRM_MS,
                )
            }
            is Fetch.Failed -> {
                failures++
                failAt = f.receivedMono
                backoffAt = SportsTiming.backoffAt(f.receivedMono, failures, f.retryAfterS, jitter())
            }
        }
        step()
    }

    /** Advance to `mono.nowMs()`: expiry, stale, hide, the delay queue, the clock hold (A-B1). */
    fun step() {
        val now = mono.nowMs()
        if (game == null) return
        advanceDisplay(now)
        if (expired()) {
            reset()
            game = null
            return
        }
        prune(now)
        hold?.let { if (now >= it.untilMono) hold = null }
        goalUntil?.let { if (now >= it) { goalUntil = null; goalSide = null } }
    }

    fun pollDue(): Boolean {
        val now = mono.nowMs()
        val at = pollAt(now) ?: return false
        return now >= at
    }

    /** min(next poll, next snapshot due, stale, hide, cap instant, expiry, hold end, goal-text end); null = idle (A-B1). */
    fun nextWakeAtMono(): Long? {
        val g = game ?: return null
        if (demand == Demand.NONE) return null
        val now = mono.nowMs()
        var best: Long? = pollAt(now)
        fun later(t: Long?) {
            if (t != null && t > now && (best == null || t < best!!)) best = t
        }
        later(queue.firstOrNull { it.anchorMono + delayMs > now }?.let { it.anchorMono + delayMs })
        val staleAt = staleAt()
        later(staleAt)
        later(staleAt?.plus(SportsConstants.HIDE_AFTER_MS))
        shown?.let { naturalAnchor(it) }?.stopAtMono?.let(::later)
        g.finalSeenUtcMs?.let { later(monoAtWall(now, it + SportsConstants.EXPIRE_AFTER_FINAL_MS)) }
        later(monoAtWall(now, g.startUtcMs + SportsConstants.HARD_EXPIRY_MS))
        later(hold?.untilMono)
        later(goalUntil)
        return best
    }

    /** Null before LIVE (owner ruling 2026-11-05) and while hidden. */
    fun view(): ScoreBugView? {
        game ?: return null
        val now = mono.nowMs()
        val waiting = shown == null
        val e = shown ?: queue.firstOrNull() ?: return null
        val s = e.snap
        if (s.phase == GamePhase.SCHEDULED || s.phase == GamePhase.PRE || s.phase == GamePhase.POSTPONED) return null
        val staleAt = staleAt()
        val stale = !waiting && staleAt != null && now >= staleAt
        if (stale && now >= staleAt!! + SportsConstants.HIDE_AFTER_MS) return null

        val label = label(s)
        var top: CentreText = CentreText.Clock
        var bottom: CentreText? = when {
            s.phase == GamePhase.FINAL -> { top = CentreText.Final(s.finalKind ?: PeriodKind.REG); null }
            s.periodKind == PeriodKind.SO -> { top = CentreText.Shootout; null }
            s.phase == GamePhase.INTERMISSION -> CentreText.Intermission(label)
            s.clock?.let { it.secondsRemaining == 0 && !it.running && !it.inIntermission } == true -> CentreText.EndOf(label)
            else -> CentreText.Period(label)
        }
        val goal = goalSide.takeIf { goalUntil?.let { now < it } == true }
        when {
            waiting -> bottom = CentreText.Delayed
            stale -> bottom = CentreText.NoUpdate
            // One-line states (Final, SO) stay one line; the goal box still runs off goalSeq/goalSide.
            goal != null && bottom != null -> bottom = CentreText.Goal(goal)
        }
        return ScoreBugView(s.away, s.home, top, bottom, dim = waiting || stale, goalSeq = goalSeq, goalSide = goal)
    }

    fun anchor(): ClockAnchor? {
        view() ?: return null
        val now = mono.nowMs()
        val e = shown
        if (e == null) {
            val first = queue.firstOrNull() ?: return null
            val c = first.snap.clock?.takeIf { clockShown(first.snap) } ?: return null
            return ClockAnchor(c.secondsRemaining, first.anchorMono, ticking = false, stopAtMono = null)
        }
        val staleAt = staleAt()
        if (staleAt != null && now >= staleAt) {
            val a = liveAnchor(e, staleAt) ?: return null
            return ClockAnchor(a.secsAt(staleAt), staleAt, ticking = false, stopAtMono = null)
        }
        return liveAnchor(e, now)
    }

    /**
     * (Commit 0 addition) The followed game as the machine now knows it (start rewritten from a snapshot,
     * `finalSeenUtcMs` set), for the repository to persist; null once removed or expired.
     */
    fun followed(): FollowedGame? = game

    fun drainEvents(): List<ScoreEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    // ------------------------------------------------------------------ polling

    private fun pollAt(now: Long): Long? {
        game ?: return null
        if (demand == Demand.NONE || !online) return null
        eagerAt?.let { return it }
        val ok = lastOk
        if (failures > 0) {
            var t = backoffAt ?: now
            if (demand == Demand.UNSEEN) failAt?.let { t = maxOf(t, it + SportsConstants.POLL_UNSEEN_MS) }
            if (ok != null && isPreGame(ok.value.phase)) t = maxOf(t, preWakeMono(now))
            return t
        }
        ok ?: return followAt
        val floor = if (demand == Demand.UNSEEN) ok.receivedMono + SportsConstants.POLL_UNSEEN_MS else Long.MIN_VALUE
        return when (ok.value.phase) {
            GamePhase.FINAL, GamePhase.POSTPONED -> null
            GamePhase.SCHEDULED, GamePhase.PRE ->
                SportsTiming.nextAligned(ok, pollPhaseMs, SportsConstants.INTERMISSION_BUCKET_SKIP, maxOf(floor, preWakeMono(now)))
            GamePhase.LIVE -> SportsTiming.nextAligned(ok, pollPhaseMs, 0, floor)
            GamePhase.INTERMISSION ->
                SportsTiming.nextAligned(ok, pollPhaseMs, SportsConstants.INTERMISSION_BUCKET_SKIP, floor)
        }
    }

    /** Whether a rise to SEEN may poll now: not for a finished game, nor for a pre-game one still asleep. */
    private fun pollsAtAll(now: Long): Boolean {
        val phase = lastOk?.value?.phase ?: return true
        return when (phase) {
            GamePhase.FINAL, GamePhase.POSTPONED -> false
            GamePhase.SCHEDULED, GamePhase.PRE -> now >= preWakeMono(now)
            else -> true
        }
    }

    private fun isPreGame(p: GamePhase) = p == GamePhase.SCHEDULED || p == GamePhase.PRE

    private fun preWakeMono(now: Long): Long =
        monoAtWall(now, (game?.startUtcMs ?: 0L) - SportsConstants.PRE_WAKE_MS)

    private fun correctedWall(): Long = wall() + skewMs

    /** The `MonoClock` instant at which the corrected wall clock reads [utcMs]. */
    private fun monoAtWall(now: Long, utcMs: Long): Long = now + (utcMs - correctedWall())

    private fun jitter(): Double = random.nextDouble(-1.0, 1.0)

    // ------------------------------------------------------------------ the delayed timeline

    private fun advanceDisplay(now: Long) {
        val q = queue.lastOrNull { it.anchorMono + delayMs <= now }
        val prev = shown
        if (q == null) {
            if (prev != null) { shown = null; hold = null }
            return
        }
        if (q === prev) return
        if (prev != null && q.anchorMono < prev.anchorMono) {
            // The delay grew: back in time, silently, the clock snaps.
            shown = q
            hold = null
            return
        }
        val at = maxOf(q.anchorMono + delayMs, q.receivedMono)
        val before = prev?.let { liveAnchor(it, at, prevOkReceived) }
        shown = q
        val s = q.snap
        val p = prev?.snap

        if (p != null && demand == Demand.SEEN) {
            val side = when {
                rose(p.home.score, s.home.score) -> Side.HOME
                rose(p.away.score, s.away.score) -> Side.AWAY
                else -> null
            }
            if (side != null) {
                goalSeq++
                goalSide = side
                goalUntil = at + SportsConstants.GOAL_TEXT_MS
            }
        }

        val g = game
        if (s.phase == GamePhase.FINAL && g != null && g.finalSeenUtcMs == null) {
            game = g.copy(finalSeenUtcMs = correctedWall() - (now - at))
        }

        hold = null
        val next = naturalAnchor(q)
        if (p != null && before != null && next != null && sameRun(p, s)) {
            val d0 = before.secsAt(at)
            if (next.secsAt(at) > d0) {
                val reachAt = next.atMono + (next.secs - d0) * 1000L
                val reaches = next.ticking && (next.stopAtMono == null || reachAt <= next.stopAtMono)
                val max = at + SportsConstants.CLOCK_HOLD_MAX_S * 1000L
                hold = Hold(d0, at, if (reaches) minOf(reachAt, max) else max)
            }
        }
    }

    private fun rose(was: Int?, now: Int?) = was != null && now != null && now > was

    private fun sameRun(a: GameSnapshot, b: GameSnapshot): Boolean =
        a.phase == b.phase && a.period == b.period && a.periodKind == b.periodKind &&
            a.clock?.running == b.clock?.running && a.clock?.inIntermission == b.clock?.inIntermission

    private fun prune(now: Long) {
        val keep = shown
        queue.removeAll { it !== keep && it.anchorMono < now - SportsConstants.QUEUE_KEEP_MS && (keep == null || it.anchorMono <= keep.anchorMono) }
    }

    private fun clockShown(s: GameSnapshot): Boolean =
        (s.phase == GamePhase.LIVE || s.phase == GamePhase.INTERMISSION) && s.periodKind != PeriodKind.SO && s.clock != null

    /** The snapshot's own anchor on the delayed timeline, capped 40 s after the last response in play (A-S3). */
    private fun naturalAnchor(e: Entry, capFrom: Long? = lastOk?.receivedMono): ClockAnchor? {
        val s = e.snap
        if (!clockShown(s)) return null
        val c = s.clock!!
        val at = e.anchorMono + delayMs
        val ticking = c.running || c.inIntermission
        if (!ticking) return ClockAnchor(c.secondsRemaining, at, ticking = false, stopAtMono = null)
        val stop = if (c.inIntermission) null else (capFrom ?: e.receivedMono) + delayMs + SportsConstants.EXTRAPOLATE_CAP_S * 1000L
        return ClockAnchor(c.secondsRemaining, at, ticking = true, stopAtMono = stop)
    }

    /** The shown anchor at [t] ignoring staleness: the hold's frozen value while it lasts, else the natural one. */
    private fun liveAnchor(e: Entry, t: Long, capFrom: Long? = lastOk?.receivedMono): ClockAnchor? {
        val h = hold
        if (h != null && t < h.untilMono && e === shown) return ClockAnchor(h.secs, h.atMono, ticking = false, stopAtMono = null)
        return naturalAnchor(e, capFrom)
    }

    /** Stale from `lastReceived + delay + max(60 s, 2 × cadence)` while the latest snapshot is live (§2.4, A-S6). */
    private fun staleAt(): Long? {
        if (suppressStale) return null
        val ok = lastOk ?: return null
        val cadence = when (ok.value.phase) {
            GamePhase.LIVE -> SportsConstants.CDN_BUCKET_MS
            GamePhase.INTERMISSION -> SportsConstants.CDN_BUCKET_MS * (1 + SportsConstants.INTERMISSION_BUCKET_SKIP)
            else -> return null
        }
        val c = if (demand == Demand.SEEN) cadence else maxOf(cadence, SportsConstants.POLL_UNSEEN_MS)
        return ok.receivedMono + delayMs + maxOf(SportsConstants.STALE_AFTER_MS, 2 * c)
    }

    private fun label(s: GameSnapshot): PeriodLabel = when (s.periodKind) {
        PeriodKind.OT -> PeriodLabel(PeriodKind.OT, s.otNumber ?: 1)
        PeriodKind.SO -> PeriodLabel(PeriodKind.SO, 1)
        else -> PeriodLabel(PeriodKind.REG, s.period ?: 1)
    }

    // ------------------------------------------------------------------ removal

    private fun expired(): Boolean {
        val g = game ?: return false
        val w = correctedWall()
        if (g.finalSeenUtcMs != null && w >= g.finalSeenUtcMs + SportsConstants.EXPIRE_AFTER_FINAL_MS) return true
        return w >= g.startUtcMs + SportsConstants.HARD_EXPIRY_MS
    }

    private fun remove(why: RemoveReason) {
        val g = game ?: return
        events += ScoreEvent.Removed(why, g)
        reset()
        game = null
    }

    private fun reset() {
        queue.clear(); shown = null; lastOk = null; prevOkReceived = null
        eagerAt = null; suppressStale = false
        failures = 0; failAt = null; backoffAt = null; notFoundFirst = null
        hold = null; goalSeq = 0; goalSide = null; goalUntil = null
    }
}

/**
 * The displayed whole seconds of this anchor at [nowMono] (§2.5): counts down 1/s from [ClockAnchor.atMono] while
 * ticking, stops at [ClockAnchor.stopAtMono], floored at 0. The UI's per-second tick reads the same function.
 */
fun ClockAnchor.secsAt(nowMono: Long): Int {
    if (!ticking || nowMono <= atMono) return secs
    val t = stopAtMono?.let { minOf(it, nowMono) } ?: nowMono
    if (t <= atMono) return secs
    return (secs - (t - atMono) / 1000L).coerceAtLeast(0L).toInt()
}
