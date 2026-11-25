package app.thebox.core.sports.hub

import app.thebox.core.sports.GamePhase

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.3; WP-1) When the shown game's detail is fetched again. Pure.
 *
 * | Phase | Next request |
 * |---|---|
 * | SCHEDULED, PRE | every `HUB_DETAIL_IDLE_MS` |
 * | LIVE, INTERMISSION | every 30 s, bucket-aligned (`SportsTiming.nearestAligned`) |
 * | FINAL | none; while the recap is missing and the game ended < `HUB_RECAP_WAIT_MS` ago, every `HUB_DETAIL_IDLE_MS` |
 * | POSTPONED | none |
 *
 * Commit 0 stub: never asks for another request (null). Only WP-1's `GameDetailRepositoryImpl` calls it, so WP-1 may
 * refine the signature (alignment, back-off and the "no request" conditions).
 */
object DetailPollPolicy {
    /** Ms after an answer at [answeredAtMs] until the next request, or null for none. */
    @Suppress("UNUSED_PARAMETER")
    fun nextDelayMs(phase: GamePhase, recapPosted: Boolean, endedAgoMs: Long?, answeredAtMs: Long): Long? = null
}
