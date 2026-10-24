// C-B3 (docs/review/2026-10-24-storage-locations-review-core.md): an interrupted reader never closes the ring's fd.
package app.thebox.player.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * C-B3: Media3 cancels a load by interrupting the loader; with `FileChannel` an interrupt inside `read` closed the ring
 * file for the flusher (DISK_ERROR), on this device and on USB. On a device the storage uses `Os.pread`/`Os.pwrite`;
 * here (no native `Os`) the JVM path, which is not interruptible either. Instrumented twin: not written (no `:player`
 * androidTest source set; see the implementation record).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidRingStorageInterruptTest {
    private val block = TimeshiftConstants.RING_BLOCK_BYTES
    private val cap = 8L * block

    private fun storage(blocking: Boolean = false): AndroidRingStorage {
        val dir = Files.createTempDirectory("ring-int").toFile()
        return AndroidRingStorage(File(dir, "ring-1.bin"), cap, posix = FakePosix(), blocking = blocking)
    }

    @Test
    fun anInterruptedPreadLeavesTheFileUsable() {
        for (st in listOf(storage(), storage(blocking = true))) { // this device, and the USB shape
            st.allocate(TimeshiftConstants.RING_HEADER_BYTES + cap)
            st.writeHeader(1, cap)
            val err = AtomicReference<Throwable?>()
            val t = Thread {
                Thread.currentThread().interrupt() // the cancel lands while the loader is in pread
                try { st.pread(ByteBuffer.allocateDirect(block), TimeshiftConstants.RING_HEADER_BYTES.toLong()) } catch (e: Throwable) { err.set(e) }
            }
            t.start(); t.join()
            assertNull("an interrupt is a cancel, never an I/O error", err.get())
            val src = ByteBuffer.allocateDirect(block).apply { for (i in 0 until block) put(i, i.toByte()) }
            assertEquals(block, st.pwrite(src, TimeshiftConstants.RING_HEADER_BYTES + block.toLong()))
            assertFalse(st.isUnlinked())
            st.delete()
        }
    }

    @Test
    fun aReaderInterruptedMidReadNeverStopsTheWriter() {
        val st = storage()
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + cap)
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger()
        val readErr = AtomicReference<Throwable?>()
        val reader = Thread {
            val dst = ByteBuffer.allocateDirect(block)
            while (!stop.get()) {
                dst.clear()
                try { st.pread(dst, TimeshiftConstants.RING_HEADER_BYTES.toLong()); reads.incrementAndGet() } catch (e: IOException) { readErr.set(e) }
                Thread.interrupted()
            }
        }.apply { isDaemon = true; start() }
        val interrupter = Thread { while (!stop.get()) { reader.interrupt(); Thread.yield() } }.apply { isDaemon = true; start() }
        try {
            val src = ByteBuffer.allocateDirect(block)
            for (n in 0 until 300) {
                src.clear()
                for (k in 0 until block) src.put(k, (n * 3 + k).toByte())
                assertEquals(block, st.pwrite(src, TimeshiftConstants.RING_HEADER_BYTES + (n % 8L) * block))
            }
        } finally { stop.set(true); interrupter.join(2_000); reader.join(2_000) }
        assertTrue(reads.get() > 0)
        assertNull(readErr.get())
        val back = ByteBuffer.allocate(block)
        st.pread(back, TimeshiftConstants.RING_HEADER_BYTES + 3L * block) // n = 299 → slot 3
        assertArrayEquals(ByteArray(block) { (299 * 3 + it).toByte() }, back.array())
        st.delete()
    }
}
