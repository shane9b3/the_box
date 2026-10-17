package app.thebox.data.backup

import androidx.room.PooledConnection
import app.thebox.core.backup.BackupCrypto
import app.thebox.core.backup.BackupCryptoException
import app.thebox.core.backup.BackupFormatException
import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupJson
import app.thebox.core.backup.BackupReader
import app.thebox.core.backup.BackupRecord
import app.thebox.core.backup.MatchTarget
import app.thebox.core.backup.MergePlanner
import app.thebox.core.backup.PlaylistMatch
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.settings.SettingsStore
import app.thebox.core.source.PlaylistType
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.repo.bindBool
import app.thebox.data.repo.bindIntOrNull
import app.thebox.data.repo.bindTextOrNull
import app.thebox.data.repo.exec
import app.thebox.data.repo.queryLong
import app.thebox.data.repo.writeTransaction
import app.thebox.data.work.ImportMutex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Preview and restore of a `.thebox` file (docs/design/m5-setup-settings.md §3.10.4). Three streaming passes, never
 * the whole file in memory: (1) preview/validate (header, every line parses, `End` matches, limits) collecting only
 * the playlists, the settings documents and the secrets record; (2) the passphrase opens the secrets, so a wrong one
 * fails before anything is written; (3) apply under [ImportMutex] in **one** library.db transaction, including
 * `SettingsStore.importInTransaction`. Secrets are written to the credential store before the transaction (the
 * Keystore is not transactional); if the transaction fails, the refs written for this restore are deleted.
 */
