// Contract (M3 commit 0): docs/design/m3-guide.md §2.10. Verbatim; changes go through the lead.
package app.thebox.ui.input

/**
 * The one key path of the app: Compose KeyEvent → KeyInput → KeyGestureDetector → KeyMap → GuideEvent.Act/Release,
 * dispatched to the current GuideStateMachine. Returns true for every key it handled (including the matching KeyUp).
 * In ScreenMode.SEARCH it returns false for every key except BACK while the search field is empty and the IME is hidden.
 */
interface KeyRouter {
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean
    /** The sink lost focus: detector.reset() (Compose drops unmatched KeyUps). */
    fun onFocusLost()
    fun attach(machine: app.thebox.core.guide.GuideStateMachine?, searchFieldIdle: () -> Boolean)
    /**
     * The key sink ("Modifier.boxKeySink" elsewhere in this document) = focusRequester(requester)
     * .onFocusChanged { if (!it.isFocused) onFocusLost() }.onPreviewKeyEvent(::onKey).focusable(). No layer, alpha or clip.
     */
    fun sinkModifier(requester: androidx.compose.ui.focus.FocusRequester): androidx.compose.ui.Modifier
}
