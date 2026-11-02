package app.thebox.ui.guide.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.ui.playback.ErrorText
import coil3.ImageLoader
import coil3.compose.AsyncImage

const val MINI_OVERLAY_TAG = "mini_overlay"
const val MINI_STILL_TAG = "mini_still"
const val ERROR_CARD_TAG = "error_card"

/** The app's Coil loader for Compose surfaces (null in tests: logos fall back to the channel name). */
val LocalShellImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
fun ProvideShellImageLoader(loader: ImageLoader?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalShellImageLoader provides loader, content = content)

/**
 * What the mini player shows over (or instead of) the video (docs/design/m3-guide.md §3.13.4). Sized to the hole
 * (720 × 405 design px). (owner ruling 2026-11-01, docs/decisions/2026-11-01-guide-panel-and-mini-player.md) Video
 * only: no pills, no channel name, no live dot, no archive pill and no prompt text over the hole. Two things remain:
 * the still (nothing tuned, Still setting / low memory, HDR poster) shows the channel logo alone, and a playback error
 * shows the compact [ErrorCard] (its reason and next step, an engineering rule).
 */
@Composable
fun MiniPlayerOverlay(
    tuned: TunedState,
    tunedChannel: GuideChannel?,
    poster: Boolean,
    modifier: Modifier = Modifier,
) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_MINI) } // H-S9
    Box(modifier.fillMaxSize().testTag(MINI_OVERLAY_TAG)) {
        val nothing = tuned.identity == null || tuned.phase == MiniPhase.IDLE || tuned.phase == MiniPhase.STOPPED
        when {
            tuned.phase == MiniPhase.ERROR && tuned.error != null ->
                ErrorCard(tuned.error!!, tunedChannel, Modifier.align(Alignment.Center), tuned.errorDetail)
            // H-B1: the HDR poster shows the still in MINI (the controller keeps the video track off; viewport MINI).
            tuned.still || nothing || poster -> Still(tunedChannel, Modifier.align(Alignment.Center))
        }
    }
}

/** Debug composition counter key (H-S9). */
const val COMPOSITIONS_MINI = "MiniPlayerOverlay"

/** The still (§3.13.4, owner ruling 2026-11-01): the channel logo over the ambient hue, no text; empty without a logo. */
@Composable
private fun Still(channel: GuideChannel?, modifier: Modifier) {
    Box(modifier.testTag(MINI_STILL_TAG), contentAlignment = Alignment.Center) {
        Logo(channel, Modifier.size(192.dp, 80.dp), nameFallback = false)
    }
}

/**
 * Compact error card (phase ERROR): logo, amber dot, headline (reason) and one next-step line (§3.13.4). (M4, M3 H-L9)
 * The step is [detail]'s own first action (the ladder narrows the defaults, `TunedState.errorDetail`) when known.
 */
@Composable
fun ErrorCard(code: PlaybackErrorCode, channel: GuideChannel?, modifier: Modifier = Modifier, detail: PlaybackError? = null) {
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
            Line(stringResource(ErrorText.headline(code)), GuideUi.sans(28, FontWeight.SemiBold), maxLines = 2)
        }
        val step = if (detail != null && detail.code == code) ErrorText.step(detail) else ErrorText.step(code)
        Line(stringResource(step), GuideUi.sans(24, color = GuideUi.Fg70), maxLines = 2)
    }
}

/**
 * The channel logo when one loads, else its name when [nameFallback] (§0.8: every surface outside the grid column shows
 * the name on failure; the still is the exception, owner ruling 2026-11-01: it draws no text).
 */
@Composable
private fun Logo(channel: GuideChannel?, modifier: Modifier, nameFallback: Boolean = true) {
    channel ?: return
    val loader = LocalShellImageLoader.current
    val url = channel.logoUrl
    val image = loader != null && !url.isNullOrBlank()
    if (!image && !nameFallback) return
    Box(modifier, contentAlignment = Alignment.Center) {
        if (image) {
            AsyncImage(model = url, contentDescription = channel.displayName, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Line(channel.displayName, GuideUi.sans(30, FontWeight.SemiBold))
        }
    }
}
