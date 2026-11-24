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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.thebox.core.weather.WeatherCity
import app.thebox.core.weather.WeatherPrefs
import app.thebox.core.weather.WeatherUnits
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val WEATHER_PANEL_TAG = "weather_city_panel"
const val WEATHER_ADD_TAG = "weather_city_add"
const val WEATHER_UNITS_TAG = "weather_units"
const val WEATHER_SEARCH_FIELD_TAG = "weather_search_field"
const val WEATHER_SEARCH_STATUS_TAG = "weather_search_status"
internal fun weatherCityRowTag(id: Long) = "weather_city_row:$id"
internal fun weatherCityDefaultTag(id: Long) = "weather_city_default:$id"
internal fun weatherCityRemoveTag(id: Long) = "weather_city_remove:$id"
internal fun weatherResultTag(i: Int) = "weather_result:$i"

internal enum class CityPanelMode { LIST, SEARCH }

/**
 * The city panel, a sheet on the right. LIST: the saved cities (OK shows one; each row has "Set as default" and
 * "Remove" to its right), then "Add a city" and the units. SEARCH: a text field and the cities the geocoder found
 * (OK adds one and shows it).
 */
@Composable
internal fun CityPanel(
    mode: CityPanelMode,
    ui: WeatherUiState,
    onMode: (CityPanelMode) -> Unit,
    onPick: (Long) -> Unit,
    onDefault: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onUnits: (WeatherUnits) -> Unit,
    onQuery: (String) -> Unit,
    onAdd: (WeatherCity) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.testTag(WEATHER_PANEL_TAG).width(720.dp).fillMaxHeight().background(GuideUi.OverlayFill)
                .border(1.dp, GuideUi.OverlayBorder).padding(horizontal = 40.dp, vertical = 56.dp),
        ) {
            when (mode) {
                CityPanelMode.LIST -> CityList(ui, onMode, onPick, onDefault, onRemove, onUnits)
                CityPanelMode.SEARCH -> CitySearch(ui, onQuery, onAdd)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CityList(
    ui: WeatherUiState,
    onMode: (CityPanelMode) -> Unit,
    onPick: (Long) -> Unit,
    onDefault: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onUnits: (WeatherUnits) -> Unit,
) {
    val prefs = ui.prefs
    val rows = remember(prefs.cities.map { it.id }) { prefs.cities.associate { it.id to FocusRequester() } }
    val add = remember { FocusRequester() }
    val units = remember { FocusRequester() }
    val defaultId = prefs.defaultCity?.id
    // Focus opens on the shown city; after a removal it falls to the first row, or to "Add a city".
    LaunchedEffect(prefs.cities.size) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { (rows[ui.city?.id] ?: rows.values.firstOrNull() ?: add).requestFocus() }
    }
    Line(stringResource(R.string.weather_cities), GuideUi.sans(40, FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    Line(stringResource(R.string.weather_cities_hint), GuideUi.sans(24, color = GuideUi.Fg60), maxLines = 2)
    Spacer(Modifier.height(24.dp))
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        prefs.cities.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelRow(rows.getValue(c.id), weatherCityRowTag(c.id), Modifier.weight(1f), onClick = { onPick(c.id) }) { color ->
                    Line(c.label, GuideUi.sans(26, FontWeight.Medium, color), Modifier.weight(1f))
                    if (c.id == defaultId) Line(stringResource(R.string.weather_default), GuideUi.sans(22, FontWeight.SemiBold, color.copy(alpha = 0.7f)))
                }
                if (c.id != defaultId) {
                    TextButton(stringResource(R.string.weather_set_default), remember(c.id) { FocusRequester() }, weatherCityDefaultTag(c.id)) {
                        // The button leaves with the click (the city is now the default): focus moves to the city's row first.
                        runCatching { rows.getValue(c.id).requestFocus() }
                        onDefault(c.id)
                    }
                }
                TextButton(
                    "✕", remember(c.id) { FocusRequester() }, weatherCityRemoveTag(c.id), width = 64,
                    description = stringResource(R.string.weather_remove, c.label),
                ) { onRemove(c.id) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val full = prefs.cities.size >= WeatherPrefs.MAX_CITIES
    PanelRow(add, WEATHER_ADD_TAG, Modifier.fillMaxWidth(), enabled = !full, onClick = { onMode(CityPanelMode.SEARCH) }) { color ->
        Line(stringResource(if (full) R.string.weather_cities_full else R.string.weather_add_city), GuideUi.sans(26, FontWeight.Medium, if (full) color.copy(alpha = 0.4f) else color))
    }
    Spacer(Modifier.height(8.dp))
    PanelRow(units, WEATHER_UNITS_TAG, Modifier.fillMaxWidth(), onClick = {
        onUnits(if (prefs.units == WeatherUnits.METRIC) WeatherUnits.IMPERIAL else WeatherUnits.METRIC)
    }) { color ->
        Line(stringResource(R.string.weather_units), GuideUi.sans(26, FontWeight.Medium, color), Modifier.weight(1f))
        Line(
            stringResource(if (prefs.units == WeatherUnits.METRIC) R.string.weather_units_metric else R.string.weather_units_imperial),
            GuideUi.sans(24, color = color.copy(alpha = 0.75f)),
        )
    }
}

@Composable
private fun PanelRow(
    requester: FocusRequester,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Color) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    FocusBox(requester, tag, modifier, enabled = enabled, onClick = onClick) { focused ->
        val m = if (focused) Modifier.background(GuideUi.Fg, shape).border(GuideUi.FocusRing, GuideUi.Accent, shape) else Modifier.background(GuideUi.White08, shape)
        Row(
            m.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(if (focused) GuideUi.Canvas else GuideUi.Fg) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CitySearch(ui: WeatherUiState, onQuery: (String) -> Unit, onAdd: (WeatherCity) -> Unit) {
    val field = remember { FocusRequester() }
    val s = ui.search
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    Line(stringResource(R.string.weather_add_city), GuideUi.sans(40, FontWeight.SemiBold))
    Spacer(Modifier.height(24.dp))
    SearchField(s.query, field, onQuery)
    Spacer(Modifier.height(20.dp))
    val saved = ui.prefs.cities.map { it.id }.toSet()
    val status = when {
        s.query.trim().length < 2 -> stringResource(R.string.weather_search_hint)
        s.loading -> stringResource(R.string.weather_searching)
        s.failed != null -> WeatherText.failure(s.failed)
        s.searched && s.results.isEmpty() -> stringResource(R.string.weather_search_none, s.query.trim())
        else -> null
    }
    if (status != null) Line(status, GuideUi.sans(24, color = GuideUi.Fg60), Modifier.testTag(WEATHER_SEARCH_STATUS_TAG), maxLines = 3)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.results.forEachIndexed { i, c ->
            val has = c.id in saved
            PanelRow(remember(c.id) { FocusRequester() }, weatherResultTag(i), Modifier.fillMaxWidth(), enabled = !has, onClick = { onAdd(c) }) { color ->
                Column(Modifier.weight(1f)) {
                    Line(c.name, GuideUi.sans(26, FontWeight.Medium, if (has) color.copy(alpha = 0.4f) else color))
                    val where = listOfNotNull(c.region, c.country).filter { it.isNotBlank() }.joinToString(", ")
                    if (where.isNotEmpty()) Line(where, GuideUi.sans(22, color = color.copy(alpha = if (has) 0.35f else 0.65f)))
                }
                if (has) Line(stringResource(R.string.weather_added), GuideUi.sans(22, color = color.copy(alpha = 0.5f)))
            }
        }
    }
}

/** The search field: the on-screen keyboard's action and D-pad Down both leave it for the first result (as in Search). */
@Composable
private fun SearchField(text: String, focus: FocusRequester, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().height(72.dp).background(GuideUi.White08, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) GuideUi.Accent else GuideUi.White16, shape).padding(horizontal = 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Line(stringResource(R.string.weather_search_field), GuideUi.sans(26, color = GuideUi.Fg50))
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            textStyle = GuideUi.sans(26, color = GuideUi.Fg),
            cursorBrush = SolidColor(GuideUi.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) {
                        keyboard?.hide()
                        focusManager.moveFocus(FocusDirection.Down)
                    } else false
                }
                .testTag(WEATHER_SEARCH_FIELD_TAG),
        )
    }
}
