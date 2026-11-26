package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.thebox.core.common.BoxLog
import app.thebox.player.internal.StreamSessionContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * (HLS edge hand-over, stage 0: docs/review/2026-11-25-hls-edge-handover-design-review.md B1 and "Recommended first
 * step") Logging only, for live HLS sessions only, in debug builds only. One `BoxLog` tag, [TAG], at DEBUG.
 *
 * **Gate.** [enabled] is `BoxLog.minLevel <= DEBUG`, which `TheBoxApplication` sets from `BuildConfig.DEBUG` (release:
 * INFO). When it is off, [begin] returns null, `MediaSourceBuilder` builds exactly the source it always built, and no
 * wrapper, listener or session exists; every other hook first checks [isTracked], which is then always false.
 *
 * **What it never logs.** No path, query, token, credential, full URL, `Location` value, header value or IP address.
 * Hosts are printed as `host[:port]`, but an IP-literal host prints as `ip#<hash>[:port]`. Connected IPs, segment file
 * names and playlist identities print only as [hash]: SHA-256 over a random per-process salt (never logged), cut to 6
 * hex, so values compare within one run and cannot be recovered. Exceptions print as simple class names only.
 *
 * **Volume.** Routine requests (2xx, reused connection, no redirect) and routine playlists (media sequence advanced) go
 * to a per-session flight recorder instead of the log; it is dumped at the first request failure and at each player
 * failure. Everything prints for [VERBOSE_WINDOW_MS] after a session start and after any failure. Steady state prints
 * one `agg` line per [AGG_WINDOW_MS] and one `pl` line per [PL_STEADY_MS] per playlist; a global cap of
 * [LINES_PER_HOUR] drops routine lines past it (failures, decisions and boundaries always print).
 */
@OptIn(UnstableApi::class)
internal object HlsEdgeProbe {
    const val TAG = "HlsEdge"
    const val VERBOSE_WINDOW_MS = 60_000L
    const val AGG_WINDOW_MS = 60_000L
    const val PL_STEADY_MS = 30_000L
    const val RECORDER_LINES = 24
    const val LINES_PER_HOUR = 1_500
    const val PENDING_BOUNDARY_MS = 180_000L
    private const val MAX_SESSIONS = 8

    /** True in debug builds (the app's DEBUG log level). */
    val enabled: Boolean get() = BoxLog.minLevel <= BoxLog.DEBUG

    /** Monotonic ms (test hook). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Where lines go (test hook); BoxLog DEBUG by default. */
    @Volatile var out: (String) -> Unit = { line -> BoxLog.d(TAG) { line } }

    private val salt by lazy { ByteArray(16).also { SecureRandom().nextBytes(it) } }
    private val sessions = ConcurrentHashMap<Long, HlsEdgeSession>()
    private val connections = WeakHashMap<Connection, ConnInfo>()
    private val rateLock = Any()
    private var rateWindowStart = Long.MIN_VALUE
    private var rateCount = 0
    private var suppressed = 0
    private val boundaryLock = Any()
    private var pending: PendingBoundary? = null

    /** Per-thread hand-over from [HlsEdgeDataSource.open] to the `Call.Factory` that creates the call. */
    private val current = ThreadLocal<ReqTrace?>()

    // ---------------------------------------------------------------- lifecycle (MediaSourceBuilder, TransportFactory)

    /** A live HLS session starts; null (and nothing at all happens) when [enabled] is off. Never throws. */
    fun begin(ctx: StreamSessionContext): HlsEdgeSession? {
        if (!enabled) return null
        return try {
            val url = ctx.resolved.url
            val chan = hash(url.substringBefore('?').substringBefore('#'))
            val origin = url.toHttpUrlOrNull()?.let(::hostLabel) ?: "-"
            val s = HlsEdgeSession(ctx.tuneId, chan, origin, clock())
            sessions[ctx.tuneId] = s
            if (sessions.size > MAX_SESSIONS) sessions.keys.sorted().take(sessions.size - MAX_SESSIONS).forEach { sessions.remove(it) }
            val p = synchronized(boundaryLock) {
                val pb = pending
                when {
                    pb == null -> null
                    pb.chan != chan || s.startedAt - pb.failAt > PENDING_BOUNDARY_MS -> { pending = null; pb.also { it.abandoned = true } }
                    else -> pb
                }
            }
            val after = when {
                p == null -> "-"
                p.abandoned -> "-"
                else -> "fail+${s.startedAt - p.failAt}ms"
            }
            if (p != null && p.abandoned) emit("boundary from=${p.fromTune} to=- result=abandoned fails=${p.fails}", always = true)
            emit("start t=${ctx.tuneId} origin=$origin chan=#$chan after=$after", always = true)
            s
        } catch (t: Throwable) {
            null
        }
    }

    fun isTracked(tuneId: Long): Boolean = sessions.containsKey(tuneId)

    /** `TransportFactory.endSession`: one `end` line; the session's state is dropped. */
    fun end(tuneId: Long) {
        val s = sessions.remove(tuneId) ?: return
        s.ended = true
        guard { s.endLine()?.let { emit(it, always = true) } }
    }

    // ---------------------------------------------------------------- controller hooks (main thread)

    /** What the player said at a failure or decision (U7). [state] is `Player.STATE_*`. */
    class PlayerFacts(val bufferedMs: Long, val liveOffsetMs: Long, val state: Int, val playWhenReady: Boolean) {
        override fun toString(): String =
            "state=${stateName(state)} pwr=${if (playWhenReady) 1 else 0} buf=$bufferedMs off=${if (liveOffsetMs == C.TIME_UNSET) "-" else liveOffsetMs.toString()}"
    }

    fun onPlayerFailure(tuneId: Long, signal: String, facts: PlayerFacts?) {
        val s = sessions[tuneId] ?: return
        guard {
            val now = clock()
            val r = s.onPlayerFailure(now, signal, facts)
            r.dump.forEach { emit(it, always = true) }
            emit(r.line, always = true)
            synchronized(boundaryLock) {
                val pb = pending
                if (pb != null && pb.chan == s.chan && !pb.abandoned && now - pb.failAt <= PENDING_BOUNDARY_MS) {
                    pb.fails++
                } else {
                    pending = r.boundary
                }
            }
        }
    }

    fun onDecision(tuneId: Long, decision: String, signal: String, facts: PlayerFacts?) {
        if (!sessions.containsKey(tuneId)) return
        guard { emit("decision t=$tuneId d=$decision sig=$signal ${facts ?: "state=-"}", always = true) }
    }

    /** First rendered frame of [tuneId]: closes a pending boundary with one summary line. */
    fun onFirstFrame(tuneId: Long) {
        val s = sessions[tuneId] ?: return
        guard {
            val now = clock()
            val pb = synchronized(boundaryLock) {
                val pb = pending ?: return@guard
                if (pb.fromTune == tuneId) return@guard // the failed session itself (a frame before the failure surfaced)
                pending = null
                pb
            }
            emit(s.boundaryLine(pb, now), always = true)
        }
    }

    // ---------------------------------------------------------------- request hand-over (data source, call factory)

    internal fun setCurrent(trace: ReqTrace?) = if (trace == null) current.remove() else current.set(trace)

    /**
     * `BrokeredHttpDataSource`'s call factory: attaches the open's trace (set by [HlsEdgeDataSource]) to [call] as an
     * extra, observe-only `EventListener`. Without a trace on this thread (every non-HLS-live path) it does nothing.
     */
    fun attach(call: Call) {
        val t = current.get() ?: return
        try {
            call.addEventListener(t)
        } catch (_: Throwable) {
            // Observation only.
        }
    }

    // ---------------------------------------------------------------- formatting and hashing

    /** Salted SHA-256 of [value], 6 hex characters. */
    fun hash(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val d = md.digest(value.toByteArray(Charsets.UTF_8))
        return String.format(Locale.ROOT, "%02x%02x%02x", d[0], d[1], d[2])
    }

    /** `host[:port]` (port only when not the scheme default); an IP-literal host as `ip#<hash>`. */
    fun hostLabel(url: HttpUrl): String {
        val h = hostOnly(url.host)
        return if (url.port == HttpUrl.defaultPort(url.scheme)) h else "$h:${url.port}"
    }

    fun hostOnly(host: String): String = if (isIpLiteral(host)) "ip#${hash(host.lowercase(Locale.ROOT))}" else host.lowercase(Locale.ROOT)

    fun ipHash(address: InetAddress?): String = address?.hostAddress?.let { "#" + hash(it) } ?: "-"

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

    fun stateName(state: Int): String = when (state) {
        1 -> "IDLE"
        2 -> "BUFFERING"
        3 -> "READY"
        4 -> "ENDED"
        else -> state.toString()
    }

    /** Simple class names of [t] and its causes (at most 3), `<`-joined. Never a message. */
    fun errName(t: Throwable): String {
        val names = ArrayList<String>(3)
        var c: Throwable? = t
        while (c != null && names.size < 3) {
            names += c.javaClass.simpleName.ifEmpty { "Throwable" }
            c = c.cause.takeIf { it !== c }
        }
        return names.joinToString("<")
    }

    internal class ConnInfo(val firstAt: Long) { var uses = 0 }

    /** First-seen time and use count of [connection] (identity-keyed, weakly held), counting this use. */
    internal fun connectionUse(connection: Connection, now: Long): ConnInfo = synchronized(connections) {
        connections.getOrPut(connection) { ConnInfo(now) }.also { it.uses++ }
    }

    internal fun connectionPeek(connection: Connection): ConnInfo? = synchronized(connections) { connections[connection] }

    /**
     * Rate-limited emit. [always] lines (start/end, failures, decisions, boundaries, request failures) always print;
     * others count against [LINES_PER_HOUR] and are dropped past it (counted in the next `agg`).
     */
    internal fun emit(line: String, always: Boolean = false) {
        val now = clock()
        val ok = synchronized(rateLock) {
            if (rateWindowStart == Long.MIN_VALUE || now - rateWindowStart >= 3_600_000L) {
                rateWindowStart = now
                rateCount = 0
            }
            rateCount++
            if (always || rateCount <= LINES_PER_HOUR) true else { suppressed++; false }
        }
        if (ok) out(line)
    }

    internal fun takeSuppressed(): Int = synchronized(rateLock) { suppressed.also { suppressed = 0 } }

    internal inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Logging only: a bug here must never reach playback.
        }
    }

    /** Test hook: forget sessions, the pending boundary and the rate window. */
    internal fun resetForTest() {
        sessions.clear()
        synchronized(boundaryLock) { pending = null }
        synchronized(rateLock) { rateWindowStart = Long.MIN_VALUE; rateCount = 0; suppressed = 0 }
        current.remove()
    }

    internal fun trackedCount(): Int = sessions.size

    fun kindOf(dataType: Int): String = when (dataType) {
        C.DATA_TYPE_MANIFEST -> "pl"
        C.DATA_TYPE_MEDIA -> "seg"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
        C.DATA_TYPE_DRM -> "key"
        C.DATA_TYPE_STEERING_MANIFEST -> "steer"
        else -> "t$dataType"
    }

    /** A playlist identity: the salted hash of the requested path (Media3's logical URL), no query. */
    fun playlistId(spec: DataSpec): String = hash(spec.uri.path ?: "")
}

