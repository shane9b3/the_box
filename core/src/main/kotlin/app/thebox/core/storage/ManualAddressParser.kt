// WP-S1 (docs/design/m6-storage-locations.md §7.2, U-S4). The single owner of the network-folder address grammar
// (closure fix pass, lead ruling R3): the TV's "Enter a name or address" ([parse]), the phone page's three fields
// ([parseFields], used by `PhoneApi.splitFolder`) and their mirror `parse`/`parseFields` in
// `data/src/main/assets/phone/app.js` (checked with `PhoneApiNetworkFolderTest.SPLIT_VECTORS`).
package app.thebox.core.storage

/**
 * "Computer name or address" (§7.2, U-S4). Accepted forms: `nas`, `nas.local`, `192.168.1.20`, `192.168.1.20:4445`,
 * `\\NAS\TV\Rewind`, `smb://nas/TV/Rewind`. [Result.Parsed.path] is `/`-separated, without leading or trailing
 * separators; [Result.Parsed.share] null = host only (land on `ComputerShares`).
 *
 * Rules (mirrored in `data/src/main/assets/phone/app.js`):
 * 1. Trim; empty → [Reason.EMPTY].
 * 2. A `scheme://` prefix: `smb://` (any case) is stripped and the rest is percent-decoded (UTF-8); any other scheme →
 *    [Reason.UNSUPPORTED_SCHEME]; a malformed `%` escape → [Reason.BAD_HOST].
 * 3. Leading `\\` or `//` are stripped. The rest splits on `\` and `/`; the first non-blank part, trimmed, is the host.
 * 4. The first part is `host[:port]`. A port must be 1..65535 digits ([Reason.BAD_PORT]). The host is an IPv4 address or
 *    a name of letters, digits, `-`, `_` and `.` that starts and ends with a letter or digit (no `..`, ≤ 253 chars,
 *    labels ≤ 63); `user@host`, IPv6 (bracketed or not) and anything else → [Reason.BAD_HOST].
 * 5. The second part is the share, the rest the path. A `.` part after the host is dropped; a `..` part, or a part
 *    with a control character (U+0000..U+001F, U+007F, also a decoded `%00`) → [Reason.BAD_PATH] (C-N4).
 */
object ManualAddressParser {
    sealed interface Result {
        data class Parsed(val host: String, val port: Int = StorageConstants.SMB_DEFAULT_PORT, val share: String? = null, val path: String = "") : Result
        data class Invalid(val reason: Reason) : Result
    }

    /**
     * Why a typed address was refused (the editor shows reason + next step). Append-only. [BAD_PATH] (C-N4, closure
     * fix pass): a `..` folder or a control character in the share or a folder.
     */
    enum class Reason { EMPTY, BAD_HOST, BAD_PORT, UNSUPPORTED_SCHEME, BAD_PATH }

    private const val MAX_HOST_CHARS = 253
    private const val MAX_LABEL_CHARS = 63

