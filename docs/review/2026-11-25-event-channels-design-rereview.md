# Re-review: event channels and team channels (revised `docs/design/m6-event-channels.md`)

Reviewer: Opus, 2026-11-25. Read-only. Code at `main` `384e159`. The in-flight Sports Hub change has landed as `9d9a009`
(`HubPanelPlan.PANEL_H = 864`, no hint row, no credit). Evidence comes from the code (file:line) and from the read-only
copies of the owner's `library.db` and `epg.db`. The throw-away scripts are in the session scratchpad under
`rereview/`:
- `glob.py`: the prefilter and the empty-slot grammar;
- `slots.py`: duplicate short names and alias collisions;
- `b1chk.py`: listing start deltas;
- `sim.py` and `sweep.py`: stage E with the revised rules, NHL and NFL.

No URL, id or bulk data is copied here.

**Verdict: CLEARED with the listed small edits.** Every first-round blocker is resolved. The read-time `EventIndex`
holds up against the real data: the prefilter misses no event, and stage E has no false positive. Section 3 lists 14
edits, E1–E14. None of them needs another round, but seven change commit-0 files or frozen strings and must go in before
commit 0: E1, E4, E5, E8, E10, E11 and E12.

---

## 1. First-round findings

| Id | Status | Note |
|---|---|---|
| Arch B1 | Resolved | Re-measured: the overlap deltas are 0 ×146, +15 ×3, −120, −60, +60, +75 and +120. The ±15 min search rule is wrong at its edge: three other-event listings start at exactly +15 (E4). |
| Arch B2 | Resolved | The index tallies every live row, with hysteresis. Write `event_count` and `event_zone` only on change (E9). |
| Arch B3 | Resolved | No per-row version. `display_name` goes through `DisplayNames` only, and the sink never stamps it (`RoomMergeSink.kt:206-208`). The parser-version link must be mechanical (E8). |
| Arch B4 | Resolved | Bit 27 is free (`GuideModel.kt:198-205`). |
| Arch B5 | Resolved | The basis is in `epgBasisOf`, and rows reload on `EventIndex.version`. |
| Arch S1, S2 | Resolved | Exact sides, whole-name hints and the `" \| "` split. The simulation gives 0 false positives (§2.4). |
| Arch S3, S12, S13 | Resolved | |
| Arch S4 / U-E-S2 | Resolved differently, adequately | Owner ruling: keep Channels (n), with the user's team focused. |
| Arch S5 / U-E-S9 | Partly | (a) "NO EVENT" slots are not in the index, so the "gone" case falls through to a normal fire that auto-switches. (b) A reminder on a real BossSports listing would be judged "moved" or "gone". Fix: E5. |
| Arch S6, S8, S10 | Resolved on paper | |
| Arch S7 / U-E-S6 | Resolved | The auto flag, the hook, `PlaylistPages.kt` in WP-2, and the Fire TV gate with a 12 h fallback. One stated limitation is missing (E13). |
| Arch S9 | Resolved | Verified: 13,109 live rows, 1,543 events, 1,672 prefilter rows. |
| Arch S11 | Resolved | Landed as `9d9a009`. |
| Arch N1–N6 | N1, N2, N3, N6 taken; N4 and N5 declined with reasons | The reasons are adequate. The guide end was re-measured at Nov 26 06:00Z over 4,565 real-listing channels, against 00:07Z over all channels. |
| U-E-B1 | Resolved, with two new strings over budget | `reason_team` is 52 characters and `reason_network_unnamed` is 85, against 47 at Largest (E10). |
| U-E-B2 | Resolved | Two side effects need fixing: matcher collapse (E3) and the prefix preview count (E7). |
| U-E-B3 | Resolved | At Largest, "Golden Knights team channel" loses "team channel" (E11). |
| U-E-B4 | Resolved | `beyond_guide_team` is 46–52 characters, over 45 at Largest. The UX abbreviation fallback was dropped (E11). |
| U-E-S1, S3, S4, S5, S7, S8 | Resolved | |
| U-E-S10 | Resolved by owner ruling, with a trap and a false claim | E1 and E2. |
| U-E-N1–N6 | Taken or left with reason | N5 leaves `sports_hub_on_tv_more` orphaned (E12). |