/** A failure waiting for the next session's first frame (the boundary summary). */
internal class PendingBoundary(
    val fromTune: Long,
    val chan: String,
    val failAt: Long,
    val firstReqFailAt: Long?,
    val signal: String,
    val oldEdge: String?,
    val oldPl: HlsPlaylistScanner.Summary?,
    val oldPlAt: Long?,
) {
    var fails = 1
    var abandoned = false
}

/**
 * One request's observations: written by the OkHttp listener (dispatcher threads) and by [HlsEdgeDataSource] (loader
 * thread), read once when the line is built. All access under `this`. Observe-only: every callback just records.
 */
internal class ReqTrace(val seq: Int, val kind: String, val startAt: Long, private val clock: () -> Long) : EventListener() {
    class Hop {
        var fresh = false
        var ip: String = "-"
        var ageMs: Long = -1
        var uses = 0
        var connection: Connection? = null
    }

    private var callStartAt = -1L
    @Volatile var requestHost: String? = null; private set
    @Volatile var finalHost: String? = null; private set
    @Volatile var status = 0; private set
    @Volatile var redirects = 0; private set
    val hops = ArrayList<Hop>(2)
    private var connectingFresh = false
    @Volatile var dnsMs = -1L; private set
    @Volatile var connectMs = -1L; private set
    @Volatile var tlsMs = -1L; private set
    @Volatile var ttfbMs = -1L; private set
    private var dnsAt = -1L
    private var connectAt = -1L
    private var tlsAt = -1L
    /** Where the call is: dns, connect, tls, request, headers, body. */
    @Volatile var stage = "request"; private set
    @Volatile var listenerErr: String? = null; private set

