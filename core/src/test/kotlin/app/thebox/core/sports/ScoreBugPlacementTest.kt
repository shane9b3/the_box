package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-score-bug.md §4.3, §4.4, §8: every row × four corners × two sizes. */
class ScoreBugPlacementTest {
    /** The values of `PlayerUi.overlayGeometry()` (`:ui`'s ScoreBugBoundsTest checks the two agree). */
    private val geo = OverlayGeometryFixture.DESIGN
    private val probing = Box(600f, 72f, 1320f, 128f)

    private fun input(corner: ScoreCorner, size: ScoreBugSize = ScoreBugSize.STANDARD) = PlacementInput(
        corner = corner, size = size, placing = false, bar = BottomBar.NONE,
        channelList = false, stats = false, groupCard = false, picker = false, numberEntry = false,
        probing = null, toast = false, reminder = false, modal = false, textTrackOn = false, screenOff = false,
    )

    private fun left(c: ScoreCorner) = c == ScoreCorner.TOP_LEFT || c == ScoreCorner.BOTTOM_LEFT
    private fun bottom(c: ScoreCorner) = c == ScoreCorner.BOTTOM_LEFT || c == ScoreCorner.BOTTOM_RIGHT
    private val sizes = ScoreBugSize.entries
    private val corners = ScoreCorner.entries

