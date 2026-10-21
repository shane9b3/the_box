# M4 "Full-screen player UX": design

Status: **proposed 2026-10-15; reviewed 2026-10-15** (`review/m4-design-review.md`: 4 blockers B1–B4 and 11 should-fix items S1–S11 applied in place, each marked "(reviewed 2026-10-15, Bn/Sn: …)"; section F's contract text F1–F5, F7 and F9 used verbatim, F6 adapted and F8 superseded by the owner ruling on player keys and the quick bar (`docs/decisions/2026-10-15-player-keys-and-quick-bar.md`, folded into the same pass); nice-to-have items N1–N17 applied where cheap and marked Nn, the rest listed in §7.2 with a reason; lead decisions E1–E26 recorded in the sign-off and §7). The lead commits commit 0 (§1.7) and starts the five work packages.
Inputs: `00-master-plan.md` §2 (Player, Catch-up, Resilience rows), §3 (every row), §4, §6, §8 (M4 row); `01-design-reference.md`; `review/product-review.md` §2.1–§2.4 (S8 and the overlay table), §3.7–§3.9, §4.4, §4.7, §4.12; `decisions/2026-10-14-settings-and-branding.md`; `design/m3-guide.md` (sign-off, §0–§2.13, §3.4, §3.9.4, §3.13, §3.16, §3.18, §5, Appendix B); `design/m2-playback-core.md` §0, §2.3, §2.7, §3.1, §3.6, §3.9.5, §3.11–§3.14, §4; `review/architecture-review.md` B5.1–B5.3, D10; `review/m2-code-review.md` and `review/m3-code-review.md` "Later" lists; `research/protocols-m3u-xmltv-xtream.md` §3.3, §4, §5.3; the code on `main` at `2182858`. Library, platform, catch-up URL and fake-panel facts come from **`docs/design/m4-media3-ui-api-notes.md`** (cited as "API notes §n"), which landed while this was written. Anything neither verified there nor in the code is marked **(verify)** with an owner in §8.

This document is the contract between five implementers who will not see the conversation that produced it. **Must** means a reviewer rejects code that does otherwise. Every number (timeout, size, step) is a default living in one named constant (Appendix B).

## Lead sign-off (2026-10-15)

Approved after the design review (`docs/review/m4-design-review.md`). All blockers and should-fix items are applied below. Lead decisions:
- **B1** accept: `ArchiveVisibility` (F2, F3) is owned by the archive session and used by both view models and the mini player. `ArchiveStatus.ownedTuneId` is set in the same main-thread turn as `tuneArchive` (§3.8.2 says exactly how), `TunedState.tuneId` comes from the mapper, and WP-B keeps the invariant that an archive `Error` it does not act on turns the session FAILED in the same main-thread turn.
- **B2** accept: `liveEdge = deviceNow + skew + correction` (F1). The `CatchupUrlBuilderImpl` "not started" check moves to the corrected start, as a commit-0 edit of the M1 file by the lead (§1.7 item 9). §5.2's frames row is replaced as the review words it. E24 (meaning of a positive correction on real providers) is recorded for the M6 real-provider check.
- **B3** accept: seek sequence token, `ArchiveStatus.settledSeq`, the settle rules (native: 250 ms minimum, 5 s maximum; re-request: first media), `PlayerEffects.archiveSeek(programmeMs, seq)`.
- **B4** accept the finding; the fix is superseded by the owner ruling below: D-pad Up on bare video opens the **quick bar** in every state (live, archive, time-shift), so the five-key route to audio, subtitles, aspect, favourite and stream info in catch-up is "Back to bare video → Up"; the Options chip is dropped. The reachability test still runs from four start states with the "no state of another kind" rule (F6, adapted in §3.1.6).
- **Owner ruling** (`docs/decisions/2026-10-15-player-keys-and-quick-bar.md`, folded into this pass): in FULL, CH+ = +1 (higher number), CH− = −1, MEDIA_NEXT/PREVIOUS and PAGE_UP/DOWN follow; **D-pad Up opens the quick bar** (a translucent horizontal row at the bottom, no blur: audio, aspect, subtitles/CC, favourite, start over / jump to live, pause/rewind, previous channel, stream info, video decoder, screen off, Settings), **D-pad Down opens the active info bar**; ← channel list, → info bar, OK channel list stay as product S8. "Up/Down in player" becomes Quick bar and info bar (default) / Change channel / Open channel list; "Invert channel direction" applies only under Change channel and only to the D-pad. In the scrub bar Up stays jump to live. The guide is unchanged, and the M3 tables were checked: in GUIDE, D-pad Up moves to the row above (lower number) and CH+/PAGE_UP page six rows towards the lower numbers (`GuideStateMachineImpl.kt:343,455`), CH± in the Groups panel step to the previous/next entry (`:700-702`); only FULL's `zap(−1)` for UP/ZAP_UP (`:491-492`) changes. Trade-off (§8 Q13): remotes without CH± zap from FULL through the channel list, the guide, or the Change channel setting.
- **S1** accept: continuation snaps to live within 5 s, user seeks within 30 s. **S2** accept: hand-offs carry an unremembered aspect, the track choice, an open picker and pause; a re-request seek committed while paused stays paused (E26). **S3** accept: a variant fails on 404/410, and on 400/403 on the first open; a lag 404 never counts; the mid-play retry has an explicit code list; connection-limit on a re-request is handled by the ladder's existing rows L2–L4 (the review's extra wait-and-retry row is not added, §3.8.2). **S4** accept (F4: `around`/`ProgrammeSlice`). **S5** accept: `KeyContext` stays `{GUIDE, PLAYER}`; `GuideStateMachine.horizontalRepeat` (F9). **S6** accept: MEDIA_NEXT/PREVIOUS and PAGE_UP/DOWN follow the zap direction. **S7** accept: the passive bar shows after every tune in FULL, including number entry and a reminder switch; OK on a future programme sets a reminder. **S8** accept (F5; DNS_FAILURE gets no Retry; Software decoder hidden when already on or for audio; an archive Retry resumes at the playhead). **S9** accept: commit 0 must compile, so the exhaustive `when`s in `AppRoot` and `GuideStateMachineImpl` get their new branches in commit 0, and the `full` attach order is specified (attached before the first `act`). **S10** accept: a composed guide + player no-dead-ends proof. **S11** accept.
- **§7 items:** 1 (a) `SubtitleView`; (b) is the named fallback if `media3-ui` 1.11.1 does not resolve in commit 0; N15's focus settings. 2 accept with S5 and S10. 3: CH+ / MEDIA_NEXT / PAGE_UP = +1 (higher number) is settled; the D-pad question was answered by the owner the same day (above): Up = quick bar, Down = info bar by default; under "Change channel" D-pad Up = +1 (`DPAD_UP_ZAP_DELTA`), inverted by the setting. 4 accept (←/→ scrub on bare archive video). 5 not in M4: D10 is the trigger, I-M4-13's 150 s run is the measure. 6 defer to v1.1. 7 accept with B2 and N3. 8 accept with F5. 9, 10 (with S7), 11, 12 accept. 13 accept with S3. 14 accept, speculative captions sorted last. 15 accept. 16 `QuickItem.SCREEN_OFF` in M4. 17 accept with S6. 18 accept. Review additions: 19 accept (B1). 20 accept (B2). 21 scrub steps 10 s, 30 s, 120 s, then 300 s after 10 s held. 22 accept (S1). 23 a read-only "Server: n of m" line in Stream info in M4; the picker is M5 with the backup editor; the "v1.1" label is dropped. 24 recorded for M6. 25 superseded by the owner's quick bar (no Options chip). 26 stay paused.
- **Not verified, owners:** §8 lists them (in-buffer seek emits no state → WP-B, I-M4-22; how panels reject timeshift forms → lead with a real provider, D10; positive correction meaning → lead, M6; `SubtitleView` focus inside `AndroidView` → WP-D; `AndroidView` layer → WP-D; panel DST behaviour → lead; TiviMate D-pad direction → owner's parity check).

Units: every size is a **design px** (1 design px = window width ÷ 1920 through `BoxDesignScale`, `ui/src/main/kotlin/app/thebox/ui/theme/BoxDesignScale.kt:41-66`) unless it says "device px". Times: `…Ms` = milliseconds, `…Min` = epoch minutes (`Int`), as in M3. Archive times use the three frames of §3.8.1 and are always `Long` epoch milliseconds.

---

## 0. Key decisions at a glance

1. **A sibling `PlayerStateMachine` in `:core` (`app.thebox.core.full`), composed into the M3 `GuideStateMachine` for FULL.** The guide machine keeps the mode (`ScreenMode`), the Back ladder across modes, the reminder card, number entry and the Exit overlay; in FULL it hands every other action to `full.act(…)` first, and runs its own FULL code only for what the player machine returns `false` for (Back with nothing open, OPEN_GUIDE, EXIT, GROUP_PREV/NEXT). The router, the one key sink (M3 B1) and the single `Act` path are unchanged. *Why:* `GuideStateMachineImpl` is already 1,597 lines (`core/.../guide/state/GuideStateMachineImpl.kt`); FULL adds about as much again (six overlays, the channel list with its own lineup, the scrub bar), with almost no shared state beyond `TunedState` and the lineup. A sibling is JVM-testable by enumeration on its own key table (§5.1) and keeps the M3 tables (`keys.csv`, 227 real-navigator tests) stable. *Rejected:* extending the guide machine (one 3,000-line file, one owner, every M3 test re-baselined); a second key sink or a router that switches machines (breaks B1's "one sink, survives every mode change" and splits the Back ladder).
2. **Catch-up is two layers: a pure `ArchivePlanner` (`:core`) and a stateful `ArchivePlaybackImpl` (`:app`)** that executes plans against `PlaybackController.tuneArchive/seekTo/position`, `ArchiveTargets`, `CatchupUrlBuilder`, the playlist `TimeBase` and a new `CatchupProbeStore`. The planner owns every time computation (three frames: archive, programme, display; §3.8.1), native-seek-or-re-request, minute alignment for `xc`, continuation, live snap and variant probing, so all of it is table-tested on the JVM. `ArchivePlayback.status` (a `StateFlow<ArchiveStatus>`) is the one source both machines read. M3's `CatchupLauncherImpl` becomes a thin adapter over it. *Rejected:* putting the timeline in the UI view model (untestable without Android) or in `PlaybackControllerImpl` (M2's 1,962-line controller would gain a catalogue of URL modes it was deliberately kept out of, M2 §3.14).
3. **Native seek is gated by `PositionSnapshot.isSeekable` and the request's span, not only by `nativeSeekRange`.** `nativeSeekRange` is null for Xtream `.ts` timeshift even though such a file with `Content-Length` and PCRs is seekable through `TsBinarySearchSeeker` (API notes §2.1, §6; M2 §3.14 S8). A target inside `[origin, origin + durationMs)` of a seekable request is a `controller.seekTo`; anything else is a new request. Scrubbing never seeks continuously: the preview moves in the UI and **one** commit happens `SCRUB_COMMIT_MS` (600) after the last key (B5.3), because every non-native seek is a new connection.
4. **Continuation re-requests open-ended, never per programme.** The first request for a finished programme is the fixed range the builder makes (seekable for Flussonic HLS/TS ranges); every later request (seek outside the span, end of a request, resume after a live pause) is **open-ended** from the playhead, so it crosses programme boundaries (B5.1, M1 §3.8.5 "one request should not stop at a programme boundary"). The scrub bar re-scopes to the next programme from the timeline alone (no request at the boundary). The Xtream fixed-duration problem (`D = min(now − S, 180 min)`, `CatchupUrlBuilderImpl.kt:68`) is handled by re-requesting `CONTINUE_LEAD_MS` (3 s) before the request's end; a continuation that would land within `CONTINUE_SNAP_MS` (5 s) of the live edge goes live instead (user seeks snap within `LIVE_SNAP_MS`, 30 s) (reviewed 2026-10-15, S1: a 30 s snap on continuation silently dropped a shallow time-shift). Each re-request is one `tuneArchive`, one connection, with the last frame held (§3.8.6). *Rejected for M4:* a controller-owned two-item playlist or `ConcatenatingMediaSource2` for gapless hand-off (API notes §2.3): it changes M2's one-`MediaSource`-per-session lease model; revisit in v1.1 if beta reports the hand-off freeze (§7 item 6).
5. **Pause on live uses the archive.** Play/Pause on an archive-capable live channel freezes the picture through `controller.pause()` (M2 releases the connection after 10 s, `PlaybackControllerImpl.kt:1412-1435`) and records the pause point in the programme frame; resume after ≥ `PAUSE_MIN_FOR_ARCHIVE_MS` (3 s) plays an open-ended archive request from that point, shorter pauses resume live. RW on live enters time-shift `RW_ON_LIVE_BACK_MS` (30 s) back. Channels without archive keep M2's freeze-and-rejoin with the "Back to live" toast (product S8). Recent-archive lag (the panel has not written the last minute yet) gets one step back of `ARCHIVE_LAG_STEP_MS`, then live with a toast; it never triggers variant probing (§3.8.7).
   (Amended 2026-10-19, M6 time-shift.) On a buffered live TS channel, Play/Pause and RW now use the device's local time-shift buffer first (`ArchivePlayback`'s LOCAL source): pause is in place with no archive request, resume continues where it stopped, and the provider archive is used only beyond the buffer or for a gap it lost (catch-up channels). Channels without archive can pause and rewind too, and the quick bar's "Pause" item comes first on every live channel. This item still describes unbuffered channels (HLS, buffer Off, no space). See `design/m6-timeshift.md` §0 items 6–7, §9 and §11.
6. **Variant probing lives in `ArchivePlayback`, and only a variant that reached first media is persisted** (`playlist.catchup_probe_winner`, written through a new column-targeted `PlaylistDao.setCatchupProbeWinner`; today nothing writes it and the resolver passes `winner = null`, API notes §6, `StreamUrlResolverImpl.kt:82-83`). While the archive session owns playback, its intermediate controller states (probe errors, the `Connecting` of every hand-off) are hidden from both machines and the mini player by one pure rule, `ArchiveVisibility.visible(tuned, status)` (reviewed 2026-10-15, B1: they leaked as error-card flashes, closed layers and a "Connecting" pill every few minutes); the guide's hollow ↺ (`suspectArchive`) and the error card follow `ArchiveStatus.phase == FAILED` only (fixes an M3 behaviour that would mark the channel suspect on the first failed variant, `GuideStateMachineImpl.kt:1137-1142`).
7. **Auto-repeat for ←/→ without a third key context** (reviewed 2026-10-15, S5: a derived context leaked into the persisted, user-remappable key map). In `PLAYER` (bare live video) long ←/→ keep M3's group cycle, which makes ←/→ fire on key-up with no auto-repeat (`DefaultKeyMap.kt:21`, `KeyGestureDetectorImpl.kt:20-46`). Scrubbing and list browsing need auto-repeat, so whenever a player layer is open, or archive video is on screen, the machine raises `GuideStateMachine.horizontalRepeat` and the router ignores the long binding of D-pad LEFT/RIGHT for that key press. `KeyContext` stays `{GUIDE, PLAYER}` (F9).
8. **The info bar is one component with two modes.** *Passive*: shown at once on every zap (it *is* the zap card: number, logo, name, then the programme when the feed answers) and for `INFO_AFTER_ZAP_MS` (4 s) after first media; it takes no keys, so Up/Down keep zapping. *Active*: opened by ↓ (default), → or INFO_BAR; ↑/↓ browse this channel's programmes, ←/→ browse other channels' now, OK acts, auto-hides after the "Info bar duration" setting (3/5/8 s). In archive or time-shift the bottom bar is the **scrub bar** instead, with the same header. (Owner ruling 2026-10-15) D-pad ↑ on bare video opens the **quick bar**, a translucent row of player shortcuts at the bottom (§3.4.1), replacing the proposal's quick-menu side sheet.
9. **Subtitles: `media3-ui` `SubtitleView` in an `AndroidView`, a sibling over the video, FULL only, fed by a new `PlaybackController.cues` flow.** `media3-ui-compose` 1.11.1 has no cue rendering and the repo has no cue path at all (API notes §1.2). `SubtitleView` already lays out DVB bitmap cues (`position/line/size/bitmapHeight`, API notes §1.2) and CEA-608/708 windows; redrawing that in Compose is ~600 lines of `SubtitlePainter` we would own. The view sits after `HoleChrome` and before the key sink, sized to `videoRect`; it is never an ancestor of `VideoLayer`. Catalog gains `media3-ui` 1.11.1 (not yet in the Gradle cache, API notes §10: commit 0 resolves it). *Rejected:* own Compose cue drawing (cost and edge cases); subtitles in the mini player (unreadable at 720 px and they collide with the pills).
10. **Error cards render the ladder's own `PlaybackError`** (code, detail, narrowed `actions`, `retry`), carried to the UI through a new `TunedState.errorDetail` (also fixes M3 H-L9). Buttons exist only for steps M4 can execute (Retry, Previous channel, Watch live, Try other catch-up format, Software decoder, Audio options, Stop, Guide). Steps whose destination is an M5 screen (account, login, playlist settings, diagnostics, user agent, secure DNS, refresh, external player) are shown as the next-step *text line*, never as a button to a placeholder (plan §2 "no coming-soon stubs"; M3 H-L8). M5 turns them into buttons through one routing table (§3.7).
11. **Programme data for the player comes from a new `ProgrammeFeed`** (`:core`, its own `box-player-epg` thread and reader handle, generation-safe like M3's pager, `GuideWindowPagerImpl.kt:264-300`; queries share the generation's guide connection and its lock, `GenerationHandle.guide`, so each call stays one short use, N1), not from the guide's pager: the info bar needs a list per channel, the channel list needs "now" for 10–24 rows of another lineup, and the archive timeline needs `at(channel, minute)` while the guide may be scrolled elsewhere. The channel list pages its lineup with a second `GuideRowsCache` from the existing factory (32-row blocks, LRU 8; M3 §3.2.1). Nothing loads a lineup or an EPG whole.
12. **Player settings that exist in the product tree but not yet in code get one `:core` source, `PlayerSettingsSource`,** in memory in `:data` like M3's `InMemoryGuideSettings`; M5 persists and edits them: info bar duration, number-entry wait, Up/Down in player, invert channel direction, Back in player, on-launch mode (product §4.4, §4.7; none exists in the tree today, grep of `core data ui app player`).
13. **Every player action is a `BoxAction`.** M4 appends `PLAY`, `PAUSE`, `TOGGLE_FAVOURITE`, `VIDEO_DECODER`, `PREV_PROGRAMME`, `NEXT_PROGRAMME`, `TIME_SHIFT` (append-only ids, `InputContracts.kt:4-7`) and default bindings for keys the API notes list as unbound (`MEDIA_STOP` → JUMP_TO_LIVE, `MEDIA_SKIP_*`/`MEDIA_STEP_*` → seek, `MEDIA_PLAY`/`MEDIA_PAUSE` split; API notes §5, §8). Actions without a default key (`STATS_OVERLAY`, `SCREEN_OFF`, `NUMBER_ENTRY`, `QUICK_MENU`, …) are reachable through the quick menu (Stream info, Screen off: `QuickItem.SCREEN_OFF`, reviewed 2026-10-15, N7/E16) or the five-key paths of §3.1.6 and bindable in M5. CH+, MEDIA_NEXT and PAGE_UP all mean +1 (the higher number) (reviewed 2026-10-15, S6/E3).
14. **Zap keeps the old picture and its rect.** The controller already keeps the last frame (`player.stop()` without `clearVideoSurface`, M2 §3.1.1 step 2), but it resets `VideoInfo` to size 0 on every tune (`PlaybackControllerImpl.kt:452-454`), which makes `AppRoot` recompute `videoRect` and resize the `SurfaceView` under the frozen frame. The root therefore holds the previous video size and aspect until `renderedFirstFrames` advances, or the tune reaches first media or an error (§3.6; reviewed 2026-10-15, S2/N4). Whether every SoC really keeps the last buffer is unverified (API notes §2.5); M6 hardware decides (§8).

---

## 1. Work packages, files and ownership

### 1.1 Rules of engagement

Copied from M3 §1.1 and unchanged:

- **Commit 0 (lead, before any WP starts):** every contract in §2 verbatim, the `:player`/`:core` additive amendments (§2.9), the build changes (§6), the fakes (§1.8), and a stub (`TODO("WP-x")` body) for every implementation class named in §1.2–§1.6 at its final path, bound in Hilt. After commit 0, `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` is green.
- **No WP edits a file owned by another WP, a contract file or a build file.** Contract problems go to the lead, who amends commit 0 for everyone (additive, a default for every new parameter).
- **Packages are disjoint** (tables below). Tests live in each WP's own test packages. Paths abbreviate `core/src/main/kotlin/app/thebox/core` as `core/...`, likewise `ui/...`, `data/...`, `app/...`, `player/...`.
- **Hilt:** each WP owns the Hilt module that binds its implementations; commit 0 creates them with the stubs bound.
- **Models:** Opus for WP-A (state machine), WP-B (archive), WP-C (engine seams and feeds) and WP-D (shell); WP-E (verification and fake panel) is Opus for the integration pass, and may delegate the fake-panel media generation and the string resources to Sonnet under this spec.
- **No `runBlocking`, no `BoxLog` of a URL, no hard-coded user text** (M2 §6.7, M3 §6.3).

### 1.2 WP-A: player brain (state machine, input, guide delegation)

Scope: what every key does in FULL, the five-key proofs, and the M3 seams that route keys to it.

| File | Contents |
|---|---|
| `core/.../full/state/PlayerStateMachineImpl.kt` | Layers, every key table of §3.1, info-bar timing, channel-list cursor and paging requests, quick bar and pickers, error-card focus, scrub interaction (steps, hold acceleration, 600 ms commit), screen-off, back ladder (§3.1.5) |
| `core/.../full/state/ScrubModel.kt` | Pure step table, preview clamp, chip targets (§3.5.2) |
| `core/.../full/state/ChannelListModel.kt` | Cursor, `top`, accelerated holds, Previous row, page sizes (§3.3) |
| `core/.../full/state/ErrorCardModel.kt` | `PlaybackError` → buttons + text steps (§3.7 table) |
| `core/.../full/state/QuickBarModel.kt` | Which `QuickItem`s show, in order (§3.4.1; owner ruling 2026-10-15) |
| `core/.../full/state/PillRules.kt` | `PillState` for both machines and the mini player (§3.2.3) |
| `core/.../full/state/PlayerStateMachineFactoryImpl.kt` | `PlayerStateMachineFactory` |
| `core/.../guide/state/GuideStateMachineImpl.kt` (edit) | FULL delegation (§3.1.1), `horizontalRepeat` (S5), EXIT drawn in FULL (H-L4), `GuideEvent.Archive` (suspect ↺ and pending-archive FULL from `ArchiveStatus`, §0.6), `GuideEvent.Player` (number wait), `GuideEvent.StartFull` real body (commit 0 sets `pendingFull`), `GuideUiState.archive`, `enterFull` on attach when already FULL (S9) |
| `core/.../guide/state/NumberEntryImpl.kt` (edit) | `setCommitAfterMs` |
| `core/.../input/DefaultKeyMap.kt` (edit) | The new default bindings and the zap direction (§3.1.7; reviewed 2026-10-15, S6) |
| `ui/.../input/KeyRouterImpl.kt` (edit) | Ignores the long binding of D-pad LEFT/RIGHT when `machine.horizontalRepeat` is set, decided per held key next to `heldContext` (reviewed 2026-10-15, S5; the lookup at `:109`) |
| `ui/.../input/InputModule.kt` (edit) | binds `PlayerStateMachineFactory` |

Implements: `PlayerStateMachine`, `PlayerStateMachineFactory` (§2.1).
Tests (§5.1): `player_keys.csv` (one row per layer × action × relevant state), `PlayerFiveKeyReachabilityTest` from four start states (F6), `ComposedFullBackLadderTest` (S10), `KeyRouterImplTest` row for `horizontalRepeat` (S5), back ladder, info-bar timing with a fake clock, scrub step and commit table, error-card mapping completeness (all 25 codes × availability), pill table, guide-delegation tests (`GuideFullDelegationTest`), `DefaultKeyMapTest` additions; the M3 `stateOnRealNavigator` task must stay green.
Hand-off notes: the final `player_keys.csv`, any key whose behaviour differs from §3.1, exclusions of the five-key test.

### 1.3 WP-B: archive (planner, playback, launcher)

Scope: everything about catch-up after the user asked for it: frames, requests, seeks, continuation, live snap, pause-live, probing and persistence.

| File | Contents |
|---|---|
| `core/.../catchup/archive/ArchivePlannerImpl.kt` | Every pure decision of §3.8 |
| `app/.../playback/ArchivePlaybackImpl.kt` | The orchestrator (§3.8.2): owns the session, executes plans, observes `controller.state`, polls `position()` at `ARCHIVE_POLL_MS` while active, publishes `status` incl. `ownedTuneId` and `settledSeq` (B1, B3), carries per-session choices across hand-offs (S2) |
| `app/.../playback/CatchupLauncherImpl.kt` (rewrite) | Adapter: `CatchupLauncher.play` → `ArchivePlayback.playProgramme`, `ArchiveFailure` → `CatchupFailure` |
| `app/.../playback/ArchiveModule.kt` | Binds `ArchivePlanner`, `ArchivePlayback`, `CatchupLauncher` (moves the launcher binding out of `ReminderModule.kt:80-86`) |

Implements: `ArchivePlanner`, `ArchivePlayback` (§2.3).
Tests (§5.2): `ArchivePlannerTest` (tables: frames incl. corr = +2 h, alignment in the archive frame, seek plan, continuation snap 5 s vs seek snap 30 s, lag, probe triggers), `ArchivePlaybackImplTest` (fake controller with a scripted state flow, fake targets/builder/probe store/feed, virtual time), `CatchupLauncherImplTest` updated.
Hand-off notes: the request sequences the fake-panel suite should see for I-M4-9…I-M4-14 (§5.5).

### 1.4 WP-C: engine seams, feeds and stores

Scope: the `:player` behaviour behind the new contract members, the player's programme feed, the probe store and the player settings.

| File | Contents |
|---|---|
| `player/.../controller/PlaybackControllerImpl.kt` (edit) | `cues` (`Player.Listener.onCues(CueGroup)`, cleared on stop/tune), `previous`, `Playing.ended` on archive `STATE_ENDED` (`:910-915`), `VideoInfo.decoderOverride`, `StreamStats.serverIndex/serverCount` (E23), M2 E-L2 (pause while Connecting/Stalled), M2 E-L7 (`keepScreenOn` behind the unlimited-retry card) |
| `core/.../full/feed/ProgrammeFeedImpl.kt` | `ProgrammeFeed` over `EpgStore` (§3.3.3), incl. `around` (S4) |
| `core/.../guide/feed/ProgrammeInfos.kt` (new) + `GuideWindowPagerImpl.kt` (one-line edit) | Shared `ProgrammeDetail → ProgrammeInfo` mapping |
| `core/.../full/feed/StatsFormatter.kt` | `StatsFacts` → `List<StatLine>` (§3.4.4) |
| `core/.../playback/errors/ErrorLadderImpl.kt` (edit) + `ladder.csv` | Row L13a: archive `Http(400)` before first byte → CATCHUP_NOT_IN_ARCHIVE (reviewed 2026-10-15, S3; §3.8.8) |
| `data/.../db/dao/PlaylistDao.kt` (edit) | `setCatchupProbeWinner(id, value)` (column-targeted `UPDATE`) and `catchupProbeWinner(id)` |
| `data/.../repo/PlaylistRepository.kt` (edit) | `probeWinner(playlistId)`, `setProbeWinner(playlistId, variant)` |
| `data/.../player/CatchupProbeStoreImpl.kt` | `CatchupProbeStore` over the repository |
| `data/.../player/InMemoryPlayerSettings.kt` | `PlayerSettingsSource` (like `InMemoryGuideSettings`; M5 persists) |
| `data/.../di/PlayerDataModule.kt` | Binds `CatchupProbeStore`, `PlayerSettingsSource`, `ProgrammeFeed` (constructs `ProgrammeFeedImpl` with the `EpgStore`) |

Implements: `ProgrammeFeed`, `CatchupProbeStore`, `PlayerSettingsSource` and the `:player` members of §2.9.
Tests (§5.3): `ProgrammeFeedImplTest` (real `EpgStoreImpl` via the `EpgTestDb` fixture: `around` on 5-minute slots, range, nowFor, at, generation swap mid-call, patch, shift), `StatsFormatterTest`, `PlaybackControllerStateTest` additions (cues cleared on tune, `ended`, `previous`, E-L2, E-L7), Robolectric DAO test for the probe winner and that `PlaylistRepository.update` (whole-row) is not used by it (M2 N-L13).
Hand-off notes: whether `onCues` arrives on the application looper in the Robolectric real-player test; the DVB/608 behaviour seen on the fake panel's `multi` clip.

### 1.5 WP-D: shell (Compose, view model, glue)

Scope: everything drawn in FULL, the view model that feeds the player machine, and the M3 shell edits.

