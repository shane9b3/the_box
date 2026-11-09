package app.thebox.ui.search

import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.fakes.GuideFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** U5 (code review 2026-11-09): Search's programme times follow the 12/24-hour setting. */
class SearchTimeTextTest {
    private val channel = GuideFixtures.channel(1, name = "BBC News")
    private fun min(h: Int, m: Int) = (LocalDateTime.of(2026, 11, 9, h, m).toEpochSecond(ZoneOffset.UTC) / 60).toInt()
    private val hit = ProgrammeHit(channel, GuideFixtures.info(min(19, 30), min(20, 0), "News"), min(20, 0), false)
    private val noon = min(12, 0) * 60_000L

    @Test fun twentyFourHour() =
        assertEquals("19:30 – 20:00", timeText(hit, clock24 = true, Locale.UK, ZoneOffset.UTC, noon))

    @Test fun twelveHour() =
        assertEquals("7:30 PM – 8:00 PM", timeText(hit, clock24 = false, Locale.US, ZoneOffset.UTC, noon))

    @Test fun anotherDayCarriesTheDay() =
        assertEquals("Mon 9 Nov · 7:30 PM – 8:00 PM", timeText(hit, clock24 = false, Locale.US, ZoneOffset.UTC, noon - 86_400_000L))
}
