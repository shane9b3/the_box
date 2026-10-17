// Contract (M3 commit 0): docs/design/m3-guide.md §2.4. Verbatim; changes go through the lead.
package app.thebox.core.guide

import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the grid lists. Persisted by these fields (never by channel_group.id). */
sealed interface GuideSource {
    data class Favourites(val listId: Long) : GuideSource
    data object Recent : GuideSource
    data class All(val playlistId: Long) : GuideSource
    data class Group(val playlistId: Long, val groupKey: String) : GuideSource
}

enum class SourceSection { FAVOURITES, RECENT, ALL, GROUP }

data class GuideSourceEntry(
    val source: GuideSource,
    val label: String,
    val count: Int,
    val section: SourceSection,
    /** Non-null for ALL/GROUP when more than one playlist is enabled (section header in the panel). */
    val playlistName: String?,
    /**
     * (M5, docs/design/m5-setup-settings.md §2.12 item 3) True only when the entry is a locked group and access is not
     * OPEN; [count] is then the group's channel count.
     */
    val locked: Boolean = false,
    /**
     * (U-S11, code review 2026-10-16; additive) A [locked] entry while access is FILTERED_NO_PIN: no PIN protects it, so
     * the panels draw the amber `18+` chip instead of the padlock (§3.9.1). Set by `ParentalRules.visibleEntries`.
     */
    val notProtected: Boolean = false,
)

/** Ordered rows of one source. [version] changes whenever the order or membership changes. */
class GuideLineup(
    val source: GuideSource,
    val version: Long,
    val rowIds: LongArray,
    /** Upper bound of archive days over the lineup (§3.3.4), for the earliest browsable minute. */
    val archiveDaysUpperBound: Int,
)

enum class GroupAction { HIDE, MOVE_UP, MOVE_DOWN }

/**
 * (M5, docs/design/m5-setup-settings.md §2.12 item 9, S10) Every query takes the caller's [ParentalAccess] explicitly
 * (fed by WP-C from `ParentalSession.state`; a source in `openedAnyway` is queried with OPEN); no query reads global
 * parental state. Semantic change for an existing caller once WP-C lands §4.3: [sources] lists locked groups with
 * `locked = true` instead of omitting them (unless `lockedInGuide = HIDE`). Until then (commit 0) OPEN behaves like
 * M3's `unlocked = true` and every other value like `unlocked = false`.
 */
interface GuideLibrary {
    /** Groups panel content (§3.8.1). Re-emits on library changes. */
    fun sources(access: app.thebox.core.parental.ParentalAccess): Flow<List<GuideSourceEntry>>
    /** Re-emits when membership/order changes (refresh finish, favourites edit, hide, reorder). */
    fun lineup(source: GuideSource, access: app.thebox.core.parental.ParentalAccess): Flow<GuideLineup>
    /** Rows for rowIds[from, to) in order; an id purged meanwhile yields null. Main-safe (switches to IO). */
    suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?>
    suspend fun rowIdOf(identity: ChannelIdentity): Long?
    /** Number entry: in [playlistId]'s numbering (M1 §3.3.3); null playlist = first enabled playlist. */
    suspend fun byNumber(playlistId: Long?, number: Int, access: app.thebox.core.parental.ParentalAccess): GuideChannel?
    suspend fun isFavourite(identity: ChannelIdentity): Boolean
    /** Adds to / removes from the default list (FavouritesRepository.defaultList). */
    suspend fun setFavourite(identity: ChannelIdentity, on: Boolean)
    suspend fun hideChannel(identity: ChannelIdentity)
    suspend fun groupAction(entry: GuideSourceEntry, action: GroupAction)
    /** The last watched channel (channel_prefs.last_watched_at), for "On launch: guide with last channel". */
    suspend fun lastWatched(): GuideChannel?
    /**
     * (reviewed 2026-10-14, B5/F3) Ticks (debounced 500 ms) whenever a row's content may have changed without the
     * lineup's membership changing: tables channel, channel_prefs, playlist, epg_mapping, epg_match, epg_source.
     */
    val rowContent: Flow<Long>
}

/** Block cache over [GuideLibrary.channels] (§3.2.1). Main-thread API; loads on IO. */
interface GuideRowsCache : GuideRows {
    fun setLineup(lineup: GuideLineup)
    /** Ensure blocks covering [from, to) are loaded or loading. */
    fun ensure(from: Int, to: Int)
    /** Emits [GuideRows.version] after every block arrival. */
    val changes: StateFlow<Long>
    /** Lineup position [index] of the cache's *current* lineup, loading its block if needed. Not used by LineupZapList (S9, §3.16.3). */
    suspend fun channelAt(index: Int): GuideChannel?
    /** (reviewed 2026-10-14, B5/F3) Reload every loaded block in place (keeps the lineup and indices); bumps [version] and emits [changes]. */
    fun reloadLoaded()
    /** (amended 2026-10-14, §2.13 item 6) `onTrimMemory`: drop blocks that do not intersect `[keepFrom, keepTo)` (§4.7). */
    fun trim(keepFrom: Int, keepTo: Int) = Unit
}

