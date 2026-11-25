package app.thebox.ui.sports

import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.GameChannelMatcher
import app.thebox.core.sports.hub.GameDetailRepository
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §9) `ScoreRepository` is an optional binding: `:ui` (the player VM,
 * WP-4a's Sports view model) injects `Optional<ScoreRepository>`, which is present once WP-2's `SportsModule` (`:data`)
 * binds `ScoreRepositoryImpl`. Until then the app has no score feature and makes no request.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SportsUiModule {
    @BindsOptionalOf abstract fun scoreRepository(): ScoreRepository

    // (Sports Hub commit 0, docs/design/m6-sports-hub.md §11) The same pattern for the hub's seams: `:data`'s SportsModule
    // binds them, so `Optional<…>` is present in the app and absent in `:ui`-only graphs.
    @BindsOptionalOf abstract fun gameDetailRepository(): GameDetailRepository

    @BindsOptionalOf abstract fun clipResolver(): ClipResolver

    @BindsOptionalOf abstract fun gameChannelMatcher(): GameChannelMatcher
}
