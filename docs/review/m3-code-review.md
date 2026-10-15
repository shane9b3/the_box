# M3 "Guide": code review

Reviewer pass over the merged M3 code (`git diff 9a87271..dc88fab`), 2026-10-14, in two halves run in parallel and merged here:
- **Engine and data** (tags `G-`): `core/.../guide/{layout,render,feed}`, the guide contracts, the prefix rule, the M3 changes to the EPG reader, `data/.../guide`, `data/.../reminder`, the M3 DAO/entity/migration changes, `ui/.../guide/grid` and `ui/.../search`.
- **Shell and behaviour** (tags `H-`): `core/.../guide/state`, `core/.../input`, `ui/.../{root,ambient,full,reminder,input}`, `ui/.../guide/{screen,overlay,vm}`, `app/.../{MainActivity,TheBoxApplication}`, `app/.../reminder`, the catch-up launch, the guide device suite, the benchmarks and the fake-panel changes.

Checked against `CLAUDE.md`, `00-master-plan.md` §3 and §6, `design/m3-guide.md` (lead sign-off, §0, §2, §2.12, §2.13, §3, §4, §5.1–§5.5, §5.11, Appendix B), `review/m3-design-review.md`, `design/m3-compose-tv-api-notes.md`, `design/m2-playback-core.md` §2.3 and §3.9.5, and the WP-A…WP-D hand-off notes.

Method: every finding was traced through the code. Five were reproduced with throwaway JVM tests before the fixes (G-S1, H-B2, H-S1, H-S2, H-S11). Every fix below has a regression test, named next to the finding. **Fails-before check:** with each fix's behaviour reverted in place (new contract members kept so the tests compile; 26 reversions), **37 of the new or changed tests fail**, which covers every finding with a JVM-testable behaviour. The rest are guards (for example `cardAcceptOfTheTunedChannelGoesFullAtOnce`, `goldenCatchesLostTimeLines`, `metaCacheMatchesTheUncachedPathAndFollowsInvalidation`), new-API tests (`KeyRouterTest.mediaKeysNotHandledInFullPassThrough`, `PlaybackControllerStateTest.renderedFirstFramesCountAcrossTunes`), or device checks (H-S10). **Line numbers refer to `dc88fab`.** The `G-`/`H-` tags also appear in the code comments and test names the fixes added.

