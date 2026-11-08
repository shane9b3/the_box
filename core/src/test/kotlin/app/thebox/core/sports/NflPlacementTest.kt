package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-nfl.md §4.1, §4.3, §4.4, §10.1: the NFL pill and tab, 4 corners × 2 sizes. */
class NflPlacementTest {
    private val geo = OverlayGeometryFixture.DESIGN

    private fun input(corner: ScoreCorner, size: ScoreBugSize = ScoreBugSize.STANDARD, league: League = League.NFL) = PlacementInput(
        corner = corner, size = size, placing = false, bar = BottomBar.NONE,
        channelList = false, stats = false, groupCard = false, picker = false, numberEntry = false,
        probing = null, toast = false, reminder = false, modal = false, textTrackOn = false, screenOff = false,
        league = league,
    )

    private fun left(c: ScoreCorner) = c == ScoreCorner.TOP_LEFT || c == ScoreCorner.BOTTOM_LEFT
    private fun bottom(c: ScoreCorner) = c == ScoreCorner.BOTTOM_LEFT || c == ScoreCorner.BOTTOM_RIGHT

    @Test
    fun nflBoxesFourCornersBothSizes() {
        for (c in ScoreCorner.entries) for (s in ScoreBugSize.entries) {
            val large = s == ScoreBugSize.LARGE
            val w = if (large) 650f else 520f
            val h = if (large) 88f else 70f
            val l = if (left(c)) 96f else 1920f - 96f - w
            val t = if (bottom(c)) 1080f - 56f - h else 56f
            val want = Box(l, t, l + w, t + h)
            assertEquals(w, ScoreBugPlacement.width(s, League.NFL))
            assertEquals(want, ScoreBugPlacement.cornerBox(c, s, League.NFL), "$c $s")
            assertEquals(want, ScoreBugPlacement.place(input(c, s), geo), "$c $s place")
            assertEquals(want, ScoreBugPlacement.place(input(c, s).copy(placing = true), geo), "$c $s place mode")
        }
        // §4.1 rest boxes.
        assertEquals(1304f, ScoreBugPlacement.cornerBox(ScoreCorner.TOP_RIGHT, ScoreBugSize.STANDARD, League.NFL).l)
        assertEquals(954f, ScoreBugPlacement.cornerBox(ScoreCorner.BOTTOM_LEFT, ScoreBugSize.STANDARD, League.NFL).t)
        assertEquals(1174f, ScoreBugPlacement.cornerBox(ScoreCorner.BOTTOM_RIGHT, ScoreBugSize.LARGE, League.NFL).l)
        assertEquals(936f, ScoreBugPlacement.cornerBox(ScoreCorner.BOTTOM_RIGHT, ScoreBugSize.LARGE, League.NFL).t)
    }

    @Test
    fun theNhlDefaultIsUnchanged() {
        for (c in ScoreCorner.entries) for (s in ScoreBugSize.entries) {
            assertEquals(ScoreBugPlacement.cornerBox(c, s), ScoreBugPlacement.cornerBox(c, s, League.NHL))
            assertEquals(ScoreBugPlacement.width(s), ScoreBugPlacement.width(s, League.NHL))
            assertEquals(ScoreBugPlacement.tabInset(s), ScoreBugPlacement.tabInset(s, League.NHL))
            assertEquals(ScoreBugPlacement.cornerBox(c, s), ScoreBugPlacement.place(input(c, s, League.NHL), geo))
        }
    }