---

## 2. Attacking the read-time `EventIndex`

### 2.1 What checks out

- **The prefilter misses nothing.** Over the 13,109 live rows (`kind IN (0,1)`), `name GLOB '*[0-9]:[0-5][0-9]*'`
  returns exactly 1,672 rows: 1,543 events, 44 year-2098 placeholders and 85 unhandled names. Every parsed event is
  among them. The GLOB cannot use an index, so it is a full scan of `channel`. The words "one indexed read" in §5.1 are
  wrong, but the cost sits inside the ≤ 150 ms gate.
- **Empty slots are found without the index.** All three consumers read every row:
  - the short name: `slotOf` in `DisplayNames` and in `RoomMergeSink.bindContent`;
  - the hide switch: `isEmptySlot` in `RuleHiddenPass`, which scans every live row (`RuleHiddenPass.kt:158-172`);
  - guide rows: the `isEmptySlot` fallback in `GuideQueries.channels`.
- **No collision with prefix-stripped names.** 1,237 rows already have a prefix-stripped `display_name`. Of these, 62
  event rows (DAZN UK shape C) change from `(Dazn 002) | …` to `(UK) (Dazn 002)`. No ordinary row is touched. No short
  name equals a broadcaster alias, so stage A gains no new match through `h.displayName`
  (`GameChannelMatcherImpl.kt:142-143`). The EPG matcher reads `row.name` (`EpgMatchRepository.kt:201`), not the display
  name.
- **Re-running the backfill is safe.** `DisplayNames.recompute` is idempotent, runs under `ImportMutex` and writes only
  changed rows. The sink computes the same function on renames, so a merge and the backfill cannot disagree.
- **Sorting and search are unaffected.**
  - Sorting reads `norm_name` and `sort_index`, both from the raw name.
  - Channel search reads `channel_fts` on the raw name, which still tokenises "Sportsnet+ 09: …".
- **Unhiding works.** The pass recomputes in `finish` after every merge, and on the switch.
  - A hidden empty slot that gets an event name is unhidden at that refresh.
  - Turning the switch off with no user rules takes the `clearAll` path (`RuleHiddenPass.kt:118-121`).
  - A backup restore runs the pass after commit (m5 §5).
- **Index and rows stay consistent.** Every change converges:
  - During a merge, names commit in batches before `finish` bumps `refresh_seq`. A row reload in that gap attaches the
    previous event to a renamed row.
  - The rebuild after `finish` emits `version`, and the guide reloads again. The pager re-queries because the basis
    changed.
  - Process death costs one cold build on the first `current()`.
  - A guide opened before the warm-up builds the index itself (serialised).
- **Multi-playlist.** The read has no playlist filter, so disabled playlists are indexed too. Every consumer filters
  through `LINEUP_JOINS` (`p.enabled = 1`, `GuideQueries.kt:464`), so this does no harm. The zone is tallied per
  playlist.

### 2.2 Problems found

- **The hide switch reads as a user rule** (E1). Three surfaces show `rule_hidden` as "hidden by a rule":
  - The Hidden channels page (`ChannelsPagesImpl.kt:108-111`, `ChannelsRows.kt:229-231`). With the switch on, it lists
    about 3,700 rows as "Hidden by the rule “”: change it in Hide rules". That page has no such rule: a dead end.
  - The channel editor says "Hidden by a rule. Change it in Hide rules (advanced settings)" (`EditorMenus.kt:54,124`).
  - The summary reads "Rules: 0 · channels hidden: 3,743".
