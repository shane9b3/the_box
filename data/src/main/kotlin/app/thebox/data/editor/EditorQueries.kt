package app.thebox.data.editor

import androidx.room.PooledConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.editor.ChannelEditorRepository
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.guide.GuideQueries
import app.thebox.data.repo.ChannelRepository

/**
 * The channel editor's raw statements (docs/design/m6-channel-editor.md §3.4–§3.6, §4). Every list is built from one
 * statement per list kind ([listSql]); the ids query, the AllOf resolution of a write, the summary and the AllOf hide all
 * use that same statement with the same filter and access, so a write never acts on rows the list did not show.
 *
 * Bind convention (numbered parameters throughout): `?1` the editor's access ordinal ([accessOrdinal]), `?2` the playlist
 * id, `?3…` the list's own parameters; callers append theirs from [Stmt.next].
 *
 * No window functions (the Robolectric tests run `AndroidSQLiteDriver`, §4 item 6).
 */
internal object EditorQueries {
    /** A select column: an expression and its alias (a compound select's outer query names the aliases). */
    class Col(val expr: String, val alias: String)

    /** A statement with its binder; [next] is the first free parameter number. */
    class Stmt(val sql: String, val next: Int, val bind: (SQLiteStatement) -> Unit)

    /**
     * `?1`: the access the editor lists under (§2.6). Locked channels are left out only while the session is locked
     * (LOCKED_AUTO / LOCKED_MANUAL_ONLY); with no PIN (FILTERED_NO_PIN) locked groups list their channels with the amber
     * 18+ chip, as the Groups panel does (U-S11), so that access filters nothing here.
     */
    fun accessOrdinal(access: ParentalAccess): Long = when (access) {
        ParentalAccess.OPEN, ParentalAccess.FILTERED_NO_PIN -> 0L
        ParentalAccess.LOCKED_AUTO -> 1L
        ParentalAccess.LOCKED_MANUAL_ONLY -> 2L
    }

    /** Hidden by the user or by a rule (the channel itself; a hidden group is the group's state). */
    const val HIDDEN_ANY = "(COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1)"

    /** Locked out under `?1`: the negated lock half of [ChannelDao.PARENTAL_ACCESS] (the guide's predicate). */
    const val LOCKED_OUT =
        "(?1 != 0 AND (COALESCE(cp.locked, CASE WHEN ?1 IN (1, 3) AND gp.locked IS NULL THEN c.is_adult ELSE 0 END) != 0 " +
            "OR COALESCE(gp.locked, CASE WHEN ?1 IN (1, 3) THEN g.is_adult ELSE 0 END, 0) != 0))"

    /** The channel's own lock (explicit, else adult detection when its group has no decision). */
    const val OWN_LOCK = "(COALESCE(cp.locked, CASE WHEN gp.locked IS NULL THEN c.is_adult ELSE 0 END, 0) != 0)"

    /** Locked through its (effective) group. */
    const val GROUP_LOCK = "(COALESCE(gp.locked, g.is_adult, 0) != 0)"

    /** In the default Favorites list (`FavouritesRepository.defaultList()`). */
    const val FAVORITE =
        "EXISTS (SELECT 1 FROM favourite f WHERE f.list_id = (SELECT id FROM favourite_list ORDER BY sort_index, id LIMIT 1) " +
            "AND f.playlist_id = c.playlist_id AND f.stable_key = c.stable_key)"

    /** The editor edits a playlist whether or not it is enabled. */
    const val PLAYLIST_JOIN = "JOIN playlist p ON p.id = c.playlist_id\n"

    const val ALL_FROM = "FROM channel c " + PLAYLIST_JOIN + ChannelDao.EFFECTIVE_GROUP_JOINS

    /** The lists' FROM: no playlist join (a list is one playlist's; the row pages read the numbering mode). */
    const val LIST_FROM = "FROM channel c " + ChannelDao.EFFECTIVE_GROUP_JOINS

    /** [FAVORITE] with the default list's id inlined (an internal Long; null = no list: nothing is a favorite). */
    fun favoriteSql(defaultListId: Long?): String = if (defaultListId == null) "0" else
        "EXISTS (SELECT 1 FROM favourite f WHERE f.list_id = $defaultListId AND f.playlist_id = c.playlist_id AND f.stable_key = c.stable_key)"

