package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16.1 `SmbReconnectTest` and `ReopenNeverTruncatesTest` (WP-S1): single flight across the flusher and a reader, the
 * same offset after the reconnect, the budget, `needsFreshFile` after a reconnect and after a write timeout, and the
 * reopen that never truncates (A-S2).
 */
class SmbReconnectTest {
    private val far = Long.MAX_VALUE / 2
    private val path = "TheBox-rewind/ring-abcdef012345-1-1.bin"

    private fun created(link: FakeRemoteLink): SmbRingStorage =
        SmbRingStorage(link, path, 64L * RING_BLOCK_BYTES, StorageLimits.SMB, link.clock).also {
            it.writeHeader(1, it.capacityBytes)
            assertTrue(it.create(far))
        }

    private fun block(seed: Int) = ByteBuffer.wrap(ByteArray(RING_BLOCK_BYTES) { ((it * 7 + seed) and 0xFF).toByte() })

    @Test
    fun aTransportLossReconnectsOnceAndRetriesAtTheSameOffset() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        assertFalse(s.needsFreshFile)
        link.dropTransport()
        val b2 = block(2)
        val expect = b2.array().copyOf()
        s.pwrite(b2, RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong())
        assertEquals(1, link.reconnects.get())
        assertEquals(listOf(OpenMode.CREATE_OVERWRITE, OpenMode.OPEN_EXISTING), link.opens.map { it.second })
        assertContentEquals(expect, link.contents(path)!!.copyOfRange(RING_HEADER_BYTES + RING_BLOCK_BYTES, RING_HEADER_BYTES + 2 * RING_BLOCK_BYTES))
        assertTrue(s.needsFreshFile, "any reconnect: never reuse this file across reset() (§4.5)")
        assertNull(s.lastFailure, "an invisible reconnect is no failure")
    }

    /**
     * Q-E3 (closure fix pass 2026-10-28): a retired link (the keeper relinked after a sign-in change) never reconnects;
     * the ring fails over at once (UNREACHABLE) instead of retrying for the whole reconnect budget.
     */
    @Test
    fun aRetiredLinkFailsOverAtOnce() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link.retired = true
        link.dropTransport()
        val t0 = System.nanoTime()
        assertFailsWith<IOException> { s.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_000, "failed over in $ms ms, not after the ${StorageConstants.SMB_RECONNECT_BUDGET_MS} ms budget")
        assertEquals(StorageFailure.UNREACHABLE, s.lastFailure)
        assertEquals(0, link.reconnects.get())
    }

    @Test
    fun reopenNeverTruncatesTheSavedPart() {
        val link = FakeRemoteLink()
        val s = created(link)
        for (i in 0 until 4) s.pwrite(block(i), RING_HEADER_BYTES + i.toLong() * RING_BLOCK_BYTES)
        val before = link.contents(path)!!
        link.dropTransport()
        // A reader triggers the reconnect: the saved part must still be there, byte for byte.
        val dst = ByteBuffer.allocate(RING_BLOCK_BYTES)
        assertEquals(RING_BLOCK_BYTES, s.pread(dst, RING_HEADER_BYTES.toLong()))
        assertContentEquals(before.copyOfRange(RING_HEADER_BYTES, RING_HEADER_BYTES + RING_BLOCK_BYTES), dst.array())
        assertContentEquals(before, link.contents(path)!!, "OPEN_EXISTING keeps every byte")
        assertEquals(OpenMode.OPEN_EXISTING, link.opens.last().second)
        assertEquals(1, link.opens.count { it.second == OpenMode.CREATE_OVERWRITE }, "CREATE once per storage")
    }

    @Test
    fun singleFlightAcrossTheFlusherAndAReader() {
        val link = FakeRemoteLink().apply { reconnectLatencyMs = 300 }
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link.dropTransport()
        val start = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val writer = Thread {
            start.await()
            try { s.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) } catch (e: Throwable) { errors.set(e) }
        }
        val reader = Thread {
            start.await()
            try { s.pread(ByteBuffer.allocate(RING_BLOCK_BYTES), RING_HEADER_BYTES.toLong()) } catch (e: Throwable) { errors.set(e) }
        }
        writer.start(); reader.start(); start.countDown()
        writer.join(5_000); reader.join(5_000)
        assertNull(errors.get())
        assertEquals(1, link.reconnects.get(), "one reconnect for both")
        assertEquals(1, link.opens.count { it.second == OpenMode.OPEN_EXISTING }, "one reopen for both")
    }

    @Test
    fun theBudgetEndsTheRetryAsUnreachable() {
        val link = FakeRemoteLink(ioTimeoutMs = 600) // the budget is min(SMB_RECONNECT_BUDGET_MS, the I/O timeout)
        val s = created(link)
        link.dropTransport(); link.reconnectFails = true
        val t0 = System.nanoTime()
        assertFailsWith<IOException> { s.pwrite(block(1), RING_HEADER_BYTES.toLong()) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms in 0..3_000, "bounded by the budget: $ms ms")
        assertEquals(StorageFailure.UNREACHABLE, s.lastFailure)
        assertTrue(s.needsFreshFile)
        assertTrue(minOf(StorageConstants.SMB_RECONNECT_BUDGET_MS, 600L) == 600L)
    }

    @Test
    fun aWriteTimeoutNeedsAFreshFileAndNeverReconnects() {
        val link = FakeRemoteLink()
        val s = created(link)
        link.timeoutNext()
        assertFailsWith<IOException> { s.pwrite(block(1), RING_HEADER_BYTES.toLong()) }
        assertEquals(StorageFailure.SLOW, s.lastFailure)
        assertTrue(s.needsFreshFile, "a timed-out WRITE may still land (§4.5)")
        assertEquals(0, link.reconnects.get(), "SLOW never reconnects (A-B4)")
        assertEquals(1L, link.epoch)
    }

    /** C-S2: a live server's refusal (an unknown status) fails the write once; it never reconnects or tears down. */
    @Test
    fun aServerRefusalNeverReconnects() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link.statusNext(FakeStatus.REFUSED, prefix = "write")
        assertFailsWith<IOException> { s.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) }
        assertEquals(0, link.reconnects.get(), "a refusal is not a link loss (A-B4)")
        assertEquals(1L, link.epoch, "the connection was kept")
        assertEquals(StorageFailure.SLOW, s.lastFailure)
        assertTrue(s.needsFreshFile, "the refused WRITE's fate is unknown: the next cycle takes a fresh file")
        assertEquals(1, link.log.count { it.startsWith("write") } - 2, "the refused write was tried once")
    }

    /**
     * C-N1: a reconnect during the zap (the old generation's in-flight WRITE retried on the new connection) sets
     * needsFreshFile after the factory's reuse check; the new generation must not write into that file.
     */
    @Test
    fun aReconnectDuringTheZapNeverWritesTheNewGenerationIntoTheOldFile() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        // Generation 1's next WRITE is in flight (the server is slow to answer it) …
        link.stallCallsUntil("write", link.clock.nowMs() + 300)
        val inFlight = Thread { s.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) }.also { it.start() }
        Thread.sleep(50)
        assertFalse(s.needsFreshFile) // … so the factory's reuse check passes …
        s.writeHeader(2, s.capacityBytes) // … and reset() starts generation 2 on the same file.
        link.dropTransport()              // Then the transport dies under the in-flight WRITE: reconnect, retried.
        inFlight.join(5_000)
        assertTrue(s.needsFreshFile)
        assertEquals(1, link.reconnects.get())
        val writesBefore = link.log.count { it.startsWith("write") }
        assertFailsWith<IOException> { s.pwrite(block(3), RING_HEADER_BYTES.toLong()) }
        assertEquals(writesBefore, link.log.count { it.startsWith("write") }, "nothing of generation 2 reached the old file")
        assertEquals(StorageFailure.UNREACHABLE, s.lastFailure)
        // A reconnect inside one generation is still invisible (§4.4): that cycle keeps writing.
        val link2 = FakeRemoteLink()
        val t = created(link2)
        t.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link2.dropTransport()
        t.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong())
        t.pwrite(block(3), RING_HEADER_BYTES + 2L * RING_BLOCK_BYTES)
        assertTrue(t.needsFreshFile, "the next zap takes a fresh file (§4.5)")
        assertNull(t.lastFailure)
    }

    @Test
    fun aReadTimeoutFailsTheReadOnly() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link.timeoutNext()
        assertFailsWith<IOException> { s.pread(ByteBuffer.allocate(100), RING_HEADER_BYTES.toLong()) }
        assertFalse(s.needsFreshFile)
        assertNull(s.lastFailure)
        assertEquals(0, link.reconnects.get())
    }

    @Test
    fun aDeletedFileIsGoneAfterTheReopen() {
        val link = FakeRemoteLink()
        val s = created(link)
        s.pwrite(block(1), RING_HEADER_BYTES.toLong())
        link.dropTransport()
        link.deleteFile(path)
        assertFailsWith<IOException> { s.pwrite(block(2), RING_HEADER_BYTES + RING_BLOCK_BYTES.toLong()) }
        assertTrue(s.gone && s.isUnlinked(), "OBJECT_NAME_NOT_FOUND on the reopen → REMOVED (STORAGE_EVICTED)")
        assertEquals(StorageFailure.REMOVED, s.lastFailure)
    }

    @Test
    fun aClosedStorageStopsRetryingWithinTheBudget() {
        val link = FakeRemoteLink().apply { reconnectLatencyMs = 2_000 }
        val s = created(link)
        link.dropTransport()
        val done = CountDownLatch(1)
        Thread { try { s.pwrite(block(1), RING_HEADER_BYTES.toLong()) } catch (_: IOException) {} ; done.countDown() }.start()
        Thread.sleep(100)
        s.close()
        assertTrue(done.await(StorageConstants.SMB_RECONNECT_BUDGET_MS + 1_000, TimeUnit.MILLISECONDS))
    }
}
