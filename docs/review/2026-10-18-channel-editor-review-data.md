# M6 channel editor: code review, data side (2026-10-18)

Scope: the uncommitted data-side work for `docs/design/m6-channel-editor.md` §3.3–§3.7, §4 and §6.2: `data/.../editor/*`,
`EditorDao`, `EditorModule`, schema 5, the v4 → v5 migration, every effective-group reader, the parental bulk locks,
`RoomMergeSink`, backup, the guide queries, and the new tests. I read the code and reasoned about it. No code was changed,
and no Gradle or emulator run was made. Line numbers are against the working tree.

Verdict: **no blocker.** The migration, the effective-group joins, the group `UNION ALL`, the tune guard and the write
transactions are correct. Two problems should be fixed before the owner sees the feature:
- A backup restored onto a fresh device drops every move.
- The returning-group rule resets the group order whenever a provider drops a category for one refresh.

After those come one way to lower protection without the PIN, and a plan probe that tests the wrong plan.

---

## 1. Migration v4 → v5: correct

- `Migrations.kt:74-83` adds exactly one column, `ALTER TABLE channel_prefs ADD COLUMN custom_group_key TEXT`, and one
  index, `index_channel_prefs_group (playlist_id, custom_group_key)`, with no table rebuild. `ALL` includes it.
- `ChannelPrefsEntity.kt:12-17,52` declares the same name, columns and `TEXT` affinity. I diffed `5.json` against
  `4.json`: the only changes are the column in `createSql`/`fields` and the index entry. They match the migration.
- `LibraryDatabaseMigrationTest.migrate4To5AddsTheMoveColumnAndItsIndex` checks all of this:
  - it seeds two v4 `channel_prefs` rows;
  - `runMigrationsAndValidate(5)` validates against `5.json`;
  - every other table keeps its CREATE SQL, the new column is NULL, the old values survive and the index SQL is right;
  - the production builder then opens the file and writes the column.
- `editor/Migration4To5Test` also covers the editor over a migrated file.
- `MigrationOnDeviceTest.kt:19` now expects 5.
- **NIT:** the test method is still named `migrate3To4KeepsDataAndBackfillsUid`, and the v3 fixture has no
  `channel_prefs` rows, so the bundled driver never migrates real prefs rows. Rename it, and add one prefs row to
  `LibraryV3Schema` (it then goes 3 → 4 → 5 with data).
- **NIT:** `data/schemas/.../5.json` is untracked. It must be in the commit, or Room's validation in CI has nothing to
  compare against.

## 2. Effective-group readers and the tune guard

Every row of the §3.6 table is done:
- `ChannelDao` `browseGroup` (`g.id = :groupId`), `search`, `matchedWithoutData`, `UNMATCHED_HEAD` and `SELECT_ROW`;
- `LINEUP_JOINS`, `RECENT_FROM` (`PROVIDER_GROUP_JOIN + GROUP_TAIL`, with no second `cp`) and `FAV_FROM`;
- `LockedChannelIndex.SQL`, `FavouriteDao.channels` and `HiddenChannelsDao.entries`;
- the `GroupDao` / `GroupAdminDao` counts (`GroupDao.EFFECTIVE_COUNTS`, one grouped pass joined back by `gid`);
- `ChannelsPagesImpl.RULE_HIDDEN_COUNT` and `RuleHiddenPass:73-76`.

The provider-on-purpose readers are unchanged: `DisplayNames:128`, `PlaylistSettingsRepository:286`,
`RoomExistingChannels:21` and the `RoomMergeSink` purge. A fresh `grep group_id data/src/main` finds nothing else.

**Tune guard (`TuneGuardImpl.kt:119-130`).**
- `g` is the effective group in both the lock clauses and the `WHERE` prefilter (`gp.locked = 1 OR g.is_adult = 1`).
  A channel moved into a locked or adult group is in the index and gets NEEDS_PIN. One moved out drops out of the
  index and gets ALLOWED.
