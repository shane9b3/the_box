// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.1. Verbatim; changes go through the lead.
package app.thebox.core.settings

import app.thebox.core.input.BoxAction
import app.thebox.core.refresh.ChannelIdentity

enum class SectionId { PLAYLISTS, GUIDE, PLAYBACK, CHANNELS, INTERFACE, REMOTE, PARENTAL, BACKUP, ABOUT, NETWORK }

/** Where Settings opens, and every page's address. Data classes so a route is a stable key for cursor memory. */
sealed interface SettingsRoute {
    data object Root : SettingsRoute
    /** First run (no playlist yet): Welcome, Back = exit the app (product S1). */
    data object FirstRun : SettingsRoute
    data class Section(val id: SectionId) : SettingsRoute
    /** Add a source; [firstRun] decides where Back and "Start watching" go. */
    data class AddSource(val kind: SourceKind? = null, val firstRun: Boolean = false) : SettingsRoute
    data class PhoneSetup(val firstRun: Boolean = false, val purpose: PhonePurpose = PhonePurpose.SETUP) : SettingsRoute
    data class ImportProgress(val playlistId: Long, val firstRun: Boolean) : SettingsRoute
    data class GroupsReview(val playlistId: Long, val firstRun: Boolean) : SettingsRoute
    /**
     * (M5 integration 2026-10-16, WP-B amendment) The guide-matching report after an import (§3.4.7): its own route, so
     * BACK from it is "Later" and a test can address it. [firstRun] decides where "Start watching" goes.
     */
    data class MatchingReport(val playlistId: Long, val firstRun: Boolean) : SettingsRoute
    data class Playlist(val playlistId: Long, val focus: RowId? = null) : SettingsRoute
    data class PlaylistSource(val playlistId: Long) : SettingsRoute
    data class Groups(val playlistId: Long) : SettingsRoute
    data class HiddenChannels(val playlistId: Long) : SettingsRoute
    data class HideRules(val playlistId: Long) : SettingsRoute
    data object FavouriteLists : SettingsRoute
    data class FavouriteList(val listId: Long) : SettingsRoute
    data object EpgSources : SettingsRoute
    /** [channel] null = the unmatched list; non-null = the picker for that channel. */
    data class FixGuide(val channel: ChannelIdentity? = null) : SettingsRoute
    data class Channel(val identity: ChannelIdentity) : SettingsRoute
    data object ButtonMapping : SettingsRoute
    data class ActionBindings(val action: BoxAction) : SettingsRoute
    data object Diagnostics : SettingsRoute
    data class NetworkTest(val playlistId: Long) : SettingsRoute
    data object SyncStatus : SettingsRoute
    data object Licences : SettingsRoute
    data object Backup : SettingsRoute
    data class RestorePreview(val token: Long) : SettingsRoute
}

enum class SourceKind { XTREAM, M3U }
enum class PhonePurpose { SETUP, RESTORE, BACKUP_DOWNLOAD, LOGS_DOWNLOAD }

/** Stable row id, e.g. "playback.audio_output". Persisted nowhere; used for routes, tests and focus restore. */
@JvmInline value class RowId(val key: String)

enum class Visibility { SIMPLE, ADVANCED }

/** When a change takes effect (decision §0.6). The row's description states every value other than LIVE. */
enum class EffectTiming { LIVE, NEXT_TUNE, REPREPARE, NEXT_REFRESH, NEXT_EPG_IMPORT, NEXT_LAUNCH, RESTART, NONE }

data class ChoiceOption(val id: String, val label: String, val detail: String? = null, val enabled: Boolean = true)

sealed interface RowKind {
    data class Toggle(val on: Boolean) : RowKind
    /** Inline radio list (product S11: "not a modal"). */
    data class Choice(val options: List<ChoiceOption>, val selected: Int) : RowKind
    /** Several of [options] (favourite lists of a channel, enabled EPG sources). */
    data class MultiChoice(val options: List<ChoiceOption>, val selected: Set<Int>) : RowKind
    data class Text(val value: String, val hint: TextHint, val masked: Boolean = false, val chips: List<String> = emptyList()) : RowKind
    data class Number(val value: Int, val min: Int, val max: Int, val unit: String? = null) : RowKind
    data class Action(val destructive: Boolean = false, val confirm: ConfirmSpec? = null, val needsPin: Boolean = false) : RowKind
    data class Link(val route: SettingsRoute, val needsPin: Boolean = false) : RowKind
    /** Read-only lines (account card, device facts). Focusable so the page can scroll to it. */
    data class Info(val lines: List<String>, val tone: Tone = Tone.NORMAL) : RowKind
    /** An entry in a management list (group, hidden channel, binding, rule). OK opens [route] or runs [primary]. */
    data class Item(val route: SettingsRoute? = null, val primary: String? = null, val menu: List<ChoiceOption> = emptyList(), val movable: Boolean = false, val badges: Set<Badge> = emptySet()) : RowKind
}

enum class TextHint { URL, USERNAME, PASSWORD, NAME, PATTERN, PASSPHRASE, HEADER, NUMBER }
enum class Tone { NORMAL, GOOD, WARN, BAD }
enum class Badge { LOCKED, HIDDEN, ADULT, FAILED, NEW, DEFAULT }

data class ConfirmSpec(val title: String, val body: String, val confirmLabel: String, val cancelLabel: String)

data class SettingsRow(
    val id: RowId,
    val label: String,
    /** Current value on the right at .65 alpha (product S11). */
    val value: String? = null,
    val description: String? = null,
    val kind: RowKind,
    val enabled: Boolean = true,
    /** Shown in place of [description] when [enabled] is false; disabled rows stay focusable. */
    val disabledReason: String? = null,
    val visibility: Visibility = Visibility.SIMPLE,
    val effect: EffectTiming = EffectTiming.LIVE,
)

/** One page. Big lists (groups, hidden channels, unmatched, EPG channels) are paged: [rows] holds the fixed rows, [list] the rest. */
data class PageModel(
    val route: SettingsRoute,
    val title: String,
    val subtitle: String? = null,
    val rows: List<SettingsRow>,
    val list: PagedList? = null,
    /** Bumped whenever the content changes; cursor restore uses RowId first, then the index. */
    val version: Long,
    /** Shown instead of rows while loading or when empty ("No hidden channels"). */
    val placeholder: String? = null,
    /**
     * (M5 integration 2026-10-16, WP-B amendment) A row the cursor moves to when this value changes from the previous
     * version of the page (a result card's primary button as the card appears, I-M5-4); null = the cursor stays.
     */
    val focus: RowId? = null,
)

data class PagedList(val count: Int, val pageSize: Int = PAGE_SIZE, val loaded: Map<Int, List<SettingsRow>>) {
    companion object { const val PAGE_SIZE = 32 }
}

/** What the user did on a row; [SettingsEffects.onIntent] executes it (writes go to the store on IO). */
sealed interface RowIntent {
    val row: RowId
    data class Toggle(override val row: RowId, val on: Boolean) : RowIntent
    data class Choose(override val row: RowId, val optionId: String) : RowIntent
    data class ChooseMany(override val row: RowId, val optionIds: Set<String>) : RowIntent
    data class Edit(override val row: RowId, val text: String) : RowIntent
    data class SetNumber(override val row: RowId, val value: Int) : RowIntent
    data class Run(override val row: RowId) : RowIntent
    data class Menu(override val row: RowId, val optionId: String) : RowIntent
    data class Move(override val row: RowId, val toIndex: Int) : RowIntent
}
