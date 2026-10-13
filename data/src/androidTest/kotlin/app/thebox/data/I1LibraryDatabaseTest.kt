package app.thebox.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
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
}
