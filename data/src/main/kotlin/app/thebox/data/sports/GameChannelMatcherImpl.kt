package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWindow
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.hub.BroadcastAliases
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.GameChannelMatcher
import app.thebox.core.sports.hub.GameListing
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.di.IoDispatcher
import app.thebox.data.guide.GuideQueries
import app.thebox.data.guide.EpgRefResolver
import app.thebox.data.repo.read
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.2 – §5.4; WP-3; A-B3, A-S5, A-S8, R-S2 – R-S4) Which of the user's
 * channels carry a game, across every enabled playlist, under the caller's [ParentalAccess] (hidden channels and groups
 * never; locked ones only when [access] lets them through, exactly as Search filters them).
 *
 * - **A, broadcaster aliases.** One `channel_fts` phrase query per listed code (`GuideQueries.channelsByNameFts`, FTS4
 *   phrases, `LIMIT 200`, no `ORDER BY`), then residue **equality** and the country check in Kotlin
 *   ([BroadcastAliases]). Streaming-only codes and local affiliates have no aliases.
 * - **C, window read of A's channels** (R-S4). One `EpgReader.window` over the game window `[start − 30 min,
 *   start + 4 h]` for every A channel with guide data; an airing naming the game confirms it, a sport-word airing that
 *   names no teams is a [MatchReason.SportListing] (LIKELY), an airing covering the scheduled start that names two other
 *   teams drops the channel. Subtitles are read (`EpgReader.detail`) only for sport-word airings that do not name the
 *   game in their title.
 * - **B, guide titles** (Search's EPG FTS). The two nicknames, then the two places; live / upcoming through `search`,
 *   ended through `searchPast` over Search's `archiveDaysAll` span (at most 7 days), `perText`
 *   [SportsConstants.HUB_MATCH_PER_TEXT]; airings overlapping the window in display minutes; refs → channels confirmed
 *   by the guide's resolution rule; ended airings only inside the channel's archive (Search's rule).
 *
 * Ranking (§5.3): confidence, favourite, watch count, national before regional, playlist order, provider order. Quality
 * variants of one feed (same playlist, same canonical alias key or residue) collapse to the best-ranked channel. At most
 * [SportsConstants.HUB_MATCH_MAX]. Cache (A-S5): key `(game, access, EPG generation + patch)` plus the game's phase,
 * start and broadcasts (a superset: a board update or a phase change reads again), LRU [SportsConstants.HUB_DETAIL_LRU],
 * emptied by [clear] (page open, any access change).
 */
@Singleton
class GameChannelMatcherImpl @Inject constructor(
    private val queries: GuideQueries,
    private val store: EpgStore,
    private val db: LibraryDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GameChannelMatcher {

    private data class Key(
        val game: GameId,
        val access: ParentalAccess,
        val generation: Long,
        val patchSeq: Int,
        val phase: GamePhase,
        val startUtcMs: Long,
        val broadcasts: List<TvBroadcast>,
    )

    private val cache = object : LinkedHashMap<Key, List<ChannelCandidate>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<ChannelCandidate>>?): Boolean =
            size > SportsConstants.HUB_DETAIL_LRU
    }

    /** Diagnostics (benchmark, tests): the stage timings of the last uncached [match], in ns. */
    class Stats(val aliasNs: Long, val windowNs: Long, val guideNs: Long, val rankNs: Long, val ftsQueries: Int, val epgSearches: Int) {
        val totalNs: Long get() = aliasNs + windowNs + guideNs + rankNs
    }

    @Volatile var lastStats: Stats? = null
        private set

    /** Diagnostics (tests): uncached computations so far. */
    @Volatile var computations: Int = 0
        private set

    override suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate> = withContext(io) {
        val info = store.info.value
        val key = Key(game.id, access, info?.generation ?: -1L, info?.patchSeq ?: -1, game.phase, game.startUtcMs, game.broadcasts)
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val out = try {
            compute(game, nowMs, access)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "match failed: ${e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        synchronized(cache) { cache[key] = out }
        out
    }

    override fun clear() {
        synchronized(cache) { cache.clear() }
    }

    // ------------------------------------------------------------------ the work

    private class AliasHit(val match: BroadcastAliases.AliasMatch, val broadcast: TvBroadcast)

    private class Airing(val info: ProgrammeInfo, val inArchive: Boolean)

    private class Acc(val channel: GuideChannel) {
        var alias: AliasHit? = null
        var guide: Airing? = null
        var sport: Airing? = null
        var dropped = false
    }

    private class Facts(val playlistName: String, val playlistSort: Int, val sortIndex: Int, val favourite: Boolean, val watchCount: Int)

    private suspend fun compute(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate> {
        computations++
        val nowMin = Math.floorDiv(nowMs, MINUTE_MS).toInt()
        val startMin = Math.floorDiv(game.startUtcMs, MINUTE_MS).toInt()
        val fromMin = startMin - SportsConstants.HUB_MATCH_BEFORE_MIN
        val toMin = startMin + SportsConstants.HUB_MATCH_AFTER_MIN
        val teams = GameListing.teamsOf(game)

        // ---- A: broadcaster aliases.
        val t0 = System.nanoTime()
        var ftsQueries = 0
        val aliasById = HashMap<Long, AliasHit>()
        for (b in game.broadcasts.distinct()) {
            val match = BroadcastAliases.ftsMatch(b.network) ?: continue
            ftsQueries++
            for (h in queries.channelsByNameFts(match, access, ALIAS_FTS_LIMIT)) {
                val m = BroadcastAliases.match(h.name, b)
                    ?: (if (h.displayName != h.name) BroadcastAliases.match(h.displayName, b) else null)
                    ?: continue
                val old = aliasById[h.id]
                if (old == null || better(m, old.match)) aliasById[h.id] = AliasHit(m, b)
            }
            coroutineContext.ensureActive()
        }
        val accs = LinkedHashMap<Long, Acc>()
        if (aliasById.isNotEmpty()) {
            val ids = aliasById.keys.toLongArray()
            for (ch in queries.channels(ids, 0, ids.size)) {
                if (ch == null) continue
                accs[ch.rowId] = Acc(ch).also { it.alias = aliasById[ch.rowId] }
            }
        }
        coroutineContext.ensureActive()
        val t1 = System.nanoTime()

        // ---- C (window read of A's channels) and B (guide-title FTS) on one reader, closed before the library reads.
        var epgSearches = 0
        val live = ArrayList<EpgSearchHit>()
        val past = ArrayList<EpgSearchHit>()
        var t2 = t1
        val reader = store.openReader()
        if (reader != null) {
            try {
                windowRead(reader, accs.values.filter { it.channel.epg != null }.take(WINDOW_MAX_CHANNELS), teams, startMin, fromMin, toMin, nowMin)
                t2 = System.nanoTime()
                coroutineContext.ensureActive()
                // Search's look-back span (A-N8 variant): B runs before A's archive days are known.
                val pastDays = if (fromMin < nowMin) minOf(SEARCH_PAST_DAYS, queries.archiveDaysAll(access)) else 0
                for (q in teams.queries) {
                    if (toMin > nowMin) { live += reader.search(q, nowMin, GUIDE_LIMIT, SportsConstants.HUB_MATCH_PER_TEXT); epgSearches++ }
                    if (pastDays > 0) { past += reader.searchPast(q, nowMin, nowMin - pastDays * 1440, GUIDE_LIMIT, SportsConstants.HUB_MATCH_PER_TEXT); epgSearches++ }
                    coroutineContext.ensureActive()
                }
            } finally {
                reader.close()
            }
        }
        guideHits(live + past, teams, fromMin, toMin, nowMin, access, accs)
        val t3 = System.nanoTime()

        // ---- confidence, ranking, collapse.
        val out = rank(accs.values.toList())
        val t4 = System.nanoTime()
        lastStats = Stats(t1 - t0, t2 - t1, t3 - t2, t4 - t3, ftsQueries, epgSearches)
        return out
    }

    /** C (R-S4): the game window of A's channels, read directly; the sport-word and team tests in Kotlin. */
    private fun windowRead(reader: EpgReader, chans: List<Acc>, teams: GameListing.Teams, startMin: Int, fromMin: Int, toMin: Int, nowMin: Int) {
        if (chans.isEmpty()) return
        val epgIds = reader.resolve(chans.map { it.channel.epg!! })
        val shifts = IntArray(chans.size) { chans[it].channel.epgShiftMin }
        val win = EpgWindow(64)
        reader.window(epgIds, shifts, fromMin, toMin, win)
        for (i in 0 until win.size) {
            val acc = chans[win.channelIndex[i]]
            val title = win.titles[i] ?: continue
            val start = win.startMin[i]
            val end = win.endMin[i]
            val raw = win.rawStartMin[i]
            var subtitle: String? = null
            var kind = GameListing.classify(title, null, teams)
            val covers = start <= startMin && startMin < end
            if (kind != GameListing.Kind.GAME && (covers || GameListing.hasSportWord(title, teams))) {
                subtitle = reader.detail(epgIds[win.channelIndex[i]], raw, acc.channel.epgShiftMin)?.subtitle
                if (subtitle != null) kind = GameListing.classify(title, subtitle, teams)
            }
            val ended = end <= nowMin
            val inArchive = ended && acc.channel.archiveDays > 0 && raw >= nowMin - acc.channel.archiveDays * 1440
            when (kind) {
                GameListing.Kind.GAME -> if (!ended || inArchive) {
                    val a = Airing(info(raw, start, end, title, subtitle), inArchive)
                    if (acc.guide == null || closer(a, acc.guide!!, startMin)) acc.guide = a
                }
                GameListing.Kind.SPORT -> if (!ended || inArchive) {
                    val a = Airing(info(raw, start, end, title, subtitle), inArchive)
                    if (acc.sport == null || closer(a, acc.sport!!, startMin)) acc.sport = a
                }
                GameListing.Kind.OTHER_GAME -> if (covers) acc.dropped = true
                GameListing.Kind.NONE -> Unit
            }
        }
    }

    /** B: guide-title hits naming the game → library channels (Search's confirmation and archive rules). */
    private suspend fun guideHits(
        hits: List<EpgSearchHit>,
        teams: GameListing.Teams,
        fromMin: Int,
        toMin: Int,
        nowMin: Int,
        access: ParentalAccess,
        accs: LinkedHashMap<Long, Acc>,
    ) {
        // A loose raw-time cut before the per-channel display shift is known (shifts are hours at most).
        val game = hits.filter { h ->
            h.rawEndMin > fromMin - SHIFT_SLACK_MIN && h.rawStartMin < toMin + SHIFT_SLACK_MIN &&
                GameListing.classify(h.title, h.subtitle, teams) == GameListing.Kind.GAME
        }
        if (game.isEmpty()) return
        val byRef = queries.channelsForRefs(game.mapTo(HashSet()) { it.ref }, access)
        coroutineContext.ensureActive()
        val ids = byRef.values.flatten().distinct().filter { it !in accs }.take(GUIDE_MAX_CHANNELS).toLongArray()
        val loaded = HashMap<Long, GuideChannel>()
        accs.values.forEach { loaded[it.channel.rowId] = it.channel }
        if (ids.isNotEmpty()) queries.channels(ids, 0, ids.size).forEach { if (it != null) loaded[it.rowId] = it }
        val startMin = fromMin + SportsConstants.HUB_MATCH_BEFORE_MIN
        for (h in game) {
            for (id in byRef[h.ref].orEmpty()) {
                val ch = loaded[id] ?: continue
                if (ch.epg != h.ref) continue // the guide's rule: only a channel whose resolved ref is this ref
                val start = h.rawStartMin + ch.epgShiftMin
                val end = h.rawEndMin + ch.epgShiftMin
                if (end <= fromMin || start >= toMin) continue
                val ended = end <= nowMin
                val inArchive = ended && ch.archiveDays > 0 && h.rawStartMin >= nowMin - ch.archiveDays * 1440
                if (ended && !inArchive) continue
                val acc = accs.getOrPut(id) { Acc(ch) }
                val a = Airing(info(h.rawStartMin, start, end, h.title, h.subtitle), inArchive)
                if (acc.guide == null || closer(a, acc.guide!!, startMin)) acc.guide = a
            }
        }
    }

    private suspend fun rank(accs: List<Acc>): List<ChannelCandidate> {
        val kept = ArrayList<Pair<Acc, MatchConfidence>>(accs.size)
        for (a in accs) {
            val c = when {
                a.guide != null -> MatchConfidence.CONFIRMED
                a.dropped -> null
                a.alias != null -> if (a.sport != null) MatchConfidence.LIKELY else a.alias!!.match.confidence
                else -> null
            } ?: continue
            kept += a to c
        }
        if (kept.isEmpty()) return emptyList()
        val facts = facts(kept.map { it.first.channel.rowId })
        val sorted = kept.sortedWith(
            compareBy<Pair<Acc, MatchConfidence>> { it.second.ordinal }
                .thenBy { if (facts[it.first.channel.rowId]?.favourite == true) 0 else 1 }
                .thenByDescending { facts[it.first.channel.rowId]?.watchCount ?: 0 }
                .thenBy { if (it.first.alias?.match?.national == false) 1 else 0 }
                .thenBy { facts[it.first.channel.rowId]?.playlistSort ?: Int.MAX_VALUE }
                .thenBy { facts[it.first.channel.rowId]?.sortIndex ?: Int.MAX_VALUE }
                .thenBy { it.first.channel.rowId },
        )
        // Collapse quality variants: same playlist, same canonical feed key (alias) or residue (guide-only).
        val groups = LinkedHashMap<String, MutableList<Pair<Acc, MatchConfidence>>>()
        for (p in sorted) {
            val ch = p.first.channel
            val feed = p.first.alias?.match?.key ?: ("r:" + BroadcastAliases.residue(ch.displayName).text)
            groups.getOrPut("${ch.identity.playlistId}\u001F$feed") { ArrayList(2) } += p
        }
        return groups.values.take(SportsConstants.HUB_MATCH_MAX).map { members ->
            val (acc, confidence) = members.first()
            val reasons = buildList {
                acc.guide?.let { add(MatchReason.Guide(it.info.title, it.info.startMin * MINUTE_MS, it.info.endMin * MINUTE_MS, it.inArchive)) }
                if (acc.guide == null) acc.sport?.let { add(MatchReason.SportListing(it.info.title, it.info.startMin * MINUTE_MS)) }
                acc.alias?.let { add(MatchReason.Broadcaster(it.broadcast.network.trim(), it.broadcast.market, it.broadcast.country)) }
            }
            ChannelCandidate(
                channel = acc.channel,
                playlistName = facts[acc.channel.rowId]?.playlistName ?: "",
                confidence = confidence,
                reasons = reasons,
                variants = members.drop(1).map { it.first.channel },
                airing = acc.guide?.info,
            )
        }
    }

    /** Playlist name and the ranking facts of [ids] (one statement per chunk). */
    private suspend fun facts(ids: List<Long>): Map<Long, Facts> {
        val out = HashMap<Long, Facts>(ids.size * 2)
        db.read {
            for (chunk in ids.chunked(EpgRefResolver.MAX_VARS)) {
                val marks = chunk.joinToString(",") { "?" }
                usePrepared(
                    "SELECT c.id, p.name, p.sort_index, c.sort_index, COALESCE(cp.watch_count, 0), " +
                        "EXISTS (SELECT 1 FROM favourite f WHERE f.playlist_id = c.playlist_id AND f.stable_key = c.stable_key) " +
                        "FROM channel c JOIN playlist p ON p.id = c.playlist_id " +
                        "LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
                        "WHERE c.id IN ($marks)",
                ) { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    while (st.step()) {
                        out[st.getLong(0)] = Facts(st.getText(1), st.getLong(2).toInt(), st.getLong(3).toInt(), st.getLong(5) != 0L, st.getLong(4).toInt())
                    }
                }
            }
        }
        return out
    }

    private fun better(a: BroadcastAliases.AliasMatch, b: BroadcastAliases.AliasMatch): Boolean =
        a.confidence.ordinal < b.confidence.ordinal || (a.confidence == b.confidence && a.national && !b.national)

    /** The airing covering the scheduled start wins, then the one starting nearest to it. */
    private fun closer(a: Airing, b: Airing, startMin: Int): Boolean {
        fun covers(x: Airing) = x.info.startMin <= startMin && startMin < x.info.endMin
        if (covers(a) != covers(b)) return covers(a)
        return kotlin.math.abs(a.info.startMin - startMin) < kotlin.math.abs(b.info.startMin - startMin)
    }

    private fun info(raw: Int, start: Int, end: Int, title: String, subtitle: String?) = ProgrammeInfo(
        rawStartMin = raw, startMin = start, endMin = end, title = title, subtitle = subtitle,
        description = null, genre = null, episodeLabel = null, flags = 0, titleHash = Hash64.of(title),
    )

    companion object {
        private const val TAG = "GameChannelMatcher"
        private const val MINUTE_MS = 60_000L
        /** §5.2 A (A-B3): per code, before the residue rule. */
        const val ALIAS_FTS_LIMIT = 200
        /** §5.2 B: EPG FTS airings per query. */
        const val GUIDE_LIMIT = 80
        /** Search's look-back cap (`GuideSearchImpl.PAST_DAYS`). */
        const val SEARCH_PAST_DAYS = 7
        /** C reads at most this many A channels (≤ 8 candidates × their variants × playlists). */
        const val WINDOW_MAX_CHANNELS = 96
        const val GUIDE_MAX_CHANNELS = 96
        /** Raw-time slack for B's pre-filter (the display shift is applied per channel after). */
        private const val SHIFT_SLACK_MIN = 24 * 60
    }
}
