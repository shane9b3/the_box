package app.thebox.data.player

import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.AutoTsRetry.Result
import app.thebox.core.playback.resolve.LearnedOutput
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (docs/decisions/2026-11-25-auto-format-retries-ts.md) The TS-retry state in `playlist.xtream_output_learned`: written
 * by learn/record on a virtual wall clock, read back after the database is closed and reopened (an app restart),
 * cleared by the user's Stream format choice and by removing the playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearnedOutputStoreTest {
    private val name = "library-learned-${System.nanoTime()}.db"
    private lateinit var db: LibraryDatabase
    private val clock = FakeClock()
    private val day = 86_400_000L

    private fun open() = LibraryDatabase.build(TestDb.context, Dispatchers.IO, AndroidSQLiteDriver(), name)
    private fun store() = LearnedOutputStore(db.playlistDao(), clock)
    private suspend fun stored(id: Long) = db.playlistDao().get(id)!!.xtreamOutputLearned
    private suspend fun due(id: Long, account: XtreamAccountInfo? = null) = store().retryDue(db.playlistDao().get(id)!!, account)

    @Before fun setUp() { db = open() }
    @After fun tearDown() { db.close(); TestDb.context.getDatabasePath(name).delete() }

    @Test
    fun theBackOffSurvivesARestart() = runBlocking {
        val id = db.newPlaylist(type = PlaylistType.XTREAM)
        store().learn(id, XtreamOutput.HLS)
        assertEquals("HLS;t=${clock.now()}", stored(id))
        assertFalse(due(id))
        clock.advance(day)
        assertTrue(due(id))
        val refused = store().record(id, Result.REFUSED)!!
        assertEquals(1, refused.step)
        // The flip's first media saves HLS right after: the refusal's step stays.
        store().learn(id, XtreamOutput.HLS)
        assertEquals(refused, LearnedOutput.parse(stored(id)))

        db.close()
        db = open() // restart
        assertEquals(refused, LearnedOutput.parse(stored(id)))
        clock.advance(3 * day - 1)
        assertFalse(due(id))
        clock.advance(1)
        assertTrue(due(id))
        assertEquals(XtreamOutput.HLS, LearnedOutput.parse(stored(id))!!.output)
    }

    @Test
    fun aConfirmedRetryClearsTheColumnAndAnInconclusiveOneHoldsOff() = runBlocking {
        val id = db.newPlaylist(type = PlaylistType.XTREAM) { it.copy(xtreamOutputLearned = "HLS") }
        assertTrue("a value stored before the retry existed is due", due(id))
        val held = store().record(id, Result.INCONCLUSIVE)!!
        assertEquals(clock.now() + 3_600_000L, held.holdUntilMs)
        assertFalse(due(id))
        clock.advance(3_600_000L)
        assertTrue(due(id))
        assertNull(store().record(id, Result.CONFIRMED))
        assertNull(stored(id))
        assertFalse(due(id))
    }

    @Test
    fun manualModesOtherTypesAndTsLessAccountsNeverRetry() = runBlocking {
        val m3u = db.newPlaylist(type = PlaylistType.M3U_URL) { it.copy(xtreamOutputLearned = "HLS") }
        assertFalse(due(m3u))
        val xc = db.newPlaylist(type = PlaylistType.XTREAM) { it.copy(xtreamOutputLearned = "HLS") }
        val hlsOnly = XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, allowedOutputFormats = listOf("m3u8"), measuredAtDeviceMs = 0)
        assertFalse(due(xc, hlsOnly))
        assertTrue(due(xc))
        // The user picks MPEG-TS (or HLS, or Auto again): the learned value and its back-off are gone.
        db.playlistDao().setXtreamOutput(xc, "TS")
        assertNull(stored(xc))
        db.playlistDao().setXtreamOutputLearned(xc, "HLS")
        assertFalse("manual TS", due(xc))
        // A result arriving after the user changed the setting writes nothing.
        assertEquals(LearnedOutput(XtreamOutput.HLS), store().record(xc, Result.CONFIRMED))
        assertEquals("HLS", stored(xc))
    }

    @Test
    fun removingThePlaylistRemovesTheState() = runBlocking {
        val id = db.newPlaylist(type = PlaylistType.XTREAM)
        store().learn(id, XtreamOutput.HLS)
        db.playlistDao().delete(id)
        assertNull(db.playlistDao().get(id))
        assertNull(store().record(id, Result.REFUSED))
    }
}
