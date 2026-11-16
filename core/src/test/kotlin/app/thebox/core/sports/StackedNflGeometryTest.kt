package app.thebox.core.sports

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedNflGeometry as G

/**
 * docs/design/m6-nfl-stacked.md §2.3–§2.5, §5.2–§5.4 (WP-2): `StackedNflGeometry`.
 * - The constants are the mock × .45 (and the design's raised values).
 * - **Mirror identity**, every corner × size × box width: each right-corner element is the screen reflection of the
 *   left-corner one (stripe, logo, code, ball, pips, score, clock column and its contents, bar stripes at rest and in
 *   travel, the bar's words group, the takeover's badge / word / block, the posts, the run / arc ball, the streaks, the
 *   wipes); offsets and rotations negated; the words keep their reading order (glyphs never flipped) and the red-zone
 *   patch stays after the divider in reading order.
 * - **Fit** with the bundled fonts' advances ([FontAdvances], the design's method): every §2.4 bar string × 32 codes
 *   (codes such as WSH / LAR / JAX, "3RD & GOAL | KC 1", the flag, the timeouts, the kick-offs, the score labels; the
 *   breaks at 243), the §2.5 takeover fit (pad → 8, word ≥ 28, then the tally, then the badge) at 333 and 243 for every
 *   kind and a stress word "2-PT CONVERSION", the codes and the §5.2 gap ≥ 12 against "88", the 3-digit score floor,
 *   the clock column words, the pip height ≥ 2 real px at 1280 / 1920 / 3840. Standard and Large.
 */
class StackedNflGeometryTest {
    private val codes: List<String> = NflTeamColours.TABLE.keys.sorted()
    private val sizes = ScoreBugSize.entries
    private fun k(s: ScoreBugSize) = ScoreBugPlacement.stackedScale(s)
    private val geo = OverlayGeometryFixture.DESIGN

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    private val bold = FontAdvances.BOLD
    private val italic = FontAdvances.BOLD_ITALIC
    private val barlow = FontAdvances.BARLOW
    private fun codeW(code: String, px: Float = K.STACKED_NFL_CODE_PX) = bold.width(code, px, 0.01f)
    private fun barW(text: String, px: Float = K.STACKED_BAR_TEXT_PX) = italic.width(text, px, 0.01f)
    private fun wordW(text: String, px: Float) = barlow.width(text, px, K.STACKED_NFL_WORD_TRACK_EM)

