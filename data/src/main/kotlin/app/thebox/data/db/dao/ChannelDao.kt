package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import app.thebox.data.db.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

/** One channel row with the user's overrides and its group, as the browse/search/number queries return it. */
data class ChannelRow(
    @Embedded val channel: ChannelEntity,
    @ColumnInfo(name = "custom_name") val customName: String?,
    @ColumnInfo(name = "custom_number") val customNumber: Int?,
    @ColumnInfo(name = "custom_logo") val customLogo: String?,
    @ColumnInfo(name = "pref_shift") val prefShiftMin: Int?,
    @ColumnInfo(name = "pref_sort") val customSortIndex: Int?,
    @ColumnInfo(name = "group_name") val groupName: String?,
    @ColumnInfo(name = "group_key") val groupKey: String?,
) {
    /**
     * M3 read-side name (docs/design/m3-guide.md §2.9.3, §3.12): `COALESCE(channel_prefs.custom_name,
     * channel.display_name, channel.name)`. [channel]`.name` stays the raw provider name (stable keys, matcher, FTS).
     */
    val displayName: String get() = customName ?: channel.displayName ?: channel.name
}

data class UnmatchedRow(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    val name: String,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    @ColumnInfo(name = "tvg_name") val tvgName: String?,
    @ColumnInfo(name = "group_name") val groupName: String?,
    @ColumnInfo(name = "watch_count") val watchCount: Int,
    /** "sourceId\u001Fxmltv_id\u001Fscore" of the best SUGGESTED row, or null. */
    @ColumnInfo(name = "best_suggestion") val bestSuggestion: String?,
)

data class MatchCounts(val live: Int, val matched: Int, @ColumnInfo(name = "matched_with_data") val matchedWithData: Int)

data class ShiftRow(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "channel_shift") val channelShiftMin: Int?,
    @ColumnInfo(name = "pref_shift") val prefShiftMin: Int?,
)

/** Matching subject inputs (§3.6.1). */
data class SubjectRow(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    val name: String,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    @ColumnInfo(name = "tvg_name") val tvgName: String?,
)

/**
 * Read side of `channel`. Writes go through RoomMergeSink (prepared statements). Every query that feeds UI
 * applies `missing_since IS NULL` and the parental predicate ([PARENTAL] / [PARENTAL_ACCESS], §4.3) in SQL
 * (architecture review C7).
 */
