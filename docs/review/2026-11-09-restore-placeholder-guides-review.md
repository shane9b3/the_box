# Review: restore placeholders for playlist-provided guides

Reviewed: `docs/design/m5-restore-placeholder-guides.md` (Option A), against the working tree as it stands on 2026-11-09, including the uncommitted fix pass. No code was changed or built.

## Verdict: approve with changes

The design holds. I checked every claim the note makes, and each one matches the code:
- the single gate;
- the fill-in branch;
- the recogniser;
- the record order;
- the hash-blind `resolveSource`;
- the delete cascade.

There are no blockers. Four **Should** items need to go into the implementation or the note before coding starts:
- S1: the fill-in must not turn a placeholder into a secret-less live row;
- S2: validate the backup's ordinal;
- S3: record why `refresh_seq = 0` is the right gate (it is, and the note undersells it);
- S4: add one end-to-end restore → refresh test.

Everything else is a Nit.

---

## 1. The single-gate claim: holds

I searched every use of `epg_source`, `EpgSourceDao` and `source_id` in `data/`, `app/`, `core/` and `ui/` main sources. Every reader that **acts** on a guide goes through `active()`:
- `EpgRepository.kt:159` (build), `:282` (patch), `:322` (resolvedRefs);
- `EpgRefResolver.kt:77`, `GuideMetaCache.kt:69`;
- `EpgMatchRepository.kt:110,128,159`;
- `EpgChannelSearchDao.kt:29`, `SourceSetupImpl.kt:321`, `RefreshScheduler.kt:122`.

These readers do **not** use `active()`. None of them causes harm.

| reader | sees a placeholder? | what it does | verdict |
|---|---|---|---|
| `BackupExporter.kt:50` `all()` | yes | exports it with its overrides; mapping keys end in `\|0` | intended; round-trips (§5) |
| `DiagnosticsImpl.kt:136` `all()` | yes | `playlists.secret(ref)` with no playlist id returns MISSING, adds nothing, sets no flag (`PlaylistRepository.kt:103-116`) | fine |
| `EpgRepository.kt:98` `sources()` → `observeAll()` | yes | **no callers** in any main source set | fine. The future EpgSources page must decide what to show (note §3 already says this) |
| `maxPriority()` (`EpgSourceDao.kt:49`) | yes | counts the placeholder's priority | harmless |
| `get(id)` in `EpgMatchRepository.kt:62` (`whitelistFor`) | only for ids being built | build ids come from `active()` | fine |
| `setSourceEnabled` / `setSourceShift` / `reorder` / `removeSource` (`EpgRepository.kt:118-136`) | by id | **no callers** | fine |
| `forPlaylist` in `PlaylistRepository.kt:92` and `RefreshRepository.kt:368` | yes | delete cleanup; the fill-in itself | intended |
| Mapping joins with no source join: `EpgRefRule.resolve` mapping branch (`EpgRefResolver.kt:36-40`), `ChannelDao.matchCounts` (`ChannelDao.kt:203-214`), `UNMATCHED_HEAD` / `matchedWithoutData` (`ChannelDao.kt:278-283`, `:190-196`), `GuideQueries.kt:229-232` | the mapping row, not the source | a channel mapped to a placeholder counts as "matched", is left out of Unmatched/Fix lists, and resolves to OVERRIDE with no data | Nit N1: only while channels exist and the row is still a placeholder |

The window in the last row is the gap between the merge commit (`RefreshRepository.kt:155-159`) and `syncSources` (`:168/:173`). That is normally under a second. It lasts longer only if `syncSources` throws, and then the next refresh repairs it. This matches a mapping to a disabled guide today, as the note says.

`GuideMetaCache.TABLES` includes `epg_source` (`GuideMetaCache.kt:76`), so the cached active list is dropped when the fill-in UPDATE lands. Room's triggers also see the restore's raw `conn.exec` writes.

