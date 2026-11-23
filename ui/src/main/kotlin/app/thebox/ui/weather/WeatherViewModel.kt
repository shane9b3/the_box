package app.thebox.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.WeatherDomain
import app.thebox.core.weather.Forecast
import app.thebox.core.weather.RadarFrames
import app.thebox.core.weather.WeatherCity
import app.thebox.core.weather.WeatherFail
import app.thebox.core.weather.WeatherPrefs
import app.thebox.core.weather.WeatherRepository
import app.thebox.core.weather.WeatherResult
import app.thebox.core.weather.WeatherTimings
import app.thebox.core.weather.WeatherUnits
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WeatherPage { FORECAST, RADAR }

sealed interface WeatherBody {
    /** No city has been added yet: the page asks for one. */
    data object NoCity : WeatherBody
    data object Loading : WeatherBody
    data class Ready(val forecast: Forecast) : WeatherBody
    data class Failed(val kind: WeatherFail) : WeatherBody
}

/** The radar page: [frames] null while loading or after a failure ([failed]). */
data class RadarUi(val frames: RadarFrames? = null, val failed: WeatherFail? = null)

/** The "Add a city" search: [results] for [query]; [searched] is true once an answer for it has arrived. */
data class CitySearchUi(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<WeatherCity> = emptyList(),
    val failed: WeatherFail? = null,
    val searched: Boolean = false,
)

data class WeatherUiState(
    val prefs: WeatherPrefs = WeatherPrefs(),
    /** The city shown: the default one unless another was picked for this visit. */
    val city: WeatherCity? = null,
    val body: WeatherBody = WeatherBody.Loading,
    /** The selected day card (index into the forecast's days). */
    val day: Int = 0,
    val page: WeatherPage = WeatherPage.FORECAST,
    val radar: RadarUi = RadarUi(),
    val search: CitySearchUi = CitySearchUi(),
)

/**
 * The Weather page's view model (docs/decisions/2026-11-19-weather-page.md). It makes requests only while the page is
 * open ([onEntered] … [onLeft]): the shown city's forecast, re-read every [WeatherTimings.REFRESH_MS], and the radar
 * frame list while the radar page is shown. With no city added it makes no request at all.
 */
