package app.thebox.ui.sports

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * (NFL fix pass U3) Robolectric runs no draw pass, so "the draw node is invalidated" is checked through the snapshot
 * system: the states a draw reads ([statesReadBy]) against the states a step wrote ([statesWrittenBy]). A draw node is
 * invalidated exactly when a state it read is written.
 */
internal object DrawInvalidation {
    /** The snapshot states [block] reads (run it as the draw would). */
    fun statesReadBy(block: () -> Unit): Set<Any> {
        val reads = HashSet<Any>()
        Snapshot.observe(readObserver = { reads += it }) { block() }
        return reads
    }

    /** The snapshot states written (with a changed value) in the global snapshot while [block] runs. */
    fun statesWrittenBy(block: () -> Unit): Set<Any> {
        val writes = HashSet<Any>()
        val handle = Snapshot.registerGlobalWriteObserver { writes += it }
        try {
            block()
        } finally {
            handle.dispose()
        }
        return writes
    }
}

/** A dispatcher the test runs by hand, so each background hop of a bake lands in its own step. */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }

    /** Runs the oldest queued block on the calling thread; false when none is queued. */
    fun runOne(): Boolean {
        val r = synchronized(queue) { queue.removeFirstOrNull() } ?: return false
        r.run()
        return true
    }
}
