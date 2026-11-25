package app.thebox.data.guide

import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.ChannelNamePrefixImpl
import app.thebox.core.text.NamePrefixRule
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-event-channels.md §5.3, §11.1: slot short names through `display_name`. The backfill re-derives every
 * row after a `RULE_VERSION` bump (only it raises the version, under ImportMutex, paged), a custom name still wins, and
 * the prefix preview skips slot names (re-review E7).
 *
 * Most cases use [slotOf], a stand-in for `EventNameParser.slotOf`. [theProductionSeamsGiveTheRealSlots] builds
 * `DisplayNames` and `ChannelNamePrefixImpl` with their defaults over the §11.1 rows 3 and 32.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventDisplayNamesTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph

    /** Test stand-in: "<slot>: … @ …", "<slot>: NO EVENT" and a bare "<slot>:" carry a slot. */
    private val slotOf: (String) -> String? = { n ->
        val colon = n.indexOf(':')
        val rest = if (colon < 0) "" else n.substring(colon + 1).trim()
        if (colon > 0 && (rest.isEmpty() || rest == "NO EVENT" || " @ " in rest)) n.substring(0, colon).trim() else null
    }
    private val prefix = ChannelNamePrefixImpl(slotOf)
    private lateinit var names: DisplayNames

    @Before fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
        names = DisplayNames(db, db.playlistDao(), prefix, g.importMutex, Dispatchers.IO, slotOf)
    }
    @After fun tearDown() { db.close() }

    private suspend fun seed(p: Long) {
        val sports = db.newGroup(p, "g:sports", "SPORTS")
        db.newChannel(p, "ev", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", sortIndex = 0)
        db.newChannel(p, "no", "Sportsnet+ 11: NO EVENT", sortIndex = 1)
        db.newChannel(p, "bare", "NHL 02:", sortIndex = 2)
        db.newChannel(p, "uk", "UK: One", sortIndex = 3)
        db.newChannel(p, "plain", "Edmonton Oilers", sortIndex = 4)
        db.newChannel(p, "grp", "SPORTS: ESPN", groupId = sports, sortIndex = 5)
    }

    @Test
    fun theBackfillAfterTheRuleVersionBumpWritesSlotShortNames() = runBlocking {
        val p = db.newPlaylist()
        seed(p)
        // A device that ran the pre-event rule: version 1, display names from the plain prefix rule only.
        db.exec("UPDATE playlist SET name_prefix_ver = 1 WHERE id = ?", p)
        db.exec("UPDATE channel SET display_name = 'One' WHERE stable_key = 'uk'")
        db.exec("UPDATE channel SET display_name = 'ESPN' WHERE stable_key = 'grp'")
        assertTrue(ChannelNamePrefix.RULE_VERSION > 1)
        val passes = names.backfill()
        assertEquals(listOf(p), passes.map { it.playlistId })
        assertEquals(3, passes.single().changed) // only the three slot rows were written
        assertEquals(
            listOf("Sportsnet+ 09", "Sportsnet+ 11", "NHL 02", "One", "null", "ESPN"),
            db.strings("SELECT display_name FROM channel ORDER BY sort_index"),
        )
        assertEquals(ChannelNamePrefix.RULE_VERSION.toLong(), db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", p))
        assertTrue("up to date: no second pass", names.backfill().isEmpty())
        // The raw name is untouched: search (channel_fts) still finds "Vegas".
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM channel_fts WHERE channel_fts MATCH 'vegas'"))
    }

    @Test
    fun theSlotShowsUnderEveryRuleAndACustomNameStillWins() = runBlocking {
        val p = db.newPlaylist()
        seed(p)
        names.setRule(p, NamePrefixRule.Off)
        assertEquals(
            "Off still shows slots",
            listOf("Sportsnet+ 09", "Sportsnet+ 11", "NHL 02", "null", "null", "null"),
            db.strings("SELECT display_name FROM channel ORDER BY sort_index"),
        )
        names.setRule(p, NamePrefixRule.Custom(literals = listOf("Sportsnet+")))
        assertEquals("Sportsnet+ 09", db.strings("SELECT display_name FROM channel WHERE stable_key = 'ev'").single())
        // A custom name wins wherever a channel name is drawn (COALESCE(custom_name, display_name, name)).
        db.channelPrefsDao().upsertCustomName(p, "ev", "Oilers game", 1)
        val gg = GuideGraph(g)
        val id = db.scalar("SELECT id FROM channel WHERE stable_key = 'ev'")
        val no = db.scalar("SELECT id FROM channel WHERE stable_key = 'no'")
        val rows = gg.queries.channels(longArrayOf(id, no), 0, 2)
        assertEquals(listOf("Oilers game", "Sportsnet+ 11"), rows.map { it!!.displayName })
    }

    @Test
    fun theProductionSeamsGiveTheRealSlots() = runBlocking {
        // Integration: DisplayNames and ChannelNamePrefixImpl with their production defaults (the real slotOf), §11.1
        // rows 3 and 32.
        val p = db.newPlaylist()
        db.newChannel(p, "sn09", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", sortIndex = 0)
        db.newChannel(p, "tsn", "CA (TSN+ 008) | NCAA Men`s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)", sortIndex = 1)
        db.newChannel(p, "plain", "Edmonton Oilers", sortIndex = 2)
        db.exec("UPDATE playlist SET name_prefix_ver = 1 WHERE id = ?", p)
        val real = DisplayNames(db, db.playlistDao(), ChannelNamePrefixImpl(), g.importMutex, Dispatchers.IO)
        real.backfill()
        assertEquals(listOf("Sportsnet+ 09", "CA (TSN+ 008)", "null"), db.strings("SELECT display_name FROM channel ORDER BY sort_index"))
    }

    @Test
    fun thePrefixPreviewSkipsSlotNames() = runBlocking {
        val p = db.newPlaylist()
        seed(p)
        assertEquals("UK: One and SPORTS: ESPN only", 2, names.previewCount(p, NamePrefixRule.Auto))
        assertEquals(0, names.previewCount(p, NamePrefixRule.Off))
        assertEquals(0, names.previewCount(p, NamePrefixRule.Custom(literals = listOf("Sportsnet+"))))
        val all = sequenceOf(
            "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET" to null, "NHL 02:" to null, "UK: One" to null, "Plain" to null,
        )
        assertEquals(1, prefix.countChanged(all, NamePrefixRule.Auto))
        assertEquals(0, prefix.countChanged(all, NamePrefixRule.Off))
    }
}
