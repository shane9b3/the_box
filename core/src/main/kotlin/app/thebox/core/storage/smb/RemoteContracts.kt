// Contract (M6 storage locations commit 0): docs/design/m6-storage-locations.md §12.2. Verbatim; changes go through the
// lead. Pure JVM: `:data`'s SmbjRemoteLink implements it over smbj; FakeRemoteLink (core testFixtures) for the JVM tests.
package app.thebox.core.storage.smb

import app.thebox.core.storage.StorageFailure

/**
 * One open remote file (§4.1). Writes and reads run concurrently on one handle (§4.2; U-1). Every failure is an
 * IOException the owning [RemoteLink.classify]s (§4.3).
 */
interface RemoteFile : AutoCloseable {
    @Throws(java.io.IOException::class) fun write(src: ByteArray, off: Int, len: Int, fileOffset: Long)
    /** ≥ 1, or −1 at EOF; may be short. */
    @Throws(java.io.IOException::class) fun read(dst: ByteArray, off: Int, len: Int, fileOffset: Long): Int
    /** Posted to box-smb; never waits (A-B1). */
    fun closeAsync()
}

/** CREATE once per storage; reopen = OPEN_EXISTING (A-S2). */
enum class OpenMode { CREATE_OVERWRITE, OPEN_EXISTING }   // CREATE once per storage; reopen = OPEN_EXISTING (A-S2)

/** A connected share at one link [epoch] (§4.3). Paths are `/`-separated, relative to the share. */
interface RemoteShare {
    val epoch: Long
    @Throws(java.io.IOException::class) fun open(path: String, mode: OpenMode, deleteOnClose: Boolean = false): RemoteFile
    @Throws(java.io.IOException::class) fun mkdirs(path: String)
    @Throws(java.io.IOException::class) fun list(path: String, max: Int): List<RemoteEntry>
    @Throws(java.io.IOException::class) fun delete(path: String)
    /**
     * The caller's free space (`getCallerFreeSpace`). The link may answer from the keeper's cache while it is younger
     * than SMB_SPACE_MAX_AGE_MS (§4.1 `availableBytes`); otherwise one bounded live query that refreshes the cache.
     */
    @Throws(java.io.IOException::class) fun freeBytes(): Long
    @Throws(java.io.IOException::class) fun lastWriteMs(path: String): Long        // server time (A-N2)
}

data class RemoteEntry(val name: String, val directory: Boolean, val hidden: Boolean, val sizeBytes: Long)

/** §4.3, in classification order. */
enum class FaultClass { CANCEL, LINK_LOST, SLOW, FULL, ACCESS, REMOVED }        // §4.3, in classification order

/** A classified failure (§4.3). [failure] is what the ring's `lastFailure` and the §10 wording use; CANCEL has none. */
data class RemoteFault(val cls: FaultClass) {
    val failure: StorageFailure? get() = when (cls) {
        FaultClass.CANCEL -> null
        FaultClass.LINK_LOST -> StorageFailure.UNREACHABLE
        FaultClass.SLOW -> StorageFailure.SLOW
        FaultClass.FULL -> StorageFailure.FULL
        FaultClass.ACCESS -> StorageFailure.ACCESS
        FaultClass.REMOVED -> StorageFailure.REMOVED
    }
}

/** One owner of liveness (A-S1, lead ruling 6): the keeper and the storages both go through it. */
interface RemoteLink {
    val epoch: Long
    /** Waits (bounded by [deadlineMonoMs]) for READY; throws IOException(UNREACHABLE) when it passes. */
    @Throws(java.io.IOException::class) fun share(deadlineMonoMs: Long): RemoteShare
    /** Single flight for [failedEpoch]; others wait; the dead Connection is close(true)d first. */
    @Throws(java.io.IOException::class) fun reconnect(failedEpoch: Long, deadlineMonoMs: Long): RemoteShare
    /** CANCEL first (interrupt anywhere in the chain, A-B3), then transport, then timeout, then statuses. */
    fun classify(t: Throwable): RemoteFault
    fun retain(); fun release()                    // open-file refcount (background disconnect, §5.3)
    val ioTimeoutMs: Long                           // SMB_IO_TIMEOUT_MS or _SMALL_POOL_MS (§4.3)
    /**
     * (Commit 0 addition, see the implementation record) Runs [work] on `box-smb` and returns at once: the storage's
     * deferred CLOSE, reference release and `rm` (§4.1 `close`/`delete`, A-B1). [work] must not throw; a failure is
     * logged by fault name and left to the sweep.
     */
    fun post(work: Runnable)
}
