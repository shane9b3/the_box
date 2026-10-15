package app.thebox.data.player

import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeCredentialStore
import app.thebox.data.testutil.FakeTimeBaseFactory
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * docs/design/m4-player-ux.md §5.3: the catch-up probe winner is read and written through one column
 * (`catchup_probe_winner`), never through the whole-row `PlaylistDao.update` / `PlaylistRepository.update` (M2 N-L13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistDaoProbeWinnerTest {
    private lateinit var db: LibraryDatabase

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    /** A [PlaylistDao] that records the name of every method called on it, then delegates to Room's. */
    private class Recording(real: PlaylistDao) {
        val calls = mutableListOf<String>()
        val dao: PlaylistDao = Proxy.newProxyInstance(PlaylistDao::class.java.classLoader, arrayOf(PlaylistDao::class.java)) { _, m, args ->
            calls += m.name
            try {
                m.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as PlaylistDao
    }

    private fun repo(dao: PlaylistDao) =
        PlaylistRepository(dao, db.xtreamAccountDao(), db.epgSourceDao(), FakeCredentialStore(), FakeTimeBaseFactory(), FakeClock())

    @Test
    fun setAndGetRoundTrip() = runBlocking {
        val id = db.newPlaylist("A")
        val dao = db.playlistDao()
        assertNull(dao.catchupProbeWinner(id))
        dao.setCatchupProbeWinner(id, "flussonic-ts")
        assertEquals("flussonic-ts", dao.catchupProbeWinner(id))
        dao.setCatchupProbeWinner(id, null)
        assertNull(dao.catchupProbeWinner(id))
        assertNull(dao.catchupProbeWinner(id + 99))
    }

    @Test
    fun onlyTheProbeColumnIsWritten() = runBlocking {
        val id = db.newPlaylist("A")
        val dao = db.playlistDao()
        val other = db.newPlaylist("B")
        // A stale copy of the row, then a concurrent write of another column: a whole-row update from the stale copy
        // would roll that write back.
        val before = dao.get(id)!!
        dao.setActiveBaseIndex(id, 2)
        dao.setXtreamOutputLearned(id, "HLS")

        CatchupProbeStoreImpl(repo(dao)).setWinner(id, "xc-ts")

        val after = dao.get(id)!!
        assertEquals("xc-ts", after.catchupProbeWinner)
        assertEquals(2, after.activeBaseIndex)
        assertEquals("HLS", after.xtreamOutputLearned)
        assertEquals(
            before.copy(activeBaseIndex = 2, xtreamOutputLearned = "HLS", catchupProbeWinner = "xc-ts"),
            after,
        )
        assertNull("another playlist's row is untouched", dao.catchupProbeWinner(other))
    }

    @Test
    fun storeUsesTheColumnTargetedCallsOnly() = runBlocking {
        val id = db.newPlaylist("A")
        val rec = Recording(db.playlistDao())
        val store = CatchupProbeStoreImpl(repo(rec.dao))

        assertNull(store.winner(id))
        store.setWinner(id, "flussonic-hls")
        assertEquals("flussonic-hls", store.winner(id))

        assertEquals(listOf("catchupProbeWinner", "setCatchupProbeWinner", "catchupProbeWinner"), rec.calls)
    }

    @Test
    fun aFailingDaoDoesNotReachTheCaller() = runBlocking {
        val broken = Proxy.newProxyInstance(PlaylistDao::class.java.classLoader, arrayOf(PlaylistDao::class.java)) { _, _, _ ->
            throw IllegalStateException("db closed")
        } as PlaylistDao
        val store = CatchupProbeStoreImpl(repo(broken))
        assertNull(store.winner(1))
        store.setWinner(1, "xc-ts")
    }
}
