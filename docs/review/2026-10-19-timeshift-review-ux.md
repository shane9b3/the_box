# M6 time-shift buffer: adversarial review, product half (WP-C)

Date: 2026-10-19. Reviewer: product/UX code review (Opus). Spec: `docs/design/m6-timeshift.md` (Lead sign-off, §0, §6, §7.2, §9–§13, §17.3, WP-C implementation record). Scope: the WP-C working-tree changes listed in the brief. The channel-editor, TMDB and guide-navigation changes in the same tree were ignored. Nothing was built or run. Every finding below was checked in the code. Line numbers refer to the working tree on 2026-10-19.

## Verdict

**Not ready to commit.** There are two blockers, both dead ends a viewer will hit:

- **B1:** on a paused LOCAL session, the screen says "Press OK to continue", but OK opens the channel list.
- **B2:** a gap hand-off to the provider that cannot be built leaves playback stuck at the gap for good.

The rest is close to the spec:

- The router follows §9.1 and A-C5.
- The P-frame mapping is right, and the tests cover −1 h correction with +90 s skew.
- The quick-bar order, the pills, the zap confirm (including digits), the dim timer with its consumed key, the 5 s idle hide and the settings budget all match.
- The time-shift UI allocates nothing per frame, and nothing applies `graphicsLayer` or blur to an ancestor of the video.

Commit after B1, B2 and S1–S6. The other should-fix items can land in the M6 hardening pass.

Checked and correct (no action):
- **Router.** `TimelineRouterImpl.route` rows 1–4 and `onGap` match §9.1 and A-C5 (`TimelineRouterImpl.kt:36-55`).
  - Provider requests use `edge − max(target, providerFrom)`.
  - The clamp reasons are checked in the order NOTHING_YET, then LIMITED_TODAY, then START.
- **Frame mapping.** `LocalTimelineBackend` uses `P = liveEdge(mono + wallMinusMono)` (`LocalTimelineBackend.kt:177`). Its inverse is at `:139-143`. The available buffer is held back until the frames are known (`:104`), and the unavailable reason is published at once.
- **Visibility.** `ArchiveVisibility` gains the `source != LOCAL` clause (`ArchiveVisibility.kt:21`).
- **Pause in place.** A LOCAL pause never starts an archive request (`ArchivePlaybackImpl.kt:1265-1278`). Ownership is `tuneId ≥ ownedTuneId` with Stopped/Idle excluded (`:740`).
- **Quick bar.** Pause comes first on every live channel and Back to live first while behind (`QuickBarModel.kt:36`). OK on Pause pauses and focuses the bar (`PlayerStateMachineImpl.kt:794-800`).
- **Dim.** The dim layer is a sibling in `FullScreenHost` (`FullScreenHost.kt:126`): above the bars, below the error card, the number entry, the screen-off layer and the root toasts. It is a plain `background`, with no `graphicsLayer`.
- **Units.** Design px holds, through `BoxDesignScale`'s density.
- **Allocation.** `TimedPillLabel` and `drawWindow` allocate nothing per frame (`PlayerParts.kt:117-161`; `ScrubBarRenderer.kt:184-290`). The card's 1 Hz ticker runs only while dimmed (`PausedCard.kt:44-46`). The card jumps every 60 s and is not animated, which is cheap.
- **Settings.**
  - The simple PLAYBACK rows are 5 and the total is 35 (`PlaybackRows.kt:52-58`).
  - Auto comes from `TimeshiftPolicy` and real StatFs, and the "storage is limited" value works (`WpaPages.kt:301-345`).
  - `PlaybackDomain` is exported whole, so the three prefs travel in backups (`BackupExporter.kt:42`: only BACKUP/UPDATE/SETUP are device-bound).

---

## Blockers

### B1. OK on a paused LOCAL session opens the channel list while the screen says "Press OK to continue"

- A media-key pause (Fire TV ⏯, Sony ⏸) shows the **unfocused** bar with `showScrubAuto`, which "takes no keys" (`PlayerStateMachineImpl.kt:1043-1054`). The same happens after Back closes the focused bar, and after the dim card wakes.
- The layer is then NONE, so SELECT reaches `bareKey`, which opens the channel list (`PlayerStateMachineImpl.kt:336`).
- The screen says otherwise:
  - The header lines FULL, HOLD_LIMIT and SLOT_LOST end in "Press OK to continue from where you paused" (`strings_player.xml`, `player_paused_header_*`).
  - The dim card says "OK or ▶ to continue" (`player_paused_card_body`).
