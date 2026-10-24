// Storage locations commit 0 (docs/design/m6-storage-locations.md §16.1): the in-memory SMB seam for the JVM tests of
// WP-S1 (SmbRingStorage, reconnect, faults), WP-S2 (SmbPrepareNoIoTest, FallbackRingStorage) and WP-S4.
package app.thebox.core.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.storage.StorageConstants
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ==================================================================== the faults the fake throws (§4.3)

/** A dead transport (PacketReader exit, reset, EOF): [FakeRemoteLink.classify] → LINK_LOST. */
class FakeTransportException(message: String = "transport closed") : IOException(message)

/** An I/O wait timeout on a live transport: → SLOW. The cause is a [TimeoutException], like smbj's. */
class FakeTimeoutException(message: String = "timed out") : IOException(message, TimeoutException(message))

/** The NT statuses the fake produces. */
enum class FakeStatus(val cls: FaultClass) {
    NETWORK_NAME_DELETED(FaultClass.LINK_LOST), USER_SESSION_DELETED(FaultClass.LINK_LOST), FILE_CLOSED(FaultClass.LINK_LOST),
    DISK_FULL(FaultClass.FULL), QUOTA_EXCEEDED(FaultClass.FULL),
    ACCESS_DENIED(FaultClass.ACCESS), LOGON_FAILURE(FaultClass.ACCESS),
    OBJECT_NAME_NOT_FOUND(FaultClass.REMOVED), DELETE_PENDING(FaultClass.REMOVED), FILE_DELETED(FaultClass.REMOVED),
    /**
     * (C-S2) A server refusal on a live transport (INVALID_PARAMETER, INSUFFICIENT_RESOURCES, a lock conflict): SLOW —
     * fail the operation, never reconnect. (C-S7) SHARING_VIOLATION is one too: an exclusive open of a file another
     * client holds.
     */
    REFUSED(FaultClass.SLOW), SHARING_VIOLATION(FaultClass.SLOW),
}

/** A server status: classified by [FakeStatus.cls]. */
class FakeStatusException(val status: FakeStatus) : IOException("STATUS_$status")

/**
 * An in-memory [RemoteLink] with the fault injection of §16.1: per-call latency ([latencyMs]), [stallUntil],
 * [dropTransport] (LINK_LOST until a reconnect), [timeoutNext] (SLOW), [reconnectLatencyMs], [deleteFile], [fullAt],
 * [denyWrites], the [epoch], call counters ([calls], [ioCalls], [log]) and a record of every open mode ([opens]).
 *
 * Waits use real time against [clock] in [WAIT_SLICE_MS] slices and honour interrupts (→ InterruptedIOException, as smbj
 * does through `Futures.get`). A wait longer than [ioTimeoutMs] (by [clock]) ends as [FakeTimeoutException]. [post] runs
 * work on one daemon thread ("fake-box-smb"), or queues it for [runPosted] when [queuePosted] is true.
 *
 * [classify] follows §4.3's order: an interrupt anywhere (the current thread's flag or the cause chain) → CANCEL, then
 * [FakeTransportException] → LINK_LOST, then a [TimeoutException] in the chain → SLOW, then [FakeStatusException]'s
 * class; anything else → LINK_LOST.
 */
