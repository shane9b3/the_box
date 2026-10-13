package app.thebox.core.epg.store

import androidx.collection.MutableIntSet
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgSourceStat
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.FreeSpace
import app.thebox.core.io.Compression
import app.thebox.core.xmltv.XmltvParser
import app.thebox.core.xmltv.XmltvTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.buffer
import okio.source
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Generation-named epg.db engine (docs/design/m1-data-layer.md §2.3–§2.5).
 * When `driver is BundledSQLiteDriver`, readers open with `SQLITE_OPEN_READONLY`; with any other driver
 * (Robolectric's `AndroidSQLiteDriver`) they rely on `PRAGMA query_only=1`. [forceFts4] is a test hook.
 *
 * Writers (build/patch/recover/clear) are serialised by an internal lock as well; WP-D's
 * `EpgWriteLock`/`ImportMutex` remain the contract (§4.6).
 */
class EpgStoreImpl(
    private val dir: File,
    private val driver: SQLiteDriver,
    private val freeSpace: FreeSpace,
    private val xmlParser: () -> XmlPullParser,
    private val clock: () -> Long,
    private val forceFts4: Boolean = false,
) : EpgStore {
    private val files = GenerationFiles(dir)
    private val parser = XmltvParser(xmlParser)
    private val current = AtomicReference<GenerationHandle?>(null)
    private val live = ConcurrentHashMap<Long, GenerationHandle>()
    private val writeLock = ReentrantLock()
    private val infoFlow = MutableStateFlow<EpgGenerationInfo?>(null)

    override val info: StateFlow<EpgGenerationInfo?> = infoFlow.asStateFlow()

    override fun openReader(): EpgReader? {
        while (true) {
            val h = current.get() ?: return null
            if (h.tryRetain()) return EpgReaderImpl(h)
        }
    }

    // ---------------------------------------------------------------- build

    override fun build(
        request: EpgBuildRequest,
        whitelist: EpgWhitelistResolver,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: (EpgProgress) -> Unit,
    ): EpgImportReport = writeLock.withLock {
        val t0 = clock()
        files.ensureDir()
        val cur = current.get()
        val inPlace = when {
            request.forceInPlace -> cur != null
            else -> {
                val (required, free) = diskCheck(request.sources)
                if (free >= required) {
                    false
                } else if (cur != null) {
                    BoxLog.w(TAG, "low disk: need $required, free $free; building in place")
                    true
                } else {
                    throw SourceException(SourceError.DiskSpace(required, free))
                }
            }
        }
        val throttle = Throttle(progress)
        if (inPlace && cur != null && cur.tryRetain()) {
            try {
                inPlace(request, whitelist, gate, cancel, throttle, t0, cur)
            } finally {
                cur.release()
            }
        } else {
            rebuild(request, whitelist, gate, cancel, throttle, t0)
        }
    }

    /** Required vs free bytes (§2.5.5). */
    internal fun diskCheck(sources: List<EpgSourceFile>): Pair<Long, Long> {
        val currentBytes = current.get()?.file?.length() ?: 0L
        val estimate = if (currentBytes > 0) {
            currentBytes
        } else {
            sources.sumOf { (minOf(it.file.length() * 8, 400L * MB) * 0.15).toLong() }
        }
        val required = (2.5 * estimate).toLong() + 64L * MB
        return required to freeSpace.bytesFree(dir)
    }

    private fun rebuild(
        request: EpgBuildRequest,
        resolver: EpgWhitelistResolver,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: Throttle,
        t0: Long,
    ): EpgImportReport {
        val gen = files.maxGeneration() + 1
        val building = files.building(gen)
        building.delete()
        files.journal(building).delete()
        var conn: SQLiteConnection? = null
        var renamed = false
        var committed = false
        try {
            conn = driver.open(building.path)
            for (p in EpgSchema.BUILD_PRAGMAS) conn.exec(p)
            conn.exec("PRAGMA user_version=${EpgSchema.SCHEMA_VERSION}")
            for (t in EpgSchema.TABLES) conn.exec(t)
            if (EpgSchema.INDEX_BEFORE_LOAD) conn.exec(EpgSchema.PROGRAMME_BY_TEXT)
            val fts = FtsSupport.detect(conn, forceFts4)
            val (from, to) = window(request.nowMs, request.pastDays, request.futureDays)
            val texts = TextDedup(conn, firstId = 1, seedFromDb = false)
            val ids = IdCounter(1)
            val stats = ArrayList<EpgSourceStat>(request.sources.size)
            for (src in request.sources) {
                cancel.throwIfCancelled()
                val r = importSource(
                    conn, src, ImportMode.REBUILD, resolver, null, from, to, texts, ids,
                    SourceOptions(request.storeDescriptions, request.maxDescriptionChars, request.preferredLanguages),
                    gate, cancel, progress,
                )
                stats += r.stat
                if (r.failed) {
                    BoxLog.w(TAG, "source ${src.sourceId} failed (${r.stat.errorCode}); generation $gen abandoned")
                    return failedReport(EpgImportReport.Mode.REBUILD, stats, src.sourceId, fts, t0)
                }
            }
            texts.close()

            progress.emit(EpgProgress(0, EpgProgress.Stage.INDEXING, 0, null, stats.sumOf { it.programmesKept }), force = true)
            for (ddl in EpgSchema.POST_LOAD_INDEXES) {
                cancel.throwIfCancelled()
                conn.exec(ddl)
            }
            conn.exec(FtsSupport.createSql(fts))
            conn.exec(FtsSupport.REBUILD_SQL)
            cancel.throwIfCancelled()
            conn.exec("PRAGMA analysis_limit=${EpgSchema.ANALYSIS_LIMIT}")
            conn.exec("ANALYZE")
            val programmes = conn.longOrNull("SELECT count(*) FROM programme")?.toInt() ?: 0
            val channels = conn.longOrNull("SELECT count(*) FROM epg_channel")?.toInt() ?: 0
            val textCount = conn.longOrNull("SELECT count(*) FROM epg_text")?.toInt() ?: 0
            val now = clock()
            conn.inTransaction {
                writeSourceStats(conn, stats, now)
                conn.putMeta(
                    mapOf(
                        EpgSchema.Meta.SCHEMA_VERSION to EpgSchema.SCHEMA_VERSION.toString(),
                        EpgSchema.Meta.GENERATION to gen.toString(),
                        EpgSchema.Meta.PATCH_SEQ to "0",
                        EpgSchema.Meta.BUILT_AT_MS to now.toString(),
                        EpgSchema.Meta.FTS to fts.id,
                        EpgSchema.Meta.WINDOW_FROM_MIN to from.toString(),
                        EpgSchema.Meta.WINDOW_TO_MIN to to.toString(),
                        EpgSchema.Meta.PROGRAMME_COUNT to programmes.toString(),
                        EpgSchema.Meta.CHANNEL_COUNT to channels.toString(),
                        EpgSchema.Meta.APP_VERSION to APP_VERSION,
                        EpgSchema.Meta.COMPLETE to "1",
                    ),
                )
            }
            conn.exec("PRAGMA journal_mode=DELETE")
            conn.close()
            conn = null

            progress.emit(EpgProgress(0, EpgProgress.Stage.PUBLISHING, 0, null, programmes), force = true)
            files.fsync(building)
            val target = files.db(gen)
            files.rename(building, target)
            renamed = true
            files.writePointer(gen) // commit point
            committed = true
            val handle = openHandle(gen)
            publish(handle)
            val report = EpgImportReport(
                mode = EpgImportReport.Mode.REBUILD,
                generation = gen,
                sources = stats,
                programmes = programmes,
                texts = textCount,
                dbBytes = target.length(),
                durationMs = clock() - t0,
                fts = fts.id,
            )
            BoxLog.i(TAG) { "generation $gen: ${report.programmes} programmes, ${report.texts} texts, ${report.dbBytes} bytes, ${report.durationMs} ms" }
            return report
        } catch (e: SQLiteException) {
            throw mapSqlite(e, request.sources)
        } catch (e: IOException) {
            throw SourceException(SourceError.Internal("epg build: ${e.message}"), e)
        } finally {
            try { conn?.close() } catch (_: Throwable) { }
            if (!committed) {
                building.delete()
                if (renamed) files.deleteGeneration(gen)
            }
        }
    }

    // ---------------------------------------------------------------- in place (low disk)

    private fun inPlace(
        request: EpgBuildRequest,
        resolver: EpgWhitelistResolver,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: Throttle,
        t0: Long,
        handle: GenerationHandle,
    ): EpgImportReport {
        val fts = handle.fts
        val conn = openWriter(handle.file)
        var report: EpgImportReport? = null
        try {
            val texts = TextDedup(conn, firstId = maxId(conn, "epg_text") + 1, seedFromDb = true)
            val ids = IdCounter(maxId(conn, "epg_channel") + 1)
            val (from, to) = window(request.nowMs, request.pastDays, request.futureDays)
            val stats = ArrayList<EpgSourceStat>()
            for (src in request.sources) {
                cancel.throwIfCancelled()
                val r = importSource(
                    conn, src, ImportMode.IN_PLACE, resolver, null, from, to, texts, ids,
                    SourceOptions(request.storeDescriptions, request.maxDescriptionChars, request.preferredLanguages),
                    gate, cancel, progress,
                )
                stats += r.stat
                if (r.failed) {
                    report = failedReport(EpgImportReport.Mode.IN_PLACE, stats, src.sourceId, fts, t0)
                    break
                }
                conn.inTransaction(immediate = true) { dropUntouched(conn, src.sourceId, r.accepted!!) }
            }
            texts.close()
            if (report == null) {
                progress.emit(EpgProgress(0, EpgProgress.Stage.INDEXING, 0, null, stats.sumOf { it.programmesKept }), force = true)
                conn.inTransaction(immediate = true) {
                    dropSourcesNotIn(conn, request.sources.map { it.sourceId }.toSet())
                    conn.exec("DELETE FROM epg_text WHERE id NOT IN (SELECT text_id FROM programme)")
                    conn.exec(FtsSupport.REBUILD_SQL)
                }
                conn.exec("PRAGMA analysis_limit=${EpgSchema.ANALYSIS_LIMIT}")
                conn.exec("ANALYZE")
            }
            val programmes = conn.longOrNull("SELECT count(*) FROM programme")?.toInt() ?: 0
            val now = clock()
            conn.inTransaction(immediate = true) {
                if (report == null) {
                    conn.exec("DELETE FROM source_stat")
                    writeSourceStats(conn, stats, now)
                }
                val seq = (conn.textOrNull("SELECT value FROM meta WHERE key = 'patch_seq'")?.toIntOrNull() ?: 0) + 1
                val meta = HashMap<String, String>()
                meta[EpgSchema.Meta.PATCH_SEQ] = seq.toString()
                meta[EpgSchema.Meta.PROGRAMME_COUNT] = programmes.toString()
                meta[EpgSchema.Meta.CHANNEL_COUNT] = (conn.longOrNull("SELECT count(*) FROM epg_channel") ?: 0L).toString()
                if (report == null) {
                    meta[EpgSchema.Meta.BUILT_AT_MS] = now.toString()
                    meta[EpgSchema.Meta.WINDOW_FROM_MIN] = from.toString()
                    meta[EpgSchema.Meta.WINDOW_TO_MIN] = to.toString()
                }
                conn.putMeta(meta)
            }
            if (report == null) {
                report = EpgImportReport(
                    mode = EpgImportReport.Mode.IN_PLACE,
                    generation = handle.gen,
                    sources = stats,
                    programmes = programmes,
                    texts = (conn.longOrNull("SELECT count(*) FROM epg_text") ?: 0L).toInt(),
                    dbBytes = handle.file.length(),
                    durationMs = clock() - t0,
                    fts = fts.id,
                )
            }
        } catch (e: SQLiteException) {
            throw mapSqlite(e, request.sources)
        } catch (e: IOException) {
            throw SourceException(SourceError.Internal("epg in-place: ${e.message}"), e)
        } finally {
            try { conn.close() } catch (_: Throwable) { }
            refreshInfo(handle)
        }
        return report
    }

    /** Channels of [sourceId] imported before but not whitelisted by this import lose their programmes. */
    private fun dropUntouched(conn: SQLiteConnection, sourceId: Long, accepted: MutableIntSet) {
        val stale = ArrayList<Long>()
        conn.query("SELECT id FROM epg_channel WHERE source_id = ? AND imported = 1", { bindLong(1, sourceId) }) { st ->
            while (st.step()) {
                val id = st.getLong(0)
                if (!accepted.contains(id.toInt())) stale += id
            }
        }
        if (stale.isEmpty()) return
        conn.query("DELETE FROM programme WHERE epg_channel_id = ?") { del ->
            conn.query("UPDATE epg_channel SET imported = 0, programme_count = 0, first_start_min = NULL, last_end_min = NULL WHERE id = ?") { upd ->
                for (id in stale) {
                    del.bindLong(1, id)
                    del.run()
                    upd.bindLong(1, id)
                    upd.run()
                }
            }
        }
    }

    private fun dropSourcesNotIn(conn: SQLiteConnection, keep: Set<Long>) {
        val gone = ArrayList<Long>()
        conn.query("SELECT DISTINCT source_id FROM epg_channel") { st -> while (st.step()) st.getLong(0).let { if (it !in keep) gone += it } }
        for (s in gone) {
            conn.query("DELETE FROM programme WHERE epg_channel_id IN (SELECT id FROM epg_channel WHERE source_id = ?)", { bindLong(1, s) }) { it.step() }
            conn.query("DELETE FROM epg_channel_name WHERE epg_channel_id IN (SELECT id FROM epg_channel WHERE source_id = ?)", { bindLong(1, s) }) { it.step() }
            conn.query("DELETE FROM epg_channel WHERE source_id = ?", { bindLong(1, s) }) { it.step() }
        }
    }

    // ---------------------------------------------------------------- patch

    override fun patch(
        request: EpgPatchRequest,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: (EpgProgress) -> Unit,
    ): EpgImportReport = writeLock.withLock {
        val t0 = clock()
        val h = current.get()
        if (h == null || !h.tryRetain()) {
            return EpgImportReport(EpgImportReport.Mode.PATCH, 0, emptyList(), 0, 0, 0, clock() - t0, "", null)
        }
        try {
            patchInto(h, request, gate, cancel, Throttle(progress), t0)
        } finally {
            h.release()
        }
    }

    private fun patchInto(h: GenerationHandle, request: EpgPatchRequest, gate: ImportGate, cancel: CancelCheck, progress: Throttle, t0: Long): EpgImportReport {
        val fts = h.fts
        val conn = openWriter(h.file)
        try {
            val texts = TextDedup(conn, firstId = maxId(conn, "epg_text") + 1, seedFromDb = true, ftsKind = fts)
            val ids = IdCounter(maxId(conn, "epg_channel") + 1)
            val (from, to) = window(request.nowMs, request.pastDays, request.futureDays)
            val stats = ArrayList<EpgSourceStat>()
            var failed: Long? = null
            for (src in request.sources) {
                val wanted = request.refs.filter { it.sourceId == src.sourceId }.mapTo(HashSet()) { it.xmltvId }
                if (wanted.isEmpty()) continue
                cancel.throwIfCancelled()
                val r = importSource(
                    conn, src, ImportMode.PATCH, null, wanted, from, to, texts, ids,
                    SourceOptions(request.storeDescriptions, request.maxDescriptionChars, request.preferredLanguages),
                    gate, cancel, progress,
                )
                stats += r.stat
                if (r.failed) {
                    failed = src.sourceId
                    break
                }
            }
            texts.close()
            val programmes = conn.longOrNull("SELECT count(*) FROM programme")?.toInt() ?: 0
            conn.inTransaction(immediate = true) {
                val seq = (conn.textOrNull("SELECT value FROM meta WHERE key = 'patch_seq'")?.toIntOrNull() ?: 0) + 1
                conn.putMeta(
                    mapOf(
                        EpgSchema.Meta.PATCH_SEQ to seq.toString(),
                        EpgSchema.Meta.PROGRAMME_COUNT to programmes.toString(),
                        EpgSchema.Meta.CHANNEL_COUNT to (conn.longOrNull("SELECT count(*) FROM epg_channel") ?: 0L).toString(),
                    ),
                )
            }
            return EpgImportReport(
                mode = EpgImportReport.Mode.PATCH,
                generation = h.gen,
                sources = stats,
                programmes = programmes,
                texts = (conn.longOrNull("SELECT count(*) FROM epg_text") ?: 0L).toInt(),
                dbBytes = h.file.length(),
                durationMs = clock() - t0,
                fts = fts.id,
                failedSourceId = failed,
            )
        } catch (e: SQLiteException) {
            throw mapSqlite(e, request.sources)
        } catch (e: IOException) {
            throw SourceException(SourceError.Internal("epg patch: ${e.message}"), e)
        } finally {
            try { conn.close() } catch (_: Throwable) { }
            refreshInfo(h)
        }
    }

    // ---------------------------------------------------------------- one source

    private class SourceOptions(val storeDescriptions: Boolean, val maxDescriptionChars: Int, val languages: List<String>)

    private class SourceResult(val stat: EpgSourceStat, val failed: Boolean, val accepted: MutableIntSet?)

    private fun importSource(
        conn: SQLiteConnection,
        src: EpgSourceFile,
        mode: ImportMode,
        resolver: EpgWhitelistResolver?,
        patchIds: Set<String>?,
        windowFrom: Int,
        windowTo: Int,
        texts: TextDedup,
        ids: IdCounter,
        opts: SourceOptions,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: Throttle,
    ): SourceResult {
        val total = src.file.length()
        val counter = CountingInputStream()
        val session = ImportSession(
            conn, src.sourceId, mode, resolver, patchIds, windowFrom, windowTo, texts, ids,
            if (mode == ImportMode.REBUILD) EpgSchema.REBUILD_BATCH_ROWS else EpgSchema.PATCH_BATCH_ROWS,
            gate, cancel,
        ) { kept -> progress.emit(EpgProgress(src.sourceId, EpgProgress.Stage.PARSING, counter.count, total, kept)) }
        progress.emit(EpgProgress(src.sourceId, EpgProgress.Stage.PARSING, 0, total, 0), force = true)
        session.begin()
        var truncated = false
        var error: String? = null
        try {
            FileInputStream(src.file).use { fis ->
                counter.input = fis
                val decompressed = Compression.decompress(counter.source().buffer())
                parser.parse(
                    decompressed.inputStream(),
                    session,
                    cancel,
                    XmltvParser.Options(opts.languages, opts.storeDescriptions, opts.maxDescriptionChars, XmltvTime.resolverFor(src.assumeZone)),
                )
            }
        } catch (e: CancellationException) {
            session.abort()
            throw e
        } catch (e: SQLiteException) {
            session.abort()
            throw e
        } catch (e: SourceException) {
            error = e.error.code
        } catch (e: FileNotFoundException) {
            error = "missing"
        } catch (e: XmlPullParserException) {
            if (isLateFailure(counter.count, total)) truncated = true else error = SourceError.Parse(e.message ?: "xml").code
        } catch (e: IOException) {
            if (isLateFailure(counter.count, total)) truncated = true else error = SourceError.Parse(e.message ?: "io").code
        }
        if (error != null) {
            session.abort()
            BoxLog.w(TAG, "source ${src.sourceId}: failed ($error) after ${counter.count} of $total bytes")
            return SourceResult(stat(src, session, 0, total, false, error), failed = true, accepted = null)
        }
        if (truncated) BoxLog.w(TAG, "source ${src.sourceId}: XML_RECOVERED (truncated after ${counter.count} of $total bytes)")
        session.finish()

        progress.emit(EpgProgress(src.sourceId, EpgProgress.Stage.REPAIRING, total, total, session.programmesKept), force = true)
        val repair = RepairPass(conn, windowTo)
        try {
            val accepted = IntArray(session.acceptedChannels.size)
            var k = 0
            session.acceptedChannels.forEach { accepted[k++] = it }
            accepted.sort()
            var i = 0
            while (i < accepted.size) {
                val end = minOf(i + EpgSchema.REPAIR_BATCH_CHANNELS, accepted.size)
                conn.inTransaction(immediate = mode != ImportMode.REBUILD) {
                    for (c in i until end) repair.repairChannel(accepted[c])
                }
                i = end
                if (i < accepted.size) {
                    gate.awaitClear()
                    cancel.throwIfCancelled()
                }
            }
        } finally {
            repair.close()
        }
        val stat = stat(src, session, repair.clipped, total, truncated, null)
        BoxLog.i(TAG) {
            "source ${src.sourceId} ($mode): ${stat.channels} channels, ${stat.programmesSeen} seen, ${stat.programmesKept} kept, " +
                "${stat.skippedWhitelist} not whitelisted, ${stat.skippedWindow} outside window, ${stat.clipped} clipped, " +
                "${stat.split} split, ${stat.badTimes} bad times"
        }
        return SourceResult(stat, failed = false, accepted = session.acceptedChannels)
    }

    private fun isLateFailure(consumed: Long, total: Long) = total > 0 && consumed >= (total * TRUNCATION_ACCEPT).toLong()

    private fun stat(src: EpgSourceFile, s: ImportSession, clipped: Int, bytes: Long, truncated: Boolean, error: String?) = EpgSourceStat(
        sourceId = src.sourceId,
        channels = s.channels,
        programmesSeen = s.programmesSeen,
        programmesKept = s.programmesKept,
        skippedWhitelist = s.skippedWhitelist,
        skippedWindow = s.skippedWindow,
        clipped = clipped,
        split = s.split,
        badTimes = s.badTimes,
        bytes = bytes,
        truncated = truncated,
        errorCode = error,
    )

    private fun writeSourceStats(conn: SQLiteConnection, stats: List<EpgSourceStat>, now: Long) {
        conn.query(
            "INSERT OR REPLACE INTO source_stat(source_id, channels, programmes_seen, programmes_kept, bytes, imported_at_ms) VALUES (?,?,?,?,?,?)",
        ) { st ->
            for (s in stats) {
                st.bindLong(1, s.sourceId)
                st.bindLong(2, s.channels.toLong())
                st.bindLong(3, s.programmesSeen.toLong())
                st.bindLong(4, s.programmesKept.toLong())
                st.bindLong(5, s.bytes)
                st.bindLong(6, now)
                st.run()
            }
        }
    }

    private fun failedReport(mode: EpgImportReport.Mode, stats: List<EpgSourceStat>, failed: Long, fts: FtsKind, t0: Long): EpgImportReport {
        val cur = current.get()
        return EpgImportReport(
            mode = mode,
            generation = cur?.gen ?: 0,
            sources = stats,
            programmes = cur?.info?.programmes ?: 0,
            texts = 0,
            dbBytes = cur?.file?.length() ?: 0,
            durationMs = clock() - t0,
            fts = fts.id,
            failedSourceId = failed,
        )
    }

    // ---------------------------------------------------------------- recovery, clear, handles

    override fun recover(): Unit = writeLock.withLock {
        files.ensureDir()
        val cur = current.get()
        val pointer = files.readPointer()
        val candidates = LinkedHashSet<Long>()
        pointer?.let { candidates += it }
        candidates += files.publishedGenerations()
        var chosen: Long? = null
        for (g in candidates) {
            if (cur != null && cur.gen == g) {
                chosen = g
                break
            }
            val f = files.db(g)
            if (!f.isFile) continue
            if (files.journal(f).exists()) rollBackHotJournal(f)
            if (isValid(f)) {
                chosen = g
                break
            }
        }
        if (chosen == null) {
            files.deletePointer()
        } else if (chosen != pointer) {
            files.writePointer(chosen)
        }
        val keep = HashSet<Long>(live.keys)
        chosen?.let { keep += it }
        files.cleanup(keep)
        if (chosen != cur?.gen) {
            if (chosen != null) {
                publish(openHandle(chosen))
            } else {
                retire(current.getAndSet(null))
                infoFlow.value = null
            }
        }
        BoxLog.i(TAG) { "recover: current generation ${chosen ?: "none"} (pointer $pointer)" }
    }

    override fun clear(): Unit = writeLock.withLock {
        retire(current.getAndSet(null))
        infoFlow.value = null
        if (dir.isDirectory) {
            files.deletePointer()
            files.cleanup(HashSet(live.keys))
        }
    }

    private fun publish(h: GenerationHandle) {
        live[h.gen] = h
        val old = current.getAndSet(h)
        infoFlow.value = h.info
        if (old != null && old !== h) retire(old)
    }

    private fun retire(h: GenerationHandle?) {
        if (h == null) return
        h.retired = true
        h.releaseBase()
    }

    private fun onHandleClosed(h: GenerationHandle) {
        live.remove(h.gen, h)
        if (h.retired) files.deleteGeneration(h.gen)
    }

    private fun openHandle(gen: Long): GenerationHandle {
        val file = files.db(gen)
        val info = openReadOnly(file).useConn { c ->
            check(isComplete(c)) { "generation $gen is incomplete" }
            readInfo(c, file)
        }
        return GenerationHandle(gen, file, { openReadOnly(file) }, info, ::onHandleClosed)
    }

    private fun refreshInfo(h: GenerationHandle) {
        try {
            h.info = h.guide.use { s -> readInfo(s.connection, h.file) }
            if (current.get() === h) infoFlow.value = h.info
        } catch (e: Exception) {
            BoxLog.w(TAG, "cannot refresh generation ${h.gen} info", e)
        }
    }

    private fun readInfo(c: SQLiteConnection, file: File): EpgGenerationInfo {
        val m = c.readMeta()
        return EpgGenerationInfo(
            generation = m[EpgSchema.Meta.GENERATION]?.toLongOrNull() ?: 0,
            patchSeq = m[EpgSchema.Meta.PATCH_SEQ]?.toIntOrNull() ?: 0,
            builtAtMs = m[EpgSchema.Meta.BUILT_AT_MS]?.toLongOrNull() ?: 0,
            programmes = m[EpgSchema.Meta.PROGRAMME_COUNT]?.toIntOrNull() ?: 0,
            channels = m[EpgSchema.Meta.CHANNEL_COUNT]?.toIntOrNull() ?: 0,
            dbBytes = file.length(),
            windowFromMin = m[EpgSchema.Meta.WINDOW_FROM_MIN]?.toIntOrNull() ?: 0,
            windowToMin = m[EpgSchema.Meta.WINDOW_TO_MIN]?.toIntOrNull() ?: 0,
            fts = m[EpgSchema.Meta.FTS] ?: FtsKind.FTS5.id,
        )
    }

    private fun isComplete(c: SQLiteConnection): Boolean =
        c.longOrNull("PRAGMA user_version") == EpgSchema.SCHEMA_VERSION.toLong() &&
            c.textOrNull("SELECT value FROM meta WHERE key = 'complete'") == "1"

    private fun isValid(file: File): Boolean = try {
        openReadOnly(file).useConn { isComplete(it) }
    } catch (e: Exception) {
        BoxLog.w(TAG, "invalid generation file ${file.name}: ${e.message}")
        false
    }

    /** A `-journal` next to a database is a hot journal of an interrupted patch: one read-write open rolls it back. */
    private fun rollBackHotJournal(file: File) {
        try {
            openWriter(file).useConn { it.longOrNull("SELECT count(*) FROM meta") }
        } catch (e: Exception) {
            BoxLog.w(TAG, "hot journal rollback failed for ${file.name}: ${e.message}")
        }
    }

    private fun openReadOnly(file: File): SQLiteConnection {
        val d = driver
        val c = if (d is BundledSQLiteDriver) d.open(file.path, SQLITE_OPEN_READONLY) else d.open(file.path)
        try {
            c.exec(EpgSchema.READER_PRAGMAS[0]) // query_only is mandatory for non-bundled drivers
            for (k in 1 until EpgSchema.READER_PRAGMAS.size) {
                try { c.exec(EpgSchema.READER_PRAGMAS[k]) } catch (_: Exception) { }
            }
        } catch (t: Throwable) {
            c.close()
            throw t
        }
        return c
    }

    private fun openWriter(file: File): SQLiteConnection {
        if (!file.isFile) throw FileNotFoundException(file.path)
        val c = driver.open(file.path)
        try {
            for (p in EpgSchema.WRITER_PRAGMAS) c.exec(p)
        } catch (t: Throwable) {
            c.close()
            throw t
        }
        return c
    }

    private fun maxId(conn: SQLiteConnection, table: String): Long = conn.longOrNull("SELECT max(id) FROM $table") ?: 0L

    private fun window(nowMs: Long, pastDays: Int, futureDays: Int): Pair<Int, Int> {
        val now = Math.floorDiv(nowMs, 60_000L)
        return (now - pastDays * 1440L).toInt() to (now + futureDays * 1440L).toInt()
    }

    private fun mapSqlite(e: SQLiteException, sources: List<EpgSourceFile>): SourceException {
        val msg = e.message.orEmpty()
        return if (msg.contains("full", ignoreCase = true) || msg.contains("code: 13") || msg.contains("SQLITE_FULL")) {
            val (required, free) = try { diskCheck(sources) } catch (_: Exception) { 0L to 0L }
            SourceException(SourceError.DiskSpace(required, free), e)
        } else {
            SourceException(SourceError.Internal("epg sqlite: $msg"), e)
        }
    }

    private inline fun <T> SQLiteConnection.useConn(block: (SQLiteConnection) -> T): T = try { block(this) } finally { close() }

    /** Progress callbacks at most once per [PROGRESS_INTERVAL_MS] (§4.6), except stage changes. */
    private inner class Throttle(private val sink: (EpgProgress) -> Unit) {
        private var last = Long.MIN_VALUE

        fun emit(p: EpgProgress, force: Boolean = false) {
            val now = clock()
            if (force || last == Long.MIN_VALUE || now - last >= PROGRESS_INTERVAL_MS) {
                last = now
                sink(p)
            }
        }
    }

    /** Counts compressed bytes consumed from the source file (progress and the 90 % rule). */
    private class CountingInputStream : FilterInputStream(null) {
        var input: InputStream?
            get() = `in`
            set(value) { `in` = value }

        @Volatile var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        private const val TAG = "EpgStoreImpl"
        private const val MB = 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 500L

        /** A parse failure after this share of the compressed bytes counts as truncation, not failure (§3.5.6). */
        const val TRUNCATION_ACCEPT = 0.9
        const val XML_RECOVERED = "XML_RECOVERED"
        private const val APP_VERSION = "1"
    }
}
