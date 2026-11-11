# Review of the deferred-item fixes (2026-11-10)

Reviewer: independent (Opus). Read-only review of `e35dc82`, `80740f2` and `128a88a` on main (merge `609f1e2`),
against `docs/review/2026-11-09-whole-code-review/` (performance.md H5, M6, M7, M9, L3, L9; README "Not fixed").
I read the surrounding code, not only the diffs. Nothing was built or run (other agents share the machine), so every
claim below comes from reading the code. Where I could not prove something, I say so.

## Verdicts

| Commit | Items | Verdict |
|---|---|---|
| `e35dc82` | L3, paused-in-local-buffer reapply, M7(b) | **Approve.** Correct and small. Only later items (N1–N4) |
| `80740f2` | M9, L9 | **Approve with one should-fix (S3).** L9 is exact. M9 cancels loads for rows that are still visible on API 29+ |
| `128a88a` | M6, H5 | **Approve with two should-fixes (S1, S2).** The skip is correct for the cases the commit names, but it ignores the build inputs and moves work to app start. **H5: keep the flip, unconditionally** (see §H5) |

**No blockers.** The should-fixes are S1–S4. Doc drafts are in §Docs.

---

## Should fix

### S1. The not-modified skip ignores every build input except the bytes (settings, app version)

**Evidence.**
- `data/.../repo/EpgRepository.kt:216-229`: the skip depends only on `!anyChanged && !force && age < 20 h`.
- `core/.../settings/tree/GuideRows.kt:37-41`: Past days, Future days, Descriptions and Matching are `NEXT_EPG_IMPORT`. The row text promises "applies at the next guide update". `playlist.playlist_epg` is the same (`PlaylistRows.kt:35`).
- `EpgBuildRequest` takes `pastDays`, `futureDays`, `storeDescriptions`, `maxDescriptionChars`, `preferredLanguages` and the per-source `assumeZone` (`EpgRepository.kt:233-237`). The generation already stores `app_version` (`EpgStoreImpl.kt` putMeta, `EpgSchema.Meta.APP_VERSION`), but nothing reads it.
- `docs/design/m1-data-layer.md` (EpgImportWorker): "If every source is not-modified **and no subject changed since the last build** → skip." The second condition was never coded. Before M6 that rarely mattered, because the common `xmltv.php` sends no validators and so every run rebuilt. It matters now.

**Failing scenario.** An Xtream provider whose `xmltv.php` sends no ETag (the case M6 targets). The user sets Future days from 7 to 14. Settings says it applies at the next guide update. The next periodic run downloads a byte-identical file and skips, and so does the one after it. The setting takes effect up to 20 h later. Before `128a88a` it took effect at the next run.
- "Descriptions: Off" and the Matching rule behave the same way.
- An app update that changes the importer (a new XMLTV field, a parse fix) is not applied to an unchanged guide for up to 20 h.

**Fix.**
1. Compute a build fingerprint: `Hash64` over `pastDays`, `futureDays`, `storeDescriptions`, `maxDescriptionChars`, `preferredLanguages`, the sorted `(source.id, assumeZone)` of `sources`, and `APP_VERSION`.
2. Store it in the generation meta (a new `build_inputs` key, written in `rebuild` and `inPlace`) or in a `DataSettings` field written after a successful build.
3. Skip only when it equals the current fingerprint.
4. Test: a changed `epgFutureDays` with an identical file rebuilds from `.src` (no second download), and an unchanged one skips.

A re-enabled playlist guide (`use_playlist_epg` off, a rebuild, then on) probably reaches the generation through `matchIncremental` → patch from `.src`. I did not prove that path. The fingerprint (active source ids) covers it either way.

### S2. App start now downloads the whole guide on every launch between `interval` and 20 h

**Evidence.**
- `data/.../work/RefreshScheduler.kt:121-133`: `onAppStart` enqueues `epg-import` when `now − builtAtMs ≥ epgRefreshIntervalH`.
- A skipped run does not move `builtAtMs` (`EpgRepository.kt:221-226`).

