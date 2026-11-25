# Architecture review: event channels and team channels (docs/design/m6-event-channels.md)

Reviewer: architecture (Opus), 2026-11-25. Read-only. Code at `main` `b37512c` plus the uncommitted working tree.
Evidence: the code (file:line), and the read-only copy of the owner's `library.db` (refresh of Nov 25 02:55 UTC, the
`events/FACTS.md` copy). Scripts that produced the counts sit in the session scratchpad under `review/` (`p2.py` reuses
the proposer's regexes from `evscripts/proto.py`; `live.py`, `times.py`, `epgcov.py`, `match.py` and `games.py` add the
missing-row filter, real instants, EPG coverage and a stage E simulation against the recorded NHL schedules in
`nhl/score-y.json` and `nhl/score-now.json`). No names or ids are copied beyond format examples.

Verdict at the end: **ready after the listed changes**. Five blockers, all fixable without a redesign. B1 needs one
data pull from the device before commit 0.

---

## What is sound (checked, keep it)

- **Parser precision on real names.** Over the 13,109 live rows (`missing_since IS NULL`), no name outside a
  `Sports | …` or `PPV Events | …` group parses as an event or as an empty slot. Every parsed date and clock is valid (0
  rejects), and nothing throws. The right-anchored, table-driven shape is right.
- **Zones on real data.** The UK-tagged shape C names on `Sports | DAZN UK` give the NHL API start **exactly** (Δ 0
  min) for all 7 Nov 23 games (`Blue Jackets @ Devils (2026-11-24 00:00:00)` = CBJ@NJD 00:00Z, and so on). Shapes A and B
  in ET give VGK@EDM at Δ 0 (`NHL 01`, `Sportsnet+ 09`) and Δ −30 (`NHL on TNT`). The CONVENTION table is right.
- **Stage E precision.** Simulated against the 8 recorded games with the −90/+30 window, stage E returns 11 hits and
  **no false positive**: every hit is the right game, and the window is the main filter (few events start within
  two hours of a given game).
- **Team-channel rule.** All 64 owner channels (`Sports | NHL Teams` and `Sports | NFL Teams`) satisfy "name equals a
  full name, plus a group or tvg_id context". The tvg stems include `UtahHockeyClub.nhl` and `StLouisBlues.nhl`. Same-name
  clubs of other leagues (`Sacramento Kings`, `Texas Rangers`, `St. Louis Cardinals`, `San Francisco Giants`) sit in
  `NBA Teams` and `MLB Teams`, so the adjacent-words group rule keeps them out.
- **Identity.** On Xtream, `stable_key` is `xc:<stream id>`, so a rename never changes identity. `channel_fts` is
  already rewritten on every rename (`RoomMergeSink.kt:137-142`; refresh_log: 1,553 to 1,589 renames per refresh), so
  the design adds no new FTS churn.
- **Storage additions.** The migration only adds columns: `ADD COLUMN … DEFAULT` plus one index, the
  `MIGRATION_2_3`…`4_5` pattern. Backup is unaffected because `MergePlanner.PLAYLIST_COLUMNS` is a whitelist
  (`core/.../backup/MergePlanner.kt:74`) and the channel rows are never backed up. Reminders on event cells travel with
  the reminder table as today.
- **Connection limits.** `StreamConnectionBroker` (`core/.../playback/broker`) covers stream connections only.
  `get_live_streams` is an API call and does not count against `max_connections`. The 6 h cadence has no broker
  interaction.
- **Pager overlay mechanics.** A merged copy per window build, plus negative text ids, works with the current
  snapshot code. Negative ids already bypass the width, flag and hash caches (`GuideWindowPagerImpl.kt:507-520`), and
  `cellTextId` has no other reader (only `GuideModel.kt:235`). The < 0.3 ms per build claim is plausible: it copies
  ≤ 300 rows.
- Removing regional-only candidates is safe for the owner. The Canadian regionals have EPG mappings
  (`SportsnetOilers.ca`, `SportsnetOntario.ca` …, status 0), so stage C still confirms them when the listing names the
  game, or keeps them as a SportListing when it doesn't.

