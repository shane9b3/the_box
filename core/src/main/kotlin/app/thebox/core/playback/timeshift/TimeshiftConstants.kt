// Contract (M6 time-shift commit 0): docs/design/m6-timeshift.md Appendix A. Every number of the design is a default
// that lives in one named constant here; tests assert against the names.
package app.thebox.core.playback.timeshift

/** Appendix A. Sizes in bytes, times in ms (MonoClock inside the ring, §0 "Units"). */
object TimeshiftConstants {
    // ---------------------------------------------------------------- ring file (§3.1)
    /** One MPEG-TS packet. */
    const val TS_PACKET = 188
    /** File header: magic, version, sizes, generation, CRC; zero padding (§3.1). */
    const val RING_HEADER_BYTES = 4_096
    /** Packets per RAM block and file block; a wrap never splits a packet or a block. */
    const val RING_BLOCK_PACKETS = 348
    /** 348 × 188 = 65,424. */
    const val RING_BLOCK_BYTES = RING_BLOCK_PACKETS * TS_PACKET
    /** Blocks allocated ahead of the head per extent (≈ 64 MiB). */
    const val RING_EXTENT_BLOCKS = 1_025
    /** Header magic. */
    const val RING_MAGIC = "TBXRING1"
    /** Header `version`. */
    const val RING_FORMAT_VERSION = 1

