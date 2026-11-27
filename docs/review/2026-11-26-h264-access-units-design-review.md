# Review: m6 H.264 access units (docs/design/m6-h264-access-units.md)

Reviewer pass, 2026-11-26. Baseline main `66ae6bd8`, Media3 1.11.1 (`tools/ffmpeg/work/media`, tag 1.11.1).
Checked against `H264Reader.java`, `TsExtractor.java`, `PesReader.java`, `DefaultTsPayloadReaderFactory.java`,
`DefaultHlsExtractorFactory.java`, `ProgressiveMediaPeriod.java`, `BundledExtractorsAdapter.java`, and app code
`TsExtractorsFactory.kt`, `MediaSourceBuilder.kt`, `ErrorLadderImpl.kt` (L27*), `PlaybackControllerImpl.kt` (tsCompat).

**Verdict: reject the probe (§5.1) as the fix. Recommended alternative below (§A): keep `FLAG_DETECT_ACCESS_UNITS`
on and coalesce VCL-less samples into the following sample on H.264 video tracks.** The diagnosis (§2) is right and
is the most valuable part of the proposal. The probe is the wrong tool. It picks a mode once from two PES, and when
it picks wrong in the dangerous direction the failure is terminal. It also adds a stall that can be tens of seconds
on radio channels. The coalescer is decided per sample, so it never needs a probe. It cannot produce fewer samples
than today, and it also fixes the rows that the proposal defers to a fork (d1).

## Verification of the mechanism (§2): confirmed

- `SampleReader.endNalUnit` (H264Reader:506–523) starts a sample when `nalUnitType == AUD` **or** (flag on and
  `sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader)`). The comparison runs at the end of every NAL unit.
  The headers rotate only in `startNalUnit` for VCL types (:359–371), and only if `detectAccessUnits` (or
  non-IDR + allowNonIdr). I traced `AUD, SEI, slice` × n with single-slice pictures. Slice ends: the pair is
  (cur, prev picture), so it starts a sample at the slice. AUD ends: [slice] is output. SEI ends: the comparison is
  still stale-true, so [AUD] is output. Next slice ends: [SEI] is output. That gives **3 samples per picture**, all with
  the same PES time, and only the slice sample is a keyframe. This matches the 182/60 row. Defect 1 (the prefix
  split at the first slice) is also confirmed for multi-slice pictures.
- `H265Reader` ignores the flag (factory:209). Correct.
- Media3 defaults are 0 for both `DefaultExtractorsFactory.tsFlags` and `DefaultHlsExtractorFactory()`. Media3's
  only flag test is the AUD-less asset (`TsExtractorTest:121`). Correct.
- **"~4 inputs per picture" is consistent but not proven.** 92/23 is a queued-in / output ratio taken while the
  decoder was dropping to keyframes, so it is not a clean samples-per-picture count. Plausible 4-sample layouts are
  `AUD + pic_timing SEI + CC SEI + slice` (x264 writes each SEI payload as its own NAL) or
  **`AUD + SEI + slice + filler`**. In the filler case, CBR filler (NAL 12) after the slice triggers the
  stale-true comparison again, so `[filler]` becomes its own sample. Filler is common on broadcast-derived CBR
  restreams, and the proposal does not mention it anywhere (see S3).

## Blockers

**B1. The probe stalls audio-only, scrambled and dead-video channels for up to 1 MiB.** The verdict needs a PES whose
payload starts `00 00 01 Ex`. Radio and audio-only TS never produce one, so the probe runs the full 1 MiB budget
before `TsExtractor` sees a byte. That is **26 s at 320 kbit/s and 65 s at 128 kbit/s** before audio can start, which
also trips the prepare and NO_DATA watchdogs. Channels with a scrambled video PID, or one declared but unfed (the
dead-track case), pay 1 MiB of probe and then another 1 MiB of dead-track grace. The §5.1 claim of "no zap cost to
speak of" is only true for healthy video.

**B2. A wrong `Aud` verdict has no recovery path, and the proposal's own recovery story does not hold.**
- Flag wrongly off (an AUD-less stream after a reconnect or encoder switch, or AUDs only on some pictures): §5.1 says
  "stall detectors fire, L27a re-prepares, the new extractor re-decides". If the bytes look the same, the re-probe
  gives the same verdict. L27b then raises compat to 1, which after this change no longer touches the flag. L27c
  then fails the channel with STREAM_UNDECODABLE. Today the same channel plays.
