# Event channels: code review, consumers and UI (2026-11-25)

Scope: `git diff main...HEAD` on `events` (HEAD `ce45e22`), limited to the guide overlay and panel, search, reminders, the
Sports Hub matcher stages T/E with ranking and the cap, the hub panel, the sheet and the view model, the settings rows,
strings and the tests for these. `EventIndex` / `EventNameParser` are treated as given contracts. I reviewed by reading
the code, by looking at the emulator screenshots (`ev-emu/e2-*` to `e6-*`), and by running one temporary unit test
(deleted afterwards).

**Verdict: ship after the three Major fixes.** No Blocker. Most of the design is in place and correct: the overlay's clip
and insert, catch-up gated on `EVENT_NAME` in both the renderer and the state machine, the reminder move and gone paths,
team channels first and never capped, and the grey chip state. Two Majors are in search (wrong results for team names,
and a possible crash). The third is accessibility of the grey chip.

Counts: Blocker 0 · Major 3 · Minor 12.

---

## Major

### M1. Searching a team name also lists other sports' games from the same city (or any word starting "la" / "ny")
- **Where:** `data/.../guide/GuideSearchImpl.kt:282-335` (`EventSearchTerms.of` / `matches`).
- **What goes wrong:** a team's places are added as alternatives, and every alternative is matched as a word **prefix**
  against any event. So:
  - "bruins" lists `Basketball: Brown vs. Boston U` and other Boston college games. This shows in the emulator,
    `e3-h.png`: the third programme card.
  - "kings", "rams" and "chargers" add the place `LA`, so they match every event with a word starting "la": Lakers,
    La Liga, Las Vegas Raiders, LAFC, Laval.
  - "rangers", "islanders", "giants" and "jets" add `NY`, which matches NYCFC.
  - Celtics, Red Sox, BC and BU games show for "bruins", and the same holds for every shared city.
- **Confirmed:** by running. A temporary `EventSearchTerms` test passed all five of these cases:
  - kings → "Lakers vs. Warriors"
  - kings → "La Liga: Real Madrid vs. Barcelona"
  - rams → "Las Vegas Raiders @ Denver Broncos"
  - bruins → "Basketball: Brown vs. Boston U"
  - rangers → "NYCFC vs. Orlando"

  Also seen on the emulator (`e3-h`).
- **Fix:**
  1. Match a **place** alternative only as whole words, never as a prefix.
  2. Accept it only when the event's matchup (`EventMatchup.of(title)`) has a side that
     `TeamNames.sideMatch(side, team)` grades for that team, and the name and group carry no negative hint. Reuse
     `EventGameRule`'s hint sets: make `hintsOf` / `NEGATIVE` internal-public, or add `EventGameRule.otherSport(name, group, league)`.
  3. Keep plain prefix matching for the words the user typed.
  4. Add negative tests for the five cases above. Today `GuideSearchImplEventTest` pins only the positive case
     (oilers → Edmonton).

### M2. Possible crash in Search: duplicate `LazyRow` key when a channel has a guide shift
- **Where:** `data/.../guide/GuideSearchImpl.kt:145-152` (`merge`) and `ui/.../search/SearchScreen.kt:164`
  (`key = "${identityHash}:${rawStartMin}"`).
- **What goes wrong:** the ±5 min de-duplication compares the guide hit's **display** start (`rawStartMin + epgShiftMin`)
  with the event's start. The list key uses the **raw** start.
  - Take a channel with a 60-minute shift that has a guide listing at raw 7:00 PM (shown at 8:00 PM) and an event from
    its name at 7:00 PM. Both match the query, for example "oilers".
  - The two starts are 60 min apart in display time, so the event is kept.
  - Both hits then have the key `identity:<7:00 PM>`.
  - Compose throws "Key … was already used" and the Search screen crashes.
- **How likely:** it needs a non-zero shift on an event channel whose listing starts on the event's own minute. That is
  rare, but whole-hour shifts and whole-hour starts make it plausible.
- **Confirmed:** by reading; not run.
- **Fix (either one, ideally both):**
  - In `merge`, also drop an event when a kept guide hit on the same channel has `rawStartMin == event.startMin`.
  - Make the key collision-proof. Add `fromName: Boolean` to `ProgrammeHit` (default false), or key on
    `"$identityHash:$rawStartMin:${info.titleHash}"`.