- `TimeshiftMachineTest.kt:144-145` asserts the wrong behaviour: "the next key acts" → CHANNELS.
- §11.7 counts Resume as "OK = 1" on the Onn, and the screen promises the same on every remote.
- This is a dead end on the most common path: pause with the media key, come back after 2 minutes, press OK.

**Fix:**
- In `bareKey`, `SELECT -> when { archivePaused() -> { effects.archiveResume(); scrubIdleAt = curMs + SCRUB_FOCUSED_IDLE_MS }; !archive && tuned.phase == STOPPED -> retry(); else -> openList(...) }`.
  - Use `statusForTuned()`, so a provider pause (PAUSED, not PAUSED_LIVE) behaves the same.
  - Keep OK → channel list while playing behind live (§11.6).
- Fix `TimeshiftMachineTest.dimCardAndTheConsumedKey`: the second SELECT must call `archiveResume`.
- Add `archive,buf_paused,D,SELECT,…,archiveResume` and `archive,paused,D,SELECT,…` rows to `player_keys.csv`.
- Add a paused-bar-closed start state to the five-key proof (S10).

### B2. A gap hand-off to the provider that fails leaves the reader stuck at the gap

- On a Provider verdict, `PlaybackControllerImpl.onRingBoundary` emits `LocalGap` and **returns without relaunching the reader** (`PlaybackControllerImpl.kt:2829-2831`).
- From there, only `ArchivePlaybackImpl` can move playback on, and it silently does nothing in three cases:
  - `onLocalGap` returns when there is no LOCAL session (`ArchivePlaybackImpl.kt:1452`). The controller's `active = c.localShift || c.paused` (`:2824`) is true after a `pausePlain` on a buffered cycle, which the machine issues while the buffer state is still held back (S7).
  - `providerFromLocal`'s context load fails, and the code only settles (`:1432`).
  - The request cannot be built (`built == null`), and again the code only settles (`:1440`).
- When the channel has `archiveDays > 0` but the panel refuses catch-up, the viewer resumes a long pause and the picture freezes at the gap. There is no toast and no card.

**Fix:** keep a local fallback.
- Add `PlaybackController.skipGapLocal(): Boolean` (additive, default false). It runs the existing "next segment's first RAP" branch (`:2832-2835`) and emits `GapSkipped`.
- `ArchivePlaybackImpl` calls it from the early return in `onLocalGap` and from both failure paths in `providerFromLocal`.
- Belt and braces: the controller arms a 5 s timer after emitting `LocalGap`. If no archive tune for the identity arrives before it fires, the controller skips locally.
- Tests:
  - `LocalTimelineBackendTest.gapHandOffFallsBackWhenTheProviderFails`: ctx Failed and built null.
  - `ControllerTimeshiftTest`: a `LocalGap` with no follow-up skips after the timeout.

---

## Should-fix

### S1. The rewind window's width is guessed, so the bar rescales mid-session (contract change now)

- The machine sizes the window from the largest saved span, rounded up to 15/30/60/120 min (`PlayerStateMachineImpl.kt:1364-1368`, `ScrubModel.rewindWindowMs`). This produces wrong bars:
  - **Auto 1 h:** the bar spans 15 min for the first 15 minutes, then 30, then 60. The scale jumps twice.
  - **"Auto (22 min: storage is limited)":** it shows a 30 min bar with 8 min of track that can never fill.
  - **Pause only:** the bar grows.
- The VM works around this by reading the raw `controller.localBuffer` for `windowMs` and `stoppedAtMonoMs` (`PlayerViewModel.kt:190`, `:457`). That breaks A-C1: the VM may read only `ArchivePlayback.localBuffer`.

**Fix (additive):**
- Add `LocalBufferInfo.windowMs: Long = 0`, `holdMs: Long? = null` and `stoppedAtMs: Long? = null` (P frame). Fill them in `LocalTimelineBackend.publish` from `LocalBufferState.windowMs`, the hold pref and `stoppedAtMonoMs`.
- The machine uses `windowMs` for `REWIND_WINDOW`. `maxSavedSpanMs` and `ScrubModel.rewindWindowMs` go.
- Drop `controller.localBuffer` from the VM.

