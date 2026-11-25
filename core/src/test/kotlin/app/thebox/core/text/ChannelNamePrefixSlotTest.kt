package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-event-channels.md §5.3, §11.1 `ChannelNamePrefixImplTest`: the slot comes first under every rule, plain
 * names keep the prefix logic, and `countChanged` skips slot names (re-review E7).
 *
 * Independent of the real parser: [slotOf] stands in for `EventNameParser.slotOf`. **Integration (re-point):** with WP-1
 * merged, `ChannelNamePrefixImpl()` (the default) must give `Sportsnet+ 09`, `MLS 03`, `CA (TSN+ 008)` and `NHL 02` for
 * the §11.1 rows 3, 15, 32 and 46, and null for rows 55–57.
 */
class ChannelNamePrefixSlotTest {
    private val slots = mapOf(
        "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET" to "Sportsnet+ 09",
        "UK: Sky Sports+ 19: Football @ 24 Nov 08:00 PM London" to "UK: Sky Sports+ 19",
        "NHL 02:" to "NHL 02",
        "CA (TSN+ 008) | NCAA Men’s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)" to "CA (TSN+ 008)",
        "blank: x" to " ",
    )
    private val slotOf: (String) -> String? = { slots[it] }
    private val p = ChannelNamePrefixImpl(slotOf)
    private val rules = listOf(NamePrefixRule.Auto, NamePrefixRule.Off, NamePrefixRule.Custom(literals = listOf("UK:")), NamePrefixRule.Custom(regex = "^[A-Z]+: "))

    @Test
    fun theSlotComesFirstUnderEveryRule() {
        for (rule in rules) {
            assertEquals("Sportsnet+ 09", p.strip("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", rule), "$rule")
            assertEquals("UK: Sky Sports+ 19", p.strip("UK: Sky Sports+ 19: Football @ 24 Nov 08:00 PM London", "UK | Sky Sports+", rule), "$rule: no prefix strip on a slot")
            assertEquals("NHL 02", p.strip("NHL 02:", null, rule), "$rule")
            assertEquals("CA (TSN+ 008)", p.strip("CA (TSN+ 008) | NCAA Men’s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)", null, rule), "$rule")
        }
    }

    @Test
    fun plainNamesKeepThePrefixLogic() {
        assertEquals("One", p.strip("UK: One", null, NamePrefixRule.Auto))
        assertNull(p.strip("UK: One", null, NamePrefixRule.Off))
        assertNull(p.strip("Edmonton Oilers", null, NamePrefixRule.Auto))
        assertNull(p.strip("Sportsnet Oilers", null, NamePrefixRule.Off))
        assertNull(p.strip("blank: x", null, NamePrefixRule.Auto), "a blank slot is no slot: the prefix logic decides")
        assertFalse(p.isSlotName("blank: x"))
        assertTrue(p.isSlotName("NHL 02:"))
    }

    @Test
    fun aThrowingSlotSeamFallsBackToThePrefixLogic() {
        val bad = ChannelNamePrefixImpl { error("parser bug") }
        assertEquals("One", bad.strip("UK: One", null, NamePrefixRule.Auto))
        assertFalse(bad.isSlotName("UK: One"))
    }

    @Test
    fun countChangedSkipsSlotNames() {
        val names = sequenceOf<Pair<String, String?>>(
            "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET" to null,
            "NHL 02:" to null,
            "UK: One" to null,
            "Plain" to null,
        )
        assertEquals(1, p.countChanged(names, NamePrefixRule.Auto))
        assertEquals(0, p.countChanged(names, NamePrefixRule.Off))
    }

    @Test
    fun theDefaultSeamIsTheEventParser() {
        // Commit 0's stub answers null: every name keeps the old behaviour until WP-1 lands.
        assertEquals("One", ChannelNamePrefixImpl().strip("UK: One", null, NamePrefixRule.Auto))
        assertEquals(1 + app.thebox.core.events.EventNameParser.VERSION, ChannelNamePrefix.RULE_VERSION)
    }
}
