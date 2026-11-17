package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import app.thebox.core.sports.TabTail
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NFL Stacked §9.2 `StackedNflFitTest` (WP-3), through the real painters with the bundled fonts at Standard (s = 1) and
 * Large (s = 1.25):
 * - §2.4's bar strings × the 32 codes, the italic's overhang and the flag icon included, inside the bar (333 for the team
 *   and flag bars, 243 for the break bars), both mirror states;
 * - §5.2's row: the ball's right edge ≥ 12 from "88"'s ink for every code with the possession stripe, both mirror states;
 *   three-digit scores fitted to ≥ 24 inside the rows;
 * - the clock column: "15:00", the periods, "No update" (two lines) and "Delayed";
 * - pip height ≥ 2 real px at 1280, 1920 and 3840 wide (S-D12);
 * - §2.5's takeover fit at 243 / 333 for every kind and every code: the word ≥ 28 (or the step that drops something), and
 *   badge, word and block in order inside the box;
 * - nothing of the box (shadow aside, which is the runner's halo budget) is drawn outside the footprint in any corner.
 *
 * Hand-built frames ([StackedNflTestKit]); re-point the footprint case at the real layout at integration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedNflFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val kit = StackedNflTestKit
    private val teams = kit.TEAMS

    private fun scale(large: Boolean) = if (large) K.SCORE_LARGE_SCALE else 1f
    private fun painter(large: Boolean) = StackedNflPainter(scale(large), large, bold, italic)
    private fun scorer(large: Boolean) = StackedNflScorePainter(scale(large), large, barlow, bold, kit.SPEC)

    @Test
    fun theFontsLoadAndTheTeamsAre32() {
        assertNotNull(bold)
        assertNotNull(italic)
        assertNotNull(barlow)
        assertEquals(32, teams.toSet().size)
    }

    @Test
    fun everyBarStringFitsItsBarForEveryCode() {
        for (large in listOf(false, true)) {
            val p = painter(large)
            val s = scale(large)
            for (t in teams) {
                val team = listOf(
                    NflBarKey(NflBarKind.TIMEOUT, Side.AWAY, left = 1),
                    NflBarKey(NflBarKind.PAT, Side.AWAY),
                    NflBarKey(NflBarKind.PAT, Side.AWAY, tail = TabTail.TwoPointTry),
                    NflBarKey(NflBarKind.KICKOFF, Side.AWAY, tail = TabTail.Field(FieldZone.OWN, 35)),
                    NflBarKey(NflBarKind.PLAY, Side.AWAY, down = app.thebox.core.sports.DownText.Down(4, null, true), tail = TabTail.RedZone),
                    NflBarKey(NflBarKind.PLAY, Side.AWAY, down = app.thebox.core.sports.DownText.Down(3, 10, false), tail = TabTail.Field(FieldZone.OPP, 35)),
                    NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.EXTRA_POINT, points = 1),
                    NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6),
                    NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.FIELD_GOAL, points = 3),
                    NflBarKey(NflBarKind.FLAG, null),
                )
                val breaks = listOf(
                    NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.REG, 4)),
                    NflBarKey(NflBarKind.END_PERIOD, null, period = PeriodLabel(PeriodKind.OT, 2)),
                    NflBarKey(NflBarKind.HALFTIME, null),
                    NflBarKey(NflBarKind.FINAL, null, finalKind = PeriodKind.OT),
                    NflBarKey(NflBarKind.NO_UPDATE, null),
                    NflBarKey(NflBarKind.DELAYED, null),
                    // A score pinned at the break width: the label alone (StackedNflPinSchedule.label, review fix U-S1).
                    *NflScoreKind.entries.map { NflBarKey(NflBarKind.SCORE, Side.AWAY, score = it) }.toTypedArray(),
                )
                for ((keys, width) in listOf(team to K.STACKED_NFL_W_MAX, breaks to K.STACKED_ROWS_W)) for (key in keys) {
                    val c = NflBarContent()
                    p.fillBar(c, key, kit.pair(t, "BUF"), t, "BUF", res)
                    val w = width * s
                    for (mirrored in listOf(false, true)) {
                        val l = if (mirrored) K.STACKED_ROWS_W * s - w else 0f
                        val ink = p.barInk(c, l, l + w, mirrored)
                        val what = "${p.barText(c)} ${if (large) "Large" else "Std"} m=$mirrored"
                        assertTrue("$what: right ${ink.right} > ${l + w - BAR_AIR * s}", ink.right <= l + w - BAR_AIR * s)
                        // The words never run into the stripes (B ends 37.8 + 11.7 + the skew's 9.5 from the outer end).
                        val stripesEnd = (37.8f + 11.7f + 9.6f) * s
                        if (!mirrored) assertTrue("$what: left ${ink.left}", ink.left >= l + stripesEnd)
                        else assertTrue("$what: right ${ink.right} into the stripes", ink.right <= l + w - stripesEnd)
                    }
                }
            }
        }
    }

    @Test
    fun theRowKeepsTwelveBetweenTheBallAndTheScoreForEveryCode() {
        for (large in listOf(false, true)) {
            val p = painter(large)
            val s = scale(large)
            for (t in teams) for (mirrored in listOf(false, true)) {
                val e = p.rowExtents(t, "88", p.possStripeWidth, mirrored)
                val gap = if (mirrored) e.ball.first - e.score.second else e.score.first - e.ball.second
                assertTrue("$t m=$mirrored: ball–score gap ${gap / s}", gap >= 12f * s)
                assertEquals(K.STACKED_NFL_CODE_PX, e.codePx, 0f)
            }
            for (score in listOf("100", "199")) for (mirrored in listOf(false, true)) {
                val e = p.rowExtents("WSH", score, p.possStripeWidth, mirrored)
                assertTrue("$score: px ${e.scorePx}", e.scorePx >= K.STACKED_NFL_SCORE_FLOOR_PX && e.scorePx < 35.1f)
                assertTrue("$score inside the rows", e.score.first >= 0f && e.score.second <= p.rowsWidth)
                val gap = if (mirrored) e.ball.first - e.score.second else e.score.first - e.ball.second
                assertTrue("$score m=$mirrored: gap ${gap / s}", gap >= 4f * s)
            }
            // A 4-letter code steps down (§5.4) and still clears the score.
            val e = p.rowExtents("ABCD", "88", p.possStripeWidth, false)
            assertTrue(e.codePx < K.STACKED_NFL_CODE_PX && e.codePx >= 18f)
        }
    }

    @Test
    fun theClockColumnsWordsFit() {
        for (large in listOf(false, true)) {
            val p = painter(large)
            assertEquals(K.STACKED_NFL_CLOCK_PX, p.clockPx, 0f)
            for (secs in listOf(0, 59, 9 * 60 + 59, 15 * 60, 99 * 60 + 59)) {
                assertTrue("clock $secs", p.clockWidth(secs) <= p.clockTextBudget)
            }
            for (period in listOf("1ST", "2ND", "3RD", "4TH", "OT", "2OT")) {
                val px = p.fitColumn(period, 21.6f, 0.02f)
                assertEquals("$period keeps 21.6", 21.6f, px, 0f)
                assertTrue(p.columnWidth(period, px, period = true) <= p.columnTextBudget)
            }
            val noUpdate = p.fitSlot(res.getString(R.string.sports_no_update))
            assertTrue("No update: two lines", noUpdate.brk > 0)
            assertTrue(noUpdate.px >= K.STACKED_FIT_MIN_PX)
            val delayed = p.fitSlot(res.getString(R.string.sports_delayed))
            assertTrue(p.columnWidth(res.getString(R.string.sports_delayed), delayed.px) <= p.columnTextBudget)
        }
    }

    @Test
    fun thePipsAreAtLeastTwoRealPixelsTall() {
        for (width in listOf(1280f, 1920f, 3840f)) for (large in listOf(false, true)) {
            val s = width / 1920f * scale(large)
            val p = StackedNflPainter(s, large, bold, italic)
            assertTrue("$width ${if (large) "Large" else "Std"}: pip ${p.pipHeight} px", p.pipHeight >= 2f - 1e-4f)
            // The block ends above the row's bottom.
            assertTrue(p.pipTopY + p.pipHeight < K.STACKED_H / 2f * s)
        }
    }

    @Test
    fun theTakeoverFitsAt243And333() {
        for (large in listOf(false, true)) {
            val sp = scorer(large)
            val s = scale(large)
            for (boxW in listOf(K.STACKED_ROWS_W, K.STACKED_NFL_W_MAX) + YDS_WIDTHS) for (kind in NflScoreKind.entries) for (t in teams) {
                sp.begin(Side.AWAY, listOf(NflScore(kind, 6, 88, 88)), kit.KC.goal, null, t, "BUF", boxW)
                val what = "$kind at $boxW ($t) ${if (large) "Large" else "Std"}"
                val w = boxW * s
                assertTrue("$what: word ${sp.wordPxUsed}", sp.wordPxUsed >= K.STACKED_NFL_WORD_FLOOR_PX)
                if (sp.badgeShown) {
                    assertTrue("$what: badge", sp.badgeLeft >= 0f)
                    assertTrue("$what: badge / word", sp.wordLeft >= sp.badgeRight + 13.5f * s - 0.5f)
                } else assertTrue(sp.wordLeft >= 0f)
                assertTrue("$what: word / block ${sp.wordRight} ${sp.blockLeft}", sp.wordRight <= sp.blockLeft - 13.5f * s + 0.5f)
                assertTrue("$what: block", sp.blockRightEdge <= w)
            }
            // The design's numbers (§2.5): TOUCHDOWN 34.3 at 333 with padding 8; FIELD GOAL, TWO-POINT, SAFETY keep their size.
            fun at(kind: NflScoreKind, boxW: Float): StackedNflScorePainter =
                sp.also { it.begin(Side.AWAY, listOf(NflScore(kind, 6, 24, 17)), kit.KC.goal, null, "KC", "BUF", boxW) }
            at(NflScoreKind.TOUCHDOWN, 333f).let { assertEquals(34.3f, it.wordPxUsed, 0.8f); assertEquals(8f * s, it.padUsed, 1e-3f); assertTrue(it.tallyShown) }
            at(NflScoreKind.SAFETY, 333f).let { assertEquals(38.7f, it.wordPxUsed, 0f); assertEquals(16.2f * s, it.padUsed, 1e-3f) }
            at(NflScoreKind.FIELD_GOAL, 333f).let { assertEquals(36f, it.wordPxUsed, 0f) }
            at(NflScoreKind.TWO_POINT, 333f).let { assertEquals(36f, it.wordPxUsed, 0f) }
            at(NflScoreKind.EXTRA_POINT, 333f).let { assertEquals(32.2f, it.wordPxUsed, 0.8f) }
            // At the 243 break width a touchdown drops the tally, then the badge (§2.5 step 4).
            at(NflScoreKind.TOUCHDOWN, 243f).let { assertFalse(it.tallyShown); assertFalse(it.badgeShown) }
            assertEquals("+6", at(NflScoreKind.TOUCHDOWN, 333f).pointsText)
            assertEquals("24 – 17", sp.tallyText)
        }
    }

    /**
     * Review fix U-B1 (V1–V3): the goalposts' **ink** (the sprite's lit columns) never crosses the "+N" / tally block as
     * drawn (with its slide and its 1.12 overshoot), for XP and FG (single, and as a pair's second), every corner, both
     * sizes, at 333 and 243, the widest tally and a narrow one, at every 5 ms of the takeover and in the still frame;
     * and the arc's ball, on its last visible frame, is inside the uprights (its ink between their inner edges, its
     * centre between the uprights' tops and the crossbar).
     */
    @Test
    fun theGoalpostsNeverCrossTheScoreAndTheBallPassesBetweenThem() {
        val corners = listOf("tl" to false, "bl" to false, "tr" to true, "br" to true)
        val kicks = listOf(NflScoreKind.EXTRA_POINT, NflScoreKind.FIELD_GOAL)
        val f = app.thebox.core.sports.NflScoreFrame()
        val posts = android.graphics.RectF()
        val block = android.graphics.RectF()
        for (large in listOf(false, true)) {
            val sp = scorer(large)
            val s = scale(large)
            // The sprite's lit columns as fractions of its width (the real ink, anti-aliasing included).
            val spr = sp.postsSprite
            var c0 = spr.width
            var c1 = -1
            for (x in 0 until spr.width) for (y in 0 until spr.height) if (Color.alpha(spr.getPixel(x, y)) > 0) { c0 = minOf(c0, x); c1 = maxOf(c1, x) }
            val inkL = c0.toFloat() / spr.width
            val inkR = (c1 + 1).toFloat() / spr.width
            for (boxW in listOf(K.STACKED_ROWS_W, K.STACKED_NFL_W_MAX) + YDS_WIDTHS) for (kind in kicks) for ((corner, mirrored) in corners) {
                for (score in listOf(NflScore(kind, if (kind == NflScoreKind.FIELD_GOAL) 3 else 1, 88, 88), NflScore(kind, 1, 7, 0))) {
                    sp.begin(Side.AWAY, listOf(score), kit.KC.goal, null, "KC", "BUF", boxW)
                    val what = "$kind $corner ${if (large) "Large" else "Std"} at $boxW, ${sp.tallyText}"
                    var checked = 0
                    fun check(tag: String) {
                        if (!f.postsOn || f.postsAlpha <= 0f || f.blockAlpha <= 0f) return
                        sp.postsRect(f, mirrored, posts)
                        sp.blockDrawnRect(f, mirrored, block)
                        val l = posts.left + inkL * posts.width()
                        val r = posts.left + inkR * posts.width()
                        assertTrue("$what $tag: posts ink $l..$r crosses the block ${block.left}..${block.right}", r <= block.left || l >= block.right)
                        assertTrue("$what $tag: posts inside the box", l >= 0f && r <= sp.boxWidth)
                        checked++
                    }
                    var t = 0f
                    while (t <= K.NFL_TAKEOVER_MS) { kit.SPEC.let { app.thebox.core.sports.NflScoreTimeline.sample(it, kind, t, f) }; check("t $t"); t += 5f }
                    var p = 0f
                    while (p <= K.NFL_TAKEOVER_MS) { app.thebox.core.sports.NflScoreTimeline.samplePair(kit.SPEC, NflScoreKind.TOUCHDOWN, kind, kit.SPEC.pairSecondMs + p, f); check("pair +$p"); p += 25f }
                    app.thebox.core.sports.NflScoreTimeline.sampleStill(kit.SPEC, kind, null, 0f, f); check("still")
                    assertTrue("$what: the posts were drawn with the block ($checked frames)", checked > 100)
                    // The ball's last visible frame of the arc: inside the uprights.
                    var last = -1f
                    t = 0f
                    while (t <= K.NFL_TAKEOVER_MS) {
                        app.thebox.core.sports.NflScoreTimeline.sample(kit.SPEC, kind, t, f)
                        if (f.arcOn && f.ballAlpha > 0f) last = t
                        t += 1f
                    }
                    app.thebox.core.sports.NflScoreTimeline.sample(kit.SPEC, kind, last, f)
                    sp.postsRect(f, mirrored, posts)
                    val ballPx = kit.SPEC.runPx * s
                    val x0 = sp.arcX(f)
                    val cx = (if (mirrored) sp.boxWidth - x0 - ballPx else x0) + ballPx / 2f
                    val cy = f.ballY * s + ballPx / 2f
                    val rInk = app.thebox.core.sports.NflShapes.ballInkRadiusUnits() * ballPx / app.thebox.core.sports.NflShapes.BALL_VIEWBOX * f.ballScale
                    val u = posts.width() / app.thebox.core.sports.NflShapes.POSTS_VIEW_W
                    val inL = posts.left + app.thebox.core.sports.NflShapes.POSTS_INNER_LEFT * u
                    val inR = posts.left + app.thebox.core.sports.NflShapes.POSTS_INNER_RIGHT * u
                    val v = posts.height() / app.thebox.core.sports.NflShapes.POSTS_VIEW_H
                    assertTrue("$what: ball ${cx - rInk}..${cx + rInk} between the uprights $inL..$inR at t $last", cx - rInk >= inL && cx + rInk <= inR)
                    assertTrue("$what: ball centre $cy between the uprights' tops and the crossbar", cy >= posts.top + 6f * v && cy <= posts.top + 44f * v)
                }
            }
        }
    }

    /** U-B1: the numbers (Standard, 333, FIELD GOAL "+3" over "88 – 88"): the posts move about 25 px left, off the block. */
    @Test
    fun theGoalpostsShiftNumbers() {
        val sp = scorer(false)
        sp.begin(Side.AWAY, listOf(NflScore(NflScoreKind.FIELD_GOAL, 3, 88, 88)), kit.KC.goal, null, "KC", "BUF", 333f)
        val blockLeft = sp.blockLeft
        val inkRight = kit.SPEC.postsX + kit.SPEC.postsW * app.thebox.core.sports.NflShapes.POSTS_INK_RIGHT / 60f
        assertEquals(blockLeft - 13.5f - inkRight, sp.postsShiftUsed, 1e-3f)
        assertTrue("shift ${sp.postsShiftUsed}", sp.postsShiftUsed < -20f && sp.postsShiftUsed > -30f)
    }

    @Test
    fun nothingOfTheBoxLeavesItsFootprint() {
        // Real views through the real table and timeline (integration pass): flag, red zone, halftime, no situation.
        val looks = listOf(
            kit.view(flagId = "401873061503"),
            kit.view(down = kit.down(4, null, goal = true), tab = kit.tab(Side.HOME, TabTail.RedZone)),
            kit.halftime(),
            kit.view(down = null),
        ).map { v -> Triple(v, kit.rest(kit.targets(v)), kit.targets(v).bar) }
        for (large in listOf(false, true)) {
            val s = scale(large)
            val p = painter(large) // no shadow bake: the shadow is the halo's, inside the layer's margins
            val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val lw = Math.round(K.STACKED_NFL_W_MAX * s + 2 * m)
            val lh = Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m)
            for ((v, f, key) in looks) for (mirrored in listOf(false, true)) for (up in listOf(false, true)) {
                for (shake in listOf(0f, -2.7f, 2.7f)) {
                    val bmp = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    val ox = m + if (mirrored) (K.STACKED_NFL_W_MAX - K.STACKED_ROWS_W) * s else 0f
                    val oy = m + if (up) K.STACKED_BAR_H * s else 0f
                    c.translate(ox, oy)
                    f.shakeDx = shake
                    f.waveDeg = 8f
                    val a = StackedNflDrawArgs().apply {
                        state = kit.state(p, v, res)
                        frame = f
                        colours = kit.pair("KC", "BUF")
                        bar = key?.let { k -> NflBarContent().also { p.fillBar(it, k, colours, "KC", "BUF", res) } }
                        this.mirrored = mirrored
                        this.up = up
                    }
                    p.drawFrame(c, a)
                    val l = m - 1
                    val r = m + K.STACKED_NFL_W_MAX * s + 1
                    val t = m - 1
                    val b = m + (K.STACKED_H + K.STACKED_BAR_H) * s + 1
                    for (y in 0 until lh) for (x in 0 until lw) {
                        if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
                        assertTrue("$key m=$mirrored up=$up ${if (large) "Large" else "Std"}: pixel ($x, $y) outside the footprint", x >= l && x <= r && y >= t && y <= b)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ NFL YDS (docs/design/m6-nfl-stacked-yds.md §3.1, §3.4)

    /**
     * §3.4: one to three digits and "−99" (U+2212) fit the 63 column at 21.6 with 6 px of air each side (the budget 51),
     * through the real `Paint` at both sizes; the digits are tabular; "YDS" fits too; the digits centre on each row by
     * the "0" bounds (27 / 81) and the label on the rows' line (54); the label clears both rows' digits by ≥ 12 (§3.1).
     */
    @Test
    fun theYdsDigitsAndTheLabelFitTheColumn() {
        val air = 6f
        for (large in listOf(false, true)) {
            val p = painter(large)
            val s = scale(large)
            val what = if (large) "Large" else "Std"
            assertEquals(K.STACKED_NFL_YDS_W * s, p.ydsWidth, 1e-3f)
            val texts = listOf(0, 7, 41, 88, 142, 888, 999, -2, -9, -99).map { app.thebox.core.sports.NflYards.text(it) }
            for (t in texts) {
                val ink = p.ydsInk(t, label = false)
                assertTrue("$what \"$t\": ink ${ink.left}..${ink.right} inside ${air * s}..${(K.STACKED_NFL_YDS_W - air) * s}", ink.left >= air * s && ink.right <= (K.STACKED_NFL_YDS_W - air) * s)
            }
            // The design's widths (tabular 0.600 em at 21.6): "888" 38.9, "−99" 37.8, "−2" 24.9, "0" 13.0.
            assertEquals(38.9f * s, p.ydsAdvance("888"), 0.6f * s)
            // "−99": the design's 37.8 took U+2212 at its proportional 0.551 em; under `tnum` the bundled font gives the
            // minus a tabular cell too (measured 39.0), still well inside the 51 budget.
            assertEquals(39.0f * s, p.ydsAdvance("−99"), 0.6f * s)
            assertEquals(13.0f * s, p.ydsAdvance("0"), 0.3f * s)
            assertEquals("tabular", p.ydsAdvance("111"), p.ydsAdvance("888"), 1e-3f)
            val label = p.ydsInk(res.getString(R.string.sports_stacked_yds), label = true)
            assertTrue("$what label ${label.width() / s} fits", label.left >= air * s && label.right <= (K.STACKED_NFL_YDS_W - air) * s)
            val rowH = K.STACKED_H * s / 2f
            val zeroA = p.ydsInk("0", false, row = 0)
            val zeroH = p.ydsInk("0", false, row = 1)
            assertEquals("$what away digits centred at 27", rowH / 2f, zeroA.centerY(), 0.5f)
            assertEquals("$what home digits centred at 81", rowH * 1.5f, zeroH.centerY(), 0.5f)
            val s0 = p.ydsInk("S", label = true)
            assertEquals("$what label centred on the line (54)", rowH, s0.centerY(), 0.5f)
            for (t in texts) {
                val a = p.ydsInk(t, false, row = 0)
                val h = p.ydsInk(t, false, row = 1)
                assertTrue("$what \"$t\": away digits clear the label ${label.top - a.bottom}", label.top - a.bottom >= 12f * s)
                assertTrue("$what \"$t\": the label clears the home digits ${h.top - label.bottom}", h.top - label.bottom >= 12f * s)
            }
        }
    }

    /** §3.2: nothing of a YDS look (396 and 306, opening and closing) is drawn outside the 396 footprint, any corner. */
    @Test
    fun nothingOfTheYdsBoxLeavesTheFootprint() {
        val mark = app.thebox.core.sports.NflTimeoutMark(Side.HOME, 1, true)
        val timeout = kit.yds(kit.view(homeTimeouts = 1, timeout = mark), 999, -99)
        val half = kit.yds(kit.halftime(), 389, 312)
        val tg = kit.targets(timeout)
        val closed = kit.rest(tg).also { kit.at(it, app.thebox.core.sports.StackedNflTrack.YDS, app.thebox.core.sports.StackedNflTransition.STILL, 0f, app.thebox.core.sports.StackedNflFrame(), to = false) }
        val looks = listOf(
            timeout to kit.rest(tg),
            timeout to kit.at(kit.rest(tg), app.thebox.core.sports.StackedNflTrack.YDS, app.thebox.core.sports.StackedNflTransition.OPEN, 400f, closed),
            timeout to kit.at(kit.rest(tg), app.thebox.core.sports.StackedNflTrack.YDS, app.thebox.core.sports.StackedNflTransition.CLOSE, 100f, kit.rest(tg), to = false),
            half to kit.rest(kit.targets(half)),
        )
        for (large in listOf(false, true)) {
            val s = scale(large)
            val p = painter(large)
            val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val lw = Math.round(K.STACKED_NFL_FOOTPRINT_W * s + 2 * m)
            val lh = Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m)
            for ((v, f) in looks) for (mirrored in listOf(false, true)) for (up in listOf(false, true)) {
                val bmp = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.translate(m + if (mirrored) (K.STACKED_NFL_FOOTPRINT_W - K.STACKED_ROWS_W) * s else 0f, m + if (up) K.STACKED_BAR_H * s else 0f)
                val key = kit.targets(v).bar
                val a = StackedNflDrawArgs().apply {
                    state = kit.state(p, v, res)
                    frame = f
                    colours = kit.pair("KC", "BUF")
                    bar = key?.let { k -> NflBarContent().also { p.fillBar(it, k, colours, "KC", "BUF", res) } }
                    this.mirrored = mirrored
                    this.up = up
                }
                p.drawFrame(c, a)
                for (y in 0 until lh) for (x in 0 until lw) {
                    if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
                    assertTrue("$key ${f.boxW} m=$mirrored up=$up: pixel ($x, $y) outside the footprint",
                        x >= m - 1 && x <= m + K.STACKED_NFL_FOOTPRINT_W * s + 1 && y >= m - 1 && y <= m + (K.STACKED_H + K.STACKED_BAR_H) * s + 1)
                }
            }
        }
    }

    private companion object {
        /** Design px kept free at the bar's far end (§2.4: "room ≈ 4"). */
        const val BAR_AIR = 2f
        /** (NFL YDS §3.6) The takeover's widths with the YDS column: 306 (a break) and 396 (an in-play stoppage). */
        val YDS_WIDTHS = listOf(K.STACKED_ROWS_W + K.STACKED_NFL_YDS_W, K.STACKED_NFL_FOOTPRINT_W)
    }
}