| File | Contents |
|---|---|
| `ui/.../full/FullScreenHost.kt` (rewrite) | Layer composition (§3.9.1), guide overlays still drawn here (group card, number entry, Exit hint/dialog, reminder card stays in `AppRoot`) |
| `ui/.../full/InfoBar.kt`, `ui/.../full/ScrubBar.kt`, `ui/.../full/ScrubBarRenderer.kt` | Info bar; scrub bar as one draw node with a preallocated `Paint` (§4.1) |
| `ui/.../full/ChannelListPanel.kt`, `ui/.../full/QuickBar.kt`, `ui/.../full/PickerSheet.kt`, `ui/.../full/StatsOverlay.kt`, `ui/.../full/PlayerErrorCard.kt`, `ui/.../full/ScreenOffLayer.kt` | Overlays (§3.9) |
| `ui/.../full/SubtitleLayer.kt` | `AndroidView(SubtitleView)` (§3.6.3) |
| `ui/.../full/PlayerUi.kt` | Geometry and colour tokens of §3.9 (design px) |
| `ui/.../full/PlayerText.kt` + `ui/src/main/res/values/strings_player.xml` | Every player string, notice toasts, track labels, stat labels, error buttons |
| `ui/.../full/vm/PlayerViewModel.kt`, `PlayerEffectsImpl.kt`, `PlayerMappers.kt`, `ChannelNowCache.kt` | Glue (§3.10) |
| `ui/.../root/AppRoot.kt` (edit) | Subtitle slot, sticky video size (§3.6.2), player shell events, toast lift over the bottom bar |
| `ui/.../root/ShellHost.kt` (edit) | `player: PlayerHost?` (§2.6) and `ShellEvent.Message` |
| `ui/.../guide/screen/MiniPlayerOverlay.kt` (edit) | PAUSED and −MM:SS pills from `PillRules` (§3.2.3); compact error card uses `errorDetail.actions` (H-L9) |
| `ui/.../guide/overlay/NumberEntryOverlay.kt` (edit) | Logo + number + now title in the preview; strings to resources (H-L7) |
| `ui/.../playback/ErrorText.kt` (edit) + `strings_errors.xml` (edit) | Detail-line format and button labels (§3.7) |
| `ui/.../guide/vm/GuideViewModel.kt` (edit) | `attachPlayer(machine)` before `startup()` (S9), forwards `Tuned` through `ArchiveVisibility` (B1), `ArchivePlayback.status` and `PlayerSettings` to the guide machine, on-launch mode (§3.10.3) |
| `ui/.../guide/vm/TunedStateMapper.kt` (edit) | `errorDetail`, `tuneId` (B1) |
| `ui/.../reminder/ReminderCard.kt` placement in `AppRoot` | Lifted over the bottom bar and left of the picker sheet in FULL (N8) |
| `ui/.../ambient/AmbientBackground.kt` (edit) | No hue work while FULL (M3 H-L5) |
| `app/.../MainActivity.kt` (edit) | Creates `PlayerViewModel`, passes it to `AppRoot` |

Implements: `PlayerEffects`, `PlayerHost` and the composables.
Tests (§5.4): Robolectric Compose: root-tree rule with `SubtitleLayer` (sibling, never an ancestor of `VideoLayer`, a direct child of the root `Box`, N11), `SubtitleView` never focusable (N15), `ScrubBarRenderer` against a recording canvas (op count, zero allocation per frame), sticky video size, overlay bounds table, `ErrorText` completeness for detail and buttons, `PlayerMappersTest` (TrackInfo/StreamStats/VideoInfo → core mirrors), `PlayerViewModelTest` (JVM, fakes; the B1 script of §5.4).
Hand-off notes: manual screenshots of every layer on the emulator; any `(verify)` API that behaved differently.

### 1.6 WP-E: verification, harness and fake panel

| File | Contents |
|---|---|
| `tools/fakepanel/fakepanel.py`, `catalog.py`, `README.md`, `test_fakepanel.py` (edits) | §5.6: continuous-timestamp timeshift sized by `duration`, out-of-archive 404, variant rejection with a configurable status, archive lag, live-shifted forms, HLS EVENT timeshift, Range-ignored mode, timeshift tokens, ranged-timeshift faults, correction channels (+2 h, −1.5 h), a `multi` clip, synthetic short-programme channel (S11) |
| `app/src/debug/kotlin/app/thebox/debug/GuideDebugReceiver.kt` (edit) | Player switches (`?infobar=`, `?numwait=`, `?updown=`, `?back=`, `?padding=`) |
| `ui/src/debug/kotlin/app/thebox/ui/full/PlayerDebugProbeImpl.kt` + release no-op | `PlayerDebugProbe` (§2.8) |
| `app/src/androidTest/kotlin/app/thebox/player/*` | Instrumented suite I-M4-1…I-M4-23 and I-M4-13b (§5.5) |
| `benchmark/src/main/kotlin/app/thebox/benchmark/PlayerOverlayBenchmark.kt` | §5.7 |

Tests: the instrumented suite and the fake-panel unit tests; the integration pass (§1.9 step 3) and its results section (§5.8).

### 1.7 Commit-0 list (lead)

1. **Contracts** §2.1–§2.8 verbatim: `core/.../full/PlayerContracts.kt`, `core/.../full/PlayerSettings.kt`, `core/.../full/ProgrammeFeed.kt` (with `around`/`ProgrammeSlice`, F4), `core/.../catchup/archive/ArchiveContracts.kt` (with F1, F2), `core/.../catchup/archive/ArchiveVisibility.kt` (F3, full body: it is a contract object), `ui/.../root/ShellHost.kt` amendment, `ui/.../full/PlayerHost.kt`, `ui/.../full/PlayerDebugProbe.kt`.
2. **Amendments** §2.9: `InputContracts.kt` (BoxAction additions, `Keys` additions; `KeyContext` unchanged, F9), `GuideStateContracts.kt` (`full`, `horizontalRepeat`, three events, `GuideUiState.archive`, `NumberEntry.setCommitAfterMs`), `GuideModel.kt` (`TunedState.errorDetail`, `tuneId`, `handoff`; `ProgrammeInfo.rawEndMin` extension, N6), `PlaybackApi.kt` (`cues`, `previous`, `Playing.ended`, `VideoInfo.decoderOverride`, `StreamStats.serverIndex/serverCount`), redacted `toString()` on `CatchupTarget`, `ArchiveRequest` and `XtreamStreamRef` (M2 N-L9: M4 passes them around).
3. **Build** §6: `media3-ui` in the catalog and `:ui`; `:core` no change; `:app` test deps unchanged. If `media3-ui` 1.11.1 does not resolve, stop and take §7 item 1 (b) before WP-D starts.
4. **Stubs** for every implementation class of §1.2–§1.6, bound in Hilt (`PlayerDataModule`, `ArchiveModule`, `InputModule` edit). `PlaybackControllerImpl` gets the trivial bodies of the new members (`cues` = empty flow, `previous` = null flow, `serverIndex/serverCount` from the resolved stream) so `:player` compiles; WP-C fills the rest.
5. **Fakes** in `core/src/testFixtures/kotlin/app/thebox/core/full/fakes/`: `FakePlayerStateMachine`, `RecordingPlayerEffects`, `FakeProgrammeFeed`, `FakePlayerSettings`, `FakeArchivePlayback` (scriptable `status`, records `seekTo(ms, seq)`), `FakeArchivePlanner` (records calls, returns scripted plans), `FakeCatchupProbeStore`, `PlayerFixtures` (programmes on a channel, track menus, stats, every `PlaybackErrorCode` as a `PlaybackError`). `:ui`/`:app` write their own fakes of `PlaybackController` (`:core` cannot see it; M3 §2.12 item 8).
6. **Remove the guide machine's FULL media passthrough** only in the contract KDoc of `handles` (the behaviour change is WP-A's).
7. (reviewed 2026-10-15, S9: commit 0 must compile) **No-op branches** for the new sealed members in the exhaustive `when`s: `AppRoot.kt:177-184` gets `is ShellEvent.Message -> Unit`; `GuideStateMachineImpl.dispatch` (`:199-222`) gets `is GuideEvent.Archive -> Unit`, `is GuideEvent.Player -> Unit` and `GuideEvent.StartFull -> pendingFull = true` (the one real line, so the event is not dead in the stub).
8. (reviewed 2026-10-15, S9/B1/B3) `TunedState.tuneId`/`handoff`, `ArchiveStatus.ownedTuneId`/`settledSeq`, `ArchivePlayback.seekTo(programmeMs, seq)`, `PlayerEffects.archiveSeek(programmeMs, seq)` and `toggleReminder` are in the contracts of items 1–2; `TunedStateMapper` fills `tuneId` from `state.target?.tuneId` in commit 0 (one line, so both VMs can use `ArchiveVisibility` from day one).
9. (reviewed 2026-10-15, B2) **Edit of the M1 builder** `core/.../catchup/CatchupUrlBuilderImpl.kt`: the "not started" check moves after `correctedStart` and compares the corrected start (review B2 text, verbatim):
   ```kotlin
   // replaces CatchupUrlBuilderImpl.kt:55 (move it after correctedStart, :58)
   val correctedStartI = timeBase.copy(paddingBefore = Duration.ZERO).archiveStart(window.programmeStart, corr)
   if (correctedStartI.isAfter(nowI)) return CatchupBuild.Unsupported("the programme has not started yet")
   ```
   with `CatchupUrlBuilderImplTest` rows for corr ∈ {−1.5 h, 0, +2 h} (§5.2).
10. (reviewed 2026-10-15, S9) **Attach order:** `GuideViewModel.attachPlayer(machine: PlayerStateMachine)` sets `machine.full`; `MainActivity` creates both view models and calls `guide.attachPlayer(player.machine)` **before** `guide.startup()` runs (startup moves from the `init` block to a call `MainActivity` makes after the attach). `GuideStateMachineImpl`'s `full` setter calls `enterFull(curMs, LAUNCH)` when the mode is already FULL, so no FULL state can run without the player machine. Commit 0 makes the move (the VM edit is otherwise WP-D's).

### 1.8 Fakes and test fixtures

As listed in §1.7 item 5. `PlayerFixtures.programmes(channel, fromMin, n, durMin)` builds a contiguous schedule; `PlayerFixtures.error(code)` builds the ladder-shaped `PlaybackError` (default actions, detail with status/host where the ladder sets them, `retry` for STREAM_INTERRUPTED under unlimited retries).

### 1.9 Integration sequence

1. All five WPs build in parallel against commit 0's fakes.
2. Merge order: **C, B, A, D, E**. B rebases on C (real probe store and feed in `ArchivePlaybackImplTest` stays fake; the Robolectric DAO test is C's). A is independent of B and C except for the `ArchiveStatus` type (commit 0). D rebases last among the code WPs and replaces fakes with real bindings. E's fake-panel work merges any time; its instrumented tests run in step 3.
3. WP-E runs the integration pass on `TheBox_TV_API30` (`-gpu host`, cold boot if the clock drifted, M3 §5.11) against the fake panel: §5.5 suite, the M3 and M2 suites as regression, §5.7 benchmarks. Results go into §5.8.
4. Code review pass (two reviewers, disjoint halves: `:core`+`:app`+`:player`, and `:ui`+tests), then the gates of §5.9.

---
## 2. Contracts

All files in this section are commit 0 (lead). Kotlin is normative for names, types and KDoc'd semantics; `object`s marked "values" are normative too. No contract file contains a `TODO` body. Package root `app.thebox.core` is abbreviated `core`.

### 2.1 `core/.../full/PlayerContracts.kt` (WP-A implements the machine, WP-D the effects)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.1. Verbatim; changes go through the lead.
package app.thebox.core.full

import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.TunedState
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import kotlinx.coroutines.flow.StateFlow

/** Which layer owns the keys in FULL (§3.1). NONE = bare video (a passive info bar may still be drawn). */
enum class PlayerLayer { NONE, INFO, SCRUB, CHANNELS, QUICK_BAR, PICKER, ERROR, SCREEN_OFF }

/** State pill (product §3.8): LIVE red, CATCH_UP accent, PAUSED white 12 %, BEHIND "−MM:SS". */
enum class PillKind { LIVE, CATCH_UP, PAUSED, BEHIND, CONNECTING, RECONNECTING }

/**
 * [behindMs] is valid at [atMs]; it grows 1:1 with wall time while [behindGrows] (paused), else it is constant (playing
 * archive at 1×: the playhead and the live edge advance together). The UI ticks its label at 1 Hz from these.
 */
data class PillState(
    val kind: PillKind = PillKind.LIVE,
    val behindMs: Long = 0,
    val atMs: Long = 0,
    val behindGrows: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectMax: Int = 0,
)

/** Mirror of :player's TrackRef (:core cannot see :player). */
data class TrackKey(val type: Int, val groupId: String, val index: Int)

data class AudioChoice(
    val key: TrackKey,
    val language: String?,
    val label: String?,
    /** "E-AC-3", "AC-3", "AAC", "MP2", … (TrackController.codecName). */
    val codec: String,
    val channels: Int,
    val describesVideo: Boolean,
    val selected: Boolean,
    val supported: Boolean,
)

data class TextChoice(
    val key: TrackKey,
    val language: String?,
    val label: String?,
    /** "DVB", "CEA-608", "WebVTT", … (TrackController.textKind). */
    val kind: String,
    val selected: Boolean,
    /** CEA-608/708 exposed without a caption descriptor (API notes §1.3): may never carry cues; labelled "if broadcast". */
    val speculative: Boolean = false,
)

data class TrackMenu(
    val audio: List<AudioChoice> = emptyList(),
    val text: List<TextChoice> = emptyList(),
    val textEnabled: Boolean = false,
)

/** What the player machine needs from :player's VideoInfo. */
data class VideoFacts(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val interlaced: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
    val aspect: AspectMode = AspectMode.FIT,
    /** The channel's decoder override (null = follow the global setting). */
    val decoderOverride: VideoDecoderMode? = null,
)

/** Mirror of :player's StreamStats (field meanings identical, PlaybackApi.kt:168-196). */
data class StatsFacts(
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val videoDecoder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val interlaced: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
    val audioDecoder: String? = null,
    val audioPassthrough: Boolean = false,
    val streamBitrateBps: Long = 0,
    val bufferMs: Long = 0,
    val droppedFrames: Long = 0,
    val httpStatus: Int? = null,
    val host: String? = null,
    val redirects: Int = 0,
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val reconnects: Int = 0,
    val broker: BrokerSnapshot? = null,
    val bufferProfile: BufferProfile? = null,
    val afr: AfrMode = AfrMode.SEAMLESS,
    val displayRefreshHz: Float? = null,
    val tunneling: Boolean = false,
    /** (reviewed 2026-10-15, E23) 0-based base URL in use and the number of bases (backup servers); "Server: n of m". */
    val serverIndex: Int = 0,
    val serverCount: Int = 1,
)

/** Stat rows in display order (§3.4.4); the UI localises [label]. */
enum class StatLabel { VIDEO, RESOLUTION, FRAME_RATE, VIDEO_DECODER, AUDIO, AUDIO_DECODER, BITRATE, BUFFER, DROPPED, FORMAT, HTTP, SERVER, CONNECTIONS, BUFFER_PROFILE, DISPLAY, TUNNELING }

/** [value] is language-neutral (numbers, codec names, hosts). */
data class StatLine(val label: StatLabel, val value: String)

data class InfoGlyphs(
    val catchupDays: Int = 0,
    /** "1080p50", "720p", "576i25" (StatsFormatter.resolutionLabel); null while unknown. */
    val resolution: String? = null,
    /** "5.1", "2.0"; null while unknown. */
    val audioLayout: String? = null,
    val subtitlesAvailable: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
)

data class InfoBarState(
    /** Keys go to the bar (layer INFO). False: passive, drawn after a zap, no keys. */
    val active: Boolean,
    /** The channel shown: the tuned one, the pending zap target, or the one browsed with ←/→. */
    val channel: GuideChannel?,
    /** True while ←/→ show another channel than the tuned one (OK tunes it). */
    val browsing: Boolean,
    /** Loaded programmes of [channel] in start order (§3.2.2); empty while loading or without EPG. */
    val programmes: List<ProgrammeInfo>,
    /** Index into [programmes] of the programme in focus; −1 when [programmes] is empty. */
    val focused: Int,
    /** Index of the programme on now; −1 when none. */
    val nowIndex: Int,
    val pill: PillState,
    val glyphs: InfoGlyphs,
    val loading: Boolean,
)

enum class ScrubFocus { BAR, CHIPS }

/**
 * The archive/time-shift bar (§3.5). All times are programme-frame epoch ms (§3.8.1); labels add [displayShiftMs].
 * [playheadMs] is valid at [atMs] and advances 1:1 while [advancing]; [previewMs] is the uncommitted scrub target.
 */
data class ScrubState(
    val focused: Boolean,
    val focus: ScrubFocus,
    val programme: ProgrammeInfo?,
    val barFromMs: Long,
    val barToMs: Long,
    val archiveFromMs: Long,
    val liveEdgeMs: Long,
    val playheadMs: Long,
    val atMs: Long,
    val advancing: Boolean,
    val previewMs: Long?,
    val prev: ProgrammeInfo?,
    val next: ProgrammeInfo?,
    /** 0 = previous chip, 1 = next chip; −1 when [focus] is BAR. (Owner ruling 2026-10-15: no Options chip; the quick bar is Back → Up.) */
    val chipFocused: Int,
    val displayShiftMs: Long,
    val pill: PillState,
    /** A re-request or native seek is in flight. */
    val seeking: Boolean,
    /** Live channel with archive, time-shift not started yet: the playhead sits on the live edge. */
    val atLive: Boolean,
)

enum class ListColumn { GROUPS, CHANNELS }
enum class ChannelMenuItem { WATCH_FROM_START, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL }

/**
 * Channel list panel (§3.3). Rows are read from the `GuideRows` the factory received (main-only); [rowsVersion] bumps
 * when blocks land, [nowVersion] when "now" titles arrive in the host's cache.
 */
data class ChannelListState(
    val column: ListColumn,
    val groups: List<GuideSourceEntry>,
    val groupFocused: Int,
    val groupCurrent: Int,
    val source: GuideSource?,
    val lineupVersion: Long,
    val size: Int,
    /** −1 = the pinned "Previous" row (only when [previous] != null), else 0 until [size]. */
    val focused: Int,
    /** First visible lineup index. */
    val top: Int,
    val previous: GuideChannel?,
    val tunedRowId: Long,
    val rowsVersion: Long,
    val nowVersion: Long,
    /** Opened by BACK ("Back in player: Open channel list"): BACK from the panel then leaves FULL (§3.1.5). */
    val openedByBack: Boolean,
    val menu: List<ChannelMenuItem>?,
    val menuFocused: Int,
)

/** Quick bar items in display order (owner ruling 2026-10-15; §3.4.1). Hidden items are left out of [QuickBarState.items]. */
enum class QuickItem {
    AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, JUMP_TO_LIVE, TIME_SHIFT,
    PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SETTINGS,
}

/** The quick bar (D-pad Up on bare video, CONTEXT_MENU, QUICK_MENU). Current values are shown on the items. */
data class QuickBarState(
    val items: List<QuickItem>,
    val focused: Int,
    val statsOn: Boolean,
    val audio: AudioChoice?,
    /** null = subtitles off. */
    val subtitle: TextChoice?,
    val aspect: AspectMode,
    val decoder: VideoDecoderMode?,
    val previous: GuideChannel?,
)

enum class PickerKind { AUDIO, SUBTITLES, ASPECT, VIDEO_DECODER }

sealed interface PickerChoice {
    data class Audio(val track: AudioChoice) : PickerChoice
    data class Text(val track: TextChoice) : PickerChoice
    data object TextOff : PickerChoice
    data class Aspect(val mode: AspectMode) : PickerChoice
    /** null = Auto (follow the global setting). */
    data class Decoder(val mode: VideoDecoderMode?) : PickerChoice
}

/**
 * [focused] indexes [choices]; `choices.size` is the "Remember for this channel" row when [remember] != null.
 * [current] is the index of the active choice (check mark).
 */
data class PickerState(
    val kind: PickerKind,
    val choices: List<PickerChoice>,
    val current: Int,
    val focused: Int,
    val remember: Boolean?,
    /** Opened from the quick bar: BACK/LEFT return to it. */
    val fromQuickBar: Boolean,
)

/** Buttons M4 can execute (§3.7). Everything else is a text step until M5 routes it. */
enum class ErrorButton { RETRY, PREVIOUS_CHANNEL, WATCH_LIVE, TRY_OTHER_CATCHUP_FORMAT, TRY_SOFTWARE_DECODER, AUDIO_OPTIONS, STOP, GUIDE }

data class ErrorCardState(
    val error: PlaybackError,
    val channel: GuideChannel?,
    val buttons: List<ErrorButton>,
    val focused: Int,
    /** The first step that has no button in M4 (shown as the next-step line); null when every step is a button. */
    val textStep: NextStep?,
    /** Catch-up probing ended without a working format (ArchiveStatus.FAILED). */
    val archiveFailed: Boolean,
)

data class PlayerUiState(
    val layer: PlayerLayer = PlayerLayer.NONE,
    val info: InfoBarState? = null,
    val scrub: ScrubState? = null,
    val channels: ChannelListState? = null,
    val quick: QuickBarState? = null,
    val picker: PickerState? = null,
    val error: ErrorCardState? = null,
    /** Non-null while the stats overlay is on (it never takes keys). */
    val stats: List<StatLine>? = null,
    val pill: PillState = PillState(),
    /** A transient "Trying another catch-up format (2/3)" line while probing; null otherwise. */
    val probing: Pair<Int, Int>? = null,
)

/** Why FULL was entered (decides the auto-shown bar, §3.2.1). */
enum class EnterReason { USER, ARCHIVE, REMINDER, SEARCH, LAUNCH }

/** Toasts the machine raises synchronously; effects toast what they learn asynchronously (M3 §2.13 item 9). */
enum class PlayerToast {
    NO_CATCHUP, NO_PREVIOUS, NO_FAVOURITES, NO_AUDIO_CHOICE, NO_SUBTITLES, ARCHIVE_NOT_READY, BACK_TO_LIVE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, CHANNEL_HIDDEN,
    /** arg = ArchiveFailure.name. */
    CATCHUP_FAILED,
}

/** Inputs other than keys. All on main. */
sealed interface PlayerEvent {
    data class Tick(val nowMs: Long) : PlayerEvent
    /** Mirrors GuideEvent.Tuned; [channel] resolved by the host. */
    data class Tuned(val state: TunedState, val channel: GuideChannel?) : PlayerEvent
    /** controller.pendingZap resolved to a row; null when no zap is pending. */
    data class PendingZap(val channel: GuideChannel?) : PlayerEvent
    /** Answer to PlayerEffects.loadProgrammes. */
    data class Programmes(val token: Long, val channelHash: Long, val programmes: List<ProgrammeInfo>) : PlayerEvent
    /** Answer to PlayerEffects.loadChannel (info-bar ←/→ browse). */
    data class BrowseChannel(val token: Long, val channel: GuideChannel?) : PlayerEvent
    data class Tracks(val menu: TrackMenu) : PlayerEvent
    data class Video(val facts: VideoFacts) : PlayerEvent
    data class Stats(val facts: StatsFacts) : PlayerEvent
    data class Archive(val status: ArchiveStatus) : PlayerEvent
    data class Sources(val entries: List<GuideSourceEntry>) : PlayerEvent
    /** The channel list's lineup (the host already called rowsCache.setLineup). */
    data class ListLineup(val lineup: GuideLineup) : PlayerEvent
    data class ListRows(val version: Long) : PlayerEvent
    data class ListNow(val version: Long) : PlayerEvent
    data class Previous(val channel: GuideChannel?) : PlayerEvent
    data class Favourite(val token: Long, val isFavourite: Boolean) : PlayerEvent
    /** The guide's current source and lineup (the channel list opens on it). */
    data class GuideSourceNow(val source: GuideSource?, val lineup: GuideLineup?) : PlayerEvent
    data class Settings(val settings: PlayerSettings, val lowEnd: Boolean) : PlayerEvent
    /** (code review 2026-10-16, U-S7 = N17; contract amendment) An archive start returned `ArchiveStart.Failed` synchronously; the host toasted CATCHUP_FAILED. From the info bar: focus back to now (§3.2.2). */
    data class ArchiveStartFailed(val reason: ArchiveFailure) : PlayerEvent
}

/** Side effects; implemented by :ui's PlayerEffectsImpl (WP-D). Main-safe, non-blocking. */
interface PlayerEffects {
    fun zap(delta: Int, isRepeat: Boolean)
    /** [lineup] non-null makes it the zap list (channel list tunes); null keeps the current zap list. */
    fun tune(channel: GuideChannel, lineup: GuideLineup?)
    fun previousChannel()
    fun retry()
    /** controller.stop(USER) (error card "Stop and wait"). */
    fun stopPlayback()
    /** Live without archive: controller.pause()/resume() (M2 freeze, 10 s release, re-tune on resume). */
    fun pausePlain()
    fun resumePlain()
    // Archive (all delegate to ArchivePlayback, §2.3)
    fun pauseLive(channel: GuideChannel)
    fun archivePause()
    fun archiveResume()
    /** (reviewed 2026-10-15, B3) [seq] increases per committed seek; ArchiveStatus.settledSeq echoes it when settled. */
    fun archiveSeek(programmeMs: Long, seq: Long)
    fun jumpToLive()
    fun startOver(channel: GuideChannel)
    fun rewindLive(channel: GuideChannel, backMs: Long)
    /** (code review 2026-10-16, P-B1; additive, defaulted) `timeShift = true` from the at-live scrub bar (chips, PREV_PROGRAMME): EPG-anchored, continues past the programme like start over. */
    fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, timeShift: Boolean = false)
    fun tryNextCatchupFormat()
    /** (reviewed 2026-10-15, S7) Info bar OK on a future programme: M3 reminder store, toasts REMINDER_SET / REMINDER_CANCELLED. */
    fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo)
    /** (owner ruling 2026-10-15) Quick bar Settings: the host dispatches GuideEvent.OpenSettings to the guide machine. */
    fun openSettings()
    // Data
    fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int)
    fun loadChannel(token: Long, from: GuideChannel, delta: Int)
    fun loadListLineup(source: GuideSource)
    fun ensureListRows(from: Int, to: Int)
    fun ensureListNow(from: Int, to: Int)
    fun queryFavourite(token: Long, channel: GuideChannel)
    fun setFavourite(channel: GuideChannel, on: Boolean)
    fun hideChannel(channel: GuideChannel)
    // Engine
    fun selectAudio(key: TrackKey?)
    fun selectText(key: TrackKey?)
    fun setAspect(mode: AspectMode, remember: Boolean)
    fun setDecoder(mode: VideoDecoderMode?)
    fun setStatsEnabled(on: Boolean)
    /** Viewport HIDDEN (video track off, audio continues) while on; the root draws black. */
    fun setScreenOff(on: Boolean)
    fun toast(kind: PlayerToast, arg: String? = null)
    fun announce(text: String)
}

interface PlayerStateMachine {
    val ui: StateFlow<PlayerUiState>
    /** Wall-clock ms of the next timer (info bar, scrub commit, passive bar, auto-shown scrub); Long.MAX_VALUE = none. */
    val nextDeadlineMs: StateFlow<Long>
    /**
     * (reviewed 2026-10-15, S5) True while a player layer is open or archive video is on screen: the router ignores the
     * long bindings of D-pad LEFT/RIGHT so they act on DOWN with auto-repeat. The guide machine reports it as
     * GuideStateMachine.horizontalRepeat in FULL.
     */
    val horizontalRepeat: Boolean
    /**
     * FULL only, called by the guide machine after its reminder card and overlays (§3.1.1). Returns false when the
     * guide machine must handle [a] (BACK with nothing open, OPEN_GUIDE, EXIT, GROUP_PREV/NEXT, DIGIT, NUMBER_ENTRY).
     */
    fun act(a: GuideEvent.Act): Boolean
    fun release(action: BoxAction)
    /** False when [action] should go on to the framework (none in M4; kept for symmetry with the guide machine). */
    fun handles(action: BoxAction): Boolean
    fun dispatch(event: PlayerEvent)
    fun enterFull(nowMs: Long, reason: EnterReason)
    /** Closes every layer, stops stats, cancels a pending scrub commit (no seek). */
    fun leaveFull()
}

fun interface PlayerStateMachineFactory {
    /** [listRows]: the channel list's own GuideRowsCache (main-only reads). */
    fun create(effects: PlayerEffects, listRows: GuideRows, nowMs: () -> Long): PlayerStateMachine
}
```

### 2.2 `core/.../full/PlayerSettings.kt` (WP-C implements the source)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.2.
package app.thebox.core.full

import kotlinx.coroutines.flow.StateFlow

/** Product §4.7 "Up/Down in player", as amended by the owner ruling of 2026-10-15. */
enum class UpDownInPlayer {
    /** Default: Up opens the quick bar, Down the active info bar. */
    QUICK_BAR_AND_INFO,
    /** Up/Down change channel (Up = DPAD_UP_ZAP_DELTA, inverted by [PlayerSettings.invertChannelDirection]). */
    ZAP,
    /** Up/Down open the channel list on the tuned row moved one row. */
    CHANNEL_LIST,
}
/** Product §4.7 "Back in player". */
enum class BackInPlayer { GUIDE, CHANNEL_LIST }
/** Product §4.4 "On launch". */
enum class OnLaunch { GUIDE_WITH_LAST, FULL_LAST, GUIDE_NOTHING }

/** Rows of product §4.4/§4.7 the player reads. In memory until M5 persists and edits them. */
data class PlayerSettings(
    /** 3 / 5 / 8 s (product §4.4 "Info bar duration"). */
    val infoBarMs: Long = 5_000,
    /** 1 / 1.5 / 2.5 s (product §4.4 "Number entry wait"); also used by the guide's number entry. */
    val numberEntryWaitMs: Long = 1_500,
    val upDown: UpDownInPlayer = UpDownInPlayer.QUICK_BAR_AND_INFO,
    /** Under [UpDownInPlayer.ZAP] only: swaps the D-pad Up/Down zap direction (CH± never invert). */
    val invertChannelDirection: Boolean = false,
    val back: BackInPlayer = BackInPlayer.GUIDE,
    val onLaunch: OnLaunch = OnLaunch.GUIDE_WITH_LAST,
)

/** Zap delta of D-pad Up under UpDownInPlayer.ZAP (+1 = higher number, like CH+). One constant (lead sign-off item 3). */
const val DPAD_UP_ZAP_DELTA = +1

interface PlayerSettingsSource {
    val settings: StateFlow<PlayerSettings>
    fun update(transform: (PlayerSettings) -> PlayerSettings)
}
```

