// Contract (M6 time-shift commit 0): docs/design/m6-timeshift.md §16.1. Verbatim; changes go through the lead.
// Amended by the lead reconciliation of WP-A/WP-B (2026-10-19, design "Lead reconciliation — WP-A/WP-B"): R1 index
// entry snapshots, R2 SegmentStormException, R3 RingWriter.reconnectAllowed, R4 daily counter, R5 window length,
// R11 allocate units, R12 `var mode`, R13 restart after a self-stop. Fix pass — playback (2026-10-19): B1 reconnect cause.
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import java.io.IOException
import java.nio.ByteBuffer

/** Why a new segment starts in the ring (§3.5). A reader never crosses a segment boundary (§5.3). */
enum class SegmentReason { FIRST, PCR_JUMP, INLINE_PCR_JUMP, PSI_CHANGE, WRITER_RESTART, GAP }

/** Why saving stopped (§4.4). Carried by [RingEndException] and `LocalBufferState.stop`. */
enum class RecordStop { FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST, PREEMPTED, UPSTREAM_FAILED, DISK_ERROR, STORAGE_EVICTED, DISK_TOO_SLOW, SESSION_END }

/** Why a live cycle is not buffered (§2 "When the buffer is unavailable", §7.1). */
enum class BufferOff { SETTING_OFF, NOT_TS, NO_SPACE, STORAGE_ERROR, ARCHIVE }

/**
 * PERSIST writes every block to the file; PAUSE_ONLY keeps only the RAM window at live and persists from the oldest
 * pool block on pause (§4.5). Not `app.thebox.core.playback.BufferMode` (the network buffer profile).
 */
enum class BufferMode { PERSIST, PAUSE_ONLY }

/**
 * (Fix pass — playback, 2026-10-19, B1) Why the recorder's `LiveTsDataSource` is about to reconnect, as given to
 * [RingWriter.reconnectAllowed]. Only [SERVER_CLOSED], [RESET] (once per loss) and [REFUSED] (every attempt) count
 * towards the paused/behind reconnect cap; [WATCHDOG] (our own no-bytes cancel) and [NETWORK] (connect errors, read
 * timeouts, 5xx/408/429, and 503, which is more often overload than a slot) never do.
 */
enum class ReconnectCause { WATCHDOG, SERVER_CLOSED, RESET, REFUSED, NETWORK }

/** Answer of [TsRing.claim] (§3.2: FULL is decided in claim, before the upstream read). */
enum class ClaimResult { OK, FULL, DISK_STALLED, STOPPED, STALE_GENERATION }

/** Index entry flags (§3.3), stored as `flags << 24 | segment`. */
object RingFlags { const val RAP = 1; const val SPLICE = 2; const val ARRIVAL_CLOCK = 4; const val SEGMENT_START = 8 }

/** Pin owners (§3.2): `tail` never passes the lowest pin. Readers take ids from [FIRST_READER] up. */
object PinOwner { const val PLAYHEAD = 0; const val RECORDER_V2 = 1; const val FIRST_READER = 8 }

/** A writable window in the current direct-backed RAM block (whole packets). Owned by the writer, reused. */
class RingSlot { var array: ByteArray = EMPTY; var offset = 0; var length = 0; var generation = 0L; private companion object { val EMPTY = ByteArray(0) } }

/**
 * File I/O behind the ring (§3.1). WP-A uses it; WP-B implements it over `Os`/FileChannel and StatFs. Positional
 * calls are thread-safe against each other (pwrite on the flusher, pread on readers). Never called on main.
 */
interface RingStorage : AutoCloseable {
    val capacityBytes: Long
    /**
     * Extends the allocation to at least [upToBytes] (fallocate, else sparse); returns the allocated size. Both are
     * **file** bytes, the RING_HEADER_BYTES header included (lead reconciliation R11); [capacityBytes] is data bytes.
     */
    @Throws(IOException::class) fun allocate(upToBytes: Long): Long
    @Throws(IOException::class) fun pwrite(src: ByteBuffer, filePosition: Long): Int
    @Throws(IOException::class) fun pread(dst: ByteBuffer, filePosition: Long): Int
    /** True once the file was unlinked under us (`st_nlink == 0`, A-S7). */
    fun isUnlinked(): Boolean
    fun availableBytes(): Long
    @Throws(IOException::class) fun writeHeader(generation: Long, capacity: Long)
    /** Closes the fd first, then deletes (space is freed on close). */
    fun delete()
}

