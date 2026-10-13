package app.thebox.data.repo

import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.dao.ChannelRow
import app.thebox.data.db.dao.FavouriteDao
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Favourite lists keyed by `(playlist_id, stable_key)`: they survive refreshes, purges and re-orders. */
@Singleton
class FavouritesRepository @Inject constructor(
    private val dao: FavouriteDao,
    private val clock: WallClock,
) {
    fun lists(): Flow<List<FavouriteListEntity>> = dao.observeLists()

    suspend fun createList(name: String): Long {
        val sort = (dao.lists().maxOfOrNull { it.sortIndex } ?: -1) + 1
        return dao.insertList(FavouriteListEntity(name = name, sortIndex = sort, createdAt = clock.now()))
    }

    /** The default list, created on first use. */
    suspend fun defaultList(): Long = dao.lists().firstOrNull()?.id ?: createList(DEFAULT_NAME)

    suspend fun renameList(listId: Long, name: String) = dao.renameList(listId, name)
    suspend fun deleteList(listId: Long) = dao.deleteList(listId)

    suspend fun add(listId: Long, channel: ChannelIdentity): Boolean =
        dao.insert(
            FavouriteEntity(listId, channel.playlistId, channel.stableKey.value, dao.maxSortIndex(listId) + 1, clock.now()),
        ) != -1L

    suspend fun remove(listId: Long, channel: ChannelIdentity) = dao.delete(listId, channel.playlistId, channel.stableKey.value)

    /** Rewrites dense sort indexes in the given order. */
    suspend fun reorder(listId: Long, ordered: List<ChannelIdentity>) {
        ordered.forEachIndexed { i, c -> dao.setSortIndex(listId, c.playlistId, c.stableKey.value, i) }
    }

    suspend fun listsContaining(channel: ChannelIdentity): List<Long> = dao.listsOf(channel.playlistId, channel.stableKey.value).map { it.listId }

    /** Present favourites (missing channels hidden, data kept) with the parental predicate. */
    fun channels(listId: Long, unlocked: Boolean): Flow<List<ChannelRow>> = dao.channels(listId, unlocked)

    companion object {
        const val DEFAULT_NAME = "Favourites"
    }
}
