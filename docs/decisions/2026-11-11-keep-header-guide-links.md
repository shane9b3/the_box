# Keep the playlist header's last known guide links (owner decision 2026-11-11)

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

## Rule per case
| Case | Before | Now |
|---|---|---|
| (a) same links | rows and matches unchanged, no guide rebuild | same |
| (b) no links (attribute missing or empty) | every header row deleted, matches cascaded | nothing touched; header JSON still stored; logged |
| (c) different non-empty set | paired by position: a removed or moved link rewrote another link's row; trailing rows deleted with their matches | paired by address first (exact URL in the row's secret, then `url_hash`, i.e. the address with secrets and the query redacted), then left-over rows take left-over links in order (S14: a new host updates in place, placeholders fill in order); only rows left over after that are deleted, with their matches; ordinals follow the list. A pure re-order changes ordinals only and queues no guide rebuild |
| (d) download failed, empty, or rejected (shrink guard) | untouched: the merge throws before step 4 | same (now pinned by a test) |

Xtream (`ORIGIN_XTREAM`, one link, skipped when the URL cannot be built) and `USER` sources are not affected.
There is no user-facing "remove a header guide" action today (`EpgRepository.removeSource` has no caller); deleting the
playlist still removes its header guides through the playlist cascade.

## Why this rule for (c)
A link that is still listed must never lose its row, or its matches, because another link moved or went away; pairing by
address gives that with no schema change. A link withdrawn from a non-empty list is a real change by the provider, so its
row is still removed (the previous behaviour, now limited to that row). The S14 in-place update for a changed address is
kept, so a provider that moves its guide to a new host keeps the matches.

## Not done
- **Matches on a withdrawn link (case c) are still deleted by the cascade.** Keeping them needs either a soft-delete
  (`missing_since` on `epg_source`, kept out of `active()`, pruned after a grace period, revived when the link returns)
  or re-pointing `epg_mapping` by `(playlist_id, stable_key)` to another source that has the same `xmltv_id`. Both are a
  schema/migration change plus matcher work, so they are left for their own design.
- **A kept link can go stale.** If the provider stops listing a guide for good, the row stays and keeps importing the
  old address; a failing import shows in that source's `last_status`. No "no longer listed by the playlist" marker was
  added (it would need a column).
- Restore placeholders on a playlist whose first refresh lists no links stay inert placeholders until a header lists
  links (previously they were deleted).
- Row names (`<playlist> (n)`) are set at insert and do not follow a re-order.