- `groupOf` returns the effective key, so "Open anyway" names the effective group.
- `MoveToGroupTest.theTuneGuardSeesTheEffectiveGroup` asserts all three. `GroupPurgeClearsMovesTest:61-78` asserts
  NEEDS_PIN while the target is in its grace period (ruling a).
- `LockedChannelIndex.TABLES` already contains `channel_prefs`, so a move rebuilds the index.
- **NIT:** for a target in its grace period, "Open anyway" opens `GuideSource.Group(<missing key>)`, which the panel
  does not list. The lineup still resolves through half 2. Check on the device that the guide names the group sensibly
  there; otherwise accept it.

**The guard test (`ProviderGroupJoinGuardTest`) does fail on the usual mistake.** A new
`LEFT JOIN channel_group g ON g.id = c.group_id` matches `=\s*c\.group_id` in test 1 and `JOIN channel_group g ON` in
test 2. It also requires both allowlisted lines to exist (`allowedSeen == 2`). Shapes it misses (**NIT**):
- another alias for `channel` (`ch.group_id = g.id`, `channel.group_id`);
- `USING (group_id)`;
- a join split across Kotlin lines (`"… ON g.id = " +` then `"c.group_id"`);
- `channel_group AS g`;
- anything new in `RoomMergeSink.kt`, which is allowlisted as a whole file. Allowlist its three lines instead.

The cheap fix is to make the pattern alias-agnostic, `\b\w+\.group_id\b`, and allowlist by line token.

## 3. The group lineup SQL (`GuideQueries.groupMembersSql`, `:425-439`)

Correctness, case by case:
- **Moved in.** Half 2 (`cpm.custom_group_key = ?3 AND gt.id IS NOT NULL`) lists the channel exactly once. Half 1 of
  its provider group excludes it (`cp.custom_group_key IS NOT NULL AND gt.id IS NOT NULL`).
- **Moved to its own provider key** (possible through a restore): half 1 excludes it and half 2 includes it, so it
  still appears exactly once. Good.
- **Moved out:** it is absent from half 1 of the provider group.
- **Target gone (no row):** `gt` is NULL. Half 1 keeps the channel at home, and half 2 drops it.
- **Target in its grace period:** `gt` matches any row, missing or not. The channel stays in the target, and `g`/`gp`
  are the target's, so it keeps the target's lock and hidden state (ruling a).
- **ORDER BY.** The outer query, `SELECT id FROM (…UNION ALL…) ORDER BY o1, o2, o3`, is a simple select over a
  subquery, so its ORDER BY terms only need to name the subquery's columns. They do (`GROUP_ORDER_COLUMNS`). The editor
  form (`EditorQueries.listSql`, Group) and `members()` use the same shape.
  - **NIT:** no final tie-breaker. If two members share `custom_sort_index` and `c.sort_index` (only after a
    hand-edited or restored order), their order is unspecified. Append `c.id AS o4`.

**The CROSS JOIN plan claim holds.**
- In SQLite, the left operand of a `CROSS JOIN` is always in an outer loop relative to the right one. So half 1 runs
  `g0` before `c`:
  - `g0` uses the unique `index_channel_group_identity`, with all three columns bound;
  - `c` uses `index_channel_group (group_id=?)`. The browse index cannot serve it, because `c.playlist_id` is not
    bound in half 1.
- Half 2 runs `cpm` before `c`. `cpm` has two equalities on `index_channel_prefs_group`, which beats the primary-key
  autoindex (one column). `c` is then a lookup on `index_channel_identity`.
- `JOIN playlist p` can still be ordered first, as a scan of a few rows. That is harmless, and the test does not
  forbid it.
- The LEFT JOINs are fixed after their left side.

