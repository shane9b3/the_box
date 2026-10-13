package app.thebox.core.common

import kotlin.coroutines.cancellation.CancellationException

/** Polled by blocking parsers/importers at least every 1,000 entries or programmes. */
fun interface CancelCheck {
    fun isCancelled(): Boolean

    fun throwIfCancelled() {
        if (isCancelled()) throw CancellationException("cancelled")
    }

    companion object {
        val Never = CancelCheck { false }
    }
}

/**
 * Blocks the importer between batches while a zap is in progress (architecture review B3.4).
 * M1 ships [Open]; M2's PlaybackController provides the real gate (a 3 s hold per zap).
 */
fun interface ImportGate {
    fun awaitClear()

    companion object {
        val Open = ImportGate { }
    }
}
