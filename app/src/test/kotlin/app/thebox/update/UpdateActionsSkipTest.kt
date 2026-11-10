package app.thebox.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review S7: Skip reads the version from the offer, so it skips on every card with buttons, not only "Available". */
class UpdateActionsSkipTest {
    @Test
    fun skipUsesTheOfferAndFallsBackToLater() {
        val calls = ArrayList<String>()
        skip(6L, { calls += "skip($it)" }, { calls += "later" })
        skip(null, { calls += "skip($it)" }, { calls += "later" })
        assertEquals(listOf("skip(6)", "later"), calls)
    }
}
