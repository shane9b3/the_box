package app.thebox.player.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.core.common.BoxLog
import app.thebox.player.api.StopReason

/**
 * Process start/stop, screen, HDMI, display state, standby grace, resume (§3.10.2). Main thread only.
 *
 * - `ON_STOP` while the device is interactive and the display is on (Home, another app) → [Target.stopForLifecycle]
 *   with BACKGROUND **at once** (TV norm; frees the provider connection).
 * - (reviewed 2026-10-13, B4) `ON_STOP` while `PowerManager.isInteractive()` is false or the display is OFF/DOZE
 *   (standby: going to sleep stops the top Activity on AOSP), `ACTION_SCREEN_OFF`, an HDMI audio unplug (1 → 0
 *   transition, never the initial sticky broadcast) or the default display turning off → a [STANDBY_GRACE_MS] grace
 *   (scaled by [timeScale] for instrumented tests); on expiry the target stops with STANDBY / HDMI_UNPLUGGED /
 *   DISPLAY_OFF. During the grace only audio plays (the `SurfaceView`'s surface is gone).
 * - `ON_START`, `ACTION_SCREEN_ON`, HDMI plug-in or display-on cancel the grace; `ON_START` (and the others when the
 *   app is already in the foreground) asks the target to resume a lifecycle stop ([Target.resumeAfterLifecycle], which
 *   re-resolves and prepares a fresh session at the live edge).
 *
 * The entry points (`onProcessStart` …) are what [start]'s platform observers call; tests call them directly with a
 * fake [DeviceState].
 */
internal class LifecycleCoordinator(
    private val context: Context,
    private val target: Target,
    private val device: DeviceState,
    private val timeScale: () -> Float = { 1f },
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    /** What the coordinator drives (the controller). */
    interface Target {
        /** Connecting, Buffering, Playing (paused included) or Stalled. */
        fun isPlaybackActive(): Boolean
        fun stopForLifecycle(reason: StopReason)
        /** Back in the foreground: resume when the last stop was a lifecycle stop (the target applies §3.10.2). */
        fun resumeAfterLifecycle()
        fun onForegroundChanged(foreground: Boolean)
    }

    /** Power and display facts (B4). */
    interface DeviceState {
        fun isInteractive(): Boolean
        /** Default display in `STATE_ON` (not OFF, DOZE or DOZE_SUSPEND). */
        fun isDisplayOn(): Boolean
    }

    /** Process at least STARTED (an Activity is visible). */
    var isForeground: Boolean = false
        private set

    /** The reason the pending grace will stop with, or null when no grace runs. */
    var graceReason: StopReason? = null
        private set

    private var started = false
    private var hdmiPlugged: Boolean? = null
    private val graceExpired = Runnable {
        val reason = graceReason ?: return@Runnable
        graceReason = null
        if (target.isPlaybackActive()) {
            BoxLog.i(TAG) { "grace expired: stop ${reason.name}" }
            target.stopForLifecycle(reason)
        }
    }

    // ------------------------------------------------------------------ events

    fun onProcessStart() {
        isForeground = true
        target.onForegroundChanged(true)
        cancelGrace("start")
        target.resumeAfterLifecycle()
    }

    fun onProcessStop() {
        isForeground = false
        target.onForegroundChanged(false)
        val interactive = device.isInteractive()
        val displayOn = device.isDisplayOn()
        if (interactive && displayOn) {
            cancelGrace("background")
            if (target.isPlaybackActive()) {
                BoxLog.i(TAG) { "ON_STOP while interactive: stop now" }
                target.stopForLifecycle(StopReason.BACKGROUND)
            }
        } else {
            startGrace(if (!interactive) StopReason.STANDBY else StopReason.DISPLAY_OFF)
        }
    }

    fun onScreenOff() = startGrace(StopReason.STANDBY)

    fun onScreenOn() {
        cancelGrace("screen on")
        if (isForeground) target.resumeAfterLifecycle()
    }

    /** [initialSticky]: the broadcast replayed at registration (never a transition). */
    fun onHdmiAudioPlug(plugged: Boolean, initialSticky: Boolean) {
        val previous = hdmiPlugged
        hdmiPlugged = plugged
        if (initialSticky || previous == null || previous == plugged) return
        if (!plugged) {
            startGrace(StopReason.HDMI_UNPLUGGED)
        } else {
            cancelGrace("hdmi plugged")
            if (isForeground) target.resumeAfterLifecycle()
        }
    }

    fun onDisplayStateChanged(on: Boolean) {
        if (!on) {
            startGrace(StopReason.DISPLAY_OFF)
        } else {
            cancelGrace("display on")
            if (isForeground) target.resumeAfterLifecycle()
        }
    }

    private fun startGrace(reason: StopReason) {
        if (graceReason != null) return // the first trigger names the stop
        if (!target.isPlaybackActive()) return
        graceReason = reason
        val ms = (STANDBY_GRACE_MS * timeScale().coerceIn(0.001f, 1000f)).toLong()
        BoxLog.i(TAG) { "grace ${reason.name} ${ms}ms" }
        handler.postDelayed(graceExpired, ms)
    }

    private fun cancelGrace(why: String) {
        if (graceReason == null) return
        BoxLog.i(TAG) { "grace cancelled ($why)" }
        graceReason = null
        handler.removeCallbacks(graceExpired)
    }

    // ------------------------------------------------------------------ platform wiring

    /** Registers the process, screen, HDMI and display observers. Idempotent; main thread. */
    fun start() {
        if (started) return
        started = true
        val process = ProcessLifecycleOwner.get().lifecycle
        // No direct read of the current state: a new observer is brought up to it, so onStart arrives at once when the
        // process is already started.
        process.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onProcessStart()
            override fun onStop(owner: LifecycleOwner) = onProcessStop()
        })

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    AudioManager.ACTION_HDMI_AUDIO_PLUG ->
                        onHdmiAudioPlug(intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1, isInitialStickyBroadcast)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val displays = context.getSystemService(DisplayManager::class.java)
        displays?.registerDisplayListener(object : DisplayManager.DisplayListener {
            private var lastOn: Boolean? = null
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val on = device.isDisplayOn()
                if (lastOn == on) return
                lastOn = on
                onDisplayStateChanged(on)
            }
        }, handler)
    }

    /** [DeviceState] over `PowerManager` and `DisplayManager`. */
    class AndroidDeviceState(context: Context) : DeviceState {
        private val power = context.getSystemService(PowerManager::class.java)
        private val displays = context.getSystemService(DisplayManager::class.java)
        override fun isInteractive(): Boolean = power?.isInteractive ?: true
        override fun isDisplayOn(): Boolean = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let { it == Display.STATE_ON } ?: true
    }

    companion object {
        private const val TAG = "Lifecycle"
        const val STANDBY_GRACE_MS = 30_000L
        /** Back after this long: refresh the Xtream account before resuming (§3.10.2). */
        const val RESUME_REAUTH_AFTER_MS = 900_000L
    }
}
