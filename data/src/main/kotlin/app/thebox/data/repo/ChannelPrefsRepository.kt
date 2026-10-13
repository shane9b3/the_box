package app.thebox.data.repo

import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.EntryKind
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Per-channel and per-group user settings, keyed by identity, never by row ids (§2.1.1). */
@Singleton
class ChannelPrefsRepository @Inject constructor(
    private val dao: ChannelPrefsDao,
    private val groupDao: GroupDao,
    private val clock: WallClock,
) {
    suspend fun get(c: ChannelIdentity): ChannelPrefsEntity? = dao.get(c.playlistId, c.stableKey.value)

    fun observe(c: ChannelIdentity): Flow<ChannelPrefsEntity?> = dao.observe(c.playlistId, c.stableKey.value)

    /** Read-modify-write of one channel's prefs (creates the row on first use). */
    suspend fun edit(c: ChannelIdentity, change: (ChannelPrefsEntity) -> ChannelPrefsEntity) {
        val cur = dao.get(c.playlistId, c.stableKey.value) ?: ChannelPrefsEntity(c.playlistId, c.stableKey.value, updatedAt = clock.now())
        dao.upsert(change(cur).copy(playlistId = c.playlistId, stableKey = c.stableKey.value, updatedAt = clock.now()))
    }

    suspend fun setHidden(c: ChannelIdentity, hidden: Boolean) = edit(c) { it.copy(hidden = hidden) }

    /** null = automatic (adult detection). */
    suspend fun setLocked(c: ChannelIdentity, locked: Boolean?) = edit(c) { it.copy(locked = locked) }

    suspend fun setEpgShift(c: ChannelIdentity, minutes: Int?) = edit(c) { it.copy(epgShiftMin = minutes) }

    suspend fun recordWatched(c: ChannelIdentity) = dao.recordWatched(c.playlistId, c.stableKey.value, clock.now())

    fun recent(limit: Int = 20): Flow<List<ChannelPrefsEntity>> = dao.recent(limit)

    suspend fun editGroup(playlistId: Long, kind: EntryKind, groupKey: String, change: (GroupPrefsEntity) -> GroupPrefsEntity) {
        val k = RoomMergeSink.groupKind(kind)
        val cur = groupDao.prefs(playlistId, k, groupKey) ?: GroupPrefsEntity(playlistId, k, groupKey, updatedAt = clock.now())
        groupDao.upsertPrefs(change(cur).copy(playlistId = playlistId, kind = k, groupKey = groupKey, updatedAt = clock.now()))
    }
}
