package app.thebox.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.Dispatchers
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

/** Performance review M4: one playlist's matching subjects are read through a playlist index, not a scan of all channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelSubjectsPlanTest {
    private lateinit var db: LibraryDatabase

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    @Test
    fun onePlaylistsSubjectsUseAPlaylistIndex() = runBlocking {
        // The framework driver refuses EXPLAIN through a statement; read the plan from the same file with rawQuery.
        val name = "plan-${System.nanoTime()}.db"
        val room = LibraryDatabase.build(TestDb.context, Dispatchers.IO, AndroidSQLiteDriver(), name)
        try {
            room.playlistDao().all() // creates the schema
            val raw = SQLiteDatabase.openDatabase(TestDb.context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
            val plan = raw.use { d ->
                d.rawQuery("EXPLAIN QUERY PLAN " + ChannelDao.SUBJECTS_OF_PLAYLIST.replace(":playlistId", "?"), arrayOf("1")).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(3)) }
                }
            }
            val channel = plan.filter { Regex("""\b(SCAN|SEARCH) c\b""").containsMatchIn(it) }
            assertTrue(plan.toString(), channel.isNotEmpty() && channel.all { it.startsWith("SEARCH") && it.contains("playlist_id=?") })
            assertFalse(plan.toString(), plan.any { Regex("""^SCAN c\b""").containsMatchIn(it) })
        } finally {
            room.close()
        }
    }

    @Test
    fun bothQueriesReturnTheSameRowsAsBefore() = runBlocking {
        val a = db.newPlaylist("A")
        val b = db.newPlaylist("B")
        db.newChannel(a, "a2", sortIndex = 2)
        db.newChannel(a, "a1", sortIndex = 1)
        db.newChannel(a, "vod", kind = 2)
        db.newChannel(a, "gone", missingSince = 5)
        db.newChannel(b, "b1", sortIndex = 0)
        val off = db.newPlaylist("Off") { it.copy(enabled = false) }
        db.newChannel(off, "o1")
        val dao = db.channelDao()
        assertEquals(listOf("a1", "a2"), dao.subjects(a).map { it.stableKey })
        assertEquals(listOf("a1", "a2", "b1"), dao.allSubjects().map { it.stableKey })
        assertTrue(dao.subjects(off).isEmpty())
    }
}
