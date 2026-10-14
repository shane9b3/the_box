package app.thebox.core.playback.resolve

import app.thebox.core.common.Redact
import app.thebox.core.playback.CredentialExpander
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.xtream.XtreamUrls

/** Placeholder names of the M2 templates (§3.2.4). */
object Placeholders {
    const val BASE = "{{base}}"
    const val EXT = "{{ext}}"
    const val USER = "{{u}}"
    const val PASS = "{{p}}"

    /** True when [s] contains `{{u}}` or `{{p}}`. */
    fun hasCredentials(s: String): Boolean = s.contains(USER) || s.contains(PASS)
}

/**
 * `{{base}}`, `{{ext}}`, then `{{u}}`/`{{p}}` with position-aware encoding (§3.2.4).
 *
 * Every pass is a single left-to-right scan: a substituted value is appended to the output and never scanned again,
 * so a password containing `{utc}`, `{{p}}` or `}}` stays literal. Unknown `{{x}}` placeholders and placeholders whose
 * value is null are copied as written.
 */
class CredentialExpanderImpl : CredentialExpander {
    override fun expandNonSecret(template: String, base: String?, extension: String?): String {
        if (!template.contains("{{")) return template
        val normalizedBase = base?.let { XtreamUrls.normalizeBase(it) }
        val ext = extension?.trim()?.trimStart('.')
        return scan(template) { name, _ ->
            when (name) {
                Placeholders.BASE -> normalizedBase
                Placeholders.EXT -> ext
                else -> null
            }
        }
    }

    override fun expandCredentials(template: String, username: String?, password: String?): String {
        if (!template.contains("{{")) return template
        return scan(template) { name, inQuery ->
            val raw = when (name) {
                Placeholders.USER -> username
                Placeholders.PASS -> password
                else -> null
            }
            when {
                raw == null -> null
                inQuery -> XtreamUrls.query(raw)
                else -> XtreamUrls.pathSegment(raw)
            }
        }
    }

    override fun expandHeaderValue(value: String, username: String?, password: String?): String {
        if (!value.contains("{{")) return value
        return scan(value) { name, _ ->
            when (name) {
                Placeholders.USER -> username
                Placeholders.PASS -> password
                else -> null
            }
        }
    }

    /**
     * Single pass over [template]. [valueFor] gets the whole `{{name}}` token and whether it sits after the first
     * literal `?` of the template (query position); it returns the replacement, or null to keep the token as written.
     */
    private inline fun scan(template: String, valueFor: (token: String, inQuery: Boolean) -> String?): String {
        val n = template.length
        val sb = StringBuilder(n + 32)
        var inQuery = false
        var i = 0
        while (i < n) {
            val c = template[i]
            if (c == '{' && i + 1 < n && template[i + 1] == '{') {
                val close = template.indexOf("}}", i + 2)
                if (close < 0) {
                    sb.append(template, i, n)
                    break
                }
                val token = template.substring(i, close + 2)
                val v = valueFor(token, inQuery)
                sb.append(v ?: token)
                i = close + 2
                continue
            }
            if (c == '?') inQuery = true
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/**
 * Registers what a resolution exposes with [Redact] (§3.2.8, reviewed 2026-10-13 S9): the raw, path-encoded and
 * query-encoded forms of each credential, and the token components of the resolved URL. Values shorter than three
 * characters are ignored by [Redact]; those rely on the `/live/u/p/` and query patterns.
 *
 * (code review 2026-10-14, N-S5) The whole URL is no longer registered: every tune, retry and archive seek made a new
 * entry and the set grew without bound. Only the URL's userinfo and token-like query values are registered, in
 * [Redact]'s bounded transient set; query strings and Xtream paths are masked by [Redact]'s patterns anyway.
 */
object ResolveSecrets {
    /** Query parameter names whose values are secrets whatever their length (lower case). */
    private val TOKEN_KEYS = setOf(
        "token", "auth", "authorization", "key", "apikey", "api_key", "sig", "signature", "hash", "session", "sid",
        "password", "pass", "pwd", "username", "user", "wmsauthsign", "hdnts", "hdntl", "access_token", "jwt",
    )

    /** Any other query value at least this long is treated as an opaque token. */
    const val OPAQUE_TOKEN_MIN_LENGTH = 16

    fun registerCredentials(username: String?, password: String?) {
        for (v in arrayOf(username, password)) {
            if (v.isNullOrEmpty()) continue
            Redact.registerSecret(v)
            Redact.registerSecret(XtreamUrls.pathSegment(v))
            Redact.registerSecret(XtreamUrls.query(v))
        }
    }

    fun registerCredentials(credentials: StreamCredentials?) {
        if (credentials == null) return
        registerCredentials(credentials.username, credentials.password)
        for (v in credentials.secretHeaders.values) Redact.registerSecret(v)
    }

    /** The resolved URL's userinfo and token-like query values (never the URL itself). */
    fun registerUrl(url: String) {
        for (component in tokenComponents(url)) Redact.registerTransient(component)
    }

    /** What [registerUrl] registers for [url] (exposed for tests). */
    fun tokenComponents(url: String): List<String> {
        val out = ArrayList<String>(2)
        val schemeEnd = url.indexOf("://")
        if (schemeEnd >= 0) {
            val start = schemeEnd + 3
            var end = url.length
            for (i in start until url.length) if (url[i] == '/' || url[i] == '?' || url[i] == '#') { end = i; break }
            val at = url.lastIndexOf('@', end - 1)
            if (at >= start) {
                val info = url.substring(start, at)
                val colon = info.indexOf(':')
                if (colon < 0) out += info else { out += info.substring(0, colon); out += info.substring(colon + 1) }
            }
        }
        val q = url.indexOf('?')
        if (q >= 0) {
            val query = url.substring(q + 1).substringBefore('#')
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).lowercase()
                val value = pair.substring(eq + 1)
                if (name in TOKEN_KEYS || value.length >= OPAQUE_TOKEN_MIN_LENGTH) out += value
            }
        }
        return out.filter { it.isNotEmpty() }
    }
}
