# Correctness review: player, ui, app (2026-11-09)

Scope: correctness bugs (not style, security, or performance) in `player/src/main`, `ui/src/main` and `app/src/main`. Checked against CLAUDE.md and docs/decisions/.
Method: I read `player` myself. Two forked reviewers read `ui` and `app` and handed back findings. I re-traced the app findings that touch the controller (F-A1, F-A3) myself.
No project file was changed. One temporary JVM test, `player/src/test/.../controller/TmpReviewCardTest.kt`, was added to confirm P1 and then deleted. `git status` matches the start of the session.

Severity: High = wrong behaviour a user will hit; Medium = real, but needs an unusual condition or setting; Low = minor.

---

## Player

### P1 (Medium): the unlimited-retry error card never goes away when the retry succeeds; the stream plays under the card and is not stopped on Home
- **Where:** `player/.../controller/PlaybackControllerImpl.kt:1407-1410` (`onFirstMedia`) and `:1237` (`onPlayerState`, READY).
- **Scenario:**
  1. Settings → Reconnect attempts = Unlimited (`reconnectAttempts = -1`, L19).
  2. A channel fails until the switch timeout. `onSwitchTimeout` shows the card (`cardShown = true`, state `Error`) and the retries continue behind it.
  3. A retry succeeds. In Media3 the video renderer renders the first frame while the player is still BUFFERING, and the renderer only becomes ready after that frame. So `onRenderedFirstFrame` reaches the app before `onPlaybackStateChanged(READY)`.
  4. `onFirstMedia` sets `cardShown = false`, but `player.playbackState` is still BUFFERING, so it does not publish `Playing`.
  5. READY arrives. `onPlayerState` hits `if (st is PlaybackState.Error && !c.cardShown) return` and returns.
- **Result:**
  - The error card ("No data… attempt 2 of ∞") stays on screen over a stream that is playing.
  - `isPlaybackActive()` is now false (Error with `cardShown = false`). On Home, `LifecycleCoordinator.onProcessStop` therefore does not stop playback: the stream and the provider connection keep running in the background.
  - `keepScreenOn` keeps the value it had under the card.
- **Confidence:** confirmed by a temporary Robolectric test using the existing `ControllerFixture`. Sequence: unlimited retries, RetrySame, error, wait past the switch timeout, wait for the next attempt, then BUFFERING → first frame → READY. The state stayed `Error(NO_DATA_TIMEOUT, retry = …)`. The existing test `unlimitedRetryCardCarriesRetryInfo…` passes only because `ControllerFixture.play()` emits READY before the first frame, which is the opposite of Media3's order.
- **Fix:** in `onPlayerState`'s READY branch, lift a card that first media already cleared. Either:
  - track it explicitly: in `onFirstMedia`, when `cardShown` was true and READY has not arrived, set a flag such as `c.cardLiftPending = true`; in READY, when `st is Error && c.cardLiftPending`, clear the flag and publish `Playing`; or
  - tie the rule to the session: `if (st is Error && !c.cardShown && !(s.firstMedia && s === session)) return`.

  Also change `ControllerFixture.play()`, or add a variant, so tests emit the first frame before READY, as Media3 does.

### P2 (Medium): the Sources picker and ladder base rotation do nothing on a catch-up (archive) cycle
- **Where:**
  - `player/.../controller/PlaybackControllerImpl.kt:624-638` (`selectServer`) and `:1646-1651` (`ReResolve(rotateBase)`) set `c.baseIndex`.
  - The archive resolve ignores it: `resolveOutcome` (`:1003`) calls `resolver.expandArchive(identity, request)`, which builds `ResolveInput` with the default `baseIndex = playlist.activeBaseIndex` (`player/.../resolve/StreamUrlResolverImpl.kt:86-93`). `StreamUrlBuilder.buildArchive` then expands the fixed `request.url`, but still reports `baseCount`/`baseHosts` for every base (`core/.../resolve/StreamUrlBuilder.kt:154-171`).
