# M6: H.264 access units in TS (slow motion with tunneling off)

Status: **proposal** (2026-11-26, proposer). Reviewer pass pending. Baseline: main `66ae6bd8` (v0.1.22), Media3 1.11.1.

## 1. Finding (short)

Sony BRAVIA (mt5895, `OMX.MTK.VIDEO.DECODER.AVC`), tunneling off: ~92 video input buffers/s queued, ~23 frames/s out on
59.94 fps content. Media3 drops to the next keyframe once a second. A diagnostic build without
`DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS` (TS and HLS) gave 60 in / 60 out / 60 rendered and smooth
picture on every source tried. The flag is set in `TsExtractorsFactory.TS_FLAGS` and `MediaSourceBuilder.HLS_TS_FLAGS`.
Tunneled playback tolerated the bad samples, and so did the emulator. The emulator symptom was logged as O-2
(m2-playback-core §5, "≈ 2 samples per frame, playback unaffected") and left in place.

## 2. Mechanism (verified against `H264Reader.java` in `tools/ffmpeg/work/media`, 1.11.1)

**The flag does not split slices of one picture.** For two slices of the same picture,
`isFirstVclNalUnitOfPicture` compares equal on every field it checks (frame_num, PPS id, field/bottom flags,
nal_ref_idc, POC, IDR id), so the slices stay together. The extra samples are the *non-VCL* NAL units in front of the
first slice. Two defects in `SampleReader.endNalUnit` (:506–523) produce them:

1. **Two boundary rules fire at different positions.** `nalUnitType == AUD` starts a sample at the AUD. With the flag,
   `sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader)` starts a sample at the **first slice** too. That is
   the wrong place: H.264 §7.4.1.2.3 starts the access unit at the first AUD/SPS/PPS/SEI after the previous picture's
   last VCL NAL. So `[AUD, SEI, SPS, PPS]` is cut off as its own sample. It has no VCL data, the picture's PES
   timestamp and no keyframe flag.
2. **The comparison uses stale headers.** `startNalUnit` (:359–371) rotates `sliceHeader`/`previousSliceHeader` only for
   VCL types (1, 2, 5), but `endNalUnit` runs the comparison at the end of **every** NAL unit. If the previous picture
   had one slice, the pair holds the last slices of two different pictures, so the test is true at the end of each
   non-VCL NAL until the next slice arrives. Every prefix NAL then becomes a sample of its own. For a multi-slice
   previous picture the pair is two slices of one picture (false), and only defect 1 applies.

Simulated per picture (a Python port of `SampleReader`'s boundary logic, run on x264 streams; scratch script, not
checked in):

| Stream (x264, 60 frames) | Flag on: samples / pictures | Flag off |
|---|---|---|
| AUD, 4 slices | 120 / 60: `[AUD]` + `[4 slices]` | 60 / 60 |
| AUD, 1 slice | 122 / 60: `[AUD]` + `[slice]` | 60 / 60 |
| AUD + per-frame SEI, 1 slice (ffmpeg-style, like the provider) | **182 / 60**: `[AUD]` `[SEI]` `[slice]` | 60 / 60 |
| AUD + SEI, 4 slices | 120 / 60: `[AUD, SEI]` + `[slices]` | 60 / 60 |
| no AUD, 1 slice, no SEI | 62 / 60 (OK; SPS and PPS split once each) | **0** (no samples) |
| no AUD, 4 slices + SEI | 60 / 60, but each SEI lands in the previous picture's sample | **0** |
| no AUD, 1 slice + per-frame SEI | **122 / 60**: `[SEI]` + `[slice]` (defect 2) | **0** |
| repo fixture `h264_aac_2s.ts` (50 frames) | 102 / 50 | 50 / 50 |
| repo fixture `h264_multi_cc_1s.ts` (CC in SEI) | 71 / 24 | 24 / 24 |

