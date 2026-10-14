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
 * query-encoded forms of each credential, and the resolved URL itself. Values shorter than three characters are
 * ignored by [Redact]; those rely on the URL registration and the `/live/u/p/` pattern.
 */
object ResolveSecrets {
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

    /** The resolved URL, whole: it may carry tokens that are not credentials. */
    fun registerUrl(url: String) = Redact.registerSecret(url)
}
