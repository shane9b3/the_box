package app.thebox.data.weather

import app.thebox.core.common.BoxLog
import app.thebox.core.weather.Forecast
import app.thebox.core.weather.OpenMeteoMapper
import app.thebox.core.weather.RadarFrames
import app.thebox.core.weather.RadarMath
import app.thebox.core.weather.RadarSource
import app.thebox.core.weather.WeatherCity
import app.thebox.core.weather.WeatherFail
import app.thebox.core.weather.WeatherRepository
import app.thebox.core.weather.WeatherResult
import app.thebox.core.weather.WeatherTimings
import app.thebox.data.di.ApiClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The Weather page's requests (docs/decisions/2026-11-19-weather-page.md): Open-Meteo for the forecast and the city
 * search, Environment Canada or RainViewer for the radar frame list. Plain GETs from the `@ApiClient`; bodies capped at
 * [MAX_BODY_BYTES]. A request carries a latitude/longitude or a typed city name and nothing else about the user.
 * Logging names the service and the outcome only, never a URL (the URL holds the coordinates).
 */
@Singleton
class WeatherRepositoryImpl internal constructor(
    client: OkHttpClient,
    private val nowMs: () -> Long,
    private val forecastUrl: (WeatherCity) -> String,
    private val geocodeBase: String,
    private val rainViewerIndex: String,
    private val envCanadaCapabilities: (Boolean) -> String,
) : WeatherRepository {
    @Inject constructor(@ApiClient client: OkHttpClient) : this(
        client, System::currentTimeMillis, OpenMeteoMapper::forecastUrl, OpenMeteoMapper.GEOCODE_BASE, RadarMath.RV_INDEX,
        RadarMath::envCanadaCapabilitiesUrl,
    )

    private val client = client.newBuilder().callTimeout(WeatherTimings.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val forecasts = ConcurrentHashMap<Long, Forecast>()

    override suspend fun forecast(city: WeatherCity, force: Boolean): WeatherResult<Forecast> {
        val cached = forecasts[city.id]
        val now = nowMs()
        if (!force && cached != null && now - cached.fetchedAtMs in 0 until WeatherTimings.FORECAST_TTL_MS) return WeatherResult.Ok(cached)
        val r = json("forecast", forecastUrl(city)) { OpenMeteoMapper.forecast(it, city.id, now) }
        if (r is WeatherResult.Ok) forecasts[city.id] = r.value
        return r
    }

    override suspend fun search(query: String, language: String): WeatherResult<List<WeatherCity>> {
        val q = query.trim()
        if (q.length < 2) return WeatherResult.Ok(emptyList())
        val url = geocodeBase.toHttpUrl().newBuilder()
            .addQueryParameter("name", q.take(MAX_QUERY))
            .addQueryParameter("count", "8")
            .addQueryParameter("language", language.take(2).ifEmpty { "en" })
            .addQueryParameter("format", "json")
            .build().toString()
        return json("geocode", url) { OpenMeteoMapper.cities(it) }
    }

    override suspend fun radar(city: WeatherCity, cold: Boolean): WeatherResult<RadarFrames> {
        if (city.canadian) {
            val r = text("ec-radar", envCanadaCapabilities(cold)) { body ->
                RadarMath.envCanadaFrames(body, WeatherTimings.EC_FRAMES, WeatherTimings.EC_FRAME_STEP_SEC)
                    .takeIf { it.isNotEmpty() }?.let { RadarFrames(RadarSource.ENV_CANADA, "", it, snow = cold) }
            }
            // Environment Canada not answering falls back to the worldwide source rather than an empty map.
            if (r is WeatherResult.Ok) return r
        }
        return json("rainviewer", rainViewerIndex) { RadarMath.rainViewerFrames(it, WeatherTimings.RV_FRAMES) }
    }

    private suspend fun <T> json(what: String, url: String, map: (JsonObject) -> T?): WeatherResult<T> = text(what, url) { body ->
        val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (e: IllegalArgumentException) { null }
        root?.let { runCatching { map(it) }.getOrNull() }
    }

    private suspend fun <T> text(what: String, url: String, map: (String) -> T?): WeatherResult<T> {
        val request = Request.Builder().url(url).header("Accept", "application/json, text/xml").build()
        val result: WeatherResult<T> = suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(WeatherResult.Failed(if (e is UnknownHostException) WeatherFail.OFFLINE else WeatherFail.NO_ANSWER))
                }

                override fun onResponse(call: Call, response: Response) {
                    val out: WeatherResult<T> = try {
                        response.use { res ->
                            if (res.code != 200) WeatherResult.Failed(WeatherFail.NO_ANSWER)
                            else {
                                val source = res.body.source()
                                if (source.request(MAX_BODY_BYTES + 1)) WeatherResult.Failed(WeatherFail.BAD_BODY)
                                else map(source.buffer.readUtf8())?.let { WeatherResult.Ok(it) } ?: WeatherResult.Failed(WeatherFail.BAD_BODY)
                            }
                        }
                    } catch (e: IOException) {
                        WeatherResult.Failed(WeatherFail.NO_ANSWER)
                    }
                    if (cont.isActive) cont.resume(out)
                }
            })
        }
        if (result is WeatherResult.Failed) BoxLog.w(TAG, "$what: ${result.kind}")
        return result
    }

    private companion object {
        const val TAG = "Weather"
        const val MAX_BODY_BYTES = 512L * 1024
        const val MAX_QUERY = 60
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherModule {
    @Binds abstract fun weatherRepository(impl: WeatherRepositoryImpl): WeatherRepository
}
