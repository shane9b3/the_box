package app.thebox.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.thebox.MainActivity
import app.thebox.core.common.Outcome
import app.thebox.core.epg.EpgStore
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ReminderBook
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.source.PlaylistType
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.Trigger
import app.thebox.player.api.PlaybackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Debug-only guide seeding and switches (docs/design/m3-guide.md §1.5, §5.6; owner WP-D). Not in release builds.
 *
 * `thebox-debug://guide?seed=<panel url>&user=test&pass=test` adds the Xtream playlist for the panel (idempotent: an
 * existing Xtream playlist with the same host is reused), runs the playlist refresh and the EPG build synchronously
 * (off the main thread), then opens [MainActivity] and finishes. Switches, applied to the in-memory
 * [GuideSettingsSource] **before** the seed so the guide starts with them:
 * - `lowend=1` → [PerformanceMode.LOW]; `lowend=0` → [PerformanceMode.HIGH]. (0 forces High rather than Auto: the API 30
 *   TV emulator can classify as low-end by `memoryClass` (§0.11), and a test that says "not low-end" must get the full
 *   path deterministically.) `perf=auto|high|low` sets the mode explicitly and wins over `lowend`.
 * - `still=0|1` → `miniStill`; `clock24=0|1`; `exit=twice|ask|none` → `exitConfirmation`; `unlocked=1` → `debugUnlocked`.
 * Other parameters: `reset=1` deletes every playlist first (a test that needs exactly one source), `refresh=0` skips the
 * refresh and the EPG build when the playlist already exists, `open=0` does not start MainActivity (tests launch it
 * through ActivityScenario).
 *
 * The outcome is published on [status] (same process as the instrumented suite) and logged under [TAG].
 */
