# Restore: placeholder rows for playlist-provided guides

Status: reviewed (`docs/review/2026-11-09-restore-placeholder-guides-review.md`), signed off and implemented 2026-11-09; see "Lead sign-off" at the end. Fixes finding F1 of the 2026-11-09 correctness review (core/data). The owner chose Option A: the restore creates the playlist's guide rows itself, and the first refresh fills them in. No schema migration.

## The bug in one paragraph
Today a restore keeps the user's settings for a playlist-provided guide only if that guide's `epg_source` row already exists (`BackupRestorer.kt:335-347`). It also keeps a manual match only if `resolveSource` finds that row (`:348-351`, `:425-435`). Only `RefreshRepository.syncSources` creates these rows (`RefreshRepository.kt:362-399`, called at `:168` and `:173`). On a new TV the restored playlists have never been refreshed, so the restore silently drops the enabled, priority, shift and zone settings and every `epg_mapping` row that points at those guides. `RestoreMergeTest.kt:175-177` works around this by restoring twice.

## 1. What the restore creates
**Where.** In the `BackupRecord.EpgSource` branch (`BackupRestorer.kt:335-347`), for records with `origin != USER` and a known playlist (`pid != null`):
- **(a)** A row for `(pid, origin, ordinal)` already exists: run the `UPDATE` exactly as today.
- **(b)** No row exists, the playlist has `refresh_seq = 0` (it has never completed a merge, so `syncSources` has never run for it), and the origin fits the playlist type: insert a placeholder. XTREAM fits type XTREAM; PLAYLIST_HEADER fits M3U_URL or M3U_FILE.
- **(c)** Anything else: skip, as today. A playlist that has refreshed on this device keeps today's behaviour: the device's own list of guides wins.

**Order is safe.** The exporter writes every `epg_source` record before any `epg_mapping` record (`BackupExporter.kt:89-96` vs `:129-134`; the order is pinned by `RestoreMergeTest.kt:373`). The restore reads the file in that order, so a placeholder exists before the mapping that needs it. `resolveSource` needs no change: for non-USER keys it matches on `(playlist_id, origin, ordinal)` and ignores the hash (`:430-433`).

**The placeholder's columns:**

| column | value | why |
|---|---|---|
| name | `rec.name` | `syncSources` never renames an existing row (`:388`), so the old device's name stays |
| origin, playlist_id, ordinal | from the record / `pid` | the unique key `index_epg_source_origin` (`EpgSourceEntity.kt:20`) |
| credential_ref | `creds.newRef()`, **no secret written** | the column is NOT NULL, and `syncSources` writes the secret under this ref later (`:386`). Never `""`: a shared ref would collide |
| url_hash | `0` | the marker (see below) |
| display_source | `""` | there is no address yet |
| priority, enabled, shift_min, assume_zone | from the record | the user's overrides |
| created_at | `now` | |
| other columns | defaults (NULL / 0) | |

The placeholder's ref is not added to `usedRefs` or `written` (`:161-181`). Nothing was stored under it, so the commit and rollback cleanup have nothing to do.

**How a placeholder is recognised:** `origin <> 'USER' AND url_hash = 0`. Put it in one place: `EpgSourceEntity.PLACEHOLDER_SQL` plus an `isPlaceholder` property.

**Why a real row cannot match:**
- USER rows are excluded by origin.
- Real playlist rows get `Hash64.of(...)` (`RefreshRepository.kt:370`). FNV-1a ends with `(x xor 0xFF) * PRIME`, and PRIME is odd, so the result is 0 only when `x == 0xFF`, a 2^-64 chance.
- To close even that case, `syncSources` stores `if (hash == 0L) 1L else hash`. For non-USER rows the hash is used only inside the backup's source key, and the restore ignores it there (`:430`), so the change is invisible.
- No migration or test fixture creates a non-USER row with hash 0. I checked `data/src/test`, `data/src/androidTest` and the v1–v5 schemas.