### 2.3 `core/.../catchup/archive/ArchiveContracts.kt` (WP-B implements planner and playback, WP-C the store)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.3.
package app.thebox.core.catchup.archive

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.PlaybackError
import kotlinx.coroutines.flow.StateFlow

enum class ArchivePhase {
    /** No archive session (live playback or nothing). */
    IDLE,
    /** First request built and tuned; no first media yet. */
    STARTING,
    /** A variant failed with CATCHUP_NOT_IN_ARCHIVE before first media; the next one is being tried. */
    PROBING,
    PLAYING,
    PAUSED,
    /** A native seek or a re-request for a seek is in flight. */
    SEEKING,
    /** A continuation re-request (end of request, resume after pause, mid-play failure) is in flight. */
    CONTINUING,
    /** Live paused on an archive-capable channel; the controller froze (and later released) the live session. */
    PAUSED_LIVE,
    /** Terminal for this session: [ArchiveStatus.failure] says why. */
    FAILED,
}

enum class ArchiveFailure { CHANNEL_GONE, NO_ARCHIVE, OUTSIDE_ARCHIVE, UNSUPPORTED_MODE, CREDENTIALS, NOT_IN_ARCHIVE, NO_PROGRAMME, PLAYBACK }

/**
 * One snapshot of the archive session. Times are **programme-frame** epoch ms (raw EPG UTC; §3.8.1). [wallMs] and
 * [liveEdgeMs] are valid at [atMs] (device wall clock); [wallMs] advances 1:1 while [advancing], [liveEdgeMs] always.
 */
data class ArchiveStatus(
    val phase: ArchivePhase = ArchivePhase.IDLE,
    val channel: GuideChannel? = null,
    val identityHash: Long = 0,
    /** The programme containing [wallMs] (re-scoped at boundaries without a new request). */
    val programme: ProgrammeInfo? = null,
    val wallMs: Long = 0,
    val atMs: Long = 0,
    val advancing: Boolean = false,
    val liveEdgeMs: Long = 0,
    /** Oldest playable instant: liveEdge − days, plus the builder's edge margin. */
    val archiveFromMs: Long = 0,
    /** Display = programme frame + this (channel EPG shift); for labels only. */
    val displayShiftMs: Long = 0,
    /** The current request can seek natively (§3.8.4). */
    val seekable: Boolean = false,
    /** Started from live (pause, rewind, start over): the pill shows −MM:SS, not CATCH-UP. */
    val timeShift: Boolean = false,
    val variant: String? = null,
    /** 1-based variant attempt and the number of variants for this target. */
    val attempt: Int = 0,
    val attempts: Int = 0,
    val failure: ArchiveFailure? = null,
    /** The controller's error when [failure] == PLAYBACK or NOT_IN_ARCHIVE. */
    val error: PlaybackError? = null,
    // (reviewed 2026-10-15, F2)
    /** tuneId of the newest controller cycle this session issued (set before tuneArchive); later tuneIds are ours too. */
    val ownedTuneId: Long = 0,
    /** The last seek sequence number whose seek has settled (native: playing again; re-request: first media). */
    val settledSeq: Long = 0,
)

sealed interface ArchiveStart {
    data object Started : ArchiveStart
    data class Failed(val reason: ArchiveFailure) : ArchiveStart
}

/** The catch-up engine (§3.8). Main-safe; suspend functions switch to IO internally. One session at a time. */
interface ArchivePlayback {
    val status: StateFlow<ArchiveStatus>
    /** Catch-up from the programme's start (guide Watch / From start, Search, info bar OK on a past programme). */
    suspend fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean = false): ArchiveStart
    /** Start over the programme airing now on [channel] (Red, quick bar). */
    suspend fun startOver(channel: GuideChannel): ArchiveStart
    /** Live → time-shift [backMs] behind the live edge (RW on live). */
    suspend fun rewindLive(channel: GuideChannel, backMs: Long): ArchiveStart
    /** Live pause on an archive-capable channel: freezes through the controller and records the pause point. */
    fun pauseLive(channel: GuideChannel)
    fun pause()
    /** Resumes an archive pause, or plays from the live-pause point (§3.8.7). */
    fun resume()
    /** [programmeMs] is clamped to [archiveFromMs, liveEdge]; within LIVE_SNAP_MS of the edge **and later than the playhead (or the live-pause point)** it goes live (code review 2026-10-16, P-S1). [status].settledSeq reaches [seq] when the seek has settled, wherever the playhead landed. */
    fun seekTo(programmeMs: Long, seq: Long)
    fun jumpToLive()
    /** Error card "Try another catch-up format": next variant from the current playhead; false when none is left. */
    fun tryNextVariant(): Boolean
}

/** playlist.catchup_probe_winner (M1 §2.1.2). Main-safe. */
interface CatchupProbeStore {
    suspend fun winner(playlistId: Long): String?
    suspend fun setWinner(playlistId: Long, variant: String)
}

/** Converts between the three time frames of one channel (§3.8.1). */
data class ArchiveFrames(
    /** Duration.ofSeconds((correctionHours × 3600).roundToLong()).toMillis(), TimeBase's rounding (N2); programme = archive + correction. */
    val correctionMs: Long,
    /** TimeBase.clockSkew in ms (panel − device). */
    val skewMs: Long,
    /** GuideChannel.epgShiftMin × 60,000; display = programme + shift. */
    val displayShiftMs: Long,
) {
    fun programmeOf(archiveMs: Long): Long = archiveMs + correctionMs
    fun archiveOf(programmeMs: Long): Long = programmeMs - correctionMs
    /**
     * The panel's now in the programme frame (archive-frame now + correction). Live-anchored instants (pause point,
     * RW and time-shift targets) are taken from it; the builder subtracts the correction again, so their requests start
     * at the panel's own clock. The builder's "not started" check compares the corrected start with the panel's now.
     */
    fun liveEdge(deviceNowMs: Long): Long = deviceNowMs + skewMs + correctionMs
}

/** The request being played, in the programme frame. */
data class ActiveRequest(
    val variant: String,
    /** requestStart (archive frame) + correction. */
    val originMs: Long,
    /** requestEnd + correction; null = open-ended. */
    val endMs: Long?,
    /** nativeSeekRange converted to programme-frame ms; null when the builder gave none. */
    val nativeFromMs: Long?,
    val nativeToMs: Long?,
)

/** PositionSnapshot reduced to what the planner needs. */
data class PositionView(val positionMs: Long, val durationMs: Long?, val isSeekable: Boolean, val ended: Boolean)

data class LiveWindow(val liveEdgeMs: Long, val archiveFromMs: Long)

/** How to build the next request. [startMs]/[stopMs] are programme frame; the builder applies correction itself. */
data class RequestPlan(
    val startMs: Long,
    /** null = open-ended. */
    val stopMs: Long?,
    /** false for every request except the first of a programme (no paddingBefore, §3.8.3). */
    val padBefore: Boolean,
    /** The playhead the user expects; the controller seeks there when the request is seekable (startOffsetMs). */
    val targetMs: Long,
)

sealed interface SeekPlan {
    data class Native(val positionMs: Long) : SeekPlan
    data class Rerequest(val plan: RequestPlan) : SeekPlan
    data object Live : SeekPlan
    /** The target equals the playhead within SEEK_NOOP_MS. */
    data object None : SeekPlan
}

sealed interface ContinuePlan {
    data object Keep : ContinuePlan
    data class Rerequest(val plan: RequestPlan) : ContinuePlan
    data object Live : ContinuePlan
}

sealed interface ProbePlan {
    /** Try variant [index] of the target's list with the same window. */
    data class NextVariant(val index: Int) : ProbePlan
    /** Archive lag near the live edge (§3.8.7): retry once [stepBackMs] earlier with the same variant. */
    data class StepBack(val stepBackMs: Long) : ProbePlan
    /** Lag retry used up near the edge: go live with ARCHIVE_NOT_READY. */
    data object LiveNotReady : ProbePlan
    data object Fail : ProbePlan
}

/** Every pure decision of the archive session (§3.8). Implemented by ArchivePlannerImpl (WP-B). */
interface ArchivePlanner {
    fun frames(timeBase: TimeBase, channelCorrectionHours: Double?, epgShiftMin: Int): ArchiveFrames
    fun liveWindow(frames: ArchiveFrames, archiveDays: Int, deviceNowMs: Long): LiveWindow
    /**
     * Start instant to request for [targetMs] with [variant]: for xc-path/xc-php `programmeOf(floorToMinute(archiveOf(t)))`
     * (aligned in the archive frame, where the token is formatted; reviewed 2026-10-15, N2), else unchanged.
     */
    fun alignStart(variant: String, targetMs: Long, frames: ArchiveFrames): Long
    /** First request of a programme ([stopMs] null while it is still airing). */
    fun programmeRequest(startMs: Long, stopMs: Long?, live: LiveWindow): RequestPlan
    fun seek(targetMs: Long, wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): SeekPlan
    /** Called on every poll and on `ended`. */
    fun onProgress(wallMs: Long, req: ActiveRequest, pos: PositionView, live: LiveWindow): ContinuePlan
    /** Resume after a live pause at [pausedAtMs] (§3.8.7). */
    fun resumeFromPause(pausedAtMs: Long, pausedForMs: Long, live: LiveWindow): ContinuePlan
    fun onNotInArchive(req: ActiveRequest, live: LiveWindow, firstMediaSeen: Boolean, variantIndex: Int, variantCount: Int, lagRetriesUsed: Int): ProbePlan
    /** startOffsetMs for tuneArchive given the built request (archive-frame requestStart). */
    fun startOffset(targetMs: Long, requestStartArchiveMs: Long, frames: ArchiveFrames): Long
}
```

### 2.3a `core/.../catchup/archive/ArchiveVisibility.kt` (commit 0, contract with body; reviewed 2026-10-15, B1/F3)

```kotlin
// Contract (M4 commit 0, review B1).
package app.thebox.core.catchup.archive

import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState

/** What both machines and the mini player show while an archive session owns playback (§3.8.2). Pure. */
object ArchiveVisibility {
    private val HANDOFF = setOf(ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)

    fun visible(tuned: TunedState, status: ArchiveStatus?): TunedState {
        if (status == null || status.phase == ArchivePhase.IDLE || status.phase == ArchivePhase.FAILED) return tuned
        if (!tuned.isArchive || tuned.identityHash != status.identityHash) return tuned
        val waiting = tuned.phase == MiniPhase.ERROR || tuned.phase == MiniPhase.CONNECTING || tuned.phase == MiniPhase.BUFFERING
        if (!waiting) return tuned
        val handoff = status.phase in HANDOFF || (status.phase == ArchivePhase.PLAYING && tuned.phase == MiniPhase.ERROR)
        return tuned.copy(
            phase = if (handoff) MiniPhase.PLAYING else MiniPhase.CONNECTING,
            error = null,
            errorDetail = null,
            handoff = handoff,
        )
    }
}
```

Both view models forward `Tuned(ArchiveVisibility.visible(mapped, archive.status.value))` from `combine(controller.state, archive.status)`; the mini player reads the guide's (already visible) `TunedState`. Nothing else filters controller states.

(Integration 2026-10-15, §5.8 item C1; lead-authorised contract edit) The `tuned.tuneId < status.ownedTuneId` clause is gone: `ArchivePlaybackImpl` raises `ownedTuneId` in the same turn as `tuneArchive`, so for one emission the controller still reports the session's **previous** cycle (e.g. the `Error(CATCHUP_NOT_IN_ARCHIVE)` of the variant that just failed) with a lower tuneId, and the clause let that ERROR through. While a session is active, an archive state of the session's channel is judged as owned whatever its tuneId; a zap away changes the identity and a live tune clears `isArchive`, so neither is hidden. `ownedTuneId` still decides session ownership inside `ArchivePlaybackImpl` (§3.8.2). WP-D's `TunedVisibility` workaround is deleted; both view models call this function. Covered by `ArchiveVisibilityTest` and `PlayerViewModelTest.probeNeverShowsTheCard`.

### 2.4 `core/.../full/ProgrammeFeed.kt` (WP-C implements)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.4.
package app.thebox.core.full

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo

/**
 * Programme lookups for the player (info bar, channel list, archive timeline). One `box-player-epg` thread and one
 * EpgReader, generation-safe like GuideWindowPagerImpl (§3.3.3). Main-safe; every call is cancellable. Minutes are
 * **display** minutes (raw + channel shift), as in GuideWindowPager.at.
 */
interface ProgrammeFeed : AutoCloseable {
    /** Programmes of [channel] overlapping [fromMin, toMin) in start order, at most [limit]; CONTINUATION rows merged. */
    suspend fun range(channel: GuideChannel, fromMin: Int, toMin: Int, limit: Int = PROGRAMME_RANGE_LIMIT): List<ProgrammeInfo>
    /** The programme airing at [atMin] on each of [channels] (null = no EPG or a gap), in the same order. */
    suspend fun nowFor(channels: List<GuideChannel>, atMin: Int): List<ProgrammeInfo?>
    suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo?
    /** (reviewed 2026-10-15, S4/F4) Up to [before] programmes ending after [fromMin] and starting before [atMin], then the one airing at [atMin] and up to [after] later ones (display minutes); [nowIndex] indexes the airing one (−1 = gap). */
    suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int = 12, after: Int = 12): ProgrammeSlice

    companion object {
        const val PROGRAMME_RANGE_LIMIT = 24
    }
}

/** (reviewed 2026-10-15, S4/F4) */
data class ProgrammeSlice(val programmes: List<ProgrammeInfo>, val nowIndex: Int)
```

### 2.5 `ui/.../full/PlayerHost.kt` (WP-D implements in `PlayerViewModel`)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.5.
package app.thebox.ui.full

import androidx.media3.common.text.Cue
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.ui.root.ShellEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What FullScreenHost and AppRoot need from the player view model. Main thread only. */
interface PlayerHost {
    val machine: PlayerStateMachine
    /** The channel list's rows (main-only reads) and its "now" cache. */
    val listRows: GuideRows
    fun nowOf(row: GuideChannel): ProgrammeInfo?
    /** Current cues (FULL only; empty otherwise). */
    val cues: StateFlow<List<Cue>>
    /** Notice toasts and player toasts, buffered until collected (M3 H-B3). */
    val events: Flow<ShellEvent>
}
```

`ui/.../root/ShellHost.kt` gains (additive): `val player: app.thebox.ui.full.PlayerHost? get() = null` and `ShellEvent.Message(val text: String)` (already-localised text, used for notices).

### 2.6 Threading and ownership

| Thing | Thread | Owner |
|---|---|---|
| `PlayerStateMachine.act/dispatch/release`, `PlayerUiState` writes | main | WP-A |
| `PlayerEffectsImpl` (calls into controller, archive, library) | main; suspend work on `viewModelScope` with IO inside the callees | WP-D |
| Channel-list `GuideRowsCache` reads | main only (M3 §2.11) | WP-D (instance), WP-B of M3 (impl) |
| `ProgrammeFeedImpl` queries, its `EpgReader`, reader close on a generation change | `box-player-epg` (one daemon thread, `Executors.newSingleThreadExecutor` + `asCoroutineDispatcher`) | WP-C |
| `ArchivePlaybackImpl` state, plan execution, controller calls | main (`@ApplicationScope` + `Dispatchers.Main.immediate`); builder, targets and store on IO | WP-B |
| `controller.cues` emission | main (application looper; `Player.Listener`) | WP-C |
| `SubtitleView.setCues` | main, from a `LaunchedEffect` collector | WP-D |
| Scrub bar draw | main (RenderThread replays) | WP-D |

Nothing on main waits on `box-player-epg` or IO. No `runBlocking`.

### 2.7 Hilt graph

`PlayerStateMachineFactory` (`InputModule`, WP-A); `ArchivePlanner`, `ArchivePlayback` (`@Singleton`), `CatchupLauncher` (`ArchiveModule`, WP-B); `ProgrammeFeed` (`@Singleton`), `CatchupProbeStore`, `PlayerSettingsSource` (`@Singleton`) (`PlayerDataModule`, WP-C); `PlayerDebugProbe` (debug holder / release no-op, WP-E). `PlayerViewModel` is `@HiltViewModel` (WP-D) and injects only `:core`/`:player`/`:ui` types (M3 B7: `:ui` never sees `:data`).

### 2.8 `ui/.../full/PlayerDebugProbe.kt` (WP-E implements)

```kotlin
// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.8.
package app.thebox.ui.full

/** The live player machine for the instrumented suite (debug holder; release no-op), like M3's GuideDebugProbe. */
interface PlayerDebugProbe {
    fun register(machine: app.thebox.core.full.PlayerStateMachine?)
    val current: app.thebox.core.full.PlayerStateMachine?
    val archive: app.thebox.core.catchup.archive.ArchivePlayback?
}
```

### 2.9 Amendments to existing contracts (commit 0, additive)

**`core/.../input/InputContracts.kt`** (append-only; ids are persisted):

```kotlin
// in enum class BoxAction, after DIGIT:
    // M4 additions (docs/design/m4-player-ux.md §2.9)
    PLAY("play", true), PAUSE("pause", true),
    TOGGLE_FAVOURITE("favourite_toggle", true), VIDEO_DECODER("video_decoder", true),
    PREV_PROGRAMME("prev_programme", true), NEXT_PROGRAMME("next_programme", true),
    /** Opens the scrub bar on live at the live edge (pause/rewind entry, §3.5.1). */
    TIME_SHIFT("time_shift", true),

// (reviewed 2026-10-15, S5/F9) KeyContext is unchanged: enum class KeyContext { GUIDE, PLAYER }

// in object Keys:
    const val MEDIA_STOP = 86
    const val MEDIA_SKIP_FORWARD = 272; const val MEDIA_SKIP_BACKWARD = 273
    const val MEDIA_STEP_FORWARD = 274; const val MEDIA_STEP_BACKWARD = 275
```

**`core/.../guide/GuideStateContracts.kt`**:

```kotlin
// GuideUiState: new field (last, default null)
    /** (M4) The archive session, for the mini player's PAUSED / −MM:SS pills (PillRules, §3.2.3). */
    val archive: app.thebox.core.catchup.archive.ArchiveStatus? = null,

// GuideEvent: three new members
    /** (M4) ArchivePlayback.status: suspect ↺ and the pending catch-up FULL follow phase FAILED, not raw errors (§0.6). */
    data class Archive(val status: app.thebox.core.catchup.archive.ArchiveStatus) : GuideEvent
    /** (M4) Player settings the guide machine reads (number-entry wait, Back in player). */
    data class Player(val settings: app.thebox.core.full.PlayerSettings) : GuideEvent
    /** (M4) "On launch: Full screen last channel": go FULL on the first active Tuned (the existing pendingFull path). */
    data object StartFull : GuideEvent
    /** (M4, owner ruling 2026-10-15) Quick bar → Settings: mode SETTINGS; BACK from SETTINGS returns to FULL when opened from it. */
    data object OpenSettings : GuideEvent

// GuideStateMachine: two new members with defaults
    /** (M4) The player machine FULL delegates to (§3.1.1); the host sets it once after create. */
    var full: app.thebox.core.full.PlayerStateMachine?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    /** (M4, reviewed 2026-10-15, S5/F9) A player layer is open or archive video is on screen: D-pad LEFT/RIGHT act on DOWN with auto-repeat (their long bindings are ignored). */
    val horizontalRepeat: Boolean get() = false

// NumberEntry: one new member with a default
    /** (M4) The auto-commit wait (PlayerSettings.numberEntryWaitMs); default NUMBER_COMMIT_MS. */
    fun setCommitAfterMs(ms: Long) = Unit
```

The KDoc of `GuideStateMachine.handles` loses "FULL leaves Play/Pause and FF/RW to M2's media session": in M4 FULL handles them (the media session still receives Assistant/phone transport commands through `SessionPlayer`, §8 Q7).

**`core/.../guide/GuideModel.kt`**: `TunedState` gains `val errorDetail: app.thebox.core.playback.PlaybackError? = null`, then (reviewed 2026-10-15, F2) `val tuneId: Long = 0` and `val handoff: Boolean = false` (last fields). `TunedStateMapper` fills `errorDetail` from `PlaybackState.Error.error` and `tuneId` from `state.target?.tuneId`. (reviewed 2026-10-15, N6) A top-level `val ProgrammeInfo.rawEndMin: Int get() = rawStartMin + (endMin - startMin)` is the one definition of a raw end; §3.5 and §3.8 use it.

**`KeyRouterImpl`** (WP-A, reviewed 2026-10-15, S5), first DOWN, replacing the long lookup at `:109`:

```kotlin
val long = keyMap.long(context, code)?.takeUnless { m.horizontalRepeat && (code == Keys.DPAD_LEFT || code == Keys.DPAD_RIGHT) }
```

The decision is stored per held key next to `heldContext`.

**`player/.../api/PlaybackApi.kt`**:

```kotlin
// PlaybackController: two members with defaults (implementations override)
    /** (M4) Current subtitle cues from Player.Listener.onCues(CueGroup); emptied on every tune and stop. */
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> get() = NO_CUES
    /** (M4) The channel previousChannel() would tune (M2 §3.1.1 rule), for the channel list's pinned row. */
    val previous: StateFlow<ChannelIdentity?> get() = NO_PREVIOUS

// PlaybackState.Playing: new last parameter
    /** (M4) Archive only: STATE_ENDED reached (the controller reports it as paused, PlaybackControllerImpl.kt:910-915). */
    val ended: Boolean = false,

// VideoInfo: new last field
    /** (M4) The channel's decoder override (null = global setting). */
    val decoderOverride: VideoDecoderMode? = null,

// StreamStats: new last fields (reviewed 2026-10-15, E23)
    /** 0-based base URL in use (ResolvedStream.baseIndex) and the number of bases. */
    val serverIndex: Int = 0,
    val serverCount: Int = 1,

