package app.thebox.playback

// Commit-0 stub (docs/design/m2-playback-core.md §1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.repo.ChannelPrefsRepository
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.ChannelPlaybackMemory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChannelMemory] over `channel_prefs` (§2.4.2): reads the row, writes only the changed columns through the
 * column-targeted `ChannelPrefsRepository` setters (never `edit`, review S10), serialised by a Mutex.
 */
@Singleton
class RoomChannelMemory @Inject constructor(private val prefs: ChannelPrefsRepository) : ChannelMemory {
    override suspend fun read(identity: ChannelIdentity): ChannelPlaybackMemory = TODO("WP-D")
    override suspend fun update(identity: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory): Unit = TODO("WP-D")
    override suspend fun recordWatched(identity: ChannelIdentity): Unit = TODO("WP-D")
}
