# Event channels: code review, data side (parser, index, storage, refresh)

Branch `events` at `ce45e22`, `git diff main...HEAD`. Scope: `core/.../events/**`, `TeamNames`, `TeamChannelRule`,
`EventGameRule`, `ChannelHorizon`, `EventIndexImpl`, the name prefix and display names, the hide pass and its reason
pages, the editor reason, playlist settings, `RefreshScheduler`, `RefreshRepository`, `MergePlanner`, the v6 migration and
schema, `PlaylistDao`, `GuideQueries` (event and team queries), `BackupRestorer`, `EpgStoreImpl` / `ImportSession`
(`data_to_min`), the app warm-up, and the fake panel's event catalog. Reviewer: Opus, read-only.

**Verdict: ship after the Major fix (or with it recorded as a known issue). No Blockers.** The v5→v6 migration, the backup
round trip, the hide switch's reversibility, the learned-zone hysteresis and the auto interval are correct as written.
The parser behaved correctly on every edge I ran (OM→PM, D.M vs M.D, year rollover both ways, DST gap and overlap,
AEST, midnight). The issues below are a search noise bug, latency on the guide's first block, and smaller reason, test and
performance gaps.

Counts: Blocker 0, Major 1, Minor 8.

---

## Major

### M1. A nickname search pulls in every event with a word that *starts with* a team's place ("kings" → Lakers, La Liga, Lazio)

- **Where:** `data/.../guide/GuideSearchImpl.kt:297-325` (`EventSearchTerms.of` / `matches`), fed by `TeamNames.places`
  (`core/.../sports/TeamNames.kt:123,128-129,168-170,176-177`). The search code is the other reviewer's file, but the
  data comes from my scope and question 3 asks about it.
- **Failure:** a nickname run gets each team place as an extra choice, and `matches` accepts a choice when each of its
  words is a **prefix** of any title or subtitle word.
  - "kings" adds `["los","angeles"]` and `["la"]`. `["la"]` prefixes `lakers`, `la` (La Liga), `lazio`, `laval`,
    `lafayette`, `las` (Las Vegas) and `latvia`, so every current event with such a word becomes a hit.
  - "rams" and "chargers" add `la` too. "rangers", "islanders", "giants" and "jets" add `ny`, which prefixes `nyc` and
    `nyu`.
  - "bruins" adds `boston`, so the query also finds "Boston University", "Boston College", "Boston Celtics" and
    "Boston Red Sox".
- **Confirmed by:** reading the code. `"lakers".startsWith("la")` is true, and `matches` has no other guard.
- **Fix:** see question 3 below. In short, a place choice must **equal a whole matchup side** (`EventMatchup.of(title)`
  then `TeamNames.sideMatch(side, team) == PLACE`), never prefix a word.

## Minor

### m1. Ordinary channel names that end in "N |", "N:" or "N | Offline" become empty slots: they get short names, and the switch hides them

- **Where:** `core/.../events/EventNameParser.kt:151-172` (`emptyForm`) and `:183-217` (`validSlot` / `labelNum`).
- **Confirmed by:** running the parser in a temporary unit test, since deleted.
  - `"Sky Sports 1 |"` gives `Empty("Sky Sports 1")`.
  - `"beIN Sports 3:"` gives `Empty("beIN Sports 3")`.
  - `"BBC 1 | Offline"` gives `Empty("BBC 1")`.
  - `"Movies 24/7 | Offline"` gives `Empty("Movies 24/7")`.
- **Effect:**
  - `ChannelNamePrefixImpl.strip` stores the slot as `display_name` under **every** prefix rule, Off included. "BBC 1 |
    Offline" therefore shows as "BBC 1", and the provider's Offline marker disappears from a non-event channel.
  - With the switch on, these channels are hidden.
- **How common:** rare (it needs 1–3 digits just before a trailing separator). Hiding "… | Offline" is arguably wanted.
- **Fix (small):** keep `isEmptySlot` as it is, but make `slotOf` return null for the `Offline` form. "Offline" is a
  provider status, not an event slot, so the full name stays visible. A playlist-level guard, such as applying slot short
  names only to groups that hold at least one parsed event, would remove the rest but is not worth it now.

