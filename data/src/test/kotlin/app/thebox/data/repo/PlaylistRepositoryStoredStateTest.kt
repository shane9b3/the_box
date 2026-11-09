package app.thebox.data.repo

import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeCredentialStore
import app.thebox.data.testutil.FakeTimeBaseFactory
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.After
import app.thebox.core.catchup.TimeBaseFactoryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Per-playlist catch-up state built from stored columns and rows only.
 *
 * Correctness review F2: the "Xtream-shaped M3U" catch-up flag comes from stored state (channels keyed by the
 * Xtream rule), not from the in-memory secret cache, which is empty at process start. M3U_FILE counts too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistRepositoryStoredStateTest {
    private lateinit var db: LibraryDatabase

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    private fun freshRepo() =
        PlaylistRepository(db.playlistDao(), db.xtreamAccountDao(), db.epgSourceDao(), FakeCredentialStore(), FakeTimeBaseFactory(), FakeClock())

    private suspend fun shaped(id: Long) = freshRepo().let { it.catchupDefaults(it.get(id)!!).xtreamShaped }

    @Test
    fun anM3uWhoseChannelsHaveXtreamKeysIsXtreamShapedAfterARestart() = runBlocking {
        val url = db.newPlaylist("U", PlaylistType.M3U_URL)
        db.newChannel(url, "tvg:bbc|bbc one")
        db.newChannel(url, "xc:101~2")
        val file = db.newPlaylist("F", PlaylistType.M3U_FILE)
        db.newChannel(file, "xcm:7")
        assertTrue(shaped(url))
        assertTrue(shaped(file))
    }

    @Test
    fun aPlainM3uAndAnXtreamPlaylistAreNot() = runBlocking {
        val plain = db.newPlaylist("P", PlaylistType.M3U_URL)
        db.newChannel(plain, "tvg:bbc|bbc one")
        db.newChannel(plain, "url:h/x.ts")
        db.newChannel(plain, "ng:xc|news")
        val xtream = db.newPlaylist("X", PlaylistType.XTREAM)
        db.newChannel(xtream, "xc:1")
        val other = db.newPlaylist("O", PlaylistType.M3U_URL)
        assertFalse(shaped(plain))
        assertFalse("the flag is for M3U only; Xtream has its own path", shaped(xtream))
        assertFalse(shaped(other))
    }

    @Test
    fun anXtreamPlaylistWithNoAccountRowStillHonoursTheTokenZoneSetting() = runBlocking {
        // Correctness review F3: the stored-column fallback goes through the factory.
        val id = db.newPlaylist("X", PlaylistType.XTREAM) {
            it.copy(tokenZoneSetting = "UTC", serverZone = "Europe/Berlin", clockSkewSec = 4, paddingBeforeSec = -10)
        }
        val repo = PlaylistRepository(db.playlistDao(), db.xtreamAccountDao(), db.epgSourceDao(), FakeCredentialStore(), TimeBaseFactoryImpl(), FakeClock())
        val tb = repo.timeBase(repo.get(id)!!)
        assertEquals(ZoneId.of("Europe/Berlin"), tb.serverZone)
        assertEquals(Duration.ofSeconds(4), tb.clockSkew)
        assertEquals(ZoneOffset.UTC, tb.tokenZone)
        assertEquals(Duration.ZERO, tb.paddingBefore)
    }
}
