package app.thebox.core.setup.lan

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.XtreamForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone page's endpoints (docs/design/m5-setup-settings.md §3.5.3) and the request checks of §3.5.2, as one
 * [LanHandler]: `Host` must be exactly `{ip}:{port}` (421, DNS rebinding); every POST carries `X-TheBox: 1` and, when
 * present, a matching `Origin` (403, cross-site forms); pages behind pairing need the session cookie from the paired
 * IP (401 no session, 403 another phone, 410 ended). Uploads stream to [spoolDir] in 64 KB chunks after a free-space
 * check (2 × Content-Length). Downloads are files the TV offered in this session, served once.
 */
class PhoneApi(
    private val session: PairingSession,
    val purpose: PhonePurpose,
    /** `{ip}:{port}` of the bound server. */
    private val hostPort: String,
    /** Bytes of a page asset (`pair.html`, `app.js`, …), null when absent. */
    private val assets: (String) -> ByteArray?,
    private val spoolDir: File,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val onSubmission: (PhoneSubmission) -> Unit,
    private val uploadMaxBytes: Long = LanLimits.PHONE_UPLOAD_MAX_BYTES,
) : LanHandler {
    private class Offer(val file: File, val contentType: String)

    private val offers = ConcurrentHashMap<String, Offer>()
    @Volatile private var tv: String = TV_WAITING
    @Volatile private var message: String = ""
    private var uploadSeq = 0

    /** [PhoneLink.offerDownload]: one GET of `/api/download/{name}` by the paired phone, then it is gone. */
    fun offerDownload(name: String, file: File, contentType: String) {
        offers[safeName(name)] = Offer(file, contentType)
    }

    /** [PhoneLink.reportStatus]. */
    fun reportStatus(text: String, done: Boolean) {
        message = text
        tv = if (done) TV_DONE else TV_CHECKING
    }

    override fun handle(request: HttpRequest): HttpResponse {
        val host = request.headers["host"]
        if (host == null || host != hostPort) return text(421, "Misdirected request")
        if (request.method == "POST") {
            if (request.headers["x-thebox"] != "1") return text(403, "Forbidden")
            val origin = request.headers["origin"]
            if (origin != null && origin != "http://$hostPort") return text(403, "Forbidden")
        }
        val path = request.path
        return when {
            path == "/" && request.isGet -> asset("pair.html")
            path == "/pair.js" && request.isGet -> asset("pair.js")
            path == "/app.css" && request.isGet -> asset("app.css")
            path == "/pair" && request.method == "POST" -> pairWithCode(request)
            path.startsWith("/s/") && request.isGet -> pairWithToken(request, path.removePrefix("/s/"))
            path == "/app" && request.isGet -> gated(request, page = true) { asset("app.html") }
            path == "/app.js" && request.isGet -> gated(request) { asset("app.js") }
            path == "/api/state" && request.isGet -> gated(request) { json(200, state()) }
            path == "/api/xtream" && request.method == "POST" -> gated(request) { onlyFor(PhonePurpose.SETUP) { xtream(request) } }
            path == "/api/m3u" && request.method == "POST" -> gated(request) { onlyFor(PhonePurpose.SETUP) { m3u(request) } }
            path == "/api/link" && request.method == "POST" -> gated(request) { onlyFor(PhonePurpose.SETUP) { link(request) } }
            path == "/api/upload" && request.method == "POST" -> gated(request) { upload(request) }
            path == "/api/passphrase" && request.method == "POST" ->
                gated(request) { onlyFor(PhonePurpose.SETUP, PhonePurpose.RESTORE, PhonePurpose.BACKUP_DOWNLOAD) { passphrase(request) } }
            path.startsWith("/api/download/") && request.isGet -> gated(request) { download(path.removePrefix("/api/download/")) }
            else -> text(404, "Not found")
        }
    }

    private val HttpRequest.isGet get() = method == "GET" || method == "HEAD"

    private fun pairWithToken(request: HttpRequest, token: String): HttpResponse {
        if (session.authorize(cookieOf(request), request.remoteAddress) == PairingSession.Auth.OK) return redirect("/app", null)
        return when (val r = session.pairWithToken(token, request.remoteAddress)) {
            is PairingSession.PairResult.Paired -> redirect("/app", r.cookie)
            PairingSession.PairResult.Busy -> text(403, "Another phone is already connected")
            is PairingSession.PairResult.Stopped -> if (session.ended) text(410, "This session has ended") else text(404, "Not found")
            is PairingSession.PairResult.Wrong -> text(404, "Not found")
        }
    }

    private fun pairWithCode(request: HttpRequest): HttpResponse {
        val obj = readJson(request) ?: return json(400, error("bad json"))
        val code = (obj["code"] as? JsonPrimitive)?.contentOrNullSafe() ?: return json(400, error("missing code"))
        return when (val r = session.pairWithCode(code, request.remoteAddress)) {
            is PairingSession.PairResult.Paired -> HttpResponse(204, securityHeaders() + ("Set-Cookie" to cookieHeader(r.cookie)))
            is PairingSession.PairResult.Wrong -> json(403, buildJsonObject { put("left", r.left) })
            PairingSession.PairResult.Busy -> json(403, error("busy"))
            is PairingSession.PairResult.Stopped -> json(410, error("stopped"))
        }
    }

    private inline fun gated(request: HttpRequest, page: Boolean = false, block: () -> HttpResponse): HttpResponse =
        when (session.authorize(cookieOf(request), request.remoteAddress)) {
            PairingSession.Auth.OK -> block()
            PairingSession.Auth.NO_SESSION -> if (page) redirect("/", null) else text(401, "Pair first")
            PairingSession.Auth.OTHER_PHONE -> text(403, "Another phone is already connected")
            PairingSession.Auth.ENDED -> text(410, "This session has ended")
        }

    private inline fun onlyFor(vararg purposes: PhonePurpose, block: () -> HttpResponse): HttpResponse =
        if (purpose in purposes) block() else json(409, error("not expected"))

    private fun state(): JsonObject = buildJsonObject {
        put("purpose", purpose.name)
        put("tv", tv)
        put("message", message)
        val offer = offers.entries.firstOrNull()
        put(
            "download",
            if (offer == null) JsonNull else buildJsonObject {
                put("name", offer.key)
                put("url", "/api/download/" + offer.key)
                put("bytes", offer.value.file.length())
            },
        )
        put("uploadMaxBytes", uploadMaxBytes)
    }

    private fun xtream(request: HttpRequest): HttpResponse {
        val o = readJson(request) ?: return json(400, error("bad json"))
        val server = o.str("server")?.trim()
        val user = o.str("username")
        val pass = o.str("password")
        if (server.isNullOrEmpty() || user.isNullOrEmpty() || pass.isNullOrEmpty()) return json(400, error("missing fields"))
        Redact.registerTransient(user)
        Redact.registerTransient(pass)
        val output = o.str("output")?.uppercase()?.takeIf { it in setOf("AUTO", "TS", "HLS") } ?: "AUTO"
        return accepted(PhoneSubmission.Xtream(XtreamForm(o.str("name")?.trim()?.ifEmpty { null }, server, user, pass, output)))
    }

    private fun m3u(request: HttpRequest): HttpResponse {
        val o = readJson(request) ?: return json(400, error("bad json"))
        val url = o.str("url")?.trim()
        if (url.isNullOrEmpty()) return json(400, error("missing url"))
        Redact.registerTransient(url)
        val epg = (o["epgUrls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.ifEmpty { null } }.orEmpty()
        epg.forEach { Redact.registerTransient(it) }
        return accepted(PhoneSubmission.M3u(M3uForm(o.str("name")?.trim()?.ifEmpty { null }, url, null, epg)))
    }

    private fun link(request: HttpRequest): HttpResponse {
        val o = readJson(request) ?: return json(400, error("bad json"))
        val url = o.str("url")?.trim()
        if (url.isNullOrEmpty()) return json(400, error("missing url"))
        Redact.registerTransient(url)
        return accepted(PhoneSubmission.Link(url, o.str("name")?.trim()?.ifEmpty { null }))
    }

    private fun passphrase(request: HttpRequest): HttpResponse {
        val o = readJson(request) ?: return json(400, error("bad json"))
        val p = o.str("passphrase") ?: return json(400, error("missing passphrase"))
        if (p.isNotEmpty()) Redact.registerTransient(p)
        return accepted(PhoneSubmission.Passphrase(p.toCharArray()))
    }

    private fun upload(request: HttpRequest): HttpResponse {
        val kind = request.query["kind"]
        val allowed = when (kind) {
            "backup" -> purpose == PhonePurpose.SETUP || purpose == PhonePurpose.RESTORE
            "m3u" -> purpose == PhonePurpose.SETUP
            else -> return json(400, error("bad kind"))
        }
        if (!allowed) return json(409, error("not expected"))
        val body = request.body ?: return json(400, error("empty"))
        if (body.length > uploadMaxBytes) return json(413, error("too large"))
        spoolDir.mkdirs()
        if (freeBytes(spoolDir) < 2 * body.length) return json(507, error("no space"))
        val seq = synchronized(this) { ++uploadSeq }
        val part = File(spoolDir, "upload-$seq.part")
        val bytes = try {
            FileOutputStream(part).use { body.copyTo(it, uploadMaxBytes) }
        } catch (e: IOException) {
            part.delete()
            if (e is HttpParseException) return json(e.status, error("upload failed"))
            BoxLog.w(TAG, "upload spool failed: ${e.javaClass.simpleName}")
            return json(500, error("upload failed"))
        }
        val name = request.query["name"]?.let(::safeName)
        val ext = if (kind == "backup") ".thebox" else ".m3u"
        val done = File(spoolDir, "upload-$seq$ext")
        if (!part.renameTo(done)) {
            part.delete()
            return json(500, error("upload failed"))
        }
        val submission = if (kind == "backup") PhoneSubmission.BackupUploaded(done.path, bytes) else PhoneSubmission.M3uFileUploaded(done.path, bytes, name)
        return accepted(submission)
    }

    private fun download(name: String): HttpResponse {
        val offer = offers.remove(name) ?: return text(404, "Not found")
        if (!offer.file.isFile) return text(404, "Not found")
        return HttpResponse(
            200,
            securityHeaders() + mapOf("Content-Type" to offer.contentType, "Content-Disposition" to "attachment; filename=\"$name\""),
            file = offer.file,
        )
    }

    private fun accepted(s: PhoneSubmission): HttpResponse {
        tv = TV_CHECKING
        message = ""
        onSubmission(s)
        return json(202, JsonObject(emptyMap()))
    }

    private fun readJson(request: HttpRequest): JsonObject? {
        val body = request.body ?: return null
        if (body.length > LanLimits.HTTP_MAX_JSON_BYTES) throw HttpParseException(413, "json too large")
        val buf = ByteArrayOutputStream(body.length.toInt())
        body.copyTo(buf, LanLimits.HTTP_MAX_JSON_BYTES.toLong())
        val bytes = buf.toByteArray()
        return try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()
    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else jsonPrimitive.content

    private fun asset(name: String): HttpResponse {
        val bytes = assets(name) ?: return text(404, "Not found")
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        return HttpResponse(200, securityHeaders() + ("Content-Type" to type), bytes)
    }

    private fun redirect(location: String, cookie: String?): HttpResponse {
        val h = securityHeaders() + ("Location" to location)
        return HttpResponse(303, if (cookie != null) h + ("Set-Cookie" to cookieHeader(cookie)) else h)
    }

    private fun json(status: Int, body: JsonObject) =
        HttpResponse(status, securityHeaders() + ("Content-Type" to "application/json; charset=utf-8"), body.toString().encodeToByteArray())

    private fun text(status: Int, body: String) =
        HttpResponse(status, securityHeaders() + ("Content-Type" to "text/plain; charset=utf-8"), body.encodeToByteArray())

    private fun error(code: String) = buildJsonObject { put("error", code) }

    companion object {
        private const val TAG = "PhoneApi"
        const val COOKIE = "tb"
        const val TV_WAITING = "Waiting"
        const val TV_CHECKING = "Checking"
        const val TV_DONE = "Done"

        const val CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; " +
            "form-action 'self'; frame-ancestors 'none'"

        fun securityHeaders(): Map<String, String> = linkedMapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY",
            "Content-Security-Policy" to CSP,
        )

        fun cookieHeader(value: String) = "$COOKIE=$value; HttpOnly; SameSite=Strict; Path=/"

        fun cookieOf(request: HttpRequest): String? {
            val header = request.headers["cookie"] ?: return null
            for (part in header.split(';')) {
                val p = part.trim()
                if (p.startsWith("$COOKIE=")) return p.substring(COOKIE.length + 1)
            }
            return null
        }

        /** File names the page may see: letters, digits, `.`, `-`, `_`, at most 80 characters. */
        fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }.joinToString("").take(80).ifEmpty { "file" }
    }
}
