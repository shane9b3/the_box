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
        assertEquals(listOf("xc-php", "xc-path", "xc-path-sec"), b.variants(target(CatchupMode.XC).copy(winner = "xc-php")))
        assertEquals(listOf("xc-path", "xc-php", "xc-path-sec"), b.variants(target(CatchupMode.XC).copy(winner = "flussonic-abs")), "winner of another mode ignored")
        assertEquals(listOf("flussonic-range", "flussonic-abs", "flussonic-kodi"), b.variants(target(CatchupMode.FLUSSONIC_TS)))
        assertEquals(listOf("flussonic-kodi", "flussonic-range", "flussonic-abs"), b.variants(target(CatchupMode.FLUSSONIC).copy(winner = "flussonic-kodi")))
        assertEquals(listOf("default"), b.variants(target(CatchupMode.DEFAULT, source = "http://a/{utc}")))
        assertEquals(listOf("append"), b.variants(target(CatchupMode.APPEND, source = "?u={utc}")))
        assertEquals(listOf("vod"), b.variants(target(CatchupMode.VOD, source = "http://v/{catchup-id}")))
        // S17: shift on an Xtream target can fall back to the Xtream endpoints.
        assertEquals(listOf("shift", "xc-path", "xc-php"), b.variants(target(CatchupMode.SHIFT)))
        assertEquals(listOf("shift"), b.variants(target(CatchupMode.SHIFT, xtream = null)))
        assertEquals(emptyList(), b.variants(target(CatchupMode.OFF)))
    }

    @Test
    fun persistedWinnerIsTheDefaultVariant() {
        // (amended 2026-10-13) CatchupTarget.winner drives both the probe order and build()'s default.
        val tb = TimeBase(serverZone = ZoneOffset.UTC)
        assertEquals("xc-path", ok(b.build(target(CatchupMode.XC), tb, window)).variant)
        assertEquals("xc-php", ok(b.build(target(CatchupMode.XC).copy(winner = "xc-php"), tb, window)).variant)
        assertEquals("xc-path", ok(b.build(target(CatchupMode.XC).copy(winner = "flussonic-abs"), tb, window)).variant)
    }

    @Test
    fun diagnosticsReachTheCaller() {
        // (amended 2026-10-13) Fallbacks are returned in CatchupBuild.Ok.diagnostics, not only logged.
        val noZone = assertIs<CatchupBuild.Ok>(b.build(target(CatchupMode.XC), TimeBase.DEFAULT, window))
        assertEquals(listOf<CatchupDiagnostic>(CatchupDiagnostic.PanelZoneUnknown), noZone.diagnostics)
        val known = assertIs<CatchupBuild.Ok>(b.build(target(CatchupMode.XC), TimeBase(serverZone = ZoneOffset.UTC), window))
        assertEquals(emptyList(), known.diagnostics)

        val tpl = target(CatchupMode.DEFAULT, live = "http://h/ch.ts", source = "http://h/a?s={utc}&x={bogus}", xtream = null)
        val r = assertIs<CatchupBuild.Ok>(b.build(tpl, TimeBase.DEFAULT, window))
        assertTrue(CatchupDiagnostic.TemplateZoneUnknown in r.diagnostics, "${r.diagnostics}")
        val unknown = r.diagnostics.filterIsInstance<CatchupDiagnostic.UnknownPlaceholders>().single()
        assertEquals(listOf("{bogus}"), unknown.placeholders)
        assertEquals("catchup.unknown_placeholder", unknown.code)
        assertEquals(emptyList(), assertIs<CatchupBuild.Ok>(b.build(tpl.copy(catchup = tpl.catchup.copy(source = "http://h/a?s={utc}")), TimeBase(templateZone = ZoneOffset.UTC), window)).diagnostics)
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
        // (M4 commit 0, review B2) The corrected start must not be after the panel's now: with −4 h the programme starts
        // 6 h before now (before B2 this row used the 19:30 window, whose corrected start 23:30 is in the panel's future).
        val early = ArchiveWindow(start.minus(Duration.ofHours(6)), start.minus(Duration.ofHours(5)), deviceNow = now)
        val channel = ok(b.build(target(CatchupMode.SHIFT, live = "http://h/c.m3u8", xtream = null, corr = -4.0), tb, early))
        assertEquals(start.minus(Duration.ofHours(2)), channel.requestStart)
        assertEquals("http://h/c.m3u8?utc=1791912600&lutc=1791928800", channel.url)
        assertTrue(unsupported(b.build(target(CatchupMode.SHIFT, live = "http://h/c.m3u8", xtream = null, corr = -4.0), tb, window)).contains("not started"))
    }

    /**
     * (M4 commit 0, docs/design/m4-player-ux.md §1.7 item 9, review B2) A live-anchored request planned from
     * `ArchiveFrames.liveEdge − 30 s` (programme frame) builds with `requestStart == panel now − 30 s` for every sign of
     * the correction. Before the builder edit the corr = +2 h row failed with "the programme has not started yet".
     */
    @Test
    fun b2LiveAnchoredRequestStartsAtThePanelClockForEveryCorrection() {
        val skew = Duration.ofSeconds(45)
        val panelNow = now.plus(skew)
        for (corrH in listOf(-1.5, 0.0, 2.0)) {
            val tb = TimeBase(serverZone = ZoneOffset.UTC, clockSkew = skew)
            val corrMs = Duration.ofSeconds((corrH * 3600).toLong()).toMillis()
            val frames = app.thebox.core.catchup.archive.ArchiveFrames(correctionMs = corrMs, skewMs = skew.toMillis(), displayShiftMs = 0)
            assertEquals(panelNow.toEpochMilli(), frames.archiveOf(frames.liveEdge(now.toEpochMilli())), "corr $corrH: liveEdge − correction is the panel's now")
            val start = Instant.ofEpochMilli(frames.liveEdge(now.toEpochMilli()) - 30_000)
            val t = target(CatchupMode.SHIFT, live = "http://h/c.m3u8", xtream = null, corr = corrH)
            val req = ok(b.build(t, tb, ArchiveWindow(start, null, deviceNow = now)))
            assertEquals(panelNow.minusSeconds(30), req.requestStart, "corr $corrH")
            // The check still rejects a start whose corrected instant is after the panel's now.
            val future = Instant.ofEpochMilli(frames.liveEdge(now.toEpochMilli()) + 60_000)
            assertTrue(unsupported(b.build(t, tb, ArchiveWindow(future, null, deviceNow = now))).contains("not started"), "corr $corrH")
        }
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
