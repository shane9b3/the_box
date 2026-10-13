package app.thebox.core.catchup

import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamServerInfo
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeBaseFactoryTest {
    private val t0 = 1_791_919_800L // 2026-10-13T19:30:00Z
    private val factory = TimeBaseFactoryImpl(deviceZone = { ZoneId.of("Australia/Sydney") })

    private fun info(tz: String?, timeNow: String?, ts: Long? = t0, measuredMs: Long = t0 * 1000) = XtreamAccountInfo(
        auth = true,
        status = AccountStatus.ACTIVE,
        server = XtreamServerInfo(timezone = tz, timestampNow = ts, timeNow = timeNow),
        measuredAtDeviceMs = measuredMs,
    )

    @Test
    fun ianaZone() {
        val tb = factory.fromAccount(PlaylistType.XTREAM, info("Europe/Berlin", "2026-10-13 21:30:00"), TimeBaseSettings())
        assertEquals(ZoneId.of("Europe/Berlin"), tb.serverZone)
        assertEquals(ZoneOffset.ofHours(2), tb.serverOffset)
        assertEquals(ZoneId.of("Europe/Berlin"), tb.effectiveTokenZone)
        assertNull(tb.templateZone)
        assertEquals(ZoneId.of("Europe/Berlin"), tb.effectiveTemplateZone)
        assertEquals(Duration.ZERO, tb.clockSkew)
        assertEquals(Duration.ofSeconds(60), tb.paddingBefore)
        assertEquals(Duration.ofSeconds(300), tb.paddingAfter)
        assertEquals(0.0, tb.correctionHours)
    }

    @Test
    fun nonIanaUsesTheMeasuredOffset() {
        val tb = factory.fromAccount(PlaylistType.XTREAM, info("UTC+3", "2026-10-13 22:30:00"), TimeBaseSettings())
        assertNull(tb.serverZone)
        assertEquals(ZoneOffset.ofHours(3), tb.serverOffset)
        assertEquals(ZoneOffset.ofHours(3), tb.effectiveTokenZone)
        // Kathmandu +05:45 survives the 15-minute snap; a few seconds of jitter do not matter.
        val ktm = factory.fromAccount(PlaylistType.XTREAM, info("GMT+5:45", "2026-10-14 01:15:07"), TimeBaseSettings())
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), ktm.serverOffset)
    }

    @Test
    fun zoneValidation() {
        assertEquals(ZoneId.of("UTC"), TimeBaseFactoryImpl.validZone("UTC"))
        assertEquals(ZoneId.of("Etc/UTC"), TimeBaseFactoryImpl.validZone(" Etc/UTC "))
        assertEquals(ZoneId.of("America/New_York"), TimeBaseFactoryImpl.validZone("America/New_York"))
        assertNull(TimeBaseFactoryImpl.validZone("UTC+3"))
        assertNull(TimeBaseFactoryImpl.validZone("+03:00"))
        assertNull(TimeBaseFactoryImpl.validZone("EST"))
        assertNull(TimeBaseFactoryImpl.validZone("Mars/Olympus"))
        assertNull(TimeBaseFactoryImpl.validZone(""))
        assertNull(TimeBaseFactoryImpl.validZone(null))
    }

    @Test
    fun missingEverything() {
        val tb = factory.fromAccount(PlaylistType.XTREAM, info(null, null, ts = null), TimeBaseSettings())
        assertNull(tb.serverZone)
        assertNull(tb.serverOffset)
        assertNull(tb.effectiveTokenZone)
        assertEquals(Duration.ZERO, tb.clockSkew)
    }

    @Test
    fun staleTzdataFallsBackToTheOffset() {
        // Zone says CEST (+2) but the panel wall clock says +1.
        val tb = factory.fromAccount(PlaylistType.XTREAM, info("Europe/Berlin", "2026-10-13 20:30:00"), TimeBaseSettings())
        assertNull(tb.serverZone)
        assertEquals(ZoneOffset.ofHours(1), tb.effectiveTokenZone)
    }

    @Test
    fun skewBounds() {
        val ahead = factory.fromAccount(PlaylistType.XTREAM, info("UTC", "2026-10-13 19:30:00", measuredMs = (t0 - 95) * 1000 + 400), TimeBaseSettings())
        assertEquals(Duration.ofSeconds(95), ahead.clockSkew)
        val behind = factory.fromAccount(PlaylistType.XTREAM, info("UTC", "2026-10-13 19:30:00", measuredMs = (t0 + 3600) * 1000), TimeBaseSettings())
        assertEquals(Duration.ofSeconds(-3600), behind.clockSkew)
        val absurd = factory.fromAccount(PlaylistType.XTREAM, info("UTC", "2026-10-13 19:30:00", measuredMs = (t0 - 2 * 86400) * 1000), TimeBaseSettings())
        assertEquals(Duration.ZERO, absurd.clockSkew)
    }

    @Test
    fun tokenZoneSettings() {
        val i = info("Europe/Berlin", "2026-10-13 21:30:00")
        fun tz(s: TokenZoneSetting) = factory.fromAccount(PlaylistType.XTREAM, i, TimeBaseSettings(tokenZone = s)).tokenZone
        assertNull(tz(TokenZoneSetting.Auto))
        assertNull(tz(TokenZoneSetting.Server))
        assertEquals(ZoneOffset.UTC, tz(TokenZoneSetting.Utc))
        assertEquals(ZoneId.of("Australia/Sydney"), tz(TokenZoneSetting.Device))
        assertEquals(ZoneId.of("Asia/Tokyo"), tz(TokenZoneSetting.Fixed("Asia/Tokyo")))
        assertNull(tz(TokenZoneSetting.Fixed("Not/AZone")))
    }

    @Test
    fun b1XtreamShapedM3uKeepsThePanelZoneForXcAndUtcForTemplates() {
        val tokyo = info("Asia/Tokyo", "2026-10-14 04:30:00")
        val tb = factory.fromAccount(PlaylistType.M3U_URL, tokyo, TimeBaseSettings())
        assertEquals(ZoneId.of("Asia/Tokyo"), tb.effectiveTokenZone)
        assertEquals(ZoneOffset.UTC, tb.effectiveTemplateZone)
        // An explicit Server choice makes templates follow the panel too.
        val server = factory.fromAccount(PlaylistType.M3U_URL, tokyo, TimeBaseSettings(tokenZone = TokenZoneSetting.Server))
        assertEquals(ZoneId.of("Asia/Tokyo"), server.effectiveTemplateZone)
    }

    @Test
    fun forM3uAndCorrection() {
        val tb = factory.forM3u(TimeBaseSettings(headerCorrectionHours = -1.5, paddingBeforeSec = 0, paddingAfterSec = 0))
        assertNull(tb.effectiveTokenZone)
        assertEquals(ZoneOffset.UTC, tb.effectiveTemplateZone)
        assertEquals(-1.5, tb.correctionHours)
        assertEquals(Duration.ZERO, tb.paddingBefore)
        val user = factory.forM3u(TimeBaseSettings(correctionHours = 2.0, headerCorrectionHours = -1.5))
        assertEquals(2.0, user.correctionHours)
        val device = factory.forM3u(TimeBaseSettings(tokenZone = TokenZoneSetting.Device))
        assertEquals(ZoneId.of("Australia/Sydney"), device.effectiveTemplateZone)
    }
}
