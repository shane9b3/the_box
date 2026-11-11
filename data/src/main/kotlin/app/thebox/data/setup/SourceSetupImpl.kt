package app.thebox.data.setup

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.playback.resolve.CredentialExpanderImpl
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.settings.SourceKind
import app.thebox.core.setup.ImportStage
import app.thebox.core.setup.ImportSummary
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupProgress
import app.thebox.core.setup.SetupResultMapper
import app.thebox.core.setup.SetupStrings
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.SourceSetup
import app.thebox.core.setup.StreamProbe
import app.thebox.core.setup.XtreamForm
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.WallClock
import app.thebox.data.repo.EpgMatchRepository
import app.thebox.data.repo.EpgRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.repo.exec
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.work.PlaylistRefreshWorker
import app.thebox.data.work.RefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The refresh / guide work the import progress folds (a seam: tests replace WorkManager). */
interface ImportWork {
    fun start(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean)
    fun cancel(playlistId: Long)
    /** The playlist's one-time refresh: queued or running, and the worker's latest progress line. */
    fun refresh(playlistId: Long): Flow<WorkPhase>
    /** A guide import or patch is queued or running. */
    fun guideBusy(): Flow<Boolean>
}

data class WorkPhase(val active: Boolean, val line: String?)

/** [ImportWork] over WorkManager's unique works (§3.10 of M1: `playlist-refresh-<id>`, `epg-import`, `epg-patch`). */
@Singleton
class WorkManagerImportWork @Inject constructor(private val wm: WorkManager, private val scheduler: RefreshScheduler) : ImportWork {
    override fun start(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean) = scheduler.refreshNow(playlistId, trigger, forceAcceptShrink)

    override fun cancel(playlistId: Long) {
        wm.cancelUniqueWork(RefreshScheduler.refreshName(playlistId))
    }

    override fun refresh(playlistId: Long): Flow<WorkPhase> = wm.getWorkInfosForUniqueWorkFlow(RefreshScheduler.refreshName(playlistId)).map { infos ->
        val live = infos.firstOrNull { !it.state.isFinished }
        WorkPhase(live != null, live?.progress?.getString(PlaylistRefreshWorker.KEY_PROGRESS))
    }

    override fun guideBusy(): Flow<Boolean> = combine(
        wm.getWorkInfosForUniqueWorkFlow(RefreshScheduler.EPG_IMPORT),
        wm.getWorkInfosForUniqueWorkFlow(RefreshScheduler.EPG_PATCH),
    ) { a, b -> (a + b).any { !it.state.isFinished && it.state != WorkInfo.State.CANCELLED } }
}

/**
 * Onboarding and edit-in-place over the M1 pipeline (docs/design/m5-setup-settings.md §2.4, §3.4.4, §3.4.5, §3.6.1).
 *
 * - [testXtream]: one `player_api.php` login, nothing stored.
 * - [saveXtream] / [saveM3u]: `PlaylistRepository.add*` (or, with `existingId`, `replaceSecret` + column writes: the
 *   `playlist_id` and every `(playlist_id, stable_key)` row stay, product #6), then the refresh (`Trigger.ONBOARDING`
 *   for a new playlist, MANUAL for an edit).
 * - [progress]: folds the refresh work, its `refresh_log` row, the guide work and the match counts into the S5
 *   stages; at stage 3 a new import runs the UA probe ([uaProbe]); completes with Finished or Failed.
 * - Never logs a URL or a credential: every username/password is registered with [Redact] before any request.
 */