**SHOULD-FIX: the probe runs `ANALYZE` (`EditorQueries.kt:366`), and production never does.**
- No `ANALYZE` or `PRAGMA optimize` exists anywhere in `data/src/main` or `app/src/main`.
- The comment at `GuideQueries.kt:419-423` says the no-statistics plan was the bad one, and that is the plan users get.
- **Fix:** take the plans without `ANALYZE`. Optionally take them a second time with it and assert both.

**NIT: `GroupLineupPlanProbe` sits in `data/src/main`.** It ships in the release APK, with a 20,000-row seed and a
`BundledSQLiteDriver` scratch database. Move it to `data/src/debug/kotlin`. `:app`'s androidTest builds against the debug
variant, so `GroupLineupPlanTest` still sees it.

**NIT: the group lineup runs the union twice** (ids, then `GROUP_ARCHIVE_SQL`). Read `ARCHIVE_ROW` as a column in both
halves and take the maximum in Kotlin, as the new All path already does (`GuideQueries.kt:58-74`).

## 4. Writes

**Transactions and keys: correct.**
- Every `EditorWrite` is one `db.writeTransaction` (`ChannelEditorRepositoryImpl.kt:248-403`, `hideChannels`,
  `moveChannels`, `favorite`).
- The bulk locks are one transaction inside `ParentalSessionImpl.lockChannels` / `setGroupsLocked`
  (`ParentalSessionImpl.kt:252-279`). `EditorBulkLockTest` asserts one invalidation each.
- Every write is keyed by `(playlist_id, stable_key)` or `(playlist_id, 0, group_key)`. Row ids are used only for the
  id arrays and the page reads.
- An AllOf target is resolved inside the transaction through `EditorQueries.resolveKeys`, which re-runs the same
  `listSql` under the same filter and access. Locks resolve inside the parental transaction (review 2 NIT 3).

**Order rules: correct.**
- (a) `moveChannels:438-450`:
  - into a custom-ordered target, the movers get `max + 1 …`;
  - otherwise the target is first materialised densely over `members()`, which includes hidden, rule-hidden, moved-in
    and missing members, and the movers get `m …`;
  - in both cases the movers go in list order.
- (b) A move back writes NULL group and NULL order (`:451-453`).
- (c) `RESET_CHANNEL` clears `custom_sort_index` only when `custom_group_key` was set. SQLite evaluates SET with the
  old row values, so the `CASE` sees the old key.
- The dense rewrite for Reorder keeps the slots of locked-out and missing members (`:270-283`). Reorder is disabled in
  core under a filter or with `lockedOut > 0`, so the coordinates agree.

**Refusals under `refusesLowering()`.**
- Unlock: refused at `:256` / `:263`, and again inside `ParentalSessionImpl`.
- Reset: refused when any target has an explicit lock or is moved out of a locked group (`:388-392`).
- Group Reset: refused when any target has `gp.locked = 1` (`:399`).
- Move out of a locked group: refused (`:435`).
- `EditorBulkLockTest.loweringIsRefusedWhileTheSessionIsLocked` covers all five, and checks that nothing was written.

**SHOULD-FIX: one move still lowers protection without the PIN.**
- An adult-detected channel (`c.is_adult = 1`) in a group with no decision (`gp.locked IS NULL`) is locked by
  `OWN_LOCK` / `PARENTAL_ACCESS`.
- Moved into a group whose lock was explicitly turned off (`gp.locked = 0`), it becomes
  `CASE WHEN gp.locked IS NULL … ELSE 0`, so it is unlocked.
- `:435` only checks the source group's lock (`groupLock`), so the move goes through under a locked session.
- **Fix:** in `targetInfo`, compute `lockedNow` = the full `OWN_LOCK OR GROUP_LOCK`. Read the target group's
  `gp.locked` / `g.is_adult` once, compute `lockedAfter` per mover, and refuse when any mover has
  `lockedNow && !lockedAfter`.
  - This also stops refusing a move between two locked groups, which today is refused for no reason (**NIT**).
  - Add the case to `EditorBulkLockTest`.

