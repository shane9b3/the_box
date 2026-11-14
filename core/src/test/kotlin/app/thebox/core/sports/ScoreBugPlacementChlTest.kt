package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * (CHL WP-2, docs/design/m6-chl-leagues.md §1.6 #18, #19) A CHL bug is a hockey bug: the Pill's width and tab inset, and
 * the Stacked footprint (392 x 141, mirrored in the right-hand corners like the NHL), never the NFL's.
 */
class ScoreBugPlacementChlTest {
    private val geo = OverlayGeometryFixture.DESIGN

    private fun input(corner: ScoreCorner, league: League, style: NhlBugStyle, size: ScoreBugSize = ScoreBugSize.STANDARD) =
        PlacementInput(
            corner = corner, size = size, placing = false, bar = BottomBar.NONE,
            channelList = false, stats = false, groupCard = false, picker = false, numberEntry = false,
            probing = null, toast = false, reminder = false, modal = false, textTrackOn = false, screenOff = false,
            league = league, style = style,
        )

    @Test
    fun aChlPillIsTheNhlPill() {
        for (league in CHL_LEAGUES) for (size in ScoreBugSize.entries) {
            assertEquals(ScoreBugPlacement.width(size, League.NHL), ScoreBugPlacement.width(size, league), "$league $size width")
            assertEquals(ScoreBugPlacement.tabInset(size, League.NHL), ScoreBugPlacement.tabInset(size, league), "$league $size inset")
            assertEquals(
                ScoreBugPlacement.cornerBox(ScoreCorner.TOP_RIGHT, size, League.NHL),
                ScoreBugPlacement.cornerBox(ScoreCorner.TOP_RIGHT, size, league),
            )
        }
    }

    @Test
    fun aChlStackedBugGetsTheStackedFootprintMirroredInRightCorners() {
        for (league in CHL_LEAGUES) for (size in ScoreBugSize.entries) for (corner in ScoreCorner.entries) {
            val nhl = ScoreBugPlacement.cornerBox(corner, size, League.NHL, NhlBugStyle.STACKED)
            val chl = ScoreBugPlacement.cornerBox(corner, size, league, NhlBugStyle.STACKED)
            assertEquals(nhl, chl, "$league $size $corner")
            assertEquals(ScoreBugPlacement.stackedScale(size) * 392f, chl.r - chl.l, "footprint width")
            assertEquals(ScoreBugPlacement.stackedScale(size) * 141f, chl.b - chl.t, "footprint height")
        }
        val right = ScoreBugPlacement.cornerBox(ScoreCorner.TOP_RIGHT, ScoreBugSize.STANDARD, League.OHL, NhlBugStyle.STACKED)
        assertEquals(1920f - 96f - 392f, right.l)
        for (league in CHL_LEAGUES) {
            val placed = assertNotNull(ScoreBugPlacement.place(input(ScoreCorner.TOP_RIGHT, league, NhlBugStyle.STACKED), geo))
            assertEquals(1920f - 96f - 392f, placed.l, "$league placed")
        }
    }
}
