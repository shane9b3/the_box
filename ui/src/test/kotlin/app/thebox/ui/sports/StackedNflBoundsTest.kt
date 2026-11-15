package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.sports.StackedNflRunFixtures as F
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-nfl-stacked.md §9.2 `StackedNflBoundsTest` (WP-4): in every corner at both sizes, the drawn box
 * (`SCORE_BUG_TAG`, the top part plus the open bar) is where §5.1 puts it (the rows hug the screen-edge inset, the bar
 * below a top corner's box and above a bottom one's), and the bounded layer (`STACKED_NFL_LAYER_TAG`) is the placed
 * footprint + 2 M (§4.5). The layer is checked against `ScoreBugPlacement`'s own footprint (WP-2's), the drawn box against
 * the §5.1 numbers, which do not depend on the footprint's height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedNflBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedNflProbe()
    private var placement by mutableStateOf(F.input())

    private fun render() {
        repo.bug.value = F.view()
        rule.mainClock.autoAdvance = false
        val hooks = F.hooks(probe)
        rule.setContent {
            BoxDesignScale { StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2_000)
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val n = minOf(16L, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    /**
     * The drawn box's node, unclipped (position and size): `boundsInRoot` is clipped by the layer, and until WP-2's real
     * footprint lands the placement stub's Pill-height footprint clips a Large box's bar.
     */
    private fun drawn(): Rect {
        val n = rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val p = n.positionInRoot
        return Rect(p.x, p.y, p.x + n.size.width, p.y + n.size.height)
    }

    private fun assertRect(msg: String, want: Rect, got: Rect) {
        assertEquals("$msg: left", want.left, got.left, 1f)
        assertEquals("$msg: top", want.top, got.top, 1f)
        assertEquals("$msg: right", want.right, got.right, 1f)
        assertEquals("$msg: bottom", want.bottom, got.bottom, 1f)
    }

    /** §5.1's table: the drawn play box with its bar, per corner and size. */
    private val expected = mapOf(
        (ScoreCorner.TOP_RIGHT to ScoreBugSize.STANDARD) to Rect(1491f, 56f, 1824f, 197f),
        (ScoreCorner.TOP_LEFT to ScoreBugSize.STANDARD) to Rect(96f, 56f, 429f, 197f),
        (ScoreCorner.BOTTOM_RIGHT to ScoreBugSize.STANDARD) to Rect(1491f, 883f, 1824f, 1024f),
        (ScoreCorner.BOTTOM_LEFT to ScoreBugSize.STANDARD) to Rect(96f, 883f, 429f, 1024f),
        (ScoreCorner.TOP_RIGHT to ScoreBugSize.LARGE) to Rect(1407.75f, 56f, 1824f, 232.25f),
        (ScoreCorner.TOP_LEFT to ScoreBugSize.LARGE) to Rect(96f, 56f, 512.25f, 232.25f),
        (ScoreCorner.BOTTOM_RIGHT to ScoreBugSize.LARGE) to Rect(1407.75f, 847.75f, 1824f, 1024f),
        (ScoreCorner.BOTTOM_LEFT to ScoreBugSize.LARGE) to Rect(96f, 847.75f, 512.25f, 1024f),
    )

    @Test
    fun everyCornerAndSizePlacesTheDrawnBoxAndTheLayer() {
        render()
        for ((key, want) in expected) {
            val (corner, size) = key
            placement = F.input(corner, size)
            Snapshot.sendApplyNotifications()
            advance(1_000)
            val name = "$corner $size"
            assertRect(name, want, drawn())
            val a = probe.args!!()
            assertEquals("$name: mirrored on the right", corner == ScoreCorner.TOP_RIGHT || corner == ScoreCorner.BOTTOM_RIGHT, a.mirrored)
            assertEquals("$name: the bar opens above a bottom corner's box", corner == ScoreCorner.BOTTOM_RIGHT || corner == ScoreCorner.BOTTOM_LEFT, a.up)
            val m = if (size == ScoreBugSize.LARGE) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val layer = rule.onNodeWithTag(STACKED_NFL_LAYER_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val fw = ScoreBugPlacement.width(size, League.NFL, NhlBugStyle.STACKED)
            val fh = ScoreBugPlacement.height(size, League.NFL, NhlBugStyle.STACKED)
            assertEquals("$name: layer width = footprint + 2 M", fw + 2 * m, layer.size.width.toFloat(), 1f)
            assertEquals("$name: layer height = footprint + 2 M", fh + 2 * m, layer.size.height.toFloat(), 1f)
            val foot = ScoreBugPlacement.cornerBox(corner, size, League.NFL, NhlBugStyle.STACKED)
            assertEquals("$name: the layer is the footprint inflated by M", foot.l - m, layer.positionInRoot.x, 1f)
        }
    }

    @Test
    fun aBreakDrawsTheRowsOnlyBoxAtTheSameOuterEdge() {
        render()
        repo.bug.value = F.halftime()
        advance(2_000)
        assertRect("top right, halftime (243)", Rect(1581f, 56f, 1824f, 197f), drawn())
        placement = F.input(ScoreCorner.BOTTOM_LEFT, ScoreBugSize.LARGE)
        Snapshot.sendApplyNotifications()
        advance(1_000)
        assertRect("bottom left Large, halftime (303.75)", Rect(96f, 847.75f, 399.75f, 1024f), drawn())
    }

    @Test
    fun staleInPlayDrawsTheBoxWithoutItsBar() {
        render()
        repo.bug.value = F.stale()
        advance(2_000)
        assertRect("top right, stale: 333 × 108", Rect(1491f, 56f, 1824f, 164f), drawn())
    }
}