## 2. The fill-in: holds, with one gap (S1)

I read `RefreshRepository.kt:362-399` line by line:
- `:368`: `existing` includes the placeholder, keyed by ordinal.
- `:370`: `hash` is the real hash, so `cur.urlHash (0) != hash` and the update always runs.
- `:385`: `playlists.secret(cur.credentialRef)` uses the ref-only overload. `TinkCredentialStore.get` returns MISSING at `:78`, before it touches the Keystore. No `playlistId` is passed, so `needs_reauth` is **not** set. The Err result is not cached.
- `:386`: `old` is null, so `putSecret` runs under the placeholder's ref, with the playlist's headers.
- `:388`: `cur.copy(urlHash, displaySource, etag = null, lastModified = null)` keeps `id`, `name`, `priority`, `enabled`, `shift_min`, `assume_zone` and `credential_ref`. Mappings hold, and `changed = true` queues `importEpgNow` (`:181-183`).

**The gap.** The `putSecret` result at `:386` is ignored. If the Keystore or Tink fails, it returns `Err(UNAVAILABLE)` without throwing (`TinkCredentialStore.kt:61-75`). Line `:388` still writes the real hash, and the row then:
- stops being a placeholder;
- passes `active()`;
- fails the next build with `cred.missing`, a FAILED `refresh_log` row (`EpgRepository.kt:177-180`).

The next refresh repairs it, but this is exactly the user-visible failure the gate exists to prevent. The note covers "process dies between put and update" but not "put returns Err". See S1.

**M3U header ordinals.**
- `HeaderAttrs.kt:12,23-40`: `epgUrls` is a `LinkedHashSet` of the raw URLs, in first-seen order. It takes `x-tvg-url`, `url-tvg` and `tvg-url`, split on commas and whitespace, merged across several `#EXTM3U` lines.
- The ordinals stay stable as long as the provider serves the same header.
- If the provider reorders or prepends a URL between the old device's last refresh and the new device's first refresh, overrides and mappings attach to a different guide. Mappings then point at xmltv ids that guide lacks, so the channel shows no data.
- This is the same limitation as S14 for real rows today. It is not new (N5).

**M3U_FILE.**
- The restored file playlist's content URI belongs to the old device, so `copier.copy` (`:241-246`) usually fails. The placeholder then stays inert until the user picks the file again.
- It is harmless: it is excluded from every reader, exported and restored again, and deleted with the playlist.

**`304 Not Modified` skips `syncSources`** (`:123-132`). This cannot happen while `refresh_seq = 0`: `playlist.etag` is set only by a successful merge (`RoomMergeSink.kt:204-208`), and restore never copies `etag` (`PLAYLIST_COLUMNS`, `MergePlanner.kt:74-79`; the test asserts it at `RestoreMergeTest.kt:186`). So "the first refresh fills it" holds.

## 3. The recogniser `origin <> 'USER' AND url_hash = 0`: no upgrade regression

- `url_hash` is `INTEGER NOT NULL` with **no default** in v1 through v5 (`data/schemas/.../1.json:1187` and later). The note checked only v1–v3; v4 and v5 are the same.
- No migration writes to `epg_source` (`Migrations.kt`).
- The only writer of non-USER rows is `syncSources` (`:376-388`), which always uses `Hash64.of(...)`. That ends with `(x xor 0xFF) * PRIME` (`Hash64.kt:23`), so the result is 0 only if `x = 0xFF`.
- USER rows (`EpgRepository.kt:110`, `BackupRestorer.kt:456-461`) are excluded by origin.
- Test fixtures: every non-USER fixture uses hash 1 or 42 (`LibraryDatabaseTest.kt:97`, `RestoreMergeTest.kt:131,162`, `DaoTest.kt:120`). No real row can disappear from `active()` on upgrade.
- The `0 → 1` guard is invisible: the hash of a non-USER row is read only in the backup key, and `resolveSource` ignores it there (`BackupRestorer.kt:429-433`; `parseSourceKey` has no other caller).

