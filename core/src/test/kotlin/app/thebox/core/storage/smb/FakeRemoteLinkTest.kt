package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.FakeMonoClock
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The commit-0 fake behaves as §16.1 says, so the WPs' tests can rely on it. */
class FakeRemoteLinkTest {
    private val far = Long.MAX_VALUE / 2

    @Test
    fun writesReadsAndRecordsOpenModes() {
        val link = FakeRemoteLink()
        val share = link.share(far)
        share.mkdirs("Rewind/TheBox-rewind")
        val f = share.open("Rewind/TheBox-rewind/ring.bin", OpenMode.CREATE_OVERWRITE)
        f.write(byteArrayOf(1, 2, 3, 4), 0, 4, 10L)
        val buf = ByteArray(8)
        assertEquals(4, f.read(buf, 0, 8, 10L))
        assertEquals(-1, f.read(buf, 0, 8, 14L))
        val again = share.open("Rewind/TheBox-rewind/ring.bin", OpenMode.OPEN_EXISTING)
        assertEquals(14, link.contents("Rewind/TheBox-rewind/ring.bin")!!.size, "OPEN_EXISTING never truncates")
        assertEquals(listOf(OpenMode.CREATE_OVERWRITE, OpenMode.OPEN_EXISTING), link.opens.map { it.second })
        assertTrue(link.ioCalls.get() >= 6)
        assertEquals(listOf("ring.bin"), share.list("Rewind/TheBox-rewind", 10).map { it.name })
        again.closeAsync()
        assertFailsWith<FakeStatusException> { share.open("Rewind/missing.bin", OpenMode.OPEN_EXISTING) }
    }

    @Test
    fun faultsClassifyInTheDesignOrder() {
        val link = FakeRemoteLink()
        val share = link.share(far)
        val f = share.open("ring.bin", OpenMode.CREATE_OVERWRITE)
        link.timeoutNext()
        val slow = assertFailsWith<IOException> { f.write(ByteArray(4), 0, 4, 0) }
        assertEquals(FaultClass.SLOW, link.classify(slow).cls)
        link.dropTransport()
        val lost = assertFailsWith<IOException> { f.write(ByteArray(4), 0, 4, 0) }
        assertEquals(FaultClass.LINK_LOST, link.classify(lost).cls)
        val e0 = link.epoch
        val s2 = link.reconnect(e0, far)
        assertEquals(e0 + 1, s2.epoch)
        assertEquals(s2.epoch, link.reconnect(e0, far).epoch, "single flight: a second caller at the old epoch gets the new share")
        assertEquals(1, link.reconnects.get())
        assertFailsWith<FakeTransportException> { f.write(ByteArray(4), 0, 4, 0) }   // the old handle died with its connection
        val f2 = s2.open("ring.bin", OpenMode.OPEN_EXISTING)
        link.denyWrites()
        assertEquals(FaultClass.ACCESS, link.classify(assertFailsWith<IOException> { f2.write(ByteArray(4), 0, 4, 0) }).cls)
        link.denyWrites(false)
        link.fullAt(2)
        assertEquals(FaultClass.FULL, link.classify(assertFailsWith<IOException> { f2.write(ByteArray(4), 0, 4, 100) }).cls)
        link.deleteFile("ring.bin")
        assertEquals(FaultClass.REMOVED, link.classify(assertFailsWith<IOException> { f2.read(ByteArray(4), 0, 4, 0) }).cls)
        assertEquals(FaultClass.REMOVED, link.classify(assertFailsWith<IOException> { s2.open("ring.bin", OpenMode.OPEN_EXISTING) }).cls)
        assertEquals(FaultClass.CANCEL, link.classify(IOException("wrapped", InterruptedIOException())).cls, "an interrupt anywhere is a cancel")
    }

    @Test
    fun aStallEndsAsATimeoutAndAnInterruptAsACancel() {
        val clock = FakeMonoClock()
        val link = FakeRemoteLink(clock, ioTimeoutMs = 1_000)
        val f = link.share(far).open("ring.bin", OpenMode.CREATE_OVERWRITE)
        link.stallUntil(clock.nowMs() + 10_000)
        val done = CountDownLatch(1)
        var caught: Throwable? = null
        val t = Thread { try { f.write(ByteArray(1), 0, 1, 0) } catch (e: Throwable) { caught = e } finally { done.countDown() } }
        t.start()
        Thread.sleep(30)
        t.interrupt()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(caught is InterruptedIOException, "$caught")
        assertEquals(1L, link.epoch, "a cancel never reconnects")
        // On the real clock a stall longer than the I/O timeout ends as SLOW, without a reconnect.
        val real = FakeRemoteLink(ioTimeoutMs = 50)
        val g = real.share(far).open("ring.bin", OpenMode.CREATE_OVERWRITE)
        real.stallUntil(real.clock.nowMs() + 10_000)
        assertEquals(FaultClass.SLOW, real.classify(assertFailsWith<IOException> { g.write(ByteArray(1), 0, 1, 0) }).cls)
        assertEquals(1L, real.epoch)
    }

    @Test
    fun postedWorkCanBeHeld() {
        val link = FakeRemoteLink().apply { queuePosted = true }
        val f = link.share(far).open("ring.bin", OpenMode.CREATE_OVERWRITE)
        f.closeAsync()
        assertFalse((f as FakeRemoteLink.FakeRemoteFile).closed, "close returns before the posted work runs")
        assertEquals(1, link.runPosted())
        assertTrue(f.closed)
        link.ready = false
        assertFailsWith<IOException> { link.share(0L) }
    }
}