@HiltViewModel
class WeatherViewModel internal constructor(
    private val repo: WeatherRepository?,
    private val store: SettingsStore,
    private val language: () -> String,
) : ViewModel() {
    @Inject constructor(repo: Optional<WeatherRepository>, store: SettingsStore) :
        this(repo.orElse(null), store, { java.util.Locale.getDefault().language })

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    /** The city picked for this visit; null = the default city. */
    private var pickedId: Long? = null
    private var entered = false
    private var forecastJob: Job? = null
    private var radarJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            store.flow(WeatherDomain).collect { prefs ->
                val before = _state.value.city
                val city = prefs.cities.firstOrNull { it.id == pickedId } ?: prefs.defaultCity
                _state.update { it.copy(prefs = prefs, city = city, body = if (city == null) WeatherBody.NoCity else it.body) }
                if (entered && city?.id != before?.id) reload()
            }
        }
    }

    fun onEntered() {
        entered = true
        reload()
    }

    fun onLeft() {
        entered = false
        pickedId = null
        forecastJob?.cancel(); radarJob?.cancel(); searchJob?.cancel()
        val prefs = _state.value.prefs
        _state.update { it.copy(city = prefs.defaultCity, page = WeatherPage.FORECAST, day = 0, radar = RadarUi(), search = CitySearchUi()) }
    }

    fun onDay(day: Int) = _state.update { it.copy(day = day) }

    fun onPage(page: WeatherPage) {
        if (_state.value.page == page) return
        _state.update { it.copy(page = page) }
        if (page == WeatherPage.RADAR) loadRadar() else radarJob?.cancel()
    }

    fun onRetry() = reload(force = true)

    /** Shows [id] for this visit (the default city is unchanged). */
    fun onCity(id: Long) {
        val city = _state.value.prefs.cities.firstOrNull { it.id == id } ?: return
        pickedId = id
        if (_state.value.city?.id == id) return
        _state.update { it.copy(city = city) }
        reload()
    }

    fun onSetDefault(id: Long) = store.update(WeatherDomain) { p -> if (p.cities.any { it.id == id }) p.copy(defaultId = id) else p }

    fun onRemove(id: Long) {
        if (pickedId == id) pickedId = null
        store.update(WeatherDomain) { it.without(id) }
    }

    /** Adds [city] and shows it; the first city added becomes the default. */
    fun onAdd(city: WeatherCity) {
        pickedId = city.id
        store.update(WeatherDomain) { it.withCity(city) }
        _state.update { it.copy(search = CitySearchUi()) }
    }

    fun onUnits(units: WeatherUnits) = store.update(WeatherDomain) { it.copy(units = units) }

    /** The search field's text: answers arrive [WeatherTimings.SEARCH_DEBOUNCE_MS] after the last keystroke. */
    fun onQuery(text: String) {
        val q = text.take(MAX_QUERY)
        searchJob?.cancel()
        if (q.trim().length < 2 || repo == null) {
            _state.update { it.copy(search = CitySearchUi(query = q)) }
            return
        }
        // The answers on screen belong to the text that was there before: they go until this text has its own.
        _state.update { it.copy(search = if (it.search.query == q) it.search.copy(loading = true, failed = null) else CitySearchUi(query = q, loading = true)) }
        searchJob = viewModelScope.launch {
            delay(WeatherTimings.SEARCH_DEBOUNCE_MS)
            val r = repo.search(q, language())
            _state.update { s ->
                if (s.search.query != q) s else when (r) {
                    is WeatherResult.Ok -> s.copy(search = CitySearchUi(q, loading = false, results = r.value, searched = true))
                    is WeatherResult.Failed -> s.copy(search = CitySearchUi(q, loading = false, failed = r.kind, searched = true))
                }
            }
        }
    }

    fun onSearchClosed() {
        searchJob?.cancel()
        _state.update { it.copy(search = CitySearchUi()) }
    }

    private fun reload(force: Boolean = false) {
        forecastJob?.cancel()
        val city = _state.value.city
        if (city == null) {
            _state.update { it.copy(body = WeatherBody.NoCity) }
            return
        }
        if (repo == null) {
            _state.update { it.copy(body = WeatherBody.Failed(WeatherFail.NO_ANSWER)) }
            return
        }
        val shown = (_state.value.body as? WeatherBody.Ready)?.forecast?.takeIf { it.cityId == city.id }
        if (shown == null) _state.update { it.copy(body = WeatherBody.Loading, day = 0) }
        forecastJob = viewModelScope.launch {
            var first = force
            while (true) {
                when (val r = repo.forecast(city, force = first)) {
                    is WeatherResult.Ok -> _state.update { s ->
                        val had = (s.body as? WeatherBody.Ready)?.forecast
                        // A new day's forecast, or another city's, starts on its own "today".
                        val day = if (had != null && had.cityId == r.value.cityId && had.days.firstOrNull()?.date == r.value.days.firstOrNull()?.date) s.day else r.value.nowDay
                        s.copy(body = WeatherBody.Ready(r.value), day = day.coerceIn(0, r.value.days.lastIndex))
                    }
                    // A failed re-read keeps the forecast already on screen.
                    is WeatherResult.Failed -> _state.update { s -> if (s.body is WeatherBody.Ready) s else s.copy(body = WeatherBody.Failed(r.kind)) }
                }
                first = true
                delay(WeatherTimings.REFRESH_MS)
            }
        }
        if (_state.value.page == WeatherPage.RADAR) loadRadar()
    }

    private fun loadRadar() {
        radarJob?.cancel()
        val city = _state.value.city ?: return
        if (repo == null) {
            _state.update { it.copy(radar = RadarUi(failed = WeatherFail.NO_ANSWER)) }
            return
        }
        _state.update { it.copy(radar = RadarUi()) }
        radarJob = viewModelScope.launch {
            while (true) {
                val cold = ((_state.value.body as? WeatherBody.Ready)?.forecast?.current?.tempC ?: 10.0) <= SNOW_BELOW_C
                when (val r = repo.radar(city, cold)) {
                    is WeatherResult.Ok -> _state.update { it.copy(radar = RadarUi(frames = r.value)) }
                    is WeatherResult.Failed -> _state.update { s -> if (s.radar.frames != null) s else s.copy(radar = RadarUi(failed = r.kind)) }
                }
                delay(WeatherTimings.RADAR_REFRESH_MS)
            }
        }
    }

    internal companion object {
        const val MAX_QUERY = 60
        /** Environment Canada has separate rain and snow radar layers: at or below this the snow one is shown. */
        const val SNOW_BELOW_C = 0.5
    }
}

/** `WeatherRepository` is optional in `:ui` (bound by `:data`'s `WeatherModule`), as the score repository is. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherUiModule {
    @BindsOptionalOf abstract fun weatherRepository(): WeatherRepository
}
