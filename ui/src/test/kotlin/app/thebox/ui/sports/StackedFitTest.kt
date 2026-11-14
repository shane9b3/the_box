package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock as M
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stacked §4.2 fit rules and §10.2 `StackedFitTest` (WP-2): every string the Stacked box can show, measured through the
 * real painters with the bundled fonts (Instrument Sans Bold, the real Bold Italic, Barlow Condensed Black Italic) at
 * Standard (s = 1) and Large (s = 1.25), so 1 design px = 1 px:
 * - every bar string × the 32 abbreviations at 20 / 25 px, the italic's measured overhang included, inside the narrowest
 *   box that can carry it (a team bar: 333, the PP width without SOG; a neutral bar: 301.5);
 * - the codes against the scores 0–19 (no overlap, the stripe at its power-play width), "10".."19" inside the score slot;
 * - SOG 0–99 and the "SOG" label in the SOG column;
 * - "19:58", "20:00", "No update", "Delayed", "END", "INT" and every period in the clock column at ≥ 18 px;
 * - the takeover's badge, GOAL and tally at 301.5 / 333 / 391.5 for every pair of abbreviations with two-digit scores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)

    private val sizes = listOf(false, true)
    private fun scale(large: Boolean) = if (large) K.SCORE_LARGE_SCALE else 1f
    private fun painter(large: Boolean) = StackedPainter(scale(large), large, bold, italic)
    private fun goal(large: Boolean) =
        StackedGoalPainter(scale(large), large, barlow, bold, res.getString(R.string.sports_goal_word), StackedFixtures.SPEC)

    /** The 32 NHL abbreviations (2026-27). */
    private val teams = StackedFixtures.TEAMS

    @Test
    fun theFontsLoad() {
        assertNotNull("Instrument Sans Bold", bold)
        assertNotNull("Instrument Sans Bold Italic (the real italic, §11)", italic)
        assertNotNull("Barlow Condensed Black Italic", barlow)
        assertEquals(32, teams.size)
        assertEquals(32, teams.toSet().size)
    }

    // ------------------------------------------------------------------ the bar

    private fun bar(p: StackedPainter, kind: BarKind, side: Side?, secs: Int, period: PeriodLabel?, away: String, home: String): BarContent =
        BarContent().also {
            it.kind = kind; it.side = side; it.secs = secs; it.period = period
            p.fillBar(it, null, away, home, res)
        }

    @Test
    fun everyTeamBarStringFitsTheNarrowestPowerPlayBoxWithItsItalicOverhang() {
        var worst = Float.MAX_VALUE
        var worstText = ""
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            // A team bar can show at 333 (rows + clock: a power play when SOG is missing) or wider.
            val limit = (K.STACKED_ROWS_W + K.STACKED_CLOCK_W) * s - K.STACKED_HAIRLINE_W * s
            for (abbrev in teams) for (side in Side.entries) {
                val away = if (side == Side.AWAY) abbrev else "TOR"
                val home = if (side == Side.HOME) abbrev else "TOR"
                val cases = listOf(
                    bar(p, BarKind.POWER_PLAY, side, 97, null, away, home),
                    bar(p, BarKind.POWER_PLAY, side, 19 * 60 + 59, null, away, home),
                    bar(p, BarKind.POWER_PLAY, side, BarContent.NO_SECS, null, away, home),
                    bar(p, BarKind.TWO_MAN, side, 19 * 60 + 59, null, away, home),
                    bar(p, BarKind.PP_GOAL, side, BarContent.NO_SECS, null, away, home),
                )
                for (c in cases) {
                    val text = p.barText(c)
                    assertTrue("\"$text\" names $abbrev", text.startsWith(abbrev))
                    val right = p.barTextRight(c)
                    assertTrue("\"$text\" ${sz(large)} ends at $right, limit $limit", right <= limit)
                    if (limit - right < worst) { worst = limit - right; worstText = "$text ${sz(large)}" }
                }
            }
        }
        println("team bars: least slack %.1f px (\"%s\")".format(worst, worstText))
    }

    @Test
    fun everyNeutralBarStringFitsTheBreakBox() {
        val periods = listOf(
            PeriodLabel(PeriodKind.REG, 1), PeriodLabel(PeriodKind.REG, 2), PeriodLabel(PeriodKind.REG, 3),
            PeriodLabel(PeriodKind.OT, 1), PeriodLabel(PeriodKind.OT, 2), PeriodLabel(PeriodKind.OT, 3),
            PeriodLabel(PeriodKind.OT, 4), PeriodLabel(PeriodKind.OT, 5),
        )
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            val limit = (K.STACKED_ROWS_W + K.STACKED_SOG_W) * s - K.STACKED_HAIRLINE_W * s
            val cases = periods.map { bar(p, BarKind.INTERMISSION, null, BarContent.NO_SECS, it, "EDM", "TOR") } +
                listOf(
                    bar(p, BarKind.SHOOTOUT, null, BarContent.NO_SECS, null, "EDM", "TOR"),
                    bar(p, BarKind.FINAL, null, BarContent.NO_SECS, PeriodLabel(PeriodKind.REG, 1), "EDM", "TOR"),
                    bar(p, BarKind.FINAL, null, BarContent.NO_SECS, PeriodLabel(PeriodKind.OT, 1), "EDM", "TOR"),
                    bar(p, BarKind.FINAL, null, BarContent.NO_SECS, PeriodLabel(PeriodKind.SO, 1), "EDM", "TOR"),
                    bar(p, BarKind.NO_UPDATE, null, BarContent.NO_SECS, null, "EDM", "TOR"),
                    bar(p, BarKind.DELAYED, null, BarContent.NO_SECS, null, "EDM", "TOR"),
                )
            val texts = cases.map { p.barText(it) }
            assertTrue(texts.toString(), texts.containsAll(listOf("1ST INTERMISSION", "2OT INTERMISSION", "SHOOTOUT", "FINAL", "FINAL/OT", "FINAL/SO", "NO UPDATE", "DELAYED")))
            for (c in cases) {
                val right = p.barTextRight(c)
                println("bar \"${p.barText(c)}\" ${sz(large)}: right %.1f of %.1f".format(right, limit))
                assertTrue("\"${p.barText(c)}\" ${sz(large)} ends at $right, limit $limit", right <= limit)
            }
        }
    }

    @Test
    fun theBarWordsAreUpperCaseAndTheColoursFollowTheKind() {
        val p = painter(false)
        val pair = StackedFixtures.EDM_TOR
        val pp = BarContent().also { it.kind = BarKind.POWER_PLAY; it.side = Side.AWAY; it.secs = 97; p.fillBar(it, pair, "EDM", "TOR", res) }
        assertEquals("EDM POWER PLAY 1:37", p.barText(pp))
        assertEquals(pair.away.barBg, pp.bg)
        assertEquals(pair.away.barA, pp.stripeA)
        assertEquals(pair.away.barB, pp.stripeB)
        val two = BarContent().also { it.kind = BarKind.TWO_MAN; it.side = Side.HOME; it.secs = 48; p.fillBar(it, pair, "EDM", "TOR", res) }
        assertEquals("TOR 5-ON-3 0:48", p.barText(two))
        assertEquals(pair.home.barBg, two.bg)
        val int = BarContent().also { it.kind = BarKind.INTERMISSION; it.period = PeriodLabel(PeriodKind.REG, 2); p.fillBar(it, pair, "EDM", "TOR", res) }
        assertEquals("2ND INTERMISSION", p.barText(int))
        assertEquals(K.STACKED_BAR_NEUTRAL, int.bg)
        assertEquals(pair.away.stripe, int.stripeA)
        assertEquals(pair.home.stripe, int.stripeB)
        val goal = BarContent().also { it.kind = BarKind.PP_GOAL; it.side = Side.HOME; p.fillBar(it, pair, "EDM", "TOR", res) }
        assertEquals("TOR POWER PLAY GOAL", p.barText(goal))
        val bare = BarContent().also { it.kind = BarKind.POWER_PLAY; it.side = Side.AWAY; p.fillBar(it, pair, "EDM", "TOR", res) }
        assertEquals("EDM POWER PLAY", p.barText(bare))
    }

    /**
     * (Owner request 2026-11-13) Mirrored (a right-hand corner), the bar's words are right-aligned 63 from the bar's right
     * end: every team bar in the narrowest power-play box (333) and every neutral bar in the break box (301.5) keeps its
     * ink (the italic's overhang included) clear of the box's left border and of stripe B's lean on its right (stripe B
     * spans 37.8..49.5 from the right end on the centre line; leaning "/", its left edge reaches ≈ 59 at the bar's bottom).
     */
    @Test
    fun everyBarStringFitsItsMirroredSlot() {
        val periods = listOf(PeriodLabel(PeriodKind.REG, 1), PeriodLabel(PeriodKind.OT, 2), PeriodLabel(PeriodKind.OT, 5))
        var least = Float.MAX_VALUE
        var leastAt = ""
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            val team = (K.STACKED_ROWS_W + K.STACKED_CLOCK_W) * s
            val brk = (K.STACKED_ROWS_W + K.STACKED_SOG_W) * s
            val cases = ArrayList<Pair<BarContent, Float>>()
            for (abbrev in teams) for (side in Side.entries) {
                val away = if (side == Side.AWAY) abbrev else "TOR"
                val home = if (side == Side.HOME) abbrev else "TOR"
                cases += bar(p, BarKind.POWER_PLAY, side, 19 * 60 + 59, null, away, home) to team
                cases += bar(p, BarKind.POWER_PLAY, side, BarContent.NO_SECS, null, away, home) to team
                cases += bar(p, BarKind.TWO_MAN, side, 19 * 60 + 59, null, away, home) to team
                cases += bar(p, BarKind.PP_GOAL, side, BarContent.NO_SECS, null, away, home) to team
            }
            for (pl in periods) cases += bar(p, BarKind.INTERMISSION, null, BarContent.NO_SECS, pl, "EDM", "TOR") to brk
            for (k in listOf(BarKind.SHOOTOUT, BarKind.NO_UPDATE, BarKind.DELAYED)) cases += bar(p, k, null, BarContent.NO_SECS, null, "EDM", "TOR") to brk
            cases += bar(p, BarKind.FINAL, null, BarContent.NO_SECS, PeriodLabel(PeriodKind.SO, 1), "EDM", "TOR") to brk
            for ((c, w) in cases) {
                val ink = p.barTextInkMirrored(c, w)
                val text = "\"${p.barText(c)}\" ${sz(large)} mirrored in $w"
                assertTrue("$text starts at ${ink.left}", ink.left >= K.STACKED_HAIRLINE_W * s)
                assertTrue("$text ends at ${ink.right}, stripe B at ${w - 59f * s}", ink.right <= w - 59f * s)
                // Right-aligned: its right ink is the anchor (63 from the end) give or take the last glyph's bearing.
                assertEquals("$text right edge", w - 63f * s, ink.right, 3f * s)
                if (ink.left < least) { least = ink.left; leastAt = text }
            }
        }
        println("mirrored bars: least left slack %.1f px (%s)".format(least, leastAt))
    }

    // ------------------------------------------------------------------ the rows and the SOG column

    /**
     * (Owner request 2026-11-13, second pass) A mirrored row is the true reflection, [score][code][logo][stripe]: every
     * element's advance box is the reflection of the left one about the rows' centre line (exactly, at even strength and
     * on the power play), and, glyphs not being flipped, the ink gap between the score and the code differs from the left
     * one only by the side bearings. It still never closes: every code clears every score (0–19, the two-digit scores
     * inside their reflected slot) with the stripe at its power-play width, at Standard and Large.
     */
    @Test
    fun aMirroredRowIsTheReflectionAndEveryCodeClearsEveryScore() {
        var least = Float.MAX_VALUE
        var leastAt = ""
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            val rowsW = p.rowsWidth
            for (score in 0..19) {
                val sc = score.toString()
                assertTrue("\"$sc\" ${sz(large)} mirrored spills ${p.scoreSpill(sc, mirrored = true)} px out of the slot", p.scoreSpill(sc, mirrored = true) <= 0f)
                for (abbrev in teams) {
                    for (stripe in listOf(p.stripeWidth, p.ppStripeWidth)) {
                        val l = p.rowExtents(abbrev, sc, stripe, mirrored = false)
                        val m = p.rowExtents(abbrev, sc, stripe, mirrored = true)
                        for ((i, e) in l.all.withIndex()) {
                            val (what, a) = e
                            val b = m.all[i].second
                            assertEquals("$abbrev / $sc ${sz(large)} stripe $stripe: $what left", rowsW - a.second, b.first, 1e-3f)
                            assertEquals("$abbrev / $sc ${sz(large)} stripe $stripe: $what right", rowsW - a.first, b.second, 1e-3f)
                        }
                        // From the screen edge (the right): stripe, logo slot, code, score.
                        assertTrue("$abbrev / $sc: logo inside the stripe", m.logoSlot.second <= m.stripe.first)
                        assertTrue("$abbrev / $sc: code inside the logo", m.code.second <= m.logoSlot.first)
                    }
                    val gap = p.rowGap(abbrev, sc, mirrored = true) / s
                    if (gap < least) { least = gap; leastAt = "$abbrev $sc ${sz(large)}" }
                    assertTrue("$abbrev / $sc ${sz(large)} mirrored: gap $gap design px", gap >= MIN_GAP)
                }
            }
        }
        println("mirrored rows: least score-to-code gap %.1f design px (%s)".format(least, leastAt))
    }

    @Test
    fun everyCodeClearsEveryScoreAndTwoDigitScoresStayInTheirSlot() {
        var least = Float.MAX_VALUE
        var leastAt = ""
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            for (score in 0..19) {
                assertTrue("\"$score\" ${sz(large)} spills ${p.scoreSpill(score.toString())} px out of the slot", p.scoreSpill(score.toString()) <= 0f)
                for (abbrev in teams) {
                    val gap = p.rowGap(abbrev, score.toString()) / s
                    if (gap < least) { least = gap; leastAt = "$abbrev $score ${sz(large)}" }
                    assertTrue("$abbrev / $score ${sz(large)}: gap $gap design px", gap >= MIN_GAP)
                }
            }
        }
        println("rows: least code-to-score gap %.1f design px (%s)".format(least, leastAt))
    }

    @Test
    fun sogZeroToNinetyNineAndTheLabelFitTheColumn() {
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            for (n in 0..99) assertTrue("SOG $n ${sz(large)}", p.sogSlack(n.toString()) / s >= MIN_GAP)
            val label = p.sogSlack(res.getString(R.string.sports_stacked_sog), label = true) / s
            println("SOG label slack ${sz(large)}: %.1f design px a side; \"88\" %.1f".format(label, p.sogSlack("88") / s))
            assertTrue("SOG label ${sz(large)}: $label", label >= MIN_GAP)
        }
    }

    // ------------------------------------------------------------------ the clock column

    /**
     * (Fix pass 2026-11-11, review U B1) The clock is tabular and keeps the mock's air: at 28.8 px every `m:ss` / `mm:ss`
     * is drawn (advance and ink) at least [CLOCK_AIR] design px inside each edge of the 90-px column, at both sizes.
     */
    @Test
    fun theClockIsTabularAndKeepsItsAir() {
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            val half = K.STACKED_CLOCK_W * s / 2f
            assertEquals("the clock stays at the mock's size ${sz(large)}", M.CLOCK_PX * K.STACKED_SCALE, p.clockPx, 1e-3f)
            assertEquals("the clock's budget ${sz(large)}", (K.STACKED_CLOCK_W - 2 * CLOCK_AIR) * s, p.clockTextBudget, 1e-3f)
            // Tabular: every value of the same shape is the same width, so a tick never moves the clock.
            assertEquals(p.clockWidth(10 * 60 + 12), p.clockWidth(19 * 60 + 58), 1e-3f)
            assertEquals(p.clockWidth(0), p.clockWidth(9 * 60 + 59), 1e-3f)
            for (secs in listOf(0, 59, 9 * 60 + 59, 10 * 60 + 12, 11 * 60 + 11, 19 * 60 + 58, 20 * 60)) {
                val w = p.clockWidth(secs)
                assertTrue("clock $secs ${sz(large)}: drawn $w > ${(K.STACKED_CLOCK_W - 2 * CLOCK_AIR) * s}", w <= (K.STACKED_CLOCK_W - 2 * CLOCK_AIR) * s)
                val ink = p.clockInk(secs)
                assertTrue("clock $secs ${sz(large)}: ink ${ink.left}..${ink.right} within ±${half - CLOCK_AIR * s}", ink.left >= -half + CLOCK_AIR * s && ink.right <= half - CLOCK_AIR * s)
            }
            val ink = p.clockInk(19 * 60 + 58)
            println("clock ${sz(large)}: %.1f px, \"19:58\" drawn %.1f, ink %.1f..%.1f in ±%.1f".format(p.clockPx, p.clockWidth(19 * 60 + 58), ink.left, ink.right, half))
        }
    }

    @Test
    fun theClockColumnStringsFitAtEighteenOrMore() {
        for (large in sizes) {
            val p = painter(large)
            val s = scale(large)
            val budget = p.columnTextBudget
            assertEquals("the words' budget ${sz(large)}", (K.STACKED_CLOCK_W - 2 * WORD_AIR) * s, budget, 1e-3f)
            assertTrue("clock px ${p.clockPx}", p.clockPx >= K.STACKED_FIT_MIN_PX)
            for (secs in listOf(0, 59, 9 * 60 + 59, 19 * 60 + 58, 20 * 60)) {
                val w = p.clockWidth(secs)
                assertTrue("clock $secs ${sz(large)}: $w > ${p.clockTextBudget}", w <= p.clockTextBudget)
            }
            val noUpdate = res.getString(R.string.sports_no_update)
            val delayed = res.getString(R.string.sports_delayed)
            val slots = listOf(noUpdate, delayed, res.getString(R.string.sports_stacked_end), res.getString(R.string.sports_stacked_int))
            for (t in slots) {
                val fit = p.fitSlot(t)
                assertTrue("\"$t\" ${sz(large)} at ${fit.px}", fit.px >= K.STACKED_FIT_MIN_PX)
                val lines = if (fit.brk > 0) listOf(t.substring(0, fit.brk), t.substring(fit.brk + 1)) else listOf(t)
                for (l in lines) {
                    val w = p.columnWidth(l, fit.px)
                    println("slot \"$l\" (of \"$t\") ${sz(large)}: %.1f px, ink %.1f of %.1f".format(fit.px, w, budget))
                    assertTrue("\"$l\" ${sz(large)} $w > $budget", w <= budget)
                }
            }
            // S1: "No update" wraps at its space into "No" / "update", above the floor, the block inside the slot.
            val nu = p.fitSlot(noUpdate)
            assertEquals("\"$noUpdate\" ${sz(large)} breaks at its space", noUpdate.indexOf(' '), nu.brk)
            assertTrue("\"$noUpdate\" ${sz(large)} at ${nu.px}", nu.px > K.STACKED_FIT_MIN_PX)
            val top = p.inkBounds(StackedPainter.Ink.SLOT, noUpdate.substring(0, nu.brk), 0f, nu.dy1 * s, nu.px)
            val bottom = p.inkBounds(StackedPainter.Ink.SLOT, noUpdate.substring(nu.brk + 1), 0f, nu.dy2 * s, nu.px)
            val block = bottom.bottom - top.top
            println("\"$noUpdate\" ${sz(large)}: two lines at %.1f px, block %.1f of %.1f, centred %.2f".format(nu.px, block, p.slotTextHeightBudget, (top.top + bottom.bottom) / 2f))
            assertTrue("the block ${sz(large)}: $block > ${p.slotTextHeightBudget}", block <= p.slotTextHeightBudget + 0.5f)
            assertEquals("the block is centred on the slot ${sz(large)}", 0f, (top.top + bottom.bottom) / 2f, 1f)
            assertTrue("the lines do not touch ${sz(large)}", bottom.top - top.bottom >= 2f * s)
            // "Delayed" stays one line, with room.
            val dl = p.fitSlot(delayed)
            assertEquals(-1, dl.brk)
            assertTrue("\"$delayed\" ${sz(large)}", p.columnWidth(delayed, dl.px) <= budget)
            for (period in listOf("1ST", "2ND", "3RD", "OT", "2OT", "3OT", "P4", "SO")) {
                val px = p.fitColumn(period, M.PERIOD_PX * K.STACKED_SCALE, 0.02f)
                val w = p.columnWidth(period, px, period = true)
                assertTrue("\"$period\" ${sz(large)} at $px", px >= K.STACKED_FIT_MIN_PX)
                assertTrue("\"$period\" ${sz(large)} $w > $budget", w <= budget)
            }
        }
    }

    // ------------------------------------------------------------------ the takeover

    @Test
    fun theTakeoverContentFitsEveryPinnedWidth() {
        val widths = listOf(
            K.STACKED_ROWS_W + K.STACKED_SOG_W,
            K.STACKED_ROWS_W + K.STACKED_CLOCK_W,
            K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W,
        )
        assertEquals(listOf(301.5f, 333f, 391.5f), widths)
        for (large in sizes) {
            val g = goal(large)
            val s = scale(large)
            for (w in widths) {
                var tallyDropped = 0
                var tight = 0
                for (away in teams) for (home in listOf("TOR", "CBJ", "WSH", "MTL", "NYR")) {
                    if (away == home) continue
                    g.begin(Side.AWAY, NhlTeamColours.of(away), null, away, home, 19, 18, w)
                    val right = w * s
                    assertTrue("badge ${sz(large)} $w", g.badgeLeft >= 0f && g.badgeRight <= g.wordLeft)
                    if (g.tallyShown) {
                        assertTrue("$away–$home ${sz(large)} at $w: GOAL ${g.wordRight} vs tally ${g.tallyLeft}", g.wordRight <= g.tallyLeft)
                        assertTrue("$away–$home ${sz(large)} at $w: tally ends ${g.tallyRight} of $right", g.tallyRight <= right)
                    } else {
                        tallyDropped++
                        assertTrue("$away–$home ${sz(large)} at $w: GOAL ends ${g.wordRight}", g.wordRight <= right - g.padUsed)
                    }
                    if (g.padUsed < M.GOAL_PAD * K.STACKED_SCALE * s - 0.01f) tight++
                }
                println("takeover ${sz(large)} at $w: tight padding in $tight, tally dropped in $tallyDropped of ${teams.size * 5 - 5}")
            }
            // A one-digit tally at the widest box keeps the mock's padding and the tally.
            g.begin(Side.HOME, NhlTeamColours.of("TOR"), null, "EDM", "TOR", 2, 1, widths[2])
            assertTrue("391.5 keeps the tally", g.tallyShown)
            assertEquals(M.GOAL_PAD * K.STACKED_SCALE * s, g.padUsed, 0.01f)
            assertEquals("EDM 2\nTOR 1", g.tallyText)
        }
    }

    private fun sz(large: Boolean) = if (large) "Large" else "Standard"

    private companion object {
        /** The least free space kept between neighbouring texts, design px. */
        const val MIN_GAP = 2f
        /** (Fix pass 2026-11-11) The clock column's air each side: the clock's (review U B1) and the words' (S1), design px. */
        const val CLOCK_AIR = 5f
        const val WORD_AIR = 6f
    }
}
