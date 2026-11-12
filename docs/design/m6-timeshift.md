# Pause and rewind (local time-shift buffer): design

Status: **proposed 2026-10-19; reviewed 2026-10-19** by an architecture/playback review (A, `scratchpad/timeshift/review-arch.md`: blockers A-B1…A-B4, should-fix A-S1…A-S11, nice-to-have A-N1…A-N9) and a product/UX review (U, `scratchpad/timeshift/review-ux.md`: blockers U-B1…U-B4, should-fix U-S1…U-S10, nice-to-have U-N1…U-N7). Every blocker is applied. Should-fix items are applied in place and marked "(reviewed 2026-10-19, A-Sn / U-Sn: …)"; where a lead ruling changed one, the mark says so, and §21 lists what was not applied and why. Nice-to-have items are applied where they are cheap and marked the same way. A confirmation pass by the architecture reviewer (2026-10-19) closed all four A-blockers and asked for five small changes, applied in place as "(reviewed 2026-10-19, A-Cn: …)". The lead commits commit 0 (§18) and starts the four work packages.

Owner request (2026-10-19, verbatim): "ok i think i need some cache or saving so if i pause live tv it will allow me to continue where i left it and be able to rewind x amount of time. discuss and plan and then execute".

Inputs:
- `CLAUDE.md`.
- `00-master-plan.md`: §2 (the v1.1 line at line 45), §3 ("Live TS transport" at line 61, "Catch-up", "Process"), §6 gates.
- `design/m2-playback-core.md`: §3.1.2 (pause rows), §3.3, §3.6.3, §3.7, §3.8.5, §3.15.
- `design/m4-player-ux.md`: §0 items 4, 5 and 8, §3.2.3, §3.4.1, §3.5, §3.8.
- `design/m5-setup-settings.md` §3.3.13 (the simple-row budget).
- `decisions/2026-10-15-player-keys-and-quick-bar.md`.
- `review/product-review.md`: line 58, item 25 (line 605), the five-key rule (line 94).
- The code on `main` at `70da111` plus the working tree.

Media3 1.11.1 facts come from the fact sheet `scratchpad/timeshift/api-facts.md`, cited as "facts §n". Every Media3 claim was checked against it or against the unpacked 1.11.1 sources. Anything verified by neither is listed in §22.

This document is the contract between four implementers who will not see the conversation that produced it. **Must** means a reviewer rejects code that does otherwise. Every number is a default that lives in one named constant (Appendix A).

Units:
- Sizes on player screens are **design px** (1 design px = window width ÷ 1920).
- Times are in ms. Inside the ring, the writer and the index, every time is **monotonic** (`MonoClock`).
- Wall times appear only at the `LocalBufferState` boundary (§16.2). The timeline uses M4's **programme frame** (P, M4 §3.8.1).

**Words on screen** (lead 2026-10-19, ruling 5; reviewed 2026-10-19, U-S5):
- The viewer never reads "buffer": the existing *Buffer* row is the network buffer.
- Viewer-facing text says *pause and rewind*, *rewind*, *saved*, *behind live*, *where you paused*, *live*. It never says *buffer*, *segment*, *ring* or *live edge*. The one use of *recording* is the advanced row the lead named (§12, §21).
- Code, constants and this document keep the engineering names (ring, buffer, segment).

---

## Lead sign-off (2026-10-19)

Approved after both reviews. Lead rulings:

1. **Architecture as proposed.** Ring always; one pump thread; seeks re-prepare the reader; LOCAL source in `ArchivePlayback`. A-B1…A-B4 and U-B1…U-B4 are accepted as the reviews wrote them:
   - Recorder failures reach the ladder through a `writerTuneId` and a recorder-only ladder path. A decided error is stashed until the saved part has played out (§7.4).
   - RAM blocks can be recycled once flushed, and readers fall back to `pread` (§4.1).
   - The reader's telemetry reflects the recorder only while the reader is at the live edge (§5.2).
   - The buffer timeline uses M4's programme frame: device now + skew + correction (§9.2).
   - The screen stays on while paused and saving, with a dimmed paused card (§11.5).
   - On catch-up channels, a gap the buffer lost goes to the provider archive (§9.1).
   - The quick-bar item is "Pause". It comes first on every live channel while live, and OK on it pauses at once. "Back to live" comes first while behind (§11.3).
   - The simple-settings budget stays at 35: the "On launch" row is demoted to ADVANCED, not Picture shape (§12).
2. **Q1, wear.** Auto = 30 min of rewind on ≤ 16 GB storage, 1 h above. Rewind is on by default; it is not Pause only. The daily write cap is an internal Auto rule:
   - 12 GB/day on ≤ 16 GB storage, none above. This sits between the three proposed figures and stands until real wear data exists.
   - It is surfaced as one ADVANCED row, "Protect built-in storage: Auto / Off".
   - "Pause only" stays a value of the length row.
3. **Q2, connection.** Auto hold = until the buffer is full, on every account.
   - A recorder that loses its connection slot (509, kicked, lease refused) stops recording and never fights another device. The saved part stays playable, and a toast says what happened.
   - The advanced row "Keep recording while paused: Auto / 10 min / 30 min / 1 h / Until full" stays.
4. **Q4–Q7.**
   - Q4: local first, provider archive beyond it, and the provider fills gaps (U-B2).
   - Q5: the buffer is discarded on Home or standby, with U-B1 and a toast on return.
   - Q6: the HLS DVR window becomes a separate follow-up item in the master plan (WP-D records it, §18).
   - Q7: seeks land on a keyframe, with direction-aware rounding and PTS-based positions (A-S4).
5. **Wording.**
   - Pills: "PAUSED · 4:12 behind" and "4:12 BEHIND".
   - On channels without catch-up, the scrub bar spans the rewind window, with the labels U-S3 proposed.
   - While paused: "Can stay paused 17 more min".
   - Every toast carries a reason and a next step, and none points at a row hidden in the simple view.
   - The UX review's strings table is used verbatim unless it conflicts with these rulings.
6. **Zap while paused ≥ 2 min.**
   - Single-key zaps (CH±, digits, Last) need a second press within 3 s, with the toast U-S8 proposed.
   - The channel list and the guide zap at once.

A-N9 (lead action, outside this design): commit the uncommitted `app/src/debug/.../GuideDebugReceiver.kt` before WP-D branches.

---

## 0. Key decisions at a glance

1. **The player always reads the ring; one writer pulls upstream.**
   - For a buffered live TS tune, one `box-ts-pump` thread owns the tune's `LiveTsDataSource`. That source is still the only upstream connection and still goes through `StreamConnectionBroker`. The pump writes every packet into a disk ring.
   - The player's `ProgressiveMediaSource` reads only the ring, through `RingDataSource`, which blocks at the live edge.
   - Pause is `playWhenReady = false`. Resume is `playWhenReady = true` and continues exactly where it stopped: no re-tune, no new connection, no "Back to live" toast.
   - *Why:* a paused player's loader parks within about 1 MiB of a full buffer (facts §4), so anything that rides on the player's reads (the existing tee) stops saving when the user pauses.
2. **One preallocated ring file per process, logical 64-bit offsets, packet-aligned blocks, in-memory index.**
   - File: `cacheDir/timeshift/ring-<pid>.bin`. A 4 KiB header, then whole 65,424-byte blocks (348 TS packets).
   - Offsets only grow. File position = `HEADER + offset mod capacity`, so a wrap never splits a packet or a block.
   - The RAM index holds one entry per random-access point (RAP), with PCR and PTS. A segment table holds one row per timestamp-continuous run, with its PSI. Neither is written to disk.
