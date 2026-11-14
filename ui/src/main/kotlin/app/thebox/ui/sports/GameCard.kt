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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.isChl
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.DesignTokens
import kotlin.math.roundToInt

internal const val SPORTS_CARD_TAG = "sports_card:"
internal const val SPORTS_FOLLOWING_TAG = "sports_following:"
internal const val SPORTS_ACTION_TAG = "sports_action:"
internal fun sportsCardTag(gameId: String) = SPORTS_CARD_TAG + gameId

/** The logo plate and the focused card's ink (`Canvas`, `#0A0D13`). */
private val Plate = DesignTokens.Colors.Canvas
private val Ink = DesignTokens.Colors.Canvas
private val AccentText = DesignTokens.Colors.AccentText
private val Accent22 = DesignTokens.Colors.OnNowFill

/**
 * Design metrics of the card (docs/design/m6-score-bug.md §5.2; owner request 2026-11-06, larger for 10-foot readability).
 * Internal so the fit test measures the same numbers the card lays out with.
 */
internal object GameCardMetrics {
    const val CARD_W = 564
    const val CARD_H = 208
    const val PAD = 18
    const val HEADER_H = 32
    const val HEADER_GAP = 12
    const val ROW_H = 60
    const val ROW_GAP = 8
    const val PLATE_W = 80
    const val PLATE_H = 56
    const val PLATE_GAP = 16
    const val NAME_PX = 32
    const val SCORE_PX = 44
    const val SCORE_W = 72
    const val NAME_SCORE_GAP = 8
    const val STATE_PX = 24
    const val CHIP_PX = 22
    const val CHIP_PAD_H = 12
    const val DOT = 12
    const val DOT_GAP = 8
    const val INNER_W = CARD_W - 2 * PAD
    /** The team name's column: the row minus plate, gap, score column and the gap before it. */
    const val NAME_W = INNER_W - PLATE_W - PLATE_GAP - SCORE_W - NAME_SCORE_GAP
}

/**
 * One game card (docs/design/m6-score-bug.md §5.2), 564 x 208 design px (1 dp = 1 design px inside the root's scale).
 * A header strip (state on the left: LIVE with the clock as the day list last reported it (owner ruling 6: no local
 * countdown), `1st INT`, `Final/OT` or the start time; one chip on the right), then two team rows (logo plate, common
 * name, score). Focused: `#EEF1F6` fill, a 3 px accent ring and the action chip. Followed: a "✓ Following" chip, never
 * the red dot.
 *
 * Focus is Compose's (the Sports tab is on Compose focus): the card is a `clickable`, OK follows or stops following.
 * Nothing ticks: a card recomposes only when the board brings a new snapshot.
 */