- **Scenario:**
  1. On an Xtream playlist with backup servers, the user plays a catch-up programme.
  2. `QuickBarModel` shows SOURCES, because `menu.servers.size >= 2` has no archive guard (`core/.../full/state/QuickBarModel.kt:62`).
  3. The user picks server 2. The session restarts on the same URL and host, and the picker snaps back to server 1.
  4. Likewise, a ladder `ReResolve(rotateBase = true)` on an archive failure "rotates" but retries the same host, which uses up the re-resolve budget for nothing.
- **Confidence:** confirmed by reading the whole path (controller → resolver → builder → quick bar).
- **Fix:** do one of the following:
  - Have `buildArchive` return `baseHosts = emptyList()` and `baseCount = 1`, so neither the picker nor rotation is offered for archive.
  - Pass `r.baseIndex` through `expandArchive` and rebuild the archive URL on the chosen base, which needs the archive request to carry a template rather than an expanded URL.

  The first option is the safe minimum.

### Player areas checked with nothing to report
- `LiveTsDataSource`: no Range header (it is filtered at `:242`); the reconnect budget; preempt/terminate paths; the lease released on every failed open (`:165-168`); the end() vs open() race (guarded by the `isEnded` re-check after acquire); the watchdog cancel vs EOF.
- `BrokeredCallFactory` and `TransportFactoryImpl`: release only after `callEnd`/`callFailed`, `forceRelease` on session end.
- `StreamHttpDataSourceFactory` (Range allowed on HLS/archive, by design).
- `StreamProbeImpl` (goes through the broker; no Range).
- Broker bypass: `StreamProbeImpl` and `UpdaterImpl` are the only direct `newCall` sites. The updater is not a stream.
- `BoxLoadErrorHandlingPolicy`.
- `LifecycleCoordinator`.
- `SurfacePlayer` (no graphicsLayer; it detaches its view).
- `PlaybackWatchdog`.
- `DisplayModeController` settling (it has a 3 s timeout).
- `TrackController` (`uppercase()` is Kotlin's locale-invariant version; the subtitles-off memory `""` round-trips).
- Ladder-learned audio policy, which `PlayerFactory.applySettings` resets every session.
- `RingDataSource`.
- `TimeshiftStorageImpl` (sweep, daily counter).
- `TimeshiftSessionImpl` close order.
- `ZapImportGate`.
- Stale-error detection (`ExoPlaybackException.timestampMs` and `MonoClock` are both `elapsedRealtime`).
- Every `PlaybackErrorCode` has a mapping in `ui/.../playback/ErrorText.kt` (25 of 25).

---

## UI

### U1 (High): in Search, BACK closes the app once focus has fallen to the root key sink and the query is not empty
- **Where:**
  - `ui/.../search/SearchScreen.kt:110-120`: Search's BACK handling is an `onPreviewKeyEvent` on a child Box.
  - `ui/.../input/KeyRouterImpl.kt:171`: in SEARCH or SPORTS the router takes BACK only while `searchFieldIdle`, meaning the query is empty and no menu is open.
  - `ui/.../root/AppRoot.kt:277`: `LifecycleEventEffect(ON_RESUME) { requestSinkFocus }`.
  - MainActivity has no `OnBackPressedCallback`, `BackHandler` or `onKeyDown`.
- **Scenario:**
  1. The user types "news" in Search and moves DOWN onto a result card.
  2. Focus moves to the key sink. Two things do this:
     - the focused card is removed (see U2);
     - the user comes back from Home or the screensaver, and ON_RESUME pulls focus to the sink.
  3. The user presses BACK. Compose sends keys only along the path from the root to the focused node, so the Search Box's preview handler never sees it. The router returns false because the query is not empty.
  4. The Activity's default BACK runs: `finish()` below API 31, task moved to the back on 31+. The user is thrown out of the app.
- **Confidence:** confirmed. The UI reviewer used a temporary Robolectric test (since deleted). It showed that removing a focused child moves focus to the nearest focusable ancestor (the sink), and that a BACK then never reaches the child Box's `onPreviewKeyEvent`. I re-read the router line and the Search handler.
- **Fix:**
  - In SEARCH and SPORTS, when the sink itself holds focus, the router always consumes BACK and hands it to a host callback (Search `onBack`, or dismiss its menu).
  - Add a MainActivity `OnBackPressedCallback` that swallows a stray BACK.
  - Do not grab sink focus on ON_RESUME in SEARCH or SPORTS, or while the settings text editor is open.
  - After results change, move focus back to the search field.

### U2 (Medium): Search wipes its results and drops focus on every library or EPG content change, including the user's own "Add to favourites"
- **Where:** `ui/.../search/SearchViewModel.kt:135-141` and `:164-169`. `refresh()` publishes `results = null` at once and searches again after 300 ms. It runs on every emission of `library.sources(OPEN)`; the source tables include `favourite` and `favourite_list`. It also runs on every `rowContent` tick and every `epg.info` generation.
- **Scenario:**
  1. In Search, the user long-presses OK on a channel and picks Add to favourites.
  2. The sources re-emit. Every card disappears ("Searching…") and focus falls to the sink, which sets up U1.
  3. A scheduled EPG refresh while Search is open does the same.
- **Confidence:** confirmed by reading (UI reviewer); the focus drop is the behaviour confirmed by the temp test.
- **Fix:**
  - Keep "clear first" only for an access (lock) change, which is why it exists.
  - On a content refresh, keep the current snapshot until the new result replaces it.
  - Restore focus to the field when the focused card goes away.

### U3 (Low–Medium): GuideViewModel's tuned-channel cache stores misses and is never cleared
- **Where:** `ui/.../guide/vm/GuideViewModel.kt:466-483`, called from `:437-451`. The cache holds `null` after an exception and is never invalidated. `PlayerViewModel.kt:245, 377, 634-646` caches hits only and clears on `library.rowContent` (M3 H-L12).
- **Scenario A:** the user renames the tuned channel, changes its logo or fixes its EPG mapping in the editor. The mini player still, the error-card logo, and the guide machine's `loadTunedNow` (old `epg` reference and shift) stay stale until another channel is tuned.
- **Scenario B:** one failed lookup (DB busy during a refresh) pins `rowId = -1`. The tuned-row marker is lost for that channel.
- **Confidence:** by reading.
- **Fix:** use the PlayerViewModel pattern: cache hits only, clear on `library.rowContent`, and re-dispatch `Tuned` after a clear.

### U4 (Low): a failed number lookup leaves the number overlay on screen
- **Where:** `ui/.../guide/vm/GuideEffectsImpl.kt:242-254`, with `launchSafely` at `:303-313`.
- **Scenario:**
  1. `library.byNumber` throws, for example a DB error during a playlist rebuild.
  2. `launchSafely` logs and swallows the exception, so no `GuideEvent.Number` is dispatched.
  3. The machine keeps `numberCommitting = true`, and its tick never closes a committing overlay (`core/.../GuideStateMachineImpl.kt:2067`).
  4. The digits stay up until the user presses BACK or types another digit.
- **Confidence:** by reading.
- **Fix:** in the catch, dispatch `GuideEvent.Number(token, null, -1)`, which shows "No channel N" and auto-closes.

### U5 (Low): Search programme times ignore the 12/24-hour setting
- **Where:** `ui/.../search/SearchScreen.kt:331-340`. The formatter is a top-level `DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())`: it is always 24-hour, and it captures the locale once per process.
- **Scenario:** with 12-hour time on, Search shows "19:30 – 20:00" while the header, mini player, sync chip, Sports and Settings show "7:30 PM".
- **Confidence:** by reading. No decision note exempts Search.
- **Fix:** pass `clock24` through and format with `TimeFormats`, as `HeaderTabs` does.

### UI rule checks (no violations)
- **SurfaceView ancestors:** `VideoLayer` is the first child of an unlayered root Box. Every `graphicsLayer`, clip and alpha is on a sibling or a leaf.
- **dp/sp:** the `.dp`/`.sp` used in guide and player screens is design px through `BoxDesignScale`'s density override (1 dp = 1 design px).
- **Error text:** `ErrorText` maps every `PlaybackErrorCode` to a reason and a next step.
- **Locale:** no `String.format` without a Locale.

---

## App

### A1 (Medium): "Back to Auto" on the audio-output error card changes the setting but never restarts playback
- **Where:**
  - `app/.../errors/ErrorRouteActionsImpl.kt:64-72` calls `applier.apply()`, which reaches `LiveSettingsApplierImpl.kt:40` and then `controller.reapplySettings()`.
  - `player/.../PlaybackControllerImpl.kt:593-595` returns at once when the state is not Playing, Buffering, Connecting or Stalled. On a terminal card the state is `Error` (`fail()`, `:1805`).
  - The full-screen machine sends BACK_TO_AUTO only to `errorAction` (`core/.../PlayerStateMachineImpl.kt:1075`), never to retry.
- **Scenario:**
  1. "Always passthrough" is on and the AVR rejects the audio.
  2. The card offers Back to Auto first (`enrich`, `:1831`). The user presses it.
  3. `audioOutput` becomes AUTO and the toast says audio output is back to Auto.
  4. The black screen and the card stay until the user also presses Retry.
- The archive card behaves the same way: `reapply` returns on `c.isArchive`. The AUDIO picker's Back to Auto works only because the state there is Playing.
- **Confidence:** confirmed by reading the whole path (I re-traced it).
- **Fix:** after the store update, if `controller.state.value is PlaybackState.Error`, call `controller.retry()` (or `archive.resume()` for an archive card, as RETRY does). Otherwise call `applier.apply()`.

### A2 (Medium): settings that need a re-prepare are dropped while watching rewound content from the local time-shift buffer
- **Where:**
  - `app/.../settings/LiveSettingsApplierImpl.kt:33-38` sends phases PLAYING, SEEKING, CONTINUING, STARTING and PROBING to `archive.reapply(playhead)`. A local-buffer session publishes those phases too (`ArchivePlaybackImpl.publishLocal`, `:1476`).
  - `ArchivePlaybackImpl.kt:436` then does `if (s.local) return`, assuming the controller re-prepares the reader itself. Nothing calls `controller.reapplySettings()` on that path, and the controller collects only `afrMode`.
- **Scenario:** the user rewinds 10 minutes, opens Settings and changes the decoder, buffer size, audio output or tunneling, then closes Settings. Nothing is applied until the next zap.
- **Confidence:** confirmed by reading (app reviewer).
- **Fix:** in `ArchivePlaybackImpl.reapply`, change the local case to `if (s.local) { controller.reapplySettings(); return }`.

### A3 (Medium): the updater stays at "Installing…" for the rest of the process after the user backs out of the fallback system installer (Fire OS)
- **Where:** `app/.../update/UpdaterImpl.kt:374`. `InstallStatus.FallbackStarted` sets `Installing`, but no `UnansweredConfirmationWatcher` is armed (only `PendingUserAction` arms one, `:370-372`) and no broadcast follows.
  - While the state is `Installing`, `download()` returns (`:124`) and `runCheck` reports busy (`:187`).
  - The About "Check now" row is disabled.
- **Scenario:**
  1. On Fire OS `createSession` throws, so `InstallResultReceiver.kt:71-78` falls back to ACTION_VIEW.
  2. The user presses Back on the system installer.
  3. Update and Check now do nothing until the app is force-stopped. Playback was already stopped for the install.
- **Confidence:** confirmed by reading (I re-read lines 362-394).
- **Fix:** arm the same watcher on `FallbackStarted`. If the state is still `Installing` when the app comes back, set it to `ReadyToInstall(m.versionName)`. There is no session to abandon.

### A4 (Low–Medium): the APK copy into the install session and its fsync run on the main thread
- **Where:** `UpdaterImpl.kt:131` and `:338`: `install()` and `runInstall` run on Main. `installer.install()` runs `copyTo` and `session.fsync` synchronously (`InstallResultReceiver.kt:81-90`).
- **Scenario:** a 30–40 MB APK on a slow Fire TV Stick eMMC freezes the UI for seconds right after Update is pressed. This risks an input ANR.
- **Confidence:** the blocking is confirmed; whether it reaches an ANR depends on the device.
- **Fix:** create, write and commit the session on IO. Keep only `startActivity` and the state publishing on main.

### A5 (Low): the external-player button on the error card stops our stream before it knows the player can open it, and a failure is silent
- **Where:** `ErrorRouteActionsImpl.kt:61, 78-84`. `handOffExternal()` stops our stream (`PlaybackControllerImpl.kt:615`) before `launcher.launch()`. On failure it silently opens Settings → Playback, with no reason. This is against the CLAUDE.md "reason + next step" rule. The Channel options path does show `CH_EXTERNAL_FAILED`.
- A plausible trigger: `ExternalPlayerLauncher.kt:62-68` sends `.m3u8` as `application/x-mpegURL` together with `setPackage`. The installed-players list was found by querying `video/*`, and intent MIME matching is case-sensitive.
- **Confidence:** the no-reason path is confirmed by reading; the MIME mismatch is plausible.
- **Fix:**
  - Resolve the intent before stopping, and fall back to `video/*`.
  - Use `setDataAndTypeAndNormalize`.
  - On failure, show the same "couldn't open {player}" toast.

### A6 (Low): an install session is left open when the confirmation activity cannot be started
- **Where:** `InstallResultReceiver.kt:130-135`. When `startActivity(confirm)` throws, the code reports `Blocked` but neither abandons the session nor clears `pendingSession`.
- **Scenario:** each Update press leaks a system install session. A later `abandonPending()` can then target a stale id.
- **Confidence:** confirmed by reading; the trigger is rare.
- **Fix:** abandon the session id from `EXTRA_SESSION_ID` and set `pendingSession = null`.

### App notes
- Reminders are foreground-only by design (§3.11.3). There are no alarms, so exact-alarm permission, reboot and time-zone change do not apply.
- The API guards for 26, 28, 31 and 33 are all present.

---

## Worth a look (unverified)
- **`PlaybackControllerImpl.kt:2685-2690`:** a PREEMPTED writer while the reader is at the head (or before the first byte) is ignored as "stale". After `WriterListener.post` has filtered by `writerTuneId`, it cannot be the old writer. If something else in the app pre-empts that PLAYBACK lease, the reader then waits at the head until the NO_BYTES stall verdict, a few seconds of frozen picture, instead of an immediate decision.
- **`UpdaterImpl`:** if the install confirmation is a translucent dialog, our activity only pauses and `UnansweredConfirmationWatcher` never fires. If Back sends no ABORTED broadcast, the updater has the same stuck "Installing" as A3.
- **`RestartNow.kt:46-47`:** `startActivity` immediately followed by `Runtime.exit(0)` can drop the restart on some OEMs.
- **Start over:** the local-buffer path (`ArchivePlaybackImpl.kt:259`) includes `correctionMs` and the provider path (`:276`) does not, so with a non-zero catch-up correction they can pick different programmes. This may be deliberate (A-B4).
- **`WpaPageEnvImpl.kt:61-68`:** the time-shift storage row always reports the internal ring file, so it shows 0 bytes when the ring is on USB or SMB.
- **`AppRoot.kt:277`:** the ON_RESUME sink grab also takes focus from Sports cards and from the settings text editor, and closes the IME.
- **`GroupsPanel.kt:95`:** `uppercase()` uses `Locale.ROOT`, which is wrong for Turkish İ. That may be acceptable.
- **`PlayerParts.kt:272-273`:** the 12-hour "h:mm" has no AM/PM, so toasts like "Now at 9:26" are ambiguous.
- **`PlayerViewModel.kt:602-606`:** `StatFs` runs on main.

## Coverage
- **player (read closely):**
  - all of `datasource/` (LiveTsDataSource, BrokeredCallFactory, TransportFactoryImpl, StreamHttpDataSourceFactory, BoxLoadErrorHandlingPolicy, FileCaptureSink)
  - `broker/`
  - `controller/`: PlaybackControllerImpl, roughly lines 255-2720 (tune, zap, session, ladder, pause, stop, lifecycle, tracks, time-shift glue), plus LifecycleCoordinator, SurfacePlayer, SessionPlayer, TuneSession, ZapImportGate
  - `engine/`: PlaybackWatchdog, TrackController, DisplayModeController (settling), PlayerFactory.applySettings
  - `probe/StreamProbeImpl`, `resolve/StreamUrlResolverImpl`
  - `timeshift/`: RingDataSource, TimeshiftSessionImpl, TimeshiftStorageImpl, PendingTimeshiftPreparation
- **player (skimmed or not reached):**
  - FailureClassifier (only the stale rule), TimeshiftSessionFactoryImpl, AndroidRingStorage, VolumeStorage, VolumeWatcher
  - MediaSourceBuilder, TsExtractorsFactory, PassthroughGateAudioSink, StereoDownmixProcessor, AdaptiveLoadControl, StatsCollector, FpsEstimator, DeviceCapabilities
  - PlaybackControllerImpl's writer-storm and executeBuffered tail (about lines 2720-3090)
- **ui (read closely, UI reviewer):**
  - `input/KeyRouterImpl`, `root/AppRoot`
  - `guide/vm/*` (GuideViewModel, GuideEffectsImpl, TunedStateMapper, LineupZapList)
  - `full/vm/PlayerViewModel`, `full/vm/PlayerEffectsImpl`, `full/FullScreenHost`
  - `search/*`, `sports/SportsViewModel`, `sports/ScoreBug` (league and takeover state)
  - `editor/ChannelEditorViewModel`, `backup/PhoneSetupViewModel`
  - MiniPlayerOverlay, NumberEntryOverlay, ErrorText, VideoLayer, BoxDesignScale
- **ui (skimmed):** settings TextEditorOverlay and SettingsScreen, PinPadView, PlayerText and PlayerParts, GuideUi (TimeFormats), DiagPages, GroupsPanel.
- **ui (not reached):**
  - the NFL and score-bug painters
  - GuideGridHost and AndroidGuideCanvas
  - ChannelEditorScreen and EditorRows, SettingsPageAdapters and SettingsEffectsImpl, setup screens
  - text-overflow checks of InfoBar, ScrubBar, ChannelListPanel and PickerSheet
  - ambient
  - guide midnight/DST (that lives in core)
- **app (read closely, app reviewer):**
  - update (UpdaterImpl, InstallResultReceiver, UpdateCheckSchedulerImpl, ApkInspector, UpdateActionsImpl)
  - reminder, MainActivity, TheBoxApplication, errors, external
  - settings (LiveSettingsApplierImpl, PersistedPlaybackSettings, RestartNow, WpaPageEnvImpl, StoragePageEnvImpl, ChannelOptionsPageSource)
  - playback (LocalTimelineBackend, DataPlaybackSourceProvider, RoomChannelMemory, FallbackToastPolicy, CatchupLauncherImpl)
  - the manifest
  - ArchivePlaybackImpl: all of it read; ownership, lifecycle and settings paths traced
- **app (not traced):** the catch-up time arithmetic (panel zone, correction), which lives in core's ArchivePlanner and CatchupUrlBuilder.
- **app (not reached):** the DI wiring modules and `app/src/debug`.
