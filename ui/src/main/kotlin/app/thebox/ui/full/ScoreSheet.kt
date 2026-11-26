package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.Gear
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.settings.SettingsUi
import app.thebox.ui.sports.HubLogo
import app.thebox.ui.sports.Measure
import app.thebox.ui.sports.SportsText
import app.thebox.ui.sports.rememberHubMeasure
import app.thebox.ui.theme.DesignTokens

/** (Score sheet restyle) A game row's one-line matchup title, for the fit tests. */
internal const val SCORE_GAME_TITLE_TAG = "score_sheet_game_title"
/** A live / final game's score block at the right of the row. */
internal const val SCORE_GAME_SCORE_TAG = "score_sheet_game_score"
/** A game row's second line (the start, or LIVE · the state, or Final). */
internal const val SCORE_GAME_STATUS_TAG = "score_sheet_game_status"

/** The focused row (owner mock-up): a translucent light fill and a light 2 px border; the text stays light. */
private val FocusFill = Color.White.copy(alpha = 0.14f)
private val FocusBorder = GuideUi.Fg.copy(alpha = 0.90f)
private val ValueInk = GuideUi.Fg70
private val ChipFill = GuideUi.White12
private val Hairline = Color.White.copy(alpha = 0.14f)
private val GearInk = GuideUi.Fg.copy(alpha = 0.35f)

/**
 * (Score sheet restyle, owner mock-up 2026-11-26) The quick bar's "Score bug" sheet ([PickerKind.SCORE_BUG]) and its nested
 * delay list ([PickerKind.SCORE_DELAY]), drawn from the same [PickerState] as before: the picker's box, fill, border and
 * radius; an upper-case title with a dim gear; settings rows with the label left and the value right; a hairline and a
 * small upper-case header over the games; two-line game rows between the teams' logos; a light translucent focus.
 * The rows ([PickerState.choices]), their order, focus and keys are the state machine's, unchanged; [scoreWindow] picks the
 * rows that fit under the title ([ScoreSheetMetrics]). No layer, clip or blur.
 */
