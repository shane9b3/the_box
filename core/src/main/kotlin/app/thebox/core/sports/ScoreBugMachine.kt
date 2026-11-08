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
 *   `max(anchor + delay, received)`. Goals, "Final", the final's expiry and stale/hide all run on that timeline (stale
 *   and hide are measured from `lastReceived + delay`).
 * - **Clock** (owner ruling 6, 2026-11-05): the shown clock is exactly the shown snapshot's `secondsRemaining`
 *   ([ScoreBugView.clockSecs]); it changes only when a new snapshot becomes visible. No extrapolation, no cap, no hold.
 * - **Polls** follow the latest *received* snapshot (not the shown one): live = next bucket end + phase; intermission
 *   and pre-game skip one bucket; pre-game first sleeps until `start − 2 min`; `UNSEEN` adds a floor of
 *   `received + 120 s`; FINAL and POSTPONED never poll. A follow, and a rise to `SEEN` whose last `Ok` is older than
 *   20 s, poll at once (the latter suppresses "No update" until the answer). A rise never polls while offline (C-S2) or
 *   while a back-off / `Retry-After` is running (C-S3).
 * - **Removal:** POSTPONED (event), two NotFounds ≥ 20 s apart (`NOT_LISTED` event). Expiry (final + 5 min, start +
 *   8 h) is silent; `RemoveReason` has no value for the hard expiry, so it is silent too.
 * - `FollowedFinal` / `Followed` are raised by the repository (it has the snapshot and the first-follow flag), not here.
 * - `goalSide` is the goal mark: non-null for `GOAL_MARK_MS` (4.5 s) after a goal becomes visible on the delayed
 *   timeline; the UI's takeover keys on `goalSeq` (docs/design/m6-goal-animation.md §5.1). The bottom line is never
 *   replaced.
 * - **Power play** (docs/design/m6-power-play.md §1.4): `view().powerPlay` is the shown snapshot's while LIVE or
 *   INTERMISSION, not SO, neither stale nor waiting (`knownView()` never has one). `goalPp` is decided where a goal is
 *   flagged and lives with the mark; `ppEnded` belongs to the shown snapshot. Polling, stale, hide and the delay queue
 *   do not look at the power play.
 */
