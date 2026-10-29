package app.thebox.data.contracts

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamEpgListing
import kotlinx.serialization.Serializable
import java.io.File

data class XtreamConnection(
    val playlistId: Long,
    /** Login host first, then backup servers (protocols §3.4). */
    val baseUrls: List<String>,
    val username: String,
    val password: String,
    val userAgent: String?,
    val headers: Map<String, String> = emptyMap(),
    val preferredBaseIndex: Int = 0,
)

enum class XtreamAction(val wire: String?) {
    LOGIN(null),
    LIVE_CATEGORIES("get_live_categories"), LIVE_STREAMS("get_live_streams"),
    VOD_CATEGORIES("get_vod_categories"), VOD_STREAMS("get_vod_streams"), VOD_INFO("get_vod_info"),
    SERIES_CATEGORIES("get_series_categories"), SERIES("get_series"), SERIES_INFO("get_series_info"),
    SHORT_EPG("get_short_epg"), SIMPLE_DATA_TABLE("get_simple_data_table"),
}

data class XtreamLogin(val account: XtreamAccountInfo, val baseIndexUsed: Int)

/** Stub in commit 0 at data/xtream/XtreamClient.kt (@Inject constructor); WP-C owns the body. */
interface XtreamApi {
    suspend fun login(conn: XtreamConnection): Outcome<XtreamLogin>
    /** Streams the response body for [action] to [dest] (never into a String). */
    suspend fun download(conn: XtreamConnection, action: XtreamAction, params: Map<String, String>, dest: File): Outcome<FetchResult>
    /** (reviewed 2026-10-13, B2) [timeBase] parses panel-local `start`/`end` when `*_timestamp` is absent (§3.7.4). */
    suspend fun shortEpg(conn: XtreamConnection, streamId: Long, timeBase: TimeBase, limit: Int = 4): Outcome<List<XtreamEpgListing>>
    suspend fun simpleDataTable(conn: XtreamConnection, streamId: Long, timeBase: TimeBase): Outcome<List<XtreamEpgListing>>
}

enum class TimeoutProfile { API, LARGE_FILE }

data class FetchRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val etag: String? = null,
    val lastModified: String? = null,
    val profile: TimeoutProfile = TimeoutProfile.LARGE_FILE,
    val maxBytes: Long = 2L * 1024 * 1024 * 1024,
)

data class FetchResult(
    val notModified: Boolean,
    val bytes: Long,
    val etag: String?,
    val lastModified: String?,
    val contentType: String?,
    val charset: String?,
)

/** WP-D (data/net/OkHttpSourceFetcher.kt). Writes to dest.part, renames to dest on success. */
interface SourceFetcher {
    suspend fun fetch(request: FetchRequest, dest: File): Outcome<FetchResult>
}

/** Everything secret about one playlist or EPG source. Encrypted as one Tink AEAD blob. */
@Serializable
data class PlaylistSecret(
    /** M3U/XMLTV URL, or Xtream base URLs (login host first, then backups). */
    val urls: List<String>,
    val username: String? = null,
    val password: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** WP-D (data/security/TinkCredentialStore.kt). */
interface CredentialStore {
    fun newRef(): String
    suspend fun put(ref: String, secret: PlaylistSecret): Outcome<Unit>
    /**
     * Err(Credentials(KEY_INVALIDATED)) after a Keystore reset: UI asks to re-enter the password.
     * Err(Credentials(UNAVAILABLE)) on a transient Keystore failure: nothing deleted, retry later (reviewed 2026-10-13, S16).
     */
    suspend fun get(ref: String): Outcome<PlaylistSecret>
    suspend fun delete(ref: String)
}

/**
 * (M5, docs/design/m5-setup-settings.md §2.12 item 8) Opaque secrets in the same `credential` table and AEAD as
 * [CredentialStore] (AD = ref): the parental PIN verifier and the stored backup passphrase. Implemented by
 * `TinkCredentialStore`; the failure policy is the same (permanent key loss wipes every row, KEY_INVALIDATED; a
 * transient failure is UNAVAILABLE with nothing deleted; a row that does not decrypt is CORRUPT; no row is MISSING).
 */
interface SecureBlobStore {
    suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit>
    suspend fun getBlob(ref: String): Outcome<ByteArray>
    suspend fun deleteBlob(ref: String)

    /**
     * (Save-location closure fix pass 2026-10-28, Q-E2; additive) Whether a blob is stored under [ref], **without
     * decrypting it** where the store can tell (`TinkCredentialStore` reads the row only). The default decrypts through
     * [getBlob] and zeroes the copy; a transient failure (UNAVAILABLE) counts as present, as nothing was deleted.
     */
    suspend fun hasBlob(ref: String): Boolean = when (val r = getBlob(ref)) {
        is Outcome.Ok -> { r.value.fill(0); true }
        is Outcome.Err -> (r.error as? SourceError.Credentials)?.reason == CredentialFailure.UNAVAILABLE
    }

    companion object {
        /** Reserved refs (never "cred-…", so they cannot collide with playlist secrets). */
        const val REF_PARENTAL_PIN = "parental-pin"
        const val REF_BACKUP_PASSPHRASE = "backup-passphrase"
        /**
         * (Storage locations commit 0, docs/design/m6-storage-locations.md §8, §12.4) A network folder's password:
         * "smb-<locationId>" (UTF-8, zeroed after put/get). Never in backups.
         */
        const val REF_SMB_PREFIX = "smb-"
    }
}
