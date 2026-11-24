// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.2. Verbatim; changes go through the lead.
package app.thebox.core.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * One persisted document per domain (`app_setting` row, decision §0.4). Values are kept in memory as StateFlows;
 * [update] is main-safe: it applies in memory at once and persists on IO, coalesced per domain
 * (SETTINGS_WRITE_DEBOUNCE_MS) and flushed on ON_STOP.
 * (reviewed 2026-10-16, S1) Nothing is written for a domain before its first read completed: earlier [update]s are
 * queued and replayed on the stored document when the read lands (also after the ready timeout, which only unblocks
 * the first frame with defaults). [importInTransaction] cancels pending writes of the domains it replaces. Values that
 * are not user choices (PlaybackSettings.defaultUserAgent, test hooks) never enter the store. JSON is `ignoreUnknownKeys`, `encodeDefaults = false`, so a
 * value equal to its default is not stored and a later default change reaches users who never touched it.
 * (Stacked commit 0, S7) and `coerceInputValues = true`: an enum name this build does not know, or a JSON `null` for a
 * non-null field, decodes to **that property's default** and the document's other fields are kept (both in a stored
 * document and in a restored one). Limits: it acts only on properties with a default; an unknown value inside a
 * collection or as a map key still fails the whole document, as before.
 * (Stacked fix pass 2026-11-11, review C N9) A domain with [coerce] = false opts out: there an unknown value still fails
 * the whole document, so a restore keeps the device's document whole ("unreadable; kept"). The parental policy does this,
 * because its defaults are the less strict choices and a coerced value would weaken it.
 */
class SettingsDomain<T>(
    val name: String,
    val serializer: KSerializer<T>,
    val default: T,
    /** Carried by backups (§3.10.2). Learned or device-bound domains say false. */
    val backedUp: Boolean = true,
    /** Bumped on an incompatible change; the store passes old documents through [migrate]. */
    val schema: Int = 1,
    val migrate: (fromSchema: Int, json: JsonElement) -> JsonElement = { _, j -> j },
    /** False: no `coerceInputValues` for this domain (an unknown value fails the document; see the class comment). */
    val coerce: Boolean = true,
)

interface SettingsStore {
    /** True once every domain was read (or SETTINGS_LOAD_TIMEOUT_MS passed: defaults, logged). */
    val ready: StateFlow<Boolean>
    suspend fun awaitReady()
    /**
     * True once the stored documents were read (or the read failed for good). Unlike [ready] it does not open on the
     * load timeout, so a value read after it is the stored one and never a default standing in for it.
     */
    val loaded: StateFlow<Boolean> get() = ready
    /**
     * (M5 commit 0, additive: the `TheBoxApplication` hook of §1.3a, "settingsStore.load() first") Starts the one
     * `SELECT * FROM app_setting` on IO and returns at once; idempotent. [ready] opens when it lands.
     */
    fun load()
    fun <T> flow(domain: SettingsDomain<T>): StateFlow<T>
    fun <T> update(domain: SettingsDomain<T>, transform: (T) -> T)
    /** Writes pending changes now (ON_STOP, before a backup, before process kill in tests). */
    suspend fun flush()
    /** Backup: every [SettingsDomain.backedUp] domain as stored JSON. */
    suspend fun export(): Map<String, JsonElement>
    /**
     * Restore inside the caller's library.db transaction (§3.10.4). Unknown domains are ignored, and so are the
     * device-bound ones (`backedUp = false`). With [replace], every backed-up domain the file does not carry is reset to
     * its default (its row deleted): e.g. a backup without a `parental` document turns a local "Lock settings" off,
     * which is why a restore needs the PIN while one is set (C-B2, §3.9.2). (D-S4, code review 2026-10-16) The rows are
     * written here; the in-memory documents are published only by [commitImport] once the transaction committed.
     */
    suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean)

    /**
     * (D-S4, code review 2026-10-16; additive) After the caller's transaction around [importInTransaction]: [committed]
     * publishes the imported documents (§3.10.4 step 5 "after commit"); false (the transaction rolled back) drops them, and
     * memory keeps the values the database still holds. A store that publishes inside the import ignores it.
     */
    fun commitImport(committed: Boolean = true) = Unit
}

