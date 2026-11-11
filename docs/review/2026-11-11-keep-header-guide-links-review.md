# Review: keep the header guide links (c2c838e)

Reviewer: independent code review, 2026-11-11. Scope: `git show c2c838e` (parent c0f0b3f). The change touches
`RefreshRepository.syncSources` and refresh step 4, `RefreshRepositoryTest`, the decision note
`docs/decisions/2026-11-11-keep-header-guide-links.md` and three design-doc amendments.

Counts: **Blocker 0, Should-fix 3, Later 6.**

## Should-fix

### S1. An interrupted sync leaves parked ordinals that make every later refresh fail
`data/src/main/kotlin/app/thebox/data/repo/RefreshRepository.kt:406-413`

`syncSources` is not in a transaction. Each `sourceDao.update` / `delete` / `insert` is its own Room transaction,
and the Keystore calls (`putSecret`, `deleteSecret`) between them are suspension points. Rows that move are parked on
`-1 - i`, where `i` is their new position. They reach their final ordinal only in the second loop. Cancellation
between the two loops leaves rows on negative ordinals: WorkManager stopping the worker, a quota or constraint stop, or
the process dying (TVs are often switched off at the wall). The next refresh can then park a row onto an ordinal a
leftover row still holds, and the unique index `index_epg_source_origin (playlist_id, origin, ordinal)` throws.

**Reproduced** with a throwaway test (since deleted):
1. Header `[A, B]` gives rows RA (0) and RB (1).
2. Set the ordinals an interrupted `[A,B] → [B,A]` sync leaves: RB = -1, RA = -2.
3. Refresh with header `[A, B]`. Pairing gives slot 0 = RA, and RA is parked on -1, which RB still holds. Result:
   `Internal(detail=SQLiteConstraintException)`.
4. The second refresh fails the same way. The state is stuck until the provider happens to list `[B, A]` again.

Each of these refreshes commits the channel merge. It then fails in step 4, before step 5, so no matching runs and no
guide rebuild is queued. The user sees a failed refresh with an internal error code, and the only way out is deleting
the playlist. Knock-on effects: a backup taken in this state exports negative ordinals. A restore drops those rows,
because `BackupRestorer.placeholderFits` (`BackupRestorer.kt:468`) needs ordinal 0..31, and `resolveSource`
(`:442`) finds nothing for their mappings.

Suggested fix (either one; ideally both):
- Park on a value no other row can hold, such as `ordinal = -row.id`. Ids are unique, so parked rows never collide,
  whether they are left over from an earlier run or not.
- Run the DB part in one `db.withTransaction { }`: the pairing, the deletes, the parking and the final ordinals. Do
  the Keystore writes before it (new refs) and the secret deletes after it. A crash then leaves either the old set or
  the new set.

Add a test that seeds negative ordinals and refreshes with a different order.

### S2. A header with no links can now never retire its guides, and nothing in the UI can remove them
`RefreshRepository.kt:177-181`, `data/src/main/kotlin/app/thebox/data/setup/SourceSetupImpl.kt:190-208`,
`data/src/main/kotlin/app/thebox/data/repo/EpgRepository.kt:122` (no caller)

The guard is right for a provider hiccup, but it is keyed only on "this download has no links". It cannot tell a
glitch from a real change, and the decision note's escape hatch (delete the playlist) is the only way out. One
concrete flow the decision did not consider:

