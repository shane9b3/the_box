package app.thebox.player.datasource

import java.time.OffsetDateTime

/**
 * (HLS edge hand-over, stage 0 instrumentation: docs/review/2026-11-25-hls-edge-handover-design-review.md B1) A line
 * scan of one playlist body as it passes through [HlsEdgeDataSource]. It is not a parser: it reads only the tags the
 * edge analysis needs and never changes, holds or re-orders the bytes Media3 receives.
 *
 * Memory: one line at a time (capped at [MAX_LINE]; a longer line is skipped to its end) and at most [MAX_SEGMENTS]
 * per-segment entries (MSN, salted name hash, PDT). Nothing of a URI is kept but its salted file-name hash and its
 * reference class ([Ref]). Not thread-safe: one scanner per open, fed by the loader thread that reads it.
 *
 * @param baseHost the host the body was served from (the final hop, after redirects), for the absolute-URI class.
 * @param hash the session's salted hash (never logs or keeps the input).
 */
internal class HlsPlaylistScanner(private val baseHost: String?, private val hash: (String) -> String) {

    /** How a URI line or attribute refers to its target. */
    enum class Ref(val label: String) { REL("rel"), ABS_PATH("abspath"), ABS_SAME("abs-same"), ABS_OTHER("abs-other") }

    /** One segment of a media playlist: its MSN, salted file-name hash and PDT (epoch ms, or null). */
    data class Segment(val msn: Long, val nameHash: String, val pdtMs: Long?)

    /** What one body said. Every field is a number, an enum, a salted hash or a sanitized PDT text. */
    data class Summary(
        val master: Boolean,
        val variants: Int,
        val renditions: Int,
        val msn: Long?,
        val targetDurationS: Long?,
        val discontinuitySequence: Long?,
        val segments: Int,
        val discontinuities: Int,
        val durationMs: Long,
        val pdtFirst: String?,
        val pdtLast: String?,
        val lastNameHash: String?,
        val segmentRefs: Set<Ref>,
        val keyMethods: Set<String>,
        val keyRefs: Set<Ref>,
        val mapNameHashes: Set<String>,
        val endList: Boolean,
        val parts: Int,
        val linesSkipped: Int,
        val entries: List<Segment>,
    ) {
        val isMedia: Boolean get() = !master && (segments > 0 || targetDurationS != null)
        val lastMsn: Long? get() = msn?.let { if (segments > 0) it + segments - 1 else it }
    }

    private val line = ByteArray(MAX_LINE)
    private var lineLen = 0
    private var overflow = false
    private var firstLine = true

    private var master = false
    private var variants = 0
    private var renditions = 0
    private var msn: Long? = null
    private var td: Long? = null
    private var dseq: Long? = null
    private var segments = 0
    private var discontinuities = 0
    private var durationMs = 0L
    private var pdtFirst: String? = null
    private var pdtLast: String? = null
    private var lastName: String? = null
    private val segRefs = LinkedHashSet<Ref>()
    private val keyMethods = LinkedHashSet<String>()
    private val keyRefs = LinkedHashSet<Ref>()
    private val mapNames = LinkedHashSet<String>()
    private var endList = false
    private var parts = 0
    private var skipped = 0
    private val entries = ArrayDeque<Segment>()

    private var pendingExtinfMs = 0L
    private var nextPdtMs: Long? = null
    private var expectVariantUri = false

