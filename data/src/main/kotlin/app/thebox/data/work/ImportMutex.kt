package app.thebox.data.work

import app.thebox.core.common.ImportGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide lock held by the playlist merge phase and by EPG build/patch, so the two importers never run
 * at the same time (§4.5 heap budget, S9). Lock order is always ImportMutex → EpgWriteLock.
 *
 * (amended 2026-10-13) It also owns the single [gate] both importers await between batches: the playlist
 * merge passes it as `MergeContext.gate`, EPG build/patch pass it to `EpgStore`. M2's PlaybackController
 * installs the real zap gate here once, and every import (whichever is running) pauses on it.
 */
@Singleton
class ImportMutex @Inject constructor() {
    val mutex = Mutex()

    /** The zap gate shared by every importer (§3.10). [ImportGate.Open] until M2 sets it. */
    @Volatile var gate: ImportGate = ImportGate.Open

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