**Other write checks.**
- **SQLITE_BUSY:** retried once, then `BUSY` (`:224-241`). `busy()` matches both drivers' messages.
  - **NIT:** no test covers the retry, and the retry runs immediately. Add a 50–100 ms delay, and a unit test that
    throws `SQLiteException("database is locked")` once from a fake connection (or at least tests `busy()`).
- **GONE:** Keys writes check the keys against `channel` (`resolveKeys`). Rename and Number use `channelExists`, and
  group writes check `groupKeys`. An AllOf over an empty list is also GONE, which is acceptable. Tested in
  `EditorBulkWritesTest.goneWhenEveryKeyHasLeft`.
- **numberClash:** checked after the upsert, against the number the guide shows (`shownNumberSql`, which matches
  `ChannelRepository.number`), excluding the channel itself (`:364-383`). Tested in `EditorOrderWritesTest`.
- **skippedByRule:** counted on Show for channels (`countRuleHidden`) and for groups (`countGroups`). Tested in
  `keysHideAndShowSkipsRuleHidden`.

**SHOULD-FIX: an AllOf hide runs the list twice and holds every key** (`hideChannels:408-418`).
- `resolveKeys` builds a `List<String>` of the whole list (20,000 keys for All, about 1–2 MB transient) only to count
  it and to feed `countRuleHidden`. Then the `INSERT … SELECT` runs the same list again.
- That doubles the "Bulk hide of 5,000" cost (400 ms budget) for no benefit.
- **Fix:**
  - before the insert, run one aggregate over the same `listSql`:
    `SELECT COUNT(*), TOTAL(x_rh) FROM (…) WHERE x_l = 0 <filter>`;
  - then run the upsert;
  - GONE means `COUNT = 0`.

**NIT (memory):** the other AllOf writes (move, reset, favorite, lock) also hold the resolved key list. Design §4.5
accepts that as transient work on IO; it is not a streaming-rule breach.

**NIT: `LockChannels` counts in a separate read** (`:257`), then `setChannelsLocked` resolves again in its own
transaction. So `Done(n)` can disagree with what was written, and a refusal inside (a session locking between the two
calls) still returns `Done`. Fix: have `setChannelsLocked` return the count, or -1 when refused; `lockChannels` already
does. That is a core contract change for the lead.

**NIT:**
- `resolveKeys` (Keys) sorts per 500-key chunk, so the order is global only up to 500 ticks.
- `summarize` (Keys) excludes missing channels, but the writes include them, so the menu count can be lower than
  `Done(n)`.

Both are harmless at real tick counts. Filter `c.missing_since IS NULL` in `resolveKeys` too, for one meaning.

## 5. RoomMergeSink

- **Purge (`:243-252`).** In the same `finish` batch and **before** the `DELETE`, it clears `custom_group_key` and
  `custom_sort_index` for keys of `kind = 0` groups matching the delete predicate (grace expired, no provider members).
  It drives `index_channel_prefs_group` with `IN (subquery)`, so it is cheap.
  `GroupPurgeClearsMovesTest.thePurgeClearsMovedChannelsInTheSameBatch` covers a target left with only moved-in
  channels.
- **Returning group (`begin`, `:72-84`).** It runs before the upsert that clears `missing_since`, in the same
  transaction, with one prepared statement over `groups`. Batching is unchanged. The cost is one primary-key UPDATE
  plus an EXISTS on the identity index per group.
- `UserDataRemapper` moves whole `channel_prefs` rows, so a stable-key remap carries the new column.

**SHOULD-FIX: rule (e) is too aggressive for real panels.**
- Many Xtream panels leave out an empty category for a single refresh.
- With this rule, each such blip permanently sends that group to the end of the user's custom group order. The
  owner will read that as "my group order keeps breaking".