### m2. Readers block on a full build whenever the key changes and nobody calls `rebuild()`

- **Where:** `data/.../events/EventIndexImpl.kt:89-100` and the class KDoc at `:54-56`.
- **Failure:** the KDoc says only a reader with no snapshot waits. The code also blocks any reader that finds a **stale
  key while no build runs**. That happens after:
  - a NOT_MODIFIED refresh (`RefreshRepository.kt:131-139` writes `last_refresh_at` but never calls `afterMerge`);
  - enabling or disabling a playlist.

  The next guide block then waits for a whole build: 0.5–0.9 s measured on the emulator, more on a Fire TV Stick. The
  refresh path itself is fine: `rebuild()` holds the mutex before the 500 ms library debounce fires, so the guide gets
  the old snapshot and reloads on the version tick.
- **Confirmed by:** reading the code.
- **Fix:**
  - Call `events?.rebuild()` in the NOT_MODIFIED branch too.
  - Change `current()` so that when `have != null` and the key is stale, it starts the build in the background (an
    application scope) and returns `have`. Only the `have == null` case waits. The version tick already reloads the rows
    (`GuideLibraryImpl.eventTicks`).

### m3. The index build writes `playlist` rows while holding its mutex

- **Where:** `EventIndexImpl.kt:143` and `:182`.
- **Failure:** `setEventZone` and `setEventCount` run inside `build`, under `mutex`. If a merge or the hide pass
  (`RoomMergeSink.finish` → `RuleHiddenPass.run`, one write transaction over the playlist) holds the write connection,
  the build waits for it. Every reader with no snapshot, which is the first guide block, then waits too. This bites on
  the first start after the upgrade, when `event_count` goes from 0 to N, so the write always happens, and
  `update_on_start` may be merging at the same time.
- **Confirmed by:** reading. I am not sure how long the hide-pass transaction holds the connection on 14k rows. It may
  be short.
- **Fix:** publish `snapshot` and `version` first, then do the two writes. The key already uses `storedZoneText`, so
  the order does not change the next comparison.

### m4. A row that a user rule and the switch both hide is reported as "no event", and the Hide rules count under-counts it

- **Where:**
  - `RuleHiddenPass.kt:117-148` (`hiddenByNoEvent`, `countHiddenByNoEvent`; the latter excludes group-rule rows only);
  - `ChannelsPagesImpl.kt:131-149` (`noEventKeys`);
  - `ChannelEditorRepositoryImpl.kt:150-161`.
- **Failure:** a user who had a channel rule "NO EVENT" before v6, a likely case on the owner's playlist (1,955 such
  rows), turns the switch on.
  - The Hidden channels page and the editor then say "Has no event … playlist settings".
  - The Hide rules summary subtracts those rows from what the user rule hides, so the rule looks like it hides about 0.
  - Turning the switch off leaves the rows hidden, now under a different reason.
- **Data:** none is lost. The pass recomputes `rule_hidden` from scratch, so turning the switch off restores exactly the
  rows only it hid, and `channel_prefs.hidden` is never touched. Only the reason is wrong.
- **Confirmed by:** reading.
- **Fix:** mark `noEvent` only when no enabled user channel rule matches the name.
  - The editor already has `matchers`: test them first.
  - In `countHiddenByNoEvent` and `noEventKeys`, load the channel matchers (`RuleHiddenPass.loadMatchers`) and skip rows
    that one matches.

### m5. The app warm-up runs after the display-name backfill, so the first guide block always builds the index cold

- **Where:** `app/.../TheBoxApplication.kt:143-164`.
- **Failure:** the warm-up waits for `epgRecovery`, then `DISPLAY_NAMES_DELAY_MS`, then the backfill. On the first v6
  start the backfill rewrites every row, because `RULE_VERSION` went from 1 to 2. The guide's first block therefore
  always pays the build (question 1).
- **Second effect:** the 6 h reschedule waits for the backfill too. That is harmless, but later than intended.
- **Confirmed by:** reading. The build cost is the lead's measurement.
- **Fix:** start the warm-up in its own `appScope.launch` right away. The index reads `c.name` only and does not depend
  on `display_name` or the guide.

