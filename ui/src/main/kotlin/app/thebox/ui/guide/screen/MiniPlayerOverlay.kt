package app.thebox.ui.guide.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.ui.R
import app.thebox.ui.playback.ErrorText
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val MINI_OVERLAY_TAG = "mini_overlay"
const val MINI_STILL_TAG = "mini_still"
const val ERROR_CARD_TAG = "error_card"

/** The app's Coil loader for Compose surfaces (null in tests: logos fall back to the channel name). */
val LocalShellImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
fun ProvideShellImageLoader(loader: ImageLoader?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalShellImageLoader provides loader, content = content)

/**
 * What the mini player shows over (or instead of) the video (docs/design/m3-guide.md §3.13.4; prototype `previewEl`
 * block). Sized to the hole (720 × 405 design px). Nothing here is opaque over live video except the pills and the
 * bottom gradient panel; the still and the error card replace the picture.
 */
@Composable
fun MiniPlayerOverlay(state: GuideUiState, clock24: Boolean, lowEnd: Boolean, poster: Boolean, modifier: Modifier = Modifier) {
    val tuned = state.tuned
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    Box(modifier.fillMaxSize().testTag(MINI_OVERLAY_TAG)) {
        val nothing = tuned.identity == null || tuned.phase == MiniPhase.IDLE || tuned.phase == MiniPhase.STOPPED
        when {
            tuned.phase == MiniPhase.ERROR && tuned.error != null ->
                ErrorCard(tuned.error!!, state.tunedChannel, Modifier.align(Alignment.Center))
            tuned.still || nothing ->
                Still(state.tunedChannel, state.tunedNow, poster && !nothing, Modifier.align(Alignment.Center))
            else -> BottomPanel(state.tunedNow, state.tunedChannel, now, clock24, Modifier.align(Alignment.BottomStart))
        }
        if (!nothing) Pills(state, lowEnd, Modifier.offset(20.dp, 18.dp))
    }
}

@Composable
private fun Pills(state: GuideUiState, lowEnd: Boolean, modifier: Modifier) {
    val tuned = state.tuned
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val pill = Modifier.background(GuideUi.PillDark, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
        Row(pill, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                tuned.phase == MiniPhase.CONNECTING || tuned.phase == MiniPhase.BUFFERING ->
                    Line(stringResource(R.string.guide_mini_connecting), GuideUi.sans(15, FontWeight.SemiBold))
                tuned.phase == MiniPhase.RECONNECTING ->
                    Line(stringResource(R.string.guide_mini_reconnecting, tuned.reconnectAttempt, tuned.reconnectMax), GuideUi.sans(15, FontWeight.SemiBold))
                tuned.isArchive ->
                    Line(stringResource(R.string.guide_mini_catchup), GuideUi.sans(15, FontWeight.SemiBold, GuideUi.Accent, 0.08f))
                else -> {
                    LiveDot(lowEnd)
                    Line(stringResource(R.string.guide_mini_live), GuideUi.sans(15, FontWeight.SemiBold, letterSpacingEm = 0.08f))
                }
            }
        }
        state.tunedChannel?.number?.let { n ->
            Box(pill) { Line(stringResource(R.string.guide_mini_ch, n.toString()), GuideUi.mono(15)) }
        }
    }
}

/**
 * The LIVE dot pulses 1 → .35 over 1.6 s (prototype `tvPulse`); in low-end mode it is a 1 Hz alpha step. The alpha is
 * applied in the dot's own layer (draw phase only; ModulateAlpha, no offscreen buffer). This node is inside the
 * mini-player overlay, a sibling subtree of `VideoLayer`, never its ancestor.
 */
@Composable
private fun LiveDot(lowEnd: Boolean) {
    val t = rememberInfiniteTransition(label = "live")
    val alpha by if (lowEnd) {
        t.animateFloat(
            1f, 0.35f,
            infiniteRepeatable(keyframes { durationMillis = 2000; 1f at 0; 1f at 999; 0.35f at 1000; 0.35f at 1999 }, RepeatMode.Restart, StartOffset(0)),
            label = "step",
        )
    } else {
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    }
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
            .background(GuideUi.Live, CircleShape),
    )
}

private val BottomGradient = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.75f)))

@Composable
private fun BottomPanel(info: ProgrammeInfo?, channel: GuideChannel?, nowMs: Long, clock24: Boolean, modifier: Modifier) {
    if (info == null && channel == null) return
    Column(
        modifier
            .fillMaxWidth()
            .background(BottomGradient)
            .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            info?.let { Line(it.title, GuideUi.sans(30, FontWeight.SemiBold, letterSpacingEm = -0.01f), Modifier.weight(1f, fill = false)) }
            channel?.let { Line(it.displayName, GuideUi.sans(18, color = Color.White.copy(alpha = 0.7f)), Modifier.padding(bottom = 4.dp)) }
        }
        if (info != null && info.endMin > info.startMin) {
            val nowMin = nowMs / 60_000.0
            val progress = ((nowMin - info.startMin) / (info.endMin - info.startMin)).coerceIn(0.0, 1.0).toFloat()
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val style = GuideUi.mono(14, color = Color.White.copy(alpha = 0.75f))
                Line(hm(info.startMin, clock24), style)
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                        .drawBehind { drawRect(Color.White, size = size.copy(width = size.width * progress)) },
                )
                Line(hm(info.endMin, clock24), style)
            }
        }
    }
}

/** Nothing tuned, Still setting / low memory, or HDR poster (§3.13.4): logo over the ambient hue, the title, a prompt. */
@Composable
private fun Still(channel: GuideChannel?, info: ProgrammeInfo?, poster: Boolean, modifier: Modifier) {
    Column(modifier.testTag(MINI_STILL_TAG).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Logo(channel, Modifier.size(192.dp, 80.dp))
        info?.let { Line(it.title, GuideUi.sans(26, FontWeight.SemiBold)) }
        val prompt = when {
            channel == null -> R.string.guide_mini_nothing
            poster -> R.string.guide_mini_hdr
            else -> R.string.guide_mini_press_ok
        }
        Line(stringResource(prompt), GuideUi.sans(18, color = GuideUi.Fg70))
    }
}

/** Compact error card (phase ERROR): logo, amber dot, headline (reason) and one next-step line (§3.13.4). */
@Composable
fun ErrorCard(code: PlaybackErrorCode, channel: GuideChannel?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .testTag(ERROR_CARD_TAG)
            .widthIn(max = 600.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Logo(channel, Modifier.size(144.dp, 60.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(GuideUi.Warn, 10.dp)
            Line(stringResource(ErrorText.headline(code)), GuideUi.sans(22, FontWeight.SemiBold), maxLines = 2)
        }
        Line(stringResource(ErrorText.step(code)), GuideUi.sans(18, color = GuideUi.Fg70), maxLines = 2)
    }
}

/** The channel logo when one loads, else its name (§0.8: every surface outside the grid column shows the name on failure). */
@Composable
private fun Logo(channel: GuideChannel?, modifier: Modifier) {
    channel ?: return
    val loader = LocalShellImageLoader.current
    val url = channel.logoUrl
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loader != null && !url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = channel.displayName, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Line(channel.displayName, GuideUi.sans(24, FontWeight.SemiBold))
        }
    }
}

private fun hm(min: Int, clock24: Boolean): String =
    SimpleDateFormat(if (clock24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(min * 60_000L))
