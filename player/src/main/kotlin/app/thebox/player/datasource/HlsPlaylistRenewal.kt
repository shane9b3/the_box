package app.thebox.player.datasource

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.internal.StreamSessionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/*
 * HLS playlist-URL renewal (docs/design/m6-hls-edge-handover.md §6). Live HLS only.
 *
 * Measured (§1.2): the provider URL O answers the first request with a 302 to a media playlist on an edge host; Media3
 * pins that post-redirect URL L and reloads it for the life of the source. After 30 minutes the edge answers reloads of
 * L with a 302 to an address that never connects: L's token expired. A fresh request to O returns a new playlist URL
 * on the same edge whose media sequence simply continues. So the data source keeps L as Media3's *logical* name and
 * swaps the URL it actually requests.
 */

/** Whether the device has a network at all (no renewal attempt without one). */
internal fun interface OnlineCheck {
    fun isOnline(): Boolean

    companion object {
        val ALWAYS = OnlineCheck { true }
    }
}

/** [OnlineCheck] from the platform: a default network exists. Never throws; unknown counts as online. */
@Singleton
internal class DeviceNetwork @Inject constructor(@ApplicationContext private val context: Context) : OnlineCheck {
    override fun isOnline(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm == null || cm.activeNetwork != null
    } catch (_: Throwable) {
        true
    }
}

/**
 * The renewal budget of one playlist (`brokerKey`), shared by its sessions (a ladder re-resolve starts a new session,
 * so a per-session budget would reset at every fallback): at most [max] renewal attempts per [windowMs]; the next one
 * disables renewal for [disableMs] (time-boxed, not for the session: design §6.5, review minor 3).
 */
internal class HlsRenewalBudget(
    private val max: Int = HlsPlaylistRenewal.BUDGET_MAX,
    private val windowMs: Long = HlsPlaylistRenewal.BUDGET_WINDOW_MS,
    private val disableMs: Long = HlsPlaylistRenewal.BUDGET_DISABLE_MS,
) {
    private val times = ArrayDeque<Long>()
    private var disabledUntil = Long.MIN_VALUE

    @Synchronized fun disabled(nowMs: Long): Boolean = nowMs < disabledUntil

    /** True (and counted) when one more attempt fits; false, and disabled for [disableMs], when it does not. */
    @Synchronized fun tryTake(nowMs: Long): Boolean {
        if (nowMs < disabledUntil) return false
        drop(nowMs)
        if (times.size >= max) {
            disabledUntil = nowMs + disableMs
            times.clear()
            return false
        }
        times.addLast(nowMs)
        return true
    }

    /**
     * (Code review A1) The same count for an attempt after an I/O failure, which may be a network blip: when it does not
     * fit it is skipped, and renewal is **not** disabled (a blip must not switch off the next real expiry's renewal).
     */
    @Synchronized fun tryTakeSoft(nowMs: Long): Boolean {
        if (nowMs < disabledUntil) return false
        drop(nowMs)
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }

    private fun drop(nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
    }
}

/**
 * One live HLS session's playlist router (design §6.2). Created by `TransportFactoryImpl.liveHls` only; registered as a
 * [Terminable] of the session, so `endSession` releases a thread waiting on a renewal.
 *
 * State: UNKNOWN until the first load of O completes; then OFF (O was not redirected, the body was a multivariant or
 * not a live media playlist: a strict no-op from then on) or ACTIVE with one [Route] per logical URL. Thread-safe: the
 * playlist and segment loaders call in from their own threads; all state is under [lock].
 *
 * (Code review A4) Turning renewal off later in an ACTIVE session ([turnOff]: a redirect loop, an oversized body; or
 * the budget's time-boxed disable) means "never renew again", not "forget the routes": a route that was renewed keeps
 * being requested at its current URL (redirects followed, today's path), never at the expired logical one.
 *
 * Holds O only as a reference to `ctx.resolved.url` (already the HLS `MediaItem` URI). Route URLs live in memory only
 * and die with the session; logs carry hosts and salted hashes only.
 */
