// M6 time-shift, WP-B (docs/design/m6-timeshift.md §3.1, §8.1, §8.3, §8.4, A-S7).
package app.thebox.player.timeshift

import android.content.Context
import android.os.StatFs
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the ring lives and how much it may write (§8):
 *
 * - **Location** `cacheDir/timeshift/ring-<pid>.bin` (§8.1): internal, app-private; the OS may reclaim it (A-S7).
 * - **Sweep** (§8.3, A-S7): once per process, on a background thread, every file in the directory **not** named
 *   `ring-<this pid>.bin` is deleted (a previous process's ring). [awaitSweep] (called by `prepare()`) waits for it, so a
 *   launch-time auto-tune never loses its own file to the sweep. [startSweep] is called from `Application.onCreate`
 *   through [TimeshiftStartup], so the sweep is normally done before the first tune.
 * - **Space**: `StatFs` on the directory (total, available).
 * - **Daily write counter** (§8.4): bytes saved today on this device, in `noBackupFilesDir/timeshift-writes`
 *   (`<yyyy-mm-dd> <bytes>`), reset at **local** midnight, persisted every [TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES].
 *   [addWritten] updates memory on the flusher thread; a coalesced background drain persists snapshots outside its lock.
 */
@Singleton
internal class TimeshiftStorageImpl(
    val dir: File,
    private val counterFile: File,
    private val pid: Int,
    private val clock: MonoClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val posix: RingPosix = AndroidRingPosix,
    private val statFs: (File) -> Space = ::statFsSpace,
    private val counterIo: Executor = COUNTER_IO,
    private val writeCounter: ((String) -> Unit)? = null,
) {
    @Inject constructor(@ApplicationContext context: Context, clock: MonoClock) : this(
        dir = File(context.cacheDir, DIR_NAME),
        counterFile = File(context.noBackupFilesDir, COUNTER_NAME),
        pid = android.os.Process.myPid(),
        clock = clock,
    )

    /** Total and available bytes of the filesystem holding [dir]. */
    class Space(val totalBytes: Long, val availableBytes: Long)

    /** This process's ring file. */
    val ringFile: File get() = File(dir, "$RING_PREFIX$pid$RING_SUFFIX")

    private val sweepStarted = AtomicBoolean(false)
    private val sweepDone = CompletableDeferred<Unit>()

    /** Starts the start-up sweep once (background thread); later calls do nothing. */
    fun startSweep() {
        if (!sweepStarted.compareAndSet(false, true)) return
        val t = Thread({
            try {
                sweepNow()
            } catch (t: Throwable) {
                BoxLog.w(TAG, "time-shift sweep failed", t)
            } finally {
                sweepDone.complete(Unit)
            }
        }, "box-ts-sweep")
        t.isDaemon = true
        t.start()
    }

    /** Suspends until the sweep finished (starting it when nobody did). */
    suspend fun awaitSweep() {
        startSweep()
        sweepDone.await()
    }

    /** Deletes every file in [dir] except this process's ring; returns how many were deleted. */
    internal fun sweepNow(): Int {
        val keep = ringFile.name
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            if (f.name == keep) continue
            if (f.isDirectory) {
                if (f.deleteRecursively()) deleted++
            } else if (f.delete()) {
                deleted++
            }
        }
        if (deleted > 0) BoxLog.i(TAG) { "swept $deleted stale time-shift file(s)" }
        return deleted
    }

    fun totalBytes(): Long = space().totalBytes

    fun availableBytes(): Long = space().availableBytes

    /** The size of this process's ring file now (0 when absent): counted as reusable space by the sizing rule (§8.2). */
    fun currentFileBytes(): Long = ringFile.takeIf { it.exists() }?.length() ?: 0L

    private fun space(): Space {
        val probe = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir
        return try {
            statFs(probe)
        } catch (e: Exception) {
            BoxLog.w(TAG, "StatFs failed: ${e.javaClass.simpleName}")
            Space(0L, 0L)
        }
    }

    /** Opens (creating) this process's ring file with a data capacity of [capacityBytes]. IO thread. */
    @Throws(IOException::class)
    fun openStorage(capacityBytes: Long): AndroidRingStorage {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw IOException("time-shift directory not created")
        return AndroidRingStorage(
            file = ringFile,
            capacityBytes = capacityBytes,
            posix = posix,
            space = { availableBytes() },
            onWritten = ::addWritten,
            createdMonoMs = clock.nowMs(),
            pid = pid,
        )
    }

    /** Deletes this process's ring file (after its storage was closed). */
    fun deleteRingFile() {
        val f = ringFile
        if (f.exists() && !f.delete()) BoxLog.w(TAG, "ring file not deleted")
    }

    // ------------------------------------------------------------------ daily write counter (§8.4)

    private val counterLock = Any()
    private var counterDay: LocalDate? = null
    private var counterBytes = 0L
    private var unflushedBytes = 0L
    private var counterLoaded = false
    private val flushRequested = AtomicBoolean(false)
    private val flushScheduled = AtomicBoolean(false)
    /** (S6) The local day's bounds in wall ms; [addWritten] compares two longs and recomputes only outside them. */
    private var dayStartWallMs = Long.MAX_VALUE
    private var nextMidnightWallMs = Long.MIN_VALUE
    private var nextWallCheckMonoMs = Long.MIN_VALUE

    /** Bytes saved since local midnight. */
    fun writtenToday(): Long = synchronized(counterLock) {
        rollDay()
        counterBytes
    }

    /**
     * Counts [bytes] written to the ring; requests persistence every 64 MiB. Any thread (the flusher, about 15 times a second at
     * 1 MB/s). (S6) Allocation-free in steady state: the date is recomputed only when the wall clock leaves the cached day.
     */
    fun addWritten(bytes: Long) {
        if (bytes <= 0) return
        synchronized(counterLock) {
            // The wall clock (a boxed `() -> Long`) is read at most once a minute; the mono check is a primitive compare.
            val m = clock.nowMs()
            if (!counterLoaded || m >= nextWallCheckMonoMs) {
                nextWallCheckMonoMs = m + WALL_RECHECK_MS
                val w = wallClock()
                if (!counterLoaded || w < dayStartWallMs || w >= nextMidnightWallMs) rollDay()
            }
            counterBytes += bytes
            unflushedBytes += bytes
            if (unflushedBytes >= TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES) flushCounter()
        }
    }

    /** Requests a coalesced background flush. Main never takes the accounting lock or waits for filesystem I/O. */
    fun flushCounter() {
        flushRequested.set(true)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        counterIo.execute {
            try {
                while (flushRequested.getAndSet(false)) persistCounter()
            } finally {
                flushScheduled.set(false)
                // A request between the last check and clearing scheduled still needs a worker.
                if (flushRequested.get()) scheduleFlush()
            }
        }
    }

    /** Loads the stored counter once and resets it when the local date changed (midnight, or a stored older day). */
    private fun rollDay() {
        val wall = wallClock()
        val z = zone()
        val now = Instant.ofEpochMilli(wall).atZone(z).toLocalDate()
        dayStartWallMs = now.atStartOfDay(z).toInstant().toEpochMilli()
        nextMidnightWallMs = now.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        if (!counterLoaded) {
            counterLoaded = true
            val (day, bytes) = readCounter()
            if (day == now) counterBytes = bytes
            counterDay = now
            return
        }
        if (counterDay != now) {
            counterDay = now
            counterBytes = 0L
            unflushedBytes = 1L // dirty sentinel: persist the new day even before its first byte
            flushCounter()
        }
    }

    private fun readCounter(): Pair<LocalDate?, Long> = try {
        if (!counterFile.exists()) {
            null to 0L
        } else {
            val parts = counterFile.readText().trim().split(' ')
            LocalDate.parse(parts[0]) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
    } catch (e: Exception) {
        BoxLog.w(TAG, "daily write counter unreadable: ${e.javaClass.simpleName}")
        null to 0L
    }

    private data class CounterSnapshot(val day: LocalDate, val bytes: Long, val dirty: Long)

    /** Only the serialized drain writes. Snapshot under the short accounting lock, then release it before disk I/O. */
    private fun persistCounter() {
        val snapshot = synchronized(counterLock) {
            val day = counterDay ?: return
            if (unflushedBytes <= 0) return
            CounterSnapshot(day, counterBytes, unflushedBytes).also { unflushedBytes = 0L }
        }
        try {
            val text = "${snapshot.day} ${snapshot.bytes}\n"
            if (writeCounter != null) writeCounter.invoke(text) else {
                counterFile.parentFile?.mkdirs()
                val next = File(counterFile.path + ".new")
                next.writeText(text)
                if (!next.renameTo(counterFile)) throw IOException("daily counter replacement failed")
            }
        } catch (e: IOException) {
            synchronized(counterLock) {
                // Retain dirty accounting for a later flush, without reintroducing yesterday's bytes after midnight.
                if (counterDay == snapshot.day) unflushedBytes += snapshot.dirty
            }
            BoxLog.w(TAG, "daily write counter not saved: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private val COUNTER_IO = Executors.newSingleThreadExecutor { task -> Thread(task, "box-ts-counter").apply { isDaemon = true } }
        private const val TAG = "TimeshiftStorage"
        const val DIR_NAME = "timeshift"
        const val COUNTER_NAME = "timeshift-writes"
        const val RING_PREFIX = "ring-"
        const val RING_SUFFIX = ".bin"
        /** (S6) How often [addWritten] looks at the wall clock for a new local day (midnight is seen within this). */
        const val WALL_RECHECK_MS = 60_000L

        fun statFsSpace(dir: File): Space {
            val s = StatFs(dir.path)
            return Space(s.totalBytes, s.availableBytes)
        }
    }
}