### M3. The grey Channels chip does not say why to a D-pad or TalkBack user
- **Where:** `ui/.../sports/HubPanel.kt:1190-1195` (the slot) and `:921-924` (the panel).
- **What goes wrong:**
  - The disabled slot cannot take focus (`canFocus = slot.enabled && …`). Its only description is "Channels".
  - The reason is set as the **panel container's** `contentDescription`. The container is not focusable either.
  - On a TV, accessibility focus follows D-pad focus: Follow → Matchup.
  - So a TalkBack or VoiceView user hears "Follow", then "Matchup". Neither the panel's description nor the channel line
    (`ChannelLineRow`, also not focusable) is spoken. The owner's requirement that the grey chip still says why is met
    only for explore-by-touch.
- **Confirmed:** by reading. I am not sure how Fire TV VoiceView treats a described container that cannot take focus;
  this is worth one check with a screen reader on.
- **Fix:**
  - While the state is `BeyondGuide`, append the line to the description of the slot that takes focus on entering the
    panel ("Follow. Channels unavailable: on the Jets and Bruins team channels."). Or set
    `liveRegion = LiveRegionMode.Polite` on `ChannelLineRow` when `line.beyond`, so the line is announced when the
    state arrives.
  - Keep the slot's own description as "Channels, unavailable" plus the line, for touch.
  - `HubEventChannelsTextTest.thePanelDescribesTheBeyondGuideLine` pins only the container's description. Add a semantics
    assertion on the focused slot.

---

## Minor

### m1. Whether "a real listing wins" depends on the window's edges, and a late real listing hides the whole event
- **Where:** `core/.../guide/feed/GuideWindowPagerImpl.kt:456`, and `EventOverlay.realOverlap` (`:735`).
- **Failure 1, window edges:**
  - `realOverlap` sees only the rows inside the request window, which starts at `windowStartMin − 180`.
  - Take a football event with an estimated 210 min: 1:00 PM to 4:30 PM, and a real listing from 12:00 to 1:15 PM.
  - Scroll so the viewport starts at about 4:20 PM. The window starts after 1:15 PM, so the listing is not in it, and the
    event is inserted. It is drawn from 1:00 PM.
  - Scroll back past the cover margin and it disappears.
  - Only events longer than 180 min are affected (football today), so this is rare.
- **Failure 2, a listing late in the span:**
  - Take an event at 7:00 PM, estimated to end at 10:00 PM, and a real "Post-game" listing at 9:30 PM.
  - The whole event is suppressed, and 7:00 to 9:30 PM shows "No Event Today" placeholders. The end is only an estimate,
    so this throws away good information.
- **Confirmed:** by reading.
- **Fix:**
  - Decide the overlap from the rows that overlap the event's **start** only: a real listing covering
    `[ev.start, ev.start + 15)`.
  - Otherwise clip the event's end at the first real listing that starts inside the span.
  - For failure 1, also widen the `rd.window` start by `max(0, EventDuration max − TARGET_MIN_BEFORE)` (30 min) on pages
    with events.
  - Add a pager test with the listing outside the window.

### m2. Panel text around an event reads oddly
- **Where:** `core/.../guide/state/GuideStateMachineImpl.kt:2474-2476`.
- **Contradiction:** for up to 3 h after the estimated end, the title says "No event scheduled" while the description
  says "…so this event may still be on" (`e2-i.png`). "This event" refers to nothing on screen.
  - Fix: during the may-be-on window, use the ended event's title as the panel title, for example "Jets vs. Bruins (may
    still be on)", or name it in the description: "Jets vs. Bruins was estimated to end at 10:00 PM; it may still be on."
  - Keep `NO_EVENT` after the window.
- **Ambiguous time:** `nextEvent(title, labels.hm(start))` gives "Next on this channel: Sharks vs. Ducks at 10:00."
  (`e6-l.png`). This has no AM/PM, and no day when the event is tomorrow or later.
  - Fix: use the hub's `SportsText.startTime`, or a guide formatter with the period, and add "tomorrow" or the weekday
    when the event is not today.
- **Confirmed:** by reading and from the screenshots.

