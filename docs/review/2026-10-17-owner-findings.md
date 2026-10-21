# Owner findings, 2026-10-17 (emulator pass after M5)

Four observations from the owner, reproduced on `emulator-5554` (1920×1080, 320 dpi). Three fixed in code with
regression tests; the fourth is a design question left for the owner (below).

## 1. "On launch = Full screen, last channel" opened the guide with nothing playing — fixed
`GuideViewModel.startup()` only tuned when the controller was `PlaybackState.Idle`. After Exit the process stays alive and
the controller is `Stopped` (log: `stop USER → state Stopped`), so a warm relaunch skipped the tune and `StartFull`
entirely, whatever "On launch" said; a cold start (process killed) worked. Now `Idle`, `Stopped` and `Error` all count as
"nothing playing"; only a live session (a recreated activity over a playing controller) keeps the startup tune away.
Test: `GuideViewModelTest.startupTunesOverAStoppedOrFailedControllerButNotOverALiveSession`.

## 2. Guide cells, names and logos coming and going while scrolling — fixed
`AndroidGuideCanvas` kept the O-2 per-row `RenderNode` recordings in 32 slots chosen by hashing the row key. One frame
records 14 keys (7 rows × background and text passes), so most frames had a collision; the second row re-recorded the
first row's node, and because the outer display list holds nodes by reference, the first row's backgrounds (or its text,
labels and logos) vanished until the keys changed. Replaced by `RowSlots`: a key → slot table with frame-aware LRU
eviction that never reuses a slot touched in the current frame (48 slots; a frame with more rows than slots draws the
rest directly). Test: `RowSlotsTest`. Verified on the emulator across scrolls.

## 3. Settings rows cut off at the bottom of the page — fixed
`SettingsMachineImpl.VISIBLE_ROWS = 8` budgets eight 76 px rows, but rows with a second line are 104 px and an open option
list is taller, so the focused row ended below the pane (seen on Playback: "External player" cut at the pane bottom). The
page now applies the machine's `top` and then scrolls on by whole rows until the cursor row is wholly inside the viewport
(`SettingsScreen.revealCursor`). At the end of a page the list sits at its end, so the first row shown may be partial, as
in any scrolled list. Test: `SettingsScreenTest.focusedTallRowBeyondTheEightRowBudgetIsScrolledIntoThePane`.

## 4. Text size against the 10-foot guidelines — owner ruling 2026-10-17, done
The design reference's sizes were desktop-mockup values; on a 1080p TV 1 design px = 0.5 sp, so everything but the
big titles sat below Android TV's 12 sp floor (24 design px). The owner ruled: raise the type, scale the logos with it,
and relay the full-screen info bar. Applied:

- **Compose UI type scale** (every `GuideUi.sans`/`mono` call outside the grid renderer, 179 sites): 13→18, 14→18,
  15→20, 16→22, 17→22, 18→24, 19→24, 20→26, 21→26, 22→28, 24→30, 26→32, 28→34, 30→36, 34→40, 40→44; 64 and the
  bubble sizes unchanged. `DesignTokens.Type`: MiniTitle 36, Clock 40, Body 26 (line height 1.4), CellTitle 26,
  CellTime 20. Focused-panel description capped at 2 lines (980 px wide) so the header region still fits.
- **Guide grid** (`GuideGeometry`, `GuideTextStyle`, `GridConstants`): 5 rows of 74 px at pitch 82 (was 6 × 60 / 68),
  label column 240 (was 200), timeline 1518 px ≈ 8.433 px/min, cell title 26/600, cell time 20 mono, channel number
  20 mono, channel name 26/600, ruler 20, logo slot 130 × 64 (was 96 × 40). The navigator, state machine and pager now
  all read `GuideGeometry.VISIBLE_ROWS`; the golden frame was re-recorded; `docs/01-design-reference.md` and
  `docs/design/m3-guide.md` Appendix A.1 carry the new numbers beside the prototype's.
- **Full-screen info bar**: a 240 × 172 logo card fills the bar's inner height on the left; the channel line (number
  chip, name 32/600, pill, glyphs 20 mono), the programme title 38/600, the time row (22 mono, 520 px progress) and the
  synopsis/next line (24) sit beside it. The scrub bar keeps the compact header. Channel list logo slot 104 × 60.
- **Settings**: row label 28, value 26, description 22, options 26, page title 36, section labels 28; "Show advanced
  settings" at 22 so it fits beside its toggle. Group chip over the guide widened to 222 px.

