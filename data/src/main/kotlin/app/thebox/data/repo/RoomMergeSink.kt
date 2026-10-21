package app.thebox.data.repo

import androidx.room.PooledConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.common.BoxLog
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.FinishStats
import app.thebox.core.refresh.GroupRow
import app.thebox.core.refresh.KeyedChannel
import app.thebox.core.refresh.MergeBatch
import app.thebox.core.refresh.MergeFinish
import app.thebox.core.refresh.MergeSink
import app.thebox.core.source.EntryKind
import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.ChannelNamePrefixImpl
import app.thebox.core.text.NamePrefixRule
import app.thebox.core.text.NameNormalizer
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.rules.RuleHiddenPass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [MergeSink] over Room's writer connection (docs/design/m1-data-layer.md §3.3). Blocking by design (S22):
 * each call bridges with `runBlocking { db.useWriterConnection { immediateTransaction { … } } }` on the
 * importer thread, never on Main. Every [apply] is one transaction; missing/purge/groups/playlist state and
 * the user-data remaps happen only in [finish].
 *
 * `channel_fts` (standalone FTS4, S6) is maintained here: insert with the channel, delete+insert when the
 * name changes, delete on purge. Touches never write FTS rows.
 */
class RoomMergeSink(
    private val db: LibraryDatabase,
    private val playlistId: Long,
    private val nowMs: Long,
    private val refreshSeq: Long,
    private val graceMs: Long = 7L * 24 * 3600 * 1000,
    /** Validators of the downloaded file: persisted only in [finish] (S10). */
    private val etag: String? = null,
    private val lastModified: String? = null,
    /** Stores credentials found by pass 1 in the playlist secret. */
    private val onDetected: suspend (DetectedCredentials) -> Unit = {},
    /**
     * M3 (docs/design/m3-guide.md §3.12.3): the playlist's channel-name prefix rule. `display_name` is a content column
     * bound from `strip(name, group-title, rule)` on insert and update; touches never change it (name and group are in
     * the content hash). A rule change alone is not a channel change: it never reaches the differ (no stable-key or diff
     * churn) and is applied by `DisplayNames.recompute`.
     */
    private val prefixRule: NamePrefixRule = NamePrefixRule.Auto,
    private val prefix: ChannelNamePrefix = ChannelNamePrefixImpl(),
) : MergeSink {
    private val groupIds = HashMap<String, Long>()

    /** Credentials pass 1 found (Xtream-shaped M3U), for the B1 login after the merge. */
    @Volatile var detected: DetectedCredentials? = null
        private set

    /** FTS rows written (test hook for S6: a pure re-order writes none). */
    @Volatile var ftsWrites: Int = 0
        private set

    @Volatile var batches: Int = 0
        private set

    override fun begin(groups: List<GroupRow>, detected: DetectedCredentials?): Unit = runBlocking {
        this@RoomMergeSink.detected = detected
        if (detected != null) onDetected(detected)
        db.writeTransaction {
            // (docs/design/m6-channel-editor.md §3.5 rule e, amended by the data review 2026-10-18) A group that comes back
            // from missing keeps its user position unless another group of the playlist now holds the same index
            // (GroupAdminDao.orderedKeys skipped it while missing, so an order rewrite meanwhile can reuse its slot). Only
            // on such a tie is its index cleared, and it rejoins after the custom-ordered groups; a one-refresh category
            // blip leaves the user's order alone. Same transaction as the upsert that clears missing_since.
            forEachBound(
                """
                UPDATE group_prefs SET custom_sort_index = NULL
                WHERE playlist_id = ? AND kind = ? AND group_key = ? AND custom_sort_index IS NOT NULL
                  AND EXISTS (SELECT 1 FROM channel_group g WHERE g.playlist_id = group_prefs.playlist_id AND g.kind = group_prefs.kind
                                AND g.group_key = group_prefs.group_key AND g.missing_since IS NOT NULL)
                  AND EXISTS (SELECT 1 FROM group_prefs o WHERE o.playlist_id = group_prefs.playlist_id AND o.kind = group_prefs.kind
                                AND o.group_key <> group_prefs.group_key AND o.custom_sort_index = group_prefs.custom_sort_index)""",
                groups,
            ) {
                bindLong(1, playlistId); bindLong(2, groupKind(it.kind).toLong()); bindText(3, it.groupKey)
            }
            forEachBound(
                """
                INSERT INTO channel_group(playlist_id, kind, group_key, name, sort_index, is_adult, missing_since)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET
                  name = excluded.name, sort_index = excluded.sort_index, is_adult = excluded.is_adult, missing_since = NULL""",
                groups,
            ) {
                bindLong(1, playlistId); bindLong(2, groupKind(it.kind).toLong()); bindText(3, it.groupKey)
                bindText(4, it.name); bindLong(5, it.sortIndex.toLong()); bindBool(6, it.adult)
            }
            usePrepared("SELECT id, kind, group_key FROM channel_group WHERE playlist_id = ?") { st ->
                st.bindLong(1, playlistId)
                while (st.step()) groupIds["${st.getLong(1)}|${st.getText(2)}"] = st.getLong(0)
            }
        }
        BoxLog.i(TAG) { "begin playlist=$playlistId groups=${groups.size}" }
    }

    override fun apply(batch: MergeBatch): Unit = runBlocking {
        db.writeTransaction {
            if (batch.inserts.isNotEmpty()) {
                forEachBound(INSERT_SQL, batch.inserts) { kc ->
                    bindLong(1, playlistId)
                    bindText(2, kc.stableKey.value)
                    bindContent(this, kc, 3)
                    bindLong(3 + CONTENT.size, nowMs)
                    bindLong(4 + CONTENT.size, nowMs)
                }
                ftsWrites += forEachBound(
                    "INSERT INTO channel_fts(rowid, name) SELECT id, name FROM channel WHERE playlist_id = ? AND stable_key = ?",
                    batch.inserts,
                ) { kc -> bindLong(1, playlistId); bindText(2, kc.stableKey.value) }
                // A key that comes back after a purge: its tombstone goes, its user data reattaches.
                forEachBound("DELETE FROM purged_key WHERE playlist_id = ? AND stable_key = ?", batch.inserts) { kc ->
                    bindLong(1, playlistId); bindText(2, kc.stableKey.value)
                }
            }
            if (batch.updates.isNotEmpty()) {
                val renamed = ArrayList<Pair<Long, String>>()
                usePrepared("SELECT name FROM channel WHERE id = ?") { st ->
                    for (u in batch.updates) {
                        st.bindLong(1, u.id)
                        if (st.step() && st.getText(0) != u.channel.channel.name) renamed += u.id to u.channel.channel.name
                        st.reset()
                    }
                }
                forEachBound(UPDATE_SQL, batch.updates) { u ->
                    bindContent(this, u.channel, 1)
                    bindLong(1 + CONTENT.size, nowMs)
                    bindLong(2 + CONTENT.size, u.id)
                }
                if (renamed.isNotEmpty()) {
                    forEachBound("DELETE FROM channel_fts WHERE rowid = ?", renamed) { bindLong(1, it.first) }
                    ftsWrites += forEachBound("INSERT INTO channel_fts(rowid, name) VALUES (?, ?)", renamed) {
                        bindLong(1, it.first); bindText(2, it.second)
                    }
                }
            }
            if (batch.touches.isNotEmpty()) {
                forEachBound("UPDATE channel SET sort_index = ?, missing_since = NULL, missing_count = 0 WHERE id = ?", batch.touches) {
                    bindLong(1, it.sortIndex.toLong()); bindLong(2, it.id)
                }
            }
        }
        batches++
        Unit
    }

    override fun finish(finish: MergeFinish): FinishStats = runBlocking {
        val stats = db.writeTransaction {
            val now = finish.nowMs
            forEachBound("UPDATE channel SET missing_since = ?, missing_count = 1 WHERE id = ?", finish.newlyMissingIds.asIterable()) {
                bindLong(1, now); bindLong(2, it)
            }
            forEachBound("UPDATE channel SET missing_count = missing_count + 1 WHERE id = ?", finish.stillMissingIds.asIterable()) {
                bindLong(1, it)
            }
            // S7: single-key migrations, inside this transaction.
            UserDataRemapper.apply(this, playlistId, finish.autoRemaps)

            if (finish.purgeIds.isNotEmpty()) {
                forEachBound("DELETE FROM channel_fts WHERE rowid = ?", finish.purgeIds.asIterable()) { bindLong(1, it) }
                forEachBound("DELETE FROM channel WHERE id = ?", finish.purgeIds.asIterable()) { bindLong(1, it) }
            }
            // S8: tombstones start the 180-day orphan clock.
            forEachBound(
                "INSERT INTO purged_key(playlist_id, stable_key, purged_at) VALUES (?, ?, ?) ON CONFLICT(playlist_id, stable_key) DO UPDATE SET purged_at = excluded.purged_at",
                finish.purgedKeys,
            ) { bindLong(1, playlistId); bindText(2, it); bindLong(3, now) }

            updateGroups(this, finish.groupKeysSeen, now)

            val removedSample = ArrayList<String>()
            if (finish.newlyMissingIds.isNotEmpty()) {
                usePrepared("SELECT name FROM channel WHERE id = ?") { st ->
                    for (id in finish.newlyMissingIds) {
                        if (removedSample.size >= SAMPLE) break
                        st.bindLong(1, id)
                        if (st.step()) removedSample += st.getText(0)
                        st.reset()
                    }
                }
            }
            val liveCount = queryLong("SELECT COUNT(*) FROM channel WHERE playlist_id = ? AND kind IN (0, 1) AND missing_since IS NULL") {
                bindLong(1, playlistId)
            }
            val favTotal = queryLong("SELECT COUNT(*) FROM favourite WHERE playlist_id = ?") { bindLong(1, playlistId) }
            val favKept = queryLong(
                """
                SELECT COUNT(*) FROM favourite f WHERE f.playlist_id = ? AND EXISTS (
                  SELECT 1 FROM channel c WHERE c.playlist_id = f.playlist_id AND c.stable_key = f.stable_key AND c.missing_since IS NULL)""",
            ) { bindLong(1, playlistId) }
            exec(
                """
                UPDATE playlist SET live_count = ?, refresh_seq = ?, last_success_at = ?, last_refresh_at = ?, last_status = 'SUCCESS',
                       etag = ?, last_modified = ? WHERE id = ?""",
            ) {
                bindLong(1, liveCount); bindLong(2, refreshSeq); bindLong(3, now); bindLong(4, now)
                bindTextOrNull(5, etag); bindTextOrNull(6, lastModified); bindLong(7, playlistId)
            }
            // (M3 §3.12.3, review S4) `name_prefix_ver` is never raised here. Rows this merge did not insert or update
            // keep display names from whatever rule computed them, so stamping RULE_VERSION when the stored value is
            // older would make the startup backfill skip this playlist forever. Only DisplayNames.recompute raises it.
            FinishStats(finish.purgeIds.size, favKept.toInt(), (favTotal - favKept).toInt(), removedSample)
        }
        // M5 (docs/design/m5-setup-settings.md §3.7.3): the hide-rule pass after every merge. D-S5 (code review
        // 2026-10-16): in its own short transaction after the merge committed, never inside the merge's transaction, so a
        // slow rule (ICU cannot be interrupted on Android) never holds the merge. New rows arrive with rule_hidden = 0
        // (column default); the UPDATE and ON CONFLICT paths above never list rule_hidden, so existing flags survive until
        // the pass recomputes them. A failing pass does not undo the merge.
        try {
            db.writeTransaction { RuleHiddenPass.run(this, playlistId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "hide-rule pass failed for playlist $playlistId: ${e.javaClass.simpleName}")
        }
        BoxLog.i(TAG) {
            "finish playlist=$playlistId batches=$batches newlyMissing=${finish.newlyMissingIds.size} purged=${finish.purgeIds.size} " +
                "remaps=${finish.autoRemaps.size}"
        }
        stats
    }

    override fun abort(error: Throwable) {
        BoxLog.w(TAG, "merge aborted for playlist $playlistId after $batches batches: ${error.message}")
    }

    private suspend fun updateGroups(conn: PooledConnection, seen: Set<String>, now: Long) {
        val unseen = ArrayList<Long>()
        conn.usePrepared("SELECT id, group_key FROM channel_group WHERE playlist_id = ?") { st ->
            st.bindLong(1, playlistId)
            while (st.step()) if (st.getText(1) !in seen) unseen += st.getLong(0)
        }
        conn.forEachBound("UPDATE channel_group SET missing_since = COALESCE(missing_since, ?) WHERE id = ?", unseen) {
            bindLong(1, now); bindLong(2, it)
        }
        // (docs/design/m6-channel-editor.md §3.6 a) Before the purge, in the same batch: channels moved into a group that is
        // about to be deleted go home (custom_group_key and the target's custom_sort_index cleared, §3.5 rule b). The
        // delete predicate is unchanged, so a group left with only moved-in channels is purged too.
        conn.exec(
            """
            UPDATE channel_prefs SET custom_group_key = NULL, custom_sort_index = NULL
            WHERE playlist_id = ? AND custom_group_key IN (
              SELECT group_key FROM channel_group WHERE playlist_id = ? AND kind = 0 AND missing_since IS NOT NULL AND missing_since <= ?
                AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.group_id = channel_group.id))""",
        ) { bindLong(1, playlistId); bindLong(2, playlistId); bindLong(3, now - graceMs) }
        conn.exec(
            """
            DELETE FROM channel_group WHERE playlist_id = ? AND missing_since IS NOT NULL AND missing_since <= ?
              AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.group_id = channel_group.id)""",
        ) { bindLong(1, playlistId); bindLong(2, now - graceMs) }
    }

    private fun bindContent(st: SQLiteStatement, kc: KeyedChannel, start: Int) {
        val ch = kc.channel
        var i = start
        st.bindText(i++, kc.rule.name)
        st.bindLong(i++, ch.kind.ordinal.toLong())
        st.bindText(i++, ch.name)
        st.bindText(i++, NameNormalizer.forMatch(ch.name))
        st.bindIntOrNull(i++, ch.number)
        st.bindLong(i++, kc.sortIndex.toLong())
        st.bindLongOrNull(i++, groupIds["${groupKind(ch.kind)}|${kc.groupKey}"])
        st.bindTextOrNull(i++, ch.logoUrl)
        st.bindTextOrNull(i++, ch.tvgId)
        st.bindTextOrNull(i++, ch.tvgName)
        st.bindText(i++, kc.urlTemplate)
        st.bindTextOrNull(i++, Converters.encodeMap(ch.headers))
        st.bindLongOrNull(i++, ch.xtreamStreamId)
        st.bindTextOrNull(i++, ch.containerExtension)
        st.bindIntOrNull(i++, ch.epgShiftMinutes)
        val c = ch.catchup
        st.bindTextOrNull(i++, c.type)
        st.bindTextOrNull(i++, c.source)
        st.bindIntOrNull(i++, c.days)
        st.bindDoubleOrNull(i++, c.correctionHours)
        st.bindIntOrNull(i++, c.timeshiftDays)
        st.bindIntOrNull(i++, c.tvgRecDays)
        st.bindLongOrNull(i++, c.catchupTimeSec)
        st.bindBoolOrNull(i++, c.xtreamTvArchive)
        st.bindIntOrNull(i++, c.xtreamArchiveDays)
        st.bindBool(i++, ch.adult)
        st.bindTextOrNull(i++, extrasJson(kc))
        st.bindLong(i++, kc.contentHash)
        st.bindTextOrNull(i, displayName(ch.name, ch.groupTitle))
    }

    private fun displayName(name: String, group: String?): String? = try {
        prefix.strip(name, group, prefixRule)
    } catch (e: RuntimeException) {
        BoxLog.w(TAG, "prefix rule failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "RoomMergeSink"
        private const val SAMPLE = 10

        /** Content columns, in bind order (shared by insert and update). */
        private val CONTENT = listOf(
            "key_rule", "kind", "name", "norm_name", "number", "sort_index", "group_id", "logo_url", "tvg_id", "tvg_name",
            "url", "headers_json", "xtream_stream_id", "container_ext", "epg_shift_min", "catchup_type", "catchup_source",
            "catchup_days", "catchup_correction_h", "timeshift_days", "tvg_rec_days", "catchup_time_sec", "tv_archive",
            "tv_archive_days", "is_adult", "extras_json", "content_hash", "display_name",
        )

        private val INSERT_SQL =
            "INSERT INTO channel(playlist_id, stable_key, ${CONTENT.joinToString()}, missing_since, missing_count, first_seen_at, updated_at) " +
                "VALUES (?, ?, ${CONTENT.joinToString { "?" }}, NULL, 0, ?, ?)"

        private val UPDATE_SQL =
            "UPDATE channel SET ${CONTENT.joinToString { "$it = ?" }}, missing_since = NULL, missing_count = 0, updated_at = ? WHERE id = ?"

        /** RADIO shares LIVE groups (stored as kind 0). */
        fun groupKind(kind: EntryKind): Int = if (kind == EntryKind.RADIO) EntryKind.LIVE.ordinal else kind.ordinal

        /** {"attrs":{},"kodi":{},"vlc":{},"groups":[],"duration":n}, empty parts omitted; null when all empty. */
        fun extrasJson(kc: KeyedChannel): String? {
            val ch = kc.channel
            val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            fun obj(m: Map<String, String>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })
            if (ch.attributes.isNotEmpty()) map["attrs"] = obj(ch.attributes)
            if (ch.kodiProps.isNotEmpty()) map["kodi"] = obj(ch.kodiProps)
            if (ch.vlcOpts.isNotEmpty()) map["vlc"] = obj(ch.vlcOpts)
            if (ch.extraGroups.isNotEmpty()) map["groups"] = JsonArray(ch.extraGroups.map { JsonPrimitive(it) })
            ch.durationSec?.let { map["duration"] = JsonPrimitive(it) }
            ch.xtreamCategoryId?.let { map["xcat"] = JsonPrimitive(it) }
            return if (map.isEmpty()) null else JsonObject(map).toString()
        }
    }
}