### S2. A seek while paused restarts the pause clock

- `syncLocalPause` (`PlayerStateMachineImpl.kt:1313-1317`) resets `localPausedSinceMs` whenever the status is not PAUSED.
- A paused ← commit goes PAUSED → SEEKING → PAUSED (`ArchivePlaybackImpl.localSeek`, `:1341-1343`, then `onLocalPlaying`). The clock therefore restarts on every nudge.
- Consequences:
  - The zap confirm can be bypassed: after a 20 min pause, one ← nudge and CH+ zaps at once and discards the saved part.
  - `PausedFacts.holdMs` (`PlayerViewModel.kt:192-194`, `stoppedAt − pausedSinceMs`) under-reports "Saving stopped after N min".

**Fix:**
- Carry the original pause instant: `ArchiveStatus.pausedSinceMs`, set in `pauseLocal`/`localPause` and kept through a SEEKING that has `pauseAtFirstMedia`.
- Or, in the machine, treat SEEKING that follows PAUSED as still paused.
- Take the hold length from `LocalBufferInfo.holdMs` (S1), not from arithmetic on the pause time.

### S3. `route()` ignores the not-saved ranges it is given

- `LocalWindow.gaps` is never read (`TimelineRouterImpl.kt:38-40`).
- A target inside a gap routes Local, and `seekLocal` floors it to the RAP **before** the gap (§6).
- Example: saving stopped while paused, leaving a 20 min gap (21:10–21:30). A scrub to 21:25 lands at 21:10, 15 min earlier than asked and with no explanation.

**Fix:** in `route`, when the target falls in `local.gaps`:
- With archive: `Provider(edge − target)` (§9.1, U-B2: "a gap the buffer lost goes to the provider").
- Without archive: `Local(gapEnd, +1)`, followed by GAP_SKIPPED.
- Add a `TimelineRouterTest.targetInsideAGap` row for each case.

### S4. The dim card truncates its next step and loses its header when the bar is closed

- The card body is `maxLines = 2` at 24 px in a 720 px card with 32 px padding (`PausedCard.kt:53-67`). That leaves about 656 px, roughly 54 characters a line.
- The HOLD_LIMIT and SLOT_LOST lines are 110–115 characters with "Press OK or ▶". They wrap to three lines and lose "to continue from where you paused".
- The header comes from `ui.scrub?.header` (`FullScreenHost.kt:126`). With the bar closed (after Back), a stopped pause shows only "Paused · 12:40 behind live", with no reason and no next step.

**Fix:**
- Set `maxLines = 3` and raise `CARD_H` to 216. Re-run the safe-area drift test.
- Take the header from machine state that does not depend on the bar (see §7 below: `PlayerUiState.paused`).

### S5. Two toasts give a settings path that does not exist

- "Show advanced settings" is the pinned toggle of the **sections pane** (`SettingsMachineImpl.kt:1093`). It is not a row inside a section.
- REWIND_LIMITED_TODAY says "Settings → Playback → Show advanced settings → Protect built-in storage".
- PAUSE_NO_REWIND_HLS (Xtream) says "Settings → Playlists → (playlist) → Show advanced settings → Stream format".
- The next step is not followable as written. §13 rule 3 is met in letter but not in use.

**Fix:**
- Reword to "Settings → Show advanced settings, then Playback → Protect built-in storage" and "Settings → Show advanced settings, then Playlists → (playlist) → Stream format".
- Amend the §13 table to match (the lead owns the text).

### S6. BACK_TO_LIVE_UNBUFFERED has no next step

- WP-C deviation 12 means the toast is a reason only (`PlayerViewModel.kt:404`, `player_toast_back_to_live_unbuffered`). That breaks §13 rule 1.

**Fix:**
- Split each PAUSE_NO_REWIND_* string into reason and step.
- The VM remembers the last PAUSE_NO_REWIND_* kind it showed for the tuned identity, and appends that step to BACK_TO_LIVE_UNBUFFERED once per tune, as §13 specifies.

### S7. Buffer states without a reason

