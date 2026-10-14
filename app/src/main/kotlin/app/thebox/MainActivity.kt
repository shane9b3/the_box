package app.thebox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.thebox.core.guide.GuideRenderer
import app.thebox.player.api.PlaybackController
import app.thebox.player.engine.WindowDisplayModeBinder
import app.thebox.shell.ShellPreview
import app.thebox.ui.guide.grid.GuideGridHost
import app.thebox.ui.guide.vm.GuideViewModel
import app.thebox.ui.root.AppRoot
import app.thebox.ui.search.SearchScreen
import app.thebox.ui.search.SearchViewModel
import app.thebox.ui.theme.BoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/**
 * The one activity (docs/design/m3-guide.md §1.4, §3.13.1, §3.16.1): keeps M2's screen-on and API < 31 display-mode
 * wiring, warms the player before the first composition (M2 E-L17), and hosts [AppRoot] with the guide view model as
 * its [app.thebox.ui.root.ShellHost]. View models come from the activity's Hilt default factory (`by viewModels()`), so
 * no `hilt-navigation-compose` is needed. `onTrimMemory` fans out to the guide (§4.7).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: PlaybackController
    @Inject lateinit var renderer: GuideRenderer
    @Inject lateinit var shellPreview: Optional<ShellPreview>

    private val guide: GuideViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    private var previewMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M2 §3.10.6 / §3.9.2: screen-on while playing, API < 31 AFR mode on this window.
        lifecycleScope.launch { controller.keepScreenOn.collect { if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        WindowDisplayModeBinder(controller).bind(this)
        controller.prewarm()

        // Debug-only WP-C visual check (see ShellPreview); never taken in release builds.
        val preview = if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SHELL_PREVIEW, false)) shellPreview.orElse(null) else null
        if (preview != null) {
            previewMode = true
            val host = preview.host(controller, intent)
            setContent { BoxTheme { AppRoot(host, onExit = ::finish, debugHint = true, grid = { preview.Grid(it) }) } }
            return
        }

        val host = guide // creates the view model: feeds, startup tune (§3.16.1)
        setContent {
            BoxTheme {
                AppRoot(
                    host = host,
                    onExit = ::finish,
                    debugHint = BuildConfig.DEBUG,
                    search = { SearchScreen(search) },
                    grid = { modifier -> GuideGridHost(host.machine, renderer, modifier) },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!previewMode) guide.onTrimMemory(level)
    }

    companion object {
        const val EXTRA_SHELL_PREVIEW = "thebox.shell_preview"
    }
}
