package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Strength
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.DesignTokens
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

const val HUB_SHEET_TAG = "hub_sheet"
const val GOALS_SHEET_TAG = "hub_goals_sheet"
/** (HIGH-1) The frame's title: the sheet's focus target while it has no row. */
const val HUB_SHEET_FRAME_TAG = "hub_sheet_frame"
/** (HIGH-1) The Goals sheet's line while its goals are loading or did not load. */
const val GOALS_SHEET_NOTE_TAG = "hub_goals_sheet_note"
fun hubSheetRowTag(key: String) = "hub_sheet_row:$key"

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4; WP-5) The Goals sheet over the right column: goals in game order, then
 * (final) Game recap, Condensed game and the catch-up "Full game from the start" rows. A goal row's queue is that goal and
 * every later goal with a clip; the recap or condensed game is a queue of one. It takes focus on open (live: the latest
 * goal; final: the first) or on [focusVideoId] (the clip just played, U-S14). Back calls [onClose].
 *
 * [clipInfo] holds the lookups the page already made (durations, thumbnails), by video id. [onMissingClip]: OK on a goal
 * without a clip id (the page shows the §6.4 toast).
 *
 * The page keeps this sheet composed but not drawn under a clip (§2.5); a [focusVideoId] change while it is composed
 * moves focus to that row again.
 */
