// docs/design/m6-event-channels.md §3, §4.1 (WP-1).
package app.thebox.core.events

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Reads an event from a provider channel name (§3). Pure and table-driven: plain string scanning, no `java.util.regex`
 * (Android's ICU regex differs from the JVM's). Never throws: any exception inside is [ParsedName.None]. Right-anchored:
 * it reads the tail first. The input is always the provider name (`channel.name`), never the custom or display name.
 *
 * Settled readings (WP-1, recorded in the WP-1 report):
 * - `slotOf` / `isEmptySlot` use the structure only (no clock): a date that exists in some year (29 Feb) or lies outside
 *   the year window still has a slot, although `parse` gives `None` for it.
 * - An empty slot may also be written `<slot> - NO EVENT` ("LIVE EVENT 02 - NO EVENT"; §2.1 counts it).
 * - With a European zone (§3.2), a B / E date that is only valid as D.M is read as D.M.
 * - A local time in a DST overlap takes the later offset; a time in a gap moves forward by the gap (java.time).
 */
object EventNameParser {
    /**
     * Keys the event index (§5.2) and feeds `ChannelNamePrefix.RULE_VERSION` (§5.3, re-review E8): bumping it rebuilds
     * both with no manual step.
     */
    const val VERSION: Int = 1

    /** Longer names are never events (real ones stay under 200 characters); keeps the fuzz cost flat. */
    private const val MAX_LEN = 400
    private const val MAX_SLOT_LEN = 40
    private const val PLACEHOLDER_YEAR = 2098
    private const val DAY_MS = 86_400_000L

    private const val NEW_YORK = "America/New_York"

    /** §3.3 zone words (upper case) → zone id. */
    private val ZONES: Map<String, String> = mapOf(
        "ET" to NEW_YORK, "EST" to NEW_YORK, "EDT" to NEW_YORK,
        "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
        "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
        "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
        "UTC" to "UTC", "GMT" to "UTC", "Z" to "UTC",
        "LONDON" to "Europe/London", "UK" to "Europe/London", "BST" to "Europe/London",
        "CET" to "Europe/Paris", "CEST" to "Europe/Paris",
        "AEST" to "+10:00", "AEDT" to "+11:00",
    )

    /** Zone words after which a B / E date may be D.M (arch S12). */
    private val EUROPEAN: Set<String> = setOf("LONDON", "UK", "BST", "CET", "CEST", "GMT", "UTC", "Z")

    /** Shape C country tags (§3.3 CONVENTION). */
    private val TAG_ZONES: Map<String, String> = mapOf(
        "CA" to "America/Toronto", "US" to NEW_YORK, "USA" to NEW_YORK, "UK" to "Europe/London", "GB" to "Europe/London",
        "AU" to "Australia/Sydney",
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9,
        "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12,
    )

