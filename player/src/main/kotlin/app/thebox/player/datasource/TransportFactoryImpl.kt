package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP-B's [TransportFactory] (§2.5, bound by `PlaybackModule`, §2.8).
 *
 * One [TransportSession] per `tuneId` holds the session's [SessionTelemetry], the live sources currently open (so
 * [endSession] can cancel them) and, for HLS/archive, the one shared lease (§3.4). Every factory built for the same
 * tune shares that session until [endSession]; a later call with the same `tuneId` starts a fresh one.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class TransportFactoryImpl @Inject constructor(
    private val clients: PlaybackHttpClients,
    private val watchdog: ByteWatchdog,
    private val clock: MonoClock,
) : TransportFactory {
    private val sessions = ConcurrentHashMap<Long, TransportSession>()

    override fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> {
        val session = session(ctx)
        val factory = LiveTsDataSource.Factory(
            ctx = ctx,
            callFactory = clients.live(ctx.resolved.ignoreTlsErrors),
            telemetry = session.telemetry,
            watchdog = watchdog,
            clock = clock,
            scope = session,
        )
        return factory to session.telemetry
    }

    override fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> {
        val session = session(ctx)
        val factory = StreamHttpDataSourceFactory(
            ctx = ctx,
            callFactory = clients.segments(ctx.resolved.ignoreTlsErrors),
            telemetry = session.telemetry,
            scope = session,
        )
        return factory to session.telemetry
    }

    override fun endSession(tuneId: Long) {
        sessions.remove(tuneId)?.end()
    }

    internal fun openSessions(): Int = sessions.size

    private fun session(ctx: StreamSessionContext): TransportSession =
        sessions.computeIfAbsent(ctx.tuneId) { TransportSession(ctx, SessionTelemetry(clock)) }
}

/** Something [TransportSession.end] must stop: an open live source, or the HTTP session lease. */
internal fun interface Terminable { fun terminate() }

/** What a data source needs from its tune's transport session. */
internal interface SessionScope {
    val isEnded: Boolean
    fun add(member: Terminable)
    fun remove(member: Terminable)

    /** The session's single HLS/archive lease (§3.4): acquired at the first open, kept until [TransportSession.end]. */
    fun httpCalls(ctx: StreamSessionContext, client: Call.Factory, telemetry: SessionTelemetry): BrokeredCallFactory
}

/** A scope with no session bookkeeping (tests, or a factory used without [TransportFactoryImpl]). */
internal open class StandaloneScope : SessionScope {
    @Volatile override var isEnded: Boolean = false
    private val members = CopyOnWriteArraySet<Terminable>()
    private val acquireLock = Any()
    @Volatile private var http: BrokeredCallFactory? = null

    override fun add(member: Terminable) { members += member }
    override fun remove(member: Terminable) { members -= member }

    /**
     * Acquires the session lease once; every later open (manifest, segment, key) attaches its call to it. A pre-empted
     * lease is never replaced: its factory refuses new calls and the data source reports PREEMPTED. Throws the
     * broker's `ConnectionLimitException`, or [IllegalStateException] once the session ended (callers map both).
     */
    override fun httpCalls(ctx: StreamSessionContext, client: Call.Factory, telemetry: SessionTelemetry): BrokeredCallFactory {
        http?.let { return it }
        synchronized(acquireLock) {
            http?.let { return it }
            check(!isEnded) { "session ended" }
            telemetry.waitingForLease = true
            val lease: CoreLease = try {
                ctx.broker.acquire(ctx.leaseKind, ctx.tuneId, PREEMPT_RELEASE_WAIT_MS)
            } finally {
                telemetry.waitingForLease = false
            }
            ctx.onEvent(PlaybackEventType.LEASE, "acquired", "http ${lease.kind}")
            val calls = BrokeredCallFactory(client, lease, telemetry)
            http = calls
            // A tune that ended while we waited for the lease must not keep it (pairs with end(): both fields volatile).
            if (isEnded) {
                calls.forceRelease()
                throw IllegalStateException("session ended")
            }
            return calls
        }
    }

    /** Ends the session: cancels every call, releases every lease. Idempotent, main-safe (never blocks). */
    fun end() {
        isEnded = true
        for (m in members) {
            try { m.terminate() } catch (t: Throwable) { BoxLog.w(TAG, "terminate failed", t) }
        }
        members.clear()
        http?.let {
            it.cancelAll()
            it.forceRelease()
        }
    }

    companion object {
        private const val TAG = "Transport"

        /** How long an acquire waits for CLOSING leases (§3.5.2; WP-A's value, repeated here for the call site). */
        const val PREEMPT_RELEASE_WAIT_MS = 300L
    }
}

/** The session object [TransportFactoryImpl] keeps per tune. */
internal class TransportSession(val ctx: StreamSessionContext, val telemetry: SessionTelemetry) : StandaloneScope()

/**
 * [StreamTelemetry] of one session (§2.5): written by data sources and OkHttp event listeners (any thread), read by
 * stats/watchdog (main). Counters are atomics or volatiles; nothing here allocates per read.
 */
internal class SessionTelemetry(private val clock: MonoClock) : StreamTelemetry {
    private val bytes = AtomicLong()
    private val redirectCount = AtomicInteger()
    private val reconnectCount = AtomicInteger()
    private val windowBytes = AtomicLong()
    @Volatile private var windowStartMs = 0L

    override val bytesTotal: Long get() = bytes.get()
    @Volatile override var lastByteAtMs: Long = 0L
    @Volatile override var firstByteAtMs: Long = 0L
    @Volatile override var bitrateBps: Long = 0L
    @Volatile override var httpStatus: Int = 0
    @Volatile override var finalHost: String? = null
    override val redirects: Int get() = redirectCount.get()
    @Volatile override var inReconnect: Boolean = false
    override val reconnects: Int get() = reconnectCount.get()
    @Volatile override var sniffed: StreamFormat = StreamFormat.UNKNOWN

    // Not in the StreamTelemetry contract (see the WP-B report): readable by casting in :player.
    /** True while an open is blocked in `broker.acquire` (the controller's WAITING_FOR_SLOT). */
    @Volatile var waitingForLease: Boolean = false
    /** Times Media3 asked a live source to open at a non-zero position (§3.3.3; must stay 0). */
    val positionIgnoredCount = AtomicInteger()
    /** Byte-watchdog cancels in this session. */
    val noBytesCount = AtomicInteger()

    fun onBytes(n: Int, nowMs: Long) {
        if (n <= 0) return
        if (bytes.getAndAdd(n.toLong()) == 0L) firstByteAtMs = nowMs
        lastByteAtMs = nowMs
        val start = windowStartMs
        if (start == 0L) { windowStartMs = nowMs; windowBytes.set(n.toLong()); return }
        val inWindow = windowBytes.addAndGet(n.toLong())
        val elapsed = nowMs - start
        if (elapsed >= BITRATE_WINDOW_MS) {
            bitrateBps = inWindow * 8_000 / elapsed
            windowStartMs = nowMs
            windowBytes.set(0)
        }
    }

    fun onResponse(status: Int, host: String, redirect: Boolean) {
        httpStatus = status
        finalHost = host
        if (redirect) redirectCount.incrementAndGet()
    }

    fun onReconnected() { reconnectCount.incrementAndGet() }

    fun now(): Long = clock.nowMs()

    companion object {
        /** Bitrate measured from bytes over this window (Appendix B, §3.13). */
        const val BITRATE_WINDOW_MS = 2_000L
    }
}