## 2. The first refresh (`syncSources`, `RefreshRepository.kt:362-399`)
**Where origin and ordinal come from.**
- Xtream: origin XTREAM, ordinal 0 only (`listOfNotNull(xmltv)`, `:163-168`).
- M3U_URL and M3U_FILE, including Xtream-shaped M3U: origin PLAYLIST_HEADER, with the ordinal equal to the index in the header's `epgUrls` (`:170-173`).

**The "existing row" branch works as claimed. I checked it line by line:**
- `playlists.secret(cur.credentialRef)` returns `Err(MISSING)` (`TinkCredentialStore.kt:78`). Because no `playlistId` is passed, it does not set `needs_reauth` (`PlaylistRepository.kt:103-116`).
- So `old` is null, `old != url`, and the secret is written under the placeholder's ref (`:386`).
- The update then sets `url_hash`, `display_source`, and clears etag/last_modified (`:387-389`). `changed = true` queues `importEpgNow` (`:181-183`).
- The id, name, priority, enabled, shift and zone are kept, so every mapping that points at the row still holds.
- If the process dies between `putSecret` and `update`, the row stays a placeholder and the next refresh repairs it, because the hash still differs.

**Changes needed:** the hash 0 → 1 guard from §1, and (review S1) a failed `putSecret` skips the row's update, in the existing-row branch and in the insert branch alike, so a placeholder never becomes a live row with no secret; the next refresh retries.

**Header ordinals** come from the header's first-seen order (`HeaderAttrs.kt`). If the provider reorders its header between the old device's last refresh and the new device's first, overrides and mappings attach to a different guide: the same limitation as S14 for real rows today (review N5).

| case | result |
|---|---|
| provider lists the same ordinal | filled in place, mappings and overrides kept, guide build queued |
| lists fewer | the extra placeholder is deleted (`:393-397`); its mappings cascade (`EpgMappingEntity` FK on `source_id`). Same as a real row today: that guide no longer exists |
| lists more | new ordinals are inserted as today (`:373-383`) |
| Xtream `xmltv()` throws | the list is empty, so the placeholder is deleted with its mappings (same as a real row today; see the risk list) |
| refresh fails (secret, download, merge) | `syncSources` is not reached; the placeholder stays and stays inert (§3); the next successful refresh fills it |
| refresh never happens (restored without the passphrase: `needs_reauth = 1`, the refresh fails at step 1 `:113-116`) | the placeholder stays inert until the user signs in; the next refresh fills it. Deleting the playlist removes it (§4) |

## 3. Readers of `epg_source`
**The one gate: `EpgSourceDao.active()` (`EpgSourceDao.kt:40-47`) adds `AND NOT <PLACEHOLDER_SQL>`.** Every reader that acts on a guide goes through it:
- **EPG build** (`EpgRepository.kt:159`): the placeholder is never downloaded. There is no `cred.missing`, no `setStatus` failure and no FAILED log row. If only placeholders exist, `sources.isEmpty()` returns `skipped` (`:160`), so no error is logged and the worker does not retry.
  - Without the gate, a library whose only guide is a placeholder ends at `:209` with `lastError = cred.missing`: a FAILED `refresh_log` row each time the build runs.
- **Patch** (`:282`), `resolvedRefs` (`:322`), `EpgRefResolver.kt:77`, `GuideMetaCache.kt:69`.
- **Matching** (`EpgMatchRepository.kt:110,128,159`): no `epg_match` rows for a placeholder.
- **Channel search for Fix guide** (`EpgChannelSearchDao`).
- **`SourceSetupImpl.kt:321`** (`hasGuide`).
- **`RefreshScheduler.onAppStart`** (`RefreshScheduler.kt:122`): no periodic EPG work for placeholders alone.

**A mapping to a placeholder** resolves to OVERRIDE, with the source's shift counted as 0 (`EpgRefResolver.kt:36-40`). That is the same as a mapping to a disabled guide today. Likewise such a channel counts as matched in `ChannelDao.matchCounts` and stays out of the Unmatched lists, the same as a mapping to a disabled guide (review N1); this lasts only between the merge commit and `syncSources`. It does not matter in practice: a restored playlist has no channels until its first refresh, and the first refresh fills the row.

