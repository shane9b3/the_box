package app.thebox.data.guide

import androidx.room.PooledConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.catchup.CatchupResolver
import app.thebox.core.catchup.PlaylistCatchupDefaults
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSource
import app.thebox.core.model.Catchup
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.RawCatchup
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.read
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw-statement queries for the guide (docs/design/m3-guide.md §3.2.1): lineups fill a `LongArray` without boxing or
 * entity mapping, blocks select only the columns a [GuideChannel] needs. Every lineup variant applies
 * `c.missing_since IS NULL`, `c.kind IN (0, 1)`, `p.enabled = 1` and the parental predicate ([ChannelDao.PARENTAL]:
 * hidden channels/groups never, locked groups and adult channels only when `unlocked`) in SQL.
 *
 * Main-safe callers switch to IO first ([GuideLibraryImpl], [GuideSearchImpl]); these run on Room's reader pool.
 */
@Singleton
class GuideQueries @Inject constructor(
    private val db: LibraryDatabase,
    private val playlists: PlaylistRepository,
    private val catchupResolver: CatchupResolver,
    private val refs: EpgRefResolver,
) {
    /** A lineup's row ids (process-local `channel.id`s) and its archive upper bound (§3.3.4). */
    class LineupRows(val ids: LongArray, val archiveDaysUpperBound: Int)

    suspend fun lineup(source: GuideSource, unlocked: Boolean): LineupRows = db.read {
        val q = lineupClause(source)
        val ids = longs("SELECT c.id ${q.from} WHERE ${q.where} $PARENTAL_RAW ${q.order}") { st -> st.bindBool(1, unlocked); q.bind(st) }
        val archive = int("SELECT $ARCHIVE_DAYS ${q.from} WHERE ${q.where} $PARENTAL_RAW") { st -> st.bindBool(1, unlocked); q.bind(st) }
        LineupRows(ids, archive)
    }

    /** Upper bound of archive days over every lineup-visible channel (search look-back, §3.10.2). */
    suspend fun archiveDaysAll(unlocked: Boolean): Int = db.read {
        int("SELECT $ARCHIVE_DAYS $LINEUP_FROM WHERE $LINEUP_BASE $PARENTAL_RAW") { st -> st.bindBool(1, unlocked) }
    }

    /** Lineup-visible ids among [ids] (search candidates and hits get the same predicate as the grid). */
    suspend fun visible(ids: Collection<Long>, unlocked: Boolean): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        db.read {
            for (chunk in ids.chunked(EpgRefResolver.MAX_VARS)) {
                // Numbered parameters throughout: ?1 is the unlock flag inside PARENTAL_RAW.
                val marks = chunk.indices.joinToString(",") { "?${it + 2}" }
                usePrepared("SELECT c.id $LINEUP_FROM WHERE $LINEUP_BASE AND c.id IN ($marks) $PARENTAL_RAW") { st ->
                    st.bindBool(1, unlocked)
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 2, id) }
                    while (st.step()) out += st.getLong(0)
                }
            }
        }
        return out
    }

    /**
     * Rows for `rowIds[from, to)` in order (§3.2.1 block load): one statement for the channel columns, the playlist's
     * numbering mode (custom number first) and catch-up defaults (cached for the call), [CatchupResolver], then
     * [EpgRefResolver] for the block. An id purged meanwhile yields null.
     */
    suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?> {
        if (from >= to) return emptyList()
        val t0 = System.nanoTime()
        val wanted = LongArray(to - from) { rowIds[from + it] }
        val raw = HashMap<Long, RawRow>(wanted.size * 2)
        db.read {
            for (chunk in wanted.asList().chunked(EpgRefResolver.MAX_VARS)) {
                val marks = chunk.joinToString(",") { "?" }
                usePrepared("$BLOCK_SELECT WHERE c.id IN ($marks)") { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    while (st.step()) RawRow(st).let { raw[it.id] = it }
                }
            }
        }
        val playlistCache = HashMap<Long, Pair<PlaylistEntity, PlaylistCatchupDefaults>?>()
        for (r in raw.values) {
            if (!playlistCache.containsKey(r.playlistId)) {
                playlistCache[r.playlistId] = playlists.get(r.playlistId)?.let { it to playlists.catchupDefaults(it) }
            }
        }
        val resolved = refs.resolve(raw.values.map { EpgRefResolver.Input(it.identity, it.channelShift, it.prefShift) })
        val out = ArrayList<GuideChannel?>(wanted.size)
        for (id in wanted) {
            val r = raw[id]
            val p = r?.let { playlistCache[it.playlistId] }
            if (r == null || p == null) {
                out += null
                continue
            }
            val (playlist, defaults) = p
            val catchup = try {
                catchupResolver.resolve(r.catchup, r.url, r.xtreamStreamId, defaults)
            } catch (e: RuntimeException) {
                Catchup.NONE
            } catch (e: NotImplementedError) {
                Catchup.NONE
            }
            val epg = resolved[r.identity]
            out += GuideChannel(
                rowId = r.id,
                identity = r.identity,
                displayName = r.displayName,
                number = r.customNumber ?: ChannelRepository.number(playlist.numberingMode, r.number, r.sortIndex),
                logoUrl = r.logo,
                archiveDays = if (catchup.isAvailable) catchup.days else 0,
                epg = epg?.ref,
                epgShiftMin = epg?.shiftMin ?: 0,
                epgHasData = epg?.hasData ?: false,
                identityHash = GuideChannel.hashOf(r.identity),
            )
        }
        val ms = (System.nanoTime() - t0) / 1e6
        if (ms > BLOCK_LOG_MS) BoxLog.i(TAG) { "row block of ${wanted.size} in ${"%.1f".format(ms)} ms" }
        return out
    }

    suspend fun rowIdOf(identity: ChannelIdentity): Long? = db.read {
        usePrepared("SELECT id FROM channel WHERE playlist_id = ? AND stable_key = ?") { st ->
            st.bindLong(1, identity.playlistId)
            st.bindText(2, identity.stableKey.value)
            if (st.step()) st.getLong(0) else null
        }
    }

    /** The last watched lineup-visible channel (startup tune never plays a locked channel, N13). */
    suspend fun lastWatched(unlocked: Boolean): Long? = db.read {
        usePrepared("SELECT c.id $RECENT_FROM WHERE $LINEUP_BASE AND cp.last_watched_at IS NOT NULL $PARENTAL_RAW ORDER BY cp.last_watched_at DESC LIMIT 1") { st ->
            st.bindBool(1, unlocked)
            if (st.step()) st.getLong(0) else null
        }
    }

    /** Groups panel counts (§3.8.1): favourites lists with present channels, and the Recent count. */
    data class ListCount(val listId: Long, val name: String, val count: Int)

    suspend fun favouriteListCounts(unlocked: Boolean): List<ListCount> = db.read {
        val lists = ArrayList<Pair<Long, String>>()
        usePrepared("SELECT id, name FROM favourite_list ORDER BY sort_index, id") { st -> while (st.step()) lists += st.getLong(0) to st.getText(1) }
        lists.map { (id, name) ->
            ListCount(id, name, int("SELECT COUNT(*) $FAV_FROM WHERE f.list_id = ?2 AND $LINEUP_BASE $PARENTAL_RAW") { st -> st.bindBool(1, unlocked); st.bindLong(2, id) })
        }
    }

    suspend fun recentCount(unlocked: Boolean): Int = db.read {
        int("SELECT COUNT(*) FROM (SELECT c.id $RECENT_FROM WHERE $LINEUP_BASE AND cp.last_watched_at IS NOT NULL $PARENTAL_RAW LIMIT $RECENT_LIMIT)") { st ->
            st.bindBool(1, unlocked)
        }
    }

    /** Channel search (§3.10.1): `channel_fts` MATCH with the lineup predicate, with what the ranking needs. */
    data class ChannelHit(val id: Long, val displayName: String, val favourite: Boolean, val watchCount: Int, val playlistSort: Int, val sortIndex: Int)

    suspend fun searchChannels(match: String, unlocked: Boolean, limit: Int): List<ChannelHit> = db.read {
        usePrepared(
            "SELECT c.id, COALESCE(cp.custom_name, c.display_name, c.name), " +
                "EXISTS (SELECT 1 FROM favourite f WHERE f.playlist_id = c.playlist_id AND f.stable_key = c.stable_key), " +
                "COALESCE(cp.watch_count, 0), p.sort_index, c.sort_index " +
                "FROM channel_fts JOIN channel c ON c.id = channel_fts.rowid " +
                LINEUP_JOINS + "WHERE channel_fts MATCH ?2 AND $LINEUP_BASE $PARENTAL_RAW ORDER BY p.sort_index, c.sort_index LIMIT ?3",
        ) { st ->
            st.bindBool(1, unlocked)
            st.bindText(2, match)
            st.bindLong(3, limit.toLong())
            buildList {
                while (st.step()) add(ChannelHit(st.getLong(0), st.getText(1), st.getLong(2) != 0L, st.getLong(3).toInt(), st.getLong(4).toInt(), st.getLong(5).toInt()))
            }
        }
    }

    /**
     * Programme search, step 2 (§3.10.2): library channels that map or AUTO-match each EPG ref (both reverse lookups
     * use the v3 `(source_id, xmltv_id)` indexes), batched [REF_BATCH] refs per statement, restricted to the lineup
     * predicate, in playlist then provider order.
     */
    suspend fun channelsForRefs(refs: Collection<EpgChannelRef>, unlocked: Boolean): Map<EpgChannelRef, List<Long>> {
        if (refs.isEmpty()) return emptyMap()
        val out = HashMap<EpgChannelRef, MutableList<Long>>()
        db.read {
            for (chunk in refs.chunked(REF_BATCH)) {
                // Numbered parameters: ?1 is the unlock flag; each ref binds ?(2 + 2k) and ?(3 + 2k), used by both arms.
                val pairs = chunk.indices.joinToString(" OR ") { k -> "(x.source_id = ?${2 + 2 * k} AND x.xmltv_id = ?${3 + 2 * k})" }
                val sql = "SELECT x.source_id, x.xmltv_id, c.id FROM (" +
                    "SELECT source_id, xmltv_id, playlist_id, stable_key FROM epg_mapping x WHERE $pairs " +
                    "UNION SELECT source_id, xmltv_id, playlist_id, stable_key FROM epg_match x WHERE x.status = 0 AND ($pairs)" +
                    ") x JOIN channel c ON c.playlist_id = x.playlist_id AND c.stable_key = x.stable_key " +
                    LINEUP_JOINS + "WHERE $LINEUP_BASE $PARENTAL_RAW ORDER BY p.sort_index, c.sort_index"
                usePrepared(sql) { st ->
                    st.bindBool(1, unlocked)
                    chunk.forEachIndexed { k, r -> st.bindLong(2 + 2 * k, r.sourceId); st.bindText(3 + 2 * k, r.xmltvId) }
                    while (st.step()) out.getOrPut(EpgChannelRef(st.getLong(0), st.getText(1))) { ArrayList(2) } += st.getLong(2)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** The statement [lineup] runs for [source] (hand-off `EXPLAIN QUERY PLAN`; binds ?1 unlock, ?2/?3 as in [lineupClause]). */
    internal fun lineupSql(source: GuideSource): String = lineupClause(source).let { "SELECT c.id ${it.from} WHERE ${it.where} $PARENTAL_RAW ${it.order}" }

    internal fun blockSql(ids: Int): String = "$BLOCK_SELECT WHERE c.id IN (${List(ids) { "?" }.joinToString(",")})"

    private class Clause(val from: String, val where: String, val order: String, val bind: (SQLiteStatement) -> Unit)

    /** Same order as the M1 reads they replace (§3.2.1): browseAll, browseGroup, FavouriteDao.channels, recents. */
    private fun lineupClause(source: GuideSource): Clause = when (source) {
        is GuideSource.All -> Clause(LINEUP_FROM, "$LINEUP_BASE AND c.playlist_id = ?2", "ORDER BY c.sort_index") { st ->
            st.bindLong(2, source.playlistId)
        }
        is GuideSource.Group -> Clause(
            LINEUP_FROM,
            "$LINEUP_BASE AND c.playlist_id = ?2 AND g.playlist_id = ?2 AND g.kind = 0 AND g.group_key = ?3",
            "ORDER BY cp.custom_sort_index IS NULL, cp.custom_sort_index, c.sort_index",
        ) { st -> st.bindLong(2, source.playlistId); st.bindText(3, source.groupKey) }
        is GuideSource.Favourites -> Clause(FAV_FROM, "$LINEUP_BASE AND f.list_id = ?2", "ORDER BY f.sort_index") { st ->
            st.bindLong(2, source.listId)
        }
        GuideSource.Recent -> Clause(
            RECENT_FROM,
            "$LINEUP_BASE AND cp.last_watched_at IS NOT NULL",
            "ORDER BY cp.last_watched_at DESC LIMIT $RECENT_LIMIT",
        ) { }
    }

    private suspend fun PooledConnection.longs(sql: String, bind: (SQLiteStatement) -> Unit): LongArray = usePrepared(sql) { st ->
        bind(st)
        var out = LongArray(256)
        var n = 0
        while (st.step()) {
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = st.getLong(0)
        }
        out.copyOf(n)
    }

    private suspend fun PooledConnection.int(sql: String, bind: (SQLiteStatement) -> Unit): Int = usePrepared(sql) { st ->
        bind(st)
        if (st.step() && !st.isNull(0)) st.getLong(0).toInt() else 0
    }

    private fun SQLiteStatement.bindBool(i: Int, v: Boolean) = bindLong(i, if (v) 1L else 0L)

    private class RawRow(st: SQLiteStatement) {
        val id = st.getLong(0)
        val playlistId = st.getLong(1)
        val identity = ChannelIdentity(playlistId, StableKey(st.getText(2)))
        val displayName: String = st.getText(3)
        val customNumber: Int? = if (st.isNull(4)) null else st.getLong(4).toInt()
        val number: Int? = if (st.isNull(5)) null else st.getLong(5).toInt()
        val sortIndex = st.getLong(6).toInt()
        val logo: String? = if (st.isNull(7)) null else st.getText(7)
        val catchup = RawCatchup(
            type = if (st.isNull(8)) null else st.getText(8),
            source = if (st.isNull(9)) null else st.getText(9),
            days = if (st.isNull(10)) null else st.getLong(10).toInt(),
            correctionHours = if (st.isNull(11)) null else st.getDouble(11),
            timeshiftDays = if (st.isNull(12)) null else st.getLong(12).toInt(),
            tvgRecDays = if (st.isNull(13)) null else st.getLong(13).toInt(),
            catchupTimeSec = if (st.isNull(14)) null else st.getLong(14),
            xtreamTvArchive = if (st.isNull(15)) null else st.getLong(15) != 0L,
            xtreamArchiveDays = if (st.isNull(16)) null else st.getLong(16).toInt(),
        )
        val url: String = st.getText(17)
        val xtreamStreamId: Long? = if (st.isNull(18)) null else st.getLong(18)
        val channelShift: Int? = if (st.isNull(19)) null else st.getLong(19).toInt()
        val prefShift: Int? = if (st.isNull(20)) null else st.getLong(20).toInt()
    }

    companion object {
        private const val TAG = "GuideQueries"
        const val RECENT_LIMIT = 50
        const val REF_BATCH = 50
        private const val BLOCK_LOG_MS = 15.0

        /** `ChannelDao.PARENTAL` with the unlock flag as `?1` (every guide statement binds it first). */
        val PARENTAL_RAW: String = ChannelDao.PARENTAL.replace(":unlocked", "?1")

        const val LINEUP_JOINS = """
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        LEFT JOIN channel_group g  ON g.id = c.group_id
        LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key
        LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
        """

        const val LINEUP_FROM = "FROM channel c $LINEUP_JOINS"

        /** Recent is driven from `channel_prefs` (its `last_watched_at` index), not by scanning `channel`. */
        private const val RECENT_FROM = """FROM channel_prefs cp
        JOIN channel c             ON c.playlist_id = cp.playlist_id AND c.stable_key = cp.stable_key
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        LEFT JOIN channel_group g  ON g.id = c.group_id
        LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
        """

        private const val FAV_FROM = "FROM favourite f JOIN channel c ON c.playlist_id = f.playlist_id AND c.stable_key = f.stable_key $LINEUP_JOINS"

        const val LINEUP_BASE = "c.missing_since IS NULL AND c.kind IN (0, 1)"

        /** §3.2.1 archive upper bound: the largest raw archive attribute, 3 days for a bare catch-up marker, the override. */
        const val ARCHIVE_DAYS =
            "COALESCE(MAX(max(COALESCE(c.catchup_days, 0), COALESCE(c.timeshift_days, 0), COALESCE(c.tv_archive_days, 0), " +
                "COALESCE(c.tvg_rec_days, 0), CASE WHEN c.catchup_type IS NOT NULL OR c.catchup_source IS NOT NULL OR c.tv_archive = 1 " +
                "THEN 3 ELSE 0 END, COALESCE(p.catchup_days_override, 0))), 0)"

        private const val BLOCK_SELECT =
            "SELECT c.id, c.playlist_id, c.stable_key, COALESCE(cp.custom_name, c.display_name, c.name), cp.custom_number, c.number, " +
                "c.sort_index, COALESCE(cp.custom_logo, c.logo_url), c.catchup_type, c.catchup_source, c.catchup_days, " +
                "c.catchup_correction_h, c.timeshift_days, c.tvg_rec_days, c.catchup_time_sec, c.tv_archive, c.tv_archive_days, " +
                "c.url, c.xtream_stream_id, c.epg_shift_min, cp.epg_shift_min " +
                "FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key"

        /** Lineup version (§3.2.1): a hash of the ordered ids; an unchanged version is not re-emitted. */
        fun versionOf(source: GuideSource, ids: LongArray): Long {
            var h = Hash64.str(Hash64.of(null), source.toString())
            for (id in ids) h = Hash64.long(h, id)
            return h
        }
    }
}