### m6. A cold build reloads the guide rows twice

- **Where:** `GuideLibraryImpl.kt:220-224` (`version.drop(1)`).
- **Failure:** the flow is subscribed while `version` is 0. The first block builds version 1, so the tick reloads rows
  that were just loaded with that same snapshot.
- **Effect:** one wasted reload, harmless today. With the non-blocking option of question 1 this tick becomes the
  needed reload. Then, though, `drop(1)` can swallow it if the subscription happens after the build, while the rows were
  loaded from a missing snapshot. See question 1 for the guard.
- **Confirmed by:** reading.

### m7. `event_count` also counts stale names, so the 6 h default can apply to a playlist with no current events

- **Where:** `EventIndexImpl.kt:178-183`.
- **Failure:** every `ParsedName.Event` counts, including names up to 300 days old (§3.4 keeps stale names in the past).
  A playlist with 50 or more never-updated event names and no current ones gets the 6 h default.
- **How likely:** low.
- **Confirmed by:** reading.
- **Fix:** count entries with `endMin > nowMin − 1 day` at build time, or leave it and note it in §10.

### m8. Test gaps where broken code would still pass

1. No test that the user's own hide (`channel_prefs.hidden`) survives turning the switch on and off. Today the pass
   never touches it, but nothing pins that.
2. No test for a row matched by a user rule **and** the switch: the reason (m4) and staying hidden after the switch goes
   off.
3. No test for a guide generation without `data_to_min` (the fallback of question 2).
4. The warm-up's `rescheduleIfChanged` loop, the only path that moves an upgraded 24 h auto playlist to 6 h before its
   next refresh, is untested. It sits in `:app`, so a small extracted function in `:data` would make it testable.
5. `EventSearchTerms` has no negative tests (a place that prefixes another word, as in M1).

Perf note, not measured, so unsure: on every patch, `FillerTexts.scanRest` (`ImportSession.kt:372-379`) reads and
classifies **every** `epg_text` row (one `EventText.fold` each, with NFD for non-ASCII). On a large 7-day guide that may
cost seconds on a Fire TV inside a patch the design budgets at about 7 s. If it is slow, classify only the text ids of
`programme` rows of channels with `last_end_min` near the median, or persist the filler ids at build time.

---

## The three open questions

### 1. The first guide block waits for the cold index build

**Recommendation: do both, in this order.**

**(a) Now, a one-line change (m5).** Start the warm-up first, in its own `appScope.launch` without the backfill
dependency. It overlaps app start and the first frame. On most starts it should finish before or soon after the guide
asks, which removes most of the wait. Combine it with m2 so stale-key readers never block.

**(b) Contract change, guide rows only.**

- **`EventIndex`** gains:
  - `fun peek(): EventSnapshot?`: the last published snapshot, possibly stale, or null; no I/O, no suspension.
  - `fun prefetch()`: starts a build on the application scope when none is running and the snapshot is missing or
    stale; returns at once.

  Both get default bodies (`= null`, `{}`) so the fakes compile. `current()` and `rebuild()` are unchanged.
- **`GuideQueries.channels(rowIds, from, to, waitForEvents: Boolean = true)`.** With `false` it uses
  `events.peek().also { if (it == null) events.prefetch() }`, waiting at most a short bound if you want one
  (`withTimeoutOrNull(150) { events.current() } ?: events.peek()`). The empty-slot fallback in `eventOf` still marks
  EMPTY rows without the index.
- **Callers that change:** only the guide's row loader in `GuideLibraryImpl`, which passes `false`.
- **Callers that must keep blocking** (`true`, the default):
  - `GameChannelMatcherImpl` (stage E);
  - `ReminderSchedulerImpl` / `ReminderEventRule`, where a missing snapshot would wrongly read as "event gone";
  - `GuideSearchImpl.searchEvents`.
- **Reload guard.** Replace `version.drop(1)` in `GuideLibraryImpl.eventTicks` with a comparison against the version
  the rows were served with: `GuideQueries` records `lastServedVersion` (0 when `peek()` was null), and the tick fires
  when `version.value != lastServedVersion`. Otherwise a build that finishes before the guide subscribes to `rowContent`
  is swallowed, and the rows keep showing no events until the next refresh.