    @Test
    fun theWiderPillMeetsTheOverlayRules() {
        // The picker sheet (x ≥ 1376, down to y 928) hides the top right, as the NHL; the bottom right (y 954) clears
        // it, and the left corners stay.
        for (c in ScoreCorner.entries) {
            val p = ScoreBugPlacement.place(input(c).copy(picker = true), geo)
            if (c == ScoreCorner.TOP_RIGHT) assertNull(p, "$c picker") else assertNotNull(p, "$c picker")
            assertEquals(p == null, ScoreBugPlacement.place(input(c, league = League.NHL).copy(picker = true), geo) == null, "$c as the NHL")
        }
        // Top left spans 96–616: the stats, the group card and the channel list hide it.
        assertEquals(616f, ScoreBugPlacement.cornerBox(ScoreCorner.TOP_LEFT, ScoreBugSize.STANDARD, League.NFL).r)
        assertNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT).copy(stats = true), geo))
        assertNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT).copy(groupCard = true), geo))
        assertNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT).copy(channelList = true), geo))
        // The toast hides bottom left, the reminder bottom right; a user bar lifts a bottom bug.
        assertNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT).copy(toast = true), geo))
        assertNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT).copy(reminder = true), geo))
        val lifted = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT).copy(bar = BottomBar.USER_BAR), geo))
        assertEquals(820f - 16f, lifted.b)
        assertEquals(520f, lifted.r - lifted.l)
    }

    @Test
    fun tabBoxFourCornersBothSizes() {
        for (c in ScoreCorner.entries) for (s in ScoreBugSize.entries) {
            val large = s == ScoreBugSize.LARGE
            val i = input(c, s)
            val pill = assertNotNull(ScoreBugPlacement.place(i, geo))
            val inset = if (large) 46f else 37f
            val depth = if (large) 45f else 36f
            val want = if (bottom(c)) {
                Box(pill.l + inset, pill.t - depth, pill.r - inset, pill.t)
            } else {
                Box(pill.l + inset, pill.b, pill.r - inset, pill.b + depth)
            }
            val tab = ScoreBugPlacement.tabBox(i, pill)
            assertEquals(want, tab, "$c $s")
            assertEquals(if (large) 558f else 446f, tab.r - tab.l, "§4.3 width $c $s")
            assertEquals(inset, ScoreBugPlacement.tabInset(s, League.NFL))
        }
        // §4.4: at rest the Standard tab's edges are at y 162 (top) and y 918 (bottom), as PP.
        val tr = ScoreCorner.TOP_RIGHT
        val bl = ScoreCorner.BOTTOM_LEFT
        assertEquals(162f, ScoreBugPlacement.tabBox(input(tr), ScoreBugPlacement.cornerBox(tr, ScoreBugSize.STANDARD, League.NFL)).b)
        assertEquals(918f, ScoreBugPlacement.tabBox(input(bl), ScoreBugPlacement.cornerBox(bl, ScoreBugSize.STANDARD, League.NFL)).t)
    }

    /** An overlay box beside the tab's outer edge (away from the pill) at [gap] px, overlapping it in x. */
    private fun beside(tab: Box, outerIsBottom: Boolean, gap: Float): Box =
        if (outerIsBottom) Box(tab.l, tab.b + gap, tab.r, tab.b + gap + 40f) else Box(tab.l, tab.t - gap - 40f, tab.r, tab.t - gap)

    @Test
    fun tabAllowedWithEachOverlayAt28And30Px() {
        val overlays: List<Pair<String, (PlacementInput, OverlayGeometry, Box) -> Pair<PlacementInput, OverlayGeometry>>> = listOf(
            "channelList" to { i, g, b -> i.copy(channelList = true) to g.copy(channelList = b) },
            "stats" to { i, g, b -> i.copy(stats = true) to g.copy(stats = b) },
            "groupCard" to { i, g, b -> i.copy(groupCard = true) to g.copy(groupCard = b) },
            "picker" to { i, g, b -> i.copy(picker = true) to g.copy(picker = b) },
            "numberEntry" to { i, g, b -> i.copy(numberEntry = true) to g.copy(numberEntry = b) },
            "probing" to { i, g, b -> i.copy(probing = b) to g },
        )
        for (c in ScoreCorner.entries) for (s in ScoreBugSize.entries) {
            val base = input(c, s)
            val pill = ScoreBugPlacement.cornerBox(c, s, League.NFL)
            val tab = ScoreBugPlacement.tabBox(base, pill)
            assertTrue(ScoreBugPlacement.tabAllowed(base, geo, pill), "$c $s bare")
            assertFalse(ScoreBugPlacement.tabAllowed(base.copy(placing = true), geo, pill), "$c $s place mode")
            for ((name, apply) in overlays) {
                val (near, nearGeo) = apply(base, geo, beside(tab, !bottom(c), 28f))
                assertFalse(ScoreBugPlacement.tabAllowed(near, nearGeo, pill), "$c $s $name at 28 px")
                val (far, farGeo) = apply(base, geo, beside(tab, !bottom(c), 30f))
                assertTrue(ScoreBugPlacement.tabAllowed(far, farGeo, pill), "$c $s $name at 30 px")
            }
        }
    }

    @Test
    fun tabReachFitsTheLayerMargin() {
        // §4.3: depth + shadow dy + 3σ ≤ M (the NHL's margin, unchanged).
        val std = ScoreBugPlacement.tabDepth(ScoreBugSize.STANDARD) + SportsConstants.PP_SHADOW_DY + 3 * SportsConstants.PP_SHADOW_SIGMA
        val large = ScoreBugPlacement.tabDepth(ScoreBugSize.LARGE) + SportsConstants.PP_SHADOW_DY_LARGE + 3 * SportsConstants.PP_SHADOW_SIGMA
        assertTrue(std <= SportsConstants.GOAL_LAYER_MARGIN, "$std")
        assertTrue(large <= SportsConstants.GOAL_LAYER_MARGIN_LARGE, "$large")
        // The tab's side inset keeps it inside the pill's width.
        for (s in ScoreBugSize.entries) {
            assertTrue(ScoreBugPlacement.tabInset(s, League.NFL) > 0f)
        }
    }
}