---

## Blockers

### B1. Half of the parsed events already have guide data. The "EPG wins" rule and search behaviour are unknown for them.

The design assumes event channels have no EPG ref: §5.1 "event channels have no EPG ref (`tvg_id` empty)", FACTS
"`tvg_id` empty". The db says otherwise:

| live parsed events (1,543) | count |
|---|---:|
| `tvg_id` set, `epg_match` status 0 `ID_EXACT`, `has_data = 1` | **777** |
| `tvg_id` set, matched, no data | 84 |
| no tvg_id, no status-0 match | 634 (+ FUZZY suggestions only, status 1, not used by the resolver) |

The ids are the provider's own event EPG, `BossSports.<group>.<slot>`. For example
`ESPN UNLTD 001: … (11.24 5:00 AM ET)` has tvg_id `BossSports.ESPN Unlimited.001`, and `NHL 01: NHL on TNT: …` has
`BossSports.NHL V2.01`. This covers ESPN Unlimited (246), NCAA Basketball (2) (110), ESPN+ (100), NCAA Basketball (50),
DAZN CA/UK, Sky Sports+ and more. `DISPLAY_NAME` auto-matches also hit event-style names
(`NFL SD 01: No games scheduled` → `NFL 01`). The owner's own example group, `Sports | Sportsnet+`, has no status-0
match, so the overlay works there.

Failure scenarios (we cannot tell which one applies without the `epg.db`):
- (a) The BossSports guide holds filler ("NO EVENT", "Upcoming", or the slot name as a 24 h block). §6.1 "EPG wins if
  anything overlaps" then hides the event on **half** of the event channels, including ESPN+ and ESPN Unlimited.
- (b) The BossSports guide lists the event. The overlay is moot there, but search (§7) shows the event twice, once from
  the EPG FTS and once from `channel_fts`. The `seen` key is `identityHash × rawStartMin` (`GuideSearchImpl.kt:123-130`),
  and the EPG start rarely equals the name's start: TNT 7:30 against 8:00. Stage B and stage E also both fire on one
  channel.

Proposed change:
1. Before commit 0, pull `epg.db` (or the cached XMLTV) from the owner's device. Measure the BossSports programmes:
   their titles, lengths and coverage around the parsed starts.
2. Write the rule in §6.1 and §7 against that data. For example, EPG wins only when an overlapping programme is not
   filler (folded title ≠ slot or "no event", length < 12 h). Search skips an event hit when the channel has an EPG
   hit within ±2 h, or the other way round, per the measured content.
3. Fix the premise sentences in §5.1 and FACTS.

### B2. The learned zone and `event_count` are computed only from rows that changed. A quiet refresh flips the zone.

§5.2 keeps the zone counts and the event count "in the sink while streaming". The sink only parses through
`bindContent`, which runs for inserts and updates. **Touched rows (unchanged name) never pass through it**
(`RoomMergeSink.kt:144-148`). Refresh 31 in `refresh_log` is `updated 24, renamed 24, unchanged 13,085`.

Failure:
- That refresh sees fewer than 20 explicit zone words, so §3.3 falls back to the panel zone (`Europe/London` for the
  owner).
- §5.3 sees "the majority differs" and re-parses every ASSUMED row: Fanatiz, PDC, F1 TV, NBA LP, MonoMAX. All of them
  move **5 h**.
- The next busy refresh (1,553 renames) flips them back.
- `event_count` falls to ≤ 24 on the quiet refresh, so §10's cadence drops back to 24 h.

Proposed change: compute both in `finish`, over the whole playlist's stored rows:
- `event_count`: `SELECT COUNT(*) … event_kind = 1 AND missing_since IS NULL`.
- Zone tallies: store the explicit zone id per row (a seventh column `event_zone_id TEXT`, or fold it into
  `event_zone`) and run `GROUP BY`.