@Composable
internal fun ScoreSheet(state: PickerState, modifier: Modifier = Modifier, clock24: Boolean = true) {
    val res = LocalResources.current
    val factor = UiTextScale.factor
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    val measure = rememberHubMeasure()
    Column(
        modifier
            .testTag(PICKER_SHEET_TAG)
            .size(PlayerUi.PICKER_W.dp, PlayerUi.PICKER_H.dp)
            .background(PlayerUi.PanelFill, shape)
            .border(1.dp, PlayerUi.Border09, shape)
            .padding(horizontal = ScoreSheetMetrics.PAD_H.dp, vertical = ScoreSheetMetrics.PAD_V.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(ScoreSheetMetrics.titleH(factor).dp).padding(horizontal = ScoreSheetMetrics.ROW_PAD_H.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(ScoreSheetText.title(res, delay = state.kind == PickerKind.SCORE_DELAY), GuideUi.sans(28, FontWeight.Bold, letterSpacingEm = 0.04f), Modifier.weight(1f))
            Gear(GearInk, Modifier.size(26.dp))
        }
        Spacer(Modifier.height(ScoreSheetMetrics.TITLE_GAP.dp))
        val heights = state.choices.map { ScoreSheetMetrics.height(it, factor) }
        // (Lead review 2026-11-26) One title form and size for all the sheet's games (not only the shown window, so it
        // does not change while the list scrolls).
        val density = LocalDensity.current
        val vs = stringResource(R.string.sports_picker_vs)
        val titleStep = remember(state.choices, factor, density.density, vs, measure) {
            val columnPx = with(density) { ScoreSheetMetrics.gameTextW().dp.toPx() }
            val gapPx = with(density) { ScoreSheetMetrics.SCORE_GAP.dp.toPx() }
            val scorePx = ScoreSheetFit.scoreBlockPx(measure)
            ScoreSheetFit.commonStep(
                state.choices.filterIsInstance<PickerChoice.ScoreGame>().map { titleAsk(it.game, ScoreSheetFit.titleWidth(columnPx, ScoreSheetFit.scored(it.game), scorePx, gapPx) - 2f) },
                vs, measure,
            )
        }
        val window = scoreWindow(heights, state.focused, ScoreSheetMetrics.budget(factor))
        for (i in window) {
            if (i != window.first) Spacer(Modifier.height(ScoreSheetMetrics.GAP.dp))
            val c = state.choices[i]
            val focused = i == state.focused
            val h = heights[i]
            when (c) {
                PickerChoice.ScoreTodayDivider, is PickerChoice.ScoreDayDivider -> HeaderRow(ScoreSheetText.header(res, c, clock24), h)
                is PickerChoice.ScoreGame -> GameRow(c, focused, clock24, h, measure, titleStep)
                is PickerChoice.ScoreFailed -> FailedRow(PlayerText.choice(res, c, i, clock24), focused, h)
                PickerChoice.ScoreLoading -> TextRow(PlayerText.choice(res, c, i, clock24), focused, h, GuideUi.Fg50)
                is PickerChoice.ScoreDelayChoice -> DelayChoiceRow(SportsText.delay(res, c.delaySec), checked = i == state.current, focused, h)
                else -> {
                    val s = ScoreSheetText.setting(res, c)
                    if (s != null) SettingRow(s, c, focused, h) else TextRow(PlayerText.choice(res, c, i, clock24), focused, h, GuideUi.Fg)
                }
            }
        }
    }
}

private fun Modifier.scoreRow(focused: Boolean, heightPx: Int): Modifier {
    val shape = RoundedCornerShape(DesignTokens.Radii.Row)
    return this
        .testTag(PICKER_ROW_TAG)
        .height(heightPx.dp)
        .fillMaxWidth()
        .background(if (focused) FocusFill else Color.Transparent, shape)
        .then(if (focused) Modifier.border(ScoreSheetMetrics.FOCUS_BORDER.dp, FocusBorder, shape) else Modifier)
        .padding(horizontal = ScoreSheetMetrics.ROW_PAD_H.dp)
}

/** The hairline across the sheet, then the small upper-case, letter-spaced header (never focused: skipped by `selectable`). */
@Composable
private fun HeaderRow(text: String, heightPx: Int) {
    Column(Modifier.testTag(PICKER_DIVIDER_TAG).fillMaxWidth().height(heightPx.dp)) {
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(Hairline))
        Spacer(Modifier.weight(1f))
        Line(text, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg50, letterSpacingEm = 0.08f), Modifier.padding(start = ScoreSheetMetrics.ROW_PAD_H.dp, bottom = 2.dp))
    }
}

