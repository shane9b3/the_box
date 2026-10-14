package app.thebox.core.guide.feed

/**
 * `SQLITE_BUSY` detection (docs/design/m3-guide.md §3.2.3, review S7; M1 code review Later 8). `androidx.sqlite`
 * formats errors as `"Error code: $errorCode, message: $errorMsg"` (sqlite-jvm 2.6.2 `SQLite.kt`), on the prepare path
 * and on the native step path of `sqlite-bundled` alike (verified by `SqliteBusyTest.realBusyOnStep`). The primary code
 * is the low byte, so `SQLITE_BUSY_RECOVERY` (261) and `SQLITE_BUSY_SNAPSHOT` (517) count as busy too. The framework
 * driver (`AndroidSQLiteDriver`) throws `android.database.sqlite.SQLiteDatabaseLockedException` /
 * `SQLiteBusyException` instead; those are matched by class name so `:core` needs no Android dependency.
 */
object SqliteBusy {
    private val CODE = Regex("Error code: (\\d+)")
    private const val SQLITE_BUSY = 5

    fun isBusy(t: Throwable): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 8) {
            val name = e.javaClass.simpleName
            if (name == "SQLiteDatabaseLockedException" || name == "SQLiteBusyException") return true
            val code = e.message?.let { CODE.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            if (code != null) return (code and 0xFF) == SQLITE_BUSY
            e = e.cause
            depth++
        }
        return false
    }
}