/** (M5 commit 0) Named constants of Appendix B that commit-0 code reads. */
object SettingsTimings {
    /** §0.5: the first frame waits at most this long for [SettingsStore.ready], then uses defaults (S1). */
    const val SETTINGS_LOAD_TIMEOUT_MS = 1_000L
    /** §5 item 7: the store's load on the emulator (measured in I-M5-10). */
    const val SETTINGS_LOAD_BUDGET_MS = 50L
    /** §2.2: writes are coalesced per domain. */
    const val SETTINGS_WRITE_DEBOUNCE_MS = 250L
}

/** The domains (single source of the names; §4.1 lists their fields). */
object Domains {
    const val GUIDE = "guide"          // GuidePrefs below (mapped onto core.guide.GuideSettings)
    const val PLAYER = "player"        // core.full.PlayerSettings
    const val PLAYBACK = "playback"    // PlaybackPrefs below
    const val DATA = "data"            // DataPrefs below
    const val NETWORK = "network"      // core.net.NetworkSettings
    const val PARENTAL = "parental"    // core.parental.ParentalPolicy
    const val REMOTE = "remote"        // core.input.KeyBindingOverrides
    const val BACKUP = "backup"        // BackupPrefs (not backed up: device-bound)
    const val UPDATE = "update"        // core.update.UpdatePrefs (not backed up)
    const val SETUP = "setup"          // SetupState (not backed up)
    /** (Storage locations commit 0, docs/design/m6-storage-locations.md §8, §12.4) core.storage.StoragePrefs (backed up). */
    const val STORAGE = "storage"
    /** (Score bug commit 0, docs/design/m6-score-bug.md §7) core.sports.SportsPrefs (backed up). */
    const val SPORTS = "sports"
    /** (Score bug commit 0, §2.6, A-S9) core.sports.SportsFollowDoc (not backed up: the followed game is device-bound). */
    const val SPORTS_FOLLOW = "sports_follow"
    /** (Weather page, docs/decisions/2026-11-19-weather-page.md) core.weather.WeatherPrefs (backed up). */
    const val WEATHER = "weather"
}

enum class ClockMode { SYSTEM, H12, H24 }

/**
 * (Owner request 2026-11-24, docs/decisions/2026-11-24-text-sizes.md) The user's text size: [factor] multiplies every
 * text size of the app (Compose text through `textSp`, which every text style is built with; the guide grid through
 * its paints). The
 * score bug has its own Size row and does not follow it.
 */
enum class TextSize(val factor: Float) { SMALL(0.9f), DEFAULT(1f), LARGE(1.1f), LARGEST(1.2f) }

/**
 * The text size this process runs with. Set once, before anything measures text (`TheBoxApplication.onCreate` reads the
 * stored value through a small boot mirror before `super.onCreate()`), and never changed afterwards: the guide's measured widths are cached per process, so the row
 * takes effect at the next start.
 */
object UiTextScale {
    @Volatile var running: TextSize = TextSize.DEFAULT
    val factor: Float get() = running.factor
}

