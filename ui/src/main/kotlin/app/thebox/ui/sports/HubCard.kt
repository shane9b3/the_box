package app.thebox.ui.sports

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.group
import app.thebox.core.sports.isChl
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.DesignTokens
import app.thebox.ui.theme.textSp
import kotlin.math.roundToInt

internal const val SPORTS_CARD_TAG = "sports_card:"
internal const val SPORTS_FOLLOWING_TAG = "sports_following:"
/** (CHL integration, L10) The followed card's refusal text, while the game's feed refuses the app. */
internal const val SPORTS_REFUSED_TAG = "sports_refused:"
/** (Integration review L1) The refused card's matchup row. */
internal const val SPORTS_REFUSED_MATCHUP_TAG = "sports_refused_matchup:"
internal const val SPORTS_HIGHLIGHTS_TAG = "sports_highlights:"
internal const val SPORTS_STATE_TAG = "sports_state:"
internal const val SPORTS_AWAY_NAME_TAG = "sports_away_name:"
internal const val SPORTS_HOME_NAME_TAG = "sports_home_name:"
internal fun sportsCardTag(gameId: String) = SPORTS_CARD_TAG + gameId

/**
 * (Sports Hub WP-4, docs/design/m6-sports-hub.md §1.2) One game card's design metrics: 472 × 304, padding 24, two team rows of
 * 88 (y 24 and y 128) and a bottom row of 44 at y 236. At Larger and Largest the logo slot is 72 × 52 with a 12 gap and the
 * name column 248 wide. Internal so the fit test measures the numbers the card lays out with.
 */
internal object HubCardMetrics {
    const val CARD_W = 472
    const val CARD_H = 304
    const val PAD = 24
    const val ROW_H = 88
    const val ROW_GAP = 16
    const val BOTTOM_H = 44
    const val SCORE_W = 76
    const val PILL_PAD = 16
    const val CHIP_PAD = 16
    const val PILL_PX = 22
    const val CHIP_PX = 22
    const val INNER_W = CARD_W - 2 * PAD
    /** The gap between the pill and the chip. */
    const val BOTTOM_GAP = 12
    const val REFUSED_MATCHUP_H = 40
    const val REFUSED_REASON_LINES = 3

    /** Larger (1.1) and Largest (1.2). */
    fun large(factor: Float): Boolean = factor > 1.05f
    fun logoW(factor: Float) = if (large(factor)) 72 else 88
    fun logoH(factor: Float) = if (large(factor)) 52 else 64
    fun logoGap(factor: Float) = if (large(factor)) 12 else 16
    fun nameW(factor: Float) = if (large(factor)) 248 else 220
}

/** The card's fills (§1.1): normal, focused, and the status pill's live fill. */
private val CardFill = Color(0xFF171C24)
private val CardFocusFill = Color(0xFF2A3340)
private val LiveFill = Color(0xFF5A1F24)
private val AccentText = DesignTokens.Colors.AccentText

/**
 * One game card. [focused] is Compose focus (a 4 px `Accent` ring inside the bounds, fill `#2A3340`); [selected] is "the panel
 * has focus and shows this game" (a 2 px `Accent` outline, normal fill): exactly one thing looks pressed (U-S12). OK, Menu
 * and (left column) Left call [onEnter]; nothing here follows or stops following (that is the panel's slot 1).
 */
