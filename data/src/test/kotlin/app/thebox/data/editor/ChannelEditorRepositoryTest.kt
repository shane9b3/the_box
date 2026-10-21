package app.thebox.data.editor

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.testutil.TestGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m6-channel-editor.md §6.2 `ChannelEditorRepositoryTest`: lists, filters, Find, group rows, access, lookups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelEditorRepositoryTest {
    private lateinit var w: EditorWorld
    private val p get() = w.p

    @Before
    fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        w.group("g:news", "News", sort = 1)
        w.group("g:sport", "Sport", sort = 0)
        w.group("g:adult", "Adult", sort = 2, adult = true)
        w.channel("n1", "g:news", 0); w.channel("n2", "g:news", 1); w.channel("n3", "g:news", 2)
        w.channel("s1", "g:sport", 3); w.channel("s2", "g:sport", 4)
        w.channel("a1", "g:adult", 5)
        w.channel("loose", null, 6)
        w.channel("gone", "g:news", 7, missing = 5L)
        w.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_sort_index) VALUES (?, 'n3', 0, 0, 1, 0)", p)
        // s2 moves into News: News is custom-ordered (n3 has 0), so s2 goes to max + 1.
        assertTrue(w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("s2")), "g:news")) is app.thebox.core.editor.WriteResult.Done)
    }

    @After fun tearDown() { w.close() }

    @Test
    fun idOrderEqualsTheGuideLineupForGroupAndAll() = runBlocking {
        val gq = GuideGraph(TestGraph(w.db)).queries
        for (source in listOf(GuideSource.Group(p, "g:news"), GuideSource.Group(p, "g:sport"), GuideSource.All(p))) {
            val list = when (source) {
                is GuideSource.Group -> EditorList.Group(source.groupKey)
                else -> EditorList.All
            }
            assertEquals(source.toString(), gq.lineup(source, ParentalAccess.OPEN).ids.toList(), w.repo.channels(p, list, EditorFilter.ALL, ParentalAccess.OPEN).ids.toList())
        }
        assertEquals(listOf("n3", "s2", "n1", "n2"), w.list(EditorList.Group("g:news")))
        // Q2: All in the user's group order (Sport, News, Adult), each in its own order, ungrouped last; missing left out.
        assertEquals(listOf("s1", "n3", "s2", "n1", "n2", "a1", "loose"), w.list(EditorList.All))
    }

    @Test
    fun filtersSplitHiddenByTheUserOrByARule() = runBlocking {
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("n1")), true))
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'n2'")
        val all = w.repo.channels(p, EditorList.Group("g:news"), EditorFilter.ALL, ParentalAccess.OPEN)
        assertEquals(4, all.ids.size)
        assertEquals(2, all.hidden)
        assertEquals(listOf("n3", "s2"), w.list(EditorList.Group("g:news"), EditorFilter.SHOWN))
        assertEquals(listOf("n1", "n2"), w.list(EditorList.Group("g:news"), EditorFilter.HIDDEN))
        assertEquals(2, w.repo.channels(p, EditorList.Group("g:news"), EditorFilter.HIDDEN, ParentalAccess.OPEN).hidden)
    }

    @Test
    fun findMatchesProviderAndCustomNames() = runBlocking {
        w.write(EditorWrite.RenameChannel(p, "n3", "Zebra Sport"))
        assertEquals(listOf("n3"), w.list(EditorList.Find("zebra")))
        assertEquals(listOf("n1"), w.list(EditorList.Find("N1")))
        assertEquals("FTS prefix on provider names plus the custom name, in All order", listOf("s1", "n3", "s2"), w.list(EditorList.Find("s")))
        assertTrue(w.list(EditorList.Find("   ")).isEmpty())
        assertTrue(w.list(EditorList.Find("100%_")).isEmpty())
    }

    @Test
    fun groupsAndGroupRowsCountTheEffectiveMembers() = runBlocking {
        assertEquals(listOf("g:sport", "g:news", "g:adult"), w.repo.groups(p, true, ParentalAccess.OPEN).keys)
        w.write(EditorWrite.HideGroups(p, app.thebox.core.editor.GroupTarget.Keys(setOf("g:sport")), true))
        w.db.exec("UPDATE channel_group SET rule_hidden = 1 WHERE group_key = 'g:adult'")
        val shown = w.repo.groups(p, false, ParentalAccess.OPEN)
        assertEquals(listOf("g:news"), shown.keys)
        assertEquals(2, shown.hidden)
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'n2'")
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("n1")), true))
        val rows = w.repo.groupRows(p, listOf("g:news", "g:sport", "g:adult"), ParentalAccess.OPEN).associateBy { it.groupKey }
        val news = rows.getValue("g:news")
        assertEquals(4, news.channels) // n3, s2 (moved in), n1, n2; "gone" is missing
        assertEquals(2, news.hiddenChannels) // n1 by the user, n2 by a rule
        assertTrue(news.customOrder)
        assertFalse(news.hidden)
        val sport = rows.getValue("g:sport")
        assertEquals(1, sport.channels) // s2 moved away
        assertTrue(sport.hidden)
        assertFalse(sport.customOrder)
        val adult = rows.getValue("g:adult")
        assertTrue(adult.adult && adult.locked && adult.ruleHidden && !adult.notProtected)
        assertTrue(w.repo.groupRows(p, listOf("g:adult"), ParentalAccess.FILTERED_NO_PIN).single().notProtected)
        assertFalse("manual-only: adult detection does not lock", w.repo.groupRows(p, listOf("g:adult"), ParentalAccess.LOCKED_MANUAL_ONLY).single().locked)
        w.write(EditorWrite.RenameGroup(p, "g:news", "Nyheter"))
        val renamed = w.repo.groupRows(p, listOf("g:news"), ParentalAccess.OPEN).single()
        assertEquals("Nyheter" to "News", renamed.name to renamed.providerName)
        assertTrue(renamed.renamed)
    }

    @Test
    fun lockedContentIsLeftOutWhileTheSessionIsLocked() = runBlocking {
        val adult = w.repo.channels(p, EditorList.Group("g:adult"), EditorFilter.ALL, ParentalAccess.LOCKED_AUTO)
        assertTrue(adult.listLocked)
        assertEquals(0, adult.ids.size)
        val all = w.repo.channels(p, EditorList.All, EditorFilter.ALL, ParentalAccess.LOCKED_AUTO)
        assertEquals(1, all.lockedOut)
        assertFalse("a1" in w.keysOf(all.ids))
        val found = w.repo.channels(p, EditorList.Find("A1"), EditorFilter.ALL, ParentalAccess.LOCKED_AUTO)
        assertEquals(0, found.ids.size)
        assertEquals(1, found.lockedOut)
        // No PIN: listed with the 18+ chip (U-S11), nothing locked out.
        val noPin = w.repo.channels(p, EditorList.Group("g:adult"), EditorFilter.ALL, ParentalAccess.FILTERED_NO_PIN)
        assertFalse(noPin.listLocked)
        assertEquals(listOf("a1"), w.keysOf(noPin.ids))
        // An explicit channel lock is locked out everywhere under a locked session, also in its own unlocked group.
        w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("n1")), true))
        val news = w.repo.channels(p, EditorList.Group("g:news"), EditorFilter.ALL, ParentalAccess.LOCKED_MANUAL_ONLY)
        assertEquals(listOf("n3", "s2", "n2"), w.keysOf(news.ids))
        assertEquals(1, news.lockedOut)
        val row = w.repo.channelRows(w.repo.channels(p, EditorList.Group("g:news"), EditorFilter.ALL, ParentalAccess.OPEN).ids, 0, 4)
        assertTrue(row.single { it!!.stableKey == "n1" }!!.locked)
        val a1 = w.repo.channelRows(noPin.ids, 0, 1).single()!!
        assertEquals("Adult", a1.lockedByGroup)
    }

    @Test
    fun indexOfLocateAndPlaylistExists() = runBlocking {
        val ids = w.repo.channels(p, EditorList.All, EditorFilter.ALL, ParentalAccess.OPEN).ids
        val keys = w.keysOf(ids)
        assertEquals(keys.indexOf("n1"), w.repo.indexOf(ids, p, "n1"))
        assertNull(w.repo.indexOf(ids, p, "gone"))
        assertNull(w.repo.indexOf(ids, p, "nope"))
        // Provider numbering without provider numbers: sort_index + 1.
        assertEquals(keys.indexOf("n1"), w.repo.locate(p, EditorList.All, EditorFilter.ALL, ParentalAccess.OPEN, 1))
        w.write(EditorWrite.SetNumber(p, "s1", 50))
        assertEquals(keys.indexOf("s1"), w.repo.locate(p, EditorList.All, EditorFilter.ALL, ParentalAccess.OPEN, 50))
        assertNull(w.repo.locate(p, EditorList.Group("g:news"), EditorFilter.ALL, ParentalAccess.OPEN, 50))
        assertTrue(w.repo.playlistExists(p))
        assertFalse(w.repo.playlistExists(p + 99))
    }

    @Test
    fun rowsCarryTheEditorFacts() = runBlocking {
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'n2'")
        w.db.exec("INSERT INTO hide_rule(playlist_id, target, pattern, is_regex, created_at) VALUES (?, 1, 'N2', 0, 1)", p)
        w.write(EditorWrite.RenameChannel(p, "n1", "  "))
        w.write(EditorWrite.RenameChannel(p, "n3", "Three"))
        w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("n3")), true))
        val ids = w.repo.channels(p, EditorList.Group("g:news"), EditorFilter.ALL, ParentalAccess.OPEN).ids
        val rows = w.repo.channelRows(ids, 0, 10).filterNotNull().associateBy { it.stableKey }
        assertEquals(4, rows.size)
        val n3 = rows.getValue("n3")
        assertEquals("Three" to "N3", n3.name to n3.providerName)
        assertTrue(n3.renamed && n3.favorite)
        assertEquals(3, n3.number)
        assertFalse("blank rename = the provider's name", rows.getValue("n1").renamed)
        assertEquals("Sport", rows.getValue("s2").movedFrom)
        assertNull(rows.getValue("n1").movedFrom)
        assertEquals("N2", rows.getValue("n2").rulePattern)
        assertTrue(rows.getValue("n2").ruleHidden)
        assertEquals(listOf(null), w.repo.channelRows(longArrayOf(9_999), 0, 1))
    }
}
