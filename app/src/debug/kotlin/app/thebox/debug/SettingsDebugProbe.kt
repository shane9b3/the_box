package app.thebox.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.thebox.MainActivity
import app.thebox.core.diag.DebugOverrides
import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.LogRing
import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideEvent
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.settings.PhonePurpose
import app.thebox.data.db.dao.CredentialDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.di.ApplicationScope
import app.thebox.data.security.AeadProvider
import app.thebox.data.security.KeyResetFlag
import app.thebox.ui.diag.DiagPages
import app.thebox.update.DebugUpdateEndpoint
import app.thebox.update.InstallResultReceiver
import app.thebox.update.UpdaterImpl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only (docs/design/m5-setup-settings.md §6.3, §6.5; owner WP-E): what the M5 instrumented suite reads
 * and switches. It derives the settings machine from the guide machine (`GuideStateMachine.settings`, attached by
 * WP-A's view model), so no other WP's file needs a probe hook. Release builds compile none of this (the debug source
 * set), so the "release no-op" of §6.5 is the absence of the class: nothing in `src/main` refers to it.
 */
@Singleton
class SettingsDebugProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guide: GuideDebugProbe,
    val store: SettingsStore,
    val networkTests: app.thebox.core.diag.NetworkTests,
    val library: app.thebox.core.guide.GuideLibrary,
    private val phone: PhoneLink,
    private val parentalSession: ParentalSession,
    val updater: UpdaterImpl,
    val installer: InstallResultReceiver,
    val updateEndpoint: DebugUpdateEndpoint,
    val diagnostics: Diagnostics,
    val diagSources: DiagSources,
    val diagPages: DiagPages,
    val logRing: LogRing,
    private val wipeLibrary: app.thebox.data.diag.LibraryWipe,
    /** I-M5-22: the v3 → v4 migration on the device. */
    val migrationCheck: app.thebox.data.diag.MigrationCheck,
    /** I-M5-16: the backup service (WP-D). */
    val backup: app.thebox.core.backup.BackupService,
    private val aead: AeadProvider,
    private val keyReset: KeyResetFlag,
    private val credentials: CredentialDao,
    private val playlists: PlaylistDao,
    @ApplicationScope private val scope: CoroutineScope,
    /** (M5 integration) WP-B's in-memory form drafts: "Clear data" must not keep a draft's created playlist id. */
    private val setupDrafts: app.thebox.core.setup.SetupDraftStore,
) {
    val settings: SettingsMachine? get() = guide.current?.settings
    fun settingsUi(): SettingsUiState? = settings?.ui?.value
    fun lan(): LanServerState = phone.state.value
    fun parental(): ParentalState = parentalSession.state.value
    /** Every backed-up domain as stored JSON (§2.2 `export`). */
    suspend fun documents(): Map<String, JsonElement> = store.export()

    /** Opens Settings on [route] through the guide machine (`GuideEvent.OpenSetup`), as the entry points do. */
    fun open(route: SettingsRoute) {
        scope.launch(Dispatchers.Main) {
            repeat(MACHINE_WAIT_STEPS) {
                guide.current?.let { m ->
                    m.dispatch(GuideEvent.OpenSetup(route))
                    return@launch
                }
                delay(100)
            }
            Log.w(TAG, "no guide machine: Settings not opened on $route")
        }
    }

    /**
     * The in-process equivalent of Clear data (§6.5 `wipe`): every library row (playlists, credentials, user data,
     * `app_setting`), the parental prefs and the guide session; the store gets an empty replace-import so its memory
     * matches; then MainActivity restarts.
     */
    suspend fun wipe(restart: Boolean = true) {
        withContext(Dispatchers.IO) {
            wipeLibrary.clearAll()
            for (name in listOf(app.thebox.data.security.PrefsKeyResetFlag.PREFS, "guide_session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { store.importInTransaction(emptyMap(), replace = true) }
            // (M5 integration) A replace leaves the device-bound domains alone (restore semantics); "Clear data" must not:
            // with `setup.completed` still true the next start skipped Welcome.
            store.update(app.thebox.core.settings.state.SetupDomain) { app.thebox.core.settings.SetupState() }
            store.update(app.thebox.core.backup.BackupDomain) { app.thebox.core.settings.BackupPrefs() }
            store.update(app.thebox.core.update.UpdateDomain) { app.thebox.core.update.UpdatePrefs() }
            setupDrafts.clear()
        }
        // (M5 integration) The parental session re-reads the (now absent) PIN and forgets the unlock and Open anyway.
        (parentalSession as? app.thebox.data.parental.ParentalSessionImpl)?.let { it.relock(app.thebox.core.parental.RelockReason.APP_STOPPED); it.refresh() }
        DebugOverrides.reset()
        if (restart) restartActivity()
    }

    /**
     * `thebox-debug://keystore-reset` (§6.5, S8, I-M5-28): the permanent-loss steps of `TinkCredentialStore.wipe()`
     * in its order: keyset reset, the key-reset flag first, every credential row, playlists flagged "Enter the login".
     */
    suspend fun keystoreReset() = withContext(Dispatchers.IO) {
        runCatching { aead.reset() }
        keyReset.markPinLost()
        credentials.deleteAll()
        playlists.setNeedsReauthAll()
    }

    fun restartActivity() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    companion object {
        const val TAG = "SettingsDebug"
        private const val MACHINE_WAIT_STEPS = 100

        /**
         * `route=` values of §6.5 (plus the others the suite needs): playback, playlist:{id}, mapping, parental, backup,
         * diag, about, licences, sync, nettest:{id}, network, root, firstrun, add, phone, logs-phone, pauserewind, saveto,
         * findcomputers.
         */
        fun parseRoute(spec: String?): SettingsRoute? {
            val s = spec?.trim()?.lowercase() ?: return null
            val arg = s.substringAfter(':', "").toLongOrNull()
            return when (s.substringBefore(':')) {
                "root" -> SettingsRoute.Root
                "firstrun" -> SettingsRoute.FirstRun
                "playback" -> SettingsRoute.Section(SectionId.PLAYBACK)
                "guide" -> SettingsRoute.Section(SectionId.GUIDE)
                "interface" -> SettingsRoute.Section(SectionId.INTERFACE)
                "remote" -> SettingsRoute.Section(SectionId.REMOTE)
                "channels" -> SettingsRoute.Section(SectionId.CHANNELS)
                "playlists" -> SettingsRoute.Section(SectionId.PLAYLISTS)
                "network" -> SettingsRoute.Section(SectionId.NETWORK)
                "parental" -> SettingsRoute.Section(SectionId.PARENTAL)
                "about" -> SettingsRoute.Section(SectionId.ABOUT)
                "playlist" -> arg?.let { SettingsRoute.Playlist(it) }
                "source" -> arg?.let { SettingsRoute.PlaylistSource(it) }
                "mapping" -> SettingsRoute.ButtonMapping
                "backup" -> SettingsRoute.Backup
                "diag" -> SettingsRoute.Diagnostics
                "licences" -> SettingsRoute.Licences
                "sync" -> SettingsRoute.SyncStatus
                "nettest" -> arg?.let { SettingsRoute.NetworkTest(it) }
                "add" -> SettingsRoute.AddSource()
                "phone" -> SettingsRoute.PhoneSetup()
                "logs-phone" -> SettingsRoute.PhoneSetup(purpose = PhonePurpose.LOGS_DOWNLOAD)
                "hidden" -> arg?.let { SettingsRoute.HiddenChannels(it) }
                "rules" -> arg?.let { SettingsRoute.HideRules(it) }
                "groups" -> arg?.let { SettingsRoute.Groups(it) }
                "fixguide" -> SettingsRoute.FixGuide()
                // (Save-location integration, 2026-10-24) the storage pages (m6-storage-locations §9.2).
                "pauserewind" -> SettingsRoute.PauseRewind
                "saveto" -> SettingsRoute.SaveLocation()
                "findcomputers" -> SettingsRoute.FindComputers
                else -> null
            }
        }

        /** True when [uri] carries one of the M5 switches (on the `guide` host or one of the §6.5 hosts). */
        fun isM5(uri: Uri): Boolean = uri.host in M5_HOSTS || M5_KEYS.any { it in uri.queryParameterNames }

        val M5_HOSTS = setOf("settings", "wipe", "clock", "update", "phone", "keystore-reset")
        val M5_KEYS = listOf("settings", "route", "wipe", "clock_offset_ms", "offsetMs", "update_manifest", "manifest", "update_key", "update_check", "phone_port", "keystore_reset", "doh_http")
    }
}

/** What the M5 instrumented suite reaches through `EntryPointAccessors.fromApplication`. Debug builds only. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsDebugEntryPoint {
    fun settingsProbe(): SettingsDebugProbe
}
