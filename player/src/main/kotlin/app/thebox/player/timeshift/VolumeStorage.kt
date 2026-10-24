// WP-S2 (docs/design/m6-storage-locations.md §3.2, §3.4, §6; A-S4, A-S8, A-B2).
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.storage.StorageLimits
import java.io.File
import java.nio.ByteBuffer

/**
 * The ring file on a USB drive or SD card: `<volume files dir>/timeshift/ring-<pid>-<n>.bin` (§3.2, §3.5), over an
 * [AndroidRingStorage] built with `preallocate = false`, `blocking = true` and a posted close.
 *
 * - [blocking] → its own `box-ts-flush-ext` flusher (A-B2): a slow stick never delays an internal ring.
 * - [availableBytes] keeps the volume reserve (§3.4, A-S4): `free − (VOLUME_FREE_SPACE_RESERVE − FREE_SPACE_RESERVE_MIN/2)`.
 * - [isUnlinked]: an explicit [eject] always; `fstat` nlink only when [nlinkReliable] (nlink was 1 right after create,
 *   A-S8). Some FUSE/vfat mounts report 0 or a constant; then only the eject answers.
 * - [eject] (the `VolumeWatcher` receiver, main): marks the storage gone and posts the fd close at once, never on the
 *   caller's thread (vold kills processes that keep files open on a volume it unmounts).
 * - No wear counter: USB wear belongs to a device the owner chose (§3.4 "Why").
 */
internal class VolumeStorage(
    /** The volume's uuid (the location id without `vol-`). */
    val uuid: String,
    private val delegate: AndroidRingStorage,
    private val space: () -> Long,
    val nlinkReliable: Boolean,
    private val limits: StorageLimits = StorageLimits.VOLUME,
    private val clock: MonoClock = MonoClock { System.nanoTime() / 1_000_000L },
) : RingStorage {
    val file: File get() = delegate.file

    @Volatile var ejected = false; private set

    /** Mono ms of the current cycle's first successful write, −1 until then (the factory's §5.4 report). */
    @Volatile var firstWriteMonoMs: Long = -1L; private set

    fun beginCycle() { firstWriteMonoMs = -1L }

    override val capacityBytes: Long get() = delegate.capacityBytes
    override val blocking: Boolean get() = true

    override fun allocate(upToBytes: Long): Long = delegate.allocate(upToBytes)

    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        val n = delegate.pwrite(src, filePosition)
        if (n > 0 && firstWriteMonoMs < 0) firstWriteMonoMs = clock.nowMs()
        return n
    }

    override fun pread(dst: ByteBuffer, filePosition: Long): Int = delegate.pread(dst, filePosition)

    override fun isUnlinked(): Boolean {
        if (ejected) return true
        return nlinkReliable && delegate.isUnlinked()
    }

    override fun availableBytes(): Long = try { limits.reportedAvailableBytes(space()) } catch (e: Exception) { 0L }

    override fun writeHeader(generation: Long, capacity: Long) = delegate.writeHeader(generation, capacity)

    /** The drive is going away: gone at once, the fd closed on the posted executor. Any thread; never waits. */
    fun eject() {
        ejected = true
        delegate.close()
    }

    override fun delete() = delegate.delete()

    override fun close() = delegate.close()
}
