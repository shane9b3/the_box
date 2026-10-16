package app.thebox.data.diag

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * I-M5-22 on the device (docs/design/m5-setup-settings.md §4.2, §6.3; debug harness only): builds a version-3
 * `library.db` fixture with the bundled driver from the exported v3 statements the suite passes in, seeds it, opens it
 * with the production builder (which runs `MIGRATION_3_4`), then reads the result back. Lives in `:data` because the
 * app's debug source set cannot see Room or androidx.sqlite. Nothing in the release app calls it.
 */
class MigrationCheck @Inject constructor(@ApplicationContext private val context: Context, @IoDispatcher private val io: CoroutineDispatcher) {
    data class Result(
        val version: Int,
        val playlists: Int,
        val playlistNames: List<String>,
        val uidsNonNull: Int,
        val uidsDistinct: Int,
        val channels: Int,
        val ruleHiddenNonZero: Int,
    )

    suspend fun run(v3Statements: List<String>, name: String = "library-m5-migration-test.db"): Result = withContext(io) {
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }.absolutePath
        val driver = BundledSQLiteDriver()
        driver.open(path).use { c ->
            v3Statements.forEach { c.execSQL(it) }
            c.execSQL("PRAGMA user_version = 3")
            c.execSQL("INSERT INTO playlist (name, type, credential_ref, display_source, sort_index, created_at) VALUES ('Alpha', 'XTREAM', 'ref-a', 'a.example:8080', 0, 1)")
            c.execSQL("INSERT INTO playlist (name, type, credential_ref, display_source, sort_index, created_at) VALUES ('Beta', 'M3U_URL', 'ref-b', 'b.example', 1, 2)")
            for (i in 1..3) {
                c.execSQL(
                    "INSERT INTO channel (playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, content_hash, first_seen_at, updated_at) " +
                        "VALUES (1, 'xc:$i', 'XTREAM', 0, 'Ch $i', 'ch $i', $i, 'u$i', $i, 1, 1)",
                )
            }
        }
        val db = LibraryDatabase.build(context, io, BundledSQLiteDriver(), name)
        db.openHelperFreeTouch()
        db.close()
        val r = driver.open(path).use { c ->
            Result(
                version = c.long("PRAGMA user_version").toInt(),
                playlists = c.long("SELECT COUNT(*) FROM playlist").toInt(),
                playlistNames = c.strings("SELECT name FROM playlist ORDER BY id"),
                uidsNonNull = c.long("SELECT COUNT(*) FROM playlist WHERE uid IS NOT NULL").toInt(),
                uidsDistinct = c.long("SELECT COUNT(DISTINCT uid) FROM playlist").toInt(),
                channels = c.long("SELECT COUNT(*) FROM channel").toInt(),
                ruleHiddenNonZero = c.long("SELECT COUNT(*) FROM channel WHERE rule_hidden != 0").toInt(),
            )
        }
        context.deleteDatabase(name)
        r
    }

    /** Forces Room to open the file (and so run the migration) with one trivial read. */
    private suspend fun LibraryDatabase.openHelperFreeTouch() {
        playlistDao().all()
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { st -> st.step(); st.getLong(0) }

    private fun SQLiteConnection.strings(sql: String): List<String> = prepare(sql).use { st ->
        val out = ArrayList<String>()
        while (st.step()) out += st.getText(0)
        out
    }
}
