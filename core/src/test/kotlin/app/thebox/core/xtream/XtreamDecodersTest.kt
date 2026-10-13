package app.thebox.core.xtream

import app.thebox.core.common.AuthReason
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.model.TimeBase
import java.io.ByteArrayInputStream
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamDecodersTest {
    private val t0 = 1_791_919_800L // 2026-10-13T19:30:00Z

    private fun account(path: String) = Fixtures.use("xtream/$path") { XtreamDecoders.account(it, 1_000L) }

    private fun authError(path: String): SourceError = assertFailsWith<SourceException> { account(path) }.error

    @Test
    fun loginAllStrings() {
        val a = account("login_ok.json")
        assertTrue(a.auth)
        assertEquals(AccountStatus.ACTIVE, a.status)
        assertEquals("Active", a.statusRaw)
        assertEquals(1_798_761_600L, a.expiresAtSec)
        assertFalse(a.isTrial)
        assertEquals(1, a.activeConnections)
        assertEquals(2, a.maxConnections)
        assertEquals(listOf("m3u8", "ts", "rtmp"), a.allowedOutputFormats)
        assertEquals(1_760_000_000L, a.createdAtSec)
        assertEquals("Welcome", a.message)
        assertEquals(1_000L, a.measuredAtDeviceMs)
        assertEquals(
            XtreamServerInfo("panel.example.com", 8080, 25463, "http", "Europe/Berlin", t0, "2026-10-13 21:30:00", false, null),
            a.server,
        )
    }

    @Test
    fun loginIntsAndUnlimited() {
        val a = account("login_ints.json")
        assertEquals(AccountStatus.ACTIVE, a.status)
        assertNull(a.expiresAtSec)
        assertEquals(1, a.maxConnections)
        assertEquals(0, a.activeConnections)
        assertEquals(listOf("ts", "m3u8"), a.allowedOutputFormats)
        assertNull(a.message)
        assertEquals(443, a.server.httpsPort)
        assertEquals("America/New_York", a.server.timezone)
    }

    @Test
    fun loginXui() {
        val a = account("login_xui.json")
        assertTrue(a.server.xui)
        assertEquals("1.5.12", a.server.version)
        assertEquals("UTC+3", a.server.timezone)
        assertEquals(t0, a.server.timestampNow)
        assertNull(a.expiresAtSec, "exp_date \"0\" is unlimited")
        assertTrue(a.isTrial)
        assertTrue(a.allowedOutputFormats.isEmpty())
    }

    @Test
    fun loginFailures() {
        assertEquals(SourceError.Auth(AuthReason.BAD_CREDENTIALS), authError("login_auth0.json"))
        assertEquals(SourceError.Auth(AuthReason.EXPIRED), authError("login_expired.json"))
        assertEquals(SourceError.Auth(AuthReason.BANNED), authError("login_banned.json"))
        assertEquals(SourceError.Auth(AuthReason.BAD_CREDENTIALS), authError("login_empty_array.json"))
        assertEquals(SourceError.Auth(AuthReason.BAD_CREDENTIALS), authError("login_empty.json"))
        assertEquals(SourceError.Auth(AuthReason.UNKNOWN), authError("login_html.html"))
        val disabled = """{"user_info":{"auth":0,"status":"Disabled"}}"""
        assertEquals(
            SourceError.Auth(AuthReason.DISABLED),
            assertFailsWith<SourceException> { XtreamDecoders.account(ByteArrayInputStream(disabled.toByteArray()), 0) }.error,
        )
        // auth=1 with an Expired status is still a login (the card shows it).
        val expiredButAuth = """{"user_info":{"auth":1,"status":"Expired"}}"""
        assertEquals(AccountStatus.EXPIRED, XtreamDecoders.account(ByteArrayInputStream(expiredButAuth.toByteArray()), 0).status)
    }

    @Test
    fun categories() {
        val c = Fixtures.use("xtream/live_categories.json") { XtreamDecoders.categories(it) }
        assertEquals(
            listOf(
                XtreamCategory("1", "News", null),
                XtreamCategory("2", "Sports", null),
                XtreamCategory("5", "UK Sports", "2"),
                XtreamCategory("7", "Category 7", null),
            ),
            c,
        )
    }

    @Test
    fun liveStreamsCoercions() {
        val s = Fixtures.use("xtream/live_streams.json") { XtreamDecoders.liveStreams(it).toList() }
        assertEquals(19, s.size, "the stream without stream_id is skipped")
        val byId = s.associateBy { it.streamId }
        assertEquals(true, byId[101]!!.tvArchive)
        assertEquals(7, byId[101]!!.tvArchiveDuration)
        assertEquals(true, byId[102]!!.tvArchive)
        assertEquals(3, byId[102]!!.tvArchiveDuration)
        assertEquals(true, byId[103]!!.tvArchive)
        assertNull(byId[103]!!.tvArchiveDuration)
        assertEquals(false, byId[104]!!.tvArchive)
        assertNull(byId[104]!!.epgChannelId)
        assertNull(byId[105]!!.epgChannelId)
        assertEquals("none", byId[106]!!.categoryId)
        assertEquals(listOf("1", "5"), byId[107]!!.categoryIds)
        assertEquals("radio_streams", byId[108]!!.streamType)
        assertTrue(byId[109]!!.isAdult)
        assertEquals("http://upstream.example/a.ts", byId[110]!!.directSource)
        assertEquals(42, byId[111]!!.num)
        assertNull(byId[113]!!.name)
        assertNotNull(byId[116])
        assertEquals(5, byId[118]!!.tvArchiveDuration)
        assertEquals("Trim Me", byId[119]!!.name)
    }

    @Test
    fun vodAndSeriesLists() {
        val v = Fixtures.use("xtream/vod_streams.json") { XtreamDecoders.vodStreams(it).toList() }
        assertEquals(listOf(201L, 202L), v.map { it.streamId })
        assertEquals(7.5, v[0].rating)
        assertEquals("mkv", v[0].containerExtension)
        assertEquals("603", v[0].tmdb)
        assertNull(v[1].rating)
        assertEquals("none", v[1].categoryId)
        assertEquals(listOf("10", "11"), v[1].categoryIds)

        val s = Fixtures.use("xtream/series.json") { XtreamDecoders.series(it).toList() }
        assertEquals(listOf("2020-01-01", "2021-02-02", "2022-03-03"), s.map { it.releaseDate })
        assertEquals(listOf(0, 1, 1), s.map { it.backdropPaths.size })
        assertEquals("none", s[2].categoryId)
        assertEquals("21", s[1].categoryId)
    }

    @Test
    fun vodInfo() {
        val i = assertNotNull(Fixtures.use("xtream/vod_info.json") { XtreamDecoders.vodInfo(it) })
        assertEquals(201L, i.streamId)
        assertEquals(6130L, i.durationSecs)
        assertEquals(listOf("http://img/bg1.jpg", "http://img/bg2.jpg"), i.backdropPaths)
        assertEquals(7.5, i.rating)
        assertEquals("mkv", i.containerExtension)

        val empty = assertNotNull(Fixtures.use("xtream/vod_info_empty_info.json") { XtreamDecoders.vodInfo(it) })
        assertEquals("Movie Two", empty.name)
        assertEquals(202L, empty.streamId)
        assertNull(empty.durationSecs)
        assertTrue(empty.backdropPaths.isEmpty())

        val str = assertNotNull(Fixtures.use("xtream/vod_info_string_backdrop.json") { XtreamDecoders.vodInfo(it) })
        assertEquals(listOf("http://img/bg3.jpg"), str.backdropPaths)
        assertEquals(6130L, str.durationSecs, "duration HH:MM:SS when duration_secs is missing")

        assertNull(XtreamDecoders.vodInfo(ByteArrayInputStream("[]".toByteArray())))
        assertNull(XtreamDecoders.vodInfo(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun seriesInfoMap() {
        val i = assertNotNull(Fixtures.use("xtream/series_info_map.json") { XtreamDecoders.seriesInfo(it) })
        assertEquals(listOf(1, 2), i.seasons.map { it.number })
        assertEquals(listOf(3011L, 3012L), i.episodes[1]!!.map { it.id })
        assertEquals(listOf(3021L), i.episodes[2]!!.map { it.id })
        assertEquals(2, i.episodes[1]!![1].episodeNum)
        assertEquals(1800L, i.episodes[1]!![0].durationSecs)
        assertEquals("Series A", i.name)
    }

    @Test
    fun seriesInfoArrayOfArrays() {
        val i = assertNotNull(Fixtures.use("xtream/series_info_array.json") { XtreamDecoders.seriesInfo(it) })
        assertNull(i.name, "info: [] is an empty object")
        assertEquals(mapOf(1 to listOf(3011L, 3012L), 2 to listOf(3021L), 5 to listOf(3031L)), i.episodes.mapValues { e -> e.value.map { it.id } })
        assertEquals(listOf(1, 2, 5), i.seasons.map { it.number }, "season 5 is derived from the episodes")
    }

    @Test
    fun seriesInfoFlatAndDerivedSeasons() {
        val f = assertNotNull(Fixtures.use("xtream/series_info_flat.json") { XtreamDecoders.seriesInfo(it) })
        assertEquals(mapOf(1 to listOf(3011L, 3012L, 3099L), 2 to listOf(3021L)), f.episodes.mapValues { e -> e.value.map { it.id } })
        assertEquals(listOf(1, 2), f.seasons.map { it.number })
        assertEquals(listOf(3, 1), f.seasons.map { it.episodeCount })

        val n = assertNotNull(Fixtures.use("xtream/series_info_no_seasons.json") { XtreamDecoders.seriesInfo(it) })
        assertEquals(listOf(1, 3), n.seasons.map { it.number })
        assertEquals(listOf(1, 2), n.seasons.map { it.episodeCount })
        assertEquals(listOf("http://c/bg.jpg"), n.backdropPaths)

        assertNull(XtreamDecoders.seriesInfo(ByteArrayInputStream("[]".toByteArray())))
    }

    @Test
    fun shortEpgBase64AndPlainGiveTheSameListings() {
        val tb = TimeBase()
        val b = Fixtures.use("xtream/short_epg_base64.json") { XtreamDecoders.epgListings(it, tb) }
        val p = Fixtures.use("xtream/short_epg_plain.json") { XtreamDecoders.epgListings(it, tb) }
        assertEquals(p, b)
        assertEquals(listOf("Morning News", "Quiz Time", "Documentary", "Match Day"), b.map { it.title })
        assertEquals("Héadlines & weather", b[0].description)
        assertEquals("Football ⚽", b[3].description)
        assertEquals(t0, b[0].startUtcSec)
        assertEquals(t0 + 3600, b[0].stopUtcSec)
        assertTrue(b[0].nowPlaying)
        assertFalse(b[1].nowPlaying)
    }

    @Test
    fun shortEpgMixedStaysPlain() {
        val m = Fixtures.use("xtream/short_epg_mixed.json") { XtreamDecoders.epgListings(it, TimeBase()) }
        assertEquals(listOf("Test", "QUJD", "News at Ten", "Weather", "Film"), m.map { it.title })
        assertTrue(Fixtures.use("xtream/short_epg_empty.json") { XtreamDecoders.epgListings(it, TimeBase()) }.isEmpty())
    }

    @Test
    fun simpleDataTablePanelLocalTimes() {
        val berlin = TimeBase(serverZone = ZoneId.of("Europe/Berlin"))
        val l = Fixtures.use("xtream/simple_data_table.json") { XtreamDecoders.epgListings(it, berlin) }
        assertEquals(4, l.size)
        assertEquals(listOf(true, true, false, false), l.map { it.hasArchive })
        assertEquals(listOf("Past Show", "Previous", "Local Only", "Local Next"), l.map { it.title })
        // 21:30 CEST (UTC+2) = 19:30Z
        assertEquals(t0, l[2].startUtcSec)
        assertEquals(t0 + 3600, l[2].stopUtcSec)
        assertEquals(t0 + 6300, l[3].stopUtcSec)
        assertTrue(l[2].nowPlaying)

        // No panel zone known: read as UTC.
        val utc = Fixtures.use("xtream/simple_data_table.json") { XtreamDecoders.epgListings(it, TimeBase()) }
        assertEquals(t0 + 7200, utc[2].startUtcSec)
        assertEquals(t0 - 7200, utc[0].startUtcSec, "rows with *_timestamp are unaffected")
    }

    @Test
    fun parseHms() {
        assertEquals(6130L, XtreamDecoders.parseHms("01:42:10"))
        assertEquals(90L, XtreamDecoders.parseHms("1:30"))
        assertNull(XtreamDecoders.parseHms("abc"))
        assertNull(XtreamDecoders.parseHms("1"))
    }
}