### m3. The grey-chip state can stick with no team names
- **Where:** `ui/.../sports/SportsViewModel.kt:836-840`.
- **What goes wrong:**
  - `startMatch` publishes `BeyondGuide(emptyList(), …)` at once, then launches `runMatch`.
  - If that job is cancelled before `teamChannels` returns, the placeholder stays. Cancellation comes from the next
    game's `startMatch` (immediately on an OK-skipped dwell), from `stopFetching`, or from `onLeft`. The first
    `teamChannels` per league is a full scan of `channel`.
  - On every later visit within the same tab entry, `old is BeyondGuide && beyondGuide(...)` returns early.
  - The line then reads "Your guide doesn't cover this game yet" instead of naming the two team channels, until the tab
    is re-entered.
- **Confirmed:** by reading.
- **Fix:**
  - Return early only for a **finished** `BeyondGuide`. Add `val complete: Boolean` to the state, or keep the
    placeholder in a separate state such as `MatchState.Finding`, drawn grey.
  - Add a view-model test that cancels the first job.

### m4. A generation without `dataToMin` makes nothing grey for up to a week
- **Where:** `data/.../sports/GameChannelMatcherImpl.kt:175-178`.
- **What goes wrong:**
  - The fallback is `windowToMin`, which is build time + `futureDays` (7 d), not the end of the data.
  - After the upgrade, until the next EPG build, no game in the next 7 days has a grey chip. A game days away runs a full
    match and shows "Channels (2)" with the team channels only.
  - The same holds forever if `dataToMin` comes back null (a SQLite error is swallowed at `EpgStoreImpl.dataToMin`).
- **Possibly related:** `e5-d.png` shows a game on Sat Nov 28 at 4:00 PM matched normally ("Channels (3)") on Wed
  12:40 PM, with a 3-day fake-panel guide. I could not tell from the files whether that generation had `dataToMin`.
  - Check the `guide data end` log line before taking e5-d as the beyond-guide evidence.
- **Fix:** fall back to `min(windowToMin, builtAt + 24 h)` instead of `windowToMin`. The design accepted the fallback, but
  the window end almost never limits anything.
- **Confirmed:** by reading.

### m5. A reminder is marked gone when the guide or the provider re-spells the same programme
- **Where:** `app/.../reminder/ReminderSchedulerImpl.kt:323-341`.
- **What goes wrong:**
  - Take a reminder set on a **real listing** of an event-named or empty-named slot.
  - At fire time the listing at the raw start has a slightly different title ("NHL Hockey: Oilers at Flames" →
    "NHL: Oilers @ Flames"), or the provider re-spells the slot's event ("Golden Knights vs. Oilers" → "Vegas Golden
    Knights vs. Edmonton Oilers").
  - Steps 0 and 1 compare exact folded titles, so this falls through to GONE.
  - The card then says the provider moved it and removes "Switch now", though the programme is on that channel at that
    time.
  - Before this feature it fired normally.
- **Fix:**
  - Step 0: any **real** listing at the raw start (`!EpgFiller.isFiller`) is SAME when the reminder's start equals that
    listing's start.
  - Step 1: the same channel with a start within ±30 min and the same two matchup teams is SAME (compare
    `EventMatchup.of` sides with `TeamNames.tokens`), even when the titles differ.
- **Confirmed:** by reading.

### m6. The card text for a gone reminder reads badly
- **Where:** `ui/src/main/res/values/strings_guide.xml`, `reminder_event_moved`.
- **What goes wrong:** "Your provider changed Golden Knights vs. Oilers's channel." The possessive on a title reads
  badly, and "changed … channel" also claims a move that was not observed.
- **Suggested:** "“%1$s” is no longer on this channel. Search for it to find where it is now." It keeps the reason and
  the next step.

### m7. The hub line uses abbreviations although the nickname would fit
- **Where:** `ui/.../sports/HubPanel.kt:623` (`lineBudget`).
- **What goes wrong:**
  - The 55 / 45-character budget is conservative.
  - In `e5-a` / `e5-b` (a larger text size), the line reads "On TV at 7:00 PM: BUF team channel · +9 more". It ends
    about 110 px short of the 664 px line.
  - "Sabres" would have fit, and "BUF" reads like a code.