@Singleton
class SourceSetupImpl @Inject constructor(
    private val db: LibraryDatabase,
    private val playlists: PlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val logDao: RefreshLogDao,
    private val sourceDao: EpgSourceDao,
    private val epg: EpgRepository,
    private val matches: EpgMatchRepository,
    private val xtream: XtreamApi,
    private val work: ImportWork,
    private val probe: StreamProbe,
    private val strings: SetupStrings,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val clock: WallClock,
) : SourceSetup {
    private val mapper = SetupResultMapper(strings)
    private val expander = CredentialExpanderImpl()

    /** When the current import of a playlist started, and why: rows older than that belong to an earlier refresh. */
    private class Started(val atMs: Long, val trigger: Trigger)
    private val started = ConcurrentHashMap<Long, Started>()
    private val probes = ConcurrentHashMap<Long, Deferred<String?>>()

    override suspend fun testXtream(form: XtreamForm): Outcome<XtreamAccountInfo> {
        register(form.username, form.password)
        val conn = XtreamConnection(
            playlistId = TEST_PLAYLIST_ID, baseUrls = listOf(form.server) + form.backups, username = form.username,
            password = form.password, userAgent = form.userAgent,
        )
        return when (val r = xtream.login(conn)) {
            is Outcome.Ok -> Outcome.Ok(r.value.account)
            is Outcome.Err -> r
        }
    }

    override suspend fun saveXtream(form: XtreamForm, existingId: Long?): Outcome<Long> {
        register(form.username, form.password)
        val urls = (listOf(form.server) + form.backups).distinct()
        val id: Long
        if (existingId == null) {
            val name = form.name?.takeIf { it.isNotBlank() } ?: PlaylistRepository.displayHost(form.server)
            id = when (val r = playlists.addXtream(name, urls, form.username, form.password, form.userAgent)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
            if (form.output != "AUTO") playlistDao.setXtreamOutput(id, form.output)
        } else {
            val p = playlistDao.get(existingId) ?: return Outcome.Err(SourceError.Internal("no playlist"))
            if (p.type != PlaylistType.XTREAM) return Outcome.Err(SourceError.Unsupported("type change"))
            // Keep the secret headers; a secret that no longer decrypts (Keystore reset) is replaced whole.
            val headers = (playlists.secret(p) as? Outcome.Ok)?.value?.headers.orEmpty()
            when (val r = playlists.replaceSecret(existingId, PlaylistSecret(urls, form.username, form.password, headers))) {
                is Outcome.Err -> return r
                is Outcome.Ok -> Unit
            }
            playlistDao.setDisplaySource(existingId, PlaylistRepository.displayHost(form.server))
            playlistDao.setActiveBaseIndex(existingId, 0)
            if (form.output != p.xtreamOutput) playlistDao.setXtreamOutput(existingId, form.output)
            if (form.userAgent != p.userAgent) playlistDao.setUserAgent(existingId, form.userAgent)
            id = existingId
        }
        startImport(id, if (existingId == null) Trigger.ONBOARDING else Trigger.MANUAL)
        return Outcome.Ok(id)
    }

    override suspend fun saveM3u(form: M3uForm, existingId: Long?): Outcome<Long> {
        val url = form.url?.takeIf { it.isNotBlank() }
        val file = form.fileUri?.takeIf { it.isNotBlank() }
        if (url == null && file == null) return Outcome.Err(SourceError.NotASource(app.thebox.core.common.NotASourceKind.EMPTY))
        val id: Long
        if (existingId == null) {
            val r = if (url != null) {
                playlists.addM3uUrl(form.name?.takeIf { it.isNotBlank() } ?: PlaylistRepository.displayHost(url), url, form.userAgent)
            } else {
                val f = file!!
                val display = form.fileName?.takeIf { it.isNotBlank() } ?: fileName(f)
                playlists.addM3uFile(form.name?.takeIf { it.isNotBlank() } ?: display, f, display)
            }
            id = when (r) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        } else {
            val p = playlistDao.get(existingId) ?: return Outcome.Err(SourceError.Internal("no playlist"))
            val wantType = if (url != null) PlaylistType.M3U_URL else PlaylistType.M3U_FILE
            if (p.type != wantType) return Outcome.Err(SourceError.Unsupported("type change"))
            val old = (playlists.secret(p) as? Outcome.Ok)?.value
            val address = url ?: file!!
            val secret = (old ?: PlaylistSecret(emptyList())).copy(urls = listOf(address))
            when (val r = playlists.replaceSecret(existingId, secret)) {
                is Outcome.Err -> return r
                is Outcome.Ok -> Unit
            }
            // Review S2 (decision 2026-11-11): a list at another address is another provider. Clearing the stored header
            // makes the next successful download authoritative for the header guides even when it lists none, so the
            // old provider's guides go. A new login or token only (same scheme, host, port and path) keeps them, and
            // so does an old address that cannot be read (nothing to compare: keeping is the safe direction).
            val previous = old?.urls?.firstOrNull()
            if (previous != null && !PlaylistRepository.sameAddress(previous, address)) playlistDao.setHeader(existingId, null)
            playlistDao.setDisplaySource(existingId, if (url != null) PlaylistRepository.displayHost(url) else form.fileName?.takeIf { it.isNotBlank() } ?: fileName(file!!))
            if (form.userAgent != null && form.userAgent != p.userAgent) playlistDao.setUserAgent(existingId, form.userAgent)
            id = existingId
        }
        // Guide links typed on the form become user EPG sources (one guide import each, coalesced by WorkManager).
        val name = playlistDao.get(id)?.name ?: "Guide"
        form.epgUrls.filter { it.isNotBlank() }.distinct().forEachIndexed { i, u ->
            if (epg.addUserSource(if (form.epgUrls.size > 1) "$name (${i + 1})" else name, u) is Outcome.Err) BoxLog.w(TAG, "guide source not stored")
        }
        startImport(id, if (existingId == null) Trigger.ONBOARDING else Trigger.MANUAL)
        return Outcome.Ok(id)
    }

    /** Starts (or restarts) the import of [playlistId] and marks where its progress begins. Also used by refresh-now. */
    fun startImport(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean = false) {
        started[playlistId] = Started(clock.now(), trigger)
        probes.remove(playlistId)?.cancel()
        work.start(playlistId, trigger, forceAcceptShrink)
    }

    override suspend fun cancelImport(playlistId: Long) {
        work.cancel(playlistId)
        probes.remove(playlistId)?.cancel()
    }

    override suspend fun applyGroupsReview(playlistId: Long, hidden: Set<String>, locked: Set<String>, unlocked: Set<String>) {
        val now = clock.now()
        db.writeTransaction {
            val keys = LinkedHashSet<String>()
            usePrepared("SELECT group_key FROM channel_group WHERE playlist_id = ? AND kind = 0") { st ->
                st.bindLong(1, playlistId)
                while (st.step()) keys += st.getText(0)
            }
            val withPrefs = HashSet<String>()
            usePrepared("SELECT group_key FROM group_prefs WHERE playlist_id = ? AND kind = 0") { st ->
                st.bindLong(1, playlistId)
                while (st.step()) withPrefs += st.getText(0)
            }
            for (key in keys) {
                val lockChange: Boolean? = when (key) { in locked -> true; in unlocked -> false; else -> null }
                val isHidden = key in hidden
                // Only rows the review decides: a hidden group, a lock decision, or an existing prefs row to update.
                if (!isHidden && lockChange == null && key !in withPrefs) continue
                exec(
                    """
                    INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (?, 0, ?, ?, ?, ?)
                    ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET hidden = excluded.hidden,
                      locked = CASE WHEN ? = 1 THEN excluded.locked ELSE group_prefs.locked END, updated_at = excluded.updated_at""",
                ) {
                    bindLong(1, playlistId); bindText(2, key); bindLong(3, if (isHidden) 1 else 0)
                    if (lockChange == null) bindNull(4) else bindLong(4, if (lockChange) 1 else 0)
                    bindLong(5, now); bindLong(6, if (lockChange != null) 1 else 0)
                }
            }
        }
    }

    // ------------------------------------------------------------------ progress

    private class Fold(val startAt: Long, val probeWanted: Boolean) {
        val last = HashMap<ImportStage, SetupProgress.Stage>()
        var ready = false
        var terminal = false
        var sawActive = false
        var idleFromTick: Long? = null
        var hasGuide: Boolean? = null
        /** The guide work the refresh queued has been seen running, or logged a run (or the wait timed out). */
        var guideSeen = false
        var guideWaitFromTick: Long? = null
        var adultGroups = 0
    }

    private class Obs(val phase: WorkPhase, val guideBusy: Boolean, val rows: List<RefreshLogEntity>, val tick: Long)

    override fun progress(playlistId: Long): Flow<SetupProgress> = flow {
        val s = started[playlistId]
        val fold = Fold(s?.atMs ?: 0L, s?.trigger == Trigger.ONBOARDING)
        val ticks = flow { var n = 0L; while (true) { emit(n++); delay(TICK_MS) } }
        val obs = combine(work.refresh(playlistId), work.guideBusy(), logDao.observe(RefreshLogEntity.TARGET_PLAYLIST, playlistId), ticks) { a, b, c, t -> Obs(a, b, c, t) }
        emitAll(obs.transformWhile { o -> for (e in step(playlistId, fold, o)) emit(e); !fold.terminal })
    }

    private suspend fun step(id: Long, f: Fold, o: Obs): List<SetupProgress> {
        val out = ArrayList<SetupProgress>(4)
        if (o.phase.active) f.sawActive = true
        // The work's end and the log row arrive through two flows; when the work ended first, read the row directly.
        val row = o.rows.firstOrNull { it.startedAt >= f.startAt }
            ?: if (f.sawActive && !o.phase.active) logDao.latest(RefreshLogEntity.TARGET_PLAYLIST, id).firstOrNull { it.startedAt >= f.startAt } else null
        if (row != null && row.outcome == RefreshOutcome.FAILED.name) {
            f.terminal = true
            out += SetupProgress.Failed(mapper.error(SetupResultMapper.errorFromCode(row.errorCode ?: "internal"), context(id)))
            return out
        }
        if (row == null && f.sawActive && !o.phase.active) {
            f.terminal = true
            out += SetupProgress.Failed(mapper.error(SourceError.Cancelled))
            return out
        }
        val report = row?.reportJson?.let { runCatching { Converters.json.decodeFromString(RefreshReport.serializer(), it) }.getOrNull() }
        val merged = row != null && report != null
        val line = o.phase.line
        val dlDone = merged || line == LINE_UPDATING || line == LINE_MATCHING
        val readDone = merged || line == LINE_MATCHING
        if (o.phase.active || merged) {
            stage(f, out, ImportStage.DOWNLOADING_PLAYLIST, dlDone, row?.bytes?.takeIf { it > 0 }?.let { strings.text(SetupText.STAGE_COUNT_MB, mb(it)) })
        }
        if (dlDone) {
            val count = report?.let { r ->
                if (r.outcome == RefreshOutcome.NOT_MODIFIED) null
                else strings.text(SetupText.STAGE_COUNT_CHANNELS, num(r.entries), num(r.live + r.radio), num(r.movies + r.series))
            }
            stage(f, out, ImportStage.READING_CHANNELS, readDone, count)
        }
        if (readDone) stage(f, out, ImportStage.GROUPS, merged, report?.takeIf { it.outcome != RefreshOutcome.NOT_MODIFIED }?.let { num(it.groupsTotal) })
        if (!merged) return out
        if (!f.ready) {
            f.ready = true
            out += SetupProgress.ReadyToWatch(id)
            f.adultGroups = adultGroups(id)
            if (f.probeWanted) probes.computeIfAbsent(id) { scope.async { uaProbe(id) } }
        }
        val hasGuide = f.hasGuide ?: sourceDao.active().isNotEmpty().also { f.hasGuide = it }
        if (hasGuide && o.guideBusy) {
            f.idleFromTick = null
            f.guideSeen = true
            stage(f, out, ImportStage.DOWNLOADING_GUIDE, false, null)
            return out
        }
        // (2026-11-06, "0 of 13 matched") The refresh queued guide work: wait until it has been seen running or has
        // logged a run, not just for a quiet moment. WorkManager can surface the queued work after the log row arrives,
        // and the counts read before the guide build are all zero. A minute without either signal falls back to done.
        if (hasGuide && report!!.guideWorkQueued && !f.guideSeen) {
            val waitFrom = f.guideWaitFromTick ?: o.tick.also { f.guideWaitFromTick = it }
            val ran = GUIDE_TARGETS.any { t -> logDao.latest(t, null).any { it.startedAt >= f.startAt } }
            if (!ran && o.tick - waitFrom < GUIDE_WAIT_TICKS) {
                stage(f, out, ImportStage.DOWNLOADING_GUIDE, false, null)
                return out
            }
            f.guideSeen = true
        }
        // The guide work is enqueued before the refresh writes its log row; still, wait a moment of quiet so a guide
        // import that is about to be enqueued (EPG patch after matching) is not missed.
        val idleFrom = f.idleFromTick ?: o.tick.also { f.idleFromTick = it }
        if (hasGuide && o.tick - idleFrom < SETTLE_TICKS) return out
        stage(f, out, ImportStage.DOWNLOADING_GUIDE, true, if (hasGuide) null else strings.text(SetupText.STAGE_COUNT_NO_GUIDE))
        val counts = matches.matchCounts(id)
        stage(f, out, ImportStage.MATCHING_GUIDE, true, strings.text(SetupText.STAGE_COUNT_MATCHED, num(counts.matchedWithData), num(counts.live), pct(counts.matchedWithData, counts.live)))
        val note = probes[id]?.let { withTimeoutOrNull(SetupConstants.PROBE_CALL_TIMEOUT_MS + PROBE_WAIT_SLACK_MS) { it.await() } }
        val live = if (report!!.outcome == RefreshOutcome.NOT_MODIFIED) playlistDao.get(id)?.liveCount ?: 0 else report.live + report.radio
        out += SetupProgress.Finished(
            ImportSummary(
                playlistId = id, live = live, vodKept = report.movies + report.series, groups = report.groupsTotal,
                adultGroups = f.adultGroups, epgMatched = counts.matchedWithData, epgTotal = counts.live, uaNote = note,
            ),
        )
        f.terminal = true
        return out
    }

    private fun stage(f: Fold, out: MutableList<SetupProgress>, s: ImportStage, done: Boolean, count: String?) {
        val prev = f.last[s]
        val next = SetupProgress.Stage(s, done || prev?.done == true, count ?: prev?.count)
        if (prev != next) { f.last[s] = next; out += next }
    }

    private suspend fun adultGroups(id: Long): Int = db.read {
        usePrepared("SELECT COUNT(*) FROM channel_group WHERE playlist_id = ? AND kind = 0 AND is_adult = 1 AND missing_since IS NULL") { st ->
            st.bindLong(1, id); if (st.step()) st.getLong(0).toInt() else 0
        }
    }

    private suspend fun context(id: Long): SetupResultMapper.Context {
        val p = playlistDao.get(id) ?: return SetupResultMapper.Context()
        val host = p.displaySource.substringBefore(':')
        val port = p.displaySource.substringAfter(':', "").toIntOrNull()
        return SetupResultMapper.Context(if (p.type == PlaylistType.XTREAM) SourceKind.XTREAM else SourceKind.M3U, host, port, editing = false)
    }

    /** The stored login says every connection is in use (`active ≥ max`). */
    private suspend fun accountFull(playlistId: Long): Boolean = try {
        db.xtreamAccountDao().get(playlistId)?.let { a -> val max = a.maxConnections; val active = a.activeConnections; max != null && active != null && max > 0 && active >= max } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ UA probe (§3.4.5)

    /**
     * After stage 3 of a new import: probe the first live, non-radio channel with the playlist's UA; on FORBIDDEN once
     * more with the VLC preset, and only if that answers MEDIA/HLS store `playlist.user_agent = VLC` (M2 D22: here the
     * onboarding is the user's choice). Returns the account-card note, or null. Never throws; logs verdicts only.
     */
    internal suspend fun uaProbe(playlistId: Long): String? {
        val p = playlistDao.get(playlistId) ?: return null
        if (p.userAgent == SetupConstants.VLC_USER_AGENT) return null
        val target = firstLiveChannel(playlistId) ?: return null
        val secret = (playlists.secret(p) as? Outcome.Ok)?.value ?: return null
        register(secret.username, secret.password)
        val base = secret.urls.getOrNull(p.activeBaseIndex) ?: secret.urls.firstOrNull()
        val ext = if (p.xtreamOutput == "HLS" || p.xtreamOutputLearned == "HLS") "m3u8" else "ts"
        val url = expander.expandCredentials(expander.expandNonSecret(target, base, ext), secret.username, secret.password)
        val headers = LinkedHashMap<String, String>().apply { p.extraHeaders?.let { putAll(it) }; putAll(secret.headers) }
        val first = probe.probe(playlistId, url, headers, p.userAgent)
        return when (first.verdict) {
            ProbeVerdict.LIMIT -> strings.text(SetupText.UA_PROBE_LIMIT_NOTE)
            ProbeVerdict.FORBIDDEN -> {
                val vlc = probe.probe(playlistId, url, headers, SetupConstants.VLC_USER_AGENT)
                if (vlc.verdict == ProbeVerdict.MEDIA || vlc.verdict == ProbeVerdict.HLS) {
                    playlistDao.setUserAgent(playlistId, SetupConstants.VLC_USER_AGENT)
                    BoxLog.i(TAG) { "playlist $playlistId answers VLC only: user agent switched" }
                    strings.text(SetupText.UA_FALLBACK_NOTE)
                } else if (vlc.verdict == ProbeVerdict.LIMIT || accountFull(playlistId)) {
                    // (M5 integration, I-M5-6) Many panels answer a full account with 403, not 458/509: when the login's
                    // account says every connection is in use, both refusals are the limit, not the user agent.
                    strings.text(SetupText.UA_PROBE_LIMIT_NOTE)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** The stored URL template of the first live (kind 0) channel in playlist order, or null. */
    private suspend fun firstLiveChannel(playlistId: Long): String? = db.read {
        usePrepared("SELECT url FROM channel WHERE playlist_id = ? AND kind = 0 AND missing_since IS NULL ORDER BY sort_index, id LIMIT 1") { st ->
            st.bindLong(1, playlistId); if (st.step()) st.getText(0) else null
        }
    }

    private fun register(user: String?, pass: String?) {
        Redact.registerSecret(user)
        Redact.registerSecret(pass)
    }

    private fun num(n: Int) = String.format(java.util.Locale.getDefault(), "%,d", n)
    private fun mb(bytes: Long) = String.format(java.util.Locale.getDefault(), "%,d", (bytes + 524_288) / 1_048_576)
    private fun pct(a: Int, b: Int) = if (b <= 0) "0" else ((a * 100L + b / 2) / b).toString()

    companion object {
        private const val TAG = "SourceSetup"
        /** XtreamApi's per-playlist permit key for a login test before any playlist row exists. */
        const val TEST_PLAYLIST_ID = -1L
        /** The refresh worker's progress lines (RefreshRepository.refreshLocked). */
        const val LINE_UPDATING = "Updating channels"
        const val LINE_MATCHING = "Matching guide data"
        const val TICK_MS = 500L
        /** Quiet ticks after the refresh before the guide counts as done (no guide work queued). */
        const val SETTLE_TICKS = 3L
        /** Ticks to wait for queued guide work to show up before the guide stage counts as done anyway (60 s). */
        const val GUIDE_WAIT_TICKS = 120L
        private val GUIDE_TARGETS = listOf(RefreshLogEntity.TARGET_EPG_BUILD, RefreshLogEntity.TARGET_EPG_PATCH)
        private const val PROBE_WAIT_SLACK_MS = 2_000L

        internal fun fileName(uri: String): String = File(uri.substringBefore('?')).name.ifEmpty { uri.takeLast(40) }
    }
}