@Composable
internal fun HubCard(
    card: GameCardModel,
    clock24: Boolean,
    selected: Boolean,
    leftColumn: Boolean,
    showHighlights: Boolean,
    optionsText: String?,
    onEnter: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** (Review L9) The grid passes one measurer for every card; a lone card makes its own. */
    measure: Measure = rememberHubMeasure(),
) {
    val res = LocalResources.current
    val g = card.game
    val factor = UiTextScale.factor
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val nowMs = remember(g, clock24) { System.currentTimeMillis() }
    val pill = remember(g, clock24, nowMs) { SportsText.hubPill(res, g, clock24, nowMs) }
    val league = g.id.league
    val away = remember(g) { HubNames.of(league, g.away) }
    val home = remember(g) { HubNames.of(league, g.home) }
    val refused = if (card.refused) SportsText.failure(res, FailKind.REFUSED, league) else null
    val desc = run {
        val head = if (g.away.score != null && g.home.score != null && g.phase != GamePhase.SCHEDULED && g.phase != GamePhase.PRE) {
            res.getString(R.string.sports_hub_desc_card_score, away.full, g.away.score!!, home.full, g.home.score!!)
        } else res.getString(R.string.sports_hub_desc_card_matchup, away.full, home.full)
        buildString {
            append(head).append(' ').append(SportsText.state(res, g, clock24, nowMs = nowMs)).append('.')
            if (card.followed) append(' ').append(res.getString(R.string.sports_hub_desc_following))
            if (refused != null) append(' ').append(refused)
            if (optionsText != null) append(' ').append(optionsText)
        }
    }
    val focusable = LocalHubFocusable.current
    val ring = when {
        focused -> Modifier.border(4.dp, GuideUi.Accent, shape)
        selected -> Modifier.border(2.dp, GuideUi.Accent, shape)
        else -> Modifier.border(1.dp, GuideUi.White08, shape)
    }
    Box(
        modifier
            .height(HubCardMetrics.CARD_H.dp)
            .fillMaxWidth()
            .testTag(sportsCardTag(g.id.id))
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            // Menu opens the panel; Left from the left column does the same (§2.2). OK is the clickable's.
            .onPreviewKeyEvent { e ->
                if (!focused) false
                else if (e.key == Key.Menu) { if (e.type == KeyEventType.KeyUp) onEnter(); true }
                else if (leftColumn && e.key == Key.DirectionLeft) { if (e.type == KeyEventType.KeyDown) onEnter(); true }
                else false
            }
            .background(if (focused) CardFocusFill else CardFill, shape)
            .then(ring)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onEnter)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Column(Modifier.fillMaxSize().padding(HubCardMetrics.PAD.dp)) {
            if (refused != null) {
                Box(Modifier.fillMaxWidth().height(HubCardMetrics.REFUSED_MATCHUP_H.dp), contentAlignment = Alignment.CenterStart) {
                    val matchup = res.getString(R.string.sports_card_live_line, away.nickname, home.nickname)
                    val widthPx = with(LocalDensity.current) { HubCardMetrics.INNER_W.dp.toPx() }
                    val size = remember(matchup, factor, widthPx) { HubFit.size(HubFit.NAME_SIZES, listOf(matchup), widthPx, 600, measure) }
                    Line(matchup, GuideUi.sans(size, FontWeight.SemiBold), Modifier.fillMaxWidth().testTag(SPORTS_REFUSED_MATCHUP_TAG + g.id.id))
                }
                Line(
                    refused, GuideUi.sans(22, FontWeight.Medium, GuideUi.Warn),
                    Modifier.fillMaxWidth().height((2 * HubCardMetrics.ROW_H + HubCardMetrics.ROW_GAP - HubCardMetrics.REFUSED_MATCHUP_H).dp).testTag(SPORTS_REFUSED_TAG + g.id.id),
                    maxLines = HubCardMetrics.REFUSED_REASON_LINES,
                )
            } else {
                val winner = if (g.phase == GamePhase.FINAL && g.away.score != null && g.home.score != null) {
                    if (g.away.score!! > g.home.score!!) 0 else if (g.home.score!! > g.away.score!!) 1 else -1
                } else -1
                TeamRow(g.away, away, league, 0, g.phase, winner, SPORTS_AWAY_NAME_TAG + g.id.id, measure)
                Spacer(Modifier.height(HubCardMetrics.ROW_GAP.dp))
                TeamRow(g.home, home, league, 1, g.phase, winner, SPORTS_HOME_NAME_TAG + g.id.id, measure)
            }
            Spacer(Modifier.weight(1f))
            BottomRow(pill, g.id.id, card.followed, showHighlights && g.media?.let { it.recapId != null || it.condensedId != null || it.goalClips > 0 } == true)
        }
    }
}