    /** Feeds [len] bytes of the body; any split across reads is fine. */
    fun feed(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            val c = b[i++]
            if (c == NL) {
                endLine()
            } else if (!overflow) {
                if (lineLen < MAX_LINE) line[lineLen++] = c else overflow = true
            }
        }
    }

    /** The summary; a final line without a newline counts. Call once, after the last [feed]. */
    fun finish(): Summary {
        if (lineLen > 0 || overflow) endLine()
        return Summary(
            master = master, variants = variants, renditions = renditions, msn = msn, targetDurationS = td,
            discontinuitySequence = dseq, segments = segments, discontinuities = discontinuities, durationMs = durationMs,
            pdtFirst = pdtFirst, pdtLast = pdtLast, lastNameHash = lastName?.let(hash),
            segmentRefs = segRefs.toSet(), keyMethods = keyMethods.toSet(), keyRefs = keyRefs.toSet(),
            mapNameHashes = mapNames.toSet(), endList = endList, parts = parts, linesSkipped = skipped, entries = entries.toList(),
        )
    }

    private fun endLine() {
        if (overflow) {
            skipped++
            overflow = false
            lineLen = 0
            firstLine = false
            return
        }
        var n = lineLen
        if (n > 0 && line[n - 1] == CR) n--
        var start = 0
        // A UTF-8 BOM on the first line.
        if (firstLine && n >= 3 && line[0] == 0xEF.toByte() && line[1] == 0xBB.toByte() && line[2] == 0xBF.toByte()) start = 3
        firstLine = false
        lineLen = 0
        while (start < n && (line[start] == SP || line[start] == TAB)) start++
        while (n > start && (line[n - 1] == SP || line[n - 1] == TAB)) n--
        if (n <= start) return
        onLine(String(line, start, n - start, Charsets.UTF_8))
    }

    private fun onLine(s: String) {
        if (s[0] != '#') {
            onUri(s)
            return
        }
        when {
            s.startsWith("#EXTINF:") -> pendingExtinfMs = parseSecondsMs(s.substring(8).substringBefore(','))
            s.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> msn = s.substring(22).trim().toLongOrNull()
            s.startsWith("#EXT-X-TARGETDURATION:") -> td = s.substring(22).trim().toLongOrNull()
            s.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> dseq = s.substring(30).trim().toLongOrNull()
            s == "#EXT-X-DISCONTINUITY" -> discontinuities++
            s.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                val raw = s.substring(25).trim()
                val clean = sanitizePdt(raw)
                if (pdtFirst == null) pdtFirst = clean
                pdtLast = clean
                nextPdtMs = parsePdtMs(raw)
            }
            s.startsWith("#EXT-X-KEY:") -> {
                keyMethods += sanitizeToken(attr(s, "METHOD") ?: "-")
                attr(s, "URI")?.let { keyRefs += refOf(it) }
            }
            s.startsWith("#EXT-X-MAP:") -> attr(s, "URI")?.let { uri ->
                mapNames += hash(fileName(uri))
                segRefs += refOf(uri)
            }
            s.startsWith("#EXT-X-STREAM-INF:") -> { master = true; expectVariantUri = true }
            s.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> master = true
            s.startsWith("#EXT-X-MEDIA:") -> { master = true; renditions++ }
            s == "#EXT-X-ENDLIST" -> endList = true
            s.startsWith("#EXT-X-PART:") -> parts++
        }
    }

    private fun onUri(uri: String) {
        if (master || expectVariantUri) {
            if (expectVariantUri) variants++
            expectVariantUri = false
            segRefs += refOf(uri)
            return
        }
        val name = fileName(uri)
        val index = segments.toLong()
        segments++
        durationMs += pendingExtinfMs
        segRefs += refOf(uri)
        lastName = name
        val pdt = nextPdtMs
        entries.addLast(Segment((msn ?: 0L) + index, hash(name), pdt))
        if (entries.size > MAX_SEGMENTS) entries.removeFirst()
        nextPdtMs = pdt?.plus(pendingExtinfMs)
        pendingExtinfMs = 0L
    }

    private fun refOf(uri: String): Ref {
        val lower = uri.lowercase(java.util.Locale.ROOT)
        val authorityStart = when {
            lower.startsWith("http://") -> 7
            lower.startsWith("https://") -> 8
            lower.startsWith("//") -> 2
            lower.startsWith("/") -> return Ref.ABS_PATH
            else -> return Ref.REL
        }
        val rest = uri.substring(authorityStart)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = hostPart(authority)
        val base = baseHost?.let(::hostPart)
        return if (base != null && host.equals(base, ignoreCase = true)) Ref.ABS_SAME else Ref.ABS_OTHER
    }

    companion object {
        const val MAX_LINE = 8_192
        const val MAX_SEGMENTS = 128
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val SP = ' '.code.toByte()
        private const val TAB = '\t'.code.toByte()

        /** `host` of `host[:port]` or `[v6]:port` (no port, no brackets). */
        fun hostPart(authority: String): String =
            if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')

        /** The last path segment of [uri], without query or fragment. */
        fun fileName(uri: String): String = uri.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')

        /** The value of attribute [name] in an attribute-list tag, quotes removed; null when absent. */
        fun attr(tag: String, name: String): String? {
            val list = tag.substringAfter(':')
            var i = 0
            while (i < list.length) {
                val eq = list.indexOf('=', i)
                if (eq < 0) return null
                val key = list.substring(i, eq).trim()
                var j = eq + 1
                val value: String
                if (j < list.length && list[j] == '"') {
                    val close = list.indexOf('"', j + 1)
                    val stop = if (close < 0) list.length else close
                    value = list.substring(j + 1, stop)
                    j = if (close < 0) list.length else close + 1
                } else {
                    val comma = list.indexOf(',', j)
                    val stop = if (comma < 0) list.length else comma
                    value = list.substring(j, stop).trim()
                    j = stop
                }
                if (key.equals(name, ignoreCase = true)) return value
                val comma = list.indexOf(',', j)
                if (comma < 0) return null
                i = comma + 1
            }
            return null
        }

        fun parseSecondsMs(s: String): Long = s.trim().toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L

        /** PDT as epoch ms, accepting `Z`, `+hh:mm` and `+hhmm` offsets; null when unparseable. */
        fun parsePdtMs(raw: String): Long? {
            val s = raw.trim()
            runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            val fixed = Regex("""([+-]\d{2})(\d{2})$""").replace(s) { "${it.groupValues[1]}:${it.groupValues[2]}" }
            return runCatching { OffsetDateTime.parse(fixed).toInstant().toEpochMilli() }.getOrNull()
        }

        /** A PDT value as printable text: date/time characters only, at most 40. */
        fun sanitizePdt(raw: String): String = raw.filter { it.isDigit() || it in "TZ:.+-" }.take(40)

        private fun sanitizeToken(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(20).ifEmpty { "-" }
    }
}
