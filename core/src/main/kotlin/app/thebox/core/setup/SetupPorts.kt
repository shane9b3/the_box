package app.thebox.core.setup

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchCandidate
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.Flow

/*
 * WP-B's data ports (docs/design/m5-setup-settings.md §3.3.1, §3.4.6, §3.6). The page builders in `pages/` live in
 * :core and are table-tested on the JVM; `:data`'s `PlaylistSettingsRepository` implements these over Room, the
 * credential store, the refresh scheduler and `epg.db` (bound in `SetupModule`). All suspend calls are main-safe.
 * No value here ever carries a credential except [EditableSource], which lives in memory only and is never logged.
 */

/** One row of the Playlists section (§3.3.1). */
data class PlaylistSummary(
    val id: Long,
    val uid: String?,
    val name: String,
    val type: PlaylistType,
    /** Redacted host or file name. */
    val displaySource: String,
    val liveCount: Int,
    val enabled: Boolean,
    val needsReauth: Boolean,
    val lastRefreshAtMs: Long?,
    val lastSuccessAtMs: Long?,
    /** `playlist.last_status`: a `RefreshOutcome` name or a `SourceError.code`. */
    val lastStatus: String?,
    /** Xtream account expiry (epoch s), or null. */
    val expiresAtSec: Long?,
    val refreshing: Boolean = false,
    /** The worker's progress line while [refreshing]. */
    val progressLine: String? = null,
) {
    val failed: Boolean get() = lastStatus != null && lastStatus !in OK_STATUSES

    companion object {
        /** `RefreshOutcome` names that are not failures; anything else in `last_status` is an error code. */
        val OK_STATUSES = setOf("SUCCESS", "NOT_MODIFIED", "CANCELLED")
    }
}

/** Everything the playlist page shows (§3.3.1). */
data class PlaylistDetail(
    val summary: PlaylistSummary,
    val account: XtreamAccountInfo?,
    val serverZone: String?,
    val clockSkewSec: Long,
    val updateOnStart: Boolean,
    /** `PROVIDER` | `PLAYLIST_ORDER` | `START_AT:<n>`. */
    val numberingMode: String,
    /** `AUTO` | `OFF` | `CUSTOM`. */
    val namePrefixMode: String,
    val namePrefixCustom: String?,
    val refreshIntervalH: Int,
    val orderMode: String,
    val usePlaylistEpg: Boolean,
    val userAgent: String?,
    /** Non-secret extra headers. */
    val headers: Map<String, String>,
    /** Number of secret headers (Authorization, Cookie) kept in the credential blob; their values never leave :data. */
    val secretHeaderCount: Int,
    val xtreamOutput: String,
    val xtreamOutputLearned: String?,
    /** Hosts of the backup servers (index 1… of the secret's URLs); never a credential. */
    val backupHosts: List<String>,
    val activeBaseIndex: Int,
    val catchupModeSetting: String,
    val catchupDaysOverride: Int?,
    val tokenZoneSetting: String,
    val paddingBeforeSec: Int,
    val paddingAfterSec: Int,
    val maxConnectionsOverride: Int?,
    val ignoreTlsErrors: Boolean,
    val lastReport: RefreshReport?,
)

/** A playlist's source with its secrets, for the edit-in-place page (memory only, never logged). */
sealed interface EditableSource {
    data class Xtream(val form: XtreamForm) : EditableSource
    data class M3u(val form: M3uForm) : EditableSource
}

data class RemovalCounts(val channels: Int, val favourites: Int, val reminders: Int)

/** `DisplayNames.previewCount`: names the rule changes / names in total. */
data class PrefixPreview(val changed: Int, val total: Int)