So the Sony's ~4 inputs per picture point to single-slice pictures carrying AUD plus about two other non-VCL NALs
(SEI, or SPS/PPS repeats), not to four slices. A 2 s capture through `TsDebugOverrides.captureDir` would confirm the
layout. Media3's own tests run the flag only on `sample_h264_no_access_unit_delimiters.ts` (`TsExtractorTest:121`) and
never on an AUD stream. Media3's default TS flags are 0, so the flag-off path is the one every ExoPlayer app runs.

Without the flag, `H264Reader` ends samples only at an AUD (and at end of input). An AUD-less stream gets a format
(SPS/PPS are parsed) but **no video samples**: prepare completes, then the video stalls.

`H265Reader` ignores the flag (`DefaultTsPayloadReaderFactory:209` passes only the SEI reader). H.262 is unaffected.

## 3. Original reasoning for the flag

From research (`docs/research/protocols-m3u-xmltv-xtream.md:363`, `android-tv-architecture.md:106`) into m2 §3.8.5:
"`FLAG_DETECT_ACCESS_UNITS` (streams without AUD NAL units)". Amendment 2026-10-13 already knew about the AUD split
and moved the flag off at compat level 1, keeping it on at level 0 because "whether a stream has AUDs is not known
before the first PES" (`TsExtractorsFactory.COMPAT_TS_FLAGS` KDoc). O-1/O-2 judged it harmless on the emulator.
Context: ffmpeg's mpegts muxer **inserts an AUD in front of every H.264 access unit that lacks one** (verified with
ffmpeg 4.4.4: an x264 `aud=0` stream muxed to TS carries 60 AUDs in 60 frames). DVB (TS 101 154) and ATSC (A/72)
require AUDs. Most Xtream restreams pass through ffmpeg or a broadcast mux, so AUD-less H.264 should be rare.

## 4. Options

| | Option | Verdict |
|---|---|---|
| a | Flag off by default; the ladder or channel memory turns it on when video produces no samples | Rejected. Every AUD-less channel costs a stall plus a re-prepare on first zap, needs a new detector, a ladder row and persisted state, all to learn something the first PES already says. |
| **b** | **Decide per extractor by looking for an AUD in the first video PES, before building `TsExtractor`** | **Recommended.** Deterministic, stateless, no zap cost to speak of. It fixes every AUD stream (the common case) and leaves AUD-less streams exactly as today. |
| c | Wrap the H.264 payload reader and pick the mode lazily | Rejected. `TsExtractor` ends tracks right after the PMT, and a lazily created `H264Reader`/`SeiReader` calls `output.track()` and `TrackIdGenerator` later. That breaks track ids and the dead-track guard. |
| d1 | Fork `H264Reader` with spec-correct AU boundaries (§7.4.1.2.3; comparison only at VCL ends) | Correct for all rows of §2, including AUD-less + SEI. About 650 lines of Media3 code to carry across upgrades. **Follow-up only** if §7's counters find AUD-less split channels. File it upstream as well. |
| d2 | Merge consecutive video samples with equal timestamps in `DeadTrackGuard` | Rejected for now. It holds back every sample by one, and it would fuse real pictures on muxes that put several AUs in one PES under one PTS. |
| — | Flag off everywhere, no detection | Rejected. AUD-less channels would lose video (§2). |

## 5. Recommended design

### 5.1 TS (live, time-shift ring, catch-up archive): `GuardedTsExtractor` decides the flag

