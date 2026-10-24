// WP-S2 (docs/design/m6-storage-locations.md §5.2, §16.1 FallbackRingStorageTest).
package app.thebox.core.storage

import app.thebox.core.playback.timeshift.FakeMonoClock
import app.thebox.core.playback.timeshift.MemoryRingStorage
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingTestKit
import app.thebox.core.playback.timeshift.TimeshiftConstants
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackRingStorageTest {
    private val clock = FakeMonoClock()
    private val cap = 8L * TimeshiftConstants.RING_BLOCK_BYTES

    /** A scripted primary: [createAnswer] decides `create`; writes land in [file] once created. */
    private inner class ScriptedRemote(var createAnswer: (Long) -> Boolean = { true }) : RemoteRingStorage {
        val file = MemoryRingStorage(cap)
        val createCalls = AtomicInteger()
        var deadlineSeen = -1L
        var created = false
        var closed = false
        var deleted = false
        var headerGen = -1L
        var failWrites: IOException? = null
        override var lastFailure: StorageFailure? = null
        override var gone = false
        override var needsFreshFile = false
        override var remoteBytesWritten = 0L
        override val capacityBytes: Long get() = cap
        override val blocking: Boolean get() = true
        override fun create(deadlineMonoMs: Long): Boolean {
            createCalls.incrementAndGet(); deadlineSeen = deadlineMonoMs
            created = createAnswer(deadlineMonoMs)
            return created
        }
        override fun ratesBps(): LongArray = longArrayOf(0, 0)
        override fun allocate(upToBytes: Long): Long = upToBytes
        override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
            check(created) { "write before create" }
            failWrites?.let { throw it }
            val n = file.pwrite(src, filePosition); remoteBytesWritten += n; return n
        }
        override fun pread(dst: ByteBuffer, filePosition: Long): Int = file.pread(dst, filePosition)
        override fun isUnlinked(): Boolean = gone
        override fun availableBytes(): Long = 1L shl 40
        override fun writeHeader(generation: Long, capacity: Long) { headerGen = generation }
        override fun delete() { closed = true; deleted = true }
        override fun close() { closed = true }
    }

    private class CountingSecondary(capacity: Long) {
        val storage = MemoryRingStorage(capacity, available = 5L shl 30)
        val builds = AtomicInteger()
    }

    private fun buf(n: Int, b: Byte = 7) = ByteBuffer.wrap(ByteArray(n) { b })

    @Test
    fun createOkDelegatesEverythingToThePrimary() {
        val p = ScriptedRemote()
        val sec = CountingSecondary(cap)
        val f = FallbackRingStorage(p, { sec.builds.incrementAndGet(); sec.storage }, 6_000L, clock)
        f.writeHeader(3, cap)
        assertEquals(3, p.headerGen) // before the decision the header only goes to the primary (no I/O there, §3.6)
        assertEquals(0, p.createCalls.get())
        assertEquals(1000L, f.pwrite(buf(1000), TimeshiftConstants.RING_HEADER_BYTES.toLong()).toLong())
        assertEquals(1, p.createCalls.get())
        assertEquals(clock.nowMs() + 6_000L, p.deadlineSeen)
        f.pwrite(buf(10), 5_000L)
        assertEquals(1, p.createCalls.get()) // once per storage
        assertEquals(1010L, p.remoteBytesWritten)
        assertEquals(0, sec.builds.get())
        assertNull(f.switchedTo)
        assertNull(f.fallbackReason)
        val dst = ByteBuffer.allocate(10)
        f.pread(dst, 5_000L)
        assertContentEquals(ByteArray(10) { 7 }, dst.array())
    }

    @Test
    fun deadlinePassesSwitchesToTheSecondaryWithTheHeaderFirst() {
        val p = ScriptedRemote(createAnswer = { false }).apply { lastFailure = StorageFailure.UNREACHABLE }
        val sec = CountingSecondary(cap)
        val f = FallbackRingStorage(p, { sec.builds.incrementAndGet(); sec.storage }, 3_000L, clock)
        f.writeHeader(5, cap)
        f.allocate(TimeshiftConstants.RING_HEADER_BYTES + 4L * TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals(Long.MAX_VALUE, f.availableBytes()) // undecided: sized at prepare, no I/O
        assertFalse(f.isUnlinked())
        f.pwrite(buf(500), TimeshiftConstants.RING_HEADER_BYTES.toLong())
        assertEquals(1, sec.builds.get())
        assertEquals(1L, sec.storage.headers.get())
        assertEquals(5L, sec.storage.lastHeaderGeneration)
        assertEquals(TimeshiftConstants.RING_HEADER_BYTES + 4L * TimeshiftConstants.RING_BLOCK_BYTES, sec.storage.allocated) // high-water replayed
        assertEquals(500L, sec.storage.bytesWritten.get())
        assertTrue(p.closed)
        assertEquals(StorageKind.INTERNAL, f.switchedTo)
        assertEquals(LocationFallback.UNREACHABLE, f.fallbackReason)
        assertSame(sec.storage, f.secondaryStorage)
        assertEquals(5L shl 30, f.availableBytes()) // the secondary's (it reports the internal reserve, §5.2)
        f.writeHeader(6, cap)
        assertEquals(6L, sec.storage.lastHeaderGeneration)
        assertEquals(0L, p.remoteBytesWritten)
    }

    @Test
    fun theFallbackReasonFollowsThePrimarysLastFailure() {
        for ((failure, reason) in listOf(
            StorageFailure.ACCESS to LocationFallback.SIGN_IN_NEEDED,
            StorageFailure.FULL to LocationFallback.NO_SPACE_THERE,
            null to LocationFallback.UNREACHABLE,
            StorageFailure.SLOW to LocationFallback.UNREACHABLE,
        )) {
            val p = ScriptedRemote(createAnswer = { false }).apply { lastFailure = failure }
            val f = FallbackRingStorage(p, { MemoryRingStorage(cap) }, 6_000L, clock)
            f.pwrite(buf(10), 4_096L)
            assertEquals(reason, f.fallbackReason, "for $failure")
        }
    }

    @Test
    fun noSwitchOnceSomethingReachedTheFolder() {
        val p = ScriptedRemote(createAnswer = { false }).apply { remoteBytesWritten = 1 }
        val sec = CountingSecondary(cap)
        val f = FallbackRingStorage(p, { sec.builds.incrementAndGet(); sec.storage }, 6_000L, clock)
        assertFailsWith<IOException> { f.pwrite(buf(10), 4_096L) }
        assertEquals(0, sec.builds.get())
        assertNull(f.switchedTo)
    }

    @Test
    fun noHotSwitchAfterASuccessfulCreate() {
        val p = ScriptedRemote()
        val sec = CountingSecondary(cap)
        val f = FallbackRingStorage(p, { sec.builds.incrementAndGet(); sec.storage }, 6_000L, clock)
        f.pwrite(buf(10), 4_096L)
        p.failWrites = IOException("link lost past the budget")
        assertFailsWith<IOException> { f.pwrite(buf(10), 4_106L) }
        assertEquals(0, sec.builds.get())
        assertNull(f.switchedTo)
    }

    @Test
    fun theWearCounterSeesOnlyTheSecondarysWrites() {
        // The secondary carries the daily counter (TimeshiftStorageImpl.openLocal(countsWear = true)); the primary none.
        var counted = 0L
        val p = ScriptedRemote(createAnswer = { false })
        val counting = object : app.thebox.core.playback.timeshift.RingStorage by MemoryRingStorage(cap) {
            override fun pwrite(src: ByteBuffer, filePosition: Long): Int { val n = src.remaining(); src.position(src.limit()); counted += n; return n }
        }
        val f = FallbackRingStorage(p, { counting }, 6_000L, clock)
        f.pwrite(buf(700), 4_096L)
        assertEquals(700L, counted)
        val ok = ScriptedRemote()
        val f2 = FallbackRingStorage(ok, { counting }, 6_000L, clock)
        f2.pwrite(buf(300), 4_096L)
        assertEquals(700L, counted)
    }

    @Test
    fun closeAndDeleteNeverWaitAndRouteTheSecondaryToBackground() {
        val posted = mutableListOf<Runnable>()
        val p = ScriptedRemote(createAnswer = { false })
        val sec = CountingSecondary(cap)
        val f = FallbackRingStorage(p, { sec.storage }, 6_000L, clock, background = { posted += it })
        f.pwrite(buf(10), 4_096L)
        f.delete()
        assertTrue(p.deleted)
        assertFalse(sec.storage.deleted) // posted, not on the caller's thread
        posted.forEach { it.run() }
        assertTrue(sec.storage.deleted)
        assertTrue(f.isUnlinked())
        assertFailsWith<IOException> { FallbackRingStorage(ScriptedRemote(), { sec.storage }, 1L, clock).also { it.close() }.pwrite(buf(1), 4_096L) }
    }

    @Test
    fun readsBeforeTheFirstWriteFail() {
        val f = FallbackRingStorage(ScriptedRemote(), { MemoryRingStorage(cap) }, 6_000L, clock)
        assertFailsWith<IOException> { f.pread(ByteBuffer.allocate(10), 4_096L) }
    }

    @Test
    fun firstWriteTimeIsPerCycle() {
        val f = FallbackRingStorage(ScriptedRemote(), { MemoryRingStorage(cap) }, 6_000L, clock)
        assertEquals(-1L, f.firstWriteMonoMs)
        clock.advance(100)
        f.pwrite(buf(10), 4_096L)
        val first = f.firstWriteMonoMs
        assertEquals(clock.nowMs(), first)
        clock.advance(100)
        f.pwrite(buf(10), 4_106L)
        assertEquals(first, f.firstWriteMonoMs)
        f.beginCycle()
        assertEquals(-1L, f.firstWriteMonoMs)
    }

    // ------------------------------------------------------------------ C-B1 (fix pass, core half, 2026-10-24)

    private val realClock = app.thebox.core.playback.MonoClock { System.nanoTime() / 1_000_000L }

    /** C-B1 (the review probe): each create step just under the deadline, far under the I/O timeout; the switch holds it. */
    @Test
    fun switchLandsByTheDeadlineWhateverEachStepTakes() {
        val link = app.thebox.core.storage.smb.FakeRemoteLink(realClock)
        link.latencyMs = 900 // a NAS disk waking: every round trip just under the deadline
        val primary = app.thebox.core.storage.smb.SmbRingStorage(link, "TheBox-rewind/ring-x-1-1.bin", cap, StorageLimits.SMB, realClock)
        val deadline = 1_000L
        val fb = FallbackRingStorage(primary, { MemoryRingStorage(cap) }, deadline, realClock)
        val t0 = realClock.nowMs()
        fb.pwrite(ByteBuffer.wrap(ByteArray(TimeshiftConstants.RING_BLOCK_BYTES)), TimeshiftConstants.RING_HEADER_BYTES.toLong())
        val took = realClock.nowMs() - t0
        assertEquals(StorageKind.INTERNAL, fb.switchedTo)
        assertTrue(took <= deadline + 250, "the first write waited $took ms for a $deadline ms deadline")
        // The create was still in flight: it ends off the flusher, and its late handle is closed and its file removed.
        waitFor("late create closed") { link.refs.get() == 0 && !link.exists(primary.relativePath) && link.log.any { it.startsWith("open:") } }
    }

    /** C-B1: a create blocked inside one I/O call (a hung CREATE) cannot hold the switch either. */
    @Test
    fun aCreateBlockedInsideOneCallStillSwitchesOnTime() {
        val link = app.thebox.core.storage.smb.FakeRemoteLink(realClock)
        link.stallCallsUntil("open:", realClock.nowMs() + 60_000) // ends as an 8 s I/O timeout
        val primary = app.thebox.core.storage.smb.SmbRingStorage(link, "TheBox-rewind/ring-x-1-3.bin", cap, StorageLimits.SMB, realClock)
        val fb = FallbackRingStorage(primary, { MemoryRingStorage(cap) }, 500L, realClock)
        val t0 = realClock.nowMs()
        fb.pwrite(buf(100), 4_096L)
        assertTrue(realClock.nowMs() - t0 <= 750, "switched after ${realClock.nowMs() - t0} ms")
        assertEquals(StorageKind.INTERNAL, fb.switchedTo)
        assertEquals(LocationFallback.UNREACHABLE, fb.fallbackReason)
    }

    /** C-B1: the pool bounds the wait, whatever the deadline: at 25 % free the cycle switches. */
    @Test
    fun aFillingPoolSwitchesBeforeTheDeadline() {
        val p = ScriptedRemote(createAnswer = { Thread.sleep(30_000); true })
        val pool = 100L * TimeshiftConstants.RING_BLOCK_BYTES
        val used = java.util.concurrent.atomic.AtomicLong()
        val fb = FallbackRingStorage(p, { MemoryRingStorage(cap) }, 6_000L, realClock)
        fb.attachPool(pool) { used.get() }
        val filler = Thread { while (used.get() < pool) { used.addAndGet(pool / 50); Thread.sleep(10) } }.apply { isDaemon = true; start() }
        val t0 = realClock.nowMs()
        fb.pwrite(buf(100), 4_096L)
        val took = realClock.nowMs() - t0
        val atSwitch = used.get()
        filler.interrupt()
        assertEquals(StorageKind.INTERNAL, fb.switchedTo)
        assertTrue(took < 1_500, "switched after $took ms (the pool fills in ≈ 0.5 s)")
        assertTrue(atSwitch < pool, "switched before the pool was full ($atSwitch of $pool)")
    }

    /** C-B1: the pool's time at the measured rate (× the margin) caps the deadline, so a 4K rate switches early. */
    @Test
    fun theMeasuredRateCapsTheDeadline() {
        val p = ScriptedRemote(createAnswer = { Thread.sleep(30_000); true })
        val pool = 160L * TimeshiftConstants.RING_BLOCK_BYTES // 10.47 MB: ≈ 3 s at 3.5 MB/s
        val t0 = realClock.nowMs()
        val fb = FallbackRingStorage(p, { MemoryRingStorage(cap) }, 6_000L, realClock)
        fb.attachPool(pool) { (realClock.nowMs() - t0) * 3_500L } // 3.5 MB/s
        fb.pwrite(buf(100), 4_096L)
        val took = realClock.nowMs() - t0
        assertTrue(took in 1_200..2_400, "switched after $took ms; the pool lasts ≈ 3 s, × ${FallbackRingStorage.POOL_MARGIN}")
    }

    /** C-S3: every switch is reported once, with the primary's failure. */
    @Test
    fun theSwitchIsReportedWithThePrimarysFailure() {
        val reports = mutableListOf<StorageFailure?>()
        val p = ScriptedRemote(createAnswer = { false }).apply { lastFailure = StorageFailure.ACCESS }
        val f = FallbackRingStorage(p, { MemoryRingStorage(cap) }, 6_000L, clock).apply { onSwitched = { reports += it } }
        f.pwrite(buf(10), 4_096L)
        f.pwrite(buf(10), 4_106L)
        assertEquals(listOf<StorageFailure?>(StorageFailure.ACCESS), reports)
        val ok = FallbackRingStorage(ScriptedRemote(), { MemoryRingStorage(cap) }, 6_000L, clock).apply { onSwitched = { reports += it } }
        ok.pwrite(buf(10), 4_096L)
        assertEquals(1, reports.size, "no report when the folder took the file")
    }

    /** C-B1: a ring stop before the decision ends it with no fallback announcement (one toast), and no secondary. */
    @Test
    fun aStopBeforeTheDecisionAnnouncesNoFallback() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val p = ScriptedRemote(createAnswer = { gate.await(); false })
        val sec = CountingSecondary(cap)
        val reports = mutableListOf<StorageFailure?>()
        val f = FallbackRingStorage(p, { sec.builds.incrementAndGet(); sec.storage }, 6_000L, realClock).apply { onSwitched = { reports += it } }
        val flusher = Thread { try { f.pwrite(buf(10), 4_096L) } catch (_: IOException) { } }.apply { start() }
        Thread.sleep(100)
        assertTrue(f.undecided)
        assertTrue(f.abandonUndecided())
        flusher.join(2_000)
        gate.countDown()
        assertFalse(flusher.isAlive)
        assertNull(f.switchedTo)
        assertNull(f.fallbackReason)
        assertEquals(0, sec.builds.get())
        assertTrue(reports.isEmpty())
        assertTrue(p.deleted, "the primary is removed (a late CREATE is closed off the flusher)")
        assertFalse(FallbackRingStorage(ScriptedRemote(), { sec.storage }, 6_000L, clock).also { it.pwrite(buf(1), 4_096L) }.abandonUndecided())
    }

    private fun waitFor(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    @Test
    fun aRealRingSwitchesBeforeItsFirstFlushAndStaysReadable() {
        val p = ScriptedRemote(createAnswer = { false }).apply { lastFailure = StorageFailure.UNREACHABLE }
        val sec = MemoryRingStorage(cap)
        val f = FallbackRingStorage(p, { sec }, 6_000L, clock)
        val r =app.thebox.core.playback.timeshift.TsRingImpl(f, clock, 4, 4096, null)
        r.reset()
        val slot = RingTestKit.newSlot(r)
        assertEquals(app.thebox.core.playback.timeshift.ClaimResult.OK, RingTestKit.append(r, slot, 3 * RingTestKit.BLOCK))
        assertTrue(r.storageUsable)
        assertNull(r.stop)
        assertEquals(StorageKind.INTERNAL, f.switchedTo)
        assertTrue(sec.bytesWritten.get() >= 3 * RingTestKit.BLOCK - 188)
        assertEquals(1, p.createCalls.get())
        val reader = r.openReader(0L, r.generation)
        RingTestKit.readAndCheck(reader, r.generation, (2 * RingTestKit.BLOCK).toInt())
        r.close()
        assertFalse(r.stop == RecordStop.DISK_ERROR)
    }
}
