package app.thebox.data.backup

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupRecord
import app.thebox.core.backup.BackupWriter
import app.thebox.core.backup.MatchTarget
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.Domains
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SetupState
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.source.PlaylistType
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import app.thebox.data.db.entity.HideRuleEntity
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeCredentialStore
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/** docs/design/m5-setup-settings.md §3.10.4 against a real library.db (Robolectric), §6.1 WP-D `RestoreMergeTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreMergeTest {
    private val guide = SettingsDomain(Domains.GUIDE, GuidePrefs.serializer(), GuidePrefs())
    private val clock = FakeClock(1_800_000_000_000L)
    private val nowMin = (clock.now() / 60_000).toInt()
    private lateinit var dir: File
    private val dbs = ArrayList<LibraryDatabase>()

    private class Graph(val db: LibraryDatabase, val service: BackupServiceImpl, val creds: FakeCredentialStore, val settings: FakeSettingsStore, val follow: RecordingFollowUp)

    class RecordingFollowUp : RestoreFollowUp {
        val calls = ArrayList<Pair<List<Long>, List<Long>>>()
        override suspend fun afterRestore(newOrRelogin: List<Long>, affected: List<Long>) { calls += newOrRelogin to affected }
    }

    class MapBlobs : SecureBlobStore {
        val blobs = HashMap<String, ByteArray>()
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> { blobs[ref] = bytes.copyOf(); return Outcome.Ok(Unit) }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> = blobs[ref]?.copyOf()?.let { Outcome.Ok(it) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        override suspend fun deleteBlob(ref: String) { blobs.remove(ref) }
    }

    /** Settings store that fails inside the restore transaction (rollback test). */
    private class FailingImportStore(val inner: FakeSettingsStore) : SettingsStore by inner {
        override suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean): Unit = throw IllegalStateException("disk full")
    }

    private fun graph(failImport: Boolean = false): Graph {
        val settings = FakeSettingsStore()
        val db = TestDb.create().also { dbs += it }
        settings.register(guide)
        settings.register(SetupDomain)
        val creds = FakeCredentialStore()
        val follow = RecordingFollowUp()
        val storage = object : BackupStorage {
            override fun downloadsWritable() = false
            override suspend fun write(name: String, body: suspend (OutputStream) -> Unit) = error("unused")
            override fun list() = BackupListing(emptyList(), false)
            override fun copyTo(location: String, dest: File) = false
            override fun delete(location: String) = Unit
        }
        val env = object : BackupEnvironment { override val versionName = "1.0-test"; override val versionCode = 7L }
        val store: SettingsStore = if (failImport) FailingImportStore(settings) else settings
        val service = BackupServiceImpl(db, creds, MapBlobs(), store, ImportMutex(), storage, env, follow, clock, Dispatchers.IO, File(dir, "state-${dbs.size}"), 1_000)
        return Graph(db, service, creds, settings, follow)
    }

    @Before fun setUp() { dir = Files.createTempDirectory("restore").toFile() }
    @After fun tearDown() { dbs.forEach { it.close() }; dir.deleteRecursively() }

    /** Two playlists with every kind of user data, learned columns set, one past and one fired reminder. */
    private suspend fun seed(g: Graph): Pair<Long, Long> {
        val db = g.db
        val a = db.newPlaylist("Provider A", type = PlaylistType.XTREAM, ref = "cred-a") { it.copy(uid = "uid-a", userAgent = "VLC/3", displaySource = "panel.a:8080", catchupCorrectionH = 2.0, etag = "e1") }
        val b = db.newPlaylist("List B", type = PlaylistType.M3U_URL, ref = "cred-b") { it.copy(uid = "uid-b", displaySource = "b.example/list.m3u", sortIndex = 1) }
        g.creds.put("cred-a", PlaylistSecret(listOf("http://panel.a:8080", "http://backup.a"), "ann", "p+w"))
        g.creds.put("cred-b", PlaylistSecret(listOf("http://b.example/list.m3u?token=1")))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "k1", hidden = true, customName = "My One", watchCount = 3, updatedAt = 1, playbackFlags = 5, fpsMilli = 50_000))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "k-learned-only", updatedAt = 1, playbackFlags = 1))
        db.groupDao().upsertPrefs(GroupPrefsEntity(a, 0, "g:news", hidden = true, locked = null, customSortIndex = 2, customName = "Nyheter", updatedAt = 1))
        db.groupDao().upsertPrefs(GroupPrefsEntity(b, 0, "g:films", hidden = false, locked = true, updatedAt = 1))
        val l1 = db.favouriteDao().insertList(FavouriteListEntity(name = "Favourites", sortIndex = 0, createdAt = 1))
        val l2 = db.favouriteDao().insertList(FavouriteListEntity(name = "Kids", sortIndex = 1, createdAt = 1))
        db.favouriteDao().insert(FavouriteEntity(l1, a, "k1", 0, 10))
        db.favouriteDao().insert(FavouriteEntity(l1, b, "m3", 1, 11))
        db.favouriteDao().insert(FavouriteEntity(l2, b, "m4", 0, 12))
        db.reminderDao().insert(ReminderEntity(playlistId = a, stableKey = "k1", startMin = nowMin + 60, endMin = nowMin + 90, titleHash = 9, title = "Future", channelName = "One", createdAt = 1))
        db.reminderDao().insert(ReminderEntity(playlistId = a, stableKey = "k1", startMin = nowMin - 60, endMin = nowMin - 30, titleHash = 8, title = "Past", channelName = "One", createdAt = 1))
        db.reminderDao().insert(ReminderEntity(playlistId = a, stableKey = "k2", startMin = nowMin + 120, endMin = nowMin + 150, titleHash = 7, title = "Fired", channelName = "Two", state = 1, createdAt = 1))
        val src = db.epgSourceDao().insert(EpgSourceEntity(name = "XT", origin = EpgSourceEntity.ORIGIN_XTREAM, playlistId = a, credentialRef = "cred-a", urlHash = 42, displaySource = "panel.a", priority = 0, createdAt = 1))
        db.epgMappingDao().upsert(EpgMappingEntity(a, "k1", src, "one.uk", 1))
        db.epgMappingDao().upsert(EpgMappingEntity(a, "k9", null, null, 1)) // explicit "no guide data"
        db.hideRuleDao().insert(HideRuleEntity(playlistId = b, target = 0, pattern = "XXX", isRegex = false, createdAt = 1))
        g.settings.update(guide) { it.copy(clock = ClockMode.H24, showLogos = false) }
        return a to b
    }

    private fun export(g: Graph, pass: String?): File = runBlocking {
        val f = File(dir, "b-${System.nanoTime()}.thebox")
        f.outputStream().use { assertTrue(g.service.export(it, pass?.toCharArray()) is Outcome.Ok) }
        f
    }

    /** The user tables as comparable text (no row ids, no learned columns, no updated_at). */
    private suspend fun userTables(db: LibraryDatabase): Map<String, List<String>> = mapOf(
        "playlist" to db.strings("SELECT uid || '|' || name || '|' || type || '|' || display_source || '|' || sort_index || '|' || enabled || '|' || COALESCE(user_agent, '') FROM playlist ORDER BY uid"),
        "channel_prefs" to db.strings(
            "SELECT p.uid || '|' || stable_key || '|' || hidden || '|' || COALESCE(custom_name, '') || '|' || watch_count FROM channel_prefs cp JOIN playlist p ON p.id = cp.playlist_id " +
                "WHERE hidden = 1 OR custom_name IS NOT NULL OR watch_count > 0 ORDER BY 1",
        ),
        "group_prefs" to db.strings("SELECT p.uid || '|' || kind || '|' || group_key || '|' || hidden || '|' || COALESCE(locked, 'auto') || '|' || COALESCE(custom_sort_index, '') || '|' || COALESCE(custom_name, '') FROM group_prefs g JOIN playlist p ON p.id = g.playlist_id ORDER BY 1"),
        "favourite" to db.strings("SELECT l.name || '|' || p.uid || '|' || f.stable_key || '|' || f.sort_index FROM favourite f JOIN favourite_list l ON l.id = f.list_id JOIN playlist p ON p.id = f.playlist_id ORDER BY 1"),
        "reminder" to db.strings("SELECT p.uid || '|' || stable_key || '|' || title || '|' || state FROM reminder r JOIN playlist p ON p.id = r.playlist_id WHERE state = 0 AND start_min > $nowMin ORDER BY 1"),
        "epg_mapping" to db.strings("SELECT p.uid || '|' || stable_key || '|' || COALESCE(xmltv_id, '-') || '|' || (source_id IS NULL) FROM epg_mapping m JOIN playlist p ON p.id = m.playlist_id ORDER BY 1"),
        "hide_rule" to db.strings("SELECT p.uid || '|' || target || '|' || pattern || '|' || is_regex || '|' || h.enabled FROM hide_rule h JOIN playlist p ON p.id = h.playlist_id ORDER BY 1"),
    )

    /** A playlist-derived EPG source exists only after a refresh; the restore target gets one so mappings can land. */
    private suspend fun addXtreamSource(db: LibraryDatabase, uid: String) {
        val pid = db.scalar("SELECT id FROM playlist WHERE uid = ?", uid)
        db.epgSourceDao().insert(EpgSourceEntity(name = "XT", origin = EpgSourceEntity.ORIGIN_XTREAM, playlistId = pid, credentialRef = "x", urlHash = 42, displaySource = "panel.a", priority = 0, createdAt = 1))
    }

    @Test
    fun exportThenRestoreMergeIntoAnEmptyLibraryGivesTheSameUserTables() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, "correct horse")
        val dst = graph()
        val result = dst.service.restore(file, "correct horse".toCharArray(), RestoreMode.MERGE) as RestoreResult.Done
        assertEquals(2, result.playlistsAdded)
        assertEquals(2, result.loginsRestored)
        assertEquals(0, result.needsLogin)
        // Mappings to a playlist-derived source wait for its source: the second restore (after a "refresh") lands them.
        addXtreamSource(dst.db, "uid-a")
        dst.service.restore(file, "correct horse".toCharArray(), RestoreMode.MERGE)
        assertEquals(userTables(src.db), userTables(dst.db))
        // Logins decrypt and are stored under new refs.
        val refA = dst.db.strings("SELECT credential_ref FROM playlist WHERE uid = 'uid-a'").single()
        assertEquals(PlaylistSecret(listOf("http://panel.a:8080", "http://backup.a"), "ann", "p+w"), (dst.creds.get(refA) as Outcome.Ok).value)
        assertEquals(0L, dst.db.scalar("SELECT needs_reauth FROM playlist WHERE uid = 'uid-a'"))
        // Learned columns never travel.
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE playback_flags <> 0 OR fps_milli IS NOT NULL"))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE stable_key = 'k-learned-only'"))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM playlist WHERE catchup_correction_h IS NOT NULL OR etag IS NOT NULL"))
        assertEquals("VLC/3", dst.db.strings("SELECT user_agent FROM playlist WHERE uid = 'uid-a'").single())
        // Past and fired reminders stay home.
        assertEquals(listOf("Future"), dst.db.strings("SELECT title FROM reminder"))
        // Settings documents, setup completed, follow-up for the new playlists.
        assertEquals(ClockMode.H24, dst.settings.flow(guide).value.clock)
        assertFalse(dst.settings.flow(guide).value.showLogos)
        assertEquals(SetupState(completed = true), dst.settings.flow(SetupDomain).value)
        assertEquals(2, dst.follow.calls.first().first.size)
    }

    @Test
    fun aSecondRestoreOfTheSameFileChangesNothing() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, null)
        val dst = graph()
        dst.service.restore(file, null, RestoreMode.MERGE)
        val first = userTables(dst.db)
        val counts = dst.db.scalar("SELECT COUNT(*) FROM playlist") to dst.db.scalar("SELECT COUNT(*) FROM favourite_list")
        val again = dst.service.restore(file, null, RestoreMode.MERGE) as RestoreResult.Done
        assertEquals(0, again.playlistsAdded)
        assertEquals(2, again.playlistsUpdated)
        assertEquals(first, userTables(dst.db))
        assertEquals(counts, dst.db.scalar("SELECT COUNT(*) FROM playlist") to dst.db.scalar("SELECT COUNT(*) FROM favourite_list"))
        // Previews now match by uid.
        val preview = (dst.service.preview(file) as Outcome.Ok).value
        assertEquals(listOf(MatchTarget.SAME_UID, MatchTarget.SAME_UID), preview.playlists.map { it.target })
    }

    @Test
    fun withoutLoginsNewPlaylistsAskForThem() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, null)
        val dst = graph()
        val r = dst.service.restore(file, null, RestoreMode.MERGE) as RestoreResult.Done
        assertEquals(2, r.needsLogin)
        assertEquals(0, r.loginsRestored)
        assertEquals(2L, dst.db.scalar("SELECT COUNT(*) FROM playlist WHERE needs_reauth = 1"))
        assertTrue(dst.creds.secrets.isEmpty())
        assertFalse((dst.service.preview(file) as Outcome.Ok).value.needsPassphrase)
    }

    @Test
    fun wrongPassphraseFailsBeforeAnythingIsWritten() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, "correct horse")
        val dst = graph()
        assertTrue((dst.service.preview(file) as Outcome.Ok).value.needsPassphrase)
        assertEquals(RestoreResult.Failed(RestoreFailure.WRONG_PASSPHRASE), dst.service.restore(file, "wrong horse".toCharArray(), RestoreMode.MERGE))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM playlist"))
        assertTrue(dst.creds.secrets.isEmpty())
        assertTrue(dst.settings.imports.isEmpty())
        assertEquals(SetupState(), dst.settings.flow(SetupDomain).value)
    }

    @Test
    fun mergeUpdatesTheSameSourceAndAdoptsTheBackupUid() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, null)
        val dst = graph()
        val local = dst.db.newPlaylist("Old name", type = PlaylistType.XTREAM) { it.copy(uid = "local-uid", displaySource = "panel.a:8080") }
        dst.db.channelPrefsDao().upsert(ChannelPrefsEntity(local, "k1", customNumber = 7, updatedAt = 1))
        val preview = (dst.service.preview(file) as Outcome.Ok).value
        assertEquals(MatchTarget.SAME_SOURCE, preview.playlists.first { it.uid == "uid-a" }.target)
        assertEquals(local, preview.playlists.first { it.uid == "uid-a" }.existingId)
        dst.service.restore(file, null, RestoreMode.MERGE)
        assertEquals(listOf("uid-a|Provider A"), dst.db.strings("SELECT uid || '|' || name FROM playlist WHERE id = $local"))
        // Field by field: the backup's columns win, the local custom number survives.
        assertEquals(listOf("My One|7|1"), dst.db.strings("SELECT custom_name || '|' || custom_number || '|' || hidden FROM channel_prefs WHERE playlist_id = $local AND stable_key = 'k1'"))
    }

    @Test
    fun favouriteListsMergeByName() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, null)
        val dst = graph()
        val list = dst.db.favouriteDao().insertList(FavouriteListEntity(name = "Kids", sortIndex = 5, createdAt = 1))
        dst.service.restore(file, null, RestoreMode.MERGE)
        assertEquals(listOf("Favourites", "Kids"), dst.db.strings("SELECT name FROM favourite_list ORDER BY sort_index"))
        assertEquals(1L, dst.db.scalar("SELECT COUNT(*) FROM favourite WHERE list_id = ?", list))
    }

    @Test
    fun replaceMakesListsMatchAndKeepsPlaylistsAbsentFromTheBackup() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, null)
        val dst = graph()
        dst.service.restore(file, null, RestoreMode.MERGE)
        val other = dst.db.newPlaylist("Added later") { it.copy(uid = "uid-z") }
        val extra = dst.db.favouriteDao().insertList(FavouriteListEntity(name = "Extra", sortIndex = 9, createdAt = 1))
        dst.db.favouriteDao().insert(FavouriteEntity(extra, other, "z1", 0, 1))
        val a = dst.db.scalar("SELECT id FROM playlist WHERE uid = 'uid-a'")
        dst.db.channelPrefsDao().upsert(ChannelPrefsEntity(a, "k5", hidden = true, updatedAt = 1, playbackFlags = 3))
        dst.db.hideRuleDao().insert(HideRuleEntity(playlistId = a, target = 1, pattern = "local", isRegex = false, createdAt = 1))

        dst.service.restore(file, null, RestoreMode.REPLACE)
        assertEquals(listOf("Favourites", "Kids"), dst.db.strings("SELECT name FROM favourite_list ORDER BY sort_index"))
        assertEquals(1L, dst.db.scalar("SELECT COUNT(*) FROM playlist WHERE uid = 'uid-z'"))
        // The user's choices are replaced; learned columns survive.
        assertEquals(listOf("0|3"), dst.db.strings("SELECT hidden || '|' || playback_flags FROM channel_prefs WHERE playlist_id = ? AND stable_key = 'k5'", a))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM hide_rule WHERE pattern = 'local'"))
        assertEquals(true, dst.settings.imports.last().second)
    }

    @Test
    fun recordsForAnUnknownPlaylistUidAreSkipped() = runBlocking {
        val f = File(dir, "crafted.thebox")
        f.outputStream().use { out ->
            val w = BackupWriter(out)
            w.writeHeader(BackupHeader(createdAtMs = 1, appVersionName = "x", appVersionCode = 1, includesLogins = false, counts = emptyMap()))
            w.write(BackupRecord.Playlist("uid-p", "P", "M3U_URL", "p", 0, true, emptyMap<String, JsonElement>(), null))
            w.write(BackupRecord.ChannelPref("uid-p", "k1", mapOf("hidden" to JsonPrimitive(true), "playback_flags" to JsonPrimitive(9))))
            w.write(BackupRecord.ChannelPref("uid-ghost", "k1", mapOf("hidden" to JsonPrimitive(true))))
            w.write(BackupRecord.Settings("guide", JsonObject(mapOf("clock" to JsonPrimitive("H12")))))
            w.finish()
        }
        val dst = graph()
        val r = dst.service.restore(f, null, RestoreMode.MERGE) as RestoreResult.Done
        assertEquals(1, r.rows)
        assertEquals(1L, dst.db.scalar("SELECT COUNT(*) FROM channel_prefs"))
        // A column outside the whitelist in a file is never written.
        assertEquals(0L, dst.db.scalar("SELECT playback_flags FROM channel_prefs"))
        assertEquals(ClockMode.H12, dst.settings.flow(guide).value.clock)
    }

    @Test
    fun aFailureInsideTheTransactionRollsEverythingBack() = runBlocking {
        val src = graph()
        seed(src)
        val file = export(src, "correct horse")
        val dst = graph(failImport = true)
        assertEquals(RestoreResult.Failed(RestoreFailure.IO), dst.service.restore(file, "correct horse".toCharArray(), RestoreMode.MERGE))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM playlist"))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM favourite_list"))
        assertTrue("logins written for this restore are deleted again", dst.creds.secrets.isEmpty())
        assertEquals(SetupState(), dst.settings.flow(SetupDomain).value)
    }

    @Test
    fun notABackupAndTruncatedFilesAreRefused() = runBlocking {
        val dst = graph()
        val m3u = File(dir, "x.thebox").apply { writeText("#EXTM3U\n") }
        assertEquals(RestoreResult.Failed(RestoreFailure.NOT_A_BACKUP), dst.service.restore(m3u, null, RestoreMode.MERGE))
        val src = graph()
        seed(src)
        val good = export(src, null).readText()
        val cut = File(dir, "cut.thebox").apply { writeText(good.substringBeforeLast("{\"t\":\"end\"")) }
        assertEquals(RestoreResult.Failed(RestoreFailure.TRUNCATED), dst.service.restore(cut, null, RestoreMode.MERGE))
        assertEquals(0L, dst.db.scalar("SELECT COUNT(*) FROM playlist"))
    }

    @Test
    fun exportIsLineByLineWithTheHeaderFirstAndSecretsSealed() = runBlocking {
        val src = graph()
        seed(src)
        val out = ByteArrayOutputStream()
        src.service.export(out, "correct horse".toCharArray())
        val lines = out.toString(Charsets.UTF_8.name()).trimEnd('\n').split('\n')
        assertTrue(lines.first().startsWith("{\"format\":\"thebox-backup\""))
        val types = lines.drop(1).map { it.substringAfter("{\"t\":\"").substringBefore('"') }
        assertEquals(
            listOf("settings", "playlist", "epg_source", "secrets", "group_pref", "channel_pref", "fav_list", "fav", "reminder", "epg_mapping", "hide_rule", "end"),
            types.distinct(),
        )
        assertFalse("no password in clear", lines.any { "p+w" in it || "\"ann\"" in it })
        assertNull(lines.firstOrNull { "playback_flags" in it })
    }
}
