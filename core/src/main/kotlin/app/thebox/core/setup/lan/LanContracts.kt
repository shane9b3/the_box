// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.5. Verbatim; changes go through the lead.
package app.thebox.core.setup.lan

import kotlinx.coroutines.flow.StateFlow

/** What the phone sent (validated by the server before it reaches the TV UI). */
sealed interface PhoneSubmission {
    // C-L14 / D-L2 (code review 2026-10-16): submissions print hosts only (a playlist URL can carry a login).
    data class Xtream(val form: app.thebox.core.setup.XtreamForm) : PhoneSubmission { override fun toString() = "Xtream($form)" }
    data class M3u(val form: app.thebox.core.setup.M3uForm) : PhoneSubmission {
        override fun toString() = "M3u(name=${form.name}, url=${form.url?.let(app.thebox.core.common.Redact::host)}, file=${form.fileUri != null}, epg=${form.epgUrls.size})"
    }
    /** Any pasted link: the TV normalises it (get.php → Xtream). */
    data class Link(val url: String, val name: String?) : PhoneSubmission { override fun toString() = "Link(name=$name, url=${app.thebox.core.common.Redact.host(url)})" } // Phase 1 flag 7 (code review 2026-10-16): a get.php link carries a login.
    /** A backup file was uploaded and spooled to [path] (app cache); passphrase follows separately. */
    data class BackupUploaded(val path: String, val bytes: Long) : PhoneSubmission
    data class Passphrase(val value: CharArray) : PhoneSubmission { override fun toString() = "Passphrase(****)" }
    /** An M3U file uploaded and spooled to [path] (for devices without a file picker). */
    data class M3uFileUploaded(val path: String, val bytes: Long, val name: String?) : PhoneSubmission
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §7.4, §12.4) `POST /api/network-folder`, accepted
     * only for `PhonePurpose.NETWORK_FOLDER`. [password] is the only copy; the receiver zeroes it once the sign-in is done.
     * Prints the host and share only: `user=<set>`, `password=****`. Not a data class: a CharArray has identity equality
     * and a generated `copy` would duplicate the password.
     */
    class NetworkFolder(
        val host: String, val share: String?, val path: String?, val user: String?, val domain: String?,
        val password: CharArray?, val guest: Boolean,
    ) : PhoneSubmission {
        override fun toString() = "NetworkFolder(host=$host, share=$share, user=${if (user == null) "null" else "<set>"}, password=****, guest=$guest)"
    }
}

/**
 * (Storage locations commit 0, §7.4) What the TV already knows when a NETWORK_FOLDER phone page starts: the host, the
 * share and the listed shares (the page's `<select>`). **Never a credential.**
 */
data class PhoneContext(val host: String?, val share: String?, val shares: List<String>)

/** (Storage locations commit 0, §7.4) `StoragePages` answers it for a browse token (WP-S4); null = nothing known. */
fun interface PhoneContextProvider { fun context(browse: Long): PhoneContext? }

enum class ServerPhase { STARTING, WAITING, PAIRED, RECEIVED, CLOSED, FAILED }

data class LanServerState(
    val phase: ServerPhase,
    /** http://192.168.1.20:47100 (typed form). Null until bound. */
    val baseUrl: String? = null,
    /** baseUrl + "/s/" + token (QR form). */
    val qrUrl: String? = null,
    val code: String? = null,
    /** IPv4 of the paired phone, shown on the TV so the user sees who connected. */
    val pairedFrom: String? = null,
    val lastRequestAtMs: Long? = null,
    val wrongCodes: Int = 0,
    val failure: LanFailure? = null,
)
enum class LanFailure { NO_LAN_ADDRESS, BIND_FAILED, TOO_MANY_WRONG_CODES, EXPIRED }

/** One server per session; started by the phone setup screen, stopped when it closes. :data implements over :core's server. */
interface PhoneLink {
    val state: StateFlow<LanServerState>
    val submissions: kotlinx.coroutines.flow.Flow<PhoneSubmission>
    /** (Storage locations commit 0, §7.4) [context] is shown on a NETWORK_FOLDER page; ignored for the other purposes. */
    fun start(purpose: app.thebox.core.settings.PhonePurpose, context: PhoneContext? = null)
    fun stop()
    /** Offers a file for one download (backup, logs) to the paired phone; removed after download or stop. */
    fun offerDownload(name: String, path: String, contentType: String)
    /** The TV's answer shown on the phone page ("Checking…", "Added 2,140 channels", or the failure card text). */
    fun reportStatus(text: String, done: Boolean)
}

/** Pure server core (`LanHttpServerImpl`), testable with FakeLanTransport. */
interface LanHttpServer {
    fun start(bind: LanBind, handler: LanHandler)
    fun stop()
}
data class LanBind(val address: String, val preferredPort: Int, val maxConnections: Int)
fun interface LanHandler { fun handle(request: HttpRequest): HttpResponse }
data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val remoteAddress: String, val body: BodySource?)
interface BodySource { val length: Long; fun copyTo(sink: java.io.OutputStream, max: Long): Long }
/**
 * [onSent] (D-S2, code review 2026-10-16; additive): runs once the whole response was written (not for HEAD, not when
 * the connection broke), e.g. to delete a file offered for a single download.
 */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray? = null, val file: java.io.File? = null, val onSent: (() -> Unit)? = null)
