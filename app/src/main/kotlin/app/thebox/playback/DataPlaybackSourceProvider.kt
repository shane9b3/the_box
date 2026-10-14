package app.thebox.playback

// Commit-0 stub (docs/design/m2-playback-core.md §1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import app.thebox.core.common.Outcome
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.player.api.PlaybackSourceProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackSourceProvider] over `:data` (§2.4.2). Learned values go through the column-targeted `PlaylistDao` writes
 * (`setActiveBaseIndex`, `setXtreamOutputLearned`), never the whole-row `PlaylistRepository.update` (review S10).
 */
@Singleton
class DataPlaybackSourceProvider @Inject constructor(
    private val channels: ChannelRepository,
    private val playlists: PlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val xtream: XtreamApi,
) : PlaybackSourceProvider {
    override suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo? = TODO("WP-D")
    override suspend fun playlist(playlistId: Long): PlaylistPlaybackInfo? = TODO("WP-D")
    override suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?> = TODO("WP-D")
    override suspend fun account(playlistId: Long): XtreamAccountInfo? = TODO("WP-D")
    override suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo> = TODO("WP-D")
    override suspend fun saveActiveBaseIndex(playlistId: Long, index: Int): Unit = TODO("WP-D")
    override suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?): Unit = TODO("WP-D")
    override suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity> = TODO("WP-D")
}
