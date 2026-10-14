package app.thebox.data.guide

import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3): in memory like `InMemoryDataSettings`; M5 persists. */
@Singleton
class InMemoryGuideSettings @Inject constructor() : GuideSettingsSource {
    override val settings: StateFlow<GuideSettings> get() = TODO("WP-B")
    override fun update(transform: (GuideSettings) -> GuideSettings): Unit = TODO("WP-B")
}
