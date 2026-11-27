package app.thebox.core.sports.hub

import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsTiming

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.3; WP-1) When the shown game's detail is fetched again. Pure. The table
 * is [DetailCadence.NHL]'s; (NFL hub §5.2) [DetailCadence.NFL] reads every 30 min before the game, every 60 s (not
 * aligned) live and in breaks, forces a re-read on a phase change (15 s floor), and keeps the final's 5 min re-read for
 * up to 2 h until a highlight is offered and for 30 min while the game has single-play clips.
 *
 * | Phase | Next request |
 * |---|---|
 * | SCHEDULED, PRE | every `HUB_DETAIL_IDLE_MS` |
 * | LIVE, INTERMISSION | every 30 s, bucket-aligned (`SportsTiming.nearestAligned`, the install's poll phase) |
 * | FINAL | none; while the recap is missing and the game ended < `HUB_RECAP_WAIT_MS` ago, every `HUB_DETAIL_IDLE_MS` |
 * | POSTPONED | none |
 *
 * Failures back off as the day list does (`SportsTiming.backoffAt`, `Retry-After` a floor); a [refused] failure (429 or
 * REFUSED) waits at least `BACKOFF_MAX_MS`. Nothing at all while the [DetailGate] is closed.
 */
object DetailPollPolicy {
    /** The "no request" conditions (§4.3): every one must hold for any request. */
    data class DetailGate(
        val sportsOn: Boolean,
        val awake: Boolean,
        val foreground: Boolean,
        val online: Boolean,
        val paused: Boolean,
    ) {
        val open: Boolean get() = sportsOn && awake && foreground && online && !paused
    }

    /**
     * The phase the policy times by: the further along of the board's [board] phase and the detail's [fetched] one (a
     * board that went LIVE re-times a detail fetched at PRE at once; a detail that already saw FINAL is not polled as
     * live because the board lags). POSTPONED on either side wins.
     */
    fun effectivePhase(board: GamePhase, fetched: GamePhase?): GamePhase {
        if (board == GamePhase.POSTPONED || fetched == GamePhase.POSTPONED) return GamePhase.POSTPONED
        fetched ?: return board
        return if (rank(fetched) > rank(board)) fetched else board
    }

    /**
     * The phase's re-read interval under [cadence], or null for none (the staleness yardstick too).
     *
     * FINAL (NFL hub §5.2): every `HUB_DETAIL_IDLE_MS` while either wait holds, else none (expiry is local, §4.3):
     * - no recap / offered highlight ([FinalFacts.recapPosted] false) and the game ended < [DetailCadence.finalWaitMs] ago;
     * - (NFL, arch m6) the detail lists a PLAY clip ([FinalFacts.playClips]) and the game ended < [DetailCadence.playsWaitMs]
     *   ago, so a single-play clip ESPN posts after the highlight (MNF: 7 min later) is still picked up.
     * An unknown end ([FinalFacts.endedAgoMs] null) waits for neither. [DetailCadence.NHL] has no plays wait, so its
     * behaviour is the Sports Hub's unchanged.
     */
    fun intervalMs(cadence: DetailCadence, phase: GamePhase, final: FinalFacts): Long? = when (phase) {
        GamePhase.SCHEDULED, GamePhase.PRE -> cadence.preMs
        GamePhase.LIVE, GamePhase.INTERMISSION -> cadence.liveMs
        GamePhase.FINAL -> if (finalWait(cadence, final)) SportsConstants.HUB_DETAIL_IDLE_MS else null
        GamePhase.POSTPONED -> null
    }

    private fun finalWait(cadence: DetailCadence, final: FinalFacts): Boolean {
        val ended = final.endedAgoMs ?: return false
        if (!final.recapPosted && ended < cadence.finalWaitMs) return true
        val plays = cadence.playsWaitMs ?: return false
        return final.playClips && ended < plays
    }

    /**
     * The `MonoClock` instant of the next detail request, or null for none. [lastOk] is the newest good answer for this
     * game (null = none yet: now). After [failures] consecutive failures since it, the back-off from [lastFailMono]
     * ([jitter] ∈ [-1, 1]); back-off is checked first. [fetchedPhase] is the phase of the cached detail.
     *
     * (NFL hub §5.2, arch m5) With [DetailCadence.phaseChangeFloorMs] set (the NFL), a phase change is due at once: when
     * [phase] (the effective phase) is in a later phase group than [fetchedPhase] (pre-game: SCHEDULED, PRE; play: LIVE,
     * INTERMISSION; FINAL; POSTPONED), the next request is `max(nowMono, lastOk + floor)`, even when the new phase has no
     * interval of its own. Within a group (SCHEDULED → PRE, LIVE ↔ a break) nothing is forced: the plain loop already
     * covers it (§17.1 P1 note 1). Back-off is checked first and always wins. The NHL ([DetailCadence.NHL]) ignores
     * [fetchedPhase], as before.
     *
     * (Integration §18) [lagTries] counts the good answers in a row that still [lagging] the board (the caller's count).
     * From `HUB_NFL_PHASE_LAG_TRIES` on, the forced re-read stops and the effective phase's own interval applies (live:
     * 60 s; FINAL: `HUB_DETAIL_IDLE_MS` even when no final wait holds, because the detail still is not final), so a
     * summary header that never catches up costs at most a few 15 s reads, then the normal final cadence.
     */
    fun nextAt(
        cadence: DetailCadence,
        gate: DetailGate,
        lastOk: Fetch.Ok<*>?,
        phase: GamePhase,
        fetchedPhase: GamePhase?,
        final: FinalFacts,
        failures: Int,
        lastFailMono: Long?,
        retryAfterS: Int?,
        refused: Boolean,
        pollPhaseMs: Long,
        jitter: Double,
        nowMono: Long,
        lagTries: Int = 0,
    ): Long? {
        if (!gate.open) return null
        if (failures > 0 && lastFailMono != null) {
            val at = SportsTiming.backoffAt(lastFailMono, failures, retryAfterS, jitter)
            return if (refused) maxOf(at, lastFailMono + SportsConstants.BACKOFF_MAX_MS) else at
        }
        lastOk ?: return nowMono
        val floor = cadence.phaseChangeFloorMs
        if (floor != null && fetchedPhase != null && lagging(phase, fetchedPhase)) {
            if (lagTries < SportsConstants.HUB_NFL_PHASE_LAG_TRIES) return maxOf(nowMono, lastOk.receivedMono + floor)
            // Capped: the phase's own cadence; a FINAL the detail has not reached keeps the 5 min re-read.
            return lastOk.receivedMono + (intervalMs(cadence, phase, final) ?: SportsConstants.HUB_DETAIL_IDLE_MS)
        }
        val interval = intervalMs(cadence, phase, final) ?: return null
        return when (phase) {
            GamePhase.LIVE, GamePhase.INTERMISSION ->
                if (cadence.liveAligned) SportsTiming.nearestAligned(lastOk, pollPhaseMs, lastOk.receivedMono + interval) else lastOk.receivedMono + interval
            else -> lastOk.receivedMono + interval
        }
    }

    /** (A-S7) A detail fetched at [fetchedAtMono] is stale once older than 2 × its phase's interval; never without one. */
    fun isStale(cadence: DetailCadence, fetchedAtMono: Long, phase: GamePhase, final: FinalFacts, nowMono: Long): Boolean {
        val interval = intervalMs(cadence, phase, final) ?: return false
        return nowMono - fetchedAtMono > STALE_FACTOR * interval
    }

    /** The instant [isStale] turns true, or null for never (so the repository can re-publish then). */
    fun staleAt(cadence: DetailCadence, fetchedAtMono: Long, phase: GamePhase, final: FinalFacts): Long? =
        intervalMs(cadence, phase, final)?.let { fetchedAtMono + STALE_FACTOR * it + 1 }

    private const val STALE_FACTOR = 2

    /**
     * (NFL hub §5.2, integration §18) The detail fetched at [fetchedPhase] lags [phase] (the effective phase): [phase] is
     * in a later phase group. The repository counts the answers in a row for which this holds (`nextAt`'s `lagTries`).
     */
    fun lagging(phase: GamePhase, fetchedPhase: GamePhase?): Boolean = fetchedPhase != null && group(phase) > group(fetchedPhase)

    /** The phase-change rule's groups: a break is still the game in play; SCHEDULED and PRE are both before it. */
    private fun group(p: GamePhase): Int = when (p) {
        GamePhase.SCHEDULED, GamePhase.PRE -> 0
        GamePhase.LIVE, GamePhase.INTERMISSION -> 1
        GamePhase.FINAL -> 2
        GamePhase.POSTPONED -> 3
    }

    private fun rank(p: GamePhase): Int = when (p) {
        GamePhase.SCHEDULED -> 0
        GamePhase.PRE -> 1
        GamePhase.LIVE, GamePhase.INTERMISSION -> 2
        GamePhase.FINAL -> 3
        GamePhase.POSTPONED -> 4
    }
}

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §5.2, §6.3) A league's detail cadence. Pure. [NHL] reproduces the Sports
 * Hub's numbers exactly (the table above); [NFL] is §5.2's (P1).
 */
