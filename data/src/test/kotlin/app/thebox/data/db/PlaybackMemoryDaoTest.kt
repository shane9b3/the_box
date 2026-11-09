package app.thebox.data.db

import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.repo.ChannelPrefsRepository
import app.thebox.data.testutil.FakeClock
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

/** v2 column-targeted writes (docs/design/m2-playback-core.md §2.10, review S10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackMemoryDaoTest {
    private lateinit var db: LibraryDatabase
    private val clock = FakeClock()

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    @Test
    fun targetedUpsertsCreateTheRowAndTouchOneColumn() = runBlocking {
        val p = db.newPlaylist()
        val c = ChannelIdentity(p, StableKey("a"))
        val repo = ChannelPrefsRepository(db.channelPrefsDao(), db.groupDao(), clock, db)

        repo.setFpsMilli(c, 50_000)                       // creates the row
        repo.setHidden(c, true)
        repo.setLocked(c, false)
        clock.advance(10)
        repo.setPlaybackFlags(c, 0b101)
        repo.setAudioLang(c, "deu")
        repo.setSubtitleLang(c, "")
        repo.setAspectMode(c, "FILL")
        repo.setVideoDecoder(c, "SOFTWARE_PREFERRED")
        repo.setBufferProfile(c, "LARGE")
        repo.recordWatched(c)

        val row = repo.get(c)!!
        assertEquals(true, row.hidden)
        assertEquals(false, row.locked)
        assertEquals(0b101, row.playbackFlags)
        assertEquals(50_000, row.fpsMilli)
        assertEquals("deu", row.audioLang)
        assertEquals("", row.subtitleLang)
        assertEquals("FILL", row.aspectMode)
        assertEquals("SOFTWARE_PREFERRED", row.videoDecoder)
        assertEquals("LARGE", row.bufferProfile)
        assertEquals(1, row.watchCount)
        assertEquals(clock.now(), row.updatedAt)

        repo.setFpsMilli(c, null)
        assertNull(repo.get(c)!!.fpsMilli)
        assertEquals(true, repo.get(c)!!.hidden)
    }

    /**
     * Correctness review F4: a playback-memory write that lands between `edit`'s read and its write must not be
     * reverted. The DAO below starts that write from another thread right after the read and gives it 500 ms.
     */
    @Test
    fun editIsNotARaceWithAColumnTargetedWrite() = runBlocking {
        val p = db.newPlaylist()
        val c = ChannelIdentity(p, StableKey("a"))
        val real = db.channelPrefsDao()
        real.upsertPlaybackFlags(p, "a", 1, 1)
        var writer: Thread? = null
        val dao = object : ChannelPrefsDao by real {
            override suspend fun get(playlistId: Long, stableKey: String): app.thebox.data.db.entity.ChannelPrefsEntity? {
                val row = real.get(playlistId, stableKey)
                if (writer == null) {
                    writer = Thread { runBlocking { real.upsertPlaybackFlags(p, "a", 2, 2) } }.also { it.start(); it.join(500) }
                }
                return row
            }
        }
        ChannelPrefsRepository(dao, db.groupDao(), clock, db).setHidden(c, true)
        writer!!.join(5_000)
        val row = real.get(p, "a")!!
        assertEquals(true, row.hidden)
        assertEquals("the playback write is kept", 2, row.playbackFlags)
    }

    @Test
    fun xtreamOutputChangeClearsTheLearnedValue() = runBlocking {
        val p = db.newPlaylist()
        val dao = db.playlistDao()
        dao.setXtreamOutputLearned(p, "HLS")
        assertEquals("HLS", dao.get(p)!!.xtreamOutputLearned)
        dao.setXtreamOutput(p, "TS")
        assertEquals("TS", dao.get(p)!!.xtreamOutput)
        assertNull(dao.get(p)!!.xtreamOutputLearned)
    }
}