    /** Every row of §4.4 as (name, modifier). */
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
        val w = if (s == ScoreBugSize.LARGE) 506f else 404f
        val h = if (s == ScoreBugSize.LARGE) 88f else 70f
        val l = if (left(c)) 96f else 1920f - 96f - w
        val top = Box(l, 56f, l + w, 56f + h)
        val rest = if (bottom(c)) Box(l, 1080f - 56f - h, l + w, 1080f - 56f) else top
        fun lifted(barTop: Float) = Box(l, barTop - 16f - h, l + w, barTop - 16f)
        return when (row) {
            "bare" -> rest
            // The probing line (600..1320 × 72..128) clears both Standard top corners but not the Large ones.
            "probing" -> if (!bottom(c) && s == ScoreBugSize.LARGE) null else rest
            "passive" -> if (bottom(c)) null else rest
            "userBar" -> if (bottom(c)) lifted(820f) else rest
            "quick" -> if (bottom(c)) lifted(952f) else rest
            "subtitles", "subtitlesUserBar" -> top
            "channelList" -> if (left(c)) null else rest
            "stats" -> if (left(c) && !bottom(c)) null else rest
            "groupCard" -> if (left(c) && !bottom(c)) null else rest
            "picker" -> if (!left(c) && !bottom(c)) null else rest
            "numberEntry" -> if (!left(c) && !bottom(c)) null else rest
            "toast" -> if (bottom(c) && left(c)) null else rest
            "reminder" -> if (bottom(c) && !left(c)) null else rest
            "modal", "screenOff" -> null
            else -> error(row)
        }
    }

    @Test
    fun everyRowEveryCornerBothSizes() {
        for ((name, mod) in rows) for (c in corners) for (s in sizes) {
            assertEquals(expected(name, c, s), ScoreBugPlacement.place(mod(input(c, s)), geo), "$name $c $s")
        }
    }

    @Test
    fun designCornerRects() {
        assertEquals(Box(1420f, 56f, 1824f, 126f), ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT), geo))
        assertEquals(Box(96f, 56f, 500f, 126f), ScoreBugPlacement.place(input(ScoreCorner.TOP_LEFT), geo))
        assertEquals(Box(1420f, 954f, 1824f, 1024f), ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT), geo))
        assertEquals(Box(96f, 954f, 500f, 1024f), ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT), geo))
        // §4.4: the user bar puts the bottom edge at 804, the quick bar at 936.
        assertEquals(804f, ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_LEFT).copy(bar = BottomBar.USER_BAR), geo)!!.b)
        assertEquals(936f, ScoreBugPlacement.place(input(ScoreCorner.BOTTOM_RIGHT).copy(bar = BottomBar.QUICK), geo)!!.b)
    }

    @Test
    fun noBoxIntersectsAnActiveOverlayAndAllInsideTheInsets() {
        val flags = listOf<(PlacementInput) -> PlacementInput>(
            { it.copy(channelList = true) }, { it.copy(stats = true) }, { it.copy(groupCard = true) },
            { it.copy(picker = true) }, { it.copy(numberEntry = true) }, { it.copy(probing = probing) },
        )
        val bars = BottomBar.entries
        for (c in corners) for (s in sizes) for (bar in bars) for (tt in listOf(false, true)) {
            // every subset of the overlay flags
            for (mask in 0 until (1 shl flags.size)) {
                var i = input(c, s).copy(bar = bar, textTrackOn = tt)
                flags.forEachIndexed { k, f -> if (mask and (1 shl k) != 0) i = f(i) }
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
                assertTrue(box.l >= 96f && box.r <= 1920f - 96f && box.t >= 56f && box.b <= 1080f - 56f, "$box")
                when (bar) {
                    BottomBar.USER_BAR -> assertTrue(box.b <= geo.infoBarTop - 16f || box.t == 56f)
                    BottomBar.QUICK -> assertTrue(box.b <= geo.quickBarTop - 16f || box.t == 56f)
                    BottomBar.PASSIVE_INFO -> assertEquals(56f, box.t)
                    BottomBar.NONE -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ glowAllowed (goal animation §2.4, S1)

    private fun margin(s: ScoreBugSize) =
        if (s == ScoreBugSize.LARGE) SportsConstants.GOAL_LAYER_MARGIN_LARGE else SportsConstants.GOAL_LAYER_MARGIN

    private fun glow(i: PlacementInput, g: OverlayGeometry = geo): Boolean? =
        ScoreBugPlacement.place(i, g)?.let { ScoreBugPlacement.glowAllowed(i, g, it, margin(i.size)) }

    @Test
    fun glowIsAllowedOnABareCorner() {
        for (c in corners) for (s in sizes) assertEquals(true, glow(input(c, s)), "$c $s")
    }

    @Test
    fun glowIsOffWhileABarLiftsABottomCorner() {
        for (c in corners) for (s in sizes) for (bar in listOf(BottomBar.USER_BAR, BottomBar.QUICK)) {
            val i = input(c, s).copy(bar = bar)
            assertEquals(!bottom(c), glow(i), "$c $s $bar")
            // Subtitles move a bottom corner to the top: no bar lifts it, so the glow stays.
            assertEquals(true, glow(i.copy(textTrackOn = true)), "$c $s $bar subtitles")
        }
        // The passive info bar hides a bottom corner; a top corner keeps its glow.
        assertEquals(true, glow(input(ScoreCorner.TOP_RIGHT).copy(bar = BottomBar.PASSIVE_INFO)))
    }

    @Test
    fun glowIsOffWhenAnActiveOverlayIsInsideTheMargin() {
        // Top right Standard: 1420..1824 × 56..126; margin 74, so the halo starts at x 1346.
        val near = Box(1000f, 56f, 1380f, 300f) // 40 px left of the pill: inside the halo, clear of the pill
        val edge = Box(1000f, 56f, 1346f, 300f) // touching the halo's edge: outside it
        val i = input(ScoreCorner.TOP_RIGHT)
        val overlays = listOf<Pair<String, (PlacementInput, Box) -> Pair<PlacementInput, OverlayGeometry>>>(
            "stats" to { p, b -> p.copy(stats = true) to geo.copy(stats = b) },
            "channelList" to { p, b -> p.copy(channelList = true) to geo.copy(channelList = b) },
            "groupCard" to { p, b -> p.copy(groupCard = true) to geo.copy(groupCard = b) },
            "picker" to { p, b -> p.copy(picker = true) to geo.copy(picker = b) },
            "numberEntry" to { p, b -> p.copy(numberEntry = true) to geo.copy(numberEntry = b) },
        )
        for ((name, put) in overlays) {
            val (on, g) = put(i, near)
            assertNotNull(ScoreBugPlacement.place(on, g), "$name does not hide the bug")
            assertEquals(false, glow(on, g), "$name inside the margin")
            val (onEdge, gEdge) = put(i, edge)
            assertEquals(true, glow(onEdge, gEdge), "$name at the margin's edge")
            assertEquals(true, glow(i, g), "$name inactive")
        }
        // Large uses its own margin (76): 2 px further out still turns it off.
        val large = input(ScoreCorner.TOP_RIGHT, ScoreBugSize.LARGE) // 1318..1824
        val l = large.copy(stats = true)
        assertEquals(false, glow(l, geo.copy(stats = Box(1000f, 56f, 1318f - 75f, 300f))))
        assertEquals(true, glow(l, geo.copy(stats = Box(1000f, 56f, 1318f - 76f, 300f))))
    }

    @Test
    fun placeModeIgnoresTheHideRules() {
        for (c in corners) for (s in sizes) {
            val everything = input(c, s).copy(
                placing = true, bar = BottomBar.PASSIVE_INFO, channelList = true, stats = true, groupCard = true,
                picker = true, numberEntry = true, probing = probing, toast = true, reminder = true, textTrackOn = true,
            )
            assertEquals(ScoreBugPlacement.cornerBox(c, s), assertNotNull(ScoreBugPlacement.place(everything, geo)), "$c $s")
            assertNull(ScoreBugPlacement.place(everything.copy(modal = true), geo))
            assertNull(ScoreBugPlacement.place(everything.copy(screenOff = true), geo))
        }
    }
}
