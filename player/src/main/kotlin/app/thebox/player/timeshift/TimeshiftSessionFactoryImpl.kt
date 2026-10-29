// M6 time-shift, WP-B (docs/design/m6-timeshift.md §7.1, §8, A-C1, A-C2, A-S1, A-S7, A-N5). Save locations, WP-S2
// (docs/design/m6-storage-locations.md §5.1, §5.4, §5.5, §6; lead rulings 2 and 5; A-B1, A-N5).
package app.thebox.player.timeshift

import android.app.ActivityManager
import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingStorage
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.playback.timeshift.CycleWriteTracking
import app.thebox.core.playback.timeshift.isStorageStop
import app.thebox.core.playback.timeshift.RingWriter
import app.thebox.core.playback.timeshift.RingWriterFactory
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TsIndexer
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.storage.FallbackRingStorage
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.StoragePurpose
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.StorageRates
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TimeshiftSession
import app.thebox.player.internal.TimeshiftSessionFactory
import app.thebox.player.internal.TransportFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * The split factory of §7.1 (A-C1) and the owner of the process's one ring (§2 "Ownership"):
 *
 * - [prepare] (IO, in parallel with the resolve; needs no resolved stream, A-N5): the sweep wait, StatFs, the sizing of
 *   §8.2, then — under one lock — the bounded join of the previous cycle's pump (`PUMP_JOIN_MAX_MS`), and either a
 *   logical reset of the existing ring (zap: `generation++`, the file is reused, §8.3) or a new file and ring when the
 *   capacity changed. The first extent is allocated here (this device only); the RAM pool covers the first seconds
 *   anyway. Answers [TimeshiftPrepared.Off] with the reason for: the setting Off (and then the file is deleted), no
 *   length, too little space (`NO_SPACE`), a storage error (`STORAGE_ERROR`).
 * - [start] (main, after the resolve): builds the writer from the resolved stream under the writer tune id and starts
 *   the pump. A pre-first-byte retry restarts that writer; it never starts a second session on a grant (fix pass B2).
 * - Discard (stop, lifecycle, length Off, trim memory; §8.3): the pump is joined, the ring closed, then the fd closed
 *   and the file unlinked, on a background thread. (Fix pass S3) A discard never runs while a `prepare` is in flight,
 *   a grant is outstanding (prepared, not yet started or [abandon]ed) or a session is open.
 * - (Fix pass S4) A ring whose storage broke (a disk stop made it RAM-only) or whose file was unlinked is rebuilt, not
 *   reused; if the rebuild fails the cycle is `Off(STORAGE_ERROR)`.
 *
 * Save locations (storage locations §5.1, §5.4, §5.5, §6; WP-S2):
 * - [prepare] asks [targets] `current(TIMESHIFT)` (no I/O, never waits) and sizes the cycle for the target: this device
 *   as today; USB with StatFs on the volume folder and the volume limits; SMB with the cached free space (capacity
 *   `min(SMB, this device)` when only last-known-good selected it, so a §5.2 switch fits). A non-internal target without
 *   room falls back to this device (`NO_SPACE_THERE`). The RAM pool and the Auto length come from the **chosen** kind,
 *   once per cycle, fallback included (lead ruling 5). The daily wear cap is wired only when the target counts wear.
 * - **No network I/O on this path** (Must, A-B1): an SMB cycle gets a [FallbackRingStorage] over `target.open(name, …)`;
 *   nothing is allocated, created or written here, and a remote/USB storage's close/delete returns at once. The flusher
 *   creates the file, or switches to this device, at its first write (§5.2).
 * - Reuse at a zap additionally needs the same location, the same pool size, and a storage not gone, not
 *   `needsFreshFile` and (SMB) not switched (§5.1).
 * - A disk stop of a non-internal cycle reaches `targets.onStorageFailure` on the pump thread **before** the session and
 *   the controller see it (A-N5), once per cycle, with the time since that cycle's first write (the §5.4 grace).
 * - [storageFields] copies the target, the fallback, the failure and the folder's rates into each `LocalBufferState`.
 * - [onVolumeGone] (the `VolumeWatcher` receiver): stops recording with STORAGE_EVICTED and posts the fd close.
 */
