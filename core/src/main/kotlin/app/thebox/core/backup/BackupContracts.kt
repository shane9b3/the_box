// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.8. Verbatim; changes go through the lead.
package app.thebox.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * (M5 commit 0, S15; Appendix B) PBKDF2-HMAC-SHA256 iterations of the backup key (§3.10.3), written to the `Secrets`
 * record so readers follow the file, not the constant. Set by the commit-0 microbenchmark on `TheBox_TV_API30` (x86,
 * API 30, `app_process`, 3 warm-ups then 5 runs) for the design's RFC 8018 loop over `Mac("HmacSHA256")`: 50,000 →
 * max 453–787 ms (median 404–477); 100,000 → max 750–1,085 ms, over the 1 s budget. A pure-Java HMAC-SHA256 with
 * precomputed pads measured 400,000 → max 536–582 ms; switching to it is a lead decision. Re-measured on hardware in M6.
 */
const val BACKUP_KDF_ITERATIONS = 50_000
/** Appendix B. */
const val BACKUP_PASSPHRASE_MIN = 8

/** Line 1 of every `.thebox` file. */
@Serializable
data class BackupHeader(
    val format: String = FORMAT,
    /** Major: a reader refuses a higher major with a reason; minor additions are ignored by older readers. */
    val schema: Int = SCHEMA,
    val minor: Int = 0,
    val createdAtMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val includesLogins: Boolean,
    /** Counts for the restore preview, so the TV can show what is in the file before reading it all. */
    val counts: Map<String, Int>,
) { companion object { const val FORMAT = "thebox-backup"; const val SCHEMA = 1 } }

/** Lines 2…n: one record each. `t` is the class discriminator. */
@Serializable
sealed interface BackupRecord {
    @Serializable @SerialName("settings") data class Settings(val domain: String, val json: JsonElement) : BackupRecord
    @Serializable @SerialName("playlist") data class Playlist(
        val uid: String, val name: String, val type: String, val displaySource: String, val sortIndex: Int, val enabled: Boolean,
        /** User-settable playlist columns only (§4.4); learned columns (etag, learned output, skew, probe winner) are not exported. */
        val settings: Map<String, JsonElement>,
        /** Key into the Secrets record; null when logins are not included. */
        val secretRef: String?,
    ) : BackupRecord
    /**
     * (N2) [ordinal] keys playlist-derived sources in a backup: (playlistUid, origin, ordinal). It is the guide link's
     * position in the playlist's list at export time; a refresh pairs rows with links by address, so a re-ordered list
     * moves the ordinal with the link (decision 2026-11-11; restore-side keying by address is open, review L2).
     */
    @Serializable @SerialName("epg_source") data class EpgSource(val playlistUid: String?, val name: String, val origin: String, val ordinal: Int, val priority: Int, val enabled: Boolean, val shiftMin: Int, val assumeZone: String?, val secretRef: String?) : BackupRecord
    @Serializable @SerialName("group_pref") data class GroupPref(val playlistUid: String, val kind: Int, val groupKey: String, val hidden: Boolean, val locked: Boolean?, val sortIndex: Int?, val customName: String?) : BackupRecord
    /** User columns of channel_prefs only (never playback_flags, fps_milli: M2 §2.10). */
    @Serializable @SerialName("channel_pref") data class ChannelPref(val playlistUid: String, val stableKey: String, val fields: Map<String, JsonElement>) : BackupRecord
    @Serializable @SerialName("fav_list") data class FavouriteList(val key: String, val name: String, val sortIndex: Int) : BackupRecord
    @Serializable @SerialName("fav") data class Favourite(val listKey: String, val playlistUid: String, val stableKey: String, val sortIndex: Int, val addedAtMs: Long) : BackupRecord
    /** Pending reminders whose start is in the future at export time. */
    @Serializable @SerialName("reminder") data class Reminder(val playlistUid: String, val stableKey: String, val startMin: Int, val endMin: Int, val titleHash: Long, val title: String, val channelName: String, val leadSec: Int, val shiftMin: Int) : BackupRecord
    @Serializable @SerialName("epg_mapping") data class EpgMapping(val playlistUid: String, val stableKey: String, val sourceKey: String?, val xmltvId: String?) : BackupRecord
    @Serializable @SerialName("hide_rule") data class HideRule(val playlistUid: String, val target: Int, val pattern: String, val isRegex: Boolean, val enabled: Boolean) : BackupRecord
    /** At most one; AES-256-GCM over JSON(Map<secretRef, PlaylistSecret>) with AD = the header line's bytes. */
    @Serializable @SerialName("secrets") data class Secrets(val kdf: String, val iterations: Int, val salt: String, val nonce: String, val ciphertext: String) : BackupRecord
    @Serializable @SerialName("end") data class End(val records: Int) : BackupRecord
}

enum class RestoreMode { MERGE, REPLACE }

data class RestorePreview(
    val header: BackupHeader,
    val playlists: List<PlaylistMatch>,
    val needsPassphrase: Boolean,
    val problems: List<String>,
)
data class PlaylistMatch(val uid: String, val name: String, val target: MatchTarget, val existingId: Long?)
enum class MatchTarget { SAME_UID, SAME_SOURCE, NEW }

sealed interface RestoreResult {
    data class Done(val playlistsAdded: Int, val playlistsUpdated: Int, val rows: Int, val loginsRestored: Int, val needsLogin: Int) : RestoreResult
    data class Failed(val reason: RestoreFailure) : RestoreResult
}
enum class RestoreFailure { NOT_A_BACKUP, NEWER_SCHEMA, TRUNCATED, WRONG_PASSPHRASE, CORRUPT_SECRETS, TOO_LARGE, IO }

interface BackupService {
    /** Streams the backup to [sink]. [passphrase] null = no logins. Never holds the whole file in memory. */
    suspend fun export(sink: java.io.OutputStream, passphrase: CharArray?): app.thebox.core.common.Outcome<BackupHeader>
    suspend fun preview(file: java.io.File): app.thebox.core.common.Outcome<RestorePreview>
    suspend fun restore(file: java.io.File, passphrase: CharArray?, mode: RestoreMode): RestoreResult
    /** Weekly worker body: writes to the auto-backup location, keeps AUTO_BACKUP_KEEP files. */
    suspend fun autoBackup(nowMs: Long): app.thebox.core.common.Outcome<String>
}