Not done: tv-material components are untouched (the app draws its own), and the search, phone-setup and parental
screens got the same mechanical scale without an individual layout pass; worth a look on real hardware in M6.

## 5. "HEVC streams show a frame, stall, then fail" on the Sony Bravia 4K VH2 — analysed, two app defects fixed
Owner log export `TheBox-logs-2026-10-17-2251.txt` (Sony BRAVIA 4K VH2, MediaTek, Android 12, hardware HEVC
`OMX.MTK.VIDEO.DECODER.HEVC` up to 4096×2304; settings: buffer NONE, tunneling ON). The channel is "Sky Cinema
Thriller" (#3150, xc 538850); its neighbours 3141–3149 zapped fine seconds earlier, so this is a stream problem on that
channel, not HEVC on this TV.

**What the stream does** (same channel family on 2026-10-16 23:25–23:32 and 2026-10-17 22:40–22:43):
- Right after the first frame every video output buffer is skipped as very late (`queued=441 rendered=0 skipped=416`)
  while audio runs, then the audio sink reports a +5.76 s timestamp jump (`UnexpectedDiscontinuityException`, every
  session), then the server sends no bytes for 4 s about 28–30 s in, the reconnect lands ~15–22 s behind the previous
  PCR, `LiveTsDataSource` raises PCR_DISCONTINUITY, L25 re-prepares, and the cycle repeats until L27c fails with
  STREAM_UNDECODABLE (Retry / Open in another player). That is the "frame, nothing, frame 15 s later, no audio, then
  the card" the owner saw. Media3 also logs ~29 "CeaUtil: malformed SEI NAL unit" warnings per second on it.
- Reading: the provider's transcoder for this channel emits A/V timestamps that disagree by several seconds, gaps in
  the audio PTS, and periodic output stalls. Needs a capture to confirm (`ffprobe -show_packets` on the raw TS); the
  owner has the credentials.

**App defects it exposed, fixed:**
1. With tunneling on, the tunneled MediaTek decoder produced nothing (`bytes+4.4 MB media+0 ms`), the watchdog called
   DECODER_STUCK at 14.1 s, the ladder chose DisableTunnelingAndReprepare, and the 15 s stream-switch deadline failed the
   cycle 0.6 s later ("stream switch timeout: play.undecodable"), so the repair never ran. A pipeline repair
   (tunneling off, software video/audio, passthrough off, TS compat raise) now gets one fresh 15 s window per cycle
   (`PlaybackControllerImpl.extendSwitchWindow`, `LadderContext.msSinceSwitchWindowStart`); plain retries do not, so a
   cycle still ends within two windows. Test `aPipelineRepairNearTheSwitchDeadlineGetsOneFreshWindow`.
2. The owner's manual retry tunneled again (the ladder's switch was per cycle). `ChannelPlaybackMemory.tunnelingOff`
   (bit 0b1000 of `channel_prefs.playback_flags`, no migration) remembers it per channel; the next tune starts
   untunneled. Test `tunnelingOffIsRememberedForTheChannelAndAppliedOnTheNextTune`.

**Still open:** the stream itself. With the fixes the channel fails faster and with the right pipeline, but it will
still not play until the timestamps are understood; a 60 s capture of the TS is the next step. Also worth a look on
this TV: tunneling was on in settings (default is off), and buffer mode NONE gives a 4K HEVC stream no slack.

## 6. Owner ruling 2026-10-17 (evening): guide cells, channel list, mini player, hold-Back, no guide flash — done
- **Guide cells** draw the title only, at 30 px (`GuideTextStyle.CELL_TITLE`); the time line is gone (the ruler
  carries the times) and the catch-up cue ↺ now leads the title of archived programmes. Golden frame re-recorded;
  the fake measurer's 15 px/char expectations updated.
- **Channel list** (player, left): number, logo, and the programme title at 28/600 (the channel name only when no
  programme is known); the progress bar stays.
- **Mini player**: the bottom panel shows the channel name only (no programme title, no progress bar).
- **Hold Back in the guide** returns to the full-screen player (`DefaultKeyMap`: guide long Back / Button B = PLAY,
  which the guide machine maps to "go FULL" when something plays); the footer shows "Hold Back · Full screen" while
  something plays. Back and B now act on key-up in the guide, like OK.
- **No guide flash on launch**: `GuideUiState.booting` starts true; the root draws the blank canvas until the startup
  decision (`GuideEvent.StartGuide` from the view model for the guide modes, the first Tuned for StartFull, setup, or
  any key) *and* the displayed mode has caught up with the machine's (the GUIDE → FULL crossing animates a scrim
  first). Verified on the emulator: blank → black player → video, no guide frame.

