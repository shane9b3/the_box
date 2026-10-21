package app.thebox.data.editor

import android.database.sqlite.SQLiteDatabaseLockedException
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.WriteFailure
import app.thebox.core.editor.WriteResult
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

/** docs/design/m6-channel-editor.md §3.5: SQLITE_BUSY is retried once after a short pause, then reported as BUSY. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorBusyRetryTest {
    private lateinit var w: EditorWorld
    private val hide get() = EditorWrite.HideChannels(w.p, ChannelTarget.Keys(setOf("a")), true)

    @Before fun setUp() = runBlocking<Unit> { w = EditorWorld(); w.group("g:a"); w.channel("a", "g:a", 0) }
    @After fun tearDown() { w.close() }

    @Test
    fun aBusyFirstAttemptIsRetriedOnce() = runBlocking {
        val attempts = ArrayList<Int>()
        w.repo.beforeAttempt = { n -> attempts += n; if (n == 0) throw SQLiteDatabaseLockedException("database is locked (code 5 SQLITE_BUSY)") }
        assertEquals(WriteResult.Done(1), w.write(hide))
        assertEquals(listOf(0, 1), attempts)
        assertEquals("1", w.pref("a", "hidden"))
    }

    @Test
    fun busyTwiceIsBusyAndOtherErrorsAreStorage() = runBlocking {
        w.repo.beforeAttempt = { throw RuntimeException("wrapped", SQLiteDatabaseLockedException("database is locked")) }
        assertEquals(WriteResult.Failed(WriteFailure.BUSY), w.write(hide))
        var calls = 0
        w.repo.beforeAttempt = { calls++; throw IllegalStateException("disk I/O error") }
        assertEquals(WriteResult.Failed(WriteFailure.STORAGE), w.write(hide))
        assertEquals("no retry for a non-busy error", 1, calls)
        assertTrue(w.repo.busy(Exception("SQLITE_BUSY[5]")))
        assertFalse(w.repo.busy(Exception("SQLITE_FULL")))
    }
}
