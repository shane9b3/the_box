package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked-yds.md §5.0 (the foundation): the YDS track's transitions (§3.5, the mock's `.yds` /
 * `.yds-in`), `NflYards.text` (§3.4) and the defaults that keep the app as it was.
 */
class StackedNflYdsFoundationTest {
    private val T = StackedNflTimeline
    private val Y = StackedNflTrack.YDS

    private fun rest(open: Boolean) = T.sample(Y, StackedNflTransition.STILL, 0f, StackedNflFrame(), open, StackedNflFrame())

    @Test
    fun theYdsTrackFollowsTheMock() {
        assertEquals(650f, T.totalMs(Y, StackedNflTransition.OPEN))
        assertEquals(650f, T.totalMs(Y, StackedNflTransition.CLOSE))
        assertEquals(0f, T.totalMs(Y, StackedNflTransition.STILL))
        val closed = rest(false)
        // Open: width 0..450 Std; contents wait 300, then α 300 and x 350.
        val o = { t: Float -> T.sample(Y, StackedNflTransition.OPEN, t, closed, true, StackedNflFrame()) }
        assertEquals(0f, o(0f).ydsWidth); assertEquals(1f, o(450f).ydsWidth)
        assertTrue(o(225f).ydsWidth in 0.01f..0.99f)
        assertEquals(0f, o(300f).ydsAlpha); assertEquals(K.STACKED_NFL_YDS_DX, o(300f).ydsDx)
        assertEquals(1f, o(600f).ydsAlpha); assertTrue(o(600f).ydsDx < 0f); assertEquals(0f, o(650f).ydsDx)
        // Close: contents out over 200, the width waits 200 then 450 Std.
        val open = rest(true)
        val c = { t: Float -> T.sample(Y, StackedNflTransition.CLOSE, t, open, false, StackedNflFrame()) }
        assertEquals(1f, c(200f).ydsWidth); assertEquals(0f, c(200f).ydsAlpha); assertEquals(K.STACKED_NFL_YDS_DX, c(200f).ydsDx)
        assertTrue(c(425f).ydsWidth in 0.01f..0.99f); assertEquals(0f, c(650f).ydsWidth)
        // The pin snaps the width only (as CLOCK).
        val p = T.samplePinned(Y, StackedNflTransition.OPEN, 100f, closed, true, StackedNflFrame())
        assertEquals(1f, p.ydsWidth); assertEquals(0f, p.ydsAlpha)
        // boxW: rows + YDS + clock.
        val f = StackedNflFrame().also { it.ydsWidth = 1f }
        assertEquals(396f, f.boxW)
        f.clockWidth = 0f
        assertEquals(306f, f.boxW)
        val g = StackedNflFrame().also { it.copyFrom(f) }
        assertEquals(306f, g.boxW)
    }

    @Test
    fun theTextUsesARealMinusAndAsciiDigits() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("142", NflYards.text(142))
            assertEquals("0", NflYards.text(0))
            assertEquals("−2", NflYards.text(-2))
            assertEquals("−99", NflYards.text(-99))
            assertEquals("999", NflYards.text(999))
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test
    fun aViewWithoutYardsNeverOpensTheColumnAndTheExtrasDefaultOff() {
        // True after WP-1 / WP-2 too: the column needs both teams' yards on the view (§2).
        assertFalse(StackedNflTargets.of(SportsFixtures.nflStackedView()).ydsOpen)
        assertEquals(FollowExtras(), FollowExtras.NONE)
        assertFalse(FollowExtras.NONE.nflYards)
    }
}