The lead's decisions where the reviewers left a choice:
- **H-B1:** the HDR poster is owned by the playback controller and is independent of the viewport. In MINI with an HDR stream and the poster setting, the controller disables the video track and the poster shows while the viewport **stays MINI**; the view model no longer maps the poster to a still/HIDDEN. M2 §3.9.5 and M3 §3.13.2, §3.13.4, §3.16.2 amended.
- **H-B2:** the reminder card is modal only where it is drawn (GUIDE, FULL); in SETTINGS/SEARCH it waits (no keys, no countdown, no auto-switch) and gets a fresh guard when the mode returns.
- **H-B3:** shell events are a buffered `Channel`, consumed once.
- **G-S3 = H-S3:** one finding; the view model resets `TimeLabels` and invalidates the pager on a clock-form or zone change (`ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, the setting).
- **G-S4:** the paragraph direction is the first strong character's (`TextFit.isRtl`); §3.2.3 amended.
- **H-S4:** media keys the machine ignores in FULL pass through to M2's media session; MEDIA_NEXT/PREVIOUS zap in FULL (H-L14 taken).
- **G-S6:** the no-schema option: `channel_prefs` is watched through a signature of the guide-relevant columns, so no library.db v4.
- **O-2:** after steps 1–4 `guide-draw` was still 3.7–4.9 ms per draw (above 2× the 2 ms budget), so the per-row recording cache **was built** (API 29+ `RenderNode`, behind `AndroidGuideCanvas.ROW_LAYERS`; direct drawing on API ≤ 28 and software canvases).

**Result:**
- Blockers: 3 found (H-B1, H-B2, H-B3), 3 fixed.
- Should fix now: 16 found (G-S1, G-S2, G-S3 = H-S3, G-S4, G-S5, G-S6, H-S1, H-S2, H-S4–H-S11), 16 fixed.
- Later: 36 items (20 engine, 16 shell). 3 taken (G-L6, G-L7, H-L14); 33 not applied.
- Performance plan O-1/O-2/O-3/O-7: applied in full, including the O-2 per-row recordings; numbers below and in design §5.12.
- Build (`:core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:assembleBenchmark :player:lintDebug :ui:lintDebug :app:lintDebug`): green. `:core` 1,709 tests (was 1,679) plus `stateOnRealNavigator` 248 (was 227), `:data` 154 (was 151), `:player` 306 (was 304), `:ui` 142 (was 120), `:app` 21 (was 21); 0 failures, 0 skipped. Lint: 0 errors. `:player:testDebugUnitTest` hung twice inside a combined run, in `PlaybackWatchdogTest` (`verdictReachesTheCallback`, `ticksOncePerSecondOnlyWhilePlayWhenReady`: Robolectric `idle()` on a repeating tick; untouched code, the known intermittent hang); re-run alone: 306/306.
- Device: every guide class, the four M2 classes and `I1LibraryDatabaseTest` pass, except M2 I-21 (known emulator flake) and the two I-22 assumption skips; see "Device pass" at the end of the Performance section. One new pre-existing defect found by the corrected I-16 (Q-1).

---

## Blockers (fixed)

### H-B1. An HDR channel in the mini player flips between STILL and MINI forever
- **Where:** `GuideViewModel.kt:278-283` (`still = v.posterMode || …`), `AppRoot.kt:58-69,145-146` (still → HIDDEN), `PlaybackControllerImpl.kt:1494-1519` (`posterMode` true only while the viewport is MINI).
- **Problem:** MINI → poster → still → HIDDEN → no poster → MINI → … `StateFlow` does not dedupe it (the values alternate).
- **Effect:** on every HDR channel in the guide: endless recomposition, a surface resized between 1×1 and 720×405 per flip, the poster never settles; worst on the low-end devices the app targets.
- **Fix (lead decision):** the VM's still is `settings.miniStill || device.stillByDefault`; the shell reads `VideoInfo.posterMode` directly: `MiniPlayerOverlay` shows the poster still, `HoleChrome` paints the still background in MINI over the parked surface, the viewport stays MINI. The controller rule is unchanged (it already kept the viewport); M2 §3.9.5 now says the poster is never a reason to change the viewport.
- **Tests:** `GuideViewModelTest.hdrPosterIsNotAStill`, `RootTreeRuleTest.hdrPosterStaysMiniAndShowsThePoster` (a host that reacts like the controller; one MINI request, poster still shown, surface on the hole rect), `PlaybackControllerStateTest.hdrPosterIsStableWhileTheViewportStaysMini`.

### H-B2. The reminder card was modal while invisible in SETTINGS and SEARCH
- **Where:** `GuideStateMachineImpl.kt:214` (card keys before any mode check); `AppRoot.kt:210-212` draws it only in GUIDE/FULL.
- **Problem / effect:** in Settings the first BACK dismissed an unseen reminder (OK accepted it and went FULL); in Search BACK on an empty field dismissed it; auto-switch left Search without a `SearchClosed`.
- **Fix (lead decision):** keys, the countdown, the deadline and auto-switch apply only while the card is drawn (`cardShown()` = GUIDE or FULL). When the mode returns, the card gets a fresh `REMINDER_CARD_GUARD_MS` guard and its countdown restarts from then (`rearmDeferredCard`).
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.invisibleCardDoesNotEatBackInSettings`, `…invisibleCardDoesNotEatBackInSearchNorAutoSwitch`; `keys.csv` rows `settings_card` (BACK, SELECT), `search_card` (BACK).

### H-B3. The "Missed while The Box was closed" toast was dropped at startup
- **Where:** `GuideViewModel.kt:115` (`MutableSharedFlow(extraBufferCapacity = 8)`, `replay = 0`) with `tryEmit`; `AppRoot.kt:162-174` subscribes after the first composition.
- **Problem / effect:** the reminder runtime's ON_START pass counts missed reminders and marks them fired before the root subscribes; a `SharedFlow` with no subscriber discards the toast. The N5 promise failed on the most likely path (a cold start).
- **Fix:** shell events are a `Channel(BUFFERED)` exposed with `receiveAsFlow()` (`ShellHost.events: Flow`), consumed once; toasts, announcements and Exit sent with no UI attached wait for the root.
- **Tests:** `GuideViewModelTest.missedToastSurvivesLateSubscription`; device `ReminderTest.missedToastAfterARestart` (I-10: the activity is closed, the process goes to the background, an ended reminder is stored, the guide is started again and the toast text must appear).

---

## Should fix now (fixed)

### G-S1. A vertical hold re-queried the whole hold target on every row step (S16 defeated)
- **Where:** `GuideWindowPagerImpl.kt:160-175`: symmetric 6-row margin against the asymmetric hold target.
- **Effect:** up to 20 full snapshot builds a second during a hold; a direct contributor to O-1/O-2.
- **Fix:** directional margin: down `[top, top + 12)`, up `[top − 6, top + 6)`; a steady hold re-queries about every 12 rows.
- **Test:** `GuideWindowPagerTest.steadyHoldRequeriesAboutEveryTwelveRows` (≤ 2 queries over 20 one-row steps, each direction).

### G-S2. Title width and RTL/COMPLEX caches survived a patch, but `epg_text` ids can be reused
- **Where:** `GuideWindowPagerImpl.kt:308-313` (patch branch), `:483-490`.
- **Fix:** the patch branch clears every `textId`-keyed cache (widths, flags and the new title-hash cache, `clearTextCaches()`).
- **Test:** `GuideWindowPagerTest.patchClearsTextCaches` (a reader spy returns another string for the same ids after a patch: width, RTL flag and title key follow the new string).

### G-S3 = H-S3. `TimeLabels.reset` was never called: the grid ignored the 12/24 h setting and any zone change after start
- **Where:** `GuideGridModule.kt:36` (one `TimeTables()`), no caller of `reset`, no `ACTION_TIMEZONE_CHANGED` handling.
- **Fix:** the VM combines `settings.clock24`, a zone flow fed by an `ACTION_TIMEZONE_CHANGED`/`ACTION_TIME_CHANGED` receiver (registered `RECEIVER_NOT_EXPORTED`, unregistered in `onCleared`) and a clock-jump counter; each change calls `labels.reset(zone, clock24)`, `pager.invalidate()` and re-sends `Settings`, on which the machine now also refreshes its window labels. `TimeLabels.version` (new, bumped by `reset`) drives the pager's time-text cache and the renderer's text caches (replacing the `hm(0)` probe, **G-L7 taken**). The header clock and mini player format with cached `SimpleDateFormat`s keyed by the default zone (H-S9).
- **Tests:** `GuideViewModelTest.clockFormAndZoneChangesResetTheLabels` (setting and a real broadcast after `TimeZone.setDefault`), `GuideWindowPagerTest.timeTextsAreCachedAndFollowTheClockForm`.

### G-S4. Mixed-direction titles were right-aligned and ellipsised on the left
- **Where:** `GuideWindowPagerImpl.kt:535-538` (`Bidi.requiresBidi`), `GuideRendererImpl.kt:446, :557-578`.
- **Fix (lead decision):** `CellFlags.RTL` = first strong character RTL (`TextFit.isRtl`); for cells the renderer uses the flag alone (names keep the fit's own detection). §3.2.3 amended.
- **Tests:** `GuideRendererTest.mixedLtrTitleWithArabicStaysLeftAligned` (flags from the pager), `GuideWindowPagerTest.rtlFlagFollowsTheFirstStrongCharacter`.

### G-S5. The golden-frame tolerance could not see a lost focus ring, ruler or time lines
- **Where:** `GoldenFrameTest.kt:28-29, :80-86` (0.5 % of the frame, alpha not compared).
- **Fix:** ≤ 0.05 % (1,036 px at 1080p) with the 24-level threshold, alpha compared; four mutation tests render the frame through a canvas that drops one element and must exceed the tolerance.
- **Tests:** `GoldenFrameTest.referenceFrameMatchesGolden` (current golden still matches; the two-pass renderer is pixel-identical within it), `goldenCatchesALostFocusRing`, `goldenCatchesLostRulerLabels`, `goldenCatchesLostTimeLines`, `goldenCatchesALostNowLine`.

### G-S6. Every tune reloaded every loaded row block and re-ran the lineup and the Groups panel about 5 s later
- **Where:** `PlaybackControllerImpl.kt:969-975, :1815` (watch history), `GuideLibraryImpl.kt:151-166` (`channel_prefs` in all three table sets), `GuideRowsCacheImpl.kt:95-100`.
- **Fix (no schema change):** the lineup, `sources` and `rowContent` flows no longer list `channel_prefs`; they merge their table flow with a **prefs signature** (`GuideQueries.prefsSignature`: a 64-bit hash over the `channel_prefs` rows that set any guide-relevant column: hidden, locked, custom name/number/logo/sort, shift; plus, for `sources`, the number of watched channels). Watch-history and learned-playback writes leave it unchanged. Recent still re-queries on every `channel_prefs` change. Also (review option a): `reloadLoaded` does not bump `rows.version` when a reloaded block is equal to the one it replaces. Keys stay `(playlist_id, stable_key)`; library.db stays v3.
- **Tests:** `GuideFlowsTest.tuneTimeWritesDoNotRequeryTheGuide` (watch, playback flags, fps and audio-language writes: no row reload, no lineup query, no Groups rebuild; a hide triggers all three), `GuideFlowsTest.recentFollowsTheWatchHistory`, `GuideRowsCacheTest.reloadWithTheSameRowsDoesNotBump`.

### H-S1. Hiding the focused channel (or any removal from the current lineup) jumped focus to row 0
- **Where:** `GuideStateMachineImpl.onLineup`, `:965-982`.
- **Fix:** same source, focused row gone → the same index (clamped) at the current anchor, `topRow` clamped, resolved by the navigator (§3.9.3).
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.hideKeepsTheIndex`, `hidingTheLastRowFocusesTheNewLastRow`, `unfavouritingInFavouritesKeepsTheIndex`; `GuideViewModelTest.hidingTheFocusedChannelKeepsTheIndex`. `GuideWorld` now re-emits the lineup on hide and on unfavouriting in Favourites.

### H-S2. Startup focus missed the tuned row when the first lineup landed before the startup `Tuned`
- **Fix:** `startupFocusPending` until the first `Tuned` with an identity, the first user action or a Search exit; that `Tuned` focuses the tuned row at now when the first lineup did not.
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.startupTuneAfterLineupFocusesTheTunedRow`, `startupTuneAfterAUserMoveKeepsTheUserFocus`, `laterTunesDoNotMoveFocus`; `GuideViewModelTest.startupTuneAfterTheFirstLineupFocusesTheTunedRow`. `GuideWorld(tuneAfterLineup = true)` answers in the other order.

### H-S4. The key sink swallowed media keys FULL ignores: remote Play/Pause no longer reached M2's media session
- **Fix (lead decision):** `GuideStateMachine.handles(action)` (new, default true) is false in FULL, with no overlay or card, for PLAY_PAUSE, SEEK_BACK and SEEK_FORWARD; the router returns false for such a key's DOWN (and so its UP), so the framework hands it to the session. `DefaultKeyMap` binds `KEYCODE_MEDIA_NEXT`/`PREVIOUS` (87/88) to ZAP_DOWN/ZAP_UP in PLAYER (**H-L14 taken**); unbound in the guide.
- **Tests:** `KeyRouterTest.mediaKeysNotHandledInFullPassThrough`, `mediaNextIsUnboundInTheGuide`, `GuideBehaviourTest.CodeReviewFixes.fullLeavesMediaKeysToTheSession`, `DefaultKeyMapTest`, `keys.csv` rows `full,PLAY_PAUSE` and `full,SEEK_FORWARD`.

### H-S5. `onCleared` of an old view model detached the new one from the singleton router and probe
- **Fix (lead decision):** `KeyRouter.detach(machine)` (new) clears only when that machine is still attached; the probe is cleared only when `current === machine`.
- **Tests:** `KeyRouterTest.detachOfAStaleMachineKeepsTheCurrent`, `GuideViewModelTest.staleOnClearedKeepsTheNewViewModelAttached` (two view models, the old one cleared last).

### H-S6. The empty-source next step ("Press Left to choose another group") did nothing
- **Fix:** LEFT on an empty lineup opens Groups.
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.leftOnAnEmptySourceOpensGroups`, `keys.csv` row `grid_empty_source,LEFT`.

### H-S7. Accepting a reminder went FULL even when the switch failed, and the effect re-read `alerts.current`
- **Fix (lead decision):** `GuideEffects.answerReminder(alert, accept)` (changed signature) works from the alert the card showed; FULL follows the first active `Tuned` of the alert's channel (at once when it is already the live channel); a gone channel toasts and stays in the guide; any user action cancels the pending FULL.
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.cardAcceptChannelGoneStaysInGuide`, `cardAcceptOfTheTunedChannelGoesFullAtOnce`, `keys.csv` row `card_channel_gone`, `GuideEffectsImplTest.acceptUsesTheShownAlertNotTheCurrentOne`.

### H-S8. The low-end LIVE pulse ran every frame, and `HoleChrome` had no layer
- **Fix (lead decision):** the low-end dot toggles its alpha from a 1 s `LaunchedEffect` loop (two layer updates a second); `HoleChrome` carries a default `graphicsLayer()`. It is a sibling after `VideoLayer`, never an ancestor, so the surface rule holds.
- **Tests:** `ShellRedrawTest.lowEndLiveDotStepsOncePerSecond` (3 s of low-end MINI: ≤ 6 layer updates, against ~180 before), `RootTreeRuleTest.holeChromeCarriesItsOwnLayerAsASibling` (and the existing ancestor rule).

### H-S9. Every `GuideUiState` change recomposed the whole GUIDE screen
- **Fix:** `GuideScreen` passes narrow slices (`HeaderTabs(zone, tabFocused, sync, clock24)`, `FocusedPanel(panel, focusedButton)`, `MiniPlayerOverlay(tuned, tunedNow, tunedChannel, …)`, `GridHeaderChips(…)`, `FooterHints(footer)`, `EmptyState(empty)`), and the machine keeps unchanged slices identical (an equal `Tuned`/`TunedNow` no longer replaces `tuned`/`tunedChannel`/`tunedNow`), so strong skipping skips them. The header clock and the mini player's times use cached `SimpleDateFormat`s (`TimeFormats`). A debug composition counter for `MiniPlayerOverlay` was added.
- **Tests:** `ShellRedrawTest.aGridMoveRecomposesThePanelNotTheMiniPlayer` (20 panel changes: mini overlay +0, panel ≤ 20); I-12 now asserts `FocusedPanel` ≤ published panel slices + 2 and `MiniPlayerOverlay` ≤ 2.

### H-S10. Still → FULL could hold a black scrim for the whole 2 s
- **Fix (lead decision):** `VideoInfo.renderedFirstFrames` (new, counts `onRenderedFirstFrame`, kept across tunes); the root waits for it to change (2 s only as a cap), also when leaving the HDR poster.
- **Tests:** `PlaybackControllerStateTest.renderedFirstFramesCountAcrossTunes`; I-16 measures OK → first rendered frame and asserts ≤ 2 s (measured 499–725 ms; the scrim used to wait for a `Playing` that often never changed, up to the 2 s cap).

### H-S11. Holds after a lost key-up blocked descriptions and spun the ticker
- **Fix (lead decision):** any fresh press (repeat 0) ends both holds as their releases would; the router dispatches `Release` for held keys on focus loss, on window detach/ON_PAUSE (`AppRoot`) and from a 1.5 s no-repeat watchdog (`HOLD_WATCHDOG_MS`).
- **Tests:** `GuideBehaviourTest.CodeReviewFixes.freshPressEndsAStuckHorizontalHold`, `freshPressEndsAStuckVerticalHold`, `KeyRouterTest.focusLossReleasesHeldActions`, `holdWatchdogReleasesAfterSilence`, `watchdogIsCancelledByTheUp`, `GuideViewModelTest.focusLossEndsAHoldInTheMachine`.

### Also tested (review test gaps closed)
- `FiveKeyReachabilityTest` now records what SELECT on each reached menu item, panel button, sheet button, Groups entry and shortcut does, and fails when one never produces its effect.
- I-1 measures from the new activity's first drawn frame (`MainActivity.firstFrameAtElapsedMs`) and asserts the lineup is shown.
- I-16 proves audio decoding by the playback position advancing ≥ 1 s in 2 s with the video track off.
- I-17 logs the resolve step of guide and FULL tunes separately (O-3).

---

## Later (not applied)

Both lists, deduplicated; **taken** items are fixed above.

### Engine and data (`G-L`)
- **G-L1.** On a generation swap the old reader is closed before the rebuild publishes; harmless today (snapshots hold no reader). Run the rebuild inline or amend §3.2.3.
- **G-L2.** After `BUSY_RETRIES` the coverage stays valid, so nothing re-queries until the view leaves it or `EpgStore.info` changes.
- **G-L3.** The detail cache key leaves out `epgShiftMin`: after a shift change up to 32 cached details keep old display times.
- **G-L4.** `trimMemory(dropSnapshot = true)` frees nothing while `machine.frame.data` holds the snapshot.
- **G-L5.** A reminder on a merged (> 6 h) cell stores the first segment's end, so the planner expires it at the cut.
- **G-L6.** Per-frame text work on main. **Taken** (O-2): ruler label widths per minute of day and channel-number widths cached per labels version and scale; `GuideChannel.nameHash` computed where the row is built.
- **G-L7.** Fragile `hm(0)` clock probe. **Taken** (G-S3): `TimeLabels.version`.
- **G-L8.** `LogoCache.get` starts loads from the draw pass; kick loads from the prefetch path.
- **G-L9.** Logo failures are remembered for the process, including transient ones (offline at start).
- **G-L10.** RUNNING_* trim levels never arrive on API 34+.
- **G-L11.** Channel FTS applies `LIMIT 100` in provider order before ranking: an exact name match can be cut.
- **G-L12.** Search decides "in archive" on display time and ignores `suspectArchive`; the grid uses raw time.
- **G-L13.** Search errors read as "Nothing found" (no reason and next step for `SQLITE_BUSY`).
- **G-L14.** `archiveDaysAll` runs a full aggregate on every search.
- **G-L15.** Search's time text is always 24 h.
- **G-L16.** Groups panel counts include hidden, locked and adult channels.
- **G-L17.** The "All" lineup sorts 20k rows through a temp B-tree on each query; a v4 partial index.
- **G-L18.** The merge reads the prefix rule before `ImportMutex` (M5 race).
- **G-L19.** A custom prefix regex has no time bound (M5 editor must validate).
- **G-L20.** A global shift change does not tick `rowContent` (in-memory setting).

### Shell and behaviour (`H-L`)
- **H-L1.** Debug-unlock toggles after start are ignored (nested `collect`; `loadLineup` captures `unlocked` once). Debug only.
- **H-L2.** The ticker sleeps against the wall clock; a backward jump stalls minute ticks. Cap the sleep and use `delay`.
- **H-L3.** Exit does not stop playback until ON_STOP (about 700 ms); stop it explicitly to free the connection slot.
- **H-L4.** `globalKey(EXIT)` in FULL opens an `Overlay.Exit` that `FullScreenHost` never draws (EXIT is unbound in M3).
- **H-L5.** Ambient hue and fade work runs in FULL behind the black background.
- **H-L6.** Each new hue allocates a 480×270 bitmap; use a pool of two.
- **H-L7.** Hard-coded English in `NumberEntryOverlay`; four unused `strings_search.xml` entries.
- **H-L8.** Next steps that point at the M3 Settings placeholder (release builds cannot add a playlist yet; release-notes item).
- **H-L9.** The mini error card shows the default steps, not the ladder-narrowed actions; "Press OK to retry" while focus is on another row.
- **H-L10.** `DeviceClass.lowEnd` treats `memoryClass ≤ 192` as low-end; measure real devices before M6.
- **H-L11.** Ticks, feeds and ambient keep running while the activity is stopped; gate on `repeatOnLifecycle(STARTED)`. (H-S11 removes the 60 Hz spin; this is the remaining background work.)
- **H-L12.** `channelOf` caches failed lookups per identity.
- **H-L13.** RESOLVE placements in `onWindow` keep a stale `animate`.
- **H-L14.** MEDIA_NEXT/PREVIOUS, BUTTON_SELECT and TV unbound. **Taken** for NEXT/PREVIOUS (H-S4); BUTTON_SELECT and TV remain.
- **H-L15.** `BenchSeedActivity` is exported in `benchmark`/`nonMinifiedRelease` (never shipped; say so or guard it).
- **H-L16.** `GuideDebugReceiver` logs a seeding failure's throwable raw (debug only); use `Redact.text`.

---

## Performance plan and results

Plan (lead): 1 G-S1 → 2 snapshot caches → 3 row-block cache and merged statement → 4 draw sub-sections and caches, a per-row `RenderNode` cache only if `guide-draw` stays above 2× its 2 ms budget after that → 5 O-3 (G-S6, real logos, one IO hop) → 6 O-7 (view model before prewarm, prewarm and reminders after the first frame).

What was built:
- **O-1 snapshot build:** G-S1; cell time lines cached per `(start, end)` minute-of-day pair with width by length (`CELL_TIME` is monospaced); `Hash64.of(title)` cached per text id; B3 kept (fresh immutable arrays per snapshot).
- **O-1 row block:** `GuideMetaCache` (per-playlist entity, catch-up defaults and header shift, and the active EPG sources) behind Room invalidation of `playlist`, `epg_source`, `xtream_account`, with a version check so a value read before an invalidation is not stored after it; `epg_mapping` and `epg_match` in one `UNION ALL` statement (primary-key order kept, so ties resolve as `EpgRepository.resolvedRefs`; the 1,000-input parity test passes). A warm 32-row block is 2 statements, 0 JSON decodes.
- **O-2:** trace sub-sections `guide-draw-chrome` (panel, ruler, focused cell, now line, bubble), `guide-draw-cells` (row backgrounds) and `guide-draw-text` (labels, titles, time lines): the rows are drawn in two passes, which is pixel-identical (`GoldenFrameTest` at the tightened tolerance). Ruler and channel-number widths cached; `GuideChannel.nameHash`. The split showed text recording dominating (High, journey a: chrome 0.67, cells 0.04, **text 2.94 ms** of 3.70 ms per draw), above 2× budget, so: **per-row recordings** (`GuideCanvas.beginRow(key, top)/endRow()`, toolkit-agnostic; the renderer keys each row pass by everything its ops depend on except its vertical position: data seq, rows version, window start and horizontal offset, now, scale, labels version, tuned channel, reminders, suspect list, logo/number switches, grid focus, `GuideImages.version` and the row's focus state). `AndroidGuideCanvas` records a row into a `RenderNode` and replays it translated on the next frame with the same key (a vertical scroll animation), 32 slots; the row clip is the row's own bounds so a recording replays anywhere. Tests: `RowRecordingTest` (a vertical scroll frame replays every row, a focus move re-records exactly 4 row passes, a horizontal offset or a minute tick re-records every row), `GoldenFrameTest` (software canvas: direct path).
- **O-3:** G-S6; real PNG logos in the fake panel's synthetic mode (96×54, one hue per channel id, `tiny_png`); in M2's controller a first session reads its facts and resolves in one `withContext(io)` (`ioDispatcher`, tests use main); facts are applied on main.
- **O-7:** `MainActivity` creates the view model first; `controller.prewarm()` and `StartupGate.open()` are posted after the first frame's draw; `ReminderBookImpl`'s Room query and the reminder runtime wait for the gate, and `TheBoxApplication` creates the runtime after it opens (no longer during `Application.onCreate`).

Measured on the API 30 TV emulator with `-gpu host`, fake panel `--synthetic 2000`, `benchmark` build (release-like), journeys a, c, d, 3 iterations each, Auto and forced High (journey b still fails in the harness, O-4). `frameDurationCpuMs` is CPU time only on API 30 (not gate-comparable). "Before" is `dc88fab` in a separate worktree on the same emulator session.

| Metric (emulator, `-gpu host`) | Before `dc88fab` | After steps 1–6 | After + row recordings (final) |
|---|---|---|---|
| Snapshot builds over 8 ms (all journeys, both modes, 3 iterations): count, P50 / P90 of those | 560, 16.2 / 36.7 ms | 21, 20.9 / 64.3 ms | 23, 26.2 / 47.2 ms |
| Row blocks over 15 ms: count, P50 / P90 of those | 126, 25.3 / 54.8 ms | 33, 22.7 / 36.1 ms | 34, 23.9 / 37.9 ms |
| `guide-draw` per draw, (a) 100× DOWN, High / Auto | 6.10 / 4.19 ms | 3.70 / 4.06 ms | **2.72 / 3.26 ms** |
| `guide-draw` per draw, (c) CH−/CH+, High / Auto | 4.30 / 3.95 ms | 4.01 / 4.86 ms | **2.39** / 5.33 ms |
| `guide-draw` split, (a) High: chrome / cells / text | — | 0.67 / 0.04 / 2.94 ms | 0.66 / 0.06 / 1.95 ms |
| `frameDurationCpuMs` P50 / P90 / P99, (a) High | 31.7 / 46.4 / 86.4 | 31.7 / 36.2 / 65.2 | 29.6 / 34.2 / 51.5 |
| same, (a) Auto | 23.9 / 38.8 / 55.7 | 20.9 / 36.7 / 51.4 | 20.1 / 23.0 / 41.4 |
| same, (c) High | 22.4 / 35.7 / 53.3 | 20.1 / 32.8 / 50.7 | 12.9 / 20.9 / 32.6 |
| same, (c) Auto | 18.3 / 33.5 / 56.3 | 22.7 / 37.1 / 58.2 | 20.9 / 36.2 / 69.4 |
| same, (d) FULL + CH+ 1 Hz, High / Auto P90 | 38.9 / 40.1 | 51.9 / 37.5 | 21.7 / 27.1 |
| I-17 guide OK first frame P50 / P90 (debug, one boot) | 663 / 1,219 ms | — | **553 / 834 ms** |
| I-17 guide OK `resolve` P50 / P90 | 212 / 251 ms | — | **136 / 211 ms** |
| I-17 FULL CH+ first frame P50 / P90; `resolve` P50 / P90 | 380 / 402; 87 / 104 ms | — | 395 / 426; 67 / 91 ms |
| Cold start `am start -W` TotalTime, median of 5: `benchmark` build / debug | 758 / 2,612 ms | — | 751 / 2,570 ms |

Notes: journey (b) fails in the harness before and after (O-4). "After steps 1–6" benchmark runs are from the same session as "Before"; the host then slept (the emulator clock fell 6 h behind), so I-17 and cold start were re-measured for both trees after a cold boot, and those two rows compare `dc88fab` and the final code on the same boot. The §5.11 I-17 numbers (guide OK P50 2.5 s) were on SwiftShader and are not comparable. Auto is low-end on this AVD (it snaps, so every frame of journey (c) is a new position: the recordings cannot replay and add about 10 % there; Auto (c) is the one case that got slower per draw).

**Device pass** (API 30 TV emulator, `-gpu host`, cold-booted with the clock in sync, `caffeinate`; fake panel via `run-instrumented.sh`, one class per call; `--synthetic 2000` for the guide classes). `range_requests_seen == 0` is asserted in every guide and M2 test's `@After` and held in every run.

| Class | Pass | Fail | Skipped / ignored | Notes |
|---|---|---|---|---|
| `HarnessTest` | 5 | 0 | 0 | |
| `StartupAndSurfaceTest` (I-1, I-2, I-3) | 3 | 0 | 0 | I-1 now from the first drawn frame: first frame 1,681 ms after the relaunch, tuned row focused at now 311 ms after it |
| `KeysOnDeviceTest` (I-4, 32 sequences) | 1 | 0 | 0 | |
| `PagingTest` (I-5) | 1 | 0 | 0 | settle 302 ms |
| `GenerationSwapTest` (I-6) | 1 | 0 | 0 | |
| `GroupsAndBackTest` (I-7, I-8) | 2 | 0 | 0 | |
| `CatchupTest` (I-9) | 1 | 0 | 0 | timeshift request in the panel zone |
| `ReminderTest` (I-10 ×3) | 3 | 0 | 0 | **new** `missedToastAfterARestart`: toast shown 1,459 ms after the restart; card 17 ms after T − 60 s |
| `SearchTest` (I-11) | 1 | 0 | 0 | |
| `RecompositionTest` (I-12) | 1 | 0 | 0 | `GuideGridHost` +0; `FocusedPanel` +71 for 72 panel slices; `MiniPlayerOverlay` +0 |
| `PrefixTest` (I-13) | 1 | 0 | 0 | |
| `NumberEntryTest` (I-14) | 1 | 0 | 0 | first attempt: the emulator process (qemu) crashed during tear-down after the body had passed; passed after a cold boot |
| `MemoryTest` (I-15) | 1 | 0 | 0 | growth run 1 → 5 −581 KiB; guide + player retained 4,090 KiB |
| `LowEndTest` (I-16) | 2 | 0 | 0 | split into `flatBackgroundStillAndFull` and `audioKeepsPlayingWithTheVideoTrackOff` (Q-1); still → FULL first rendered frame 499–725 ms after OK (3 runs); position +2.3–2.5 s per 2 s |
| `ZapThroughGuideTest` (I-17) | 1 | 0 | 0 | guide OK first frame P50 648 / P90 740 ms, resolve P50 161 / P90 207 ms |
| `LayerScopeTest` (I-18) | 1 | 0 | 0 | 201 frames: `guide-draw` 195, `guide-panel` 55, `guide-ambient` 0 (B8 holds with the `HoleChrome` layer) |
| `ModeSurvivalTest` (I-19) | 1 | 0 | 0 | |
| M2 `SmokeAndZapTest` | 7 | 0 | 0 | all four I-6 linger variants pass |
| M2 `MediaAndLifecycleTest` | 4 | 1 | 2 skipped by assumption | I-21 "picture does not change" (the known emulator flake, also at `abe7b54`/`dc88fab`); I-22, I-22b: "display has no 50 Hz mode" (`assumeTrue`) |
| M2 `ReconnectTest` | 7 | 0 | 1 ignored | I-3 `@Ignore` (M2 O-1) |
| M2 `ErrorPathTest` | 6 | 0 | 0 | |
| `:data` `I1LibraryDatabaseTest` | 4 | 0 | 0 | library.db stays v3 |

`assumeTrue` skips: only I-22 and I-22b (no 50 Hz mode on the AVD). **O-6** on a quiet host: `SmokeAndZapTest` I-6 passes in every variant and I-22/I-22b now reach their assumption (they failed the fps check before), so those were host load; I-21 remains the known emulator flake.

**Q-1 (new, found by the corrected I-16, pre-existing):** with the video track off (Still setting or low memory, viewport HIDDEN) the session rebuffers once right after first media and recovers; in one run, where the position probe followed a stats-overlay toggle, it stayed in `Buffering` for over 8 s and the position did not advance. The same probe at `dc88fab` saw the position go back 3.1 s. It is M2 playback behaviour (track disabling, load control), out of this pass's scope; it needs an M2 investigation (and an HDR/poster run, which shares the disabled-track path) before M3 sign-off.

**Q-1: fixed (2026-10-15, M2 design §5.13).** Two causes. (1) The rebuffer right after first media is the emulator's audio HAL: leaving standby it drops ~1.5 s of audio in ~2 s (`pcm_write was late reading frames, dropping 22131 us`), the AudioTrack clock runs ~1.4× and the paced stream drains; FULL with video on rebuffers the same way, and the counted rebuffer raising the shared playlist's FSM (L1, then L2 with a 10 s after-rebuffer threshold) explains the > 8 s `Buffering`. Not a player defect; the device test keeps the HAL warm. (2) The real defect: re-enabling the video track (still → FULL, poster → FULL, FULL during a rebuffer) reset the live TS period to position 0 (`ProgressiveMediaPeriod.seekToUs` on an unseekable source) because the disabled video queue had been trimmed past its last keyframe; the load was cancelled, the connection re-opened and a position read during the reset returned 0 (the "went back 3.1 s"). Fix: `AdaptiveLoadControl.retainBackBufferFromKeyframe = true`. Tests: JVM `DisabledVideoTrackTest.reEnablingTheVideoTrackContinuesFromTheBufferedKeyframe` and `loadControlRetainsTheBackBufferFromTheKeyframe` (`:player`); instrumented `app.thebox.playback.DisabledVideoTrackTest` (7 tests: hidden at tune ± stats toggle, hidden while playing → FULL, hidden → FULL, HDR poster in MINI → FULL via the fake panel's new `media_override` HDR10 clip, FULL during a rebuffer, standby grace) 5 × 7/7; the pre-fix build fails 4 of 7 (every re-enable restarts the load).

---

## O-3 / O-7 / O-4 analysis

**O-3 (guide OK resolve 0.6–2.6 s against 0.13 s for CH+ in FULL, §5.11).** Both paths run the same `tune()` cycle, so the difference is the environment. The shell review ranked main-thread starvation of the `Main.immediate` controller first (about 18 IO ↔ main hops per tune, each waiting for a busy main thread: the per-frame LIVE pulse, the 1.2 s ambient cross-fade re-recording the un-layered root, and whole-screen recomposition per `Tuned`) and `library.db` contention second (the G-S6 storm, which also happens in FULL). Fixed: the hops (one IO hop for facts + resolve), the per-frame pulse and the root re-record (H-S8), the recomposition (H-S9) and the storm (G-S6); the fake panel now serves real logos, so every guide tune no longer cross-fades to a hash hue. Results in the table: on the same boot the guide-path `resolve` fell from P50 212 / P90 251 ms to 136 / 211 ms and the guide OK first frame from 663 / 1,219 ms to 553 / 834 ms (FULL CH+ is unchanged at about 0.4 s). With `-gpu host` the original 0.6–2.6 s does not reproduce at `dc88fab` either: it was mostly SwiftShader's 134–356 ms frames multiplying the hops. **O-3: closed on the emulator**; the 1.2 s/2.0 s gate stays for hardware (M6).

**O-7 (debug cold start registers the machine 5.4–6.3 s after launch).** The time is spent before composition: process start, `Application.onCreate` (the eager reminder runtime constructed `LibraryDatabase` and started a Room query), activity injection, `controller.prewarm()` (ExoPlayer, renderers, FFmpeg JNI, MediaSession on main) and the view-model graph. Changed as above. Results: `am start -W` medians are unchanged within noise (benchmark build 758 → 751 ms, debug 2,612 → 2,570 ms): the first composition's `VideoLayer` reads `surfacePlayer`, which builds the ExoPlayer engine anyway, so moving `prewarm()` after the first frame moves no work off the first frame; the reminder book's query and runtime now start after it (no main-thread effect measurable here). **O-7 stays open:** the remaining lever is to give `VideoLayer` the surface player after the first frame (a placeholder surface for one frame), which needs its own review because it touches M2's surface wiring. The debug build stays slow mainly because it is debuggable and unoptimised (53 MB dex, no AOT); only a release-like number on hardware with the baseline profile (O-5) matters for the startup gate.

**O-4 (journey (b) "no renderthread slices").** Still fails at `dc88fab` and after the fixes, in both modes. Not investigated further in this pass; the shell review's two hypotheses stand (the measure block starts outside GRID, where a tapped DPAD_RIGHT is a no-op long-press candidate in FULL, or the key sink lost focus after `BenchSeedActivity`). Next step: a bench-only state log at the start of the measure block.

---

## Verified correct (no action)

### Engine and data half
- **Pager:** thread confinement of the reader, ref map, caches, sink and window on `box-guide-epg`; generation safety (new reader, cleared caches, re-run, close on the epg thread; patch clears the ref map); B3 fresh arrays; latest-wins coalescing with `startedSeq`; `SQLITE_BUSY` parsing and 3 × 500 ms retries keeping the snapshot; B5/S10a basis re-query only.
- **Rows cache:** 32-row blocks, ≤ 8, LRU by `ensure`, primitive maps, no boxing in `channel()`; tokens drop superseded loads; trim keeps `[top − 6, top + 12)`.
- **Layout and navigator:** geometry matches §3.1 and `01-design-reference.md`; row cells (S10b, overlaps, continuations, slivers, NO_EPG blocks); navigator moves and the Left-edge rule; `TimeTables` interned tables and DST/half-hour zones.
- **Renderer and adapters:** §3.6.1 draw order, N14, sticky titles, culling by binary search; `TextFit` (B4, S6) guards; zero-allocation draw (`RendererAllocationTest`); every adapter size is design px × scale; `GuideGridHost` is one `Spacer` with a default layer as a sibling of `VideoLayer`.
- **Search, reminders, session store:** caps, ordering, dedupe, parental predicate in SQL, `debugUnlocked` unreachable in release; reminders keyed `(playlist_id, stable_key, start_min)` raw with `shift_min` stored; session store persists source fields only.
- **Prefix rule and display names**, **migration 2 → 3** (exact columns, defaults and indexes; `Migrations.ALL` registered), and **logging** (counts, ids, timings and exception class names only).

### Shell and behaviour half
- **Root tree (B1, B8):** no layer, clip or alpha on any ancestor of `VideoLayer`; layers only on siblings and descendants of the sink (now also `HoleChrome`).
- **Nothing inside the video rect** except the allowed mini pills and gradient; overlay positions stay clear of the hole.
- **Hole publication** dedupes (no layout loop).
- **Debug-only pieces** stay out of release; x86 libraries excluded from release.
- **ExoPlayer access** only on main; catch-up launch (§3.13.6, §2.13 item 11) uses raw times and the panel zone, FULL only on the first archive `Tuned`.
- **Back ladder (B2)**, **Left-edge rule**, **gesture detection** (§3.18.1), **key map** families and `Keys` constants, **key capabilities** from `KeysSeen`.
- **Reminder runtime:** fires at the displayed time, background clears without writes, `setStateIfPending` writes; the 600 ms guard and countdown.
- **Search exits**, **error text** (exhaustive over the 25 codes), **logs and telemetry**, **toasts never take focus**, **trim** without `recycle()`.

---

## Test gaps that remain

Closed by this pass: the pager's steady hold (G-S1) and patch caches (G-S2); `TimeLabels.reset` wiring (G-S3); mixed-direction titles (G-S4); the golden tolerance (G-S5); watch writes and the guide flows (G-S6); a JVM `GuideViewModel` test and a `GuideWorld` that re-emits lineups and answers in the other order (H review gaps 1, 2); five-key effects (gap 3); `keys.csv` rows for the card in SEARCH/SETTINGS, LEFT on an empty source and Play/Pause/FF in FULL (gap 4, except EXIT in FULL, H-L4); I-1 first frame (gap 5); I-10 missed toast after a restart (gap 7); I-16 audio decoding and still → FULL timing (gap 8); HDR poster at the VM, root and controller levels (gap 9); low-end idle redraws and the mini overlay recomposition bound (gap 10); real logos in the fake panel (gap 11, first half).

Still open:
1. **Navigator in a real zone:** `GuideNavigatorTest` and the 10,000-walk property test use `labels = null` (UTC floor30); run them with `TimeTables` for Asia/Kolkata, Asia/Kathmandu and Europe/London across the 2026 DST days, plus the UP/DOWN/PAGE no-drift invariant.
2. **Migration v1 → 3 in one run** (`runMigrationsAndValidate(3, …)` from a populated v1 database).
3. **Search with shifted channels** (±60): on-now/upcoming/ended on display times and the archive edge against the grid (G-L12).
4. **Allocation outside the counting canvas:** `LogoCache` misses and `AndroidGuideCanvas` (a debug on-device `Debug.startAllocCounting` around `guide-draw`).
5. **`WindowQueryPerfTest`:** the cold guard relaxed from max to P90 by WP-B, accepted without review; record or restore a looser max.
6. **`EpgRefResolver` parity:** 10 × 100 rather than §5.2's 1,000 random fixtures (it now also covers the merged statement).
7. **`SearchScreen`:** no Compose test of result rows, the long-OK menu or the `guideDataMissing` text.
8. **I-2's Compose half** (TODO) and **I-3's** corner probes against the centre pixel; overlay bounds via semantics for every overlay.
9. **`DeviceClass` thresholds** (2 GB / 192 MB, 1.5 GB / 256 MB, 1 GB) table tests.
10. **Fake panel:** every synthetic stream serves the same clip, so per-channel fps/HDR learning and AFR are not exercised (and no HDR stream exists to exercise H-B1 on the device).
11. **H-S10 on the JVM:** the scrim timing is device-only (I-16); a Compose test would need the scrim value exposed.
13. ~~**Q-1**~~ fixed 2026-10-15 (above; M2 design §5.13): `DisabledVideoTrackTest` (JVM and instrumented) reproduces and guards it.
14. **Q-1 finding 1, emulator artifact, not a defect:** the ranchu audio HAL drops ~1.5 s of audio in the ~2 s after its output leaves standby (`pcm_write was late reading frames, dropping 22131 us`), so the AudioTrack clock runs ~1.4× and the first paced session after HAL idle rebuffers once, video on or off. `DisabledVideoTrackTest` keeps the HAL warm (`-e q1cold 1` for the control). Confirm absence on hardware (M6).
15. **Later (M2), lifecycle:** Google TV pauses the active media session at screen-off (`playWhenReady=false, USER_REQUEST` via `MediaSession` ~0.1 s after sleep), so the standby grace plays no audio; decide whether that is the desired TV behaviour.
16. **Later (M2), lifecycle:** a wake inside the grace can report "display on while not started: stop now" before the Activity's `ON_START` (E-B2 rule), so the session is stopped and re-tuned instead of continuing; order the wake handling after `ON_START` or treat a pending grace as foreground.
17. **M6 gate (Q-1 fix):** 4K HEVC long-GOP channel on a 24 MiB-class device: bounded rebuffers in FULL and the re-enable first frame vs the 1.5 s gate; remedy a GOP allowance in the cap or the no-codec renderer spike, never a revert (M2 design §5.13).
12. **G-S6's cache** relies on Room's asynchronous invalidation: a block loaded in the few ms between a playlist/source commit and the tracker's notification uses the old facts until the next reload (the `rowContent` debounce reloads after the notification).