- **Edit in place to another provider.** `SourceSetupImpl.saveM3u(existingId = …)` replaces the playlist URL and
  keeps the `playlist_id` (product #6). Suppose the old provider's header listed `url-tvg` and the new one does not;
  the user types the new guide link on the form, which becomes a USER source.
- Result: the old provider's `PLAYLIST_HEADER` rows stay for good. Their secret still holds the old provider's URL
  and login, and every guide build keeps downloading it with that login.
- Those rows also keep their priority. A tvg-id they share with the new playlist (`BBCOne.uk`-style ids are common)
  can show the old provider's programmes, and a failed fetch leaves stale data until it ages out.
- Before this change, the first refresh after the edit removed those rows.

The mechanics are what `caseB_aHeaderWithNoLinksKeepsTheSourcesAndTheManualMatches` pins. I did not write a separate
end-to-end edit test. The same applies to a provider that really withdraws its guide, and to a power user who removes
`url-tvg` from an `M3U_FILE`: the rows stay, and `removeSource` has no caller (`SettingsRoute.EpgSources` has no page
yet).

Suggested fix, smallest first:
- (a) In the `saveM3u` edit path, when the URL's host or path changes, delete the playlist's `PLAYLIST_HEADER` rows
  (and their secrets), or flag the next refresh so that an empty list is treated as authoritative.
- (b) Make the guard count consecutive empty headers, e.g. a `header_guide_misses` counter on `playlist`. A transient
  glitch keeps the rows; after N successful downloads without links (or K days) the empty list is treated as real.
- (c) At minimum, record in the decision note that an edit to another provider keeps the old guides, and make the
  guide-sources page (m5 open item) own a "remove" action for header rows.

### S3. The decision overstates what the address pass guarantees: a withdrawn first link plus a moved second link still crosses the matches
`RefreshRepository.kt:398-400`; `docs/decisions/2026-11-11-keep-header-guide-links.md` ("Why this rule for (c)");
KDoc at `RefreshRepository.kt:370-377`

Passes 1 and 2 match only an unchanged URL or an unchanged redacted form (same host and path). Anything else falls
through to pass 3, which pairs leftover rows with leftover links in ordinal order, as the old code did.

**Reproduced** (throwaway test, since deleted):
1. Header `[A, B]`, each row with one manual match.
2. The header becomes `[B']`, where B' is B on a new host.
3. Pass 3 gives B' to RA, the row with the lowest ordinal, and deletes RB.
4. Result: A's match (`key-0`) now points at the B' guide, and B's match (`key-1`) is deleted. This is the same
   failure the commit message describes as fixed, in a slightly different shape.

It is not a regression: the parent behaves the same. But the decision text says "a link that is still listed must
never lose its row", and that holds only when the link's URL, or its host and path, are unchanged.

Suggested fix:
- At minimum, say so in the decision note and the KDoc.
- Better: when pass 3 has more rows than links, prefer a row whose `display_source` (host) or path matches. Failing
  that, either only pair rows with links when the counts are equal, or delete rather than reassign. A reassigned row
  carries matches whose `xmltv_id` most likely do not exist in the new guide.

## Later

### L1. A full-row update on a stale snapshot can overwrite a concurrent guide import's or restore's writes
`RefreshRepository.kt:408, 413, 442`

`syncSources` runs after `ImportMutex` is released. The guide build, and restore's `UPDATE epg_source SET enabled,
priority, …` (`BackupRestorer.kt:346`), run under the mutex. `@Update` writes the whole row from the snapshot taken at
`:388`, so a concurrent `etag`/`cached_file`/`cached_bytes`/`last_status` write or a restored priority can be reverted.

The hash-update path at `:442` already did this. The change adds two more full-row writes per moved row, including on
a pure re-order, which before wrote nothing.

Suggested fix: a targeted `@Query("UPDATE epg_source SET ordinal = :o WHERE id = :id")` for the parking and the final
move, and the same idea for the hash, display and etag update.

### L2. Backup and restore key header sources by ordinal, which a re-order now moves under a still-listed link
`BackupRestorer.kt:342-348, 436-445`; `core/.../backup/MergePlanner.kt:95`

The exported `sourceKey` includes `urlHash`, but `resolveSource` matches header sources only by
`(playlist, origin, ordinal)`. Suppose a backup is taken with `[A, B]`, the provider re-orders to `[B, A]`, and the
backup is restored in merge mode. Then A's overrides and mappings land on B's row.

This is not a regression: before the change, a re-order rewrote both rows' addresses, which was worse. But the reason
the ordinal key was safe has changed.

Suggested fix: resolve by `(playlist, origin, url_hash)` first and fall back to the ordinal (and placeholders).

### L3. Hash pass and case/whitespace/trailing-slash variants
`RefreshRepository.kt:387, 399`

`Redact.text` drops the whole query, so `epg.php?c=uk` and `epg.php?c=us` hash the same, and so do a token rotation
and a different guide. Pass 1 runs first over all links, so unchanged URLs never cross. Cross-pairing needs both a
rotation and a re-order of same-path links, which is rare.

A change in case (`HTTP://EPG.A/`) or a trailing slash matches neither pass and falls to pass 3 (see S3).

`Redact.text` also masks registered transient tokens (a 64-entry process-wide LRU), so the stored `url_hash` of the
same URL can in principle differ between processes. The code already did that; it now also affects pairing. Consider
hashing `template(url)` with only the query stripped, not the full `Redact.text`, and normalising the scheme/host
case and a trailing slash.

### L4. Test gaps
The new tests are real regression tests: with the parent's `RefreshRepository.kt` and the new test file, 5 tests
fail:
- `caseB_…`
- `caseC_aDroppedLink…`
- `caseC_aReorderedHeader…`
- `caseC_anAddedLink…`
- `xtvgUrlChangeKeepsSourceIdAndMappings`

`caseA`, `caseD`, `regression_aChangedLink…` and the Xtream test pass on both, as intended for guards.

Not covered:
- A pure re-order queues no guide rebuild. The decision claims it, but `caseC_aReorderedHeader…` does not check
  `requester.imports`.
- Pass 2 (same `url_hash`, rotated token): row kept, secret rewritten, rebuild queued.
- A row whose secret is unreadable (Keystore loss): pass 2 heals it, and it is rewritten.
- Restore placeholders with the new pairing, including placeholders kept by an empty first header and filled later.
- The parked-ordinal recovery (S1).
- More rows than links where the left-over link is a moved one (S3).
- A failed `putSecret` on a moved row.

### L5. Docs that still describe the old identity
- `data/src/main/kotlin/app/thebox/data/db/entity/EpgSourceEntity.kt:11` still says playlist-derived sources "are
  identified by (playlist_id, origin, ordinal)".
- `core/src/main/kotlin/app/thebox/core/backup/BackupContracts.kt:45` says the same.
- `m1-data-layer.md` says "Ordinal = the link's position after each refresh". That is not true when an insert's
  `putSecret` fails (the position stays empty) or after an interrupted sync (S1).

### L6. The log line fires on every refresh of every M3U without guide links
`RefreshRepository.kt:178`

It does not leak anything: it holds only the playlist id. But it reads "keeping the last known ones" even when there
are none. Log it only when header rows exist.

## Checked and found sound
- **The guard's trigger.** `HeaderAttrs.splitEpg` splits on commas and whitespace and drops empty pieces, and the set
  is a `LinkedHashSet`. A missing attribute, `url-tvg=""`, a whitespace-only value, a missing `#EXTM3U` line
  (`header ?: M3uHeader()`) and an attribute the lexer drops all give an empty list. All of them take the keep path,
  which is the safe direction.
- **Duplicate URLs.** A header cannot produce the same link twice (the set dedups it), so pass 1 cannot be ambiguous
  within one header.
- **Pairing order.** Pass 1 claims exact matches over all links before pass 2 runs, so an exact match always wins
  over a hash match. Placeholders (hash 0) are excluded from pass 2, and a placeholder has no secret, so pass 1 cannot
  match it. They fill in ordinal order in pass 3, as before.
- **Ordinal collisions within one clean run.** Unclaimed rows are deleted before any parking. Every claimed row whose
  ordinal changes is parked before any row takes its final ordinal, and an insert at position `i` happens only after
  every row that held `i` was deleted or parked. A clean run cannot violate the unique index.
- **Unreadable secrets.** A row whose secret cannot be read has `oldUrls = null`. Pass 2 can still claim it by hash,
  and then `old != url` rewrites the secret and queues a rebuild. Keystore loss heals instead of deleting.
- **Rebuild triggering.** `changed` is set on every delete, insert, URL change or hash change. A pure re-order sets
  nothing, and ordinals are not read by the guide build. No missed rebuild was found.
- **Xtream.** There is only one link, so pass 1 or pass 3 applies and nothing is parked. The behaviour is the same as
  before, and `xtreamGuideSourceAndMatchesSurviveRefreshes` passes. USER sources are filtered out by origin.
- **Secrets.**
  - The new log line holds only the playlist id.
  - Deleted rows delete their secret first.
  - Kept rows keep their ref.
  - No new orphan path was found. An insert that throws after `putSecret` can orphan a secret, but the code already
    did that.
- **Failed downloads.** A failed, empty or shrink-rejected download never reaches step 4 (`caseD` pins it).
- **Build.** `:data:testDebugUnitTest` passes at c2c838e.

## Not verified
- Whether WorkManager cancellation in practice lands between the parking loop and the final loop. The window has
  several suspension points (Room calls and `putSecret`). S1 was reproduced by seeding the state, not by cancelling a
  real worker.
- The concurrent overwrite in L1. It follows from reading the code (`ImportMutex` is released before step 4, and the
  guide build writes `epg_source` under it); there is no test.
- The S2 end-to-end flow through `SourceSetupImpl.saveM3u`. The mechanics are covered by `caseB`. I did not check
  which source wins for a shared tvg-id between an old header guide and a new USER guide.
- Instrumented tests, lint and `:app:assembleDebug` were not run (out of scope; no instrumented tests by
  instruction).

## Fix pass (2026-11-11)
Lead rulings applied on top of c2c838e. Decision note updated:
`docs/decisions/2026-11-11-keep-header-guide-links.md`.

| Finding | Disposition |
|---|---|
| S1 interrupted sync | **Fixed.** `syncSources` now runs in three steps, so a committed row always has its secret and an interruption leaves the old or the new set: (1) Keystore writes: new refs, and in-place rewrites of rows whose address changes; (2) one `writeTransaction` with every `epg_source` write, in targeted SQL; (3) the dropped rows' secret deletes. Parking uses `-1_000_000 - id` (unique, below any committed ordinal). New refs are deleted if the transaction throws. Test `s1_parkedOrdinalsLeftByAnInterruptedSyncHealOnTheNextRefresh` uses the reproduction here (RB -1, RA -2, header `[A,B]`, then `[B,A]`). Backup export: negative ordinals can only come from an interrupted c2c838e build and heal on the next refresh. Filtering them on export would turn their mappings into "no guide", so export is unchanged (noted in the decision). |
| S2 header guides never retired | **Fixed (edit path).** `SourceSetupImpl.saveM3u` clears `header_json` when the address changes (`PlaylistRepository.sameAddress`: scheme, host, port and path compared; user info, query and fragment ignored; an unreadable old address keeps the header). Refresh step 4 treats `header_json IS NULL` with `refresh_seq > 0` as authoritative for that one download, so an empty list then retires the header guides. No migration. Tests: `s2_afterAnAddressChangeAHeaderWithNoLinksRetiresTheOldGuides`, `s2_withoutAnAddressChangeTheEmptyHeaderStillKeepsTheGuides`, `editingAnM3uToAnotherAddressClearsTheStoredHeader`, `editingOnlyTheLoginOrTokenKeepsTheStoredHeader`, `sameAddressComparesSchemeHostPortAndPathOnly`. A provider that really withdraws its guide without an address change still keeps it (no UI remove action, per ruling). |
| S3 fallback crosses matches | **Fixed.** The leftovers pair by position only when the counts are equal. Otherwise a placeholder takes the link at its ordinal, and another row takes a link only as the unique same-host row (narrowed by path if several share the host) or, with no host match, the unique same-path row; any other link gets a new row. KDoc and decision note now state exactly this. Tests: `s3_aMovedLinkThatSharesNothingUniqueWithARowGetsANewRowAndNoMatchesCross` (the case here: A and B share `/guide.xml`, so B' gets a new row and no match crosses), `s3_aMovedLinkPairsWithTheOneRowSharingItsPathOrHost`. |
| L1 stale full-row update | **Open.** `syncSources` itself now writes targeted SQL (ordinal; hash/display/etag), so the change no longer adds full-row writes. Other full-row `@Update` callers are unchanged. |
| L2 restore keyed by ordinal | **Open.** |
| L3 hash form / case / slash | **Open.** The S3 fallback limits the damage: a case/slash variant with unequal counts pairs by host. |
| L4 test gaps | **Done:** re-order queues no rebuild (assert added to `caseC_aReorderedHeader…`), `l4_aRotatedTokenKeepsTheRowRewritesItsLoginAndQueuesARebuild`, `l4_aRowWhoseLoginIsMissingHealsInPlace`, `l4_placeholdersStayThroughAnEmptyFirstHeaderAndFillByOrdinalLater`, `l4_aFailedLoginWriteOnAMovedRowKeepsItsOldAddressUntilTheNextRefresh`; S1 and S3 as above. |
| L5 stale wording | **Fixed** in `EpgSourceEntity.kt`, `BackupContracts.kt` and `m1-data-layer.md` (the `epg_source` comment and refresh step 4). |
| L6 log line | **Fixed:** it is logged only when the playlist has header rows to keep. |