The cost is one redraw of the visible rows about 1 s after a cold start, with event cells appearing. The guide already
redraws on every refresh for the same reason (§6.5).

### 2. `guideEndMs()` on a generation built before `data_to_min`

**Recommendation: compute a conservative value in memory when the generation is opened. Do not store it, and do not use
the window's end.**

- In `EpgStoreImpl.readInfo` (`:705-719`), use `dataToMin = m[DATA_TO_MIN]?.toIntOrNull() ?: legacyDataTo(c,
  windowToMin)`, where `legacyDataTo` is the **median `last_end_min` over `epg_channel WHERE programme_count > 0 AND
  last_end_min IS NOT NULL`, capped at `windowToMin`**. That is the same median as `FillerTexts.dataToMin`, without the
  placeholder exclusion.
  - It is one pass over `epg_channel` (thousands of rows) on the read-only connection: milliseconds.
  - `readInfo` runs on open and on `refreshInfo`, so it is computed once per generation or patch.
- **Why this direction is safe:** the placeholder exclusion only removes channels whose blocks stop **earlier** than
  the real listings (§2.4, BossSports). Including them can only pull the median earlier. The fallback therefore errs
  toward "beyond the guide" and never claims guide coverage the data lacks.
- **The exact value arrives by itself:** the next patch recomputes over the whole generation (`scanRest`) and writes
  `data_to_min`, and so does the next build.
- **Rejected:**
  - Writing `data_to_min` lazily into the live generation. Readers open it read-only, and writing meta outside the
    import path breaks the "built, then swapped atomically" rule for `epg.db`.
  - Treating a missing value as null, which leaves only the 12 h floor. That greys tomorrow's Channels chip for every
    upgrader who has a real 7-day guide.
- **Test to add:** a generation with the `data_to_min` meta row deleted gives the plain median, not `windowToMin`.

### 3. "bruins" also finds Boston University and Boston College

**Is the expansion sound? Only half.**

- The tables in `TeamNames` are correct, and `sideMatch` is sound: equality on folded tokens, never containment
  (arch S2).
- The search expansion is unsound. It turns a place into a **word-prefix** test anywhere in the title or subtitle, which
  is containment, the very rule arch S2 rejects for the hub. Short places make it much worse than "Boston": `la` matches
  Lakers, La Liga, Lazio and Las Vegas, and `ny` matches NYC and NYU (M1).

**Smallest rule that removes the noise and keeps city-only names: a place choice matches only when it equals a whole
matchup side of the event title.**

- Keep the nickname or full-name run as it is: a word prefix anywhere ("bruins" still finds "UCLA Bruins @ USC", a
  real Bruins team).
- Mark each place choice as side-only. For a side-only choice, `matches` computes `EventMatchup.of(title)` once per
  event and accepts when `m.first` or `m.second` equals the place's tokens. Use `TeamNames.tokens` on both sides, so
  "St. Louis" and "Montréal" fold the same way.
- **Results:**
  - "habs" finds `Montreal @ Colorado`: the side `[montreal]` equals the place.
  - "avalanche" finds the same event through `[colorado]`.
  - "bruins" no longer finds `Boston University vs. Maine` (side `[boston, university]`) or `Boston College @ Notre Dame`.
  - "kings" no longer finds `Lakers @ Celtics` or `La Liga: Barcelona - Girona`, because no side equals `[la]` or
    `[los, angeles]`.
- **What is left:** a city-only name from another sport, such as an NBA `Boston @ Toronto`. To remove that too (a
  second, optional rule), apply `EventGameRule`'s negative hints (`nba`, `ncaa`, `college`, `basketball`, …, read over
  `EventEntry.name` and `group`) to place-only hits. Expose `hintsOf(...).negative(league)` as an `internal` helper
  rather than copying the set.
- **Cost:** `EventMatchup.of` runs only for events that already passed the cheaper word test, at most about 1,500 per
  query.

---

## Checked and correct