- **New `core/src/main/kotlin/app/thebox/core/playback/ts/H264AudProbe.kt`** (pure Kotlin, next to `TsPacketAligner`
  and `PcrTracker`, JVM-testable). It is fed TS bytes and returns `Undecided | Aud | NoAud`:
  - It walks 188-byte packets and resyncs on `0x47` the way the aligner does.
  - The video PID is the first PID whose PUSI payload starts `00 00 01 Ex`. There is no PMT parse, and the stream type
    does not matter: the flag only affects `H264Reader`, so a wrong verdict on H.265 or MPEG-2 changes nothing.
  - It skips the PES header (`9 + PES_header_data_length`) and scans the ES bytes of each new PES (crossing packets) for
    start codes. H.264 NAL type is `b & 0x1F` with forbidden bit 0.
  - Per PES: an AUD (9) before the first VCL NAL (1/5) counts as "AUD-led". A VCL NAL with no AUD in front of it counts
    as "not AUD-led".
  - **Verdict:** `Aud` after **2** consecutive AUD-led PES (this protects against AUDs only on IDRs), `NoAud` at the
    first PES that is not AUD-led.
- **`TsExtractorsFactory`:** `createExtractors()` returns a `GuardedTsExtractor` that gets a `(flags) -> TsExtractor`
  builder (the same `DefaultExtractorsFactory` call with mode and `SPECULATIVE_CC_FORMATS`), not a finished extractor.
  - `TS_FLAGS` becomes `BASE_TS_FLAGS = FLAG_ALLOW_NON_IDR_KEYFRAMES`.
  - `flagsFor(verdict, debugOverride)`: add `FLAG_DETECT_ACCESS_UNITS` for `NoAud` and for `Undecided`. `Undecided`
    keeps today's behaviour for odd streams.
  - `COMPAT_TS_FLAGS` goes away: compat level 1 means only `MODE_MULTI_PMT`.
- **`GuardedTsExtractor`:**
  - `sniff()` delegates to a sniff-only `TsExtractor` (sniff does not depend on flags).
  - `init(output)` stores the output and builds the `DeadTrackGuard`.
  - The first `read()` calls `input.peek()` in packet-sized chunks into the probe until it gives a verdict or
    `AUD_PROBE_BUDGET_BYTES` (1 MiB, same as `DEAD_TRACK_GRACE_BYTES`) is reached or input ends. It then calls
    `resetPeekPosition()`, builds the delegate with the chosen flags, calls `delegate.init(guard)` and continues as
    today.
  - `seek()` before the decision resets the probe.
  - The decision is reported once through a new `onAuMode(verdict, pid)` callback. `MediaSourceBuilder.tsExtractors`
    turns it into `ctx.onEvent(SNIFF, "H264_AU", "aud=1|0|? pid=…")` so it shows in the event log and diagnostics.
- **Zap cost:** the probe reads bytes the extractor needs anyway. The delay is one video PES start plus one picture
  (≤ 40 ms of stream at 25 fps) and a byte scan of typically < 50 KB. Prepare already waits for the video format,
  which arrives in those same PES, so the cost is lost in the noise. Gate it anyway (§6).
- **Scope per path:**
  - Live TS: decide once per extractor, so a `LiveTsDataSource` reconnect splice keeps the mode.
  - Time-shift ring readers and catch-up archive build a new extractor per prepare/reader context
    (`Seams.kt` per reader ctx, m6-timeshift §4.3), so they re-decide on the bytes they read.
  - Archive seeks inside one extractor keep the mode.
- **Mid-stream encoder change** (e.g. provider failover across a reconnect):
  - AUD → AUD-less with the flag off: video samples stop, the existing stall detectors (FROZEN_VIDEO / POSITION_STUCK)
    fire, L27a re-prepares, and the new extractor re-decides. No new ladder row.
  - AUD-less → AUD with the flag on: today's split comes back until the next re-prepare. This is rare and §7 makes it
    visible.

### 5.2 HLS (live, archive, Sports Hub clips)

`MediaSourceBuilder.HLS_TS_FLAGS = FLAG_ALLOW_NON_IDR_KEYFRAMES`. Drop the flag outright. `DefaultHlsExtractorFactory`
creates its `TsExtractor` privately, so the probe cannot be injected without our own `HlsExtractorFactory`. Media3's
HLS default is flags = 0. ffmpeg's HLS muxer uses the mpegts muxer (AUDs always), and so do Apple's segmenters. Risk:
an AUD-less HLS stream would get no video. If that turns up, the follow-up is a `BoxHlsExtractorFactory` that wraps
the TS case in `GuardedTsExtractor` (open question Q2).

