package app.thebox.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.Migrations
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.repo.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-1 (§5.7): LibraryDatabase on BundledSQLiteDriver: open, FTS4 channel_fts search, `PRAGMA foreign_keys = 1`
 * on the writer and on a reader connection (S2), and `MigrationTestHelper.createDatabase(1)` from the exported JSON.
 */
@RunWith(AndroidJUnit4::class)
class I1LibraryDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun bundledDriverFtsAndForeignKeysOnEveryConnection() = runBlocking {
        context.deleteDatabase("i1.db")
        val db = LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), "i1.db")
        try {
            val p = db.playlistDao().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, credentialRef = "r", displaySource = "h", sortIndex = 0, createdAt = 1))
            db.useWriterConnection { w ->
                w.usePrepared("INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, content_hash, first_seen_at, updated_at) VALUES (?, 'k', 'URL', 0, 'Télé Matin', 'tele matin', 0, 'u', 1, 1, 1)") {
                    it.bindLong(1, p); it.step()
                }
                w.usePrepared("INSERT INTO channel_fts(rowid, name) SELECT id, name FROM channel") { it.step() }
                assertEquals(1L, w.usePrepared("PRAGMA foreign_keys") { it.step(); it.getLong(0) })
            }
            db.useReaderConnection { r -> assertEquals(1L, r.usePrepared("PRAGMA foreign_keys") { it.step(); it.getLong(0) }) }
            assertEquals(listOf("k"), db.channelDao().search(ChannelRepository.ftsQuery("tele")!!, false, 10).map { it.channel.stableKey })
        } finally {
            db.close()
            context.deleteDatabase("i1.db")
        }
    }

    @Test
    fun exportedSchemaVersion1Creates() {
        val file = context.getDatabasePath("i1-migration.db").apply { delete() }
        val helper = MigrationTestHelper(instrumentation, file, BundledSQLiteDriver(), LibraryDatabase::class, { LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), file.name) }, emptyList())
        val conn = helper.createDatabase(1)
        try {
            val n = conn.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('channel', 'purged_key', 'epg_match')").use { it.step(); it.getLong(0) }
            assertEquals(3L, n)
        } finally {
            conn.close()
            file.delete()
        }
    }

    /**
     * I-23 / day-0 check 5 (docs/design/m2-playback-core.md §2.10, §6.8): the additive v1 → v2 auto-migration on the
     * device's bundled SQLite keeps user rows and adds `playback_flags`, `fps_milli` and `xtream_output_learned`.
     */
    @Test
    fun migrate1To2() {
        val file = context.getDatabasePath("i1-migration-2.db").apply { delete() }
        val helper = MigrationTestHelper(instrumentation, file, BundledSQLiteDriver(), LibraryDatabase::class, { LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), file.name) }, emptyList())
        try {
            val v1 = helper.createDatabase(1)
            try {
                v1.prepare("INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, created_at) VALUES (7, 'P', 'XTREAM', 'r', 'h', 0, 1)").use { it.step() }
                v1.prepare("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, audio_lang, watch_count, updated_at) VALUES (7, 'xc:1', 1, 'deu', 3, 5)").use { it.step() }
            } finally {
                v1.close()
            }
            val v2 = helper.runMigrationsAndValidate(2, emptyList())
            try {
                v2.prepare("SELECT hidden, audio_lang, watch_count, playback_flags, fps_milli FROM channel_prefs").use {
                    assertEquals(true, it.step())
                    assertEquals(1L, it.getLong(0))
                    assertEquals("deu", it.getText(1))
                    assertEquals(3L, it.getLong(2))
                    assertEquals(0L, it.getLong(3))
                    assertEquals(true, it.isNull(4))
                }
                v2.prepare("SELECT xtream_output_learned FROM playlist WHERE id = 7").use {
                    assertEquals(true, it.step())
                    assertEquals(true, it.isNull(0))
                }
            } finally {
                v2.close()
            }
            val db = LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), file.name)
            try {
                runBlocking { assertEquals(0, db.channelPrefsDao().get(7, "xc:1")!!.playbackFlags) }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(file.name)
        }
    }

    /**
     * M3 (docs/design/m3-guide.md §2.9, §5.2 `LibraryV3MigrationTest`): the additive v2 → v3 auto-migration on the
     * device's bundled SQLite keeps user rows, adds `display_name`, the `name_prefix_*` columns and `reminder.shift_min`
     * with their defaults, and creates the two reverse-lookup indexes.
     */
    @Test
    fun migrate2To3() {
        val file = context.getDatabasePath("i1-migration-3.db").apply { delete() }
        val helper = MigrationTestHelper(instrumentation, file, BundledSQLiteDriver(), LibraryDatabase::class, { LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), file.name) }, emptyList())
        try {
            val v2 = helper.createDatabase(2)
            try {
                v2.prepare("INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, created_at) VALUES (7, 'P', 'XTREAM', 'r', 'h', 0, 1)").use { it.step() }
                v2.prepare("INSERT INTO channel(id, playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, content_hash, first_seen_at, updated_at) VALUES (11, 7, 'xc:1', 'XTREAM', 0, 'UK: One', 'uk one', 0, 'u', 1, 1, 1)").use { it.step() }
                v2.prepare("INSERT INTO reminder(id, playlist_id, stable_key, start_min, end_min, title_hash, title, channel_name, created_at) VALUES (3, 7, 'xc:1', 1000, 1030, 42, 'News', 'One', 2)").use { it.step() }
            } finally {
                v2.close()
            }
            val v3 = helper.runMigrationsAndValidate(3, Migrations.ALL.toList())
            try {
                v3.prepare("SELECT name, display_name FROM channel WHERE id = 11").use {
                    assertEquals(true, it.step())
                    assertEquals("UK: One", it.getText(0))
                    assertEquals(true, it.isNull(1))
                }
                v3.prepare("SELECT name_prefix_mode, name_prefix_custom, name_prefix_ver FROM playlist WHERE id = 7").use {
                    assertEquals(true, it.step())
                    assertEquals("AUTO", it.getText(0))
                    assertEquals(true, it.isNull(1))
                    assertEquals(0L, it.getLong(2))
                }
                v3.prepare("SELECT title, lead_sec, shift_min FROM reminder WHERE id = 3").use {
                    assertEquals(true, it.step())
                    assertEquals("News", it.getText(0))
                    assertEquals(60L, it.getLong(1))
                    assertEquals(0L, it.getLong(2))
                }
                v3.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN ('index_epg_match_ref', 'index_epg_mapping_ref')").use {
                    it.step()
                    assertEquals(2L, it.getLong(0))
                }
            } finally {
                v3.close()
            }
            val db = LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), file.name)
            try {
                runBlocking { assertEquals(0, db.reminderDao().all().single().shiftMin) }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(file.name)
        }
    }
}