    @Synchronized override fun callStart(call: Call) {
        callStartAt = clock()
        requestHost = HlsEdgeProbe.hostLabel(call.request().url)
    }

    @Synchronized override fun dnsStart(call: Call, domainName: String) { dnsAt = clock(); stage = "dns" }

    @Synchronized override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (dnsAt >= 0) dnsMs = maxOf(dnsMs, 0) + (clock() - dnsAt)
    }

    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAt = clock()
        connectingFresh = true
        stage = "connect"
    }

    @Synchronized override fun secureConnectStart(call: Call) { tlsAt = clock(); stage = "tls" }

    @Synchronized override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        if (tlsAt >= 0) tlsMs = maxOf(tlsMs, 0) + (clock() - tlsAt)
    }

    @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        if (connectAt >= 0) connectMs = maxOf(connectMs, 0) + (clock() - connectAt)
    }

    @Synchronized override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        if (connectAt >= 0) connectMs = maxOf(connectMs, 0) + (clock() - connectAt)
        listenerErr = HlsEdgeProbe.errName(ioe)
        // A failed route's IP still identifies the edge being dialled.
        if (hops.isEmpty() || hops.last().connection != null) {
            hops += Hop().apply { fresh = true; ip = HlsEdgeProbe.ipHash(inetSocketAddress.address) }
        }
    }

    @Synchronized override fun connectionAcquired(call: Call, connection: Connection) {
        val now = clock()
        val info = HlsEdgeProbe.connectionUse(connection, now)
        val last = hops.lastOrNull()
        val hop = if (last != null && last.connection == null && last.fresh) last else Hop().also { hops += it }
        hop.fresh = connectingFresh
        hop.ip = HlsEdgeProbe.ipHash(connection.route().socketAddress.address)
        hop.ageMs = now - info.firstAt
        hop.uses = info.uses
        hop.connection = connection
        connectingFresh = false
        stage = "request"
    }

    @Synchronized override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { stage = "headers" }

    @Synchronized override fun responseHeadersEnd(call: Call, response: Response) {
        status = response.code
        finalHost = HlsEdgeProbe.hostLabel(response.request.url)
        if (response.isRedirect) {
            redirects++
        } else if (callStartAt >= 0) {
            ttfbMs = clock() - callStartAt
        }
        stage = "body"
    }

    @Synchronized override fun callFailed(call: Call, ioe: IOException) { if (listenerErr == null) listenerErr = HlsEdgeProbe.errName(ioe) }

    @Synchronized override fun canceled(call: Call) { if (listenerErr == null) listenerErr = "canceled" }

    /** The final hop's connection age now (U2: how old the connection was when it failed). */
    @Synchronized fun finalAgeNow(now: Long): Long {
        val c = hops.lastOrNull()?.connection ?: return hops.lastOrNull()?.ageMs ?: -1
        val info = HlsEdgeProbe.connectionPeek(c) ?: return -1
        return now - info.firstAt
    }

    @Synchronized fun finalIp(): String = hops.lastOrNull()?.ip ?: "-"

    @Synchronized fun connLabel(): String = if (hops.isEmpty()) "-" else hops.joinToString(">") { if (it.fresh) "N" else "R" }

    @Synchronized fun ipLabel(): String = if (hops.isEmpty()) "-" else hops.joinToString(">") { it.ip }

    @Synchronized fun usesLabel(): String = hops.lastOrNull()?.uses?.toString() ?: "-"
}