### 5.3 Debug and diagnostic switches

- `TsDebugOverrides.detectAccessUnitsOff: Boolean` becomes `detectAccessUnits: Boolean?` (null = probe decides,
  true/false = force). `PlayerDebugHooks` and the tv-diag branch's `DiagSwitches.DETECTAU` (`detectau=0|1|auto`) map
  onto it.
- `TsExtractorsFactoryTest.theDebugOverrideTurnsOffOnlyAccessUnitDetection` is rewritten for the tri-state.

### 5.4 Docs to amend

- m2-playback-core §3.8.5 defaults and the 2026-10-13 amendment (compat level no longer touches the flag), §2.11, O-2
  (resolved: it was this bug).
- m4-media3-ui-api-notes line 61 (`TS_FLAGS`).
- m6-timeshift §4.3 table row.
- Master plan decision line.

## 6. Test plan

**Fixtures** go in `core/src/test/resources/ts/` (shared with `:player`), with README rows and the commands. Each is
320×180, 60 fps, 30 frames (0.5 s), `-bf 2 -g 30`, video only, **≈ 25–30 KB each, about 150 KB total**:

| File | x264 params | Expected samples, new code |
|---|---|---|
| `h264_aud_sei_s1.ts` | `slices=1:aud=1:nal-hrd=vbr:vbv-maxrate=2000:vbv-bufsize=2000` (the provider's shape) | 30 (today 91) |
| `h264_aud_s4.ts` | `slices=4:aud=1` | 30 (today 60) |
| `h264_noaud_s1.ts` | `slices=1:aud=0` | 30 frames (+ SPS/PPS splits, 32) |
| `h264_noaud_sei_s4.ts` | `slices=4:aud=0:nal-hrd=…` | 30 |
| `h264_noaud_sei_s1.ts` | `slices=1:aud=0:nal-hrd=…` | 60: the known Media3 limitation (§4 d1), pinned so a Media3 upgrade or a fork shows up |

ffmpeg cannot write an AUD-less TS (its muxer inserts AUDs). The AUD-less files therefore need a small generator,
**`tools/fixtures/es2ts.py`** (≈ 150 lines, stdlib only, `python3 -I`). It reads an Annex-B `.h264` from `ffmpeg -f
h264` plus a frame rate and writes PAT, PMT (stream type 0x1B), one PES per AU with PTS/DTS taken from x264's POC
order, and PCR on the video PID. Use it for all five files so they differ only in the ES. The fake panel gets the same
generator for a `noaud` clip (`tools/fakepanel`) for emulator runs.

**Unit tests:**
- `H264AudProbeTest` (`:core`): verdicts on each fixture, on `h264_aac_2s.ts`, on `garbage_prefix.ts`, with a probe fed
  1 byte at a time, on an H.265 or no-video input (no crash; verdict irrelevant), and on budget exhaustion
  (`Undecided`).
- `TsExtractorsFactoryTest` (Robolectric, `FakeExtractorOutput`). For each fixture:
  - video `sampleCount` equals the picture count from `ffprobe -count_frames`;
  - no two consecutive video samples share a timestamp;
  - every sample holds exactly one slice with `first_mb_in_slice == 0` (scan the sample bytes);
  - the first keyframe is unchanged.
  - The existing guarded-vs-plain equality test switches its plain path to the same chosen flags. It currently passes
    because both paths split identically. Today the fixture gives 102 samples for 50 pictures, and the test should
    assert 50.
- A peek test: a `DefaultExtractorInput` that returns 1–7 bytes per read (the probe must not need `peekFully`
  alignment), and a seek before the decision.

**Release gate** (added to plan §6 as a playback gate):
- **The number:** over 30 s of steady playback, with tunneling off, the video decoder's queued input buffers per
  second equal output frames per second (rendered + skipped + dropped), ratio **1.00 ± 0.03**. On 59.94 fps content
  both are 59–61/s, with zero drop-to-keyframe flushes. The extractor counter in §7 reads 0 % split.
- **Emulator** (API 30 TV):
  - fake panel ch1 (AUD) and the `noaud` clip, live TS: ratio 1.00 and event `H264_AU aud=1` / `aud=0`;
  - time-shift rewind on ch1 (a new extractor re-decides);
  - a catch-up archive item;
  - one HLS channel;
  - zap time p50 against v0.1.22 on the same channels, no regression beyond noise (±50 ms).
- **Sony**, tunneling off then on: 720p59.94 TS, 1080 TS, HLS live, a Sports Hub HLS clip, one catch-up item, 60 s
  each. Check the in/out counters (tvdiag), no slow motion, no flush storms. Also one interlaced channel if the
  provider has one (PAFF fields become one sample per field, as in every flag-off Media3 app).

## 7. Diagnostics and safety net

- **Yes, a release diagnostics line would have caught it at once.** Add "video in/out per s" (queued input vs
  rendered+skipped+dropped, 5 s window, from the `DecoderCounters` the watchdog already reads; `videoQueuedMinusOutput`
  exists, diagnostics only) and "AU split %" to the stats overlay and the per-tune summary in Recent channel changes.
- **AU split %** comes from `DeadTrackGuard.LazyTrackOutput.commit` for video tracks: the share of samples whose
  timestamp equals the previous sample's. Split samples share the picture's PES timestamp, and real pictures never
  do. It is cheap and decoder-independent, and it would also have shown on the emulator, where the decoder kept up.
- After 5 s of video, log `AU_SPLIT ratio=0.67 mode=aud|noaud|forced` once per session when the ratio is > 0.1.
- **No ladder action on either signal.**
  - The decoder ratio is noisy: drop-to-keyframe skips inputs, and decode-only samples follow seeks.
  - The extractor signal's only remaining cause after this fix is AUD-less + per-frame SEI, which no Media3 flag
    setting fixes (§2), so the ladder has nothing to switch to.
  - These counters tell us whether d1 (fork) is worth doing.

## 8. Risks

- **Intermittent AUDs** (AUD only on some pictures): the 2-PES rule lowers the risk without removing it. With the flag
  off, pictures without an AUD merge into the previous sample, which corrupts the picture. The AU-split counter does
  not catch this. A cheap extra counter catches it: samples holding more than one slice with `first_mb_in_slice == 0`.
  Recommend adding it with the probe.
- **Persisted compat level 1** channels keep `MODE_MULTI_PMT`. Some may have escalated *because* of the split (stalls
  feeding L27b). That is harmless but sticky.
- **Peek buffer:** up to 1 MiB of peek on a pathological stream, once per extractor.
- **Probe placement:** `GuardedTsExtractor` now delays `TsExtractor.init`/`read`. `ProgressiveMediaPeriod` tolerates
  this (it calls `init` then `read` on the loader thread). The archive path's `TsDurationReader` seek happens inside
  the delegate after the decision, and the M4-integration byte-accounting fix (consumed bytes, not position) stays
  valid because peeks do not move the position.
- **HLS AUD-less** streams lose video (§5.2).

## 9. Open questions for the lead

1. Reset persisted `tsCompatLevel = 1` to 0 once on upgrade? Level 1 used to mean "flag off", and that is now automatic
   for AUD streams. Proposer: no, unless the reviewer finds L27b escalations caused by the split.
2. HLS: accept "flag off, no probe" (proposed), or build `BoxHlsExtractorFactory` now?
3. The 2-PES `Aud` rule against 1-PES (faster by one picture): keep 2?
4. File the two `H264Reader` defects upstream (androidx/media) with the `h264_aud_sei_s1.ts` fixture? A newer Media3
   may have changed this code; not checked (no network access in this pass).
5. Capture 2 s of the Sony's failing channel (debug `captureDir`) so the provider's NAL layout is in the record and
   becomes a fixture (redacted, video PID only)?

## Lead ruling after review (2026-11-26)

Review: `docs/review/2026-11-26-h264-access-units-design-review.md`. The diagnosis stands; the probe (option b) is
rejected for the review's B1 (audio-only and dead-video channels stall up to 1 MiB before the extractor starts),
B2 (a wrong guess has no recovery) and B3 (seek replay and cancellation inside the extractor contract).

Decision: keep `FLAG_DETECT_ACCESS_UNITS` on everywhere, as today, and repair the reader's output instead.
On H.264 video tracks a sample that contains no slice NAL (types 1 and 5) is held and joined to the front of the next
sample of that track. Reasons:

- It can only remove samples that carry no picture data, so it never fuses two pictures and never produces fewer
  pictures than today. A wrong decision is impossible in the direction that loses video.
- It is decided per sample: no probe, no zap cost, nothing to do on reconnects, seeks, ring replays or encoder changes.
- It also covers AUD-less streams with per-picture SEI and CBR filler, which the probe left for later.
- No ladder, channel-memory or settings change. `COMPAT_TS_FLAGS` goes (review S6): compat level 1 means
  `MODE_MULTI_PMT` only, which also un-breaks AUD-less channels that had escalated.

Rules for the join: the joined sample takes the later sample's time; flags are OR-ed (keyframe); size is the sum (the
bytes are contiguous in the track output); a held sample is dropped on a seek or flush. Trailing filler therefore moves
to the front of the next picture; the fixture set and a Sony run must show the MTK decoder accepts that.

