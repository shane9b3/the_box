package app.thebox.data.player

import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerSettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Player settings held in memory like `InMemoryGuideSettings` (docs/design/m4-player-ux.md §1.4, §0.12); M5 persists them. */
@Singleton
class InMemoryPlayerSettings @Inject constructor() : PlayerSettingsSource {
    private val state = MutableStateFlow(PlayerSettings())
    override val settings: StateFlow<PlayerSettings> = state.asStateFlow()
    override fun update(transform: (PlayerSettings) -> PlayerSettings) = state.update(transform)
}
