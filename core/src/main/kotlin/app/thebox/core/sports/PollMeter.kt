package app.thebox.core.sports

import kotlin.math.ceil

/**
 * The per-league poll meter and the Auto recommendation (docs/design/m6-sports-update-interval.md §3.2, §3.3, §3.7; owner
 * rulings 29–32; review S2, S6, S9). Pure functions of [PollMeasure], so the repository (which feeds it) and the settings
 * row (which reads the persisted copy) agree.
 *
 * - **p90** = the nearest-rank 90th percentile of `workMs`, only from [SportsConstants.METER_MIN] samples.
 * - **Feed refresh** = the largest `max-age` seen on this endpoint, at least 1 s (NHL 19, ESPN board 10, per-event 1).
 * - **Raw recommendation** = the smallest listed choice `c ≥ feed refresh` with `c × 1000 ≥ METER_FACTOR × p90`; before
 *   METER_MIN samples, the smallest choice ≥ max(the league default, feed refresh). Never under the list's first entry
 *   (NFL 5 s, NHL 20 s).
 * - **Hysteresis** (S9): the held value moves up at once; it moves down only after
 *   [SportsConstants.METER_DOWN_STREAK] samples in a row whose `METER_FACTOR × p90` fits in
 *   [SportsConstants.METER_DOWN_SHARE] of the lower choice.
 * - **An endpoint change resets the window** (the board fallback has a 10 s feed); [PollMeasure.slowed] survives it.
 *   Only a good sample changes the endpoint; a failed one on another endpoint is dropped (fix pass 2).
 * - **Effective interval** = the choice (Auto = the recommendation), raised to [SportsConstants.AUTO_429_FLOOR_S] for the
 *   NFL after a 429 or 403 (S2: a server instruction, so for every choice).
 */
