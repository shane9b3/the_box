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

    // ------------------------------------------------------------------ the rows and the SOG column

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

    @Test
    fun theClockColumnStringsFitAtEighteenOrMore() {
        for (large in sizes) {
            val p = painter(large)
            val budget = p.columnTextBudget
            assertTrue("clock px ${p.clockPx}", p.clockPx >= K.STACKED_FIT_MIN_PX)
            for (secs in listOf(0, 59, 9 * 60 + 59, 19 * 60 + 58, 20 * 60)) {
                val w = p.clockWidth(secs)
                assertTrue("clock $secs ${sz(large)}: $w > $budget", w <= budget)
            }
            println("clock ${sz(large)}: %.1f px, \"19:58\" %.1f of %.1f".format(p.clockPx, p.clockWidth(19 * 60 + 58), budget))
            val slots = listOf(
                res.getString(R.string.sports_no_update), res.getString(R.string.sports_delayed),
                res.getString(R.string.sports_stacked_end), res.getString(R.string.sports_stacked_int),
            )
            for (t in slots) {
                val px = p.fitColumn(t, M.CLOCK_PX * K.STACKED_SCALE)
                val w = p.columnWidth(t, px)
                println("slot \"$t\" ${sz(large)}: %.1f px, %.1f of %.1f".format(px, w, budget))
                assertTrue("\"$t\" ${sz(large)} at $px", px >= K.STACKED_FIT_MIN_PX)
                assertTrue("\"$t\" ${sz(large)} $w > $budget", w <= budget)
            }
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
    }
}
