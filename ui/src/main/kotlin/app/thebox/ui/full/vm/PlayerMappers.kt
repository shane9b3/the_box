package app.thebox.ui.full.vm

import app.thebox.core.full.StatsFacts
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.VideoFacts
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.VideoInfo

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.10.1, §3.6.3): `:player` types → the `:core` mirrors the player machine
 * reads (incl. `TextChoice.speculative` for CEA tracks without a descriptor).
 */
object PlayerMappers {
    fun trackMenu(info: TrackInfo): TrackMenu = TODO("WP-D")
    fun videoFacts(info: VideoInfo): VideoFacts = TODO("WP-D")
    fun statsFacts(stats: StreamStats): StatsFacts = TODO("WP-D")
}
