package app.thebox.settings

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.DataDomain
import app.thebox.data.di.ApplicationScope
import app.thebox.data.work.RefreshScheduler
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide settings side effects (docs/design/m5-setup-settings.md §2.2, §3.3.2):
 * - the store is flushed on ON_STOP (Home, standby, HDMI off), so a debounced write never waits for a process kill;
 * - a change of the guide update interval ("Every N hours" / "Daily at a set time", N4) reschedules the periodic EPG
 *   import at once (`guide.interval` is LIVE). Daily runs as a 24 h period from the change (the hour is not yet an
 *   initial delay: `RefreshScheduler` is not WP-A's file; see §1.4a).
 * Installed once by [PersistedPlaybackSettings] (created with the controller at startup).
 */
@Singleton
class SettingsLifecycle @Inject constructor(
    private val store: SettingsStore,
    private val scheduler: Lazy<RefreshScheduler>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val observe = Runnable {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        scope.launch { store.flush() }
                    }
                })
            } catch (e: Exception) {
                BoxLog.w(TAG, "process lifecycle unavailable: ${e.javaClass.simpleName}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) observe.run() else Handler(Looper.getMainLooper()).post(observe)
        scope.launch {
            store.flow(DataDomain).map { it.epgRefreshIntervalH to it.epgDailyAtHour }.distinctUntilChanged().drop(1).collect {
                try {
                    scheduler.get().scheduleEpgPeriodic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.e(TAG, "guide update reschedule failed", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SettingsLifecycle"
    }
}
