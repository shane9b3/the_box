package app.thebox.player.probe

import app.thebox.core.setup.StreamProbe
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** WP-B's `:player` binding (docs/design/m5-setup-settings.md §2.13). Commit 0 binds the stub; WP-B owns this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProbeModule {
    @Binds @Singleton abstract fun streamProbe(impl: StreamProbeImpl): StreamProbe
}
