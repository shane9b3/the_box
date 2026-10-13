package app.thebox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import app.thebox.data.db.entity.ChannelEntity
import app.thebox.data.db.entity.ChannelFtsEntity
import app.thebox.data.db.entity.ChannelGroupEntity
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.CredentialEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.db.entity.PurgedKeyEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.db.entity.StreamSourceEntity
import app.thebox.data.db.entity.XtreamAccountEntity
import kotlinx.coroutines.CoroutineDispatcher

/**
 * library.db (docs/design/m1-data-layer.md §2.1). Room 2.8.5 on the bundled SQLite driver, WAL.
 * No open callback: Room's open delegate enables foreign keys on every pooled connection (§2.1.3, S2).
 * Migration policy: [Migrations].
 */
@Database(
    version = LibraryDatabase.VERSION,
    exportSchema = true,
    entities = [
        PlaylistEntity::class,
        CredentialEntity::class,
        XtreamAccountEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        ChannelFtsEntity::class,
        StreamSourceEntity::class,
        GroupPrefsEntity::class,
        ChannelPrefsEntity::class,
        FavouriteListEntity::class,
        FavouriteEntity::class,
        EpgSourceEntity::class,
        EpgMappingEntity::class,
        EpgMatchEntity::class,
        ReminderEntity::class,
        RefreshLogEntity::class,
        PurgedKeyEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun credentialDao(): CredentialDao
    abstract fun xtreamAccountDao(): XtreamAccountDao
    abstract fun groupDao(): GroupDao
    abstract fun channelDao(): ChannelDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun channelPrefsDao(): ChannelPrefsDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun epgMappingDao(): EpgMappingDao
    abstract fun epgMatchDao(): EpgMatchDao
    abstract fun reminderDao(): ReminderDao
    abstract fun refreshLogDao(): RefreshLogDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val VERSION = 1
        const val NAME = "library.db"

        /** Production builder (§2.1.3). Tests pass `AndroidSQLiteDriver` (Robolectric) instead. */
        fun build(
            context: Context,
            io: CoroutineDispatcher,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            name: String? = NAME,
        ): LibraryDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            } else {
                Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            }
            return builder
                .setDriver(driver)
                .setQueryCoroutineContext(io)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}
