package app.thebox.data.storage.discovery

import android.annotation.SuppressLint
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import app.thebox.core.common.BoxLog
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.discovery.DiscoveryMerge
import app.thebox.core.storage.discovery.Sighting
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The slice of [NsdManager] the finder uses (docs/design/m6-storage-locations.md §7.1). A seam so the listener quirks can
 * be tested without the platform service.
 */
internal interface NsdApi {
    /** API 34+: `registerServiceInfoCallback` instead of the deprecated, one-at-a-time `resolveService` (facts §4.2). */
    val watchSupported: Boolean
    fun discover(type: String, listener: NsdManager.DiscoveryListener)
    fun stopDiscovery(listener: NsdManager.DiscoveryListener)
    fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener)
    /** Registers a service-info callback; returns the token for [unwatch]. Only when [watchSupported]. */
    fun watch(info: NsdServiceInfo, executor: Executor, onUpdated: (NsdServiceInfo) -> Unit): Any
    fun unwatch(token: Any)
    fun addresses(info: NsdServiceInfo): List<InetAddress>
}

internal class AndroidNsdApi(private val nsd: NsdManager) : NsdApi {
    override val watchSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

    override fun discover(type: String, listener: NsdManager.DiscoveryListener) =
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)

    override fun stopDiscovery(listener: NsdManager.DiscoveryListener) = nsd.stopServiceDiscovery(listener)

    @Suppress("DEPRECATION") // resolveService: the only resolve below API 34; serialised by the caller
    override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) = nsd.resolveService(info, listener)

    @SuppressLint("NewApi") // only called when [watchSupported] (SDK_INT >= 34); Api34 is @RequiresApi(34)
    override fun watch(info: NsdServiceInfo, executor: Executor, onUpdated: (NsdServiceInfo) -> Unit): Any =
        Api34.watch(nsd, info, executor, onUpdated)

    override fun unwatch(token: Any) {
        if (Build.VERSION.SDK_INT >= 34) Api34.unwatch(nsd, token)
    }

    @Suppress("DEPRECATION") // getHost: the pre-34 single address
    override fun addresses(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)

    @RequiresApi(34)
    private object Api34 {
        fun watch(nsd: NsdManager, info: NsdServiceInfo, executor: Executor, onUpdated: (NsdServiceInfo) -> Unit): Any {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onUpdated(serviceInfo)
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, executor, cb)
            return cb
        }

        fun unwatch(nsd: NsdManager, token: Any) {
            nsd.unregisterServiceInfoCallback(token as NsdManager.ServiceInfoCallback)
        }
    }
}

/**
 * NsdManager `_smb._tcp` discovery (§7.1), plus `_device-info._tcp` for the §7.7 Mac hint (a hint-only [Sighting]).
 *
 * Quirks handled (facts §4.2):
 * - a **new listener per search** (one listener cannot be reused);
 * - `stopServiceDiscovery` only after `onDiscoveryStarted`; a stop that races a late `onDiscoveryStarted` stops it on
 *   arrival; nothing is stopped after `onStartDiscoveryFailed` (that throws `IllegalArgumentException`);
 * - every callback (main-thread before API 33) is posted to the search's `box-find` executor, so state has one thread;
 * - below API 34 resolves run **one at a time** (`FAILURE_ALREADY_ACTIVE`), SMB before device-info; from 34
 *   `registerServiceInfoCallback`, unregistered on stop.
 *
 * A resolve that never answers (seen on some builds) holds the queue until the search's DISCOVERY_TOTAL_MS ends it.
 */
