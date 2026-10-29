// WP-S1 (docs/design/m6-storage-locations.md §7.8; lead ruling 7, U-S3, A-B4).
package app.thebox.data.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.TestPhase
import app.thebox.core.storage.TestProgress
import app.thebox.core.storage.TestStopReason
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteFile
import app.thebox.core.storage.smb.RemoteShare
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Random
import java.util.zip.CRC32

/**
 * The time-bounded folder Test (§7.8), on `box-smb-browse` over the browse client's share (never the ring's):
 * 1. make `TheBox-rewind/`;
 * 2. wake: time the first 4 KiB write of `.wake-<deviceId>` ([LocationTestResult.wakeMs], excluded from every rate);
 * 3. write `RING_BLOCK_BYTES` blocks synchronously (the ring's pattern) into a `FILE_DELETE_ON_CLOSE` test file for
 *    `min(SMB_TEST_MAX_BYTES, TEST_WRITE_MS)`;
 * 4. read back what was written in `READER_STAGING_BYTES` reads for at most `TEST_READ_MS`, CRC32 per block;
 * 5. RTT: the median of `TEST_RTT_SAMPLES` one-block reads;
 * 6. close (the server deletes the file); free space.
 *
 * A timeout during a rate phase grades what was measured so far. FAILED only for §7.7 causes, a CRC mismatch or too
 * little space. An interrupt (Stop test, BACK) ends it with `InterruptedIOException`.
 *
 * C-N7 (closure fix pass): the whole run has a deadline, [runMaxMs] from the start plus the wake write's own time,
 * checked between round trips. Past it the Test stops itself: FAILED with [LocationTestResult.stop]
 * [TestStopReason.TOOK_TOO_LONG] ("Stopped: NAS took too long."), the test file closed (and so deleted).
 */
