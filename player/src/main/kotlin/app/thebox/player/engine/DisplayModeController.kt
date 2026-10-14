package app.thebox.player.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.DisplayModeInfo
import app.thebox.core.playback.DisplayModePicker
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.Viewport
import app.thebox.player.internal.DisplayModeControllerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The display as data: current and supported modes, change notifications. */
internal interface DisplayAccess {
    fun current(): DisplayModeInfo?
    fun supported(): List<DisplayModeInfo>
    /** [listener] is called on the main thread when the display (mode) changes; null unregisters. */
    fun setListener(listener: (() -> Unit)?)
}

/**
 * `Surface.setFrameRate` votes on the root `SurfaceView`'s surface (a seam: Robolectric's `ShadowSurface` records no
 * votes and its `SurfaceHolder` has no surface).
 */
internal interface FrameRateVoter {
    /** API 31+: `setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)`. False when there is no valid surface yet. */
    fun vote(holder: SurfaceHolder, fps: Float): Boolean
    /** API 31+: back to the default mode, `setFrameRate(0, DEFAULT, ONLY_IF_SEAMLESS)`. */
    fun revert(holder: SurfaceHolder)
    /** API 30+: drop any vote, including one ExoPlayer left (`clearFrameRate()` on 34+). */
    fun clear(holder: SurfaceHolder)
}

/**
 * Auto frame rate (docs/design/m2-playback-core.md §3.9, review S12). Main thread only.
 *
 * - [AfrMode.OFF] / [AfrMode.SEAMLESS]: nothing app-side; the player's frame-rate strategy is set by
 *   [onAfrModeChanged] (`OFF` / ExoPlayer's `ONLY_IF_SEAMLESS`).
 * - [AfrMode.ALWAYS]: only in the FULL viewport, with a known fps (cached `fps_milli` or [onFps]) and after
 *   [AFR_DWELL_MS] on the channel. A tune whose previous channel was watched for the dwell in FULL switches at once
 *   (that is the case for [awaitSettled]); otherwise the switch is evaluated when the dwell has passed.
 *   - API ≥ 31: a `Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` vote on the root `SurfaceView`,
 *     re-applied in `surfaceCreated` (the vote dies with the surface, S12).
 *   - API < 31: [DisplayModePicker.pick] → [request] (`preferredDisplayModeId`, applied to the window by
 *     [WindowDisplayModeBinder]); playback is held ([switchListener], [awaitSettled]) until the display reports the
 *     mode or [AFR_SWITCH_TIMEOUT_MS] passes.
 * - Back in MINI/HIDDEN the content mode stays (no ping-pong) and is reverted after [AFR_REVERT_IDLE_MS]; [clear]
 *   (stop, background) reverts at once.
 */
