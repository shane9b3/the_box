# M1 "Data in": code review

Reviewer pass over the merged M1 code (`git diff c4f626a..1639b70`), 2026-10-13. Checked against `CLAUDE.md`, `00-master-plan.md` §3, `design/m1-data-layer.md` (§3.2, §3.3, §3.6, §3.8, §3.9 and §3.10 read closely) and `review/m1-design-review.md` §B.

Scope: `core/src/main`, `data/src/main`, `app/src/main`. Four parallel passes covered:
- identity and merge,
- EPG store and matcher,
- Xtream, catch-up and network,
- workers, credentials, DI and app.

Every finding below was traced through the code. Fixed bugs were reproduced by a regression test that fails on `1639b70`.

Line numbers refer to `1639b70`, the code before the fixes. Tags `B-n` and `S-n` also appear in the code comments and test names that the fixes added.

**Result:**
- Blockers: 1 found, 1 fixed.
- Should fix now: 13 found, 13 fixed.
- Later: 30 items, none applied.
- Build: `:core:test` 848 tests, was 840. `:data:testDebugUnitTest` 120 tests, was 111. `:app:assembleDebug` is green.
- Instrumented tests were not run: another process is using the emulator.

---

## Blocker (fixed)

### B-1. The header-level `catchup-source` is stored with the password in clear (B3 not fully closed)
- **Where:** `data/.../repo/RefreshRepository.kt:376-385` (`redactHeader`).
- **Problem:** WP-A's `HeaderAttrs` treats `#EXTM3U catchup-source="…"` as a typed field. It lands in `M3uHeader.catchup.source` and never in `attributes`. `redactHeader` cleaned `epgUrls`, `urlLogo` and `attributes`, but copied `catchup` unchanged. A `get.php` header such as `catchup-source="http://h/timeshift/USER/PASS/…"` therefore reached `playlist.header_json` in clear. The design-review blocker B3 exists to rule out exactly this. Per-channel `catchup_source` was already templated; the header copy was not.
- **Fix:** `catchup = h.catchup.copy(source = template(source, user, pass))`. It is templated only, not `Redact`ed, because `PlaylistRepository.catchupDefaults` reads it back and M2 expands `{{u}}`/`{{p}}`.
- **Test:** `RefreshRepositoryTest.headerCatchupSourceIsTemplated` asserts that no table column contains the password. `FakeM3uParser` now types `catchup-source` the way `HeaderAttrs` does.

---

## Should fix now (all fixed)

### S-1. Two refreshes of the same playlist can run at once
- **Where:** `data/.../repo/RefreshRepository.kt:93`; scheduling in `work/RefreshScheduler.kt:48-78, 114-121`.
- **Problem:** `playlist-periodic-<id>` and `playlist-refresh-<id>` are different unique works. On the first app start after a playlist is added (`last_success_at` null), `onAppStart` enqueues both. A new periodic work runs immediately.
- **Effect:** both runs download into the same `<id>.src.part`. Pass 2 of one merge can re-open a file that the other download replaced after pass 1, which is a different file from the one pass 1 validated (shrink guard, groups). Both runs also start from the same stale `refresh_seq`/`etag`.
- **Fix:** a `Mutex` per playlist around the whole refresh (`ConcurrentHashMap.computeIfAbsent`). The second run then usually gets a cheap 304.
- **Test:** `RefreshRepositoryTest.concurrentRefreshesOfOnePlaylistAreSerialised`. The test fetcher now records the maximum number of downloads in flight.

### S-2. Two EPG builds can download and promote at once
- **Where:** `data/.../repo/EpgRepository.kt:144-246, 248`.
- **Problem:** `epg-periodic` and `epg-import` are different unique works, and both run on the first start after a source is added.
- **Effect:**
  - The downloads (into `<id>.new.part`) and the `.new` → `.src` promotion (delete, then rename) ran outside every lock. Only `store.build` was locked.
  - Two runs write the same part file.
  - A patch can read `<id>.src` between a build's delete and its rename.
- **Fix:** an EPG `runLock` around all of `build()` and `patch()`. Lock order: runLock → ImportMutex → EpgWriteLock.
- **Test:** `EpgRepositoryTest.concurrentBuildsDoNotDownloadAtTheSameTime` fails without the lock.