- **Unknown state.** While the buffer state is unknown (the first frames lookup, or `buffer == null`), `noRewindToast` gives NO_CATCHUP for a rewind (`PlayerStateMachineImpl.kt:1389`). §13 removes NO_CATCHUP from pause and rewind. A pause in that window is a silent `pausePlain`: the controller pauses in place, but the machine shows no LOCAL session, pill or dim.
- **NOT_TS.** `BufferOff.NOT_TS` with `hls == false` has no branch, so a pause gets no toast at all. This matters more because `runDeferred` sets `c.tsOff = NOT_TS` after JUMPED_TO_LIVE_RESTART (`PlaybackControllerImpl.kt`, `runDeferred`). From then on, every pause on that cycle freezes silently.
  - That contradicts the restart toast's "Rewind saves again from now". WP-B owns this: either keep buffering, or drop that sentence.
- **STORAGE_ERROR on a pause.** It shows REWIND_STOPPED_STORAGE ("Live TV continues"), but the picture is frozen.

**Fix:**
- With the buffer unknown and no catch-up: a rewind toasts REWIND_NOTHING_YET.
- NOT_TS: reuse the HLS M3U variant, or add PAUSE_NO_REWIND_FORMAT.
- STORAGE_ERROR on a pause: add the "so Play returns to live" variant.

### S8. REWIND_START says "when you tuned in" when that is not true

- `clampToast` passes the oldest saved instant (`PlayerStateMachineImpl.kt:1418`).
- Once the ring has wrapped (tuned at 19:00, window full), or after a writer restart, "That's as far back as you can go (21:02, when you tuned in)" is false.

**Fix:**
- Use the current text only when `from` is within LOCAL_MARGIN of the cycle's first saved instant.
- Otherwise use a variant: "(21:02: rewind holds 30 min)", with the window taken from `windowMs` (S1).

### S9. The tune-relative state resets only when the identity changes

- `LocalTimelineBackend` resets `identitySinceMs` and the frames only when the identity changes. It also never clears `framesFor` when the state goes null (`LocalTimelineBackend.kt:69-77`).
- The machine resets `tunedSinceMs` and `maxSavedSpanMs` the same way (`PlayerStateMachineImpl.kt:1711-1712`).
- When the same channel is re-tuned (Back to live after a provider hand-off, Retry, a ladder restart), the new cycle inherits the old one's clock. REWIND_NOTHING_YET never shows; REWIND_START shows instead.

**Fix:**
- Add a cycle id (`tuneId` of the writer cycle) to `LocalBufferState` and `LocalBufferInfo`, and reset on that.
- At least clear `framesFor` when the snapshot is null.

### S10. The five-key proof's new start states are legitimate but leave a hole

The two new start states hide no function:
- **LIVE_WITH_ARCHIVE** no longer reaches SCRUB or `rewindLive` (`PlayerFiveKeyReachabilityTest.kt:126-131`). That loss is legitimate: rewind is still reachable as ↑ OK (Pause) → ← → OK, which is the §11.7 Onn journey. The bar now belongs to the time-shift kind.
- **LIVE_BUFFERED** and **LOCAL_TIME_SHIFT** are real states.

The proof still has two gaps:
- `LOCAL_TIME_SHIFT` is built with **media keys** (`:63-67`), and nothing proves that the `pauseLive` reached from LIVE_BUFFERED lands in a LOCAL state.
- No start state is "paused, bar closed", which is exactly where B1 lives. `archiveResume` is in no `effects` list.

**Fix:**
- Add a `PAUSED_LOCAL` start state: ↑ OK from LIVE_BUFFERED, then Back. Expect `archiveResume` and `jumpToLive` under every UpDownInPlayer value.
- In `liveBuffered`, assert that the state after `pauseLive` has `source == LOCAL`.

### S11. The window-mode bar text is too small for 10 feet

- `drawWindow` draws the programme title and the live clock at 15 px (`tickText`, `small`) and the centre time at 18 px (`ScrubBarRenderer.kt:55`, `:60-61`).
- §11.4 puts the live time with the 24 px end labels. The owner's 2026-10-17 pass raised player text to 22 px or more (chips, stats).

**Fix:**
- 24 px for the live clock.
- At least 22 px for the tick title and the centre time.
- Keep the title clear of the live clock, which can overlap it near the right end (`:248`, `:258`).

