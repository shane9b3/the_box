package app.thebox.ui.weather

import android.content.Context
import androidx.compose.ui.graphics.Path
import app.thebox.core.weather.RadarMath
import java.io.DataInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The radar page's base map: Natural Earth 1:50m land, lakes, country and state borders, and place names (public
 * domain), bundled as `assets/weather/basemap.bin` (built by `tools/weather/build_basemap.py`, which documents the
 * format). No map tiles are fetched: only the radar pictures come from the network.
 *
 * Coordinates are 16-bit: x = (lon + 180) / 360 × 65535, y = (90 − lat) / 180 × 65535 (about 600 m, under a pixel at the
 * zooms the radar is shown at).
 */
internal class BaseMap private constructor(private val layers: Array<Array<Ring>>, val places: List<Place>) {
    class Ring(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val xy: ShortArray)
    class Place(val lon: Double, val lat: Double, val rank: Int, val name: String)

    /**
     * One layer's outlines inside [v], as a path in canvas pixels ([scale] = canvas px per view unit). [closed] closes
     * each ring (land and lakes are filled; borders are stroked open).
     */
    fun path(kind: Int, v: RadarMath.View, scale: Float, closed: Boolean): Path {
        val path = Path()
        val rings = layers.getOrNull(kind) ?: return path
        val qy0 = qy(RadarMath.latOf(v.top, v.zoom))
        val qy1 = qy(RadarMath.latOf(v.bottom, v.zoom))
        // (Review 2026-11-23, L8) A view across the 180th meridian (a Pacific city) reaches past the world's edge: the
        // world is drawn again one width to that side, as the radar tiles already wrap.
        val world = RadarMath.worldSize(v.zoom)
        for (copy in -1..1) {
            val shift = copy * world
            val left = v.left - shift
            val right = v.right - shift
            if (right < 0.0 || left > world) continue
            val qx0 = qx(RadarMath.lonOf(left.coerceAtLeast(0.0), v.zoom))
            val qx1 = qx(RadarMath.lonOf(right.coerceAtMost(world), v.zoom))
            for (r in rings) {
                if (r.maxX < qx0 || r.minX > qx1 || r.maxY < qy0 || r.minY > qy1) continue
                val xy = r.xy
                var i = 0
                while (i < xy.size) {
                    val lon = (xy[i].toInt() and 0xFFFF) / Q * 360.0 - 180.0
                    val lat = 90.0 - (xy[i + 1].toInt() and 0xFFFF) / Q * 180.0
                    val x = ((RadarMath.xOf(lon, v.zoom) + shift - v.left) * scale).toFloat()
                    val y = ((RadarMath.yOf(lat, v.zoom) - v.top) * scale).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    i += 2
                }
                if (closed) path.close()
            }
        }
        return path
    }

    private fun qx(lon: Double) = ((lon + 180.0) / 360.0 * Q).toInt()
    private fun qy(lat: Double) = ((90.0 - lat) / 180.0 * Q).toInt()

    companion object {
        const val LAND = 0
        const val LAKES = 1
        const val COUNTRIES = 2
        const val STATES = 3
        private const val Q = 65535.0
        private const val MAGIC = 0x424D4150
        private const val ASSET = "weather/basemap.bin"

        @Volatile private var cached: BaseMap? = null

        /** Loaded once per process, off the main thread; null when the asset cannot be read. */
        suspend fun load(context: Context): BaseMap? {
            cached?.let { return it }
            return withContext(Dispatchers.IO) {
                runCatching { context.assets.open(ASSET).use { read(DataInputStream(it.buffered(1 shl 16))) } }.getOrNull()?.also { cached = it }
            }
        }

        internal fun read(input: DataInputStream): BaseMap {
            require(input.readInt() == MAGIC && input.readInt() == 1) { "not a base map" }
            val layerCount = input.readInt()
            val layers = Array(4) { emptyArray<Ring>() }
            repeat(layerCount) {
                val kind = input.readByte().toInt()
                val rings = Array(input.readInt()) {
                    val minX = input.readUnsignedShort(); val minY = input.readUnsignedShort()
                    val maxX = input.readUnsignedShort(); val maxY = input.readUnsignedShort()
                    val n = input.readInt()
                    val xy = ShortArray(n * 2) { input.readShort() }
                    Ring(minX, minY, maxX, maxY, xy)
                }
                if (kind in layers.indices) layers[kind] = rings
            }
            val places = List(input.readInt()) {
                val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                val rank = input.readByte().toInt()
                Place(x / Q * 360.0 - 180.0, 90.0 - y / Q * 180.0, rank, input.readUTF())
            }
            return BaseMap(layers, places)
        }
    }
}
