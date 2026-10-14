package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.TimeLabels
import kotlinx.coroutines.flow.StateFlow

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.4, §3.9): zones, key tables, back ladder, panel debounce, data events. */
class GuideStateMachineImpl(
    private val effects: GuideEffects,
    private val navigator: GuideNavigator,
    private val labels: TimeLabels,
    private val renderer: GuideRenderer,
    private val nowMs: () -> Long,
) : GuideStateMachine {
    override val ui: StateFlow<GuideUiState> get() = TODO("WP-D")
    override val frame: GuideFrame get() = TODO("WP-D")
    override val frameVersion: StateFlow<Long> get() = TODO("WP-D")
    override fun dispatch(event: GuideEvent): Unit = TODO("WP-D")

    companion object {
        val FACTORY = GuideStateMachineFactory { effects, navigator, labels, renderer, nowMs ->
            GuideStateMachineImpl(effects, navigator, labels, renderer, nowMs)
        }
    }
}
