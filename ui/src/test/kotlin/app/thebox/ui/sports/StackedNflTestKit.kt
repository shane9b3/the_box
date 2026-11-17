package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflStackedColours
import app.thebox.core.sports.NflTab
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.NflTimeoutMark
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.StackedColours
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedNflTimeline
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import java.io.File
import kotlin.math.abs

/**
 * NFL Stacked painter-test inputs, **from the real code** (integration pass, 2026-11-15): the views are `ScoreBugView`s
 * as the machine shapes them (`SportsFixtures.nflStackedView`), the layout is [StackedNflTargets.of], the box words are
 * [StackedNflPainter.frame], the colours are [NflStackedColours.pair], every frame is sampled from [StackedNflTimeline]
 * and the takeover from `NflScoreTimeline` under [NflScoreSpec.STACKED]. Nothing here re-states a design number.
 */
internal object StackedNflTestKit {
    /** The 32 NFL abbreviations. */
    val TEAMS = listOf(
        "ARI", "ATL", "BAL", "BUF", "CAR", "CHI", "CIN", "CLE", "DAL", "DEN", "DET", "GB", "HOU", "IND", "JAX", "KC",
        "LAC", "LAR", "LV", "MIA", "MIN", "NE", "NO", "NYG", "NYJ", "PHI", "PIT", "SEA", "SF", "TB", "TEN", "WSH",
    )

    /** Each team's own resolution (`NflStackedColours.of`). */
    val KC: StackedColours get() = NflStackedColours.of("KC")
    val BUF: StackedColours get() = NflStackedColours.of("BUF")

    /** The matchups of §9.2 (away first), as the real table pairs them (after the §6.2 collision rule). */
    val MATCHUPS = listOf("KC-BUF", "IND-NE", "DAL-NYG", "BUF-NE", "DAL-PHI", "LV-PIT", "CIN-DEN")
    val PAIRS: Map<String, StackedPair> get() = MATCHUPS.associateWith { pair(it.substringBefore('-'), it.substringAfter('-')) }

    /** The game's pair: `NflStackedColours.pair`. */
    fun pair(away: String, home: String): StackedPair = NflStackedColours.pair(away, home)

    /** The takeover's numbers: the shipped spec. */
    val SPEC: NflScoreSpec get() = NflScoreSpec.STACKED

    /** The takeover at [t] under [SPEC] through the real `NflScoreTimeline`. */
    fun score(t: Float, first: NflScoreKind, second: NflScoreKind? = null, still: Boolean = false, out: NflScoreFrame = NflScoreFrame()): NflScoreFrame =
        when {
            still -> NflScoreTimeline.sampleStill(SPEC, first, second, t, out)
            second != null -> NflScoreTimeline.samplePair(SPEC, first, second, t, out)
            else -> NflScoreTimeline.sample(SPEC, first, t, out)
        }

    // ------------------------------------------------------------------ views (as the machine shapes them)

    val Q4 = PeriodLabel(PeriodKind.REG, 4)
    fun tab(side: Side, tail: TabTail? = TabTail.Field(FieldZone.OWN, 35), lead: TabLead = TabLead.BALL) = NflTab(side, lead, tail)
    fun down(n: Int, dist: Int?, goal: Boolean = false) = DownText.Down(n, dist, goal)
    fun field(zone: FieldZone, yard: Int) = TabTail.Field(zone, yard)

