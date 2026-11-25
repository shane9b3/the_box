package app.thebox.core.sports.hub

import app.thebox.core.sports.SportsConstants
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-hub.md §4.2, §4.3, §4.5, §6.5: kinds (RECAP, PLAY, others dropped), settop, https, the effective
 * expiry and the `atMs` check, over the reduced fixtures (which also prove the frozen DTO decodes every recorded shape).
 */
class EspnVideoMapperTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The evening after MNF: every MNF clip is past its embargo and 1+ day from its expiry. */
    private val mnfNight = ms("2026-11-25T00:00:00Z")

    private fun dto(
        id: Long = 7L,
        coverage: String? = EspnVideoMapper.COVERAGE_ONE_PLAY,
        hls: String? = "https://clips.invalid/7/playlist.m3u8",
        published: String? = "2026-11-24T02:30:58Z",
        embargo: String? = null,
        expires: String? = "2026-11-26T02:30:00Z",
        devices: List<String>? = listOf("settop", "tablet"),
        deviceType: String? = "whitelist",
        duration: Int? = 23,
    ) = EspnVideoDto(
        id = JsonPrimitive(id), headline = "  A play  ", duration = duration?.let { JsonPrimitive(it) }, originalPublishDate = published,
        timeRestrictions = EspnTimeRestrictionsDto(embargo, expires),
        deviceRestrictions = devices?.let { EspnDeviceRestrictionsDto(deviceType, it) },
        thumbnail = "https://img.invalid/7.jpg", tracking = coverage?.let { EspnTrackingDto(it) },
        links = hls?.let { EspnVideoLinksDto(EspnVideoSourceDto(EspnHrefDto(it))) },
    )

    @Test fun everySummaryFixtureDecodes() {
        for (path in NflHubFixtures.SUMMARIES) {
            val s = NflHubFixtures.summary(path)
            val comp = assertNotNull(s.header?.competitions?.firstOrNull(), path)
            assertEquals(2, comp.competitors?.size, path)
            assertNotNull(s.boxscore?.teams, path)
        }
        assertNull(NflHubFixtures.summary(NflHubFixtures.PRE_GB_LAR).scoringPlays, "the pre-game summary has no scoringPlays key")
    }

    @Test fun mnfOffersTheHighlightAndThreeOnePlays() {
        val s = NflHubFixtures.summary(NflHubFixtures.FINAL_MNF)
        val entries = s.videos.orEmpty().mapNotNull { EspnVideoMapper.entry(it, mnfNight, httpOk = false) }
        assertEquals(listOf(50579688L, 50578630L, 50578740L, 50579083L), entries.map { it.clip.videoId })
        assertEquals(listOf(ClipKind.RECAP, ClipKind.PLAY, ClipKind.PLAY, ClipKind.PLAY), entries.map { it.clip.kind })
        val h = entries.first()
        assertEquals("https://clips.invalid/50579688/playlist.m3u8", h.hlsUrl, "the HLS master, never the MP4 or CMAF")
        assertEquals(74_000L, h.clip.durationMs)
        assertEquals(ms("2026-11-24T04:32:10Z"), h.clip.embargoMs)
        assertEquals(ms("2026-11-26T04:32:10Z"), h.clip.expiresMs)
        assertEquals(ms("2026-11-24T04:32:11Z"), h.clip.publishedMs)
        assertEquals("https://img.invalid/50579688.jpg", h.clip.thumbUrl)
        assertEquals("Commanders storm back, stun Bengals 22-20 on MNF", h.clip.title)
        assertTrue("clips.invalid" !in h.toString(), "the URL is never printed")
    }

    @Test fun theNinetySixHourHighlightAndTheAnalysisClip() {
        val pit = NflHubFixtures.summary(NflHubFixtures.FINAL_PIT_PHI).videos.orEmpty()
        val e = assertNotNull(EspnVideoMapper.entry(pit.single(), ms("2026-11-23T00:00:00Z"), httpOk = false))
        assertEquals(ClipKind.RECAP, e.clip.kind)
        assertEquals(52_000L, e.clip.durationMs)
        assertEquals(ms("2026-11-26T21:29:49Z"), e.clip.expiresMs, "the 96 h window")
        val pre = NflHubFixtures.summary(NflHubFixtures.PRE_GB_LAR).videos.orEmpty()
        assertEquals(1, pre.size)
        assertNull(EspnVideoMapper.entry(pre.single(), null, httpOk = false), "Analysis is dropped")
    }

    @Test fun clipRecordsAndTheBoardHighlightShareTheShape() {
        val r = NflHubFixtures.record(NflHubFixtures.RECORD_50578630).videos.orEmpty().single()
        val e = assertNotNull(EspnVideoMapper.entry(r, mnfNight, httpOk = false))
        assertEquals(50578630L, e.clip.videoId)
        assertEquals(ClipKind.PLAY, e.clip.kind)
        val lights = NflHubFixtures.record(NflHubFixtures.RECORD_50579083).videos.orEmpty().single()
        assertEquals(50579083L, EspnVideoMapper.entry(lights, mnfNight, httpOk = false)?.clip?.videoId)
        val board = NflHubFixtures.board()
        val highlights = board.events.orEmpty().map { ev -> ev.competitions.orEmpty().single().highlights.orEmpty().single() }
        val mapped = highlights.map { EspnVideoMapper.entry(it, null, httpOk = false) }
        assertEquals(listOf(ClipKind.RECAP, ClipKind.RECAP), mapped.map { it?.clip?.kind })
    }

    @Test fun onlyHighlightsAndOnePlays() {
        assertEquals(ClipKind.RECAP, EspnVideoMapper.entry(dto(coverage = EspnVideoMapper.COVERAGE_HIGHLIGHT), null, false)?.clip?.kind)
        assertEquals(ClipKind.PLAY, EspnVideoMapper.entry(dto(), null, false)?.clip?.kind)
        for (c in listOf("Analysis", "Recap", "", null)) assertNull(EspnVideoMapper.entry(dto(coverage = c), null, false), "$c")
    }

    @Test fun httpsOnlyUnlessTheDebugOrigin() {
        assertNull(EspnVideoMapper.entry(dto(hls = "http://clips.invalid/7/playlist.m3u8"), null, httpOk = false))
        assertNotNull(EspnVideoMapper.entry(dto(hls = "http://10.0.2.2:8080/7/playlist.m3u8"), null, httpOk = true))
        assertNull(EspnVideoMapper.entry(dto(hls = null), null, false), "no HLS: never the MP4")
        assertNull(EspnVideoMapper.entry(dto(hls = "ftp://clips.invalid/x"), null, true))
    }

    @Test fun settopMustBeAllowed() {
        assertNull(EspnVideoMapper.entry(dto(devices = listOf("tablet", "handset")), null, false), "a whitelist without settop")
        assertNotNull(EspnVideoMapper.entry(dto(devices = null), null, false), "no restriction")
        assertNull(EspnVideoMapper.entry(dto(devices = listOf("settop"), deviceType = "blacklist"), null, false))
        assertNotNull(EspnVideoMapper.entry(dto(devices = listOf("handset"), deviceType = "blacklist"), null, false))
    }

    @Test fun theEffectiveExpiryAndTheTimeCheck() {
        val published = "2026-11-24T02:30:58Z"
        val noExpiry = assertNotNull(EspnVideoMapper.entry(dto(expires = null), null, false))
        assertEquals(ms(published) + SportsConstants.HUB_CLIP_DEFAULT_LIFE_MS, noExpiry.clip.expiresMs, "48 h after publishing")
        assertNull(EspnVideoMapper.entry(dto(expires = null, published = null), null, false), "no expiry at all: dropped")
        val exp = ms("2026-11-26T02:30:00Z")
        assertNotNull(EspnVideoMapper.entry(dto(), exp - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS - 1, false))
        assertNull(EspnVideoMapper.entry(dto(), exp - SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS, false), "inside the margin")
        assertNull(EspnVideoMapper.entry(dto(embargo = "2026-11-24T03:00:00Z"), ms("2026-11-24T02:59:59Z"), false), "embargoed")
        assertNotNull(EspnVideoMapper.entry(dto(embargo = "2026-11-24T03:00:00Z"), null, false), "null atMs skips the time check")
        assertNotNull(EspnVideoMapper.entry(dto(expires = "2026-11-26T02:30Z"), null, false), "ESPN's minute-only form parses")
    }

    @Test fun idsAndDurations() {
        assertNull(EspnVideoMapper.entry(dto(id = 0), null, false))
        val s = dto().let { EspnVideoDto(JsonPrimitive("50578630"), it.headline, JsonPrimitive(23.0), it.originalPublishDate, it.timeRestrictions, it.deviceRestrictions, it.thumbnail, it.tracking, it.links) }
        val e = assertNotNull(EspnVideoMapper.entry(s, null, false))
        assertEquals(50578630L, e.clip.videoId, "a digit string")
        assertEquals(23_000L, e.clip.durationMs, "a whole double")
        assertEquals("A play", e.clip.title)
        assertNull(EspnVideoMapper.entry(dto(duration = 0), null, false)?.clip?.durationMs)
    }
}
