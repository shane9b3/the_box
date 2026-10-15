package app.thebox.data.player

import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.data.repo.PlaylistRepository
import javax.inject.Inject

/**
 * WP-C stub (docs/design/m4-player-ux.md §1.4, §0.6): `playlist.catchup_probe_winner` over [playlists], written only
 * through the column-targeted `PlaylistDao.setCatchupProbeWinner` (never the whole-row update, M2 N-L13).
 */
class CatchupProbeStoreImpl @Inject constructor(private val playlists: PlaylistRepository) : CatchupProbeStore {
    override suspend fun winner(playlistId: Long): String? = TODO("WP-C")
    override suspend fun setWinner(playlistId: Long, variant: String): Unit = TODO("WP-C")
}