**Settings: the code contradicts the assumption here.** No Settings page lists guides today. `SettingsRoute.EpgSources` has no page builder (`docs/design/m5-setup-settings.md:331`; no `handles()` in any `SettingsPageSource` matches it). So no user-visible string is needed in this change.
- Recommendation for whoever builds that page: **show** placeholders, not hide them. The user's shift and zone live on that row, and a guide that silently vanishes reads as data loss.
- Show the address line as **"Waiting for the playlist's first refresh"**. It is one new string; nothing else is needed.
- Add this to the page's spec (§3.3.2 row `guide.sources`) when that page gets an owner.

**Diagnostics export** (`DiagnosticsImpl.kt:136-143`): the secret lookup fails, so the row adds nothing to the redaction set. No change.

**Backup export before the first refresh** (`BackupExporter.kt:89-96`, `:129-134`):
- The placeholder is written as a normal `EpgSource` record with its name and overrides, and `secretRef` null (non-USER).
- Mappings are written with key `ORIGIN|uid|ordinal|0`.
- A later restore matches it by `(playlist, origin, ordinal)`, so the export round-trips with nothing lost. The weekly auto-backup is covered by the same path.

## 4. Modes and lifecycles
- **Merge, onto a fresh TV:** case (b) creates the placeholders, and the mappings land in the same transaction.
- **Replace:** `clearUserRows` (`:401-413`) deletes the playlist's `epg_mapping` rows but never `epg_source` rows. The `EpgSource` pass then runs case (a) or (b) as in Merge. No difference.
- **Restoring twice:**
  - The second pass finds the placeholder and takes case (a), an UPDATE. No duplicate is possible: the unique index on `(playlist_id, origin, ordinal)` forbids one, and the code checks before inserting.
  - The mapping upsert is keyed by `(playlist_id, stable_key)`, so it is idempotent. `aSecondRestoreOfTheSameFileChangesNothing` must still pass.
- **The playlist and its guides already exist and it has refreshed:** case (a), or case (c) skip. This is exactly today's behaviour.
  - A matched local playlist that never refreshed (`refresh_seq = 0`) is treated like a new one. That is correct, because it has no guide rows of its own yet. `afterRestore` does not refresh it unless it got a new login, so its placeholders wait for the next app-start or periodic refresh (review N3; accepted).
- **Deleting the playlist before it ever refreshes:**
  - `PlaylistRepository.delete` (`PlaylistRepository.kt:90-98`) calls `credentials.delete` on the placeholder's ref. No row exists, so this is a no-op (`TinkCredentialStore.kt:95`).
  - Deleting the playlist cascades to `epg_source` (`EpgSourceEntity.kt:15`). That cascades to `epg_mapping` through `source_id`, and the mappings also go through `playlist_id` (`EpgMappingEntity` FKs).
  - Nothing is left behind.

## 5. Mappings that point at a placeholder
- The mapping row is keyed by `(playlist_id, stable_key)`. Its `source_id` holds the placeholder's library.db id, the same kind of value a mapping to a real playlist guide holds today.
- Filling the row in is an UPDATE of the same id (§2), so `source_id` stays valid.
- An EPG rebuild changes only epg.db. `EpgChannelRef` is `epg_source.id` plus the XMLTV id, never an epg.db row id (`EpgContracts.kt:10`), so mappings survive generation swaps.
- The `source_id` changes only if the provider stops listing that ordinal. Then the row and its mappings are deleted by the cascade, as for any guide that goes away.

## 6. Test plan
**`RestoreMergeTest`:**
- **Replace the workaround** in `exportThenRestoreMergeIntoAnEmptyLibraryGivesTheSameUserTables`: drop `addXtreamSource` and the second restore. Assert `userTables` equal after **one** restore. Also assert the XTREAM row is a placeholder carrying the source's priority, enabled, shift and zone.
- **Adjust `restoringOntoAnExistingUserGuideLeavesNoUnusedLogin` (`:199-215`).** The seed's XTREAM guide now produces a placeholder whose ref has no secret, so the `used == secrets.keys` assertion breaks.
  - New assertion: non-placeholder refs equal the stored secrets, and every placeholder ref has no secret.
  - The expected count of 3 secrets stays the same.