data class DetailCadence(
    /** SCHEDULED, PRE: the re-read interval. */
    val preMs: Long,
    /** LIVE, INTERMISSION: the re-read interval. */
    val liveMs: Long,
    /** The live re-read is bucket-aligned (`SportsTiming.nearestAligned`, the install's poll phase). */
    val liveAligned: Boolean,
    /** FINAL: re-read every `HUB_DETAIL_IDLE_MS` while no recap / highlight and ended < this. */
    val finalWaitMs: Long,
    /** FINAL: also while the detail lists PLAY clips and ended < this; null = never (NHL). */
    val playsWaitMs: Long?,
    /** The end estimate of a game first seen final: start + this. */
    val gameLengthMs: Long,
    /** A phase change (effective vs fetched) is due at once, no earlier than lastOk + this; null = off (NHL). */
    val phaseChangeFloorMs: Long?,
) {
    companion object {
        /** A regulation NHL game's usual length, start to final horn (2 h 30 min). */
        private const val NHL_GAME_LENGTH_MS = 9_000_000L

        val NHL = DetailCadence(
            preMs = SportsConstants.HUB_DETAIL_IDLE_MS,
            liveMs = SportsConstants.DAY_POLL_LIVE_MS,
            liveAligned = true,
            finalWaitMs = SportsConstants.HUB_RECAP_WAIT_MS,
            playsWaitMs = null,
            gameLengthMs = NHL_GAME_LENGTH_MS,
            phaseChangeFloorMs = null,
        )
        val NFL = DetailCadence(
            preMs = SportsConstants.HUB_NFL_PRE_MS,
            liveMs = SportsConstants.HUB_NFL_LIVE_MS,
            liveAligned = false,
            finalWaitMs = SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS,
            playsWaitMs = SportsConstants.HUB_NFL_PLAYS_WAIT_MS,
            gameLengthMs = SportsConstants.HUB_NFL_GAME_LENGTH_MS,
            phaseChangeFloorMs = SportsConstants.HUB_NFL_REFETCH_FLOOR_MS,
        )

        /**
         * (CHL hub commit 0, docs/design/m6-chl-hub.md §4.2) The CHL's: pre-game every 30 min; the final re-read every
         * `HUB_DETAIL_IDLE_MS` while the three stars are missing ([FinalFacts.recapPosted] = `stars.isNotEmpty()`), for
         * at most `HUB_CHL_STARS_WAIT_MS` after the end; a phase change is due at once (15 s floor). [liveMs] is unused in
         * v1: the repository publishes `BoardOnly` for a CHL game live, in a break or postponed, before the policy runs.
         */
        val CHL = DetailCadence(
            preMs = SportsConstants.HUB_NFL_PRE_MS,
            liveMs = SportsConstants.DAY_POLL_LIVE_MS,
            liveAligned = false,
            finalWaitMs = SportsConstants.HUB_CHL_STARS_WAIT_MS,
            playsWaitMs = null,
            gameLengthMs = NHL_GAME_LENGTH_MS,
            phaseChangeFloorMs = SportsConstants.HUB_NFL_REFETCH_FLOOR_MS,
        )
    }
}

/**
 * (NFL hub §6.3) The FINAL facts the policy reads. [recapPosted]: the NHL recap id / the NFL offered highlight;
 * [playClips]: the detail lists a PLAY clip; [endedAgoMs]: how long ago the game ended, null when unknown.
 */
data class FinalFacts(val recapPosted: Boolean, val playClips: Boolean, val endedAgoMs: Long?)