- With intermittent AUDs (for example AUDs on reference pictures only, which passes the 2-PES rule with I, P
  first), pictures fuse into one sample. That shows up as corruption or judder, not a stall, so no detector fires
  at all (§8 admits this).
- The probe needs a "video format present, video bytes flowing, zero (or ≪1/s) committed samples" detector with
  a repair that re-prepares with the flag forced on (per session, optionally remembered). The proposal has neither.

**B3. Probe mechanics conflict with how `ProgressiveMediaPeriod` drives the extractor.**
- `ExtractingLoadable.load` always calls `extractor.seek(position, seekTimeUs)` before the first `read()` on every
  loadable (`pendingExtractorSeek = true` in the constructor, PMP:1278/1327). So "seek before the decision" happens
  on every tune, not as an edge case. The design only resets the probe. It must also record (position, timeUs) and
  apply it to the delegate once it is built (`timeUs` drives the timestamp adjuster and the binary-search seeker).
- Running the whole probe inside one `read()` call blocks `loadCanceled` checks for its whole duration. That is
  B1's 65 s on radio, and it delays zap-away. The probe would have to be incremental: return `RESULT_CONTINUE` per
  chunk, keep its state across calls, and restart on a new input.
- `getUnderlyingImplementation()` would have no delegate to return before the decision.

These are fixable, but each fix adds state to a component that the recommended alternative does not need to touch.

## Should-fix (they apply to whichever design lands)

**S1. HLS flag off (§5.2) has no safety net, and the failure is worse than "no video".** In HLS mode
`TsExtractor` synthesizes an empty PUSI at each segment end (`canConsumeSynthesizedEmptyPusi`). `PesReader` then calls
`endOfInputReached()`, and `H264Reader` fakes an AUD there (:189–192). So an AUD-less HLS stream with the flag off
gives **no sample for segment 1, then one sample per segment** holding every picture of that segment, stamped with
the previous segment's last PES time. The result is a frozen picture or garbage with audio still playing.
FROZEN_VIDEO may catch it, but L27b is TS-only, so it ends in L27c. This is rare: every default ExoPlayer HLS app
runs flag 0, and ffmpeg, nginx-rtmp and Apple segmenters insert AUDs. It still needs either the §A coalescer on
HLS (keep the flag on) or the B2 detector. A thin `HlsExtractorFactory` that delegates to `DefaultHlsExtractorFactory`
and wraps the returned `HlsMediaChunkExtractor` (a public interface, no `instanceof` checks in exoplayer_hls) so its
`init(output)` wraps the output is about 40 lines. That is much less than the "BoxHlsExtractorFactory" that §5.2
implies.

**S2. The fixtures only cover the easy PES layout.** `es2ts.py` writes one aligned PES per access unit, and the
probe is tuned to exactly that case. Add fixtures for: several AUs in one PES; one AU split over bounded PES
(`PES_packet_length` ≠ 0); an AUD at the tail of the previous PES; CBR filler (`nal-hrd=cbr`); and AUDs on some
pictures only. Also use Media3's own `sample_h264_no_access_unit_delimiters.ts` (its dump: 6 samples) and
`sample_h264.ts` from `libraries/test_data` as independent oracles, so that the app's TS writer is not the only
source of truth. The Robolectric `FakeExtractorOutput` tests do run the real `H264Reader`, which is good. Only the
throwaway Python port of the counts is not real Media3; drop it from the record or label it as such.

**S3. Sample-content assertions.** "No two consecutive samples share a timestamp" and the "AU split %" metric (§7,
equal timestamps) both misfire on PAFF and on multi-AU-per-PES muxes. Use the exact signal instead: count
**video samples containing no VCL NAL** and **samples containing more than one slice with
`first_mb_in_slice == 0`**. The second is the fused-picture counter that §8 already suggests; make it mandatory. Use
both in the tests and in the diagnostics line.

**S4. Q5 (capture the Sony channel) should be a precondition, not an open question.** The fixture "provider shape"
is a guess (no filler, SEI layout unknown). Take a 2 s video-PID-only capture through `captureDir` and add it to the
README record before the fixtures are frozen.