- **The favourite claim is false** (E2). §5.5 says "a favourite stays visible, because user data outranks rules". The
  code says otherwise: `ChannelDao.HIDDEN` puts `c.rule_hidden = 0` into every lineup, the Favourites lineup included
  (`GuideQueries.kt:348`, `ChannelDao.kt:295-300`). With the switch on, a favourited empty slot disappears from
  Favourites. The `RuleHiddenPassTest` case "a favourite stays visible" would fail.
- **`event_count` is written on every build** (E9). `playlist` is in `ROW_CONTENT_TABLES` (`GuideLibraryImpl.kt:250`), so
  each write reloads the guide rows once more. It is not a loop, but it is churn.

### 2.3 B1 rules against the data

- **The placeholder test.** Over the whole `epg.db`, `EpgFiller` flags only these titles:
  - "No Event Today" (19,276 BossSports, 11 elsewhere);
  - "Signing Off";
  - "SIGN OFF" (235, other sources);
  - about 180 "Next Event: …";
  - one "Upcoming - MLB Network Strike Zone" (14).

  No title is a bare "Offline", and no real programme title starts with "Upcoming". The false-positive risk is
  negligible.
- **The residual risk is the other way round.** Another provider's unknown placeholder ("TBA", "To be announced") counts
  as a real listing, so it hides the event. That is acceptable for v1: the guide then shows what the provider sent.
- **"Real listing wins" is right for 146 of 154.** It also hides the name's event in two cases where the real listing is
  an earlier event whose 4 h block runs over the name's start (−120 and −60). That is a rare cost, and the rule stays.
- **±15 min search de-duplication: wrong at its edge (E4).** Every same-event listing starts at Δ 0. Three different-event
  listings start at exactly +15 ("Maui Invitational" against "Woking vs. Worthing" on the same DAZN slot). An inclusive
  ±15 window drops those three event hits.
- **The guide-end median checks out.** Over the 4,565 channels with at least one real listing it is Nov 26 06:00Z. Over
  all channels it is 00:07Z. The quartiles of the real-listing channels are 00:19Z and 06:06Z.

### 2.4 Matcher re-simulation (revised rules, `sim.py`, `sweep.py`)

The games are VGK at EDM (Nov 25 01:00Z) and the 13 recorded NFL games of Nov 20–24.

| Game | Hits | Notes |
|---|---|---|
| VGK at EDM | 4 | `NHL 01` (B) CONFIRMED Δ 0; `NHL 01` (NHL on TNT) CONFIRMED −30; `MAX 002` CONFIRMED −30; `Sportsnet+ 09: Vegas @ Edmonton` cities-only CONFIRMED Δ 0. No false hit. |
| MIN at SF | 2 | DAZN UK English and Spanish, CONFIRMED Δ 0. |
| CIN at WSH | 3 | DAZN UK 026 (Manningcast, −15), 028 and 029, all CONFIRMED. `NFL GP 01` is missed: its zone is ASSUMED Eastern while the real zone is UTC (the documented miss). The `ESPN UNLTD` 5:00 AM replays are outside the window, as they should be. |
| Other NFL | 0 | Those slots had rotated by the refresh. |

**The time-free sweep** grades every event name against every pair of teams.
- NHL: 14 grades, all real NHL games.
- NFL: 11 grades. Two of them are not NFL games:
  - `ESPN UNLTD 203: Football: Jaguars vs. Giants` grades LIKELY (cross-league "Giants");
  - `Sportsnet+ 10: Dallas @ Denver` grades **cities-only CONFIRMED**. That name is an NBA game (Mavericks at Nuggets).

The clean-board upgrade only sees the NFL board. The arena argument holds for NHL against NBA, because they share
arenas. It does not hold for the NFL. Fix: E6.

---

## 3. Edits for the lead (apply without another round)

**E1. The hide switch has its own reason (§5.5, §9.3, §12).**
- Add these strings in commit 0:

  ```xml
  <string name="backup_hidden_no_event">Hidden because it has no event. Turn off “Hide channels with no event” in this playlist\'s settings.</string>
  <string name="editor_d_no_event">Hidden because it has no event. Turn off “Hide channels with no event” in this playlist\'s settings.</string>
  ```