### S-3. A regex runs per row in the merge (`channel.norm_name`), against CLAUDE.md
- **Where:** `data/.../repo/RoomMergeSink.kt:216` calls `NameNormalizer.forMatch` for every inserted or updated row. That function runs four regexes. The same call runs per EPG display-name in `ImportSession.onChannel` and per name in the matcher.
- **Fix:**
  - Added a regex-free `FastNames.forMatch` for ASCII input: country prefix, brackets, then the forKey word pass.
  - `NameNormalizer.forMatch` now takes it for ASCII names. Non-ASCII names, and the reference definition, use `forMatchRegex`.
  - The output is identical, so persisted values are unchanged.
- **Test:** `FastNamesParityTest` now checks `forMatch` on the hand-picked names, 50k random names (with country prefixes and every kind of bracket added) and 20k random printable strings.

### S-4. `XtreamClient` lets OkHttp's `IllegalArgumentException` escape
- **Where:** `data/.../xtream/XtreamClient.kt:258-259` (`buildRequest` outside every try).
- **Problem:** a non-ASCII User-Agent or header value, or an invalid host or port, threw a raw IAE out of `login()`/`download()`. OkHttp's message quotes the header value. Inside a refresh this became a permanent `Internal` error.
- **Fix:** map it to `Unsupported("invalid server address or header")` and try the next base.
- **Test:** `XtreamClientTest.invalidHeaderOrHostIsAnErrorNotAThrow`.

### S-5. JSON parse errors carry an excerpt of the response body, which can include the password
- **Where:** `core/.../xtream/XtreamJson.kt:116-124` (`guard`).
- **Problem:** kotlinx-serialization messages end with `\nJSON input: …`. On `player_api.php` that excerpt is `user_info` with the username and password. It went into `SourceError.Parse.detail` (UI-facing and in `refresh_log`) and into the cause chain (logs). `Redact` masks only registered secrets of 3 or more characters, and not their JSON-escaped forms.
- **Fix:** `parseDetail()` cuts the excerpt. Serialization exceptions are no longer kept as the cause.
- **Test:** `XtreamJsonTest.parseErrorDetailNeverQuotesTheBody`.

### S-6. Cancelling a fetch while it copies the body does not close the socket
- **Where:** `data/.../net/OkHttpSourceFetcher.kt:58-101`.
- **Problem:** `Call.await()` cancels only while it is suspended. The body copy blocks in `read()` and calls `ensureActive()` only after a read returns. Against a stalled server, a cancelled worker kept the socket and the part file until the read timeout (120 s). With S-1 it also kept the playlist lock that long.
- **Fix:** register `job.invokeOnCompletion(onCancelling = true) { call.cancel() }` for the whole call and dispose it in `finally`. After an `IOException`, `ensureActive()` rethrows cancellation.
- **Test:** `OkHttpSourceFetcherTest.cancellationDuringAStalledBodyReturnsPromptly` (1 KB, then a 60 s stall).

### S-7. A whitelisted EPG channel with no programmes stays `imported = 0`, so a full XMLTV re-parse runs after every build and refresh
- **Where:** `core/.../epg/store/ImportSession.kt:84-88`. `imported` was set only in `decide()`, which only `acceptProgramme` reaches.
- **Effect:**
  - `EpgMatchRepository.pendingRefs` keeps reporting the channel. The cause is common: a provider's EPG lists a channel with an empty guide.
  - So every build and every playlist refresh enqueued an `epg-patch`, which re-parses the whole XMLTV file (60–90 s on a stick).
  - The patch could not mark the channel either.
- **Fix:** `finish()` accepts every known `<channel>` that the whitelist accepts. A patch also accepts requested channels whose `<channel>` element disappeared, since `begin()` had deleted their rows. Repair then writes `programme_count = 0`.
- **Test:** `EpgImportedFlagTest` (build and patch cases). Both tests fail on `1639b70`.

### S-8. `TextDedup` statements leak on a failed, cancelled or erroring build
- **Where:** `core/.../epg/store/EpgStoreImpl.kt:151-167` (rebuild), `:255-273` (in-place) and `:383-403` (patch).
- **Problem:** `texts.close()` was skipped by the failed-source `return` and by every exception. On the bundled driver, `close()` with an unfinalized statement leaves a zombie connection. That connection keeps the deleted `.building` file's space and the page cache until the process dies. It bites on the common bad-download path, right before the retry with the last good file and its disk check.
- **Fix:** `try/finally` around the source loop in all three paths.
- **Test:** `EpgStatementLeakTest.failedSourceClosesEveryStatement`, which uses a driver wrapper that counts open statements. The cancellation variant passes on both versions, so it is a guard rather than a regression test.

