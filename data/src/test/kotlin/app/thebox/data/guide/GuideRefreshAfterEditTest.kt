package app.thebox.data.guide

import app.thebox.core.editor.ChannelSort
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupTarget
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.editor.EditorWorld
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * docs/design/m6-channel-editor.md §6.2 `GuideRefreshAfterEditTest` (§3.7): the guide's lineup and Groups panel
 * re-emit once after each editor write; a move-only pref changes the prefs signature; watch-history writes never emit
 * the editor's `changes()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideRefreshAfterEditTest {
    private lateinit var w: EditorWorld
    private lateinit var gg: GuideGraph
    private val scope = CoroutineScope(Dispatchers.IO)
    private val jobs = ArrayList<Job>()
    private val p get() = w.p

    @Before
    fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        gg = GuideGraph(TestGraph(w.db))
        w.group("g:a", "A", sort = 0); w.group("g:b", "B", sort = 1)
        for (i in 0 until 4) w.channel("a$i", "g:a", i)
        w.channel("b0", "g:b", 10)
    }

    @After fun tearDown() { jobs.forEach { it.cancel() }; w.close() }

    private fun <T> count(flow: Flow<T>): AtomicInteger {
        val n = AtomicInteger()
        jobs += scope.launch { flow.collect { n.incrementAndGet() } }
        return n
    }

    /** Runs [block] and asserts [n] grows by exactly one (waits for the first, then for a stray second). */
    private suspend fun once(label: String, n: AtomicInteger, block: suspend () -> Unit) {
        val before = n.get()
        block()
        withTimeout(5_000) { while (n.get() == before) delay(10) }
        delay(400)
        assertEquals(label, before + 1, n.get())
    }

    @Test
    fun theLineupReEmitsOnceAfterEachWriteType() = runBlocking {
        val lineup = count(gg.library.lineup(GuideSource.Group(p, "g:a"), ParentalAccess.LOCKED_AUTO))
        withTimeout(5_000) { while (lineup.get() == 0) delay(10) }
        delay(300)
        once("hide", lineup) { w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("a0")), true)) }
        once("move in", lineup) { w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b0")), "g:a")) }
        once("reorder", lineup) { w.write(EditorWrite.ReorderChannels(p, "g:a", listOf("b0"), 0)) }
        once("sort", lineup) { w.write(EditorWrite.SortChannels(p, "g:a", ChannelSort.NAME_ZA)) }
        once("lock", lineup) { w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a1")), true)) }
        once("reset", lineup) { w.write(EditorWrite.ResetChannels(p, ChannelTarget.Keys(setOf("a0", "a1")))) }
        once("provider order", lineup) { w.write(EditorWrite.SortChannels(p, "g:a", ChannelSort.PROVIDER)) }
        once("move back", lineup) { w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b0")), "g:b")) }
    }

    @Test
    fun theGroupsPanelReEmitsOnceAfterEachWriteType() = runBlocking {
        val sources = count(gg.library.sources(ParentalAccess.OPEN))
        withTimeout(5_000) { while (sources.get() == 0) delay(10) }
        delay(300)
        once("move changes the counts", sources) { w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("a3")), "g:b")) }
        once("rename group", sources) { w.write(EditorWrite.RenameGroup(p, "g:b", "Bee")) }
        once("group order", sources) { w.write(EditorWrite.ReorderGroups(p, listOf("g:b"), 0)) }
        once("hide group", sources) { w.write(EditorWrite.HideGroups(p, GroupTarget.Keys(setOf("g:b")), true)) }
        once("favorite", sources) { w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("a0")), true)) }
    }

    @Test
    fun aMoveOnlyPrefChangesThePrefsSignature() = runBlocking {
        val before = gg.queries.prefsSignature(false)
        w.db.channelPrefsDao().upsertCustomGroup(p, "a2", "g:b", null, 1)
        assertNotEquals(before, gg.queries.prefsSignature(false))
        assertEquals("the move-only row is in the signature's WHERE", 1L, w.db.scalar("SELECT COUNT(*) FROM (${GuideQueries.PREFS_SIGNATURE_SELECT})"))
    }

    @Test
    fun watchHistoryNeverEmitsTheEditorsChanges() = runBlocking {
        val changes = count(w.repo.changes(p))
        delay(500)
        assertEquals("no initial emission", 0, changes.get())
        w.db.channelPrefsDao().recordWatched(p, "a0", 5)
        w.db.channelPrefsDao().upsertPlaybackFlags(p, "a1", 3, 6)
        w.db.channelPrefsDao().upsertFpsMilli(p, "a1", 50_000, 7)
        delay(600)
        assertEquals("tune-time writes are not editor changes", 0, changes.get())
        w.write(EditorWrite.RenameChannel(p, "a0", "Mine"))
        withTimeout(5_000) { while (changes.get() == 0) delay(10) }
        w.write(EditorWrite.HideGroups(p, GroupTarget.Keys(setOf("g:b")), true))
        withTimeout(5_000) { while (changes.get() < 2) delay(10) }
    }

    @Test
    fun oneMergeOfManyBatchesReloadsTheEditorOnce() = runBlocking {
        val changes = count(w.repo.changes(p))
        delay(500)
        val entries = (0 until 200).map { app.thebox.data.testutil.pc("C$it", position = it, group = "G${it % 5}") }
        val sink = app.thebox.data.repo.RoomMergeSink(w.db, p, 1_760_000_000_000L, 1)
        app.thebox.data.testutil.FakeDiffer().merge(
            app.thebox.core.refresh.EntryProducer { c -> entries.forEach(c) },
            app.thebox.data.repo.RoomExistingChannels(w.db, p),
            sink,
            app.thebox.core.refresh.MergeContext(p, app.thebox.core.source.PlaylistType.M3U_URL, 1, 1_760_000_000_000L, previousLiveCount = 0, batchSize = 10),
        )
        assertTrue("several batches", sink.batches >= 10)
        withTimeout(5_000) { while (changes.get() == 0) delay(10) }
        delay(800)
        assertEquals("one reload for the whole merge", 1, changes.get())
    }
}
