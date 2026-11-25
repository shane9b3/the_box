package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GoalItem
import app.thebox.player.api.PositionSnapshot
import app.thebox.ui.R
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay

const val CLIP_LAYER_TAG = "hub_clip_layer"
const val CLIP_BAR_TAG = "hub_clip_bar"
const val CLIP_UP_NEXT_TAG = "hub_clip_up_next"
const val CLIP_ERROR_CARD_TAG = "hub_clip_error_card"
fun clipErrorButtonTag(i: Int) = "hub_clip_error_button:$i"

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5; WP-5) The clip layer `SportsScreen` composes (full screen,
 * transparent, over the root surface) while [session] is not idle: the transport bar, "Up next", the mid-clip error card,
 * and every key (OK / Play / Pause, Left / Right, Rewind / Fast-forward, Channel ±, Menu, Back → [ClipSession.back]).
 * It calls [onBackFreeChanged]`(false)` for its whole life (and `true` when it leaves the composition), so Back never
 * reaches `onSportsClosed()`. No `graphicsLayer`, clip or alpha on it: the bar is composed or not, never faded.
 *
 * (WP-5, additive) [game] and [goals] (the page's `GameDetail.goals`) give the sub-line its teams and "Goal k of n ·
 * 1st 5:11"; without them it names the clip kind only.
 */
@Composable
fun ClipPlayerLayer(
    session: ClipSession,
    onBackFreeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    game: GameSnapshot? = null,
    goals: List<GoalItem> = emptyList(),
) {
    val res = LocalResources.current
    val state by session.state.collectAsState()
    val backFree by rememberUpdatedState(onBackFreeChanged)
    DisposableEffect(Unit) {
        backFree(false)
        onDispose { backFree(true) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The bar: HUB_CLIP_BAR_MS at start and after any key; stays while paused, buffering or resolving.
    var keySeq by remember { mutableIntStateOf(0) }
    var barShown by remember { mutableStateOf(true) }
    val sticky = when (val s = state) {
        is ClipSession.State.Playing -> s.paused || s.buffering
        is ClipSession.State.Resolving -> true
        else -> false
    }
    val clipKey = (state as? ClipSession.State.Playing)?.ownedTuneId
    LaunchedEffect(keySeq, sticky, clipKey) {
        barShown = true
        if (sticky) return@LaunchedEffect
        delay(SportsConstants.HUB_CLIP_BAR_MS)
        barShown = false
    }
    // Progress at 2 Hz while the bar shows.
    var pos by remember { mutableStateOf<PositionSnapshot?>(null) }
    LaunchedEffect(barShown, clipKey) {
        while (barShown) {
            pos = session.position()
            delay(500)
        }
    }
    var errorFocus by remember { mutableIntStateOf(0) }
    val lastSeekAt = remember { mutableLongStateOf(0L) }

    fun onKey(e: KeyEvent): Boolean {
        val s = session.state.value
        if (isBack(e)) {
            if (e.type == KeyEventType.KeyUp) session.back()
            return true
        }
        if (e.type != KeyEventType.KeyDown) return e.key in HANDLED
        keySeq++
        if (s is ClipSession.State.Failed) {
            when (e.key) {
                Key.DirectionLeft -> errorFocus = 0
                Key.DirectionRight -> errorFocus = 1
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (errorFocus == 0) session.retry() else session.back()
                else -> Unit
            }
            return true
        }
        val repeat = e.nativeKeyEvent.repeatCount > 0
        when (e.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> session.togglePause()
            Key.MediaPlay -> if ((s as? ClipSession.State.Playing)?.paused == true) session.togglePause()
            Key.MediaPause -> if ((s as? ClipSession.State.Playing)?.paused == false) session.togglePause()
            Key.DirectionLeft, Key.DirectionRight -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (!repeat || now - lastSeekAt.longValue >= SEEK_REPEAT_MS) {
                    lastSeekAt.longValue = now
                    session.seekBy(if (e.key == Key.DirectionLeft) -SportsConstants.HUB_CLIP_SEEK_MS else SportsConstants.HUB_CLIP_SEEK_MS)
                }
            }
            Key.MediaRewind -> session.seekBy(-SportsConstants.HUB_CLIP_SEEK_LONG_MS)
            Key.MediaFastForward -> session.seekBy(SportsConstants.HUB_CLIP_SEEK_LONG_MS)
            Key.ChannelDown, Key.MediaPrevious -> if (!repeat) session.previous()
            Key.ChannelUp, Key.MediaNext -> if (!repeat) session.next()
            Key.Menu -> if (!repeat) session.toggleCaptions()
            Key.DirectionUp, Key.DirectionDown -> Unit // shows the bar (keySeq)
            else -> return false
        }
        return true
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag(CLIP_LAYER_TAG)
            .focusRequester(focus)
            .onPreviewKeyEvent(::onKey)
            .focusable(),
    ) {
        val s = state
        val upNext = (s as? ClipSession.State.Playing)?.upNext
        if (upNext != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 96.dp).testTag(CLIP_UP_NEXT_TAG)
                    .background(PlayerUi.CardFill, RoundedCornerShape(DesignTokens.Radii.Button))
                    .border(1.dp, PlayerUi.Border12, RoundedCornerShape(DesignTokens.Radii.Button))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) { Line(res.getString(R.string.sports_hub_clip_up_next, upNext), GuideUi.sans(28, FontWeight.SemiBold)) }
        }
        if (s is ClipSession.State.Failed) {
            ClipErrorCard(res, errorFocus, Modifier.align(Alignment.Center))
        } else if (barShown && s !is ClipSession.State.Idle) {
            TransportBar(res, s, pos, game, goals)
        }
    }
}

