package app.thebox.core.weather

import kotlinx.serialization.Serializable

/**
 * The Weather page (owner request 2026-11-19, docs/decisions/2026-11-19-weather-page.md).
 *
 * Forecast and city search come from Open-Meteo; the radar from Environment Canada (Canadian cities) or RainViewer
 * (everywhere else). Nothing about the user is sent: a request carries a latitude/longitude the user picked by city
 * name, or the name typed into the city search. The device's own location is never read.
 */

/** A city the user added. [id] is the geocoder's id (stable); [zone] is an IANA zone id. */
@Serializable
data class WeatherCity(
    val id: Long,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val lat: Double,
    val lon: Double,
    val zone: String = "UTC",
) {
    /** "London, ON" style second part: the region when there is one, else the country. */
    val place: String? get() = region?.takeIf { it.isNotBlank() } ?: country?.takeIf { it.isNotBlank() }
    val label: String get() = place?.let { "$name, $it" } ?: name
    val canadian: Boolean get() = countryCode.equals("CA", ignoreCase = true)
}

enum class WeatherUnits { METRIC, IMPERIAL }

/** Stored and backed up (`Domains.WEATHER`). [defaultId] is the city the page opens on; null = the first city. */
@Serializable
data class WeatherPrefs(
    val cities: List<WeatherCity> = emptyList(),
    val defaultId: Long? = null,
    val units: WeatherUnits = WeatherUnits.METRIC,
) {
    val defaultCity: WeatherCity? get() = cities.firstOrNull { it.id == defaultId } ?: cities.firstOrNull()

    fun withCity(city: WeatherCity): WeatherPrefs {
        if (cities.any { it.id == city.id }) return this
        if (cities.size >= MAX_CITIES) return this
        val next = cities + city
        return copy(cities = next, defaultId = defaultId ?: city.id)
    }

    fun without(id: Long): WeatherPrefs {
        val next = cities.filterNot { it.id == id }
        return copy(cities = next, defaultId = if (defaultId == id) next.firstOrNull()?.id else defaultId)
    }

    companion object {
        const val MAX_CITIES = 12
    }
}

/** What the sky looks like; picks the icon and the background picture. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

/** The worded condition (one string each in `:ui`). */
enum class Condition(val sky: Sky) {
    CLEAR(Sky.CLEAR), MAINLY_CLEAR(Sky.CLEAR), PARTLY_CLOUDY(Sky.PARTLY), OVERCAST(Sky.CLOUDY), FOG(Sky.FOG),
    LIGHT_DRIZZLE(Sky.DRIZZLE), DRIZZLE(Sky.DRIZZLE), FREEZING_DRIZZLE(Sky.DRIZZLE),
    LIGHT_RAIN(Sky.RAIN), RAIN(Sky.RAIN), HEAVY_RAIN(Sky.RAIN), FREEZING_RAIN(Sky.RAIN),
    LIGHT_SNOW(Sky.SNOW), SNOW(Sky.SNOW), HEAVY_SNOW(Sky.SNOW),
    LIGHT_SHOWERS(Sky.RAIN), SHOWERS(Sky.RAIN), HEAVY_SHOWERS(Sky.RAIN), SNOW_SHOWERS(Sky.SNOW),
    THUNDERSTORM(Sky.STORM), THUNDERSTORM_HAIL(Sky.STORM);

    companion object {
        /** WMO weather interpretation codes, as Open-Meteo sends them. Unknown codes read as overcast. */
        fun ofWmo(code: Int): Condition = when (code) {
            0 -> CLEAR
            1 -> MAINLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51 -> LIGHT_DRIZZLE
            53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61 -> LIGHT_RAIN
            63 -> RAIN
            65 -> HEAVY_RAIN
            66, 67 -> FREEZING_RAIN
            71, 77 -> LIGHT_SNOW
            73 -> SNOW
            75 -> HEAVY_SNOW
            80 -> LIGHT_SHOWERS
            81 -> SHOWERS
            82 -> HEAVY_SHOWERS
            85, 86 -> SNOW_SHOWERS
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> OVERCAST
        }
    }
}

/** Metric throughout; `:ui` converts for display. */
data class CurrentWeather(
    val tempC: Double,
    val feelsC: Double,
    val humidityPct: Int,
    val isDay: Boolean,
    val condition: Condition,
    val windKmh: Double,
    val windDirDeg: Int,
    val gustKmh: Double,
    val precipMm: Double,
)

