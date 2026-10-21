package app.thebox.data.editor

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupTarget
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.testutil.TestGraph
import kotlinx.coroutines.flow.first
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

/** docs/design/m6-channel-editor.md §6.2 `MoveToGroupTest`: every reader of §3.6 sees the effective group. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoveToGroupTest {
    private lateinit var w: EditorWorld
    private lateinit var gg: GuideGraph
    private val p get() = w.p

    @Before
    fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        gg = GuideGraph(TestGraph(w.db))
        w.group("g:news", "News", sort = 0)
        w.group("g:sport", "Sport", sort = 1)
        w.group("g:adult", "Adult", sort = 2, adult = true)
        w.channel("n1", "g:news", 0); w.channel("n2", "g:news", 1)
        w.channel("s1", "g:sport", 2)
        w.channel("a1", "g:adult", 3)
    }

    @After fun tearDown() { w.close() }

    private suspend fun lineup(source: GuideSource, access: ParentalAccess = ParentalAccess.OPEN) = w.keysOf(gg.queries.lineup(source, access).ids)
    private fun id(k: String) = ChannelIdentity(p, StableKey(k))
    private suspend fun move(k: String, to: String) = w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf(k)), to))

    @Test
    fun theGuideLineupAndEveryGroupCountSeeTheEffectiveGroup() = runBlocking {
        move("n1", "g:sport")
        assertEquals(listOf("n2"), lineup(GuideSource.Group(p, "g:news")))
        assertEquals("half 2 adds the moved channel, at the end", listOf("s1", "n1"), lineup(GuideSource.Group(p, "g:sport")))
        assertEquals(listOf("n2", "s1", "n1", "a1"), lineup(GuideSource.All(p)))
        assertTrue(gg.queries.lineup(GuideSource.Group(p, "g:sport"), ParentalAccess.OPEN).archiveDaysUpperBound >= 0)
        val panel = w.db.groupDao().panelGroups(p, 0, 0).first().associate { it.group.groupKey to it.channelCount }
        assertEquals(mapOf("g:news" to 1, "g:sport" to 2, "g:adult" to 1), panel)
        val groups = w.db.groupDao().groups(p, 0, true).first().associate { it.group.groupKey to it.channelCount }
        assertEquals(mapOf("g:news" to 1, "g:sport" to 2, "g:adult" to 1), groups)
        val admin = w.db.groupAdminDao().entries(p, 0, 0, 10).associate { it.groupKey to it.channels }
        assertEquals(mapOf("g:news" to 1, "g:sport" to 2, "g:adult" to 1), admin)
        val sources = gg.library.sources(ParentalAccess.OPEN).first().filter { it.source is GuideSource.Group }.associate { (it.source as GuideSource.Group).groupKey to it.count }
        assertEquals(mapOf("g:news" to 1, "g:sport" to 2, "g:adult" to 1), sources)
    }

    @Test
    fun theTargetsHiddenAndLockStateApply() = runBlocking {
        w.write(EditorWrite.HideGroups(p, GroupTarget.Keys(setOf("g:sport")), true))
        move("n1", "g:sport")
        assertEquals("moved into a hidden group: not in the guide", listOf("n2", "a1"), lineup(GuideSource.All(p)))
        move("n2", "g:adult")
        assertEquals(listOf("a1", "n2"), lineup(GuideSource.Group(p, "g:adult")))
        assertEquals("moved into an adult group: locked", emptyList<String>(), lineup(GuideSource.All(p), ParentalAccess.LOCKED_AUTO))
        move("a1", "g:news")
        assertEquals("moved out of the adult group: unlocked", listOf("a1"), lineup(GuideSource.All(p), ParentalAccess.LOCKED_AUTO))
    }

    @Test
    fun aMoveBackWritesNull() = runBlocking {
        move("n1", "g:sport")
        assertEquals("g:sport", w.pref("n1", "custom_group_key"))
        assertEquals(WriteResult.Done(1), move("n1", "g:news"))
        assertEquals("null" to "null", w.pref("n1", "custom_group_key") to w.pref("n1", "custom_sort_index"))
        assertEquals("already there: nothing to do", WriteResult.Done(0), move("n1", "g:news"))
    }

    @Test
    fun theTuneGuardSeesTheEffectiveGroup() = runBlocking {
        w.group("g:locked", "Locked", sort = 3)
        w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:locked")), true))
        w.lockSession()
        assertEquals(ParentalAccess.LOCKED_AUTO, w.session.state.value.access)
        move("n1", "g:locked") // into a locked group: always allowed
        w.index.rebuild()
        assertEquals(TuneVerdict.NEEDS_PIN, w.guard.check(id("n1")))
        assertEquals(GuideSource.Group(p, "g:locked"), w.index.groupOf(id("n1")))
        assertEquals(TuneVerdict.ALLOWED, w.guard.check(id("n2")))
        // Out of a locked group needs the PIN: refused while the session is locked.
        assertEquals(WriteResult.Failed(WriteFailure.REFUSED), move("n1", "g:news"))
        assertEquals(WriteResult.Failed(WriteFailure.REFUSED), move("a1", "g:news"))
        w.session.unlock(0)
        assertEquals(WriteResult.Done(1), move("a1", "g:news"))
        w.session.relock(app.thebox.core.parental.RelockReason.TIMEOUT)
        w.index.rebuild()
        assertEquals("moved out of the adult group", TuneVerdict.ALLOWED, w.guard.check(id("a1")))
        assertEquals("no longer lockable content: not in the guard's index", null, w.index.groupOf(id("a1")))
    }

    @Test
    fun searchFixGuideDataAndFavouritesSeeTheEffectiveGroup() = runBlocking {
        move("n1", "g:sport")
        assertEquals("g:sport", w.db.channelDao().search("n1*", true, 10).single().groupKey)
        assertEquals("g:sport", w.db.channelDao().row(p, "n1")!!.groupKey)
        assertEquals("Sport", w.db.channelDao().unmatched(true).first().single { it.stableKey == "n1" }.groupName)
        val list = w.db.favouriteDao().insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 1))
        w.db.favouriteDao().insert(FavouriteEntity(list, p, "n1", 0, 1))
        assertEquals("g:sport", w.db.favouriteDao().channels(list, true).first().single().groupKey)
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("n1")), true))
        assertEquals("Sport", w.db.hiddenChannelsDao().entries(p, 0, 10).single().groupName)
        // ChannelDao.browseGroup takes the effective group's id (M1-only).
        assertEquals(listOf("s1"), w.db.channelDao().browseGroup(p, w.groupIds.getValue("g:sport"), true).first().map { it.channel.stableKey })
    }

    @Test
    fun aMissingTargetFallsBackToTheProviderGroup() = runBlocking {
        w.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key) VALUES (?, 'n1', 0, 0, 1, 'g:nope')", p)
        assertEquals(listOf("n1", "n2"), lineup(GuideSource.Group(p, "g:news")))
        assertEquals(emptyList<String>(), lineup(GuideSource.Group(p, "g:nope")))
        assertEquals("g:news", w.db.channelDao().row(p, "n1")!!.groupKey)
        assertFalse(w.db.groupDao().panelGroups(p, 0, 0).first().any { it.group.groupKey == "g:nope" })
    }

    @Test
    fun aChannelMovedToItsOwnProviderKeyAppearsOnce() = runBlocking {
        // Possible through a restore: custom_group_key equals the provider group's key.
        w.db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key) VALUES (?, 'n1', 0, 0, 1, 'g:news')", p)
        assertEquals("once, in its provider position (half 2 only)", listOf("n1", "n2"), lineup(GuideSource.Group(p, "g:news")))
        assertEquals(listOf("n1", "n2"), w.list(app.thebox.core.editor.EditorList.Group("g:news")))
        assertEquals(1, lineup(GuideSource.All(p)).count { it == "n1" })
        assertEquals(mapOf("g:news" to 2), w.db.groupDao().panelGroups(p, 0, 0).first().filter { it.group.groupKey == "g:news" }.associate { it.group.groupKey to it.channelCount })
        // A move "back" to its own group normalises the key to NULL.
        assertEquals(WriteResult.Done(1), move("n1", "g:news"))
        assertEquals("null", w.pref("n1", "custom_group_key"))
    }
}
