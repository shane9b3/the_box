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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @Inject lateinit var documentPicker: app.thebox.core.settings.tree.DocumentPicker

    private val guide: GuideViewModel by viewModels()
    private val player: PlayerViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    private val settings: app.thebox.ui.settings.vm.SettingsViewModel by viewModels()
    private val phone: app.thebox.ui.backup.PhoneSetupViewModel by viewModels()

    /** (M5 integration) WP-D's SAF requests (backup to / restore from a file) and WP-B's M3U file pick. */
    private var backupRequestId = 0L
    private val backupDocument = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        documentPicker.picked(backupRequestId, app.thebox.ui.backup.BackupDocuments.resultUri(r.resultCode, r.data))
    }
    private var m3uRoute: app.thebox.core.settings.SettingsRoute? = null
    private val m3uDocument = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val route = m3uRoute ?: return@registerForActivityResult
        m3uRoute = null
        val uri = r.data?.data?.takeIf { r.resultCode == RESULT_OK }
        var name: String? = null
        if (uri != null) {
            // The import reads the document later (and on every refresh): keep the read grant.
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        settings.setupPages?.filePicked(route, uri?.toString(), name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M2 §3.10.6 / §3.9.2: screen-on while playing, API < 31 AFR mode on this window.
        WindowDisplayModeBinder(controller).bind(this)

        val host = guide // creates the view model and its feeds (§3.16.1)
        // (M4, docs/design/m4-player-ux.md §1.7 item 10, §3.10.1, S9) The player view model and its machine exist, and
        // the guide machine delegates FULL to it, before the startup tune: no FULL state runs without the player machine.
        host.attachPlayer(player)
        // (M5 commit 0, docs/design/m5-setup-settings.md §2.13, S11) The settings machine SETTINGS delegates to; attached
        // before startup, which waits for it (bounded) before the first-run decision.
        host.attachSettings(settings.machine)
        wireSettingsPages()
        // M2 §3.10.6 / §3.9.2: screen-on while playing.
        // (M5 integration, S4) Also while the phone page or the import progress is open (the screen saver would end them).
        val settingsKeepOn = settings.machine.ui.map { u -> u.route is app.thebox.core.settings.SettingsRoute.PhoneSetup || u.route is app.thebox.core.settings.SettingsRoute.ImportProgress }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(controller.keepScreenOn, settingsKeepOn) { a, b -> a || b }.distinctUntilChanged().collect {
                if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
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
                    settingsPhone = { purpose -> app.thebox.ui.backup.PhoneSetupHost(phone, purpose) },
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

    /**
     * (M5 integration, docs/design/m5-setup-settings.md §1.5a, §1.7a) The activity-owned parts of the settings pages:
     * the phone page's keys (WP-D `PhoneScreenModel` through [PhoneSetupViewModel]), WP-D's document requests and WP-B's
     * M3U file picker.
     */
    private fun wireSettingsPages() {
        val link = settings.machine as? app.thebox.core.settings.state.SettingsShellLink
        link?.keyHandler = handler@{ route, act ->
            if (route !is app.thebox.core.settings.SettingsRoute.PhoneSetup) return@handler false
            val now = System.currentTimeMillis()
            val hint = app.thebox.core.setup.lan.PhoneScreenModel.showIsolationHint(phone.server.value, phone.shownAtMs, now)
            val typeOnTv = app.thebox.core.setup.lan.PhoneScreenModel.showTypeOnTv(hint, phone.vpn, route.purpose)
            when (phone.act(act.action, typeOnTv)) {
                app.thebox.core.setup.lan.PhoneScreenEffect.TYPE_ON_TV -> {
                    link.replace(app.thebox.core.settings.SettingsRoute.AddSource(kind = null, firstRun = route.firstRun))
                    true
                }
                // BACK: the session stopped; the machine pops the page as usual.
                app.thebox.core.setup.lan.PhoneScreenEffect.LEAVE -> false
                null -> act.action != app.thebox.core.input.BoxAction.BACK
            }
        }
        lifecycleScope.launch {
            documentPicker.requests.collect { req ->
                if (req == null || req.id == backupRequestId) return@collect
                backupRequestId = req.id
                try {
                    backupDocument.launch(app.thebox.ui.backup.BackupDocuments.intentFor(req))
                } catch (e: android.content.ActivityNotFoundException) {
                    documentPicker.picked(req.id, null)
                }
            }
        }
        lifecycleScope.launch {
            settings.setupPages?.pickRequests?.collect { route ->
                m3uRoute = route
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                try {
                    m3uDocument.launch(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    m3uRoute = null
                    settings.setupPages?.filePicked(route, null, null)
                }
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