fun interface GuideRowsCacheFactory {
    fun create(library: GuideLibrary, scope: kotlinx.coroutines.CoroutineScope): GuideRowsCache
}

/**
 * Programme windows (§3.2). Owns the `box-guide-epg` thread and the EpgReader. [ensure] is cheap and main-safe;
 * results arrive on [snapshots]. [close] stops the thread and closes the reader on it.
 */
interface GuideWindowPager : AutoCloseable {
    val snapshots: StateFlow<GuideWindowData?>
    val generation: StateFlow<EpgGenerationInfo?>
    /** [holdDirection]: −1/+1 while a vertical hold is active (S16 target bias), else 0. */
    fun ensure(topRow: Int, windowStartMin: Int, holdDirection: Int = 0)
    /** Lineup or rows changed: drop coverage, re-query the current need. */
    fun invalidate()
    /** Detail for the panel (§3.9.3); null when the programme is gone. Runs on the epg thread. */
    suspend fun info(channel: GuideChannel, rawStartMin: Int): ProgrammeInfo?
    /** The programme airing at display minute [atMin] (mini player bottom panel). */
    suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo?
    /**
     * (amended 2026-10-14, §2.13 item 6) `onTrimMemory` (§4.7): clear the width and detail caches; with
     * [dropSnapshot] (`TRIM_MEMORY_UI_HIDDEN`) also the current snapshot, which the next [ensure] rebuilds.
     */
    fun trimMemory(dropSnapshot: Boolean) = Unit
}

fun interface GuideWindowPagerFactory {
    /**
     * (amended 2026-10-14, §2.13 item 3) [measurer] is a factory called on the pager's own thread with the metrics
     * scale: the pager keeps the one instance it gets (Paint is not thread-safe) and calls the factory again only when
     * the scale changes and the instance is not a `RescalableTextMeasurer`.
     */
    fun create(rows: GuideRows, metrics: () -> GuideMetrics?, measurer: (scale: Float) -> GuideTextMeasurer, builder: RowCellsBuilder, labels: TimeLabels): GuideWindowPager
}

enum class PerformanceMode { AUTO, HIGH, LOW }
enum class ExitConfirmation { BACK_TWICE, ASK, NONE }
enum class OpenGuideOn { ALL, FAVOURITES, LAST }

/** Guide settings (product §4.3, §4.6, §4.7). In-memory in M3 (like DataSettings); M5 persists them. */
data class GuideSettings(
    val clock24: Boolean = true,
    val showLogos: Boolean = true,
    val showNumbers: Boolean = true,
    val performance: PerformanceMode = PerformanceMode.AUTO,
    val miniStill: Boolean = false,
    val ambient: Boolean = true,
    val exitConfirmation: ExitConfirmation = ExitConfirmation.BACK_TWICE,
    val openGuideOn: OpenGuideOn = OpenGuideOn.LAST,
    val reminderAutoSwitch: Boolean = false,
    val reminderLeadSec: Int = 60,
    /** Debug builds only (`thebox-debug://guide?unlocked=1`): show locked/adult groups until M5's PIN exists (lead §7 item 12). */
    val debugUnlocked: Boolean = false,
)

interface GuideSettingsSource {
    val settings: StateFlow<GuideSettings>
    fun update(transform: (GuideSettings) -> GuideSettings)
}

/** Last source and first-run flags (SharedPreferences `guide_session`). */
interface GuideSessionStore {
    fun lastSource(): GuideSource?
    fun setLastSource(source: GuideSource)
    fun flag(name: String): Boolean
    fun setFlag(name: String)

    companion object {
        /**
         * (amended 2026-10-14, §2.13 item 20) The one flag for "the first Remind me explains that reminders need the
         * app open" (§3.11.3), shared by the guide's effects and the Search screen (they used different names).
         */
        const val FLAG_REMINDER_EXPLAINED = "reminder_first_set"
    }
}

/**
 * Device facts the guide adapts to (computed once in :ui from ActivityManager; reviewed 2026-10-14, S3/F9).
 * `isLowRamDevice` and `memoryClass` decide first; MemTotal is only a floor. To measure on hardware (M6):
 * MemTotal and memoryClass of a Fire TV Stick 4K (nominal 1.5 GB) and an Onn 4K (nominal 2 GB).
 */
data class DeviceClass(val lowRam: Boolean, val totalMemBytes: Long, val memoryClassMb: Int) {
    /** MemTotal runs ~5–10 % under nominal RAM: 1.75 GiB catches nominal 1.5 GB devices, not 2 GB ones. */
    val lowEnd: Boolean get() = lowRam || totalMemBytes < 1792L * 1024 * 1024 || memoryClassMb <= 192
    /** Nominal 1 GB class only (product §3.8: Fire TV Stick Lite). */
    val stillByDefault: Boolean get() = totalMemBytes < 1280L * 1024 * 1024
}
