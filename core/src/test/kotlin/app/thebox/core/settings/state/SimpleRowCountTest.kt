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
     * pages (ButtonMapping, ActionBindings) are sub-pages of Remote control, so their registered rows are not counted;
     * nor are the rows of Playback's "Pause and rewind" sub-page.
     */
    private val SUB_PAGE_SOURCES = listOf(
        ChannelOptionsRows, app.thebox.core.settings.tree.ButtonMappingRows,
        // (Storage locations commit 0, docs/design/m6-storage-locations.md §9.1, the M5 §3.3.13 amendment) "Pause and
        // rewind" is one simple Link on Playback; its sub-page's rows are not counted.
        app.thebox.core.settings.tree.PauseRewindRows,
    )

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

    /**
     * C-S9 (M5 code review 2026-10-16): every section's §3.3.13 split in the registry, and the SIMPLE rows each built page
     * **publishes** with "Show advanced settings" off (through the machine, which drops ADVANCED rows, C-S5). WP-E's About
     * page is built in `:ui` (DiagPages); its share is the registry's.
     */
    @Test
    fun everySectionsSplitHoldsAndThePagesPublishIt() {
        for ((s, n) in expected) assertEquals(n, simple(s), "registry split of $s")
        val real = RealPages(playlists = 2)
        val wpa = WpaPages(app.thebox.core.settings.fakes.FakeSettingsStore(), SettingsTexts.KEYS, FakePageEnv())
        fun published(route: app.thebox.core.settings.SettingsRoute): Int {
            val m = SettingsMachineImpl(QueueEffects(), { 0L })
            m.open(route, fromFull = false)
            val page = if (wpa.handles(route)) wpa.build(route, advanced = false) else requireNotNull(real.build(route, advanced = false)) { "no builder for $route" }
            m.dispatch(app.thebox.core.settings.SettingsEvent.Page(page))
            // C-S5: with advanced off no page publishes an ADVANCED row (WP-B's Playlist page builds them all).
            val advancedShown = m.ui.value.page!!.rows.filter { it.visibility == Visibility.ADVANCED }.map { it.id.key }
            assertTrue(advancedShown.isEmpty(), "$route publishes advanced rows in the simple view: $advancedShown")
            return m.ui.value.page!!.rows.count { r ->
                r.visibility == Visibility.SIMPLE && r.id !in NOT_COUNTED && SettingsTree.spec(r.id)?.let { spec -> SettingsTree.sources.none { src -> src in SUB_PAGE_SOURCES && spec in src.rows } } == true
            }
        }
        val counts = LinkedHashMap<SectionId, Int>()
        counts[SectionId.PLAYLISTS] = published(app.thebox.core.settings.SettingsRoute.Playlist(1)) // the playlist page, counted once
        for (s in WpaPages.SECTIONS) counts[s] = published(app.thebox.core.settings.SettingsRoute.Section(s))
        for (s in listOf(SectionId.CHANNELS, SectionId.PARENTAL, SectionId.BACKUP)) counts[s] = published(app.thebox.core.settings.SettingsRoute.Section(s))
        counts[SectionId.ABOUT] = simple(SectionId.ABOUT)
        for ((s, n) in counts) assertEquals(expected[s], n, "SIMPLE rows the $s page publishes with advanced off")
        assertEquals(35, counts.values.sum() + (expected.keys - counts.keys).sumOf { expected.getValue(it) })
    }
}