- The rule only protects against a **tie**.
- **Fix:** clear only when another group of the playlist and kind holds the same index:

  ```sql
  … AND EXISTS (SELECT 1 FROM group_prefs o WHERE o.playlist_id = group_prefs.playlist_id
                AND o.kind = group_prefs.kind AND o.group_key <> group_prefs.group_key
                AND o.custom_sort_index = group_prefs.custom_sort_index)
  ```

  Otherwise the group keeps its old slot. Record the change of rule (e) in the design. Extend
  `aReturningGroupLosesItsStaleIndexInBegin` with the no-tie case: the index is kept.

## 6. Backup

- `MergePlanner.CHANNEL_PREF_COLUMNS` has `custom_group_key` (`MergePlanner.kt:88`), and so does
  `USER_PREF_PREDICATE` (`BackupExporter.kt:171`). Export, the restore filter and `clearUserRows` follow the set.
- An old backup without the field keeps the device's value in merge mode and is nulled in replace mode. That is
  tested.

**SHOULD-FIX (the most important finding): a restore onto a fresh device drops every move.**
- `dropDanglingGroup` (`BackupRestorer.kt:381-387`) looks the key up in `channel_group` of the device's playlist.
- For a playlist the restore has just **inserted** (`apply:217-228`), there are no `channel_group` rows until its first
  refresh. So every `custom_group_key` is dropped silently.
- A restore onto a new box is the main reason to have a backup.
- Dropping is also not needed for safety:
  - readers fall back through `gt` by construction;
  - the purge only ever deletes groups that exist;
  - a key whose group never arrives stays inert (`moved` is false, half 2 needs `gt`).
- **Fix:** drop only when the playlist already has LIVE groups (`EXISTS channel_group WHERE playlist_id = ? AND
  kind = 0`) and the key is not among them. Better, never drop; that is the simplest rule and costs nothing. Either
  way, amend §3.6 (c).
- Add a test: restore into a fresh graph with no groups, then the move is kept; refresh with the group present, then
  the channel is in the target.

**SHOULD-FIX: a dropped key leaves its order behind.**
- When the key is dropped, the same record's `custom_sort_index` (an index into the **target's** order) is still
  restored.
- The channel then sits at an arbitrary slot in its provider group, which breaks rule (b).
- **Fix:** drop `custom_sort_index` together with the key. `aDanglingKeyIsDroppedSilently` should assert the index
  too; today it checks only `key|group|name`.

## 7. The guide

**Prefs signature: correct.**
- `PREFS_SIGNATURE_SELECT` has `custom_group_key` in its select list and in its `WHERE`, plus a hash step
  (`GuideQueries.kt:353-381`).
- `aMoveOnlyPrefChangesThePrefsSignature` asserts both, the `WHERE` through a `COUNT` over the select.
- `GuideRefreshAfterEditTest` asserts exactly one re-emission per write type for the lineup and for `sources`.

**The All order (Q2).**
- `ALL_ORDER` matches §3.7, and `GroupDao` / `GroupAdminDao` use the same group order.
- `ChannelEditorRepositoryTest.idOrderEqualsTheGuideLineupForGroupAndAll` pins the guide and the editor together.

**The cost of the All order.** On the JVM, All ids P90 is 54 ms against a 40 ms budget, and Guide All is 84 ms against
50 ms.
- The **sort is not new.** The old `ORDER BY c.sort_index` had no index giving that order: `index_channel_browse`
  puts `group_id` before `sort_index`, and `index_channel_missing` gives no order. So it was already a temp B-tree over
  the playlist.
- The joins on `cp`, `gp` and `g` are not new either: `HIDDEN` and `PARENTAL_ACCESS` need them.
- What v5 adds per row:
  - two lookups, `g0` by rowid and `gt` on the identity index (with a NULL key when there is no prefs row);
  - wider sort records (eight terms instead of one).
