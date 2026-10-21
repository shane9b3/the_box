package app.thebox.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A remote may send no repeated OK events at all. Focus loss and cancelled presses never activate a card. */
internal class SearchOkGesture(
    private val scope: CoroutineScope,
    private val onClick: () -> Unit,
    private val onLongClick: () -> Unit,
) {
    private var held = false
    private var longFired = false
    private var timer: Job? = null

    fun down(repeated: Boolean, canceled: Boolean = false) {
        if (canceled) { cancel(); return }
        if (repeated) return // the timer, not hardware repeat cadence, determines a long press
        if (held) return
        held = true
        longFired = false
        timer = scope.launch {
            delay(LONG_PRESS_MS)
            if (held && !longFired) {
                longFired = true
                onLongClick()
            }
        }
    }

    fun up(canceled: Boolean = false) {
        val click = held && !longFired && !canceled
        cancel()
        if (click) onClick()
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        held = false
        longFired = false
    }

    companion object { const val LONG_PRESS_MS = 500L }
}