    suspend fun defaultListId(conn: PooledConnection): Long? =
        conn.usePrepared(app.thebox.data.db.dao.EditorDao.DEFAULT_LIST) { st -> if (st.step()) st.getLong(0) else null }

    /** The user's group order (the same as `GroupAdminDao.orderedKeys`). */
    const val GROUP_ORDER = "COALESCE(gp.custom_sort_index, 2147483647), g.sort_index, g.id"

    const val GROUPS_FROM =
        "FROM channel_group g LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key "

    const val UPSERT_GROUP_HIDDEN =
        "INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, updated_at) VALUES (?, 0, ?, ?, ?) " +
            "ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET hidden = excluded.hidden, updated_at = excluded.updated_at"

    /** `GroupDao.upsertGroupSortIndex` (what `GroupDao.setGroupOrder` loops), batched in the caller's transaction. */
    const val UPSERT_GROUP_SORT_INDEX =
        "INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, custom_sort_index, updated_at) VALUES (?, 0, ?, 0, ?, ?) " +
            "ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET custom_sort_index = excluded.custom_sort_index, updated_at = excluded.updated_at"

    const val UPSERT_CHANNEL_LOCKED =
        "INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, watch_count, updated_at) VALUES (?, ?, 0, ?, 0, ?) " +
            "ON CONFLICT(playlist_id, stable_key) DO UPDATE SET locked = excluded.locked, updated_at = excluded.updated_at"

    const val UPSERT_GROUP_LOCKED =
        "INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (?, 0, ?, 0, ?, ?) " +
            "ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET locked = excluded.locked, updated_at = excluded.updated_at"

    /** Bound-variable chunk (API 25's framework SQLite allows 999). */
    const val CHUNK = 500

    val COL_ID = Col("c.id", "x_id")
    val COL_KEY = Col("c.stable_key", "x_k")
    val COL_HIDDEN = Col("CASE WHEN $HIDDEN_ANY THEN 1 ELSE 0 END", "x_h")
    val COL_LOCKED_OUT = Col("CASE WHEN $LOCKED_OUT THEN 1 ELSE 0 END", "x_l")
    val COL_RULE = Col("c.rule_hidden", "x_rh")

    /**
     * The rows of [list] in list order, non-missing live/radio channels of [playlistId], selecting [cols].
     * - All (and Find): `LIST_FROM` ordered by the guide's All order ([GuideQueries.ALL_ORDER], owner ruling Q2).
     * - Find: `channel_fts` over the provider name, or the custom name (`LIKE`), in All order.
     * - Group: the shared group union ([GuideQueries.groupMembersSql]), ordered by `o1 … o4`.
     */
    fun listSql(playlistId: Long, list: EditorList, access: ParentalAccess, cols: List<Col>): Stmt {
        val ord = accessOrdinal(access)
        val select = cols.joinToString { "${it.expr} AS ${it.alias}" }
        return when (list) {
            EditorList.All -> Stmt(
                "SELECT $select $LIST_FROM WHERE c.playlist_id = ?2 AND ${GuideQueries.LINEUP_BASE} ORDER BY ${GuideQueries.ALL_ORDER}",
                3,
            ) { st -> st.bindLong(1, ord); st.bindLong(2, playlistId) }
            is EditorList.Find -> {
                val q = list.query.trim()
                val match = ChannelRepository.ftsQuery(q)
                if (q.isEmpty()) {
                    Stmt("SELECT $select $LIST_FROM WHERE c.playlist_id = ?2 AND 0", 3) { st -> st.bindLong(1, ord); st.bindLong(2, playlistId) }
                } else {
                    val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                    val fts = if (match != null) "c.id IN (SELECT rowid FROM channel_fts WHERE channel_fts MATCH ?4) OR " else ""
                    Stmt(
                        "SELECT $select $LIST_FROM WHERE c.playlist_id = ?2 AND ${GuideQueries.LINEUP_BASE} " +
                            "AND ($fts(cp.custom_name IS NOT NULL AND cp.custom_name LIKE ?3 ESCAPE '\\')) ORDER BY ${GuideQueries.ALL_ORDER}",
                        if (match != null) 5 else 4,
                    ) { st ->
                        st.bindLong(1, ord); st.bindLong(2, playlistId); st.bindText(3, like)
                        if (match != null) st.bindText(4, match)
                    }
                }
            }
            is EditorList.Group -> Stmt(
                "SELECT ${cols.joinToString { it.alias }} FROM (\n" +
                    GuideQueries.groupMembersSql("$select, ${GuideQueries.GROUP_ORDER_COLUMNS}", GuideQueries.LINEUP_BASE, "") +
                    ") ${GuideQueries.GROUP_ORDER_BY}",
                4,
            ) { st -> st.bindLong(1, ord); st.bindLong(2, playlistId); st.bindText(3, list.groupKey) }
        }
    }

