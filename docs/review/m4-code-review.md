# M4 "Full-screen player UX": code review

Reviewer pass over the merged M4 code (`git diff e92db1d..2c4eee5`), 2026-10-16, in two halves run in parallel and merged here, then a fix pass on `main`:
- **Engine** (tags `P-`): `core/.../full/**` (contracts, `PlayerStateMachineImpl` and its models, `ProgrammeFeedImpl`, `StatsFormatter`, `player_keys.csv`), `core/.../catchup/**` (archive contracts, `ArchiveVisibility`, `ArchivePlannerImpl`, the B2 edit in `CatchupUrlBuilderImpl`), the M4 edits in `core/.../guide/**` and `core/.../input/**`, `player/src/main/**` (the M4 edits in `PlaybackControllerImpl`, `PlaybackApi`, the `TsExtractorsFactory` guard, ladder row L13a), `data/src/main/**` (probe winner, `CatchupProbeStoreImpl`, `InMemoryPlayerSettings`), `app/src/main/**` (`ArchivePlaybackImpl`, `CatchupLauncherImpl`, `ArchiveModule`, the `MainActivity` wiring, the debug receiver switches).
- **Shell and tests** (tags `U-`): `ui/.../full/**` (FULL shell, bars, quick bar, channel list, pickers, scrub bar and `ScrubBarRenderer`, stream info, error card, `SubtitleLayer`, pills, `PlayerViewModel`, `PlayerEffectsImpl`, `PlayerMappers`, `ChannelNowCache`), the M4 changes under `ui/.../guide/`, `ui/.../root`, `ui/.../input`, `ui/.../ambient`, the 12 instrumented classes under `app/src/androidTest/.../player/`, `PlayerOverlayBenchmark` and the `tools/fakepanel/**` M4 changes.

Checked against `CLAUDE.md`, `design/m4-player-ux.md` (lead sign-off, §0, §1, §2 incl. §2.3a, §3.1–§3.10, §4, §5, §5.8 with C1–C4, V1–V8 and L-1…L-16, §5.9), `design/m4-media3-ui-api-notes.md` §1, `review/m4-design-review.md` (B1–B4, S1–S11, N1–N17), `decisions/2026-10-15-player-keys-and-quick-bar.md`, `design/m2-playback-core.md` §2–§3 where the controller edits touch M2, and the M3 shell findings H-S5, H-S8–H-S11.

Method: every finding was traced through the code. Nine were reproduced with throwaway tests before the fixes (P-B1 on both halves, P-S1, P-S2, P-S3, U-B1 twice, U-B2, U-B3, U-S1); P-S4 was traced through a pure function; V5 was rejected by a throwaway test. Every fix below has a regression test, named next to the finding, and a `// P-xx` / `// U-xx` comment at the change. **Fails-before check:** with each fix's behaviour reverted in place (new contract members kept so the tests compile; **25 reversions**, run in five batches by module), **56 of the new or changed tests fail**: `:core` 36 (25 of them the parameterised `ErrorCardModelTest.everyCardCarriesANextStepLine` rows), `:player`/`:app` 3, `:ui` 14, fake panel 3. That covers every finding with a JVM-testable behaviour. The rest are guards (`ArchivePlannerTest.forwardStepAtTwentyFiveSecondsBehindIsLive`, `ArchivePlaybackImplTest.atLiveProgrammeChipStartsEpgAnchoredForEveryCorrection`, `LivePauseReleaseTest` (machine side of P-S4, which is a mapper fix), `PlayerOverlayBoundsTest.everyErrorCardDrawsANextStep` (the UI half of P-S2; it fails only with the core half reverted), `SubtitleLayerPlacementTest.liftFollowsTheHighestBar`, `ScrubBarRendererTest.zeroAllocationPerDraw` (U-L2, a test-only change)) or device checks (U-S6's idle gate, U-S8, I-M4-13's extension, V8 on channel 7). Two U-S5 tests passed with the fix reverted in their first form (the cue test counted only `FullScreenHost`, which skipped; the list test stepped without scrolling); both were sharpened (a root composition counter; a step that scrolls the window) and then failed before and passed after. **Line numbers refer to `2c4eee5`.** The `P-`/`U-` tags also appear in the code comments and test names the fixes added.

The lead's decisions where the reviewers left a choice:
1. **P-B1:** from the at-live scrub bar a programme chip and PREV_PROGRAMME are EPG-anchored: the machine calls `playProgramme(channel, p, timeShift = true)`, never a `rewindLive` distance computed from a programme start. `PlayerEffects.playProgramme` gained a defaulted `timeShift` parameter to reach `ArchivePlayback.playProgramme`'s existing one (additive; design §2.1, §3.5.3 amended).
2. **P-S1 = U-L3 (L-6):** the live snap is direction-aware in `ArchivePlannerImpl.seek` and `ArchivePlaybackImpl.seekFromLivePause`: only a committed target later than the playhead (or the live-pause point) snaps to live; a backward step always requests the archive. `LIVE_SNAP_MS` stays 30 s. Design §3.5.2, §3.8.4 and the `seekTo` KDoc amended.
3. **P-S2 = U-S4 (V4):** one source, core `ErrorCardModel`: when the walk leaves no text step, the next-step line is the sentence of the first action other than RETRY that became a button (CONNECTION_LIMIT: "Stop watching on another device, then try again"); when only RETRY (and GUIDE) remain, the RETRY sentence. The card always draws the line.
4. **P-S3:** the ladder's `fail()` drops TRY_SOFTWARE_DECODER when `ctx.videoDecoderMode == SOFTWARE_PREFERRED` (the effective mode, including the L29 switch) or `!ctx.softwareVideoAvailable`.
5. **P-S4 = U-B3 (V7):** the mapper route, no contract change: `TunedStateMapper` maps `Stopped(LIVE_PAUSE_TIMEOUT, resumable = true)` to `MiniPhase.PAUSED`. The mini player draws the PAUSED pill for `tuned.phase == PAUSED` without a session, PLAY_PAUSE/PLAY after the 10 s release resume through `resumePlain` → `controller.resume()`, and the FULL stopped hint shows only for real stops. GUIDE hole after the release, measured on the emulator: the surface keeps the frozen frame (colourfulness 145 and 142 in the hole over two runs, screenshot `v7-guide-mini-paused`), so the mini stays MINI on the frozen frame with the PAUSED / −MM:SS pill; no still was needed.
6. **U-B1 (L-1):** both halves: the guide ticker got the player ticker's past-deadline guard, and `GuideStateMachineImpl.nextDeadline()` leaves `descDueAtMs` out while a hold blocks the load (the release re-arms it).
7. **U-B2 (V1):** option (a): bottom **view padding** on the `SubtitleView` with `setBottomPaddingFraction(0f)`; lift = `max(8 % of the video height, the distance from the video's bottom to 16 design px above the highest open bar)`, capped at half the video. The DVB bitmap squash while a bar shows is accepted and added to the M6 DVB check (design §3.6.3, §5.8, §8).
8. **U-S3 (V2):** the stats overlay is hidden while the channel list is open or the group card shows.
9. **U-S6 (L-14/L-16):** `player-scrub` / `player-info` trace sections added (no layers); the FULL idle-frame gate is the instrumented `FullIdleFramesTest` (`FrameMetrics` listener, 10 s idle FULL, asserted); benchmark journey (a) is **removed** with a note, (b)–(e) stay.
10. **U-S7 (L-2, N17):** contract amendment: `PlayerEvent.ArchiveStartFailed(reason)`. The host dispatches it after toasting CATCHUP_FAILED; when the failed start came from the info bar and no key followed, the machine brings the bar back on the tuned channel with the focus on now; the toast stays (design §2.1, §3.2.2 amended).
11. **V8 (engine):** a speculative CC track is accepted: `TsExtractorsFactory` calls `setTsSubtitleFormats(listOf(CEA-608))`; `PlayerMappers.isSpeculative` already labels it and sorts it last. API notes §1.3 and design §3.6.3 corrected.
12. **Remaining should-fix items applied as proposed:** U-S1, U-S2, U-S5 (including L-4 and the core half that also covers P-L1), U-S8 (a)–(f), U-S9.
13. **Later items:** only U-L2 (a per-draw allocation bound, best of three batches) and the core part of U-L7 (`full` setter guards `field === value`) were taken. Everything else in the Later lists stays unapplied (below), with U-L1 flagged **before M6**. L-5 is rejected as a product defect (U-L2).

