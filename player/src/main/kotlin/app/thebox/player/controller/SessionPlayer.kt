package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** `ForwardingSimpleBasePlayer` given to the `MediaSession` (§3.10.4). */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player) : ForwardingSimpleBasePlayer(player)
