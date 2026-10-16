package app.thebox.ui.full

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerUiState
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.ui.R
import app.thebox.ui.guide.overlay.ExitHint
import app.thebox.ui.guide.overlay.NumberEntryOverlay
import app.thebox.ui.guide.screen.ErrorCard
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.playback.ErrorText
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

const val FULL_SCREEN_TAG = "full_screen"
const val GROUP_CARD_TAG = "group_card"
const val BOTTOM_SCRIM_TAG = "player_bottom_scrim"
const val PROBING_TAG = "player_probing"
const val STOPPED_HINT_TAG = "player_stopped_hint"
/** U-S5: debug composition counter of [FullScreenHost] (`GuideDebugCounters`). */
const val COMPOSITIONS_FULL = "FullScreenHost"
private const val MINUTE_MS = 60_000L

/**
 * FULL (docs/design/m4-player-ux.md §3.9): the player overlays, each a sibling inside this box (which sits in the key
 * sink, itself a sibling after `HoleChrome`), never an ancestor of `VideoLayer`. Draw order, back to front: bottom
 * scrim, info or scrub bar, quick bar, stats, channel list, picker sheet, error card, transient lines (catch-up
 * probing, the stopped hint), then the guide's own overlays still drawn here (group card, number entry with the
 * preview's now title, the Exit hint/dialog: M3 H-L4), and the screen-off layer on top. The reminder card and toasts
 * are the root's. Fades are 150 ms on each overlay's own content (`AnimatedVisibility`), snapped in [lowEnd]; nothing
 * here runs an infinite transition (§4.1).
 *
 * Keys never come here: the root key sink feeds the guide machine, which delegates to the player machine (§3.1.1).
 * Without a [player] (tests, the debug preview) this is the M3 minimal screen (compact error card).
 */
