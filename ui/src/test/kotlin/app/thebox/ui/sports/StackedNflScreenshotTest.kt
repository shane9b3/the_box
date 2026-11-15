package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TabTail
import app.thebox.ui.R
import app.thebox.ui.sports.StackedNflTestKit.DARK
import app.thebox.ui.sports.StackedNflTestKit.GREY
import app.thebox.ui.sports.StackedNflTestKit.TRANSPARENT
import app.thebox.ui.sports.StackedNflTestKit.field
import app.thebox.ui.sports.StackedNflTestKit.play
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
 * NFL Stacked §9.2 `StackedNflScreenshotTest` (WP-3): the box drawn through the real [StackedNflPainter.drawFrame] (and
 * through it [StackedNflScorePainter] and [NineSlice]) with the bundled fonts, from **hand-built** frames
 * ([StackedNflTestKit]; re-point at integration): every §3.4 state for KC @ BUF, the dark-bar matchups IND @ NE and
 * DAL @ NYG, BUF–NE, DAL @ PHI, LV–PIT and CIN–DEN; the four corners × Standard / Large over the dark gradient, and the
 * top corners over white ice and 50 % grey; the takeover for each kind at 0 / 216 / 450 / 1 000 / 1 500 / 2 025 /
 * 3 600 / 4 200, a +7 pair at 3 600 / 5 016 / 8 400, the still frames; the flag at 150 / 400; the burn at 200 / 1 600.
 *
 * PNGs under `ui/build/score-bug-stacked-nfl/` (not yet goldens: the lead appends them after the owner's review), plus a
 * contact sheet for the lead in [StackedNflTestKit.SHEETS]. Asserted: the bar background is the content's colour where
 * drawn, white words on every team bar ≥ 4.5 : 1 and the flag words ≥ 4.5, the rows' white ≥ 7, and no ball, post or
 * streak pixel outside the top part.
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
        val logoPx = Math.round(58.5f * s)
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

    /** One look: the matchup, the state, the layout, the bar key (and the previous one during a REKEY). */
    private class Look(
        val name: String,
        val match: String = "KC-BUF",
        val state: (StackedNflPainter) -> StackedNflFrameState,
        val f: () -> StackedNflFrame,
        val bar: NflBarKey? = null,
        val barDim: Boolean = false,
        val prev: NflBarKey? = null,
    ) {
        val away get() = match.substringBefore('-')
        val home get() = match.substringAfter('-')
    }

    private val kit = StackedNflTestKit
    private val p1 = PeriodLabel(PeriodKind.REG, 1)
    private fun st(away: String = "KC", home: String = "BUF", a: Int? = 3, h: Int? = 2, slots: Int = 3): (StackedNflPainter) -> StackedNflFrameState =
        { p -> kit.state(p, away, home, targets = kit.targets(a, h, slots)) }

    private val looks: List<Look> = listOf(
        Look("play", state = st(), f = { kit.frame(poss = Side.AWAY) }, bar = play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))),
        Look("play-redzone", state = st(), f = { kit.frame(poss = Side.HOME) }, bar = play(Side.HOME, 4, null, goal = true, tail = TabTail.RedZone)),
        Look("play-neutral", state = st(), f = { kit.frame() }, bar = play(null, 3, 7)),
        Look("play-midfield", state = st(), f = { kit.frame(poss = Side.AWAY) }, bar = play(Side.AWAY, 2, 5, tail = field(FieldZone.MIDFIELD, 50))),
        Look("play-no-distance", state = st(), f = { kit.frame(poss = Side.AWAY) }, bar = play(Side.AWAY, 3, null, tail = field(FieldZone.OPP, 12))),
        Look("pat", state = st(), f = { kit.frame(poss = Side.AWAY) }, bar = NflBarKey(NflBarKind.PAT, Side.AWAY)),
        Look("kickoff", state = st(), f = { kit.frame(poss = Side.AWAY) }, bar = NflBarKey(NflBarKind.KICKOFF, Side.AWAY, tail = field(FieldZone.OWN, 35))),
        Look("flag-t150", state = st(), f = { kit.frame(poss = Side.AWAY).apply { shakeDx = kit.shake(150f); waveDeg = kit.wave(150f) } }, bar = NflBarKey(NflBarKind.FLAG, null)),
        Look("flag-t400", state = st(), f = { kit.frame(poss = Side.AWAY).apply { shakeDx = kit.shake(400f); waveDeg = kit.wave(400f) } }, bar = NflBarKey(NflBarKind.FLAG, null)),
        Look("timeout-burn-t200", state = st(h = 1), f = { kit.burn(kit.frame(poss = Side.AWAY, homeLit = 1), Side.HOME, 1, 200f) }, bar = NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1)),
        Look("timeout-burn-t1600", state = st(h = 1), f = { kit.burn(kit.frame(poss = Side.AWAY, homeLit = 1), Side.HOME, 1, 1_600f) }, bar = NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1)),
        Look("timeout", state = st(h = 1), f = { kit.frame(poss = Side.AWAY, homeLit = 1) }, bar = NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1)),
        Look("halftime", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.HALFTIME, null)),
        Look("end-of-1st", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.END_PERIOD, null, period = p1)),
        Look("final", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.REG)),
        Look("final-ot", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.OT)),
        Look("stale-play", state = { kit.state(it, slot = ClockSlot.NoUpdate, slotText = res.getString(R.string.sports_no_update), dim = true) }, f = { kit.frame(bar = 0f) }),
        Look("delayed-play", state = { kit.state(it, slot = ClockSlot.Delayed, slotText = res.getString(R.string.sports_delayed), dim = true) }, f = { kit.frame(bar = 0f) }),
        Look("stale-break", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.NO_UPDATE, null), barDim = true),
        Look("delayed-break", state = st(), f = { kit.frame(clock = 0f) }, bar = NflBarKey(NflBarKind.DELAYED, null), barDim = true),
        Look("no-situation", state = st(), f = { kit.frame(bar = 0f) }),
        Look("score-bar", state = { kit.state(it, awayScore = "30") }, f = { kit.frame(poss = Side.AWAY) }, bar = NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6)),
        Look("score-between-pair", state = { kit.state(it, awayScore = "30") }, f = { kit.frame(poss = Side.AWAY) }, bar = NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.EXTRA_POINT)),
        Look("rekey-t200", state = st(h = 1),
            f = { kit.frame(poss = Side.AWAY, homeLit = 1, text = 0.25f).apply { barBgMix = 0.55f } },
            bar = NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1), prev = play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))),
        Look("open-t200", state = st(), f = { kit.frame(poss = Side.AWAY, bar = 0.5f, text = 0f) }, bar = play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))),
        Look("three-digit", state = { kit.state(it, awayScore = "100", homeScore = "99") }, f = { kit.frame(poss = Side.HOME) }, bar = play(Side.HOME, 2, 8, tail = field(FieldZone.OPP, 41))),
        Look("ot-two-slots", state = { kit.state(it, period = "OT", targets = kit.targets(1, 2, 2)) }, f = { kit.frame(poss = Side.HOME, awayLit = 1, homeLit = 2) }, bar = play(Side.HOME, 1, 10, tail = field(FieldZone.OWN, 25))),
        Look("pips-hidden", state = { kit.state(it, targets = kit.targets(null, null, 0)) }, f = { kit.frame(poss = Side.AWAY) }, bar = play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))),
        // The dark-bar matchups (Rec 2) and the collision pairs.
        Look("ind-ne-play", "IND-NE", st("IND", "NE"), { kit.frame(poss = Side.AWAY) }, play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 25))),
        Look("ind-ne-redzone", "IND-NE", st("IND", "NE"), { kit.frame(poss = Side.HOME) }, play(Side.HOME, 2, 4, tail = TabTail.RedZone)),
        Look("dal-nyg-play", "DAL-NYG", st("DAL", "NYG"), { kit.frame(poss = Side.AWAY) }, play(Side.AWAY, 3, 6, tail = field(FieldZone.OPP, 44))),
        Look("dal-nyg-timeout", "DAL-NYG", st("DAL", "NYG", h = 0), { kit.frame(poss = Side.AWAY, homeLit = 0) }, NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 0)),
        Look("buf-ne-play", "BUF-NE", st("BUF", "NE"), { kit.frame(poss = Side.HOME) }, play(Side.HOME, 1, 10, tail = field(FieldZone.OWN, 20))),
        Look("dal-phi-play", "DAL-PHI", st("DAL", "PHI"), { kit.frame(poss = Side.HOME) }, play(Side.HOME, 2, 10, tail = field(FieldZone.OPP, 30))),
        Look("lv-pit-play", "LV-PIT", st("LV", "PIT"), { kit.frame(poss = Side.AWAY) }, play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))),
        Look("cin-den-kickoff", "CIN-DEN", st("CIN", "DEN"), { kit.frame(poss = Side.AWAY) }, NflBarKey(NflBarKind.KICKOFF, Side.AWAY, tail = field(FieldZone.OWN, 35))),
    )

    private fun content(r: Rig, key: NflBarKey?, dim: Boolean, pair: StackedPair, away: String, home: String): NflBarContent? =
        key?.let { NflBarContent().also { c -> c.dim = dim; r.painter.fillBar(c, it, pair, away, home, res) } }

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float, val content: NflBarContent?)

    /** A takeover to draw over [render]'s look: [kind] (and a pair's [second]) at [t] by [scorer]. */
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
        val pair = kit.PAIRS.getValue(look.match)
        val f = look.f()
        val a = StackedNflDrawArgs()
        val stt = look.state(r.painter)
        a.state = stt
        a.frame = f
        a.colours = pair
        a.bar = content(r, look.bar, look.barDim, pair, look.away, look.home)
        a.barPrev = content(r, look.prev, false, pair, look.away, look.home)
        a.awayLogo = r.logo(look.away)
        a.homeLogo = r.logo(look.home)
        a.mirrored = corner.mirrored
        a.up = corner.up
        a.still = take?.still ?: false
        r.painter.takeover = null
        r.painter.glow = null
        if (take != null) {
            val sc = if (take.scorer == Side.AWAY) pair.away else pair.home
            val awayN = stt.awayScore.toIntOrNull() ?: 0
            val homeN = stt.homeScore.toIntOrNull() ?: 0
            val g = kit.score(take.t, take.kind, take.second, take.still)
            val kind = if (g.segment == 1 && take.second != null) take.second else take.kind
            val pts = POINTS.getValue(kind)
            r.score.begin(take.scorer, listOf(NflScore(kind, pts, awayN, homeN)), sc.goal, r.logo(if (take.scorer == Side.AWAY) look.away else look.home), look.away, look.home, f.boxW)
            r.painter.takeover = r.score
            r.painter.glow = r.glow(f.boxW, K.STACKED_H + if (f.barClosed) 0f else f.barH)
            a.score = g
            a.scoreSide = take.scorer
            a.takeoverOn = true
        }
        val ox = r.m + if (corner.mirrored) (K.STACKED_NFL_W_MAX - K.STACKED_ROWS_W) * r.s else 0f
        val oy = r.m + if (corner.up) K.STACKED_BAR_H * r.s else 0f
        c.translate(ox, oy)
        r.painter.drawFrame(c, a)
        return Shot(bmp, ox, oy, a.bar)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(name: String, cells: List<List<Bitmap>>, alsoForLead: Boolean = false) {
        val cw = cells.flatten().maxOf { it.width }
        val ch = cells.flatten().maxOf { it.height }
        val cols = cells.maxOf { it.size }
        val b = Bitmap.createBitmap(cols * cw, cells.size * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { row, list -> list.forEachIndexed { col, bmp -> c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null) } }
        save(name, b)
        if (alsoForLead) runCatching {
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
            val byCorner = Corner.entries.map { corner ->
                looks.map { look -> render(look, large, corner).bmp.also { save("nfl-stacked-${look.name}-${corner.tag}-${sz(large)}-dark", it) } }
            }
            sheet("nfl-stacked-contact-sheet-${sz(large)}-dark-tl", byCorner[0].chunked(4), alsoForLead = true)
            sheet("nfl-stacked-contact-sheet-${sz(large)}-dark-tr", byCorner[1].chunked(4), alsoForLead = true)
            sheet("nfl-stacked-contact-sheet-${sz(large)}-dark-bottom", (byCorner[2].take(8) + byCorner[3].take(8)).chunked(4), alsoForLead = !large)
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

    @Test
    fun theTakeoverForEveryKindAtTheDesignTimesThePairAndTheStillFrames() {
        val play = looks.first { it.name == "play" }
        val half = looks.first { it.name == "halftime" }
        val leadRows = ArrayList<List<Bitmap>>()
        for (large in listOf(false, true)) for (corner in listOf(Corner.TL, Corner.TR, Corner.BR)) {
            for (kind in NflScoreKind.entries) {
                val row = times.map { t ->
                    // The runner swaps in the scored body once the lead covers the box (216) and the score bar from 216.
                    val look = if (t < 216) play else Look("td", state = { kit.state(it, awayScore = "30") }, f = { kit.frame(poss = Side.AWAY) },
                        bar = NflBarKey(NflBarKind.SCORE, Side.AWAY, score = kind, points = POINTS.getValue(kind)))
                    render(look, large, corner, take = Take(t.toFloat(), kind)).bmp.also {
                        save("nfl-stacked-score-${kind.name.lowercase()}-${corner.tag}-${sz(large)}-t$t", it)
                    }
                }
                if (corner != Corner.BR || !large) leadRows += row.take(8)
                if (corner == Corner.BR) break // one kind is enough for the bottom corner
            }
        }
        sheet("nfl-stacked-takeover-sheet", leadRows.take(15), alsoForLead = true)
        // A +7 pair (TD then XP) at 3 600 / 5 016 / 8 400, and the still frames A / B of a single score and of the pair.
        val pairRow = listOf(3_600, 5_016, 8_400).map { t ->
            render(looks.first { it.name == "score-bar" }, false, Corner.TL, take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN, NflScoreKind.EXTRA_POINT)).bmp
                .also { save("nfl-stacked-score-pair-tl-std-t$t", it) }
        }
        val stills = listOf(1_000, 4_000, 6_000, 9_000).map { t ->
            render(looks.first { it.name == "score-bar" }, false, Corner.TL, take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN, if (t > 4_800) NflScoreKind.EXTRA_POINT else null, still = true)).bmp
                .also { save("nfl-stacked-score-still-tl-std-t$t", it) }
        }
        // A takeover pinned at the 243 break width (§2.5 step 4 territory).
        val breakRow = listOf(450, 1_500).map { t ->
            render(half, false, Corner.TL, take = Take(t.toFloat(), NflScoreKind.TOUCHDOWN)).bmp.also { save("nfl-stacked-score-td-break-tl-std-t$t", it) }
        }
        sheet("nfl-stacked-takeover-pair-still-sheet", listOf(pairRow + breakRow.take(1), stills + breakRow.drop(1)), alsoForLead = true)
        assertTrue(File(out, "nfl-stacked-takeover-sheet.png").length() > 10_000)
    }

    // ------------------------------------------------------------------ assertions on the drawn pixels

    @Test
    fun theBarBackgroundIsTheContentsColourAndItsWordsPassContrast() {
        for (look in looks.filter { it.bar != null && it.prev == null && !it.name.startsWith("open") }) for (corner in Corner.entries) {
            val r = std
            val shot = render(look, false, corner)
            val b = shot.content!!
            val l = if (corner.mirrored) (K.STACKED_ROWS_W - look.f().boxW) * r.s else 0f
            val w = look.f().boxW * r.s
            // The bar's far end from its stripes and words: the right end unmirrored, the left end mirrored.
            val x = if (corner.mirrored) l + 3f else l + w - 3f
            val y = if (corner.up) -K.STACKED_BAR_H * r.s / 2f else K.STACKED_H * r.s + K.STACKED_BAR_H * r.s / 2f
            val px = shot.bmp.getPixel((x + shot.ox).toInt(), (y + shot.oy).toInt())
            val want = if (b.redZone && !corner.mirrored) StackedNflPainter.RED_ZONE_SUB else b.bg
            assertTrue("${look.name} ${corner.tag}: bar bg ${Integer.toHexString(px)} vs ${Integer.toHexString(want)}", kit.near(want, px, 3))
            val min = 4.5
            assertTrue("${look.name}: words ${contrast(b.fg, b.bg)} on the bar", contrast(b.fg or (0xFF shl 24), b.bg or (0xFF shl 24)) >= min)
            if (b.redZone) assertTrue("${look.name}: words on red", contrast(b.fg or (0xFF shl 24), StackedNflPainter.RED_ZONE_SUB) >= min)
        }
        // White on the rows (≥ 7, §9.2) and the flag words (≥ 4.5).
        assertTrue(contrast(Color.WHITE, K.STACKED_ROW_BG) >= 7.0)
        assertTrue(contrast(K.STACKED_NFL_FLAG_FG, K.STACKED_NFL_FLAG_BG) >= 4.5)
    }

    @Test
    fun theRowsCodeIsWhiteOnNavyFromTheDrawnPixels() {
        val shot = render(looks.first(), false, Corner.TL)
        val r = std
        // The code "KC"'s line box in the away row: the brightest pixel there is the glyphs' white.
        var brightest = 0
        val x0 = (81f * r.s + shot.ox).toInt()
        for (y in (12 + shot.oy.toInt())..(36 + shot.oy.toInt())) for (x in x0..(x0 + 30)) {
            val p = shot.bmp.getPixel(x, y)
            if (lum(p) > lum(brightest)) brightest = p
        }
        assertTrue("code ink ${Integer.toHexString(brightest)}", contrast(brightest, K.STACKED_ROW_BG) >= 7.0)
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
        fun words(key: NflBarKey, away: String = "KC", home: String = "BUF") =
            NflBarContent().also { r.painter.fillBar(it, key, kit.PAIRS["KC-BUF"], away, home, res) }.let { r.painter.barText(it) }
        assertEquals("1ST & 10 | KC 35", words(play(Side.AWAY, 1, 10, tail = field(FieldZone.OWN, 35))))
        assertEquals("1ST & 10 | BUF 35", words(play(Side.AWAY, 1, 10, tail = field(FieldZone.OPP, 35))))
        assertEquals("4TH & GOAL | RED ZONE", words(play(Side.HOME, 4, null, goal = true, tail = TabTail.RedZone)))
        assertEquals("3RD | KC 35", words(play(Side.AWAY, 3, null, tail = field(FieldZone.OWN, 35))))
        assertEquals("2ND & 5 | 50", words(play(Side.AWAY, 2, 5, tail = field(FieldZone.MIDFIELD, 50))))
        assertEquals("3RD & 7", words(play(null, 3, 7, tail = field(FieldZone.OWN, 20))))
        assertEquals("EXTRA POINT", words(NflBarKey(NflBarKind.PAT, Side.AWAY)))
        assertEquals("LV KICKOFF | LV 35", words(NflBarKey(NflBarKind.KICKOFF, Side.HOME, tail = field(FieldZone.OWN, 35)), "SEA", "LV"))
        // RC9: a kick-off never reads RED ZONE.
        assertEquals("LV KICKOFF", words(NflBarKey(NflBarKind.KICKOFF, Side.HOME, tail = TabTail.RedZone), "SEA", "LV"))
        assertEquals("FLAG ON THE PLAY", words(NflBarKey(NflBarKind.FLAG, null)))
        assertEquals("TIMEOUT BUF | 1 LEFT", words(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1)))
        assertEquals("TIMEOUT BUF | 0 LEFT", words(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 0)))
        assertEquals("END OF 1ST", words(NflBarKey(NflBarKind.END_PERIOD, null, period = p1)))
        assertEquals("END OF 4TH", words(NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.REG, 4))))
        assertEquals("END OF OT", words(NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.OT, 1))))
        assertEquals("HALFTIME", words(NflBarKey(NflBarKind.HALFTIME, null)))
        assertEquals("FINAL", words(NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.REG)))
        assertEquals("FINAL/OT", words(NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.OT)))
        assertEquals("NO UPDATE", words(NflBarKey(NflBarKind.NO_UPDATE, null)))
        assertEquals("DELAYED", words(NflBarKey(NflBarKind.DELAYED, null)))
        assertEquals("TOUCHDOWN | KC +6", words(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6)))
        assertEquals("EXTRA POINT", words(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.EXTRA_POINT)))
        assertEquals("TWO-POINT | BUF +2", words(NflBarKey(NflBarKind.SCORE, Side.HOME, score = NflScoreKind.TWO_POINT, points = 2)))
        // Colours: a team kind takes the side's bar; neutral and break kinds the neutral bar with the row stripes; the flag
        // bar its fixed yellow (S-D6).
        val pair = kit.PAIRS.getValue("KC-BUF")
        val c = NflBarContent()
        r.painter.fillBar(c, play(Side.HOME, 1, 10), pair, "KC", "BUF", res)
        assertEquals(listOf(pair.home.barBg, pair.home.barA, pair.home.barB, Color.WHITE), listOf(c.bg, c.a, c.b, c.fg))
        r.painter.fillBar(c, play(null, 1, 10), pair, "KC", "BUF", res)
        assertEquals(listOf(K.STACKED_BAR_NEUTRAL, pair.away.stripe, pair.home.stripe), listOf(c.bg, c.a, c.b))
        r.painter.fillBar(c, NflBarKey(NflBarKind.FLAG, null), pair, "KC", "BUF", res)
        assertEquals(listOf(K.STACKED_NFL_FLAG_BG, K.STACKED_NFL_FLAG_FG, K.STACKED_NFL_FLAG_B, K.STACKED_NFL_FLAG_FG), listOf(c.bg, c.a, c.b, c.fg))
        assertTrue(c.flag)
    }
}
