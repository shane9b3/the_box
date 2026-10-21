package app.thebox.ui.guide.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.FooterHint
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Decision 2026-10-19, ruling 8: the footer is a measuring layout. Hints go left to right in list (priority) order, 36
 * design px apart, and the first one that would pass the available width (1792 design px, x 64 → 1856) ends the row:
 * it and everything after it are left out. Never wraps. Drawn at 1 dp = 1 px so bounds are design px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths
class FooterHintsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(hints: List<FooterHint>, boxWidth: Float) {
        rule.setContent {
            BoxTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.width(boxWidth.dp)) { FooterHints(hints) }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Bounds of hint [i] when it is placed, else null. */
    private fun placed(i: Int): Rect? = rule.onAllNodesWithTag(footerHintTag(i), useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull { it.layoutInfo.isPlaced }?.boundsInRoot

    private fun placedCount(n: Int): Int = (0 until n).count { placed(it) != null }

    @Test
    fun anOverflowingListDropsFromTheEndAndNeverWraps() {
        // A fake list far wider than 1792 px; the box is wider still, so the 1792 cap is what stops it.
        val n = 14
        show(List(n) { FooterHint("Hold Back", "Groups, Search, Settings") }, boxWidth = 3000f)
        val first = placed(0)!!
        val w = first.width
        val gap = FOOTER_GAP
        val expected = ((FOOTER_MAX_W + gap) / (w + gap)).toInt()
        assertTrue("the fake list overflows ($expected of $n fit, hint $w px)", expected in 1 until n)
        assertEquals(expected, placedCount(n))
        // A prefix: the first `expected` hints are placed, none after.
        for (i in 0 until n) assertEquals("hint $i", i < expected, placed(i) != null)
        for (i in 0 until expected) {
            val r = placed(i)!!
            assertEquals("hint $i in list order, $gap apart", first.left + i * (w + gap), r.left, 0.5f)
            assertEquals("one line: never wraps", first.top, r.top, 0.5f)
        }
        assertTrue("the last placed hint ends inside 1792", placed(expected - 1)!!.right - first.left <= FOOTER_MAX_W + 0.5f)
    }

    @Test
    fun aNarrowerParentWinsAndMixedWidthsKeepTheirOrder() {
        val hints = listOf(
            FooterHint("← → ↑ ↓", "Browse"), FooterHint("OK", "Watch"), FooterHint("Back", "Groups"),
            FooterHint("Hold OK", "Options"), FooterHint("Hold Back", "Full screen"), FooterHint("Red", "From start"),
            FooterHint("CH±", "Page"), FooterHint("0–9", "Channel"),
        )
        show(hints, boxWidth = 600f)
        val k = placedCount(hints.size)
        assertTrue("some, not all, fit in 600 px ($k)", k in 1 until hints.size)
        for (i in 0 until hints.size) assertEquals("hint $i", i < k, placed(i) != null)
        assertTrue(placed(k - 1)!!.right - placed(0)!!.left <= 600.5f)
    }

    @Test
    fun aShortListIsPlacedWhole() {
        show(listOf(FooterHint("OK", "Watch"), FooterHint("Back", "Now")), boxWidth = 1856f)
        assertEquals(2, placedCount(2))
        val a = placed(0)!!; val b = placed(1)!!
        assertEquals(a.right + FOOTER_GAP, b.left, 0.5f)
    }
}
