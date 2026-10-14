package app.thebox.data.guide

import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Guide settings held in memory like `InMemoryDataSettings` (docs/design/m3-guide.md §1.3); M5 persists them. */
@Singleton
class InMemoryGuideSettings @Inject constructor() : GuideSettingsSource {
    private val state = MutableStateFlow(GuideSettings())
    override val settings: StateFlow<GuideSettings> = state.asStateFlow()
    override fun update(transform: (GuideSettings) -> GuideSettings) = state.update(transform)
}