**Result:**
- Blockers: 4 found (P-B1, U-B1, U-B2, U-B3 = P-S4), 4 fixed.
- Should fix now: 12 found after merging duplicates (P-S1 = U-L3, P-S2 = U-S4, P-S3, U-S1, U-S2, U-S3, U-S5, U-S6, U-S7, U-S8, U-S9, plus V8 handed over by the shell half), 12 fixed.
- Later: 21 items (9 engine incl. the P-L9 verdict table, 12 shell). 2 taken (U-L2, U-L7 core part); P-L1's guide half is covered by U-S5; U-L3 = P-S1; 17 not applied. Plus 3 new findings from the device pass (F-1…F-3), not applied.
- Build (`:core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:assembleBenchmark :player:lintDebug :ui:lintDebug :app:lintDebug`): green. `:core` 2,191 tests (was 2,154) plus `stateOnRealNavigator` 263 (was 261), `:data` 158 (was 158), `:player` 323 (was 321), `:ui` 203 (was 187), `:app` 42 (was 40); 0 failures, 0 skipped. Fake panel 95 tests (was 88). Lint: 0 errors (`:player` 3, `:ui` 25, `:app` 23 warnings, unchanged). `:player:testDebugUnitTest` hung once inside the combined run, in `PlaybackWatchdogTest.verdictReachesTheCallback` (the known Robolectric `idle()` hang on a repeating tick, untouched code); re-run alone: 323/323. `ArchivePlaybackImplTest.atLiveProgrammeChipStartsEpgAnchoredForEveryCorrection` failed once in that run on a wrong expectation of mine (a finished programme is a closed range; the continuation carries on), corrected.
- Device: every M4 class (14 incl. the new `FullIdleFramesTest`, InfoBarTest's two methods as separate runs), 13 M3 guide classes and M2 `SmokeAndZapTest`/`ErrorPathTest` pass on the API 30 TV emulator; no emulator crash. Five expectations were corrected on evidence (I-M4-16 codes, I-M4-13's continuation count under the new clamp, I-M4-6's timing origin, I-M4-20's one step), one fake-panel defect of this pass was found and fixed (the clamp's length), and two new findings are listed (F-1, F-2). Details in "Device pass".

---

## Blockers (fixed)

### P-B1. From the at-live scrub bar, the programme chips and PREV_PROGRAMME used the device clock as the live edge: on a corrected (or skewed) channel the archive request started `correction + skew` away from the chosen programme
- **Where:** `PlayerStateMachineImpl.kt:1010-1016` (`seekToProgramme`, branch `scrubAtLive -> effects.rewindLive(it, edge - target)`), `:1152-1155` (`liveEdgeNow()` returns `curMs` without a session), reached through `chipsKey` SELECT (`:974-979`) and `seekProgramme` (`:1021-1035`); `ArchivePlaybackImpl.kt:241` (`t = live.liveEdgeMs − backMs`, the panel's now in the programme frame).
- **Problem:** with no session the machine passed `backMs = deviceNow − P.start`; the session computed `t = deviceNow + skew + corr − backMs = P.start + skew + corr`, and the builder subtracted `corr`, so the A-frame start was `P.start + skew` instead of `P.start − corr`. Reproduced with corr = +2 h: request at 11:30Z for a programme starting 11:30Z EPG (expected 09:30Z), bar two hours past the chosen programme. With corr = 0 still `skew` off. This is the five-key route to "watch the previous programme from the start" (§3.1.6, §3.5.3); against CLAUDE.md's catch-up rule and the §5.9 correctness gate.
- **Fix (lead decision 1):** the at-live branch calls `effects.playProgramme(ch, p, timeShift = true)`: EPG-anchored, raw programme times, padded one minute, the builder subtracts the correction, and the session is a time-shift. `PlayerEffects.playProgramme` gained the defaulted `timeShift` parameter (`PlayerEffectsImpl` passes it to `ArchivePlayback.playProgramme`).
- **Tests:** `ScrubInteractionTest.chipFromTheAtLiveBarIsEpgAnchored`, `…prevProgrammeFromTheAtLiveBarIsEpgAnchored` (the effect is `playProgramme(…, timeShift = true)` of the chosen programme, never `rewindLive`); `ArchivePlaybackImplTest.atLiveProgrammeChipStartsEpgAnchoredForEveryCorrection` (corr ∈ {−1.5, 0, +2} h, skew 42 s: `requestStart == P.start − corr − 60 s`, status in the P frame at `P.start − 60 s`, a time-shift).

### U-B1. The guide ticker busy-looped on main when the guide machine left a past deadline (L-1, upgraded)
- **Where:** `GuideViewModel.kt:426-435` (`startTicker`, the wait at `:432`); core `GuideStateMachineImpl.kt:1326` (the Tick skips the description while a hold lasts) and `:1353-1368` (`nextDeadline()` kept `descDueAtMs`).
- **Problem:** hold LEFT/RIGHT until the focus stops moving (archive limit, data window end, a refused step): the Tick at the description's deadline skipped the load, so `nextDeadlineMs` stayed in the past; `nextDeadlineMs.first { it < target }` then returned without suspending and `while (true)` never yielded. The key-up (and the H-S11 watchdog) could never run: ANR. Reproduced twice (a `GuideWorld` hold left `nextDeadlineMs` 50 ms in the past; a fake machine with a past deadline dispatched 5,000 Ticks in one looper turn).
- **Fix (lead decision 6):** the guide ticker copies the player's wait (`first { it != deadline && it < target }`, timeout at least 1 ms); `nextDeadline()` publishes no description deadline while a hold blocks the load, and the release re-arms it (`armDescription` / `rebuildPanel`).
- **Tests:** `GuideViewModelTest.pastDeadlineDoesNotSpinTheTicker` (fake machine with a deadline long past: ≤ 2 Ticks per looper turn, ≤ 80 Ticks per second; it broke out after 5,000 before); `GuideBehaviourTest.heldRightAtTheEdgeLeavesNoPastDeadline` (no past deadline while holding; the release re-arms and the description loads).

### U-B2. Subtitles ignored the bottom padding: WebVTT and CEA-608 captions sat on the video's bottom edge and under every bar (V1)
- **Where:** `SubtitleLayer.kt:56-59` (`setBottomPaddingFraction`, `setCues`), `AppRoot.kt:266-274`, `PlayerUi.SUB_PAD_*`.
- **Problem:** Media3's `SubtitlePainter` applies `bottomPaddingFraction` only to cues whose line is `DIMEN_UNSET` (`SubtitlePainter.java:357`); WebVTT "auto" lines become `line −1, LINE_TYPE_NUMBER` (`WebvttSubtitle.java:87`), CEA-608 rows are `LINE_TYPE_NUMBER` (`Cea608Decoder.java:1050`) and DVB cues `LINE_TYPE_FRACTION`. So N5 moved nothing IPTV carries: captions were hidden for the 4 s passive bar after every zap and whenever a bar was open, and touched the bottom edge (overscan) otherwise. Reproduced in a Robolectric `SubtitleView`: the `line −1` cue stayed at 1003 px for fractions .08 and .40.
- **Fix (lead decision 7):** `applySubtitleState` sets the lift as the view's **bottom padding** and `newSubtitleView` sets the fraction to 0; `subtitleLiftPx = ceil(max(0.08 × videoRect.height, videoRect.bottom − (barTop − 16 × scale)))`, capped at half the video height. The padding shrinks the `CanvasSubtitleOutput` child, the box every cue type is laid out in. DVB bitmap cues squash vertically while a bar shows: accepted, on the M6 DVB check.
- **Tests:** `SubtitleLayerPlacementTest` (Robolectric, native graphics; the view built and fed as the layer does, drawn into a bitmap; for an unset-line cue, a WebVTT `−1 NUMBER` cue and a CEA-608 bottom-row cue: text bottom ≤ 804 with the info bar, ≤ 0.92 × height without; `liftFollowsTheHighestBar`); I-M4-8 extension (`TracksTest.webVttSubtitles`: the cue box, the `SubtitleView`'s child, ends 8 % above the video bottom with no bar and above y 804 with the info bar).

### U-B3 = P-S4. A live pause longer than 10 s read as "stopped": wrong mini player, wrong FULL hint, and Play/Pause stopped working on channels without archive (V7)
- **Where:** `TunedStateMapper.kt:36` (`Stopped → STOPPED` for every reason); `MiniPlayerOverlay.kt:103-112` (`nothing` → the still with "Press OK to watch", pills hidden) and `:127-131` (archive pill only with an active session); `FullScreenHost.kt:119` (the N12 hint); `ArchiveVisibility.kt:21`; `PlaybackControllerImpl.kt:1486-1497` (`Stopped(LIVE_PAUSE_TIMEOUT, resumable = true)` 10 s into a live pause).
- **Problem:** GUIDE: the mini showed the still with "Press OK to watch" and no PAUSED/−MM:SS pill (V7, both captures); a plain pause (< 10 s) drew LIVE. FULL: "Stopped to free the connection. Press OK to play again." over the paused frame, and OK opened the channel list (archive); on a channel without archive PLAY_PAUSE → `pausePlain()` returned early on `Stopped`. Pause and time-shift are the M4 headline (§3.2.3, §3.1.2).
- **Fix (lead decision 5):** `TunedStateMapper` maps `Stopped(LIVE_PAUSE_TIMEOUT, resumable = true)` to `MiniPhase.PAUSED` (`controller.resume()` already handles a resumable `Stopped`); every other stop stays STOPPED. `MiniPlayerOverlay` draws the PAUSED pill through `PillRules` for `tuned.phase == PAUSED` without a session. The FULL hint keys on STOPPED, so it now shows for real stops only; PLAY_PAUSE/PLAY on PAUSED go to `resumePlain`.
- **Tests:** `TunedStateMapperTest.livePauseTimeoutIsPaused` (every other `StopReason`, and a non-resumable timeout, stay STOPPED); `MiniPlayerOverlayTest` (Compose, through the real mapper: PAUSED pill and no "Press OK to watch" for the timeout with a PAUSED_LIVE session, for a plain `Playing(paused)` and for the timeout without a session; a USER stop still shows the still); `PlayerViewModelTest.livePauseOverTenSecondsShowsNoStoppedHint`; `LivePauseReleaseTest.playPauseAfterTheReleaseResumesPlain`, `…selectAfterTheReleaseIsNotTheStoppedHint` (machine); I-M4-13 extension `TimeShiftTest.livePauseReleaseStaysPausedInTheGuide` (no STOPPED phase sampled during the pause, Back to the guide 12 s in shows PAUSED / −MM:SS, OK returns to FULL without a re-tune, PLAY_PAUSE resumes from the archive).

---

## Should fix now (fixed)

### P-S1 = U-L3. A backward scrub step within 30 s of the live edge jumped **forward** to live (§5.8 L-6)
- **Where:** `ArchivePlannerImpl.kt:48-50` (`seek` returns `Live` when `t ≥ liveEdge − LIVE_SNAP_MS`, whatever the direction); `ArchivePlaybackImpl.kt:979-986` (`seekFromLivePause`, same rule).
- **Problem:** 20 s behind, one ← (10 s) targeted `edge − 30 s` and the planner answered `Live`: "back 10 s" moved 20 s forward. From a fresh live pause the first one or two ← commits snapped to live (I-M4-20 had to rewind four steps, §5.8 T9). Reproduced: `seek(wall − 10 s, wall = edge − 20 s)` → `Live`.
- **Fix (lead decision 2):** direction-aware: `t > wallMs && t ≥ liveEdge − LIVE_SNAP_MS` → Live; in `seekFromLivePause`, `t > s.pausedAtMs`. `LIVE_SNAP_MS` stays 30 s for forward seeks.
- **Tests:** `ArchivePlannerTest.backStepAtTwentySecondsBehindIsARerequest` (also a 1 s step back), `…forwardStepAtTwentyFiveSecondsBehindIsLive` (guard); `ArchivePlaybackImplTest.firstLeftAfterAFreshLivePauseRequestsTheArchive` (one archive request, no live tune; a forward commit inside the zone still goes live).

### P-S2 = U-S4. Error cards whose every step became a button carried no next-step sentence; CONNECTION_LIMIT's own instruction was never shown (V4)
- **Where:** `ErrorCardModel.kt:34-43` (a step with an M4 button became the button only; `textStep` was the first step without one); `PlayerErrorCard.kt:83-85` (only `textStep` was drawn).
- **Problem:** no text line for CREDENTIALS_UNAVAILABLE, CONNECTION_LIMIT (buttons Retry, Stop, Guide), CONNECTION_LIMIT_LOCAL, AUDIO_OUTPUT_FAILED, CATCHUP_NOT_IN_ARCHIVE with a variant left, and for CHANNEL_OFFLINE and SERVER_ERROR once the ladder drops TRY_BACKUP. "Retry" does not say the conflict is on another device. CLAUDE.md: errors carry a reason and a next step.
- **Fix (lead decision 3):** when the walk leaves no text step, `ErrorCardModel` takes the first action other than RETRY that became a button (STOP_AND_WAIT, AUDIO_OPTIONS, WATCH_LIVE…), else RETRY. `PlayerErrorCard` always draws the line (tag `PLAYER_ERROR_STEP_TAG`; a RETRY fallback covers a hand-built state).
- **Tests:** `ErrorCardModelTest.everyCardCarriesANextStepLine` (every code × {defaults, ladder-narrowed, RETRY only} × variantLeft × decoder: a line, and the RETRY sentence only when no other action became a button), `…connectionLimitShowsStopAndWaitAsText`, `perCodeTable` and `ladderNarrowedActions` rows updated; `PlayerOverlayBoundsTest.everyErrorCardDrawsANextStep` (Compose over all 25 codes; the CONNECTION_LIMIT sentence on screen); I-M4-16 (U-S8 a).

### P-S3. The "Software decoder" button was offered when software decoding already ran, which looped
- **Where:** `ErrorCardModel.kt:51-53` (checks `VideoFacts.decoderOverride`, the channel's own override only, `PlaybackControllerImpl.kt:783`); `ErrorLadderImpl.kt:480` (L30 fails with CODEC_UNSUPPORTED, whose steps start with TRY_SOFTWARE_DECODER) and `:151-153` (`fail()` did not filter it).
- **Problem:** after L29 switched to software and software failed too (or with the global setting on software, or no software decoder for the MIME), the card offered "Software decoder", which re-prepared the same path and came back to the same card (S8/F5: no button loops).
- **Fix (lead decision 4):** `fail()` drops TRY_SOFTWARE_DECODER when `ctx.videoDecoderMode == SOFTWARE_PREFERRED` (the effective mode, `c.softwareVideo` included) or `!ctx.softwareVideoAvailable`.
- **Tests:** `ErrorLadderTableTest.l30OffersTheSoftwareDecoderOnlyWhenItCanHelp`; `PlaybackControllerStateTest.softwareFallbackFailureOffersNoSoftwareButton` (L29 then the real ladder: the context carries SOFTWARE_PREFERRED, the error's actions are `[OPEN_EXTERNAL]`).

### U-S1. Info and scrub bar headers: the glyphs ended mid-bar (V3)
- **Where:** `InfoBar.kt:83-93` (`BarHeader`: the name column `weight(1f, fill = false)` and a `Spacer(weight(1f))` split the free width).
- **Fix:** name and pill share one `Row(Modifier.weight(1f))`; the spacer is gone; the glyphs end at the bar's inner right (measured x 1828, was 1087). The missing fps in "720p" is not a defect (`VideoInfo.fps` is null until the estimate settles; software decoding on the emulator, M2 I-22).
- **Test:** `PlayerOverlayBoundsTest.headerGlyphsAreRightAligned` (glyph node right = 1828 ± 1, pill left of the glyphs). The scrub bar's header has no glyphs; it shares the composable.

### U-S2. The state pill described the tuned channel next to a browsed or pending-zap channel (V6, origin P-L5)
- **Where:** `InfoBar.kt:56`, `ScrubBar.kt:69`; the machine's pill is always the tuned playback's (`PlayerStateMachineImpl.kt:1475`).
- **Fix:** `FullScreenHost` draws the info bar's pill only when `info.channel.identityHash == GuideUiState.tuned.identityHash` (`InfoBar(showPill = …)`, `BarHeader(pill: PillState?)`). The scrub bar always shows the tuned channel.
- **Test:** `PlayerOverlayBoundsTest.pillOnlyForTheTunedChannel`.

### U-S3. Stream info showed through the channel list and collided with the group card (V2)
- **Where:** `FullScreenHost.kt:95-97`, `:133-143`.
- **Fix (lead decision 8):** the stats overlay is drawn only while `ui.channels == null && state.groupCard == null`.
- **Test:** `PlayerOverlayBoundsTest.statsNeverOverlapTheListOrTheGroupCard`.

### U-S5. Recomposition scope: player state, cues and the archive status recomposed far more than their nodes (M3 H-S9 pattern; L-4; covers P-L1)
- **Where:** `AppRoot.kt:146-149` (`pui` and `cues` read in the root body), `:258` (`LocalPlayerArchive`, a `staticCompositionLocalOf`); core `GuideStateMachineImpl.kt:1243-1244` (every `ArchiveStatus`, one a second while playing, copied into `GuideUiState`); `FullScreenHost.kt:76` (`nowMs` read in composition); `ChannelListPanel.kt:72-82` (rows by position).
- **Fix:** the root derives screen-off, the bar under the toasts and captions, and the picker with `derivedStateOf` (`PlayerBar`, `barOf`), so it never reads the player state itself; `SubtitleLayer` takes a `State<List<Cue>>` read only in `update`; `GuideScreen` passes `state.archive` to the mini explicitly and the static local is gone (L-4); core `onArchive` republishes `GuideUiState.archive` only when the phase, channel, programme, failure, error, time-shift, advancing or display shift change, or the wall clock or live edge leave the published status's extrapolation by more than `ARCHIVE_UI_SLACK_MS` (1 s) (P-L1's guide half); `FullScreenHost`'s clock is a per-minute ticker and the scrub track reads the wall clock in its draw lambda; channel rows are `key(identityHash)`. P-L1's FULL half (`PillState.atMs` on every publish) is not changed: `PlayerUiState` still changes per status while an archive session runs; with the root no longer reading it, the cost is `FullScreenHost` only.
- **Tests:** `ShellRedrawTest.archiveStatusesInGuideRecomposeTheMiniPlayerOnly` (10 statuses: the panel never recomposes), `…cueListsDoNotRecomposeTheFullScreen` (10 cue lists: 0 root and 0 `FullScreenHost` compositions; debug counters `COMPOSITIONS_ROOT`, `COMPOSITIONS_FULL`), `…oneListStepRecomposesAtMostTwoRows` (a scrolling step: ≤ 3 row compositions, `COMPOSITIONS_LIST_ROW`); `GuideBehaviourTest.archiveStatusPollsDoNotChurnTheGuideState` (core: ten polls on the extrapolation keep the same object; a seek and a phase change publish).

### U-S6. The overlay draw gate could not be measured, and the idle journey could not report its own result (L-14, L-16)
- **Where:** `ScrubBar.kt:76-79`, `InfoBar.kt` (no trace sections); `PlayerOverlayBenchmark.kt:101-107` (`FrameTimingMetric` throws on zero frames).
- **Fix (lead decision 9):** `Trace` sections `player-scrub` (around `renderer.draw` in the track's draw lambda) and `player-info` (`drawWithContent` on the info bar root); neither adds a layer. The FULL idle gate is `FullIdleFramesTest` (instrumented, `Window.OnFrameMetricsAvailableListener` over 10 s of bare live FULL; ≤ 5 frames on the emulator, ≤ 2 on hardware); benchmark journey (a) removed with a note.
- **Test:** `FullIdleFramesTest.fullIdleDrawsNoFrames` (device: 1 app-window frame in 10 s, ceiling 5). Benchmark journey (b) on the emulator: `player-scrub` 72–82 draws per iteration, 15.4–22.3 ms in total (≈ 0.25 ms per draw; the §5.9 P90 needs per-slice data, M6 hardware); `player-info` is 0 there (journey (b) never opens the info bar); journey (d) was not rerun.

### U-S7. A synchronous catch-up failure from the info bar left the focus on the past programme (L-2, N17)
- **Where:** `PlayerEffectsImpl.kt:183-190` (toast only); the machine closed the bar at `infoSelect` before the launcher answered.
- **Fix (lead decision 10, contract amendment):** `PlayerEvent.ArchiveStartFailed(reason)`, dispatched by `PlayerEffectsImpl` after the toast. The machine remembers a launch from the bar until the next key (`barLaunchPending`); on the event it reopens the info bar on the tuned channel with the focus on now (or moves an open bar's focus to now). Failures of other starts change nothing; the toast stays. Design §2.1 and §3.2.2 amended.
- **Tests:** `ScrubInteractionTest.syncArchiveFailureReturnsFocusToNow` (and no effect after another key); `PlayerViewModelTest.effectsGlue` asserts the event on `ArchiveStart.Failed`.

### U-S8. Instrumented assertions that could pass while the behaviour was wrong
- **(a) I-M4-16** (`PlayerErrorCardTest`): asserts the code per case, a next step on every card, the RETRY-sentence rule, and STOP_AND_WAIT on CONNECTION_LIMIT. Two of the reviewer's expected codes did **not** reproduce and the test follows the measured ladder: `auth_mode 403` is FORBIDDEN (a stream 403 maps to FORBIDDEN in M2 rows L6/L7; AUTH_REJECTED needs a 401 or an AUTH_FAILED account gate), and `status Expired` is ACCOUNT_EXPIRED or FORBIDDEN depending on whether the account card was refreshed before the 403 rows decided (both seen; F-3). The Expired case runs last: the app keeps the Expired account card after the panel recovers, so the next case read ACCOUNT_EXPIRED (F-3).
- **(b) I-M4-10 linger** (`CatchupPlaybackTest.lingeringPanelOnRangedReopens`): "playing" is required when the panel recorded no rejection; after the seek it must settle, or end on the CONNECTION_LIMIT card with a recorded refusal; FAILED no longer passes.
- **(c) `PlayerPanel.timeshiftLogical`:** two unranged opens of the same request are merged only when a ranged read of that request sits between them (the `TsDurationReader` pattern); a plain duplicate stays two requests.
- **(d) I-M4-8:** U-B2's cue-box check (above).
- **(e) I-M4-18** (`PlayerMemoryTest`): the scrub leg runs first, on channel 1 (live with archive), and asserts the TIME_SHIFT item instead of skipping (it used to run after ten zaps, on a channel the test did not choose).
- **(f) `PlayerTestBase.isEmulator()`:** `Build.HARDWARE in {ranchu, goldfish}` only (a `generic` fingerprint is common on no-name AOSP TV boxes), and the decision is logged.

### U-S9. Fake panel: `timeshift_mode: "duration"` served archive that could not exist yet
- **Where:** `fakepanel.py:1416-1435` (body = `D` seconds, exact `Content-Length`), `:1208-1226` (`archive_gate` checks only the start).
- **Fix:** `timeshift_future: "clamp" | "serve"` (default `clamp`): in duration mode the body ends at `now − archive_lag_sec − start` (log note `CLAMPED{n}s/{D}s`); `timeshift_chunked` (default false): no `Content-Length`/`Accept-Ranges`, a close-delimited body, `Range` ignored (log note `NO-LENGTH`). README documents both. HLS duration playlists are not clamped.
- **Device follow-up (this pass's own defect, fixed):** evaluating `now` on every open made the total grow between the reopens of one load (TsDurationReader's tail read, binary-search seeks); Media3's seeker then reopened in ~1 KB steps (1,896 requests) and every time-shift resume ended in NO_DATA_TIMEOUT. A 600 s snapshot per URL then made each continuation of the same minute end at the old edge: a re-request every ~300 ms. The written length is now snapshotted for `CLAMP_SNAPSHOT_S` = 5 s (a panel writing its archive in chunks). See F-1/F-2.
- **Tests:** `TestTimeshiftFutureAndLength` (clamp is the default and ends at the panel's now, the length is stable across reopens, honours the lag, old starts unclamped, `serve`, length-less body, validation); I-M4-9, I-M4-10, I-M4-13/13b/14/15 rerun with the clamp (device pass).

### V8 (handed over by the shell half). CEA-608 was not offered on TS channels without a caption descriptor
- **Where:** `TsExtractorsFactory.kt:51` (`DefaultExtractorsFactory()` without `setTsSubtitleFormats`); Media3 1.11.1 passes an empty list (`DefaultExtractorsFactory.java:583-592`, `DefaultTsPayloadReaderFactory.java:125`), `SeiReader` creates one track per listed format (`SeiReader.java:49`), so with no descriptor no CC track exists. API notes §1.3 quoted the constructor Javadoc and was wrong for this build.
- **Fix (lead decision 11):** `setTsSubtitleFormats(SPECULATIVE_CC_FORMATS)` (one CEA-608 format); `PlayerMappers.isSpeculative` labels and sorts it last. API notes §1.3 and design §3.6.3 corrected.
- **Tests:** `TsExtractorsFactoryTest.multiClipListsOneSpeculativeCea608Track` (new fixture `core/src/test/resources/ts/h264_multi_cc_1s.ts`, the first 288,392 bytes of the fake panel's `multi` clip: one CEA-608 text track with samples); `TracksTest.speculativeCea608OnTsWithoutDescriptor` (channel 7: a Subtitles item, the track is speculative, CC1 cues decode).

### Also tested (review test gaps closed)
- `ScrubBarRendererTest.zeroAllocationPerDraw` is a per-draw bound (< 16 B per draw, best of three batches of 1,000), U-L2.
- `GuideFullDelegationTest.reattachingTheSameMachineDoesNotReEnterFull` (U-L7 core part: the `full` setter returns on `field === value`).
- A next step on every card, I-M4-16 per code (gap 5); the guide ticker and a held key at the edge (gap 1); cue placement (gap 2); the 10 s release in the mini, the FULL hint and PLAY_PAUSE (gap 3); header, pill and overlap (gap 4); recomposition bounds (gap 6); the overlay gates (gap 7); N17 (gap 8); CEA-608 on the `multi` TS clip (gap 10); the clamped and length-less archive (gap 13); P-B1, P-S1, P-S2, P-S3, P-S4 rows (engine gaps 1–5).

---

## Later (not applied)

Both lists, deduplicated; **taken** items are fixed above.

### Engine (`P-L`)
- **P-L1.** Archive status republishes both UI states every second. **Guide half taken** with U-S5 (`archiveUiChanged`); the FULL half (`PillState.atMs` stamped on every publish) is not applied: measure FULL idle during catch-up in M6.
- **P-L2 (§5.8 L-8).** The switch timeout fires on a live cycle paused before first media; its Error ends the PAUSED_LIVE session. Suggested: report `Stopped(LIVE_PAUSE_TIMEOUT)` or suspend the timeout while `c.paused`. Test `PlaybackControllerStateTest.pauseBeforeFirstMediaNeverTimesOutToAnError`.
- **P-L3.** `ArchivePlayback.notices` is a `MutableSharedFlow` without replay (H-B3 class): a LiveNotReady between view models is lost. Use a `Channel(BUFFERED).receiveAsFlow()`.
- **P-L4.** The unlimited-retry card's "next attempt in 5 s" goes stale: `RetryInfo` has no timestamp and the card is not updated when the attempt starts.
- **P-L5.** The machine passes the tuned pill to a browsed channel (origin of V6). Fixed in the drawer by U-S2; the machine still carries it.
- **P-L6.** The quick bar (`menu.audio.size ≥ 2`) and the audio picker (two **supported** tracks) disagree; use one predicate.
- **P-L7.** A live pause followed by Home loses the pause point (`Stopped(BACKGROUND)` is not owned). Spec-conformant; a v1.1 parity question (TiviMate keeps it).
- **P-L8.** The B2 builder test covers only the not-started check; add the depth check (`correctedStart < windowStart`) against the planner's `archiveFromMs` for the same corrections.
- **P-L9.** Verdicts on §5.8 Later items: L-2 → U-S7 (taken); L-4 (`PlayerFixtures.error` attaches `retry` to NO_DATA_TIMEOUT) harmless, align the fixture or §1.8; L-6 → P-S1 (taken); L-8 → P-L2; L-15 (L32 on AAC underrun storms) for M6 hardware; V4 → P-S2; V6 → P-L5/U-S2; V7 → P-S4.

### Shell and tests (`U-L`)
- **U-L1 (L-3), before M6.** `PlayerViewModel.channelOf` runs `rowIdOf` + `channels` on a miss before the first `PendingZap` of a zap target (~120 ms of the 169–445 ms key → passive bar). Prefetch the ±1/±2 neighbours of the zap lineup when the tuned channel changes, or resolve from the list's rows cache. **Do it before M6**: the 100 ms passive-bar criterion is at risk on low-end hardware.
- **U-L2 (L-5).** **Taken** as a test change; rejected as a product defect (2 B per draw is JIT/OSR noise; one object per draw would be ≥ 16 B).
- **U-L3 (L-6).** = P-S1, taken.
- **U-L4 (V5, L-7).** Number entry: the now title arrives after two serial lookups and each digit keeps the previous preview until its own lookup lands. Not a defect in the path (V5 rejected by a throwaway test); latency only.
- **U-L5.** `ScrubBar.kt:56` captures `TimeZone.getDefault()` once per composition lifetime; key it on the guide's labels version (M3 G-S3 pattern).
- **U-L6.** `GuideViewModel.onLineup` replaces the controller's zap list with the guide's lineup on every re-emission, so after a channel-list tune from another group CH± returns to the guide's group (§3.3.1).
- **U-L7.** On an activity recreation in FULL `attachPlayer` set `machine.full` to the same machine and re-entered FULL. **Core part taken** (`field === value`); the view-model side (skip the re-attach) not applied.
- **U-L8.** `ChannelNowCache` is never cleared on an EPG generation swap.
- **U-L9.** Accessibility: nothing in FULL announces; the overlays expose no semantics or live region.
- **U-L10.** Sleeps that should be awaits: I-M4-10 `Thread.sleep(45_000)`, I-M4-5/I-M4-20 `Thread.sleep(2_000)`, I-M4-1 `Thread.sleep(5_000)`.
- **U-L11 (L-9).** M3 `SearchTest` should find the archive card by state.
- **U-L12.** Benchmark (b) approximates a hold with separate presses, so the accelerated scrub table is never drawn under load.

### Found by the device pass (new, not applied)
- **F-1 (archive, M6 / real panel D10).** With a panel that serves only what is written (`timeshift_future: clamp`), each xc request ends at the panel's now, so a time-shift ~20 s behind re-requests about every 17–20 s (8 continuations in 150 s in I-M4-13; each one a hand-off with the last frame held, §3.8.6). The offset holds and live is never reached, so S1 holds; the cost is a hand-off every ~20 s at shallow time-shifts. Whether real Xtream panels behave so (and whether a live-shifted form or a longer lead should be used near the edge) is for the real-panel check D10 and a TiviMate comparison.
- **F-2 (Media3 seeking, M6).** A finite TS whose total grows between the reopens of one load (Content-Length, then a `Content-Range` total that is larger) sent `TsBinarySearchSeeker` into a reopen loop in ~1 KB steps and the session into NO_DATA_TIMEOUT (seen only with this pass's first clamp, fixed in the fake panel). If a real panel's archive file grows during a request, the same can happen; check on a real panel (D10), and consider treating a changing total as "no length" in the archive data source.
- **F-3 (M2 ladder).** An expired account reads FORBIDDEN or ACCOUNT_EXPIRED depending on whether the account card was refreshed before L6/L7 decided, and the app keeps an Expired card after the provider recovers (the next 458 read ACCOUNT_EXPIRED). An M2 question: refresh the account before FORBIDDEN on a 403, and expire a non-Active card sooner.

---

## Verified correct (no action)

### Engine half
- **C1, the `ArchiveVisibility` subtraction:** removing the lower-tuneId clause hides nothing that should show (`ArchivePlaybackImpl.kt:586` is the only caller of `tuneArchive`; controller tuneIds are monotonic and superseded cycles are guarded; zaps, live tunes, FAILED and IDLE pass through; every owned Error the session does not act on reaches FAILED in the same turn).
- **C2, the `TsExtractorsFactory` guard:** bytes are counted per `read` call; `RESULT_SEEK` opens a new input; `TsDurationReader` peeks (counts 0); end of input still gives up; a live stream always consumes bytes.
- **C3, the seek sequence after a relaunch:** `onArchive` adopts `settledSeq`; a seek in flight at relaunch is covered by the new request's settle; no other per-VM counter is compared with app-wide state.
- **C4, re-prepares keep the playhead:** the position is read after `endSession()`'s `stop()`; before READY it is the masked seek position; the engine is created once; a lifecycle restart is a new cycle at `archiveResumeAtMs`.
- **Design review items B2, B3, S1–S3** and the probe winner (stored once per session for a non-first variant that reached first media, column-targeted DAO update).
- **Controller additions:** unlimited retries (`maxAttempts == 0`, 5 s interval, screen-on only behind the card), `Playing.ended` (E-L2), cues on main and cleared in `endSession`, `previous`, decoder override, server index; ladder row L13a; no M4 edit touches `LiveTsDataSource`, the broker or the Range rule.
- **`ProgrammeFeedImpl`:** one daemon thread, generation swap on that thread, patch clears the id cache, `SQLITE_BUSY` 3 × 500 ms, cancellation, idempotent `close()`, title-only rows.
- **Wiring and hygiene:** Hilt scopes, no stub left bound, `MainActivity` attach order, media keys in FULL consumed by the machine, no URL logged (`Redact.host`), no telemetry, debug-only seed URL, the S6 key map and §3.1.5 Back ladder, main-thread discipline and no `runBlocking`.

### Shell and tests half
- **Surface rule:** no `graphicsLayer`, alpha, clip, shadow or `RenderEffect` on any ancestor of `VideoLayer`; `SubtitleLayer` is a direct child of the root `Box` between `HoleChrome` and the sink (N11), FULL only, screen on; no blur, `Popup`/`Dialog` or `rememberInfiniteTransition` in FULL.
- **Design px** everywhere in FULL; native-canvas text multiplies by the density. **Allocation:** `ScrubBarRenderer` 13 ops with preallocated paints; the 1 Hz tick read only in the draw lambda.
- **Sticky video** (§3.6.2, S2, N4), **`PlayerViewModel`** (`ArchiveVisibility` in both VMs, lookups cache hits only, buffered shell events, probe unregistered by its owner, ticker under `repeatOnLifecycle(STARTED)` with the guard, `LiveResumed` suppression), **attach order** (S9), **keys** (S5, H-S4, the one sink; `SubtitleView` unfocusable), **error card, pickers, quick bar, number entry, ambient** and the **fake panel** (`range_requests_seen` on live TS only; 206/`Content-Range`; reject forms validated; window and lag per request; `--set`).

---

## Test gaps that remain
1. **C4:** a re-prepare after a player error before READY, and `SwitchSourceType` on an archive cycle (`ArchiveAndMemoryTest`).
2. **C3 sibling:** a new machine while the session has a seek in flight (`pendingSeq > settledSeq`).
3. **P-L3:** a late `notices` subscriber.
4. **P-L1 FULL half:** FULL idle during catch-up and `GuideScrollBenchmark` with an archive mini player (benchmark).
5. **`TsExtractorsFactory`:** the dead-PID grace when the loader reopens the input mid-grace.
6. **`PlayerViewModelTest`:** the number-entry now title (§3.10.4); `focusedDetail` cancellation beyond the debounce case.
7. **Zone change** under the scrub labels (U-L5); **EPG swap** under the list titles (U-L8); **zap list** after a list tune and a lineup re-emission (U-L6); **activity recreation** in FULL on the device (U-L7).
8. **DVB bitmap subtitles** under a bar (the accepted U-B2 squash) and on real streams: M6.
9. **The speculative CC track on real broadcasts** (no captions: an empty track; with a descriptor: the declared services) on hardware.
10. **The fake panel's clamp** keeps a growing `Content-Length` across the reopens of one request (a real archive file grows the same way); a length-less archive on the device (`timeshift_chunked`) was not run.

---

## Device pass

API 30 TV emulator `TheBox_TV_API30` (x86, Intel Mac), cold-booted with `-gpu host -no-snapshot-load -feature -Vulkan,-GLDirectMem,-HasSharedSlotsHostMemoryAllocator,-HardwareDecoder,-GLDMA,-GLDMA2` after moving one crash dump out of `/tmp/android-$USER/emu-crash-*/completed`; device clock in sync with the Mac. Fake panel via `tools/fakepanel/run-instrumented.sh`, one class per call: M4 classes with `--synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"'` (so the new default `timeshift_future: clamp` applies), M3 guide classes with `--synthetic 2000`, M2 classes with no flags. `range_requests_seen == 0` on live TS held in every `@After`. No emulator crash in this pass (InfoBarTest's two methods run as separate invocations). All numbers are emulator numbers.

| Class | Pass | Fail | Notes |
|---|---|---|---|
| `InfoBarTest#passiveBarOnZap` (I-M4-1) | 1 | 0 | key → passive bar 213 ms (emulator ceiling 600 ms); hid 4,000 ms after PLAYING |
| `InfoBarTest#activeBar` (I-M4-2) | 1 | 0 | |
| `TracksTest` (I-M4-7, 8, 23, V8) | 4 | 0 | I-M4-8: cue box ends at y 993 without a bar (≤ 0.92 × 1080) and at y 804 with the info bar (U-B2); new `speculativeCea608OnTsWithoutDescriptor`: channel 7 lists one CEA-608 track (no label, no language), Subtitles in the quick bar, CC1 cues within ~2 s |
| `TimeShiftTest` (I-M4-13, 13b, 14, 15, + extension) | 5 | 0 | first run 2 fails (the clamp defect, U-S9 follow-up), second run 1 (the continuation count, T1 revisited), third run green. I-M4-13: 22.0 s behind after resume, 23.8 s after 150 s, 8 continuations, never live; I-M4-13b 23.3 s; I-M4-14 two 404s (lag) then live; extension: no STOPPED phase sampled during the pause, mini pill PAUSED −00:15 growing, OK back to FULL without a re-tune, resume from the archive |
| `PlayerErrorCardTest` (I-M4-16) | 1 | 0 | after the expectation fixes (U-S8 a): FORBIDDEN (`auth_mode 403`, buttons Retry Guide, step "user agent"), CONNECTION_LIMIT (Retry Stop Guide, step STOP_AND_WAIT), WEB_PAGE_INSTEAD, ACCOUNT_EXPIRED (Guide, step account) |
| `FullIdleFramesTest` (new, U-S6) | 1 | 0 | 1 app-window frame in 10 s of idle FULL (ceiling 5 on the emulator) |
| `CatchupPlaybackTest` (I-M4-9, 10, 10 linger, 22) | 4 | 0 | first run (during the clamp defect) 2 fails, rerun green twice. I-M4-22 settled 1,370 ms after the key; linger: CONNECTION_LIMIT card with 3 recorded refusals (U-S8 b) |
| `ChannelListTest` (I-M4-3…5) | 3 | 0 | |
| `NumberEntryFullTest` (I-M4-6) | 1 | 0 | twice 771/790 ms "early": the test timed the last digit after `digits()`'s trailing 200 ms sleep; fixed, commit 1,045 ms after the last digit (§5.8's 1,141 ms included the old late commit) |
| `ProbeTest` (I-M4-11, 12, 21) | 3 | 0 | |
| `StatsOverlayTest` (I-M4-17) | 1 | 0 | |
| `PlayerMemoryTest` (I-M4-18) | 1 | 0 | scrub leg first, asserted (U-S8 e); growth −3,618 KiB over 5 journeys |
| `PlayerSurfaceTest` (I-M4-19) | 1 | 0 | |
| `FiveKeyJourneyTest` (I-M4-20) | 1 | 0 | now **one** ← from the 5 s live pause reaches the time-shift (P-S1; §5.8 T9 needed four) |
| M3 `HarnessTest`, `StartupAndSurfaceTest`, `KeysOnDeviceTest`, `PagingTest`, `GroupsAndBackTest`, `RecompositionTest`, `LowEndTest`, `ModeSurvivalTest`, `ZapThroughGuideTest`, `LayerScopeTest`, `PrefixTest`, `NumberEntryTest`, `CatchupTest` | 21 | 0 | the guide ticker (U-B1), the `full` guard (U-L7), the archive throttle and the root changes (U-S5) under the M3 hold, mini-player and recomposition checks |
| M2 `SmokeAndZapTest` | 7 | 0 | |
| M2 `ErrorPathTest` | 6 | 0 | |

Not run this pass: M3 `ReminderTest`, `SearchTest` (L-9/U-L11), `MemoryTest`, `GenerationSwapTest`; M2 `MediaAndLifecycleTest`, `ReconnectTest`; `GuideScrollBenchmark` (still open from §5.8).

**Screenshots** (scratchpad `m4-screens-fixed/`, 1920 × 1080, taken with `screencap` by a scratch instrumented class, deleted after the run): `v1-subs-nobar` and `v1-v3-subs-infobar` (WebVTT above the info bar; glyphs `720p · 1.0 · CC` at the bar's right), `v2-stats` and `v2-stats-under-list` (stats hidden while the list is open), `v4-connection-limit` ("Stop watching on another device, then try again" under the headline), `v6-browse` (browsing channel 7 with 8 tuned: no pill), `v7-full-paused-12s`, `v7-full-paused-bare` (PAUSED scrub bar, no stopped hint), `v7-guide-mini-paused` (frozen frame, PAUSED −00:15, CH 1), `v8-cc1` (CC1 on channel 7). V5 was rejected (no capture). Noted in passing, not changed: on the CONNECTION_LIMIT capture the passive bar of the zap shows a LIVE pill under the card (the tuned phase is ERROR; out of this pass's scope).

**Test-expectation corrections this pass** (evidence above): I-M4-16 codes and case order (U-S8 a, F-3); I-M4-13 accepts 2–10 continuations (U-S9: a clamped file carries ~20 s, F-1); I-M4-6 times the last digit when it is sent; I-M4-20 one step (P-S1); I-M4-18 scrub leg first (U-S8 e).
