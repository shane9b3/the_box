package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedTargets
import app.thebox.ui.R
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * docs/design/m6-score-bug-stacked-theme.md §10.2 `StackedBoundsTest` (Stacked WP-3; S11, §4.3, §6). In a 1920 × 1080
 * window (1 design px = 1 px):
 * - the bounded layer is the footprint + 2 M, and the footprint sits inside the 96 / 56 insets;
 * - `SCORE_BUG_TAG` is the **drawn** box: inside the footprint in all four corners at both sizes, in play, on a power
 *   play and in an intermission; the bar opens above the box in the bottom corners;
 * - the rows never move between states (the box's left edge, and its top part's top or bottom edge);
 * - the drop shadow and the baked glow never leave the layer, at every width and bar height, both sizes, bar up or down
 *   (rendered with the real painters, native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedProbe()
    private var placement by mutableStateOf(StackedRunFixtures.input())

    private fun render() {
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe)
        rule.setContent {
            BoxDesignScale { StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        repeat(150) { rule.mainClock.advanceTimeBy(16) }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertRect(msg: String, l: Float, t: Float, r: Float, b: Float, actual: Rect) {
        // Layout rounds each offset to whole px (the Large footprint is 176.25 tall).
        val e = 1.01f
        assertEquals("$msg left", l, actual.left, e); assertEquals("$msg top", t, actual.top, e)
        assertEquals("$msg right", r, actual.right, e); assertEquals("$msg bottom", b, actual.bottom, e)
    }

    private val states: List<Pair<String, ScoreBugView>> = listOf(
        "play" to StackedRunFixtures.view(),
        "power play" to StackedRunFixtures.view(pp = StackedRunFixtures.pp()),
        "intermission" to StackedRunFixtures.intermission(),
    )

    @Test
    fun theDrawnBoxSitsInTheFootprintInEveryCornerAtBothSizesAndTheRowsNeverMove() {
        repo.bug.value = StackedRunFixtures.view()
        render()
        for (size in ScoreBugSize.entries) {
            val k = ScoreBugPlacement.stackedScale(size)
            val m = if (size == ScoreBugSize.LARGE) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            for (corner in ScoreCorner.entries) {
                placement = StackedRunFixtures.input(corner, size)
                val foot = ScoreBugPlacement.cornerBox(corner, size, League.NHL, NhlBugStyle.STACKED)
                val bottom = corner == ScoreCorner.BOTTOM_LEFT || corner == ScoreCorner.BOTTOM_RIGHT
                // (Owner request 2026-11-13) The right corners are mirrored: the box's right edge (the rows' outer edge)
                // is the footprint's right edge, and the box grows on its left.
                val right = corner == ScoreCorner.TOP_RIGHT || corner == ScoreCorner.BOTTOM_RIGHT
                assertTrue("$corner/$size: the footprint inside the insets ($foot)",
                    foot.l >= K.SCORE_INSET_X && foot.r <= 1920f - K.SCORE_INSET_X && foot.t >= K.SCORE_INSET_Y && foot.b <= 1080f - K.SCORE_INSET_Y)
                val rowsLeft = HashSet<Float>()
                val rowsEdge = HashSet<Float>()
                for ((name, v) in states) {
                    repo.bug.value = v
                    settle()
                    val msg = "$corner/$size/$name"
                    assertRect("$msg: the layer is the footprint + 2 M", foot.l - m, foot.t - m, foot.r + m, foot.b + m, bounds(STACKED_LAYER_TAG))
                    val t = StackedTargets.of(v)
                    val w = (K.STACKED_ROWS_W + (if (t.sogOpen) K.STACKED_SOG_W else 0f) + (if (t.clockOpen) K.STACKED_CLOCK_W else 0f)) * k
                    val h = K.STACKED_H * k
                    val bar = if (t.bar != null) K.STACKED_BAR_H * k else 0f
                    val tag = bounds(SCORE_BUG_TAG)
                    val l = if (right) foot.r - w else foot.l
                    if (bottom) {
                        assertRect("$msg: the drawn box, its bar above", l, foot.b - h - bar, l + w, foot.b, tag)
                        assertTrue("$msg: up", probe.args!!().up)
                    } else {
                        assertRect("$msg: the drawn box, its bar below", l, foot.t, l + w, foot.t + h + bar, tag)
                        assertTrue("$msg: down", !probe.args!!().up)
                    }
                    assertEquals("$msg: mirrored exactly in the right corners", right, probe.args!!().mirrored)
                    assertTrue("$msg: inside the footprint ($tag in $foot)",
                        tag.left >= foot.l - 1 && tag.right <= foot.r + 1 && tag.top >= foot.t - 1 && tag.bottom <= foot.b + 1)
                    // The rows' outer edge. Mirrored, the node's left is rounded to whole px by layout, so its right edge
                    // is the margin within that rounding (the drawing itself is not rounded:
                    // theMirroredBoxEndsAtTheRightMarginAndTheRowsNeverMove pins the rows from the pixels).
                    if (right) assertEquals("$msg: the rows' outer edge is the footprint's right edge", foot.r, tag.right, 1.01f) else rowsLeft += tag.left
                    rowsEdge += if (bottom) tag.bottom else tag.top // the top part's outer edge
                }
                if (!right) assertEquals("$corner/$size: the rows' left edge never moves", 1, rowsLeft.size)
                assertEquals("$corner/$size: the top part's outer edge never moves", 1, rowsEdge.size)
            }
        }
    }

    /**
     * (Owner request 2026-11-13) In the right corners the drawn box ends at the right margin (1920 − 96) at even strength
     * on a power play and in a break, at both sizes; and the rows, found in the pixels of the real draw (the row
     * background's extent), are the same rectangle in all three states, hugging that margin.
     */
    @Test
    fun theMirroredBoxEndsAtTheRightMarginAndTheRowsNeverMove() {
        repo.bug.value = StackedRunFixtures.view()
        render()
        val margin = 1920f - K.SCORE_INSET_X
        for (size in ScoreBugSize.entries) for (corner in listOf(ScoreCorner.TOP_RIGHT, ScoreCorner.BOTTOM_RIGHT)) {
            val k = ScoreBugPlacement.stackedScale(size)
            placement = StackedRunFixtures.input(corner, size)
            val foot = ScoreBugPlacement.cornerBox(corner, size, League.NHL, NhlBugStyle.STACKED)
            val rows = ArrayList<Rect>()
            // Even strength (333), the power play (391.5) and the break (301.5, fix pass review L5).
            val views = listOf(StackedRunFixtures.view(), StackedRunFixtures.view(pp = StackedRunFixtures.pp()), StackedRunFixtures.intermission())
            for ((i, v) in views.withIndex()) {
                repo.bug.value = v
                settle()
                val msg = "$corner/$size ${listOf("even", "power play", "break")[i]}"
                val tag = bounds(SCORE_BUG_TAG)
                assertEquals("$msg: the box's right edge is the margin", margin, tag.right, 1.01f)
                val a = probe.args!!()
                assertTrue("$msg: mirrored", a.mirrored)
                // The layer as the runner draws it (its node offsets, the origin translation), captured.
                val layer = bounds(STACKED_LAYER_TAG)
                val bmp = rule.onNodeWithTag(STACKED_LAYER_TAG, useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val rowsBox = ScoreBugPlacement.stackedRowsBox(foot, size)
                // The row background (#12284F) inside the rows' band (2 px in from the top and bottom): its first and
                // last pixel.
                var l = Int.MAX_VALUE
                var r = Int.MIN_VALUE
                var t = Int.MAX_VALUE
                var b = Int.MIN_VALUE
                val y0 = (rowsBox.t - layer.top).toInt() + 2
                val y1 = (rowsBox.b - layer.top).toInt() - 2
                for (y in y0 until y1) for (x in 0 until bmp.width) {
                    if (bmp.getPixel(x, y) == (K.STACKED_ROW_BG or (0xFF shl 24))) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
                }
                assertTrue("$msg: the rows were drawn", l <= r)
                rows += Rect(layer.left + l, layer.top + t, layer.left + r + 1, layer.top + b + 1)
            }
            println("mirrored rows $corner/$size: even ${rows[0]}, power play ${rows[1]}, break ${rows[2]}")
            assertEquals("$corner/$size: the rows do not move on the power play", rows[0], rows[1])
            assertEquals("$corner/$size: the rows do not move in a break", rows[0], rows[2])
            // The row background stops at the stripe (3.6 wide at even strength, 6.3 on the PP side) and the rounded
            // corner, so its last pixel is a few px in from the margin; its first is the rows' left edge.
            assertEquals("$corner/$size: the rows' left edge", margin - K.STACKED_ROWS_W * k, rows[0].left, 1.01f)
            assertTrue("$corner/$size: the rows reach the margin's stripe (${rows[0].right})", rows[0].right in (margin - 8f * k)..margin)
        }
    }

    /** The place hint is beside the drawn box (N8), toward the centre and centred on its height. */
    @Test
    fun thePlaceHintIsPositionedFromTheDrawnBox() {
        placement = StackedRunFixtures.input(ScoreCorner.TOP_RIGHT, placing = true)
        render()
        val bug = bounds(SCORE_BUG_TAG)
        // (Owner request 2026-11-13) Mirrored: the sample's right edge at the margin.
        assertRect("the sample at the play width", 1491f, 56f, 1824f, 164f, bug)
        val hint = bounds(SCORE_BUG_HINT_TAG)
        assertTrue("left of the drawn box: $hint", hint.right <= bug.left)
        assertEquals((bug.top + bug.bottom) / 2f, (hint.top + hint.bottom) / 2f, 1.5f)
        placement = StackedRunFixtures.input(ScoreCorner.TOP_LEFT, placing = true)
        settle()
        val left = bounds(SCORE_BUG_TAG)
        assertTrue("right of a left-corner box, not of the footprint", bounds(SCORE_BUG_HINT_TAG).left in left.right..left.right + 20f)
    }

    // ------------------------------------------------------------------ a right-to-left locale (review U S2)

    /**
     * (Fix pass 2026-11-11, review U S2) Under a right-to-left layout direction (a Hebrew or Arabic system locale), hosted
     * in a full-screen box as `FullScreenHost` hosts it, the layer, the drawn box and the place hint land exactly where
     * they do left-to-right, in every corner, at both sizes, at the play and the power-play widths.
     */
    @Test
    fun aRightToLeftLocaleMovesNothing() {
        var dir by mutableStateOf(LayoutDirection.Ltr)
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                Box(Modifier.fillMaxSize()) {
                    BoxDesignScale { StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
                }
            }
        }
        val views = listOf(StackedRunFixtures.view(), StackedRunFixtures.view(pp = StackedRunFixtures.pp()))
        for (size in ScoreBugSize.entries) for (corner in ScoreCorner.entries) for (placing in listOf(false, true)) for (v in views) {
            val got = HashMap<LayoutDirection, List<Rect>>()
            for (d in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                dir = d
                placement = StackedRunFixtures.input(corner, size, placing = placing)
                repo.bug.value = v
                settle()
                val hint = if (placing) listOf(bounds(SCORE_BUG_HINT_TAG)) else emptyList()
                got[d] = listOf(bounds(STACKED_LAYER_TAG), bounds(SCORE_BUG_TAG), bounds(SCORE_BUG_LIVE_TAG)) + hint
            }
            val msg = "$corner/$size placing=$placing pp=${v.powerPlay != null}"
            val ltr = got.getValue(LayoutDirection.Ltr)
            val rtl = got.getValue(LayoutDirection.Rtl)
            for (i in ltr.indices) assertRect("$msg: node $i under RTL", ltr[i].left, ltr[i].top, ltr[i].right, ltr[i].bottom, rtl[i])
            if (placing) {
                val box = ltr[1]
                val hint = rtl[3]
                assertTrue("$msg: the hint clears the drawn box ($hint vs $box)", hint.right <= box.left + 1 || hint.left >= box.right - 1)
            }
        }
    }

    // ------------------------------------------------------------------ the shadow and the glow inside the layer

    @Test
    fun theShadowAndTheGlowNeverLeaveTheLayer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val res = context.resources
        val pair = NhlStackedColours.pair("CGY", "EDM")
        for (size in ScoreBugSize.entries) {
            val large = size == ScoreBugSize.LARGE
            val k = ScoreBugPlacement.stackedScale(size)
            val s = k // 1 design px = 1 px
            val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
            val footW = ScoreBugPlacement.width(size, League.NHL, NhlBugStyle.STACKED)
            val footH = ScoreBugPlacement.height(size, League.NHL, NhlBugStyle.STACKED)
            val painter = StackedPainter(s, large, sansTypeface(context, R.font.instrument_sans_bold), StackedPainter.barTypeface(context))
            val goal = StackedGoalPainter(s, large, sansTypeface(context, R.font.barlow_condensed_black_italic), sansTypeface(context, R.font.instrument_sans_bold), "GOAL")
            val shadow = painter.bakeShadow()
            val g = GoalTimeline.sample(GoalSpec.STACKED, 1_800f, GoalFrame()) // the glow's peak
            for (cols in listOf(false to true, true to true, true to false)) for (barOpen in listOf(false, true)) for (up in listOf(false, true)) {
                val v = StackedRunFixtures.view(pp = if (barOpen) StackedRunFixtures.pp() else null)
                val t = StackedTargets.of(v)
                val f = StackedFrame().apply {
                    sogWidth = if (cols.first) 1f else 0f; sogAlpha = sogWidth; sogDx = 0f
                    clockWidth = if (cols.second) 1f else 0f; clockAlpha = clockWidth
                    barHeight = if (barOpen) 1f else 0f; barClosed = !barOpen; stripeAX = 0f; stripeBX = 0f; barTextAlpha = 1f; barTextDx = 0f
                }
                val bar = BarContent().also {
                    it.kind = app.thebox.core.sports.BarKind.POWER_PLAY; it.side = Side.HOME; it.secs = 79
                    painter.fillBar(it, pair, "CGY", "EDM", res)
                }
                goal.begin(Side.HOME, NhlTeamColours.of("EDM"), null, "CGY", "EDM", 0, 3, f.boxW)
                val glow = goal.bakeGlow(f.boxW * s, (K.STACKED_H + f.barH) * s)!!
                val a = StackedDrawArgs().apply {
                    body = painter.frame(v, t, res); colours = pair; this.f = f; this.bar = bar; this.up = up
                    this.shadow = shadow; this.goal = goal; this.g = g; this.glow = glow; glowOn = true
                }
                val extra = 60
                val lw = (footW + 2 * m).toInt()
                val lh = (footH + 2 * m).toInt()
                val bmp = Bitmap.createBitmap(lw + 2 * extra, lh + 2 * extra, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val topY = if (up) footH - K.STACKED_H * k else 0f
                c.translate(extra + m, extra + m + topY)
                painter.drawFrame(c, a)
                var outside = 0
                var worst = 0
                for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                    // One px of slack for the masks' ceil()ed size.
                    val inX = x >= extra - 1 && x < extra + lw + 1
                    val inY = y >= extra - 1 && y < extra + lh + 1
                    if (inX && inY) continue
                    val alpha = bmp.getPixel(x, y) ushr 24
                    if (alpha > 0) { outside++; worst = maxOf(worst, alpha) }
                }
                assertEquals("$size cols=$cols bar=$barOpen up=$up: pixels outside the layer (worst α $worst)", 0, outside)
            }
        }
    }
}
