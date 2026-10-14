# M3 "Guide": design

Status: **proposed, 2026-10-14; reviewed 2026-10-14** (`review/m3-design-review.md`: 8 blockers B1–B8 and 16 should-fix items S1–S16 applied in place, each marked "(reviewed 2026-10-14, Bn/Sn: …)"; section F's contract text used verbatim; nice-to-have items N1–N18 applied where cheap, marked Nn). The lead commits commit 0 (§1.6) and starts the five work packages.
Inputs: `00-master-plan.md` §2–§6, §8 (M3 row); `01-design-reference.md`; `design/TV Guide.dc.html` (markup, CSS and the `Component` script); `review/architecture-review.md` B1, B2, C, D, E; `review/product-review.md` §2.1–§2.4 (S7, S7a, S7b, S8, S9, S10), §3.1–§3.10, §4.3, §4.5–§4.7, §4.12; `decisions/2026-10-14-settings-and-branding.md`; `design/m1-data-layer.md` §2.1–§2.5; `design/m2-playback-core.md` §0, §1, §2.3, §2.6, §3.11, §3.14–§3.16, §6; `review/m1-code-review.md` and `review/m2-code-review.md` "Later" lists; the code on `main` at `9a87271`. Compose, tv-material, Coil and platform API facts come from `docs/design/m3-compose-tv-api-notes.md` (cited as "API notes §n"), which landed while this was being written. What that file could not verify is marked **(verify)** here, and the WP that first uses it confirms it against the sources jar before building on it.

This document is the contract between five implementers. **Must** means a reviewer rejects code that does otherwise. A number (timeout, size, budget) is the default and lives in one named constant (Appendix B).

## Lead sign-off (2026-10-14)

Approved after the design review (`docs/review/m3-design-review.md`). All blockers and should-fix items are applied below. Lead decisions:
- **B1** One key sink at `AppRoot`, outside the screen switch, so it survives GUIDE ↔ FULL; it passes keys through in SEARCH. `KeyRouter` and `Modifier.boxKeySink` are defined once, in §2.10, and referenced elsewhere.
- **B2** Back from home: jump to now → full screen → (Back from FULL lands on home) → exit step. Back after leaving full screen goes to the exit step, never back to FULL. I-8 matches.
- **B3** Snapshots are allocated fresh off-main and never recycled while main may hold them.
- **B4** `TextFit` is keyed by a content hash of the string plus the width, never by generation-local ids.
- **B5** Row blocks reload on any change to `channel`, `channel_prefs`, `playlist`, `epg_mapping`, `epg_match`, `epg_source` (custom name and `display_name` included): table invalidation, not lineup membership.
- **B6** `searchPast` (newest first, per-text cap) is separate from the live/upcoming `search`.
- **B7** Commit 0 gets every missing contract: `SyncFeed`, `GuideDataWindowSource`, `DeviceClassSource` (`:core` interfaces, bound in `:data`/`:ui`/`:app`), `GuideDebugProbe` (owner WP-D), `GuideEvent.SearchClosed`, and `NamePrefixRules.parse` specified in §3.12 with only the declaration in the contract (no `TODO` bodies in contract files).
- **B8** A default (no-op) `graphicsLayer` on the grid host, the header/panel column and the ambient background, so a scroll frame re-records only the grid. The surface rule is about **ancestors** of `VideoLayer`; these three are siblings. WP-A confirms with a Perfetto/Compose trace in the device pass (I-18).
- **§7 items:** 1 accept (with B1). 2 accept, with S6 grapheme/RTL guards and B4 keys. 3 accept: a loaded logo replaces the name in the column; the focused panel and info surfaces always show the name; a failed or missing logo, or "Channel logos in guide" = Off, shows the name. 4 **changed**: Left opens Groups when there is no earlier watchable cell (rows without usable archive: Left from the on-now cell; archive rows: at the archive limit); the non-watchable past stays reachable by holding Left, RW and the day strip. 5 accept: foreground only, fire at the displayed time (S1), missed-reminder toast on the next start, modal card with a 600 ms input guard, UI says once that reminders need the app open. 6 accept. 7 accept with the S13 result type. 8 accept (page = 6 rows). 9 accept with S3 thresholds; "Still image" disables the video track (viewport HIDDEN → M2 `TrackController`; audio keeps decoding, the connection stays), FULL re-enables it, at the cost of a first-frame wait on the switch. 10 accept: colon without space, spaced hyphen, one fixed ISO list with alpha-2 **and** alpha-3, an exclusion list of codes that are common words (§3.12.1), group-name equality only for tags with no lower-case letter (so "Sky Sports: F1" keeps its name). 11 accept. 12 accept with a debug unlock switch; recorded for M5: "no PIN set" must not mean "hidden forever". 13 accept with B6. 14 accept. 15 accept; `01-design-reference.md` corrected (1,558 px, 8.656 px/min) and listed in commit 0. 16 accept.
- **Reviewer additions, all accepted:** reminders keyed by raw times, fired at displayed times (E18); S3 RAM thresholds with `isLowRamDevice`/`memoryClass` first and `totalMem` only as a floor, the real Fire TV Stick 4K / Onn 4K figures "to measure" (E19); modal reminder card (E20); a **More…** panel button for remotes without long-press or Menu (E21); Search and Settings rows in the Groups panel (E22); WP-C split: branding → new **WP-E** (Sonnet, small), Search screen → WP-B (E23).
- **Not verified, owners:** §8 lists them (B8 trace → WP-A; `SQLITE_BUSY` text → WP-B; `FrameTimingMetric` on API 30 → WP-D; `InvalidationTracker.createFlow` → WP-B; `LineBreak.Heading` → WP-A; `AndroidView` layer around the `SurfaceView` → WP-C).

Units: every size below is a **design px** (1 design px = window width ÷ 1920 via `BoxDesignScale`, `ui/src/main/kotlin/app/thebox/ui/theme/BoxDesignScale.kt:41-66`) unless it says "device px". Times are **epoch minutes** (`Int`) unless they say ms.

---

## 0. Key decisions at a glance

1. **No new module. Pure guide logic lives in `:core` (`app.thebox.core.guide`, `app.thebox.core.input`), Android adapters in `:ui`, data implementations in `:data`.** `:core` is already the pure-JVM home with JUnit 5, `androidx.collection` and the real `epg.db` test fixtures (`core/build.gradle.kts`, `java-test-fixtures`), the plan fixes eight modules (plan §3 "Modules"), and M2 put its pure logic in `:core` the same way (M2 §0.1). A `:guide` module would buy nothing `:core` packages do not already give.
2. **The grid is one draw node in Compose (`GuideGridHost`: a `Spacer` with `drawBehind`) that calls `GuideRenderer.draw(GuideCanvas, …)`.** `GuideRenderer` and `GuideCanvas` are `:core` interfaces in device px; `:ui` supplies `AndroidGuideCanvas` over `android.graphics.Canvas` (`drawIntoCanvas { it.nativeCanvas }`). The renderer is JVM-tested against a recording canvas and a fake text measurer; the View-host fallback (architecture review B2.9 step 3) is the same adapter inside a custom `View`.
3. **The guide screen is virtual-focus only.** (reviewed 2026-10-14, B1: the single focus target is a key sink at `AppRoot` that wraps every screen and survives mode changes, §2.10, §3.18.1.) It is a single Compose focus target. Tabs, chips, panel buttons, the Groups panel, menus, sheets and the day strip are drawn from state, and a pure `GuideStateMachine` (`:core`) maps `BoxAction`s to state. The search text field is the only place the guide uses real Compose focus (the IME needs it). Why: every key table in §3.4 becomes a JVM test, the five-key rule is checkable by enumeration, and tv-material never decides where focus goes: its focusable components scale to 1.1, draw their own borders and apply `graphicsLayer { clip; alpha }` (API notes §2.5), none of which matches the design, and a node that returns `true` from `onPreviewKeyEvent` stops Compose's own traversal for that key (API notes §2.1, §2.4).
4. **Focus = `(row, cellStart, cellEnd, anchorMin)` plus a window `(windowStartMin, topRow)`.** Left/Right/FF/RW/day/now set the anchor; Up/Down/page keep it and pick the cell containing it (plan §4). The window start is always a multiple of 30 min; a horizontal move that leaves the visible span pages to `floor30(cell.start) − 30`. Window start is clamped to `[floor30(earliest), latest − 180]`.
5. **Rows are paged, programmes are windowed, nothing is loaded whole.** A lineup is a `LongArray` of `channel.id` values (process-local, never persisted; 20k rows = 160 KB). Channel details load in 32-row blocks (LRU of 8 blocks). Programmes load for rows `[top − 6, top + 12)` × minutes `[windowStart − 180, windowStart + 360)` through one `EpgReader` owned by one thread (`box-guide-epg`), into immutable `GuideWindowData` snapshots allocated fresh for each publication (reviewed 2026-10-14, B3). Loaded row blocks reload in place when their content changes (B5). A new query is issued when the visible rows or minutes come within 2 rows or 60 min of the edge of the loaded range.
6. **Generation safety.** `epg_channel` ints and the reader never leave the `box-guide-epg` thread. Rows carry `EpgChannelRef` (stable). On a generation change the pager opens a new reader, re-resolves, re-queries, publishes, then closes the old reader **on the same thread** (fixes the UI-thread delete in M1 Later 11). On a patch it re-resolves and re-queries (M1 §2.5.3 S13). `SQLITE_BUSY` keeps the old snapshot and retries (M1 Later 8).
7. **Text is single-line `android.graphics.Paint` text with the bundled typefaces.** Title widths are measured once per `textId` off the main thread when a snapshot is built. Overflow is a real ellipsis computed with `Paint.breakText` and drawn as `drawText(text, 0, n, …)` + `"…"`, cached per `(content hash of the string, availablePx)` (reviewed 2026-10-14, B4), backed off to a grapheme boundary, RTL-aware (S6). Times come from 1,440-entry pre-built tables. `Paint.setFontFeatureSettings("tnum")` and `setLetterSpacing` keep the design's tabular digits and tracking, which API notes §1.3 lists as the only thing the native path would otherwise lose; Compose's `TextMeasurer` was rejected for the hot path because its draw overloads allocate per call and its 8-entry LRU misses every frame (API notes §1.3). Why not B2.4's clip-and-fade: the cells are translucent over a moving ambient field and an archive band, so no single fade colour matches. `breakText` and the ranged `drawText` allocate nothing.
8. **Logos: Coil 3 `ImageLoader.execute` at the exact slot size into our own LRU (160 entries, 48 in low-end mode).** When a logo loads it replaces the channel name in the 200 px column (the number stays); the focused-programme panel and every other surface always show the name; a failed or missing logo, or "Channel logos in guide" = Off, shows the name. Failures are cached for the session. Coil's own memory cache is bypassed for logos (reviewed 2026-10-14, S8).
9. **Search over streaming data.** Channels: the existing title-only `channel_fts` (raw name, prefix terms) through a new `GuideSearch` in `:data`, shown with `display_name`. Programmes: `EpgReader.search` (on now and upcoming, per-text cap) plus a separate `EpgReader.searchPast` (newest first, per-text cap) for catch-up hits (reviewed 2026-10-14, B6), both with CONTINUATION rows filtered (M1 Later 10); every hit is mapped back to library channels with the same resolution rule as the guide, the parental predicate is applied in SQL, and hits outside the channel's archive are dropped. Ranking is fixed in §3.10.
10. **Reminders fire in-process while the app is in the foreground. No `AlarmManager`, no WorkManager, no notification.** A `ReminderScheduler` runs while `ProcessLifecycleOwner` is STARTED, fires a modal card at the **displayed** start − `lead_sec` (60 s) (reviewed 2026-10-14, S1, S2), and catches up missed reminders on the next start (late card if the programme is still on, else a "missed" toast). The first "Remind me" shows "Reminders work while The Box is open." Why: plan §2 and product §3.6 say foreground only; live TV itself stops at Home (plan §3 "Process"); exact alarms need `SCHEDULE_EXACT_ALARM` on API 31+ and a background activity start would be blocked anyway.
11. **Low-end mode is one switch** (`PerformanceMode` Auto/High/Low; Auto → Low when `ActivityManager.isLowRamDevice()` or `memoryClass ≤ 192`, with `totalMem < 1.75 GiB` only as a floor; reviewed 2026-10-14, S3: MemTotal runs 5–10 % under nominal RAM): no ambient blobs (flat `#0A0D13`), logos capped at 48, scroll snaps instead of animating, no 150 ms cell transitions, no description prefetch. The mini player switches to a still only when the user picks "Mini player: Still image" or `totalMem < 1.25 GiB` (nominal 1 GB class, product §3.8). A still sets viewport `HIDDEN`, which disables the video track through M2's `TrackController` (audio keeps decoding, the connection stays); FULL re-enables it and waits for the next keyframe behind the scrim (§3.13.5).
12. **`channel.display_name` is computed in the M1 merge from a per-playlist rule and stored; NULL means "same as `name`".** The rule (`ChannelNamePrefix`, pure, `:core`) is the decision note's Auto rule (§3.12), with ISO alpha-2 and alpha-3 codes, a short exclusion list of codes that are common words, and group equality only for tags without lower-case letters (reviewed 2026-10-14, S5). Readers use `COALESCE(channel_prefs.custom_name, channel.display_name, channel.name)`. A rule change or a rule-version bump runs one recompute pass per playlist under `ImportMutex`. Stable keys, matcher input and `channel_fts` keep the raw name.
13. **Catch-up in M3 is one request, no probing.** OK on a past cell in the archive (or "From start") asks a new public `:player` seam `ArchiveTargets.catchupTarget(identity)`, builds one `ArchiveRequest` with M1's `CatchupUrlBuilder` and the playlist's `TimeBase`, and calls `PlaybackController.tuneArchive`. Variant probing, the scrub bar, auto-continue and jump-to-live are M4 (`ArchivePlayback`).
14. **Hole punch:** root `Box { AmbientBackground ; VideoLayer ; HoleChrome ; KeySink { screen } }`, no `graphicsLayer`/clip/alpha on any **ancestor** of `VideoLayer`. Siblings may carry a default layer, and the grid host, the header/panel column and the ambient background do (reviewed 2026-10-14, B8). The `SurfaceView` clears its own rect; `HoleChrome` is drawn after it and paints the rounded corners (the ambient bitmap clipped to the four corner regions), the 1 px ring, and opaque black outside `videoRect`. The shadow lies outside the hole and is a pre-rendered bitmap drawn by `AmbientBackground`.
15. **Ambient:** a 480×270 bitmap per hue (radial gradients approximating the two blurred blobs, B1.4), drawn scaled, cross-faded over 1.2 s. The hue comes from the tuned channel's logo (our own 16×16 hue extractor, no Palette dependency), is cached in memory, and falls back to a hash of the stable key. It is not persisted (a deviation from B1.4, §7 Q6).
16. **`:ui` gets Hilt and `lifecycle-viewmodel-compose`, never `:data`.** Feeds are `:core` interfaces; `:data` binds them, including the sync chip, the EPG retention window and the device class (reviewed 2026-10-14, B7: `GuideEnvironment.kt`). `GuideViewModel` (`:ui`) is glue: it owns coroutine scopes, maps `PlaybackState` (a `:player` type) to the `:core` `TunedState`, and implements the state machine's effects.

---

## 1. Work packages, files and ownership

### 1.1 Rules of engagement

Copied from M2 §1.1:

- **Commit 0 (lead, before any WP starts):** everything in §2 marked "commit 0", the `library.db` v3 migration (§2.9), the `:player` seam (§2.8), the build changes (§6), and a stub (`TODO("WP-x")` body) for every implementation class named in §1.2–§1.5 at its final path. After commit 0, `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug` is green.
- **No WP edits a file owned by another WP, a contract file or a build file.** Contract problems go to the lead, who amends commit 0 for everyone (additive, a default for every new parameter).
- **Packages are disjoint** (tables below). Tests live in each WP's own test packages. Paths abbreviate `core/src/main/kotlin/app/thebox/core` as `core/...`, likewise `ui/...`, `data/...`, `app/...`.
- **Hilt:** each WP owns the Hilt module that binds its implementations (listed in its table); commit 0 creates every module with the stubs bound, so `:app` compiles from day one.
- **Fakes:** each contract in §2 ships a `Fake…` in `core/src/testFixtures/kotlin/app/thebox/core/guide/fakes/` in commit 0 (lead), so every WP can test against the others before they land.
- **Models:** Opus for WP-A to WP-D (layout/rendering, data paging, shell, focus/state and integration). WP-E (branding assets) runs on Sonnet under §3.17 (reviewed 2026-10-14, S15). Mechanical parts of the others (string resources, key-code tables) may be delegated to Sonnet under this spec.

### 1.2 WP-A: grid engine (geometry, cells, navigator, renderer, Android drawing adapters)

Scope: everything drawn inside the grid panel, the maths that decides where focus goes inside the grid, and the adapters that put it on an Android canvas.

| File | Contents |
|---|---|
| `core/.../guide/layout/GuideMetricsImpl.kt` | `GuideMetrics` factory: design px → device px, pixel-snapped (§3.1) |
| `core/.../guide/layout/RowCellsBuilderImpl.kt` | Builds a row's cells from `EpgWindow` rows: programmes, hour-aligned gap cells, slivers, NO_EPG rows (§3.2) |
| `core/.../guide/layout/GuideNavigatorImpl.kt` | Every grid move of §3.4.2, anchor and snap rules (§3.3) |
| `core/.../guide/layout/TimeTables.kt` | `TimeLabels` impl: 1,440 `HH:mm` strings (24 h and 12 h), weekday/day labels, contextual ruler label (§3.5) |
| `core/.../guide/render/GuideRendererImpl.kt` | Draw order, cell states, archive band, now line, skeletons, bubble (§3.6, §4) |
| `core/.../guide/render/TextFit.kt` | Ellipsis cache `(textId, availPx) → chars` (§3.6.4) |
| `ui/.../guide/grid/AndroidGuideCanvas.kt` | `GuideCanvas` over `android.graphics.Canvas`, preallocated `Paint`s, `RectF` |
| `ui/.../guide/grid/AndroidTextMeasurer.kt` | `GuideTextMeasurer` with `Paint` + `ResourcesCompat.getFont` typefaces; one instance per thread |
| `ui/.../guide/grid/GuideAssets.kt` | Pre-rendered bitmaps: focus shadow, now-line glow, ↺ glyph (solid/hollow), lock glyph, skeleton bar |
| `ui/.../guide/grid/LogoCache.kt` | `GuideImages` impl over Coil 3 (§3.7) |
| `ui/.../guide/grid/GuideGridHost.kt` | The one draw node: `Spacer` sized to the panel rect with a default `graphicsLayer()` and `drawBehind { … }` (reviewed 2026-10-14, B8, N4), scroll `Animatable`s, a11y semantics (§3.6.6). No focus or key modifiers (B1) |
| `ui/.../guide/grid/GuideTrim.kt` | `onTrimMemory` handling for the grid caches (§4.7) |
| `ui/.../guide/grid/GuideGridModule.kt` | Hilt bindings for `GuideNavigator`, `GuideRenderer`, `TimeLabels`, `GuideMetricsFactory`, `RowCellsBuilder`, `GuideImages` |
| `ui/.../guide/grid/GuideGridView.kt` | The View-host fallback (`android.view.View.onDraw` calling the same renderer), compiled and unit-tested, not wired (B2.9) |

Implements: `GuideMetrics`, `RowCellsBuilder`, `GuideNavigator`, `TimeLabels`, `GuideRenderer`, `GuideCanvas`, `GuideTextMeasurer`, `GuideImages` (§2.2–§2.3).
Tests (§5.1): geometry table, cells table, navigator table (every row of §3.4.2), renderer recording-canvas tests, allocation test, text-fit tests (B4, S6), Robolectric `AndroidGuideCanvas` smoke; in the device pass, the layer trace I-18 (B8).
Hand-off notes: measured draw-op counts for the §4.1 reference frame; JVM render time for 1,000 frames; anything in §3.6 that could not be pixel-matched to the prototype.

### 1.3 WP-B: data feed (`:data` additions, pager, search and its screen, reminders store, prefix rule)

Scope: everything that reads `library.db`/`epg.db` for the guide, the `display_name` rule end to end, the reminder store, and the Search screen (reviewed 2026-10-14, S15: moved from WP-C; WP-B owns `GuideSearch` and its ranking).

| File | Contents |
|---|---|
| `core/.../text/ChannelNamePrefixImpl.kt` | The Auto/Off/Custom rule (§3.12) with a fixed ISO-3166 list |
| `core/.../guide/feed/GuideRowsCacheImpl.kt` | 32-row blocks, LRU 8, `GuideRows` impl (§3.2.1) |
| `core/.../guide/feed/GuideWindowPagerImpl.kt` | Coverage, prefetch, `box-guide-epg` thread, reader/generation lifecycle, snapshots, detail LRU (§3.2) |
| `core/.../epg/store/EpgReaderImpl.kt`, `FtsSupport.kt` (edits) | `search(…, pastFromMin)` and the CONTINUATION filter (§2.7) |
| `data/.../guide/GuideLibraryImpl.kt` | `GuideLibrary` over Room: sources, lineups, blocks, number lookup, favourites/hide/group actions (§3.8) |
| `data/.../guide/GuideQueries.kt` | Raw-statement queries (`useReaderConnection`) that fill `LongArray`s and block rows without Room entity mapping |
| `data/.../guide/EpgRefResolver.kt` | The M1 §2.2 resolution rule for a set of identities (shared with `EpgRepository.resolvedRefs`, which is refactored to call it) |
| `data/.../guide/GuideSearchImpl.kt` | `GuideSearch` (§3.10) |
| `ui/.../search/SearchScreen.kt`, `ui/.../search/SearchViewModel.kt` | S9 screen and its view model (§3.10.3) |
| `data/.../guide/GuideEnvironmentImpl.kt` | `SyncFeed` over `SyncStatusRepository`, `GuideDataWindowSource` over `DataSettings` (reviewed 2026-10-14, B7) |
| `data/.../guide/DisplayNames.kt` | Recompute pass + startup backfill (§3.12.3) |
| `data/.../guide/GuideSessionStoreImpl.kt` | Last source and first-run flags (SharedPreferences `guide_session`, §3.8.4) |
| `data/.../guide/InMemoryGuideSettings.kt` | `GuideSettingsSource` (in memory, like `InMemoryDataSettings`; M5 persists) |
| `data/.../reminder/ReminderBookImpl.kt` | `ReminderBook` over `ReminderDao` (§3.11.1) |
| `data/.../repo/RoomMergeSink.kt`, `RefreshRepository.kt`, `EpgRepository.kt`, `ChannelRepository.kt`, `ChannelDao.kt`, `FavouriteDao.kt`, `GroupDao.kt`, `ReminderDao.kt`, `ChannelPrefsDao.kt` (edits) | `display_name` bind, rule plumbing, read-side `COALESCE`, new DAO methods (§2.9.3) |
| `data/.../di/GuideDataModule.kt` | Hilt bindings for `GuideLibrary`, `GuideSearch`, `GuideRowsCacheFactory`, `GuideWindowPagerFactory`, `ReminderBook`, `GuideSessionStore`, `GuideSettingsSource`, `ChannelNamePrefix`, `SyncFeed`, `GuideDataWindowSource` |

Implements: `ChannelNamePrefix` (+ `NamePrefixRules.parse`), `GuideRowsCache`, `GuideWindowPager`, `GuideLibrary`, `GuideSearch`, `ReminderBook`, `GuideSessionStore`, `GuideSettingsSource`, `SyncFeed`, `GuideDataWindowSource`, the Search screen (§2.4–§2.7, §2.9.2).
Tests (§5.2): prefix table (every example in §3.12), merge/recompute Robolectric tests (incl. S4 upgrade order), row-content reload (B5), `searchPast` (B6), `isBusy` (S7), `SearchViewModelTest`, pager tests against the real `EpgStoreImpl` (testFixtures `EpgTestDb`) including a generation swap mid-scroll and a patch, block/lineup query tests, search ranking table, reminder book tests, the JVM window-query perf test.
Hand-off notes: query plans (`EXPLAIN QUERY PLAN`) for the lineup and block queries on a 20k-channel library; window query P50/P90 from the perf test.

### 1.4 WP-C: shell (Compose screens, overlays, hole, ambient, view models, MainActivity)

Scope: everything on screen except the inside of the grid, the Search screen (WP-B) and the branding assets (WP-E), and the glue that wires the state machine to Android. (reviewed 2026-10-14, S15: split.)

| File | Contents |
|---|---|
| `ui/.../root/AppRoot.kt` | The root tree (§3.13.1), the key-sink `Box` that wraps the screen switch (applies WP-D's `Modifier.boxKeySink`, B1), scrim |
| `ui/.../root/HoleChrome.kt`, `ui/.../root/HoleGeometry.kt` | Corner masks, ring, letterbox (§3.13) |
| `ui/.../ambient/AmbientBackground.kt`, `AmbientRenderer.kt`, `HueExtractor.kt`, `Oklch.kt` | Cached ambient bitmap, cross-fade, hue, default `graphicsLayer()` on the node (B8) (§3.14) |
| `ui/.../guide/screen/GuideScreen.kt` | Layout of header, panel, grid host slot, footer; default `graphicsLayer()` on the header/panel column (B8) (§3.15) |
| `ui/.../guide/screen/MiniPlayerOverlay.kt` | Pills, bottom panel, still, compact error card (§3.13.4) |
| `ui/.../guide/screen/FocusedPanel.kt`, `HeaderTabs.kt`, `GridHeaderChips.kt`, `FooterHints.kt` | Drawn from `GuideUiState` |
| `ui/.../guide/overlay/GroupsPanel.kt`, `DayStrip.kt`, `ContextMenu.kt`, `ProgrammeSheet.kt`, `ExitHint.kt`, `EmptyState.kt`, `SyncChip.kt` | Overlays (§3.15, §3.4.4) |
| `ui/.../guide/vm/GuideViewModel.kt`, `GuideEffectsImpl.kt`, `TunedStateMapper.kt`, `LineupZapList.kt` | Glue (§3.16) |
| `ui/.../full/FullScreenHost.kt` | Minimal M3 full screen (§3.13.5) |
| `ui/.../reminder/ReminderCard.kt` | Card UI (logic is WP-D's) |
| `ui/.../playback/ErrorText.kt` + `ui/src/main/res/values/strings_errors.xml` | Headline and next-step line for every `PlaybackErrorCode` (§3.13.4) |
| `ui/src/main/res/values/strings_guide.xml` | Every guide string |
| `app/.../MainActivity.kt` (rewrite), `app/.../image/ImageModule.kt` | Root wiring, `prewarm()` (M2 E-L17), Coil `ImageLoader` (2 MB / 1 MB memory cache, S8), app-level `onTrimMemory` fan-out (§4.7) |

Implements: the composables, `GuideEffects` (§2.5).
Tests (§5.3): Robolectric Compose tests for the root tree rule (no `graphicsLayer` on the surface's **ancestors**; a default layer **on** the grid host, B8), `LineupZapListTest` (S9), `HoleGeometry` table, `Oklch` table, `HueExtractor` table, `TunedStateMapper` table, `ErrorText` completeness, ambient bitmap determinism.
Hand-off notes: screenshots of every overlay on the emulator (manual), any `(verify)` API that behaved differently.

### 1.5 WP-D: behaviour and verification (state machine, input, number entry, reminders runtime, catch-up launch, harness, tests, benchmarks)

Scope: what a key press does, everything that fires on time, and proving the whole milestone on the device.

| File | Contents |
|---|---|
| `core/.../guide/state/GuideStateMachineImpl.kt` | Zones, every key table of §3.4, back ladder, panel debounce, data events (§3.4, §3.9) |
| `core/.../guide/state/NumberEntryImpl.kt` | Digits, auto-commit, "No channel N" (§3.9.4) |
| `core/.../guide/state/GuideLimits.kt` | Earliest/latest browsable minute, day strip model (§3.3.4, §3.5) |
| `core/.../input/KeyGestureDetectorImpl.kt`, `DefaultKeyMap.kt` | Short/long/repeat, default bindings (§3.18) |
| `core/.../guide/reminder/ReminderPlannerImpl.kt` | Pure due/next computation (§3.11.2) |
| `app/src/main/kotlin/app/thebox/reminder/ReminderSchedulerImpl.kt` | Foreground runner + `ReminderAlerts` (§3.11.3); in `:app` because it needs `ProcessLifecycleOwner` (`androidx.lifecycle.process`, already an `:app` dependency) |
| `ui/.../input/KeyCapabilitiesImpl.kt`, `ui/.../input/KeyRouterImpl.kt`, `ui/.../input/BoxKeySink.kt` | Seen-keys tracking; the one key path `KeyEvent` → `KeyInput` → gesture → action (§2.10, §3.18.1); `Modifier.boxKeySink` (B1) |
| `ui/.../input/DeviceClassSourceImpl.kt` | `DeviceClassSource` from `ActivityManager` (B7) |
| `ui/.../guide/GuideDebugProbeImpl.kt` (`src/debug`) + no-op in `src/release` | `GuideDebugProbe` (B7) |
| `ui/.../input/InputModule.kt`, `app/.../reminder/ReminderModule.kt` | Hilt bindings for `KeyMap`, `KeyGestureDetector`, `KeyCapabilities`, `KeyRouter`, `DeviceClassSource`, `GuideDebugProbe`, `GuideStateMachineFactory`, `ReminderPlanner`; `ReminderAlerts`, `CatchupLauncher` |
| `ui/.../guide/overlay/NumberEntryOverlay.kt` | Digits UI |
| `app/.../playback/CatchupLauncherImpl.kt` | §3.13.6 |
| `app/src/debug/kotlin/app/thebox/debug/GuideDebugReceiver.kt` + manifest entries | `thebox-debug://guide?...` seeding and switches (§5.6) |
| `app/src/benchmark/kotlin/app/thebox/bench/BenchSeedActivity.kt` + manifest | Seeding for the benchmark build (§5.7) |
| `tools/fakepanel/catalog.py`, `fakepanel.py` (edits), `README.md` | `--synthetic N` channels/groups/EPG (§5.6) |
| `app/src/androidTest/kotlin/app/thebox/guide/*` | Instrumented suite I-1…I-16 (§5.5) |
| `benchmark/src/main/kotlin/app/thebox/benchmark/GuideScrollBenchmark.kt`, `baselineprofile/.../BaselineProfileGenerator.kt` (edit) | §5.7 |

Implements: `GuideStateMachine`, `NumberEntry`, `KeyGestureDetector`, `KeyMap`, `KeyCapabilities`, `KeyRouter`, `DeviceClassSource`, `GuideDebugProbe`, `ReminderPlanner`, `ReminderAlerts`, `CatchupLauncher`.
Tests (§5.4–§5.7): state-machine key tables per zone (generated from §3.4), five-key reachability test, back ladder, number entry, gesture detector timing table, reminder planner table, scheduler test with a fake clock; then the instrumented suite and benchmarks after integration.
Hand-off notes: the integration results subsection (§5.8), benchmark numbers, open device issues.

### 1.5a WP-E: branding assets (Sonnet, small; reviewed 2026-10-14, S15)

| File | Contents |
|---|---|
| `tools/branding/make-assets.sh` (+ its small Python step) | Trace and render pipeline (§3.17) |
| `ui/src/main/res/drawable/wordmark.xml` | White wordmark vector |
| `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/mipmap-*/ic_launcher.png`, `app/src/main/res/drawable-xhdpi/banner.png` | Adaptive icon, API 25 icons, TV banner |
| `app/src/main/AndroidManifest.xml` | only the `android:icon` attribute (lead hands this one line to WP-E) |

Tests: none automated; hand-off screenshots of the banner, the icon on the launcher (API 30 emulator) and the wordmark at 1080p and 720p.

### 1.6 Commit-0 list (lead)

1. **Contracts** §2.1–§2.8 and §2.10, verbatim, plus the `Fake…` implementations in `core/src/testFixtures`. Files: `core/.../guide/GuideModel.kt`, `GuideLayoutContracts.kt`, `GuideRenderContracts.kt`, `GuideFeedContracts.kt`, `GuideEnvironment.kt` (B7), `GuideStateContracts.kt`, `ReminderContracts.kt`, `GuideSearchContracts.kt`; `core/.../input/InputContracts.kt`; `core/.../text/ChannelNamePrefix.kt` (declarations only, no bodies: B7); the `EpgReader` amendment in `core/.../epg/EpgContracts.kt` (F2, B6); `ui/.../input/KeyRouter.kt` (the `KeyRouter` interface and the `boxKeySink` signature, B1); `ui/.../playback/CatchupLauncher.kt`.
2. **`library.db` v3** (§2.9): entity fields (incl. `reminder.shift_min`, S1), `@AutoMigration(from = 2, to = 3)`, exported `3.json`, the migration tests in `:data`.
3. **`:player` seam** (§2.8): `ArchiveTargets` with `CatchupTargetResult` (S13) + its binding in `PlaybackModule` (about 15 lines over the internal resolver).
4. **Build** (§6): catalog entries, `:ui` Hilt plugin and dependencies, `:app` benchmark source set, androidTest dependencies.
5. **Stubs** for every implementation class in §1.2–§1.5a, bound in Hilt (`GuideDataModule` binds WP-B stubs; `ImageModule` is a stub returning a default `ImageLoader`).
6. **`DesignTokens` cross-check:** `GuideColors` (§2.1) and `DesignTokens.Colors` (`ui/.../theme/DesignTokens.kt:19-46`) must agree; commit 0 adds a unit test in `:ui` that compares them.
7. **Design reference fix** (applied with this review pass, 2026-10-14; the lead commits it with commit 0): `01-design-reference.md` line "1656 px of grid width … ≈ 8.09 px/min" was wrong; the prototype gives 1792 − 2×1 (border) − 2×16 (padding) − 200 = **1558 px for 180 min = 8.656 px/min**; architecture review B2.1's 1560 forgot the border (Appendix A.1). Correct it in commit 0.

### 1.7 Integration sequence

1. WP-A, WP-B, WP-D and WP-E build in parallel against the commit-0 fakes. WP-C builds in parallel against fakes of the state machine and feeds.
2. Merge order: **A, B, D, E, C** (reviewed 2026-10-14, S15). D rebases on A (the state machine's tests switch from `FakeNavigator` to `GuideNavigatorImpl`, both must pass) and on B (number lookup, reminder book). E touches only resources and `tools/branding`. C rebases last and replaces fakes with real bindings.
3. WP-D runs the integration pass: real bindings end to end on `TheBox_TV_API30` against the fake panel with `--synthetic 2000`, the instrumented suite (§5.5), the benchmarks (§5.7). Results go into §5.8.
4. Code review pass (CLAUDE.md), then the gates of §5.9.

---
## 2. Contracts

All files in this section are commit 0 (lead). Kotlin is normative for names, types and KDoc'd semantics; bodies of `object`s marked "values" are normative too. Package root `app.thebox.core` is abbreviated `core`.

### 2.1 `core/.../guide/GuideModel.kt`: geometry, colours, data snapshots

```kotlin
package app.thebox.core.guide

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.refresh.ChannelIdentity

/** Design-px geometry (docs/01-design-reference.md, prototype markup; Appendix A.1). Values are normative. */
object GuideGeometry {
    const val CANVAS_W = 1920f
    const val CANVAS_H = 1080f
    // Mini player hole (prototype: left 64, top 56, 720×405, radius 20)
    const val HOLE_L = 64f; const val HOLE_T = 56f; const val HOLE_W = 720f; const val HOLE_H = 405f; const val HOLE_R = 20f
    // Header region right of the hole (left 832, top 56, right 64, height 405)
    const val HEAD_L = 832f; const val HEAD_T = 56f; const val HEAD_R = 64f; const val HEAD_H = 405f
    // Grid panel (left 64, right 64, top 500, height 460, radius 24, padding 0 16 12, 1 px border)
    const val PANEL_L = 64f; const val PANEL_T = 500f; const val PANEL_W = 1792f; const val PANEL_H = 460f; const val PANEL_R = 24f
    const val PANEL_PAD_X = 16f; const val PANEL_PAD_B = 12f; const val PANEL_BORDER = 1f
    const val HEADER_H = 48f
    const val LABEL_W = 200f
    const val LABEL_PAD_X = 12f; const val LABEL_GAP = 14f
    const val ROW_H = 60f; const val ROW_GAP = 8f; const val ROW_PITCH = 68f
    const val VISIBLE_ROWS = 6
    const val WINDOW_MIN = 180
    const val TICK_MIN = 30
    const val TIMELINE_W = 1558f                        // 1792 − 2×1 border − 2×16 padding − 200 (Appendix A.1)
    const val PX_PER_MIN = TIMELINE_W / WINDOW_MIN      // 8.6556
    const val TICK_LABEL_PAD = 10f
    // Cells
    const val CELL_INSET_X = 3f; const val CELL_R = 12f; const val CELL_PAD_X = 16f; const val CELL_PAD_X_NARROW = 8f
    const val CELL_LINE_GAP = 3f; const val CELL_BORDER = 1f; const val PROGRESS_H = 3f; const val FOCUS_RING = 3f
    /** Below this outer width a cell draws its title only (no time line) with [CELL_PAD_X_NARROW]. */
    const val NARROW_CELL_W = 120f
    /** Below this inner width a cell draws no text at all. */
    const val MIN_TEXT_W = 24f
    // Now line
    const val NOW_W = 2f; const val NOW_DOT = 10f; const val NOW_OVERHANG = 6f; const val NOW_GLOW = 12f
    // Footer
    const val FOOTER_L = 64f; const val FOOTER_B = 40f
}

/** ARGB values. oklch tokens use their sRGB fallbacks (DesignTokens.kt). Commit 0 tests equality with DesignTokens.Colors. */
object GuideColors {
    const val CANVAS = 0xFF0A0D13.toInt()
    const val FG = 0xFFEEF1F6.toInt()
    const val ACCENT = 0xFF5CA8F2.toInt()
    const val ACCENT_TEXT = 0xFFB6D4F7.toInt()
    const val LIVE = 0xFFFF4D5E.toInt()
    const val WARN = 0xFFFFB547.toInt()                 // amber dot of the compact error card (product §2.4)
    const val PANEL_FILL = 0x0BFFFFFF                   // .045
    const val PANEL_BORDER = 0x17FFFFFF                 // .09
    const val ROW_FOCUS_FILL = 0x14FFFFFF               // .08
    const val CELL_FILL = 0x0DFFFFFF                    // .05
    const val CELL_BORDER = 0x14FFFFFF                  // .08
    const val CELL_NOW_FILL = 0x1CFFFFFF                // .11
    const val CELL_PROGRESS = 0x99EEF1F6.toInt()        // .6
    const val ARCHIVE_BAND = 0x08FFFFFF                 // .03 (B2.6)
    const val TICK_RULE = 0x1AFFFFFF                    // .1
    const val TEXT_TICK = 0x8CEEF1F6.toInt()            // .55
    const val TEXT_LABEL = 0x80EEF1F6.toInt()           // .5
    const val TEXT_PAST = 0x73EEF1F6.toInt()            // .45 past, not watchable
    const val TEXT_ARCHIVE = 0xD9EEF1F6.toInt()         // .85 past, in archive (product §3.5)
    const val TEXT_TIME_ALPHA = 0.6f                    // cell time line opacity
    const val SKELETON = 0x0FFFFFFF                     // .06
    const val SHADOW = 0x66000000                       // focused cell 0 12 30 rgba(0,0,0,.4)
}

/** Single-line styles drawn by the renderer. Sizes in design px; weights as in docs/01-design-reference.md. */
enum class GuideTextStyle(val sizePx: Float, val weight: Int, val mono: Boolean, val letterSpacingEm: Float = 0f) {
    CELL_TITLE(18f, 600, false),
    CELL_TIME(13f, 400, true),
    RULER(15f, 400, true),
    RULER_CONTEXT(15f, 600, false, 0.1f),
    CH_NUMBER(15f, 400, true),
    CH_NAME(19f, 600, false),
    NO_INFO(16f, 500, false),
    BUBBLE(64f, 500, true),
}

/** Cell kinds in a [GuideWindowData]. */
object CellKind {
    const val PROGRAMME: Byte = 0
    /** "No information" inside a row that has EPG (a gap between programmes, or before/after its data). */
    const val GAP: Byte = 1
    /** "No information" block of a row with no EPG channel or no programmes in this generation. */
    const val NO_EPG: Byte = 2
    /** Synthesised by the navigator/renderer outside the loaded range; never stored. */
    const val LOADING: Byte = 3
}

/** Row status inside a [GuideWindowData]. */
object RowStatus {
    const val OK: Byte = 0
    const val NO_EPG: Byte = 1
    /** The row's channel block was not loaded when the snapshot was built. */
    const val PENDING: Byte = 2
}

/**
 * One row of the guide. [rowId] is `channel.id`: process-local, used only to page blocks and to find the tuned
 * row; it is never persisted and never crosses a library refresh in a lineup (lineups are rebuilt on refresh).
 */
data class GuideChannel(
    val rowId: Long,
    val identity: ChannelIdentity,
    /** COALESCE(custom_name, display_name, name). */
    val displayName: String,
    val number: Int?,
    /** custom_logo ?: logo_url. */
    val logoUrl: String?,
    /** Catchup.days when Catchup.isAvailable, else 0 (resolver chain, M1 §3.8). */
    val archiveDays: Int,
    /** Resolved EPG reference (M1 §2.2); null = no guide data. */
    val epg: EpgChannelRef?,
    /** Total guide shift in minutes (M1 §2.2). */
    val epgShiftMin: Int,
    val epgHasData: Boolean,
    /** [GuideChannel.hashOf] of [identity]; the renderer's key for tuned/reminder/suspect lookups. */
    val identityHash: Long,
) {
    companion object {
        fun hashOf(identity: ChannelIdentity): Long =
            app.thebox.core.common.Hash64.str(app.thebox.core.common.Hash64.long(app.thebox.core.common.Hash64.of(null), identity.playlistId), identity.stableKey.value)
    }
}

/**
 * Immutable programme snapshot for rows [firstRow, firstRow + rowCount) of one lineup and display minutes
 * [fromMin, toMin). Built on `box-guide-epg`, published to main, never mutated after publication.
 * Cells of a row are contiguous, sorted by start, non-overlapping, and tile [fromMin, toMin) exactly
 * (PROGRAMME, GAP or NO_EPG). A PROGRAMME may extend beyond either edge.
 */
class GuideWindowData(
    val seq: Long,
    val lineupVersion: Long,
    /** epg generation, or -1 when there is no guide data at all. */
    val generation: Long,
    val patchSeq: Int,
    val firstRow: Int,
    val rowCount: Int,
    val fromMin: Int,
    val toMin: Int,
    val rowStatus: ByteArray,
    val rowFirstCell: IntArray,
    val rowCellCount: IntArray,
    val cellStart: IntArray,
    val cellEnd: IntArray,
    /** Raw (unshifted) start: the catch-up and reminder key. Equals [cellStart] for GAP/NO_EPG. */
    val cellRawStart: IntArray,
    val cellKind: ByteArray,
    /** ProgrammeFlags (EpgContracts.kt:161-179). */
    val cellFlags: IntArray,
    /** epg_text id, generation-local: used only as a text-cache key, never persisted. −1 for non-programmes. */
    val cellTextId: IntArray,
    val cellTitle: Array<String?>,
    /** Width of the full title in device px at [metricsScale], measured off-main; −1 = not measured. */
    val cellTitleWidth: FloatArray,
    /** (reviewed 2026-10-14, B4) Hash64.of(title), off-main: the TextFit key (never the generation-local textId). */
    val cellTitleKey: LongArray,
    /** "19:30 – 20:00" built off-main from TimeLabels (null for GAP/NO_EPG), and its width in device px. */
    val cellTimeText: Array<String?>,
    val cellTimeWidth: FloatArray,
    val metricsScale: Float,
    /** (reviewed 2026-10-14, B5/S10) `rows.version` the snapshot was built from, and per row a hash of (epg ref, epgShiftMin). */
    val rowsVersion: Long,
    val rowEpgBasis: LongArray,
) {
    val cellCount: Int get() = cellStart.size
    fun covers(row: Int, minute: Int): Boolean = row >= firstRow && row < firstRow + rowCount && minute >= fromMin && minute < toMin
}

/** The tuned channel as the guide sees it (VM maps PlaybackState → TunedState, §3.16.2). */
data class TunedState(
    val identity: ChannelIdentity? = null,
    /** channel.id of [identity] in the library, −1 when unknown. */
    val rowId: Long = -1,
    val identityHash: Long = 0,
    val phase: MiniPhase = MiniPhase.IDLE,
    val isArchive: Boolean = false,
    val error: PlaybackErrorCode? = null,
    val reconnectAttempt: Int = 0,
    val reconnectMax: Int = 0,
    /** HDR poster (VideoInfo.posterMode) or the Still setting: the hole shows a still, not video. */
    val still: Boolean = false,
)

enum class MiniPhase { IDLE, CONNECTING, BUFFERING, RECONNECTING, PLAYING, PAUSED, STOPPED, ERROR }

/** Small programme facts for the panel, copied out of ProgrammeDetail on the epg thread (no generation-local ids). */
data class ProgrammeInfo(
    val rawStartMin: Int,
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val genre: String?,
    val episodeLabel: String?,
    val flags: Int,
    val titleHash: Long,
)
```

### 2.2 `core/.../guide/GuideLayoutContracts.kt`: metrics, cells, navigation, labels (WP-A implements)

```kotlin
package app.thebox.core.guide

/**
 * Device-px geometry for one window size. All values pixel-snapped as §3.1 says. Immutable; a new instance per
 * window size. [scale] = device px per design px (BoxDesignScale's value).
 */
interface GuideMetrics {
    val scale: Float
    val panelLeft: Float; val panelTop: Float; val panelRight: Float; val panelBottom: Float
    /** Left edge of the timeline (x of minute windowStart) and its width. */
    val timelineLeft: Float; val timelineWidth: Float
    val labelLeft: Float; val labelWidth: Float
    val headerTop: Float; val headerBottom: Float
    /** Top of visible row 0 and the row pitch/height in device px (rows are pixel-snapped). */
    val rowsTop: Float; val rowPitch: Float; val rowHeight: Float
    val rowsBottom: Float
    val pxPerMin: Float
    fun px(designPx: Float): Float
    /** x of display minute [min] for a window starting at [windowStartMin] (fractional during animation). */
    fun xOf(min: Int, windowStartMin: Float): Float
    /** Device-px rect of the mini-player hole, rounded to whole pixels: [left, top, right, bottom]. */
    fun holeRect(out: IntArray)
}

fun interface GuideMetricsFactory { fun create(windowWidthPx: Int, windowHeightPx: Int): GuideMetrics }

/** Builds the cells of one row (WP-A). Called by the pager on `box-guide-epg`. Appends to the builder arrays. */
interface RowCellsBuilder {
    /**
     * [w] holds this row's programmes at indices [from, to) (sorted by start, as EpgReader.window returns them).
     * Appends cells tiling [fromMin, toMin) to [out] and returns the number appended. [noEpg] = the row has no
     * EPG ref or no data: emit NO_EPG blocks only.
     */
    fun build(w: app.thebox.core.epg.EpgWindow, from: Int, to: Int, fromMin: Int, toMin: Int, noEpg: Boolean, out: CellSink): Int
}

/** Growable struct-of-arrays target used while building a snapshot (WP-B owns the implementation). */
interface CellSink {
    fun add(start: Int, end: Int, rawStart: Int, kind: Byte, flags: Int, textId: Int, title: String?)
}

/** Where focus can be inside the grid. All minutes are display epoch minutes. */
data class GridFocus(
    val row: Int,
    val cellStart: Int,
    val cellEnd: Int,
    val cellKind: Byte,
    val anchorMin: Int,
    val windowStartMin: Int,
    val topRow: Int,
)

enum class GridMove { LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN, TIME_BACK, TIME_FORWARD, STEP_BACK, STEP_FORWARD, TO_ROW, TO_TIME, RESOLVE }

sealed interface GridMoveResult {
    /** [animate]: horizontal/vertical scroll may animate (false = snap). */
    data class Moved(val focus: GridFocus, val animate: Boolean) : GridMoveResult
    /**
     * Left with no earlier *watchable* cell (decision note accelerator 1; reviewed 2026-10-14, lead §7 item 4):
     * on a row without usable archive, Left from the cell containing now; on an archive row, Left at the archive
     * limit. Holding Left (STEP_BACK), RW and the day strip still reach the non-watchable past.
     */
    data object LeftEdge : GridMoveResult
    /** Up on lineup row 0 (product S7: goes to the header row). */
    data object TopEdge : GridMoveResult
    /** Nothing to do (bottom row, latest minute). */
    data object Blocked : GridMoveResult
}

/** Inputs of a move. [data] may be null (nothing loaded): every cell is LOADING. */
class GridContext(
    val lineupSize: Int,
    val data: GuideWindowData?,
    val nowMin: Int,
    val earliestMin: Int,
    val latestMin: Int,
    /** Snap instead of animate (key repeat beyond SNAP_AFTER_REPEATS, low-end mode). */
    val snap: Boolean,
    /** Earliest watchable display minute of lineup row [row] (now − archiveDays × 1440, or now without usable archive). */
    val watchableFrom: RowWatchable,
)

fun interface RowWatchable { fun earliestWatchableMin(row: Int): Int }

interface GuideNavigator {
    /** [arg]: target row for TO_ROW, target minute for TO_TIME; ignored otherwise. Pure; no allocation besides the result. */
    fun move(from: GridFocus, move: GridMove, arg: Int, ctx: GridContext): GridMoveResult
    /** Initial focus: [row] at [atMin] with the window placed by §3.3.2. */
    fun focusAt(row: Int, atMin: Int, ctx: GridContext): GridFocus
    /** The cell containing [minute] on [row] packed as (start shl 32) or (end and 0xffffffff); kind via [kindAt]. */
    fun cellAt(row: Int, minute: Int, ctx: GridContext): Long
    fun kindAt(row: Int, minute: Int, ctx: GridContext): Byte
}

/**
 * Allocation-free time labels in the device zone (§3.5). (reviewed 2026-10-14, S12) Offsets come from a precomputed
 * transition table (±16 days around now, rebuilt by [reset] and at local midnight); [minuteOfDay] and [dayOf] are
 * integer arithmetic, never `ZoneRules.getOffset(Instant)`. [reset] builds new immutable tables and publishes them
 * through one `@Volatile` reference, so readers on main and on `box-guide-epg` always see a consistent snapshot.
 */
interface TimeLabels {
    /** "19:30" / "7:30" for display epoch minute [min]. Returned strings are interned table entries. */
    fun hm(min: Int): String
    /** Minute of day in the device zone. */
    fun minuteOfDay(min: Int): Int
    /** Local day number (epoch day in the device zone). */
    fun dayOf(min: Int): Int
    /** "Tue 13 Oct". */
    fun dayLabel(day: Int): String
    /** "TONIGHT", "THIS AFTERNOON", … (§3.5.2). */
    fun contextLabel(windowStartMin: Int, nowMin: Int): String
    /** Duration "30 min", "1 h", "1 h 30 min" (prototype durLabel). */
    fun duration(minutes: Int): String
    /** Rebuild the zone tables (zone or 12/24 h change). */
    fun reset(zone: java.time.ZoneId, clock24: Boolean)
}
```

### 2.3 `core/.../guide/GuideRenderContracts.kt`: canvas, text, images, renderer, frame (WP-A implements)

```kotlin
package app.thebox.core.guide

/** Opaque image handle (an Android Bitmap in :ui). */
interface GuideImage { val widthPx: Int; val heightPx: Int }

/** Pre-rendered assets the renderer draws as images (no runtime blur, B1.3/B2.6). */
enum class GuideAsset { FOCUS_SHADOW, NOW_GLOW, ARCHIVE_GLYPH, ARCHIVE_GLYPH_HOLLOW, LOCK_GLYPH }

/**
 * Toolkit-agnostic drawing surface in device px (architecture review B2.9 step 3). Implementations must not
 * allocate per call. Colours are ARGB ints.
 */
interface GuideCanvas {
    fun save(): Int
    fun restoreToCount(count: Int)
    fun clipRect(l: Float, t: Float, r: Float, b: Float)
    fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int)
    fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int)
    fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int)
    fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int)
    /** Draws text[start, end) with its baseline at [baseline]. */
    fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int)
    fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float)
    /** Draws a pre-rendered asset stretched to the rect, multiplied by [argb] where the asset is a mask. */
    fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int)
}

/** Text measurement in device px. One instance per thread (Paint is not thread-safe). */
interface GuideTextMeasurer {
    val scale: Float
    fun width(text: String, start: Int, end: Int, style: GuideTextStyle): Float
    /** Count of chars of text[start, end) that fit in [maxWidth] measuring forwards (Paint.breakText). */
    fun fit(text: String, start: Int, end: Int, style: GuideTextStyle, maxWidth: Float): Int
    /** Positive distances from baseline, device px. */
    fun ascent(style: GuideTextStyle): Float
    fun descent(style: GuideTextStyle): Float
}

/** Logo cache (§3.7). Non-blocking: [get] returns null and starts a load; [onLoaded] fires on main. */
interface GuideImages {
    fun get(url: String): GuideImage?
    /** True when [url] failed this session (draw the name instead, do not retry). */
    fun failed(url: String): Boolean
    var onLoaded: (() -> Unit)?
    /** Trim to the cap, least recently drawn first (called on lineup change). */
    fun trim()
}

/** Channel rows of the current lineup (WP-B's cache). Main-thread reads only. */
interface GuideRows {
    val size: Int
    val version: Long
    /** null = block not loaded yet (draw a skeleton). */
    fun channel(index: Int): GuideChannel?
    /** Lineup index of [rowId], −1 if not in this lineup. O(n) scan, call on events only, never per frame. */
    fun indexOf(rowId: Long): Int

    companion object {
        val EMPTY: GuideRows = object : GuideRows {
            override val size = 0; override val version = 0L
            override fun channel(index: Int): GuideChannel? = null
            override fun indexOf(rowId: Long) = -1
        }
    }
}

/** Sorted set of `Hash64.long(identityHash, rawStartMin)`; binary search, no allocation. */
class ReminderIndex(private val keys: LongArray) {
    fun contains(identityHash: Long, rawStartMin: Int): Boolean =
        java.util.Arrays.binarySearch(keys, app.thebox.core.common.Hash64.long(identityHash, rawStartMin.toLong())) >= 0
    val size: Int get() = keys.size
    companion object {
        val EMPTY = ReminderIndex(LongArray(0))
        fun of(entries: List<Pair<Long, Int>>): ReminderIndex =
            ReminderIndex(LongArray(entries.size) { app.thebox.core.common.Hash64.long(entries[it].first, entries[it].second.toLong()) }.also { it.sort() })
    }
}

/**
 * Everything the renderer reads, owned and mutated by the state machine on main. The host reads
 * GuideStateMachine.frameVersion inside its draw lambda so a change invalidates the draw only.
 */
class GuideFrame {
    var metrics: GuideMetrics? = null
    var rows: GuideRows = GuideRows.EMPTY
    var data: GuideWindowData? = null
    /** Target window; the host animates towards these (§3.6.5). */
    var windowStartMin: Int = 0
    var topRow: Int = 0
    var focus: GridFocus? = null
    /** False while focus is in another zone: the row highlight stays, the focused cell draws as "selected" (ring only). */
    var gridFocused: Boolean = true
    var nowMin: Int = 0
    var earliestMin: Int = Int.MIN_VALUE
    var tunedHash: Long = 0
    var reminders: ReminderIndex = ReminderIndex.EMPTY
    /** Sorted identity hashes whose archive failed this session (hollow ↺, product §3.5). */
    var suspectArchive: LongArray = LongArray(0)
    var showLogos: Boolean = true
    var showNumbers: Boolean = true
    var lowEnd: Boolean = false
    var contextLabel: String = ""
    /** Channel-number bubble while Up/Down is held (product S7); −1 = hidden. */
    var bubbleNumber: Int = -1
    var labels: TimeLabels? = null
    var images: GuideImages? = null
}

interface GuideRenderer {
    /** Draws the whole grid panel. [scrollMin]/[scrollRow]: animated window start (epoch min) and top row. Zero allocation. */
    fun draw(canvas: GuideCanvas, frame: GuideFrame, scrollMin: Float, scrollRow: Float)
    /** Device-px bounds of the focused cell at the current scroll into [out] (l, t, r, b); false if off-screen. */
    fun focusedCellBounds(frame: GuideFrame, scrollMin: Float, scrollRow: Float, out: FloatArray): Boolean
    /** Accessibility text for the focused cell (§3.6.6). Allocates; call debounced. */
    fun describeFocus(frame: GuideFrame): String
}
```

### 2.4 `core/.../guide/GuideFeedContracts.kt`: sources, lineups, pager, settings (WP-B implements)

```kotlin
package app.thebox.core.guide

import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the grid lists. Persisted by these fields (never by channel_group.id). */
sealed interface GuideSource {
    data class Favourites(val listId: Long) : GuideSource
    data object Recent : GuideSource
    data class All(val playlistId: Long) : GuideSource
    data class Group(val playlistId: Long, val groupKey: String) : GuideSource
}

enum class SourceSection { FAVOURITES, RECENT, ALL, GROUP }

data class GuideSourceEntry(
    val source: GuideSource,
    val label: String,
    val count: Int,
    val section: SourceSection,
    /** Non-null for ALL/GROUP when more than one playlist is enabled (section header in the panel). */
    val playlistName: String?,
)

/** Ordered rows of one source. [version] changes whenever the order or membership changes. */
class GuideLineup(
    val source: GuideSource,
    val version: Long,
    val rowIds: LongArray,
    /** Upper bound of archive days over the lineup (§3.3.4), for the earliest browsable minute. */
    val archiveDaysUpperBound: Int,
)

enum class GroupAction { HIDE, MOVE_UP, MOVE_DOWN }

interface GuideLibrary {
    /** Groups panel content (§3.8.1). Re-emits on library changes. [unlocked] = PIN session open (always false until M5). */
    fun sources(unlocked: Boolean): Flow<List<GuideSourceEntry>>
    /** Re-emits when membership/order changes (refresh finish, favourites edit, hide, reorder). */
    fun lineup(source: GuideSource, unlocked: Boolean): Flow<GuideLineup>
    /** Rows for rowIds[from, to) in order; an id purged meanwhile yields null. Main-safe (switches to IO). */
    suspend fun channels(rowIds: LongArray, from: Int, to: Int): List<GuideChannel?>
    suspend fun rowIdOf(identity: ChannelIdentity): Long?
    /** Number entry: in [playlistId]'s numbering (M1 §3.3.3); null playlist = first enabled playlist. */
    suspend fun byNumber(playlistId: Long?, number: Int, unlocked: Boolean): GuideChannel?
    suspend fun isFavourite(identity: ChannelIdentity): Boolean
    /** Adds to / removes from the default list (FavouritesRepository.defaultList). */
    suspend fun setFavourite(identity: ChannelIdentity, on: Boolean)
    suspend fun hideChannel(identity: ChannelIdentity)
    suspend fun groupAction(entry: GuideSourceEntry, action: GroupAction)
    /** The last watched channel (channel_prefs.last_watched_at), for "On launch: guide with last channel". */
    suspend fun lastWatched(): GuideChannel?
    /**
     * (reviewed 2026-10-14, B5/F3) Ticks (debounced 500 ms) whenever a row's content may have changed without the
     * lineup's membership changing: tables channel, channel_prefs, playlist, epg_mapping, epg_match, epg_source.
     */
    val rowContent: Flow<Long>
}

/** Block cache over [GuideLibrary.channels] (§3.2.1). Main-thread API; loads on IO. */
interface GuideRowsCache : GuideRows {
    fun setLineup(lineup: GuideLineup)
    /** Ensure blocks covering [from, to) are loaded or loading. */
    fun ensure(from: Int, to: Int)
    /** Emits [GuideRows.version] after every block arrival. */
    val changes: StateFlow<Long>
    /** Lineup position [index] of the cache's *current* lineup, loading its block if needed. Not used by LineupZapList (S9, §3.16.3). */
    suspend fun channelAt(index: Int): GuideChannel?
    /** (reviewed 2026-10-14, B5/F3) Reload every loaded block in place (keeps the lineup and indices); bumps [version] and emits [changes]. */
    fun reloadLoaded()
}

fun interface GuideRowsCacheFactory {
    fun create(library: GuideLibrary, scope: kotlinx.coroutines.CoroutineScope): GuideRowsCache
}

/**
 * Programme windows (§3.2). Owns the `box-guide-epg` thread and the EpgReader. [ensure] is cheap and main-safe;
 * results arrive on [snapshots]. [close] stops the thread and closes the reader on it.
 */
interface GuideWindowPager : AutoCloseable {
    val snapshots: StateFlow<GuideWindowData?>
    val generation: StateFlow<EpgGenerationInfo?>
    /** [holdDirection]: −1/+1 while a vertical hold is active (S16 target bias), else 0. */
    fun ensure(topRow: Int, windowStartMin: Int, holdDirection: Int = 0)
    /** Lineup or rows changed: drop coverage, re-query the current need. */
    fun invalidate()
    /** Detail for the panel (§3.9.3); null when the programme is gone. Runs on the epg thread. */
    suspend fun info(channel: GuideChannel, rawStartMin: Int): ProgrammeInfo?
    /** The programme airing at display minute [atMin] (mini player bottom panel). */
    suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo?
}

fun interface GuideWindowPagerFactory {
    fun create(rows: GuideRows, metrics: () -> GuideMetrics?, measurer: () -> GuideTextMeasurer, builder: RowCellsBuilder, labels: TimeLabels): GuideWindowPager
}

enum class PerformanceMode { AUTO, HIGH, LOW }
enum class ExitConfirmation { BACK_TWICE, ASK, NONE }
enum class OpenGuideOn { ALL, FAVOURITES, LAST }

/** Guide settings (product §4.3, §4.6, §4.7). In-memory in M3 (like DataSettings); M5 persists them. */
data class GuideSettings(
    val clock24: Boolean = true,
    val showLogos: Boolean = true,
    val showNumbers: Boolean = true,
    val performance: PerformanceMode = PerformanceMode.AUTO,
    val miniStill: Boolean = false,
    val ambient: Boolean = true,
    val exitConfirmation: ExitConfirmation = ExitConfirmation.BACK_TWICE,
    val openGuideOn: OpenGuideOn = OpenGuideOn.LAST,
    val reminderAutoSwitch: Boolean = false,
    val reminderLeadSec: Int = 60,
    /** Debug builds only (`thebox-debug://guide?unlocked=1`): show locked/adult groups until M5's PIN exists (lead §7 item 12). */
    val debugUnlocked: Boolean = false,
)

interface GuideSettingsSource {
    val settings: StateFlow<GuideSettings>
    fun update(transform: (GuideSettings) -> GuideSettings)
}

/** Last source and first-run flags (SharedPreferences `guide_session`). */
interface GuideSessionStore {
    fun lastSource(): GuideSource?
    fun setLastSource(source: GuideSource)
    fun flag(name: String): Boolean
    fun setFlag(name: String)
}

/**
 * Device facts the guide adapts to (computed once in :ui from ActivityManager; reviewed 2026-10-14, S3/F9).
 * `isLowRamDevice` and `memoryClass` decide first; MemTotal is only a floor. To measure on hardware (M6):
 * MemTotal and memoryClass of a Fire TV Stick 4K (nominal 1.5 GB) and an Onn 4K (nominal 2 GB).
 */
data class DeviceClass(val lowRam: Boolean, val totalMemBytes: Long, val memoryClassMb: Int) {
    /** MemTotal runs ~5–10 % under nominal RAM: 1.75 GiB catches nominal 1.5 GB devices, not 2 GB ones. */
    val lowEnd: Boolean get() = lowRam || totalMemBytes < 1792L * 1024 * 1024 || memoryClassMb <= 192
    /** Nominal 1 GB class only (product §3.8: Fire TV Stick Lite). */
    val stillByDefault: Boolean get() = totalMemBytes < 1280L * 1024 * 1024
}
```

### 2.4a `core/.../guide/GuideEnvironment.kt` (commit 0; reviewed 2026-10-14, B7)

```kotlin
package app.thebox.core.guide

import kotlinx.coroutines.flow.StateFlow

/** EPG retention the guide limits use (DataSettings in :data; bound in GuideDataModule). */
data class GuideDataWindow(val pastDays: Int = 2, val futureDays: Int = 7)
interface GuideDataWindowSource { val window: StateFlow<GuideDataWindow> }

/** Sync chip input (SyncStatusRepository in :data maps to this; bound in GuideDataModule). */
data class SyncFeedState(val busy: Boolean, val progress: String?, val unacknowledgedFailures: Int)
interface SyncFeed { val state: StateFlow<SyncFeedState> }

/** Bound in :ui (InputModule, WP-D) from ActivityManager; computed once. */
fun interface DeviceClassSource { fun get(): DeviceClass }

/** Debug-only probe for the instrumented suite: the live VM registers its machine; release builds bind a no-op. */
interface GuideDebugProbe {
    fun register(machine: GuideStateMachine?)
    val current: GuideStateMachine?
}
```

### 2.5 `core/.../guide/GuideStateContracts.kt`: state machine, UI state, effects (WP-D implements the machine, WP-C the effects)

```kotlin
package app.thebox.core.guide

import app.thebox.core.input.BoxAction
import kotlinx.coroutines.flow.StateFlow

enum class ScreenMode { GUIDE, FULL, SEARCH, SETTINGS }
enum class Zone { TABS, PANEL, HEADER, GRID }
/** (reviewed 2026-10-14, S11/E21) MORE opens the context menu, for remotes without long-press or Menu; always last. */
enum class PanelButton { WATCHING, WATCH_NOW, WATCH, FROM_START, REMIND, REMINDER_SET, WATCH_CHANNEL_NOW, MORE }
/** (reviewed 2026-10-14, E22) Rows at the top of the Groups panel. */
enum class GroupsShortcut { SEARCH, SETTINGS }
enum class HeaderChip { GROUP, NOW, DAY }
enum class MenuItem { WATCH, WATCH_FROM_START, REMIND, CANCEL_REMINDER, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL }
enum class GroupMenuItem { HIDE, MOVE_UP, MOVE_DOWN }

data class PanelState(
    val channelNumber: Int?,
    val channelName: String,
    val genre: String?,
    val onNow: Boolean,
    /** "Catch-up · 7 days" when > 0 (product §3.5). */
    val catchupDays: Int,
    val title: String,
    /** "19:30 – 20:00 · 30 min" (prototype timeLabel · durLabel); "Not in catch-up" appended for a past cell without archive. */
    val timeLine: String,
    /** null until 150 ms without a focus change (B2.8). */
    val description: String?,
    val buttons: List<PanelButton>,
    val noInfo: Boolean,
)

data class DayItem(val day: Int, val label: String, val enabled: Boolean, val primeTime: Boolean = false)

sealed interface Overlay {
    /** [focused] indexes [shortcuts] first, then [entries]. */
    data class Groups(val shortcuts: List<GroupsShortcut>, val entries: List<GuideSourceEntry>, val focused: Int, val current: Int, val menu: List<GroupMenuItem>?, val menuFocused: Int) : Overlay
    data class Days(val items: List<DayItem>, val focused: Int) : Overlay
    data class Menu(val items: List<MenuItem>, val focused: Int) : Overlay
    /** Programme sheet for a future cell (product S7b). */
    data class Sheet(val reminderSet: Boolean, val focused: Int) : Overlay
    data class Number(val digits: String, val preview: GuideChannel?, val notFound: Boolean, val cursorDigit: Int) : Overlay
    /** "Press Back again to exit" (BACK_TWICE) or the dialog (ASK). */
    data class Exit(val dialog: Boolean, val focused: Int) : Overlay
}

/** Modal (reviewed 2026-10-14, S2): keys are ignored until [inputFromMs] (shown + REMINDER_CARD_GUARD_MS). */
data class ReminderCardState(val alert: ReminderAlert, val focused: Int, val secondsLeft: Int, val inputFromMs: Long)

data class FooterHint(val keys: String, val label: String)

data class SyncChip(val text: String, val failed: Boolean)

enum class EmptyKind { NO_PLAYLIST, EMPTY_SOURCE, NO_GUIDE_DATA }

data class GuideUiState(
    val mode: ScreenMode = ScreenMode.GUIDE,
    val zone: Zone = Zone.GRID,
    val tabFocused: Int = 0,
    val headerChips: List<HeaderChip> = listOf(HeaderChip.GROUP, HeaderChip.DAY),
    val headerFocused: Int = 0,
    val panelFocused: Int = 0,
    val sourceLabel: String = "",
    val dayLabel: String = "",
    /** −1: now is left of the window, +1: right, 0: visible (NOW chip hidden). */
    val nowDirection: Int = 0,
    val panel: PanelState? = null,
    val overlay: Overlay? = null,
    val reminder: ReminderCardState? = null,
    val tuned: TunedState = TunedState(),
    val tunedNow: ProgrammeInfo? = null,
    val tunedChannel: GuideChannel? = null,
    val footer: List<FooterHint> = emptyList(),
    val sync: SyncChip? = null,
    val empty: EmptyKind? = null,
    /** Group-name card in FULL after a group cycle (decision note accelerator 3). */
    val groupCard: String? = null,
)

/** Inputs of the state machine. All delivered on main. */
sealed interface GuideEvent {
    /** [heldMs] = eventTime − downTime of the key (0 for a single press); drives the hold rules of §3.4.7. */
    data class Act(val action: BoxAction, val repeat: Int = 0, val digit: Int = -1, val heldMs: Long = 0) : GuideEvent
    /** Key released after a hold (ends time-step mode and the bubble). */
    data class Release(val action: BoxAction) : GuideEvent
    data class Tick(val nowMs: Long) : GuideEvent
    data class Sources(val entries: List<GuideSourceEntry>) : GuideEvent
    data class Lineup(val lineup: GuideLineup) : GuideEvent
    data class RowsChanged(val version: Long) : GuideEvent
    data class Window(val data: GuideWindowData) : GuideEvent
    data class Generation(val info: app.thebox.core.epg.EpgGenerationInfo?) : GuideEvent
    data class Info(val token: Long, val info: ProgrammeInfo?) : GuideEvent
    data class TunedNow(val info: ProgrammeInfo?) : GuideEvent
    data class Tuned(val state: TunedState, val channel: GuideChannel?) : GuideEvent
    data class Reminders(val index: ReminderIndex) : GuideEvent
    data class Alert(val alert: ReminderAlert?) : GuideEvent
    data class Favourite(val token: Long, val isFavourite: Boolean) : GuideEvent
    data class Number(val token: Long, val channel: GuideChannel?, val lineupIndex: Int) : GuideEvent
    data class Settings(val settings: GuideSettings, val device: DeviceClass) : GuideEvent
    data class Sync(val chip: SyncChip?) : GuideEvent
    data class KeysSeen(val keyCodes: Set<Int>) : GuideEvent
    data class Metrics(val metrics: GuideMetrics) : GuideEvent
    /** (reviewed 2026-10-14, B7) SEARCH closed. [channel] non-null: focus it in the grid; [tuned]: it was tuned from search, go FULL. */
    data class SearchClosed(val channel: GuideChannel?, val tuned: Boolean) : GuideEvent
    data class DataWindow(val window: GuideDataWindow) : GuideEvent
    /** Missed reminders found on start (N5): a toast with the count. */
    data class MissedReminders(val count: Int) : GuideEvent
}

/** Side effects requested by the state machine; implemented by :ui's GuideEffectsImpl (WP-C). Main-safe, non-blocking. */
interface GuideEffects {
    fun loadLineup(source: GuideSource)
    fun ensureRows(from: Int, to: Int)
    fun ensureWindow(topRow: Int, windowStartMin: Int, holdDirection: Int = 0)
    fun loadInfo(token: Long, channel: GuideChannel, rawStartMin: Int)
    fun loadTunedNow(channel: GuideChannel, atMin: Int)
    fun tune(channel: GuideChannel, lineup: GuideLineup)
    /** Catch-up from the programme's start (§3.13.6): "Watch" on a finished programme and "From start" on the current one. */
    fun playArchive(channel: GuideChannel, info: ProgrammeInfo)
    fun setFullScreen(full: Boolean)
    fun zap(delta: Int, isRepeat: Boolean)
    fun previousChannel()
    fun retry()
    fun setReminder(channel: GuideChannel, info: ProgrammeInfo)
    fun cancelReminder(channel: GuideChannel, rawStartMin: Int)
    fun answerReminder(reminderId: Long, accept: Boolean)
    fun queryFavourite(token: Long, channel: GuideChannel)
    fun setFavourite(channel: GuideChannel, on: Boolean)
    fun hideChannel(channel: GuideChannel)
    fun groupAction(entry: GuideSourceEntry, action: GroupAction)
    fun lookupNumber(token: Long, number: Int)
    fun persistSource(source: GuideSource)
    fun openSearch()
    fun openSettings()
    fun exitApp()
    fun toast(kind: GuideToast, arg: String? = null)
    /** TalkBack/VoiceView announcement (debounced by the caller). */
    fun announce(text: String)
}

/** (reviewed 2026-10-14, S13) */
enum class GuideToast {
    REMINDER_SET_FIRST_TIME, REMINDER_SET, REMINDER_CANCELLED, REMINDER_CHANNEL_GONE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, NO_FAVOURITES, CHANNEL_HIDDEN, NO_CATCHUP,
    /** arg = CatchupFailure.name; ErrorText maps it to reason + next step. */
    CATCHUP_FAILED,
    /** arg = count (N5). */
    REMINDERS_MISSED,
    EXIT_HINT,
}

interface GuideStateMachine {
    val ui: StateFlow<GuideUiState>
    val frame: GuideFrame
    /** Bumped on every change of [frame]; the grid host reads it in its draw lambda. */
    val frameVersion: StateFlow<Long>
    fun dispatch(event: GuideEvent)
}

fun interface GuideStateMachineFactory {
    fun create(effects: GuideEffects, navigator: GuideNavigator, labels: TimeLabels, renderer: GuideRenderer, nowMs: () -> Long): GuideStateMachine
}

/** Number entry logic (§3.9.4), pure. */
interface NumberEntry {
    val digits: String
    fun digit(d: Int, nowMs: Long): Boolean
    /** D-pad entry (5-key path): Up/Down change the digit under the cursor, Right moves the cursor. */
    fun adjust(delta: Int)
    fun advance()
    /** The number to commit, or null when empty. */
    fun commitValue(): Int?
    /** True when the auto-commit deadline passed. */
    fun due(nowMs: Long): Boolean
    fun clear()
}
```

### 2.6 `core/.../guide/ReminderContracts.kt` (WP-B: book; WP-D: planner, alerts)

```kotlin
package app.thebox.core.guide

import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.StateFlow

/** A reminder as the planner sees it (reminder row, M1 ReminderEntity). (reviewed 2026-10-14, S1/F7) */
data class PendingReminder(
    val id: Long,
    val identity: ChannelIdentity,
    /** Key (raw, unshifted). */
    val rawStartMin: Int,
    val rawEndMin: Int,
    /** Guide shift at set time (reminder.shift_min); the programme airs at rawStart + shiftMin on this channel. */
    val shiftMin: Int,
    val title: String,
    val channelName: String,
    val leadSec: Int,
)

interface ReminderBook {
    /** Pending reminders as an index for cell markers; re-emits on every change. */
    val index: StateFlow<ReminderIndex>
    val pending: StateFlow<List<PendingReminder>>
    /** False when a *pending* one already exists for (identity, rawStartMin); a fired/dismissed row for the key is replaced (N5). Stores shift_min = info.startMin − info.rawStartMin. */
    suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean
    suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int)
    suspend fun markFired(id: Long)
    suspend fun dismiss(id: Long)
    suspend fun markOrphaned(id: Long)
}

data class ReminderAlert(
    val reminderId: Long,
    val identity: ChannelIdentity,
    val channelName: String,
    val title: String,
    /** Displayed (shifted) start, ms: (rawStart + shiftMin) × 60_000. (reviewed 2026-10-14, S1) */
    val startAtMs: Long,
    /** True when shown after the start (missed while the app was closed or in the background). */
    val late: Boolean,
    val autoSwitch: Boolean,
)

sealed interface ReminderDecision {
    data class Fire(val reminder: PendingReminder, val late: Boolean) : ReminderDecision
    /** The programme already ended: mark fired without a card. */
    data class Expire(val reminder: PendingReminder) : ReminderDecision
}

/** Pure scheduling (§3.11.2). */
interface ReminderPlanner {
    fun decide(pending: List<PendingReminder>, nowMs: Long, out: MutableList<ReminderDecision>)
    /** Next wall-clock ms at which [decide] could return something new; Long.MAX_VALUE when none. */
    fun nextWakeMs(pending: List<PendingReminder>, nowMs: Long): Long
}

/** The runtime (WP-D, :app; N16). Active only while the process is STARTED. */
interface ReminderAlerts {
    val current: StateFlow<ReminderAlert?>
    /** The user chose "Switch now" (or auto-switch fired): marks fired and clears the card; the caller tunes. */
    suspend fun accept(reminderId: Long)
    suspend fun dismiss(reminderId: Long)
}
```

### 2.7 Search contracts and the `EpgReader` amendment

`core/.../guide/GuideSearchContracts.kt` (WP-B implements in `:data`):

```kotlin
package app.thebox.core.guide

data class ProgrammeHit(
    val channel: GuideChannel,
    val info: ProgrammeInfo,
    val rawEndMin: Int,
    /** Past and inside the channel's archive (catch-up glyph, OK plays catch-up). */
    val inArchive: Boolean,
)

data class SearchResults(val query: String, val channels: List<GuideChannel>, val programmes: List<ProgrammeHit>)

interface GuideSearch {
    /** Empty results for fewer than 2 letters/digits (product S9). Main-safe. Cancellable (checks between stages). */
    suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults
}
```

`EpgReader` (`core/src/main/kotlin/app/thebox/core/epg/EpgContracts.kt:253-254`) changes as follows (lead in commit 0; WP-B changes the implementation). (reviewed 2026-10-14, B6: the overloaded `pastFromMin` returned week-old airings of the best text first and starved on-now hits; past search is now its own call.)

```kotlin
/**
 * FTS over title+subtitle; airings ending after [nowMin] (raw), best text first, at most [perText] airings per
 * text in start order. Rows flagged ProgrammeFlags.CONTINUATION are never returned (M1 code review Later 10).
 */
fun search(query: String, nowMin: Int, limit: Int = 50, perText: Int = Int.MAX_VALUE): List<EpgSearchHit>

/**
 * Past airings for catch-up search: end_min in (pastFromMin, nowMin] (raw), best text first, at most [perText]
 * airings per text, newest first. CONTINUATION rows are never returned. Default body returns emptyList() so
 * fakes compile; EpgReaderImpl overrides it.
 */
fun searchPast(query: String, nowMin: Int, pastFromMin: Int, limit: Int = 100, perText: Int = 5): List<EpgSearchHit> = emptyList()
```

`FtsSupport.AIRINGS_SQL` gains `AND (p.flags & 32) = 0` and is unchanged otherwise. A new `PAST_AIRINGS_SQL`:

```sql
SELECT … WHERE p.text_id = ?1 AND p.start_min >= ?2 AND p.end_min > ?2 + 360 AND p.end_min <= ?3 AND (p.flags & 32) = 0
ORDER BY p.start_min DESC LIMIT ?4
```

It binds `?2 = pastFromMin − 360`, `?3 = nowMin`. The FTS4 forms get the same flag filter and a `p.end_min <= ?` variant.

### 2.8 `:player` seam and the catch-up launcher

`player/src/main/kotlin/app/thebox/player/api/PlaybackApi.kt` gains (lead, commit 0, additive):

```kotlin
/** (reviewed 2026-10-14, S13) Distinguishes "channel gone" from "credentials missing" so the toast names the right next step. */
sealed interface CatchupTargetResult {
    data class Found(val target: app.thebox.core.catchup.CatchupTarget) : CatchupTargetResult
    data object ChannelGone : CatchupTargetResult
    data object CredentialsUnavailable : CatchupTargetResult
}
/** M3 catch-up seam (M2 §3.14). Bound in PlaybackModule over the internal StreamUrlResolver.catchupTarget. */
interface ArchiveTargets {
    /** Main-safe. */
    suspend fun catchupTarget(identity: app.thebox.core.refresh.ChannelIdentity): CatchupTargetResult
}
```

`ui/.../playback/CatchupLauncher.kt` (contract, commit 0; WP-D implements in `:app` because it needs `PlaylistRepository.timeBase`):

```kotlin
package app.thebox.ui.playback

sealed interface CatchupLaunch {
    data object Started : CatchupLaunch
    /** Reason + next step (CLAUDE.md error rule): shown as a toast/card by the caller. */
    data class Failed(val reason: CatchupFailure) : CatchupLaunch
}

enum class CatchupFailure { CHANNEL_GONE, NO_ARCHIVE, OUTSIDE_ARCHIVE, UNSUPPORTED_MODE, CREDENTIALS }

interface CatchupLauncher {
    /** Builds one ArchiveRequest (default variant) and calls PlaybackController.tuneArchive; FULL viewport. */
    suspend fun play(channel: app.thebox.core.guide.GuideChannel, info: app.thebox.core.guide.ProgrammeInfo, rawEndMin: Int): CatchupLaunch
}
```

### 2.9 `library.db` v3 (commit 0, lead)

#### 2.9.1 Columns and indexes

Additive, `@AutoMigration(from = 2, to = 3)`, `LibraryDatabase.VERSION = 3` (`data/.../db/LibraryDatabase.kt:93`), exported `data/schemas/…/3.json`, `MigrationTestHelper` instrumented case and a Robolectric open-and-read test next to M2's v2 tests (M1 §2.1.3 policy: no destructive fallback).

| Table | Column / index | Type | Default | Meaning |
|---|---|---|---|---|
| `channel` | `display_name` | `TEXT` | `NULL` | Name after the playlist's prefix rule; NULL = same as `name` |
| `playlist` | `name_prefix_mode` | `TEXT NOT NULL` | `'AUTO'` | `AUTO` \| `OFF` \| `CUSTOM` |
| `playlist` | `name_prefix_custom` | `TEXT` | `NULL` | JSON `{"literals":["UK: ","VIP | "]}` or `{"regex":"^\\[[A-Z]+\\]\\s*"}` |
| `playlist` | `name_prefix_ver` | `INTEGER NOT NULL` | `0` | `ChannelNamePrefix.RULE_VERSION` the stored `display_name`s were computed with; 0 = never |
| `reminder` | `shift_min` | `INTEGER NOT NULL` | `0` | guide shift when the reminder was set (reviewed 2026-10-14, S1) |
| `epg_match` | `index_epg_match_ref (source_id, xmltv_id)` | index | | reverse lookup for programme search (§3.10.2) |
| `epg_mapping` | `index_epg_mapping_ref (source_id, xmltv_id)` | index | | same |

Entity fields: `@ColumnInfo(name = "display_name") val displayName: String? = null` on `ChannelEntity`; `@ColumnInfo(name = "name_prefix_mode", defaultValue = "'AUTO'") val namePrefixMode: String = "AUTO"`, `@ColumnInfo(name = "name_prefix_custom") val namePrefixCustom: String? = null`, `@ColumnInfo(name = "name_prefix_ver", defaultValue = "0") val namePrefixVer: Int = 0` on `PlaylistEntity`; `@ColumnInfo(name = "shift_min", defaultValue = "0") val shiftMin: Int = 0` on `ReminderEntity`; the two `Index` entries on `EpgMatchEntity`/`EpgMappingEntity`. The generated migration must be exactly:

```sql
ALTER TABLE `channel` ADD COLUMN `display_name` TEXT;
ALTER TABLE `playlist` ADD COLUMN `name_prefix_mode` TEXT NOT NULL DEFAULT 'AUTO';
ALTER TABLE `playlist` ADD COLUMN `name_prefix_custom` TEXT;
ALTER TABLE `playlist` ADD COLUMN `name_prefix_ver` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `reminder` ADD COLUMN `shift_min` INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS `index_epg_match_ref` ON `epg_match` (`source_id`, `xmltv_id`);
CREATE INDEX IF NOT EXISTS `index_epg_mapping_ref` ON `epg_mapping` (`source_id`, `xmltv_id`);
```

No table rebuild. Review `3.json` against this. Existing rows get `display_name = NULL` and `name_prefix_ver = 0`, so the startup backfill (§3.12.3) computes them once.

#### 2.9.2 `core/.../text/ChannelNamePrefix.kt` (contract; WP-B implements)

```kotlin
package app.thebox.core.text

sealed interface NamePrefixRule {
    data object Auto : NamePrefixRule
    data object Off : NamePrefixRule
    /** Literal prefixes (case-insensitive, longest first) or one regex (applied with lookingAt). Exactly one is set. */
    data class Custom(val literals: List<String> = emptyList(), val regex: String? = null) : NamePrefixRule
}
// (reviewed 2026-10-14, B7) No `parse` body in this contract file. WP-B adds, in ChannelNamePrefixImpl.kt,
// `object NamePrefixRules { fun parse(mode: String, customJson: String?): NamePrefixRule }`, specified in §3.12.2.

interface ChannelNamePrefix {
    /** The display name for [name] in group [groupName], or null when the rule leaves it unchanged. */
    fun strip(name: String, groupName: String?, rule: NamePrefixRule): String?

    companion object {
        /** Bump when the Auto algorithm changes; the startup backfill recomputes every playlist below it. */
        const val RULE_VERSION = 1
    }
}
```

#### 2.9.3 DAO additions (WP-B; listed so reviewers can check the SQL)

| DAO | Method | SQL / note |
|---|---|---|
| `ChannelDao` | `SELECT_ROW` and every read that feeds UI | name column becomes `COALESCE(cp.custom_name, c.display_name, c.name)`; `ChannelRow` gains `display_name` |
| `ChannelPrefsDao` | `upsertHidden(playlistId, stableKey, hidden, now)` | column-targeted (M2 §2.10 pattern; fixes N-L13 for hide) |
| `GroupDao` | `upsertGroupHidden(playlistId, kind, groupKey, hidden, now)`, `setGroupOrder(…)` | targeted; reorder rewrites dense `custom_sort_index` for the playlist/kind in one transaction |
| `PlaylistDao` | `setNamePrefix(id, mode, customJson)` | also sets `name_prefix_ver = 0` (forces recompute) |
| `PlaylistDao` | `setNamePrefixVer(id, ver)` | after a recompute pass |
| `ReminderDao` | `observeAllPending(): Flow<List<ReminderEntity>>` | `state = 0 ORDER BY start_min` (no lower bound: the planner expires old ones) |
| `ReminderDao` | `setStateIfPending(id, state): Int` | `UPDATE … WHERE id = ? AND state = 0` (two writers: card and planner) |
| `ReminderDao` | `deleteIfNotPending(playlistId, stableKey, startMin)` | `DELETE … WHERE … AND state <> 0` before re-setting (N5: the identity index ignores state) |
| `ReminderRepository` | `add(…, shiftMin: Int = 0)` | writes `reminder.shift_min` (S1) |

### 2.10 `core/.../input/InputContracts.kt` (WP-D implements)

```kotlin
package app.thebox.core.input

/**
 * Every bindable action (decision note 2026-10-14 "Remote button mapping", in its order, plus M3's guide
 * accelerators). M5's Button mapping screen lists the [remappable] ones. Persisted by [id]; append-only.
 */
enum class BoxAction(val id: String, val remappable: Boolean) {
    // Reserved: D-pad and OK keep navigation outside the player, Back keeps "close top layer" (decision note).
    UP("up", false), DOWN("down", false), LEFT("left", false), RIGHT("right", false),
    SELECT("select", false), BACK("back", false),
    /** Long-press OK; the Menu key is an alias (product §2.1). */
    CONTEXT_MENU("context_menu", true),
    // Decision note list
    ZAP_UP("zap_up", true), ZAP_DOWN("zap_down", true), PREVIOUS_CHANNEL("previous_channel", true),
    OPEN_GUIDE("guide", true), CHANNEL_LIST("channel_list", true), GROUPS_PANEL("groups_panel", true),
    QUICK_MENU("quick_menu", true), AUDIO("audio", true), SUBTITLES("subtitles", true), ASPECT("aspect", true),
    START_OVER("start_over", true), FAVOURITES("favourites", true), INFO_BAR("info_bar", true),
    NUMBER_ENTRY("number_entry", true), PLAY_PAUSE("play_pause", true), SEEK_BACK("seek_back", true),
    SEEK_FORWARD("seek_forward", true), JUMP_TO_LIVE("jump_to_live", true), STATS_OVERLAY("stats_overlay", true),
    SCREEN_OFF("screen_off", true), EXIT("exit", true),
    // M3 additions
    GROUP_NEXT("group_next", true), GROUP_PREV("group_prev", true),
    PAGE_UP("page_up", true), PAGE_DOWN("page_down", true),
    REMIND("remind", true), JUMP_TO_NOW("jump_to_now", true), DAY_STRIP("day_strip", true),
    /** A digit key; the digit travels in GuideEvent.Act.digit. Not remappable (numbers are numbers). */
    DIGIT("digit", false),
}

enum class KeyContext { GUIDE, PLAYER }

/** Android KeyEvent fields, copied without Android types. */
data class KeyInput(
    val keyCode: Int,
    val isDown: Boolean,
    val repeatCount: Int,
    val downTimeMs: Long,
    val eventTimeMs: Long,
    val canceled: Boolean = false,
)

sealed interface KeyGesture {
    val keyCode: Int
    /** Short press: on DOWN for keys without a long binding (repeat > 0 for auto-repeat), on UP for keys with one. */
    data class Press(override val keyCode: Int, val repeat: Int) : KeyGesture
    data class LongPress(override val keyCode: Int) : KeyGesture
    /** Key released after one or more repeats (ends holds). */
    data class ReleaseAfterRepeat(override val keyCode: Int) : KeyGesture
}

interface KeyGestureDetector {
    /** [hasLong]: the key has a long-press binding in the current context. Appends 0–1 gestures to [out]. */
    fun onKey(input: KeyInput, hasLong: Boolean, out: MutableList<KeyGesture>)
    /**
     * Called by the router LONG_PRESS_MS after the DOWN of a key with a long binding, for remotes that never
     * auto-repeat OK (API notes §2.2). Appends LongPress when the key is still down and none fired yet.
     */
    fun onTimer(keyCode: Int, nowMs: Long, out: MutableList<KeyGesture>)
    /** Focus left the guide root: Compose drops the matching KeyUp (API notes §2.1), so forget every key. */
    fun reset()
}

data class KeyBinding(val keyCode: Int, val short: BoxAction?, val long: BoxAction?)

interface KeyMap {
    fun short(context: KeyContext, keyCode: Int): BoxAction?
    fun long(context: KeyContext, keyCode: Int): BoxAction?
    /** 0–9 for digit keys (incl. numpad), else −1. */
    fun digit(keyCode: Int): Int
    fun bindings(context: KeyContext): List<KeyBinding>
}

/** Android key codes used by DefaultKeyMap (values from android.view.KeyEvent; :core cannot see Android). */
object Keys {
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
    const val BACK = 4; const val MENU = 82; const val ENTER = 66; const val NUMPAD_ENTER = 160; const val ESCAPE = 111
    const val BUTTON_A = 96; const val BUTTON_B = 97
    const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167; const val PAGE_UP = 92; const val PAGE_DOWN = 93
    const val MEDIA_PLAY_PAUSE = 85; const val MEDIA_PLAY = 126; const val MEDIA_PAUSE = 127
    const val MEDIA_FAST_FORWARD = 90; const val MEDIA_REWIND = 89; const val MEDIA_RECORD = 130
    const val DIGIT_0 = 7 /* … 9 = 16 */; const val NUMPAD_0 = 144 /* … 9 = 153 */
    const val PROG_RED = 183; const val PROG_GREEN = 184; const val PROG_YELLOW = 185; const val PROG_BLUE = 186
    const val LAST_CHANNEL = 229; const val GUIDE = 172; const val INFO = 165; const val CAPTIONS = 175
    const val MEDIA_AUDIO_TRACK = 222; const val TV_ZOOM_MODE = 255; const val SEARCH = 84
}

/** Keys the device has actually reported (product §2.1). :ui implements (SharedPreferences `keys_seen`; writes only a newly seen code, N7). */
interface KeyCapabilities {
    val seen: kotlinx.coroutines.flow.StateFlow<Set<Int>>
    fun record(keyCode: Int)
}
```

`ui/.../input/KeyRouter.kt` (commit 0, WP-D implements; reviewed 2026-10-14, B1: **the only definition of the key path**; every other section refers here and to §3.18.1):

```kotlin
package app.thebox.ui.input

/**
 * The one key path of the app: Compose KeyEvent → KeyInput → KeyGestureDetector → KeyMap → GuideEvent.Act/Release,
 * dispatched to the current GuideStateMachine. Returns true for every key it handled (including the matching KeyUp).
 * In ScreenMode.SEARCH it returns false for every key except BACK while the search field is empty and the IME is hidden.
 */
interface KeyRouter {
    fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean
    /** The sink lost focus: detector.reset() (Compose drops unmatched KeyUps). */
    fun onFocusLost()
    fun attach(machine: app.thebox.core.guide.GuideStateMachine?, searchFieldIdle: () -> Boolean)
    /**
     * The key sink ("Modifier.boxKeySink" elsewhere in this document) = focusRequester(requester)
     * .onFocusChanged { if (!it.isFocused) onFocusLost() }.onPreviewKeyEvent(::onKey).focusable(). No layer, alpha or clip.
     */
    fun sinkModifier(requester: androidx.compose.ui.focus.FocusRequester): androidx.compose.ui.Modifier
}
```

### 2.11 Threading and ownership

| Thing | Thread | Owner |
|---|---|---|
| `GuideStateMachine.dispatch`, `GuideFrame` mutation, navigator calls, `KeyRouter.onKey` | main | WP-D |
| `GuideRowsCache` reads and block application (`channel(i)`, `indexOf`) | main only; the pager receives copied `GuideChannel` arrays (§3.2.3) | WP-B |
| `TimeLabels` reads | any thread; `reset` publishes immutable tables through one `@Volatile` reference (S12) | WP-A |
| `LineupZapList.neighbour` | controller's scope; `GuideLibrary` calls on IO, never the rows cache (S9) | WP-C |
| `GuideRenderer.draw` | main (RenderThread replays the recorded ops) | WP-A |
| `GuideWindowPager` queries, `EpgReader` (one per pager), `RowCellsBuilder`, title measurement, reader close | `box-guide-epg` (one daemon thread per pager, `Executors.newSingleThreadExecutor` + `asCoroutineDispatcher`) | WP-B |
| `GuideLibrary`, `GuideSearch`, `ReminderBook` | `Dispatchers.IO` inside the implementation (main-safe API) | WP-B |
| `GuideSearch` programme stage | its own `EpgReader` from `EpgStore.openReader()` (the aux connection, M1 §2.5.3), closed in `finally` | WP-B |
| Coil loads, ambient bitmap rendering, hue extraction | Coil dispatchers / `Dispatchers.Default` | WP-A (logos), WP-C (ambient) |
| `ReminderSchedulerImpl` (`:app`) | `@ApplicationScope` + main for `current` | WP-D |

No `runBlocking` in any M3 code. Nothing on main waits on the epg thread.

---
## 3. Algorithms

### 3.1 Geometry (WP-A `GuideMetricsImpl`)

`s` = device px per design px = `designPxToPx(windowW, windowH)` (`BoxDesignScale.kt:63-66`): 1.0 at 1920×1080, 0.6667 at 1280×720, 2.0 at 3840×2160. Positions below are design px; the metrics multiply by `s`.

| Quantity | Design px | Rule |
|---|---|---|
| Panel outer | (64, 500) – (1856, 960) | border 1, radius 24 |
| Content box | (81, 501) – (1839, 947) | panel minus border 1 and padding 0/16/12 |
| Header row | y 501 – 549 | 48 |
| Label column | x 81 – 281 | 200; text starts at 81 + 12 |
| Timeline | x 281 – 1839 | width 1558; `pxPerMin = 1558 s / 180` |
| Row *i* (visible index) | top = 549 + 68 *i*, height 60 | `rowTop(i) = round((549 + 68 i) s)`, `rowBottom = rowTop + round(60 s)` (rounded per row, so 6 rows never drift) |
| Rows area | y 549 – 947 | clip; row 5 ends at 949 in the prototype (2 px into the padding, which the prototype does not clip); we clip at 947 + 12 = 959 (inner panel bottom), so nothing is cut |
| Cell *x* | `xOf(start) + 3` – `xOf(end) − 3` | `xOf(m) = timelineLeft + (m − scrollMin) × pxPerMin`, not snapped (anti-aliased edges) |
| Now line | `xOf(now)`, from `rowsTop − 6` to rows bottom | dot centre `(x, rowsTop − 5)`, radius 5 |
| Hole | (64, 56) – (784, 461) | `holeRect = [round(64 s), round(56 s), round(784 s), round(461 s)]`: (64, 56, 784, 461) at 1080p, (43, 37, 523, 307) at 720p |

Verification at 1080p: a 30-min tick is 259.67 px (prototype: 1558 / 6); the prototype's now line at 19:47 with the window at 19:00 sits at `281 + 47 × 8.6556 = 687.8`. Both are rows of the WP-A geometry test.

### 3.2 Paging (WP-B)

#### 3.2.1 Lineup and row blocks

- **Lineup query** per source (`GuideQueries`, raw statement on `useReaderConnection`, filling a `LongArray` without boxing). Every variant applies `c.missing_since IS NULL`, `c.kind IN (0, 1)`, `p.enabled = 1` and `ChannelDao.PARENTAL` (`ChannelDao.kt:232-236`), and orders exactly like the M1 read it replaces:
  - `Group(playlistId, groupKey)`: `browseGroup` order (`ChannelDao.kt:74-80`), group matched by `g.playlist_id = ? AND g.kind = 0 AND g.group_key = ?`.
  - `All(playlistId)`: `browseAll` order (`ChannelDao.kt:83-89`).
  - `Favourites(listId)`: `FavouriteDao.channels` order (`FavouriteDao.kt:47-61`), across playlists.
  - `Recent`: `channel_prefs.last_watched_at DESC`, limit `RECENT_LIMIT` (50). While Recent is the displayed source its order is frozen; it re-sorts when the source is re-selected (N2: otherwise the focused row jumps about 5 s after every tune).
- `All` keeps `browseAll`'s provider order, not `custom_sort_index` (which is dense per group, `ChannelPrefsEntity.kt:23`, and meaningless across groups; API notes §8.4 raised it).
- **Re-emission:** `db.invalidationTracker.createFlow("channel", "channel_prefs", "group_prefs", "channel_group", "favourite", "playlist")` **(verify Room 2.8 name; owner WP-B, §8)** triggers a re-query; the new `LongArray`'s `Hash64` is the `version`, and an unchanged version is not emitted (a refresh that changes nothing visible does not reload the grid).
- **`archiveDaysUpperBound`:** one aggregate over the same lineup predicate: `MAX(max(COALESCE(catchup_days,0), COALESCE(timeshift_days,0), COALESCE(tv_archive_days,0), COALESCE(tvg_rec_days,0), CASE WHEN catchup_type IS NOT NULL OR catchup_source IS NOT NULL OR tv_archive = 1 THEN 3 ELSE 0 END))`, then `max(that, playlist.catchup_days_override ?: 0)`. It is an upper bound only (the exact days per row come from `CatchupResolver`); it sets how far back the window may go (§3.3.4).
- **Blocks:** `GuideRowsCacheImpl` keeps blocks of `ROW_BLOCK` = 32 lineup positions, at most `ROW_BLOCKS_MAX` = 8 (256 rows), evicting the least recently `ensure`d. `ensure(from, to)` loads every block intersecting `[from − 32, to + 32)`. A block load runs one statement with `c.id IN (?×≤32)` selecting id, playlist, stable key, `COALESCE(cp.custom_name, c.display_name, c.name)`, number columns, `COALESCE(cp.custom_logo, c.logo_url)`, the raw catch-up columns, `url` and `xtream_stream_id`; then `ChannelRepository.number(…)` (`ChannelRepository.kt:126-130`) per row with the playlist's `numbering_mode` (custom number first), `CatchupResolver.resolve` (`CatchupContracts.kt` "CatchupResolver") with the playlist's `catchupDefaults` (cached per block load), and `EpgRefResolver.resolve(identities)` for the block (§3.2.2). The cache emits `changes` after each block. Budget: one block ≤ 15 ms on the emulator (logged).
- **Row content** (reviewed 2026-10-14, B5/F3): membership does not change when the matcher writes `epg_match` after the first import, a mapping is fixed, a source's priority or shift changes, a custom name/number/logo is set, `display_name` is recomputed or a refresh changes catch-up attributes. `GuideLibrary.rowContent` ticks (debounced 500 ms) on any change of `channel`, `channel_prefs`, `playlist`, `epg_mapping`, `epg_match` or `epg_source`. The VM collects `library.rowContent` and calls `rowsCache.reloadLoaded()`. The pager re-queries rows whose `(epg, epgShiftMin)` differ from the snapshot's basis.
- Block results are keyed by block index in a primitive map (`MutableIntObjectMap`), so `channel(i)` never boxes (N3).
- The state machine calls `ensureRows(top − 6, top + 12)` on every vertical move; the cache is the `GuideRows` the frame reads (main only).

#### 3.2.2 EPG reference per channel (`EpgRefResolver`)

The M1 §2.2 rule, unchanged, factored out of `EpgRepository.resolvedRefs` (`EpgRepository.kt:319-358`) so the guide can run it for 32 identities instead of every channel of every playlist: mapping row → explicit ref or explicit none; else the first `AUTO` match with `has_data` in source priority (`epg_source.priority`, enabled, `playlist_id IS NULL OR = channel's`); else the first `AUTO` match; shift = `source.shift_min + (prefs ?: channel tvg-shift ?: header tvg-shift ?: 0) + global`. Inputs for a block: `epg_mapping WHERE playlist_id = ? AND stable_key IN (…)`, `epg_match WHERE playlist_id = ? AND stable_key IN (…) AND status = 0`, `epg_source` active list (cached, invalidated by the tracker), the playlist header shift (cached per playlist). `resolvedRefs` becomes a loop over the same function, and its existing tests must still pass.

#### 3.2.3 Programme windows (`GuideWindowPagerImpl`)

- **Need and coverage.** Given `(topRow, windowStartMin = ws)`: the *visible need* is rows `[top, top + 6)` × minutes `[ws, ws + 180)`. The *margin need* is rows `[top − 2, top + 8)` × `[ws − 60, ws + 240)`, both clipped to `[0, size)` and `[earliest, latest)`. When the current snapshot does not cover the margin need, the pager queries the *target*: rows `[top − 6, top + 12)` × `[ws − 180, ws + 360)` (clipped): 18 rows × 9 h, about 270 programmes on a typical list.
- **Holds** (reviewed 2026-10-14, S16): while a vertical hold is active (`ensure(…, holdDirection)` from the state machine), the target is biased in the direction of motion (rows `[top − 2, top + 24)` going down, mirrored going up) and the row margin is 6. At 20 Hz repeat 2 margin rows gave only 100 ms of lead.
- **Coalescing:** at most one query runs. An `ensure` while one runs replaces the queued target (latest wins). `ensure` itself only compares integers on main and posts at most one runnable.
- **Thread confinement:** `GuideRows` is main-only (block results are applied on main). `ensure` copies the target rows' `GuideChannel?` values (immutable) into an array that travels with the request, together with `rows.version`; the epg thread never reads `GuideRows`.
- **Query** on `box-guide-epg`: for target rows with a loaded channel and an `epg` ref, look up the generation-local int in the pager's `HashMap<EpgChannelRef, Int>` (filled by `reader.resolve(refs)` for misses, M1 §2.5.3); rows without a ref or with −1 get `NO_EPG`; rows whose block is not loaded get `PENDING` and no cells. One `reader.window(ints, shifts, from, to, epgWindow)` call (`EpgContracts.kt:246`, `EpgReaderImpl.kt:51-88`) fills a reused `EpgWindow`; `RowCellsBuilder` turns each row's slice into cells (§3.2.4); each PROGRAMME title width is taken from the pager's `textId → width` cache (`MutableIntFloatMap`, cleared on generation, patch or scale change, and when it exceeds 8,192 entries) or measured with the pager's own `GuideTextMeasurer`.
- (reviewed 2026-10-14, B3/F1) **Snapshot** arrays are allocated fresh for every snapshot (no recycling: worst case ≈ 2,000 cells ≈ 200 KB, off-main) and never written after `snapshots.value = data`. The state machine drops a `Window` whose `lineupVersion` differs from the current lineup's or whose rows were built from an older `rows.version` than the last `RowsChanged` it processed for those rows; the pager re-queries on the next `ensure` (S10a). Publication: `snapshots.value = data`; the VM forwards it as `GuideEvent.Window`. Each snapshot also records, per row, the `(epg, epgShiftMin)` it was built from (the B5 basis) and sets `CELL_RTL` (`1 shl 29`) on titles where `java.text.Bidi.requiresBidi` is true (S6), once per `textId`.
- **Pending rows:** the state machine calls `ensureWindow` again on `RowsChanged`; if the current snapshot has `PENDING` rows and was built from an older `rows.version`, `ensure` re-queries even though the coverage is unchanged (coalesced).
- **Generation swap:** the pager collects `EpgStore.info` on its own dispatcher. A new `generation` → `openReader()`, clear the ref-int map and the width cache, re-run the current target with the new reader, publish, then `close()` the old reader **on this thread** (M1 Later 11: `GenerationHandle.release` deletes the file on the closing thread; never main). A new `patchSeq` with the same generation → clear the ref-int map, re-run (M1 §2.5.3 S13). `null` info (no guide data) → snapshots with `generation = −1` and every row `NO_EPG`.
- **`SQLITE_BUSY`** (M1 Later 8: readers use `busy_timeout = 250` while a patch commits): (reviewed 2026-10-14, S7) one `isBusy(e)` helper: `androidx.sqlite` formats errors as `"Error code: $errorCode, message: $errorMsg"` (`sqlite-jvm-2.6.2` `SQLite.kt:60-67`), so parse `Regex("Error code: (\\d+)")` and treat `(code and 0xFF) == 5` as busy (covers `SQLITE_BUSY_RECOVERY` 261 and `SQLITE_BUSY_SNAPSHOT` 517); unit-tested on a constructed message. The native step path's format is **(verify; owner WP-B, §8)**. On busy: keep the current snapshot, retry after `BUSY_RETRY_MS` (500), at most `BUSY_RETRIES` (3), then wait for the next `ensure`.
- **Details:** `info(channel, rawStart)` → `reader.detail(int, rawStart, shift)` (`EpgReaderImpl.kt:90-99`) → `ProgrammeInfo` (genre = first category). LRU of `INFO_CACHE` = 32 keyed by `(generation, identityHash, rawStart)`. `at(channel, atMin)` uses `reader.at` (`EpgReaderImpl.kt:101-112`) for the mini player's bottom panel.
- **Cost:** warm window query < 1 ms for 21 channels × 12 h on the JVM (plan §8 M1 row), so a full 18 × 9 h refresh including cell building and measurement is budgeted at ≤ 8 ms on the emulator and must never touch main.

#### 3.2.4 Row cells (WP-A `RowCellsBuilderImpl`)

Input: one row's programmes in start order, the coverage `[fromMin, toMin)`, `noEpg`.

1. `noEpg`: emit `NO_EPG` blocks cut at every local hour boundary (`TimeLabels.minuteOfDay(m) % 60 == 0`) across `[fromMin, toMin)` (product §3.1: "60-min blocks aligned to the hour").
2. Otherwise walk the programmes with a cursor `c = fromMin`. (reviewed 2026-10-14, S10b) For each programme `p`: skip `p` when `p.end ≤ c` (wholly inside its predecessor); if `p.start > c`, emit the gap `[c, p.start)` (rule 3); emit `p` with `start = max(p.start, c)` and its true end (for the first cell only, a true start before `fromMin` is kept); `c = p.end`. After the last programme, emit the gap `[c, toMin)`. Cells therefore never overlap.
3. A gap is cut at local hour boundaries into `GAP` cells. A gap piece shorter than `SLIVER_MIN` (5 min) is still emitted (it tiles the row) but marked with the flag bit `CELL_SLIVER = 1 shl 30` in `cellFlags`; slivers are not focusable and draw nothing.
4. CONTINUATION segments (`ProgrammeFlags.CONTINUATION`, split at 6 h in M1) are merged with their predecessor when both are present (one cell, end = the continuation's end); a lone continuation at the coverage start is emitted as is.

### 3.3 Time model (WP-A navigator, WP-D limits)

#### 3.3.1 Terms

`now` = `floor(nowMs / 60_000)`. `floor30(m)` / `ceil30(m)` round to a local :00/:30 boundary (`TimeLabels.minuteOfDay`; zones with a :15/:45 offset still get local :00/:30). `nowWindow = floor30(now) − 30` (the prototype's window at 19:47 starts 19:00: the now line sits 17–33 % in, about the product's "25 %"). `clampWs(m) = clamp(m, floor30(earliest), max(floor30(earliest), ceil30(latest) − 180))`.

#### 3.3.2 Placement rules

- **Initial / jump to now / to a row:** `ws = clampWs(nowWindow)`, `anchor = now`, cell = cell containing `now`, `topRow = clamp(row − 1, 0, max(0, size − 6))` (one row of context above).
- **Horizontal move** onto cell `c`: if `c.start < ws` or `c.start ≥ ws + 180 − TAIL_MIN` (30), then `ws' = clampWs(floor30(c.start) − LEAD_MIN)` (30); otherwise `ws' = ws`. `anchor = max(c.start, ws')`. Invariant: after Left/Right the focused cell's start is visible (unless it starts before `earliest`).
- **Vertical move:** the window does not move horizontally; `anchor` is unchanged (plan §4: "Up/Down keep the anchor"); the new cell is the one containing `anchor` (a sliver resolves to the next focusable cell, else the previous).
- **Vertical scroll (product §3.4, edge scrolling with one-row lookahead):** focus stays at visible index 1…4. `topRow' = clamp(topRow, focus − 4, focus − 1)` then clamped to `[0, max(0, size − 6)]`; at the list ends focus may reach index 0 or 5.
- **Time page (FF/RW):** `ws' = clampWs(ws ± 180)`, `anchor' = clamp(anchor ± 180, ws', ws' + 179)`.
- **Time step (hold, §3.4.7):** `ws' = clampWs(ws ± 30)`, `anchor' = clamp(anchor ± 30, ws', ws' + 179)`.
- **Day jump (§3.5.1):** `anchor' = target`, `ws' = clampWs(floor30(target) − 30)`.

#### 3.3.3 Animation

Every placement says `animate = !ctx.snap`. `ctx.snap` is true in low-end mode and when the key's `repeat ≥ SNAP_AFTER_REPEATS` (3) (B2.5). The host animates `scrollMin`/`scrollRow` over `SCROLL_ANIM_MS` (200 ms, ease-out, product §3.1); a new target while animating retargets from the current value.

#### 3.3.4 Limits (WP-D `GuideLimits`)

- `D = max(GuideDataWindow.pastDays, lineup.archiveDaysUpperBound)` (the `DataWindow` event from `GuideDataWindowSource`, which `:data` binds over `DataSettings.epgPastDays`, `DataSettings.kt:15`; reviewed 2026-10-14, B7) (product §3.5: "max(archiveDays over channels in the current group, pastDaysToKeep)").
- `earliest = localMidnight(now − D × 1440)`; with a generation, raised to `floor30(info.windowFromMin)` (no data before it).
- `latest = now + GuideDataWindow.futureDays × 1440`; with a generation, lowered to `info.windowToMin`; never below `now + 180`.
- Recomputed on every `Lineup`, `Generation`, `DataWindow` and minute `Tick`. If the current window falls outside after a change, it is re-clamped (snap).

### 3.4 Focus and keys (WP-D state machine, WP-A grid moves)

#### 3.4.1 Zones

Screen `GUIDE`, from top to bottom: **TABS** (Guide · Search · Settings gear) → **PANEL** (the focused programme's buttons) → **HEADER** (Group chip · Now chip when shown · Day chip) → **GRID**. Overlays (Groups, Days, Menu, Sheet, NumberEntry, Exit) sit above any zone and take every key while open. (reviewed 2026-10-14, S2) The reminder card is **modal**: it takes every key, and ignores all input for `REMINDER_CARD_GUARD_MS` (600) after it appears so an in-flight OK cannot accept; Back = Dismiss. Dispatch order per action: reminder card → overlay → global actions (DIGIT, PREVIOUS_CHANNEL, EXIT, OPEN_GUIDE) → mode (`FULL`) → zone.

Leaving GRID keeps the grid focus (`frame.gridFocused = false`, the focused cell draws as a ring only); returning restores it.

#### 3.4.2 GRID

| Action | Behaviour |
|---|---|
| LEFT | `GridMove.LEFT`. (reviewed 2026-10-14, lead §7 item 4) `LeftEdge` → open Groups (decision note accelerator 1) when there is no earlier **watchable** cell: on a row without usable archive (`archiveDays == 0` or suspect), Left from the cell containing `now`; on an archive row, Left at the archive limit (`ctx.watchableFrom`). The non-watchable past stays reachable by holding Left (`STEP_BACK`, §3.4.7), RW and the day strip. A focused cell already in the non-watchable past moves Left normally until `earliest`. |
| RIGHT | `GridMove.RIGHT`; `Blocked` at `latest`. |
| UP | `GridMove.UP`; `TopEdge` on lineup row 0 → zone HEADER, chip GROUP. |
| DOWN | `GridMove.DOWN`; `Blocked` on the last row. |
| ZAP_UP / PAGE_UP, ZAP_DOWN / PAGE_DOWN (CH±, Page) | `PAGE_UP`/`PAGE_DOWN`: ±6 rows and ±6 `topRow` (product S7), anchor kept. |
| SEEK_BACK / SEEK_FORWARD (RW/FF) | `TIME_BACK`/`TIME_FORWARD` (±3 h). |
| SELECT | OK table §3.9.2. |
| CONTEXT_MENU (long OK, Menu) | Menu overlay (§3.9.3). |
| BACK | Back ladder §3.4.6. |
| PLAY_PAUSE | Tuned and not IDLE/STOPPED → FULL. Otherwise no-op. |
| JUMP_TO_NOW, OPEN_GUIDE | Jump to now (focus row unchanged; window and anchor to now). |
| FAVOURITES (Green) | Toggle the Favourites filter (§3.8.3). |
| START_OVER (Red) | FROM_START when offered, else REMIND on a future programme, else toast NO_CATCHUP. |
| REMIND, MEDIA_RECORD | Toggle the reminder on a future PROGRAMME; else no-op. |
| GROUPS_PANEL | Open Groups. |
| DAY_STRIP | Open Days. |
| PREVIOUS_CHANNEL (Blue, Last) | `effects.previousChannel()`. |
| DIGIT, NUMBER_ENTRY | Number entry (§3.9.4). |
| AUDIO, SUBTITLES, ASPECT, QUICK_MENU, INFO_BAR, CHANNEL_LIST, STATS_OVERLAY, JUMP_TO_LIVE, SCREEN_OFF, GROUP_NEXT/PREV | No-op in the guide (player actions, M4). |

#### 3.4.3 TABS, PANEL, HEADER

| Zone | LEFT/RIGHT | SELECT | UP | DOWN | BACK | Other |
|---|---|---|---|---|---|---|
| TABS (Guide, Search, Settings) | move, no wrap | Guide → GRID; Search → mode SEARCH; Settings → mode SETTINGS (M3 placeholder: version, "Settings arrive with the next update", Back returns) | no-op | PANEL if it has buttons, else HEADER | GRID | — |
| PANEL (buttons of `PanelState.buttons`, MORE always last) | move | the button (§3.9.2); MORE → Menu overlay (S11) | TABS | HEADER | GRID | `panelFocused` is clamped when the buttons change under it (N8) |
| HEADER (GROUP, NOW?, DAY) | move | GROUP → Groups; NOW → jump to now, zone GRID; DAY → Days | PANEL (TABS if no buttons) | GRID | GRID | PLAY_PAUSE → jump to now (product §3.3); ZAP± → page the grid |

The NOW chip exists only while `now ∉ [ws, ws + 180)`; its arrow is `nowDirection`. If it disappears while focused, focus moves to GROUP.

#### 3.4.4 Overlays

| Overlay | UP/DOWN | LEFT/RIGHT | SELECT | CONTEXT_MENU | BACK | Other |
|---|---|---|---|---|---|---|
| Groups | move over the Search and Settings shortcut rows (E22), then the entries (skips section headers) | RIGHT closes; LEFT no-op | shortcut → mode SEARCH / SETTINGS; entry → apply source (§3.8.2); close | GROUP entries: group menu (Hide, Move up, Move down) | close (menu first) | ZAP±/PAGE± → previous/next entry applied at once, panel stays open (accelerator 2) |
| Groups → group menu | move | — | apply action, close menu | — | close menu | — |
| Days | — | move over enabled items | jump (§3.5.1), close | — | close | — |
| Menu | move | — | item (§3.9.3), close | — | close | — |
| Sheet (future cell) | — | move ([Remind me / Reminder set], [Watch channel now]) | button | — | close | — |
| NumberEntry | adjust the digit under the cursor (D-pad entry) | RIGHT: next position | commit | — | clear and close | DIGIT appends; any other action commits first, then runs |
| Exit (ASK dialog) | — | move ([Exit], [Cancel]) | button | — | Cancel | — |
| Reminder card (modal, S2) | ignored | move ([Switch now], [Dismiss]) | button | ignored | Dismiss | every key ignored during the 600 ms guard |

The `BACK_TWICE` exit hint is not modal: it is a 2 s toast-like overlay; a second BACK within `EXIT_CONFIRM_MS` (2,000) exits, any other action hides it and runs.

#### 3.4.5 FULL (minimal M3 full screen, §3.13.5)

| Action | Behaviour |
|---|---|
| BACK | (reviewed 2026-10-14, B2/F10) GUIDE, viewport MINI, focus **home** (tuned row at now), `returnedFromFull = true` (cleared by any other action); product nav map: the next Back shows the exit step |
| UP / ZAP_UP, DOWN / ZAP_DOWN | `effects.zap(−1 / +1, isRepeat)` (M2 debounce coalesces holds) |
| GROUP_PREV / GROUP_NEXT (long ← / →) | previous/next Groups entry becomes the source and the zap list (the channel keeps playing); `groupCard` shows its label for `GROUP_CARD_MS` (2,000) (accelerator 3) |
| SELECT | error phase with RETRY in the code's steps → `retry()`; otherwise no-op until M4's channel list |
| DIGIT, NUMBER_ENTRY | number entry; commit tunes directly |
| PREVIOUS_CHANNEL | `previousChannel()` |
| OPEN_GUIDE | as BACK |
| CONTEXT_MENU | no-op until M4's quick menu |
| everything else | no-op (M4) |

#### 3.4.6 Back ladder (GUIDE)

1. An overlay is open → close it (reminder card: Dismiss).
2. Zone ≠ GRID → GRID.
3. Not at *home* → go home (animated): home = row of the tuned channel if it is in the lineup, else the current row; cell containing `now`; `ws = clampWs(nowWindow)`; `anchor = now`.
4. (reviewed 2026-10-14, B2/F10) Something is tuned (`phase ∉ {IDLE, STOPPED}`) **and `returnedFromFull` is false** → FULL.

The ladder from home is therefore: jump to now → FULL → (Back in FULL returns home with `returnedFromFull`) → exit step; Back after leaving full screen never goes back to FULL (B2: otherwise GUIDE ↔ FULL looped forever and exit was unreachable while anything played).
5. Exit per `GuideSettings.exitConfirmation`: `BACK_TWICE` → hint, second BACK within 2 s exits; `ASK` → Exit dialog; `NONE` → `exitApp()`.

#### 3.4.7 Holds and repeats

- Vertical (UP/DOWN with `repeat > 0`): one row per event; snap from the 3rd repeat; `bubbleNumber` = the focused row's number while held; (reviewed 2026-10-14, S16) while `repeat > 0`, `PanelState` is not rebuilt at all (no 64 px title relayout at 20 Hz); it is rebuilt on `Release`, and `loadInfo` follows 150 ms later. `ensureWindow` passes `holdDirection` (§3.2.3).
- Horizontal (LEFT/RIGHT): cell by cell while `heldMs < HOLD_TIME_STEP_AFTER_MS` (1,000); after that each repeat is a `STEP_BACK/FORWARD` (30 min of window, product §3.1), and focus follows the cell under the anchor.
- Every move calls `effects.ensureRows` and `effects.ensureWindow`; both are cheap and coalesced downstream.

#### 3.4.8 Five-key rule

Every v1 guide function is reachable with UP/DOWN/LEFT/RIGHT/SELECT/BACK and CONTEXT_MENU (long OK): tune (OK), full screen (OK on tuned, Back ladder), groups (Up → Group chip, or Left at the edge), favourites (Groups → Favourites entry; menu → Add to favourites), day (Up → Day chip), now (Back), reminders (panel button, sheet, menu), catch-up (OK on an archive cell, panel button, menu), hide channel (menu), group hide/move (Groups → long OK), search (Up to tabs), exit (Back ladder). (reviewed 2026-10-14, S11) Long-press is not universal (many HDMI-CEC remotes send DOWN/UP pairs with no hold and have no Menu), so the panel's **More…** button (`PanelButton.MORE`) opens the same Menu (favourite, hide, reminders, catch-up). The Groups panel's group menu (Hide/Move) stays on long OK; it is also available in M5's group manager and is listed as an exclusion below. WP-D's `FiveKeyReachabilityTest` searches the state graph with UP/DOWN/LEFT/RIGHT/SELECT/BACK (no CONTEXT_MENU) from the initial state, once per `ExitConfirmation`, and asserts every `MenuItem`, `PanelButton`, `HeaderChip`, overlay and `ScreenMode` is reached, with stated exclusions: `Overlay.Number` (an accelerator, product §2.1), `Overlay.Exit(dialog = true)` outside `ASK`, and the group menu (Hide/Move; long OK, also reachable in M5's group manager).

#### 3.4.9 Data events

| Event | State machine |
|---|---|
| `Lineup` | (the VM has already called `rowsCache.setLineup`) recompute limits; same source and focus row id still present → keep focus on that row id (index may move); else row 0 at now (or the pending focus, §3.8.2); `ensureRows`, `ensureWindow` |
| `RowsChanged` | bump `frameVersion`; if the focused row just loaded, rebuild the panel; first time the tuned row id is found, store its index |
| `Window` | (reviewed 2026-10-14, S10a) dropped when `data.lineupVersion` ≠ the current lineup's or `data.rowsVersion` is older than the last processed `RowsChanged`; else `frame.data = data`; `GridMove.RESOLVE` on the current focus (the cell may have changed, §3.3.2); panel rebuilt only if the focused cell changed |
| `Generation` | recompute limits (window from/to); drop the panel's description token |
| `DataWindow` | recompute limits (B7) |
| `SearchClosed` | mode GUIDE; `channel` → focus its row at now (switching to `All(playlistId)` if it is not in the lineup); `tuned` → FULL (B7) |
| `MissedReminders` | toast `REMINDERS_MISSED` with the count (N5) |
| `Tick` | `nowMin` changes → `frame.nowMin`, limits, the context label, the NOW chip; panel ON NOW flag; overlay countdowns (number entry, exit hint, reminder card) |
| `Tuned` | `frame.tunedHash`; `ui.tuned`; `loadTunedNow` on identity change and at each programme end; an ERROR with CATCHUP_NOT_IN_ARCHIVE while `isArchive` → add to `suspectArchive` |
| `Reminders` | `frame.reminders`; panel button REMIND ↔ REMINDER_SET |
| `Alert` | `ui.reminder` (focus on "Switch now", `inputFromMs = now + 600`); auto-switch → `answerReminder(accept)` then `tune` + FULL |
| `Settings` | logos, numbers, low-end, still, clock (re-runs the pager for time texts) |
| `Metrics` | `frame.metrics`; snapshots built at another scale are re-requested |

### 3.5 Days and labels

#### 3.5.1 Day chip and day strip

- Day chip label for `day(anchor)`: "Today", "Yesterday", "Tomorrow", else `TimeLabels.dayLabel` ("Wed 15 Oct").
- Days overlay items: every local day from `day(earliest)` to `day(latest)` (at most 14 + 1), plus a **"Tonight 20:00"** item right after Today (product §3.2). Days with no EPG coverage (`[info.windowFromMin, info.windowToMin)` does not intersect the day) are `enabled = false`: dimmed and skipped by LEFT/RIGHT. Initial focus: the anchor's day.
- Selecting a day `d ≠ today`: `target` = the epoch minute of local date `d` at the anchor's local time-of-day, computed with local date-time arithmetic in `GuideLimits` (N1: `localMidnight + minuteOfDay` is 60 min off on a DST day) (product: "keeps the current clock time"). Today → `now`. Tonight → today 20:00 local. Then the day-jump placement (§3.3.2), focus row unchanged.

#### 3.5.2 Ruler and header text

- Ticks at every :00/:30 inside the window: 1 px rule + `hm(m)` at `x + 10` (prototype `padding-left:10px`).
- The first tick label is preceded by the **contextual label** (product §3.10: "moves to the ruler's left edge"), computed from `mid = ws + 90`: `day(mid) == today` → `mid < now − 60` ? "EARLIER TODAY" : hour < 12 ? "THIS MORNING" : hour < 18 ? "THIS AFTERNOON" : "TONIGHT"; tomorrow → "TOMORROW"; yesterday → "YESTERDAY"; else the weekday in capitals. Style `RULER_CONTEXT`, colour `TEXT_LABEL`, 12 px gap before the time.
- Top-right header: date "Tue 13 Oct" (18 px, .65) and clock (34/500, `tnum`), 12 h form "7:47 PM" when `clock24` is false. The clock recomposes once a minute from its own minute-aligned flow, not from `GuideUiState`.

### 3.6 Rendering (WP-A `GuideRendererImpl`)

#### 3.6.1 Draw order (one pass, no allocation)

1. Panel: `fillRoundRect(panel, 24, PANEL_FILL)`, `strokeRoundRect(…, 1, PANEL_BORDER)`.
2. `save`; clip to the content box.
3. Ruler: ticks and labels (§3.5.2), the contextual label.
4. Clip to the rows area. For each lineup row `i` from `floor(scrollRow)` while its top < rows bottom (at most 7 during an animation):
   1. Label cell (§3.6.3).
   2. `save`; clip to the row's timeline rect.
   3. Archive band (§3.6.2).
   4. Cells overlapping `[scrollMin, scrollMin + 180]`, except the focused one (§3.6.4).
   5. `restore`.
5. Focused cell, drawn last so its ring and shadow overlap neighbours, clipped to the timeline columns of the rows area.
6. Now line (if `now ∈ [scrollMin, scrollMin + 180]`): `NOW_GLOW` asset 24 px wide centred on x, a 2 px `ACCENT` rect from `rowsTop − 6` to the rows bottom, a radius-5 `ACCENT` dot at `(x, rowsTop − 5)`. Drawn over the cells (B2.6).
7. Bubble (`bubbleNumber ≥ 0`): a 160×88 pill (`CANVAS` at .9, radius 20) over the label column, vertically centred on the focused row, number in `BUBBLE` style.
8. `restore`.

Rows with `rows.channel(i) == null` draw a skeleton: two `SKELETON` bars in the label (40×12 and 110×16) and `SKELETON` rounded rects every 60 min on the timeline. Rows whose minutes are outside the snapshot draw `LOADING` cells as `SKELETON` rects cut at :00/:30. No shimmer animation.

#### 3.6.2 Archive band and past cells (product §3.5, B2.6)

- Band: when `row.archiveDays > 0` and `now > scrollMin` (N14: otherwise the rect is negative), `fillRect(xOf(max(now − days × 1440, scrollMin)), rowTop, xOf(min(now, scrollMin + 180)), rowBottom, ARCHIVE_BAND)`. Where `now − days × 1440` is inside the window, a 1 px `TICK_RULE` boundary line marks the archive start.
- A cell is **in archive** when `row.archiveDays > 0`, the row is not in `frame.suspectArchive`, `cellRawStart ≥ now − days × 1440` and `cellEnd ≤ now`. In-archive past cells use `TEXT_ARCHIVE` (.85) and draw the 12 px ↺ glyph before the time; other past cells use `TEXT_PAST` (.45). A suspect row's glyph is `ARCHIVE_GLYPH_HOLLOW` and its past cells are `.45`.

#### 3.6.3 Label cell (200 × 60)

- Focused row (`i == focus.row`): `fillRoundRect(label, 12, ROW_FOCUS_FILL)`.
- Number (`showNumbers`): `CH_NUMBER`, `TEXT_LABEL`, at `x = 81 + 12`, baseline centred.
- Then, after a 14 px gap: the logo (§3.7) when loaded, else the name (`CH_NAME`, `FG`, ellipsised to the space left).
- Right end, inside 12 px padding: the tuned dot (7 px `LIVE` circle) when `identityHash == frame.tunedHash`; left of it the 12 px ↺ glyph when `archiveDays > 0` (product §3.5). The name's available width stops before them.

#### 3.6.4 Cells

Outer rect `[xOf(start) + 3, rowTop, xOf(end) − 3, rowBottom]`; skipped when narrower than 1 px or a sliver. Inner padding `pad = 16` (`8` when the outer width < 120).

| State | Fill | Border | Text | Extra |
|---|---|---|---|---|
| future | `CELL_FILL` | `CELL_BORDER` 1 px | `FG` | `● ` (`LIVE`) before the title when `reminders.contains(rowHash, rawStart)` |
| on now (`start ≤ now < end`) | `CELL_NOW_FILL` | `CELL_BORDER` | `FG` | progress bar: `CELL_PROGRESS`, 3 px, inset 4 px from the left/right edges and 2 px from the bottom (the prototype's bar is clipped by the 12 px radius; we inset instead of clipping to a path), width ∝ `(now − start)/(end − start)` |
| past, in archive | `CELL_FILL` | `CELL_BORDER` | `TEXT_ARCHIVE` | ↺ before the time |
| past, not in archive | `CELL_FILL` | `CELL_BORDER` | `TEXT_PAST` | — |
| GAP / NO_EPG | `CELL_FILL` | `CELL_BORDER` | `TEXT_PAST` | title "No information" (`NO_INFO`), no time line |
| LOADING | `SKELETON` | none | — | — |
| **focused** (grid zone) | `FG` | none | `CANVAS` | `FOCUS_SHADOW` asset under it (rect inflated 30 px, offset +12 y, prototype `0 12px 30px rgba(0,0,0,.4)`); ring `strokeRoundRect(rect inflated 1.5, radius 13.5, width 3, ACCENT)` (prototype `0 0 0 3px`); progress bar `ACCENT` |
| focused, focus in another zone | as its unfocused state | — | — | ring only, `ACCENT` at 50 % |

Text layout: title (`CELL_TITLE`) baseline and time (`CELL_TIME`, alpha .6) baseline are centred as a two-line block with a 3 px gap (prototype `gap:3px`); narrow cells draw the title only, centred. Text starts at `max(cellLeft, timelineLeft) + pad` (sticky titles, B2.4). Available width = `cellRight − pad − textX`; below `MIN_TEXT_W` (24) nothing is drawn.

Overflow: if `cellTitleWidth ≤ available` draw the whole title; else `(n, cutWidth) = TextFit.get(textKey, floor(available − ellipsisWidth))` (a direct-mapped cache of 512 entries filled by `measurer.fit`) and draw `title[0, min(n, title.length))` followed by `"…"` at `textX + cutWidth`.

(reviewed 2026-10-14, B4) Entries are keyed by `(textKey, availPx)` where `textKey` is a **content hash of the string itself** (`Hash64.of(title)`, computed off-main into the snapshot alongside the width; `Hash64.of(name)` for channel names): never the generation-local `textId` and never `rowId`, which both outlive a rebuild or a rename while the string changes. Each entry also stores the string length it was computed for and is ignored on mismatch; the renderer always draws `min(n, text.length)`, and clears `TextFit` when `metricsScale` or the clock form changes. (reviewed 2026-10-14, S6) `fit` backs `n` off to a grapheme boundary: never between a high and a low surrogate, and, for strings the pager flagged `CELL_COMPLEX` (`1 shl 28`: contains a combining mark, variation selector or ZWJ), to the previous `BreakIterator.getCharacterInstance` boundary (rare, cached with the entry); `TextFit` stores `(n, cutWidthPx)` so no `measureText` runs per frame. Cells with `CELL_RTL` are right-aligned: the kept text is the logical start drawn at the right edge, and the "…" sits on the left. The time line is the snapshot's `cellTimeText` (built off-main with its width, so a 12/24 h change re-runs the pager); it is drawn whole when it fits and otherwise not at all (a cut time is useless). Channel names in the label column use the same fit cache keyed by the name's content hash (B4).

#### 3.6.5 Host and animation (`GuideGridHost`)

```kotlin
@Composable
fun GuideGridHost(sm: GuideStateMachine, renderer: GuideRenderer, canvas: AndroidGuideCanvas, modifier: Modifier) {
    val scrollMin = remember { Animatable(sm.frame.windowStartMin.toFloat()) }
    val scrollRow = remember { Animatable(sm.frame.topRow.toFloat()) }
    val version = sm.frameVersion.collectAsState()  // a State object; its value is read only inside drawBehind
    // LaunchedEffect collects sm.frameVersion: on change, animateTo/snapTo the frame's targets (§3.3.3).
    Spacer(modifier /* sized to the grid panel rect, N4 */.graphicsLayer() /* default: B8 */.semantics { /* §3.6.6 */ }.drawBehind {
        version.value                              // State read in draw: invalidates the draw only
        imageTick.intValue                         // logo arrivals
        // the renderer works in window px: translate by −panelOrigin before drawing (N4)
        drawIntoCanvas { c -> canvas.bind(c.nativeCanvas); renderer.draw(canvas, sm.frame, scrollMin.value, scrollRow.value) }
    })
}
```

The composable never reads `frameVersion`, `scrollMin` or `scrollRow` in composition, so focus moves and scroll frames cause **no recomposition** (checked by I-12). (reviewed 2026-10-14, B8/F6) The node carries `Modifier.graphicsLayer()` with default parameters (no alpha, clip, shape or render effect): a plain RenderNode, so a grid redraw re-records only the grid. `GuideScreen`'s header/panel column and `AmbientBackground` carry one too. None of them is an ancestor of `VideoLayer`. The root rule (§3.13.1) forbids layers only on ancestors of `VideoLayer`. Without them, every scroll frame re-recorded the whole window (ambient, hole chrome, header, panel, footer text). WP-A confirms it on the device with a Perfetto/Compose trace: a `Trace` section around the panel's draw must not fire on a grid-only frame (I-18; the layer-scope behaviour itself is **(verify; owner WP-A, §8)**). `drawBehind` redraws only when state read inside it changes, and `drawIntoCanvas { it.nativeCanvas }` yields the `android.graphics.Canvas` (API notes §1.1, §1.2; `DrawModifier.kt:93`, `AndroidCanvas.android.kt:53`). (reviewed 2026-10-14, B1/F5) The node carries no focus or key modifiers (keys arrive through the root key sink, §2.10, §3.18.1). The `Spacer` is sized to the panel rect, not the window, so TalkBack's focus rectangle is the grid (N4).

#### 3.6.6 Accessibility (B2.7)

The grid node carries `semantics { contentDescription = text; liveRegion = LiveRegionMode.Polite; role = Role.Button }`; `text = renderer.describeFocus(frame)` updated `A11Y_DEBOUNCE_MS` (300) after the last focus change, e.g. "BBC One, channel 101, Six O'Clock News, 18:00 to 18:30, on now, catch-up available, reminder set". Other zones call `effects.announce` with the focused element's label ("Group: Sports, button"). TalkBack and Fire TV VoiceView follow D-pad focus, which is what this supports; touch exploration of cells is not supported (the View host would add `ExploreByTouchHelper`).

### 3.7 Logos (WP-A `LogoCache`)

- Slot: 96 × 40 design px, left-aligned after the number, contain-fit (aspect kept). Request: `ImageRequest.Builder(ctx).data(url).size(round(96 s), round(40 s)).scale(Scale.FIT).precision(Precision.INEXACT).build()` executed with the suspend `imageLoader.execute` in the cache's coroutine scope; `SuccessResult.image.toBitmap()` is wrapped as a `GuideImage` (API notes §5: `ImageRequest.kt:535-575`, `ImageLoader.kt:68`, `Image.android.kt:28`). Hardware bitmaps stay allowed for logos: they draw on the hardware canvas of both hosts and keep logo pixels off the Java heap on API 26+; the Robolectric adapter test and the hue request use `allowHardware(false)` (API notes §5 warns that hardware bitmaps cannot be read back).
- Cache: our LRU of `LOGO_CACHE` = 160 entries (48 in low-end mode) and `LOGO_CACHE_BYTES`, keyed by URL, plus a session `failed` set; in-flight loads are deduplicated per URL so a missing logo never launches a coroutine per frame (N3). (reviewed 2026-10-14, S8) Logo requests use `memoryCachePolicy(CachePolicy.DISABLED)`: our LRU is the cache, so the byte cap is real on API 25 where bitmaps live on the Java heap. Coil's global memory cache is capped at 2 MB (1 MB low-end) for other images; its disk cache at 64 MB under `cacheDir/logos`.
- Prefetch: when a block arrives, `get()` is called for rows `[top − 6, top + 12)` so logos are usually ready before they scroll in. `onLoaded` bumps the host's `imageTick`.
- Logos off (`showLogos = false`, "Channel logos in guide" = Off), a failed URL, or a row without `logoUrl` → the name. The focused panel, the mini player, search, the reminder card and the group card always show the name (lead §7 item 3). Logos from the EPG channel icon are not used in M3 (§8 Q4).

### 3.8 Sources, Groups panel and the accelerators

#### 3.8.1 Entries (`GuideLibrary.sources`)

(reviewed 2026-10-14, E22) The panel starts with two shortcut rows, **Search** and **Settings** (`Overlay.Groups.shortcuts`), so every top-level function is two or three presses from any row. Then, in order: every favourites list with at least one present channel (label = list name), **Recently watched** when any channel has `last_watched_at`, then for each enabled playlist in `sort_index` order: **All channels** (count = `playlist.live_count`) and its live groups from `GroupDao.groups(playlistId, 0, unlocked)` (`GroupDao.kt:23-34`, user order, hidden and locked groups excluded by its predicate) **with `channel_count > 0`** (M1 Later 1). `playlistName` is set only when more than one playlist is enabled; the panel then shows a section header per playlist. Labels use `custom_name ?: name`.

#### 3.8.2 Applying a source

`effects.loadLineup(source)` and `persistSource(source)`. On the first `Lineup` event for that source: focus lineup row 0 at now (product S7a: "focus goes to the first channel at now"), unless a pending focus (number entry into another source, `SearchClosed`'s channel) names a row id, in which case that row. The zap list given to `tune` is the current lineup (§3.16.3).

#### 3.8.3 The three accelerators (decision note "Switch sections easily") and Green

1. **Left at the channel column edge** → Groups opens with the current source focused (§3.4.2 LEFT: no earlier watchable cell; lead §7 item 4).
2. **CH+/CH− (and Page±) while Groups is open** → the next/previous entry is applied at once; the panel stays open with it focused (§3.4.4).
3. **Long-press ←/→ in the player** → GROUP_PREV/NEXT in FULL (§3.4.5).
4. **Green (FAVOURITES)** toggles between `Favourites(default list)` and the last non-favourites source. With no favourites: toast `NO_FAVOURITES` (S13) "No favourites yet. Hold OK on a channel (or use More…) to add it."

#### 3.8.4 Startup

Initial source from `GuideSettings.openGuideOn`: LAST → `GuideSessionStore.lastSource()` if it still exists, else `All(first enabled playlist)`; FAVOURITES → the default list if non-empty, else as ALL; ALL → `All(first enabled playlist)`. No enabled playlist → `EmptyKind.NO_PLAYLIST` ("No playlist yet" + next step: "Add one in Settings" in M5; in debug builds the seeding deep link is named). Initial focus: the tuned channel's row at now if present, else row 0 at now. `GuideLibrary.lastWatched()` applies `PARENTAL`, so the startup tune never plays a locked channel, and `setZapList` is called when the first lineup arrives after it (N13).

---
### 3.9 Panel, OK, menus, number entry (WP-D)

#### 3.9.1 Focused-programme panel (B2.8)

On every focus change the state machine builds `PanelState` at once from the snapshot (title, time line, ON NOW, channel number/name, catch-up days, buttons) and sets `description = null`, `genre = null`. If focus then stays `PANEL_DESC_DELAY_MS` (150) and no key is held, it calls `loadInfo(token)`; the `Info` event fills description and genre only if its token is still current. Low-end mode skips the description (title and time only) unless the panel stays 600 ms. The composable caches text layouts per `title`/`description` (`remember(key)`) and limits the description to 3 lines (B2.8). Cells without data: title "No information", time line `hm(start) – hm(end)`, description "No guide data for this channel." for `NO_EPG` and "No guide data for this time." for `GAP`.

#### 3.9.2 OK and the panel buttons

| Focused cell | Primary button (= OK) | Secondary | OK in GRID |
|---|---|---|---|
| on now, row tuned and playing | WATCHING | FROM_START if the archive covers `rawStart` | FULL |
| on now, row tuned, phase ERROR | WATCH_NOW | — | `retry()` when the error's steps contain RETRY, else FULL (the card shows the reason) |
| on now, not tuned | WATCH_NOW | FROM_START if covered | `tune` |
| past, in archive | WATCH | — | `playArchive` (FULL) |
| past, not in archive | WATCH_NOW (time line ends "· Not in catch-up") | — | tuned → FULL; else `tune` (live) |
| future PROGRAMME | REMIND / REMINDER_SET | WATCH_CHANNEL_NOW | Sheet overlay (product S7b) |
| GAP / NO_EPG containing now, or in the past | WATCH_NOW / WATCHING | — | as "on now" |
| future GAP | WATCH_CHANNEL_NOW | — | `tune` |
| LOADING / skeleton row | — | — | `tune` once the row's channel is loaded; else no-op |

(reviewed 2026-10-14, S11/E21) Every row of the table also ends with **MORE** ("More…"), which opens the context menu of §3.9.3, so favourite/hide/reminders work on remotes without long-press or Menu.

"Archive covers `rawStart`" = the row is in archive by §3.6.2 except that `cellEnd ≤ now` is not required (product §3.7: "programme start within archive"). WATCH and FROM_START both call `playArchive(channel, info)` (§3.13.6). Labels follow product §3.7: "Watch" for finished archive programmes, "From start" for the current one.

#### 3.9.3 Context menu (long OK / Menu / More…)

Items, in this order, only when they apply: WATCH (past, in archive) · WATCH_FROM_START (on now, covered) · REMIND or CANCEL_REMINDER (future PROGRAMME) · ADD_FAVOURITE or REMOVE_FAVOURITE · HIDE_CHANNEL. The menu opens on the `Favourite` event that answers `queryFavourite` (≤ 1 frame of delay in practice). Hide: `effects.hideChannel` → `channel_prefs.hidden` via the targeted upsert; the lineup re-emits without it; focus moves to the same index. "Fix guide data", "Lock channel", "Channel info" and the per-channel settings of product §4.12 are M5 and are not shown (plan §2: no "coming soon" stubs).

#### 3.9.4 Number entry (`NumberEntryImpl`)

- DIGIT opens the overlay (top right, over the header region, never the hole) and appends; at most 4 digits. Each digit restarts `NUMBER_COMMIT_MS` (1,500; product §4.4 "Number entry wait") and, after `NUMBER_PREVIEW_MS` (150) without another digit, asks `lookupNumber` for the live preview (name and logo).
- Commit (OK, timeout, or any non-digit action): `lookupNumber(n)`; not found → "No channel {n}" for 1,500 ms, then close. Found: GUIDE → if `lineupIndex ≥ 0`, focus it at now (`TO_ROW`); else switch to `All(channel.playlistId)` with a pending focus on its row id. A second OK tunes (it is the normal OK). FULL → `tune` directly.
- NUMBER_ENTRY (a remapped key, or the five-key path from M5's mapping) opens the overlay empty with a cursor digit: UP/DOWN change it, RIGHT moves on, OK commits.
- The lookup is in the source's playlist (`GuideSource` playlist, or the tuned channel's for Favourites/Recent).

### 3.10 Search (WP-B data and screen; reviewed 2026-10-14, S15)

#### 3.10.1 Channels

`ChannelRepository.ftsQuery(text)` (`ChannelRepository.kt:137-141`) builds the MATCH; `GuideSearchImpl` runs the `ChannelDao.search` join (`ChannelDao.kt:130-144`) through `GuideQueries` selecting row ids, limit 100, then loads `GuideChannel`s with the block code. Rank, best first: folded `displayName` equals the folded query; `displayName` starts with it; in any favourites list; `watch_count` desc; playlist `sort_index`; `sort_index`. Keep 30.

#### 3.10.2 Programmes

1. (reviewed 2026-10-14, B6/F2) `reader.search(q, now, limit = 150, perText = 8)` and `reader.searchPast(q, now, now − min(7, D) × 1440, limit = 100, perText = 5)`, merged, on a reader opened for the call (aux connection; closed in `finally`) with `D` from §3.3.4. The single overloaded query returned the oldest airings of the best text first (an hourly "News" filled all 200 hits with week-old airings) and starved on-now and upcoming hits.
2. Distinct refs → library channels: `SELECT playlist_id, stable_key FROM epg_mapping WHERE source_id = ? AND xmltv_id = ?` and the same on `epg_match` with `status = 0` (both indexed by v3, §2.9.1), batched 50 refs per statement; candidates are loaded with the lineup predicate (enabled, not missing, `PARENTAL`, live/radio); `EpgRefResolver` confirms each candidate's **resolved** ref equals the hit's ref (a channel with a lower-priority AUTO match to this EPG channel must not show it). At most 3 channels per airing.
3. Display times = raw + the channel's `epgShiftMin`. "Ended" is decided on the **display** end; rows flagged `CONTINUED` (a 6 h split) are skipped in past results so a long film in progress never reads as finished (N18). A hit that ended is kept only if it is in archive for that channel (§3.6.2), else dropped.
4. Rank: first the hits whose folded title equals or starts with the folded query; within that, bucket ON NOW < UPCOMING within 24 h < ARCHIVE < LATER; within a bucket, the reader's order (bm25 for FTS5), then start (ascending for upcoming, descending for archive). Dedupe `(identityHash, rawStart)`. Keep 50. `GuideSearchTest`'s fixture has an hourly title on 20 channels over 14 days and asserts at least one ON NOW and one UPCOMING hit (B6).
5. Cancellation between stages (the screen debounces 300 ms and cancels the previous search).

#### 3.10.3 Screen (S9)

Tabs row at the top (Search active), a `BasicTextField` (system IME, voice via the IME mic) and two horizontal rows: **Channels** (logo cards 220 × 124 with name and number) and **Programmes** (title, channel, time, ↺ when in archive, ● when a reminder is set). Search starts at 2 letters/digits. This screen uses Compose focus (`FocusRequester` on the field; DOWN moves into the results). OK: channel → tune, FULL; programme on now → tune, FULL; in archive → `playArchive`; future → toggle reminder (toast). Long OK on a result → a small menu (Remind/Cancel, Add/Remove favourite). BACK: results → field → GUIDE (`GuideEvent.SearchClosed(channel, tuned)`, B7: the state machine owns `mode`, so the screen always leaves SEARCH through this event; a tune from search sends `tuned = true` and the machine goes FULL). The root key sink passes every key through while SEARCH is shown, except BACK when the field is empty and the IME is hidden (§3.18.1). Empty result: "Nothing found for "{q}"" + "Try fewer words, or search a channel name." A search error (no guide data): channels still show; the programmes row says "Programme search needs guide data."

### 3.11 Reminders (WP-B book, WP-D planner and runtime, WP-C card)

(reviewed 2026-10-14, S1) Keys stay raw; firing uses the **displayed** time. Protocols research §2.6: provider EPGs are often wrong by whole hours and "+1" channels reuse the base EPG with `tvg-shift`, so the shifted time is when the programme airs on that channel. Catch-up stays raw (M1 decision 5).

#### 3.11.1 Book

`ReminderBookImpl` wraps `ReminderRepository` (`ReminderRepository.kt:22-41`): `set` → delete a non-pending row for the key (fired/dismissed: the unique index ignores state, so re-setting would silently fail, N5), then `add(identity, info.rawStartMin, rawEnd = info.rawStartMin + (info.endMin − info.startMin), info.title, channel.displayName, leadSec, shiftMin = info.startMin − info.rawStartMin)` (`ReminderRepository.add` gains the defaulted `shiftMin` parameter); `index`/`pending` from `ReminderDao.observeAllPending()` mapped to `(GuideChannel.hashOf(identity), rawStart)`. Keys are durable (`reminder` keyed by `(playlist_id, stable_key, start_min)`, `ReminderEntity.kt:9-17`), so a reminder survives EPG rebuilds; it shows its stored title.

#### 3.11.2 Planner (pure)

(reviewed 2026-10-14, S1/F7) For each pending reminder: `startMs = (rawStart + shiftMin) × 60_000`, `fireAt = startMs − leadSec × 1000`, `endMs = (rawEnd + shiftMin) × 60_000`. `now ≥ endMs` → `Expire`; `now ≥ fireAt` → `Fire(late = now > startMs + LATE_AFTER_MS)` (60 s); otherwise nothing. `nextWakeMs` = the smallest `fireAt > now`. The key stays raw, so a later shift change does not orphan the reminder.

#### 3.11.3 Runtime (`ReminderSchedulerImpl`)

- Lives in `:app` and runs while `ProcessLifecycleOwner` is at least STARTED (`androidx.lifecycle.process`, M2 §6.1). Loop: combine `pending` with a timer; `decide`; `Expire` → `markFired`; `Fire` → enqueue (one card at a time, FIFO); sleep until `min(nextWake, now + 60 s)` or until `pending` changes (so a clock change is noticed within a minute). Stops on `ON_STOP`; on the next start, the first pass catches up: late cards for programmes still on; ended ones are marked fired and counted, and the count is shown once as "Missed while The Box was closed: {n} reminders" (`GuideEvent.MissedReminders`, N5). The scheduler takes a `foreground: StateFlow<Boolean>` fed from `ProcessLifecycleOwner` so it is testable without lifecycle artefacts.
- The card is modal with a `REMINDER_CARD_GUARD_MS` (600) input guard (S2, §3.4.1). Card lifetime: until `max(startMs, firedAt) + REMINDER_CARD_MS` (60 s countdown). Auto-dismiss → `markFired`. "Switch now" → `accept` (marks fired) and the VM tunes the channel and goes FULL. Auto-switch (`GuideSettings.reminderAutoSwitch`) accepts at `startMs`. "Dismiss" → `dismiss`. A channel that no longer exists when accepted → `markOrphaned` + toast `REMINDER_CHANNEL_GONE` (S13) "{Channel} is no longer in your playlist." Writes use `setStateIfPending` so the card and the planner never race.
- Card text: "{Title} starts in 1 min on {Channel}" / late: "{Title} started {n} min ago on {Channel}". Position: bottom right, 520 × 152, above the footer, in GUIDE and FULL.
- First "Remind me" ever: toast `REMINDER_SET_FIRST_TIME` = "Reminder set. Reminders work while The Box is open." (`GuideSessionStore` flag), product §3.6.

### 3.12 Channel-name prefix (WP-B)

#### 3.12.1 Auto rule (decision note; reviewed 2026-10-14, S5 and lead §7 item 10)

Strip **one** leading tag of the forms `UK: `, `UK | `, `UK - `, `[UK] `, `(UK) `, `UK|` when the tag is an ISO-3166 code (alpha-2 or alpha-3) or `UK`, not on the exclusion list, or equals the entry's group name and has no lower-case letter. Algorithm on `name` (no regex):

1. `i` = first non-whitespace index.
2. **Bracket form:** `name[i]` is `[` or `(`: the tag is up to the matching `]`/`)` within 32 chars; the rest starts after it, skipping whitespace and then at most one separator char of `:`, `|`, `-` and whitespace again.
3. **Bare form:** otherwise the tag is `name[i, j)` where `j` is the first `:` or `|`, or the first `" - "` (hyphen with whitespace on both sides), within 32 chars; the rest starts after the separator and any whitespace. A hyphen without surrounding spaces is not a separator.
4. The tag (trimmed) qualifies if (a) it is two or three ASCII letters in `ISO_3166 (alpha-2 ∪ alpha-3) ∪ {UK}` (case-insensitive) and not in `EXCLUDED_TAGS`, or (b) it equals `groupName.trim()` case-insensitively **and contains no lower-case letter** (group = `ParsedChannel.groupTitle` at merge time, `channel_group.name` in the recompute pass). (b)'s guard keeps "Sky Sports: F1" whole: the display name also appears in favourites, Recent, search, the reminder card and the info bar, where the group context is absent.

`EXCLUDED_TAGS` (codes that are common words in channel names; lead decision): alpha-2 `TV` (Tuvalu), `FM` (Micronesia), `AM` (Armenia); alpha-3 `AND` (Andorra), `ARE` (UAE), `PER` (Peru). `US`, `IN`, `CAN`, `MAR` and every other code stay. The list is a constant next to the ISO list; changing either bumps `RULE_VERSION`.
5. The result is the rest, trimmed. If the rest is empty or has no letter or digit, the name is unchanged. Unchanged → `display_name = NULL`.

`ISO_3166` is one fixed constant list in `ChannelNamePrefixImpl` (the 249 alpha-2 and 249 alpha-3 codes as of 2026), **not** `Locale.getISOCountries()` (which `NameNormalizer` uses, `NameNormalizer.kt:22`): the platform list differs between Android versions, and a display name must not change with an OS update.

| Name | Group | Display name | Why |
|---|---|---|---|
| `UK: BBC One HD` | UK Entertainment | `BBC One HD` | ISO-like tag `UK`, colon |
| `UK \| Sky Sports Main Event` | Sports | `Sky Sports Main Event` | pipe with spaces |
| `UK\|ITV 1` | — | `ITV 1` | pipe without spaces |
| `DE - ZDF` | — | `ZDF` | spaced hyphen |
| `[FR] TF1` | — | `TF1` | bracket |
| `(es) La 1` | — | `La 1` | bracket, lower case |
| `NO: NRK1` | — | `NRK1` | Norway |
| `UK:BBC Two` | — | `BBC Two` | colon without space is accepted |
| `SPORTS: ESPN` | Sports | `ESPN` | tag equals the group |
| `Sky Sports: F1` | Sky Sports | unchanged | tag equals the group but has lower-case letters (S5) |
| `DE: DE: Das Erste` | — | `DE: Das Erste` | one tag only |
| `USA: CNN` | — | `CNN` | alpha-3 code (S5) |
| `[GBR] BBC One` | — | `BBC One` | bracketed alpha-3 |
| `AND: Then There Were None` | — | unchanged | `AND` is excluded |
| `UK-Gold` | — | unchanged | hyphen without spaces |
| `E! Entertainment` | — | unchanged | no separator |
| `BBC One - HD` | — | unchanged | tag `BBC One` is not a code or the group |
| `UK: ` | — | unchanged | rest empty |
| `UK \| ---` | — | unchanged | rest has no letter or digit |
| `TV: Live Events` | — | unchanged | `TV` (Tuvalu) is excluded |
| `FM: Radio Hits` | Radio | unchanged | `FM` excluded; tag is not the group |
| `4K: Planet Earth` | 4K | `Planet Earth` | tag equals the group |
| `4K: Planet Earth` | Documentaries | unchanged | `4K` is not a code |

#### 3.12.2 Off and Custom

`NamePrefixRules.parse(mode, customJson)` (WP-B, `ChannelNamePrefixImpl.kt`; B7): `OFF` → Off; `CUSTOM` with `{"literals":[…]}` → `Custom(literals)`, with `{"regex":"…"}` → `Custom(regex = …)`, both keys or malformed JSON → Auto (logged); anything else → Auto.

Off → always NULL. Custom literals: case-insensitive `startsWith` against the literals sorted longest first; the first match is removed, then leading whitespace and one of `:|-`; the same empty-rest rule. Custom regex: compiled once per pass, applied with `Matcher.lookingAt()` to at most the first 256 chars; a `PatternSyntaxException` or `StackOverflowError` falls back to Auto for that pass and is logged (M5's editor validates and previews counts).

#### 3.12.3 Where it runs

- **Merge:** `RoomMergeSink` gains `prefixRule: NamePrefixRule = Auto` (constructor default keeps tests compiling); `RefreshRepository` passes `NamePrefixRules.parse(playlist.namePrefixMode, playlist.namePrefixCustom)` (`RefreshRepository.kt:136-140`). `display_name` joins the `CONTENT` columns (`RoomMergeSink.kt` `CONTENT`), bound from `strip(ch.name, ch.groupTitle, rule)`. Touches do not change it (name and group are part of the content hash). (reviewed 2026-10-14, S4) `finish` sets `name_prefix_ver = RULE_VERSION` **only if the stored value already equals `RULE_VERSION`**; otherwise it leaves it for `DisplayNames.recompute` (untouched rows were not rewritten, so stamping would make the backfill skip the playlist forever when a start-up refresh wins `ImportMutex` first).
- **Recompute pass** (`DisplayNames.recompute(playlistId)`): under `ImportMutex` (the merge holds it too, so they never interleave), page by id ranges (N15: `… WHERE c.playlist_id = ? AND c.id > ? ORDER BY c.id LIMIT 2000` over `channel c LEFT JOIN channel_group g ON g.id = c.group_id`, selecting `c.id, c.name, c.display_name, g.name`), compute, and `UPDATE channel SET display_name = ? WHERE id = ?` only where it changed, one transaction per page; then `setNamePrefixVer`. The startup backfill runs after the first frame. Budget: 20k channels ≤ 2 s on the emulator.
- **Triggers:** app start (`TheBoxApplication`, after `EpgRecovery`) runs it for every playlist with `name_prefix_ver < RULE_VERSION`; M5's setting calls `setNamePrefix` then the pass. The lineup flow re-emits through the invalidation tracker.
- `channel_fts` keeps the raw name (typing "uk" still finds "UK: BBC One"), and `StableKey`/matcher inputs are untouched (decision note: display name only).

### 3.13 Hole punch, mini player, full screen, catch-up (WP-C, WP-D)

#### 3.13.1 Root tree (B1.2, M2 §3.11)

```kotlin
Box(Modifier.fillMaxSize()) {                       // at the window origin (M2 E-L18): VideoLayer places in window px
    AmbientBackground(state.ambient, holeRect, mode)   // draws the base, the ambient bitmap and the hole shadow
    VideoLayer(controller.surfacePlayer, videoRect)    // the one SurfaceView, behind the window; clears its rect
    HoleChrome(holeRect, videoRect, ambient, mode)     // corners, ring, letterbox, still/poster, scrim
    Box(Modifier.fillMaxSize().then(keyRouter.sinkModifier(requester))) {   // B1: the one key sink, survives every mode change
        when (ui.mode) { GUIDE -> GuideScreen(…) ; FULL -> FullScreenHost(…) ; SEARCH -> SearchScreen(…) ; SETTINGS -> SettingsPlaceholder(…) }
        ReminderCardHost(…); ToastHost(…)
    }
}
```

(reviewed 2026-10-14, B1) The key-sink `Box` is a sibling after `HoleChrome`, wraps the screens and is **not** an ancestor of `VideoLayer`; it adds no layer, alpha or clip. `requester.requestFocus()` runs from `LaunchedEffect(ui.mode)`, on `Lifecycle.Event.ON_RESUME` and after `SearchClosed`. No node in this tree applies `graphicsLayer`, `alpha`, `clip`, `shadow` or `RenderEffect` to a parent of `VideoLayer`; siblings may carry a default layer (B8: `AmbientBackground`, the header/panel column, the grid host). Whether Compose's `AndroidView` wraps the `SurfaceView` in its own layer is **(verify; owner WP-C, §8)**: I-2/I-3 and D1 cover it (I-2 checks the semantics/layout tree on the device; a Robolectric test walks the modifier chain of the root). Screens fade their own content, never the root. **Overlays must not intersect the hole in GUIDE** (I-3 asserts the bounds of every overlay against `holeRect`); the only things drawn over the video are the mini player's own pills and bottom panel.

#### 3.13.2 Rects

`holeRect` from `GuideMetrics.holeRect` (§3.1). `videoRect = VideoRectCalculatorImpl.place(hole, video.width, video.height, video.pixelRatio, video.aspectMode)` (`VideoRectCalculator.kt:20-47`) for MINI; for FULL the hole is the whole window `(0, 0, W, H)`. `controller.setViewport(MINI | FULL | HIDDEN)` follows the mode: GUIDE → MINI (HIDDEN when the mini is a still, §3.13.4), FULL → FULL, SEARCH/SETTINGS → HIDDEN (they cover the hole; audio continues, M2 §3.11).

#### 3.13.3 `HoleChrome`

Drawn after the surface, so it composites over the video:
1. Black (`#000`) in `hole − videoRect` (letterbox, B1.5b).
2. The four corner regions `hole − roundRect(hole, 20)`: the current ambient bitmap drawn clipped to an even-odd `Path` (rect minus round rect), with the same cross-fade alpha as `AmbientBackground` so the corners do not pop for 1.2 s on a tune (N9), so anti-aliased corners blend background over video (B1.1). The path is rebuilt only when `holeRect` changes.
3. The ring: `strokeRoundRect(hole, 20, 1 px, white .14)`.
4. The still (§3.13.4) or the scrim (§3.13.5) when active.

The shadow `0 30 80 rgba(0,0,0,.55)` is outside the hole: `AmbientBackground` draws a pre-rendered shadow bitmap (made once at startup with a software `BlurMaskFilter` into an offscreen bitmap, never per frame) under where the hole will be; the `SurfaceView` clears only its own rect, so the shadow survives.

#### 3.13.4 Mini player overlays (prototype `previewEl` block; product §3.8, §2.4)

- Top-left pills (20, 18): state pill and `CH {number}` pill (`rgba(0,0,0,.45)`, no blur). State pill: **LIVE** (pulsing `LIVE` dot, 1.6 s; the pulse is a 1 Hz alpha step, not a per-frame animation, in low-end mode), **CATCH-UP** (accent) when `isArchive`, "Connecting…" while CONNECTING, "Reconnecting {n}/{max}" while RECONNECTING. PAUSED and "−MM:SS" are M4.
- Bottom panel: gradient (`0 → .75` black), now title (30/600, `tunedNow`; while `isArchive`, the archived programme's `ProgrammeInfo`, not `at(channel, now)`, N17), channel name (18, .7), time row `start — progress — end` (mono 14, 4 px bar). Updated on `Tick` and on `TunedNow`.
- **Still** (nothing tuned, Still setting/low memory, HDR poster): logo centred on the ambient hue (the ambient bitmap clipped to the hole), the now title, and "Press OK to watch". When a still is shown while something is tuned, the viewport is `HIDDEN`: M2's `TrackController` disables the video track (no decode, no surface output), audio keeps decoding and the connection stays (lead §7 item 9). FULL re-enables the track; the first frame then waits for a keyframe (up to the GOP length), covered by the scrim (§3.13.5). `VideoInfo.posterMode` (`PlaybackApi.kt:120`) also shows the still with "HDR · Press OK for full screen".
- **Compact error card** (phase ERROR): logo, amber dot, headline and a one-line next step from `ErrorText` (`PlaybackErrorCode` → headline + step; every one of the 25 codes, `PlaybackContracts.kt:129-155`, has both; a unit test enforces completeness). The step for codes whose first `NextStep` is RETRY reads "Press OK to retry"; others name where to go ("Check your account in Settings", M5).

#### 3.13.5 Full screen (minimal M3) and the scrim

GUIDE → FULL: `HoleChrome` fades a black scrim over the hole in `SCRIM_MS` (120 ms); then mode FULL in one layout pass (viewport FULL, `videoRect` = full-window placement, guide composables leave, background black); after two frames (`withFrameNanos` ×2) the full-screen scrim fades out in 120 ms (B1.5a). Coming from a still (viewport HIDDEN), the scrim stays until the next first-media milestone (`PlaybackState.Playing` after the re-enabled track renders) or 2 s, because re-enabling video waits for a keyframe (N6). FULL → GUIDE is the same in reverse. Full screen in M3 shows only the video, the group card (accelerator 3), the compact error card centred when in ERROR, number entry and the reminder card; the info bar, channel list and pickers are M4.

#### 3.13.6 Catch-up launch (`CatchupLauncherImpl`, WP-D)

1. `archiveTargets.catchupTarget(identity)` (§2.8; reviewed 2026-10-14, S13): `ChannelGone` → `CHANNEL_GONE`; `CredentialsUnavailable` → `CREDENTIALS`; `Found(target)` → continue.
2. `target.catchup.isAvailable` and `info.rawStartMin ≥ now − days × 1440`, else `NO_ARCHIVE` / `OUTSIDE_ARCHIVE`.
3. `timeBase = PlaylistRepository.timeBase(playlist)` (`PlaylistRepository.kt:159`); `window = ArchiveWindow(programmeStart = rawStart, programmeStop = rawEnd if ended else null, deviceNow = now)`; `CatchupUrlBuilder.build(target, timeBase, window)` (default variant = the stored probe winner, `CatchupContracts.kt` `CatchupTarget.winner`).
4. `Ok` → `controller.tuneArchive(ArchiveTune(identity, request))` and FULL; `Unsupported` → `UNSUPPORTED_MODE`.
5. Failures become toast `CATCHUP_FAILED` with `arg = CatchupFailure.name` (S13); `ErrorText` maps each to a reason and a next step ("This channel has no catch-up" / "This programme is older than the channel's {n}-day archive" / "Couldn't build a catch-up link for this channel's catch-up mode. Try another mode in playlist settings."). A playback error with `CATCHUP_NOT_IN_ARCHIVE` while `isArchive` adds the channel to `frame.suspectArchive` for the session (hollow ↺, product §3.5).

### 3.14 Ambient background (WP-C)

- Hue: the tuned channel's logo, loaded once at 16 × 16 with `allowHardware(false)` (`imageRequests.android.kt:299`, API notes §5); `HueExtractor` takes the pixels (`IntArray`), drops pixels with alpha < 128, saturation < .25 or lightness outside .15–.9, and returns the circular mean hue weighted by saturation; fewer than 8 usable pixels → fallback `(GuideChannel.hashOf(identity) ushr 1) % 360`. Cached in an LRU of 64 by identity hash; not persisted (§7 Q6).
- Bitmap: 480 × 270 ARGB_8888 rendered on `Dispatchers.Default`: base `#0A0D13`; blob A = `oklch(.45 .12 h)` ellipse 1300 × 1000 at (−200, −260), opacity .55; blob B = `oklch(.40 .10 h+40)` 1100 × 900 at (right −300, top 200), opacity .35 (01-design-reference "Ambient background"); each as a `RadialGradient` with 8 stops along `exp(−r²/2σ²)` where σ = blur/2 (B1.4), scaled to the 480 × 270 canvas; then the vertical overlay `0 → .6 @45 % → .92`. `Oklch.toArgb` converts (OKLab → linear sRGB → gamma; out-of-gamut channels clamped).
- Drawing: `drawImage(bitmap, dstSize = window, filterQuality = Low)`; a hue change cross-fades the previous and next bitmaps over `AMBIENT_FADE_MS` (1,200) by alpha in the draw phase. Low-end or Ambient off → a flat `#0A0D13` fill.

### 3.15 Screen layout (WP-C)

| Element | Rect (design px) | Notes |
|---|---|---|
| Wordmark | (832, 56), height 28 | white on dark, vector (§3.17) |
| Tabs | from x = 832 + wordmark + 24, top 56 | pills 18 px, padding 8 × 18, active = 12 % fill + 16 % border; focused (TABS zone) = solid `FG` with dark text + accent ring |
| Sync chip | after the tabs, gap 16 | `SyncFeed.state` (B7; `:data` maps `SyncStatusRepository.status`, `SyncStatusRepository.kt:15-58`): "Updating guide…"/progress line while busy; "Guide update failed. Details arrive with Settings." when failures are unacknowledged (N11: the chip is not focusable and there is no details screen in M3) |
| Date + clock | right-aligned at 1856, top 56 | §3.5.2 |
| Panel meta row … buttons | bottom-aligned in (832, 56) – (1856, 461), gap 16 | prototype; buttons padding 14 × 26, radius 14; focused button = `FG` fill + accent ring; the secondary uses `rgba(255,255,255,.1)` with no blur (B1.3) |
| Grid | §3.1 | the host |
| Group chip | label column of the header row: (93, 507), max 184 × 36 | pill with the source label and ▾ |
| Now chip, Day chip | right-aligned in the header row, ending at 1827 | pills, 36 high |
| Groups panel | (64, 500) – (424, 960) | over the label column (S7a: 360 wide, full grid height) |
| Days strip | (281, 501) – (1839, 549) | over the ruler |
| Menu / Sheet | anchored to `renderer.focusedCellBounds`, below the cell if it fits above y 960, else above it but never above y 470 | clamped to the grid panel |
| Number entry | top right (1520, 72), 320 × 160 | over the header region |
| Footer hints | (64, bottom 40), 17 px .55, key caps mono 14 | §3.18.3 |
| Reminder card | right 64, bottom 96, 520 × 152 | |
| Toasts | left 64, bottom 96 | never steal focus |

### 3.16 View-model glue (WP-C)

#### 3.16.1 `GuideViewModel`

`@HiltViewModel`, injected with `PlaybackController`, `ArchiveTargets`, `CatchupLauncher`, `GuideLibrary`, `GuideRowsCacheFactory`, `GuideWindowPagerFactory`, `GuideSettingsSource`, `GuideSessionStore`, `ReminderBook`, `ReminderAlerts`, `KeyCapabilities`, `GuideStateMachineFactory`, `GuideNavigator`, `TimeLabels`, `GuideRenderer`, `DeviceClassSource`, `SyncFeed`, `GuideDataWindowSource`, `GuideDebugProbe` and `KeyRouter` (reviewed 2026-10-14, B7: all `:core`/`:ui` types; no `:data` type reaches `:ui`). It registers its machine with `GuideDebugProbe` and attaches it to the `KeyRouter`. It creates the state machine with `GuideEffectsImpl(this)` and forwards: library flows → `Sources`/`Lineup`; `library.rowContent` → `rowsCache.reloadLoaded()` (B5); rows cache `changes` → `RowsChanged`; `SyncFeed` → `Sync`; `GuideDataWindowSource` → `DataWindow`; pager `snapshots`/`generation` → `Window`/`Generation`; `controller.state` + `controller.video` → `Tuned` (§3.16.2); reminders → `Reminders`/`Alert`; settings → `Settings`; a minute-aligned ticker (plus 250 ms ticks while a countdown or the exit hint shows) → `Tick`. `onCleared` closes the pager. `GuideSessionStore` and `keys_seen` are read on IO at creation, never on main (N7). At creation it starts the startup tune (product §4.4 "Guide with last channel"): `library.lastWatched()` → `controller.tune`, in parallel with the first composition (architecture review C9), after `controller.prewarm()` has run in `MainActivity.onCreate` (M2 E-L17).

#### 3.16.2 `PlaybackState` → `TunedState`

| `PlaybackState` (`PlaybackApi.kt:100-111`) | `MiniPhase` |
|---|---|
| `Idle` | IDLE |
| `Connecting` | CONNECTING |
| `Buffering(cause = RECONNECTING)` | RECONNECTING (attempt/max from `reconnect`) |
| `Buffering` (other) | BUFFERING |
| `Playing(paused = false)` | PLAYING |
| `Playing(paused = true)` | PAUSED |
| `Stalled` | RECONNECTING (attempt/max) |
| `Stopped` | STOPPED |
| `Error` | ERROR (`error.code`) |

`identity` = `state.target?.identity`; `isArchive` = `target.isArchive`; `still` = `video.posterMode || settings.miniStill || device.stillByDefault`; `rowId` via `library.rowIdOf` (cached per identity).

#### 3.16.3 Effects to the controller

`tune(channel, lineup)` → `controller.tune(TuneTarget.Channel(identity), zapList = LineupZapList(lineup.rowIds, library))`. (reviewed 2026-10-14, S9) `LineupZapList` holds the **captured** `rowIds` and never touches the rows cache (which indexes the current lineup and is main-only): `id = "lineup:" + lineup.version`; `neighbour(from, delta)` resolves `from` with `library.rowIdOf` and a scan (position 0 when absent), steps `delta` with wrap, and loads the target with `library.channels(rowIds, i, i + 1)` on IO, skipping ids that come back null (purged). The lineup predicate already excludes hidden/locked rows (`PlaybackApi.kt:75-76`). `zap` → `controller.zap`; `previousChannel` → `controller.previousChannel()`; `retry` → `controller.retry()`; `setFullScreen` → viewport + scrim sequence. A source change while something plays calls `controller.setZapList` with the new lineup (Up/Down in FULL then zaps within it).

### 3.17 Branding assets (WP-E; reviewed 2026-10-14, S15; decision note "Branding")

`tools/branding/make-assets.sh` (committed; outputs committed; it is not part of the Gradle build). Inputs: `design/logo/The_Box.png`. Tools: `potrace` and ImageMagick (MacPorts or Homebrew; not installed on the build machine today, the script checks and says how to install). Steps: threshold and invert the PNG → trace with `potrace --svg` → convert the SVG path to an Android `<vector>` (a small Python step that copies `d` attributes and the viewBox) → `ui/src/main/res/drawable/wordmark.xml` (white), `app/src/main/res/drawable/ic_launcher_foreground.xml` (the box frame with "Box" only, cropped from the trace by bounding boxes, inside the 66 dp safe zone of a 108 dp canvas), `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive: background `#0A0D13`, foreground the vector), and `app/src/main/res/drawable-xhdpi/banner.png` 640 × 360 (white wordmark on `#0A0D13`, 48 px = 24 dp padding, no gradient) rendered from the SVG with `rsvg-convert` (installed at `/opt/local/bin`). The manifest keeps `android:banner="@drawable/banner"` and points `android:icon` at `@mipmap/ic_launcher`; API 25 uses `mipmap-xhdpi/ic_launcher.png` rendered by the same script. If tracing quality is poor at small sizes, the fallback is a 2× PNG of the white wordmark in `drawable-nodpi` drawn with `FilterQuality.High`. The black-on-white original stays for store listings and the phone setup page.

### 3.18 Input (WP-D)

#### 3.18.1 Gesture detection (`KeyGestureDetectorImpl`)

Per key code, state `{downAt, longFired, repeats}`.
- DOWN, `repeatCount == 0`: remember `downAt`. Key without a long binding → `Press(repeat = 0)` now. With one → nothing yet, and the router arms a `LONG_PRESS_MS` (500) timer that calls `onTimer`.
- DOWN, `repeatCount > 0`: without a long binding → `Press(repeat)`; with one → `LongPress` once (`longFired = true`) when `eventTime − downAt ≥ LONG_PRESS_MS` **or** this is the first repeat: tv-material treats `repeatCount == 1` as the long click (`SurfaceImpl.kt:125-187`) and the platform sets `FLAG_LONG_PRESS` on it (API notes §2.2), so whichever comes first wins.
- Timer: the key is still down and no `LongPress` fired → `LongPress`. This covers Bluetooth remotes and Fire OS builds that do not auto-repeat DPAD_CENTER at all (API notes §2.2).
- UP: with a long binding and not `longFired` → `Press(0)` (the timer is cancelled); after a `LongPress` the UP is swallowed so OK does not also tune; without a long binding and `repeats > 0` → `ReleaseAfterRepeat`. `canceled` → nothing.
- A repeat or UP for a key without state (focus arrived mid-press; Compose drops unmatched UPs, API notes §2.1) is ignored; `reset()` runs when the guide root loses focus.

(reviewed 2026-10-14, B1/F5) `KeyRouter` (`:ui`, WP-D; contract in §2.10) turns a Compose `KeyEvent.nativeKeyEvent` into `KeyInput`, records the key in `KeyCapabilities` (only when first seen), runs the detector with `hasLong = keyMap.long(context, code) != null` (`context` = PLAYER in FULL, else GUIDE), maps gestures to `BoxAction`s through `KeyMap`, and dispatches `GuideEvent.Act(action, repeat, digit, heldMs)`. `ReleaseAfterRepeat` → `Release`. WP-D ships the sink (`KeyRouter.sinkModifier(requester)`, called `Modifier.boxKeySink` in the review) = `focusRequester(requester).onPreviewKeyEvent(router::onKey).focusable()`. WP-C applies it **once**, on a `Box(Modifier.fillMaxSize())` that is a sibling after `HoleChrome` and wraps the `when (ui.mode)` screens, so it survives every mode change (§3.13.1). It adds no layer, alpha or clip, and is not an ancestor of `VideoLayer`. The router returns `true` for every key it handled, including the matching KeyUp; unhandled keys (volume) return `false`. In `ScreenMode.SEARCH` it returns `false` for every key except BACK while the search field is empty and the IME is hidden. `requester.requestFocus()` runs from `LaunchedEffect(ui.mode)`, on `Lifecycle.Event.ON_RESUME`, and after `SearchClosed`. `detector.reset()` runs when the sink loses focus. Before B1 the focus target was the grid host, which leaves composition in FULL: Back then reached the Activity and exited the app.

#### 3.18.2 Default map (`DefaultKeyMap`; M5's mapping screen edits it)

| Key(s) | GUIDE short | GUIDE long | PLAYER short | PLAYER long |
|---|---|---|---|---|
| DPAD_UP/DOWN | UP/DOWN | — | UP/DOWN | — |
| DPAD_LEFT/RIGHT | LEFT/RIGHT | — | LEFT/RIGHT | GROUP_PREV/GROUP_NEXT |
| DPAD_CENTER, ENTER, NUMPAD_ENTER, BUTTON_A | SELECT | CONTEXT_MENU | SELECT | CONTEXT_MENU |
| MENU | CONTEXT_MENU | — | CONTEXT_MENU | — |
| BACK, ESCAPE, BUTTON_B | BACK | — | BACK | — |
| CHANNEL_UP / CHANNEL_DOWN | ZAP_UP / ZAP_DOWN | — | ZAP_UP / ZAP_DOWN | — |
| PAGE_UP / PAGE_DOWN | PAGE_UP / PAGE_DOWN | — | ZAP_UP / ZAP_DOWN | — |
| MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE | PLAY_PAUSE | — | PLAY_PAUSE | — |
| MEDIA_FAST_FORWARD / MEDIA_REWIND | SEEK_FORWARD / SEEK_BACK | — | same | — |
| 0–9, NUMPAD_0–9 | DIGIT | — | DIGIT | — |
| PROG_RED / GREEN / YELLOW / BLUE | START_OVER / FAVOURITES / AUDIO / PREVIOUS_CHANNEL (product §2.1) | — | same | — |
| LAST_CHANNEL | PREVIOUS_CHANNEL | — | same | — |
| GUIDE | OPEN_GUIDE | — | OPEN_GUIDE | — |
| INFO | INFO_BAR | — | INFO_BAR | — |
| CAPTIONS / MEDIA_AUDIO_TRACK / TV_ZOOM_MODE | SUBTITLES / AUDIO / ASPECT | — | same | — |
| MEDIA_RECORD | REMIND (v1; Record in v2) | — | — | — |
| SEARCH | not bound in M3 (§8 Q3) | — | — | — |

Only DPAD_CENTER-family keys and, in PLAYER, LEFT/RIGHT have long bindings, so every other key acts on DOWN with auto-repeat (lowest latency for D-pad browsing).

#### 3.18.3 Footer hints

Built by the state machine from `KeysSeen` (`KeyCapabilities.seen`, persisted, product §2.1: "keys the device has actually reported"): always `← → ↑ ↓ Browse`, `OK Watch`, `Hold OK Options`; plus `Red Remind` when PROG_RED was seen (REMIND context: future programme focused) or `Red From start`; `CH± Page` when CHANNEL_UP was seen; `0–9 Channel` when a digit was seen. The design's `R Record` is gone (product §3.6). `KeyCharacterMap.deviceHasKey` is not used to show hints (TV builds report keys their remotes lack), only for diagnostics.

---
## 4. Rendering budget

### 4.1 Draw operations per frame

Reference frame: 6 full rows + 1 partial (during a vertical animation), 180-min window, average 6 cells per row (30-min programmes), logos on, one row tuned, now line visible.

| Part | Ops | Count |
|---|---|---|
| Panel fill + border | 2 | 2 |
| Ruler: 7 rules + 7 labels + context label | 15 | 15 |
| Label cell per row: focus fill (1 row), number, logo or name, ↺, tuned dot | ≤ 5 | 35 |
| Archive band + boundary per row | ≤ 2 | 14 |
| Cell: fill, border, title (+ "…"), time, ↺ or ●, progress | ≤ 7 (typ. 4) | ≤ 294 (typ. 170) |
| Focused cell: shadow, fill, ring, title, time, progress | 6 | 6 |
| Now line: glow, line, dot | 3 | 3 |
| save/clip/restore | 2 per row + 4 | 18 |
| **Total** | | **≤ 387, typical ≈ 260** |

These are `RecordingCanvas` (HWUI display-list) ops issued on main; HWUI batches rects and glyph runs on the RenderThread. Budget for `GuideRenderer.draw` on main: ≤ 2 ms on the emulator per frame (P90, logged by a debug `Trace` section `guide-draw`), and ≤ 0.3 ms per frame on the JVM against the counting canvas (WP-A perf test, tagged `perf`).

### 4.2 Allocation rules

- **Zero allocations in `GuideRenderer.draw`, `focusedCellBounds` and `AndroidGuideCanvas`.** No `String` building (titles, times and labels are pre-built; ellipsis is a ranged `drawText` plus a constant `"…"`), no boxing (primitive maps from `androidx.collection`), no iterators, no lambdas capturing per frame, preallocated `Paint`s/`RectF`/`Path`. WP-A's `RendererAllocationTest` draws the reference frame 1,000 times and asserts `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` grows by < 1 KB.
- **Per key event:** at most a `GridFocus`, a `GuideUiState` copy (only when a field of it changed) and a `PanelState`. The 20 Hz key repeat on Android TV therefore allocates < 20 KB/s.
- **Per snapshot (off-main):** arrays recycled from two publications back; new `String`s only for time texts of new programmes.

### 4.3 Text

| Text | Measured | Cached |
|---|---|---|
| Programme titles | once per `textId` on `box-guide-epg` | pager `textId → width` (≤ 8,192) |
| Ellipsis cut | on first draw at a given width | `TextFit` direct-mapped 512 `(textId, px)` (titles) and 256 `(rowId, px)` (names) |
| Cell time line | at snapshot build | in the snapshot |
| Ruler labels, `hm` | once per zone/clock change | `TimeLabels` tables (1,440 strings × 2 forms) and their widths |
| Panel title (64 px, balanced) / description (21 px, 3 lines) | Compose `Text` | `remember(title)` / `remember(description)`; description only after 150 ms idle (B2.8) |

Balanced line breaking for the 64 px title uses `LineBreak.Heading` where available (API 33+) and the default elsewhere (B2.8 notes older APIs fall back silently). **(verify)** `TextStyle(lineBreak = LineBreak.Heading)` on BOM 2026.09.00.

### 4.4 Images

| Image | Size (1080p) | Count | Memory |
|---|---|---|---|
| Logos | 96 × 40 | ≤ 160 (48 low-end) and ≤ `LOGO_CACHE_BYTES` 4 MB (2 MB low-end) | hardware bitmaps on API 26+; Java heap on API 25 |
| Ambient | 480 × 270 | 2 during a cross-fade | 1.0 MB |
| Hole shadow | hole + 2 × 80 px margin at ¼ scale | 1 | ≈ 60 KB |
| Focus shadow, now glow, ↺ ×2, lock | small | 5 | < 50 KB |

At 4K output (`s = 2`) the logo slot is 192 × 80 and the byte cap, not the count, binds.

### 4.5 What is drawn behind the hole

Nothing of the guide: the grid panel starts at y 500 and the hole ends at y 461 (B1.3); the header region starts at x 832 and the hole ends at x 784. `AmbientBackground` draws the full window (the `SurfaceView` clears its rect), so there is no special case. Inside the hole only `HoleChrome` (corners, ring, letterbox, still, scrim) and the mini overlays draw. No guide overlay may intersect the hole in GUIDE (I-3).

### 4.6 Memory budget (guide, Java heap, low-end device)

| Item | Budget |
|---|---|
| Lineup (`LongArray`, 20k rows) | 160 KB |
| Row blocks (256 `GuideChannel`) | ≤ 200 KB |
| Programme snapshots (fresh per publication, B3; ≈ 300 cells typical, ≤ 2,000 worst case; at most 2–3 live at once) + `EpgWindow` | ≤ 600 KB |
| Reader title LRU (`EpgReaderImpl.TITLE_CACHE` 4,096) | ≤ 600 KB |
| Width/fit caches | ≤ 120 KB |
| Logos on API 25 (byte cap) | ≤ 2 MB |
| Ambient + assets | ≤ 1.2 MB |
| Coil's own memory cache (other images only; logos bypass it, S8) | ≤ 1 MB low-end (2 MB otherwise) |
| **Guide total** | **≤ 5 MB** retained after a full scroll journey (≤ 6 MB including Coil's cache); no growth across 5 repetitions (I-15); an API 25 check of the logo heap joins the M6 device list |

### 4.7 Trim (reviewed 2026-10-14, S8)

`MainActivity` registers `ComponentCallbacks2` and fans `onTrimMemory` out to the guide (WP-C wiring; each owner clears its own cache):
- `TRIM_MEMORY_RUNNING_LOW` and above: clear `LogoCache` (WP-A), `TextFit` (WP-A), the pager width cache (WP-B), row blocks outside `[top − 6, top + 12)` (WP-B), the non-current ambient bitmap and the hue cache (WP-C).
- `TRIM_MEMORY_UI_HIDDEN`: all of the above plus the current snapshot (the pager re-queries on return).

---

## 5. Tests

### 5.1 WP-A (JVM, JUnit 5; Robolectric for the adapters)

| Test | Cases |
|---|---|
| `GuideMetricsTest` | §3.1 table at s = 1, 0.6667, 2; per-row rounding never drifts; hole rects; `xOf` of the prototype's now line (687.8 at 19:47) |
| `RowCellsBuilderTest` | empty row → NO_EPG hour blocks; gap split at hours (19:47–21:10 → 3 cells); sliver flagged; overlap clipped; nested programme skipped (S10b); continuation merged; programme crossing both edges; DST day (Europe/London, 2026-03-29 and 2026-10-25) hour cuts |
| `GuideNavigatorTest` | every row of §3.4.2 for LEFT/RIGHT/UP/DOWN/PAGE/TIME/STEP/TO_ROW/TO_TIME/RESOLVE; LeftEdge from the on-now cell on a row without archive and at the archive limit on an archive row (lead §7 item 4), STEP_BACK past it; LeftEdge at `earliest`; TopEdge; Blocked; long programme on Left (window jumps to its start); sliver skip; LOADING cells outside coverage; RESOLVE after a snapshot whose cell boundaries changed; anchor invariants (§3.3.2) checked by a property test over 10,000 random walks on random rows |
| `TimeLabelsTest` | 24/12 h, DST, contextual labels at each boundary, day labels; zero allocation for `minuteOfDay`/`dayOf`; concurrent `reset` while another thread reads (S12) |
| `GuideRendererTest` (recording canvas, fake measurer: width = chars × size × 0.5) | op order of §3.6.1; focused cell last; sticky title x; ellipsis cut; past/archive/suspect colours; reminder dot; now line x; skeleton rows; narrow/tiny cells; nothing drawn above `panelTop`; (B4) generation swap with the same `textId` and a shorter title, and a rename to a shorter channel name: no exception, correct cut; (S6) emoji/surrogate pair at the cut, an Arabic title right-aligned with the ellipsis on the left; `now < scrollMin` draws no band (N14) |
| `RendererAllocationTest` | §4.2 |
| `GoldenFrameTest` (Robolectric `@GraphicsMode(NATIVE)`, N10) | the §4.1 reference frame rendered through `AndroidGuideCanvas` into a `Bitmap` with the bundled fonts, compared to one checked-in PNG (no new dependency; API notes §7) |
| `RendererPerfTest` (`perf`) | 1,000 frames ≤ 0.3 ms each |
| `AndroidGuideCanvasTest` (Robolectric) | draws into a `Bitmap`; pixel probes for fill, ring and text presence; `AndroidTextMeasurer.fit` agrees with `Paint.breakText` |

### 5.2 WP-B (JVM for `:core`, Robolectric for `:data`)

| Test | Cases |
|---|---|
| `ChannelNamePrefixTest` | every row of the §3.12.1 table; Custom literals and regex; bad regex fallback; 256-char cap |
| `MergeDisplayNameTest` | merge binds `display_name`; NULL when unchanged; group rename changes it; `name_prefix_ver` stamped only when already current; upgrade: merge before backfill → backfill still runs (S4) |
| `DisplayNamesTest` | recompute updates only changed rows; under `ImportMutex`; startup backfill for ver 0 |
| `LibraryV3MigrationTest` | v2 → v3 Robolectric open-and-read + instrumented `MigrationTestHelper` (commit 0) |
| `LineupQueryTest` | each source's order equals the M1 browse query on the same fixture; hidden/locked/missing/disabled excluded; version stable when nothing changes; re-emits after a favourite add |
| `RowBlockTest` | numbering modes; custom name/number/logo; catch-up days via the resolver; `EpgRefResolver` equals `EpgRepository.resolvedRefs` on 1,000 random fixtures; (B5) matcher writes `epg_match` → `rowContent` ticks → the loaded block re-emits with the ref; a custom-name edit reloads the block |
| `GuideWindowPagerTest` (real `EpgStoreImpl` from testFixtures `EpgTestDb`) | coverage and margins; coalescing (100 `ensure`s → ≤ 2 queries); PENDING rows re-query; generation swap mid-scroll (new snapshot generation, old reader closed on the epg thread, file deleted, no `IllegalStateException`); patch re-resolve; BUSY retry with a throwing fake reader and `isBusy` on constructed messages (codes 5, 261, 517, and a non-busy 1, S7); no guide data → NO_EPG rows; fresh arrays per snapshot (B3); hold bias rows `[top − 2, top + 24)` (S16); re-query of rows whose `(epg, shift)` basis changed (B5) |
| `GuideSearchTest` | ranking table; past hits only in archive; resolved-ref confirmation; parental filter; 3 channels per airing cap; CONTINUATION filtered; (B6) hourly title on 20 channels over 14 days → at least one ON NOW and one UPCOMING hit; `searchPast` newest first with the per-text cap |
| `SearchViewModelTest` | debounce 300 ms, cancellation, 2-char minimum, `SearchClosed` on Back and on tune (moved from WP-C, S15) |
| `ReminderBookTest` | set/cancel/index; duplicate pending set returns false; re-setting a fired/dismissed reminder succeeds (N5); `shift_min` stored (S1) |
| `WindowQueryPerfTest` (`perf`) | 20k channels × 7 days (3.4M rows, `EpgTestDb` generator), 18 rows × 9 h: warm ≤ 5 ms, cold ≤ 30 ms (plan §6 targets, checked on the JVM as a regression guard; the device number is logged in I-5) |

### 5.3 WP-C (Robolectric + Compose UI test)

`RootTreeRuleTest` (no `graphicsLayer`/`alpha`/`clip` modifiers on any **ancestor** of `VideoLayer`, by walking the modifier chain of the root's `LayoutInfo`; separately, a layer modifier **on** `GuideGridHost`, the header/panel column and `AmbientBackground`, B8; the key-sink `Box` is a sibling of `VideoLayer`, B1), `KeySinkModeTest` (Robolectric: the sink keeps focus across GUIDE → FULL → GUIDE and BACK in FULL reaches the state machine, B1), `LineupZapListTest` (captured row ids survive a lineup re-emission; purged ids skipped, S9), `HoleGeometryTest`, `OklchTest` (the four design tokens round-trip to their sRGB fallbacks within 1/255), `HueExtractorTest`, `TunedStateMapperTest` (§3.16.2 table), `ErrorTextTest` (every `PlaybackErrorCode` has a headline and a step), `AmbientRendererTest` (same hue → identical bitmap hash), `GuideEffectsImplTest` (each effect calls the right collaborator, fakes).

### 5.4 WP-D (JVM)

- `GuideKeyTableTest`: table-driven from `core/src/test/resources/guide/keys.csv` (columns: mode, zone, overlay, precondition, action, expected zone/overlay/effects/focus). The CSV is generated from §3.4 and reviewed line by line; every row of §3.4.2–§3.4.6 appears.
- `FiveKeyReachabilityTest` (§3.4.8: no CONTEXT_MENU, once per `ExitConfirmation`, stated exclusions, S11), `BackLadderTest` (incl. the B2 loop: home → FULL → BACK → home → BACK = exit step, never FULL again), `ReminderCardTest` (modal, 600 ms guard, S2), `StaleWindowTest` (a `Window` for an older lineup or rows version is dropped, S10a), `HoldTest` (`heldMs` thresholds, bubble, `PanelState` not rebuilt while `repeat > 0`, S16), `PanelDebounceTest`, `NumberEntryTest` (1.5 s commit, preview, 4 digits, not found, D-pad entry), `GroupsAcceleratorTest`, `StartupFocusTest`.
- `KeyGestureDetectorTest`: timing table (short tap, 499/500 ms holds, repeats with and without long bindings, cancel, mid-press focus).
- `KeyRouterTest` (Robolectric): PLAYER context in FULL; pass-through of every key in SEARCH except BACK with an empty field and hidden IME (B1); `KeyCapabilities.record` writes only new codes (N7).
- `ReminderPlannerTest` adds: fire at the displayed time on a +60 shifted channel (S1); missed count on start (N5).
- `ReminderPlannerTest`: fire, late, expire, lead, next wake. `ReminderSchedulerTest` (JUnit 4 + `kotlinx-coroutines-test` in `app/src/test`; the scheduler takes a `foreground: StateFlow<Boolean>` that `:app` feeds from `ProcessLifecycleOwner`, so no lifecycle test artifact is needed): runs only while foreground, catch-up on start, one card at a time, race with the card via `setStateIfPending`.

### 5.5 Instrumented suite (API 30 TV emulator `TheBox_TV_API30`, fake panel `--synthetic 2000`)

Launched through `MainActivity` with the debug seeding route (§5.6); state read through `GuideDebugProbe.current` (B7: a `@Singleton` the live VM registers its machine with; debug source set binds the real holder, release a no-op; owner WP-D) via a Hilt `@EntryPoint`.

| # | Test | Pass criterion |
|---|---|---|
| I-1 | Startup | lineup shown, last channel tuned in the mini player, focus on its row at now within 3 s of first frame |
| I-2 | Surface rule | exactly one `SurfaceView` in the window; no ancestor `View` with alpha < 1, a layer type or `clipToOutline`; no Compose `graphicsLayer` above `VideoLayer` (semantics dump) |
| I-3 | Hole | `UiAutomation.takeScreenshot()`: centre of the hole = video (fake-panel colour bars), the four 2-px corner probes ≠ video, ring pixel brighter than the ambient; every overlay's bounds (Groups, Days, Menu, Sheet, NumberEntry, Exit, reminder card, toasts) do not intersect `holeRect` |
| I-4 | Key tables on device | 30 scripted key sequences via `UiDevice.pressKeyCode` give the same end state as the JVM table |
| I-5 | Paging | 200× DOWN at 25 Hz then 60× RIGHT: no PENDING/LOADING visible 300 ms after the last key; window query P50/P90 logged (plan §6 window query, device number) |
| I-6 | Generation swap | an EPG rebuild (fake panel admin bumps the guide) while scrolling: no crash, new generation drawn, the old `epg-<gen>.db` deleted, reminders unchanged |
| I-7 | Groups and accelerators | Left at the edge opens Groups; CH± switches groups with the panel open; long ←/→ in FULL cycles with the card |
| I-8 | Days and Back ladder | day strip jump keeps the clock time; (reviewed 2026-10-14, B2) Back ×1 from a scrolled grid → home; Back → FULL; Back → GUIDE (home); Back → exit hint; Back → exit |
| I-9 | Catch-up | OK on an archive cell: the fake panel logs a timeshift request whose start matches `timeshift_expect` in the panel zone (`--timezone Asia/Tokyo`) |
| I-10 | Reminder | reminder on a synthetic programme starting in 2 min: card at T − 60 s ± 2 s; Switch now tunes and goes FULL; after process restart a missed reminder for a running programme shows "started" |
| I-11 | Search | "news" → channel and programme rows; OK on an archive hit plays catch-up |
| I-12 | No recomposition while scrolling | 50 DOWN + 20 RIGHT: `GuideGridHost` body composes once; `FocusedPanel` recomposes ≤ focus changes + description loads |
| I-13 | Prefix | synthetic `UK: ` names show stripped; search "uk" finds them |
| I-14 | Number entry | "1", "2", "3" → row 123 focused at now after 1.5 s; OK tunes |
| I-15 | Memory | journey ×5 with GC between: Java heap growth < 2 MB; guide retained ≤ §4.6 |
| I-16 | Low-end | debug `lowend=1`: flat background (pixel probe), logo cap 48, `still=1` → viewport HIDDEN (video track disabled, audio continues) and the still shown; OK → FULL shows video after the scrim |
| I-17 | Zap through the guide (S14) | OK on 10 different rows in GUIDE, then 20× CH+ in FULL at 1 Hz: `controller.diagnostics.zaps()` P50/P90 logged (plan §6 zap gate; asserted on hardware in M6) |
| I-18 | Layer scope (B8, owner WP-A) | Perfetto/`Trace` capture during 50× DOWN: the panel's and the ambient's draw sections do not fire on grid-only frames; only `guide-draw` does |
| I-19 | Mode survival (B1) | OK → FULL; Back returns to GUIDE (the app does not exit); CH+ in FULL zaps; Search → type → Back returns to the grid |

### 5.6 Fake panel and debug harness (WP-D)

- `fakepanel.py --synthetic N [--synthetic-days-back 3 --synthetic-days-ahead 7]`: N live channels in groups of 50, 30 % with `UK: `/`[DE] `/`FR | ` prefixes, 20 % with `tv_archive = 1` and 3 or 7 days, EPG for 95 % of channels from `now − back` to `now + ahead`, durations drawn from {5, 10, 30, 60, 120, 360} min, 10 % titles over 60 chars, and a programme starting 2 and 5 min after server start on channel 1 (I-10). The XMLTV is generated while streaming the response (never held in memory). All synthetic streams serve the existing test clip.
- Debug deep links (debug source set only, like M2's harness): `thebox-debug://guide?seed=<panel url>&user=&pass=` adds the Xtream playlist, runs the refresh and EPG import synchronously and opens the guide; `?lowend=0|1`, `?still=0|1`, `?clock24=0|1`, `?exit=twice|ask|none`, `?unlocked=1` (lead §7 item 12: `GuideSettings.debugUnlocked`, debug builds only) set the in-memory guide settings.

### 5.7 Benchmarks (WP-D)

- `GuideScrollBenchmark` (Macrobenchmark, `FrameTimingMetric`, `CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)` until the profile is committed, then `Require`; reviewed 2026-10-14, S14): journeys (a) 100× DOWN at 25 Hz, (b) 100× RIGHT at 10 Hz, (c) 20× CH+ (pages the grid), (d) FULL then 20× CH+ at 1 Hz reading `controller.diagnostics.zaps()` P50/P90 (logged), each with the mini player decoding the fake panel's 1080i50 H.264 + MP2 clip, and each run twice: `PerformanceMode` Auto and forced High (S3). Seeding through `BenchSeedActivity` in `app/src/benchmark` (the benchmark build type does not include `src/debug`). The emulator run needs `androidx.benchmark.suppressErrors=EMULATOR`. Metrics logged: `frameDurationCpuMs` P50/P90/P95/P99 on API 30; `frameOverrunMs` only where reported (it is API 31+ **(verify; owner WP-D, §8)**), so the API 30 numbers exclude GPU time and are not comparable to the gate.
- Baseline profile: `BaselineProfileGenerator` adds the guide journey (30× DOWN, 10× RIGHT, open Groups, open Search) and the profile is regenerated on the emulator.

### 5.8 Integration results

To be filled by WP-D after the integration pass (as M1/M2 did).

### 5.9 Gates M3 can measure (plan §6)

| Gate | Target | How M3 measures it | Asserted? |
|---|---|---|---|
| Guide scroll | P90 < 16.7 ms, P99 < 33 ms with video in the mini window | `GuideScrollBenchmark` on the emulator (Auto and forced High); Fire TV Stick 4K and Onn 4K when available | logged on the emulator (CPU frame time only on API 30, S14); asserted on hardware in M6 |
| Zap time (S14) | P50 ≤ 1.2 s, P90 ≤ 2.0 s through the guide path | I-17 and benchmark journey (d) from `controller.diagnostics.zaps()` | logged; asserted on hardware in M6 |
| Window query | warm ≤ 5 ms, cold ≤ 30 ms at 20k × 7 d | `WindowQueryPerfTest` (JVM) + pager timings in I-5 | JVM asserted; device logged |
| Memory | guide ≤ 5 MB retained, no growth | I-15 | asserted |
| Main-thread draw | ≤ 2 ms P90 | `guide-draw` trace section in the benchmark | logged |
| Snapshot build | ≤ 8 ms | pager timing log | logged |
| Row block | ≤ 15 ms | rows cache timing log | logged |
| Prefix recompute | 20k ≤ 2 s | `DisplayNamesTest` perf variant + log | logged |

Golden images: **none**. The renderer is verified op by op against a recording canvas (exact, toolkit-free); the hole punch involves a `SurfaceView`, which JVM screenshot tools (Paparazzi, Roborazzi) cannot render, so I-3's device probes cover it; overlays are simple Compose and get manual screenshots in the WP-C hand-off. Adding a screenshot library would cost a dependency and a reference-image workflow for little coverage.

---

## 6. Build (commit 0, lead)

### 6.1 Catalog additions (`gradle/libs.versions.toml`)

```toml
[libraries]
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }  # 2.10.0
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }        # BOM 2026.09.00
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }    # BOM 2026.09.00
```

No new versions. Commit 0 runs `./gradlew :ui:dependencies` once: the cache shows lifecycle 2.11.0 raised transitively while the catalog pins 2.10.0 (API notes §0), and `lifecycle-viewmodel-compose` must resolve to the same version as `lifecycle-runtime-compose`. Not added: `androidx.palette` (own hue extractor, §3.14), `hilt-navigation-compose` (`viewModel()` with the activity's Hilt default factory is enough **(verify)**), any screenshot-test library (§5.9).

### 6.2 Modules

```
:app ──► :ui ──► :player ──► :core
  │        └──────────────────►│
  ├──► :data ──────────────────►│   (:ui never sees :data; guide feeds are :core interfaces bound in :data)
  └──► :player, :ffmpeg
```

- `:core`: no new dependencies (`androidx.collection` is already there for `MutableIntFloatMap`). New packages `app.thebox.core.guide{,.layout,.render,.feed,.state,.reminder}` and `app.thebox.core.input`. Fakes in `testFixtures`.
- `:ui`: add `alias(libs.plugins.thebox.hilt)`; `implementation(libs.androidx.lifecycle.viewmodel.compose)`, `implementation(libs.androidx.lifecycle.runtime.compose)`, `implementation(libs.androidx.core.ktx)`; tests: `testImplementation(libs.robolectric)`, `libs.junit4`, `libs.androidx.test.core`, `libs.compose.ui.test.junit4`, `debugImplementation(libs.compose.ui.test.manifest)`; `testOptions.unitTests.isIncludeAndroidResources = true`.
- `:data`: none.
- `:app`: `implementation(libs.coil.compose)`, `implementation(libs.coil.network.okhttp)` (for `ImageModule`); `testImplementation(libs.junit4)`, `testImplementation(libs.kotlinx.coroutines.test)` (scheduler and launcher tests); the `benchmark` source set (`app/src/benchmark/{kotlin,AndroidManifest.xml}`).
- `:benchmark`: none.

### 6.3 Conventions

M1 §6.6 and M2 §6.7 apply (dispatchers, `BoxLog` only, no URL in logs, `*Impl` naming, `internal` by default, no `runBlocking`). Plus: no `Modifier.graphicsLayer`, `alpha`, `clip` or `shadow` on any composable that is an ancestor of `VideoLayer`; no `BlurMaskFilter`/`RenderEffect` at draw time; all guide strings in `strings_guide.xml`/`strings_errors.xml` (no hard-coded user text).

---

## 7. Decisions (signed off 2026-10-14)

All sixteen proposer items and the seven reviewer additions are decided in the **Lead sign-off** section at the top. Summary of what changed from the proposal:

| # | Item | Outcome |
|---|---|---|
| 1 | Virtual focus for the whole guide screen | accepted, with the root key sink (B1) |
| 2 | Real ellipsis instead of clip-and-fade | accepted, with content-hash keys (B4) and grapheme/RTL guards (S6) |
| 3 | Loaded logo replaces the name in the column | accepted; every other surface shows the name; logo off/failed → name |
| 4 | Left opens Groups only at the archive limit | **changed**: no earlier *watchable* cell (on-now cell on rows without archive; archive limit on archive rows) |
| 5 | Foreground-only reminders | accepted; displayed-time firing (S1), modal card (S2), missed toast (N5) |
| 6 | Hue not persisted | accepted |
| 7 | Minimal catch-up via `ArchiveTargets` | accepted, with `CatchupTargetResult` (S13) |
| 8 | Page = 6 rows | accepted |
| 9 | Still keeps audio | accepted; S3 thresholds; video track disabled through `TrackController` (viewport HIDDEN); first-frame wait on FULL |
| 10 | Prefix details | accepted with changes: alpha-2 and alpha-3, exclusion list (`TV FM AM AND ARE PER`), group equality only without lower-case letters |
| 11 | ON NOW secondary = From start | accepted |
| 12 | Locked groups hidden in M3 | accepted with `?unlocked=1` debug switch; **for M5**: with auto-lock on and no PIN set, adult groups must not stay unreachable (onboarding asks for a PIN or turns auto-lock off) |
| 13 | 7-day search look-back | accepted with the split `searchPast` (B6) |
| 14 | `:ui` gets Hilt | accepted |
| 15 | Timeline 1,558 px | accepted; `01-design-reference.md` corrected |
| 16 | Earliest minute from an upper bound | accepted |
| E17 | Back after leaving FULL | exit step, never FULL again (B2) |
| E18 | Reminder time base | raw keys, displayed-time firing |
| E19 | RAM thresholds | `isLowRamDevice`/`memoryClass` first, MemTotal floor 1.75 GiB; still < 1.25 GiB; real device figures to measure in M6 |
| E20 | Reminder card | modal, 600 ms guard |
| E21 | More… panel button | added |
| E22 | Search and Settings rows in Groups | added |
| E23 | WP-C split | branding → WP-E (Sonnet), Search → WP-B; merge order A, B, D, E, C |

---

## 8. Open questions

1. **Xtream without XMLTV** (architecture review C6 `ShortEpgFetcher` for visible rows): not in M3. Xtream panels nearly always serve `xmltv.php`; if beta shows otherwise it is a pager addition (a per-row fallback source), not a contract change.
2. **"Locked channels in guide: Show with lock"** (product §4.8 default): needs the PIN session and a lineup variant that includes locked rows with a lock glyph and no titles. M5.
3. **SEARCH key and voice search** (`KEYCODE_SEARCH`, Assistant): unbound in M3. Bind it to "open Search" in M5's mapping screen, or now?
4. **Logos from the guide** (product §4.3 "Logos from: Playlist/Guide"): needs `EpgReader` to return `epg_channel.icon_url` per ref (one indexed lookup). Add in M5 with the setting.
5. **Reminder retargeting** when a rebuild moves a programme (same title hash within ±60 min): v1 fires at the stored time with the stored title. Worth doing in M5?
6. **Translucent Compose over the `SurfaceView` on Fire OS 6/7** (mini pills and gradient): D1 on hardware is still open (plan §7). If a device shows artefacts, the fallback is opaque pills and no gradient.
7. **VoiceView and `liveRegion` on a single `Spacer`**: needs a Fire TV check; TalkBack on the API 30 emulator is the M3 proxy.
8. **API 25 logos on the Java heap**: within the 2 MB low-end byte cap by design; confirm on a Fire TV Stick 4K.
9. **Panel buttons and the design's blur:** the secondary button's `backdrop-filter: blur(20px)` becomes a flat 10 % fill (B1.3). The design owner should see it once.
10. **Recordings tab:** hidden (not a stub, plan §2). The tab row is Guide · Search · gear; the gear opens a placeholder until M5. Confirm the placeholder wording.
11. **Not verified, with owners** (reviewed 2026-10-14; each owner checks before building on it, a contradiction becomes a lead amendment):
    - Compose layer scope: a draw invalidation without its own layer re-records up to the root (B8) → **WP-A**, I-18 trace.
    - `SQLITE_BUSY` text from the native step path of `sqlite-bundled` 2.6.2 (the Kotlin format is verified, S7) → **WP-B**.
    - `FrameTimingMetric` member names in benchmark 1.5.0 and whether `frameOverrunMs` exists on API 30 (S14) → **WP-D**.
    - Room 2.8.5 `InvalidationTracker.createFlow` name → **WP-B**.
    - `LineBreak.Heading` on BOM 2026.09.00 → **WP-A** (the panel title is WP-C's composable; WP-A owns the text-API check and hands the result over).
    - Whether Compose's `AndroidView` adds its own layer around the `SurfaceView` → **WP-C** (I-2/I-3; D1 on hardware).
    - `viewModel()` picking up Hilt's default factory without `hilt-navigation-compose` → **WP-C**.
    - `OkHttpNetworkFetcherFactory(callFactory = …)` in `coil-network-okhttp` 3.6.3 → **WP-C** (`ImageModule`).
    - MemTotal and `memoryClass` of a Fire TV Stick 4K and an Onn 4K (S3) → **M6** device pass.
12. **M5 parental follow-up** (lead §7 item 12): with "Lock adult content automatically" on and no PIN set, the M1 predicate (`ChannelDao.kt:235`) hides adult groups forever. M5's onboarding must ask for a PIN or turn auto-lock off.

---

## Appendix A. Facts relied on (with sources)

### A.1 Prototype geometry (`design/TV Guide.dc.html`)

- Mini player `left:64px;top:56px;width:720px;height:405px;border-radius:20px` with `box-shadow:0 30px 80px rgba(0,0,0,.55),0 0 0 1px rgba(255,255,255,.14)`.
- Header region `left:832px;top:56px;right:64px;height:405px`.
- Grid `left:64px;right:64px;top:500px;height:460px;border-radius:24px;…border:1px solid rgba(255,255,255,.09);…padding:0 16px 12px;box-sizing:border-box` → content width 1792 − 2 − 32 = 1758; label column `width:200px` → timeline `flex:1` = **1558 px**.
- Header row `height:48px`; tick `left:{{ pct(m) }}` for m ∈ {0,30,…,150}, `padding-left:10px;border-left:1px solid rgba(255,255,255,.1)`, mono 15 px .55.
- Rows `gap:8px`, row `height:60px`; label `gap:14px;padding:0 12px;border-radius:12px`, number mono 15 .5, name 19/600, tuned dot 7 px `#ff4d5e` `margin-left:auto`.
- Cell wrapper `padding:0 3px`; cell `border-radius:12px;padding:0 16px;gap:3px`, title 18/600 ellipsis, time mono 13 `opacity:.6`; progress `height:3px` at `bottom:0` inside `overflow:hidden`.
- Cell states from the script: focused `bg #eef1f6`, `shadow 0 0 0 3px accent, 0 12px 30px rgba(0,0,0,.4)`, `color #0a0d13`, bar accent; on now `.11`; default `.05`, border `.08`; past text `rgba(238,241,246,.45)`.
- Now line `top:-6px;width:2px;box-shadow:0 0 12px`, dot `top:-4px;left:-4px;10×10`; `nowLeft = calc(200px + (100% - 200px) * NOW/WIN)`.
- Anchor rule in the script: `anchor = Math.max(p.s, 0) + 1` (start + 1 min), replaced by plan §4's sticky anchor.
- Footer `left:64px;right:64px;bottom:40px;gap:36px;font-size:17px`, key caps mono 14 with `.22` border radius 7.
- Labels: `watchLabel = row === tuned && isNow ? 'Watching' : isNow ? 'Watch now' : 'Watch from start'`; `durLabel` "1 h 30 min" form.

### A.2 Code on `main` (`9a87271`)

| Fact | Where |
|---|---|
| `EpgReader.window(channels, shiftMin, fromMin, toMin, out)`; `detail`, `at`, `search(query, nowMin, limit)`, `resolve`, `refOf`; reader not thread-safe per call site | `core/.../epg/EpgContracts.kt:232-267` |
| `EpgWindow` struct of arrays sorted by (channelIndex, startMin), `rawStartMin`, `textId`, `flags`, `titles` | `EpgContracts.kt:181-203` |
| `ProgrammeFlags.CONTINUATION = 1 shl 5` | `EpgContracts.kt:167-168` |
| `ProgrammeDetail.channel` is generation-local; `titleHash = Hash64.of(title)` | `EpgContracts.kt:205-228` |
| Window SQL is a bounded PK range scan per channel; titles from a 4,096-entry `IntLru` | `EpgReaderImpl.kt:51-88, 418-425` |
| Search returns only airings with `end_min > now`; CONTINUATION rows not filtered | `FtsSupport.kt:52-66`, `EpgReaderImpl.kt:114-200` |
| `EpgStore.info` emits on publish and patch; `openReader()` ref-counted | `EpgContracts.kt:129-158` |
| Guide contract: re-resolve on generation and patch | `docs/design/m1-data-layer.md` §2.5.3 |
| `ChannelDao.browseGroup/browseAll` return whole `ChannelRow` lists (not used by the guide: they materialise every row) | `data/.../db/dao/ChannelDao.kt:73-89` |
| `ChannelDao.PARENTAL` predicate | `ChannelDao.kt:231-236` |
| `ChannelDao.search` over `channel_fts` | `ChannelDao.kt:129-144` |
| `GroupDao.groups(playlistId, kind, unlocked)` with `channel_count` | `GroupDao.kt:22-34` |
| `FavouriteDao.channels(listId, unlocked)` | `FavouriteDao.kt:46-61` |
| `ReminderEntity` keyed `(playlist_id, stable_key, start_min)`, raw start, `lead_sec` 60, states 0–3 | `data/.../db/entity/ReminderEntity.kt:9-39` |
| `ReminderRepository.add/remove/pending/due/markFired/dismiss/markOrphaned` | `data/.../repo/ReminderRepository.kt:16-42` |
| `ChannelRepository.number(mode, …)`, `ftsQuery`, `byNumber` | `data/.../repo/ChannelRepository.kt:46-62, 124-141` |
| `EpgRepository.resolvedRefs` rule and shift formula | `data/.../repo/EpgRepository.kt:314-358` |
| `ChannelPrefsRepository.setHidden` uses the whole-row `edit` (N-L13 risk) | `data/.../repo/ChannelPrefsRepository.kt:26-34` |
| `RoomMergeSink` content columns and FTS maintenance; constructed in `RefreshRepository` | `data/.../repo/RoomMergeSink.kt` (`CONTENT`, `apply`), `RefreshRepository.kt:136-140` |
| `ImportMutex` shared by merge and EPG import | `data/.../work/ImportMutex.kt:9-25` |
| `SyncStatusRepository.status` | `data/.../repo/SyncStatusRepository.kt:15-58` |
| `DataSettingsSnapshot.epgPastDays = 2`, `epgFutureDays = 7` | `data/.../repo/DataSettings.kt:14-28` |
| `library.db` version 2, auto-migration 1 → 2 | `data/.../db/LibraryDatabase.kt:53-55, 93`; `data/.../db/Migrations.kt` |
| `NameNormalizer.ISO2` from `Locale.getISOCountries()` + "uk" | `core/.../text/NameNormalizer.kt:22` |
| `PlaybackController.tune(target, zapList)`, `zap`, `setZapList`, `previousChannel`, `tuneArchive`, `setViewport`, `prewarm` | `player/.../api/PlaybackApi.kt:11-57` |
| `ZapList.neighbour(from, delta)` | `PlaybackApi.kt:72-77` |
| `PlaybackState` variants; `VideoInfo.posterMode`, `aspectMode` | `PlaybackApi.kt:100-124` |
| `Viewport { NONE, MINI, FULL, HIDDEN }`; `PlaybackErrorCode` (25 codes) with `defaultSteps` | `core/.../playback/PlaybackContracts.kt:267, 129-155` |
| `VideoRectCalculatorImpl.place(hole, w, h, par, mode)` | `core/.../playback/surface/VideoRectCalculator.kt:20-47` |
| `VideoLayer(surfacePlayer, videoRect)` places in window px relative to its parent | `ui/.../playback/VideoLayer.kt:16-38`; M2 code review E-L18 |
| `BoxDesignScale` density = `min(W/1920, H/1080)`, `fontScale = 1`; popups need re-wrapping | `ui/.../theme/BoxDesignScale.kt:28-66` |
| `DesignTokens.Colors` and type tokens | `ui/.../theme/DesignTokens.kt:17-88` |
| Bundled fonts (static instances, API 25) | `ui/.../theme/Fonts.kt:8-26` |
| `MainActivity` is the M0 placeholder; collects `keepScreenOn`, binds `WindowDisplayModeBinder` | `app/.../MainActivity.kt:27-40` |
| `:ui` already depends on Coil (`coil-compose`, `coil-network-okhttp` 3.6.3) and `media3-ui-compose` | `ui/build.gradle.kts`, `gradle/libs.versions.toml` (`coil = "3.6.3"`) |
| `CatchupUrlBuilder.build(target, timeBase, window, variant)`, `CatchupTarget`, `ArchiveWindow` | `core/.../catchup/CatchupContracts.kt` |
| `StreamUrlResolver.catchupTarget(identity)` is internal to `:player` | `docs/design/m2-playback-core.md` §3.14 |
| Fake panel: catalog of a dozen channels, `timeshift_expect`, `--timezone` | `tools/fakepanel/README.md` |
| No ImageMagick or potrace on the build machine; `rsvg-convert` at `/opt/local/bin` | checked 2026-10-14 |

---

## Appendix B. Named constants

| Constant | Value | Owner | Section |
|---|---|---|---|
| `GuideGeometry.*` | §2.1 | lead (commit 0) | §3.1 |
| `GuideColors.*` | §2.1 | lead | §3.6 |
| `ROW_BLOCK` / `ROW_BLOCKS_MAX` | 32 / 8 | WP-B | §3.2.1 |
| `RECENT_LIMIT` | 50 | WP-B | §3.2.1 |
| Target rows / minutes around the view | −6…+12 rows / −180…+360 min | WP-B | §3.2.3 |
| Margin rows / minutes | 2 / 60 | WP-B | §3.2.3 |
| `BUSY_RETRY_MS` / `BUSY_RETRIES` | 500 / 3 | WP-B | §3.2.3 |
| `INFO_CACHE` | 32 | WP-B | §3.2.3 |
| Width cache cap | 8,192 | WP-B | §3.2.3 |
| `SLIVER_MIN` | 5 min | WP-A | §3.2.4 |
| `LEAD_MIN` / `TAIL_MIN` | 30 / 30 min | WP-A | §3.3.2 |
| `SNAP_AFTER_REPEATS` | 3 | WP-A/WP-D | §3.3.3 |
| `SCROLL_ANIM_MS` | 200 | WP-A | §3.3.3 |
| `PAGE_ROWS` | 6 | WP-A | §3.4.2 |
| `TIME_PAGE_MIN` / `TIME_STEP_MIN` | 180 / 30 | WP-A | §3.3.2 |
| `HOLD_TIME_STEP_AFTER_MS` | 1,000 | WP-D | §3.4.7 |
| `PANEL_DESC_DELAY_MS` / low-end | 150 / 600 | WP-D | §3.9.1 |
| `EXIT_CONFIRM_MS` | 2,000 | WP-D | §3.4.6 |
| `GROUP_CARD_MS` | 2,000 | WP-D | §3.4.5 |
| `NUMBER_COMMIT_MS` / `NUMBER_PREVIEW_MS` / max digits | 1,500 / 150 / 4 | WP-D | §3.9.4 |
| `LONG_PRESS_MS` | 500 | WP-D | §3.18.1 |
| `A11Y_DEBOUNCE_MS` | 300 | WP-A/WP-D | §3.6.6 |
| `TEXT_FIT_SLOTS` (titles / names) | 512 / 256 | WP-A | §3.6.4 |
| `LOGO_CACHE` / low-end | 160 / 48 entries | WP-A | §3.7 |
| `LOGO_CACHE_BYTES` / low-end | 4 MB / 2 MB | WP-A | §4.4 |
| Coil memory / disk cache (logos bypass the memory cache, S8) | 2 MB (1 MB low-end) / 64 MB | WP-C (`ImageModule`) | §3.7 |
| `SEARCH_MIN_CHARS` / debounce | 2 / 300 ms | WP-C | §3.10.3 |
| Search limits (channels / programmes / per airing / FTS hits / past days) | 30 / 50 / 3 / 200 / 7 | WP-B | §3.10 |
| `LATE_AFTER_MS` | 60,000 | WP-D | §3.11.2 |
| `REMINDER_CARD_MS` | 60,000 | WP-D | §3.11.3 |
| `REMINDER_CARD_GUARD_MS` | 600 | WP-D | §3.4.1 (S2) |
| Hold target / margin rows | `[top − 2, top + 24)` / 6 | WP-B | §3.2.3 (S16) |
| Row-content debounce | 500 ms | WP-B | §3.2.1 (B5) |
| `searchPast` limit / per text; `search` per text | 100 / 5; 8 | WP-B | §3.10.2 (B6) |
| `EXCLUDED_TAGS` | `TV FM AM AND ARE PER` | WP-B | §3.12.1 |
| Reminder lead default | 60 s (`reminder.lead_sec`) | M1 | §3.11 |
| `SCRIM_MS` | 120 | WP-C | §3.13.5 |
| `AMBIENT_FADE_MS` | 1,200 | WP-C | §3.14 |
| Ambient bitmap | 480 × 270 | WP-C | §3.14 |
| Hue cache | 64 | WP-C | §3.14 |
| Recompute batch | 2,000 rows | WP-B | §3.12.3 |
| `ChannelNamePrefix.RULE_VERSION` | 1 | WP-B | §3.12 |
| Low-end thresholds (S3) | `isLowRamDevice` or class ≤ 192 MB, MemTotal floor < 1.75 GiB; still < 1.25 GiB | lead | §0.11, §2.4 |
