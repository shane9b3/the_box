package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColours
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedMock as M
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTargets
import app.thebox.core.sports.StackedTimeline
import app.thebox.core.sports.StackedTrack
import app.thebox.core.sports.StackedTransition
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/**
 * The Stacked painters' hand-built inputs (Stacked §10.2: "frames built by hand … so it does not wait on WP-1"). The
 * colours are the design's own §5.1 table values (EDM, TOR) and the goal colours come from the real `NhlTeamColours`
 * table; the takeover spec is §7.2's STACKED column. None of it reads the commit-0 stubs (`NhlStackedColours`,
 * `StackedTargets.of`, `StackedTimeline`, `GoalSpec.STACKED`).
 */
internal object StackedFixtures {
    /** The 32 NHL abbreviations (2026-27). */
    val TEAMS = listOf(
        "ANA", "BOS", "BUF", "CGY", "CAR", "CHI", "COL", "CBJ", "DAL", "DET", "EDM", "FLA", "LAK", "MIN", "MTL", "NSH",
        "NJD", "NYI", "NYR", "OTT", "PHI", "PIT", "SJS", "SEA", "STL", "TBL", "TOR", "UTA", "VAN", "VGK", "WSH", "WPG",
    )

    /** §5.1's EDM and TOR columns. */
    val EDM = StackedColours(
        stripe = 0xFFFF4C00.toInt(), barBg = 0xFFD33F00.toInt(), barA = 0xFF041E42.toInt(), barB = 0xFFFF9466.toInt(),
        goal = NhlTeamColours.of("EDM"), rowFlashAlpha = 0.40f,
    )
    val TOR = StackedColours(
        stripe = 0xFFFFFFFF.toInt(), barBg = 0xFF001A4B.toInt(), barA = 0xFFFFFFFF.toInt(), barB = 0xFF66799D.toInt(),
        goal = NhlTeamColours.of("TOR"), rowFlashAlpha = 0.50f,
    )
    /** The mock's matchup: EDM (away, top row) at TOR. */
    val EDM_TOR = StackedPair(EDM, TOR)

    /** §7.2's STACKED column (WP-1 puts the same numbers into `GoalSpec.STACKED`). */
    val SPEC = GoalSpec(
        streak1Alpha = 0.55f, streak2Alpha = 0.30f, streakFrom = -90f, streakTo = 450f, flashAlpha = 0.40f,
        badgeMs = 600f, badgeMidScale = 1.1f, badgeMidDeg = 6f, letterMs = 450f, letterDrop = 27f, letterOvershoot = -2.25f,
        pulsePeak = 1.06f, tallySlide = 16.2f, popFromScale = 1.5f, popMs = 900f, rowFlashMs = 1_200f, endMs = 4_800f,
        stillFrameBMs = 1_200f,
    )

    /**
     * The takeover at [t] under [SPEC] through the real `GoalTimeline` (whose shared keyframes are final), with the row
     * flash envelope written by hand (commit 0's sampler writes 0 there): 1 → 0 over 1 200 ms from 3 600, an ease-out
     * stand-in `(1 − p)²`; the still version's frame B writes 1.
     */
    fun goalFrame(t: Float, still: Boolean, out: GoalFrame = GoalFrame()): GoalFrame {
        if (still) GoalTimeline.sampleStill(SPEC, t, out) else GoalTimeline.sample(SPEC, t, out)
        val p = (t - 3_600f) / SPEC.rowFlashMs
        out.rowFlashAlpha = when {
            p < 0f || p >= 1f -> 0f
            still -> 1f
            else -> (1f - p) * (1f - p)
        }
        return out
    }

    /** A sampled layout: each column / the bar open by its fraction, its contents at rest for that fraction. */
    fun frame(sog: Float, clock: Float, bar: Float, stripeAway: Float = 0f, stripeHome: Float = 0f): StackedFrame =
        StackedFrame().apply {
            sogWidth = sog; sogAlpha = sog; sogDx = K.STACKED_SOG_DX * (1f - sog)
            clockWidth = clock; clockAlpha = clock
            barHeight = bar; barClosed = bar <= 0f
            stripeAX = K.STACKED_BAR_STRIPE_TRAVEL * (1f - bar); stripeBX = stripeAX
            barTextAlpha = bar; barTextDx = K.STACKED_BAR_TEXT_DX * (1f - bar)
            this.stripeAway = stripeAway; this.stripeHome = stripeHome
        }

    val TARGETS = StackedTargets(
        sogOpen = false, clockOpen = true, bar = null, barPeriod = null, barSecs = null, ppSide = null,
        period = PeriodLabel(PeriodKind.REG, 2), clockSlot = ClockSlot.Clock, columnDim = false, barDim = false,
    )

    @Suppress("LongParameterList")
    fun body(
        p: StackedPainter,
        away: Int = 2,
        home: Int = 1,
        awaySog: String = "14",
        homeSog: String = "10",
        period: String = "2ND",
        slot: ClockSlot = ClockSlot.Clock,
        slotText: String = "",
        clockSecs: Int = 384,
        dim: Boolean = false,
        awayAbbrev: String = "EDM",
        homeAbbrev: String = "TOR",
    ): StackedFrameState {
        val fit = if (slot == ClockSlot.Clock) null else p.fitSlot(slotText)
        return StackedFrameState(
            awayAbbrev = awayAbbrev, homeAbbrev = homeAbbrev, awayScore = away.toString(), homeScore = home.toString(),
            awaySog = awaySog, homeSog = homeSog,
            periodText = period, periodPx = p.fitColumn(period, M.PERIOD_PX * K.STACKED_SCALE, 0.02f),
            slot = slot, slotText = slotText,
            slotPx = fit?.px ?: p.clockPx,
            clockSecs = clockSecs, columnDim = dim, targets = TARGETS,
            slotBreak = fit?.brk ?: -1, slotDy1 = fit?.dy1 ?: 0f, slotDy2 = fit?.dy2 ?: 0f,
        )
    }

    /** A track's transition [kind] at [t] ms (ending open when [to]), as the runner starts it ([StackedTimeline.sample]). */
    class Move(val track: StackedTrack, val kind: StackedTransition, val t: Float, val to: Boolean = kind != StackedTransition.CLOSE)

    /** Every track at rest: the SOG and clock columns, the bar open or not, the row stripes wide or not. */
    fun rest(sog: Boolean, clock: Boolean, bar: Boolean, stripeAway: Boolean = false, stripeHome: Boolean = false): StackedFrame {
        val out = StackedFrame()
        val x = StackedFrame()
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.STILL, 0f, x, sog, out)
        StackedTimeline.sample(StackedTrack.CLOCK, StackedTransition.STILL, 0f, x, clock, out)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.STILL, 0f, x, bar, out)
        StackedTimeline.sample(StackedTrack.STRIPE_AWAY, StackedTransition.STILL, 0f, x, stripeAway, out)
        StackedTimeline.sample(StackedTrack.STRIPE_HOME, StackedTransition.STILL, 0f, x, stripeHome, out)
        return out
    }

    /**
     * (Fix pass 2026-11-11, review U S4) A mid-transition frame the timeline really produces: [from] at rest, then each of
     * [moves] sampled at its named instant through the real `StackedTimeline` (the other tracks stay at [from]).
     */
    fun sampled(from: StackedFrame, vararg moves: Move): StackedFrame {
        val out = StackedFrame().apply { copyFrom(from) }
        for (m in moves) StackedTimeline.sample(m.track, m.kind, m.t, from, m.to, out)
        return out
    }
}

