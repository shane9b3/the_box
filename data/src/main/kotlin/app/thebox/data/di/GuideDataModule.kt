package app.thebox.data.di

import app.thebox.core.epg.EpgStore
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.guide.feed.GuideWindowPagerImpl
import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.ChannelNamePrefixImpl
import app.thebox.data.guide.GuideDataWindowSourceImpl
import app.thebox.data.guide.GuideLibraryImpl
import app.thebox.data.guide.GuideSearchImpl
import app.thebox.data.guide.GuideSessionStoreImpl
import app.thebox.data.guide.SyncFeedImpl
import app.thebox.data.reminder.ReminderBookImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Guide feeds (docs/design/m3-guide.md §1.3; owner WP-B). Commit 0 binds the stubs so `:app` compiles; nothing is
 * constructed at app start until WP-C wires the guide.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuideDataModule {
    @Binds abstract fun library(impl: GuideLibraryImpl): GuideLibrary
    @Binds abstract fun search(impl: GuideSearchImpl): GuideSearch
    @Binds abstract fun reminderBook(impl: ReminderBookImpl): ReminderBook
    @Binds abstract fun sessionStore(impl: GuideSessionStoreImpl): GuideSessionStore
    // (M5, WP-A) GuideSettingsSource → PersistedGuideSettings, bound in data/.../settings/SettingsDataModule.
    @Binds abstract fun syncFeed(impl: SyncFeedImpl): SyncFeed
    @Binds abstract fun dataWindow(impl: GuideDataWindowSourceImpl): GuideDataWindowSource

    companion object {
        @Provides fun rowsCacheFactory(): GuideRowsCacheFactory = GuideRowsCacheFactory { library, scope -> GuideRowsCacheImpl(library, scope) }

        @Provides fun windowPagerFactory(store: EpgStore): GuideWindowPagerFactory =
            GuideWindowPagerFactory { rows, metrics, measurer, builder, labels -> GuideWindowPagerImpl(store, rows, metrics, measurer, builder, labels) }

        @Provides @Singleton fun channelNamePrefix(): ChannelNamePrefix = ChannelNamePrefixImpl()
    }
}