- Where a row has `rule_hidden = 1`, the playlist has `hide_empty_events = 1` and `EventNameParser.isEmptySlot(name)` is
  true, these surfaces show the new text and link to the playlist page instead of Hide rules:
  - the Hidden channels page (`ChannelsPagesImpl.kt`, `ChannelsRows.hiddenItem`);
  - the editor (`ChannelEditorRepositoryImpl.kt`, which already resolves the rule per row at line 148, and
    `EditorMenus.kt`).
- The Hide rules summary counts only channels a user rule hides.
- Add `data/.../rules/ChannelsPagesImpl.kt`, `core/.../settings/tree/ChannelsRows.kt`,
  `data/.../editor/ChannelEditorRepositoryImpl.kt` and `core/.../editor/EditorMenus.kt` to WP-2.
- Add a test: switch on, then the Hidden page row text, then the link target.

**E2. Favourites (§5.5, §11.1).**
- Replace the Favourites bullet with: "Favourites: a favourite empty slot is hidden too (hide rules apply to every
  lineup, `ChannelDao.HIDDEN`). It comes back with its next event."
- Change the `RuleHiddenPassTest` item to "a favourite empty slot is hidden and returns with an event".

**E3. Matcher collapse (§8.6, `GameChannelMatcherImpl.kt:317`).** Today the key is `residue(displayName)`. With short
names, `NHL 01` in `Sports | NHL` and `NHL 01` in `Sports | NHL (2)` (69 duplicate slot names in the owner's data)
collapse into one row. Their events differ: 8:00 PM against "NHL on TNT" at 7:30 PM. Add to §8.6 Collapse: "A
candidate whose only evidence is an event name collapses on `e:` + folded event title + `|` + start minute, never on the
short name. Same-event duplicates fold into "Also in <group>"; different events stay separate rows, and line 3 shows the
group." Team channels keep the residue key.

**E4. Search de-duplication (§7 step 3, Appendix A).**
- Set `EVENT_SEARCH_DEDUP_MIN = 5`.
- Replace the reason with: "every same-event listing starts at the name's minute; three different-event listings start
  at exactly +15".
- Add a test with a listing at +15 that keeps both hits.

**E5. Reminders (§6.6).** Replace the first sentence and step 1 with:

> At fire time, `ReminderSchedulerImpl` resolves the reminder's channel through `GuideQueries.channels`. The lookup is
> suspend and runs outside `synchronized(lock)`. The check applies when that channel's `event` is not null (EVENT, or
> EMPTY through the `isEmptySlot` fallback).
>
> 0. **Listing.** If the guide has a programme on the channel at the reminder's raw start with the same folded title,
>    fire as today.
> 1. **Same event.** …

- Add to the limitation paragraph: "The NAME_GROUP rule (`ng:<name>|<group>`) also puts the name in the key."

**E6. Cities-only CONFIRMED is NHL only (§8.4 table, row 5).** Add "**and the league is the NHL**". For the NFL, an
ordered cities-only name with |Δ| ≤ 15 and a clean board is LIKELY. The reason: NFL stadiums are not shared with NBA
arenas, and `Sportsnet+ 10: Dallas @ Denver` (NBA) would otherwise confirm a DAL at DEN NFL game. Add that name as an
`EventGameRuleTest` fixture.

**E7. Prefix preview (§5.3).** `DisplayNames.previewCount` and `ChannelNamePrefixImpl.countChanged` skip names where
`EventNameParser.slotOf(name) != null`. Otherwise the playlist page's "changes N of M names" adds about 5,300 for every
rule, Off included. Add `data/.../guide/DisplayNames.kt` to WP-2.

**E8. Version link (§3.1, commit 0).** In `ChannelNamePrefix.kt` write
`const val RULE_VERSION = 1 + EventNameParser.VERSION` (2 today). A parser bump then re-runs the backfill with no manual
step. `ChannelNamePrefix.kt` moves into commit 0.

