package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §6.3, §10.2: source order, DRM skipped, http fallback, captions, duration, nothing playable. */
class BrightcoveMapperTest {
    private val game = GameId(League.NHL, "2026020382")
    private fun body(path: String): JsonObject = Json.parseToJsonElement(SportsFixtures.text(path)).jsonObject
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun recordedGoalPicksHttpsHlsAndEnglishCaptionsOverHttps() {
        val b = body(HubFixtures.CLIP_GOAL)
        val s = assertNotNull(BrightcoveMapper.stream(b))
        assertEquals(ClipFormat.HLS, s.format)
        assertTrue(s.url.startsWith("https://manifest.prod.boltdns.net/manifest/v1/hls/v4/clear/"), s.url)
        assertTrue(s.url.endsWith("master.m3u8?redacted=1"))
        val c = assertNotNull(s.captionsUrl)
        assertTrue(c.startsWith("https://") && c.contains("/text.vtt"), c)
        assertTrue(!c.contains("thumbnail"), "the thumbnails metadata track is not captions")
    }

    @Test
    fun recordedInfoUsesLongDescriptionDurationAndHttpsImages() {
        val ref = ClipRef(6408791393112, ClipKind.GOAL, game)
        val i = assertNotNull(BrightcoveMapper.info(ref, body(HubFixtures.CLIP_GOAL)))
        assertEquals("Vilardi strikes first", i.title)
        // The recorded text carries no-break spaces (U+00A0) between some words; kept as recorded.
        assertTrue(i.description!!.startsWith("Kyle") && i.description!!.contains("Vilardi") && i.description!!.endsWith("open the scoring"))
        assertEquals(46_045L, i.durationMs)
        assertTrue(i.posterUrl!!.startsWith("https://") && i.posterUrl!!.contains("1280x720"))
        assertTrue(i.thumbnailUrl!!.startsWith("https://") && i.thumbnailUrl!!.contains("208x117"))
        assertEquals(ref, i.ref)
    }

    @Test
    fun everyRecordedClipIsPlayable() {
        for (p in listOf(HubFixtures.CLIP_RECAP, HubFixtures.CLIP_CONDENSED, HubFixtures.CLIP_GOAL)) {
            val s = assertNotNull(BrightcoveMapper.stream(body(p)), p)
            assertEquals(ClipFormat.HLS, s.format, p)
            assertTrue(s.url.startsWith("https://"), p)
            assertNotNull(BrightcoveMapper.info(ClipRef(1, ClipKind.RECAP, game), body(p)), p)
        }
    }

    @Test
    fun drmAndDashAreSkippedThenMp4AtMost720IsChosen() {
        val b = json(
            """{"name":"x","sources":[
              {"src":"https://a.test/v.mpd","type":"application/dash+xml"},
              {"src":"https://a.test/drm.m3u8","type":"application/x-mpegURL","key_systems":{"com.apple.fps.1_0":{}}},
              {"src":"https://a.test/1080.mp4","container":"MP4","height":1080},
              {"src":"https://a.test/540.mp4","container":"MP4","height":540},
              {"src":"https://a.test/720.mp4","container":"MP4","height":720}
            ]}""",
        )
        val s = assertNotNull(BrightcoveMapper.stream(b))
        assertEquals(ClipFormat.MP4, s.format)
        assertEquals("https://a.test/720.mp4", s.url)
        assertNull(s.captionsUrl)
    }

    @Test
    fun httpsMp4BeatsHttpHlsAndHttpIsTheLastResort() {
        val mixed = json(
            """{"name":"x","sources":[
              {"src":"http://a.test/m.m3u8","type":"application/x-mpegURL"},
              {"src":"https://a.test/720.mp4","container":"MP4","height":720}
            ]}""",
        )
        assertEquals("https://a.test/720.mp4", BrightcoveMapper.stream(mixed)!!.url)
        val httpOnly = json(
            """{"name":"x","sources":[
              {"src":"http://a.test/720.mp4","container":"MP4","height":720},
              {"src":"http://a.test/m.m3u8","type":"application/x-mpegURL"}
            ]}""",
        )
        val s = assertNotNull(BrightcoveMapper.stream(httpOnly))
        assertEquals("http://a.test/m.m3u8", s.url)
        assertEquals(ClipFormat.HLS, s.format)
    }

    @Test
    fun nothingPlayableIsNull() {
        assertNull(BrightcoveMapper.stream(json("""{"name":"x"}""")))
        assertNull(BrightcoveMapper.stream(json("""{"name":"x","sources":"nope"}""")))
        assertNull(
            BrightcoveMapper.stream(
                json(
                    """{"name":"x","sources":[
                      {"src":"https://a.test/v.mpd","type":"application/dash+xml"},
                      {"src":"rtmp://a.test/live","type":"application/x-mpegURL"},
                      {"src":"https://a.test/drm.m3u8","key_systems":{"widevine":{"license_url":"https://l.test"}}},
                      {"src":"https://a.test/1080.mp4","container":"MP4","height":1080},
                      {"type":"application/x-mpegURL"}
                    ]}""",
                ),
            ),
        )
    }

    @Test
    fun captionsNeedKindCaptionsAndEnglish() {
        fun cap(tracks: String) = BrightcoveMapper.stream(
            json("""{"name":"x","sources":[{"src":"https://a.test/m.m3u8","type":"application/x-mpegURL"}],"text_tracks":$tracks}"""),
        )!!.captionsUrl
        assertNull(cap("""[{"kind":"captions","srclang":"fr","src":"https://a.test/fr.vtt"}]"""))
        assertNull(cap("""[{"kind":"metadata","srclang":"en","src":"https://a.test/t.vtt"}]"""))
        assertNull(cap("""[{"kind":"captions","srclang":null,"src":"https://a.test/t.vtt"}]"""))
        assertEquals("https://a.test/en.vtt", cap("""[{"kind":"captions","srclang":"en-US","src":"https://a.test/en.vtt"}]"""))
        assertEquals("http://a.test/en.vtt", cap("""[{"kind":"captions","srclang":"en","src":"http://a.test/en.vtt","sources":null}]"""))
        assertNull(cap("\"odd\""))
    }

    @Test
    fun durationAndDescriptionFallbacks() {
        val ref = ClipRef(1, ClipKind.RECAP, game)
        val a = BrightcoveMapper.info(ref, json("""{"name":" Recap ","description":"short","long_description":"  ","duration":"46000"}"""))!!
        assertEquals("Recap", a.title)
        assertEquals("short", a.description)
        assertNull(a.durationMs, "a string duration is not a duration")
        val b = BrightcoveMapper.info(ref, json("""{"name":"Recap","duration":0,"poster":"http://p.test/p.jpg"}"""))!!
        assertNull(b.durationMs)
        assertNull(b.description)
        assertEquals("http://p.test/p.jpg", b.posterUrl)
        assertNull(BrightcoveMapper.info(ref, json("""{"name":""}""")))
        assertNull(BrightcoveMapper.info(ref, json("""{"name":42}""")))
    }
}
