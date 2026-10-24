// M6 time-shift, WP-C (docs/design/m6-timeshift.md §9.2, §11.2, A-B4, A-C1).
package app.thebox.playback

import app.thebox.core.catchup.archive.ArchiveFrames
import app.thebox.core.catchup.archive.LocalWindow
import app.thebox.core.common.BoxLog
import app.thebox.core.full.LocalBufferInfo
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `ArchivePlayback`'s LOCAL source (§11.2) and the **only** converter from the controller's mono times to the
 * programme frame (§9.2, A-C1):
 *
 * `P(t) = frames.liveEdge(deviceWall(t)) = mono + wallMinusMono + skew + correction`, and its inverse for seeks.
 *
 * The frames are those of the channel's catch-up (`ArchivePlanner.frames`, the same M4 uses for the live-pause point)
 * and, for a channel without catch-up, correction 0 with the playlist's skew. They are looked up once per tuned
 * identity (A-B4 confirmation: "loaded at tune time"); until they are known [localBuffer] holds back an available
 * buffer (null), so a pause in the first second never jumps when they arrive. An unavailable buffer is published at
 * once (it carries only the reason, no times).
 *
 * [localBuffer] is the player VM's only source for `PlayerEvent.Buffer`; its [LocalBufferInfo.atMs] is the live edge
 * in P at the moment of publication (device wall [clock]), [LocalBufferInfo.fromMs] the oldest saved instant.
 *
 * Main-confined: [scope] runs on main (`Dispatchers.Main.immediate`, like `ArchivePlaybackImpl`).
 */
