package app.thebox.core.m3u

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParseWarning
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.WarningCode
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.buffer
import java.nio.charset.Charset

/**
 * M3U state machine (docs/design/m1-data-layer.md §3.4): header → pending entry (`#EXTINF` + aux lines)
 * → URL → emit. Streams line by line from the byte level; never holds more than one line plus one
 * pending entry, never uses a regex per line.
 */
class M3uParserImpl : M3uPlaylistParser {
    override fun parse(source: BufferedSource, options: M3uParseOptions, sink: M3uSink, cancel: CancelCheck): M3uParseSummary =
        Run(source, options, sink, cancel).execute()

    private class Run(
        private val source: BufferedSource,
        private val options: M3uParseOptions,
        private val sink: M3uSink,
        private val cancel: CancelCheck,
    ) {
        private val header = HeaderAttrs()
        private var headerSent = false
        private var urlLogo: String? = null
        private val aux = AuxDirectives(options.extgrpSticky)

        private var pending: ExtinfLexer.Result? = null
        private var pendingLine = 0L
        private var pendingBad = false
        private var dropNextUrl = false
        private var seenExtinf = false
        private var sawContent = false

        private var entries = 0
        private var live = 0
        private var radio = 0
        private var movies = 0
        private var series = 0
        private var skippedLines = 0
        private var warnings = 0

        fun execute(): M3uParseSummary {
            val forced = CharsetDecoderChain.parseCharset(options.forcedCharset)
            var bomSeen = false
            var src = source
            when {
                src.rangeEquals(0, UTF32_LE) || src.rangeEquals(0, UTF32_BE) -> throw notA(NotASourceKind.BINARY)
                src.rangeEquals(0, UTF8_BOM) -> { src.skip(3); bomSeen = true }
                src.rangeEquals(0, UTF16_LE) -> { src.skip(2); src = Utf16ToUtf8Source(src, bigEndian = false).buffer(); bomSeen = true }
                src.rangeEquals(0, UTF16_BE) -> { src.skip(2); src = Utf16ToUtf8Source(src, bigEndian = true).buffer(); bomSeen = true }
            }
            var chainForced: Charset? = forced
            if (forced != null && forced.name().uppercase().startsWith("UTF-16")) {
                // A forced UTF-16 without a BOM: transcode, then read as UTF-8.
                if (!bomSeen) src = Utf16ToUtf8Source(src, bigEndian = !forced.name().uppercase().endsWith("LE")).buffer()
                chainForced = Charsets.UTF_8
            } else if (bomSeen && forced == null) {
                chainForced = null
            }
            sniff(src)
            val hint = if (bomSeen) null else CharsetDecoderChain.parseCharset(options.charsetHint)
            val chain = CharsetDecoderChain(chainForced, hint)
            val reader = LineReader(src, options.maxLineBytes)

            while (reader.next()) {
                if (reader.skipped.isNotEmpty()) for (s in reader.skipped) onSkipped(s)
                if (reader.lineNumber % 1000 == 0L) cancel.throwIfCancelled()
                val lineNo = reader.lineNumber
                var line = chain.decode(reader.bytes, reader.length) { cs -> warn(lineNo, WarningCode.CHARSET_FALLBACK, cs.name()) }
                if (line.isNotEmpty() && (ExtinfLexer.isTrim(line[0]) || ExtinfLexer.isTrim(line[line.length - 1]))) {
                    line = line.trim { ExtinfLexer.isTrim(it) }
                    if (line.isEmpty()) continue
                }
                sawContent = true
                if (line[0] == '#') directive(line, lineNo) else url(line, lineNo)
            }
            if (reader.skipped.isNotEmpty()) for (s in reader.skipped) onSkipped(s)
            if (pending != null) {
                warn(pendingLine, WarningCode.EXTINF_WITHOUT_URL, null)
                pending = null
            }
            if (!sawContent) throw notA(NotASourceKind.EMPTY)
            sendHeader()
            return M3uParseSummary(
                entries = entries, live = live, radio = radio, movies = movies, series = series,
                skippedLines = skippedLines, warnings = warnings, charset = chain.reportedName, hadHeader = header.seen,
            )
        }

        // ---- M2: not a playlist -------------------------------------------------------------------

        private fun sniff(src: BufferedSource) {
            src.request(SNIFF_BYTES)
            val n = minOf(src.buffer.size, SNIFF_BYTES).toInt()
            if (n == 0) throw notA(NotASourceKind.EMPTY)
            val head = src.buffer.snapshot(n)
            for (i in 0 until n) if (head[i] == 0.toByte()) throw notA(NotASourceKind.BINARY)
            var i = 0
            while (i < n && isAsciiWs(head[i])) i++
            if (i == n) {
                if (src.buffer.size <= SNIFF_BYTES && !src.request(SNIFF_BYTES + 1)) throw notA(NotASourceKind.EMPTY)
                return
            }
            when (head[i].toInt().toChar()) {
                '<' -> {
                    val text = head.substring(i).utf8().lowercase()
                    val tv = findTvTag(text)
                    throw notA(if (tv) NotASourceKind.WRONG_FORMAT else NotASourceKind.HTML_PAGE)
                }
                '{', '[' -> throw notA(NotASourceKind.WRONG_FORMAT)
            }
        }

        /** `<tv` as an element (`<tv>`, `<tv `, `<tv\n`), at the start or after an `<?xml …?>` / DOCTYPE prologue. */
        private fun findTvTag(text: String): Boolean {
            var from = 0
            while (true) {
                val i = text.indexOf("<tv", from)
                if (i < 0) return false
                val after = text.getOrNull(i + 3)
                if (after == null || after == '>' || after == ' ' || after == '\n' || after == '\r' || after == '\t') {
                    // Only a prologue (`<?…?>`, `<!…>`, comments) may precede it.
                    return text.substring(0, i).let { pre -> pre.isBlank() || pre.trimStart().startsWith("<?xml") || pre.trimStart().startsWith("<!") }
                }
                from = i + 3
            }
        }

        // ---- lines ------------------------------------------------------------------------------------

        private fun directive(line: String, lineNo: Long) {
            when {
                starts(line, "#EXTINF:") || starts(line, "#EXTINF ") -> extinf(line, lineNo)
                starts(line, "#EXTM3U") -> {
                    if (!headerSent) {
                        header.add(line, 7)
                        urlLogo = header.build().urlLogo
                    }
                }
                starts(line, "#EXTGRP:") -> aux.extGrp(line.substring(8))
                starts(line, "#EXTVLCOPT:") -> if (!aux.vlcOpt(line.substring(11))) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "EXTVLCOPT")
                starts(line, "#KODIPROP:") -> if (!aux.kodiProp(line.substring(10))) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "KODIPROP")
                starts(line, "#EXTHTTP:") -> if (!aux.extHttp(line.substring(9))) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "EXTHTTP")
                starts(line, "#EXT-X-") -> if (!seenExtinf) {
                    // M24: an HLS manifest given as a playlist.
                    if (starts(line, "#EXT-X-TARGETDURATION") || starts(line, "#EXT-X-STREAM-INF") ||
                        starts(line, "#EXT-X-MEDIA-SEQUENCE") || starts(line, "#EXT-X-PLAYLIST-TYPE")
                    ) {
                        throw notA(NotASourceKind.HLS_MANIFEST)
                    }
                }
                // M29: every other '#' line is ignored.
            }
        }

        private fun extinf(line: String, lineNo: Long) {
            seenExtinf = true
            dropNextUrl = false
            if (pending != null) warn(pendingLine, WarningCode.EXTINF_WITHOUT_URL, null)
            val r = ExtinfLexer.lex(line, 8, withDuration = true)
            pending = r
            pendingLine = lineNo
            pendingBad = r.badAttribute
            if (r.badAttribute) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "quote")
        }

        private fun onSkipped(s: LineReader.SkippedLine) {
            skippedLines++
            warn(s.line, WarningCode.LINE_TOO_LONG, null)
            val head = s.head
            if (head.startsWith("#EXTINF", ignoreCase = true)) {
                // M5: the entry is lost; its URL must not become an orphan entry.
                pending = null
                aux.reset()
                dropNextUrl = true
            } else if (!head.startsWith("#")) {
                pending = null
                aux.reset()
            }
        }

        private fun url(rawLine: String, lineNo: Long) {
            if (dropNextUrl) {
                dropNextUrl = false
                skippedLines++
                aux.reset()
                return
            }
            val p = pending
            pending = null
            try {
                buildAndEmit(p, rawLine, lineNo)
            } finally {
                aux.reset()
            }
        }

        private fun buildAndEmit(p: ExtinfLexer.Result?, rawLine: String, lineNo: Long) {
            val split = PipeHeaders.splitDetailed(rawLine)
            if (split.badPairs > 0) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "pipe")
            var url = split.url
            val scheme = UrlResolution.scheme(url)
            if (scheme == null) {
                if (p == null && url.any { it == ' ' || it == '\t' }) {
                    // Free text (an error message) is not a URL.
                    skip(lineNo, WarningCode.URL_WITHOUT_EXTINF)
                    return
                }
                url = UrlResolution.resolve(options.baseUrl, url) ?: run {
                    skip(lineNo, WarningCode.RELATIVE_URL_UNRESOLVED)
                    return
                }
            } else if (scheme !in UrlResolution.ACCEPTED_SCHEMES) {
                skip(lineNo, WarningCode.UNSUPPORTED_SCHEME)
                return
            }
            val attrs: Map<String, String> = p?.attributes ?: emptyMap()
            if (attrs["type"].equals("playlist", ignoreCase = true)) {
                skip(lineNo, WarningCode.UNSUPPORTED_SCHEME) // M28
                return
            }
            if (p == null) warn(lineNo, WarningCode.URL_WITHOUT_EXTINF, null)

            val tvgName = attrs["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() }
            val name = p?.name?.takeIf { it.isNotEmpty() } ?: tvgName ?: UrlResolution.lastSegmentName(url)

            var groupTitle: String? = null
            var extra: List<String> = emptyList()
            val gt = attrs["group-title"]
            if (gt != null && gt.isNotBlank()) {
                if (gt.indexOf(';') < 0) {
                    groupTitle = gt.trim()
                } else {
                    val parts = gt.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                    groupTitle = parts.firstOrNull()
                    if (parts.size > 1) extra = parts.subList(1, parts.size).toList()
                }
            }
            if (groupTitle == null) groupTitle = aux.group

            val hb = HeaderPrecedence.Builder()
            for ((k, v) in attrs) if (k in HeaderPrecedence.ALIASES) hb.put(k, v) // lexer keys are already lower case
            aux.applyHeadersTo(hb)
            hb.putAll(split.headers)

            val shift = Attr.shiftMinutes(attrs["tvg-shift"])
            if (shift.bad) warn(lineNo, WarningCode.BAD_ATTRIBUTE, "tvg-shift")

            val number = Attr.channelNumber(attrs["tvg-chno"] ?: attrs["ch-number"] ?: attrs["channel-number"] ?: attrs["tvg-num"])
            val kind = EntryClassifier.classify(attrs["tvg-type"], attrs["radio"], url, groupTitle, name)
            val duration = p?.duration?.takeIf { it > 0 }?.toLong()

            val rest: Map<String, String> = if (attrs.isEmpty()) emptyMap() else {
                val m = LinkedHashMap<String, String>(attrs.size * 2)
                for ((k, v) in attrs) if (k !in TYPED && k !in HeaderPrecedence.ALIASES) m[k] = v
                m
            }

            val entry = ParsedChannel(
                kind = kind,
                name = name,
                url = url,
                position = entries,
                groupTitle = groupTitle,
                extraGroups = extra,
                tvgId = Attr.tvgId(attrs["tvg-id"]),
                tvgName = tvgName,
                logoUrl = UrlResolution.logo(attrs["tvg-logo"]?.trim(), urlLogo, options.baseUrl),
                number = number,
                epgShiftMinutes = shift.minutes,
                headers = hb.build(),
                catchup = Attr.catchup(attrs),
                adult = Attr.adult(attrs),
                durationSec = duration,
                kodiProps = aux.kodiProps,
                vlcOpts = aux.vlcOpts,
                attributes = rest,
            )
            sendHeader()
            entries++
            when (kind) {
                EntryKind.LIVE -> live++
                EntryKind.RADIO -> radio++
                EntryKind.MOVIE -> movies++
                EntryKind.SERIES -> series++
            }
            if (entries % 1000 == 0) cancel.throwIfCancelled()
            sink.onEntry(entry)
        }

        private fun skip(lineNo: Long, code: WarningCode) {
            skippedLines++
            warn(lineNo, code, null)
        }

        private fun sendHeader() {
            if (headerSent) return
            headerSent = true
            if (header.badAttribute) warn(1, WarningCode.BAD_ATTRIBUTE, "EXTM3U")
            sink.onHeader(header.build())
        }

        private fun warn(line: Long, code: WarningCode, detail: String?) {
            warnings++
            sink.onWarning(ParseWarning(line, code, detail))
        }

        private fun starts(line: String, prefix: String) = line.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
    }

    private companion object {
        const val SNIFF_BYTES = 1024L
        val UTF8_BOM = "efbbbf".decodeHex()
        val UTF16_LE = "fffe".decodeHex()
        val UTF16_BE = "feff".decodeHex()
        val UTF32_LE = "fffe0000".decodeHex()
        val UTF32_BE = "0000feff".decodeHex()

        /** EXTINF attributes that map 1:1 onto typed [ParsedChannel] fields; every other one stays in `attributes` (M30). */
        val TYPED = setOf(
            "tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-chno", "ch-number", "channel-number", "tvg-num",
            "tvg-shift", "catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "timeshift",
            "tvg-rec", "catchup-time",
        )

        fun notA(kind: NotASourceKind) = SourceException(SourceError.NotASource(kind))

        fun isAsciiWs(b: Byte) = b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() ||
            b == 0x0B.toByte() || b == 0x0C.toByte()
    }
}
