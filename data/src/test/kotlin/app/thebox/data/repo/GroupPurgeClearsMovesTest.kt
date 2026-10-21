package app.thebox.data.repo

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupTarget
import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.EntryProducer
import app.thebox.core.refresh.MergeContext
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.data.editor.EditorWorld
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.pc
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-channel-editor.md §6.2 `GroupPurgeClearsMovesTest` (§3.5 rule e, §3.6 purge and review 2 ruling a).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupPurgeClearsMovesTest {
    private lateinit var w: EditorWorld
    private var seq = 0L
    private val day = 24L * 3600 * 1000
    private val t0 = 1_760_000_000_000L

    @Before fun setUp() { w = EditorWorld() }
    @After fun tearDown() { w.close() }

    private fun refresh(entries: List<ParsedChannel>, now: Long) {
        seq++
        val sink = RoomMergeSink(w.db, w.p, now, seq)
        FakeDiffer().merge(EntryProducer { c -> entries.forEach(c) }, RoomExistingChannels(w.db, w.p), sink, MergeContext(w.p, PlaylistType.M3U_URL, seq, now, previousLiveCount = 0))
    }

    private val both = listOf(pc("A1", position = 0, group = "News"), pc("K1", position = 1, group = "Kids"))
    private val newsOnly = listOf(pc("A1", position = 0, group = "News"))
    private suspend fun key(name: String) = w.db.strings("SELECT stable_key FROM channel WHERE name = ?", name).single()
    private suspend fun groupKey(name: String) = w.db.strings("SELECT group_key FROM channel_group WHERE name = ?", name).single()

    @Test
    fun targetInGraceStillLockedAndHidden() = runBlocking {
        refresh(both, t0)
        val a1 = key("A1"); val kids = groupKey("Kids")
        w.write(EditorWrite.MoveChannels(w.p, ChannelTarget.Keys(setOf(a1)), kids))
        w.write(EditorWrite.LockGroups(w.p, GroupTarget.Keys(setOf(kids)), true))
        w.write(EditorWrite.HideGroups(w.p, GroupTarget.Keys(setOf(kids)), true))
        refresh(newsOnly, t0 + day) // Kids is dropped: missing, inside its grace period
        assertTrue(w.db.scalar("SELECT missing_since FROM channel_group WHERE group_key = ?", kids) > 0)
        assertEquals(kids, w.pref(a1, "custom_group_key"))
        w.lockSession()
        w.index.rebuild()
        val id = ChannelIdentity(w.p, StableKey(a1))
        assertEquals("target in grace: still locked", TuneVerdict.NEEDS_PIN, w.guard.check(id))
        assertEquals(GuideSource.Group(w.p, kids), w.index.groupOf(id))
        val gg = GuideGraph(TestGraph(w.db))
        assertFalse("still hidden", a1 in w.keysOf(gg.queries.lineup(GuideSource.All(w.p), ParentalAccess.OPEN).ids))
        assertEquals(emptyList<String>(), w.keysOf(gg.queries.lineup(GuideSource.Group(w.p, groupKey("News")), ParentalAccess.OPEN).ids))
        assertFalse("the editor does not list the missing group", kids in w.repo.groups(w.p, true, ParentalAccess.OPEN).keys)
    }

    @Test
    fun aReturningGroupLosesItsStaleIndexInBegin() = runBlocking {
        refresh(both, t0)
        val news = groupKey("News"); val kids = groupKey("Kids")
        w.write(EditorWrite.ReorderGroups(w.p, listOf(kids), 0)) // kids=0, news=1
        refresh(newsOnly, t0 + day)
        w.write(EditorWrite.ReorderGroups(w.p, listOf(news), 0)) // only news is listed now: news=0, kids keeps 0 → would tie
        assertEquals("0" to "0", w.groupPref(news, "custom_sort_index") to w.groupPref(kids, "custom_sort_index"))
        refresh(both, t0 + 2 * day)
        assertEquals("null", w.groupPref(kids, "custom_sort_index"))
        assertEquals("0", w.groupPref(news, "custom_sort_index"))
        assertEquals(listOf(news, kids), w.repo.groups(w.p, true, ParentalAccess.OPEN).keys)
    }

    @Test
    fun aReturningGroupWithNoTieKeepsItsPosition() = runBlocking {
        refresh(both, t0)
        val news = groupKey("News"); val kids = groupKey("Kids")
        w.write(EditorWrite.ReorderGroups(w.p, listOf(kids), 0)) // kids=0, news=1
        refresh(newsOnly, t0 + day) // a one-refresh blip: nothing rewrites the order meanwhile
        refresh(both, t0 + 2 * day)
        assertEquals("0" to "1", w.groupPref(kids, "custom_sort_index") to w.groupPref(news, "custom_sort_index"))
        assertEquals(listOf(kids, news), w.repo.groups(w.p, true, ParentalAccess.OPEN).keys)
    }

    @Test
    fun thePurgeClearsMovedChannelsInTheSameBatch() = runBlocking {
        refresh(both, t0)
        val a1 = key("A1"); val k1 = key("K1"); val kids = groupKey("Kids"); val news = groupKey("News")
        w.write(EditorWrite.MoveChannels(w.p, ChannelTarget.Keys(setOf(a1)), kids))
        assertEquals("1", w.pref(a1, "custom_sort_index"))
        refresh(newsOnly, t0 + day)
        refresh(newsOnly, t0 + 9 * day) // K1 purged; Kids keeps only the moved-in A1, so it is purged too
        assertEquals(0L, w.db.scalar("SELECT COUNT(*) FROM channel WHERE stable_key = ?", k1))
        assertEquals(0L, w.db.scalar("SELECT COUNT(*) FROM channel_group WHERE group_key = ?", kids))
        assertEquals("null" to "null", w.pref(a1, "custom_group_key") to w.pref(a1, "custom_sort_index"))
        assertEquals("its channels go home", listOf(a1), w.list(EditorList.Group(news)))
    }

    @Test
    fun aHandDeletedTargetFallsBack() = runBlocking {
        refresh(both, t0)
        val a1 = key("A1"); val kids = groupKey("Kids")
        w.write(EditorWrite.MoveChannels(w.p, ChannelTarget.Keys(setOf(a1)), kids))
        w.db.exec("UPDATE channel SET group_id = NULL WHERE stable_key = ?", key("K1"))
        w.db.exec("DELETE FROM channel_group WHERE group_key = ?", kids)
        assertEquals(listOf(a1), w.list(EditorList.Group(groupKey("News"))))
    }
}
