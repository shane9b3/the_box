package app.thebox.core.common

import java.util.concurrent.CopyOnWriteArraySet

/** Mandatory log redaction (CLAUDE.md): user/pass/tokens/query strings. */
object Redact {
    private val secrets = CopyOnWriteArraySet<String>()

    /** Register every decrypted username/password/token so it is masked anywhere in a log line. */
    fun registerSecret(secret: String?) {
        if (secret != null && secret.length >= 3) secrets += secret
    }

    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")
    private val XC_PATH = Regex("""(?i)/(live|movie|series|timeshift)/[^/\s?#]+/[^/\s?#]+/""")
    private val QUERY = Regex("""(://[^\s?#"'<>]*)\?[^\s#"'<>]*""")
    private val HEADER = Regex("""(?i)\b(authorization|cookie|x-token|token)\s*[:=]\s*[^\s,;&]+""")

    fun text(s: String): String {
        var r = s
        for (secret in secrets) if (r.contains(secret)) r = r.replace(secret, "***")
        r = USERINFO.replace(r) { it.groupValues[1] + "***@" }
        r = XC_PATH.replace(r) { "/" + it.groupValues[1] + "/***/***/" }
        r = QUERY.replace(r) { it.groupValues[1] + "?***" }
        r = HEADER.replace(r) { it.groupValues[1] + "=***" }
        return r
    }
}