/** One hour. [day] indexes [Forecast.days]; [hour] is 0..23 in the city's zone. [popPct] null = not reported. */
data class HourWeather(val day: Int, val hour: Int, val tempC: Double, val condition: Condition, val popPct: Int?, val isDay: Boolean)

/** One day. [date] is ISO "2026-11-19"; sunrise and sunset are minutes of that day in the city's zone (-1 = none). */
data class DayWeather(
    val date: String,
    val condition: Condition,
    val maxC: Double,
    val minC: Double,
    val popPct: Int?,
    val precipMm: Double,
    val sunriseMin: Int,
    val sunsetMin: Int,
    val uvMax: Double?,
    val daylightSec: Int,
)

/**
 * A city's forecast. [nowDay] / [nowMinute] are the city's local day index and minute of day when the forecast was
 * made ("current.time"); [hours] holds every hour of every day in [days].
 */
data class Forecast(
    val cityId: Long,
    val fetchedAtMs: Long,
    val nowDay: Int,
    val nowMinute: Int,
    val current: CurrentWeather,
    val hours: List<HourWeather>,
    val days: List<DayWeather>,
) {
    /**
     * The hourly strip (owner request): for today the next [HOURLY_SLOTS] hours from now; for another day every second
     * hour of that day (00:00 … 22:00), so the whole day fits the same twelve columns.
     */
    fun hourly(day: Int): List<HourWeather> {
        if (day == nowDay) {
            val first = hours.indexOfFirst { it.day == nowDay && it.hour > nowMinute / 60 }
                .let { if (it < 0) hours.indexOfFirst { h -> h.day > nowDay } else it }
            if (first < 0) return emptyList()
            return hours.subList(first, minOf(hours.size, first + HOURLY_SLOTS))
        }
        return hours.filter { it.day == day && it.hour % 2 == 0 }.take(HOURLY_SLOTS)
    }

    companion object {
        const val HOURLY_SLOTS = 12
    }
}

enum class WeatherFail { OFFLINE, NO_ANSWER, BAD_BODY }

sealed interface WeatherResult<out T> {
    data class Ok<T>(val value: T) : WeatherResult<T>
    data class Failed(val kind: WeatherFail) : WeatherResult<Nothing>
}

/** Which service draws the radar for a city. */
enum class RadarSource { ENV_CANADA, RAINVIEWER }

/** One radar picture: [timeSec] is its UTC time; [key] is the source's handle (RainViewer path, or an ISO time). */
data class RadarFrame(val timeSec: Long, val key: String)

/** The frames in time order, oldest first. [host] is RainViewer's tile host (empty for Environment Canada). */
data class RadarFrames(val source: RadarSource, val host: String, val frames: List<RadarFrame>, val snow: Boolean = false)

interface WeatherRepository {
    /** The forecast for [city]; a copy under [WeatherTimings.FORECAST_TTL_MS] old is returned without a request unless [force]. */
    suspend fun forecast(city: WeatherCity, force: Boolean = false): WeatherResult<Forecast>

    /** Cities named like [query] (at least two characters), best match first; [language] is a two-letter code. */
    suspend fun search(query: String, language: String): WeatherResult<List<WeatherCity>>

    /** The radar frames for [city]; [cold] picks Environment Canada's snow layer. */
    suspend fun radar(city: WeatherCity, cold: Boolean): WeatherResult<RadarFrames>
}

object WeatherTimings {
    const val FORECAST_TTL_MS = 10 * 60_000L
    /** The open page re-reads its forecast this often. */
    const val REFRESH_MS = 15 * 60_000L
    const val RADAR_REFRESH_MS = 5 * 60_000L
    const val CALL_TIMEOUT_MS = 12_000L
    const val SEARCH_DEBOUNCE_MS = 400L
    const val RADAR_FRAME_MS = 600L
    const val RADAR_LAST_FRAME_HOLD_MS = 1_800L
    /** Environment Canada: the last two hours, one frame every twelve minutes. */
    const val EC_FRAME_STEP_SEC = 12 * 60L
    const val EC_FRAMES = 10
    /** RainViewer: the newest frames of its two-hour list (each is a set of tiles, so fewer are fetched). */
    const val RV_FRAMES = 8
}