@Singleton
internal class TimeshiftSessionFactoryImpl(
    private val storage: TimeshiftStorageImpl,
    private val policy: TimeshiftPolicy,
    private val ringFactory: TsRingFactory,
    private val writerFactory: RingWriterFactory,
    private val indexers: Provider<TsIndexer>,
    private val transport: TransportFactory,
    private val clock: MonoClock,
    private val lowRam: Boolean,
    private val background: (Runnable) -> Unit = { r -> Thread(r, "box-ts-discard").apply { isDaemon = true }.start() },
    /** (§5.1) Null → this device only ([InternalRingTargets] over [storage]). */
    targets: RingTargetSource? = null,
    /** `ActivityManager.memoryClass` in MiB: the remote pool size (lead ruling 5). */
    private val memoryClassMb: Int = 0,
    /**
     * (Save-location integration, S1-Q1) Production: the bound source, resolved on the first [prepare] (IO), so building
     * the controller on main never constructs the storage graph (keeper, locations). `TheBoxApplication` builds it off
     * main at start. Used only when [targets] is null.
     */
    targetsLater: (() -> RingTargetSource)? = null,
) : TimeshiftSessionFactory {

    /**
     * Q-D10 (closure fix pass 2026-10-28): `:data`'s `StorageModule` always binds [RingTargetSource] in the one Hilt graph
     * that has this module (the app's; `:player` has no androidTest and `:data`'s test graph has no `:player`), so the
     * source is injected directly; [InternalRingTargets] stays the primary constructor's default for the JVM tests.
     */
    @Inject constructor(
        @ApplicationContext context: Context,
        storage: TimeshiftStorageImpl,
        policy: TimeshiftPolicy,
        ringFactory: TsRingFactory,
        writerFactory: RingWriterFactory,
        indexers: Provider<TsIndexer>,
        transport: TransportFactory,
        clock: MonoClock,
        targets: Provider<RingTargetSource>,
    ) : this(
        storage, policy, ringFactory, writerFactory, indexers, transport, clock,
        lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
        memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0,
        targetsLater = { targets.get() },
    )

    private val targets: RingTargetSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        targets ?: targetsLater?.invoke() ?: InternalRingTargets(storage)
    }

    private val lock = Mutex()
    private val ringLock = Any()
    private var ring: TsRing? = null
    private var ringStorage: RingStorage? = null
    private var ringCapacity = 0L
    private var ringIndexEntries = 0
    private var ringLengthMs = 0L
    /** (§5.1 reuse) The location the ring's storage is on, and the RAM pool it was built with. */
    private var ringLocationId: String? = null
    private var ringPool = 0
    /** The ring and its storage, readable without [ringLock] (main: [storageFields], [onVolumeGone]). */
    @Volatile private var current: Current? = null
    /** The previous session's writer: its pump is joined before the ring is reset (A-S1). */
    @Volatile private var lastWriter: RingWriter? = null
    /** Sessions open now (normally 0 or 1). */
    private val open = LinkedHashSet<TimeshiftSessionImpl>()
    /** (S3) Grants handed out by [prepare] and not yet started or abandoned; a new prepare supersedes them. */
    private val granted = java.util.Collections.newSetFromMap(IdentityHashMap<TimeshiftGrant, Boolean>())
    /** (S3) `prepare` calls in flight (the ring may be reset and not yet granted). */
    private var preparing = 0
    private var discardWhenIdle = false
    private val lengths = IdentityHashMap<TimeshiftGrant, Long>()
    private val limits = IdentityHashMap<TimeshiftGrant, Long?>()
    /** (§5.5) Whether the grant's target counts wear: the daily cap and `limitedToday` are wired only then. */
    private val wear = IdentityHashMap<TimeshiftGrant, Boolean>()

    private class Current(val ring: TsRing, val storage: RingStorage, val locationId: String)

    override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared =
        prepare(settings, maxConnections) { true }

    override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int, needed: () -> Boolean): TimeshiftPrepared = lock.withLock {
        if (!needed()) TimeshiftPrepared.Off(BufferOff.SETTING_OFF) else prepareLocked(settings, needed)
    }

    private suspend fun prepareLocked(settings: PlaybackSettings, needed: () -> Boolean): TimeshiftPrepared {
        // One cycle at a time: a new prepare supersedes every grant not started yet (S3).
        synchronized(ringLock) { granted.clear(); lengths.clear(); limits.clear(); wear.clear(); discardWhenIdle = false }
        if (settings.timeshift == TimeshiftLength.OFF) {
            discardNow()
            return TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
        }
        synchronized(ringLock) { preparing++ }
        var preparingDone = false
        fun donePreparing() {
            if (preparingDone) return
            preparingDone = true
            val discard = synchronized(ringLock) { preparing--; idleLocked() && discardWhenIdle }
            if (discard) background { discardNow() }
        }
        return try {
            storage.awaitSweep()
            val total = storage.totalBytes()
            // §5.1: no I/O, never waits (Must). The pool and the Auto length follow the chosen kind (lead ruling 5).
            val asked = targets.current(StoragePurpose.TIMESHIFT)
            val chosenLimits = StorageLimits.of(asked.chosenKind, total)
            val pool = chosenLimits.ramBlocks(memoryClassMb, lowRam)
            val lengthMs = policy.lengthMs(settings.timeshift, total, chosenLimits)
            if (lengthMs == null || lengthMs <= 0) {
                donePreparing()
                discardNow()
                return TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
            }
            var choice = asked
            var capacity = sizeFor(choice.target, lengthMs, total)
            if (capacity < TimeshiftConstants.MIN_RING_BYTES && choice.target.kind != StorageKind.INTERNAL && TimeshiftDebugOverrides.ringBytes == null) {
                // §5.1 "target space < MIN_RING_BYTES + reserve": this device, NO_SPACE_THERE (the pool stays the chosen one).
                BoxLog.i(TAG) { "time-shift: the ${choice.target.kind} location has no room; saving on this device" }
                choice = choice.copy(target = internalTarget(total, choice.target.name), fallback = LocationFallback.NO_SPACE_THERE)
                capacity = sizeFor(choice.target, lengthMs, total)
            }
            capacity = capacity / TimeshiftConstants.RING_BLOCK_BYTES * TimeshiftConstants.RING_BLOCK_BYTES
            if (capacity < TimeshiftConstants.MIN_RING_BYTES && TimeshiftDebugOverrides.ringBytes == null) {
                BoxLog.i(TAG) { "time-shift off: not enough space (${capacity shr 20} MiB)" }
                donePreparing()
                discardNow()
                return TimeshiftPrepared.Off(BufferOff.NO_SPACE)
            }
            val indexEntries = ceil(lengthMs / 1000.0 * TimeshiftConstants.INDEX_ENTRIES_PER_SEC).toInt() + TimeshiftConstants.INDEX_SLACK_ENTRIES
            val countsWear = choice.target.limits.countsWear
            val dailyLimit = TimeshiftDebugOverrides.dailyLimitBytes?.takeIf { it > 0 } ?: policy.dailyLimitBytes(settings.storageProtection, total)
            // §5.5: the daily cap applies only to a target that counts wear (this device); USB and SMB cycles never cap.
            val mode = policy.mode(settings.timeshift, storage.writtenToday(), if (countsWear) dailyLimit else null, needsPersist = false)
            val hold = TimeshiftDebugOverrides.holdMs ?: policy.holdLimitMs(settings.timeshiftHold)
            if (!needed()) return TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
            val ringT0 = System.nanoTime()
            val before = synchronized(ringLock) { ring }
            val r = ringFor(choice, capacity, indexEntries, lengthMs, pool, total)
            r.mode = mode
            // (Save-location integration, 2026-10-24) one line per prepare: what the zap got and what the ring cost.
            BoxLog.d(TAG) {
                "prepare: ${choice.target.kind} chosen=${choice.chosenId.take(12)} fb=${choice.fallback} pool=$pool cap=${capacity shr 20}MiB " +
                    "${if (r === before) "reuse" else "build"} ${(System.nanoTime() - ringT0) / 1_000_000} ms"
            }
            val grant = TimeshiftGrant(r, capacity, mode, hold, choice)
            synchronized(ringLock) {
                lengths[grant] = lengthMs
                limits[grant] = dailyLimit
                wear[grant] = countsWear
                granted += grant
            }
            donePreparing()
            TimeshiftPrepared.Granted(grant)
        } catch (e: IOException) {
            BoxLog.w(TAG, "time-shift storage failed: ${e.javaClass.simpleName}")
            donePreparing()
            discardNow()
            TimeshiftPrepared.Off(BufferOff.STORAGE_ERROR)
        } finally {
            donePreparing()
        }
    }

    /** (§5.1) The capacity of a cycle on [target]: the per-kind policy with that target's space. No network I/O. */
    private fun sizeFor(target: RingTarget, lengthMs: Long, total: Long): Long {
        TimeshiftDebugOverrides.ringBytes?.let { return it }
        val rate = TimeshiftConstants.ASSUMED_BYTES_PER_SEC
        return when (target) {
            is RingTarget.Local -> if (target.kind == StorageKind.INTERNAL) internalCapacity(lengthMs, total) else {
                val available = storage.spaceOf(storage.volumeRingDir(target)).availableBytes
                val current = synchronized(ringLock) {
                    (ringStorage as? VolumeStorage)?.takeIf { ringLocationId == target.locationId }?.file?.length() ?: 0L
                }
                policy.capacityBytes(lengthMs, rate, available, target.limits, current)
            }
            is RingTarget.Remote -> {
                val current = synchronized(ringLock) {
                    if (ringLocationId != target.locationId) 0L
                    else (ringStorage as? FallbackRingStorage)?.takeIf { it.switchedTo == null }?.let {
                        try {
                            minOf(it.primary.remoteBytesWritten, TimeshiftConstants.RING_HEADER_BYTES + ringCapacity)
                        } catch (e: Exception) {
                            0L
                        }
                    } ?: 0L
                }
                val remote = policy.capacityBytes(lengthMs, rate, target.availableBytes, target.limits, current)
                // §5.1: chosen on last-known-good without READY → a §5.2 switch must fit this device too.
                if (target.ready) remote else {
                    val local = internalCapacity(lengthMs, total)
                    if (local >= TimeshiftConstants.MIN_RING_BYTES) minOf(remote, local) else remote
                }
            }
        }
    }

    /** Today's sizing on this device (TS §8.2). */
    private fun internalCapacity(lengthMs: Long, total: Long): Long {
        val available = TimeshiftDebugOverrides.freeSpaceBytes ?: storage.availableBytes()
        val current = storage.currentFileBytes() // this process's internal ring file (0 when the ring is elsewhere)
        return policy.capacityBytes(lengthMs, TimeshiftConstants.ASSUMED_BYTES_PER_SEC, available, StorageLimits.internal(total), current)
    }

    /** This device as a cycle's target (a §5.1 fallback keeps the chosen location's [name] for the wording). */
    private fun internalTarget(total: Long, name: String = ""): RingTarget.Local = RingTarget.Local(
        locationId = StorageConstants.INTERNAL_ID, kind = StorageKind.INTERNAL, limits = StorageLimits.internal(total),
        name = name, dir = storage.dir, totalBytes = total, availableBytes = 0L,
    )

    /** IO, under [lock]: the previous pump joined, then the ring reset or rebuilt (§3.2 "Generation fence"). */
    private fun ringFor(choice: RingTargetChoice, capacity: Long, indexEntries: Int, lengthMs: Long, pool: Int, total: Long): TsRing {
        // S8: a short join on the zap path; the generation fence covers a pump still inside a lease wait or a connect.
        lastWriter?.let { w ->
            if (!w.awaitExit(TimeshiftConstants.PUMP_JOIN_ZAP_MS)) BoxLog.i(TAG) { "previous pump still exiting; the generation fence covers it" }
        }
        lastWriter = null
        val target = choice.target
        synchronized(ringLock) {
            val existing = ring
            if (existing != null && reusableAfterSwitch(existing, target, capacity, indexEntries, lengthMs, pool)) {
                existing.reset()
                beginCycle(ringStorage)
                return existing
            }
            // S4: a ring made RAM-only by a disk stop, or whose file was unlinked, is rebuilt instead of reused.
            // §5.1: also a storage that is gone, needs a fresh file (A-B4) or switched to this device.
            val broken = existing != null && ((existing as? TsRingImpl)?.storageUsable == false || storageUnusable(ringStorage))
            if (existing != null && !broken && ringCapacity == capacity && ringIndexEntries == indexEntries && ringLengthMs == lengthMs &&
                ringLocationId == target.locationId && ringPool == pool
            ) {
                existing.reset()
                beginCycle(ringStorage)
                return existing
            }
            if (broken) BoxLog.i(TAG) { "ring storage unusable after a disk stop: rebuilding it" }
            // A remote or USB file is never reopened by name, so a rebuild deletes it (its close/delete never waits, A-B1);
            // so is this device's file when the cycle moves elsewhere (it would hold its space for nothing).
            closeRingLocked(deleteFile = broken || ringStorage?.blocking == true || (ringLocationId != null && ringLocationId != target.locationId))
            val st = openFor(target, capacity, pool, total)
            try {
                if (target.kind == StorageKind.INTERNAL) {
                    // The first extent (file bytes, header included, as the ring's allocate calls are). This device only:
                    // USB does not preallocate (§3.2) and SMB does no I/O here (A-B1).
                    st.allocate(TimeshiftConstants.RING_HEADER_BYTES + minOf(capacity, TimeshiftConstants.RING_EXTENT_BLOCKS.toLong() * TimeshiftConstants.RING_BLOCK_BYTES))
                }
                val created = ringFactory.create(st, clock, pool, indexEntries, lengthMs)
                created.reset()
                ring = created
                ringStorage = st
                ringCapacity = capacity
                ringIndexEntries = indexEntries
                ringLengthMs = lengthMs
                ringLocationId = target.locationId
                ringPool = pool
                current = Current(created, st, target.locationId)
                return created
            } catch (e: Throwable) {
                st.delete()
                throw e
            }
        }
    }

    /**
     * C-S3: the previous cycle switched to this device (the folder refused the file or did not answer) and this cycle
     * saves on this device (the folder is demoted, or not ready): keep that ring and its local file rather than a new
     * 10 MiB pool and a new file on every zap. Same pool, length and index; its capacity must fit this cycle's sizing
     * (which did not count the file it already occupies). Under [ringLock]; no I/O but a local `length()`.
     */
    private fun reusableAfterSwitch(existing: TsRing, target: RingTarget, capacity: Long, indexEntries: Int, lengthMs: Long, pool: Int): Boolean {
        if (target.kind != StorageKind.INTERNAL) return false
        val fb = ringStorage as? FallbackRingStorage ?: return false
        if (fb.switchedTo != StorageKind.INTERNAL || fb.isUnlinked() || (existing as? TsRingImpl)?.storageUsable == false) return false
        if (ringIndexEntries != indexEntries || ringLengthMs != lengthMs || ringPool != pool) return false
        val occupied = (fb.secondaryStorage as? AndroidRingStorage)?.file?.length() ?: 0L
        return ringCapacity <= capacity + occupied
    }

    /** §5.1 reuse: a storage that is gone, needs a fresh file (A-B4) or switched to this device is never reused. No I/O. */
    private fun storageUnusable(st: RingStorage?): Boolean = when (st) {
        null -> false
        is FallbackRingStorage -> st.switchedTo != null || st.isUnlinked() || try {
            st.primary.gone || st.primary.needsFreshFile
        } catch (e: Exception) {
            true
        }
        else -> st.isUnlinked()
    }

    private fun beginCycle(st: RingStorage?) { (st as? CycleWriteTracking)?.beginCycle() } // Q-U10

    /** The storage for [target], under [ringLock]. SMB: a [FallbackRingStorage] whose flusher creates the file (A-B1). */
    @Throws(IOException::class)
    private fun openFor(target: RingTarget, capacity: Long, pool: Int, total: Long): RingStorage = when (target) {
        is RingTarget.Local ->
            if (target.kind == StorageKind.INTERNAL) storage.openStorage(capacity)
            else storage.openLocal(target, capacity, preallocate = target.limits.preallocate, countsWear = target.limits.countsWear, closer = background)
        is RingTarget.Remote -> {
            val name = targets.nextRemoteFileName(target.locationId)
            val primary = target.open(name, capacity)
            // Q-U5: the same decision as the pool and the keeper's I/O timeout.
            val deadline = if (TimeshiftConstants.smallRemotePool(memoryClassMb)) StorageConstants.SMB_OPEN_DEADLINE_SMALL_POOL_MS else StorageConstants.SMB_OPEN_DEADLINE_MS
            val local = internalTarget(total)
            FallbackRingStorage(
                primary = primary,
                // On the flusher, only when the folder did not take the first write in time (§5.2): this device, wear counted.
                secondary = { storage.openLocal(local, capacity, preallocate = true, countsWear = true, asFallback = true, closer = background) },
                deadlineMs = deadline,
                clock = clock,
                background = background,
            ).also { fb ->
                // C-S3: a switch reaches the target source (the keeper demotes a folder that refused the file).
                val locationId = target.locationId
                fb.onSwitched = { failure ->
                    try { targets.onCreateFailed(locationId, failure) } catch (e: Exception) {
                        BoxLog.w(TAG, "create failure report failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    override fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession {
        var writer: RingWriter? = null
        var session: TimeshiftSessionImpl? = null
        try {
            val (lengthMs, dailyLimit) = synchronized(ringLock) {
                check(grant in granted) { "time-shift grant is no longer owned" }
                checkNotNull(lengths[grant]) to limits[grant]
            }
            val countsWear = synchronized(ringLock) { wear[grant] ?: true }
            val cur = current?.takeIf { it.ring === grant.ring }
            val indexer = indexers.get().also { it.reset() }
            val baseWriter = writerFactory.create(grant.ring, indexer, clock, "tune-${writerCtx.tuneId}")
            // §5.4, A-N5: a non-internal cycle reports its disk stop to the target source before anyone else sees it.
            val createdWriter = if (cur != null && cur.locationId != StorageConstants.INTERNAL_ID) ReportingWriter(baseWriter, cur) else baseWriter
            writer = createdWriter
            val createdSession = TimeshiftSessionImpl(
                writerTuneId = writerCtx.tuneId,
                grant = grant,
                writer = createdWriter,
                indexer = indexer,
                writerCtx = writerCtx,
                transport = transport,
                policy = policy,
                clock = clock,
                lengthMs = lengthMs,
                onClosed = ::onSessionClosed,
                // §5.5: only a cycle that counts wear (this device, or an SMB cycle switched to it) is "limited today".
                limitedToday = { dailyLimit != null && (countsWear || switchedToInternal(grant)) && storage.writtenToday() >= dailyLimit },
            )
            session = createdSession
            synchronized(ringLock) {
                granted -= grant
                lengths.remove(grant)
                limits.remove(grant)
                wear.remove(grant)
                open += createdSession
            }
            createdSession.start(listener)
            return createdSession
        } catch (e: Exception) {
            // Construction and transport opening can fail on either side of grant consumption. Always return ownership,
            // even when a partial session's close throws before its onClosed callback.
            try {
                if (session != null) session.close(discardFile = true) else writer?.close()
            } catch (closeError: Exception) {
                BoxLog.w(TAG, "failed time-shift start cleanup", closeError)
                try {
                    writer?.close() // session.close may have failed before it reached the pump's close
                } catch (writerError: Exception) {
                    BoxLog.w(TAG, "failed time-shift writer cleanup", writerError)
                }
            } finally {
                if (writer != null) lastWriter = writer
                synchronized(ringLock) { open.remove(session) }
                abandon(grant, discardFile = true)
                if (session != null || writer != null) discardUnused()
            }
            throw e
        }
    }

    private fun onSessionClosed(session: TimeshiftSessionImpl, writer: RingWriter, discardFile: Boolean) {
        lastWriter = writer
        val idle = synchronized(ringLock) {
            open -= session
            discardWhenIdle = discardWhenIdle || discardFile
            idleLocked() && discardWhenIdle
        }
        storage.flushCounter()
        if (idle) background { discardNow() }
    }

    override fun abandon(grant: TimeshiftGrant, discardFile: Boolean) {
        val idle = synchronized(ringLock) {
            val owned = granted.remove(grant)
            lengths.remove(grant)
            limits.remove(grant)
            wear.remove(grant)
            // A superseded grant cannot impose a delayed discard on the newer cycle sharing this ring.
            if (owned) discardWhenIdle = discardWhenIdle || discardFile
            idleLocked() && discardWhenIdle
        }
        if (idle) background { discardNow() }
    }

    /** No session open, no grant outstanding and no prepare in flight (S3). Under [ringLock]. */
    private fun idleLocked(): Boolean = open.isEmpty() && granted.isEmpty() && preparing == 0

    /** `onTrimMemory(TRIM_MEMORY_COMPLETE)`: delete the file unless a buffered cycle is using it. */
    fun discardIfIdle() {
        val idle = synchronized(ringLock) { idleLocked() }
        if (idle) background { discardNow() }
    }

    override fun discardUnused() {
        val idle = synchronized(ringLock) { discardWhenIdle = true; idleLocked() }
        if (idle) background { discardNow() }
    }

    /** IO: joins the last pump, closes the ring, closes the fd, then deletes the file (§7.5, §8.3). */
    internal fun discardNow() {
        lastWriter?.awaitExit(TimeshiftConstants.PUMP_JOIN_MAX_MS)
        lastWriter = null
        synchronized(ringLock) {
            // S3: never under a cycle that holds a grant or is preparing one.
            if (!idleLocked()) return
            discardWhenIdle = false
            closeRingLocked(deleteFile = true)
            // Keep deletion behind the same fence as creation: a new prepare must not create a file between these.
            storage.deleteRingFile()
        }
    }

    private fun closeRingLocked(deleteFile: Boolean) {
        val r = ring
        val st = ringStorage
        ring = null
        ringStorage = null
        ringCapacity = 0
        ringIndexEntries = 0
        ringLengthMs = 0L
        ringLocationId = null
        ringPool = 0
        current = null
        lengths.clear()
        limits.clear()
        wear.clear()
        try {
            r?.close()
        } catch (t: Throwable) {
            BoxLog.w(TAG, "ring close failed", t)
        }
        // A blocking (USB/SMB) storage returns at once and finishes on its own thread (A-B1).
        if (st != null) {
            try {
                if (deleteFile) st.delete() else st.close()
            } catch (e: IOException) {
                BoxLog.w(TAG, "ring storage close failed", e)
            }
        }
    }

    /**
     * Q-D1 (lead ruling R2): the guide's preview start → the target source's §5.3 trigger (the keeper's connect and the
     * `.wake` write when a network folder is kept). IO (the first call may resolve the source); never blocks on the network.
     */
    override fun warmSaveLocation() = targets.warm()

    private fun switchedToInternal(grant: TimeshiftGrant): Boolean =
        (current?.takeIf { it.ring === grant.ring }?.storage as? FallbackRingStorage)?.switchedTo == StorageKind.INTERNAL

    /**
     * (§5.5, §10) [snap] with the cycle's storage fields: where it saves (after a §5.2 switch: INTERNAL), why it falls
     * back, why a remote/USB ring stopped, the chosen location's id and name, the folder's rates. Main, ≤ 1 Hz; no I/O.
     */
    override fun storageFields(grant: TimeshiftGrant, snap: LocalBufferState): LocalBufferState {
        val choice = grant.choice
        val st = current?.takeIf { it.ring === grant.ring }?.storage
        val fb = st as? FallbackRingStorage
        val kind = if (fb != null) fb.switchedTo ?: StorageKind.SMB else choice.target.kind
        val stop = snap.stop
        val failure = if (stop != null && stop.isStorageStop) failureOf(st, stop) else null
        val rates = if (fb != null && fb.switchedTo == null) {
            try {
                fb.primary.ratesBps().let { StorageRates(it.getOrElse(0) { 0L }, it.getOrElse(1) { 0L }) }
            } catch (e: Exception) {
                StorageRates.NONE
            }
        } else StorageRates.NONE
        return snap.copy(
            storage = kind,
            storageFailure = failure,
            fallback = choice.fallback ?: fb?.fallbackReason,
            storageName = choice.target.name.takeIf { it.isNotEmpty() && choice.chosenId != StorageConstants.INTERNAL_ID },
            locationId = choice.chosenId,
            storageRatesBps = rates,
        )
    }

    /** The §10 failure of a storage stop on [st]; null on this device (today's wording). */
    private fun failureOf(st: RingStorage?, stop: RecordStop): StorageFailure? = when (st) {
        is FallbackRingStorage -> if (st.switchedTo != null) null else {
            val last = try { st.primary.lastFailure } catch (e: Exception) { null }
            when {
                stop == RecordStop.DISK_TOO_SLOW -> StorageFailure.SLOW
                last != null -> last
                stop == RecordStop.STORAGE_EVICTED -> StorageFailure.REMOVED
                else -> StorageFailure.UNREACHABLE
            }
        }
        is VolumeStorage -> when {
            stop == RecordStop.DISK_TOO_SLOW -> StorageFailure.SLOW
            stop == RecordStop.STORAGE_EVICTED || st.ejected -> StorageFailure.REMOVED
            else -> null
        }
        else -> null
    }

    /**
     * (§3.2 "Removal", §6) The volume [uuid] (or the one mounted at [mountPath]) is going away. When it holds the ring:
     * `stopRecording(STORAGE_EVICTED)` (cheap, takes the ring's lock) and the fd close posted at once. Any thread (the
     * receiver runs on main); never waits on I/O. True when the ring was on that volume.
     */
    fun onVolumeGone(uuid: String?, mountPath: String? = null): Boolean {
        val cur = current ?: return false
        val vs = cur.storage as? VolumeStorage ?: return false
        val match = (uuid != null && vs.uuid == uuid) || (mountPath != null && vs.file.path.startsWith(mountPath.trimEnd('/') + "/"))
        if (!match) return false
        BoxLog.i(TAG) { "time-shift: the USB drive holding the ring is going away" }
        cur.ring.stopRecording(RecordStop.STORAGE_EVICTED)
        vs.eject()
        return true
    }

    /**
     * The writer of a non-internal cycle (§5.4, A-N5): its listener reports a disk stop to [targets] synchronously, on
     * the pump thread, before the session records it and before the controller (main) can publish it. Once per cycle.
     */
    private inner class ReportingWriter(private val inner: RingWriter, private val cur: Current) : RingWriter by inner {
        @Volatile private var reported = false

        override fun start(upstream: TsUpstream, listener: RingWriterListener) = inner.start(upstream, object : RingWriterListener by listener {
            override fun onStopped(reason: RecordStop) {
                if (reason.isStorageStop && !reported) {
                    reported = true
                    report(reason)
                }
                listener.onStopped(reason)
            }
        })

        private fun report(reason: RecordStop) {
            val st = cur.storage
            // A cycle already switched to this device is not the folder's failure (§5.2).
            if (st is FallbackRingStorage && st.switchedTo != null) return
            // C-B1: a stop before the decision is not reported, and the decision then ends without a fallback announcement:
            // the viewer gets this stop's one toast, never a second "saving on this device" for a cycle that saves nowhere.
            if (st is FallbackRingStorage && st.abandonUndecided()) {
                BoxLog.i(TAG) { "$reason before the network folder answered: not the folder's failure" }
                return
            }
            val first = (st as? CycleWriteTracking)?.firstWriteMonoMs ?: -1L // Q-U10
            // No write yet in this cycle: the stop started inside the grace (§5.4).
            val since = if (first >= 0) (clock.nowMs() - first).coerceAtLeast(0L) else 0L
            try {
                targets.onStorageFailure(cur.locationId, reason, failureOf(st, reason), since)
            } catch (e: Exception) {
                BoxLog.w(TAG, "storage failure report failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Tests: the ring this process holds now. */
    internal fun currentRing(): TsRing? = synchronized(ringLock) { ring }

    /** Tests: the ring's storage now. */
    internal fun currentStorage(): RingStorage? = synchronized(ringLock) { ringStorage }

    internal val mode: BufferMode? get() = synchronized(ringLock) { ring?.mode }

    private companion object {
        const val TAG = "TimeshiftFactory"
    }
}

/** The factory a controller gets when time-shift is not wired (tests, commit 0): every cycle is unbuffered. */
internal object NoTimeshiftFactory : TimeshiftSessionFactory {
    override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared = TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
    override fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession =
        throw IllegalStateException("time-shift is not available")
}