private const val SEEK_REPEAT_MS = 300L
private val HANDLED = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.MediaRewind, Key.MediaFastForward,
    Key.ChannelDown, Key.ChannelUp, Key.MediaPrevious, Key.MediaNext, Key.Menu,
)

private val BAR_GRADIENT = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))

/** y 852 → 1080 over a static black gradient; title 32/600, sub-line 24/500, progress 1728 × 6 at y 990, times, hints. */
@Composable
private fun TransportBar(res: Resources, s: ClipSession.State, pos: PositionSnapshot?, game: GameSnapshot?, goals: List<GoalItem>) {
    val (queue, index, title) = when (s) {
        is ClipSession.State.Playing -> Triple(s.queue, s.index, s.info?.title)
        is ClipSession.State.Resolving -> Triple(s.queue, s.index, null)
        else -> return
    }
    val ref = queue[index]
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(y = 852.dp).fillMaxWidth().height(228.dp).background(BAR_GRADIENT).testTag(CLIP_BAR_TAG)) {
            Column(Modifier.padding(start = 96.dp, end = 96.dp, top = 20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Line(title ?: "", GuideUi.sans(32, FontWeight.SemiBold), Modifier.weight(1f))
                    Line(clipHints(res), GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg70))
                }
                clipSubLine(res, ref, game, goals)?.let { Line(it, GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg70)) }
            }
            val duration = pos?.durationMs?.takeIf { it > 0 }
            val at = pos?.positionMs?.coerceAtLeast(0) ?: 0L
            val frac = if (duration != null) (at.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val desc = duration?.let { res.getString(R.string.sports_hub_desc_progress, spoken(res, at), spoken(res, it)) }
            Box(
                Modifier.offset(x = 96.dp, y = (990 - 852).dp).width(1728.dp).height(6.dp)
                    .background(GuideUi.White22, RoundedCornerShape(3.dp))
                    .then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier),
            ) {
                if (frac > 0f) Box(Modifier.width((1728 * frac).dp).height(6.dp).background(GuideUi.Accent, RoundedCornerShape(3.dp)))
            }
            Row(Modifier.offset(x = 96.dp, y = (1004 - 852).dp).width(1728.dp)) {
                Line(SportsText.clock((at / 1000).toInt()), GuideUi.mono(22, color = GuideUi.Fg70), Modifier.weight(1f))
                Line(duration?.let { SportsText.clock((it / 1000).toInt()) } ?: "", GuideUi.mono(22, color = GuideUi.Fg70))
            }
        }
    }
}

/** "OK Pause · ← → 10 s · Back Done" */
private fun clipHints(res: Resources): String {
    val sep = hubSep(res)
    return listOf(
        "OK " + res.getString(R.string.sports_hub_hint_pause),
        "← → " + res.getString(R.string.sports_hub_hint_seek),
        "Back " + res.getString(R.string.sports_hub_hint_done),
    ).joinToString(sep)
}

/** "Goal 1 of 6 · UTA at WPG · 1st 5:11" / "Game recap · UTA at WPG"; null without the game. */
internal fun clipSubLine(res: Resources, ref: ClipRef, game: GameSnapshot?, goals: List<GoalItem>): String? {
    game ?: return null
    val a = game.away.abbrev
    val h = game.home.abbrev
    return when (ref.kind) {
        ClipKind.RECAP -> res.getString(R.string.sports_hub_clip_recap_sub, a, h)
        ClipKind.CONDENSED -> res.getString(R.string.sports_hub_clip_condensed_sub, a, h)
        ClipKind.GOAL -> {
            val i = goals.indexOfFirst { it.clipId == ref.videoId }
            if (i < 0) null else res.getString(R.string.sports_hub_clip_goal_sub, i + 1, goals.size, a, h, goalWhen(res, goals[i]))
        }
    }
}

/** "12 seconds" / "1 minute 5 seconds". */
private fun spoken(res: Resources, ms: Long): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    val sec = res.getQuantityString(R.plurals.sports_hub_desc_seconds, s, s)
    return if (m == 0) sec else res.getQuantityString(R.plurals.sports_hub_desc_minutes, m, m) + if (s > 0) " $sec" else ""
}

/** §6.8 mid-clip card (the player's error-card look): the reason + next step, Try again / Back. */
@Composable
private fun ClipErrorCard(res: Resources, focused: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier.testTag(CLIP_ERROR_CARD_TAG).width(PlayerUi.CARD_W.dp)
            .background(PlayerUi.CardFill, shape).border(1.dp, PlayerUi.Border12, shape).padding(PlayerUi.CARD_PAD.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Line(res.getString(R.string.sports_hub_error_video_stopped), GuideUi.sans(30, FontWeight.SemiBold), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.sports_hub_clip_try_again, R.string.sports_hub_clip_back).forEachIndexed { i, label ->
                Chip(
                    focused = i == focused,
                    modifier = Modifier.testTag(clipErrorButtonTag(i)),
                    fill = PlayerUi.ChipFill,
                    shape = RoundedCornerShape(DesignTokens.Radii.Button),
                    padding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                ) { c -> Line(res.getString(label), GuideUi.sans(24, FontWeight.SemiBold, c)) }
            }
        }
    }
}