- Add hysteresis: keep the stored zone unless the new majority has ≥ 20 samples **and** ≥ 2× the stored zone's count.
- Never fall back to the panel zone when a stored learned zone exists.

### B3. Stamping `event_parser_ver` in `finish` repeats the bug the sink's own comment warns about.

§5.3 says `finish` stores `event_parser_ver = VERSION`. `RoomMergeSink.kt:206-208` says why `name_prefix_ver` is
**never** raised there: "rows this merge did not insert or update keep … whatever rule computed them … stamping … would
make the startup backfill skip this playlist forever".

Failure on the v5 → v6 upgrade:
- The migration leaves the version at 0 and `event_kind` at 0 on every row.
- `RefreshScheduler.onAppStart` (`RefreshScheduler.kt:163-170`) enqueues the start-up refresh. The backfill runs
  elsewhere at start (the existing pattern is `TheBoxApplication.kt:144`).
- If the refresh's `finish` commits first, it stamps 1. The backfill then skips the playlist, and every unchanged row
  (≈ 11–13k on a quiet refresh, including still-current event names such as the 99 Fanatiz and 50 PDC slots listed weeks
  ahead) has no event **until its name changes**.
- The same race repeats on every parser bump.

Proposed change: follow `DisplayNames.recompute` (`data/.../guide/DisplayNames.kt:58-95`).
- Only `EventBackfill` raises the version.
- It runs under `importMutex`, reads in pages and writes only rows whose result changed.
- The merge never stamps the version.
- Alternatively, `finish` re-parses **all** of the playlist's rows when `event_parser_ver < VERSION` (14k parses, about
  0.2 s on a Fire TV Stick) and only then stamps it.

### B4. `CellFlags.EVENT_NAME = 1 shl 29` collides with `CellFlags.RTL`.

`GuideModel.kt:203` already defines `const val RTL: Int = 1 shl 29`. `SLIVER` is bit 30 and `COMPLEX` is bit 28.
`ProgrammeFlags` uses bits 0–12 (`EpgContracts.kt:169-185`).

Failure:
- The pager ORs `RTL` into every Arabic or Hebrew title (`GuideWindowPagerImpl.kt:519-521`), so every RTL EPG cell
  would read as an event cell. The panel would say "From the channel name", and "Watch from start" would disappear.
- Event cells would draw right-aligned.

Change: `EVENT_NAME = 1 shl 27`, in commit 0. The contracts freeze after commit 0, so this must be right there.

### B5. The pager will not redraw renamed event rows after a refresh.

§6.6 only checks that the rows cache reloads (it does: `GuideLibraryImpl.kt:204` → `GuideViewModel.kt:495`
`reloadLoaded()`). The pager's coverage gate then skips the rebuild. `rowsChanged` re-queries only when `epgBasisOf`
changes (`GuideWindowPagerImpl.kt:200-214`: "A display-name change alone does not re-query"), and `epgBasisOf` hashes
only the shift and the EPG ref (`GuideModel.kt:303-310`). The state machine uses the same basis to accept a snapshot
(`GuideStateMachineImpl.kt:2062`).

Failure: after a refresh, the visible `Sportsnet+ 09` row keeps the old event's cell until the user scrolls out of the
covered window. Emulator check 7 would fail intermittently.

Change, in commit 0 (`GuideModel.kt` is a commit-0 file): fold `event` (kind, start, end, title hash) into
`epgBasisOf`. State in §6.6 that the basis is the invalidation key.

---

## Should-fix

S1. **Hint scope misses real names.** §8.4 step 3 reads hints from "slot + group + title outside the two sides", and
§3.7 splits only on `": "`.
- DAZN UK names are `NHL.TV | Kings @ Canadiens`. The `NHL.TV` token lands inside side 1, so the positive hint is lost.
  `Kings @ Canadiens` and `Mammoth @ Jets` (cross-league nicknames) drop from CONFIRMED to LIKELY. That is 2 of the 7
  real Nov 23 games.
