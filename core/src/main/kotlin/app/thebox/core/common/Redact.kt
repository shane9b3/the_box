package app.thebox.core.common

/**
 * Mandatory log redaction (CLAUDE.md): user/pass/tokens/query strings.
 *
 * (code review 2026-10-14, N-S5) Registered secrets live in two bounded LRU sets, so a process that runs for weeks does
 * not grow them with every resolved URL: credentials ([registerSecret], [CREDENTIAL_CAP]) and per-URL token components
 * ([registerTransient], [TRANSIENT_CAP]). [text] reads an immutable snapshot (longest secret first, so a secret that
 * contains another is masked whole); the snapshot is rebuilt only when a new secret is added or one is evicted.
 */
object Redact {
    /** Credentials: usernames, passwords, secret header values in raw and encoded forms. Bounded by accounts in practice. */
    const val CREDENTIAL_CAP = 1_024

    /** Token components of resolved URLs (userinfo, token-like query values): the most recent ones only. */
    const val TRANSIENT_CAP = 64

    private const val MIN_LENGTH = 3

    private val lock = Any()
    private val credentials = lru(CREDENTIAL_CAP)
    private val transient = lru(TRANSIENT_CAP)
    @Volatile private var snapshot: Array<String> = emptyArray()

    /** Register every decrypted username/password/token so it is masked anywhere in a log line. */
    fun registerSecret(secret: String?) = add(credentials, secret)

    /** Register a short-lived token component (kept in the [TRANSIENT_CAP] LRU, never the credential set). */
    fun registerTransient(secret: String?) = add(transient, secret)

    /** Number of distinct registered secrets (test hook; bounded by [CREDENTIAL_CAP] + [TRANSIENT_CAP]). */
    fun secretCount(): Int = snapshot.size

    private fun add(set: LinkedHashMap<String, Unit>, secret: String?) {
        if (secret == null || secret.length < MIN_LENGTH) return
        synchronized(lock) {
            // Access order: a re-registration only refreshes the entry; nothing to rebuild.
            if (set.put(secret, Unit) != null) return
            val all = LinkedHashSet<String>(credentials.size + transient.size)
            all.addAll(credentials.keys)
            all.addAll(transient.keys)
            snapshot = all.sortedByDescending { it.length }.toTypedArray()
        }
    }

    private fun lru(cap: Int) = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > cap
    }

    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")
    private val XC_PATH = Regex("""(?i)/(live|movie|series|timeshift)/[^/\s?#]+/[^/\s?#]+/""")
    private val QUERY = Regex("""(://[^\s?#"'<>]*)\?[^\s#"'<>]*""")
    private val HEADER = Regex("""(?i)\b(authorization|cookie|x-token|token)\s*[:=]\s*[^\s,;&]+""")

    private val HOST = Regex("""(?i)^[a-z][a-z0-9+.\-]*://(?:[^/\s@?#]*@)?([^/\s?#]*)""")

    /**
     * (M4, M2 N-L9) `host[:port]` of [url] (userinfo, path and query dropped) for redacted `toString()`s; "?" when
     * [url] has no scheme.
     */
    fun host(url: String): String = HOST.find(url)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: "?"

    fun text(s: String): String {
        var r = s
        for (secret in snapshot) if (r.contains(secret)) r = r.replace(secret, "***")
        r = USERINFO.replace(r) { it.groupValues[1] + "***@" }
        r = XC_PATH.replace(r) { "/" + it.groupValues[1] + "/***/***/" }
        r = QUERY.replace(r) { it.groupValues[1] + "?***" }
        r = HEADER.replace(r) { it.groupValues[1] + "=***" }
        return r
    }
}
