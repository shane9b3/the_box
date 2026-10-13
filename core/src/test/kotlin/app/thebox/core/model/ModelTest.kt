package app.thebox.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun catchupModeParsesM3uAttribute() {
        assertEquals(CatchupMode.FLUSSONIC, CatchupMode.fromM3uAttribute("fs"))
        assertEquals(CatchupMode.SHIFT, CatchupMode.fromM3uAttribute(" Shift "))
        assertEquals(CatchupMode.OFF, CatchupMode.fromM3uAttribute(null))
    }

    @Test
    fun programmeTimeHelpers() {
        val start = Instant.parse("2026-10-13T19:30:00Z")
        val p = Programme("bbc1.uk", start, start.plusSeconds(30 * 60), "News")
        assertEquals(30, p.durationMinutes)
        assertEquals(start.epochSecond / 60, p.startMin)
        assertTrue(p.isAiringAt(start))
        assertFalse(p.isAiringAt(p.stop))
    }

    @Test
    fun stableKeyFactoriesArePrefixed() {
        assertEquals("xc:42", StableKey.xtream(42).value)
        assertEquals("ng:BBC One|UK", StableKey.nameGroup("BBC One", "UK").value)
    }

    @Test
    fun catchupCorrectionIsSubtracted() {
        val start = Instant.parse("2026-10-13T19:30:00Z")
        // -4.0 means the request is 4 h later (Kodi's code, master plan §3).
        assertEquals(Instant.parse("2026-10-13T23:30:00Z"), TimeBase(correctionHours = -4.0).archiveStart(start))
        // Per-channel correction overrides the playlist value; padding widens the window.
        val tb = TimeBase(correctionHours = 2.0, paddingBefore = Duration.ofMinutes(5), paddingAfter = Duration.ofMinutes(10))
        assertEquals(Instant.parse("2026-10-13T18:55:00Z"), tb.archiveStart(start, channelCorrectionHours = 0.5))
        assertEquals(Instant.parse("2026-10-13T17:40:00Z"), tb.archiveStop(start))
    }

    @Test
    fun tokenZoneDefaultsToPanelZoneNeverDevice() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(tokyo, TimeBase(serverZone = tokyo).effectiveTokenZone)
        assertEquals(ZoneId.of("UTC"), TimeBase(serverZone = tokyo, tokenZone = ZoneId.of("UTC")).effectiveTokenZone)
        assertEquals(ZoneOffset.ofHours(3), TimeBase(serverOffset = ZoneOffset.ofHours(3)).effectiveTokenZone)
        assertNull(TimeBase.DEFAULT.effectiveTokenZone)
    }

    @Test
    fun serverOffsetFromTimeNowSnapsToQuarterHour() {
        // Panel wall clock 21:30:07 (UTC+3) while timestamp_now is 18:30:00Z (7 s of response lag).
        val ts = Instant.parse("2026-10-13T18:30:00Z").epochSecond
        assertEquals(ZoneOffset.ofHours(3), TimeBase.offsetFromServerInfo(LocalDateTime.parse("2026-10-13T21:30:07"), ts))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), TimeBase.offsetFromServerInfo(LocalDateTime.parse("2026-10-14T00:15:00"), ts))
    }

    @Test
    fun flussonicVariantsAndXtreamAliases() {
        assertEquals(CatchupMode.FLUSSONIC_HLS, CatchupMode.fromM3uAttribute("flussonic-hls"))
        assertEquals(CatchupMode.FLUSSONIC_TS, CatchupMode.fromM3uAttribute("Flussonic-TS"))
        assertEquals(CatchupMode.XC, CatchupMode.fromM3uAttribute("xtream-codes"))
        assertEquals(CatchupMode.SHIFT, CatchupMode.fromM3uAttribute("timeshift"))
        assertTrue(CatchupMode.FLUSSONIC_TS.isFlussonic)
        assertFalse(CatchupMode.XC.isFlussonic)
    }

    @Test
    fun catchupAvailabilityAndDaysSource() {
        assertFalse(Catchup.NONE.isAvailable)
        assertNull(Catchup.NONE.daysSource)
        val c = Catchup(CatchupMode.XC, days = 3, daysSource = ArchiveDaysSource.XTREAM_DURATION)
        assertTrue(c.isAvailable)
        assertFalse(Catchup(CatchupMode.XC, days = 0).isAvailable)
    }

    @Test
    fun stableKeyM1Additions() {
        val k = StableKey.xtream(42)
        assertEquals(k, k.withOccurrence(1))
        assertEquals("xc:42~2", k.withOccurrence(2).value)
        assertEquals("xcm:7", StableKey.xtreamMovie(7).value)
        assertEquals("xce:9", StableKey.xtreamEpisode(9).value)
    }

    @Test
    fun templateZoneNeverOverridesPanelZoneForXc() {
        // Review blocker B1: M3U Auto sets templateZone = UTC; the xc segment still uses the panel zone.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val utc = ZoneId.of("UTC")
        val tb = TimeBase(serverZone = tokyo, templateZone = utc)
        assertEquals(tokyo, tb.effectiveTokenZone)
        assertEquals(utc, tb.effectiveTemplateZone)
        // An explicit tokenZone wins for both.
        assertEquals(utc, tb.copy(tokenZone = utc, templateZone = null).effectiveTokenZone)
        // No template zone: template tokens fall back to the panel zone.
        assertEquals(tokyo, TimeBase(serverZone = tokyo).effectiveTemplateZone)
        assertNull(TimeBase.DEFAULT.effectiveTemplateZone)
    }
}
