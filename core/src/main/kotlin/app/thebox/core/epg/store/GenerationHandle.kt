package app.thebox.core.epg.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.epg.EpgGenerationInfo
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One read-only connection with its own lock and prepared-statement cache (§2.5.3). Opened lazily.
 * Callers must leave every statement reset when [use] returns, so no read transaction stays open.
 */
internal class ReaderConnection(private val opener: () -> SQLiteConnection) {
    private val lock = ReentrantLock()
    private var conn: SQLiteConnection? = null
    private var cache: StatementCache? = null
    private var closed = false

    inner class Scope {
        val connection: SQLiteConnection get() = conn!!
        fun statement(sql: String): SQLiteStatement = cache!![sql]
    }

    private val scope = Scope()

    fun <T> use(block: (Scope) -> T): T = lock.withLock {
        check(!closed) { "generation closed" }
        if (conn == null) {
            val c = opener()
            conn = c
            cache = StatementCache(c)
        }
        block(scope)
    }

    fun close() = lock.withLock {
        closed = true
        cache?.closeAll()
        cache = null
        try { conn?.close() } catch (_: Throwable) { }
        conn = null
    }
}

/**
 * Ref-counted handle on one generation file (§2.5.3). The count starts at 1, the "published"
 * reference that [releaseBase] drops when another generation replaces it. Each [EpgReaderImpl] holds
 * one more. At zero the connections close and [onClosed] lets the store delete the file when it is no
 * longer current.
 */
internal class GenerationHandle(
    val gen: Long,
    val file: File,
    opener: () -> SQLiteConnection,
    @Volatile var info: EpgGenerationInfo,
    private val onClosed: (GenerationHandle) -> Unit,
) {
    private val refs = AtomicInteger(1)

    /** Set when another generation replaced this one (or on clear): the file is deleted at the last release. */
    @Volatile var retired = false

    /** window / at / detail / resolve (the guide thread). */
    val guide = ReaderConnection(opener)

    /** search / forEachChannel / findChannels. */
    val aux = ReaderConnection(opener)

    val fts: FtsKind get() = FtsKind.of(info.fts)

    val isOpen: Boolean get() = refs.get() > 0

    fun tryRetain(): Boolean {
        while (true) {
            val r = refs.get()
            if (r == 0) return false
            if (refs.compareAndSet(r, r + 1)) return true
        }
    }

    fun release() {
        val r = refs.decrementAndGet()
        if (r == 0) {
            guide.close()
            aux.close()
            onClosed(this)
        }
        check(r >= 0) { "generation $gen released too often" }
    }

    fun releaseBase() = release()
}
