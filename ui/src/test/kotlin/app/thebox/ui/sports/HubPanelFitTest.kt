package app.thebox.ui.sports

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub §1.3, §1.4, §1.5, §10.2) The panel's height budget at the four Text sizes for every phase, the headline's names
 * giving way before a score does, and the three buttons fitting 664 px in every phase (so no slot ever moves into a sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubPanelFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val factors = listOf(0.9f, 1.0f, 1.1f, 1.2f)
    private val poster = HubPanelPlan.POSTER_FLOOR
    private val art = HubPanelPlan.ART_FLOOR

    private fun measure(f: Float): Measure = { text, px, _ -> Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semi; textSize = px * f }.measureText(text) }

    private fun plan(f: Float, rows: Int, extra: Int = 0, summary: Int, channel: Boolean = true, note: Boolean = false, floor: Int) =
        HubPanelPlan.plan(f, rows, extra, summary, channel, note, floor, hasRows = rows > 0)

    @Test fun theDefaultSizeMatchesTheSpecsNumbers() {
        assertEquals("live and final NHL", 264, plan(1.0f, 5, summary = 2, floor = poster).mediaH)
        assertEquals("pre-game NHL", 226, plan(1.0f, 6, summary = 2, floor = art).mediaH)
    }

    @Test fun everyPhaseKeepsItsMediaFloorAtEverySize() {
        for (f in factors) {
            val nhlLive = plan(f, 5, summary = 2, floor = poster)
            assertTrue("NHL live / final at $f: $nhlLive", nhlLive.ok)
            val nhlPre = plan(f, 6, summary = 2, floor = art)
            assertTrue("NHL pre-game at $f: $nhlPre", nhlPre.ok)
            // NFL: the quarters and the total, then the optional rows only while the floor holds; the CHL has fewer rows.
            val nflLive = plan(f, 5, extra = 3, summary = 2, floor = art)
            assertTrue("NFL live at $f: $nflLive", nflLive.ok)
            assertTrue(nflLive.extraRows in 0..3)
            assertTrue("NFL pre-game at $f", plan(f, 1, summary = 0, floor = art).ok)
            assertTrue("CHL live at $f", plan(f, 2, summary = 0, floor = art).ok)
            assertTrue("a failed detail's text in place of the rows at $f", plan(f, HubPanelPlan.ERROR_ROWS, summary = 0, floor = art).ok)
            assertTrue("nothing to show at $f", plan(f, 0, summary = 0, channel = false, floor = art).ok)
        }
    }

    // Owner change 2026-11-25: the panel is 864 high (was 812), so the 52 extra px keep summary line 2 even at Largest (§1.4's
    // drop order still applies when the floors bite: the line goes before the media goes under its floor).
    @Test fun theSummaryKeepsBothLinesAtTheLargestSizeInTheTallerPanel() {
        val live = plan(1.2f, 5, summary = 2, floor = poster)
        assertEquals("both lines fit at Largest in 864", 2, live.summaryLines)
        assertTrue("media ${live.mediaH}", live.mediaH >= poster)
        val pre = plan(1.2f, 6, summary = 2, floor = art)
        assertEquals(2, pre.summaryLines)
        assertEquals("the default keeps both lines", 2, plan(1.0f, 5, summary = 2, floor = poster).summaryLines)
        assertEquals("Smaller does not shrink the rows", 264, plan(0.9f, 5, summary = 2, floor = poster).mediaH)
        // The drop order itself: with more rows than the floor allows, the line drops before the media gives way.
        val tight = plan(1.2f, 7, summary = 2, floor = poster)
        assertTrue("tight $tight", tight.summaryLines < 2 && tight.mediaH >= poster)
    }

    @Test fun theHeadlineNamesGiveWayAndNoScoreIsEverCut() {
        val longest = listOf(
            "Golden Knights" to "Blue Jackets", "Maple Leafs" to "Golden Knights", "Blue Jackets" to "Maple Leafs",
        )
        for (f in factors) {
            val m = measure(f)
            val col = HubPanelPlan.INNER_W.toFloat()
            for ((a, h) in longest) {
                val away = HubNames.of(League.NHL, SportsFixtures.team(if (a == "Golden Knights") "VGK" else if (a == "Maple Leafs") "TOR" else "CBJ", a))
                val home = HubNames.of(League.NHL, SportsFixtures.team(if (h == "Golden Knights") "VGK" else if (h == "Maple Leafs") "TOR" else "CBJ", h))
                for ((sa, sh) in listOf(10 to 12, 0 to 0, null to null)) {
                    val p = HubFit.headline(away, home, sa, sh, col, m)
                    assertFalse("$a vs $h at $f must not ellipsize: $p", p.ellipsis)
                    val fixed = HubFit.headlineFixedPx(sa, sh, p.designPx, m)
                    val names = (if (p.nicknames) listOf(away.nickname, home.nickname) else listOf(away.full, home.full)).sumOf { m(it, p.designPx, 600).toDouble() }
                    assertTrue("scores and names fit 664: ${fixed + names}", fixed + names <= col)
                }
            }
            // The longest NFL and CHL names: nicknames at worst, the scores intact.
            val tb = HubNames.of(League.NFL, SportsFixtures.nflTeam("TB", "Tampa Bay Buccaneers", "Buccaneers"))
            val wsh = HubNames.of(League.NFL, SportsFixtures.nflTeam("WSH", "Washington Commanders", "Commanders"))
            val p = HubFit.headline(tb, wsh, 31, 28, col, m)
            assertFalse("NFL at $f: $p", p.ellipsis)
            val sag = HubNames.of(League.QMJHL, SportsFixtures.chlTeam("CHI", "Saguenéens", 0))
            val thb = HubNames.of(League.WHL, SportsFixtures.chlTeam("SEA", "Thunderbirds", 0))
            assertFalse(HubFit.headline(sag, thb, 4, 12, col, m).ellipsis)
            // Full names win while they fit: "Vegas Golden Knights vs. Edmonton Oilers" at the default size.
        }
        val vgk = HubNames.of(League.NHL, SportsFixtures.team("VGK", "Golden Knights"))
        val edm = HubNames.of(League.NHL, SportsFixtures.team("EDM", "Oilers"))
        val p = HubFit.headline(vgk, edm, null, null, 664f, measure(1.0f))
        assertEquals(32, p.designPx); assertFalse(p.nicknames)
    }

    @Test fun threeButtonsFitInEveryPhaseAtEverySize() {
        val live = listOf(res.getString(R.string.sports_hub_slot_following), res.getString(R.string.sports_hub_slot_channels_n, 3), res.getString(R.string.sports_hub_slot_goals_n, 6))
        val finalNhl = listOf(res.getString(R.string.sports_hub_slot_watch_recap), res.getString(R.string.sports_hub_slot_goals_n, 6), res.getString(R.string.sports_hub_slot_box_score))
        val pre = listOf(res.getString(R.string.sports_hub_slot_following), res.getString(R.string.sports_hub_slot_channels_n, 3), res.getString(R.string.sports_hub_slot_matchup))
        for (f in factors) {
            val m = measure(f)
            val large = f > 1.05f
            val pad = if (large) 20 else 28
            val gap = if (large) 12 else 16
            for ((name, labels) in listOf("live" to live, "final" to finalNhl, "pre" to pre)) {
                // Slot 1 carries a 24 px glyph and its 8 px gap (the widest case: "▶ Watch recap").
                val widths = labels.mapIndexed { i, l -> m(l, 24, 600) + 2 * pad + if (i == 0) m("▶", 24, 600) + 8 else 0f }
                val total = widths.sum() + 2 * gap
                assertTrue("$name at $f: $total px of ${HubPanelPlan.INNER_W}", total <= HubPanelPlan.INNER_W)
            }
        }
    }
}
