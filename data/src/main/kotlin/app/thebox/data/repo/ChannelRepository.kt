package app.thebox.data.repo

import app.thebox.core.catchup.CatchupResolver
import app.thebox.core.catchup.PlaylistCatchupDefaults
import app.thebox.core.model.Catchup
import app.thebox.core.model.Channel
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.KeyRemap
import app.thebox.core.source.EntryKind
import app.thebox.core.source.RawCatchup
import app.thebox.core.text.NameNormalizer
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.ChannelRow
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.db.dao.GroupRow
import app.thebox.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channel read models for the UI (M3–M5). Every query applies the parental predicate in SQL
 * ([ChannelDao.PARENTAL]); `unlocked` is true only while the PIN session is open. Numbering follows
 * `playlist.numbering_mode` at read time, and `channel_prefs.custom_number` overrides it (§3.3.3).
 */
@Singleton
class ChannelRepository @Inject constructor(
    private val db: LibraryDatabase,
    private val channelDao: ChannelDao,
    private val groupDao: GroupDao,
    private val playlists: PlaylistRepository,
    private val catchupResolver: CatchupResolver,
) {
    fun groups(playlistId: Long, unlocked: Boolean, kind: EntryKind = EntryKind.LIVE): Flow<List<GroupRow>> =
        groupDao.groups(playlistId, RoomMergeSink.groupKind(kind), unlocked)

    fun browseGroup(playlistId: Long, groupId: Long, unlocked: Boolean): Flow<List<Channel>> =
        channelDao.browseGroup(playlistId, groupId, unlocked).map { rows -> toChannels(playlistId, rows) }

    fun browseAll(playlistId: Long, unlocked: Boolean): Flow<List<Channel>> =
        channelDao.browseAll(playlistId, unlocked).map { rows -> toChannels(playlistId, rows) }

    /** Number entry (M3 "type 101"): custom number first, then the playlist's numbering mode. */
    suspend fun byNumber(playlistId: Long, number: Int, unlocked: Boolean): Channel? {
        val p = playlists.get(playlistId) ?: return null
        val row = rowByNumber(p, number, unlocked) ?: return null
        return toChannel(p, defaults(p), row)
    }

    /** The row behind [byNumber]; M3's `GuideLibrary.byNumber` loads its guide row from the id. */
    suspend fun rowByNumber(p: PlaylistEntity, number: Int, unlocked: Boolean): ChannelRow? {
        val playlistId = p.id
        val mode = p.numberingMode
        return when {
            mode.startsWith("START_AT:") -> {
                val start = mode.substringAfter(':').toIntOrNull() ?: 1
                channelDao.byNumber(playlistId, number, unlocked)?.takeIf { it.customNumber == number }
                    ?: channelDao.bySortIndex(playlistId, number - start, unlocked)
            }
            mode == "PLAYLIST_ORDER" -> channelDao.byNumber(playlistId, number, unlocked)?.takeIf { it.customNumber == number }
                ?: channelDao.bySortIndex(playlistId, number - 1, unlocked)
            else -> channelDao.byNumber(playlistId, number, unlocked) ?: channelDao.bySortIndex(playlistId, number - 1, unlocked)
                ?.takeIf { it.channel.number == null }
        }
    }

    suspend fun get(identity: ChannelIdentity): Channel? = getWithRow(identity)?.channel

    /**
     * [get] together with the row it was built from, for callers that also need a column the [Channel] model does not
     * carry (the tune path's `container_ext`): one row read instead of two (review 2026-11-09, performance M7b).
     */
    suspend fun getWithRow(identity: ChannelIdentity): ChannelWithRow? {
        val p = playlists.get(identity.playlistId) ?: return null
        val row = channelDao.row(identity.playlistId, identity.stableKey.value) ?: return null
        return ChannelWithRow(toChannel(p, defaults(p), row), row)
    }

    /** A [Channel] and the [ChannelRow] it was read from ([getWithRow]). */
    class ChannelWithRow(val channel: Channel, val row: ChannelRow)

    /** Title search over `channel_fts` (prefix match per word, diacritics folded). */
    suspend fun search(text: String, unlocked: Boolean, limit: Int = 50): List<Channel> {
        val match = ftsQuery(text) ?: return emptyList()
        val rows = channelDao.search(match, unlocked, limit)
        val cache = HashMap<Long, Pair<PlaylistEntity, PlaylistCatchupDefaults>?>()
        return rows.mapNotNull { row ->
            val (p, d) = cache.getOrPut(row.channel.playlistId) { playlists.get(row.channel.playlistId)?.let { it to defaults(it) } } ?: return@mapNotNull null
            toChannel(p, d, row)
        }
    }

    /** §3.3.6: moves favourites, prefs, mappings, reminders and stream sources to new keys in one transaction. */
    suspend fun remapUserData(playlistId: Long, remaps: List<KeyRemap>): Int =
        db.writeTransaction { UserDataRemapper.apply(this, playlistId, remaps) }

    private suspend fun toChannels(playlistId: Long, rows: List<ChannelRow>): List<Channel> {
        val p = playlists.get(playlistId) ?: return emptyList()
        val d = defaults(p)
        return rows.map { toChannel(p, d, it) }
    }

    private suspend fun defaults(p: PlaylistEntity): PlaylistCatchupDefaults = playlists.catchupDefaults(p)

    private fun toChannel(p: PlaylistEntity, defaults: PlaylistCatchupDefaults, row: ChannelRow): Channel {
        val c = row.channel
        val raw = RawCatchup(
            type = c.catchupType, source = c.catchupSource, days = c.catchupDays, correctionHours = c.catchupCorrectionH,
            timeshiftDays = c.timeshiftDays, tvgRecDays = c.tvgRecDays, catchupTimeSec = c.catchupTimeSec,
            xtreamTvArchive = c.tvArchive, xtreamArchiveDays = c.tvArchiveDays,
        )
        val catchup = try {
            catchupResolver.resolve(raw, c.url, c.xtreamStreamId, defaults)
        } catch (e: NotImplementedError) {
            Catchup.NONE // WP-C stub on a branch without WP-C
        }
        return Channel(
            playlistId = c.playlistId,
            stableKey = StableKey(c.stableKey),
            name = row.displayName,
            streamUrl = c.url,
            number = row.customNumber ?: number(p.numberingMode, c.number, c.sortIndex),
            group = row.groupName,
            logoUrl = row.customLogo ?: c.logoUrl,
            tvgId = c.tvgId,
            tvgName = c.tvgName,
            headers = c.headers ?: emptyMap(),
            catchup = catchup,
            epgShiftHours = (row.prefShiftMin ?: c.epgShiftMin ?: 0) / 60.0,
            kind = EntryKind.entries.getOrElse(c.kind) { EntryKind.LIVE },
            xtreamStreamId = c.xtreamStreamId,
            adult = c.isAdult,
        )
    }

    companion object {
        /** §3.3.3: PROVIDER → number else position+1; PLAYLIST_ORDER → position+1; START_AT:n → n + position. */
        fun number(mode: String, providerNumber: Int?, sortIndex: Int): Int = when {
            mode.startsWith("START_AT:") -> (mode.substringAfter(':').toIntOrNull() ?: 1) + sortIndex
            mode == "PLAYLIST_ORDER" -> sortIndex + 1
            else -> providerNumber ?: (sortIndex + 1)
        }

        /**
         * User text → FTS4 query: each folded word as a prefix term (`word*`). Words are letters/digits only and
         * lower-case, so no FTS operator (AND/OR/NOT/NEAR are upper-case) or quote can be injected. Null when
         * nothing is searchable.
         */
        fun ftsQuery(text: String): String? {
            val words = NameNormalizer.fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.take(8).joinToString(" ") { "$it*" }
        }
    }
}
