package app.thebox.ui.full

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.state.PillRules
import app.thebox.core.guide.GuideChannel
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.LiveDot
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.guide.screen.TimeFormats
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

const val PLAYER_PILL_TAG = "player_pill"

/**
 * (§3.2.3) The displayed pill changes only after the machine's pill has been stable for [PlayerUi.PILL_DEBOUNCE_MS]
 * (hides M2 E-L8's Playing ↔ Buffering(RECONNECTING) flicker). Same-kind updates (a new attempt count, a new
 * `behindMs` anchor) apply at once.
 */
@Composable
fun rememberDebouncedPill(pill: PillState): PillState {
    val debouncer = remember { PillRules.Debouncer(PlayerUi.PILL_DEBOUNCE_MS) }
    var shown by remember { mutableStateOf(debouncer.offer(pill, System.currentTimeMillis())) }
    LaunchedEffect(pill) {
        shown = debouncer.offer(pill, System.currentTimeMillis())
        while (shown.kind != pill.kind) {
            delay((debouncer.nextChangeAtMs - System.currentTimeMillis()).coerceIn(1L, PlayerUi.PILL_DEBOUNCE_MS))
            shown = debouncer.offer(pill, System.currentTimeMillis())
        }
    }
    return shown
}

/**
 * The state pill of the info and scrub bars (§3.2.3, prototype pill style): LIVE (red dot, stepped at 1 Hz: no
 * infinite transition in FULL, §3.9), CATCH-UP (accent), PAUSED (white 12 %), `−MM:SS` (BEHIND, drawn into a reused
 * `CharArray` at 1 Hz only while it grows, §4.1), Connecting, Reconnecting n/m (unlimited when m = 0).
 */
@Composable
fun PlayerPill(pill: PillState, modifier: Modifier = Modifier) {
    val p = rememberDebouncedPill(pill)
    val fill = when (p.kind) {
        PillKind.PAUSED -> PlayerUi.PausedFill
        else -> GuideUi.PillDark
    }
    Row(
        modifier
            .testTag(PLAYER_PILL_TAG)
            .background(fill, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (p.kind) {
            PillKind.LIVE -> {
                LiveDot(lowEnd = true)
                Line(stringResource(R.string.player_pill_live), GuideUi.sans(15, FontWeight.SemiBold, letterSpacingEm = 0.08f))
            }
            PillKind.CATCH_UP -> Line(stringResource(R.string.player_pill_catchup), GuideUi.sans(15, FontWeight.SemiBold, GuideUi.Accent, 0.08f))
            PillKind.BEHIND -> BehindLabel(p)
            else -> Line(PlayerText.pill(androidx.compose.ui.platform.LocalResources.current, p), GuideUi.sans(15, FontWeight.SemiBold, letterSpacingEm = if (p.kind == PillKind.PAUSED) 0.08f else 0f))
        }
    }
}

/** `−MM:SS` / `−H:MM:SS` drawn in the draw phase from a reused buffer; ticks at 1 Hz only while [PillState.behindGrows]. */
@Composable
internal fun BehindLabel(p: PillState) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * density
            color = GuideUi.Fg.toArgb()
            typeface = monoTypeface(context)
        }
    }
    val buf = remember { CharArray(16) }
    val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.behindGrows, p.atMs) {
        tick.longValue = System.currentTimeMillis()
        while (p.behindGrows) {
            delay(1_000L - Math.floorMod(System.currentTimeMillis() - p.atMs, 1_000L))
            tick.longValue = System.currentTimeMillis()
        }
    }
    Spacer(
        Modifier
            .size(84.dp, 20.dp)
            .drawBehind {
                val now = tick.longValue
                val ms = p.behindMs + if (p.behindGrows) (now - p.atMs).coerceAtLeast(0) else 0L
                val n = TimeChars.behind(buf, 0, ms)
                drawIntoCanvas { it.nativeCanvas.drawText(buf, 0, n, 0f, size.height * 0.8f, paint) }
            },
    )
}

/** JetBrains Mono for native-canvas text, or the platform default when the font cannot be loaded (tests). */
fun monoTypeface(context: android.content.Context): Typeface? = try {
    ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
} catch (e: Exception) {
    null
}

/** The channel logo (Coil, the shell's loader) or its name when there is none; [radius] rounds the image only. */
@Composable
fun ChannelLogo(channel: GuideChannel?, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    channel ?: return
    val loader = LocalShellImageLoader.current
    val url = channel.logoUrl
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (loader != null && !url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = channel.displayName,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(radius)),
            )
        }
    }
}

/** Number chip (mono 16, 8 % fill, radius 8). */
@Composable
fun NumberChip(number: Int?, modifier: Modifier = Modifier) {
    number ?: return
    Box(modifier.background(PlayerUi.ChipFill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Line(number.toString(), GuideUi.mono(16))
    }
}

/** `HH:mm` / `h:mm` of an epoch minute in the device zone (the guide's labels). */
fun clockOfMin(min: Int, clock24: Boolean): String = clockOfMs(min * 60_000L, clock24)

fun clockOfMs(ms: Long, clock24: Boolean): String =
    TimeFormats.format(if (clock24) "HH:mm" else "h:mm", Locale.getDefault(), Date(ms))

/** A wall-clock value that ticks every [periodMs] (aligned to the period); read it in draw lambdas only (§4.1). */
@Composable
fun rememberTicker(periodMs: Long, enabled: Boolean = true): androidx.compose.runtime.State<Long> {
    val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs, enabled) {
        tick.longValue = System.currentTimeMillis()
        while (enabled) {
            delay(periodMs - Math.floorMod(System.currentTimeMillis(), periodMs))
            tick.longValue = System.currentTimeMillis()
        }
    }
    return tick
}

/** A dim colour helper (white at [alpha]) for overlay text. */
fun fg(alpha: Float): Color = GuideUi.Fg.copy(alpha = alpha)
