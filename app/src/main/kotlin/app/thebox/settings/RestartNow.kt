package app.thebox.settings

import android.content.Context
import android.content.Intent
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.SettingsStore
import app.thebox.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Restart now" for RESTART rows (docs/design/m5-setup-settings.md §3.3.3, N9: a TV process can live for weeks):
 * flushes the store, relaunches the launcher activity in a cleared task (`Intent.makeRestartActivityTask`, i.e.
 * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`) and ends the process, so the new engine is built with the stored
 * `softwareAudioFallback`. [onFailed] gets the user text when no launch intent exists.
 */
@Singleton
class RestartNow internal constructor(
    private val context: Context,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val exit: () -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, store: SettingsStore, @ApplicationScope scope: CoroutineScope) :
        this(context, store, scope, { Runtime.getRuntime().exit(0) })

    var onFailed: (() -> Unit)? = null

    fun restart() {
        scope.launch {
            store.flush()
            withContext(Dispatchers.Main) {
                val pm = context.packageManager
                val launch = (pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName))?.component
                if (launch == null) {
                    BoxLog.w(TAG, "restart: no launch activity")
                    onFailed?.invoke()
                    return@withContext
                }
                BoxLog.i(TAG) { "restart now" }
                context.startActivity(Intent.makeRestartActivityTask(launch))
                exit()
            }
        }
    }

    private companion object {
        const val TAG = "RestartNow"
    }
}
