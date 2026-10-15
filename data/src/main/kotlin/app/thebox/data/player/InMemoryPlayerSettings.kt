package app.thebox.data.player

import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerSettingsSource
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** WP-C stub (docs/design/m4-player-ux.md §1.4, §0.12): player settings in memory like `InMemoryGuideSettings`; M5 persists them. */
@Singleton
class InMemoryPlayerSettings @Inject constructor() : PlayerSettingsSource {
    override val settings: StateFlow<PlayerSettings> get() = TODO("WP-C")
    override fun update(transform: (PlayerSettings) -> PlayerSettings): Unit = TODO("WP-C")
}
