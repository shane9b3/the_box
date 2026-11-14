package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals

/** (CHL WP-2, docs/design/m6-chl-leagues.md §1.6 #8, #9) The CHL's 429 floor and wire estimate. */
class PollMeterChlTest {
    @Test
    fun aSlowedChlLeagueIsFlooredLikeTheNfl() {
        for (league in CHL_LEAGUES) {
            val slowed = PollMeasure(slowed = true)
            assertEquals(SportsConstants.AUTO_429_FLOOR_S, PollMeter.effectiveS(5, league, slowed), "$league 5 s")
            assertEquals(15, PollMeter.effectiveS(15, league, slowed), "$league 15 s stays")
            assertEquals(5, PollMeter.effectiveS(5, league, PollMeasure()), "$league not slowed")
        }
        assertEquals(20, PollMeter.effectiveS(20, League.NHL, PollMeasure(slowed = true)), "the NHL has no floor")
    }

    @Test
    fun anUnmeasuredChlLeagueUsesTheChlWireEstimate() {
        for (league in CHL_LEAGUES) assertEquals(SportsConstants.EST_WIRE_BYTES_CHL, PollMeter.bytesPerPoll(null, league))
        assertEquals(SportsConstants.EST_WIRE_BYTES_NHL, PollMeter.bytesPerPoll(null, League.NHL))
        assertEquals(SportsConstants.EST_WIRE_BYTES_NFL, PollMeter.bytesPerPoll(null, League.NFL))
    }
}
