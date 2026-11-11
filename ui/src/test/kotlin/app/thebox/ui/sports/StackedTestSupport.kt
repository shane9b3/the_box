package app.thebox.ui.sports

import android.graphics.Bitmap
import app.thebox.core.sports.BottomBar
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * (Stacked WP-3) Views, inputs and hooks for the Stacked runner tests. The views are CGY @ EDM with shots on goal and the
 * period label (`ScoreBugView.period`, B1), as the machine builds them for the NHL.
 */
internal object StackedRunFixtures {
    val P2 = PeriodLabel(PeriodKind.REG, 2)

    /** A view in play (2nd, 10:22, 0–2, shots 12–20) with [pp], or any [top] / [bottom]. */
    fun view(
        top: CentreText = CentreText.Clock,
        bottom: CentreText? = CentreText.Period(P2),
        away: Int = 0,
        home: Int = 2,
        awaySog: Int? = 12,
        homeSog: Int? = 20,
        dim: Boolean = false,
        clockSecs: Int? = if (top == CentreText.Clock) 622 else null,
        pp: PowerPlay? = null,
        goalSeq: Int = 0,
        goalSide: Side? = null,
        goalPp: Boolean = false,
        period: PeriodLabel? = P2,
        gameId: app.thebox.core.sports.GameId? = ScoreBugFixtures.GAME,
    ): ScoreBugView {
        val v = ScoreBugFixtures.view(
            top = top, bottom = bottom, away = away, home = home, dim = dim, goalSeq = goalSeq, goalSide = goalSide,
            clockSecs = clockSecs, powerPlay = pp, goalPp = goalPp, gameId = gameId,
        )
        return v.copy(away = v.away.copy(sog = awaySog), home = v.home.copy(sog = homeSog), period = period)
    }

    fun pp(side: Side = Side.HOME, kind: PowerPlayKind = PowerPlayKind.ONE_MAN, secs: Int? = 79) = PowerPlay(side, kind, secs)

    /** The first intermission (no power play carried). */
    fun intermission(pp: PowerPlay? = null, dim: Boolean = false, bottom: CentreText? = null) = view(
        top = CentreText.Intermission(PeriodLabel(PeriodKind.REG, 1)), bottom = bottom, pp = pp, dim = dim,
        period = PeriodLabel(PeriodKind.REG, 1),
    )

    fun input(
        corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
        size: ScoreBugSize = ScoreBugSize.STANDARD,
        placing: Boolean = false,
        bar: BottomBar = BottomBar.NONE,
        textTrackOn: Boolean = false,
    ): PlacementInput = ScoreBugFixtures.input(corner, size, placing, bar).copy(style = NhlBugStyle.STACKED, textTrackOn = textTrackOn)

    /** A 1 × 1 stand-in for the glow masks (the wiring tests count bakes; the bounds test bakes for real). */
    fun fakeGlow(): GlowMasks {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        return GlowMasks(b, b, 0f, 0f)
    }

    /**
     * The production hooks but for the test seams: the background work on the test thread ([background]), a recording
     * glow bake ([glow]), the test's [context] (a `MotionDurationScale` for the still path) and logos.
     */
    fun hooks(
        probe: StackedProbe?,
        context: CoroutineContext = EmptyCoroutineContext,
        background: CoroutineContext = EmptyCoroutineContext,
        glow: ((StackedGoalPainter, Float, Float) -> GlowMasks?)? = { _, _, _ -> fakeGlow() },
        logoFetch: LogoFetch? = null,
        glowAllowed: Boolean = true,
    ) = StackedHooks(
        bakeGlow = glow ?: { p, w, h -> p.bakeGlow(w, h) },
        logoFetch = logoFetch,
        context = context,
        background = background,
        probe = probe,
        glowAllowed = { _, _, _, _ -> glowAllowed },
    )
}
