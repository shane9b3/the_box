package app.thebox.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.thebox.core.weather.DayWeather
import app.thebox.core.weather.Forecast
import app.thebox.core.weather.HourWeather
import app.thebox.core.weather.WeatherScene
import app.thebox.core.weather.WeatherUnits
import app.thebox.core.weather.WeatherUnitsMath
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.HeaderClock
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.guide.screen.TimeFormats
import app.thebox.ui.theme.DesignTokens
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val WEATHER_SCREEN_TAG = "weather_screen"
const val WEATHER_BACKGROUND_TAG = "weather_background"
const val WEATHER_CITY_TAG = "weather_city"
const val WEATHER_FORECAST_TAB_TAG = "weather_page_forecast"
const val WEATHER_RADAR_TAB_TAG = "weather_page_radar"
const val WEATHER_MESSAGE_TAG = "weather_message"
const val WEATHER_ACTION_TAG = "weather_action"
const val WEATHER_HOURLY_TAG = "weather_hourly"
const val WEATHER_HOURLY_TITLE_TAG = "weather_hourly_title"
const val WEATHER_TEMP_TAG = "weather_temp"
internal fun weatherDayTag(i: Int) = "weather_day:$i"
internal fun weatherHourTag(i: Int) = "weather_hour:$i"

/**
 * The Weather page (owner request 2026-11-19; docs/decisions/2026-11-19-weather-page.md). It is on Compose focus like
 * Sports: the router passes every key but BACK, and one Back returns to the guide. While the city panel is open the
 * screen holds BACK ([onBackFreeChanged] false) and closes the panel itself.
 *
 * Layout on the 1920 × 1080 design canvas (1 dp = 1 design px under the root's scale): the top bar at (64, 56) with the
 * tabs, the Forecast / Live radar switch and the clock; the current conditions on the left with six tiles on the right;
 * then one panel holding the hourly strip and the seven day cards. No footer hints (as in the guide).
 *
 * Focus: the day cards (moving across them changes the hourly strip to that day), the city chip, and the page switch.
 */
@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    clock24: Boolean,
    modifier: Modifier = Modifier,
    onBackFreeChanged: (Boolean) -> Unit = {},
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<CityPanelMode?>(null) }
    val radarState = remember { RadarState() }

    DisposableEffect(viewModel) {
        viewModel.onEntered()
        onDispose {
            viewModel.onLeft()
            onBackFreeChanged(true)
        }
    }
    LaunchedEffect(panel) { onBackFreeChanged(panel == null) }

    val cityChip = remember { FocusRequester() }
    val forecastTab = remember { FocusRequester() }
    val radarTab = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    val days = remember { List(8) { FocusRequester() } }

    val body = ui.body
    val forecast = (body as? WeatherBody.Ready)?.forecast
    val radar = ui.page == WeatherPage.RADAR && ui.city != null

    // Initial focus: today's card once a forecast is shown; the action button (Add a city / Try again) otherwise.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(body is WeatherBody.Ready, body is WeatherBody.Loading, panel) {
        if (panel != null || radar) return@LaunchedEffect
        if (body is WeatherBody.Loading) { settled = false; runCatching { forecastTab.requestFocus() }; return@LaunchedEffect }
        if (settled) return@LaunchedEffect
        settled = true
        androidx.compose.runtime.withFrameNanos { }
        runCatching { (if (body is WeatherBody.Ready) days[ui.day.coerceIn(0, days.lastIndex)] else action).requestFocus() }
    }
    // The radar page opens with Play/Pause focused once its bar exists; back on the forecast the switch keeps focus.
    LaunchedEffect(radar, ui.radar.frames != null, ui.radar.failed) {
        if (radar && panel == null && (ui.radar.frames != null || ui.radar.failed != null)) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { play.requestFocus() }
        }
    }

    fun closePanel() {
        val wasSearch = panel == CityPanelMode.SEARCH
        viewModel.onSearchClosed()
        // Back from the search returns to the city list when there is one to return to.
        panel = if (wasSearch && ui.prefs.cities.isNotEmpty()) CityPanelMode.LIST else null
        if (panel == null) scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { (if (ui.city == null) action else cityChip).requestFocus() }
        }
    }

    Box(
        modifier.fillMaxSize().testTag(WEATHER_SCREEN_TAG).onPreviewKeyEvent { e ->
            if (panel != null && (e.key == Key.Back || e.key == Key.Escape || e.key == Key.ButtonB)) {
                if (e.type == KeyEventType.KeyUp) closePanel()
                true
            } else false
        },
    ) {
        val city = ui.city
        if (radar && city != null) RadarMap(city, ui.radar.frames, radarState) else Background(forecast)

        androidx.compose.runtime.CompositionLocalProvider(LocalWeatherFocusAllowed provides (panel == null)) {
        Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 56.dp, bottom = 40.dp)) {
            TopBar(ui.page, clock24, forecastTab, radarTab, canRadar = city != null, radarBackdrop = radar, onPage = viewModel::onPage)
            if (radar && city != null) {
                RadarControls(city, ui.radar, radarState, clock24, play, up = radarTab, onRetry = viewModel::onRetry)
            } else {
                Spacer(Modifier.height(40.dp))
                when (body) {
                    is WeatherBody.Ready -> ForecastBody(
                        ui, body.forecast, clock24, cityChip, days,
                        onCity = { panel = CityPanelMode.LIST },
                        onDay = viewModel::onDay,
                    )
                    WeatherBody.NoCity -> Message(
                        stringResource(R.string.weather_no_city), stringResource(R.string.weather_add_city), action, forecastTab,
                    ) { panel = CityPanelMode.SEARCH }
                    WeatherBody.Loading -> Message(stringResource(R.string.weather_loading), null, action, forecastTab) {}
                    // The city chip lives in the forecast, so a city that keeps failing would leave no way to another
                    // one: the failure offers the city panel itself (review 2026-11-23).
                    is WeatherBody.Failed -> Message(
                        WeatherText.failure(body.kind), stringResource(R.string.weather_retry), action, forecastTab,
                        second = stringResource(R.string.weather_change_city), secondRequester = cityChip, onSecond = { panel = CityPanelMode.LIST },
                    ) {
                        settled = false
                        viewModel.onRetry()
                    }
                }
            }
        }
        }

        panel?.let { mode ->
            CityPanel(
                mode = mode, ui = ui,
                onMode = { panel = it },
                onPick = { id ->
                    viewModel.onCity(id)
                    settled = false
                    panel = null
                },
                onDefault = viewModel::onSetDefault,
                onRemove = viewModel::onRemove,
                onUnits = viewModel::onUnits,
                onQuery = viewModel::onQuery,
                onAdd = { c ->
                    viewModel.onAdd(c)
                    settled = false
                    panel = null
                },
            )
        }
    }
}

