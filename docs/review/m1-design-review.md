# M1 data-layer design: adversarial review

Reviewer pass over `docs/design/m1-data-layer.md` (proposed 2026-10-13), 2026-10-13.
Cross-checked against `00-master-plan.md` §3, `research/protocols-m3u-xmltv-xtream.md` §1–§4, `review/architecture-review.md` B3/B5/B7/C/E, `review/product-review.md` §4.2–4.3, `review/m0-review.md`, the M0 code in `core/` and the build files.

All Blockers and Should-fix items below were applied in place to the design doc. Each changed passage there is marked `(reviewed 2026-10-13: …)`. Nits were not applied, except the ones that sat inside a passage I was already editing.

Scratch evidence (not committed) is under the session scratchpad: `jvmtest/FtsCheck.java`, `jvmtest/KxmlCheck.java`, and `contracts/`, a Gradle JVM project that compiles every `package …` code block of §1.2 with Kotlin 2.4.21 and runs probe tests against it.

---

## (A) Verified assumptions

| # | Claim in the design | Verdict | Evidence |
|---|---|---|---|
| A1 | `androidx.sqlite:sqlite-bundled:2.6.2` has a JVM variant usable from a plain Kotlin/JVM module | **True** | The `.module` file has `jvmApiElements-published`/`jvmRuntimeElements-published` (`org.gradle.jvm.environment=standard-jvm`) → `sqlite-bundled-jvm:2.6.2`. The jar ships `natives/{linux_x64,linux_arm64,osx_x64,osx_arm64,windows_x64}`. The scratch Gradle JVM project (`kotlin("jvm") 2.4.21`, `implementation("androidx.sqlite:sqlite-bundled:2.6.2")`) resolved and ran it |
| A2 | FTS5 is compiled into the bundled build | **True** | On this Mac (JVM natives): `sqlite_version() = 3.50.1`; `PRAGMA compile_options` lists `ENABLE_FTS3`, `ENABLE_FTS4`, `ENABLE_FTS5`, `ENABLE_STAT4`, `THREADSAFE=2`, `TEMP_STORE=3`, `SECURE_DELETE`, `MAX_MMAP_SIZE=0x7fff0000`. These ran successfully: an `fts5(... content='epg_text', content_rowid='id', tokenize='unicode61 remove_diacritics 2')` table, `'rebuild'`, `MATCH '"tele"*'` finding "Télé Matin" with `bm25`, the FTS4 fallback DDL, `WITHOUT ROWID` + PK range plan (`SEARCH programme USING PRIMARY KEY (epg_channel_id=? AND start_min>? AND start_min<?)`), `journal_mode=OFF` → `DELETE`, and `open(path, SQLITE_OPEN_READONLY)`, which rejects writes with code 8. The Android AAR (`sqlite-bundled-android-2.6.2.aar`, all four ABIs) carries the same `ENABLE_FTS5`/`TEMP_STORE=3`/`THREADSAFE=2` strings |
| A3 | Day-0 check runs "on macOS arm64" | **False for this Mac** | The dev Mac is an Intel i7 (`x86_64`). The jar has `osx_x64` natives too and works (A2). The check text is corrected |
| A4 | `SQLITE_TMPDIR` must be set or index builds fail; `temp_store=FILE` | **False / moot** | Both the JVM and Android builds are compiled with `TEMP_STORE=3`, so temp storage is **always memory** and the pragma and env var are ignored. The real risk moves to native memory during `CREATE INDEX`/FTS `rebuild`/`ANALYZE` on a 1M-row build (see S1) |
| A5 | `androidx.sqlite:sqlite` (API) has a JVM artifact | **True** | `sqlite-2.6.2.module` → `sqlite-jvm`/`sqlite-android`. `SQLiteDriver`, `SQLiteConnection`, `SQLiteStatement` and the `execSQL` extension compile from `:core`. `open(String, Int)` exists only on `BundledSQLiteDriver` (not on the `SQLiteDriver` interface), which matches the design's `driver is BundledSQLiteDriver` branch |
| A6 | Android consumers of `:core` get the AAR, not the JVM jar | **True (by metadata)** | Variant selection is per consumer. `:data`/`:app` request `jvm.environment=android` → `androidRuntimeElements-published` → `sqlite-bundled-android`. Day-0 check 2 should pass. It stays in the list as a build-time assertion |
| A7 | Tink 1.23 API names | **True, one omission** | `tink-android-1.23.0.jar` (latest release on Central): `AndroidKeysetManager.Builder().withSharedPref(Context,String,String)` (throws `IOException`), `.withKeyTemplate(KeyTemplate)`, `.withMasterKeyUri(String)`, `.build()`, `getKeysetHandle()`, `isUsingKeystore()`; `KeyTemplates.get(String)`; `AeadConfig.register()`; `RegistryConfiguration.get()`; `KeysetHandle.getPrimitive(Configuration, Class)`. The design chains `.build()` straight into `getPrimitive`, so it was missing `.keysetHandle` (fixed) |
| A8 | Room 2.8.5 with a driver: does it enable `foreign_keys`? | **True: Room does it on every connection** | `room-compiler-2.8.5` `OpenDelegateWriter` emits `connection.execSQL("PRAGMA foreign_keys = ON")` in `onOpen` when any entity declares a FK. `room-runtime-2.8.5` `RoomConnectionManager` calls `openDelegate.onOpen` for the first connection *and* for every pooled connection (`configurationConnection`), and sets `synchronous=NORMAL` (WAL) and `busy_timeout` per connection. `InvalidationTracker` sets `temp_store=MEMORY`. `RoomDatabase.Callback.onOpen` runs **once** (first connection only), so the design's callback pragmas were redundant at best and would not reach pooled connections. Callback removed (S2) |
| A9 | Room KSP works alongside raw `androidx.sqlite` in one module | **True** | `room-runtime-android:2.8.5` depends on `sqlite-android:2.6.2` and `sqlite-framework-android:2.6.2`, the same version as the catalog. KSP only processes Room annotations. `RoomDatabase.useWriterConnection` ends with `invalidationTracker.refreshAsync()`, so raw writes in `RoomMergeSink` do notify Flows. It is `suspend`, so the blocking `MergeSink` needs a `runBlocking` bridge (now specified) |
| A10 | Room's `@Fts4(contentEntity = …)` sync triggers | **Verified, with a cost the design missed** | `FtsEntity` generates `CREATE TRIGGER … BEFORE UPDATE ON \`channel\` BEGIN DELETE FROM \`channel_fts\` …` with no column list, plus an `AFTER UPDATE` insert. **Every** channel `UPDATE` (each `ChannelTouch` of `sort_index`/`missing_since`) rewrites FTS rows (S6) |
| A11 | kxml2 on the JVM, `relaxed` feature | **True, with parity caveats** | `net.sf.kxml:kxml2:2.3.0` exists and bundles `org.xmlpull.v1.*` + `KXmlParser`. `setFeature(".../features.html#relaxed", true)` is accepted. With relaxed, `A&nbsp;B & C &#233;` parses without error, but `&nbsp;` is **dropped** (`"AB"`), not replaced. Without relaxed: `unresolved: &nbsp;`. kxml2 2.3.0 **rejects** `FEATURE_PROCESS_DOCDECL`, which Android's `Xml.newPullParser()` turns on, and Android's KXmlParser refuses `defineEntityReplacementText` while DOCDECL processing is on (from libcore source, recalled, not executed here). Encoding from the declaration works (`ISO-8859-1`). Fix: S4 + instrumented test I-8 |
| A12 | Android platform parser supports `relaxed` | **Unverified on device** | Not executable here. I-8 (new) asserts it on the API 30 emulator |
| A13 | §1.2 contracts compile | **True** | All 18 `package …` blocks + the M0 `TimeBase`/`Programme`/`Playlist` + the `StableKey` additions compile with no warnings (Kotlin 2.4.21, okio 3.18.1, sqlite 2.6.2, coroutines/serialization 1.11.0, xz 1.10, collection 1.5.0, kxml2 compileOnly). The existing `ModelTest` passes 6/6 with the new `CatchupMode`. **After this review's amendments**, all 18 blocks were re-extracted and compiled again, and the fixes were exercised: `forGroup` keeps "Sports HD"/"Sports SD" apart, the oversized `.xz` now gives `SourceException(Unsupported)`, and `templateZone` = UTC while `xc` stays on Asia/Tokyo |
| A14 | `Compression` maps an oversized xz dictionary to `Unsupported` | **False** | Probe: a 32 MiB-dictionary `.xz` makes `decompress()` throw a raw `org.tukaani.xz.MemoryLimitException`. The block header is read on the next `sniff()`/`read()`, outside the `try` around the constructor (S3) |
| A15 | Double gzip unwraps | **True** | Probe: gzip(gzip("#EXTM3U")) → `#EXTM3U` |
| A16 | `NameNormalizer` table §3.1 | **True for every listed row**; two new defects | Probe output matches the table. But `Sports HD`/`Sports SD` → `sports`, `UK HD`/`UK SD` → `uk`, so distinct M3U groups collide on `g:<forKey>` (B5). `TV-5 Monde` → hint `tv` (Tuvalu), match `5 monde` (nit) |
| A17 | `XtreamStreamUrl` long form is safe to use without the playlist vote | **False** | Probe: `http://cdn.example.com/live/bbc/hd/1.m3u8` parses as Xtream (`user=bbc, pass=hd, id=1`), so rule 1 would key a non-Xtream channel `xc:1` (B6) |
| A18 | `Redact` | **Mostly true** | Long-form paths, `timeshift`, userinfo, query strings and headers are masked. The short form `http://h/u/p/102` is masked only after `registerSecret` (which needs ≥ 3 chars). Acceptable because per-row logging is banned |
| A19 | Catalog pins in §6.1 | **Partly false** | `okio 3.16.0` is wrong: OkHttp 5.5.0 requires **3.18.1** (its `.module`). Latest releases today: okio 3.18.2, xz 1.12, Robolectric 4.17, androidx.collection 1.7.0, androidx.test core-ktx/runner 1.7.0, `mockwebserver3` 5.5.0 (exists). Updated |
| A20 | Robolectric `sdk=35` with "JDK 17 also supported" (m0-toolchain) | **Caveat** | Robolectric's Android V (SDK 35) jar requires a Java 21 runtime (Robolectric 4.14 release notes, recalled). Local and CI both use 21, so this is fine, but the toolchain doc's "17 also works" no longer holds for `:data` tests |
| A21 | Emulator | **True** | `TheBox_TV_API30` is `android-tv/x86` (32-bit). The AAR has `x86` natives, and the debug build keeps x86 libs. A `Pixel_6_Pro_API_34` (x86_64) AVD also exists and is used for the API 31+ foreground-service rules (I-9) |
| A22 | Robolectric SQLite supports `WITHOUT ROWID`, FTS4 `unicode61 remove_diacritics=2`, `journal_mode=OFF` through `AndroidSQLiteDriver` | **Unverified** | Needs an Android Gradle module. Added as day-0 check 5. If it fails, `EpgBuildIT` drops to a fake `EpgStore` plus the JVM `:core` store tests |