3. **A seek re-prepares the player from a ring offset (M4's re-request model). It is never a native seek.**
   - Native seeking cannot work on a growing TS in Media3 1.11.1 (facts §1.1, implications 1–4).
   - Rounding is direction-aware: backward steps land on the RAP at or before the target, forward steps on the RAP at or after it.
   - Positions come from the RAP's **PTS**, not its PCR (reviewed 2026-10-19, A-S4; lead ruling 4).
   - Target: P50 ≤ 600 ms from commit to first frame.
4. **The buffer is a time window bounded by bytes, and it never overwrites the playhead.**
   - `tail` advances only up to the lowest pin.
   - While playing behind live and the ring is full, the pump waits for the reader to free space. The backpressure goes to TCP, as a parked loader's does today (reviewed 2026-10-19, A-S3).
   - While paused and full, saving stops and the connection is released.
5. **Connection while paused.** "Keep recording until the buffer is full" (Auto, every account; lead ruling 3) replaces the 10 s release for buffered tunes.
   - A recorder that loses its slot while paused or behind stops saving instead of reconnecting, so it never fights another device (reviewed 2026-10-19, A-S2).
   - Unbuffered tunes keep M2's `LIVE_PAUSE_RELEASE_MS`.
6. **Local first, provider archive beyond it and for gaps, one timeline** (reviewed 2026-10-19, U-B2, A-N3; lead ruling 4).
   - On a catch-up channel, pause and rewinds inside the buffer use the buffer.
   - A target older than the buffer, or a gap the buffer lost, goes to the provider archive, as M4 does today. That ends the ring session.
   - `ArchivePlayback` gains a LOCAL source. Its times are in M4's programme frame (reviewed 2026-10-19, A-B4).
7. **HLS live is not buffered.**
   - The quick bar's Pause is still present and still first, so ↑ OK always pauses (reviewed 2026-10-19, U-B3).
   - On HLS it freezes the picture as M2 does, and a toast gives the reason and a next step.
   - The HLS DVR window is a separate follow-up (lead ruling 4).
8. **Failures split by side** (reviewed 2026-10-19, A-B1, A-B3).
   - Upstream failures reach the ladder on a recorder-only path (`writerTuneId`, `writerJob`), and the reader keeps playing. A `Fail` is stashed until the saved part has played out.
   - A PTS/PCR jump becomes a segment boundary, and a reader hands off across it with one local re-prepare.
   - M2's in-source repairs run **before** the ring. The extractor guards run **after** it, on every read.
9. **The screen stays on while a pause is still being saved.** After 2 min the paused frame dims under a drifting card that says how much longer the pause can last (reviewed 2026-10-19, U-B1, U-S4). Without this, the Fire TV screensaver ends the pause after about 5 min and the saved part is lost.
10. **Budgets.**
    - No extra user-space copy on the write path: blocks are direct-backed arrays, and the only added copy is the kernel `pwrite` (reviewed 2026-10-19, A-S8).
    - Zero allocation per packet and per block.
    - ≤ 4.7 MiB extra heap (2.4 MiB low-RAM).
    - Writer ≤ 2 % of one core at 1 MB/s.
    - Threads: one `box-ts-pump` per buffered tune and one shared `box-ts-flush`.
11. **Flash wear is budgeted.** Writes per day equal bitrate × hours saved, whatever the buffer length. Internal Auto cap: 12 GB/day on ≤ 16 GB devices (lead ruling 2). Past it, the buffer saves only while paused or behind live, and the viewer is told once, when a rewind reaches the shortened start.
12. **State machine: one predicate plus the quick-bar order.**
    - `archiveCapable()` becomes `timeShiftCapable() = archiveCapable() || bufferCapable()`. Play/Pause, RW, the scrub bar and Back to live then take M4's existing paths, which `ArchivePlayback` routes.
    - Pause is first in the quick bar on every live channel, and OK on it pauses.
    - A zap discards the buffer. Single-key zaps after a pause of ≥ 2 min need a second press (lead ruling 6).
13. **The ring is the v2 DVR seam.** A v2 recorder of the watched channel is one more pinned `RingReader`. The tee stays only for the debug capture sink.

---

## 1. What exists today (verified in the code)

| Fact | Where |
|---|---|
| v1.1 parks "Local disk ring-buffer pause/rewind for non-archive channels" | `00-master-plan.md:45` |
| `LiveTsDataSource` is built as `TeeDataSource(LiveTsDataSource, sink ?: NoOpDataSink())` | `player/.../datasource/LiveTsDataSource.kt:585-589`; plan line 61 |
| The tee sees only what the loader reads; it writes on the loader thread; a sink exception fails the load | facts §3 |
| Live pause: `livePauseJob` ends the session after `LIVE_PAUSE_RELEASE_MS` = 10,000; resume re-tunes at live with `LiveResumed` | `PlaybackControllerImpl.kt:1647-1708, 2124`; M2 §3.1.2 |
| `keepScreenOn = !paused`: a paused player lets the device idle | `PlaybackControllerImpl.kt:2040-2048` |
| The screensaver stops the activity with the display on, which is treated as Home (stop at once). Sleep takes the standby path (30 s grace) | `LifecycleCoordinator.kt:20-35` |
| `controller.seekTo` is archive-only; `Player.seekTo` on unseekable live reloads from byte 0 | `PlaybackControllerImpl.kt:1921-1927`; facts §2 |
| The paused loader keeps loading to 20 s / 24–48 MB, then parks | `AdaptiveLoadControl.kt:93-105`; facts §4 |
| `ByteWatchdog` measures silence only while a `read()` is pending | `LiveTsDataSource.kt:161, 556-569` |
| Reconnects splice on a packet boundary with a forced CC gap and a post-splice PCR hold; a jump > 2 s throws `PCR_DISCONTINUITY` | `LiveTsDataSource.kt:371-388, 396-465` |
| The classifier drops an error whose tuneId is not the current session's, as stale | `FailureClassifier.kt:58, 133` |
| `onSessionEvent` drops events from any id other than `session.tuneId` | `PlaybackControllerImpl.kt:1282-1285` |
| Every ladder decision ends in `restart` → `launchSession` (`endSession`, `player.stop`, `++lastTuneId`); `fail()` ends the session at once | `PlaybackControllerImpl.kt:633-635, 1448-1610` |
| `launchSession` call sites: `reapplySettings`, `selectServer`, `startCycle`, the ladder, `setVideoDecoderOverride` | `PlaybackControllerImpl.kt:508, 535, 596, 1583, 1915` |
| Extractor guards (dead track, stuck audio, L33r audio repair, compat `MULTI_PMT`) wrap `TsExtractor` | `engine/TsExtractorsFactory.kt:43-61, 113-146` |
| Each prepare gets a fresh `TimestampAdjuster(0)`: position 0 = the first adjusted PTS | facts §1.3 |
| M4: Play/Pause on an archive-capable channel → `ArchivePlayback.pauseLive`; RW → `rewindLive(30 s)`; no-archive channels freeze and rejoin | `PlayerStateMachineImpl.kt:369-411`; `ArchivePlaybackImpl.kt:251-288, 1003-1035` |
| Live edge in the programme frame = `deviceNow + skew + correction` | `ArchiveContracts.kt:129`; `ArchivePlaybackImpl.kt:1103` |
| Quick bar: `TIME_SHIFT` only when `archiveCapable && !inArchive`; order Audio, Aspect, Subtitles, Favourite, Start over, … | `QuickBarModel.kt:13-42` |
| A focused scrub bar never hides | `PlayerStateMachineImpl.kt:1517` |
| Pill text 15 dp | `PlayerParts.kt:113` |
| Simple settings rows total exactly 35, and PLAYBACK is pinned at 5 (Buffer, AFR, Audio output, Aspect, On launch) | `SimpleRowCountTest.kt:19, 39`; `PlaybackRows.kt:43-48` |
| Fake panel: endless paced TS; faults `close_every`, `reconnect_mode` (continue/reset/jump), `stall`, `stall_after`, `slow`, `linger` | `tools/fakepanel/fakepanel.py:76-77, 1748-1765` |

---

## 2. Architecture

```
                         one connection (broker lease, PLAYBACK)
panel ──HTTP──► LiveTsDataSource (reconnect, aligner, PCR hold, ByteWatchdog; onPcrJump hook for the writer)
                     │  read() on box-ts-pump (never parks on the player)
                     ▼
               RingWriter ── TsIndexer (RAP + PTS, PCR, PSI, stream-domain jumps) ──► RingIndex + segments (RAM, seqlock)
                     │ claim/commit(slot, generation)                (debug only: tee to FileCaptureSink)
                     ▼
               TsRing: direct-backed RAM block pool ──flush queue──► box-ts-flush ──pwrite──► RingStorage (ring-<pid>.bin)
                     │                                                                          │
                     └──────► RingReader (RAM copy, seq-checked │ pread via direct staging) ◄────┘
                                          │ blocks at head; pins its cursor; generation-fenced
                                          ▼
               RingDataSource (Media3, registered with the reader tune's TransportSession) ─► ProgressiveMediaSource
                                          ─► GuardedTsExtractor (repairs) ─► ExoPlayer
```

**Ownership.**
- A controller **cycle** (one user request on one channel) owns one `TimeshiftSession`: the writer, a `writerTuneId` reserved from the controller's sequence, a cycle-level `writerJob`, and the session's stashed error.
- The process owns one `TsRing` over one `RingStorage`. Each session uses it under a new generation.
- Each player **session** (`TuneSession`, a new `tuneId` per prepare) owns one `RingReader`, registered as a `Terminable` in its tune's transport scope so that `TuneSession.end()` cancels it (reviewed 2026-10-19, A-S9).
- Ending a player session closes its reader only. Ending the cycle closes the `TimeshiftSession`: the writer's call is cancelled first, then the lease is released (M2 §3.3.7). This always happens **before** the next cycle's `launchSession` (reviewed 2026-10-19, A-S1: "cancel the old call before opening the next").

**Clocks** (reviewed 2026-10-19, A-S9).
- The ring, the indexer, the hold timer and every pin use `MonoClock` (`elapsedRealtime`).
- `LocalBufferState` converts to device wall time once per snapshot: `wall = mono + (System.currentTimeMillis() − mono.nowMs())` at the snapshot. A wall-clock step (NTP after standby) moves the whole timeline together and never distorts the index.

**When the buffer is unavailable** (setting Off, HLS, not enough space, archive playback, storage error), the pipeline is exactly today's, and `localBuffer.value.off` says why.

---

## 3. Ring file format and index

### 3.1 File

| Region | Bytes | Contents |
|---|---|---|
| Header | 4,096 | `magic` "TBXRING1" (8), `version` u32 = 1, `packetSize` u32 = 188, `blockSize` u32 = 65,424, `capacity` u64 (a multiple of `blockSize`), `generation` u64, `createdMonoMs` u64, `pid` u32, CRC32 of the preceding fields; zero padding |
| Data | `capacity` | logical offset `L` lives at `4096 + (L mod capacity)` |

- **Header.** Written when the file is created and on each `reset()` (`generation++`); never per block.
- **Name.** `ring-<pid>.bin` (reviewed 2026-10-19, A-S7). The start-up sweep deletes only files with other names, and `TimeshiftStorage.prepare()` waits for the sweep to finish, so a launch-time auto-tune can never lose its file to the sweep.
- **Packet alignment.** The writer appends whole packets only, and `capacity` is a multiple of `blockSize` = 348 × 188. Wrap never splits a packet or a block.
- **Pre-allocation.** Extents of `RING_EXTENT_BLOCKS` (1,025 blocks, about 64 MiB) are allocated ahead of the head with `RingStorage.allocate` (`Os.posix_fallocate`; on `EOPNOTSUPP`/`ENOSYS` a sparse `setLength`, with `ENOSPC` detected at write). Before each extent and every `FREE_SPACE_CHECK_MS` (60,000), if `available < reserve / 2`, growth stops and the ring wraps at its current size; the setting row then says the rewind is limited (reviewed 2026-10-19, A-S7).
- **Durability.** No `fsync`/`force`.

### 3.2 Logical offsets, wrap and pins

State: `head` (volatile), `tail` (volatile; always an index entry's offset or 0), `flushed`, `generation` (volatile), and `pins`, a fixed array mapping owner → offset.

Invariants (asserted in tests):
1. `0 ≤ tail ≤ min(pins) ≤ head` and `head − tail ≤ capacity`.
2. Every byte in `[tail, head)` reads back exactly as written, from RAM or from the file. PAUSE_ONLY has its own rule (§4.5).
3. `tail` only moves forward, and only onto an entry offset; where a RAP exists, that entry is the oldest RAP (reviewed 2026-10-19, A-N8).

**FULL is decided in `claim()`, before the upstream read** (reviewed 2026-10-19, A-S3). If the claimed block would push `tail` past `min(pins)`, `claim` answers `FULL`:
- **while playing**, the pump waits on the pin condition, so backpressure reaches TCP. Saving stops with a gap only if the pin has not advanced for `PIN_STALL_MS` (10,000);
- **while paused**, saving stops at once (`FULL_WHILE_PAUSED`, §4.4).

There is no headroom behind the pin: the proposal's 8 MiB was unusable old media, not burst absorption (A-S3). On each commit, `tail` advances to the first entry with `offset ≥ head' − capacity`, and for the time limit to the first entry with `pts-wall ≥ newest − lengthMs`. Entries and segments that fall entirely before the new `tail` are dropped, which is a pointer move.

**Generation fence** (reviewed 2026-10-19, A-S1).
- `claim`/`commit` carry the generation the writer was started with; a commit from an older generation is dropped.
- Every `RingReader` carries its generation; a read after a `reset()` throws `RingEndException(SESSION_END)`.
- `reset()` runs on IO only after the previous pump has exited (a bounded join of `PUMP_JOIN_MAX_MS`, 1,000; never on main).

### 3.3 Index

One entry per RAP, at most one per `INDEX_MIN_SPACING_MS` (400). A time-only anchor is added at least every `INDEX_MAX_GAP_MS` (2,000) when no RAP comes.

Storage is a struct of arrays in a preallocated circular buffer:

| Field | Type | Bytes |
|---|---|---|
| `offset` (logical; for a RAP, the packet that starts the video PES) | `LongArray` | 8 |
| `pts` (video PES PTS for a RAP, 90 kHz, unwrapped per segment; for anchors, the interpolated PTS from the PCR) (reviewed 2026-10-19, A-S4) | `LongArray` | 8 |
| `pcr` (90 kHz, unwrapped per segment; arrival mono ms for `ARRIVAL_CLOCK` segments) | `LongArray` | 8 |
| `flags (RAP, SPLICE, ARRIVAL_CLOCK, SEGMENT_START) << 24 \| segment` | `IntArray` | 4 |

**Memory.** At most 2.5 entries/s. Capacity = `ceil(lengthSec × 2.5) + 256`:
- 2 h: 18,256 × 28 B = **511 KB**;
- 30 min: 129 KB.

A full index evicts its oldest entry, and `tail` moves with it.

**Time.**
- Per segment, `monoOffsetMs = min over the segment's PCR samples of (arrivalMono − pcr/90)`. A connect burst arrives late relative to its PCR, so the minimum converges on the real-time offset.
- `monoOf(pts) = pts/90 − ptsPcrLead + monoOffset`, where `ptsPcrLead` is measured per segment (the median of PTS − PCR on RAPs).
- In practice the playhead origin is `ptsMono(rap)`, the live edge is the mono time of the **newest PTS** (not the arrival of the newest byte), and BEHIND right after Back to live reads at most `liveLandingMs` + 1 s (§6; reviewed 2026-10-19, A-S4; A-C3: the landing is deliberately ≥ 2 s behind the newest PTS).

**33-bit wrap.** PTS and PCR are unwrapped per segment with `PcrTrackerImpl.signed33` (`PcrTracker.kt:67`).

**Thread safety** (reviewed 2026-10-19, A-S9).
- The writer wraps each mutation in a seqlock (`seq` odd while writing).
- `RingIndexView` lookups run on any thread: they read `seq`, read the fields, and retry if `seq` changed. After `INDEX_READ_RETRIES` (8) attempts they fall back to the ring lock.
- Lookups allocate nothing.

**Lookups** (binary search): `floorRap(mono)`, `ceilRap(mono)`, `newestRap()`, `oldestRap()`, `monoOfOffset(offset)`.

### 3.4 Random-access and jump detection (`TsIndexer`, writer thread)

For each packet: the PID and two bit tests. Packets on PAT, PMT, the PCR PID and the video PID get more work.

1. **PSI.** The indexer keeps the latest PAT and PMT packets (at most `PSI_MAX_PACKETS`, 8). The PMT gives the PCR PID, the video PID and its stream type. A PMT whose version or content changes ends the segment with `PSI_CHANGE` (reviewed 2026-10-19, A-S5): `MODE_SINGLE_PMT` locks onto the first PMT per extractor, so stale PSI would pin a reader to old PIDs.
2. **RAP, first choice:** `random_access_indicator` (byte 5, bit 0x40) on the video PID, or on the PCR PID when the stream has no video. The indexer then parses the PES header in the same packet (≤ 14 bytes) for the PTS.
3. **RAP, fallback:** used when no RAI has been seen for `RAI_FALLBACK_MS` (10,000). On payload-unit-start packets of the video PID, look for a start code:
   - H.264: NAL type 5 or 7;
   - H.265: types 16–21 or 32–34;
   - MPEG-2: `0xB3`.
4. **No video and no RAI (radio):** time anchors only.
5. **Jumps, in the stream domain** (reviewed 2026-10-19, A-S5).
   - Consecutive PCRs on the PCR PID are ≤ 100 ms apart by the spec, so a step greater than `PCR_INLINE_JUMP_MS` (1,000), backwards, or with the `discontinuity_indicator` set is a jump. This does not depend on arrival time, so a connect burst never creates a segment.
   - Arrival time is used only for `monoOffset`.
   - The **boundary** is placed at the first video PES whose PTS steps more than 1 s from the previous one, or goes backwards. The PTS step comes before the PCR step in byte order, and Media3 has no detection for it (facts §1.3).
   - The next segment is readable from its first RAP. The bytes between the boundary and that RAP are never handed to a reader.
   - Radio (no video PES) has no PTS-based placement: the boundary falls back to the PCR packet that jumped. Accepted: audio-only decoders ride a step of a few frames.
6. **Splice.** `RECONNECT_OK` from the writer's source marks the next entry `SPLICE`. Not a boundary.

### 3.5 Segments

Reasons: `FIRST`, `PCR_JUMP`, `INLINE_PCR_JUMP`, `PSI_CHANGE`, `WRITER_RESTART`, `GAP`.

Each table entry holds: `boundaryOffset`, `firstRapSlot`, `monoOffsetMs`, `ptsPcrLead`, `psi` (≤ 8 packets), `reason`, and `gapMs` (the media time missing before it).

At most `MAX_SEGMENTS` (64) live in the window. If `SEGMENT_STORM_COUNT` (6) new segments start within `SEGMENT_STORM_WINDOW_MS` (60,000), the indexer reports `PcrDiscontinuity` to the writer-scoped ladder (§7.4). Stream-domain detection means a connect burst no longer counts (A-S5).

---

## 4. Writer

### 4.1 Threads, pool and recycling

**`box-ts-pump`: one long-lived thread per `TimeshiftSession`** (reviewed 2026-10-19, A-S1).
- It owns its upstream's lifecycle. It loops `claim → upstream.read(slot) → indexer.scan → commit`; the bytes land directly in the ring's RAM block.
- `restart` posts a command and `terminate()`s the current upstream from the caller's thread to unblock it. The pump opens the next upstream only after the old one has returned from `read`.
- `commit` publishes to readers at once. When a block is full, or `FLUSH_PARTIAL_MS` (1,000) has passed, `commit` enqueues `(block, from, to)` for the flusher.

**`box-ts-flush`: one daemon thread per process.**
- It writes each queued range through `RingStorage.pwrite` from the block's **direct** `ByteBuffer`, so there is no hidden JNI copy (reviewed 2026-10-19, A-S8).
- On Android, `allocateDirect` is backed by a non-movable `byte[]`. The pool allocates each block with `allocateDirect` and hands `array()` plus `arrayOffset()` to `RingSlot`.

**Pool size.** `RING_RAM_BLOCKS` = 64 (4.0 MiB); 32 on low-RAM devices.

**Recycling** (reviewed 2026-10-19, A-B2).
- A block can be recycled **as soon as it is flushed**, whatever the reader cursors are. A reader that needs bytes no longer in RAM reads them with `pread`.
- The only reader guard is a per-block sequence number. The reader reads it, copies, then reads it again; on a mismatch it re-reads the range from the file.
- A paused reader or a reader behind live therefore never holds the pool.

**Disk slower than the stream.** If no block is free (all unflushed) for `PUMP_BLOCK_MAX_MS` (2,000), saving stops with `DISK_TOO_SLOW`.

### 4.2 Upstream, reconnect and watchdog

`LiveTsUpstream` (`:player`) adapts `LiveTsDataSource` to `TsUpstream`, under the cycle's `writerTuneId`.
- The source behaves as today, except for one optional hook used only by the writer (reviewed 2026-10-19, A-N1): `onPcrJump: ((deltaMs: Long) -> Unit)?`. With the hook set, a post-splice PCR jump **releases** the held packets and reports the jump instead of throwing `PCR_DISCONTINUITY`. The indexer places the boundary (§3.4). One connection is saved on one-slot panels, and no bytes are dropped. The default (null) keeps M2's behaviour for every other caller.
- (reviewed 2026-10-19, A-C4) A second writer-only hook, `reconnectAllowed: (() -> Boolean)?`, is asked before every in-source reconnect attempt. The writer answers false once the reader is not at the head and the reconnect cap (§4.4) is used up; the source then ends the read with `LiveStreamException(RECONNECT_EXHAUSTED)` and the writer stops with `SLOT_LOST` without going to the ladder (toast SAVING_STOPPED_CONNECTION, or the paused header line). Default null = today's behaviour.
- **The ByteWatchdog keeps working, and better:** the pump is always inside `read()`, so silence while the player is paused now triggers a reconnect.
- Writer events (`LEASE`, `FIRST_BYTE`, `RECONNECT`, `RECONNECT_OK`, `ERROR`) go through the controller's `onWriterEvent(writerTuneId, …)` (§7.4), not `onSessionEvent` (reviewed 2026-10-19, A-B1).

### 4.3 TS guard: before or after the ring

| Repair | Where | Effect on saved content |
|---|---|---|
| Sniff, alignment, splice on a packet boundary, forced CC gap, post-splice PCR hold | `LiveTsDataSource`, **before** the ring | the ring holds the repaired stream |
| Segment boundaries (PTS/PCR jump, PSI change) | indexer, **at** the ring | readers never cross a timestamp step or a PSI change |
| Dead-track guard, stuck-audio detector, L33r audio regeneration, `FLAG_DETECT_ACCESS_UNITS`/`MULTI_PMT` compat, speculative CEA-608 | `GuardedTsExtractor`, **after** the ring, on every read | a mid-session escalation applies to rewound content, because each reader re-prepare builds a new extractor over the same bytes (`Seams.kt:76` per reader ctx) |

### 4.4 Saving state machine

`recording` is true from the first commit until one of the `RecordStop` cases below:

| Stop | When | Connection | Afterwards |
|---|---|---|---|
| `FULL_WHILE_PAUSED` | `claim` answers FULL while paused | released at once | Resume restarts the writer (`GAP` segment). Catch-up channel: at the gap the reader goes to the provider (§9.1). No catch-up: skip with GAP_SKIPPED_PAUSED |
| `HOLD_LIMIT` | paused longer than the hold setting (mono timer) | released | as above |
| `SLOT_LOST` (reviewed 2026-10-19, A-S2; lead ruling 3) | while the reader is **not** at the head: a limit-like status (403/458/503/509), `TINY_BODY` with 0 bytes, `RECONNECT_EXHAUSTED` with a limit status, or (reviewed 2026-10-19, A-C4) the reconnect cap: more than `PAUSED_RECONNECTS_MAX` (2) EOF/reset reconnects within `PAUSED_RECONNECT_WINDOW_MS` (60,000). On kick-oldest panels our reconnect *succeeds* by kicking the other device, so no limit status ever appears; the cap is what stops the ping-pong | released; **no** reconnect, no ladder | toast SAVING_STOPPED_CONNECTION (and paused header line). Resume or Back to live restarts the writer through the normal ladder |
| `PREEMPTED` | the broker pre-empts the writer's lease (for example the import's `PROBE`, `BrokerCore.kt:90-97`) while not at the head | already gone | as `SLOT_LOST` |
| `UPSTREAM_FAILED` | the writer-scoped ladder decided `Fail` (§7.4) | released | the reader plays the saved part, then the stashed error card |
| `DISK_ERROR` | `pwrite` throws (ENOSPC after the sparse fallback, EIO) | kept | §4.6 |
| `STORAGE_EVICTED` (reviewed 2026-10-19, A-S7) | the flusher sees `Os.fstat(fd).st_nlink == 0` (checked every `NLINK_CHECK_MS`, 1,000) | kept | the fd is closed at once so the space is really freed; §4.6 |
| `DISK_TOO_SLOW` | pool exhausted for 2 s | kept | §4.6 |
| `SESSION_END` | cycle ended | released | — |

**At the head** (viewer at live), a writer failure follows today's reconnect and ladder behaviour.

(Fix pass — playback, 2026-10-19, B1) **What the reconnect cap counts.** `LiveTsDataSource` passes the hook a `ReconnectCause` and whether the attempt opens the loss. Only a server close (`EOF`) or a reset count, once per loss, and a refusal (403/458/509, or a 200 that closes before its first byte) counts on every attempt. Our own watchdog cancel (`NO_BYTES`, including a cancelled body that ends with −1) and network trouble (connect errors, read timeouts, 5xx/408/429 and 503) never count. At the head the history is cleared, and so it is on pause, resume and restart, so reconnects at live are never carried into a pause. **Residual risk:** a panel that "kicks" by going silent without closing the socket now looks like our watchdog and is not counted. The fake panel needs `kick_mode=silent`, and I-TS-25 should decide on the device whether a silence right after a successful reconnect (a connection that lived under 5 s) ought to count.

### 4.5 Pause-only mode (reviewed 2026-10-19, A-S10)

`BufferMode.PAUSE_ONLY` applies with the setting value "Pause only" or under the daily cap.
- **At live**, the flusher skips blocks, `tail` follows the RAM window (the oldest block still in the pool), and the PLAYHEAD pin is **not** set. Invariant 2 then holds for `[tail, head)` = the RAM window.
- **On pause**, saving starts from the oldest block still in the pool. The player's buffer means the playhead may be up to 20 s *before* that block (`AdaptiveLoadControl` 10–20 s), so rewind after resume reaches back to roughly the pause point. The row's description says so.
- **A reader re-prepare at the pin** (§7.4) clamps to `max(pin, oldestRap)` in PAUSE_ONLY and after a disk stop; a `RingPositionLostException` is then an error, not routine.

### 4.6 Storage failures while playing or paused (reviewed 2026-10-19, A-S10, A-N7)

| State at the failure | Behaviour |
|---|---|
| At live | the reader continues from RAM. `localBuffer.available = false (STORAGE_ERROR)` for the rest of the cycle. Toast REWIND_STOPPED_STORAGE / REWIND_STOPPED_SLOW |
| Behind live | the reader keeps reading the file while `pread` succeeds. On a read error (or `STORAGE_EVICTED`), the LOCAL session jumps to live with REWIND_STOPPED_STORAGE |
| Paused | the frozen frame and the PAUSED pill stay. Resume continues from the file if it is readable; otherwise it jumps to live with the toast |

---

## 5. Reader `DataSource` contract (`RingDataSource`)

### 5.1 Contract

| Call | Behaviour |
|---|---|
| construction | by `TransportFactory.ring(ctx, RingOpen(ring, generation, startOffset, telemetry))`, which also registers the reader as a `Terminable` in the reader tune's transport scope (reviewed 2026-10-19, A-S9) |
| `open(dataSpec)` | opens a `RingReader` at `startOffset` (a RAP's packet). Injects the segment's PSI first, then the bytes. Returns `C.LENGTH_UNSET`. `dataSpec.position` is ignored and counted in telemetry; Media3 produces only 0 for our unseekable map. Blocks until one packet is readable, the ring ends, or cancel. (reviewed 2026-10-19, A-C2) It **never** rethrows a writer failure: a failure before the first byte reaches the controller only through `RingWriterListener.onFailure` (§7.4), and the controller's restart closes this reader, whose blocked `open()` then ends with `RingEndException(SESSION_END)`, which the classifier treats as stale (the reader's tune has ended). The only exceptions a reader surfaces are the `RingException`s of the next row |
| re-open (reviewed 2026-10-19, A-S9) | there is no "continue at the cursor". Every `IOException` from `RingDataSource` is a ring exception or a `LiveStreamException`, and both are fatal under `BoxLoadErrorHandlingPolicy`. A Media3 deferred retry would reset the sample queues and re-anchor the `TimestampAdjuster` (facts §2), which breaks `origin + position`; the controller re-prepares at the pin instead |
| `read(buf, off, len)` | returns `n > 0` whole packets. **Never** `C.RESULT_END_OF_INPUT`. At `head` it waits on the ring condition in `READER_WAIT_SLICE_MS` (250) slices, rechecking its cancel flag. At a segment boundary it throws `RingBoundaryException(nextOffset, reason, gapMs)`. At `head` after saving stopped it throws `RingEndException(stop, cause)`. Below `tail` it throws `RingPositionLostException`. On a disk read error it throws `RingIoException`. On a generation mismatch it throws `RingEndException(SESSION_END)` |
| cancel | `Loader.cancelLoading` interrupts the thread, and the wait throws `InterruptedIOException`. `close()` and `TuneSession.end()` (through the `Terminable`) set the flag and signal the condition first |
| `close()` | unpins and closes the reader; idempotent; never touches the writer |
| `getUri()` / `getResponseHeaders()` | the session placeholder / empty (S9) |

**PSI injection.** The injected packets are the segment's PSI (which is always current, §3.4). A duplicate or discontinuous PSI packet is harmless to `TsExtractor`, so CC values need no special care (reviewed 2026-10-19, A-S5).

**Copies.**
- From RAM: one sequence-checked `System.arraycopy` into Media3's array.
- From the file: `pread` into one per-reader direct staging buffer (`READER_STAGING_BYTES`, 64 KiB), then one `arraycopy` into Media3's array.

Either way it is the same count as today's aligner → Media3 copy (reviewed 2026-10-19, A-S8).

### 5.2 Telemetry seen by the stall detectors (reviewed 2026-10-19, A-B3)

`RingReaderTelemetry : StreamTelemetry` for the player session:

| Field | Value |
|---|---|
| `bytesTotal`, `lastByteAtMs`, `firstByteAtMs` | bytes delivered **to the player** |
| `readPendingSinceMs` | the **writer's** `readPendingSinceMs`, and only while this reader is blocked at the head; 0 otherwise |
| `inReconnect` | (writer `inReconnect` ∥ writer restart pending ∥ writer waiting for a lease) **and** the reader is at the head |
| `waitingForLease` | the writer's, only while the reader is at the head and before first media |
| `httpStatus`, `finalHost`, `redirects`, `reconnects`, `sniffed`, `bitrateBps` | the writer's (stats only) |

Behind live, the writer's state shows only in `localBuffer`. Detector (a) keeps its M2 meaning (a backstop for a failed ByteWatchdog). When it fires while the reader is at the head, `NO_BYTES` goes to the **writer-scoped** ladder, not to a reader re-prepare (§7.4).

### 5.3 Boundaries

A reader never crosses a boundary. Media3 surfaces `RingBoundaryException` once the buffered media has played out (M2 §3.3.4 timing). The controller then re-prepares at the next segment's first RAP, or hands off to the provider (§9.1). The boundary is excluded from the stats error count and from event-log `ERROR` rows (reviewed 2026-10-19, A-N2).

---

## 6. Seek strategy

**Chosen: re-prepare from a ring offset (M4's re-request model).** `controller.seekLocal(monoTarget, direction)` (main thread):

1. **Pick the RAP** (reviewed 2026-10-19, A-S4):
   - `direction < 0` (or none): `floorRap(target)`;
   - `direction > 0`: `ceilRap(target)`. If that is not later than the landed position, use the next RAP after it, so → is never a no-op on 5–10 s GOPs or on anchor-only streams;
   - before `oldestRap` → the oldest RAP, `Clamped`;
   - `LIVE` → the RAP at or before `newestPtsMono − liveLandingMs`, with `liveLandingMs = max(profile.startMs + LIVE_LANDING_EXTRA_MS (1,000), latencyAtTune)` (named so tests can assert against it, A-C3). `latencyAtTune` is the backlog measured on the cycle's first connection (newest PTS mono − first-media PTS mono). Jump-to-live therefore starts with as much media as a fresh tune and does not rebuffer or escalate `bufferFsm`.
2. **Set the PLAYHEAD pin** to that offset **before** closing the old reader.
3. **Re-prepare the reader only:** `launchSession(c, Plan.readerOnly(offset, originMono = ptsMono(rap)))` (§7.6). The last frame is held (M2 §3.1.1 step 2).
   - (amended 2026-11-11, ladder fix; `docs/review/2026-11-11-keep-decoders-design-review.md` §9/B2; fix pass after `docs/review/2026-11-11-decoder-repairs-and-zap-timing-review.md`) **Exception: a re-prepare that needs new decoders stops the player first.** Without `stop()`, Media3 only *disables* the renderers on `setMediaSource` + `prepare` (`ExoPlayerImplInternal.prepareInternal` and `seekToPeriodPosition` run with `resetRenderers = false`), and the next format reuses the same `MediaCodec` through `canReuseCodec`; the `MediaCodecSelector` is asked only when a codec is created (`MediaCodecRenderer.maybeInitCodecOrBypass`). So the codec policy written by `applySwitches` never reached the decoder. `Plan.freshDecoders = true` makes `launchSession` call `player.stop()` (through `stopExo()`, like every other stop) after ending the reader's transport and before the reader re-prepare. `ExoPlayerImpl.stop()` → `stopInternal(forceResetRenderers = false)` → `resetInternal(resetRenderers = forceResetRenderers || !foregroundMode)`; we never turn foreground mode on, so every renderer (video and audio) is reset and `MediaCodecRenderer.onReset` releases the codecs (Media3 1.11.1).
   - Set by: the ladder's `Reprepare` (L27a, and L27b with `raiseTsCompat`) after a `DECODER_STUCK`, `FROZEN_VIDEO` or `POSITION_STUCK` stall verdict, so that L27 repairs the same way with the buffer on as off (review S3: a stuck position is often a stuck audio decoder or sink, and the clock is the audio renderer's); `SoftwareVideoAndReprepare` when no player error preceded it; `setVideoDecoderOverride` and `reapplySettings` when the effective video decoder mode differs from the codec policy's current one (picking the mode already in effect re-prepares without a stop, review L6). Not set after a player error: Media3 has already reset the renderers then (renderer and runtime errors `stopInternal(true)`, IO errors `stopInternal(false)`, which resets with foreground mode off). Not set for tunnelling (the video renderer releases its codec on a tunnelling change), passthrough, stereo or software audio, which already took effect.
   - Kept: the cycle, the `TimeshiftSession`, the ring and its generation, the recorder and its connection (the broker lease is the writer's; `player.stop()` touches no transport), and the place: the reader re-opens at the pin taken before the stop, with `playWhenReady = !paused`. Only the player is stopped; `stop(reason)` is not called, and the IDLE callback is ignored because `session` is already null.
   - The viewer sees what an unbuffered ladder repair shows: the last frame is expected to stay while the codec is released (not cleared; unverified per SoC, m4 §1933), under the decision's waiting state, then the first frame from the same RAP. The `OPEN RING` event's detail ends with `fresh_decoders`; the log says `buffered re-prepare: player stopped first for new decoders`. Unbuffered cycles are unchanged (they always stop).
4. **Playhead** = `originMono + player.currentPosition`, because the fresh `TimestampAdjuster(0)` makes position 0 = the RAP's PTS (facts §1.3). It is reported as `PositionSnapshot.localMonoMs` and converted to wall/P at the boundary.

**Precision.** The playhead lands on the RAP before the target (backward) or after it (forward), within one GOP. The next step starts from the **landed** position: M4 B3 already settles there, and the preview shows the requested time until first media.

**Why not native seeking.** facts §1.1, §2, implications 1–4: a frozen input length, a timeline that is not dynamic, and `seekTo` on the unseekable item reloads from 0. A custom `SeekMap` wrapper is a phase-2 spike only (§19 R3).

**Latency budget.** First bytes < 20 ms; PSI injected; start on a RAP; codec re-init 100–300 ms **(verify, §22)**. Gate: commit → first frame P50 ≤ 600 ms, P90 ≤ 1,000 ms.

---

## 7. Controller integration (`PlaybackControllerImpl`, WP-B)

### 7.1 When a tune is buffered

A **live** cycle with format TS (or UNKNOWN → TS) gets a `TimeshiftSession` when the prefs length ≠ OFF, storage grants at least `MIN_RING_BYTES` (64 MiB), and the target is not a preview.
- (reviewed 2026-10-19, A-C1) The factory is split in two, because a `StreamSessionContext` needs the resolved stream:
  - `suspend fun prepare(prefs, maxConnections)`: IO only (the sweep wait, StatFs, mkdir, the first extent), started **in parallel with the resolve**; returns a `TimeshiftGrant` or a `BufferOff` reason;
  - `fun start(grant, writerCtx, listener)`: main thread, right after the resolve; builds the writer context from the resolved stream and starts the pump.
  The writer's first byte never waits for the first extent: the RAM pool covers the first seconds and the flusher waits for storage (reviewed 2026-10-19, A-S9, A-N5). This protects the ≤ 5 % zap gate.
- `c.timeshift` is created once per cycle, by `startCycle` (reviewed 2026-10-19, A-S6). One exception (reviewed 2026-10-19, A-C2): a ladder restart decided **before the cycle's first byte** (`!c.firstByteSeen`) runs `launchSession(Plan(recreateTimeshift = true))`, which closes the session and starts a new one from the same grant, so a first-open RetrySame/ReResolve keeps the cycle buffered.
- (reviewed 2026-10-19, A-B4 confirmation) The channel's `ArchiveFrames` are loaded at tune time (with the facts), not lazily by the first `ArchivePlayback` call, so a LOCAL pause in the first second of a tune never jumps when the frames arrive. Until they are known, the VM holds back `PlayerEvent.Buffer`.

### 7.2 Pause and resume rows (replace M2 §3.1.2 rows 10–12 for buffered cycles)

| State | Event | Action | Next |
|---|---|---|---|
| Playing (buffered) | `pause()` | `playWhenReady = false`; PLAYHEAD pin ← the RAP before the playhead; `ts.onPause(mono)`; **no** `livePauseJob`; `keepScreenOn = true` while saving (§11.5) | Playing(paused) |
| Playing(paused), saving | hold limit, full, slot lost | the writer stops, the connection is released, `localBuffer.saving = false, stop`, `keepScreenOn` released (except §11.5's catch-up rule) | Playing(paused) |
| Playing(paused) | `resume()` | `playWhenReady = true`; if saving stopped, restart the writer (fresh resolve, `GAP` segment) on `writerJob` | Playing |
| Playing (buffered) | `seekLocal(target, dir)` | §6 | Connecting (hidden by `ArchiveVisibility`) → Playing |
| Playing (buffered) | `jumpToLiveLocal()` | `seekLocal(LIVE)`; if saving stopped, restart the writer first and open at the new segment's first RAP | as above |
| any | zap / tune / `tuneArchive` / `stop` / `retry` / lifecycle stop | `ts.close()` **before** the next `launchSession` (writer call cancelled, lease released) | per M2 |
| Playing (unbuffered) | `pause()` / `resume()` | M2 rows unchanged | per M2 |

`position()` adds `localMonoMs`. The existing 1 Hz poll moves the PLAYHEAD pin while playing and publishes `localBuffer` (≤ 1 Hz).

### 7.3 Connection lifetime while paused (lead ruling 3)

`TimeshiftHold`:
- `AUTO` = until full, on every account;
- otherwise 10 min / 30 min / 1 h / until full.

While paused, the tune keeps exactly one connection until saving stops. Another device on a one-connection account cannot watch meanwhile; the row's description says so, and talks about **other devices** only (reviewed 2026-10-19, A-N6: in-app PLAYBACK and PROBE acquires pre-empt, so the app's own catch-up is not blocked).

A kicked or refused recorder stops (`SLOT_LOST`) and never reconnects while paused or behind, so there is no ping-pong with the other device (A-S2).

### 7.4 Ladder routing (reviewed 2026-10-19, A-B1, A-B3, A-S2; lead ruling 1)

**Classifier (commit 0).**
- `FailureClassifier.Facts` gains `writerTuneId: Long?`, and `live()` accepts either id.
- `classifyLive(e: LiveStreamException, facts): FailureSignal?` serves the writer path, which has no `PlaybackException`.
- Ring exceptions are classified **before** the `LiveStreamException` row (`RingEndException` carries one in its cause chain).

**Writer events.** `onWriterEvent(writerTuneId, type, code, detail)` records the zap milestones of the cycle's first connection (`leaseMs`, `firstByteMs`). It applies the `RECONNECT`/`RECONNECT_OK` state rules (Buffering(RECONNECTING), the Reconnecting toast) **only while the reader is at the head**.

**`writerJob`.** A cycle-level job, independent of `sessionJob`, which `launchSession` cancels on every seek. Writer-scoped decisions run on it.

| Source | Decision / signal | Handling |
|---|---|---|
| Writer, `LiveStreamException` before the first byte (reviewed 2026-10-19, A-C2) | any | **one route only**: `RingWriterListener.onFailure` → main → `classifyLive(e, facts with writerTuneId)` → ladder, decided once (the reader never rethrows, §5.1). Restart decisions run `launchSession(Plan(recreateTimeshift = true))` so the cycle stays buffered; `Fail` shows the card at once (nothing was saved) |
| Writer, while not at the head: limit-like, `TINY_BODY` 0 bytes, `PREEMPTED`, `RECONNECT_EXHAUSTED` with a limit status | — | stop saving (`SLOT_LOST`/`PREEMPTED`), no ladder (§4.4) |
| Writer, other failures, or (a) `NO_BYTES` at the head | **writer-scoped**: `RetrySame`, `ReResolve` (incl. `rotateBase`, `refreshAccount`), `UserAgentFallback` | restart the writer on `writerJob` (`WRITER_RESTART` segment). Reader untouched. No waiting state and no toast while behind |
| Writer | **cycle-scoped**: `SwitchSourceType`, `FlipXtreamOutput`, `TryBackupSource` | at the head: end the ring and launch as today. Behind: **deferred** until the reader reaches the head (the writer stopped as `UPSTREAM_FAILED`), then run |
| Writer | `Fail(error)` | at the head: `fail()` now. Behind: stash `(error, signal)` in the `TimeshiftSession` and stop with `UPSTREAM_FAILED`. On `RingEndException(UPSTREAM_FAILED)`, call `fail(c, stashed, signal)` directly. **The ladder decides once** |
| Writer `PCR_DISCONTINUITY` | — | does not occur with `onPcrJump` (§4.2) |
| Segment storm | `PcrDiscontinuity` | writer-scoped `ReResolve`; on a repeat within 5 min, cycle-scoped as M2 L25 |
| Reader `RingBoundaryException` | `FailureSignal.RingBoundary` | before the ladder: `TimelineRouter.onGap` decides (§9.1; reviewed 2026-10-19, A-C5): provider at the gap only on a catch-up channel **during an active time-shift** and when the gap came from saving stopping or is ≥ `GAP_PROVIDER_MIN_MS` (30,000); otherwise `seekLocal` at the next segment's first RAP, plus GAP_SKIPPED_* when `gapMs ≥ 1,000`. No counters |
| Reader `RingEndException` (non-failure stop) | — | restart the writer, then `seekLocal(LIVE)` |
| Reader `RingPositionLostException` / `RingIoException` | — | the oldest RAP, or live on an I/O error, with the toast of §4.6. The event log records `ring_position_lost` |
| Decoder and stall signals (b1, b1r, b2, c, d1, d2, codec) | reader-scoped `Reprepare*`, software, compat, `RepairAudioTimestampsAndReprepare`, `DropAudio…`, `SeekToLiveAndPrepare` (→ `jumpToLiveLocal`) | `launchSession(Plan.readerOnly(at = max(pin, oldestRap)))`: a time-shifted viewer keeps their place |

### 7.5 Lifecycle (lead ruling 4; reviewed 2026-10-19, U-B1)

- Home stops the tune at once; standby, HDMI-off and display-off stop it after the 30 s grace.
- The screensaver no longer ends a pause while saving, because the screen is kept on (§11.5). Once the screen is released and the device sleeps, that is the standby path.
- Either way the cycle ends, and the saved part and the file are discarded (close the fd, then unlink).
- `resumeAfterLifecycle` re-tunes live with an empty buffer. The first tune after a lifecycle stop that ended a **paused or behind** cycle shows LEFT_APP_CLEARED once.

### 7.6 Every `launchSession` call site (reviewed 2026-10-19, A-S6)

`Plan.readerOnly(startOffset, originMono)`:
- skips the facts, the resolve and the broker configuration;
- keeps `applySwitches` (codec and audio policies);
- never creates a `TimeshiftSession`.

| Call site | Buffered cycle |
|---|---|
| `startCycle` (`:596`) | closes any old session first; creates the cycle's `TimeshiftSession` |
| ladder `restart` (`:1583`) | per §7.4: reader-only, writer-only, or cycle |
| `reapplySettings` (`:508`) | reader-only at the pin (stays paused if paused). The early return for "a paused live tune resumes with a fresh tune anyway" (`:503`) is removed for buffered cycles. (amended 2026-11-11) A changed video decoder mode stops the player first (§6 step 3) |
| `setVideoDecoderOverride` (`:1915`) | reader-only at the pin; (amended 2026-11-11) when the effective mode changes, the player is stopped first, so the chosen decoder is created (§6 step 3) |
| `selectServer` (`:535`) | writer restart on the chosen base; reader untouched |
| seeks, boundaries, Back to live | reader-only (§6) |

(amended 2026-11-10) `LiveSettingsApplierImpl` now calls `reapplySettings` for a session paused in the local buffer (`ArchivePhase.PAUSED`, `TimelineSource.LOCAL`) through `ArchivePlayback.reapply`. The reader re-prepares at the PLAYHEAD pin, the RAP before the paused frame, so the paused picture and the resume point can move back by up to one GOP.

---

## 8. Disk policy and flash wear

### 8.1 Location

`context.cacheDir/timeshift/ring-<pid>.bin`: internal and app-private. The OS may reclaim it; that is detected through `st_nlink` (§4.4). USB/SAF is v2.

### 8.2 Size

```
lengthMs = setting (15/30/60/120 min); Auto: 30 min if total storage ≤ 16 GB, else 60 min (lead ruling 2)
rate     = ASSUMED_BYTES_PER_SEC (1,000,000) until RATE_PROBE_MS (20,000) after the first byte, then measured
reserve  = max(FREE_SPACE_RESERVE_MIN (1 GiB), total × 0.10)
capacity = floorToBlocks(clamp(lengthMs/1000 × rate × 1.15, 64 MiB,
                               min(8 GiB, available + currentFileBytes − reserve)))
```

- The capacity is final at `RATE_PROBE_MS`. Afterwards it only shrinks (ENOSPC, or the free-space watch of §3.1).
- Below 64 MiB the buffer is off (`NO_SPACE`).
- The effective window is `min(lengthMs, capacity / rate)`. When space caps it, the row's value reads "Auto (22 min: storage is limited)".

### 8.3 Creation and deletion

| Event | File |
|---|---|
| App start | sweep `cacheDir/timeshift/` for every file not named `ring-<this pid>.bin`; `prepare()` awaits the sweep |
| First buffered tune | create, write the header, allocate extents as the head grows |
| Zap | **logical reset** (`generation++`, after the old pump exits); the file is reused. (amended 2026-11-10, review L3) The ring is reused when its capacity equals the new sizing or is smaller by at most max(2 blocks, 1 % of the new sizing); a larger ring, a capacity outside that tolerance, a different length, index size, pool or location, or a debug ring size (`TimeshiftDebugOverrides.ringBytes`, exact) rebuilds it. A smaller ring keeps the §8.2 reserve; the window is at most 1 % shorter until the next rebuild. The grant carries the ring's actual capacity. |
| Stop, lifecycle stop, length set to Off, next tune unbuffered, `onTrimMemory(TRIM_MEMORY_COMPLETE)` | close the fd, then delete |

### 8.4 Flash-wear budget (lead ruling 2)

Writes per day = stream rate × hours saved per day.

Assumptions (none of them verified; §22):
- 1 MB/s, which is 3.6 GB per hour saved;
- an 8 GB eMMC with 1,000 P/E (conservative) to 3,000 P/E;
- write amplification 1.5 for large sequential writes into a preallocated file.

So TBW ≈ 5.3 TB (1k) to 16 TB (3k). If only the free ~4 GB takes part in wear levelling, the years in the table halve (reviewed 2026-10-19, A-N4).

| Saving per day | GB/day | Endurance used after: 1k P/E | 3k P/E | 1k P/E, free-space-only levelling |
|---|---|---|---|---|
| 2 h | 7.2 | ≈ 2.0 y | ≈ 6 y | ≈ 1.0 y |
| 3.3 h = the Auto cap of 12 GB | 12 | ≈ 1.2 y | ≈ 3.6 y | ≈ 0.6 y |
| 8 h (no cap) | 28.8 | ≈ 6 months | ≈ 1.5 y | ≈ 3 months |
| Pause only, 30 min/day | 1.8 | ≈ 8 y | — | ≈ 4 y |

**Mechanism.**
- A per-device counter of the bytes saved today (reset at local midnight) lives in `noBackupFilesDir/timeshift-writes`, updated every 64 MiB.
- With *Protect built-in storage* = Auto, reaching `DAILY_LIMIT_SMALL` (12 GB, on ≤ 16 GB storage; no cap above) switches the session to PAUSE_ONLY at the next moment nothing needs persisting. A running time-shift is never cut.
- No numbers are shown. REWIND_LIMITED_TODAY appears once a day, when a ⏪/← reaches the start of the shortened window (reviewed 2026-10-19, U-S2, with the lead's 12 GB).
- WP-D's debug build tries to read eMMC `life_time` / `pre_eol_info` (`/sys/class/mmc_host/mmc0/mmc0:*/`) on the Fire TV and Onn. If they are readable, the M6 soak records them to replace these assumptions (A-N4).

---

## 9. Coexistence with the provider archive (lead ruling 4)

### 9.1 Routing (pure `TimelineRouter`, `:core`)

Notation: P frame, `localFrom = P(oldestRap)`, `edge = P(live)`.

| Target or event | Channel has archive | Result |
|---|---|---|
| `≥ localFrom + LOCAL_MARGIN_MS` (5,000) and not within the live snap | any | **Local** |
| within `LIVE_SNAP_MS` of the edge and later than the playhead (direction-aware, M4 P-S1) | any | **Live** (local) |
| older than the buffer | yes | **Provider**: M4's live-anchored request (`backMs = edge − target`); ends the ring |
| older than the buffer | no | **Clamp** to `localFrom`. Toast REWIND_START, or REWIND_NOTHING_YET within 5 s of tuning, or REWIND_LIMITED_TODAY when the daily cap shortened the window |
| reader reaches a `GAP`/`WRITER_RESTART` boundary **during an active LOCAL time-shift** (paused or behind), and the gap came from saving stopping (`FULL_WHILE_PAUSED`, `HOLD_LIMIT`, `SLOT_LOST`, `PREEMPTED`) or is ≥ `GAP_PROVIDER_MIN_MS` (30,000) (reviewed 2026-10-19, U-B2; A-C5) | yes | **Provider** at the gap's start (`backMs = edge − P(gapStart)`); ends the ring. No toast |
| any other gap ≥ 1 s (a short reconnect hiccup, or the reader at the head) (A-C5) | yes | Local at the next segment; GAP_SKIPPED_STREAM. Local rewind is kept, and a live viewer is never pushed into an archive request |
| any gap ≥ 1 s | no | Local at the next segment, GAP_SKIPPED_PAUSED or GAP_SKIPPED_STREAM |

- **Pause** on a buffered channel never starts an archive request. On an unbuffered archive channel (HLS, buffer off), M4 §3.8.7 is unchanged.
- **After a provider hand-off** the ring is gone. Back to live re-tunes with an empty buffer, and a later short rewind goes to the provider again, which only differs in speed.
- **Start over** of the programme on now goes local when the buffer covers its start, else to the provider (catch-up channels), else NO_CATCHUP (unchanged).

### 9.2 Frames (reviewed 2026-10-19, A-B4; lead ruling 1)

`LocalTimelineBackend` maps buffer times into M4's programme frame with the same affine shift M4 uses for the live-pause point:
- `P(t) = frames.liveEdge(deviceWall(t)) = deviceWall(t) + skewMs + correctionMs`, where `deviceWall` comes from the snapshot's mono → wall conversion;
- the inverse maps a P target back to mono before `seekLocal`;
- for channels without archive, `frames = (correction 0, skew from the playlist's TimeBase, epg shift)`.

Then `bufferFromMs`, `wallMs`, the LOCAL playhead and `liveEdgeMs` are all in one frame, and `TimelineRouter` compares like with like. A hand-off to the provider is expressed as a live-anchored distance, which M4 B2 turns into a correct panel request for every sign of the correction.

---

## 10. HLS live

- **Not buffered in this feature.**
- **The Pause item is present** (reviewed 2026-10-19, U-B3). OK freezes as M2 does (`pausePlain()`, release after `LIVE_PAUSE_RELEASE_MS`) and shows PAUSE_NO_REWIND_HLS at once (the Xtream or M3U variant). Resume shows BACK_TO_LIVE_UNBUFFERED. RW shows PAUSE_NO_REWIND_HLS.
- **Follow-up (lead ruling 4):** use the HLS playlist's DVR window (`isCurrentMediaItemSeekable`, window ≥ `HLS_DVR_MIN_WINDOW_MS`, 120,000) as a separate item in the master plan; WP-D records it.
- **Measurement for that item (reviewed 2026-10-19, U-Q6):** the M6 hardware week logs the share of the owner's tunes that are HLS. Above about 20 %, the DVR-window spike is scheduled next.

---

## 11. State machine and UI

### 11.1 `PlayerStateMachineImpl` (WP-C)

**New input:** `PlayerEvent.Buffer(info: LocalBufferInfo?)` (≤ 1 Hz).

**Predicates:**
- `bufferCapable() = info?.available == true && !tuned.isArchive`;
- `timeShiftCapable() = archiveCapable() || bufferCapable()`;
- `archiveFromNow()` uses `info.fromMs` (P frame) when the channel has no archive and no session exists.

| Start | Key | Condition | Action | Result |
|---|---|---|---|---|
| live, bare | PLAY_PAUSE / PAUSE / quick bar **Pause** | `timeShiftCapable()` | `effects.pauseLive(ch)`; the scrub bar opens at the pause point (focused when it came from the quick bar, unfocused for a media key) | status PAUSED (LOCAL); pill "PAUSED · 0:00 behind" growing |
| live, bare | same | not capable | `effects.pausePlain()`; the reason toast at once (PAUSE_NO_REWIND_HLS / _OFF / _SPACE) | M2 freeze |
| PAUSED (LOCAL) | PLAY_PAUSE / PLAY / SELECT in the scrub bar | — | `effects.archiveResume()` | PLAYING (LOCAL); pill "4:12 BEHIND" |
| PLAYING (LOCAL) | PLAY_PAUSE / PAUSE | — | `effects.archivePause()` | PAUSED (LOCAL) |
| live, bare | SEEK_BACK | `timeShiftCapable()` | `rewindLive(ch, 30 s)`, routed by §9 | SEEKING → PLAYING (LOCAL) or provider |
| live, bare | SEEK_BACK | not capable | the reason toast | — |
| LOCAL, scrub | ←/→/FF/RW, commit | — | `archiveSeek(t, seq)`, with the direction from the key | settle per M4 B3 |
| LOCAL | ↑ in the scrub bar, quick bar **Back to live**, MEDIA_STOP | — | `jumpToLive()` → `jumpToLiveLocal()` | status IDLE; pill LIVE |
| PAUSED (LOCAL) ≥ `ZAP_CONFIRM_AFTER_PAUSE_MS` (120,000) | ZAP_UP/ZAP_DOWN (CH±, MEDIA_NEXT/PREVIOUS, PAGE_UP/DOWN), PREVIOUS_CHANNEL (Last/Blue), number-entry commit (lead ruling 6) | first press | no zap; toast ZAP_CONFIRM (ZAP_CONFIRM_NUMBER for digits: the number stays on screen) and `zapConfirmUntil = now + 3,000` | — |
| same | same key again (OK or the same commit for digits) within 3 s | — | zap | buffer discarded |
| any | channel list, guide tune, search tune | — | zap at once | buffer discarded |
| live | START_OVER | archive, or the buffer covers the programme start | `effects.startOver(ch)` → local or provider | — |

`GuideStateMachineImpl`'s number entry asks the player machine through `PlayerStateMachine.zapNeedsConfirm(nowMs): Boolean` (commit 0) before committing a digit zap in FULL.

`player_keys.csv` gains the `bufferCapable` axis (buffered without archive, buffered with archive, HLS without archive) and the zap-confirm rows. The five-key reachability proof re-runs from four start states.

### 11.2 `ArchivePlayback` LOCAL source (WP-C, `app/.../playback/LocalTimelineBackend.kt`)

`ArchivePlaybackImpl` delegates to it while `controller.localBuffer.value?.available == true` for the tuned identity.

(reviewed 2026-10-19, A-C1) `LocalTimelineBackend` is the **only** converter from the controller's mono times to the programme frame (§9.2). It publishes the P-frame `LocalBufferInfo` as `ArchivePlayback.localBuffer: StateFlow<LocalBufferInfo?>`, and the player VM builds `PlayerEvent.Buffer` from that flow, never from the raw `LocalBufferState`.

| Call | Behaviour |
|---|---|
| `pauseLive(ch)` | `controller.pause()`. Status PAUSED, `source = LOCAL`, `timeShift = true`, `wallMs` = the P playhead, `advancing = false`, `liveEdgeMs` = `P(now)`, `archiveFromMs` = the provider's start (archive channels) or `bufferFromMs` |
| `resume()` | `controller.resume()`; PLAYING; 1 Hz polling of `position().localMonoMs` |
| `seekTo(t, seq)` / `rewindLive(ch, back)` | through `TimelineRouter` (§9). Local → SEEKING, `controller.seekLocal(mono(t), dir)`, settled at first media of the new tuneId. Provider → M4's existing code with `backMs` |
| `jumpToLive()` | `controller.jumpToLiveLocal()`; SEEKING, then IDLE |

- **Ownership:** while the controller target is the identity, `isArchive == false` and `tuneId ≥ ownedTuneId`.
- **Gap hand-off:** `RingBoundary` with a gap on an archive channel arrives as a controller notice (`LocalGap(gapStartMono)`), and the backend issues the provider request (§9.1).
- **Availability lost** while LOCAL (reviewed 2026-10-19, A-N7): §4.6. A FlipXtreamOutput to HLS behind live is deferred (§7.4).

### 11.3 Quick bar (reviewed 2026-10-19, U-B3; lead ruling 1)

`QuickItem.TIME_SHIFT` keeps its id (append-only), is labelled **"Pause"**, and is reordered:

| State | Order |
|---|---|
| Live (every live channel: TS, HLS, buffer off, no space) | **Pause** · Audio · Picture shape · Subtitles · Favourite · Start over · Previous · Stream info · Decoder · Screen off · (Sources) · Settings |
| Behind live (LOCAL or provider time-shift, catch-up) | **Back to live** · Audio · Picture shape · Subtitles · Favourite · Previous · Stream info · Decoder · Screen off · (Sources) · Settings |

- OK on **Pause** pauses at once and opens the **focused** scrub bar at the pause point; ← in it rewinds.
- On an unbuffered channel, the same OK calls `pausePlain()` and shows the reason toast.
- There is no separate "Rewind 30 s" item.
- `START_OVER` is shown when the channel is archive-capable or the buffer covers the start of the programme on now.
- WP-D records the order change as an amendment to `decisions/2026-10-15-player-keys-and-quick-bar.md`, with §11.7 as the reason.
- One-time tip on the first pause done with OK (reviewed 2026-10-19, U-N6): "Tip: ↑ then OK pauses live TV."

### 11.4 Pills, visibility, scrub bar

**Pill text** (reviewed 2026-10-19, U-S7; lead ruling 5). Text ≥ 24 design px; no leading zero (`4:12`, `1:04:12`).

| State | Full screen | Mini player (unchanged) |
|---|---|---|
| Paused | **PAUSED · 4:12 behind** (grows) | PAUSED −4:12 |
| Playing behind | **4:12 BEHIND** (constant) | −4:12 |
| Live | **LIVE** | LIVE |

`PillRules` logic is unchanged; the format lives in `PlayerParts`.

**Visibility.** `ArchiveVisibility.visible`'s early return `!tuned.isArchive` becomes `!tuned.isArchive && status.source != LOCAL` (commit 0). A local hand-off's `Connecting` is then hidden like a provider hand-off's.

**Scrub bar scope** (reviewed 2026-10-19, U-S3; lead ruling 5).

| Channel | Scope | Drawing (design px, 1920 canvas) |
|---|---|---|
| No catch-up | `[edge − rewindLength, edge]`, fixed width; the saved part grows from the right. Programme boundaries are ticks with the title above; chips unchanged | track `#FFFFFF` 18 %, 8 px; saved `#FFFFFF` 45 %; watched up to the playhead in the accent at 100 %. Not saved (a gap, or after saving stopped): track colour plus a 4 px `#F2A93B` underline, and when ≥ 120 px wide the label **"Not saved"** at 22 px. No hatching. End labels at 24 px under the bar: left **"Rewind from 21:02"** (display frame), right **"LIVE"** with the live time above the marker |
| Catch-up | the programme (M4 §3.5.1) | **no** band; everything left of live is reachable |

`ScrubState` gains `scope: ScrubScope {PROGRAMME, REWIND_WINDOW}`, `windowFromMs`, `savedFromMs`, `notSaved: TimeRanges` (start/end pairs, ≤ `MAX_SEGMENTS`; content equality, A-C1), `header: PausedHeader?` and `hint: Boolean`. A skip across a gap plays a 0.5 s skip-forward animation on the bar (reviewed 2026-10-19, U-N2).

### 11.5 Paused screen: keep-awake, header, dim card (reviewed 2026-10-19, U-B1, U-S4)

**Keep-awake.** `keepScreenOn = !paused || (bufferedPause && saving) || (catchUpPause && pausedFor < PAUSE_KEEP_AWAKE_MAX_MS)`.
- `PAUSE_KEEP_AWAKE_MAX_MS` = 10,800,000 (3 h). On a catch-up channel the provider covers the rest of the pause, but the screen is not kept on forever.
- When the screen is released, the device may sleep, which ends the cycle by the standby path.

**Header line** under the pill (24 px), while paused. The variant is chosen by whether the device has reported a media key (product line 94): "Press OK" on remotes without media keys, "Press OK or ▶" otherwise.

| State | Header |
|---|---|
| Paused, saving | **Can stay paused 17 more min** (min(hold remaining, free capacity ÷ rate), whole minutes) |
| Paused, full, no catch-up | **Saving stopped: rewind holds 30 min. Press OK to continue from where you paused.** |
| Paused, hold limit | **Saving stopped after 10 min to free your provider connection. Press OK to continue from where you paused.** |
| Paused, slot lost (lead ruling 3) | **Saving stopped: another device is using your provider connection. Press OK to continue from where you paused.** |
| Paused, catch-up channel, saving stopped | **Can stay paused up to 3 h: catch-up covers the rest.** (U-S4's "as long as you like", bounded by the keep-awake limit; §21) |

**Dim card.**
- After `PAUSED_DIM_AFTER_MS` (120,000), dim the frozen frame to 35 % with an app-drawn alpha layer over the hole. It is a plain Compose layer, never on an ancestor of the `SurfaceView` (CLAUDE.md).
- Show a card that moves every `PAUSED_CARD_DRIFT_MS` (60,000):
  > **Paused** · 12:40 behind live
  > Can stay paused 17 more min · OK or ▶ to continue
- Any key restores the picture and is **consumed** (like SCREEN_OFF), so the first OK only wakes the screen. The next OK continues, whether the bar is unfocused, closed or focused (fix pass 2026-10-19, U-B1).
- (Fix pass 2026-10-19, WP-D question) The dim and the zap confirm both start at 2 min. A single-key zap (CH±, Last, Up/Down under "Change channel") that wakes the picture also asks the confirm in the same press, so the second press zaps: two presses, never three.
- The card's body shows the whole header line, next step included, on up to three lines (card budget 216 px), and the header comes from `PlayerUiState.paused`, so it is there with the bar closed (U-S4).

### 11.6 Focused bar idle and hint row (reviewed 2026-10-19, U-S6, U-N1)

- After a SELECT that resumes with no preview pending, focus stays but `SCRUB_FOCUSED_IDLE_MS` (5,000) is armed while playing. When it expires, the bar unfocuses and hides, and the next OK opens the channel list as on bare video.
- **Hint row** in the focused bar (22 px, 60 % white): "◀ ▶ Rewind / forward    OK Pause / play    ▲ Live". On devices that have reported ⏪/⏩, add " ⏪ ⏩". The hint shows only while the bar is focused, and only the first `HINT_SHOW_COUNT` (3) times per install (counter in prefs).

### 11.7 Press journeys (reviewed 2026-10-19, U-N4)

Each journey starts from live, full screen, on a TS channel without catch-up, with default settings. "Commit" is M4's automatic 600 ms commit, so it needs no key.

| Journey | Fire TV (⏯ ⏪ ⏩) | Onn / CCwGTV (no media keys) | Sony (▶ ⏸ ⏪ ⏩ ■) |
|---|---|---|---|
| Pause | ⏯ = **1** | ↑, OK = **2** (any live channel) | ⏸ = **1** |
| Resume where you left off | ⏯ = **1** | OK = **1** (the bar hides after 5 s) | ▶ = **1** |
| Rewind 30 s | ⏪ = **1** | ↑, OK, ←×3, OK (play; a commit while paused stays paused, M4 E26) = **6** | ⏪ = **1** |
| Rewind 2 min | ⏪×4 = **4** (two seeks: the first ⏪ commits at once) | ↑, OK, ←×12, OK = **15**; holding ← = **4 actions** | ⏪×4 = **4** |
| Back to live while behind | ↓, ↑ = **2** (or ↑, OK: Back to live is first) | ↑, OK = **2** | ■ = **1** |
| Pause on HLS without catch-up | ⏯ = 1 (freeze plus reason) | ↑, OK = 2 | 1 |

---

## 12. Settings rows (reviewed 2026-10-19, U-B4, U-S1, U-S2, U-S10; lead rulings 1–3)

| Row id | Label | Values (default) | Visibility | Effect | Description |
|---|---|---|---|---|---|
| `playback.timeshift` | Pause and rewind | Auto (30 min) · 15 min · 30 min · 1 hour · 2 hours · Pause only (no rewind) · Off. Auto = 30 min on ≤ 16 GB storage, else 1 hour; capped: "Auto (22 min: storage is limited)" | **SIMPLE** | NEXT_TUNE; Off deletes the file at once | "Saves the channel you're watching so you can pause and rewind up to 30 min. Uses up to 1.9 GB of storage while you watch; cleared when you change channel." Pause only adds: "Pause only saves while you're paused, so after you continue you can rewind only to about where you paused." |
| `playback.timeshift_hold` | Keep recording while paused | Auto · 10 min · 30 min · 1 hour · Until full | ADVANCED | LIVE | "While paused, The Box keeps using your provider connection so it can keep saving. On a one-connection account, other devices can't watch until this time is up. Auto: until rewind is full." |
| `playback.timeshift_protect` | Protect built-in storage | Auto · Off | ADVANCED | LIVE | "On small devices, The Box stops saving for rewind after about 3 hours of TV a day, to spare the storage chip. Pause still works." |
| `playback.on_launch` (existing) | On launch | unchanged | **ADVANCED** (was SIMPLE; lead ruling 1) | unchanged | unchanged |
| `playback.buffer` (existing) | Buffer | unchanged | SIMPLE | unchanged | (reviewed 2026-10-19, U-N7) "Smooths playback on slow connections. To pause and rewind, see Pause and rewind." |

The simple PLAYBACK rows stay at 5 (Buffer, AFR, Audio output, Picture shape, Pause and rewind) and the total stays 35. `SimpleRowCountTest` needs no change. WP-D records the demotion as an amendment to M5 §3.3.13.

```kotlin
// SettingsStore.kt (additive; defaults keep old documents valid)
@kotlinx.serialization.Serializable enum class TimeshiftLength { AUTO, MIN_15, MIN_30, H_1, H_2, PAUSE_ONLY, OFF }
@kotlinx.serialization.Serializable enum class TimeshiftHold { AUTO, MIN_10, MIN_30, H_1, UNTIL_FULL }
@kotlinx.serialization.Serializable enum class StorageProtection { AUTO, OFF }
// PlaybackPrefs gains:
    val timeshift: TimeshiftLength = TimeshiftLength.AUTO,
    val timeshiftHold: TimeshiftHold = TimeshiftHold.AUTO,
    val storageProtection: StorageProtection = StorageProtection.AUTO,
```

Backups carry the three values. The daily counter is device state and is not backed up.

---

## 13. Viewer strings (reviewed 2026-10-19, U-S5, U-S9, U-§5; lead ruling 5)

**Rules.**
- A toast is ≤ 2 lines: the reason, then the next step.
- Times are clock times or "N min".
- Settings paths use " → " and include "Show advanced settings" when the row is advanced. (Fix pass 2026-10-19, U-S5) It is the sections pane's pinned toggle, so it comes first: "Settings → Show advanced settings, then Playback → …". A SIMPLE row's path has no such step.
- (Fix pass 2026-10-19, U-S6) Each PAUSE_NO_REWIND_* text is a reason and a next step (two strings); BACK_TO_LIVE_UNBUFFERED appends that step once per tune.
- `NO_CATCHUP` stays only for catch-up-specific actions (Start over, past programmes).
- `player_toast_no_catchup`'s pause/rewind use and the reason-less `player_toast_back_to_live` / `player_notice_live_resumed` are replaced by the rows below (U-S9).

The UX review's table is used verbatim. Rows marked *(new)* were added for the lead rulings:

| Key | When | Text |
|---|---|---|
| PAUSE_NO_REWIND_HLS (Xtream playlist) | Pause or RW on HLS without catch-up | "This channel can't be rewound, so Play returns to live. It streams as HLS; TS can fix it: Settings → Show advanced settings, then Playlists → (playlist) → Stream format." (fix pass 2026-10-19, U-S5: "Show advanced settings" is the sections pane's toggle, not a row) |
| PAUSE_NO_REWIND_HLS (M3U) | same, M3U playlist | "This channel can't be rewound, so Play returns to live. It streams as HLS; ask your provider for TS links." |
| PAUSE_NO_REWIND_OFF | Pause or RW with the buffer Off | "Rewind is turned off, so Play returns to live. To keep your place: Settings → Playback → Pause and rewind." |
| PAUSE_NO_REWIND_SPACE | Pause or RW with no space | "Not enough free storage to save this channel (0.6 GB free), so Play returns to live. Free up space or choose a shorter rewind in Settings → Playback." |
| PAUSE_NO_REWIND_FORMAT *(new, fix pass 2026-10-19, U-S7)* | Pause or RW on a live stream that is neither TS nor HLS (`BufferOff.NOT_TS`, `hls == false`) | "This channel can't be rewound, so Play returns to live. Its stream isn't TS, the format The Box can save; ask your provider for TS links." |
| PAUSE_NO_REWIND_STORAGE *(new, fix pass 2026-10-19, U-S7)* | Pause while the buffer is off after a storage failure (RW keeps REWIND_STOPPED_STORAGE) | "Saving for rewind failed on this device's storage, so Play returns to live. Change channel and back to try again." |
| BACK_TO_LIVE_UNBUFFERED | resume after an unbuffered pause | "Back to live: this channel can't be rewound, so the paused part was skipped." (with the next step of the reason above, once per tune) |
| REWIND_NOTHING_YET | ⏪/← within 5 s of tuning, no catch-up | "Nothing to rewind yet: The Box saves this channel from when you tune in, up to 30 min." |
| REWIND_START | reached the oldest saved point, no catch-up | "That's as far back as you can go (21:02, when you tuned in). This channel has no catch-up for earlier." When the oldest point is more than `LOCAL_MARGIN_MS` after the cycle's first saved instant (the window is full, or saving restarted; fix pass 2026-10-19, U-S8): "That's as far back as you can go (21:02: rewind holds 30 min). This channel has no catch-up for earlier." |
| GAP_SKIPPED_PAUSED | crossed a gap left by a long pause (no catch-up) | "Skipped 12 min that weren't saved while paused. Now at 21:26." |
| GAP_SKIPPED_STREAM | crossed a gap left by a dropped stream (no catch-up) | "Skipped 40 s the provider didn't send. Now at 21:26." |
| REWIND_STOPPED_STORAGE | `DISK_ERROR`, `STORAGE_EVICTED` | "Rewind stopped: saving to storage failed. Live TV continues; change channel and back to try again." |
| REWIND_STOPPED_SLOW | `DISK_TOO_SLOW` | "Rewind stopped: this device's storage can't keep up with this channel. Live TV continues. Try Pause only in Settings → Playback → Pause and rewind." |
| REWIND_LIMITED_TODAY | a rewind reaches the start shortened by the daily cap, once a day | "Rewind is shorter for the rest of today to protect this device's storage. Pause still works. Change it in Settings → Show advanced settings, then Playback → Protect built-in storage." (fix pass 2026-10-19, U-S5) |
| LEFT_APP_CLEARED | first tune after Home or standby ended a paused or behind cycle | "Back to live: leaving The Box clears what was paused. Stay in The Box to keep your place." |
| ZAP_CONFIRM | single-key zap after a pause of ≥ 2 min | "Changing channel ends your pause (20 min saved). Press again to change." |
| ZAP_CONFIRM_NUMBER *(new, lead ruling 6)* | digit zap after a pause of ≥ 2 min | "Changing channel ends your pause (20 min saved). Press OK to change." |
| SAVING_STOPPED_CONNECTION *(new, lead ruling 3)* | `SLOT_LOST`/`PREEMPTED` while behind (while paused: the header line instead) | "Saving for rewind stopped: another device is using your provider connection. What was saved still plays; then you're back at live." |
| JUMPED_TO_LIVE_RESTART *(new, A-B1 deferred decision)* | a cycle-scoped decision ran after the saved part played out | "The stream had to restart, so you're back at live. Rewind saves again from now." |
| Paused header lines | §11.5 | see §11.5 |
| Hint row | §11.6 | "◀ ▶ Rewind / forward    OK Pause / play    ▲ Live" |
| Scrub end labels | §11.4 | "Rewind from 21:02" · "LIVE" · "Not saved" |
| Quick bar items | §11.3 | "Pause" (live) · "Back to live" (behind) |
| Stats overlay line (reviewed 2026-10-19, U-N3) | stats on | "Rewind: 18:20 saved of 30:00 · 1.1 GB" |

An error card reached after the saved part has played out uses the ladder's card, with the detail line **"You've watched everything that was saved before the stream failed."**

---

## 14. Failure modes

| Failure | Detection | Behaviour |
|---|---|---|
| Server close or reset, continuous PCR | `LiveTsDataSource` reconnect | seamless splice (`SPLICE`); invisible behind live |
| Reconnect with a PCR jump | `onPcrJump` plus the indexer's PTS jump | boundary; a ~0.5 s hold at the hand-off |
| PCR/PTS jump inside one connection | stream-domain check (§3.4) | same |
| PMT change | PSI version | `PSI_CHANGE` boundary |
| Connect or reconnect burst | — | no segment (stream domain) |
| Reconnect budget exhausted | writer → writer-scoped ladder | reader untouched behind live; at the head, Buffering(RECONNECTING) as today |
| Slot kicked or refused while paused or behind | limit-like status / `PREEMPTED` | `SLOT_LOST`: stop, no fight, toast or header |
| Ladder `Fail` while behind | stash | the saved part plays out, then the card (decided once) |
| Ring full while paused | `claim` FULL | saving stops; the connection is released |
| Ring full while playing behind | `claim` FULL | the pump waits; a gap only after `PIN_STALL_MS` |
| Disk full, EIO, eviction, slow disk | write error / `st_nlink` / pool timeout | §4.6 |
| Wall-clock step | — | none: everything inside runs on mono |
| Old pump after a zap | generation token | the late commit is dropped |
| Old reader after a zap | generation token | `RingEndException(SESSION_END)` on its next read |
| Screensaver or sleep while paused | keep-awake while saving (§11.5) | after release: standby path, discard, LEFT_APP_CLEARED |
| Process death | — | swept at the next start |
| Segment storm | 6 in 60 s | writer ReResolve, then L25 |

---

## 15. Memory, CPU and performance gates

| Budget / gate | Value | Measured by |
|---|---|---|
| User-space copies | write path: 0 extra (the pump reads into direct-backed blocks; `pwrite` from the direct buffer is a kernel copy only). Read path: one `arraycopy` into Media3's array (RAM, or `pread` → direct staging → `arraycopy`), the same count as today (A-S8) | `RingAllocationTest` with a copy-count hook |
| Allocation per packet / per block, steady state | 0 / 0 | `RingAllocationTest` (`getThreadAllocatedBytes` over 10,000 blocks) |
| Extra heap | ≤ 4.7 MiB (blocks 4.0, index ≤ 0.51, segments ≤ 0.1, two staging buffers 0.13); low-RAM ≤ 2.4 MiB | size assertions; heap dump in I-TS soak |
| Threads | +1 per buffered tune (`box-ts-pump`), +1 per process (`box-ts-flush`) | thread dump |
| Writer CPU | ≤ 2 % of one core at 1 MB/s, Fire TV Stick 4K | `/proc/self/task/<tid>/stat`, 60 s (M6 gate; logged on the emulator) |
| Disk write rate | stream rate × ≤ 1.01 | ring counter vs `/proc/self/io` |
| Live-edge latency added | ≤ 20 ms | commit vs reader return timestamps |
| Seek, commit → first frame | P50 ≤ 600 ms, P90 ≤ 1,000 ms | per-seek record |
| Resume after pause → video advancing | ≤ 150 ms | debug probe |
| Jump to live | rebuffers in the next 5 min ≤ a fresh tune's | I-TS-26 |
| Zap time with the buffer on | ≤ 5 % regression vs off (**hard gate**, U-§8) | I-TS-22, M6 hardware |
| Connections while paused | exactly 1 until saving stops, then 0 | fake-panel slot log |
| Soak, 2 h behind live, wrapping | heap flat; file = capacity; PSI and LMK kills logged (page-cache churn on 1.5 GB) | I-TS-21 |

---

## 16. Contracts (commit 0)

> **Amended 2026-10-19** by "Lead reconciliation — WP-A/WP-B" (end of this document, items R1–R14). Where the listings below and that section differ, the reconciliation and the code win.

### 16.1 `core/.../playback/timeshift/TimeshiftContracts.kt` (new, pure JVM)

```kotlin
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import java.io.IOException
import java.nio.ByteBuffer

enum class SegmentReason { FIRST, PCR_JUMP, INLINE_PCR_JUMP, PSI_CHANGE, WRITER_RESTART, GAP }
enum class RecordStop { FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST, PREEMPTED, UPSTREAM_FAILED, DISK_ERROR, STORAGE_EVICTED, DISK_TOO_SLOW, SESSION_END }
enum class BufferOff { SETTING_OFF, NOT_TS, NO_SPACE, STORAGE_ERROR, ARCHIVE }
enum class BufferMode { PERSIST, PAUSE_ONLY }
enum class ClaimResult { OK, FULL, DISK_STALLED, STOPPED, STALE_GENERATION }

object RingFlags { const val RAP = 1; const val SPLICE = 2; const val ARRIVAL_CLOCK = 4; const val SEGMENT_START = 8 }
object PinOwner { const val PLAYHEAD = 0; const val RECORDER_V2 = 1; const val FIRST_READER = 8 }

/** A writable window in the current direct-backed RAM block (whole packets). Owned by the writer, reused. */
class RingSlot { var array: ByteArray = EMPTY; var offset = 0; var length = 0; var generation = 0L; private companion object { val EMPTY = ByteArray(0) } }

/**
 * File I/O behind the ring (§3.1). WP-A uses it; WP-B implements it over `Os`/FileChannel and StatFs. Positional
 * calls are thread-safe against each other (pwrite on the flusher, pread on readers). Never called on main.
 */
interface RingStorage : AutoCloseable {
    val capacityBytes: Long
    /** Extends the allocation to at least [upToBytes] (fallocate, else sparse); returns the allocated size. */
    @Throws(IOException::class) fun allocate(upToBytes: Long): Long
    @Throws(IOException::class) fun pwrite(src: ByteBuffer, filePosition: Long): Int
    @Throws(IOException::class) fun pread(dst: ByteBuffer, filePosition: Long): Int
    /** True once the file was unlinked under us (`st_nlink == 0`, A-S7). */
    fun isUnlinked(): Boolean
    fun availableBytes(): Long
    @Throws(IOException::class) fun writeHeader(generation: Long, capacity: Long)
    /** Closes the fd first, then deletes (space is freed on close). */
    fun delete()
}

/**
 * The time-shift ring (§3). Logical offsets only grow; bytes in [tail, head) are readable (§3.2, PAUSE_ONLY §4.5).
 * One writer thread per generation calls claim/commit/startSegment/stopRecording; any thread may pin, open readers or
 * read the index. commit() never blocks on disk; claim() may wait (FULL while playing, pool exhausted).
 * Every time is MonoClock ms.
 */
interface TsRing : AutoCloseable {
    val capacityBytes: Long
    val tail: Long
    val head: Long
    val generation: Long
    val saving: Boolean
    val stop: RecordStop?
    val mode: BufferMode
    val index: RingIndexView

    fun claim(minBytes: Int, timeoutMs: Long, paused: Boolean, slot: RingSlot): ClaimResult
    /** Drops the commit when [slot].generation != [generation] (A-S1). */
    fun commit(slot: RingSlot, length: Int, arrivalMonoMs: Long)
    fun startSegment(reason: SegmentReason, gapMs: Long)
    fun stopRecording(reason: RecordStop)
    fun setPin(owner: Int, offset: Long)
    fun clearPin(owner: Int)
    fun setMode(mode: BufferMode)
    fun openReader(fromOffset: Long, generation: Long): RingReader
    /** IO thread only, after the previous generation's pump exited (bounded join). New generation, tail = head = 0. */
    fun reset(): Long
}

/** Constructor shape WP-B calls (commit 0 stubs it). */
fun interface TsRingFactory {
    fun create(storage: RingStorage, clock: MonoClock, ramBlocks: Int, indexEntries: Int): TsRing
}

interface RingReader : AutoCloseable {
    val cursor: Long
    val segment: Int
    val generation: Long
    val atHead: Boolean
    /** n > 0 whole packets; blocks at head; throws the Ring* exceptions; never returns 0 or −1. */
    @Throws(IOException::class) fun read(dst: ByteArray, off: Int, len: Int): Int
    /** Any thread: makes a blocked read throw InterruptedIOException. */
    fun cancel()
}

open class RingException(message: String, cause: Throwable? = null) : IOException(message, cause)
class RingBoundaryException(val nextRapOffset: Long, val reason: SegmentReason, val gapMs: Long, val gapStartMonoMs: Long) : RingException("ring segment boundary")
class RingEndException(val stop: RecordStop, cause: IOException? = null) : RingException("ring ended: $stop", cause)
class RingPositionLostException(val requested: Long, val tail: Long) : RingException("ring position lost")
class RingIoException(cause: IOException) : RingException("ring read failed", cause)

/** Any thread, allocation-free, seqlock-consistent (§3.3). Slots index circular arrays; −1 = none. */
interface RingIndexView {
    val size: Int
    fun floorRap(monoMs: Long): Int
    fun ceilRap(monoMs: Long): Int
    fun newestRap(): Int
    fun oldestRap(): Int
    fun offsetAt(slot: Int): Long
    /** The RAP's PTS mapped to mono (the playhead origin, A-S4). */
    fun ptsMonoAt(slot: Int): Long
    fun flagsAt(slot: Int): Int
    fun segmentAt(slot: Int): Int
    fun newestPtsMono(): Long
    fun monoOfOffset(offset: Long): Long
    fun segmentReason(segment: Int): SegmentReason
    fun segmentGapMs(segment: Int): Long
}

/** Upstream as the writer sees it; :player implements it over LiveTsDataSource (with onPcrJump). */
interface TsUpstream {
    @Throws(IOException::class) fun open()
    @Throws(IOException::class) fun read(dst: ByteArray, off: Int, len: Int): Int
    fun close()
    fun terminate()
}

/**
 * Start/end pairs with **content** equality (reviewed 2026-10-19, A-C1): used in data classes that are StateFlow and
 * Compose state (ArchiveStatus, ScrubState), where a LongArray's identity equality would make every snapshot unequal.
 */
class TimeRanges(private val pairs: LongArray = LongArray(0)) {
    val count: Int get() = pairs.size / 2
    fun startAt(i: Int): Long = pairs[2 * i]
    fun endAt(i: Int): Long = pairs[2 * i + 1]
    override fun equals(other: Any?): Boolean = other is TimeRanges && pairs.contentEquals(other.pairs)
    override fun hashCode(): Int = pairs.contentHashCode()
    companion object { val EMPTY = TimeRanges() }
}

/**
 * Called on the **pump thread** (reviewed 2026-10-19, A-C1). The controller's implementation must only post to main;
 * it never blocks and never touches the player from here. Before onFirstBytes, onFailure is the only failure route (A-C2).
 */
interface RingWriterListener {
    fun onFirstBytes()
    fun onFailure(e: IOException)
    fun onStopped(reason: RecordStop)
    fun onSegment(reason: SegmentReason, gapMs: Long)
}

/** One long-lived pump thread per session (A-S1). Commands are posted; restart terminates the current upstream first. */
interface RingWriter : AutoCloseable {
    fun start(upstream: TsUpstream, listener: RingWriterListener)
    fun restart(upstream: TsUpstream, reason: SegmentReason)
    fun stop(reason: RecordStop)
    fun onPause(nowMonoMs: Long, holdLimitMs: Long?)
    fun onResume()
    /** Joins the pump thread for at most [timeoutMs] (IO only). */
    fun awaitExit(timeoutMs: Long): Boolean
}

fun interface RingWriterFactory {
    fun create(ring: TsRing, indexer: TsIndexer, clock: MonoClock, tag: String): RingWriter
}

interface TsIndexer {
    fun scan(buf: ByteArray, off: Int, len: Int, logicalOffset: Long, arrivalMonoMs: Long, sink: IndexSink)
    fun onSplice()
    fun reset()
}

interface IndexSink {
    fun onRap(offset: Long, pts90k: Long, pcr90k: Long, flags: Int)
    fun onAnchor(offset: Long, pcr90k: Long, flags: Int)
    fun onBoundary(offset: Long, reason: SegmentReason)
    fun onPsi(packets: ByteArray, count: Int)
}

/** Pure sizing and policy (§8). */
interface TimeshiftPolicy {
    fun lengthMs(setting: app.thebox.core.settings.TimeshiftLength, totalStorageBytes: Long): Long?
    fun capacityBytes(lengthMs: Long, bytesPerSec: Long, availableBytes: Long, totalBytes: Long, currentFileBytes: Long): Long
    fun holdLimitMs(setting: app.thebox.core.settings.TimeshiftHold): Long?
    fun dailyLimitBytes(setting: app.thebox.core.settings.StorageProtection, totalStorageBytes: Long): Long?
    fun mode(setting: app.thebox.core.settings.TimeshiftLength, writtenTodayBytes: Long, dailyLimitBytes: Long?, needsPersist: Boolean): BufferMode
    /** "Can stay paused N more min" (§11.5). */
    fun pauseRemainingMs(holdRemainingMs: Long?, freeCapacityBytes: Long, bytesPerSec: Long): Long
}
```

### 16.2 `player/.../api/PlaybackApi.kt` (additive, with defaults)

```kotlin
/** (Time-shift) The local buffer of the current live cycle. Wall times converted once per snapshot from mono. */
data class LocalBufferState(
    val identity: ChannelIdentity?,
    val available: Boolean,
    val off: app.thebox.core.playback.timeshift.BufferOff? = null,
    val fromMonoMs: Long = 0,           // oldest RAP's PTS
    val liveMonoMs: Long = 0,           // newest PTS
    val atMonoMs: Long = 0,
    val wallMinusMonoMs: Long = 0,      // snapshot conversion (A-S9)
    val saving: Boolean = false,
    val stop: app.thebox.core.playback.timeshift.RecordStop? = null,
    val stoppedAtMonoMs: Long? = null,
    val notSavedMono: app.thebox.core.playback.timeshift.TimeRanges = app.thebox.core.playback.timeshift.TimeRanges.EMPTY,
    val mode: app.thebox.core.playback.timeshift.BufferMode = app.thebox.core.playback.timeshift.BufferMode.PERSIST,
    val windowMs: Long = 0,
    val pauseRemainingMs: Long? = null,
    val usedBytes: Long = 0,
    val capacityBytes: Long = 0,
    val limitedToday: Boolean = false,
    val hls: Boolean = false,
)

sealed interface LocalSeekResult {
    data class Started(val landedMonoMs: Long) : LocalSeekResult
    data class Clamped(val landedMonoMs: Long) : LocalSeekResult
    data object Live : LocalSeekResult
    data object Unavailable : LocalSeekResult
}

interface PlaybackController {
    // ... existing members ...
    val localBuffer: StateFlow<LocalBufferState?> get() = NO_LOCAL_BUFFER
    /** [direction] < 0 floor, > 0 ceil, 0 floor (A-S4). Main thread. */
    fun seekLocal(targetMonoMs: Long, direction: Int): LocalSeekResult = LocalSeekResult.Unavailable
    fun jumpToLiveLocal(): Boolean = false
}

// Declared next to NO_SERVERS (reviewed 2026-10-19, A-C1):
internal val NO_LOCAL_BUFFER: StateFlow<LocalBufferState?> = kotlinx.coroutines.flow.MutableStateFlow(null)

// PositionSnapshot gains, last and defaulted:  val localMonoMs: Long? = null
// PlaybackNotice gains: data class LocalGap(val gapStartMonoMs: Long, val gapMs: Long, val reason: SegmentReason),
//   data class GapSkipped(val gapMs: Long, val stream: Boolean), data class SavingStopped(val stop: RecordStop),
//   data object LimitedToday, data object JumpedToLiveRestart, data object LeftAppCleared
// StreamStats gains (A-S9): val rewindSavedMs: Long = 0, val rewindWindowMs: Long = 0, val rewindBytes: Long = 0, val rewindSaving: Boolean = false
```

### 16.3 `core` additions

```kotlin
// core/.../playback/PlaybackContracts.kt, FailureSignal (additive)
data class RingBoundary(val nextRapOffset: Long, val gapMs: Long) : FailureSignal   // handled before the ladder

// core/.../catchup/archive/ArchiveContracts.kt (additive)
enum class TimelineSource { PROVIDER, LOCAL }
// ArchiveStatus gains: val source: TimelineSource = TimelineSource.PROVIDER, val bufferFromMs: Long? = null,
//   val notSaved: TimeRanges = TimeRanges.EMPTY, val pauseRemainingMs: Long? = null, val savingStop: RecordStop? = null   (A-C1)

// core/.../catchup/archive/ArchiveVisibility.kt: `!tuned.isArchive` → `!tuned.isArchive && status.source != TimelineSource.LOCAL`

// core/.../catchup/archive/TimelineRouter.kt
data class LocalWindow(val fromMs: Long, val liveEdgeMs: Long, val gaps: TimeRanges)
sealed interface TimelineTarget {
    data class Local(val programmeMs: Long, val direction: Int) : TimelineTarget
    data class Provider(val backMs: Long) : TimelineTarget
    data class Clamp(val programmeMs: Long, val reason: ClampReason) : TimelineTarget
    data object Live : TimelineTarget
}
enum class ClampReason { START, NOTHING_YET, LIMITED_TODAY }
interface TimelineRouter {
    fun route(targetMs: Long, playheadMs: Long, local: LocalWindow?, providerFromMs: Long?, liveEdgeMs: Long, sinceTuneMs: Long, limitedToday: Boolean): TimelineTarget
    /** (A-C5) Provider only when [timeShiftActive] and ([stop] != null or [gapMs] ≥ GAP_PROVIDER_MIN_MS) on an archive channel. */
    fun onGap(gapStartMs: Long, gapMs: Long, stop: RecordStop?, timeShiftActive: Boolean, hasArchive: Boolean, liveEdgeMs: Long): TimelineTarget
}

// core/.../full/PlayerContracts.kt (additive)
data class LocalBufferInfo(
    val available: Boolean, val fromMs: Long, val atMs: Long, val saving: Boolean, val notSaved: TimeRanges,   // P frame, from LocalTimelineBackend (A-C1)
    val pauseRemainingMs: Long?, val stop: app.thebox.core.playback.timeshift.RecordStop?,
    val off: app.thebox.core.playback.timeshift.BufferOff?, val hls: Boolean, val limitedToday: Boolean,
)
// PlayerEvent gains: data class Buffer(val info: LocalBufferInfo?) : PlayerEvent
// PlayerToast gains (append-only): PAUSE_NO_REWIND_HLS, PAUSE_NO_REWIND_OFF, PAUSE_NO_REWIND_SPACE, BACK_TO_LIVE_UNBUFFERED,
//   REWIND_NOTHING_YET, REWIND_START, GAP_SKIPPED_PAUSED, GAP_SKIPPED_STREAM, REWIND_STOPPED_STORAGE, REWIND_STOPPED_SLOW,
//   REWIND_LIMITED_TODAY, LEFT_APP_CLEARED, ZAP_CONFIRM, ZAP_CONFIRM_NUMBER, SAVING_STOPPED_CONNECTION, JUMPED_TO_LIVE_RESTART, PAUSE_TIP
// ScrubState gains: scope: ScrubScope = PROGRAMME, windowFromMs: Long? = null, savedFromMs: Long? = null,
//   notSaved: TimeRanges = TimeRanges.EMPTY, header: PausedHeader? = null, hint: Boolean = false
enum class ScrubScope { PROGRAMME, REWIND_WINDOW }
enum class PausedHeader { SAVING, FULL, HOLD_LIMIT, SLOT_LOST, CATCHUP_COVERS }
// PlayerStateMachine gains: fun zapNeedsConfirm(nowMs: Long): Boolean = false   (lead ruling 6)
// ArchivePlayback gains (A-C1): val localBuffer: StateFlow<LocalBufferInfo?>  (P frame; the VM's only source for PlayerEvent.Buffer)
// QuickBarModel.items is not a contract: only WP-C calls it, and WP-C owns QuickBarModel.kt (A-C1).
```

### 16.4 `player/.../internal/Seams.kt` and the classifier

```kotlin
internal class RingOpen(val ring: TsRing, val generation: Long, val startOffset: Long, val telemetry: StreamTelemetry)

internal interface TransportFactory {
    // ... existing ...
    /** Registers the reader as a Terminable in ctx.tuneId's scope so endSession(readerTuneId) cancels it (A-S9). */
    fun ring(ctx: StreamSessionContext, open: RingOpen): Pair<DataSource.Factory, StreamTelemetry>
}
// MediaSourceBuilderApi.build(…, archive: ArchiveRequest?, ring: RingOpen? = null)

/** (reviewed 2026-10-19, A-C1) Split: IO preparation in parallel with the resolve, then a main-thread start. */
internal interface TimeshiftSessionFactory {
    /** IO only (sweep wait, StatFs, mkdir, first extent); needs no resolved stream (A-N5). */
    suspend fun prepare(prefs: PlaybackPrefs, maxConnections: Int): TimeshiftPrepared
    /** Main thread, after the resolve: builds the writer from [writerCtx] (resolved stream, writerTuneId) and starts the pump. */
    fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession
}
internal sealed interface TimeshiftPrepared { class Granted(val grant: TimeshiftGrant) : TimeshiftPrepared; class Off(val reason: BufferOff) : TimeshiftPrepared }
/** Storage reserved for one cycle; reusable by a pre-first-byte recreate (A-C2). */
internal class TimeshiftGrant(val ring: TsRing, val capacityBytes: Long, val mode: BufferMode, val holdLimitMs: Long?)
internal interface TimeshiftSession : AutoCloseable {
    val writerTuneId: Long
    val ring: TsRing
    val writerTelemetry: StreamTelemetry
    var stashed: Pair<PlaybackError, FailureSignal>?
    fun onPause(nowMonoMs: Long); fun onResume()
    fun restartWriter(resolved: ResolvedStream, reason: SegmentReason)
    fun readerTelemetry(reader: () -> RingReader?): StreamTelemetry      // §5.2
    fun snapshot(nowMonoMs: Long, wallMinusMonoMs: Long): LocalBufferState
}

// FailureClassifier (commit 0): Facts gains `val writerTuneId: Long? = null`; new
//   fun classifyLive(e: LiveStreamException, facts: Facts): FailureSignal?
// Ring exceptions are matched before LiveStreamException. BoxLoadErrorHandlingPolicy: RingException → fatal.
// LiveTsDataSource (WP-B, additive): constructor/factory parameters `onPcrJump: ((Long) -> Unit)? = null` (A-N1) and
//   `reconnectAllowed: (() -> Boolean)? = null` (A-C4); both null for every caller but the writer.
```

---

## 17. Tests

### 17.1 JVM (WP-A, `:core`)

| Test | What it proves |
|---|---|
| `TsRingPropertyTest` (seeded, 10,000 runs) | random appends, pins, readers, resets: invariants of §3.2; every byte read equals the byte written |
| `RingStressTest` (reviewed 2026-10-19, A-S11) | **multi-threaded**: pump + flusher + 2 readers + main-thread index lookups with random delays for 60 s; no torn reads, invariants hold |
| `TsRingWrapTest` | wrap at `capacity` and ±188; no straddling; entries before `tail` dropped; `tail` on the oldest RAP (A-N8) |
| `RingPoolTest` (A-B2) | a parked reader plus 60 s of writes, and a reader 10 min behind plus 60 s of writes: the pool never waits; a recycled block during a copy → seq mismatch → file re-read |
| `TsRingFullTest` (A-S3) | FULL decided in `claim`; while playing, a 15 MB burst with a 1× reader gives **no** gap; while paused, FULL stops at once; `PIN_STALL_MS` gap |
| `RingFenceTest` (A-S1) | old pump blocked in a fake `read` across `reset()`: its late commit is dropped; an old reader gets `RingEndException(SESSION_END)` |
| `RingIndexTest` | `floorRap`/`ceilRap` over wrapped circular arrays; spacing; memory 28 B/entry; seqlock retry under concurrent eviction |
| `PcrUnwrapTest` | PTS/PCR across 2³³: mono continuous; BEHIND correct across the wrap |
| `PtsOriginTest` (A-S4) | fixture with a 700 ms PTS−PCR lead: landed position = target within GOP + 50 ms; BEHIND right after Back to live ≤ `liveLandingMs` + 1,000 (reviewed 2026-10-19, A-C3) |
| `DirectionStepTest` (A-S4) | 10 s-GOP fixture: → +10 s always advances; ← floors |
| `TsIndexerTest` | RAI; NAL fallback (H.264/H.265/MPEG-2); PSI capture incl. 2 PMTs; no PCR → arrival clock |
| `InlineJumpTest` (A-S5) | a 5 s connect burst → **no** segment; a PTS step before the PCR step → boundary at the PTS; `discontinuity_indicator` |
| `PsiChangeTest` (A-S5) | PMT version change → `PSI_CHANGE`; a seek after it injects the new PSI |
| `WallStepTest` (A-S9) | a wall-clock step of ±1 h mid-session: index, hold timer and pins unaffected; snapshot wall shifts as a whole |
| `RingReaderTest` | blocks at head and wakes ≤ 5 ms after a commit; cancel/interrupt → `InterruptedIOException`; never 0/−1; boundary/end/lost/io exceptions; PSI first |
| `RingWriterTest` (fake `TsUpstream`) | `onPcrJump` path; failures → `onFailure` once; hold timer on mono; restart terminates the old upstream before opening the next; `awaitExit` |
| `FlushTest` (fake `RingStorage`) | latency, ENOSPC, EIO, unlink (`isUnlinked`) → the matching `RecordStop`; PAUSE_ONLY writes nothing at live and persists from the oldest pool block on pause (A-S10) |
| `TimeshiftPolicyTest` | §8.2 incl. the Fire TV example; Auto lengths; hold Auto = until full; 12 GB cap only on ≤ 16 GB; mode switch never while behind; `pauseRemainingMs` |
| `RingAllocationTest` | 0 bytes per block steady state; copy count per §15 |

### 17.2 `:player` (WP-B; real ExoPlayer under Robolectric where M2 §5.4 allows)

| Test | What it proves |
|---|---|
| `RingDataSourceTest` | §5.1 against a real `TsRing` in a temp dir; every `IOException` is a `RingException` (fatal) |
| `ReaderOverRingExoTest` | prepare at a mid-stream RAP reaches READY; position 0 = the RAP's PTS; boundary hand-off |
| `ReaderCancelTest` (A-S11) | `Loader.cancelLoading` and `player.release()` on a reader blocked at the head: the loader thread exits ≤ 300 ms; `TuneSession.end()` cancels through the `Terminable` |
| `ControllerTimeshiftTest`, **real** `FailureClassifier` (A-S11) | a writer 403 before the first byte gives today's card, not "stale", decided **once** (B1, A-C2); a first-open RetrySame keeps the cycle buffered (A-C2); a 3rd EOF reconnect within 60 s while paused → `SLOT_LOST` (A-C4). Writer `RECONNECT` at the head → Buffering(RECONNECTING); behind live → no state change and no toast (B1, B3). Writer RetrySame with a 10 s delay at the head → no `NO_BYTES` stall (B3). Writer `Fail` behind → the saved part plays out, then the card, ladder decided once (B1). Cycle-scoped decision behind → deferred. `SLOT_LOST` while paused → no reconnect (S2). Every `launchSession` call site keeps the position while behind (S6). Pause/resume rows of §7.2; no `livePauseJob`; no `LiveResumed`; `keepScreenOn` rules of §11.5 |
| `ZapMilestoneTest` | `leaseMs`/`firstByteMs` come from writer events |
| `FailureClassifierTest` additions | `writerTuneId`; ring exceptions first |
| `BoxLoadErrorHandlingPolicyTest` | `RingException` fatal |
| `TimeshiftStorageTest` | `StatFs`; fallocate fallback; `ring-<pid>.bin` sweep spares the current pid; `prepare()` awaits the sweep; close then delete |

### 17.3 WP-C (JVM, Robolectric, Compose UI)

| Test | What it proves |
|---|---|
| `TimelineRouterTest` | every row of §9.1, including gaps → Provider only during an active time-shift with a saving-stop or ≥ 30 s gap (A-C5), and frames with `correctionMs = −3,600,000`, `skewMs = +90,000` (A-B4) |
| `LocalTimelineBackendTest` (A-B4, A-S11) | P-frame mapping and its inverse with correction and skew; status fields; gap hand-off |
| `QuickBarModelTest` | Pause first on every live channel incl. HLS/off/no space; Back to live first while behind; Start over with buffer coverage |
| `player_keys.csv` + five-key reachability | `bufferCapable` axis; zap-confirm rows; the number-entry confirm through `zapNeedsConfirm` |
| `TimeRangesTest` (A-C1) | content equality; an `ArchiveStatus` snapshot with equal ranges compares equal |
| `PillFormatTest` | "PAUSED · 4:12 behind", "4:12 BEHIND", "1:04:12", no leading zero |
| `ScrubWindowTest` | REWIND_WINDOW scope without catch-up; PROGRAMME scope and no band with catch-up; "Not saved" label threshold 120 px |
| `PausedHeaderTest` | the five header lines; the OK / OK-or-▶ variant |
| `ScrubIdleTest` | 5 s idle after a resume; hint row first three times only |
| `PlayerViewModelTest` | `Buffer` event; notices → the §13 toasts; LEFT_APP_CLEARED once; REWIND_LIMITED_TODAY once a day |
| `PlaybackRowsTest` + existing `SimpleRowCountTest` | PLAYBACK simple = 5, total = 35; On launch ADVANCED |
| Compose screenshot tests | the dim card's drift positions stay inside the safe area; the dim layer is not an ancestor of the `SurfaceView` |

### 17.4 Device suite (WP-D; API 30 TV emulator + fake panel; then Fire TV Stick 4K and Onn in M6)

| # | Case | Pass |
|---|---|---|
| I-TS-1 | Pause 60 s, resume | same tuneId; no new connection; BEHIND 60 s ± 2 s; continues from the pause frame (burnt-in counter) |
| I-TS-2 | Watch 3 min, RW to −2:00 | BEHIND −2:00 ± one GOP; seek latency logged |
| I-TS-3 | Back to live from −2:00 | LIVE; behind ≤ `liveLandingMs` + 1 s (A-C3); no new connection |
| I-TS-4 | Pause on `max_connections = 1`, hold 10 min (debug 30 s) | one connection for 30 s, then 0; `/admin/occupy` refused before, accepted after |
| I-TS-5 | Pause until full (debug 1-min ring), no catch-up | saving stops; connection released; header line FULL; resume plays the minute, then GAP_SKIPPED_PAUSED |
| I-TS-6 | `close_every: 15` while paused 2 min | no segment; resume plays across the splices |
| I-TS-7 | `reconnect_mode: reset` while behind | boundary, ≤ 1 s hold, no card, BEHIND correct |
| I-TS-8 | `inline_jump_after` | boundary hand-off; no stall-detector re-prepare |
| I-TS-9 | Zap while behind | buffer discarded; RW on the new channel → REWIND_NOTHING_YET |
| I-TS-10 | Archive channel: pause 2 min; RW 10 min with a 5-min buffer | pause makes no timeshift request; RW makes one provider request and ends the ring |
| I-TS-11 | HLS without catch-up | Pause item present and first; ↑ OK freezes with PAUSE_NO_REWIND_HLS; M2 release after 10 s |
| I-TS-12 | Low space (debug override) | PAUSE_NO_REWIND_SPACE; M2 behaviour |
| I-TS-13 | Home while paused, return | stopped; file deleted; live; LEFT_APP_CLEARED once |
| I-TS-14 | Kill the process, relaunch | stale file swept; an auto-tune's new file survives the sweep. **Manual** for the kill/relaunch half (M-TS-1 below); the suite checks the in-process half (one file, named for this pid) |
| I-TS-15 | 4 min behind with a 1-min ring | no stall; `bufferFrom` advances; no pin violation |
| I-TS-16 | `pts_offset` near 2³³ | **BEHIND correct** across the wrap; RW across it lands correctly (A-S11) |
| I-TS-17 | `strip_rai` | seeks reach first frame ≤ 1 s |
| I-TS-18 | `psi_every_s: 5` and a PMT version change | seeks reach first frame ≤ 1 s; no dead-track after the change |
| I-TS-19 | Injected `DECODER_STUCK` while behind | re-prepare at the pin; BEHIND unchanged |
| I-TS-20 | Settings Off/on; Pause only; daily cap (debug 50 MB) | file deleted on Off; Pause only writes ≈ 0 at live; REWIND_LIMITED_TODAY when ← hits the start |
| I-TS-21 | Soak 2 h behind with a 15-min ring | heap flat; file = capacity; CPU, PSI and LMK logged. Opt-in (`soak=1`); LMK is **manual** (M-TS-4) |
| I-TS-22 | 50 zaps, buffer on vs off | zap P50/P90 ≤ 5 % regression (hardware gate) |
| I-TS-23 (U-B1) | Emulator screen timeout 1 min; pause 8 min; resume | screen stays on; dim card after 2 min; first key only wakes; same tuneId; BEHIND ≈ 8 min. The Fire TV screensaver and Google TV ambient mode are **manual** (M-TS-2) |
| I-TS-24 (U-B2) | Archive channel, ring 2 min, pause 4 min, resume | continuous content across the gap through one provider request; no toast |
| I-TS-25 (A-S2) | `max_connections = 1`, `/admin/occupy?mode=kick_oldest` while paused | `SLOT_LOST` header after at most `PAUSED_RECONNECTS_MAX` (2) reconnects (A-C4); the other device keeps its slot; the saved part plays |
| I-TS-26 (A-S4) | Back to live, then 5 min of play | rebuffer count ≤ a fresh tune's |
| I-TS-27 (A-B2) | Rewind 10 min, then 60 s of normal play | no `DISK_TOO_SLOW`; no pool wait |
| I-TS-28 (A-S7) | Delete the ring file while saving | `STORAGE_EVICTED`; REWIND_STOPPED_STORAGE; live continues; space freed |
| I-TS-29 (lead ruling 6) | Paused 2 min, then CH+ / Last / digits | first press → toast and no zap; second within 3 s zaps; channel list and guide zap at once |
| I-TS-30 (U-B3) | Onn key set: ↑ OK on a TS, an HLS and an Off channel | always pauses (or freezes with the reason) |
| regressions | M4 `TimeShiftTest` and the M2–M5 classes | M4 archive time-shift cases run with the buffer Off (they test the provider path) |

**Manual steps** (WP-D, 2026-10-19). What the emulator cannot automate: process death, the vendor screensaver, flash wear and the low-memory killer.

| # | Tag | Case | Steps | Pass |
|---|---|---|---|---|
| M-TS-1 (I-TS-14) | manual | Kill the process, relaunch | 1. Tune a TS channel, wait 30 s. 2. `adb shell run-as app.thebox.debug ls -l cache/timeshift` shows one `ring-<pid>.bin`. 3. Plant a stale file: `adb shell run-as app.thebox.debug sh -c 'dd if=/dev/zero of=cache/timeshift/ring-1.bin bs=1m count=8'`. 4. `adb shell am force-stop app.thebox.debug`, then start the app. If "On launch" is "Last channel", it auto-tunes. 5. Within 5 s, list `cache/timeshift` again. | `ring-1.bin` and the old pid's file are gone. Only the new process's `ring-<newpid>.bin` exists (or nothing, when no auto-tune). An auto-tune's file is never deleted by the sweep (`prepare()` awaits it) |
| M-TS-2 (I-TS-23) | manual, hardware | Screensaver while paused | On the Fire TV Stick 4K (screensaver "After 5 minutes") and the Onn (ambient mode): pause live TS, leave the remote 10 min, then press ▶ or OK. | The screensaver or ambient mode never starts while paused and saving. The dim card shows after 2 min. The first key only wakes the picture, and the next resumes in place, ≈ 10 min behind. Then the same with "Keep recording while paused" = 10 min: after saving stops the screen may sleep, and on return LEFT_APP_CLEARED shows once |
| M-TS-3 (§8.4, A-N4) | manual, hardware | Flash wear | 1. Read eMMC wear before the soak: `adb shell am start -a android.intent.action.VIEW -d 'thebox-debug://guide?emmc=1&open=0'`, then `adb logcat -d -s GuideDebug \| grep emmc:`. 2. Run I-TS-21 (2 h, `soak=1`), or a day of normal use with rewind Auto. 3. Read again. 4. Compare the ring's daily counter (`run-as … cat no_backup/timeshift-writes`) with `/proc/<pid>/io` `write_bytes`. | Record `life_time` (type A/B), `pre_eol_info`, bytes written and write amplification in the M6 hardware notes. If they are readable, they replace §8.4's assumptions. An unreadable path is recorded with its reason |
| M-TS-4 (I-TS-21) | manual, hardware | LMK and page cache during the soak | During I-TS-21 on the Fire TV (1.5 GB): `adb logcat -b events \| grep -E 'am_kill\|am_low_memory'` and `adb shell cat /proc/pressure/memory` every 10 min. | No kill of the app. PSI `some avg60` is logged. Other apps killed during the soak are listed |
| M-TS-5 (§22) | manual, hardware | `Os.posix_fallocate`, direct buffers on Fire OS 6 | First buffered tune on the Fire TV Stick 4K (API 25) and the Onn. Check `adb logcat \| grep RingStorage` for the "fallocate unavailable: sparse ring file" line, and that I-TS-1 passes. | Either fallocate works or the sparse fallback is logged. No crash in `allocateDirect().array()` |

### 17.5 Fake-panel additions (WP-D, `tools/fakepanel/fakepanel.py`, `test_fakepanel.py`, README)

| Addition | Purpose |
|---|---|
| `strip_rai` | clear the adaptation-field RAI |
| `pts_offset` | start PTS/PCR at a given 90 kHz value |
| `inline_jump_after: [s, ms]` | a PTS/PCR step inside one connection, PTS before PCR in byte order |
| `psi_every_s` | sparse PAT/PMT repetition |
| `pmt_change_after: s` | bump the PMT version and change the audio PID |
| `pad_bps` | null-packet padding |
| `/admin/occupy?user=&sid=&mode=hold\|kick_oldest` | another device holding or taking the slot |
| live connection log in `/admin/stats` | open/close times per connection |

Existing faults reused: `close_every`, `reconnect_mode`, `stall`, `stall_after`, `slow`, `linger`, `max_connections`.

---

## 18. Work packages (disjoint files)

The rules are M4 §1.1's:
- commit 0 first, and green;
- no WP edits another WP's file, a contract or a build file;
- contract problems go to the lead.

**Commit 0 (lead).** §16 verbatim:
- new files: `core/.../playback/timeshift/TimeshiftContracts.kt`, `TimeshiftConstants.kt` (Appendix A), `core/.../catchup/archive/TimelineRouter.kt`;
- additive edits to `core/.../playback/PlaybackContracts.kt`, `core/.../catchup/archive/ArchiveContracts.kt`, `ArchiveVisibility.kt` (the LOCAL line), `core/.../full/PlayerContracts.kt`, `core/.../settings/SettingsStore.kt`, `core/.../settings/tree/PlaybackRows.kt` (new ids and specs; `ON_LAUNCH` → ADVANCED), `player/.../api/PlaybackApi.kt`, `player/.../internal/Seams.kt`, `player/.../controller/FailureClassifier.kt` (`Facts.writerTuneId` and the `classifyLive` signature only);
- stubs for every class below, bound in Hilt;
- sequenced edits to the exhaustive `when`s over `PlayerToast` / `PlaybackNotice` / `FailureSignal`: `ui/.../full/PlayerText.kt` and the controller (reviewed 2026-10-19, A-§12).

| WP | Model | Files (new unless "edit") | Tests |
|---|---|---|---|
| **WP-A** ring, index, writer, reader (JVM) | Opus | `core/.../playback/timeshift/TsRingImpl.kt`, `RingIndexImpl.kt`, `RingReaderImpl.kt`, `TsIndexerImpl.kt`, `RingWriterImpl.kt`, `BlockPool.kt`, `RingFlusher.kt`, `FileRingStorage.kt` (a pure-JVM `RingStorage` over `FileChannel`, for tests and as the fallback), `TimeshiftPolicyImpl.kt` | §17.1 |
| **WP-B** controller, ladder, seek | Opus | `player/.../timeshift/TimeshiftSessionImpl.kt`, `TimeshiftSessionFactoryImpl.kt`, `LiveTsUpstream.kt`, `RingDataSource.kt`, `RingReaderTelemetry.kt`, `AndroidRingStorage.kt` (`Os.posix_fallocate`/`pwrite`/`pread`/`fstat`, StatFs), `TimeshiftStorageImpl.kt` (sweep, daily counter), `TimeshiftDebugOverrides.kt`, `TimeshiftModule.kt`. Edits: `controller/PlaybackControllerImpl.kt`, `controller/TuneSession.kt`, `controller/FailureClassifier.kt` (bodies), `controller/LifecycleCoordinator.kt` (LeftAppCleared), `engine/MediaSourceBuilder.kt`, `engine/StatsCollector.kt`, `datasource/BoxLoadErrorHandlingPolicy.kt`, `datasource/TransportFactoryImpl.kt` (`ring()`, writer tune ids), `datasource/LiveTsDataSource.kt` (`onPcrJump` only); `app/.../TheBoxApp` start-up sweep hook | §17.2. **Lead review checkpoint of the controller work before WP-C integrates against `localBuffer`/`seekLocal`** (A-§12) |
| **WP-C** state machine, `ArchivePlayback` LOCAL, UI, settings, strings | Opus (machine, archive, UI); Sonnet may take strings and the settings page under this spec | `core/.../catchup/archive/TimelineRouterImpl.kt`; edits `core/.../full/state/PlayerStateMachineImpl.kt`, `core/.../full/state/QuickBarModel.kt` (A-C1), `ScrubModel.kt`, `PillRules.kt` (none expected), `core/.../guide/state/GuideStateMachineImpl.kt` + `NumberEntryImpl.kt` (zap confirm), `core/.../settings/state/WpaPages.kt`; new `app/.../playback/LocalTimelineBackend.kt`, edit `app/.../playback/ArchivePlaybackImpl.kt`; edits `ui/.../full/vm/PlayerViewModel.kt`, `PlayerEffectsImpl.kt`, `ui/.../full/ScrubBar.kt`, `ScrubBarRenderer.kt`, `QuickBar.kt`, `PlayerParts.kt` (pill text), `PlayerText.kt` (bodies), `StatsOverlay.kt`, new `ui/.../full/PausedCard.kt` (dim layer and card), `ui/src/main/res/values/strings_player.xml`, `strings_settings.xml`, `strings_m5_enums.xml` (labels of `TimeshiftLength`, `TimeshiftHold`, `StorageProtection`) | §17.3 |
| **WP-D** fake panel, device suite, docs | Opus for integration; Sonnet for the fake-panel media and README | `tools/fakepanel/fakepanel.py`, `test_fakepanel.py`, `README.md`; `app/src/androidTest/kotlin/app/thebox/player/TimeShiftBufferTest.kt` (+ helpers); `app/src/debug/.../GuideDebugReceiver.kt` (ring size, hold, cap, free space, eMMC `life_time` read, A-N4); docs: this file updated with the integration results; `00-master-plan.md` (the §2 v1.1 line, §3 "Live TS transport" → the DVR seam moves to the ring, **a new v1.1 follow-up item "HLS DVR-window pause/rewind" with the U-Q6 measurement**, lead ruling 4); `decisions/2026-10-15-player-keys-and-quick-bar.md` amendment (quick-bar order); M2 §3.1.2, M4 §0.5, M5 §3.3.13 amendment notes; `review/product-review.md` item 25 status | §17.4, §17.5 |

**Working tree** (lead ruling on process, 2026-10-19; reviewed 2026-10-19, A-C confirmation §3). No worktrees for this feature: every WP works on the main working tree, and the disjoint file lists above are what keep them apart. The tree has uncommitted work (about 216 entries, several in files commit 0 and the WPs edit); the lead commits it before commit 0 so every WP starts from the same base. Agents do not commit; the lead commits.

**Integration order.**
1. WP-A and WP-D (fake panel) start at once.
2. WP-B starts against WP-A's stubs and a fake `TsRing`, then passes the controller review checkpoint.
3. WP-C works against fakes of `localBuffer`/`seekLocal` and integrates after the checkpoint.
4. Lead integration on the emulator: I-TS-1…30 plus the regression classes, then the code-review pass and the release gates (plan §6, plus §15's hard zap gate).

---

## 19. Rejected, and why

| # | Alternative | Why not |
|---|---|---|
| R1 | Saving through the `TeeDataSource` sink on the loader thread | the loader parks when the player's buffer is full (facts §4); a sink exception fails the load (facts §3) |
| R2 | `CacheDataSource` + `SimpleCache` | position-keyed spans, upstream `Range`, LRU eviction, bypassed for unset lengths (facts §5) |
| R3 | Native seeking (`TsBinarySearchSeeker`, a custom growing `SeekMap`) | frozen length, timeline not dynamic, `seekTo` reloads from 0 (facts §1.1, §2); phase-2 spike only |
| R4 | RAM-only ring | 24–48 MB is 25–50 s at 1 MB/s on 1.5 GB devices |
| R5 | A second upstream connection for the recorder | breaks one-connection accounts; the plan wants one connection per watched channel |
| R6 | Read upstream directly at live, switch to the ring on pause | hands an open source between threads mid-stream; two code paths; loses the player's 20 s for rewinds |
| R7 | Keep the ring while the provider archive plays | a second connection or a frozen ring with a hole |
| R8 | Keep the saved part across Home/standby, or save in the background | no foreground service in v1 (plan §3 "Process", M2 B5); that is the v2 DVR (lead ruling 4) |
| R9 | One file per segment or per minute | file churn and fragmentation |
| R10 | Provider archive for every pause on catch-up channels (M4 §0.5 as is) | slower, archive lag, a hand-off on every resume; the provider is kept for gaps and older targets (lead ruling 4) |
| R11 | `fsync`, a persisted index | no durability needed |
| R12 | HLS segment caching | different keying; the DVR window is the follow-up |
| R13 | USB/SAF storage | v2 storage backends |
| R14 | Repairing the TS before the ring | repairs are extractor state that can change during a session |
| R15 | Media3 seek-back/forward commands | need a seekable item (facts §6) |
| R16 | A separate `LocalTimeshift` interface beside `ArchivePlayback` | duplicates every key path, pill and visibility rule |
| R17 | Headroom behind the pin (proposal) | unusable old media, not burst absorption (A-S3) |
| R18 | Block recycling gated on reader cursors (proposal) | exhausts the pool on every pause and rewind (A-B2) |
| R19 | Identity device-wall timeline (proposal) | wrong by skew + correction (A-B4) |
| R20 | Media3 deferred retry continuing at the reader's cursor (proposal) | re-anchors the `TimestampAdjuster` (A-S9) |
| R21 | A "Rewind 30 s" quick-bar item | ↑ OK ←←← OK is 6 presses with no extra width or decision (U-B3) |
| R22 | A per-previous-channel frozen ring for the two-channel flip | a second ring file and timeline holes for little gain (U-§7) |

---

## 20. Rulings (formerly "open questions for the owner")

| Q | Ruling (lead 2026-10-19) |
|---|---|
| Q1 Default and wear | Rewind on by default. Auto = 30 min on ≤ 16 GB storage, 1 h above. Internal daily cap of 12 GB on ≤ 16 GB storage, none above, until wear data exists (A-N4 measurement). Surfaced as the ADVANCED row "Protect built-in storage: Auto / Off". "Pause only" is a value of the length row |
| Q2 Connection while paused | Auto = until full on every account. A recorder that loses its slot stops and never fights another device; the saved part plays, with a toast or header line. The ADVANCED "Keep recording while paused" row lets a sharing household shorten it |
| Q3 Quick bar | "Pause" first on every live channel; OK pauses at once; "Back to live" first while behind (U-B3) |
| Q4 Catch-up channels | Local first, provider beyond, provider fills gaps (U-B2) |
| Q5 Leaving the app | Discard on Home/standby. The screen stays on while paused and saving (U-B1); LEFT_APP_CLEARED on return |
| Q6 HLS | Not buffered; Pause present with an honest freeze. The DVR window is a separate master-plan follow-up (WP-D records it) |
| Q7 Seek precision | Keyframe landing with direction-aware rounding and PTS-based positions (A-S4) |
| Zap while paused | Single-key zaps (CH±, digits, Last) after a pause of ≥ 2 min need a second press within 3 s; the channel list and guide zap at once |

---

## 21. Not applied

| Item | What it asked | Why not applied |
|---|---|---|
| U-S1 (part) | Release the connection after 60 s on one-connection accounts on catch-up channels | lead ruling 3: Auto = until full on every account |
| U-S1 (part) | Row label "Hold the connection while paused", values "Until rewind is full (Auto) · 10 min · 30 min · 1 hour" | lead ruling 3 keeps "Keep recording while paused: Auto / 10 min / 30 min / 1 h / Until full"; the UX description is used |
| U-S2 (part) | Cap 16 GB (≤ 16 GB storage) / 32 GB (≤ 64 GB); row values On/Off | lead ruling 2: 12 GB on ≤ 16 GB, none above; Auto/Off |
| A-Q1 | 6 GB cap until measured | lead ruling 2 (12 GB) |
| U-B4 (part) | Demote Picture shape | lead ruling 1: demote On launch |
| U-S8 (part) | Exclude number entry from the zap confirm | lead ruling 6 includes digits (and Last) |
| U-Q7 | Always land at or before the target, never after | lead ruling 4: direction-aware rounding (A-S4); backward steps still land at or before |
| U-S4 (part) | "Can stay paused as long as you like: catch-up covers the rest." | bounded by `PAUSE_KEEP_AWAKE_MAX_MS` (3 h) so the screen is not kept on forever; text says "up to 3 h" |
| U-N5 | 32 MiB RAM pool for instant replay in Pause only | a spike, not this design: +28 MiB heap on 2 GB devices is outside §15 |
| A-N9 | Commit `GuideDebugReceiver.kt` before WP-D | a lead repo action, not a design change (recorded in the sign-off) |

---

## 22. Unverified

| Claim | Check | Owner |
|---|---|---|
| The last frame stays and the codec re-inits quickly across `setMediaSource` + `prepare` (facts §6 "not found") | I-TS-2 on the emulator; Fire TV and Onn in M6 | WP-B, WP-D |
| `Os.posix_fallocate` on Fire OS 6/7 ext4 and the Onn (low risk, A-S7) | device run | WP-B |
| `allocateDirect` is backed by a non-movable `byte[]` with `array()` on API 25 (verified on the API 34 sources, A-S8) | Robolectric is not enough; device run on Fire OS 6 | WP-B |
| `Os.fstat(fd).st_nlink` reads 0 after installd's cache clearing | I-TS-28 | WP-D |
| `FLAG_KEEP_SCREEN_ON` holds off the Fire TV screensaver and Google TV ambient mode | I-TS-23 on hardware | WP-D |
| eMMC endurance, write amplification, static vs free-space wear levelling (§8.4) | eMMC `life_time` read (A-N4); vendor sheets | lead |
| `monoOffset` convergence and the `ptsPcrLead` spread on real panels | D10-style real-provider session | lead |
| Media3 HLS playlist refresh while paused, and the `BehindLiveWindowException` path (follow-up only) | separate spike | — |
| Writer CPU ≤ 2 %, seek P50 ≤ 600 ms, zap ≤ 5 % on hardware | M6 | WP-D |
| Page-cache churn and LMK on 1.5 GB devices during a 2 h soak (no `fadvise` in public `Os`) | I-TS-21 on the Fire TV | WP-D |

---

## Appendix A. Constants (`core/.../playback/timeshift/TimeshiftConstants.kt` unless noted)

| Constant | Value | Used in |
|---|---|---|
| `TS_PACKET` | 188 | §3.1 |
| `RING_HEADER_BYTES` | 4,096 | §3.1 |
| `RING_BLOCK_PACKETS` / `RING_BLOCK_BYTES` | 348 / 65,424 | §3.1, §4.1 |
| `RING_EXTENT_BLOCKS` | 1,025 (≈ 64 MiB) | §3.1 |
| `RING_RAM_BLOCKS` / `_LOW_RAM` | 64 (4.0 MiB) / 32 (2.0 MiB) | §4.1 |
| `READER_STAGING_BYTES` | 65,536 | §5.1 |
| `PIN_STALL_MS` | 10,000 | §3.2 |
| `PUMP_BLOCK_MAX_MS` | 2,000 | §4.1 |
| `PUMP_JOIN_MAX_MS` | 1,000 | §3.2 |
| `FLUSH_PARTIAL_MS` | 1,000 | §4.1 |
| `NLINK_CHECK_MS` | 1,000 | §4.4 |
| `FREE_SPACE_CHECK_MS` | 60,000 | §3.1 |
| `READER_WAIT_SLICE_MS` | 250 | §5.1 |
| `INDEX_MIN_SPACING_MS` / `INDEX_MAX_GAP_MS` | 400 / 2,000 | §3.3 |
| `INDEX_ENTRIES_PER_SEC` / `INDEX_SLACK_ENTRIES` | 2.5 / 256 | §3.3 |
| `INDEX_READ_RETRIES` | 8 | §3.3 |
| `RAI_FALLBACK_MS` | 10,000 | §3.4 |
| `PSI_MAX_PACKETS` | 8 | §3.4 |
| `PCR_INLINE_JUMP_MS` | 1,000 | §3.4 |
| `MAX_SEGMENTS` | 64 | §3.5 |
| `SEGMENT_STORM_COUNT` / `_WINDOW_MS` | 6 / 60,000 | §3.5 |
| `ASSUMED_BYTES_PER_SEC` | 1,000,000 | §8.2 |
| `RATE_PROBE_MS` | 20,000 | §8.2 |
| `CAPACITY_HEADROOM` | 1.15 | §8.2 |
| `MIN_RING_BYTES` / `MAX_RING_BYTES` | 64 MiB / 8 GiB | §8.2 |
| `FREE_SPACE_RESERVE_MIN` / `_FRACTION` | 1 GiB / 0.10 | §8.2 |
| `SMALL_STORAGE_BYTES` | 16 GB | §8.2, §8.4 |
| `AUTO_LENGTH_SMALL_MS` / `_LARGE_MS` | 1,800,000 / 3,600,000 | §8.2 |
| `DAILY_LIMIT_SMALL` | 12 GB (≤ 16 GB storage; none above) | §8.4 |
| `DAILY_COUNTER_FLUSH_BYTES` | 64 MiB | §8.4 |
| `LOCAL_MARGIN_MS` | 5,000 | §9.1 |
| `REWIND_NOTHING_YET_MS` | 5,000 | §9.1 |
| `GAP_MIN_MS` | 1,000 | §7.4, §9.1 |
| `GAP_PROVIDER_MIN_MS` | 30,000 (A-C5) | §7.4, §9.1 |
| `LIVE_LANDING_EXTRA_MS` | 1,000 (`liveLandingMs = max(startMs + this, latencyAtTune)`, A-C3) | §6 |
| `PAUSED_RECONNECTS_MAX` / `PAUSED_RECONNECT_WINDOW_MS` | 2 / 60,000 (A-C4) | §4.2, §4.4 |
| `ZAP_CONFIRM_AFTER_PAUSE_MS` / `ZAP_CONFIRM_WINDOW_MS` | 120,000 / 3,000 | §11.1 |
| `PAUSED_DIM_AFTER_MS` / `PAUSED_DIM_ALPHA` | 120,000 / 0.35 (picture brightness) | §11.5 |
| `PAUSED_CARD_DRIFT_MS` | 60,000 | §11.5 |
| `PAUSE_KEEP_AWAKE_MAX_MS` | 10,800,000 | §11.5 |
| `SCRUB_FOCUSED_IDLE_MS` | 5,000 | §11.6 |
| `HINT_SHOW_COUNT` | 3 | §11.6 |
| `PILL_MIN_TEXT_PX` | 24 (design px) | §11.4 |
| `LIVE_SNAP_MS`, `RW_ON_LIVE_BACK_MS`, `SCRUB_COMMIT_MS` | 30,000 / 30,000 / 600 (M4's) | §9, §11 |
| `LIVE_PAUSE_RELEASE_MS` | 10,000 (M2's; unbuffered tunes only) | §7.2, §10 |
| `HLS_DVR_MIN_WINDOW_MS` | 120,000 (follow-up) | §10 |
| `SEEK_LOCAL_P50_MS` / `_P90_MS` | 600 / 1,000 (gates) | §6, §15 |
| `WRITER_CPU_MAX` | 0.02 of one core at 1 MB/s (gate) | §15 |

---

## Implementation record — commit 0 (2026-10-19)

On the main working tree, uncommitted (the lead commits). Build command of CLAUDE.md plus `:core:stateOnRealNavigator`, `:data`, `:player`, `:ui`, `:app` unit tests, `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug`: **green**. No emulator run.

**Tests:** `:core:test` 3,237; `:core:stateOnRealNavigator` 358; `:data:testDebugUnitTest` 337; `:player:testDebugUnitTest` 370; `:ui:testDebugUnitTest` 344; `:app:testDebugUnitTest` 56. All pass, none skipped.

**New contract files (§16 verbatim, with KDoc):**
- `core/.../playback/timeshift/TimeshiftContracts.kt`: §16.1, every type.
- `core/.../playback/timeshift/TimeshiftConstants.kt`: Appendix A as `object TimeshiftConstants`, every number named. `LIVE_SNAP_MS`, `RW_ON_LIVE_BACK_MS` and `SCRUB_COMMIT_MS` alias M4's constants. `LIVE_PAUSE_RELEASE_MS` mirrors M2's value, because `:core` cannot see `:player`. The table's units are added as `RING_MAGIC` and `RING_FORMAT_VERSION`.
- `core/.../catchup/archive/TimelineRouter.kt`: §16.3 (interface, `LocalWindow`, `TimelineTarget`, `ClampReason`).

**Additive edits:**
- `PlaybackContracts.kt`: `FailureSignal.RingBoundary`.
- `ArchiveContracts.kt`: `TimelineSource`; `ArchiveStatus` gains `source`, `bufferFromMs`, `notSaved`, `pauseRemainingMs` and `savingStop`; `ArchivePlayback.localBuffer`.
- `ArchiveVisibility.kt`: the LOCAL line.
- `PlayerContracts.kt`: `LocalBufferInfo`, `PlayerEvent.Buffer`, 17 `PlayerToast` values, the `ScrubState` fields, `ScrubScope`, `PausedHeader`, and `PlayerStateMachine.zapNeedsConfirm` (default false).
- `SettingsStore.kt`: three `PlaybackPrefs` fields and three enums.
- `PlaybackRows.kt`: `TIMESHIFT` (SIMPLE, NEXT_TUNE), `TIMESHIFT_HOLD` and `TIMESHIFT_PROTECT` (ADVANCED, LIVE); `ON_LAUNCH` → ADVANCED. Order: …Aspect, Pause and rewind, Keep recording, Protect storage, On launch….
- `PlaybackApi.kt`: `LocalBufferState`, `LocalSeekResult`, the three controller members with defaults, `NO_LOCAL_BUFFER`, `PositionSnapshot.localMonoMs`, six `PlaybackNotice`s, four `StreamStats` fields.
- `Seams.kt`: `RingOpen`, `TransportFactory.ring`, `MediaSourceBuilderApi.build(…, ring)`, `TimeshiftSessionFactory`, `TimeshiftPrepared`, `TimeshiftGrant`, `TimeshiftSession`.
- `FailureClassifier.kt`: `Facts.writerTuneId`; `classifyLive`, which delegates to `live()`. `live()` also accepts `writerTuneId`, which is a no-op while that is null.

**Stubs** (each file starts with `// WP-x replaces this stub`):
- WP-A (`core/.../playback/timeshift/`): `TsRingImpl` (an empty ring, `FACTORY`), `RingIndexImpl` (empty), `RingReaderImpl` (throws `RingEndException(SESSION_END)`), `TsIndexerImpl`, `RingWriterImpl` (`FACTORY`; no thread), `BlockPool`, `RingFlusher`, `FileRingStorage` (no space) and `TimeshiftPolicyImpl` (length null, which means off).
- WP-B (`player/.../timeshift/`): `TimeshiftSessionFactoryImpl` (`prepare` → `Off(SETTING_OFF)`), `TimeshiftSessionImpl`, `LiveTsUpstream`, `RingDataSource`, `RingReaderTelemetry`, `AndroidRingStorage`, `TimeshiftStorageImpl`, `TimeshiftDebugOverrides`, and `TimeshiftModule`. The module binds `TimeshiftSessionFactory`, `TsRingFactory`, `RingWriterFactory`, `TimeshiftPolicy` and `TimelineRouter`.
- WP-B also gets stub edits in two files it owns: `TransportFactoryImpl.ring()` returns a `RingDataSource` factory, and `MediaSourceBuilder.build` takes `ring` and ignores it.
- WP-C: `core/.../catchup/archive/TimelineRouterImpl.kt` (M4 routing: Live, Provider or Clamp; never Local), `app/.../playback/LocalTimelineBackend.kt` (`localBuffer` always null) and `ui/.../full/PausedCard.kt` (draws nothing).
- The controller does not call any of them, so the app behaves as before.

**Exhaustive `when`s, texts and tests:**
- `PlayerText.toast` handles the 17 toasts. Multi-value args are joined with `PlayerText.ARG_SEP`. For `PAUSE_NO_REWIND_HLS`, the arg is the playlist name for the Xtream variant and null for M3U.
- `PlayerText.notice` handles the six notices:
  - `LocalGap` and `GapSkipped` → null. The VM adds the landing time.
  - `SavingStopped` maps SLOT_LOST/PREEMPTED, DISK_ERROR/STORAGE_EVICTED and DISK_TOO_SLOW to their §13 toasts; the rest → null.
- `PlayerStateMachineImpl` gets `is PlayerEvent.Buffer -> Unit`.
- `strings_player.xml` holds every §13 string verbatim, with placeholders for the example values. It also holds the header, card, pill, hint, scrub, quick-bar, stats and error-detail lines. The existing `player_scrub_live` ("Live") clashed with the new "LIVE" end label, so the new one is `player_scrub_live_end`.
- `SettingsText` gains 15 keys, with `ResourceSettingsTexts` and `strings_settings.xml` in step.
- `WpaPages` renders the three choice rows with their values and writes `PlaybackPrefs`.
- Test fakes gain `ring()` and the new `build` parameter (`Fakes.kt`, `RealEngineControllerTest`, `EngineTestSupport`).
- `WpaPagesTest`: `ON_LAUNCH` is now ADVANCED; three new rows; a new intents test. `SettingsBackLadderTest` focuses `TIMESHIFT` instead of `ON_LAUNCH`. `SimpleRowCountTest` is unchanged (PLAYBACK = 5, total = 35).

**Contract adjustments (for lead review):**
1. `ArchivePlayback.localBuffer` has a default getter (`NO_LOCAL_BUFFER_INFO`, always null) so that `ArchivePlaybackImpl` and the fakes compile unchanged. §16.3 shows it abstract.
2. `TransportFactory.ring` is abstract as §16.4 says. Every implementer got an override: the stub in `TransportFactoryImpl`, and the test fakes.
3. `PlayerToast` value args are not specified by §16.3. Commit 0 documents them in `PlayerText` with `ARG_SEP`. WP-C may change the bodies.
4. The `PAUSE_NO_REWIND_HLS` Xtream text has the playlist name as `%1$s` where §13 writes "(playlist)".
5. The WpaPages "Pause and rewind" row reads Auto as 30 min and computes its size as length × `ASSUMED_BYTES_PER_SEC` × `CAPACITY_HEADROOM` in GiB, which gives "1.9 GB". WP-C wires Auto to real storage and adds the "storage is limited" value and Off deleting the file.

**Left for the WPs:**
- The Buffer row's U-N7 description.
- Relabelling `QuickItem.TIME_SHIFT` to "Pause" (`player_quick_pause` exists; `player_quick_time_shift` is unchanged).
- The `strings_m5_enums.xml` labels. The settings labels live in `strings_settings.xml` through `SettingsText`.

## Implementation record — WP-B (2026-10-19)

On the main working tree, uncommitted (the lead commits). `./gradlew :player:testDebugUnitTest`: **437 tests, 0 failures** (370 before WP-B, 67 new). `:app:assembleDebug`: **green** (the Hilt graph resolves the new bindings). `:core:test` not run (WP-A's). No emulator run.

**New files (`player/src/main/kotlin/app/thebox/player/timeshift/`):**
- `LiveTsUpstream.kt`: `TsUpstream` over the tune's `LiveTsDataSource` (one brokered connection; in-source reconnect, splice, PCR hold and `ByteWatchdog` unchanged; `terminate()` cancels the call and releases the lease from any thread). `WriterHooks` (`onPcrJump`, `reconnectAllowed`, `onSplice` on `RECONNECT_OK` → `TsIndexer.onSplice`) and the `WriterTransport` seam.
- `RingDataSource.kt`: §5.1. `open` opens the reader at the start offset, reads one packet (the "blocks until one packet is readable" rule), returns `C.LENGTH_UNSET`, ignores and counts `dataSpec.position`; `read` returns whole packets, never 0/−1; any non-ring `IOException` is wrapped in `RingIoException`; cancel by thread interrupt (`Loader.cancelLoading`), `terminate()` (registered as a `Terminable` in the reader tune's transport scope, A-S9) or `close()`; placeholder URI, no headers.
- `RingReaderTelemetry.kt`: §5.2 / A-B3 gating (`readPendingSinceMs` only while this reader's read is blocked at the head; `inReconnect` = (writer reconnecting ∥ restart pending ∥ waiting for a lease) ∧ at the head; `waitingForLease` at the head before the first delivered byte; the writer's HTTP facts for stats).
- `AndroidRingStorage.kt`: `RandomAccessFile`/`FileChannel` positional I/O from direct buffers (pwrite/pread, A-S8), `Os.posix_fallocate` behind a `RingPosix` seam with the sparse `setLength` fallback (EOPNOTSUPP/ENOSYS/no native `Os`; ENOSPC is an error), `Os.fstat(fd).st_nlink` with a re-stat fallback, StatFs space, close-then-unlink `delete()`, every written byte to the daily counter. Header through WP-A's `RingHeaderCodec`.
- `TimeshiftStorageImpl.kt`: `cacheDir/timeshift/ring-<pid>.bin`, the once-per-process sweep on a background thread that spares this pid's file, `awaitSweep()`, StatFs, and the daily write counter in `noBackupFilesDir/timeshift-writes` (`<date> <bytes>`, local-midnight reset, persisted every 64 MiB).
- `TimeshiftSessionImpl.kt`: the cycle's session (writer under `writerTuneId`, stash, pause hold, writer restart with a new upstream under the same id, connection release on FULL/HOLD/SLOT_LOST/PREEMPTED/UPSTREAM_FAILED, not-saved ranges, `snapshot`), plus `TimeshiftSessionControl` (below). Close order: `writer.stop(SESSION_END)` → `transport.endSession(writerTuneId)` (call cancelled, then lease) → `writer.close()`; the pump is joined by the next `prepare`.
- `TimeshiftSessionFactoryImpl.kt`: the A-C1 split. `prepare` (IO): Off → delete the file; sweep wait, StatFs, sizing through `TimeshiftPolicy` and the debug overrides, `NO_SPACE` below `MIN_RING_BYTES`, then under one lock the bounded join of the previous pump and either a logical `reset()` (zap; file reused) or a new storage + ring when the capacity changed beyond the §8.3 tolerance; first extent allocated; `STORAGE_ERROR` on `IOException`. `start` (main): writer + fresh indexer, session started. Discard (stop, lifecycle, Off, trim) closes the ring, closes the fd, deletes the file, never under an open session. Also `TimeshiftPrefsSource`/`StoreTimeshiftPrefs` and `NoTimeshiftFactory`.
- `TimeshiftStartup.kt` (public): `onAppStart()` (sweep) and `onTrimMemoryComplete()`; `TimeshiftDebugOverrides.kt`: a public object (ring bytes, hold, daily cap, free space) for WP-D's debug receiver; `TimeshiftModule.kt`: + `WriterTransport` → `TransportFactoryImpl`, `TimeshiftPrefsSource` → `StoreTimeshiftPrefs`, an unscoped `TsIndexer` provider.

**Edits:**
- `controller/PlaybackControllerImpl.kt` (§7): two constructor parameters with Off defaults (`timeshift`, `timeshiftPrefs`); storage prepared on IO in parallel with facts + resolve (setting Off: nothing launched on the zap path); the decision once per cycle after the resolve; `startTimeshift` reserves the writer tune id from the controller's sequence; `localBuffer` (≤ 1 Hz, forced on events); `seekLocal` (direction-aware RAP pick, A-S4 forward rule, Clamped, forward past the newest RAP → Live), `jumpToLiveLocal` (`liveLandingMs = max(profile.startMs + 1,000, latencyAtTune)`, A-C3; after a saving stop: writer restart, then the first RAP after the old head); `Plan.reader`/`Plan.recreateTimeshift`; every re-open of a buffered cycle is reader-only at the pin (`launchSession` converts the plan), with **no `player.stop()`** between prepares (amended 2026-11-11: except a re-prepare that needs new decoders, §6 step 3); pause/resume rows of §7.2 (no `livePauseJob`, no `LiveResumed`, writer restart with `GAP` on resume after a stop); `keepScreenOn` rules of §11.5 for buffered pauses; the writer listener posts to main; `onWriterEvent` (lease milestone; reconnect state rules only at the head); `onWriterFailure` (A-C4 refusal and limit-like failures not at the head → SLOT_LOST, PREEMPTED, `SegmentStormException` → `PcrDiscontinuity`, else `classifyLive` with `writerTuneId` → ladder once); `executeBuffered` (writer-scoped on `writerJob`, recreate before the first byte, cycle-scoped deferred behind live, `Fail` stashed behind live, `SeekToLiveAndPrepare` → `jumpToLiveLocal`); detector (a) `NO_BYTES` with a buffered cycle is writer-scoped; reader ring exceptions before the classifier and the ladder (boundary hand-off with the A-C5 rule, stashed card or deferred decision at `RingEnd(UPSTREAM_FAILED)`, writer restart + live on other ends, oldest RAP on position lost, live + storage toast on I/O errors); `closeTimeshift` before every next cycle (zap keeps the file, catch-up/stop/lifecycle discard it); `LeftAppCleared` once after a lifecycle stop of a paused/behind buffered cycle; stats `rewind*` fields; `position().localMonoMs`.
- `controller/FailureClassifier.kt` (bodies): ring row first (`RingBoundary`; `RingEnd(SESSION_END)` → stale; other ring exceptions → `Unknown`, never a network row), `ringException()` helper.
- `datasource/LiveTsDataSource.kt`: `onPcrJump` and `reconnectAllowed` (null for every caller but the writer). `datasource/TransportFactoryImpl.kt`: `ring()` (reader registered in the reader tune's scope) and `writer()` (WriterTransport). `datasource/BoxLoadErrorHandlingPolicy.kt`: `RingException` fatal. `engine/MediaSourceBuilder.kt`: the ring source (same progressive TS source and extractor guards over `transport.ring`).
- `app/.../TheBoxApplication.kt`: the start-up sweep and the trim-memory discard through `TimeshiftStartup`.
- Not edited: `LifecycleCoordinator.kt` (the controller marks and emits `LeftAppCleared`), `StatsCollector.kt` (the controller fills the rewind stats).

**Tests (67 new):** `RingDataSourceTest` (11: open/offset/unset length, position ignored, block and wake, terminate and interrupt cancel, boundary/end/generation, wrapping and never-0, `RingException` fatal under the policy, A-B3 telemetry gating, `TransportFactoryImpl.ring` + `endSession` cancel), `RingDataSourceRealRingTest` (1, WP-A's real `TsRingImpl` over `AndroidRingStorage` in a temp dir), `ReaderOverRingExoTest` (3, real ExoPlayer: mid-stream RAP to first frame with position ≈ 0; boundary → `RingBoundary` → reader-only re-prepare without stop; `release()` closes a reader blocked at the head ≤ 300 ms), `TimeshiftStorageTest` (8: fallocate fallback and success, ENOSPC, pwrite/pread from direct buffers, shared header codec, nlink and re-stat, close-then-unlink, sweep spares this pid and `prepare` awaits it, daily counter 64 MiB flush and local-midnight reset), `TimeshiftSessionTest` (10: life cycle and ordering, join before reset, rebuild on capacity change, Off/NO_SPACE/STORAGE_ERROR, discard never under an open session, connection-releasing stops, hold limit and restart, splice hook, snapshot), `ControllerTimeshiftTest` (29, real `FailureClassifier`: every §17.2 case — 403 before the first byte decided once, first-open RetrySame recreate, 3rd reconnect while paused → SLOT_LOST, reconnect state only at the head, RetrySame with a 10 s delay at the head is no NO_BYTES stall, Fail behind → stash → card once, cycle-scoped deferred, SLOT_LOST no reconnect, every `launchSession` call site keeps the place behind, pause/resume rows, keep-awake, seek with the last frame held, forward/clamp, live landing, boundary hand-off, provider gap rule, Home/standby discard + `LeftAppCleared` once, zap milestones from the writer, zap closes the recorder first, Off path journal-identical to M2), `FailureClassifierRingTest` (2), `LiveTsWriterHooksTest` (3). Test-only edits: `ControllerFixture` takes the time-shift factory and prefs (defaults = Off), `FakeMediaSourceBuilder` records `ring`, `FakeProvider.catchup`.

**Deviations:**
1. Settings: commit 0's `PlaybackSettings` does not carry `timeshift`/`timeshiftHold`/`storageProtection`, so the controller reads `PlaybackPrefs` through `TimeshiftPrefsSource` (bound to `SettingsStore`'s playback domain).
2. `TimeshiftSessionControl` (internal, WP-B's file) extends `TimeshiftSession` with what §7 needs and the contract lacks: `stopSaving`, `close(discardFile)`, `firstBytes`, `restartPending`/`markRestartPending`, `stoppedBy`, `readerAtHead`, `takeCapRefusal`. The controller uses it through a safe cast.
3. The recorder's upstream comes from a `WriterTransport` seam implemented by `TransportFactoryImpl` (no contract change to `TransportFactory`).
4. `RingStorage.allocate` takes and returns **file** bytes (header included), as WP-A's `TsRingImpl` calls it; `capacityBytes` is the data capacity. The header is WP-A's little-endian `RingHeaderCodec`.
5. The gap routing rule of A-C5 is applied in the controller (catch-up channel ∧ active LOCAL time-shift ∧ (`GAP` segment ∨ gap ≥ 30 s) → `LocalGap` notice) instead of calling WP-C's `TimelineRouter.onGap`, whose commit-0 stub never answers Local. Switching to the router is a one-line change once WP-C's lands.
6. No preview exclusion (§7.1 "the target is not a preview"): the API has no preview flag; every live TS cycle is eligible.
7. The A-C4 cap: when the writer is WP-A's `RingWriterImpl`, the hook delegates to its `reconnectAllowed()` (it stops itself with SLOT_LOST); any other writer uses the session's own count and the controller turns the refusal into SLOT_LOST.
8. Exo tests assert the first rendered frame instead of READY (an endless source never reaches READY under Robolectric's shadow codecs; the RealEngineControllerTest day-0 constraint).
9. WP-B's own fakes (`player/src/test/.../timeshift/TimeshiftFakes.kt`): WP-A's `core/src/testFixtures` are not on `:player`'s test classpath and build files are out of scope.
10. A pause during Connecting **before** the time-shift session exists (still resolving) takes M2's `livePauseJob`; once paused there, a later resume re-tunes as M2 does.

**Contract problems for the lead:**
- `TimeshiftSession` lacks stop/discard/first-byte/stop-reason members (deviation 2); `TransportFactory` has no writer method (deviation 3); `PlaybackSettings` lacks the three prefs (deviation 1).
- `RingStorage.allocate` units are ambiguous in §16.1 (WP-A and WP-B now agree on file bytes; the KDoc should say so).
- `TsRing` declares `val mode` and `fun setMode(mode)`: an implementer cannot use `var mode` (JVM signature clash); harmless but surprising.
- `RingWriter` has no `reconnectAllowed`; WP-A added it as a class method, which `TimeshiftSessionImpl` calls by type.
- `RingWriter.restart` after the writer stopped itself (FULL/HOLD/SLOT_LOST) is assumed to resume saving with the given segment reason (§7.2 resume, Back to live); WP-A's KDoc covers restart after a failure, not explicitly after a self-stop.

**Needs the device (WP-D / M6 hardware):**
- The last frame held across `setMediaSource` + `prepare` with no `stop()` (§22), and seek commit → first frame P50/P90 (I-TS-2, -3).
- The hard zap gate (I-TS-22): Off adds one settings read; On awaits `prepare` after the resolve, which includes the bounded join (≤ `PUMP_JOIN_MAX_MS`) of the previous pump and the first-extent allocation; both should be milliseconds, but a slow pump exit would add up to 1 s.
- `Os.posix_fallocate` on Fire OS ext4 / Onn, `st_nlink` after installd's cache clearing (I-TS-28), the sweep vs an auto-tune (I-TS-14).
- `keepScreenOn` against the Fire TV screensaver while paused (I-TS-23), Home/standby discard + `LeftAppCleared` (I-TS-13).
- One connection while paused, then 0 after the hold/full/slot stop, against the fake panel's slot log (I-TS-4, -5, -25), and the A-C4 cap with `kick_oldest`.
- `ReaderCancelTest` under the real `Loader` on device threads, and the reader telemetry against the real `PlaybackWatchdog` (no NO_BYTES re-prepare behind live, I-TS-8, -19).

---

## Implementation record — WP-A (2026-10-19)

On the main working tree, uncommitted (the lead commits). `./gradlew :core:test` is **green**: 3,316 tests (3,237 before plus 79 new) and `stateOnRealNavigator` 358, none skipped. No contract file was changed. No emulator was used.

**Files.** All nine stubs in `core/src/main/kotlin/app/thebox/core/playback/timeshift/` are replaced:
- `TsRingImpl.kt`: the ring, sink, pins, FULL, PAUSE_ONLY, disk stops, fence and flush step.
- `RingIndexImpl.kt`: the index and the segment table.
- `RingReaderImpl.kt`.
- `TsIndexerImpl.kt`.
- `RingWriterImpl.kt`: the pump, plus the new public `SegmentStormException`.
- `BlockPool.kt`.
- `RingFlusher.kt`.
- `FileRingStorage.kt`: also holds the public `RingHeaderCodec`, the §3.1 header that WP-B's `AndroidRingStorage` can share.
- `TimeshiftPolicyImpl.kt`.

The constructors that WP-B binds are unchanged: `TsRingImpl.FACTORY`, `RingWriterImpl.FACTORY`, `TimeshiftPolicyImpl()`, `TsIndexerImpl()` and `FileRingStorage(file)`.

**Test fixtures** (`core/src/testFixtures/.../playback/timeshift/`):
- `SyntheticTs`: a deterministic, allocation-free TS generator with PAT/PMT (one or two programs), PCR, RAI, IDR/keyframe NALs for H.264, H.265 and MPEG-2, the 2³³ start, PTS-first or joint jumps, the `discontinuity_indicator`, a PMT change, radio and no-PCR streams.
- `TimeshiftFakes.kt`:
  - `FakeMonoClock`;
  - `MemoryRingStorage`, with fault injection (write and read errors, unlink, free space, latency);
  - `FakeTsUpstream`, scripted: failures, EOF, a blocking read for the fence, pacing;
  - `RecordingWriterListener`;
  - `FakeRingReader` and `FakeTsRing` for WP-B.

**Tests** (`core/src/test/.../playback/timeshift/`): 79 tests in 20 classes, plus the shared helpers in `RingTestKit.kt`. The whole package runs in about 75 s.

| Class (§17.1 row) | Tests | What it proves |
|---|---|---|
| `TsRingPropertyTest` | 1 (10,000 seeded runs) | Random packet runs, PLAYHEAD pins, ≤ 3 readers at random offsets, PERSIST/PAUSE_ONLY, pause, resets. §3.2 invariants after every operation; every byte read equals the byte written; full read-back of `[tail, head)`; old readers fenced |
| `TsRingWrapTest` | 6 | Capacity for ±188; `HEADER + L mod capacity` with no packet straddling; tail on the oldest RAP; entries before tail dropped; a reader across 3+ wraps; position lost |
| `RingPoolTest` | 3 | A parked reader plus 60 s of writes: 0 pool waits. A reader 600 blocks behind plus 60 s at 1×: 0 pool waits, reading from the file. A block recycled during a copy: stamp miss, then a file re-read with correct bytes |
| `TsRingFullTest` | 4 | Paused FULL answers at once and the tail never passes the pin; playing FULL waits for the pin; a 15 MB burst into a 10 MB ring with a 1× reader gives no gap; a pin stuck past `PIN_STALL_MS` gives a `GAP` segment and a boundary at the reader |
| `RingFenceTest` | 1 | An old pump blocked in `read` across `reset()`: its late commit is dropped, the new generation's bytes are intact, the pump exits on STALE_GENERATION, and an old reader gets `RingEndException(SESSION_END)` |
| `RingIndexTest` | 5 | floor/ceil over a wrapped 32-slot array; first/last/monoOfOffset; 28 B per entry (2 h = 18,256 entries = 511 KB); segment drop, reuse and the 64-segment cap; 3 reader threads against a hot writer with consistent snapshots |
| `PcrUnwrapTest` | 1 | Start 20 s before 2³³: one segment, RAP spacing 1,000 ± 50 ms across the wrap, BEHIND 50 s ± 1 s |
| `PtsOriginTest` | 2 | 700 ms PTS−PCR lead. 500 random targets land ≤ one GOP + 50 ms before the target, and RAP mono equals its bytes' arrival within 60 ms. Back to live: `liveLandingMs` ≤ BEHIND ≤ `liveLandingMs` + 1,000 |
| `DirectionStepTest` | 1 | 10 s GOP: every → advances exactly one GOP; a short → still advances; ← floors; anchors are never chosen |
| `TsIndexerTest` | 8 | RAI with its PTS and lead; NAL fallback for H.264 (5 and 7), H.265 and MPEG-2; fallback only after 10 s without RAI; PSI with 2 PMTs reported once; no PCR gives arrival clock; spacing ≥ 400 ms and anchors ≤ 2 s; radio; broken sync bytes |
| `InlineJumpTest` | 6 | A 5 s connect burst makes no segment; PTS-first gives one boundary at the PTS with the PCR step absorbed; a joint backwards jump; `discontinuity_indicator` with a 300 ms step; a jump after a splice is `PCR_JUMP` with the SPLICE flag; a continuous splice makes no boundary |
| `PsiChangeTest` | 1 | A PMT version change gives `PSI_CHANGE`; a reader stops exactly at the boundary with the next RAP; a reader at that RAP gets the new PMT (version 1, new audio PID) |
| `WallStepTest` | 1 | The ring's half: index, pins and live edge depend only on `MonoClock` |
| `RingReaderTest` | 8 | Wake after a commit (30 trials: median ≤ 5 ms, max ≤ 50 ms); cancel, interrupt and close end the read in ≤ 50 ms; PSI first; whole packets; `len` < 188; never 0/−1; end only once drained (also from a blocked read); a disk stop at live does not end the reader; `RingIoException`; fence |
| `RingWriterTest` | 12 | `onFirstBytes` once, all callbacks on `box-ts-pump-<tag>`; open and read failures reported once, then idle; restart is terminate → close → open; hold limit on mono (4,999 ms vs 5,001 ms); FULL while paused stops and releases, then resume gives a `GAP` with its gap; the reconnect cap while paused gives `SLOT_LOST` with no ladder; at the head always allowed; a controller stop is not echoed; segment storm reported once; close/`awaitExit` gives `SESSION_END`; a splice plus jump gives `PCR_JUMP` with gap ≥ 3 s |
| `FlushTest` | 8 | A slow disk gives `DISK_TOO_SLOW`, the connection is kept and live goes on from RAM; ENOSPC/EIO give `DISK_ERROR`; unlink gives `STORAGE_EVICTED` and closes the fd; PAUSE_ONLY writes 0 bytes at live, follows the RAM window and persists from the oldest pool block on pause; partial-block flush after 1 s; extent growth and low-space wrap; daily counter every 64 MiB; header on create and on reset |
| `TimeshiftPolicyTest` | 7 | Lengths (Auto 30/60 min); the Fire TV example (8 GB storage, 3 GB free: 1.926 GB capacity, window = 30 min; 2 GB free: ≈ 15 min, "storage is limited"); 64 MiB to 8 GiB clamps; `NO_SPACE`; hold Auto = until full; 12 GB cap only on ≤ 16 GB; mode never switches while behind; `pauseRemainingMs` |
| `RingAllocationTest` | 2 | 10,000 blocks of write + scan + commit + flush + block-aligned reads on one thread: < 1 KB in total (measured 0). One copy per range read. With the threaded pump and flusher over 5,000 blocks: < 1 B per block (measured ≈ 0.07 and 0.14) |
| `RingSoakTest` | 1 | 10 min of 1 MB/s (597 MB) into a 40-block ring: 228 wraps; a 1× reader at a random 0–20 blocks behind with the PLAYHEAD pin following; invariants after every block; ≈ 3 M filler packets checked byte by byte; about 3 s |
| `RingStressTest` | 1 | Pump, flusher, a reader at live and a reader behind that re-seeks, plus main-thread lookups, for 10 s: no torn byte in ≈ 6 M packets, invariants hold, wraps |

**Deviations, with reasons:**
1. **`RingStressTest` runs 10 s, not 60 s**, to keep `:core:test` reasonable; `-Dthebox.ringStressSeconds=60` runs the design's length. The soak and property tests cover volume.
2. **`WallStepTest` covers the ring's half only.** The ring, index, writer and pins never read the wall clock. The "snapshot shifts as a whole" half belongs to `TimeshiftSession.snapshot` (WP-B).
3. **Radio anchors carry `RAP`.** §3.4 item 4 says time anchors only, but then a radio channel could never be sought (`floorRap` = −1). They sit on audio PES starts (with PTS) every ≤ 2 s, and an audio frame is a random-access point.
4. **`PIN_STALL_MS` while playing:** a stall is not a `RecordStop`, because no reason fits and the connection should live on. After `PIN_STALL_MS` of FULL, the pump keeps reading the upstream into a scratch buffer and drops the bytes. When space returns it starts a `GAP` segment with the real gap.
5. **Time limit on `tail`** (§3.2, "first entry with pts ≥ newest − lengthMs") is not enforced in the ring: `TsRingFactory` passes no `lengthMs`. The window is bounded by capacity (sized from the length, §8.2) and by the index capacity.
6. **Disk stops make the ring RAM-only.** For `DISK_ERROR`, `STORAGE_EVICTED` and `DISK_TOO_SLOW`, `stop` is set and `saving` = false, but the data is not ended: the pump keeps committing to RAM, so a reader at live goes on (§4.6). The part already in the file stays readable while the pins hold it. Unflushed RAM blocks are recycled only once `tail` passes them, so a reader behind live holds the pump (FULL) instead of losing bytes. Only non-disk stops (and close) make a reader at `head` throw `RingEndException`.
7. **Low free space** (§3.1) can only shrink the capacity before the first wrap: the ring then wraps at the current head block. After the first wrap the whole file is allocated, so there is nothing left to stop growing.
8. **`onStopped`** fires for the stops the writer decides (FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST, the three disk stops); it is not fired for a `stop(reason)` the controller asked for.
9. **`PCR_JUMP` gap** = arrival of the batch holding the jump − the old segment's live edge, which equals the reconnect time. Inline jumps and PSI changes have gap 0; `WRITER_RESTART`/`GAP` take the writer's measured gap.
10. **Mono timeline monotonic across boundaries:** an entry's mono is clamped to at least the previous segment's live edge, so binary search stays valid while `monoOffset` converges. `ptsPcrLead` is the median of up to 16 RAP samples per segment. The segment table is ≈ 0.11 MB (64 × 8 PSI packets = 96 KB, plus lead samples), against §15's "segments ≤ 0.1".
11. **Arrival-clock switch** after `INDEX_MAX_GAP_MS` of arrival without a PCR; it switches back when a PCR appears. Entries carry the flag individually.
12. **Jump pairing:** after a PTS-placed boundary, PCRs still on the old timeline are ignored until the PCR step, which is absorbed as the new base; the symmetric rule holds for a PCR-placed boundary. Either is given up after 2 s of old-timeline progress.
13. **At a boundary whose next segment has no RAP yet,** the reader waits (as at the head) until it has one, or throws `RingEndException` if the ring ends.
14. **PAUSE_ONLY persistence** also starts when a pin comes within half the pool of the RAM window's end, not only on pause, so a reader falling behind at live never loses bytes. A reader at live never triggers it.
15. **"Behind" for the reconnect cap** = paused, or no open reader at the head. A reader is at the head while it waits there or is within one block of it.
16. **`read` with `len` < 188** returns a partial packet and realigns on the next read: Media3 may peek with small lengths. With `len` ≥ 188, reads are whole packets.
17. **`FileRingStorage`** gained defaulted parameters `(capacityBytes = 0, pid = 0, createdMonoMs = 0)`. Capacity 0 is the commit-0 "no space", and a ring over it runs RAM-only. On the JVM, `isUnlinked` means "the path is gone".
18. **`TsRingImpl` takes an internal `flusher` parameter;** with null (tests), `claim` flushes inline.
19. **Hot-path locks** spin briefly on `tryLock` before `lock()`, and all waits use `LockSupport.park`, so no AQS node is allocated per block (§15).

**Contract problems for the lead:**
1. **The `RingIndexView` slot API has a check-then-use window.** `floorRap()` followed by `offsetAt()`/`ptsMonoAt()` are separate seqlock reads, so a slot can be reused by a newer entry in between. That needs a full index lap, so it is rare, but `RingIndexTest` saw it under load. Suggested addition: `fun floorRapEntry(monoMs: Long, out: LongArray): Int` returning offset and PTS mono in one section. It exists as `internal floorRapSnapshot`. Until then, WP-B should check `offsetAt(slot) >= ring.tail` and that the mono is on the expected side of the target, and re-query if not.
2. **Segment storm has no listener call.** WP-A reports it as `RingWriterListener.onFailure(SegmentStormException)`. WP-B's classifier must map it to `FailureSignal.PcrDiscontinuity` on the writer-scoped ladder (§3.5, §7.4); it is not a `LiveStreamException`.
3. **`reconnectAllowed()` is on `RingWriterImpl`, not `RingWriter`.** WP-B wires `LiveTsDataSource(reconnectAllowed = …)` and needs a cast of the factory's result, or the lead adds it to the interface.
4. **The daily write counter is not on `TsRing`.** `persistedBytes`/`persistListener` are on `TsRingImpl`, so WP-B needs a cast or a concrete factory type.
5. **`TsRingFactory` takes no `lengthMs`** (deviation 5). If the time limit matters beyond the byte capacity, the factory or `TsRing` needs it.
6. **`:player` has no `testImplementation(testFixtures(project(":core")))`.** The fakes above (`FakeTsRing`, `FakeRingReader`, `MemoryRingStorage`, `SyntheticTs`, `FakeTsUpstream`) cannot be reached from `:player` tests without that build-file line, which is a lead change. The real `TsRingImpl` over `FileRingStorage` in a temp directory works without it.
7. **Splice notification.** The indexer marks SPLICE and classifies `PCR_JUMP` only if `TsIndexer.onSplice()` is called on the pump thread when the source reports `RECONNECT_OK`. `LiveTsUpstream` (WP-B) holds the indexer it passed to the factory and must make that call; the writer has no hook for it.

---

## Lead reconciliation — WP-A/WP-B (2026-10-19)

The lead's pass over the "contract problems for the lead" and the deviations of commit 0, WP-B and WP-A. On the main working tree, uncommitted. Contract changes are marked in `TimeshiftContracts.kt` (header comment) and `Seams.kt` (`R8`/`R9` comments). WP-C's files were not touched, with one exception noted under R10.

**Build** (`./gradlew :core:test :player:testDebugUnitTest :app:assembleDebug`): see "Result" at the end.

| # | Problem (source) | Decision | Where |
|---|---|---|---|
| R1 | `RingIndexView` slot API has a check-then-use window (WP-A #1) | **Contract:** `floorRapEntry(monoMs, out)`, `ceilRapEntry(monoMs, out)`, `newestRapEntry(out)`, `oldestRapEntry(out)`: the slot and `out[0]` = offset, `out[1]` = PTS mono from one seqlock-consistent read (`out` undefined when −1). All four, because the controller uses all four lookups off the writer thread. The two-step forms stay for the writer thread (the only index writer). The controller's seek path (`seekLocal`, `pinStart`, `liveStart`, `setPlayheadPin`, `resolveOrigin`, position-lost, boundary origin, live-after-restart) and `TimeshiftSessionImpl.snapshot` use the entry forms. There was no `offset >= tail` workaround to remove. | `TimeshiftContracts.kt`, `RingIndexImpl.kt` (`floorRapSnapshot` now backs `floorRapEntry`), `PlaybackControllerImpl.kt`, `TimeshiftSessionImpl.kt`; test `TsRingTimeLimitTest.entryFormsMatchTheTwoStepLookups` |
| R2 | Segment storm has no listener call (WP-A #2) | `SegmentStormException` moves into the contract file (still reported through `onFailure`). §7.4 and §14 say *writer-scoped `ReResolve`, then M2 L25 on a repeat*, which is exactly what `FailureSignal.PcrDiscontinuity` gets from the ladder, so no dedicated signal. The mapping moves from the controller into the classifier: `FailureClassifier.classifyWriter(e)` (cause chain, so a wrapped storm also maps); the controller calls it first in `onWriterFailure`. | `TimeshiftContracts.kt`, `RingWriterImpl.kt`, `FailureClassifier.kt`, `PlaybackControllerImpl.kt`; test `FailureClassifierRingTest.aSegmentStormIsAWriterPcrDiscontinuity` |
| R3 | `reconnectAllowed()` not on `RingWriter` (WP-A #3, WP-B deviation 7) | **Contract:** `RingWriter.reconnectAllowed()`. The writer owns the A-C4 cap; a refusal makes it stop itself with `SLOT_LOST` through `onStopped` (which releases the connection in the session). WP-B's session-level count, `takeCapRefusal`, the controller's refusal branch, the `readerAtHead` session hook and the controller's `atHeadFlag`/`updateHeadFlag` are removed: a session always has a writer, so no session-level cap is needed. "Behind" is now the writer's rule only (paused, or an open reader not within one block of the head; WP-A deviation 15). It fails open while no reader is open (a reader re-prepare), which is today's behaviour. A closed session refuses. | `TimeshiftContracts.kt`, `RingWriterImpl.kt`, `TimeshiftSessionImpl.kt`, `PlaybackControllerImpl.kt`; tests `ControllerTimeshiftTest.aRefusedReconnectWhilePausedIsSlotLostWithoutTheLadder`, `theHookAsksTheWriterAndAClosedSessionRefuses` (the cap itself: `RingWriterTest`) |
| R4 | Daily counter not on `TsRing` (WP-A #4) | **Contract:** `TsRing.persistedBytes` and `var persistListener`. WP-B keeps counting at the storage level (`AndroidRingStorage.onWritten`, every `pwrite`), which is exact and also covers the header; it does **not** also set `persistListener`, which would double-count. The ring's counter is for consumers that do not own the storage (stats, the v2 recorder) and for tests. | `TimeshiftContracts.kt`, `TsRingImpl.kt`, both fakes |
| R5 | `TsRingFactory` takes no length; the time limit on `tail` is not enforced (WP-A #5, deviation 5) | **Contract:** `TsRingFactory.create(…, lengthMs)`; 0 = no time limit. `TsRingImpl` applies §3.2 on every commit: `tail` moves to the first RAP whose PTS mono is ≥ newest − `lengthMs`, only forward and never past `min(pins)` (a paused playhead or a reader behind keeps older media; the time limit never answers FULL). The factory reuses a ring only when capacity, index size **and** length match. | `TimeshiftContracts.kt`, `TsRingImpl.kt`, `TimeshiftSessionFactoryImpl.kt`; `TsRingTimeLimitTest` (window follows the length while capacity would hold more; no length = capacity only; a pin holds older media and releasing it catches up) |
| R6 | `:player` cannot see `:core`'s test fixtures (WP-A #6, WP-B deviation 9) | `player/build.gradle.kts` gains `testImplementation(testFixtures(project(":core")))`; `:core` uses the `java-test-fixtures` plugin and `:app`, `:data` and `:ui` already depend on it the same way. Overlap: WP-B's `FakeUpstream` is replaced by WP-A's `FakeTsUpstream(source = null)`. WP-B's `FakeTsRing`, `FakeRingReader` and `FakeIndex` stay: they have a data path (append, scripted boundaries, readers that block at the head) that WP-A's recording fakes lack, so they are not duplicates. `FakeRingWriter`, `FakeWriterFactory`, `FakeIndexer`, `FakePolicy`, `FakeRingFactory`, `FakePosix`, `FakeWriterTelemetry`, `FakeWriterTransport` and `nullPackets` have no counterpart and stay. | `player/build.gradle.kts`, `player/src/test/.../timeshift/TimeshiftFakes.kt`, `TimeshiftSessionTest.kt` |
| R7 | Splice notification (WP-A #7) | Already wired: `TransportFactory.writer` builds the writer's `LiveTsDataSource` with an `onEvent` that calls `WriterHooks.onSplice` on `RECONNECT_OK`, which `TimeshiftSessionImpl` binds to its writer's `TsIndexer.onSplice()`. `RECONNECT_OK` is emitted inside `read()`, so it runs on the pump thread before the spliced bytes are returned. New test drives the real wiring across two reconnects. | test `LiveTsWriterHooksTest.everyReconnectCallsOnSpliceOnTheReadingThreadBeforeTheSplicedBytes` |
| R8 | `TimeshiftSession` lacks stop/discard/first-byte/stop-reason members (WP-B deviation 2) | **Contract:** `TimeshiftSessionControl` is folded into `TimeshiftSession`: `grant`, `firstBytes`, `restartPending`, `paused`, `stopReason` (was `stoppedBy`), `stoppedAtMonoMs`, `markRestartPending()`, `stopSaving(reason)`, `close(discardFile)`. `takeCapRefusal` and `readerAtHead` are dropped (R3). The controller uses `c.ts` directly; no cast. | `Seams.kt`, `TimeshiftSessionImpl.kt`, `PlaybackControllerImpl.kt` |
| R9 | `TransportFactory` has no writer method (WP-B deviation 3) | **Contract:** `TransportFactory.writer(ctx, hooks): Pair<TsUpstream, StreamTelemetry>`; `WriterHooks` moves to `Seams.kt`. The `WriterTransport` seam and its Hilt binding are removed; the session factory and session take only `TransportFactory`. Every `TransportFactory` fake gains `writer` (the plain ones throw). | `Seams.kt`, `TransportFactoryImpl.kt`, `LiveTsUpstream.kt`, `TimeshiftSessionFactoryImpl.kt`, `TimeshiftSessionImpl.kt`, `TimeshiftModule.kt`, test fakes |
| R10 | `PlaybackSettings` lacks the three prefs (WP-B deviation 1) | **`PlaybackSettings` gains `timeshift`, `timeshiftHold`, `storageProtection`** (defaults as `PlaybackPrefs`), because that is how every other playback setting reaches the controller and it keeps `:player` free of `SettingsStore` knowledge. `TimeshiftPrefsSource`/`StoreTimeshiftPrefs` are removed; `TimeshiftSessionFactory.prepare` takes `PlaybackSettings`. The controller keeps a cycle unbuffered with no IO when the factory is `NoTimeshiftFactory`, whatever the setting, so every pre-M6 controller test still runs the Off path. **Outside the listed files:** `:app`'s `PersistedPlaybackSettings` (the settings adapter, not a WP-C file) maps the three fields both ways (`toSettings`, `prefsFrom`). | `PlaybackProviders.kt`, `Seams.kt`, `TimeshiftSessionFactoryImpl.kt`, `PlaybackControllerImpl.kt`, `app/.../settings/PersistedPlaybackSettings.kt`, `ControllerFixture.kt` |
| R11 | `RingStorage.allocate` units (WP-B contract problem, deviation 4) | KDoc: argument and result are **file** bytes including the `RING_HEADER_BYTES` header; `capacityBytes` is data bytes. Both implementations already did this. | `TimeshiftContracts.kt` |
| R12 | `TsRing.mode`/`setMode` signature clash | **Contract:** a single `var mode: BufferMode`; `setMode` removed. `TsRingImpl` keeps its volatile backing field behind the property; WP-A's `FakeTsRing` records `mode:` in its setter; callers use `ring.mode = …`. | `TimeshiftContracts.kt`, `TsRingImpl.kt`, both fakes, `FlushTest`, `TsRingPropertyTest`, `TimeshiftSessionFactoryImpl.kt` |
| R13 | `RingWriter.restart` after a self-stop unspecified (WP-B) | **Contract KDoc:** restart is valid after a failure, a controller stop (not SESSION_END) and a self-stop (FULL_WHILE_PAUSED, HOLD_LIMIT, SLOT_LOST). It starts a `reason` segment whose gap is the time since saving stopped, resumes saving (`stop` cleared, `saving` true on the next commit), clears the reconnect-cap history (new in `RingWriterImpl`: a fresh connection is not charged for the old one's reconnects), and does not repeat `onFirstBytes`. Disk stops stay (RAM-only for the generation). Call `onResume` first; a restart while still paused into a full ring stops again. | `TimeshiftContracts.kt`, `RingWriterImpl.kt`; test `RingWriterTest.resumeAndBackToLiveAfterEachSelfStopResumeSaving` (each of the three self-stops × ▶ resume and Back to live: `GAP` segment with gap ≥ the stopped time, `stop` null, saving, one `onFirstBytes`, and for Back to live a reader at the first RAP after the old head reads) |
| R14 | Gap rule coded in the controller instead of `TimelineRouter.onGap` (WP-B deviation 5) | **Left for WP-C.** Prepared so the switch is one line: the controller takes `timelineRouter: TimelineRouter` (bound in `TimeshiftModule`; default `TimelineRouterImpl()`, unused until then) and records `Cycle.lastStop` (writer self-stop or `stopSaving`). When WP-C's router lands, the lead replaces the condition in `PlaybackControllerImpl.onRingBoundary` (marked with an `R14` comment) — `if (hasArchive && active && (stopGap \|\| e.gapMs >= TimeshiftConstants.GAP_PROVIDER_MIN_MS)) {` — with: `if (timelineRouter.onGap(e.gapStartMonoMs, e.gapMs, if (stopGap) c.lastStop ?: RecordStop.SLOT_LOST else null, active, hasArchive, index.newestPtsMono()) is TimelineTarget.Provider) {` (only the `Provider` verdict is used, so the mono-frame times are harmless; the VM adds the landing time as today), and drops `@Suppress("unused")`. | `PlaybackControllerImpl.kt` |

**Not changed.** WP-B deviation 6 (no preview exclusion: the API has no preview flag) and 10 (pause while still resolving takes M2's `livePauseJob`) stand as recorded. WP-A deviations 1–4 and 6–19 stand. Commit 0's adjustments 1–5 stand.

**Unresolved / for the lead's attention.**
- R3 changes who decides "behind" for the cap from the controller's view (paused, or time-shifted and the reader not at the head) to the ring's (paused, or no open reader within one block of the head). They agree whenever a reader is open; with no reader open (during a re-prepare) the cap now fails open. Device check: I-TS-25 with `kick_oldest`.
- R10 touches `:app`'s `PersistedPlaybackSettings` (two mappings), which is outside the listed files but is the only consumer that builds `PlaybackSettings` from the store.

**Result.** `./gradlew :core:test :player:testDebugUnitTest :app:assembleDebug`: **green**. `:core:test` 3,321 (3,316 + 5: `TsRingTimeLimitTest` 4, `RingWriterTest` R13 1) and `stateOnRealNavigator` 358; `:player:testDebugUnitTest` 439 (437 − 2 replaced cap tests + 2 cap tests + R7 splice + R2 storm); none skipped. `:app` `PersistedPlaybackSettingsTest` also passes. No emulator run; nothing committed.

---

## Implementation record — WP-C (2026-10-19)

On the main working tree, uncommitted (the lead commits). No emulator run.

**Build:** `./gradlew :core:test :core:stateOnRealNavigator :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` is **green**:

| Task | Tests |
|---|---|
| `:core:test` | 3,401 (3,321 before) |
| `:core:stateOnRealNavigator` | 358 |
| `:player:testDebugUnitTest` | 439 |
| `:ui:testDebugUnitTest` | 356 (344 before) |
| `:app:testDebugUnitTest` | 65 (56 before) |

None are skipped.

**Files (WP-C's list):**
- `core/.../catchup/archive/TimelineRouterImpl.kt`: replaces the stub. It implements every row of §9.1: live snap (direction-aware), Local (floor or ceil), Provider (`edge − max(target, providerFrom)`), and Clamp (NOTHING_YET, then LIMITED_TODAY, then START). `onGap` follows A-C5.
- `PlaybackControllerImpl.kt`: the R14 one-line switch to `timelineRouter.onGap(...)`, and `@Suppress("unused")` dropped. Nothing else in the controller changed.
- `PlayerStateMachineImpl.kt`, §11.1–§11.6:
  - The `bufferCapable` and `timeShiftCapable` predicates.
  - Play/Pause, PAUSE, RW and TIME_SHIFT take M4's paths when time-shift capable. Otherwise the machine freezes with the reason toast: HLS (Xtream or M3U), Off, no space, or storage. While the buffer state is still unknown, a pause gets no toast and a rewind gets NO_CATCHUP.
  - Start over goes local when the buffer covers the programme on now.
  - Clamp toasts.
  - The REWIND_WINDOW scrub state and the paused header.
  - The hint row on the first 3 focused openings.
  - `SCRUB_FOCUSED_IDLE_MS` after a resuming SELECT.
  - The dim timer, with the waking key consumed.
  - Zap confirm for CH±, Last, and D-pad zap under the ZAP setting, plus `zapNeedsConfirm` for digits.
  - The buffer is reset on an identity change.
  - The live edge comes from the buffer before a session exists.
- `QuickBarModel.kt`: Pause first on every live channel; Back to live first while behind; Start over when the channel has catch-up or the buffer covers the programme start.
- `ScrubModel.kt`: `rewindWindowMs` and `pausedHeader`.
- `PillRules.kt`: unchanged; the text format lives in `PlayerParts`.
- `GuideStateMachineImpl.kt` and `NumberEntryImpl.kt`: the digit confirm. The number stays on screen, auto-commit is disarmed (`holdForConfirm`), OK within 3 s zaps, and the entry closes when the window passes.
- `WpaPages.kt`:
  - Auto comes from `TimeshiftPolicy` and the storage facts.
  - When storage is limited, the value reads "Auto (N min: storage is limited)", and the description shows the capped window and size.
  - Off calls `WpaPageEnv.timeshiftOff()` at once.
  - The Buffer row gets its U-N7 description.
  - New defaulted `WpaPageEnv` members: `timeshiftStorage()` and `timeshiftOff()`.
- `LocalTimelineBackend.kt`: replaces the stub.
  - It maps P = mono + wallMinusMono + skew + correction, and back.
  - Frames are looked up once per identity: the catch-up's frames, or correction 0 with the playlist skew when there is no catch-up.
  - An available buffer is held back until its frames are known.
  - It exposes `localBuffer` (P frame).
- `ArchivePlaybackImpl.kt`, LOCAL source (§11.2):
  - `pauseLive` is a local pause in place.
  - `pause`, `resume`, `seekTo`, `rewindLive` and `startOver` go through the router. Local seeks call `seekLocal(mono, dir)`, show SEEKING, and settle at the new reader's first media.
  - A target older than the buffer, or the `LocalGap` notice, is handed to the provider with M4's live-anchored request.
  - `jumpToLive` calls `jumpToLiveLocal`, shows SEEKING, then IDLE.
  - The LOCAL status carries `source`, `bufferFromMs`, `notSaved`, `pauseRemainingMs` and `savingStop`.
  - A zap, a stop, an owned Error or a lost buffer ends the session.
- UI:
  - `PausedCard.kt`: the dim layer at 35 % and a card drifting among 5 positions inside the safe area.
  - `PlayerParts.kt`: pills "PAUSED · 4:12 behind" and "4:12 BEHIND" at 24 px, drawn in the draw phase.
  - `ScrubBar.kt` and `ScrubBarRenderer.kt`: the window mode (track, saved, watched, "Not saved" with underline and its 120 px label threshold, programme ticks, "Rewind from …", "LIVE" with the live time), the header line and the hint row.
  - `QuickBar` label "Pause" through `PlayerText`.
  - `PlayerViewModel.kt`:
    - `Buffer` events come only from `archive.localBuffer`.
    - GAP_SKIPPED toasts carry the landing time.
    - LiveResumed becomes BACK_TO_LIVE_UNBUFFERED.
    - REWIND_LIMITED_TODAY shows once a day.
    - SLOT_LOST while paused shows the header line, not a toast.
    - Toast values are formatted here: clock, minutes, GB, "your playlist".
    - New `pausedFacts` and `rewindStat` flows.
  - `StatsOverlay.kt`: the rewind line.
  - `strings_player.xml`: argument strings (`player_arg_*`).
  - `strings_m5_enums.xml`: unchanged, because `player_quick_pause` already exists.

**Edits outside the listed files (minimal; for the lead):**
- `SettingsTexts.kt`, `ResourceSettingsTexts.kt` and `strings_settings.xml`: the key `PB_BUFFER_DESC`. Commit 0 left the U-N7 description to the WPs.
- `app/.../settings/WpaPageEnvImpl.kt`: StatFs of `cacheDir`, the `ring-<pid>.bin` size, and Off through `TimeshiftStartup.onTrimMemoryComplete()` (the factory's idle discard).
- `FullScreenHost.kt`: draws `PausedCard` and passes `pausedFacts` and `rewindStat`.
- `PlayerExtras.kt`: two defaulted flows plus `PausedFacts`.
- `ArchivePlaybackImplTest`'s fake controller: two flows, because the impl now reads `localBuffer` and `notices`.

**Tests:**
- `TimelineRouterTest` (7): every §9.1 row, A-C5, frames with −1 h correction and +90 s skew, `TimeRanges` content equality.
- `TimeshiftMachineTest` (16).
- `QuickBarModelTest`: rewritten for the M6 order (+2).
- `player_keys.csv`: +45 rows covering the bufferCapable axis (buffered, buffered with catch-up, HLS, Off, no space), the LOCAL session, the dim key, and the zap confirm. Existing rows were updated for Pause first.
- `PlayerFiveKeyReachabilityTest`: +2 start states (LIVE_BUFFERED, LOCAL_TIME_SHIFT), 19 runs in all.
- `WpaPagesTest` (+4): storage class, the Fire TV example, the limited value, Off deletes, the Buffer description.
- `LocalTimelineBackendTest` (9): the mapping and its inverse, held back until frames, status fields, local seek, settle, clamp versus provider, Back to live, gap hand-off, RW local, zap.
- `TimeshiftUiTest` (6): pill format, header lines including the OK-or-▶ variant, the window renderer (labels, "Not saved" threshold, zero allocation per draw), header and hint bounds, the dim card and its safe-area drift.
- `TimeshiftViewModelTest` (6).

**Deviations:**
1. **Rewind window width.** `LocalBufferInfo` carries no window length. The machine uses the largest saved span this cycle, rounded up to the setting's lengths (15 min, 30 min, 1 h, 2 h). The VM fills "up to N min" and "rewind holds N min" from `controller.localBuffer.windowMs`.
2. **Machine state outside `PlayerUiState`.** Media-key use, dim state and pause start are published as `PlayerStateMachineImpl.timeshiftUi` (`TimeshiftUi`). The VM reads it with a type check.
3. **Per-machine counters.** The hint count and the pause tip are counted per machine, not per install; persisting them needs a prefs channel into the machine.
4. **Pause tip trigger.** PAUSE_TIP shows on the first pause made with OK in the at-live scrub bar. It does not show on the quick bar's ↑ OK, which is the path the tip teaches.
5. **Zap confirm.** "The same key again" is implemented as any single-key zap within 3 s.
6. **Clamp toasts.** The machine raises them through the same pure router; `ArchiveNotice` cannot carry them.
7. **Toast args.** Machine toast args are raw ms values, formatted by the VM.
8. **Five-key proof.** Live with catch-up no longer reaches the at-live scrub bar or `rewindLive` within the LIVE kind, because Pause pauses at once. Those functions are proven from the time-shift start states.
9. **Off is idle-only.** Off deletes the file at once only when no buffered cycle is open, which is the factory's rule. Otherwise the next tune's `prepare(Off)` deletes it.
10. **Settings size.** The description's size uses the capped capacity in GiB, so the Fire TV example reads "1.8 GB".
11. **Gap landing time.** The GAP_SKIPPED landing time is the playhead plus the gap: approximate, at the moment of the notice.
12. **Not done:** U-N2 (the skip animation) and the "next step once per tune" suffix of BACK_TO_LIVE_UNBUFFERED.

**Contract problems:**
- `LocalBufferInfo` lacks `windowMs`, the hold limit and `stoppedAt`.
- `PlayerUiState` has no dim or media-key state.
- `ArchiveNotice` has no clamp or reason values.
- `PlayerToast` args are unspecified (WP-C's convention is in `PlayerText` and the VM).

**Needs the device:**
- The dim card and its drift on a TV.
- Whether the header line (y ≈ 912–944) collides with the preview bubble.
- That `ArchiveVisibility` hides a real local seek's Connecting.
- I-TS-24 (gap hand-off continuity) and I-TS-29 (zap confirm on real remotes, including digits).
- I-TS-30 (↑ OK on TS, HLS and Off).
- Settings Auto and the limited value on Fire TV and Onn storage.
- The "Press OK or ▶" detection.

---

## Implementation record — WP-D (2026-10-19)

This covers the device suite, the debug hooks and the docs. The work is on the main working tree, uncommitted (the lead commits). `./gradlew :app:compileDebugAndroidTestKotlin` is **green**, with no warnings in WP-D's files. Nothing ran on the emulator: the lead runs the suite.

**Files**
- `app/src/androidTest/kotlin/app/thebox/player/TimeShiftBufferTest.kt` holds the cases: 35 methods for I-TS-1…30, with I-TS-20 split into a–c and I-TS-29 into a–d.
- `app/src/androidTest/kotlin/app/thebox/player/TimeShiftBufferSupport.kt` holds two helpers:
  - `TimeShiftBufferBase`, built on M4's `PlayerTestBase`;
  - `TimeShiftPanel`, for the panel's `connection_log` and `/admin/occupy`.
- `app/src/debug/kotlin/app/thebox/debug/GuideDebugReceiver.kt` gets the M6 switches. They are listed below and documented in the class KDoc.

Docs:
- `00-master-plan.md`:
  - §2 v1.1: the ring buffer is struck and pulled into v1, and the new follow-up "HLS DVR-window pause/rewind" is added with the U-Q6 measurement.
  - §3 "Live TS transport": the DVR seam is now the ring.
  - The M6 and v2 rows point here.
- `review/product-review.md` item 25 gets a dated amendment.
- `design/m2-playback-core.md` §3.1.2 gets a dated note under the pause rows.
- `design/m4-player-ux.md` §0 item 5 gets a dated note.
- `decisions/2026-10-15-player-keys-and-quick-bar.md` gets a dated amendment: "Pause" first, the reason being §11.7.
- §17.4 above gets the "manual" tags and the M-TS-1…5 table.

**Debug switches.** They go on the `thebox-debug://guide?…` link. In-process, the suite calls `GuideDebugReceiver.applyTimeshift(uri, store)`, the same code the link runs. This matters because starting the receiver over a playing `MainActivity` would stop playback as Home.

| Switch | Effect |
|---|---|
| `ts_clear=1` | `TimeshiftDebugOverrides.clear()` (applied first) |
| `ts_ring_bytes=N` | ring capacity. `0`, `none` or empty clears it |
| `ts_hold_ms=N` | "Keep recording while paused" limit |
| `ts_cap_bytes=N` | daily write cap. `0` falls back to the policy's cap |
| `ts_free_bytes=N` | free space the sizing rule sees (`0` gives NO_SPACE) |
| `timeshift=auto\|off\|pause_only\|15\|30\|60\|120` | `PlaybackPrefs.timeshift`, **persisted**. `off` also runs the row's idle-file deletion |
| `ts_hold=auto\|10\|30\|60\|full`, `ts_protect=auto\|off` | `timeshiftHold`, `storageProtection` (persisted) |
| `emmc=1` | reads `life_time`, `pre_eol_info`, `name`, `manfid`, `date` and `type` of every `mmcN:XXXX` card under `/sys/class/mmc_host` and of every `/sys/block/mmcblkN/device` (A-N4). It logs `emmc: <path> = <value>` under `GuideDebug` and publishes `GuideDebugReceiver.emmcReport` |

All overrides are read by the factory at the **next tune**.

**How a case runs.** The class baseline is:

```
tools/fakepanel/run-instrumented.sh --synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"' --class app.thebox.player.TimeShiftBufferTest[#method]
```

- Each case POSTs its own panel config after the per-test `/admin/reset`, so the class runs as one, with no extra `--set`.
- The `--set` column gives the line that reproduces a case alone. Append it to the baseline with `#method`. Never add it to a class run: `--set` values survive `/admin/reset`, so every case would get them.
- Every case starts with `ts_clear=1&timeshift=auto&ts_hold=auto&ts_protect=auto` and restores the same in `@After`.

Channels on the synthetic lineup:
- 13 is TS without catch-up, and so are its neighbours 12 and 14.
- 1 is TS with a 7-day xc archive.
- `fx.multiHls` (8) is live HLS without catch-up.

A "N-min ring" is the measured bytes/s × N s. The clip runs at 2 Mbit/s with a 2 s GOP.

Where the cases look:
- the controller's journal: `seek_local`, `segment`, `ring_boundary`, `saving_stopped`, `timeshift_end`, `STALL`, `REBUFFER`, `FIRST_FRAME`;
- `controller.localBuffer` and `ArchivePlayback.status` (source LOCAL);
- `controller.notices`;
- the panel's `connection_log` and `timeshift_requests`;
- toasts on screen, through UiAutomator `textContains` on the §13 strings;
- `/proc/self/io` and `/proc/self/task`, which are the app's own, since the suite runs in-process;
- the ring file.

Emulator margins: `emuSlack` is +2 s on timing asserts. The hardware gates (seek P50 ≤ 600 ms, the 5 % zap regression, writer CPU ≤ 2 %) are asserted on hardware only and logged on the emulator.

| Case | Method | `--set` for a single run | Asserts |
|---|---|---|---|
| I-TS-1 | `its01_pauseSixtySecondsResumesInPlace` | — | PAUSED 60 s ± 2 s; same tuneId; no new panel connection; the mono playhead after the resume is within 1.5 s of the pause point (stands in for the burnt-in counter); BEHIND 60 s after the resume |
| I-TS-2 | `its02_rewindTwoMinutesAfterThreeMinutes` | — | 4 × RW after 3 min gives BEHIND 2:00 ± 3 s; seek latencies logged, P50 ≤ 600 ms on hardware |
| I-TS-3 | `its03_backToLiveFromTwoMinutesBehind` | — | Back to live is first in the quick bar; IDLE and LIVE; behind ≤ the latency at tune + 1 s (+1 s margin); no new connection |
| I-TS-4 | `its04_holdLimitReleasesTheOnlySlot` | `--set max_connections=1` | 1 connection 15 s into the pause; occupy → 409; HOLD_LIMIT after 28–36 s; 0 connections; occupy → 200; `savingStop` HOLD_LIMIT |
| I-TS-5 | `its05_pauseUntilTheOneMinuteRingIsFull` | — | FULL_WHILE_PAUSED; connection released; `savingStop` FULL; after the resume, the GAP_SKIPPED_PAUSED toast and a `GapSkipped(stream=false)` notice |
| I-TS-6 | `its06_serverClosesWhilePausedSpliceWithoutSegments` | `--set 'faults={"close_every":35}'` | ≥ 2 reconnects while paused, no non-FIRST segment, still saving; after the resume, 60 s with no boundary and no stall, BEHIND 2:00 ± 3 s |
| I-TS-7 | `its07_resetReconnectWhileBehindIsABoundary` | `--set 'faults={"close_every":40,"reconnect_mode":"reset"}'` | ≥ 1 `ring_boundary`; never an error card; BEHIND grows ≤ 1 s per boundary + 2 s; pill BEHIND |
| I-TS-8 | `its08_inlineJumpIsABoundaryWithoutStallReprepare` | `--set 'faults={"inline_jump_after":[40,5000]}'` | an `INLINE_PCR_JUMP` segment; a boundary hand-off; no STALL event; never Stalled or Error |
| I-TS-9 | `its09_zapWhileBehindDiscardsTheBuffer` | — | `timeshift_end`; the new buffer holds < 10 s; RW gives REWIND_NOTHING_YET; archive IDLE |
| I-TS-10 | `its10_archiveChannelPausesLocallyAndRewindsBeyondToTheProvider` | — | no timeshift request for a 2-min pause or the resume; a target 10 min back gives exactly 1 logical provider request; archive tune; `timeshift_end`; ring file gone |
| I-TS-11 | `its11_hlsWithoutCatchupFreezesWithTheReason` | — | not buffered; Pause first; ↑ OK freezes with the HLS toast; Stopped(LIVE_PAUSE_TIMEOUT) within 15 s; resume shows BACK_TO_LIVE_UNBUFFERED |
| I-TS-12 | `its12_lowSpaceFreezesWithTheReason` | — (`ts_free_bytes=0`) | `off` NO_SPACE; PAUSE_NO_REWIND_SPACE; M2 freeze and release; no ring file |
| I-TS-13 | `its13_homeWhilePausedClearsAndToastsOnce` | — | Stopped(BACKGROUND); file deleted; back to live on return; `LeftAppCleared` exactly once; its toast shown |
| I-TS-14 | `its14_ringFileIsNamedForThisProcess` | — | in-process half only: one file, `ring-<pid>.bin`. Kill/relaunch is M-TS-1 |
| I-TS-15 | `its15_oneMinuteRingFourMinutesBehind` | — | 4 min of play behind with a full 1-min ring: `bufferFrom` advances > 2 min; no stall, no position lost, no boundary; behind steady ± 3 s |
| I-TS-16 | `its16_ptsWrapKeepsBehindAndSeeks` | `--set 'faults={"pts_offset":8584534592}'` (wraps 60 s in) | the saved span grows 1:1 across the wrap (± 3 s); RW −30 s and −90 s (across the wrap) land ± 3 s; 60 s of play across the wrap with no boundary |
| I-TS-17 | `its17_stripRaiSeeksReachAFrame` | `--set 'faults={"strip_rai":true}'` | 3 seeks (RW, RW, FF) after 45 s each reach a first frame ≤ 1 s (emulator ≤ 2.5 s) |
| I-TS-18 | `its18_sparsePsiAndPmtChange` | `--set 'faults={"psi_every_s":5,"pmt_change_after":60}'` | a `PSI_CHANGE` segment; seeks ≤ 1 s (emulator 2.5 s); no STALL; an audio track after the change; no error |
| I-TS-19 | `its19_reprepareWhileBehindKeepsThePosition` | — | `reapplySettings` and the decoder override re-prepare while behind with BEHIND ± 3 s; natural stall verdicts in 2 min are logged and, if any, checked the same way |
| I-TS-20a | `its20a_offDeletesTheFile` | — | Off then a zap gives `SETTING_OFF`; the file is deleted; PAUSE_NO_REWIND_OFF |
| I-TS-20b | `its20b_pauseOnlyWritesNothingAtLive` | — | process `wchar` over 30 s: PERSIST ≥ half the stream, PAUSE_ONLY at live < 10 %, PAUSE_ONLY while paused ≥ half |
| I-TS-20c | `its20c_dailyCapLimitsRewind` | — (`ts_cap_bytes=50000000`) | after 52 MB saved, the next tune is PAUSE_ONLY with `limitedToday`; RW gives REWIND_LIMITED_TODAY |
| I-TS-21 | `its21_soakTwoHoursBehind` | — plus Gradle `-Pandroid.testInstrumentationRunnerArguments.soak=1` (`soakMinutes=N`) | 15-min ring, 10 min behind, 2 h of play: heap growth ≤ 8 MiB after warm-up; file = capacity; per-minute writer CPU, native heap and PSI logged; CPU ≤ 2 % on hardware |
| I-TS-22 | `its22_zapTimeBufferOnVersusOff` | — | 50 CH+ zaps from channel 100, buffer on, then Off: first-frame P50/P90 logged, ≤ 5 % regression on hardware |
| I-TS-23 | `its23_screenStaysOnWhilePausedAndDims` | — (sets and restores `screen_off_timeout` 60 s) | `keepScreenOn`; never non-interactive in 8 min; dimmed at 2:05; the first key only wakes; same tuneId; BEHIND 8:00 ± 3 s |
| I-TS-24 | `its24_archiveGapGoesToTheProviderWithoutToast` | — | 2-min ring on channel 1, paused 4 min: FULL_WHILE_PAUSED; after the resume, the source becomes PROVIDER at the gap with exactly 1 logical request; no "Skipped" toast; BEHIND 4:00 ± 10 s |
| I-TS-25 | `its25_kickOldestWhilePausedIsSlotLost` | `--set max_connections=1` | repeated `occupy?mode=kick_oldest` until SLOT_LOST; ≤ 2 client reconnects; 1 occupier open and 0 client streams; `savingStop` SLOT_LOST; the saved part plays after the resume |
| I-TS-26 | `its26_backToLiveRebuffersNoMoreThanAFreshTune` | — | REBUFFER count in 5 min after Back to live ≤ a fresh tune's 5 min (+1 on the emulator) |
| I-TS-27 | `its27_rewindTenMinutesThenPlayNoDiskTooSlow` | — | 11 min watched, 20 × RW (≥ 9 min behind), 60 s of play: no DISK_TOO_SLOW, no stall |
| I-TS-28 | `its28_deletedRingFileIsStorageEvicted` | — | the file is deleted while behind; STORAGE_EVICTED (also §22's `st_nlink` claim); REWIND_STOPPED_STORAGE; no fd left on the deleted file; playback continues 20 s with no ERROR event |
| I-TS-29a | `its29a_zapConfirmChannelUp` | — | paused 2:05: CH+ gives ZAP_CONFIRM, no zap, still paused; CH+ again zaps |
| I-TS-29b | `its29b_zapConfirmLast` | — | same with Last (`KEYCODE_LAST_CHANNEL`); lands on the previous channel (12) |
| I-TS-29c | `its29c_zapConfirmDigits` | — | digits "12" give ZAP_CONFIRM_NUMBER with no zap; OK zaps |
| I-TS-29d | `its29d_guideZapsAtOnce` | — | Back to the guide, ↓ OK zaps at once; no ZAP_CONFIRM toast |
| I-TS-30 | `its30_upOkPausesOnTsHlsAndOff` | — | ↑ OK with Pause first on each of TS (LOCAL PAUSED, focused scrub bar), HLS (freeze + toast) and Off (freeze + toast) |
| regressions | M4 `TimeShiftTest`, `FiveKeyJourneyTest`, M2–M5 classes | — | the two M4 classes need `timeshift=off` (see "For the lead", item 1) |

Manual steps: M-TS-1…5 in §17.4.

**Deviations, with reasons**
1. **I-TS-6 uses `close_every` 35, not 15.** Fifteen seconds means 4 reconnects a minute while paused. A-C4 (`PAUSED_RECONNECTS_MAX` = 2 per 60 s) correctly turns that into SLOT_LOST, which is I-TS-25's case. 35 s stays under the cap.
2. **I-TS-10 commits the 10-min target through `ArchivePlayback.seekTo`,** the call a scrub commit makes, rather than 20 RW presses. The PROGRAMME-scope bar of a catch-up channel can clamp ← at the programme start, which would land inside the buffer. The buffer is whatever was saved since the tune (≈ 2.5 min), not a 5-min override: the target only has to be older than the buffer.
3. **I-TS-15 reads as "4 min of play behind live with a full 1-min ring".** "4 min behind" in a 1-min ring is only reachable across a gap.
4. **I-TS-19 has no `DECODER_STUCK` injection.** `:player` has no debug hook for it, and that file is outside WP-D's list. The case forces the re-prepare through two real `launchSession` call sites (`reapplySettings` and the decoder override, A-S6) and checks any natural stall verdicts the same way. A `PlayerDebugHooks.injectStall(kind)` would make it exact.
5. **I-TS-1's "continues from the pause frame" uses the mono playhead,** not a burnt-in counter, because testsrc2 has none that the suite can read.
6. **I-TS-14 is half automated.** The instrumentation shares the app's process, so it cannot kill it. The kill/relaunch half is M-TS-1.
7. **Unobservable items are logged, not asserted.** The pool-wait counter (I-TS-27) and LMK (I-TS-21) are not exposed. DISK_TOO_SLOW, the pool's timeout, stands in for the first; M-TS-4 covers the second.

**For the lead (outside WP-D's files)**
1. **Regression classes need the buffer Off.** The setting is persisted and Auto is the default, so the M4 classes that pause live now pause LOCAL instead of `PAUSED_LIVE`: `TimeShiftTest` (all four cases) and `FiveKeyJourneyTest` (quick-bar time-shift → `PAUSED_LIVE`). Proposed fix:
   - append `timeshift=off` to their `playerSwitches`;
   - add `ts_clear=1&timeshift=auto` to `PlayerTestBase.PLAYER_DEFAULTS`, so every other player class starts from Auto (the last occurrence wins).

   `TimeShiftBufferTest` restores Auto itself.
2. **M5 §3.3.13 amendment ("On launch" demoted to ADVANCED, lead ruling 1) is not written.** §18 lists it for WP-D, but `design/m5-setup-settings.md` was not in this assignment's files.
3. **Two interactions to watch on the first run:**
   - **Dim versus zap confirm.** `PAUSED_DIM_AFTER_MS` = `ZAP_CONFIRM_AFTER_PAUSE_MS` = 2 min, so after a 2-min pause the first CH+ only wakes the picture (consumed, §11.5). The confirm toast comes on the second press and the zap on the third. I-TS-29 wakes the picture with BACK first. Whether a viewer should need three presses is a UX question.
   - **Unfocused scrub bar.** I-TS-29 and I-TS-30 assume the scrub bar opened unfocused by a media-key pause closes with BACK.
4. **Long cases.** The longest are I-TS-21 (2 h 15 min, opt-in), I-TS-27 (≈ 13 min), I-TS-26 (≈ 11 min), I-TS-23 (≈ 9 min), I-TS-20c (≈ 4–7 min) and each I-TS-29 method (≈ 2.5 min). The whole class without the soak is about 90 min.
5. **Fake panel.** No README change was needed: every key the suite uses is already documented under "Faults" and "M5 additions".

---

## Implementation record — fix pass, playback half (2026-10-19)

This pass applies `docs/review/2026-10-19-timeshift-review-playback.md` B1–B3 and S1–S9, plus the controller side of the UX review's B2, S1 and S7. The work is on the main working tree, uncommitted (the lead commits). No emulator was used. The review file's "Fix pass — playback (2026-10-19)" section has the item-by-item list.

**Build:** `./gradlew :core:test --tests 'app.thebox.core.playback.timeshift.*' :player:testDebugUnitTest :app:assembleDebug` is **green**.
- Time-shift tests in `:core`: 90 (84 before).
- `:player`: 462 (439 before).
- Nothing skipped.

**Contract changes (additive or shape-only, all in this pass's files):**
- `TimeshiftContracts.kt`:
  - new `enum ReconnectCause { WATCHDOG, SERVER_CLOSED, RESET, REFUSED, NETWORK }`;
  - `RingWriter.reconnectAllowed(cause, firstOfLoss)` replaces `reconnectAllowed()`.
- `Seams.kt`:
  - `WriterHooks.reconnectAllowed` takes `(ReconnectCause, Boolean)`;
  - `RingOpen` gains `firstRap: Boolean = false` (S7);
  - `TimeshiftSessionFactory` gains `abandon(grant, discardFile)` with a no-op default (S3).
- `TsRingImpl` gains the public `storageUsable` (S4).
- `TimeshiftConstants` gains `PUMP_JOIN_ZAP_MS` (100), `GAP_HANDOFF_TIMEOUT_MS` (5,000), `WRITER_STORM_REPEAT_MS` (300,000) and `FIRST_RAP_WAIT_MS` (10,000).
- `PlaybackApi.kt`:
  - `PlaybackController.skipGapLocal(): Boolean`, default false (UX-B2);
  - `LocalBufferState` gains `holdMs: Long? = null` and `cycleId: Long = 0` (UX-S1).

  `LocalBufferState.windowMs` is now stable for a session: `min(length, capacity ÷ ASSUMED_BYTES_PER_SEC)`, never the measured rate. `stoppedAtMonoMs` already existed.
- **For the second pass (not touched here):** add `LocalBufferInfo.windowMs`, `holdMs`, `stoppedAtMs` and `cycleId` in `core/.../full/PlayerContracts.kt`, and fill them in `LocalTimelineBackend.publish` from these `LocalBufferState` fields (`stoppedAtMs` is `stoppedAtMonoMs` converted to the P frame). Also have `ArchivePlaybackImpl` call `controller.skipGapLocal()` from the early return in `onLocalGap` and from both failure paths in `providerFromLocal`.

**Design changes:**
- **§7.1, §7.4 (A-C2 replaced):** before the first byte, a writer-scoped decision and `selectServer` restart the cycle's one writer in place. A second session on the same grant is never started. `Plan.recreateTimeshift` and `keepGrant` are gone.
- **§7.2, §4.5 (B3):** the 1 Hz poll sets the PLAYHEAD pin only while paused or time-shifted. At live it clears the pin once a reader is open.
- **§7.4 (S1):**
  - "At live" is now playhead-based: `newest − playhead ≤ liveLanding + GAP_MIN_MS`. The loader cursor gates only the §5.2 telemetry.
  - Once bytes were saved, a writer `Fail` is always stashed.
- **§7.4, §14 "Segment storm" (S2):** a writer storm restarts the writer with a fresh resolve. A repeat within 5 min is cycle-scoped: at live it relaunches now, behind live it is deferred. It never reaches the ladder.
- **UX-S7:** after a cycle-scoped close (`runDeferred`, a cycle-scoped decision at the head, a storm repeat), the relaunch decides buffering again instead of setting `NOT_TS`. A TS source is buffered again, and an HLS one reports `NOT_TS` with `hls`.
- **§5.1 (S7):** the cycle's first reader waits, for at most 10 s, for the generation's first RAP and opens there.
- **§8.3 (S3, S4):** the factory never discards while a prepare is in flight, a grant is outstanding or a session is open. A ring whose storage broke or whose file was unlinked is rebuilt.

**Deviations, with reasons:**
1. **The storm repeat relaunches buffered, not unbuffered.** The relaunch re-decides buffering (UX-S7), so a TS channel gets a fresh ring. An unbuffered relaunch would need a `BufferOff` reason. `NOT_TS` would be wrong and gives no pause toast, and adding an enum value breaks the exhaustive `when`s in `ui/` and `app/`. Neither path reaches L25 or L27.
2. **S8 has no thread interrupt.** `LiveTsDataSource`'s reconnect sleep now waits on a monitor that `terminate()` notifies, so the up-to-1 s wait ends at once. Interrupting the pump could leave the interrupt flag set into the next upstream's lease wait and fail its open. The remaining block on the zap path is the broker's lease wait (at most 300 ms), which the generation fence covers.
3. **S9 is diagnostics only, as instructed.** The watchdog logs the silence, the connection's age, the bytes and the rate, and the writer's `NO_BYTES` event detail carries `gap=` and `age=`. Thresholds are unchanged, and the adaptive `noBytesTimeoutMs` is not applied.
4. **"Behind" for the writer's own cap is still the loader cursor or paused** (`anyReaderAtHead`, R3). The writer cannot see the playhead. S1's playhead rule applies to the controller's SLOT_LOST, deferral and stash decisions.
5. **The S7 test is at the data-source level** (`RingDataSourceRealRingTest`: a mid-GOP join opens at the first RAP). The controller-level origin follows, because `resolveOrigin` already takes the first RAP at or after the tail.

**Later items done on the way:** L1 (`anyReaderAtHead` takes the lock), L3 (the segment listener is cleared only by its owner) and L4 (the index sink is fenced by generation). L2 and L5–L17 remain.


---

## Implementation record — fix pass, product half (2026-10-19)

This pass applies `docs/review/2026-10-19-timeshift-review-ux.md` B1, B2 (the `ArchivePlaybackImpl` side), S1–S9, S11, §7's "now" rows and the WP-D dim/zap question, plus what the playback half left (the `LocalBufferInfo` fields, the three `skipGapLocal()` calls, the NOT_TS pause toast). The work is on the main working tree, uncommitted (the lead commits). No emulator was used. The review file's "Fix pass — product (2026-10-19)" section has the item-by-item list.

**Build:** `./gradlew :core:test :core:stateOnRealNavigator :ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` is **green**: `:core:test` 3,442, `:core:stateOnRealNavigator` 358, `:ui` 360, `:app` 69, nothing skipped.

**Contract changes (additive, defaults keep callers compiling):**
- `PlayerContracts.kt`:
  - `LocalBufferInfo` gains `windowMs: Long = 0`, `holdMs: Long? = null`, `stoppedAtMs: Long? = null` (P frame) and `cycleId: Long = 0`;
  - new `PausedUi(pausedSinceMs, dimmed, dimSinceMs, mediaKeys, header)` and `PlayerUiState.paused: PausedUi? = null`;
  - `PlayerToast` KDoc states the argument convention (raw ms, display-frame times, "" = unnamed playlist, values joined by the new top-level `PLAYER_TOAST_ARG_SEP`; per-kind order listed); appended `PAUSE_NO_REWIND_FORMAT`, `PAUSE_NO_REWIND_STORAGE`.
- `PlayerStateMachineImpl.timeshiftUi` / `TimeshiftUi` are `@Deprecated`, derived from the same state, and kept only because the device suite (`TimeShiftBufferSupport.tsUi()`) reads them and was outside this pass's files. **For the lead:** move that helper to `ui.value.paused` and delete the shim.
- `ScrubModel.rewindWindowMs` / `REWIND_WINDOWS_MS` are removed (the window comes from the buffer).
- `ArchivePlaybackImpl.providerFromLocal` gains an internal `onFail` callback.

**Design changes (recorded here and inline):**
- **§9.1, router:** a target inside `LocalWindow.gaps` goes to the provider at the target on a catch-up channel; without catch-up to the gap's end (ceil), or live when the gap reaches the edge, with GAP_SKIPPED (PAUSED when the range is ≥ `GAP_PROVIDER_MIN_MS`, else STREAM). If that provider hand-off cannot be built, the session seeks to the gap's end.
- **§9.1 / §11.2, gap hand-off:** every hand-off that cannot happen (no LOCAL session, context failed, no request built) calls `controller.skipGapLocal()`.
- **§11.1:** OK on bare video while a time-shift is paused (LOCAL or provider) continues; playing, it opens the channel list. FF on live with an unknown buffer state is silent; RW and TIME_SHIFT say REWIND_NOTHING_YET. NOT_TS and STORAGE_ERROR have their own pause toasts. NO_CATCHUP never answers a pause or a rewind.
- **§11.4:** the REWIND_WINDOW width is `LocalBufferInfo.windowMs`, so the bar never rescales. Window-mode text: live time 24 px, programme title and centre time 22 px; the title stops 16 px before the live time.
- **§11.5:** the pause start survives a seek while paused (zap confirm, dim base); "after N min" is the hold limit in force; the dim card's body is the full header line on up to three lines; the zap key that wakes the picture also asks the confirm.
- **§13:** the S5 paths, the S6 reason/step split, the S8 REWIND_START variant and the two new rows are in the table above.
- **S9:** a new `cycleId` restarts the tune clock in the machine and in `LocalTimelineBackend`; a null snapshot clears the backend's frames.

**Deviations, with reasons:**
1. **The five-key `PAUSED_LOCAL` start state is built with QUICK_MENU, OK, Back** rather than ↑ OK Back, so the same setup works under "Change channel" and "Channel list" (where ↑ zaps or opens the list). The search from it uses the five keys only.
2. **The GAP_SKIPPED kind of a scrub into a gap is a length heuristic** (§9.1's own 30 s split). `TimeRanges` carries no cause.
3. **The unknown-buffer pause stays a silent plain pause.** See the review's deferred list.
4. **`timeshiftUi` kept as a deprecated shim** (above), since the device suite was out of scope.

**Needs the device:** the 22–24 px window text against the paused header line (y ≈ 912–944); the three-line dim card on a TV; the B1 resume on Fire TV and Onn remotes; I-TS-29 with the wake-and-ask press.

---

## Integration pass (2026-10-19)

The lead's first device run had `TimeShiftBufferTest` at 21 of 35, with `FiveKeyJourneyTest` and `SmokeAndZapTest` I-2 failing. This pass reran every failing case alone on `TheBox_TV_API30` (emulator-5554) against the fake panel, classified each failure, fixed it, then ran the regression classes and the whole class twice. The work is on the main working tree, uncommitted (the lead commits). Logs and JUnit XML for every run are under the session scratchpad, in `device/<case>/` (`gradle.txt`, `logcat-full.txt`, `logcat.txt`, the XML).

**Result.** `TimeShiftBufferTest` stands at **34 of 35** on the second full class run, after 31 of 35 on the first. In the second run I-TS-21 logged "not run": it is opt-in and was not run in this pass. The one failure is **I-TS-6**, the first splice crossed after the resume (see the I-TS-6 class-run row and lead item 1). Every case also passes when run alone.

The regression classes all pass when run alone:
- `FiveKeyJourneyTest` 1/1;
- `SmokeAndZapTest` 7/7;
- `TimeShiftTest` 5/5;
- `InfoBarTest` 2/2.

### Per case

| Case | Lead's run | Now | Verdict | Root cause | Fix |
|---|---|---|---|---|---|
| I-2 (`SmokeAndZapTest`) | fail | pass | **product** (WP-A) | `TsIndexerImpl`: the spacing rule (one entry per `INDEX_MIN_SPACING_MS`) counted time-only anchors. With a GOP of exactly `INDEX_MAX_GAP_MS` (2 s, the fake panel's clips and many real channels), the anchor fires on the PCR packet just before the keyframe. Its estimated PTS sits 0–400 ms before the keyframe's, so the RAP was dropped. The 2 s cadence then locks, and every later RAP is dropped too: the whole connection has no RAP. The first reader waited `FIRST_RAP_WAIT_MS` (10 s) for one, so the zap had no first media in the test's 10 s (`RingDataSource: no RAP within 10000 ms`, tune 37) | The spacing rule counts RAPs only (`hasRapEntry`/`lastRapPts`), and an anchor never suppresses a RAP. New `TsIndexerTest.aGopOfExactlyTheAnchorGapKeepsEveryRap` (25 and 50 fps, 2 s GOP): it failed before the fix with 1 RAP for 10 keyframes and passes after it. `SyntheticTs` gains `pcrPacketFirst`, an adaptation-only PCR packet before each PES, as ffmpeg writes them |
| I-TS-9 | fail | pass | test | The test asserted archive IDLE *after* the RW. On a channel without catch-up, §9.1's "older than the buffer → Clamp to `localFrom`" lands the playhead on the new buffer's oldest point. That is a LOCAL session of the **new** channel, with REWIND_NOTHING_YET | IDLE is asserted right after the zap, before the RW. After the RW the archive is IDLE or a LOCAL session of the new channel |
| I-TS-20a | fail | pass | **product** (WP-B, WP-C) | Two faults. (1) The Off path decides in `runSession` (`tsDecided = true`), so the post-resolve publish is skipped. The tune's first publish (`off = null`) was the only one, and SETTING_OFF was never published. (2) The machine drops its buffer when the tuned identity changes (`onTuned`). `PlayerViewModel.channelOf` suspends, so the new cycle's snapshot can reach the machine *before* that `Tuned`, and is then wiped. An unbuffered cycle never republishes (a StateFlow), so a pause on an Off channel was the silent "unknown" plain pause with no reason toast | (1) `PlaybackControllerImpl.runSession` publishes right after setting `tsOff = SETTING_OFF`. (2) `PlayerViewModel.forwardFeeds` re-sends `archive.localBuffer` after a `Tuned` whose identity changed, when `controller.localBuffer` is that identity's |
| I-TS-30 | fail | pass | product | The Off leg is the same as I-TS-20a | as I-TS-20a |
| `FiveKeyJourneyTest` | fail | pass | test (M6 contract change) + one flake | The journey assumed M4's quick-bar item: OK opened the bar at live, and a second OK paused. §11.3 (U-B3) made the item "Pause", which pauses at once and opens the focused bar at the pause point. One run then lost a ↑ key while the EPG REBUILD (generation 2) finished in the background. That run is logged, and two reruns passed | Steps 1–2 are now "quick bar → Pause → PAUSED_LIVE, focused scrub bar" (buffer Off, catch-up channel). The flake is recorded, not changed |
| I-TS-29a/b/d | fail | pass | test | `pausedTwoMinutesOn` polled `player.zapNeedsConfirm(now)` as if it were a query. It is the number entry's commit hook: it holds a zap back (toast) and arms the confirm window. The case's own first CH+ therefore counted as the confirming press and zapped | The case waits on the pure fact instead: `PausedUi.pausedSinceMs` is at least `ZAP_CONFIRM_AFTER_PAUSE_MS` ago |
| I-TS-6, I-TS-10, I-TS-24 | fail | pass | test | Each pauses 2 min or more. §11.5 dims the frame at `PAUSED_DIM_AFTER_MS` (2 min), and the next key only wakes the picture, so `resumeByKey`'s single ▶ timed out | `resumeByKey` wakes a dimmed picture first (BACK, consumed). If the pause age is within 1 s of the dim at the press, it gets one more press, because the dim can land between the check and the press. Any other press that does not resume still fails |
| I-TS-15 | fail | pass | **environment (fake panel)** | Measured over 240 s: the playhead advanced 30.64 s per 30.00 s, about 2.1 % fast, so "behind" closed by 5.2 s, against ±3 s + 2 s. There was no stall, no boundary and no lost position. The cause is the synthetic clip's loop. Its audio PTS steps +213 ms at every 10 s loop; logcat shows `Unexpected audio track timestamp discontinuity: expected …085329, got …298666` every ~10.03 s. The audio sink resyncs forward each time: 213 ms per 10.03 s is the 2.1 %. The player has no speed control on the ring source (no `LiveConfiguration`) | A documented fake-panel tolerance: ±3 s + 2.5 % of the 240 s played, plus `emuSlack` on the emulator. It applies on hardware against the fake panel too. A real stream does not loop |
| I-TS-21 | fail | not run (opt-in) | tooling | AGP's connected runner reports an `assumeTrue` violation as a failed test and fails the Gradle task | Without `soak=1` the case logs "not run (opt-in)" and returns. It was not run in this pass (2 h 15 min) |
| I-TS-22 | fail | pass | test | The zap history is a 50-entry ring (`DiagConstants.ZAP_HISTORY`), so "size grew" never became true at the 50th zap | A new zap is a new last `tuneId` |
| I-TS-4 | fail | pass | test (class order) | In the class run I-TS-11 (HLS) runs first, and the app's launch resumes that HLS channel. The panel counts an HLS session against `max_connections` until `hls_timeout` (20 s) after the last request, so the TS tune got three 403s, then L4 `CONNECTION_LIMIT` | The case POSTs `hls_timeout: 1` with `max_connections: 1`. I-TS-25 does the same |
| I-TS-20c | fail | pass | test (class order) | The daily counter counts every case's writes today. In a class run the 50 MB debug cap is already used up before the case starts, so the first tune is PAUSE_ONLY and saving 52 MB never happens (420 s timeout) | When the first tune is already PAUSE_ONLY and `limitedToday`, the fill step is skipped. The PAUSE_ONLY, `limitedToday` and REWIND_LIMITED_TODAY assertions are unchanged |
| I-TS-16 | pass | pass | test + fake panel (seen in class run 1) | Two effects. The mono-continuity baseline was taken at the tune (14 KiB saved), so it counted the panel's 1 s connect burst as growth: 103.8 s in 100.0 s. And the panel's live PCR advances **1.0276 × wall time**: a direct read of `/live/test/test/13.ts` over 65 s gave the same ratio with and without `pts_offset`, so it is not the wrap. A 100 s window therefore saves about 102.8 s, at the edge of ±3 s (102.9 s in class run 2) | The baseline is taken 10 s in, still before the wrap at 60 s, and the rewinds still cross the wrap. The tolerance is ±3 s + 3 % of the elapsed time (fake panel). A wrap fault would be a 2³³ step or a segment. Alone after the fix: 102.0 s and 101.96 s in 100.0 s |
| I-TS-6 (both class runs) | — | **fail in class runs, pass alone (×3)** | **open** (lead item 1) | The failure is deterministic in class order, with identical numbers in both runs. 30.4 s after the resume, the playhead reaches the media time of the **first splice**: the panel's forced close 35 s into the connection, 30 s after the pause point. AudioFlinger logs `BUFFER TIMEOUT: remove(…) from active list`, then `AudioTrack … disabled due to previous underrun`. Rendering stops: video queued 2,588 ms, rendered 867/869 ms, 15.4 s buffered. Five seconds later the readiness detector reports `DECODER_STUCK`, and L27a re-prepares in 0.2–0.3 s. There was no segment and no boundary, and the panel's connection log shows whole-packet byte counts with `reconnect_mode: continue`. Alone, the same splice plays through. The difference is the clip phase at the splice, which depends on the earlier connections on stream 13 since `/admin/reset`. That points at the splice landing near the clip's +213 ms audio loop step (see I-TS-15) | None: no assertion was loosened. Recovery worked, but "no stall across a continuous splice" is what the design requires |

The 21 cases that passed in the lead's run passed in both class runs here, except I-TS-16 in class run 1 (row above).

### Other changes in this pass

- **Writer end of session, generation-checked** (`TsRingImpl.stopRecordingFor`, `RingWriterImpl` pump exit). A closing pump checked "the generation is still mine" and then stopped the ring, in two steps. A `reset()` for the next zap between them would end the new generation (SESSION_END), and that cycle would never save. This was found while diagnosing I-2; no case showed it. The stop is now one step under the ring's lock. Test: `RingFenceTest.aStaleSessionEndNeverStopsTheNewGeneration`.
- **Frames lookup off the zap** (`LocalTimelineBackend`). The catch-up-target and time-base reads (library.db) now run once the buffer is available, not at every tune. An Off cycle never looks them up.
- **Test hooks for the zap gate** (androidTest only):
  - `PanelTestBase` reads `-e timeshift off` (M2 suite with the buffer Off);
  - `ZapThroughGuideTest` reads `-e timeshift off|auto` (the M3 I-17 journey).

**Contracts:** no change. `stopRecordingFor` is internal to `TsRingImpl`. `SyntheticTs.pcrPacketFirst` is a test-fixture parameter with a default.

**Unit tests** (all green):
- `:core:test --tests 'app.thebox.core.playback.timeshift.*'`: 92, previously 90;
- `:player:testDebugUnitTest`: 462;
- `:ui:testDebugUnitTest`: 360;
- `:app:testDebugUnitTest`: 69.

### Emulator numbers (not gate-relevant; §15's gates are hardware gates)

| What | Emulator |
|---|---|
| Seek, commit → first frame | class run 1: I-TS-2 [294, 269] ms, I-TS-17 (no RAI) [303, 340, 254], I-TS-18 (PMT change) [283, 218]; class run 2: [284, 212], [306, 286, 226], [288, 337]. P50 ≈ 285 ms, worst 340 ms (gate P50 ≤ 600 ms, P90 ≤ 1,000 ms) |
| Back to live (I-TS-3) | 2.4 s; behind 4.1 s and 3.6 s after it (latency at tune 0.26 s and 0.54 s) |
| Rebuffers in 5 min (I-TS-26) | 0 after Back to live, 0 for a fresh tune |
| I-TS-22, 50 CH+ zaps, first frame P50 / P90 | class run 1: on 647 / 694 ms, off 998 / 1,098 ms; class run 2: on 631 / 699, off 992 / 1,070; alone: on 695 / 935, off 970 / 1,087. On is faster here: from first byte, On needs 425 ms and Off 840 ms, because the reader starts at the first RAP (S7) and the unbuffered extractor waits for the next IDR |
| M3 I-17 journey, 20 CH+ in FULL, P50 / P90 | Auto 874 / 928 and 918 / 976; Off 798 / 870, 806 / 969, 892 / 985, 811 / 932, 840 / 897, 786 / 979, 816 / 896. On is +9–15 % at P50 on this journey |
| M2 I-2, one connection, 40 zaps, P50 / P90 | Auto 803 / 1,441 (alone) and 703 / 1,292 (class); Off 800 / 1,442 |
| Writer CPU | not logged: it is logged only by I-TS-21, which was not run |
| Fake-panel clocks | live PCR 1.0276 × wall (direct read, 65 s); playback +2.1 % from the clip's +213 ms audio step per loop (I-TS-15) |

**Zap gate, kept honest** (§15: Off unchanged, On within 5 %).

*On versus Off:*
- The two journeys disagree on the emulator. I-TS-22 has On 35 % faster; I-17 has On 9–15 % slower.
- Neither is a gate measurement; the gate is a hardware gate.

*Off versus before the feature.* A build of `70da111` (the last commit) in a scratch worktree, on the same emulator and the same day, gave I-17 FULL CH+ P50 745 / 715 / 695 / 691 ms. The current tree with the buffer Off gives 786–892 ms, about +16 %. The extra time is mostly before any network: `resolve` median 141 → 191–201 ms, then first media 715 → 816–839 ms.

*The cause is not the M6 Off path:*
- With the M6 Off-path work stripped out, the same journey gave 804 / 845 / 816 ms, with `resolve` 191 ms. The stripped work is the tune publish, the reason publish and `launchPrepareForOff`; this was a temporary experiment and is reverted.
- `70da111` also lacks the rest of the uncommitted tree: the 2026-10-17/18 owner and device-finding fixes, plus the guide, settings and player UI edits. That is about 148 files outside M6, so this A/B cannot isolate M6.

### Lead/owner items

1. **I-TS-6 in class order.**
   - **What happens.** The first reconnect splice, played 2 min behind live, ends in an audio underrun and then `DECODER_STUCK`; L27a recovers it in 0.3 s.
   - **Next steps.** Reproduce alone by shifting the clip phase (one extra connection on stream 13 before the case) or with a loop-continuous clip (item 6). Then decide between two readings: a fake-panel artifact (the +213 ms audio loop step at the splice), or a player issue at a splice with an audio gap. A real provider's splice can carry an audio gap too, so the second reading matters.
   - **Evidence.** In `device/class_full2/` and `device/class_full/`, around `its06`.
2. **Zap time, Off versus 70da111: +16 % at P50 on the emulator.** It is not explained by the M6 Off path (experiment above). It sits in `resolve` (tune → resolved, main and IO hops). Bisect on hardware against the commits the lead makes from this tree. On hardware, measure the §15 gate with I-TS-22, plus I-17 with `-e timeshift off|auto`.
3. **Hardware-only gates still open:** seek P50/P90, the 5 % zap regression, writer CPU (I-TS-21 with `soak=1`, about 2 h 15 min) and the M-TS-1…5 manual steps.
4. ~~Delete the deprecated `PlayerStateMachineImpl.timeshiftUi` / `TimeshiftUi` shim.~~ Done by the lead (2026-10-19, before the final gate): the suite reads `PlayerUiState.paused` through `tsUi()` and the shim and its KDoc are gone.
5. **Fake panel, HLS sessions.** Each HLS session holds a `max_connections` slot for `hls_timeout` (20 s), and the app's launch resumes the last channel. Any one-connection case that follows an HLS case needs a short `hls_timeout`, as I-TS-4 and I-TS-25 now set.
6. **Fake panel, clip loop.** The synthetic clips are not loop-continuous:
   - the audio steps +213 ms per 10 s loop;
   - the live PCR runs 2.76 % ahead of wall time.

   I-TS-15 and I-TS-16 carry documented tolerances for this. A loop-continuous clip (audio trimmed to the video span, the loop offset per PID) would remove them. It would also tell whether I-TS-6 is the panel or the player.
7. **Writer storm and other later items** from the playback review (L2, L5–L17) are untouched by this pass.

## App audit follow-up (2026-10-20)

The owner's app audit (`docs/review/2026-10-20-app-audit.md`, decisions `docs/decisions/2026-10-20-audit-hardening.md` and
`2026-10-20-bounded-user-regex-and-lan-overload.md`) found and fixed three time-shift defects the 2026-10-19 reviews missed:

| Audit finding | Where | What changed |
|---|---|---|
| 2. A rejected preparation (HLS, buffer Off, cycle ended) kept its ring grant until the next prepare or process death | `PlaybackControllerImpl` (tune path), `TimeshiftSessionFactoryImpl` | every preparation has one owner with exactly-once take/release; late completions are disposed on IO; superseded queued preparations are skipped; a discard request survives until late grants settle |
| 3. Closing a buffered cycle wrote the daily counter on the main thread under the accounting lock | `TimeshiftSessionFactoryImpl.close`, `TimeshiftStorageImpl` | in-memory accounting stays immediate; persistence is coalesced on a background writer with atomic file replacement; a failed snapshot stays dirty for the next flush |
| 9. Per-tune grant metadata (`lengths`/`limits`) grew until the ring was rebuilt | `TimeshiftSessionFactoryImpl` | metadata is consumed on start and removed on abandonment or supersession; partial start failures roll back |

The audit's validation was JVM (5,077 tests) plus three emulator navigation journeys on a null stream; the time-shift device
class was rerun by the lead afterwards (see the entry below this one). Audit items that touch this feature's open list: the
release-signing guard and CI now cover the `:player` adapter tests; the regex engine change (RE2/J) does not affect the ring.

### Device class after the audit (2026-10-20)

`TimeShiftBufferTest` run in full on the emulator against the audited tree (`run-instrumented.sh --synthetic 2000
--set player_fixtures=true --set timeshift_mode='"duration"'`, 1 h 33 min):

| | Result |
|---|---|
| Cases | 35 run, **34 pass**, 1 fail, I-TS-21 soak opt-in and not run |
| Failure | **I-TS-6**, `I-TS-6 no stall`, the same class-order failure as both integration runs (lead item 1 stays open; passes alone) |
| Numbers | I-TS-2 seek latencies 216/162 ms; I-TS-17 246/148/188 ms; I-TS-22 zap P50 612 ms buffered vs 985 ms Off, P90 683 vs 1,093; I-TS-26 Back-to-live rebuffer 0 vs 0; I-TS-4 held 30,043 ms; I-TS-25 3 kicks, 2 reconnects; I-TS-7 2 boundaries; I-TS-19 0 natural stalls |

The three audit fixes (findings 2, 3 and 9) introduced no regression in the class: every case that passed before the audit
passes after it. I-TS-22's Off-path zap is now slower than the buffered one on the emulator (985 vs 612 ms); the earlier
integration note (~16 % slower Off than 70da111) still wants the hardware bisect before anything is concluded.

