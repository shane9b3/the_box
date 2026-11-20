package app.thebox.core.weather

import app.thebox.core.settings.Domains
import app.thebox.core.settings.state.NOT_BACKED_UP_DOMAINS
import app.thebox.core.settings.state.WeatherDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherCoreTest {
    private fun fixture(): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/weather/forecast.json")!!.readText()) as JsonObject

    private val london = WeatherCity(6058560, "London", "Ontario", "Canada", "CA", 42.98339, -81.23304, "America/Toronto")

    @Test
    fun aRealForecastAnswerIsMapped() {
        val f = assertNotNull(OpenMeteoMapper.forecast(fixture(), london.id, 1_000L))
        assertEquals(7, f.days.size)
        assertEquals(168, f.hours.size)
        assertEquals(0, f.nowDay)
        assertEquals(20 * 60 + 45, f.nowMinute)
        assertEquals(11.6, f.current.tempC)
        assertEquals(Condition.OVERCAST, f.current.condition)
        assertFalse(f.current.isDay)
        assertEquals(227, f.current.windDirDeg)
        val today = f.days[0]
        assertEquals("2026-11-19", today.date)
        assertEquals(Condition.FOG, today.condition)
        assertEquals(7 * 60 + 22, today.sunriseMin)
        assertEquals(16 * 60 + 58, today.sunsetMin)
        assertEquals(34532, today.daylightSec)
        assertEquals(0, f.hours[0].hour)
        assertEquals(1, f.hours[24].day)
    }

    @Test
    fun todayShowsTheNextTwelveHoursAndAnotherDayEverySecondHour() {
        val f = OpenMeteoMapper.forecast(fixture(), london.id, 0L)!!
        val today = f.hourly(0)
        assertEquals(12, today.size)
        assertEquals(21, today.first().hour, "the first slot is the next full hour after 20:45")
        assertEquals(listOf(0, 0, 0, 1), today.take(4).map { it.day }, "and it runs past midnight into tomorrow")
        val wed = f.hourly(2)
        assertEquals((0..22 step 2).toList(), wed.map { it.hour })
        assertTrue(wed.all { it.day == 2 })
        assertTrue(f.hourly(9).isEmpty())
    }

    @Test
    fun unusableBodiesAreRejected() {
        assertNull(OpenMeteoMapper.forecast(JsonObject(emptyMap()), 1, 0))
        assertNull(OpenMeteoMapper.forecast(Json.parseToJsonElement("""{"current":{},"hourly":{},"daily":{"time":[]}}""") as JsonObject, 1, 0))
        assertEquals(-1, OpenMeteoMapper.minuteOfDay("soon"))
    }

    @Test
    fun geocoderResults() {
        val root = Json.parseToJsonElement(
            """{"results":[{"id":6058560,"name":"London","latitude":42.98339,"longitude":-81.23304,"country_code":"CA",
               "admin1":"Ontario","country":"Canada","timezone":"America/Toronto"},{"name":"No id"},{"id":5,"name":"Bare","latitude":1,"longitude":2}]}""",
        ) as JsonObject
        val c = OpenMeteoMapper.cities(root)
        assertEquals(listOf(london, WeatherCity(5, "Bare", lat = 1.0, lon = 2.0)), c)
        assertEquals("London, Ontario", c[0].label)
        assertTrue(c[0].canadian)
        assertEquals("Bare", c[1].label)
        assertTrue(OpenMeteoMapper.cities(JsonObject(emptyMap())).isEmpty())
        assertTrue(OpenMeteoMapper.forecastUrl(london).contains("latitude=42.9834&longitude=-81.2330&"))
    }

    @Test
    fun wmoCodes() {
        assertEquals(Condition.CLEAR, Condition.ofWmo(0))
        assertEquals(Sky.RAIN, Condition.ofWmo(61).sky)
        assertEquals(Sky.SNOW, Condition.ofWmo(85).sky)
        assertEquals(Sky.STORM, Condition.ofWmo(99).sky)
        assertEquals(Condition.OVERCAST, Condition.ofWmo(1234))
    }

    @Test
    fun scenes() {
        fun s(sky: Sky, day: Boolean, now: Int) = WeatherScene.of(sky, day, now, 7 * 60, 17 * 60)
        assertEquals(WeatherScene.CLEAR_DAY, s(Sky.CLEAR, true, 12 * 60))
        assertEquals(WeatherScene.DUSK, s(Sky.CLEAR, true, 16 * 60 + 30))
        assertEquals(WeatherScene.DUSK, s(Sky.PARTLY, false, 6 * 60 + 30))
        assertEquals(WeatherScene.CLEAR_NIGHT, s(Sky.CLEAR, false, 23 * 60))
        assertEquals(WeatherScene.RAIN_NIGHT, s(Sky.DRIZZLE, false, 17 * 60), "twilight is for clear skies only")
        assertEquals(WeatherScene.SNOW_DAY, s(Sky.SNOW, true, 12 * 60))
        assertEquals(WeatherScene.STORM, s(Sky.STORM, true, 12 * 60))
        assertEquals(WeatherScene.FOG, s(Sky.FOG, false, 2 * 60))
        assertEquals(WeatherScene.CLOUDY_NIGHT, WeatherScene.of(Sky.CLOUDY, false, -1, -1, -1))
        assertEquals(WeatherScene.CLOUDY_NIGHT, WeatherScene.of(OpenMeteoMapper.forecast(fixture(), 1, 0)!!))
    }

    @Test
    fun prefs() {
        val other = london.copy(id = 2, name = "Paris")
        var p = WeatherPrefs().withCity(london)
        assertEquals(london.id, p.defaultId, "the first city becomes the default")
        p = p.withCity(other).withCity(other)
        assertEquals(2, p.cities.size)
        assertEquals(london, p.defaultCity)
        p = p.without(london.id)
        assertEquals(other, p.defaultCity)
        assertEquals(2L, p.defaultId)
        assertNull(p.without(2).defaultCity)
        assertEquals(Domains.WEATHER, WeatherDomain.name)
        assertFalse(Domains.WEATHER in NOT_BACKED_UP_DOMAINS)
        val round = Json.decodeFromString(WeatherPrefs.serializer(), Json.encodeToString(WeatherPrefs.serializer(), p))
        assertEquals(p, round)
    }

    @Test
    fun units() {
        assertEquals(48, WeatherUnitsMath.temp(9.0, WeatherUnits.IMPERIAL))
        assertEquals(9, WeatherUnitsMath.temp(9.4, WeatherUnits.METRIC))
        assertEquals(11, WeatherUnitsMath.speed(18.0, WeatherUnits.IMPERIAL))
        assertEquals(5, WeatherUnitsMath.compass(227))
        assertEquals(0, WeatherUnitsMath.compass(350))
        assertEquals(0, WeatherUnitsMath.uvBand(2.5))
        assertEquals(4, WeatherUnitsMath.uvBand(11.0))
        assertEquals(3, WeatherUnitsMath.humidityBand(88))
    }

    @Test
    fun radarMaths() {
        val z = 7.0
        assertEquals(RadarMath.worldSize(z) / 2, RadarMath.xOf(0.0, z), 1e-6)
        assertEquals(42.98, RadarMath.latOf(RadarMath.yOf(42.98, z), z), 1e-6)
        assertEquals(-81.25, RadarMath.lonOf(RadarMath.xOf(-81.25, z), z), 1e-6)
        val v = RadarMath.view(42.98, -81.25, z, 1920.0, 1080.0)
        assertEquals(RadarMath.xOf(-81.25, z), v.left + 960, 1e-6)
        val tiles = RadarMath.rainViewerTiles("https://h", "/v2/radar/abc", v, RadarMath.rainViewerTileZoom(z))
        assertEquals(6, RadarMath.rainViewerTileZoom(z))
        assertTrue(tiles.size in 8..20, "a frame is a handful of tiles: ${tiles.size}")
        assertTrue(tiles.all { it.size == 512f })
        assertTrue(tiles.any { it.url == "https://h/v2/radar/abc/256/6/17/23/2/1_1.png" }, tiles.first().url)
        assertTrue(tiles.minOf { it.x } <= 0f && tiles.maxOf { it.x + it.size } >= 1920f)
        assertEquals(7, RadarMath.rainViewerTileZoom(9.0))
        val url = RadarMath.envCanadaUrl(v, 960, 540, "2026-11-20T01:42:00Z", snow = false)
        assertTrue(url.contains("LAYERS=RADAR_1KM_RRAI") && url.contains("WIDTH=960&HEIGHT=540") && url.contains("TIME=2026-11-20T01:42:00Z"), url)
        val bbox = Regex("BBOX=([^&]+)").find(url)!!.groupValues[1].split(',').map { it.toDouble() }
        assertTrue(bbox[0] < bbox[2] && bbox[1] < bbox[3])
        assertTrue(bbox[0] < -9_000_000 && bbox[2] > -9_100_000, "the box spans London, Ontario: $bbox")
    }

    @Test
    fun radarFrameLists() {
        val caps = """<Layer><Dimension name="time" units="ISO8601" default="2026-11-20T01:42:00Z" nearestValue="0">2026-11-19T22:42:00Z/2026-11-20T01:42:00Z/PT6M</Dimension></Layer>"""
        val ec = RadarMath.envCanadaFrames(caps, 10, 720)
        assertEquals(10, ec.size)
        assertEquals("2026-11-20T01:42:00Z", ec.last().key)
        assertEquals("2026-11-19T23:54:00Z", ec.first().key)
        assertTrue(RadarMath.envCanadaFrames("<nothing/>", 10, 720).isEmpty())
        val rv = Json.parseToJsonElement(
            """{"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":3,"path":"/v2/radar/c"},{"time":1,"path":"/v2/radar/a"},
               {"time":2,"path":"/v2/radar/b"},{"time":4,"path":"http://evil/x y"}]}}""",
        ) as JsonObject
        val frames = RadarMath.rainViewerFrames(rv, 2)!!
        assertEquals(RadarSource.RAINVIEWER, frames.source)
        assertEquals(listOf("/v2/radar/b", "/v2/radar/c"), frames.frames.map { it.key })
        assertNull(RadarMath.rainViewerFrames(Json.parseToJsonElement("""{"host":"http://plain","radar":{"past":[]}}""") as JsonObject, 2))
    }
}