**Failing scenario.** Interval 6 h (or 12 h), a provider with no validators, and a box the user turns on several times a day (the reason the app-start rule exists).
- Before M6: an app start after 6 h downloaded and rebuilt, which reset `builtAtMs`. The next app start within 6 h did nothing.
- Now: every app start between 6 h and 20 h after the last build downloads the full XMLTV (often 20–150 MB on these panels), compares it and skips.
- That is more provider traffic than before the fix. Some panels rate-limit `xmltv.php`, so it can also produce `http.429` FAILED rows.

**Fix.** Make `onAppStart` measure the age from the newest of `builtAtMs` and the last `EPG_BUILD` row whose outcome is `SUCCESS` or `NOT_MODIFIED` (`refreshLogDao.latest(TARGET_EPG_BUILD, null)`, `finishedAt`). M6 now writes that row, so the information exists. Add a `RefreshSchedulerTest` case: a NOT_MODIFIED row 1 h old with a generation 8 h old → no enqueue.

### S3. M9 cancels the in-flight logo loads of rows that are still on screen (API 29+), then re-records every row

**Evidence.**
- `core/.../guide/render/GuideRendererImpl.kt:178`: a row whose key did not change replays its recording, so `drawLabel` (and with it `images.get`) does not run.
- `LogoCache.kt:98-111`: a load with no `get` for 60 draws is cancelled, and any cancel bumps `version`. `version` is in `frameKey` (`GuideRendererImpl.kt:201`), so the next draw re-records **every** visible row.
- The KDoc (`LogoCache.kt:34-38`) acknowledges this and relies on the bump to restart the load.