- **Can it be avoided?** Partly.
  - "Only when a custom order exists" saves almost nothing. Under Q2 the order is still group order then channel
    order, which is not `c.sort_index` for interleaved M3U lists. So the sort stays, and `gp`/`cp` are joined anyway.
  - The one real saving is the move joins. When the playlist has no move (`NOT EXISTS (SELECT 1 FROM channel_prefs
    WHERE playlist_id = ? AND custom_group_key IS NOT NULL)`, one probe of `index_channel_prefs_group`), run a variant
    with `LEFT JOIN channel_group g ON g.id = c.group_id` and no `gt` or `g0`.
  - The guard test needs that variant allowlisted as a named constant.
- **Recommendation:**
  - the one-pass ids-and-archive change (`:58-74`) is already the main win;
  - measure on the API 30 emulator, and on the Onn and Fire TV when available, before adding the no-move variant;
  - the JVM numbers here are framework SQLite under a loaded Gradle run.
- **SHOULD-FIX: the tolerance is too loose.** `EditorPerfTest` uses `JVM_FACTOR = 8` (`:30`), but §6.2 says 3×. At
  8×, a 5× regression passes silently. Set it back to 3, or 4 with the reason recorded, and rely on the device run as
  the gate.
- **NIT:** the Groups-panel effective count (`EFFECTIVE_COUNTS`) replaced per-group indexed counts with one full pass
  over the playlist on every `sources` re-emit. Its P90 is in the perf test; quote it in the hand-off.

**`changes(pid)` (`ChannelEditorRepositoryImpl.kt:209-220`).**
- It never emits on subscription: the base uses `emitInitialState = false`, and the prefs flow uses `drop(1)` after
  `distinctUntilChanged`. Tested.
- Watch-history and learned-playback writes do not emit. Tested.
- It is not narrowed to the playlist, because Room's tracker is per table. That is **acceptable** for correctness.
- **SHOULD-FIX (cheap):** while a refresh merge runs, every `apply` batch is a transaction on `channel`, so each one
  re-runs the open editor's list query (about 50 ms for All) plus the visible page. A 20,000-channel refresh is about
  40 batches.
- **Fix:** add `.conflate()` and a short debounce (250–500 ms) in `changes()` or in the view model, as the guide's
  row tick does (`GuideLibraryImpl.kt:194`, 500 ms).
- Hashing the whole `channel_prefs` table on each invalidation matches the guide (G-S6) and is fine.

**NIT:** `GuideQueries.lineup` tells Recent from the others by `q.order.contains("LIMIT")` (`:54`). Use an explicit
flag on `Clause.Parts`.

**Guide Move up/down (`GuideLibraryImpl.kt:169-184`).** It swaps with the nearest visible group (non-empty, the same set
`sources` shows, `:90`) inside `GroupAdminDao.orderedKeys`, then rewrites the full list. `GuideGroupMoveTest` asserts
dense indices with no collision. Correct.

## 8. Memory, streaming and logs

- Lists are ordered `LongArray`s, built by streaming the statement (`EditorQueries.channelList`).
- Rows come in pages of at most 32 (`channelRows`, chunked by 500 bound variables).
- `members()` holds one group, as §4.6 allows.
- No whole-playlist entity list exists; the AllOf key lists are covered in §4 above.
- Logs carry the class name and exception type only (`ChannelEditorRepositoryImpl:232,238`,
  `ParentalSessionImpl:254,270`), with no keys, names or SQL.
- The Find `LIKE` is escaped, and the FTS query goes through `ChannelRepository.ftsQuery`.
- `shownNumberSql` inlines only `toIntOrNull()` output and fixed SQL.
- **NIT:** `RuleHiddenPass:71-77` now runs the full effective join over the playlist **once per 500 matched groups**.
  Run `EFFECTIVE_COUNTS` once and sum the matched ids in Kotlin.

## 9. Tests against §6.2

