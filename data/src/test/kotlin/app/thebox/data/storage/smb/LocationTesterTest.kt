package app.thebox.data.storage.smb

import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.TestPhase
import app.thebox.core.storage.TestProgress
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.smb.FakeRemoteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** §7.8 (WP-S1): the Test's phases, its grading table, its failures and Stop. */
class LocationTesterTest {
    private val far = Long.MAX_VALUE / 2

    private fun tester(link: FakeRemoteLink) = LocationTester(link.clock, "abcdef012345", 77, { link.classify(it).cls }, wifi = true, nowWallMs = { 42L })

    @Test fun gradeTable() {
        val hd = StorageConstants.HD_BPS; val uhd = StorageConstants.UHD_BPS
        assertEquals(TestVerdict.GOOD_4K, LocationTester.grade((3 * uhd).toLong(), 4))
        assertEquals(TestVerdict.GOOD_HD, LocationTester.grade((3 * uhd - 1).toLong(), 4))
        assertEquals(TestVerdict.GOOD_HD, LocationTester.grade(3 * hd, 40))
        assertEquals(TestVerdict.SLOW, LocationTester.grade(3 * hd - 1, 4))
        assertEquals(TestVerdict.SLOW, LocationTester.grade(10 * uhd, 41)) // the RTT cap
        assertEquals(TestVerdict.SLOW, LocationTester.grade((1.5 * hd).toLong(), 4))
        assertEquals(TestVerdict.TOO_SLOW, LocationTester.grade((1.5 * hd).toLong() - 1, 4))
        assertEquals(TestVerdict.TOO_SLOW, LocationTester.grade(hd, 100))
    }

    @Test fun aFastFolderIsGoodAndTheTestFileIsDeleted() {
        val link = FakeRemoteLink()
        val share = link.share(far)
        share.mkdirs("Rewind")
        val phases = CopyOnWriteArrayList<TestProgress>()
        val r = tester(link).run(share, "Rewind", phases::add)
        assertEquals(TestVerdict.GOOD_4K, r.verdict)
        assertTrue(r.writeBps > 0 && r.readBps > 0)
        assertTrue(r.wifi)
        assertEquals(42L, r.atWallMs)
        assertEquals(listOf(TestPhase.CONNECTING, TestPhase.WAKING, TestPhase.WRITING, TestPhase.READING, TestPhase.RTT, TestPhase.DONE), phases.map { it.phase }.distinct())
        assertEquals(StorageConstants.SMB_TEST_MAX_BYTES / 65_424 * 65_424, phases.last { it.phase == TestPhase.WRITING }.doneBytes)
        assertTrue(link.exists("Rewind/TheBox-rewind/.wake-abcdef012345"))
        Thread.sleep(100) // the posted close
        assertFalse("FILE_DELETE_ON_CLOSE removed the test file", share.list("Rewind/TheBox-rewind", 100).any { it.name.startsWith("test-") })
    }

    @Test fun aSlowFolderIsGradedOnWhatWasMeasured() {
        val link = FakeRemoteLink()
        val share = link.share(far)
        share.mkdirs("Rewind")
        link.latencyMs = 50 // ≈ 1.3 MB/s per 64 KiB block
        val t0 = System.nanoTime()
        val r = tester(link).run(share, "Rewind") {}
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("time-bounded: $ms ms", ms < StorageConstants.TEST_WRITE_MS + StorageConstants.TEST_READ_MS + 3_000)
        assertTrue(r.verdict == TestVerdict.TOO_SLOW || r.verdict == TestVerdict.SLOW)
        assertTrue(r.rttMs >= 50)
    }

    @Test fun refusedWritesAndNoSpaceFail() {
        run {
            val link = FakeRemoteLink(); val share = link.share(far); share.mkdirs("Rewind")
            link.denyWrites()
            val r = tester(link).run(share, "Rewind") {}
            assertEquals(TestVerdict.FAILED, r.verdict)
            assertEquals(BrowseErrorKind.WRITE_REFUSED, r.error!!.kind)
        }
        run {
            val link = FakeRemoteLink(); val share = link.share(far); share.mkdirs("Rewind")
            link.fullAt(1L shl 30) // 1 GiB: under MIN_RING_BYTES + the 2 GiB reserve
            val r = tester(link).run(share, "Rewind") {}
            assertEquals(TestVerdict.FAILED, r.verdict)
            assertEquals(StorageFailure.FULL, r.error!!.failure)
            assertNotNull(r.error!!.needBytes)
        }
    }

    @Test fun stopTestInterrupts() {
        val link = FakeRemoteLink()
        val share = link.share(far); share.mkdirs("Rewind")
        link.latencyMs = 30
        val err = AtomicReference<Throwable?>()
        val t = Thread { try { tester(link).run(share, "Rewind") {} } catch (e: Throwable) { err.set(e) } }
        t.start(); Thread.sleep(300); t.interrupt(); t.join(3_000)
        assertTrue("${err.get()}", err.get() is InterruptedIOException)
    }
}