// ------------------------------------------------------------------ background

/**
 * The photograph for the city's sky and time of day under a scrim (darker at the bottom, where the panel is). Before a
 * forecast there is no picture: the canvas with the page's blue wash.
 */
@Composable
private fun Background(forecast: Forecast?) {
    val scene = forecast?.let { WeatherScene.of(it) }
    val loader = LocalShellImageLoader.current
    Box(Modifier.fillMaxSize().background(GuideUi.Canvas).testTag(WEATHER_BACKGROUND_TAG)) {
        if (scene != null && loader != null) {
            coil3.compose.AsyncImage(
                model = WeatherBackgrounds.of(scene), contentDescription = null, imageLoader = loader,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xFF07101C).copy(alpha = if (scene == null) 0f else 0.62f),
                    0.45f to Color(0xFF07101C).copy(alpha = if (scene == null) 0f else 0.70f),
                    1f to GuideUi.Canvas.copy(alpha = 0.90f),
                ),
            ),
        )
        if (scene == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF0B2A47), 0.6f to GuideUi.Canvas)))
        }
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(
    page: WeatherPage,
    clock24: Boolean,
    forecastTab: FocusRequester,
    radarTab: FocusRequester,
    canRadar: Boolean,
    radarBackdrop: Boolean,
    onPage: (WeatherPage) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        // The guide's tabs, shown for orientation (Back returns to the guide; they are not focusable here, as in Search).
        val tabs = Modifier.then(if (radarBackdrop) Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(DesignTokens.Radii.Pill)) else Modifier)
        Row(tabs.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(R.string.guide_tab_guide, R.string.guide_tab_search, R.string.sports_tab).forEach { id ->
                Line(stringResource(id), GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg60), Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
            Box(
                Modifier.background(GuideUi.White12, RoundedCornerShape(DesignTokens.Radii.Pill))
                    .border(1.dp, GuideUi.White16, RoundedCornerShape(DesignTokens.Radii.Pill)).padding(horizontal = 18.dp, vertical = 8.dp),
            ) { Line(stringResource(R.string.weather_tab), GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg)) }
        }
        Spacer(Modifier.weight(1f))
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.background(Color.Black.copy(alpha = 0.45f), shape).border(1.dp, GuideUi.White16, shape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Segment(stringResource(R.string.weather_page_forecast), page == WeatherPage.FORECAST, false, forecastTab, WEATHER_FORECAST_TAB_TAG, true) { onPage(WeatherPage.FORECAST) }
            Segment(stringResource(R.string.weather_page_radar), page == WeatherPage.RADAR, true, radarTab, WEATHER_RADAR_TAB_TAG, canRadar) { onPage(WeatherPage.RADAR) }
        }
        Spacer(Modifier.width(28.dp))
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000L - value % 60_000L)
            }
        }
        val locale: Locale = LocalConfiguration.current.locales[0]
        HeaderClock(TimeFormats.format("EEE d MMM", locale, Date(now)), TimeFormats.format(if (clock24) "HH:mm" else "h:mm a", locale, Date(now)))
    }
}

