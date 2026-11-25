package app.thebox.data.events

import androidx.sqlite.driver.AndroidSQLiteDriver
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A private check (docs/design/m6-event-channels.md §5.2, §11.2): the production index built over a real library.
 * Skipped unless `THEBOX_LIBRARY_DB` names a local `library.db` (never committed). The file is **copied** to a temporary
 * directory first and only the copy is opened, so the v5 → v6 migration and the index's `event_zone` / `event_count`
 * writes never touch the original. Prints counts and timings only: no names, ids or URLs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventIndexLiveDbTest {
    @Test
    fun buildOverACopyOfARealLibrary() = runBlocking {
        val path = System.getenv("THEBOX_LIBRARY_DB")
        assumeTrue("set THEBOX_LIBRARY_DB to a library.db to copy", path != null && File(path).isFile)
        val dir = File(TestDb.context.cacheDir, "live-${System.nanoTime()}").apply { mkdirs() }
        val copy = File(dir, "library.db")
        File(path!!).copyTo(copy)
        try {
            val t0 = System.nanoTime()
            val db = LibraryDatabase.build(TestDb.context, Dispatchers.IO, AndroidSQLiteDriver(), copy.absolutePath)
            val version = db.strings("PRAGMA user_version").single()
            val migrateMs = (System.nanoTime() - t0) / 1_000_000
            val now = db.strings("SELECT MAX(last_refresh_at) FROM playlist").single().toLongOrNull() ?: System.currentTimeMillis()
            val idx = EventIndexImpl(db, db.playlistDao(), Dispatchers.IO, FakeClock(now))
            val t1 = System.nanoTime()
            idx.current()
            val coldMs = (System.nanoTime() - t1) / 1_000_000
            val cold = idx.lastBuild!!
            val warm = (1..5).map { idx.rebuild(); idx.lastBuild!! }
            val zones = db.strings("SELECT COALESCE(event_zone, '-') || ' n=' || event_zone_n || ' count=' || event_count FROM playlist WHERE enabled = 1")
            println(
                "LIVE index: user_version=$version (open + migration $migrateMs ms); rows=${cold.rowsRead} events=${cold.events} " +
                    "empty=${cold.empty} reparsed=${cold.reparsed}; cold ${coldMs} ms (build ${cold.ms}: read ${cold.readMs}, parse ${cold.parseMs}); " +
                    "warm ${warm.map { "${it.ms} (read ${it.readMs}, parse ${it.parseMs})" }}; playlists $zones",
            )
            db.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