/**
 * The time-shift ring (§3). Logical offsets only grow; bytes in [tail, head) are readable (§3.2, PAUSE_ONLY §4.5).
 * One writer thread per generation calls claim/commit/startSegment/stopRecording; any thread may pin, open readers or
 * read the index. commit() never blocks on disk; claim() may wait (FULL while playing, pool exhausted).
 * Every time is MonoClock ms.
 */
interface TsRing : AutoCloseable {
    val capacityBytes: Long
    val tail: Long
    val head: Long
    val generation: Long
    val saving: Boolean
    val stop: RecordStop?
    /** Any thread (lead reconciliation R12: one `var`, no separate `setMode`, so an implementer may use `var`). */
    var mode: BufferMode
    val index: RingIndexView
    /** Bytes written to the file over the ring's life (the daily write counter's source, §8.4; lead reconciliation R4). */
    val persistedBytes: Long
    /**
     * Called on the flusher thread with the bytes persisted since the last call, every DAILY_COUNTER_FLUSH_BYTES (§8.4).
     * Must not block. Optional: a consumer that counts at the [RingStorage] level does not set it (R4).
     */
    var persistListener: ((Long) -> Unit)?

    fun claim(minBytes: Int, timeoutMs: Long, paused: Boolean, slot: RingSlot): ClaimResult
    /** Drops the commit when [slot].generation != [generation] (A-S1). */
    fun commit(slot: RingSlot, length: Int, arrivalMonoMs: Long)
    fun startSegment(reason: SegmentReason, gapMs: Long)
    fun stopRecording(reason: RecordStop)
    fun setPin(owner: Int, offset: Long)
    fun clearPin(owner: Int)
    fun openReader(fromOffset: Long, generation: Long): RingReader
    /** IO thread only, after the previous generation's pump exited (bounded join). New generation, tail = head = 0. */
    fun reset(): Long
}

/**
 * Constructor shape WP-B calls (commit 0 stubs it). [lengthMs] is the rewind window (§3.2, lead reconciliation R5):
 * on each commit `tail` also advances to the first RAP whose PTS mono is ≥ newest − [lengthMs], never past a pin.
 * 0 = no time limit (the window is bounded by capacity only).
 */
fun interface TsRingFactory {
    fun create(storage: RingStorage, clock: MonoClock, ramBlocks: Int, indexEntries: Int, lengthMs: Long): TsRing
}

/** One reader cursor over the ring (§5.1); pins its cursor and is fenced by [generation]. */
interface RingReader : AutoCloseable {
    val cursor: Long
    val segment: Int
    val generation: Long
    val atHead: Boolean
    /** n > 0 whole packets; blocks at head; throws the Ring* exceptions; never returns 0 or −1. */
    @Throws(IOException::class) fun read(dst: ByteArray, off: Int, len: Int): Int
    /** Any thread: makes a blocked read throw InterruptedIOException. */
    fun cancel()
}

/** Every exception a ring reader surfaces (§5.1). Fatal under `BoxLoadErrorHandlingPolicy` (no Media3 retry). */
open class RingException(message: String, cause: Throwable? = null) : IOException(message, cause)
/** The reader reached a segment boundary (§5.3); the controller re-prepares at [nextRapOffset] or hands off (§9.1). */
class RingBoundaryException(val nextRapOffset: Long, val reason: SegmentReason, val gapMs: Long, val gapStartMonoMs: Long) : RingException("ring segment boundary")
/** The reader reached `head` after saving stopped, or its generation ended ([RecordStop.SESSION_END]). */
class RingEndException(val stop: RecordStop, cause: IOException? = null) : RingException("ring ended: $stop", cause)
/** The reader's cursor fell below `tail` (§4.5: an error, not routine, once PAUSE_ONLY clamps). */
class RingPositionLostException(val requested: Long, val tail: Long) : RingException("ring position lost")
/** A `pread` from the ring file failed (§4.6). */
class RingIoException(cause: IOException) : RingException("ring read failed", cause)