Present, and they assert behaviour, not just "no crash":
- `ChannelEditorRepositoryTest`, `EditorSummaryTest` (under each access and with a filter);
- `EditorBulkWritesTest` (one invalidation for 5,000; the default list created and kept in order; Reset keeps order,
  playback prefs and Favorites; Reset of a moved channel; GONE);
- `EditorOrderWritesTest`: dense over hidden, rule-hidden, moved-in and missing members; the Collator; the number
  sort; `max + 1`; materialise;
- `GuideGroupMoveTest`, `EditorBulkLockTest`, `MoveToGroupTest` (every reader in the table, plus the tune guard);
- `GroupPurgeClearsMovesTest` (grace: still locked and hidden; returning index; purge in the same batch; a hand-deleted
  target);
- `ProviderGroupJoinGuardTest`, the v4 → v5 migration case, `BackupCustomGroupTest`, `GuideRefreshAfterEditTest`,
  `EditorPerfTest`;
- `GroupLineupPlanTest` (on device).

Missing or too weak:
- A restore onto a playlist with no groups yet (§6). Today it would expose the drop.
- That the dangling-key restore also drops `custom_sort_index`.
- The adult channel moved into an explicitly unlocked group under a locked session (§4).
- A returning group with no tie keeps its index, once §5 is fixed.
- The `SQLITE_BUSY` retry: none.
- A channel whose `custom_group_key` equals its own provider key appears once in the group lineup.
- The plan probe without `ANALYZE` (§3).

## Out of scope, but in the same tree

The working tree also carries the TMDB poster work: `data/src/main/kotlin/app/thebox/data/art/`, the
`data/build.gradle.kts` BuildConfig key read from `local.properties`, `DataModule` bindings and
`SecureBlobStore.REF_TMDB_KEY`. It is not part of this review. Commit it separately from the editor so that each commit
has one reason.

---

## Fixes to make before the owner sees it, in order

1. **Backup: stop dropping moves on a fresh device.** In `dropDanglingGroup`, keep the key when the playlist has no
   LIVE groups yet; simpler still, never drop. When a key is dropped, drop that record's `custom_sort_index` too. Add
   both tests, and amend §3.6 (c).
2. **Returning groups (`RoomMergeSink.begin`):** clear `custom_sort_index` only on a real tie with another group, so a
   one-refresh category blip does not reset the user's group order. Add the no-tie test, and amend §3.5 (e).
3. **The move's lock check:** refuse a move under `refusesLowering()` when a mover is locked now and would not be
   after the move. This covers an adult channel moved into a `gp.locked = 0` group, and it allows locked-to-locked
   moves. Add the test.
4. **The plan probe:** drop `ANALYZE`, so `GroupLineupPlanTest` checks the plan production gets. Move
   `GroupLineupPlanProbe` to `data/src/debug`.
5. **AllOf hide:** replace the `resolveKeys` pre-pass with one `COUNT`/`TOTAL(x_rh)` aggregate over the same list SQL.
6. **`changes()`:** add `conflate()` and a debounce of about 300 ms, so a refresh merge does not re-run the editor's
   list once per batch.
7. **`EditorPerfTest`:** set `JVM_FACTOR` back to 3 (or record why it is higher). Run it on the API 30 emulator, and
   only then decide on the no-move All variant (§7).
8. **NITs, in one pass:**
   - a `c.id` tie-breaker in the group order;
   - one-pass ids and archive for the group lineup;
   - an explicit Recent flag on `Clause.Parts` instead of `contains("LIMIT")`;
   - an alias-agnostic guard pattern, with `RoomMergeSink` allowlisted by line;
   - `setChannelsLocked` returns its count;
   - `missing_since` filtered in `resolveKeys` (Keys);
   - a single grouped count in `RuleHiddenPass`;
   - a delay before the BUSY retry, plus a test;
   - `MigrationOnDeviceTest`: rename it and seed a prefs row;
   - commit `5.json`;
   - commit the TMDB work separately.
