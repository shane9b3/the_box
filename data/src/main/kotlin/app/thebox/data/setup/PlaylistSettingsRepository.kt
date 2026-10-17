package app.thebox.data.setup

import android.content.Context
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgStore
import app.thebox.core.match.MatchCandidate
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.legacyUnlocked
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.setup.EditableSource
import app.thebox.core.setup.GroupsReviewSource
import app.thebox.core.setup.GuideMatching
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.MatchTotals
import app.thebox.core.setup.NowAiring
import app.thebox.core.setup.PlaylistAdmin
import app.thebox.core.setup.PlaylistDetail
import app.thebox.core.setup.PlaylistSummary
import app.thebox.core.setup.PrefixPreview
import app.thebox.core.setup.RemovalCounts
import app.thebox.core.setup.ReviewGroup
import app.thebox.core.setup.UnmatchedChannel
import app.thebox.core.setup.XtreamForm
import app.thebox.core.source.PlaylistType
import app.thebox.core.text.NamePrefixRules
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.EpgChannelSearchDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.RefreshLogDao
import app.thebox.data.db.dao.UnmatchedRow
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.guide.DisplayNames
import app.thebox.data.repo.ChannelPrefsRepository
import app.thebox.data.repo.EpgMatchRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.SyncStatusRepository
import app.thebox.data.repo.Trigger
import app.thebox.data.repo.read
import app.thebox.data.work.RefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ports of WP-B's playlist and matching pages over :data (docs/design/m5-setup-settings.md §3.3.1, §3.4.6, §3.6):
 * every per-playlist write is one column-targeted `PlaylistDao` update (never a whole-row update from a stale entity),
 * secrets stay in the Tink store (headers named Authorization/Cookie go into the credential blob), and the matching
 * calls go to [EpgMatchRepository] / [EpgChannelSearchDao] with the caller's [ParentalAccess] (S10).
 */
