package app.thebox.ui.weather

import app.thebox.core.weather.WeatherScene
import app.thebox.ui.R

/**
 * One photograph per [WeatherScene] (`res/drawable-nodpi/weather_bg_*.webp`, 1280 × 720). Sources, authors and licences:
 * `assets/licenses/weather-backgrounds.txt`.
 */
internal object WeatherBackgrounds {
    fun of(scene: WeatherScene): Int = when (scene) {
        WeatherScene.CLEAR_DAY -> R.drawable.weather_bg_clear_day
        WeatherScene.CLEAR_NIGHT -> R.drawable.weather_bg_clear_night
        WeatherScene.DUSK -> R.drawable.weather_bg_dusk
        WeatherScene.PARTLY_DAY -> R.drawable.weather_bg_partly_day
        WeatherScene.PARTLY_NIGHT -> R.drawable.weather_bg_partly_night
        WeatherScene.CLOUDY_DAY -> R.drawable.weather_bg_cloudy_day
        WeatherScene.CLOUDY_NIGHT -> R.drawable.weather_bg_cloudy_night
        WeatherScene.RAIN_DAY -> R.drawable.weather_bg_rain_day
        WeatherScene.RAIN_NIGHT -> R.drawable.weather_bg_rain_night
        WeatherScene.SNOW_DAY -> R.drawable.weather_bg_snow_day
        WeatherScene.SNOW_NIGHT -> R.drawable.weather_bg_snow_night
        WeatherScene.STORM -> R.drawable.weather_bg_storm
        WeatherScene.FOG -> R.drawable.weather_bg_fog
    }
}