class FakeRemoteLink(
    val clock: MonoClock = MonoClock { System.nanoTime() / 1_000_000L },
    override val ioTimeoutMs: Long = StorageConstants.SMB_IO_TIMEOUT_MS,
    /** The server's clock for `lastWriteMs` (A-N2); settable. */
    @Volatile var serverNowMs: () -> Long = { 1_700_000_000_000L + clock.nowMs() },
) : RemoteLink {
    // ---------------------------------------------------------------- knobs
    /** Added to every share/file operation. */
    @Volatile var latencyMs: Long = 0L
    /** [share] waits until this is true (or its deadline passes → IOException UNREACHABLE). */
    @Volatile var ready: Boolean = true
    /** A reconnect takes this long. */
    @Volatile var reconnectLatencyMs: Long = 0L
    /** A reconnect fails (FakeTransportException) while true. */
    @Volatile var reconnectFails: Boolean = false
    /** Writes throw ACCESS_DENIED while true. */
    @Volatile var writesDenied: Boolean = false
    /** Queue [post]ed work for [runPosted] instead of running it on the fake box-smb thread. */
    @Volatile var queuePosted: Boolean = false

    // ---------------------------------------------------------------- state
    private val epochV = AtomicLong(1L)
    override val epoch: Long get() = epochV.get()
    @Volatile var transportUp: Boolean = true; private set
    @Volatile private var stallUntilMs: Long = Long.MIN_VALUE
    @Volatile private var timeoutPending: Boolean = false
    @Volatile private var statusPending: Pair<FakeStatus, String>? = null
    @Volatile private var stallPrefix: String? = null
    @Volatile private var stallPrefixUntil: Long = Long.MIN_VALUE
    @Volatile private var capacityLimit: Long = Long.MAX_VALUE
    private val reconnectLock = Any()

    private val files = ConcurrentHashMap<String, FakeFileData>()
    private val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("") }

    /** Every call on the link, its share and its files (retain/release excluded). */
    val calls = AtomicInteger()
    /** Calls that would cross the network: share, reconnect and every share/file operation. */
    val ioCalls = AtomicInteger()
    /** Names of the calls in order ("share", "open:<path>:<mode>", "write:<path>", …). */
    val log: MutableList<String> = CopyOnWriteArrayList()
    /** Every open, in order. */
    val opens: MutableList<Pair<String, OpenMode>> = CopyOnWriteArrayList()
    val reconnects = AtomicInteger()
    /** The open-file reference count ([retain]/[release]). */
    val refs = AtomicInteger()
    val posted = AtomicInteger()
    private val queue = LinkedBlockingQueue<Runnable>()
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "fake-box-smb").apply { isDaemon = true } } }

    // ---------------------------------------------------------------- fault injection
    /** Every share/file call blocks until [clock] reaches [monoMs] (a NAS disk asleep, a blackhole). */
    fun stallUntil(monoMs: Long) { stallUntilMs = monoMs }
    /** The transport dies: every call throws [FakeTransportException] until [reconnect] succeeds. */
    fun dropTransport() { transportUp = false }
    /** The next I/O call throws [FakeTimeoutException] once. */
    fun timeoutNext() { timeoutPending = true }
    /** (C-S2) The next I/O call whose name starts with [prefix] throws [status] once, on a live transport. */
    fun statusNext(status: FakeStatus, prefix: String = "") { statusPending = status to prefix }
    /** (C-B1) Every call whose name starts with [prefix] blocks until [clock] reaches [monoMs] (a hung CREATE, say). */
    fun stallCallsUntil(prefix: String, monoMs: Long) { stallPrefix = prefix; stallPrefixUntil = monoMs }
    /** Deletes [path] on the "server": open handles get FILE_DELETED, a reopen OBJECT_NAME_NOT_FOUND. */
    fun deleteFile(path: String) { files.remove(path)?.deleted = true }
    /** The share holds at most [bytes] in all: a write past it throws DISK_FULL; [RemoteShare.freeBytes] follows. */
    fun fullAt(bytes: Long) { capacityLimit = bytes }
    fun denyWrites(deny: Boolean = true) { writesDenied = deny }
    /** Runs every queued [post] (with [queuePosted]). Returns how many ran. */
    fun runPosted(): Int { var n = 0; while (true) { val r = queue.poll() ?: return n; r.run(); n++ } }

    /** The bytes of [path] as the server holds them; null when absent. */
    fun contents(path: String): ByteArray? = files[path]?.snapshot()
    fun exists(path: String): Boolean = files.containsKey(path)
    fun usedBytes(): Long = files.values.sumOf { it.size() }
    /** Seeds a server file (another box's, a stale one) with its LastWriteTime. */
    fun putFile(path: String, bytes: ByteArray = ByteArray(0), lastWriteMs: Long = serverNowMs()) {
        files[path] = FakeFileData().also { it.write(bytes, 0, bytes.size, 0L); it.lastWriteMs = lastWriteMs }
    }

    // ---------------------------------------------------------------- RemoteLink
    override fun share(deadlineMonoMs: Long): RemoteShare {
        count("share")
        while (!ready) {
            if (clock.nowMs() >= deadlineMonoMs) throw IOException("UNREACHABLE")
            sleepSlice()
        }
        if (!transportUp) throw FakeTransportException()
        return FakeRemoteShare(epoch)
    }

    override fun reconnect(failedEpoch: Long, deadlineMonoMs: Long): RemoteShare {
        count("reconnect")
        synchronized(reconnectLock) {
            if (epoch > failedEpoch && transportUp) return FakeRemoteShare(epoch) // single flight: someone already reconnected
            val until = clock.nowMs() + reconnectLatencyMs
            while (clock.nowMs() < until) {
                if (clock.nowMs() >= deadlineMonoMs) throw IOException("UNREACHABLE")
                sleepSlice()
            }
            if (reconnectFails || clock.nowMs() > deadlineMonoMs) throw FakeTransportException("reconnect failed")
            reconnects.incrementAndGet()
            transportUp = true
            epochV.incrementAndGet()
            return FakeRemoteShare(epoch)
        }
    }

    override fun classify(t: Throwable): RemoteFault {
        if (Thread.currentThread().isInterrupted) return RemoteFault(FaultClass.CANCEL)
        if (chain(t).any { it is InterruptedException || it is InterruptedIOException }) return RemoteFault(FaultClass.CANCEL)
        if (chain(t).any { it is FakeTransportException }) return RemoteFault(FaultClass.LINK_LOST)
        if (chain(t).any { it is TimeoutException }) return RemoteFault(FaultClass.SLOW)
        chain(t).firstNotNullOfOrNull { it as? FakeStatusException }?.let { return RemoteFault(it.status.cls) }
        return RemoteFault(FaultClass.LINK_LOST)
    }

    override fun retain() { refs.incrementAndGet() }
    override fun release() { refs.decrementAndGet() }

    override fun post(work: Runnable) {
        posted.incrementAndGet()
        if (queuePosted) queue.add(work) else worker.execute(work)
    }

    // ---------------------------------------------------------------- internals
    private fun chain(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause.takeIf { c -> c !== it } }

    private fun count(name: String) { calls.incrementAndGet(); ioCalls.incrementAndGet(); log += name }

    private fun sleepSlice() {
        try { Thread.sleep(WAIT_SLICE_MS) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("interrupted")
        }
    }

    /** The common entry of every share/file operation: latency, stall, transport, one-shot timeout. */
    private fun io(name: String, shareEpoch: Long) {
        count(name)
        val start = clock.nowMs()
        val prefixStall = if (stallPrefix?.let { name.startsWith(it) } == true) stallPrefixUntil else Long.MIN_VALUE
        val until = maxOf(stallUntilMs, start + latencyMs, prefixStall)
        while (clock.nowMs() < until) {
            if (clock.nowMs() - start >= ioTimeoutMs) throw FakeTimeoutException()
            sleepSlice()
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("interrupted")
        if (!transportUp || shareEpoch != epoch) throw FakeTransportException()
        if (timeoutPending) { timeoutPending = false; throw FakeTimeoutException() }
        statusPending?.let { (s, prefix) -> if (name.startsWith(prefix)) { statusPending = null; throw FakeStatusException(s) } }
    }

    inner class FakeRemoteShare(override val epoch: Long) : RemoteShare {
        override fun open(path: String, mode: OpenMode, deleteOnClose: Boolean, exclusive: Boolean): RemoteFile {
            io("open:$path:$mode", epoch)
            opens += path to mode
            val parent = path.substringBeforeLast('/', "")
            if (parent !in dirs) throw FakeStatusException(FakeStatus.OBJECT_NAME_NOT_FOUND)
            // C-S7: share modes. An exclusive open fails while any handle is open; any open fails while an exclusive one is.
            val existing = files[path]
            if (existing != null && (existing.exclusive || (exclusive && existing.openHandles.get() > 0))) throw FakeStatusException(FakeStatus.SHARING_VIOLATION)
            val data = when (mode) {
                OpenMode.CREATE_OVERWRITE -> FakeFileData().also { files[path] = it }
                OpenMode.OPEN_EXISTING -> existing ?: throw FakeStatusException(FakeStatus.OBJECT_NAME_NOT_FOUND)
            }
            if (!exclusive) data.lastWriteMs = serverNowMs()
            data.openHandles.incrementAndGet()
            if (exclusive) data.exclusive = true
            return FakeRemoteFile(path, data, epoch, deleteOnClose, exclusive)
        }

        override fun mkdirs(path: String) {
            io("mkdirs:$path", epoch)
            var p = ""
            for (part in path.split('/').filter { it.isNotEmpty() }) { p = if (p.isEmpty()) part else "$p/$part"; dirs += p }
        }

        override fun list(path: String, max: Int): List<RemoteEntry> {
            io("list:$path", epoch)
            if (path !in dirs) throw FakeStatusException(FakeStatus.OBJECT_NAME_NOT_FOUND)
            val prefix = if (path.isEmpty()) "" else "$path/"
            val subdirs = dirs.filter { it.startsWith(prefix) && it != path && '/' !in it.removePrefix(prefix) }
                .map { RemoteEntry(it.removePrefix(prefix), directory = true, hidden = false, sizeBytes = 0L) }
            val fs = files.entries.filter { it.key.startsWith(prefix) && '/' !in it.key.removePrefix(prefix) }
                .map { RemoteEntry(it.key.removePrefix(prefix), directory = false, hidden = false, sizeBytes = it.value.size()) }
            return (subdirs + fs).take(max)
        }

        override fun delete(path: String) {
            io("delete:$path", epoch)
            (files.remove(path) ?: throw FakeStatusException(FakeStatus.OBJECT_NAME_NOT_FOUND)).deleted = true
        }

        override fun freeBytes(): Long {
            io("freeBytes", epoch)
            return if (capacityLimit == Long.MAX_VALUE) Long.MAX_VALUE / 4 else (capacityLimit - usedBytes()).coerceAtLeast(0L)
        }

        override fun lastWriteMs(path: String): Long {
            io("lastWriteMs:$path", epoch)
            return (files[path] ?: throw FakeStatusException(FakeStatus.OBJECT_NAME_NOT_FOUND)).lastWriteMs
        }
    }

    inner class FakeRemoteFile(val path: String, val data: FakeFileData, val epoch: Long, val deleteOnClose: Boolean, val exclusive: Boolean = false) : RemoteFile {
        @Volatile var closed = false; private set

        override fun write(src: ByteArray, off: Int, len: Int, fileOffset: Long) {
            io("write:$path", epoch)
            if (closed) throw FakeStatusException(FakeStatus.FILE_CLOSED)
            if (data.deleted) throw FakeStatusException(FakeStatus.FILE_DELETED)
            if (writesDenied) throw FakeStatusException(FakeStatus.ACCESS_DENIED)
            val growth = (fileOffset + len - data.size()).coerceAtLeast(0L)
            if (capacityLimit != Long.MAX_VALUE && usedBytes() + growth > capacityLimit) throw FakeStatusException(FakeStatus.DISK_FULL)
            data.write(src, off, len, fileOffset)
            data.lastWriteMs = serverNowMs()
        }

        override fun read(dst: ByteArray, off: Int, len: Int, fileOffset: Long): Int {
            io("read:$path", epoch)
            if (closed) throw FakeStatusException(FakeStatus.FILE_CLOSED)
            if (data.deleted) throw FakeStatusException(FakeStatus.FILE_DELETED)
            return data.read(dst, off, len, fileOffset)
        }

        override fun closeAsync() {
            calls.incrementAndGet(); log += "close:$path"
            post {
                if (!closed) { data.openHandles.decrementAndGet(); if (exclusive) data.exclusive = false }
                closed = true
                if (deleteOnClose && files.remove(path, data)) data.deleted = true
            }
        }

        override fun close() = closeAsync()
    }

    companion object { const val WAIT_SLICE_MS = 5L }
}

/** One server file: a growable byte array, thread-safe. */
class FakeFileData {
    private var bytes = ByteArray(0)
    private var length = 0L
    @Volatile var deleted = false
    @Volatile var lastWriteMs = 0L
    /** (C-S7) Handles open on this file now, and whether one of them is exclusive (no sharing). */
    val openHandles = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var exclusive = false

    @Synchronized fun size(): Long = length

    @Synchronized fun write(src: ByteArray, off: Int, len: Int, fileOffset: Long) {
        val end = fileOffset + len
        require(end <= Int.MAX_VALUE) { "fake files are limited to 2 GiB" }
        if (end > bytes.size) bytes = bytes.copyOf(maxOf(end.toInt(), bytes.size * 2))
        System.arraycopy(src, off, bytes, fileOffset.toInt(), len)
        if (end > length) length = end
    }

    @Synchronized fun read(dst: ByteArray, off: Int, len: Int, fileOffset: Long): Int {
        if (fileOffset >= length) return -1
        val n = minOf(len.toLong(), length - fileOffset).toInt()
        System.arraycopy(bytes, fileOffset.toInt(), dst, off, n)
        return n
    }

    @Synchronized fun snapshot(): ByteArray = bytes.copyOf(length.toInt())
}