- **New test, Replace mode:** a single Replace restore onto an empty library keeps the mappings and the overrides.
- **New test, refreshed playlist:** a playlist that exists and has refreshed (`refresh_seq > 0`) with no row for the backup's ordinal gets no placeholder, and the mapping is skipped as today.
- **New test, type mismatch:** an XTREAM-origin record restored onto an M3U playlist creates no placeholder.
- **New test, export before refresh:** export before any refresh, then restore into a third library. The overrides and mappings are identical.
- **New test, delete before refresh:** deleting the playlist before a refresh leaves 0 `epg_source` and 0 `epg_mapping` rows for it, and no credential row.

**`RefreshRepositoryTest`** (it already drives `syncSources` through the header URL harness, `:208-229`). Seed a placeholder plus a mapping, then refresh in each case:
- **Same ordinal:**
  - the id is unchanged;
  - the hash is non-zero;
  - the secret exists under the placeholder's ref;
  - priority, shift and zone are kept;
  - the mapping is kept;
  - `importEpgNow` is called.
- **Fewer ordinals:** the row and its mapping are gone.
- **More ordinals:** a new row is inserted.
- **Download fails:** the placeholder is unchanged.
- **Xtream origin:** the same checks for an Xtream playlist.

**`EpgRepositoryTest`:**
- With only a placeholder present, `build` returns `skipped`, writes no `refresh_log` FAILED row and calls no `setStatus`.
- With a placeholder plus a working USER guide, the build succeeds and the placeholder's `last_status` stays NULL.

**`DaoTest`:** `active()` excludes the placeholder, and still includes a USER row whose hash is 0.