// top level
internal val NO_CUES: StateFlow<List<androidx.media3.common.text.Cue>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
internal val NO_PREVIOUS: StateFlow<ChannelIdentity?> = kotlinx.coroutines.flow.MutableStateFlow(null)
```

`NO_CUES`/`NO_PREVIOUS` are `internal` to `:player`; fakes in other modules override the members. **`PositionSnapshot`** is unchanged (the planner reads `ended` from the state).

**Redaction (M2 N-L9):** `CatchupTarget`, `ArchiveRequest` (`CatchupContracts.kt`) and `XtreamStreamRef` (`core/.../xtream/XtreamContracts.kt`) override `toString()` to print the mode/variant/host only (pattern: `ResolvedStream.toString`, `PlaybackContracts.kt:110`). They are data classes; `equals`/`hashCode` stay generated.

---
## 3. Algorithms

### 3.1 Keys and layers (WP-A)

Key behaviour in FULL follows the owner ruling of 2026-10-15 (`docs/decisions/2026-10-15-player-keys-and-quick-bar.md`): CH± zap in TV order, D-pad Up opens the quick bar, D-pad Down the info bar. The guide's tables are unchanged.

#### 3.1.1 Dispatch order in FULL

`GuideStateMachineImpl.act` keeps its order (`:235-257`) and inserts the player machine after the guide's own overlays:

1. Reminder card (modal, M3 S2) → `cardKey`.
2. Guide overlay (`Overlay.Number`, `Overlay.Exit`) → `overlayKey` (number entry stays the guide's in both modes, so the preview, the commit and "No channel N" exist once).
3. **`mode == FULL` and `full != null` → `if (full.act(a)) return`.**
4. `globalKey` (DIGIT, NUMBER_ENTRY, EXIT, OPEN_GUIDE; PREVIOUS_CHANNEL is consumed by the player first).
5. `fullKey`, reduced to: BACK / OPEN_GUIDE → `returnToGuide()`; GROUP_PREV / GROUP_NEXT → `cycleGroup` (M3 accelerator 3; the group card is still `GuideUiState.groupCard`, drawn by `FullScreenHost`); everything else no-op. M3's UP/DOWN/ZAP (`:491-492`) and SELECT-retry branches move into the player machine.

`goFull()` calls `full.enterFull(curMs, reason)` (reason from the path that went FULL: `pendingArchiveHash` → ARCHIVE, reminder → REMINDER, search → SEARCH, `StartFull` → LAUNCH, else USER); `returnToGuide()` and `setMode(≠FULL)` call `full.leaveFull()`. `handles(action)` in FULL returns `full?.handles(action) ?: true` (M3's `FULL_PASSTHROUGH`, `:1590-1591`, is removed). `horizontalRepeat` = `mode == FULL && full?.horizontalRepeat == true` (reviewed 2026-10-15, S5). `GuideEvent.OpenSettings` (from the quick bar) runs the existing `openSettings()` and remembers `settingsFromFull`; BACK in SETTINGS then calls `goFull()` instead of `setMode(GUIDE)`. `nextDeadlineMs` is the guide's own; the player VM ticks the player machine on its own deadline (§3.10.1).

(reviewed 2026-10-15, S9) The `full` setter, when the mode is already FULL, calls `enterFull(curMs, LAUNCH)`; `MainActivity` attaches the player machine before the guide VM's startup tune (§1.7 item 10), so no `act` in FULL ever runs with `full == null`.

`Release` events in FULL go to `full.release(action)` as well as the guide's own `release`.

#### 3.1.2 Bare video (layer NONE)

`horizontalRepeat` is false on live video and true on archive/time-shift video (`status.phase ∉ {IDLE, FAILED}` or the visible tuned state `isArchive`). "Change channel" below means `upDown == ZAP`: D-pad Up = `zap(DPAD_UP_ZAP_DELTA)` (+1), Down = −1, both negated when `invertChannelDirection`.

| Action | Live | Archive / time-shift |
|---|---|---|
| UP | `QUICK_BAR_AND_INFO` (default): quick bar (§3.4.1); `ZAP`: change channel; `CHANNEL_LIST`: channel list on the tuned row moved one row up | same |
| DOWN | default: info bar, active; `ZAP`: change channel; `CHANNEL_LIST`: list on the tuned row moved one row down | default: scrub bar, focused; others as live |
| ZAP_UP / ZAP_DOWN (CH+/CH−, PAGE_UP/DOWN, ⏭/⏮) | `zap(+1)` / `zap(−1)` (+1 = higher number; never inverted) (reviewed 2026-10-15, S6; owner ruling); passive info bar for the target at once (§3.2.1) | same (leaving archive is a zap) |
| LEFT | channel list, GROUPS column | open the scrub bar focused and apply one step back (§3.5.2) |
| RIGHT | info bar, active | open the scrub bar focused and apply one step forward |
| GROUP_PREV / GROUP_NEXT (long ←/→, live only) | not consumed → guide `cycleGroup` | n/a (long ←/→ ignored while `horizontalRepeat`) |
| SELECT | channel list on the tuned row; on `Stopped(USER)`: `retry()` (N12) | channel list |
| CONTEXT_MENU, QUICK_MENU | quick bar | quick bar |
| INFO_BAR | info bar, active | scrub bar, focused |
| CHANNEL_LIST | channel list, CHANNELS | same |
| GROUPS_PANEL | channel list, GROUPS | same |
| FAVOURITES | channel list on the first Favourites entry (`SourceSection.FAVOURITES`); none → toast NO_FAVOURITES | same |
| PLAY_PAUSE | archive-capable: `pauseLive`, scrub bar shown unfocused (PAUSED pill); else `pausePlain` (M2 freeze) | toggle `archivePause`/`archiveResume`; scrub bar shown unfocused for `SCRUB_AUTO_SHOW_MS` |
| PLAY | paused live (PAUSED_LIVE) → `archiveResume`; plain-paused → `resumePlain`; else no-op | `archiveResume` if paused |
| PAUSE | as PLAY_PAUSE when playing; no-op when paused | `archivePause` |
| SEEK_BACK (RW) | archive-capable → `rewindLive(RW_ON_LIVE_BACK_MS)`, scrub bar focused; else toast NO_CATCHUP | scrub bar focused, preview −`FF_RW_STEP_MS` (commit after 600 ms) |
| SEEK_FORWARD (FF) | toast NO_CATCHUP when no archive; else no-op (already live) | scrub bar focused, preview +`FF_RW_STEP_MS` |
| JUMP_TO_LIVE | no-op | `jumpToLive` |
| START_OVER | archive-capable and a programme on now → `startOver`; else toast NO_CATCHUP | seek to the start of the programme under the playhead |
| TIME_SHIFT | archive-capable → scrub bar focused at the live edge (`atLive`); else NO_CATCHUP | scrub bar focused |
| PREV_PROGRAMME / NEXT_PROGRAMME | info bar active, focus moved −1/+1 | seek to the previous programme's start / the next one's start (next beyond live → live) |
| AUDIO, SUBTITLES, ASPECT, VIDEO_DECODER | picker of that kind (`fromQuickBar = false`) | same |
| STATS_OVERLAY | toggle stats | same |
| TOGGLE_FAVOURITE | `queryFavourite` then toggle on the answer; toast ADDED/REMOVED_FAVOURITE (effect) | same |
| PREVIOUS_CHANNEL | `previousChannel()`; none → toast NO_PREVIOUS | same |
| SCREEN_OFF | layer SCREEN_OFF, `setScreenOff(true)` | same |
| BACK | `back == GUIDE` → not consumed (guide `returnToGuide`); `CHANNEL_LIST` → channel list with `openedByBack` | same |
| DIGIT, NUMBER_ENTRY, OPEN_GUIDE, EXIT | not consumed (guide) | same |
| REMIND, PAGE_*, JUMP_TO_NOW, DAY_STRIP | no-op (guide-only; PAGE_UP/DOWN arrive as ZAP_UP/DOWN in FULL, `DefaultKeyMap.kt:27`) | same |

#### 3.1.3 Layer tables

| Layer | UP / DOWN | LEFT / RIGHT | SELECT | BACK | Other |
|---|---|---|---|---|---|
| QUICK_BAR | UP no-op; DOWN closes | move over `items` (auto-repeat, wraps off) | item (§3.4.1) | close | ZAP_* zap (the bar stays, values refresh); auto-hides after `QUICK_BAR_IDLE_MS` without a key |
| INFO (active) | focus −1/+1 over `programmes` (earlier stops at the first programme inside the archive when the channel has archive, else at now; later is free); loads more at either end (§3.2.2) | browse the zap list's previous/next channel: `loadChannel(token, current, ∓1)`, programmes of that channel around now | on the tuned channel: focused == now → quick bar (the route that works under every `upDown` setting); past in archive → `playProgramme`; future → `toggleReminder` (reviewed 2026-10-15, S7: product S8 "OK on next sets a reminder"); on a browsed channel → `tune(channel, null)` and the bar shows it (passive after the tune) | hide | ZAP_* zaps (bar follows); INFO_BAR hides; timer restarts on every key |
| SCRUB (focused, BAR) | UP → `jumpToLive` (time-shift/archive; owner ruling: Up stays jump to live while the bar is open); on `atLive` UP is no-op. DOWN → `focus = CHIPS`, `chipFocused = 1` (next) or 0 when no next | step back / forward (§3.5.2), preview moves, commit armed | preview pending → commit now; else toggle pause (`archivePause`/`archiveResume`; on `atLive` → `pauseLive`) | preview pending → cancel (preview = null, nothing sent); else hide the bar | SEEK_* ±30 s preview; PLAY_PAUSE/PLAY/PAUSE as bare; PREV/NEXT_PROGRAMME seek |
| SCRUB (CHIPS) | UP → BAR | move between the previous and next chips (absent chips skipped) | seek to that programme's start (next beyond live → live), focus BAR | BAR | — |
| CHANNELS, column CHANNELS | move (hold accelerates §3.1.4); from 0 UP goes to the Previous row when present | LEFT → GROUPS column; RIGHT → `tune(row, lineup)` and keep the panel open (TiviMate [K]) | row == tuned → close panel; else tune and stay open; Previous row → `previousChannel()` | `openedByBack` → not consumed (leave FULL); else close | ZAP_UP/ZAP_DOWN → page up/down (`LIST_PAGE_ROWS`, towards lower/higher numbers as in the guide); CONTEXT_MENU → channel menu; DIGIT not consumed |
| CHANNELS, column GROUPS | move over `groups` | RIGHT → CHANNELS; LEFT no-op | apply that source (`loadListLineup`), focus CHANNELS row 0 (or the tuned row when present) | CHANNELS | ZAP_* → previous/next group applied at once (decision note accelerator 2) |
| CHANNELS, channel menu | move | — | item: WATCH_FROM_START → `startOver` (tunes that channel's archive), ADD/REMOVE_FAVOURITE → `setFavourite`, HIDE_CHANNEL → `hideChannel` (row index kept, M3 H-S1); close menu | close menu | — |
| PICKER | move over choices (+ Remember row) | LEFT → back to the quick bar (`fromQuickBar`) or close | choice → apply (§3.4.2), close (to video); Remember row → toggle `remember` | `fromQuickBar` → quick bar; else close | — |
| ERROR | as bare video for the `upDown` setting (default: UP quick bar over the card, DOWN no-op) | move over buttons | button (§3.7) | not consumed → guide (product S8: "Back goes to the guide") | ZAP_* zap (leaving a broken channel is the common next step); PREVIOUS_CHANNEL; QUICK_MENU opens the quick bar over the card |
| SCREEN_OFF | any action: `setScreenOff(false)`, layer NONE, the action is consumed (it only wakes) | | | | |

Opening any layer closes the others except the stats overlay (orthogonal) and the passive info bar (replaced). (reviewed 2026-10-15, B1/F3) The error layer appears when the **visible** `Tuned.phase == ERROR` (`ArchiveVisibility`) or `status.phase == FAILED`, and closes every other layer except stats; it leaves when the visible phase leaves ERROR. The quick bar may open over the error card (layer QUICK_BAR, the card stays drawn); closing the bar returns to ERROR.

#### 3.1.4 Holds and repeats

- Channel list UP/DOWN: one row per event for `repeat < LIST_ACCEL_AFTER` (8), then `LIST_ACCEL_STEP` (3) rows per event, then a page (`LIST_PAGE_ROWS`) per event from repeat 20. `ensureListRows`/`ensureListNow` are coalesced: requested on every move but the host debounces "now" loads by `LIST_NOW_DEBOUNCE_MS` (120) during a hold and fires at `Release`.
- Info bar ←/→ browse: one channel per event; the programme load for intermediate channels is skipped while `repeat > 0` (only the channel row loads), the programmes of the final one load on `Release` (mirrors M3 S16).
- Quick bar ←/→: one item per event.
- Scrub ←/→ and FF/RW: steps from `heldMs` (§3.5.2). `Release` re-arms the 600 ms commit from the release time.
- Zap holds: `effects.zap(delta, isRepeat = repeat > 0)` (M2 debounce 350 ms coalesces).

#### 3.1.5 Back ladder in FULL

1. Guide overlay (number entry: clear; Exit hint) – the guide's (§3.1.1 step 2).
2. SCREEN_OFF → wake.
3. PICKER → quick bar if `fromQuickBar`, else close.
4. QUICK_BAR → close (to ERROR when it was opened over the card); channel menu → close menu; GROUPS column → CHANNELS column.
5. CHANNELS → close, unless `openedByBack` (then step 9).
6. SCRUB with a preview → cancel the preview; SCRUB → hide.
7. INFO (active) → hide.
8. STATS on and nothing else open → stats off.
9. ERROR, or nothing open → not consumed: `back == GUIDE` → guide `returnToGuide()` (M3 B2: GUIDE, MINI, home, `returnedFromFull`); `back == CHANNEL_LIST` and the list was not just closed by step 5 → open the channel list with `openedByBack`. A passive info bar never consumes BACK.

(reviewed 2026-10-15, S10) `ComposedFullBackLadderTest` (§5.1) proves that BACK from every composed FULL state reaches GUIDE (or the exit dialog) within 6 presses.

#### 3.1.6 Five-key rule (FULL)

Every v1 player function is reachable with UP/DOWN/LEFT/RIGHT/SELECT/BACK, **without** CONTEXT_MENU (long-press is not universal, M3 S11):
- quick bar: UP from bare video in every state (live, archive, time-shift; owner ruling 2026-10-15); from it: audio, aspect, subtitles, decoder pickers, favourite, start over / jump to live, pause/rewind, previous channel, stream info, screen off, Settings;
- zap: CH± where the remote has them; otherwise the channel list (SELECT, then RIGHT/SELECT on a row) or the "Change channel" setting (§8 Q13); info bar: DOWN or RIGHT; channel list: SELECT; groups: LEFT;
- pause/rewind on live: quick bar → Pause / rewind (TIME_SHIFT) → scrub bar (SELECT pauses, LEFT rewinds, UP back to live);
- catch-up of a past programme: info bar → UP to it → SELECT;
- previous channel: quick bar → Previous channel, or channel list → UP from row 0 → Previous row;
- error actions: LEFT/RIGHT/SELECT on the card; guide: BACK.

(reviewed 2026-10-15, B4/F6, adapted to the owner ruling) `PlayerFiveKeyReachabilityTest` searches the state graph with the six keys only, from four start states: live with archive, live without archive, archive (`playProgramme`), and time-shift (`pauseLive` + resume). It asserts that **from each start state, without passing through a state of another kind** (no zap, no jump to live), every `PlayerLayer`, every `QuickItem` that applies in that kind of state, every `PickerKind`, `ScrubFocus`, the Previous row and the kind's effects (`jumpToLive`/`startOver`/`pauseLive`/`playProgramme`) are reached. It also asserts every `ErrorButton` that applies, with a scripted ERROR per code. In archive and time-shift the quick bar is reached by BACK to bare video, then UP. Stated exclusions: the channel menu (long OK; favourite is in the quick bar) and number entry (digits). It runs once per `UpDownInPlayer` value with no extra exclusion: under ZAP and CHANNEL_LIST, where Up does not open the quick bar, it is reached through RIGHT (info bar) → SELECT on the now programme.

#### 3.1.7 Default key map (WP-A edits `DefaultKeyMap`)

`KeyContext` stays `{GUIDE, PLAYER}` (reviewed 2026-10-15, S5/F9). New and changed default bindings (PLAYER; GUIDE unchanged):

| Key | Action |
|---|---|
| CHANNEL_UP / CHANNEL_DOWN | ZAP_UP / ZAP_DOWN (unchanged binding; in FULL ZAP_UP now means +1, the higher number) |
| PAGE_UP / PAGE_DOWN | ZAP_UP / ZAP_DOWN (unchanged binding, so they follow CH±) |
| MEDIA_NEXT / MEDIA_PREVIOUS | **ZAP_UP / ZAP_DOWN** (were ZAP_DOWN / ZAP_UP, `DefaultKeyMap.kt:29`, because ZAP_UP meant −1; reviewed 2026-10-15, S6) |
| MEDIA_PLAY / MEDIA_PAUSE | PLAY / PAUSE (were PLAY_PAUSE; MEDIA_PLAY_PAUSE stays PLAY_PAUSE). GUIDE keeps PLAY_PAUSE for all three |
| MEDIA_STOP (86) | JUMP_TO_LIVE |
| MEDIA_SKIP_FORWARD / BACKWARD (272/273), MEDIA_STEP_FORWARD / BACKWARD (274/275) | SEEK_FORWARD / SEEK_BACK |
| everything else | as M3 §3.18.2 |

`DefaultKeyMapTest` gains the row "CH+, ⏭ and PAGE_UP give the same action in PLAYER". The router reads `m.horizontalRepeat` on a key's first DOWN and stores the decision per held key next to `heldContext` (`KeyRouterImpl.kt:67,:114,:118`), so a change mid-hold (the scrub bar opening on LEFT) never turns the held key into a long press.

### 3.2 Info bar (WP-A state, WP-D drawing)

#### 3.2.1 When it shows

| Trigger | Mode | Hides |
|---|---|---|
| DOWN (default `upDown`), RIGHT, INFO_BAR on live | active | `infoBarMs` (setting, default 5 s) after the last key; BACK; any other layer opening |
| Zap (CH±, Up/Down under "Change channel", previous channel, channel-list tune with the list closed) | passive, at once, channel = pending zap target or the tuned channel (programme loads, "Connecting" pill) | `INFO_AFTER_ZAP_MS` (4 s) after the tune reaches PLAYING or ERROR; replaced by the next zap |
| `enterFull(USER/LAUNCH/REMINDER/SEARCH)` on live | passive | `INFO_AFTER_ZAP_MS` after enter |
| `enterFull(ARCHIVE)`, START_OVER, `rewindLive`, `pauseLive` | scrub bar, unfocused | `SCRUB_AUTO_SHOW_MS` (4 s, product §3.7) after first media (or on PAUSED_LIVE: stays while paused) |

(reviewed 2026-10-15, S7) Also: any `Tuned` whose `identityHash` differs from the previous one while in FULL and not owned by the archive session (number-entry commit and reminder "Switch now" go through the guide machine, `GuideStateMachineImpl.kt:948, :856-865`) → passive bar for that channel, same timing as a zap.

The passive bar never takes keys; layer stays NONE. Timers are deadlines in `nextDeadlineMs`.

#### 3.2.2 Programmes

(reviewed 2026-10-15, S4: a time-bounded first `range` with a limit of 24 returned only past programmes on short-slot channels) On show (or channel change) the machine asks `loadProgrammes(token, channel, aroundMin = nowMin, before = INFO_PAST_MIN (360) when archive else 0, after = INFO_FUTURE_MIN (720))`; the host calls `ProgrammeFeed.around(channel, atMin = now, fromMin = now − before, before = 12, after = 12)` and passes `ProgrammeSlice.programmes`; `focused = slice.nowIndex` on arrival (−1 = a gap: focus the first programme after now). Moving within `INFO_EDGE` (2) of either end issues the next `around(edgeProgramme.startMin, …)` (same token scheme; stale tokens dropped). A channel without EPG shows "No information" (`programmes` empty, `focused = −1`): OK on it opens the quick bar (tuned) or tunes (browsed). (reviewed 2026-10-15, N17) When `playProgramme` from the bar fails synchronously, focus returns to now. (code review 2026-10-16, U-S7, contract amendment) The host dispatches `PlayerEvent.ArchiveStartFailed(reason)` after toasting CATCHUP_FAILED; when the failed start came from the bar and no key followed, the machine reopens the info bar on the tuned channel with the focus on now (or moves the focus to now if the bar is open). Failures of other starts (chips, RW, start over) change nothing; the toast stays.

#### 3.2.3 Pill (`PillRules.pill(tuned, status, nowMs)`, shared by both machines and the mini player)

| Condition (first match) | Pill |
|---|---|
| (reviewed 2026-10-15, B1/F3) `tuned.handoff` | the pill of the last non-hand-off state (BEHIND/CATCH-UP); `ScrubState.seeking = true` |
| `tuned.phase == CONNECTING/BUFFERING` | CONNECTING |
| `tuned.phase == RECONNECTING` | RECONNECTING (attempt/max) |
| `status.phase == PAUSED_LIVE` or (`status.phase == PAUSED`) or `tuned.phase == PAUSED` | PAUSED; if `status.phase ∈ {PAUSED_LIVE, PAUSED}` then `behindMs = live − wall`, `behindGrows = true` |
| `status.phase ∈ {PLAYING, SEEKING, CONTINUING, STARTING, PROBING}` and `status.timeShift` | BEHIND (`behindMs = liveEdge − wall`, constant) |
| `status.phase ∈ {…}` (not time-shift), or `tuned.isArchive` | CATCH-UP |
| otherwise | LIVE |

`tuned` is always the **visible** state (`ArchiveVisibility`, §2.3a), so a hand-off never reads CONNECTING. The UI changes the displayed pill only after it has been stable for `PILL_DEBOUNCE_MS` (400), which hides M2's Playing ↔ Buffering(RECONNECTING) flicker (M2 E-L8). The mini player draws PAUSED (white 12 %) and BEHIND (`−MM:SS`, hours as `−H:MM:SS`) with the M3 pill style (`MiniPlayerOverlay.kt:104-126`).

#### 3.2.4 Glyphs

`catchupDays` from the channel (0 when `suspectArchive`), resolution from `VideoFacts` through `StatsFormatter.resolutionLabel(height, fps, interlaced)` ("1080i25" for interlaced 50-field content, "720p50", "2160p"), audio layout from the selected `AudioChoice.channels` (1 → "1.0", 2 → "2.0", 6 → "5.1", 8 → "7.1"), subtitles glyph when any non-speculative text track exists, HDR badge from `VideoFacts.hdr`.

### 3.3 Channel list (WP-A model, WP-D panel and feed glue, WP-C feed)

#### 3.3.1 Opening

The list opens on the guide's current source and lineup (`PlayerEvent.GuideSourceNow`, forwarded by the guide VM whenever it changes), with the cursor on the tuned row (or 0) and `top` placing it at visible row 3. Opening via GROUPS_PANEL/LEFT focuses the GROUPS column on the current source. Selecting another group calls `loadListLineup(source)`; the VM loads `GuideLibrary.lineup(source, unlocked)` (first emission), calls `listRows.setLineup`, then `ListLineup`. The guide's own source does **not** change (TiviMate keeps the guide where it was); a tune from the list makes that lineup the zap list (`tune(channel, lineup)`), as the guide's OK does (`GuideEffectsImpl`, M3 §3.16.3).

#### 3.3.2 Paging

Rows come from the channel list's own `GuideRowsCache` (factory `GuideRowsCacheFactory.create(library, viewModelScope)`, 32-row blocks, LRU 8, `GuideFeedContracts.kt:64-79`). On every cursor or `top` change the machine calls `ensureListRows(top − LIST_PREFETCH_BEFORE (6), top + LIST_VISIBLE (10) + LIST_PREFETCH_AFTER (8))` and `ensureListNow` for the visible range. The VM collects `nowFor(channels, nowMin)` for the rows already loaded (skipping PENDING rows; they ask again when `listRows.changes` fires), stores results in `ChannelNowCache` (LRU `NOW_CACHE` (256) keyed by `identityHash`, entries expire at the programme's end minute), and bumps `ListNow(version)`. A minute tick re-asks only entries whose programme ended. Nothing iterates the lineup; the cursor moves over indices.

#### 3.3.3 `ProgrammeFeedImpl` (WP-C)

- One `box-player-epg` single-thread executor; one `EpgReader` from `EpgStore.openReader()`, reopened when `EpgStore.info` reports a new generation (the old reader is closed **on the same thread**, M3 decision 6) and re-resolved on a patch. (reviewed 2026-10-15, N1) The reader handle is the feed's own, but its queries run on the generation's shared guide connection under its lock (`GenerationHandle.guide`, `EpgReaderImpl.kt:33,54,104`), contending with the guide pager: every call is one short use (target ≤ 2 ms; `around`/`nowFor` are one `window` statement each).
- Per-call: resolve `channel.epg` to the generation-local int (cache `identityHash → id` per generation, cap 1,024), then `EpgReader.window` (for `range`/`nowFor`, one call for all channels with `shiftMin[]`) or `EpgReader.at` (`EpgContracts.kt:246-251`). Programme rows become `ProgrammeInfo` with the same mapping as `GuideWindowPagerImpl` (`ProgrammeDetail.toInfo`, `GuideWindowPagerImpl.kt:459`); WP-C extracts it into `core/.../guide/feed/ProgrammeInfos.kt` (a WP-C file) and changes the pager to call it (one-line edit of a WP-B-of-M3 file, accepted as WP-C's).
- `around(channel, atMin, fromMin, before, after)`: one `window(fromMin, atMin + AROUND_AHEAD_MIN (1,440))` query, then the slice is cut by count around the programme containing `atMin` (S4).
- CONTINUATION rows (`ProgrammeFlags.CONTINUATION`) are merged into the previous segment in `range` and `around`, so a film over 6 h is one entry (M3 open item "films over 6 h").
- `SQLITE_BUSY`: retry after `BUSY_RETRY_MS` (500) up to 3 times (M3 constants), then return what it has (empty list / nulls). No exception reaches the caller except cancellation.

#### 3.3.4 Row content (drawn by WP-D)

Number (mono 18, .5), logo 56 × 56 (Coil, the shell's loader), name 20/600, now title 17 (.7) one line ellipsis, a 3 px progress bar under the title (now programme only). The tuned row shows the red dot (prototype tuned dot, 7 px). Locked rows are not in the lineup (M1 predicate); hidden rows neither.

### 3.4 Quick bar, pickers, stats, screen off

#### 3.4.1 Quick bar (`QuickBarModel`; owner ruling 2026-10-15, replaces the proposal's quick-menu sheet)

Opened by D-pad Up on bare video (default `upDown`), CONTEXT_MENU, QUICK_MENU, and SELECT on the tuned channel's now programme in the info bar. A horizontal row at the bottom of the screen on a translucent background (no blur), drawn over the video (§3.9). Items in this order, each hidden when it does not apply:

| Item | Shown when | Label (current value) | SELECT |
|---|---|---|---|
| AUDIO | ≥ 2 audio choices, or the selected one unsupported | "Audio · English 5.1" | AUDIO picker (`fromQuickBar`) |
| ASPECT | always | "Aspect · Fit" | ASPECT picker |
| SUBTITLES | ≥ 1 text choice | "Subtitles · Off" / "· English" / "· CC" | SUBTITLES picker |
| FAVOURITE_ADD / FAVOURITE_REMOVE | after the `queryFavourite` answer issued on open (until then neither) | "Add to favourites" / "Remove from favourites" | `setFavourite`, toast, bar stays |
| START_OVER | live with archive and a programme on now; archive/time-shift: never (JUMP_TO_LIVE takes the slot) | "Start over" | `startOver`, bar closes |
| JUMP_TO_LIVE | archive/time-shift | "Back to live" | `jumpToLive`, bar closes |
| TIME_SHIFT | live with archive | "Pause / rewind" | scrub bar focused at the live edge (`atLive`); the five-key pause path (proposer addition to the owner's list, §7 item 16a) |
| PREVIOUS_CHANNEL | `previous != null` | "Previous: {name}" | `previousChannel()`, bar closes |
| STREAM_INFO | always | "Stream info" (check mark when on) | toggle stats, bar stays |
| VIDEO_DECODER | always (video present) | "Decoder · Auto" | VIDEO_DECODER picker |
| SCREEN_OFF | always (reviewed 2026-10-15, N7/E16) | "Screen off" | layer SCREEN_OFF |
| SETTINGS | always, last | "Settings" | `openSettings()` → `GuideEvent.OpenSettings` (mode SETTINGS: the M3 placeholder route the guide's gear already uses, `GuideStateMachineImpl.openSettings`; not a new stub); BACK from it returns to FULL |

The bar opens with focus on AUDIO (or the first item). It closes on BACK/DOWN, on SELECT of an item that leaves (marked above), and after `QUICK_BAR_IDLE_MS` (8,000) without a key. "Sources" as a picker and "Open in external player" are M5 (backup editor; external player setting); the current server shows read-only in Stream info (E23).

#### 3.4.2 Pickers

| Kind | Choices | Current | Apply | Remember |
|---|---|---|---|---|
| AUDIO | `TrackMenu.audio` (unsupported ones disabled with "not supported on this device") | selected | `selectAudio(key)` → `controller.selectAudioTrack(ref, remember = true)` (the language is remembered per channel automatically, product §4.4) | none |
| SUBTITLES | Off + `TrackMenu.text` (speculative CEA tracks labelled "Captions (if broadcast)") | selected or Off | `selectText(key or null)` → `selectTextTrack(ref, remember = true)` | none |
| ASPECT | FIT, FILL, ZOOM, RATIO_16_9, RATIO_4_3 | `VideoFacts.aspect` | `setAspect(mode, remember)` | row, default **checked** (product S8) |
| VIDEO_DECODER | Auto (null), HARDWARE_ONLY, SOFTWARE_PREFERRED | `VideoFacts.decoderOverride` | `setDecoder(mode)` → `setVideoDecoderOverride(mode, remember = true)` (a re-prepare of the channel, M2 §3.12) | none (per channel by definition, product §4.12) |

OK applies and closes to video. Speculative captions are sorted last, after real text tracks (E14). A picker opened with no choice to make (AUDIO with one supported track, SUBTITLES with none) is not opened: toast NO_AUDIO_CHOICE / NO_SUBTITLES. The ratio modes need no controller work: `VideoRectCalculatorImpl.place` already handles them from `VideoInfo.aspectMode` (`VideoRectCalculator.kt:20-47`; API notes §8.1 last bullet).

#### 3.4.3 Selected track while the list changes

(reviewed 2026-10-15, S2) `PlayerEvent.Tracks` replaces the menu; an open picker keeps its focus by `TrackKey`. A picker closes only when the tuned identity changes; during an archive hand-off (visible `tuned.handoff`) it keeps its choices until the next `Tracks` with entries. The quick bar's value labels follow the same rule.

#### 3.4.4 Stats overlay (`StatsFormatter`, WP-C)

Toggling on calls `setStatsEnabled(true)` (M2 publishes at ≤ 2 Hz, `StatsCollector.kt:98-147`); off, and `leaveFull`, call `false`. Lines, in `StatLabel` order, skipping empty values: VIDEO `H.264 High` (mime → name + `codecs` profile), RESOLUTION `1920×1080` (+ HDR kind), FRAME_RATE `50 fps · AFR seamless · display 50 Hz`, VIDEO_DECODER name, AUDIO `E-AC-3 5.1` (+ ` · passthrough`), AUDIO_DECODER, BITRATE `7.8 Mbit/s`, BUFFER `2.4 s`, DROPPED count, FORMAT `TS` (`HLS`), HTTP `200 · 1 redirect · 2 reconnects`, SERVER `host · server 2 of 3` (from `serverIndex/serverCount`; reviewed 2026-10-15, E23, read-only, the switch is M5), CONNECTIONS `1/2 (broker)`, BUFFER_PROFILE `Auto L1 · start 1.0 s` or the manual mode, DISPLAY refresh, TUNNELING on/off. All numbers formatted with `Locale.ROOT`; the UI localises labels only.

#### 3.4.5 Screen off

SCREEN_OFF draws an opaque black full-window layer and calls `setScreenOff(true)`; the VM sets viewport HIDDEN (M2: video track disabled, audio and the connection continue, `PlaybackControllerImpl.kt:1541-1569`). Any key wakes (viewport FULL, the scrim waits for the next rendered first frame like still → FULL, M3 H-S10).

### 3.5 Scrub bar (WP-A behaviour, WP-D drawing)

#### 3.5.1 Scope and entry

- **Scope:** the programme containing the shown position (preview if any, else the playhead): `barFromMs = programme.rawStart`, `barToMs = programme.rawEnd` (programme frame, ms). Without EPG (no programme) the bar spans `[playhead − 30 min, playhead + 30 min]` clamped to `[archiveFrom, liveEdge]`.
- **Live with archive (`atLive`)**: entered with TIME_SHIFT, SEEK_BACK, PLAY_PAUSE, START_OVER; the bar scopes to the programme on now and the playhead sits on the live edge marker. A preview committed before the live edge starts time-shift (`rewindLive` with `backMs = liveEdge − preview`).
- **Archive / time-shift:** entered with LEFT/RIGHT/INFO_BAR/SEEK_*; auto-shown unfocused per §3.2.1.

#### 3.5.2 Steps and commit (`ScrubModel`)

| `heldMs` of the ←/→ event | Step |
|---|---|
| < 1,000 (incl. single presses) | 10 s |
| < 3,000 | 30 s |
| < 10,000 | 120 s |
| ≥ 10,000 | 300 s |

(reviewed 2026-10-15, N9/E21: product S8's 10 s / 30 s / 2 min, plus 5 min after 10 s of holding for long programmes.)

FF/RW: 30 s per press (`FF_RW_STEP_MS`), repeats use the table from its second row. `preview = clamp((preview ?: playhead) ± step, archiveFromMs, liveEdgeMs)`. Every step re-arms `commitAt = now + SCRUB_COMMIT_MS` (600); at the deadline (or SELECT) the machine increments its `seekSeq`, calls `archiveSeek(preview, seekSeq)` (or `rewindLive` on `atLive`), and sets `seeking`. (reviewed 2026-10-15, B3: the old "within 2 s of the target" test never held after a minute-aligned or lag-stepped re-request, and an in-buffer native seek emits no state) It clears `preview` and `seeking` when `status.settledSeq ≥ seekSeq`, wherever the playhead landed; the next step starts from the playhead. Crossing a programme boundary with the preview re-scopes the bar to that programme (`loadProgrammes` around the preview; until it answers the bar keeps its bounds and the preview runs past the end). Preview at the live edge → JUMP_TO_LIVE on commit. (code review 2026-10-16, P-S1 = U-L3) The `LIVE_SNAP_MS` (30 s) snap is **direction-aware**: a committed target snaps to live only when it is later than the playhead (in a live pause: later than the pause point); a backward step always requests the archive, so "back 10 s" never moves forward to live.

#### 3.5.3 Chips (DOWN)

`prev` = programme before the scoped one, `next` = after (from the same `around` answer). Two chips only: review F8 (the Options chip) is superseded by the owner ruling of 2026-10-15, under which the quick bar is BACK → UP from any state. SELECT on prev seeks to `prev.rawStart`; on next to `next.rawStart` (beyond the live edge → live). (code review 2026-10-16, P-B1) On the **at-live** bar (no session yet) a chip, and PREV_PROGRAMME, start the programme EPG-anchored with `playProgramme(channel, p, timeShift = true)` (raw programme times, padded, the builder subtracts the correction), never `rewindLive(now − start)`: before a session the machine's live edge is the device clock, and a distance from it landed `correction + skew` away from the programme on the panel.

### 3.6 Zap, last frame, sticky video size, subtitles

#### 3.6.1 Zap card

The passive info bar is the zap card (§0.8). The VM maps `controller.pendingZap` (an identity, `PlaybackApi.kt:13-14`) to a row with `library.rowIdOf` + `channels(...)` (cached per identity, hits only: a failed lookup is not cached; reviewed 2026-10-15, N10 / M3 H-L12) and sends `PendingZap(channel)`, so the card names the target at once during a held CH± (M2 debounce: the old channel keeps playing until the 350 ms timer, then it is stopped with its last frame).

#### 3.6.2 Sticky video size (WP-D, `AppRoot`)

`AppRoot` computes rects from `stickyVideo = if (holding) lastNonZeroVideo else video`, where `holding` starts when `playback.target.tuneId` changes while `video.width == 0`, and ends when `renderedFirstFrames` advances, the tune reaches first media (`Playing`, also for radio) or `Error` (reviewed 2026-10-15, N4: a radio channel or a failed tune never renders a frame). (reviewed 2026-10-15, S2) During an owned archive hand-off (visible `tuned.handoff`) `stickyVideo` also holds `aspectMode`, and `ArchivePlaybackImpl` re-applies the session's last explicit `setAspectMode(mode, remember = false)` after each owned `Connecting` (§3.8.2), so FILL never flickers to FIT. So between a zap's `stop()` and the new channel's first frame the `SurfaceView` keeps its rect and the frozen last frame is not stretched. Test: `StickyVideoSizeTest` (Robolectric). Whether the platform keeps the last buffer at all after `MediaCodec` release is **(verify; M6, D3)** (API notes §2.5).

#### 3.6.3 Subtitles (`SubtitleLayer`, WP-D; cues WP-C)

- **Cues:** `PlaybackControllerImpl`'s `Player.Listener` gains `onCues(CueGroup)` (the non-deprecated form, API notes §1.2) → `_cues.value = cueGroup.cues`; every tune and stop sets it to empty (`ExoPlayerImpl.stop()` resets its own cue group without a callback, API notes §1.2).
- **Layer:** `AndroidView(factory = { SubtitleView(it).apply { setUserDefaultStyle(); setUserDefaultTextSize(); setApplyEmbeddedStyles(true); setViewType(VIEW_TYPE_CANVAS) } })`, with `isFocusable = false`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS` and `importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO` set in the factory (reviewed 2026-10-15, N15: a classic `View` must never take focus from the key sink), placed with `Modifier.offset`/`size` to `videoRect` (device px → design px), only while the displayed mode is FULL and the layer is not SCREEN_OFF; `update = { it.setCues(cues) }` from `host.player.cues`. (reviewed 2026-10-15, N5) While the info, scrub or quick bar is visible `setBottomPaddingFraction(((videoRect.bottom − barTopPx + 16 × scale) / videoRect.height).coerceIn(0.08f, 0.5f))` in device px, so captions move above it; 0.08 otherwise. **(code review 2026-10-16, U-B2 / V1, lead decision; supersedes the fraction)** Media3's `SubtitlePainter` applies the fraction only to cues whose line is unset, and WebVTT "auto" lines and CEA-608 rows are `LINE_TYPE_NUMBER`, anchored to the box's bottom, so the fraction moved nothing IPTV carries. The lift is the `SubtitleView`'s **bottom view padding** in device px, `max(0.08 × videoRect.height, videoRect.bottom − (barTopPx − 16 × scale))`, capped at half the video height (`subtitleLiftPx`), with `setBottomPaddingFraction(0f)`; the padding shrinks the box every cue type is laid out in. DVB bitmap cues are scaled into the shorter box (squashed vertically) while a bar shows: accepted, checked with the DVB item in M6 (§8). The cue list is passed as a `State` and read only in `update` (U-S5).
- **Order in `AppRoot`:** `VideoLayer → AmbientBackground → HoleChrome → SubtitleLayer → key sink { screens }`. `SubtitleLayer` carries no `graphicsLayer`/alpha/clip, is not an ancestor of `VideoLayer`, and is a **direct child of the root `Box`** because it places `videoRect` relative to its parent like `VideoLayer` (reviewed 2026-10-15, N11 / M2 E-L18); `RootTreeRuleTest` adds it. `CaptionStyleCompat.createFromCaptionStyle` (the system captioning style) is what `setUserDefaultStyle` applies (API notes §1.2); M5 may add size/style settings.
- **Speculative CEA tracks:** API notes §1.3 shows a CEA-608 track is exposed for every H.264 TS without a caption descriptor; the mapper sets `TextChoice.speculative = kind in {CEA-608, CEA-708} && label == null && language == null` **(verify on the `multi` clip and real streams; owner WP-C)**. They are listed after real tracks (E14). **(code review 2026-10-16, V8)** Verified wrong for Media3 1.11.1: `DefaultExtractorsFactory` passes an empty subtitle-format list, so without a descriptor `SeiReader` creates no caption track (API notes §1.3 corrected). `TsExtractorsFactory` now calls `setTsSubtitleFormats(listOf(CEA-608))`, so every H.264/H.265 TS stream has one speculative CC track (empty when nothing is broadcast). Tests: `TsExtractorsFactoryTest.multiClipListsOneSpeculativeCea608Track` (fixture `h264_multi_cc_1s.ts`), `TracksTest.speculativeCea608OnTsWithoutDescriptor` (channel 7: Subtitles item, CC1 cues decode).

