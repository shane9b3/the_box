package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** `ForwardingPlayer` given to `PlayerSurface`; exposes only the surface commands and captures the `SurfaceView` (§2.6). */
@OptIn(UnstableApi::class)
internal class SurfacePlayer(player: Player) : ForwardingPlayer(player)
