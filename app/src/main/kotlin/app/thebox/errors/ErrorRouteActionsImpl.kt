package app.thebox.errors

import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.full.ErrorRouteActions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Error-card buttons that act outside the player (docs/design/m5-setup-settings.md §3.14): secure DNS, refresh, the
 * external hand-off, Back to Auto. In `:app` because it needs `:data` (settings, `RefreshScheduler`) and `:player`.
 * **Commit-0 stub (WP-C replaces the body):** does nothing (no card offers these buttons before WP-C's ErrorCardModel).
 */
class ErrorRouteActionsImpl @Inject constructor() : ErrorRouteActions {
    override fun run(button: ErrorButton, card: ErrorCardState) = Unit
}

/** WP-C's `:app` binding. Commit 0 binds the stub; WP-C owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ErrorRoutesModule {
    @Binds abstract fun errorRouteActions(impl: ErrorRouteActionsImpl): ErrorRouteActions
}
