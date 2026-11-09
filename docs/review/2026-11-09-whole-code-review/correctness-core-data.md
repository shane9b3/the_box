# Correctness review: `core` and `data` (2026-11-09, main after 2246f66)

This review covers correctness only. Security, performance and the player, ui and app modules were left to other reviewers.

**How it was done**
- No project file was edited and no test was run or added.
- Every finding below was confirmed by reading the whole path: the callers, the decision notes in `docs/decisions/` and the earlier reviews in `docs/review/`.
- Two parallel helpers covered part of the scope:
  - core sports, update, setup/lan, settings, guide state, parental and editor;
  - the data repositories, guide, work, storage, sports, settings and DAOs.
- I re-read the code behind every helper finding that is listed here.

**Overall:** the code is in good shape. The earlier milestone reviews removed the obvious bugs, and I found no High-severity bug that is not already logged. Most of what is left sits where two layers meet: restore on a new device, catch-up defaults that depend on in-memory state, and read-modify-write of prefs.

## Findings

### F1. Medium: restoring onto a new device drops EPG mappings and per-source overrides for playlist-provided guides
- **Where:** `data/src/main/kotlin/app/thebox/data/backup/BackupRestorer.kt:341-349` (EpgMapping), `:328-336` (EpgSource overrides) and `:418-428` (`resolveSource`).
- **Cause:** a playlist-derived `epg_source` row (`PLAYLIST_HEADER` or `XTREAM`) is created only by a refresh (`RefreshRepository.kt:168,173` `syncSources`). On a fresh TV the restored playlists have no such rows during the restore transaction. As a result:
  - `resolveSource` returns null, so `sourceId = -1` and every `epg_mapping` that points at that guide is skipped with `continue`;
  - the source-override UPDATE (enabled, priority, `shift_min`, `assume_zone`) matches 0 rows and is lost.
- **Scenario:**
  1. The user exports from an old box that has manual "Fix unmatched channels" mappings to the Xtream XMLTV guide and a +60 min shift on that guide.
  2. The user restores in Merge mode on a new box.
  3. The restore reports success. After the first refresh the mappings and the shift are gone, and channels fall back to AUTO matching.
- **Evidence:** `RestoreMergeTest.exportThenRestoreMergeIntoAnEmptyLibraryGivesTheSameUserTables` works around this by inserting the source and restoring a second time ("the second restore (after a "refresh") lands them"). Nothing in the product does that second pass, and no UI text asks the user to restore again (grepped `BackupPagesImpl` and `ui`).
- **Confidence:** confirmed by reading the code; the test comment acknowledges the behaviour.
- **Fix (pick one):**
  - **(a)** Have the restore create the derived `epg_source` rows itself. It knows (uid, origin, ordinal), and the URL can be filled in by the first refresh because `syncSources` already upserts by (playlist_id, origin, ordinal).
  - **(b)** Park unresolved mapping and override records in a small `pending_restore` table keyed by (playlist uid, origin, ordinal), and apply them in `syncSources` once the source exists.
  - Either way, add a test with no second restore.

### F2. Medium: the catch-up "Xtream-shaped" flag depends on a process-local cache, so guide and player can disagree
*(Found by the data helper; I re-read it.)*
- **Where:** `data/src/main/kotlin/app/thebox/data/repo/PlaylistRepository.kt:174-176` (`catchupDefaults`). The guide caches the result in `data/.../guide/GuideMetaCache.kt:60`.
- **Cause:** `xtreamShaped = type == M3U_URL && (secretCache[ref]?.username != null || accountDao.get(id) != null)`. `secretCache` is empty at process start and is cleared under memory pressure.
- **Scenario:**
  - The setup: an Xtream-shaped M3U whose channels advertise only `catchup-days` or `catchup-time` (`CatchupResolverImpl.autoMode` rule f), and whose detected-credential login failed, so there is no `xtream_account` row.
  - After a restart the guide's first meta build sees `xtreamShaped = false`, so catch-up is OFF and the archive is 0 days.
  - `GuideMetaCache` keeps that answer until the `playlist`, `epg_source` or `xtream_account` table changes. Loading the secret changes no table.
  - The player path (`ChannelRepository.defaults`) recomputes each time, so the guide shows no archive while the player would allow it. The result can also differ from one run to the next.
- **Second problem:** `M3U_FILE` is excluded from the check, although the differ's credential vote and the `xc:` key rule run for M3U_FILE.
- **Confidence:** confirmed by reading the code.
- **Fix:** derive the flag from stored state. Options are a `playlist.xtream_shaped` column written in `RoomMergeSink.finish` from `report.credentialsTemplated`, or `EXISTS(channel WHERE playlist_id=? AND key_rule='XTREAM')`. Include `M3U_FILE`.

### F3. Low: the Xtream TimeBase fallback ignores the user's token-zone setting and the padding clamp
- **Where:** `data/src/main/kotlin/app/thebox/data/repo/PlaylistRepository.kt:163-171`.
- **Cause:** an Xtream playlist with no stored `xtream_account` row, or whose `infoJson` no longer decodes after a model change, gets `TimeBase(...)` built directly. That drops:
  - `tokenZone`, so the Utc, Device or Fixed zone the user chose is ignored;
  - `templateZone`;
  - the `coerceAtLeast(0)` on the paddings.
