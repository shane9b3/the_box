package app.thebox.core.catchup

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `Y m d H M S` formatting of an instant in a zone (docs/design/m1-data-layer.md §3.8.4). In a pattern, the six
 * letters are replaced by zero-padded fields (4, 2, 2, 2, 2, 2 digits); every other character is literal.
 */
object TokenFormatter {
    /** Xtream timeshift segment, minute precision. */
    const val XC_MINUTES = "Y-m-d:H-M"

    /** Xtream timeshift segment, second precision (`xc-path-sec`). */
    const val XC_SECONDS = "Y-m-d:H-M-S"

    fun format(epochSec: Long, zone: ZoneId, pattern: String): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone)
        val sb = StringBuilder(pattern.length + 8)
        for (c in pattern) {
            when (c) {
                'Y' -> pad(sb, t.year, 4)
                'm' -> pad(sb, t.monthValue, 2)
                'd' -> pad(sb, t.dayOfMonth, 2)
                'H' -> pad(sb, t.hour, 2)
                'M' -> pad(sb, t.minute, 2)
                'S' -> pad(sb, t.second, 2)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** One field (`Y`, `m`, `d`, `H`, `M` or `S`). */
    fun field(epochSec: Long, zone: ZoneId, letter: Char): String = format(epochSec, zone, letter.toString())

    /** ISO-8601 with a numeric offset, in [zone]: `2026-10-13T20:30:00+01:00`. */
    fun iso(epochSec: Long, zone: ZoneId): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone)
        val sb = StringBuilder(25)
        sb.append(format(epochSec, zone, "Y-m-dTH:M:S"))
        val total = t.offset.totalSeconds
        sb.append(if (total < 0) '-' else '+')
        val abs = Math.abs(total)
        pad(sb, abs / 3600, 2)
        sb.append(':')
        pad(sb, (abs / 60) % 60, 2)
        return sb.toString()
    }

    private fun pad(sb: StringBuilder, v: Int, width: Int) {
        val s = v.toString()
        for (i in s.length until width) sb.append('0')
        sb.append(s)
    }
}