    /** Q-E4: compiled once (the editor's validator runs [parse] on every edit). */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    fun parse(input: String): Result {
        var s = input.webTrim()
        if (s.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val scheme = SCHEME.find(s)
        if (scheme != null) {
            if (!scheme.groupValues[1].equals("smb", ignoreCase = true)) return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            s = percentDecode(s.substring(scheme.value.length)) ?: return Result.Invalid(Reason.BAD_HOST)
        } else if (s.startsWith("\\\\") || s.startsWith("//")) {
            s = s.substring(2)
        }
        val all = s.split('\\', '/')
        val first = all.indexOfFirst { it.any { c -> !c.isWebSpace() } }
        if (first < 0) return Result.Invalid(Reason.EMPTY)
        val hostPort = all[first].webTrim()
        val host: String
        var port = StorageConstants.SMB_DEFAULT_PORT
        val colon = hostPort.indexOf(':')
        if (colon >= 0) {
            if (hostPort.indexOf(':', colon + 1) >= 0) return Result.Invalid(Reason.BAD_HOST) // IPv6 is not supported
            host = hostPort.substring(0, colon)
            val p = hostPort.substring(colon + 1)
            if (p.isEmpty() || p.length > 5 || !p.all { it in '0'..'9' }) return Result.Invalid(Reason.BAD_PORT)
            port = p.toInt()
            if (port !in 1..65_535) return Result.Invalid(Reason.BAD_PORT)
        } else host = hostPort
        if (!validHost(host)) return Result.Invalid(Reason.BAD_HOST)
        val parts = folderParts(all.drop(first + 1)) ?: return Result.Invalid(Reason.BAD_PATH)
        val share = parts.firstOrNull()
        val path = parts.drop(1).joinToString("/")
        return Result.Parsed(host = host, port = port, share = share, path = path)
    }

    /**
     * The phone page's three fields (§7.4 item 2): "Computer", "Shared folder" and "Folder inside it". A whole address
     * ([isWholeAddress]) pasted into Shared folder replaces the computer and the shared folder; one typed into Computer
     * brings its share and path. A Shared folder typed as `TV\Rewind` is split; its first part replaces the computer's
     * share and the rest goes before Folder inside it. Every part follows [parse]'s rules. A folder without a shared
     * folder → [Reason.BAD_PATH].
     */
    fun parseFields(host: String?, share: String?, path: String?): Result {
        val h = host?.webTrim().orEmpty()
        val s = share?.webTrim().orEmpty()
        val whole = isWholeAddress(s)
        val base = when (val r = parse(if (whole) s else h)) {
            is Result.Parsed -> r
            is Result.Invalid -> return r
        }
        var shareParts = listOfNotNull(base.share) + base.path.split('/').filter { it.isNotEmpty() }
        if (!whole && s.isNotEmpty()) shareParts = segments(s) ?: return Result.Invalid(Reason.BAD_PATH)
        val more = segments(path) ?: return Result.Invalid(Reason.BAD_PATH)
        val sh = shareParts.firstOrNull()
        val rest = shareParts.drop(1) + more
        if (sh == null && rest.isNotEmpty()) return Result.Invalid(Reason.BAD_PATH)
        return Result.Parsed(base.host, base.port, sh, rest.joinToString("/"))
    }

    /** `\\…`, `//…` or `scheme://…` (any scheme: a wrong one is refused as [Reason.UNSUPPORTED_SCHEME]). */
    fun isWholeAddress(s: String): Boolean {
        val t = s.webTrim()
        return t.startsWith("\\\\") || t.startsWith("//") || SCHEME.containsMatchIn(t)
    }

    /** `TV\Rewind` or `/Rewind//./Box/` → its folder names ([parse] rule 5); null when one is `..` or has a control character. */
    fun segments(s: String?): List<String>? = folderParts(s.orEmpty().split('\\', '/'))

    /** Rule 5 on raw parts: control characters are checked before the trim (a trailing newline is refused, not trimmed). */
    private fun folderParts(raw: List<String>): List<String>? {
        val out = ArrayList<String>(raw.size)
        for (r in raw) {
            if (r.any { it < ' ' || it == '\u007F' }) return null
            val t = r.webTrim()
            if (t.isEmpty() || t == ".") continue
            if (t == "..") return null
            out += t
        }
        return out
    }

    private fun validHost(h: String): Boolean {
        if (h.isEmpty() || h.length > MAX_HOST_CHARS) return false
        if (!h.first().isAsciiLetterOrDigit() || !h.last().isAsciiLetterOrDigit() || h.contains("..")) return false
        if (!h.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return false
        val labels = h.split('.')
        if (labels.any { it.length > MAX_LABEL_CHARS }) return false
        // All-digit dotted forms must be a valid IPv4 address.
        if (labels.all { l -> l.all { it in '0'..'9' } }) {
            if (labels.size != 4) return false
            return labels.all { it.length in 1..3 && it.toInt() in 0..255 }
        }
        return true
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * The trim of the phone page's JavaScript (`String.prototype.trim`, `\s`), so both sides cut the same characters:
     * Kotlin's whitespace without U+001C..U+001F (control characters, refused as such), plus U+FEFF.
     */
    private fun Char.isWebSpace() = (isWhitespace() && this !in '\u001C'..'\u001F') || this == '\uFEFF'

    private fun String.webTrim() = trim { it.isWebSpace() }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * `%XX` decoding of an smb:// URL (UTF-8); null on a malformed escape (two hex digits, nothing else) or bytes that are
     * not UTF-8 (the phone's `decodeURIComponent` refuses them too).
     */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = hex(s[i + 1])
                val lo = hex(s[i + 2])
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo); i += 3
            } else {
                // A run up to the next '%' at once, so a surrogate pair stays one code point.
                val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
                val b = try {
                    Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(s, i, end))
                } catch (e: java.nio.charset.CharacterCodingException) {
                    return null // a lone surrogate (encodeURIComponent throws on it)
                }
                while (b.hasRemaining()) out.write(b.get().toInt())
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
