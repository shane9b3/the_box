package app.thebox.ui.sports

import app.thebox.core.sports.ScoreRepository
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
}
