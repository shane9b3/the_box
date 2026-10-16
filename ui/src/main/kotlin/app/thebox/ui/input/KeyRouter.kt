// Contract (M3 commit 0): docs/design/m3-guide.md §2.10. Verbatim; changes go through the lead.
package app.thebox.ui.input

/**
 * The one key path of the app: Compose KeyEvent → KeyInput → KeyGestureDetector → KeyMap → GuideEvent.Act/Release,
 * dispatched to the current GuideStateMachine. Returns true for every key it handled (including the matching KeyUp).
 * In ScreenMode.SEARCH it returns false for every key except BACK while the search field is empty and the IME is hidden.
 * (M5, §2.12 item 5, S3) While `GuideStateMachine.textInputActive` (the settings editor is open) it returns false for
 * every key except BACK/ESCAPE/BUTTON_B, which it consumes and dispatches as `SettingsEvent.EditorBack`.
 */
interface KeyRouter {
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean
    /** The sink lost focus: detector.reset() (Compose drops unmatched KeyUps). */
    fun onFocusLost()
    fun attach(machine: app.thebox.core.guide.GuideStateMachine?, searchFieldIdle: () -> Boolean)
    /**
     * (code review 2026-10-14, H-S5) Detaches [machine] only if it is still the attached one: an old view model's
     * `onCleared` can run after the new activity's view model attached its machine.
     */
    fun detach(machine: app.thebox.core.guide.GuideStateMachine) {
        attach(null) { true }
    }
    /**
     * The key sink ("Modifier.boxKeySink" elsewhere in this document) = focusRequester(requester)
     * .onFocusChanged { if (!it.isFocused) onFocusLost() }.onPreviewKeyEvent(::onKey).focusable(). No layer, alpha or clip.
     */
    fun sinkModifier(requester: androidx.compose.ui.focus.FocusRequester): androidx.compose.ui.Modifier

    /**
     * (M5, docs/design/m5-setup-settings.md §2.12 item 5; reviewed 2026-10-16, S14) Button-mapping capture: while armed
     * the router delivers the next DOWN with repeat 0 of any key to [listener] instead of the key map, consumes that DOWN
     * and its UP, and ignores repeats. After the capture it swallows every event of the captured code until its UP, even
     * after disarm; focus loss disarms. BACK/ESCAPE/BUTTON_B are delivered too (the screen treats them as "cancel").
     * null disarms. Commit 0: no-op default; WP-C implements it in KeyRouterImpl.
     */
    fun capture(listener: ((app.thebox.core.input.CapturedKey) -> Unit)?) = Unit
}
