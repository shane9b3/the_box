package app.thebox.data.integration

import app.thebox.core.common.Outcome
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.refresh.PlaylistDifferImpl
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.6 `M3uToRoomIT`: WP-A's parser and differ → RoomMergeSink on every `refresh/` pair (fixtures from
 * `core/src/test/resources/refresh/`, shared into :data tests by the build). Favourites must survive
 * `basic`, `host_rotation` and `xc_templated`.
 */
@Ignore("needs WP-A")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3uToRoomIT {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private val url = "http://lb1.example:8080/get.php?username=user1&password=pass1&type=m3u_plus"

    @Before fun setUp() { db = TestDb.create(); g = TestGraph(db) }
    @After fun tearDown() { db.close() }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("refresh/$name")) { "missing fixture refresh/$name" }.readText()

    private suspend fun refreshPair(pair: String, favouriteAll: Boolean = true): Pair<Long, app.thebox.data.repo.RefreshResult> {
        val id = (g.playlists.addM3uUrl(pair, url) as Outcome.Ok).value
        val repo = g.refresh(differ = PlaylistDifferImpl(StableKeyDeriverImpl()), parser = M3uParserImpl())
        g.fetcher.bodies[url] = fixture("${pair}_v1.m3u")
        assertNull(repo.refresh(id, Trigger.ONBOARDING).error)
        if (favouriteAll) {
            val list = db.favouriteDao().insertList(FavouriteListEntity(name = pair, sortIndex = 0, createdAt = 0))
            db.strings("SELECT stable_key FROM channel WHERE playlist_id = ?", id).forEachIndexed { i, k ->
                db.favouriteDao().insert(FavouriteEntity(list, id, k, i, 0))
            }
        }
        g.fetcher.bodies[url] = fixture("${pair}_v2.m3u")
        return id to repo.refresh(id, Trigger.MANUAL)
    }

    @Test
    fun basicKeepsFavouritesOfSurvivingChannels() = runBlocking {
        val (id, r) = refreshPair("basic")
        val report = r.report!!
        assertEquals(2, report.added)
        assertEquals(3, report.newlyMissing)
        assertEquals(2, report.urlChanged)
        assertEquals(db.scalar("SELECT COUNT(*) FROM favourite") - 3, report.favouritesKept.toLong())
        assertEquals(db.scalar("SELECT COUNT(*) FROM channel WHERE playlist_id = ? AND missing_since IS NULL", id), db.scalar("SELECT COUNT(*) FROM channel_fts") - 3)
    }

    @Test
    fun hostRotationKeepsEveryKey() = runBlocking {
        val (_, r) = refreshPair("host_rotation")
        assertEquals(0, r.report!!.added)
        assertEquals(0, r.report!!.newlyMissing)
        assertEquals(0, r.report!!.favouritesOrphaned)
    }

    @Test
    fun xcTemplatedSurvivesAPasswordChangeAndStoresNoPassword() = runBlocking {
        val (_, r) = refreshPair("xc_templated")
        assertEquals(0, r.report!!.newlyMissing)
        assertTrue(r.report!!.credentialsTemplated)
        assertTrue(db.strings("SELECT url FROM channel").all { "{{p}}" in it || "{{u}}" in it || !it.contains("/live/") })
    }

    @Test
    fun providerMigrationSuggestsAMassRemap() = runBlocking {
        val (_, r) = refreshPair("provider_migration")
        assertTrue(r.report!!.massRemapSuggested)
    }

    @Test
    fun shrinkIsRefusedAndNothingIsMarkedMissing() = runBlocking {
        val (id, r) = refreshPair("shrink", favouriteAll = false)
        assertEquals("shrink", r.error!!.code)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE playlist_id = ? AND missing_since IS NOT NULL", id))
    }
}