## 4. `refresh_seq = 0` as "never synced": keep it (answer to open question 1)

**What it means today.**
- `refresh_seq` is written only by `RoomMergeSink.kt:204-208`. The default is 0 (`PlaylistEntity.kt:62`).
- The differ throws on EMPTY and on the shrink guard (`PlaylistDifferImpl.kt:72-77`), so `refresh_seq > 0` means exactly "a merge committed".
- Every completed merge is followed by `syncSources` unless something throws in between.

**The stronger argument the note is missing: it is race-free against a concurrent refresh.**
- The merge commits `refresh_seq` while holding `ImportMutex` (`RefreshRepository.kt:155`), and `syncSources` runs **after** the mutex is released.
- The restore holds the same `ImportMutex` (`BackupRestorer.kt:172`).
- So a restore sees one of two states:
  - `seq = 0`: `syncSources` has not started, and it will later read the placeholder through `forPlaylist` and fill it in;
  - `seq ≥ 1`: the restore inserts nothing.

**Why the looser gate is worse.** "No rows of this origin" has a real race. Suppose `syncSources` has already taken its `existing` snapshot (`:368`) but has not inserted yet. If the restore inserts a placeholder for ordinal 0 in that gap, the `syncSources` insert at `:376` hits `index_epg_source_origin` (unique). The resulting `SQLiteConstraintException` falls into the catch-all (`:203-207`) and logs a FAILED refresh.

**Recommendation.** Keep `refresh_seq = 0` and write this reason into the note (S3).

The case the note worries about, where `syncSources` throws after a merge, behaves like today: the restore skips the record, and the next refresh recreates the rows. That trade is acceptable.

**Merge onto a matched local playlist that never refreshed.** Placeholders are created, which is correct. `afterRestore` (`BackupSupport.kt:46-52`) does not queue a refresh for such a playlist unless it was re-logged-in. The placeholder therefore waits for app start or the periodic refresh (N3).

**Future migrations.** `Migrations.kt:23-24` explicitly allows a migration to set `refresh_seq = 0` to force a full refresh. After such a migration, every refreshed playlist would look never-synced until its next refresh. The consequence is benign:
- Existing rows take case (a).
- Ordinals the device does not have become placeholders, and the next `syncSources` deletes them (`:393-397`). That is today's net result.

Add one line to that policy comment so a future author knows (N4).

## 5. `epg_mapping` keys `ORIGIN|uid|ordinal|hash`: hold

`resolveSource` uses `(playlist_id, origin, ordinal)` only, for non-USER keys (`BackupRestorer.kt:429-433`). All of these resolve:
- **Old-device backup (real hash) → new device placeholder (hash 0):** resolves by ordinal.
- **New-device backup taken before the fill-in (`|0`):**
  - onto a third device's placeholder: resolves;
  - onto the old device's real row: resolves;
  - back onto the same device after the fill-in: resolves.
- **After the fill-in:** the export carries the real hash, and the result is the same.

USER keys still match on the hash (`:428`), and placeholders are never USER rows. The record order is pinned: `epg_source` before `epg_mapping` (`RestoreMergeTest.kt:372-374`).

## 6. Unused-credential cleanup (fix pass, `BackupRestorer.kt:178-181`): no bad interaction

- The cleanup deletes only refs in `written`, which come from `refFor`, minus `usedRefs`.
- The placeholder's ref comes from `creds.newRef()` (`TinkCredentialStore.kt:59`, a UUID). It is never in `written`, so it is never deleted. Nothing is stored under it, so nothing leaks.
- On rollback (`:183-196`), the placeholder row disappears with the transaction and the ref was never persisted.
- `PlaylistRepository.delete` (`:92-94`) calls `credentials.delete` on it, which is a no-op.
- The one test that changes is `restoringOntoAnExistingUserGuideLeavesNoUnusedLogin` (`RestoreMergeTest.kt:212-214`), as the note says. `used` gains the placeholder ref, and the secret count stays at 3 (cred-a, cred-b, cred-epg).

