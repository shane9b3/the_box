package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked.md §5.1, §9.1 `NflStackedPlacementTest` (WP-2): the NFL Stacked footprint (333 × 141
 * Standard, 416.25 × 176.25 Large; RC6, no YDS column) at the four corners × two sizes, the drawn box's widths (333 in
 * play, 243 in breaks and at Final) with the rows never moving and the right corners mirrored, every SB §4.4 hide / lift
 * rule on the footprint, and the `glowAllowed` halo (M 74 / 76).
 */
class NflStackedPlacementTest {
    private val geo = OverlayGeometryFixture.DESIGN
    private val probing = Box(600f, 72f, 1320f, 128f)
    private val sizes = ScoreBugSize.entries
    private val corners = ScoreCorner.entries

    private fun input(corner: ScoreCorner, size: ScoreBugSize = ScoreBugSize.STANDARD) = PlacementInput(
        corner = corner, size = size, placing = false, bar = BottomBar.NONE,
        channelList = false, stats = false, groupCard = false, picker = false, numberEntry = false,
        probing = null, toast = false, reminder = false, modal = false, textTrackOn = false, screenOff = false,
        league = League.NFL, style = NhlBugStyle.STACKED,
    )

    private fun left(c: ScoreCorner) = c == ScoreCorner.TOP_LEFT || c == ScoreCorner.BOTTOM_LEFT
    private fun bottom(c: ScoreCorner) = c == ScoreCorner.BOTTOM_LEFT || c == ScoreCorner.BOTTOM_RIGHT
    private fun k(s: ScoreBugSize) = if (s == ScoreBugSize.LARGE) 1.25f else 1f

    private val play = SportsConstants.STACKED_ROWS_W + SportsConstants.STACKED_CLOCK_W
    private val brk = SportsConstants.STACKED_ROWS_W

    @Test
    fun theFootprintIsTheDesignsNumbers() {
        assertEquals(333f, SportsConstants.STACKED_NFL_W_MAX)
        assertEquals(333f, play)
        assertEquals(243f, brk)
        assertFalse(SportsConstants.STACKED_NFL_YDS_ENABLED, "RC6: the YDS column is not in the footprint")
        assertEquals(333f, ScoreBugPlacement.width(ScoreBugSize.STANDARD, League.NFL, NhlBugStyle.STACKED))
        assertEquals(141f, ScoreBugPlacement.height(ScoreBugSize.STANDARD, League.NFL, NhlBugStyle.STACKED))
        assertEquals(416.25f, ScoreBugPlacement.width(ScoreBugSize.LARGE, League.NFL, NhlBugStyle.STACKED))
        assertEquals(176.25f, ScoreBugPlacement.height(ScoreBugSize.LARGE, League.NFL, NhlBugStyle.STACKED))
        // NFL + Pill keeps the Pill's 520 × 70 / 650 × 88 (§7: the same call and key).
        assertEquals(520f, ScoreBugPlacement.width(ScoreBugSize.STANDARD, League.NFL, NhlBugStyle.PILL))
        assertEquals(70f, ScoreBugPlacement.height(ScoreBugSize.STANDARD, League.NFL, NhlBugStyle.PILL))
        assertEquals(650f, ScoreBugPlacement.width(ScoreBugSize.LARGE, League.NFL, NhlBugStyle.PILL))
        // NHL Stacked is untouched (392 × 141).
        assertEquals(392f, ScoreBugPlacement.width(ScoreBugSize.STANDARD, League.NHL, NhlBugStyle.STACKED))
        assertEquals(141f, ScoreBugPlacement.height(ScoreBugSize.STANDARD, League.NHL, NhlBugStyle.STACKED))
    }