@OptIn(UnstableApi::class)
internal class HlsPlaylistRenewal(
    private val ctx: StreamSessionContext,
    private val budget: HlsRenewalBudget,
    private val online: OnlineCheck,
    private val clock: MonoClock,
    private val telemetry: SessionTelemetry,
    /** How long a second loader waits for a running renewal ([WAIT_MS]). Test hook. */
    private val waitMs: Long = WAIT_MS,
    /** (Code review A9) Waits between online re-checks; interruptible. Test hook. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : Terminable {
    enum class State { UNKNOWN, ACTIVE, OFF }

    /** One URL Media3 holds ([logical], the post-redirect URL the tracker pinned) and the URL requested for it. */
    class Route internal constructor(val logical: String, last: HlsPlaylistScanner.Summary, lastAt: Long) {
        @Volatile var current: String = logical
            internal set
        internal var last: HlsPlaylistScanner.Summary = last
        internal var lastAt: Long = lastAt
        /** The first reload after this route's URL was adopted (or created) has not succeeded yet (loop guard). */
        internal var fresh = true
        /** Set once this route fell back: later reloads get the same answer without network or event. */
        internal var fallback: Outcome? = null
        /**
         * (Code review A2) This route's reloads follow redirects (today's path) from now on: a redirect led to a working
         * playlist on another host, which cannot be adopted as the current URL (design §6.12).
         */
        @Volatile internal var follow = false
        /** MonoClock ms of the last renewal attempt started by an I/O failure (code review A1 cool-down). */
        internal var softAt: Long? = null
        internal val id: String = HlsEdgeProbe.hash(logical.toHttpUrlOrNull()?.encodedPath ?: "")
    }

    sealed class Plan {
        data object None : Plan()
        data object Origin : Plan()
        /** A reload of [route]: request [requestUrl]; with [follow], redirects followed and no renewal (today's path). */
        class Pinned(val route: Route, val requestUrl: String, val follow: Boolean = false) : Plan()
    }

    /** What a renewal ended with. [label] is the log/event word: renewed, declined:x, misaligned:x, failed:x. */
    sealed class Outcome(val label: String) {
        class Renewed(val body: ByteArray) : Outcome("renewed")
        /** Another loader's renewal of the same route succeeded while this one waited: re-request the new URL. */
        data object Joined : Outcome("joined")
        /** Zap, stop or interrupt during the renewal: no fallback, no event. */
        data object Cancelled : Outcome("cancelled")
        class Declined(val reason: String) : Outcome("declined:$reason")
        class Misaligned(val rule: String) : Outcome("misaligned:$rule")
        class Failed(val phase: String) : Outcome("failed:$phase")

        /**
         * Outcomes that may end in the typed fast signal to the ladder. Offline, busy (a waiter that timed out, code
         * review A5) and cool-down never do: the reload gets today's error and Media3's own retries.
         */
        val isFallback: Boolean
            get() = this is Misaligned || this is Failed || (this is Declined && reason !in QUIET_DECLINES)
    }

    /** What the data source got from O: the final URL and body, or the failure's phase (never the exception). */
    class Fetched private constructor(val finalUrl: String?, val body: ByteArray?, val phase: String?, val status: Int?) {
        companion object {
            fun ok(finalUrl: String, body: ByteArray) = Fetched(finalUrl, body, null, null)
            fun failed(phase: String, status: Int? = null) = Fetched(null, null, phase, status)
            val CANCELLED = Fetched(null, null, "cancelled", null)
        }
    }

    private class Flight(val route: Route) {
        var outcome: Outcome? = null
    }

    private val lock = ReentrantLock()
    private val flightDone = lock.newCondition()
    private var state = State.UNKNOWN
    /** (Code review A4) Renewal is off for the rest of the session; routes keep their current URL. */
    private var renewOff = false
    private val routes = ArrayList<Route>(2)
    private var inflight: Flight? = null
    @Volatile private var ended = false
    private var segmentSignalled = false

    init {
        created.incrementAndGet()
    }

    val currentState: State get() = lock.withLock { state }

    /** True once [turnOff] ran: no renewal for the rest of the session (the routes still map, code review A4). */
    val renewalsOff: Boolean get() = lock.withLock { renewOff }

    override fun terminate() {
        ended = true
        lock.withLock { flightDone.signalAll() }
    }

    // ---------------------------------------------------------------- planning (loader threads)

    /** How to serve a `MANIFEST` open of [uri]. */
    fun plan(uri: Uri): Plan {
        if (ended) return Plan.None
        val s = uri.toString()
        lock.withLock {
            if (state == State.OFF) return Plan.None
            if (isOrigin(s)) return Plan.Origin
            if (state != State.ACTIVE) return Plan.None
            val follow = renewOff || budget.disabled(clock.nowMs())
            for (r in routes) {
                val req = requestUrl(r, s) ?: continue
                val followed = follow || r.follow
                // Never renewed: exactly today's request (Media3's own URL, redirects followed).
                if (followed && r.current == r.logical) return Plan.None
                return Plan.Pinned(r, req, followed)
            }
        }
        return Plan.None
    }

    /**
     * The first (or a later) load of O by Media3 completed: [finalUrl] is where the redirects ended, [summary] the line
     * scan of the body (null when it was not read to its end).
     */
    fun onOriginLoaded(finalUrl: String?, summary: HlsPlaylistScanner.Summary?) {
        if (finalUrl == null || summary == null) return
        var line: String? = null
        lock.withLock {
            when (state) {
                State.OFF -> return
                State.UNKNOWN -> {
                    val off = when {
                        // (Code review A3) Compared as parsed URLs: OkHttp's normal form of O (lower-case scheme and
                        // host, no default port, %-encoding) is not a redirect.
                        isOrigin(finalUrl) -> "not_redirected"
                        summary.master -> "multivariant"
                        !summary.isMedia -> "not_media"
                        summary.endList -> "endlist"
                        else -> null
                    }
                    if (off != null) {
                        state = State.OFF
                        debug("off:$off t=${ctx.tuneId}")
                        return
                    }
                    state = State.ACTIVE
                    routes += Route(finalUrl, summary, clock.nowMs())
                    line = "active t=${ctx.tuneId} pl=#${routes.last().id} edge=${hostOf(finalUrl)} td=${summary.targetDurationS ?: "-"}"
                }
                State.ACTIVE -> {
                    if (summary.master || !summary.isMedia || isOrigin(finalUrl)) return
                    if (routes.none { it.logical == finalUrl } && routes.size < MAX_ROUTES) routes += Route(finalUrl, summary, clock.nowMs())
                }
            }
        }
        line?.let { debug(it) }
    }

    /** A pinned reload served a media playlist: it becomes the route's last good one. */
    fun onPinnedLoaded(route: Route, summary: HlsPlaylistScanner.Summary) {
        lock.withLock {
            route.last = summary
            route.lastAt = clock.nowMs()
            route.fresh = false
        }
    }

    /**
     * No renewal for the rest of the session ([reason]: `loop`, a redirect answered the first reload of a URL we just
     * adopted or learned, so this provider redirects every reload; `too_large`). (Code review A4) The routes stay: a
     * renewed route keeps being requested at its current URL.
     */
    fun turnOff(route: Route?, reason: String) {
        lock.withLock {
            if (renewOff) return
            renewOff = true
        }
        out("declined:$reason t=${ctx.tuneId} pl=#${route?.id ?: "-"}")
    }

    /** [route]'s reloads follow redirects from now on (a reference that cannot be rebased byte-safely, an other host). */
    fun followFromNowOn(route: Route) {
        route.follow = true
    }

    /** True while [route] is on its first reload since it was learned or adopted. */
    fun isFresh(route: Route): Boolean = lock.withLock { route.fresh }

    /** The fallback [route] already took, if any (its later reloads fail the same way without network). */
    fun fallbackOf(route: Route): Outcome? = lock.withLock { route.fallback }

    /** [requested] (Media3's URL for [route], maybe with delivery directives) mapped to the route's current URL. */
    fun requestUrlFor(route: Route, requested: String): String = requestUrl(route, requested) ?: route.current

    // ---------------------------------------------------------------- renewal

    /**
     * Renews [route] after a reload of its current URL failed with [trigger], on the caller's thread and lease. [fetch]
     * requests O (redirects followed) and must never throw. Single flight: a second caller waits (interruptibly) for the
     * running one and takes its outcome. [soft] (an I/O failure before the response headers, code review A1): at most
     * one attempt per route per [IO_COOLDOWN_MS], the budget is never disabled by it, and the caller adopts only a
     * [Outcome.Renewed]. Logs the outcome; never stores a fallback or signals the ladder ([fallBack] does).
     * Never throws except [InterruptedIOException] when interrupted while waiting.
     */
    fun renew(route: Route, trigger: String, soft: Boolean, fetch: () -> Fetched): Outcome {
        val startedAt = clock.nowMs()
        // (Code review A9) A transient "no network" right at the expiry: re-check briefly before declining.
        val isOnline = if (soft) online.isOnline() else awaitOnline()
        val flight: Flight
        var early: Outcome? = null
        lock.lockInterruptiblyOrThrow()
        try {
            while (true) {
                if (ended) return Outcome.Cancelled
                route.fallback?.let { return it }
                val f = inflight ?: break
                val left = startedAt + waitMs - clock.nowMs()
                if (left <= 0) {
                    early = Outcome.Declined(BUSY)
                    break
                }
                try {
                    flightDone.await(left, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("renewal wait interrupted")
                }
                if (f.route === route) f.outcome?.let { o -> return if (o is Outcome.Renewed) Outcome.Joined else o }
            }
            if (early == null) {
                val soft0 = route.softAt
                early = when {
                    !isOnline -> Outcome.Declined(OFFLINE)
                    soft && soft0 != null && startedAt - soft0 < IO_COOLDOWN_MS -> Outcome.Declined(COOLDOWN)
                    budget.disabled(startedAt) -> Outcome.Declined("disabled")
                    soft && !budget.tryTakeSoft(startedAt) -> Outcome.Declined(COOLDOWN)
                    !soft && !budget.tryTake(startedAt) -> Outcome.Declined("budget")
                    else -> null
                }
                if (soft && early == null) route.softAt = startedAt
            }
            flight = Flight(route)
            if (early == null) inflight = flight
        } finally {
            lock.unlock()
        }
        if (early != null) {
            report(route, trigger, early, startedAt, null)
            return early
        }
        var outcome: Outcome = Outcome.Failed("internal")
        var before: HlsPlaylistScanner.Summary? = null
        try {
            before = lock.withLock { route.last }
            outcome = evaluate(route, fetch(), startedAt)
        } finally {
            lock.withLock {
                flight.outcome = outcome
                if (inflight === flight) inflight = null
                flightDone.signalAll()
            }
        }
        report(route, trigger, outcome, startedAt, before)
        if (outcome is Outcome.Renewed) ctx.onEvent(PlaybackEventType.DECISION, RENEWED_CODE, "")
        return outcome
    }

    /**
     * The reload of [route] ended in a fallback ([o]): stored, so later reloads fail the same way without network, and
     * the controller is told at once (design §6.6). No-op for an outcome that is not a fallback or a route that fell back.
     */
    fun fallBack(route: Route, o: Outcome) {
        if (!o.isFallback) return
        lock.withLock {
            if (ended || route.fallback != null) return
            route.fallback = o
        }
        ctx.onEvent(PlaybackEventType.STALL, FALLBACK_CODE, o.label)
    }

    /** Null when [summary] may continue [route]'s last good playlist (the continuity rule), else the rule it breaks. */
    fun continuityOf(route: Route, summary: HlsPlaylistScanner.Summary): String? {
        val (last, lastAt) = lock.withLock { route.last to route.lastAt }
        return continuityViolation(last, lastAt, summary, clock.nowMs())
    }

    /**
     * (Code review A2) A redirect on a pinned reload whose renewal did not succeed was followed (today's request) and
     * gave a usable continuation at [finalUrl]: it becomes the last good playlist. On the same host as the logical URL,
     * [finalUrl] becomes the current URL ([adopt]); on another host the route follows redirects from now on.
     */
    fun onFollowed(route: Route, finalUrl: String, summary: HlsPlaylistScanner.Summary, adopt: Boolean, trigger: String) {
        lock.withLock {
            route.last = summary
            route.lastAt = clock.nowMs()
            if (adopt && finalUrl != route.current) {
                route.current = finalUrl
                route.fresh = true
            } else {
                route.fresh = false
            }
            if (!adopt) route.follow = true
        }
        out("followed t=${ctx.tuneId} pl=#${route.id} trigger=$trigger edge=${hostOf(finalUrl)} ${if (adopt) "adopted" else "other_host"}")
    }

    private fun evaluate(route: Route, got: Fetched, startedAt: Long): Outcome {
        if (got === Fetched.CANCELLED || ended) return Outcome.Cancelled
        got.phase?.let { return Outcome.Failed(if (got.status != null) "status:${got.status}" else it) }
        val finalUrl = got.finalUrl ?: return Outcome.Failed("internal")
        val body = got.body ?: return Outcome.Failed("internal")
        if (!sameAuthority(finalUrl, route.logical)) return Outcome.Declined("host")
        val summary = mediaSummary(body) ?: return Outcome.Failed("not_playlist")
        if (summary.master) return Outcome.Declined("multivariant")
        val now = clock.nowMs()
        val (last, lastAt) = lock.withLock { route.last to route.lastAt }
        continuityViolation(last, lastAt, summary, now)?.let { return Outcome.Misaligned(it) }
        val served = if (finalUrl == route.logical) body else PlaylistRebase.rebase(body, route.logical, finalUrl) ?: return Outcome.Failed("rebase")
        lock.withLock {
            if (ended) return Outcome.Cancelled
            route.current = finalUrl
            route.last = summary
            route.lastAt = now
            route.fresh = true
            route.follow = false
            // (Code review A5) A renewal that succeeds clears any fallback a timed-out waiter might have left.
            route.fallback = null
        }
        telemetry.renewalCount.incrementAndGet()
        return Outcome.Renewed(served)
    }

    /** A segment or key open was refused with [status]: the typed fast signal for 401/403/410 (design §6.7). */
    fun onSegmentRefused(status: Int) {
        if (status != 401 && status != 403 && status != 410) return
        lock.withLock {
            if (state != State.ACTIVE || ended || segmentSignalled) return
            segmentSignalled = true
        }
        out("segment:$status t=${ctx.tuneId}")
        ctx.onEvent(PlaybackEventType.STALL, SEGMENT_CODE, "status=$status")
    }

    private fun report(route: Route, trigger: String, o: Outcome, startedAt: Long, before: HlsPlaylistScanner.Summary?) {
        if (o === Outcome.Joined || o === Outcome.Cancelled) return
        // A blip's repeated reloads inside the cool-down: one line per blip is enough.
        if (o is Outcome.Declined && o.reason == COOLDOWN) return
        val ms = clock.nowMs() - startedAt
        val line = StringBuilder(160).append(o.label).append(" t=").append(ctx.tuneId).append(" pl=#").append(route.id)
            .append(" trigger=").append(trigger).append(" in=").append(ms).append("ms")
        if (o is Outcome.Renewed || o is Outcome.Misaligned) {
            val after = lock.withLock { route.last }
            line.append(" edge=").append(hostOf(route.current))
            if (before != null) {
                line.append(" msn=").append(before.msn ?: "-").append('>').append(if (o is Outcome.Renewed) after.msn ?: "-" else "?")
                line.append(" td=").append(before.targetDurationS ?: "-").append('>').append(if (o is Outcome.Renewed) after.targetDurationS ?: "-" else "?")
            }
            if (o is Outcome.Renewed) line.append(" n=").append(telemetry.playlistRenewals)
        }
        out(line.toString())
    }

    // ---------------------------------------------------------------- helpers

    /** Media3's URL [s] is O itself: the `MediaItem` URI, or the same URL in another written form (code review A3). */
    private fun isOrigin(s: String): Boolean = s == ctx.resolved.url || sameUrl(s, ctx.resolved.url)

    /** [requested] mapped to [r]'s current URL: equal to the logical URL, or it plus HLS delivery directives. */
    private fun requestUrl(r: Route, requested: String): String? {
        val current = r.current
        if (requested == r.logical) return current
        if (!requested.startsWith(r.logical)) return null
        val rest = requested.substring(r.logical.length)
        if (rest.length < 2 || (rest[0] != '?' && rest[0] != '&')) return null
        val params = rest.substring(1)
        if (params.split('&').any { !it.startsWith("_HLS_") }) return null
        return current + (if (current.contains('?')) "&" else "?") + params
    }

    /** (Code review A9) Online now, or within [OFFLINE_RECHECKS] re-checks [OFFLINE_RECHECK_MS] apart (≤ 1.5 s). */
    private fun awaitOnline(): Boolean {
        if (online.isOnline()) return true
        repeat(OFFLINE_RECHECKS) {
            if (ended) return false
            try {
                pause(OFFLINE_RECHECK_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("renewal online check interrupted")
            }
            if (online.isOnline()) return true
        }
        return false
    }

    private fun ReentrantLock.lockInterruptiblyOrThrow() {
        try {
            lockInterruptibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("renewal lock interrupted")
        }
    }

    companion object {
        const val TAG = "HlsRenewal"

        /** Kill switch: false makes `liveHls` build exactly Media3's default HLS data sources (today's behaviour). */
        const val ENABLED = true

        /** The controller's typed fast signal codes (`PlaybackEventType.STALL`, detail = outcome label / status). */
        const val FALLBACK_CODE = "HLS_RENEWAL_FALLBACK"
        const val SEGMENT_CODE = "HLS_SEGMENT_REFUSED"
        /** (Code review A8) `PlaybackEventType.DECISION`: one playlist URL renewed in place (the controller counts it). */
        const val RENEWED_CODE = "HLS_PLAYLIST_RENEWED"

        const val BUDGET_MAX = 3
        const val BUDGET_WINDOW_MS = 120_000L
        const val BUDGET_DISABLE_MS = 600_000L
        /** A waiting loader gives up after this long (O connect + headers + body, with margin). */
        const val WAIT_MS = 15_000L
        /**
         * (Code review A1) At most one renewal attempt per route per this long after an I/O failure: a blip's three
         * Media3 retries then cost at most one request to O, and the retries run on their own schedule.
         */
        const val IO_COOLDOWN_MS = 30_000L
        /** (Code review A9) Online re-checks before an expiry is declined as offline: 3 × 500 ms. */
        const val OFFLINE_RECHECKS = 3
        const val OFFLINE_RECHECK_MS = 500L
        /** Largest playlist body buffered (a live window of thousands of segments still fits). */
        const val MAX_PLAYLIST_BYTES = 1 shl 20
        const val MAX_ROUTES = 4
        /**
         * (Code review A6) Target duration: a change up to max([TD_TOLERANCE_S], [TD_TOLERANCE_PERCENT] % of the last
         * one) is the same stream. `#EXT-X-TARGETDURATION` is the longest segment rounded up, so a segmenter cutting at
         * ≈ 11 s flips between 11 and 12 (measured, design §1.2); 25 % of 11 s allows 11 ↔ 13, and the ±2 s floor keeps
         * short targets (2 s → 4 s) from tripping on the same rounding. A different stream is caught by `msn`/`names`.
         */
        const val TD_TOLERANCE_S = 2L
        const val TD_TOLERANCE_PERCENT = 25L
        const val MSN_SLACK = 2L
        /** (Code review A7) The same MSN on both sides: PDTs within this (when both carry one) and durations within [DUR_TOLERANCE_MS]. */
        const val PDT_TOLERANCE_MS = 1_000L
        const val DUR_TOLERANCE_MS = 500L
        internal const val OFFLINE = "offline"
        internal const val BUSY = "busy"
        internal const val COOLDOWN = "cooldown"
        private val QUIET_DECLINES = setOf(OFFLINE, BUSY, COOLDOWN)

        /** Routers built in this process (test hook: TS, archive, clip and ring paths must leave it unchanged). */
        val created = AtomicInteger()

        /** Info lines (release builds too): hosts and salted hashes only. Test hook. */
        @Volatile var out: (String) -> Unit = { line -> BoxLog.i(TAG) { line } }

        /** Debug-only lines (once per session). Test hook. */
        @Volatile var debug: (String) -> Unit = { line -> BoxLog.d(TAG) { line } }

        fun hostOf(url: String): String = url.toHttpUrlOrNull()?.let(HlsEdgeProbe::hostLabel) ?: "-"

        /** Same scheme, host and port. */
        fun sameAuthority(a: String, b: String): Boolean {
            val x = a.toHttpUrlOrNull() ?: return false
            val y = b.toHttpUrlOrNull() ?: return false
            return x.scheme == y.scheme && x.host == y.host && x.port == y.port
        }

        /** (Code review A3) The same URL once both are in OkHttp's normal form (`HttpUrl` equality); false if either is not one. */
        fun sameUrl(a: String, b: String): Boolean {
            val x = a.toHttpUrlOrNull() ?: return false
            return x == b.toHttpUrlOrNull()
        }

        /** The scan of [body] when it starts with `#EXTM3U` (after a BOM and blank space); else null. */
        fun mediaSummary(body: ByteArray): HlsPlaylistScanner.Summary? {
            var i = 0
            if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) i = 3
            while (i < body.size && (body[i] == ' '.code.toByte() || body[i] == '\t'.code.toByte() || body[i] == '\r'.code.toByte() || body[i] == '\n'.code.toByte())) i++
            val tag = "#EXTM3U".toByteArray(Charsets.US_ASCII)
            if (body.size - i < tag.size) return null
            for (k in tag.indices) if (body[i + k] != tag[k]) return null
            val s = HlsPlaylistScanner(null, HlsEdgeProbe::hash)
            s.feed(body, 0, body.size)
            val summary = s.finish()
            return if (summary.master || summary.isMedia) summary else null
        }

        /** (Code review A6) The target-duration rule: [td1] may follow [td0]. */
        fun targetDurationAgrees(td0: Long, td1: Long): Boolean {
            val d = kotlin.math.abs(td1 - td0)
            return d <= TD_TOLERANCE_S || d * 100 <= td0 * TD_TOLERANCE_PERCENT
        }

        /**
         * The continuity rule (design §6.4, review M4, code review A6/A7): null when [next] may continue [last] (loaded at
         * [lastAt]), else the name of the first rule it breaks.
         */
        fun continuityViolation(last: HlsPlaylistScanner.Summary, lastAt: Long, next: HlsPlaylistScanner.Summary, nowMs: Long): String? {
            if (next.master || !next.isMedia) return "media"
            if (next.endList) return "endlist"
            val td0 = last.targetDurationS ?: return "td"
            val td1 = next.targetDurationS ?: return "td"
            if (!targetDurationAgrees(td0, td1)) return "td"
            val m0 = last.msn ?: 0L
            val m1 = next.msn ?: 0L
            if (m1 < m0) return "msn_back"
            val tdMs = maxOf(1L, td0) * 1000L
            val elapsed = maxOf(0L, nowMs - lastAt)
            val bySegments = (elapsed + tdMs - 1) / tdMs
            if (m1 > m0 + last.segments + bySegments + MSN_SLACK) return "msn_ahead"
            if ((next.discontinuitySequence ?: 0L) < (last.discontinuitySequence ?: 0L)) return "dseq"
            // Where the two windows overlap, the same MSN must name the same file (the same segmenter), last as long and,
            // when both sides carry `#EXT-X-PROGRAM-DATE-TIME`, start at the same wall-clock time (code review A7).
            val old = HashMap<Long, HlsPlaylistScanner.Segment>(last.entries.size * 2)
            for (e in last.entries) old[e.msn] = e
            for (e in next.entries) {
                val was = old[e.msn] ?: continue
                if (was.nameHash != e.nameHash) return "names"
                if (was.durMs > 0 && e.durMs > 0 && kotlin.math.abs(was.durMs - e.durMs) > DUR_TOLERANCE_MS) return "dur"
                val p0 = was.pdtMs
                val p1 = e.pdtMs
                if (p0 != null && p1 != null && kotlin.math.abs(p0 - p1) > PDT_TOLERANCE_MS) return "pdt"
            }
            return null
        }
    }
}

/**
 * Rewrites the URI references of a playlist body served from [current] so that resolving them against [logical] (the
 * URI Media3 parses with) gives what resolving against [current] gives. A reference whose two resolutions agree (the
 * measured case: absolute paths on the same host) is left as is; nothing else in the body changes, byte for byte.
 */
@OptIn(UnstableApi::class)
internal object PlaylistRebase {
    private val URI_TAGS = arrayOf(
        "#EXT-X-KEY:", "#EXT-X-MAP:", "#EXT-X-PART:", "#EXT-X-PRELOAD-HINT:", "#EXT-X-RENDITION-REPORT:", "#EXT-X-SESSION-KEY:", "#EXT-X-MEDIA:",
    )

    /** The rebased body ([body] itself when nothing changes), or null when a reference is not plain ASCII. */
    fun rebase(body: ByteArray, logical: String, current: String): ByteArray? {
        // ISO-8859-1 maps every byte to one char and back: untouched text round-trips exactly.
        val text = String(body, Charsets.ISO_8859_1)
        val out = StringBuilder(text.length + 64)
        var changed = false
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(start, end)
            val rewritten = rewriteLine(line, logical, current) ?: return null
            if (rewritten !== line) changed = true
            out.append(rewritten)
            if (nl < 0) break
            out.append('\n')
            start = nl + 1
        }
        return if (changed) out.toString().toByteArray(Charsets.ISO_8859_1) else body
    }

    /** [line] itself when unchanged, a new string when rewritten, null when it cannot be rewritten safely. */
    private fun rewriteLine(line: String, logical: String, current: String): String? {
        var a = 0
        var z = line.length
        if (z > 0 && line[z - 1] == '\r') z--
        while (a < z && (line[a] == ' ' || line[a] == '\t')) a++
        while (z > a && (line[z - 1] == ' ' || line[z - 1] == '\t')) z--
        if (a >= z) return line
        if (line[a] != '#') {
            val ref = line.substring(a, z)
            val fixed = fix(ref, logical, current) ?: return null
            return if (fixed === ref) line else line.substring(0, a) + fixed + line.substring(z)
        }
        val tag = URI_TAGS.firstOrNull { line.startsWith(it, a) } ?: return line
        val key = line.indexOf("URI=\"", a + tag.length)
        if (key < 0) return line
        val v0 = key + 5
        val v1 = line.indexOf('"', v0)
        if (v1 < 0) return line
        val ref = line.substring(v0, v1)
        val fixed = fix(ref, logical, current) ?: return null
        return if (fixed === ref) line else line.substring(0, v0) + fixed + line.substring(v1)
    }

    private fun fix(ref: String, logical: String, current: String): String? {
        if (ref.any { it.code < 0x21 || it.code > 0x7E }) return null
        val viaLogical = UriUtil.resolve(logical, ref)
        val viaCurrent = UriUtil.resolve(current, ref)
        return if (viaLogical == viaCurrent) ref else viaCurrent
    }
}

