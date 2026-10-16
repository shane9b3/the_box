package app.thebox.core.settings.state

import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.SettingsTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simple rows across all sections, the playlist page counted once (docs/design/m5-setup-settings.md §3.2.3, §3.3.13):
 * ≤ 35, and exactly the §3.3.13 split per section. Sections whose rows another WP has not registered yet are reported,
 * not counted (the total is asserted as = 35 once every section is registered). Channel options are per channel and
 * not counted.
 */
class SimpleRowCountTest {
    private val expected = mapOf(
        SectionId.PLAYLISTS to 7, SectionId.GUIDE to 3, SectionId.PLAYBACK to 5, SectionId.CHANNELS to 4, SectionId.INTERFACE to 3,
        SectionId.REMOTE to 2, SectionId.PARENTAL to 4, SectionId.BACKUP to 2, SectionId.ABOUT to 5, SectionId.NETWORK to 0,
    )

    /**
     * Section pages only (§3.3.13; WP-C's rule at integration): Channel options are per channel and the button-mapping
     * pages (ButtonMapping, ActionBindings) are sub-pages of Remote control, so their registered rows are not counted.
     */
    private val SUB_PAGE_SOURCES = listOf(ChannelOptionsRows, app.thebox.core.settings.tree.ButtonMappingRows)

    private fun simple(section: SectionId) =
        SettingsTree.rowsOf(section).count {
            it.visibility == Visibility.SIMPLE && it.id !in NOT_COUNTED && SettingsTree.sources.none { s -> s in SUB_PAGE_SOURCES && it in s.rows }
        }

    /** §3.3.5: the playlist chooser is a context control (WP-D's `ChannelsRows.NOT_COUNTED`). */
    private val NOT_COUNTED = app.thebox.core.settings.tree.ChannelsRows.NOT_COUNTED

    @Test
    fun simpleRowsMatchTheTarget() {
        assertEquals(35, expected.values.sum())
        for (s in listOf(SectionId.GUIDE, SectionId.PLAYBACK, SectionId.INTERFACE, SectionId.REMOTE, SectionId.NETWORK)) {
            // guide.fix_unmatched is built on the TV guide page but registered by WP-B (MatchingRows).
            val pending = if (s == SectionId.GUIDE && SettingsTree.spec(app.thebox.core.settings.tree.GuideRows.FIX_UNMATCHED) == null) 1 else 0
            assertEquals(expected[s], simple(s) + pending, "WP-A section $s")
        }
        val registered = expected.keys.filter { s -> SettingsTree.rowsOf(s).any { it.owner != "A" } || s in WpaPages.SECTIONS }
        val total = registered.sumOf { simple(it) }
        assertTrue(total <= 35, "simple rows $total > 35")
        if (registered.size == expected.size) assertEquals(35, total)
        else println("SimpleRowCountTest: sections not registered yet: ${expected.keys - registered.toSet()} (total so far $total)")
    }
}
