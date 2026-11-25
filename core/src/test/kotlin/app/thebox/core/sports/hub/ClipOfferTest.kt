package app.thebox.core.sports.hub

import app.thebox.core.sports.SportsConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-nfl-hub.md §4.3: the embargo, the 5 min margin, and the instant the answer changes. */
class ClipOfferTest {
    private val margin = SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS
    private val expires = 10_000_000L

    @Test fun offeredBetweenTheEmbargoAndTheMargin() {
        assertTrue(ClipOffer.offered(null, expires, 0L))
        assertTrue(ClipOffer.offered(1_000L, expires, 1_000L), "the embargo instant itself is offered")
        assertFalse(ClipOffer.offered(1_000L, expires, 999L), "embargoed")
        assertTrue(ClipOffer.offered(null, expires, expires - margin - 1))
        assertFalse(ClipOffer.offered(null, expires, expires - margin), "inside the margin")
        assertFalse(ClipOffer.offered(null, expires, expires + 1), "expired")
    }

    @Test fun changesAtTheEmbargoThenAtTheMargin() {
        assertEquals(1_000L, ClipOffer.changesAt(1_000L, expires, 0L), "embargoed: when it lifts")
        assertEquals(expires - margin, ClipOffer.changesAt(1_000L, expires, 1_000L), "offered: when the margin starts")
        assertEquals(expires - margin, ClipOffer.changesAt(null, expires, 0L))
        assertNull(ClipOffer.changesAt(null, expires, expires - margin), "past the margin: never again")
        assertNull(ClipOffer.changesAt(expires, expires, 0L), "an embargo past the margin: never offered, no change")
    }

    @Test fun changesAtIsWhereOfferedFlips() {
        val embargo = 2_000_000L
        for (now in listOf(0L, 1_999_999L, 2_000_000L, 5_000_000L, expires - margin - 1)) {
            val at = ClipOffer.changesAt(embargo, expires, now) ?: continue
            assertTrue(at > now)
            assertEquals(ClipOffer.offered(embargo, expires, now), ClipOffer.offered(embargo, expires, at - 1), "steady until $at")
            assertTrue(ClipOffer.offered(embargo, expires, now) != ClipOffer.offered(embargo, expires, at), "flips at $at")
        }
    }
}