### S-9. Every `has_data` flag reads 0 during an EPG build and after a failed one
- **Where:** `data/.../repo/EpgMatchRepository.kt:68-74`.
- **Problem:** `whitelistFor` took `withData` from the generation being built, where `programme_count` is 0 until repair runs.
- **Effect:** for the whole build, and permanently after a failed build until the next success:
  - the "matched without data" lists (S5/S13) are wrong;
  - `resolvedRefs` loses its preference for a source that has data.
- **Fix:** start from the published `has_data` set (`EpgMatchDao.xmltvIdsWithData`), joined with any positive counts. `afterBuild` still sets the new values.
- **Test:** `EpgMatchRepositoryTest.whitelistDuringABuildKeepsThePublishedHasData`.

### S-10. A transient download failure can publish a guide without that source
- **Where:** `data/.../repo/EpgRepository.kt:191-196`, together with `enforceCachePolicy`.
- **Problem:** the default `IF_FREE_OVER_1GB` policy deletes `.src` files on low-storage sticks. A source that then hit a transient error had no fallback, dropped out of `picked`, and the rebuild published without it.
- **Fix:** on a transient error with no cached file, for a source that has data (`programme_count > 0`), keep the current generation and return the error so the worker retries. Permanent errors (such as 404) still rebuild without the source.
- **Test:** `EpgRepositoryTest.transientFailureOfASourceWithoutCachedFileKeepsTheCurrentGuide`.

### S-11. Duplicate channels: when the first copy disappears, the favourite on the second copy is lost
- **Where:** `core/.../refresh/PlaylistDifferImpl.kt:162-186, 215`.
- **Problem:** `~n` suffixes follow playlist order (§3.2.1). Take `[X(u1) → k, X(u2) → k~2]` with the user's favourite on `k~2`, the working copy. If the provider drops `u1`, then `u2` takes `k` as an update and `k~2` goes missing, later purged. The S7 auto-remap only considers inserts, so it never fired.
- **Fix:** an update that changes the URL now also records `newUrlHash → key`. At finish, a newly missing slot with that URL is remapped to the key only when both keys are in the same occurrence family (`key` / `key~n`). A URL moving between unrelated channels is still never remapped.
- **Tests:**
  - `PlaylistDifferTest`: "dropping the first of two duplicates moves the second's user data with its stream";
  - `PlaylistDifferTest`: "a url moving between unrelated keys is not remapped".
- **Limitation:** a full fix (occurrence assignment that is aware of URLs, so swaps keep their keys) changes the key semantics and needs a design amendment. See "Later".

### S-12. Xtream `direct_source` is persisted untemplated
- **Where:** `core/.../xtream/XtreamCatalogDecoderImpl.kt:57`.
- **Problem:** the decoder copied it into `extras_json.attrs`. It is the panel's upstream URL, often with a reseller's credentials or tokens. The differ never templates Xtream playlists, and nothing reads the field.
- **Fix:** the field is no longer stored.
- **Test:** `XtreamCatalogDecoderImplTest.directSourceIsNotStored`.

### S-13. Runtime exceptions escape the refresh and EPG runs with no `refresh_log` row
- **Where:**
  - `data/.../repo/RefreshRepository.kt:177-183` caught only `SourceException`, `CancellationException` and `NotImplementedError`;
  - `EpgRepository.kt:216-222` and `:265-273` had the same gap.
- **Problem:** SQLite errors (for example a full disk on a stick), I/O errors on the cached file, and `IllegalStateException` all escaped the worker. The result was `Result.failure` with no log row and no `last_status`, against §3.10 step 6.
- **Fix:** a final `catch (e: Exception)` that logs and returns `Internal(<class name>)`. Only the class name is kept, because messages can hold URLs.
- **Tests:**
  - `RefreshRepositoryTest.unexpectedExceptionIsLoggedAsFailure`, which also asserts the password is not in `error_detail`;
  - `EpgRepositoryTest.runtimeErrorDuringBuildIsLoggedAsFailure`.

---

## Later (not applied)

### Identity and merge
1. **Groups are marked missing by key only.**
   - Where: `PlaylistDifferImpl.kt:83` builds `groupKeysSeen` from keys alone, and `RoomMergeSink.updateGroups` compares only `group_key`.
   - Effect: when a LIVE `g:sports` disappears but a MOVIE `g:sports` stays, the LIVE group is never marked missing. It keeps showing with 0 channels.
   - Fix: carry `kind:key` in `groupKeysSeen` and in the SELECT. In the meantime, M3 can filter `channel_count > 0`.
