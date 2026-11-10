# Launch into full screen, hold Back from anywhere in the guide, no audio gap around Settings

Date: 2026-11-10.
- Three owner-reported bugs, fixed together. The decisions below are the implementer's. The lead should confirm the two marked "choice".
- Behaviour that changes from the design: `docs/design/m3-guide.md` §3.13.2 ("SEARCH/SETTINGS → HIDDEN") and `docs/design/m5-setup-settings.md` §3.3 visual notes ("SETTINGS … covers the hole (viewport HIDDEN, audio continues)"). The viewport is now the one of the screen underneath (item 3). The design docs are not edited. This note records the change.

## 1. "On launch: Full screen, last channel" flashed the guide

Owner (verbatim): "when opening with last channel full screen as the setting it flashes the guide for a second before going to the full screen."

**Cause.** The boot blank (owner ruling 2026-10-17) ends on the startup decision: `StartGuide`, the first active `Tuned` after `StartFull`, setup, or a key. `GuideStateMachineImpl.onTuned` also ended it on **any** `Tuned` whose phase was not IDLE (`if (state.phase != MiniPhase.IDLE) endBoot()`, `GuideStateMachineImpl.kt:1902` before this fix).

On a warm launch the process outlived Exit, so the controller is still `Stopped` on the last channel (USER from Exit, BACKGROUND from Home). The view model's `Tuned` feed forwards that leftover STOPPED (or ERROR) state at once. `startup()` meanwhile awaits the settings store, the first-run query and `library.lastWatched()`.

On a box where those reads are slower than the feed, the leftover STOPPED reached the machine first and ended the blank. The guide then drew until the startup tune connected, `StartFull`'s pending FULL fired, and the GUIDE → FULL scrim crossing ran. On the emulator the reads finish before the first frame, so the flash does not show there. `GuideViewModelTest.aWarmLaunchIntoFullKeepsTheBlankOverTheStoppedSession` reproduces the order on the JVM.

**Fix.** `onTuned` ends the blank only for a live phase: CONNECTING, BUFFERING, RECONNECTING, PLAYING or PAUSED. STOPPED and ERROR during the boot belong to the previous session.
- Nothing else changes. `StartGuide` still ends the blank at once. `StartFull` still ends it through `goFull` on the tune's first active (or ERROR) `Tuned`. A key, Settings or Search still end it.
- A recreated activity over a playing controller (`Tuned` PLAYING) still ends it on its own.
- If there is no last channel, the channel is gone or it is locked, `startup()` sends `StartGuide` (`lastWatched()` returns null, or the guard refuses and opens the PIN prompt above the blank).
- `BOOT_BLANK_MAX_MS` (8 s) stays as the safety net.
- Nothing is read on the main thread.

Cold start and process death: the controller is Idle, so there is no leftover state and the behaviour is unchanged. Warm return from Home with the activity alive: `startup()` does not run again and the screen stays where it was.

Known gap, not fixed: if the leftover state is ERROR, `StartFull` finds `somethingTuned()` true and goes FULL at once. The previous error card can then show in FULL until the new tune's CONNECTING replaces it. This is FULL, not the guide, and it is rare (Exit while a channel had failed).

## 2. Hold Back goes to full screen from every part of the guide

Owner (verbatim): "when in the guide and in any section but the grid you should still be able to long press back to go to the full screen, right now it just does it when in the guide grid."

**Cause.** The key map binds long BACK (and long BUTTON_B) to `PLAY` in the GUIDE context (owner ruling 2026-10-17). The router delivers it the same way from every zone. Only `gridKey` acted on `PLAY` (`if (somethingTuned()) goFull()`).
- `tabsKey` and `headerKey` passed it to `commonGuideKey`, which ignores it.
- Groups (and its menu), Days, the channel menu and the programme sheet ignored it in `overlayKey`.
- Number entry committed the number and let it fall through.

