package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.RingHeaderCodec
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §16.1 `SmbRingStorageTest` (WP-S1): no I/O off the flusher, create once, header first, reads, posted close/delete. */
class SmbRingStorageTest {
    private val far = Long.MAX_VALUE / 2
    private val path = "TV/Rewind/TheBox-rewind/ring-abcdef012345-77-1.bin"

    private fun storage(link: FakeRemoteLink, cap: Long = 64L * RING_BLOCK_BYTES) =
        SmbRingStorage(link, path, cap, StorageLimits.SMB, link.clock)

    private fun block(seed: Int, n: Int = RING_BLOCK_BYTES): ByteBuffer =
        ByteBuffer.wrap(ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() })

    @Test
    fun constructionWriteHeaderAllocateCloseAndDeleteDoNoIo() {
        val link = FakeRemoteLink().apply { queuePosted = true }
        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        assertEquals(RING_HEADER_BYTES + 10L, s.allocate(RING_HEADER_BYTES + 10L))
        assertEquals(RING_HEADER_BYTES + s.capacityBytes, s.allocate(Long.MAX_VALUE), "allocate clamps to the file size, no SET_INFO")
        assertFalse(s.isUnlinked())
        assertTrue(s.availableBytes() > 0, "unknown space before the create never halts growth")
        s.close(); s.delete()
        assertEquals(0, link.ioCalls.get(), "no link call on the zap path (A-B1): ${link.log}")
        assertTrue(s.isUnlinked() && s.gone, "close sets gone at once")
        link.runPosted()
        assertEquals(listOf("share", "delete:$path"), link.log, "the posted delete runs on box-smb")
    }

    @Test
    fun createOnceWithOverwriteThenTheHeaderBeforeData() {
        val link = FakeRemoteLink()
        val s = storage(link)
        s.writeHeader(7, s.capacityBytes)
        assertTrue(s.create(link.clock.nowMs() + 5_000))
        assertTrue(s.create(link.clock.nowMs() + 5_000), "a second call answers the first result without I/O")
        assertEquals(listOf(path to OpenMode.CREATE_OVERWRITE), link.opens)
        assertEquals(1, link.refs.get(), "an open file holds a link reference")
        val hdr = RingHeaderCodec.decode(ByteBuffer.wrap(link.contents(path)!!))
        assertNotNull(hdr); assertEquals(7L, hdr.generation); assertEquals(s.capacityBytes, hdr.capacity)

        // A header set later (reset) is written before the next data write, from the volatiles.
        s.writeHeader(8, s.capacityBytes)
        val b = block(3)
        assertEquals(RING_BLOCK_BYTES, s.pwrite(b, RING_HEADER_BYTES.toLong()))
        assertFalse(b.hasRemaining(), "pwrite consumes the buffer")
        val log = link.log.filter { it.startsWith("write") }
        assertEquals(3, log.size, "header, header again, data")
        assertEquals(8L, RingHeaderCodec.decode(ByteBuffer.wrap(link.contents(path)!!))!!.generation)
        assertEquals(RING_BLOCK_BYTES.toLong(), s.remoteBytesWritten)
        assertEquals(1, link.opens.size)
    }

    @Test
    fun aFailedCreateIsNeverRetriedAndSetsTheFailure() {
        val link = FakeRemoteLink().apply { ready = false }
        val s = storage(link)
        assertFalse(s.create(link.clock.nowMs() + 30))
        assertEquals(StorageFailure.UNREACHABLE, s.lastFailure)
        link.ready = true
        assertFalse(s.create(link.clock.nowMs() + 5_000), "once per storage")
        assertFailsWith<IOException> { s.pwrite(block(1), RING_HEADER_BYTES.toLong()) }
        assertTrue(link.opens.isEmpty())
        assertEquals(0L, s.remoteBytesWritten)
    }

    @Test
    fun aCreateRefusedForAccessReportsAccess() {
        val link = FakeRemoteLink()
        link.denyWrites() // the header write after the CREATE is refused

        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        assertFalse(s.create(far))
        assertEquals(StorageFailure.ACCESS, s.lastFailure)
    }

    @Test
    fun withoutAFallbackTheFirstPwriteCreates() {
        val link = FakeRemoteLink()
        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        s.pwrite(block(5), RING_HEADER_BYTES.toLong())
        assertEquals(listOf(path to OpenMode.CREATE_OVERWRITE), link.opens)
    }

    @Test
    fun preadLoopsShortReadsAndUsesHeapStagingForDirectBuffers() {
        val link = FakeRemoteLink()
        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        assertTrue(s.create(far))
        val src = block(9)
        val expect = src.array().copyOf()
        s.pwrite(src, RING_HEADER_BYTES.toLong())
        val heap = ByteBuffer.allocate(RING_BLOCK_BYTES)
        assertEquals(RING_BLOCK_BYTES, s.pread(heap, RING_HEADER_BYTES.toLong()))
        assertContentEquals(expect, heap.array())
        val direct = ByteBuffer.allocateDirect(1_000)
        assertEquals(1_000, s.pread(direct, RING_HEADER_BYTES + 100L))
        direct.flip()
        val got = ByteArray(1_000).also { direct.get(it) }
        assertContentEquals(expect.copyOfRange(100, 1_100), got)
        // Past the end: a short count, then −1.
        val tail = ByteBuffer.allocate(4_000)
        assertEquals(1_000, s.pread(tail, RING_HEADER_BYTES + RING_BLOCK_BYTES - 1_000L))
        assertEquals(-1, s.pread(ByteBuffer.allocate(10), RING_HEADER_BYTES + RING_BLOCK_BYTES + 10L))
        assertEquals(2, s.ratesBps().size)
        assertTrue(s.ratesBps()[0] > 0 && s.ratesBps()[1] > 0)
    }

    @Test
    fun aReaderWithNoHandleNeverOpens() {
        val link = FakeRemoteLink()
        val s = storage(link)
        assertFailsWith<IOException> { s.pread(ByteBuffer.allocate(10), 0) }
        assertTrue(link.opens.isEmpty(), "a reader never opens or creates (A-S2)")
        assertEquals(0, link.ioCalls.get())
    }

    @Test
    fun closeAndDeleteReturnBeforeThePostedWorkRuns() {
        val link = FakeRemoteLink().apply { queuePosted = true }
        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        assertTrue(s.create(far))
        val before = link.ioCalls.get()
        s.delete()
        assertEquals(before, link.ioCalls.get(), "delete only posts")
        assertTrue(link.exists(path), "the file is still there until box-smb runs the work")
        assertEquals(1, link.refs.get())
        assertFailsWith<IOException> { s.pwrite(block(1), RING_HEADER_BYTES.toLong()) }
        assertFailsWith<IOException> { s.pread(ByteBuffer.allocate(10), RING_HEADER_BYTES.toLong()) }
        assertTrue(link.runPosted() >= 2)
        link.runPosted() // the handle's own posted close
        assertEquals(0, link.refs.get(), "the reference is released")
        assertFalse(link.exists(path))
    }

    @Test
    fun closeWhileTheCreateIsInFlightDiscardsTheHandle() {
        val link = FakeRemoteLink()
        val s = storage(link)
        link.latencyMs = 200
        val done = CountDownLatch(1)
        var ok: Boolean? = null
        Thread { ok = s.create(far); done.countDown() }.start()
        Thread.sleep(60)
        s.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, ok)
        Thread.sleep(100)
        assertEquals(0, link.refs.get())
    }

    @Test
    fun availableBytesKeepsTheNetworkReserveAndAFailureAnswersZero() {
        val link = FakeRemoteLink()
        val s = storage(link)
        s.writeHeader(1, s.capacityBytes)
        assertTrue(s.create(far))
        link.fullAt(10L * 1024 * 1024 * 1024)
        val free = 10L * 1024 * 1024 * 1024 - link.usedBytes()
        assertEquals(StorageLimits.SMB.reportedAvailableBytes(free), s.availableBytes())
        link.timeoutNext()
        assertEquals(0L, s.availableBytes())
        assertNull(s.lastFailure, "a space query never sets the write failure")
    }
}