/**
 * Stacked §10.2 `StackedScreenshotTest` (WP-2): the Stacked box drawn through the real [StackedPainter.drawFrame] (and
 * through it [StackedGoalPainter] and [NineSlice]) with the bundled fonts, from hand-built frames ([StackedFixtures]):
 * every §8 look the painter can be handed, a few mid-transition frames, the bar above the box (bottom corners), place
 * mode, and the takeover at 0 / 216 / 1 000 / 1 800 / 3 600 / 4 200 ms plus the still version's frames A and B, over
 * the dark gradient, white ice and 50 % grey, at Standard and Large.
 *
 * Every frame is written under `ui/build/score-bug-stacked/` with contact sheets, for review by eye against the mock-up
 * (`design/nhl-stacked/Stacked.dc.html` at × 0.45); they are not goldens. The asserted numbers are §10.2's (white on the
 * row ≥ 7 : 1, bar text ≥ 4.5, SOG label ≥ 4.5, from the drawn pixels) plus the §6 drawing rules: the nine-slice shadow
 * matches a direct blur, the glow is ≤ 2 % at the screen edge, nothing leaves the layer (footprint + 2 M), round corners,
 * the bar stays visible through the takeover, and the end of the takeover is pixel-equal to the plain box.
 *
 * Logos are synthetic stand-ins (a team-colour oval with a light border), loaded at the takeover size as WP-3 loads them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val out = File("build/score-bug-stacked").apply { mkdirs() }

    private inner class Rig(val large: Boolean) {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val painter = StackedPainter(s, large, bold, italic)
        val goal = StackedGoalPainter(s, large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC)
        val shadow: NineSlice = painter.bakeShadow()!!
        val glows = HashMap<Pair<Int, Int>, GlowMasks>()
        val logoPx = Math.round(K.STACKED_GOAL_LOGO * s)
        /** The layer: footprint (`STACKED_W_MAX` × top + bar) + 2 M, device px. */
        val layerW = Math.round((K.STACKED_W_MAX * s) + 2 * m)
        val layerH = Math.round(((K.STACKED_H + K.STACKED_BAR_H) * s) + 2 * m)
        val logos: FollowLogos = FollowLogos({ url, w, h -> logoBitmap(if (url == "EDM") 0xFFFF4C00.toInt() else 0xFF00205B.toInt(), w, h) }, CoroutineScope(Dispatchers.Unconfined))
            .apply { start("EDM", "TOR", logoPx, logoPx) }

        fun glow(w: Float, h: Float): GlowMasks = glows.getOrPut(Math.round(w) to Math.round(h)) { goal.bakeGlow(w, h)!! }
    }

    private val std by lazy { Rig(false) }
    private val big by lazy { Rig(true) }
    private fun rig(large: Boolean) = if (large) big else std

    private fun logoBitmap(colour: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val b = w * 0.06f
        p.color = 0xFFF2F2F2.toInt()
        c.drawOval(w * 0.1f, 1f, w * 0.9f, h - 1f, p)
        p.color = colour
        c.drawOval(w * 0.1f + b, 1f + b, w * 0.9f - b, h - 1f - b, p)
        p.color = Color.WHITE
        c.drawRect(w * 0.36f, h * 0.44f, w * 0.64f, h * 0.56f, p)
    }

    /** One look: the body, the layout, the bar's content (and the previous one during a change / swap). */
    private class Look(
        val name: String,
        val body: (StackedPainter) -> StackedFrameState,
        val f: StackedFrame,
        val bar: Triple<BarKind, Side?, Int>? = null,
        val barPeriod: PeriodLabel? = null,
        val barDim: Boolean = false,
        val prev: Triple<BarKind, Side?, Int>? = null,
        val prevPeriod: PeriodLabel? = null,
        val placing: Boolean = false,
        val logos: Boolean = true,
        /** A frame from the middle of a transition. */
        val moving: Boolean = false,
    )

    private val p2 = PeriodLabel(PeriodKind.REG, 2)
    private val noSecs = BarContent.NO_SECS

    private val looks: List<Look> = listOf(
        Look("play", { StackedFixtures.body(it) }, StackedFixtures.frame(0f, 1f, 0f)),
        Look("pp-edm", { StackedFixtures.body(it) }, StackedFixtures.frame(1f, 1f, 1f, stripeAway = 1f), Triple(BarKind.POWER_PLAY, Side.AWAY, 97)),
        Look("pp-tor-5on3", { StackedFixtures.body(it, clockSecs = 612) }, StackedFixtures.frame(1f, 1f, 1f, stripeHome = 1f), Triple(BarKind.TWO_MAN, Side.HOME, 48)),
        Look("pp-goal-edm", { StackedFixtures.body(it, away = 3) }, StackedFixtures.frame(1f, 1f, 1f, stripeAway = 1f), Triple(BarKind.PP_GOAL, Side.AWAY, noSecs)),
        Look("pp-no-sog", { StackedFixtures.body(it, awaySog = "", homeSog = "") }, StackedFixtures.frame(0f, 1f, 1f, stripeAway = 1f), Triple(BarKind.POWER_PLAY, Side.AWAY, 19 * 60 + 59)),
        Look("intermission", { StackedFixtures.body(it) }, StackedFixtures.frame(1f, 0f, 1f), Triple(BarKind.INTERMISSION, null, noSecs), p2),
        Look("pp-into-int", { StackedFixtures.body(it, period = "1ST", slot = ClockSlot.Intermission, slotText = res.getString(R.string.sports_stacked_int)) }, StackedFixtures.frame(1f, 1f, 1f, stripeAway = 1f), Triple(BarKind.POWER_PLAY, Side.AWAY, 72)),
        Look("end-of-period", { StackedFixtures.body(it, period = "1ST", slot = ClockSlot.End, slotText = res.getString(R.string.sports_stacked_end)) }, StackedFixtures.frame(0f, 1f, 0f)),
        Look("stale-play", { StackedFixtures.body(it, slot = ClockSlot.NoUpdate, slotText = res.getString(R.string.sports_no_update), dim = true) }, StackedFixtures.frame(0f, 1f, 0f)),
        Look("delayed-play", { StackedFixtures.body(it, slot = ClockSlot.Delayed, slotText = res.getString(R.string.sports_delayed), dim = true) }, StackedFixtures.frame(0f, 1f, 0f)),
        Look("stale-int", { StackedFixtures.body(it) }, StackedFixtures.frame(1f, 0f, 1f), Triple(BarKind.NO_UPDATE, null, noSecs), barDim = true),
        Look("shootout", { StackedFixtures.body(it, away = 3, home = 3, period = "SO") }, StackedFixtures.frame(1f, 0f, 1f), Triple(BarKind.SHOOTOUT, null, noSecs)),
        Look("final-so", { StackedFixtures.body(it, away = 4, home = 3) }, StackedFixtures.frame(1f, 0f, 1f), Triple(BarKind.FINAL, null, noSecs), PeriodLabel(PeriodKind.SO, 1)),
        Look("ot", { StackedFixtures.body(it, away = 10, home = 10, period = "OT", clockSecs = 252, awaySog = "38", homeSog = "41") }, StackedFixtures.frame(0f, 1f, 0f)),
        // (Fix pass 2026-11-11, review U S4) Mid-transition cells come from the real timeline at named instants, so the
        // sheets never show a state the runner cannot draw.
        Look("pp-open-t300", { StackedFixtures.body(it) },
            StackedFixtures.sampled(StackedFixtures.rest(sog = false, clock = true, bar = false),
                StackedFixtures.Move(StackedTrack.SOG, StackedTransition.OPEN, 300f), StackedFixtures.Move(StackedTrack.BAR, StackedTransition.OPEN, 300f),
                StackedFixtures.Move(StackedTrack.STRIPE_AWAY, StackedTransition.OPEN, 300f)),
            Triple(BarKind.POWER_PLAY, Side.AWAY, 97), moving = true),
        Look("pp-open-t900", { StackedFixtures.body(it) },
            StackedFixtures.sampled(StackedFixtures.rest(sog = false, clock = true, bar = false),
                StackedFixtures.Move(StackedTrack.SOG, StackedTransition.OPEN, 900f), StackedFixtures.Move(StackedTrack.BAR, StackedTransition.OPEN, 900f),
                StackedFixtures.Move(StackedTrack.STRIPE_AWAY, StackedTransition.OPEN, 900f)),
            Triple(BarKind.POWER_PLAY, Side.AWAY, 97), moving = true),
        Look("pp-close-t400", { StackedFixtures.body(it) },
            StackedFixtures.sampled(StackedFixtures.rest(sog = true, clock = true, bar = true, stripeAway = true),
                StackedFixtures.Move(StackedTrack.SOG, StackedTransition.CLOSE, 400f), StackedFixtures.Move(StackedTrack.BAR, StackedTransition.CLOSE, 400f),
                StackedFixtures.Move(StackedTrack.STRIPE_AWAY, StackedTransition.CLOSE, 400f)),
            Triple(BarKind.POWER_PLAY, Side.AWAY, 12), moving = true),
        Look("int-to-play-t600", { StackedFixtures.body(it) },
            StackedFixtures.sampled(StackedFixtures.rest(sog = true, clock = false, bar = true),
                StackedFixtures.Move(StackedTrack.CLOCK, StackedTransition.OPEN, 600f), StackedFixtures.Move(StackedTrack.SOG, StackedTransition.CLOSE, 600f),
                StackedFixtures.Move(StackedTrack.BAR, StackedTransition.CLOSE, 600f)),
            Triple(BarKind.INTERMISSION, null, noSecs), p2, moving = true),
        Look("change-pp-to-int-t100", { StackedFixtures.body(it) },
            StackedFixtures.sampled(StackedFixtures.rest(sog = true, clock = true, bar = true, stripeAway = true),
                StackedFixtures.Move(StackedTrack.BAR, StackedTransition.CHANGE, 100f), StackedFixtures.Move(StackedTrack.CLOCK, StackedTransition.CLOSE, 100f),
                StackedFixtures.Move(StackedTrack.STRIPE_AWAY, StackedTransition.CLOSE, 100f)),
            Triple(BarKind.INTERMISSION, null, noSecs), p2, prev = Triple(BarKind.POWER_PLAY, Side.AWAY, 12), moving = true),
        Look("place", { StackedFixtures.body(it, away = 0, home = 0, awaySog = "", homeSog = "", period = "", clockSecs = 0, awayAbbrev = "AWY", homeAbbrev = "HME") },
            StackedFixtures.frame(0f, 1f, 0f), placing = true, logos = false),
    )

    /**
     * A rendered frame: [ox] / [oy] place the painter's origin (the rows' left edge, the top part's top) in [bmp]; [bl] is
     * the top part's left edge from that origin (0, or `rowsW − boxW` mirrored), device px.
     */
    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float, val bl: Float = 0f) {
        /** The pixel at box coordinates (x, y) (the top part's top-left is 0, 0, unmirrored). */
        fun px(x: Float, y: Float): Int = bmp.getPixel((x + ox).toInt(), (y + oy).toInt())
    }

    private fun bar(r: Rig, spec: Triple<BarKind, Side?, Int>?, period: PeriodLabel?, dim: Boolean, pair: StackedPair): BarContent? =
        spec?.let { (kind, side, secs) ->
            BarContent().also {
                it.kind = kind; it.side = side; it.secs = secs; it.period = period; it.dim = dim
                r.painter.fillBar(it, pair, "EDM", "TOR", res)
            }
        }

    /**
     * [look] on [bg] (DARK, GREY, TRANSPARENT or a colour) in the layer. [t] non-null = a takeover by [scorer] at [t]
     * ([still] = the still version); [shadow] / [glowOn] as the runner would hand them; [up] = the bar above the box.
     */
    private fun render(
        look: Look,
        large: Boolean,
        bg: Int = DARK,
        t: Float? = null,
        still: Boolean = false,
        scorer: Side = Side.AWAY,
        up: Boolean = false,
        shadow: Boolean = true,
        glowOn: Boolean = true,
        pair: StackedPair = StackedFixtures.EDM_TOR,
        mirrored: Boolean = false,
    ): Shot {
        val r = rig(large)
        val bmp = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (bg) {
            DARK -> c.drawRect(0f, 0f, r.layerW.toFloat(), r.layerH.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, r.layerW.toFloat(), r.layerH.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
            })
            TRANSPARENT -> Unit
            else -> c.drawColor(bg)
        }
        val f = look.f
        val a = StackedDrawArgs()
        a.body = look.body(r.painter)
        a.colours = pair
        a.f = f
        a.bar = bar(r, look.bar, look.barPeriod, look.barDim, pair)
        a.barPrev = bar(r, look.prev, look.prevPeriod, false, pair)
        a.up = up
        a.logos = if (look.logos) r.logos else null
        a.logoWaited = true
        a.placing = look.placing
        a.shadow = if (shadow) r.shadow else null
        a.still = still
        a.mirrored = mirrored
        if (t != null) {
            val body = a.body!!
            val goalColours = if (scorer == Side.AWAY) pair.away.goal else pair.home.goal
            val badge = (if (scorer == Side.AWAY) r.logos.away else r.logos.home).image?.asAndroidBitmap()
            r.goal.begin(scorer, goalColours, badge, body.awayAbbrev, body.homeAbbrev, body.awayScore.toInt(), body.homeScore.toInt(), f.boxW)
            a.goal = r.goal
            a.g = StackedFixtures.goalFrame(t, still)
            a.glow = r.glow(f.boxW * r.s, (K.STACKED_H + if (f.barClosed) 0f else f.barH) * r.s)
            a.glowOn = glowOn
            a.t = t
        }
        // Mirrored (a right-hand corner): the rows hug the footprint's right edge, as the runner places them.
        val ox = r.m + if (mirrored) (K.STACKED_W_MAX - K.STACKED_ROWS_W) * r.s else 0f
        val oy = r.m + if (up) K.STACKED_BAR_H * r.s else 0f
        c.translate(ox, oy)
        r.painter.drawFrame(c, a)
        return Shot(bmp, ox, oy, if (mirrored) (K.STACKED_ROWS_W - f.boxW) * r.s else 0f)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(name: String, cells: List<List<Bitmap>>) {
        val cw = cells.flatten().maxOf { it.width }
        val ch = cells.flatten().maxOf { it.height }
        val cols = cells.maxOf { it.size }
        val b = Bitmap.createBitmap(cols * cw, cells.size * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { row, list -> list.forEachIndexed { col, bmp -> c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null) } }
        save(name, b)
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun near(expected: Int, actual: Int, tol: Int) =
        abs(Color.red(expected) - Color.red(actual)) <= tol && abs(Color.green(expected) - Color.green(actual)) <= tol &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tol

    private fun hex(c: Int) = "#" + Integer.toHexString(c).padStart(8, '0').uppercase()
    private fun sz(large: Boolean) = if (large) "large" else "std"
    private val backgrounds = listOf("dark" to DARK, "ice" to Color.WHITE, "grey" to GREY)
    private val times = listOf(0, 216, 1_000, 1_800, 3_600, 4_200)

    // ------------------------------------------------------------------ the frames and contact sheets

    @Test
    fun everyLookAtBothSizesOverEveryBackgroundAndTheContactSheets() {
        for (large in listOf(false, true)) for ((bgName, bg) in backgrounds) {
            val shots = looks.map { look ->
                render(look, large, bg).bmp.also { save("stacked-${look.name}-${sz(large)}-$bgName", it) }
            }
            val upShots = looks.filter { it.bar != null }.take(3).map { look ->
                render(look, large, bg, up = true).bmp.also { save("stacked-${look.name}-up-${sz(large)}-$bgName", it) }
            }
            sheet("stacked-contact-sheet-${sz(large)}-$bgName", (shots + upShots).chunked(4))
        }
        assertTrue(File(out, "stacked-contact-sheet-std-dark.png").length() > 10_000)
    }

    @Test
    fun theTakeoverAtTheDesignTimesAndTheStillFrames() {
        val bases = listOf(looks.first { it.name == "pp-edm" }, looks.first { it.name == "play" }, looks.first { it.name == "intermission" })
        for (large in listOf(false, true)) for ((bgName, bg) in backgrounds) {
            val rows = bases.map { look ->
                val animated = times.map { t ->
                    render(look, large, bg, t = t.toFloat()).bmp.also { save("stacked-goal-${look.name}-${sz(large)}-$bgName-t$t", it) }
                }
                val stillA = render(look, large, bg, t = 1_000f, still = true).bmp.also { save("stacked-goal-${look.name}-${sz(large)}-$bgName-still-a", it) }
                val stillB = render(look, large, bg, t = 4_200f, still = true).bmp.also { save("stacked-goal-${look.name}-${sz(large)}-$bgName-still-b", it) }
                animated + stillA + stillB
            }
            sheet("stacked-goal-contact-sheet-${sz(large)}-$bgName", rows)
            // The home scorer (TOR: navy panel, white lead) on the PP box, and the bar above the box.
            save("stacked-goal-tor-${sz(large)}-$bgName-t1800", render(bases[0], large, bg, t = 1_800f, scorer = Side.HOME).bmp)
            save("stacked-goal-pp-edm-up-${sz(large)}-$bgName-t1800", render(bases[0], large, bg, t = 1_800f, up = true).bmp)
        }
        assertTrue(File(out, "stacked-goal-contact-sheet-std-dark.png").length() > 10_000)
    }

    // ------------------------------------------------------------------ geometry probes (review U S3)

    /**
     * The ink of one colour in a region of [shot] (box coordinates, device px): every pixel at least half-way from [bg]
     * to [ink] on [channel] (red 16, green 8, blue 0), as a box of whole-pixel edges; null when there is none.
     */
    private fun inkBox(shot: Shot, x0: Float, y0: Float, x1: Float, y1: Float, bg: Int, ink: Int, channel: Int = 8): android.graphics.RectF? {
        val b = (bg shr channel) and 0xFF
        val k = (ink shr channel) and 0xFF
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var bt = Int.MIN_VALUE
        for (py in Math.floor((y0 + shot.oy).toDouble()).toInt() until Math.ceil((y1 + shot.oy).toDouble()).toInt()) {
            for (px in Math.floor((x0 + shot.ox).toDouble()).toInt() until Math.ceil((x1 + shot.ox).toDouble()).toInt()) {
                val v = (shot.bmp.getPixel(px, py) shr channel) and 0xFF
                if ((v - b).toFloat() / (k - b) >= 0.5f) { l = minOf(l, px); r = maxOf(r, px); t = minOf(t, py); bt = maxOf(bt, py) }
            }
        }
        if (l == Int.MAX_VALUE) return null
        return android.graphics.RectF(l - shot.ox, t - shot.oy, r + 1 - shot.ox, bt + 1 - shot.oy)
    }

    /**
     * (Fix pass 2026-11-11, review U S3) The screenshot test's regression pin: the drawn ink of a dozen things per size,
     * found by scanning the rendered pixels, against §4.2's table (mock × 0.45) within ±1 device px. Each text's expected
     * ink is its §4.2 origin plus the glyphs' own outline bounds (the font's side bearings), so a moved code, a resized
     * clock, a lost stripe or a shifted bar fails here. No image goldens.
     */
    @Test
    fun theDrawnGeometryMatchesTheDesignTable() {
        // ±1 px for shapes; text gets ±1.5: the renderer snaps each glyph run's origin to a whole pixel (no subpixel text)
        // and boosts edge coverage (text gamma), against the outlines' exact float bounds.
        val tol = 1f
        val textTol = 1.5f
        for (large in listOf(false, true)) {
            val r = rig(large)
            val s = r.s
            val p = r.painter
            val play = render(looks.first { it.name == "play" }, large, DARK, shadow = false)
            val pp = render(looks.first { it.name == "pp-edm" }, large, DARK, shadow = false)
            fun check(what: String, expected: Float, actual: Float?, shape: Boolean = false) {
                assertTrue("${sz(large)} $what: nothing drawn", actual != null)
                println("probe ${sz(large)} $what: expected %.2f, drawn %.2f".format(expected, actual))
                assertEquals("${sz(large)} $what", expected, actual!!, if (shape) tol else textTol)
            }
            val row = K.STACKED_ROW_BG
            val col = K.STACKED_COL_BG
            val white = Color.WHITE
            val rowH = K.STACKED_H / 2f * s

            // 1–2. The away code's left ink edge: 3.6 + 9.9 + 50.4 + 13.5 = 77.4; 80.1 on the power play's wide stripe.
            val codeInk = p.inkBounds(StackedPainter.Ink.CODE, "EDM", 0f, 0f)
            check("code left (77.4)", 77.4f * s + codeInk.left, inkBox(play, 66f * s, 0f, 170f * s, rowH, row, white)?.left)
            check("code left on the PP (80.1)", 80.1f * s + codeInk.left, inkBox(pp, 66f * s, 0f, 170f * s, rowH, row, white)?.left)
            // 3–4. The scores' right ink edge: 243 − 15.3 = 227.7, both rows.
            check("away score right (227.7)", p.inkBounds(StackedPainter.Ink.SCORE, "2", 227.7f * s, 0f).right,
                inkBox(play, 175f * s, 0f, 243f * s, rowH, row, white)?.right)
            check("home score right (227.7)", p.inkBounds(StackedPainter.Ink.SCORE, "1", 227.7f * s, 0f).right,
                inkBox(play, 175f * s, rowH, 243f * s, 2 * rowH, row, white)?.right)
            // 5–7. The SOG digits centred at x 272.25 on each row (y 27 / 81), the label on the row line (y 54).
            val sogCx = (K.STACKED_ROWS_W + K.STACKED_SOG_W / 2f) * s
            val sogTop = inkBox(pp, (K.STACKED_ROWS_W + 2f) * s, 0f, (K.STACKED_ROWS_W + K.STACKED_SOG_W - 2f) * s, rowH - 10f * s, col, white)
            check("SOG digits x centre (272.25)", p.inkBounds(StackedPainter.Ink.SOG, "14", sogCx, 0f).centerX(), sogTop?.centerX())
            check("away SOG y centre (27)", 27f * s, sogTop?.centerY())
            val sogBottom = inkBox(pp, (K.STACKED_ROWS_W + 2f) * s, rowH + 10f * s, (K.STACKED_ROWS_W + K.STACKED_SOG_W - 2f) * s, 2 * rowH, col, white)
            check("home SOG y centre (81)", 81f * s, sogBottom?.centerY())
            val label = inkBox(pp, (K.STACKED_ROWS_W + 2f) * s, rowH - 9f * s, (K.STACKED_ROWS_W + K.STACKED_SOG_W - 2f) * s, rowH + 9f * s, col, K.STACKED_SOG_LABEL)
            check("SOG label y centre (54)", 54f * s, label?.centerY())
            check("SOG label x centre (272.25)", p.inkBounds(StackedPainter.Ink.SOG_LABEL, "SOG", sogCx, 0f).centerX(), label?.centerX())
            // 8. The period's baseline: the 50.4 slot less its 6.3 padding, less the font's descent (≈ 38.7).
            val x0 = K.STACKED_ROWS_W * s // the clock column in play (SOG closed)
            val cx = x0 + K.STACKED_CLOCK_W / 2f * s
            val periodBottom = (50.4f - 6.3f) * s
            val fm = Paint().apply { typeface = bold; textSize = M.PERIOD_PX * K.STACKED_SCALE * s }.fontMetrics
            val periodBaseline = periodBottom - fm.descent
            assertEquals("${sz(large)} the period's baseline (38.7)", 38.7f * s, periodBaseline, 0.6f * s)
            val period = inkBox(play, x0 + 2f * s, 0f, x0 + K.STACKED_CLOCK_W * s - 2f * s, 48f * s, col, white)
            check("period ink bottom", p.inkBounds(StackedPainter.Ink.PERIOD, "2ND", cx, periodBaseline).bottom, period?.bottom)
            check("period x centre", p.inkBounds(StackedPainter.Ink.PERIOD, "2ND", cx, periodBaseline).centerX(), period?.centerX())
            // 9. The rule: y 50.4, x 243 + 11.7 = 254.7 to 333 − 11.7 = 321.3.
            val rule = inkBox(play, x0 + 2f * s, 46f * s, x0 + K.STACKED_CLOCK_W * s - 2f * s, 56f * s, col, K.STACKED_RULE)
            check("rule left (254.7)", 254.7f * s, rule?.left, shape = true)
            check("rule right (321.3)", 321.3f * s, rule?.right, shape = true)
            check("rule top (50.4)", 50.4f * s, rule?.top, shape = true)
            // 10. The clock "6:24": tabular, centred in the column below the rule, inside the 5-px air (B1).
            val line = (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f)
            val clockCy = (50.4f * s + line + K.STACKED_H * s) / 2f
            val want = p.clockInk(384).apply { offset(cx, clockCy) }
            val clock = inkBox(play, x0 + 2f * s, 56f * s, x0 + K.STACKED_CLOCK_W * s - 2f * s, K.STACKED_H * s - 1f, col, white)
            check("clock ink left", want.left, clock?.left)
            check("clock ink right", want.right, clock?.right)
            check("clock ink top", want.top, clock?.top)
            check("clock ink bottom", want.bottom, clock?.bottom)
            assertTrue("${sz(large)} the clock keeps its air: ${clock!!.left}..${clock.right}", clock.left >= x0 + 5f * s - tol && clock.right <= x0 + 85f * s + tol)
            // 11–12. The bar at rest: stripes A / B at 20.7 / 37.8 (11.7 wide) on its centre line, the words at 63.
            val barCy = (K.STACKED_H + K.STACKED_BAR_H / 2f) * s
            val edm = StackedFixtures.EDM
            val stripeA = inkBox(pp, 3f * s, barCy - 0.5f, 60f * s, barCy + 0.5f, edm.barBg, edm.barA, channel = 16)
            check("bar stripe A left (20.7)", 20.7f * s, stripeA?.left, shape = true)
            check("bar stripe A right (32.4)", 32.4f * s, stripeA?.right, shape = true)
            val stripeB = inkBox(pp, 34f * s, barCy - 0.5f, 60f * s, barCy + 0.5f, edm.barBg, edm.barB, channel = 0)
            check("bar stripe B left (37.8)", 37.8f * s, stripeB?.left, shape = true)
            check("bar stripe B right (49.5)", 49.5f * s, stripeB?.right, shape = true)
            val words = inkBox(pp, 57f * s, barCy - 9f * s, 250f * s, barCy + 9f * s, edm.barBg, white, channel = 0)
            check("bar words left (63)", p.inkBounds(StackedPainter.Ink.BAR, "EDM", 63f * s, 0f).left, words?.left)
        }
    }

    // ------------------------------------------------------------------ §10.2's contrast assertions, from the pixels

    @Test
    fun whiteOnTheRowIsAtLeastSevenToOne() {
        for (large in listOf(false, true)) for ((_, bg) in backgrounds) {
            val r = rig(large)
            val shot = render(looks[0], large, bg)
            // An empty spot of the away row: right of the score, left of the row's end.
            val row = shot.px(K.STACKED_ROWS_W * r.s - 4f * r.s, 4f * r.s)
            assertTrue("row ${hex(row)} vs #12284F", near(K.STACKED_ROW_BG, row, 2))
            val cr = contrast(Color.WHITE, row)
            assertTrue("white on the row: $cr", cr >= 7.0)
            // And the code's ink reaches white.
            var white = 0
            for (y in (20 * r.s).toInt()..(34 * r.s).toInt()) for (x in (80 * r.s).toInt()..(130 * r.s).toInt()) {
                if (near(Color.WHITE, shot.px(x.toFloat(), y.toFloat()), 6)) white++
            }
            assertTrue("white code pixels: $white", white > 20)
        }
    }

    @Test
    fun barTextIsAtLeastFourAndAHalfToOneOnEveryBar() {
        val bars = looks.filter { it.bar != null && !it.moving }
        for (large in listOf(false, true)) for (look in bars) for (up in listOf(false, true)) {
            val r = rig(large)
            val shot = render(look, large, DARK, up = up)
            val y = if (up) -K.STACKED_BAR_H * r.s / 2f else (K.STACKED_H + K.STACKED_BAR_H / 2f) * r.s
            // The bar's background at its right end, clear of stripes and words.
            val bg = shot.px(look.f.boxW * r.s - 4f * r.s, y)
            val cr = contrast(Color.WHITE, bg)
            println("bar ${look.name} ${sz(large)}${if (up) " up" else ""}: ${hex(bg)} white %.2f".format(cr))
            assertTrue("${look.name}: white on ${hex(bg)} is $cr", cr >= 4.5)
            var white = 0
            for (yy in (y - 8f * r.s).toInt()..(y + 8f * r.s).toInt()) for (x in (64 * r.s).toInt()..(150 * r.s).toInt()) {
                if (Color.red(shot.px(x.toFloat(), yy.toFloat())) > 235 && Color.green(shot.px(x.toFloat(), yy.toFloat())) > 235) white++
            }
            if (!look.barDim && look.f.barTextAlpha >= 1f) assertTrue("${look.name}: white word pixels $white", white > 20)
        }
    }

    @Test
    fun theSogLabelIsAtLeastFourAndAHalfToOne() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val shot = render(looks.first { it.name == "pp-edm" }, large)
            val col = shot.px((K.STACKED_ROWS_W + 3f) * r.s, 4f * r.s)
            assertTrue("column ${hex(col)} vs #15191F", near(K.STACKED_COL_BG, col, 2))
            val cr = contrast(K.STACKED_SOG_LABEL, col)
            assertTrue("SOG label: $cr", cr >= 4.5)
            // The label's ink is there, in its colour, on the row line.
            var label = 0
            val cx = (K.STACKED_ROWS_W + K.STACKED_SOG_W / 2f) * r.s
            for (y in ((K.STACKED_H / 2f - 6f) * r.s).toInt()..((K.STACKED_H / 2f + 6f) * r.s).toInt()) for (x in (cx - 18 * r.s).toInt()..(cx + 18 * r.s).toInt()) {
                if (near(K.STACKED_SOG_LABEL, shot.px(x.toFloat(), y.toFloat()), 10)) label++
            }
            assertTrue("label pixels $label", label > 10)
        }
    }

    // ------------------------------------------------------------------ §6: drawing rules

    /**
     * (§6, B4) The stretched nine-slice is the blurred shadow of the box at every size the box goes through (3 widths ×
     * bar open / closed), outside the box (its centre is skipped):
     * - across the middle of each straight side, where the slices are stretched, it is the analytic Gaussian edge
     *   `½·erfc(d / σ√2)` within [EDGE_TOL] / 255 (the "exact profile" claim);
     * - everywhere, it is within [DIRECT_TOL] / 255 of a direct `BlurMaskFilter` of the whole box (Skia's own round-rect
     *   blur takes an approximate path near the corners, so this bound is looser; the measured worst is printed).
     */
    @Test
    fun theNineSliceShadowMatchesTheBlurAtEveryBoxSize() {
        val worsts = ArrayList<Int>()
        val edges = ArrayList<Int>()
        for (large in listOf(false, true)) {
            val r = rig(large)
            val src = r.shadow.sourceSide
            val sigma = K.STACKED_SHADOW_SIGMA * r.s
            val dy = K.STACKED_SHADOW_DY * r.s
            assertTrue("source $src ≥ 6σ + 1 (${6 * sigma + 1})", src >= 6 * sigma + 1)
            for (w in listOf(301.5f, 333f, 391.5f)) for (barOpen in listOf(false, true)) {
                val bw = w * r.s
                val bh = (K.STACKED_H + if (barOpen) K.STACKED_BAR_H else 0f) * r.s
                val sliced = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
                Canvas(sliced).apply { translate(r.m, r.m) }.let { r.shadow.draw(it, 0f, 0f, bw, bh, paint) }
                val direct = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
                Canvas(direct).apply {
                    translate(r.m, r.m)
                    val rad = M.BOX_RADIUS * K.STACKED_SCALE * r.s
                    drawRoundRect(0f, dy, bw, bh + dy, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
                    })
                }
                // The analytic edge across the middle of each side, sampled at pixel centres; d = the distance past the
                // shadow shape's edge (the box moved down by dy), positive outside.
                fun alphaAt(b: Bitmap, px: Int, py: Int) = Color.alpha(b.getPixel(px + r.m.toInt(), py + r.m.toInt()))
                fun want(d: Float) = Math.round(255.0 * 0.5 * erfc(d / (sigma * Math.sqrt(2.0)))).toInt()
                val mx = (bw / 2f).toInt()
                val my = (bh / 2f + dy).toInt()
                // (x, y, d) along the four mid-side lines.
                val probes = ArrayList<Triple<Int, Int, Float>>()
                for (y in -30..-1) probes += Triple(mx, y, dy - (y + 0.5f))
                for (y in Math.ceil(bh.toDouble()).toInt()..(bh + 30).toInt()) probes += Triple(mx, y, y + 0.5f - (bh + dy))
                for (x in -30..-1) probes += Triple(x, my, -(x + 0.5f))
                for (x in Math.ceil(bw.toDouble()).toInt()..(bw + 30).toInt()) probes += Triple(x, my, x + 0.5f - bw)
                var edge = 0
                var skia = 0
                var sideVsDirect = 0
                for ((x, y, d) in probes) {
                    edge = maxOf(edge, abs(alphaAt(sliced, x, y) - want(d)))
                    skia = maxOf(skia, abs(alphaAt(direct, x, y) - want(d)))
                    sideVsDirect = maxOf(sideVsDirect, abs(alphaAt(sliced, x, y) - alphaAt(direct, x, y)))
                }
                println("nine-slice ${sz(large)} $w × ${if (barOpen) 141 else 108} mid-sides: vs erfc Δα $edge (Skia's direct blur vs erfc $skia), vs direct $sideVsDirect")
                edges += edge
                var worst = 0
                var at = ""
                for (y in 0 until r.layerH) for (x in 0 until r.layerW) {
                    val bx = x - r.m
                    val by = y - r.m
                    if (bx >= 0f && bx < bw && by >= 0f && by < bh) continue // the centre is skipped: the box covers it
                    val d = abs(Color.alpha(sliced.getPixel(x, y)) - Color.alpha(direct.getPixel(x, y)))
                    if (d > worst) { worst = d; at = "($bx, $by) sliced ${Color.alpha(sliced.getPixel(x, y))} direct ${Color.alpha(direct.getPixel(x, y))}" }
                }
                println("nine-slice ${sz(large)} $w × ${if (barOpen) 141 else 108}: edges vs erfc Δα $edge; vs a direct blur worst Δα $worst at $at")
                worsts += worst
            }
            assertEquals("ALPHA_8", Bitmap.Config.ALPHA_8, r.shadow.bitmap!!.config)
        }
        assertTrue("edges vs erfc Δα $edges", edges.all { it <= EDGE_TOL })
        assertTrue("vs a direct blur Δα $worsts", worsts.all { it <= DIRECT_TOL })
    }

    /** The complementary error function (Abramowitz & Stegun 7.1.26, |ε| < 1.5e-7). */
    private fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val y = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * Math.exp(-z * z)
        return if (x >= 0) y else 2.0 - y
    }

    /** (§6 N6) At the glow's peak, 56 px past the box (the screen-edge inset) the glow is ≤ 2 %, at every pinned box. */
    @Test
    fun theGlowFadesBeforeTheScreenEdge() {
        val g = StackedFixtures.goalFrame(1_800f, still = false)
        for (large in listOf(false, true)) for (look in listOf("pp-edm", "play", "intermission")) {
            val r = rig(large)
            val l = looks.first { it.name == look }
            val w = l.f.boxW * r.s
            val h = (K.STACKED_H + if (l.f.barClosed) 0f else l.f.barH) * r.s
            val glow = r.glow(w, h)
            assertTrue("the peak mask fits the layer", glow.peakMargin <= r.m + 0.01f)
            val bmp = Bitmap.createBitmap(r.layerW + 40, r.layerH + 40, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(r.m + 20, r.m + 20)
            r.goal.begin(Side.AWAY, StackedFixtures.EDM.goal, null, "EDM", "TOR", 3, 1, l.f.boxW)
            r.goal.drawGlow(c, g, glow, 0f, 0f)
            val a20 = Color.alpha(bmp.getPixel((r.m + 20 + w / 2f).toInt(), (r.m + 20 - 20f).toInt())) / 255.0
            val a56 = Color.alpha(bmp.getPixel((r.m + 20 + w / 2f).toInt(), (r.m + 20 - 56f).toInt())) / 255.0
            val b56 = Color.alpha(bmp.getPixel((r.m + 20 + w / 2f).toInt(), (r.m + 20 + h + 56f).toInt())) / 255.0
            println("glow $look ${sz(large)}: α 20 px %.3f, 56 px above %.4f, below %.4f".format(a20, a56, b56))
            assertTrue("glow at 20 px: $a20", a20 > 0.15)
            assertTrue("glow at 56 px above: $a56", a56 <= K.GOAL_GLOW_EDGE_MAX_ALPHA)
            assertTrue("glow at 56 px below: $b56", b56 <= K.GOAL_GLOW_EDGE_MAX_ALPHA)
            assertTrue("glow is the team colour", near(StackedFixtures.EDM.goal.glow, bmp.getPixel((r.m + 20 + w / 2f).toInt(), (r.m + 20 - 20f).toInt()), 12))
        }
    }

    /** The layer (footprint + 2 M) holds everything: its outermost ring of pixels stays transparent through a takeover. */
    @Test
    fun nothingReachesTheLayerEdge() {
        for (large in listOf(false, true)) for (look in looks.filter { it.name in setOf("pp-edm", "play", "intermission") }) for (up in listOf(false, true)) for (mirrored in listOf(false, true)) {
            var t = 0f
            while (t <= 4_800f) {
                val shot = render(look, large, TRANSPARENT, t = t, up = up, mirrored = mirrored)
                val b = shot.bmp
                for (x in 0 until b.width) {
                    assertEquals("${look.name} ($x, 0) at $t", 0, Color.alpha(b.getPixel(x, 0)))
                    assertEquals("${look.name} ($x, bottom) at $t", 0, Color.alpha(b.getPixel(x, b.height - 1)))
                }
                for (y in 0 until b.height) {
                    assertEquals("${look.name} (0, $y) at $t", 0, Color.alpha(b.getPixel(0, y)))
                    assertEquals("${look.name} (right, $y) at $t", 0, Color.alpha(b.getPixel(b.width - 1, y)))
                }
                t += 300f
            }
        }
    }

    /** (§6 N3) The box's corners are round: the row stripes, the bar and the last column never paint a square corner. */
    @Test
    fun theBoxCornersAreRound() {
        for (large in listOf(false, true)) for (look in looks.filter { it.name in setOf("pp-edm", "play", "intermission") }) for (up in listOf(false, true)) for (mirrored in listOf(false, true)) {
            val r = rig(large)
            val m = render(look, large, TRANSPARENT, shadow = false, up = up, mirrored = mirrored)
            // In box coordinates (the top part's left edge at x 0), mirrored or not.
            val shot = Shot(m.bmp, m.ox + m.bl, m.oy)
            val w = look.f.boxW * r.s
            val top = if (up && !look.f.barClosed) -K.STACKED_BAR_H * r.s else 0f
            val bottom = K.STACKED_H * r.s + if (!up && !look.f.barClosed) K.STACKED_BAR_H * r.s else 0f
            // The pixel holding each corner (the right edge can be fractional: 301.5, 391.5).
            for ((x, y) in listOf(0f to top, w - 0.5f to top, 0f to bottom - 0.5f, w - 0.5f to bottom - 0.5f)) {
                val a = Color.alpha(shot.px(x, y))
                // A square corner covers this pixel fully (255); the 2.7 (3.4 Large) radius leaves it partly empty.
                assertTrue("${look.name} ${sz(large)} up=$up mirrored=$mirrored corner ($x, $y) α $a", a < 240)
            }
            // Along an edge, one pixel in, the box is solid.
            assertEquals(255, Color.alpha(shot.px(w / 2f, top + 1f)))
        }
    }

    /** (§7.2) The takeover covers the top part only: the open bar is unchanged under it, and outside the box nothing moves with the glow off. */
    @Test
    fun theTakeoverLeavesTheBarVisible() {
        for (large in listOf(false, true)) for (up in listOf(false, true)) for (mirrored in listOf(false, true)) {
            val r = rig(large)
            val look = looks.first { it.name == "pp-edm" }
            val plain = render(look, large, DARK, up = up, mirrored = mirrored).let { Shot(it.bmp, it.ox + it.bl, it.oy) }
            val goal = render(look, large, DARK, t = 1_800f, up = up, glowOn = false, mirrored = mirrored).let { Shot(it.bmp, it.ox + it.bl, it.oy) }
            val barY = if (up) -K.STACKED_BAR_H * r.s / 2f else (K.STACKED_H + K.STACKED_BAR_H / 2f) * r.s
            for (x in listOf(10f, 100f, 250f, 380f)) {
                assertEquals("bar at $x", plain.px(x * r.s, barY), goal.px(x * r.s, barY))
            }
            val panel = goal.px(look.f.boxW * r.s - 6f * r.s, K.STACKED_H * r.s / 2f)
            assertTrue("panel ${hex(panel)} is EDM's base", near(StackedFixtures.EDM.goal.base, panel, 4) || Color.red(panel) > 200)
        }
    }

    /** (§7.2, §7.3) The row flash: the scorer's row takes its stripe colour after 3 600, at the C7 peak (animated) or .25 (still). */
    @Test
    fun theRowFlashAndTheStillTint() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val look = looks.first { it.name == "play" }
            val spotX = K.STACKED_ROWS_W * r.s - 4f * r.s
            val plain = render(look, large).px(spotX, 4f * r.s)
            val flash = render(look, large, t = 3_600f + 1f).px(spotX, 4f * r.s)
            val expected = StackedPainter.lerpColour(K.STACKED_ROW_BG, StackedFixtures.EDM.stripe, StackedFixtures.EDM.rowFlashAlpha)
            assertTrue("flash ${hex(flash)} vs ${hex(expected)}", near(expected, flash, 4))
            val stillB = render(look, large, t = 4_200f, still = true).px(spotX, 4f * r.s)
            val tint = StackedPainter.lerpColour(K.STACKED_ROW_BG, StackedFixtures.EDM.stripe, K.STACKED_STILL_ROW_TINT)
            assertTrue("still tint ${hex(stillB)} vs ${hex(tint)}", near(tint, stillB, 4))
            // The other row is untouched.
            assertEquals(render(look, large).px(spotX, 58f * r.s), render(look, large, t = 3_601f).px(spotX, 58f * r.s))
            assertTrue(plain != flash)
        }
    }

    /** At and after `endMs` (and the still version's frame B end) the frame is pixel-equal to the plain box. */
    @Test
    fun fromTheEndItIsPixelEqualToThePlainBox() {
        for (large in listOf(false, true)) for (look in looks.filter { it.name in setOf("pp-edm", "intermission") }) for (mirrored in listOf(false, true)) {
            val plain = render(look, large, mirrored = mirrored).bmp
            assertTrue("animated at 4 800 mirrored=$mirrored", render(look, large, t = 4_800f, mirrored = mirrored).bmp.sameAs(plain))
            assertTrue("still at 4 800 mirrored=$mirrored", render(look, large, t = 4_800f, still = true, mirrored = mirrored).bmp.sameAs(plain))
        }
    }

    /** (§7.3) The still frame A has no glow: outside the box it is pixel-equal to the plain box. */
    @Test
    fun theStillFrameAHasNoGlowOrStreak() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val look = looks.first { it.name == "pp-edm" }
            val plain = render(look, large, TRANSPARENT)
            val a = render(look, large, TRANSPARENT, t = 1_000f, still = true)
            val w = look.f.boxW * r.s
            val h = (K.STACKED_H + K.STACKED_BAR_H) * r.s
            for (y in 0 until plain.bmp.height) for (x in 0 until plain.bmp.width) {
                val bx = x - plain.ox
                val by = y - plain.oy
                if (bx > -1f && bx < w + 1f && by > -1f && by < h + 1f) continue
                assertEquals("($x, $y)", plain.bmp.getPixel(x, y), a.bmp.getPixel(x, y))
            }
        }
    }

    /** The bar's words cut over at the change's swap instant: before it the previous words and stripe colours are drawn. */
    @Test
    fun aChangeDrawsThePreviousContentUntilItsSwap() {
        val r = std
        val look = looks.first { it.name == "change-pp-to-int-t100" }
        assertTrue("before the swap instant", !look.f.barSwapped)
        assertTrue("the background mid-mix: ${look.f.barBgMix}", look.f.barBgMix in 0.05f..0.95f)
        val before = render(look, false)
        val after = render(Look(look.name, look.body, StackedFrame().apply { copyFrom(look.f); barSwapped = true }, look.bar, look.barPeriod, prev = look.prev), false)
        val y = (K.STACKED_H + K.STACKED_BAR_H / 2f) * r.s
        val bgMid = StackedPainter.lerpColour(StackedFixtures.EDM.barBg, K.STACKED_BAR_NEUTRAL, look.f.barBgMix)
        val px = before.px(look.f.boxW - 4f, y)
        assertTrue("half-way background ${hex(px)} vs ${hex(bgMid)}", near(bgMid, px, 3))
        assertTrue("the words differ across the swap", !before.bmp.sameAs(after.bmp))
    }

    // ------------------------------------------------------------------ the right-hand corners: mirrored (owner 2026-11-13)

    /**
     * A pair whose every colour is unique on the box (no white, nothing near the row, column or bar neutrals), so each
     * element can be found by its colour: away stripe green, home stripe magenta; the team bars' backgrounds and stripes.
     */
    private val probePair = StackedPair(
        StackedColours(stripe = 0xFF00E000.toInt(), barBg = 0xFF7A0000.toInt(), barA = 0xFFFFFF00.toInt(), barB = 0xFF00FFFF.toInt(), goal = StackedFixtures.EDM.goal, rowFlashAlpha = 0.4f),
        StackedColours(stripe = 0xFFE000E0.toInt(), barBg = 0xFF00007A.toInt(), barA = 0xFFFF8000.toInt(), barB = 0xFF80FF80.toInt(), goal = StackedFixtures.TOR.goal, rowFlashAlpha = 0.5f),
    )

    /**
     * The x extent (box coordinates: the top part's left edge is 0) of the pixels within [tol] of [colour] in the band
     * [y0, y1) (top-part coordinates) and, when given, the box-coordinate window [x0, x1); null when there are none.
     */
    private fun extent(shot: Shot, colour: Int, y0: Float, y1: Float, tol: Int = 6, x0: Float = -1e9f, x1: Float = 1e9f): Pair<Float, Float>? {
        var l = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        val left = shot.ox + shot.bl
        for (py in Math.floor((y0 + shot.oy).toDouble()).toInt() until Math.ceil((y1 + shot.oy).toDouble()).toInt()) {
            for (px in 0 until shot.bmp.width) {
                val bx = px + 0.5f - left
                if (bx < x0 || bx >= x1) continue
                if (near(colour, shot.bmp.getPixel(px, py), tol) && Color.alpha(shot.bmp.getPixel(px, py)) > 250) { l = minOf(l, px); r = maxOf(r, px) }
            }
        }
        if (l == Int.MAX_VALUE) return null
        return (l - left) to (r + 1 - left)
    }

    /** The x centre (box coordinates) of [ink]'s half-way pixels over [bg] in a box-coordinate window, or null. */
    private fun inkCentre(shot: Shot, x0: Float, x1: Float, y0: Float, y1: Float, bg: Int, ink: Int, channel: Int = 8): Float? {
        val b = Shot(shot.bmp, shot.ox + shot.bl, shot.oy)
        val box = inkBox(b, x0, y0, x1, y1, bg, ink, channel) ?: return null
        return box.centerX()
    }

    /**
     * (Owner request 2026-11-13) **The mirror property.** For every look (the rest states, the mid-transition frames, the
     * bar above the box) at both sizes, each element of the mirrored box is the reflection, within the box, of the same
     * element of the left-corner box (box coordinates: x ↦ w − x), within a pixel: the row stripes, the row background, the
     * column background, the inset lines, the rule, the bar's background and its two stripes on their centre line; and
     * the centred texts' centres (the SOG digits and label, the period, the clock). Text is never mirrored, so the row's
     * code and score and the bar's words are pinned separately (`theMirroredGeometryProbes`).
     */
    @Test
    fun theMirroredBoxIsTheReflectionOfTheLeftOne() {
        var checks = 0
        for (large in listOf(false, true)) for (look in looks.filter { !it.placing }) for (up in listOf(false, true)) {
            if (up && look.bar == null) continue
            val r = rig(large)
            val s = r.s
            val f = look.f
            val w = f.boxW * s
            val h = K.STACKED_H * s
            val rowH = h / 2f
            val left = render(look, large, TRANSPARENT, shadow = false, up = up, pair = probePair)
            val right = render(look, large, TRANSPARENT, shadow = false, up = up, pair = probePair, mirrored = true)
            val msg = "${look.name} ${sz(large)} up=$up"
            fun reflect(e: Pair<Float, Float>) = (w - e.second) to (w - e.first)
            fun same(what: String, colour: Int, y0: Float, y1: Float, tol: Int = 6) {
                val a = extent(left, colour, y0, y1, tol)
                val b = extent(right, colour, y0, y1, tol)
                assertEquals("$msg $what: drawn on both sides", a == null, b == null)
                if (a == null || b == null) return
                val want = reflect(a)
                assertEquals("$msg $what left (unmirrored $a)", want.first, b.first, 1f)
                assertEquals("$msg $what right (unmirrored $a)", want.second, b.second, 1f)
                checks++
            }
            same("away stripe", probePair.away.stripe, 2f, rowH - 2f)
            same("home stripe", probePair.home.stripe, rowH + 2f, h - 2f)
            same("row background", K.STACKED_ROW_BG, 2f, h - 2f, tol = 1)
            same("column background", K.STACKED_COL_BG, 2f, h - 2f, tol = 1)
            // A sliding column's 1-px line sits on its (aliased, pixel-snapped) clip edge mid-transition, where the
            // snapping rounds the two sides' edges differently by up to a pixel: at rest only.
            if (!look.moving) same("inset lines", K.STACKED_INSET_LINE, 2f, h - 2f, tol = 2)
            val ruleTop = M.PERIOD_SLOT_H * K.STACKED_SCALE * s
            same("rule", K.STACKED_RULE, ruleTop - 2f, ruleTop + 3f, tol = 2)
            if (!f.barClosed && look.prev == null) {
                val bh = f.barH * s
                val bar = bar(r, look.bar, look.barPeriod, look.barDim, probePair)!!
                val regionTop = if (up) -bh else h
                same("bar background", bar.bg, regionTop + 2f, regionTop + bh - 2f, tol = 2)
                // The stripes on their own centre line (a "/" parallelogram's cross-section there is centred on it).
                val contentTop = if (up) regionTop else regionTop + bh - K.STACKED_BAR_H * s
                val cy = contentTop + K.STACKED_BAR_H * s / 2f
                if (cy - 1f >= regionTop && cy + 1f <= regionTop + bh) {
                    // One pixel row: the one holding the centre line.
                    same("bar stripe A", bar.stripeA, cy, cy + 0.001f, tol = 30)
                    same("bar stripe B", bar.stripeB, cy, cy + 0.001f, tol = 30)
                }
            }
            // The centred texts, at rest only (a moving column's contents slide).
            if (!look.moving) {
                val rowsW = K.STACKED_ROWS_W * s
                val sogW = K.STACKED_SOG_W * s
                val clockW = K.STACKED_CLOCK_W * s
                fun centre(what: String, x0: Float, x1: Float, y0: Float, y1: Float, bg: Int, ink: Int) {
                    val a = inkCentre(left, x0, x1, y0, y1, bg, ink)
                    val b = inkCentre(right, w - x1, w - x0, y0, y1, bg, ink)
                    assertEquals("$msg $what: drawn on both sides", a == null, b == null)
                    if (a == null || b == null) return
                    // Text is moved, not reflected: its slot's centre reflects and its ink keeps its offset from it.
                    val cx = (x0 + x1) / 2f
                    assertEquals("$msg $what centre (unmirrored $a in a slot centred at $cx)", (w - cx) + (a - cx), b, 1.5f)
                    checks++
                }
                if (f.sogWidth >= 1f) {
                    centre("away SOG", rowsW + 2f, rowsW + sogW - 2f, 2f, rowH - 10f * s, K.STACKED_COL_BG, Color.WHITE)
                    centre("SOG label", rowsW + 2f, rowsW + sogW - 2f, rowH - 9f * s, rowH + 9f * s, K.STACKED_COL_BG, K.STACKED_SOG_LABEL)
                }
                if (f.clockWidth >= 1f) {
                    val x0 = rowsW + f.sogWidth * sogW
                    centre("period", x0 + 2f, x0 + clockW - 2f, 2f, ruleTop - 2f, K.STACKED_COL_BG, Color.WHITE)
                    centre("clock slot", x0 + 2f, x0 + clockW - 2f, ruleTop + 4f, h - 2f, K.STACKED_COL_BG, Color.WHITE)
                }
            }
        }
        println("mirror property: $checks element checks")
        assertTrue("checks ran: $checks", checks > 300)
    }

    /**
     * (Owner request 2026-11-13) The mirrored box's own numbers, from the drawn pixels (box coordinates; the rows are its
     * right 243): the stripe on the rows' right edge; the row's contents one block before it in reading order (the code's
     * ink at 9.9 + 50.4 + 13.5 = 73.8 past the rows' left edge, the score's right ink 243 − 3.6 − 15.3 = 224.1, 221.4 on
     * the wide power-play stripe); the columns in the order [clock][SOG][rows]; the bar's stripes at 20.7 / 37.8 from its
     * right end and its words right-aligned 63 from it.
     */
    @Test
    fun theMirroredGeometryProbes() {
        val textTol = 1.5f
        for (large in listOf(false, true)) {
            val r = rig(large)
            val s = r.s
            val p = r.painter
            val white = Color.WHITE
            val rowH = K.STACKED_H / 2f * s
            val h = K.STACKED_H * s
            fun boxed(m: Shot) = Shot(m.bmp, m.ox + m.bl, m.oy)
            val playLook = looks.first { it.name == "play" }
            val ppLook = looks.first { it.name == "pp-edm" }
            val play = boxed(render(playLook, large, DARK, shadow = false, pair = probePair, mirrored = true))
            val pp = boxed(render(ppLook, large, DARK, shadow = false, pair = probePair, mirrored = true))
            for ((name, shot, look) in listOf(Triple("play", play, playLook), Triple("pp", pp, ppLook))) {
                val w = look.f.boxW * s
                val rl = w - K.STACKED_ROWS_W * s // the rows' left edge
                val wide = name == "pp" // EDM (away) has the power play: its stripe is 6.3
                val awayStripe = (if (wide) 6.3f else 3.6f) * s
                val stripe = extent(shot, probePair.away.stripe, 2f, rowH - 2f)
                assertTrue("$name ${sz(large)}: the away stripe is drawn", stripe != null)
                // Its solid pixels: from its inner edge to the box's 1-px border on the right edge.
                val line = (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f)
                assertEquals("$name ${sz(large)}: away stripe's outer edge = the box's right edge less the border", w - line, stripe!!.second, 1f)
                assertEquals("$name ${sz(large)}: away stripe's inner edge (${if (wide) 6.3 else 3.6} in)", w - awayStripe, stripe.first, 1f)
                val codeInk = p.inkBounds(StackedPainter.Ink.CODE, "EDM", 0f, 0f)
                val code = inkBox(shot, rl + 66f * s, 0f, rl + 170f * s, rowH, K.STACKED_ROW_BG, white)
                assertEquals("$name ${sz(large)}: code left (73.8)", rl + 73.8f * s + codeInk.left, code!!.left, textTol)
                val scoreR = rl + (243f - (if (wide) 6.3f else 3.6f) - 15.3f) * s
                val score = inkBox(shot, rl + 175f * s, 0f, rl + 243f * s - awayStripe - 1f, rowH, K.STACKED_ROW_BG, white)
                assertEquals("$name ${sz(large)}: away score right", p.inkBounds(StackedPainter.Ink.SCORE, "2", scoreR, 0f).right, score!!.right, textTol)
                // The clock column is the outermost: the period and the clock centred on its 90, at the box's left edge.
                val ccx = K.STACKED_CLOCK_W / 2f * s
                val clock = inkBox(shot, 2f * s, 56f * s, K.STACKED_CLOCK_W * s - 2f * s, h - 1f, K.STACKED_COL_BG, white)
                val want = p.clockInk(384).apply { offset(ccx, (50.4f * s + (K.STACKED_HAIRLINE_W * s).coerceAtLeast(1f) + h) / 2f) }
                assertEquals("$name ${sz(large)}: clock ink left", want.left, clock!!.left, textTol)
                assertEquals("$name ${sz(large)}: clock ink right", want.right, clock.right, textTol)
                if (wide) {
                    // The SOG column between the clock and the rows: digits centred at 90 + 29.25.
                    val sogCx = (K.STACKED_CLOCK_W + K.STACKED_SOG_W / 2f) * s
                    val sog = inkBox(shot, (K.STACKED_CLOCK_W + 2f) * s, 0f, (K.STACKED_CLOCK_W + K.STACKED_SOG_W - 2f) * s, rowH - 10f * s, K.STACKED_COL_BG, white)
                    assertEquals("pp ${sz(large)}: SOG digits x centre", p.inkBounds(StackedPainter.Ink.SOG, "14", sogCx, 0f).centerX(), sog!!.centerX(), textTol)
                    // The bar: stripes A / B 20.7 / 37.8 (11.7 wide) from the right end on the centre line; words end at 63.
                    val barCy = (K.STACKED_H + K.STACKED_BAR_H / 2f) * s
                    val bg = probePair.away.barBg
                    val a = inkBox(shot, w - 60f * s, barCy - 0.5f, w - 3f * s, barCy + 0.5f, bg, probePair.away.barA, channel = 16)
                    assertEquals("pp ${sz(large)}: bar stripe A right (20.7 from the end)", w - 20.7f * s, a!!.right, 1f)
                    assertEquals("pp ${sz(large)}: bar stripe A left (32.4 from the end)", w - 32.4f * s, a.left, 1f)
                    val b = inkBox(shot, w - 60f * s, barCy - 0.5f, w - 34f * s, barCy + 0.5f, bg, probePair.away.barB, channel = 0)
                    assertEquals("pp ${sz(large)}: bar stripe B right (37.8 from the end)", w - 37.8f * s, b!!.right, 1f)
                    assertEquals("pp ${sz(large)}: bar stripe B left (49.5 from the end)", w - 49.5f * s, b.left, 1f)
                    val content = bar(r, ppLook.bar, null, false, probePair)!!
                    val ink = p.barTextInkMirrored(content, w)
                    val words = inkBox(shot, 3f * s, barCy - 9f * s, w - 57f * s, barCy + 9f * s, bg, white, channel = 8)
                    assertEquals("pp ${sz(large)}: bar words right", ink.right, words!!.right, textTol)
                    assertEquals("pp ${sz(large)}: bar words left", ink.left, words.left, textTol)
                }
            }
        }
    }

    /**
     * (Owner request 2026-11-13) The owner's sheet: the top-left and the top-right placements side by side over the dark
     * gradient for every state, then the bottom corners with the bar above the box, at Standard and Large:
     * `ui/build/score-bug-stacked/stacked-mirror-sheet-{std,large}.png`. Each cell is the bug's layer (footprint + 2 M)
     * drawn as the runner places it in that corner.
     */
    @Test
    fun theLeftAndRightCornersSideBySide() {
        val rows = listOf(
            Triple("Even strength", "play", null as Float?),
            Triple("Power play", "pp-edm", null),
            Triple("5-on-3", "pp-tor-5on3", null),
            Triple("Intermission", "intermission", null),
            Triple("Goal 150 ms (lead wipe)", "pp-edm", 150f),
            Triple("Goal 700 ms (streak)", "pp-edm", 700f),
            Triple("Goal 1800 ms", "play", 1_800f),
            Triple("No update", "stale-play", null),
            Triple("No update (break)", "stale-int", null),
            Triple("Shootout", "shootout", null),
            Triple("Final", "final-so", null),
            Triple("PP opening 300 ms", "pp-open-t300", null),
        )
        for (large in listOf(false, true)) {
            val cells = ArrayList<Triple<String, Bitmap, Bitmap>>()
            for ((label, name, t) in rows) {
                val look = looks.first { it.name == name }
                cells += Triple(label, render(look, large, DARK, t = t).bmp, render(look, large, DARK, t = t, mirrored = true).bmp)
            }
            val pp = looks.first { it.name == "pp-edm" }
            cells += Triple("Bottom corners, bar up", render(pp, large, DARK, up = true).bmp, render(pp, large, DARK, up = true, mirrored = true).bmp)
            val inter = looks.first { it.name == "intermission" }
            cells += Triple("Bottom, intermission", render(inter, large, DARK, up = true).bmp, render(inter, large, DARK, up = true, mirrored = true).bmp)
            cells += Triple("Bottom, goal 700 ms", render(pp, large, DARK, t = 700f, up = true).bmp, render(pp, large, DARK, t = 700f, up = true, mirrored = true).bmp)
            val labelW = 300
            val gap = 24
            val cw = cells[0].second.width
            val ch = cells[0].second.height
            val head = 40
            val sheet = Bitmap.createBitmap(labelW + 2 * cw + gap, head + cells.size * ch, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.rgb(12, 12, 14))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = bold }
            c.drawText("Left corners (unchanged)", (labelW + 10).toFloat(), 28f, text)
            c.drawText("Right corners (mirrored)", (labelW + cw + gap + 10).toFloat(), 28f, text)
            cells.forEachIndexed { i, (label, l, r) ->
                val y = head + i * ch
                c.drawText(label, 12f, y + ch / 2f + 8f, text)
                c.drawBitmap(l, labelW.toFloat(), y.toFloat(), null)
                c.drawBitmap(r, (labelW + cw + gap).toFloat(), y.toFloat(), null)
            }
            save("stacked-mirror-sheet-${sz(large)}", sheet)
        }
        assertTrue(File(out, "stacked-mirror-sheet-std.png").length() > 10_000)
    }

    private companion object {
        const val DARK = 0x01010101
        const val GREY = 0xFF808080.toInt()
        const val TRANSPARENT = 0x00000000
        /**
         * The stretched sides against the analytic Gaussian edge, alpha of 255. Measured 3–4: the bake's own blur (Skia's
         * box-filter approximation of a Gaussian) is the whole of it; Skia's direct blur of the full box is 4–8 off the
         * same curve, so the slicing adds nothing.
         */
        const val EDGE_TOL = 4
        /** Against Skia's direct round-rect blur, alpha of 255 (≈ 3 %, under 2 % once tinted at .55). */
        const val DIRECT_TOL = 8
    }
}
