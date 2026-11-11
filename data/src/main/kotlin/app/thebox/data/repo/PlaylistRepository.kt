package app.thebox.data.repo

import app.thebox.core.catchup.CatchupModeSetting
import app.thebox.core.catchup.PlaylistCatchupDefaults
import app.thebox.core.catchup.TimeBaseFactory
import app.thebox.core.catchup.TimeBaseSettings
import app.thebox.core.catchup.TokenZoneSetting
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.Converters
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.XtreamAccountDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.db.entity.XtreamAccountEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlists: CRUD, secrets (Tink blob per playlist, cached in memory), the per-playlist [TimeBase] and
 * catch-up defaults, and the Xtream account card. `display_source` is always a redacted host or file name.
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val accountDao: XtreamAccountDao,
    private val epgSourceDao: EpgSourceDao,
    private val credentials: CredentialStore,
    private val timeBaseFactory: TimeBaseFactory,
    private val clock: WallClock,
) {
    private val secretCache = ConcurrentHashMap<String, PlaylistSecret>()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()
    fun observe(id: Long): Flow<PlaylistEntity?> = playlistDao.observe(id)
    suspend fun get(id: Long): PlaylistEntity? = playlistDao.get(id)
    suspend fun all(): List<PlaylistEntity> = playlistDao.all()

    suspend fun addM3uUrl(name: String, url: String, userAgent: String? = null, secretHeaders: Map<String, String> = emptyMap()): Outcome<Long> =
        add(name, PlaylistType.M3U_URL, displayHost(url), PlaylistSecret(listOf(url), headers = secretHeaders), userAgent)

    /** [uri] is a SAF content URI (or a file path); [displayName] the file name shown in settings. */
    suspend fun addM3uFile(name: String, uri: String, displayName: String): Outcome<Long> =
        add(name, PlaylistType.M3U_FILE, displayName, PlaylistSecret(listOf(uri)), null)

    /** [baseUrls]: login host first, then backup servers. */
    suspend fun addXtream(name: String, baseUrls: List<String>, username: String, password: String, userAgent: String? = null): Outcome<Long> =
        add(name, PlaylistType.XTREAM, displayHost(baseUrls.first()), PlaylistSecret(baseUrls, username, password), userAgent)

    private suspend fun add(name: String, type: PlaylistType, display: String, secret: PlaylistSecret, userAgent: String?): Outcome<Long> {
        val ref = credentials.newRef()
        when (val put = credentials.put(ref, secret)) {
            is Outcome.Err -> return put
            is Outcome.Ok -> Unit
        }
        secretCache[ref] = secret
        val id = playlistDao.insert(
            PlaylistEntity(
                name = name, type = type, credentialRef = ref, displaySource = display,
                sortIndex = playlistDao.maxSortIndex() + 1, userAgent = userAgent, createdAt = clock.now(),
            ),
        )
        return Outcome.Ok(id)
    }

    suspend fun update(playlist: PlaylistEntity) = playlistDao.update(playlist)

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        playlistDao.get(id)?.let { playlistDao.update(it.copy(enabled = enabled)) }
    }

    /** Deletes the playlist, its credential blobs and those of its EPG sources (rows cascade). */
    suspend fun delete(id: Long) {
        val p = playlistDao.get(id) ?: return
        for (s in epgSourceDao.forPlaylist(id)) {
            credentials.delete(s.credentialRef); secretCache.remove(s.credentialRef)
        }
        credentials.delete(p.credentialRef)
        secretCache.remove(p.credentialRef)
        playlistDao.delete(id)
    }

    /** Decrypted secret (cached). A KEY_INVALIDATED/CORRUPT result flags the playlist `needs_reauth`. */
    suspend fun secret(playlist: PlaylistEntity): Outcome<PlaylistSecret> = secret(playlist.credentialRef, playlist.id)

    suspend fun secret(ref: String, playlistId: Long? = null): Outcome<PlaylistSecret> {
        secretCache[ref]?.let { return Outcome.Ok(it) }
        val r = credentials.get(ref)
        when (r) {
            is Outcome.Ok -> secretCache[ref] = r.value
            is Outcome.Err -> {
                val e = r.error
                if (playlistId != null && e is SourceError.Credentials && e.reason != CredentialFailure.UNAVAILABLE) {
                    playlistDao.setNeedsReauth(playlistId, true)
                }
            }
        }
        return r
    }

    suspend fun putSecret(ref: String, secret: PlaylistSecret): Outcome<Unit> {
        val r = credentials.put(ref, secret)
        if (r is Outcome.Ok) secretCache[ref] = secret
        return r
    }

    /** Re-entered password after KEY_INVALIDATED (M5): new blob, `needs_reauth` cleared. */
    suspend fun replaceSecret(playlistId: Long, secret: PlaylistSecret): Outcome<Unit> {
        val p = playlistDao.get(playlistId) ?: return Outcome.Err(SourceError.Internal("no playlist $playlistId"))
        val r = putSecret(p.credentialRef, secret)
        if (r is Outcome.Ok) playlistDao.setNeedsReauth(playlistId, false)
        return r
    }

    fun newRef(): String = credentials.newRef()

    suspend fun deleteSecret(ref: String) {
        credentials.delete(ref)
        secretCache.remove(ref)
    }

    /** TRIM_MEMORY_COMPLETE (§3.9). */
    fun clearSecretCache() = secretCache.clear()

    fun credentialRefExists(ref: String): Boolean = secretCache.containsKey(ref)

    // ---------------------------------------------------------------- time and catch-up

    fun timeBaseSettings(p: PlaylistEntity): TimeBaseSettings = TimeBaseSettings(
        tokenZone = tokenZone(p.tokenZoneSetting),
        correctionHours = p.catchupCorrectionH,
        headerCorrectionHours = header(p)?.catchup?.correctionHours,
        paddingBeforeSec = p.paddingBeforeSec,
        paddingAfterSec = p.paddingAfterSec,
    )

    /**
     * Per-playlist clock model via WP-C's [TimeBaseFactory]: `fromAccount` when an account response is stored
     * (Xtream, and Xtream-shaped M3U after the B1 login), `forM3u` for plain M3U. An Xtream playlist that never
     * logged in falls back to the persisted server zone columns.
     */
    suspend fun timeBase(playlist: PlaylistEntity): TimeBase {
        val settings = timeBaseSettings(playlist)
        val account = accountDao.get(playlist.id)?.let { decodeAccount(it.infoJson) }
        if (account != null) return timeBaseFactory.fromAccount(playlist.type, account, settings)
        if (playlist.type != PlaylistType.XTREAM) return timeBaseFactory.forM3u(settings)
        return timeBaseFactory.fromStored(
            serverZone = playlist.serverZone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
            serverOffset = playlist.serverOffsetSec?.let { runCatching { ZoneOffset.ofTotalSeconds(it) }.getOrNull() },
            clockSkew = Duration.ofSeconds(playlist.clockSkewSec),
            settings = settings,
        )
    }

    suspend fun catchupDefaults(playlist: PlaylistEntity): PlaylistCatchupDefaults {
        // From stored state only (correctness review F2): the secret cache is empty at process start.
        val xtreamShaped = playlist.type != PlaylistType.XTREAM &&
            (accountDao.get(playlist.id) != null || playlistDao.hasXtreamKeyedChannels(playlist.id))
        return PlaylistCatchupDefaults(
            type = playlist.type,
            modeSetting = catchupModeSetting(playlist.catchupModeSetting),
            daysOverride = playlist.catchupDaysOverride,
            header = header(playlist)?.catchup ?: app.thebox.core.source.RawCatchup.NONE,
            xtreamShaped = xtreamShaped,
        )
    }

    /** (M4, §3.8.8) The persisted catch-up probe winner of [playlistId], or null. */
    suspend fun probeWinner(playlistId: Long): String? = playlistDao.catchupProbeWinner(playlistId)

    /** (M4, §3.8.8) Column-targeted write of the probe winner; never the whole-row [update] (M2 N-L13). */
    suspend fun setProbeWinner(playlistId: Long, variant: String?) = playlistDao.setCatchupProbeWinner(playlistId, variant)

    // ---------------------------------------------------------------- account card

    fun account(playlistId: Long): Flow<XtreamAccountInfo?> = accountDao.observe(playlistId).map { it?.let { e -> decodeAccount(e.infoJson) } }

    suspend fun saveAccount(playlistId: Long, info: XtreamAccountInfo) {
        accountDao.upsert(
            XtreamAccountEntity(
                playlistId = playlistId,
                infoJson = Converters.json.encodeToString(XtreamAccountInfo.serializer(), info),
                status = info.status.name,
                expiresAt = info.expiresAtSec?.let { it * 1000 },
                maxConnections = info.maxConnections,
                activeConnections = info.activeConnections,
                fetchedAt = info.measuredAtDeviceMs,
            ),
        )
    }

    /** Persists the zone/offset/skew a fresh TimeBase derived from the account (shown in playlist settings). */
    suspend fun saveServerTime(playlistId: Long, tb: TimeBase) {
        playlistDao.setServerTime(playlistId, tb.serverZone?.id, tb.serverOffset?.totalSeconds, tb.clockSkew.seconds)
    }

    companion object {
        private const val TAG = "PlaylistRepository"

        fun header(p: PlaylistEntity): M3uHeader? = p.headerJson?.let {
            runCatching { Converters.json.decodeFromString(M3uHeader.serializer(), it) }.getOrNull()
        }

        fun decodeAccount(json: String): XtreamAccountInfo? =
            runCatching { Converters.json.decodeFromString(XtreamAccountInfo.serializer(), json) }
                .onFailure { BoxLog.w(TAG, "bad account json") }.getOrNull()

        fun tokenZone(setting: String): TokenZoneSetting = when (setting.uppercase()) {
            "AUTO", "" -> TokenZoneSetting.Auto
            "UTC" -> TokenZoneSetting.Utc
            "DEVICE" -> TokenZoneSetting.Device
            "SERVER" -> TokenZoneSetting.Server
            else -> TokenZoneSetting.Fixed(setting)
        }

        fun catchupModeSetting(setting: String): CatchupModeSetting = when (setting.uppercase()) {
            "AUTO", "" -> CatchupModeSetting.Auto
            "OFF" -> CatchupModeSetting.Off
            else -> runCatching { CatchupModeSetting.Forced(CatchupMode.valueOf(setting.uppercase())) }.getOrDefault(CatchupModeSetting.Auto)
        }

        /**
         * True when two playlist addresses point at the same list (decision 2026-11-11, review S2): the same scheme,
         * host and port (case-insensitive, default ports filled in) and the same path (a trailing slash ignored).
         * User info, query and fragment are ignored, so a new login or token in `get.php?username=…&password=…`
         * is the same address; another host, port or path is not. Unparseable input compares without its query.
         */
        fun sameAddress(a: String, b: String): Boolean {
            fun key(s: String): String = runCatching {
                val u = URI(s.trim())
                val scheme = u.scheme?.lowercase() ?: return@runCatching null
                val port = if (u.port > 0) u.port else when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
                val host = (u.host ?: u.rawAuthority?.substringAfterLast('@'))?.lowercase().orEmpty()
                "$scheme://$host:$port${u.rawPath.orEmpty().trimEnd('/')}"
            }.getOrNull() ?: s.trim().substringBefore('?').trimEnd('/')
            return key(a) == key(b)
        }

        /** "host:port" (no user info, path or query): safe to show and to store in clear. */
        fun displayHost(url: String): String = runCatching {
            val u = URI(url.trim())
            val host = u.host ?: return@runCatching null
            if (u.port > 0) "$host:${u.port}" else host
        }.getOrNull() ?: app.thebox.core.common.Redact.text(url).take(80)
    }
}
