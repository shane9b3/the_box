// WP-S1 (docs/design/m6-storage-locations.md §7.2, U-S4). WP-S4 (manual entry) and WP-S6 (the phone page's mirror in
// app.js, same vectors: ManualAddressParserTest.VECTORS) compile and test against this shape.
package app.thebox.core.storage

/**
 * "Computer name or address" (§7.2, U-S4). Accepted forms: `nas`, `nas.local`, `192.168.1.20`, `192.168.1.20:4445`,
 * `\\NAS\TV\Rewind`, `smb://nas/TV/Rewind`. [Parsed.path] is `/`-separated, without leading or trailing separators;
 * [Parsed.share] null = host only (land on `ComputerShares`).
 *
 * Rules (mirrored in `data/src/main/assets/phone/app.js`):
 * 1. Trim; empty → [Reason.EMPTY].
 * 2. A `scheme://` prefix: `smb://` (any case) is stripped and the rest is percent-decoded; any other scheme →
 *    [Reason.UNSUPPORTED_SCHEME].
 * 3. Leading `\\` or `//` are stripped. The rest splits on `\` and `/`; empty parts are dropped.
 * 4. The first part is `host[:port]`. A port must be 1..65535 digits ([Reason.BAD_PORT]). The host is an IPv4 address or
 *    a name of letters, digits, `-`, `_` and `.` (no leading/trailing `.`, ≤ 253 chars, labels ≤ 63); `user@host`,
 *    IPv6 and anything else → [Reason.BAD_HOST].
 * 5. The second part is the share, the rest the path.
 */
object ManualAddressParser {
    sealed interface Result {
        data class Parsed(val host: String, val port: Int = StorageConstants.SMB_DEFAULT_PORT, val share: String? = null, val path: String = "") : Result
        data class Invalid(val reason: Reason) : Result
    }

    /** Why a typed address was refused (the editor shows reason + next step). */
    enum class Reason { EMPTY, BAD_HOST, BAD_PORT, UNSUPPORTED_SCHEME }

    private const val MAX_HOST_CHARS = 253
    private const val MAX_LABEL_CHARS = 63

    fun parse(input: String): Result {
        var s = input.trim()
        if (s.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(s)
        if (scheme != null) {
            if (!scheme.groupValues[1].equals("smb", ignoreCase = true)) return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
            s = percentDecode(s.substring(scheme.value.length)) ?: return Result.Invalid(Reason.BAD_HOST)
        } else if (s.startsWith("\\\\") || s.startsWith("//")) {
            s = s.substring(2)
        }
        val parts = s.split('\\', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val hostPort = parts[0]
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
        val share = parts.getOrNull(1)
        val path = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else ""
        return Result.Parsed(host = host, port = port, share = share, path = path)
    }

    private fun validHost(h: String): Boolean {
        if (h.isEmpty() || h.length > MAX_HOST_CHARS) return false
        if (h.startsWith('.') || h.endsWith('.') || h.contains("..")) return false
        if (!h.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }) return false
        val labels = h.split('.')
        if (labels.any { it.length > MAX_LABEL_CHARS }) return false
        // All-digit dotted forms must be a valid IPv4 address.
        if (labels.all { l -> l.all { it in '0'..'9' } }) {
            if (labels.size != 4) return false
            return labels.all { it.length in 1..3 && it.toInt() in 0..255 }
        }
        return true
    }

    /** `%XX` decoding of an smb:// URL (UTF-8); null on a malformed escape. */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v); i += 3
            } else {
                val b = c.toString().toByteArray(Charsets.UTF_8); out.write(b, 0, b.size); i++
            }
        }
        return out.toString("UTF-8")
    }
}
