# Guide sync chip names the reason and the guide on screen

Date: 2026-11-01. Owner finding; lead diagnosed; implemented under the lead's brief (additive contract change to
`SyncFeedState`, reviewed by the lead at merge). Amends the sync chip row of docs/design/m3-guide.md §3.15.

## Finding

"The guide updates but right at the end after matching channels it says 500 error and shows an error on the guide,
but all the channels have shows and data."

## Cause

A playlist refresh ends with "Matching guide data" and then queues a guide import (`RefreshRepository`,
`importEpgNow(PLAYLIST_CHANGED)`). The import downloads each XMLTV source; the provider's `xmltv.php` answered
HTTP 500. With a last-good `<id>.src` file the build uses it silently (SUCCESS, no chip). Without one
(`enforceCachePolicy` drops it after every build when "Keep cached XMLTV" is Never, or under 1 GB free with the
default policy, which is common on Fire TV sticks), a transient error (`NetErrors.isTransient`) on a source the guide
already has data for keeps the current generation and logs a FAILED `EPG_BUILD` row with `http.500`; the worker
retries up to three times with backoff. That is the right data behaviour (the guide stays full). The chip was the
problem: it showed only "Guide update failed. Check Settings → TV guide." for any unacknowledged failure, which
reads as "the guide is broken" while the guide on screen is complete, and the line is ellipsized in the chip's space.

## Decision

`SyncFeedState` gains null-defaulted fields: the newest unacknowledged failure's `lastFailureCode`
(`SourceError.code`), `lastFailureTarget` (refresh_log target) and `lastFailureAtMs`, and `guideBuiltAtMs` (the
current generation's built-at, from `EpgStore.info`). `SyncFeedImpl` fills them; the newest failure is the first row
of `observeUnacknowledgedFailures` (started_at DESC). The pure `syncChipReason` in `:core` selects the chip; the
view model only maps it to strings.

Wording rule:

1. Busy wins (unchanged): "Updating guide…" or the progress line.
2. Newest failure is a guide build with a transient code (`http.5xx`, `http.408`, `http.429`, `net.*`) and a
   generation exists that was not built after the failure: reason, then which guide is on screen.
   "HTTP 500 · guide from Sat 18:40"; network errors "Offline · guide from Sat 18:40". The time is "18:40" today,
   "Sat 18:40" within the last week, "13 Oct 18:40" before that; the 12 h setting gives "6:40 PM"; device zone.
3. Guide build with a non-transient code keeps a reason and a next step: "Refused (HTTP 403) · see Settings",
   "Sign-in rejected · see Settings" (`auth.*`), "Unreadable guide · see Settings" (`format.*`, `parse`),
   "Storage full · free up space" (`disk`).
4. Anything else (no generation, playlist and patch failures, other codes, a generation built after the failure)
   keeps "Guide update failed · see Settings" (shortened 2026-11-02; the longer text clipped at 443 px).

No raw codes except the HTTP status number. Styling (`failed = true`, amber dot) and acknowledgement (Diagnostics,
Menu → dismiss) are unchanged; Settings › TV guide keeps its "HTTP 500. The server refused the request" line.

Length: the chip is one line (`SyncChipView`, max 420 design px) in the space between the tabs and the clock, about
335 px of text at 20 px Instrument Sans. Measured with the font's advance widths: the stale forms are 262–336 px in
the worst case ("HTTP 500 · guide from Sun 12:40 AM"), the non-transient forms 239–314 px. The lead's longer draft
("Guide not refreshed: the provider's guide server answered HTTP 500. Showing the guide from Sat 18:40. Retrying.")
would be cut after about 30 characters, so it was shortened with the reason first. "Retrying" was dropped: it does
not fit, and after the third attempt the worker stops until the next periodic or app-start import, so it would be
untrue.

## Reproduction

Fake panel: `POST /admin/config {"epg_status":500}` (or `"epg_status": 500` in the config file) makes `xmltv.php`
answer 500. Set "Keep cached XMLTV" to Never (or have the cache dropped), import the guide once with `epg_status` 0,
then set 500 and refresh the playlist.

## Follow-up 2026-11-02

Owner finding (Sony Bravia log): the device held 18 `FAILED` `EPG_BUILD` rows with `http.500` from 2026-10-29, when
the provider's `xmltv.php` answered 500 for an evening. Eight guide builds since then succeeded (generation 41 on
screen), yet Settings › TV guide said "Failed: HTTP 500" and the chip showed a failure for days. Nothing but the
row menu's Dismiss, one row at a time, ever acknowledged a failure, and the 30-rows-per-target retention kept them.

Ruling: a successful run resolves the earlier failures of the same target. `RefreshLogDao.insert` (one transaction
with the insert and trim) marks `acknowledged = 1` on the target's `FAILED` rows with a smaller `id` when the new
row is `SUCCESS` or `NOT_MODIFIED` (a playlist 304 is recorded as `last_success_at`, and after a failure the stored
ETag usually makes the next refresh a 304, so without it the failure would never clear). The match is
`target_type = ? AND target_id IS ?`, so `EPG_BUILD` (null id) and each playlist are separate. An `EPG_BUILD` success
also acknowledges earlier `EPG_PATCH` failures (a full rebuild supersedes any patch, and the Settings guide row shows
only build failures, so a stuck patch failure kept the chip amber with no Dismiss anywhere); a patch success does not
clear a build failure. The order is by `id`, not `started_at`, because the device clock can move back. Dismiss stays
for failures with no later success.

`syncChipReason` keeps its "generation built after the failure → generic wording" guard: the generation swaps inside
`store.build`, and `finishBuild` logs only after `setImported`, cache policy and `matches.afterBuild`. The worker is
busy in between (the chip says "Updating guide…"). The guard is reached when a build swapped the generation but
logged no row (worker cancelled after the swap, a throw in `setImported`/`enforceCachePolicy`/`afterBuild` outside the
build's try, process death), or when the wall clock was wrong at failure time.

Known gaps (from the review, open): with several guide sources, a source that failed without a cached copy is
dropped from a build that still logs SUCCESS, so its earlier failure is cleared while its data is missing (narrow,
predates this change). A playlist refresh that fails after the merge saved the new ETag gets a 304 next time, and that
NOT_MODIFIED clears the failure although the post-merge steps did not re-run (rare).
(amended 2026-11-10, review of the deferred fixes N3) Since the unchanged-guide skip (performance review M6), the first
gap also applies when every other source answers 304 or an identical file: the periodic rebuild that keeps the future
window moving (at least once a day) drops such a source the same way, where before it stayed in the generation.
