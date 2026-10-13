package app.thebox.core.xmltv

import app.thebox.core.xmltv.XmltvProgramme.Companion.NONE

/**
 * `<episode-num>` systems (docs/design/m1-data-layer.md §3.5.2). Hand-written scanners, no regex.
 *
 * - `xmltv_ns`: `S.E.P`, each part optionally `n/total`, parts may be empty, all zero-based.
 * - `onscreen` / `SxxExx`: `s 3 e 10`-style (`[ex.]` separator), else `E`/`Ep`/`Episode` + number.
 * - `dd_progid`: `EP01234567.0012` → series ref `EP01234567`.
 *
 * Precedence: season/episode from `xmltv_ns` beat `onscreen`/`SxxExx`; the label is the onscreen text if
 * present, else the derived `S{s} E{e}` (+ ` (Part p/t)`).
 */
object EpisodeNum {
    /** Result slots of [parseXmltvNs]: season, episode, part, partTotal (1-based or [NONE]). */
    fun parseXmltvNs(text: String, out: IntArray): Boolean {
        out.fill(NONE, 0, 4)
        var field = 0
        var i = 0
        val n = text.length
        var any = false
        while (field < 3 && i <= n) {
            var end = i
            while (end < n && text[end] != '.') end++
            // Field [i, end): "n" or "n/total" (whitespace tolerated), may be empty.
            val slash = indexOf(text, '/', i, end)
            val value = parseIntIn(text, i, if (slash >= 0) slash else end)
            if (value >= 0) {
                any = true
                when (field) {
                    0 -> out[0] = value + 1
                    1 -> out[1] = value + 1
                    2 -> {
                        out[2] = value + 1
                        if (slash >= 0) parseIntIn(text, slash + 1, end).let { if (it > 0) out[3] = it }
                    }
                }
            }
            field++
            i = end + 1
        }
        return any
    }

    /** Result slots: season, episode (1-based as written, or [NONE]). */
    fun parseOnscreen(text: String, out: IntArray): Boolean {
        out[0] = NONE
        out[1] = NONE
        val n = text.length
        // (?i)s\s*(\d{1,3})\s*[ex.]\s*(\d{1,4})
        for (i in 0 until n) {
            val c = text[i]
            if (c != 's' && c != 'S') continue
            var k = skipSpaces(text, i + 1)
            val sStart = k
            while (k < n && k - sStart < 4 && text[k].isAsciiDigit()) k++
            val sLen = k - sStart
            if (sLen !in 1..3) continue
            k = skipSpaces(text, k)
            if (k >= n) continue
            val sep = text[k]
            if (sep != 'e' && sep != 'E' && sep != 'x' && sep != 'X' && sep != '.') continue
            k = skipSpaces(text, k + 1)
            val eStart = k
            while (k < n && k - eStart < 5 && text[k].isAsciiDigit()) k++
            val eLen = k - eStart
            if (eLen !in 1..4) continue
            out[0] = parseIntIn(text, sStart, sStart + sLen)
            out[1] = parseIntIn(text, eStart, eStart + eLen)
            return true
        }
        // (?i)\be(?:p(?:isode)?)?\.?\s*(\d{1,4})
        for (i in 0 until n) {
            val c = text[i]
            if (c != 'e' && c != 'E') continue
            if (i > 0 && text[i - 1].isLetterOrDigit()) continue
            var k = i + 1
            if (k < n && (text[k] == 'p' || text[k] == 'P')) {
                k++
                if (text.regionMatches(k, "isode", 0, 5, ignoreCase = true)) k += 5
            }
            if (k < n && text[k] == '.') k++
            k = skipSpaces(text, k)
            val eStart = k
            while (k < n && k - eStart < 5 && text[k].isAsciiDigit()) k++
            val eLen = k - eStart
            if (eLen !in 1..4) continue
            out[1] = parseIntIn(text, eStart, eStart + eLen)
            return true
        }
        return false
    }

    /** `EP01234567.0012` → `EP01234567`; null for a blank id. */
    fun seriesRef(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val dot = t.indexOf('.')
        val ref = if (dot >= 0) t.substring(0, dot) else t
        return ref.ifEmpty { null }
    }

    fun label(season: Int, episode: Int, part: Int, partTotal: Int): String? {
        if (season == NONE && episode == NONE) return null
        val sb = StringBuilder(24)
        if (season != NONE) sb.append('S').append(season)
        if (episode != NONE) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append('E').append(episode)
        }
        if (part != NONE) {
            sb.append(" (Part ").append(part)
            if (partTotal != NONE) sb.append('/').append(partTotal)
            sb.append(')')
        }
        return sb.toString()
    }

    /** Collects every `<episode-num>` of one programme, then applies the precedence rules. */
    class Accumulator {
        private val ns = IntArray(4)
        private val os = IntArray(2)
        private var hasNs = false
        private var osSeason = NONE
        private var osEpisode = NONE
        private var onscreenText: String? = null
        private var seriesRef: String? = null

        fun reset() {
            hasNs = false
            ns.fill(NONE)
            osSeason = NONE
            osEpisode = NONE
            onscreenText = null
            seriesRef = null
        }

        fun add(system: String?, text: String) {
            when {
                system == null || system.equals("xmltv_ns", ignoreCase = true) -> {
                    // No system attribute: the DTD default is "onscreen", but bare "S.E.P" is xmltv_ns in practice.
                    if (system == null && !looksLikeNs(text)) addOnscreen(text) else if (!hasNs) hasNs = parseXmltvNs(text, ns)
                }
                system.equals("onscreen", ignoreCase = true) || system.equals("SxxExx", ignoreCase = true) -> addOnscreen(text)
                system.equals("dd_progid", ignoreCase = true) -> if (seriesRef == null) seriesRef = seriesRef(text)
                else -> Unit
            }
        }

        private fun addOnscreen(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            if (onscreenText == null) onscreenText = t
            if (osSeason == NONE && osEpisode == NONE && parseOnscreen(t, os)) {
                osSeason = os[0]
                osEpisode = os[1]
            }
        }

        fun applyTo(p: XmltvProgramme) {
            if (hasNs && (ns[0] != NONE || ns[1] != NONE || ns[2] != NONE)) {
                p.season = ns[0]
                p.episode = ns[1]
                p.part = ns[2]
                p.partTotal = ns[3]
            } else {
                p.season = osSeason
                p.episode = osEpisode
            }
            p.episodeLabel = onscreenText ?: label(p.season, p.episode, p.part, p.partTotal)
            p.seriesRef = seriesRef
        }

        private fun looksLikeNs(text: String): Boolean {
            for (c in text) if (!(c.isAsciiDigit() || c == '.' || c == '/' || c == ' ')) return false
            return text.indexOf('.') >= 0
        }
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun skipSpaces(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isWhitespace()) k++
        return k
    }

    private fun indexOf(s: String, c: Char, from: Int, to: Int): Int {
        for (k in from until to) if (s[k] == c) return k
        return -1
    }

    /** Non-negative integer in [from, to) ignoring surrounding whitespace; -1 when absent or not a number. */
    private fun parseIntIn(s: String, from: Int, to: Int): Int {
        var a = from
        var b = to
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        if (a == b || b - a > 6) return -1
        var v = 0
        for (k in a until b) {
            val c = s[k]
            if (!c.isAsciiDigit()) return -1
            v = v * 10 + (c - '0')
        }
        return v
    }
}
