package app.thebox.data.editor

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
import app.thebox.data.guide.exec
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** docs/design/m6-channel-editor.md §6.2 `EditorBulkWritesTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorBulkWritesTest {
    private lateinit var w: EditorWorld
    private val p get() = w.p

    @Before fun setUp() { w = EditorWorld() }
    @After fun tearDown() { w.close() }

    private suspend fun bigGroup(n: Int) {
        w.group("g:big", "Big", sort = 0)
        val gid = w.groupIds.getValue("g:big")
        w.db.writeTransaction {
            usePrepared(
                "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, group_id, url, is_adult, " +
                    "content_hash, missing_count, first_seen_at, updated_at) VALUES (?, ?, 'URL', 0, ?, '', ?, ?, 'http://h', 0, 0, 0, 1, 1)",
            ) { st ->
                for (i in 0 until n) {
                    st.bindLong(1, p); st.bindText(2, "k$i"); st.bindText(3, "Channel $i"); st.bindLong(4, i.toLong()); st.bindLong(5, gid)
                    st.step(); st.reset(); st.clearBindings()
                }
            }
        }
    }

    @Test
    fun allOfHideOf5000IsOneTransaction() = runBlocking {
        bigGroup(5_000)
        val fired = AtomicInteger()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            w.db.invalidationTracker.createFlow("channel_prefs", emitInitialState = true).collect {
                if (!ready.isCompleted) ready.complete(Unit) else fired.incrementAndGet()
            }
        }
        withTimeout(5_000) { ready.await() }
        val r = w.write(EditorWrite.HideChannels(p, ChannelTarget.AllOf(EditorList.Group("g:big"), EditorFilter.ALL, emptySet()), true))
        assertEquals(WriteResult.Done(5_000), r)
        withTimeout(5_000) { while (fired.get() == 0) delay(10) }
        delay(300)
        job.cancel()
        assertEquals("one invalidation for the whole write", 1, fired.get())
        assertEquals(5_000L, w.db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE hidden = 1"))
        // Show under "Hidden only" with an exception goes through the resolved-key path.
        val show = w.write(EditorWrite.HideChannels(p, ChannelTarget.AllOf(EditorList.Group("g:big"), EditorFilter.HIDDEN, setOf("k1", "k2")), false))
        assertEquals(WriteResult.Done(4_998), show)
        assertEquals(listOf("k1", "k2"), w.db.strings("SELECT stable_key FROM channel_prefs WHERE hidden = 1 ORDER BY stable_key"))
    }

    @Test
    fun keysHideAndShowSkipsRuleHidden() = runBlocking {
        w.group("g:a")
        w.channel("a", "g:a", 0); w.channel("b", "g:a", 1); w.channel("c", "g:a", 2)
        assertEquals(WriteResult.Done(2), w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("a", "b")), true)))
        assertEquals("1" to "1", w.pref("a", "hidden") to w.pref("b", "hidden"))
        assertEquals("none", w.pref("c", "hidden"))
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'b'")
        assertEquals(WriteResult.Done(2, skippedByRule = 1), w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("a", "b")), false)))
    }

    @Test
    fun favoritesBulkAddCreatesTheDefaultListAndKeepsListOrder() = runBlocking {
        w.group("g:a", sort = 1); w.group("g:b", sort = 0)
        w.channel("a1", "g:a", 0); w.channel("a2", "g:a", 1); w.channel("b1", "g:b", 2)
        assertEquals(0L, w.db.scalar("SELECT COUNT(*) FROM favourite_list"))
        assertEquals(WriteResult.Done(2), w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("a2", "b1")), true)))
        assertEquals(listOf("Favourites"), w.db.strings("SELECT name FROM favourite_list"))
        // Keys are written in list (All) order: g:b comes first.
        assertEquals(listOf("b1", "a2"), w.db.strings("SELECT stable_key FROM favourite ORDER BY sort_index"))
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.Favorite(p, ChannelTarget.AllOf(EditorList.All, EditorFilter.ALL, emptySet()), true)))
        assertEquals(listOf("b1", "a2", "a1"), w.db.strings("SELECT stable_key FROM favourite ORDER BY sort_index"))
        assertEquals(listOf("0", "1", "2"), w.db.strings("SELECT sort_index FROM favourite ORDER BY sort_index"))
        // Remove: from the default list only.
        val other = w.db.favouriteDao().insertList(app.thebox.data.db.entity.FavouriteListEntity(name = "Kids", sortIndex = 5, createdAt = 1))
        w.db.favouriteDao().insert(app.thebox.data.db.entity.FavouriteEntity(other, p, "a1", 0, 1))
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("a1")), false)))
        assertEquals(listOf("Kids|a1"), w.db.strings("SELECT l.name || '|' || f.stable_key FROM favourite f JOIN favourite_list l ON l.id = f.list_id WHERE f.stable_key = 'a1'"))
    }

    @Test
    fun resetKeepsOrderPlaybackPrefsAndFavoritesOfAnUnmovedChannel() = runBlocking {
        w.group("g:a")
        w.channel("a", "g:a", 0); w.channel("b", "g:a", 1)
        w.write(EditorWrite.ReorderChannels(p, "g:a", listOf("b"), 0))
        w.write(EditorWrite.RenameChannel(p, "a", "Mine"))
        w.write(EditorWrite.SetNumber(p, "a", 7))
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("a")), true))
        w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a")), true))
        w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("a")), true))
        w.db.channelPrefsDao().upsertAudioLang(p, "a", "deu", 1)
        w.db.exec("UPDATE channel_prefs SET epg_shift_min = 30, custom_logo = 'http://l' WHERE stable_key = 'a'")
        assertEquals("1", w.pref("a", "custom_sort_index"))
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.ResetChannels(p, ChannelTarget.Keys(setOf("a")))))
        assertEquals(listOf("null", "null", "0", "null"), listOf("custom_name", "custom_number", "hidden", "locked").map { w.pref("a", it) })
        assertEquals("order is a group property (rule c)", "1", w.pref("a", "custom_sort_index"))
        assertEquals(listOf("deu", "30", "http://l"), listOf("audio_lang", "epg_shift_min", "custom_logo").map { w.pref("a", it) })
        assertEquals(1L, w.db.scalar("SELECT COUNT(*) FROM favourite WHERE stable_key = 'a'"))
        assertEquals(listOf("b", "a"), w.list(EditorList.Group("g:a")))
    }

    @Test
    fun resetOfAMovedChannelReturnsItHome() = runBlocking {
        w.group("g:a", sort = 0); w.group("g:b", sort = 1)
        w.channel("a", "g:a", 0); w.channel("b", "g:b", 1)
        w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("a")), "g:b"))
        assertEquals("g:b" to "1", w.pref("a", "custom_group_key") to w.pref("a", "custom_sort_index"))
        w.write(EditorWrite.ResetChannels(p, ChannelTarget.AllOf(EditorList.Group("g:b"), EditorFilter.ALL, setOf("b"))))
        assertEquals("null" to "null", w.pref("a", "custom_group_key") to w.pref("a", "custom_sort_index"))
        assertEquals(listOf("a"), w.list(EditorList.Group("g:a")))
    }

    @Test
    fun goneWhenEveryKeyHasLeft() = runBlocking {
        w.group("g:a")
        w.channel("a", "g:a", 0)
        val gone = WriteResult.Failed(WriteFailure.GONE)
        assertEquals(gone, w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("x", "y")), true)))
        assertEquals(gone, w.write(EditorWrite.RenameChannel(p, "x", "n")))
        assertEquals(gone, w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("a")), "g:nope")))
        assertEquals(gone, w.write(EditorWrite.HideChannels(p, ChannelTarget.AllOf(EditorList.Group("g:nope"), EditorFilter.ALL, emptySet()), true)))
        assertEquals(gone, w.write(EditorWrite.HideGroups(p, app.thebox.core.editor.GroupTarget.Keys(setOf("g:nope")), true)))
        assertTrue(w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("a", "x")), true)) == WriteResult.Done(1))
        assertEquals(1L, w.db.scalar("SELECT COUNT(*) FROM channel_prefs"))
    }
}
