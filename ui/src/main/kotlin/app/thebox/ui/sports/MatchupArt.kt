package app.thebox.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.Contrast
import app.thebox.core.sports.ChlTeamColours
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.group
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

/**
 * (Sports Hub WP-4, docs/design/m6-sports-hub.md §1.3) The matchup art: the area split on a 12 degree diagonal into the two
 * teams' primary colours (each darkened until white text has 4.5 : 1), the logos 120 px (88 below a 160 px area) centred in
 * their halves, and a centre disc (`#0B0E14`) with [centreTop] over [centreBottom]. [caption] (the venue, 22/500) sits at the
 * bottom. Static brushes only: no blur, no graphics layer.
 */
@Composable
internal fun MatchupArt(
    away: TeamLine,
    home: TeamLine,
    league: League,
    centreTop: String,
    centreBottom: String?,
    caption: String?,
    heightDp: Int,
    modifier: Modifier = Modifier,
) {
    val compact = heightDp < 160
    val logo = if (compact) 88 else 120
    val left = MatchupColours.of(league, away.abbrev)
    val right = MatchupColours.of(league, home.abbrev)
    Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            val dx = (h / 2f) * TAN_12
            val cx = w / 2f
            val l = Path().apply { moveTo(0f, 0f); lineTo(cx + dx, 0f); lineTo(cx - dx, h); lineTo(0f, h); close() }
            val r = Path().apply { moveTo(cx + dx, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(cx - dx, h); close() }
            drawPath(l, left)
            drawPath(r, right)
        },
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HubLogo(away, league, logo, logo, align = Alignment.Center) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HubLogo(home, league, logo, logo, align = Alignment.Center) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val d = if (compact) 88 else 120
            Column(
                Modifier.defaultMinSize(d.dp, d.dp).background(Color(0xFF0B0E14), RoundedCornerShape(50)).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Line(centreTop, GuideUi.sans(26, FontWeight.Bold))
                if (centreBottom != null) Line(centreBottom, GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg70))
            }
        }
        if (caption != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Line(caption, GuideUi.sans(22, FontWeight.Medium)) }
        }
    }
}

private val TAN_12 = Math.tan(Math.toRadians(12.0)).toFloat()

/** The art's two colours (§1.3): the team's primary, darkened until white text on it passes 4.5 : 1. */
internal object MatchupColours {
    fun of(league: League, abbrev: String): Color = Color(darken(base(league, abbrev)))

    private fun base(league: League, abbrev: String): Int = when (league.group) {
        LeagueGroup.NHL -> NhlTeamColours.of(abbrev).base
        LeagueGroup.NFL -> NflTeamColours.of(abbrev).base
        LeagueGroup.CHL -> ChlTeamColours.of(league, abbrev).base
    }

    /** Scales the channels down in 5 % steps until [Contrast.ratio] against white is at least 4.5 (never below black). */
    fun darken(argb: Int): Int {
        var c = 0xFF000000.toInt() or (argb and 0xFFFFFF)
        var f = 1.0f
        while (Contrast.ratio(0xFFFFFFFF.toInt(), c) < 4.5 && f > 0f) {
            f -= 0.05f
            val r = (((argb shr 16) and 0xFF) * f).toInt()
            val g = (((argb shr 8) and 0xFF) * f).toInt()
            val b = ((argb and 0xFF) * f).toInt()
            c = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        return c
    }
}