@AndroidEntryPoint
class GuideDebugReceiver : ComponentActivity() {
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var refresher: PlaylistRefresher
    @Inject lateinit var epgImporter: EpgImporter
    @Inject lateinit var epgStore: EpgStore
    @Inject lateinit var channels: ChannelRepository
    @Inject lateinit var guideSettings: GuideSettingsSource
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            text = "The Box debug: preparing the guide…"
        }
        setContentView(text)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data
        if (uri == null || uri.scheme != PlaybackDebugActivity.SCHEME || uri.host != HOST) {
            finish()
            return
        }
        val settingsResult = applySettings(uri)
        val seed = uri.getQueryParameter("seed")
        val open = uri.getQueryParameter("open") != "0"
        if (seed.isNullOrBlank()) {
            publish(SeedStatus(state = SeedState.DONE, settingsApplied = settingsResult == null, settingsError = settingsResult))
            if (open) openGuide()
            finish()
            return
        }
        val seq = ++requestSeq
        publish(SeedStatus(seq = seq, state = SeedState.RUNNING, settingsApplied = settingsResult == null, settingsError = settingsResult))
        // The seed outlives this activity (a rotation or a second deep link must not cancel an import half-way).
        appScope.launch {
            val result = seedMutex.withLock {
                withContext(Dispatchers.IO) {
                    seed(
                        base = seed.trimEnd('/'),
                        user = uri.getQueryParameter("user") ?: "test",
                        pass = uri.getQueryParameter("pass") ?: "test",
                        reset = uri.getQueryParameter("reset") == "1",
                        refresh = uri.getQueryParameter("refresh") != "0",
                    )
                }
            }.copy(seq = seq, settingsApplied = settingsResult == null, settingsError = settingsResult)
            publish(result)
            withContext(Dispatchers.Main) {
                text.text = if (result.state == SeedState.DONE) "Seeded: ${result.channels} channels" else "Seed failed: ${result.error}"
                if (open && result.state == SeedState.DONE) openGuide()
                finish()
            }
        }
    }

    /** Null on success, else why the settings were not applied (WP-B's InMemoryGuideSettings may still be a stub). */
    private fun applySettings(uri: Uri): String? {
        val names = uri.queryParameterNames
        if (SETTING_KEYS.none { it in names }) return null
        return try {
            guideSettings.update { applySwitches(it, uri) }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "guide settings not applied: $t")
            t.toString()
        }
    }

    private suspend fun seed(base: String, user: String, pass: String, reset: Boolean, refresh: Boolean): SeedStatus {
        try {
            if (reset) playlists.all().forEach { playlists.delete(it.id) }
            val host = PlaylistRepository.displayHost(base)
            val existing = playlists.all().firstOrNull { it.type == PlaylistType.XTREAM && it.displaySource == host }
            val id = existing?.id ?: when (val r = playlists.addXtream("Guide seed $host", listOf(base), user, pass)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return SeedStatus(state = SeedState.FAILED, error = "addXtream: ${r.error.code}")
            }
            if (existing == null || refresh) {
                Log.i(TAG, "refreshing playlist $id ($host)")
                val rr = refresher.refresh(id, Trigger.MANUAL)
                if (!rr.ok) return SeedStatus(state = SeedState.FAILED, playlistId = id, error = "refresh: ${rr.error?.code}")
                Log.i(TAG, "building the EPG")
                val er = epgImporter.build(Trigger.MANUAL, force = true)
                if (er.error != null && epgStore.info.value == null) {
                    return SeedStatus(state = SeedState.FAILED, playlistId = id, error = "epg: ${er.error?.code}")
                }
            }
            val count = channels.browseAll(id, unlocked = true).first().size
            val gen = epgStore.info.value
            Log.i(TAG, "seeded playlist $id: $count channels, epg generation ${gen?.generation} (${gen?.programmes} programmes)")
            return SeedStatus(state = SeedState.DONE, playlistId = id, channels = count, generation = gen?.generation, programmes = gen?.programmes ?: 0)
        } catch (t: Throwable) {
            Log.e(TAG, "seed failed", t)
            return SeedStatus(state = SeedState.FAILED, error = t.toString())
        }
    }

    private fun openGuide() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    enum class SeedState { IDLE, RUNNING, DONE, FAILED }

    data class SeedStatus(
        val seq: Int = 0,
        val state: SeedState = SeedState.IDLE,
        val playlistId: Long = 0,
        val channels: Int = 0,
        val generation: Long? = null,
        val programmes: Int = 0,
        val error: String? = null,
        val settingsApplied: Boolean = true,
        val settingsError: String? = null,
    )

    companion object {
        const val TAG = "GuideDebug"
        const val HOST = "guide"
        private val SETTING_KEYS = listOf("lowend", "perf", "still", "clock24", "exit", "unlocked")
        private val seedMutex = Mutex()
        @Volatile private var requestSeq = 0
        private val statusFlow = MutableStateFlow(SeedStatus())

        /** Last seed outcome (in-process; the instrumented suite waits on it). */
        val status: StateFlow<SeedStatus> get() = statusFlow

        private fun publish(s: SeedStatus) {
            statusFlow.value = s
            Log.i(TAG, "status: $s")
        }

        /** Pure mapping of the deep-link switches onto [GuideSettings] (documented on the class). */
        fun applySwitches(s: GuideSettings, uri: Uri): GuideSettings {
            var out = s
            when (uri.getQueryParameter("lowend")) {
                "1" -> out = out.copy(performance = PerformanceMode.LOW)
                "0" -> out = out.copy(performance = PerformanceMode.HIGH)
            }
            when (uri.getQueryParameter("perf")?.lowercase()) {
                "auto" -> out = out.copy(performance = PerformanceMode.AUTO)
                "high" -> out = out.copy(performance = PerformanceMode.HIGH)
                "low" -> out = out.copy(performance = PerformanceMode.LOW)
            }
            uri.getQueryParameter("still")?.let { out = out.copy(miniStill = it == "1") }
            uri.getQueryParameter("clock24")?.let { out = out.copy(clock24 = it == "1") }
            when (uri.getQueryParameter("exit")?.lowercase()) {
                "twice" -> out = out.copy(exitConfirmation = ExitConfirmation.BACK_TWICE)
                "ask" -> out = out.copy(exitConfirmation = ExitConfirmation.ASK)
                "none" -> out = out.copy(exitConfirmation = ExitConfirmation.NONE)
            }
            uri.getQueryParameter("unlocked")?.let { out = out.copy(debugUnlocked = it == "1") }
            return out
        }
    }
}

/** What the M3 instrumented suite (§5.5) reaches through `EntryPointAccessors.fromApplication`. Debug builds only. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GuideDebugEntryPoint {
    fun probe(): GuideDebugProbe
    fun guideSettings(): GuideSettingsSource
    fun keyCapabilities(): KeyCapabilities
    fun reminderBook(): ReminderBook
    fun playlists(): PlaylistRepository
    fun refresher(): PlaylistRefresher
    fun epgImporter(): EpgImporter
    fun epgStore(): EpgStore
    fun channels(): ChannelRepository
    fun controller(): PlaybackController
    fun search(): GuideSearch
}
