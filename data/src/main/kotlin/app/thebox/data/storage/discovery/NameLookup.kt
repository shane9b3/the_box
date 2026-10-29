package app.thebox.data.storage.discovery

import app.thebox.core.storage.NameSource
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.discovery.DiscoveryMerge
import app.thebox.core.storage.discovery.Ipv4
import app.thebox.core.storage.discovery.NbnsCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A name for an address the NSD finder did not name (§7.1): NetBIOS node status, then reverse DNS, else none. */
internal fun interface NameLookup {
    suspend fun lookup(address: String): Pair<String, NameSource>?
}

/**
 * [NameLookup] over the network. NBNS: one unicast `NBSTAT *` to UDP 137 of [address], answers accepted only from that
 * address with our transaction id, within NBNS_TIMEOUT_MS; the socket is closed at once if the search is cancelled.
 * Reverse DNS runs on [dnsExecutor] behind a REVERSE_DNS_TIMEOUT_MS bound: `getnameinfo` cannot be interrupted, so a
 * late answer is dropped, and the bounded pool and queue keep abandoned lookups from piling up.
 */
internal class NetworkNameLookup(
    private val sockets: LanSockets,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val dnsExecutor: Executor = sharedDnsExecutor,
    private val nbnsTimeoutMs: Long = StorageConstants.NBNS_TIMEOUT_MS,
    private val dnsTimeoutMs: Long = StorageConstants.REVERSE_DNS_TIMEOUT_MS,
    private val nbnsPort: Int = StorageConstants.NBNS_PORT,
    private val reverse: (ByteArray) -> String? = { b -> InetAddress.getByAddress(b).hostName },
) : NameLookup {
    override suspend fun lookup(address: String): Pair<String, NameSource>? {
        val bytes = ipv4Bytes(address) ?: return null
        netbios(address, bytes)?.let { return it to NameSource.NETBIOS }
        reverseDns(address, bytes)?.let { return it to NameSource.REVERSE_DNS }
        return null
    }

    suspend fun netbios(address: String, bytes: ByteArray? = ipv4Bytes(address)): String? {
        if (bytes == null) return null // Q-U8: not an IPv4 literal
        val socket = try { sockets.datagram() } catch (e: IOException) { return null }
        return try {
            blockingClosingOnCancel(socket, io) { query(socket, InetAddress.getByAddress(bytes)) }
        } catch (e: IOException) {
            null                                                             // unreachable, refused, or closed by the stop
        } catch (e: SecurityException) {
            null
        } finally {
            socket.closeQuietly()
        }
    }

    suspend fun reverseDns(address: String, bytes: ByteArray? = ipv4Bytes(address)): String? {
        if (bytes == null) return null // Q-U8: not an IPv4 literal
        val answer = CompletableDeferred<String?>()
        try {
            dnsExecutor.execute { answer.complete(try { reverse(bytes) } catch (e: Exception) { null }) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            return null                                                      // too many abandoned lookups in flight
        }
        val name = withTimeoutOrNull(dnsTimeoutMs) { answer.await() }
        return DiscoveryMerge.cleanName(name, address)
    }

    private fun query(socket: java.net.DatagramSocket, target: InetAddress): String? {
        val tid = Random.nextInt(0x10000)
        val req = NbnsCodec.nodeStatusRequest(tid)
        socket.send(DatagramPacket(req, req.size, InetSocketAddress(target, nbnsPort)))
        val deadline = System.nanoTime() + nbnsTimeoutMs * 1_000_000
        val buf = ByteArray(1024)
        while (true) {
            val leftMs = (deadline - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) return null
            socket.soTimeout = leftMs.toInt().coerceAtLeast(1)
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (p.address != target) continue                                // only the host we asked
            val status = NbnsCodec.parseNodeStatus(p.data, p.length, expectedTransactionId = tid) ?: continue
            return status.computerName()
        }
    }

    companion object {
        /** 4 threads, 32 queued, idle threads exit: an abandoned `getnameinfo` costs a thread for its own timeout only. */
        val sharedDnsExecutor: Executor by lazy {
            ThreadPoolExecutor(4, 4, 5, TimeUnit.SECONDS, ArrayBlockingQueue(32)) { r -> Thread(r, "box-find-dns").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
        }
    }
}

/**
 * Q-U8 (save-location closure fix pass, 2026-10-28): the four bytes of a dotted IPv4 literal ([Ipv4.parse]'s grammar),
 * for `InetAddress.getByAddress` (no DNS); null when [address] is not one. The one copy for the sweep and the lookups.
 */
internal fun ipv4Bytes(address: String): ByteArray? =
    Ipv4.parse(address)?.let { v -> byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) }