- **Parser.** Results from running the production parser in a temporary unit test, since deleted:

  | Input | Result |
  |---|---|
  | "09:30 OM ET" on Nov 25 | 21:30 ET (02:30Z the next day) |
  | "(12.31 11:00 PM ET)" read on Jan 1 | the past Dec 31 |
  | "(1.1 1:00 AM ET)" read on Dec 30 | the next Jan 1 |
  | "31 Dec 11:30 PM PT" read at 12:00Z Dec 31 | 07:30Z Jan 1 (the year is taken in the event's zone) |
  | "(24.11 20:00 UK)" | D.M, 20:00 London |
  | "(11.12 20:00 UK)" | Nov 12, the nearer reading, as §3.2 says |
  | DST overlap and gap | the later offset; moved forward |
  | AEST | fixed +10 |
  | "0:30 AM" | None |
  | "12:30 AM" | 00:30 |
  | "@ … PST (English)" | None |
  | "2.29" | None from `parse`, but a slot from `slotOf` (§17.1) |

  - There is no regex. Every scan is linear, using `lastIndexOf` plus bounded cursors, so there is no backtracking risk.
    The 400-character cap and the try/catch on all three entry points match "never throws".
- **Migration 5→6.** Five `ADD COLUMN`s whose defaults match the entity `defaultValue`s, and `auto = 0` wherever
  `refresh_interval_h != 24`. Manual (0) stays manual. Nothing is rebuilt and no data moves.
- **Backup.**
  - `hide_empty_events` and `refresh_interval_auto` are carried. The derived zone and count stay home.
  - A pre-v6 backup gets the migration's rule (`withLegacyIntervalAuto`).
  - `afterRestore` re-runs the hide pass for affected playlists.
- **Auto interval.**
  - `effectiveIntervalH` never overrides 0, and `setRefreshInterval` is the only writer of `refresh_interval_h`, and
    it clears `auto`. New rows start at 24 h auto.
  - `rescheduleIfChanged` never cancels, so a running periodic worker cannot cancel itself, and it compares the
    enqueued period before re-enqueueing with UPDATE.
- **Hide switch.**
  - The pass recomputes `rule_hidden` from user rules plus the built-in test. Turning the switch off re-derives the
    flag from user rules alone, so it restores exactly the rows only the switch hid and never touches
    `channel_prefs.hidden`.
  - Missing rows are skipped.
  - The built-in test is checked first and is outside the regex budget.
- **Learned zone.** `chooseZone` follows §3.3: a stored zone is replaced only when a different majority has at least
  20 names and at least twice the stored zone's current count. An empty tally keeps the stored zone. The panel's zone is
  never stored and never used once a zone is learned. The tally covers all live rows.
- **Index concurrency.**
  - Snapshots are immutable and published through a volatile field.
  - Builds are serialised, and the version only rises.
  - The key after the build includes our own zone write, so there is no second build.
  - A deleted or disabled playlist leaves the key, so the next `current()` rebuilds. Until then the stale snapshot's
    ids are filtered by each consumer's own lineup query.
  - Memory holds only the GLOB-matched rows (about 1,700 on the owner's 13k playlist).
- **Logs.** The new log lines carry counts, ids and exception class names only. No names, URLs or credentials.
- **CLAUDE.md rules.**
  - User data stays keyed by `(playlist_id, stable_key)`. The index is keyed by row id but holds only derived,
    rebuildable data.
  - `library.db` stays Room, with no change to `epg.db`'s generation and swap model.
  - No whole-playlist loads.

## Not reviewed

- The guide overlay, renderer and state machine, search ranking and dedup (beyond question 3), reminders,
  `GameChannelMatcherImpl` stages T and E and ranking, and all UI. These belong to the second reviewer.
- The `teamChannelCandidates` placeholder binding (`?1` comes from `bindAccess`). I relied on `GuideQueriesTeamTest`
  rather than reading `PARENTAL_RAW`.
- Device cost: I measured nothing on a Fire TV. Build time, `scanRest` and the double reload are reasoned, not timed.
- `tools/fakepanel/events.py` and `catalog.py`: skimmed only (the zones use `zoneinfo`, and the names are generated
  around the featured game). Its tests were not run.
- The full gradle gate was not run, as instructed. One temporary `:core` unit test exercised the parser and was deleted.
