package app.thebox.data.guide

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgStore
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
 * filtered in SQL unless [search]'s `unlocked` is set (PIN session, or `GuideSettings.debugUnlocked` in debug builds);
 * ended hits survive only inside the channel's archive. Cancellable between stages.
 */
@Singleton
class GuideSearchImpl @Inject constructor(
    private val queries: GuideQueries,
    private val store: EpgStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GuideSearch {

    override suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults = withContext(io) {
        val q = query.trim()
        if (q.count { it.isLetterOrDigit() } < MIN_CHARS) return@withContext SearchResults(q, emptyList(), emptyList())
        val folded = NameNormalizer.fold(q).trim()
        val channels = searchChannels(q, folded, unlocked)
        coroutineContext.ensureActive()
        val programmes = try {
            searchProgrammes(q, folded, (nowMs / 60_000L).toInt(), unlocked)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "programme search failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        SearchResults(q, channels, programmes)
    }

    private suspend fun searchChannels(q: String, folded: String, unlocked: Boolean): List<GuideChannel> {
        val match = ChannelRepository.ftsQuery(q) ?: return emptyList()
        val hits = queries.searchChannels(match, unlocked, CHANNEL_FTS_LIMIT)
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
        return queries.channels(ids, 0, ids.size).filterNotNull()
    }

    private enum class Bucket { ON_NOW, UPCOMING, ARCHIVE, LATER }

    private class Candidate(
        val hit: ProgrammeHit,
        val prefix: Boolean,
        val bucket: Bucket,
        /** Position of the hit's text in the reader's order (bm25 on FTS5), per list. */
        val textRank: Int,
    )

    private suspend fun searchProgrammes(q: String, folded: String, nowMin: Int, unlocked: Boolean): List<ProgrammeHit> {
        val reader = store.openReader() ?: return emptyList()
        val live: List<EpgSearchHit>
        val past: List<EpgSearchHit>
        try {
            live = reader.search(q, nowMin, LIVE_LIMIT, LIVE_PER_TEXT)
            coroutineContext.ensureActive()
            val days = minOf(PAST_DAYS, queries.archiveDaysAll(unlocked))
            past = if (days > 0) reader.searchPast(q, nowMin, nowMin - days * 1440, PAST_LIMIT, PAST_PER_TEXT) else emptyList()
        } finally {
            reader.close()
        }
        coroutineContext.ensureActive()
        if (live.isEmpty() && past.isEmpty()) return emptyList()

        // Step 2: refs → library channels, confirmed by the guide's resolution rule.
        val refs = (live.asSequence() + past.asSequence()).map { it.ref }.toSet()
        val byRef = queries.channelsForRefs(refs, unlocked)
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
                val rank = textOrder.getOrPut(h.title + '\u001F' + (h.subtitle ?: "")) { textOrder.size }
                for (ch in confirmed[h.ref].orEmpty()) {
                    if (!seen.add(Hash64.long(ch.identityHash, h.rawStartMin.toLong()))) continue
                    val start = h.rawStartMin + ch.epgShiftMin
                    val end = h.rawEndMin + ch.epgShiftMin
                    val ended = end <= nowMin
                    val inArchive = ended && ch.archiveDays > 0 && start >= nowMin - ch.archiveDays * 1440
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
        return out.sortedWith(
            compareBy<Candidate> { if (it.prefix) 0 else 1 }
                .thenBy { it.bucket.ordinal }
                .thenBy { it.textRank }
                .thenBy { if (it.bucket == Bucket.ARCHIVE) -it.hit.info.startMin else it.hit.info.startMin },
        ).take(PROGRAMMES_KEPT).map { it.hit }
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
