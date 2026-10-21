package app.thebox.data.guide

import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-channel-editor.md §6.2 `GuideGroupMoveTest` (§3.5 rule d): the guide's Move up/down swaps with the
 * nearest visible group inside `GroupAdminDao.orderedKeys`; hidden groups keep their indices and nothing collides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideGroupMoveTest {
    private lateinit var db: app.thebox.data.db.LibraryDatabase
    private lateinit var gg: GuideGraph
    private var p = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        db = TestDb.create()
        gg = GuideGraph(TestGraph(db))
        p = db.newPlaylist()
        for ((i, k) in listOf("a", "b", "c", "d", "e").withIndex()) {
            val g = db.newGroup(p, "g:$k", k.uppercase())
            db.exec("UPDATE channel_group SET sort_index = ? WHERE id = ?", i, g)
            db.newChannel(p, "ch-$k", "Ch $k", g, sortIndex = i)
        }
        db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, updated_at) VALUES (?, 0, 'g:b', 1, 1)", p)
        db.exec("UPDATE channel_group SET rule_hidden = 1 WHERE group_key = 'g:d'")
    }

    @After fun tearDown() { db.close() }

    private suspend fun move(key: String, action: GroupAction) =
        gg.library.groupAction(GuideSourceEntry(GuideSource.Group(p, key), key, 1, SourceSection.GROUP, null), action)

    private suspend fun order() = db.groupAdminDao().orderedKeys(p, 0)
    private suspend fun indices() = db.strings("SELECT group_key || '=' || custom_sort_index FROM group_prefs WHERE custom_sort_index IS NOT NULL ORDER BY custom_sort_index")

    @Test
    fun moveUpAndDownSkipHiddenGroupsWhichKeepTheirSlots() = runBlocking {
        move("g:c", GroupAction.MOVE_UP)
        assertEquals(listOf("g:c", "g:b", "g:a", "g:d", "g:e"), order())
        assertEquals(listOf("g:c=0", "g:b=1", "g:a=2", "g:d=3", "g:e=4"), indices())
        move("g:a", GroupAction.MOVE_DOWN)
        assertEquals(listOf("g:c", "g:b", "g:e", "g:d", "g:a"), order())
        move("g:c", GroupAction.MOVE_UP) // already the first visible group
        move("g:a", GroupAction.MOVE_DOWN) // already the last
        assertEquals(listOf("g:c", "g:b", "g:e", "g:d", "g:a"), order())
        assertEquals("dense, no collision", listOf("0", "1", "2", "3", "4"), indices().map { it.substringAfter('=') })
    }
}
