// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.5. Verbatim; changes go through the lead.
package app.thebox.core.setup.lan

import kotlinx.coroutines.flow.StateFlow

/** What the phone sent (validated by the server before it reaches the TV UI). */
sealed interface PhoneSubmission {
    data class Xtream(val form: app.thebox.core.setup.XtreamForm) : PhoneSubmission
    data class M3u(val form: app.thebox.core.setup.M3uForm) : PhoneSubmission
    /** Any pasted link: the TV normalises it (get.php → Xtream). */
    data class Link(val url: String, val name: String?) : PhoneSubmission
    /** A backup file was uploaded and spooled to [path] (app cache); passphrase follows separately. */
    data class BackupUploaded(val path: String, val bytes: Long) : PhoneSubmission
    data class Passphrase(val value: CharArray) : PhoneSubmission { override fun toString() = "Passphrase(****)" }
    /** An M3U file uploaded and spooled to [path] (for devices without a file picker). */
    data class M3uFileUploaded(val path: String, val bytes: Long, val name: String?) : PhoneSubmission
}

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
    fun start(purpose: app.thebox.core.settings.PhonePurpose)
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
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray? = null, val file: java.io.File? = null)
