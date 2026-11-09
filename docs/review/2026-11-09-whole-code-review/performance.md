# The Box: whole-codebase performance review (2026-11-09)

This review is read-only: no project file was edited and nothing was committed. It was done in three parts:
- **Lead:** the guide hot path, Compose, startup/DI, coroutines and build config.
- **Delegated:** the import pipeline, and playback plus sports polling. The lead spot-checked the top claims of both.

The JVM checks are:
- SQLite `compile_options`;
- `EXPLAIN QUERY PLAN` on the library.db import queries;
- a regex-normaliser micro-benchmark;
- Room 2.8.5's `InvalidationTracker.createFlow` source.

The micro-benchmark sources are in `scratchpad/sq/`. Every millisecond and megabyte figure for the device is an **estimate** unless it is marked as measured.

The target is a Fire TV Stick: 1 GB RAM, slow eMMC, 4 slow cores, mostly Fire OS 7 (API 28) or Fire OS 8 (API 30). The workload is 50k+ channels, XMLTV files of hundreds of MB, and 2M programmes.

---

## 1. Ranked findings

**Impact** is High, Medium or Low. **Confidence** is one of:
- **C:** confirmed by reading the path end to end and knowing how often it runs;
- **M:** measured;
- **P:** plausible.

### High

| # | Finding | Where | Conf. | Group |
|---|---|---|---|---|
| H1 | Every zap releases the video and audio decoders and the AudioTrack (`player.stop()` with foreground mode off resets the renderers). Est. 100–300 ms per channel change. Already known as review item N1 and still open. | `player/.../controller/PlaybackControllerImpl.kt:1089-1097` (`endSession` → `player?.stop()`); `PlayerFactory.kt` never calls `setForegroundMode` | C (mechanism); P (size) | Measure first |
| H2 | Downloads are stored decompressed. `OkHttpSourceFetcher` sets no `Accept-Encoding`, so OkHttp inflates gzip on the fly. A 100 MB `.xml.gz` is written to and read back from slow flash as about 1 GB (est. 35–60 s of I/O against 5–10 s to inflate). About 2 GB is held during a refresh on an 8 GB stick, which can trip `storageNotLow` and stop periodic work. | `data/.../net/OkHttpSourceFetcher.kt:37,50-54` | C | Safe now (needs a fakepanel gzip test; do **not** apply to `XtreamClient`, which sniffs raw bytes) |
| H3 | No baseline profile is checked in. The `:baselineprofile` module, the plugin and `profileinstaller` are all wired up, but no `baseline-prof.txt` exists under `app/src/main`. The app code (renderer, state machine, pager, AppRoot) runs interpreted/JIT on first use after every install or update. Startup and the first guide scrolls are typically 20–40 % slower without one. The master plan lists it as open ("needs API 33+/root"). | `app/build.gradle.kts:136-139`; nothing under `app/src/main/` | C (absence); P (gain) | Safe now: generate it on an API 33+ Android TV emulator (profiles are not device-specific), not on the Stick |
| H4 | Guide lineup and Groups-panel queries re-run back to back during any import, also while the user watches in FULL. Every `channel` commit (about 25 merge batches of 2,000, including "touch" batches when nothing changed) invalidates `channel`. Room's invalidation versions are a `MutableStateFlow`, so the triggers coalesce but never pause. While commits arrive faster than one query, the following loop continuously: the All lineup (a joined, multi-key `ORDER BY` over 50k rows into a temp B-tree), `buildSources` (one COUNT per favourites list, plus `panelGroups` per playlist), and `rowContent` → `reloadLoaded` every ≥500 ms. That competes for CPU with the import and with playback, and long-lived readers can hold back WAL checkpoints. Writes to the `playlist` table (`setActiveBaseIndex`, `setXtreamOutputLearned`, `setServerTime`, refresh status) re-run the same set. | `data/.../guide/GuideLibraryImpl.kt:66-69,105-119,208-216`; `RoomMergeSink.kt:108` | C (mechanism); P (size) | Measure first, but the fix is low risk |
| H5 | Native memory peak during the post-load index build at 2M programmes. `TEMP_STORE=3` keeps all sort spill in RAM. The ≤64 MB I-5 budget was set for 1M rows; at 2M, expect about 60–120 MB native while playback may be running. The low-memory killer could throw away a build of several minutes. | `core/.../epg/store/EpgSchema.kt:11,78-83`; `EpgStoreImpl.kt:175-183` | C (M: `TEMP_STORE=3` on the JVM jar); P (size) | Measure first (run I-5 on a Stick at 2M rows) |