internal class NsdFinder(
    private val api: NsdApi,
    private val executorFactory: () -> Executor = { findExecutor() },
) {
    /** Cold: each collection is one search with its own listeners; cancelling it stops discovery. */
    fun sightings(): Flow<Sighting> = callbackFlow {
        val executor = executorFactory()
        val session = Session(api, executor) { trySend(it) }
        executor.post { session.start() }
        awaitClose { executor.post { session.stop() } }
    }

    internal enum class DiscoveryState { NEW, STARTING, STARTED, FAILED, STOPPING, STOPPED }

    /** Everything here runs on [executor] (one thread), so no locks. */
    internal class Session(private val api: NsdApi, private val executor: Executor, private val emit: (Sighting) -> Unit) {
        inner class Discovery(val type: String) {
            var state = DiscoveryState.NEW
            var stopRequested = false

            /** New per search: a listener cannot be reused. */
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = executor.post { started() }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = executor.post {
                    state = DiscoveryState.FAILED
                    BoxLog.w(TAG, "nsd: start failed for $type ($errorCode)")
                }
                override fun onDiscoveryStopped(serviceType: String) = executor.post { state = DiscoveryState.STOPPED }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = executor.post { state = DiscoveryState.STOPPED }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) = executor.post { found(this@Discovery, serviceInfo) }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit   // the search is short; a row stays until "Search again"
            }

            fun start() {
                state = DiscoveryState.STARTING
                try {
                    api.discover(type, listener)
                } catch (e: RuntimeException) {   // IllegalArgumentException (listener in use), SecurityException
                    state = DiscoveryState.FAILED
                    BoxLog.w(TAG, "nsd: discover refused for $type (${e.javaClass.simpleName})")
                }
            }

            fun started() {
                if (state != DiscoveryState.STARTING) return
                state = DiscoveryState.STARTED
                if (stopRequested) stopNow()
            }

            fun stop() {
                when (state) {
                    DiscoveryState.STARTED -> stopNow()
                    DiscoveryState.STARTING -> stopRequested = true      // stopped when onDiscoveryStarted arrives
                    else -> Unit                                       // NEW / FAILED / already stopping: never stop
                }
            }

            private fun stopNow() {
                state = DiscoveryState.STOPPING
                try {
                    api.stopDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    state = DiscoveryState.STOPPED
                }
            }
        }

        private class Pending(val type: String, val info: NsdServiceInfo, var retries: Int = 0)

        val smb = Discovery(SMB_TYPE)
        val deviceInfo = Discovery(DEVICE_INFO_TYPE)
        var closed = false
            private set
        private val seen = HashSet<String>()
        private val smbQueue = ArrayDeque<Pending>()
        private val infoQueue = ArrayDeque<Pending>()
        private var resolving = false
        private val watches = ArrayList<Any>()

        fun start() {
            smb.start()
            deviceInfo.start()
        }

        fun stop() {
            if (closed) return
            closed = true
            smb.stop()
            deviceInfo.stop()
            smbQueue.clear(); infoQueue.clear()
            for (w in watches) try { api.unwatch(w) } catch (e: IllegalArgumentException) { /* already gone */ }
            watches.clear()
        }

        private fun found(d: Discovery, info: NsdServiceInfo) {
            if (closed) return
            val key = d.type + "/" + info.serviceName
            if (!seen.add(key) || seen.size > MAX_SERVICES) return
            if (api.watchSupported) {
                try {
                    watches += api.watch(info, executor) { updated -> if (!closed) deliver(d.type, updated) }
                } catch (e: RuntimeException) {
                    BoxLog.w(TAG, "nsd: watch refused (${e.javaClass.simpleName})")
                }
            } else {
                (if (d.type == SMB_TYPE) smbQueue else infoQueue).addLast(Pending(d.type, info))
                resolveNext()
            }
        }

        /** One resolve at a time below API 34; SMB first. */
        private fun resolveNext() {
            if (closed || resolving) return
            val p = smbQueue.removeFirstOrNull() ?: infoQueue.removeFirstOrNull() ?: return
            resolving = true
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = executor.post {
                    resolving = false
                    if (!closed) deliver(p.type, serviceInfo)
                    resolveNext()
                }
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = executor.post {
                    resolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && p.retries < MAX_RESOLVE_RETRIES && !closed) {
                        p.retries++
                        (if (p.type == SMB_TYPE) smbQueue else infoQueue).addLast(p)
                    }
                    resolveNext()
                }
            }
            try {
                api.resolve(p.info, listener)
            } catch (e: RuntimeException) {
                resolving = false
                BoxLog.w(TAG, "nsd: resolve refused (${e.javaClass.simpleName})")
                resolveNext()
            }
        }

        private fun deliver(type: String, info: NsdServiceInfo) {
            val v4 = try { api.addresses(info) } catch (e: RuntimeException) { emptyList() }
                .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            if (v4.isEmpty()) return
            if (type == SMB_TYPE) {
                val port = info.port.takeIf { it in 1..65535 }
                for (a in v4) emit(Sighting(a, info.serviceName, NameSource.NSD, port = port))
            } else {
                val model = info.attributes?.get("model")?.let { String(it, Charsets.UTF_8) }
                if (DiscoveryMerge.isAppleModel(model)) for (a in v4) emit(Sighting(a, port = null, apple = true, hintOnly = true))
            }
        }
    }

    companion object {
        const val SMB_TYPE = "_smb._tcp."
        const val DEVICE_INFO_TYPE = "_device-info._tcp."
        private const val MAX_SERVICES = 128
        private const val MAX_RESOLVE_RETRIES = 2
        private const val TAG = "ComputerFinder"
    }
}

/** Runs [block] on this executor; dropped only if the executor was shut down (production never shuts it down). */
internal fun Executor.post(block: () -> Unit) {
    try {
        execute(block)
    } catch (e: RejectedExecutionException) {
        // dropped
    }
}

/**
 * The per-search `box-find` thread: one thread at most, which exits after [idleMs] idle. It is never shut down, so a
 * late platform callback (a late `onDiscoveryStarted` after the page closed) still runs and is stopped on arrival.
 */
internal fun findExecutor(idleMs: Long = 2_000L): ExecutorService =
    ThreadPoolExecutor(0, 1, idleMs, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r -> Thread(r, "box-find").apply { isDaemon = true } }