---

## Later

- **L1.** `zapNeedsConfirm(nowMs)` reads like a query but arms the window and toasts (`PlayerStateMachineImpl.kt:1438-1441`). The guide calls it a second time just to clear the window (`GuideStateMachineImpl.kt:1712`). Split it into `zapGate(nowMs, kind): ZapGate {ALLOW, ASKED}` and `confirmZap()`.
- **L2.** The zap-confirm window is shared by CH± and digits:
  - A CH+ followed by a digit commit within 3 s zaps unasked.
  - A digit lookup that returns after 3 s toasts again, and the guide ignores the answer and tunes anyway (`GuideStateMachineImpl.kt:1712`).
  - Key the window by kind, and act on the return value.
- **L3.** Off deletes the file only when no buffered cycle is open (WP-C deviation 9; `WpaPageEnvImpl.kt:70`). Record this in §12 as a lead decision, or have the factory discard on the cycle's close when the pref is Off.
- **L4.** The hint row and PAUSE_TIP counters live per machine, which means per app launch, not per install (deviation 3). Persist them in `GuidePrefs`.
- **L5.** `limitedTodayOnce` is kept in memory (`PlayerViewModel.kt:429`), so the toast repeats after a process restart. Store the day in prefs.
- **L6.** Duration formatting:
  - "Can stay paused 0 more min" under a minute (`PlayerText.minutesDown`): use "less than a minute".
  - "rewind holds 60 min" and "120 min": use "1 hour" and "2 hours", as the settings row does.
  - Use plurals resources for "%d hours".
- **L7.** The quick bar's "Pause" item resumes when a plain pause is on (`PlayerStateMachineImpl.kt:797`). Label it "Play" while `tuned.phase == PAUSED`.
- **L8.** At the moment of a pause, the pill reads "PAUSED · 0:0x behind", not "0:00". The P edge is device now, while the playhead is the frame's arrival time, so the player's latency is included. Check this on the device; if it shows, anchor "behind" to `P(newest PTS) − liveLandingMs`.
- **L9.** `keepScreenOn` holds the guide (mini player) on for the whole saving hold with no dim, because dim is FULL-only. Dim the mini player too, or release keep-awake outside FULL after `PAUSED_DIM_AFTER_MS`.
- **L10.** M5 §3.3.13 and the Playback table (`m5-setup-settings.md:1487`) still mark `playback.on_launch` as S. This is WP-D's amendment. No test or device test depends on its visibility: `WpaPagesTest.kt:63` is already ADVANCED, and `GuideViewModelTest.onLaunchModes` sets the pref directly.
- **L11.** U-N2 (the skip animation) is not done (deviation 12).
- **L12.** In `newLocalSession`, `s.frames` is overwritten after `adopt` (`ArchivePlaybackImpl.kt:1246-1248`). This is harmless today because only `displayShiftMs` is read, but it is a trap. Set only the display shift.
- **L13.** `LocalSeekResult.Unavailable` ends the session silently (`ArchivePlaybackImpl.kt:1351`). Add a REWIND_STOPPED_* toast.

---

## §7: the WP-C "contract problems"