**S5. Compat level 1 (§8, Q1).** Removing `COMPAT_TS_FLAGS` also fixes a latent bug today. Any AUD-less channel that
escalated through L27b for an unrelated stall now plays with the flag off, which means **no video, persisted**.
Under the proposal (and under §A) those channels recover. Channels that escalated *because of* the split keep
`MODE_MULTI_PMT` and lose their L27b rung (`tsCompatLevel == 0` gate). That is acceptable. A one-time reset is
optional; I agree with the proposer that it is not needed. Note the latent bug in the m2 amendment.

**S6. Release gate.** The ratio 1.00 ± 0.03 over 30 s steady playback is measurable from `DecoderCounters`. Keep
it. Add the extractor-side "VCL-less samples = 0" and "fused samples = 0" on the emulator, because those are
decoder-independent (the emulator decoder hid this bug, O-2). Also add one radio/audio-only channel to the zap-time
check (it would have caught B1).

## Notes

- N1. The option (c) rejection is right for a *lazily created* reader. It does not apply to an output-side wrapper
  that creates its tracks at the normal time (§A).
- N2. The prompt's idea of "suppress slice boundaries once an AUD has been seen" is not possible without forking
  `SampleReader`, because `detectAccessUnits` is final and the class is private. §A gets the same effect from outside.
- N3. MPEG-2 and H.265 under the probe: `b & 0x1F` on MPEG-2 slice codes (0x01–0xAF) yields 1/5 → `NoAud`. That is
  harmless, as the proposal says. Only the B1 cases are harmful.
- N4. File the two `H264Reader` defects upstream regardless (Q4). Under §A an upstream fix turns the coalescer into a
  no-op without any app change.
- N5. Q3 (2-PES vs 1-PES) does not matter under §A.

## A. Recommended alternative: flag on, coalesce VCL-less samples

On H.264 video tracks, put a small layer between the reader and the real `TrackOutput`. In TS, that is
`DeadTrackGuard.LazyTrackOutput`; activate it when `format()` says `video/avc`. `H264Reader` emits no sample
metadata before its format (:510), so nothing is missed. In HLS, use the S1 wrapper.

- As bytes pass through `sampleData` (both overloads), scan for start codes, carrying 3 bytes across calls. Record
  the absolute offsets of VCL NAL starts (types 1 and 5).
- On `sampleMetadata(t, flags, size, offset)`, the sample spans `[W−offset−size, W−offset)`. If it contains no VCL
  start, **hold** it and commit nothing.
- On the next sample, if a sample is held, commit one merged sample: `sampleMetadata(t₂, flags₁|flags₂,
  size₁+size₂, offset₂)`. Flag-on samples are contiguous (`samplePosition = nalUnitStartPosition`). Assert the held
  end equals the new start; if it does not, drop the held one, as `outputSample` does today for unset times. Clear
  the held sample on `format()` changes and on any seek.

Why it is safe in both directions:
- The flag stays on, so AUD-less streams keep exactly today's boundaries. The coalescer can only *remove*
  VCL-less samples. It never splits a sample and never joins two VCL samples, so it cannot fuse pictures.
- AUD streams get one sample per access unit. For every row in the §2 table that has an AUD, the result equals
  flag off.
- It also fixes the rows the proposal sends to d1: AUD-less + per-frame SEI (122 → 60) and filler splits. So it
  fixes AUD-less channels on the Sony too, which the probe does not.
- It is decided per sample, so encoder switches across `LiveTsDataSource` reconnects, ring replays and archive seeks
  need no special handling. There is no probe, no zap cost, no radio stall and no new ladder row.
- VCL samples are committed the moment Media3 emits them, so live latency is unchanged. Only header-only samples
  wait for the next sample.

The one behavioural difference from flag off is that trailing non-VCL NALs (filler, end of sequence) move to the
front of the next sample. Byte order is unchanged and decoders ignore filler. Cover it with the CBR-filler fixture and
the Sony run. If the MTK decoder rejects that, hold only prefix types (6–9, 13–18) and let 10–12 stay with the
previous picture by holding them in the guard for one sample. Size estimate: about 100 lines plus tests, versus the
probe plus `es2ts.py`.

What this keeps from the proposal: the §5.3 debug tri-state (map `detectau=0` to "flag off" for A/B), the §7
diagnostics using the S3 counters, the fixtures plus S2, and the release gate plus S6. If the lead still prefers the
probe, the minimum is B1 to B3 resolved: incremental and seek-correct, a PMT-aware early `Undecided` for
no-H.264-PID programs, and the zero-samples detector with a flag-on repair.