internal class BackupRestorer(
    private val db: LibraryDatabase,
    private val creds: CredentialStore,
    private val store: SettingsStore,
    private val importMutex: ImportMutex,
    private val nowMs: () -> Long,
) {
    /** What pass 1 keeps (small: playlists, settings documents, one secrets record). */
    class Scan(
        val header: BackupHeader,
        val headerBytes: ByteArray,
        val playlists: List<BackupRecord.Playlist>,
        val settings: Map<String, JsonElement>,
        val secrets: BackupRecord.Secrets?,
        val records: Int,
    )

    /** Outcome of the apply pass for the follow-up work after commit. */
    class Applied(
        val result: RestoreResult.Done,
        val newPlaylists: List<Long>,
        val reloginPlaylists: List<Long>,
        val affected: List<Long>,
        val carriedLocks: Boolean,
        /** Credential refs replaced by this restore: deleted after the commit. */
        val oldRefs: List<String>,
        /** C-S8: uids of the backup's playlists that carried a group or channel lock, and the locked groups among them. */
        val lockedUids: Set<String> = emptySet(),
        val lockedGroups: Int = 0,
    )

    fun scan(file: File): Scan {
        FileInputStream(file).use { input ->
            val r = BackupReader(input)
            val header = r.readHeader()
            val playlists = ArrayList<BackupRecord.Playlist>()
            val settings = LinkedHashMap<String, JsonElement>()
            var secrets: BackupRecord.Secrets? = null
            var n = 0
            while (true) {
                val rec = r.next() ?: break
                n++
                when (rec) {
                    is BackupRecord.Playlist -> playlists += rec
                    is BackupRecord.Settings -> settings[rec.domain] = rec.json
                    is BackupRecord.Secrets -> if (secrets == null) secrets = rec
                    else -> Unit
                }
            }
            return Scan(header, r.headerBytes, playlists, settings, secrets, n)
        }
    }

    suspend fun preview(file: File): RestorePreview {
        val scan = scan(file)
        val matches = match(scan, null)
        val problems = ArrayList<String>()
        if (scan.header.includesLogins && scan.secrets == null) problems += "logins missing"
        return RestorePreview(scan.header, matches, needsPassphrase = scan.header.includesLogins && scan.secrets != null, problems = problems)
    }

    /** Decrypts the logins; throws [BackupCryptoException] (WRONG_PASSPHRASE / CORRUPT_SECRETS). */
    fun openSecrets(scan: Scan, passphrase: CharArray, isActive: () -> Boolean = { true }): Map<String, PlaylistSecret> {
        val s = scan.secrets ?: return emptyMap()
        val plain = BackupCrypto.open(s, passphrase, scan.headerBytes, isActive)
        try {
            return BackupJson.decodeFromString(BackupExporter.SECRETS_SERIALIZER, plain.decodeToString())
        } catch (e: SerializationException) {
            throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "secrets do not parse")
        } catch (e: IllegalArgumentException) {
            throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "secrets do not parse")
        } finally {
            plain.fill(0)
        }
    }

    private suspend fun match(scan: Scan, secrets: Map<String, PlaylistSecret>?): List<PlaylistMatch> {
        val local = db.playlistDao().all().map { p ->
            val s = if (secrets != null) (creds.get(p.credentialRef) as? Outcome.Ok)?.value else null
            MergePlanner.LocalPlaylist(p.id, p.uid, p.type.name, p.displaySource, s?.urls, s?.username)
        }
        val backup = scan.playlists.map { b ->
            val s = secrets?.get(BackupExporter.secretRef(b.uid))
            MergePlanner.BackupPlaylist(b.uid, b.name, b.type, b.displaySource, s?.urls, s?.username)
        }
        return MergePlanner.match(backup, local)
    }

    suspend fun restore(file: File, passphrase: CharArray?, mode: RestoreMode): Pair<RestoreResult, Applied?> {
        val scan = try {
            scan(file)
        } catch (e: BackupFormatException) {
            return RestoreResult.Failed(e.failure) to null
        } catch (e: IOException) {
            return RestoreResult.Failed(RestoreFailure.IO) to null
        }
        val secrets: Map<String, PlaylistSecret>? = if (scan.secrets != null && passphrase != null && passphrase.isNotEmpty()) {
            try {
                // C-L9: a cancelled restore stops the key derivation.
                val ctx = kotlin.coroutines.coroutineContext
                openSecrets(scan, passphrase) { ctx[kotlinx.coroutines.Job]?.isActive != false }
            } catch (e: BackupCryptoException) {
                return RestoreResult.Failed(e.failure) to null
            }
        } else {
            null
        }
        val matches = match(scan, secrets)

        // Secrets first, each under a fresh ref (deleted again if the transaction fails).
        val written = ArrayList<String>()
        val refFor = HashMap<String, String>() // backup secretRef → new local ref
        try {
            for ((ref, secret) in secrets.orEmpty()) {
                val newRef = creds.newRef()
                when (val r = creds.put(newRef, secret)) {
                    is Outcome.Ok -> { written += newRef; refFor[ref] = newRef }
                    is Outcome.Err -> BoxLog.w(TAG, "a login could not be stored (${r.error.code}); that playlist asks for it again")
                }
            }
            val applied = importMutex.withLock {
                db.writeTransaction { apply(this, file, scan, matches, refFor, mode) }
            }
            // D-S4 (§3.10.4 step 5): the settings documents go live only now that the transaction committed.
            store.commitImport(committed = true)
            // Old refs of playlists that got a new login go once the transaction committed.
            for (r in applied.oldRefs) creds.delete(r)
            return applied.result to applied
        } catch (e: Exception) {
            store.commitImport(committed = false) // D-S4: rolled back, so nothing of the backup reaches memory
            if (e is kotlinx.coroutines.CancellationException) {
                for (r in written) creds.delete(r)
                throw e
            }
            BoxLog.e(TAG, "restore failed; nothing kept", e)
            for (r in written) creds.delete(r)
            val failure = when (e) {
                is BackupFormatException -> e.failure
                else -> RestoreFailure.IO
            }
            return RestoreResult.Failed(failure) to null
        }
    }

    private suspend fun apply(
        conn: PooledConnection,
        file: File,
        scan: Scan,
        matches: List<PlaylistMatch>,
        refFor: Map<String, String>,
        mode: RestoreMode,
    ): Applied {
        val now = nowMs()
        val idOf = HashMap<String, Long>() // backup uid → local id
        val newIds = ArrayList<Long>()
        val relogin = ArrayList<Long>()
        val oldRefs = ArrayList<String>()
        var added = 0
        var updated = 0
        var needsLogin = 0
        var logins = 0
        val byUid = scan.playlists.associateBy { it.uid }

        for (m in matches) {
            val b = byUid.getValue(m.uid)
            val newRef = refFor[BackupExporter.secretRef(b.uid)]
            val type = PlaylistType.entries.firstOrNull { it.name == b.type } ?: PlaylistType.M3U_URL
            if (m.existingId == null) {
                val ref = newRef ?: creds.newRef()
                conn.exec(
                    "INSERT INTO playlist(name, type, credential_ref, display_source, sort_index, enabled, needs_reauth, uid, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ) {
                    bindText(1, b.name); bindText(2, type.name); bindText(3, ref); bindText(4, b.displaySource); bindLong(5, b.sortIndex.toLong())
                    bindBool(6, b.enabled); bindBool(7, newRef == null); bindText(8, b.uid); bindLong(9, now)
                }
                val id = conn.queryLong("SELECT last_insert_rowid()")
                idOf[b.uid] = id
                newIds += id
                added++
                if (newRef == null) needsLogin++ else logins++
            } else {
                val id = m.existingId!!
                idOf[b.uid] = id
                updated++
                // Adopt the backup's uid so the next restore of this file matches by uid (idempotent, §3.10.4).
                val taken = conn.queryLong("SELECT COUNT(*) FROM playlist WHERE uid = ? AND id <> ?") { bindText(1, b.uid); bindLong(2, id) } > 0
                conn.exec("UPDATE playlist SET name = ?, enabled = ?, sort_index = ?" + (if (taken) "" else ", uid = ?") + " WHERE id = ?") {
                    bindText(1, b.name); bindBool(2, b.enabled); bindLong(3, b.sortIndex.toLong())
                    if (taken) bindLong(4, id) else { bindText(4, b.uid); bindLong(5, id) }
                }
                if (newRef != null) {
                    conn.usePrepared("SELECT credential_ref FROM playlist WHERE id = ?") { st -> st.bindLong(1, id); if (st.step()) oldRefs += st.getText(0) }
                    conn.exec("UPDATE playlist SET credential_ref = ?, needs_reauth = 0 WHERE id = ?") { bindText(1, newRef); bindLong(2, id) }
                    relogin += id
                    logins++
                }
            }
            applyPlaylistColumns(conn, idOf.getValue(b.uid), b.settings)
        }
        val affected = idOf.values.distinct()

        if (mode == RestoreMode.REPLACE) clearUserRows(conn, affected)

        // Pass 3: every keyed record, streamed again.
        var rows = 0
        var carriedLocks = false
        val lockedUids = HashSet<String>()
        var lockedGroups = 0
        val listIdOf = HashMap<String, Long>()
        val problems = HashSet<String>()
        FileInputStream(file).use { input ->
            val r = BackupReader(input)
            r.readHeader()
            while (true) {
                val rec = r.next() ?: break
                when (rec) {
                    is BackupRecord.GroupPref -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        conn.exec(
                            "INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, custom_sort_index, custom_name, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                                "ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET hidden = excluded.hidden, locked = excluded.locked, " +
                                "custom_sort_index = excluded.custom_sort_index, custom_name = excluded.custom_name, updated_at = excluded.updated_at",
                        ) {
                            bindLong(1, pid); bindLong(2, rec.kind.toLong()); bindText(3, rec.groupKey); bindBool(4, rec.hidden)
                            if (rec.locked == null) bindNull(5) else bindBool(5, rec.locked!!)
                            bindIntOrNull(6, rec.sortIndex); bindTextOrNull(7, rec.customName); bindLong(8, now)
                        }
                        if (rec.locked == true) { carriedLocks = true; lockedUids += rec.playlistUid; lockedGroups++ }
                        rows++
                    }
                    is BackupRecord.ChannelPref -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        val fields = rec.fields.filterKeys { it in MergePlanner.CHANNEL_PREF_COLUMNS }
                        conn.exec("INSERT OR IGNORE INTO channel_prefs(playlist_id, stable_key, updated_at) VALUES (?, ?, ?)") {
                            bindLong(1, pid); bindText(2, rec.stableKey); bindLong(3, now)
                        }
                        if (fields.isNotEmpty()) {
                            val names = fields.keys.toList()
                            conn.exec("UPDATE channel_prefs SET ${names.joinToString { "$it = ?" }}, updated_at = ? WHERE playlist_id = ? AND stable_key = ?") {
                                names.forEachIndexed { i, n -> bindJson(this, i + 1, fields.getValue(n)) }
                                bindLong(names.size + 1, now); bindLong(names.size + 2, pid); bindText(names.size + 3, rec.stableKey)
                            }
                        }
                        if ((fields["locked"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.longOrNull == 1L) } == true) { carriedLocks = true; lockedUids += rec.playlistUid }
                        rows++
                    }
                    is BackupRecord.FavouriteList -> {
                        val existing = conn.usePrepared("SELECT id FROM favourite_list WHERE name = ? ORDER BY id LIMIT 1") { st -> st.bindText(1, rec.name); if (st.step()) st.getLong(0) else null }
                        val id = existing ?: run {
                            conn.exec("INSERT INTO favourite_list(name, sort_index, created_at) VALUES (?, ?, ?)") { bindText(1, rec.name); bindLong(2, rec.sortIndex.toLong()); bindLong(3, now) }
                            conn.queryLong("SELECT last_insert_rowid()")
                        }
                        if (existing != null) conn.exec("UPDATE favourite_list SET sort_index = ? WHERE id = ?") { bindLong(1, rec.sortIndex.toLong()); bindLong(2, id) }
                        listIdOf[rec.key] = id
                        rows++
                    }
                    is BackupRecord.Favourite -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        val list = listIdOf[rec.listKey] ?: continue
                        conn.exec(
                            "INSERT INTO favourite(list_id, playlist_id, stable_key, sort_index, added_at) VALUES (?, ?, ?, ?, ?) " +
                                "ON CONFLICT(list_id, playlist_id, stable_key) DO UPDATE SET sort_index = excluded.sort_index",
                        ) { bindLong(1, list); bindLong(2, pid); bindText(3, rec.stableKey); bindLong(4, rec.sortIndex.toLong()); bindLong(5, rec.addedAtMs) }
                        rows++
                    }
                    is BackupRecord.Reminder -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        if (!MergePlanner.reminderTravels(0, rec.startMin, now)) continue
                        conn.exec(
                            "INSERT OR IGNORE INTO reminder(playlist_id, stable_key, start_min, end_min, title_hash, title, channel_name, lead_sec, state, created_at, shift_min) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                        ) {
                            bindLong(1, pid); bindText(2, rec.stableKey); bindLong(3, rec.startMin.toLong()); bindLong(4, rec.endMin.toLong()); bindLong(5, rec.titleHash)
                            bindText(6, rec.title); bindText(7, rec.channelName); bindLong(8, rec.leadSec.toLong()); bindLong(9, now); bindLong(10, rec.shiftMin.toLong())
                        }
                        rows++
                    }
                    is BackupRecord.EpgSource -> {
                        // Playlist-derived sources: only the user overrides, on the source the playlist already has (§3.10.2).
                        val pid = rec.playlistUid?.let { idOf[it] }
                        if (rec.origin != EpgSourceEntity.ORIGIN_USER && pid != null) {
                            conn.exec("UPDATE epg_source SET enabled = ?, priority = ?, shift_min = ?, assume_zone = ? WHERE playlist_id = ? AND origin = ? AND ordinal = ?") {
                                bindBool(1, rec.enabled); bindLong(2, rec.priority.toLong()); bindLong(3, rec.shiftMin.toLong()); bindTextOrNull(4, rec.assumeZone)
                                bindLong(5, pid); bindText(6, rec.origin); bindLong(7, rec.ordinal.toLong())
                            }
                            rows++
                        } else if (rec.origin == EpgSourceEntity.ORIGIN_USER) {
                            rows += restoreUserSource(conn, rec, refFor, now)
                        }
                    }
                    is BackupRecord.EpgMapping -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        val sourceId: Long? = rec.sourceKey?.let { key -> resolveSource(conn, key, idOf) ?: return@let -1L }
                        if (sourceId == -1L) continue // the source is not on this TV (yet)
                        conn.exec(
                            "INSERT INTO epg_mapping(playlist_id, stable_key, source_id, xmltv_id, created_at) VALUES (?, ?, ?, ?, ?) " +
                                "ON CONFLICT(playlist_id, stable_key) DO UPDATE SET source_id = excluded.source_id, xmltv_id = excluded.xmltv_id",
                        ) { bindLong(1, pid); bindText(2, rec.stableKey); if (sourceId == null) bindNull(3) else bindLong(3, sourceId); bindTextOrNull(4, rec.xmltvId); bindLong(5, now) }
                        rows++
                    }
                    is BackupRecord.HideRule -> {
                        val pid = idOf[rec.playlistUid] ?: run { problems += rec.playlistUid; null } ?: continue
                        val same = conn.queryLong("SELECT COUNT(*) FROM hide_rule WHERE playlist_id = ? AND target = ? AND pattern = ? AND is_regex = ?") {
                            bindLong(1, pid); bindLong(2, rec.target.toLong()); bindText(3, rec.pattern); bindBool(4, rec.isRegex)
                        }
                        if (same == 0L) {
                            conn.exec("INSERT INTO hide_rule(playlist_id, target, pattern, is_regex, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?)") {
                                bindLong(1, pid); bindLong(2, rec.target.toLong()); bindText(3, rec.pattern); bindBool(4, rec.isRegex); bindBool(5, rec.enabled); bindLong(6, now)
                            }
                            conn.exec("UPDATE playlist SET hide_rules_ver = hide_rules_ver + 1 WHERE id = ?") { bindLong(1, pid) }
                        }
                        rows++
                    }
                    is BackupRecord.Playlist, is BackupRecord.Settings, is BackupRecord.Secrets, is BackupRecord.End -> Unit
                }
            }
        }
        if (problems.isNotEmpty()) BoxLog.w(TAG, "${problems.size} unknown playlist uid(s) in keyed records; skipped")

        // Settings last, inside this transaction (§3.10.4; the store cancels pending writes of the domains it replaces).
        store.importInTransaction(scan.settings, replace = mode == RestoreMode.REPLACE)

        return Applied(RestoreResult.Done(added, updated, rows, logins, needsLogin), newIds, relogin, affected, carriedLocks, oldRefs, lockedUids, lockedGroups)
    }

    private suspend fun clearUserRows(conn: PooledConnection, playlistIds: List<Long>) {
        val user = MergePlanner.CHANNEL_PREF_COLUMNS.filter { it != "hidden" && it != "watch_count" }
        for (pid in playlistIds) {
            conn.exec("DELETE FROM group_prefs WHERE playlist_id = ?") { bindLong(1, pid) }
            // Learned columns (playback_flags, fps_milli) stay: only the user's choices are replaced.
            conn.exec("UPDATE channel_prefs SET hidden = 0, watch_count = 0, ${user.joinToString { "$it = NULL" }} WHERE playlist_id = ?") { bindLong(1, pid) }
            conn.exec("DELETE FROM reminder WHERE playlist_id = ? AND state = 0") { bindLong(1, pid) }
            conn.exec("DELETE FROM epg_mapping WHERE playlist_id = ?") { bindLong(1, pid) }
            conn.exec("DELETE FROM hide_rule WHERE playlist_id = ?") { bindLong(1, pid) }
        }
        conn.exec("DELETE FROM favourite")
        conn.exec("DELETE FROM favourite_list")
    }

    private suspend fun applyPlaylistColumns(conn: PooledConnection, id: Long, settings: Map<String, JsonElement>) {
        val fields = settings.filterKeys { it in MergePlanner.PLAYLIST_COLUMNS }.filterValues { it is JsonPrimitive }
        if (fields.isEmpty()) return
        val names = fields.keys.toList()
        conn.exec("UPDATE playlist SET ${names.joinToString { "$it = ?" }} WHERE id = ?") {
            names.forEachIndexed { i, n -> bindJson(this, i + 1, fields.getValue(n)) }
            bindLong(names.size + 1, id)
        }
    }

    private suspend fun resolveSource(conn: PooledConnection, key: String, idOf: Map<String, Long>): Long? {
        val k = MergePlanner.parseSourceKey(key) ?: return null
        return if (k.origin == EpgSourceEntity.ORIGIN_USER) {
            conn.usePrepared("SELECT id FROM epg_source WHERE origin = 'USER' AND url_hash = ? LIMIT 1") { st -> st.bindLong(1, k.urlHash); if (st.step()) st.getLong(0) else null }
        } else {
            val pid = k.playlistUid?.let { idOf[it] } ?: return null
            conn.usePrepared("SELECT id FROM epg_source WHERE playlist_id = ? AND origin = ? AND ordinal = ?") { st ->
                st.bindLong(1, pid); st.bindText(2, k.origin); st.bindLong(3, k.ordinal.toLong()); if (st.step()) st.getLong(0) else null
            }
        }
    }

    /** A USER guide source needs its URL (a login): restored only when the logins were opened. */
    private suspend fun restoreUserSource(conn: PooledConnection, rec: BackupRecord.EpgSource, refFor: Map<String, String>, now: Long): Int {
        val secretRef = rec.secretRef ?: return 0
        val ref = refFor[secretRef] ?: return 0
        val hash = secretRef.removePrefix("epg:").toLongOrNull() ?: return 0
        val exists = conn.queryLong("SELECT COUNT(*) FROM epg_source WHERE origin = 'USER' AND url_hash = ?") { bindLong(1, hash) } > 0
        if (exists) {
            conn.exec("UPDATE epg_source SET enabled = ?, priority = ?, shift_min = ?, assume_zone = ? WHERE origin = 'USER' AND url_hash = ?") {
                bindBool(1, rec.enabled); bindLong(2, rec.priority.toLong()); bindLong(3, rec.shiftMin.toLong()); bindTextOrNull(4, rec.assumeZone); bindLong(5, hash)
            }
        } else {
            conn.exec(
                "INSERT INTO epg_source(name, origin, playlist_id, ordinal, credential_ref, url_hash, display_source, priority, enabled, shift_min, assume_zone, created_at) " +
                    "VALUES (?, 'USER', NULL, 0, ?, ?, ?, ?, ?, ?, ?, ?)",
            ) {
                bindText(1, rec.name); bindText(2, ref); bindLong(3, hash); bindText(4, rec.name); bindLong(5, rec.priority.toLong())
                bindBool(6, rec.enabled); bindLong(7, rec.shiftMin.toLong()); bindTextOrNull(8, rec.assumeZone); bindLong(9, now)
            }
        }
        return 1
    }

    private fun bindJson(st: androidx.sqlite.SQLiteStatement, i: Int, v: JsonElement) {
        val p = v as? JsonPrimitive
        when {
            p == null || p is kotlinx.serialization.json.JsonNull -> st.bindNull(i)
            p.isString -> st.bindText(i, p.content)
            p.booleanOrNull != null -> st.bindLong(i, if (p.booleanOrNull == true) 1 else 0)
            p.longOrNull != null -> st.bindLong(i, p.longOrNull!!)
            p.doubleOrNull != null -> st.bindDouble(i, p.doubleOrNull!!)
            else -> st.bindText(i, p.content)
        }
    }

    private companion object { const val TAG = "BackupRestorer" }
}
