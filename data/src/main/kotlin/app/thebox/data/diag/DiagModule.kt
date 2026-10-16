package app.thebox.data.diag

import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.LogRing
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** WP-E's `:data` bindings (docs/design/m5-setup-settings.md §2.13). Commit 0 binds the stubs; WP-E owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagModule {
    @Binds @Singleton abstract fun diagnostics(impl: DiagnosticsImpl): Diagnostics
    @Binds @Singleton abstract fun logRing(impl: LogRingImpl): LogRing
}
