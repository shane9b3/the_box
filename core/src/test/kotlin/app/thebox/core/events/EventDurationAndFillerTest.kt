package app.thebox.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/design/m6-event-channels.md §3.5 (`EventDuration`) and §6.1 / §2.3 (`EpgFiller`). */
class EventDurationAndFillerTest {
    @Test
    fun footballMatchups() {
        for (t in listOf(
            "NFL: NFL Football: 2026 Week 11 Cincinnati Bengals vs. Washington Commanders",
            "NFL Game Pass | Bengals @ Commanders",
            "College Football: Temple vs. Memphis",
            "NCAAF: Kent State at Eastern Michigan",
            "CFL: Argonauts vs. Alouettes",
        )) assertEquals(EventConstants.EVENT_MIN_FOOTBALL, EventDuration.minutes(t), t)
    }

    @Test
    fun soccerMatchups() {
        for (t in listOf(
            "UEFA Champions League: Bodø/Glimt vs. LASK",
            "Wolves v Derby County",
            "Galatasaray v Aston Villa UEFA Champions League Matchday 5 2026/2027",
            "Premier League: Arsenal vs. Spurs",
            "Nashville SC vs. FC Cincinnati (English)",
            "MLS: Austin vs. Dallas",
            "La Liga: Racing Santander vs. Real Sociedad",
            "Ligue 1: PSG vs. Lyon",
            "Serie A: Inter vs. Milan",
            "Bundesliga: Werder Bremen vs. Hamburg",
            "EFL: Cardiff vs. Preston",
            "EPL: Spurs vs. Fulham",
        )) assertEquals(EventConstants.EVENT_MIN_SOCCER, EventDuration.minutes(t), t)
    }

    @Test
    fun otherMatchupsAndShows() {
        assertEquals(180, EventDuration.minutes("Golden Knights vs. Oilers"))
        assertEquals(180, EventDuration.minutes("NHL on TNT: Golden Knights at Oilers"))
        // A block of several games is still a matchup.
        assertEquals(180, EventDuration.minutes("NCAA Men's Basketball: CLEM vs. WASH • SAM vs. GT"))
        // "football" alone is not an NFL word; " v " makes it soccer.
        assertEquals(135, EventDuration.minutes("Football Sky Bet Championship Wolves v Derby County"))
        assertEquals(60, EventDuration.minutes("Sportsnet Central: Nov. 23"))
        assertEquals(60, EventDuration.minutes("[4K] F1 LIVE"))
        assertEquals(60, EventDuration.minutes("NFL Live"))
        assertEquals(60, EventDuration.minutes(""))
    }

    @Test
    fun placeholdersMeasuredInTheOwnersGuide() {
        for (t in listOf(
            "No Event Today", "NO EVENT TODAY", "no event today", "Signing Off", "SIGN OFF", "Sign Off", "Off Air", "Offline",
            "No Event", "No Events", "No Events Today", "No events today.", "  No   Event  Today ",
            "Next Event: NHL on TNT: Golden Knights at Oilers at 07:30PM EST on Nov 24",
            "Next Event: Devils at Blue Jackets at 07:00PM EST on Nov 25",
            "NEXT EVENT: Something",
            "Upcoming - MLB Network Strike Zone",
            "Upcoming: Bruins at Leafs",
            "Upcoming",
        )) assertTrue(EpgFiller.isFiller(t), t)
    }

    @Test
    fun realListingsAreNotPlaceholders() {
        for (t in listOf(
            "Signing Day Special", "Next Gen Stats", "No Events Barred", "Next Event", "Offline TV", "Upcomingly",
            "NHL Hockey: Golden Knights at Oilers", "Devils at Blue Jackets", "The Next Event: A Documentary", "", " ",
            "Sign Off Special", "Off Air Live",
        )) assertFalse(EpgFiller.isFiller(t), t)
    }
}
