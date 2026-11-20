package app.thebox.ui.weather

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.WeatherDomain
import app.thebox.core.weather.Condition
import app.thebox.core.weather.CurrentWeather
import app.thebox.core.weather.DayWeather
import app.thebox.core.weather.Forecast
import app.thebox.core.weather.HourWeather
import app.thebox.core.weather.RadarFrame
import app.thebox.core.weather.RadarFrames
import app.thebox.core.weather.RadarSource
import app.thebox.core.weather.WeatherCity
import app.thebox.core.weather.WeatherFail
import app.thebox.core.weather.WeatherPrefs
import app.thebox.core.weather.WeatherRepository
import app.thebox.core.weather.WeatherResult
import app.thebox.core.weather.WeatherUnits
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Weather page (docs/decisions/2026-11-19-weather-page.md): first run, the forecast, the day cards, cities and radar. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class WeatherScreenTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val london = WeatherCity(1, "London", "Ontario", "Canada", "CA", 42.98, -81.23, "America/Toronto")
    private val paris = WeatherCity(2, "Paris", "Île-de-France", "France", "FR", 48.85, 2.35, "Europe/Paris")

    private class FakeRepo : WeatherRepository {
        var forecastAnswer: (WeatherCity) -> WeatherResult<Forecast> = { WeatherResult.Ok(forecast(it.id)) }
        var found: List<WeatherCity> = emptyList()
        val forecastCalls = ArrayList<Long>()
        val searchCalls = ArrayList<String>()
        val radarCalls = ArrayList<Pair<Long, Boolean>>()
        override suspend fun forecast(city: WeatherCity, force: Boolean): WeatherResult<Forecast> { forecastCalls += city.id; return forecastAnswer(city) }
        override suspend fun search(query: String, language: String): WeatherResult<List<WeatherCity>> { searchCalls += query; return WeatherResult.Ok(found) }
        override suspend fun radar(city: WeatherCity, cold: Boolean): WeatherResult<RadarFrames> {
            radarCalls += city.id to cold
            return WeatherResult.Ok(RadarFrames(RadarSource.ENV_CANADA, "", listOf(RadarFrame(1_795_138_800, "2026-11-20T01:00:00Z"), RadarFrame(1_795_139_520, "2026-11-20T01:12:00Z"))))
        }
    }

    private val repo = FakeRepo()
    private val store = FakeSettingsStore()
    private val backFree = ArrayList<Boolean>()
    private lateinit var vm: WeatherViewModel

    private fun show(vararg cities: WeatherCity) {
        if (cities.isNotEmpty()) store.update(WeatherDomain) { WeatherPrefs(cities.toList(), cities.first().id) }
        vm = WeatherViewModel(repo, store) { "en" }
        rule.setContent { BoxTheme { WeatherScreen(vm, clock24 = true, onBackFreeChanged = { backFree += it }) } }
        rule.waitForIdle()
    }

    /** `Key` is a value class, so it cannot be a vararg: the tests pass a list. */
    private fun press(keys: List<Key>) {
        for (k in keys) rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    /** Runs the view model's delayed work (the search debounce) on Robolectric's main looper. */
    private fun idle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    @Test fun withNoCityThePageAsksForOneAndMakesNoRequest() {
        show()
        rule.onNodeWithTag(WEATHER_MESSAGE_TAG).assertTextEquals(str(R.string.weather_no_city))
        rule.onNodeWithTag(WEATHER_ACTION_TAG).assertIsFocused()
        assertTrue(repo.forecastCalls.isEmpty())
        press(listOf(Key.DirectionCenter))
        rule.onNodeWithTag(WEATHER_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(false, backFree.last())
    }

    @Test fun theForecastOpensOnTodayWithTheNextHours() {
        show(london)
        assertEquals(listOf(1L), repo.forecastCalls)
        rule.onNodeWithTag(weatherDayTag(0)).assertIsFocused()
        rule.onNodeWithTag(WEATHER_TEMP_TAG).assertTextEquals("9°")
        rule.onNodeWithTag(WEATHER_HOURLY_TITLE_TAG).assertTextEquals(str(R.string.weather_next_hours).uppercase())
        rule.onNodeWithText("20:00").assertExists() // the first hour after 19:47
        rule.onNodeWithText("London, Ontario").assertExists()
        rule.onNodeWithText(str(R.string.weather_credit)).assertExists()
    }

    @Test fun movingAcrossTheDayCardsChangesTheHourlyStrip() {
        show(london)
        press(listOf(Key.DirectionRight))
        rule.onNodeWithTag(weatherDayTag(1)).assertIsFocused()
        assertEquals(1, vm.state.value.day)
        rule.onNodeWithTag(WEATHER_HOURLY_TITLE_TAG).assertTextEquals(str(R.string.weather_day_hours, "Wednesday").uppercase())
        rule.onNodeWithText("00:00").assertExists()
        rule.onNodeWithText("22:00").assertExists()
        rule.onNodeWithText("21:00").assertDoesNotExist() // every second hour of that day
        press(listOf(Key.DirectionLeft))
        rule.onNodeWithTag(WEATHER_HOURLY_TITLE_TAG).assertTextEquals(str(R.string.weather_next_hours).uppercase())
    }

    @Test fun imperialUnitsConvertTheReadings() {
        store.update(WeatherDomain) { WeatherPrefs(listOf(london), 1, WeatherUnits.IMPERIAL) }
        show()
        rule.onNodeWithTag(WEATHER_TEMP_TAG).assertTextEquals("48°")
        rule.onNodeWithText("11 mph").assertExists()
    }

    @Test fun theCityPanelPicksSetsTheDefaultAndRemoves() {
        show(london, paris)
        press(listOf(Key.DirectionUp))
        rule.onNodeWithTag(WEATHER_CITY_TAG).assertIsFocused()
        press(listOf(Key.DirectionCenter))
        rule.onNodeWithTag(weatherCityRowTag(1)).assertIsFocused()
        assertEquals(false, backFree.last())
        // Back closes the panel (the screen holds BACK while it is open) and focus returns to the chip.
        press(listOf(Key.Back))
        rule.onNodeWithTag(WEATHER_PANEL_TAG).assertDoesNotExist()
        assertEquals(true, backFree.last())
        rule.onNodeWithTag(WEATHER_CITY_TAG).assertIsFocused()

        press(listOf(Key.DirectionCenter))
        // Nothing behind the panel takes focus: Down from the last row's neighbours stays inside it.
        press(listOf(Key.DirectionDown))
        rule.onNodeWithTag(weatherCityRowTag(2)).assertIsFocused()
        press(listOf(Key.DirectionLeft))
        rule.onNodeWithTag(weatherCityRowTag(2)).assertIsFocused()
        press(listOf(Key.DirectionRight))
        rule.onNodeWithTag(weatherCityDefaultTag(2)).assertIsFocused()
        press(listOf(Key.DirectionCenter))
        assertEquals(2L, store.flow(WeatherDomain).value.defaultId)
        // OK on Paris shows it for this visit.
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))
        rule.onNodeWithTag(WEATHER_PANEL_TAG).assertDoesNotExist()
        assertEquals(paris, vm.state.value.city)
        assertEquals(listOf(1L, 2L), repo.forecastCalls)
        rule.onNodeWithText("Paris, Île-de-France").assertExists()

        vm.onRemove(2)
        rule.waitForIdle()
        assertEquals(london, vm.state.value.city)
        assertEquals(1L, store.flow(WeatherDomain).value.defaultId)
    }

    @Test fun addingTheFirstCityMakesItTheDefaultAndShowsIt() {
        show()
        repo.found = listOf(paris)
        vm.onQuery("Par")
        idle()
        assertTrue(vm.state.value.search.searched)
        assertEquals(listOf("Par"), repo.searchCalls)
        vm.onAdd(paris)
        rule.waitForIdle()
        assertEquals(WeatherPrefs(listOf(paris), 2), store.flow(WeatherDomain).value)
        assertEquals(listOf(2L), repo.forecastCalls)
        assertTrue(vm.state.value.body is WeatherBody.Ready)
    }

    @Test fun aShortQueryMakesNoRequest() {
        show()
        vm.onQuery("P")
        idle()
        assertTrue(repo.searchCalls.isEmpty())
        assertFalse(vm.state.value.search.loading)
    }

    @Test fun aFailedForecastSaysWhyAndOffersARetry() {
        repo.forecastAnswer = { WeatherResult.Failed(WeatherFail.OFFLINE) }
        show(london)
        rule.onNodeWithTag(WEATHER_MESSAGE_TAG).assertTextEquals(str(R.string.weather_fail_offline))
        rule.onNodeWithTag(WEATHER_ACTION_TAG).assertIsFocused()
        repo.forecastAnswer = { WeatherResult.Ok(forecast(it.id)) }
        press(listOf(Key.DirectionCenter))
        rule.onNodeWithTag(WEATHER_TEMP_TAG).assertExists()
        rule.onNodeWithTag(weatherDayTag(0)).assertIsFocused()
    }

    @Test fun theRadarPageAsksForFramesAndFocusesPlay() {
        show(london)
        press(listOf(Key.DirectionUp, Key.DirectionUp))
        rule.onNodeWithTag(WEATHER_FORECAST_TAB_TAG).assertIsFocused()
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertEquals(listOf(1L to false), repo.radarCalls)
        rule.onNodeWithTag(RADAR_MAP_TAG).assertExists()
        rule.onNodeWithTag(RADAR_PLAY_TAG).assertIsFocused()
        rule.onNodeWithTag(RADAR_TIME_TAG).assertTextEquals("20:52") // the newest frame, in the city's zone
        press(listOf(Key.DirectionLeft, Key.DirectionCenter)) // step back: pauses on the earlier frame
        rule.onNodeWithTag(RADAR_TIME_TAG).assertTextEquals("20:40")
        // Zoom out to the limit: the button stays focused although it can do no more.
        press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight))
        rule.onNodeWithTag(RADAR_ZOOM_OUT_TAG).assertIsFocused()
        press(listOf(Key.DirectionCenter, Key.DirectionCenter, Key.DirectionCenter))
        rule.onNodeWithTag(RADAR_ZOOM_OUT_TAG).assertIsFocused()
        // Leaving the page drops back to the forecast and the default city.
        vm.onLeft()
        assertEquals(WeatherPage.FORECAST, vm.state.value.page)
    }

    private companion object {
        /** Tuesday 13 October 19:47, light rain, 9°: the mock-up's numbers. Seven days, every hour. */
        fun forecast(cityId: Long): Forecast {
            val days = List(7) { d ->
                DayWeather("2026-10-${13 + d}", if (d == 0) Condition.LIGHT_RAIN else Condition.PARTLY_CLOUDY, 13.0 + d, 6.0 - d, 70 - d * 10, if (d == 0) 4.0 else 0.0, 7 * 60 + 41, 18 * 60 + 48, 1.0, 40_020)
            }
            val hours = List(7 * 24) { i -> HourWeather(i / 24, i % 24, 9.0, Condition.LIGHT_RAIN, 70, i % 24 in 8..18) }
            val current = CurrentWeather(9.0, 6.0, 88, false, Condition.LIGHT_RAIN, 18.0, 225, 35.0, 0.4)
            return Forecast(cityId, 0L, 0, 19 * 60 + 47, current, hours, days)
        }
    }
}