@Composable
internal fun GameCard(
    card: GameCardModel,
    clock24: Boolean,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val res = LocalResources.current
    val g = card.game
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    val ink = if (focused) Ink else GuideUi.Fg
    val desc = run {
        val state = SportsText.state(res, g, clock24)
        val live = if (g.phase == GamePhase.LIVE) res.getString(R.string.sports_live) + ", " else ""
        res.getString(
            if (card.followed) R.string.sports_card_desc_following else R.string.sports_card_desc,
            g.away.name, g.away.score?.toString() ?: "", g.home.name, g.home.score?.toString() ?: "", live + state,
        )
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(GameCardMetrics.CARD_H.dp)
            .fillMaxWidth()
            .testTag(sportsCardTag(g.id.id))
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .background(if (focused) GuideUi.Fg else DesignTokens.Colors.GlassFill, shape)
            .border(if (focused) GuideUi.FocusRing else 1.dp, if (focused) GuideUi.Accent else DesignTokens.Colors.GlassBorder, shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Column(Modifier.fillMaxSize().padding(GameCardMetrics.PAD.dp)) {
            Row(Modifier.fillMaxWidth().height(GameCardMetrics.HEADER_H.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { StateLine(card, clock24, ink) }
                // One chip at a time: the focused card's action, else the followed card's "✓ Following".
                if (focused) ActionChip(g.id.id, card.followed) else if (card.followed) FollowingChip(g.id.id, false)
            }
            Spacer(Modifier.height(GameCardMetrics.HEADER_GAP.dp))
            TeamRow(g.away, ink, g.id.league.isChl)
            Spacer(Modifier.height(GameCardMetrics.ROW_GAP.dp))
            TeamRow(g.home, ink, g.id.league.isChl)
        }
    }
}

/** The name a card draws for [team]: the NFL short name when the feed gave one, else the common name. */
internal fun cardName(team: TeamLine): String = team.shortName ?: team.name

@Composable
private fun TeamRow(team: TeamLine, ink: Color, chl: Boolean) {
    Row(Modifier.fillMaxWidth().height(GameCardMetrics.ROW_H.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoPlate(team, chl)
        Spacer(Modifier.width(GameCardMetrics.PLATE_GAP.dp))
        // (NFL §7.2, S5) The NFL shows the short name ("Commanders"); the NHL's `name` is already the common name.
        Line(cardName(team), GuideUi.sans(GameCardMetrics.NAME_PX, FontWeight.SemiBold, ink), Modifier.weight(1f))
        Spacer(Modifier.width(GameCardMetrics.NAME_SCORE_GAP.dp))
        Line(
            team.score?.toString().orEmpty(),
            GuideUi.sans(GameCardMetrics.SCORE_PX, FontWeight.Bold, ink).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End),
            Modifier.width(GameCardMetrics.SCORE_W.dp),
        )
    }
}

/**
 * The fixed `#0A0D13` 80 x 56 radius-10 plate: the abbreviation shows until the logo has loaded (never waits on the
 * network). The logo comes through WP-3's path ([coilLogoFetch]: the app loader with the SVG decoder, rasterised and
 * trimmed to its art by [LogoTrim], fitted to the 70 x 45 inner slot), so the cards and the bug crop logos the same way.
 * The `_dark` art is drawn on the dark plate in both focus states (§5.2), so its white parts never sit on the light card.
 */
@Composable
private fun LogoPlate(team: TeamLine, chl: Boolean) {
    val loader = LocalShellImageLoader.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val url = team.logo
    val w = (LOGO_SLOT_W * density).roundToInt().coerceAtLeast(1)
    val h = (LOGO_SLOT_H * density).roundToInt().coerceAtLeast(1)
    // U-S6: trimmed card logos are held in a small process LRU, so a day change, a re-open or a row scrolled back in
    // shows the logo at once instead of re-rasterising the SVG.
    val logo by produceState(url?.let { CardLogoCache.get(it, w, h) }, url, loader, w, h) {
        value = url?.let { CardLogoCache.get(it, w, h) }
        if (value != null || url == null || loader == null) return@produceState
        // (Review S2) A CHL logo that failed (404, no alpha) is not fetched again for a while: the code plate stays.
        if (chl && CardLogoCache.recentlyFailed(url)) return@produceState
        val bmp = runCatching { coilLogoFetch(loader, context, chl)(url, w, h) }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrNull()?.asImageBitmap()
        if (bmp != null) CardLogoCache.put(url, w, h, bmp) else if (chl) CardLogoCache.markFailed(url)
        value = bmp
    }
    Box(Modifier.size(GameCardMetrics.PLATE_W.dp, GameCardMetrics.PLATE_H.dp).background(Plate, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        val img = logo
        if (img == null) Line(team.abbrev, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg60))
        else Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(LOGO_SLOT_W.dp, LOGO_SLOT_H.dp))
    }
}

/**
 * (Fix pass, U-S6) Trimmed card logos by URL and slot size: about 64 entries of a few KB each (70 × 45 design px), so the
 * whole league for two densities fits. The bug's two logos stay in [FollowLogos] (A-S11).
 */
internal object CardLogoCache {
    private val lru = android.util.LruCache<String, ImageBitmap>(64)
    private fun key(url: String, w: Int, h: Int) = "$url@${w}x$h"
    fun get(url: String, w: Int, h: Int): ImageBitmap? = lru.get(key(url, w, h))
    fun put(url: String, w: Int, h: Int, img: ImageBitmap) { lru.put(key(url, w, h), img) }

    // (CHL review S2) The negative cache: URL to the elapsed-realtime ms of its failure. CHL only (NHL and NFL logos never
    // fail in steady state and keep today's behaviour); bounded to 128 URLs (the 61 teams and spare), expiring after
    // [FAILED_TTL_MS] and with the process.
    const val FAILED_TTL_MS = 6 * 60 * 60 * 1000L
    private const val FAILED_MAX = 128
    private val failed = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > FAILED_MAX
    }

    fun recentlyFailed(url: String, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean = synchronized(failed) {
        val at = failed[url] ?: return false
        if (now - at >= FAILED_TTL_MS) { failed.remove(url); false } else true
    }

    fun markFailed(url: String, now: Long = android.os.SystemClock.elapsedRealtime()) { synchronized(failed) { failed[url] = now } }

    /** Tests only. */
    internal fun clearFailed() { synchronized(failed) { failed.clear() } }
}

/** The logo's slot inside the 80 x 56 plate (5 px clear on each side: the old 56 x 36 slot x 1.25). Design px. */
private const val LOGO_SLOT_W = 70
private const val LOGO_SLOT_H = 45

/** Live games: the red dot, LIVE, then `2nd · 10:22`. Every other game: its state words (24/600, `.75`; live text full ink). */
@Composable
private fun StateLine(card: GameCardModel, clock24: Boolean, ink: Color) {
    val res = LocalResources.current
    val g = card.game
    val style = GuideUi.sans(GameCardMetrics.STATE_PX, FontWeight.SemiBold, ink.copy(alpha = 0.75f))
    if (g.phase != GamePhase.LIVE) {
        Line(SportsText.state(res, g, clock24), style, Modifier.testTag("sports_state:" + g.id.id))
        return
    }
    val p = SportsText.periodOf(g)
    val detail = when {
        p == null -> null
        p.kind == PeriodKind.SO -> SportsText.period(res, p, g.id.league)
        g.clock?.inIntermission == true -> res.getString(R.string.sports_intermission, SportsText.period(res, p, g.id.league))
        else -> {
            val secs = SportsViewModel.clockSecs(card)
            if (secs == null) SportsText.period(res, p, g.id.league)
            else res.getString(R.string.sports_card_live_line, SportsText.period(res, p, g.id.league), SportsText.clock(secs))
        }
    }
    Row(Modifier.testTag("sports_state:" + g.id.id), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GameCardMetrics.DOT_GAP.dp)) {
        Dot(GuideUi.Live, GameCardMetrics.DOT.dp)
        val full = style.copy(color = ink)
        Line(stringResource(R.string.sports_live), full)
        if (detail != null) Line(detail, full)
    }
}

