// M6 time-shift, WP-B (docs/design/m6-timeshift.md §3.1, §4.4, §8, A-S7, A-S8).
package app.thebox.player.timeshift

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.playback.timeshift.TimeshiftConstants
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import app.thebox.core.playback.timeshift.RingHeaderCodec
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The two POSIX calls the ring file needs beyond positional I/O, behind a seam so the fallbacks are testable (Robolectric
 * has no native `Os`). [AndroidRingPosix] is the device implementation.
 */
internal interface RingPosix {
    /**
     * Reserves `[offset, offset + len)` (extending the file). true when allocated; false when the filesystem cannot
     * (EOPNOTSUPP/ENOSYS, or no native `Os`): the caller then extends the file sparsely. Throws on ENOSPC.
     */
    @Throws(IOException::class)
    fun fallocate(fd: FileDescriptor, offset: Long, len: Long): Boolean

    /** `fstat(fd).st_nlink`, or −1 when it cannot be read (the caller then re-stats the path). */
    fun nlink(fd: FileDescriptor): Long
}

/** `Os.posix_fallocate` and `Os.fstat` (API 21) with the fallbacks of §3.1 and A-S7. */
internal object AndroidRingPosix : RingPosix {
    override fun fallocate(fd: FileDescriptor, offset: Long, len: Long): Boolean = try {
        Os.posix_fallocate(fd, offset, len)
        true
    } catch (e: ErrnoException) {
        // ENOSPC is real; EOPNOTSUPP/ENOSYS (some FUSE/emulated filesystems) and anything else take the sparse path,
        // and a missing extent then shows up as ENOSPC at write time (§3.1).
        val enospc = OsConstants.ENOSPC
        if (enospc != 0 && e.errno == enospc) throw IOException("no space for the time-shift ring", e)
        false
    } catch (e: LinkageError) {
        false
    } catch (e: RuntimeException) {
        false
    }

    override fun nlink(fd: FileDescriptor): Long = try {
        Os.fstat(fd).st_nlink
    } catch (t: Throwable) {
        -1L
    }
}

/**
 * [RingStorage] over one preallocated file `cacheDir/timeshift/ring-<pid>.bin` (§3.1, §8.1).
 *
 * Layout: a [TimeshiftConstants.RING_HEADER_BYTES] header (`RingHeaderCodec`, shared with WP-A), then the data region;
 * logical offset `L` lives at file position `HEADER + (L mod capacity)` (the ring computes the position; [pwrite]/[pread]
 * take **file** positions). [capacityBytes] is the data region's size (a multiple of the block size, header excluded).
 * [allocate] takes and returns **file** bytes (header included), as `TsRingImpl` calls it: it grows the file extent by
 * extent, never past `HEADER + capacity`.
 *
 * - Pre-allocation: `Os.posix_fallocate`, else a sparse `setLength` (ENOSPC then surfaces at write) (§3.1).
 * - Positional writes from the block's **direct** `ByteBuffer` through `FileChannel` (`pwrite`, no user-space copy, A-S8)
 *   and positional reads (`pread`) are thread-safe against each other (flusher vs readers).
 * - [isUnlinked]: `fstat(fd).st_nlink == 0` (the OS reclaimed the cache under us, A-S7); without a native `fstat`, a
 *   re-stat of the path.
 * - No `fsync`/`force` (§3.1 "Durability").
 * - [delete] closes the fd first, then unlinks, so the space is really freed (§7.5, §8.3).
 *
 * [onWritten] sees every byte written (the daily flash-wear counter, §8.4), on the flusher thread.
 *
 * (Storage locations WP-S2, docs/design/m6-storage-locations.md §3.2, §3.4, §4.2)
 * - [preallocate] false (USB): [allocate] records and returns the bound with no I/O; the file grows only by sequential
 *   writes (FAT zero-fills a grown file, so no holes and no zero-fill pass), ENOSPC surfaces at write → DISK_ERROR.
 * - [blocking] true (USB): the ring gets its own `box-ts-flush-ext` flusher (A-B2).
 * - [closer] non-null (USB): [close] and [delete] mark the storage closed at once and run the fd close (then the
 *   unlink) on that executor, never on the caller's thread: a close racing a `pread` stuck on a vanishing device can
 *   block in the kernel (§3.2 "Removal").
 */
