package app.thebox.data.parental

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupTarget
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.editor.EditorWorld
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.CompletableDeferred
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

/**
 * docs/design/m6-channel-editor.md §6.2 `EditorBulkLockTest`: the bulk `ParentalControls` locks run as one transaction
 * each; unlock, Reset and move-out are refused under `refusesLowering()`; Lock is always allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorBulkLockTest {
    private lateinit var w: EditorWorld
    private val p get() = w.p

    @Before
    fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        w.group("g:a", sort = 0); w.group("g:b", sort = 1)
        for (i in 0 until 300) w.channel("a$i", "g:a", i)
        w.channel("b1", "g:b", 400)
    }

    @After fun tearDown() { w.close() }

    /** Invalidations of [table] while [block] runs (after the subscription's initial one). */
    private suspend fun invalidations(table: String, block: suspend () -> Unit): Int = kotlinx.coroutines.coroutineScope {
        val n = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            w.db.invalidationTracker.createFlow(table, emitInitialState = true).collect { if (!ready.isCompleted) ready.complete(Unit) else n.incrementAndGet() }
        }
        withTimeout(5_000) { ready.await() }
        block()
        withTimeout(5_000) { while (n.get() == 0) delay(10) }
        delay(300)
        job.cancel()
        n.get()
    }

    @Test
    fun bulkLocksAreOneTransactionEach() = runBlocking {
        val all = ChannelTarget.AllOf(EditorList.Group("g:a"), EditorFilter.ALL, setOf("a0"))
        assertEquals(1, invalidations("channel_prefs") { w.session.setChannelsLocked(p, all, ParentalAccess.OPEN, true) })
        assertEquals(299L, w.db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE locked = 1"))
        assertEquals(1, invalidations("group_prefs") { w.session.setGroupsLocked(p, listOf("g:a", "g:b"), true) })
        assertEquals(listOf("g:a|1", "g:b|1"), w.db.strings("SELECT group_key || '|' || locked FROM group_prefs ORDER BY group_key"))
        assertEquals(299, w.session.lockChannels(p, all, ParentalAccess.OPEN, false))
        assertEquals(299L, w.db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE locked = 0"))
    }

    @Test
    fun loweringIsRefusedWhileTheSessionIsLocked() = runBlocking {
        w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a1")), true))
        w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:b")), true))
        w.lockSession()
        assertTrue(w.session.refusesLowering())
        val refused = WriteResult.Failed(WriteFailure.REFUSED)
        assertEquals(refused, w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a1")), false), ParentalAccess.LOCKED_AUTO))
        assertEquals(refused, w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:b")), false), ParentalAccess.LOCKED_AUTO))
        assertEquals(refused, w.write(EditorWrite.ResetChannels(p, ChannelTarget.Keys(setOf("a1", "a2"))), ParentalAccess.LOCKED_AUTO))
        assertEquals(refused, w.write(EditorWrite.ResetGroups(p, GroupTarget.Keys(setOf("g:b"))), ParentalAccess.LOCKED_AUTO))
        assertEquals(refused, w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b1")), "g:a"), ParentalAccess.LOCKED_AUTO))
        assertEquals(-1, w.session.lockChannels(p, ChannelTarget.Keys(setOf("a1")), ParentalAccess.LOCKED_AUTO, false))
        w.session.setGroupsLocked(p, listOf("g:b"), false)
        assertEquals("1", w.pref("a1", "locked"))
        assertEquals("1", w.groupPref("g:b", "locked"))
        assertEquals("g:b stays", "null", w.pref("b1", "custom_group_key").let { if (it == "none") "null" else it })
        // Lock is always allowed; a Reset of unlocked channels lowers nothing and is allowed.
        assertEquals(WriteResult.Done(2), w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a2", "a3")), true), ParentalAccess.LOCKED_AUTO))
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:a")), true), ParentalAccess.LOCKED_AUTO))
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.ResetChannels(p, ChannelTarget.Keys(setOf("a5"))), ParentalAccess.LOCKED_AUTO))
        w.session.unlock(0)
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("a1")), false), ParentalAccess.OPEN))
        assertEquals("0", w.pref("a1", "locked"))
    }

    @Test
    fun anAdultChannelMovedIntoAnExplicitlyUnlockedGroupNeedsThePin() = runBlocking {
        w.group("g:open", sort = 2); w.group("g:c", sort = 3)
        w.channel("x", "g:a", 500, adult = true)
        w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:open")), false)) // explicit "not locked" (no PIN yet)
        w.write(EditorWrite.LockGroups(p, GroupTarget.Keys(setOf("g:b", "g:c")), true))
        w.lockSession()
        assertEquals(WriteResult.Failed(WriteFailure.REFUSED), w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("x")), "g:open"), ParentalAccess.LOCKED_AUTO))
        assertEquals("none", w.pref("x", "custom_group_key"))
        // Locked to locked lowers nothing: allowed without the PIN.
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b1")), "g:c"), ParentalAccess.LOCKED_AUTO))
        // An adult channel into a locked group stays locked: allowed.
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("x")), "g:b"), ParentalAccess.LOCKED_AUTO))
        // Unlocked channels move freely.
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("a7")), "g:open"), ParentalAccess.LOCKED_AUTO))
    }
}
