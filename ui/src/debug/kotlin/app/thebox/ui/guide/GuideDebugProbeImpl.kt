package app.thebox.ui.guide

import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideStateMachine

/**
 * Debug builds (docs/design/m3-guide.md §1.5, §5.5, review B7; owner WP-D): the live VM registers its machine here and
 * the instrumented suite reads it through a Hilt `@EntryPoint`. Commit 0 ships the plain holder.
 */
class GuideDebugProbeImpl : GuideDebugProbe {
    @Volatile override var current: GuideStateMachine? = null
        private set

    override fun register(machine: GuideStateMachine?) {
        current = machine
    }
}