**Manual check** (emulator plus the fake panel; not part of this note's work):
1. Export from a box with a manual match and a +60 shift on the Xtream guide.
2. Restore onto a wiped app.
3. Confirm the guide shows the matched channel's programmes with the shift after the first refresh and build.
4. Confirm Sync status shows no guide failure before the refresh.

## 7. Risks and decisions
1. **Decided: the gate is `refresh_seq = 0`** (review §4, S3). `refresh_seq` is written only by `RoomMergeSink`, so `refresh_seq > 0` means exactly "a merge committed". The gate is race-free against a concurrent refresh: the merge commits `refresh_seq` while holding `ImportMutex`, `syncSources` runs only after the mutex is released, and the restore holds the same mutex. So a restore sees either `seq = 0` (`syncSources` has not started; it will read the placeholder through `forPlaylist` and fill it in) or `seq ≥ 1` (the restore inserts nothing). The looser gate "no rows of this origin" races: if `syncSources` has taken its `existing` snapshot but not yet inserted, a placeholder inserted in that gap makes its insert hit the unique index `index_epg_source_origin`, and the refresh fails. If `syncSources` throws after a merge, a later restore skips the record, which is today's behaviour; the next refresh recreates the rows. A future migration that resets `refresh_seq = 0` makes a restore in that window create placeholders the next refresh prunes (harmless; one line in the `Migrations.kt` policy).
2. **The Xtream `xmltv()` failure path cannot happen today** (review §7). `XtreamUrls(...).xmltv()` cannot throw for any input, and the one throwing call (`secret.urls.first()`) sits outside the `runCatching`. As hygiene, `syncSources` is now skipped when the URL is null (N2), so that path can never delete the guide. **The live version of this risk is on the M3U side:** a download whose header lists no guide URLs deletes every `PLAYLIST_HEADER` guide and, by cascade, its mappings. Placeholders make one bad first download lose all restored mappings at once. Recorded as a follow-up in the m5 open items (`docs/design/m5-setup-settings.md`); not fixed here.
3. **Priority collisions:** the placeholder takes the backup's priority, which may equal a local USER guide's priority. The existing override UPDATE has always done this. The order then falls back to `id` (`ORDER BY priority, id`). This is not new.
4. **The `EpgSources` page** must show placeholders when it is built (§3). Added to the m5 open-items list.
5. **The rule depends on `active()` staying the only way to act on guides.** `all()` and `observeAll()` carry a KDoc saying they include placeholders.
6. **Ordinals from the file are validated** (review S2): a placeholder is created only for ordinal 0 (XTREAM) or `0 until EpgSourceEntity.MAX_HEADER_GUIDES` (32, PLAYLIST_HEADER). A negative ordinal would never be pruned by `syncSources` and would be exported again forever.

**Files an implementer would touch:**
- `data/src/main/kotlin/app/thebox/data/backup/BackupRestorer.kt`: the `EpgSource` branch, cases (a), (b) and (c).
- `data/src/main/kotlin/app/thebox/data/db/entity/EpgSourceEntity.kt`: `PLACEHOLDER_SQL` and `isPlaceholder`.
- `data/src/main/kotlin/app/thebox/data/db/dao/EpgSourceDao.kt`: the `active()` filter and the KDoc.
- `data/src/main/kotlin/app/thebox/data/repo/RefreshRepository.kt`: the non-zero hash guard.
- Tests: `RestoreMergeTest`, `RefreshRepositoryTest`, `EpgRepositoryTest`, `DaoTest`.
- Docs: `docs/design/m5-setup-settings.md` §3.10.2 (one line: the restore creates placeholder guide rows) and the open-items note for `EpgSources`.

## Lead sign-off (2026-11-09)
Review verdict: approve with changes; no blockers. Dispositions:

| item | disposition |
|---|---|
| S1 secret write fails during the fill-in | done: `syncSources` skips the row update (existing-row branch) and the insert (insert branch) when `putSecret` returns Err; logged by code only. Test `aGuideLoginThatCannotBeStoredKeepsThePlaceholderAndAddsNoRow` |
| S2 validate the backup's ordinal | done: `EpgSourceEntity.MAX_HEADER_GUIDES = 32`; XTREAM needs 0, PLAYLIST_HEADER needs 0..31, else skipped. Test `onlyOrdinalsARefreshCanProduceBecomePlaceholders` |
| S3 `refresh_seq = 0` gate, open question 2, M3U follow-up | done: §7.1 is now a decision with the mutex reasoning; §7.2 records that the Xtream path is unreachable; the empty-M3U-header risk is in the m5 open items |
| S4 end-to-end restore → refresh test | done: `RestoreThenRefreshTest`; the restore-twice workaround in `RestoreMergeTest` is replaced by a single-restore assertion |
| N1 mapping to a placeholder counts as matched | noted in §3 |
| N2 dead `xmltv()` failure path | done: `syncSources` is skipped when the Xtream guide URL is null |
| N3 matched never-refreshed playlist not refreshed by `afterRestore` | accepted, noted in §4 (not a one-liner: it needs a `refresh_seq` read) |
| N4 `Migrations.kt` policy line; "v1–v5" | done |
| N5 header ordinals from first-seen order | noted in §2 |
| N6 type check from the local row | done: `BackupRestorer` reads `type, refresh_seq` of the local playlist once per pid |
| N7 targeted UPDATE in `syncSources` | skipped (lead decision) |
| N8 one `PLACEHOLDER_SQL` constant | done: `EpgSourceEntity.PLACEHOLDER_SQL` (for the alias `s`), used by `active()` and the tests; `PLACEHOLDER_HASH`; `isPlaceholder`; KDoc on `all()`/`observeAll()` |
| Missing tests (review §8) | added: restore without a passphrase, Merge onto a matched never-refreshed playlist, invalid ordinals, app start with only placeholders, header placeholder at ordinal 1, EPG build with only a placeholder, export before the first refresh, Replace mode, refreshed playlist, type mismatch (local row), delete before refresh, fill-in cases (same/fewer/more ordinals, failed download, Xtream) |
