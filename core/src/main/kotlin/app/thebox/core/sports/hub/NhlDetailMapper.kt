package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import kotlinx.serialization.json.JsonObject

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.1, §4.2; WP-1) Maps `gamecenter/{id}/right-rail`, `/landing` and
 * `/boxscore` bodies, every field optional (a missing or odd field never fails the detail). Pure.
 *
 * Commit 0 stub: maps nothing (null). Only WP-1's `NhlDetailSource` calls it, so WP-1 may refine the signatures.
 */
object NhlDetailMapper {
    /**
     * The detail of [id] from its [rightRail] (required) and [landing] (null when that call failed: goals, stars,
     * leaders and goalies stay empty). [serverDateMs] is the newer of the two answers' `Date`. Null = unmappable.
     */
    @Suppress("UNUSED_PARAMETER")
    fun detail(id: GameId, rightRail: JsonObject, landing: JsonObject?, serverDateMs: Long?): GameDetail? = null

    /** The box score from a `/boxscore` body (`playerByGameStats`). Null = unmappable. */
    @Suppress("UNUSED_PARAMETER")
    fun boxScore(body: JsonObject): BoxScore? = null
}