class LocalTimelineBackend(
    private val controller: PlaybackController,
    /** The channel's frames (display shift ignored); null when they cannot be found (then: zero skew and correction). */
    private val framesOf: suspend (ChannelIdentity) -> ArchiveFrames?,
    private val scope: CoroutineScope,
    /** Device wall clock (`System.currentTimeMillis`; tests substitute). */
    private val clock: () -> Long,
) {
    private val _local = MutableStateFlow<LocalBufferInfo?>(null)
    /** The P-frame view of `controller.localBuffer` (≤ 1 Hz, A-C1). */
    val localBuffer: StateFlow<LocalBufferInfo?> = _local.asStateFlow()

    /** The last controller snapshot (mono times). */
    var state: LocalBufferState? = null
        private set
    private var framesFor: ChannelIdentity? = null
    /** The identity of the last snapshot (the REWIND_NOTHING_YET clock restarts when it changes). */
    private var identityOf: ChannelIdentity? = null
    private var frames: ArchiveFrames? = null
    private var framesJob: Job? = null
    /** Device wall ms when the current cycle began: a new identity or a new `cycleId` (REWIND_NOTHING_YET; fix pass S9). */
    private var identitySinceMs = 0L
    /** The controller cycle of the last snapshot (fix pass S9: a re-tune of the same channel is a new cycle). */
    private var cycleId = 0L
    private var started = false

    /** Starts following `controller.localBuffer`; idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch { controller.localBuffer.collect { onState(it) } }
    }

    private fun onState(s: LocalBufferState?) {
        state = s
        val id = s?.identity
        if (s == null || id == null) {
            // (Fix pass S9) No buffer: forget the frames and the cycle, so the next one (even on the same channel) starts
            // its own clock and looks its frames up again.
            framesFor = null
            identityOf = null
            frames = null
            framesJob?.cancel()
            cycleId = 0L
            _local.value = null
            return
        }
        if (s.cycleId != cycleId) {
            cycleId = s.cycleId
            identitySinceMs = clock()
        }
        if (id != identityOf) {
            identityOf = id
            identitySinceMs = clock()
            if (framesFor != id) {
                framesFor = null
                frames = null
                framesJob?.cancel()
            }
        }
        // (Integration 2026-10-19, §15 zap gate) The frames lookup (catch-up target and time base: library.db reads) runs
        // once the buffer is available, not at the tune: an Off or not-yet-saving cycle never has a P frame to show, and
        // at the tune the lookup raced the controller's own facts read and resolve on every zap.
        if (s.available && id != framesFor) {
            framesFor = id
            frames = null
            framesJob?.cancel()
            framesJob = scope.launch {
                val f = try {
                    framesOf(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.w(TAG, "time-shift frames lookup failed: ${e.javaClass.simpleName}")
                    null
                }
                if (framesFor == id) {
                    frames = (f ?: ArchiveFrames(0, 0, 0)).copy(displayShiftMs = 0)
                    publish()
                }
            }
        }
        publish()
    }

    private fun publish() {
        val s = state ?: return
        val f = frames
        _local.value = when {
            !s.available -> LocalBufferInfo(
                available = false, fromMs = 0, atMs = 0, saving = false, notSaved = TimeRanges.EMPTY,
                pauseRemainingMs = null, stop = s.stop, off = s.off, hls = s.hls, limitedToday = s.limitedToday,
                cycleId = s.cycleId,
                // (Storage locations WP-S2, §5.5, §10) where the cycle saves, and why it fell back or stopped.
                storage = s.storage, storageFailure = s.storageFailure, fallback = s.fallback, storageName = s.storageName,
                locationId = s.locationId,
            )
            f == null -> null // §7.1 (A-B4 confirmation): held back until the frames are known
            else -> LocalBufferInfo(
                available = true,
                fromMs = p(s.fromMonoMs, s, f),
                atMs = f.liveEdge(clock()),
                saving = s.saving,
                notSaved = ranges(s.notSavedMono, s, f),
                pauseRemainingMs = s.pauseRemainingMs,
                stop = s.stop,
                off = s.off,
                hls = s.hls,
                limitedToday = s.limitedToday,
                // (Fix pass, UX review S1, S2, S9) Lengths need no frame; the stop instant is converted like any time.
                windowMs = s.windowMs,
                holdMs = s.holdMs,
                stoppedAtMs = s.stoppedAtMonoMs?.let { p(it, s, f) },
                cycleId = s.cycleId,
                storage = s.storage, storageFailure = s.storageFailure, fallback = s.fallback, storageName = s.storageName,
                locationId = s.locationId,
            )
        }
    }

    // ------------------------------------------------------------------ the frame conversion (§9.2)

    /** The buffer of [identity] is available and its frames are known. */
    fun availableFor(identity: ChannelIdentity): Boolean {
        val s = state ?: return false
        return s.available && s.identity == identity && framesFor == identity && frames != null
    }

    /** The identity the snapshot describes. */
    val identity: ChannelIdentity? get() = state?.identity

    /** Mono → P (§9.2). */
    fun toProgramme(monoMs: Long): Long {
        val s = state ?: return monoMs
        val f = frames ?: return monoMs + s.wallMinusMonoMs
        return p(monoMs, s, f)
    }

    /** P → mono, the inverse of [toProgramme] (seek targets). */
    fun toMono(programmeMs: Long): Long {
        val s = state ?: return programmeMs
        val f = frames ?: return programmeMs - s.wallMinusMonoMs
        return programmeMs - f.skewMs - f.correctionMs - s.wallMinusMonoMs
    }

    /** The live edge now in P: the panel's now (`ArchiveFrames.liveEdge`, M4 §3.8.1). */
    fun liveEdgeMs(nowMs: Long = clock()): Long = frames?.liveEdge(nowMs) ?: nowMs

    /** The oldest saved instant in P; null without a buffer. */
    fun fromMs(): Long? {
        val s = state?.takeIf { it.available } ?: return null
        return toProgramme(s.fromMonoMs)
    }

    /** The playhead in P from the controller's `position().localMonoMs`, else the snapshot's playhead. */
    fun playheadMs(): Long? {
        val mono = controller.position().localMonoMs ?: state?.takeIf { it.available }?.atMonoMs ?: return null
        return toProgramme(mono)
    }

    /** The buffer as the router sees it (P frame, §9.1). */
    fun window(nowMs: Long = clock()): LocalWindow? {
        val s = state?.takeIf { it.available } ?: return null
        val f = frames ?: return null
        return LocalWindow(p(s.fromMonoMs, s, f), f.liveEdge(nowMs), ranges(s.notSavedMono, s, f))
    }

    /** Device wall ms since the cycle's identity appeared (REWIND_NOTHING_YET). */
    fun sinceTuneMs(nowMs: Long = clock()): Long = nowMs - identitySinceMs

    /** The not-saved ranges in P. */
    fun notSaved(): TimeRanges {
        val s = state ?: return TimeRanges.EMPTY
        val f = frames ?: return TimeRanges.EMPTY
        return ranges(s.notSavedMono, s, f)
    }

    private fun p(mono: Long, s: LocalBufferState, f: ArchiveFrames): Long = f.liveEdge(mono + s.wallMinusMonoMs)

    private fun ranges(r: TimeRanges, s: LocalBufferState, f: ArchiveFrames): TimeRanges {
        if (r.count == 0) return TimeRanges.EMPTY
        val out = LongArray(r.count * 2)
        for (i in 0 until r.count) {
            out[2 * i] = p(r.startAt(i), s, f)
            out[2 * i + 1] = p(r.endAt(i), s, f)
        }
        return TimeRanges(out)
    }

    private companion object {
        const val TAG = "LocalTimeline"
    }
}