    /** The filter over [COL_HIDDEN] (`x_h`). */
    fun filterSql(filter: EditorFilter): String = when (filter) {
        EditorFilter.ALL -> ""
        EditorFilter.SHOWN -> " AND x_h = 0"
        EditorFilter.HIDDEN -> " AND x_h = 1"
    }

    private fun EditorFilter.accepts(hidden: Boolean) = when (this) {
        EditorFilter.ALL -> true
        EditorFilter.SHOWN -> !hidden
        EditorFilter.HIDDEN -> hidden
    }

    /** E7: [groupKey] is locked for [access] while the session is locked (its channels are not listed then). */
    suspend fun groupLockedOut(conn: PooledConnection, playlistId: Long, groupKey: String, access: ParentalAccess): Boolean {
        val ord = accessOrdinal(access)
        if (ord == 0L) return false
        return conn.usePrepared(
            "SELECT COALESCE(gp.locked, CASE WHEN ?1 IN (1, 3) THEN g.is_adult ELSE 0 END, 0) $GROUPS_FROM" +
                "WHERE g.playlist_id = ?2 AND g.kind = 0 AND g.group_key = ?3",
        ) { st ->
            st.bindLong(1, ord); st.bindLong(2, playlistId); st.bindText(3, groupKey)
            st.step() && st.getLong(0) != 0L
        }
    }

    /** The list's ids under [filter] and [access], with its hidden count and the locked-out count (§3.4, §2.6 b). */
    suspend fun channelList(conn: PooledConnection, playlistId: Long, list: EditorList, filter: EditorFilter, access: ParentalAccess): ChannelEditorRepository.ChannelList {
        if (list is EditorList.Group && groupLockedOut(conn, playlistId, list.groupKey, access)) {
            return ChannelEditorRepository.ChannelList(LongArray(0), 0, 0, true)
        }
        val s = listSql(playlistId, list, access, listOf(COL_ID, COL_HIDDEN, COL_LOCKED_OUT))
        var ids = LongArray(256)
        var n = 0
        var hidden = 0
        var lockedOut = 0
        conn.usePrepared(s.sql) { st ->
            s.bind(st)
            while (st.step()) {
                val h = st.getLong(1) != 0L
                if (st.getLong(2) != 0L) {
                    if (filter.accepts(h)) lockedOut++
                    continue
                }
                if (h) hidden++
                if (!filter.accepts(h)) continue
                if (n == ids.size) ids = ids.copyOf(n * 2)
                ids[n++] = st.getLong(0)
            }
        }
        return ChannelEditorRepository.ChannelList(ids.copyOf(n), hidden, lockedOut, false)
    }

