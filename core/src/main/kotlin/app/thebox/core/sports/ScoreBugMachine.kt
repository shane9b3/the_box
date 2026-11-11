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

    /**
     * (Update interval §3.5) The followed game's live interval, ms; null = today's behaviour (every pre-interval test).
     * NHL: multiples of 20 s, bucket-aligned. NFL: from completion ([doneMono] + interval).
     */
    private var liveMs: Long? = null
    /** When the last `Ok` finished (after its decode, F15): the NFL's completion-based schedule runs from it. */
    private var doneMono = 0L
    /** (Fix pass 5) When the first of the current run of held older NFL bodies arrived; null = none held. */
    private var olderSince: Long? = null

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

    // ---- NFL (docs/design/m6-nfl.md §3.2–§3.6). Written only in advanceDisplay (and cleared by reset()), never in view().
    /** (§3.2, ruling 25) The flagged score's takeovers (one, or a +7/+8 pair); set and cleared with the mark. */
    private var nflScores: List<NflScore> = emptyList()
    /** (§3.3, B2) The side whose try is pending, and the (period, clock) of the snapshot that set it. */
    private var pendingTry: Side? = null
    private var tryAt: Pair<Int?, Int?>? = null
    /** (§3.3) The TD `lastPlay` whose try is over: a later `Dead` body still naming it never sets the try again. */
    private var tryDoneId: String? = null
    /** (§3.4) Inference memory: the kicker of the previously shown play when it was a `Kickoff`; the last shown `Down`. */
    private var lastKicker: Side? = null
    private var lastDown: NflPlay.Down? = null
    private var lastDownPoss: Side? = null
    /** (§3.4) The last shown tab in its plain form (never RED ZONE): held through a `Dead` with no try pending. */
    private var heldTab: NflTab? = null
    /** The rendering of [nflEntry] (§3.4), gated in [view]. [nflTry]: rendered as the try (kept through a break). */
    private var nflEntry: Entry? = null
    private var nflDown: DownText? = null
    private var nflTab: NflTab? = null
    private var nflTry = false
    /** (N5) The scores of the snapshots shown going forward, (receivedMono, away, home), kept [SportsConstants.QUEUE_KEEP_MS]. */
    private val nflShown = ArrayList<Triple<Long, Int?, Int?>>()

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

    /**
     * (Update interval §3.5, commit 0) The live poll interval in ms for the followed game's league, as the repository
     * resolves it (pref → Auto → the 429 floor); null = today's timing. NHL 20 s is byte-identical to null.
     */
    fun interval(liveMs: Long?) {
        this.liveMs = liveMs?.takeIf { it > 0 }
        step()
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
            val old = ok == null || now - ok.receivedMono > (liveMs ?: SportsConstants.CDN_BUCKET_MS)
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
                doneMono = now
                val held = lastOk?.value
                if (fast() && held != null && olderNfl(held, f.value) && holdsOlder(now, held, f.value)) {
                    // (Review S5) An older body (the board fallback lags per-event by 6–9 s): a no-op Ok for stale and the
                    // schedule, never shown, so the score never goes back in time.
                    lastOk = f.copy(value = held)
                    step()
                    return
                }
                olderSince = null
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
                // (Update interval §3.4) The NFL at a set interval backs off from 5 s, so one dropped packet does not
                // freeze the bug for 20 s; a 429 keeps the 20 s base, with Retry-After as a floor (C-S4).
                val base = if (fast() && f.kind != FailKind.HTTP_429) SportsConstants.BACKOFF_FAST_BASE_MS else SportsConstants.BACKOFF_BASE_MS
                backoffAt = SportsTiming.backoffAt(f.receivedMono, failures, f.retryAfterS, jitter(), base)
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
        markUntil?.let { if (now >= it) { markUntil = null; goalSide = null; goalPp = false; nflScores = emptyList() } }
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
        if (s.id.league == League.NFL) return nflView(e, waiting, stale, now)

        val label = label(s)
        var top: CentreText = CentreText.Clock
        var bottom: CentreText? = when {
            s.phase == GamePhase.FINAL -> { top = CentreText.Final(s.finalKind ?: PeriodKind.REG); null }
            s.periodKind == PeriodKind.SO -> { top = CentreText.Shootout; null }
            // (Owner ruling 33, 2026-11-08) A break is one line with no clock: "End 1st" at 0:00, then "1st INT".
            s.phase == GamePhase.INTERMISSION -> { top = CentreText.Intermission(label); null }
            s.clock?.let { it.secondsRemaining <= 0 && !it.running && !it.inIntermission } == true -> { top = CentreText.EndOf(label); null }
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
            period = label, // (Stacked §2.1, B1) kept in stale and waiting views, where `bottom` is overwritten
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
        if (s.id.league == League.NFL) {
            // (NFL §3.1, S3) The NFL line order: "No update" over the clock in play; one line in a break, never a frozen 0:00.
            val live = s.phase == GamePhase.LIVE
            val top = if (s.phase == GamePhase.FINAL) CentreText.Final(s.finalKind ?: PeriodKind.REG) else CentreText.NoUpdate
            val bottom = if (live) CentreText.Clock else null
            val secs = if (live) s.clock?.takeIf { clockShown(s) }?.secondsRemaining?.coerceAtLeast(0) else null
            return ScoreBugView(
                s.away, s.home, top, bottom, dim = s.phase != GamePhase.FINAL, goalSeq = goalSeq, goalSide = null,
                clockSecs = secs, gameId = game?.id, league = League.NFL,
            )
        }
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
            if (ok != null && isPreGame(ok.value.phase) && !kickoffDue(ok.value)) t = maxOf(t, preWakeMono(now))
            return t
        }
        ok ?: return followAt
        val floor = if (demand == Demand.UNSEEN) ok.receivedMono + SportsConstants.POLL_UNSEEN_MS else Long.MIN_VALUE
        return when (ok.value.phase) {
            GamePhase.FINAL, GamePhase.POSTPONED -> null
            // (NFL lead sign-off 1) An ESPN `in` body still at the coin toss (S8, PRE) polls at the LIVE cadence.
            GamePhase.SCHEDULED, GamePhase.PRE -> if (kickoffDue(ok.value)) {
                livePoll(ok, floor)
            } else {
                SportsTiming.nextAligned(ok, pollPhaseMs, SportsConstants.INTERMISSION_BUCKET_SKIP, maxOf(floor, preWakeMono(now)))
            }
            GamePhase.LIVE -> livePoll(ok, floor)
            GamePhase.INTERMISSION -> {
                val b = breakMs()
                when {
                    b == null -> SportsTiming.nextAligned(ok, pollPhaseMs, SportsConstants.INTERMISSION_BUCKET_SKIP, floor)
                    fast() -> maxOf(doneMono + b, floor)
                    else -> SportsTiming.nextAligned(ok, pollPhaseMs, bucketSkip(b), floor)
                }
            }
        }
    }

    /**
     * (Update interval §3.5) A live poll: NHL bucket-aligned, skipping `interval / 20 s − 1` buckets (20 s → 0, as
     * today); the NFL at a set interval from completion (per-event has `max-age=1`, so no bucket to align to).
     */
    private fun livePoll(ok: Fetch.Ok<GameSnapshot>, floor: Long): Long {
        val l = liveMs
        return when {
            l == null -> SportsTiming.nextAligned(ok, pollPhaseMs, 0, floor)
            fast() -> maxOf(doneMono + l, floor)
            else -> SportsTiming.nextAligned(ok, pollPhaseMs, bucketSkip(l), floor)
        }
    }

    private fun bucketSkip(ms: Long): Int = (ms / SportsConstants.CDN_BUCKET_MS - 1).coerceAtLeast(0L).toInt()

    /** The NFL follow at a set interval: completion-based polls, the fast back-off, the older-body guard. */
    private fun fast(): Boolean = liveMs != null && game?.league == League.NFL

    /** (§3.5) Breaks at 2 × live, never under [SportsConstants.INTERMISSION_MIN_MS]; null = today's (skip one bucket). */
    private fun breakMs(): Long? = liveMs?.let { maxOf(2 * it, SportsConstants.INTERMISSION_MIN_MS) }

    /**
     * (Review B1) A rise is a goal against a comparison at most this old: today's 100 s, or `2 × break + 20 s` when the
     * interval makes breaks longer (NHL 60 s: 280 s). NHL 20 s stays 100 s.
     */
    private fun goalFreshMs(): Long {
        val b = breakMs() ?: return GOAL_FRESH_MS
        return maxOf(GOAL_FRESH_MS, 2 * b + SportsConstants.BACKOFF_BASE_MS)
    }

    /**
     * (Review S5) [s] is older than the held [prev]: a lower `lastPlay.id` (ESPN's ids rise), or, with the same or no
     * ids, an earlier (period, clock) at an equal or lower score sum.
     */
    private fun olderNfl(prev: GameSnapshot, s: GameSnapshot): Boolean {
        if (prev.id != s.id || s.id.league != League.NFL) return false
        if (s.phase == GamePhase.FINAL) return false // (fix pass 5) a Final body is never held
        val a = (prev.detail as? LeagueDetail.Nfl)?.lastPlay?.id?.toLongOrNull()
        val b = (s.detail as? LeagueDetail.Nfl)?.lastPlay?.id?.toLongOrNull()
        if (a != null && b != null && a != b) return b < a
        fun inPlay(g: GameSnapshot) = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION
        if (!inPlay(prev) || !inPlay(s)) return false
        val pp = prev.period ?: return false
        val sp = s.period ?: return false
        val pc = prev.clock?.secondsRemaining
        val sc = s.clock?.secondsRemaining
        val earlier = sp < pp || (sp == pp && pc != null && sc != null && sc > pc)
        val sum = { g: GameSnapshot -> (g.away.score ?: 0) + (g.home.score ?: 0) }
        return earlier && sum(s) <= sum(prev)
    }

    /**
     * (Fix pass 5) Whether the older [s] is still held. The guard assumes ESPN's `lastPlay.id` only rises; an overturned
     * scoring play that ESPN deletes can send it back down for good. So a run of older bodies is held for at most
     * [SportsConstants.NFL_OLDER_HOLD_MAX_MS] from the first; after that, one whose score or phase differs from the held
     * snapshot is ESPN's correction and is shown. One with the same score and phase stays a no-op.
     */
    private fun holdsOlder(now: Long, held: GameSnapshot, s: GameSnapshot): Boolean {
        val since = olderSince ?: now.also { olderSince = it }
        if (now - since < SportsConstants.NFL_OLDER_HOLD_MAX_MS) return true
        return s.phase == held.phase && s.away.score == held.away.score && s.home.score == held.home.score
    }

    /** Whether a rise to SEEN may poll now: not for a finished game, nor for a pre-game one still asleep. */
    private fun pollsAtAll(now: Long): Boolean {
        val snap = lastOk?.value ?: return true
        return when (snap.phase) {
            GamePhase.FINAL, GamePhase.POSTPONED -> false
            GamePhase.SCHEDULED, GamePhase.PRE -> kickoffDue(snap) || now >= preWakeMono(now)
            else -> true
        }
    }

    private fun isPreGame(p: GamePhase) = p == GamePhase.SCHEDULED || p == GamePhase.PRE

    /**
     * (NFL §1.3 S8, lead sign-off 1) An NFL PRE snapshot from an ESPN `in` body (the coin toss, before the opening
     * kick-off): the one PRE with both scores set (`EspnMapper` leaves them null before `in`). It polls at the LIVE
     * cadence, so the bug appears within one poll of the first logged play.
     */
    private fun kickoffDue(s: GameSnapshot): Boolean =
        s.id.league == League.NFL && s.phase == GamePhase.PRE && s.away.score != null && s.home.score != null

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
            if (q.snap.id.league == League.NFL) {
                // (NFL §3.3, §3.6) The try, the inference memory and the held tab restart from the earlier snapshot.
                resetNflPlay()
                nflAdvance(null, q)
            }
            return
        }
        val at = maxOf(q.anchorMono + delayMs, q.receivedMono)
        shown = q
        val s = q.snap
        val p = prev?.snap
        var flagged = false

        // C-N1: a goal is flagged only against a recent comparison (not the hours-old snapshot shown before a long NONE
        // or UNSEEN stretch), and never when both sides rose meanwhile.
        if (p != null && demand == Demand.SEEN && q.receivedMono - prev.receivedMono <= goalFreshMs()) {
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
            if (side != null && s.id.league == League.NFL) {
                // (NFL §3.2) The kind from the point rise; 4, 5, ≥ 9 and a correction (N5) flag nothing.
                val scores = nflScoresFor(p, s, side, q.receivedMono)
                if (scores != null) {
                    goalSeq++
                    goalSide = side
                    markUntil = at + if (scores.size == 2) SportsConstants.NFL_DOUBLE_MARK_MS else SportsConstants.GOAL_MARK_MS
                    goalPp = false
                    nflScores = scores
                    flagged = true
                }
            } else if (side != null) {
                goalSeq++
                goalSide = side
                markUntil = at + SportsConstants.GOAL_MARK_MS
                goalPp = goalWasPp(p, s, side)
                flagged = true
            }
        }
        if (s.id.league == League.NFL) nflAdvance(p, q)

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

    // ------------------------------------------------------------------ NFL (docs/design/m6-nfl.md §3)

    /**
     * (NFL §3.1, §3.4) The NFL view of [e]: the quarter over the clock in play, one line in breaks and at Final, no
     * shootout or "0:00 → EndOf" rule (ESPN shows 0:00 during untimed downs). The down column and the tab only when
     * fresh and LIVE, or for a pending try's PAT through a break (§3.4).
     */
    private fun nflView(e: Entry, waiting: Boolean, stale: Boolean, now: Long): ScoreBugView {
        val s = e.snap
        val label = label(s)
        val d = s.detail as? LeagueDetail.Nfl
        val oneLine = s.phase != GamePhase.LIVE
        var top: CentreText = when {
            s.phase == GamePhase.FINAL -> CentreText.Final(s.finalKind ?: PeriodKind.REG)
            s.phase == GamePhase.INTERMISSION && d?.breakKind == NflBreak.HALFTIME -> CentreText.Halftime
            s.phase == GamePhase.INTERMISSION -> CentreText.EndOf(label)
            else -> CentreText.Period(label)
        }
        when {
            waiting -> top = CentreText.Delayed
            stale -> top = CentreText.NoUpdate
        }
        val bottom: CentreText? = if (oneLine) null else CentreText.Clock
        val clockSecs = if (top == CentreText.Clock || bottom == CentreText.Clock) {
            s.clock?.takeIf { clockShown(s) }?.secondsRemaining?.coerceAtLeast(0)
        } else {
            null
        }
        val goal = goalSide.takeIf { markUntil?.let { now < it } == true }
        val fresh = !waiting && !stale
        val drawn = fresh && e === nflEntry &&
            (s.phase == GamePhase.LIVE || (s.phase == GamePhase.INTERMISSION && nflTry))
        return ScoreBugView(
            s.away, s.home, top, bottom, dim = waiting || stale, goalSeq = goalSeq, goalSide = goal, clockSecs = clockSecs,
            gameId = game?.id,
            league = League.NFL,
            nfl = NflView(
                down = if (drawn) nflDown else null,
                tab = if (drawn) nflTab else null,
                scores = if (goal != null) nflScores else emptyList(),
            ),
        )
    }

    /**
     * (NFL §3.2, ruling 25) The takeovers for [side]'s rise from [p] to [s], or null for no flag:
     * 6 → TOUCHDOWN; 7 → TOUCHDOWN +6 then EXTRA POINT +1; 8 → TOUCHDOWN +6 then TWO-POINT +2; 3 → FIELD GOAL;
     * 1 → EXTRA POINT; 2 → TWO-POINT with a try pending for [side], else the `lastPlay` tie-break, else SAFETY;
     * anything else (4, 5, ≥ 9) → null.
     *
     * **The correction guard (N5):** a rise back to a score [side] showed in a snapshot received within
     * `QUEUE_KEEP_MS` is not flagged (a reversed TD restored never replays its takeover).
     *
     * Known misses (§3.2): a defensive two-point return during the other side's try reads SAFETY; a TD and a missed try
     * followed by a safety inside one gap is a two-sided rise (silent); a field goal plus a safety inside one gap (+5)
     * is silent.
     */
    private fun nflScoresFor(p: GameSnapshot, s: GameSnapshot, side: Side, receivedMono: Long): List<NflScore>? {
        val was = scoreOf(p, side) ?: return null
        val now = scoreOf(s, side) ?: return null
        val keep = SportsConstants.QUEUE_KEEP_MS
        if (nflShown.any { receivedMono - it.first <= keep && (if (side == Side.AWAY) it.second else it.third) == now }) return null
        val other = scoreOf(s, if (side == Side.AWAY) Side.HOME else Side.AWAY) ?: 0
        fun score(kind: NflScoreKind, points: Int, total: Int) =
            if (side == Side.AWAY) NflScore(kind, points, total, other) else NflScore(kind, points, other, total)
        return when (now - was) {
            6 -> listOf(score(NflScoreKind.TOUCHDOWN, 6, now))
            7 -> listOf(score(NflScoreKind.TOUCHDOWN, 6, was + 6), score(NflScoreKind.EXTRA_POINT, 1, now))
            8 -> listOf(score(NflScoreKind.TOUCHDOWN, 6, was + 6), score(NflScoreKind.TWO_POINT, 2, now))
            3 -> listOf(score(NflScoreKind.FIELD_GOAL, 3, now))
            1 -> listOf(score(NflScoreKind.EXTRA_POINT, 1, now))
            2 -> listOf(score(twoPointKind(p, s, side), 2, now))
            else -> null
        }
    }

    /**
     * (NFL §3.2) A +2: TWO-POINT when [side] has a try pending; else a `lastPlay` new since [p] and belonging to [side]
     * decides ("two-point" / "2pt" → TWO-POINT, "safety" → SAFETY); else SAFETY.
     */
    private fun twoPointKind(p: GameSnapshot, s: GameSnapshot, side: Side): NflScoreKind {
        if (pendingTry == side) return NflScoreKind.TWO_POINT
        val lp = (s.detail as? LeagueDetail.Nfl)?.lastPlay
        val before = (p.detail as? LeagueDetail.Nfl)?.lastPlay
        if (lp != null && lp.side == side && lp.id != before?.id) {
            val text = lp.typeText?.lowercase().orEmpty()
            if ("two-point" in text || "2pt" in text) return NflScoreKind.TWO_POINT
        }
        return NflScoreKind.SAFETY
    }

    private fun scoreOf(s: GameSnapshot, side: Side): Int? = if (side == Side.AWAY) s.away.score else s.home.score

    /**
     * (NFL §3.3, §3.4) The bookkeeping for the newly shown [q] (previous shown snapshot [p]), then its rendering:
     * 1. **clear** the try on any score change, a shown `Kickoff`, or a shown `Down` whose (period, clock) differs from
     *    `tryAt` (never on a phase change alone: a try after 0:00 may already be in a break);
     * 2. **set** it on any rise of exactly 6 (flagged or not), or on a shown `Dead` whose `lastPlay` is a TD when nothing
     *    is pending (the app started during the try). The TD `lastPlay` seen with a score change is remembered as done,
     *    so a `Dead` body still naming it after the try never sets the try again (WP-1 reading);
     * 3. **render**: a `Dead`, a `Down` equal to the previous shown `Down` (the stale body that carries the score), or a
     *    `Down` at `tryAt` renders as the try ("PAT", "X BALL | EXTRA POINT").
     */
    private fun nflAdvance(p: GameSnapshot?, q: Entry) {
        val s = q.snap
        val d = s.detail as? LeagueDetail.Nfl
        val play = d?.play ?: NflPlay.None
        val at = s.period to s.clock?.secondsRemaining
        val changed = p != null && (p.away.score != s.away.score || p.home.score != s.home.score)
        val six: Side? = when {
            p == null -> null
            delta(p.away.score, s.away.score) == 6 -> Side.AWAY
            delta(p.home.score, s.home.score) == 6 -> Side.HOME
            else -> null
        }
        // 1. Clear, against the state before this snapshot's set.
        if (changed || play is NflPlay.Kickoff || (play is NflPlay.Down && at != tryAt)) {
            pendingTry = null
            tryAt = null
        }
        val lp = d?.lastPlay
        if (changed && lp != null && lp.scoreValue == TD_POINTS) tryDoneId = lp.id
        // 2. Set.
        if (six != null) {
            pendingTry = six
            tryAt = at
        } else if (play is NflPlay.Dead && pendingTry == null && play.scoreValue == TD_POINTS && play.scorer != null &&
            lp != null && lp.id != tryDoneId
        ) {
            pendingTry = play.scorer
            tryAt = at
        }
        // 3. Render.
        val pending = pendingTry
        val asTry = pending != null &&
            (play is NflPlay.Dead || (play is NflPlay.Down && (play == lastDown || at == tryAt)))
        var down: DownText? = null
        var tab: NflTab? = null
        var plain: NflTab? = null
        when {
            asTry -> {
                down = DownText.Pat
                tab = NflTab(pending!!, TabLead.BALL, TabTail.ExtraPoint)
                plain = tab
            }
            play is NflPlay.Down -> {
                down = DownText.Down(play.down, play.distance, play.goalToGo)
                val poss = d?.possession ?: inferPossession(lp)
                if (poss != null) {
                    plain = NflTab(poss, TabLead.BALL, tail(play.field, poss))
                    tab = if (redZone(d, play, poss)) plain.copy(tail = TabTail.RedZone) else plain
                }
            }
            play is NflPlay.Dead -> {
                tab = heldTab // after a FG or a safety: no close/open before the kick-off body
                plain = heldTab
            }
            play is NflPlay.Kickoff -> {
                down = DownText.Kickoff
                plain = play.kicker?.let { NflTab(it, TabLead.KICKOFF, tail(play.field, it)) }
                tab = plain
            }
            else -> Unit // None: no column, no tab
        }
        nflEntry = q
        nflDown = down
        nflTab = tab
        nflTry = asTry
        heldTab = plain
        // The inference memory: the previous shown play.
        when (play) {
            is NflPlay.Kickoff -> lastKicker = play.kicker
            is NflPlay.Down -> {
                lastDown = play
                lastDownPoss = tab?.side
                lastKicker = null
            }
            else -> Unit
        }
        nflShown += Triple(q.receivedMono, s.away.score, s.home.score)
        nflShown.removeAll { q.receivedMono - it.first > SportsConstants.QUEUE_KEEP_MS }
    }

    private fun delta(was: Int?, now: Int?): Int? = if (was != null && now != null) now - was else null

    /**
     * (NFL §3.4, N6) The possession of a `Down` that has none: the shown `lastPlay`'s team when it is a kick-off (the
     * receiver), else the other side of the previously shown `Kickoff`, else the previously shown `Down`'s; else null.
     */
    private fun inferPossession(lp: NflLastPlay?): Side? {
        if (lp != null && lp.side != null && (lp.typeId == SportsConstants.NFL_KICKOFF_TYPE || isKickReturn(lp.typeText))) return lp.side
        lastKicker?.let { return if (it == Side.AWAY) Side.HOME else Side.AWAY }
        return lastDownPoss
    }

    /** ESPN's "Kickoff Return (Offense)": its team is the receiver, as for type 53. */
    private fun isKickReturn(text: String?): Boolean = text != null && KICK_RETURN.containsMatchIn(text)

    /** (B1) OWN / OPP / MIDFIELD from whose half the ball is in, relative to [side]; null with no field. */
    private fun tail(field: FieldPos?, side: Side): TabTail? = when {
        field == null -> null
        field.territory == null -> TabTail.Field(FieldZone.MIDFIELD, 50)
        field.territory == side -> TabTail.Field(FieldZone.OWN, field.yard)
        else -> TabTail.Field(FieldZone.OPP, field.yard)
    }

    /**
     * (NFL §3.5, ruling 26) RED ZONE only on a real down not rendered as the try, with the tab's side known, `isRedZone`
     * set, and the field (when known) the opponent's half at yard ≤ 20.
     */
    private fun redZone(d: LeagueDetail.Nfl?, play: NflPlay.Down, side: Side): Boolean {
        if (d?.redZone != true || play.down !in 1..4) return false
        val f = play.field ?: return true
        return f.territory != null && f.territory != side && f.yard <= RED_ZONE_YARDS
    }

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
        // (Update interval §3.5) The cadence is the league's live or break interval; the 60 s floor stays.
        val cadence = when (ok.value.phase) {
            GamePhase.LIVE -> liveMs ?: SportsConstants.CDN_BUCKET_MS
            GamePhase.INTERMISSION -> breakMs() ?: (SportsConstants.CDN_BUCKET_MS * (1 + SportsConstants.INTERMISSION_BUCKET_SKIP))
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
        olderSince = null
        // goalSeq is monotonic for the machine's lifetime (goal animation §5.1, B1.4): a re-follow never reuses a seq
        // the UI has already seen.
        goalSide = null; markUntil = null; goalPp = false
        lastShownPp = null; ppEndedEntry = null
        nflScores = emptyList(); nflShown.clear()
        resetNflPlay()
    }

    /** (NFL §3.6) The try, the inference memory, the held tab and the current rendering. */
    private fun resetNflPlay() {
        pendingTry = null; tryAt = null; tryDoneId = null
        lastKicker = null; lastDown = null; lastDownPoss = null; heldTab = null
        nflEntry = null; nflDown = null; nflTab = null; nflTry = false
    }

    private companion object {
        /**
         * C-N1: a score rise is a "goal" only when the snapshot it is compared with was received at most this long before
         * (2 × the slowest SEEN cadence plus a failed poll's back-off).
         */
        const val GOAL_FRESH_MS = 2 * 40_000L + SportsConstants.BACKOFF_BASE_MS

        /** (NFL §3.3) A touchdown's points: the rise (and the `lastPlay.scoreValue`) that sets the try. */
        const val TD_POINTS = 6
        /** (NFL §3.5) The red zone: the opponent's half at this yard or closer. */
        const val RED_ZONE_YARDS = 20
        val KICK_RETURN = Regex("(?i)kickoff return \\(offense\\)")
    }
}