    @Test
    fun theConstantsAreTheMockAt045() {
        val s = K.STACKED_SCALE.toDouble()
        val m = StackedNflMock
        near(m.STRIPE_W * s, K.STACKED_NFL_STRIPE_W, "stripe"); near(m.POSS_STRIPE_W * s, K.STACKED_NFL_POSS_STRIPE_W, "poss stripe")
        near(m.LOGO_ML * s, K.STACKED_NFL_LOGO_ML, "logo ml"); near(m.LOGO_SLOT_W * s, K.STACKED_NFL_LOGO_SLOT_W, "logo slot")
        near((m.LOGO_ML + m.LOGO_SLOT_W + m.CENTRE_PL) * s, K.STACKED_NFL_CODE_DX, "code dx")
        near(m.CODE_PX * s, K.STACKED_NFL_CODE_PX, "code px"); near(m.BALL * s, K.STACKED_NFL_BALL, "ball"); near(m.BALL_GAP * s, K.STACKED_NFL_BALL_GAP, "ball gap")
        near(m.PIP_W * s, K.STACKED_NFL_PIP_W, "pip w"); near(m.PIP_GAP * s, K.STACKED_NFL_PIP_GAP, "pip gap"); near(m.PIP_R * s, K.STACKED_NFL_PIP_R, "pip r")
        near(3.0, K.STACKED_NFL_PIP_H, "pip h raised (S-D12)")
        near((120 - 70) / 2.0 * s, K.STACKED_NFL_CODE_TOP, "code top")
        near(K.STACKED_NFL_CODE_TOP + K.STACKED_NFL_CODE_PX + m.CODE_PIPS_GAP * s, K.STACKED_NFL_PIP_TOP, "pip top 40.05")
        near(m.SCORE_SLOT_W * s, K.STACKED_NFL_SCORE_W, "score w"); near(m.SCORE_MR * s, K.STACKED_NFL_SCORE_MR, "score mr")
        near(m.SCORE_PX * s, K.STACKED_NFL_SCORE_PX, "score px")
        near(183.6, G.SCORE_X, "score x"); near(228.6, G.SCORE_RIGHT, "score right")
        near(m.PERIOD_PX * s, K.STACKED_NFL_PERIOD_PX, "period px"); near(m.PERIOD_SLOT_H * s, K.STACKED_NFL_PERIOD_SLOT_H, "period slot")
        near(m.PERIOD_PAD_B * s, K.STACKED_NFL_PERIOD_PAD_B, "period pad"); near(m.RULE_MARGIN * s, K.STACKED_NFL_RULE_MARGIN, "rule margin")
        near(m.CLOCK_PX * s, K.STACKED_NFL_CLOCK_PX, "clock px")
        near(46 * s, K.STACKED_NFL_BAR_STRIPE_A_X, "stripe A"); near(84 * s, K.STACKED_NFL_BAR_STRIPE_B_X, "stripe B")
        near(26 * s, K.STACKED_NFL_BAR_STRIPE_W, "stripe w"); near(10 * s, K.STACKED_NFL_BAR_STRIPE_OVERHANG, "overhang")
        near(m.BAR_TEXT_PL * s, K.STACKED_NFL_BAR_TEXT_X, "text x"); near(m.BAR_TEXT_GAP * s, K.STACKED_NFL_BAR_TEXT_GAP, "text gap")
        near(m.BAR_DIVIDER_H * 20.0 / m.BAR_TEXT_PX, K.STACKED_NFL_BAR_DIVIDER_H, "divider 30/38 of 20", 0.02)
        near(m.FLAG_ICON_W * 20.0 / m.BAR_TEXT_PX, K.STACKED_NFL_FLAG_ICON_W, "flag icon w", 0.02)
        near(m.FLAG_ICON_H * 20.0 / m.BAR_TEXT_PX, K.STACKED_NFL_FLAG_ICON_H, "flag icon h", 0.02)
        near(m.SCORE_PAD * s, K.STACKED_NFL_TAKEOVER_PAD, "takeover pad"); near(m.SCORE_GAP * s, K.STACKED_NFL_TAKEOVER_GAP, "gap")
        near(m.SCORE_BADGE * s, K.STACKED_NFL_BADGE, "badge"); near(m.POINTS_PX * s, K.STACKED_NFL_POINTS_PX, "points px")
        near(m.WORD_TD_PX * s, K.STACKED_NFL_WORD_TD_PX, "TD"); near(m.WORD_XP_PX * s, K.STACKED_NFL_WORD_XP_PX, "XP"); near(m.WORD_FG_PX * s, K.STACKED_NFL_WORD_FG_PX, "FG")
        near(m.BLOCK_GAP * s, K.STACKED_NFL_BLOCK_GAP, "block gap")
        near(110 * s, K.STACKED_NFL_STREAK1_W, "streak 1"); near(40 * s, K.STACKED_NFL_STREAK2_W, "streak 2"); near(40 * s, K.STACKED_NFL_STREAK_OVERHANG, "streak overhang")
        assertEquals(8f, K.STACKED_TAKEOVER_PAD_TIGHT)
        near(17.1, G.BALL_TOP, "ball centred on the code line")
    }

    // ------------------------------------------------------------------ the mirror

    private data class Span(val l: Float, val r: Float)

