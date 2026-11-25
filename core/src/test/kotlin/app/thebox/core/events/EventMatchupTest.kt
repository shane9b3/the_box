package app.thebox.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** docs/design/m6-event-channels.md §3.7. */
class EventMatchupTest {
    private fun m(first: String, second: String, ordered: Boolean) =
        Matchup(first.split(' ').filter { it.isNotEmpty() }, second.split(' ').filter { it.isNotEmpty() }, ordered)

    @Test
    fun separatorsAndOrder() {
        assertEquals(m("golden knights", "oilers", false), EventMatchup.of("Golden Knights vs. Oilers"))
        assertEquals(m("golden knights", "oilers", true), EventMatchup.of("Golden Knights at Oilers"))
        assertEquals(m("vegas", "edmonton", true), EventMatchup.of("Vegas @ Edmonton"))
        assertEquals(m("thunder", "jazz", true), EventMatchup.of("Thunder AT Jazz"))
        assertEquals(m("camrose", "drayton valley", false), EventMatchup.of("Camrose vs Drayton Valley"))
        assertEquals(m("wolves", "derby county", false), EventMatchup.of("Wolves v Derby County"))
        assertEquals(m("a", "b", false), EventMatchup.of("A v. B"))
        assertEquals(m("galatasaray", "aston villa", false), EventMatchup.of("Galatasaray - Aston Villa"))
        assertEquals(m("viking", "sabah", false), EventMatchup.of("Viking – Sabah"))
        assertEquals(m("viking", "sabah", false), EventMatchup.of("Viking — Sabah"))
    }

    @Test
    fun theSeparatorListIsAPriorityOrder() {
        // "vs." outranks " - " even though the dash comes first.
        assertEquals(m("penn state berks", "navy", false), EventMatchup.of("Penn State - Berks vs. Navy"))
        // "@" outranks "vs.".
        assertEquals(m("a vs b", "c", true), EventMatchup.of("A vs. B @ C"))
    }

    @Test
    fun lastSegmentWithASeparator() {
        assertEquals(m("golden knights", "oilers", true), EventMatchup.of("NHL on TNT: Golden Knights at Oilers"))
        assertEquals(m("kings", "canadiens", true), EventMatchup.of("NHL.TV | Kings @ Canadiens"))
        assertEquals(m("bengals", "commanders", true), EventMatchup.of("NFL Game Pass | Bengals @ Commanders"))
        assertEquals(m("samford", "auburn", false), EventMatchup.of("SEC Network: Samford vs. Auburn"))
        assertEquals(m("luca brecel", "luo zetao", false), EventMatchup.of("UK Championship | Qualifying: Luca Brecel - Luo Zetao"))
        // No segment has one, and neither has the whole title.
        assertNull(EventMatchup.of("Sportsnet Central: Nov. 23"))
        assertNull(EventMatchup.of("[4K] F1 LIVE"))
    }

    @Test
    fun severalMatchupsHaveNoSides() {
        assertNull(EventMatchup.of("NCAA Men's Basketball: CLEM vs. WASH • SAM vs. GT"))
        assertNull(EventMatchup.of("Bruins vs. Leafs / Jets vs. Wild"))
        // One " / " inside a single matchup is fine.
        assertEquals(m("bodø glimt", "lask", false), EventMatchup.of("UEFA Champions League: Bodø/Glimt vs. LASK"))
        assertEquals(m("a b", "c", false), EventMatchup.of("A / B vs. C"))
    }

    @Test
    fun sidesAreCleaned() {
        assertEquals(m("texas tech", "louisville", true), EventMatchup.of("9 TEXAS TECH @ 23 LOUISVILLE (M)"))
        assertEquals(m("texas tech", "louisville", false), EventMatchup.of("#5 Texas Tech vs. #25 Louisville (Quarterfinal #3)"))
        assertEquals(
            m("cincinnati bengals", "washington commanders", false),
            EventMatchup.of("NFL: NFL Football: 2026 Week 11 Cincinnati Bengals vs. Washington Commanders"),
        )
        assertEquals(m("bengals", "commanders", false), EventMatchup.of("Week 11 Bengals vs. Commanders"))
        assertEquals(m("arsenal", "dortmund", false), EventMatchup.of("Arsenal vs. Dortmund [AMZ]"))
        assertEquals(m("rangers", "devils", false), EventMatchup.of("The Rangers vs. The Devils"))
        assertEquals(m("oilers", "flames", true), EventMatchup.of("NHL Hockey Oilers @ NHL Flames"))
        assertEquals(m("montreal", "st louis", true), EventMatchup.of("Montréal @ St. Louis"))
        assertEquals(m("st louis blues", "la kings", false), EventMatchup.of("Saint Louis Blues vs. L.A. Kings"))
        assertEquals(m("san jose state", "arizona state", true), EventMatchup.of("SAN JOSÉ STATE @ ARIZONA STATE (W)"))
        assertEquals(m("st marys", "texas a and m corpus christi", true), EventMatchup.of("ST. MARY'S (TX) @ TEXAS A&M-CORPUS CHRISTI (W)"))
        // Other sports' words stay, so the side never equals a club of another league.
        assertEquals(m("basketball nba dallas mavericks", "denver nuggets", true), EventMatchup.of("Basketball NBA Dallas Mavericks @ Denver Nuggets"))
        // The 49ers keep their digits.
        assertEquals(m("49ers", "rams", false), EventMatchup.of("49ers vs. Rams"))
    }

    @Test
    fun emptySidesAreNoMatchup() {
        assertNull(EventMatchup.of(""))
        assertNull(EventMatchup.of(" @ "))
        assertNull(EventMatchup.of("A @ "))
        assertNull(EventMatchup.of("(TBD) vs. (TBD)"))
        assertNull(EventMatchup.of("NHL vs. Hockey"))
        assertNull(EventMatchup.of("7 @ 9"))
    }

    /** Code review 2026-11-25, UI open item 2: the hub sheet shows what the title says besides the matchup. */
    @Test
    fun theRestOfATitle() {
        assertEquals("Jets Broadcast", EventMatchup.rest("Winnipeg Jets vs. Boston Bruins (Jets Broadcast)"))
        assertEquals("NHL on TNT", EventMatchup.rest("NHL on TNT: Golden Knights at Oilers"))
        assertEquals("Hockey · Bruins Broadcast", EventMatchup.rest("Hockey: Jets vs. Bruins (Bruins Broadcast)"))
        assertEquals("English", EventMatchup.rest("Vegas @ Edmonton | English"))
        assertNull(EventMatchup.rest("Golden Knights vs. Oilers"))
        assertNull(EventMatchup.rest("Vegas @ Edmonton ()"))
        assertNull(EventMatchup.rest("NHL Tonight (Studio)"), "no single matchup")
    }
}
