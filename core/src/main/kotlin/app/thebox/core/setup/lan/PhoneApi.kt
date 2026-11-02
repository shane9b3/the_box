package app.thebox.core.setup.lan

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.XtreamForm
import app.thebox.core.storage.ManualAddressParser
import app.thebox.core.storage.StorageConstants
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
    /** D-L4: false while nothing on the TV collects submissions; they are refused with 503 instead of dropped. */
    private val canAccept: () -> Boolean = { true },
    /**
     * (WP-S6, docs/design/m6-storage-locations.md §7.4) What the TV knows for a NETWORK_FOLDER page (host, share, the
     * listed shares), shown on the phone. Never a credential. Ignored for every other purpose.
     */
    private val folderContext: PhoneContext? = null,
) : LanHandler {
    private class Offer(val file: File, val contentType: String)

    private val offers = ConcurrentHashMap<String, Offer>()
    @Volatile private var tv: String = TV_WAITING
    @Volatile private var message: String = ""
    private var uploadSeq = 0

    // (WP-S6, §7.4) The NETWORK_FOLDER sign-in: one submission at a time, each answered by one [reportStatus].
    private val folderLock = Any()
    /** A `/api/network-folder` submission reached the TV and waits for its sign-in result. */
    private var folderChecking = false
    /** Failed sign-ins in this session; [LanLimits.PAIR_MAX_WRONG] of them end it. */
    @Volatile var folderFailures: Int = 0
        private set
    @Volatile private var folderOutcome: FolderOutcome? = null
    /** "Received from your phone": the masked user name or guest, never the password or the full user name. */
    @Volatile private var folderReceived: JsonObject? = null

    /** The NETWORK_FOLDER session is over: a sign-in succeeded, or too many failed. No submission is accepted any more. */
    val folderFinished: Boolean get() = folderOutcome != null
    val folderSignedIn: Boolean get() = folderOutcome == FolderOutcome.SIGNED_IN
    val folderExhausted: Boolean get() = folderOutcome == FolderOutcome.TOO_MANY

    private enum class FolderOutcome(val wire: String) { SIGNED_IN("signed_in"), TOO_MANY("too_many_sign_ins") }

    /** Test hook: sees the fixed body buffer of `/api/network-folder` (it must be all zeros once the call returns). */
    internal var bodyBufferProbe: ((ByteArray) -> Unit)? = null

    /** [PhoneLink.offerDownload]: one GET of `/api/download/{name}` by the paired phone, then it is gone. */
    fun offerDownload(name: String, file: File, contentType: String) {
        offers.put(safeName(name), Offer(file, contentType))?.let { old -> if (old.file != file) old.file.delete() }
    }

    /** D-S2: the session ends; every file still on offer is deleted (contract: "removed after download or stop"). */
    fun dropOffers() {
        for (key in offers.keys.toList()) offers.remove(key)?.file?.delete()
    }

    /**
     * [PhoneLink.reportStatus]. For NETWORK_FOLDER it is the **sign-in result** of the pending submission (WP-S6, §7.4):
     * `done = true` = signed in (the session accepts nothing more), `done = false` = the sign-in failed (one of
     * [LanLimits.PAIR_MAX_WRONG]). The TV reports no progress: the page shows "checking" by itself.
     */
    fun reportStatus(text: String, done: Boolean) {
        if (purpose == PhonePurpose.NETWORK_FOLDER) {
            reportSignIn(text, done)
            return
        }
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
            path == "/api/network-folder" && request.method == "POST" ->
                gated(request) { onlyFor(PhonePurpose.NETWORK_FOLDER) { networkFolder(request) } }
            else -> text(404, "Not found")
        }
    }

    private val HttpRequest.isGet get() = method == "GET" || method == "HEAD"

    /**
     * `GET /s/{token}`. A HEAD or a prefetch / link preview ([isPrefetch]) never pairs: 204, no cookie, the session is
     * not touched (owner finding 2026-11-01: the phone's QR scanner fetched the link before the browser opened it). A
     * GET from the paired IP without a valid cookie re-pairs ([PairingSession.pairWithToken]) and sets the new cookie.
     */
    private fun pairWithToken(request: HttpRequest, token: String): HttpResponse {
        if (request.method == "HEAD" || isPrefetch(request)) return HttpResponse(204, securityHeaders())
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
        if (purpose == PhonePurpose.NETWORK_FOLDER) {
            // §7.4 mirroring: what the TV knows and the outcome; never the password, never the full user name.
            val c = folderContext
            put(
                "folder",
                buildJsonObject {
                    put("host", c?.host)
                    // SEC-S3: the computer's name, for display only; the page sends [PhoneContext.host] back.
                    put("name", c?.name)
                    put("share", c?.share)
                    put("shares", JsonArray(c?.shares.orEmpty().take(FOLDER_SHARES_MAX).map(::JsonPrimitive)))
                },
            )
            put("received", folderReceived ?: JsonNull)
            put("failures", folderFailures)
            put("maxFailures", LanLimits.PAIR_MAX_WRONG)
            put("ended", folderOutcome?.wire)
        }
    }

    /**
     * `POST /api/network-folder` (WP-S6, §7.4): `{host, share?, path?, user?, domain?, password?, guest}`. The body is
     * read into one fixed array that is zeroed before this returns; the password goes into the submission's `CharArray`
     * only. One submission is checked at a time (409 while the TV signs in); after a success or the last allowed
     * failure the endpoint answers 410. Nothing of the body is logged.
     */
    private fun networkFolder(request: HttpRequest): HttpResponse {
        synchronized(folderLock) {
            if (folderOutcome != null) return json(410, error("ended"))
            if (folderChecking) return json(409, error("checking"))
            folderChecking = true
        }
        var handedOver = false
        try {
            val o = readJsonFixed(request) ?: return json(400, error("bad json"))
            val guest = (o["guest"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content == "true"
            val folder = splitFolder(o.str("host"), o.str("share"), o.str("path")) ?: return json(400, error("bad folder"))
            val user = if (guest) null else o.str("user")?.trim()?.ifEmpty { null }
            val domain = if (guest) null else o.str("domain")?.trim()?.ifEmpty { null }
            val password = if (guest) null else o.str("password")?.ifEmpty { null }
            if (!guest && user == null) return json(400, error("missing user"))
            if ((user?.length ?: 0) > FOLDER_USER_MAX || (domain?.length ?: 0) > FOLDER_DOMAIN_MAX ||
                (password?.length ?: 0) > FOLDER_PASSWORD_MAX
            ) {
                return json(400, error("too long"))
            }
            Redact.registerTransient(user)
            Redact.registerTransient(password)
            folderReceived = buildJsonObject {
                if (user == null) put("guest", true) else put("user", maskUser(user))
                put("host", folder.host)
            }
            val submission = PhoneSubmission.NetworkFolder(folder.host, folder.share, folder.path, user, domain, password?.toCharArray(), guest)
            val response = accepted(submission)
            handedOver = response.status == 202
            return response
        } finally {
            if (!handedOver) {
                synchronized(folderLock) {
                    folderChecking = false
                    folderReceived = null
                }
            }
        }
    }

    private fun reportSignIn(text: String, done: Boolean) = synchronized(folderLock) {
        if (folderOutcome != null) return
        message = text
        when {
            done -> {
                folderChecking = false
                folderOutcome = FolderOutcome.SIGNED_IN
                tv = TV_DONE
            }
            !folderChecking -> Unit // no submission is waiting: only the text changes
            else -> {
                folderChecking = false
                folderFailures++
                if (folderFailures >= LanLimits.PAIR_MAX_WRONG) {
                    folderOutcome = FolderOutcome.TOO_MANY
                    tv = TV_ENDED
                } else {
                    tv = TV_RETRY
                }
            }
        }
    }

    /**
     * A JSON body read into one fixed array (not a `ByteArrayOutputStream`, whose internal buffer cannot be zeroed),
     * parsed, then zeroed (§7.4). The parser's `String`s cannot be zeroed (§19 N-3).
     */
    private fun readJsonFixed(request: HttpRequest): JsonObject? {
        val body = request.body ?: return null
        if (body.length > LanLimits.HTTP_MAX_JSON_BYTES) throw HttpParseException(413, "json too large")
        val buf = ByteArray(body.length.toInt())
        bodyBufferProbe?.invoke(buf)
        try {
            val sink = FixedSink(buf)
            body.copyTo(sink, LanLimits.HTTP_MAX_JSON_BYTES.toLong())
            if (sink.pos != buf.size) return null
            return Json.parseToJsonElement(buf.decodeToString()) as? JsonObject
        } catch (e: kotlinx.serialization.SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            buf.fill(0)
        }
    }

    /** Writes into a fixed array; more bytes than it holds is an error, never a reallocation. */
    private class FixedSink(private val buf: ByteArray) : java.io.OutputStream() {
        var pos = 0
            private set

        override fun write(b: Int) {
            if (pos >= buf.size) throw HttpParseException(400, "body longer than its length")
            buf[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > buf.size - pos) throw HttpParseException(400, "body longer than its length")
            System.arraycopy(b, off, buf, pos, len)
            pos += len
        }
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
            onSent = { offer.file.delete() }, // D-S2: served once, then removed from the TV
        )
    }

    private fun accepted(s: PhoneSubmission): HttpResponse {
        if (!canAccept()) {
            // D-L4: nothing on the TV would see it; the phone is told to send it again instead of "Received".
            BoxLog.w(TAG, "submission refused: nothing on the TV is listening")
            when (s) {
                is PhoneSubmission.BackupUploaded -> File(s.path).delete()
                is PhoneSubmission.M3uFileUploaded -> File(s.path).delete()
                is PhoneSubmission.Passphrase -> s.value.fill('\u0000')
                is PhoneSubmission.NetworkFolder -> s.password?.fill('\u0000') // WP-S6: the only copy
                else -> Unit
            }
            return json(503, error("not ready"))
        }
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

    /** (WP-S6) A computer, a shared folder and a folder inside it, as [splitFolder] reads the phone page's fields. */
    data class Folder(val host: String, val share: String?, val path: String?)

    companion object {
        private const val TAG = "PhoneApi"
        const val COOKIE = "tb"
        const val TV_WAITING = "Waiting"
        const val TV_CHECKING = "Checking"
        const val TV_DONE = "Done"
        /** (WP-S6) NETWORK_FOLDER: the sign-in failed; the phone may send again. */
        const val TV_RETRY = "Retry"
        /** (WP-S6) NETWORK_FOLDER: too many failed sign-ins; the session is about to end. */
        const val TV_ENDED = "Ended"

        const val FOLDER_SHARES_MAX = 200
        const val FOLDER_HOST_MAX = 255
        const val FOLDER_SHARE_MAX = 80
        const val FOLDER_PATH_MAX = 1_024
        const val FOLDER_USER_MAX = 256
        const val FOLDER_DOMAIN_MAX = 255
        const val FOLDER_PASSWORD_MAX = 256

        /** "shawn" → "sh***" (§7.4: the state never carries the full user name). */
        fun maskUser(user: String): String = if (user.length <= 2) "***" else user.take(2) + "***"

        /**
         * The phone page's three fields to one folder (§7.4 item 2). The grammar is [ManualAddressParser]'s (its
         * `parseFields`; closure fix pass, R3: one owner), mirrored by `parseFields`/`splitFolder` in `app.js` and checked
         * with the same vectors in `PhoneApiNetworkFolderTest`. This adds only the length caps. Null when the parser
         * refuses the fields (no computer, IPv6, a `..` or a control character, a path without a share) or a part is
         * too long. [Folder.host] carries `:port` when it is not 445.
         */
        fun splitFolder(host: String?, share: String?, path: String?): Folder? {
            val p = ManualAddressParser.parseFields(host, share, path) as? ManualAddressParser.Result.Parsed ?: return null
            val h = if (p.port == StorageConstants.SMB_DEFAULT_PORT) p.host else "${p.host}:${p.port}"
            if (h.length > FOLDER_HOST_MAX) return null
            if (p.share != null && p.share.length > FOLDER_SHARE_MAX) return null
            if (p.path.length > FOLDER_PATH_MAX) return null
            return Folder(h, p.share, p.path.ifEmpty { null })
        }

        const val CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; " +
            "form-action 'self'; frame-ancestors 'none'"

        fun securityHeaders(): Map<String, String> = linkedMapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY",
            "Content-Security-Policy" to CSP,
        )

        /**
         * `SameSite=Lax` (2026-11-01, was Strict): a QR link opened from inside a web page (a Lens result, an in-app
         * browser) is a cross-site navigation, and Chromium withholds a Strict cookie on the 303 hop to `/app`. POSTs stay
         * protected by `X-TheBox` and the `Origin` check.
         */
        fun cookieHeader(value: String) = "$COOKIE=$value; HttpOnly; SameSite=Lax; Path=/"

        /**
         * A speculative fetch rather than a navigation (2026-11-01): `Sec-Purpose: prefetch|prerender`, `Purpose: prefetch`,
         * `X-Purpose: preview`, `X-Moz: prefetch`. Header names arrive lowercased ([HttpParser]); values are matched
         * case-insensitively.
         */
        fun isPrefetch(request: HttpRequest): Boolean {
            fun has(name: String, vararg words: String): Boolean {
                val v = request.headers[name]?.lowercase() ?: return false
                return words.any { it in v }
            }
            return has("sec-purpose", "prefetch", "prerender") || has("purpose", "prefetch") ||
                has("x-purpose", "preview") || has("x-moz", "prefetch")
        }

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
