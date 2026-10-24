package app.thebox.settings

import app.thebox.core.art.ProgrammeArtwork
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.WpaPageEnv
import app.thebox.core.settings.state.WpaPages
import app.thebox.core.settings.state.StoragePageEnv
import app.thebox.core.settings.state.StoragePages
import app.thebox.core.settings.state.StorageTexts
import app.thebox.core.settings.state.TimeshiftPlaceSource
import app.thebox.core.settings.state.TimeshiftPlaces
import app.thebox.core.setup.lan.PhoneContextProvider
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.storage.FallbackToastGate
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.VolumeCatalog
import app.thebox.playback.FallbackToastPolicy
import app.thebox.playback.FileSignInToastCounters
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
    @Binds abstract fun storagePageEnv(impl: StoragePageEnvImpl): StoragePageEnv
    @Binds @IntoSet abstract fun channelOptions(impl: ChannelOptionsPageSource): SettingsPageSource

    companion object {
        /** (WP-S4) The texts of the Save to pages and the network-folder walk (`strings_storage.xml`). */
        @Provides @Singleton fun storageTexts(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): StorageTexts =
            ResourceStorageTexts(context.resources)

        /** (WP-S4, storage locations section 9.1) Where rewind saves, for the Playback link and the Pause and rewind page. */
        @Provides @Singleton fun timeshiftPlaces(locations: StorageLocations, volumes: VolumeCatalog, texts: StorageTexts): TimeshiftPlaceSource =
            TimeshiftPlaces(locations, volumes, texts)

        @Provides @Singleton fun wpaPages(store: SettingsStore, texts: SettingsTexts, env: WpaPageEnv, artwork: ProgrammeArtwork, places: TimeshiftPlaceSource): WpaPages =
            WpaPages(store, texts, env, artwork, places)

        @Provides @IntoSet fun wpaPagesSource(pages: WpaPages): SettingsPageSource = pages

        /** (WP-S4, section 9.2, 17) The pages of Pause and rewind, Save to, a stored location and the folder walk. */
        @Provides @Singleton fun storagePages(
            store: SettingsStore, texts: SettingsTexts, storageTexts: StorageTexts, wpa: WpaPages, wpaEnv: WpaPageEnv, places: TimeshiftPlaceSource,
            locations: StorageLocations, folders: NetworkFolders, volumes: VolumeCatalog, phone: PhoneLink, env: StoragePageEnv,
        ): StoragePages = StoragePages(store, texts, storageTexts, wpa, wpaEnv, places, locations, folders, volumes, phone, env)

        @Provides @IntoSet fun storagePagesSource(pages: StoragePages): SettingsPageSource = pages

        /** (section 7.4) What a NETWORK_FOLDER phone page knows: the host, the share and the listed shares, never a credential. */
        @Provides fun phoneContext(pages: StoragePages): PhoneContextProvider = pages

        /** (section 5.1, 10) The once-per-process / three-processes rules of the "saving on this device" toast. */
        @Provides @Singleton fun fallbackToasts(counters: FileSignInToastCounters): FallbackToastGate = FallbackToastPolicy(counters)
    }
}
