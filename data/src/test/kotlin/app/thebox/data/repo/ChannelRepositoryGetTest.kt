package app.thebox.data.repo

import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
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

/** Review 2026-11-09, performance M7b: the tune path's channel and `container_ext` come from one row read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelRepositoryGetTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: ChannelRepository
    private var p = 0L

    @Before fun setUp() = runBlocking {
        db = TestDb.create()
        repo = GuideGraph(TestGraph(db)).channelRepo
        p = db.newPlaylist()
        db.newChannel(p, "vod", "Film")
        db.exec("UPDATE channel SET container_ext = ? WHERE stable_key = ?", "mkv", "vod")
        db.newChannel(p, "live", "News")
        Unit
    }

    @After fun tearDown() { db.close() }

    private fun id(key: String) = ChannelIdentity(p, StableKey(key))

    @Test fun getWithRowCarriesTheChannelAndItsContainerExt() = runBlocking {
        val read = repo.getWithRow(id("vod"))!!
        assertEquals("Film", read.channel.name)
        assertEquals("mkv", read.row.channel.containerExt)
        assertEquals(read.channel.name, repo.get(id("vod"))!!.name)
        assertNull(repo.getWithRow(id("live"))!!.row.channel.containerExt)
    }

    @Test fun getAndGetWithRowAgreeOnAMissingChannelOrPlaylist() = runBlocking {
        assertNull(repo.get(id("nope")))
        assertNull(repo.getWithRow(id("nope")))
        assertNull(repo.getWithRow(ChannelIdentity(p + 99, StableKey("vod"))))
        assertNull(repo.get(ChannelIdentity(p + 99, StableKey("vod"))))
    }
}