- **Fix:** measure with `rememberTextMeasurer()` at the line's style and width instead of counting characters, or raise
  the budget about 10 %. The test should pin the measured case.

### m8. The beyond-guide line for one team says "On TV:" for a game days away
- **Where:** `ui/.../sports/HubPanel.kt:663`.
- **What goes wrong:** with only one team channel, the line reads "On TV: Oilers team channel", which sounds as if the
  game is on now.
- **Fix:** add `sports_hub_beyond_guide_one` = "On the %1$s team channel", matching the two-team string.

### m9. City-only CONFIRMED has holes outside the NBA arena argument
- **Where:** `core/.../sports/hub/EventGameRule.kt:92-97`.
- **What goes wrong:**
  - The "no clash on the board" check reads only the NHL board. The arena argument covers NHL against NBA home games
    only.
  - Pairs it misses:
    - college teams named by state: Utah @ Colorado and Washington @ Minnesota are conference games in Big 12 / Big Ten
      basketball, on the same nights as the Mammoth @ Avalanche and Capitals @ Wild NHL games;
    - an NFL Sunday against an NHL matinee: Washington @ Pittsburgh, Buffalo @ Tampa Bay.
  - When the name and the group carry no league word ("ESPN+ 45: Utah @ Colorado @ …"), such an event is CONFIRMED for the
    NHL game.
- **Confirmed:** by reading. These are rare; I am not sure it is worth a change before the owner sees a real case.
- **Cheapest fix:** require the event's group or name to have no college or other-sport word **and** the group to look
  like a hockey or general-sports slot. Or keep city-only matches at LIKELY unless the group says NHL.

### m10. Duplicates of one feed take cap slots, pushing event rows out
- **Where:** `data/.../sports/GameChannelMatcherImpl.kt:534`, `:541`.
- **What goes wrong:** in `e4-f` / `e4-g`, 4 of the 8 capped rows are 2 feeds: "4K: NESN" / "NESN" and
  "TSN 3 (Alternate)" / "TSN 3". Guide-only candidates collapse on the display-name residue, and those residues differ.
  This is the main reason event rows fall under the cap (see open item 3).
- **Fix:** collapse guide-only candidates in one playlist that resolve to the same EPG channel (`channel.epg`) and the
  same airing raw start. They are the same feed in another quality.

### m11. Editor wording (open item 1)
- **Where:** `strings_editor.xml` (`editor_d_rule`, `editor_d_no_event`, `editor_d_rule_any`, `editor_n_skipped_rule`)
  and `ui/.../editor/EditorMenuCard.kt:114`.
- See the answer to open item 1 below.

### m12. Blocking SQLite on `Dispatchers.Default` in the reminder check
- **Where:** `app/.../reminder/ReminderSchedulerImpl.kt:370` (`listingTitle`).
- **What goes wrong:** `store.openReader()` / `resolve` / `detail` run on the `@ApplicationScope` (Default) coroutine.
  This is not the main thread, but it is blocking IO on a CPU pool.
- **Fix:** wrap the call in `withContext(io)`; `GuideQueries` already does.

### Tests that would pass with broken code
- **Search:** `GuideSearchImplEventTest` has only positive place cases (M1). Nothing covers a guide shift on an event
  channel (M2).
- **Pager overlay:**
  - `GuideWindowPagerEventTest` has no case with the real listing outside the request window, and none with a
    placeholder that spans both sides of the event (head and tail of one row).
  - `overlayOf60EventsOver300RowsIsUnderAMillisecond` and `overlayOf1700EventsStaysCheap` assert wall-clock time in a JVM
    unit test. That is flaky on a loaded CI runner and proves nothing about a Fire TV. Make them benchmarks, or assert
    allocation and row counts instead.
- **View model:** `SportsViewModelEventChannelsTest` does not cover a cancelled beyond-guide job (m3).
- **Hub accessibility:** `HubEventChannelsTextTest` asserts the panel's description, not what a focused element says
  (M3).
- **Reminders:** `ReminderSchedulerEventTest` has no re-spelled-title case (m5).

---

## Open items

### 1. Editor reason line, and `N_SKIPPED_RULE`
The menu's reason is one 22 px line in a 480 px card, about 38 characters at Default. The three texts are 62, 80 and 110
characters, so the next step is always cut. That breaks the rule that an error carries a reason and a next step.