    /**
     * The stable keys a [target] applies to, in list order: Keys = the keys still present (not missing, the summary's
     * meaning too; in All order, so a block
     * keeps the list's order); AllOf = the list re-run under the same filter and access, minus `except`. Runs on the
     * caller's connection (inside a write transaction for writes, review 2 NIT 3).
     */
    suspend fun resolveKeys(conn: PooledConnection, playlistId: Long, target: ChannelTarget, access: ParentalAccess): List<String> = when (target) {
        is ChannelTarget.Keys -> {
            val out = ArrayList<String>(target.keys.size)
            for (chunk in target.keys.chunked(CHUNK)) {
                conn.usePrepared(
                    "SELECT c.stable_key $LIST_FROM WHERE c.playlist_id = ?1 AND c.missing_since IS NULL AND c.stable_key IN (${marks(chunk.size, 2)}) ORDER BY ${GuideQueries.ALL_ORDER}",
                ) { st ->
                    st.bindLong(1, playlistId)
                    chunk.forEachIndexed { i, k -> st.bindText(i + 2, k) }
                    while (st.step()) out += st.getText(0)
                }
            }
            out
        }
        is ChannelTarget.AllOf -> {
            if (target.list is EditorList.Group && groupLockedOut(conn, playlistId, (target.list as EditorList.Group).groupKey, access)) {
                emptyList()
            } else {
                val s = listSql(playlistId, target.list, access, listOf(COL_KEY, COL_HIDDEN, COL_LOCKED_OUT))
                val out = ArrayList<String>()
                conn.usePrepared(s.sql) { st ->
                    s.bind(st)
                    while (st.step()) {
                        if (st.getLong(2) != 0L) continue
                        if (!target.filter.accepts(st.getLong(1) != 0L)) continue
                        val k = st.getText(0)
                        if (k !in target.except) out += k
                    }
                }
                out
            }
        }
    }

    /**
     * The AllOf hide/show in one statement (§3.5): `INSERT … SELECT … ON CONFLICT DO UPDATE` over the list under its
     * filter and access. The SELECT always has a `WHERE` (SQLite's upsert parsing needs it after a join or subquery).
     * Only for an empty `except` (the caller resolves keys otherwise). Binds [Stmt.next] = hidden, +1 = now.
     */
    fun hideAllSql(playlistId: Long, target: ChannelTarget.AllOf, access: ParentalAccess): Stmt {
        val s = listSql(playlistId, target.list, access, listOf(COL_KEY, COL_HIDDEN, COL_LOCKED_OUT))
        val h = s.next
        return Stmt(
            "INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at) " +
                "SELECT ?2, x_k, ?$h, 0, ?${h + 1} FROM (${s.sql}) WHERE x_l = 0${filterSql(target.filter)} " +
                "ON CONFLICT(playlist_id, stable_key) DO UPDATE SET hidden = excluded.hidden, updated_at = excluded.updated_at",
            h + 2,
            s.bind,
        )
    }

    /** Per-target facts the writes check (effective group, provider group, locks, rule). */
    class TargetInfo(
        val effectiveKey: String?,
        val providerKey: String?,
        val moved: Boolean,
        val explicitLock: Boolean,
        val groupLock: Boolean,
        val ruleHidden: Boolean,
        /** `channel_prefs.locked` (null = automatic). */
        val ownLockSetting: Boolean?,
        val adult: Boolean,
        /** Locked now by any means ([OWN_LOCK] or [GROUP_LOCK]). */
        val lockedNow: Boolean,
    ) {
        /** Whether the channel would be locked in a group with lock decision [groupLocked] (null = automatic) and [groupAdult]. */
        fun lockedIn(groupLocked: Boolean?, groupAdult: Boolean): Boolean {
            val own = ownLockSetting ?: (groupLocked == null && adult)
            val group = groupLocked ?: groupAdult
            return own || group
        }
    }

    suspend fun targetInfo(conn: PooledConnection, playlistId: Long, keys: List<String>): Map<String, TargetInfo> {
        val out = HashMap<String, TargetInfo>(keys.size * 2)
        for (chunk in keys.chunked(CHUNK)) {
            conn.usePrepared(
                "SELECT c.stable_key, g.group_key, g0.group_key, (cp.custom_group_key IS NOT NULL AND gt.id IS NOT NULL), " +
                    "COALESCE(cp.locked, 0) = 1, $GROUP_LOCK, c.rule_hidden, cp.locked, c.is_adult, ($OWN_LOCK OR $GROUP_LOCK) " +
                    "$LIST_FROM WHERE c.playlist_id = ?1 AND c.stable_key IN (${marks(chunk.size, 2)})",
            ) { st ->
                st.bindLong(1, playlistId)
                chunk.forEachIndexed { i, k -> st.bindText(i + 2, k) }
                while (st.step()) {
                    out[st.getText(0)] = TargetInfo(
                        effectiveKey = if (st.isNull(1)) null else st.getText(1),
                        providerKey = if (st.isNull(2)) null else st.getText(2),
                        moved = st.getLong(3) != 0L,
                        explicitLock = st.getLong(4) != 0L,
                        groupLock = st.getLong(5) != 0L,
                        ruleHidden = st.getLong(6) != 0L,
                        ownLockSetting = if (st.isNull(7)) null else st.getLong(7) != 0L,
                        adult = st.getLong(8) != 0L,
                        lockedNow = st.getLong(9) != 0L,
                    )
                }
            }
        }
        return out
    }

