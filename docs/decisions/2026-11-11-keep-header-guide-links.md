# Keep the playlist header's last known guide links (owner decision 2026-11-11)

Status: built in c2c838e; fix pass after the review (`docs/review/2026-11-11-keep-header-guide-links-review.md`) the
same day.

## Defect
An M3U header can list guide links (`url-tvg` / `x-tvg-url`). Each becomes an `epg_source` row of origin
`PLAYLIST_HEADER`. On refresh, `RefreshRepository.syncSources` deleted every row whose ordinal was past the end of the
new list. A download whose header had no links at all (provider hiccup, truncated or alternate response) therefore
deleted every header guide, and `epg_mapping.source_id … ON DELETE CASCADE` took the user's manual channel-to-guide
matches with it. One bad download lost them for good. Rows were also paired with links by position only, so a header
that dropped its first link (`[A, B]` → `[B]`) rewrote A's row to B's address and deleted B's row: B's matches were
lost and A's matches silently pointed at B.

## Decision
Owner, 2026-11-11: "Keep the last known links". A download whose header has no guide links leaves the existing header
guide sources and the matches alone.

Lead ruling on the review (S2): the one exception is the first successful download after the user changes the
playlist's address. That download is authoritative even when its header lists no links.

## Rule per case
| Case | Before | Now |
|---|---|---|
| (a) same links | rows and matches unchanged, no guide rebuild | same |
| (b) no links (attribute missing or empty) | every header row deleted, matches cascaded | nothing touched; header JSON still stored; logged when header rows exist. Exception: see "Address change" |
| (c) different non-empty set | paired by position: a removed or moved link rewrote another link's row; trailing rows deleted with their matches | see "Pairing" below; unpaired rows deleted with their matches; ordinals follow the list; a pure re-order queues no guide rebuild |
| (d) download failed, empty, or rejected (shrink guard) | untouched: the merge throws before step 4 | same (pinned by a test) |

### Pairing (case c)
1. The exact URL held in the row's secret.
2. The same `url_hash`: the templated address with the query redacted, so a rotated token is the same guide. Rows
   whose secret is missing also pair here, and their secret is rewritten.
3. The leftovers:
   - When there are as many leftover rows as leftover links, they pair by position (S14: a renamed host or path
     updates in place, and placeholders fill in order).
   - Otherwise a restore placeholder takes the link at its ordinal. Any other row takes a link only when it is the
     single leftover row on the link's host. If several share the host, it must be the single one of those with the
     link's path; if none shares the host, it must be the single row with the link's path.
   - A link with no such row gets a new row. A row is never handed to an unrelated link.

So a link that is still listed keeps its row and matches when its URL is unchanged, only its query changed, or the
counts are equal. When a link changes both host and path while another link is added or removed, it gets a new row.
The same happens when its path is shared by several leftover rows (the reviewer's `[A, B]` → `[B']` with A and B both
on `/guide.xml`). The old row's matches then go with the old row; they are not moved onto another guide.

### Address change (review S2)
`SourceSetupImpl.saveM3u` (edit in place) clears `playlist.header_json` when the new address differs from the old
one. Step 4 treats `header_json IS NULL` on a playlist with `refresh_seq > 0` as an address change. The next successful
download then applies its list even when it is empty, which retires the old provider's guides, and storing its header
re-arms the guard. No migration was needed.

Address comparison (`PlaylistRepository.sameAddress`):
- **Compared:** scheme, host and port (case-insensitive, default ports filled in) and the path (a trailing slash ignored).
- **Ignored:** user info, the query and the fragment. A new login or token in `get.php?username=…&password=…` is the
  same address and keeps the guides.
- **Path-embedded credentials:** a URL with credentials in the path (`/get/u/p/`) counts as a change when they change.
  The consequence is mild: only a download whose header has no links then retires the guides.
- **Unreadable old address:** when the old address cannot be read (Keystore loss), nothing is cleared. Keeping is the
  safe direction.

### Crash safety (review S1)
`syncSources` does all Keystore writes first: new refs for new rows, and in-place rewrites for rows whose address
changes. One IMMEDIATE transaction then holds every `epg_source` write: deletes, parking, final ordinals, address and
hash updates, and inserts. The secrets of deleted rows are deleted last.

- A committed row always has its secret.
- An interruption leaves the old set or the new set.
- A secret rewritten before an interrupted transaction holds the address the row was moving to. The next refresh pairs
  the row by that address and finishes the update.
- Moved rows park on `-1_000_000 - id`, which is unique and cannot collide with real ordinals or with the -1..-32 an
  interrupted c2c838e build could leave. Such leftovers heal on the next refresh.
- If the transaction throws, the new refs are deleted.
- A crash after commit but before the final secret deletes leaves only unreferenced blobs.

Xtream (`ORIGIN_XTREAM`, one link, skipped when the URL cannot be built) and `USER` sources are not affected.
There is no user-facing "remove a header guide" action today (`EpgRepository.removeSource` has no caller); deleting the
playlist still removes its header guides through the playlist cascade.

## Why this rule for (c)
Pairing by address means a link that is still listed does not lose its row or its matches because another link moved
or went away, and it needs no schema change. A link withdrawn from a non-empty list is a real change by the provider,
so its row is still removed. A row is reused for a changed address only when the evidence says it is the same guide
(equal counts, as S14 always assumed, or a unique host/path match). Otherwise its matches would point at a guide whose
`xmltv_id`s most likely do not exist.

## Not done
- **Matches on a withdrawn link (case c) are still deleted by the cascade.** Keeping them needs either of two changes:
  - a soft-delete: `missing_since` on `epg_source`, kept out of `active()`, pruned after a grace period, and revived
    when the link returns;
  - re-pointing `epg_mapping` by `(playlist_id, stable_key)` to another source that has the same `xmltv_id`.

  Both are a schema/migration change plus matcher work, so they are left for their own design.
- **A kept link can go stale.** If the provider stops listing a guide for good, the row stays and keeps importing the old
  address; a failing import shows in that source's `last_status`. No "no longer listed by the playlist" marker was
  added (it would need a column), and no remove action in the UI.
- Restore placeholders on a playlist whose first refresh lists no links stay inert placeholders until a header lists
  links (previously they were deleted).
- Row names (`<playlist> (n)`) are set at insert and do not follow a re-order.
- **Review L1, L2 and L3 are open:**
  - L1: full-row `@Update` elsewhere on a stale snapshot. `syncSources` itself now uses targeted SQL.
  - L2: restore keys header sources by ordinal.
  - L3: the hash form and case/slash variants.

  See the review file.
- A backup can still carry a negative ordinal only if it was taken between an interrupted c2c838e sync and the next
  refresh. Filtering it on export would turn its mappings into "no guide", so it is left; the restorer already drops it.
