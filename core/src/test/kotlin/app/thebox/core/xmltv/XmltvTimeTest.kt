package app.thebox.core.xmltv

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class XmltvTimeTest {
    private fun min(iso: String): Int = (OffsetDateTime.parse(iso).toEpochSecond() / 60).toInt()
    private fun utc(s: String) = XmltvTime.parseMinutes(s, XmltvTime.UTC)

    private val six = min("2026-10-13T18:00:00Z")

    @Test
    fun offsetForms() {
        assertEquals(six, utc("20261013190000 +0100"))
        assertEquals(six, utc("20261013130000 -0500"))
        assertEquals(six, utc("20261013233000 +05:30"))
        assertEquals(six, utc("20261013234500 +0545"))
        assertEquals(six, utc("20261013190000+0100"))
        assertEquals(six, utc("20261013200000 +02"))
        assertEquals(six, utc("20261013180000Z"))
        assertEquals(six, utc("20261013180000 Z"))
        assertEquals(six, utc("20261013180000 GMT"))
        assertEquals(six, utc("20261013180000 UTC"))
        assertEquals(six, utc("20261013180000 UT"))
        assertEquals(six, utc("20261013190000 GMT+01:00"))
        assertEquals(six, utc("20261013130000 EST"))
        assertEquals(six, utc("20261013140000 EDT"))
        assertEquals(six, utc("20261013110000 PDT"))
        assertEquals(six, utc("20261013200000 CEST"))
        assertEquals(six, utc("20261013210000 MSK"))
        assertEquals(six, utc("20261013180000 +0000"))
        assertEquals(six, utc("  20261013180000   +0000  "))
    }

    @Test
    fun shortFormsAndSecondsAreFloored() {
        assertEquals(six, utc("202610131800"))
        assertEquals(six, utc("2026101318"))
        assertEquals(min("2026-10-13T00:00:00Z"), utc("20261013"))
        assertEquals(six, utc("20261013180059 +0000"))
        assertEquals(six, utc("20261013180059.999 +0000"))
    }

    @Test
    fun badTimes() {
        for (s in listOf("", "abc", "2026101", "202610131", "2026131319", "20261013250000", "20261032180000", "20261013186000", "18991231000000")) {
            assertEquals(XmltvTime.BAD, utc(s), s)
        }
    }

    @Test
    fun unknownOffsetFallsBackToAssumedZone() {
        // "XYZ" is not in the abbreviation table: treated as missing.
        val london = XmltvTime.resolverFor(ZoneId.of("Europe/London"))
        assertEquals(min("2026-10-13T17:00:00Z"), XmltvTime.parseMinutes("20261013180000 XYZ", london))
        assertEquals(min("2026-10-13T17:00:00Z"), XmltvTime.parseMinutes("20261013180000", london))
        assertEquals(six, XmltvTime.parseMinutes("20261013180000 +0000", london))
    }

    @Test
    fun dstOverlapAndGapFollowJavaTime() {
        val london = XmltvTime.resolverFor(ZoneId.of("Europe/London"))
        // Autumn: 01:00–02:00 local happens twice on 2026-10-25; the earlier offset (BST) wins.
        assertEquals(min("2026-10-24T23:30:00Z"), XmltvTime.parseMinutes("20261025003000", london))
        assertEquals(min("2026-10-25T00:30:00Z"), XmltvTime.parseMinutes("20261025013000", london))
        assertEquals(min("2026-10-25T02:30:00Z"), XmltvTime.parseMinutes("20261025023000", london))
        // Spring: 01:00–02:00 local does not exist on 2026-03-29; the offset before the gap (GMT) is used.
        assertEquals(min("2026-03-29T01:30:00Z"), XmltvTime.parseMinutes("20260329013000", london))
        assertEquals(min("2026-03-29T01:00:00Z"), XmltvTime.parseMinutes("20260329020000", london))
    }

    @Test
    fun fixedOffsetZoneAndUtcDefault() {
        assertEquals(XmltvTime.UTC, XmltvTime.resolverFor(null))
        assertEquals(XmltvTime.UTC, XmltvTime.resolverFor(ZoneOffset.UTC))
        val plus2 = XmltvTime.resolverFor(ZoneOffset.ofHours(2))
        assertEquals(min("2026-10-13T16:00:00Z"), XmltvTime.parseMinutes("20261013180000", plus2))
    }

    @Test
    fun daysFromCivilMatchesJavaTime() {
        for (y in listOf(1900, 1969, 1970, 2000, 2024, 2100, 4000)) {
            for (m in 1..12) {
                assertEquals(java.time.LocalDate.of(y, m, 1).toEpochDay(), XmltvTime.daysFromCivil(y, m, 1), "$y-$m")
            }
        }
    }

    /** Property test (§5.3): 100k random timestamps 1970–2100 in every offset form, against java.time. */
    @Test
    fun randomTimestampsAgreeWithJavaTime() {
        val rnd = Random(42)
        val from = LocalDateTime.of(1970, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)
        val to = LocalDateTime.of(2100, 12, 31, 23, 59).toEpochSecond(ZoneOffset.UTC)
        val london = ZoneId.of("Europe/London")
        val londonResolver = XmltvTime.resolverFor(london)
        repeat(100_000) { i ->
            val epochSec = from + (rnd.nextDouble() * (to - from)).toLong()
            val offMin = (rnd.nextInt(27 * 4) - 12 * 4) * 15
            val local = LocalDateTime.ofEpochSecond(epochSec, 0, ZoneOffset.ofTotalSeconds(offMin * 60))
            val digits = "%04d%02d%02d%02d%02d%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second)
            val a = kotlin.math.abs(offMin)
            val sign = if (offMin < 0) '-' else '+'
            val off = when (i % 3) {
                0 -> " %c%02d%02d".format(sign, a / 60, a % 60)
                1 -> "%c%02d%02d".format(sign, a / 60, a % 60)
                else -> " %c%02d:%02d".format(sign, a / 60, a % 60)
            }
            val expected = Math.floorDiv(local.toEpochSecond(ZoneOffset.ofTotalSeconds(offMin * 60)), 60L).toInt()
            assertEquals(expected, XmltvTime.parseMinutes(digits + off, XmltvTime.UTC), digits + off)
            if (i % 10 == 0) {
                val viaRules = LocalDateTime.ofEpochSecond(Math.floorDiv(local.toEpochSecond(ZoneOffset.UTC), 60L) * 60, 0, ZoneOffset.UTC)
                val expectedLocal = Math.floorDiv(viaRules.toEpochSecond(london.rules.getOffset(viaRules)), 60L).toInt()
                assertEquals(expectedLocal, XmltvTime.parseMinutes(digits, londonResolver), digits)
            }
        }
    }
}
