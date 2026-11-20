package app.thebox.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.weather.RadarFrames
import app.thebox.core.weather.RadarMath
import app.thebox.core.weather.RadarSource
import app.thebox.core.weather.WeatherCity
import app.thebox.core.weather.WeatherTimings
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.sports.motionOff
import kotlinx.coroutines.delay

const val RADAR_MAP_TAG = "weather_radar_map"
const val RADAR_PLAY_TAG = "weather_radar_play"
const val RADAR_BACK_TAG = "weather_radar_back"
const val RADAR_FORWARD_TAG = "weather_radar_forward"
const val RADAR_ZOOM_IN_TAG = "weather_radar_zoom_in"
const val RADAR_ZOOM_OUT_TAG = "weather_radar_zoom_out"
const val RADAR_TIME_TAG = "weather_radar_time"
const val RADAR_MESSAGE_TAG = "weather_radar_message"

/** The view is the 1920 × 1080 design canvas; one view unit is one design px. */
private const val VIEW_W = 1920.0
private const val VIEW_H = 1080.0
private val ZOOMS = doubleArrayOf(5.0, 6.0, 7.0, 8.0)
private const val DEFAULT_ZOOM = 2
/** Environment Canada's picture is asked for at half the canvas and scaled up: radar cells are 1 km, a 4 km pixel here. */
private const val EC_PX_W = 960
private const val EC_PX_H = 540
private const val RADAR_ALPHA = 0.8f
private const val MAX_LABELS = 36

private val Water = Color(0xFF0A111C)
private val Land = Color(0xFF1B2231)
private val Border = Color.White.copy(alpha = 0.34f)
private val StateBorder = Color.White.copy(alpha = 0.16f)

/** The radar's state holder: the zoom step and the frame shown. Kept by the screen so the controls and the map share it. */
internal class RadarState {
    var zoomIndex by mutableIntStateOf(DEFAULT_ZOOM)
    var frame by mutableIntStateOf(0)
    var playing by mutableStateOf(true)
    val zoom: Double get() = ZOOMS[zoomIndex]
    val canZoomIn: Boolean get() = zoomIndex < ZOOMS.lastIndex
    val canZoomOut: Boolean get() = zoomIndex > 0
}

/**
 * The map behind the radar page: the bundled base map, the radar pictures of every frame stacked (only the current one
 * drawn, so stepping never waits for the network once they are loaded), the place names and the city's marker.
 */