/**
 * Any thread, allocation-free, seqlock-consistent (§3.3). Slots index circular arrays; −1 = none.
 *
 * **Slot reuse (lead reconciliation R1).** A slot from [floorRap]/[ceilRap]/[newestRap]/[oldestRap] followed by
 * [offsetAt]/[ptsMonoAt] is two separate consistent reads: after a full index lap the slot can hold a newer entry.
 * Off the writer thread, use the `*Entry` forms, which answer the slot **and** its offset and PTS mono from one
 * consistent read: `out[0]` = offset, `out[1]` = PTS mono (`out.size ≥ 2`; undefined when the answer is −1).
 */
interface RingIndexView {
    val size: Int
    fun floorRap(monoMs: Long): Int
    fun ceilRap(monoMs: Long): Int
    fun newestRap(): Int
    fun oldestRap(): Int
    /** [floorRap] with its entry in one read (R1). */
    fun floorRapEntry(monoMs: Long, out: LongArray): Int
    /** [ceilRap] with its entry in one read (R1). */
    fun ceilRapEntry(monoMs: Long, out: LongArray): Int
    /** [newestRap] with its entry in one read (R1). */
    fun newestRapEntry(out: LongArray): Int
    /** [oldestRap] with its entry in one read (R1). */
    fun oldestRapEntry(out: LongArray): Int
    fun offsetAt(slot: Int): Long
    /** The RAP's PTS mapped to mono (the playhead origin, A-S4). */
    fun ptsMonoAt(slot: Int): Long
    fun flagsAt(slot: Int): Int
    fun segmentAt(slot: Int): Int
    fun newestPtsMono(): Long
    fun monoOfOffset(offset: Long): Long
    fun segmentReason(segment: Int): SegmentReason
    fun segmentGapMs(segment: Int): Long
}

/** Upstream as the writer sees it; :player implements it over LiveTsDataSource (with onPcrJump). */
interface TsUpstream {
    @Throws(IOException::class) fun open()
    @Throws(IOException::class) fun read(dst: ByteArray, off: Int, len: Int): Int
    fun close()
    fun terminate()
}

/**
 * Start/end pairs with **content** equality (reviewed 2026-10-19, A-C1): used in data classes that are StateFlow and
 * Compose state (ArchiveStatus, ScrubState), where a LongArray's identity equality would make every snapshot unequal.
 */
class TimeRanges(private val pairs: LongArray = LongArray(0)) {
    val count: Int get() = pairs.size / 2
    fun startAt(i: Int): Long = pairs[2 * i]
    fun endAt(i: Int): Long = pairs[2 * i + 1]
    override fun equals(other: Any?): Boolean = other is TimeRanges && pairs.contentEquals(other.pairs)
    override fun hashCode(): Int = pairs.contentHashCode()
    companion object { val EMPTY = TimeRanges() }
}

/**
 * Called on the **pump thread** (reviewed 2026-10-19, A-C1). The controller's implementation must only post to main;
 * it never blocks and never touches the player from here. Before onFirstBytes, onFailure is the only failure route (A-C2).
 */
interface RingWriterListener {
    fun onFirstBytes()
    fun onFailure(e: IOException)
    fun onStopped(reason: RecordStop)
    fun onSegment(reason: SegmentReason, gapMs: Long)
}

/**
 * [SEGMENT_STORM_COUNT][TimeshiftConstants.SEGMENT_STORM_COUNT] segments within
 * [SEGMENT_STORM_WINDOW_MS][TimeshiftConstants.SEGMENT_STORM_WINDOW_MS] (§3.5), reported once per storm through
 * [RingWriterListener.onFailure] (lead reconciliation R2). The classifier maps it to `FailureSignal.PcrDiscontinuity`
 * on the writer-scoped ladder (§7.4: writer `ReResolve`, then M2 L25 on a repeat). Not a `LiveStreamException`.
 */
class SegmentStormException(val segments: Int, val windowMs: Long) :
    IOException("$segments ring segments within $windowMs ms")

