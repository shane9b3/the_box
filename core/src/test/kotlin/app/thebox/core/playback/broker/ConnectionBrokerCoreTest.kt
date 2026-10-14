package app.thebox.core.playback.broker

import app.thebox.core.playback.ConnectionHandle
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionBrokerCoreTest {
    private val broker = ConnectionBrokerCoreImpl("p:1") { 0L }

    private class CountingHandle : ConnectionHandle {
        val cancels = AtomicInteger()
        override fun cancel() {
            cancels.incrementAndGet()
        }
    }

    private val wait = BrokerConstants.PREEMPT_RELEASE_WAIT_MS

    @Test
    fun grantsUnderMax() {
        broker.configure(2)
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val b = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        assertFalse(a.isPreempted || b.isPreempted)
        val s = broker.snapshot()
        assertEquals(2, s.active)
        assertEquals(0, s.closing)
        assertEquals(2, s.max)
        assertEquals(mapOf(LeaseKind.PLAYBACK to 2), s.byKind)
    }

    @Test
    fun preemptingAnotherOwnerCancelsEveryHandleExactlyOnce() {
        val old = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val h1 = CountingHandle()
        val h2 = CountingHandle()
        assertTrue(old.attach(h1))
        assertTrue(old.attach(h2))
        val new = broker.acquire(LeaseKind.PLAYBACK, 2, 0) // grant-after-wait: old never releases here
        assertTrue(old.isPreempted)
        assertFalse(new.isPreempted)
        assertEquals(1, h1.cancels.get())
        assertEquals(1, h2.cancels.get())
        assertEquals(1, broker.preemptPlayback("zap"))   // only the new lease is ACTIVE
        assertEquals(1, h1.cancels.get())
        assertEquals(1, h2.cancels.get())
        assertTrue(new.isPreempted)
    }

    @Test
    fun preemptPlaybackMarksClosingAndClosingCounts() {
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val h = CountingHandle()
        a.attach(h)
        assertEquals(1, broker.preemptPlayback("tune"))
        assertEquals(1, h.cancels.get())
        val s = broker.snapshot()
        assertEquals(0, s.active)
        assertEquals(1, s.closing)
        assertEquals(mapOf(LeaseKind.PLAYBACK to 1), s.byKind)
        assertEquals(0, broker.preemptPlayback("again"))
    }

    @Test
    fun sameOwnerIsNotPreempted() {
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        assertFailsWith<ConnectionLimitException> { broker.acquire(LeaseKind.PLAYBACK, 1, wait) }
        assertFalse(a.isPreempted)
    }

    @Test
    fun waitsForReleaseThenGrantsNormally() {
        val old = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val started = CountDownLatch(1)
        val granted = AtomicReference<CoreLease>()
        val t = Thread {
            started.countDown()
            granted.set(broker.acquire(LeaseKind.PLAYBACK, 2, wait))
        }
        t.start()
        started.await()
        // Release lags 100 ms behind the cancel, like an OkHttp callEnd.
        while (!old.isPreempted) Thread.onSpinWait()
        val t0 = System.nanoTime()
        Thread.sleep(100)
        old.release()
        t.join(2_000)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertNotNull(granted.get())
        assertTrue(elapsedMs < wait, "granted ${elapsedMs}ms after the release started")
        val s = broker.snapshot()
        assertEquals(1, s.active)
        assertEquals(0, s.closing)
    }

    @Test
    fun grantsAfterTheWaitWhenReleaseLags() {
        val old = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val t0 = System.nanoTime()
        val new = broker.acquire(LeaseKind.PLAYBACK, 2, wait)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue(elapsedMs >= wait - 20, "waited only ${elapsedMs}ms")
        assertTrue(old.isPreempted)
        val s = broker.snapshot()
        assertEquals(1, s.active)
        assertEquals(1, s.closing) // count reads max + 1 until the old socket's release lands
        old.release()
        assertEquals(0, broker.snapshot().closing)
        new.release()
        assertEquals(0, broker.snapshot().active)
    }

    @Test
    fun recordingIsNeverPreempted() {
        val rec = broker.acquire(LeaseKind.RECORDING, 1, wait)
        val h = CountingHandle()
        rec.attach(h)
        val e = assertFailsWith<ConnectionLimitException> { broker.acquire(LeaseKind.PLAYBACK, 2, wait) }
        assertEquals(1, e.active)
        assertEquals(1, e.max)
        assertEquals(0, broker.preemptPlayback("tune"))
        assertEquals(0, h.cancels.get())
        assertFalse(rec.isPreempted)
    }

    @Test
    fun probePreemptsPlayback() {
        val play = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        broker.acquire(LeaseKind.PROBE, 2, 0)
        assertTrue(play.isPreempted)
    }

    @Test
    fun previewIsRefusedAtMaxOneAndNeverPreempts() {
        assertFailsWith<ConnectionLimitException> { broker.acquire(LeaseKind.PREVIEW, 1, wait) }
        broker.configure(2)
        val play = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val preview = broker.acquire(LeaseKind.PREVIEW, 2, wait)
        assertFailsWith<ConnectionLimitException> { broker.acquire(LeaseKind.PREVIEW, 3, 0) }
        assertFalse(play.isPreempted)
        assertFalse(preview.isPreempted)
    }

    @Test
    fun attachAfterPreemptOrReleaseReturnsFalse() {
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        broker.preemptPlayback("x")
        assertFalse(a.attach(CountingHandle()))
        val b = broker.acquire(LeaseKind.PLAYBACK, 2, 0)
        b.release()
        assertFalse(b.attach(CountingHandle()))
    }

    @Test
    fun detachedHandlesAreNotCancelled() {
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val h = CountingHandle()
        a.attach(h)
        a.detach(h)
        broker.preemptPlayback("x")
        assertEquals(0, h.cancels.get())
    }

    @Test
    fun releaseIsIdempotent() {
        broker.configure(2)
        val a = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val b = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        a.release()
        a.release()
        assertEquals(1, broker.snapshot().active)
        b.release()
        assertEquals(0, broker.snapshot().active)
    }

    @Test
    fun configureClampsAndTreatsZeroAsUnknown() {
        broker.configure(0)
        assertEquals(1, broker.snapshot().max)
        broker.configure(500)
        assertEquals(BrokerConstants.MAX_CONNECTIONS_CAP, broker.snapshot().max)
        assertEquals(3, BrokerConstants.effectiveMax(3, 7))
        assertEquals(7, BrokerConstants.effectiveMax(null, 7))
        assertEquals(7, BrokerConstants.effectiveMax(0, 7))
        assertEquals(1, BrokerConstants.effectiveMax(null, 0))
        assertEquals(50, BrokerConstants.effectiveMax(null, 999))
    }

    @Test
    fun snapshotCountsWaiters() {
        val old = broker.acquire(LeaseKind.PLAYBACK, 1, wait)
        val inWait = CountDownLatch(1)
        val t = Thread { broker.acquire(LeaseKind.PLAYBACK, 2, 2_000) }
        t.start()
        var seen = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && seen == 0) seen = broker.snapshot().waiting
        assertEquals(1, seen)
        old.release()
        t.join(2_000)
        assertEquals(0, broker.snapshot().waiting)
        inWait.countDown()
    }

    @Test
    fun stressFiftyThreadsNeverDeadlockOrOvercount() {
        val max = 5
        broker.configure(max)
        val threads = 50
        val cycles = 200
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val violations = AtomicInteger()
        val failures = AtomicReference<Throwable?>()
        val limits = AtomicInteger()
        repeat(threads) { owner ->
            pool.execute {
                try {
                    barrier.await()
                    repeat(cycles) {
                        val lease = try {
                            broker.acquire(LeaseKind.PLAYBACK, owner.toLong(), wait)
                        } catch (e: ConnectionLimitException) {
                            limits.incrementAndGet()
                            return@repeat
                        }
                        val s = broker.snapshot()
                        if (s.active > s.max || s.active + s.closing > s.max + s.closing) violations.incrementAndGet()
                        lease.attach(CountingHandle())
                        lease.release()
                    }
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "deadlock: workers did not finish")
        assertNull(failures.get())
        assertEquals(0, violations.get())
        assertEquals(0, limits.get())
        val s = broker.snapshot()
        assertEquals(0, s.active)
        assertEquals(0, s.closing)
        assertEquals(0, s.waiting)
    }
}