@Composable
fun GoalsSheet(
    game: GameSnapshot,
    /**
     * (HIGH-1) Null while the game's detail is loading or did not load: the slot is usable from the board's goal count, so
     * the sheet opens anyway with [note] (the reason and the next step) and, for a final game, the board's recap and
     * condensed game and the catch-up rows; the goals appear when the detail arrives.
     */
    detail: GameDetail?,
    clipInfo: Map<Long, ClipInfo>,
    catchUp: List<ChannelCandidate>,
    focusVideoId: Long?,
    onPlay: (queue: List<ClipRef>, index: Int) -> Unit,
    onPlayArchive: (ChannelCandidate) -> Unit,
    onMissingClip: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val res = LocalResources.current
    val final = game.phase == GamePhase.FINAL
    val rows = remember(detail, catchUp, final, game.media) { goalsSheetRows(detail, catchUp, final, game.media) }
    val initial = goalsSheetInitialRow(rows, focusVideoId, final)
    HubSheetFrame(
        title = res.getString(R.string.sports_hub_sheet_goals, game.away.abbrev, game.home.abbrev),
        rowCount = rows.size,
        initialRow = initial,
        focusKey = focusVideoId,
        onClose = onClose,
        modifier = modifier.testTag(GOALS_SHEET_TAG),
        rowsKey = rows,
    ) { requesters ->
        if (detail == null && note != null) {
            item(key = "note") {
                Line(note, GuideUi.sans(24, FontWeight.Medium, GuideUi.Fg70), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(GOALS_SHEET_NOTE_TAG), maxLines = 3)
            }
        }
        rows.forEachIndexed { i, row ->
            item(key = row.key) {
                when (row) {
                    is GoalsRow.Goal -> GoalRowView(res, game, row.goal, row.key, clipInfo[row.goal.clipId], requesters[i], first = i == 0, last = i == rows.lastIndex) {
                        val q = goalQueue(game, detail?.goals.orEmpty(), row.index)
                        if (q.isEmpty()) onMissingClip() else onPlay(q, 0)
                    }
                    is GoalsRow.Video -> VideoRowView(res, row, clipInfo[row.videoId], requesters[i], first = i == 0, last = i == rows.lastIndex) {
                        onPlay(listOf(ClipRef(row.videoId, row.kind, game.id)), 0)
                    }
                    is GoalsRow.CatchUp -> HubSheetRow(requesters[i], first = i == 0, last = i == rows.lastIndex, key = row.key, height = ROW_H, onClick = { onPlayArchive(row.candidate) }) { color ->
                        app.thebox.ui.full.ChannelLogo(row.candidate.channel, 96.dp, 54.dp, 6.dp)
                        Line(res.getString(R.string.sports_hub_catchup_row, row.candidate.channel.displayName), GuideUi.sans(26, FontWeight.SemiBold, color), Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ model (pure; GoalsSheetTest-friendly)

internal sealed interface GoalsRow {
    val key: String
    /** [index] in `detail.goals`. */
    data class Goal(val goal: GoalItem, val index: Int) : GoalsRow { override val key get() = "goal:$index" }
    data class Video(val videoId: Long, val kind: ClipKind) : GoalsRow { override val key get() = "video:$kind:$videoId" }
    data class CatchUp(val candidate: ChannelCandidate, val n: Int) : GoalsRow { override val key get() = "catchup:$n:${candidate.channel.identityHash}" }
}

/**
 * §6.4 order: goals, then (final) recap, condensed game, catch-up rows. (HIGH-1) Without a [detail] there are no goal rows;
 * the recap and condensed game then come from the board's [media].
 */
internal fun goalsSheetRows(detail: GameDetail?, catchUp: List<ChannelCandidate>, final: Boolean, media: GameMedia? = null): List<GoalsRow> = buildList {
    detail?.goals?.forEachIndexed { i, g -> add(GoalsRow.Goal(g, i)) }
    if (final) {
        (detail?.recapId ?: media?.recapId)?.let { add(GoalsRow.Video(it, ClipKind.RECAP)) }
        (detail?.condensedId ?: media?.condensedId)?.let { add(GoalsRow.Video(it, ClipKind.CONDENSED)) }
        catchUp.filter { it.airing != null }.forEachIndexed { n, c -> add(GoalsRow.CatchUp(c, n)) }
    }
}

/** §2.5: the clip just played (U-S14), else live: the latest goal, final: the first row. */
internal fun goalsSheetInitialRow(rows: List<GoalsRow>, focusVideoId: Long?, final: Boolean): Int {
    if (rows.isEmpty()) return 0
    if (focusVideoId != null) {
        val i = rows.indexOfFirst { (it is GoalsRow.Goal && it.goal.clipId == focusVideoId) || (it is GoalsRow.Video && it.videoId == focusVideoId) }
        if (i >= 0) return i
    }
    if (final) return 0
    val lastGoal = rows.indexOfLast { it is GoalsRow.Goal }
    return if (lastGoal >= 0) lastGoal else 0
}

/** §6.4: that goal and every later goal with a clip; empty when the goal itself has no clip yet. */
internal fun goalQueue(game: GameSnapshot, goals: List<GoalItem>, from: Int): List<ClipRef> {
    if (goals.getOrNull(from)?.clipId == null) return emptyList()
    return goals.drop(from).mapNotNull { g -> g.clipId?.let { ClipRef(it, ClipKind.GOAL, game.id) } }
}

/** "1st" / "2nd" / "3rd" / "OT" / "2OT" / "SO" (row words). */
internal fun hubPeriodRow(res: Resources, p: PeriodLabel): String = when (p.kind) {
    PeriodKind.REG -> when (p.number) {
        1 -> res.getString(R.string.sports_hub_row_period_1)
        2 -> res.getString(R.string.sports_hub_row_period_2)
        3 -> res.getString(R.string.sports_hub_row_period_3)
        else -> res.getString(R.string.sports_hub_row_ot)
    }
    PeriodKind.OT -> if (p.number <= 1) res.getString(R.string.sports_hub_row_ot) else res.getString(R.string.sports_hub_period_ot_n, p.number)
    PeriodKind.SO -> res.getString(R.string.sports_hub_row_so)
}

/** "1st 5:11" */
internal fun goalWhen(res: Resources, g: GoalItem): String = res.getString(R.string.sports_hub_goal_when, hubPeriodRow(res, g.period), g.timeInPeriod)

/** Line 1: "G. Vilardi (4) · PP" (tags PP, SH, EN; nothing at even strength). */
internal fun goalLine1(res: Resources, g: GoalItem): String {
    val sep = res.getString(R.string.sports_hub_summary_sep)
    val name = g.scorerTotal?.let { res.getString(R.string.sports_hub_goal_line1, g.scorer, it) } ?: g.scorer
    val tags = buildList {
        when (g.strength) {
            Strength.PP -> add(res.getString(R.string.sports_hub_goal_tag_pp))
            Strength.SH -> add(res.getString(R.string.sports_hub_goal_tag_sh))
            Strength.EV -> Unit
        }
        if (g.emptyNet) add(res.getString(R.string.sports_hub_goal_tag_en))
    }
    return (listOf(name) + tags).joinToString(sep)
}

/** Line 2: "1st 5:11 · WPG leads 1–0 · Connor, Morrissey" ("Tied 2–2"; "· Video soon" without a clip). */
internal fun goalLine2(res: Resources, game: GameSnapshot, g: GoalItem): String {
    val sep = res.getString(R.string.sports_hub_summary_sep)
    val score = when {
        g.away == g.home -> res.getString(R.string.sports_hub_goal_tied, g.away, g.home)
        g.away > g.home -> res.getString(R.string.sports_hub_goal_leads, game.away.abbrev, g.away, g.home)
        else -> res.getString(R.string.sports_hub_goal_leads, game.home.abbrev, g.home, g.away)
    }
    val parts = buildList {
        add(goalWhen(res, g))
        add(score)
        if (g.assistsLast.isNotEmpty()) add(g.assistsLast.joinToString(", "))
        if (g.clipId == null) add(res.getString(R.string.sports_hub_goal_video_soon))
    }
    return parts.joinToString(sep)
}

/** "4:55" from ms; null when unknown. */
internal fun clipDuration(ms: Long?): String? = ms?.takeIf { it > 0 }?.let { SportsText.clock(((it + 500) / 1000).toInt()) }

// ------------------------------------------------------------------ rows

private const val ROW_H = 96

@Composable
private fun GoalRowView(
    res: Resources,
    game: GameSnapshot,
    g: GoalItem,
    key: String,
    info: ClipInfo?,
    requester: FocusRequester,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val dim = g.clipId == null
    HubSheetRow(requester, first, last, key = key, height = ROW_H, dimmed = dim, onClick = onClick) { color ->
        HubHeadshot(g.headshot, g.scorer, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Line(goalLine1(res, g), GuideUi.sans(26, FontWeight.SemiBold, color))
            Line(goalLine2(res, game, g), GuideUi.sans(22, FontWeight.Medium, GuideUi.secondary(color, focusedOf(color), 0.70f)))
        }
        if (!dim) Line(clipDuration(info?.durationMs) ?: "▶", GuideUi.mono(22, FontWeight.Medium, color))
    }
}

@Composable
private fun VideoRowView(res: Resources, row: GoalsRow.Video, info: ClipInfo?, requester: FocusRequester, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val duration = clipDuration(info?.durationMs)
    HubSheetRow(requester, first, last, key = row.key, height = ROW_H, onClick = onClick) { color ->
        ClipThumbnail(row.videoId, info?.thumbnailUrl ?: info?.posterUrl, 128.dp, 72.dp)
        val label = when (row.kind) {
            ClipKind.RECAP -> res.getString(R.string.sports_hub_row_recap)
            else -> duration?.let { res.getString(R.string.sports_hub_row_condensed, it) } ?: res.getString(R.string.sports_hub_row_condensed_plain)
        }
        Line(label, GuideUi.sans(26, FontWeight.SemiBold, color), Modifier.weight(1f))
        if (row.kind == ClipKind.RECAP) duration?.let { Line(it, GuideUi.mono(22, FontWeight.Medium, color)) }
    }
}

/** The clip's picture, with the §6.3 cache keys (signed URLs change per lookup). */
@Composable
internal fun ClipThumbnail(videoId: Long, url: String?, width: Dp, height: Dp) {
    val loader = LocalShellImageLoader.current
    val shape = RoundedCornerShape(DesignTokens.Radii.Chip)
    Box(Modifier.size(width, height).background(GuideUi.White08, shape)) {
        if (loader != null && !url.isNullOrBlank()) {
            val context = LocalContext.current
            // (Review L7) The request in real px (the root scales design px by its density), as HubMedia sizes the poster.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val w = with(density) { width.roundToPx() }
            val h = with(density) { height.roundToPx() }
            val request = remember(videoId, url, w, h) {
                val key = "nhl-clip:$videoId:${w}x$h"
                ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).size(w, h).build()
            }
            AsyncImage(request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
        }
    }
}

/** A round headshot from the feed (no placeholder text; an empty disc while it loads or without one). */
@Composable
internal fun HubHeadshot(url: String?, name: String, size: Dp) {
    val loader = LocalShellImageLoader.current
    Box(Modifier.size(size).background(GuideUi.White08, CircleShape)) {
        if (loader != null && !url.isNullOrBlank()) {
            AsyncImage(url, contentDescription = name, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
    }
}

// ------------------------------------------------------------------ the shared sheet frame (§6.4)

/** Whether the row's content colour is the focused one (dark on the FG fill). */
internal fun focusedOf(color: Color): Boolean = color == GuideUi.Canvas

internal fun isBack(e: KeyEvent): Boolean = e.key == Key.Back || e.key == Key.Escape || e.key == Key.ButtonB

/**
 * §6.4 frame for every sheet: over the right column, x 856, y 160, w 968, h 864, radius 24, `OverlayFill` + 1 px
 * `OverlayBorder`, padding 28; [title] 32/600; a `LazyColumn` of focusable rows from y 84 (inside the frame). Focus goes to
 * row [initialRow] on open and again when [focusKey] changes; Back (on key up) calls [onClose]; Left / Right never leave
 * the sheet. [header] sits between the title and the list (the box score's tabs). Opaque, no layer.
 *
 * (Review H1) Whenever [rowsKey] changes and nothing in the sheet holds focus (the focused row left with the change: Retry
 * pressed, a failure replacing the rows, rows arriving after open), focus goes to [refocus] (default: row [initialRow]),
 * so Back (handled only with focus inside) always works. (Review H2) No request is made while [LocalHubFocusable] is false
 * (a clip plays over the sheet): the clip layer keeps focus whatever the rows do.
 *
 * (Verification review HIGH-1, MED-1) A sheet with no row to focus (the goals still loading, a channel list emptied by a
 * re-match, a matchup without rows) would leave nothing focused and Back dead, since the page behind is not focusable.
 * Whenever there is no row and no [refocus] target, the frame's title takes focus itself ([HUB_SHEET_FRAME_TAG]), so Back
 * always reaches [onClose]; once rows arrive focus moves on to them.
 */
@Composable
internal fun HubSheetFrame(
    title: String,
    rowCount: Int,
    initialRow: Int,
    focusKey: Any?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    requesters: List<FocusRequester> = remember(rowCount) { List(rowCount) { FocusRequester() } },
    takeInitialFocus: Boolean = true,
    rowsKey: Any? = rowCount,
    /** The target after a change of [rowsKey] left the sheet without focus, with the list index to scroll to (null = none). */
    refocus: (() -> Pair<FocusRequester, Int?>?)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: LazyListScope.() -> Unit = {},
    content: LazyListScope.(requesters: List<FocusRequester>) -> Unit,
) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    val focusable = LocalHubFocusable.current
    // Focus facts, read only by the effects (plain holders: no recomposition on every focus move). [0] = the frame holds
    // focus, [1] = a row of the list holds it, [2] = the frame's own title holds it (HIGH-1).
    val focus = remember { BooleanArray(3) }
    // Composed before the change is applied, so it still sees the row that is about to leave.
    val rowHadFocus = remember(rowsKey) { focus[1] }
    // (HIGH-1) The frame's own focus target, used only while the sheet has nothing else to focus.
    val frameRequester = remember { FocusRequester() }
    val selfFocus = rowCount == 0 && refocus == null
    val target by androidx.compose.runtime.rememberUpdatedState(
        refocus ?: { if (rowCount == 0) frameRequester to null else initialRow.coerceIn(0, rowCount - 1).let { requesters[it] to it } },
    )
    // Composed before the change is applied: the frame's title held focus (an empty sheet whose rows now arrive).
    val frameHadFocus = remember(rowsKey) { focus[2] }
    if (takeInitialFocus) {
        LaunchedEffect(focusKey) {
            if (!focusable) return@LaunchedEffect
            if (rowCount == 0) {
                withFrameNanos { }
                if (refocus == null) runCatching { frameRequester.requestFocus() }
                return@LaunchedEffect
            }
            val i = initialRow.coerceIn(0, rowCount - 1)
            listState.scrollToItem(i)
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
        }
    }
    // The first run is the open (the initial request above, or the sheet's own); later runs are changes of the rows.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(rowsKey, focusable) {
        val first = !opened
        opened = true
        if (first) return@LaunchedEffect
        withFrameNanos { }
        // Lost: nothing in the sheet has focus, the focused row left (the frame's group then hands focus to its first
        // child, which is not where the user was), or the frame's title held it and rows have arrived.
        val lost = !focus[0] || (rowHadFocus && !focus[1]) || (frameHadFocus && rowCount > 0) || (focus[2] && rowCount > 0)
        if (!focusable || !lost) return@LaunchedEffect
        val (req, index) = target() ?: return@LaunchedEffect
        if (index != null) listState.scrollToItem(index)
        withFrameNanos { }
        runCatching { req.requestFocus() }
    }
    Box(modifier.fillMaxSize().testTag(HUB_SHEET_TAG)) {
        Column(
            Modifier
                .padding(start = 856.dp, top = 160.dp)
                .size(968.dp, 864.dp)
                .onFocusChanged { focus[0] = it.hasFocus }
                .focusGroup()
                .background(GuideUi.OverlayFill, shape)
                .border(1.dp, GuideUi.OverlayBorder, shape)
                .onPreviewKeyEvent { e ->
                    when {
                        isBack(e) -> {
                            if (e.type == KeyEventType.KeyUp) onClose()
                            true
                        }
                        else -> false
                    }
                }
                .padding(28.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(HUB_SHEET_FRAME_TAG)
                    .focusRequester(frameRequester)
                    .onFocusChanged { focus[2] = it.isFocused }
                    .focusProperties { canFocus = focusable && selfFocus }
                    .onPreviewKeyEvent { e -> e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionUp || e.key == Key.DirectionDown }
                    .focusable(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Line(title, GuideUi.sans(32, FontWeight.SemiBold))
            }
            header?.invoke()
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).onFocusChanged { focus[1] = it.hasFocus },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
            ) {
                content(requesters)
                footer()
            }
        }
    }
}

/**
 * One focusable sheet row: [height] tall, radius 12, focused = FG fill + accent ring (the shell's [Chip] look); Up on
 * the [first] and Down on the [last] row stop there, Left / Right are consumed. [dimmed]: drawn at half strength (still
 * focusable: OK explains why it does nothing).
 */
@Composable
internal fun HubSheetRow(
    requester: FocusRequester,
    first: Boolean,
    last: Boolean,
    key: String,
    height: Int,
    dimmed: Boolean = false,
    upTarget: FocusRequester? = null,
    description: String? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(contentColor: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DesignTokens.Radii.Row)
    val focusable = LocalHubFocusable.current
    var m = Modifier
        .testTag(hubSheetRowTag(key))
        .fillMaxWidth()
        .height(height.dp)
        .focusRequester(requester)
        .focusProperties { canFocus = focusable }
        .onPreviewKeyEvent { e ->
            when (e.key) {
                Key.DirectionLeft, Key.DirectionRight -> true
                Key.DirectionUp -> if (first && upTarget != null) {
                    if (e.type == KeyEventType.KeyDown) runCatching { upTarget.requestFocus() }
                    true
                } else first
                Key.DirectionDown -> last
                else -> false
            }
        }
        .onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    if (description != null) m = m.semantics(mergeDescendants = true) { contentDescription = description }
    if (focused) m = m.border(GuideUi.FocusRing, GuideUi.Accent, shape)
    val base = if (focused) GuideUi.Canvas else GuideUi.Fg
    val color = if (dimmed && !focused) base.copy(alpha = 0.5f) else base
    Row(
        m.background(if (focused) GuideUi.Fg else Color.Transparent, shape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) { content(color) }
}