/** Stored form of the guide/interface rows; PersistedGuideSettings maps it onto GuideSettings (SYSTEM → DateFormat.is24HourFormat). */
@kotlinx.serialization.Serializable
data class GuidePrefs(
    val clock: ClockMode = ClockMode.SYSTEM,
    val showLogos: Boolean = true,
    val showNumbers: Boolean = true,
    val performance: app.thebox.core.guide.PerformanceMode = app.thebox.core.guide.PerformanceMode.AUTO,
    /** null = automatic (Still in low-RAM mode, product §4.6). */
    val miniStill: Boolean? = null,
    /** null = automatic (Off in low-end mode). */
    val ambient: Boolean? = null,
    val exitConfirmation: app.thebox.core.guide.ExitConfirmation = app.thebox.core.guide.ExitConfirmation.BACK_TWICE,
    val openGuideOn: app.thebox.core.guide.OpenGuideOn = app.thebox.core.guide.OpenGuideOn.LAST,
    val reminderAutoSwitch: Boolean = false,
    val reminderLeadSec: Int = 60,
    /** Unused since the owner ruling of 2026-11-17 (every setting is always shown); kept so stored prefs and backups still read. */
    val advancedSettings: Boolean = false,
    /**
     * (owner ruling 2026-10-18) Programme posters from TMDB when the EPG has none. On by default: the app ships with its
     * own key (`local.properties` → `BuildConfig.TMDB_KEY`), so this works out of the box; only the programme title
     * leaves the device. There is no user key (owner ruling 2026-10-19); the former user-key flag in older
     * documents and backups is ignored on read (`ignoreUnknownKeys`).
     */
    val posters: Boolean = true,
    /**
     * (owner request 2026-11-09) "Scroll long descriptions": a description longer than the guide panel's space scrolls
     * up slowly; Off clips it with an ellipsis (docs/decisions/2026-11-09-guide-panel-description.md).
     */
    val scrollDescriptions: Boolean = true,
    /** (Owner request 2026-11-24) "Text size"; read at the app's start ([UiTextScale]). */
    val textSize: TextSize = TextSize.DEFAULT,
)

enum class SubtitleMode { OFF, ON, FORCED_ONLY }

/** User-settable part of :player's PlaybackSettings (the rest are test hooks or derived). */
@kotlinx.serialization.Serializable
data class PlaybackPrefs(
    val bufferMode: app.thebox.core.playback.BufferMode = app.thebox.core.playback.BufferMode.AUTO,
    val videoDecoderMode: app.thebox.core.playback.VideoDecoderMode = app.thebox.core.playback.VideoDecoderMode.AUTO,
    val softwareAudioFallback: Boolean = true,
    val preferSoftwareAudio: Boolean = false,
    val audioOutput: app.thebox.core.playback.AudioOutputMode = app.thebox.core.playback.AudioOutputMode.AUTO,
    val tunneling: Boolean = false,
    val afrMode: app.thebox.core.playback.AfrMode = app.thebox.core.playback.AfrMode.SEAMLESS,
    val hdrInPreview: app.thebox.core.playback.HdrPreviewMode = app.thebox.core.playback.HdrPreviewMode.POSTER,
    val preferredAudioLanguages: List<String> = emptyList(),
    /** OFF/ON map to PlaybackSettings.subtitlesEnabled; FORCED_ONLY needs a Media3 check (§9 F21) or ships as OFF/ON only. */
    val subtitles: SubtitleMode = SubtitleMode.OFF,
    val preferredTextLanguages: List<String> = emptyList(),
    val defaultAspect: app.thebox.core.playback.AspectMode = app.thebox.core.playback.AspectMode.FIT,
    val reconnectAttempts: Int = 5,
    val failoverToBackup: Boolean = true,
    val resumeAfterStandby: Boolean = true,
    /** Package name of the external player, null = none (product §4.4). */
    val externalPlayer: String? = null,
    // (M6 time-shift commit 0, docs/design/m6-timeshift.md §12; additive, defaults keep old documents valid)
    /** "Pause and rewind" (SIMPLE): the local buffer length. */
    val timeshift: TimeshiftLength = TimeshiftFeature.DEFAULT, // Off by default (owner ruling 2026-11-18); read it through [timeshiftInUse]
    /** "Keep recording while paused" (ADVANCED): how long a paused tune keeps its provider connection. */
    val timeshiftHold: TimeshiftHold = TimeshiftHold.AUTO,
    /** "Protect built-in storage" (ADVANCED): the internal daily write cap (§8.4). */
    val storageProtection: StorageProtection = StorageProtection.AUTO,
    // (Smooth playback, 2026-11-23; additive, the defaults are the behaviour before these rows existed)
    /** "Keep up with live" (ADVANCED): an HLS live channel may play 3% slower or faster to hold its distance from live. */
    val liveSpeedCorrection: Boolean = true,
    /** "Decoder hand-off" (ADVANCED). */
    val codecQueueing: app.thebox.core.playback.CodecQueueing = app.thebox.core.playback.CodecQueueing.AUTO,
    /** "Audio buffer" (ADVANCED). */
    val audioBuffer: app.thebox.core.playback.AudioBufferSize = app.thebox.core.playback.AudioBufferSize.AUTO,
)