2. **`url_hash` depends on process state** (`RefreshRepository.syncSources`).
   - Cause: `Redact.text` masks every secret registered so far in the process.
   - Effect: when the hash changes, `syncSources` clears the validators and starts a full EPG import. This only happens when secrets overlap.
   - Fix: one deterministic helper (template, then strip query, userinfo and XC path) that does not read the global secret set. `addUserSource` should use the same formula.
3. **`RefreshRepository.template` replaces the password before the username.** A username that contains the password keeps a partial literal. Fix: segment- and value-aware templating (`CredentialTemplater`).
4. **Accepted residual risk on lists that are not Xtream-shaped** (lead decision in the design review).
   - Tokens and minority credential pairs stay in `channel.url`, `catchup_source`, `headers_json` and the Kodi/VLC extras.
   - `UrlKey` keeps "the last two segments" for paths like `/u/p/102.mp4`, which the short Xtream form does not recognise, so `stable_key` can hold the password.
   - Cheap hardening: drop the segments that `Redact` would mask.
5. **Ordering around `etag`.** It is persisted in `finish` before step 4 (header and EPG sources). If step 4 crashes, the next refresh gets a 304 and never re-stores the header. Fix: write the header inside the finish transaction.
6. **Two small leftovers:** `ChannelPrefsRepository.edit` does an unprotected read-modify-write, and `ChannelRepository.toChannel` still catches `NotImplementedError` (a stub leftover).
7. **Duplicate swaps are still order-based** (S-11 covers only the "first copy dropped" case). A real fix needs URL-aware occurrence assignment, which is a design amendment.

### EPG store and matcher
8. **Readers can throw `SQLITE_BUSY` on the guide thread.** Readers use `busy_timeout = 250` (`EpgSchema.kt:99`), while a patch commit or an in-place spill takes an exclusive lock (DELETE journal). M3 must catch it, or raise the timeout and commit patches in smaller batches.
9. **Trailing content after `</tv>` counts as truncation** (`XmltvParser.kt:100,179,445`). For files smaller than about 8 KB, every programme is dropped. Fix: look for the last `</tv>` in the tail.
10. **Search returns CONTINUATION segments as duplicate hits** (`FtsSupport.kt:52-66`). Fix: filter on the flag.
11. **`GenerationHandle.release` deletes the retired file on the closing thread**, which may be the UI thread (`EpgStoreImpl.kt:646-649`). Fix: move the delete to IO.
12. **`NameNormalizer.countryHint` still runs two regexes per EPG display name** (`EpgMatcherImpl.kt:139,175`). It is inside the budget.
13. **`matchIncremental` re-matches every unmatched subject on each refresh.** Unmatched subjects have no `epg_match` row, and the return value checks every subject's refs. With S-7 fixed this no longer loops patches, but it does wasted work.
14. **Interleaved XMLTV files need a patch after the build** (only tvg-id matches are whitelisted at build time). Accepted by the design.
15. **After a failed in-place build, new texts stay out of FTS** until the next build.
16. **`TextDedup` merges texts whose 64-bit hashes collide.** Negligible.
17. **Before `recover()` finishes, the disk check treats "no generation" as fatal.** The repository awaits recovery first, so this is minor.
18. **A patch uses the current time for its window,** not the generation's build-time window.
19. **The `RepairPass` constructor leaks earlier statements** if a later `prepare` throws.
20. **Removing a source does not trigger a rebuild.** If the remaining sources then answer 304, the build is skipped and the generation keeps the removed source's data until the next change.

### Xtream, catch-up and network
21. **`Call.await` can leak the response** if cancellation races the `isActive` check. Fix: `cont.resume(response) { response.close() }`.
22. **`XtreamClient.isEmptyPayload` reads the file on the caller's dispatcher** (`XtreamClient.kt:105,226-231`). Fix: `withContext(io)`.
23. **`XtreamClient` downloads have no byte cap,** unlike `SourceFetcher.maxBytes`.
24. **`XtreamClient.kt:75`: `System.currentTimeMillis()` in the Hilt constructor.** Fix: inject `WallClock`.
25. **`PlaylistRepository.kt:164-171`: the fallback `TimeBase`** for an Xtream playlist without an account row ignores the token-zone setting and the padding clamp.
26. **`XtreamDecoders.kt:310`: `epgListings` reads panel-local times in `effectiveTokenZone`,** so a user's catch-up token-zone override also shifts `short_epg` times. Decide whether this should be `serverZone ?: serverOffset` only.
27. **`CatchupUrlBuilderImpl.kt:65`: a programme still on air gets `E = stop`,** which is in the future, so Flussonic asks for future archive. Use `min(E, N)` or treat it as open-ended.
28. **Guidance for M2:** substitute `{{u}}`/`{{p}}` *after* `TemplateExpander`. Otherwise a password that contains `{utc}` gets expanded.

