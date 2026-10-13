package app.thebox.data.di

import android.content.Context
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.db.dao.CredentialDao
import app.thebox.data.db.dao.EpgMappingDao
import app.thebox.data.db.dao.EpgMatchDao
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.FavouriteDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.db.dao.MaintenanceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.dao.ReminderDao
import app.thebox.data.db.dao.XtreamAccountDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/** The library.db singleton (BundledSQLiteDriver, WAL, IO query context) and its DAOs (§3.10). */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher): LibraryDatabase =
        LibraryDatabase.build(context, io)

    @Provides fun playlistDao(db: LibraryDatabase): PlaylistDao = db.playlistDao()
    @Provides fun credentialDao(db: LibraryDatabase): CredentialDao = db.credentialDao()
    @Provides fun xtreamAccountDao(db: LibraryDatabase): XtreamAccountDao = db.xtreamAccountDao()
    @Provides fun groupDao(db: LibraryDatabase): GroupDao = db.groupDao()
    @Provides fun channelDao(db: LibraryDatabase): ChannelDao = db.channelDao()
    @Provides fun favouriteDao(db: LibraryDatabase): FavouriteDao = db.favouriteDao()
    @Provides fun channelPrefsDao(db: LibraryDatabase): ChannelPrefsDao = db.channelPrefsDao()
    @Provides fun epgSourceDao(db: LibraryDatabase): EpgSourceDao = db.epgSourceDao()
    @Provides fun epgMappingDao(db: LibraryDatabase): EpgMappingDao = db.epgMappingDao()
    @Provides fun epgMatchDao(db: LibraryDatabase): EpgMatchDao = db.epgMatchDao()
    @Provides fun reminderDao(db: LibraryDatabase): ReminderDao = db.reminderDao()
    @Provides fun refreshLogDao(db: LibraryDatabase): RefreshLogDao = db.refreshLogDao()
    @Provides fun maintenanceDao(db: LibraryDatabase): MaintenanceDao = db.maintenanceDao()
}