    @Test
    fun theSection51TableFourCornersBothSizes() {
        val std = ScoreBugSize.STANDARD
        val lg = ScoreBugSize.LARGE
        val want = mapOf(
            (ScoreCorner.TOP_RIGHT to std) to Box(1491f, 56f, 1824f, 197f),
            (ScoreCorner.TOP_RIGHT to lg) to Box(1407.75f, 56f, 1824f, 232.25f),
            (ScoreCorner.TOP_LEFT to std) to Box(96f, 56f, 429f, 197f),
            (ScoreCorner.TOP_LEFT to lg) to Box(96f, 56f, 512.25f, 232.25f),
            (ScoreCorner.BOTTOM_RIGHT to std) to Box(1491f, 883f, 1824f, 1024f),
            (ScoreCorner.BOTTOM_RIGHT to lg) to Box(1407.75f, 847.75f, 1824f, 1024f),
            (ScoreCorner.BOTTOM_LEFT to std) to Box(96f, 883f, 429f, 1024f),
            (ScoreCorner.BOTTOM_LEFT to lg) to Box(96f, 847.75f, 512.25f, 1024f),
        )
        for ((key, box) in want) {
            val (c, s) = key
            assertEquals(box, ScoreBugPlacement.cornerBox(c, s, League.NFL, NhlBugStyle.STACKED), "$c $s cornerBox")
            assertEquals(box, ScoreBugPlacement.place(input(c, s), geo), "$c $s place")
            assertEquals(box, ScoreBugPlacement.place(input(c, s).copy(placing = true), geo), "$c $s place mode")
            // Margins 96 / 56 hold everywhere.
            assertTrue(if (left(c)) box.l == 96f else box.r == 1824f, "$c $s side margin")
            assertTrue(if (bottom(c)) box.b == 1024f else box.t == 56f, "$c $s top/bottom margin")
            // The mirror rule follows the placed box; the bar opens above in the bottom corners (R16).
            assertEquals(!left(c), ScoreBugPlacement.stackedMirrored(box), "$c $s mirrored")
            assertEquals(bottom(c), ScoreBugPlacement.stackedBarUp(box), "$c $s bar up")
        }
    }

