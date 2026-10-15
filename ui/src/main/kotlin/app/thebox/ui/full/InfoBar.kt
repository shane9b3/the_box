package app.thebox.ui.full

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.InfoBarState
import app.thebox.core.full.InfoGlyphs
import app.thebox.core.full.PillState
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val INFO_BAR_TAG = "player_info_bar"

/**
 * The info bar (docs/design/m4-player-ux.md §3.2, §3.9): passive (the zap card, no keys) or active (↑/↓ programmes,
 * ←/→ channels). The caller places it at (64, 820) 1792 × 220 design px. Header: number chip, logo 64, name 24/600,
 * state pill, glyphs (↺ 7d · 1080p50 · 5.1 · CC · HDR). Then the focused programme (30/600) with its time row (a 4 px
 * progress bar for the programme on now, redrawn every 15 s in the draw phase only), "Catch-up · press OK" / "Not in
 * catch-up" for a past one and "Starts 21:00" for a future one; last the next programme or the browse hint.
 * [nowMs] classifies past / now / future (the composition's clock; the progress bar ticks on its own).
 */
@Composable
fun InfoBar(state: InfoBarState, nowMs: Long, modifier: Modifier = Modifier, clock24: Boolean = true, detail: ProgrammeInfo? = null) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val shape = RoundedCornerShape(PlayerUi.BAR_R.dp)
    Column(
        modifier
            .testTag(INFO_BAR_TAG)
            .background(PlayerUi.BarFill, shape)
            .border(1.dp, PlayerUi.Border09, shape)
            .padding(horizontal = PlayerUi.BAR_PAD_H.dp, vertical = PlayerUi.BAR_PAD_V.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BarHeader(state.channel, state.pill, glyphsText(res, state.glyphs, state.channel), title = null)
        val focused = state.programmes.getOrNull(state.focused)
        val nowMin = (nowMs / 60_000L).toInt()
        when {
            focused != null -> {
                Line(focused.title, GuideUi.sans(30, FontWeight.SemiBold, letterSpacingEm = -0.01f))
                TimeRow(focused, nowMin, state.channel, clock24, onNow = state.focused == state.nowIndex)
            }
            state.loading -> Line(stringResource(R.string.player_info_loading), GuideUi.sans(30, FontWeight.SemiBold, GuideUi.Fg60))
            else -> Line(stringResource(R.string.player_info_no_info), GuideUi.sans(30, FontWeight.SemiBold, GuideUi.Fg60))
        }
        val next = if (state.focused >= 0) state.programmes.getOrNull(state.focused + 1) else null
        // The focused programme's synopsis (`ProgrammeFeed.at`, matched by raw start), in the active bar only.
        val synopsis = detail?.takeIf { state.active && focused != null && it.rawStartMin == focused.rawStartMin }
            ?.let { d -> listOfNotNull(d.episodeLabel, d.subtitle, d.description).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } }
        when {
            synopsis != null && !state.browsing -> Line(synopsis, GuideUi.sans(17, color = GuideUi.Fg65))
            state.active && (state.browsing || (state.focused != state.nowIndex && state.focused >= 0)) ->
                Line(stringResource(R.string.player_info_browse_hint), GuideUi.sans(15, color = GuideUi.Fg50))
            next != null -> Line(stringResource(R.string.player_info_next, clockOfMin(next.startMin, clock24), next.title), GuideUi.sans(19, color = GuideUi.Fg65))
        }
    }
}

/** Header row shared with the scrub bar: number chip, logo, name (+ [title] under it), pill, glyphs on the right. */
@Composable
internal fun BarHeader(channel: GuideChannel?, pill: PillState, glyphs: String?, title: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        NumberChip(channel?.number)
        ChannelLogo(channel, PlayerUi.LOGO.dp, PlayerUi.LOGO_R.dp)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Line(title, GuideUi.sans(26, FontWeight.SemiBold))
            channel?.let { Line(it.displayName, if (title != null) GuideUi.sans(17, color = GuideUi.Fg70) else GuideUi.sans(24, FontWeight.SemiBold)) }
        }
        PlayerPill(pill)
        Spacer(Modifier.weight(1f))
        glyphs?.let { Line(it, GuideUi.mono(15, color = GuideUi.Fg70)) }
    }
}

@Composable
private fun TimeRow(p: ProgrammeInfo, nowMin: Int, channel: GuideChannel?, clock24: Boolean, onNow: Boolean) {
    val isNow = onNow || (nowMin >= p.startMin && nowMin < p.endMin)
    if (isNow && p.endMin > p.startMin) {
        // 2 draw ops (track + fill), redrawn every 15 s (M3 mini cadence) without recomposition (§4.1).
        val tick = rememberTicker(PROGRESS_TICK_MS)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val style = GuideUi.mono(15, color = GuideUi.Fg70)
            Line(clockOfMin(p.startMin, clock24), style)
            Box(
                Modifier
                    .width(PROGRESS_W.dp)
                    .height(4.dp)
                    .drawBehind {
                        val nowM = tick.value / 60_000.0
                        val f = ((nowM - p.startMin) / (p.endMin - p.startMin)).coerceIn(0.0, 1.0).toFloat()
                        drawRect(PlayerUi.ProgressBase)
                        drawRect(Color.White, size = Size(size.width * f, size.height))
                    },
            )
            Line(clockOfMin(p.endMin, clock24), style)
        }
        return
    }
    val text = when {
        p.endMin <= nowMin -> if (inArchive(p, nowMin, channel)) stringResource(R.string.player_info_catchup_ok) else stringResource(R.string.player_info_not_in_catchup)
        else -> stringResource(R.string.player_info_starts, clockOfMin(p.startMin, clock24))
    }
    Line(text, GuideUi.sans(19, color = GuideUi.Fg70))
}

private fun inArchive(p: ProgrammeInfo, nowMin: Int, channel: GuideChannel?): Boolean {
    val days = channel?.archiveDays ?: 0
    return days > 0 && p.startMin >= nowMin - days * 1_440
}

/** "↺ 7d · 1080p50 · 5.1 · CC · HDR" (§3.2.4); null when nothing is known. */
internal fun glyphsText(res: Resources, g: InfoGlyphs?, channel: GuideChannel?): String? {
    val parts = ArrayList<String>(5)
    val days = g?.catchupDays ?: channel?.archiveDays ?: 0
    if (days > 0) parts += res.getString(R.string.player_glyph_catchup, days)
    g?.resolution?.let { parts += it }
    g?.audioLayout?.let { parts += it }
    if (g?.subtitlesAvailable == true) parts += res.getString(R.string.player_glyph_cc)
    g?.let { PlayerText.hdr(it.hdr) }?.let { parts += res.getString(it) }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private const val PROGRESS_TICK_MS = 15_000L
private const val PROGRESS_W = 420f
