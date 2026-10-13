package app.thebox.core.xmltv

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.ProgrammeFlags
import org.xmlpull.v1.XmlPullParser
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Streaming XMLTV parser (docs/design/m1-data-layer.md §3.5, §4.1). Pushes channels and accepted
 * programmes to an [XmltvHandler]; never builds a DOM or a list of programmes.
 *
 * [factory] only constructs the parser (`Xml::newPullParser` on Android, `::KXmlParser` on the JVM).
 * Every feature is set here, in the §3.5.7 order, so both platforms behave the same (review S4).
 *
 * Truncation: relaxed kxml silently closes open elements at EOF. To report truncation anyway, the
 * decompressed stream is watched: once it has hit EOF without ending in `</tv>`, the parser stops
 * emitting and throws [EOFException] (the store then applies the 90 % rule, §3.5.6).
 */
class XmltvParser(private val factory: () -> XmlPullParser) {

    class Options(
        val preferredLanguages: List<String> = emptyList(),
        val storeDescriptions: Boolean = true,
        val maxDescriptionChars: Int = 2048,
        /** Offset for times without one; one resolver per source ([XmltvTime.resolverFor]). */
        val assumed: XmltvTime.OffsetResolver = XmltvTime.UTC,
    ) {
        companion object {
            val DEFAULT = Options()
        }
    }

    /** Parses [input] (already decompressed). Blocking; polls [cancel] every [CANCEL_EVERY] programmes. */
    fun parse(input: InputStream, handler: XmltvHandler, cancel: CancelCheck = CancelCheck.Never, options: Options = Options.DEFAULT) {
        val tail = TailInputStream(stripUtf8Bom(input))
        val xpp = factory()
        configure(xpp, tail)
        Run(xpp, tail, handler, cancel, options).run()
    }

