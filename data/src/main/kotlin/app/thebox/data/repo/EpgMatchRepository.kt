package app.thebox.data.repo

import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.legacyUnlocked
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.match.EpgMatcher
import app.thebox.core.match.MatchCandidate
import app.thebox.core.match.MatchMethod
import app.thebox.core.match.MatchResult
import app.thebox.core.match.MatchSettings
import app.thebox.core.match.MatchStatus
import app.thebox.core.match.MatchSubject
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.EpgMappingDao
import app.thebox.data.db.dao.EpgMatchDao
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.MatchCounts
import app.thebox.data.db.dao.SubjectRow
import app.thebox.data.db.dao.UnmatchedRow
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matching persistence and the importer's whitelist (docs/design/m1-data-layer.md §3.6.5).
 *
 * [whitelistFor] is the `MatcherWhitelistResolver`: called by the EPG importer once per source, it matches the
 * source's subjects against the source's `<channel>` list, rewrites that source's `epg_match` rows (per
 * subject, transactions of 2,000 subjects) and returns
 * `autoIds ∪ overrideIds ∪ subjectTvgIds (+ case-insensitive, + iptv-org base ids)`.
 */
@Singleton
class EpgMatchRepository @Inject constructor(
    private val db: LibraryDatabase,
    private val channelDao: ChannelDao,
    private val sourceDao: EpgSourceDao,
    private val mappingDao: EpgMappingDao,
    private val matchDao: EpgMatchDao,
    private val settings: DataSettings,
    private val requester: EpgWorkRequester,
    private val clock: WallClock,
) : EpgWhitelistResolver {

    /** Blocking; runs on the importer thread inside `EpgStore.build`. */
    override fun whitelistFor(sourceId: Long, channels: Sequence<EpgChannelInfo>): EpgWhitelist = runBlocking {
        val source = sourceDao.get(sourceId)
        if (source == null) {
            BoxLog.w(TAG, "whitelist for unknown source $sourceId: nothing imported")
            return@runBlocking EpgWhitelist.NONE
        }
        val subjects = subjectsFor(source)
        val overrides = overrides(subjects)
        val match = settings.current.match
        // The channels streamed here come from the generation being built, where programme_count is still 0
        // (repair has not run). Keep the published generation's has_data until afterBuild sets the new one;
        // otherwise every match reads "no data" during a build and after a failed one (code review M1, S-9).
        val withData = matchDao.xmltvIdsWithData(sourceId).toHashSet()
        val results = if (subjects.isEmpty()) {
            channels.forEach { } // drain: the importer may expect the sequence to be consumed
            emptyList()
        } else {
            val matcher = EpgMatcher(match)
            matcher.matchSource(matcher.prepare(subjects.map { it.toSubject() }), sourceId, channels.onEach { if (it.programmeCount > 0) withData += it.xmltvId }, overrides)
        }
        persist(sourceId, subjects, results, withData, match)

        val ids = HashSet<String>()
        for (r in results) if (r.status == MatchStatus.AUTO) r.best?.let { ids += it.ref.xmltvId }
        for ((_, ref) in overrides) if (ref != null && ref.sourceId == sourceId) ids += ref.xmltvId
        val lower = HashSet<String>()
        val bases = HashSet<String>()
        for (s in subjects) s.tvgId?.takeIf { it.isNotBlank() }?.let { id ->
            ids += id
            val l = id.lowercase(Locale.ROOT)
            lower += l
            bases += EpgMatcher.baseOf(l)
        }
        val caseInsensitive = match.caseInsensitiveIds
        BoxLog.i(TAG) { "source $sourceId: ${subjects.size} subjects, ${results.count { it.status == MatchStatus.AUTO }} auto, whitelist ${ids.size}" }
        EpgWhitelist { id ->
            id in ids || (caseInsensitive && id.lowercase(Locale.ROOT) in lower) || EpgMatcher.baseOf(id.lowercase(Locale.ROOT)) in bases
        }
    }

    /**
     * After a build (§3.6.5): sets `has_data` from the new generation, re-matches subjects without an AUTO row
     * against every channel of each source (catches late `<channel>` elements), and returns the refs that need an
     * `epg-patch` (AUTO or override refs whose EPG channel has `imported = 0`).
     */
    suspend fun afterBuild(store: EpgStore): List<EpgChannelRef> {
        val reader = store.openReader() ?: return emptyList()
        reader.use { r ->
            val notImported = HashSet<String>()
            for (source in sourceDao.active()) {
                val channels = channelsOf(r, source.id)
                val withData = HashSet<String>()
                for (c in channels) {
                    if (c.programmeCount > 0) withData += c.xmltvId
                    if (!c.imported) notImported += key(source.id, c.xmltvId)
                }
                updateHasData(source.id, withData)
                rematchUnmatched(source, channels, withData)
            }
            return pendingRefs(notImported)
        }
    }

    /** After a patch: has_data only. */
    suspend fun refreshHasData(store: EpgStore) {
        val reader = store.openReader() ?: return
        reader.use { r ->
            for (source in sourceDao.active()) {
                val withData = HashSet<String>()
                r.forEachChannel(source.id) { if (it.programmeCount > 0) withData += it.xmltvId }
                updateHasData(source.id, withData)
            }
        }
    }

    /** Refs an `epg-patch` should import now (no ids travel in WorkManager Data). */
    suspend fun pendingPatchRefs(store: EpgStore): List<EpgChannelRef> {
        val reader = store.openReader() ?: return emptyList()
        reader.use { r ->
            val notImported = HashSet<String>()
            r.forEachChannel(null) { if (!it.imported) notImported += key(it.sourceId, it.xmltvId) }
            return pendingRefs(notImported)
        }
    }

    /**
     * After a playlist refresh: matches only subjects that are new or whose `inputs_hash` changed, against the
     * current generation. Returns true when an `epg-patch` is needed.
     */
    suspend fun matchIncremental(playlistId: Long, store: EpgStore): Boolean {
        val reader = store.openReader() ?: return false
        reader.use { r ->
            val match = settings.current.match
            val known = matchDao.subjectHashes(playlistId).associate { it.stableKey to it.inputsHash }
            val changed = channelDao.subjects(playlistId).filter { known[it.stableKey] != inputsHash(it, match) }
            if (changed.isEmpty()) return false
            val notImported = HashSet<String>()
            val overrides = overrides(changed)
            for (source in sourceDao.active()) {
                if (source.playlistId != null && source.playlistId != playlistId) continue
                val channels = channelsOf(r, source.id)
                val withData = HashSet<String>()
                for (c in channels) {
                    if (c.programmeCount > 0) withData += c.xmltvId
                    if (!c.imported) notImported += key(source.id, c.xmltvId)
                }
                val matcher = EpgMatcher(match)
                val results = matcher.matchSource(matcher.prepare(changed.map { it.toSubject() }), source.id, channels.asSequence(), overrides)
                persist(source.id, changed, results, withData, match)
            }
            BoxLog.i(TAG) { "incremental match playlist=$playlistId subjects=${changed.size}" }
            return pendingRefs(notImported).isNotEmpty()
        }
    }

    // ---------------------------------------------------------------- overrides and queries

    /** Explicit mapping; [ref] null = "no guide data". A new ref triggers a patch for it. */
    suspend fun setOverride(channel: ChannelIdentity, ref: EpgChannelRef?) {
        mappingDao.upsert(EpgMappingEntity(channel.playlistId, channel.stableKey.value, ref?.sourceId, ref?.xmltvId, clock.now()))
        if (ref != null) requester.patchEpg()
    }

    suspend fun clearOverride(channel: ChannelIdentity) = mappingDao.delete(channel.playlistId, channel.stableKey.value)

    suspend fun override(channel: ChannelIdentity): EpgMappingEntity? = mappingDao.get(channel.playlistId, channel.stableKey.value)

    /** (M5 integration) [access] through WP-C's §4.3 predicate (`ChannelDao.unmatchedAccess`). */
    fun unmatched(access: ParentalAccess): Flow<List<UnmatchedRow>> = channelDao.unmatchedAccess(access.ordinal)
    fun matchedWithoutData(unlocked: Boolean): Flow<List<UnmatchedRow>> = channelDao.matchedWithoutData(unlocked)
    suspend fun matchCounts(playlistId: Long): MatchCounts = channelDao.matchCounts(playlistId)
    suspend fun matches(channel: ChannelIdentity): List<EpgMatchEntity> = matchDao.forSubject(channel.playlistId, channel.stableKey.value)

    /** The S13 picker: brute-force candidates over every EPG channel of the current generation. */
    suspend fun suggest(channel: ChannelIdentity, store: EpgStore, limit: Int = 10): List<MatchCandidate> {
        val row = channelDao.get(channel.playlistId, channel.stableKey.value) ?: return emptyList()
        val reader = store.openReader() ?: return emptyList()
        val all = ArrayList<EpgChannelInfo>()
        reader.use { it.forEachChannel(null) { c -> all += c } }
        return EpgMatcher(settings.current.match).suggest(
            MatchSubject(channel, row.name, row.tvgId, row.tvgName, null), all.asSequence(), limit,
        )
    }

    // ---------------------------------------------------------------- internals

    private suspend fun subjectsFor(source: EpgSourceEntity): List<SubjectRow> =
        source.playlistId?.let { channelDao.subjects(it) } ?: channelDao.allSubjects()

    private suspend fun overrides(subjects: List<SubjectRow>): Map<ChannelIdentity, EpgChannelRef?> {
        if (subjects.isEmpty()) return emptyMap()
        val wanted = subjects.mapTo(HashSet()) { it.playlistId to it.stableKey }
        val out = HashMap<ChannelIdentity, EpgChannelRef?>()
        for (m in mappingDao.all()) {
            if ((m.playlistId to m.stableKey) !in wanted) continue
            val ref = if (m.sourceId != null && m.xmltvId != null) EpgChannelRef(m.sourceId, m.xmltvId) else null
            out[ChannelIdentity(m.playlistId, StableKey(m.stableKey))] = ref
        }
        return out
    }

    private fun channelsOf(reader: EpgReader, sourceId: Long): List<EpgChannelInfo> {
        val list = ArrayList<EpgChannelInfo>()
        reader.forEachChannel(sourceId) { list += it }
        return list
    }

    private suspend fun rematchUnmatched(source: EpgSourceEntity, channels: List<EpgChannelInfo>, withData: Set<String>) {
        val subjects = subjectsFor(source)
        if (subjects.isEmpty()) return
        val auto = matchDao.autosForSource(source.id).mapTo(HashSet()) { it.playlistId to it.stableKey }
        val mapped = mappingDao.all().mapTo(HashSet()) { it.playlistId to it.stableKey }
        val todo = subjects.filter { (it.playlistId to it.stableKey) !in auto && (it.playlistId to it.stableKey) !in mapped }
        if (todo.isEmpty()) return
        val match = settings.current.match
        val matcher = EpgMatcher(match)
        val results = matcher.matchSource(matcher.prepare(todo.map { it.toSubject() }), source.id, channels.asSequence())
        persist(source.id, todo, results, withData, match)
    }

    private suspend fun pendingRefs(notImported: Set<String>): List<EpgChannelRef> {
        if (notImported.isEmpty()) return emptyList()
        val refs = LinkedHashSet<EpgChannelRef>()
        for (m in matchDao.autos()) if (key(m.sourceId, m.xmltvId) in notImported) refs += EpgChannelRef(m.sourceId, m.xmltvId)
        for (m in mappingDao.all()) {
            val s = m.sourceId ?: continue
            val x = m.xmltvId ?: continue
            if (key(s, x) in notImported) refs += EpgChannelRef(s, x)
        }
        return refs.toList()
    }

    /** Writes only the rows whose flag changes (performance review M1: an unchanged guide rewrote every row twice). */
    private suspend fun updateHasData(sourceId: Long, withData: Set<String>) {
        db.writeTransaction {
            val had = HashSet<String>()
            usePrepared("SELECT DISTINCT xmltv_id FROM epg_match WHERE source_id = ? AND has_data = 1") { st ->
                st.bindLong(1, sourceId)
                while (st.step()) had += st.getText(0)
            }
            forEachBound("UPDATE epg_match SET has_data = 0 WHERE source_id = ? AND xmltv_id = ? AND has_data = 1", had.filter { it !in withData }) {
                bindLong(1, sourceId); bindText(2, it)
            }
            forEachBound("UPDATE epg_match SET has_data = 1 WHERE source_id = ? AND xmltv_id = ? AND has_data = 0", withData) {
                bindLong(1, sourceId); bindText(2, it)
            }
        }
    }

    /** Replaces the rows of each subject for [sourceId] (AUTO best, or SUGGESTED best + alternatives). */
    private suspend fun persist(sourceId: Long, subjects: List<SubjectRow>, results: List<MatchResult>, withData: Set<String>, match: MatchSettings) {
        if (subjects.isEmpty()) return
        val now = clock.now()
        for (chunk in subjects.indices.chunked(BATCH)) {
            db.writeTransaction {
                forEachBound("DELETE FROM epg_match WHERE playlist_id = ? AND stable_key = ? AND source_id = ?", chunk) { i ->
                    bindLong(1, subjects[i].playlistId); bindText(2, subjects[i].stableKey); bindLong(3, sourceId)
                }
                val rows = ArrayList<Pair<Int, Pair<MatchCandidate, Int>>>()
                for (i in chunk) {
                    val r = results.getOrNull(i) ?: continue
                    val best = r.best ?: continue
                    if (best.method == MatchMethod.OVERRIDE) continue // epg_mapping is the source of truth
                    rows += i to (best to if (r.status == MatchStatus.AUTO) EpgMatchEntity.STATUS_AUTO else EpgMatchEntity.STATUS_SUGGESTED)
                    for (s in r.suggestions) rows += i to (s to EpgMatchEntity.STATUS_SUGGESTED)
                }
                forEachBound(
                    """
                    INSERT OR REPLACE INTO epg_match(playlist_id, stable_key, source_id, xmltv_id, status, method, score, has_data, inputs_hash, matched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    rows,
                ) { (i, cs) ->
                    val (c, status) = cs
                    val s = subjects[i]
                    bindLong(1, s.playlistId); bindText(2, s.stableKey); bindLong(3, sourceId); bindText(4, c.ref.xmltvId)
                    bindLong(5, status.toLong()); bindText(6, c.method.name); bindDouble(7, c.score.toDouble())
                    bindBool(8, c.ref.xmltvId in withData); bindLong(9, inputsHash(s, match)); bindLong(10, now)
                }
            }
        }
    }

    companion object {
        private const val TAG = "EpgMatchRepository"
        private const val BATCH = 2_000

        /** Hash64(tvg_id, tvg_name, name, settings.level, MATCHER_VERSION) (§3.6.5). */
        fun inputsHash(s: SubjectRow, match: MatchSettings): Long {
            var h = Hash64.str(Hash64.OFFSET, s.tvgId)
            h = Hash64.str(h, s.tvgName)
            h = Hash64.str(h, s.name)
            h = Hash64.int(h, match.level.ordinal)
            return Hash64.int(h, EpgMatcher.VERSION)
        }

        private fun key(sourceId: Long, xmltvId: String) = "$sourceId\u001F$xmltvId"

        private fun SubjectRow.toSubject() = MatchSubject(ChannelIdentity(playlistId, StableKey(stableKey)), name, tvgId, tvgName, null)
    }
}
