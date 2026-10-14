package app.thebox.core.guide.state

import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.TimeLabels
import app.thebox.core.guide.fakes.FakeTimeLabels
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §3.3.4, §3.5.1 (N1 DST day). */
class GuideLimitsTest {
    private val utc = GuideLimits(FakeTimeLabels())
    private val now = 29_866_787 // 2026-10-14 19:47Z

    @Test fun earliestIsLocalMidnightOfMaxPastAndArchive() {
        val e = utc.earliestMin(now, archiveDaysUpperBound = 7, window = GuideDataWindow(pastDays = 2))
        assertEquals(now - 19 * 60 - 47 - 7 * 1440, e)
        assertEquals(now - 19 * 60 - 47 - 2 * 1440, utc.earliestMin(now, 0, GuideDataWindow(pastDays = 2)))
    }

    @Test fun earliestRaisedToGenerationStart() {
        val e = utc.earliestMin(now, 7, GuideDataWindow(), generationFromMin = now - 100)
        assertEquals(now - 107, e) // floor30(19:47 − 1:40 = 18:07) = 18:00
    }

    @Test fun latestFutureDaysLoweredToGenerationNeverBelowNowPlus180() {
        assertEquals(now + 7 * 1440, utc.latestMin(now, GuideDataWindow(futureDays = 7)))
        assertEquals(now + 1000, utc.latestMin(now, GuideDataWindow(futureDays = 7), generationToMin = now + 1000))
        assertEquals(now + 180, utc.latestMin(now, GuideDataWindow(futureDays = 7), generationToMin = now + 10))
    }

    @Test fun clampWs() {
        val e = now - 1000; val l = now + 1000
        assertEquals(utc.floor30(e), utc.clampWs(e - 5000, e, l))
        assertEquals(utc.ceil30(l) - 180, utc.clampWs(l, e, l))
        assertEquals(utc.nowWindow(now), utc.clampWs(utc.nowWindow(now), e, l))
        assertEquals(now - 17 - 30, utc.nowWindow(now)) // 19:00
    }

    @Test fun daysStripWithTonightAndCoverage() {
        val e = utc.earliestMin(now, 0, GuideDataWindow(pastDays = 2))
        val l = utc.latestMin(now, GuideDataWindow(futureDays = 2))
        val items = utc.days(now, e, l, coverFrom = now - 1440, coverTo = now + 1440)
        assertEquals(listOf("D20738", "Yesterday", "Today", "Tonight 20:00", "Tomorrow", "D20742"), items.map { it.label })
        assertEquals(listOf(false, true, true, true, true, false), items.map { it.enabled })
        assertTrue(items[3].primeTime)
        assertEquals(now + 13, utc.dayTarget(items[3], now - 500, now))
        assertEquals(now, utc.dayTarget(items[2], now - 500, now))
        assertEquals(now - 500 + 1440, utc.dayTarget(items[4], now - 500, now))
    }

    @Test fun dayJumpKeepsClockTimeAcrossDst() {
        // Europe/London, autumn change on 2026-10-25: 25 h day. Jumping from Sat 24 Oct 21:00 to Sun 25 Oct keeps 21:00.
        val labels = ZoneLabels(ZoneId.of("Europe/London"))
        val l = GuideLimits(labels)
        val sat = minOf(LocalDate.of(2026, 10, 24), LocalTime.of(21, 0), labels.zone)
        val sun = minOf(LocalDate.of(2026, 10, 25), LocalTime.of(21, 0), labels.zone)
        assertEquals(sun, l.atDayKeepingTime(labels.dayOf(sat) + 1, sat))
        assertEquals(sun - sat, 25 * 60, "the naive +1440 would be an hour off (N1)")
        // spring: 2026-03-29 is a 23 h day
        val fri = minOf(LocalDate.of(2026, 3, 28), LocalTime.of(8, 30), labels.zone)
        val sunSpring = minOf(LocalDate.of(2026, 3, 29), LocalTime.of(8, 30), labels.zone)
        assertEquals(sunSpring, l.atDayKeepingTime(labels.dayOf(fri) + 1, fri))
    }

    private fun minOf(d: LocalDate, t: LocalTime, z: ZoneId): Int = (ZonedDateTime.of(d, t, z).toEpochSecond() / 60).toInt()

    /** Real-zone labels (only the members GuideLimits uses). */
    private class ZoneLabels(val zone: ZoneId) : TimeLabels {
        private fun zdt(min: Int) = Instant.ofEpochSecond(min * 60L).atZone(zone)
        override fun hm(min: Int) = zdt(min).toLocalTime().toString()
        override fun minuteOfDay(min: Int) = zdt(min).let { it.hour * 60 + it.minute }
        override fun dayOf(min: Int) = zdt(min).toLocalDate().toEpochDay().toInt()
        override fun dayLabel(day: Int) = LocalDate.ofEpochDay(day.toLong()).toString()
        override fun contextLabel(windowStartMin: Int, nowMin: Int) = ""
        override fun duration(minutes: Int) = "$minutes min"
        override fun reset(zone: ZoneId, clock24: Boolean) = Unit
    }
}
