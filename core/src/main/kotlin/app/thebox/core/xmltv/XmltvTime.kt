package app.thebox.core.xmltv

import androidx.collection.MutableIntIntMap
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Allocation-free XMLTV time parser to UTC epoch minutes (docs/design/m1-data-layer.md §3.5.1).
 * No `java.time`, `substring` or regex per row: digits are read by index, the day number comes from
 * Hinnant's `days_from_civil`, and a missing offset goes through an [OffsetResolver] that calls
 * `java.time` once per distinct local hour.
 */
object XmltvTime {
    /** Unusable time (fewer than 8 digits, odd digit count, out-of-range field). */
    const val BAD: Int = Int.MIN_VALUE

    /** Marker for "no stop attribute" in [XmltvProgramme.stopMin] and [XmltvHandler.acceptProgramme]. */
    const val MISSING: Int = Int.MIN_VALUE + 1

    private const val NO_OFFSET = Int.MIN_VALUE
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 5000

    /** Offset in minutes east of UTC for a local wall-clock minute (used only when the string has no offset). */
    fun interface OffsetResolver {
        fun offsetMinutes(localMin: Int): Int
    }

    val UTC: OffsetResolver = OffsetResolver { 0 }

    /** Resolver for a source's `assumeZone` (null = UTC, protocols §2.6). Not thread-safe; one per source. */
    fun resolverFor(zone: ZoneId?): OffsetResolver {
        if (zone == null) return UTC
        val rules = zone.rules
        if (rules.isFixedOffset) {
            val off = rules.getOffset(java.time.Instant.EPOCH).totalSeconds / 60
            return if (off == 0) UTC else OffsetResolver { off }
        }
        return ZoneOffsetCache(zone)
    }

    /** Per-source cache `localHour → offset`, filled through `ZoneRules.getOffset(LocalDateTime)` on a miss. */
    class ZoneOffsetCache(zone: ZoneId) : OffsetResolver {
        private val rules = zone.rules
        private val cache = MutableIntIntMap(64)

        override fun offsetMinutes(localMin: Int): Int {
            val hour = Math.floorDiv(localMin, 60)
            val cached = cache.getOrDefault(hour, NO_OFFSET)
            if (cached != NO_OFFSET) return cached
            // Gaps and overlaps follow java.time: the offset before the transition (the earlier offset).
            val ldt = LocalDateTime.ofEpochSecond(hour.toLong() * 3600L, 0, ZoneOffset.UTC)
            val off = rules.getOffset(ldt).totalSeconds / 60
            cache[hour] = off
            return off
        }
    }

    /** Parses `YYYYMMDD[hh[mm[ss]]] [offset]` to epoch minutes, or [BAD]. */
    fun parseMinutes(s: String, assumed: OffsetResolver): Int {
        val n = s.length
        var i = 0
        while (i < n && s[i] == ' ') i++
        val digitsStart = i
        while (i < n && s[i] in '0'..'9') i++
        val count = i - digitsStart
        if (count < 8 || (count < 14 && count % 2 != 0)) return BAD
        val b = digitsStart
        val year = num(s, b, 4)
        val month = num(s, b + 4, 2)
        val day = num(s, b + 6, 2)
        val hour = if (count >= 10) num(s, b + 8, 2) else 0
        val minute = if (count >= 12) num(s, b + 10, 2) else 0
        val second = if (count >= 14) num(s, b + 12, 2) else 0
        if (year < MIN_YEAR || year > MAX_YEAR || month !in 1..12 || day !in 1..31 ||
            hour > 23 || minute > 59 || second > 60
        ) {
            return BAD
        }
        // Fractional seconds ("…00.000") are tolerated and ignored.
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') i++
        }
        while (i < n && s[i] == ' ') i++
        val offset = parseOffset(s, i)
        val localMin = (daysFromCivil(year, month, day) * 1440L + hour * 60 + minute).toInt()
        return if (offset != NO_OFFSET) localMin - offset else localMin - assumed.offsetMinutes(localMin)
    }

    /** Hinnant's days_from_civil: days since 1970-01-01 for a proleptic Gregorian date. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun num(s: String, from: Int, len: Int): Int {
        var v = 0
        for (k in from until from + len) v = v * 10 + (s[k] - '0')
        return v
    }

    /** Offset in minutes at [from], or [NO_OFFSET] when absent or unrecognised (then the resolver decides). */
    private fun parseOffset(s: String, from: Int): Int {
        val n = s.length
        if (from >= n) return NO_OFFSET
        val c = s[from]
        if (c == '+' || c == '-') return signed(s, from)
        if (!c.isLetter()) return NO_OFFSET
        var end = from
        while (end < n && s[end].isLetter()) end++
        val len = end - from
        if (tokenIs(s, from, len, "Z") || tokenIs(s, from, len, "UTC") || tokenIs(s, from, len, "GMT") || tokenIs(s, from, len, "UT")) {
            // "GMT+01:00" style: an explicit offset after the zero zone wins.
            var k = end
            while (k < n && s[k] == ' ') k++
            if (k < n && (s[k] == '+' || s[k] == '-')) {
                val o = signed(s, k)
                if (o != NO_OFFSET) return o
            }
            return 0
        }
        for (z in ABBREVIATIONS.indices) {
            if (tokenIs(s, from, len, ABBREVIATIONS[z])) return ABBREVIATION_OFFSETS[z]
        }
        return NO_OFFSET
    }

    private fun tokenIs(s: String, from: Int, len: Int, token: String): Boolean =
        len == token.length && s.regionMatches(from, token, 0, len, ignoreCase = true)

    /** `±HHMM`, `±HH:MM`, `±HH`. */
    private fun signed(s: String, at: Int): Int {
        val n = s.length
        val sign = if (s[at] == '-') -1 else 1
        var i = at + 1
        if (i + 2 > n || !isDigit(s[i]) || !isDigit(s[i + 1])) return NO_OFFSET
        val hh = num(s, i, 2)
        i += 2
        var mm = 0
        if (i < n && s[i] == ':') i++
        if (i + 2 <= n && isDigit(s[i]) && isDigit(s[i + 1])) {
            mm = num(s, i, 2)
            i += 2
        }
        if (i < n && isDigit(s[i])) return NO_OFFSET
        if (hh > 23 || mm > 59) return NO_OFFSET
        return sign * (hh * 60 + mm)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private val ABBREVIATIONS = arrayOf("EST", "EDT", "CST", "CDT", "MST", "MDT", "PST", "PDT", "CET", "CEST", "BST", "EET", "EEST", "MSK")
    private val ABBREVIATION_OFFSETS = intArrayOf(-300, -240, -360, -300, -420, -360, -480, -420, 60, 120, 60, 120, 180, 180)
}
