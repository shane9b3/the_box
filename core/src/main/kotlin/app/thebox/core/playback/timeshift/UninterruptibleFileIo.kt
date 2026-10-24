// C-B3 (docs/review/2026-10-24-storage-locations-review-core.md): positional ring-file I/O that a thread interrupt
// cannot close.
package app.thebox.core.playback.timeshift

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * (C-B3) Positional reads and writes on a ring file that survive a thread interrupt. `FileChannel` is an
 * `InterruptibleChannel`: a Media3 load cancel that interrupts a loader inside `FileChannel.read` closes the channel **for
 * every thread**, and the flusher's next write then fails (`ClosedChannelException` → `DISK_ERROR`). An interrupt must
 * be a cancel, never a storage failure (A-B3).
 *
 * `RandomAccessFile`'s `read`/`write` are not interruptible. Positional I/O is seek + read/write, so the writer (one
 * flusher) and the readers (Media3 loaders) each get their own handle, each under its own lock: a read never moves the
 * writer's file pointer. A direct buffer goes through a per-thread heap array.
 *
 * Used by [FileRingStorage] (the JVM ring storage) and as `:player`'s `AndroidRingStorage` path when there is no native
 * `Os` (Robolectric). On a device `AndroidRingStorage` uses `Os.pread`/`Os.pwrite` on the fd, which are not interruptible
 * either.
 */
class UninterruptibleFileIo(file: File) : Closeable {
    /** The writer's handle; also the one for `setLength`/`length` and the fd. */
    val writer: RandomAccessFile = RandomAccessFile(file, "rw")
    private val reader: RandomAccessFile = try { RandomAccessFile(file, "r") } catch (e: IOException) { writer.close(); throw e }
    private val writeLock = Any()
    private val readLock = Any()
    private val scratch = ThreadLocal<ByteArray>()

    /** Writes all of [src] at [filePosition]; advances its position. */
    @Throws(IOException::class)
    fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        val len = src.remaining()
        if (len == 0) return 0
        if (src.hasArray()) {
            synchronized(writeLock) { writer.seek(filePosition); writer.write(src.array(), src.arrayOffset() + src.position(), len) }
            (src as Buffer).position(src.position() + len)
            return len
        }
        val tmp = scratchFor(len)
        var done = 0
        while (done < len) {
            val n = minOf(tmp.size, len - done)
            src.get(tmp, 0, n)
            synchronized(writeLock) { writer.seek(filePosition + done); writer.write(tmp, 0, n) }
            done += n
        }
        return len
    }

    /** Reads up to `dst.remaining()` bytes at [filePosition]; −1 at EOF. Advances [dst]'s position by the count. */
    @Throws(IOException::class)
    fun pread(dst: ByteBuffer, filePosition: Long): Int {
        val len = dst.remaining()
        if (len == 0) return 0
        if (dst.hasArray()) {
            val n = synchronized(readLock) { reader.seek(filePosition); reader.read(dst.array(), dst.arrayOffset() + dst.position(), len) }
            if (n > 0) (dst as Buffer).position(dst.position() + n)
            return n
        }
        val tmp = scratchFor(len)
        val n = synchronized(readLock) { reader.seek(filePosition); reader.read(tmp, 0, minOf(tmp.size, len)) }
        if (n > 0) dst.put(tmp, 0, n)
        return n
    }

    override fun close() {
        try { reader.close() } catch (_: IOException) { }
        try { writer.close() } catch (_: IOException) { }
    }

    private fun scratchFor(len: Int): ByteArray =
        scratch.get()?.takeIf { it.size >= minOf(len, TimeshiftConstants.RING_BLOCK_BYTES) }
            ?: ByteArray(minOf(len, TimeshiftConstants.RING_BLOCK_BYTES)).also { scratch.set(it) }
}
