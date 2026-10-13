package app.thebox.data.repo

import app.thebox.core.common.Hash64
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.dao.ReminderDao
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Programme reminders. Durable key = (playlist_id, stable_key, RAW start minute, Hash64(title)) (§2.6):
 * never an epg.db row id, so a reminder survives every EPG rebuild.
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val clock: WallClock,
) {
    /** False when a reminder for that airing already exists. */
    suspend fun add(
        channel: ChannelIdentity, rawStartMin: Int, rawEndMin: Int, title: String, channelName: String, leadSec: Int = 60,
    ): Boolean = dao.insert(
        ReminderEntity(
            playlistId = channel.playlistId, stableKey = channel.stableKey.value, startMin = rawStartMin, endMin = rawEndMin,
            titleHash = Hash64.of(title), title = title, channelName = channelName, leadSec = leadSec, createdAt = clock.now(),
        ),
    ) != -1L

    suspend fun remove(channel: ChannelIdentity, rawStartMin: Int) = dao.delete(channel.playlistId, channel.stableKey.value, rawStartMin)

    suspend fun exists(channel: ChannelIdentity, rawStartMin: Int): Boolean = dao.get(channel.playlistId, channel.stableKey.value, rawStartMin) != null

    fun pending(fromMin: Int = (clock.now() / 60_000).toInt()): Flow<List<ReminderEntity>> = dao.observePending(fromMin)

    suspend fun due(nowMin: Int = (clock.now() / 60_000).toInt()): List<ReminderEntity> = dao.due(nowMin)

    suspend fun markFired(id: Long) = dao.setState(id, ReminderEntity.STATE_FIRED)
    suspend fun dismiss(id: Long) = dao.setState(id, ReminderEntity.STATE_DISMISSED)
    suspend fun markOrphaned(id: Long) = dao.setState(id, ReminderEntity.STATE_ORPHANED)
}