/** One long-lived pump thread per session (A-S1). Commands are posted; restart terminates the current upstream first. */
interface RingWriter : AutoCloseable {
    fun start(upstream: TsUpstream, listener: RingWriterListener)
    /**
     * Opens [upstream] on the pump after the current one was terminated and closed, and starts a [reason] segment whose
     * gap is the time since the last commit (or since saving stopped). Valid after a failure (the pump idles until a
     * command), after a controller [stop] other than SESSION_END, and after a **self-stop** (FULL_WHILE_PAUSED,
     * HOLD_LIMIT, SLOT_LOST; lead reconciliation R13): saving resumes (`TsRing.saving` true, `stop` null once bytes
     * arrive), the reconnect-cap history is cleared, and [RingWriterListener.onFirstBytes] is not repeated. A disk stop
     * stays (the ring is RAM-only for the generation). Call [onResume] first: a restart while still paused into a full
     * ring stops again with FULL_WHILE_PAUSED. Ignored after [close].
     */
    fun restart(upstream: TsUpstream, reason: SegmentReason)
    fun stop(reason: RecordStop)
    fun onPause(nowMonoMs: Long, holdLimitMs: Long?)
    fun onResume()
    /** Joins the pump thread for at most [timeoutMs] (IO only). */
    fun awaitExit(timeoutMs: Long): Boolean
    /**
     * The upstream's `LiveTsDataSource.reconnectAllowed` hook (A-C4, lead reconciliation R3), on the pump thread before
     * each in-source reconnect attempt. [firstOfLoss] is true on the attempt that opens a loss (the first of a reconnect
     * burst). At the head always true, and the history is cleared (nothing at live is carried into a pause). While
     * paused or behind live, only server-initiated losses count (fix pass B1): [ReconnectCause.SERVER_CLOSED] and
     * [ReconnectCause.RESET] once per loss, [ReconnectCause.REFUSED] on every attempt; more than PAUSED_RECONNECTS_MAX
     * within PAUSED_RECONNECT_WINDOW_MS answer false, and the refused read then stops the writer with SLOT_LOST
     * ([RingWriterListener.onStopped]), never reaching the ladder. The history is also cleared by [onPause],
     * [onResume] and [restart].
     */
    fun reconnectAllowed(cause: ReconnectCause, firstOfLoss: Boolean): Boolean
}

/** Constructor shape of the writer (§4.1); [tag] names the `box-ts-pump` thread. */
fun interface RingWriterFactory {
    fun create(ring: TsRing, indexer: TsIndexer, clock: MonoClock, tag: String): RingWriter
}

/** Random-access and jump detection on the writer thread (§3.4). Allocation-free per packet. */
interface TsIndexer {
    fun scan(buf: ByteArray, off: Int, len: Int, logicalOffset: Long, arrivalMonoMs: Long, sink: IndexSink)
    fun onSplice()
    fun reset()
}

/** What [TsIndexer.scan] reports; the ring's index and segment table implement it (§3.3, §3.5). */
interface IndexSink {
    fun onRap(offset: Long, pts90k: Long, pcr90k: Long, flags: Int)
    fun onAnchor(offset: Long, pcr90k: Long, flags: Int)
    fun onBoundary(offset: Long, reason: SegmentReason)
    fun onPsi(packets: ByteArray, count: Int)
}

/** Pure sizing and policy (§8). */
interface TimeshiftPolicy {
    fun lengthMs(setting: app.thebox.core.settings.TimeshiftLength, totalStorageBytes: Long): Long?
    fun capacityBytes(lengthMs: Long, bytesPerSec: Long, availableBytes: Long, totalBytes: Long, currentFileBytes: Long): Long
    fun holdLimitMs(setting: app.thebox.core.settings.TimeshiftHold): Long?
    fun dailyLimitBytes(setting: app.thebox.core.settings.StorageProtection, totalStorageBytes: Long): Long?
    fun mode(setting: app.thebox.core.settings.TimeshiftLength, writtenTodayBytes: Long, dailyLimitBytes: Long?, needsPersist: Boolean): BufferMode
    /** "Can stay paused N more min" (§11.5). */
    fun pauseRemainingMs(holdRemainingMs: Long?, freeCapacityBytes: Long, bytesPerSec: Long): Long
}
