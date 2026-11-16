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
 * PNGs under `ui/build/score-bug-stacked-nfl/` (hashed by `:ui:sportsHashGate`), contact sheets also to
 * [StackedNflTestKit.SHEETS]. Asserted: the bar background is the content's colour where drawn, the words pass contrast
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

    private inner class Rig(val large: Boolean) {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val painter = StackedNflPainter(s, large, bold, italic)
        val score = StackedNflScorePainter(s, large, barlow, bold, StackedNflTestKit.SPEC)
        val shadow = painter.bakeShadow()!!
        val logoPx = Math.round(STACKED_NFL_BADGE * s)
        val logos = HashMap<String, Bitmap>()
        fun logo(code: String) = logos.getOrPut(code) { StackedNflTestKit.logo(code, logoPx) }
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
        Look("play-neutral", kit.view(down = down(3, 7), tab = null)),
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
    )

    private fun content(r: Rig, key: NflBarKey?, dim: Boolean, pair: StackedPair, away: String, home: String): NflBarContent? =
        key?.let { NflBarContent().also { c -> c.dim = dim; r.painter.fillBar(c, it, pair, away, home, res) } }

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float, val content: NflBarContent?, val f: StackedNflFrame)

    /** A takeover over [render]'s look: [kind] (and a pair's [second]) at [t] by [scorer]. */
    private class Take(val t: Float, val kind: NflScoreKind, val second: NflScoreKind? = null, val still: Boolean = false, val scorer: Side = Side.AWAY)

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
            val awayN = stt.awayScore.toIntOrNull() ?: 0
            val homeN = stt.homeScore.toIntOrNull() ?: 0
            val g = kit.score(take.t, take.kind, take.second, take.still)
            val kind = if (g.segment == 1 && take.second != null) take.second else take.kind
            r.score.begin(take.scorer, listOf(NflScore(kind, POINTS.getValue(kind), awayN, homeN)), sc.goal,
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

    private fun sheet(name: String, cells: List<List<Bitmap>>) {
        val cw = cells.flatten().maxOf { it.width }
        val ch = cells.flatten().maxOf { it.height }
        val cols = cells.maxOf { it.size }
        val b = Bitmap.createBitmap(cols * cw, cells.size * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { row, list -> list.forEachIndexed { col, bmp -> c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null) } }
        save(name, b)
        runCatching {
            kit.SHEETS.mkdirs()
            File(kit.SHEETS, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    @Test
    fun everyStateInEveryCornerAtBothSizesAndTheContactSheets() {
        for (large in listOf(false, true)) {
            for (corner in Corner.entries) {
                val shots = looks.map { look -> render(look, large, corner).bmp.also { save("nfl-stacked-${look.name}-${corner.tag}-${sz(large)}-dark", it) } }
                sheet("nfl-stacked-contact-sheet-${sz(large)}-dark-${corner.tag}", shots.chunked(4))
            }
            for ((bgName, bg) in listOf("ice" to Color.WHITE, "grey" to GREY)) {
                val shots = looks.take(16).flatMap { look ->
                    listOf(Corner.TL, Corner.TR).map { corner ->
                        render(look, large, corner, bg).bmp.also { save("nfl-stacked-${look.name}-${corner.tag}-${sz(large)}-$bgName", it) }
                    }
                }
                sheet("nfl-stacked-contact-sheet-${sz(large)}-$bgName", shots.chunked(4))
            }
        }
        assertTrue(File(out, "nfl-stacked-contact-sheet-std-dark-tl.png").length() > 10_000)
    }

    private val times = listOf(0, 216, 450, 1_000, 1_500, 2_025, 3_600, 4_200)

    /** The look a takeover of [kind] by [scorer] at [t] draws over: the held pre-score body until 216, then the scored one. */
    private fun takeLook(kind: NflScoreKind, t: Int, scorer: Side = Side.AWAY): Look {
        if (t < 216) return looks.first { it.name == "play" }
        val v = if (scorer == Side.AWAY) kit.view(awayScore = 24 + POINTS.getValue(kind)) else kit.view(homeScore = 17 + POINTS.getValue(kind))
        return Look("td", v, bar = kit.scoreKey(scorer, kind, POINTS.getValue(kind)))
    }

    @Test
    fun theTakeoverForEveryKindAtTheDesignTimesThePairAndTheStillFrames() {
        val half = looks.first { it.name == "halftime" }
        for (large in listOf(false, true)) for (corner in Corner.entries) {
            val strip = ArrayList<List<Bitmap>>()
            for (kind in NflScoreKind.entries) {
                strip += times.map { t ->
                    render(takeLook(kind, t), large, corner, take = Take(t.toFloat(), kind)).bmp.also {
                        save("nfl-stacked-score-${kind.name.lowercase()}-${corner.tag}-${sz(large)}-t$t", it)
                    }
                }
            }
            // A +7 pair (TD then XP): 0 / 216 / 1 500 / 3 600 (tally A pops, "EXTRA POINT") / 5 016 / 6 300 / 8 400 / 9 000.
            strip += listOf(0, 216, 1_500, 3_600, 5_016, 6_300, 8_400, 9_000).map { t ->
                val bar = when {
                    t < 216 -> null
                    t < 3_600 -> kit.scoreKey(Side.AWAY, NflScoreKind.TOUCHDOWN, 6)
                    t < 5_016 -> NflBarKey(NflBarKind.PAT, Side.AWAY)
                    else -> kit.scoreKey(Side.AWAY, NflScoreKind.EXTRA_POINT, 1)
                }
                val v = kit.view(awayScore = if (t < 216) 24 else if (t < 5_016) 30 else 31)
                render(Look("pair", v, bar = bar), large, corner, take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN, NflScoreKind.EXTRA_POINT)).bmp
                    .also { save("nfl-stacked-score-pair-${corner.tag}-${sz(large)}-t$t", it) }
            }
            sheet("nfl-stacked-takeover-strip-${sz(large)}-${corner.tag}", strip)
        }
        // The still frames A / B of a single score and of the pair, and a takeover pinned at the 243 break width.
        val stills = listOf(1_000, 4_000, 6_000, 9_000).map { t ->
            render(Look("still", kit.view(awayScore = 30), bar = kit.scoreKey(Side.AWAY, NflScoreKind.TOUCHDOWN, 6)), false, Corner.TL,
                take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN, if (t > 4_800) NflScoreKind.EXTRA_POINT else null, still = true)).bmp
                .also { save("nfl-stacked-score-still-tl-std-t$t", it) }
        }
        val breakRow = listOf(450, 1_500).map { t ->
            render(half, false, Corner.TL, take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN)).bmp.also { save("nfl-stacked-score-td-break-tl-std-t$t", it) }
        }
        sheet("nfl-stacked-takeover-still-break-sheet", listOf(stills, breakRow))
        assertTrue(File(out, "nfl-stacked-takeover-strip-std-tl.png").length() > 10_000)
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
            val shot = render(takeLook(NflScoreKind.FIELD_GOAL, 1_000), false, corner, take = Take(1_000f, NflScoreKind.FIELD_GOAL))
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
        assertEquals("EXTRA POINT", words(kit.view(down = DownText.Pat, tab = tab(Side.AWAY, null))))
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
}
