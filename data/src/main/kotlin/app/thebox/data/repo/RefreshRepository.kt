package app.thebox.data.repo

import app.thebox.core.catchup.TimeBaseFactory
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.ImportGate
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgStore
import app.thebox.core.io.Compression
import app.thebox.core.m3u.M3uParseOptions
import app.thebox.core.m3u.M3uPlaylistParser
import app.thebox.core.m3u.M3uSink
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.EntryProducer
import app.thebox.core.refresh.MergeContext
import app.thebox.core.refresh.PlaylistDiffer
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.ParseWarning
import app.thebox.core.source.PlaylistType
import app.thebox.core.text.NamePrefixRules
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamCategory
import app.thebox.core.xtream.XtreamUrls
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.net.ContentCopier
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * One playlist refresh (§3.10 PlaylistRefreshWorker steps): secret → download (conditional GET, SAF copy, or
 * Xtream login + categories + streams) → [PlaylistDiffer.merge] with [RoomMergeSink] under [ImportMutex] →
 * header (EPG URLs redacted, B3), account info, playlist EPG sources upserted in place (S14), B1 login for
 * Xtream-shaped M3U → incremental matching and `epg-patch`/`epg-import` enqueue → refresh_log.
 *
 * Templating: M3U channel URLs are templated by WP-A's differ. Xtream API channels arrive from WP-C's decoder
 * already templated (`{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}`) and are passed through untouched.
 */