    /**
     * A view of [away] @ [home] in play (4th, 3:42, 24–17 unless told), KC ball 1st & 10 at its 35, timeouts 3 / 2 of 3;
     * [top] / [bottom] / [dim] override the centre lines (breaks, stale, waiting), as the NFL machine writes them.
     */
    @Suppress("LongParameterList")
    fun view(
        away: String = "KC",
        home: String = "BUF",
        awayScore: Int? = 24,
        homeScore: Int? = 17,
        down: DownText? = down(1, 10),
        tab: NflTab? = tab(Side.AWAY),
        awayTimeouts: Int? = 3,
        homeTimeouts: Int? = 2,
        slots: Int? = 3,
        timeout: NflTimeoutMark? = null,
        flagId: String? = null,
        top: CentreText? = null,
        bottom: CentreText? = CentreText.Clock,
        dim: Boolean = false,
        clock: Int = 222,
        period: PeriodLabel = Q4,
        heldDown: DownText? = null,
        field: app.thebox.core.sports.FieldPos? = null,
    ): ScoreBugView {
        val g = SportsFixtures.nflGame(
            away = SportsFixtures.nflTeam(away, away, away, awayScore),
            home = SportsFixtures.nflTeam(home, home, home, homeScore),
        )
        val v = SportsFixtures.nflStackedView(
            game = g, down = down, tab = tab, awayTimeouts = awayTimeouts, homeTimeouts = homeTimeouts, timeoutSlots = slots,
            timeout = timeout, flagId = flagId,
        )
        return v.copy(
            top = top ?: CentreText.Period(period), bottom = bottom, dim = dim, period = period,
            clockSecs = if (bottom == CentreText.Clock) clock else null,
            nfl = v.nfl?.copy(heldDown = heldDown, field = field),
        )
    }

    fun halftime() = view(top = CentreText.Halftime, bottom = null, down = null, tab = null)
    fun endOf(p: PeriodLabel) = view(top = CentreText.EndOf(p), bottom = null, down = null, tab = null, period = p)
    fun final(kind: PeriodKind = PeriodKind.REG) = view(top = CentreText.Final(kind), bottom = null, down = null, tab = null)
    fun staleInPlay() = view(top = CentreText.NoUpdate, dim = true, down = null, tab = null)
    fun delayedInPlay() = view(top = CentreText.Delayed, dim = true, down = null, tab = null)
    fun staleInBreak() = view(top = CentreText.NoUpdate, bottom = null, dim = true, down = null, tab = null)
    fun delayedInBreak() = view(top = CentreText.Delayed, bottom = null, dim = true, down = null, tab = null)

    /** (NFL YDS) [v] carrying both teams' total yards, as the machine shows a stoppage snapshot's (`NflView`). */
    fun yds(v: ScoreBugView, away: Int, home: Int): ScoreBugView = v.copy(nfl = v.nfl!!.copy(awayYards = away, homeYards = home))

    /** The real layout table. */
    fun targets(v: ScoreBugView): StackedNflTargets = StackedNflTargets.of(v)

    /** The box's words for [v] as the runner gets them: `StackedNflPainter.frame(v, StackedNflTargets.of(v), res)`. */
    fun state(p: StackedNflPainter, v: ScoreBugView, res: Resources): StackedNflFrameState = p.frame(v, targets(v), res)

    // ------------------------------------------------------------------ frames (StackedNflTimeline)

    /** Every layout track of [t] at rest (each track's STILL to its target), into [out]. */
    fun rest(t: StackedNflTargets, out: StackedNflFrame = StackedNflFrame()): StackedNflFrame {
        val from = StackedNflFrame()
        val still = StackedNflTransition.STILL
        StackedNflTimeline.sample(StackedNflTrack.CLOCK, still, 0f, from, t.clockOpen, out)
        StackedNflTimeline.sample(StackedNflTrack.BAR, still, 0f, from, t.bar != null, out)
        StackedNflTimeline.sample(StackedNflTrack.STRIPE_AWAY, still, 0f, from, t.possSide == Side.AWAY, out)
        StackedNflTimeline.sample(StackedNflTrack.STRIPE_HOME, still, 0f, from, t.possSide == Side.HOME, out)
        StackedNflTimeline.sample(StackedNflTrack.BALL_AWAY, still, 0f, from, t.possSide == Side.AWAY, out)
        StackedNflTimeline.sample(StackedNflTrack.BALL_HOME, still, 0f, from, t.possSide == Side.HOME, out)
        val a = t.awayTimeouts ?: 0
        val h = t.homeTimeouts ?: 0
        StackedNflTimeline.samplePips(Side.AWAY, still, a, a, 0f, from, out)
        StackedNflTimeline.samplePips(Side.HOME, still, h, h, 0f, from, out)
        StackedNflTimeline.sampleFlag(0f, true, out)
        // (NFL YDS) The column at rest; closed (every v1 look) it is exactly the frame's defaults.
        StackedNflTimeline.sample(StackedNflTrack.YDS, still, 0f, from, t.ydsOpen, out)
        return out
    }