object PollMeter {
    /** The nearest-rank p90 of [work], or null when there are fewer than [SportsConstants.METER_MIN] samples. */
    fun p90(work: List<Int>): Int? {
        if (work.size < SportsConstants.METER_MIN) return null
        val sorted = work.sorted()
        val rank = ceil(0.9 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun feedRefreshS(m: PollMeasure): Int = maxOf(1, m.maxAgeS ?: 1)

    /** The smallest choice of [league] at or above [floorS] that also fits `share × c × 1000 ≥ FACTOR × p90`; else the last. */
    private fun fit(league: League, floorS: Int, p90: Int?, share: Double): Int {
        val choices = SportsConstants.intervalChoices(league)
        return choices.firstOrNull { c ->
            c >= floorS && (p90 == null || share * c * 1000.0 >= SportsConstants.METER_FACTOR.toDouble() * p90)
        } ?: choices.last()
    }

    /** The recommendation from the window alone, without hysteresis. */
    fun raw(m: PollMeasure, league: League): Int {
        val p = p90(m.workMs) ?: return fit(league, maxOf(SportsConstants.intervalDefault(league), feedRefreshS(m)), null, 1.0)
        return fit(league, feedRefreshS(m), p, 1.0)
    }

    /**
     * [m] plus one sample on [endpoint]: [workMs] (a failure's elapsed time is capped by the caller's [failed] flag),
     * [wireBytes] and [maxAgeS] for a good poll. Returns the new measure with its held recommendation updated.
     */
    fun add(
        m: PollMeasure?,
        league: League,
        endpoint: String,
        workMs: Long,
        wireBytes: Long?,
        maxAgeS: Int?,
        failed: Boolean = false,
    ): PollMeasure {
        val base = when {
            m == null -> PollMeasure(endpoint = endpoint)
            // (Fix pass 2) Only a good poll on the new endpoint moves the meter there (the source names a new endpoint
            // only once it has switched): a failure on another endpoint says nothing about either window, so it is dropped
            // and the window and the hysteresis stay as they are.
            m.endpoint != endpoint -> if (failed) return m else PollMeasure(endpoint = endpoint, slowed = m.slowed)
            else -> m
        }
        val w = (if (failed) minOf(workMs, SportsConstants.METER_FAIL_CAP_MS.toLong()) else workMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val work = (base.workMs + w).takeLast(SportsConstants.METER_WINDOW)
        val bytes = if (!failed && wireBytes != null && wireBytes >= 0) {
            (base.wireBytes + wireBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).takeLast(SportsConstants.METER_WINDOW)
        } else {
            base.wireBytes
        }
        val age = if (!failed && maxAgeS != null) maxOf(base.maxAgeS ?: 0, maxAgeS) else base.maxAgeS
        val next = base.copy(workMs = work, wireBytes = bytes, maxAgeS = age)
        return hold(base.recommendedS, next, league)
    }

    /** (S9) Moves the held recommendation: up at once, down only after a streak that clears the margin. */
    private fun hold(held: Int?, m: PollMeasure, league: League): PollMeasure {
        val raw = raw(m, league)
        val p = p90(m.workMs)
        return when {
            held == null || p == null || raw > held -> m.copy(recommendedS = raw, downStreak = 0)
            raw == held -> m.copy(recommendedS = held, downStreak = 0)
            else -> {
                val down = fit(league, feedRefreshS(m), p, SportsConstants.METER_DOWN_SHARE)
                if (down >= held) {
                    m.copy(recommendedS = held, downStreak = 0)
                } else {
                    val streak = m.downStreak + 1
                    if (streak >= SportsConstants.METER_DOWN_STREAK) m.copy(recommendedS = down, downStreak = 0)
                    else m.copy(recommendedS = held, downStreak = streak)
                }
            }
        }
    }

    /** The recommendation the row and Auto show: the held value (when it is a listed choice), else [raw]. */
    fun recommended(m: PollMeasure?, league: League): Int {
        val choices = SportsConstants.intervalChoices(league)
        return m?.recommendedS?.takeIf { it in choices } ?: raw(m ?: PollMeasure(), league)
    }

    /** Whether [m] holds enough samples for a measured recommendation. */
    fun measured(m: PollMeasure?): Boolean = m != null && m.workMs.size >= SportsConstants.METER_MIN

    /**
     * (§3.3) A stored choice read against [league]'s list: 0 = Auto; a listed value as is; anything else (a restore from
     * a newer build) the next listed value above it, else Auto.
     */
    fun normalize(choiceS: Int, league: League): Int {
        if (choiceS <= 0) return 0
        val choices = SportsConstants.intervalChoices(league)
        if (choiceS in choices) return choiceS
        return choices.firstOrNull { it > choiceS } ?: 0
    }

    /** The interval the machine runs, in seconds: the choice (Auto = [recommended]), with the NFL's 429 floor (S2). */
    fun effectiveS(choiceS: Int, league: League, m: PollMeasure?): Int {
        val c = normalize(choiceS, league)
        val base = if (c == 0) recommended(m, league) else c
        return if (league == League.NFL && m?.slowed == true) maxOf(base, SportsConstants.AUTO_429_FLOOR_S) else base
    }

    /** The median wire bytes per good poll, else the league's estimate (§3.7). */
    fun bytesPerPoll(m: PollMeasure?, league: League): Int {
        val b = m?.wireBytes.orEmpty()
        if (b.isEmpty()) return if (league == League.NHL) SportsConstants.EST_WIRE_BYTES_NHL else SportsConstants.EST_WIRE_BYTES_NFL
        val s = b.sorted()
        return s[(s.size - 1) / 2]
    }

    /** About this many MB an hour at [intervalS] while a game is live (headers not counted). */
    fun mbPerHour(m: PollMeasure?, league: League, intervalS: Int): Double =
        3600.0 / intervalS.coerceAtLeast(1) * bytesPerPoll(m, league) / 1_000_000.0

    /** The meter's endpoint tag for the per-event NFL follow and for the board fallback. */
    const val NFL_EVENT = "nfl-event"
    const val NFL_BOARD = "nfl-board"
    const val NHL_LANDING = "nhl-landing"
}
