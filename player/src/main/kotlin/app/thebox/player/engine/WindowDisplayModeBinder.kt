package app.thebox.player.engine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import app.thebox.player.api.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Applies `PlaybackController.displayModeRequest` as the Activity window's `preferredDisplayModeId` (API < 31 AFR,
 * docs/design/m2-playback-core.md §3.9.2). Used by `MainActivity` and the debug harness. On API 31+ the request stays
 * 0 (the system default), so binding is harmless there.
 */
class WindowDisplayModeBinder internal constructor(private val requests: StateFlow<Int>) {

    constructor(controller: PlaybackController) : this(controller.displayModeRequest)

    /** Binds to [activity]'s window until the activity is destroyed. Call once, from `onCreate`. */
    fun bind(activity: Activity) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val app = activity.application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) {
                if (a !== activity) return
                scope.cancel()
                app.unregisterActivityLifecycleCallbacks(this)
            }

            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        scope.launch { requests.collect { apply(activity.window, it) } }
    }

    internal companion object {
        /** Sets the window's preferred mode when it differs (0 = system default). */
        fun apply(window: Window?, modeId: Int) {
            if (window == null) return
            val attrs = window.attributes
            if (attrs.preferredDisplayModeId == modeId) return
            attrs.preferredDisplayModeId = modeId
            window.attributes = attrs
        }
    }
}
