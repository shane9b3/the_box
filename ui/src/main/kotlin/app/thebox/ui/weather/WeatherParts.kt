package app.thebox.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.weather.Condition
import app.thebox.core.weather.Forecast
import app.thebox.core.weather.Sky
import app.thebox.core.weather.WeatherFail
import app.thebox.core.weather.WeatherUnits
import app.thebox.core.weather.WeatherUnitsMath
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The page's glass: darker than the guide's, because it lies over a photograph. */
internal object WeatherUi {
    val Glass = Color(0xFF0B0F17).copy(alpha = 0.64f)
    val GlassBorder = Color.White.copy(alpha = 0.10f)
    val Card = Color.White.copy(alpha = 0.07f)
    val RainText = Color(0xFF8CC8FF)
}

/**
 * False for the page behind the open city panel: its chips and cards take no focus, so the D-pad cannot leave the panel
 * for something hidden under it.
 */
internal val LocalWeatherFocusAllowed = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * A focusable, clickable box (OK = click). [content] gets whether it is focused; the caller draws the focused look.
 * [up] / [down] pin the vertical neighbours where the default search would pick a far one.
 */
@Composable
internal fun FocusBox(
    requester: FocusRequester,
    tag: String,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    enabled: Boolean = true,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val allowed = LocalWeatherFocusAllowed.current
    Box(
        modifier
            .testTag(tag)
            .focusRequester(requester)
            .focusProperties {
                canFocus = enabled && allowed
                if (up != null) this.up = up
                if (down != null) this.down = down
            }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused()
                focused = it.isFocused
            }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

/** The page's button: light fill and accent ring when focused, glass otherwise. */
@Composable
internal fun TextButton(
    text: String,
    requester: FocusRequester,
    tag: String,
    up: FocusRequester? = null,
    width: Int? = null,
    enabled: Boolean = true,
    description: String? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit,
) {
    // A disabled button stays focusable (dimmed, OK does nothing): a button that disables itself under the cursor, such as
    // a zoom button at its limit, must not drop the focus.
    FocusBox(requester, tag, up = up, onClick = { if (enabled) onClick() }) { focused ->
        var m = Modifier.height(56.dp)
        if (width != null) m = m.width(width.dp)
        if (description != null) m = m.semantics { contentDescription = description }
        m = if (focused) m.background(GuideUi.Fg, shape).border(GuideUi.FocusRing, GuideUi.Accent, shape)
        else m.background(GuideUi.White10, shape).border(1.dp, GuideUi.White16, shape)
        Box(m.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            val color = if (focused) GuideUi.Canvas else GuideUi.Fg
            Line(text, GuideUi.sans(24, FontWeight.SemiBold, if (enabled) color else color.copy(alpha = 0.35f)))
        }
    }
}

/** Every worded value on the page. Temperatures, speeds and amounts are converted here from the metric model. */
internal object WeatherText {
    @Composable
    fun condition(c: Condition): String = stringArrayResource(R.array.weather_conditions)[c.ordinal]

    fun temp(c: Double, units: WeatherUnits): String = "${WeatherUnitsMath.temp(c, units)}°"

    @Composable
    fun speed(kmh: Double, units: WeatherUnits): String = stringResource(
        if (units == WeatherUnits.IMPERIAL) R.string.weather_speed_mph else R.string.weather_speed_kmh, WeatherUnitsMath.speed(kmh, units),
    )

    @Composable
    fun amount(mm: Double, units: WeatherUnits): String {
        val v = WeatherUnitsMath.precipTenths(mm, units)
        // The configuration's locale (a locale change recomposes); lint NonObservableLocale.
        val locale: Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        return if (units == WeatherUnits.IMPERIAL) stringResource(R.string.weather_amount_in, String.format(locale, "%.2f", v / 100.0))
        else stringResource(R.string.weather_amount_mm, if (v % 10 == 0 || v >= 100) (v / 10).toString() else String.format(locale, "%.1f", v / 10.0))
    }

    @Composable
    fun compassShort(deg: Int): String = stringArrayResource(R.array.weather_compass_short)[WeatherUnitsMath.compass(deg)]

    @Composable
    fun compassLong(deg: Int): String = stringArrayResource(R.array.weather_compass_long)[WeatherUnitsMath.compass(deg)]

    /** A minute of the day as a clock time: "07:41" or "7:41 AM". */
    fun minute(min: Int, clock24: Boolean): String {
        if (min < 0) return "—"
        val h = min / 60 % 24
        val m = min % 60
        return if (clock24) String.format(Locale.ROOT, "%02d:%02d", h, m)
        else String.format(Locale.ROOT, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }

    /** An hour column's heading: "20:00" or "8 PM". */
    fun hour(h: Int, clock24: Boolean): String =
        if (clock24) String.format(Locale.ROOT, "%02d:00", h) else String.format(Locale.ROOT, "%d %s", if (h % 12 == 0) 12 else h % 12, if (h < 12) "AM" else "PM")

    /** A radar frame's time in the city's zone. */
    fun clock(epochSec: Long, zone: String, clock24: Boolean): String {
        val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
        val t = Instant.ofEpochSecond(epochSec).atZone(z)
        return minute(t.hour * 60 + t.minute, clock24)
    }

    @Composable
    fun ago(seconds: Long): String {
        val min = (seconds / 60).toInt().coerceAtLeast(1)
        return pluralStringResource(R.plurals.weather_min_ago, min, min)
    }

    /** "2 h 5 min", "45 min". */
    @Composable
    fun span(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> stringResource(R.string.weather_span_min, m)
            m == 0 -> stringResource(R.string.weather_span_h, h)
            else -> stringResource(R.string.weather_span_h_min, h, m)
        }
    }

    @Composable
    fun failure(kind: WeatherFail, radar: Boolean = false): String = stringResource(
        when {
            kind == WeatherFail.OFFLINE -> R.string.weather_fail_offline
            radar -> R.string.weather_fail_radar
            else -> R.string.weather_fail_answer
        },
    )

    fun date(iso: String, pattern: String, locale: Locale): String =
        runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern(pattern, locale)) }.getOrDefault(iso)

    /**
     * The line under the temperature, from the next twelve hours: whether rain or snow is falling, coming or absent, and
     * the wind when it is worth a word.
     */
    @Composable
    fun summary(f: Forecast, clock24: Boolean): String {
        val next = f.hourly(f.nowDay)
        fun wet(s: Sky) = s == Sky.DRIZZLE || s == Sky.RAIN || s == Sky.SNOW || s == Sky.STORM
        val now = f.current.condition
        val first = if (wet(now.sky)) {
            val end = next.firstOrNull { !wet(it.condition.sky) }
            if (end == null) stringResource(R.string.weather_sum_through, condition(now))
            else stringResource(R.string.weather_sum_until, condition(now), hour(end.hour, clock24))
        } else {
            val start = next.firstOrNull { wet(it.condition.sky) && (it.popPct ?: 100) >= 30 }
            if (start == null) stringResource(R.string.weather_sum_none)
            else stringResource(
                R.string.weather_sum_from,
                stringResource(
                    when (start.condition.sky) {
                        Sky.SNOW -> R.string.weather_kind_snow
                        Sky.STORM -> R.string.weather_kind_storms
                        else -> R.string.weather_kind_rain
                    },
                ),
                hour(start.hour, clock24),
            )
        }
        val wind = f.current.windKmh
        val second = when {
            wind >= 40 -> stringResource(R.string.weather_sum_windy, compassLong(f.current.windDirDeg))
            wind >= 20 -> stringResource(R.string.weather_sum_breezy, compassLong(f.current.windDirDeg))
            else -> null
        }
        return if (second == null) first else "$first $second"
    }
}