internal class AndroidRingStorage(
    val file: File,
    capacityBytes: Long,
    private val posix: RingPosix = AndroidRingPosix,
    private val space: () -> Long = { 0L },
    private val onWritten: (Long) -> Unit = {},
    private val createdMonoMs: Long = 0L,
    private val pid: Int = android.os.Process.myPid(),
    private val preallocate: Boolean = true,
    override val blocking: Boolean = false,
    private val closer: ((Runnable) -> Unit)? = null,
) : RingStorage {
    private val raf: RandomAccessFile
    private val channel: FileChannel
    @Volatile private var closed = false
    @Volatile private var capacity: Long = capacityBytes
    /** File bytes allocated (header included). */
    @Volatile private var allocated: Long

    /** Writes that took the sparse path (diagnostics and tests). */
    @Volatile var sparse = false
        private set

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        channel = raf.channel
        allocated = raf.length().coerceAtMost(HEADER + capacity)
    }

    override val capacityBytes: Long get() = capacity

    /** Shrinks the target (ENOSPC, the free-space watch of §3.1); never grows it. */
    fun shrinkTo(bytes: Long) {
        if (bytes < capacity) capacity = bytes.coerceAtLeast(0L)
    }

    override fun allocate(upToBytes: Long): Long {
        ensureOpen()
        val fileLength = upToBytes.coerceAtMost(HEADER + capacity)
        if (fileLength <= allocated) return allocated
        if (!preallocate) {
            // §3.2: no preallocation (FAT zero-fills); sequential writes extend the file, readers never pass `flushed`.
            allocated = fileLength
            return allocated
        }
        val ok = posix.fallocate(raf.fd, 0L, fileLength)
        if (!ok) {
            if (!sparse) BoxLog.i(TAG) { "fallocate unavailable: sparse ring file" }
            sparse = true
            if (raf.length() < fileLength) raf.setLength(fileLength)
        }
        allocated = fileLength
        return allocated
    }

    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        ensureOpen()
        var written = 0
        var pos = filePosition
        while (src.hasRemaining()) {
            val n = channel.write(src, pos)
            if (n <= 0) break
            written += n
            pos += n
        }
        if (written > 0) onWritten(written.toLong())
        return written
    }

    override fun pread(dst: ByteBuffer, filePosition: Long): Int {
        ensureOpen()
        var read = 0
        var pos = filePosition
        while (dst.hasRemaining()) {
            val n = channel.read(dst, pos)
            if (n < 0) break
            if (n == 0) continue
            read += n
            pos += n
        }
        return read
    }

    override fun isUnlinked(): Boolean {
        if (closed) return true
        val links = try {
            posix.nlink(raf.fd)
        } catch (e: IOException) {
            -1L
        }
        return if (links >= 0) links == 0L else !file.exists()
    }

    override fun availableBytes(): Long = try {
        space()
    } catch (e: Exception) {
        0L
    }

    override fun writeHeader(generation: Long, capacity: Long) {
        ensureOpen()
        val b = RingHeaderCodec.encode(generation, capacity, createdMonoMs, pid)
        var pos = 0L
        while (b.hasRemaining()) pos += channel.write(b, pos)
    }

    /** `fstat(fd).st_nlink` now (−1 when unreadable): `VolumeStorage` checks it once after create (A-S8). */
    fun nlinkNow(): Long = if (closed) -1L else try { posix.nlink(raf.fd) } catch (e: IOException) { -1L }

    override fun delete() {
        val post = closer
        if (post != null) {
            if (!markClosed()) { post { unlink() }; return }
            post { closeFd(); unlink() }
            return
        }
        close()
        unlink()
    }

    private fun unlink() {
        if (file.exists() && !file.delete()) BoxLog.w(TAG, "ring file not deleted")
    }

    override fun close() {
        if (!markClosed()) return
        val post = closer
        if (post != null) post { closeFd() } else closeFd()
    }

    /** True when this call closed the storage (it was open). */
    private fun markClosed(): Boolean = synchronized(this) {
        if (closed) false else { closed = true; true }
    }

    private fun closeFd() {
        try {
            channel.close()
        } catch (e: IOException) {
            BoxLog.w(TAG, "ring channel close failed", e)
        }
        try {
            raf.close()
        } catch (e: IOException) {
            BoxLog.w(TAG, "ring file close failed", e)
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("ring storage closed")
    }

    companion object {
        private const val TAG = "RingStorage"
        private const val HEADER = TimeshiftConstants.RING_HEADER_BYTES.toLong()
    }
}
