package app.thebox.data.guide

import app.thebox.core.catchup.CatchupResolverImpl
import app.thebox.core.epg.EpgStore
import app.thebox.core.text.ChannelNamePrefixImpl
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.FavouritesRepository
import app.thebox.data.repo.ReminderRepository
import app.thebox.data.repo.writeTransaction
import app.thebox.data.reminder.ReminderBookImpl
import app.thebox.data.testutil.TestGraph
import kotlinx.coroutines.Dispatchers

/** The WP-B guide graph over a [TestGraph] (real Room on Robolectric, fakes for the network). */
class GuideGraph(val g: TestGraph, store: EpgStore = g.store) {
    val db: LibraryDatabase = g.db
    val catchup = CatchupResolverImpl()
    val refs = EpgRefResolver(db, db.epgSourceDao(), db.playlistDao(), g.settings)
    val queries = GuideQueries(db, g.playlists, catchup, refs)
    val favourites = FavouritesRepository(db.favouriteDao(), g.clock)
    val channelRepo = ChannelRepository(db, db.channelDao(), db.groupDao(), g.playlists, catchup)
    val library = GuideLibraryImpl(
        db, queries, db.playlistDao(), db.groupDao(), db.favouriteDao(), favourites, db.channelPrefsDao(), channelRepo, g.clock, Dispatchers.IO,
    )
    val prefix = ChannelNamePrefixImpl()
    val displayNames = DisplayNames(db, db.playlistDao(), prefix, g.importMutex, Dispatchers.IO)
    val search = GuideSearchImpl(queries, store, Dispatchers.IO)
    val reminders by lazy { ReminderBookImpl(ReminderRepository(db.reminderDao(), g.clock), g.scope, Dispatchers.IO) }
}

suspend fun LibraryDatabase.exec(sql: String, vararg args: Any?) = writeTransaction {
    usePrepared(sql) { st ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                is Boolean -> st.bindLong(i + 1, if (a) 1 else 0)
                is String -> st.bindText(i + 1, a)
                is Double -> st.bindDouble(i + 1, a)
                else -> error("bad arg $a")
            }
        }
        st.step()
    }
}

suspend fun LibraryDatabase.newSource(name: String = "src", priority: Int = 0, playlistId: Long? = null, shiftMin: Int = 0, enabled: Boolean = true): Long =
    epgSourceDao().insert(
        EpgSourceEntity(
            name = name, origin = EpgSourceEntity.ORIGIN_USER, playlistId = playlistId, credentialRef = "r-$name", urlHash = name.hashCode().toLong(),
            displaySource = "h", priority = priority, enabled = enabled, shiftMin = shiftMin, createdAt = 1,
        ),
    )

suspend fun LibraryDatabase.match(playlistId: Long, key: String, sourceId: Long, xmltvId: String, hasData: Boolean = true, status: Int = 0) =
    epgMatchDao().insertAll(listOf(EpgMatchEntity(playlistId, key, sourceId, xmltvId, status, "EXACT_ID", 1f, hasData, 0L, 1L)))

suspend fun LibraryDatabase.map(playlistId: Long, key: String, sourceId: Long?, xmltvId: String?) =
    epgMappingDao().upsert(EpgMappingEntity(playlistId, key, sourceId, xmltvId, 1L))
