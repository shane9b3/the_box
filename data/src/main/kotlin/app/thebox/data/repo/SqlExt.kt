package app.thebox.data.repo

import androidx.room.PooledConnection
import androidx.room.TransactionScope
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.data.db.LibraryDatabase

/** One IMMEDIATE write transaction on Room's writer connection (refreshes the InvalidationTracker). */
internal suspend fun <R> LibraryDatabase.writeTransaction(block: suspend TransactionScope<R>.() -> R): R =
    useWriterConnection { it.immediateTransaction(block) }

internal suspend fun <R> LibraryDatabase.read(block: suspend PooledConnection.() -> R): R =
    useReaderConnection { block(it) }

/** Prepares [sql] once and runs it for every item (bind, step, reset, clearBindings). */
internal suspend fun <T> PooledConnection.forEachBound(sql: String, items: Iterable<T>, bind: SQLiteStatement.(T) -> Unit): Int =
    usePrepared(sql) { st ->
        var n = 0
        for (item in items) {
            st.bind(item)
            st.step()
            st.reset()
            st.clearBindings()
            n++
        }
        n
    }

internal suspend fun PooledConnection.exec(sql: String, bind: SQLiteStatement.() -> Unit = {}) {
    usePrepared(sql) { st -> st.bind(); st.step() }
}

internal suspend fun PooledConnection.queryLong(sql: String, bind: SQLiteStatement.() -> Unit = {}): Long =
    usePrepared(sql) { st -> st.bind(); if (st.step() && !st.isNull(0)) st.getLong(0) else 0L }

internal fun SQLiteStatement.bindLongOrNull(index: Int, v: Long?) = if (v == null) bindNull(index) else bindLong(index, v)
internal fun SQLiteStatement.bindIntOrNull(index: Int, v: Int?) = if (v == null) bindNull(index) else bindLong(index, v.toLong())
internal fun SQLiteStatement.bindTextOrNull(index: Int, v: String?) = if (v == null) bindNull(index) else bindText(index, v)
internal fun SQLiteStatement.bindDoubleOrNull(index: Int, v: Double?) = if (v == null) bindNull(index) else bindDouble(index, v)
internal fun SQLiteStatement.bindBool(index: Int, v: Boolean) = bindLong(index, if (v) 1L else 0L)
internal fun SQLiteStatement.bindBoolOrNull(index: Int, v: Boolean?) = if (v == null) bindNull(index) else bindBool(index, v)
internal fun SQLiteStatement.longOrNull(col: Int): Long? = if (isNull(col)) null else getLong(col)
internal fun SQLiteStatement.textOrNull(col: Int): String? = if (isNull(col)) null else getText(col)