Where: TS in the existing guarded track output (`TsExtractorsFactory.kt`), HLS through a small extractor-factory
wrapper around `DefaultHlsExtractorFactory`. H.265 and other video types pass through untouched.

Tests go through the real Media3 reader, not a port: fixtures per review S2 (AUD + SEI single-slice, AUD + 4 slices,
AUD-less, AUD-less + SEI, filler, awkward PES layouts) and Media3's own TS samples as an independent check. Gate: video
samples out equal pictures in for every fixture, and no sample without a slice reaches the track output. Release
counter per review S3 (slice-less samples joined, per tune) in diagnostics only. Device gate: Sony, tunneling off,
TS 720/1080, HLS and a hub clip at in = out = rendered with the diagnostic switch cleared.

## Implementation record (2026-11-26)

**Built.**
- `player/.../engine/SlicelessSampleJoiner.kt`:
  - `SlicelessSampleJoiner` (a `TrackOutput`): an Annex-B start-code scan of every `sampleData` byte, carried across
    calls. Slice header positions (types 1, 2, 5) go into a fixed 1024-entry ring, and a sample `[W−offset−size,
    W−offset)` with no slice in it is held. The next sample commits `(t₂, flags₁|flags₂, size₁+size₂, offset₂)`.
  - A held sample is dropped on `reset()` (extractor seek), at end of input or end of an HLS segment, and when the next
    sample is not contiguous.
  - It activates only on `video/avc` in container `video/mp2t` (so HLS fMP4 is never scanned). Bytes written before
    the format are scanned, because `H264Reader` writes its first PES chunk before the format.
  - Also in the file: `SlicelessJoiningOutput` (wraps video tracks only), `SlicelessJoinCounter`, and the HLS wrappers
    `SlicelessJoiningHlsExtractorFactory` and `SlicelessJoiningChunkExtractor`. The chunk extractor delegates
    `isPackedAudioExtractor`/`isReusable`, re-wraps `recreate()`, and resets on `onTruncatedSegmentParsed`. The
    factory forwards the subtitle and codec configuration calls and `getOutputTextFormat`.