### Medium

| # | Finding | Where | Conf. | Group |
|---|---|---|---|---|
| M1 | `epg_match` is fully rewritten on every EPG build, for every source, even when nothing changed. That is roughly 100–250k index writes and 25 WAL commits per source, it blocks the start of the XMLTV parse, and it also fires the guide's `rowContent`. | `data/.../repo/EpgMatchRepository.kt:61-99,262-292,106-122,252-258` | C | Diff part: measure first. The `has_data` updates guarded with `AND has_data = 0/1` are safe now |
| M2 | Unmatched and overridden subjects are matched again on every playlist refresh: `persist` writes no row, so `subjectHashes` always sees them as changed. That means 15–30k subjects, all EPG channels loaded, and it can enqueue a full `epg-patch` re-parse. | `EpgMatchRepository.kt:150-174,272-274` | C | Schema change: needs a proposer and reviewer |
| M3 | The matcher uses the ICU-regex `NameNormalizer.forKey`/`countryHint` on its hot path. Measured on desktop JVM: 4.5 µs per name, against 1 µs for `FastNames.forKey`, which already exists and has a parity test. Est. 2–5 s per 50k-subject prepare on device, repeated per source and per refresh. | `core/.../match/EpgMatcherImpl.kt:64,73,80,139,168,175` | M | `forKey`: safe now. `countryHint`: needs an ASCII fast path and a parity test first |
| M4 | The subjects query does not use the playlist index. `(:playlistId IS NULL OR c.playlist_id = :playlistId)` plans as `SCAN … index_channel_missing` plus a temp B-tree, so every playlist's VOD and series rows are read too. Est. 1–3 s per call. | `data/.../db/dao/ChannelDao.kt:216-224` (EXPLAIN-confirmed) | M | Safe now: split into two `@Query`s |
| M5 | One early insertion rewrites `sort_index` for every later row. It is an absolute position, so one new channel near the top causes up to 50k UPDATEs on an indexed column. | `core/.../refresh/PlaylistDifferImpl.kt:230-233`; `RoomMergeSink.kt:148-151` | C | Measure first: log `report.moved`. The fix is a design change |
| M6 | Nothing is skipped when the server sends no ETag or Last-Modified (typical of `xmltv.php`, `get.php` and `player_api`). Every 12 h run re-downloads and fully rebuilds the EPG, which takes minutes on device. | `data/.../repo/EpgRepository.kt:186-192,210`; `RefreshRepository.kt:123` | C | EPG content hash in the fetcher copy loop: fairly safe. Playlist side: needs review (it affects `missing_count`) |
| M7 | About 12–14 sequential DB queries per zap, half of them repeats. `readFacts` and then the resolver read the channel, playlist and account twice. `channel()` reads the row twice. `account()` uses `flow.first()`, so a one-shot Room observer creates and drops its triggers, a write that can queue behind an import. | `PlaybackControllerImpl.kt:796,942-950`; `app/.../playback/DataPlaybackSourceProvider.kt:58-60,85-87`; `PlaylistRepository.kt:194` | C | (a) `accountDao.get` instead of `.first()` and (b) drop the duplicate `channelDao.row`: safe now. (c) Pass the facts on to the resolver: needs the lead |
| M8 | The focused-panel description scroll renders at the full display rate whenever a description overflows, which is the default. `DescriptionScrollState.tween` writes a fresh float every vsync. `offset{}` and the `DstIn` mask read it, so every frame re-places, redraws the offscreen layer and re-records the header layer. At 24 design px/s the integer offset changes only about every 2.5 frames, so about 60 % of those frames draw the same image. The loop repeats for as long as the user rests on a cell, next to the mini player's video. `lowEnd` does not turn it off. | `ui/.../guide/screen/FocusedPanel.kt:122-145,171-200` | C | Safe now: write `offset` only when `roundToInt()` changes (or step 1 px per `delay(1000/speed)`). That cuts frames to about 24/s at 1080p, with identical pixels. Consider defaulting it off in low-end mode (owner decision) |
| M9 | Guide logo loads are never cancelled. `LogoCache.get` starts one Coil load per URL the draw touches. During a long D-pad hold, dozens of rows pass, each starting a network fetch and decode that keeps running after the row has gone. Coil/OkHttp allow about 5 per host, so the landing rows' logos queue behind stale ones. Each arrival also bumps `images.version`, which is in the renderer's `frameKey`, so every row recording is invalidated and re-recorded. | `ui/.../guide/grid/LogoCache.kt:65-71,99-125`; `core/.../guide/render/GuideRendererImpl.kt:201` | C (mechanism); P (user-visible delay) | Measure first. Fix: keep a `Job` per URL, cancel loads whose URL was not requested for N frames or is outside `[top−6, top+18)`. Optionally bump `version` only when the URL is in the visible rows |
| M10 | Secure-DNS (DoH) answers are not cached, so every new connection (every zap, reconnect and redirect) pays a DoH round trip of about 20–100 ms. Applies only when the user turns secure DNS on. | `player/.../BoxDns.kt:56-75` (path as reported) | C | Safe now: in-memory host → address cache, about 60 s or the TTL |