Recommended:
- Let the reason line wrap to **two lines**: `maxLines = 2` at `EditorMenuCard.kt:114`, and raise
  `EditorUi.MENU_LINE_REASON_H` by one 22 px line height.
- Shorten the texts to about 70 characters, so they fit in two lines at Default and nearly so at Largest:
  - `editor_d_rule`: "Hidden by a rule. Change it in Hide rules." (42)
  - `editor_d_no_event`: "No event listed. Turn off “Hide channels with no event” in playlist settings." (77). If that is
    too long at Largest: "No event listed. Turn off the no-event switch in playlist settings." (67)
  - `editor_d_rule_any`: "Hidden by a rule or the no-event switch. See Hide rules or playlist settings." (77)
- `editor_n_skipped_rule` is a notice. It cannot split the count cheaply, so name both causes: "%1$d stay hidden by a
  rule or by “Hide channels with no event”." The notice line has more width than the menu card. The precise
  alternative is to count switch-hidden rows apart in the show write: rows with `rule_hidden = 1` in a playlist with
  `hide_empty_events = 1`, checked with `isEmptySlot` in Kotlin, about 3,700 at most.

### 2. Sheet row reason for an event (`sports_hub_reason_event`)
In "From the channel name: 7:00 PM, Winnipeg Jets vs. Boston Bruins (Jets Br…", the matchup repeats the sheet's own
title ("Channels · WPG at BOS"). The useful parts are the source, the time and the **feed hint** ("Jets Broadcast" /
"Bruins Broadcast"), and the feed hint is what gets cut. `e4-g` shows the shorter ESPN rows fit:
"From the channel name: 7:00 PM, Hockey: Jets vs. Bruins (Bruins Broadcast)".

Recommended:
- Drop the matchup from the sheet reason. It was graded against this game, so its sides **are** the two teams. Show
  what is left of the title after the matchup, if anything:
  - `sports_hub_reason_event` = "Channel name: %1$s · %2$s" → "Channel name: 7:00 PM · Jets Broadcast" (39).
  - With nothing left: `sports_hub_reason_event_bare` = "Channel name lists this game at %1$s" (37).
  - Untimed (assumed zone): "Channel name lists this game" / "Channel name: Jets Broadcast".
- "What is left" is the title minus the two matched side spans and the separators, keeping any parenthesis or leading
  label ("NHL on TNT", "Hockey:"). `EventMatchup` already has the side boundaries. If it cannot expose spans cheaply, a
  fallback is to strip the matched side tokens from the folded title.
- Keep the full title in the row's content description.
- Rank event rows by |Δ start| and exact-before-cities-only inside the class, so "7:00 PM" rows come before "7:30 PM"
  ("NHL on TNT") ones.

### 3. Does the "one event row reserved" work?
Yes, as designed. `rank` (`GameChannelMatcherImpl.kt:534-537`) replaces the 8th row with the best event group only when
**no** capped group's head carries an event. It reserves exactly one row.

Why `NHL 01` / `Sportsnet+ 09` can still fall out:
- **Other event rows outrank them.** In `e4-g`, four event rows made the cut: NHL 12, NHL 13, ESPN UNLTD 200 and 202. The
  reservation never triggers. The rest are sorted by provider order inside class 2, not by closeness to the game.
- **Duplicate feeds use the slots** (m10). Four rows are two feeds.
- **Two edge cases:**
  - A capped head that has both a guide listing and an event satisfies the check. Its sheet reason reads "In your guide…",
    so no row says "From the channel name".
  - An event candidate that also has a sport listing collapses on the display-name residue and can sit behind a non-event
    head. Then `rest.firstOrNull { it.first().acc.event != null }` misses it.

Recommended:
1. Fix m10 (collapse same-EPG feeds). That alone returns 2 slots here.
2. Inside class 2, sort by exact before cities-only, then |Δ|, then the existing keys.
3. Test the reservation against a group whose **any member** has an event, and count only event-only groups as
   "reserved satisfied".

I would not raise the reservation above one row. With the first two changes, a typical NHL night keeps 3–5 event rows
under the cap.

