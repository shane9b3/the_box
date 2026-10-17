package app.thebox.core.setup

import app.thebox.core.xtream.XtreamLoginUrl
import app.thebox.core.xtream.XtreamUrls

/**
 * What the user typed or pasted into Server / Playlist URL / a login field → a clean value or a reason
 * (docs/design/m5-setup-settings.md §3.4.3). Pure and table-tested (`SourceInputNormalizerTest`).
 *
 * - Invisible characters (whitespace, the NBSP family, zero-width U+200B–U+200D, U+2060, U+FEFF) are trimmed from
 *   both ends of every field. Inside a URL the zero-width ones are removed too (they are never part of a URL and
 *   arrive with copy/paste from chat apps); inside a password nothing is touched.
 * - A URL without a scheme gets `http://` (providers are plain HTTP, CLAUDE.md); only http and https are accepted.
 * - A pasted `get.php` / `player_api.php` / `xmltv.php` / stream URL is split by [XtreamLoginUrl.parse].
 * - Stalker portals (`portal.php`, `/c/`, `stalker_portal`) are refused with their own reason (product §7.3).
 */
class SourceInputNormalizerImpl : SourceInputNormalizer {

    override fun field(raw: String): String {
        var start = 0
        var end = raw.length
        while (start < end && isInvisible(raw[start])) start++
        while (end > start && isInvisible(raw[end - 1])) end--
        return raw.substring(start, end)
    }

    override fun server(raw: String): NormalizedInput {
        val s = cleanUrl(raw)
        precheck(s)?.let { return it }
        XtreamLoginUrl.parse(s)?.let { return xtream(it.base, it.username, it.password, it.output) }
        val parts = split(s) ?: return NormalizedInput.Invalid(InputProblem.NOT_A_URL)
        authorityProblem(parts)?.let { return NormalizedInput.Invalid(it) }
        return NormalizedInput.Xtream(
            server = XtreamUrls.normalizeBase(parts.scheme + "://" + lowerHost(parts.authority)),
            username = null, password = null, output = null, filledFromLink = false,
        )
    }

    override fun m3uUrl(raw: String): NormalizedInput {
        val s = cleanUrl(raw)
        precheck(s)?.let { return it }
        val parts = split(s) ?: return NormalizedInput.Invalid(InputProblem.NOT_A_URL)
        authorityProblem(parts)?.let { return NormalizedInput.Invalid(it) }
        val url = parts.scheme + "://" + lowerHost(parts.authority) + encodeSpaces(parts.rest)
        val login = XtreamLoginUrl.parse(url)?.let { xtream(it.base, it.username, it.password, it.output) }
        return NormalizedInput.M3u(url, login)
    }

    // ------------------------------------------------------------------ internals

    /** C-L10: `user:Pass@Host:8080` → `user:Pass@host:8080`; credentials in the authority keep their case. */
    private fun lowerHost(authority: String): String {
        val at = authority.lastIndexOf('@')
        return if (at < 0) authority.lowercase() else authority.substring(0, at + 1) + authority.substring(at + 1).lowercase()
    }

    private fun xtream(base: String, user: String, pass: String, output: String?) = NormalizedInput.Xtream(
        server = base, username = field(user), password = pass, output = outputOf(output), filledFromLink = true,
    )

