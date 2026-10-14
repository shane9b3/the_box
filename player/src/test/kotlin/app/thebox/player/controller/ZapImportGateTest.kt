package app.thebox.player.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** §5.3 WP-D `ZapImportGate`: closed at tune, opens at first media + 500 ms or 3 s, `awaitClear` bounded. Plain JVM. */
class ZapImportGateTest {
    private val now = AtomicLong(1_000)
    private val gate = ZapImportGate({ now.get() }, maxWaitMs = 300)

    @Test
    fun openUntilAZapClosesIt() {
        assertFalse(gate.isClosed)
        gate.closeForZap()
        assertTrue(gate.isClosed)
    }

    @Test
    fun opensThreeSecondsAfterTheTuneAtTheLatest() {
        gate.closeForZap()
        now.addAndGet(2_999)
        assertTrue(gate.isClosed)
        now.addAndGet(1)
        assertFalse(gate.isClosed)
    }

    @Test
    fun opensFiveHundredMsAfterFirstMedia() {
        gate.closeForZap()
        now.addAndGet(800)
        gate.releaseAfterFirstMedia()
        now.addAndGet(499)
        assertTrue(gate.isClosed)
        now.addAndGet(1)
        assertFalse(gate.isClosed)
    }

    @Test
    fun errorOrStopOpensAtOnceAndWakesTheImporter() {
        gate.closeForZap()
        val t0 = System.nanoTime()
        val waiter = thread { gate.awaitClear() }
        Thread.sleep(50)
        gate.open()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 290)
    }

    @Test
    fun awaitClearIsBoundedPerCall() {
        gate.closeForZap() // the fake clock never advances: only the per-call bound can release the importer
        val t0 = System.nanoTime()
        gate.awaitClear()
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $waited ms", waited in 250..2_000)
    }

    @Test
    fun awaitClearReturnsAtOnceWhenOpen() {
        val t0 = System.nanoTime()
        gate.awaitClear()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 100)
    }
}