internal class DisplayModeController(
    private val picker: DisplayModePicker,
    private val clock: MonoClock,
    private val afrMode: () -> AfrMode,
    private val setPlayerStrategy: (Int) -> Unit,
    private val display: DisplayAccess,
    private val voter: FrameRateVoter = PlatformFrameRateVoter,
    looper: Looper = Looper.getMainLooper(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : DisplayModeControllerApi {

    /** Hold/resume notifications for API < 31 switches (the controller pauses and shows SWITCHING_DISPLAY_MODE). */
    interface SwitchListener {
        fun onSwitchStarted()
        fun onSwitchSettled()
    }

    var switchListener: SwitchListener? = null

    private val requestState = MutableStateFlow(0)
    private val refreshState = MutableStateFlow(display.current()?.refreshRate)
    override val request: StateFlow<Int> = requestState.asStateFlow()
    override val refreshHz: StateFlow<Float?> = refreshState.asStateFlow()

    private val handler = Handler(looper)
    private val evaluateRunnable = Runnable { evaluate(force = false) }
    private val revertRunnable = Runnable { revert("idle") }
    private val switchTimeoutRunnable = Runnable { settle("timeout") }

    private var view: SurfaceView? = null
    private var viewport = Viewport.NONE
    private var lastTuneAtMs = NEVER
    private var fps: Float? = null

    /** A content-mode vote/request is in effect. */
    private var applied = false
    private var votedFps = 0f
    private var holding = false
    private var requestedModeId = 0
    private val settleCallbacks = ArrayList<() -> Unit>(2)

    @VisibleForTesting
    internal val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            // The vote belongs to the Surface and is lost with it (standby, Home): re-apply (S12).
            if (applied && sdkInt >= 31) voter.vote(holder, votedFps)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    init {
        display.setListener { onDisplayChanged() }
    }

    // ---------------------------------------------------------------- DisplayModeControllerApi

    override fun attachSurfaceView(view: SurfaceView?) {
        if (this.view === view) return
        this.view?.holder?.removeCallback(surfaceCallback)
        this.view = view
        if (view == null) return
        view.holder.addCallback(surfaceCallback)
        if (applied && sdkInt >= 31) voter.vote(view.holder, votedFps)
    }

    override fun onTune(knownFpsMilli: Int?) {
        val now = clock.nowMs()
        val previousDwelled = viewport == Viewport.FULL && lastTuneAtMs != NEVER && now - lastTuneAtMs >= AFR_DWELL_MS
        lastTuneAtMs = now
        fps = knownFpsMilli?.takeIf { it > 0 }?.let { it / 1000f }
        handler.removeCallbacks(evaluateRunnable)
        if (previousDwelled) evaluate(force = true) else handler.postDelayed(evaluateRunnable, AFR_DWELL_MS)
    }

    override fun onFps(fps: Float) {
        if (this.fps == fps) return
        this.fps = fps
        evaluate(force = false)
    }

    override fun onViewport(viewport: Viewport) {
        if (this.viewport == viewport) return
        this.viewport = viewport
        if (viewport == Viewport.FULL) {
            handler.removeCallbacks(revertRunnable)
            evaluate(force = false)
        } else {
            handler.removeCallbacks(evaluateRunnable)
            if (applied) {
                handler.removeCallbacks(revertRunnable)
                handler.postDelayed(revertRunnable, AFR_REVERT_IDLE_MS)
            }
        }
    }

    override fun awaitSettled(onSettled: () -> Unit) {
        if (holding) settleCallbacks += onSettled else onSettled()
    }

    override fun clear() {
        handler.removeCallbacks(evaluateRunnable)
        handler.removeCallbacks(revertRunnable)
        revert("clear")
        if (holding) settle("clear")
    }

    // ---------------------------------------------------------------- additions for the controller

    /** The AFR setting changed (S12): drop any vote left on the surface, then set the player's strategy. */
    fun onAfrModeChanged(mode: AfrMode) {
        val holder = view?.holder
        if (sdkInt >= 30 && holder != null) voter.clear(holder)
        setPlayerStrategy(PlayerFactory.frameRateStrategyFor(mode))
        if (mode == AfrMode.ALWAYS) {
            applied = false
            evaluate(force = false)
        } else {
            revert("mode $mode")
        }
    }

    /** True while an API < 31 switch holds playback. */
    val isHolding: Boolean get() = holding

    fun release() {
        clear()
        attachSurfaceView(null)
        display.setListener(null)
    }

    // ---------------------------------------------------------------- internals

    private fun evaluate(force: Boolean) {
        if (afrMode() != AfrMode.ALWAYS || viewport != Viewport.FULL) return
        val target = fps ?: return
        val sinceTune = clock.nowMs() - lastTuneAtMs
        if (!force && lastTuneAtMs != NEVER && sinceTune < AFR_DWELL_MS) {
            handler.removeCallbacks(evaluateRunnable)
            handler.postDelayed(evaluateRunnable, AFR_DWELL_MS - sinceTune)
            return
        }
        if (sdkInt >= 31) {
            if (applied && votedFps == target) return
            applied = true
            votedFps = target
            val holder = view?.holder
            val voted = holder != null && voter.vote(holder, target)
            BoxLog.i(TAG) { "AFR vote ${target}fps (surface ${if (voted) "set" else "pending"})" }
        } else {
            val current = display.current() ?: return
            val mode = picker.pick(current, display.supported(), target) ?: return
            if (mode.id == requestState.value) return
            applied = true
            requestedModeId = mode.id
            requestState.value = mode.id
            BoxLog.i(TAG) { "AFR request mode ${mode.id} ${mode.width}x${mode.height}@${mode.refreshRate} for ${target}fps" }
            beginHold()
        }
    }

    private fun beginHold() {
        handler.removeCallbacks(switchTimeoutRunnable)
        handler.postDelayed(switchTimeoutRunnable, AFR_SWITCH_TIMEOUT_MS)
        if (holding) return
        holding = true
        switchListener?.onSwitchStarted()
    }

    private fun settle(reason: String) {
        handler.removeCallbacks(switchTimeoutRunnable)
        if (!holding) return
        holding = false
        BoxLog.d(TAG) { "AFR settled ($reason)" }
        switchListener?.onSwitchSettled()
        if (settleCallbacks.isNotEmpty()) {
            val callbacks = settleCallbacks.toList()
            settleCallbacks.clear()
            callbacks.forEach { it() }
        }
    }

    private fun revert(reason: String) {
        if (!applied) return
        applied = false
        if (sdkInt >= 31) {
            view?.holder?.let { voter.revert(it) }
        } else {
            requestedModeId = 0
            requestState.value = 0
        }
        BoxLog.i(TAG) { "AFR reverted ($reason)" }
    }

    private fun onDisplayChanged() {
        val current = display.current()
        refreshState.value = current?.refreshRate
        if (holding && current != null && current.id == requestedModeId) settle("display changed")
    }

    companion object {
        private const val TAG = "DisplayMode"
        private const val NEVER = Long.MIN_VALUE

        /** `AFR_DWELL_MS`, `AFR_SWITCH_TIMEOUT_MS`, `AFR_REVERT_IDLE_MS` (App. B). */
        const val AFR_DWELL_MS = 2_000L
        const val AFR_SWITCH_TIMEOUT_MS = 3_000L
        const val AFR_REVERT_IDLE_MS = 30_000L

        /** Builds the production controller over the default display and the platform voter. */
        fun create(
            context: Context,
            picker: DisplayModePicker,
            clock: MonoClock,
            afrMode: () -> AfrMode,
            setPlayerStrategy: (Int) -> Unit,
        ): DisplayModeController = DisplayModeController(picker, clock, afrMode, setPlayerStrategy, PlatformDisplayAccess(context))

        fun infoOf(mode: Display.Mode): DisplayModeInfo = DisplayModeInfo(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }
}

/** [DisplayAccess] over `DisplayManager` and the default display. */
internal class PlatformDisplayAccess(context: Context, looper: Looper = Looper.getMainLooper()) : DisplayAccess {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(looper)
    private var registered: DisplayManager.DisplayListener? = null

    private val display: Display? get() = manager?.getDisplay(Display.DEFAULT_DISPLAY)

    override fun current(): DisplayModeInfo? = display?.mode?.let(DisplayModeController::infoOf)

    override fun supported(): List<DisplayModeInfo> = display?.supportedModes?.map(DisplayModeController::infoOf).orEmpty()

    override fun setListener(listener: (() -> Unit)?) {
        registered?.let { manager?.unregisterDisplayListener(it) }
        registered = null
        if (listener == null || manager == null) return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) listener()
            }
        }
        manager.registerDisplayListener(l, handler)
        registered = l
    }
}

