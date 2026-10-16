package app.thebox.data.db

import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.3 WP-D "Room": the schema matches §2.1.2, foreign keys cascade, and no column references an epg.db row id. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDatabaseTest {
    private lateinit var db: LibraryDatabase

    @Before fun setUp() { db = TestDb.create() }
    @After fun tearDown() { db.close() }

    @Test
    fun schemaMatchesDesign() = runBlocking {
        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'channel_fts_%' ORDER BY name")
        assertEquals(
            listOf(
                // v4 (docs/design/m5-setup-settings.md §4.2): app_setting, hide_rule
                "app_setting", "channel", "channel_fts", "channel_group", "channel_prefs", "credential", "epg_mapping", "epg_match", "epg_source",
                "favourite", "favourite_list", "group_prefs", "hide_rule", "playlist", "purged_key", "refresh_log", "reminder", "stream_source",
                "xtream_account",
            ),
            tables,
        )
        val indexes = db.strings("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%' ORDER BY name")
        assertEquals(
            listOf(
                "index_channel_browse", "index_channel_group", "index_channel_group_identity", "index_channel_identity", "index_channel_missing",
                "index_channel_number", "index_channel_prefs_recent", "index_channel_tvg", "index_epg_mapping_ref", "index_epg_mapping_source", "index_epg_match_ref", "index_epg_match_source",
                "index_epg_match_status", "index_epg_source_hash", "index_epg_source_origin", "index_epg_source_playlist",
                "index_favourite_channel", "index_hide_rule_playlist", "index_playlist_uid", "index_purged_key_age", "index_refresh_log_target", "index_reminder_due", "index_reminder_identity",
                "index_stream_source_source",
            ),
            indexes,
        )
        val fts = db.strings("SELECT sql FROM sqlite_master WHERE name = 'channel_fts'").single()
        assertTrue(fts, fts.contains("FTS4") && fts.contains("remove_diacritics=2"))
        // Unique (playlist_id, origin, ordinal) on epg_source (S14).
        val origin = db.strings("SELECT sql FROM sqlite_master WHERE name = 'index_epg_source_origin'").single()
        assertTrue(origin.contains("UNIQUE") && origin.contains("`ordinal`"))
        // channel has every §2.1.2 column.
        val cols = db.strings("SELECT name FROM pragma_table_info('channel')")
        for (c in listOf("stable_key", "key_rule", "norm_name", "catchup_source", "tv_archive_days", "extras_json", "content_hash", "missing_count", "first_seen_at")) {
            assertTrue(c, c in cols)
        }
    }

    @Test
    fun foreignKeysOnWriterAndReader() = runBlocking {
        assertEquals(1L, db.scalar("PRAGMA foreign_keys"))
        db.read { assertEquals(1L, usePrepared("PRAGMA foreign_keys") { it.step(); it.getLong(0) }) }
        assertEquals("wal", db.strings("PRAGMA journal_mode").single().lowercase())
    }

    @Test
    fun noEpgRowIdColumns() = runBlocking {
        // §2.6: EPG row ids are generation-local and must never be persisted in library.db.
        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
        for (t in tables) {
            for (c in db.strings("SELECT name FROM pragma_table_info('$t')")) {
                assertTrue("$t.$c", !c.contains("epg_channel_id") && c != "text_id")
            }
        }
    }

    @Test
    fun deletingAPlaylistCascades() = runBlocking {
        val p = db.newPlaylist()
        val g = db.newGroup(p, "g:news")
        db.newChannel(p, "a", groupId = g)
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 1))
        db.favouriteDao().insert(FavouriteEntity(list, p, "a", 0, 1))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "a", updatedAt = 1))
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "s", origin = "PLAYLIST_HEADER", playlistId = p, credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, "a", s, "x", 1))
        db.epgMatchDao().insertAll(listOf(EpgMatchEntity(p, "a", s, "x", 0, "ID_EXACT", 1f, inputsHash = 1, matchedAt = 1)))

        db.playlistDao().delete(p)
        for (t in listOf("channel", "channel_group", "favourite", "channel_prefs", "epg_source", "epg_mapping", "epg_match")) {
            assertEquals(t, 0L, db.scalar("SELECT COUNT(*) FROM $t"))
        }
        assertEquals("favourite lists are global", 1L, db.scalar("SELECT COUNT(*) FROM favourite_list"))
    }

    @Test
    fun deletingAGroupNullsChannelsAndASourceDropsItsMappings() = runBlocking {
        val p = db.newPlaylist()
        val g = db.newGroup(p, "g:news")
        val c = db.newChannel(p, "a", groupId = g)
        db.writeGroupDelete(g)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE id = ? AND group_id IS NOT NULL", c))
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, "a", s, "x", 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, "b", null, null, 1))
        db.epgSourceDao().delete(s)
        assertEquals(listOf("b"), db.strings("SELECT stable_key FROM epg_mapping"))
    }

    private suspend fun LibraryDatabase.writeGroupDelete(id: Long) =
        writeTransaction { usePrepared("DELETE FROM channel_group WHERE id = ?") { it.bindLong(1, id); it.step() } }
}