### 4. "Bruins team channel · +9 more", and the home side first
- **The line.** It names one team while the owner's requirement is that the game is on **both** teams' channels, and the
  "+9" hides the other team channel. Recommended: name both when both exist, with the time first so nothing important
  is cut.
  - New `sports_hub_on_tv_teams_at` = "On TV at %1$s: %2$s and %3$s team channels" → "On TV at 7:00 PM: Bruins and Jets
    team channels · +8 more". That is 57 characters, so at the current budget it would fall back.
  - A shorter form fits at Default: "7:00 PM: Bruins and Jets team channels · +8 more" (48).
  - Fall back to the single-team line, not to abbreviations, when it does not fit. Abbreviations ("BUF") read like codes
    (m7).
  - With one team channel, keep today's line.
- **Home first when neither side is favourited or more watched.** Keep it. It is the owner-confirmed rule, it is
  deterministic, and the home feed is the more common "main" broadcast.
  - One cheap improvement before the home tie-break: prefer the side whose team's country matches the device's region.
    The owner's library is Canadian (Sportsnet+, TSN), so Jets at Bruins would lead with the Jets. Only do this if the
    owner wants it; it changes a confirmed ruling.
  - Watch counts on the team channels will take over after the first few tunes anyway.

---

## What I did not review
- The parser, `EventIndexImpl`, the migration, refresh and scheduler, hide-switch storage, `RuleHiddenPass` and backup.
  These belong to the second reviewer. I treated `EventIndex` / `EventNameParser` / `EventMatchup` / `TeamNames` as
  given contracts. I read `EventGameRule` and `TeamChannelRule` only as far as the cross-league questions needed.
- `EpgStoreImpl` / `ImportSession` (`dataToMin` computation), beyond the fallback in m4.
- The golden hashes in `ui/src/test/resources/golden/sports-hashes.txt`: not regenerated or diffed visually.
- The benchmark journey `scrollEventRows25Hz`: read only. It looks right: CH− into the event rows, 25 Hz down, 10 Hz
  right. Not run, and there is no device gate result to check.
- I did not run the gradle gate, lint or any emulator or adb step. One temporary data unit test was run and deleted
  (`EventSearchTerms` cases, M1).

---

## Resolution (fix pass, 2026-11-25)

Recorded in `docs/design/m6-event-channels.md` §18, with the owner's rulings of the same day (§18.1).

| Finding | Status |
|---|---|
| M1, team name search noise | Fixed (§18.2), with the five negative cases as tests. |
| M2, duplicate `LazyRow` key | Fixed both ways (§18.2): same raw start dropped, and the key carries the hit kind; a guide-shift test. |
| M3, grey chip not spoken | Fixed (§18.3): the slot that takes focus and the grey slot carry the reason; a semantics test on the focused slot. With the owner's ruling (§18.1 item 2) the chip is grey only when no team channel was found. |
| m1, "a real listing wins" and the window | Deferred (§18.8). |
| m2, panel text around an event | Fixed (§18.7). |
| m3, sticky beyond-guide placeholder | Fixed (§18.3), with a view-model test that cancels the first read. |
| m4, no `dataToMin` | Fixed (§18.4). |
| m5, re-spelled reminder | Fixed (§18.7) by matchup equivalence (not "any real listing", which could not tell a different programme at the same minute). |
| m6, the gone card's wording | Fixed as suggested. |
| m7, measured hub line | Partly: both team names come first; measuring deferred (§18.8). |
| m8, "On TV:" beyond the guide | Fixed: "Not in guide yet: …" (§18.1 item 2). |
| m9, city-only CONFIRMED across sports | Deferred (§18.8). |
| m10, duplicate feeds in cap slots | Superseded by the owner's ruling: no cap; feeds of one guide channel grouped, each selectable (§18.1 item 1). |
| m11, editor wording | Fixed (§18.7), with a two-line fit test at Default and Largest. |
| m12, blocking SQLite on Default | Fixed: `withContext(io)`. |
| Flaky timing assertions | Made robust: best of five batch medians, and the overlay's output pinned. |
| Open item 1 | Done (§18.7). |
| Open item 2 | Done (§18.3; `EventMatchup.rest`). |
| Open item 3 | Superseded: no cap or reservation; event rows ordered by name match then closeness (§18.1, §18.3). |
| Open item 4 | Done (§18.3); home first kept. |