/** "✓ Following": accentText on White08; focused `#0A0D13` on accent .22 (the lead's sign-off: never the red dot). */
@Composable
private fun FollowingChip(gameId: String, focused: Boolean) {
    Chip22(
        stringResource(R.string.sports_card_following),
        textColor = if (focused) Ink else AccentText,
        fill = if (focused) Accent22 else GuideUi.White08,
        modifier = Modifier.testTag(SPORTS_FOLLOWING_TAG + gameId),
    )
}

/** The focused card's action: "OK Follow score" or "OK Stop following". */
@Composable
private fun ActionChip(gameId: String, followed: Boolean) {
    Chip22(
        stringResource(if (followed) R.string.sports_card_action_stop else R.string.sports_card_action_follow),
        textColor = GuideUi.Fg,
        fill = Ink,
        modifier = Modifier.testTag(SPORTS_ACTION_TAG + gameId),
    )
}

@Composable
private fun Chip22(text: String, textColor: Color, fill: Color, modifier: Modifier) {
    Box(
        Modifier.height(GameCardMetrics.HEADER_H.dp).background(fill, RoundedCornerShape(DesignTokens.Radii.Chip)).padding(horizontal = GameCardMetrics.CHIP_PAD_H.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The tag sits on the text node, so tests (and TalkBack) read the chip's words.
        Line(text, TextStyle.Default.merge(GuideUi.sans(GameCardMetrics.CHIP_PX, FontWeight.SemiBold, textColor)), modifier)
    }
}