@Singleton
class PlaylistSettingsRepository @Inject constructor(
    private val db: LibraryDatabase,
    private val playlists: PlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val logDao: RefreshLogDao,
    private val sync: SyncStatusRepository,
    private val setup: SourceSetupImpl,
    private val scheduler: RefreshScheduler,
    private val displayNames: DisplayNames,
    private val matches: EpgMatchRepository,
    private val search: EpgChannelSearchDao,
    private val channelDao: ChannelDao,
    private val prefs: ChannelPrefsRepository,
    private val store: EpgStore,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaylistAdmin, GroupsReviewSource, GuideMatching {

    // ------------------------------------------------------------------ PlaylistAdmin: reads

    override fun observeAll(): Flow<List<PlaylistSummary>> =
        combine(playlistDao.observeAll(), sync.status) { list, status -> list to status }.map { (list, status) ->
            list.map { summarize(it, status.playlistsRefreshing, status.progress) }
        }

    override fun observe(playlistId: Long): Flow<PlaylistDetail?> = combine(
        playlistDao.observe(playlistId),
        playlists.account(playlistId),
        logDao.observe(RefreshLogEntity.TARGET_PLAYLIST, playlistId),
        sync.status,
    ) { p, account, log, status -> Quad(p, account, log, status) }.map { (p, account, log, status) ->
        p ?: return@map null
        val secret = (playlists.secret(p) as? Outcome.Ok)?.value
        val report = log.firstOrNull { it.reportJson != null && it.outcome != "FAILED" }?.reportJson
            ?.let { runCatching { Converters.json.decodeFromString(RefreshReport.serializer(), it) }.getOrNull() }
        PlaylistDetail(
            summary = summarize(p, status.playlistsRefreshing, status.progress, account?.expiresAtSec),
            account = account,
            serverZone = p.serverZone,
            clockSkewSec = p.clockSkewSec,
            updateOnStart = p.updateOnStart,
            numberingMode = p.numberingMode,
            namePrefixMode = p.namePrefixMode,
            namePrefixCustom = p.namePrefixCustom,
            refreshIntervalH = p.refreshIntervalH,
            orderMode = p.orderMode,
            usePlaylistEpg = p.usePlaylistEpg,
            userAgent = p.userAgent,
            headers = p.extraHeaders.orEmpty(),
            secretHeaderCount = secret?.headers?.size ?: 0,
            xtreamOutput = p.xtreamOutput,
            xtreamOutputLearned = p.xtreamOutputLearned,
            backupHosts = if (p.type == PlaylistType.XTREAM) secret?.urls?.drop(1)?.map(PlaylistRepository::displayHost).orEmpty() else emptyList(),
            activeBaseIndex = p.activeBaseIndex,
            catchupModeSetting = p.catchupModeSetting,
            catchupDaysOverride = p.catchupDaysOverride,
            tokenZoneSetting = p.tokenZoneSetting,
            paddingBeforeSec = p.paddingBeforeSec,
            paddingAfterSec = p.paddingAfterSec,
            maxConnectionsOverride = p.maxConnectionsOverride,
            ignoreTlsErrors = p.ignoreTlsErrors,
            lastReport = report,
        )
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private suspend fun summarize(p: PlaylistEntity, refreshing: Set<Long>, line: String?, expiresAtSec: Long? = null) = PlaylistSummary(
        id = p.id, uid = p.uid, name = p.name, type = p.type, displaySource = p.displaySource, liveCount = p.liveCount,
        enabled = p.enabled, needsReauth = p.needsReauth, lastRefreshAtMs = p.lastRefreshAt, lastSuccessAtMs = p.lastSuccessAt,
        lastStatus = p.lastStatus,
        expiresAtSec = expiresAtSec ?: if (p.type == PlaylistType.XTREAM) accountExpiry(p.id) else null,
        refreshing = p.id in refreshing, progressLine = if (p.id in refreshing) line else null,
    )

    private suspend fun accountExpiry(playlistId: Long): Long? = db.read {
        usePrepared("SELECT expires_at FROM xtream_account WHERE playlist_id = ?") { st ->
            st.bindLong(1, playlistId); if (st.step() && !st.isNull(0)) st.getLong(0) / 1000 else null
        }
    }

    override suspend fun summary(playlistId: Long): PlaylistSummary? = playlistDao.get(playlistId)?.let { summarize(it, emptySet(), null) }

    override suspend fun editableSource(playlistId: Long): EditableSource? {
        val p = playlistDao.get(playlistId) ?: return null
        val s = (playlists.secret(p) as? Outcome.Ok)?.value ?: return null
        return when (p.type) {
            PlaylistType.XTREAM -> EditableSource.Xtream(
                XtreamForm(p.name, s.urls.firstOrNull().orEmpty(), s.username.orEmpty(), s.password.orEmpty(), p.xtreamOutput, p.userAgent, s.urls.drop(1)),
            )
            PlaylistType.M3U_URL -> EditableSource.M3u(M3uForm(p.name, s.urls.firstOrNull(), null, emptyList(), p.userAgent))
            PlaylistType.M3U_FILE -> EditableSource.M3u(M3uForm(p.name, null, s.urls.firstOrNull(), emptyList(), null))
        }
    }

    override suspend fun removalCounts(playlistId: Long): RemovalCounts = db.read {
        val channels = usePrepared("SELECT COUNT(*) FROM channel WHERE playlist_id = ? AND kind IN (0, 1) AND missing_since IS NULL") { st -> st.bindLong(1, playlistId); st.step(); st.getLong(0).toInt() }
        val favourites = usePrepared("SELECT COUNT(*) FROM favourite WHERE playlist_id = ?") { st -> st.bindLong(1, playlistId); st.step(); st.getLong(0).toInt() }
        val reminders = usePrepared("SELECT COUNT(*) FROM reminder WHERE playlist_id = ?") { st -> st.bindLong(1, playlistId); st.step(); st.getLong(0).toInt() }
        RemovalCounts(channels, favourites, reminders)
    }

    // ------------------------------------------------------------------ PlaylistAdmin: writes

    override suspend fun remove(playlistId: Long) {
        scheduler.cancelPlaylist(playlistId)
        playlists.delete(playlistId)
    }

    override fun refreshNow(playlistId: Long, forceAcceptShrink: Boolean) = setup.startImport(playlistId, Trigger.MANUAL, forceAcceptShrink)

    override fun refreshAll() {
        scope.launch { for (p in playlistDao.all()) if (p.enabled) setup.startImport(p.id, Trigger.MANUAL) }
    }

    override suspend fun addSingleStream(name: String?, url: String, userAgent: String?): Outcome<Long> {
        val title = name?.takeIf { it.isNotBlank() } ?: PlaylistRepository.displayHost(url)
        val file = withContext(io) {
            val dir = File(context.filesDir, SINGLE_DIR).apply { mkdirs() }
            File(dir, "single-${UUID.randomUUID()}.m3u").also { f ->
                f.writeText("#EXTM3U\n#EXTINF:-1,${title.replace('\n', ' ').replace(',', ' ')}\n${url.trim()}\n")
            }
        }
        return when (val r = playlists.addM3uFile(title, "file://" + file.absolutePath, title)) {
            is Outcome.Err -> { withContext(io) { file.delete() }; r }
            is Outcome.Ok -> {
                if (userAgent != null) playlistDao.setUserAgent(r.value, userAgent)
                setup.startImport(r.value, Trigger.ONBOARDING)
                r
            }
        }
    }

    override suspend fun rename(playlistId: Long, name: String) = playlistDao.setName(playlistId, name.trim().take(NAME_MAX))
    override suspend fun setUpdateOnStart(playlistId: Long, on: Boolean) = playlistDao.setUpdateOnStart(playlistId, on)
    override suspend fun setNumbering(playlistId: Long, mode: String) = playlistDao.setNumberingMode(playlistId, mode)

    override suspend fun setNamePrefix(playlistId: Long, mode: String, customJson: String?) {
        // CUSTOM before the rule is typed: remember the choice; names keep the current rule until there is one.
        if (mode == NamePrefixRules.CUSTOM && customJson.isNullOrBlank()) {
            playlistDao.setNamePrefix(playlistId, mode, null)
            return
        }
        displayNames.setRule(playlistId, NamePrefixRules.parse(mode, customJson))
    }

    override suspend fun previewNamePrefix(playlistId: Long, mode: String, customJson: String?): PrefixPreview {
        val total = playlistDao.get(playlistId)?.liveCount ?: 0
        val changed = displayNames.previewCount(playlistId, NamePrefixRules.parse(mode, customJson))
        return PrefixPreview(changed, maxOf(total, changed))
    }

    override suspend fun setEnabled(playlistId: Long, on: Boolean) {
        playlistDao.setEnabled(playlistId, on)
        playlistDao.get(playlistId)?.let(scheduler::schedulePlaylist)
    }

    override suspend fun setRefreshInterval(playlistId: Long, hours: Int) {
        playlistDao.setRefreshInterval(playlistId, hours.coerceAtLeast(0))
        playlistDao.get(playlistId)?.let(scheduler::schedulePlaylist)
    }

    override suspend fun setOrder(playlistId: Long, mode: String) = playlistDao.setOrderMode(playlistId, mode)
    override suspend fun setUsePlaylistEpg(playlistId: Long, on: Boolean) = playlistDao.setUsePlaylistEpg(playlistId, on)
    override suspend fun setUserAgent(playlistId: Long, userAgent: String?) = playlistDao.setUserAgent(playlistId, userAgent?.takeIf { it.isNotBlank() })

    /** Authorization and Cookie are secrets: they go into the credential blob, never into `extra_headers_json`. */
    override suspend fun setHeader(playlistId: Long, name: String, value: String?) {
        val p = playlistDao.get(playlistId) ?: return
        if (name.equals("Range", ignoreCase = true)) return
        if (isSecretHeader(name)) {
            val s = (playlists.secret(p) as? Outcome.Ok)?.value ?: return
            val h = s.headers.filterKeys { !it.equals(name, ignoreCase = true) }.toMutableMap()
            if (value != null) h[name] = value
            playlists.putSecret(p.credentialRef, s.copy(headers = h))
        } else {
            val h = p.extraHeaders.orEmpty().filterKeys { !it.equals(name, ignoreCase = true) }.toMutableMap()
            if (value != null) h[name] = value
            playlistDao.setExtraHeaders(playlistId, h.takeIf { it.isNotEmpty() })
        }
    }

    override suspend fun setStreamFormat(playlistId: Long, value: String) = playlistDao.setXtreamOutput(playlistId, value)

    override suspend fun setBackupServers(playlistId: Long, urls: List<String>): Outcome<Unit> {
        val p = playlistDao.get(playlistId) ?: return Outcome.Err(SourceError.Internal("no playlist"))
        val s = when (val r = playlists.secret(p)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
        val main = s.urls.firstOrNull() ?: return Outcome.Err(SourceError.Internal("no server"))
        val next = PlaylistSecret((listOf(main) + urls).distinct(), s.username, s.password, s.headers)
        val r = playlists.putSecret(p.credentialRef, next)
        if (r is Outcome.Ok && p.activeBaseIndex >= next.urls.size) playlistDao.setActiveBaseIndex(playlistId, 0)
        return r
    }

    override suspend fun setCatchupMode(playlistId: Long, value: String) = playlistDao.setCatchupModeSetting(playlistId, value)
    override suspend fun setCatchupDays(playlistId: Long, days: Int?) = playlistDao.setCatchupDaysOverride(playlistId, days?.coerceIn(1, 30))
    override suspend fun setCatchupZone(playlistId: Long, value: String) = playlistDao.setTokenZoneSetting(playlistId, value)

    override suspend fun setPadding(playlistId: Long, beforeSec: Int?, afterSec: Int?) {
        beforeSec?.let { playlistDao.setPaddingBefore(playlistId, it.coerceAtLeast(0)) }
        afterSec?.let { playlistDao.setPaddingAfter(playlistId, it.coerceAtLeast(0)) }
    }

    override suspend fun setMaxConnections(playlistId: Long, max: Int?) = playlistDao.setMaxConnectionsOverride(playlistId, max?.coerceIn(1, 10))
    override suspend fun setIgnoreTls(playlistId: Long, on: Boolean) = playlistDao.setIgnoreTlsErrors(playlistId, on)

    // ------------------------------------------------------------------ GroupsReviewSource

    override suspend fun groups(playlistId: Long): List<ReviewGroup> = db.read {
        usePrepared(
            """
            SELECT g.group_key, g.name, g.is_adult, COALESCE(gp.hidden, 0), gp.locked,
                   (SELECT COUNT(*) FROM channel c WHERE c.group_id = g.id AND c.missing_since IS NULL)
            FROM channel_group g
            LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
            WHERE g.playlist_id = ? AND g.kind = 0 AND g.missing_since IS NULL
            ORDER BY g.sort_index, g.id""",
        ) { st ->
            st.bindLong(1, playlistId)
            buildList {
                while (st.step()) add(
                    ReviewGroup(
                        key = st.getText(0), name = st.getText(1), adult = st.getLong(2) != 0L, hidden = st.getLong(3) != 0L,
                        lockedPref = if (st.isNull(4)) null else st.getLong(4) != 0L, channels = st.getLong(5).toInt(),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ GuideMatching

    override fun unmatched(access: ParentalAccess): Flow<List<UnmatchedChannel>> = matches.unmatched(access).map { rows -> rows.map(::toUnmatched) }

    override fun matchedWithoutData(access: ParentalAccess): Flow<List<UnmatchedChannel>> =
        matches.matchedWithoutData(access.legacyUnlocked).map { rows -> rows.map(::toUnmatched) }

    private fun toUnmatched(r: UnmatchedRow) = UnmatchedChannel(
        identity = ChannelIdentity(r.playlistId, app.thebox.core.model.StableKey(r.stableKey)), name = r.name, groupName = r.groupName,
        watchCount = r.watchCount, bestScore = r.bestSuggestion?.split('\u001F')?.getOrNull(2)?.toFloatOrNull(),
    )

    override suspend fun totals(playlistId: Long): MatchTotals = matches.matchCounts(playlistId).let { MatchTotals(it.live, it.matched, it.matchedWithData) }

    override suspend fun channelName(channel: ChannelIdentity): String? = channelDao.get(channel.playlistId, channel.stableKey.value)?.name

    override suspend fun suggestions(channel: ChannelIdentity, limit: Int): List<MatchCandidate> = withContext(io) { matches.suggest(channel, store, limit) }

    override suspend fun search(query: String, limit: Int): List<EpgChannelInfo> = search.searchEpgChannels(query, limit)

    override suspend fun nowAiring(refs: List<EpgChannelRef>, nowMs: Long): Map<EpgChannelRef, NowAiring> = withContext(io) {
        val reader = store.openReader() ?: return@withContext emptyMap()
        reader.use { r ->
            val ids = r.resolve(refs)
            val nowMin = (nowMs / 60_000).toInt()
            val out = HashMap<EpgChannelRef, NowAiring>()
            refs.forEachIndexed { i, ref ->
                val id = ids.getOrElse(i) { -1 }
                if (id < 0) return@forEachIndexed
                r.at(id, nowMin, 0)?.let { out[ref] = NowAiring(it.title, it.startMin * 60_000L, it.endMin * 60_000L) }
            }
            out
        }
    }

    override suspend fun override(channel: ChannelIdentity): EpgChannelRef? =
        matches.override(channel)?.let { m -> if (m.sourceId != null && m.xmltvId != null) EpgChannelRef(m.sourceId, m.xmltvId) else null }

    override suspend fun hasOverride(channel: ChannelIdentity): Boolean = matches.override(channel) != null

    override suspend fun choose(channel: ChannelIdentity, ref: EpgChannelRef) {
        matches.setOverride(channel, ref) // requests the EPG patch for the new ref
        try {
            withContext(io) { matches.matchIncremental(channel.playlistId, store) }
        } catch (e: NotImplementedError) {
            // No EPG store in this build: the patch request above is enough.
        }
    }

    override suspend fun clearChoice(channel: ChannelIdentity) = matches.clearOverride(channel)

    override suspend fun channelShift(channel: ChannelIdentity): Int = prefs.get(channel)?.epgShiftMin ?: 0

    override suspend fun setChannelShift(channel: ChannelIdentity, minutes: Int) = prefs.setEpgShift(channel, minutes.takeIf { it != 0 })

    companion object {
        /** `filesDir/playlists/`: one-entry playlist files of "Add as a single channel" (§3.4.4). */
        const val SINGLE_DIR = "playlists"
        private const val NAME_MAX = 80

        /**
         * Headers kept with the login (sealed in backups, never in `extra_headers_json`). (D-L1, code review 2026-10-16)
         * Any name that mentions auth, token, key or session counts (`X-Api-Key`, `X-Auth-Token`, `Session-Id`), beside
         * Cookie; a false positive only keeps a harmless header out of a backup without a passphrase.
         */
        fun isSecretHeader(name: String): Boolean {
            val n = name.lowercase()
            return n == "cookie" || SECRET_HEADER_WORDS.any { it in n }
        }

        private val SECRET_HEADER_WORDS = listOf("auth", "token", "key", "session")
    }
}
