package app.thebox.ui.guide.vm

import androidx.lifecycle.ViewModel
import app.thebox.core.guide.GuideStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * WP-C stub (docs/design/m3-guide.md §1.4, §3.16.1): glue. Owns the coroutine scopes, maps `PlaybackState` to
 * `TunedState`, implements the state machine's effects ([GuideEffectsImpl]).
 */
@HiltViewModel
class GuideViewModel @Inject constructor() : ViewModel() {
    val machine: GuideStateMachine get() = TODO("WP-C")
}
