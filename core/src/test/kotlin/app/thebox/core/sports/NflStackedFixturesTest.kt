package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** (NFL Stacked §3.1, commit 0) The shared fixture helper reads all eight 2026-11-15 bodies as live NFL snapshots. */
class NflStackedFixturesTest {
    @Test
    fun everyFixtureMapsToALiveNflGame() {
        for (name in NflStackedFixtures.ALL) {
            val s = assertNotNull(NflStackedFixtures.snapshot(name), name)
            assertEquals(NflStackedFixtures.idOf(name), s.id, name)
            assertEquals(GamePhase.LIVE, s.phase, name)
            assertNotNull(s.detail as? LeagueDetail.Nfl, name)
        }
    }

    @Test
    fun theFixturesNameTheirGames() {
        val ari = assertNotNull(NflStackedFixtures.snapshot(NflStackedFixtures.PENALTY_TYPE8))
        assertEquals("LAR" to "ARI", ari.away.abbrev to ari.home.abbrev)
        val lv = assertNotNull(NflStackedFixtures.snapshot(NflStackedFixtures.KICKOFF_RETURN_NO_POSSESSION))
        assertEquals("SEA" to "LV", lv.away.abbrev to lv.home.abbrev)
    }
}
