package app.thebox.core.m3u

import app.thebox.core.source.M3uHeader
import app.thebox.core.source.RawCatchup

/**
 * `#EXTM3U` attributes → [M3uHeader] (docs/design/m1-data-layer.md §3.4 M25). Several `#EXTM3U` lines
 * (concatenated lists) merge: the first value per key wins, and the EPG URLs are the union.
 */
internal class HeaderAttrs {
    private val attrs = LinkedHashMap<String, String>()
    private val epg = LinkedHashSet<String>()
    var badAttribute = false
        private set
    var seen = false
        private set

    fun add(line: String, from: Int) {
        seen = true
        val r = ExtinfLexer.lex(line, from, withDuration = false)
        if (r.badAttribute) badAttribute = true
        for ((k, v) in r.attributes) {
            if (k == "x-tvg-url" || k == "url-tvg" || k == "tvg-url") {
                splitEpg(v)
            } else if (!attrs.containsKey(k)) {
                attrs[k] = v
            }
        }
    }

    private fun splitEpg(v: String) {
        var i = 0
        val n = v.length
        while (i < n) {
            while (i < n && (v[i] == ',' || v[i].isWhitespace())) i++
            val s = i
            while (i < n && v[i] != ',' && !v[i].isWhitespace()) i++
            if (i > s) epg += v.substring(s, i)
        }
    }

    fun build(): M3uHeader {
        val rest = LinkedHashMap<String, String>()
        for ((k, v) in attrs) if (k !in CONSUMED) rest[k] = v
        val shift = Attr.parseDecimal(attrs["tvg-shift"])?.let { if (kotlin.math.abs(it) <= 24.0) it else it / 3600.0 }
        return M3uHeader(
            epgUrls = epg.toList(),
            tvgShiftHours = shift,
            catchup = Attr.catchup(attrs),
            urlLogo = attrs["url-logo"]?.takeIf { it.isNotBlank() },
            refreshSec = Attr.leadingDigits(attrs["refresh"])?.toLongOrNull(),
            maxConnections = Attr.leadingDigits(attrs["max-conn"])?.toIntOrNull(),
            attributes = rest,
        )
    }

    private companion object {
        val CONSUMED = setOf(
            "tvg-shift", "catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction",
            "timeshift", "tvg-rec", "catchup-time", "url-logo", "refresh", "max-conn",
        )
    }
}

/** Small attribute parsers shared by the header and entry paths (§3.4 M15–M17, M26, M27). */
internal object Attr {
    private val JUNK_IDS = setOf("", "none", "null", "nil", "0", "-", "n/a", "na", "unknown", "noepg", "no-epg", "no_epg", "undefined")

    /** M15: trimmed tvg-id, or null when it is a junk value. Case preserved. */
    fun tvgId(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.length <= 9 && t.lowercase(java.util.Locale.ROOT) in JUNK_IDS) return null
        return t
    }

    /** M16: `^\d{1,6}(\.0+)?$`. */
    fun channelNumber(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        var i = 0
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == 0 || i > 6) return null
        if (i < s.length) {
            if (s[i] != '.' || i + 1 >= s.length) return null
            for (j in i + 1 until s.length) if (s[j] != '0') return null
        }
        return s.substring(0, i).toInt()
    }

    /** Double with a decimal comma accepted. */
    fun parseDecimal(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return s.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * M17: `|v| ≤ 24` → hours, else seconds; rounded to whole minutes. [Result] is null-valued and
     * [ShiftResult.bad] when out of range or unparsable.
     */
    class ShiftResult(val minutes: Int?, val bad: Boolean)

    fun shiftMinutes(raw: String?): ShiftResult {
        if (raw == null || raw.isBlank()) return ShiftResult(null, false)
        val v = parseDecimal(raw) ?: return ShiftResult(null, true)
        val minutes = if (kotlin.math.abs(v) <= 24.0) Math.round(v * 60.0) else Math.round(v / 60.0)
        if (minutes < -24 * 60 || minutes > 24 * 60) return ShiftResult(null, true)
        return ShiftResult(minutes.toInt(), false)
    }

    fun leadingDigits(raw: String?): String? {
        val s = raw?.trim() ?: return null
        var i = 0
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        val d = s.substring(0, i)
        return d.takeIf { it.isNotEmpty() && it != "-" }
    }

    /** M26. */
    fun catchup(a: Map<String, String>): RawCatchup {
        val type = (a["catchup"] ?: a["catchup-type"])?.trim()?.takeIf { it.isNotEmpty() }
        val source = a["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() }
        val days = leadingDigits(a["catchup-days"])?.toIntOrNull()
        val corr = parseDecimal(a["catchup-correction"])
        val ts = leadingDigits(a["timeshift"])?.toIntOrNull()
        val rec = leadingDigits(a["tvg-rec"])?.toIntOrNull()
        val time = leadingDigits(a["catchup-time"])?.toLongOrNull()
        if (type == null && source == null && days == null && corr == null && ts == null && rec == null && time == null) return RawCatchup.NONE
        return RawCatchup(type = type, source = source, days = days, correctionHours = corr, timeshiftDays = ts, tvgRecDays = rec, catchupTimeSec = time)
    }

    /** M27. */
    fun adult(a: Map<String, String>): Boolean {
        val ad = a["adult"]?.trim()
        if (ad == "1" || ad.equals("true", ignoreCase = true)) return true
        if (a["censored"]?.trim() == "1") return true
        return !a["parent-code"].isNullOrBlank()
    }

    fun truthy(v: String?): Boolean {
        val t = v?.trim() ?: return false
        return t == "1" || t.equals("true", ignoreCase = true)
    }
}