@Composable
internal fun RadarMap(city: WeatherCity, frames: RadarFrames?, state: RadarState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map by produceState<BaseMap?>(null) { value = BaseMap.load(context.applicationContext) }
    val view = remember(city.id, state.zoom) { RadarMath.view(city.lat, city.lon, state.zoom, VIEW_W, VIEW_H) }
    val loader = LocalShellImageLoader.current

    // The frame clock: newest frame first shown, then a loop that rests on the newest.
    val count = frames?.frames?.size ?: 0
    LaunchedEffect(frames?.frames) { state.frame = (count - 1).coerceAtLeast(0) }
    LaunchedEffect(state.playing, count) {
        // System animations off: the newest picture stays; the step buttons still work.
        if (!state.playing || count < 2 || motionOff()) return@LaunchedEffect
        while (true) {
            delay(if (state.frame == count - 1) WeatherTimings.RADAR_LAST_FRAME_HOLD_MS else WeatherTimings.RADAR_FRAME_MS)
            state.frame = (state.frame + 1) % count
        }
    }

    Box(
        modifier.fillMaxSize().background(Water).testTag(RADAR_MAP_TAG).drawWithCache {
            val scale = (size.width / VIEW_W).toFloat()
            val m = map
            val land = m?.path(BaseMap.LAND, view, scale, closed = true)
            val lakes = m?.path(BaseMap.LAKES, view, scale, closed = true)
            val countries = m?.path(BaseMap.COUNTRIES, view, scale, closed = false)
            val states = m?.path(BaseMap.STATES, view, scale, closed = false)
            onDrawBehind {
                land?.let { drawPath(it, Land) }
                lakes?.let { drawPath(it, Water) }
                states?.let { drawPath(it, StateBorder, style = Stroke(1.2f * scale)) }
                countries?.let { drawPath(it, Border, style = Stroke(1.8f * scale)) }
            }
        },
    ) {
        if (frames != null && loader != null) {
            frames.frames.forEachIndexed { i, f ->
                val alpha = if (i == state.frame.coerceIn(0, count - 1)) RADAR_ALPHA else 0f
                when (frames.source) {
                    RadarSource.ENV_CANADA -> coil3.compose.AsyncImage(
                        model = RadarMath.envCanadaUrl(view, EC_PX_W, EC_PX_H, f.key, frames.snow),
                        contentDescription = null, imageLoader = loader, alpha = alpha, contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize(),
                    )
                    RadarSource.RAINVIEWER -> {
                        val tiles = remember(f.key, view) { RadarMath.rainViewerTiles(frames.host, f.key, view, RadarMath.rainViewerTileZoom(view.zoom)) }
                        tiles.forEach { t ->
                            coil3.compose.AsyncImage(
                                model = t.url, contentDescription = null, imageLoader = loader, alpha = alpha,
                                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                                modifier = Modifier.offset(t.x.dp, t.y.dp).size(t.size.dp),
                            )
                        }
                    }
                }
            }
        }
        map?.let { PlaceLabels(it, view, city) }
        CityMarker(city, view)
    }
}