    @Test
    fun theRowsNeverMoveAndTheBoxIs333InPlayAnd243InABreak() {
        // §5.1 "Rows": top right 1581–1824 (Large 1520.25–1824), top left 96–339 (Large 96–399.75).
        val tr = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT), geo))
        assertEquals(Box(1581f, 56f, 1824f, 164f), ScoreBugPlacement.stackedRowsBox(tr, ScoreBugSize.STANDARD))
        val trL = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT, ScoreBugSize.LARGE), geo))
        assertEquals(Box(1520.25f, 56f, 1824f, 191f), ScoreBugPlacement.stackedRowsBox(trL, ScoreBugSize.LARGE))
        val tl = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT), geo))
        assertEquals(Box(96f, 56f, 339f, 164f), ScoreBugPlacement.stackedRowsBox(tl, ScoreBugSize.STANDARD))
        val tlL = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT, ScoreBugSize.LARGE), geo))
        assertEquals(Box(96f, 56f, 399.75f, 191f), ScoreBugPlacement.stackedRowsBox(tlL, ScoreBugSize.LARGE))
        // Top right Standard: 1491–1824 in play, 1581–1824 in a break; the bar below (164..197).
        assertEquals(Box(1491f, 56f, 1824f, 164f), ScoreBugPlacement.stackedTopBox(tr, ScoreBugSize.STANDARD, play))
        assertEquals(Box(1581f, 56f, 1824f, 164f), ScoreBugPlacement.stackedTopBox(tr, ScoreBugSize.STANDARD, brk))
        // Bottom left Standard: the top part at the bottom, the bar above (883..916).
        val bl = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT), geo))
        assertEquals(Box(96f, 916f, 429f, 1024f), ScoreBugPlacement.stackedTopBox(bl, ScoreBugSize.STANDARD, play))
        assertEquals(Box(96f, 916f, 339f, 1024f), ScoreBugPlacement.stackedTopBox(bl, ScoreBugSize.STANDARD, brk))

        for (c in corners) for (s in sizes) {
            val fp = assertNotNull(ScoreBugPlacement.place(input(c, s), geo))
            val rows = ScoreBugPlacement.stackedRowsBox(fp, s)
            for (w in listOf(play, brk)) {
                val top = ScoreBugPlacement.stackedTopBox(fp, s, w)
                assertEquals(w * k(s), top.r - top.l, 1e-3f, "$c $s $w")
                assertEquals(108f * k(s), top.b - top.t, 1e-3f)
                if (left(c)) assertEquals(fp.l, top.l, "$c $s $w: rows' outer edge") else assertEquals(fp.r, top.r, "$c $s $w: mirrored")
                assertTrue(top.l >= fp.l && top.r <= fp.r, "$c $s $w inside the footprint")
                // The rows sit at the outer edge in both widths.
                if (left(c)) assertEquals(top.l, rows.l) else assertEquals(top.r, rows.r)
                // Top part + open bar = the footprint's height.
                val barH = SportsConstants.STACKED_BAR_H * k(s)
                if (bottom(c)) assertEquals(fp.t, top.t - barH, 1e-3f) else assertEquals(fp.b, top.b + barH, 1e-3f)
            }
            // Right corners are the left ones reflected on the screen.
            if (!left(c)) {
                val lc = if (c == ScoreCorner.TOP_RIGHT) ScoreCorner.TOP_LEFT else ScoreCorner.BOTTOM_LEFT
                val lfp = assertNotNull(ScoreBugPlacement.place(input(lc, s), geo))
                assertEquals(Box(1920f - lfp.r, lfp.t, 1920f - lfp.l, lfp.b), fp, "$c $s footprint mirror")
                for (w in listOf(play, brk)) {
                    val l = ScoreBugPlacement.stackedTopBox(lfp, s, w)
                    assertEquals(Box(1920f - l.r, l.t, 1920f - l.l, l.b), ScoreBugPlacement.stackedTopBox(fp, s, w), "$c $s $w")
                }
            }
        }
    }

    /** Every SB §4.4 row as (name, modifier), as `ScoreBugPlacementTest`. */
    private val rows: List<Pair<String, (PlacementInput) -> PlacementInput>> = listOf(
        "bare" to { i -> i },
        "passive" to { i -> i.copy(bar = BottomBar.PASSIVE_INFO) },
        "userBar" to { i -> i.copy(bar = BottomBar.USER_BAR) },
        "quick" to { i -> i.copy(bar = BottomBar.QUICK) },
        "subtitles" to { i -> i.copy(textTrackOn = true) },
        "subtitlesUserBar" to { i -> i.copy(textTrackOn = true, bar = BottomBar.USER_BAR) },
        "channelList" to { i -> i.copy(channelList = true) },
        "stats" to { i -> i.copy(stats = true) },
        "groupCard" to { i -> i.copy(groupCard = true) },
        "picker" to { i -> i.copy(picker = true) },
        "numberEntry" to { i -> i.copy(numberEntry = true) },
        "probing" to { i -> i.copy(probing = probing) },
        "toast" to { i -> i.copy(toast = true) },
        "reminder" to { i -> i.copy(reminder = true) },
        "modal" to { i -> i.copy(modal = true) },
        "screenOff" to { i -> i.copy(screenOff = true) },
    )

    private fun expected(row: String, c: ScoreCorner, s: ScoreBugSize): Box? {
        val w = 333f * k(s)
        val h = 141f * k(s)
        val l = if (left(c)) 96f else 1920f - 96f - w
        val top = Box(l, 56f, l + w, 56f + h)
        val rest = if (bottom(c)) Box(l, 1080f - 56f - h, l + w, 1080f - 56f) else top
        fun lifted(barTop: Float) = Box(l, barTop - 16f - h, l + w, barTop - 16f)
        return when (row) {
            "bare" -> rest
            // The probing line (600..1320 × 72..128) clears both NFL Stacked top footprints (≤ 512.25 / ≥ 1407.75).
            "probing" -> rest
            "passive" -> if (bottom(c)) null else rest
            "userBar" -> if (bottom(c)) lifted(820f) else rest
            "quick" -> if (bottom(c)) lifted(952f) else rest
            "subtitles", "subtitlesUserBar" -> top
            "channelList" -> if (left(c)) null else rest
            "stats" -> if (left(c) && !bottom(c)) null else rest
            "groupCard" -> if (left(c) && !bottom(c)) null else rest
            // §5.1: the picker sheet (1376, 48)–(1872, 928) still covers both right footprints.
            "picker" -> if (!left(c)) null else rest
            "numberEntry" -> if (!left(c) && !bottom(c)) null else rest
            "toast" -> if (bottom(c) && left(c)) null else rest
            "reminder" -> if (bottom(c) && !left(c)) null else rest
            "modal", "screenOff" -> null
            else -> error(row)
        }
    }

    @Test
    fun everyHideAndLiftRuleRunsOnTheFootprint() {
        for ((name, mod) in rows) for (c in corners) for (s in sizes) {
            assertEquals(expected(name, c, s), ScoreBugPlacement.place(mod(input(c, s)), geo), "$name $c $s")
        }
        // A user bar lifts a bottom footprint to 804; the quick bar to 936.
        assertEquals(804f, assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT).copy(bar = BottomBar.USER_BAR), geo)).b)
        assertEquals(936f, assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT).copy(bar = BottomBar.QUICK), geo)).b)
        // Subtitles move a bottom corner to the top: its bar then drops downwards (ST S11); lifted keeps it above.
        val moved = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT).copy(textTrackOn = true), geo))
        assertEquals(Box(1491f, 56f, 1824f, 197f), moved)
        assertFalse(ScoreBugPlacement.stackedBarUp(moved))
        assertTrue(ScoreBugPlacement.stackedMirrored(moved))
        val lifted = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT).copy(bar = BottomBar.USER_BAR), geo))
        assertTrue(ScoreBugPlacement.stackedBarUp(lifted))
    }

    private fun margin(s: ScoreBugSize) =
        if (s == ScoreBugSize.LARGE) SportsConstants.GOAL_LAYER_MARGIN_LARGE else SportsConstants.GOAL_LAYER_MARGIN

    private fun glow(i: PlacementInput, g: OverlayGeometry = geo): Boolean? =
        ScoreBugPlacement.place(i, g)?.let { ScoreBugPlacement.glowAllowed(i, g, it, margin(i.size)) }

    @Test
    fun theGlowHaloIsTheFootprintInflatedByM() {
        assertEquals(74f, margin(ScoreBugSize.STANDARD))
        assertEquals(76f, margin(ScoreBugSize.LARGE))
        // §5.1: top right Standard (1417, −18)–(1898, 271); Large (1331.75, −20)–(1900, 308.25).
        val tr = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT), geo))
        val m = margin(ScoreBugSize.STANDARD)
        assertEquals(Box(1417f, -18f, 1898f, 271f), Box(tr.l - m, tr.t - m, tr.r + m, tr.b + m))
        val trL = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT, ScoreBugSize.LARGE), geo))
        val mL = margin(ScoreBugSize.LARGE)
        assertEquals(Box(1331.75f, -20f, 1900f, 308.25f), Box(trL.l - mL, trL.t - mL, trL.r + mL, trL.b + mL))
        // §4.5: the leaf layer = footprint + 2 M: 481 × 289 Standard, 568.25 × 328.25 Large.
        assertEquals(481f, tr.r - tr.l + 2 * m); assertEquals(289f, tr.b - tr.t + 2 * m)
        assertEquals(568.25f, trL.r - trL.l + 2 * mL); assertEquals(328.25f, trL.b - trL.t + 2 * mL)
        // An overlay one pixel inside the halo stops the glow; at the halo's edge it does not.
        val i = input(ScoreCorner.TOP_RIGHT).copy(stats = true)
        assertEquals(false, glow(i, geo.copy(stats = Box(1000f, 56f, 1418f, 300f))))
        assertEquals(true, glow(i, geo.copy(stats = Box(1000f, 56f, 1417f, 300f))))
        val l = input(ScoreCorner.TOP_RIGHT, ScoreBugSize.LARGE).copy(stats = true)
        assertEquals(false, glow(l, geo.copy(stats = Box(1000f, 56f, 1332.75f, 300f))))
        assertEquals(true, glow(l, geo.copy(stats = Box(1000f, 56f, 1331.75f, 300f))))
        for (c in corners) for (s in sizes) {
            assertEquals(true, glow(input(c, s)), "bare $c $s")
            for (bar in listOf(BottomBar.USER_BAR, BottomBar.QUICK)) {
                assertEquals(!bottom(c), glow(input(c, s).copy(bar = bar)), "$c $s $bar")
                assertEquals(true, glow(input(c, s).copy(bar = bar, textTrackOn = true)), "$c $s $bar subtitles")
            }
        }
    }

    @Test
    fun noPlacedFootprintIntersectsAnActiveOverlay() {
        val flags = listOf<(PlacementInput) -> PlacementInput>(
            { it.copy(channelList = true) }, { it.copy(stats = true) }, { it.copy(groupCard = true) },
            { it.copy(picker = true) }, { it.copy(numberEntry = true) }, { it.copy(probing = probing) },
        )
        for (c in corners) for (s in sizes) for (bar in BottomBar.entries) for (tt in listOf(false, true)) {
            for (mask in 0 until (1 shl flags.size)) {
                var i = input(c, s).copy(bar = bar, textTrackOn = tt)
                flags.forEachIndexed { n, f -> if (mask and (1 shl n) != 0) i = f(i) }
                val box = ScoreBugPlacement.place(i, geo) ?: continue
                val active = buildList {
                    if (i.channelList) add(geo.channelList)
                    if (i.stats) add(geo.stats)
                    if (i.groupCard) add(geo.groupCard)
                    if (i.picker) add(geo.picker)
                    if (i.numberEntry) add(geo.numberEntry)
                    i.probing?.let(::add)
                }
                assertTrue(active.none { it.intersects(box) }, "$c $s $bar $mask")
                assertTrue(box.l >= 96f && box.r <= 1824f && box.t >= 56f && box.b <= 1024f, "$box")
                assertEquals(333f * k(s), box.r - box.l, 1e-3f)
            }
        }
    }
}