- **Why it matters:** the `PanelZoneUnknown` diagnostic tells the user to "set the zone in playlist settings". In this state that setting does nothing.
- **Confidence:** confirmed by reading the code.
- **Fix:** add a `TimeBaseFactory.fromStored(serverZone, offset, skew, settings)` that shares `build()`, and call it here.

### F4. Low: `ChannelPrefsRepository.edit` is an untransacted read-modify-write of the whole row
- **Where:** `data/src/main/kotlin/app/thebox/data/repo/ChannelPrefsRepository.kt:26-29`. Used by `setHidden`, `setEpgShift` (from `PlaylistSettingsRepository.kt:357`) and the other `set*` calls.
- **Scenario:**
  - A playback-memory write (`recordWatched`, `upsertPlaybackFlags`, `upsertFpsMilli`) runs on the player thread for the same channel.
  - If it lands between `dao.get` and `dao.upsert`, the old `watch_count`, `last_watched_at`, `playback_flags` or `fps_milli` is written back.
  - The column-targeted DAO writes exist to prevent exactly this, but `edit` is the other side of the same race.
  - The window is milliseconds, so this is rare.
- **Confidence:** confirmed by reading the code; I did not reproduce it.
- **Fix:** wrap `edit` in `db.writeTransaction`, or add column-targeted upserts for the user columns.

### F5. Low: the Sports "Today" board stays on yesterday when left open past US Eastern midnight
*(Found by the core helper; I re-read it.)*
- **Where:** `data/src/main/kotlin/app/thebox/data/sports/ScoreRepositoryImpl.kt:505`, together with `core/.../sports/DayPollPolicy.kt:35`.
- **Cause:** `dayKey` is fixed by the first `today()` call and is cleared only when the tab or picker is reopened, or when demand goes from NONE to SEEN (`:452-464`).
- **Scenario:**
  - The tab is opened at 23:00 ET and left on.
  - After midnight it keeps polling `day("<yesterday>")`, still labelled `isToday = true`.
  - Once every game is FINAL, `DayPollPolicy.nextAt` returns null and the stale board never refreshes. NHL late games end around 01:00 ET, so this is realistic.
- **Confidence:** confirmed by reading the code.
- **Fix:**
  - When `dayWanted == null`, drop `dayKey` and `dayOk` once the Eastern date changes.
  - Have `DayPollPolicy` return a wake at the next Eastern midnight instead of null for an all-final today board.

### F6. Low: `CancellationException` is caught as something else in two workers
- **`data/.../work/EpgImportWorker.kt:47-53` (`tryForeground`):**
  - It catches `IllegalStateException`, and `CancellationException` is a subclass of it.
  - A worker cancelled inside `setForeground` logs "foreground service not allowed" and goes on into `importer.build`.
  - The cancel check then aborts the build, so the cost is a wrong log line and some wasted work.
- **`data/.../work/PlaylistRefreshWorker.kt:46-50`:**
  - `maintenance?.runIfDue()` is a suspend call wrapped in `catch (e: Exception)`.
  - Cancellation is logged as "maintenance failed" and the worker still returns a result. The trim is safe to repeat.
- **Confidence:** confirmed by reading the code.
- **Fix:** add `catch (e: CancellationException) { throw e }` first in both places.

### F7. Low: a restore whose USER EPG source already exists leaves an orphan credential entry
- **Where:** `data/src/main/kotlin/app/thebox/data/backup/BackupRestorer.kt:161-169` with `:431-449`.
- **Cause:** every secret in the backup is written under a fresh ref before the transaction. When the USER source already exists (same `url_hash`), `restoreUserSource` only updates its settings. The new ref is never referenced and never deleted: `written` refs are deleted only on failure, and `oldRefs` covers playlists only.
- **Effect:** each restore of such a file leaks one Tink entry. There is no functional harm.
- **Confidence:** confirmed by reading the code.
- **Fix:** collect refs that were written but not used and delete them after the commit, or point the existing source at the new ref and add the old one to `oldRefs`.

## Known and still open (logged earlier; flagged because the impact is larger than the log suggests)
- **Duplicate-key swaps are order-based** (M1 code review, Later 7).
  - `NameNormalizer.forKey` strips quality tokens, so the very common M3U pattern "CNN HD" / "CNN SD" / "CNN FHD" with one shared `tvg-id` produces a single base key. The variants are told apart only by `~n` in playlist order (`PlaylistDifferImpl.kt:163-187`).
  - If the provider reorders the variants, favourites, prefs, mappings and reminders silently move to the other quality (`k` is updated in place to the SD row).
  - This is the most user-visible identity weakness left. It is worth scheduling the URL-aware occurrence assignment amendment.

