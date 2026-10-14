package app.thebox.core.playback.broker

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.ConnectionHandle
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.MonoClock
import java.util.EnumMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object BrokerConstants {
    const val DEFAULT_MAX_CONNECTIONS = 1
    const val MAX_CONNECTIONS_CAP = 50
    /** How long an acquire waits for pre-empted (CLOSING) leases to release (§3.5.2). Pass it as `waitMs`. */
    const val PREEMPT_RELEASE_WAIT_MS = 300L
    const val LOG_TAG = "StreamBroker"

    /** `max_connections` = playlist override → account → 1; 0 or negative is "unknown"; clamped to 1..50 (§3.5.1). */
    fun effectiveMax(override: Int?, accountMax: Int?): Int {
        val raw = override?.takeIf { it > 0 } ?: accountMax?.takeIf { it > 0 } ?: DEFAULT_MAX_CONNECTIONS
        return clamp(raw)
    }

    fun clamp(max: Int): Int = if (max <= 0) DEFAULT_MAX_CONNECTIONS else minOf(max, MAX_CONNECTIONS_CAP)
}

/**
 * Leases, pre-emption, waiters, snapshot (§3.5). One per broker key; one [ReentrantLock] with a `released` condition.
 *
 * Invariants: `active ≤ max` (a grant needs a free non-CLOSING slot), and `active + closing ≤ max + closing` — the only
 * way past `max` is the grant-after-wait path, which grants while pre-empted sockets are still CLOSING. Handles are
 * cancelled outside the lock (OkHttp `Call.cancel` may run listeners), each exactly once.
 */
class ConnectionBrokerCoreImpl(override val key: String, private val clock: MonoClock) : ConnectionBrokerCore {
    private enum class State { ACTIVE, CLOSING, RELEASED }

    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private val leases = ArrayList<Lease>()
    private var max = BrokerConstants.DEFAULT_MAX_CONNECTIONS
    private var nextId = 1L
    private var waiting = 0

    override fun configure(maxConnections: Int) {
        val m = BrokerConstants.clamp(maxConnections)
        lock.lock()
        try {
            if (m == max) return
            max = m
            released.signalAll()
        } finally {
            lock.unlock()
        }
        BoxLog.d(BrokerConstants.LOG_TAG) { "$key max=$m" }
    }

    override fun preemptPlayback(reason: String): Int {
        val toCancel = ArrayList<ConnectionHandle>()
        var n = 0
        lock.lock()
        try {
            for (l in leases) {
                if (l.state == State.ACTIVE && l.kind == LeaseKind.PLAYBACK) {
                    markClosingLocked(l, toCancel)
                    n++
                }
            }
        } finally {
            lock.unlock()
        }
        cancelAll(toCancel)
        if (n > 0) BoxLog.i(BrokerConstants.LOG_TAG) { "$key preempt n=$n reason=$reason" }
        return n
    }

    override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceAtLeast(0))
        val toCancel = ArrayList<ConnectionHandle>()
        while (true) {
            toCancel.clear()
            var preempted = 0
            lock.lock()
            try {
                if (kind == LeaseKind.PREVIEW && max <= 1) throw limitLocked()
                if (activeLocked() < max && countLocked() < max) return grantLocked(kind, ownerTuneId, afterWait = false)
                if (kind == LeaseKind.PLAYBACK || kind == LeaseKind.PROBE) {
                    for (l in leases) {
                        if (l.state == State.ACTIVE && l.ownerTuneId != ownerTuneId &&
                            (l.kind == LeaseKind.PLAYBACK || l.kind == LeaseKind.PROBE)
                        ) {
                            markClosingLocked(l, toCancel)
                            preempted++
                        }
                    }
                }
                if (preempted == 0) {
                    val closing = closingLocked()
                    if (closing > 0 && remainingNs > 0) {
                        waiting++
                        try {
                            remainingNs = released.awaitNanos(remainingNs)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        } finally {
                            waiting--
                        }
                        continue
                    }
                    if (closing > 0 && activeLocked() < max) return grantLocked(kind, ownerTuneId, afterWait = true)
                    throw limitLocked()
                }
            } finally {
                lock.unlock()
            }
            cancelAll(toCancel)
            BoxLog.i(BrokerConstants.LOG_TAG) { "$key preempt n=$preempted for ${kind.name} tune=$ownerTuneId" }
        }
    }

    override fun snapshot(): BrokerSnapshot {
        lock.lock()
        try {
            val byKind = EnumMap<LeaseKind, Int>(LeaseKind::class.java)
            for (l in leases) byKind[l.kind] = (byKind[l.kind] ?: 0) + 1
            return BrokerSnapshot(key, activeLocked(), closingLocked(), max, waiting, byKind)
        } finally {
            lock.unlock()
        }
    }

    // ------------------------------------------------------------------ internals (lock held)

    private fun countLocked(): Int = leases.size
    private fun activeLocked(): Int = leases.count { it.state == State.ACTIVE }
    private fun closingLocked(): Int = leases.count { it.state == State.CLOSING }
    private fun limitLocked() = ConnectionLimitException(countLocked(), max)

    private fun grantLocked(kind: LeaseKind, owner: Long, afterWait: Boolean): Lease {
        val l = Lease(nextId++, kind, owner, clock.nowMs())
        leases += l
        if (afterWait) {
            BoxLog.i(BrokerConstants.LOG_TAG) { "$key broker.grant_after_wait lease=${l.id} count=${leases.size}/$max" }
        } else {
            BoxLog.d(BrokerConstants.LOG_TAG) { "$key grant lease=${l.id} ${kind.name} tune=$owner count=${leases.size}/$max" }
        }
        return l
    }

    private fun markClosingLocked(l: Lease, out: MutableList<ConnectionHandle>) {
        l.state = State.CLOSING
        l.preempted = true
        out.addAll(l.handles)
        l.handles.clear()
    }

    private fun cancelAll(handles: List<ConnectionHandle>) {
        for (h in handles) {
            try {
                h.cancel()
            } catch (e: RuntimeException) {
                BoxLog.w(BrokerConstants.LOG_TAG, "$key cancel failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private inner class Lease(
        override val id: Long,
        override val kind: LeaseKind,
        override val ownerTuneId: Long,
        val grantedAtMs: Long,
    ) : CoreLease {
        var state = State.ACTIVE
        val handles = ArrayList<ConnectionHandle>(2)

        @Volatile var preempted = false

        override val isPreempted: Boolean get() = preempted

        override fun attach(handle: ConnectionHandle): Boolean {
            lock.lock()
            try {
                if (state != State.ACTIVE) return false
                handles += handle
                return true
            } finally {
                lock.unlock()
            }
        }

        override fun detach(handle: ConnectionHandle) {
            lock.lock()
            try {
                handles.remove(handle)
            } finally {
                lock.unlock()
            }
        }

        override fun release() {
            lock.lock()
            try {
                if (state == State.RELEASED) return
                state = State.RELEASED
                handles.clear()
                leases.remove(this)
                released.signalAll()
            } finally {
                lock.unlock()
            }
            BoxLog.d(BrokerConstants.LOG_TAG) { "$key release lease=$id held=${clock.nowMs() - grantedAtMs}ms" }
        }

        override fun toString(): String = "Lease($id, ${kind.name}, tune=$ownerTuneId)"
    }
}
