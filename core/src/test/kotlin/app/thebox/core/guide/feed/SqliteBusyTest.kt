package app.thebox.core.guide.feed

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/design/m3-guide.md §8 item 11 (owner WP-B): the `SQLITE_BUSY` message text from the **native step path** of
 * `sqlite-bundled`. A second connection reads while the first holds an EXCLUSIVE lock with `busy_timeout = 0`.
 */
class SqliteBusyTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun realBusyOnStep() {
        val path = File(tmp, "busy.db").path
        val driver = BundledSQLiteDriver()
        val writer = driver.open(path)
        val reader = driver.open(path)
        try {
            writer.execSQL("PRAGMA journal_mode = DELETE")
            writer.execSQL("CREATE TABLE t(x INTEGER)")
            writer.execSQL("INSERT INTO t VALUES (1)")
            reader.execSQL("PRAGMA busy_timeout = 0")
            // Prepared before the lock (schema already read), so the failure comes from sqlite3_step.
            val st = reader.prepare("SELECT x FROM t")
            writer.execSQL("BEGIN EXCLUSIVE")
            writer.execSQL("INSERT INTO t VALUES (2)")
            var caught: Throwable? = null
            try {
                while (st.step()) Unit
            } catch (e: Throwable) {
                caught = e
            } finally {
                st.close()
            }
            val e = assertNotNull(caught, "the read must fail while the writer holds EXCLUSIVE")
            println("SQLITE_BUSY on step: ${e.javaClass.name}: ${e.message}")
            assertTrue(SqliteBusy.isBusy(e), "isBusy must recognise: ${e.message}")
            // And on prepare (a fresh statement must read the locked schema).
            val onPrepare = runCatching { reader.prepare("SELECT x FROM t WHERE x > 0").use { it.step() } }.exceptionOrNull()
            println("SQLITE_BUSY on prepare: ${onPrepare?.javaClass?.name}: ${onPrepare?.message}")
            if (onPrepare != null) assertTrue(SqliteBusy.isBusy(onPrepare))
            writer.execSQL("ROLLBACK")
        } finally {
            reader.close()
            writer.close()
        }
    }
}