    /** Every reflected element of the box at corner [c], size [s], top part [boxW] Standard px wide, in screen px. */
    private fun elements(c: ScoreCorner, s: ScoreBugSize, boxW: Float): Map<String, Span> {
        val input = PlacementInput(
            corner = c, size = s, placing = false, bar = BottomBar.NONE, channelList = false, stats = false, groupCard = false,
            picker = false, numberEntry = false, probing = null, toast = false, reminder = false, modal = false,
            textTrackOn = false, screenOff = false, league = League.NFL, style = NhlBugStyle.STACKED,
        )
        val fp = assertNotNull(ScoreBugPlacement.place(input, geo))
        val top = ScoreBugPlacement.stackedTopBox(fp, s, boxW)
        val m = ScoreBugPlacement.stackedMirrored(fp)
        val kk = k(s)
        val out = LinkedHashMap<String, Span>()
        fun box(name: String, x: Float, w: Float) { val l = top.l + kk * x; out[name] = Span(l, l + kk * w) }
        fun row(name: String, x: Float, w: Float) = box(name, G.rowsLeft(boxW, m) + G.rowX(x, w, m), w)
        fun inBox(name: String, x: Float, w: Float) = box(name, G.boxX(x, w, boxW, m), w)
        for (wide in listOf(0f, 0.5f, 1f)) {
            val sw = G.stripeW(wide)
            val cx = G.codeX(sw)
            val cw = codeW("WSH")
            row("stripe $wide", 0f, sw)
            row("logo $wide", G.logoX(sw), K.STACKED_NFL_LOGO_SLOT_W)
            row("code $wide", cx, cw)
            row("ball $wide", G.ballX(cx, cw), K.STACKED_NFL_BALL)
            for (i in 0 until 3) row("pip $i $wide", G.pipX(cx, i), K.STACKED_NFL_PIP_W)
        }
        row("score", G.SCORE_X, K.STACKED_NFL_SCORE_W)
        row("rows", 0f, K.STACKED_ROWS_W)
        val clockW = boxW - K.STACKED_ROWS_W
        box("clock column", G.clockX(boxW, clockW, m), clockW)
        box("clock contents", G.clockContentX(boxW, m), K.STACKED_CLOCK_W)
        for (travel in listOf(0f, -31.5f, K.STACKED_BAR_STRIPE_TRAVEL)) {
            box("stripe A $travel", G.barStripeX(G.BAR_STRIPE_A_X, travel, boxW, m), K.STACKED_NFL_BAR_STRIPE_W)
            box("stripe B $travel", G.barStripeX(G.BAR_STRIPE_B_X, travel, boxW, m), K.STACKED_NFL_BAR_STRIPE_W)
        }
        val g = FloatArray(G.G_SIZE)
        G.barGroup(boxW, icon = false, labelW = barW("1ST & 10"), subW = barW("KC 35"), mirrored = m, out = g)
        box("bar group", g[G.G_LABEL], g[G.G_WIDTH])
        G.barGroup(boxW, icon = true, labelW = barW("FLAG ON THE PLAY"), subW = 0f, mirrored = m, out = g)
        box("flag group", g[G.G_ICON], g[G.G_WIDTH])
        // The takeover at this box width.
        val fit = G.fitTakeover(boxW, 1f, K.STACKED_NFL_WORD_TD_PX, { wordW("TOUCHDOWN", it) }, 68.2f, 30.6f)
        val ww = wordW("TOUCHDOWN", fit.wordPx)
        val blockW = if (fit.tally) 68.2f else 30.6f
        val t = G.takeover(boxW, 1f, fit, ww, blockW, m, FloatArray(G.T_SIZE))
        if (fit.badge) box("badge", t[G.T_BADGE], K.STACKED_NFL_BADGE)
        box("word", t[G.T_WORD], ww)
        box("block", t[G.T_BLOCK], blockW)
        val spec = NflScoreSpec.STACKED
        box("posts", G.spriteX(spec.postsX * G.arcK(boxW), spec.postsW, boxW, m), spec.postsW)
        for (tt in listOf(300f, 900f, 1_500f)) {
            val f = NflScoreTimeline.sample(spec, NflScoreKind.TOUCHDOWN, tt, NflScoreFrame())
            box("run $tt", G.spriteX(f.ballX, spec.runPx, boxW, m), spec.runPx)
            box("streak $tt", G.boxX(f.streak1X, K.STACKED_NFL_STREAK1_W, boxW, m), K.STACKED_NFL_STREAK1_W)
            val a = NflScoreTimeline.sample(spec, NflScoreKind.FIELD_GOAL, tt, NflScoreFrame())
            box("arc $tt", G.spriteX(a.ballX * G.arcK(boxW), spec.runPx, boxW, m), spec.runPx)
            box("wipe $tt", G.wipeFrom(f.panelFrom, f.panelTo, m) * boxW, (f.panelTo - f.panelFrom) * boxW)
        }
        return out
    }