/** One half of the Forecast / Live radar switch: the shown page is the light one; focus adds the accent ring. */
@Composable
private fun Segment(text: String, selected: Boolean, dot: Boolean, requester: FocusRequester, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    FocusBox(requester, tag, enabled = enabled, onClick = onClick) { focused ->
        var m = Modifier.height(46.dp)
        m = if (selected) m.background(GuideUi.Fg, shape) else if (focused) m.background(GuideUi.White22, shape) else m
        if (focused) m = m.border(GuideUi.FocusRing, GuideUi.Accent, shape)
        Row(m.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (dot) Box(Modifier.size(8.dp).background(DesignTokens.Colors.Live, CircleShape))
            val color = if (selected) GuideUi.Canvas else GuideUi.Fg
            Line(text, GuideUi.sans(24, FontWeight.SemiBold, if (enabled) color else color.copy(alpha = 0.35f)))
        }
    }
}

// ------------------------------------------------------------------ messages

@Composable
private fun Message(
    text: String,
    button: String?,
    action: FocusRequester,
    up: FocusRequester,
    second: String? = null,
    secondRequester: FocusRequester? = null,
    onSecond: () -> Unit = {},
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Line(text, GuideUi.sans(32, color = GuideUi.Fg), Modifier.testTag(WEATHER_MESSAGE_TAG), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (button != null) TextButton(button, action, WEATHER_ACTION_TAG, up = up, onClick = onClick)
                if (second != null && secondRequester != null) TextButton(second, secondRequester, WEATHER_CITY_TAG, up = up, onClick = onSecond)
            }
        }
    }
}

// ------------------------------------------------------------------ forecast

