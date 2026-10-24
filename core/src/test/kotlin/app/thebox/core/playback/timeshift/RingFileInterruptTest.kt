// C-B3 (docs/review/2026-10-24-storage-locations-review-core.md): an interrupted reader never closes the ring file.
package app.thebox.core.playback.timeshift

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-B3: Media3 cancels a load by interrupting the loader thread. With `FileChannel` an interrupt inside `read` closed the
 * channel, the ring's only fd, and the flusher's next `pwrite` failed (`DISK_ERROR`). [FileRingStorage] (the JVM twin of
 * `AndroidRingStorage`) and [UninterruptibleFileIo] must keep the file usable for every thread.
 */
class RingFileInterruptTest {
    private val cap = 8L * TimeshiftConstants.RING_BLOCK_BYTES

    private fun tmp(): File = File.createTempFile("ring", ".bin").apply { deleteOnExit() }

    /** The review probe: an interrupt that arrives while the reader is in pread, then the flusher's next write. */
    @Test
    fun anInterruptedReaderDoesNotCloseTheRingFile() {
        val st = FileRingStorage(tmp(), cap)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + cap)
        st.pwrite(ByteBuffer.wrap(ByteArray(4096)), 0L)
        val t = Thread {
            Thread.currentThread().interrupt() // the cancel arrives while the reader is in pread
            try { st.pread(ByteBuffer.allocate(4096), 0L) } catch (_: IOException) { }
        }
        t.start(); t.join()
        st.pwrite(ByteBuffer.wrap(ByteArray(4096)), 4096L) // the flusher's next write must still work
        st.close()
    }

    /** A reader interrupted over and over mid-read (direct and heap buffers) while the writer keeps writing. */
    @Test
    fun aReaderInterruptedMidReadLeavesTheWriterWriting() {
        val f = tmp()
        val st = FileRingStorage(f, cap)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + cap)
        val block = TimeshiftConstants.RING_BLOCK_BYTES
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger()
        val readerError = AtomicReference<Throwable?>()
        val reader = Thread {
            val direct = ByteBuffer.allocateDirect(block)
            val heap = ByteBuffer.allocate(block)
            var i = 0
            while (!stop.get()) {
                val dst = if (i++ % 2 == 0) direct else heap
                dst.clear()
                try { st.pread(dst, TimeshiftConstants.RING_HEADER_BYTES.toLong()); reads.incrementAndGet() } catch (e: IOException) {
                    readerError.set(e)
                }
                Thread.interrupted() // the loader clears the cancel and serves the next load
            }
        }.apply { isDaemon = true; start() }
        val interrupter = Thread { while (!stop.get()) { reader.interrupt(); Thread.yield() } }.apply { isDaemon = true; start() }
        try {
            val src = ByteBuffer.allocateDirect(block) // the flusher writes from the pool's direct blocks
            for (n in 0 until 400) {
                src.clear()
                for (k in 0 until block) src.put(k, (n + k).toByte())
                val pos = TimeshiftConstants.RING_HEADER_BYTES + (n % 8L) * block
                var written = 0
                while (src.hasRemaining()) written += st.pwrite(src, pos + written)
                assertEquals(block, written)
            }
        } finally {
            stop.set(true); interrupter.join(2_000); reader.join(2_000)
        }
        assertTrue(reads.get() > 0, "the reader ran")
        assertNull(readerError.get(), "an interrupt is a cancel, never an I/O error")
        val back = ByteBuffer.allocate(block)
        st.pread(back, TimeshiftConstants.RING_HEADER_BYTES + 7L * block)
        assertContentEquals(ByteArray(block) { (399 + it).toByte() }, back.array())
        st.close()
    }

    /** [UninterruptibleFileIo] directly: an interrupted thread reads and writes as if it were not. */
    @Test
    fun anInterruptedThreadStillReadsAndWrites() {
        val io = UninterruptibleFileIo(tmp())
        val err = AtomicReference<Throwable?>()
        val t = Thread {
            try {
                Thread.currentThread().interrupt()
                io.pwrite(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 10L)
                val d = ByteBuffer.allocateDirect(3)
                assertEquals(3, io.pread(d, 10L))
                d.flip()
                assertEquals(1, d.get().toInt())
                assertEquals(-1, io.pread(ByteBuffer.allocate(4), 1_000L), "EOF")
                assertTrue(Thread.currentThread().isInterrupted, "the flag is left for the caller")
            } catch (e: Throwable) { err.set(e) }
        }
        t.start(); t.join()
        assertNull(err.get())
        io.close()
    }
}
