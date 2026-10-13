package app.thebox.data.repo

import app.thebox.core.common.BoxLog
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.ImportGate
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.FreeSpace
import app.thebox.core.match.MatchMethod
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.db.Converters
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.EpgMappingDao
import app.thebox.data.db.dao.EpgMatchDao
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.epg.EpgRecovery
import app.thebox.data.net.NetErrors
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Single writer for epg.db build/patch/clear (§4.6). Lock order: ImportMutex → EpgWriteLock. */
@Singleton
class EpgWriteLock @Inject constructor() {
    val mutex = Mutex()
}

/** The guide's view of one channel's EPG (§2.2). [ref] null = no guide data (explicit or unmatched). */
data class ResolvedEpg(val ref: EpgChannelRef?, val shiftMin: Int, val method: MatchMethod?, val hasData: Boolean)

/**
 * EPG sources and import orchestration (§3.10 EpgImportWorker, §3.6.5 after-build steps, §2.2).
 *
 * build: conditional GET per active source to `cacheDir/epg/<id>.new` → skip when nothing changed and a
 * generation exists → `EpgStore.build` under ImportMutex + EpgWriteLock with [EpgMatchRepository] as the
 * whitelist → retry once with the last good `.src` when a source failed → promote `.new` to `.src` and
 * persist validators/counts (only now, S10) → has_data, late-channel matching, patch enqueue → refresh_log.
 */