**Fix.** `GuideStateMachineImpl.act` handles `PLAY` in GUIDE mode before the overlays and zones, in `guidePlay()`. If something is tuned (the grid's condition), it closes any transient overlay and goes FULL. If nothing is tuned, nothing happens.

Edge cases (choice):
- **Transient overlays** close and go FULL: Groups and its menu, Days, Menu, Sheet, the exit hint. These are guide UI, and holding Back means "back to the picture".
- **Number entry** is dropped, not committed. Back means cancel there, and a long Back must not change channel.
- **The exit dialog** ("Exit The Box?", modal) keeps its keys. It asked a question, and holding Back does nothing there.
- **The PIN pad and the reminder card** are modal and take the key before this, as before.
- **The update card** passes `PLAY` on (it is not one of its keys), so the guide goes FULL and the card waits in the guide.
- **Search, Sports and Settings** are separate screens. They are unchanged: a long Back there does nothing, and the router swallows the short press after the long one.

Short Back keeps its meaning everywhere: the router sends a short press only on key-up when no long press fired. A long press is delivered once (the 500 ms timer or the first repeat, whichever comes first), and the key-up after it is swallowed. This applies to both Android TV and Fire TV remotes: the timer covers remotes that never auto-repeat.

## 3. The audio dropped for about a second when Settings opened from the guide and on the way back

Owner (verbatim): "when in the guide and selecting setting the audio disappears for a second, same when leaving the setting and going back to the guide."

Nothing in the design stops playback in Settings. m5 says "audio continues", so this was a defect, not a decision.

**Cause.** SETTINGS covers the hole: `holeModeFor` returns COVERED (`AppRoot.kt:87`), and `viewportFor(COVERED)` was `Viewport.HIDDEN` (`AppRoot.kt:94`). `PlaybackControllerImpl.setViewport` → `applyVideoTrackState` (`PlaybackControllerImpl.kt:2048-2057`) then disables the video track type on the way in and enables it again on the way out. Each toggle is a track re-selection on the playing period. Leaving FULL also schedules the frame-rate-match revert.
- On the way back, the re-enabled video stream has to find a keyframe at the playhead. On a live stream whose disabled video queue was discarded, `ProgressiveMediaPeriod.selectTracks` then seeks, which resets every renderer, the audio included.
- With tunneling on, either toggle also changes the audio renderer's configuration, which rebuilds the `AudioTrack`.
- Nothing else touches playback on this path. `onLeave` re-prepares only when a REPREPARE row changed, `openSettings` is a no-op for the player, and the surface stays attached.

On the emulator the toggle is visible in the log: the video surface disconnects on opening Settings, and a new H.264 decoder is created on leaving. The emulator's software decoders do not show the audible gap.

**Fix.** An opaque screen (SETTINGS, and SEARCH and SPORTS, which share the mapping: choice) keeps the viewport of the screen underneath: MINI over a video mini player, HIDDEN over a still, FULL over full screen. This is `viewportFor(hole, screenOff, beneath)` in `AppRoot.kt`, with the last uncovered viewport remembered.
- The video track is never toggled, so there is no re-selection and no audio gap.
- Over full screen, the display mode no longer reverts while Settings is open.
- The picture keeps decoding into the parked 1 × 1 surface behind the opaque screen. The cost is one hardware decode while Settings is open, the same as the mini player in the guide.
- The player's screen-off (COVERED with the player's SCREEN_OFF layer) keeps HIDDEN, as before.
- A Settings that opens with nothing beneath (first run) is HIDDEN.
- A real REPREPARE change (buffer, decoder, audio output, …) still re-prepares once on leaving, as designed. That gap is expected.

## Tests

- `GuideFullAttachTest.aLeftoverStoppedTunedKeepsTheBootBlankUntilStartFullLands` (core).
- `GuideViewModelTest.aWarmLaunchIntoFullKeepsTheBlankOverTheStoppedSession` (ui, real machine; fails without the fix).
- `keys.csv`: 14 new rows for PLAY from tabs, header, Groups and its menu, Days, Menu, Sheet, number entry, the exit hint and dialog, and the grid with nothing tuned, plus the `exit_hint_tuned` precondition.
- `RootTreeRuleTest.anOpaqueScreenOverTheGuideKeepsTheMiniViewport` and `.settingsOverFullKeepsTheFullViewport`.