    private val WEEKDAYS: Set<String> = setOf(
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /** Leap-year month lengths: the structure check is year-free, `parse` checks the real year. */
    private val MAX_DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** The full result for [name] read at [ctx] (§3.2–§3.5). Never throws. */
    fun parse(name: String, ctx: EventParseContext): ParsedName = try {
        parseImpl(name, ctx)
    } catch (e: Exception) {
        ParsedName.None
    }

    /** The slot of an event or empty-slot name; null otherwise. No clock, no zone (§5.3). */
    fun slotOf(name: String): String? = try {
        structure(name)?.slot
    } catch (e: Exception) {
        null
    }

    /** True for an empty slot (§3.2 `empty`). No clock, no zone (§5.5). */
    fun isEmptySlot(name: String): Boolean = try {
        structure(name)?.empty == true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ structure (no clock, no zone)

    private class Tail(
        val shape: EventShape,
        /** Where the tail starts in the trimmed name; the head is everything before it. */
        val start: Int,
        /** A / D / C: month; B / E: the first number of n1.n2. */
        val n1: Int,
        /** A / D / C: day; B / E: the second number. */
        val n2: Int,
        /** Shape C only, else -1. */
        val year: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        /** The zone word, upper case, or null. */
        val zone: String?,
        /** Shape E's network. */
        val net: String?,
    )

    private class Struct(
        val slot: String?,
        val empty: Boolean,
        val tail: Tail? = null,
        val title: String = "",
        val subtitle: String? = null,
        /** Shape C's country tag, upper case. */
        val tag: String? = null,
    )

    private class Slot(val text: String, val tag: String?)

    private fun structure(name: String): Struct? {
        if (name.length > MAX_LEN) return null
        val t = trimWs(name)
        if (t.isEmpty()) return null
        emptyForm(t)?.let { return it }
        val tail = tailIso(t) ?: tailParen(t) ?: tailPipe(t) ?: tailAt(t) ?: return null

        var head = t.substring(0, tail.start)
        if (tail.shape == EventShape.AT_MONTH_DAY) head = dropTrailingWeekday(head)
        var slot: Slot? = null
        var titleRaw = head
        val sepAt = firstSep(head)
        if (sepAt >= 0) {
            slot = validSlot(head.substring(0, sepAt))
            if (slot != null) titleRaw = head.substring(sepAt + 1)
        }
        if (slot == null && tail.shape != EventShape.ISO) return null

        val (title, subtitle) = cleanTitle(titleRaw, tail.net)
        if (tail.shape == EventShape.ISO && tail.year == PLACEHOLDER_YEAR) return Struct(slot?.text, empty = true)
        if (title.isEmpty()) return if (slot == null) null else Struct(slot.text, empty = true)
        return Struct(slot?.text, empty = false, tail = tail, title = title, subtitle = subtitle, tag = slot?.tag)
    }

    /** `slot sep ( "NO EVENT" | "" | "Offline" )`, and `slot - NO EVENT`. */
    private fun emptyForm(t: String): Struct? {
        val sepAt = firstSep(t)
        if (sepAt >= 0) {
            val slot = validSlot(t.substring(0, sepAt))
            if (slot != null) {
                val rest = trimWs(t.substring(sepAt + 1))
                if (rest.isEmpty() || rest.equals("NO EVENT", ignoreCase = true) || rest.equals("Offline", ignoreCase = true)) {
                    return Struct(slot.text, empty = true)
                }
            }
        }
        if (t.endsWith("NO EVENT", ignoreCase = true)) {
            var k = t.length - "NO EVENT".length
            var ws = 0
            while (k > 0 && isWs(t[k - 1])) { k--; ws++ }
            if (ws > 0 && k > 1 && t[k - 1] == '-' && isWs(t[k - 2])) {
                val slot = validSlot(t.substring(0, k - 1))
                if (slot != null) return Struct(slot.text, empty = true)
            }
        }
        return null
    }

    private fun firstSep(s: String): Int {
        for (i in s.indices) if (s[i] == ':' || s[i] == '|') return i
        return -1
    }

    /**
     * `label ws? num | "(" cc ")" ws "(" label num ")" | cc ws "(" label num ")" | "(" label num ")"`. The slot text is
     * the prefix with whitespace runs collapsed.
     */
    private fun validSlot(prefix: String): Slot? {
        val s = collapseWs(prefix)
        if (s.isEmpty() || s.length > MAX_SLOT_LEN) return null
        if (s.endsWith(")")) {
            val open = s.lastIndexOf('(')
            if (open < 0) return null
            if (!labelNum(trimWs(s.substring(open + 1, s.length - 1)))) return null
            val outer = trimWs(s.substring(0, open))
            val tag = when {
                outer.isEmpty() -> null
                isCc(outer) -> outer.uppercase()
                outer.length > 2 && outer.startsWith("(") && outer.endsWith(")") && isCc(trimWs(outer.substring(1, outer.length - 1))) ->
                    trimWs(outer.substring(1, outer.length - 1)).uppercase()
                else -> return null
            }
            if (outer.isNotEmpty() && !isWs(s[open - 1])) return null
            return Slot(s, tag)
        }
        return if (labelNum(s)) Slot(s, null) else null
    }

    /** `label ws? num`: 1–3 trailing digits after a label with a letter and no brackets. */
    private fun labelNum(x: String): Boolean {
        var d = 0
        var i = x.length
        while (i > 0 && x[i - 1] in '0'..'9') { i--; d++ }
        if (d !in 1..3) return false
        var hasLetter = false
        for (k in 0 until i) {
            val c = x[k]
            if (c == '(' || c == ')' || c == '[' || c == ']' || c == '@') return false
            if (c.isLetter()) hasLetter = true
        }
        return hasLetter
    }

    private fun isCc(s: String): Boolean = s.uppercase() in TAG_ZONES

    // ------------------------------------------------------------------ tails

    /** `ws? "(" yyyy "-" mm "-" dd ws hh ":" mi [":" ss] ")"`. */
    private fun tailIso(t: String): Tail? {
        if (!t.endsWith(")")) return null
        val open = t.lastIndexOf('(')
        if (open < 0) return null
        val c = Cursor(t, open + 1, t.length - 1)
        val y = c.digits(4, 4) ?: return null
        if (!c.char('-')) return null
        val mo = c.digits(1, 2) ?: return null
        if (!c.char('-')) return null
        val d = c.digits(1, 2) ?: return null
        if (c.ws() < 1) return null
        val h = c.digits(1, 2) ?: return null
        if (!c.char(':')) return null
        val mi = c.digits(2, 2) ?: return null
        var ss = 0
        if (c.char(':')) ss = c.digits(2, 2) ?: return null
        c.ws()
        if (!c.atEnd()) return null
        if (mo !in 1..12 || h !in 0..23 || mi !in 0..59 || ss !in 0..59) return null
        if (d < 1 || d > YearMonth.of(y, mo).lengthOfMonth()) return null
        return Tail(EventShape.ISO, open, mo, d, y, h, mi, ss, null, null)
    }

    /** `ws? "(" n1 "." n2 ws clock [ws zone] [ws? "/" any] ")"`. */
    private fun tailParen(t: String): Tail? {
        if (!t.endsWith(")")) return null
        val open = t.lastIndexOf('(')
        if (open < 0) return null
        val c = Cursor(t, open + 1, t.length - 1)
        val n1 = c.digits(1, 2) ?: return null
        if (!c.char('.')) return null
        val n2 = c.digits(1, 2) ?: return null
        if (c.ws() < 1) return null
        val clock = c.clock() ?: return null
        c.ws()
        var zone: String? = null
        if (c.peekLetter()) zone = c.zoneWord() ?: return null
        c.ws()
        if (!c.char('/')) {
            if (!c.atEnd()) return null
        }
        if (!readingValid(n1, n2, zone)) return null
        return Tail(EventShape.PAREN_MONTH_DAY, open, n1, n2, -1, clock / 60, clock % 60, 0, zone, null)
    }

    /** `ws? "|" ws? n1 "." n2 ws clock [ws zone] [ws? "|" ws? net]`. */
    private fun tailPipe(t: String): Tail? {
        val p1 = t.lastIndexOf('|')
        if (p1 < 0) return null
        dateClock(t, p1 + 1, t.length, p1, null)?.let { return it }
        if (p1 == 0) return null
        val p2 = t.lastIndexOf('|', p1 - 1)
        if (p2 < 0) return null
        val net = collapseWs(t.substring(p1 + 1)).ifEmpty { null }
        return dateClock(t, p2 + 1, p1, p2, net)
    }

    private fun dateClock(t: String, from: Int, to: Int, start: Int, net: String?): Tail? {
        val c = Cursor(t, from, to)
        c.ws()
        val n1 = c.digits(1, 2) ?: return null
        if (!c.char('.')) return null
        val n2 = c.digits(1, 2) ?: return null
        if (c.ws() < 1) return null
        val clock = c.clock() ?: return null
        c.ws()
        var zone: String? = null
        if (c.peekLetter()) zone = c.zoneWord() ?: return null
        c.ws()
        if (!c.atEnd()) return null
        if (!readingValid(n1, n2, zone)) return null
        return Tail(EventShape.PIPE_MONTH_DAY, start, n1, n2, -1, clock / 60, clock % 60, 0, zone, net)
    }

    /**
     * Shape A `ws "@" ws [wkd ws] day ws mon ws clock [ws zone]` and shape D `ws [wkd ws] "@" ws mon ws day ws clock
     * [ws? zone]`, on the last "@".
     */
    private fun tailAt(t: String): Tail? {
        val at = t.lastIndexOf('@')
        if (at < 1 || at + 1 >= t.length || !isWs(t[at - 1]) || !isWs(t[at + 1])) return null
        val c = Cursor(t, at + 1, t.length)
        c.ws()
        // An optional weekday ("@ Tue 24 Nov …").
        val save = c.pos
        val w = c.letters()
        if (w != null && w.lowercase() in WEEKDAYS) {
            if (!c.char(',')) c.char('.')
            if (c.ws() < 1) c.pos = save
        } else {
            c.pos = save
        }
        val shape: EventShape
        val month: Int
        val day: Int
        if (c.peekDigit()) {
            day = c.digits(1, 2) ?: return null
            if (c.ws() < 1) return null
            month = c.month() ?: return null
            shape = EventShape.AT_DAY_MONTH
        } else {
            month = c.month() ?: return null
            if (c.ws() < 1) return null
            day = c.digits(1, 2) ?: return null
            shape = EventShape.AT_MONTH_DAY
        }
        if (c.ws() < 1) return null
        val clock = c.clock() ?: return null
        c.ws()
        var zone: String? = null
        if (c.peekLetter()) zone = c.zoneWord() ?: return null
        c.ws()
        if (!c.atEnd()) return null
        if (day < 1 || day > MAX_DAYS[month - 1]) return null
        return Tail(shape, at, month, day, -1, clock / 60, clock % 60, 0, zone, null)
    }

    /** n1.n2 is month.day; with a European zone, day.month too (arch S12). Year-free. */
    private fun readingValid(n1: Int, n2: Int, zone: String?): Boolean {
        if (n1 in 1..12 && n2 in 1..MAX_DAYS[n1 - 1]) return true
        return zone in EUROPEAN && n2 in 1..12 && n1 in 1..MAX_DAYS[n2 - 1]
    }

    private fun dropTrailingWeekday(head: String): String {
        var end = head.length
        while (end > 0 && isWs(head[end - 1])) end--
        var e = end
        if (e > 0 && (head[e - 1] == ',' || head[e - 1] == '.')) e--
        var b = e
        while (b > 0 && head[b - 1].isLetter()) b--
        if (b == e || b == 0 || !isWs(head[b - 1])) return head
        return if (head.substring(b, e).lowercase() in WEEKDAYS) head.substring(0, b) else head
    }

    // ------------------------------------------------------------------ title

    /** §3.2 title clean-up; returns (title, subtitle). */
    private fun cleanTitle(raw: String, net: String?): Pair<String, String?> {
        var s = collapseWs(raw.replace('`', '’'))
        while (s.isNotEmpty() && (s.last() == '@' || s.last() == '-' || s.last() == '|')) s = trimWs(s.dropLast(1))
        var sub = net
        if (net == null && s.isNotEmpty() && s[0] in 'a'..'z') {
            var i = 0
            while (i < s.length && (s[i] in 'a'..'z' || s[i] in '0'..'9' || s[i] == '-')) i++
            if (i < s.length - 1 && s[i] == ':' && s[i + 1] == ' ') {
                sub = s.substring(0, i)
                s = trimWs(s.substring(i + 2))
            }
        }
        return s to sub
    }

    // ------------------------------------------------------------------ time

    private fun parseImpl(name: String, ctx: EventParseContext): ParsedName {
        val st = structure(name) ?: return ParsedName.None
        if (st.empty) return ParsedName.Empty(st.slot)
        val tail = st.tail ?: return ParsedName.None

        val source: EventZoneSource
        val zoneText: String
        val zone: ZoneId
        when {
            tail.zone != null -> {
                source = EventZoneSource.EXPLICIT; zoneText = ZONES.getValue(tail.zone); zone = ZoneId.of(zoneText)
            }
            tail.shape == EventShape.ISO && st.tag != null -> {
                source = EventZoneSource.CONVENTION; zoneText = TAG_ZONES.getValue(st.tag); zone = ZoneId.of(zoneText)
            }
            tail.shape == EventShape.PIPE_MONTH_DAY -> {
                source = EventZoneSource.CONVENTION; zoneText = NEW_YORK; zone = ZoneId.of(zoneText)
            }
            else -> {
                source = EventZoneSource.ASSUMED; zone = ctx.assumedZone; zoneText = zone.id
            }
        }

        val start: Long = if (tail.shape == EventShape.ISO) {
            instant(tail.year, tail.n1, tail.n2, tail.hour, tail.minute, tail.second, zone)
        } else {
            nearestYear(tail, zone, ctx.nowUtcMs) ?: return ParsedName.None
        }
        val end = start + EventDuration.minutes(st.title) * 60_000L
        return ParsedName.Event(st.slot, st.title, st.subtitle, start, end, source, zoneText, tail.shape)
    }

    /** §3.4: years y−1, y, y+1 (y in the event's zone at `now`), inside [now − 300 d, now + 90 d], nearest to `now`. */
    private fun nearestYear(tail: Tail, zone: ZoneId, now: Long): Long? {
        val y = Instant.ofEpochMilli(now).atZone(zone).year
        val from = now - EventConstants.EVENT_YEAR_PAST_DAYS * DAY_MS
        val to = now + EventConstants.EVENT_YEAR_FUTURE_DAYS * DAY_MS
        val dayMonth = (tail.shape == EventShape.PAREN_MONTH_DAY || tail.shape == EventShape.PIPE_MONTH_DAY) && tail.zone in EUROPEAN
        var best: Long? = null
        var bestDist = Long.MAX_VALUE
        for (reading in 0..(if (dayMonth) 1 else 0)) {
            val m = if (reading == 0) tail.n1 else tail.n2
            val d = if (reading == 0) tail.n2 else tail.n1
            if (m !in 1..12) continue
            for (yy in y - 1..y + 1) {
                if (d < 1 || d > YearMonth.of(yy, m).lengthOfMonth()) continue
                val at = instant(yy, m, d, tail.hour, tail.minute, 0, zone)
                if (at < from || at > to) continue
                val dist = if (at >= now) at - now else now - at
                if (dist < bestDist) { best = at; bestDist = dist }
            }
        }
        return best
    }

    private fun instant(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int, zone: ZoneId): Long =
        LocalDateTime.of(y, m, d, h, mi, s).atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()

    // ------------------------------------------------------------------ scanning

    private fun isWs(c: Char) = c == ' ' || c == '\t' || c == ' '

    private fun trimWs(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isWs(s[a])) a++
        while (b > a && isWs(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun collapseWs(s: String): String {
        val sb = StringBuilder(s.length)
        var pending = false
        for (c in s) {
            if (isWs(c)) {
                pending = sb.isNotEmpty()
            } else {
                if (pending) sb.append(' ')
                pending = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** A cursor over `s[pos, end)`. */
    private class Cursor(val s: String, var pos: Int, val end: Int) {
        fun atEnd() = pos >= end
        fun peekDigit() = pos < end && s[pos] in '0'..'9'
        fun peekLetter() = pos < end && s[pos].isLetter()

        fun ws(): Int {
            var n = 0
            while (pos < end && isWs(s[pos])) { pos++; n++ }
            return n
        }

        fun char(c: Char): Boolean {
            if (pos < end && s[pos] == c) { pos++; return true }
            return false
        }

        /** [min]–[max] ASCII digits, not followed by another digit. */
        fun digits(min: Int, max: Int): Int? {
            var n = 0
            var v = 0
            while (pos + n < end && s[pos + n] in '0'..'9') {
                if (n == max) return null
                v = v * 10 + (s[pos + n] - '0')
                n++
            }
            if (n < min) return null
            pos += n
            return v
        }

        fun letters(): String? {
            val b = pos
            while (pos < end && s[pos].isLetter()) pos++
            return if (pos > b) s.substring(b, pos) else null
        }

        /** A month word with an optional ".". */
        fun month(): Int? {
            val w = letters() ?: return null
            val m = MONTHS[w.lowercase()] ?: return null
            char('.')
            return m
        }

        /** A §3.3 zone word, upper case, or null when the word is not one. */
        fun zoneWord(): String? {
            val w = letters()?.uppercase() ?: return null
            return if (w in ZONES) w else null
        }

        /** `h ":" mi [ws? ampm]` as minutes of the day; "OM" reads as PM. */
        fun clock(): Int? {
            val h = digits(1, 2) ?: return null
            if (!char(':')) return null
            val mi = digits(2, 2) ?: return null
            if (mi > 59) return null
            val save = pos
            ws()
            if (pos + 2 <= end) {
                val a = s[pos].uppercaseChar()
                val m = s[pos + 1].uppercaseChar()
                val after = if (pos + 2 < end) s[pos + 2] else ' '
                if ((a == 'A' || a == 'P' || a == 'O') && m == 'M' && !after.isLetter()) {
                    pos += 2
                    if (h !in 1..12) return null
                    return ((h % 12) + if (a == 'A') 0 else 12) * 60 + mi
                }
            }
            pos = save
            if (h !in 0..23) return null
            return h * 60 + mi
        }
    }
}
