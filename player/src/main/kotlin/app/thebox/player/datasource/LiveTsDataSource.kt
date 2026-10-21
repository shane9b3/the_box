package app.thebox.player.datasource

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.SniffResult
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.ts.PcrTrackerImpl
import app.thebox.core.playback.ts.StreamSnifferImpl
import app.thebox.core.playback.timeshift.ReconnectCause
import app.thebox.core.playback.ts.TsPacketAlignerImpl
import app.thebox.player.internal.StreamSessionContext
import okhttp3.Call
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The reconnecting live TS source (§3.3). Ignores `DataSpec.position`/`length`, never sends `Range`, always returns
 * `C.LENGTH_UNSET`, and releases only whole 188-byte packets. Built as `TeeDataSource(this, sink)` by the factory.
 *
 * - **Open** (§3.3.3): lease from the session broker, connect to `ctx.resolved.url` (the pre-redirect URL, so every
 *   reconnect follows the redirect again and gets a fresh token), sniff the first bytes; anything but TS is a typed
 *   [LiveStreamException]. The URL and response headers never leave this class: [getUri] is the session placeholder
 *   `thebox-session://<tuneId>` and [getResponseHeaders] is empty (reviewed S9).
 * - **Read** (§3.3.4): bytes go through [TsPacketAlignerImpl]; end of input or a read error reconnects inside the
 *   source (§3.3.6), splicing on a packet boundary with a forced CC gap per PID (B1) and holding post-splice packets
 *   until the PCR verdict (S1). A live stream never returns `RESULT_END_OF_INPUT`.
 * - **Close** (§3.3.7): `Call.cancel()` first, then the body, then the lease (released by the call's
 *   `EventListener` once the socket is closed, S2).
 *
 * Threads: `open`/`read`/`close` on one Media3 loader thread; the byte watchdog and [terminate] (session end, any
 * thread) only cancel calls and set flags.
 */
