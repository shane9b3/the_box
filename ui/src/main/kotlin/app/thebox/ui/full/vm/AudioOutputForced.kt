package app.thebox.ui.full.vm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * (U-S12, code review 2026-10-16) Whether `playback.audioOutput` is "Always passthrough" (B2): the player's AUDIO picker
 * then offers Back to Auto (`TrackMenu.audioOutputForced`) and the stats overlay's audio line says so. Bound in
 * `SettingsUiModule` from the settings store; [NONE] for view-model tests that predate it.
 */
fun interface AudioOutputForced {
    fun flow(): Flow<Boolean>

    companion object {
        val NONE = AudioOutputForced { flowOf(false) }
    }
}
