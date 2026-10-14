package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.view.SurfaceView
import app.thebox.core.playback.Viewport
import app.thebox.player.internal.DisplayModeControllerApi
import kotlinx.coroutines.flow.StateFlow

/** AFR application on both API paths, display listener, revert (§3.9). */
internal class DisplayModeController : DisplayModeControllerApi {
    override val request: StateFlow<Int> get() = TODO("WP-C")
    override val refreshHz: StateFlow<Float?> get() = TODO("WP-C")
    override fun attachSurfaceView(view: SurfaceView?): Unit = TODO("WP-C")
    override fun onTune(knownFpsMilli: Int?): Unit = TODO("WP-C")
    override fun onFps(fps: Float): Unit = TODO("WP-C")
    override fun onViewport(viewport: Viewport): Unit = TODO("WP-C")
    override fun awaitSettled(onSettled: () -> Unit): Unit = TODO("WP-C")
    override fun clear(): Unit = TODO("WP-C")
}
