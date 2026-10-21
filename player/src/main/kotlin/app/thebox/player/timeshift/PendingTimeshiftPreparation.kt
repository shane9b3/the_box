package app.thebox.player.timeshift

import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TimeshiftSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Owns a speculative preparation until the controller takes it or releases it. Preparing only does local storage
 * work; it must finish registering/discarding its result even when the playback scope is cancelled. No network work
 * belongs here. The main thread only changes ownership; abandonment and any late completion run on IO.
 */
internal class PendingTimeshiftPreparation(
    private val factory: TimeshiftSessionFactory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    settings: PlaybackSettings,
    maxConnections: Int,
) {
    private val lock = Any()
    private val ready = CompletableDeferred<TimeshiftPrepared>()
    private var result: TimeshiftPrepared? = null
    private var released = false
    private var taken = false
    private var discardFile = false

    init {
        scope.launch(io + NonCancellable) {
            try {
                val prepared = factory.prepare(settings, maxConnections) { synchronized(lock) { !released } }
                val dispose = synchronized(lock) {
                    if (released) discardFile else { result = prepared; null }
                }
                if (dispose != null) {
                    if (prepared is TimeshiftPrepared.Granted) factory.abandon(prepared.grant, dispose)
                    else if (dispose) factory.discardUnused()
                }
                ready.complete(prepared)
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            }
        }
    }

    suspend fun take(): TimeshiftPrepared {
        ready.await()
        return synchronized(lock) {
            if (released) throw CancellationException("time-shift preparation released")
            check(!taken) { "time-shift preparation already taken" }
            taken = true
            checkNotNull(result).also { result = null }
        }
    }

    fun release(discardFile: Boolean) {
        val prepared = synchronized(lock) {
            if (released || taken) return
            released = true
            this.discardFile = discardFile
            result.also { result = null }
        }
        if (prepared is TimeshiftPrepared.Granted) scope.launch(io + NonCancellable) {
            factory.abandon(prepared.grant, discardFile)
        } else if (prepared != null && discardFile) scope.launch(io + NonCancellable) {
            factory.discardUnused()
        }
    }
}