- The design's own worked example, `Sky Sports + 20: Basketball NBA Dallas Mavericks @ Denver Nuggets`, has its negative
  words inside side 1 too, so the rule as written does **not** reject it. A real name of this kind is
  `Sky Sports + 19: Basketball NBA Boston Celtics @ Washington Wizards`, PLACE + PLACE, ordered. It would be LIKELY for a
  Bruins @ Capitals game at the same time.

Change:
- Scan hints over the whole name.
- Split segments on `" | "` as well as `": "`.
- Reject a side whose tokens, after the team match, contain another league's nickname of that city (Celtics, Wizards,
  Mavericks).

S2. **PLACE by containment admits college and other-league sides.** The db yields `Southern Utah vs. Florida
International` (Utah + Florida), `SOUTHERN UTAH @ FLORIDA INTERNATIONAL (M)`, `Boston Celtics`, and also
`Boston College`, `Colorado College` and `Washington State` would count. Today only negative hints and the unordered
separator save these. Change: a side is PLACE only if its tokens **equal** the place, after dropping ranks, seeds and
brackets.

S3. **Year window is symmetric, so stale names jump forward.** Live stale names include `Sports | NCAA Baseball`
(−3,724 h, 155 days), `SuperLeague+` (−1,948 h), `National League` (−590 h) and `Dirtvision` (−1,162 h).
- On any re-parse after 183 days (a backfill, a parser bump, or a zone re-parse under B2's fix), "nearest year" picks
  **next** year.
- Search (§7, `event_end_min > now`) then lists a June baseball game as "upcoming".

Change: make the window asymmetric (for example −300 d / +90 d; future listings on the db reach +60 d, Fanatiz up to
Jan 23). Add a test.

S4. **The Watch slot disappears for NHL and NFL games.** `HubPanel.kt:273-275` shows Watch only for
`candidates.singleOrNull()` CONFIRMED. Team channels always add two CONFIRMED candidates. §8.6 notes this in passing,
but it silently removes the hub's primary action for the owner. It needs an explicit UX ruling: for example, Watch =
the favourite team's channel, else the home team's channel, when the only CONFIRMED candidates are the two team
channels plus name evidence.

S5. **Reminders on event cells are bound to a slot, not to the event.** The reminder key is
`(identity, rawStartMin)`, and firing does not re-check the content (`ReminderContracts.kt:8-24`, `ReminderPlanner` is
pure time). Providers reassign slots between refreshes, so a reminder can fire, and with auto-switch tune, onto a slot
that now carries another event.
- Change: at fire time, compare `channel.event` (title hash and start ± 30 min). On a mismatch, show the card with "this
  event may have moved" and no auto-switch.
- Also: the §6.4 claim "survives a rename" is true only for XTREAM and URL keys. The TVG rule puts the name into the key
  (`StableKeyDeriverImpl.kt:31`, `tvg:<id>|<name>`). An M3U provider with tvg-ids on event slots gets a new
  `stable_key` on every rename, and its reminders are orphaned. Say so and test it.

S6. **Catch-up gating goes beyond "Watch from start".** Past event cells on an archive channel still get:
- `MenuItem.WATCH` (`CellClass.PAST_ARCHIVE`, `GuideStateMachineImpl.kt:1666,1685`);
- the Start-over key (`:711-714`);
- the renderer's "catch-up available" (`GuideRendererImpl.kt:255,527`).

The owner's event groups have no `tv_archive` today, but other providers do. Gate all of them on `EVENT_NAME`. That
means §1's "renderer untouched" becomes one flag check, or §6.4 states that archive play is allowed.

S7. **The refresh cadence needs a reschedule hook, an owner and a cost measure.**
- `schedulePlaylist` runs only at app start and on a settings change (`RefreshScheduler.kt:168`;
  `PlaylistSettingsRepository.kt:225,230`). The first time `event_count` crosses 50, nothing reschedules until the next
  process start, which can be days on a Fire TV. Reschedule from the refresh path when the effective interval changes.
- `min(user, 6)` overrides a user who chose 24 h to save data. Make it explicit (an "Auto" interval value, or a toggle)
  rather than a note.
- The Settings page builder (`core/.../setup/pages/PlaylistPages.kt`) is in no WP's file list.
- Each refresh re-matches every renamed channel (`EpgMatchRepository.matchIncremental`, where `inputs_hash` includes the
  name). It can queue an EPG patch that re-parses the whole cached XMLTV: refresh_log id 24 → 25, a PATCH reading
  27 MB / 377k programmes. Measure that ×4 a day on the Fire TV Stick (§11.2) before choosing 6 h.

S8. **Backfill placement.** The call site is `TheBoxApplication` (`:app`, the DisplayNames precedent at line 144), not
`RefreshScheduler.onAppStart`, and `:app` is in no WP's list. The backfill must hold `importMutex` (B3) so it cannot
interleave with a merge.

S9. **Counts include 1,101 missing rows.** `channel.missing_since` is set on 999 FloSports and 100 Victory+ shape C
rows (stale Nov 19 names, gone since Nov 24). Live counts: **1,543 events** (A 456, B 430, C 419 + 44 placeholders,
D 126, E 112), and **162 untagged shape C** (Fanatiz 99, PDC 50, NBA LP 10, Setanta 2, NFL GP 1), not 1,162.
- The Eastern conclusion still holds on live data. Fanatiz Ligue 1 at 14:45 is 20:45 CET, Polish Ekstraklasa at
  11:55 / 14:10 is 17:55 / 20:10 CET, and PDC at 14:00 is the 19:00 UK evening session. NBA LP is the UTC exception.
- §2, §3.3 and §13 must be restated with live counts. The "1,049 of 1,162 FloSports and PDC" argument rests on deleted
  rows.
- Every query, as the lineup predicate already does, and the finish tallies (B2) must exclude `missing_since` rows.

S10. **No-generation and cache paths.**
- `info()` and `at()` return null before reaching any event logic when `reader == null`
  (`GuideWindowPagerImpl.kt:265,276`), and `build()` clears the window when no reader exists (`:422-425`). §7 promises
  event hits "even with no guide generation", so WP-3 must run the overlay and synthesise info without a reader.
- `InfoKey` (`:156`) and the state machine's loaded-info reuse (`GuideStateMachineImpl.kt:2574`) key on
  `identityHash × rawStart`. A renamed slot with the same start shows the old title. Add the event title hash to the
  key, or don't cache synthesised info.

S11. **Work in progress overlaps.** The working tree has uncommitted Sports Hub changes in `HubPanel.kt`,
`strings_sports_hub.xml`, `SportsScreen.kt` and `docs/design/m6-sports-hub.md`. Commit 0 and WP-4 own two of those
files. Land or stash that work before commit 0, and rebase the design's line references.

S12. **Shape B is M.D only.** It is right for the owner's US-style listings. A European provider writing D.M
(`(5.11 20:00 CET)`) parses as May 11 without error. Change: when both readings are valid and the zone is European,
take the one nearer the refresh time.

S13. **Window edge.** The owner's Nov 25 example `Montreal @ Colorado @ 25 Nov 09:30 OM ET` is +30 min after a 9:00 PM ET
Colorado puck drop (7 PM MT), exactly on the inclusive edge of [−90, +30]. Use +45 (no real-data hit lands between +30
and +45) and pin it with the owner's string as a fixture against a 9:00 PM ET game.

---

## Nice-to-have

N1. **Consider read-time derivation instead of stored columns.**
- Parse `name` inline when `GuideQueries.channels` builds a row. Right-anchored parsing fails fast at about 1 µs, and only
  loaded rows pay.
- For the matcher and search, keep one in-memory list of the playlist's events, built lazily per
  `(playlist_id, refresh_seq, VERSION)` on IO: a ~14k-name read and parse, about 0.1–0.2 s on a Fire TV Stick, once per
  refresh.
- Year "now" = `playlist.last_refresh_at`. This is identical for changed rows and irrelevant for unchanged ones within
  the ±window.
- This removes the v6 migration, `EventBackfill`, the version-stamp race (B3), the touched-rows tallies (B2), the
  `finish` re-parse and the index. The cost is one cold build per refresh on the first hub or search use. If the team
  keeps the stored design, B2 and B3 are mandatory.

N2. Drop the title → negative-id `HashMap` (§6.1 item 4). Negative ids are never cached (`GuideWindowPagerImpl.kt:507-520`),
so the map does nothing. Any constant ≤ −2 works.

N3. Search (§7) does `channel_fts MATCH … ORDER BY event_start_min`, which cannot use the FTS order and sorts every
token hit. "nhl" or "sportsnet" match thousands of NO EVENT slots. Then it re-filters on title in Kotlin. With ≤ 2,500
events, a Kotlin prefix scan over the cached event list (N1) is cheaper and exact.

N4. Add a per-playlist "Read events from channel names" switch (default on) beside open question 2's zone override.
It is cheap insurance against a provider whose format change parses into wrong times.

N5. `dataToMin`: computing the median from the open reader at match time, cached per `(generation, patchSeq)`, avoids
the `EpgGenerationInfo` and meta contract change and the publish/patch writes. Restrict it to `epg_channel` rows that
library channels resolve to (status 0); unmapped XMLTV channels say nothing about the user's guide.

N6. Editorial fixes: §0 says "five new `channel` columns" and §5.1 lists six. §2 "Shape A 456 … includes MLS `|`"
conflicts with the grammar's `sep`; this is fine, but say the `|` separator is shape A's.

---

## Answers to the brief's specific questions

- **False positives on 14k ordinary names:** 0 (live rows), and 0 invalid date or time parses. The risk lies in
  matching (S1, S2), not parsing.
- **Year at Dec/Jan:** correct for listings near now. Wrong for stale names on re-parse (S3). Refresh-time and read-time
  give the same result within the window.
- **DST:** `ZonedDateTime.of` rules are fine. AEST as a fixed +10 is justified by the Kayo cross-check; keep the test.
- **Learned zone:** gives the right answer for the owner on live data, but the stated evidence is wrong (S9), and as
  specified it is unstable (B2). A mixed-zone playlist still gets one zone; the LIKELY cap and the "time may be off" text
  are the only guard, which supports N4.
- **"OM":** fine as a one-letter table entry.
- **Cost per refresh:** parse cost is negligible and the import stays streaming. The real per-refresh cost is the
  re-match and patch (S7).
- **content_hash:** names are already in the hash, renames are already updates with an FTS rewrite, and `stable_key` is
  fixed on Xtream (not on M3U TVG keys, S5).
- **Synthetic ids:** no collision with real `epg_text` ids, which are ≥ 0, and no consumer of `cellTextId`. The real
  collision is the flag bit (B4).
- **Matcher on real data:** precise (11/11). Recall loss from hint scope (S1). Regional drop: no loss for the owner's
  Sportsnet regionals with EPG.
- **p95 ≤ 250 ms:** stage T is one ~14k-row scan per page session, and stage E is one indexed range. Both are fine.
- **Hidden and locked:** every new query uses `LINEUP_BASE` and `PARENTAL_RAW` (§4.5). Keep the tests.
- **CLAUDE.md:** streaming kept. User data stays keyed by `(playlist_id, stable_key)` (reminders, S5 caveat). No
  telemetry. No credentials in names. Error texts carry a reason and a next step (§9). The 6 h override needs a visible
  user choice (S7).

## Verdict

**Ready after listed changes.** B1 (measure the provider's BossSports EPG and fix the EPG-wins and search rules), B2
(tallies over the whole playlist, with hysteresis), B3 (only the backfill stamps the version, under `importMutex`), B4
(flag bit 27) and B5 (event in `epgBasisOf`) must be in the design before commit 0. B4 and B5 change commit-0 files. S1,
S2, S4 and S7 should be settled in the same revision, because they change contracts or the owner-visible behaviour. If
the lead adopts N1 (read-time derivation), B2 and B3 go away, and commit 0 loses the migration.
