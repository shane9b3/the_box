package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId

/**
 * (NFL hub, docs/design/m6-nfl-hub.md §2, §3.5, §5.3, §6.1; P1) ESPN's game summary → the NFL [GameDetail] and
 * [BoxScore]. Pure. Sides come from the header's `homeAway` by team id in every section, never by index; no code depends
 * on key order.
 *
 * Commit 0: the frozen signatures only, both returning null, so `:data` (P2) and `:ui` (P3) compile and test against
 * them. P1 implements them.
 */
object EspnDetailMapper {
    /**
     * The detail of [id] from [dto]; clips are offered at [atMs] (`now + skew`; null skips the time check, §4.3). Null
     * when the summary is not usable (no header, no two sides by team id).
     */
    @Suppress("UNUSED_PARAMETER")
    fun detail(dto: EspnSummaryDto, id: GameId, atMs: Long?): GameDetail? = null // P1

    /** The box score (Passing, Rushing, Receiving tables per team, ≤ `HUB_NFL_BOX_LINES` athletes), sides from the header. */
    @Suppress("UNUSED_PARAMETER")
    fun box(dto: EspnSummaryDto): BoxScore? = null // P1
}