### Workers and scheduling
29. **`epg-patch` uses `APPEND_OR_REPLACE`,** so every request appends another link to the chain. Each extra run is a cheap no-op once S-7 is in. `KEEP` would avoid the chain.
30. **A new periodic work runs immediately.** With S-1/S-2 this is safe but duplicates the first refresh (a 304). Consider `setInitialDelay(interval)` on the periodic requests.

---

## Verified correct (no action)

- **StableKey chain:**
  - The priority order and the B6 gate (shaped vote plus pair equality).
  - `UrlKey` drops the credential segments of any parseable Xtream URL (B3d).
  - `forGroup` keys (B5).
  - `ExistingIndex` collision side table, and literal `~n` keys never colliding with suffixes.
- **Merge:**
  - Shrink guard; grace and purge (7 days and 2 refreshes, exact boundary); tombstones written on purge and deleted on re-insert.
  - S7 auto-remap uniqueness.
  - `UserDataRemapper` (`UPDATE OR IGNORE`, then delete, five tables, inside the finish transaction).
  - User data is keyed only by `(playlist_id, stable_key)`, and no Room entity references an EPG row id.
- **Catch-up:**
  - `xc` uses `effectiveTokenZone` (the panel zone), never the device zone. UTC is used only with the `PanelZoneUnknown` diagnostic, and `templateZone` = UTC for M3U only (B1).
  - `catchup-correction` is subtracted, and the channel attribute overrides the playlist value.
  - `TimeBaseFactory` rejects offset ids and unparseable zones, and falls back to the measured offset when tzdata is stale. Skew is capped at 24 h.
  - The S17 depth check and padding clamp, the 180-min open-ended cap, and single-pass `TemplateExpander`.
- **EPG:**
  - The generation pointer rename is the commit point. Recovery is correct after a crash between rename and pointer write, and hot journals roll back.
  - Readers keep their generation through cleanup, and retired files are deleted at the last release.
  - `patchSeq` re-reads `max_len_min` and titles.
  - The window query includes programmes that start before the window, and `max_len_min` is right after repair, split and patch.
- **Matcher:** overrides first, ranks 0–2 auto, rank 3/4 auto only when unambiguous after the country filter, digit guard, and fuzzy AUTO only at ≥ 0.92 with a margin of 0.02.
- **Credentials:**
  - Tink and Keystore wipe only on permanent loss (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, keyset `AEADBadTagException`, or the master key alias confirmed absent). A transient failure retries once and then returns `UNAVAILABLE` → `Result.retry()`.
  - One undecryptable row → `CORRUPT`.
  - Crypto runs on `@IoDispatcher`.
- **Workers:**
  - `WorkResults`: transient errors (network, 408/429/5xx, `cred.unavailable`) retry for at most 3 runs; everything else fails.
  - Both import workers override `getForegroundInfo()` (B4).
  - `setForeground` catches `IllegalStateException` (`ForegroundServiceStartNotAllowedException` on API 31+). The 3-argument `ForegroundInfo` is used on API 29+.
  - The manifest declares `FOREGROUND_SERVICE_DATA_SYNC` and `SystemForegroundService` `dataSync`. The default WorkManager initializer is removed, and `HiltWorkerFactory` is wired.
- **Android pitfalls:**
  - No `String.format`, `java.nio.file`, `System.getenv` or reflection in main code.
  - The regexes are ICU-safe. Kotlin `lowercase()` is `Locale.ROOT`. The `windows-125x` charsets exist on Android.
  - `StatFs` falls back to `usableSpace`. `KeyPermanentlyInvalidatedException` is API 23, below minSdk 25.
  - `Dispatchers.IO` appears only in `DispatchersModule`, and the `BoxLog` sink redacts stack traces.

## Test gaps that remain

- Nothing exercises the desugared `java.time` tzdb on API 25, the stale-tzdata fallback path in particular. Add an instrumented check on an API 25 image.
- No reader query runs while a patch commits (`SQLITE_BUSY`, Later 8).
- No XMLTV file has content after `</tv>` (Later 9).
- The cancellation half of S-8: no test cancels a build mid-source with `TextDedup` statements already prepared.
- No API 34 run of the background `setForeground` rejection (I-9), at least not in this pass. The emulator was busy.
- Nothing checks that the `KeyStore` alias probe (`masterKeyAbsent`) answers `false` on Fire OS after a Keystore reset. That needs device evidence.
