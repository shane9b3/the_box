package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** §17.2 `TimeshiftStorageTest`: the ring file (fallocate fallback, pwrite/pread, header, unlink), the sweep, the daily counter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeshiftStorageTest {
    private val root: File = Files.createTempDirectory("ts-storage").toFile()
    private val dir = File(root, "cache/timeshift")
    private val counter = File(root, "nobackup/timeshift-writes")
    private val clock = MonoClock { 1_000L }
    private var wall = ZonedDateTime.of(2026, 10, 19, 23, 50, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli()
    private val posix = FakePosix()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun storage(pid: Int = 42) = TimeshiftStorageImpl(
        dir = dir, counterFile = counter, pid = pid, clock = clock, wallClock = { wall }, zone = { ZoneId.of("Europe/London") },
        posix = posix, statFs = { TimeshiftStorageImpl.Space(totalBytes = 8_000_000_000L, availableBytes = 3_000_000_000L) },
        counterIo = java.util.concurrent.Executor { it.run() },
    )

    @Test
    fun fallocateFallsBackToASparseFileAndGrowsByExtentUpToCapacity() {
        val cap = 3L * TimeshiftConstants.RING_BLOCK_BYTES
        val header = TimeshiftConstants.RING_HEADER_BYTES.toLong()
        val st = storage().openStorage(cap)
        assertEquals(cap, st.capacityBytes)
        // File bytes in and out, header included (TsRingImpl's convention).
        assertEquals(header + TimeshiftConstants.RING_BLOCK_BYTES, st.allocate(header + TimeshiftConstants.RING_BLOCK_BYTES))
        assertTrue("sparse path taken", st.sparse)
        assertEquals(1, posix.fallocates)
        assertEquals(header + TimeshiftConstants.RING_BLOCK_BYTES, st.file.length())
        assertEquals(header + cap, st.allocate(10 * cap)) // never past the capacity
        assertEquals(header + cap, st.file.length())
        st.delete()
    }

    @Test
    fun fallocateSuccessIsUsedAndEnospcIsAnError() {
        posix.fallocateOk = true
        val st = storage().openStorage(2L * TimeshiftConstants.RING_BLOCK_BYTES)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + TimeshiftConstants.RING_BLOCK_BYTES.toLong())
        assertFalse(st.sparse)
        posix.enospc = true
        try {
            st.allocate(TimeshiftConstants.RING_HEADER_BYTES + 2L * TimeshiftConstants.RING_BLOCK_BYTES)
            throw AssertionError("ENOSPC expected")
        } catch (e: IOException) {
            // expected: the controller turns it into BufferOff.STORAGE_ERROR / RecordStop.DISK_ERROR
        }
        st.delete()
    }

    @Test
    fun positionalWritesFromDirectBuffersReadBackAndCountTowardTheDailyCounter() {
        val s = storage()
        val st = s.openStorage(2L * TimeshiftConstants.RING_BLOCK_BYTES)
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + st.capacityBytes)
        val block = ByteBuffer.allocateDirect(TimeshiftConstants.RING_BLOCK_BYTES)
        for (i in 0 until block.capacity()) block.put(i, (i % 251).toByte())
        val pos = TimeshiftConstants.RING_HEADER_BYTES + TimeshiftConstants.RING_BLOCK_BYTES.toLong()
        assertEquals(TimeshiftConstants.RING_BLOCK_BYTES, st.pwrite(block, pos))
        val back = ByteBuffer.allocateDirect(TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals(TimeshiftConstants.RING_BLOCK_BYTES, st.pread(back, pos))
        for (i in 0 until back.capacity() step 997) assertEquals((i % 251).toByte(), back.get(i))
        assertEquals(TimeshiftConstants.RING_BLOCK_BYTES.toLong(), s.writtenToday())
        assertEquals(3_000_000_000L, st.availableBytes())
        st.delete()
    }

    @Test
    fun headerIsTheSharedCodecsWithGenerationCapacityAndPid() {
        val st = storage(pid = 7).openStorage(4L * TimeshiftConstants.RING_BLOCK_BYTES)
        st.writeHeader(generation = 5, capacity = st.capacityBytes)
        val bytes = st.file.readBytes()
        assertEquals("TBXRING1", String(bytes, 0, 8, Charsets.US_ASCII))
        val h = app.thebox.core.playback.timeshift.RingHeaderCodec.decode(ByteBuffer.wrap(bytes, 0, TimeshiftConstants.RING_HEADER_BYTES))
        assertEquals(st.capacityBytes, h!!.capacity)
        assertEquals(5L, h.generation)
        assertEquals(1_000L, h.createdMonoMs)
        assertEquals(7, h.pid)
        st.delete()
    }

    @Test
    fun unlinkIsDetectedThroughNlinkAndByReStatWithoutIt() {
        val st = storage().openStorage(TimeshiftConstants.RING_BLOCK_BYTES.toLong())
        assertFalse(st.isUnlinked())
        posix.links = 0
        assertTrue(st.isUnlinked())
        posix.links = -1 // no native fstat: re-stat the path
        assertFalse(st.isUnlinked())
        st.file.delete()
        assertTrue(st.isUnlinked())
        st.delete()
    }

    @Test
    fun deleteClosesTheFdThenUnlinks() {
        val st = storage().openStorage(TimeshiftConstants.RING_BLOCK_BYTES.toLong())
        st.allocate(TimeshiftConstants.RING_HEADER_BYTES + st.capacityBytes)
        assertTrue(st.file.exists())
        st.delete()
        assertFalse(st.file.exists())
        try {
            st.pwrite(ByteBuffer.allocateDirect(188), TimeshiftConstants.RING_HEADER_BYTES.toLong())
            throw AssertionError("closed storage must refuse writes")
        } catch (e: IOException) {
        }
    }

    @Test
    fun theSweepDeletesOtherRingsButSparesThisPidsAndPrepareAwaitsIt() = runBlocking {
        dir.mkdirs()
        File(dir, "ring-41.bin").writeText("stale")
        File(dir, "ring-43.bin").writeText("stale")
        File(dir, "junk.tmp").writeText("x")
        val s = storage(pid = 42)
        val mine = s.openStorage(TimeshiftConstants.RING_BLOCK_BYTES.toLong()) // an auto-tune created it first
        s.awaitSweep()
        assertEquals(listOf("ring-42.bin"), dir.list()!!.toList())
        assertTrue(mine.file.exists())
        mine.delete()
        s.startSweep() // idempotent
    }

    @Test
    fun theDailyCounterPersistsEvery64MiBAndResetsAtLocalMidnight() {
        val s = storage()
        s.addWritten(TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES - 1)
        assertFalse("not persisted below 64 MiB", counter.exists())
        s.addWritten(1)
        assertTrue(counter.readText().startsWith("2026-10-19 ${TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES}"))
        // A new process the same day reads it back.
        assertEquals(TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES, storage().writtenToday())
        // Local midnight (Europe/London): the counter restarts at 0.
        wall += 11 * 60_000L
        assertEquals(0L, s.writtenToday())
        assertTrue(counter.readText().startsWith("2026-10-20 0"))
        // A stored older day never counts for today.
        wall += 86_400_000L
        assertEquals(0L, storage().writtenToday())
    }

    /** Fix pass S6: the daily counter costs no allocation per block write in steady state (§15: zero per block). */
    @Test
    fun theDailyCounterAllocatesNothingPerWrite() {
        val st = storage()
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(20_000) { st.addWritten(1_000) } // warm-up (the first call loads the stored counter)
        val tid = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(tid)
        repeat(10_000) { st.addWritten(1_000) }
        val allocated = mx.getThreadAllocatedBytes(tid) - before
        assertTrue("allocated $allocated bytes over 10,000 writes", allocated < 1_024)
    }

    @Test
    fun blockedPersistenceNeverHoldsTheAccountingLockAndFlushRequestsAreCoalesced() {
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val saved = CountDownLatch(2)
        val snapshots = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = TimeshiftStorageImpl(dir, counter, 42, clock, wallClock = { wall },
            counterIo = writer, writeCounter = { text ->
                snapshots += text
                if (snapshots.size == 1) {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                }
                saved.countDown()
            })
        try {
            s.addWritten(10)
            s.flushCounter()
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val whileBlocked = caller.submit<Long> {
                s.addWritten(20)
                repeat(1_000) { s.flushCounter() }
                s.writtenToday()
            }
            assertEquals("disk work must not block accounting or caller flush", 30L, whileBlocked.get(1, TimeUnit.SECONDS).toLong())
            finishWrite.countDown()
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            assertEquals(2, snapshots.size)
            assertTrue(snapshots.last().trim().endsWith(" 30"))
        } finally {
            finishWrite.countDown()
            writer.shutdownNow()
            caller.shutdownNow()
        }
    }

    @Test
    fun queuedFlushPersistsNewestAccountingAndFailedWritesRemainRetryableAcrossMidnight() {
        val tasks = ArrayDeque<Runnable>()
        val writes = mutableListOf<String>()
        var fail = true
        val s = TimeshiftStorageImpl(dir, counter, 42, clock, wallClock = { wall }, zone = { ZoneId.of("Europe/London") },
            counterIo = Executor { tasks.addLast(it) }, writeCounter = { text ->
                if (fail) throw IOException("disk busy")
                writes += text
            })
        s.addWritten(10)
        repeat(1_000) { s.flushCounter() }
        assertEquals("one pending task, even during rapid channel changes", 1, tasks.size)
        tasks.removeFirst().run() // fails, but the dirty bytes remain
        s.addWritten(20)
        fail = false
        s.flushCounter()
        tasks.removeFirst().run()
        assertTrue(writes.single().trim().endsWith(" 30"))
        wall += 11 * 60_000L
        assertEquals(0L, s.writtenToday()) // schedules rollover snapshot
        s.addWritten(7)
        s.flushCounter()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals("2026-10-20 7", writes.last().trim())
    }
}
