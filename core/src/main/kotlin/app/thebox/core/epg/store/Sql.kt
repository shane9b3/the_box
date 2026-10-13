package app.thebox.core.epg.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.thebox.core.xmltv.XmltvProgramme

/** Small helpers over the KMP `androidx.sqlite` API (no Room, no cursors). */

/** Runs one statement, stepping it exactly once (AndroidSQLiteDriver's journal_mode statement throws on a second step). */
internal fun SQLiteConnection.exec(sql: String) = execSQL(sql)

internal inline fun <T> SQLiteConnection.query(sql: String, bind: SQLiteStatement.() -> Unit = {}, block: (SQLiteStatement) -> T): T =
    prepare(sql).use { st ->
        st.bind()
        block(st)
    }

internal fun SQLiteConnection.longOrNull(sql: String, bind: SQLiteStatement.() -> Unit = {}): Long? =
    query(sql, bind) { st -> if (st.step() && !st.isNull(0)) st.getLong(0) else null }

internal fun SQLiteConnection.textOrNull(sql: String, bind: SQLiteStatement.() -> Unit = {}): String? =
    query(sql, bind) { st -> if (st.step() && !st.isNull(0)) st.getText(0) else null }

internal inline fun <T> SQLiteConnection.inTransaction(immediate: Boolean = false, block: () -> T): T {
    exec(if (immediate) "BEGIN IMMEDIATE" else "BEGIN")
    try {
        val r = block()
        exec("COMMIT")
        return r
    } catch (t: Throwable) {
        try { exec("ROLLBACK") } catch (_: Throwable) { }
        throw t
    }
}

internal fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

/** Binds an Int that uses [XmltvProgramme.NONE] as "absent". */
internal fun SQLiteStatement.bindIntOrNone(index: Int, value: Int) {
    if (value == XmltvProgramme.NONE) bindNull(index) else bindLong(index, value.toLong())
}

internal fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

internal fun SQLiteStatement.intOrNull(index: Int): Int? = if (isNull(index)) null else getLong(index).toInt()

/** Steps a re-bound statement once and resets it (inserts, updates, deletes). */
internal fun SQLiteStatement.run() {
    try {
        step()
    } finally {
        reset()
    }
}

/** Prepared statements cached per connection; prepared once, re-bound per row (§4.4). */
internal class StatementCache(private val conn: SQLiteConnection) {
    private val map = HashMap<String, SQLiteStatement>()

    operator fun get(sql: String): SQLiteStatement = map.getOrPut(sql) { conn.prepare(sql) }

    fun closeAll() {
        for (st in map.values) try { st.close() } catch (_: Throwable) { }
        map.clear()
    }
}

/**
 * True for a SQLite driver error on either platform. `androidx.sqlite.SQLiteException` is a KMP `expect class`:
 * a real class in the JVM variant :core compiles against, but a typealias of `android.database.SQLException` in
 * the Android variant, so the class does not exist on a device (or under Robolectric). Naming it in a catch
 * clause makes `EpgStoreImpl` fail with `NoClassDefFoundError` there, so match by class name instead.
 */
internal fun Throwable.isSqliteException(): Boolean {
    var c: Class<*>? = javaClass
    while (c != null) {
        if (c.name == "androidx.sqlite.SQLiteException" || c.name == "android.database.SQLException") return true
        c = c.superclass
    }
    return false
}

/** Reads `meta` into a map. */
internal fun SQLiteConnection.readMeta(): Map<String, String> = query("SELECT key, value FROM meta") { st ->
    val m = HashMap<String, String>()
    while (st.step()) m[st.getText(0)] = st.getText(1)
    m
}

internal fun SQLiteConnection.putMeta(values: Map<String, String>) {
    query("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)") { st ->
        for ((k, v) in values) {
            st.bindText(1, k)
            st.bindText(2, v)
            st.step()
            st.reset()
        }
    }
}
