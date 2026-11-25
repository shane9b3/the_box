package app.thebox.data.sports

import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import javax.inject.Inject

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.2; WP-1) `gamecenter/{id}/right-rail` + `/landing` together (the result is
 * `Ok` when the right-rail answered; a failed landing sets `failing`), `/boxscore` on demand; over [NhlHttp] on the
 * `@ApiClient` client with `WireMeter`, `SportsEndpoints.apiBase`, bodies capped, `NhlDetailMapper`.
 *
 * Commit 0 stub: makes no request and answers `Failed(HTTP_OTHER)`. Bound in `SportsModule`; WP-1 changes the
 * constructor freely (Hilt injects it).
 */
class NhlDetailSource @Inject constructor() : GameDetailSource {
    override suspend fun detail(id: GameId): Fetch<GameDetail> = Fetch.Failed(FailKind.HTTP_OTHER, null, 0L)
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> = Fetch.Failed(FailKind.HTTP_OTHER, null, 0L)
}
