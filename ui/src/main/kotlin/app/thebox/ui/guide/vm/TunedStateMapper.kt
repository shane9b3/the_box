package app.thebox.ui.guide.vm

import app.thebox.core.guide.TunedState
import app.thebox.player.api.PlaybackState

/** WP-C stub (docs/design/m3-guide.md §1.4, §3.16.2): `PlaybackState` → `TunedState`. */
object TunedStateMapper {
    fun map(state: PlaybackState, rowId: Long, still: Boolean): TunedState = TODO("WP-C")
}
