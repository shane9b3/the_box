// M6 time-shift, WP-B (docs/design/m6-timeshift.md §7.1, §8, A-C1, A-C2, A-S1, A-S7, A-N5).
package app.thebox.player.timeshift

import android.app.ActivityManager
import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RingWriter
import app.thebox.core.playback.timeshift.RingWriterFactory
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TsIndexer
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.settings.TimeshiftLength
import app.thebox.player.api.PlaybackSettings
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
 *   capacity changed. The first extent is allocated here; the RAM pool covers the first seconds anyway. Answers
 *   [TimeshiftPrepared.Off] with the reason for: the setting Off (and then the file is deleted), no length, too little
 *   space (`NO_SPACE`), a storage error (`STORAGE_ERROR`).
 * - [start] (main, after the resolve): builds the writer from the resolved stream under the writer tune id and starts
 *   the pump. A pre-first-byte retry restarts that writer; it never starts a second session on a grant (fix pass B2).
 * - Discard (stop, lifecycle, length Off, trim memory; §8.3): the pump is joined, the ring closed, then the fd closed
 *   and the file unlinked, on a background thread. (Fix pass S3) A discard never runs while a `prepare` is in flight,
 *   a grant is outstanding (prepared, not yet started or [abandon]ed) or a session is open.
 * - (Fix pass S4) A ring whose storage broke (a disk stop made it RAM-only) or whose file was unlinked is rebuilt, not
 *   reused; if the rebuild fails the cycle is `Off(STORAGE_ERROR)`.
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
) : TimeshiftSessionFactory {

    @Inject constructor(
        @ApplicationContext context: Context,
        storage: TimeshiftStorageImpl,
        policy: TimeshiftPolicy,
        ringFactory: TsRingFactory,
        writerFactory: RingWriterFactory,
        indexers: Provider<TsIndexer>,
        transport: TransportFactory,
        clock: MonoClock,
    ) : this(
        storage, policy, ringFactory, writerFactory, indexers, transport, clock,
        lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
    )

    private val lock = Mutex()
    private val ringLock = Any()
    private var ring: TsRing? = null
    private var ringStorage: AndroidRingStorage? = null
    private var ringCapacity = 0L
    private var ringIndexEntries = 0
    private var ringLengthMs = 0L
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

    override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared =
        prepare(settings, maxConnections) { true }

    override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int, needed: () -> Boolean): TimeshiftPrepared = lock.withLock {
        if (!needed()) TimeshiftPrepared.Off(BufferOff.SETTING_OFF) else prepareLocked(settings, needed)
    }

    private suspend fun prepareLocked(settings: PlaybackSettings, needed: () -> Boolean): TimeshiftPrepared {
        // One cycle at a time: a new prepare supersedes every grant not started yet (S3).
        synchronized(ringLock) { granted.clear(); lengths.clear(); limits.clear(); discardWhenIdle = false }
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
            val lengthMs = policy.lengthMs(settings.timeshift, total)
            if (lengthMs == null || lengthMs <= 0) {
                donePreparing()
                discardNow()
                return TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
            }
            val available = TimeshiftDebugOverrides.freeSpaceBytes ?: storage.availableBytes()
            val sized = TimeshiftDebugOverrides.ringBytes
                ?: policy.capacityBytes(lengthMs, TimeshiftConstants.ASSUMED_BYTES_PER_SEC, available, total, storage.currentFileBytes())
            val capacity = sized / TimeshiftConstants.RING_BLOCK_BYTES * TimeshiftConstants.RING_BLOCK_BYTES
            if (capacity < TimeshiftConstants.MIN_RING_BYTES && TimeshiftDebugOverrides.ringBytes == null) {
                BoxLog.i(TAG) { "time-shift off: not enough space (${capacity shr 20} MiB)" }
                donePreparing()
                discardNow()
                return TimeshiftPrepared.Off(BufferOff.NO_SPACE)
            }
            val indexEntries = ceil(lengthMs / 1000.0 * TimeshiftConstants.INDEX_ENTRIES_PER_SEC).toInt() + TimeshiftConstants.INDEX_SLACK_ENTRIES
            val dailyLimit = TimeshiftDebugOverrides.dailyLimitBytes?.takeIf { it > 0 } ?: policy.dailyLimitBytes(settings.storageProtection, total)
            val mode = policy.mode(settings.timeshift, storage.writtenToday(), dailyLimit, needsPersist = false)
            val hold = TimeshiftDebugOverrides.holdMs ?: policy.holdLimitMs(settings.timeshiftHold)
            if (!needed()) return TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
            val r = ringFor(capacity, indexEntries, lengthMs)
            r.mode = mode
            val grant = TimeshiftGrant(r, capacity, mode, hold)
            synchronized(ringLock) {
                lengths[grant] = lengthMs
                limits[grant] = dailyLimit
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

    /** IO, under [lock]: the previous pump joined, then the ring reset or rebuilt (§3.2 "Generation fence"). */
    private fun ringFor(capacity: Long, indexEntries: Int, lengthMs: Long): TsRing {
        // S8: a short join on the zap path; the generation fence covers a pump still inside a lease wait or a connect.
        lastWriter?.let { w ->
            if (!w.awaitExit(TimeshiftConstants.PUMP_JOIN_ZAP_MS)) BoxLog.i(TAG) { "previous pump still exiting; the generation fence covers it" }
        }
        lastWriter = null
        synchronized(ringLock) {
            val existing = ring
            // S4: a ring made RAM-only by a disk stop, or whose file was unlinked, is rebuilt instead of reused.
            val broken = existing != null && ((existing as? TsRingImpl)?.storageUsable == false || ringStorage?.isUnlinked() == true)
            if (existing != null && !broken && ringCapacity == capacity && ringIndexEntries == indexEntries && ringLengthMs == lengthMs) {
                existing.reset()
                return existing
            }
            if (broken) BoxLog.i(TAG) { "ring storage unusable after a disk stop: rebuilding it" }
            closeRingLocked(deleteFile = broken)
            val st = storage.openStorage(capacity)
            try {
                // The first extent (file bytes, header included, as the ring's allocate calls are).
                st.allocate(TimeshiftConstants.RING_HEADER_BYTES + minOf(capacity, TimeshiftConstants.RING_EXTENT_BLOCKS.toLong() * TimeshiftConstants.RING_BLOCK_BYTES))
                val blocks = if (lowRam) TimeshiftConstants.RING_RAM_BLOCKS_LOW_RAM else TimeshiftConstants.RING_RAM_BLOCKS
                val created = ringFactory.create(st, clock, blocks, indexEntries, lengthMs)
                created.reset()
                ring = created
                ringStorage = st
                ringCapacity = capacity
                ringIndexEntries = indexEntries
                ringLengthMs = lengthMs
                return created
            } catch (e: Throwable) {
                st.delete()
                throw e
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
            val indexer = indexers.get().also { it.reset() }
            val createdWriter = writerFactory.create(grant.ring, indexer, clock, "tune-${writerCtx.tuneId}")
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
                limitedToday = { dailyLimit != null && storage.writtenToday() >= dailyLimit },
            )
            session = createdSession
            synchronized(ringLock) {
                granted -= grant
                lengths.remove(grant)
                limits.remove(grant)
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
        lengths.clear()
        limits.clear()
        try {
            r?.close()
        } catch (t: Throwable) {
            BoxLog.w(TAG, "ring close failed", t)
        }
        if (st != null) if (deleteFile) st.delete() else st.close()
    }

    /** Tests: the ring this process holds now. */
    internal fun currentRing(): TsRing? = synchronized(ringLock) { ring }

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