    @Test
    fun everyRightCornerElementIsTheReflectionOfTheLeftOne() {
        val pairs = listOf(ScoreCorner.TOP_LEFT to ScoreCorner.TOP_RIGHT, ScoreCorner.BOTTOM_LEFT to ScoreCorner.BOTTOM_RIGHT)
        var checked = 0
        for ((lc, rc) in pairs) for (s in sizes) for (boxW in listOf(333f, 243f, 288f)) {
            val left = elements(lc, s, boxW)
            val right = elements(rc, s, boxW)
            assertEquals(left.keys, right.keys)
            for ((name, l) in left) {
                val r = right.getValue(name)
                near((1920f - l.r).toDouble(), r.l, "$lc→$rc $s $boxW $name left edge", 1e-2)
                near((1920f - l.l).toDouble(), r.r, "$lc→$rc $s $boxW $name right edge", 1e-2)
                checked++
            }
            // Large is Standard × 1.25 about the corner's anchor (the rows' outer edge).
            if (s == ScoreBugSize.LARGE) {
                val ls = elements(lc, ScoreBugSize.STANDARD, boxW)
                for ((name, l) in left) {
                    val st = ls.getValue(name)
                    near(((st.r - st.l) * 1.25f).toDouble(), l.r - l.l, "$lc $boxW $name Large width", 1e-2)
                    near((96f + (st.l - 96f) * 1.25f).toDouble(), l.l, "$lc $boxW $name Large x", 1e-2)
                }
            }
        }
        assertTrue(checked > 500, "$checked")
    }

    @Test
    fun theLeftCornerPositionsAreTheDesigns() {
        // §5.2: code at 78.3 / 81.0; WSH ends at 143.7; ball 149.1–162.6 with the possession stripe; pips at 81.0 + 12.6 i.
        near(78.3, G.codeX(G.stripeW(0f)), "code narrow"); near(81.0, G.codeX(G.stripeW(1f)), "code wide")
        near(62.7, codeW("WSH"), "WSH", 0.05); near(31.3, codeW("SF"), "SF", 0.6)
        val cx = G.codeX(G.stripeW(1f))
        near(162.6, G.ballX(cx, codeW("WSH")) + K.STACKED_NFL_BALL, "ball end with WSH", 0.05)
        near(81.0 + 2 * 12.6 + 9.9, G.pipX(cx, 2) + K.STACKED_NFL_PIP_W, "last pip end")
        assertTrue(K.STACKED_NFL_PIP_TOP + K.STACKED_NFL_PIP_H <= StackedNflMock.CODE_PX * 0 + 54f, "pips end above the 54 row")
        near(43.05, K.STACKED_NFL_PIP_TOP + K.STACKED_NFL_PIP_H, "pip bottom")
        // The clock column at 243 (left) and at 0 (mirrored); the bar words at 63 / right-aligned 63 from the end.
        assertEquals(243f, G.clockX(333f, 90f, false)); assertEquals(0f, G.clockX(333f, 90f, true))
        assertEquals(243f, G.clockContentX(333f, false)); assertEquals(0f, G.clockContentX(333f, true))
        assertEquals(-45f, G.clockContentX(288f, true), "half-folded mirrored: anchored on the rows' side, clipped")
        val g = G.barGroup(333f, false, 100f, 50f, mirrored = true, out = FloatArray(G.G_SIZE))
        near(333.0 - 63.0, g[G.G_SUB] + 50f, "mirrored group ends 63 from the right end")
        // Mirrored the score is left-aligned 14.4 from the rows' left.
        assertEquals(14.4f, G.rowX(G.SCORE_X, K.STACKED_NFL_SCORE_W, true), 1e-4f)
        // Offsets and rotations are negated mirrored, kept on the left; skews are not this API's (kept by the painter).
        assertEquals(11.7f, G.dx(K.STACKED_NFL_REKEY_TEXT_DX, true)); assertEquals(-11.7f, G.dx(K.STACKED_NFL_REKEY_TEXT_DX, false))
        assertEquals(40f, G.deg(K.STACKED_NFL_BALL_FROM_DEG, true)); assertEquals(25f, G.deg(K.NFL_LOGO_FROM_DEG, true))
        assertEquals(-900f, G.deg(K.NFL_RUN_ROT, true)); assertEquals(-2.7f, G.dx(K.STACKED_NFL_SHAKE_DX1, true))
    }