## 7. Open question 2 (Xtream `xmltv()` failure deletes rows): not reachable today

- `XtreamUrls(...)` and `.xmltv()` cannot throw for any input. `normalizeBase` always finds `://` after it prepends a scheme (`XtreamUrls.kt:53-67`), and `encode` covers every character.
- The only throwing call is `secret.urls.first()` at `RefreshRepository.kt:165`. It is **outside** the `runCatching`, so it goes to the catch-all and `syncSources` is never reached.
- So the `onFailure` → `listOfNotNull(null)` → delete path at `:166-168` is dead code.

Turning it into safe code is one line: return without calling `syncSources` when `xmltv == null`. Do it in this change as hygiene (N2). It does not need its own design.

**The live version of this risk is on the M3U side, not Xtream.**
- When a download comes back with no `#EXTM3U` guide attributes, `header ?: M3uHeader()` (`:170`) gives an empty list, and `:393-397` deletes every `PLAYLIST_HEADER` row. The cascade takes every mapping with them.
- Causes include a provider glitch, a header the parser did not see, or a temporary edit.
- `xtvgUrlChangeKeepsSourceIdAndMappings` pins this as intended (`RefreshRepositoryTest.kt:225-228`).
- Placeholders do make it worse: one bad first download loses all the restored mappings at once.

That is a separate change: a grace period like `missing_since` for header guides. Record it as a follow-up in the m5 open items and do not fold it into this one (S3 asks for the note line).

## 8. Test plan

The plan is good. These items are missing.
- **End-to-end chain (S4).** Every `RefreshRepositoryTest` case seeds a placeholder by hand, so a seed that drifts from what the restorer writes would go unnoticed. Add one test that runs restore then refresh on one graph: run `BackupRestorer` on an empty library, refresh the playlist with the fake fetcher (M3U header with two URLs), then assert:
  - the same ids;
  - the mappings kept;
  - the shift and zone kept;
  - the secrets present;
  - `importEpgNow` queued.
- **Restore without a passphrase.** The XTREAM playlist gets `needs_reauth = 1` and still gets a placeholder plus mappings. `aSecondRestoreOfTheSameFileChangesNothing` already restores without logins, so extend it to assert the placeholder count stays at 1 after the second restore.
- **Merge onto a matched local playlist with `refresh_seq = 0`** (SAME_SOURCE match): placeholder created and mappings land.
- **`putSecret` returns Err during the fill-in** (S1): the row stays a placeholder, and `active()` still excludes it.
- **Invalid ordinals** (S2): a record with ordinal −1, or ordinal 1 for XTREAM, creates nothing.
- **Readers:**
  - `RefreshScheduler.onAppStart` with only placeholders schedules no EPG periodic work (extend `RefreshSchedulerTest.kt:117-142`);
  - `EpgChannelSearchDao` and `GuideMetaCache.activeSources()` exclude the placeholder. One `DaoTest` row covers both, since they call `active()`.
- **`DaoTest`:** add a PLAYLIST_HEADER placeholder at ordinal 1 next to a real ordinal 0, and assert that only the placeholder is filtered.

---

## Findings

### Blocker
None.

### Should

**S1. The fill-in must not drop the marker when the secret write fails.**
- Evidence: `RefreshRepository.kt:386` ignores the `Outcome` of `putSecret`, and `:387-388` then writes the real hash. `TinkCredentialStore.kt:61-75` returns `Err(UNAVAILABLE)` without throwing.
- Result: a secret-less live row, a `cred.missing` FAILED build (`EpgRepository.kt:177-180`), and the "no failure before refresh" promise (note §6, manual check step 4) broken.
- Resolution: in the existing-row branch, when `old != url`, call `putSecret`. If it returns Err, log it (code only) and `return@forEachIndexed` without updating the row. The row stays a placeholder and the next refresh retries.
- Optional: apply the same rule to the insert branch at `:374-375`, which has the same flaw today.
- Add the test from §8.

