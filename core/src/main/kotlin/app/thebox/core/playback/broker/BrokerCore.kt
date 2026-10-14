package app.thebox.core.playback.broker

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.MonoClock

/** Leases, pre-emption, waiters, snapshot (§3.5). One per broker key; `ReentrantLock` + `Condition`. */
class ConnectionBrokerCoreImpl(override val key: String, private val clock: MonoClock) : ConnectionBrokerCore {
    override fun configure(maxConnections: Int): Unit = TODO("WP-A")
    override fun preemptPlayback(reason: String): Int = TODO("WP-A")
    override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease = TODO("WP-A")
    override fun snapshot(): BrokerSnapshot = TODO("WP-A")
}
