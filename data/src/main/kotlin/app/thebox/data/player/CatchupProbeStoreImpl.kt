package app.thebox.data.player

import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.common.BoxLog
import app.thebox.data.repo.PlaylistRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * `playlist.catchup_probe_winner` over [playlists] (docs/design/m4-player-ux.md §1.4, §0.6, §3.8.8), written only through
 * the column-targeted `PlaylistDao.setCatchupProbeWinner` (never the whole-row update, M2 N-L13).
 *
 * Best effort: the winner only reorders the probe list, so a failed read means "no winner" and a failed write is logged
 * and dropped; neither reaches the archive session as an exception. Room's suspend DAO calls run on its own executor, so
 * both are main-safe.
 */
class CatchupProbeStoreImpl @Inject constructor(private val playlists: PlaylistRepository) : CatchupProbeStore {
    override suspend fun winner(playlistId: Long): String? = try {
        playlists.probeWinner(playlistId)?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BoxLog.w(TAG, "probe winner unavailable: ${e.javaClass.simpleName}")
        null
    }

    override suspend fun setWinner(playlistId: Long, variant: String) {
        try {
            playlists.setProbeWinner(playlistId, variant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "probe winner not saved: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "CatchupProbeStore"
    }
}