@Singleton
class RefreshRepository @Inject constructor(
    private val db: LibraryDatabase,
    private val playlistDao: PlaylistDao,
    private val sourceDao: EpgSourceDao,
    private val logDao: RefreshLogDao,
    private val playlists: PlaylistRepository,
    private val matches: EpgMatchRepository,
    private val epgStore: EpgStore,
    private val parser: M3uPlaylistParser,
    private val differ: PlaylistDiffer,
    private val decoder: XtreamCatalogDecoder,
    private val xtream: XtreamApi,
    private val fetcher: SourceFetcher,
    private val copier: ContentCopier,
    private val timeBaseFactory: TimeBaseFactory,
    private val importMutex: ImportMutex,
    private val requester: EpgWorkRequester,
    @param:Named(PLAYLIST_CACHE_DIR) private val cacheDir: File,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
) : PlaylistRefresher {

    /**
     * One lock per playlist around the whole refresh. `playlist-periodic-<id>` and `playlist-refresh-<id>` are
     * different unique works, so both can run at once (a new periodic work runs immediately, and app start also
     * enqueues the one-time refresh). Without this they download into the same `<id>.src.part`, and pass 2 of
     * one merge can re-open a file the other download replaced after pass 1 (code review M1, S-1).
     */
    private val playlistLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun refresh(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean, progress: (String) -> Unit): RefreshResult =
        playlistLocks.computeIfAbsent(playlistId) { Mutex() }.withLock { refreshLocked(playlistId, trigger, forceAcceptShrink, progress) }

    private suspend fun refreshLocked(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean, progress: (String) -> Unit): RefreshResult {
        val started = clock.now()
        val playlist = playlistDao.get(playlistId) ?: return RefreshResult(null, SourceError.Internal("no playlist $playlistId"))
        try {
            // 1. Secret.
            val secret = when (val s = playlists.secret(playlist)) {
                is Outcome.Ok -> s.value
                is Outcome.Err -> return fail(playlist, trigger, started, s.error)
            }
            // 2. Download.
            progress("Downloading playlist")
            val dl = when (val d = download(playlist, secret)) {
                is Outcome.Ok -> d.value
                is Outcome.Err -> return fail(playlist, trigger, started, d.error)
            }
            if (dl.notModified) {
                val report = RefreshReport(playlistId, playlist.refreshSeq, started, clock.now(), RefreshOutcome.NOT_MODIFIED)
                db.writeTransaction {
                    exec("UPDATE playlist SET last_refresh_at = ?, last_success_at = ?, last_status = ? WHERE id = ?") {
                        bindLong(1, clock.now()); bindLong(2, clock.now()); bindText(3, RefreshOutcome.NOT_MODIFIED.name); bindLong(4, playlistId)
                    }
                }
                log(playlistId, trigger, started, report, null, 0)
                return RefreshResult(report, null)
            }

            // 3. Merge under the import mutex.
            progress("Updating channels")
            var header: M3uHeader? = null
            val sink = RoomMergeSink(
                db, playlistId, nowMs = clock.now(), refreshSeq = playlist.refreshSeq + 1,
                etag = dl.fetch?.etag, lastModified = dl.fetch?.lastModified,
                onDetected = { storeDetected(playlist, secret, it) },
                // M3 §3.12.3: display_name from the playlist's prefix rule.
                prefixRule = NamePrefixRules.parse(playlist.namePrefixMode, playlist.namePrefixCustom),
            )
            val producer: EntryProducer = when (playlist.type) {
                PlaylistType.XTREAM -> xtreamProducer(dl)
                else -> m3uProducer(playlist, secret, dl) { header = it }
            }
            val ctx = MergeContext(
                playlistId = playlistId, playlistType = playlist.type, refreshSeq = playlist.refreshSeq + 1, nowMs = clock.now(),
                previousLiveCount = playlist.liveCount, forceAcceptShrink = forceAcceptShrink,
                // Read the shared gate per batch, so a gate installed mid-refresh (M2) applies at once.
                gate = ImportGate { importMutex.gate.awaitClear() },
            )
            val cancel = cancelCheck()
            val report = importMutex.withLock {
                withContext(io) {
                    withBackgroundPriority { differ.merge(producer, RoomExistingChannels(db, playlistId), sink, ctx, cancel) }
                }
            }

            // 4. Header, account, EPG sources, B1.
            var sourcesChanged = false
            if (playlist.type == PlaylistType.XTREAM) {
                dl.login?.let { saveLogin(playlist, it.account, it.baseIndexUsed) }
                val base = secret.urls.getOrNull(dl.login?.baseIndexUsed ?: playlist.activeBaseIndex) ?: secret.urls.first()
                val xmltv = runCatching { XtreamUrls(base, secret.username.orEmpty(), secret.password.orEmpty()).xmltv() }
                    .onFailure { BoxLog.w(TAG, "xmltv url unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                // An empty list would delete the guide row and its mappings; keep them when the URL cannot be built.
                if (xmltv != null) sourcesChanged = syncSources(playlist, EpgSourceEntity.ORIGIN_XTREAM, listOf(xmltv), secret)
            } else {
                val h = header ?: M3uHeader()
                val detected = sink.detected
                playlistDao.setHeader(playlistId, Converters.json.encodeToString(M3uHeader.serializer(), redactHeader(h, secret, detected)))
                // Decision 2026-11-11 (docs/decisions/2026-11-11-keep-header-guide-links.md): a header with no guide
                // links keeps the last known ones. Syncing an empty list would delete the rows, and the delete
                // cascades to the user's manual matches; one bad download would lose them for good.
                if (h.epgUrls.isEmpty()) {
                    BoxLog.i(TAG) { "playlist $playlistId header lists no guide links; keeping the last known ones" }
                } else {
                    sourcesChanged = syncSources(playlist, EpgSourceEntity.ORIGIN_PLAYLIST_HEADER, h.epgUrls, secret, detected)
                }
                if (detected != null) loginDetected(playlist, detected)
            }

            // 5. Matching and EPG work.
            progress("Matching guide data")
            var guideWorkQueued = false
            try {
                if (sourcesChanged) {
                    requester.importEpgNow(Trigger.PLAYLIST_CHANGED)
                    guideWorkQueued = true
                } else if (matches.matchIncremental(playlistId, epgStore)) {
                    requester.patchEpg()
                    guideWorkQueued = true
                }
            } catch (e: NotImplementedError) {
                BoxLog.w(TAG, "EPG store not available; matching deferred to the next build")
            }

            // 6. Report.
            val final = report.copy(finishedAtMs = clock.now(), guideWorkQueued = guideWorkQueued)
            log(playlistId, trigger, started, final, null, dl.bytes)
            BoxLog.i(TAG) { "playlist $playlistId refreshed: +${final.added} −${final.newlyMissing} purged ${final.purged}" }
            return RefreshResult(final, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            return fail(playlist, trigger, started, e.error)
        } catch (e: NotImplementedError) {
            return fail(playlist, trigger, started, SourceError.Unsupported("not available in this build: ${e.message}"))
        } catch (e: Exception) {
            // Room/IO/state errors would otherwise escape the worker with no refresh_log row and no last_status
            // (§3.10 step 6: a log row on success and on failure). Only the class name: messages may hold URLs.
            BoxLog.e(TAG, "playlist $playlistId refresh crashed", e)
            return fail(playlist, trigger, started, SourceError.Internal(e.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- download

    private class Download(
        val notModified: Boolean,
        val file: File,
        val categoriesFile: File? = null,
        val fetch: FetchResult? = null,
        val login: app.thebox.data.contracts.XtreamLogin? = null,
        val bytes: Long = 0,
    )

    private suspend fun download(p: PlaylistEntity, secret: PlaylistSecret): Outcome<Download> {
        cacheDir.mkdirs()
        val file = File(cacheDir, "${p.id}.src")
        return when (p.type) {
            PlaylistType.M3U_URL -> {
                val url = secret.urls.firstOrNull() ?: return Outcome.Err(SourceError.Internal("no url"))
                // Validators only count when the last download is still there to re-parse.
                val r = fetcher.fetch(
                    FetchRequest(
                        url = url, headers = requestHeaders(p, secret),
                        etag = p.etag.takeIf { file.exists() }, lastModified = p.lastModified.takeIf { file.exists() },
                    ),
                    file,
                )
                when (r) {
                    is Outcome.Err -> r
                    is Outcome.Ok -> Outcome.Ok(Download(r.value.notModified, file, fetch = r.value, bytes = r.value.bytes))
                }
            }
            PlaylistType.M3U_FILE -> {
                val uri = secret.urls.firstOrNull() ?: return Outcome.Err(SourceError.Internal("no uri"))
                when (val r = copier.copy(uri, file)) {
                    is Outcome.Err -> r
                    is Outcome.Ok -> Outcome.Ok(Download(false, file, fetch = r.value, bytes = r.value.bytes))
                }
            }
            PlaylistType.XTREAM -> {
                val conn = connection(p, secret)
                val login = when (val l = xtream.login(conn)) {
                    is Outcome.Err -> return l
                    is Outcome.Ok -> l.value
                }
                val pinned = conn.copy(preferredBaseIndex = login.baseIndexUsed)
                val cats = File(cacheDir, "${p.id}.cat.json")
                when (val c = xtream.download(pinned, XtreamAction.LIVE_CATEGORIES, emptyMap(), cats)) {
                    is Outcome.Err -> return c
                    is Outcome.Ok -> Unit
                }
                when (val s = xtream.download(pinned, XtreamAction.LIVE_STREAMS, emptyMap(), file)) {
                    is Outcome.Err -> s
                    is Outcome.Ok -> Outcome.Ok(Download(false, file, cats, s.value, login, s.value.bytes))
                }
            }
        }
    }

    private fun connection(p: PlaylistEntity, secret: PlaylistSecret) = XtreamConnection(
        playlistId = p.id, baseUrls = secret.urls, username = secret.username.orEmpty(), password = secret.password.orEmpty(),
        userAgent = p.userAgent, headers = requestHeaders(p, secret), preferredBaseIndex = p.activeBaseIndex,
    )

    private fun requestHeaders(p: PlaylistEntity, secret: PlaylistSecret): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        p.extraHeaders?.let { h.putAll(it) }
        h.putAll(secret.headers)
        p.userAgent?.let { h["User-Agent"] = it }
        return h
    }

    // ---------------------------------------------------------------- producers

    /**
     * Re-opens and decompresses the cached file on every pass (the differ runs two). The stored header is the
     * summary's merged one (amended 2026-10-13: it includes `#EXTM3U` lines after the first entry).
     * Channel URLs are passed raw: the differ templates them once (§3.2.4).
     */
    private fun m3uProducer(p: PlaylistEntity, secret: PlaylistSecret, dl: Download, headerOut: (M3uHeader) -> Unit) = EntryProducer { consumer ->
        val options = M3uParseOptions(
            baseUrl = if (p.type == PlaylistType.M3U_URL) secret.urls.firstOrNull() else null,
            charsetHint = dl.fetch?.charset,
            forcedCharset = p.charsetOverride,
            extgrpSticky = p.extgrpSticky,
        )
        Compression.decompress(dl.file.source().buffer()).use { src ->
            var early: M3uHeader? = null
            val summary = parser.parse(
                src, options,
                object : M3uSink {
                    override fun onHeader(header: M3uHeader) { early = header }
                    override fun onEntry(entry: ParsedChannel) = consumer(entry)
                    override fun onWarning(warning: ParseWarning) {}
                },
            )
            (summary.header ?: early)?.let(headerOut)
        }
    }

    private fun xtreamProducer(dl: Download): EntryProducer {
        val categories: List<XtreamCategory> by lazy {
            dl.categoriesFile?.takeIf { it.exists() }?.inputStream()?.use { decoder.categories(it) } ?: emptyList()
        }
        return EntryProducer { consumer ->
            dl.file.inputStream().buffered(64 * 1024).use { input ->
                decoder.liveChannels(input, categories).forEach { e ->
                    // Xtream API channels arrive templated from the decoder; the differ never templates XTREAM
                    // playlists, so a raw URL here would store credentials. Refuse rather than leak or re-template.
                    if (!isXtreamTemplate(e.url)) throw SourceException(SourceError.Internal("xtream channel url is not templated"))
                    consumer(e)
                }
            }
        }
    }

    // ---------------------------------------------------------------- after the merge

    private suspend fun storeDetected(p: PlaylistEntity, secret: PlaylistSecret, d: DetectedCredentials) {
        Redact.registerSecret(d.username)
        Redact.registerSecret(d.password)
        if (secret.username == d.username && secret.password == d.password) return
        playlists.putSecret(p.credentialRef, secret.copy(username = d.username, password = d.password))
    }

    private suspend fun saveLogin(p: PlaylistEntity, account: app.thebox.core.xtream.XtreamAccountInfo, baseIndex: Int) {
        playlists.saveAccount(p.id, account)
        playlistDao.setActiveBaseIndex(p.id, baseIndex)
        runCatching { timeBaseFactory.fromAccount(p.type, account, playlists.timeBaseSettings(p)) }
            .onSuccess { playlists.saveServerTime(p.id, it) }
            .onFailure { BoxLog.w(TAG, "time base unavailable: ${it.javaClass.simpleName}") }
    }

    /** B1: one best-effort login with the detected pair to learn the panel zone for `xc` catch-up. */
    private suspend fun loginDetected(p: PlaylistEntity, d: DetectedCredentials) {
        try {
            val r = xtream.login(XtreamConnection(p.id, listOf(d.base), d.username, d.password, p.userAgent))
            when (r) {
                is Outcome.Ok -> saveLogin(p, r.value.account, p.activeBaseIndex)
                is Outcome.Err -> BoxLog.w(TAG, "detected-credential login failed: ${r.error.code}; previous zone kept")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BoxLog.w(TAG, "detected-credential login unavailable: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Upserts the playlist's EPG sources of one origin, updating the URL in place (S14: ids and user mappings
     * survive a URL change). The real URL goes only into the source's credential blob; `url_hash` hashes the
     * templated/redacted form (B3). Returns true when the set of addresses changed (a pure re-order does not).
     *
     * Rows are paired with [urls] in three passes (decision 2026-11-11, case c), so a link that is still listed
     * never loses its row, and so its manual matches, because another link moved or went away:
     * 1. same address: the exact URL in the row's secret, then the same `url_hash` (the address with secrets and
     *    the query redacted, so a rotated token is the same guide);
     * 2. the rows left over, in ordinal order, take the links left over, in list order (S14: a new host or a
     *    tokenised URL updates in place; restore placeholders fill in order);
     * 3. rows still left over are links the provider withdrew from a non-empty list: deleted, and their matches
     *    cascade with them. Callers never pass an empty list for a playlist header (that case keeps every row).
     * Each kept row's ordinal becomes its link's position.
     */
    private suspend fun syncSources(
        p: PlaylistEntity, origin: String, urls: List<String>, secret: PlaylistSecret, detected: DetectedCredentials? = null,
    ): Boolean {
        val user = detected?.username ?: secret.username
        val pass = detected?.password ?: secret.password
        var changed = false
        // Hash 0 marks a restore placeholder (EpgSourceEntity.isPlaceholder); a real row never stores it.
        val hashes = urls.map { url -> Hash64.of(Redact.text(template(url, user, pass))).let { if (it == EpgSourceEntity.PLACEHOLDER_HASH) 1L else it } }
        val free = sourceDao.forPlaylist(p.id).filter { it.origin == origin }.sortedBy { it.ordinal }.toMutableList()
        val oldUrls = free.associate { it.id to (playlists.secret(it.credentialRef) as? Outcome.Ok)?.value?.urls?.firstOrNull() }
        val claimed = arrayOfNulls<EpgSourceEntity>(urls.size)
        fun claim(match: (Int, EpgSourceEntity) -> Boolean) {
            for (i in urls.indices) if (claimed[i] == null) {
                val row = free.firstOrNull { match(i, it) } ?: continue
                claimed[i] = row
                free.remove(row)
            }
        }
        claim { i, row -> oldUrls[row.id] == urls[i] }
        claim { i, row -> !row.isPlaceholder && row.urlHash == hashes[i] }
        claim { _, _ -> true }
        for (s in free) {
            playlists.deleteSecret(s.credentialRef)
            sourceDao.delete(s.id)
            changed = true
        }
        // Park moved rows on negative ordinals first: (playlist_id, origin, ordinal) is unique.
        claimed.forEachIndexed { i, row ->
            if (row != null && row.ordinal != i) claimed[i] = row.copy(ordinal = -1 - i).also { sourceDao.update(it) }
        }
        urls.forEachIndexed { ordinal, url ->
            val hash = hashes[ordinal]
            val display = PlaylistRepository.displayHost(url)
            val cur = claimed[ordinal]?.let { if (it.ordinal != ordinal) it.copy(ordinal = ordinal).also { m -> sourceDao.update(m) } else it }
            if (cur == null) {
                val ref = playlists.newRef()
                // A row without its secret would fail every guide build with cred.missing: retry on the next refresh.
                val put = playlists.putSecret(ref, PlaylistSecret(listOf(url), headers = secret.headers))
                if (put is Outcome.Err) {
                    BoxLog.w(TAG, "guide login not stored (${put.error.code}); retried next refresh")
                    return@forEachIndexed
                }
                sourceDao.insert(
                    EpgSourceEntity(
                        name = if (urls.size > 1) "${p.name} (${ordinal + 1})" else p.name, origin = origin, playlistId = p.id,
                        ordinal = ordinal, credentialRef = ref, urlHash = hash, displaySource = display,
                        priority = sourceDao.maxPriority() + 1, createdAt = clock.now(),
                    ),
                )
                changed = true
            } else {
                val old = oldUrls[cur.id]
                if (old != url) {
                    // S1: on failure the row keeps its old hash, so a restore placeholder stays a placeholder (inert)
                    // and the next refresh retries; never a live row with no secret.
                    val put = playlists.putSecret(cur.credentialRef, PlaylistSecret(listOf(url), headers = secret.headers))
                    if (put is Outcome.Err) {
                        BoxLog.w(TAG, "guide login not stored (${put.error.code}); retried next refresh")
                        return@forEachIndexed
                    }
                }
                if (cur.urlHash != hash || old != url) {
                    sourceDao.update(cur.copy(urlHash = hash, displaySource = display, etag = null, lastModified = null))
                    changed = true
                }
            }
        }
        return changed
    }

    private fun redactHeader(h: M3uHeader, secret: PlaylistSecret, d: DetectedCredentials?): M3uHeader {
        val user = d?.username ?: secret.username
        val pass = d?.password ?: secret.password
        fun clean(s: String) = Redact.text(template(s, user, pass))
        return h.copy(
            epgUrls = h.epgUrls.map(::clean),
            urlLogo = h.urlLogo?.let(::clean),
            attributes = h.attributes.mapValues { clean(it.value) },
            // B3: the header-level catchup-source is a typed field, not an attribute. Templated only (no Redact):
            // catch-up reads it back and M2 expands {{u}}/{{p}} (code review M1, B-1).
            catchup = h.catchup.copy(source = h.catchup.source?.let { template(it, user, pass) }),
        )
    }

    private suspend fun fail(p: PlaylistEntity, trigger: Trigger, started: Long, error: SourceError): RefreshResult {
        val report = RefreshReport(p.id, p.refreshSeq, started, clock.now(), RefreshOutcome.FAILED, errorCode = error.code)
        playlistDao.setRefreshStatus(p.id, clock.now(), error.code)
        log(p.id, trigger, started, report, error, null)
        BoxLog.w(TAG, "playlist ${p.id} refresh failed: ${error.code}")
        return RefreshResult(report, error)
    }

    private suspend fun log(playlistId: Long, trigger: Trigger, started: Long, report: RefreshReport, error: SourceError?, bytes: Long?) {
        logDao.insert(
            RefreshLogEntity(
                targetType = RefreshLogEntity.TARGET_PLAYLIST, targetId = playlistId, trigger = trigger.name, startedAt = started,
                finishedAt = clock.now(), outcome = report.outcome.name, errorCode = error?.code,
                errorDetail = error?.let { Redact.text(it.toString()) }, bytes = bytes,
                reportJson = Converters.json.encodeToString(RefreshReport.serializer(), report),
            ),
        )
    }

    private suspend fun cancelCheck(): CancelCheck {
        val ctx = coroutineContext
        return CancelCheck { !ctx.isActive }
    }

    companion object {
        private const val TAG = "RefreshRepository"
        const val PLAYLIST_CACHE_DIR = "playlistCacheDir"

        /** `{{base}}/…/{{u}}/{{p}}/…` with each token exactly once: the stored form of an Xtream API channel URL (§1.2.4 ParsedChannel.url). */
        internal fun isXtreamTemplate(url: String): Boolean =
            url.startsWith("{{base}}/") && once(url, "{{u}}") && once(url, "{{p}}")

        private fun once(s: String, token: String): Boolean {
            val i = s.indexOf(token)
            return i >= 0 && s.indexOf(token, i + token.length) < 0
        }

        /** Replaces credential literals with `{{u}}`/`{{p}}` (§3.2.4) before anything derived is stored. */
        fun template(s: String, user: String?, pass: String?): String {
            var r = s
            if (!pass.isNullOrEmpty() && pass.length >= 3) r = r.replace(pass, "{{p}}")
            if (!user.isNullOrEmpty() && user.length >= 3) r = r.replace(user, "{{u}}")
            return r
        }
    }
}
