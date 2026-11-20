package app.thebox.core.weather

/**
 * The background picture behind the Weather page: the sky's state crossed with the time of day in the city
 * (owner request 2026-11-19). `:ui` holds one photograph per scene.
 */
enum class WeatherScene {
    CLEAR_DAY, CLEAR_NIGHT, DUSK, PARTLY_DAY, PARTLY_NIGHT, CLOUDY_DAY, CLOUDY_NIGHT, RAIN_DAY, RAIN_NIGHT, SNOW_DAY, SNOW_NIGHT,
    STORM, FOG;

    companion object {
        /** A clear or partly cloudy sky within this many minutes of sunrise or sunset shows [DUSK]. */
        const val TWILIGHT_MIN = 40

        /** [nowMin], [sunriseMin] and [sunsetMin] are minutes of the city's day; -1 = unknown (then [isDay] alone decides). */
        fun of(sky: Sky, isDay: Boolean, nowMin: Int, sunriseMin: Int, sunsetMin: Int): WeatherScene {
            val twilight = nowMin >= 0 && (
                (sunriseMin >= 0 && kotlin.math.abs(nowMin - sunriseMin) <= TWILIGHT_MIN) ||
                    (sunsetMin >= 0 && kotlin.math.abs(nowMin - sunsetMin) <= TWILIGHT_MIN)
                )
            return when (sky) {
                Sky.CLEAR -> if (twilight) DUSK else if (isDay) CLEAR_DAY else CLEAR_NIGHT
                Sky.PARTLY -> if (twilight) DUSK else if (isDay) PARTLY_DAY else PARTLY_NIGHT
                Sky.CLOUDY -> if (isDay) CLOUDY_DAY else CLOUDY_NIGHT
                Sky.DRIZZLE, Sky.RAIN -> if (isDay) RAIN_DAY else RAIN_NIGHT
                Sky.SNOW -> if (isDay) SNOW_DAY else SNOW_NIGHT
                Sky.STORM -> STORM
                Sky.FOG -> FOG
            }
        }

        fun of(f: Forecast): WeatherScene {
            val d = f.days.getOrNull(f.nowDay)
            return of(f.current.condition.sky, f.current.isDay, f.nowMinute, d?.sunriseMin ?: -1, d?.sunsetMin ?: -1)
        }
    }
}

/** Display maths shared by the page's tiles; metric in, the chosen units out. */
object WeatherUnitsMath {
    fun temp(c: Double, units: WeatherUnits): Int =
        Math.round(if (units == WeatherUnits.IMPERIAL) c * 9.0 / 5.0 + 32.0 else c).toInt()

    fun speed(kmh: Double, units: WeatherUnits): Int = Math.round(if (units == WeatherUnits.IMPERIAL) kmh * 0.621371 else kmh).toInt()

    /** Millimetres, or inches ×100 (so 0.25 in = 25) for imperial: the caller formats. */
    fun precipTenths(mm: Double, units: WeatherUnits): Int =
        Math.round(if (units == WeatherUnits.IMPERIAL) mm / 25.4 * 100.0 else mm * 10.0).toInt()

    /** 0..7 = N, NE, E, SE, S, SW, W, NW (the direction the wind comes from). */
    fun compass(deg: Int): Int = (((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8

    /** 0 low, 1 moderate, 2 high, 3 very high, 4 extreme (WHO bands). */
    fun uvBand(uv: Double): Int = when {
        uv < 3 -> 0
        uv < 6 -> 1
        uv < 8 -> 2
        uv < 11 -> 3
        else -> 4
    }

    /** 0 dry, 1 comfortable, 2 humid, 3 damp. */
    fun humidityBand(pct: Int): Int = when {
        pct < 30 -> 0
        pct < 60 -> 1
        pct < 80 -> 2
        else -> 3
    }
}