/**
 * A renewal declined, misaligned or failed (design §6.6): fatal for Media3 (no retry; the ladder already has the typed
 * signal). Built against the request Media3 made (its logical URL), never O or the current URL; no cause, no headers,
 * no body. The message is the outcome label only.
 */
@OptIn(UnstableApi::class)
internal sealed class HlsRenewalException(label: String, dataSpec: DataSpec) :
    HttpDataSource.HttpDataSourceException("hls renewal $label", dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

/** The fresh playlist does not continue the last one ([rule]: td, msn_back, msn_ahead, dseq, endlist, names, media). */
@OptIn(UnstableApi::class)
internal class HlsRenewalMisaligned(val rule: String, dataSpec: DataSpec) : HlsRenewalException("misaligned:$rule", dataSpec)

/** Renewal declined (budget, host, …) or failed (O's status, connect, body, …): [outcome] is the label. */
@OptIn(UnstableApi::class)
internal class HlsRenewalFailed(val outcome: String, dataSpec: DataSpec) : HlsRenewalException(outcome, dataSpec)

/** The live HLS source's data sources (`HlsDataSourceFactory`: the tracker asks per data type). */
@OptIn(UnstableApi::class)
internal class RenewingHlsDataSourceFactory(
    private val ctx: StreamSessionContext,
    private val callFactory: Call.Factory,
    private val telemetry: SessionTelemetry,
    private val scope: SessionScope,
    private val clock: MonoClock,
    private val renewal: HlsPlaylistRenewal,
) : HlsDataSourceFactory {
    override fun createDataSource(dataType: Int): DataSource =
        RenewingHlsDataSource(ctx, BrokeredHttpDataSource(ctx, callFactory, telemetry, scope, clock), dataType, renewal, scope)
}

/**
 * One loader's data source on a live HLS session (design §6.3).
 *
 * - Segments, keys and every request the router does not plan: [inner] exactly as today (redirects followed, the same
 *   exceptions); a segment refused with 401/403/410 additionally sends the typed fast signal.
 * - Media3's load of O: as today, plus a line scan; at close the router learns (or declines) the pinned URL.
 * - A pinned reload: the current URL, **redirects not followed**, the body buffered (≤ 1 MiB) and checked. A 3xx, a
 *   401/403/404/410, a failure before the response headers, or a body that is not a media playlist triggers one renewal
 *   on this thread and lease: the old call is already closed when O opens. The fresh body is returned as the answer to
 *   Media3's request and [getUri] stays the URL Media3 asked for, so the tracker's base and identity do not move.
 *   Every failure is rebuilt against Media3's own `DataSpec` (no O, no current URL, no headers, no body).
 * - (Code review 2026-11-26) Only an unambiguous expiry ends in the typed fallback. A failure before the response
 *   headers (A1) adopts a renewal only when it succeeds, else rethrows the original error for Media3's own retries. A
 *   redirect whose renewal does not succeed (A2, A5, A9) is re-sent once with redirects followed, today's request: a
 *   usable continuation is served; only when that fails too does the ladder hear of it (or, offline / busy, Media3
 *   gets today's error). A route in follow mode (A4) is requested at its current URL with redirects followed.
 */
@OptIn(UnstableApi::class)
internal class RenewingHlsDataSource(
    private val ctx: StreamSessionContext,
    private val inner: BrokeredHttpDataSource,
    private val dataType: Int,
    private val renewal: HlsPlaylistRenewal,
    private val scope: SessionScope,
) : DataSource {
    /** FOLLOW: [inner] with redirects followed for a request that may not be Media3's own; its errors are translated. */
    private enum class Mode { CLOSED, PASS, ORIGIN, SERVE, FOLLOW }

    private var mode = Mode.CLOSED
    private var spec: DataSpec? = null
    private var body: ByteArray? = null
    private var pos = 0
    /** An oversized pinned body: [body] is its first part, the rest still comes from [inner]. */
    private var tail = false
    private var scanner: HlsPlaylistScanner? = null
    private var originFinal: String? = null
    private var originLength = C.LENGTH_UNSET.toLong()
    private var originBytes = 0L
    private var originEnded = false

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        reset()
        spec = dataSpec
        if (dataType == C.DATA_TYPE_MANIFEST) {
            when (val plan = renewal.plan(dataSpec.uri)) {
                HlsPlaylistRenewal.Plan.Origin -> return openOrigin(dataSpec)
                is HlsPlaylistRenewal.Plan.Pinned -> return openPinned(dataSpec, plan.route, plan.requestUrl, plan.follow, mayRenew = true)
                HlsPlaylistRenewal.Plan.None -> Unit
            }
        }
        inner.followRedirects = true
        val n = try {
            inner.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (dataType != C.DATA_TYPE_MANIFEST) renewal.onSegmentRefused(e.responseCode)
            throw e
        }
        mode = Mode.PASS
        return n
    }

    private fun openOrigin(dataSpec: DataSpec): Long {
        inner.followRedirects = true
        val n = inner.open(dataSpec)
        mode = Mode.ORIGIN
        originFinal = inner.uri?.toString()
        originLength = n
        originEnded = n == 0L
        scanner = HlsPlaylistScanner(null, HlsEdgeProbe::hash)
        return n
    }

    private fun openPinned(dataSpec: DataSpec, route: HlsPlaylistRenewal.Route, requestUrl: String, follow: Boolean, mayRenew: Boolean): Long {
        // A route that fell back answers the same way without network or a second signal.
        renewal.fallbackOf(route)?.let { throw typed(it, dataSpec) }
        val request = dataSpec.buildUpon().setUri(Uri.parse(requestUrl)).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        // (Code review A4) Renewal is off (loop, oversized body, budget time box) or this route follows redirects: its
        // current URL with today's request, never the expired logical one.
        if (follow) return openFollowed(request, dataSpec)
        inner.followRedirects = false
        var original: HttpDataSource.HttpDataSourceException? = null
        val trigger: String = try {
            inner.open(request)
            val (got, complete) = readBody()
            if (!complete) {
                // Not a live media playlist we can check: keep today's behaviour for the rest of the session.
                renewal.turnOff(route, "too_large")
                if (route.current == route.logical) {
                    tail = true
                    return serve(got, dataSpec)
                }
                // (Code review A4) A renewed route's body cannot be rebased unread: today's request at its current URL.
                inner.close()
                return openFollowed(request, dataSpec)
            }
            inner.close()
            val summary = HlsPlaylistRenewal.mediaSummary(got)
            if (summary != null && !summary.master) {
                val out = if (route.current == route.logical) got else PlaylistRebase.rebase(got, route.logical, route.current)
                if (out != null) {
                    renewal.onPinnedLoaded(route, summary)
                    return serve(out, dataSpec)
                }
                // A reference that cannot be rewritten byte-safely: Media3 reads this route at its real URL from now on.
                renewal.followFromNowOn(route)
                return openFollowed(request, dataSpec)
            }
            "body"
        } catch (e: HttpDataSource.HttpDataSourceException) {
            inner.close()
            if (e is LiveStreamException || scope.isEnded) throw translate(e, dataSpec)
            original = e
            triggerOf(e) ?: throw translate(e, dataSpec)
        }
        val redirect = trigger.startsWith("redirect")
        if (redirect && renewal.isFresh(route)) {
            // The first reload of a URL we just learned or adopted was redirected: this provider redirects every reload.
            renewal.turnOff(route, "loop")
            return openFollowed(request, dataSpec)
        }
        if (!mayRenew) {
            if (redirect) return openFollowed(request, dataSpec)
            throw original?.let { translate(it, dataSpec) } ?: HlsRenewalFailed("failed:$trigger", dataSpec)
        }
        val soft = trigger.startsWith("io:")
        val outcome = renewal.renew(route, trigger, soft) { fetchOrigin() }
        when (outcome) {
            is HlsPlaylistRenewal.Outcome.Renewed -> return serve(outcome.body, dataSpec)
            HlsPlaylistRenewal.Outcome.Joined ->
                return openPinned(dataSpec, route, renewal.requestUrlFor(route, dataSpec.uri.toString()), follow = false, mayRenew = false)
            HlsPlaylistRenewal.Outcome.Cancelled ->
                if (scope.isEnded) throw preempted(dataSpec) else throw InterruptedIOException("renewal cancelled")
            else -> Unit
        }
        // (Code review A1) A failure before the response headers may be a blip: the original error, unchanged, so
        // Media3's own retries run exactly as before; no fallback is stored and the ladder hears nothing.
        if (soft) throw translate(original ?: throw HlsRenewalFailed(outcome.label, dataSpec), dataSpec)
        // (Code review A2, A5, A9) A redirect whose renewal did not succeed: today's request (followed) decides.
        if (redirect) return followAfterRedirect(dataSpec, route, request, outcome, trigger)
        if (!outcome.isFallback) throw original?.let { translate(it, dataSpec) } ?: HlsRenewalFailed(outcome.label, dataSpec)
        renewal.fallBack(route, outcome)
        throw typed(outcome, dataSpec)
    }

    /**
     * (Code review A2) [request] was answered with a redirect and its renewal ended in [outcome] (not renewed). Offline,
     * busy or cool-down: exactly today's request, errors and retries ([openFollowed]). Otherwise the reload is re-sent once
     * with redirects followed; a media playlist that continues the last good one is served (rebased against Media3's
     * URL; on the same host its final URL becomes the current URL, on another host the route follows redirects from now
     * on). Only when that fails too does the renewal's outcome become the typed fallback.
     */
    private fun followAfterRedirect(
        dataSpec: DataSpec,
        route: HlsPlaylistRenewal.Route,
        request: DataSpec,
        outcome: HlsPlaylistRenewal.Outcome,
        trigger: String,
    ): Long {
        if (!outcome.isFallback) return openFollowed(request, dataSpec)
        inner.followRedirects = true
        val why: String = try {
            inner.open(request)
            val finalUrl = inner.uri?.toString()
            val (got, complete) = readBody()
            inner.close()
            val summary = if (complete) HlsPlaylistRenewal.mediaSummary(got) else null
            when {
                finalUrl == null -> "internal"
                !complete -> "too_large"
                summary == null || summary.master -> "body"
                else -> renewal.continuityOf(route, summary)?.let { "misaligned:$it" } ?: run {
                    val served = if (finalUrl == route.logical) got else PlaylistRebase.rebase(got, route.logical, finalUrl)
                    if (served != null) {
                        renewal.onFollowed(route, finalUrl, summary, adopt = HlsPlaylistRenewal.sameAuthority(finalUrl, route.logical), trigger)
                        return serve(served, dataSpec)
                    }
                    "rebase"
                }
            }
        } catch (e: HttpDataSource.HttpDataSourceException) {
            inner.close()
            if (e is LiveStreamException || scope.isEnded || isInterruption(e)) throw translate(e, dataSpec)
            if (e is HttpDataSource.InvalidResponseCodeException) "status:${e.responseCode}" else phaseOf(e)
        }
        HlsPlaylistRenewal.out("follow_failed:$why t=${ctx.tuneId} pl=#${route.id} trigger=$trigger")
        renewal.fallBack(route, outcome)
        throw typed(outcome, dataSpec)
    }

    /** [request] (maybe not Media3's own URL) with redirects followed: today's request; errors carry Media3's request only. */
    private fun openFollowed(request: DataSpec, dataSpec: DataSpec): Long {
        inner.followRedirects = true
        val n = try {
            inner.open(request)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            inner.close()
            throw translate(e, dataSpec)
        }
        spec = dataSpec
        mode = Mode.FOLLOW
        return n
    }

    private fun typed(o: HlsPlaylistRenewal.Outcome, spec: DataSpec): HlsRenewalException =
        if (o is HlsPlaylistRenewal.Outcome.Misaligned) HlsRenewalMisaligned(o.rule, spec) else HlsRenewalFailed(o.label, spec)

    /** One GET of O through [inner] (redirects followed, no `Range`, the session's headers). Never throws. */
    private fun fetchOrigin(): HlsPlaylistRenewal.Fetched {
        inner.followRedirects = true
        val o = DataSpec.Builder().setUri(Uri.parse(ctx.resolved.url)).setFlags(DataSpec.FLAG_ALLOW_GZIP).build()
        return try {
            inner.open(o)
            val finalUrl = inner.uri?.toString()
            val (got, complete) = readBody()
            inner.close()
            when {
                !complete -> HlsPlaylistRenewal.Fetched.failed("too_large")
                finalUrl == null -> HlsPlaylistRenewal.Fetched.failed("internal")
                else -> HlsPlaylistRenewal.Fetched.ok(finalUrl, got)
            }
        } catch (e: IOException) {
            inner.close()
            when {
                scope.isEnded || isInterruption(e) || (e is LiveStreamException && e.kind == LiveFailureKind.PREEMPTED) -> HlsPlaylistRenewal.Fetched.CANCELLED
                e is HttpDataSource.InvalidResponseCodeException -> HlsPlaylistRenewal.Fetched.failed("status", e.responseCode)
                e is LiveStreamException -> HlsPlaylistRenewal.Fetched.failed(e.kind.name.lowercase())
                (e as? HttpDataSource.HttpDataSourceException)?.type == HttpDataSource.HttpDataSourceException.TYPE_READ -> HlsPlaylistRenewal.Fetched.failed("body")
                else -> HlsPlaylistRenewal.Fetched.failed(phaseOf(e))
            }
        } catch (e: RuntimeException) {
            inner.close()
            HlsPlaylistRenewal.Fetched.failed("internal")
        }
    }

    /** The body of [inner]'s open response, at most [HlsPlaylistRenewal.MAX_PLAYLIST_BYTES] (then `complete` is false). */
    private fun readBody(): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(4096)
        val buf = ByteArray(8192)
        while (true) {
            val n = inner.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray() to true
            if (n > 0) out.write(buf, 0, n)
            if (out.size() > HlsPlaylistRenewal.MAX_PLAYLIST_BYTES) return out.toByteArray() to false
        }
    }

    private fun serve(bytes: ByteArray, dataSpec: DataSpec): Long {
        spec = dataSpec
        body = bytes
        pos = 0
        mode = Mode.SERVE
        return if (tail) C.LENGTH_UNSET.toLong() else bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return when (mode) {
            Mode.PASS -> inner.read(buffer, offset, length)
            Mode.FOLLOW -> try {
                inner.read(buffer, offset, length)
            } catch (e: HttpDataSource.HttpDataSourceException) {
                throw translate(e, spec ?: e.dataSpec)
            }
            Mode.ORIGIN -> {
                val n = inner.read(buffer, offset, length)
                if (n == C.RESULT_END_OF_INPUT) {
                    originEnded = true
                } else if (n > 0) {
                    originBytes += n
                    if (originLength != C.LENGTH_UNSET.toLong() && originBytes >= originLength) originEnded = true
                    try {
                        scanner?.feed(buffer, offset, n)
                    } catch (_: Throwable) {
                        scanner = null
                    }
                }
                n
            }
            Mode.SERVE -> {
                val b = body ?: return C.RESULT_END_OF_INPUT
                if (pos < b.size) {
                    val n = minOf(length, b.size - pos)
                    System.arraycopy(b, pos, buffer, offset, n)
                    pos += n
                    n
                } else if (tail) {
                    try {
                        inner.read(buffer, offset, length)
                    } catch (e: HttpDataSource.HttpDataSourceException) {
                        throw translate(e, spec ?: e.dataSpec)
                    }
                } else {
                    C.RESULT_END_OF_INPUT
                }
            }
            Mode.CLOSED -> throw IOException("not open")
        }
    }

    override fun getUri(): Uri? = if (mode == Mode.SERVE) spec?.uri else inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (mode == Mode.SERVE) emptyMap() else inner.responseHeaders

    override fun close() {
        val was = mode
        try {
            if (was == Mode.PASS || was == Mode.ORIGIN || was == Mode.FOLLOW || tail) inner.close()
        } finally {
            if (was == Mode.ORIGIN && originEnded) {
                try {
                    renewal.onOriginLoaded(originFinal, scanner?.finish())
                } catch (_: Throwable) {
                    // Learning is best effort: a bug here leaves the router UNKNOWN (today's behaviour).
                }
            }
            reset()
        }
    }

    private fun reset() {
        mode = Mode.CLOSED
        body = null
        pos = 0
        tail = false
        scanner = null
        originFinal = null
        originLength = C.LENGTH_UNSET.toLong()
        originBytes = 0L
        originEnded = false
    }

    private fun preempted(dataSpec: DataSpec): LiveStreamException {
        val info = FailureInfo(null, ctx.resolved.host, 0, 0, FailureInfo.Phase.OPEN, null, null, 0)
        return LiveStreamException(LiveFailureKind.PREEMPTED, info, dataSpec, null)
    }

    companion object {
        /** The renewal trigger word for a pinned reload's open failure, or null when it is not a trigger. */
        fun triggerOf(e: HttpDataSource.HttpDataSourceException): String? {
            if (e is HttpDataSource.InvalidResponseCodeException) {
                return when (val code = e.responseCode) {
                    in 300..399 -> "redirect:$code"
                    401, 403, 404, 410 -> "status:$code"
                    else -> null
                }
            }
            if (e is LiveStreamException || e is HlsRenewalException) return null
            if (e.type != HttpDataSource.HttpDataSourceException.TYPE_OPEN || isInterruption(e)) return null
            if (e is HttpDataSource.CleartextNotPermittedException) return null
            val kind = networkKindOf(e)
            if (kind == NetworkFailureKind.CLEARTEXT) return null
            return "io:${kind.name.lowercase()}"
        }

        fun phaseOf(e: Throwable): String = when (networkKindOf(e)) {
            NetworkFailureKind.DNS -> "dns"
            NetworkFailureKind.CONNECT -> "connect"
            NetworkFailureKind.TIMEOUT -> "timeout"
            NetworkFailureKind.TLS -> "tls"
            else -> "io"
        }

        /**
         * (review M6) [e] rebuilt against Media3's request [spec], as `hide()` does for the placeholder: same type, status
         * and reason; no URL of ours, no response headers, no error body, and a cause only when it holds no `DataSpec`.
         */
        fun translate(e: HttpDataSource.HttpDataSourceException, spec: DataSpec): HttpDataSource.HttpDataSourceException = when (e) {
            is HttpDataSource.InvalidResponseCodeException ->
                HttpDataSource.InvalidResponseCodeException(e.responseCode, null, e.cause?.let(::safeCause), emptyMap(), spec, ByteArray(0))
            is LiveStreamException -> LiveStreamException(e.kind, e.info, spec, e.cause?.let(::safeCause))
            is HlsRenewalException -> e
            else -> {
                val cause = safeCause(e)
                if (cause != null) HttpDataSource.HttpDataSourceException(cause, spec, e.reason, e.type)
                else HttpDataSource.HttpDataSourceException(spec, e.reason, e.type)
            }
        }
    }
}