- TS chain (`TsExtractorsFactory.kt`): `TsExtractor` → joiner → `DeadTrackGuard` → period. The guard's stuck-audio
  check, audio repair and pre-format replay therefore see whole access units. `GuardedTsExtractor.seek` resets the
  joiner.
- HLS: `MediaSourceBuilder.hlsExtractors(ctx, expose608)` is used at both call sites. `TsDebugOverrides.detectAccessUnitsOff`
  now applies to HLS as well (`TsExtractorsFactory.debugFlags`).
- `COMPAT_TS_FLAGS` is removed. `flagsFor(compat)` is `TS_FLAGS` at every level. Tests and the m2 amendment were
  updated, and O-2 is marked resolved.
- Counter: one per built source. The event log gets `SNIFF H264_AU_JOIN joined=N` at 1, 100, 1000, … joined samples.
  It was not added to the stats overlay or to the per-tune summary.

**Fixtures** (`core/src/test/resources/ts/`, generator `tools/fixtures/h264_ts_fixtures.py`, ffmpeg 4.4.4/x264,
testsrc). The generator re-muxes ffmpeg's video PES itself. Samples out, Media3 flag on → joined (pictures):

| Fixture | Progressive TS | HLS chunk |
|---|---|---|
| `h264_aud_sei_s1` | 89 → 30 (30) | 87 → 29 |
| `h264_aud_s4` | 60 → 30 (30) | |
| `h264_noaud_s1` | 30 → 30 (30) | |
| `h264_noaud_sei_s1` | 59 → 30 (30) | 57 → 29 |
| `h264_aud_sei_filler_s1` (3 filler NALs) | 92 → 30 (30) | 90 → 29 |
| `h264_aud_sei_awkward` | 89 → 30 (30) | |
| Media3 `sample_h264.ts` | 64 → 30 (its flag-off dump: 30) | |
| Media3 `sample_h264_no_access_unit_delimiters.ts` | 6 → 3 (3) | |
| repo `h264_aac_2s.ts` | 102 → 50 (50) | |

