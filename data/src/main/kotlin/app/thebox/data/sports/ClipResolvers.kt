package app.thebox.data.sports

import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §4.1, §4.2) The [ClipResolver] the app binds: a router by
 * `ref.gameId.league`. NHL → [nhl]; NFL → [espn], or `NotFound` without a call while [espnVideo] is off
 * (`SportsConstants.ESPN_VIDEO_ENABLED`, the kill switch); any other league → `NotFound`.
 */
@Singleton
class ClipResolvers internal constructor(
    private val nhl: ClipResolver,
    private val espn: ClipResolver,
    private val espnVideo: Boolean,
) : ClipResolver {
    @Inject constructor(nhl: NhlClipResolver, espn: EspnClipResolver) : this(nhl, espn, SportsConstants.ESPN_VIDEO_ENABLED)

    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> = route(ref)?.info(ref) ?: ClipResult.NotFound

    override suspend fun stream(ref: ClipRef, fresh: Boolean): ClipResult<Pair<ClipInfo, ClipStream>> =
        route(ref)?.stream(ref, fresh) ?: ClipResult.NotFound

    private fun route(ref: ClipRef): ClipResolver? = when (ref.gameId.league) {
        League.NHL -> nhl
        League.NFL -> espn.takeIf { espnVideo }
        else -> null
    }
}