interface PlaylistAdmin {
    fun observeAll(): Flow<List<PlaylistSummary>>
    fun observe(playlistId: Long): Flow<PlaylistDetail?>
    suspend fun summary(playlistId: Long): PlaylistSummary?
    suspend fun editableSource(playlistId: Long): EditableSource?
    suspend fun removalCounts(playlistId: Long): RemovalCounts
    suspend fun remove(playlistId: Long)
    /** Starts a refresh now (MANUAL); [forceAcceptShrink] after the user accepted a shrink (§3.4.4). */
    fun refreshNow(playlistId: Long, forceAcceptShrink: Boolean = false)
    fun refreshAll()
    /** "Add as a single channel" for an HLS manifest pasted as an M3U URL (M1 M24): a one-entry playlist file. */
    suspend fun addSingleStream(name: String?, url: String, userAgent: String?): app.thebox.core.common.Outcome<Long>
    suspend fun rename(playlistId: Long, name: String)
    suspend fun setUpdateOnStart(playlistId: Long, on: Boolean)
    suspend fun setNumbering(playlistId: Long, mode: String)
    suspend fun setNamePrefix(playlistId: Long, mode: String, customJson: String?)
    suspend fun previewNamePrefix(playlistId: Long, mode: String, customJson: String?): PrefixPreview
    suspend fun setEnabled(playlistId: Long, on: Boolean)
    suspend fun setRefreshInterval(playlistId: Long, hours: Int)
    suspend fun setOrder(playlistId: Long, mode: String)
    suspend fun setUsePlaylistEpg(playlistId: Long, on: Boolean)
    suspend fun setUserAgent(playlistId: Long, userAgent: String?)
    suspend fun setHeader(playlistId: Long, name: String, value: String?)
    suspend fun setStreamFormat(playlistId: Long, value: String)
    suspend fun setBackupServers(playlistId: Long, urls: List<String>): app.thebox.core.common.Outcome<Unit>
    suspend fun setCatchupMode(playlistId: Long, value: String)
    suspend fun setCatchupDays(playlistId: Long, days: Int?)
    suspend fun setCatchupZone(playlistId: Long, value: String)
    suspend fun setPadding(playlistId: Long, beforeSec: Int?, afterSec: Int?)
    suspend fun setMaxConnections(playlistId: Long, max: Int?)
    suspend fun setIgnoreTls(playlistId: Long, on: Boolean)
}

/** A group in the onboarding review (§3.4.6). */
data class ReviewGroup(
    val key: String,
    val name: String,
    val channels: Int,
    val adult: Boolean,
    val hidden: Boolean,
    /** `group_prefs.locked`: null = automatic (adult groups lock). */
    val lockedPref: Boolean?,
) {
    val locked: Boolean get() = lockedPref ?: adult
}

interface GroupsReviewSource {
    /** Live groups of [playlistId] in playlist order, with their channel counts. */
    suspend fun groups(playlistId: Long): List<ReviewGroup>
}

/** A channel of the Fix unmatched list (§3.6.3). */
data class UnmatchedChannel(
    val identity: ChannelIdentity,
    val name: String,
    val groupName: String?,
    val watchCount: Int,
    /** Best SUGGESTED candidate's score (0–1), or null. */
    val bestScore: Float?,
)

data class MatchTotals(val live: Int, val matched: Int, val matchedWithData: Int)

/** Programme airing now on an EPG channel ("Now: {title} · 19:30–20:00"). */
data class NowAiring(val title: String, val startMs: Long, val endMs: Long)

interface GuideMatching {
    fun unmatched(access: ParentalAccess): Flow<List<UnmatchedChannel>>
    fun matchedWithoutData(access: ParentalAccess): Flow<List<UnmatchedChannel>>
    suspend fun totals(playlistId: Long): MatchTotals
    suspend fun channelName(channel: ChannelIdentity): String?
    suspend fun suggestions(channel: ChannelIdentity, limit: Int): List<MatchCandidate>
    /** EPG channels of the enabled sources whose name or id matches [query] (`EpgChannelSearchDao`). */
    suspend fun search(query: String, limit: Int): List<EpgChannelInfo>
    suspend fun nowAiring(refs: List<EpgChannelRef>, nowMs: Long): Map<EpgChannelRef, NowAiring>
    suspend fun override(channel: ChannelIdentity): EpgChannelRef?
    suspend fun hasOverride(channel: ChannelIdentity): Boolean
    /** `setOverride`, then the incremental match and an EPG patch. */
    suspend fun choose(channel: ChannelIdentity, ref: EpgChannelRef)
    suspend fun clearChoice(channel: ChannelIdentity)
    /** Per-channel guide time shift in minutes (`channel_prefs.epg_shift_min`), 0 = none. */
    suspend fun channelShift(channel: ChannelIdentity): Int
    suspend fun setChannelShift(channel: ChannelIdentity, minutes: Int)
}
