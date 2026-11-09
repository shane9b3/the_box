package app.thebox.data.db

import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.CredentialEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import app.thebox.data.db.entity.PurgedKeyEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.db.entity.StreamSourceEntity
import app.thebox.data.db.entity.XtreamAccountEntity
import app.thebox.data.repo.LibraryMaintenance
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One round trip per table (§5.3 "Room (Robolectric)"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaoTest {
    private lateinit var db: LibraryDatabase

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    @Test
    fun playlist() = runBlocking {
        val id = db.newPlaylist("A") { it.copy(extraHeaders = mapOf("Referer" to "http://x")) }
        val p = db.playlistDao().get(id)!!
        assertEquals(mapOf("Referer" to "http://x"), p.extraHeaders)
        assertEquals(24, p.refreshIntervalH)
        assertEquals("AUTO", p.tokenZoneSetting)
        db.playlistDao().setNeedsReauthAll()
        db.playlistDao().setServerTime(id, "Asia/Tokyo", null, 95)
        db.playlistDao().setActiveBaseIndex(id, 2)
        val q = db.playlistDao().observe(id).first()!!
        assertTrue(q.needsReauth)
        assertEquals("Asia/Tokyo", q.serverZone)
        assertEquals(95L, q.clockSkewSec)
        assertEquals(2, q.activeBaseIndex)
        assertEquals(0, db.playlistDao().maxSortIndex())
        assertEquals(1, db.playlistDao().observeAll().first().size)
    }

    @Test
    fun credential() = runBlocking {
        val dao = db.credentialDao()
        dao.upsert(CredentialEntity("cred-1", byteArrayOf(1, 2, 3), updatedAt = 5))
        dao.upsert(CredentialEntity("cred-1", byteArrayOf(9), updatedAt = 6))
        assertArrayEquals(byteArrayOf(9), dao.get("cred-1")!!.ciphertext)
        assertEquals(1, dao.count())
        dao.deleteAll()
        assertNull(dao.get("cred-1"))
    }

    @Test
    fun xtreamAccount() = runBlocking {
        val p = db.newPlaylist()
        db.xtreamAccountDao().upsert(XtreamAccountEntity(p, "{}", "ACTIVE", null, 2, 0, 10))
        assertEquals("ACTIVE", db.xtreamAccountDao().observe(p).first()!!.status)
    }

    @Test
    fun groupAndChannelPrefs() = runBlocking {
        val p = db.newPlaylist()
        db.groupDao().upsertPrefs(GroupPrefsEntity(p, 0, "g:news", hidden = true, updatedAt = 1))
        assertTrue(db.groupDao().prefs(p, 0, "g:news")!!.hidden)
        db.channelPrefsDao().recordWatched(p, "a", 100)
        db.channelPrefsDao().recordWatched(p, "a", 200)
        val prefs = db.channelPrefsDao().get(p, "a")!!
        assertEquals(2, prefs.watchCount)
        assertEquals(200L, prefs.lastWatchedAt)
        db.newChannel(p, "a")
        assertEquals(listOf("a"), db.channelPrefsDao().recent(10).first().map { it.stableKey })
        db.channelPrefsDao().upsert(prefs.copy(customName = "Mine"))
        assertEquals("Mine", db.channelPrefsDao().observe(p, "a").first()!!.customName)
    }

    @Test
    fun favourites() = runBlocking {
        val p = db.newPlaylist()
        db.newChannel(p, "a", sortIndex = 0)
        db.newChannel(p, "b", sortIndex = 1, missingSince = 5)
        val dao = db.favouriteDao()
        val list = dao.insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 1))
        assertTrue(dao.insert(FavouriteEntity(list, p, "a", 0, 1)) != -1L)
        assertEquals(-1L, dao.insert(FavouriteEntity(list, p, "a", 5, 1)))
        dao.insert(FavouriteEntity(list, p, "b", 1, 1))
        assertEquals(2, dao.countForPlaylist(p))
        // A missing channel's favourite is kept but hidden.
        assertEquals(listOf("a"), dao.channels(list, unlocked = false).first().map { it.channel.stableKey })
        dao.delete(list, p, "a")
        assertEquals(listOf("b"), dao.observe(list).first().map { it.stableKey })
    }

    @Test
    fun epgSourceOriginIsUniqueAndActiveFilters() = runBlocking {
        val p = db.newPlaylist()
        val dao = db.epgSourceDao()
        fun src(origin: String, playlist: Long?, ordinal: Int, priority: Int) =
            EpgSourceEntity(name = "s", origin = origin, playlistId = playlist, ordinal = ordinal, credentialRef = "r", urlHash = 1, displaySource = "h", priority = priority, createdAt = 1)
        val a = dao.insert(src("PLAYLIST_HEADER", p, 0, 1))
        dao.insert(src("PLAYLIST_HEADER", p, 1, 2))
        dao.insert(src("USER", null, 0, 0))
        dao.insert(src("USER", null, 0, 3)) // USER rows (NULL playlist) never conflict
        try {
            dao.insert(src("PLAYLIST_HEADER", p, 0, 9))
            fail("duplicate (playlist, origin, ordinal) must be rejected")
        } catch (e: Exception) { /* expected */ }
        assertEquals(listOf(0, 1, 2, 3), dao.active().map { it.priority })
        db.playlistDao().get(p)!!.let { db.playlistDao().update(it.copy(usePlaylistEpg = false)) }
        assertEquals(listOf(0, 3), dao.active().map { it.priority })
        assertEquals(a, dao.byOrigin(p, "PLAYLIST_HEADER", 0)!!.id)
        dao.setImported(a, "e", "lm", "/f", 10, 99, "SUCCESS", 5, 50)
        assertEquals(50, dao.get(a)!!.programmeCount)
    }

    @Test
    fun activeLeavesOutRestorePlaceholdersOnly() = runBlocking {
        // docs/design/m5-restore-placeholder-guides.md §3: placeholder = origin <> 'USER' AND url_hash = 0.
        val p = db.newPlaylist()
        val x = db.newPlaylist("X", type = app.thebox.core.source.PlaylistType.XTREAM)
        val dao = db.epgSourceDao()
        fun src(origin: String, playlist: Long?, ordinal: Int, hash: Long, priority: Int) =
            EpgSourceEntity(name = "s$priority", origin = origin, playlistId = playlist, ordinal = ordinal, credentialRef = "r$priority", urlHash = hash, displaySource = "h", priority = priority, createdAt = 1)
        dao.insert(src("PLAYLIST_HEADER", p, 0, 42, 0)) // real header guide at ordinal 0
        val ph = dao.insert(src("PLAYLIST_HEADER", p, 1, 0, 1)) // placeholder at ordinal 1, next to it
        dao.insert(src("XTREAM", x, 0, 0, 2)) // Xtream placeholder
        dao.insert(src("USER", null, 0, 0, 3)) // a USER guide whose hash happens to be 0 is never a placeholder
        assertEquals(listOf(0, 3), dao.active().map { it.priority })
        assertEquals(listOf(false, true, true, false), dao.all().map { it.isPlaceholder })
        assertEquals(4, dao.all().size)
        assertTrue(dao.get(ph)!!.isPlaceholder)
    }

    @Test
    fun mappingAndMatch() = runBlocking {
        val p = db.newPlaylist()
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, "a", s, "x", 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, "a", null, null, 2))
        assertNull(db.epgMappingDao().get(p, "a")!!.sourceId)
        db.epgMatchDao().insertAll(
            listOf(
                EpgMatchEntity(p, "a", s, "x", 0, "ID_EXACT", 1f, inputsHash = 7, matchedAt = 1),
                EpgMatchEntity(p, "a", s, "y", 1, "FUZZY", 0.8f, inputsHash = 7, matchedAt = 1),
            ),
        )
        assertEquals(listOf("x", "y"), db.epgMatchDao().forSubject(p, "a").map { it.xmltvId })
        assertEquals(7L, db.epgMatchDao().subjectHashes(p).single().inputsHash)
        assertEquals(1, db.epgMatchDao().autosForSource(s).size)
    }

    @Test
    fun reminders() = runBlocking {
        val p = db.newPlaylist()
        val dao = db.reminderDao()
        val r = ReminderEntity(playlistId = p, stableKey = "a", startMin = 1000, endMin = 1060, titleHash = 1, title = "News", channelName = "BBC", createdAt = 1)
        assertTrue(dao.insert(r) > 0)
        assertEquals(-1L, dao.insert(r.copy(title = "dup")))
        assertEquals(0, dao.due(998).size)
        assertEquals(1, dao.due(999).size) // 60 s lead
        assertEquals(1, dao.observePending(0).first().size)
        dao.setState(dao.all().single().id, ReminderEntity.STATE_FIRED)
        assertEquals(0, dao.observePending(0).first().size)
    }

    @Test
    fun refreshLogKeepsNewest30PerTarget() = runBlocking {
        val dao = db.refreshLogDao()
        repeat(35) { i -> dao.insert(RefreshLogEntity(targetType = "PLAYLIST", targetId = 1, trigger = "MANUAL", startedAt = i.toLong(), finishedAt = null, outcome = if (i == 34) "FAILED" else "SUCCESS")) }
        repeat(3) { i -> dao.insert(RefreshLogEntity(targetType = "EPG_BUILD", targetId = null, trigger = "PERIODIC", startedAt = i.toLong(), finishedAt = null, outcome = "SUCCESS")) }
        assertEquals(30, dao.count("PLAYLIST", 1))
        assertEquals(3, dao.count("EPG_BUILD", null))
        assertEquals(34L, dao.latest("PLAYLIST", 1).single().startedAt)
        assertEquals(5L, db.scalar("SELECT MIN(started_at) FROM refresh_log WHERE target_type = 'PLAYLIST'"))
        val failures = dao.observeUnacknowledgedFailures().first()
        assertEquals(1, failures.size)
        dao.acknowledge(failures.single().id)
        assertEquals(0, dao.observeUnacknowledgedFailures().first().size)
    }

    @Test
    fun aSuccessAcknowledgesEarlierFailuresOfTheSameTargetOnly() = runBlocking {
        // Owner finding 2026-11-02: http.500 build failures stayed on screen for days after successful builds.
        val dao = db.refreshLogDao()
        fun row(type: String, target: Long?, at: Long, outcome: String) =
            RefreshLogEntity(targetType = type, targetId = target, trigger = "PERIODIC", startedAt = at, finishedAt = at, outcome = outcome, errorCode = if (outcome == "FAILED") "http.500" else null)
        suspend fun unacked() = dao.observeUnacknowledgedFailures().first().map { it.targetType to it.targetId }.sortedBy { it.toString() }

        repeat(3) { i -> dao.insert(row("EPG_BUILD", null, 100L + i, "FAILED")) }
        dao.insert(row("EPG_PATCH", null, 103, "FAILED"))
        dao.insert(row("PLAYLIST", 1, 100, "FAILED"))
        // started_at earlier than the failures (the clock moved back): the insertion order still resolves them.
        // A build success also resolves the earlier patch failure (a rebuild supersedes any patch).
        dao.insert(row("EPG_BUILD", null, 50, "SUCCESS"))
        // target_id IS NULL matched only the guide rows; the playlist failure is untouched.
        assertEquals(listOf("PLAYLIST" to 1L), unacked())

        // A failure after the success stays until the next success or the viewer's Dismiss.
        dao.insert(row("EPG_BUILD", null, 200, "FAILED"))
        assertEquals(listOf("EPG_BUILD" to null, "PLAYLIST" to 1L), unacked())
        // A patch success does not resolve a build failure.
        dao.insert(row("EPG_PATCH", null, 250, "SUCCESS"))
        assertEquals(listOf("EPG_BUILD" to null, "PLAYLIST" to 1L), unacked())

        // Another playlist's success does not resolve playlist 1, nor does a cancelled run; its own success does.
        dao.insert(row("PLAYLIST", 2, 300, "SUCCESS"))
        assertEquals(listOf("EPG_BUILD" to null, "PLAYLIST" to 1L), unacked())
        dao.insert(row("PLAYLIST", 1, 300, "CANCELLED"))
        assertEquals(listOf("EPG_BUILD" to null, "PLAYLIST" to 1L), unacked())
        // A 304 is a success for this purpose (RefreshRepository stamps last_success_at for it).
        dao.insert(row("PLAYLIST", 1, 400, "NOT_MODIFIED"))
        assertEquals(listOf("EPG_BUILD" to null), unacked())
        // Logged after that success but with an earlier started_at (clock moved back): still open, ordering is by id.
        dao.insert(row("PLAYLIST", 1, 10, "FAILED"))
        assertEquals(listOf("EPG_BUILD" to null, "PLAYLIST" to 1L), unacked())
        dao.insert(row("EPG_BUILD", null, 500, "SUCCESS"))
        assertEquals(listOf("PLAYLIST" to 1L), unacked())
    }

    @Test
    fun orphanTrimUsesTombstoneAgeNotFavouriteAge() = runBlocking {
        // S8: a 2-year-old favourite survives a purge for 180 days.
        val clock = FakeClock(start = 1_000L * 24 * 3600 * 1000)
        val p = db.newPlaylist()
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p, "old", 0, addedAt = clock.now() - 730L * 24 * 3600 * 1000))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "old", updatedAt = 0))
        db.maintenanceDao().upsertTombstone(PurgedKeyEntity(p, "old", purgedAt = clock.now()))
        db.maintenanceDao().upsertStreamSource(StreamSourceEntity(p, "old", 1, p, "other", createdAt = 0))
        val m = LibraryMaintenance(TestDb.context, db.maintenanceDao(), clock)

        clock.advance(179L * 24 * 3600 * 1000)
        assertEquals(0, m.trimOrphans())
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM favourite"))

        // A key that came back is never trimmed, whatever its tombstone age.
        db.maintenanceDao().upsertTombstone(PurgedKeyEntity(p, "back", purgedAt = 0))
        db.favouriteDao().insert(FavouriteEntity(list, p, "back", 1, 0))
        db.newChannel(p, "back")

        clock.advance(2L * 24 * 3600 * 1000)
        assertEquals(4, m.trimOrphans()) // favourite, prefs, stream_source, tombstone of "old"
        assertEquals(listOf("back"), db.favouriteDao().observe(list).first().map { it.stableKey })
        assertEquals(1, db.maintenanceDao().tombstones(p).size)
        assertTrue(m.runIfDue() >= 0)
    }
}
