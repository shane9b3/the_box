package app.thebox.data.guide

import androidx.room.InvalidationTracker
import app.thebox.core.catchup.PlaylistCatchupDefaults
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.repo.PlaylistRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (M3 code review 2026-10-14, O-1 row block) Per-playlist facts a row block needs (the playlist row, its catch-up
 * defaults, the header `tvg-shift`) and the active EPG sources, cached behind Room's invalidation of `playlist`,
 * `epg_source` and `xtream_account`. A 32-row block then costs two statements (the channel select and one mapping +
 * match statement) instead of about seven, and no JSON decode.
 *
 * Thread-safe: maps are concurrent; a value read before an invalidation is not stored after it ([version] check).
 */
@Singleton
class GuideMetaCache @Inject constructor(
    db: LibraryDatabase,
    private val playlists: PlaylistRepository,
    private val sourceDao: EpgSourceDao,
) {
    /** One playlist's cached facts. */
    class PlaylistFacts(val entity: PlaylistEntity, val catchup: PlaylistCatchupDefaults, val headerShiftMin: Int?)

    private val version = AtomicLong()
    private val byPlaylist = ConcurrentHashMap<Long, PlaylistFacts>()
    @Volatile private var activeSources: List<EpgSourceEntity>? = null

    /** Test/diagnostic counters. */
    @Volatile var loads: Int = 0
        private set

    private val observer = object : InvalidationTracker.Observer(TABLES) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    init {
        db.invalidationTracker.addObserver(observer)
    }

    fun invalidate() {
        version.incrementAndGet()
        byPlaylist.clear()
        activeSources = null
    }

    /** The playlist's facts, or null when it does not exist. */
    suspend fun playlist(id: Long): PlaylistFacts? {
        byPlaylist[id]?.let { return it }
        val v = version.get()
        val p = playlists.get(id) ?: return null
        loads++
        val facts = PlaylistFacts(p, playlists.catchupDefaults(p), EpgRefRule.headerShiftMin(p))
        if (version.get() == v) byPlaylist[id] = facts
        return facts
    }

    /** `EpgSourceDao.active()`: enabled sources in priority order. */
    suspend fun activeSources(): List<EpgSourceEntity> {
        activeSources?.let { return it }
        val v = version.get()
        val list = sourceDao.active()
        loads++
        if (version.get() == v) activeSources = list
        return list
    }

    companion object {
        val TABLES = arrayOf("playlist", "epg_source", "xtream_account")
    }
}
