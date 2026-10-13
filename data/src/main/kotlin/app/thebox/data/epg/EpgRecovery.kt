package app.thebox.data.epg

import app.thebox.core.common.BoxLog
import app.thebox.core.epg.EpgStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs `EpgStore.recover()` (§2.5.4) once, off the main thread, on the application scope. Builds, patches
 * and readers await it. A failure is logged and never crashes the app: in particular, on a branch where
 * WP-B's `EpgStoreImpl` is still the commit-0 stub, `recover()` throws NotImplementedError, which is caught.
 */
class EpgRecovery(store: EpgStore, scope: CoroutineScope, io: CoroutineDispatcher) {
    private val done: Deferred<Boolean> = scope.async(io) {
        try {
            store.recover()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BoxLog.e(TAG, "EPG recovery failed; the guide starts empty", t)
            false
        }
    }

    /** Waits for recovery; false when it failed (the store may be unusable). */
    suspend fun await(): Boolean = done.await()

    companion object {
        private const val TAG = "EpgRecovery"
    }
}