**Failing scenario.** An Onn 4K or Google TV (API 29+, recordings on) with a slow logo host, which is common for provider logo URLs (several seconds per logo, or queued behind Coil/OkHttp's per-host limit). The user browses down at a moderate pace: a step every 300–400 ms, about 9 animation frames per step.
- A row that stays in view for more than about 60 draws (6–7 steps) while its logo is still loading gets cancelled.
- The cancel bumps `version`, every visible row re-records (a full guide-draw, the cost O-2 exists to avoid), the load restarts at the back of the host queue, and 60 draws later the same thing can happen again.
- While browsing continues, the logo may never arrive, and the sweep can cause a full re-record every 8th frame.
- The same applies to `prefetchLogos()` loads for `[top − 6, top + 12)` (`GuideViewModel.kt:542-549`). They are requested once, so slow prefetches die before the row scrolls in.
- On API ≤ 28 (the Fire TV Stick 4K) rows are drawn directly every frame, so this does not happen there.

**Fix (small, keeps the zero-allocation draw).**
1. Add `GuideImages.touch(url: String)` (default no-op). `LogoCache` sets `inFlight[url]?.lastFrame = frame`: a map lookup with no allocation.
2. In `drawRows`, when `beginRow` returns true (replayed) and `frame.showLogos`, call `images.touch(rows.channel(i)?.logoUrl)`. The replayed rows are exactly the visible ones.
3. Drop `version++` on cancel (`LogoCache.kt:111`). Only off-screen loads are cancelled now, so nothing on screen needs re-keying.
4. Test: a load whose URL is only touched (never `get`) for 3 × `STALE_FRAMES` is not cancelled, and the version does not change on a sweep that cancels.

This also answers the question of whether cancelling is worse than the original problem. As written, in the worst case it can be about as bad: a full re-record every 8 frames during a hold, against one per arrival before. With the fix it is strictly better.

### S4. A test for the `lastError == null` guard, and the force path with an identical file

**Evidence.** `EpgRepository.kt:225` writes NOT_MODIFIED only when no source failed in the run. No test covers this. The new tests cover only single-source runs.

**Failing scenario (regression guard).** Sources A and B. A answers 500 and has a cached `.src`, so it falls back silently. B is byte-identical. Someone later drops the guard. The NOT_MODIFIED row then acknowledges an earlier FAILED `EPG_BUILD` row (for example a `transientLoss` row for A) while A is still failing. The sync chip clears and Settings › TV guide shows OK. Today's code is correct here, but nothing pins it.

**Fix.** Add these to `EpgRepositoryTest`:
- `aFailedSourceNextToAnIdenticalOneLogsNoNotModifiedRow`: two user sources, an earlier FAILED row, A `errors[url] = Http(500)` with `.src` present, B in `noValidators` and identical. Expect `skipped`, no new row, and the FAILED row still unacknowledged.
- `manualForceWithAnIdenticalFileRebuildsFromTheKeptFile`: expect `.new` deleted, `lastBuild.sources.single().file.name == "$s.src"` and one SUCCESS row.

---

## Later

### M6 / 20 h rule
- **N1. "At least one rebuild a day" is not guaranteed.** The 20 h threshold counts from the last build of any kind. A daily (24 h) schedule plus a mid-cycle build skips the next periodic run. Example: periodic at 0 h, a manual or PLAYLIST_CHANGED build at 5 h, the periodic run at 24 h sees an age of 19 h and skips, so the next rebuild is at 48 h. The future window then shrinks by up to about 43 h; with Future days = 3, about 1.2 days of guide are left.
  - The app-start rule partly hides this; a box left on the guide all day does not get it.
  - A schedule-independent rule: rebuild when `generation.windowToMin − nowMin < (futureDays − 1) × 1440` (or when the age is ≥ 20 h). This caps the loss at one day whatever the schedule. If it is not adopted, at least correct the doc sentence (§Docs D3).
- **N2. The 20 h rebuild resets the status of a source that is still failing.** A failed download with a cached `.src` (`EpgRepository.kt:203-204`) feeds the 20 h rebuild. Step 3 then calls `setImported(..., status = st?.errorCode ?: SUCCESS, at = now)` (`:276-279`) for that source, which overwrites the `setStatus(error)` written seconds earlier.
  - Before M6 this happened only when another source changed. Now it happens every day for a single source that keeps failing.
  - No UI shows `epg_source.last_status` yet (there is no EpgSources page), so the impact is low.
  - Fix when that page is built: for a non-fresh pick that came from an `Outcome.Err`, keep the source's error status and `last_import_at`.
- **N3. The known gap from the sync-chip decision now applies on the 304 path too.** A source whose download fails non-transiently, with no cached file but data in the guide, used to stay in the generation forever when the other sources answered 304. After 20 h it is now dropped by a SUCCESS build (`docs/decisions/2026-11-01-guide-sync-chip.md`, "Known gaps"). This is consistent with the existing rule for changed sources. Record it next to that gap.
- **N4. The compare runs after the download, not in it.** It reads both files in full (2 × file size) and cannot be cancelled mid-file: no `ensureActive`, so a cancelled worker waits for it. At 150 MB on eMMC that is a few seconds, much cheaper than a rebuild, so it is fine for now.
  - The review suggested hashing in the fetcher's copy loop. That would also save the second read.
  - An `if (!coroutineContext.isActive) return false` every 64 KB costs nothing.
  - The compare itself is correct. It checks lengths first. `readFully` handles short reads, so a buffer boundary is not a problem (the test covers 200,000 bytes, which is 3 × 64 KB + a remainder). It runs on `io` under `runLock`, and neither file can be replaced while it runs (`.src` changes only under `runLock`, and `.new` is ours). An unreadable file gives false, which means rebuild: the safe side.
- **N5. Retention and diagnostics.** NOT_MODIFIED rows now take room in the 30-row `EPG_BUILD` retention (4 a day on a 6 h schedule, so 7.5 days of history) and in the 10 export report lines (`DiagnosticsImpl.kt:146`). They carry no report, so the last real build report can drop out of an exported bundle. Optional: keep the last SUCCESS row out of the trim, or use `REPORTS` for builds only.
- Checked and fine:
  - Placeholders are excluded by `active()`.
  - The transient-loss path still returns FAILED before the skip.
  - `.src` missing (cache policy Never or under 1 GB) means an unconditional GET, and `anyChanged` stays true as before.
  - A truncated `.src` gives a length mismatch, so the run rebuilds from `.new`.
  - The 304 `FetchResult` carries the request's validators, so `setImported` keeps them.
  - Clock moved back: a negative age rebuilds once, and `builtAtMs` is then the moved-back now, so the next run is in range.
  - Clock moved forward: the age is large and the run rebuilds.
  - On the sync chip: NOT_MODIFIED is in `RefreshLogDao.insert`'s acknowledgement, so a 304 or identical run after a build failure clears the chip. That is the intended "playlist 304" behaviour.
  - `SourceSetupImpl.kt:333` now sees the guide run sooner during onboarding (a NOT_MODIFIED row counts as "ran"). That is an improvement, and no test depended on the old absence.

### H5
- **N6. Re-run `:core:perfTest` (EpgPerfTest) after the flip and record the numbers.**
  - The JVM budget is 25 s × 1.5. M1 recorded 20 s, so +20 % gives about 24 s, with no headroom without the tolerance.
  - Search p95 may also move: `programme_by_text` is now interleaved with table pages instead of written in one sorted pass, and the file grows 2 %.
  - Update the plan's M1 line (`docs/00-master-plan.md` §8, "980k-programme EPG rebuild 20 s") or add a note.
- **N7. The time cost will not transfer from the Mac.** A pre-load index means random inserts into a B-tree larger than the 16 MB build cache (text ids are reused across channels). On a Mac SSD that cost is +20 %. On a Fire TV Stick's eMMC with `synchronous=OFF` it may be well above that. I-5 must record build time as well as native peak, against the §6 gate (≤ 90 s for ~1M while playing).

### M9
- **N8. `tick()` allocates an iterator every 8th draw while loads are in flight** (`LogoCache.kt:102`). That breaks M3's zero-per-frame-allocation rule only marginally, but an allocation-counting test would flag it. Iterating `inFlight.entries` has the same cost. To make it allocation-free, keep a parallel `ArrayList<Load>` and sweep by index.
- **N9. Does cancelling stop the fetch?** Most likely yes. `coilFetch` runs `ImageLoader.execute` in the launched job. Cancelling the job cancels Coil's request, and `coil-network-okhttp` (3.6.3) cancels the OkHttp `Call` when the coroutine is cancelled, which also drops a queued call from OkHttp's per-host queue. Not verified on a device. A late result that arrives anyway is dropped by the `inFlight[url] !== rec` check (`:149`).
  - That check is correct: a restart installs a new `Load`, so an old completion can neither remove nor fill the new entry.
  - The cost is that a bitmap that has already been downloaded is thrown away.
- **N10. Thread safety holds, by convention only.** `get` (draw and `prefetchLogos` on `viewModelScope`), `tick` (draw), completions (`Dispatchers.Main.immediate`), `clear`/`trim` (`onTrimMemory`) and the `scale`/`lowEnd` setters (draw) all run on main. Add a one-line "main thread only" note to the KDoc, or an `@MainThread` annotation. `GuideGridView` (unused in production) does not call `tick()`; if it is ever used, loads are never cancelled there.

### L3
- **N11. `TimeshiftGrant.capacityBytes` has no production reader.** The snapshot reads `ring.capacityBytes` (`TimeshiftSessionImpl.kt:197, 204, 222`). The new `ringBytes` line is correct but only tests see it. Either document the field as informational or remove it from the grant.
- **N12. The factory's `ringCapacity` is stale after a runtime shrink.** `AndroidRingStorage.shrinkTo` (ENOSPC or the free-space watch, §8.2 "afterwards it only shrinks") changes the storage's capacity, but not the factory's `ringCapacity`. A shrunk ring can be reused on the next zap while the factory still records its original size. This predates L3 (the exact-match rule had the same issue), but the tolerance now makes the reuse a little more likely. Fix: read `ring.capacityBytes` in `capacityReusable` instead of the recorded value.
- **N13. Only half of the wobble is removed.** A one-block **dip** in free space still rebuilds; only the recovery is absorbed (the new test `theFreeSpaceFloorStillHoldsWhenSpaceWobbles` shows the rebuild).
  - The "larger is never safe" argument follows §8.2 to the letter: capacity = `available + current − reserve`, and an equal length means the old capacity no longer fits.
  - But the reserve is at least 1 GiB, and a 2-block (128 KiB) overshoot cannot matter against it. A symmetric 2-block tolerance would remove the remaining rebuilds. That is the lead's call, because it bends §8.2.
  - Drift cannot accumulate: every comparison is against a freshly computed target, so the ring is never more than max(2 blocks, 1 %) below it. A ratchet down to the bottom of the wobble range is bounded by the same tolerance.
  - SMB and USB use the same rule. `reusableAfterSwitch` (C-S3) already allowed a smaller ring, and its grant previously carried the sizing instead of the ring's size, which the `ringBytes` line also fixes.

### Paused reapply
- **N14. The paused frame can step back up to one GOP.** `reapplySettings` → `launchSession` → `pinStart(c)`: the reader re-prepares at the PLAYHEAD pin, which is the RAP **before** the paused playhead (§7.2 row "Playing (buffered) pause"). So after a REPREPARE change the paused frame and the resume point can move back by up to one GOP (2–10 s on IPTV). This is the same accuracy as the playing case and acceptable, but say so in the design note.
  - The ring cannot overwrite the pin while paused: the tail never passes the pin, and FULL stops the writer (`TsRingFullTest`). After a disk or hold stop the re-prepare clamps to `max(pin, oldestRap)` (§4.5).
  - Only REPREPARE rows reach the applier (`SettingsEffects.onClosed`), and a paused session has no audio, so the 0.1.4 Settings audio-gap fix is not affected.
  - A network folder reads the ring through the same reader path as resume.
- **Test note.** `LocalTimelineBackendTest.reapplyWhilePausedInTheBufferStaysPaused` passes without the fix: `ArchivePlaybackImpl.reapply` already routed LOCAL sessions to the controller in every phase (`ArchivePlaybackImpl.kt:438`). It is a useful regression guard, but the test that pins the fix is `ReapplyOnExitTest.pausedInTheLocalBufferIsReappliedAtThePausedPlayhead`.

### L9 and M7(b)
- **L9 is exact.**
  - There is one production producer (`PlaylistDifferImpl.kt:216-217`) and one test fake (`FakeDiffer.kt:77`), and both set `renamed`.
  - `ExistingChannel.nameHash` is `Hash64.of(stored name)` (`RoomExistingChannels.kt:31`), and the incoming one is `ContentHasher.nameHash(stored.name)` = `Hash64.of` (`ContentHasher.kt:49`). The values are equal exactly when the old SELECT's string compare was equal, apart from the documented 2^-32 collision.
  - The per-playlist prefix setting writes only `display_name` (`DisplayNames.kt:22-23`), and FTS keeps the raw name, so search rows behave as before.
  - `RoomMergeSinkTest.updateWithoutRenameLeavesTheFtsRowAlone` would also pass on the old code. It does not pin that the SELECT is gone, which is acceptable for a mechanical change.
- **M7(b) is fine.** No code constructs `DataPlaybackSourceProvider` directly (Hilt only), its other constructor parameters are unchanged, and `ChannelRepository.get` delegates to `getWithRow`, so every other caller keeps its behaviour.

### Flakiness
None of the new tests depend on wall time or load. `EpgRepositoryTest` uses the fake clock, `LogoCacheTest` uses `TestScope.advanceUntilIdle`, `TimeshiftSessionTest` uses fakes and `ChannelRepositoryGetTest` runs on Robolectric. `EpgIndexMemoryTest` is opt-in (`@Tag("perf")` plus `THEBOX_H5=1`), so it is outside the parallel gate.

---

## H5 recommendation: keep it on, unconditionally

**What else depends on the indexes.**
- `INDEX_BEFORE_LOAD` changes only `rebuild` (`EpgStoreImpl.kt:148`, and `POST_LOAD_INDEXES` drops the duplicate), so nothing creates `programme_by_text` twice.
- Patch and in-place builds write into a generation that already has every index, before and after the flip.
- `epg_text_hash` stays post-load. `TextDedup(seedFromDb = false)` never looks texts up by hash during a rebuild.
- The FTS rebuild, `ANALYZE` and the generation swap are unchanged.
- The schema is the same, so existing generations need no migration.

**Is the measurement enough?** For the memory direction, yes. The saving comes from SQLite's external sorter, which `TEMP_STORE=3` keeps in RAM on every target. Its size depends on the number of rows, not on the platform: pointers are smaller on 32-bit ARM, but the sort records are not. The index-phase delta (+66–72 MB → +13 MB) is the figure that carries over. The absolute RSS does not, and the JVM heap growth during the load muddies the "108–115 MB over baseline" headline.

**Device-class condition: no.** At 1M programmes the low-end Stick is where the risk is: an LMK kill throws away a build of several minutes, and a guide that never finishes is worse than one that takes 20 % longer. A device-class condition would add a second code path to the most memory-sensitive step for a gain only on devices that do not need it.

**Conditions.**
1. The I-5 device run records **both** native peak and build time (N7).
2. If a Stick's 1M build exceeds the 90 s gate, try a larger build `cache_size` for the load before going back to the post-load sort.
3. Re-run `EpgPerfTest` and record it (N6).

---

## Docs (exact text for a follow-up agent)

### D1. `docs/review/2026-11-09-whole-code-review/README.md`
Replace the "Not fixed, with the reason" bullets for performance and the paused-buffer line.

Replace:
```
- Performance H1 (decoders released on every zap), H5, M5, M6, M9, L1–L4: need measuring on a real Fire TV Stick.
```
with:
```
- Performance H1 (decoders released on every zap), M5, L1, L2, L4: need measuring on a real Fire TV Stick.
```

Replace:
```
- P2 proper fix (rebuild the archive URL on the chosen base), M7(b), L9: need a core contract change.
- A setting changed while paused in the local rewind buffer applies only at the next re-prepare.
```
with:
```
- P2 proper fix (rebuild the archive URL on the chosen base): needs a core contract change.
```

Append under "Fixed in the fix pass" (or as a new section):
```
## Fixed after the pass (2026-11-10, deferred items)
- `e35dc82`: L3 (a rewind ring up to max(2 blocks, 1 %) smaller than the new sizing is reused; a larger one is still
  rebuilt), a REPREPARE setting changed while paused in the local rewind buffer now re-prepares the reader at the pin
  and stays paused, M7(b) (one channel row read per zap).
- `80740f2`: M9 (logo loads not requested for 60 draws are cancelled; one version bump per arrival), L9
  (`ChannelUpdate.renamed` replaces the per-update SELECT).
- `128a88a`: M6 (a byte-identical guide download counts as not modified; a NOT_MODIFIED `EPG_BUILD` row is logged
  for 304 and identical runs; a generation 20 h old or more is rebuilt from the cached files), H5
  (`INDEX_BEFORE_LOAD = true` after the JVM measurement in `EpgIndexMemoryTest`; I-5 on a Stick still confirms).
- Review of these fixes: `docs/review/2026-11-10-deferred-items-fix-review.md` (open: S1–S4 and later items).
```

### D2. `docs/design/m6-timeshift.md` §8.3, the Zap row
Replace:
```
| Zap | **logical reset** (`generation++`, after the old pump exits); the file is reused |
```
with:
```
| Zap | **logical reset** (`generation++`, after the old pump exits); the file is reused. (amended 2026-11-10, review L3) The ring is reused when its capacity equals the new sizing or is smaller by at most max(2 blocks, 1 % of the new sizing); a larger ring, a capacity outside that tolerance, a different length, index size, pool or location, or a debug ring size (`TimeshiftDebugOverrides.ringBytes`, exact) rebuilds it. A smaller ring keeps the §8.2 reserve; the window is at most 1 % shorter until the next rebuild. The grant carries the ring's actual capacity. |
```
Also amend the §1516 implementation line "or a new storage + ring when the capacity changed" to "…when the capacity changed beyond the §8.3 tolerance".

And in §7.6, after the `reapplySettings` row:
```
(amended 2026-11-10) `LiveSettingsApplierImpl` now calls this for a session paused in the local buffer (`ArchivePhase.PAUSED`, `TimelineSource.LOCAL`) through `ArchivePlayback.reapply`. The reader re-prepares at the PLAYHEAD pin, the RAP before the paused frame, so the paused picture and the resume point can move back by up to one GOP.
```

### D3. `docs/design/m1-data-layer.md`, EpgImportWorker M6 amendment
Replace the sentence:
```
20 h means at least one rebuild a day on the 6/12/24 h schedules, with 4 h of slack for WorkManager drift on the daily one.
```
with:
```
On the 6 h and 12 h schedules this rebuilds at least once a day. On the 24 h schedule a mid-cycle build (manual, playlist change, app start) can push the next rebuild to the second periodic run after it, so the future window can shrink by up to about 43 h before it moves (review 2026-11-10, N1). A skip also does not move `built_at_ms`, so `RefreshScheduler.onAppStart` should measure the age from the newest of the build and the last SUCCESS/NOT_MODIFIED `EPG_BUILD` row (review 2026-11-10, S2).
```
If S1 is adopted, add:
```
"Nothing changed" also requires the build inputs to match: a fingerprint of past/future days, descriptions, description length, preferred languages, the active sources with their assumed zones and the app version, stored with the generation. A NEXT_EPG_IMPORT setting change therefore rebuilds at the next run (from the cached files when the download is unchanged).
```
In the H5 paragraph, append: `` `epg_text_hash` and the FTS rebuild stay after the load (the index phase then adds about 13 MB). The build-time cost was measured on an SSD; I-5 records the Stick's build time too. ``

### D4. `docs/design/m1-data-layer.md` §1.2 contract (line ~822) and the differ table (line ~2460)
```
class ChannelUpdate(val id: Long, val channel: KeyedChannel, val restored: Boolean, val renamed: Boolean)
```
Table row: `` `ChannelUpdate(restored = missingSince != null, renamed = name hash differs)` ``. In the `RoomMergeSink` description, add: "(amended 2026-11-10, review L9) the FTS row is rewritten for updates with `renamed`; no per-update SELECT. A 32-bit name-hash collision leaves that row on the old name until the next rename."

### D5. `docs/design/m3-guide.md` §3.7, after the Cache bullet
```
- (amended 2026-11-10, review M9) Stale loads: the host calls `LogoCache.tick()` once per draw. A load whose URL no `get` asked for in `STALE_FRAMES` (60) draws is cancelled at the next 8-draw sweep (Coil cancels the OkHttp call). A cancelled URL is neither cached nor failed, and the next `get` starts it again. An arrival bumps `version` once. All `LogoCache` calls are on the main thread. Rows replayed from a recording (O-2) do not call `get`; see review 2026-11-10 S3 for the `touch` follow-up.
```

### D6. `docs/design/m5-setup-settings.md` line ~123 ("a paused session is left paused")
Append: `(amended 2026-11-10) Except a session paused in the local rewind buffer: it re-prepares at the pin now and stays paused (m6-timeshift §7.6).`

---

## Fix pass (2026-11-10)

Lead rulings applied in one uncommitted change on top of `609f1e2`. Every finding was checked in the code first; none
was rejected.

| Item | Disposition |
|---|---|
| S1 | **Fixed.** `EpgBuildInputs.fingerprint` (core) hashes past/future days, descriptions, description length, preferred languages, the match settings + `EpgMatcher.VERSION`, the active sources (sorted id + stored assume-zone) and `EpgSchema.SCHEMA_VERSION` + `EpgSchema.IMPORTER_VERSION` (the value stored as `app_version`, moved out of `EpgStoreImpl`; bump it with an importer change). `EpgBuildRequest.buildInputs` carries it; the store writes `meta.build_inputs` in `rebuild` and in a successful `inPlace` (removes a stale one when none is given); patches keep it; `EpgGenerationInfo.buildInputs` reads it. `EpgRepository` skips only when the bytes are unchanged **and** the fingerprint matches. The fingerprint uses the *active* sources, not the ones this run could read, so a source failing without a cache does not force a rebuild that drops it. A generation from before has no fingerprint → one rebuild. The never-coded "no subject changed" clause is not implemented (a playlist change reaches the guide through `matchIncremental` + patch); the m1 doc now says so. |
| S2 | **Fixed.** `RefreshLogDao.lastCurrentAt` (newest SUCCESS/NOT_MODIFIED row by id). `onAppStart` enqueues unless the build or that row finished within one interval; FAILED rows never count, a future time (clock moved back) never counts. |
| N1 (window rule) | **Fixed; replaces the 20 h age rule.** Skip only when `shrunk + interval ≤ 26 h` (`MAX_FUTURE_SHRINK_MS`), `shrunk = futureDays × 24 h − (window_to_min − now)`. 6 h: rebuild every 4th run; 12 h: every 2nd (a 12 h run up to 2 h late still skips); 24 h / daily: every run, also after a mid-cycle build. Minimum shown ahead: `futureDays − 26 h −` WorkManager delay, about 2 days on the 3-day setting. Negative age still rebuilds. |
| S3 | **Fixed.** `GuideImages.touch(url)` (default no-op). The renderer calls it for rows replayed in the text pass when logos are on (`rows.channel(i)?.logoUrl`, no allocation). `LogoCache.touch` = `get` without the result: decided that it **may start a load** exactly where `get` would, because a row's recording can outlive a load cancelled while the row was off screen and nothing else would restart it. A cancel no longer bumps `version`. `FakeGuideImages` records `touched`; `StaticGuideImages`, the golden test's `Logos` and the debug receiver's accessor keep the no-op default (they never cancel). |
| S4 | **Done.** `aFailedSourceNextToAnIdenticalOneLogsNoNotModifiedRow`, `manualForceWithAnIdenticalFileRebuildsFromTheKeptFile`. |
| N2, N5 | Open (N2 when the EpgSources page exists; N5 optional). |
| N3 | Recorded next to the known gap in `docs/decisions/2026-11-01-guide-sync-chip.md`. |
| N4 | **Fixed** (cancel check per 64 KB buffer in `sameFileBytes`). Hashing in the fetcher's copy loop not done. |
| N6 | Re-run alone after the fix pass: `EpgPerfTest` import 21.3 s against the 25 s budget (85 %, 3.7 s headroom), 980k programmes, retained heap 5.1 MB, db 79.9 MB, window warm p95 0.62 ms, search p95 0.93 ms. Plan M1 line updated. |
| N7 | Open (I-5 on hardware). |
| N8 | Open (tick's iterator every 8th draw while loads are in flight). |
| N9 | Open (device check). |
| N10 | Already met: the `LogoCache` KDoc says "Main thread only". |
| N11, N12, N13 | Open (N12 touches several `ringCapacity` readers; N13 is the lead's call). |
| N14 | Documented (m6-timeshift §7.6 note). |
| Test note | `LocalTimelineBackendTest.reapplyWhilePausedInTheBufferStaysPaused` kept: it pins the `ArchivePlaybackImpl.reapply` routing the fix relies on (no resume, no seek, no provider re-request); its KDoc now says it does not pin the fix itself. |
| Docs D1–D6 | Applied. D3 carries the review's sentence plus an amendment for what was implemented; the §7.6 note sits under the table and names `reapplySettings`. |
