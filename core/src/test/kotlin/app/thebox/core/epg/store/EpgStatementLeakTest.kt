package app.thebox.core.epg.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import app.thebox.core.epg.store.StoreFixtures.ALL
import app.thebox.core.epg.store.StoreFixtures.request
import app.thebox.core.epg.store.StoreFixtures.res
import app.thebox.core.epg.testutil.EpgTestDb
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Code review M1 S-8: every prepared statement is closed when a build fails or is cancelled. On the bundled
 * driver an unfinalized statement makes `close()` leave a zombie connection holding the deleted file.
 */
class EpgStatementLeakTest {
    @TempDir
    lateinit var tmp: File

    private val open = AtomicInteger()

    private inner class CountingStatement(private val d: SQLiteStatement) : SQLiteStatement by d {
        private var closed = false
        override fun close() {
            if (!closed) { closed = true; open.decrementAndGet() }
            d.close()
        }
    }

    private inner class CountingConnection(private val d: SQLiteConnection) : SQLiteConnection by d {
        override fun prepare(sql: String): SQLiteStatement {
            val st = d.prepare(sql)
            open.incrementAndGet()
            return CountingStatement(st)
        }
    }

    private val driver = object : SQLiteDriver {
        private val real = BundledSQLiteDriver()
        override fun open(fileName: String): SQLiteConnection = CountingConnection(real.open(fileName))
    }

    @Test
    fun failedSourceClosesEveryStatement() {
        val s = EpgTestDb.openStore(File(tmp, "epg"), driver, clock = { StoreFixtures.NOW_MS })
        val r = s.build(request(1L to res("basic.xml"), 2L to res("not_xmltv.html")), ALL)
        assertEquals(2L, r.failedSourceId)
        assertEquals(0, open.get(), "statements left open after a failed source")
    }

    @Test
    fun cancelledBuildClosesEveryStatement() {
        val s = EpgTestDb.openStore(File(tmp, "epg"), driver, clock = { StoreFixtures.NOW_MS })
        var calls = 0
        assertFailsWith<CancellationException> {
            s.build(request(1L to res("basic.xml")), ALL, ImportGate.Open, CancelCheck { ++calls > 1 })
        }
        assertEquals(0, open.get(), "statements left open after cancellation")
    }
}