/** Place names inside the view, the biggest places first, skipping any that would overlap one already placed. */
@Composable
private fun PlaceLabels(map: BaseMap, view: RadarMath.View, city: WeatherCity) {
    val labels = remember(view) {
        val maxRank = when {
            view.zoom >= 8 -> 8
            view.zoom >= 7 -> 6
            view.zoom >= 6 -> 4
            else -> 2
        }
        val cityX = RadarMath.xOf(city.lon, view.zoom) - view.left
        val cityY = RadarMath.yOf(city.lat, view.zoom) - view.top
        val placed = ArrayList<FloatArray>()
        // The city's own label sits to the right of its marker: keep that strip free.
        placed += floatArrayOf((cityX - 20).toFloat(), (cityY - 24).toFloat(), (cityX + 30 + city.name.length * 15).toFloat(), (cityY + 24).toFloat())
        val out = ArrayList<Triple<String, Float, Float>>()
        for (p in map.places) { // sorted by rank in the asset
            if (p.rank > maxRank || out.size >= MAX_LABELS) continue
            val x = (RadarMath.xOf(p.lon, view.zoom) - view.left).toFloat()
            val y = (RadarMath.yOf(p.lat, view.zoom) - view.top).toFloat()
            if (x < 40 || x > VIEW_W - 200 || y < 150 || y > VIEW_H - 170) continue
            val box = floatArrayOf(x - 10, y - 16, x + 18 + p.name.length * 11.5f, y + 16)
            if (placed.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
            placed += box
            out += Triple(p.name, x, y)
        }
        out
    }
    labels.forEach { (name, x, y) ->
        Box(Modifier.offset((x - 3).dp, (y - 3).dp).size(6.dp).background(GuideUi.Fg55, CircleShape))
        Line(name, GuideUi.sans(20, FontWeight.Medium, GuideUi.Fg60), Modifier.offset((x + 10).dp, (y - 14).dp))
    }
}

@Composable
private fun CityMarker(city: WeatherCity, view: RadarMath.View) {
    val x = (RadarMath.xOf(city.lon, view.zoom) - view.left).toFloat()
    val y = (RadarMath.yOf(city.lat, view.zoom) - view.top).toFloat()
    Box(Modifier.offset((x - 9).dp, (y - 9).dp).size(18.dp).background(Color.White, CircleShape).padding(4.dp).background(GuideUi.Accent, CircleShape))
    Line(city.name, GuideUi.sans(24, FontWeight.SemiBold, GuideUi.Fg), Modifier.offset((x + 18).dp, (y - 17).dp))
}

/**
 * The radar page's bottom bar: step back, Play/Pause, step forward, the frame's time, the timeline, and the zoom buttons.
 * Stepping pauses the loop.
 */
@Composable
internal fun RadarControls(
    city: WeatherCity,
    radar: RadarUi,
    state: RadarState,
    clock24: Boolean,
    play: FocusRequester,
    up: FocusRequester,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frames = radar.frames
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        if (frames == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    val failed = radar.failed
                    Line(
                        if (failed == null) stringResource(R.string.weather_radar_loading) else WeatherText.failure(failed, radar = true),
                        GuideUi.sans(28, color = GuideUi.Fg), Modifier.testTag(RADAR_MESSAGE_TAG), maxLines = 2,
                    )
                    if (failed != null) TextButton(stringResource(R.string.weather_retry), play, RADAR_PLAY_TAG, up = up, onClick = onRetry)
                }
            }
            return@Column
        }
        val n = frames.frames.size
        val index = state.frame.coerceIn(0, n - 1)
        val frame = frames.frames[index]
        Line(
            stringResource(
                R.string.weather_radar_credit,
                stringResource(if (frames.source == RadarSource.ENV_CANADA) R.string.weather_radar_ec else R.string.weather_radar_rv),
            ),
            GuideUi.sans(18, color = GuideUi.Fg55), Modifier.align(Alignment.End).padding(bottom = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val back = remember { FocusRequester() }
            val forward = remember { FocusRequester() }
            TextButton("◀", back, RADAR_BACK_TAG, up = up, width = 64, description = stringResource(R.string.weather_radar_previous)) {
                state.playing = false; state.frame = (index - 1 + n) % n
            }
            TextButton(
                stringResource(if (state.playing) R.string.weather_radar_pause else R.string.weather_radar_play), play, RADAR_PLAY_TAG, up = up,
                width = 132,
            ) { state.playing = !state.playing }
            TextButton("▶", forward, RADAR_FORWARD_TAG, up = up, width = 64, description = stringResource(R.string.weather_radar_next)) {
                state.playing = false; state.frame = (index + 1) % n
            }
            Column(Modifier.width(200.dp)) {
                Line(WeatherText.clock(frame.timeSec, city.zone, clock24), GuideUi.mono(30, FontWeight.Medium), Modifier.testTag(RADAR_TIME_TAG))
                Line(
                    if (index == n - 1) stringResource(R.string.weather_radar_latest) else WeatherText.ago(frames.frames.last().timeSec - frame.timeSec),
                    GuideUi.sans(20, color = GuideUi.Fg60),
                )
            }
            Row(Modifier.weight(1f).height(28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    Box(
                        Modifier.weight(1f).height(if (i == index) 12.dp else 6.dp)
                            .background(if (i == index) GuideUi.Accent else if (i < index) GuideUi.White22 else GuideUi.White10, RoundedCornerShape(6.dp)),
                    )
                }
            }
            val zoomOut = remember { FocusRequester() }
            val zoomIn = remember { FocusRequester() }
            TextButton("−", zoomOut, RADAR_ZOOM_OUT_TAG, up = up, width = 64, enabled = state.canZoomOut, description = stringResource(R.string.weather_radar_zoom_out)) {
                if (state.canZoomOut) state.zoomIndex--
            }
            TextButton("+", zoomIn, RADAR_ZOOM_IN_TAG, up = up, width = 64, enabled = state.canZoomIn, description = stringResource(R.string.weather_radar_zoom_in)) {
                if (state.canZoomIn) state.zoomIndex++
            }
        }
    }
}
