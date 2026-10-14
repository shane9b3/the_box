package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Low-RAM class, decoders, HDR caps, display modes, FFmpeg version, audio caps (§3.15). */
@Singleton
internal class DeviceCapabilities @Inject constructor(@ApplicationContext private val context: Context) {
    val isLowRam: Boolean get() = TODO("WP-C")
    fun dump(): Map<String, String> = TODO("WP-C")
}
