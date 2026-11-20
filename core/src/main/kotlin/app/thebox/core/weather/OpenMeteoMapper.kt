package app.thebox.core.weather

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Open-Meteo's forecast and geocoding answers → the page's model. Pure, so the JVM tests cover it.
 * The request these parse is [FORECAST_QUERY]; times come back in the city's own zone (`timezone=auto`).
 */
object OpenMeteoMapper {
    const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search"

    /** Everything but latitude and longitude. Metric units (the defaults). */
    const val FORECAST_QUERY = "timezone=auto&forecast_days=7" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m," +
        "wind_direction_10m,wind_gusts_10m,precipitation" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
        "sunrise,sunset,uv_index_max,daylight_duration"

    fun forecastUrl(city: WeatherCity): String = "$FORECAST_BASE?latitude=${coord(city.lat)}&longitude=${coord(city.lon)}&$FORECAST_QUERY"

    /** Four decimals (about 11 m) is all the forecast grid can use. */
    private fun coord(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)

    /** Null when the body is not a usable forecast. */
    fun forecast(root: JsonObject, cityId: Long, fetchedAtMs: Long): Forecast? {
        val cur = root["current"] as? JsonObject ?: return null
        val hourly = root["hourly"] as? JsonObject ?: return null
        val daily = root["daily"] as? JsonObject ?: return null
        val dates = daily.strings("time") ?: return null
        if (dates.isEmpty()) return null
        val dCode = daily.arr("weather_code")
        val dMax = daily.arr("temperature_2m_max")
        val dMin = daily.arr("temperature_2m_min")
        val dPop = daily.arr("precipitation_probability_max")
        val dSum = daily.arr("precipitation_sum")
        val dRise = daily.arr("sunrise")
        val dSet = daily.arr("sunset")
        val dUv = daily.arr("uv_index_max")
        val dLight = daily.arr("daylight_duration")
        val days = ArrayList<DayWeather>(dates.size)
        for (i in dates.indices) {
            days += DayWeather(
                date = dates[i],
                condition = Condition.ofWmo(dCode.int(i) ?: 3),
                maxC = dMax.dbl(i) ?: return null,
                minC = dMin.dbl(i) ?: return null,
                popPct = dPop.int(i),
                precipMm = dSum.dbl(i) ?: 0.0,
                sunriseMin = minuteOfDay(dRise.str(i)),
                sunsetMin = minuteOfDay(dSet.str(i)),
                uvMax = dUv.dbl(i),
                daylightSec = (dLight.dbl(i) ?: 0.0).toInt(),
            )
        }
        val times = hourly.strings("time") ?: return null
        val hTemp = hourly.arr("temperature_2m")
        val hCode = hourly.arr("weather_code")
        val hPop = hourly.arr("precipitation_probability")
        val hDay = hourly.arr("is_day")
        val hours = ArrayList<HourWeather>(times.size)
        for (i in times.indices) {
            val t = times[i]
            if (t.length < 13) continue
            val day = dates.indexOf(t.substring(0, 10))
            val temp = hTemp.dbl(i)
            if (day < 0 || temp == null) continue
            hours += HourWeather(
                day = day, hour = t.substring(11, 13).toIntOrNull() ?: continue, tempC = temp,
                condition = Condition.ofWmo(hCode.int(i) ?: 3), popPct = hPop.int(i), isDay = (hDay.int(i) ?: 1) != 0,
            )
        }
        if (hours.isEmpty()) return null
        val now = (cur["time"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val nowDay = dates.indexOf(now.take(10)).coerceAtLeast(0)
        val current = CurrentWeather(
            tempC = cur.dbl("temperature_2m") ?: return null,
            feelsC = cur.dbl("apparent_temperature") ?: cur.dbl("temperature_2m")!!,
            humidityPct = cur.int("relative_humidity_2m") ?: 0,
            isDay = (cur.int("is_day") ?: 1) != 0,
            condition = Condition.ofWmo(cur.int("weather_code") ?: 3),
            windKmh = cur.dbl("wind_speed_10m") ?: 0.0,
            windDirDeg = cur.int("wind_direction_10m") ?: 0,
            gustKmh = cur.dbl("wind_gusts_10m") ?: 0.0,
            precipMm = cur.dbl("precipitation") ?: 0.0,
        )
        return Forecast(cityId, fetchedAtMs, nowDay, minuteOfDay(now).coerceAtLeast(0), current, hours, days)
    }

    /** The geocoder's `results`; an answer without the key means no match. */
    fun cities(root: JsonObject): List<WeatherCity> {
        val results = root["results"] as? JsonArray ?: return emptyList()
        val out = ArrayList<WeatherCity>(results.size)
        for (e in results) {
            val o = e as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: continue
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: continue
            val lat = o.dbl("latitude") ?: continue
            val lon = o.dbl("longitude") ?: continue
            out += WeatherCity(
                id = id, name = name, region = o.str("admin1"), country = o.str("country"), countryCode = o.str("country_code"),
                lat = lat, lon = lon, zone = o.str("timezone") ?: "UTC",
            )
        }
        return out
    }

    /** "2026-11-19T07:22" → 442; -1 when absent or malformed. */
    internal fun minuteOfDay(iso: String?): Int {
        if (iso == null || iso.length < 16 || iso[13] != ':') return -1
        val h = iso.substring(11, 13).toIntOrNull() ?: return -1
        val m = iso.substring(14, 16).toIntOrNull() ?: return -1
        return h * 60 + m
    }

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.strings(key: String): List<String>? =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonArray?.dbl(i: Int): Double? = this?.getOrNull(i).prim()?.doubleOrNull
    private fun JsonArray?.int(i: Int): Int? = this?.getOrNull(i).prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonArray?.str(i: Int): String? = this?.getOrNull(i).prim()?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(key: String): Double? = this[key].prim()?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key].prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.str(key: String): String? = this[key].prim()?.takeIf { it.isString }?.content
}
