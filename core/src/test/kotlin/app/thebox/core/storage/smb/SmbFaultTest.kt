package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16.1 `SmbFaultTest` (WP-S1), the storage side: the §4.3 classes in order and what each does to the storage. The smbj
 * side of the classification (statuses, TransportException, the interrupt chain) is `:data`'s `SmbFaultsTest`.
 */
class SmbFaultTest {
    private val far = Long.MAX_VALUE / 2
    private val path = "TheBox-rewind/ring-abcdef012345-1-1.bin"

    private fun created(link: FakeRemoteLink) =
        SmbRingStorage(link, path, 64L * RING_BLOCK_BYTES, StorageLimits.SMB, link.clock).also {
            it.writeHeader(1, it.capacityBytes); assertTrue(it.create(far))
        }

    private fun block() = ByteBuffer.wrap(ByteArray(RING_BLOCK_BYTES) { it.toByte() })

    @Test
    fun anInterruptAnywhereClassifiesFirstAsCancel() {
        val link = FakeRemoteLink()
        // Even a transport loss or a timeout is a cancel when the thread is interrupted (A-B3).
        Thread.currentThread().interrupt()
        try {
            assertEquals(FaultClass.CANCEL, link.classify(FakeTransportException()).cls)
            assertEquals(FaultClass.CANCEL, link.classify(FakeTimeoutException()).cls)
        } finally { Thread.interrupted() }
        assertEquals(FaultClass.CANCEL, link.classify(IOException("x", InterruptedException())).cls)
        assertEquals(FaultClass.LINK_LOST, link.classify(FakeTransportException(message = "t")).cls)
        assertEquals(FaultClass.SLOW, link.classify(IOException("x", TimeoutException())).cls)
        assertEquals(FaultClass.FULL, link.classify(FakeStatusException(FakeStatus.DISK_FULL)).cls)
        assertEquals(FaultClass.ACCESS, link.classify(FakeStatusException(FakeStatus.ACCESS_DENIED)).cls)
        assertEquals(FaultClass.REMOVED, link.classify(FakeStatusException(FakeStatus.FILE_DELETED)).cls)
    }

    @Test
    fun anInterruptedReaderIsACancelAndTheWritersWriteGoesOn() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(), RING_HEADER_BYTES.toLong())
        val epoch = link.epoch
        link.stallUntil(link.clock.nowMs() + 400)
        val readErr = AtomicReference<Throwable?>()
        val writeErr = AtomicReference<Throwable?>()
        val readDone = CountDownLatch(1)
        val reader = Thread {
            try { s.pread(ByteBuffer.allocate(1_000), RING_HEADER_BYTES.toLong()) } catch (e: Throwable) { readErr.set(e) }
            readDone.countDown()
        }
        val writer = Thread { try { s.pwrite(block(), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) } catch (e: Throwable) { writeErr.set(e) } }
        reader.start(); writer.start()
        Thread.sleep(80)
        reader.interrupt() // the viewer seeks: Media3 cancels the load
        assertTrue(readDone.await(2, TimeUnit.SECONDS))
        writer.join(3_000)
        assertTrue(readErr.get() is InterruptedIOException, "${readErr.get()}")
        assertNull(writeErr.get(), "the concurrent WRITE completes")
        assertEquals(epoch, link.epoch, "no reconnect on a cancel")
        assertEquals(0, link.reconnects.get())
        assertNull(s.lastFailure)
        assertTrue(!s.needsFreshFile)
        assertEquals(2L * RING_BLOCK_BYTES, s.remoteBytesWritten)
    }

    @Test
    fun eachFailureClassMapsToItsStorageFailure() {
        run {
            val link = FakeRemoteLink(); val s = created(link)
            link.fullAt(link.usedBytes() + 10)
            assertFailsWith<IOException> { s.pwrite(block(), RING_HEADER_BYTES.toLong()) }
            assertEquals(StorageFailure.FULL, s.lastFailure)
        }
        run {
            val link = FakeRemoteLink(); val s = created(link)
            link.denyWrites()
            assertFailsWith<IOException> { s.pwrite(block(), RING_HEADER_BYTES.toLong()) }
            assertEquals(StorageFailure.ACCESS, s.lastFailure)
        }
        run {
            val link = FakeRemoteLink(); val s = created(link)
            link.deleteFile(path)
            assertFailsWith<IOException> { s.pwrite(block(), RING_HEADER_BYTES.toLong()) }
            assertEquals(StorageFailure.REMOVED, s.lastFailure)
            assertTrue(s.isUnlinked())
        }
    }
}
