package app.thebox.update

import app.thebox.core.update.UpdateActions
import app.thebox.core.update.UpdateCardSource
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.Updater
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/** WP-E's `:app` bindings (docs/design/m5-setup-settings.md §2.13 UpdateModule). */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds @Singleton abstract fun updater(impl: UpdaterImpl): Updater
    @Binds abstract fun updateCards(impl: UpdaterImpl): UpdateCardSource
    @Binds abstract fun updateActions(impl: UpdateActionsImpl): UpdateActions
    @Binds abstract fun updateScheduler(impl: UpdateCheckSchedulerImpl): UpdateCheckScheduler

    /** Empty in release builds; the debug source set contributes the debug endpoint (S7). */
    @Multibinds abstract fun endpointOverrides(): Set<UpdateEndpointOverride>
}