/** League / CHL league / Corner / Delay / Style / Stop following: the label left, the value right (a chip for the delay). */
@Composable
private fun SettingRow(s: SettingText, c: PickerChoice, focused: Boolean, heightPx: Int) {
    Row(Modifier.scoreRow(focused, heightPx), verticalAlignment = Alignment.CenterVertically) {
        Line(s.label, GuideUi.sans(26, FontWeight.Medium), Modifier.weight(1f))
        val value = s.value ?: return@Row
        Spacer(Modifier.width(12.dp))
        if (s.logo != null) {
            // An empty code: nothing shows while the mark loads, or if it never does (no loader in tests). Always fetched as
            // a non-CHL logo: a league mark is not on the CHL team-logo host, whose URL check would refuse it.
            HubLogo(TeamLine(abbrev = "", name = "", score = null, logo = s.logo), League.NHL, ScoreSheetMetrics.LEAGUE_LOGO_W, ScoreSheetMetrics.LEAGUE_LOGO, align = Alignment.CenterEnd)
            Spacer(Modifier.width(10.dp))
        }
        if (s.chip) {
            Box(Modifier.background(ChipFill, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                Line(value, GuideUi.sans(22, FontWeight.SemiBold))
            }
        } else {
            Line(value, GuideUi.sans(26, FontWeight.Medium, if (focused) GuideUi.Fg.copy(alpha = 0.85f) else ValueInk))
        }
    }
}

/** A one-line row (Loading, or any row without a label / value split). */
@Composable
private fun TextRow(text: String, focused: Boolean, heightPx: Int, ink: Color) {
    Row(Modifier.scoreRow(focused, heightPx), verticalAlignment = Alignment.CenterVertically) {
        Line(text, GuideUi.sans(26, FontWeight.Medium, ink), Modifier.weight(1f))
    }
}

/** The nested delay list: "Off" / "10 s" left, a check mark right on the current one. */
@Composable
private fun DelayChoiceRow(text: String, checked: Boolean, focused: Boolean, heightPx: Int) {
    Row(Modifier.scoreRow(focused, heightPx), verticalAlignment = Alignment.CenterVertically) {
        Line(text, GuideUi.sans(26, FontWeight.Medium), Modifier.weight(1f))
        if (checked) Line("✓", GuideUi.sans(26, FontWeight.SemiBold, GuideUi.Accent))
    }
}

/**
 * (CHL emulator pass, defect 1) A score failure: the whole reason and next step, wrapped at 22/500 (up to [FAILED_LINES]
 * lines) in a row two settings rows tall, never ellipsised. Focus and OK work as on any row (OK retries).
 */
@Composable
private fun FailedRow(text: String, focused: Boolean, heightPx: Int) {
    Row(Modifier.scoreRow(focused, heightPx), verticalAlignment = Alignment.CenterVertically) {
        Line(text, GuideUi.sans(22, FontWeight.Medium), Modifier.weight(1f).testTag(PICKER_FAILED_TAG), maxLines = FAILED_LINES)
    }
}

/**
 * A game (owner change 2026-11-26): the away logo at the left; a text column with the one-line matchup title over the
 * start / LIVE / Final; for a game in progress or final the score at the right ("14 – 10", a fixed-width block so the
 * scores line up); then the followed game's small green marker, whose slot every game row keeps ("Following" for TalkBack).
 */
@Composable
private fun GameRow(c: PickerChoice.ScoreGame, focused: Boolean, clock24: Boolean, heightPx: Int, measure: Measure, titleStep: Int) {
    val g = c.game
    val league = g.id.league
    val following = stringResource(R.string.sports_picker_followed_desc)
    val density = LocalDensity.current
    val factor = UiTextScale.factor
    val scored = ScoreSheetFit.scored(g)
    val scorePx = remember(measure, factor, density.density) { ScoreSheetFit.scoreBlockPx(measure) }
    Row(
        Modifier.scoreRow(focused, heightPx).then(if (c.followed) Modifier.semantics { contentDescription = following } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val columnPx = with(density) { ScoreSheetMetrics.gameTextW().dp.toPx() }
            val gapPx = with(density) { ScoreSheetMetrics.SCORE_GAP.dp.toPx() }
            MatchupLine(g, ScoreSheetFit.titleWidth(columnPx, scored, scorePx, gapPx) - 2f, titleStep, measure)
            Spacer(Modifier.height(2.dp))
            StatusLine(g, clock24)
        }
        if (scored) {
            Spacer(Modifier.width(ScoreSheetMetrics.SCORE_GAP.dp))
            ScoreBlock(g, with(density) { scorePx.toDp() })
        }
        Spacer(Modifier.width(ScoreSheetMetrics.MARKER_GAP.dp))
        Box(
            Modifier.size(ScoreSheetMetrics.MARKER_W.dp, ScoreSheetMetrics.MARKER_H.dp)
                .then(if (c.followed) Modifier.background(SettingsUi.Good, RoundedCornerShape(4.dp)) else Modifier),
        )
    }
}

private fun titleAsk(g: GameSnapshot, maxPx: Float): ScoreSheetFit.TitleAsk {
    val league = g.id.league
    return ScoreSheetFit.TitleAsk(g.away.abbrev, ScoreSheetText.teamName(league, g.away), g.home.abbrev, ScoreSheetText.teamName(league, g.home), maxPx)
}

/** "CHI Bears vs DET Lions" on one line, never wrapped or cut: the [ScoreSheetFit.matchup] ladder within [maxPx]. */
@Composable
private fun MatchupLine(g: GameSnapshot, maxPx: Float, titleStep: Int, measure: Measure) {
    val vs = stringResource(R.string.sports_picker_vs)
    val plan = remember(g.away, g.home, maxPx, titleStep, vs, measure) { ScoreSheetFit.shared(titleAsk(g, maxPx), titleStep, vs, measure) }
    val league = g.id.league
    val style = GuideUi.sans(plan.designPx, FontWeight.SemiBold)
    val awayText = remember(plan, vs) {
        buildAnnotatedString {
            append(plan.away)
            append(' ')
            withStyle(SpanStyle(color = GuideUi.Fg55, fontWeight = FontWeight.Medium)) { append(vs) }
        }
    }
    // (Owner 2026-11-26) Both logos, each right before its own team; the slot stays empty until the art arrives.
    Row(verticalAlignment = Alignment.CenterVertically) {
        HubLogo(g.away.copy(abbrev = ""), league, ScoreSheetMetrics.TEAM_LOGO, ScoreSheetMetrics.TEAM_LOGO, align = Alignment.Center)
        Spacer(Modifier.width(ScoreSheetMetrics.LOGO_GAP.dp))
        BasicText(awayText, Modifier.testTag(SCORE_GAME_TITLE_TAG), style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        Spacer(Modifier.width(ScoreSheetMetrics.LOGO_GAP.dp))
        HubLogo(g.home.copy(abbrev = ""), league, ScoreSheetMetrics.TEAM_LOGO, ScoreSheetMetrics.TEAM_LOGO, align = Alignment.Center)
        Spacer(Modifier.width(ScoreSheetMetrics.LOGO_GAP.dp))
        BasicText(AnnotatedString(plan.home), Modifier.testTag(SCORE_GAME_TITLE_TAG), style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** "14 – 10" (away first) at 30/700 tabular, right-aligned in a fixed block; a final's loser in the dim ink. */
@Composable
private fun ScoreBlock(g: GameSnapshot, width: Dp) {
    val text = remember(g.away.score, g.home.score, g.phase) {
        fun ink(side: Int) = if (ScoreSheetFit.dimmed(g, side)) GuideUi.Fg50 else GuideUi.Fg
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink(0))) { append((g.away.score ?: 0).toString()) }
            withStyle(SpanStyle(color = GuideUi.Fg50)) { append(" – ") }
            withStyle(SpanStyle(color = ink(1))) { append((g.home.score ?: 0).toString()) }
        }
    }
    BasicText(
        text, Modifier.width(width).testTag(SCORE_GAME_SCORE_TAG),
        style = GuideUi.sans(ScoreSheetMetrics.SCORE_PX, FontWeight.Bold).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
    )
}

/** The second line: "● LIVE · 2nd 10:22" (the live red), or the start / "Postponed" / "Final/OT" in the dim ink. */
@Composable
private fun StatusLine(g: GameSnapshot, clock24: Boolean) {
    val res = LocalResources.current
    val nowMs = remember(g, clock24) { System.currentTimeMillis() }
    val status = remember(g, clock24, nowMs) { ScoreSheetText.status(res, g, clock24, nowMs) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.live) {
            Dot(GuideUi.Live, 10.dp)
            Spacer(Modifier.width(8.dp))
        }
        val live = stringResource(R.string.sports_live)
        val text = remember(status, live) {
            buildAnnotatedString {
                if (status.live) {
                    withStyle(SpanStyle(color = GuideUi.Live, fontWeight = FontWeight.Bold)) { append(live) }
                    append("  ·  ")
                }
                append(status.text)
            }
        }
        BasicText(
            text, Modifier.testTag(SCORE_GAME_STATUS_TAG), style = GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg60),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val FAILED_LINES = 4
