package app.thebox.data.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide lock held by the playlist merge phase and by EPG build/patch, so the two importers never run
 * at the same time (§4.5 heap budget, S9). Lock order is always ImportMutex → EpgWriteLock.
 */
@Singleton
class ImportMutex @Inject constructor() {
    val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
