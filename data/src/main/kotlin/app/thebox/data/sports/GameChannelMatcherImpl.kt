package app.thebox.data.sports

import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.GameChannelMatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.2 – §5.4; WP-3) Broadcaster aliases over `channel_fts` (A), guide titles
 * over the EPG FTS (B) and a window read of A's channels (C); confidence, ranking and variant collapse (§5.3); cache keyed
 * by (game id, `ParentalAccess`, EPG generation), LRU `HUB_DETAIL_LRU`, cleared by [clear].
 *
 * Commit 0 stub: finds nothing and reads nothing. Bound in `SportsModule`; WP-3 changes the constructor freely (Hilt
 * injects it).
 */
@Singleton
class GameChannelMatcherImpl @Inject constructor() : GameChannelMatcher {
    override suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate> = emptyList()
    override fun clear() = Unit
}