## Worth a look (not verified)
- `EpgStoreImpl.isLateFailure` (`:545`) counts compressed bytes that okio and kxml have read ahead. For small files (under about 80 KB, or gz), a parse error near the start can already show at least 90 % consumed, so it is treated as truncation and the partial data is accepted instead of failing over to the last good file.
- `RefreshRepository.kt:388` writes back a whole `epg_source` row read earlier, outside the EPG run lock. A concurrent build's `setImported` (`programme_count`, `cached_file`) can be overwritten. `programme_count` feeds the transient-loss guard at `EpgRepository.kt:199`. A column-targeted update would remove the risk.
- `RoomMergeSink.kt:238-246` checks group missing-ness by `group_key` without the kind. This matters only for M3U playlists that mix kinds under one group title.
- `storage/AtomicFiles.kt:16` renames without first fsyncing the temp file. After a power cut, `device-id` could be empty and a new id gets minted for shared folders.
- `XmltvTime.ZoneOffsetCache` resolves the repeated hour at the autumn DST change to the earlier offset (documented). For offset-less XMLTV with `assume_zone`, the second pass of 01:00-02:00 replaces the first (same PK), so about an hour of guide is lost once a year.
- `SourceInputNormalizerImpl.kt:33-35` and `XtreamUrls.normalizeBase` drop any base sub-path. A panel under `http://host/panel/` cannot be used. Check whether such panels are in scope.
- `EspnMapper.kt:103-104` builds the previous and next week keys from the current body's `season.year`. Check against a real response at the season boundary.

## Checked and found guarded (no action)
- **PATCH `ImportSession.begin()` deleting outside the transaction:** patch refs are only not-imported channels (`EpgMatchRepository.pendingPatchRefs`). Those rows have no programmes (`dropUntouched` and the repair pass zero them), so a failed or cancelled patch loses nothing.
- **Generation-number reuse after `clear()`:** `maxGeneration()+1` can restart at 1, but only once no reader holds the old file. `GuideWindowPagerImpl` and `ProgrammeFeedImpl` handle the null info and the `patchSeq` change.
- **Catch-up zones:** the `xc` segment uses the panel zone or offset, and correction is subtracted (`TimeBase.archiveStart`). `ArchiveFrames` and the builder use the same rounding, and the "not started" check uses the corrected start against the panel's now.
- **Parsers:**
  - XMLTV: offsets, fractional seconds, `GMT+hh` and the year range; the repair pass handles continuations and inferred stops.
  - M3U: BOM and UTF-16 transcoding, CR, LF and CRLF, quotes inside quoted values, unterminated quotes.
  - Xtream: numbers as strings, `[]` for `{}`, null versus absent.
- **Other:** differ occurrence and remap logic, backup Replace/Merge clearing, migrations (with a migration test, no destructive fallback), updater ordering, LAN pairing, PollMeter and ScoreBugMachine timing (helper).

## Coverage
- **Read closely:**
  - core: `catchup/*`, `catchup/archive/{ArchivePlannerImpl,TimelineRouterImpl,ArchiveVisibility}`, `model/TimeBase`;
  - core: `epg/store/{EpgStoreImpl,GenerationFiles,GenerationHandle,ImportSession,RepairPass,TextDedup,EpgSchema}` and the `EpgReaderImpl` window, at and cache code;
  - core: `xmltv/XmltvTime` and the first half of `XmltvParser`, `xtream/{LenientJson,XtreamDecoders,XtreamCatalogDecoderImpl,XtreamUrls}`, `m3u/{LineReader,CharsetDecoderChain,ExtinfLexer}`;
  - core: `identity/{StableKeyDeriverImpl,UrlKey,ContentHasher,GroupKeys}`, `refresh/PlaylistDifferImpl`, `guide/layout/TimeTables`, `diag/NetworkTest`, `text/NameNormalizer`, `backup/MergePlanner`;
  - data: `backup/{BackupRestorer,BackupExporter,BackupServiceImpl,BackupSupport}`, `db/{LibraryDatabase,Migrations}`, `repo/EpgRepository` (build and patch).
  - Through the helpers: core `sports/{ScoreBugMachine,PollMeter,DayPollPolicy,EspnMapper}`, `update/*`, `setup/lan/{PairingSession,LanPhoneLink,HttpParser}`, `parental/*` and the small playback models; data `repo/*`, `work/*`, `guide/*`, `RoomSettingsStore` and `ScoreRepositoryImpl`.
- **Skimmed:**
  - core: `playback/timeshift/RingIndexImpl` (first 200 lines), `ts/PcrTracker`, `M3uParserImpl` (BOM path), `ArchiveContracts`;
  - data: the remaining DAOs via grep;
  - helpers: storage `DeviceIdentity` and `AtomicFiles`.
- **Not reached:**
  - core: the rest of `playback/timeshift` (RingWriter/Reader, TsRing, TsIndexer, FileRingStorage), `match/*`, `storage/*`, `editor`;
  - core: most of `GuideStateMachineImpl`, `PlayerStateMachineImpl`, `SettingsMachineImpl` and `settings/tree`, `setup/pages`, `setup/lan/{PhoneApi,LanHttpServerImpl}`;
  - data: `storage/smb`, `storage/discovery`, `art`, most of `diag`, `rules`, `parental`, `SourceSetupImpl`, `BackupPagesImpl`, `BackupStorage`.
