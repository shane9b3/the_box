package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.MemoryRingStorage
import app.thebox.core.playback.timeshift.RingFlusher
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.storage.StorageLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** §16.1 `DedicatedFlusherTest` (WP-S1, A-B2): one flusher thread per remote ring, never shared, gone after close. */
class DedicatedFlusherTest {
    private val block = RING_BLOCK_BYTES.toLong()

    @Test
    fun aStalledSmbRingNeverDelaysANewInternalRingAndItsThreadExitsAfterClose() {
        val link = FakeRemoteLink()
        val st = SmbRingStorage(link, "TheBox-rewind/ring-abcdef012345-1-1.bin", 128 * block, StorageLimits.SMB, link.clock)
        assertTrue(st.create(link.clock.nowMs() + 5_000))
        val smb = TsRingImpl(st, link.clock, 64, 4_096)
        val own = smb.ringFlusher
        assertNotNull(own)
        assertNotSame(RingFlusher.shared, own, "a blocking storage gets its own flusher")
        assertTrue(own.owned)
        val smbPump = PatternPump(smb, 1_000_000L, "smb-pump").also { it.start() }
        waitUntil("smb flushing", 3_000) { smb.flushed > block }
        assertEquals(RingFlusher.NET_THREAD, own.liveThread?.name)

        // The folder stalls inside a pwrite (a NAS disk asleep). The next cycle zaps to this device.
        link.stallUntil(link.clock.nowMs() + 4_000)
        Thread.sleep(200)
        val mem = MemoryRingStorage(128 * block)
        val internal = TsRingImpl(mem, link.clock, 8, 4_096)
        assertSame(RingFlusher.shared, internal.ringFlusher, "an internal ring shares box-ts-flush")
        val pump = PatternPump(internal, 2_000_000L, "internal-pump").also { it.start() }
        val t0 = System.nanoTime()
        waitUntil("internal flushes during the SMB stall", 2_000) { internal.flushed >= 4 * block }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertNull(internal.stop)

        // Close the SMB cycle while its flusher is still stuck: the thread ends once the pwrite returns.
        smbPump.halt()
        smb.close(); st.close()
        val thread = own.liveThread
        waitUntil("owned flusher exit", 6_000) { thread == null || !thread.isAlive }
        assertNull(own.liveThread)
        assertTrue(RingFlusher.shared.liveThread?.isAlive == true, "the shared flusher lives on")
        pump.halt(); internal.close()
    }

    @Test
    fun anOwnedFlusherRestartsWhenARingRegistersAgain() {
        val f = RingFlusher("box-ts-flush-test", owned = true)
        val mem = MemoryRingStorage(16 * block)
        val r1 = TsRingImpl(mem, link().clock, 4, 256, f, 0L)
        val t1 = f.liveThread
        assertNotNull(t1)
        r1.close()
        waitUntil("exit", 2_000) { f.liveThread == null }
        val r2 = TsRingImpl(MemoryRingStorage(16 * block), link().clock, 4, 256, f, 0L)
        assertNotNull(f.liveThread)
        r2.close()
        waitUntil("exit again", 2_000) { f.liveThread == null }
    }

    private fun link() = FakeRemoteLink()
}