    @Test
    fun theWordsReadLeftToRightOnBothSidesAndTheRedPatchFollowsTheDivider() {
        for (m in listOf(false, true)) for (barW in listOf(333f, 243f)) {
            val g = G.barGroup(barW, icon = true, labelW = 80f, subW = 40f, mirrored = m, out = FloatArray(G.G_SIZE))
            assertTrue(g[G.G_ICON] < g[G.G_LABEL] && g[G.G_LABEL] < g[G.G_DIVIDER] && g[G.G_DIVIDER] < g[G.G_SUB], "reading order m=$m")
            near(g[G.G_ICON] + K.STACKED_NFL_FLAG_ICON_W + K.STACKED_NFL_BAR_TEXT_GAP.toDouble(), g[G.G_LABEL], "icon gap")
            near(g[G.G_LABEL] + 80.0 + K.STACKED_NFL_BAR_TEXT_GAP, g[G.G_DIVIDER], "divider gap")
            near(g[G.G_DIVIDER] + K.STACKED_NFL_BAR_DIVIDER_W + K.STACKED_NFL_BAR_TEXT_GAP.toDouble(), g[G.G_SUB], "sub gap")
            // The red-zone patch: from the divider to the bar's end in reading order, under the sub, on both sides.
            assertEquals(g[G.G_DIVIDER], g[G.G_RED_FROM])
            // Integration ruling 4: mirrored, the patch stops a text gap after the sub (the stripes keep the team's bar).
            if (m) near(g[G.G_SUB] + 40.0 + K.STACKED_NFL_BAR_TEXT_GAP, g[G.G_RED_TO], "mirrored red end")
            else assertEquals(barW, g[G.G_RED_TO])
            assertTrue(g[G.G_SUB] + 40f <= g[G.G_RED_TO], "m=$m $barW")
            // The group's own offsets are the same both sides (only the group is re-anchored).
            val l = G.barGroup(barW, icon = true, labelW = 80f, subW = 40f, mirrored = false, out = FloatArray(G.G_SIZE))
            near((l[G.G_SUB] - l[G.G_ICON]).toDouble(), g[G.G_SUB] - g[G.G_ICON], "same offsets")
        }
        // No sub: no divider, no patch.
        val g = G.barGroup(333f, icon = false, labelW = 80f, subW = 0f, mirrored = false, out = FloatArray(G.G_SIZE))
        assertTrue(g[G.G_DIVIDER].isNaN() && g[G.G_RED_FROM].isNaN() && g[G.G_ICON].isNaN())
        assertEquals(80f, g[G.G_WIDTH])
    }

    // ------------------------------------------------------------------ fit: the bar (§2.4)

    private val downs = listOf("1ST & 10", "2ND & 7", "3RD & 25", "3RD & GOAL", "4TH & GOAL", "3RD", "4TH & 1")

    /** Every team-bar string of §2.4 for [code] (label, sub); "" = no sub. */
    private fun teamStrings(code: String): List<Pair<String, String>> = buildList {
        for (d in downs) { add(d to "$code 35"); add(d to "$code 1"); add(d to "$code 49"); add(d to "50"); add(d to "RED ZONE"); add(d to "") }
        add("EXTRA POINT" to "")
        add("$code KICKOFF" to "$code 35"); add("$code KICKOFF" to "$code 49"); add("$code KICKOFF" to "")
        for (n in 0..3) add("TIMEOUT $code" to "$n LEFT")
        for ((w, p) in listOf("TOUCHDOWN" to 6, "EXTRA POINT" to 1, "FIELD GOAL" to 3, "SAFETY" to 2, "TWO-POINT" to 2)) add(w to "$code +$p")
    }