/** One team row: the logo slot, the name column (place over nickname) and the score (§1.2). */
@Composable
private fun TeamRow(team: TeamLine, name: HubName, league: League, index: Int, phase: GamePhase, winner: Int, nameTag: String, measure: Measure) {
    val factor = UiTextScale.factor
    val density = LocalDensity.current
    val nameCol = HubCardMetrics.nameW(factor)
    val nameSize = remember(name, factor, density.density) { HubFit.nameSize(name, with(density) { nameCol.dp.toPx() }, measure) }
    val scoreText = if (phase == GamePhase.SCHEDULED || phase == GamePhase.PRE || phase == GamePhase.POSTPONED) "" else team.score?.toString().orEmpty()
    val scoreSize = remember(scoreText, factor, density.density) { HubFit.scoreSize(scoreText, with(density) { HubCardMetrics.SCORE_W.dp.toPx() }, measure) }
    val scoreColor = if (winner >= 0 && winner != index) GuideUi.Fg55 else GuideUi.Fg
    Row(Modifier.fillMaxWidth().height(HubCardMetrics.ROW_H.dp), verticalAlignment = Alignment.CenterVertically) {
        HubLogo(team, league, HubCardMetrics.logoW(factor), HubCardMetrics.logoH(factor))
        Spacer(Modifier.width(HubCardMetrics.logoGap(factor).dp))
        Column(Modifier.width(nameCol.dp).testTag(nameTag), verticalArrangement = Arrangement.Center) {
            val style = GuideUi.sans(nameSize, FontWeight.SemiBold).copy(lineHeight = textSp(34))
            name.cardLines.forEach { Line(it, style) }
        }
        Spacer(Modifier.weight(1f))
        Line(
            scoreText,
            GuideUi.sans(scoreSize, FontWeight.Bold, scoreColor).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End),
            Modifier.width(HubCardMetrics.SCORE_W.dp),
        )
    }
}

/** A text-width measurer for composition: [Measure] over the real fonts at the Text size, in px. */
@Composable
internal fun rememberHubMeasure(): Measure {
    val measurer = rememberTextMeasurer()
    return remember(measurer) {
        { text, px, weight ->
            measurer.measure(text, GuideUi.sans(px, FontWeight(weight)), maxLines = 1, softWrap = false).size.width.toFloat()
        }
    }
}

