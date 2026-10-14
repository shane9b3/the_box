package app.thebox.player.datasource

import app.thebox.core.playback.ConnectionHandle
import app.thebox.core.playback.CoreLease
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * `Call.Factory` that tags requests, attaches every `Call` to the session lease (a per-call `EventListener` via
 * `Call.addEventListener`; `callEnd`/`callFailed` detach and release) and records status/redirects/final host (§1.5, S2).
 *
 * Lease rule (§3.5.2, reviewed S2): the lease is released only once it has no live call **and** either its holder
 * said it is done ([finish]) or the broker pre-empted it. OkHttp fires `callEnd`/`callFailed` from `RealCall.callDone`
 * after it closed the socket, so a release never precedes the socket close. A call created after the lease was
 * pre-empted (or after [finish]) is cancelled at once and never reaches the network.
 *
 * [client] is normally an `OkHttpClient` from [PlaybackHttpClients]; any `Call.Factory` whose calls support
 * `addEventListener` works.
 */
internal class BrokeredCallFactory(
    private val client: Call.Factory,
    private val lease: CoreLease,
    private val telemetry: SessionTelemetry? = null,
) : Call.Factory {

    /** Request tag (diagnostics, interceptors): which tune and lease a request belongs to. Holds no URL. */
    data class LeaseTag(val tuneId: Long, val leaseId: Long)

    private val lock = Any()
    private val live = ArrayList<Handle>(2)
    private var ending = false
    private var released = false

    override fun newCall(request: Request): Call {
        val call = client.newCall(request.newBuilder().tag(LeaseTag::class.java, LeaseTag(lease.ownerTuneId, lease.id)).build())
        val handle = Handle(call)
        call.addEventListener(handle)
        val refuse = synchronized(lock) {
            live += handle
            ending
        }
        handle.attached = !refuse && lease.attach(handle)
        if (!handle.attached) call.cancel()
        return call
    }

    /** Cancels every live call (synchronous socket close on HTTP/1.1). Idempotent; any thread. */
    fun cancelAll() {
        val snapshot = synchronized(lock) { if (live.isEmpty()) return else live.toTypedArray() }
        for (h in snapshot) h.call.cancel()
    }

    /** The holder is done with the lease: release it as soon as no call is live (maybe now). */
    fun finish() {
        synchronized(lock) { ending = true }
        maybeRelease()
    }

    /**
     * Releases the lease now, live calls or not. Only after [cancelAll]: the sockets are closed, only the body close
     * by a loader thread is still pending (`TransportFactory.endSession`).
     */
    fun forceRelease() {
        synchronized(lock) {
            ending = true
            if (released) return
            released = true
        }
        lease.release()
    }

    fun liveCalls(): Int = synchronized(lock) { live.size }

    val isReleased: Boolean get() = synchronized(lock) { released }

    val leaseIsPreempted: Boolean get() = lease.isPreempted

    private fun onCallDone(handle: Handle) {
        synchronized(lock) { live.remove(handle) }
        if (handle.attached) lease.detach(handle)
        maybeRelease()
    }

    private fun maybeRelease() {
        val release = synchronized(lock) {
            if (!released && live.isEmpty() && (ending || lease.isPreempted)) {
                released = true
                true
            } else {
                false
            }
        }
        if (release) lease.release()
    }

    private inner class Handle(val call: Call) : EventListener(), ConnectionHandle {
        @Volatile var attached = false
        private var done = false

        override fun cancel() = call.cancel()

        override fun responseHeadersEnd(call: Call, response: Response) {
            telemetry?.onResponse(response.code, hostOf(response.request.url), response.isRedirect)
        }

        override fun callEnd(call: Call) = done()

        override fun callFailed(call: Call, ioe: IOException) = done()

        private fun done() {
            synchronized(this) { if (done) return else done = true }
            onCallDone(this)
        }
    }

    companion object {
        /** `host[:port]` (port only when not the scheme default). Never a path or query. */
        fun hostOf(url: HttpUrl): String =
            if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
    }
}
