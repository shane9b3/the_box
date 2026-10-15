package app.thebox.ui.full.vm

import androidx.lifecycle.ViewModel
import androidx.media3.common.text.Cue
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerStateMachineFactory
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.guide.DeviceClassSource
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.player.api.PlaybackController
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.full.PlayerHost
import app.thebox.ui.root.ShellEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.10.1): the player's glue. Creates the channel list's rows cache and the
 * machine (with [PlayerEffectsImpl]), registers it with the probe and forwards controller, archive, library and settings
 * feeds as `PlayerEvent`s. Injects only `:core`/`:player`/`:ui` types (M3 B7). Not created by MainActivity until WP-D
 * wires it (the stub's machine would throw).
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val archive: ArchivePlayback,
    private val feed: ProgrammeFeed,
    private val library: GuideLibrary,
    private val rowsFactory: GuideRowsCacheFactory,
    private val playerSettings: PlayerSettingsSource,
    private val guideSettings: GuideSettingsSource,
    private val deviceClass: DeviceClassSource,
    private val machineFactory: PlayerStateMachineFactory,
    private val probe: PlayerDebugProbe,
) : ViewModel(), PlayerHost {
    override val machine: PlayerStateMachine get() = TODO("WP-D")
    override val listRows: GuideRows get() = TODO("WP-D")
    override fun nowOf(row: GuideChannel): ProgrammeInfo? = TODO("WP-D")
    override val cues: StateFlow<List<Cue>> get() = TODO("WP-D")
    override val events: Flow<ShellEvent> get() = TODO("WP-D")
}