**E9. Index writes (§5.2 Build).** Write `event_count` and `event_zone` only when they change. `playlist` is a
row-content table, so each write reloads the guide.

**E10. Sheet strings over budget (§9.1).**
- `sports_hub_reason_team`: `Team channel: always shows %1$s games` (47 characters with "Golden Knights").
- `sports_hub_reason_network_unnamed`: `A national broadcaster carries this game.` (41 characters).

**E11. Abbreviation fallback (§9.1, §8.5).** For `on_tv_team`, `on_tv_team_at` and `beyond_guide_team`: when the
filled text plus the tail exceeds the hub budget (55 characters at Default, 45 at Largest), the caller uses the team
abbreviations, as in "On the VGK and CBJ team channels" (32) and "On TV at 10:30 PM: VGK team channel · +8 more" (45).
Otherwise Largest shows "On TV at 10:30 PM: Golden Knigh…", which reads as a team again (U-E-B3). Add the case to the
`HubPanel` line tests.

**E12. Hub strings left behind (§9.1).**
- Add `sports_hub_on_tv_more` to the deletion list: `sports_hub_more` replaces it.
- State the new `shortReason` fallback once `reason_short_local` is gone (`HubPanel.kt:629`): "national broadcaster".

**E13. 6 h default (§10).** Add: "A playlist whose 24 h was chosen before v6 cannot be told from the default, so it
becomes auto. The row shows 'Every 6 hours' with the recommendation, and picking 24 h again keeps it."

**E14. Commit-0 completeness (§4.4, §12).** As written, commit 0 does not compile the other modules, or breaks their
tests:
- **`GameChannelMatcher.match` with a fourth parameter.** `GameChannelMatcherImpl.kt:94` stops overriding it. Keep the
  three-argument method and add
  `suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess, board: List<GameSnapshot>): List<ChannelCandidate> = match(game, nowMs, access)`.
  WP-4 overrides it.
- **`PlaylistAdmin.setHideEmptyEvents`.** Give it a default body `{}`. Give the new `PlaylistDetail` fields defaults
  (`effectiveIntervalH` declared after `refreshIntervalH`, defaulting to it). `PlaylistSettingsRepository` and four
  test fakes then still compile.
- **`GuideQueries`** gains `private val events: EventIndex? = null`. It is constructed directly in two `:data` tests.
- **Gate.** Add `:data:compileDebugUnitTestKotlin :ui:compileDebugUnitTestKotlin` to commit 0's gate; the smoke build
  compiles neither.
- **Dependencies.** WP-4 depends on WP-2 (the `teamChannelCandidates` body). WP-3 may touch `EpgReaderImpl.kt` (the
  title check in §6.1): give it to WP-3 by name.

---

## 4. File ownership after the edits

- **Commit 0 only:** the §12 list, plus `core/.../text/ChannelNamePrefix.kt` (E8) and the E1 strings in
  `strings_backup.xml` and `strings_editor.xml`.
- **WP-2 adds** `ChannelsPagesImpl.kt`, `ChannelsRows.kt`, `ChannelEditorRepositoryImpl.kt`, `EditorMenus.kt` (E1) and
  `DisplayNames.kt` (E7). It loses `ChannelNamePrefix.kt` to commit 0; `ChannelNamePrefixImpl.kt` stays.
- **WP-3 adds** `EpgReaderImpl.kt` if its title check needs it.
- **WP-4 also owns** `ui/src/test/resources/golden/sports-hashes.txt`, because the two-part line and line 3 change the
  goldens.
- **Claimed by two packages:** none after these edits. `GameChannelMatcherImpl.kt` is untouched by commit 0 under E14's
  overload.
- **Claimed by none:** none after these edits.

**Conflicts with the landed hub change:** none.
- The design targets the 864 px panel and specifies no footer hints.
- WP-4's `SportsViewModel` edits (`startMatch`, `board = repo.day`) sit beside the b37512c Today fix and leave it alone.
