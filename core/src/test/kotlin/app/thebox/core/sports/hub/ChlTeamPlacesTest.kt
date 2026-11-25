package app.thebox.core.sports.hub

import app.thebox.core.sports.CHL_LEAGUES
import app.thebox.core.sports.ChlTeamColours
import app.thebox.core.sports.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** (Sports Hub §4.6, U-S15) 61 `(league, code)` entries, the same codes as the colour tables. */
class ChlTeamPlacesTest {
    @Test fun thereAre61EntriesWithTheColourTablesCodes() {
        assertEquals(listOf(20, 23, 18), CHL_LEAGUES.map { ChlTeamPlaces.TABLE.getValue(it).size })
        for (league in CHL_LEAGUES) assertEquals(ChlTeamColours.codes(league), ChlTeamPlaces.TABLE.getValue(league).keys, "$league")
        assertEquals(61, ChlTeamPlaces.TABLE.values.sumOf { it.size })
    }

    @Test fun aCodeAloneNeverPicksARow() {
        assertEquals("Calgary", ChlTeamPlaces.of(League.WHL, "cgy")?.place)
        assertNull(ChlTeamPlaces.of(League.NHL, "CGY"), "the NHL's CGY is not a CHL team")
        assertEquals("Victoria", ChlTeamPlaces.of(League.WHL, "VIC")?.place)
        assertEquals("Victoriaville", ChlTeamPlaces.of(League.QMJHL, "VIC")?.place)
        assertEquals("Newfoundland", ChlTeamPlaces.of(League.QMJHL, "NFL")?.place)
        assertNull(ChlTeamPlaces.of(League.OHL, null))
        assertNull(ChlTeamPlaces.of(League.OHL, "ZZZ"))
    }

    @Test fun everyEntryHasAPlaceANicknameAndACardPlace() {
        for ((league, rows) in ChlTeamPlaces.TABLE) for ((code, e) in rows) {
            assertTrue(e.place.isNotBlank() && e.nickname.isNotBlank() && e.cardPlace.isNotBlank(), "$league $code")
            assertNotNull(ChlTeamPlaces.of(league, code))
        }
        assertEquals("Boisbriand", ChlTeamPlaces.of(League.QMJHL, "BLB")?.cardPlace)
    }
}