    @Test
    fun everyBarStringFitsAt20And25() {
        var worst = Float.MAX_VALUE
        var worstText = ""
        for (s in sizes) {
            val kk = k(s)
            for (code in codes) for ((label, sub) in teamStrings(code)) {
                val w = G.barGroupW(false, barW(label, 20f * kk) / kk, if (sub.isEmpty()) 0f else barW(sub, 20f * kk) / kk)
                val spare = G.barRoom(K.STACKED_NFL_W_MAX) - w
                assertTrue(spare >= 0f, "$s \"$label | $sub\" ${w} in ${G.barRoom(K.STACKED_NFL_W_MAX)}")
                if (spare < worst) { worst = spare; worstText = "$label | $sub" }
            }
            // The flag, with its icon.
            val flag = G.barGroupW(true, barW("FLAG ON THE PLAY"), 0f)
            near(219.7, flag, "icon + FLAG ON THE PLAY", 0.2)
            assertTrue(flag <= G.barRoom(K.STACKED_NFL_W_MAX))
            // The neutral break bars fit the 243 break width.
            val breaks = listOf("END OF 1ST", "END OF 2ND", "END OF 3RD", "END OF 4TH", "END OF OT", "END OF 2OT", "HALFTIME", "FINAL", "FINAL/OT", "NO UPDATE", "DELAYED")
            for (b in breaks) {
                val w = barW(b)
                assertTrue(w <= G.barRoom(K.STACKED_ROWS_W), "$b $w in ${G.barRoom(K.STACKED_ROWS_W)}")
            }
            // §2.4's listed break strings are ≤ 119.7.
            for (b in listOf("END OF 4TH", "HALFTIME", "FINAL/OT", "NO UPDATE", "DELAYED")) assertTrue(barW(b) <= 119.8f, "$b ≤ 119.7 (§2.4)")
        }
        // §2.4: the worst is a kick-off or a down with a 2-digit field (≥ 18 spare).
        assertTrue(worst >= 18f, "worst \"$worstText\" spare $worst")
        // The design's quoted widths.
        near(243.8, G.barGroupW(false, barW("WSH KICKOFF"), barW("WSH 35")), "WSH KICKOFF | WSH 35", 0.2)
        near(247.7, G.barGroupW(false, barW("4TH & GOAL"), barW("RED ZONE")), "4TH & GOAL | RED ZONE", 0.2)
        near(232.4, G.barGroupW(false, barW("TIMEOUT WSH"), barW("1 LEFT")), "TIMEOUT WSH | 1 LEFT", 0.2)
        assertTrue(G.barGroupW(false, barW("3RD & GOAL"), barW("KC 1")) < 200f, "3RD & GOAL | KC 1")
        for (c in listOf("WSH", "LAR", "JAX", "LAC")) assertTrue(G.barGroupW(false, barW("$c KICKOFF"), barW("$c 35")) <= 244f, c)
    }

    // ------------------------------------------------------------------ fit: the takeover (§2.5)

    private val tallySlot = { kk: Float -> bold.width("88 – 88", K.STACKED_TALLY_PX * kk, tnum = true) }
    private val points = { p: Int, kk: Float -> bold.width("+$p", K.STACKED_NFL_POINTS_PX * kk) }

    private fun fit(word: String, nat: Float, p: Int, boxW: Float, kk: Float): G.TakeoverFit {
        val pw = points(p, kk)
        val block = maxOf(tallySlot(kk), pw)
        return G.fitTakeover(boxW * kk, kk, nat * kk, { px -> wordW(word, px) }, block, pw)
    }

    private val words = listOf(
        Triple(NflScoreKind.TOUCHDOWN, "TOUCHDOWN", 6), Triple(NflScoreKind.EXTRA_POINT, "EXTRA POINT", 1),
        Triple(NflScoreKind.FIELD_GOAL, "FIELD GOAL", 3), Triple(NflScoreKind.SAFETY, "SAFETY", 2),
        Triple(NflScoreKind.TWO_POINT, "TWO-POINT", 2),
    )

    @Test
    fun theTakeoverFitsAt333ByTheSection25Steps() {
        near(184.6, wordW("TOUCHDOWN", 38.7f), "TOUCHDOWN at 38.7", 0.05)
        near(229.0, wordW("TOUCHDOWN", 48f), "TOUCHDOWN at 48 (NFL §5.2's method)", 0.05)
        for (s in sizes) {
            val kk = k(s)
            val want = mapOf(
                NflScoreKind.TOUCHDOWN to (8.0 to 34.3), NflScoreKind.EXTRA_POINT to (8.0 to 32.2),
                NflScoreKind.FIELD_GOAL to (8.0 to 36.0), NflScoreKind.TWO_POINT to (8.0 to 36.0),
                NflScoreKind.SAFETY to (16.2 to 38.7),
            )
            for ((kind, word, p) in words) {
                val f = fit(word, G.wordPx(kind), p, 333f, kk)
                val (pad, px) = want.getValue(kind)
                near(pad * kk, f.pad, "$s $kind pad")
                near(px * kk, f.wordPx, "$s $kind px", 0.15 * kk)
                assertTrue(f.tally && f.badge && f.wordFits, "$s $kind keeps everything: $f")
                // The fitted row fits the box exactly or with room.
                val block = maxOf(tallySlot(kk), points(p, kk))
                val used = 2 * f.pad + K.STACKED_NFL_BADGE * kk + 2 * K.STACKED_NFL_TAKEOVER_GAP * kk + wordW(word, f.wordPx) + block
                assertTrue(used <= 333f * kk + 0.01f, "$s $kind used $used")
                assertTrue(f.wordPx >= K.STACKED_NFL_WORD_FLOOR_PX * kk)
            }
        }
    }