@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun get(playlistId: Long, stableKey: String): ChannelEntity?

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channel WHERE playlist_id = :playlistId AND missing_since IS NULL")
    suspend fun activeCount(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM channel WHERE playlist_id = :playlistId")
    suspend fun totalCount(playlistId: Long): Int

    @Query("SELECT * FROM channel WHERE playlist_id = :playlistId ORDER BY sort_index")
    suspend fun allForPlaylist(playlistId: Long): List<ChannelEntity>

    /**
     * Guide rows for one group (M3): user order first, then provider order (§2.1.4). M1-only (no production caller outside
     * `ChannelRepository`); v5 (docs/design/m6-channel-editor.md §3.6 row 1): [groupId] is the **effective** group's id,
     * which cannot use the `group_id` index (accepted for an M1-only reader).
     */
    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND g.id = :groupId AND c.missing_since IS NULL
        """ + PARENTAL + """
        ORDER BY cp.custom_sort_index IS NULL, cp.custom_sort_index, c.sort_index""",
    )
    fun browseGroup(playlistId: Long, groupId: Long, unlocked: Boolean): Flow<List<ChannelRow>>

    /** Every live/radio channel of a playlist ("All channels"), provider order. */
    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL
        """ + PARENTAL + """
        ORDER BY c.sort_index""",
    )
    fun browseAll(playlistId: Long, unlocked: Boolean): Flow<List<ChannelRow>>

    /** Number entry: custom number, then the provider number. Numbering modes are applied by the repository. */
    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL
          AND (cp.custom_number = :number OR (cp.custom_number IS NULL AND c.number = :number))
        """ + PARENTAL + """
        ORDER BY cp.custom_number IS NULL, c.sort_index LIMIT 1""",
    )
    suspend fun byNumber(playlistId: Long, number: Int, unlocked: Boolean): ChannelRow?

    /** (M5, §4.3) [byNumber] with the caller's `ParentalAccess.ordinal` (number entry, GuideLibraryImpl). */
    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL
          AND (cp.custom_number = :number OR (cp.custom_number IS NULL AND c.number = :number))
        """ + PARENTAL_ACCESS + """
        ORDER BY cp.custom_number IS NULL, c.sort_index LIMIT 1""",
    )
    suspend fun byNumberAccess(playlistId: Long, number: Int, access: Int): ChannelRow?

    /** (M5, §4.3) [bySortIndex] with the caller's `ParentalAccess.ordinal`. */
    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL AND c.sort_index = :sortIndex
        """ + PARENTAL_ACCESS,
    )
    suspend fun bySortIndexAccess(playlistId: Long, sortIndex: Int, access: Int): ChannelRow?

    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL
        """ + PARENTAL + """
        ORDER BY c.sort_index LIMIT 1 OFFSET :position""",
    )
    suspend fun atPosition(playlistId: Long, position: Int, unlocked: Boolean): ChannelRow?

    @Query(
        SELECT_ROW + """
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL AND c.sort_index = :sortIndex
        """ + PARENTAL,
    )
    suspend fun bySortIndex(playlistId: Long, sortIndex: Int, unlocked: Boolean): ChannelRow?

    /** Guide shift inputs per live channel (§2.2). */
    @Query(
        """
        SELECT c.playlist_id, c.stable_key, c.epg_shift_min AS channel_shift, cp.epg_shift_min AS pref_shift
        FROM channel c
        LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key
        WHERE c.playlist_id IN (:playlistIds) AND c.kind IN (0, 1) AND c.missing_since IS NULL""",
    )
    suspend fun shiftInputs(playlistIds: List<Long>): List<ShiftRow>

    @Query(SELECT_ROW + " WHERE c.playlist_id = :playlistId AND c.stable_key = :stableKey")
    suspend fun row(playlistId: Long, stableKey: String): ChannelRow?

    /** Title search over channel_fts (FTS4 MATCH syntax, e.g. `"bbc"*`). */
    @Query(
        """
        SELECT c.*, cp.custom_name, cp.custom_number, cp.custom_logo, cp.epg_shift_min AS pref_shift,
               cp.custom_sort_index AS pref_sort, g.name AS group_name, g.group_key AS group_key
        FROM channel_fts
        JOIN channel c             ON c.id = channel_fts.rowid
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        """ + EFFECTIVE_GROUP_JOINS + """
        WHERE channel_fts MATCH :match AND c.missing_since IS NULL
        """ + PARENTAL + """
        ORDER BY c.playlist_id, c.sort_index LIMIT :limit""",
    )
    suspend fun search(match: String, unlocked: Boolean, limit: Int): List<ChannelRow>

    /** S13 unmatched channels, most watched first (§2.1.4). */
    @Query(UNMATCHED_HEAD + PARENTAL + UNMATCHED_ORDER)
    fun unmatched(unlocked: Boolean): Flow<List<UnmatchedRow>>

    /** (M5, §4.3, S10) [unmatched] with the caller's `ParentalAccess.ordinal` (for `EpgMatchRepository.unmatched(access)`, WP-B). */
    @Query(UNMATCHED_HEAD + PARENTAL_ACCESS + UNMATCHED_ORDER)
    fun unmatchedAccess(access: Int): Flow<List<UnmatchedRow>>

    /** Channels matched (AUTO) only to EPG channels without programmes: "No EPG" diagnostics. */
    @Query(
        """
        SELECT c.playlist_id, c.stable_key, c.name, c.tvg_id, c.tvg_name, g.name AS group_name,
               COALESCE(cp.watch_count, 0) AS watch_count, NULL AS best_suggestion
        FROM channel c
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        """ + EFFECTIVE_GROUP_JOINS + """
        LEFT JOIN epg_mapping m    ON m.playlist_id = c.playlist_id AND m.stable_key = c.stable_key
        WHERE c.kind IN (0, 1)
          AND c.missing_since IS NULL
          AND m.stable_key IS NULL
          AND EXISTS (SELECT 1 FROM epg_match a
                       WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0)
          AND NOT EXISTS (SELECT 1 FROM epg_match a
                           WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0 AND a.has_data = 1)
        """ + PARENTAL + """
        ORDER BY watch_count DESC, COALESCE(cp.last_watched_at, 0) DESC, c.playlist_id, c.sort_index""",
    )
    fun matchedWithoutData(unlocked: Boolean): Flow<List<UnmatchedRow>>

    /** (live, matched, matchedWithData) for S5 stage 5 and S13. An explicit mapping counts as matched. */
    @Query(
        """
        SELECT COUNT(*) AS live,
               SUM(CASE WHEN m.source_id IS NOT NULL OR EXISTS (SELECT 1 FROM epg_match a
                     WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0) THEN 1 ELSE 0 END) AS matched,
               SUM(CASE WHEN m.source_id IS NOT NULL OR EXISTS (SELECT 1 FROM epg_match a
                     WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0 AND a.has_data = 1) THEN 1 ELSE 0 END) AS matched_with_data
        FROM channel c
        LEFT JOIN epg_mapping m ON m.playlist_id = c.playlist_id AND m.stable_key = c.stable_key
        WHERE c.playlist_id = :playlistId AND c.kind IN (0, 1) AND c.missing_since IS NULL""",
    )
    suspend fun matchCounts(playlistId: Long): MatchCounts

    /** Matching subjects: non-missing LIVE/RADIO channels of enabled playlists ([playlistId] null = all). */
    @Query(
        """
        SELECT c.playlist_id, c.stable_key, c.name, c.tvg_id, c.tvg_name
        FROM channel c JOIN playlist p ON p.id = c.playlist_id AND p.enabled = 1
        WHERE c.kind IN (0, 1) AND c.missing_since IS NULL AND (:playlistId IS NULL OR c.playlist_id = :playlistId)
        ORDER BY c.playlist_id, c.sort_index""",
    )
    suspend fun subjects(playlistId: Long?): List<SubjectRow>

    @Query("SELECT DISTINCT tvg_id FROM channel WHERE tvg_id IS NOT NULL AND missing_since IS NULL AND kind IN (0, 1) AND (:playlistId IS NULL OR playlist_id = :playlistId)")
    suspend fun tvgIds(playlistId: Long?): List<String>

    companion object {
        // ---- v5 effective group (docs/design/m6-channel-editor.md §3.6, review 2 ruling a and item 1). Every reader of a
        // channel's group joins through these pieces; ProviderGroupJoinGuardTest fails on any other provider-group join.

        /** The provider's group (refresh-owned membership). Only the allowlisted readers use it alone (§3.6 rows 16–20). */
        const val PROVIDER_GROUP_JOIN = "LEFT JOIN channel_group g0 ON g0.id = c.group_id\n"

        /** The channel's prefs (for queries that do not already drive from `channel_prefs cp`). */
        const val CP_JOIN = "LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key\n"

        /**
         * Needs `g0` and `cp` in scope: the move target `gt` (any existing row, missing or not: a target in its grace period
         * keeps its lock and hidden state, ruling a), the effective group `g` (the target, else the provider group) and its
         * prefs `gp`. A dangling `custom_group_key` (no row) falls back to the provider group.
         */
        const val GROUP_TAIL = """
        LEFT JOIN channel_group gt ON gt.playlist_id = c.playlist_id AND gt.kind = 0 AND gt.group_key = cp.custom_group_key
        LEFT JOIN channel_group g  ON g.id = COALESCE(gt.id, g0.id)
        LEFT JOIN group_prefs gp   ON gp.playlist_id = c.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
        """

        const val EFFECTIVE_GROUP_TAIL = CP_JOIN + GROUP_TAIL

        /** `FROM channel c` + this = the channel with its prefs, its provider group `g0` and its effective group `g`/`gp`. */
        const val EFFECTIVE_GROUP_JOINS = PROVIDER_GROUP_JOIN + EFFECTIVE_GROUP_TAIL

        const val UNMATCHED_HEAD = """
        SELECT c.playlist_id, c.stable_key, c.name, c.tvg_id, c.tvg_name, g.name AS group_name,
               COALESCE(cp.watch_count, 0) AS watch_count,
               (SELECT em.source_id || char(31) || em.xmltv_id || char(31) || em.score
                  FROM epg_match em
                 WHERE em.playlist_id = c.playlist_id AND em.stable_key = c.stable_key AND em.status = 1
                 ORDER BY em.score DESC LIMIT 1) AS best_suggestion
        FROM channel c
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        """ + EFFECTIVE_GROUP_JOINS + """
        LEFT JOIN epg_mapping m    ON m.playlist_id = c.playlist_id AND m.stable_key = c.stable_key
        WHERE c.kind IN (0, 1)
          AND c.missing_since IS NULL
          AND m.stable_key IS NULL
          AND NOT EXISTS (SELECT 1 FROM epg_match a
                           WHERE a.playlist_id = c.playlist_id AND a.stable_key = c.stable_key AND a.status = 0)
        """
        const val UNMATCHED_ORDER = """
        ORDER BY watch_count DESC, COALESCE(cp.last_watched_at, 0) DESC, c.playlist_id, c.sort_index"""

        const val SELECT_ROW = """
        SELECT c.*, cp.custom_name, cp.custom_number, cp.custom_logo, cp.epg_shift_min AS pref_shift,
               cp.custom_sort_index AS pref_sort, g.name AS group_name, g.group_key AS group_key
        FROM channel c
        """ + EFFECTIVE_GROUP_JOINS

        /** Hidden channels and groups (by the user or by a hide rule) never show (§4.3). */
        private const val HIDDEN = """
          AND COALESCE(cp.hidden, 0) = 0
          AND COALESCE(gp.hidden, 0) = 0
          AND c.rule_hidden = 0
          AND COALESCE(g.rule_hidden, 0) = 0
        """

        /**
         * The parental and rule predicate (docs/design/m5-setup-settings.md §4.3; reviewed 2026-10-16, B3, S5, S10):
         * `:access` = `ParentalAccess.ordinal` (OPEN 0, LOCKED_AUTO 1, LOCKED_MANUAL_ONLY 2, FILTERED_NO_PIN 3).
         * FILTERED_NO_PIN filters exactly as LOCKED_AUTO. An explicit group decision (`gp.locked` not null) overrides
         * per-channel adult detection; an explicit channel lock still wins over everything. No query reads global
         * parental state: the caller passes the access.
         */
        const val PARENTAL_ACCESS = HIDDEN + """
          AND (:access = 0 OR (
                COALESCE(cp.locked, CASE WHEN :access IN (1, 3) AND gp.locked IS NULL THEN c.is_adult ELSE 0 END) = 0
            AND COALESCE(gp.locked, CASE WHEN :access IN (1, 3) THEN g.is_adult ELSE 0 END, 0) = 0))
        """

        /**
         * The same predicate for the M1–M4 signatures that still pass `unlocked: Boolean` (ChannelRepository,
         * FavouritesRepository, EpgMatchRepository, tests): `unlocked = true` is OPEN, `false` is LOCKED_AUTO.
         */
        const val PARENTAL = HIDDEN + """
          AND (:unlocked = 1 OR (
                COALESCE(cp.locked, CASE WHEN gp.locked IS NULL THEN c.is_adult ELSE 0 END) = 0
            AND COALESCE(gp.locked, g.is_adult, 0) = 0))
        """
    }
}