    private fun configure(xpp: XmlPullParser, stream: InputStream) {
        // 1. DOCDECL off (kxml2 rejects the feature; Android needs it off for entity replacement).
        try { xpp.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) { }
        // 2. Namespaces off.
        try { xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) } catch (_: Exception) { }
        // 3. Relaxed: tolerate stray '&' and unknown entities.
        try { xpp.setFeature(FEATURE_RELAXED, true) } catch (_: Exception) { }
        // 4. Encoding from the BOM / XML declaration, then HTML entities as characters (relaxed would drop them).
        xpp.setInput(stream, null)
        for (k in ENTITY_NAMES.indices) {
            try { xpp.defineEntityReplacementText(ENTITY_NAMES[k], ENTITY_VALUES[k]) } catch (_: Exception) { }
        }
    }

    private class Run(
        val xpp: XmlPullParser,
        val tail: TailInputStream,
        val handler: XmltvHandler,
        val cancel: CancelCheck,
        val options: Options,
    ) {
        val picker = LangPicker(options.preferredLanguages)
        val p = XmltvProgramme()
        val episodes = EpisodeNum.Accumulator()
        val text = StringBuilder(256)
        val catTexts = ArrayList<String>(8)
        var catRanks = IntArray(8)
        var programmes = 0

        fun run() {
            var sawRoot = false
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev != XmlPullParser.START_TAG) continue
                val name = xpp.name
                if (!sawRoot) {
                    sawRoot = true
                    if (!name.equals("tv", ignoreCase = true)) throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
                    continue
                }
                when (name) {
                    "programme" -> {
                        readProgramme()
                        if (++programmes % CANCEL_EVERY == 0) cancel.throwIfCancelled()
                    }
                    "channel" -> readChannel()
                    else -> skip()
                }
            }
            if (!sawRoot) {
                throw SourceException(SourceError.NotASource(if (tail.total == 0L) NotASourceKind.EMPTY else NotASourceKind.WRONG_FORMAT))
            }
            if (!tail.endsWithCloseTv()) throw EOFException("XMLTV document truncated (no </tv>)")
            handler.onEnd()
        }

        fun readChannel() {
            val id = attr("id")
            if (id.isNullOrEmpty()) {
                skip()
                return
            }
            val depth = xpp.depth
            var names: ArrayList<XmltvDisplayName>? = null
            var icon: String? = null
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_TAG && xpp.depth == depth) break
                if (ev == XmlPullParser.END_DOCUMENT) throw EOFException("EOF inside <channel>")
                if (ev != XmlPullParser.START_TAG) continue
                when (xpp.name) {
                    "display-name" -> {
                        val lang = attr("lang")
                        val t = readText()
                        if (t.isNotEmpty()) (names ?: ArrayList<XmltvDisplayName>(2).also { names = it }).add(XmltvDisplayName(t, lang))
                    }
                    "icon" -> {
                        if (icon == null) icon = attr("src")?.trim()?.ifEmpty { null }
                        skip()
                    }
                    else -> skip()
                }
            }
            val list: List<XmltvDisplayName> = names?.let { picker.order(it) { n -> n.lang } } ?: emptyList()
            handler.onChannel(XmltvChannel(id, list, icon))
        }

        fun readProgramme() {
            var startAttr: String? = null
            var stopAttr: String? = null
            var channel: String? = null
            var catchupId: String? = null
            for (a in 0 until xpp.attributeCount) {
                when (xpp.getAttributeName(a)) {
                    "start" -> startAttr = xpp.getAttributeValue(a)
                    "stop" -> stopAttr = xpp.getAttributeValue(a)
                    "channel" -> channel = xpp.getAttributeValue(a)
                    "catchup-id" -> catchupId = xpp.getAttributeValue(a)
                }
            }
            if (channel == null || startAttr == null) {
                if (channel != null) handler.onBadTime(channel)
                skip()
                return
            }
            val start = XmltvTime.parseMinutes(startAttr, options.assumed)
            if (start == XmltvTime.BAD) {
                handler.onBadTime(channel)
                skip()
                return
            }
            var stop = if (stopAttr == null) XmltvTime.MISSING else XmltvTime.parseMinutes(stopAttr, options.assumed)
            if (stop == XmltvTime.BAD) stop = XmltvTime.MISSING
            if (stop != XmltvTime.MISSING && stop <= start) {
                handler.onBadTime(channel)
                skip()
                return
            }
            if (!handler.acceptProgramme(channel, start, stop)) {
                skip()
                return
            }
            p.reset()
            p.channelId = channel
            p.startMin = start
            p.stopMin = stop
            if (!catchupId.isNullOrEmpty()) {
                p.catchupId = catchupId
                p.flags = p.flags or ProgrammeFlags.HAS_CATCHUP_ID
            }
            readProgrammeChildren()
            if (tail.eof && !tail.endsWithCloseTv()) throw EOFException("XMLTV document truncated")
            handler.onProgramme(p)
        }

        private fun readProgrammeChildren() {
            val depth = xpp.depth
            var titleRank = -1
            var subRank = -1
            var descRank = -1
            catTexts.clear()
            episodes.reset()
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_TAG && xpp.depth == depth) break
                if (ev == XmlPullParser.END_DOCUMENT) throw EOFException("EOF inside <programme>")
                if (ev != XmlPullParser.START_TAG) continue
                when (xpp.name) {
                    "title" -> {
                        val r = picker.rank(attr("lang"))
                        if (titleRank == -1 || r < titleRank) {
                            p.title = readText()
                            titleRank = r
                        } else {
                            skip()
                        }
                    }
                    "sub-title" -> {
                        val r = picker.rank(attr("lang"))
                        if (subRank == -1 || r < subRank) {
                            p.subtitle = readText().ifEmpty { null }
                            subRank = r
                        } else {
                            skip()
                        }
                    }
                    "desc" -> {
                        val r = if (options.storeDescriptions) picker.rank(attr("lang")) else 0
                        if (options.storeDescriptions && (descRank == -1 || r < descRank)) {
                            p.description = cap(readText(), options.maxDescriptionChars)
                            descRank = r
                        } else {
                            skip()
                        }
                    }
                    "category" -> {
                        val r = picker.rank(attr("lang"))
                        val t = readText()
                        if (t.isNotEmpty() && catTexts.size < MAX_CATEGORY_CANDIDATES) {
                            if (catTexts.size == catRanks.size) catRanks = catRanks.copyOf(catRanks.size * 2)
                            catRanks[catTexts.size] = r
                            catTexts.add(t)
                        }
                    }
                    "icon" -> {
                        if (p.iconUrl == null) p.iconUrl = attr("src")?.trim()?.ifEmpty { null }
                        skip()
                    }
                    "rating" -> {
                        if (p.rating == null) {
                            val system = attr("system")?.trim()
                            val v = readChildValue()
                            if (v != null) p.rating = if (system.isNullOrEmpty()) v else "$system:$v"
                        } else {
                            skip()
                        }
                    }
                    "star-rating" -> if (p.starRating == null) p.starRating = readChildValue() else skip()
                    "date" -> {
                        val t = readText()
                        if (p.year == XmltvProgramme.NONE) p.year = year(t)
                    }
                    "credits" -> readCredits()
                    "episode-num" -> {
                        val system = attr("system")
                        val t = readText()
                        if (t.isNotEmpty()) episodes.add(system, t)
                    }
                    "new" -> flag(ProgrammeFlags.NEW)
                    "premiere" -> flag(ProgrammeFlags.PREMIERE)
                    "last-chance" -> flag(ProgrammeFlags.LAST_CHANCE)
                    "previously-shown" -> flag(ProgrammeFlags.PREVIOUSLY_SHOWN)
                    "subtitles" -> flag(ProgrammeFlags.SUBTITLES)
                    "live" -> flag(ProgrammeFlags.LIVE)
                    "video" -> readVideo()
                    else -> skip()
                }
            }
            pickCategories()
            episodes.applyTo(p)
        }

        private fun flag(f: Int) {
            p.flags = p.flags or f
            skip()
        }

        private fun pickCategories() {
            val n = catTexts.size
            if (n == 0) return
            var best = Int.MAX_VALUE
            for (i in 0 until n) if (catRanks[i] < best) best = catRanks[i]
            for (i in 0 until n) {
                if (catRanks[i] != best) continue
                val c = catTexts[i]
                var dup = false
                for (k in p.categories.indices) if (p.categories[k].equals(c, ignoreCase = true)) { dup = true; break }
                if (dup) continue
                p.categories.add(c)
                if (c.equals("Live", ignoreCase = true)) p.flags = p.flags or ProgrammeFlags.LIVE
                if (p.categories.size == XmltvProgramme.MAX_CATEGORIES) break
            }
        }

        private fun readVideo() {
            val depth = xpp.depth
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_TAG && xpp.depth == depth) return
                if (ev == XmlPullParser.END_DOCUMENT) throw EOFException("EOF inside <video>")
                if (ev != XmlPullParser.START_TAG) continue
                if (xpp.name == "quality") {
                    val q = readText()
                    when {
                        q.equals("UHD", true) || q.equals("4K", true) || q.equals("UHDTV", true) -> p.flags = p.flags or ProgrammeFlags.UHD
                        q.equals("HDTV", true) || q.equals("HD", true) -> p.flags = p.flags or ProgrammeFlags.HD
                    }
                } else {
                    skip()
                }
            }
        }

        private fun readCredits() {
            val depth = xpp.depth
            var sb: StringBuilder? = null
            var full = false
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_TAG && xpp.depth == depth) break
                if (ev == XmlPullParser.END_DOCUMENT) throw EOFException("EOF inside <credits>")
                if (ev != XmlPullParser.START_TAG) continue
                val prefix = creditPrefix(xpp.name)
                if (prefix == null || full) {
                    skip()
                    continue
                }
                val role = if (prefix == "a") attr("role")?.trim()?.ifEmpty { null } else null
                val name = readText()
                if (name.isEmpty()) continue
                val b = sb ?: StringBuilder(64).also { sb = it }
                val add = prefix.length + 1 + name.length + (if (role != null) role.length + 3 else 0) + (if (b.isEmpty()) 0 else 1)
                if (b.length + add > XmltvProgramme.MAX_CREDITS_CHARS) {
                    full = true
                    continue
                }
                if (b.isNotEmpty()) b.append(XmltvProgramme.CATEGORY_SEPARATOR)
                b.append(prefix).append(':').append(name)
                if (role != null) b.append(" (").append(role).append(')')
            }
            if (p.credits == null) p.credits = sb?.toString()?.ifEmpty { null }
        }

        /** Text of the first `<value>` child (rating, star-rating). */
        private fun readChildValue(): String? {
            val depth = xpp.depth
            var v: String? = null
            while (true) {
                val ev = xpp.next()
                if (ev == XmlPullParser.END_TAG && xpp.depth == depth) return v
                if (ev == XmlPullParser.END_DOCUMENT) throw EOFException("EOF inside element")
                if (ev != XmlPullParser.START_TAG) continue
                if (xpp.name == "value" && v == null) v = readText().ifEmpty { null } else skip()
            }
        }

        /**
         * Text content of the current element (positioned on its START_TAG), nested elements skipped,
         * trimmed. Ends on the element's END_TAG.
         */
        fun readText(): String {
            val depth = xpp.depth
            var single: String? = null
            var multi = false
            while (true) {
                val ev = xpp.next()
                when (ev) {
                    XmlPullParser.TEXT -> {
                        val t = xpp.text
                        if (single == null && !multi) {
                            single = t
                        } else {
                            if (!multi) {
                                text.setLength(0)
                                text.append(single)
                                multi = true
                            }
                            text.append(t)
                        }
                    }
                    XmlPullParser.START_TAG -> skip()
                    XmlPullParser.END_TAG -> if (xpp.depth == depth) break
                    XmlPullParser.END_DOCUMENT -> throw EOFException("EOF inside text element")
                }
            }
            val raw = if (multi) text.toString() else single ?: return ""
            return raw.trim()
        }

        /** Skips the subtree of the current START_TAG by depth counting; never reads text. */
        fun skip() {
            var d = 1
            while (d > 0) {
                when (xpp.next()) {
                    XmlPullParser.START_TAG -> d++
                    XmlPullParser.END_TAG -> d--
                    XmlPullParser.END_DOCUMENT -> {
                        // Relaxed parsers close open elements at EOF; reaching the end here means truncation.
                        throw EOFException("EOF while skipping")
                    }
                }
            }
        }

        fun attr(name: String): String? {
            for (a in 0 until xpp.attributeCount) if (xpp.getAttributeName(a) == name) return xpp.getAttributeValue(a)
            return null
        }
    }

    /** Remembers the last [TAIL] bytes and whether the underlying stream reached EOF. */
    internal class TailInputStream(input: InputStream) : FilterInputStream(input) {
        private val ring = ByteArray(TAIL)
        private var pos = 0
        private var filled = false
        var eof = false
            private set
        var total = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b < 0) eof = true else record(b.toByte())
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) {
                eof = true
            } else {
                for (k in off until off + n) record(b[k])
            }
            return n
        }

        private fun record(b: Byte) {
            ring[pos] = b
            pos++
            total++
            if (pos == TAIL) {
                pos = 0
                filled = true
            }
        }

        /** True when the bytes seen last end with `</tv>` (optionally followed by whitespace). UTF-16 tolerant. */
        fun endsWithCloseTv(): Boolean {
            val size = if (filled) TAIL else pos
            val bytes = ByteArray(size)
            var n = 0
            for (k in 0 until size) {
                val idx = if (filled) (pos + k) % TAIL else k
                val c = ring[idx]
                if (c.toInt() != 0) bytes[n++] = c
            }
            var i = n - 1
            while (i >= 0 && isWs(bytes[i])) i--
            if (i < 0 || bytes[i] != '>'.code.toByte()) return false
            i--
            while (i >= 0 && isWs(bytes[i])) i--
            if (i < 3) return false
            val v = bytes[i].toInt().toChar().lowercaseChar()
            val t = bytes[i - 1].toInt().toChar().lowercaseChar()
            return v == 'v' && t == 't' && bytes[i - 2] == '/'.code.toByte() && bytes[i - 3] == '<'.code.toByte()
        }

        private fun isWs(b: Byte) = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()
    }

    companion object {
        const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        const val CANCEL_EVERY = 1_000
        private const val TAIL = 256
        private const val MAX_CATEGORY_CANDIDATES = 32

        private val ENTITY_NAMES = arrayOf(
            "nbsp", "ndash", "mdash", "hellip", "laquo", "raquo", "copy", "reg", "euro", "apos",
            "lsquo", "rsquo", "ldquo", "rdquo", "trade",
        )
        private val ENTITY_VALUES = arrayOf(
            " ", "–", "—", "…", "«", "»", "©", "®", "€", "'",
            "‘", "’", "“", "”", "™",
        )

        internal fun stripUtf8Bom(input: InputStream): InputStream {
            val pb = PushbackInputStream(input, 3)
            val head = ByteArray(3)
            var n = 0
            while (n < 3) {
                val r = pb.read(head, n, 3 - n)
                if (r < 0) break
                n += r
            }
            val bom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
            if (!bom && n > 0) pb.unread(head, 0, n)
            return pb
        }

        /** `<desc>` cap on a code-point boundary. */
        internal fun cap(s: String, max: Int): String? {
            if (s.isEmpty()) return null
            if (s.length <= max) return s
            var cut = max
            if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut--
            return s.substring(0, cut)
        }

        internal fun year(s: String): Int {
            if (s.length < 4) return XmltvProgramme.NONE
            var v = 0
            for (k in 0 until 4) {
                val c = s[k]
                if (c !in '0'..'9') return XmltvProgramme.NONE
                v = v * 10 + (c - '0')
            }
            return v
        }

        private fun creditPrefix(name: String): String? = when (name) {
            "director" -> "d"
            "actor" -> "a"
            "writer" -> "w"
            "adapter" -> "ad"
            "producer" -> "p"
            "composer" -> "c"
            "editor" -> "e"
            "presenter" -> "pr"
            "commentator" -> "co"
            "guest" -> "g"
            else -> null
        }
    }
}
