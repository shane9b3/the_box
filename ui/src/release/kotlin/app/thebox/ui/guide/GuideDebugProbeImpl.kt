package app.thebox.ui.guide

import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideStateMachine

/** Release builds (docs/design/m3-guide.md §1.5, review B7): a no-op; nothing holds the machine. */
class GuideDebugProbeImpl : GuideDebugProbe {
    override val current: GuideStateMachine? get() = null
    override fun register(machine: GuideStateMachine?) = Unit
}
