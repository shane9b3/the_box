package app.thebox.data.guide

import app.thebox.data.guide.GuideLibraryImpl.Companion.spaced
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Performance review H4: a burst of invalidations re-queries the guide a few times, not once per commit. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideRequerySpacingTest {
    private val gap = GuideLibraryImpl.REQUERY_GAP_MS

    @Test
    fun theFirstChangePassesAtOnceABurstIsConflatedAndTheLastChangeAlwaysLands() = runTest {
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val at = ArrayList<Long>()
        val job = launch { changes.consumeAsFlow().spaced(gap).collect { at += currentTime } }
        changes.send(Unit)
        runCurrent()
        assertEquals("the initial emission is not delayed", listOf(0L), at)

        // An import: 50 commits, 20 ms apart (1 s in all).
        repeat(50) {
            advanceTimeBy(20)
            changes.send(Unit)
        }
        advanceTimeBy(5 * gap)
        runCurrent()
        // 51 changes in 1 s: one re-query per gap, and the last change (at 1 000 ms) still gets one of its own.
        assertEquals(listOf(0L, gap, 2 * gap, 3 * gap), at)

        // After a quiet spell a user's change passes at once.
        val t = currentTime
        changes.send(Unit)
        runCurrent()
        assertEquals(t, at.last())
        job.cancel()
    }
}
