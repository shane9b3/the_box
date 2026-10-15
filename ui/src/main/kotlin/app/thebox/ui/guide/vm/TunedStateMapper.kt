package app.thebox.ui.guide.vm

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.PlaybackState

/** `PlaybackState` → `TunedState` (docs/design/m3-guide.md §3.16.2). Pure. */
object TunedStateMapper {
    /**
     * [rowId] = `channel.id` of the tuned identity in the library (−1 unknown); [still] = `settings.miniStill ||
     * device.stillByDefault` (computed by the caller). (code review 2026-10-14, H-B1) Not `video.posterMode`: the
     * HDR poster keeps the viewport MINI and the shell draws it from `VideoInfo.posterMode` directly.
     */
    fun map(state: PlaybackState, rowId: Long, still: Boolean): TunedState {
        val identity = state.target?.identity
        var attempt = 0
        var max = 0
        val phase = when (state) {
            is PlaybackState.Idle -> MiniPhase.IDLE
            is PlaybackState.Connecting -> MiniPhase.CONNECTING
            is PlaybackState.Buffering -> if (state.cause == BufferingCause.RECONNECTING) {
                attempt = state.reconnect?.attempt ?: 0
                max = state.reconnect?.maxAttempts ?: 0
                MiniPhase.RECONNECTING
            } else {
                MiniPhase.BUFFERING
            }
            is PlaybackState.Playing -> if (state.paused) MiniPhase.PAUSED else MiniPhase.PLAYING
            is PlaybackState.Stalled -> {
                attempt = state.attempt
                max = state.maxAttempts
                MiniPhase.RECONNECTING
            }
            is PlaybackState.Stopped -> MiniPhase.STOPPED
            is PlaybackState.Error -> MiniPhase.ERROR
        }
        return TunedState(
            identity = identity,
            rowId = if (identity == null) -1 else rowId,
            identityHash = identity?.let(GuideChannel::hashOf) ?: 0L,
            phase = phase,
            isArchive = state.target?.isArchive ?: false,
            error = (state as? PlaybackState.Error)?.error?.code,
            reconnectAttempt = attempt,
            reconnectMax = max,
            still = still,
        )
    }
}