class ScoreBugMachine(
    private val mono: MonoClock,
    private val wall: () -> Long,
    private val pollPhaseMs: Long = SportsConstants.POLL_PHASE_MIN_MS,
    private val random: Random = Random.Default,
) {
    private class Entry(val snap: GameSnapshot, val anchorMono: Long, val receivedMono: Long)

    private var game: FollowedGame? = null
    private var delayMs = 0L
    private var demand = Demand.NONE
    private var online = true

    private val queue = ArrayList<Entry>() // ascending anchorMono
    private var shown: Entry? = null
    private var lastOk: Fetch.Ok<GameSnapshot>? = null
    private var skewMs = 0L

    private var followAt = 0L
    private var eagerAt: Long? = null
    private var suppressStale = false

    private var failures = 0
    private var failAt: Long? = null
    private var backoffAt: Long? = null
    private var notFoundFirst: Long? = null

    /** Bumped once per goal; never reset, not even by a re-follow ([reset]), so the UI can key on it (B1.4). */
    private var goalSeq = 0
    private var goalSide: Side? = null
    private var markUntil: Long? = null
    /** (Power play §1.4, N12) The flagged goal was a power-play goal; set and cleared with the mark. */
    private var goalPp = false

    /** (Power play §1.4) The gated power play of the last snapshot shown going forward (survives a stale stretch). */
    private var lastShownPp: PowerPlay? = null
    /** (Power play §1.4, §7) The shown entry whose view carries `ppEnded` (a real end, with no goal flagged by it). */
    private var ppEndedEntry: Entry? = null

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
            // C-S2: no rise poll (and so no "No update" suppression) while offline. C-S3: never ahead of a running
            // back-off or Retry-After.
            val backingOff = failures > 0 && (backoffAt ?: Long.MIN_VALUE) > now
            if (old && online && !backingOff && pollsAtAll(now)) {
                eagerAt = now
                if (ok != null) suppressStale = true
            }
        }
        step()
    }

    /** (Commit 0 addition) A default network exists (§2.4). Offline: nothing is due, but stale and hide still advance. */
    fun online(on: Boolean) {
        online = on
        if (!on) suppressStale = false // C-S2: no poll can answer, so stale and hide must run
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

    /** Advance to `mono.nowMs()`: expiry, stale, hide, the delay queue (A-B1). */
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
        markUntil?.let { if (now >= it) { markUntil = null; goalSide = null; goalPp = false } }
    }

    fun pollDue(): Boolean {
        val now = mono.nowMs()
        val at = pollAt(now) ?: return false
        return now >= at
    }

    /**
     * min(next poll, next snapshot due, stale, hide, expiry, goal-mark end); null = idle (A-B1). Nothing is scheduled for
     * the clock: it changes only with a snapshot (owner ruling 6).
     */
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
        g.finalSeenUtcMs?.let { later(monoAtWall(now, it + SportsConstants.EXPIRE_AFTER_FINAL_MS)) }
        later(monoAtWall(now, g.startUtcMs + SportsConstants.HARD_EXPIRY_MS))
        later(markUntil)
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
            s.clock?.let { it.secondsRemaining <= 0 && !it.running && !it.inIntermission } == true -> CentreText.EndOf(label)
            else -> CentreText.Period(label)
        }
        val goal = goalSide.takeIf { markUntil?.let { now < it } == true }
        when {
            waiting -> bottom = CentreText.Delayed
            stale -> bottom = CentreText.NoUpdate
        }
        // Owner ruling 6: the clock is the snapshot's own value, whatever the state (live, stopped, intermission,
        // stale, waiting on the delay).
        val clockSecs = if (top == CentreText.Clock) s.clock?.takeIf { clockShown(s) }?.secondsRemaining?.coerceAtLeast(0) else null
        val fresh = !waiting && !stale
        return ScoreBugView(
            s.away, s.home, top, bottom, dim = waiting || stale, goalSeq = goalSeq, goalSide = goal, clockSecs = clockSecs,
            gameId = game?.id,
            powerPlay = if (fresh) gatedPp(s) else null,
            goalPp = goal != null && goalPp,
            ppEnded = fresh && e === ppEndedEntry,
        )
    }

    /**
     * (Fix pass, U-S4) The bug's view with the stale-hide ignored: the last known score while the bug is hidden for want
     * of updates. Null before LIVE, like [view].
     */
    fun knownView(): ScoreBugView? {
        view()?.let { return it }
        game ?: return null
        val e = shown ?: return null
        val s = e.snap
        if (s.phase != GamePhase.LIVE && s.phase != GamePhase.INTERMISSION && s.phase != GamePhase.FINAL) return null
        val clockSecs = s.clock?.takeIf { clockShown(s) }?.secondsRemaining?.coerceAtLeast(0)
        return ScoreBugView(s.away, s.home, CentreText.Clock, CentreText.NoUpdate, dim = true, goalSeq = goalSeq, goalSide = null, clockSecs = clockSecs, gameId = game?.id)
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
            if (prev != null) shown = null
            return
        }
        if (q === prev) return
        if (prev != null && q.anchorMono < prev.anchorMono) {
            // The delay grew: back in time, silently.
            shown = q
            ppEndedEntry = null
            lastShownPp = gatedPp(q.snap)
            return
        }
        val at = maxOf(q.anchorMono + delayMs, q.receivedMono)
        shown = q
        val s = q.snap
        val p = prev?.snap
        var flagged = false

        // C-N1: a goal is flagged only against a recent comparison (not the hours-old snapshot shown before a long NONE
        // or UNSEEN stretch), and never when both sides rose meanwhile.
        if (p != null && demand == Demand.SEEN && q.receivedMono - prev.receivedMono <= GOAL_FRESH_MS) {
            val home = rose(p.home.score, s.home.score)
            val away = rose(p.away.score, s.away.score)
            val side = when {
                home && away -> null
                home -> Side.HOME
                away -> Side.AWAY
                else -> null
            }
            // Owner ruling 11: the shootout winner's +1 (usually on the FINAL snapshot, Final/SO) is a rise like any other
            // and plays the takeover. N9 (accepted miss): a tying goal and the shootout win inside one missed poll can
            // arrive as both sides rising, which is silent, as above.
            if (side != null) {
                goalSeq++
                goalSide = side
                markUntil = at + SportsConstants.GOAL_MARK_MS
                goalPp = goalWasPp(p, s, side)
                flagged = true
            }
        }

        // Power play §1.4, §7: a real end is "the previous fresh view had one, this one has none, and it flagged no goal".
        val pp = gatedPp(s)
        // It ended in play: the snapshot is LIVE or INTERMISSION and not the shootout (a FINAL/OFF or SO is not an "over").
        val inPlay = (s.phase == GamePhase.LIVE || s.phase == GamePhase.INTERMISSION) && s.periodKind != PeriodKind.SO
        // Fix pass N5: any score change counts as a goal here, flagged or not (UNSEEN demand, a long gap), so an unflagged
        // goal that ends the power play is never announced as "Power play over.".
        val scored = p != null && (p.away.score != s.away.score || p.home.score != s.home.score)
        ppEndedEntry = if (lastShownPp != null && pp == null && !flagged && !scored && inPlay) q else null
        lastShownPp = pp

        val g = game
        if (s.phase == GamePhase.FINAL && g != null && g.finalSeenUtcMs == null) {
            game = g.copy(finalSeenUtcMs = correctedWall() - (now - at))
        }
    }

    private fun rose(was: Int?, now: Int?) = was != null && now != null && now > was

    /** (Power play §1.4) The snapshot's power play when the phase can show one: LIVE or INTERMISSION, not the shootout. */
    private fun gatedPp(s: GameSnapshot): PowerPlay? =
        s.powerPlay.takeIf { (s.phase == GamePhase.LIVE || s.phase == GamePhase.INTERMISSION) && s.periodKind != PeriodKind.SO }

    /**
     * (Power play §1.4, S1; fix pass S4) Whether [side]'s goal, flagged going from [p] to [s], was a power-play goal. Only
     * when [p] is LIVE or INTERMISSION (a power play carried over the break, scored on early in the next period, is compared
     * against the intermission poll) and neither [p] nor [s] is the shootout (so never the shootout winner's +1).
     * 1. [GameSnapshot.lastGoal] has the scorer's side and equals [s]'s score: its `strength == "pp"`.
     * 2. Otherwise the summary lags, and the fallback is "[p] had [side] on the power play". Its known misses:
     *    - a minor that expires inside a poll gap, then an even-strength goal by the same team: a false PP GOAL;
     *    - a power play that starts and is scored on inside one gap: no PP GOAL;
     *    - `situation` clearing a bucket before the score rises: no PP GOAL.
     */
    private fun goalWasPp(p: GameSnapshot, s: GameSnapshot, side: Side): Boolean {
        if (p.phase != GamePhase.LIVE && p.phase != GamePhase.INTERMISSION) return false
        if (p.periodKind == PeriodKind.SO || s.periodKind == PeriodKind.SO || s.finalKind == PeriodKind.SO) return false
        val g = s.lastGoal
        if (g != null && g.side == side && g.away == s.away.score && g.home == s.home.score) return g.pp
        return p.powerPlay?.side == side
    }

    private fun prune(now: Long) {
        val keep = shown
        queue.removeAll { it !== keep && it.anchorMono < now - SportsConstants.QUEUE_KEEP_MS && (keep == null || it.anchorMono <= keep.anchorMono) }
    }

    private fun clockShown(s: GameSnapshot): Boolean =
        (s.phase == GamePhase.LIVE || s.phase == GamePhase.INTERMISSION) && s.periodKind != PeriodKind.SO && s.clock != null

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
        queue.clear(); shown = null; lastOk = null
        eagerAt = null; suppressStale = false
        failures = 0; failAt = null; backoffAt = null; notFoundFirst = null
        // goalSeq is monotonic for the machine's lifetime (goal animation §5.1, B1.4): a re-follow never reuses a seq
        // the UI has already seen.
        goalSide = null; markUntil = null; goalPp = false
        lastShownPp = null; ppEndedEntry = null
    }

    private companion object {
        /**
         * C-N1: a score rise is a "goal" only when the snapshot it is compared with was received at most this long before
         * (2 × the slowest SEEN cadence plus a failed poll's back-off).
         */
        const val GOAL_FRESH_MS = 2 * 40_000L + SportsConstants.BACKOFF_BASE_MS
    }
}