The HLS column comes from a single segment. Media3's HLS mode itself delivers one picture fewer, so the joined output
equals the reader's slice samples. Media3's "no AUD" asset in fact carries AUDs, and its own flag-on dump (6 samples)
records the split.

**Tests.**
- `SlicelessSampleJoinerTest`, run through the real `TsExtractor`/`H264Reader` and `DefaultHlsExtractorFactory`. It
  checks, per fixture:
  - samples out equal pictures in;
  - every sample holds exactly one slice with `first_mb_in_slice == 0`;
  - the bytes equal the reader's bytes, and only a trailing slice-less run may be missing;
  - the output is a contiguous run of the ES;
  - times and flags equal those of the reader's slice samples.
- Unit cases: a start code straddling calls, the header byte alone, `offset ≠ 0` with a slice in the offset bytes,
  slices written before the format, a seek drop, a scanner restart after a seek, non-H.264 tracks (H.265, MPEG-2,
  H.264 in MP4), two slice-less samples in a row, a stream ending on a held sample, a gap, the `DataReader` path, and
  counter milestones.
- `TsExtractorsFactoryTest`: guarded vs plain, where video is now 50 pictures and other tracks are identical; compat
  level 1 keeps the flag.

**Deviations from the ruling.**
- (a) Type 2 (partition A) counts as a slice, as in `H264Reader`. This only makes the joiner more conservative.
- (b) Safety valve: after 32 consecutive slice-less samples the run is committed as one sample instead of being held
  forever.
- (c) A ring overflow answers "has a slice", which is the safe direction.
- (d) In HLS a held sample is dropped at the end of each segment rather than joined across chunks. That only loses
  trailing filler or end-of-sequence NALs, and it keeps HLS chunk bookkeeping per chunk.
- (e) The fixtures live in `:core`'s shared TS resource root, which `:player`'s host tests read, next to the README.

**Not verified here.** No device or emulator run (the Sony and emulator gates in §6 are open). The MTK decoder's
acceptance of filler at the front of a picture is unverified. No interlaced (PAFF) fixture was made.
