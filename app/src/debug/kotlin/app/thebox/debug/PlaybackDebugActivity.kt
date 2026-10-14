package app.thebox.debug

// Commit-0 stub (docs/design/m2-playback-core.md §1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.thebox.player.api.PlaybackController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only playback harness (§3.16): `thebox-debug://play?url=…` / `?playlist=<id>&key=<stableKey>`, and
 * `thebox-debug://stop`. Hosts `VideoLayer` exactly as M3 will, with the state/stats/event overlay. Debug source set
 * only: release builds do not contain it and do not register the scheme.
 */
@AndroidEntryPoint
class PlaybackDebugActivity : ComponentActivity() {
    @Inject lateinit var controller: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TODO("WP-D")
    }
}
