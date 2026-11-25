package app.thebox.data.guide

import app.thebox.core.parental.ParentalAccess
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgStore
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EpgFiller
import app.thebox.core.events.EventConstants
import app.thebox.core.events.EventEntry
import app.thebox.core.events.EventIndex
import app.thebox.core.guide.feed.eventProgrammeInfo
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamName
import app.thebox.core.sports.TeamNames
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.SearchResults
import app.thebox.core.text.NameNormalizer
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.ChannelRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Guide search (docs/design/m3-guide.md §3.10; review B6, S15).
 *
 * Channels: `channel_fts` (raw names, prefix terms) with the lineup predicate, ranked exact > prefix > favourite >
 * watch count > playlist order > provider order, 30 kept, shown with their display names.
 *
 * Programmes: [app.thebox.core.epg.EpgReader.search] (on now and upcoming, per-text cap) and `searchPast` (newest first,
 * per-text cap, at most 7 days or the archive upper bound back) on a reader opened for the call (aux connection, closed
 * in `finally`); each hit maps back to library channels that map or AUTO-match its EPG ref, keeps only channels whose
 * **resolved** ref is that ref (the guide's rule), at most 3 per airing; locked groups, hidden and adult channels are
 * filtered in SQL by the caller's `access` (§4.3: OPEN for an unlocked PIN session or `GuideSettings.debugUnlocked`);
 * ended hits survive only inside the channel's archive. Cancellable between stages.
 *
 * Event channels (docs/design/m6-event-channels.md §7): guide hits whose title is a provider placeholder ([EpgFiller])
 * are dropped; events read from channel names come from the in-memory [EventIndex] (every query word, or a team
 * nickname's place, prefixes a word of the title or subtitle), filtered by the lineup and parental rules, capped at
 * [EventConstants.EVENT_SEARCH_LIMIT] in start order, and dropped when the same channel has a kept guide hit starting
 * within [EventConstants.EVENT_SEARCH_DEDUP_MIN]. They need no guide generation. Empty-slot channels rank last.
 */
@Singleton
class GuideSearchImpl(
    private val queries: GuideQueries,
    private val store: EpgStore,
    private val io: CoroutineDispatcher,
    /** Null (tests that construct this class directly): no event hits. */
    private val events: EventIndex? = null,
    /** The placeholder-listing test (WP-1's [EpgFiller]); tests inject one. */
    private val isFiller: (String) -> Boolean = EpgFiller::isFiller,
    /** The teams whose nickname or full name in a query also finds their places (§7, U-E-S5); tests inject them. */
    private val teams: () -> List<TeamName> = { TeamNames.all(League.NHL) + TeamNames.all(League.NFL) },
) : GuideSearch {

    @Inject constructor(
        queries: GuideQueries,
        store: EpgStore,
        @IoDispatcher io: CoroutineDispatcher,
        events: EventIndex,
    ) : this(queries, store, io, events, EpgFiller::isFiller)

    override suspend fun search(query: String, nowMs: Long, access: ParentalAccess): SearchResults = withContext(io) {
        val q = query.trim()
        val noGuide = store.info.value == null
        if (q.count { it.isLetterOrDigit() } < MIN_CHARS) return@withContext SearchResults(q, emptyList(), emptyList(), noGuide)
        val folded = NameNormalizer.fold(q).trim()
        val channels = searchChannels(q, folded, access)
        coroutineContext.ensureActive()
        val nowMin = (nowMs / 60_000L).toInt()
        val guide = try {
            searchProgrammes(q, folded, nowMin, access)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "programme search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        coroutineContext.ensureActive()
        val evented = try {
            searchEvents(folded, nowMin, access)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "event search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        SearchResults(q, channels, merge(guide, evented), guideDataMissing = noGuide)
    }

    private suspend fun searchChannels(q: String, folded: String, access: ParentalAccess): List<GuideChannel> {
        val match = ChannelRepository.ftsQuery(q) ?: return emptyList()
        val hits = queries.searchChannels(match, access, CHANNEL_FTS_LIMIT)
        val ranked = hits.sortedWith(
            compareBy<GuideQueries.ChannelHit> {
                val name = NameNormalizer.fold(it.displayName).trim()
                when {
                    name == folded -> 0
                    name.startsWith(folded) -> 1
                    else -> 2
                }
            }
                .thenBy { if (it.favourite) 0 else 1 }
                .thenByDescending { it.watchCount }
                .thenBy { it.playlistSort }
                .thenBy { it.sortIndex },
        ).take(CHANNELS_KEPT)
        coroutineContext.ensureActive()
        if (ranked.isEmpty()) return emptyList()
        val ids = LongArray(ranked.size) { ranked[it].id }
        // (Event channels §7 step 4) Empty slots ("Sportsnet+ 11: NO EVENT") rank last; the order is otherwise kept.
        return queries.channels(ids, 0, ids.size).filterNotNull().sortedBy { if (it.event?.kind == ChannelEvent.Kind.EMPTY) 1 else 0 }
    }

    private enum class Bucket { ON_NOW, UPCOMING, ARCHIVE, LATER }

    private class Candidate(
        val hit: ProgrammeHit,
        val prefix: Boolean,
        val bucket: Bucket,
        /** Position of the hit's text in the reader's order (bm25 on FTS5), per list; events: after every guide text. */
        val textRank: Int,
    )

    /** The ranking shared by guide and event hits. */
    private val order: Comparator<Candidate> = compareBy<Candidate> { if (it.prefix) 0 else 1 }
        .thenBy { it.bucket.ordinal }
        .thenBy { it.textRank }
        .thenBy { if (it.bucket == Bucket.ARCHIVE) -it.hit.info.startMin else it.hit.info.startMin }

    /**
     * (Event channels §7 step 3, re-review E4) Drops an event hit when the same channel has a kept guide hit starting
     * within ± [EventConstants.EVENT_SEARCH_DEDUP_MIN] of it (the same event, however the listing spells it), then ranks
     * both lists together.
     */
    private fun merge(guide: List<Candidate>, evented: List<Candidate>): List<ProgrammeHit> {
        if (evented.isEmpty()) return guide.map { it.hit }
        val kept = evented.filter { e ->
            guide.none { g ->
                g.hit.channel.rowId == e.hit.channel.rowId &&
                    kotlin.math.abs(g.hit.info.startMin - e.hit.info.startMin) <= EventConstants.EVENT_SEARCH_DEDUP_MIN
            }
        }
        return (guide + kept).sortedWith(order).map { it.hit }
    }

    /**
     * (Event channels §7 step 2) Events that have not ended whose title or subtitle words are prefixed by every query
     * word (or one of its team places), visible to [access], at most [EventConstants.EVENT_SEARCH_LIMIT] in start order.
     */
    private suspend fun searchEvents(folded: String, nowMin: Int, access: ParentalAccess): List<Candidate> {
        val index = events ?: return emptyList()
        val terms = EventSearchTerms.of(folded, teams())
        if (terms.isEmpty()) return emptyList()
        val snapshot = index.current()
        coroutineContext.ensureActive()
        val matched = ArrayList<EventEntry>()
        for (e in snapshot.current(nowMin)) {
            val ev = e.event
            val title = ev.title
            if (ev.kind != ChannelEvent.Kind.EVENT || title == null) continue
            if (EventSearchTerms.matches(terms, EventSearchTerms.words(title) + EventSearchTerms.words(ev.subtitle ?: ""))) matched += e
        }
        if (matched.isEmpty()) return emptyList()
        val visible = queries.visible(matched.map { it.channelId }, access)
        val shown = matched.filter { it.channelId in visible }.sortedBy { it.event.startMin }.take(EventConstants.EVENT_SEARCH_LIMIT)
        coroutineContext.ensureActive()
        if (shown.isEmpty()) return emptyList()
        val ids = LongArray(shown.size) { shown[it].channelId }
        val loaded = queries.channels(ids, 0, ids.size).filterNotNull().associateBy { it.rowId }
        val out = ArrayList<Candidate>(shown.size)
        for (e in shown) {
            val ch = loaded[e.channelId] ?: continue
            val info = eventProgrammeInfo(e.event)
            val bucket = when {
                info.startMin <= nowMin -> Bucket.ON_NOW
                info.startMin < nowMin + 1440 -> Bucket.UPCOMING
                else -> Bucket.LATER
            }
            val title = NameNormalizer.fold(info.title).trim()
            out += Candidate(ProgrammeHit(ch, info, info.endMin, false), title == folded || title.startsWith(folded), bucket, Int.MAX_VALUE)
        }
        return out
    }

    private suspend fun searchProgrammes(q: String, folded: String, nowMin: Int, access: ParentalAccess): List<Candidate> {
        val reader = store.openReader() ?: return emptyList()
        val live: List<EpgSearchHit>
        val past: List<EpgSearchHit>
        try {
            live = reader.search(q, nowMin, LIVE_LIMIT, LIVE_PER_TEXT)
            coroutineContext.ensureActive()
            val days = minOf(PAST_DAYS, queries.archiveDaysAll(access))
            past = if (days > 0) reader.searchPast(q, nowMin, nowMin - days * 1440, PAST_LIMIT, PAST_PER_TEXT) else emptyList()
        } finally {
            reader.close()
        }
        coroutineContext.ensureActive()
        if (live.isEmpty() && past.isEmpty()) return emptyList()

        // Step 2: refs → library channels, confirmed by the guide's resolution rule.
        val refs = (live.asSequence() + past.asSequence()).map { it.ref }.toSet()
        val byRef = queries.channelsForRefs(refs, access)
        coroutineContext.ensureActive()
        val ids = byRef.values.flatten().distinct().toLongArray()
        val loaded = if (ids.isEmpty()) emptyMap() else queries.channels(ids, 0, ids.size).filterNotNull().associateBy { it.rowId }
        val confirmed = HashMap<EpgChannelRef, List<GuideChannel>>()
        for ((ref, list) in byRef) {
            confirmed[ref] = list.mapNotNull { loaded[it] }.filter { it.epg == ref }.take(PER_AIRING)
        }
        coroutineContext.ensureActive()

        // Step 3: display times, archive rule, buckets.
        val seen = HashSet<Long>()
        val out = ArrayList<Candidate>()
        fun add(list: List<EpgSearchHit>) {
            val textOrder = HashMap<String, Int>()
            for (h in list) {
                // (Event channels §7 step 1) Provider placeholders ("No Event Today", "Next Event: …") are never results.
                if (isFiller(h.title)) continue
                val rank = textOrder.getOrPut(h.title + '\u001F' + (h.subtitle ?: "")) { textOrder.size }
                for (ch in confirmed[h.ref].orEmpty()) {
                    if (!seen.add(Hash64.long(ch.identityHash, h.rawStartMin.toLong()))) continue
                    val start = h.rawStartMin + ch.epgShiftMin
                    val end = h.rawEndMin + ch.epgShiftMin
                    val ended = end <= nowMin
                    // (Review 2026-11-24, Low 9) The archive cutoff is on the raw start, as the player machine checks it.
                    val inArchive = ended && ch.archiveDays > 0 && h.rawStartMin >= nowMin - ch.archiveDays * 1440
                    if (ended && !inArchive) continue
                    val bucket = when {
                        ended -> Bucket.ARCHIVE
                        start <= nowMin -> Bucket.ON_NOW
                        start < nowMin + 1440 -> Bucket.UPCOMING
                        else -> Bucket.LATER
                    }
                    val title = NameNormalizer.fold(h.title).trim()
                    val info = ProgrammeInfo(
                        rawStartMin = h.rawStartMin, startMin = start, endMin = end, title = h.title, subtitle = h.subtitle,
                        description = null, genre = null, episodeLabel = null, flags = 0, titleHash = Hash64.of(h.title),
                    )
                    out += Candidate(ProgrammeHit(ch, info, h.rawEndMin, inArchive), title == folded || title.startsWith(folded), bucket, rank)
                }
            }
        }
        add(live)
        add(past)
        return out.sortedWith(order).take(PROGRAMMES_KEPT)
    }

    companion object {
        private const val TAG = "GuideSearch"
        /** Product S9: search starts at 2 letters/digits. */
        const val MIN_CHARS = 2
        const val CHANNEL_FTS_LIMIT = 100
        const val CHANNELS_KEPT = 30
        const val PROGRAMMES_KEPT = 50
        const val PER_AIRING = 3
        const val LIVE_LIMIT = 150
        const val LIVE_PER_TEXT = 8
        const val PAST_LIMIT = 100
        const val PAST_PER_TEXT = 5
        const val PAST_DAYS = 7
    }
}

/**
 * (Event channels, docs/design/m6-event-channels.md §7 step 2, U-E-S5) The query terms of event search. Each term is a
 * list of alternatives, each a list of folded words; a term matches when one alternative's words each prefix a word of
 * the event. A query run that equals a team's nickname or full name ("oilers", "golden knights", "edmonton oilers")
 * becomes one term whose alternatives are the run itself and each of the team's places ("edmonton"), so "oilers" finds
 * "Vegas @ Edmonton". Pure.
 */
internal object EventSearchTerms {
    /** Folded words: runs of letters and digits ("St. Louis" → st, louis; "Montréal" → montreal). */
    fun words(s: String): List<String> {
        val f = NameNormalizer.fold(s)
        val out = ArrayList<String>(4)
        var i = 0
        while (i < f.length) {
            while (i < f.length && !f[i].isLetterOrDigit()) i++
            val st = i
            while (i < f.length && f[i].isLetterOrDigit()) i++
            if (i > st) out += f.substring(st, i)
        }
        return out
    }

    fun of(folded: String, teams: List<TeamName>): List<List<List<String>>> {
        val q = words(folded)
        if (q.isEmpty()) return emptyList()
        // Each team name as words, with the team's places; longest names first so "golden knights" beats "knights".
        val names = ArrayList<Pair<List<String>, List<List<String>>>>()
        for (t in teams) {
            val places = t.places.map { words(it) }.filter { it.isNotEmpty() }
            if (places.isEmpty()) continue
            for (n in t.fullNames + t.nicknames) words(n).takeIf { it.isNotEmpty() }?.let { names += it to places }
        }
        names.sortByDescending { it.first.size }
        val out = ArrayList<List<List<String>>>()
        var i = 0
        while (i < q.size) {
            var hit: Pair<List<String>, List<List<String>>>? = null
            for (n in names) {
                val w = n.first
                if (i + w.size <= q.size && (w.indices).all { q[i + it] == w[it] }) { hit = n; break }
            }
            if (hit != null) {
                val run = q.subList(i, i + hit.first.size).toList()
                // Every team that shares this name ("Panthers", "Kings") adds its places.
                val alts = LinkedHashSet<List<String>>().apply {
                    add(run)
                    for (n in names) if (n.first == run) addAll(n.second)
                }
                out += alts.toList()
                i += run.size
            } else {
                out += listOf(listOf(q[i]))
                i++
            }
        }
        return out
    }

    fun matches(terms: List<List<List<String>>>, eventWords: List<String>): Boolean = terms.all { alts ->
        alts.any { alt -> alt.all { w -> eventWords.any { it.startsWith(w) } } }
    }
}