@Composable
fun FullScreenHost(
    state: GuideUiState,
    modifier: Modifier = Modifier,
    player: PlayerHost? = null,
    clock24: Boolean = true,
    lowEnd: Boolean = false,
) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_FULL) } // U-S5
    val flow = remember(player) { player?.machine?.ui ?: MutableStateFlow(PlayerUiState()) }
    val ui by flow.collectAsState()
    val extras = player as? PlayerExtras
    val detailFlow = remember(player) { extras?.focusedDetail ?: MutableStateFlow(null) }
    val detail by detailFlow.collectAsState()
    val failureFlow = remember(player) { extras?.archiveFailure ?: MutableStateFlow(null) }
    val archiveFailure by failureFlow.collectAsState()
    // U-S5: a per-minute clock (past / now / future, list progress); never read in composition per recomposition, so
    // the bars and the list skip when only the stats (2 Hz) change. Sub-minute time is read in the draw lambdas.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            val n = System.currentTimeMillis()
            value = n
            delay(MINUTE_MS - n % MINUTE_MS)
        }
    }
    Box(modifier.fillMaxSize().testTag(FULL_SCREEN_TAG)) {
        val bottomBar = ui.info != null || ui.scrub != null || ui.quick != null
        Fade(if (bottomBar) Unit else null, lowEnd) {
            Box(
                Modifier
                    .offset(0.dp, PlayerUi.SCRIM_TOP.dp)
                    .size(1920.dp, (1080f - PlayerUi.SCRIM_TOP).dp)
                    .testTag(BOTTOM_SCRIM_TAG)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PlayerUi.ScrimBottom))),
            )
        }
        Fade(ui.scrub, lowEnd) { s ->
            ScrubBar(s, nowMs, Modifier.offset(PlayerUi.BAR_L.dp, PlayerUi.BAR_T.dp), channel = state.tunedChannel, clock24 = clock24)
        }
        Fade(if (ui.scrub == null) ui.info else null, lowEnd) { info ->
            InfoBar(
                info, nowMs, Modifier.offset(PlayerUi.BAR_L.dp, PlayerUi.BAR_T.dp).size(PlayerUi.BAR_W.dp, PlayerUi.BAR_H.dp), clock24 = clock24, detail = detail,
                showPill = info.channel != null && info.channel?.identityHash == state.tuned.identityHash, // U-S2 (V6)
            )
        }
        Fade(ui.quick, lowEnd) { q -> QuickBar(q, Modifier.offset(PlayerUi.QUICK_L.dp, PlayerUi.QUICK_T.dp)) }
        // U-S3 (V2): the stats share the top-left corner with the channel list and the group card; hidden while either shows.
        if (ui.channels == null && state.groupCard == null) ui.stats?.let { StatsOverlay(it, Modifier.offset(PlayerUi.STATS_L.dp, PlayerUi.STATS_T.dp)) }
        if (player != null) {
            Fade(ui.channels, lowEnd) { c -> ChannelListPanel(c, player, Modifier.offset(PlayerUi.LIST_L.dp, PlayerUi.LIST_T.dp), nowMs) }
        }
        Fade(ui.picker, lowEnd) { p -> PickerSheet(p, Modifier.offset(PlayerUi.PICKER_L.dp, PlayerUi.PICKER_T.dp)) }

        val card = ui.error
        if (card != null) {
            PlayerErrorCard(card, Modifier.align(Alignment.Center), archiveFailure = archiveFailure ?: state.archive?.failure)
        } else if (player == null && state.tuned.phase == MiniPhase.ERROR && state.tuned.error != null) {
            ErrorCard(state.tuned.error!!, state.tunedChannel, Modifier.align(Alignment.Center), state.tuned.errorDetail)
        }
        ui.probing?.let { (n, m) ->
            Line(
                stringResource(R.string.player_probing, n, m),
                GuideUi.sans(19, FontWeight.Medium),
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .testTag(PROBING_TAG)
                    .background(GuideUi.OverlayFill, RoundedCornerShape(DesignTokens.Radii.Button))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
        if (state.tuned.phase == MiniPhase.STOPPED && state.tuned.identity != null && ui.layer == PlayerLayer.NONE && ui.error == null) {
            Line(
                stringResource(ErrorText.stoppedHint()),
                GuideUi.sans(22, FontWeight.Medium),
                Modifier
                    .align(Alignment.Center)
                    .testTag(STOPPED_HINT_TAG)
                    .background(GuideUi.OverlayFill.copy(alpha = 0.86f), RoundedCornerShape(DesignTokens.Radii.Button))
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                maxLines = 2,
            )
        }

        // ---- the guide's overlays in FULL
        state.groupCard?.let { label ->
            Line(
                label,
                GuideUi.sans(24, FontWeight.SemiBold),
                Modifier
                    .offset(64.dp, 56.dp)
                    .testTag(GROUP_CARD_TAG)
                    .background(GuideUi.OverlayFill, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        when (val o = state.overlay) {
            is Overlay.Number -> {
                val nowTitle = o.preview?.let { player?.nowOf(it)?.title }
                NumberEntryOverlay(o, Modifier.offset(PlayerUi.NUMBER_L.dp, PlayerUi.NUMBER_T.dp).size(PlayerUi.NUMBER_W.dp, PlayerUi.NUMBER_H.dp), nowTitle)
            }
            is Overlay.Exit -> ExitHint(state, if (o.dialog) Modifier else Modifier.align(Alignment.BottomStart).padding(start = PlayerUi.TOAST_L.dp, bottom = PlayerUi.TOAST_B.dp))
            else -> Unit
        }
        if (ui.layer == PlayerLayer.SCREEN_OFF) ScreenOffLayer()
    }
}

/**
 * `AnimatedVisibility` with a 150 ms fade on the overlay's own content (API notes §4; snapped in low-end mode). The last
 * non-null [value] stays drawn while it fades out.
 */
@Composable
private fun <T : Any> Fade(value: T?, lowEnd: Boolean, content: @Composable (T) -> Unit) {
    // A plain holder (not snapshot state): the content recomposes with this function, and a snapshot write during
    // composition would schedule another pass.
    val last = remember { LastValue<T>() }
    if (value != null) last.value = value
    AnimatedVisibility(
        visible = value != null,
        enter = if (lowEnd) EnterTransition.None else fadeIn(tween(PlayerUi.FADE_MS)),
        exit = if (lowEnd) ExitTransition.None else fadeOut(tween(PlayerUi.FADE_MS)),
    ) {
        last.value?.let { content(it) }
    }
}

private class LastValue<T : Any> {
    var value: T? = null
}
