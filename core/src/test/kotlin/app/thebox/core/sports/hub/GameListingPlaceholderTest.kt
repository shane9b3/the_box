package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * (Event channels, docs/design/m6-event-channels.md §8.1, §2.3) A provider placeholder listing is never guide evidence:
 * "Next Event: …" blocks name both teams, so without this rule stage B would confirm a channel on a 4 h placeholder.
 */
class GameListingPlaceholderTest {
    private val vgkEdm = GameSnapshot(
        GameId(League.NHL, "2026020385"), "", 0L, GamePhase.SCHEDULED,
        TeamLine("VGK", "Golden Knights", null, null), TeamLine("EDM", "Oilers", null, null), null, null, null, null, null,
    )
    private val teams = GameListing.teamsOf(vgkEdm)

    @Test fun placeholdersAreNeverEvidence() {
        for (t in listOf(
            "Next Event: NHL on TNT: Golden Knights at Oilers at 07:30PM EST on Nov 24",
            "No Event Today", "Signing Off", "Upcoming - NHL Hockey: Golden Knights at Oilers", "NO EVENT", "Offline",
        )) {
            assertTrue(GameListing.isPlaceholder(t), t)
            assertEquals(GameListing.Kind.NONE, GameListing.classify(t, null, teams), t)
            assertEquals(GameListing.Kind.NONE, GameListing.classify(t, "Golden Knights at Oilers", teams), "$t with a subtitle")
        }
    }

    @Test fun realListingsAreUnchanged() {
        assertFalse(GameListing.isPlaceholder("NHL Hockey: Golden Knights at Oilers"))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("NHL Hockey: Golden Knights at Oilers", null, teams))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("Next Gen Stats: Oilers hockey", null, teams))
        assertEquals(GameListing.Kind.SPORT, GameListing.classify("NHL Hockey", null, teams))
        assertEquals(GameListing.Kind.OTHER_GAME, GameListing.classify("NHL Hockey: Flames at Canucks", null, teams))
    }
}
