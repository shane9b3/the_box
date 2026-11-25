package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWindow
import app.thebox.core.events.EventEntry
import app.thebox.core.events.EventIndex
import app.thebox.core.events.EventSnapshot
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TeamChannelRule
import app.thebox.core.sports.TeamName
import app.thebox.core.sports.TeamNames
import app.thebox.core.sports.TvBroadcast
import app.thebox.core.sports.hub.BroadcastAliases
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.EventFacts
import app.thebox.core.sports.hub.EventGameRule
import app.thebox.core.sports.hub.GameChannelMatcher
import app.thebox.core.sports.hub.GameListing
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.di.IoDispatcher
import app.thebox.data.guide.EpgRefResolver
import app.thebox.data.guide.GuideQueries
import app.thebox.data.repo.read
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.2 – §5.4; event channels, docs/design/m6-event-channels.md §8) Which of
 * the user's channels carry a game, across every enabled playlist, under the caller's [ParentalAccess] (hidden channels
 * and groups never; locked ones only when [access] lets them through, exactly as Search filters them).
 *
 * - **A, broadcaster aliases.** One `channel_fts` phrase query per listed code (`GuideQueries.channelsByNameFts`, FTS4
 *   phrases, `LIMIT 200`, no `ORDER BY`), then residue **equality** and the country check in Kotlin
 *   ([BroadcastAliases]). Streaming-only codes and local affiliates have no aliases. (Event channels §8.1) A channel
 *   whose only evidence is a **regional** alias is never a candidate: the alias only seeds C, so a regional channel shows
 *   when its guide names the game (CONFIRMED) or carries the sport (LIKELY).
 * - **C, window read of A's channels** (R-S4). One `EpgReader.window` over the game window `[start − 30 min,
 *   start + 4 h]` for every A channel with guide data; an airing naming the game confirms it, a sport-word airing that
 *   names no teams is a [MatchReason.SportListing] (LIKELY), an airing covering the scheduled start that names two other
 *   teams drops the channel. Placeholder listings ("No Event Today", "Next Event: …") are never evidence
 *   ([GameListing.classify]).
 * - **B, guide titles** (Search's EPG FTS). The two nicknames, then the two places; live / upcoming through `search`,
 *   ended through `searchPast` over Search's `archiveDaysAll` span (at most 7 days).
 * - **T, team channels** (§8.2). `GuideQueries.teamChannelCandidates` once per league, access and library state (cached
 *   until [clear]), narrowed by `TeamChannelRule.teamOf`; both teams' channels are CONFIRMED with a
 *   [MatchReason.TeamChannel].
 * - **E, event channels** (§8.3, §8.4). `EventSnapshot.startingIn(start − 90 min, start + 45 min)`, graded by
 *   [EventGameRule] against the game and the shown day's [board]; then `GuideQueries.visible`. A channel that is also a
 *   B / C hit is one candidate with both reasons.
 *
 * **Ranking (§8.6).** Team channels first and never capped, the user's team first (the side whose team channel is a
 * favourite, else the more-watched one, else the home side); then CONFIRMED guide listings, CONFIRMED event names,
 * LIKELY, POSSIBLE. Within a class: favourite, watch count, national before regional, playlist order, provider order.
 * Variants collapse per playlist on the alias key, else on `e:` + folded event title + `|` + start minute for an
 * event-only candidate (re-review E3), else on the name's residue. At most [SportsConstants.HUB_MATCH_MAX] rows besides
 * the team channels; when stage E found an event and none made the cut, the last row is the best event row.
 *
 * **[ChannelCandidate.group].** The shown channel's provider group; when a collapsed variant has the **same** name in
 * another group (a backup group, "NHL Teams (2)"), that variant's group instead, which the sheet shows as
 * "Also in <group>" (the frozen contract has one group field; §9.1 line 3).
 *
 * Cache (A-S5): key `(game, access, EPG generation + patch, enabled playlists' refresh_seq sum, event index version,
 * the board's games)` plus the game's phase, start and broadcasts, LRU [SportsConstants.HUB_DETAIL_LRU], emptied by
 * [clear] (page open, any access change), which also drops the team maps.
 */
@Singleton
class GameChannelMatcherImpl @Inject constructor(
    private val queries: GuideQueries,
    private val store: EpgStore,
    private val db: LibraryDatabase,
    private val events: EventIndex,
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
        val librarySeq: Long,
        val eventsVersion: Long,
        val board: List<Pair<GameId, Long>>,
    )

    private val cache = object : LinkedHashMap<Key, List<ChannelCandidate>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<ChannelCandidate>>?): Boolean =
            size > SportsConstants.HUB_DETAIL_LRU
    }

    /** Stage T's map per (league, access, library state): team abbreviation → its channels, in lineup order. */
    private data class TeamKey(val league: League, val access: ParentalAccess, val librarySeq: Long)

    private val teamMaps = HashMap<TeamKey, Map<String, List<GuideQueries.TeamChannelHit>>>()

    /** Diagnostics (benchmark, tests): the stage timings of the last uncached [match], in ns. */
    class Stats(
        val aliasNs: Long,
        val windowNs: Long,
        val guideNs: Long,
        val teamNs: Long,
        val eventNs: Long,
        val rankNs: Long,
        val ftsQueries: Int,
        val epgSearches: Int,
        /** Stage T read the database (false: the cached team map). */
        val teamQueried: Boolean,
        /** Stage E's graded, visible hits. */
        val eventHits: Int,
    ) {
        val totalNs: Long get() = aliasNs + windowNs + guideNs + teamNs + eventNs + rankNs
    }

    @Volatile var lastStats: Stats? = null
        private set

    /** Diagnostics (tests): uncached computations so far. */
    @Volatile var computations: Int = 0
        private set

    override suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate> =
        match(game, nowMs, access, emptyList())

    override suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess, board: List<GameSnapshot>): List<ChannelCandidate> =
        withContext(io) {
            val out = try {
                val info = store.info.value
                val snapshot = events.current()
                val librarySeq = librarySeq()
                val key = Key(
                    game.id, access, info?.generation ?: -1L, info?.patchSeq ?: -1, game.phase, game.startUtcMs, game.broadcasts,
                    librarySeq, snapshot.version, board.filter { it.id != game.id }.map { it.id to it.startUtcMs }.sortedBy { it.first.id },
                )
                synchronized(cache) { cache[key] }?.let { return@withContext it }
                compute(game, nowMs, access, board, snapshot, librarySeq).also { synchronized(cache) { cache[key] = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "match failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            out
        }

    override fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(teamMaps) { teamMaps.clear() }
    }

    /**
     * (§8.5) The end of usable guide data: `EpgGenerationInfo.dataToMin`, else (a generation built before it was
     * written) its `windowToMin`; null without a guide. `ChannelHorizon.beyondGuide` adds the now + 12 h floor.
     */
    override fun guideEndMs(): Long? {
        val i = store.info.value ?: return null
        return (i.dataToMin ?: i.windowToMin).toLong() * MINUTE_MS
    }

    /** (§8.5) Stage T only, ranked as in [match] (the user's team first), for the beyond-guide line. */
    override suspend fun teamChannels(game: GameSnapshot, access: ParentalAccess): List<ChannelCandidate> = withContext(io) {
        try {
            val accs = LinkedHashMap<Long, Acc>()
            teamStage(game, access, librarySeq(), accs)
            rank(accs.values.toList(), eventFound = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "team channels failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ the work

    private class AliasHit(val match: BroadcastAliases.AliasMatch, val broadcast: TvBroadcast)

    private class Airing(val info: ProgrammeInfo, val inArchive: Boolean)

    private class TeamHit(val team: TeamName, val side: Side)

    private class EventHit(val entry: EventEntry, val grade: EventGameRule.Grade)

    private class Acc(val channel: GuideChannel) {
        var alias: AliasHit? = null
        var guide: Airing? = null
        var sport: Airing? = null
        var dropped = false
        var team: TeamHit? = null
        var event: EventHit? = null
    }

    private class Facts(
        val playlistName: String,
        val playlistSort: Int,
        val sortIndex: Int,
        val favourite: Boolean,
        val watchCount: Int,
        val group: String?,
    )

    private suspend fun compute(
        game: GameSnapshot,
        nowMs: Long,
        access: ParentalAccess,
        board: List<GameSnapshot>,
        snapshot: EventSnapshot,
        librarySeq: Long,
    ): List<ChannelCandidate> {
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
        fun take(h: GuideQueries.NameHit, b: TvBroadcast): Boolean {
            val m = BroadcastAliases.match(h.name, b)
                ?: (if (h.displayName != h.name) BroadcastAliases.match(h.displayName, b) else null)
                ?: return false
            val old = aliasById[h.id]
            if (old == null || better(m, old.match)) aliasById[h.id] = AliasHit(m, b)
            return true
        }
        // (Sports Hub code review 2026-11-25, L3) Renamed channels: channel_fts keeps the provider name only.
        var renamed: List<GuideQueries.NameHit>? = null
        for (b in game.broadcasts.distinct()) {
            val match = BroadcastAliases.ftsMatch(b.network) ?: continue
            // (M3) Page through the token's channels (keyset on rowid) until the FTS answer runs out, enough residue
            // matches are in hand for C, or ALIAS_FTS_MAX_ROWS were read.
            var after = 0L
            var read = 0
            var matched = 0
            while (read < ALIAS_FTS_MAX_ROWS && matched < WINDOW_MAX_CHANNELS) {
                val page = queries.channelsByNameFts(match, access, minOf(ALIAS_FTS_LIMIT, ALIAS_FTS_MAX_ROWS - read), after)
                ftsQueries++
                for (h in page) if (take(h, b)) matched++
                read += page.size
                coroutineContext.ensureActive()
                if (page.size < ALIAS_FTS_LIMIT) break
                after = page.last().id
            }
            val r = renamed ?: queries.channelsWithCustomName(access, RENAMED_MAX).also { renamed = it }
            for (h in r) take(h, b)
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

        // ---- T: team channels.
        val teamQueried = teamStage(game, access, librarySeq, accs)
        coroutineContext.ensureActive()
        val t4 = System.nanoTime()

        // ---- E: event channels.
        val eventHits = eventStage(game, access, board, snapshot, startMin, accs)
        coroutineContext.ensureActive()
        val t5 = System.nanoTime()

        // ---- confidence, ranking, collapse.
        val out = rank(accs.values.toList(), eventFound = eventHits > 0)
        val t6 = System.nanoTime()
        lastStats = Stats(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, t6 - t5, ftsQueries, epgSearches, teamQueried, eventHits)
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
            // A placeholder ("Next Event: …") is never evidence: classify answers NONE, and its subtitle is not read.
            if (kind != GameListing.Kind.GAME && !GameListing.isPlaceholder(title) && (covers || GameListing.hasSportWord(title, teams))) {
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

    /**
     * T (§8.2): the two teams' channels into [accs]. Returns true when the team map was read from the database (not the
     * cached map).
     */
    private suspend fun teamStage(game: GameSnapshot, access: ParentalAccess, librarySeq: Long, accs: LinkedHashMap<Long, Acc>): Boolean {
        val league = game.id.league
        val word = leagueWord(league) ?: return false
        val away = TeamNames.of(league, game.away.abbrev)
        val home = TeamNames.of(league, game.home.abbrev)
        if (away == null && home == null) return false
        val key = TeamKey(league, access, librarySeq)
        var queried = false
        val map = synchronized(teamMaps) { teamMaps[key] } ?: run {
            queried = true
            val byTeam = HashMap<String, MutableList<GuideQueries.TeamChannelHit>>()
            for (h in queries.teamChannelCandidates(word, access, SportsConstants.HUB_TEAM_QUERY_MAX)) {
                val t = TeamChannelRule.teamOf(league, h.name, h.group, h.tvgId) ?: continue
                byTeam.getOrPut(t.abbrev) { ArrayList(2) } += h
            }
            synchronized(teamMaps) { teamMaps[key] = byTeam }
            byTeam
        }
        val wanted = LinkedHashMap<Long, TeamHit>()
        for ((team, side) in listOf(away to Side.AWAY, home to Side.HOME)) {
            if (team == null) continue
            for (h in map[team.abbrev].orEmpty()) wanted.putIfAbsent(h.id, TeamHit(team, side))
        }
        val missing = wanted.keys.filter { it !in accs }.toLongArray()
        if (missing.isNotEmpty()) for (ch in queries.channels(missing, 0, missing.size)) if (ch != null) accs[ch.rowId] = Acc(ch)
        for ((id, hit) in wanted) accs[id]?.team = hit
        return queried
    }

    /** E (§8.3): events starting near the game, graded, then the lineup and parental filter. Returns the kept hits. */
    private suspend fun eventStage(
        game: GameSnapshot,
        access: ParentalAccess,
        board: List<GameSnapshot>,
        snapshot: EventSnapshot,
        startMin: Int,
        accs: LinkedHashMap<Long, Acc>,
    ): Int {
        if (leagueWord(game.id.league) == null) return 0
        val entries = snapshot.startingIn(startMin - SportsConstants.HUB_EVENT_BEFORE_MIN, startMin + SportsConstants.HUB_EVENT_AFTER_MIN)
        if (entries.isEmpty()) return 0
        val graded = LinkedHashMap<Long, EventHit>()
        for (e in entries) {
            val title = e.event.title ?: continue
            val grade = EventGameRule.grade(EventFacts(e.name, title, e.group, e.event.startMin * MINUTE_MS, e.event.zone), game, board) ?: continue
            val hit = EventHit(e, grade)
            val old = graded[e.channelId]
            if (old == null || betterEvent(hit, old, startMin)) graded[e.channelId] = hit
        }
        if (graded.isEmpty()) return 0
        val visible = queries.visible(graded.keys, access)
        if (visible.isEmpty()) return 0
        val missing = visible.filter { it !in accs }.toLongArray()
        if (missing.isNotEmpty()) for (ch in queries.channels(missing, 0, missing.size)) if (ch != null) accs[ch.rowId] = Acc(ch)
        var kept = 0
        for (id in visible) {
            val acc = accs[id] ?: continue
            acc.event = graded.getValue(id)
            kept++
        }
        return kept
    }

    /** The candidate's level from its best evidence, or null when it is not a candidate (§8.1, §8.4). */
    private fun confidenceOf(a: Acc): MatchConfidence? {
        if (a.team != null || a.guide != null) return MatchConfidence.CONFIRMED
        var best: MatchConfidence? = a.event?.grade?.confidence
        val alias = a.alias
        if (alias != null && !a.dropped) {
            // §8.1: a regional alias alone is no evidence; with a sport listing it is LIKELY.
            val c = when {
                a.sport != null -> MatchConfidence.LIKELY
                alias.match.national -> alias.match.confidence
                else -> null
            }
            if (c != null && (best == null || c.ordinal < best.ordinal)) best = c
        }
        return best
    }

    /** §8.6 classes: team channels, CONFIRMED guide listings, CONFIRMED event names, LIKELY, POSSIBLE. */
    private fun classOf(a: Acc, c: MatchConfidence): Int = when {
        a.team != null -> 0
        c == MatchConfidence.CONFIRMED && a.guide != null -> 1
        c == MatchConfidence.CONFIRMED -> 2
        c == MatchConfidence.LIKELY -> 3
        else -> 4
    }

    private class Kept(val acc: Acc, val confidence: MatchConfidence, val cls: Int)

    private suspend fun rank(accs: List<Acc>, eventFound: Boolean): List<ChannelCandidate> {
        val kept = ArrayList<Kept>(accs.size)
        for (a in accs) {
            val c = confidenceOf(a) ?: continue
            kept += Kept(a, c, classOf(a, c))
        }
        if (kept.isEmpty()) return emptyList()
        val facts = facts(kept.map { it.acc.channel.rowId })
        fun f(k: Kept) = facts[k.acc.channel.rowId]

        // The user's team (§8.6): the side whose team channel is a favourite, else the more-watched one, else home.
        val teamKept = kept.filter { it.acc.team != null }
        val userSide: Side = run {
            fun fav(s: Side) = teamKept.any { it.acc.team!!.side == s && f(it)?.favourite == true }
            fun watch(s: Side) = teamKept.filter { it.acc.team!!.side == s }.maxOfOrNull { f(it)?.watchCount ?: 0 } ?: 0
            when {
                fav(Side.AWAY) != fav(Side.HOME) -> if (fav(Side.AWAY)) Side.AWAY else Side.HOME
                watch(Side.AWAY) != watch(Side.HOME) -> if (watch(Side.AWAY) > watch(Side.HOME)) Side.AWAY else Side.HOME
                else -> Side.HOME
            }
        }
        val sorted = kept.sortedWith(
            compareBy<Kept> { it.cls }
                .thenBy { val t = it.acc.team; if (t == null || t.side == userSide) 0 else 1 }
                .thenBy { if (f(it)?.favourite == true) 0 else 1 }
                .thenByDescending { f(it)?.watchCount ?: 0 }
                .thenBy { if (it.acc.alias?.match?.national == false) 1 else 0 }
                .thenBy { f(it)?.playlistSort ?: Int.MAX_VALUE }
                .thenBy { f(it)?.sortIndex ?: Int.MAX_VALUE }
                .thenBy { it.acc.channel.rowId },
        )
        // Collapse quality variants and same-event duplicates (same playlist): the alias key, else the event key for an
        // event-only candidate (re-review E3: never the short name), else the residue (guide-only, team channels).
        val groups = LinkedHashMap<String, MutableList<Kept>>()
        for (k in sorted) groups.getOrPut("${k.acc.channel.identity.playlistId}\u001F${collapseKey(k.acc)}") { ArrayList(2) } += k
        val reserved = groups.values.filter { it.first().acc.team != null }
        val rest = groups.values.filter { it.first().acc.team == null }
        var capped = rest.take(SportsConstants.HUB_MATCH_MAX)
        if (eventFound && rest.size > capped.size && capped.none { it.first().acc.event != null }) {
            rest.firstOrNull { it.first().acc.event != null }?.let { best -> capped = capped.dropLast(1) + listOf(best) }
        }
        return (reserved + capped).map { members -> candidateOf(members, facts) }
    }

    private fun collapseKey(a: Acc): String {
        a.alias?.let { return it.match.key }
        val e = a.event
        if (e != null && a.team == null && a.guide == null && a.sport == null) {
            return "e:" + TeamNames.tokens(e.entry.event.title.orEmpty()).joinToString(" ") + "|" + e.entry.event.startMin
        }
        return "r:" + BroadcastAliases.residue(a.channel.displayName).text
    }

    private fun candidateOf(members: List<Kept>, facts: Map<Long, Facts>): ChannelCandidate {
        val head = members.first()
        val acc = head.acc
        val reasons = buildList {
            acc.team?.let { add(MatchReason.TeamChannel(it.team.fullNames.first(), it.team.nicknames.first(), it.side)) }
            acc.guide?.let { add(MatchReason.Guide(it.info.title, it.info.startMin * MINUTE_MS, it.info.endMin * MINUTE_MS, it.inArchive)) }
            if (acc.guide == null) acc.sport?.let { add(MatchReason.SportListing(it.info.title, it.info.startMin * MINUTE_MS)) }
            acc.event?.let { e ->
                add(MatchReason.EventName(e.entry.event.title.orEmpty(), e.entry.event.startMin * MINUTE_MS, e.grade.citiesOnly, e.grade.zoneAssumed))
            }
            acc.alias?.let { add(MatchReason.Broadcaster(it.broadcast.network.trim(), it.broadcast.market, it.broadcast.country)) }
        }
        val own = facts[acc.channel.rowId]
        // A same-name variant in another group: the sheet's "Also in <group>" (see the class KDoc).
        val elsewhere = members.drop(1).firstNotNullOfOrNull { m ->
            val g = facts[m.acc.channel.rowId]?.group
            g?.takeIf { m.acc.channel.displayName.equals(acc.channel.displayName, ignoreCase = true) && it != own?.group }
        }
        return ChannelCandidate(
            channel = acc.channel,
            playlistName = own?.playlistName ?: "",
            confidence = head.confidence,
            reasons = reasons,
            variants = members.drop(1).map { it.acc.channel },
            airing = acc.guide?.info,
            group = elsewhere ?: own?.group,
        )
    }

    /** Playlist name, group and the ranking facts of [ids] (one statement per chunk). */
    private suspend fun facts(ids: List<Long>): Map<Long, Facts> {
        val out = HashMap<Long, Facts>(ids.size * 2)
        db.read {
            for (chunk in ids.chunked(EpgRefResolver.MAX_VARS)) {
                val marks = chunk.joinToString(",") { "?" }
                usePrepared(
                    "SELECT c.id, p.name, p.sort_index, c.sort_index, COALESCE(cp.watch_count, 0), " +
                        "EXISTS (SELECT 1 FROM favourite f WHERE f.playlist_id = c.playlist_id AND f.stable_key = c.stable_key), g0.name " +
                        "FROM channel c JOIN playlist p ON p.id = c.playlist_id " +
                        "LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
                        // The provider group (§9.1 "Also in", duplicate names), never the user's moved group.
                        ChannelDao.PROVIDER_GROUP_JOIN +
                        "WHERE c.id IN ($marks)",
                ) { st ->
                    chunk.forEachIndexed { i, id -> st.bindLong(i + 1, id) }
                    while (st.step()) {
                        out[st.getLong(0)] = Facts(
                            st.getText(1), st.getLong(2).toInt(), st.getLong(3).toInt(), st.getLong(5) != 0L, st.getLong(4).toInt(),
                            if (st.isNull(6)) null else st.getText(6),
                        )
                    }
                }
            }
        }
        return out
    }

    /** The enabled playlists' `refresh_seq` sum: a refresh, an enable or a disable moves it (cache keys). */
    private suspend fun librarySeq(): Long = db.read {
        usePrepared("SELECT COALESCE(SUM(refresh_seq), 0), COUNT(*) FROM playlist WHERE enabled = 1") { st ->
            if (st.step()) st.getLong(0) * 31 + st.getLong(1) else 0L
        }
    }

    private fun leagueWord(league: League): String? = when (league) {
        League.NHL -> "nhl"
        League.NFL -> "nfl"
        else -> null
    }

    private fun better(a: BroadcastAliases.AliasMatch, b: BroadcastAliases.AliasMatch): Boolean =
        a.confidence.ordinal < b.confidence.ordinal || (a.confidence == b.confidence && a.national && !b.national)

    /** A higher grade wins, then the event starting nearest the game. */
    private fun betterEvent(a: EventHit, b: EventHit, startMin: Int): Boolean {
        if (a.grade.confidence != b.grade.confidence) return a.grade.confidence.ordinal < b.grade.confidence.ordinal
        return kotlin.math.abs(a.entry.event.startMin - startMin) < kotlin.math.abs(b.entry.event.startMin - startMin)
    }

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
        /**
         * (Sports Hub code review 2026-11-25, M3) The alias pages stop after this many rows per code: 5 pages, so a token
         * that hundreds of channels share still reaches the national channel without an unbounded read on a Fire TV.
         */
        const val ALIAS_FTS_MAX_ROWS = 1_000
        /** (L3) The renamed channels read once per match. */
        const val RENAMED_MAX = 200
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
