package app.thebox.data.sports

import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameDetailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.3, §4.4; WP-1) The panel's game detail: its own single-threaded actor beside
 * `ScoreRepositoryImpl` (never the follow machine, its meter, demand owners or the day list), fetching only the shown
 * game by `DetailPollPolicy`, LRU of `HUB_DETAIL_LRU`, paused while a clip plays, nothing with Show Sports off, the device
 * asleep, the app in the background or no network.
 *
 * Commit 0 stub: always [DetailState.None]; makes no request. Bound in `SportsModule`; WP-1 changes the constructor
 * freely (Hilt injects it).
 */
@Singleton
class GameDetailRepositoryImpl @Inject constructor() : GameDetailRepository {
    private val _detail = MutableStateFlow<DetailState>(DetailState.None)
    override val detail: StateFlow<DetailState> = _detail.asStateFlow()

    override fun show(game: GameSnapshot?) = Unit
    override fun pause(paused: Boolean) = Unit
    override fun retry() = Unit
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> = Fetch.Failed(FailKind.HTTP_OTHER, null, 0L)
    override fun trim() = Unit
}
