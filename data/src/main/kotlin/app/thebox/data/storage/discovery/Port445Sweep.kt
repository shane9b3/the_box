package app.thebox.data.storage.discovery

import android.net.Network
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.discovery.Ipv4
import app.thebox.core.storage.discovery.LanSubnet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Sockets for the finders, bound to the Wi-Fi/Ethernet [Network] that owns our LAN address (never the VPN, U-S2). */
internal interface LanSockets {
    fun stream(): Socket
    fun datagram(): DatagramSocket
}

/**
 * Binds to [network] where the platform allows it. With no VPN up, a failed bind (or no network handle) leaves the socket
 * unbound and the system routes it to our own /24. (C-S5) With [strict] (a VPN is up) a socket that cannot be bound is
 * closed and refused (IOException): a full-tunnel VPN would carry its SYNs and NBNS packets to the provider's network,
 * where an RFC 1918 /24 may well exist (§7.1, U-S2: never `TRANSPORT_VPN`).
 */
internal class NetworkLanSockets(private val network: Network?, private val strict: Boolean = false) : LanSockets {
    init { require(network != null || !strict) { "a strict socket source needs a network" } }

    override fun stream(): Socket = network?.let { n ->
        try { n.socketFactory.createSocket() } catch (e: IOException) { if (strict) throw e else null }
    } ?: Socket()

    override fun datagram(): DatagramSocket = DatagramSocket().also { s ->
        try { network?.bindSocket(s) } catch (e: IOException) {
            if (strict) { s.close(); throw e } // C-S5: never an unbound socket while a VPN is up
            /* routed by the system */
        }
    }
}

internal fun Closeable.closeQuietly() {
    try { close() } catch (e: IOException) { /* nothing to do */ } catch (e: RuntimeException) { /* nothing to do */ }
}

/**
 * Runs the blocking [block] on [dispatcher]; if the caller is cancelled, [resource] is closed at once from the cancelling
 * thread, so a blocked `connect`/`receive` (not interruptible) ends now instead of at its timeout (§7.1, Must).
 */
internal suspend fun <T> blockingClosingOnCancel(resource: Closeable, dispatcher: CoroutineDispatcher, block: () -> T): T = coroutineScope {
    val watcher = launch(Dispatchers.Unconfined) {
        try { awaitCancellation() } finally { resource.closeQuietly() }
    }
    try {
        withContext(dispatcher) { block() }
    } finally {
        watcher.cancel()
    }
}

/**
 * The TCP 445 sweep of our own /24 (§7.1, owner ruling 2: only while `FindComputers` is open). At most
 * [parallel] (SWEEP_PARALLEL) connects at a time, each bounded by [connectTimeoutMs] (SWEEP_CONNECT_TIMEOUT_MS), over the
 * ≤ SWEEP_MAX_HOSTS targets of [LanSubnet.sweepTargets]; worst case ⌈253 / 32⌉ × 400 ms ≈ 3.2 s. A connect is closed at
 * once; no SMB bytes are sent. **The stop path closes every pending socket**, since `Socket.connect(timeout)` is not
 * interruptible. Addresses are literal (`InetAddress.getByAddress`): no DNS.
 */
internal class Port445Sweep(
    private val sockets: LanSockets,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(StorageConstants.SWEEP_PARALLEL),
    private val parallel: Int = StorageConstants.SWEEP_PARALLEL,
    private val connectTimeoutMs: Int = StorageConstants.SWEEP_CONNECT_TIMEOUT_MS,
    private val port: Int = StorageConstants.SMB_DEFAULT_PORT,
) {
    data class Stats(val probed: Int, val open: Int)

    private val pending: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val stopped = AtomicBoolean(false)

    /** Sockets currently connecting (tests). */
    val pendingCount: Int get() = pending.size

    /** Probes every target of [subnet]; [onOpen] runs for each that accepted. One sweep per instance. */
    suspend fun sweep(subnet: LanSubnet, onOpen: suspend (String) -> Unit): Stats {
        val targets = subnet.sweepTargets()
        val queue = Channel<String>(Channel.UNLIMITED)
        targets.forEach { queue.trySend(it) }
        queue.close()
        val probed = AtomicInteger()
        val open = AtomicInteger()
        coroutineScope {
            val watcher = launch(Dispatchers.Unconfined) {
                try { awaitCancellation() } finally { stop() }
            }
            List(minOf(parallel, targets.size).coerceAtLeast(0)) {
                launch(dispatcher) {
                    for (host in queue) {
                        coroutineContext.ensureActive()
                        check(host in subnet) { "sweep target outside the /24" }   // never happens; the guard is the rule
                        probed.incrementAndGet()
                        if (probe(host)) {
                            open.incrementAndGet()
                            onOpen(host)
                        }
                    }
                }
            }.joinAll()
            watcher.cancel()
        }
        return Stats(probed.get(), open.get())
    }

    /** Closes every pending socket; later probes do nothing. Idempotent. */
    fun stop() {
        stopped.set(true)
        for (s in pending.toList()) s.closeQuietly()
    }

    private fun probe(host: String): Boolean {
        if (stopped.get()) return false
        val bytes = Ipv4.parse(host)?.let { v -> byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) } ?: return false
        val s = try { sockets.stream() } catch (e: IOException) { return false }
        pending += s
        try {
            if (stopped.get()) return false                    // a stop between the check and the add closes nothing else
            s.connect(InetSocketAddress(InetAddress.getByAddress(bytes), port), connectTimeoutMs)
            return true
        } catch (e: IOException) {
            return false                                       // refused, timed out, unreachable, or closed by stop()
        } catch (e: SecurityException) {
            return false
        } finally {
            pending -= s
            s.closeQuietly()
        }
    }
}