    @Test
    fun atTheBreakWidthTheTallyThenTheBadgeAreDropped() {
        for (s in sizes) {
            val kk = k(s)
            for ((kind, word, p) in words) {
                val f = fit(word, G.wordPx(kind), p, 243f, kk)
                assertTrue(f.wordFits, "$s $kind at 243: $f")
                assertTrue(f.wordPx >= K.STACKED_NFL_WORD_FLOOR_PX * kk - 1e-3f, "$s $kind ≥ 28")
                assertEquals(K.STACKED_TAKEOVER_PAD_TIGHT * kk, f.pad)
                // Every word needs the tally dropped at 243; TOUCHDOWN, EXTRA POINT and TWO-POINT the badge too.
                assertFalse(f.tally, "$s $kind tally dropped")
                val pw = points(p, kk)
                val used = 2 * f.pad + (if (f.badge) K.STACKED_NFL_BADGE * kk + 2 * K.STACKED_NFL_TAKEOVER_GAP * kk else K.STACKED_NFL_TAKEOVER_GAP * kk) + wordW(word, f.wordPx) + pw
                assertTrue(used <= 243f * kk + 0.01f, "$s $kind used $used")
            }
            val td = fit("TOUCHDOWN", G.wordPx(NflScoreKind.TOUCHDOWN), 6, 243f, kk)
            assertFalse(td.badge, "TOUCHDOWN at 243 drops the badge")
            val sf = fit("SAFETY", G.wordPx(NflScoreKind.SAFETY), 2, 243f, kk)
            assertTrue(sf.badge, "SAFETY at 243 keeps the badge")
            // The layout: the badge at the pad, the block right-aligned, the word centred between; mirrored, reflected.
            val o = G.takeover(243f * kk, kk, sf, wordW("SAFETY", sf.wordPx), points(2, kk), false, FloatArray(G.T_SIZE))
            near(sf.pad.toDouble(), o[G.T_BADGE], "badge at the pad")
            near((243f * kk - sf.pad - points(2, kk)).toDouble(), o[G.T_BLOCK], "block right-aligned")
            val om = G.takeover(243f * kk, kk, sf, wordW("SAFETY", sf.wordPx), points(2, kk), true, FloatArray(G.T_SIZE))
            near((243f * kk - sf.pad - K.STACKED_NFL_BADGE * kk).toDouble(), om[G.T_BADGE], "mirrored badge on the right")
            near(sf.pad.toDouble(), om[G.T_BLOCK], "mirrored block on the left")
        }
    }

    @Test
    fun aLongerWordStillFits() {
        // A stress word (not a shipped string; the two-point word is "TWO-POINT"): "2-PT CONVERSION" at the TWO-POINT size
        // fits the 333 box (step 3: the tally dropped), and at the 243 break width it is under the floor even without the
        // badge: the fit reports it (wordFits false) rather than clipping silently.
        for (s in sizes) {
            val kk = k(s)
            val f333 = fit("2-PT CONVERSION", K.STACKED_NFL_WORD_2PT_PX, 2, 333f, kk)
            assertTrue(f333.wordFits && f333.wordPx >= 28f * kk - 1e-3f, "$s at 333: $f333")
            val f243 = fit("2-PT CONVERSION", K.STACKED_NFL_WORD_2PT_PX, 2, 243f, kk)
            assertFalse(f243.wordFits, "$s at 243: $f243")
        }
        // A word too long for any step reports it (drawn at the floor, never silently).
        val none = G.fitTakeover(243f, 1f, 36f, { px -> wordW("TWO-POINT CONVERSION ATTEMPT", px) }, 68f, 31f)
        assertFalse(none.wordFits); assertEquals(28f, none.wordPx)
    }

    // ------------------------------------------------------------------ fit: codes, scores, the clock column, pips