---

## (B) Blockers (applied)

**B1. `xc` catch-up on Xtream-shaped M3U playlists would be formatted in UTC.** §3.8.3 maps `TokenZoneSetting.Auto` to `tokenZone = UTC` for M3U. `TimeBase.effectiveTokenZone = tokenZone ?: serverZone ?: …` then never reaches a panel zone, and `forM3u()` has no server info anyway. An M3U from `get.php` with `catchup="xc"` (the most common "M3U" an Xtream user pastes) would request the timeshift segment in UTC. That breaks the CLAUDE.md rule "Xtream timeshift times are in the panel's zone". **Fix:**
- Auto leaves `tokenZone = null` for every playlist type.
- `TimeBase` gains `templateZone` (zone for `{Y}…`/`{utc:FMT}` tokens when `tokenZone` is null; Auto: UTC for M3U, null for Xtream).
- `xc` always uses `effectiveTokenZone` (panel zone).
- For Xtream-shaped M3U playlists (`DetectedCredentials` present), the refresh worker logs in via `player_api.php` with the detected pair to fill `server_zone`/`server_offset`/`clock_skew`. This is best effort, and a failure keeps the last values.

**B2. Contracts that WP-D needs from WP-C were missing from commit 0.** `TimeBaseFactory` (and its undefined `settings` type), `XtreamUrls` (D builds the `xmltv.php` EPG source) and `XtreamLoginUrl` (D's "upgrade to Xtream") were listed as C's *exposed* API but not in §1.2, so D could not compile against them until C merged. Also:
- `XtreamApi.shortEpg`/`simpleDataTable` cannot parse panel-local `start`/`end` without a `TimeBase`.
- `EpgImportReport`/`EpgSourceStat` lack `@Serializable`, but D must write them to `refresh_log.report_json`.
- D's tests use B's `XmltvGenerator`/`EpgTestDb` from `:core` *test* sources, which a different module cannot see.

**Fix:** added `TimeBaseSettings`, `TimeBaseFactory`, `XtreamUrls` and `XtreamLoginUrl` stubs to §1.2.8, a `timeBase` parameter on the two EPG calls, `@Serializable` on the EPG report types, and a `java-test-fixtures` source set in `:core` (`core/src/testFixtures/…`, owned by B) consumed by `:data` via `testFixtures(project(":core"))`.

**B3. Credentials would be stored in clear or in a reversible form.**
- (a) `playlist.header_json` stores `M3uHeader.epgUrls`, and a `get.php` M3U's header is `url-tvg="http://h/xmltv.php?username=U&password=P"`.
- (b) `epg_source.url_hash` is a 64-bit FNV of a credential-bearing URL. With the host and username known, a short password can be recovered by brute force in seconds.
- (c) `channel.catchup_source` is not templated, and `get.php` lists can put the credential path in it.
- (d) For a non-shaped playlist, `UrlKey` keeps "the last two path segments", which for `http://h/u/p/102` is `p/102`. The password ends up in `stable_key`, which is copied into favourites, prefs and backups.

**Fix:**
- `header_json` stores EPG URLs only through `Redact`-style templating, and the real URLs live in each `epg_source` credential blob.
- `url_hash` = `Hash64` of the **templated/redacted** URL, with collisions confirmed by decrypting.
- `catchup_source` gets the same `{{u}}`/`{{p}}` templating as `url`.
- `UrlKey` drops any segment pair that `XtreamStreamUrl.parse(url, allowShort = true)` identifies as credentials, whether or not the vote passed.

**B4. The expedited `PlaylistRefreshWorker` crashes on API ≤ 30 (every Fire TV).** WorkManager runs expedited work as a foreground service below API 31, which requires `getForegroundInfo()`. `CoroutineWorker`'s default throws `IllegalStateException`. On API 31+, `setForeground()` from the background can throw `ForegroundServiceStartNotAllowedException` (Onn/Google TV). **Fix:** both workers implement `getForegroundInfo()` (dataSync). `setForeground` is wrapped in `try/catch (IllegalStateException)` and the import continues without the FGS. New instrumented test I-9 runs on the API 34 AVD.

**B5. M3U group identity merges distinct groups.** `group_key = "g:" + forKey(groupTitle)`, and `forKey` strips quality tokens: "Sports HD"/"Sports SD" → `g:sports`, and "UK HD"/"UK SD" → `g:uk` (probe output). The unique index and the pass-1 `LinkedHashMap<groupKey, GroupRow>` then fold two provider groups into one, and `group_prefs` (hidden/locked/order, user data) is keyed by it. That identity is persisted, so it must be right before ship. **Fix:** new `NameNormalizer.forGroup(name)` (fold + whitespace/punctuation collapse, **no** quality or bracket stripping), used for `g:` keys and for the group half of `ng:` keys.

**B6. StableKey rule 1 applies the long-form Xtream regex to any M3U.** URLs like `http://cdn/live/<a>/<b>/<digits>.m3u8` are common on non-Xtream CDNs. They would get `xc:<digits>` keys, which collide across channels and resolve to order-dependent `~n` suffixes. It also makes `CatchupResolver` step (f) and the templater treat them as Xtream. **Fix:**
- On M3U playlists, rule 1 by URL requires `ctx.xtreamShaped` **and** the parsed pair equal to `ctx.credentials`.
- `CatchupResolver` gets `xtreamShaped` through `PlaylistCatchupDefaults` and uses `allowShort = xtreamShaped`.
- The design note "short form only when shaped" becomes "Xtream URL detection only when shaped".

---

## (C) Should-fix (applied)

| # | Area | Problem | Fix applied |
|---|---|---|---|
| S1 | epg.db temp storage | `TEMP_STORE=3` (A4): `SQLITE_TMPDIR` and `temp_store=FILE` do nothing, and sorter spill for `CREATE INDEX programme_by_text` (1M rows), `epg_text_hash` and the FTS rebuild lives in **native memory** (estimate 30–60 MB peak on the 1M case). D4's rationale ("temp directory is a known trap") is moot | Removed `SqliteTmpDir` and `temp_store=FILE`. I-5 now records native heap (`Debug.getNativeHeapAllocatedSize`) during the build, with a budget of ≤ 64 MB native peak. `EpgSchema` gets a switch to create `programme_by_text` **before** the bulk load (no sort, slower inserts) if I-5 or D8 exceeds it. `PRAGMA secure_delete=OFF` on EPG writer connections (the build compiles it on) |
| S2 | Room setup | Callback pragmas were redundant (A8) and would only reach one connection | Callback removed. The text states what Room already does |
| S3 | `Compression` | Raw `MemoryLimitException` escapes (A14) | `decompress` wraps the XZ stream in a source that maps `MemoryLimitException` → `SourceException(Unsupported)` on read. Test added |
| S4 | XMLTV parser config | JVM/Android parity (A11): DOCDECL on by default on Android, entity replacement forbidden there, relaxed drops unknown entities | `XmltvParser` (B) sets every feature itself in try/catch: DOCDECL off, NAMESPACES off, relaxed on, then after `setInput` `defineEntityReplacementText` for the HTML entity list (`nbsp` → U+00A0, …). The factory is a bare constructor. New I-8 parses `entities.xml` on the emulator and compares with the JVM result |
| S5 | Xtream object-form lists | `{"0":{…},"1":{…}}` was decoded as one `JsonObject`: a 20k-stream panel is roughly 100 MB of tree, an OOM on a stick | Object form streams through a custom map-entry `DeserializationStrategy` that hands each value to the consumer and keeps none (`Json.decodeFromStream`) |
| S6 | `channel_fts` | Room content-sync triggers fire on every `UPDATE` (A10): a refresh that re-orders 50k channels rewrites 50k FTS rows | `channel_fts` becomes a standalone `@Fts4` entity (`rowid = channel.id`, `name`), maintained by `RoomMergeSink` only on insert, rename and purge. +~5 MB for 200k names |
| S7 | Key churn | A provider adding or fixing a `tvg-id` moves a channel from `url:`/`ng:` to `tvg:`. A rename of the name half of a `tvg:` key does the same. User data is silently orphaned (the mass-remap prompt only fires at ≥ 70 %) | The differ pairs *newly missing* slots with *inserted* entries that have the same `urlHash` (1:1, unique on both sides) → `MergeFinish.autoRemaps`, applied by the sink through `remapUserData` in the finish transaction. New report counter `keysMigrated` |
| S8 | Orphan cleanup | Orphan age was taken from `favourite.added_at`/`channel_prefs.updated_at`, so a two-year-old favourite was deleted the day its channel was purged | New `purged_key(playlist_id, stable_key, purged_at)` tombstone written by `finish`, cleared when the key returns. Maintenance deletes user data only for tombstones older than 180 days |
| S9 | Import concurrency | Periodic playlist and EPG workers are separate unique works and can overlap. The 20 MB heap budget assumed they never do | One process-wide `ImportMutex` (D) is held by the playlist merge phase and by EPG build/patch. `EpgWriteLock` remains for EPG-only ordering |
| S10 | Conditional GET | Not said when `etag`/`last_modified` are persisted. If stored before a merge that then fails, the next refresh gets 304 and the merge is never retried | Persisted only in `finish` (playlist) or after a successful build (EPG source) |
| S11 | Matcher name stages | `forMatch` drops brackets and country prefixes, so "HBO (East)"/"HBO (West)"/"UK: BBC One" collide, and rank 3/4 AUTO picks the first seen. Wrong mappings are "worse than none" (product §4.3) | Name stages compare `forKey` first, then `forMatch`. AUTO only when the normalised name maps to exactly one EPG channel id in the source after the country-hint filter. Otherwise SUGGESTED |
| S12 | Repair pass | 6 h `CONTINUATION` segments of a 30 h "Off air" placeholder clip real programmes that follow | An overlapping `CONTINUATION` row is deleted instead of clipping its predecessor. Real rows always win over continuations |
| S13 | Reader API | (a) On a patch the design keeps the reader and only re-runs the window, but patch can add `epg_channel` rows (§2.6), so new mappings stay `-1`. (b) `channels()` returns a lazy `Sequence` that holds a connection lock and a statement: a consumer that stops early (`first {}`) leaves them held | Guide re-`resolve()`s and refreshes its `max_len_min` cache on `patchSeq` change. `channels()` → `forEachChannel(sourceId, block)` (callback, always closes) |
| S14 | Playlist-derived EPG sources | Upserting `PLAYLIST_HEADER`/`XTREAM` sources by URL would delete and recreate rows when a tokenised URL changes. `epg_mapping.source_id … ON DELETE CASCADE` then deletes the user's manual mappings | Identity `(playlist_id, origin, ordinal)` (new column + unique index). The URL is updated in place, and the id is stable |
| S15 | M3U charset | A permanent switch to 1252 after one bad line garbles a UTF-8 file with a single corrupt line | Per-line decision: strict UTF-8 first, else the fallback chosen at the first failure |
| S16 | Credential store | Any `GeneralSecurityException` wiped **every** credential row. A transient Keystore failure at boot (seen on Fire OS) would destroy all logins | Only `KeyPermanentlyInvalidatedException`/`UnrecoverableKeyException`/`AEADBadTagException` on the keyset → wipe. Other `GeneralSecurityException`/`ProviderException`/`IOException` → retry once after 1 s, then `Err(Credentials(UNAVAILABLE))` (new enum value) with nothing deleted |
| S17 | Catch-up probing and window | (a) `shift`/`timeshift=` on an Xtream-shaped URL can never work (XC panels don't serve `?utc=`), and the probe cannot cross modes. (b) The archive-depth check used the padded start, so padding pushed a programme at the edge of the window to `Unsupported` | (a) `variants()` appends `xc-path, xc-php` when `target.xtream != null` and the mode is `shift`. (b) The depth check uses the corrected start without padding, and `S` is clamped to the window |
| S18 | Mass-remap counts | `added` counts VOD while the threshold compares with `live + radio` | All three terms (active before, newly missing, added) count LIVE+RADIO only |
| S19 | `pastDays` | Product "Auto (max catch-up days, minimum 1)" had no owner | `DataSettings.pastDays` Auto = max resolved catch-up days over enabled playlists, clamped to 1..7 |
| S20 | Catalog | okio and others (A19) | §6.1 updated (okio 3.18.1, which **must** match OkHttp) |
| S21 | Test plan / CI | Day-0 check said arm64. CI ran `:core:test` only. JVM perf budgets on shared runners would flake. Nothing tested API 31+ FGS rules | Check text fixed. CI adds `:data:testDebugUnitTest`. `perfTest` on CI is report-only (budgets enforced locally/nightly with 1.5× tolerance). I-8, I-9 added. Day-0 check 5 (Robolectric SQLite) added |
| S22 | `MergeSink` bridging | The blocking sink must call `suspend` Room APIs | Specified: `RoomMergeSink` uses `runBlocking { db.useWriterConnection { it.immediateTransaction { … } } }` on the importer thread. Never on Main |

---

## (D) Nits (not applied unless noted)

1. `StableKey.withOccurrence` is an extension declared *inside* the companion, so callers need `import …StableKey.Companion.withOccurrence`. Make it a member function. **Applied**, because the block was already being edited.
2. `KeyRule.XTREAM("xc")` has no colon, unlike the other prefixes. Fine as a doc, but don't use `prefix` with `startsWith` for parsing.
3. `NameNormalizer.PREFIX` accepts `tv`, `sd` and similar as country codes ("TV-5 Monde" → hint `tv`). Add a small deny-list (`tv`, `hd`, `sd`, `my`, `no`, `be`, `it` only when followed by `:`/`|`).
4. `findChannels`: `n.norm LIKE ? || '%'` cannot use the BINARY index (LIKE is case-insensitive). Use `norm >= ?1 AND norm < ?1 || char(0x10FFFF)`.
5. §3.8.4 uses `N` both for "now" and for the divisor in `{offset:N}`/`{duration:N}`. Rename the divisor `k`.
6. Directory `fsync` after the pointer rename (`FileChannel.open(dir, READ).force(true)` works on Linux) makes the commit point durable on power loss.
7. M14: Kodi ends a sticky `#EXTGRP` at an explicit `group-title` (protocols §1.4). The design keeps the sticky value. Either is defensible; document the deviation in the setting's help text.
8. `epg-import` with `APPEND_OR_REPLACE` can chain many imports. Each one is cheap thanks to the not-modified skip, but `KEEP` plus a "dirty" flag re-checked at the end is simpler.
9. `FetchRequest.maxBytes = 2 GB` for everything. Use 256 MB for playlists and 1 GB for EPG, and abort when free space would drop below 64 MB.
10. Open item (c): `get_series_info` `series=` fallback. Series is v1.1, so keep the code and drop the test gate.
11. Open item (b): keep UTC for M3U `{Y}` tokens. CLAUDE.md forbids a device-zone default, and the per-playlist setting is the escape hatch (now `templateZone`).
12. Open item (a): the 20 % / 70 % thresholds are fine as named constants. Record real refresh reports in beta before tuning.
13. `model/Playlist.kt` (M0) still carries `PlaylistSource.Xtream(server, …)` and `epgUrls` in clear. Commit 0 should align it with `display_source` + `credential_ref`, or mark it unused.
14. `XtreamEpgListing`/`direct_source` and per-channel `headers_json` may hold provider tokens. This is accepted residual risk, so note it in the threat model.
15. Robolectric needs JDK 21 for sdk=35. Update `docs/m0-toolchain.md` ("JDK 17 also works" no longer holds for `:data` tests).
16. The `EpgChannelInfo.programmeCount` tie-break is always 0 inside the whitelist callback (the current source is not imported yet). It is harmless, but the doc implies otherwise.

---

## (E) Work-package disjointness and the day-0 commit

**Verdict:** disjoint by package after the B2 fix. There are no circular dependencies: A, B and C depend only on lead-owned contracts, and D depends on A/B/C through interfaces only. The collisions I found and how they are resolved:

| Collision | Resolution |
|---|---|
| D needed C's `TimeBaseFactory`, `XtreamUrls`, `XtreamLoginUrl` (not contracts) | Now §1.2 contracts + commit-0 stubs (B2) |
| D's tests import B's `epg/testutil` from another module's test sources | `core/src/testFixtures` (B owns), `testFixtures(project(":core"))` in `:data` |
| XML parser features would have been split between B (`XmltvParser`) and D (`Xml::newPullParser` factory in `EpgModule`) | All features set inside B's `XmltvParser`. The factory is a bare constructor (S4) |
| Group normalisation shared by A (`GroupKeys`) and D (browse/group prefs) | `NameNormalizer.forGroup` in the lead-owned contract (B5) |
| Auto-remap produced by A, applied by D | `MergeFinish.autoRemaps` contract field (S7) |
| `ImportMutex` used by D's workers only | D-owned (`data/.../work`). No cross-WP edit |
| `data/.../di/Qualifiers.kt` in commit 0, then D | Unchanged. Fine |

**Day-0 commit list (lead), in order:**
1. `gradle/libs.versions.toml`: §6.1 as amended (okio 3.18.1, xz, kxml2, collection, robolectric, junit4, androidx-test, sqlite API + framework, room-testing, work-testing, mockwebserver3, hilt-android-testing).
2. `core/build.gradle.kts`: §6.2 + `java-test-fixtures` + `perfTest`. `data/build.gradle.kts`: §6.3 + `testImplementation(testFixtures(project(":core")))`. `app/build.gradle.kts`: §6.4.
3. `core/src/main/kotlin/app/thebox/core/`: `model/CatchupMode.kt`, `model/Channel.kt`, `model/StableKey.kt` (member `withOccurrence`), `model/TimeBase.kt` (+ `templateZone`), `common/{Outcome,Cancel,Hash64,Redact,BoxLog}.kt`, `io/Compression.kt` (xz mapping), `text/NameNormalizer.kt` (+ `forGroup`), `source/{XtreamStreamUrl,SourceTypes}.kt`, `m3u/M3uContracts.kt`, `refresh/RefreshContracts.kt` (+ `autoRemaps`, `keysMigrated`), `epg/EpgContracts.kt` (+ `@Serializable` reports, `forEachChannel`), `match/MatchContracts.kt`, `xtream/XtreamContracts.kt`, `catchup/CatchupContracts.kt` (+ `TimeBaseSettings`, `TimeBaseFactory`, `xtreamShaped`).
4. `data/src/main/kotlin/app/thebox/data/contracts/DataContracts.kt` (+ `timeBase` params; `CredentialFailure.UNAVAILABLE` lives in `common`), `data/.../di/Qualifiers.kt`.
5. Stubs (`TODO("WP-x")`) at final paths: everything in the §1.2 stub table **plus** `catchup/TimeBaseFactoryImpl.kt`, `xtream/XtreamUrls.kt`, `xtream/XtreamLoginUrl.kt` (C), and `core/src/testFixtures/.../epg/testutil/{XmltvGenerator,EpgTestDb}.kt` (B).
6. `.github/workflows/android.yml`: `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug`, and upload `data/build/reports/tests/` on failure.
7. Day-0 checks before splitting the work:
   - (1) bundled FTS5 on the dev Mac (x86_64) and Linux CI. The Mac half was **already verified** in this review (SQLite 3.50.1, `ENABLE_FTS5`).
   - (2) one `libsqliteJni.so` per ABI in the APK and no `natives/` jar entries.
   - (3) no `org/xmlpull` in the APK.
   - (4) `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug` green.
   - (5) a one-test Robolectric run: `AndroidSQLiteDriver` + `WITHOUT ROWID` + FTS4 `unicode61 "remove_diacritics=2"` + `journal_mode=OFF`.

**Lead decisions needed:**
- Accept the +1 login request per refresh for Xtream-shaped M3U playlists (B1).
- Accept the residual risk that non-shaped M3U playlists keep per-URL tokens and credentials of *other* pairs in `channel.url` (B3, D14).
- Choose whether `programme_by_text` is built before or after the load. The default stays "after", switching if I-5 shows native peak > 64 MB (S1).
- Approve the `purged_key` table and the standalone `channel_fts` (schema additions S6, S8, S14).
