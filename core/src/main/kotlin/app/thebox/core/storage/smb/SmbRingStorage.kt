// WP-S1 replaces this stub (docs/design/m6-storage-locations.md §4; storage locations commit 0, §12.4).
package app.thebox.core.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import java.nio.ByteBuffer

/**
 * The ring file in `<path>/TheBox-rewind/` on a network folder (§4.1), over the keeper's [link]. **No I/O** in
 * construction, `writeHeader`, `allocate`, `close` or `delete` (Must, A-B1): the file is created once by [create] on the
 * flusher (called by `FallbackRingStorage` before the first `pwrite`), reopens use `OPEN_EXISTING` (A-S2), interrupts are
 * cancels (A-B3) and only transport errors reconnect (A-B4). [relativePath] is relative to the share, `/`-separated.
 */
class SmbRingStorage(
    private val link: RemoteLink,
    val relativePath: String,
    override val capacityBytes: Long,
    private val limits: StorageLimits,
    private val clock: MonoClock,
) : RemoteRingStorage {
    override val blocking: Boolean get() = true
    override val gone: Boolean get() = TODO("WP-S1")
    override val lastFailure: StorageFailure? get() = TODO("WP-S1")
    override val needsFreshFile: Boolean get() = TODO("WP-S1")
    override val remoteBytesWritten: Long get() = TODO("WP-S1")

    override fun create(deadlineMonoMs: Long): Boolean = TODO("WP-S1")
    override fun ratesBps(): LongArray = TODO("WP-S1")
    override fun allocate(upToBytes: Long): Long = TODO("WP-S1")
    override fun pwrite(src: ByteBuffer, filePosition: Long): Int = TODO("WP-S1")
    override fun pread(dst: ByteBuffer, filePosition: Long): Int = TODO("WP-S1")
    override fun isUnlinked(): Boolean = TODO("WP-S1")
    override fun availableBytes(): Long = TODO("WP-S1")
    override fun writeHeader(generation: Long, capacity: Long): Unit = TODO("WP-S1")
    override fun delete(): Unit = TODO("WP-S1")
    override fun close(): Unit = TODO("WP-S1")
}
