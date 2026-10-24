package app.thebox.settings

import app.thebox.core.art.ProgrammeArtwork
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.WpaPageEnv
import app.thebox.core.settings.state.WpaPages
import app.thebox.player.api.PlaybackSettingsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * WP-A's `:app` bindings (docs/design/m5-setup-settings.md §2.13 `SettingsBindingsModule`): the persisted
 * `PlaybackSettingsSource` (replaces M2's in-memory one; `defaultUserAgent` stays out of the store), the
 * `LiveSettingsApplier` (S9) and WP-A's page sources (`Set<SettingsPageSource>`, declared in `:ui`'s
 * `SettingsUiModule`): the TV guide / Playback / Interface / Remote / Network sections and Channel options.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindingsModule {
    @Binds @Singleton abstract fun playbackSettings(impl: PersistedPlaybackSettings): PlaybackSettingsSource
    @Binds abstract fun liveSettingsApplier(impl: LiveSettingsApplierImpl): LiveSettingsApplier
    @Binds abstract fun pageEnv(impl: WpaPageEnvImpl): WpaPageEnv
    @Binds @IntoSet abstract fun channelOptions(impl: ChannelOptionsPageSource): SettingsPageSource

    companion object {
        @Provides @Singleton fun wpaPages(store: SettingsStore, texts: SettingsTexts, env: WpaPageEnv, artwork: ProgrammeArtwork): WpaPages = WpaPages(store, texts, env, artwork)

        @Provides @IntoSet fun wpaPagesSource(pages: WpaPages): SettingsPageSource = pages
        /**
         * (Storage locations commit 0, docs/design/m6-storage-locations.md §7.4) What a NETWORK_FOLDER phone page knows;
         * nothing until WP-S4 binds StoragePages here.
         */
        @Provides fun phoneContext(): app.thebox.core.setup.lan.PhoneContextProvider = app.thebox.core.setup.lan.PhoneContextProvider { null }
        /** (Storage locations commit 0, §10) No fallback toast is due until WP-S4 binds FallbackToastPolicy here. */
        @Provides fun fallbackToasts(): app.thebox.core.storage.FallbackToastGate = app.thebox.core.storage.FallbackToastGate { _, _ -> false }
    }
}