/** The bottom row: the status pill, then at most one chip (§1.2); the chip is hidden when both do not fit, never the pill. */
@Composable
private fun BottomRow(pill: SportsText.HubPill, gameId: String, followed: Boolean, highlights: Boolean) {
    val chip = followed || highlights
    Layout(
        content = {
            StatusPill(pill, Modifier.testTag(SPORTS_STATE_TAG + gameId))
            if (followed) {
                Chip(Modifier) { Line(androidx.compose.ui.res.stringResource(R.string.sports_hub_chip_following), GuideUi.sans(HubCardMetrics.CHIP_PX, FontWeight.SemiBold, AccentText), Modifier.testTag(SPORTS_FOLLOWING_TAG + gameId)) }
            } else if (highlights) {
                Chip(Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(GuideUi.Live, 10.dp)
                        Line(androidx.compose.ui.res.stringResource(R.string.sports_hub_chip_highlights), GuideUi.sans(HubCardMetrics.CHIP_PX, FontWeight.SemiBold), Modifier.testTag(SPORTS_HIGHLIGHTS_TAG + gameId))
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(HubCardMetrics.BOTTOM_H.dp),
    ) { ms, c ->
        val loose = Constraints(maxHeight = c.maxHeight)
        val p = ms[0].measure(loose)
        val k = if (chip) ms.getOrNull(1)?.measure(loose) else null
        val gap = HubCardMetrics.BOTTOM_GAP.dp.roundToPx()
        val fits = k != null && p.width + gap + k.width <= c.maxWidth
        layout(c.maxWidth, c.maxHeight) {
            p.placeRelative(0, (c.maxHeight - p.height) / 2)
            if (fits && k != null) k.placeRelative(p.width + gap, (c.maxHeight - k.height) / 2)
        }
    }
}

@Composable
private fun Chip(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.height(HubCardMetrics.BOTTOM_H.dp).background(GuideUi.White08, RoundedCornerShape(22.dp)).padding(horizontal = HubCardMetrics.CHIP_PAD.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A status pill (§1.2): h 44, radius 12, padding 16, 22/700 caps; live has the red dot and the `#5A1F24` fill. */
@Composable
internal fun StatusPill(pill: SportsText.HubPill, modifier: Modifier = Modifier, height: Int = HubCardMetrics.BOTTOM_H) {
    Box(
        modifier.height(height.dp).background(if (pill.live) LiveFill else GuideUi.White08, RoundedCornerShape(12.dp)).padding(horizontal = HubCardMetrics.PILL_PAD.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pill.live) Dot(GuideUi.Live, 12.dp)
            Line(pill.text, GuideUi.sans(HubCardMetrics.PILL_PX, FontWeight.Bold, if (pill.live) GuideUi.Fg else GuideUi.Fg70))
        }
    }
}

// ------------------------------------------------------------------ the logo (no plate)

/**
 * A team's logo fitted into a [wDp] × [hDp] slot (§1.2: no plate; the `_dark` art as today). The abbreviation shows until
 * the logo has loaded (never waits on the network). The logo comes through [coilLogoFetchDetailed] (the app loader with the
 * SVG decoder, rasterised and trimmed to its art by [LogoTrim]), so the cards and the bug crop logos the same way.
 */
@Composable
internal fun HubLogo(team: TeamLine, league: League, wDp: Int, hDp: Int, modifier: Modifier = Modifier, align: Alignment = Alignment.CenterStart) {
    val loader = LocalShellImageLoader.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val url = team.logo
    val chl = league.isChl
    val w = (wDp * density).roundToInt().coerceAtLeast(1)
    val h = (hDp * density).roundToInt().coerceAtLeast(1)
    // U-S6: trimmed card logos are held in a small process LRU, so a day change, a re-open or a row scrolled back in
    // shows the logo at once instead of re-rasterising the SVG.
    val logo by produceState(url?.let { CardLogoCache.get(it, w, h) }, url, loader, w, h) {
        value = url?.let { CardLogoCache.get(it, w, h) }
        if (value != null || url == null || loader == null) return@produceState
        // (Review S2) A CHL logo that failed (404, no alpha) is not fetched again for a while: the code stays.
        if (chl && CardLogoCache.recentlyFailed(url)) return@produceState
        val outcome = runCatching { coilLogoFetchDetailed(loader, context, chl, null)(url, w, h) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrElse { LogoOutcome(null, it) }
        val bmp = outcome.bitmap?.asImageBitmap()
        // (Integration review S2) 6 h only for a permanent failure (4xx, no alpha, a rejected URL); 60 s for the rest.
        if (bmp != null) CardLogoCache.put(url, w, h, bmp) else if (chl) failedLogoTtlMs(outcome)?.let { CardLogoCache.markFailed(url, ttlMs = it) }
        value = bmp
    }
    Box(modifier.size(wDp.dp, hDp.dp), contentAlignment = align) {
        val img = logo
        if (img == null) Line(team.abbrev, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg60))
        else Image(img, contentDescription = null, contentScale = ContentScale.Fit, alignment = align, modifier = Modifier.size(wDp.dp, hDp.dp))
    }
}

/**
 * (Fix pass, U-S6) Trimmed card logos by URL and slot size: about 64 entries of a few KB each, so the whole league for two
 * densities fits. The bug's two logos stay in [FollowLogos] (A-S11).
 */
internal object CardLogoCache {
    private val lru = android.util.LruCache<String, ImageBitmap>(64)
    private fun key(url: String, w: Int, h: Int) = "$url@${w}x$h"
    fun get(url: String, w: Int, h: Int): ImageBitmap? = lru.get(key(url, w, h))
    fun put(url: String, w: Int, h: Int, img: ImageBitmap) { lru.put(key(url, w, h), img) }

    // (CHL review S2) The negative cache: URL to the elapsed-realtime ms until which it counts as failed. CHL only (NHL and NFL
    // logos never fail in steady state); bounded to 128 URLs, expiring after [FAILED_TTL_MS] for a permanent failure or
    // [TRANSIENT_TTL_MS] for any other (integration review S2), and with the process.
    const val FAILED_TTL_MS = 6 * 60 * 60 * 1000L
    /** (Integration review S2) A transient failure (no network, timeout, 5xx, decode): tried again after a minute. */
    const val TRANSIENT_TTL_MS = 60_000L
    private const val FAILED_MAX = 128
    private val failed = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > FAILED_MAX
    }

    /** URL to the elapsed-realtime ms until which it counts as failed. */
    fun recentlyFailed(url: String, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean = synchronized(failed) {
        val until = failed[url] ?: return false
        if (now >= until) { failed.remove(url); false } else true
    }

    fun markFailed(url: String, now: Long = android.os.SystemClock.elapsedRealtime(), ttlMs: Long = FAILED_TTL_MS) {
        synchronized(failed) { failed[url] = now + ttlMs }
    }

    /** Tests only. */
    internal fun clearFailed() { synchronized(failed) { failed.clear() } }
}