@OptIn(UnstableApi::class)
internal class LiveTsDataSource(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
    private val telemetry: SessionTelemetry = SessionTelemetry(DEFAULT_CLOCK),
    private val watchdog: ByteWatchdog? = null,
    private val clock: MonoClock = DEFAULT_CLOCK,
    private val timing: Timing = Timing(),
    private val scope: SessionScope = StandaloneScope(),
    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §4.2, A-N1) Writer-only: with the hook set, a post-splice PCR jump
     * **releases** the held packets and reports the jump here instead of throwing `PCR_DISCONTINUITY` (the ring's indexer
     * places the segment boundary). Called on the reading thread. null (every other caller) = M2's behaviour.
     */
    private val onPcrJump: ((deltaMs: Long) -> Unit)? = null,
    /**
     * (M6 time-shift, §4.2, A-C4; fix pass B1) Writer-only: asked before every in-source reconnect attempt with the
     * [ReconnectCause] of the failure being recovered and whether this attempt opens the loss (the first of a burst).
     * false ends the read with `LiveStreamException(RECONNECT_EXHAUSTED)` at once (the recorder's reconnect cap while
     * paused or behind live). Called on the reading thread; must not block. null = M2's behaviour.
     */
    private val reconnectAllowed: ((cause: ReconnectCause, firstOfLoss: Boolean) -> Boolean)? = null,
) : BaseDataSource(/* isNetwork = */ true), Terminable {

    /** The §3.3.4/§3.3.6 numbers (Appendix B defaults) and the sleep used between reconnects; tests shrink them. */
    class Timing(
        val firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS,
        val reconnectFirstByteTimeoutMs: Long = RECONNECT_FIRST_BYTE_TIMEOUT_MS,
        val noBytesTimeoutMs: Long = NO_BYTES_TIMEOUT_MS,
        val reconnectMaxAttempts: Int = RECONNECT_MAX_ATTEMPTS,
        val reconnectWindowMs: Long = RECONNECT_WINDOW_MS,
        val reconnectDelaysMs: LongArray = RECONNECT_DELAYS_MS,
        val limitStatusDelayMs: Long = RECONNECT_LIMIT_STATUS_DELAY_MS,
        val pcrHoldMaxBytes: Int = PCR_HOLD_MAX_BYTES,
        val pcrHoldMaxMs: Long = PCR_HOLD_MAX_MS,
        val sleeper: Sleeper = DEFAULT_SLEEPER,
    )

    fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleep(ms: Long)
    }

    /** BAD_REQUEST: OkHttp refused to build the request (an invalid header name or value, code review N-S1). */
    private enum class Fail { STATUS, NETWORK, NO_BYTES, READ_ERROR, EOF, HLS, HTML, TINY, UNRECOGNIZED, PREEMPTED, BAD_REQUEST }

    /** Internal outcome of one connection attempt (allocated on failures only). */
    private class Failure(
        val kind: Fail,
        val status: Int = 0,
        val net: NetworkFailureKind? = null,
        val bytes: Int = 0,
        val cause: IOException? = null,
    )

    private val sessionUri: Uri = "$SESSION_SCHEME://${ctx.tuneId}".toUri()
    private val sessionSpec: DataSpec = DataSpec(sessionUri)
    private val sniffer = StreamSnifferImpl()
    private val chunk = ByteArray(READ_CHUNK_BYTES)
    private val sniffBuf = ByteArray(StreamSnifferImpl.SNIFF_MAX_BYTES)
    private var pcr = PcrTrackerImpl()
    /** Clock value handed to the PCR tracker for the packets of the current write (one clock read per chunk). */
    private var pcrNow = 0L
    private val packetListener = TsPacketAlignerImpl.PacketListener { b, o -> pcr.onPacket(b, o, pcrNow) }
    private var aligner = TsPacketAlignerImpl(onPacket = packetListener)

    private var opened = false
    private var innerSpec: DataSpec? = null
    private var inner: OkHttpDataSource? = null
    @Volatile private var lease: CoreLease? = null
    @Volatile private var calls: BrokeredCallFactory? = null
    @Volatile private var watchdogRegistration: AutoCloseable? = null
    private var fatal: IOException? = null
    private var openStartedAtMs = 0L

    // Watchdog-visible connection state (§3.3.6).
    @Volatile private var terminated = false
    @Volatile private var cancelledByWatchdog = false
    @Volatile private var connActive = false
    @Volatile private var connHasBytes = false
    @Volatile private var connIsReconnect = false
    @Volatile private var connStartedAtMs = 0L
    @Volatile private var lastByteAtMs = 0L
    /** Non-zero while a Media3 call (open/read) is blocked in this source. */
    @Volatile private var blockedSinceMs = 0L
    /** (S9) The silence that made the watchdog cancel the current connection, and the connection's age then (ms). */
    @Volatile private var watchdogGapMs = 0L
    @Volatile private var watchdogConnAgeMs = 0L
    /** (S8) The reconnect sleep waits on this, so [terminate] ends it at once instead of after up to a second. */
    private val sleepLock = Object()

    // Reconnect budget and post-splice hold (loader thread only).
    private var attempt = 0
    private var windowStartMs = 0L
    private var burstLastStatus: Int? = null
    private var holdActive = false
    private var holdStartMs = 0L

    private val watchdogCheck: () -> Unit = { checkWatchdog() }

    // ------------------------------------------------------------------ DataSource

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L && telemetry.positionIgnoredCount.getAndIncrement() == 0) {
            BoxLog.w(TAG, "tune ${ctx.tuneId}: open at position ${dataSpec.position} ignored (live TS never sends Range)")
        }
        if (terminated || scope.isEnded) throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.OPEN)
        transferInitializing(dataSpec)
        resetForOpen()
        scope.add(this)
        try {
            acquireLease()
            watchdogRegistration = watchdog?.register(ctx.tuneId, watchdogCheck)
            blockedSinceMs = clock.nowMs()
            val f = connect(reconnect = false)
            if (f != null) onFirstConnectFailed(f)
        } catch (e: Throwable) {
            // (code review 2026-10-14, N-S1) Any failure, not only IOException: a lease must never outlive a failed open.
            shutdown()
            throw e
        } finally {
            blockedSinceMs = 0
        }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fatal?.let { throw it }
        blockedSinceMs = clock.nowMs()
        try {
            while (true) {
                val n = aligner.read(buffer, offset, length)
                if (n > 0) {
                    bytesTransferred(n)
                    return n
                }
                // (code review 2026-10-14, E-B1) Waiting on the socket: the watchdog's no-bytes age runs from here.
                if (telemetry.readPendingSinceMs == 0L) telemetry.readPendingSinceMs = blockedSinceMs
                pump()
            }
        } catch (e: LiveStreamException) {
            fatal = e
            throw e
        } finally {
            blockedSinceMs = 0
            telemetry.readPendingSinceMs = 0
        }
    }

    /** Always the session placeholder: the resolved (credential-bearing) URL never reaches Media3 (S9). */
    override fun getUri(): Uri = sessionUri

    /** Empty: response headers may carry tokens and must not reach `LoadEventInfo` or `IcyHeaders` (S9). */
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        val wasOpen = opened
        opened = false
        shutdown()
        if (wasOpen) transferEnded()
    }

    /** Session end (`TransportFactory.endSession`, any thread): cancel the call and release the lease now. */
    override fun terminate() {
        terminated = true
        connActive = false
        synchronized(sleepLock) { sleepLock.notifyAll() }
        watchdogRegistration?.close()
        calls?.let {
            it.cancelAll()
            it.forceRelease()
        }
    }

    // ------------------------------------------------------------------ open helpers

    private fun resetForOpen() {
        fatal = null
        openStartedAtMs = clock.nowMs()
        pcr = PcrTrackerImpl()
        aligner = TsPacketAlignerImpl(onPacket = packetListener)
        attempt = 0
        windowStartMs = 0L
        burstLastStatus = null
        holdActive = false
        cancelledByWatchdog = false
        innerSpec = DataSpec.Builder()
            .setUri(ctx.resolved.url.toUri())
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(ctx.resolved.headers.filterKeys { !it.equals(RANGE_HEADER, ignoreCase = true) })
            .setFlags(0)
            .build()
    }

    private fun acquireLease() {
        telemetry.waitingForLease = true
        val acquired = try {
            ctx.broker.acquire(ctx.leaseKind, ctx.tuneId, StandaloneScope.PREEMPT_RELEASE_WAIT_MS)
        } catch (e: ConnectionLimitException) {
            throw failure(LiveFailureKind.LOCAL_LIMIT, FailureInfo.Phase.OPEN).apply { addSuppressed(e) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("lease wait interrupted")
        } catch (e: RuntimeException) {
            // Loader would wrap a runtime exception in UnexpectedLoaderException (§3.3.3 step 3): map it here.
            throw failure(LiveFailureKind.LOCAL_LIMIT, FailureInfo.Phase.OPEN).apply { addSuppressed(e) }
        } finally {
            telemetry.waitingForLease = false
        }
        lease = acquired
        val factory = BrokeredCallFactory(callFactory, acquired, telemetry)
        calls = factory
        ctx.onEvent(PlaybackEventType.LEASE, "acquired", acquired.kind.name)
        if (terminated || scope.isEnded) {
            factory.forceRelease()
            throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.OPEN)
        }
    }

    private fun onFirstConnectFailed(f: Failure) {
        val phase = FailureInfo.Phase.OPEN
        when (f.kind) {
            // The ladder owns first-open statuses and connect errors (§3.3.3 step 3).
            Fail.STATUS -> throw failure(LiveFailureKind.HTTP_STATUS, phase, status = f.status)
            Fail.NETWORK -> throw failure(LiveFailureKind.NETWORK, phase, net = f.net, cause = f.cause)
            Fail.HLS -> throw failure(LiveFailureKind.FORMAT_IS_HLS, FailureInfo.Phase.SNIFF)
            Fail.HTML -> throw failure(LiveFailureKind.HTML_PAGE, FailureInfo.Phase.SNIFF)
            Fail.TINY -> throw failure(LiveFailureKind.TINY_BODY, FailureInfo.Phase.SNIFF)
            Fail.UNRECOGNIZED -> throw failure(LiveFailureKind.FORMAT_UNRECOGNIZED, FailureInfo.Phase.SNIFF)
            Fail.PREEMPTED -> throw failure(LiveFailureKind.PREEMPTED, phase)
            Fail.BAD_REQUEST -> throw failure(LiveFailureKind.NETWORK, phase, net = NetworkFailureKind.OTHER)
            // A silent server (first-byte timeout) or a body cut during the sniff: reconnect (§3.3.6, I-14).
            Fail.NO_BYTES, Fail.READ_ERROR, Fail.EOF -> reconnectLoop(f)
        }
    }

    /** One connection: open the inner source and sniff. Null on TS (sniffed bytes already in the aligner). */
    private fun connect(reconnect: Boolean): Failure? {
        val factory = calls ?: return Failure(Fail.PREEMPTED)
        val ds = OkHttpDataSource.Factory(factory).createDataSource()
        cancelledByWatchdog = false
        connHasBytes = false
        connIsReconnect = reconnect
        connStartedAtMs = clock.nowMs()
        inner = ds
        connActive = true
        try {
            ds.open(innerSpec!!)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            closeInner()
            telemetry.httpStatus = e.responseCode
            return if (isPreempted()) Failure(Fail.PREEMPTED) else Failure(Fail.STATUS, status = e.responseCode)
        } catch (e: IOException) {
            closeInner()
            return ioFailure(e, afterHeaders = false)
        } catch (e: IllegalArgumentException) {
            // (code review 2026-10-14, N-S1) OkHttp's header check; its message carries the header value, so it is
            // neither logged nor kept as a cause. HeaderPolicy.sanitize drops such headers upstream; this is the backstop.
            closeInner()
            BoxLog.w(TAG, "tune ${ctx.tuneId}: request refused by OkHttp (invalid header)")
            return Failure(Fail.BAD_REQUEST)
        }
        return sniff(ds)
    }

    private fun sniff(ds: OkHttpDataSource): Failure? {
        var n = 0
        while (true) {
            val r = try {
                ds.read(sniffBuf, n, sniffBuf.size - n)
            } catch (e: IOException) {
                closeInner()
                return ioFailure(e, afterHeaders = true)
            }
            val eos = r == C.RESULT_END_OF_INPUT
            if (eos && cancelledByWatchdog) {
                // (B1) Our own cancel can end the body with −1 instead of throwing: a silence, not a TINY refusal.
                closeInner()
                sniffBuf.fill(0, 0, n)
                return Failure(Fail.NO_BYTES, net = NetworkFailureKind.TIMEOUT)
            }
            if (!eos) {
                n += r
                onBytes(r, clock.nowMs())
            }
            val verdict = sniffer.sniff(sniffBuf, n, eos)
            val failure = when (verdict.result) {
                SniffResult.NEED_MORE -> continue
                SniffResult.TS -> {
                    telemetry.sniffed = StreamFormat.TS
                    ctx.onEvent(PlaybackEventType.SNIFF, "ts", contentType(ds))
                    pcrNow = clock.nowMs()
                    aligner.write(sniffBuf, verdict.tsOffset, n - verdict.tsOffset)
                    return null
                }
                SniffResult.HLS_PLAYLIST -> Failure(Fail.HLS, bytes = n).also { telemetry.sniffed = StreamFormat.HLS }
                SniffResult.HTML -> Failure(Fail.HTML, bytes = n)
                SniffResult.TINY -> Failure(Fail.TINY, bytes = n)
                SniffResult.UNRECOGNIZED -> Failure(Fail.UNRECOGNIZED, bytes = n).also { telemetry.sniffed = StreamFormat.PROGRESSIVE_OTHER }
            }
            ctx.onEvent(PlaybackEventType.SNIFF, failure.kind.name.lowercase(), contentType(ds))
            closeInner()
            sniffBuf.fill(0, 0, n) // the body is not kept: a splash page may echo credentials
            return failure
        }
    }

    // ------------------------------------------------------------------ read helpers

    private fun pump() {
        val ds = inner
        if (ds == null) {
            reconnectLoop(if (cancelledByWatchdog) Failure(Fail.NO_BYTES, net = NetworkFailureKind.TIMEOUT) else Failure(Fail.EOF))
            return
        }
        val r = try {
            ds.read(chunk, 0, chunk.size)
        } catch (e: IOException) {
            closeInner()
            val f = ioFailure(e, afterHeaders = true)
            if (f.kind == Fail.PREEMPTED) throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.READ)
            reconnectLoop(f)
            return
        }
        if (r == C.RESULT_END_OF_INPUT) {
            closeInner()
            if (isPreempted()) throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.READ)
            // (B1) A body our watchdog cancelled may end with −1 instead of throwing: that is our silence, not a close.
            reconnectLoop(if (cancelledByWatchdog) Failure(Fail.NO_BYTES, net = NetworkFailureKind.TIMEOUT) else Failure(Fail.EOF))
            return
        }
        val now = clock.nowMs()
        onBytes(r, now)
        pcrNow = now
        aligner.write(chunk, 0, r)
        if (holdActive) checkHold(now)
    }

    private fun onBytes(n: Int, nowMs: Long) {
        lastByteAtMs = nowMs
        connHasBytes = true
        telemetry.onBytes(n, nowMs)
    }

    /** (reviewed S1) Post-splice hold: release on a clean PCR, drop and fail on a jump, release when bounded out. */
    private fun checkHold(nowMs: Long) {
        val jump = pcr.pendingJumpMs()
        if (jump != null && onPcrJump != null) {
            // (M6 time-shift, A-N1) The recorder keeps every byte: the indexer turns the jump into a segment boundary.
            aligner.releaseHeld()
            holdActive = false
            ctx.onEvent(PlaybackEventType.PCR_JUMP, "jump_kept", "$jump ms")
            BoxLog.i(TAG) { "tune ${ctx.tuneId}: PCR jump of $jump ms after a reconnect (kept for the ring)" }
            onPcrJump.invoke(jump)
            return
        }
        if (jump != null) {
            aligner.dropHeld()
            holdActive = false
            ctx.onEvent(PlaybackEventType.PCR_JUMP, "jump", "$jump ms")
            BoxLog.i(TAG) { "tune ${ctx.tuneId}: PCR jump of $jump ms after a reconnect" }
            throw failure(LiveFailureKind.PCR_DISCONTINUITY, FailureInfo.Phase.READ, delta = jump)
        }
        if (!pcr.awaitingPcr()) {
            aligner.releaseHeld()
            holdActive = false
        } else if (aligner.heldBytes() > timing.pcrHoldMaxBytes || nowMs - holdStartMs > timing.pcrHoldMaxMs) {
            aligner.releaseHeld()
            holdActive = false
            BoxLog.i(TAG) { "tune ${ctx.tuneId}: no PCR within the post-splice hold; check skipped for this splice" }
        }
    }

    /**
     * The in-source reconnect loop (§3.3.6). Budget: [Timing.reconnectMaxAttempts] attempts inside a burst of
     * [Timing.reconnectWindowMs]; a loss that happens after the previous burst's window has passed (so after a
     * successful reconnect and flowing bytes) starts a new burst. See the WP-B report for why this reads the design's
     * pseudo-code as a burst window.
     */
    private fun reconnectLoop(first: Failure) {
        var f = first
        var firstOfLoss = true
        val start = clock.nowMs()
        if (attempt == 0 || start - windowStartMs > timing.reconnectWindowMs) {
            attempt = 0
            windowStartMs = start
            burstLastStatus = null
        }
        while (true) {
            if (isPreempted()) {
                telemetry.inReconnect = false
                throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.RECONNECT)
            }
            if (f.kind == Fail.NO_BYTES) {
                telemetry.noBytesCount.incrementAndGet()
                // (S9) The silence and the connection's age, so a provider's periodic gaps can be read from the log.
                ctx.onEvent(PlaybackEventType.NO_BYTES, "no_bytes", "attempt $attempt gap=${watchdogGapMs} ms age=${watchdogConnAgeMs} ms")
            }
            if (f.kind == Fail.STATUS) burstLastStatus = f.status
            val now = clock.nowMs()
            if (attempt >= timing.reconnectMaxAttempts || (attempt > 0 && now - windowStartMs > timing.reconnectWindowMs)) {
                telemetry.inReconnect = false
                throw failure(LiveFailureKind.RECONNECT_EXHAUSTED, FailureInfo.Phase.RECONNECT, status = burstLastStatus, net = exhaustedKind(f))
            }
            val loss = firstOfLoss
            firstOfLoss = false
            if (reconnectAllowed != null && !reconnectAllowed.invoke(reconnectCause(f), loss)) {
                // (M6 time-shift, A-C4) The recorder's reconnect cap while paused or behind live: no further attempt.
                telemetry.inReconnect = false
                BoxLog.i(TAG) { "tune ${ctx.tuneId}: reconnect refused by the recorder's cap" }
                throw failure(LiveFailureKind.RECONNECT_EXHAUSTED, FailureInfo.Phase.RECONNECT, status = burstLastStatus, net = exhaustedKind(f))
            }
            val delays = timing.reconnectDelaysMs
            val delay = if (isLimitLike(f)) timing.limitStatusDelayMs else delays[minOf(attempt, delays.size - 1)]
            telemetry.inReconnect = true
            if (delay > 0) sleep(delay)
            if (isPreempted()) {
                telemetry.inReconnect = false
                throw failure(LiveFailureKind.PREEMPTED, FailureInfo.Phase.RECONNECT)
            }
            attempt++
            ctx.onEvent(PlaybackEventType.RECONNECT, "attempt", "$attempt/${timing.reconnectMaxAttempts} ${f.kind.name.lowercase()}")
            BoxLog.i(TAG) { "tune ${ctx.tuneId}: reconnect $attempt after ${f.kind.name.lowercase()} ${if (f.status != 0) f.status else ""}" }
            closeInner()
            aligner.resync()
            pcr.markReconnect(clock.nowMs())
            val t0 = clock.nowMs()
            val next = connect(reconnect = true)
            if (next == null) {
                telemetry.inReconnect = false
                telemetry.onReconnected()
                ctx.onEvent(PlaybackEventType.RECONNECT_OK, "ok", "${clock.nowMs() - t0} ms")
                // The sniffed bytes may already hold the first PCR: the verdict is checked before anything is released.
                holdStartMs = clock.nowMs()
                holdActive = true
                checkHold(holdStartMs)
                return
            }
            f = next
            val phase = FailureInfo.Phase.RECONNECT
            when (next.kind) {
                Fail.STATUS -> if (!isReconnectableStatus(next.status)) {
                    telemetry.inReconnect = false
                    throw failure(LiveFailureKind.HTTP_STATUS, phase, status = next.status)
                }
                Fail.NETWORK, Fail.NO_BYTES, Fail.READ_ERROR, Fail.EOF -> Unit
                // (S7) A 200 that closes at once on a reconnect is the panel still counting our previous socket.
                Fail.TINY -> if (next.bytes > 0) throwSurfaced(LiveFailureKind.TINY_BODY, phase)
                Fail.HTML -> throwSurfaced(LiveFailureKind.HTML_PAGE, phase)
                Fail.HLS -> throwSurfaced(LiveFailureKind.FORMAT_IS_HLS, phase)
                Fail.UNRECOGNIZED -> throwSurfaced(LiveFailureKind.FORMAT_UNRECOGNIZED, phase)
                Fail.PREEMPTED -> throwSurfaced(LiveFailureKind.PREEMPTED, phase)
                Fail.BAD_REQUEST -> {
                    telemetry.inReconnect = false
                    throw failure(LiveFailureKind.NETWORK, phase, net = NetworkFailureKind.OTHER)
                }
            }
        }
    }

    private fun throwSurfaced(kind: LiveFailureKind, phase: FailureInfo.Phase): Nothing {
        telemetry.inReconnect = false
        throw failure(kind, phase)
    }

    private fun sleep(ms: Long) {
        try {
            if (timing.sleeper === DEFAULT_SLEEPER) {
                // (S8) Terminable: a zap ends the old recorder's reconnect wait at once (terminate() notifies).
                synchronized(sleepLock) {
                    val end = System.nanoTime() + ms * 1_000_000L
                    while (!terminated) {
                        val left = (end - System.nanoTime()) / 1_000_000L
                        if (left <= 0) break
                        sleepLock.wait(left)
                    }
                }
            } else {
                timing.sleeper.sleep(ms)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            telemetry.inReconnect = false
            throw InterruptedIOException("reconnect wait interrupted")
        }
    }

    // ------------------------------------------------------------------ failures and shutdown

    /** (reviewed S3) Pre-emption and the watchdog are checked before anything else: a cancel looks like a reset. */
    private fun ioFailure(e: IOException, afterHeaders: Boolean): Failure {
        if (isPreempted()) return Failure(Fail.PREEMPTED)
        if (cancelledByWatchdog) return Failure(Fail.NO_BYTES, net = NetworkFailureKind.TIMEOUT)
        if (isInterruption(e)) {
            telemetry.inReconnect = false
            throw InterruptedIOException("interrupted")
        }
        val kind = networkKindOf(e)
        return Failure(if (afterHeaders) Fail.READ_ERROR else Fail.NETWORK, net = kind, cause = safeCause(e))
    }

    /**
     * (B1) The recorder's reconnect cause: only a server close, a reset and a refusal can be a slot fight; our own
     * watchdog cancel and network trouble (timeouts, 5xx/408/429, and 503, more often overload than a slot) never are.
     */
    private fun reconnectCause(f: Failure): ReconnectCause = when (f.kind) {
        Fail.NO_BYTES -> ReconnectCause.WATCHDOG
        Fail.EOF -> ReconnectCause.SERVER_CLOSED
        Fail.READ_ERROR -> if (f.net == NetworkFailureKind.RESET) ReconnectCause.RESET else ReconnectCause.NETWORK
        Fail.STATUS -> if (f.status in CAP_REFUSAL_STATUSES) ReconnectCause.REFUSED else ReconnectCause.NETWORK
        Fail.TINY -> if (f.bytes == 0) ReconnectCause.REFUSED else ReconnectCause.NETWORK
        else -> ReconnectCause.NETWORK
    }

    private fun isPreempted(): Boolean = terminated || lease?.isPreempted == true

    private fun isLimitLike(f: Failure): Boolean =
        (f.kind == Fail.STATUS && f.status in LIMIT_STATUSES) || (f.kind == Fail.TINY && f.bytes == 0)

    private fun isReconnectableStatus(status: Int): Boolean =
        status in LIMIT_STATUSES || status == 408 || status == 429 || status in 500..599

    private fun exhaustedKind(f: Failure): NetworkFailureKind? = when (f.kind) {
        Fail.NO_BYTES -> NetworkFailureKind.TIMEOUT
        Fail.NETWORK, Fail.READ_ERROR -> f.net
        Fail.EOF -> NetworkFailureKind.RESET
        else -> null
    }

    private fun failure(
        kind: LiveFailureKind,
        phase: FailureInfo.Phase,
        status: Int? = telemetry.httpStatus.takeIf { it != 0 },
        net: NetworkFailureKind? = null,
        delta: Long? = null,
        cause: IOException? = null,
    ): LiveStreamException {
        val info = FailureInfo(
            status = status,
            host = telemetry.finalHost ?: ctx.resolved.host,
            bytesRead = telemetry.bytesTotal,
            attempt = attempt,
            phase = phase,
            networkKind = net,
            deltaMs = delta,
            elapsedMs = clock.nowMs() - openStartedAtMs,
        )
        if (kind != LiveFailureKind.PREEMPTED) ctx.onEvent(PlaybackEventType.ERROR, "live_${kind.name.lowercase()}", "${status ?: ""} ${phase.name.lowercase()}".trim())
        return LiveStreamException(kind, info, sessionSpec, cause)
    }

    /** Cancel FIRST (no 100 ms drain of a flowing body), then close the body (§3.3.7, S2). */
    private fun closeInner() {
        connActive = false
        val ds = inner ?: return
        inner = null
        calls?.cancelAll()
        try {
            ds.close()
        } catch (t: Throwable) {
            BoxLog.w(TAG, "inner close failed", t)
        }
    }

    private fun shutdown() {
        closeInner()
        watchdogRegistration?.close()
        watchdogRegistration = null
        calls?.finish()
        scope.remove(this)
        holdActive = false
        telemetry.inReconnect = false
    }

    private fun checkWatchdog() {
        val since = blockedSinceMs
        if (since == 0L || !connActive || cancelledByWatchdog) return
        val now = clock.nowMs()
        val expired = if (!connHasBytes) {
            now - connStartedAtMs > (if (connIsReconnect) timing.reconnectFirstByteTimeoutMs else timing.firstByteTimeoutMs)
        } else {
            now - maxOf(lastByteAtMs, since) > timing.noBytesTimeoutMs
        }
        if (!expired) return
        cancelledByWatchdog = true
        // (S9) Redacted diagnostics: tune id and numbers only (no URL, host or header).
        val gap = now - (if (connHasBytes) maxOf(lastByteAtMs, since) else connStartedAtMs)
        val age = now - connStartedAtMs
        watchdogGapMs = gap
        watchdogConnAgeMs = age
        BoxLog.i(TAG) {
            "tune ${ctx.tuneId}: no bytes for $gap ms (connection $age ms old, ${telemetry.bytesTotal} bytes, " +
                "${telemetry.bitrateBps / 1000} kbit/s${if (connIsReconnect) ", a reconnect" else ""}), cancelling the connection"
        }
        calls?.cancelAll()
    }

    private fun contentType(ds: OkHttpDataSource): String {
        for ((k, v) in ds.responseHeaders) if (k != null && k.equals("Content-Type", ignoreCase = true)) return v.firstOrNull().orEmpty()
        return ""
    }

    class Factory internal constructor(
        private val ctx: StreamSessionContext,
        private val callFactory: Call.Factory,
        private val telemetry: SessionTelemetry = SessionTelemetry(DEFAULT_CLOCK),
        private val watchdog: ByteWatchdog? = null,
        private val clock: MonoClock = DEFAULT_CLOCK,
        private val timing: Timing = Timing(),
        private val scope: SessionScope = StandaloneScope(),
    ) : DataSource.Factory {
        /** `TeeDataSource(LiveTsDataSource, sink)`: the v2 recorder seam sits above the reconnect logic (§3.3.2). */
        override fun createDataSource(): DataSource = TeeDataSource(
            LiveTsDataSource(ctx, callFactory, telemetry, watchdog, clock, timing, scope),
            ctx.sinkFactory?.createDataSink() ?: NoOpDataSink(),
        )
    }

    companion object {
        /** The `MediaItem`/`DataSpec` placeholder scheme for live TS (S9): `thebox-session://<tuneId>`. */
        const val SESSION_SCHEME = "thebox-session"

        const val FIRST_BYTE_TIMEOUT_MS = 8_000L
        const val RECONNECT_FIRST_BYTE_TIMEOUT_MS = 4_000L
        const val NO_BYTES_TIMEOUT_MS = 4_000L
        const val RECONNECT_MAX_ATTEMPTS = 3
        const val RECONNECT_WINDOW_MS = 3_000L
        val RECONNECT_DELAYS_MS = longArrayOf(0L, 500L, 1_000L)
        const val RECONNECT_LIMIT_STATUS_DELAY_MS = 1_000L
        const val READ_CHUNK_BYTES = 16_384
        const val PCR_HOLD_MAX_BYTES = 262_144
        const val PCR_HOLD_MAX_MS = 500L

        /** Answers on a reconnect that mean "the panel still counts our previous socket" (§3.3.6). */
        private val LIMIT_STATUSES = intArrayOf(403, 458, 503, 509)
        /** (B1) The statuses the recorder's reconnect cap counts as a refusal: the owner's list, without 503. */
        private val CAP_REFUSAL_STATUSES = intArrayOf(403, 458, 509)
        /** The production sleep: replaced by the terminable wait in [sleep] (S8); tests pass their own [Sleeper]. */
        internal val DEFAULT_SLEEPER = Sleeper { Thread.sleep(it) }
        private const val RANGE_HEADER = "Range"
        private const val TAG = "LiveTs"
        private val DEFAULT_CLOCK = MonoClock { SystemClock.elapsedRealtime() }
    }
}
