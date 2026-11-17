package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflStackedColours
import app.thebox.core.sports.NflTimeoutMark
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflGeometry as G
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TabTail
import app.thebox.ui.R
import app.thebox.ui.sports.StackedNflTestKit.DARK
import app.thebox.ui.sports.StackedNflTestKit.GREY
import app.thebox.ui.sports.StackedNflTestKit.TRANSPARENT
import app.thebox.ui.sports.StackedNflTestKit.down
import app.thebox.ui.sports.StackedNflTestKit.field
import app.thebox.ui.sports.StackedNflTestKit.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

/**
 * NFL Stacked §9.2 `StackedNflScreenshotTest`, **through the real pipeline** (integration pass 2026-11-15): each state is a
 * `ScoreBugView` as the NFL machine shapes it, laid out by `StackedNflTargets.of`, worded by `StackedNflPainter.frame` /
 * `fillBar`, coloured by `NflStackedColours`, sampled from `StackedNflTimeline` (rest, or a transition at a time) and the
 * takeover from `NflScoreTimeline` under `NflScoreSpec.STACKED`, then drawn by `StackedNflPainter.drawFrame` (which draws
 * the takeover itself, the hockey order). Every §3.4 state for KC @ BUF, the dark-bar matchups IND @ NE and DAL @ NYG,
 * BUF–NE, DAL @ PHI, LV–PIT and CIN–DEN; the four corners × Standard / Large over the dark gradient, the top corners over
 * white ice and 50 % grey; every takeover kind at the design times on both sides, a +7 pair, the still frames.
 *
 * PNGs under `ui/build/score-bug-stacked-nfl/` (hashed by `:ui:sportsHashGate`). Review fix U-S3: the hashed set is
 * proportionate: every look in an unmirrored (TL) and a mirrored (BR) corner at both sizes, a few in the other two
 * corners, the ice and grey backgrounds in TL only, the takeover strips' cells (no t = 0: it is the play look) in TL and
 * BR at both sizes. The contact sheets and strips are for humans: every look in every corner, written to
 * `ui/build/score-bug-stacked-nfl-review/` (not a gated folder) and to [StackedNflTestKit.SHEETS]. Review fix U-S1:
 * the takeover looks (the rows' body, the segment's tally, the pinned bar key) come from `StackedNflPinSchedule`, the
 * runner's own sequencing, never by hand. Review fix U-N5: a team with no logo asset draws the real `LogoDisc`. Asserted: the bar background is the content's colour where drawn, the words pass contrast
 * (team bars ≥ 4.5, flag ≥ 4.5, rows ≥ 7), and no ball, post or streak pixel outside the top part.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedNflScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val out = File("build/score-bug-stacked-nfl").apply { mkdirs() }
    /** U-S3: the contact sheets and strips, outside the gated folders. */
    private val review = File("build/score-bug-stacked-nfl-review").apply { mkdirs() }

    private inner class Rig(val large: Boolean) {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val painter = StackedNflPainter(s, large, bold, italic)
        val score = StackedNflScorePainter(s, large, barlow, bold, StackedNflTestKit.SPEC)
        val shadow = painter.bakeShadow()!!
        val logoPx = Math.round(STACKED_NFL_BADGE * s)
        val logos = HashMap<String, Bitmap?>()
        fun logo(code: String) = logos.getOrPut(code) { StackedNflTestKit.logoOrNull(code, logoPx) }
        val glows = HashMap<Pair<Int, Int>, GlowMasks>()
        fun glow(w: Float, h: Float) = glows.getOrPut(Math.round(w * 10) to Math.round(h * 10)) { score.bakeGlow(w, h)!! }
        val layerW = Math.round(K.STACKED_NFL_W_MAX * s + 2 * m)
        val layerH = Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m)
    }

    private val std by lazy { Rig(false) }
    private val big by lazy { Rig(true) }
    private fun rig(large: Boolean) = if (large) big else std

    private enum class Corner(val tag: String, val mirrored: Boolean, val up: Boolean) {
        TL("tl", false, false), TR("tr", true, false), BL("bl", false, true), BR("br", true, true)
    }

    /**
     * One look: a real view and how its frame is sampled. [frame] gets the targets' rest frame and may move one track
     * into a transition; [bar] overrides the bar key (the runner's pinned score labels only); [prev] is the bar a REKEY
     * leaves (its background lerps under `barBgMix`).
     */
    private class Look(
        val name: String,
        val view: ScoreBugView,
        val frame: (StackedNflTargets, StackedNflFrame) -> StackedNflFrame = { _, f -> f },
        val bar: NflBarKey? = null,
        val prev: ScoreBugView? = null,
    ) {
        val away get() = view.away.abbrev
        val home get() = view.home.abbrev
    }

    private val kit = StackedNflTestKit
    private val p1 = PeriodLabel(PeriodKind.REG, 1)
    private val flagView = kit.view(flagId = "401873061503")
    private val buf1 = NflTimeoutMark(Side.HOME, 1, confirmed = true)
    private val timeoutView = kit.view(homeTimeouts = 1, timeout = buf1)

    /** [f] with the bar track [ms] into [kind] from the bar at rest (open) or closed. */
    private fun bar(kind: StackedNflTransition, ms: Float, fromOpen: Boolean) = { t: StackedNflTargets, f: StackedNflFrame ->
        val from = kit.rest(t).also { if (!fromOpen) kit.at(it, StackedNflTrack.BAR, StackedNflTransition.STILL, 0f, StackedNflFrame(), to = false) }
        kit.at(f, StackedNflTrack.BAR, kind, ms, from)
    }

    private val looks: List<Look> = listOf(
        Look("play", kit.view()),
        Look("play-redzone", kit.view(down = down(4, null, goal = true), tab = kit.tab(Side.HOME, TabTail.RedZone))),
        Look("play-neutral", kit.view(down = down(3, 7), tab = null, field = app.thebox.core.sports.FieldPos(Side.HOME, 35))),
        Look("play-midfield", kit.view(down = down(2, 5), tab = kit.tab(Side.AWAY, field(FieldZone.MIDFIELD, 50)))),
        Look("play-no-distance", kit.view(down = down(3, null), tab = kit.tab(Side.AWAY, field(FieldZone.OPP, 12)))),
        Look("pat", kit.view(down = DownText.Pat, tab = kit.tab(Side.AWAY, null))),
        Look("kickoff", kit.view(down = DownText.Kickoff, tab = kit.tab(Side.AWAY, field(FieldZone.OWN, 35)))),
        Look("flag-t150", flagView, { _, f -> kit.flag(f, 150f) }),
        Look("flag-t400", flagView, { _, f -> kit.flag(f, 400f) }),
        Look("timeout-burn-t200", timeoutView, { _, f -> kit.burn(f, Side.HOME, 2, 1, 200f) }),
        Look("timeout-burn-t1600", timeoutView, { _, f -> kit.burn(f, Side.HOME, 2, 1, 1_600f) }),
        Look("timeout", timeoutView),
        Look("halftime", kit.halftime()),
        Look("end-of-1st", kit.endOf(p1)),
        Look("final", kit.final()),
        Look("final-ot", kit.final(PeriodKind.OT)),
        Look("stale-play", kit.staleInPlay()),
        Look("delayed-play", kit.delayedInPlay()),
        Look("stale-break", kit.staleInBreak()),
        Look("delayed-break", kit.delayedInBreak()),
        Look("no-situation", kit.view(down = null)),
        // C-S1: the dead ball after a field goal holds the bar shown with the held tab.
        Look("fg-dead-ball-held", kit.view(homeScore = 20, down = null, heldDown = down(4, 3), tab = kit.tab(Side.HOME, field(FieldZone.OPP, 20)))),
        Look("score-bar", kit.view(awayScore = 30), bar = kit.scoreKey(Side.AWAY, NflScoreKind.TOUCHDOWN, 6)),
        Look("score-between-pair", kit.view(awayScore = 30), bar = NflBarKey(NflBarKind.PAT, Side.AWAY)),
        // Mid-transition frames (deliberate): a REKEY 200 ms in (the new words still sliding in over the lerped
        // background), an OPEN 200 ms in (the bar half open, its words not yet in: they start at 400).
        Look("rekey-t200", timeoutView, bar(StackedNflTransition.REKEY, 200f, fromOpen = true), prev = kit.view()),
        Look("open-t200", kit.view(), bar(StackedNflTransition.OPEN, 200f, fromOpen = false)),
        Look("three-digit", kit.view(awayScore = 100, homeScore = 99, down = down(2, 8), tab = kit.tab(Side.HOME, field(FieldZone.OPP, 41)))),
        Look("ot-two-slots", kit.view(awayTimeouts = 1, homeTimeouts = 2, slots = 2, period = PeriodLabel(PeriodKind.OT, 1), tab = kit.tab(Side.HOME, field(FieldZone.OWN, 25)))),
        Look("pips-hidden", kit.view(awayTimeouts = null, homeTimeouts = null, slots = null)),
        // The dark-bar matchups (Rec 2) and the collision pairs.
        Look("ind-ne-play", kit.view("IND", "NE", tab = kit.tab(Side.AWAY, field(FieldZone.OWN, 25)))),
        Look("ind-ne-redzone", kit.view("IND", "NE", down = down(2, 4), tab = kit.tab(Side.HOME, TabTail.RedZone))),
        Look("dal-nyg-play", kit.view("DAL", "NYG", down = down(3, 6), tab = kit.tab(Side.AWAY, field(FieldZone.OPP, 44)))),
        Look("dal-nyg-timeout", kit.view("DAL", "NYG", homeTimeouts = 0, timeout = NflTimeoutMark(Side.HOME, 0, true))),
        Look("buf-ne-play", kit.view("BUF", "NE", tab = kit.tab(Side.HOME, field(FieldZone.OWN, 20)))),
        Look("dal-phi-play", kit.view("DAL", "PHI", down = down(2, 10), tab = kit.tab(Side.HOME, field(FieldZone.OPP, 30)))),
        Look("lv-pit-play", kit.view("LV", "PIT")),
        Look("cin-den-kickoff", kit.view("CIN", "DEN", down = DownText.Kickoff)),
        // U-N5: a four-letter code with no logo: the real capsule fallback.
        Look("fallback-capsule", kit.view("WASH", "NE", tab = kit.tab(Side.AWAY, field(FieldZone.OWN, 30)))),
    )

    /** U-S3: the looks also hashed in the other two corners (TR: mirrored, bar below; BL: unmirrored, bar above). */
    private val crossCorners = setOf("play", "flag-t400", "timeout", "halftime")

    private fun content(r: Rig, key: NflBarKey?, dim: Boolean, pair: StackedPair, away: String, home: String): NflBarContent? =
        key?.let { NflBarContent().also { c -> c.dim = dim; r.painter.fillBar(c, it, pair, away, home, res) } }

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float, val content: NflBarContent?, val f: StackedNflFrame)

    /** A takeover over [render]'s look: the machine's [scores] (one, or a +7 / +8 pair) at [t] by [scorer]. */
    private class Take(val t: Float, val scores: List<NflScore>, val still: Boolean = false, val scorer: Side = Side.AWAY) {
        val kind get() = scores[0].kind
        val second get() = scores.getOrNull(1)?.kind
    }

    private fun render(look: Look, large: Boolean, corner: Corner, bg: Int = DARK, take: Take? = null): Shot {
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
        val pair = NflStackedColours.pair(look.away, look.home)
        val targets = kit.targets(look.view)
        val f = look.frame(targets, kit.rest(targets))
        val a = StackedNflDrawArgs()
        val stt = r.painter.frame(look.view, targets, res)
        a.state = stt
        a.frame = f
        a.colours = pair
        a.bar = content(r, look.bar ?: targets.bar, targets.barDim, pair, look.away, look.home)
        a.barPrev = look.prev?.let { content(r, kit.targets(it).bar, false, pair, look.away, look.home) }
        a.awayLogo = r.logo(look.away)
        a.homeLogo = r.logo(look.home)
        a.mirrored = corner.mirrored
        a.up = corner.up
        a.still = take?.still ?: false
        a.shadow = r.shadow
        if (take != null) {
            val sc = if (take.scorer == Side.AWAY) pair.away else pair.home
            val g = kit.score(take.t, take.kind, take.second, take.still)
            // U-S1: the segment's own score (its kind and tally), as the runner's `begin` gets it.
            val seg = StackedNflPinSchedule.segment(take.t, take.scores.size > 1, kit.SPEC)
            r.score.begin(take.scorer, listOf(take.scores[seg]), sc.goal,
                r.logo(if (take.scorer == Side.AWAY) look.away else look.home), look.away, look.home, f.boxW)
            a.takeover = r.score
            a.glow = r.glow(f.boxW, K.STACKED_H + if (f.barClosed) 0f else f.barH)
            a.glowOn = true
            a.score = g
            a.scoreSide = take.scorer
            a.takeoverOn = true
        }
        val ox = r.m + if (corner.mirrored) (K.STACKED_NFL_W_MAX - K.STACKED_ROWS_W) * r.s else 0f
        val oy = r.m + if (corner.up) K.STACKED_BAR_H * r.s else 0f
        c.translate(ox, oy)
        r.painter.drawFrame(c, a)
        return Shot(bmp, ox, oy, a.bar, f)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(name: String, cells: List<List<Bitmap>>, dest: File = kit.SHEETS) {
        val cw = cells.flatten().maxOf { it.width }
        val ch = cells.flatten().maxOf { it.height }
        val cols = cells.maxOf { it.size }
        val b = Bitmap.createBitmap(cols * cw, cells.size * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { row, list -> list.forEachIndexed { col, bmp -> c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null) } }
        File(review, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        runCatching {
            dest.mkdirs()
            File(dest, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun sz(large: Boolean) = if (large) "large" else "std"

    private val POINTS = mapOf(NflScoreKind.TOUCHDOWN to 6, NflScoreKind.EXTRA_POINT to 1, NflScoreKind.FIELD_GOAL to 3, NflScoreKind.SAFETY to 2, NflScoreKind.TWO_POINT to 2)

    // ------------------------------------------------------------------ the states × corners × sizes

    /** U-S3: whether [look] in [corner] is hashed (TL and BR always; TR and BL for [crossCorners]). */
    private fun hashed(look: Look, corner: Corner) = corner == Corner.TL || corner == Corner.BR || look.name in crossCorners

    @Test
    fun everyStateInEveryCornerAtBothSizesAndTheContactSheets() {
        for (large in listOf(false, true)) {
            for (corner in Corner.entries) {
                val shots = looks.map { look ->
                    render(look, large, corner).bmp.also { if (hashed(look, corner)) save("nfl-stacked-${look.name}-${corner.tag}-${sz(large)}-dark", it) }
                }
                sheet("nfl-stacked-contact-sheet-${sz(large)}-dark-${corner.tag}", shots.chunked(4))
            }
            for ((bgName, bg) in listOf("ice" to Color.WHITE, "grey" to GREY)) {
                val shots = looks.take(16).flatMap { look ->
                    listOf(Corner.TL, Corner.TR).map { corner ->
                        // U-S3: the backgrounds are hashed in TL only (TR is on the sheet).
                        render(look, large, corner, bg).bmp.also { if (corner == Corner.TL) save("nfl-stacked-${look.name}-${corner.tag}-${sz(large)}-$bgName", it) }
                    }
                }
                sheet("nfl-stacked-contact-sheet-${sz(large)}-$bgName", shots.chunked(4))
            }
        }
        assertTrue(File(review, "nfl-stacked-contact-sheet-std-dark-tl.png").length() > 10_000)
        assertFalse("the sheets are not in the gated folder", File(out, "nfl-stacked-contact-sheet-std-dark-tl.png").exists())
    }

    /** U-S3: the design times without t = 0 (the takeover's first frame is the `play` look). */
    private val times = listOf(216, 450, 1_000, 1_500, 2_025, 3_600, 4_200)

    /** The machine's score(s) for a takeover of [kind] by [scorer] from 24 – 17 (a +7 pair when [second] is set). */
    private fun scoresOf(kind: NflScoreKind, scorer: Side = Side.AWAY, second: NflScoreKind? = null): List<NflScore> {
        fun at(k: NflScoreKind, before: Pair<Int, Int>): NflScore {
            val pts = POINTS.getValue(k)
            val away = before.first + if (scorer == Side.AWAY) pts else 0
            val home = before.second + if (scorer == Side.HOME) pts else 0
            return NflScore(k, pts, away, home)
        }
        val a = at(kind, 24 to 17)
        return if (second == null) listOf(a) else listOf(a, at(second, a.away to a.home))
    }

    /**
     * U-S1: the look the runner draws at [t] of a takeover of [scores] by [scorer] over [pre] (the view before the score):
     * the rows' body (`StackedNflPinSchedule.body`: the pre-score body until the lead covers, a pair's first tally until
     * B covers, then the scored view) and the pinned bar key (`StackedNflPinSchedule.barKey`, else the body's own bar).
     */
    private fun takeLook(pre: ScoreBugView, scores: List<NflScore>, t: Int, still: Boolean = false, scorer: Side = Side.AWAY): Look {
        val last = scores.last()
        fun scored(sc: NflScore) = pre.copy(away = pre.away.copy(score = sc.away), home = pre.home.copy(score = sc.home))
        val pair = scores.size > 1
        val body = when (StackedNflPinSchedule.body(t.toFloat(), still, pair, kit.SPEC)) {
            StackedNflPinSchedule.Body.PRE_SCORE -> pre
            StackedNflPinSchedule.Body.PAIR_FIRST_TALLY -> scored(scores[0])
            StackedNflPinSchedule.Body.SCORED -> scored(last)
        }
        val pinBoxW = nflRestBoxW(kit.targets(pre).clockOpen)
        val key = StackedNflPinSchedule.barKey(scorer, scores[0], scores.getOrNull(1), t.toFloat(), still, kit.targets(pre).bar != null, pinBoxW, kit.SPEC)
        return Look("take", body, bar = key)
    }

    @Test
    fun theTakeoverForEveryKindAtTheDesignTimesThePairAndTheStillFrames() {
        val play = looks.first { it.name == "play" }.view
        val half = looks.first { it.name == "halftime" }.view
        for (large in listOf(false, true)) for (corner in Corner.entries) {
            val gated = corner == Corner.TL || corner == Corner.BR
            val strip = ArrayList<List<Bitmap>>()
            for (kind in NflScoreKind.entries) {
                val sc = scoresOf(kind)
                strip += times.map { t ->
                    render(takeLook(play, sc, t), large, corner, take = Take(t.toFloat(), sc)).bmp.also {
                        if (gated) save("nfl-stacked-score-${kind.name.lowercase()}-${corner.tag}-${sz(large)}-t$t", it)
                    }
                }
            }
            // A +7 pair (TD then XP): 216 / 1 500 / 3 600 (tally A pops, "EXTRA POINT") / 5 016 / 6 300 / 8 400 / 9 000.
            val pairScores = scoresOf(NflScoreKind.TOUCHDOWN, second = NflScoreKind.EXTRA_POINT)
            strip += listOf(216, 1_500, 3_600, 5_016, 6_300, 8_400, 9_000).map { t ->
                render(takeLook(play, pairScores, t), large, corner, take = Take(t.toFloat(), pairScores)).bmp
                    .also { if (gated) save("nfl-stacked-score-pair-${corner.tag}-${sz(large)}-t$t", it) }
            }
            sheet("nfl-stacked-takeover-strip-${sz(large)}-${corner.tag}", strip)
        }
        // The still frames A / B of a single score (1 000, 4 000) and of the pair (A2 6 000, B2 9 000), and a takeover
        // pinned at the 243 break width (the bar re-keys to the score label once the lead covers, as the runner does).
        val stills = listOf(1_000, 4_000, 6_000, 9_000).map { t ->
            val sc = if (t > 4_800) scoresOf(NflScoreKind.TOUCHDOWN, second = NflScoreKind.EXTRA_POINT) else scoresOf(NflScoreKind.TOUCHDOWN)
            render(takeLook(play, sc, t, still = true), false, Corner.TL, take = Take(t.toFloat(), sc, still = true)).bmp
                .also { save("nfl-stacked-score-still-tl-std-t$t", it) }
        }
        val td = scoresOf(NflScoreKind.TOUCHDOWN)
        val breakRow = listOf(450, 1_500).map { t ->
            render(takeLook(half, td, t), false, Corner.TL, take = Take(t.toFloat(), td)).bmp.also { save("nfl-stacked-score-td-break-tl-std-t$t", it) }
        }
        sheet("nfl-stacked-takeover-still-break-sheet", listOf(stills, breakRow))
        assertTrue(File(review, "nfl-stacked-takeover-strip-std-tl.png").length() > 10_000)
        // U-S1: what the runner shows, from its schedule: the pair's A2 is "EXTRA POINT | KC +1" over 31; B2 is released.
        val a2 = takeLook(play, scoresOf(NflScoreKind.TOUCHDOWN, second = NflScoreKind.EXTRA_POINT), 6_000, still = true)
        assertEquals(kit.scoreKey(Side.AWAY, NflScoreKind.EXTRA_POINT, 1), a2.bar)
        assertEquals(31, a2.view.away.score)
        assertNull(takeLook(play, scoresOf(NflScoreKind.TOUCHDOWN, second = NflScoreKind.EXTRA_POINT), 9_000, still = true).bar)
        // On the 243 break box the pinned label is the word alone (§2.4: the sub needs the 333 bar).
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN), takeLook(half, td, 450).bar)
        assertEquals(kit.scoreKey(Side.AWAY, NflScoreKind.TOUCHDOWN, 6), takeLook(play, td, 450).bar)
    }

    // ------------------------------------------------------------------ assertions on the drawn pixels

    @Test
    fun theBarBackgroundIsTheContentsColourAndItsWordsPassContrast() {
        for (look in looks.filter { kit.targets(it.view).bar != null && it.prev == null && !it.name.startsWith("open") }) for (corner in Corner.entries) {
            val r = std
            val shot = render(look, false, corner)
            val b = shot.content!!
            val w = shot.f.boxW * r.s
            val l = if (corner.mirrored) K.STACKED_ROWS_W * r.s - w else 0f
            // The bar's far end from its words: the right end unmirrored (on the red patch in the red zone), the left
            // end mirrored (the words are right-aligned, the patch runs to the right end, under the stripes).
            val x = if (corner.mirrored) l + 3f else l + w - 3f
            val y = if (corner.up) -K.STACKED_BAR_H * r.s / 2f else K.STACKED_H * r.s + K.STACKED_BAR_H * r.s / 2f
            val px = shot.bmp.getPixel((x + shot.ox).toInt(), (y + shot.oy).toInt())
            val want = if (b.redZone && !corner.mirrored) NflStackedColours.RED_ZONE_SUB else b.bg
            assertTrue("${look.name} ${corner.tag}: bar bg ${Integer.toHexString(px)} vs ${Integer.toHexString(want)}", kit.near(want, px, 3))
            val min = 4.5
            assertTrue("${look.name}: words ${contrast(b.fg, b.bg)} on the bar", contrast(b.fg or (0xFF shl 24), b.bg or (0xFF shl 24)) >= min)
            if (b.redZone) assertTrue("${look.name}: words on red", contrast(b.fg or (0xFF shl 24), NflStackedColours.RED_ZONE_SUB) >= min)
        }
        assertTrue(contrast(Color.WHITE, K.STACKED_ROW_BG) >= 7.0)
        assertTrue(contrast(K.STACKED_NFL_FLAG_FG, K.STACKED_NFL_FLAG_BG) >= 4.5)
    }

    @Test
    fun theRowsCodeIsWhiteOnNavyFromTheDrawnPixels() {
        val shot = render(looks.first(), false, Corner.TL)
        var brightest = 0
        val x0 = (81f + shot.ox).toInt()
        for (y in (12 + shot.oy.toInt())..(36 + shot.oy.toInt())) for (x in x0..(x0 + 30)) {
            val p = shot.bmp.getPixel(x, y)
            if (lum(p) > lum(brightest)) brightest = p
        }
        assertTrue("code ink ${Integer.toHexString(brightest)}", contrast(brightest, K.STACKED_ROW_BG) >= 7.0)
    }

    @Test
    fun theTakeoverCoversTheTopPartButNeverTheBorder() {
        // Integration ruling 1 (the hockey order): at 1 000 the panel covers the top part, and the 1-px border is on top.
        for (corner in Corner.entries) {
            val fg = scoresOf(NflScoreKind.FIELD_GOAL)
            val shot = render(takeLook(looks.first().view, fg, 1_000), false, corner, take = Take(1_000f, fg))
            val w = shot.f.boxW
            val l = if (corner.mirrored) K.STACKED_ROWS_W - w else 0f
            val top = if (corner.up) -K.STACKED_BAR_H else 0f
            val edge = shot.bmp.getPixel((shot.ox + l + w / 2f).toInt(), (shot.oy + top + 0.3f).toInt())
            assertTrue("${corner.tag}: the border's colour on the top edge ${Integer.toHexString(edge)}", kit.near(K.STACKED_BORDER, edge, 40))
            // The glow is drawn around the drawn box (device px, mirrored x, bar-up y): tinted just outside every edge.
            val bottom = if (corner.up) K.STACKED_H else K.STACKED_H + K.STACKED_BAR_H
            val bgAt = { x: Float, y: Float -> render(looks.first(), false, corner).bmp.getPixel((shot.ox + x).toInt(), (shot.oy + y).toInt()) }
            for ((x, y) in listOf(l + w / 2f to top - 4f, l + w / 2f to bottom + 4f, l - 4f to K.STACKED_H / 2f, l + w + 4f to K.STACKED_H / 2f)) {
                val glow = shot.bmp.getPixel((shot.ox + x).toInt(), (shot.oy + y).toInt())
                assertTrue("${corner.tag}: the glow at ($x, $y) ${Integer.toHexString(glow)}", !kit.near(bgAt(x, y), glow, 4))
            }
        }
    }

    @Test
    fun noBallPostOrStreakPixelLeavesTheTopPart() {
        for (large in listOf(false, true)) for (mirrored in listOf(false, true)) for (kind in NflScoreKind.entries) {
            val r = rig(large)
            val boxW = K.STACKED_NFL_W_MAX
            r.score.begin(Side.AWAY, listOf(NflScore(kind, 6, 30, 17)), kit.KC.goal, null, "KC", "BUF", boxW)
            r.score.extent(0f, K.STACKED_H * r.s)
            var t = 0f
            while (t <= 3_600f) {
                val bmp = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val ox = r.m + if (mirrored) (K.STACKED_NFL_W_MAX - K.STACKED_ROWS_W) * r.s else 0f
                c.translate(ox, r.m)
                r.score.drawTakeover(c, kit.score(t, kind), mirrored)
                val left = (r.m - 0.5f).toInt()
                val right = Math.round(r.m + boxW * r.s)
                val bottom = Math.round(r.m + K.STACKED_H * r.s)
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                    if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
                    val inside = x >= left && x <= right && y >= (r.m - 1).toInt() && y <= bottom
                    assertTrue("$kind t=$t ${sz(large)} m=$mirrored: pixel at ($x, $y) outside the top part", inside)
                }
                t += 300f
            }
        }
    }

    @Test
    fun theBarWordsAreTheDesignsWords() {
        val r = std
        fun words(v: ScoreBugView) = NflBarContent().also { r.painter.fillBar(it, kit.targets(v).bar!!, kit.pair(v.away.abbrev, v.home.abbrev), v.away.abbrev, v.home.abbrev, res) }
            .let { r.painter.barText(it) }
        fun words(key: NflBarKey, away: String = "KC", home: String = "BUF") =
            NflBarContent().also { r.painter.fillBar(it, key, kit.pair(away, home), away, home, res) }.let { r.painter.barText(it) }
        assertEquals("1ST & 10 | KC 35", words(kit.view()))
        assertEquals("1ST & 10 | BUF 35", words(kit.view(tab = tab(Side.AWAY, field(FieldZone.OPP, 35)))))
        assertEquals("4TH & GOAL | RED ZONE", words(kit.view(down = down(4, null, true), tab = tab(Side.HOME, TabTail.RedZone))))
        assertEquals("3RD | KC 35", words(kit.view(down = down(3, null))))
        assertEquals("2ND & 5 | 50", words(kit.view(down = down(2, 5), tab = tab(Side.AWAY, field(FieldZone.MIDFIELD, 50)))))
        assertEquals("3RD & 7", words(kit.view(down = down(3, 7), tab = null)))
        // C-N4: the neutral bar keeps the field ("same words"), named by its territory.
        assertEquals("3RD & 7 | BUF 35", words(kit.view(down = down(3, 7), tab = null, field = app.thebox.core.sports.FieldPos(Side.HOME, 35))))
        assertEquals("3RD & 7 | 50", words(kit.view(down = down(3, 7), tab = null, field = app.thebox.core.sports.FieldPos(null, 50))))
        // C-S1: the held bar on the dead ball after a field goal.
        assertEquals("4TH & 3 | KC 20", words(kit.view(down = null, heldDown = down(4, 3), tab = tab(Side.HOME, field(FieldZone.OPP, 20)))))
        assertEquals("EXTRA POINT", words(kit.view(down = DownText.Pat, tab = tab(Side.AWAY, null))))
        assertEquals("EXTRA POINT", words(kit.view(down = DownText.Pat, tab = tab(Side.AWAY, TabTail.ExtraPoint))))
        // Owner ruling 2026-11-16: a try drawn as a real goal-to-go down inside the 5.
        assertEquals("2-PT TRY", words(kit.view(down = DownText.Pat, tab = tab(Side.AWAY, TabTail.TwoPointTry))))
        assertEquals("LV KICKOFF | LV 35", words(kit.view("SEA", "LV", down = DownText.Kickoff, tab = tab(Side.HOME))))
        // RC9: a kick-off never reads RED ZONE, through the real table.
        assertEquals("LV KICKOFF", words(kit.view("SEA", "LV", down = DownText.Kickoff, tab = tab(Side.HOME, TabTail.RedZone))))
        assertEquals("FLAG ON THE PLAY", words(flagView))
        assertEquals("TIMEOUT BUF | 1 LEFT", words(timeoutView))
        assertEquals("TIMEOUT BUF | 0 LEFT", words(kit.view(homeTimeouts = 0, timeout = NflTimeoutMark(Side.HOME, 0, true))))
        assertEquals("END OF 1ST", words(kit.endOf(p1)))
        assertEquals("END OF 4TH", words(kit.endOf(PeriodLabel(PeriodKind.REG, 4))))
        assertEquals("END OF OT", words(kit.endOf(PeriodLabel(PeriodKind.OT, 1))))
        assertEquals("HALFTIME", words(kit.halftime()))
        assertEquals("FINAL", words(kit.final()))
        assertEquals("FINAL/OT", words(kit.final(PeriodKind.OT)))
        assertEquals("NO UPDATE", words(kit.staleInBreak()))
        assertEquals("DELAYED", words(kit.delayedInBreak()))
        assertEquals("TOUCHDOWN | KC +6", words(kit.scoreKey(Side.AWAY, NflScoreKind.TOUCHDOWN, 6)))
        assertEquals("EXTRA POINT", words(NflBarKey(NflBarKind.PAT, Side.AWAY)))
        assertEquals("2-PT TRY", words(NflBarKey(NflBarKind.PAT, Side.AWAY, tail = TabTail.TwoPointTry)))
        assertEquals("TWO-POINT | BUF +2", words(kit.scoreKey(Side.HOME, NflScoreKind.TWO_POINT, 2)))
        // Colours: the one rule, `NflStackedColours.bar`.
        val pair = kit.pair("KC", "BUF")
        val c = NflBarContent()
        for (v in listOf(kit.view(tab = tab(Side.HOME)), kit.view(tab = null), flagView, kit.halftime(), timeoutView)) {
            val key = kit.targets(v).bar!!
            r.painter.fillBar(c, key, pair, "KC", "BUF", res)
            val want = NflStackedColours.bar(key, pair)
            assertEquals(listOf(want.bg, want.a, want.b, want.fg), listOf(c.bg, c.a, c.b, c.fg))
        }
    }

    // ------------------------------------------------------------------ NFL YDS (docs/design/m6-nfl-stacked-yds.md §4.6)

    /**
     * (NFL YDS WP-2) One look of the YDS column: [view] is the body the rows and columns show; [layout] the view whose
     * targets lay the frame out (the pin's pre-score layout; default [view]); [frame] may move tracks into a transition;
     * [bar] overrides the bar key (the pinned score label); [prev] is the bar a REKEY leaves; [lastClock] / [lastYds]
     * are the last bodies with the clock / YDS column open, merged as the runner's `NflBodyMerge` does.
     */
    private class YLook(
        val name: String,
        val view: ScoreBugView,
        val layout: ScoreBugView = view,
        val frame: (StackedNflTargets, StackedNflFrame) -> StackedNflFrame = { _, f -> f },
        val bar: NflBarKey? = null,
        val prev: ScoreBugView? = null,
        val lastClock: ScoreBugView? = null,
        val lastYds: ScoreBugView? = null,
    )

    /** The YDS shots' layer: the 396 footprint (`STACKED_NFL_FOOTPRINT_W`) + 2 M; mirrored, the rows hug its right edge. */
    private fun renderYds(look: YLook, large: Boolean, corner: Corner, take: Take? = null, still: Boolean = false): Shot {
        val r = rig(large)
        val lw = Math.round(K.STACKED_NFL_FOOTPRINT_W * r.s + 2 * r.m)
        val bmp = Bitmap.createBitmap(lw, r.layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, lw.toFloat(), r.layerH.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, lw.toFloat(), r.layerH.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
        })
        val away = look.view.away.abbrev
        val home = look.view.home.abbrev
        val pair = NflStackedColours.pair(away, home)
        val lt = kit.targets(look.layout)
        val f = look.frame(lt, kit.rest(lt))
        val body = r.painter.frame(look.view, kit.targets(look.view), res)
        val lc = look.lastClock?.let { r.painter.frame(it, kit.targets(it), res) }
        val ly = look.lastYds?.let { r.painter.frame(it, kit.targets(it), res) }
        val a = StackedNflDrawArgs()
        a.state = NflBodyMerge().of(body, f, lc, ly)
        a.frame = f
        a.colours = pair
        a.bar = content(r, look.bar ?: lt.bar, lt.barDim, pair, away, home)
        a.barPrev = look.prev?.let { content(r, kit.targets(it).bar, false, pair, away, home) }
        a.awayLogo = r.logo(away)
        a.homeLogo = r.logo(home)
        a.mirrored = corner.mirrored
        a.up = corner.up
        a.still = take?.still ?: still
        a.shadow = r.shadow
        if (take != null) {
            val sc = if (take.scorer == Side.AWAY) pair.away else pair.home
            r.score.begin(take.scorer, listOf(take.scores[0]), sc.goal, r.logo(if (take.scorer == Side.AWAY) away else home), away, home, f.boxW)
            a.takeover = r.score
            a.glow = r.glow(f.boxW, K.STACKED_H + if (f.barClosed) 0f else f.barH)
            a.glowOn = true
            a.score = kit.score(take.t, take.kind, take.second, take.still)
            a.scoreSide = take.scorer
            a.takeoverOn = true
        }
        val ox = r.m + if (corner.mirrored) (K.STACKED_NFL_FOOTPRINT_W - K.STACKED_ROWS_W) * r.s else 0f
        val oy = r.m + if (corner.up) K.STACKED_BAR_H * r.s else 0f
        c.translate(ox, oy)
        r.painter.drawFrame(c, a)
        return Shot(bmp, ox, oy, a.bar, f)
    }

    private fun yds(v: ScoreBugView, away: Int, home: Int) = kit.yds(v, away, home)
    private val bufTimeout2 = NflTimeoutMark(Side.HOME, 2, confirmed = true)
    /** TIMEOUT BUF | 2 LEFT, 142 / 51 (fake panel "q2 BUF ball"). */
    private val ydsTimeout = yds(kit.view(homeTimeouts = 2, timeout = bufTimeout2), 142, 51)
    /** An official timeout (S2): the play bar "1ST & 10 | BUF 25" unchanged, YDS beside it; 41 / 0. */
    private val ydsOfficial = yds(kit.view(tab = kit.tab(Side.HOME, field(FieldZone.OWN, 25))), 41, 0)
    private val ydsHalftime = yds(kit.halftime(), 168, 97)
    private val ydsEnd1st = yds(kit.endOf(p1), 75, -2)
    private val ydsFinal = yds(kit.final(), 389, 312)
    private val ydsStaleHalftime = yds(kit.staleInBreak(), 168, 97)
    /** KC's kick-off after an official timeout (RC9 shape), 7 / −2. */
    private val ydsKickoff = yds(kit.view(down = DownText.Kickoff, tab = kit.tab(Side.AWAY, field(FieldZone.OWN, 35))), 7, -2)
    private val ydsExtremes = yds(kit.view(homeTimeouts = 2, timeout = bufTimeout2), 999, -99)

    /** [t]'s rest frame with the YDS track closed (the frame a late OPEN starts from). */
    private fun ydsClosed(t: StackedNflTargets) =
        kit.rest(t).also { kit.at(it, StackedNflTrack.YDS, StackedNflTransition.STILL, 0f, StackedNflFrame(), to = false) }

    private val ydsOpenAt = { ms: Float -> { t: StackedNflTargets, f: StackedNflFrame -> kit.at(f, StackedNflTrack.YDS, StackedNflTransition.OPEN, ms, ydsClosed(t)) } }
    private val ydsCloseAt = { ms: Float -> { t: StackedNflTargets, f: StackedNflFrame -> kit.at(f, StackedNflTrack.YDS, StackedNflTransition.CLOSE, ms, kit.rest(t), to = false) } }

    /** The field goal after BUF's "icing" timeout (S9): KC 27 – 17, laid out on the pinned 396 box at [t]. */
    private fun ydsFieldGoal(t: Int): Pair<YLook, Take> {
        val fg = NflScore(NflScoreKind.FIELD_GOAL, 3, 27, 17)
        val scored = kit.view(awayScore = 27, homeTimeouts = 2)
        val pre = ydsTimeout
        val pinW = nflRestBoxW(kit.targets(pre).ydsOpen, kit.targets(pre).clockOpen)
        val body = when (StackedNflPinSchedule.body(t.toFloat(), false, false, kit.SPEC)) {
            StackedNflPinSchedule.Body.PRE_SCORE -> pre
            else -> scored
        }
        val key = StackedNflPinSchedule.barKey(Side.AWAY, fg, null, t.toFloat(), false, kit.targets(pre).bar != null, pinW, kit.SPEC)
        return YLook("fg-$t-pinned-396", body, layout = pre, bar = key, lastYds = pre) to Take(t.toFloat(), listOf(fg))
    }

    /** Play → halftime with yards at [ms]: the clock folds, the bar re-keys, the ball goes and YDS opens, together (§3.5). */
    private fun playToHalftime(ms: Float): YLook {
        val play = kit.view()
        val from = kit.rest(kit.targets(play))
        return YLook("play-to-halftime-t${ms.toInt()}", ydsHalftime, frame = { _, f ->
            kit.at(f, StackedNflTrack.CLOCK, StackedNflTransition.CLOSE, ms, from, to = false)
            kit.at(f, StackedNflTrack.BAR, StackedNflTransition.REKEY, ms, from)
            kit.at(f, StackedNflTrack.STRIPE_AWAY, StackedNflTransition.CLOSE, ms, from, to = false)
            kit.at(f, StackedNflTrack.BALL_AWAY, StackedNflTransition.CLOSE, ms, from, to = false)
            kit.at(f, StackedNflTrack.YDS, StackedNflTransition.OPEN, ms, from)
        }, prev = play, lastClock = play)
    }

    private val ydsRest = listOf(
        YLook("timeout", ydsTimeout), YLook("official-timeout", ydsOfficial), YLook("halftime", ydsHalftime),
        YLook("end-1st-negative", ydsEnd1st), YLook("final-3-digit", ydsFinal), YLook("stale-halftime", ydsStaleHalftime),
    )

    /**
     * (NFL YDS §4.6) The 26 hashed `yds-*` shots under `score-bug-stacked-nfl/`, the contact sheets of the yards states
     * (Standard and Large; TL, TR mirrored, BR) and the frame strips of the column opening and of play → halftime.
     */
    @Test
    fun theYdsColumnShotsSheetsAndStrips() {
        fun shot(name: String, b: Bitmap) = save("yds-$name", b)
        var n = 0
        for (look in ydsRest) for (corner in listOf(Corner.TL, Corner.BR)) { shot("${look.name}-${corner.tag}-std", renderYds(look, false, corner).bmp); n++ }
        for (look in ydsRest.filter { it.name == "timeout" || it.name == "halftime" }) for (corner in listOf(Corner.TL, Corner.BR)) {
            shot("${look.name}-${corner.tag}-large", renderYds(look, true, corner).bmp); n++
        }
        for (corner in listOf(Corner.TR, Corner.BL)) { shot("timeout-${corner.tag}-std", renderYds(ydsRest[0], false, corner).bmp); n++ }
        for (corner in listOf(Corner.TL, Corner.BR)) {
            shot("open-t225-${corner.tag}-std", renderYds(YLook("open-t225", ydsTimeout, frame = ydsOpenAt(225f)), false, corner).bmp); n++
            shot("close-t425-${corner.tag}-std", renderYds(YLook("close-t425", ydsTimeout, frame = ydsCloseAt(425f)), false, corner).bmp); n++
        }
        val (fgLook, fgTake) = ydsFieldGoal(1_500)
        for ((large, corner) in listOf(false to Corner.TL, false to Corner.BR, true to Corner.TL)) {
            shot("fg-1500-pinned-396-${corner.tag}-${sz(large)}", renderYds(fgLook, large, corner, take = fgTake).bmp); n++
        }
        // Reduce animations: the STILL transition puts the whole column up at once (here 225 ms in: no partial width).
        val still = YLook("timeout-still", ydsTimeout, frame = { t, f -> kit.at(f, StackedNflTrack.YDS, StackedNflTransition.STILL, 225f, ydsClosed(t), to = true) })
        shot("timeout-still-tl-std", renderYds(still, false, Corner.TL, still = true).bmp); n++
        assertEquals("§4.6: 26 shots", 26, n)

        // The contact sheets: every yards state (1-, 2-, 3-digit and negative values) beside the v1 play / halftime looks.
        val states = listOf(
            YLook("timeout", ydsTimeout), YLook("official-timeout", ydsOfficial), YLook("kickoff", ydsKickoff),
            YLook("halftime", ydsHalftime), YLook("end-1st", ydsEnd1st), YLook("final", ydsFinal),
            YLook("stale-halftime", ydsStaleHalftime), YLook("extremes", ydsExtremes),
            YLook("v1-play", kit.view()), YLook("v1-halftime", kit.halftime()),
        )
        for (large in listOf(false, true)) for (corner in listOf(Corner.TL, Corner.TR, Corner.BR)) {
            sheet("yds-states-${sz(large)}-${corner.tag}", states.map { renderYds(it, large, corner).bmp }.chunked(4), kit.YDS_SHEETS)
        }
        // The frame strips: the column opening (late yards on a timeout) and play → halftime, TL and BR, Standard.
        val openTimes = listOf(0, 75, 150, 225, 300, 375, 450, 525, 600, 650)
        val halfTimes = listOf(0, 100, 200, 300, 350, 400, 450, 550, 650, 800)
        val strip = listOf(Corner.TL, Corner.BR).flatMap { corner ->
            listOf(
                openTimes.map { renderYds(YLook("open", ydsTimeout, frame = ydsOpenAt(it.toFloat())), false, corner).bmp },
                halfTimes.map { renderYds(playToHalftime(it.toFloat()), false, corner).bmp },
            )
        }
        sheet("yds-strip-open-and-play-to-halftime-std", strip, kit.YDS_SHEETS)
        // The field goal pinned at 396 through the takeover, TL and BR.
        val fgTimes = listOf(216, 450, 1_000, 1_500, 2_025, 3_000)
        sheet("yds-strip-fg-pinned-396-std", listOf(Corner.TL, Corner.BR).map { corner ->
            fgTimes.map { t -> ydsFieldGoal(t).let { (l, tk) -> renderYds(l, false, corner, take = tk).bmp } }
        }, kit.YDS_SHEETS)
        assertTrue(File(review, "yds-states-std-tl.png").length() > 10_000)
        assertFalse("the sheets are not in the gated folder", File(out, "yds-states-std-tl.png").exists())
    }

    /** (NFL YDS §3.1–§3.3) The drawn column: digits in it, the clock column after it, mirrored `[clock][YDS][rows]`. */
    @Test
    fun theYdsColumnIsDrawnBetweenTheRowsAndTheClockOnBothSides() {
        fun bright(shot: Shot, x0: Float, x1: Float, y0: Float, y1: Float): Int {
            var n = 0
            for (y in (shot.oy + y0).toInt() until (shot.oy + y1).toInt()) for (x in (shot.ox + x0).toInt() until (shot.ox + x1).toInt()) {
                val p = shot.bmp.getPixel(x, y)
                if (Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200) n++
            }
            return n
        }
        for (corner in Corner.entries) {
            val shot = renderYds(YLook("timeout", ydsTimeout), false, corner)
            assertEquals("${corner.tag}: the 396 box", K.STACKED_NFL_FOOTPRINT_W, shot.f.boxW, 1e-3f)
            // Box-local → rows-origin x: mirrored the box spans [243 − 396, 243].
            val l = if (corner.mirrored) K.STACKED_ROWS_W - K.STACKED_NFL_FOOTPRINT_W else 0f
            val ydsL = l + G.ydsX(K.STACKED_NFL_FOOTPRINT_W, K.STACKED_NFL_YDS_W, corner.mirrored)
            val clockL = l + G.clockX(K.STACKED_NFL_FOOTPRINT_W, K.STACKED_CLOCK_W, K.STACKED_NFL_YDS_W, corner.mirrored)
            assertEquals(if (corner.mirrored) -K.STACKED_NFL_YDS_W else K.STACKED_ROWS_W, ydsL, 1e-3f)
            assertEquals(if (corner.mirrored) -K.STACKED_NFL_YDS_W - K.STACKED_CLOCK_W else K.STACKED_ROWS_W + K.STACKED_NFL_YDS_W, clockL, 1e-3f)
            val rowH = K.STACKED_H / 2f
            assertTrue("${corner.tag}: \"142\" in the away row", bright(shot, ydsL + 4f, ydsL + 59f, 14f, rowH - 14f) > 20)
            assertTrue("${corner.tag}: \"51\" in the home row", bright(shot, ydsL + 4f, ydsL + 59f, rowH + 14f, K.STACKED_H - 14f) > 20)
            assertTrue("${corner.tag}: the clock digits after the column", bright(shot, clockL + 8f, clockL + 82f, K.STACKED_NFL_PERIOD_SLOT_H + 4f, K.STACKED_H - 4f) > 20)
            // On the rows' line only the grey "YDS" label (`STACKED_SOG_LABEL`), no white digit ink (§3.1 clearances).
            assertEquals("${corner.tag}: the label band", 0, bright(shot, ydsL + 2f, ydsL + 61f, rowH - 2.5f, rowH + 2.5f))
            // The column's background between the label and the digits: the box fill.
            val bg = shot.bmp.getPixel((shot.ox + ydsL + 4f).toInt(), (shot.oy + rowH / 2f).toInt())
            assertTrue("${corner.tag}: column fill ${Integer.toHexString(bg)}", kit.near(K.STACKED_COL_BG, bg, 6))
        }
        // Halftime (306): the clock folded, the column beside the rows.
        val half = renderYds(YLook("halftime", ydsHalftime), false, Corner.TL)
        assertEquals(306f, half.f.boxW, 1e-3f)
        // The play look without yards keeps its 333 box (the v1 pixels: the same frame as the hashed `play` look).
        assertEquals(K.STACKED_NFL_W_MAX, renderYds(YLook("play", kit.view()), false, Corner.TL).f.boxW, 1e-3f)
    }
}