    /** A LIVE group's lock decision (`group_prefs.locked`, null = automatic) and adult flag; null when the group is gone. */
    suspend fun groupLockFacts(conn: PooledConnection, playlistId: Long, groupKey: String): Pair<Boolean?, Boolean>? =
        conn.usePrepared("SELECT gp.locked, g.is_adult $GROUPS_FROM" + "WHERE g.playlist_id = ? AND g.kind = 0 AND g.group_key = ?") { st ->
            st.bindLong(1, playlistId); st.bindText(2, groupKey)
            if (!st.step()) null else (if (st.isNull(0)) null else st.getLong(0) != 0L) to (st.getLong(1) != 0L)
        }

    /** One member of a group's full order (every channel whose effective group it is, missing and hidden included). */
    class Member(val key: String, val sortIndex: Int?, val name: String, val customNumber: Int?, val number: Int?, val position: Int)

    /** Every member of [groupKey] in its current order (§3.5: the dense rewrite covers hidden, moved-in and missing ones). */
    suspend fun members(conn: PooledConnection, playlistId: Long, groupKey: String): List<Member> {
        val out = ArrayList<Member>()
        conn.usePrepared(
            "SELECT x_k, o2, x_n, x_cn, x_num, o3 FROM (\n" +
                GuideQueries.groupMembersSql(
                    "c.stable_key AS x_k, COALESCE(cp.custom_name, c.display_name, c.name) AS x_n, cp.custom_number AS x_cn, " +
                        "c.number AS x_num, ${GuideQueries.GROUP_ORDER_COLUMNS}",
                    "1 = 1",
                    "",
                ) + ") ${GuideQueries.GROUP_ORDER_BY}",
        ) { st ->
            st.bindLong(2, playlistId); st.bindText(3, groupKey)
            while (st.step()) {
                out += Member(
                    st.getText(0), if (st.isNull(1)) null else st.getLong(1).toInt(), st.getText(2),
                    if (st.isNull(3)) null else st.getLong(3).toInt(), if (st.isNull(4)) null else st.getLong(4).toInt(), st.getLong(5).toInt(),
                )
            }
        }
        return out
    }

    /** Ordered LIVE group keys with their hidden state (by the user or a rule), non-missing, in the user's order. */
    suspend fun groupKeys(conn: PooledConnection, playlistId: Long): List<Pair<String, Boolean>> =
        conn.usePrepared(
            "SELECT g.group_key, (COALESCE(gp.hidden, 0) = 1 OR g.rule_hidden = 1) $GROUPS_FROM" +
                "WHERE g.playlist_id = ? AND g.kind = 0 AND g.missing_since IS NULL ORDER BY $GROUP_ORDER",
        ) { st ->
            st.bindLong(1, playlistId)
            buildList { while (st.step()) add(st.getText(0) to (st.getLong(1) != 0L)) }
        }

    /** The playlist's numbering mode, or null when the playlist is gone. */
    suspend fun numberingMode(conn: PooledConnection, playlistId: Long): String? =
        conn.usePrepared("SELECT numbering_mode FROM playlist WHERE id = ?") { st -> st.bindLong(1, playlistId); if (st.step()) st.getText(0) else null }

    /** The number the guide shows, in SQL, for [mode] (custom first; `ChannelRepository.number`). */
    fun shownNumberSql(mode: String): String {
        val byMode = when {
            mode.startsWith("START_AT:") -> "(c.sort_index + ${mode.substringAfter(':').toIntOrNull() ?: 1})"
            mode == "PLAYLIST_ORDER" -> "(c.sort_index + 1)"
            else -> "COALESCE(c.number, c.sort_index + 1)"
        }
        return "COALESCE(cp.custom_number, $byMode)"
    }

    fun marks(n: Int, from: Int): String = (0 until n).joinToString(",") { "?${from + it}" }
}
