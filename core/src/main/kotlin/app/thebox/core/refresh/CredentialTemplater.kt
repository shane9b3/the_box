package app.thebox.core.refresh

import app.thebox.core.identity.XtreamUrlShape
import app.thebox.core.m3u.PipeHeaders
import app.thebox.core.source.ParsedChannel

/**
 * Pass-1 Xtream credential vote (docs/design/m1-data-layer.md §3.2.4). `xtreamShaped` when ≥ 80 % of the
 * http(s) entries parse as Xtream stream URLs (long or short form) and one `(username, password)` pair
 * accounts for ≥ 95 % of the parsed ones. The majority base of that pair goes into [DetectedCredentials].
 */
internal class CredentialVote {
    private var http = 0
    private var parsed = 0
    private val pairs = HashMap<String, Int>()
    private val bases = HashMap<String, Int>()

    fun offer(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        http++
        val x = XtreamUrlShape.parse(url, allowShort = true) ?: return
        parsed++
        val pair = x.username + SEP + x.password
        val c = pairs[pair]
        if (c != null) pairs[pair] = c + 1 else if (pairs.size < MAX_TRACKED) pairs[pair] = 1
        val bk = pair + SEP + x.base
        val b = bases[bk]
        if (b != null) bases[bk] = b + 1 else if (bases.size < MAX_TRACKED) bases[bk] = 1
    }

    fun result(): DetectedCredentials? {
        if (http == 0 || parsed == 0) return null
        if (parsed.toLong() * 100 < http.toLong() * SHAPED_PERCENT) return null
        val top = pairs.maxByOrNull { it.value } ?: return null
        if (top.value.toLong() * 100 < parsed.toLong() * PAIR_PERCENT) return null
        val prefix = top.key + SEP
        val base = bases.entries.filter { it.key.startsWith(prefix) }.maxByOrNull { it.value }?.key?.substring(prefix.length) ?: return null
        val sep = top.key.indexOf(SEP)
        return DetectedCredentials(base = base, username = top.key.substring(0, sep), password = top.key.substring(sep + 1))
    }

    companion object {
        const val SHAPED_PERCENT = 80
        const val PAIR_PERCENT = 95
        private const val MAX_TRACKED = 4_096
        private const val SEP = '\u0000'
    }
}

/**
 * `{{u}}`/`{{p}}` substitution (§3.2.4; reviewed 2026-10-13, B3). Only applied for Xtream-shaped lists:
 * stream URLs that parse with the voted pair get their two credential path segments replaced (host, port,
 * path and extension stay literal). `catchup-source` and every attribute value containing the pair (as
 * consecutive path segments, or as `username=`/`password=` query values) are templated the same way.
 */
internal object CredentialTemplater {
    const val U = "{{u}}"
    const val P = "{{p}}"

    /** The stored form of [c]: templated URL, catch-up source and attributes, plus the detected Xtream id. */
    fun apply(c: ParsedChannel, cred: DetectedCredentials?): ParsedChannel {
        if (cred == null) return c
        val x = XtreamUrlShape.parse(c.url, allowShort = true)?.takeIf { it.matches(cred.username, cred.password) }
        val url = if (x != null) replaceSegments(c.url, x) else c.url
        val cs = c.catchup.source
        val catchup = if (cs != null && mayContain(cs, cred)) {
            val t = templateValue(cs, cred)
            if (t == cs) c.catchup else c.catchup.copy(source = t)
        } else c.catchup
        var attrs = c.attributes
        for ((k, v) in c.attributes) {
            if (!mayContain(v, cred)) continue
            val t = templateValue(v, cred)
            if (t != v) {
                if (attrs === c.attributes) attrs = LinkedHashMap(c.attributes)
                (attrs as LinkedHashMap)[k] = t
            }
        }
        if (x == null && catchup === c.catchup && attrs === c.attributes) return c
        return c.copy(
            url = url,
            catchup = catchup,
            attributes = attrs,
            xtreamStreamId = c.xtreamStreamId ?: x?.streamId,
            containerExtension = c.containerExtension ?: x?.extension,
        )
    }

    fun templateUrl(url: String, cred: DetectedCredentials): String {
        val x = XtreamUrlShape.parse(url, allowShort = true) ?: return url
        return if (x.matches(cred.username, cred.password)) replaceSegments(url, x) else url
    }

    private fun replaceSegments(url: String, x: XtreamUrlShape): String =
        url.substring(0, x.userStart) + U + url.substring(x.userEnd, x.passStart) + P + url.substring(x.passEnd)

    private fun mayContain(v: String, cred: DetectedCredentials): Boolean =
        v.contains(cred.password) || v.contains(cred.username) || v.indexOf('%') >= 0

    /** Generic templating for a URL-like value: consecutive `user/pass` path segments and `username=`/`password=` query values. */
    fun templateValue(value: String, cred: DetectedCredentials): String {
        val q = value.indexOf('?')
        val pathPart = if (q >= 0) value.substring(0, q) else value
        val queryPart = if (q >= 0) value.substring(q + 1) else null
        val segs = pathPart.split('/')
        var changed = false
        val out = segs.toMutableList()
        var i = 0
        while (i + 1 < segs.size) {
            if (eq(segs[i], cred.username) && eq(segs[i + 1], cred.password)) {
                out[i] = U
                out[i + 1] = P
                changed = true
                i += 2
            } else {
                i++
            }
        }
        var newQuery = queryPart
        if (queryPart != null) {
            val pairs = queryPart.split('&').toMutableList()
            for (j in pairs.indices) {
                val pr = pairs[j]
                val eqi = pr.indexOf('=')
                if (eqi <= 0) continue
                val k = pr.substring(0, eqi)
                val v = pr.substring(eqi + 1)
                if (k.equals("username", ignoreCase = true) && eq(v, cred.username)) { pairs[j] = "$k=$U"; changed = true }
                else if (k.equals("password", ignoreCase = true) && eq(v, cred.password)) { pairs[j] = "$k=$P"; changed = true }
            }
            newQuery = pairs.joinToString("&")
        }
        if (!changed) return value
        return out.joinToString("/") + (if (newQuery != null) "?$newQuery" else "")
    }

    private fun eq(raw: String, plain: String): Boolean =
        raw == plain || (raw.indexOf('%') >= 0 && PipeHeaders.percentDecode(raw) == plain)

    /** Whether [c] (already templated) still carries [cred] in clear: a test and diagnostics helper. */
    fun leaks(c: ParsedChannel, cred: DetectedCredentials): Boolean {
        fun has(s: String?) = s != null && s.contains(cred.password)
        return has(c.url) || has(c.catchup.source) || c.attributes.values.any { has(it) }
    }
}