/** The platform's `Surface.setFrameRate` (API 30/31/34 variants). */
internal object PlatformFrameRateVoter : FrameRateVoter {
    private fun validSurface(holder: SurfaceHolder): Surface? {
        val surface: Surface? = holder.surface
        return surface?.takeIf { it.isValid }
    }

    override fun vote(holder: SurfaceHolder, fps: Float): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val surface = validSurface(holder) ?: return false
        return Api31.set(surface, fps)
    }

    override fun revert(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT < 31) return
        validSurface(holder)?.let { Api31.revert(it) }
    }

    override fun clear(holder: SurfaceHolder) {
        val surface = validSurface(holder) ?: return
        when {
            Build.VERSION.SDK_INT >= 34 -> Api34.clear(surface)
            Build.VERSION.SDK_INT >= 30 -> Api30.clear(surface)
        }
    }

    @RequiresApi(30)
    private object Api30 {
        fun clear(surface: Surface) = guard { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    @RequiresApi(31)
    private object Api31 {
        fun set(surface: Surface, fps: Float): Boolean =
            guard { surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }

        fun revert(surface: Surface) =
            guard { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS) }
    }

    @RequiresApi(34)
    private object Api34 {
        fun clear(surface: Surface) = guard { surface.clearFrameRate() }
    }

    /** A released surface throws `IllegalStateException`; never let a vote crash playback. */
    private inline fun guard(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: IllegalStateException) {
        BoxLog.w("DisplayMode", "setFrameRate failed: ${e.javaClass.simpleName}")
        false
    } catch (e: IllegalArgumentException) {
        BoxLog.w("DisplayMode", "setFrameRate rejected: ${e.javaClass.simpleName}")
        false
    }
}