    @Test
    fun everyCodeFitsAt252AndKeeps12FromTheScore() {
        for (s in sizes) {
            val kk = k(s)
            for (code in codes) {
                assertEquals(K.STACKED_NFL_CODE_PX * kk, G.codePx(kk) { px -> bold.width(code, px, 0.01f) }, "$s $code")
                // §5.2: the possession stripe (widest start), the ball, against "88"'s left edge, both mirror states.
                val cx = G.codeX(G.stripeW(1f))
                val ballEnd = G.ballX(cx, codeW(code)) + K.STACKED_NFL_BALL
                val scoreL = G.SCORE_RIGHT - bold.width("88", K.STACKED_NFL_SCORE_PX, tnum = true)
                val gap = (scoreL - ballEnd) * kk
                assertTrue(gap >= K.STACKED_NFL_CODE_SCORE_GAP_MIN * kk, "$s $code gap $gap")
                val m = G.rowX(scoreL, 0f, true) - G.rowX(ballEnd, 0f, true)
                near(-(scoreL - ballEnd).toDouble(), m, "$code mirrored gap is the reflection")
            }
        }
        // §5.2's narrowest: WSH, 23.9 to "88" (186.5).
        val wsh = G.SCORE_RIGHT - bold.width("88", K.STACKED_NFL_SCORE_PX, tnum = true) - (G.ballX(81f, codeW("WSH")) + K.STACKED_NFL_BALL)
        near(23.9, wsh, "WSH gap", 0.05)
        // A 4-letter code steps down by 2 px to 18, never ellipsised; null only past 18.
        val four = G.codePx(1f) { px -> bold.width("WWWW", px, 0.01f) }
        assertTrue(four == null || four < K.STACKED_NFL_CODE_PX, "WWWW $four")
        assertEquals(null, G.codePx(1f) { 1_000f })
    }

    @Test
    fun scoresFitTheSlotAndThreeDigitsKeepTheFloor() {
        for (s in sizes) {
            val kk = k(s)
            for (n in 0..99) {
                assertEquals(K.STACKED_NFL_SCORE_PX * kk, G.scorePx(kk) { px -> bold.width("$n", px, tnum = true) }, "$s $n")
                assertTrue(bold.width("$n", K.STACKED_NFL_SCORE_PX * kk, tnum = true) <= K.STACKED_NFL_SCORE_W * kk)
            }
            for (n in 100..199) {
                val px = G.scorePx(kk) { p -> bold.width("$n", p, tnum = true) }
                assertTrue(px >= K.STACKED_NFL_SCORE_FLOOR_PX * kk && px < K.STACKED_NFL_SCORE_PX * kk, "$s $n px $px")
                assertTrue(bold.width("$n", px, tnum = true) <= K.STACKED_NFL_SCORE_FIT_W * kk + 1e-3f || px == K.STACKED_NFL_SCORE_FLOOR_PX * kk)
            }
            // §5.4 quotes "100" → 24.5; with the tabular figures (0.6 em) it is 23.9, so it sits at the 24 floor.
            assertEquals(K.STACKED_NFL_SCORE_FLOOR_PX * kk, G.scorePx(kk) { p -> bold.width("100", p, tnum = true) }, "$s \"100\"")
            near(42.12 * kk, bold.width("88", K.STACKED_NFL_SCORE_PX * kk, tnum = true), "$s \"88\" 42.1 (§2.3)", 0.01)
        }
    }

    @Test
    fun theClockColumnWordsFit() {
        for (q in listOf("1ST", "2ND", "3RD", "4TH", "OT", "2OT")) {
            assertTrue(bold.width(q, K.STACKED_NFL_PERIOD_PX, 0.02f) <= K.STACKED_CLOCK_W - 2 * K.STACKED_NFL_CLOCK_AIR, q)
        }
        near(44.3, bold.width("4TH", K.STACKED_NFL_PERIOD_PX, 0.02f), "4TH", 0.6)
        // "15:00" at 27.9 with 5 px of air each side.
        assertTrue(bold.width("15:00", K.STACKED_NFL_CLOCK_PX, tnum = true) + 2 * K.STACKED_NFL_CLOCK_AIR <= K.STACKED_CLOCK_W)
    }

    @Test
    fun thePipIsAtLeastTwoRealPixels() {
        for (w in listOf(1280f, 1920f, 3840f)) for (s in sizes) {
            assertTrue(G.pipRealPx(w, k(s)) >= 2f, "$w $s ${G.pipRealPx(w, k(s))}")
        }
        assertEquals(2f, G.pipRealPx(1280f, 1f)); assertEquals(2.5f, G.pipRealPx(1280f, 1.25f))
        assertEquals(3f, G.pipRealPx(1920f, 1f)); assertEquals(6f, G.pipRealPx(3840f, 1f))
        // The mock's 2.7 would be 1.8 at 720p (S-D12).
        assertTrue(2.7f * 1280f / 1920f < 2f)
    }
}