### Low

| # | Finding | Where | Group |
|---|---|---|---|
| L1 | Row RenderNode recordings (O-2) are API 29+ only. Most Fire TV Sticks run Fire OS 7 (API 28), so they always take the direct-draw path. The renderer allocates nothing and is fast, so this is not a defect. But the O-2 vertical-scroll gain does not reach the main target. If the Stick's `guide-draw` misses the 2 ms budget, a `Picture` recording per row (API 23+, drawn on hardware canvases on API 28) would save the Kotlin-side work. | `ui/.../guide/grid/AndroidGuideCanvas.kt:128-131` | Measure first (systrace on an API 28 Stick) |
| L2 | `AdaptiveLoadControl` uses `DefaultAllocator(trimOnReset = true)`, so up to 24 MiB of 64 KB segments is freed and allocated again on every zap. | `player/.../engine/AdaptiveLoadControl.kt:44` | Measure first (`trimOnReset = false` plus `trim()` on ON_STOP) |
| L3 | The time-shift ring is rebuilt when capacity, which is derived from free space, changes by one block between zaps: the file is reopened, 64 MiB pre-allocated and the pool rebuilt. | `player/.../timeshift/TimeshiftSessionFactoryImpl.kt:309-311`; `TimeshiftPolicyImpl.kt:53` | Needs a reviewer (allow a tolerance) |
| L4 | The time-shift pool sizes from `isLowRamDevice` only, so a 1 GB Stick probably gets 4 MiB instead of 2 MiB. Use `DeviceCapabilities.isLowRam`. | `TimeshiftSessionFactoryImpl.kt:128` | Mechanical; check the slow-flash trade-off |
| L5 | Two threads wake when idle: `ByteWatchdog` every 500 ms after the first tune, and `RingFlusher` every 100 ms with no rings. About 12 wakeups a second. | `player/.../ByteWatchdog.kt:52-56`; `RingFlusher.kt:59-72` | Safe now |
| L6 | The hide-rule pass scans 50k rows inside a write transaction even when no rules exist. | `data/.../rules/RuleHiddenPass.kt:29-47,150-165` | Safe now: one guarded UPDATE when there are no matchers |
| L7 | `TailInputStream` copies every decompressed byte one at a time (about 1e9 iterations for 1 GB). | `core/.../xmltv/XmltvParser.kt:424-432` | Safe now: `arraycopy` of the last ≤256 bytes |
| L8 | Progress is persisted to WorkManager's database every 500 ms (about 1,200 transactions in a 10-minute build). | `data/.../work/EpgImportWorker.kt:37-40`; `PlaylistRefreshWorker` | Safe now: throttle to 2 s or write only on a text change |
| L9 | One extra SELECT per updated channel to detect a rename the differ already knows about. | `RoomMergeSink.kt:128-135` | Mechanical |
| L10 | library.db has no bulk-merge tuning: 2 MB cache, no `PRAGMA optimize`/ANALYZE, no `journal_size_limit`. | Room writer; `LibraryMaintenance` | Measure first |
| L11 | `TextDedup` starts its map at 4096 and grows to about 1M entries, so the last rehash is about 30–40 MB transient. Xtream decodes a `JsonObject` tree per item. | `core/.../epg/store/TextDedup.kt:22`; Xtream decoder | Measure first (presize from the file size) |
| L12 | The NHL source parses whole bodies into a full JSON tree (`score/now` may be 100–300 KB per 30 s day poll; only while the tab or picker is open). | `core/.../sports/NhlScoreSource.kt:153-158,174-178` | Measure first |
| L13 | The Sports tab recomposes every 30 s even when nothing changed: `DayState.Board` carries a fresh `receivedMono`. | `data/.../sports/ScoreRepositoryImpl.kt:506` | Safe now (skip equal content) |
| L14 | `AppRoot` reads the whole `GuideUiState` in its body, so the root body (sticky video, `scoreFacts`, `onEvent` lambda, toast and reminder logic) re-runs on every guide key press that changes the panel. Children skip correctly. Est. well under 1 ms per press. | `ui/.../root/AppRoot.kt:175,265-345` | Measure first. Only if a Stick trace shows root recomposition mattering: derive mode, tuned, reminder, pin, update and overlay with `derivedStateOf` and pass the `State` to `GuideScreen` |
| L15 | The pager computes `cellTimeText`/`cellTimeWidth` for every cell of every snapshot, but nothing reads them since the 2026-10-17 ruling (cells show the title only). One array plus a cached lookup per cell per snapshot, on the epg thread. | `core/.../guide/feed/GuideWindowPagerImpl.kt:500-526`; `GuideModel.kt:229-230` | Safe now (drop it, or fill lazily) |
| L16 | `TsExtractorsFactory` builds Media3's full default extractor list on every prepare and keeps only TS. | `player/.../TsExtractorsFactory.kt:53-60` | Only if profiling shows it |
| L17 | The guide ticker is `viewModelScope.launch` with no lifecycle gate (the player's uses `repeatOnLifecycle`). It does minute ticks plus deadline wakeups in the background. Negligible, but inconsistent. | `ui/.../guide/vm/GuideViewModel.kt:535-551` | Safe now (`repeatOnLifecycle(STARTED)`) |

---

## 2. Grouped by action

### Safe, mechanical, worth doing now
1. **H2:** `Accept-Encoding: gzip` on the source fetcher only. The biggest import win on slow flash.
2. **H3:** generate and check in the baseline profile from an API 33+ TV emulator (`:baselineprofile` already exists).
3. **H4 (low-risk form):** in `GuideLibraryImpl.trigger`, debounce the non-initial invalidations (about 500–1000 ms; keep the initial emission immediate). Or sample while `ImportMutex` is held and run one final re-query on release. Lineups only emit when their ids change, so the only cost is that lineup changes appear later during an import.
4. **M3:** `FastNames.forKey` in `EpgMatcherImpl`.
5. **M4:** split the subjects `@Query`.
6. **M7 (a)(b):** `accountDao.get` instead of `.first()`, and drop the duplicate `channelDao.row`.
7. **M8:** quantise the description scroll to whole pixels.
8. **M1 (part):** guard the `has_data` updates.
9. **M10:** DoH answer cache.
10. **L5, L6, L7, L8, L9, L13, L15, L17:** each is local and mechanical.

### Measure on a device first
- **H1:** foreground mode, or skip `stop()` on a plain live→live zap. Keep the renderer reset whenever the decoder, tunnelling or audio-fallback switches differ from the previous session. A/B with the existing zap records. This is likely the biggest channel-change win.
- **H5:** I-5 at 2M programmes on a Stick. If it is over budget, `INDEX_BEFORE_LOAD = true` and create `epg_text_hash` early.
- **M1 (diff):** count the rows written per build.
- **M5:** log `report.moved` on real playlists before redesigning the order keys.
- **M6:** EPG content-hash skip. The playlist side needs review.
- **M9:** logo-load cancellation; measure the time for logos to appear after a long hold.
- **L1:** systrace `guide-draw` on an API 28 Stick before considering `Picture` recordings.
- **L2, L3, L4, L10, L11, L12, L14, L16.**
- **M2:** a schema change, so it goes through proposer and reviewer.

---

## 3. Areas found sound (leave alone)

### Guide grid draw path
`GuideRendererImpl` allocates nothing per frame:
- the per-row RenderNode cache with frame-aware `RowSlots`;
- a binary search for the first visible cell;
- `TextFit`'s direct-mapped cache keyed by content hash, with known-width short-circuits;
- ruler and number width caches;
- a two-pass background/text split.

`GuideGridHost` reads the frame version, scroll and logo tick only in `onDrawBehind`, so focus moves and scroll animation frames invalidate the draw only. `AndroidGuideCanvas` reuses its paints and rects.

### Guide data feed
- `GuideWindowPagerImpl` uses one single-threaded epg reader with directional hold margins, so a held key does not re-query every row. Requests are coalesced through an `AtomicReference`, snapshots are immutable, and the width, flag and hash caches are keyed by text id.
- `EpgReaderImpl.window` runs one primary-key range query per channel, with a reused statement and an LRU of titles.
- `GuideRowsCacheImpl` loads blocks of 32 rows, with no boxing and `contentEquals` to suppress version bumps.
- `GuideQueries` fills `LongArray`s from raw statements and runs the All lineup in one pass.

### Compose
- The guide shell passes narrow slices of state.
- The live dot steps at 1 Hz instead of using an infinite transition.
- The channel list is keyed and draws only a window of rows.
- The score bug and NFL bug drive their animations from `Animatable`s read only in draw.
- Ambient hue extraction runs on `Default`, with an LRU.
- No real-time blur. No `graphicsLayer` or clip on any ancestor of the video.

### Startup
- `Application.onCreate` defers everything heavy behind the `StartupGate` or IO: settings load on IO, WorkManager runs after the first frame, and the reminder scheduler, backup scheduling and display-name backfill start late.
- The activity's view models are lazy (Sports is created only on its tab).
- `controller.prewarm()` runs after the first frame.

### Import
- The XMLTV pull parser allocates nothing per programme and filters by whitelist and window before reading children.
- The epg.db rebuild runs with `journal_mode=OFF`, `synchronous=OFF`, exclusive locking and a 16 MB cache. It writes 10k-row transactions through cached statements into a `WITHOUT ROWID` primary key, builds indexes after the load and swaps generations atomically.
- The M3U byte-level line reader reuses its buffer.
- The differ is O(n) with 64-bit hash arrays.
- Downloads stream to disk with conditional GET.
- Imports run at background priority and pause for zaps.

### Playback
- `LiveTsDataSource`, the packet aligner and the PCR tracker allocate nothing per `read()`, read the clock once per 16 KiB, and log lazily.
- The broker cancels outside its lock and releases a zap's lease immediately.
- Playback clients share one connection pool.
- The live start threshold is 1 s, and the buffer is capped at 24 MiB on ≤2 GiB devices.
- The time-shift writer writes 64 KiB blocks from direct buffers with no fsync, into pre-allocated extents, and the ring is reused across zaps.

### Sports
- No requests unless Sports is on, the app is in the foreground, the screen is on, a game is followed and the device is online.
- An actor waits on its inputs when nothing is due.
- ESPN bodies are stream-decoded into DTOs with a size cap.
- StateFlows skip equal values.

### Build
- Release has `isMinifyEnabled` and `isShrinkResources` with `proguard-android-optimize`.
- APKs are split per ABI (ARM only in release).
- Strong skipping is the default with Kotlin 2.4.

The one gap in build config is H3, the missing baseline profile.

---

## 4. Not covered
- Settings, search, the channel editor, backup/restore, SMB storage and the update installer were only skimmed (not hot paths).
- No device measurement was taken. The 2 ms guide-draw budget, cold start and scroll frame time on the real Stick are still open, as the master plan notes.
- The FFmpeg audio decoder's CPU cost was not reviewed.
- `GuideStateMachineImpl` was scanned for O(n) loops per key and was not read in full. The linear `indexOf` over the lineup runs only per lineup change or tune, about 50 µs at 50k rows.
