package app.thebox.core.catchup

import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamStreamRef
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatchupUrlBuilderTest {
    private val b = CatchupUrlBuilderImpl()
    private val start = Instant.parse("2026-10-13T19:30:00Z")
    private val now = Instant.parse("2026-10-13T22:00:00Z")
    private val window = ArchiveWindow(start, start.plusSeconds(3600), deviceNow = now)
    private val ref = XtreamStreamRef("http://panel.example:8080", "user", "pass", 101, "ts")
    private val xcLive = "http://panel.example:8080/live/user/pass/101.ts"

    private fun target(mode: CatchupMode, live: String = xcLive, source: String? = null, xtream: XtreamStreamRef? = ref, days: Int = 7, corr: Double? = null) =
        CatchupTarget(live, emptyMap(), Catchup(mode, days, source, corr), xtream)

    private fun ok(build: CatchupBuild): ArchiveRequest = assertIs<CatchupBuild.Ok>(build, "$build").request
    private fun unsupported(build: CatchupBuild): String = assertIs<CatchupBuild.Unsupported>(build, "$build").reason

    @Test
    fun variantOrder() {
        assertEquals(listOf("xc-path", "xc-php", "xc-path-sec"), b.variants(target(CatchupMode.XC)))
        assertEquals(listOf("xc-php", "xc-path", "xc-path-sec"), b.variants(target(CatchupMode.XC), winner = "xc-php"))
        assertEquals(listOf("xc-path", "xc-php", "xc-path-sec"), b.variants(target(CatchupMode.XC), winner = "flussonic-abs"), "winner of another mode ignored")
        assertEquals(listOf("flussonic-range", "flussonic-abs", "flussonic-kodi"), b.variants(target(CatchupMode.FLUSSONIC_TS)))
        assertEquals(listOf("flussonic-kodi", "flussonic-range", "flussonic-abs"), b.variants(target(CatchupMode.FLUSSONIC), "flussonic-kodi"))
        assertEquals(listOf("default"), b.variants(target(CatchupMode.DEFAULT, source = "http://a/{utc}")))
        assertEquals(listOf("append"), b.variants(target(CatchupMode.APPEND, source = "?u={utc}")))
        assertEquals(listOf("vod"), b.variants(target(CatchupMode.VOD, source = "http://v/{catchup-id}")))
        // S17: shift on an Xtream target can fall back to the Xtream endpoints.
        assertEquals(listOf("shift", "xc-path", "xc-php"), b.variants(target(CatchupMode.SHIFT)))
        assertEquals(listOf("shift"), b.variants(target(CatchupMode.SHIFT, xtream = null)))
        assertEquals(emptyList(), b.variants(target(CatchupMode.OFF)))
    }

    @Test
    fun absoluteSourceOverridesTheDerivation() {
        val t = target(CatchupMode.XC, source = "http://override.example/{utc}.ts")
        assertEquals(listOf("default", "xc-path", "xc-php", "xc-path-sec"), b.variants(t))
        assertEquals("http://override.example/1791919800.ts", ok(b.build(t, TimeBase.DEFAULT, window)).url)
        val fl = target(CatchupMode.FLUSSONIC, live = "http://fl/ch/index.m3u8", source = "https://x/{utc}")
        assertEquals(listOf("default", "flussonic-range", "flussonic-abs", "flussonic-kodi"), b.variants(fl))
        // A relative source does not override.
        assertEquals(listOf("xc-path", "xc-php", "xc-path-sec"), b.variants(target(CatchupMode.XC, source = "?utc={utc}")))
    }

    @Test
    fun unsupportedReasons() {
        val tb = TimeBase(serverZone = ZoneOffset.UTC)
        assertEquals("catch-up is off for this channel", unsupported(b.build(target(CatchupMode.OFF), tb, window)))
        assertTrue(unsupported(b.build(target(CatchupMode.XC, xtream = null), tb, window)).contains("Xtream"))
        assertTrue(unsupported(b.build(target(CatchupMode.FLUSSONIC, live = "http://cdn/hls/ch1/playlist"), tb, window)).contains("Flussonic"))
        assertEquals("no catchup-id", unsupported(b.build(target(CatchupMode.VOD, source = "http://v/{catchup-id}"), tb, window)))
        val old = ArchiveWindow(now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(8)).plusSeconds(3600), deviceNow = now)
        assertTrue(unsupported(b.build(target(CatchupMode.XC), tb, old)).contains("older"))
        val future = ArchiveWindow(now.plusSeconds(600), now.plusSeconds(4200), deviceNow = now)
        assertTrue(unsupported(b.build(target(CatchupMode.XC), tb, future)).contains("not started"))
        assertTrue(unsupported(b.build(target(CatchupMode.XC), tb, window, "flussonic-abs")).contains("does not apply"))
        assertTrue(unsupported(b.build(target(CatchupMode.FLUSSONIC, live = "http://fl/ch/index.mpd"), tb, window, "flussonic-abs")).contains("DASH"))
    }

    @Test
    fun paddingAcrossTheArchiveEdgeIsClamped() {
        // Programme starts 30 s inside the 1-day window; 60 s of padding would cross it (S17).
        val edgeStart = now.minus(Duration.ofDays(1)).plusSeconds(30)
        val w = ArchiveWindow(edgeStart, edgeStart.plusSeconds(1800), deviceNow = now)
        val tb = TimeBase(serverZone = ZoneOffset.UTC, paddingBefore = Duration.ofSeconds(60))
        val req = ok(b.build(target(CatchupMode.SHIFT, live = "http://h/ch.m3u8", xtream = null, days = 1), tb, w))
        assertEquals(now.minus(Duration.ofDays(1)).plusSeconds(60), req.requestStart)
        // ...but a programme whose unpadded corrected start is outside the window is rejected.
        val outside = ArchiveWindow(now.minus(Duration.ofDays(1)).minusSeconds(30), now, deviceNow = now)
        assertIs<CatchupBuild.Unsupported>(b.build(target(CatchupMode.SHIFT, live = "http://h/ch.m3u8", xtream = null, days = 1), tb, outside))
    }

    @Test
    fun channelCorrectionOverridesThePlaylist() {
        val tb = TimeBase(serverZone = ZoneOffset.UTC, correctionHours = 2.0)
        val playlist = ok(b.build(target(CatchupMode.SHIFT, live = "http://h/c.m3u8", xtream = null), tb, window))
        assertEquals(start.minus(Duration.ofHours(2)), playlist.requestStart)
        val channel = ok(b.build(target(CatchupMode.SHIFT, live = "http://h/c.m3u8", xtream = null, corr = -4.0), tb, window))
        assertEquals(start.plus(Duration.ofHours(4)), channel.requestStart)
        assertEquals("http://h/c.m3u8?utc=1791934200&lutc=1791928800", channel.url)
    }

    @Test
    fun b1PanelZoneForXcWhileTemplatesStayUtc() {
        // Xtream-shaped M3U with server_zone = Asia/Tokyo and Auto token zone (§5.3 review addition).
        val tb = TimeBase(serverZone = ZoneId.of("Asia/Tokyo"), templateZone = ZoneOffset.UTC)
        assertEquals(
            "http://panel.example:8080/timeshift/user/pass/60/2026-10-14:04-30/101.ts",
            ok(b.build(target(CatchupMode.XC), tb, window)).url,
        )
        val tpl = target(CatchupMode.DEFAULT, source = "http://a/{Y}-{m}-{d}/{H}{M}.ts")
        assertEquals("http://a/2026-10-13/1930.ts", ok(b.build(tpl, tb, window)).url)
    }

    @Test
    fun xcWithUnknownZoneUsesUtc() {
        assertEquals(
            "http://panel.example:8080/timeshift/user/pass/60/2026-10-13:19-30/101.ts",
            ok(b.build(target(CatchupMode.XC), TimeBase.DEFAULT, window)).url,
        )
    }

    @Test
    fun hlsXcIsNativelySeekable() {
        val req = ok(b.build(target(CatchupMode.XC, xtream = ref.copy(extension = "m3u8")), TimeBase.DEFAULT, window))
        assertEquals(1_791_919_800L..1_791_923_400L, req.nativeSeekRange)
        assertEquals(CatchupMode.XC, req.mode)
        assertEquals("xc-path", req.variant)
    }

    @Test
    fun relativeSources() {
        val live = "http://cdn.example/hls/ch1/index.m3u8?token=abc"
        assertEquals("http://cdn.example/a/b", CatchupUrlBuilderImpl.resolveAgainst(live, "/a/b"))
        assertEquals("http://cdn.example/hls/ch1/x.m3u8", CatchupUrlBuilderImpl.resolveAgainst(live, "x.m3u8"))
        assertEquals("http://cdn.example/hls/ch1/index.m3u8?u=1", CatchupUrlBuilderImpl.resolveAgainst(live, "?u=1"))
        assertEquals("http://other/x", CatchupUrlBuilderImpl.resolveAgainst(live, "//other/x"))
        assertEquals("https://abs/x", CatchupUrlBuilderImpl.resolveAgainst(live, "https://abs/x"))
    }
}
