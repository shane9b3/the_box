package app.thebox.data.guide

import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.legacyUnlocked
import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.db.dao.FavouriteDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.FavouritesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GuideLibrary] over `library.db` (docs/design/m3-guide.md §3.2.1, §3.8). Main-safe: every suspend call and every flow
 * runs on [io]. Lineups and the Groups panel re-query on Room's invalidation tracker
 * (`InvalidationTracker.createFlow(vararg tables, emitInitialState = true)`, Room 2.8.5, verified against the sources jar)
 * and emit only when the result changes.
 */
@Singleton
class GuideLibraryImpl @Inject constructor(
    private val db: LibraryDatabase,
    private val queries: GuideQueries,
    private val playlistDao: PlaylistDao,
    private val groupDao: GroupDao,
    private val favouriteDao: FavouriteDao,
    private val favourites: FavouritesRepository,
    private val prefsDao: ChannelPrefsDao,
    private val channels: ChannelRepository,
    private val clock: WallClock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GuideLibrary {

    // (M5 commit 0, S10) `access` is explicit; until WP-C's §4.3 predicate it maps onto M3's `unlocked` flag.
    override fun sources(access: ParentalAccess): Flow<List<GuideSourceEntry>> =
        trigger(SOURCE_TABLES, withRecentCount = true, emitInitial = true)
            .map { buildSources(access.legacyUnlocked) }
            .distinctUntilChanged()
            .flowOn(io)

    /** Diagnostics (tests): Groups-panel builds so far. */
    @Volatile var sourceBuilds: Int = 0
        private set

    private suspend fun buildSources(unlocked: Boolean): List<GuideSourceEntry> {
        sourceBuilds++
        val out = ArrayList<GuideSourceEntry>()
        for (l in queries.favouriteListCounts(unlocked)) {
            if (l.count > 0) out += GuideSourceEntry(GuideSource.Favourites(l.listId), l.name, l.count, SourceSection.FAVOURITES, null)
        }
        val recent = queries.recentCount(unlocked)
        if (recent > 0) out += GuideSourceEntry(GuideSource.Recent, LABEL_RECENT, recent, SourceSection.RECENT, null)
        val enabled = playlistDao.all().filter { it.enabled }.sortedWith(compareBy({ it.sortIndex }, { it.id }))
        val multi = enabled.size > 1
        for (p in enabled) {
            val header = if (multi) p.name else null
            out += GuideSourceEntry(GuideSource.All(p.id), LABEL_ALL, p.liveCount, SourceSection.ALL, header)
            for (g in groupDao.groups(p.id, 0, unlocked).first()) {
                if (g.channelCount <= 0) continue
                out += GuideSourceEntry(GuideSource.Group(p.id, g.group.groupKey), g.customName ?: g.group.name, g.channelCount, SourceSection.GROUP, header)
            }
        }
        return out
    }

    /**
     * Re-queries on every change of the lineup tables; emits only when the ordered ids change ([GuideQueries.versionOf]).
     * Recent keeps the order it had when it was selected (N2): later emissions keep surviving ids in place and put new
     * ones first, so the focused row does not jump after every tune.
     */
    override fun lineup(source: GuideSource, access: ParentalAccess): Flow<GuideLineup> {
        val unlocked = access.legacyUnlocked
        // G-S6: only Recent depends on the watch history (`last_watched_at`); every other lineup ignores tune-time writes.
        val changes = if (source == GuideSource.Recent) db.invalidationTracker.createFlow(*LINEUP_TABLES, "channel_prefs").map { }
        else trigger(LINEUP_TABLES, withRecentCount = false, emitInitial = true)
        val raw = changes.map { queries.lineup(source, unlocked) }
        val shaped = if (source != GuideSource.Recent) {
            raw.map { GuideLineup(source, GuideQueries.versionOf(source, it.ids), it.ids, it.archiveDaysUpperBound) }
        } else {
            raw.runningFold(null as GuideLineup?) { prev, rows ->
                val ids = if (prev == null) rows.ids else frozenRecent(prev.rowIds, rows.ids)
                GuideLineup(source, GuideQueries.versionOf(source, ids), ids, rows.archiveDaysUpperBound)
            }.drop(1).mapNotNull { it }
        }
        return shaped.distinctUntilChanged { a, b -> a.version == b.version && a.archiveDaysUpperBound == b.archiveDaysUpperBound }.flowOn(io)
    }

    override suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?> =
        withContext(io) { queries.channels(rowIds, from, to) }

    override suspend fun rowIdOf(identity: ChannelIdentity): Long? = withContext(io) { queries.rowIdOf(identity) }

    override suspend fun byNumber(playlistId: Long?, number: Int, access: ParentalAccess): GuideChannel? = withContext(io) {
        val unlocked = access.legacyUnlocked
        val p = (if (playlistId != null) playlistDao.get(playlistId) else null)
            ?: playlistDao.all().filter { it.enabled }.minWithOrNull(compareBy({ it.sortIndex }, { it.id }))
            ?: return@withContext null
        val row = channels.rowByNumber(p, number, unlocked) ?: return@withContext null
        queries.channels(longArrayOf(row.channel.id), 0, 1).firstOrNull()
    }

    override suspend fun isFavourite(identity: ChannelIdentity): Boolean = withContext(io) {
        val default = favouriteDao.lists().firstOrNull()?.id ?: return@withContext false
        favouriteDao.listsOf(identity.playlistId, identity.stableKey.value).any { it.listId == default }
    }

    override suspend fun setFavourite(identity: ChannelIdentity, on: Boolean) = withContext(io) {
        val list = favourites.defaultList()
        if (on) favourites.add(list, identity) else favourites.remove(list, identity)
        Unit
    }

    override suspend fun hideChannel(identity: ChannelIdentity) = withContext(io) {
        prefsDao.upsertHidden(identity.playlistId, identity.stableKey.value, true, clock.now())
    }

    override suspend fun groupAction(entry: GuideSourceEntry, action: GroupAction) = withContext(io) {
        val src = entry.source as? GuideSource.Group ?: return@withContext
        when (action) {
            GroupAction.HIDE -> groupDao.upsertGroupHidden(src.playlistId, 0, src.groupKey, true, clock.now())
            GroupAction.MOVE_UP, GroupAction.MOVE_DOWN -> {
                // The panel's order is the visible order; locked groups count as visible here so a later unlock keeps it.
                val keys = groupDao.groups(src.playlistId, 0, true).first().map { it.group.groupKey }.toMutableList()
                val i = keys.indexOf(src.groupKey)
                val j = if (action == GroupAction.MOVE_UP) i - 1 else i + 1
                if (i < 0 || j < 0 || j >= keys.size) return@withContext
                keys[i] = keys[j].also { keys[j] = keys[i] }
                groupDao.setGroupOrder(src.playlistId, 0, keys, clock.now())
            }
        }
    }

    override suspend fun lastWatched(): GuideChannel? = withContext(io) {
        val id = queries.lastWatched(unlocked = false) ?: return@withContext null
        queries.channels(longArrayOf(id), 0, 1).firstOrNull()
    }

    /** B5/F3: ticks (debounced 500 ms) on any change that can alter a loaded row without changing lineup membership. */
    @OptIn(FlowPreview::class)
    override val rowContent: Flow<Long> =
        trigger(ROW_CONTENT_TABLES, withRecentCount = false, emitInitial = false)
            .debounce(ROW_CONTENT_DEBOUNCE_MS)
            .runningFold(0L) { n, _ -> n + 1 }
            .drop(1)
            .flowOn(io)

    /**
     * (code review 2026-10-14, G-S6) Changes of [tables] (which leave out `channel_prefs`) plus changes of the guide's
     * `channel_prefs` columns ([GuideQueries.prefsSignature]): a tune's watch-history and learned-playback writes to
     * `channel_prefs` are not guide changes. With [emitInitial] it emits once at start (from [tables]).
     */
    private fun trigger(tables: Array<String>, withRecentCount: Boolean, emitInitial: Boolean): Flow<Unit> {
        val base = db.invalidationTracker.createFlow(*tables, emitInitialState = emitInitial).map { }
        val prefs = db.invalidationTracker.createFlow("channel_prefs")
            .map { queries.prefsSignature(withRecentCount) }
            .distinctUntilChanged()
            .drop(1) // the subscription's own value; only changes trigger
            .map { }
        return merge(base, prefs)
    }

    companion object {
        const val ROW_CONTENT_DEBOUNCE_MS = 500L
        /** Section labels (the panel shows them as given; M5 may localise by [SourceSection]). */
        const val LABEL_ALL = "All channels"
        const val LABEL_RECENT = "Recently watched"

        /** G-S6: `channel_prefs` is watched through [GuideQueries.prefsSignature], not as a table (Recent adds it back). */
        val LINEUP_TABLES = arrayOf("channel", "group_prefs", "channel_group", "favourite", "playlist")
        val SOURCE_TABLES = arrayOf("channel", "group_prefs", "channel_group", "favourite", "favourite_list", "playlist")
        val ROW_CONTENT_TABLES = arrayOf("channel", "playlist", "epg_mapping", "epg_match", "epg_source")

        /** N2: surviving ids keep their order; ids new to Recent go first (most recent). */
        fun frozenRecent(prev: LongArray, now: LongArray): LongArray {
            val nowSet = HashSet<Long>(now.size * 2).apply { now.forEach { add(it) } }
            val prevSet = HashSet<Long>(prev.size * 2).apply { prev.forEach { add(it) } }
            val out = LongArray(now.size)
            var n = 0
            for (id in now) if (id !in prevSet) out[n++] = id
            for (id in prev) if (id in nowSet) out[n++] = id
            return out
        }
    }
}