/**
 * (M6 time-shift commit 0, §12) "Pause and rewind". AUTO = 30 min on ≤ 16 GB storage, else 1 hour (lead ruling 2);
 * PAUSE_ONLY saves only while paused (§4.5); OFF = no buffer. Persisted by name; append-only.
 */
@kotlinx.serialization.Serializable enum class TimeshiftLength { AUTO, MIN_15, MIN_30, H_1, H_2, PAUSE_ONLY, OFF }

/**
 * (Owner ruling 2026-11-18, docs/decisions/2026-11-18-pause-rewind-off.md) Pause and rewind is switched off in this
 * build: a tester's box stuttered about every 4 s and the buffer (the only thing that writes the stream to storage
 * while watching) is the suspect, with no such device to test on. While [AVAILABLE] is false nothing is saved, whatever
 * the stored setting says (an older install or a restored backup may hold Auto), and Settings shows the row greyed
 * out as "Coming soon". Set [SHIPPED] to true to bring the feature back; stored choices are kept.
 *
 * The unit-test suites still exercise the feature: the Gradle test tasks set the system property
 * `thebox.timeshift.tests`, under which it is available and the default length is Auto, as those tests were written.
 * The property does not exist on a device, so the app never sees that mode.
 */
object TimeshiftFeature {
    /** What the app ships with. */
    const val SHIPPED = false
    private val underTest: Boolean = runCatching { System.getProperty("thebox.timeshift.tests") == "1" }.getOrDefault(false)
    /** Mutable for the tests of the switched-off state only. */
    @Volatile var available: Boolean = SHIPPED || underTest
    /** The default rewind length: Off in the app (all recording is off by default); Auto in the unit-test suites. */
    val DEFAULT: TimeshiftLength = if (underTest) TimeshiftLength.AUTO else TimeshiftLength.OFF
}

/** The rewind length playback uses: the stored one while [TimeshiftFeature.available], else Off. */
val PlaybackPrefs.timeshiftInUse: TimeshiftLength get() = if (TimeshiftFeature.available) timeshift else TimeshiftLength.OFF

/** (M6 commit 0, §7.3, §12) "Keep recording while paused". AUTO = until the buffer is full, on every account (lead ruling 3). */
@kotlinx.serialization.Serializable enum class TimeshiftHold { AUTO, MIN_10, MIN_30, H_1, UNTIL_FULL }

/** (M6 commit 0, §8.4, §12) "Protect built-in storage". AUTO = the 12 GB/day cap on ≤ 16 GB storage, none above. */
@kotlinx.serialization.Serializable enum class StorageProtection { AUTO, OFF }

/** User-settable part of :data's DataSettingsSnapshot. */
@kotlinx.serialization.Serializable
data class DataPrefs(
    /** 0 = Auto (max catch-up days, minimum 1; product §4.3). */
    val epgPastDays: Int = 0,
    val epgFutureDays: Int = 7,
    /** (reviewed 2026-10-16, S12) The existing core.match.MatchLevel (MatchContracts.kt:32), not a new enum. */
    val matchLevel: app.thebox.core.match.MatchLevel = app.thebox.core.match.MatchLevel.SUGGEST_FUZZY,
    val storeDescriptions: Boolean = true,
    val epgRefreshIntervalH: Int = 12,
    /** (N4) "Daily at a set time": hour 0–23; null = use [epgRefreshIntervalH]. */
    val epgDailyAtHour: Int? = null,
    val globalShiftMin: Int = 0,
)

@kotlinx.serialization.Serializable
data class SetupState(val completed: Boolean = false, val lastAddedPlaylistId: Long? = null, val pinOfferedFor: Set<String> = emptySet())

@kotlinx.serialization.Serializable
data class BackupPrefs(
    val weekly: Boolean = true,
    val lastAutoAtMs: Long? = null,
    val lastAutoFile: String? = null,
    /** A Keystore-wrapped backup passphrase exists (secure blob `backup-passphrase`), so weekly backups include logins. */
    val passphraseStored: Boolean = false,
)
