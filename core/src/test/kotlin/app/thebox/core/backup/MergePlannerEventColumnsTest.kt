package app.thebox.core.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/design/m6-event-channels.md §4.5: the v6 user columns travel in a backup; the derived ones stay home. */
class MergePlannerEventColumnsTest {
    @Test
    fun theSwitchAndTheAutoFlagTravelTheDerivedColumnsDoNot() {
        assertTrue("hide_empty_events" in MergePlanner.PLAYLIST_COLUMNS)
        assertTrue("refresh_interval_auto" in MergePlanner.PLAYLIST_COLUMNS)
        for (c in listOf("event_zone", "event_zone_n", "event_count")) assertFalse(c in MergePlanner.PLAYLIST_COLUMNS, c)
    }
}
