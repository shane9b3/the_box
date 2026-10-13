package app.thebox.data.testutil

import app.thebox.core.catchup.TimeBaseFactory
import app.thebox.core.catchup.TimeBaseSettings
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.m3u.M3uParseOptions
import app.thebox.core.m3u.M3uParseSummary
import app.thebox.core.m3u.M3uPlaylistParser
import app.thebox.core.m3u.M3uSink
import app.thebox.core.model.TimeBase
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamEpgListing
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.FetchRequest
import app.thebox.data.contracts.FetchResult
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.SourceFetcher
import app.thebox.data.contracts.XtreamAction
import app.thebox.data.contracts.XtreamApi
import app.thebox.data.contracts.XtreamConnection
import app.thebox.data.contracts.XtreamLogin
import app.thebox.data.repo.EpgWorkRequester
import app.thebox.data.repo.Trigger
import okio.BufferedSource
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeCredentialStore : CredentialStore {
    val secrets = ConcurrentHashMap<String, PlaylistSecret>()
    var failGet: SourceError? = null
    override fun newRef(): String = "cred-" + UUID.randomUUID()
    override suspend fun put(ref: String, secret: PlaylistSecret): Outcome<Unit> { secrets[ref] = secret; return Outcome.Ok(Unit) }
    override suspend fun get(ref: String): Outcome<PlaylistSecret> =
        failGet?.let { Outcome.Err(it) } ?: secrets[ref]?.let { Outcome.Ok(it) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
    override suspend fun delete(ref: String) { secrets.remove(ref) }
}

/** Serves [bodies] by URL; [notModified] URLs answer 304 when validators are sent. */
class FakeFetcher : SourceFetcher {
    val bodies = HashMap<String, String>()
    val errors = HashMap<String, SourceError>()
    val notModified = HashSet<String>()
    val requests: MutableList<FetchRequest> = java.util.Collections.synchronizedList(ArrayList())
    /** Simulated download time; with [maxInFlight] it shows whether two runs download at once. */
    @Volatile var delayMs = 0L
    val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun fetch(request: FetchRequest, dest: File): Outcome<FetchResult> {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            return fetchNow(request, dest)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun fetchNow(request: FetchRequest, dest: File): Outcome<FetchResult> {
        requests += request
        errors[request.url]?.let { return Outcome.Err(it) }
        if (request.url in notModified && (request.etag != null || request.lastModified != null)) {
            return Outcome.Ok(FetchResult(true, 0, request.etag, request.lastModified, null, null))
        }
        val body = bodies[request.url] ?: return Outcome.Err(SourceError.Http(404))
        dest.parentFile?.mkdirs()
        dest.writeText(body)
        return Outcome.Ok(FetchResult(false, body.length.toLong(), "\"e-${body.hashCode()}\"", "Mon, 01 Jan 2026 00:00:00 GMT", "audio/x-mpegurl", null))
    }
}

class FakeTimeBaseFactory : TimeBaseFactory {
    var calls = 0
    override fun fromAccount(type: PlaylistType, info: XtreamAccountInfo, settings: TimeBaseSettings): TimeBase {
        calls++
        return TimeBase(serverZone = info.server.timezone?.let { ZoneId.of(it) })
    }
    override fun forM3u(settings: TimeBaseSettings): TimeBase = TimeBase(templateZone = java.time.ZoneOffset.UTC)
}

class FakeXtreamApi : XtreamApi {
    var login: Outcome<XtreamLogin> = Outcome.Err(SourceError.Http(500))
    val logins = ArrayList<XtreamConnection>()
    val downloads = HashMap<XtreamAction, String>()
    override suspend fun login(conn: XtreamConnection): Outcome<XtreamLogin> { logins += conn; return login }
    override suspend fun download(conn: XtreamConnection, action: XtreamAction, params: Map<String, String>, dest: File): Outcome<FetchResult> {
        val body = downloads[action] ?: return Outcome.Err(SourceError.Http(404))
        dest.parentFile?.mkdirs(); dest.writeText(body)
        return Outcome.Ok(FetchResult(false, body.length.toLong(), null, null, "application/json", null))
    }
    override suspend fun shortEpg(conn: XtreamConnection, streamId: Long, timeBase: TimeBase, limit: Int): Outcome<List<XtreamEpgListing>> = Outcome.Ok(emptyList())
    override suspend fun simpleDataTable(conn: XtreamConnection, streamId: Long, timeBase: TimeBase): Outcome<List<XtreamEpgListing>> = Outcome.Ok(emptyList())
}

class FakeRequester : EpgWorkRequester {
    val imports = ArrayList<Trigger>()
    var patches = 0
    override fun importEpgNow(trigger: Trigger) { imports += trigger }
    override fun patchEpg() { patches++ }
}

/**
 * Minimal M3U reader for :data tests (WP-A's parser replaces it in the integration tests): `#EXTM3U url-tvg="…"`,
 * `#EXTINF:-1 tvg-id="…" group-title="…",Name` then the URL line.
 */
class FakeM3uParser : M3uPlaylistParser {
    private val attr = Regex("""([a-zA-Z-]+)="([^"]*)"""")
    override fun parse(source: BufferedSource, options: M3uParseOptions, sink: M3uSink, cancel: CancelCheck): M3uParseSummary {
        var header = M3uHeader()
        var headerSent = false
        var pending: Map<String, String>? = null
        var pendingName = ""
        var n = 0
        while (true) {
            val line = source.readUtf8Line() ?: break
            val t = line.trim()
            when {
                t.startsWith("#EXTM3U") -> {
                    val a = attr.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    header = M3uHeader(
                        epgUrls = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                        // Like WP-A's HeaderAttrs: catchup-source is a typed field, not an attribute.
                        catchup = app.thebox.core.source.RawCatchup(source = a["catchup-source"]),
                        attributes = a - "catchup-source",
                    )
                }
                t.startsWith("#EXTINF") -> {
                    if (!headerSent) { sink.onHeader(header); headerSent = true }
                    pending = attr.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingName = t.substringAfterLast(',').trim()
                }
                t.isNotEmpty() && !t.startsWith("#") && pending != null -> {
                    val a = pending!!
                    sink.onEntry(ParsedChannel(app.thebox.core.source.EntryKind.LIVE, pendingName, t, n++, a["group-title"], tvgId = a["tvg-id"], tvgName = a["tvg-name"]))
                    pending = null
                }
            }
        }
        if (!headerSent) sink.onHeader(header)
        return M3uParseSummary(n, n, 0, 0, 0, 0, 0, "UTF-8", true, header = header)
    }
}
