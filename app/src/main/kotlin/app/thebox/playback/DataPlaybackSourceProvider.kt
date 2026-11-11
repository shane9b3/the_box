package app.thebox.playback

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.StreamCredentials
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.dao.XtreamAccountDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.PlaylistRepository
import app.thebox.player.api.PlaybackSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackSourceProvider] over `:data` (§2.4.2). Learned values go through the column-targeted `PlaylistDao` writes
 * (`setActiveBaseIndex`, `setXtreamOutputLearned`), never the whole-row `PlaylistRepository.update` (review S10).
 *
 * The decrypted [PlaylistSecret] never leaves this class except as the [StreamCredentials] the resolver needs (whose
 * `toString()` is redacted) and as the `XtreamConnection` of the re-login. `baseUrls` is empty for M3U playlists, whose
 * `PlaylistSecret.urls[0]` is the secret M3U URL itself. Every call switches to [io].
 */
@Singleton
class DataPlaybackSourceProvider @Inject constructor(
    private val channels: ChannelRepository,
    private val playlists: PlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val accountDao: XtreamAccountDao,
    private val xtream: XtreamApi,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackSourceProvider {

    private class Refresh(val atMs: Long, val result: Outcome<XtreamAccountInfo>)

    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()
    private val lastRefresh = ConcurrentHashMap<Long, Refresh>()

    /** Not parental-filtered: a tune by identity comes from a UI that already applied the PIN (§2.4.2). */
    override suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo? = withContext(io) {
        // (Review 2026-11-09, M7b) One row read: the channel and its container_ext come from the same row.
        val read = channels.getWithRow(identity) ?: return@withContext null
        val ch = read.channel
        ChannelStreamInfo(
            identity = identity,
            name = ch.name,
            urlTemplate = ch.streamUrl,
            headers = ch.headers,
            xtreamStreamId = ch.xtreamStreamId,
            containerExt = read.row.channel.containerExt,
            kind = ch.kind,
            catchup = ch.catchup,
        )
    }

    override suspend fun playlist(playlistId: Long): PlaylistPlaybackInfo? = withContext(io) {
        playlists.get(playlistId)?.let(::toInfo)
    }

    override suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?> = withContext(io) {
        val p = playlists.get(playlistId) ?: return@withContext Outcome.Err(SourceError.Internal("no playlist"))
        when (val r = playlists.secret(p)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(toCredentials(p, r.value))
        }
    }

    override suspend fun account(playlistId: Long): XtreamAccountInfo? = withContext(io) {
        // (Fix pass M7a) A one-shot read: `playlists.account(id).first()` registered and dropped a Room observer (its
        // triggers are writes that can queue behind an import) on every tune.
        accountDao.get(playlistId)?.let { PlaylistRepository.decodeAccount(it.infoJson) }
    }

    /**
     * `player_api.php` login now (Xtream and Xtream-shaped M3U), stored as the account card, and the server time base
     * refreshed from it (catch-up tokens use the panel zone). Coalesced: at most one login per playlist per
     * [ACCOUNT_REFRESH_MIN_INTERVAL_MS]; later callers get the cached result.
     */
    override suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo> = withContext(io) {
        val lock = refreshLocks.computeIfAbsent(playlistId) { Mutex() }
        lock.withLock {
            val now = System.currentTimeMillis()
            lastRefresh[playlistId]?.let { if (now - it.atMs in 0 until ACCOUNT_REFRESH_MIN_INTERVAL_MS) return@withLock it.result }
            val result = login(playlistId)
            lastRefresh[playlistId] = Refresh(System.currentTimeMillis(), result)
            result
        }
    }

    private suspend fun login(playlistId: Long): Outcome<XtreamAccountInfo> {
        val p = playlists.get(playlistId) ?: return Outcome.Err(SourceError.Internal("no playlist"))
        val secret = when (val r = playlists.secret(p)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        val user = secret.username
        val pass = secret.password
        if (user.isNullOrEmpty() || pass == null) return Outcome.Err(SourceError.Unsupported("no account"))
        val bases = when (p.type) {
            PlaylistType.XTREAM -> secret.urls
            PlaylistType.M3U_URL -> listOfNotNull(secret.urls.firstOrNull()?.let(::origin))
            PlaylistType.M3U_FILE -> emptyList()
        }
        if (bases.isEmpty()) return Outcome.Err(SourceError.Unsupported("no account base"))
        val conn = XtreamConnection(
            playlistId = p.id,
            baseUrls = bases,
            username = user,
            password = pass,
            userAgent = p.userAgent,
            headers = requestHeaders(p, secret),
            preferredBaseIndex = if (p.type == PlaylistType.XTREAM) p.activeBaseIndex.coerceIn(0, bases.size - 1) else 0,
        )
        return try {
            when (val r = xtream.login(conn)) {
                is Outcome.Err -> r
                is Outcome.Ok -> {
                    val account = r.value.account
                    playlists.saveAccount(p.id, account)
                    try {
                        playlists.saveServerTime(p.id, playlists.timeBase(p))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BoxLog.w(TAG, "time base unavailable: ${e.javaClass.simpleName}")
                    }
                    Outcome.Ok(account)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "account refresh failed: ${e.javaClass.simpleName}")
            Outcome.Err(SourceError.Internal(e.javaClass.simpleName))
        }
    }

    override suspend fun saveActiveBaseIndex(playlistId: Long, index: Int) = withContext(io) {
        playlistDao.setActiveBaseIndex(playlistId, index)
    }

    override suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?) = withContext(io) {
        playlistDao.setXtreamOutputLearned(playlistId, output?.takeIf { it != XtreamOutput.AUTO }?.name)
    }

    /** v1.1 failover (stream_source): v1 has none. */
    override suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity> = emptyList()

    companion object {
        private const val TAG = "PlaybackSource"
        const val ACCOUNT_REFRESH_MIN_INTERVAL_MS = 10_000L

        fun toInfo(p: PlaylistEntity) = PlaylistPlaybackInfo(
            playlistId = p.id,
            type = p.type,
            userAgent = p.userAgent?.takeIf { it.isNotBlank() },
            extraHeaders = p.extraHeaders ?: emptyMap(),
            xtreamOutput = output(p.xtreamOutput) ?: XtreamOutput.AUTO,
            xtreamOutputLearned = p.xtreamOutputLearned?.let(::output)?.takeIf { it != XtreamOutput.AUTO },
            activeBaseIndex = p.activeBaseIndex,
            ignoreTlsErrors = p.ignoreTlsErrors,
            maxConnectionsOverride = p.maxConnectionsOverride?.takeIf { it > 0 },
            needsReauth = p.needsReauth,
        )

        /** Null for a plain M3U without a username or secret headers ("Ok(null)" in the contract). */
        fun toCredentials(p: PlaylistEntity, s: PlaylistSecret): StreamCredentials? = when (p.type) {
            PlaylistType.XTREAM -> StreamCredentials(s.urls, s.username, s.password, s.headers)
            else -> if (s.username == null && s.password == null && s.headers.isEmpty()) null
            else StreamCredentials(emptyList(), s.username, s.password, s.headers)
        }

        fun output(name: String?): XtreamOutput? = name?.let { n -> XtreamOutput.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }

        /** `scheme://host[:port]` of an M3U URL (the Xtream base of an Xtream-shaped M3U). */
        fun origin(url: String): String? = try {
            val u = URI(url.trim())
            val host = u.host
            if (u.scheme == null || host == null) null else if (u.port > 0) "${u.scheme}://$host:${u.port}" else "${u.scheme}://$host"
        } catch (e: Exception) {
            null
        }

        private fun requestHeaders(p: PlaylistEntity, s: PlaylistSecret): Map<String, String> {
            val h = LinkedHashMap<String, String>()
            p.extraHeaders?.let { h.putAll(it) }
            h.putAll(s.headers)
            p.userAgent?.takeIf { it.isNotBlank() }?.let { h["User-Agent"] = it }
            return h
        }
    }
}