| Problem | Decision | Change |
|---|---|---|
| `LocalBufferInfo` lacks `windowMs`, the hold limit, `stoppedAt` | **Now** (fixes S1, S2 and the A-C1 breach in the VM) | `LocalBufferInfo` gains `windowMs: Long = 0, holdMs: Long? = null, stoppedAtMs: Long? = null, cycleId: Long = 0` (P frame, defaults keep callers compiling) |
| `PlayerUiState` has no dim or media-key state | **Now** (fixes S4's header-without-bar; removes the `machine as? PlayerStateMachineImpl` cast at `PlayerViewModel.kt:189`, which silently disables the card for any other machine) | `PlayerUiState.paused: PausedUi? = null` with `dimmed, dimSinceMs, pausedSinceMs, mediaKeys, header: PausedHeader?`; `TimeshiftUi` goes |
| `ArchiveNotice` has no clamp reasons | **Later** (M6 hardening). Today two routers, the machine's `clampToast` and the impl's `route`, decide the same clamp from slightly different snapshots. They agree in the tests, and the risk is a toast without a clamp. | `ArchiveNotice.Clamped(reason: ClampReason, landedMs: Long)` from `ArchivePlaybackImpl`; the machine's `clampToast` goes |
| `PlayerToast` args are unspecified | **Now in KDoc only; typed later** | Document the convention on `PlayerToast`: times and lengths as raw ms, the playlist name ("" = unnamed), several values joined by `PlayerText.ARG_SEP`. A typed `ToastArg` comes later |

---

## Missing tests

- **B1:** bare SELECT on a paused LOCAL session and on a provider pause → `archiveResume`; the dim wake, then OK, resumes. Add the `player_keys.csv` rows.
- **B2:** gap hand-off with ctx Failed, with `built == null`, and with no LOCAL session → a local skip and GAP_SKIPPED.
- **S2:** a paused ← commit keeps the zap confirm armed after 2 min, and `holdMs` stays right.
- **S3:** a router target inside a gap, with and without archive.
- **S4:** a Compose test that the HOLD_LIMIT and SLOT_LOST bodies fit the card (no ellipsis) at 1920 × 1080, and that the header shows with the bar closed.
- **S9:** re-tuning the same identity shows REWIND_NOTHING_YET within 5 s.
- **Key table columns.**
  - `buf_arch` SEEK_BACK and quick-bar SELECT.
  - Z, ZI and L Up/Down settings on `buf` and `buf_paused`.
  - The NOT_TS and STORAGE_ERROR pause rows.
  - The unknown-buffer pause and rewind rows.
  - Dimmed + BACK and dimmed + DIGIT.
  - MEDIA_STOP while LOCAL.
- **VM:** LEFT_APP_CLEARED once (§17.3 lists it; `TimeshiftViewModelTest` has no case); BACK_TO_LIVE_UNBUFFERED with its step (S6).
- **Backup:** a JVM round trip of `timeshift`, `timeshiftHold` and `storageProtection` through `BackupExporter`/`BackupRestorer` (§12 "Backups carry the three values").
- **Five-key:** the `PAUSED_LOCAL` start state and the LIVE_BUFFERED → LOCAL transition (S10).

---

## Fix pass — product (2026-10-19)

This pass covers B1, B2 (product side), S1–S9, S11 and the WP-D dim/zap question. Each claim was checked in the code before it was fixed; every one held. Nothing is committed and no emulator was used. The design's "Implementation record — fix pass, product half" lists the contract and design changes.

**Build:** `./gradlew :core:test :core:stateOnRealNavigator :ui:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug` is **green**, nothing skipped.

| Task | Tests |
|---|---|
| `:core:test` | 3,442 |
| `:core:stateOnRealNavigator` | 358 |
| `:ui:testDebugUnitTest` | 360 (356 at WP-C) |
| `:app:testDebugUnitTest` | 69 (65 at WP-C) |

### Fixed

| Item | Fix | Tests |
|---|---|---|
| **B1** | `bareKey` SELECT: `archivePaused()` → `archiveResume()` (LOCAL and provider pauses, PAUSED and PAUSED_LIVE); stopped → retry; else the channel list (playing behind live and the M2 plain freeze keep the list, §11.6). Covers the unfocused bar after a media-key pause, the bar closed with Back, and the key after the dim wake | `TimeshiftMachineTest.dimCardAndTheConsumedKey` (rewritten: the second OK now resumes), `okOnAPausedTimeShiftWithTheBarUnfocusedOrClosedContinues`; key-table rows `buf_paused`, `buf_paused_closed`, `archive_paused`, `live_paused` + SELECT → `archiveResume`, `buf_tshift`/`plain_paused` + SELECT → list. `buf_paused_long_list*` now open the list with CHANNEL_LIST |
| **S10** | Five-key proof: new start state `PAUSED_LOCAL` (quick bar Pause, OK, Back; QUICK_MENU opens the quick bar so the setup works under every Up/Down value). `archiveResume` and `jumpToLive` are reached under every `UpDownInPlayer`, and the very first OK resumes. `liveBuffered` asserts that the five-key Pause lands in a LOCAL PAUSED state | `PlayerFiveKeyReachabilityTest.pausedLocal` ×4, `liveBuffered` ×4 |
| **B2** | `ArchivePlaybackImpl.onLocalGap` calls `controller.skipGapLocal()` when there is no LOCAL session for the identity; `providerFromLocal` takes an `onFail` that runs on a failed context load and on `built == null`; the gap path passes `skipGapLocal()` | `LocalTimelineBackendTest.gapHandOffFallsBackWhenTheProviderFails` (no session, ctx Failed, built null) |
| **S1** | `LocalBufferInfo` gains `windowMs`, `holdMs`, `stoppedAtMs` (P) and `cycleId`, filled in `LocalTimelineBackend.publish`. The machine's REWIND_WINDOW width is `windowMs` (fallback: the saved span, at least 1 min); `maxSavedSpanMs` and `ScrubModel.rewindWindowMs` are gone. The VM reads only `archive.localBuffer` (no `controller.localBuffer`) | `theWindowWidthComesFromTheBufferAndNeverRescales` (22 min "limited" bar stays 22 min), `theBufferInfoCarriesWindowHoldStopAndCycle`, VM `pausedFacts`, `toastValuesBecomeWords` |
| **§7 contract** | `PlayerUiState.paused: PausedUi?` (`pausedSinceMs`, `dimmed`, `dimSinceMs`, `mediaKeys`, `header`). The VM's `machine as? PlayerStateMachineImpl` cast is gone; `FullScreenHost` takes dim/media keys from `ui.paused`. `PlayerToast`'s argument convention is in its KDoc, with `PLAYER_TOAST_ARG_SEP` in `:core` (`PlayerText.ARG_SEP` aliases it) | VM `pausedFacts` |
| **S2** | `syncLocalPause` keeps the pause start through a SEEKING that follows a LOCAL pause, so a ← nudge never restarts the zap confirm's 2 min or the dim base. "Saving stopped after N min" is `LocalBufferInfo.holdMs`, not `stoppedAt − pausedSince` | `aSeekWhilePausedKeepsThePauseClock`, VM `pausedFacts` (holdMs) |
| **S3** | `TimelineRouterImpl.route`: a target in `LocalWindow.gaps` → Provider at the target (catch-up), else Local at the gap's end (ceil), or Live when the gap reaches the edge. `ArchivePlaybackImpl.localSeekTo` falls back to the gap's end if that provider hand-off fails. The machine toasts GAP_SKIPPED for a no-catch-up scrub into a gap (PAUSED when the range is ≥ `GAP_PROVIDER_MIN_MS`, else STREAM) | `TimelineRouterTest.targetInsideAGap`, `LocalTimelineBackendTest.aSeekIntoANotSavedRangeLandsAtItsEnd`, `TimeshiftMachineTest.aScrubIntoANotSavedRangeToastsTheSkip` |
| **S4** | `PausedCard`: body `maxLines = 3`, `CARD_H` 216 (drift test re-run), header from `ui.paused.header`, so a stopped pause with the bar closed still shows its reason and next step | `TimeshiftUiTest.theCardShowsTheWholeHeaderLineWithItsNextStep` (HOLD_LIMIT, SLOT_LOST, FULL with "Press OK or ▶": no overflow, ≤ 3 lines, card ≤ 216 px), `pausedCardDrawsOnlyWhenDimmed…` (budget), `thePausedHeaderIsThereWithTheBarClosed` |
| **S5** | Verified: `settings.advanced` is the sections pane's pinned toggle (`SettingsMachineImpl.ADVANCED_ROW`); `playback.timeshift` is SIMPLE, `playback.timeshift_protect` and `playlist.stream_format` are ADVANCED. Strings now read "Settings → Show advanced settings, then Playback → Protect built-in storage" and "… then Playlists → (playlist) → Stream format". "Settings → Playback → Pause and rewind" stays (simple row) | VM `noRewindToastsHaveAReasonAndAFollowableStep`, `toastValuesBecomeWords` |
| **S6** | Every PAUSE_NO_REWIND_* string is split into a reason and a step (`player_reason_*`, `player_step_*`, joined by `player_toast_two_parts`). The VM remembers the step with the identity and tune id of the toast; BACK_TO_LIVE_UNBUFFERED appends it once, for the same channel | VM `backToLiveUnbufferedCarriesTheStepOncePerTune` |
| **S7** | `noRewindToast`: NOT_TS → new `PAUSE_NO_REWIND_FORMAT`; STORAGE_ERROR on a pause → new `PAUSE_NO_REWIND_STORAGE` ("so Play returns to live"; RW keeps REWIND_STOPPED_STORAGE); unknown / no reason: pause silent (the controller pauses a buffered cycle in place), RW and TIME_SHIFT → REWIND_NOTHING_YET, FF silent. NO_CATCHUP never answers a pause or rewind | `everyBufferStateHasItsReason`; key rows `buf_notts`, `buf_storage`, `plain` (old NO_CATCHUP rows updated) |
| **S8** | The machine remembers the cycle's first saved instant. REWIND_START keeps "when you tuned in" only within `LOCAL_MARGIN_MS` of it, else passes the window and reads "(21:02: rewind holds 30 min)" (`player_toast_rewind_start_window`) | `rewindStartSaysWhenYouTunedInOnlyWhenTrue`, VM `toastValuesBecomeWords` |
| **S9** | A new `cycleId` restarts the machine's `tunedSinceMs` and the backend's `identitySinceMs`; the backend clears `framesFor` (and the cycle) when the snapshot is null | `aNewCycleOnTheSameChannelRestartsTheTuneClock`, `LocalTimelineBackendTest.aNewCycleRestartsTheTuneClock` |
| **S11** | Window mode: live clock 24 px, programme title and centre time 22 px (both above the 2026-10-17 ≥ 20 px ruling). The title is cut with `breakText` to end 16 px before the live time (width of "88:88", measured once per typeface, so still nothing allocated per frame) | `ScrubWindowLegibilityTest` (native text widths), `rewindWindowMovesWithTheEdgeAndAllocatesNothing` |
| **WP-D question** | When dimmed, a single-key zap (CH±, Last, Up/Down under "Change channel") that wakes the picture also runs the zap confirm in the same press (dim implies ≥ 2 min paused). The second press zaps. Other keys only wake | `theZapKeyThatWakesThePictureAlsoAsks`; key rows `buf_dim` + ZAP_UP (ZAP_CONFIRM), BACK, DIGIT, PLAY_PAUSE |
| Key-table columns | Added: `buf_arch` SEEK_BACK and quick-bar SELECT, Z/ZI/L on `buf` and `buf_paused`, NOT_TS and STORAGE_ERROR pause and RW, the unknown-buffer pause and RW, dimmed BACK and DIGIT, MEDIA_STOP (JUMP_TO_LIVE) while paused LOCAL | `PlayerKeysTableTest` |

### Deferred, with reasons

- **L1, L2 (the guide's `zapNeedsConfirm` double call, the shared CH±/digit window):** unchanged. They need the split `zapGate`/`confirmZap` contract; the guide files were in scope for the zap confirm only, and nothing in this pass needed them.
- **The unknown-buffer pause (S7, first half):** still a silent `pausePlain`. It freezes in place on a buffered cycle and resumes in place, so no toast is honest; adopting it as a LOCAL session once the buffer state arrives needs an `ArchivePlayback` change (not in this pass's files). B2's `skipGapLocal` covers the stuck-at-gap consequence.
- **`ArchiveNotice.Clamped` (§7 row 3):** later, as the review says.
- **Typed `ToastArg`:** later; the convention is now in `PlayerToast`'s KDoc.
- **The GAP_SKIPPED kind for a scrub into a gap** is a heuristic (≥ 30 s → "while paused"). An exact kind needs the gap's cause in `LocalBufferInfo.notSaved`, which `TimeRanges` does not carry.
- **The old-than-buffer provider seek that fails** (not a gap) still only settles in place. It is not a dead end: the playhead stays where it was, and the viewer can seek again.
- **S11 vertical fit:** the 22–24 px title and live time sit above the track (baseline 10 px over it). Whether they meet the paused header line (y ≈ 912–944) needs the device (it was already on WP-C's device list).
- **L3–L13:** untouched.
- **Device suite:** `TimeShiftBufferSupport.tsUi()` still reads `PlayerStateMachineImpl.timeshiftUi`, which is kept as a `@Deprecated` shim derived from the same state. The lead moves it to `ui.paused` and deletes the shim. No device case presses OK on a paused session, so B1 changes no device expectation; I-TS-29a/b wake with BACK first and still pass the two-press confirm.
