package app.thebox.core.xmltv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewMarkerTest {
    @Test
    fun markersAreTakenOut() {
        val cases = mapOf(
            "Chicago Fire (New)" to "Chicago Fire",
            "Chicago Fire [NEW]" to "Chicago Fire",
            "Chicago Fire *new*" to "Chicago Fire",
            "(New) Chicago Fire" to "Chicago Fire",
            "[NEW] - Chicago Fire" to "Chicago Fire",
            "New: Chicago Fire" to "Chicago Fire",
            "NEW - Chicago Fire" to "Chicago Fire",
            "Chicago Fire - New" to "Chicago Fire",
            "Chicago Fire | NEW" to "Chicago Fire",
            "Chicago Fire ᴺᵉʷ" to "Chicago Fire",
            "Chicago Fire ᴺᵉʷ S12 E4" to "Chicago Fire S12 E4",
            "Chicago Fire NEW" to "Chicago Fire",
        )
        for ((raw, clean) in cases) assertEquals(clean, NewMarker.strip(raw), raw)
    }

    @Test
    fun realTitlesStayWhole() {
        for (t in listOf(
            "New Amsterdam", "Brand New Day", "What's New Scooby-Doo?", "The New Adventures of Old Christine", "Something New",
            "Good Morning New York", "News at Ten", "BREAKING NEWS NEW", "Newsnight", "New", "(New)", "Chicago Fire", "",
            "New-Gen", "Renew", "New Year's Eve: Live",
        )) assertNull(NewMarker.strip(t), t)
    }
}
