package app.thebox.data.repo

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.data.db.dao.MaintenanceDao
import app.thebox.data.di.WallClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weekly maintenance run by PlaylistRefreshWorker (§2.1.2): removes user data of keys purged more than 180
 * days ago that never came back (S8; age = `purged_key.purged_at`).
 */
@Singleton
class LibraryMaintenance @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: MaintenanceDao,
    private val clock: WallClock,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun runIfDue(): Int {
        val now = clock.now()
        val last = prefs.getLong(KEY_LAST, 0L)
        if (now - last < WEEK_MS) return 0
        val removed = trimOrphans(now)
        prefs.edit().putLong(KEY_LAST, now).apply()
        return removed
    }

    suspend fun trimOrphans(now: Long = clock.now()): Int {
        val removed = dao.trimOrphans(now - ORPHAN_AGE_MS)
        if (removed > 0) BoxLog.i(TAG) { "orphan trim removed $removed rows" }
        return removed
    }

    companion object {
        private const val TAG = "LibraryMaintenance"
        private const val PREFS = "thebox_maintenance"
        private const val KEY_LAST = "orphan_trim_at"
        const val WEEK_MS = 7L * 24 * 3600 * 1000
        const val ORPHAN_AGE_MS = 180L * 24 * 3600 * 1000
    }
}
