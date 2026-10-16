package app.thebox.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.thebox.MainActivity
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.common.Outcome
import app.thebox.core.epg.EpgStore
import app.thebox.core.full.BackInPlayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.UpDownInPlayer
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
import app.thebox.ui.full.PlayerDebugProbe
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
 * Player switches (docs/design/m4-player-ux.md §1.6, §5.5; owner WP-E), applied to the in-memory [PlayerSettingsSource]
 * before the seed, last occurrence wins:
 * - `infobar=<ms>` → `infoBarMs`; `numwait=<ms>` → `numberEntryWaitMs`;
 * - `updown=quick|zap|list` → `upDown` QUICK_BAR_AND_INFO / ZAP / CHANNEL_LIST; `invert=0|1` → `invertChannelDirection`;
 * - `back=guide|list` → `back` GUIDE / CHANNEL_LIST.
 * - `padding=<before>[,<after>]` (seconds) → the seeded playlist's `paddingBeforeSec` / `paddingAfterSec` (applied after the
 *   playlist exists, also with `refresh=0`; a whole-row update, debug only). Needs `seed`.
 * Other parameters: `reset=1` deletes every playlist first (a test that needs exactly one source), `refresh=0` skips the
 * refresh and the EPG build when the playlist already exists, `open=0` does not start MainActivity (tests launch it
 * through ActivityScenario). `m3u=1` seeds the panel as an **M3U URL** playlist
 * (`<seed>/get.php?username=…&password=…&type=m3u_plus&output=ts`, idempotent by host like the Xtream form) instead of
 * an Xtream login: the M4 suite needs it because `catchup-correction` exists only in the M3U (I-M4-13b).
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
    @Inject lateinit var playerSettings: PlayerSettingsSource
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    /** M5 (WP-E): the settings/updater/diagnostics harness. */
    @Inject lateinit var settingsProbe: SettingsDebugProbe

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
        // M5 (docs/design/m5-setup-settings.md §6.5; WP-E): settings route, wipe, clock offset, update manifest, phone
        // port and keystore reset, on the `guide` host (the debug manifest registers only that one) or on their own
        // hosts once the manifest lists them. Seeding on the same link still runs afterwards.
        if (uri != null && uri.scheme == PlaybackDebugActivity.SCHEME && SettingsDebugProbe.isM5(uri)) {
            handleM5(uri)
            if (uri.host != HOST || uri.getQueryParameter("seed").isNullOrBlank()) {
                finish()
                return
            }
        }
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
                        m3u = uri.lastParam("m3u") == "1",
                        padding = parsePadding(uri.lastParam("padding")),
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

    /**
     * The M5 switches (§6.5), applied in this order: `keystore_reset=1` (or host `keystore-reset`), `wipe=1` (host
     * `wipe`), `clock_offset_ms=N` (host `clock?offsetMs=N`), `phone_port=N` (host `phone?port=N`),
     * `update_manifest=URL` + `update_key=HEX` (host `update?manifest=URL&key=HEX`), `update_check=1|manual`,
     * `settings=<route>` / `route=<route>` (host `settings?route=…`; [SettingsDebugProbe.parseRoute]). The outcome is
     * published on [m5Status] (seq + done) for the suite.
     */
    private fun handleM5(uri: Uri) {
        val probe = settingsProbe
        val seq = ++m5Seq
        val keystore = uri.host == "keystore-reset" || uri.lastParam("keystore_reset") == "1"
        val wipe = uri.host == "wipe" || uri.lastParam("wipe") == "1"
        (uri.lastParam("clock_offset_ms") ?: uri.lastParam("offsetMs"))?.toLongOrNull()?.let { app.thebox.core.diag.DebugOverrides.clockOffsetMs = it }
        (uri.lastParam("phone_port") ?: if (uri.host == "phone") uri.lastParam("port") else null)?.toIntOrNull()?.let { app.thebox.core.diag.DebugOverrides.phonePort = it }
        // (M5 integration, I-M5-25) a plain-http custom DoH endpoint (the fake panel) is accepted in debug builds only.
        uri.lastParam("doh_http")?.let { app.thebox.core.diag.DebugOverrides.allowHttpDoh = it == "1" }
        (uri.lastParam("update_manifest") ?: uri.lastParam("manifest"))?.let { probe.updateEndpoint.manifestUrl = it.ifBlank { null } }
        (uri.lastParam("update_key") ?: if (uri.host == "update") uri.lastParam("key") else null)?.let { probe.updateEndpoint.runtimeKeyHex = it.ifBlank { null } }
        val check = uri.lastParam("update_check")
        val route = SettingsDebugProbe.parseRoute(uri.lastParam("settings") ?: uri.lastParam("route"))
        m5StatusFlow.value = M5Status(seq, done = false)
        appScope.launch {
            var error: String? = null
            try {
                if (keystore) probe.keystoreReset()
                if (wipe) probe.wipe(restart = uri.lastParam("restart") != "0")
                when (check) {
                    "1", "auto" -> probe.updater.runCheck(manual = false)
                    "manual" -> probe.updater.runCheck(manual = true)
                }
                if (route != null) {
                    if (uri.lastParam("open") != "0") withContext(Dispatchers.Main) { openGuide() }
                    probe.open(route)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "M5 switch failed", t)
                error = t.toString()
            }
            m5StatusFlow.value = M5Status(seq, done = true, error = error)
            Log.i(TAG, "m5 status: ${m5StatusFlow.value} (keystore=$keystore wipe=$wipe check=$check route=$route)")
        }
    }

    data class M5Status(val seq: Int = 0, val done: Boolean = true, val error: String? = null)

    /** Null on success, else why the settings were not applied (an in-memory settings source may still be a stub). */
    private fun applySettings(uri: Uri): String? {
        val names = uri.queryParameterNames
        val errors = ArrayList<String>(2)
        if (SETTING_KEYS.any { it in names }) {
            try {
                guideSettings.update { applySwitches(it, uri) }
            } catch (t: Throwable) {
                Log.w(TAG, "guide settings not applied: $t")
                errors += t.toString()
            }
        }
        if (PLAYER_KEYS.any { it in names }) {
            try {
                playerSettings.update { applyPlayerSwitches(it, uri) }
            } catch (t: Throwable) {
                Log.w(TAG, "player settings not applied: $t")
                errors += "player: $t"
            }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private suspend fun seed(
        base: String,
        user: String,
        pass: String,
        reset: Boolean,
        refresh: Boolean,
        m3u: Boolean = false,
        padding: Pair<Int, Int?>? = null,
    ): SeedStatus {
        try {
            if (reset) playlists.all().forEach { playlists.delete(it.id) }
            val host = PlaylistRepository.displayHost(base)
            val type = if (m3u) PlaylistType.M3U_URL else PlaylistType.XTREAM
            val existing = playlists.all().firstOrNull { it.type == type && it.displaySource == host }
            val id = existing?.id ?: run {
                val added = if (m3u) {
                    playlists.addM3uUrl("Player seed $host", "$base/get.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}&type=m3u_plus&output=ts")
                } else {
                    playlists.addXtream("Guide seed $host", listOf(base), user, pass)
                }
                when (added) {
                    is Outcome.Ok -> added.value
                    is Outcome.Err -> return SeedStatus(state = SeedState.FAILED, error = "${if (m3u) "addM3uUrl" else "addXtream"}: ${added.error.code}")
                }
            }
            if (padding != null) {
                playlists.get(id)?.let { p ->
                    val after = padding.second ?: p.paddingAfterSec
                    playlists.update(p.copy(paddingBeforeSec = padding.first, paddingAfterSec = after))
                    Log.i(TAG, "playlist $id padding set to ${padding.first}/$after s")
                }
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
        private val PLAYER_KEYS = listOf("infobar", "numwait", "updown", "invert", "back")
        private val seedMutex = Mutex()
        @Volatile private var requestSeq = 0
        private val statusFlow = MutableStateFlow(SeedStatus())

        /** Last seed outcome (in-process; the instrumented suite waits on it). */
        val status: StateFlow<SeedStatus> get() = statusFlow

        @Volatile private var m5Seq = 0
        private val m5StatusFlow = MutableStateFlow(M5Status())

        /** Last M5 switch outcome (§6.5; the M5 suite waits for `seq > before && done`). */
        val m5Status: StateFlow<M5Status> get() = m5StatusFlow

        private fun publish(s: SeedStatus) {
            statusFlow.value = s
            Log.i(TAG, "status: $s")
        }

        /**
         * A switch given twice takes its **last** value (device bug G2, §2.13 item 20): the suite appends per-class
         * switches after the defaults, and `getQueryParameter` returns the first occurrence.
         */
        private fun Uri.lastParam(key: String): String? = getQueryParameters(key).lastOrNull()

        /** `padding=<before>[,<after>]` in seconds; null when absent or malformed. */
        fun parsePadding(v: String?): Pair<Int, Int?>? {
            if (v.isNullOrBlank()) return null
            val parts = v.split(',')
            val before = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val after = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            return before to after
        }

        /** Pure mapping of the player switches onto [PlayerSettings] (documented on the class). Unknown values are ignored. */
        fun applyPlayerSwitches(s: PlayerSettings, uri: Uri): PlayerSettings {
            var out = s
            uri.lastParam("infobar")?.toLongOrNull()?.takeIf { it > 0 }?.let { out = out.copy(infoBarMs = it) }
            uri.lastParam("numwait")?.toLongOrNull()?.takeIf { it > 0 }?.let { out = out.copy(numberEntryWaitMs = it) }
            when (uri.lastParam("updown")?.lowercase()) {
                "quick" -> out = out.copy(upDown = UpDownInPlayer.QUICK_BAR_AND_INFO)
                "zap" -> out = out.copy(upDown = UpDownInPlayer.ZAP)
                "list" -> out = out.copy(upDown = UpDownInPlayer.CHANNEL_LIST)
            }
            uri.lastParam("invert")?.let { out = out.copy(invertChannelDirection = it == "1") }
            when (uri.lastParam("back")?.lowercase()) {
                "guide" -> out = out.copy(back = BackInPlayer.GUIDE)
                "list" -> out = out.copy(back = BackInPlayer.CHANNEL_LIST)
            }
            return out
        }

        /** Pure mapping of the deep-link switches onto [GuideSettings] (documented on the class). */
        fun applySwitches(s: GuideSettings, uri: Uri): GuideSettings {
            var out = s
            when (uri.lastParam("lowend")) {
                "1" -> out = out.copy(performance = PerformanceMode.LOW)
                "0" -> out = out.copy(performance = PerformanceMode.HIGH)
            }
            when (uri.lastParam("perf")?.lowercase()) {
                "auto" -> out = out.copy(performance = PerformanceMode.AUTO)
                "high" -> out = out.copy(performance = PerformanceMode.HIGH)
                "low" -> out = out.copy(performance = PerformanceMode.LOW)
            }
            uri.lastParam("still")?.let { out = out.copy(miniStill = it == "1") }
            uri.lastParam("clock24")?.let { out = out.copy(clock24 = it == "1") }
            when (uri.lastParam("exit")?.lowercase()) {
                "twice" -> out = out.copy(exitConfirmation = ExitConfirmation.BACK_TWICE)
                "ask" -> out = out.copy(exitConfirmation = ExitConfirmation.ASK)
                "none" -> out = out.copy(exitConfirmation = ExitConfirmation.NONE)
            }
            uri.lastParam("unlocked")?.let { out = out.copy(debugUnlocked = it == "1") }
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
    /** The logo cache (I-16 reads `LogoCache.capacity`). */
    fun guideImages(): app.thebox.core.guide.GuideImages
    /** M4 (docs/design/m4-player-ux.md §2.8): the live player machine and the archive session. */
    fun playerProbe(): PlayerDebugProbe
    fun playerSettings(): PlayerSettingsSource
    fun archivePlayback(): ArchivePlayback
}
