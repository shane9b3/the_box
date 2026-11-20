package app.thebox.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.thebox.core.weather.Sky
import kotlin.math.cos
import kotlin.math.sin

/**
 * The weather glyphs, drawn (no icon font or images are bundled): sun, moon, cloud, and rain, snow, bolt or fog under
 * the cloud. One square box; every measure is a fraction of its side, so the same glyph serves 28 px and 150 px.
 */
internal object WeatherPalette {
    val Sun = Color(0xFFFFC043)
    val Moon = Color(0xFFE6ECF7)
    val Cloud = Color(0xFFAEB9CC)
    val CloudLight = Color(0xFFDDE4F0)
    val Rain = Color(0xFF6DB6FF)
    val Snow = Color(0xFFEAF2FF)
    val Bolt = Color(0xFFFFD75E)
}

@Composable
internal fun WeatherIcon(sky: Sky, isDay: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        when (sky) {
            Sky.CLEAR -> if (isDay) sun(Offset(s * 0.5f, s * 0.5f), s * 0.2f, s) else moon(Offset(s * 0.5f, s * 0.5f), s * 0.3f)
            Sky.PARTLY -> {
                if (isDay) sun(Offset(s * 0.36f, s * 0.36f), s * 0.15f, s * 0.8f) else moon(Offset(s * 0.36f, s * 0.36f), s * 0.22f)
                cloud(s * 0.24f, s * 0.44f, s * 0.72f, WeatherPalette.CloudLight)
            }
            Sky.CLOUDY -> cloud(s * 0.08f, s * 0.26f, s * 0.84f, WeatherPalette.CloudLight)
            Sky.FOG -> {
                cloud(s * 0.12f, s * 0.14f, s * 0.76f, WeatherPalette.Cloud)
                for (i in 0..1) {
                    val y = s * (0.74f + 0.13f * i)
                    drawLine(WeatherPalette.CloudLight, Offset(s * (0.16f + 0.08f * i), y), Offset(s * (0.84f - 0.08f * i), y), s * 0.05f, StrokeCap.Round)
                }
            }
            Sky.DRIZZLE, Sky.RAIN -> {
                cloud(s * 0.1f, s * 0.1f, s * 0.8f, WeatherPalette.Cloud)
                val n = if (sky == Sky.RAIN) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.5f + (i - (n - 1) / 2f) * 0.2f)
                    drawLine(WeatherPalette.Rain, Offset(x + s * 0.04f, s * 0.72f), Offset(x - s * 0.04f, s * 0.9f), s * 0.055f, StrokeCap.Round)
                }
            }
            Sky.SNOW -> {
                cloud(s * 0.1f, s * 0.1f, s * 0.8f, WeatherPalette.Cloud)
                for (i in 0..2) drawCircle(WeatherPalette.Snow, s * 0.042f, Offset(s * (0.3f + 0.2f * i), s * (if (i == 1) 0.88f else 0.78f)))
            }
            Sky.STORM -> {
                cloud(s * 0.1f, s * 0.08f, s * 0.8f, WeatherPalette.Cloud)
                val p = Path().apply {
                    moveTo(s * 0.54f, s * 0.62f); lineTo(s * 0.4f, s * 0.8f); lineTo(s * 0.5f, s * 0.8f)
                    lineTo(s * 0.44f, s * 0.96f); lineTo(s * 0.62f, s * 0.74f); lineTo(s * 0.52f, s * 0.74f); close()
                }
                drawPath(p, WeatherPalette.Bolt)
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, box: Float) {
    drawCircle(WeatherPalette.Sun, r, c)
    val inner = r * 1.45f
    val outer = r * 1.95f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(WeatherPalette.Sun, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), box * 0.045f, StrokeCap.Round)
    }
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val full = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r * 0.45f, c.y - r * 1.25f, c.x + r * 1.45f, c.y + r * 0.65f)) }
    drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, WeatherPalette.Moon)
}

/** A cloud [w] wide with its top-left at ([x], [y]); it is about 0.56 · w high. */
private fun DrawScope.cloud(x: Float, y: Float, w: Float, color: Color) {
    val h = w * 0.56f
    val base = h * 0.52f
    drawRoundRect(color, Offset(x, y + h - base), Size(w, base), CornerRadius(base / 2, base / 2))
    drawCircle(color, w * 0.2f, Offset(x + w * 0.32f, y + h * 0.5f))
    drawCircle(color, w * 0.27f, Offset(x + w * 0.58f, y + w * 0.27f))
}
