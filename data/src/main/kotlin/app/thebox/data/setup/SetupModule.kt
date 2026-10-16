package app.thebox.data.setup

import app.thebox.core.setup.SourceSetup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** WP-B's `:data` bindings (docs/design/m5-setup-settings.md §2.13). Commit 0 binds the stub; WP-B owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SetupModule {
    @Binds @Singleton abstract fun sourceSetup(impl: SourceSetupImpl): SourceSetup
}
