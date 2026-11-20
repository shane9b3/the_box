package app.thebox.core.xmltv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveMarkerTest {
    @Test
    fun markersAreTakenOut() {
        val cases = mapOf(
            "NHL Hockey ᴸᶦᵛᵉ" to "NHL Hockey",
            "NHL Hockey ᴸᴵⱽᴱ" to "NHL Hockey",
            "NHL Hockey ˡⁱᵛᵉ" to "NHL Hockey",
            "ᴸᶦᵛᵉ NHL Hockey" to "NHL Hockey",
            "NHL Hockey ᴸᶦᵛᵉ Leafs at Habs" to "NHL Hockey Leafs at Habs",
            "NHL Hockey (Live)" to "NHL Hockey",
            "NHL Hockey [LIVE]" to "NHL Hockey",
            "NHL Hockey *live*" to "NHL Hockey",
            "(Live) NHL Hockey" to "NHL Hockey",
            "[LIVE] - NHL Hockey" to "NHL Hockey",
        )
        for ((raw, clean) in cases) assertEquals(clean, LiveMarker.strip(raw), raw)
    }

    @Test
    fun realTitlesStayWhole() {
        for (t in listOf(
            "Saturday Night Live", "Live with Kelly and Mark", "Live: PGA Tour", "LIVE - Premier League", "NHL Hockey - Live",
            "NHL Hockey LIVE", "Live", "(Live)", "ᴸᶦᵛᵉ", "Live at the Apollo", "Alive (2020)", "The (Lively) Show", "",
        )) assertNull(LiveMarker.strip(t), t)
    }
}