@Composable
private fun ForecastBody(
    ui: WeatherUiState,
    f: Forecast,
    clock24: Boolean,
    cityChip: FocusRequester,
    days: List<FocusRequester>,
    onCity: () -> Unit,
    onDay: (Int) -> Unit,
) {
    val units = ui.prefs.units
    val today = f.days[f.nowDay.coerceIn(0, f.days.lastIndex)]
    val selected = ui.day.coerceIn(0, f.days.lastIndex)
    val locale: Locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Now: the city, the date, the temperature and what the sky is doing.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val chip = RoundedCornerShape(8.dp)
                    FocusBox(cityChip, WEATHER_CITY_TAG, down = days[selected], onClick = onCity) { focused ->
                        val m = if (focused) Modifier.background(GuideUi.Fg, chip).border(GuideUi.FocusRing, GuideUi.Accent, chip)
                        else Modifier.background(Color.Black.copy(alpha = 0.35f), chip).border(1.dp, GuideUi.White22, chip)
                        Row(m.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val color = if (focused) GuideUi.Canvas else GuideUi.Fg
                            Line(ui.city?.label.orEmpty(), GuideUi.mono(22, color = color))
                            Line("▾", GuideUi.sans(18, color = color.copy(alpha = 0.7f)))
                        }
                    }
                    Line(WeatherText.date(today.date, "EEEE d MMMM", locale), GuideUi.sans(26, color = GuideUi.Fg.copy(alpha = 0.8f)))
                    Box(Modifier.background(DesignTokens.Colors.OnNowFill, RoundedCornerShape(DesignTokens.Radii.Pill)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Line(stringResource(R.string.weather_now), GuideUi.sans(20, FontWeight.SemiBold, DesignTokens.Colors.AccentText, 0.06f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    WeatherIcon(f.current.condition.sky, f.current.isDay, Modifier.size(150.dp))
                    Line(WeatherText.temp(f.current.tempC, units), GuideUi.sans(176, FontWeight.Bold, GuideUi.Fg, -0.03f), Modifier.testTag(WEATHER_TEMP_TAG))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Line(WeatherText.condition(f.current.condition), GuideUi.sans(46, FontWeight.SemiBold))
                        Line(
                            stringResource(
                                R.string.weather_feels, WeatherText.temp(f.current.feelsC, units), WeatherText.temp(today.maxC, units), WeatherText.temp(today.minC, units),
                            ),
                            GuideUi.mono(24, color = GuideUi.Fg.copy(alpha = 0.8f)),
                        )
                    }
                }
                Line(WeatherText.summary(f, clock24), GuideUi.sans(27, color = GuideUi.Fg.copy(alpha = 0.92f)).copy(lineHeight = androidx.compose.ui.unit.TextUnit(36f, androidx.compose.ui.unit.TextUnitType.Sp)), Modifier.width(820.dp), maxLines = 2)
            }
            Tiles(f, today, units, clock24, Modifier.width(816.dp))
        }
        Spacer(Modifier.height(20.dp))
        val panel = RoundedCornerShape(DesignTokens.Radii.Panel)
        Column(
            Modifier.fillMaxWidth().background(WeatherUi.Glass, panel).border(1.dp, WeatherUi.GlassBorder, panel).padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            val title = if (selected == f.nowDay) stringResource(R.string.weather_next_hours)
            else stringResource(R.string.weather_day_hours, WeatherText.date(f.days[selected].date, "EEEE", locale))
            SectionLabel(title, Modifier.testTag(WEATHER_HOURLY_TITLE_TAG))
            Spacer(Modifier.height(10.dp))
            Hourly(f.hourly(selected), units, clock24)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(GuideUi.White10))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.weather_days, f.days.size), Modifier.weight(1f))
                // Open-Meteo's licence (CC BY 4.0) asks for this credit where the data is shown.
                Line(stringResource(R.string.weather_credit), GuideUi.sans(17, color = GuideUi.Fg50), Modifier.padding(end = 14.dp))
            }
            Spacer(Modifier.height(12.dp))
            Days(f, selected, units, locale, days, cityChip, onDay)
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Line(text.uppercase(LocalConfiguration.current.locales[0]), GuideUi.sans(18, FontWeight.SemiBold, GuideUi.Fg60, 0.08f), modifier.padding(horizontal = 14.dp))
}

@Composable
private fun Tiles(f: Forecast, today: DayWeather, units: WeatherUnits, clock24: Boolean, modifier: Modifier) {
    val c = f.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(
                stringResource(R.string.weather_tile_precip), today.popPct?.let { "$it%" } ?: "—",
                if (today.precipMm >= 0.05) stringResource(R.string.weather_tile_precip_expected, WeatherText.amount(today.precipMm, units))
                else stringResource(R.string.weather_tile_precip_none),
            )
            Tile(
                stringResource(R.string.weather_tile_wind), WeatherText.speed(c.windKmh, units),
                stringResource(R.string.weather_tile_wind_sub, WeatherText.compassShort(c.windDirDeg), WeatherText.speed(c.gustKmh, units)),
            )
            Tile(stringResource(R.string.weather_tile_humidity), "${c.humidityPct}%", stringArrayResource(R.array.weather_humidity)[WeatherUnitsMath.humidityBand(c.humidityPct)])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val uv = today.uvMax
            Tile(
                stringResource(R.string.weather_tile_uv), uv?.let { Math.round(it).toString() } ?: "—",
                uv?.let { stringArrayResource(R.array.weather_uv)[WeatherUnitsMath.uvBand(it)] }.orEmpty(),
            )
            Tile(
                stringResource(R.string.weather_tile_sunrise), WeatherText.minute(today.sunriseMin, clock24),
                if (today.daylightSec > 0) stringResource(R.string.weather_tile_daylight, WeatherText.span(today.daylightSec / 60)) else "",
            )
            val toSet = today.sunsetMin - f.nowMinute
            Tile(
                stringResource(R.string.weather_tile_sunset), WeatherText.minute(today.sunsetMin, clock24),
                when {
                    today.sunsetMin < 0 -> ""
                    toSet > 0 -> stringResource(R.string.weather_tile_sets_in, WeatherText.span(toSet))
                    else -> stringResource(R.string.weather_tile_set_ago, WeatherText.span(-toSet))
                },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(label: String, value: String, sub: String) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.weight(1f).height(150.dp).background(WeatherUi.Glass, shape).border(1.dp, WeatherUi.GlassBorder, shape).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Line(label.uppercase(LocalConfiguration.current.locales[0]), GuideUi.sans(17, FontWeight.SemiBold, GuideUi.Fg60, 0.08f))
        Line(value, GuideUi.sans(42, FontWeight.SemiBold))
        Line(sub, GuideUi.sans(20, color = GuideUi.Fg70))
    }
}

