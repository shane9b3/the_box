// docs/design/m6-event-channels.md §3.7, §4.1 (WP-1). Pure string scanning, no regex.
package app.thebox.core.events

import java.text.Normalizer
import java.util.Locale

/** The two sides of an event title (§3.7), for `EventGameRule` and the duration estimate. */
object EventMatchup {
    /**
     * Separators in priority order (§3.7 step 2): the first one of this list that occurs in the segment wins, at its
     * leftmost occurrence. "@" and "at" are ordered (away first). Matched case-insensitively.
     */
    private val SEPARATORS: Array<String> = arrayOf(" @ ", " at ", " vs. ", " vs ", " v. ", " v ", " - ", " – ", " — ")
    private const val ORDERED_COUNT = 2

    /** Sport and league words dropped from a side (§3.7 step 4). */
    private val SPORT_WORDS: Set<String> = setOf("nhl", "hockey", "nfl", "football", "the")

    /** The cleaned, folded sides of [title], or null when it names no single matchup. Never throws. */
    fun of(title: String): Matchup? = try {
        ofImpl(title)
    } catch (e: Exception) {
        null
    }

    private fun ofImpl(title: String): Matchup? {
        val found = locate(title) ?: return null
        if (found.multi) return null
        val seg = found.segment
        val sep = SEPARATORS[found.sep]
        val first = cleanSide(seg.substring(0, found.at))
        val second = cleanSide(seg.substring(found.at + sep.length))
        if (first.isEmpty() || second.isEmpty()) return null
        return Matchup(first, second, ordered = found.sep < ORDERED_COUNT)
    }

    /** True when [title] has a segment with a separator, even one holding several matchups (`EventDuration`). */
    internal fun hasMatchup(title: String): Boolean = try {
        locate(title) != null
    } catch (e: Exception) {
        false
    }

    private class Found(val segment: String, val sep: Int, val at: Int, val multi: Boolean)

    /** Step 1: the last ": " / " | " segment with a separator, else the whole title; steps 2 and 3. */
    private fun locate(title: String): Found? {
        val segments = splitSegments(title)
        for (i in segments.indices.reversed()) {
            val f = separatorIn(segments[i])
            if (f != null) return f
        }
        return if (segments.size > 1) separatorIn(title) else null
    }

    private fun splitSegments(title: String): List<String> {
        val out = ArrayList<String>(4)
        var start = 0
        var i = 0
        while (i < title.length) {
            val c = title[i]
            if (c == ':' && i + 1 < title.length && title[i + 1] == ' ') {
                out += title.substring(start, i); i += 2; start = i; continue
            }
            if (c == ' ' && i + 2 < title.length && title[i + 1] == '|' && title[i + 2] == ' ') {
                out += title.substring(start, i); i += 3; start = i; continue
            }
            i++
        }
        out += title.substring(start)
        return out
    }

    private fun separatorIn(segment: String): Found? {
        for (k in SEPARATORS.indices) {
            val at = segment.indexOf(SEPARATORS[k], ignoreCase = true)
            if (at > 0) return Found(segment, k, at, isMulti(segment))
        }
        return null
    }

    /** Step 3: "•", or " / " between two separators: several matchups, no sides. */
    private fun isMulti(segment: String): Boolean {
        if (segment.indexOf('•') >= 0) return true
        if (segment.indexOf(" / ") < 0) return false
        var withSep = 0
        for (part in segment.split(" / ")) {
            val padded = " $part "
            if (SEPARATORS.any { padded.indexOf(it, ignoreCase = true) >= 0 }) withSep++
        }
        return withSep >= 2
    }

    /** Step 4: brackets, `#n` ranks, a leading number or `<year>? week <n>`, sport words. */
    internal fun cleanSide(raw: String): List<String> {
        val sb = StringBuilder(raw.length)
        var depth = 0
        var close = ' '
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (depth == 0) {
                when (c) {
                    '(' -> { depth = 1; close = ')' }
                    '[' -> { depth = 1; close = ']' }
                    '{' -> { depth = 1; close = '}' }
                    '#' -> {
                        var j = i + 1
                        while (j < raw.length && raw[j].isAsciiDigit()) j++
                        if (j > i + 1) { sb.append(' '); i = j; continue }
                        sb.append(' ')
                    }
                    else -> sb.append(c)
                }
            } else if (c == close) {
                depth = 0; sb.append(' ')
            }
            i++
        }
        val tokens = EventText.tokens(sb.toString()).filterTo(ArrayList()) { it !in SPORT_WORDS }
        var drop = 0
        if (tokens.size >= 3 && tokens[0].length == 4 && tokens[0].allDigits() && tokens[1] == "week" && tokens[2].allDigits()) {
            drop = 3
        } else if (tokens.size >= 2 && tokens[0] == "week" && tokens[1].allDigits()) {
            drop = 2
        } else if (tokens.isNotEmpty() && tokens[0].allDigits()) {
            drop = 1
        }
        return if (drop == 0) tokens else tokens.subList(drop, tokens.size).toList()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun String.allDigits() = isNotEmpty() && all { it in '0'..'9' }
}

/**
 * Shared folding for event titles, hints and team names (§3.7, §4.2). Plain scanning, no regex: Android's ICU regex
 * differs from the JVM's.
 */
internal object EventText {
    /** Lower case (ROOT), accents removed (NFD, combining marks dropped). */
    fun fold(s: String): String {
        var ascii = true
        for (ch in s) if (ch.code >= 0x80) { ascii = false; break }
        if (ascii) return s.lowercase(Locale.ROOT)
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(n.length)
        for (ch in n) {
            when (Character.getType(ch).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /**
     * Folded tokens: letters and digits only; apostrophes join ("women's" → "womens"); a dot joins single letters
     * ("l.a." → "la") and otherwise splits ("st.louis" → "st", "louis"); "&" is "and"; "saint" is "st".
     */
    fun tokens(s: String): List<String> {
        val f = fold(s)
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val t = sb.toString()
                out += if (t == "saint") "st" else t
                sb.setLength(0)
            }
        }
        var i = 0
        while (i < f.length) {
            val c = f[i]
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '\'' || c == '’' || c == '`' || c == '‘' -> Unit
                c == '.' && sb.length == 1 && sb[0].isLetter() && i + 1 < f.length && f[i + 1].isLetter() &&
                    (i + 2 >= f.length || !f[i + 2].isLetterOrDigit()) -> Unit
                c == '&' -> { flush(); out += "and" }
                else -> flush()
            }
            i++
        }
        flush()
        return out
    }
}
