package app.thebox.data.backup

import androidx.room.PooledConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.backup.BackupCounts
import app.thebox.core.backup.BackupCrypto
import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupJson
import app.thebox.core.backup.BackupRecord
import app.thebox.core.backup.BackupWriter
import app.thebox.core.backup.MergePlanner
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsStore
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.repo.queryLong
import app.thebox.data.repo.read
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream

/**
 * Streams the library's user data into a `.thebox` file (docs/design/m5-setup-settings.md §3.10.1–§3.10.3), record by
 * record in the order of §3.10.1, reading each table with one statement (constant memory whatever the library size).
 * Logins go into one `Secrets` record sealed under the passphrase with the header line as associated data.
 */
internal class BackupExporter(
    private val db: LibraryDatabase,
    private val creds: CredentialStore,
    private val store: SettingsStore,
    private val env: BackupEnvironment,
    private val nowMs: () -> Long,
    private val kdfIterations: Int,
) {
    /** Domains never exported even if a store marks them backed up (device-bound, §3.10.2). */
    private val deviceBound = setOf(Domains.BACKUP, Domains.UPDATE, Domains.SETUP)

    suspend fun export(sink: OutputStream, passphrase: CharArray?): Outcome<BackupHeader> {
        val now = nowMs()
        store.flush()
        val settings = store.export().filterKeys { it !in deviceBound }
        val playlists = db.playlistDao().all()
        val uidOf = playlists.associate { it.id to (it.uid ?: it.id.toString()) }
        val sources = db.epgSourceDao().all()

        // Logins first (only with a passphrase): one map, sealed after the header is written.
        var secrets: Map<String, PlaylistSecret>? = null
        if (passphrase != null) {
            val m = LinkedHashMap<String, PlaylistSecret>()
            for (p in playlists) when (val s = creds.get(p.credentialRef)) {
                is Outcome.Ok -> m[secretRef(p.uid ?: p.id.toString())] = s.value
                is Outcome.Err -> BoxLog.w(TAG, "login of playlist ${p.id} not readable (${s.error.code}); exported without it")
            }
            for (s in sources) if (s.origin == EpgSourceEntity.ORIGIN_USER) {
                (creds.get(s.credentialRef) as? Outcome.Ok)?.let { m[epgRef(s)] = it.value }
            }
            secrets = m
        }

        val counts = db.read {
            mapOf(
                BackupCounts.SETTINGS to settings.size,
                BackupCounts.PLAYLISTS to playlists.size,
                BackupCounts.EPG_SOURCES to sources.size,
                BackupCounts.GROUP_PREFS to count("SELECT COUNT(*) FROM group_prefs"),
                BackupCounts.CHANNEL_PREFS to count("SELECT COUNT(*) FROM channel_prefs WHERE $USER_PREF_PREDICATE"),
                BackupCounts.FAV_LISTS to count("SELECT COUNT(*) FROM favourite_list"),
                BackupCounts.FAVOURITES to count("SELECT COUNT(*) FROM favourite"),
                BackupCounts.REMINDERS to count("SELECT COUNT(*) FROM reminder WHERE state = 0 AND start_min * 60000 > $now"),
                BackupCounts.EPG_MAPPINGS to count("SELECT COUNT(*) FROM epg_mapping"),
                BackupCounts.HIDE_RULES to count("SELECT COUNT(*) FROM hide_rule"),
            )
        }
        val header = BackupHeader(createdAtMs = now, appVersionName = env.versionName, appVersionCode = env.versionCode, includesLogins = secrets != null, counts = counts)
        val w = BackupWriter(sink)
        val headerLine = w.writeHeader(header)

        for ((domain, json) in settings) w.write(BackupRecord.Settings(domain, json))
        for (p in playlists) {
            val cols = db.read { playlistColumns(this, p.id) }
            w.write(BackupRecord.Playlist(uidOf.getValue(p.id), p.name, p.type.name, p.displaySource, p.sortIndex, p.enabled, cols, secrets?.let { secretRef(uidOf.getValue(p.id)) }))
        }
        for (s in sources) {
            w.write(
                BackupRecord.EpgSource(
                    s.playlistId?.let { uidOf[it] }, s.name, s.origin, s.ordinal, s.priority, s.enabled, s.shiftMin, s.assumeZone,
                    if (secrets != null && s.origin == EpgSourceEntity.ORIGIN_USER && secrets.containsKey(epgRef(s))) epgRef(s) else null,
                ),
            )
        }
        if (secrets != null) {
            val plain = BackupJson.encodeToString(SECRETS_SERIALIZER, secrets).encodeToByteArray()
            try {
                w.write(BackupCrypto.seal(plain, passphrase!!, headerLine, kdfIterations))
            } finally {
                plain.fill(0)
            }
        }
        db.read {
            stream("SELECT playlist_id, kind, group_key, hidden, locked, custom_sort_index, custom_name FROM group_prefs ORDER BY playlist_id, kind, group_key") { st ->
                val uid = uidOf[st.getLong(0)] ?: return@stream
                w.write(BackupRecord.GroupPref(uid, st.getLong(1).toInt(), st.getText(2), st.getLong(3) != 0L, st.boolOrNull(4), st.intOrNull(5), st.textOrNull(6)))
            }
            val cols = MergePlanner.CHANNEL_PREF_COLUMNS.toList()
            stream("SELECT playlist_id, stable_key, ${cols.joinToString()} FROM channel_prefs WHERE $USER_PREF_PREDICATE ORDER BY playlist_id, stable_key") { st ->
                val uid = uidOf[st.getLong(0)] ?: return@stream
                val fields = LinkedHashMap<String, JsonElement>()
                cols.forEachIndexed { i, c -> primitive(st, i + 2)?.let { fields[c] = it } }
                w.write(BackupRecord.ChannelPref(uid, st.getText(1), fields))
            }
            stream("SELECT id, name, sort_index FROM favourite_list ORDER BY sort_index, id") { st ->
                w.write(BackupRecord.FavouriteList(st.getLong(0).toString(), st.getText(1), st.getLong(2).toInt()))
            }
            stream("SELECT list_id, playlist_id, stable_key, sort_index, added_at FROM favourite ORDER BY list_id, sort_index") { st ->
                val uid = uidOf[st.getLong(1)] ?: return@stream
                w.write(BackupRecord.Favourite(st.getLong(0).toString(), uid, st.getText(2), st.getLong(3).toInt(), st.getLong(4)))
            }
            stream("SELECT playlist_id, stable_key, start_min, end_min, title_hash, title, channel_name, lead_sec, shift_min, state FROM reminder ORDER BY start_min") { st ->
                val uid = uidOf[st.getLong(0)] ?: return@stream
                if (!MergePlanner.reminderTravels(st.getLong(9).toInt(), st.getLong(2).toInt(), now)) return@stream
                w.write(BackupRecord.Reminder(uid, st.getText(1), st.getLong(2).toInt(), st.getLong(3).toInt(), st.getLong(4), st.getText(5), st.getText(6), st.getLong(7).toInt(), st.getLong(8).toInt()))
            }
            val sourceKeys = sources.associate { s -> s.id to MergePlanner.sourceKey(s.origin, s.playlistId?.let { uidOf[it] }, s.ordinal, s.urlHash) }
            stream("SELECT playlist_id, stable_key, source_id, xmltv_id FROM epg_mapping ORDER BY playlist_id, stable_key") { st ->
                val uid = uidOf[st.getLong(0)] ?: return@stream
                val key = if (st.isNull(2)) null else sourceKeys[st.getLong(2)]
                w.write(BackupRecord.EpgMapping(uid, st.getText(1), key, st.textOrNull(3)))
            }
            stream("SELECT playlist_id, target, pattern, is_regex, enabled FROM hide_rule ORDER BY playlist_id, id") { st ->
                val uid = uidOf[st.getLong(0)] ?: return@stream
                w.write(BackupRecord.HideRule(uid, st.getLong(1).toInt(), st.getText(2), st.getLong(3) != 0L, st.getLong(4) != 0L))
            }
        }
        val n = w.finish()
        BoxLog.i(TAG) { "exported $n records, logins=${secrets != null}" }
        return Outcome.Ok(header)
    }

    private suspend fun PooledConnection.count(sql: String): Int = queryLong(sql).toInt()

    private suspend fun PooledConnection.stream(sql: String, row: (SQLiteStatement) -> Unit) {
        usePrepared(sql) { st -> while (st.step()) row(st) }
    }

    private suspend fun playlistColumns(conn: PooledConnection, id: Long): Map<String, JsonElement> {
        val cols = MergePlanner.PLAYLIST_COLUMNS.toList()
        return conn.usePrepared("SELECT ${cols.joinToString()} FROM playlist WHERE id = ?") { st ->
            st.bindLong(1, id)
            val out = LinkedHashMap<String, JsonElement>()
            if (st.step()) cols.forEachIndexed { i, c -> primitive(st, i)?.let { out[c] = it } }
            out
        }
    }

    companion object {
        private const val TAG = "BackupExporter"

        /** A channel_prefs row carries something the user chose (rows holding only learned columns stay home). */
        const val USER_PREF_PREDICATE = "(hidden = 1 OR locked IS NOT NULL OR custom_name IS NOT NULL OR custom_number IS NOT NULL OR " +
            "custom_logo IS NOT NULL OR custom_sort_index IS NOT NULL OR epg_shift_min IS NOT NULL OR aspect_mode IS NOT NULL OR " +
            "audio_lang IS NOT NULL OR subtitle_lang IS NOT NULL OR video_decoder IS NOT NULL OR buffer_profile IS NOT NULL OR " +
            "last_watched_at IS NOT NULL OR watch_count > 0)"

        val SECRETS_SERIALIZER = MapSerializer(String.serializer(), PlaylistSecret.serializer())

        fun secretRef(playlistUid: String) = "playlist:$playlistUid"
        fun epgRef(s: EpgSourceEntity) = "epg:${s.urlHash}"

        /** The column as a JSON primitive by its SQLite type; null for NULL. */
        fun primitive(st: SQLiteStatement, i: Int): JsonPrimitive? = when (st.getColumnType(i)) {
            SQLITE_INTEGER -> JsonPrimitive(st.getLong(i))
            SQLITE_FLOAT -> JsonPrimitive(st.getDouble(i))
            SQLITE_TEXT -> JsonPrimitive(st.getText(i))
            else -> null
        }

        private const val SQLITE_INTEGER = 1
        private const val SQLITE_FLOAT = 2
        private const val SQLITE_TEXT = 3

        private fun SQLiteStatement.textOrNull(i: Int) = if (isNull(i)) null else getText(i)
        private fun SQLiteStatement.intOrNull(i: Int) = if (isNull(i)) null else getLong(i).toInt()
        private fun SQLiteStatement.boolOrNull(i: Int) = if (isNull(i)) null else getLong(i) != 0L
    }
}

/** App facts the header carries. */
interface BackupEnvironment {
    val versionName: String
    val versionCode: Long
}
