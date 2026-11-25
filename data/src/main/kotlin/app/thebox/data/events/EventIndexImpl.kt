// Event channels WP-2: docs/design/m6-event-channels.md §3.3 (learned zone), §5.1, §5.2 (the index), §10 (event_count).
package app.thebox.data.events

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EventConstants
import app.thebox.core.events.EventEntry
import app.thebox.core.events.EventIndex
import app.thebox.core.events.EventNameParser
import app.thebox.core.events.EventParseContext
import app.thebox.core.events.EventSnapshot
import app.thebox.core.events.EventZoneSource
import app.thebox.core.events.ParsedName
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.PlaylistEventKey
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.repo.read
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-memory event index of every enabled playlist's live channels (§5.2), bound in `GuideDataModule`.
 *
 * **Key.** For each enabled playlist `(id, refresh_seq, last_refresh_at, event_zone)`, plus [EventNameParser.VERSION].
 * [current] reads the key (one small `playlist` query) and rebuilds when it moved; the refresh path calls [rebuild] after
 * the merge's `finish` (§10).
 *
 * **Read.** Pages of [PAGE] rows by id with the clock prefilter `name GLOB '*[0-9]:[0-5][0-9]*'` (every handled shape
 * carries a clock; §2.1: 1,672 of 13,109 live rows on the owner's playlist). The GLOB cannot use an index, so each page
 * is a scan; only the matching rows (about 1,700) are held. No lineup or parental predicate: the index is metadata by
 * row id, and each consumer filters visibility in its own query.
 *
 * **Two passes.** Pass 1 parses every row with the zone the playlist would use today and tallies the EXPLICIT zone ids
 * per playlist, over **all** live rows (arch B2: a quiet refresh cannot move the zone). The learned zone is then chosen
 * with hysteresis (§3.3, [chooseZone]); pass 2 re-parses only the ASSUMED names when the chosen zone differs from the
 * one pass 1 used.
 *
 * **Writes.** `event_zone` / `event_zone_n` and `event_count` only when they change (re-review E9: `playlist` is a
 * row-content table, so each write reloads the guide's rows).
 *
 * **Concurrency.** Builds are serialised by one [Mutex] and publish an immutable snapshot. A reader that finds a stale
 * snapshot while a build is running gets the previous snapshot at once (the [version] emission that follows reloads the
 * guide); only a reader with no snapshot at all (the first after process start) waits for a build.
 */
@Singleton
class EventIndexImpl internal constructor(
    private val db: LibraryDatabase,
    private val playlistDao: PlaylistDao,
    private val io: CoroutineDispatcher,
    private val clock: WallClock,
    /** The parser seam: [EventNameParser.parse] in production, a fake in `EventIndexImplTest`. */
    private val parse: (String, EventParseContext) -> ParsedName,
    /** Folded into the key, so a parser bump rebuilds (§5.2). */
    private val parserVersion: Int = EventNameParser.VERSION,
) : EventIndex {
    @Inject constructor(
        db: LibraryDatabase,
        playlistDao: PlaylistDao,
        @IoDispatcher io: CoroutineDispatcher,
        clock: WallClock,
    ) : this(db, playlistDao, io, clock, EventNameParser::parse)

    private val mutex = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    private var nextVersion = 0L
    private val _version = MutableStateFlow(EventSnapshot.EMPTY.version)
    override val version: StateFlow<Long> = _version.asStateFlow()

    /** What the last build measured (tests and the timing log). */
    @Volatile var lastBuild: BuildStats? = null
        private set

    /** [readMs]: the prefiltered read; [parseMs]: both passes; [ms]: the whole build, writes included. */
    data class BuildStats(val rowsRead: Int, val events: Int, val empty: Int, val reparsed: Int, val ms: Long, val readMs: Long = 0, val parseMs: Long = 0)

    override suspend fun current(): EventSnapshot {
        val have = snapshot
        val keys = withContext(io) { playlistDao.eventKeys() }
        if (have != null && have.key == keyOf(keys)) return have
        // Stale with a build under way (the refresh's rebuild, or another reader's): answer the previous snapshot now.
        if (have != null && mutex.isLocked) return have
        return mutex.withLock {
            val again = snapshot
            val fresh = withContext(io) { playlistDao.eventKeys() }
            if (again != null && again.key == keyOf(fresh)) again else build(fresh)
        }
    }

    override suspend fun rebuild(): EventSnapshot = mutex.withLock { build(withContext(io) { playlistDao.eventKeys() }) }

    private fun keyOf(keys: List<PlaylistEventKey>): Long {
        var h = Hash64.long(Hash64.OFFSET, parserVersion.toLong())
        for (k in keys) {
            h = Hash64.long(h, k.id)
            h = Hash64.long(h, k.refreshSeq)
            h = Hash64.long(h, k.lastRefreshAt ?: -1L)
            h = Hash64.str(h, k.eventZone)
        }
        return h
    }

    /** Runs under [mutex]. */
    private suspend fun build(keys: List<PlaylistEventKey>): Snapshot = withContext(io) {
        val t0 = System.nanoTime()
        val byPlaylist = keys.associateBy { it.id }
        val rows = readRows(byPlaylist.keys)
        val tRead = System.nanoTime()

        // Pass 1: each playlist's zone as it stands (stored, else the panel's, else UTC) and the EXPLICIT tally.
        val firstZone = HashMap<Long, ZoneId>(keys.size * 2)
        for (k in keys) firstZone[k.id] = storedZone(k) ?: fallbackZone(k)
        val ctxs = HashMap<Long, EventParseContext>(keys.size * 2)
        for (k in keys) ctxs[k.id] = EventParseContext(k.lastRefreshAt ?: clock.now(), firstZone.getValue(k.id))
        val parsed = arrayOfNulls<ParsedName>(rows.size)
        val tallies = HashMap<Long, HashMap<String, Int>>()
        for ((i, r) in rows.withIndex()) {
            val p = safeParse(r.name, ctxs.getValue(r.playlistId))
            parsed[i] = p
            if (p is ParsedName.Event && p.zone == EventZoneSource.EXPLICIT) {
                val t = tallies.getOrPut(r.playlistId) { HashMap() }
                t[p.zoneId] = (t[p.zoneId] ?: 0) + 1
            }
        }

        // The learned zone (§3.3), stored only on a change.
        val chosen = HashMap<Long, ZoneId>(keys.size * 2)
        val storedZoneText = HashMap<Long, String?>(keys.size * 2)
        for (k in keys) {
            val c = chooseZone(k.eventZone, tallies[k.id].orEmpty())
            if (c.store != null && c.store.zone != k.eventZone) playlistDao.setEventZone(k.id, c.store.zone, c.store.n)
            val text = c.store?.zone ?: k.eventZone
            storedZoneText[k.id] = text
            chosen[k.id] = text?.let(::zoneOrNull) ?: fallbackZone(k)
        }

        // Pass 2: only ASSUMED names of playlists whose zone moved.
        var reparsed = 0
        for ((i, r) in rows.withIndex()) {
            val p = parsed[i] as? ParsedName.Event ?: continue
            if (p.zone != EventZoneSource.ASSUMED) continue
            val z = chosen.getValue(r.playlistId)
            if (z == firstZone.getValue(r.playlistId)) continue
            parsed[i] = safeParse(r.name, EventParseContext(ctxs.getValue(r.playlistId).nowUtcMs, z))
            reparsed++
        }

        val tParse = System.nanoTime()
        // Entries: EVENTs in start order; an Empty result (the 2098 placeholder) is kept for `of` only.
        val events = ArrayList<EventEntry>()
        val empties = ArrayList<EventEntry>()
        for ((i, r) in rows.withIndex()) {
            when (val p = parsed[i]) {
                is ParsedName.Event -> events += EventEntry(
                    r.id, r.playlistId, p.slot, r.group,
                    ChannelEvent(ChannelEvent.Kind.EVENT, p.title, p.subtitle, minuteOf(p.startUtcMs), minuteOf(p.endUtcMs), p.zone),
                    r.name,
                )
                is ParsedName.Empty -> empties += EventEntry(r.id, r.playlistId, p.slot, r.group, ChannelEvent.EMPTY, r.name)
                else -> Unit
            }
        }
        events.sortWith(compareBy<EventEntry> { it.event.startMin }.thenBy { it.channelId })

        // event_count (§10) only on a change.
        val counts = HashMap<Long, Int>()
        for (e in events) counts[e.playlistId] = (counts[e.playlistId] ?: 0) + 1
        for (k in keys) {
            val n = counts[k.id] ?: 0
            if (n != k.eventCount) playlistDao.setEventCount(k.id, n)
        }

        // The key as it stands after the writes above, so the next `current` does not rebuild for our own zone write.
        val key = keyOf(keys.map { it.copy(eventZone = storedZoneText[it.id]) })
        val content = contentHash(events, empties)
        val prev = snapshot
        val v = if (prev != null && prev.key == key && prev.content == content) prev.version else ++nextVersion
        val snap = Snapshot(v, key, content, events, empties, counts)
        snapshot = snap
        val ms = (System.nanoTime() - t0) / 1_000_000
        val readMs = (tRead - t0) / 1_000_000
        val parseMs = (tParse - tRead) / 1_000_000
        lastBuild = BuildStats(rows.size, events.size, empties.size, reparsed, ms, readMs, parseMs)
        BoxLog.i(TAG) { "event index v$v: rows=${rows.size} events=${events.size} empty=${empties.size} reparsed=$reparsed in $ms ms (read $readMs, parse $parseMs)" }
        if (_version.value != v) _version.value = v
        snap
    }

    private class Row(val id: Long, val playlistId: Long, val name: String, val group: String?)

    /** The prefiltered rows of [playlists], paged by id ([PAGE] per read). */
    private suspend fun readRows(playlists: Set<Long>): List<Row> {
        val out = ArrayList<Row>()
        if (playlists.isEmpty()) return out
        var after = Long.MIN_VALUE
        while (true) {
            var last = Long.MIN_VALUE
            var n = 0
            db.read {
                usePrepared(PAGE_SQL) { st ->
                    st.bindLong(1, after)
                    st.bindLong(2, PAGE.toLong())
                    while (st.step()) {
                        n++
                        last = st.getLong(0)
                        val pid = st.getLong(1)
                        if (pid in playlists) out += Row(last, pid, st.getText(2), if (st.isNull(3)) null else st.getText(3))
                    }
                }
            }
            if (n < PAGE) return out
            after = last
        }
    }

    private fun safeParse(name: String, ctx: EventParseContext): ParsedName = try {
        parse(name, ctx)
    } catch (e: RuntimeException) {
        ParsedName.None // the parser never throws by contract; a bug there must not lose the whole index
    }

    private fun storedZone(k: PlaylistEventKey): ZoneId? = k.eventZone?.let(::zoneOrNull)

    /** Until a zone is learned: the panel's (`server_zone`), else UTC. Never stored (§3.3 step 2). */
    private fun fallbackZone(k: PlaylistEventKey): ZoneId = k.serverZone?.let(::zoneOrNull) ?: ZoneOffset.UTC

    private fun contentHash(events: List<EventEntry>, empties: List<EventEntry>): Long {
        var h = Hash64.OFFSET
        for (e in events) { h = Hash64.long(h, e.channelId); h = Hash64.long(h, e.event.basis); h = Hash64.str(h, e.event.subtitle) }
        for (e in empties) h = Hash64.long(h, e.channelId)
        return h
    }

    /** An immutable snapshot: entries sorted by start with a parallel start array, and a map by channel id. */
    private class Snapshot(
        override val version: Long,
        val key: Long,
        val content: Long,
        private val sorted: List<EventEntry>,
        empties: List<EventEntry>,
        private val counts: Map<Long, Int>,
    ) : EventSnapshot {
        private val starts = IntArray(sorted.size) { sorted[it].event.startMin }
        private val byId = HashMap<Long, EventEntry>((sorted.size + empties.size) * 2).apply {
            for (e in empties) put(e.channelId, e)
            for (e in sorted) put(e.channelId, e)
        }

        override fun of(channelId: Long): EventEntry? = byId[channelId]

        override fun startingIn(fromMin: Int, toMin: Int): List<EventEntry> {
            if (fromMin > toMin || sorted.isEmpty()) return emptyList()
            var i = lowerBound(fromMin)
            val out = ArrayList<EventEntry>()
            while (i < starts.size && starts[i] <= toMin) out += sorted[i++]
            return out
        }

        override fun current(nowMin: Int): List<EventEntry> = sorted.filter { it.event.endMin > nowMin }

        override fun count(playlistId: Long): Int = counts[playlistId] ?: 0

        /** The first index whose start is ≥ [min]. */
        private fun lowerBound(min: Int): Int {
            var lo = 0
            var hi = starts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < min) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    companion object {
        private const val TAG = "EventIndex"
        /** Rows per paged read. */
        const val PAGE = 2_000

        /**
         * §5.2: live rows of every kind the guide lists (live and radio) whose name carries a clock, with the **provider**
         * group on purpose (`EventEntry.group`: what the provider named the slot's group, read by stage E's hints).
         */
        private const val PAGE_SQL =
            "SELECT c.id, c.playlist_id, c.name, g0.name FROM channel c " + ChannelDao.PROVIDER_GROUP_JOIN +
                "WHERE c.id > ? AND c.missing_since IS NULL AND c.kind IN (0, 1) AND c.name GLOB '*[0-9]:[0-5][0-9]*' " +
                "ORDER BY c.id LIMIT ?"

        private fun minuteOf(ms: Long): Int = Math.floorDiv(ms, 60_000L).toInt()

        internal fun zoneOrNull(id: String): ZoneId? = try {
            ZoneId.of(id)
        } catch (e: Exception) {
            null
        }

        /** What [chooseZone] decided: the zone to store (null: keep what is stored, or nothing learned yet). */
        data class ZoneChoice(val store: Stored?) {
            data class Stored(val zone: String, val n: Int)
        }

        /**
         * §3.3 step 2. [stored]: `playlist.event_zone`; [tally]: EXPLICIT zone id → names, over every live row.
         * - With a stored zone Z, keep it unless the majority M ≠ Z has at least [EventConstants.EVENT_ZONE_MIN_SAMPLES]
         *   names **and** at least [EventConstants.EVENT_ZONE_HYSTERESIS] times Z's current count.
         * - With none, take M once it has at least the sample count.
         * A tie for the majority keeps the stored zone, else takes the smaller id (deterministic).
         */
        fun chooseZone(stored: String?, tally: Map<String, Int>): ZoneChoice {
            if (tally.isEmpty()) return ZoneChoice(null)
            val top = tally.values.max()
            val m = if (stored != null && tally[stored] == top) stored else tally.filterValues { it == top }.keys.min()
            val mN = top
            if (stored != null) {
                if (m == stored) return ZoneChoice(null)
                val zN = tally[stored] ?: 0
                return if (mN >= EventConstants.EVENT_ZONE_MIN_SAMPLES && mN >= EventConstants.EVENT_ZONE_HYSTERESIS * zN) {
                    ZoneChoice(ZoneChoice.Stored(m, mN))
                } else {
                    ZoneChoice(null)
                }
            }
            return if (mN >= EventConstants.EVENT_ZONE_MIN_SAMPLES) ZoneChoice(ZoneChoice.Stored(m, mN)) else ZoneChoice(null)
        }
    }
}
