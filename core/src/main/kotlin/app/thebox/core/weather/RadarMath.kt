package app.thebox.core.weather

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web-Mercator maths and the two radar services' URLs (docs/decisions/2026-11-19-weather-page.md).
 *
 * "World pixels" are the Mercator plane at a zoom: the whole world is `256 · 2^zoom` wide. The radar view is a window
 * of that plane centred on the city.
 */
object RadarMath {
    const val TILE = 256
    /** RainViewer serves radar tiles up to this zoom only. */
    const val RV_MAX_ZOOM = 7
    private const val EARTH_HALF = 20037508.342789244
    private const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Double): Double = TILE * Math.pow(2.0, zoom)

    fun xOf(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun yOf(lat: Double, zoom: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0 * worldSize(zoom)
    }

    fun lonOf(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun latOf(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /** A view [width] × [height] world pixels at [zoom], centred on a point. */
    data class View(val zoom: Double, val left: Double, val top: Double, val width: Double, val height: Double) {
        val right: Double get() = left + width
        val bottom: Double get() = top + height
    }

    fun view(lat: Double, lon: Double, zoom: Double, width: Double, height: Double): View {
        val world = worldSize(zoom)
        val top = (yOf(lat, zoom) - height / 2).coerceIn(0.0, maxOf(0.0, world - height))
        return View(zoom, xOf(lon, zoom) - width / 2, top, width, height)
    }

    /** One RainViewer tile placed in the view: draw [url] at ([x], [y]) with side [size] (view units). */
    data class PlacedTile(val url: String, val x: Float, val y: Float, val size: Float)

    /**
     * The RainViewer tiles covering [v]. Tiles are fetched at the integer zoom [tileZoom] (≤ [RV_MAX_ZOOM]) and scaled to
     * the view's zoom. Colour scheme 2 (Universal Blue), smoothed, with snow shown.
     */
    fun rainViewerTiles(host: String, path: String, v: View, tileZoom: Int): List<PlacedTile> {
        val scale = Math.pow(2.0, v.zoom - tileZoom)
        val side = TILE * scale
        val n = 1 shl tileZoom
        val x0 = floor(v.left / side).toInt()
        val x1 = floor((v.right - 1e-6) / side).toInt()
        val y0 = floor(v.top / side).toInt().coerceAtLeast(0)
        val y1 = floor((v.bottom - 1e-6) / side).toInt().coerceAtMost(n - 1)
        val out = ArrayList<PlacedTile>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (ty in y0..y1) for (tx in x0..x1) {
            val wrapped = ((tx % n) + n) % n
            out += PlacedTile(
                "$host$path/$TILE/$tileZoom/$wrapped/$ty/2/1_1.png",
                (tx * side - v.left).toFloat(), (ty * side - v.top).toFloat(), side.toFloat(),
            )
        }
        return out
    }

    /** The integer zoom RainViewer tiles are fetched at for a view zoom: one level coarser, so a frame is few small files. */
    fun rainViewerTileZoom(viewZoom: Double): Int = (floor(viewZoom).toInt() - 1).coerceIn(2, RV_MAX_ZOOM)

    /** Environment Canada's WMS picture of the whole view, [pxW] × [pxH], for one frame time. */
    fun envCanadaUrl(v: View, pxW: Int, pxH: Int, timeIso: String, snow: Boolean): String {
        val world = worldSize(v.zoom)
        fun mx(x: Double) = x / world * 2 * EARTH_HALF - EARTH_HALF
        fun my(y: Double) = EARTH_HALF - y / world * 2 * EARTH_HALF
        val bbox = String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", mx(v.left), my(v.bottom), mx(v.right), my(v.top))
        val layer = if (snow) EC_LAYER_SNOW else EC_LAYER_RAIN
        return "$EC_BASE?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=&CRS=EPSG:3857&BBOX=$bbox" +
            "&WIDTH=$pxW&HEIGHT=$pxH&FORMAT=image/png&TRANSPARENT=TRUE&TIME=$timeIso"
    }

    const val EC_BASE = "https://geo.weather.gc.ca/geomet"
    const val EC_LAYER_RAIN = "RADAR_1KM_RRAI"
    const val EC_LAYER_SNOW = "RADAR_1KM_RSNO"
    const val RV_INDEX = "https://api.rainviewer.com/public/weather-maps.json"

    fun envCanadaCapabilitiesUrl(snow: Boolean): String =
        "$EC_BASE?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities&LAYER=" + if (snow) EC_LAYER_SNOW else EC_LAYER_RAIN

    private val EC_TIME = Regex("<Dimension[^>]*name=\"time\"[^>]*>\\s*([0-9T:\\-Z]+)/([0-9T:\\-Z]+)/PT(\\d+)M\\s*<")

    /** The layer's time range from its capabilities document → the last [count] frames, [stepSec] apart, ending at its newest. */
    fun envCanadaFrames(capabilities: String, count: Int, stepSec: Long): List<RadarFrame> {
        val m = EC_TIME.find(capabilities) ?: return emptyList()
        val start = parseIsoSec(m.groupValues[1]) ?: return emptyList()
        val end = parseIsoSec(m.groupValues[2]) ?: return emptyList()
        val out = ArrayList<RadarFrame>(count)
        var t = end
        while (out.size < count && t >= start) {
            out += RadarFrame(t, isoOf(t))
            t -= stepSec
        }
        out.reverse()
        return out
    }

    /** RainViewer's index → its host and the newest [count] past frames. */
    fun rainViewerFrames(root: JsonObject, count: Int): RadarFrames? {
        val host = (root["host"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://") } ?: return null
        val past = ((root["radar"] as? JsonObject)?.get("past") as? JsonArray) ?: return null
        val frames = past.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val time = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("/") && SAFE_PATH.matches(it) } ?: return@mapNotNull null
            RadarFrame(time, path)
        }.sortedBy { it.timeSec }.takeLast(count)
        if (frames.isEmpty()) return null
        return RadarFrames(RadarSource.RAINVIEWER, host, frames)
    }

    private val SAFE_PATH = Regex("[A-Za-z0-9/_\\-.]+")

    /** "2026-11-20T01:42:00Z" → epoch seconds. */
    internal fun parseIsoSec(iso: String): Long? = runCatching { java.time.Instant.parse(iso).epochSecond }.getOrNull()

    internal fun isoOf(sec: Long): String = java.time.Instant.ofEpochSecond(sec).toString()
}
