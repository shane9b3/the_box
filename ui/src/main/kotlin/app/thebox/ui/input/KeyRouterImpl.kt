package app.thebox.ui.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.input.KeyGestureDetector
import app.thebox.core.input.KeyMap

/** WP-D stub (docs/design/m3-guide.md §1.5, §2.10, §3.18.1): the one key path. */
class KeyRouterImpl(
    private val keyMap: KeyMap,
    private val detector: KeyGestureDetector,
    private val capabilities: KeyCapabilities,
) : KeyRouter {
    override fun onKey(event: KeyEvent): Boolean = TODO("WP-D")
    override fun onFocusLost(): Unit = TODO("WP-D")
    override fun attach(machine: GuideStateMachine?, searchFieldIdle: () -> Boolean): Unit = TODO("WP-D")
    override fun sinkModifier(requester: FocusRequester): Modifier = TODO("WP-D")
}
