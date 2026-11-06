package app.thebox.ui.sports

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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import coil3.compose.AsyncImage

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
 * One game card (docs/design/m6-score-bug.md §5.2), 564 x 128 design px (1 dp = 1 design px inside the root's scale).
 * Two team rows (logo plate, common name, score), and the state column on the right: LIVE with the ticking clock, `1st
 * INT`, `Final/OT` or the start time. Focused: `#EEF1F6` fill, a 3 px accent ring and an action chip. Followed: a
 * "✓ Following" chip, never the red dot.
 *
 * Focus is Compose's (the Sports tab is on Compose focus): the card is a `clickable`, OK follows or stops following.
 * [nowMono] is read only by the state line, so the per-second tick recomposes that line and nothing else.
 */
@Composable
internal fun GameCard(
    card: GameCardModel,
    nowMono: State<Long>,
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
            .height(128.dp)
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
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamRow(g.away, ink)
                TeamRow(g.home, ink)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.width(STATE_COLUMN_W.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                StateLine(card, nowMono, clock24, ink)
                if (card.followed) FollowingChip(g.id.id, focused)
                if (focused) ActionChip(g.id.id, card.followed)
            }
        }
    }
}

private const val STATE_COLUMN_W = 232

@Composable
private fun TeamRow(team: TeamLine, ink: Color) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoPlate(team)
        Spacer(Modifier.width(12.dp))
        Line(team.name, GuideUi.sans(26, FontWeight.SemiBold, ink), Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Line(
            team.score?.toString().orEmpty(),
            GuideUi.sans(34, FontWeight.Bold, ink).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End),
            Modifier.width(44.dp),
        )
    }
}

/**
 * The fixed `#0A0D13` 64 x 44 radius-8 plate: the abbreviation shows until the logo has loaded (never waits on the
 * network). WP-3's `TeamLogo` replaces this at integration.
 */
@Composable
private fun LogoPlate(team: TeamLine) {
    var loaded by remember(team.logo) { mutableStateOf(false) }
    Box(Modifier.size(64.dp, 44.dp).background(Plate, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (!loaded) Line(team.abbrev, GuideUi.sans(18, FontWeight.SemiBold, GuideUi.Fg60))
        val url = team.logo
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

/** Live games: the red dot, LIVE, then `2nd · 10:22`. Every other game: its state words (22/500, `.75`). */
@Composable
private fun StateLine(card: GameCardModel, nowMono: State<Long>, clock24: Boolean, ink: Color) {
    val res = LocalResources.current
    val g = card.game
    val style = GuideUi.sans(22, FontWeight.Medium, ink.copy(alpha = 0.75f))
    if (g.phase != GamePhase.LIVE) {
        Line(SportsText.state(res, g, clock24), style, Modifier.testTag("sports_state:" + g.id.id))
        return
    }
    val p = SportsText.periodOf(g)
    val detail = when {
        p == null -> null
        p.kind == PeriodKind.SO -> SportsText.period(res, p)
        g.clock?.inIntermission == true -> res.getString(R.string.sports_intermission, SportsText.period(res, p))
        else -> {
            val secs = SportsViewModel.clockSecs(card, nowMono.value)
            if (secs == null) SportsText.period(res, p)
            else res.getString(R.string.sports_card_live_line, SportsText.period(res, p), SportsText.clock(secs))
        }
    }
    Row(Modifier.testTag("sports_state:" + g.id.id), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(GuideUi.Live, 10.dp)
        Line(stringResource(R.string.sports_live), style.copy(fontWeight = FontWeight.SemiBold))
        if (detail != null) Line(detail, style)
    }
}

/** "✓ Following": accentText on White08; focused `#0A0D13` on accent .22 (the lead's sign-off: never the red dot). */
@Composable
private fun FollowingChip(gameId: String, focused: Boolean) {
    Chip18(
        stringResource(R.string.sports_card_following),
        textColor = if (focused) Ink else AccentText,
        fill = if (focused) Accent22 else GuideUi.White08,
        modifier = Modifier.testTag(SPORTS_FOLLOWING_TAG + gameId),
    )
}

/** The focused card's action: "OK Follow score" or "OK Stop following". */
@Composable
private fun ActionChip(gameId: String, followed: Boolean) {
    Chip18(
        stringResource(if (followed) R.string.sports_card_action_stop else R.string.sports_card_action_follow),
        textColor = GuideUi.Fg,
        fill = Ink,
        modifier = Modifier.testTag(SPORTS_ACTION_TAG + gameId),
    )
}

@Composable
private fun Chip18(text: String, textColor: Color, fill: Color, modifier: Modifier) {
    Box(Modifier.background(fill, RoundedCornerShape(DesignTokens.Radii.Chip)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        // The tag sits on the text node, so tests (and TalkBack) read the chip's words.
        Line(text, TextStyle.Default.merge(GuideUi.sans(18, FontWeight.SemiBold, textColor)), modifier)
    }
}
