package app.thebox.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.data.editor.GroupLineupPlanProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/design/m6-channel-editor.md §3.6 "Plan assertions" (review C17b): `EXPLAIN QUERY PLAN` of the group lineup's
 * `UNION ALL` on the production (bundled) driver, 20,000 channels in 600 groups. The Robolectric data tests check
 * results only (they run `AndroidSQLiteDriver`).
 */
@RunWith(AndroidJUnit4::class)
class GroupLineupPlanTest {
    @Test
    fun bothHalvesDriveFromTheirIndexes() {
        val plans = runBlocking { GroupLineupPlanProbe.run(InstrumentationRegistry.getInstrumentation().targetContext) }
        val h1 = plans.half1.joinToString(" | ")
        val h2 = plans.half2.joinToString(" | ")
        android.util.Log.i("GroupLineupPlan", "half 1: $h1\nhalf 2: $h2\nwhole: ${plans.whole.joinToString(" | ")}")
        assertTrue(h1, h1.contains("SEARCH g0 USING COVERING INDEX index_channel_group_identity (playlist_id=? AND kind=? AND group_key=?)") ||
            h1.contains("SEARCH g0 USING INDEX index_channel_group_identity (playlist_id=? AND kind=? AND group_key=?)"))
        assertTrue(h1, h1.contains("SEARCH c USING INDEX index_channel_group (group_id=?)") ||
            Regex("""SEARCH c USING INDEX index_channel_browse \([^)]*group_id=\?""").containsMatchIn(h1))
        assertTrue(h2, h2.contains("SEARCH cpm USING INDEX index_channel_prefs_group (playlist_id=? AND custom_group_key=?)") ||
            h2.contains("SEARCH cpm USING COVERING INDEX index_channel_prefs_group (playlist_id=? AND custom_group_key=?)"))
        for ((label, plan) in listOf("half 1" to h1, "half 2" to h2, "whole" to plans.whole.joinToString(" | "))) {
            for (bad in listOf("SCAN c ", "SCAN channel", "SCAN cpm")) assertTrue("$label: $bad in $plan", !"$plan ".contains(bad))
        }
    }
}