    private fun precheck(s: String): NormalizedInput.Invalid? {
        if (s.isEmpty()) return NormalizedInput.Invalid(InputProblem.EMPTY)
        if (isStalker(s)) return NormalizedInput.Invalid(InputProblem.STALKER_PORTAL)
        val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME)
        if (scheme == null && OTHER_SCHEME.containsMatchIn(s)) return NormalizedInput.Invalid(InputProblem.UNSUPPORTED_SCHEME)
        return null
    }

    private class Parts(val scheme: String, val authority: String, val rest: String)

    /** `scheme://authority` + the rest (path, query, fragment), with `http://` added when no scheme was typed. */
    private fun split(input: String): Parts? {
        val m = SCHEME.find(input)
        val scheme = m?.groupValues?.get(1)?.lowercase() ?: "http"
        val afterScheme = if (m != null) input.substring(m.range.last + 1) else input
        var end = afterScheme.length
        for (i in afterScheme.indices) {
            val c = afterScheme[i]
            if (c == '/' || c == '?' || c == '#' || c == '\\') { end = i; break }
        }
        val authority = afterScheme.substring(0, end)
        if (authority.isEmpty()) return null
        var rest = afterScheme.substring(end).replace('\\', '/')
        // A lone trailing "/" carries no meaning for a server or a playlist path.
        if (rest == "/") rest = ""
        return Parts(scheme, authority, rest)
    }

    private fun authorityProblem(p: Parts): InputProblem? {
        val hostPort = p.authority.substringAfterLast('@')
        if (hostPort.any { it == ' ' || it == '\t' || isNbsp(it) }) return InputProblem.SPACES_IN_HOST
        val host: String
        val port: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return InputProblem.NOT_A_URL
            host = hostPort.substring(0, close + 1)
            port = hostPort.substring(close + 1).takeIf { it.isNotEmpty() }?.let { if (it.startsWith(":")) it.substring(1) else return InputProblem.NOT_A_URL }
        } else {
            val colon = hostPort.lastIndexOf(':')
            host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            port = if (colon >= 0) hostPort.substring(colon + 1) else null
        }
        if (host.isEmpty() || host.startsWith(".") || host.endsWith("..") || host.contains("..")) return InputProblem.NOT_A_URL
        if (!host.startsWith("[") && host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return InputProblem.NOT_A_URL
        if (port != null) {
            val n = port.toIntOrNull() ?: return InputProblem.NOT_A_URL
            if (port.isEmpty() || n !in 1..65_535) return InputProblem.NOT_A_URL
        }
        return null
    }

    private fun cleanUrl(raw: String): String {
        val trimmed = field(raw)
        if (trimmed.none { isZeroWidth(it) }) return trimmed
        val sb = StringBuilder(trimmed.length)
        for (c in trimmed) if (!isZeroWidth(c)) sb.append(c)
        return field(sb.toString())
    }

    private fun encodeSpaces(s: String): String =
        if (s.none { it == ' ' || isNbsp(it) }) s else buildString(s.length + 8) { for (c in s) if (c == ' ' || isNbsp(c)) append("%20") else append(c) }

    private fun isStalker(s: String): Boolean {
        val lower = s.lowercase()
        if ("stalker_portal" in lower || PORTAL_PHP.containsMatchIn(lower)) return true
        val path = lower.substringAfter("://", lower).substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path == "c" || path == "c/" || path.endsWith("/c") || path.endsWith("/c/")
    }

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
        /** `rtsp:x`, `mailto:` … without "//": refused as a scheme, but not `host:8080` (a port is all digits). */
        private val OTHER_SCHEME = Regex("^(rtsp|rtmp|rtp|udp|ftp|sftp|mms|file|content|smb):", RegexOption.IGNORE_CASE)
        private val PORTAL_PHP = Regex("(^|/)(portal|server/load|stalker_portal/server/load)\\.php")

        /** Xtream `output` from a pasted link → the `playlist.xtream_output` value (`AUTO` / `TS` / `HLS`). */
        fun outputOf(output: String?): String? = when (output?.lowercase()) {
            null, "" -> null
            "ts", "mpegts" -> "TS"
            "m3u8", "hls" -> "HLS"
            else -> null
        }

        fun isNbsp(c: Char): Boolean = c == ' ' || c == ' ' || c == ' '

        fun isZeroWidth(c: Char): Boolean = c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿'

        fun isInvisible(c: Char): Boolean = Character.isWhitespace(c) || isNbsp(c) || isZeroWidth(c) || Character.isSpaceChar(c)
    }
}