class LocationTester(
    private val clock: MonoClock,
    private val deviceId: String,
    private val pid: Int,
    private val classify: (Throwable) -> FaultClass,
    private val wifi: Boolean,
    private val nowWallMs: () -> Long = System::currentTimeMillis,
    /** C-N7: the whole-run deadline, the wake excluded ([StorageConstants.TEST_RUN_MAX_MS]; shorter in tests). */
    private val runMaxMs: Long = StorageConstants.TEST_RUN_MAX_MS,
) {
    @Throws(IOException::class)
    fun run(share: RemoteShare, folder: String, progress: (TestProgress) -> Unit): LocationTestResult {
        val dir = if (folder.isEmpty()) StorageConstants.TIMESHIFT_DIR_SMB else "${folder.trim('/')}/${StorageConstants.TIMESHIFT_DIR_SMB}"
        val block = TimeshiftConstants.RING_BLOCK_BYTES
        val maxBlocks = (StorageConstants.SMB_TEST_MAX_BYTES / block).toInt()
        val total = maxBlocks.toLong() * block
        var deadline = clock.nowMs() + runMaxMs
        fun over() = clock.nowMs() >= deadline
        try {
            progress(TestProgress(TestPhase.CONNECTING))
            share.mkdirs(dir)
            if (over()) return tookTooLong(0L, 0L, 0)

            // 2. Wake.
            progress(TestProgress(TestPhase.WAKING))
            val w0 = clock.nowMs()
            share.open("$dir/${SmbSweeper.wakeName(deviceId)}", OpenMode.CREATE_OVERWRITE).use { it.write(ByteArray(StorageConstants.WAKE_FILE_BYTES), 0, StorageConstants.WAKE_FILE_BYTES, 0L) }
            val wakeMs = (clock.nowMs() - w0).toInt()
            deadline += wakeMs // the wake is timed on its own (a sleeping disk), not held against the run

            // 3. Write phase.
            val file = share.open("$dir/${SmbSweeper.testName(deviceId, pid, System.nanoTime())}", OpenMode.CREATE_OVERWRITE, deleteOnClose = true)
            try {
                if (over()) return tookTooLong(0L, 0L, wakeMs)
                val buf = ByteArray(block)
                val rnd = Random(deviceId.hashCode().toLong())
                val crcs = LongArray(maxBlocks)
                var blocks = 0
                val wStart = clock.nowMs()
                var writeMs: Long
                progress(TestProgress(TestPhase.WRITING, 0, total))
                while (blocks < maxBlocks && clock.nowMs() - wStart < StorageConstants.TEST_WRITE_MS && !over()) {
                    rnd.nextBytes(buf)
                    try { file.write(buf, 0, block, blocks.toLong() * block) } catch (e: IOException) {
                        if (rateTimeout(e)) break else throw e
                    }
                    crcs[blocks] = crc(buf, 0, block)
                    blocks++
                    progress(TestProgress(TestPhase.WRITING, blocks.toLong() * block, total))
                }
                writeMs = (clock.nowMs() - wStart).coerceAtLeast(1L)
                val written = blocks.toLong() * block
                val writeBps = written * 1_000L / writeMs
                if (over()) return tookTooLong(writeBps, 0L, wakeMs)

                // 4. Read phase.
                progress(TestProgress(TestPhase.READING, 0, written))
                val staging = ByteArray(TimeshiftConstants.READER_STAGING_BYTES.coerceAtLeast(block))
                val rStart = clock.nowMs()
                var readBytes = 0L
                var b = 0
                read@ while (b < blocks && clock.nowMs() - rStart < StorageConstants.TEST_READ_MS && !over()) {
                    var got = 0
                    while (got < block) {
                        val n = try { file.read(staging, got, block - got, b.toLong() * block + got) } catch (e: IOException) {
                            if (rateTimeout(e)) break@read else throw e
                        }
                        if (n <= 0) break
                        got += n
                    }
                    if (got < block || crc(staging, 0, block) != crcs[b]) {
                        return failed(BrowseError(BrowseErrorKind.DATA_MISMATCH), writeBps, 0L, wakeMs)
                    }
                    readBytes += block
                    b++
                    progress(TestProgress(TestPhase.READING, readBytes, written))
                }
                val readMs = (clock.nowMs() - rStart).coerceAtLeast(1L)
                val readBps = readBytes * 1_000L / readMs
                if (over()) return tookTooLong(writeBps, readBps, wakeMs)

                // 5. RTT.
                progress(TestProgress(TestPhase.RTT))
                val samples = ArrayList<Long>()
                if (blocks > 0) for (i in 0 until StorageConstants.TEST_RTT_SAMPLES) {
                    if (over()) return tookTooLong(writeBps, readBps, wakeMs)
                    val t = clock.nowMs()
                    try { file.read(staging, 0, block, (i % blocks).toLong() * block) } catch (e: IOException) { if (rateTimeout(e)) break else throw e }
                    samples += clock.nowMs() - t
                }
                val rtt = if (samples.isEmpty()) Int.MAX_VALUE else samples.sorted()[samples.size / 2].toInt()

                // 6. Close (server deletes) and free space.
                file.closeAsync()
                if (over()) return tookTooLong(writeBps, readBps, wakeMs)
                val free = try { share.freeBytes() } catch (e: IOException) { if (classify(e) == FaultClass.CANCEL) throw e; -1L }
                val need = TimeshiftConstants.MIN_RING_BYTES + StorageConstants.NETWORK_FREE_SPACE_RESERVE
                if (free in 0 until need) {
                    return failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.FULL, freeBytes = free, needBytes = need), writeBps, readBps, wakeMs, free)
                }
                progress(TestProgress(TestPhase.DONE, readBytes, written))
                return LocationTestResult(grade(minOf(writeBps, readBps), rtt), writeBps, readBps, rtt, wakeMs, free, wifi, nowWallMs())
            } finally { file.closeAsync() }
        } catch (e: IOException) {
            when (classify(e)) {
                FaultClass.CANCEL -> throw (e as? InterruptedIOException ?: InterruptedIOException("test stopped"))
                FaultClass.ACCESS -> return failed(
                    if (SmbFaults.isLogonFailure(e)) BrowseError(BrowseErrorKind.AUTH)
                    else BrowseError(BrowseErrorKind.WRITE_REFUSED, StorageFailure.ACCESS), // the page words it by BrowseHandle.mode
                    0L, 0L, 0,
                )
                FaultClass.FULL -> return failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.FULL), 0L, 0L, 0)
                FaultClass.REMOVED -> return failed(BrowseError(BrowseErrorKind.NOT_FOUND), 0L, 0L, 0)
                FaultClass.SLOW -> return LocationTestResult(TestVerdict.TOO_SLOW, 0L, 0L, Int.MAX_VALUE, 0, -1L, wifi, nowWallMs())
                FaultClass.LINK_LOST -> return failed(SmbFaults.browseError(e, vpnUp = false), 0L, 0L, 0)
            }
        }
    }

    /** C-N7: the run passed its deadline; what was measured stays in the result, but it is never graded or stored. */
    private fun tookTooLong(w: Long, r: Long, wakeMs: Int) =
        LocationTestResult(TestVerdict.FAILED, w, r, 0, wakeMs, -1L, wifi, nowWallMs(), stop = TestStopReason.TOOK_TOO_LONG)

    private fun rateTimeout(e: IOException): Boolean = classify(e) == FaultClass.SLOW

    private fun failed(error: BrowseError, w: Long, r: Long, wakeMs: Int, free: Long = -1L) =
        LocationTestResult.failed(error, wifi, nowWallMs()).copy(writeBps = w, readBps = r, wakeMs = wakeMs, freeBytes = free)

    private fun crc(b: ByteArray, off: Int, len: Int): Long = CRC32().also { it.update(b, off, len) }.value

    private inline fun <T> RemoteFile.use(f: (RemoteFile) -> T): T = try { f(this) } finally { closeAsync() }

    companion object {
        /** §7.8's table: [rate] = min(write, read) in bytes/s; [rttMs] the median one-block read. */
        fun grade(rate: Long, rttMs: Int): TestVerdict {
            val rttOk = rttMs <= StorageConstants.TEST_RTT_SLOW_MS
            return when {
                rttOk && rate >= StorageConstants.TEST_GOOD_FACTOR * StorageConstants.UHD_BPS -> TestVerdict.GOOD_4K
                rttOk && rate >= StorageConstants.TEST_GOOD_FACTOR * StorageConstants.HD_BPS -> TestVerdict.GOOD_HD
                rate >= StorageConstants.TEST_SLOW_FACTOR * StorageConstants.HD_BPS -> TestVerdict.SLOW
                else -> TestVerdict.TOO_SLOW
            }
        }
    }
}
