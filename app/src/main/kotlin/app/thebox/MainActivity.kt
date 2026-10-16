package app.thebox

import android.os.Bundle
import androidx.core.view.OneShotPreDrawListener
import app.thebox.data.di.StartupGate
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.thebox.core.guide.GuideRenderer
import app.thebox.player.api.PlaybackController
import app.thebox.player.engine.WindowDisplayModeBinder
import app.thebox.ui.guide.grid.GuideGridHost
import app.thebox.ui.guide.grid.GuideTrim
import app.thebox.ui.full.vm.PlayerViewModel
import app.thebox.ui.guide.vm.GuideViewModel
import app.thebox.ui.root.AppRoot
import app.thebox.ui.search.SearchScreen
import app.thebox.ui.search.SearchViewModel
import app.thebox.ui.theme.BoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one activity (docs/design/m3-guide.md §1.4, §3.13.1, §3.16.1): keeps M2's screen-on and API < 31 display-mode
 * wiring, and hosts [AppRoot] with the guide view model as
 * its [app.thebox.ui.root.ShellHost] (and the player view model attached to it, M4 §3.10.1). View models come from the activity's Hilt default factory (`by viewModels()`), so
 * no `hilt-navigation-compose` is needed. The Search screen's effects go to the guide view model (B7, §2.13 item 7);
 * `onTrimMemory` goes to [GuideTrim] (§4.7, §2.13 item 6).
 *
 * (M3 code review 2026-10-14, O-7) Startup order: the guide view model first (its feeds and the startup tune are the
 * first frame's content), then the composition; after the first frame has drawn, the [StartupGate] opens (reminders)
 * and `controller.prewarm()` runs (a no-op when the composition's `VideoLayer` already built the engine).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: PlaybackController
    @Inject lateinit var renderer: GuideRenderer
    @Inject lateinit var guideTrim: GuideTrim
    @Inject lateinit var startupGate: StartupGate

    private val guide: GuideViewModel by viewModels()
    private val player: PlayerViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    private val settings: app.thebox.ui.settings.vm.SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M2 §3.10.6 / §3.9.2: screen-on while playing, API < 31 AFR mode on this window.
        lifecycleScope.launch { controller.keepScreenOn.collect { if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
        WindowDisplayModeBinder(controller).bind(this)

        val host = guide // creates the view model and its feeds (§3.16.1)
        // (M4, docs/design/m4-player-ux.md §1.7 item 10, §3.10.1, S9) The player view model and its machine exist, and
        // the guide machine delegates FULL to it, before the startup tune: no FULL state runs without the player machine.
        host.attachPlayer(player)
        // (M5 commit 0, docs/design/m5-setup-settings.md §2.13, S11) The settings machine SETTINGS delegates to; attached
        // before startup, which waits for it (bounded) before the first-run decision.
        host.attachSettings(settings.machine)
        player.runTicker(lifecycle)
        host.startup()
        lifecycleScope.launch { search.effects.collect { host.onSearchEffect(it) } }
        setContent {
            BoxTheme {
                AppRoot(
                    host = host,
                    onExit = ::finish,
                    debugHint = BuildConfig.DEBUG,
                    search = { SearchScreen(search, onIdleChanged = { idle -> host.searchFieldIdle = idle }) },
                    grid = { modifier -> GuideGridHost(host.machine, renderer, modifier) },
                )
            }
        }
        val decor = window.decorView
        OneShotPreDrawListener.add(decor) {
            // Runs after the first frame's draw pass (posted from its pre-draw).
            decor.post {
                firstFrameAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                startupGate.open()
                controller.prewarm()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        guideTrim.onTrimMemory(level)
    }

    companion object {
        /** `elapsedRealtime` just after the latest instance's first frame drew (I-1 measures from it). */
        @Volatile var firstFrameAtElapsedMs: Long = 0L
            private set
    }
}