@Singleton
class EpgRepository @Inject constructor(
    private val store: EpgStore,
    private val recovery: EpgRecovery,
    private val sourceDao: EpgSourceDao,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val mappingDao: EpgMappingDao,
    private val matchDao: EpgMatchDao,
    private val logDao: RefreshLogDao,
    private val playlists: PlaylistRepository,
    private val matches: EpgMatchRepository,
    private val fetcher: SourceFetcher,
    private val settings: DataSettings,
    private val importMutex: ImportMutex,
    private val writeLock: EpgWriteLock,
    private val requester: EpgWorkRequester,
    private val freeSpace: FreeSpace,
    @param:Named(CACHE_DIR) private val cacheDir: File,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
) : EpgImporter {
    /** The zap gate (amended 2026-10-13): delegates to [ImportMutex.gate], shared with the playlist merge. */
    var gate: ImportGate
        get() = importMutex.gate
        set(value) { importMutex.gate = value }

    fun sources(): Flow<List<EpgSourceEntity>> = sourceDao.observeAll()

    /** A user-added XMLTV source (applies to every playlist); priority after the existing ones. */
    suspend fun addUserSource(name: String, url: String, secretHeaders: Map<String, String> = emptyMap()): Outcome<Long> {
        val ref = playlists.newRef()
        when (val r = playlists.putSecret(ref, PlaylistSecret(listOf(url), headers = secretHeaders))) {
            is Outcome.Err -> return r
            is Outcome.Ok -> Unit
        }
        val id = sourceDao.insert(
            EpgSourceEntity(
                name = name, origin = EpgSourceEntity.ORIGIN_USER, playlistId = null, ordinal = 0, credentialRef = ref,
                urlHash = Hash64.of(Redact.text(url)), displaySource = PlaylistRepository.displayHost(url),
                priority = sourceDao.maxPriority() + 1, createdAt = clock.now(),
            ),
        )
        requester.importEpgNow(Trigger.MANUAL)
        return Outcome.Ok(id)
    }

    suspend fun removeSource(id: Long) {
        sourceDao.get(id)?.let { playlists.deleteSecret(it.credentialRef) }
        sourceDao.delete(id)
        File(cacheDir, "$id.src").delete()
        File(cacheDir, "$id.new").delete()
    }

    suspend fun setSourceEnabled(id: Long, enabled: Boolean) {
        sourceDao.get(id)?.let { sourceDao.update(it.copy(enabled = enabled)) }
    }

    suspend fun setSourceShift(id: Long, shiftMin: Int) {
        sourceDao.get(id)?.let { sourceDao.update(it.copy(shiftMin = shiftMin)) }
    }

    /** Reorders sources (first = highest priority). */
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { i, id -> sourceDao.get(id)?.let { sourceDao.update(it.copy(priority = i)) } }
    }

    /** Built-at of the current generation, or null (none yet, or the store is not available). */
    fun generationBuiltAt(): Long? = try {
        store.info.value?.builtAtMs
    } catch (e: NotImplementedError) {
        null // WP-B stub on a branch without WP-B
    }

    /**
     * Serialises whole build/patch runs, downloads and `.new` → `.src` promotion included. `epg-periodic` and
     * `epg-import` are different unique works and both run on the first app start after a source is added (a new
     * periodic work runs at once); without this they download into the same `<id>.new.part`, and a patch can
     * read `<id>.src` between a build's delete and rename (code review M1, S-2). Taken before ImportMutex.
     */
    private val runLock = Mutex()

    override suspend fun build(trigger: Trigger, force: Boolean, progress: (String) -> Unit): EpgRunResult =
        runLock.withLock { buildLocked(trigger, force, progress) }

    private suspend fun buildLocked(trigger: Trigger, force: Boolean, progress: (String) -> Unit): EpgRunResult {
        recovery.await()
        val started = clock.now()
        val sources = sourceDao.active()
        if (sources.isEmpty()) return EpgRunResult(null, null, skipped = true)
        val dir = cacheDir.apply { mkdirs() }

        // 1. Downloads (conditional when a last-good file exists).
        class Picked(val source: EpgSourceEntity, val file: File, val fresh: Boolean, val etag: String?, val lastModified: String?, val bytes: Long)
        val picked = ArrayList<Picked>()
        var anyChanged = false
        var lastError: SourceError? = null
        var transientLoss: SourceError? = null
        for ((i, s) in sources.withIndex()) {
            progress("Downloading guide ${i + 1} of ${sources.size}")
            val src = File(dir, "${s.id}.src")
            val next = File(dir, "${s.id}.new")
            val secret = when (val r = playlists.secret(s.credentialRef)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> { lastError = r.error; if (src.exists()) picked += Picked(s, src, false, s.etag, s.lastModified, src.length()); continue }
            }
            val url = secret.urls.firstOrNull() ?: continue
            val haveSrc = src.exists()
            val res = fetcher.fetch(
                FetchRequest(
                    url = url, headers = secret.headers,
                    etag = if (haveSrc) s.etag else null, lastModified = if (haveSrc) s.lastModified else null,
                ),
                next,
            )
            when (res) {
                is Outcome.Ok -> if (res.value.notModified && haveSrc) {
                    picked += Picked(s, src, false, res.value.etag, res.value.lastModified, src.length())
                } else {
                    anyChanged = true
                    picked += Picked(s, next, true, res.value.etag, res.value.lastModified, res.value.bytes)
                }
                is Outcome.Err -> {
                    lastError = res.error
                    sourceDao.setStatus(s.id, res.error.code, clock.now())
                    BoxLog.w(TAG, "source ${s.id} download failed: ${res.error.code}")
                    if (haveSrc) {
                        picked += Picked(s, src, false, s.etag, s.lastModified, src.length())
                    } else if (NetErrors.isTransient(res.error) && s.programmeCount > 0) {
                        // No last-good file (cache policy dropped it) and the guide has this source's data: a
                        // rebuild now would publish without it. Keep the current generation and retry later
                        // (code review M1, S-10).
                        transientLoss = res.error
                    }
                }
            }
        }
        transientLoss?.let { if (generationBuiltAt() != null) return finishBuild(started, trigger, null, it) }
        if (picked.isEmpty()) return finishBuild(started, trigger, null, lastError ?: SourceError.Internal("no source available"))
        if (!anyChanged && !force && generationBuiltAt() != null) {
            BoxLog.i(TAG) { "all sources not modified: build skipped" }
            return EpgRunResult(null, null, skipped = true)
        }

        // 2. Build under ImportMutex → EpgWriteLock.
        val s = settings.current
        fun request(files: List<Picked>) = EpgBuildRequest(
            sources = files.map { EpgSourceFile(it.source.id, it.file, it.source.assumeZone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() }) },
            nowMs = clock.now(), pastDays = s.epgPastDays, futureDays = s.epgFutureDays,
            storeDescriptions = s.storeDescriptions, maxDescriptionChars = s.maxDescriptionChars, preferredLanguages = s.preferredLanguages,
        )
        val cancel = cancelCheck()
        var report: EpgImportReport
        try {
            report = locked { store.build(request(picked), matches, gate, cancel) { p -> progress("Building guide: ${p.programmesKept} programmes") } }
            val failed = report.failedSourceId
            if (failed != null) {
                // §3.5.6: fall back to the last good file for the failed source, once.
                val idx = picked.indexOfFirst { it.source.id == failed && it.fresh }
                val src = File(dir, "$failed.src")
                if (idx >= 0 && src.exists()) {
                    BoxLog.w(TAG, "source $failed failed; retrying with the last good file")
                    picked[idx] = Picked(picked[idx].source, src, false, picked[idx].source.etag, picked[idx].source.lastModified, src.length())
                    report = locked { store.build(request(picked), matches, gate, cancel) { p -> progress("Building guide: ${p.programmesKept} programmes") } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            return finishBuild(started, trigger, null, e.error)
        } catch (e: NotImplementedError) {
            return finishBuild(started, trigger, null, SourceError.Unsupported("EPG store not available in this build"))
        } catch (e: Exception) {
            // SQLite (disk full, I/O) and other runtime errors: log a FAILED row instead of escaping the worker
            // with no refresh_log entry (code review M1, S-13).
            BoxLog.e(TAG, "EPG build crashed", e)
            return finishBuild(started, trigger, null, SourceError.Internal(e.javaClass.simpleName))
        }
        if (report.failedSourceId != null) return finishBuild(started, trigger, report, SourceError.Parse("source ${report.failedSourceId} failed"))

        // 3. Promote downloads and persist validators/counts (only after a successful build, S10).
        val stats = report.sources.associateBy { it.sourceId }
        for (p in picked) {
            val src = File(dir, "${p.source.id}.src")
            if (p.fresh) {
                src.delete()
                p.file.renameTo(src)
            }
            val st = stats[p.source.id]
            sourceDao.setImported(
                p.source.id, p.etag, p.lastModified, src.path, src.length(), clock.now(),
                st?.errorCode ?: RefreshOutcome.SUCCESS.name, st?.channels ?: 0, st?.programmesKept ?: 0,
            )
        }
        enforceCachePolicy(picked.map { it.source.id })

        // 4. has_data, late channels, patch.
        progress("Matching channels")
        val pending = matches.afterBuild(store)
        if (pending.isNotEmpty()) requester.patchEpg()
        return finishBuild(started, trigger, report, null)
    }

    override suspend fun patch(progress: (String) -> Unit): EpgRunResult = runLock.withLock { patchLocked(progress) }

    private suspend fun patchLocked(progress: (String) -> Unit): EpgRunResult {
        recovery.await()
        val started = clock.now()
        val refs = try { matches.pendingPatchRefs(store) } catch (e: NotImplementedError) { emptyList() }
        if (refs.isEmpty()) return EpgRunResult(null, null, skipped = true)
        val bySource = refs.map { it.sourceId }.toSet()
        val files = sourceDao.active().filter { it.id in bySource }.mapNotNull { s ->
            File(cacheDir, "${s.id}.src").takeIf { it.exists() }?.let { EpgSourceFile(s.id, it, s.assumeZone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() }) }
        }
        if (files.isEmpty()) return EpgRunResult(null, null, skipped = true)
        val s = settings.current
        val request = EpgPatchRequest(
            sources = files, refs = refs.filter { r -> files.any { it.sourceId == r.sourceId } }, nowMs = clock.now(),
            pastDays = s.epgPastDays, futureDays = s.epgFutureDays, storeDescriptions = s.storeDescriptions,
            maxDescriptionChars = s.maxDescriptionChars, preferredLanguages = s.preferredLanguages,
        )
        val cancel = cancelCheck()
        val result = try {
            val report = locked { store.patch(request, gate, cancel) { p -> progress("Adding guide data: ${p.programmesKept} programmes") } }
            matches.refreshHasData(store)
            EpgRunResult(report, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            EpgRunResult(null, e.error)
        } catch (e: Exception) {
            BoxLog.e(TAG, "EPG patch crashed", e) // S-13
            EpgRunResult(null, SourceError.Internal(e.javaClass.simpleName))
        }
        log(RefreshLogEntity.TARGET_EPG_PATCH, Trigger.MAPPING, started, result.report, result.error)
        return result
    }

    /** Settings → "Rebuild guide data". */
    suspend fun clear() {
        importMutex.withLock { writeLock.mutex.withLock { withContext(io) { store.clear() } } }
    }

    /**
     * §2.2: per channel of [playlistIds], the explicit mapping, else the first AUTO match with data in source
     * priority order, else the first AUTO match without data; plus the total guide shift
     * `source.shift_min + (prefs ?: channel tvg-shift ?: header tvg-shift ?: 0) + global`.
     */
    suspend fun resolvedRefs(playlistIds: List<Long>): Map<ChannelIdentity, ResolvedEpg> {
        if (playlistIds.isEmpty()) return emptyMap()
        val sources = sourceDao.active()
        val rank = sources.withIndex().associate { (i, s) -> s.id to i }
        val sourceById = sources.associateBy { it.id }
        val wanted = playlistIds.toSet()
        val mappings = mappingDao.all().filter { it.playlistId in wanted }.associateBy { it.playlistId to it.stableKey }
        val autos = matchDao.autos().filter { it.playlistId in wanted }.groupBy { it.playlistId to it.stableKey }
        val headerShift = playlistIds.associateWith { id ->
            playlistDao.get(id)?.let { PlaylistRepository.header(it)?.tvgShiftHours }?.let { (it * 60).roundToInt() }
        }
        val global = settings.current.globalShiftMin
        val out = HashMap<ChannelIdentity, ResolvedEpg>()
        for (row in channelDao.shiftInputs(playlistIds)) {
            val k = row.playlistId to row.stableKey
            val channelShift = row.prefShiftMin ?: row.channelShiftMin ?: headerShift[row.playlistId] ?: 0
            val identity = ChannelIdentity(row.playlistId, StableKey(row.stableKey))
            val mapping = mappings[k]
            if (mapping != null) {
                val ref = if (mapping.sourceId != null && mapping.xmltvId != null) EpgChannelRef(mapping.sourceId, mapping.xmltvId) else null
                val srcShift = ref?.let { sourceById[it.sourceId]?.shiftMin } ?: 0
                out[identity] = ResolvedEpg(ref, srcShift + channelShift + global, MatchMethod.OVERRIDE, ref != null)
                continue
            }
            val candidates = autos[k].orEmpty()
                .filter { it.sourceId in rank && (sourceById[it.sourceId]?.playlistId ?: row.playlistId) == row.playlistId }
                .sortedBy { rank.getValue(it.sourceId) }
            val pick = candidates.firstOrNull { it.hasData } ?: candidates.firstOrNull()
            out[identity] = if (pick == null) {
                ResolvedEpg(null, channelShift + global, null, false)
            } else {
                ResolvedEpg(
                    EpgChannelRef(pick.sourceId, pick.xmltvId),
                    (sourceById[pick.sourceId]?.shiftMin ?: 0) + channelShift + global,
                    runCatching { MatchMethod.valueOf(pick.method) }.getOrNull(),
                    pick.hasData,
                )
            }
        }
        return out
    }

    private suspend fun <T> locked(block: () -> T): T =
        importMutex.withLock { writeLock.mutex.withLock { withContext(io) { withBackgroundPriority(block) } } }

    private suspend fun cancelCheck(): CancelCheck {
        val ctx = coroutineContext
        return CancelCheck { !ctx.isActive }
    }

    private suspend fun enforceCachePolicy(ids: List<Long>) {
        val drop = when (settings.current.keepCachedXmltv) {
            KeepCachedXmltv.ALWAYS -> false
            KeepCachedXmltv.NEVER -> true
            KeepCachedXmltv.IF_FREE_OVER_1GB -> freeSpace.bytesFree(cacheDir) < ONE_GB
        }
        if (!drop) return
        // Without the cached file, patches need a full rebuild: dropping only trades disk for network.
        for (id in ids) File(cacheDir, "$id.src").delete()
        BoxLog.i(TAG) { "cached XMLTV dropped (policy)" }
    }

    private suspend fun finishBuild(started: Long, trigger: Trigger, report: EpgImportReport?, error: SourceError?): EpgRunResult {
        log(RefreshLogEntity.TARGET_EPG_BUILD, trigger, started, report, error)
        return EpgRunResult(report, error)
    }

    private suspend fun log(type: String, trigger: Trigger, started: Long, report: EpgImportReport?, error: SourceError?) {
        logDao.insert(
            RefreshLogEntity(
                targetType = type, targetId = null, trigger = trigger.name, startedAt = started, finishedAt = clock.now(),
                outcome = if (error == null) RefreshOutcome.SUCCESS.name else RefreshOutcome.FAILED.name,
                errorCode = error?.code, errorDetail = error?.let { Redact.text(it.toString()) },
                bytes = report?.dbBytes,
                reportJson = report?.let { Converters.json.encodeToString(EpgImportReport.serializer(), it) },
            ),
        )
    }

    companion object {
        private const val TAG = "EpgRepository"
        const val CACHE_DIR = "epgCacheDir"
        private const val ONE_GB = 1L shl 30
    }
}

/** §4.6: imports run at THREAD_PRIORITY_BACKGROUND; the old priority is restored afterwards. */
internal inline fun <T> withBackgroundPriority(block: () -> T): T {
    val tid = android.os.Process.myTid()
    val old = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
    try {
        return block()
    } finally {
        if (old != null) runCatching { android.os.Process.setThreadPriority(old) }
    }
}
