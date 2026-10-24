// WP-S2 replaces this stub (docs/design/m6-storage-locations.md §5.2; storage locations commit 0, §12.4).
package app.thebox.core.storage

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingStorage
import java.nio.ByteBuffer

/**
 * The [RingStorage] the ring sees for every SMB cycle (§5.2, lead ruling 2): the first `pwrite` (flusher) calls
 * `primary.create(now + deadlineMs)`; true → delegate to [primary] from then on; false (deadline or any failure, nothing
 * has reached the folder) → close the primary (posted), build [secondary] on the flusher (this device, with the wear
 * counter), write the header there and delegate to it; [switchedTo] = INTERNAL, [fallbackReason] from the primary's
 * `lastFailure`. No switch after a successful create. [blocking] is true, so the ring keeps its dedicated flusher.
 *
 * [deadlineMs] is SMB_OPEN_DEADLINE_MS (160-block pool) or SMB_OPEN_DEADLINE_SMALL_POOL_MS (96). No I/O in the
 * constructor (A-B1).
 */
class FallbackRingStorage(
    val primary: RemoteRingStorage,
    private val secondary: () -> RingStorage,
    private val deadlineMs: Long,
    private val clock: MonoClock,
) : RingStorage {
    /** Null until the switch; then the kind written to ([StorageKind.INTERNAL]). */
    @Volatile var switchedTo: StorageKind? = null; private set
    /** Why the cycle switched (UNREACHABLE, SIGN_IN_NEEDED, NO_SPACE_THERE); null while not switched. */
    @Volatile var fallbackReason: LocationFallback? = null; private set

    override val capacityBytes: Long get() = primary.capacityBytes
    override val blocking: Boolean get() = true

    override fun allocate(upToBytes: Long): Long = TODO("WP-S2")
    override fun pwrite(src: ByteBuffer, filePosition: Long): Int = TODO("WP-S2")
    override fun pread(dst: ByteBuffer, filePosition: Long): Int = TODO("WP-S2")
    override fun isUnlinked(): Boolean = TODO("WP-S2")
    override fun availableBytes(): Long = TODO("WP-S2")
    override fun writeHeader(generation: Long, capacity: Long): Unit = TODO("WP-S2")
    override fun delete(): Unit = TODO("WP-S2")
    override fun close(): Unit = TODO("WP-S2")
}
