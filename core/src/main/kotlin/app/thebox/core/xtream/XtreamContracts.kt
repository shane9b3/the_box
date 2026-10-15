package app.thebox.core.xtream

import app.thebox.core.source.ParsedChannel
import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
enum class AccountStatus { ACTIVE, BANNED, DISABLED, EXPIRED, UNKNOWN }

@Serializable
data class XtreamServerInfo(
    val url: String? = null,
    val port: Int? = null,
    val httpsPort: Int? = null,
    val protocol: String? = null,
    /** Raw server_info.timezone (may be non-IANA, e.g. "UTC+3"). */
    val timezone: String? = null,
    val timestampNow: Long? = null,
    /** Raw "YYYY-MM-DD HH:MM:SS", panel-local. */
    val timeNow: String? = null,
    val xui: Boolean = false,
    val version: String? = null,
)

/** Account card (S12). Contains no credentials. Stored as xtream_account.info_json. */
@Serializable
data class XtreamAccountInfo(
    val auth: Boolean,
    val status: AccountStatus,
    val statusRaw: String? = null,
    /** null = unlimited (`exp_date` null, "0" or ""). */
    val expiresAtSec: Long? = null,
    val isTrial: Boolean = false,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val createdAtSec: Long? = null,
    val message: String? = null,
    val server: XtreamServerInfo = XtreamServerInfo(),
    /** Device epoch ms when the response arrived (clock-skew reference). */
    val measuredAtDeviceMs: Long,
)

data class XtreamCategory(val id: String, val name: String, val parentId: String?)

/** Everything the catch-up builder needs to address an Xtream stream (credentials already decrypted). */
data class XtreamStreamRef(val base: String, val username: String, val password: String, val streamId: Long, val extension: String) {
    /** (M4, M2 N-L9) Host only: never the credentials. */
    override fun toString(): String = "XtreamStreamRef(${app.thebox.core.common.Redact.host(base)}, .$extension)"
}

data class XtreamEpgListing(
    val title: String,
    val description: String?,
    val startUtcSec: Long,
    val stopUtcSec: Long,
    val nowPlaying: Boolean,
    val hasArchive: Boolean?,
)

/** Stub in commit 0 at xtream/XtreamCatalogDecoderImpl.kt; WP-C owns the body. */
interface XtreamCatalogDecoder {
    /** Throws SourceException(Auth | NotASource). */
    fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo
    fun categories(input: InputStream): List<XtreamCategory>
    /**
     * Lazily decodes get_live_streams into ParsedChannel (kind LIVE or RADIO); url is the template
     * "{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}". The sequence must be consumed while [input] is open.
     */
    fun liveChannels(input: InputStream, categories: List<XtreamCategory>): Sequence<ParsedChannel>
    fun epgListings(input: InputStream, timeBase: app.thebox.core.model.TimeBase): List<XtreamEpgListing>
}

/** (reviewed 2026-10-13, B2) Parsed "upgrade to Xtream" input (output of XtreamLoginUrl.parse). */
data class XtreamLoginInput(val base: String, val username: String, val password: String, val output: String?)