    /** [f] with [track] at [ms] into [kind] from where [from] rests (CSS semantics: the transition starts from [from]). */
    fun at(f: StackedNflFrame, track: StackedNflTrack, kind: StackedNflTransition, ms: Float, from: StackedNflFrame, to: Boolean = true): StackedNflFrame =
        StackedNflTimeline.sample(track, kind, ms, from, to, f)

    /** The flag sub-track at [ms] since the flag bar's REKEY. */
    fun flag(f: StackedNflFrame, ms: Float): StackedNflFrame = StackedNflTimeline.sampleFlag(ms, false, f)

    /** [side]'s pips at [ms] into the BURN from [fromLit] to [toLit] lit (the pips at rest at [fromLit] before). */
    fun burn(f: StackedNflFrame, side: Side, fromLit: Int, toLit: Int, ms: Float): StackedNflFrame {
        val from = StackedNflFrame()
        StackedNflTimeline.samplePips(side, StackedNflTransition.STILL, fromLit, fromLit, 0f, from, from)
        return StackedNflTimeline.samplePips(side, StackedNflTransition.BURN, fromLit, toLit, ms, from, f)
    }

    // ---- bar keys (for the pinned score labels, never a target)
    fun scoreKey(side: Side, kind: NflScoreKind, points: Int?) = NflBarKey(NflBarKind.SCORE, side, score = kind, points = points)

    // ---- logos: the mock's two PNGs for KC and BUF when present, else a synthetic team-colour oval
    private val ASSETS = mapOf(
        "KC" to "3bbd50edac45d15e8b4888e874011dbf.png",
        "BUF" to "a93d56206deaee9924dace155c9d15af.png",
    )

    /**
     * (Review fix U-N5) The team's logo at [px] when the mock ships its asset (KC, BUF), else null: the painter then
     * draws the real `LogoDisc` fallback, as the app does for a team with no logo (the contact sheets and the hash gate).
     */
    fun logoOrNull(code: String, px: Int): Bitmap? = if (code in ASSETS) logo(code, px) else null

    fun logo(code: String, px: Int): Bitmap {
        ASSETS[code]?.let { name ->
            val f = listOf(File("../design/nfl-stacked/assets/$name"), File("design/nfl-stacked/assets/$name")).firstOrNull { it.isFile }
            val src = f?.let { BitmapFactory.decodeFile(it.path) }
            if (src != null) {
                val k = minOf(px.toFloat() / src.width, px.toFloat() / src.height)
                val w = maxOf(1, Math.round(src.width * k))
                val h = maxOf(1, Math.round(src.height * k))
                return Bitmap.createScaledBitmap(src, w, h, true)
            }
        }
        val colour = NflTeamColours.of(code).base
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val b = px * 0.06f
            p.color = 0xFFF2F2F2.toInt()
            c.drawOval(px * 0.08f, px * 0.12f, px * 0.92f, px * 0.88f, p)
            p.color = colour or (0xFF shl 24)
            c.drawOval(px * 0.08f + b, px * 0.12f + b, px * 0.92f - b, px * 0.88f - b, p)
            p.color = Color.WHITE
            c.drawRect(px * 0.36f, px * 0.45f, px * 0.64f, px * 0.55f, p)
        }
    }

    const val DARK = 0x01010101
    const val GREY = 0xFF808080.toInt()
    const val TRANSPARENT = 0x00000000

    /** The lead's contact-sheet folder for the integration pass (outside the repo); created on demand. */
    val SHEETS = File("/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/nfl-stacked/fix")

    /** (NFL YDS WP-2) The YDS column's contact sheets and strips for the lead and the owner (outside the repo). */
    val YDS_SHEETS = File("/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/yds-shots")

    fun near(a: Int, b: Int, tol: Int) =
        abs(Color.red(a) - Color.red(b)) <= tol && abs(Color.green(a) - Color.green(b)) <= tol && abs(Color.blue(a) - Color.blue(b)) <= tol
}