/** Twelve columns: the time, the sky, the temperature and the chance of rain or snow (blue from 30 %). */
@Composable
private fun Hourly(hours: List<HourWeather>, units: WeatherUnits, clock24: Boolean) {
    Row(Modifier.fillMaxWidth().height(150.dp).testTag(WEATHER_HOURLY_TAG)) {
        for (i in 0 until Forecast.HOURLY_SLOTS) {
            val h = hours.getOrNull(i)
            Column(
                Modifier.weight(1f).fillMaxHeight().testTag(weatherHourTag(i)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (h != null) {
                    Line(WeatherText.hour(h.hour, clock24), GuideUi.mono(19, color = GuideUi.Fg70))
                    WeatherIcon(h.condition.sky, h.isDay, Modifier.size(46.dp))
                    Line(WeatherText.temp(h.tempC, units), GuideUi.sans(28, FontWeight.SemiBold))
                    val pop = h.popPct ?: 0
                    Line(if (pop > 0) "$pop%" else "—", GuideUi.mono(17, color = if (pop >= 30) WeatherUi.RainText else GuideUi.Fg55))
                }
            }
            if (i < Forecast.HOURLY_SLOTS - 1) Box(Modifier.width(1.dp).fillMaxHeight().background(GuideUi.White08))
        }
    }
}

/** The day cards. Focus selects: moving across them changes the hourly strip to that day. */
@Composable
private fun Days(
    f: Forecast,
    selected: Int,
    units: WeatherUnits,
    locale: Locale,
    requesters: List<FocusRequester>,
    up: FocusRequester,
    onDay: (Int) -> Unit,
) {
    val weekMin = f.days.minOf { it.minC }
    val weekMax = f.days.maxOf { it.maxC }
    val range = (weekMax - weekMin).coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(176.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        f.days.forEachIndexed { i, d ->
            if (i >= requesters.size) return@forEachIndexed
            val shape = RoundedCornerShape(14.dp)
            FocusBox(requesters[i], weatherDayTag(i), Modifier.weight(1f).fillMaxHeight(), up = up, onFocused = { onDay(i) }, onClick = { onDay(i) }) { focused ->
                val m = when {
                    focused -> Modifier.background(GuideUi.Fg, shape).border(GuideUi.FocusRing, GuideUi.Accent, shape)
                    i == selected -> Modifier.background(GuideUi.White16, shape).border(1.dp, GuideUi.White22, shape)
                    else -> Modifier.background(WeatherUi.Card, shape).border(1.dp, GuideUi.White08, shape)
                }
                val fg = if (focused) GuideUi.Canvas else GuideUi.Fg
                Column(m.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Line(
                            if (i == f.nowDay) stringResource(R.string.weather_today) else WeatherText.date(d.date, "EEE", locale),
                            GuideUi.sans(26, FontWeight.SemiBold, fg), Modifier.weight(1f),
                        )
                        Line(WeatherText.date(d.date, "d MMM", locale), GuideUi.mono(17, color = fg.copy(alpha = 0.65f)))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WeatherIcon(d.condition.sky, true, Modifier.size(50.dp))
                        Spacer(Modifier.weight(1f))
                        d.popPct?.takeIf { it > 0 }?.let { Line("$it%", GuideUi.mono(18, color = fg.copy(alpha = 0.75f))) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Line(WeatherText.temp(d.maxC, units), GuideUi.sans(28, FontWeight.SemiBold, fg), Modifier.weight(1f))
                        Line(WeatherText.temp(d.minC, units), GuideUi.sans(28, color = fg.copy(alpha = 0.6f)))
                    }
                    // Where this day's low-to-high sits in the week's range.
                    val start = ((d.minC - weekMin) / range).toFloat().coerceIn(0f, 0.98f)
                    val end = ((d.maxC - weekMin) / range).toFloat().coerceIn(start + 0.02f, 1f)
                    Row(Modifier.fillMaxWidth().height(4.dp).background(fg.copy(alpha = 0.16f), RoundedCornerShape(2.dp))) {
                        if (start > 0f) Spacer(Modifier.weight(start))
                        Box(
                            Modifier.weight(end - start).fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFF5CC2F2), Color(0xFFF2B45C))), RoundedCornerShape(2.dp)),
                        )
                        if (end < 1f) Spacer(Modifier.weight(1f - end))
                    }
                }
            }
        }
    }
}
