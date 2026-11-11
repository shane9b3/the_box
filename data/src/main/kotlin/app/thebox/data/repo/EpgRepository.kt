package app.thebox.data.repo

import app.thebox.core.common.BoxLog
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.ImportGate
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgBuildInputs
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.FreeSpace
import app.thebox.core.match.EpgMatcher
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
import app.thebox.data.guide.EpgRefRule
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
 * build: conditional GET per active source to `cacheDir/epg/<id>.new` → skip when nothing changed (304, or a file
 * byte-identical to `.src`), the generation was built from the same inputs and its future window still covers the
 * next run (`rebuildReason`, review 2026-11-10 S1/N1) →
 * `EpgStore.build` under ImportMutex + EpgWriteLock with [EpgMatchRepository] as the whitelist → retry once with
 * the last good `.src` when a source failed → promote `.new` to `.src` and persist validators/counts (only now, S10) → has_data, late-channel matching, patch enqueue → refresh_log.
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
    fun generationBuiltAt(): Long? = generationInfo()?.builtAtMs

    private fun generationInfo(): EpgGenerationInfo? = try {
        store.info.value
    } catch (e: NotImplementedError) {
        null // WP-B stub on a branch without WP-B
    }

    /**
     * Why an unchanged download must still rebuild from the cached files, or null to skip (review 2026-11-10, S1, N1):
     * - the build inputs differ from the generation's (a NEXT_EPG_IMPORT setting, the active sources, the importer
     *   version; a generation from before the fingerprint has none, so it rebuilds once);
     * - the generation was built in the future (the clock moved back);
     * - the future window would have shrunk by more than [MAX_FUTURE_SHRINK_MS] by the next periodic run. The build
     *   keeps `[now − pastDays, now + futureDays)` and the guide stops at `window_to_min`, so the window only moves on
     *   a rebuild.
     */
    private fun rebuildReason(info: EpgGenerationInfo, inputs: String, s: DataSettingsSnapshot, now: Long): String? {
        if (info.buildInputs != inputs) return "build inputs changed"
        if (now < info.builtAtMs) return "built in the future (clock moved back)"
        val shrunkMs = s.epgFutureDays * DAY_MS - (info.windowToMin * MINUTE_MS - now)
        val nextRunMs = s.epgRefreshIntervalH.coerceAtLeast(1) * HOUR_MS
        if (shrunkMs + nextRunMs > MAX_FUTURE_SHRINK_MS) return "future window ${shrunkMs / HOUR_MS} h short"
        return null
    }

    /** [EpgBuildInputs] of the active sources (not only the ones this run could read) and the settings a build reads. */
    private fun buildInputs(sources: List<EpgSourceEntity>, s: DataSettingsSnapshot): String = EpgBuildInputs.fingerprint(
        sources = sources.map { EpgBuildInputs.Source(it.id, it.assumeZone) },
        pastDays = s.epgPastDays, futureDays = s.epgFutureDays,
        storeDescriptions = s.storeDescriptions, maxDescriptionChars = s.maxDescriptionChars,
        preferredLanguages = s.preferredLanguages,
        // EpgMatchRepository.whitelistFor reads the match settings; the matcher version changes what it accepts.
        matching = "${s.match}|${EpgMatcher.VERSION}",
    )

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
                } else if (!res.value.notModified && haveSrc && sameBytes(next, src)) {
                    // Performance review M6: many guide servers send no ETag/Last-Modified, so a byte-identical file
                    // counts as not modified (the 304 path). Both files hold the body as received (gzip stays gzip).
                    BoxLog.i(TAG) { "source ${s.id} unchanged (identical file)" }
                    next.delete()
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
        // 2. Skip when nothing changed (review 2026-11-10, S1 and N1): every download is unchanged, the generation was
        // built from the same inputs, and its future window still covers the configured days until the next run.
        val s = settings.current
        val inputs = buildInputs(sources, s)
        val info = generationInfo()
        if (!anyChanged && !force && info != null) {
            val reason = rebuildReason(info, inputs, s, clock.now())
            if (reason == null) {
                BoxLog.i(TAG) { "all sources not modified: build skipped" }
                // A clean not-modified run resolves earlier build failures like a playlist 304 does (RefreshLogDao).
                // A source that failed in this run without a cached file keeps the old behaviour: no row.
                if (lastError == null) log(RefreshLogEntity.TARGET_EPG_BUILD, trigger, started, null, null, RefreshOutcome.NOT_MODIFIED)
                return EpgRunResult(null, null, skipped = true)
            }
            BoxLog.i(TAG) { "sources not modified, rebuilding from the cached files: $reason" }
        }

        // 3. Build under ImportMutex → EpgWriteLock.
        fun request(files: List<Picked>) = EpgBuildRequest(
            sources = files.map { EpgSourceFile(it.source.id, it.file, it.source.assumeZone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() }) },
            nowMs = clock.now(), pastDays = s.epgPastDays, futureDays = s.epgFutureDays,
            storeDescriptions = s.storeDescriptions, maxDescriptionChars = s.maxDescriptionChars, preferredLanguages = s.preferredLanguages,
            buildInputs = inputs,
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

        // 4. Promote downloads and persist validators/counts (only after a successful build, S10).
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

        // 5. has_data, late channels, patch.
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
        // M3 (docs/design/m3-guide.md §3.2.2): one rule, shared with the guide's EpgRefResolver.
        val sources = EpgRefRule.Sources(sourceDao.active())
        val wanted = playlistIds.toSet()
        val mappings = mappingDao.all().filter { it.playlistId in wanted }
            .associate { (it.playlistId to it.stableKey) to EpgRefRule.Mapping(it.sourceId, it.xmltvId) }
        val autos = matchDao.autos().filter { it.playlistId in wanted }
            .groupBy({ it.playlistId to it.stableKey }) { EpgRefRule.Auto(it.sourceId, it.xmltvId, it.hasData, it.method) }
        val headerShift = playlistIds.associateWith { id -> EpgRefRule.headerShiftMin(playlistDao.get(id)) }
        val global = settings.current.globalShiftMin
        val out = HashMap<ChannelIdentity, ResolvedEpg>()
        for (row in channelDao.shiftInputs(playlistIds)) {
            val k = row.playlistId to row.stableKey
            val channelShift = row.prefShiftMin ?: row.channelShiftMin ?: headerShift[row.playlistId] ?: 0
            out[ChannelIdentity(row.playlistId, StableKey(row.stableKey))] =
                EpgRefRule.resolve(row.playlistId, channelShift, mappings[k], autos[k].orEmpty(), sources, global)
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

    private suspend fun log(
        type: String,
        trigger: Trigger,
        started: Long,
        report: EpgImportReport?,
        error: SourceError?,
        outcome: RefreshOutcome = if (error == null) RefreshOutcome.SUCCESS else RefreshOutcome.FAILED,
    ) {
        logDao.insert(
            RefreshLogEntity(
                targetType = type, targetId = null, trigger = trigger.name, startedAt = started, finishedAt = clock.now(),
                outcome = outcome.name,
                errorCode = error?.code, errorDetail = error?.let { Redact.text(it.toString()) },
                bytes = report?.dbBytes,
                reportJson = report?.let { Converters.json.encodeToString(EpgImportReport.serializer(), it) },
            ),
        )
    }

    /** True when [a] and [b] have the same length and bytes; streamed in fixed buffers, never whole in memory. */
    private suspend fun sameBytes(a: File, b: File): Boolean = withContext(io) {
        val ctx = coroutineContext
        sameFileBytes(a, b) { !ctx.isActive }
    }

    companion object {
        private const val TAG = "EpgRepository"
        const val CACHE_DIR = "epgCacheDir"
        private const val ONE_GB = 1L shl 30
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * A not-modified run skips only while the future window, shrunk since its build, will have lost at most this
         * much by the next periodic run: `shrunk + interval ≤ 26 h` (review 2026-11-10, N1). It replaces the 20 h age
         * rule of performance review M6, which a mid-cycle build defeated on the daily schedule (up to ~43 h lost).
         * The guide then always shows at least `futureDays − 26 h − WorkManager delay`, so the 3-day setting keeps
         * about 2 days:
         * - 6 h: the 6/12/18 h runs skip (shrunk + 6 ≤ 24), the 24 h run rebuilds (30 > 26): at most 24 h lost.
         * - 12 h: the 12 h run skips (24 ≤ 26, so it may run up to 2 h late), the 24 h run rebuilds: 24–26 h lost.
         * - 24 h and daily: every run rebuilds (≥ 24 + 24), also after a mid-cycle manual or playlist build: 24 h lost.
         * Measured from `window_to_min`, so it does not matter what kind of build came last.
         */
        internal const val MAX_FUTURE_SHRINK_MS = 26 * HOUR_MS
    }
}

/**
 * Streams both files through 64 KB buffers; false on any length or byte difference, or when either is unreadable.
 * [cancelled] is checked per buffer (review 2026-11-10, N4), so a cancelled worker does not wait for a 150 MB compare.
 */
internal fun sameFileBytes(a: File, b: File, cancelled: () -> Boolean = { false }): Boolean {
    if (!a.isFile || !b.isFile) return false
    val len = a.length()
    if (len != b.length()) return false
    return try {
        a.inputStream().use { ia ->
            b.inputStream().use { ib ->
                val ba = ByteArray(COMPARE_BUFFER)
                val bb = ByteArray(COMPARE_BUFFER)
                var left = len
                while (left > 0) {
                    if (cancelled()) throw CancellationException("guide compare cancelled")
                    val n = minOf(left, COMPARE_BUFFER.toLong()).toInt()
                    if (!readFully(ia, ba, n) || !readFully(ib, bb, n)) return false
                    for (i in 0 until n) if (ba[i] != bb[i]) return false // Arrays.equals(range) is API 33
                    left -= n
                }
                true
            }
        }
    } catch (e: java.io.IOException) {
        false
    }
}

private fun readFully(input: java.io.InputStream, buf: ByteArray, n: Int): Boolean {
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r < 0) return false
        off += r
    }
    return true
}

private const val COMPARE_BUFFER = 64 * 1024

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
