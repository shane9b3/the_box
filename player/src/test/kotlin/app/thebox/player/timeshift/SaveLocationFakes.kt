// WP-S2 test support (docs/design/m6-storage-locations.md §16.2): remote targets over FakeRemoteLink.
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteFile
import app.thebox.core.storage.smb.SmbRingStorage
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A minimal [RemoteRingStorage] over [FakeRemoteLink] that follows §4.1's no-I/O rules (construction, `writeHeader`,
 * `allocate`, `close`, `delete` touch nothing; close/delete are posted). Used only while WP-S1's [SmbRingStorage] is a
 * stub: [remoteStorage] picks the real class as soon as it has a body, so these tests then drive the real storage.
 */
class NoIoRemoteStorage(
    private val link: FakeRemoteLink,
    val relativePath: String,
    override val capacityBytes: Long,
) : RemoteRingStorage {
    @Volatile override var gone = false
    @Volatile override var lastFailure: StorageFailure? = null
    @Volatile override var needsFreshFile = false
    @Volatile override var remoteBytesWritten = 0L
    @Volatile var closed = false
    @Volatile private var file: RemoteFile? = null
    override val blocking: Boolean get() = true

    override fun create(deadlineMonoMs: Long): Boolean = try {
        val share = link.share(deadlineMonoMs)
        share.mkdirs(relativePath.substringBeforeLast('/', ""))
        file = share.open(relativePath, OpenMode.CREATE_OVERWRITE)
        link.retain()
        true
    } catch (e: IOException) {
        lastFailure = link.classify(e).failure ?: StorageFailure.UNREACHABLE
        false
    }

    override fun ratesBps(): LongArray = longArrayOf(1_000_000L, 0L)
    override fun allocate(upToBytes: Long): Long = minOf(upToBytes, HEADER + capacityBytes)
    override fun pwrite(src: ByteBuffer, filePosition: Long): Int {
        val f = file ?: throw IOException("not created")
        val n = src.remaining()
        f.write(src.array(), src.arrayOffset() + src.position(), n, filePosition)
        src.position(src.limit())
        remoteBytesWritten += n
        return n
    }
    override fun pread(dst: ByteBuffer, filePosition: Long): Int = throw IOException("unused")
    override fun isUnlinked(): Boolean = gone || closed
    override fun availableBytes(): Long = 1L shl 40
    override fun writeHeader(generation: Long, capacity: Long) = Unit
    override fun close() {
        if (closed) return
        closed = true; gone = true
        val f = file
        link.post { f?.closeAsync(); if (f != null) link.release() }
    }
    override fun delete() {
        close()
        link.post { try { link.share(0L).delete(relativePath) } catch (e: IOException) { } }
    }

    private companion object { const val HEADER = 4096L }
}

/** True once WP-S1 filled [SmbRingStorage] (the stub's members throw `NotImplementedError`). */
fun smbRingStorageImplemented(): Boolean = try {
    SmbRingStorage(FakeRemoteLink(), "probe.bin", 1L shl 26, StorageLimits.SMB, MonoClock { 0L }).needsFreshFile
    true
} catch (e: NotImplementedError) {
    false
}

/** The remote storage under test: WP-S1's real [SmbRingStorage] when it exists, else [NoIoRemoteStorage]. */
fun remoteStorage(link: FakeRemoteLink, path: String, capacity: Long, clock: MonoClock): RemoteRingStorage =
    if (smbRingStorageImplemented()) SmbRingStorage(link, path, capacity, StorageLimits.SMB, clock) else NoIoRemoteStorage(link, path, capacity)

/** An SMB choice whose `open` builds a remote storage over [link] and records it in [opened]. */
fun smbChoice(
    link: FakeRemoteLink,
    clock: MonoClock,
    opened: MutableList<RemoteRingStorage>,
    id: String = "smb-1",
    ready: Boolean = true,
    available: Long = 1L shl 40,
): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Remote(id, StorageKind.SMB, StorageLimits.SMB, "NAS", availableBytes = available, ready = ready,
        open = { name, cap -> remoteStorage(link, "${StorageConstants.TIMESHIFT_DIR_SMB}/$name", cap, clock).also { opened += it } }),
    chosenId = id, chosenKind = StorageKind.SMB, fallback = null, lastKnownGood = !ready,
)

/** SMB chosen, this device used at tune time (§5.1), e.g. UNREACHABLE. */
fun smbFellBackChoice(dir: File, reason: LocationFallback, total: Long = 32_000_000_000L): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), "NAS", dir, total, 10_000_000_000L),
    chosenId = "smb-1", chosenKind = StorageKind.SMB, fallback = reason, lastKnownGood = false,
)

fun internalChoice(dir: File, total: Long = 32_000_000_000L): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), "", dir, total, 10_000_000_000L),
    chosenId = StorageConstants.INTERNAL_ID, chosenKind = StorageKind.INTERNAL, fallback = null, lastKnownGood = false,
)

fun volumeChoice(filesDir: File, uuid: String = "ABCD-1234", total: Long = 64L shl 30): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local("${StorageConstants.VOLUME_ID_PREFIX}$uuid", StorageKind.VOLUME, StorageLimits.VOLUME, "USB drive", filesDir, total, 32L shl 30),
    chosenId = "${StorageConstants.VOLUME_ID_PREFIX}$uuid", chosenKind = StorageKind.VOLUME, fallback = null, lastKnownGood = false,
)