    // ---------------------------------------------------------------- RAM pool and reader (§4.1, §5.1)
    /** RAM block pool: 64 blocks (4.0 MiB). */
    const val RING_RAM_BLOCKS = 64
    /** RAM block pool on low-RAM devices: 32 blocks (2.0 MiB). */
    const val RING_RAM_BLOCKS_LOW_RAM = 32
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §3.4, §4.4, lead ruling 5) The pool of a ring on a
     * USB drive or network folder: 160 blocks (10.0 MiB, ≈ 10 s at 1 MB/s; the stall budget) when `memoryClass` ≥
     * [REMOTE_POOL_MEMORY_CLASS_MB], else [RING_RAM_BLOCKS_EXTERNAL_SMALL] (96 blocks, 6.0 MiB). Also the pool of an
     * internal fallback of such a choice (one pool size per cycle).
     */
    const val RING_RAM_BLOCKS_EXTERNAL = 160
    const val RING_RAM_BLOCKS_EXTERNAL_SMALL = 96
    /** `ActivityManager.memoryClass` (MiB) from which the remote pool is [RING_RAM_BLOCKS_EXTERNAL]. */
    const val REMOTE_POOL_MEMORY_CLASS_MB = 192
    /** Per-reader direct staging buffer for `pread`. */
    const val READER_STAGING_BYTES = 65_536
    /** A blocked reader rechecks its cancel flag this often. */
    const val READER_WAIT_SLICE_MS = 250L

    // ---------------------------------------------------------------- writer and flusher (§3.2, §4.1, §4.4)
    /** While playing and FULL, saving stops with a gap only if the pin has not moved for this long. */
    const val PIN_STALL_MS = 10_000L
    /** No free pool block for this long → `DISK_TOO_SLOW`. */
    const val PUMP_BLOCK_MAX_MS = 2_000L
    /** Bounded join of the previous pump before `reset()`. */
    const val PUMP_JOIN_MAX_MS = 1_000L
    /**
     * (Fix pass — playback, S8) The join on the zap path (`prepare`): the generation fence (stale claims and commits, the
     * fenced index sink, the writer's own exit fence) makes `reset()` safe while an old pump is still alive.
     */
    const val PUMP_JOIN_ZAP_MS = 100L
    /** A partial block is queued for the flusher after this long. */
    const val FLUSH_PARTIAL_MS = 1_000L
    /** The flusher checks `st_nlink` this often (`STORAGE_EVICTED`). */
    const val NLINK_CHECK_MS = 1_000L
    /** Free-space watch while the ring grows. */
    const val FREE_SPACE_CHECK_MS = 60_000L

    // ---------------------------------------------------------------- index (§3.3)
    /** At most one index entry per this spacing. */
    const val INDEX_MIN_SPACING_MS = 400L
    /** A time-only anchor at least this often when no RAP comes. */
    const val INDEX_MAX_GAP_MS = 2_000L
    /** Index capacity = ceil(lengthSec × this) + [INDEX_SLACK_ENTRIES]. */
    const val INDEX_ENTRIES_PER_SEC = 2.5
    const val INDEX_SLACK_ENTRIES = 256
    /** Seqlock retries before a lookup falls back to the ring lock. */
    const val INDEX_READ_RETRIES = 8

    // ---------------------------------------------------------------- indexer (§3.4, §3.5)
    /** NAL/start-code RAP fallback after this long without a `random_access_indicator`. */
    const val RAI_FALLBACK_MS = 10_000L
    /** PAT/PMT packets kept per segment. */
    const val PSI_MAX_PACKETS = 8
    /** A PCR step above this (or backwards, or with `discontinuity_indicator`) inside one connection is a jump. */
    const val PCR_INLINE_JUMP_MS = 1_000L
    /** Segments in the window. */
    const val MAX_SEGMENTS = 64
    /** This many new segments within [SEGMENT_STORM_WINDOW_MS] → `PcrDiscontinuity` to the writer-scoped ladder. */
    const val SEGMENT_STORM_COUNT = 6
    const val SEGMENT_STORM_WINDOW_MS = 60_000L

    // ---------------------------------------------------------------- sizing (§8.2)
    /** Assumed stream rate until [RATE_PROBE_MS] after the first byte. */
    const val ASSUMED_BYTES_PER_SEC = 1_000_000L
    /** The capacity is final this long after the first byte (then only shrinks). */
    const val RATE_PROBE_MS = 20_000L
    /** capacity = lengthSec × rate × this. */
    const val CAPACITY_HEADROOM = 1.15
    /** Below this the buffer is off (`NO_SPACE`); 64 MiB. */
    const val MIN_RING_BYTES = 64L * 1024 * 1024
    /** 8 GiB. */
    const val MAX_RING_BYTES = 8L * 1024 * 1024 * 1024
    /** reserve = max(this, total × [FREE_SPACE_RESERVE_FRACTION]); 1 GiB. */
    const val FREE_SPACE_RESERVE_MIN = 1L * 1024 * 1024 * 1024
    const val FREE_SPACE_RESERVE_FRACTION = 0.10
    /** "Small storage" (Auto length and the daily cap): total ≤ 16 GB. */
    const val SMALL_STORAGE_BYTES = 16_000_000_000L
    /** Auto length: 30 min on small storage, 1 h above (lead ruling 2). */
    const val AUTO_LENGTH_SMALL_MS = 1_800_000L
    const val AUTO_LENGTH_LARGE_MS = 3_600_000L

    // ---------------------------------------------------------------- flash wear (§8.4)
    /** Daily write cap with "Protect built-in storage" = Auto on ≤ 16 GB storage (none above); 12 GB. */
    const val DAILY_LIMIT_SMALL = 12_000_000_000L
    /** The daily counter is persisted every 64 MiB. */
    const val DAILY_COUNTER_FLUSH_BYTES = 64L * 1024 * 1024

    // ---------------------------------------------------------------- routing (§6, §7.4, §9.1)
    /** A target at least this far after the oldest RAP goes local. */
    const val LOCAL_MARGIN_MS = 5_000L
    /** ⏪/← within this of tuning, no catch-up → REWIND_NOTHING_YET. */
    const val REWIND_NOTHING_YET_MS = 5_000L
    /** A gap shorter than this is skipped silently. */
    const val GAP_MIN_MS = 1_000L
    /** A gap at least this long during an active time-shift goes to the provider on catch-up channels (A-C5). */
    const val GAP_PROVIDER_MIN_MS = 30_000L
    /**
     * (Fix pass — playback, UX-B2) After a `LocalGap` hand-off to the provider, the controller skips the gap locally when
     * no archive tune arrived within this long.
     */
    const val GAP_HANDOFF_TIMEOUT_MS = 5_000L
    /** (Fix pass — playback, S2) A second writer segment storm within this long goes cycle-scoped (§7.4, §14). */
    const val WRITER_STORM_REPEAT_MS = 300_000L
    /**
     * (Fix pass — playback, S7) The cycle's first reader waits up to this long for the generation's first RAP, so position
     * 0 is a RAP's PTS (the playhead origin); then it opens at the tail as before.
     */
    const val FIRST_RAP_WAIT_MS = 10_000L
    /** `liveLandingMs = max(profile.startMs + this, latencyAtTune)` (A-C3). */
    const val LIVE_LANDING_EXTRA_MS = 1_000L
    /**
     * While not at the head: more than this many server-initiated losses (a close or reset, once per loss) or refusals
     * (every attempt) within the window → `SLOT_LOST` (A-C4; fix pass B1: watchdog and network reconnects never count).
     */
    const val PAUSED_RECONNECTS_MAX = 2
    const val PAUSED_RECONNECT_WINDOW_MS = 60_000L

    // ---------------------------------------------------------------- state machine and UI (§11)
    /** Single-key zaps need a second press after a pause this long (lead ruling 6). */
    const val ZAP_CONFIRM_AFTER_PAUSE_MS = 120_000L
    /** The second press must come within this. */
    const val ZAP_CONFIRM_WINDOW_MS = 3_000L
    /** The paused frame dims after this long. */
    const val PAUSED_DIM_AFTER_MS = 120_000L
    /** Picture brightness under the dim layer. */
    const val PAUSED_DIM_ALPHA = 0.35f
    /** The paused card moves this often. */
    const val PAUSED_CARD_DRIFT_MS = 60_000L
    /** A catch-up pause keeps the screen on at most this long (3 h). */
    const val PAUSE_KEEP_AWAKE_MAX_MS = 10_800_000L
    /** A focused scrub bar unfocuses and hides this long after a resume. */
    const val SCRUB_FOCUSED_IDLE_MS = 5_000L
    /** The hint row shows the first this-many times per install. */
    const val HINT_SHOW_COUNT = 3
    /** Pill text size, design px (1 design px = window width ÷ 1920). */
    const val PILL_MIN_TEXT_PX = 24
    /** M4's: user seeks within this of the live edge go live (`ArchivePlannerImpl.LIVE_SNAP_MS`). */
    const val LIVE_SNAP_MS = app.thebox.core.catchup.archive.ArchivePlannerImpl.LIVE_SNAP_MS
    /** M4's: RW on live (`PlayerStateMachineImpl.RW_ON_LIVE_BACK_MS`). */
    const val RW_ON_LIVE_BACK_MS = app.thebox.core.full.state.PlayerStateMachineImpl.RW_ON_LIVE_BACK_MS
    /** M4's: the scrub commit (`ScrubModel.SCRUB_COMMIT_MS`). */
    const val SCRUB_COMMIT_MS = app.thebox.core.full.state.ScrubModel.SCRUB_COMMIT_MS
    /** M2's (`PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS` in `:player`, mirrored): unbuffered tunes only. */
    const val LIVE_PAUSE_RELEASE_MS = 10_000L
    /** Follow-up (§10): the HLS DVR window counts from this length. */
    const val HLS_DVR_MIN_WINDOW_MS = 120_000L

    // ---------------------------------------------------------------- gates (§6, §15)
    /** Seek commit → first frame. */
    const val SEEK_LOCAL_P50_MS = 600L
    const val SEEK_LOCAL_P90_MS = 1_000L
    /** Writer CPU, share of one core at 1 MB/s. */
    const val WRITER_CPU_MAX = 0.02
}
