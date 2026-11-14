package app.thebox.core.sports

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** (CHL commit 0, docs/design/m6-chl-leagues.md §2.3, §7.2) Reference zones, the 05:00 rollover and the DST days. */
class SportsDaysTest {
    private fun ms(local: String, zone: String): Long = LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun theReferenceZones() {
        assertEquals(ZoneId.of("America/Toronto"), SportsDays.zone(League.OHL))
        assertEquals(ZoneId.of("America/Toronto"), SportsDays.zone(League.QMJHL))
        assertEquals(ZoneId.of("America/Vancouver"), SportsDays.zone(League.WHL))
        assertEquals(ZoneId.of("America/New_York"), SportsDays.zone(League.NHL))
        assertEquals(ZoneId.of("America/New_York"), SportsDays.zone(League.NFL))
    }

    @Test
    fun theChlDayRollsOverAtFiveLocal() {
        for (l in listOf(League.OHL, League.QMJHL)) {
            assertEquals(LocalDate.parse("2026-11-13"), SportsDays.today(l, ms("2026-11-14T04:59:59", "America/Toronto")), "$l late game")
            assertEquals(LocalDate.parse("2026-11-14"), SportsDays.today(l, ms("2026-11-14T05:00:00", "America/Toronto")), "$l at 05:00")
            assertEquals(LocalDate.parse("2026-11-13"), SportsDays.today(l, ms("2026-11-13T12:00:00", "America/Toronto")))
        }
        // The WHL on Pacific time: 01:30 Eastern is still the evening before in Vancouver, and before 05:00 there.
        assertEquals(LocalDate.parse("2026-11-13"), SportsDays.today(League.WHL, ms("2026-11-14T04:59:00", "America/Vancouver")))
        assertEquals(LocalDate.parse("2026-11-14"), SportsDays.today(League.WHL, ms("2026-11-14T05:00:00", "America/Vancouver")))
        assertEquals(LocalDate.parse("2026-11-13"), SportsDays.today(League.WHL, ms("2026-11-14T07:00:00", "America/Toronto")), "04:00 Pacific")
    }

    @Test
    fun theNhlAndNflKeepTheEasternCalendarDate() {
        // Unchanged: SportsViewModel's `LocalDate.now(EASTERN)`, no rollover.
        assertEquals(LocalDate.parse("2026-11-14"), SportsDays.today(League.NHL, ms("2026-11-14T00:00:00", "America/New_York")))
        assertEquals(LocalDate.parse("2026-11-13"), SportsDays.today(League.NHL, ms("2026-11-13T23:59:59", "America/New_York")))
        assertEquals(LocalDate.parse("2026-11-14"), SportsDays.today(League.NFL, ms("2026-11-14T00:30:00", "America/New_York")))
    }

    @Test
    fun theRolloverStaysAtFiveLocalOnTheDstDays() {
        // DST ends on the first Sunday of November (2026-11-01: 02:00 EDT → 01:00 EST) and starts on the second Sunday of
        // March (2027-03-14: 02:00 EST → 03:00 EDT). The day still turns over at 05:00 on the wall clock.
        for ((zone, l) in listOf("America/Toronto" to League.OHL, "America/Vancouver" to League.WHL)) {
            assertEquals(LocalDate.parse("2026-10-31"), SportsDays.today(l, ms("2026-11-01T04:30:00", zone)), "$l fall back, 04:30")
            assertEquals(LocalDate.parse("2026-11-01"), SportsDays.today(l, ms("2026-11-01T05:00:00", zone)), "$l fall back, 05:00")
            assertEquals(LocalDate.parse("2027-03-13"), SportsDays.today(l, ms("2027-03-14T04:59:00", zone)), "$l spring forward, 04:59")
            assertEquals(LocalDate.parse("2027-03-14"), SportsDays.today(l, ms("2027-03-14T05:00:00", zone)), "$l spring forward, 05:00")
        }
    }
}