**S2. Validate the backup's ordinal before creating a placeholder.**
- Evidence: `BackupRecord.EpgSource.ordinal` is an unchecked `Int` (`BackupContracts.kt:46`). `syncSources` deletes stale rows only when `ordinal >= urls.size` (`RefreshRepository.kt:393`), so a negative ordinal from a corrupt or hand-edited file creates a placeholder that is **never** cleaned up. It is also exported again forever.
- Resolution: in case (b), require:
  - `ordinal == 0` for XTREAM;
  - `ordinal in 0 until MAX_HEADER_GUIDES` (for example 32) for PLAYLIST_HEADER.

  Otherwise take case (c), skip.

**S3. Rewrite open question 1 as a decision, and record two follow-ups.**
- In the note's §7.1, replace the question with the decision "`refresh_seq = 0`, because it is race-free". Give the reason from §4: the merge commits under `ImportMutex` before `syncSources`, the restore holds `ImportMutex`, and the looser gate races the unique index at `RefreshRepository.kt:368` → `:376`.
- In the note's §7.2, record that the Xtream path is unreachable (§7) and that the real risk is an empty M3U header deleting every header guide. Add that as a follow-up to the m5 open-items list, next to the `EpgSources` page item.

**S4. Add one end-to-end restore → refresh test** (§8, first item). It is the only test that proves the restorer and `syncSources` agree on the placeholder's shape.

### Nit

**N1.** A mapping to a placeholder counts as matched in `ChannelDao.matchCounts` (`:203-214`) and keeps the channel out of the Unmatched lists (`:278-283`). That holds only in the gap between the merge commit and `syncSources`. Mention it in note §3 with the same "same as a disabled guide" wording.

**N2.** Make the dead `onFailure` path at `RefreshRepository.kt:166-168` safe: skip `syncSources` when `xmltv == null`. This is one line.

**N3.** In Merge, a matched local playlist with `refresh_seq = 0` and no relogin is not refreshed by `afterRestore` (`BackupSupport.kt:51`). If you want placeholders filled promptly, add `affected` playlists with `refresh_seq = 0` to the follow-up `refreshNow`. Otherwise note that the fill-in waits for the next scheduled refresh.

**N4.** Add one line to the `Migrations.kt:23-24` policy: "`refresh_seq = 0` also tells a restore that the playlist has no guide rows yet; resetting it makes a restore in that window create placeholders the next refresh prunes (harmless)". Also fix "v1–v3 schemas" in note §1 to "v1–v5".

**N5.** Note under §2 that header ordinals come from first-seen order (`HeaderAttrs.kt:12,38,47`). If the provider reorders its header between devices, overrides and mappings attach to a different guide, the same as S14 today.

**N6.** In case (b), take "the playlist type" from the **local** row (`SELECT type, refresh_seq FROM playlist WHERE id = ?`), not from `b.type`. A SAME_UID match does not compare types (`MergePlanner.kt:22-23`). Cache the result per `pid` for the pass.

**N7.** `syncSources` writes the full entity (`sourceDao.update(cur.copy(...))`, `:388`). A restore that changes the overrides between the `:368` snapshot and `:388` loses those changes. It is a rare race and it already exists for real rows. A targeted `UPDATE epg_source SET url_hash = ?, display_source = ?, etag = NULL, last_modified = NULL WHERE id = ?` removes it. Optional.

**N8.** Keep `PLACEHOLDER_SQL` as a `const val` that both `active()` and tests interpolate. Room `@Query` accepts compile-time constant concatenation, so the predicate exists in one place and the KDoc on `all()` and `observeAll()` can name it.
