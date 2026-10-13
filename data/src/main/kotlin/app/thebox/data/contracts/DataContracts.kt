package app.thebox.data.contracts

import app.thebox.core.common.Outcome
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