## 7. 2026-10-18: "still not playing HEVC" — the stream's audio timestamps are broken; the app now plays it video-only
Second Sony log (`TheBox-logs-2026-10-18-0004.txt`, running the fixes of §5): the switch window was extended as
designed, the repair ran, and "Sky Cinema Thriller" (xc 538850) still stalled after its first frame
(`bytes+2.3 MB media+0 ms`). Its neighbours (538848, 538866) played. The launch tune of 538850 itself played for 30 s,
so the channel is *sometimes* playable, which pointed at the stream, not the device.

**Capture.** A debug-only raw TS capture was added (`TsDebugOverrides.captureDir` → `FileCaptureSink` behind the
existing `TeeDataSource` seam; `thebox-debug://clock?capture_ts=1` in debug builds). The emulator, on the same
playlist, reproduced the failure exactly and recorded the stream (`scratchpad capture2/tune-2.ts`, 9.8 MB, 21 s):
- Video: HEVC Main 1080p50, IDR every ~0.8 s, parameter sets on each, PTS continuous (19129.48 → 19150.92 s).
- Audio: E-AC3 5.1, 667 frames that should span 21 s but whose PES timestamps advance **3 ticks (33 µs) per packet**
  (the frames inside one PES are 2880 ticks apart, the next PES restarts 3 ticks after the previous), in a time base
  85 000 s away from the video's. The neighbour 538856's E-AC3 advances 32 ms per frame and spans 33 s.
- ExoPlayer's buffered position is the minimum over the audio and video tracks, so with the audio track standing still
  the player never leaves BUFFERING: the b1 detector's `media+0 ms`, then the L27 re-prepare loop, then
  STREAM_UNDECODABLE. The Oct 16 audio-sink "timestamp discontinuity" errors and the 5.76 s jumps are the same mux.

**Fix (device finding, ships):** the TS dead-track guard now also tracks each track's timestamp span; when video has
advanced ≥ 4 s while an audio track with ≥ 60 samples advanced < 0.5 s it reports the PID once
(`STUCK_AUDIO_PTS` session event → `FailureSignal.AudioTimestampsStuck` → ladder **L33t** → DropAudioAndReprepare with
`PlaybackWarning.AUDIO_TIMESTAMPS`: "No sound: this channel's audio timestamps are broken at the provider. Playing the
picture only."; L33u fails if it recurs). Emulator: 538850 went from a 50 s failure loop to `state Playing` 0.2 s after
the first frame, picture only. Tests: `TsExtractorsFactoryTest.stuckAudioTimestampsAreReportedOnceHealthyAudioNever`,
`ErrorLadderTableTest.l33tDropsTheAudioTrackForStuckTimestampsThenFails`,
`LadderExecutionTest.stuckAudioTimestampsFromTheGuardDropTheAudioTrack`.

**Follow-up the same day (owner: "a few channels in UK movies now have no sound"):** the stuck-timestamp verdict is
now followed by a repair before the drop. Ladder **L33r** (`RepairAudioTimestampsAndReprepare`, `AudioStep.TS_REPAIRED`)
re-prepares with `StreamSessionContext.repairAudioTimestamps`; the TS guard then regenerates every audio sample's
timestamp as `previous + frame spacing`, the spacing learned from the stream's own intra-PES deltas (1–200 ms window,
32 ms for E-AC3), the first sample placed on the guard's current video clock, and the audio clock snapped back onto
the video clock whenever they drift more than 1.5 s apart. The stuck window check now judges the timestamps the
player sees, is a window of 60 samples (max − min) rather than "since the first sample", so one odd first timestamp
cannot fake a verdict. L33t (drop, picture only) remains the second step, L33u the fail. Emulator, 538850: verdict
0.1 s after first media → L33r → Playing 0.8 s later, no further audio verdicts. A/V sync after the repair is as
good as the mux's interleaving (one PES group, ~0.1 s); real audio gaps resync within 1.5 s. The provider should
still be told their muxer stamps E-AC3 PES at 1/2880 of the right rate on these channels.

**UK | Movies (HEVC), Entertainment (HEVC), Sky/TNT Sports (HEVC) groups:** every channel tried (618727, 618724,
618713, 618701, 618761) answers HTTP 503 on the TS request before any byte, through retries, an account refresh and
re-resolves; the account shows 4 connections allowed, 0 active, and the non-HEVC variants play. Provider-side; the
app's "The provider's server had an error" card is the right outcome.