### 3.7 Error cards (WP-A mapping, WP-D drawing)

**Card** (centre, 720 wide): channel logo 48 + name (19, .7); headline (the reason, `ErrorText.headline`, 30/600, two lines max); detail line (mono 15, .55): fields present in `ErrorDetail` joined by ` · `: `HTTP {status}`, `{host}`, `{codec}`, `{decoderName}`, `{active}/{max} connections`, then the code key (`play.forbidden`); a retry line when `retry != null`: "Still trying… attempt {n} of {max}" or "Still trying… next attempt in {s} s" (unlimited L19 behind the card, M2 §4.3); the next-step line (19, .75) for `textStep`; buttons (19/600, 14 × 26 padding, focused = FG fill + accent ring).

(reviewed 2026-10-15, S8/F5: the proposal's "RETRY prepended" rule contradicted its own table and offered buttons that loop or lose the position)

> **Buttons from steps** (`ErrorCardModel`): walk `error.actions` in order. A step whose M4 button **applies** adds it; the first step without one becomes `textStep`. A button applies unless:
> - TRY_SOFTWARE_DECODER: the channel already runs `SOFTWARE_PREFERRED`, or `detail.codec` starts with `audio/`;
> - TRY_OTHER_CATCHUP_FORMAT: no variant is left;
> - PREVIOUS_CHANNEL: the ladder already filtered it.
>
> Then always append GUIDE. RETRY appears only when the code's own `actions` contain it; no button is ever prepended. On an archive error, RETRY calls `ArchivePlayback` (re-request open-ended from the last `wallMs`), never `controller.retry()`. STOP calls `stopPlayback()` and then shows the stopped hint (N12).

(reviewed 2026-10-15, N12) The stopped hint: after STOP the bare video shows a centred line "Stopped to free the connection. Press OK to play again."; SELECT on `Stopped(USER)` calls `retry()`.

| `NextStep` | M4 button | Effect |
|---|---|---|
| RETRY | RETRY | `retry()` (archive: re-request at the playhead, F5) |
| PREVIOUS_CHANNEL | PREVIOUS_CHANNEL | `previousChannel()` (the ladder offers it only when one exists, `PlaybackControllerImpl.kt:1403`) |
| WATCH_LIVE | WATCH_LIVE | `jumpToLive()` (tunes the live channel) |
| TRY_OTHER_CATCHUP_MODE | TRY_OTHER_CATCHUP_FORMAT (only while `ArchivePlayback` has a variant left, else text) | `tryNextCatchupFormat()` |
| TRY_SOFTWARE_DECODER | TRY_SOFTWARE_DECODER (not when already software or the codec is audio) | `setDecoder(SOFTWARE_PREFERRED)` (re-prepares) |
| AUDIO_OPTIONS | AUDIO_OPTIONS | AUDIO picker |
| STOP_AND_WAIT | STOP | `stopPlayback()` (frees the slot on the provider), then the stopped hint (N12) |
| ACCOUNT_DETAILS, EDIT_LOGIN, PLAYLIST_SETTINGS, DIAGNOSTICS, CHANGE_USER_AGENT, USE_SECURE_DNS, REFRESH_PLAYLIST, OPEN_EXTERNAL, TRY_BACKUP | none in M4 → `textStep` | M5 routes (§7 item 8) |

Per code (`PlaybackContracts.kt:126-152`; headline strings exist, `strings_errors.xml`):

| Code | Detail fields | Buttons (with default actions) | Text step |
|---|---|---|---|
| AUTH_REJECTED | host | Guide | "Update your login in Settings" (ACCOUNT_DETAILS first: "Check your account in Settings") |
| ACCOUNT_EXPIRED, ACCOUNT_DISABLED | host | Guide | ACCOUNT_DETAILS |
| NEEDS_REAUTH | — | Guide | EDIT_LOGIN |
| CREDENTIALS_UNAVAILABLE | — | Retry, Guide | — |
| CONNECTION_LIMIT | status, host, active/max | Retry, Stop, Guide | — |
| CONNECTION_LIMIT_LOCAL | active/max | Retry, Guide | — |
| FORBIDDEN | status, host | Retry, Guide | CHANGE_USER_AGENT |
| CHANNEL_OFFLINE | status, host | (Try backup filtered in v1) Previous channel, Retry, Guide | — |
| FORMAT_REFUSED | status, host | Guide | PLAYLIST_SETTINGS |
| WEB_PAGE_INSTEAD | host | Retry, Guide | ACCOUNT_DETAILS (first) |
| EMPTY_RESPONSE, NO_DATA_TIMEOUT, STREAM_INTERRUPTED, CONNECT_FAILED, INTERNAL | status/host as present | Retry, Guide | DIAGNOSTICS |
| SERVER_ERROR | status, host | Retry, Guide | — |
| DNS_FAILURE | host | Guide (no Retry: E8) | USE_SECURE_DNS |
| TLS_FAILURE | host | Guide | PLAYLIST_SETTINGS |
| CODEC_UNSUPPORTED | codec, decoder | Software decoder (video only, not already software), Guide | OPEN_EXTERNAL |
| STREAM_UNDECODABLE | codec | Retry, Guide | OPEN_EXTERNAL |
| AUDIO_OUTPUT_FAILED | codec | Audio options, Guide | — |
| UNSUPPORTED_SOURCE | — | Guide | OPEN_EXTERNAL |
| CATCHUP_NOT_IN_ARCHIVE | status, host, variant | Watch live, Try other catch-up format (if a variant is left), Guide | TRY_OTHER_CATCHUP_MODE text when no variant is left: "Try another catch-up mode in playlist settings" |
| CHANNEL_NOT_FOUND | — | Guide | REFRESH_PLAYLIST |

`ErrorCardModelTest` asserts for every code × {defaults, ladder-narrowed} that the card has a non-empty headline, at least one button and either a button or a text step for the first action (never a bare card), and that no RETRY appears for a code whose `actions` lack it. The compact mini card (M3 §3.13.4) uses the same `actions` for its one-line step (M3 H-L9). `ArchiveStatus.phase == FAILED` with `failure` ≠ PLAYBACK shows the same card with `error = PlaybackError(CATCHUP_NOT_IN_ARCHIVE)` for NOT_IN_ARCHIVE, or the M3 `CatchupFailure` headline/step pair for the build failures (CHANNEL_GONE … CREDENTIALS) with buttons Watch live, Guide.

### 3.8 `ArchivePlayback` (WP-B)

#### 3.8.1 Three time frames

For one channel, with `corr` = the channel's `catchup-correction` else the playlist's (`TimeBase.archiveStart`, `TimeBase.kt:58-66`), `skew` = `TimeBase.clockSkew`, `shift` = `GuideChannel.epgShiftMin`:

| Frame | Meaning | Relation |
|---|---|---|
| **archive** (A) | the panel's own clock; `ArchiveRequest.requestStart` and every URL token live here (the builder subtracts the correction, `CatchupUrlBuilderImpl.kt:12-18`) | A = P − corr |
| **programme** (P) | raw EPG UTC (`ProgrammeInfo.rawStartMin`); the frame M3 already requests in (`CatchupLauncherImpl.kt:52-56`; tvg-shift never changes request times, architecture review B5.2) | P = A + corr |
| **display** (D) | what the guide shows (`startMin`) | D = P + shift |

`ArchiveStatus`, `ScrubState` and every planner input are in P. Playhead: `wallMs = req.originMs + positionMs` with `originMs = requestStart(A) + corr` (M2 §3.14: "archiveStartUtcMs + positionMs", here corrected into P). Live edge (reviewed 2026-10-15, B2/F1: the proposal's `min(0, corr)` cap made every live-anchored request on a positive-correction channel start `corr` hours in the past, and hid a frame-mixed builder check): `ArchiveFrames.liveEdge(deviceNow) = deviceNow + skew + corr`, the panel's now expressed in P. A live-anchored instant (pause point, RW target, time-shift preview) is taken from it; the builder subtracts `corr` again, so the request's A-frame start is the panel's own clock for every sign of `corr`. The builder's "not started" check compares the corrected start with the panel's now (§1.7 item 9). Consequence for a negative correction (archive clock ahead of the EPG): the EPG's on-now programme starts after the live edge in P, so "Start over" of it is refused as not started (Failed(NO_PROGRAMME) toast); that is the documented meaning of the correction (research §4.4) and E24 checks it on a real provider. Archive start: `liveEdge − days × 86,400,000 + EDGE_MARGIN_MS (60,000)` (the builder's `EDGE_MARGIN_SEC`, `CatchupUrlBuilderImpl.kt:164`). Labels add `shift` (D). Device-local time is never used for a token (CLAUDE.md): only the builder formats instants, in the panel zone (`effectiveTokenZone`).

#### 3.8.2 Session and orchestration (`ArchivePlaybackImpl`)

State (main-confined): `channel`, `target` (from `ArchiveTargets.catchupTarget`, with `winner` filled from `CatchupProbeStore.winner(playlistId)` because the resolver passes null, `StreamUrlResolverImpl.kt:82-83`), `timeBase` (`TimeBaseLookup`, `CatchupLauncherImpl.kt:20`), `frames`, `variants = builder.variants(target)`, `variantIndex`, `request: ActiveRequest?`, `ownedTuneId` (B1, below), `firstMediaSeen`, `pausedAtMs`, `pausedSinceMs`, `lagRetries`, `midPlayRetries`, `phase`.

(reviewed 2026-10-15, B1) **Issuing a request:** the session first sets the next phase (STARTING / PROBING / CONTINUING / SEEKING), then calls `controller.tuneArchive`, then, in the same main-thread turn, reads `controller.state.value.target?.tuneId` (the controller's `startCycle` emits the new cycle's `Connecting` synchronously, `PlaybackControllerImpl.kt:432-458`) into `ownedTuneId`, and only then publishes `status` once. The review asked for `ownedTuneId` "before `tuneArchive`"; the controller assigns the tuneId inside the call, so this same-turn read is the equivalent: no collector can observe the new cycle before the status that owns it. **Invariant (WP-B):** after any owned `Error` the session does not act on, it goes FAILED in the same main-thread turn, so an owned `Error` is always either about to be replaced by a new request or reported as FAILED. Both view models show `ArchiveVisibility.visible(tuned, status)` (§2.3a).

Ownership rule: the session owns playback while `controller.state.target` is our identity with `isArchive == true` **and** `tuneId ≥ ownedTuneId` (later tuneIds of our cycle, ladder re-prepares and lifecycle restarts, are ours too), or while `phase == PAUSED_LIVE` and the target is our identity live (Playing paused or `Stopped(LIVE_PAUSE_TIMEOUT)`). Any other target (a zap, a guide tune, a reminder switch, Stop) ends the session: `phase = IDLE`, nothing is sent. `jumpToLive` ends it by tuning live.

Observed transitions (collector on `controller.state`):

| Controller state for our request | Session reaction |
|---|---|
| `Connecting` / `Buffering` | phase stays (STARTING / SEEKING / CONTINUING / PROBING) |
| first `Playing(paused = false)` of an owned cycle | `firstMediaSeen = true`; phase PLAYING; if `variantIndex > 0` (or the winner was absent and index 0 is not the stored one) → `store.setWinner(playlistId, variant)` once per session; `lagRetries = 0`; re-request settle (`settledSeq = pendingSeq`, B3); (reviewed 2026-10-15, S2) re-apply the session's last explicit `setAspectMode(mode, remember = false)` (audio/subtitle choices need nothing: the pickers remember the language per channel, so the new cycle's defaults pick them again); if the session was PAUSED when the request was issued (a seek committed while paused, E26) → `controller.pause()`, phase PAUSED; start polling |
| `Playing(paused = true, ended = false)` | PAUSED |
| `Playing(ended = true)` | `planner.onProgress(…, pos.ended = true)` → execute |
| (reviewed 2026-10-15, S3) `Error(c)` before first media of the owned cycle, and a **variant failure**: `c == CATCHUP_NOT_IN_ARCHIVE` (archive 404/410, ladder L13), or `detail.httpStatus == 400` (new ladder row L13a, §3.8.8), or `c == FORBIDDEN` (403 after the ladder's L2–L6 ran: not a connection limit), or `c ∈ {FORMAT_REFUSED, WEB_PAGE_INSTEAD, EMPTY_RESPONSE, UNSUPPORTED_SOURCE, SERVER_ERROR}` | `planner.onNotInArchive` (lag rule first, then next variant, §3.8.8) |
| `Error(CONNECTION_LIMIT)` | the ladder already retried within the cycle (L2 RetrySame 1.5 s, L3 ReResolve at 3 s, then L4, M2 §4.3) before the `Error` reaches the session, which covers a panel lingering on the previous request (lead sign-off S3): FAILED(PLAYBACK); the card offers Retry and Stop |
| `Error(c)` after first media, `c ∈ {STREAM_INTERRUPTED, NO_DATA_TIMEOUT, CONNECT_FAILED, EMPTY_RESPONSE, SERVER_ERROR, CATCHUP_NOT_IN_ARCHIVE}`, `midPlayRetries < ARCHIVE_MIDPLAY_RETRIES` (1) | re-request open-ended from the last known `wallMs` (token expiry after a long pause, a dropped connection the archive transport does not reconnect, M2 §3.4); `midPlayRetries` resets after `MIDPLAY_RESET_MS` (300,000) of PLAYING |
| any other `Error` | FAILED(PLAYBACK) at once, `error` = the controller's (auth, account, limit-local, codec: another open cannot help, plan §3 "401 → no loop") |
| (reviewed 2026-10-15, N13) `Stopped(BACKGROUND / STANDBY / HDMI_UNPLUGGED / DISPLAY_OFF)` | phase PAUSED, polling off; the controller's lifecycle resume restarts the same request at `archiveResumeAtMs` (`PlaybackControllerImpl.kt:1802-1803`) under a later tuneId, which the ownership rule accepts |

Polling: while phase ∈ {PLAYING, SEEKING, CONTINUING}, every `ARCHIVE_POLL_MS` (1,000) read `controller.position()` and `controller.state`, compute `wallMs`, re-scope `programme` when `wallMs` crosses `programme.rawEnd` (`ProgrammeFeed.at(channel, displayMin(wallMs))`, no request), call `planner.onProgress`, run the native-seek settle check (§3.8.4), publish `status`. Paused or idle: no polling (the UI extrapolates; §3.2.3).

#### 3.8.3 Building a request

`RequestPlan` → `ArchiveWindow(programmeStart = Instant(plan.startMs), programmeStop = plan.stopMs?.let(Instant), deviceNow = Instant(now))` and `builder.build(target.copy(winner = …), tb, window, variants[variantIndex])` with `tb = if (plan.padBefore) timeBase else timeBase.copy(paddingBefore = Duration.ZERO)`. Only the first request of a programme pads (the product's "programme start − 1 min", §3.7); seeks, continuations and pause resumes must start where the user is. `startOffsetMs = planner.startOffset(plan.targetMs, request.requestStart.toEpochMilli(), frames)` = `max(0, target − (requestStartA + corr))`, passed in `ArchiveTune` (M2 seeks before prepare when seekable; an unseekable map treats it as 0, API notes §2.1). Then `controller.tuneArchive(ArchiveTune(identity, request, startOffsetMs))`. `CatchupBuild.Unsupported` → FAILED(UNSUPPORTED_MODE) (first request) or the next variant (probing). Diagnostics (`PanelZoneUnknown` …) are kept on the session and logged once; they are shown in the FAILED card's detail ("panel time zone unknown") **(M5 shows them in channel info)**.

**Minute alignment.** `xc-path` and `xc-php` format the start at minute resolution (`TokenFormatter.XC_MINUTES`), but `ArchiveRequest.requestStart` keeps the seconds (`CatchupUrlBuilderImpl.kt:62,:88`). A start that is not on a minute would put the media origin up to 59 s before `requestStart` and skew every wall position. `planner.alignStart(variant, target, frames)` floors the requested start to the minute **in the archive frame** for those two variants, `programmeOf(floorToMinute(archiveOf(t)))` (reviewed 2026-10-15, N2: a fractional-minute correction or the builder's edge-margin branch would unalign a P-frame floor; `ArchiveFrames.correctionMs` uses `TimeBase`'s rounding) (`xc-path-sec`, Flussonic, shift, default and append are exact) and keeps `targetMs` unaligned, so `startOffset` covers the difference when the file is seekable. Whether `TokenFormatter` floors or rounds is irrelevant once the input is aligned; WP-B adds a test that `requestStart` equals the aligned instant minus correction.

#### 3.8.4 Seek (`planner.seek`)

1. `t = clamp(target, live.archiveFromMs, live.liveEdgeMs)`; `|t − wall| < SEEK_NOOP_MS` (1,000) → None.
2. `t > wall && t ≥ live.liveEdgeMs − LIVE_SNAP_MS` (30 s; user seeks only, S1) → Live. (code review 2026-10-16, P-S1 = U-L3: direction-aware; a target at or before the playhead never snaps forward to live. `ArchivePlaybackImpl.seekFromLivePause` applies the same rule against the pause point.)
3. Native window = `[req.nativeFromMs, req.nativeToMs]` if present, else, when `pos.isSeekable && pos.durationMs != null`, `[req.originMs, req.originMs + durationMs − END_GUARD_MS (5,000)]`. `t` inside → `Native(t − req.originMs)` (`controller.seekTo`).
4. Otherwise → `Rerequest(RequestPlan(startMs = alignStart(variant, t, frames), stopMs = null, padBefore = false, targetMs = t))`.

(reviewed 2026-10-15, B3: an in-buffer native seek stays in `STATE_READY` and emits no new controller state, so "until the next `Playing`" never ended) **Settling.** `seekTo(programmeMs, seq)` stores `pendingSeq = seq`. Native: phase SEEKING until the first poll at least `SEEK_SETTLE_MIN_MS` (250) after `controller.seekTo` where the state is `Playing(paused = false)` and the position advanced by ≥ 200 ms since the previous poll, or `SEEK_SETTLE_MAX_MS` (5,000) after it, whichever comes first (polling runs at 250 ms while SEEKING, `ARCHIVE_POLL_MS` otherwise). Re-request: until first media of the issued tuneId. Either way `status.settledSeq = pendingSeq` and phase PLAYING (or PAUSED, S2). `None` and `Live` plans settle at once. HLS VOD archive (`.m3u8` with ENDLIST) is seekable with a known duration (API notes §2.4); an HLS EVENT/live archive (`-now.m3u8`, `timeshift_abs`) reports a moving window, so `isSeekable` is true but `durationMs` grows and `positionMs` is window-relative: the planner treats `req.endMs == null && pos.durationMs == null`, and any HLS request without `nativeSeekRange`, as **re-request only** **(verify the EVENT origin on real Flussonic; owner WP-B, §8)**.

#### 3.8.5 Continuation (`planner.onProgress`)

- `req.endMs != null` (fixed range) or the file has a duration: `remaining = durationMs − positionMs`. When `pos.ended` or `remaining ≤ CONTINUE_LEAD_MS` (3,000): if `live.liveEdgeMs − wall ≤ CONTINUE_SNAP_MS` (5,000) → Live, else `Rerequest(start = alignStart(variant, wall, frames), stop = null, padBefore = false, target = wall)` (phase CONTINUING). `LIVE_SNAP_MS` (30 s) applies to user seeks only (§3.8.4); a continuation never drops content the user chose to be behind. (reviewed 2026-10-15, S1/F7)
- Open request without duration (shift, Flussonic open forms): Keep; it is a growing stream that ends only if the panel ends it (`ended` → as above).
- The xc open request is finite (`D = min(now − S, 180 min)`, `CatchupUrlBuilderImpl.kt:68`), so it continues by the first rule every `D` of playback. For a shallow time-shift (a 2-minute pause) that is a re-request every ~2 minutes; the hook `XC_OPEN_EXTRA_SEC` (0) in §7 item 5 extends `D` past now if D10 shows panels serve growing files.
- Programme boundaries never cause a request (the status re-scopes, §3.8.2).

#### 3.8.6 Hand-off cost

A continuation or seek re-request is one `tuneArchive`: M2 cancels the old call before the new open (broker pre-emption, M2 §3.5.2) and `player.stop()` keeps the last frame (M2 §3.1.1 step 2); the root keeps the rect (§3.6.2). Expected freeze: first media of an archive request (≈ the emulator's 0.4–0.6 s tune, M2 §5.8) plus the finite-TS prepare's extra opens (head, tail 112,800 bytes, head again, API notes §2.1) under the same lease. The scrub bar shows `seeking` (spinner on the playhead) and the pill keeps its last value: the hand-off is hidden by `ArchiveVisibility` (B1), and the picker, aspect and pause state carry over (S2). With `max_connections = 1` and a panel that lingers, the tail/head reopens of one session are sequential calls under one lease, not new connections from the panel's point of view **only if** the panel releases a slot on close; a lingering panel may refuse them **(verify with fake-panel `linger` on timeshift, I-M4-14; API notes §7 gap 4)**.

#### 3.8.7 Pause on live, archive lag, RW, start over

- `pauseLive(channel)`: `pausedAtMs = max(0, frames.liveEdge(now) − controller.position().bufferedMs)` (reviewed 2026-10-15, N3: what is on screen is behind the panel's now by the live buffer), `pausedSinceMs = now`, `controller.pause()` (live freeze; M2 releases the connection after `LIVE_PAUSE_RELEASE_MS` = 10 s), phase PAUSED_LIVE, `timeShift = true`.
- `resume()` from PAUSED_LIVE: `planner.resumeFromPause(pausedAtMs, now − pausedSinceMs, live)`: paused less than `PAUSE_MIN_FOR_ARCHIVE_MS` (3,000) → Live (`controller.resume()`; M2 re-tunes at the live edge, `LiveResumed` notice suppressed by the VM because it is expected); else Rerequest(start = alignStart(variant, pausedAtMs, frames), open, target = pausedAtMs).
- Archive lag: a request whose start is within `ARCHIVE_LAG_GUARD_MS` (120,000) of the live edge that fails with CATCHUP_NOT_IN_ARCHIVE before first media is **not** a variant failure: `StepBack(ARCHIVE_LAG_STEP_MS = 60,000)` once (same variant, start 60 s earlier, target unchanged so `startOffset` restores the position when seekable), then `LiveNotReady` (tune live, toast ARCHIVE_NOT_READY "This channel's archive isn't ready yet. Back to live."). Neither persists a winner nor marks the channel suspect.
- `rewindLive(channel, backMs)`: Rerequest(start = alignStart(variant, liveEdge − backMs, frames), open, target = liveEdge − backMs), `timeShift = true`. The lag rule applies. With F1 the request's A-frame start is `panelNow − backMs` for every sign of the correction (B2; I-M4-13b).
- `startOver(channel)`: `ProgrammeFeed.at(channel, nowDisplayMin)`; none → FAILED(NO_PROGRAMME) (toast, stays live); else `playProgramme(channel, info, rawEndMin, timeShift = true)` (padded first request, open-ended because it is still airing).
- Pausing an archive request is a real pause (M2). Resuming after `ARCHIVE_PAUSE_REBUILD_MS` (600,000) re-requests from `wallMs` instead of `controller.resume()` (tokens and panel sessions expire; the mid-play retry would catch it too, one error later).

#### 3.8.8 Variant probing (`planner.onNotInArchive`)

1. Lag rule first (§3.8.7) when the request start is within `ARCHIVE_LAG_GUARD_MS` of the edge: a lag 404 never counts as a variant failure (S3).

(reviewed 2026-10-15, S3) A variant failure is any row of §3.8.2's first `Error` row. HTTP 400 on an archive request reaches the session as CATCHUP_NOT_IN_ARCHIVE through a new ladder row **L13a** (archive session, `Http(400)`, before first byte → Fail(CATCHUP_NOT_IN_ARCHIVE, detail.httpStatus = 400); WP-C, `core/.../playback/errors/ErrorLadderImpl.kt` next to L13 at `:370-371`). 403 is deliberately **not** a variant failure on the first open, against the review's wording: a 403 right after a live → archive switch is usually the panel lingering on the live connection, which L2–L4 handle; only a 403 that the ladder ends as FORBIDDEN (UA tried, account shows a free slot) counts. The variant that then plays is persisted, so a lingering 403 can never store a wrong winner.
2. `firstMediaSeen` (the variant worked earlier in this session, e.g. a later seek hit a hole): `midPlayRetries` rule of §3.8.2 once, then Fail.
3. `variantIndex + 1 < variantCount` → `NextVariant(variantIndex + 1)`: phase PROBING, the same `RequestPlan` (window, target), status `attempt/attempts`; the player shows "Trying another catch-up format (2/3)" in the bar.
4. Otherwise Fail: phase FAILED, `failure = NOT_IN_ARCHIVE`, `error` = the controller's error. The guide machine adds the channel to `suspectArchive` (hollow ↺) on this status only (§0.6).

`tryNextVariant()` (error card) continues the walk from the current playhead (or the programme start) with `variantIndex + 1`; false when the list is exhausted (the button is not offered then, §3.7). The probe order is `builder.variants(target)` with the stored winner first (`CatchupUrlBuilderImpl.kt:23-41`; API notes §6 lists the per-mode orders). Persisting happens only after first media with a variant other than the list's first (§3.8.2), through `PlaylistDao.setCatchupProbeWinner` (one-column `UPDATE`; never the whole-row `PlaylistRepository.update`, M2 N-L13).

#### 3.8.9 `playProgramme` (first request)

1. Target (`ArchiveTargets`): `ChannelGone` → Failed(CHANNEL_GONE); `CredentialsUnavailable` → Failed(CREDENTIALS) (M3 S13 semantics kept).
2. `catchup.isAvailable`, else NO_ARCHIVE; `rawStart ≥ liveWindow.archiveFrom − EDGE_MARGIN`, else OUTSIDE_ARCHIVE (the builder repeats the check without padding, `CatchupUrlBuilderImpl.kt:56-61`).
3. `planner.programmeRequest(rawStartMs, rawEndMs if ended else null, live)`: `padBefore = true`, `target = startMs − paddingBefore` (so the status playhead and the bar start where the media starts).
4. Build with `variants[0]` (winner first) → `tuneArchive`; phase STARTING; `ArchiveStart.Started`.

The M3 launcher contract is unchanged (`CatchupLauncher.play`), so `GuideEffectsImpl.playArchive` and `SearchViewModel` need no change; the launcher maps `ArchiveFailure` → `CatchupFailure` (NOT_IN_ARCHIVE/NO_PROGRAMME/PLAYBACK do not occur synchronously).

#### 3.8.10 Status publication

`status` is a `MutableStateFlow` written on main: on every phase change, on every poll (≤ 1 Hz), on programme re-scope. `atMs` = device wall clock at the write; consumers extrapolate `wall + (now − atMs)` while `advancing` and `liveEdge + (now − atMs)` always. The guide VM forwards it as `GuideEvent.Archive`, the player VM as `PlayerEvent.Archive`; neither recomposes per second because the pill and the scrub playhead tick in the draw phase (§4).

### 3.9 Layout (WP-D; design px on the 1920 × 1080 canvas)

| Element | Rect | Style |
|---|---|---|
| Bottom scrim (under info/scrub/quick bar) | (0, 700) – (1920, 1080) | vertical gradient black 0 → .55, drawn by the bar's host, sibling over the video |
| Info bar / scrub bar | (64, 820) 1792 × 220 | fill `#0A0D13` at .78, 1 px border white .09, radius 24, padding 28 × 24; no blur (plan §3) |
| Info header row | inside, top | number chip (mono 16, 8 % fill, radius 8), logo 64 × 64 radius 12, name 24/600, state pill (§3.2.3), glyphs right (mono 15 .7: ↺ 7d · 1080p50 · 5.1 · CC · HDR) |
| Info programme row | below | focused programme title 30/600 one line; time row mono 15: `19:30 ━━━━━─── 20:15` 4 px bar (white on 20 %); for a browsed past/future programme the bar is replaced by "Catch-up · press OK" / "Starts 21:00" (19, .7) |
| Info next row | bottom | "Next 20:15 · Title" 19 .65; while browsing: "← → channels · ↑ ↓ programmes" hint 15 .5 |
| Scrub track | (128, 952) 1664 × 6, radius 3 | base white .08; archive-available span white .22; played span FG; future after the live edge none; the part before the archive limit white .05; live-edge marker 2 × 18 `#FF4D5E`; playhead 14 px circle FG (accent 3 px ring when focused); preview: hollow 14 px accent circle + label bubble |
| Scrub labels | under the track | left `barFrom` (mono 15), right `barTo`, centre playhead clock `19:42:10` mono 18 and `−00:17:50` (BEHIND) or `Catch-up` |
| Scrub chips | row under the labels when CHIPS | two chips: `◀ 18:30 Previous title` / `Next title 20:15 ▶` 17, focused chip FG fill (no Options chip: owner ruling) |
| Channel list panel | (48, 48) 620 × 984 | fill `#0A0D13` .86, border .09, radius 24; header 64 (group name 22/600 + count .5); Previous row 72 (accent "Previous" label 15/600); rows 72, pitch 80, 10 visible; focused row FG fill dark text; GROUPS column (48, 48) 260 × 984 slides over the channel rows (channels dimmed to .4) |
| Quick bar (owner ruling 2026-10-15) | (64, 952) 1792 × 88 | black .55 fill (translucent, no blur), radius 24, padding 12; items are pills 64 high, padding 0 × 24, gap 12, label 19/600 + value 17/400 .7 ("Audio · English 5.1"); focused pill FG fill dark text with accent ring; overflow scrolls horizontally keeping the focused pill inside 64 px of either end (`LazyRow` with `requestScrollToItem`, API notes §4) |
| Picker sheet | (1376, 48) 496 × 880 (ends above the quick bar) | fill `#0A0D13` .86, border .09, radius 24; items 64 high 21/500; picker title 24/600; check mark for `current`; Remember row with a box glyph |
| Stats overlay | (64, 56) 600 wide, auto height | black .62, radius 16, mono 16 lines of 24, label .55 / value FG |
| Error card | centred, 720 wide | fill `#0A0D13` .94, border .12, radius 24, padding 36 |
| Number entry | (1520, 72) 320 × 160 (M3) | + logo 40 left of the name line, now title 15 .6 under it (§3.10.4) |
| Group card | (64, 56) (M3) | unchanged |
| Toasts in FULL | left 64, bottom 96; bottom 276 while the info/scrub bar is visible, 156 over the quick bar | M3 style |
| Reminder card in FULL (reviewed 2026-10-15, N8) | right 64, bottom 96; bottom 276 over the info/scrub bar, 156 over the quick bar; right edge 568 from the screen edge while the picker sheet is open | M3 card |
| Subtitles | `videoRect` | `SubtitleView` (§3.6.3) |

Every overlay is a sibling inside `FullScreenHost` (under the key sink), never an ancestor of `VideoLayer`; fades use `AnimatedVisibility(enter = fadeIn(tween(150)), exit = fadeOut(tween(150)))` on the overlay's own content (API notes §4), snapped in low-end mode. No `rememberInfiniteTransition` anywhere in FULL (it requests every frame, API notes §4); the LIVE dot reuses M3's `LiveDot` (1 Hz step in low-end).

### 3.10 View-model glue (WP-D)

#### 3.10.1 `PlayerViewModel`

`@HiltViewModel`, injected with `PlaybackController`, `ArchivePlayback`, `ProgrammeFeed`, `GuideLibrary`, `GuideRowsCacheFactory`, `PlayerSettingsSource`, `GuideSettingsSource` (unlocked/low-end), `DeviceClassSource`, `PlayerStateMachineFactory`, `PlayerDebugProbe`, Coil `ImageLoader` (via the shell). It creates `listRows = rowsFactory.create(library, viewModelScope)`, the machine with `PlayerEffectsImpl(this)`, registers it with the probe, and forwards:

| Source | Event |
|---|---|
| `combine(controller.state, archive.status)` + channel lookup (cached per identity, hits only, N10) | `Tuned(ArchiveVisibility.visible(TunedStateMapper.map(…), status))` (reviewed 2026-10-15, B1; the guide VM forwards `GuideEvent.Tuned` the same way) |
| `controller.pendingZap` | `PendingZap` |
| `controller.tracks` | `Tracks` (`PlayerMappers.trackMenu`) |
| `controller.video` | `Video` |
| `controller.stats` (only while stats are on) | `Stats` |
| `controller.previous` | `Previous` (row lookup) |
| `archive.status` | `Archive` |
| `library.sources(unlocked)` | `Sources` |
| `listRows.changes` | `ListRows` |
| now cache bumps | `ListNow` |
| `settings` | `Settings` |
| `controller.notices` | toasts (§3.10.2) |

Ticker: one coroutine under `repeatOnLifecycle(STARTED)` (M3 H-L11) that dispatches `Tick` at `machine.nextDeadlineMs` and on every minute boundary, using `delay` (monotonic; M3 H-L2), at least 16 ms apart, woken early when the deadline moves earlier (`GuideViewModel.nextTickAt` pattern). (reviewed 2026-10-15, S9) `MainActivity` creates both view models and calls `guide.attachPlayer(player.machine)` (sets `guideMachine.full`) **before** `guide.startup()`; the guide VM forwards `GuideSourceNow` from its own lineup flow. `PlayerEffects.openSettings()` → `guideMachine.dispatch(GuideEvent.OpenSettings)`; `toggleReminder` → the M3 reminder path (`GuideEffectsImpl.setReminder/cancelReminder` logic via `ReminderBook`, toasting `GuideToast.REMINDER_SET`/`REMINDER_CANCELLED`).

#### 3.10.2 Notices → toasts

| `PlaybackNotice` | Toast (strings_player.xml) |
|---|---|
| `Reconnecting(n, m)` | "Reconnecting… ({n}/{m})" (replaces the previous one; not shown in GUIDE, where the mini pill already says it) |
| `Reconnected` | "Reconnected" |
| `SwitchedToBackupServer(i)` | "Switched to backup server {i+1}" |
| `SwitchedFormat(to)` | "The server refused MPEG-TS, switched to HLS" (or the reverse) |
| `AudioDecoderSwitched(sw, mime)` | "Audio decoder switched to software ({codec})" |
| `PassthroughDisabled(mime)` | "Your audio system didn't play {codec}; the TV box is decoding it now" |
| `VideoDecoderSwitchedToSoftware` | "Using the software video decoder for this channel" |
| `TunnelingDisabled` | "Tunneled playback turned off for this channel" |
| `UserAgentSwitched(preset)` | "Playing with a different user agent ({preset})" |
| `BufferIncreased(level)` | "Buffer increased to {s} s for smoother playback" (level → `afterRebufferMs` seconds) |
| `LiveResumed` | "Back to live" (suppressed when the archive session asked for it) |
| `Warning(NO_AUDIO / AUDIO_UNSUPPORTED)` | "No sound: this channel's audio can't be played here. Press OK, then Audio, to try another track." |
| `Warning(SOFTWARE_VIDEO)` | as VideoDecoderSwitchedToSoftware |
| `Warning(PASSTHROUGH_DISABLED)` | as PassthroughDisabled |

Each toast states what happened and, where the user can act, how (CLAUDE.md error rule). Notices are sent as `ShellEvent.Message` through the player host's buffered channel (M3 H-B3).

#### 3.10.3 On-launch mode and the guide VM

`GuideViewModel.startup()` (`GuideViewModel.kt:387-400`; reviewed 2026-10-15, S9: the proposal cited `:309-322`, which are the window/generation/reminder collectors) reads `PlayerSettings.onLaunch`: `GUIDE_WITH_LAST` = M3 behaviour; `FULL_LAST` = the same tune plus `machine.dispatch(GuideEvent.StartFull)` (the machine's existing `pendingFull` path, `GuideStateMachineImpl.kt:1150`); `GUIDE_NOTHING` = no tune. The guide VM also forwards `PlayerSettings` (`GuideEvent.Player`) so the guide's number entry uses `numberEntryWaitMs` (`NumberEntryImpl.setCommitAfterMs`; `NUMBER_COMMIT_MS` stays the default).

#### 3.10.4 Number entry preview (FULL and GUIDE)

The guide machine's `Overlay.Number.preview` (a `GuideChannel`) already arrives after `NUMBER_PREVIEW_MS` (`NumberEntryImpl.kt:66-71`). M4 draws the preview's logo and number, and in FULL also the channel's now title (the VM answers `ProgrammeFeed.at` for the preview channel and keeps it in a one-entry cache keyed by `identityHash`, read by the overlay). Strings move to resources ("No channel {n}", content descriptions; M3 H-L7).

#### 3.10.5 Effects implementation notes

- `tune(channel, lineup)` → `controller.tune(TuneTarget.Channel(identity), lineup?.let { LineupZapList(it, library) })` (M3 `LineupZapList`, S9).
- Archive effects → `viewModelScope.launch { archive.xxx(...) }`; a `Failed` result → toast CATCHUP_FAILED with the reason (M3 `ErrorText.catchupHeadline/Step`).
- `setScreenOff(on)` → `ShellHost.setViewport(if (on) HIDDEN else FULL)` through the root's viewport rule: the root reads `player.ui.layer == SCREEN_OFF` and maps it to `HoleMode.COVERED` with a black fill.
- `loadChannel(token, from, delta)` → `LineupZapList` of the current zap lineup `.neighbour(from.identity, delta)` then a row lookup (no new list type).

---
## 4. Rendering and memory budget

### 4.1 Per-frame draw cost

FULL draws video through the `SurfaceView` (no app GPU work) plus at most: the bottom bar, the stats overlay, one side panel and the subtitle view. Budget: **≤ 1 ms main-thread draw per frame P90** for the whole FULL overlay set on the reference device (logged on the emulator, asserted in M6), and **no frame at all** when nothing changes (idle FULL with no overlay must produce zero app frames: no infinite transitions, no tickers that invalidate draw while the bar is hidden).

| Component | Per-frame work when it redraws | When it redraws |
|---|---|---|
| Scrub bar (`ScrubBarRenderer`, one `Spacer.drawBehind`) | ≤ 14 ops: track base, archive span, played span, live-edge marker, playhead (circle + ring), preview circle, 3 text runs via `drawIntoCanvas { nativeCanvas.drawText(chars, 0, n, x, y, paint) }` | playhead: once per second while advancing (a `withFrameMillis`-free 1 Hz `delay` loop bumps a `mutableLongStateOf` read **only in the draw lambda**, so composition is untouched); on every preview step; on status |
| Info bar progress row | 2 ops (track + fill) in a draw lambda | once per 15 s (M3 mini panel cadence) and on programme change |
| Pills with −MM:SS | one text run; label rebuilt into a reused `CharArray` once per second | 1 Hz while BEHIND or PAUSED is shown |
| Quick bar | `LazyRow` of ≤ 13 pills, labels change only on events (tracks, favourite answer, stats toggle) | on open, per focus step |
| Stats overlay | ≤ 16 `BasicText` lines | ≤ 2 Hz (M2 cadence), only while on |
| Channel list | `LazyColumn` of ~12 visible rows with `requestScrollToItem` per step (API notes §4) | per cursor step; logos async |
| Subtitle view | `SubtitleView` canvas painting | per cue change |

### 4.2 Allocation rules

- No allocation in any draw lambda (scrub, progress, pills): `Paint`, `RectF`, `CharArray` label buffers are created once per composable (`remember`) and reused; time labels are written digit by digit into the buffer (a small `TimeChars.write(buf, ms, withSeconds)` helper, JVM-tested for zero allocation like M3's renderer).
- State objects (`PlayerUiState` and its parts) are allocated per **event** (key, status, stats), never per frame; `PillState`/`ScrubState` carry `atMs` anchors so extrapolation needs no new objects.
- `ArchiveStatus` ≤ 1 per second while playing; `StatsFacts` ≤ 2 per second while stats are on; cue lists as Media3 delivers them.
- The machine never copies a lineup; the channel list reads rows through `GuideRows` (main-only).

### 4.3 Memory (Java heap, low-end device)

| Item | Bound |
|---|---|
| Channel-list rows cache | 8 blocks × 32 rows (M3 constants), ~80 KB |
| `ChannelNowCache` | 256 `ProgrammeInfo` (descriptions not loaded: `range`/`nowFor` fill title/genre only, `description = null`), ~60 KB |
| Info-bar programme list | ≤ 2 × `PROGRAMME_RANGE_LIMIT` entries |
| `ProgrammeFeedImpl` id cache | 1,024 ints per generation |
| Logos | the shell's Coil loader (M3 §3.7: 2 MB / 1 MB low-end memory cache); list logos requested at 56 px |
| Subtitle bitmaps | owned by Media3's cues (DVB regions), released with the cue list |
| `media3-ui` classes | code only; `SubtitleView` is the one class instantiated |

Gate: I-M4-18 (§5.5) asserts Java-heap growth < 2 MB over 5 × the FULL journey with GC between (M3 I-15 method) and the M3 guide budget unchanged.

---

## 5. Tests

### 5.0 Fails-before story per review fix (reviewed 2026-10-15)

Every blocker and should-fix item has a test that fails on the proposal as first written and passes with the fix. Implementers keep the reverted-fix run in their hand-off notes (M3 practice: "37 of the new tests fail with the fixes reverted").

| Fix | Test | Fails before the fix because |
|---|---|---|
| B1 | `ArchiveVisibilityTest` (table: every `ArchivePhase` × visible phase × owned/unowned tuneId); `PlayerViewModelTest.probeNeverShowsTheCard` scripts `Connecting → Error(CATCHUP_NOT_IN_ARCHIVE) → Connecting → Playing` with `ArchiveStatus` lagging the `Error` by one emission and asserts `ui.layer != ERROR` and pill ≠ CONNECTING after first media; `GuideFullDelegationTest.handoffKeepsTunedPlaying` asserts the same for `GuideUiState.tuned` | the raw `Error` reached the machine and opened the ERROR layer on the second emission |
| B2 | `ArchivePlannerTest.framesRow` and `CatchupUrlBuilderImplTest` with corr ∈ {−1.5 h, 0, +2 h}: "liveEdge − correction == device now + skew (the panel's now in the archive frame) for corr ∈ {−1.5 h, 0, +2 h}; a request planned from `liveEdge − 30 s` builds with `requestStart == panel now − 30 s` (aligned) for every sign"; I-M4-13b | corr = +2 h requested 2 h in the past (or `Unsupported` from the frame-mixed builder check) |
| B3 | `ArchivePlaybackImplTest.nativeSeekWithoutStateEmissionSettles` (phase leaves SEEKING within 5 s); `ScrubModelTest.previewClearsOnSettledSeq` (wall lands 45 s before the target); I-M4-22 | SEEKING never ended; the preview never cleared |
| B4 + owner ruling | `PlayerFiveKeyReachabilityTest` from four start states (§3.1.6) | AUDIO was unreachable from the archive start state |
| S1 | `ArchivePlannerTest.continuationSnapsOnlyWithin5s`; I-M4-13 150 s run | a 20 s time-shift went live at the first continuation |
| S2 | `ArchivePlaybackImplTest.continuationKeepsUnrememberedAspect`, `.rerequestWhilePausedStaysPaused`; `PlayerStateMachineTest.pickerSurvivesHandoff` | the new cycle reset aspect, closed the picker, resumed playback |
| S3 | `ArchivePlaybackImplTest` rows: 400 → next variant, FORBIDDEN after the ladder → next variant, lag 404 → step back (no variant), AUTH_REJECTED after first media → FAILED without a retry, `midPlayRetries` reset after 5 min; `ErrorLadderTest` row L13a; I-M4-21 | probing ignored 400/403; mid-play retried auth errors; the retry counter never reset |
| S4 | `ProgrammeFeedImplTest.aroundFindsNowOnFiveMinuteSlots` | `nowIndex == −1` |
| S5 | `KeyRouterImplTest.leftWithHorizontalRepeatRepeatsAndNeverFiresGroupPrev`; `DefaultKeyMapTest` stays two-context | no auto-repeat without the third context |
| S6 + owner ruling | `DefaultKeyMapTest.chPlusNextAndPageUpAgree`; `player_keys.csv` rows for CH±/⏭/⏮ in FULL | ⏭ went to the lower number |
| S7 | `PlayerStateMachineTest.numberEntryTuneShowsPassiveBar`, `.okOnFutureSetsReminder` | no passive bar; OK on a future programme did nothing |
| S8 | `ErrorCardModelTest` (no prepended RETRY; DNS no Retry; Software decoder absent for audio or when already software; archive Retry calls `ArchivePlayback`) | Retry prepended on four codes; loops |
| S9 | `./gradlew :core:test :ui:testDebugUnitTest :app:assembleDebug` green on commit 0 alone; `GuideFullDelegationTest.attachWhileFullCallsEnterFull` | non-exhaustive `when`s; FULL without the player machine |
| S10 | `ComposedFullBackLadderTest` | no proof existed |
| S11 | §5.5 I-M4-13b, I-M4-21…I-M4-23 and the §5.6 panel additions | the cases were not exercisable |

### 5.1 WP-A (JVM, JUnit 5)

- `PlayerKeysTableTest` driven by `core/src/test/resources/player_keys.csv` (layer, state precondition, `upDown` setting, action, repeat/heldMs, expected layer, expected effects, expected toast): every row of §3.1.2 (live and archive columns, each `UpDownInPlayer` value; CH± = ±1 per the owner ruling), §3.1.3 (incl. QUICK_BAR and the quick bar over the error card) and §3.1.5; written from this document by a different author than the implementation (M2 test gap 2: no self-referential golden tables).
- `PlayerFiveKeyReachabilityTest` (§3.1.6, F6 adapted): four start states (live with archive, live without archive, archive, time-shift) × three `UpDownInPlayer` values; within each start state no state of another kind is visited; in archive/time-shift the quick bar is reached by BACK → UP.
- (reviewed 2026-10-15, S10) `ComposedFullBackLadderTest`: guide machine + real player machine over the product of {no overlay, Number(preview), Exit hint, Exit dialog} × {reminder card, none} × every `PlayerLayer` × {live, archive} × `BackInPlayer`. Asserts: BACK reaches `mode == GUIDE` (or the exit dialog) within 6 presses; with the card shown, any key changes neither `PlayerUiState` nor the effects; after `returnToGuide` the player's `ui.layer == NONE` and stats are off; SETTINGS opened from the quick bar returns to FULL on BACK.
- `InfoBarTimingTest` (fake clock): passive after zap until PLAYING + 4 s, passive on a guide-originated tune (S7), active 3/5/8 s with key resets, replacement by the next zap, ERROR during passive.
- `QuickBarModelTest`: item order and hiding per state (§3.4.1), favourite label after the answer only, SETTINGS last, `QUICK_BAR_IDLE_MS`.
- `ScrubModelTest`: step table by `heldMs` (10 s / 30 s / 120 s / 300 s after 10 s), clamps at both ends, live-edge commit → live, programme re-scope with the preview, 600 ms commit re-armed by every step and by `Release`, BACK cancels without an effect, SELECT commits at once, preview cleared by `settledSeq` (B3), two chips only.
- `ErrorCardModelTest`: all 25 codes × default and narrowed actions (§3.7 invariant incl. F5's rules), archive FAILED variants.
- `PillRulesTest`: every row of §3.2.3 incl. `handoff`, extrapolation of `behindMs`.
- `ChannelListModelTest` (hold acceleration, Previous row, page sizes, openedByBack ladder).
- `GuideFullDelegationTest` (guide machine + real player machine + fakes): order of §3.1.1, BACK falls through to `returnToGuide`, number entry over an open channel list, EXIT in FULL draws the Exit overlay (H-L4), `GuideEvent.Archive` FAILED → `suspectArchive`, PROBING does not clear `pendingArchiveHash`, `StartFull`, `OpenSettings` round trip, `horizontalRepeat` per state, `handles` true for media keys in FULL, attach-while-FULL calls `enterFull` (S9).
- `DefaultKeyMapTest`: new bindings; MEDIA_NEXT/PREVIOUS now ZAP_UP/ZAP_DOWN; GUIDE unchanged; two contexts only. `KeyRouterImplTest` (`:ui`, Robolectric) for `horizontalRepeat`.
- The M3 suites (`:core:test` + `stateOnRealNavigator`) stay green; `keys.csv` rows for FULL are moved to `player_keys.csv` (the GUIDE rows, incl. CH± paging towards lower numbers, are unchanged).

### 5.2 WP-B (JVM)

- `ArchivePlannerTest` tables: **frames** (reviewed 2026-10-15, B2: "liveEdge − correction == device now + skew (the panel's now in the archive frame) for corr ∈ {−1.5 h, 0, +2 h}; a request planned from `liveEdge − 30 s` builds with `requestStart == panel now − 30 s` (aligned) for every sign"), skew ±, shift ±; `alignStart` per variant in the archive frame incl. a fractional-minute correction and the edge-margin start (N2); `programmeRequest`; `seek` (no-op, live snap 30 s, native via `nativeSeekRange`, native via `isSeekable+duration`, HLS without range → re-request, clamps); `onProgress` (lead, ended, open without duration, continuation snap 5 s, S1); `resumeFromPause` (< 3 s live, ≥ 3 s archive); `onNotInArchive` (lag step once then LiveNotReady, mid-session, next variant, exhausted); `startOffset`.
- `ArchivePlaybackImplTest` with a scripted fake `PlaybackController` (states with `tuneId`s, `position()` answers, `ended`, a native seek that emits nothing), fake `ArchiveTargets`, the real `CatchupUrlBuilderImpl` and `TimeBase` (so URLs are asserted literally: panel zone `Asia/Tokyo`, correction −1.5 h and +2 h, padding 60/300), `FakeCatchupProbeStore`, `FakeProgrammeFeed`, virtual time: start → first media → boundary re-scope (no request) → continuation re-request (open, aligned, offset) → seek native (settles without a state emission, B3) → seek re-request (settles on first media) → live snap; `ownedTuneId` read in the same turn as `tuneArchive`; the FAILED-in-the-same-turn invariant; probe triggers of S3; lag near the edge; pause-live 2 s (live) vs 20 s (archive), pause point minus `bufferedMs` (N3); ownership lost on a zap; mid-play retry list and its reset; lifecycle stop → PAUSED (N13); S2's carried aspect and pause.
- `CatchupLauncherImplTest` (updated): failure mapping, `Started` passthrough.
- Commit 0 (lead) adds `CatchupUrlBuilderImplTest` rows for the moved "not started" check with corr ∈ {−1.5 h, 0, +2 h} (B2).

### 5.3 WP-C (JVM + Robolectric)

- `ProgrammeFeedImplTest` against `EpgTestDb` (the `:core` testFixtures real store): `around` on 5-minute slots (`nowIndex ≥ 0`, S4) and at a gap, `range` ordering, limit, continuation merge, shift; `nowFor` with gaps and NO_EPG rows; `at`; a generation swap between two calls (new reader, old closed on the feed thread); a patch; `SQLITE_BUSY` retry with a fake reader; a timing assertion that `around` stays one statement.
- `StatsFormatterTest` (every `StatLabel`, `Locale.ROOT`, empty fields skipped, `resolutionLabel` table incl. interlaced, "server 2 of 3").
- `ErrorLadderTest` (`ladder.csv`): row L13a, and that a 403 on an archive first open still walks L2–L4.
- `PlaybackControllerStateTest` additions (real engine under Robolectric where M2 does so): cues emitted and cleared on tune/stop; `Playing(ended = true)` on archive end; `previous` follows the M2 rule; E-L2 pause during Connecting/Stalled keeps the state honest; E-L7 `keepScreenOn` stays true behind an unlimited-retry card; `decoderOverride` in `VideoInfo`; `serverIndex/serverCount` in `StreamStats`.
- `PlaylistDaoProbeWinnerTest` (Robolectric Room): set/get, `catchup_probe_winner` only column touched.

### 5.4 WP-D (Robolectric + Compose UI test; JVM for the VM)

- `RootTreeRuleTest` extended: `SubtitleLayer` and every FULL overlay are siblings of `VideoLayer`, none an ancestor; `SubtitleLayer` is a direct child of the root `Box` (N11); no `graphicsLayer`/alpha/clip above `VideoLayer`; whether `AndroidView` adds a layer is recorded (§8).
- `SubtitleViewFocusTest`: the `SubtitleView` is not focusable, blocks descendants and is hidden from accessibility; the key sink keeps focus after it is composed (N15).
- `ScrubBarRendererTest` against a recording canvas: op count per state (≤ 14), zero allocation per draw (allocation counter as M3 WP-A), label buffer content.
- `StickyVideoSizeTest`: rect unchanged from `stop()` until `renderedFirstFrames` advances; released on first media of a radio channel and on `Error` (N4); aspect held during an owned hand-off (S2).
- `OverlayBoundsTest`: every layer's bounds equal §3.9 (quick bar, picker sheet above it); toasts and the reminder card lift over the bottom bars (N8).
- `ErrorTextTest` extended: detail line for every `ErrorDetail` combination; a label for every `ErrorButton`; every notice has a string; the stopped hint.
- `PlayerMappersTest`: `TrackInfo` → `TrackMenu` (speculative flag, sorted last), `StreamStats` → `StatsFacts`, `VideoInfo` → `VideoFacts`.
- `PlayerViewModelTest` (JVM, fakes): the B1 script of §5.0; event forwarding through `ArchiveVisibility`; ticker deadline wake; `repeatOnLifecycle` gating; notice suppression of `LiveResumed` during an archive resume; `openSettings` dispatches `GuideEvent.OpenSettings`.

### 5.5 Instrumented suite (WP-E; API 30 TV emulator `TheBox_TV_API30`, fake panel)

Launched through `MainActivity` with the debug seeding route (M3 §5.6); state read through `GuideDebugProbe` and `PlayerDebugProbe`. `range_requests_seen == 0` on live TS is asserted in every `@After` (as in M2/M3).

| # | Test | Pass criterion |
|---|---|---|
| I-M4-1 | Passive info bar on zap | CH+ in FULL: the bar shows the target (the **higher** number) within 100 ms of the key, programme title within 1 s, hides 4 s after PLAYING; CH± still zap while it shows |
| I-M4-2 | Active info bar | DOWN → active; ↓ moves to the next programme; ← shows another channel's now; OK tunes it; OK on a future programme sets a reminder (S7); auto-hide after `?infobar=3000` |
| I-M4-3 | Channel list paging (`--synthetic 2000`) | OK opens on the tuned row; 200 × DOWN at 25 Hz: no PENDING row visible 300 ms after the last key; "now" titles present; RIGHT tunes and the panel stays; second OK closes |
| I-M4-4 | Groups column and CH± | LEFT in the list → groups; OK on another group reloads the list, the guide's source unchanged after Back |
| I-M4-5 | Previous row and quick bar | tune A, tune B: the list's Previous row shows A and SELECT tunes A; Up → quick bar → Previous channel tunes B |
| I-M4-6 | Number entry in FULL | "1","2","3": preview shows name + logo; after `?numwait=1000` commits in 1 s ± 150 ms; the passive bar shows the new channel (S7) |
| I-M4-7 | Audio picker | `multi` clip: Up → quick bar → Audio lists two languages; selecting the second changes `tracks.audio.selected`; re-tune of the channel keeps it (memory) |
| I-M4-8 | Subtitles | `multi` HLS with a WebVTT rendition: quick bar → Subtitles → track on → cues non-empty within 5 s and `SubtitleView` bounds inside `videoRect`; Off clears |
| I-M4-9 | Catch-up start and continue | short-programme synthetic channel (1-min programmes, `?padding=0`): OK on a past programme plays; after ~60 s the scrub bar's programme changes without a new request in `timeshift_requests`; the request after the file's end is open-ended and starts on the minute at the wall position |
| I-M4-10 | Native seek vs re-request | `timeshift_ranges` on: scrub −30 s inside the file → no new timeshift request (only ranged reopens); scrub before the request origin → one new timeshift request whose start equals the target minute in the panel zone (`timeshift_expect`). Linger variant (`max_connections = 1`, `linger` on timeshift): assert the refused-reopen behaviour (ladder L2–L4 retries, then either playback or the CONNECTION_LIMIT card), not only the open count (S11) |
| I-M4-11 | Probe and persist | panel rejects `xc-path` with 404: playback starts through `xc-php`; `playlist.catchup_probe_winner = "xc-php"`; the next catch-up requests `xc-php` first; no error card was ever visible (B1) |
| I-M4-12 | All variants fail | panel rejects every form: error card CATCHUP_NOT_IN_ARCHIVE with Watch live; the guide shows the hollow ↺ for that channel; no winner stored |
| I-M4-13 | Pause live and resume (S1: 150 s) | PLAY_PAUSE on an archive channel, wait 20 s: live connection released after 10 s; resume → timeshift request at the pause instant (± 60 s alignment); the run continues 150 s after resume: the pill stays `−00:20` ± 3 s and exactly one continuation request appears in `timeshift_requests`; never live |
| I-M4-13b | Positive correction (B2) | channel with `catchup-correction="+2"`, panel zone ≠ UTC: pause 20 s, resume → `timeshift_expect` = the pause instant on the panel's clock (± 60 s); pill `−00:20` ± 3 s |
| I-M4-14 | Archive lag | panel `archive_lag_sec: 120`: pause 20 s, resume → one step back then live with the ARCHIVE_NOT_READY toast; no winner change, no ↺ |
| I-M4-15 | Jump to live and live snap | in time-shift 40 s behind, UP in the scrub bar → live; scrub to 10 s behind → live |
| I-M4-16 | Error cards | `auth_mode 403`, `status Expired`, `max_connections 0` linger, `html_splash`: each card shows reason, detail line and buttons per §3.7 (no Retry where the code has none); Retry re-tunes; Back goes to the guide |
| I-M4-17 | Stats overlay | quick bar → Stream info: lines appear within 1 s and update, incl. "server 1 of 1"; Back closes; `setStatsEnabled(false)` after leaving FULL |
| I-M4-18 | Memory | 5 × (zap ×10, list open/scroll, quick bar, picker, scrub, stats) with GC: Java heap growth < 2 MB |
| I-M4-19 | Surface rule and subtitles in the tree | exactly one `SurfaceView`; no ancestor `View` with alpha < 1, a layer type or clip; the `SubtitleView` is a sibling after it and never focused |
| I-M4-20 | Five-key journey on device | scripted D-pad/OK/Back only: quick bar by Up, Pause/rewind, rewind, jump to live, start over, previous channel; in catch-up Back then Up reaches Audio; end states equal the JVM table |
| I-M4-21 | Probe on a 400 rejection (S3) | panel rejects `xc-path` with 400: playback starts through the next variant, winner stored |
| I-M4-22 | Native in-buffer seek settles (B3) | archive playing with ≥ 30 s buffered: FF +10 s commits; `status.phase` leaves SEEKING within 5 s; the scrub preview clears |
| I-M4-23 | Catch-up audio with five keys (B4, owner ruling) | `multi` archive: Back, Up → quick bar → Audio → second language, using D-pad/OK/Back only |

Regression: M3 I-1…I-19 and the M2 classes, one class per `run-instrumented.sh` call (M3 §5.11).

### 5.6 Fake panel additions (WP-E)

From API notes §7's gap list and review S11, each behind a config key (defaults keep today's behaviour) and covered in `test_fakepanel.py`:

1. **`timeshift_mode: "duration"`** (default `"clip3"`): the `.ts` response length = requested duration (`{minutes}` × 60 s, `duration` for php, Flussonic `DURATION`), built by looping the clip with the **live timestamp rewriter** (README "Live TS timestamps"), so PCR/PTS are continuous from the requested start and `TsDurationReader` reads the true duration (gaps 1, 2). `Content-Length` exact; ranges as today. Open-ended forms (shift `?utc=`, Flussonic `-now`, `timeshift_abs`) stream paced like live from the requested start without `Content-Length` (gaps 5, 7: route `timeshift_abs-{S}.{ts,m3u8}` and `timeshift_rel-{N}.m3u8`).
2. **Archive window enforcement** `timeshift_window: true`: 404 for a start older than the channel's `archive_days` or later than the panel's now (gap 3), independent of `timeshift_expect`.
3. **`archive_lag_sec: N`**: 404 for a start within N s of now (I-M4-14).
4. **`timeshift_reject_forms: {form: status}`** (`"path"`, `"php"`, `"path_sec"`, `"flussonic_range"`, …) with a configurable status: `404`, `400`, `403`, `500`, `"200-html"`, `"200-empty"` (S11 item 2; I-M4-11/12/21).
5. **`range_rejects` on timeshift** and a **`linger`** that applies to timeshift reopens (gap 4; I-M4-10 linger variant).
6. **`timeshift_range_ignored: true`**: `Content-Length` present but `Range` ignored (200 with the whole body), the `bytesToSkip` path (S11 item 3; gap 5).
7. **HLS EVENT timeshift** for the `-now.m3u8` forms: `#EXT-X-PLAYLIST-TYPE:EVENT` without ENDLIST, growing with wall time (S11 item 4; gap 6), so §3.8.4's re-request-only rule is exercised.
8. **`token_ttl` on timeshift URLs** (302 to the LB port with an expiring token, as for live; S11 item 5) for the mid-play retry.
9. **Correction channels** (S11 item 1): synthetic channel 3 with `catchup-correction="+2"`, channel 4 with `"-1.5"` in the M3U, the panel's `server_info.timezone` ≠ UTC (default stays `Europe/Berlin`), and `timeshift_expect` helpers that compute the expected panel-clock start (I-M4-13b).
10. **`multi` media variant** (`media_override`): H.264 + two audio PIDs (`aac` eng 440 Hz, `mp2` deu 880 Hz, ISO-639 descriptors via `-metadata:s:a:N language=`) for TS; for HLS a master playlist with a WebVTT subtitle rendition (`#EXT-X-MEDIA:TYPE=SUBTITLES`) generated from a committed 30-line `.srt`. DVB bitmap subtitles cannot be produced by ffmpeg from text **(verify; owner WP-E: a licence-free DVB sample, or keep DVB manual on real streams, §8)**.
11. **Synthetic short-programme channel** (`--synthetic` channel 2: 1-min programmes for the last 3 h and next 1 h, archive 1 day) for I-M4-9.
12. **EPG/archive alignment** re-computed per request rather than at server start (gap 8), so a long-running panel does not drift.

`/admin/stats` gains `timeshift_requests: [{form, start, duration, status, at}]` (last 50) so tests assert request sequences without parsing logs.

### 5.7 Benchmarks (WP-E)

`PlayerOverlayBenchmark` (Macrobenchmark, `FrameTimingMetric`, the M3 setup incl. `androidx.benchmark.suppressErrors=EMULATOR`, `-gpu host`): (a) FULL idle 10 s with no overlay — frame count must be ~0 (asserted ≤ 5 on the emulator); (b) scrub hold ← for 5 s; (c) channel list 100 × DOWN at 25 Hz; (d) 20 × CH+ at 1 Hz with the passive bar (zap P50/P90 from `controller.diagnostics.zaps()`, **filtered to live tunes**: archive cycles are logged as TUNE and zap records too, `PlaybackControllerImpl.kt:458`, and continuations would skew P50; reviewed 2026-10-15, N14); (e) quick bar open + 12 × RIGHT. `frameDurationCpuMs` P50/P90/P99 logged; API 30 reports CPU time only (M3 §5.11), so hardware decides the gates (M6).

### 5.8 Integration results (2026-10-15)

Integration of commit 0 and WP-A…WP-E on `main` (base `b8de83d`), step 3 of §1.9. Device: API 30 TV emulator `TheBox_TV_API30` (x86, 4 vCPU, 2 GB), fake panel via `tools/fakepanel/run-instrumented.sh --synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"' --class …`, one class per call (M3 and M2 classes with their own flags, M3 §5.11). Every number below is an **emulator** number and not gate-relevant (plan §6 gates are asserted on hardware in M6). `range_requests_seen == 0` on live TS held in every `@After`.

**Emulator state (E-1…E-5, all host/emulator, none app).** The AVD had been relaunched by Android Studio with software rendering; it was cold-booted as asked with `-gpu host`, clock in sync with the Mac (± 1 s) on every boot. On this host (emulator 37.1.11, macOS 15, AMD Radeon "Baffin", HVF) the emulator was unstable in four ways, each recorded with its evidence:
- **E-1** `-gpu host` crashed the emulator twice inside `InfoBarTest.passiveBarOnZap` (crash dumps in `libgfxstream_backend`).
- **E-2** `-gpu swiftshader_indirect` (M3's suite setting) piled up 400–980 POSIX shared-memory descriptors from gfxstream; past ~1,000 descriptors the slirp network died (guest `NUD_FAILED` on 10.0.2.2/10.0.2.3, "CANNOT TRANSLATE guest DNS ip", `ConnectException` in the suite) and later the whole guest wedged (`adb shell` unresponsive). Three runs lost this way.
- **E-3** With `-feature -Vulkan,-GLDirectMem,-HasSharedSlotsHostMemoryAllocator` the descriptors stayed under 220, but the emulator then aborted with `hvf_map_safe: no free slots` (HVF memory-slot exhaustion), always during the second test of a process in `InfoBarTest` (guide navigation after a full session); also with `-HardwareDecoder,-GLDMA,-GLDMA2`. Alone, `passiveBarOnZap` passes.
- **E-4** After a crash the next emulator start blocks on a modal crash-report consent dialog (`CrashConsentProvider::showDialog`): the dumps must be moved out of `/tmp/android-<user>/emu-crash-*.db/completed` first.
- **E-5** The emulator audio HAL's underrun storms trigger L32 `SoftwareAudioAndReprepare` every 30–60 s on long runs (M2 §5.13), each adding ~1–1.5 s of stall.

The suite finally ran with `-feature -Vulkan,-GLDirectMem,-HasSharedSlotsHostMemoryAllocator,-HardwareDecoder,-GLDMA,-GLDMA2` (software video decoding), first with `-gpu swiftshader_indirect` (M3/M2 regression and most M4 classes; CPU-starved: the main thread skips 30–60 frames during seeding) and at the end with `-gpu host` (benchmarks and the final `PlayerErrorCardTest`, `PlayerMemoryTest`, `TimeShiftTest`, `InfoBarTest` runs), with a runner that cold-boots the emulator again after a crash (scratch tooling, not in the repo).

**JVM suite** (`:core:test :core:stateOnRealNavigator :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :ui:lintDebug :app:lintDebug`), after every source change of this pass: green except one flake. `:core` 2,154 (+ `stateOnRealNavigator` 261), `:data` 158, `:player` 321, `:ui` 187, `:app` 40; 0 skipped. Lint: 0 errors (`:ui` 25 warnings, `:app` 23). Flakes: `ScrubBarRendererTest.zeroAllocationPerDraw` (`:ui`, WP-D) reports exactly 2,088 bytes over 1,000 draws in the full parallel gate (twice) and 0 alone (twice) and inside the smaller `:core :ui :app` run: the same class as `RendererAllocationTest.referenceFrameAllocatesNothing` (JIT/profile state of a shared JVM), listed for the reviewers (L-5). `GuideViewModelTest.hidingTheFocusedChannelKeepsTheIndex` (known flake) passed in every run today. Fake panel: `test_fakepanel.py` 88 tests OK.

| Class (§5.5) | Pass | Fail | Notes |
|---|---|---|---|
| `InfoBarTest` (I-M4-1, I-M4-2) | 2 | 0 | I-M4-2 in every run (auto-hide 2.4–3.1 s). I-M4-1 passes alone (`#passiveBarOnZap`); as the class's second test it is where E-1/E-3 kill the emulator, on both GPU modes (last run: `hvf_map_safe: no free slots` under `-gpu host`). Key → passive bar measured 114, 169, 200, 445, 533 ms over five runs (criterion 100 ms; emulator ceiling 600 ms, T3) |
| `ChannelListTest` (I-M4-3…5) | 3 | 0 | I-M4-3: 200 × DOWN, no PENDING row; T5 |
| `NumberEntryFullTest` (I-M4-6) | 1 | 0 | commit 1,141 ms after the last digit (1 s ± 150); T6 |
| `TracksTest` (I-M4-7, 8, 23) | 3 | 0 | I-M4-23 needed C2 (no tracks in catch-up) |
| `CatchupPlaybackTest` (I-M4-9, 10, 10 linger, 22) | 4 | 0 | needed C2 and C3; T8. I-M4-22 settled 2.4 s after the key. Linger variant ends on the CONNECTION_LIMIT card (`max_connections` rejections 3) |
| `ProbeTest` (I-M4-11, 12, 21) | 3 | 0 | `path:404 → php:200` (and `path:400 → php:200`), winner `xc-php` tried first next time; no ERROR layer sampled during probing (B1, C1) |
| `TimeShiftTest` (I-M4-13, 13b, 14, 15) | 4 | 0 | needed C4; T1, T2, T10. Final run (`-gpu host`): I-M4-13 21.5 s behind after resume (20 s + 0.9 s live buffer + start-up), 19.2 s after 150 s, exactly 2 continuations, never live; I-M4-13b 21.6 s, request on the panel clock, not 2 h back (B2); I-M4-14 404 at the floored pause minute, then 60 s back, then live. On SwiftShader: 26.0 → 31.6 s over 150 s with three L32 re-prepares (E-5) |
| `PlayerErrorCardTest` (I-M4-16) | 1 | 0 | |
| `StatsOverlayTest` (I-M4-17) | 1 | 0 | T7 |
| `PlayerMemoryTest` (I-M4-18) | 1 | 0 | growth −918 KiB (final run) and −1,484 KiB (heap 12,147 → 10,662 KiB) over 5 journeys; criterion < 2 MB |
| `PlayerSurfaceTest` (I-M4-19) | 1 | 0 | |
| `FiveKeyJourneyTest` (I-M4-20) | 1 | 0 | T9 |
| M3 `HarnessTest`, `StartupAndSurfaceTest`, `KeysOnDeviceTest`, `PagingTest`, `GenerationSwapTest`, `GroupsAndBackTest`, `CatchupTest`, `RecompositionTest`, `PrefixTest`, `NumberEntryTest`, `MemoryTest`, `LowEndTest`, `ZapThroughGuideTest`, `LayerScopeTest`, `ModeSurvivalTest` | 23 | 0 | |
| M3 `ReminderTest` | 2 | 0 | 1 skip: `lateReminderShowsStarted` assumption ("the on-now programme started over a minute ago", time of day) |
| M3 `SearchTest` (I-11) | 0 | 1 | twice: OK lands on a live card, no archive start attempted. Only one archive hit for "news" at 03:00; the test counts RIGHT presses from its own search while the screen searches a moment later during a guide rebuild. No M4 code on that path; M3 test fragility (L-9) |
| M2 `SmokeAndZapTest` | 7 | 0 | |
| M2 `ErrorPathTest` | 6 | 0 | |
| M2 `MediaAndLifecycleTest` | 5 | 2 | I-22/I-22b "fps known for ch 4": M3 O-6's emulator symptom; with software decoding (E-3 configuration) the 50 fps estimate never settles. I-21 passed |
| M2 `ReconnectTest` | 7 | 0 | + I-3 `@Ignore`d (M2 O-1) |

**Bugs found on the device and fixed**

| # | Where (owner) | Symptom | Cause | Fix | Test |
|---|---|---|---|---|---|
| C1 | `core/.../catchup/archive/ArchiveVisibility.kt` (contract, lead-authorised; WP-D hand-off) | for one emission the previous cycle's `Error(CATCHUP_NOT_IN_ARCHIVE)` reached both machines while probing | `ownedTuneId` rises in the same turn as `tuneArchive`; the `tuned.tuneId < ownedTuneId` clause passed the earlier own cycle through | clause removed: while a session is active, an archive state of its channel is owned whatever its tuneId (§2.3a updated); WP-D's `ui/.../vm/TunedVisibility.kt` deleted, both VMs call the contract | `ArchiveVisibilityTest` (new: every active phase × visible phase × owned/earlier/later tuneId; B1 row; pass-through table), `PlayerContractsTest` row changed, `PlayerViewModelTest.probeNeverShowsTheCard`; I-M4-11/12/21 |
| C2 | `player/.../engine/TsExtractorsFactory.kt` (M2 dead-track guard) | every catch-up with a `Content-Length` over 1 MiB (every real archive file) went READY with **no tracks** (no decoder, `controller.tracks` empty), then NO_DATA_TIMEOUT after 15 s (I-M4-9, 10, 23) | the guard measured "bytes read since the PMT" as an input-position difference; on a finite TS, `TsExtractor` ends its tracks after the PMT and `TsDurationReader` then seeks to the end of the file, so the jump counted as > 1 MiB read and every track was given up before its first PES. Hidden until now because `clip3` archive files were < 1 MiB | the guard counts the bytes each `read` call consumed (seeks are not reads) | `TsExtractorsFactoryTest.durationReadSeekOnAFiniteInputIsNotCountedAsRead` (fails with the fix reverted); I-M4-9, 10, 22, 23 |
| C3 | `core/.../full/state/PlayerStateMachineImpl.kt` (WP-A) | I-M4-10's native seek never settled (second test of the process) | `ArchivePlayback` is app-wide and its `settledSeq` outlives a `PlayerViewModel`; a new machine's first `archiveSeek` reused seq 1, already settled, so the preview cleared at once and the session never echoed a new seq. In production: any activity recreation with the process alive | `onArchive` adopts the session's high-water mark (`seekSeq = max(seekSeq, status.settledSeq)`) | `ScrubInteractionTest.firstSeekOfANewMachineContinuesAfterTheSessionsSettledSeq` (fails with the fix reverted); I-M4-10 |
| C4 | `player/.../controller/PlaybackControllerImpl.kt` (M2/WP-C) | a 20 s time-shift ended 125 s behind after 150 s (I-M4-13) | a ladder re-prepare (here L32 after E-5) starts a new session of the same archive cycle; `setMediaSource(resetPosition = true)` dropped the playhead and only the cycle's first session seeked to `startOffsetMs`, so the file restarted at 0 | a later session of an archive cycle seeks back to the player's position before replacing the source (`stop()` keeps it) | `ArchiveAndMemoryTest.ladderReprepareOfAnArchiveCycleContinuesAtThePlayhead` (fails with the fix reverted); I-M4-13 |

Test-expectation corrections (the merged app was right; `app/src/androidTest/.../player/`): **T1** I-M4-13 expects 2–3 continuations in 150 s (WP-B hand-off: each xc open request is `ceil((now − S)/60)` min), counted after the resume request by identity; on the emulator the offset may grow by stall time but never move towards live (E-5), the design's ± 3 s stays for hardware. **T2** I-M4-14 `archive_lag_sec: 180` (WP-B: the first request floors the pause instant, up to ~80 s behind, so with 120 the step back plays). **T3** I-M4-1 samples the passive bar from a thread started before the key (the old loop started after both synchronous injections returned); emulator ceiling 600 ms, 100 ms on hardware. **T4** I-M4-2 sets the reminder on a programme ≥ 3 min ahead (one starting within the 60 s lead fires the modal card at once, which took the remaining keys) and times the auto-hide from the key. **T5** I-M4-5 waits 2 s before zapping (M2 `PREVIOUS_CHANNEL_MIN_PLAY_MS` = 1 s). **T6** I-M4-6 awaits the preview for "123" (each digit keeps the previous preview until its own lookup lands, L-7). **T7** I-M4-17 accepts the host alone on the SERVER line with one base URL (`StatsFormatter.server`, §3.4.4/E23: "server n of m" only with a choice). **T8** I-M4-9/10 count logical requests: a finite TS is opened twice without a Range (before and after `TsDurationReader`'s tail read), `PlayerPanel.timeshiftLogical`; I-M4-9 identifies "after the first request" by identity (WP-B: request 2 repeats request 1's minute with a longer D). **T9** I-M4-20 rewinds four steps from a 5 s live pause (one step lands inside `LIVE_SNAP_MS` and rightly snaps to live, L-6), accepts a PAUSED time-shift (S2: a seek while paused stays paused), waits 2 s before the list tune (T5's rule), and `quickSelect` steers by the live state (the quick bar's items change while it is open). **T10** I-M4-13/13b expect −00:20 **plus the live buffer at the pause** (N3 resumes at the pause point minus `bufferedMs`; 0.9–4.6 s measured), with 4 s tolerance on the emulator for the time-shift start-up. `PlayerTestBase.isEmulator()` added.

**Measured (emulator)**

`PlayerOverlayBenchmark` (§5.7; `benchmark` build, `CompilationMode.Partial`, 3 iterations, `-gpu host` with `-feature -Vulkan,-GLDirectMem,-HasSharedSlotsHostMemoryAllocator,-HardwareDecoder,-GLDMA,-GLDMA2`, panel `--synthetic 2000`): `frameDurationCpuMs` over all frames of the three iterations (API 30 reports CPU time only, M3 §5.11).

| Journey | Frames (median) | P50 / P90 / P95 / P99 ms | Note |
|---|---|---|---|
| (a) FULL idle 10 s, no overlay | **0** | — | the gate (~0, ≤ 5 on the emulator) is met, but Macrobenchmark throws "At least one result is necessary, 0 found for frameDurationCpuMs", so the journey reports a failure (L-16) |
| (b) scrub: RW, ← held 5 s | 110 | 15.6 / 37.6 / 42.5 / 57.6 | |
| (c) channel list, 100 × DOWN at 25 Hz | 214 | 18.1 / 31.5 / 37.0 / 57.4 | |
| (d) 20 × CH+ at 1 Hz, passive bar | 241 | 11.7 / 25.5 / 28.8 / 38.7 | zap times live in `controller.diagnostics.zaps()`, not readable from the benchmark process; I-M4-1 above |
| (e) quick bar + 12 × RIGHT | 29 | 19.2 / 29.5 / 33.9 / 35.5 | |

`player-scrub` / `player-info` trace sections: count 0 in every journey (not emitted, L-14). `GuideScrollBenchmark` (§5.9 "Guide scroll unchanged") was not rerun in this pass (the emulator time went to E-1…E-4; open, below).

| What | Result | Gate (plan §6) | Verdict here |
|---|---|---|---|
| Zap (I-M4-1, CH+ in FULL) | key → passive bar 114–533 ms (5 runs); the controller's Connecting 40–550 ms after the key, the rest is `PlayerViewModel.channelOf` (2 queries on a cache miss, L-3) | bar ≤ 100 ms (§5.5); zap P50 ≤ 1.2 s | not measurable on this emulator; hardware (M6) |
| FULL idle frames (benchmark a) | 0 frames in 10 s | ~0 (≤ 5 on the emulator) | **met** (harness reports an error on the empty metric, L-16) |
| Overlay draw (trace sections) | not emitted | ≤ 1 ms P90 | not measurable (L-14) |
| Memory (I-M4-18) | Java heap growth −0.9 / −1.5 MB over 5 journeys (two runs) | < 2 MB | **met** |
| Catch-up correctness (I-M4-10/11/13/13b) | starts on the panel clock, correction subtracted, `timeshift_expect` never warned | asserted | **met** |
| Info-bar auto-hide (I-M4-2) | 2.4–3.1 s for `infobar=3000` | — | met |
| I-M4-22 settle | 2.4 s from the key (600 ms commit included) | ≤ 5 s | met |

**Screenshots** (`m4-screens/`, scratch, not in the repo; 1920 × 1080 device = design px) checked against §3.9 and `01-design-reference.md`. Matching: passive and active info bar (rect (64, 820) 1792 × 220, header, title, time row, Next row, browsing hint), quick bar ((64, 952) 1792 × 88, 64 px pills, focused FG fill + accent ring, label · value), pickers (Aspect with the Remember row, Video decoder, Audio, Subtitles: (1376, 48) 496 × 880, 64 px rows, check mark), channel list ((48, 48) 620 × 984, 72/80 px rows, ten visible, Previous row with the accent label) and groups column (260 px, rows dimmed), stream info ((64, 56) 600 wide, mono rows), scrub bar (track (128, 952) 1664 × 6, labels, focused playhead ring, preview bubble, live-edge marker, two chips), error cards (720 wide, centred; fill measured .94 over the yellow bar), probing line, number entry ((1520, 72) 320 × 160 with logo), group card, screen off, mini player error and stopped hint. Contradictions found (for WP-D, L-1, L-2, L-10…L-13):
- **V1** Subtitles ignore the bottom padding (N5): WebVTT cues (channel 8) sit on the bottom edge, partly off-screen, with no bar (0.08 expected) and under the info bar (padding above the bar expected). `SubtitleLayer` passes `bottomPaddingFraction`, but Media3 maps a WebVTT "auto" line to `LINE_TYPE_NUMBER` −1, which `SubtitleView` anchors to its bottom without the fraction (probable cause; verify).
- **V2** The stream-info overlay stays on under the channel list; both sit at the top left, so the stats text shows through the .86 panel.
- **V3** Info header glyphs (`↺ 7d · 720p · 1.0`) end around x = 1240 instead of right-aligned in the header; the resolution label lacks the frame rate until fps is known ("720p", §3.2.4 "1080p50").
- **V4** The CONNECTION_LIMIT card has no next-step line (FORBIDDEN and NO_DATA_TIMEOUT have one), against the rule "errors carry a reason and a next step".
- **V5** Number entry in FULL showed name and logo but not the now title (§3.10.4) in a capture ~1 s after the digit.
- **V6** While browsing another channel in the active info bar, the header shows a LIVE pill next to the browsed (not tuned) channel.
- **V7** After a media-key pause/resume in FULL, Back to the guide showed the mini player's "Press OK to watch" (stopped) instead of the PAUSED / −MM:SS pill, twice (live on channel 8, time-shift on channel 1); not covered by the suite.
- **V8** Channel 7's CEA-608 captions (no PMT descriptor) are not offered as a text choice, so the quick bar has no Subtitles item there (API notes §1.3 expected a speculative CC track; §3.6.3 "verify on the multi clip", owner WP-C).
Not captured: Exit dialog in FULL (EXIT has no default key; the guide's dialog was captured), NO_DATA_TIMEOUT countdown (needs the unlimited-retry setting, not reachable through the debug link), 4:3→16:9 zap (no 4:3 fixture in the panel), mini player −MM:SS pill (V7).

**Later list for the reviewers** (none blocks a test)
- L-1…L-4 from the hand-offs: `GuideViewModel.startTicker` can spin when a deadline is in the past (WP-D fixed the player ticker only); N17 "focus returns to now" after a synchronous archive-start failure needs a `PlayerEvent`; `PlayerFixtures.error` is attached to NO_DATA_TIMEOUT; `GuideScreen.kt` could pass `state.archive` instead of `LocalPlayerArchive`.
- L-3 `PlayerViewModel.channelOf` on a cache miss runs `rowIdOf` + `channels` (two queries) before the first `Tuned` of a zap target; prefetching the CH± neighbours into `channelCache` when the tuned channel changes would take it off the 100 ms path.
- L-5 `ScrubBarRendererTest.zeroAllocationPerDraw`: 2,088 B in the full gate, 0 alone; find the allocation (or measure per draw with the warm-up in the same JIT state).
- L-6 `LIVE_SNAP_MS` (30 s) against the 10 s scrub step: from a fresh live pause the first two ← commits snap back to live; UX review.
- L-7 Number entry keeps the previous number's preview until the new lookup lands ("12" under "123" for ≥ 150 ms; M3 behaviour).
- L-8 The 15 s switch timeout can fire while a live cycle is paused before its first media; the Error then ends the PAUSED_LIVE session and resume makes no archive request (seen once on the starved emulator).
- L-9 M3 `SearchTest` (I-11) navigates by a RIGHT count computed from its own search; make it find the archive card by state.
- L-10…L-13 the screenshot findings V1–V8 above.
- L-14 `player-scrub` / `player-info` trace sections are not emitted, so §5.9 "Overlay draw ≤ 1 ms P90" is not measurable yet.
- L-15 L32 `SoftwareAudioAndReprepare` repeats on AAC underrun storms (E-5); check on hardware whether an underrun storm should switch decoders at all.
- L-16 `PlayerOverlayBenchmark.fullIdle10s` cannot report its own success: zero frames make Macrobenchmark throw; catch it and report `frameCount = 0`, or add a metric that tolerates an empty frame list (WP-E).

**Open**: `GuideScrollBenchmark` rerun against M3 §5.12 (next emulator session). **For M6 (hardware)**: zap P50/P90 and the 100 ms passive bar; FULL idle frames and overlay draw time with real GPU timings; I-M4-13's ± 3 s over 150 s without emulator stalls; I-M4-1 in sequence (E-1/E-3); M2 I-22 fps; DVB bitmap subtitles (§8 Q5), including the vertical squash of DVB bitmaps while a bar is open (accepted with U-B2's view-padding lift, code review 2026-10-16); the baseline profile (M3 O-5).

**Code review fixes (2026-10-16).** Two reviewers (engine `P-`, shell and tests `U-`) and a fix pass on `main`: `review/m4-code-review.md` (findings, the lead's 13 decisions, fails-before check, device pass). Fixed: P-B1 (at-live chips EPG-anchored), U-B1 (guide ticker spin), U-B2 = V1 (caption lift as view padding; §3.6.3), U-B3 = P-S4 = V7 (the 10 s live-pause release reads PAUSED), P-S1 = L-6 (direction-aware live snap; §3.5.2, §3.8.4), P-S2 = V4 (a next step on every card), P-S3, U-S1 = V3, U-S2 = V6, U-S3 = V2, U-S5 (incl. L-4 and P-L1's guide half), U-S6 = L-14/L-16 (trace sections; FULL idle frames asserted by `FullIdleFramesTest`, benchmark journey (a) removed), U-S7 = L-2/N17 (`PlayerEvent.ArchiveStartFailed`, contract amendment; §2.1, §3.2.2), U-S8 (test hardening), U-S9 (fake panel `timeshift_future`, `timeshift_chunked`), V8 (speculative CEA-608 track; §3.6.3, API notes §1.3). `PlayerEffects.playProgramme` gained a defaulted `timeShift` parameter (P-B1). V5 was rejected (latency, not a defect); L-5 rejected as a product allocation. The Later list and the remaining gaps are in the review doc.

### 5.9 Gates M4 can measure (plan §6)

| Gate | Target | How M4 measures it | Asserted? |
|---|---|---|---|
| Zap time | P50 ≤ 1.2 s, P90 ≤ 2.0 s, no black frame | I-M4-1 and benchmark (d) with the passive bar on; no-black-frame by screenshot probe of the centre pixel between key and first frame (I-M4-1 extension) | logged on the emulator; hardware in M6 (last-frame retention is SoC-dependent, API notes §2.5) |
| Soak (partial) | no heap growth | I-M4-18 (5 journeys); the 12 h soak is M6 | asserted (growth < 2 MB) |
| Guide scroll | unchanged | `GuideScrollBenchmark` (M3) rerun after integration | logged, compared with M3 §5.12 |
| FULL idle frames | ~0 | benchmark (a) | asserted ≤ 5 on the emulator |
| Overlay draw | ≤ 1 ms P90 main thread | trace sections `player-scrub`, `player-info` in benchmark (b)/(d) | logged |
| Catch-up correctness | panel-zone start, correction subtracted | I-M4-10/11/13 with `timeshift_expect` | asserted |

---

## 6. Build (commit 0, lead)

### 6.1 Catalog additions (`gradle/libs.versions.toml`)

```toml
[libraries]
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }   # 1.11.1: SubtitleView only (§3.6.3)
```

`media3-ui` 1.11.1 is not in the Gradle cache (API notes §0, §10): commit 0 resolves it online once and runs `./gradlew :ui:dependencies` to confirm it pulls `media3-common` 1.11.1 (same as the rest) and nothing that raises another pinned version. If it does not resolve, the fallback is the Compose cue renderer of §7 item 1 (b), decided before WP-D starts.

### 6.2 Modules

- `:core`: no new dependencies. New packages `app.thebox.core.full{,.state,.feed}` and `app.thebox.core.catchup.archive`; fakes in `testFixtures` (`app.thebox.core.full.fakes`).
- `:ui`: `implementation(libs.media3.ui)`. R8 keeps only `SubtitleView` and what it references; `isShrinkResources` already strips the player-control drawables in release **(verify the APK size delta; owner WP-D, report in the hand-off)**.
- `:player`, `:data`, `:app`: none.
- No `library.db` migration: `playlist.catchup_probe_winner` exists (`PlaylistEntity.kt:40`); per-channel aspect/audio/decoder memory exists (`channel_prefs`, M2 §2.10); player settings are in memory until M5.

### 6.3 Conventions

M1 §6.6, M2 §6.7 and M3 §6.3 apply. Plus: every player string in `strings_player.xml`/`strings_errors.xml`; no `rememberInfiniteTransition` in FULL; no `ExoPlayer` or `Player` type below `:player` except `Cue` (subtitles) and `surfacePlayer` (already in `PlaybackApi`); archive URLs, `ArchiveRequest` and `CatchupTarget` never logged (redacted `toString`, §2.9).

---
## 7. Decisions (signed off 2026-10-15)

All items are decided in the **Lead sign-off** at the top and by the owner ruling (`docs/decisions/2026-10-15-player-keys-and-quick-bar.md`). Summary:

| # | Item | Outcome |
|---|---|---|
| 1 | Subtitle rendering | **(a)** `media3-ui` `SubtitleView` with N15's focus settings; **(b)** own Compose cue drawing is the named fallback if `media3-ui` 1.11.1 does not resolve in commit 0 |
| 2 | Sibling `PlayerStateMachine` composed into the guide machine | accepted, with S5 (`horizontalRepeat`, no third `KeyContext`) and S10 (composed proof) |
| 3 | Zap direction | **decided by the owner**: CH+ / MEDIA_NEXT / PAGE_UP = +1 (higher number) in FULL; D-pad Up opens the quick bar and Down the info bar by default; under "Change channel" Up = `DPAD_UP_ZAP_DELTA` (+1), Invert flips the D-pad only. GUIDE unchanged (Up and CH+ towards lower numbers) |
| 4 | ←/→ scrub on bare archive video | accepted |
| 5 | Xtream open-ended duration extension | not in M4; D10 is the trigger, I-M4-13's 150 s run the measure |
| 6 | Gapless hand-off | deferred to v1.1 |
| 7 | Live pause resumes from the archive after ≥ 3 s | accepted with B2 (live edge with the correction) and N3 (minus the live buffer) |
| 8 | Error-card buttons only for M4 actions | accepted with F5; DNS_FAILURE has no Retry |
| 9 | Channel list does not change the guide's source | accepted |
| 10 | Info bar OK | accepted with S7: OK on the tuned channel's now → quick bar; on a future programme → reminder |
| 11 | Subtitles in FULL only | accepted |
| 12 | Number entry stays the guide's | accepted |
| 13 | Probe winner persisted after first media with a non-first variant | accepted with S3's trigger set (§3.8.2, §3.8.8) |
| 14 | Speculative CEA captions listed "if broadcast" | accepted, sorted last |
| 15 | FULL consumes Play/Pause and FF/RW | accepted |
| 16 | Screen off | `QuickItem.SCREEN_OFF` in M4 |
| 16a | Pause / rewind in the quick bar (proposer addition to the owner's item list) | included as `QuickItem.TIME_SHIFT`: without it a remote with no Play/Pause or RW key has no five-key route to pause on live; flagged to the owner |
| 17 | New default bindings | accepted with S6 and the owner ruling |
| 18 | Player settings in memory | accepted |
| 19 | Archive visibility rule (B1) | accepted: `ArchiveVisibility` |
| 20 | Live edge and correction (B2) | accepted: F1 plus the builder check, commit 0 |
| 21 | Scrub step table | 10 s, 30 s, 120 s, then 300 s after 10 s held |
| 22 | Continuation snap (S1) | 5 s for continuation, 30 s for user seeks |
| 23 | "Sources" | a read-only "Server: n of m" in Stream info in M4; the picker is M5 with the backup editor; no "v1.1" label |
| 24 | Meaning of a positive `catchup-correction` | recorded for the M6 real-provider check |
| 25 | Five-key route to the quick bar in archive | superseded by the owner's quick bar: BACK → UP (no Options chip) |
| 26 | Re-request seek while paused | stays paused |

### 7.1 Review "Later" items swept

| Tag | Item | M4 |
|---|---|---|
| M2 N-L9 | catch-up types print secrets in `toString()` | **folded** into commit 0 (§2.9) |
| M2 E-L2 | `pause()` during Connecting/Stalled reports Playing(paused) | **folded** (WP-C, §5.3): pause before first media keeps Connecting and is applied at READY |
| M2 E-L7 | `keepScreenOn` false behind the unlimited-retry card | **folded** (WP-C) |
| M2 E-L8 | Playing ↔ Buffering(RECONNECTING) flicker | **folded** UI-side: pill debounce (§3.2.3) |
| M2 E-L16 | HDR poster on a video-only stream times out | deferred: MINI-only, not a FULL concern (M2 owner) |
| M2 N-L1…N-L8, N-L10…N-L18, E-L1, E-L5, E-L6, E-L9…E-L15, E-L17…E-L19 | ladder, transport, engine internals | deferred: no M4 code path touches them; they stay on the M2 list for M6 |
| M2 test gap 7 | pause during a ladder delay | **folded** into the E-L2 test |
| M3 H-L2 | ticker on the wall clock | **folded** for the player ticker (monotonic `delay`); the guide ticker stays (M3 owner) |
| M3 H-L3 | Exit does not stop playback until ON_STOP | deferred: EXIT is the guide's exit step; M5 (exit flow) |
| M3 H-L4 | EXIT in FULL opens an undrawn overlay | **folded** (WP-A: Exit drawn by `FullScreenHost`) |
| M3 H-L5 | ambient work in FULL | **folded** (WP-D) |
| M3 H-L7 | hard-coded English in `NumberEntryOverlay` | **folded** (WP-D) |
| M3 H-L8 | next steps pointing at the Settings placeholder | **folded** for FULL (text steps, §3.7); the mini card follows the same rule |
| M3 H-L9 | mini error card ignores narrowed actions | **folded** (`TunedState.errorDetail`) |
| M3 H-L11 | work while stopped | **folded** for the player VM (`repeatOnLifecycle`) |
| M3 H-L12 | `channelOf` caches failed lookups | **folded** for the player VM's lookups (hits only, N10); the guide VM's own cache stays (M3 owner) |
| M2 E-L18 | `VideoLayer` places relative to its parent | **folded** for `SubtitleLayer`: a direct child of the root `Box` (N11) |
| M3 H-L13, H-L14 (BUTTON_SELECT, TV) | guide-only / mapping | deferred to M5 (button mapping) |
| M3 open item "films over 6 h" | CONTINUATION in search | partly: `ProgrammeFeed.range` merges continuations; search unchanged (M5) |
| M3 G-L3 | detail cache ignores shift | not affected: `ProgrammeFeed` keys by generation and channel incl. shift |
| M3 G-L12 | search ignores `suspectArchive` | deferred (M5 search pass) |
| Other G-L/H-L items | guide internals | deferred, unrelated to FULL |

### 7.2 Review nice-to-have items (reviewed 2026-10-15)

Taken (marked Nn in place): N1 (§0.11, §3.3.3), N2 (§2.3, §3.8.3), N3 (§3.8.7), N4 (§3.6.2), N5 (§3.6.3), N6 (§2.9), N7 (§3.4.1, as E16), N8 (§3.9), N9 (§3.5.2, as E21), N10 (§3.6.1, §3.10.1), N11 (§3.6.3), N12 (§3.7), N13 (§3.8.2), N14 (§5.7), N15 (§3.6.3), N17 (§3.2.2).

Not taken: **N16** (DST diagnostic when the panel zone is only a fixed offset): useful only with a new `CatchupDiagnostic` member and M5's channel-info screen to show it; recorded as §8 Q15 for M5.

---

## 8. Open questions and unverified claims, with owners

1. **Last frame after `stop()`** on Fire OS 6/7/8 and Google TV SoCs (API notes §2.5, §9; architecture review "some SoCs blank"). The zap card and sticky rect assume it. Owner: **M6 hardware (D3)**; M4 logs the no-black-frame probe on the emulator only.
2. **Real-panel archive behaviour (D10):** whether Xtream `/timeshift/…/id.ts` is finite with `Content-Length` and 206; the maximum `duration`; behaviour for a `duration` past now (§7 item 5); `.m3u8` timeshift ENDLIST; Flussonic `-now`/`timeshift_abs` HLS window origin; recent-archive lag size (`ARCHIVE_LAG_GUARD_MS`); 458/509 on ranged reopens; **how panels reject unsupported timeshift forms** (400, 403, 500, HTML, empty: review "could not verify"). Owner: **lead with a real provider, M6**; M4 defaults: seek by `isSeekable`, re-request otherwise, lag step once, S3's trigger set.
3. **`media3-ui` 1.11.1** resolves alongside the pinned toolchain; APK size delta after R8. Owner: **commit 0 (resolve), WP-D (size)**.
4. **Speculative CEA track rule** (`label == null && language == null` for CEA-608/708) against the `multi` clip and one real stream. Owner: **WP-C**.
5. **DVB bitmap subtitles** have no generated fixture (ffmpeg cannot encode text to `dvb_subtitle`). Owner: **WP-E** (a licence-free sample, else manual on a real stream before M4 sign-off). (code review 2026-10-16, U-B2) The manual check also covers the accepted vertical squash of DVB bitmaps while a bar is open (the caption box shrinks by the view-padding lift).
6. **`onCues` delivery thread** under Robolectric's real player (expected: application looper). Owner: **WP-C**.
7. **Media-session pause** (Assistant, phone remote) calls `controller.pause()` directly (`SessionPlayer`), bypassing `ArchivePlayback.pauseLive`. Accepted for v1 (E15); owner **M5/M6** (route `SessionPlayer.pause` through an injectable hook).
8. **`max_connections = 1` with a lingering panel and the finite-TS prepare reopens** (API notes §7 gap 4). Owner: **WP-E** (I-M4-10 linger variant asserts the behaviour); if refused, WP-C considers keeping the tail read inside one call (not in M4 scope without a review).
9. **In-buffer native seeks stay in `STATE_READY` with no BUFFERING** (review B3, from `ProgressiveMediaPeriod.seekInsideBufferUs`, not re-read in 1.11.1). The settle rule is safe either way. Owner: **WP-B**, I-M4-22.
10. **Translucent overlays over the `SurfaceView` on Fire OS 6/7** (M3 §8 Q6, D1), now including the quick bar. Owner: **M6**; fallback opaque fills (`PlayerUi` constants).
11. **Key repeat timing and `FLAG_LONG_PRESS`** on real remotes (API notes §4, §9): the scrub step table assumes ~20 Hz repeats. Owner: **M6**; the table is constants.
12. **Archive back buffer is 0** (`AdaptiveLoadControl.kt:52,:88`; API notes §2.1): every backward native seek beyond the retained keyframe reopens with Range. Owner **WP-B** confirms the open count in I-M4-10's log.
13. **Remotes without CH±** (Chromecast with Google TV, Onn) no longer zap with the D-pad in FULL by default (owner ruling 2026-10-15): they zap through the channel list (OK), the guide, or the "Change channel" setting. Known trade-off; owner **lead's parity session** (plan §7) checks how often such users miss D-pad zapping.
14. **Meaning of a positive `catchup-correction`** (E24): the B2 fix follows research §4.4 and Kodi's code (EPG → archive mapping). Owner: **lead, M6 real-provider check**. Related: **panel DST behaviour** (whether panels format timeshift tokens DST-aware, and the repeated hour). Owner: **lead with a real provider**.
15. **DST diagnostic** for a fixed-offset panel zone (review N16, not taken): owner **M5** (channel info).
16. **`SubtitleView` focus inside `AndroidView`** (whether Compose's holder makes it focusable) and **whether `AndroidView` adds a layer** (a sibling's, allowed). Owner: **WP-D** (`SubtitleViewFocusTest`, `RootTreeRuleTest`, I-M4-19).
17. **HTTP 400 on an archive request today** matches no specific ladder row before L13a is added; WP-C checks what `ErrorLadderImpl` does with it now and that L13a does not change live behaviour. Owner: **WP-C**.

---
## Appendix A. Facts relied on

### A.1 Code on `main` (`2182858`)

| Fact | Where |
|---|---|
| `PlaybackController` members: `state`, `pendingZap`, `video`, `tracks`, `stats`, `notices`, `surfacePlayer`, `tune`, `zap`, `setZapList`, `previousChannel`, `tuneArchive`, `seekTo` (archive only), `position()`, `pause`/`resume`, `stop`, `retry`, `setViewport`, `setStatsEnabled`, track/aspect/decoder setters | `player/.../api/PlaybackApi.kt:11-57` |
| `ArchiveTune(identity, request, startOffsetMs)`; `PositionSnapshot(positionMs, bufferedMs, durationMs, isSeekable, archiveStartUtcMs)` | `PlaybackApi.kt:80, :96` |
| `PlaybackState` variants, `Playing(paused, warning)`; `VideoInfo` incl. `renderedFirstFrames`; `TrackInfo`, `AudioTrackOption`, `TextTrackOption`, `StreamStats`, `PlaybackNotice` | `PlaybackApi.kt:114-212` |
| `ArchiveTargets` / `CatchupTargetResult`; impl asks the provider first | `PlaybackApi.kt:84-94`; `player/.../resolve/ArchiveTargetsImpl.kt:20-29` |
| Resolver passes `winner = null` (contract gap) | `player/.../resolve/StreamUrlResolverImpl.kt:82-83` |
| `PlaybackErrorCode` (25) with `defaultSteps`; `NextStep`; `ErrorDetail`; `PlaybackError(code, detail, actions, retry)` | `core/.../playback/PlaybackContracts.kt:116-178` |
| `AspectMode`, `VideoDecoderMode`, `Viewport`, `HdrKind` | `PlaybackContracts.kt:267-274` |
| `CatchupTarget.winner`, `ArchiveWindow`, `ArchiveRequest(requestStart, requestEnd, nativeSeekRange in epoch s)`, `CatchupVariants`, `CatchupUrlBuilder.build/variants` | `core/.../catchup/CatchupContracts.kt:67-162` |
| Builder: correction subtracted, padding, edge margin, open xc `D = min(now − S, 180 min)`, `nativeSeekRange` null for xc `.ts`, minute tokens, winner first | `core/.../catchup/CatchupUrlBuilderImpl.kt:23-41, 52-69, 115-132, 164-165` |
| `TimeBase` (`serverZone`, `clockSkew`, `correctionHours`, `paddingBefore/After`, `effectiveTokenZone`, `archiveStart/Stop`) | `core/.../model/TimeBase.kt:28-66` |
| `Catchup(mode, days, source, correctionHours)`, `isAvailable` | `core/.../model/Channel.kt:27-42` |
| Controller: `tuneArchive` → archive cycle; `seekTo` archive only; `position()`; live pause releases after 10 s, resume re-tunes; archive pause is real; `STATE_ENDED` on archive → `Playing(paused = true)`; `stop()` keeps `archiveResumeAtMs`; `VideoInfo` reset on every tune; `previousIdentity` rule; `enrich` filters PREVIOUS_CHANNEL/TRY_BACKUP | `player/.../controller/PlaybackControllerImpl.kt:338-347, 393-397, 432-458, 570-582, 881-920, 1389-1410, 1412-1480, 1541-1569, 1587-1627, 1629-1660, 1662-1681, 1861` |
| `TrackController` codec names, text kinds, selection API | `player/.../engine/TrackController.kt:120-233` |
| `StatsCollector`: 500 ms publish while enabled, fields | `player/.../engine/StatsCollector.kt:37-193` |
| `BoxAction`, `KeyContext`, `KeyGestureDetector`, `KeyMap`, `Keys` | `core/.../input/InputContracts.kt:8-93` |
| Default bindings; PLAYER long ←/→ = GROUP_PREV/NEXT | `core/.../input/DefaultKeyMap.kt:18-39` |
| Gesture detector: keys with a long binding fire on UP, no repeats | `core/.../input/KeyGestureDetectorImpl.kt:20-46` |
| Router picks PLAYER in FULL, keeps the context per held key, asks `handles` | `ui/.../input/KeyRouterImpl.kt:67, 104-127` |
| `GuideStateMachine` (`handles`, `nextDeadlineMs`, `lastSourceHint`), `GuideEvent`, `GuideEffects`, `Overlay.Number`, `NumberEntry` | `core/.../guide/GuideStateContracts.kt:35-208` |
| Guide machine FULL handling, `goFull`, `returnToGuide`, `handles`/`FULL_PASSTHROUGH`, number entry, `onTuned` suspect marking and pending archive FULL, `pendingFull` | `core/.../guide/state/GuideStateMachineImpl.kt:188-192, 235-257, 297-310, 486-535, 882-958, 1122-1173, 1590-1591` |
| `NumberEntryImpl` commit/preview constants | `core/.../guide/state/NumberEntryImpl.kt:61-83` |
| `GuideChannel`, `TunedState`, `MiniPhase`, `ProgrammeInfo` | `core/.../guide/GuideModel.kt:103-226` |
| `GuideLibrary` (`sources`, `lineup`, `channels`, `rowIdOf`, `byNumber`), `GuideRowsCache(Factory)`, `GuideWindowPager.at/info`, `GuideSettings` | `core/.../guide/GuideFeedContracts.kt:36-135` |
| Pager's `onEpg`/`idOf`/`toInfo` pattern | `core/.../guide/feed/GuideWindowPagerImpl.kt:264-300, 459` |
| `EpgStore.openReader`, `EpgReader.window/detail/at` | `core/.../epg/EpgContracts.kt:134, 240-251` |
| `FullScreenHost` (group card, compact error card, number entry) | `ui/.../full/FullScreenHost.kt:33-53` |
| Root order, scrim, viewport by displayed mode, rects from `video` | `ui/.../root/AppRoot.kt:98-229` |
| `ShellHost`, `ShellEvent` | `ui/.../root/ShellHost.kt:16-57` |
| Toast text and view | `ui/.../root/Toasts.kt:29-69` |
| Mini pills (LIVE/CATCH-UP/Connecting/Reconnecting) | `ui/.../guide/screen/MiniPlayerOverlay.kt:73-126` |
| `ErrorText` headline/step maps; error strings | `ui/.../playback/ErrorText.kt:14-84`; `ui/src/main/res/values/strings_errors.xml` |
| `TunedStateMapper` | `ui/.../guide/vm/TunedStateMapper.kt:16-50` |
| `LineupZapList` | `ui/.../guide/vm/LineupZapList.kt:15-49` |
| Guide VM ticker, startup tune, `Tuned` forwarding | `ui/.../guide/vm/GuideViewModel.kt` (`startTicker`, `startup`, `forwardFeeds`) |
| `CatchupLauncherImpl` (one request, raw times, open when airing) and its binding | `app/.../playback/CatchupLauncherImpl.kt:30-73`; `app/.../reminder/ReminderModule.kt:76-86` |
| `PlaylistRepository.timeBase`; no probe-winner method; column-targeted DAO updates pattern | `data/.../repo/PlaylistRepository.kt:146-172`; `data/.../db/dao/PlaylistDao.kt:36-69`; `data/.../db/entity/PlaylistEntity.kt:40` |
| `VideoRectCalculatorImpl.place` handles ratio modes; unknown size → hole | `core/.../playback/surface/VideoRectCalculator.kt:15-47` |
| No player settings rows in code (info bar, number wait, up/down, back, on launch) | grep of `core data ui app player` (only a test match) |
| Owner ruling on player keys and the quick bar | `docs/decisions/2026-10-15-player-keys-and-quick-bar.md` |
| Guide CH±/PAGE page towards lower numbers; Groups panel CH± steps entries; FULL UP/ZAP_UP = `zap(−1)` today | `GuideStateMachineImpl.kt:343-344, 455-456, 491-492, 700-702` |
| Ladder L13: archive 404/410 → CATCHUP_NOT_IN_ARCHIVE | `core/.../playback/errors/ErrorLadderImpl.kt:370-371` (review D5) |
| `GuideViewModel.startup()`; `Tuned` forwarding | `ui/.../guide/vm/GuideViewModel.kt:387-400`; `:319-328` (review S9) |
| `EpgReader` queries share the generation's guide connection and lock | `core/.../epg/store/GenerationHandle.kt:66`; `EpgReaderImpl.kt:33,54,104` (review N1) |
| Fake panel timeshift serving (`clip × 3`, ranges, validation) and media generation | `tools/fakepanel/fakepanel.py:231-283, 642-678, 828-1015, 1116-1123` |

### A.2 Library and platform facts (API notes)

| Fact | API notes |
|---|---|
| No cue rendering in `media3-ui-compose` 1.11.1; no cue path in the repo; `onCues(CueGroup)`; `SubtitleView` API; DVB cues are bitmaps with fractions | §1.2 |
| CEA-608/708 decoded in the renderer; a 608 track exposed without descriptors; DVB needs no flag | §1.3 |
| Finite TS seekable via `TsDurationReader` + binary search when length and PCRs are known; ~3 opens to prepare; live-shifted TS unseekable | §2.1 |
| HLS VOD finite and seekable; EVENT dynamic | §2.4 |
| No callback confirms the last frame stays after `stop()` | §2.5 |
| `media3-ui` 1.11.1 not in the cache | §0, §10 |
| `AnimatedVisibility` fade specs; `rememberInfiniteTransition` runs every frame; `requestScrollToItem` | §4 |
| Key codes 86, 272–275 | §5 |
| Variant table, probe order, open xc cap | §6 |
| Fake panel gaps | §7 |
| Unbound actions in `DefaultKeyMap` | §5, §8 |

---

## Appendix B. Named constants

| Constant | Value | Owner | Section |
|---|---|---|---|
| `INFO_AFTER_ZAP_MS` | 4,000 | WP-A | §3.2.1 |
| `QUICK_BAR_IDLE_MS` (owner ruling) | 8,000 | WP-A | §3.4.1 |
| `DPAD_UP_ZAP_DELTA` (under "Change channel") | +1 | lead (contract) | §2.2 |
| `PlayerSettings.upDown` default | QUICK_BAR_AND_INFO | WP-C | §2.2 |
| `PlayerSettings.infoBarMs` (default / choices) | 5,000 / 3,000, 5,000, 8,000 | WP-C | §2.2 |
| `PlayerSettings.numberEntryWaitMs` | 1,500 / 1,000, 1,500, 2,500 | WP-C | §2.2 |
| `SCRUB_AUTO_SHOW_MS` | 4,000 | WP-A | §3.2.1 |
| `SCRUB_COMMIT_MS` | 600 | WP-A | §3.5.2 |
| Scrub steps by `heldMs` (E21) | < 1 s: 10 s; < 3 s: 30 s; < 10 s: 120 s; else 300 s | WP-A | §3.5.2 |
| `FF_RW_STEP_MS` | 30,000 | WP-A | §3.5.2 |
| `RW_ON_LIVE_BACK_MS` | 30,000 | WP-A | §3.1.2 |
| `INFO_PAST_MIN` / `INFO_FUTURE_MIN` / `INFO_EDGE` | 360 / 720 / 2 | WP-A | §3.2.2 |
| `PILL_DEBOUNCE_MS` | 400 | WP-D | §3.2.3 |
| `LIST_VISIBLE` / `LIST_PAGE_ROWS` | 10 / 10 | WP-A | §3.3.2 |
| `LIST_PREFETCH_BEFORE` / `LIST_PREFETCH_AFTER` | 6 / 8 | WP-A | §3.3.2 |
| `LIST_ACCEL_AFTER` / `LIST_ACCEL_STEP` / page from repeat | 8 / 3 / 20 | WP-A | §3.1.4 |
| `LIST_NOW_DEBOUNCE_MS` | 120 | WP-D | §3.1.4 |
| `NOW_CACHE` | 256 | WP-D | §3.3.2 |
| `PROGRAMME_RANGE_LIMIT` | 24 | WP-C | §2.4 |
| `around` before / after (S4) | 12 / 12 programmes | WP-C | §2.4 |
| `AROUND_AHEAD_MIN` | 1,440 | WP-C | §3.3.3 |
| Feed id cache | 1,024 per generation | WP-C | §3.3.3 |
| `BUSY_RETRY_MS` / retries (M3) | 500 / 3 | WP-C | §3.3.3 |
| `ARCHIVE_POLL_MS` | 1,000 | WP-B | §3.8.2 |
| `ARCHIVE_MIDPLAY_RETRIES` | 1 | WP-B | §3.8.2 |
| `SEEK_NOOP_MS` | 1,000 | WP-B | §3.8.4 |
| `END_GUARD_MS` | 5,000 | WP-B | §3.8.4 |
| `LIVE_SNAP_MS` (user seeks only, S1) | 30,000 | WP-B | §3.8.4 |
| `CONTINUE_SNAP_MS` (continuation, S1) | 5,000 | WP-B | §3.8.5 |
| `SEEK_SETTLE_MIN_MS` / `SEEK_SETTLE_MAX_MS` (B3) | 250 / 5,000 | WP-B | §3.8.4 |
| Seek settle progress / poll while SEEKING | 200 ms advanced / 250 ms | WP-B | §3.8.4 |
| `MIDPLAY_RESET_MS` (S3) | 300,000 | WP-B | §3.8.2 |
| `CONTINUE_LEAD_MS` | 3,000 | WP-B | §3.8.5 |
| `XC_OPEN_EXTRA_SEC` (not built in M4; D10 trigger) | 0 | lead | §7 item 5 |
| `PAUSE_MIN_FOR_ARCHIVE_MS` | 3,000 | WP-B | §3.8.7 |
| `ARCHIVE_LAG_GUARD_MS` / `ARCHIVE_LAG_STEP_MS` | 120,000 / 60,000 | WP-B | §3.8.7 |
| `ARCHIVE_PAUSE_REBUILD_MS` | 600,000 | WP-B | §3.8.7 |
| `EDGE_MARGIN_MS` (= builder `EDGE_MARGIN_SEC`) | 60,000 | WP-B | §3.8.1 |
| `LIVE_PAUSE_RELEASE_MS` (M2) | 10,000 | M2 | §3.8.7 |
| `STATS_INTERVAL_MS` (M2) | 500 | M2 | §3.4.4 |
| Subtitle bottom padding (default / over a bar, N5) | 0.08 / `((videoRect.bottom − barTopPx + 16·scale) / videoRect.height).coerceIn(0.08, 0.5)` | WP-D | §3.6.3 |
| Overlay fade | 150 ms tween (snap in low-end) | WP-D | §3.9 |
| Geometry (info/scrub bar, quick bar (64, 952) 1792 × 88, channel panel, picker sheet, stats, card) | §3.9 table | WP-D | §3.9 |
| Toast / reminder-card lift (N8) | bottom 276 over the info/scrub bar, 156 over the quick bar | WP-D | §3.9 |
| Overlay draw budget | ≤ 1 ms P90; idle FULL ≤ 5 frames / 10 s (emulator) | WP-E | §4.1, §5.9 |
| Heap growth over 5 journeys | < 2 MB | WP-E | §4.3 |
