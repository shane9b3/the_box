# NFL hub: code review, UI half (2026-11-26)

Branch `nfl` at 2ad75cb, `git diff main...HEAD`. Scope: `ui/src/main/kotlin/app/thebox/ui/sports/` (HubLeagueUi, NflHubUi,
NhlHubUi, ChlHubUi, HubPanel, GoalsSheet / Scoring sheet, BoxScoreSheet, MatchupSheet, SportsViewModel, SportsScreen,
ClipSession, ClipPlayerLayer, SportsText, HubCard), `strings_sports_hub.xml`, the UI tests and `sports-hashes.txt`.
Reviewed by reading, against `main`, the golden renders in `ui/build/sports-tab/nfl-hub-*.png` and the emulator shots
`n1`–`n6`. No test was run; every finding says how it was found.

**Verdict: ship after fixing the Major (M1). There are no Blockers.** The NHL and CHL paths moved without changing
behaviour. The offer timer is correct and cannot spin. Every NFL phase has a usable slot. The Minors can wait for a
follow-up.

Counts: Blocker 0 · Major 1 · Minor 12.

---

## Lead question: the final CHL panel in `n1.png` has no slots and no channel line

**This matches `main` and the approved design. It is not a regression.**

- On `main`, `HubSlots.default` (`git show main:ui/…/HubPanel.kt`, lines 244–251) gives a non-NHL final
  `[WATCH_FROM_START if a catch-up airing exists, else EMPTY, EMPTY, EMPTY]`. On the branch, `ChlHubUi.slots`
  (`ChlHubUi.kt:52-57`) has the same code. `channelLine` returned null on `main` for every final except the NHL's
  (`nhlFinal`). On the branch it returns null for every final in a league that is not `detailed`, and the CHL is not
  (`HubPanel.kt:518`). So for the CHL the result is the same.
- The design says this is intended: m6-sports-hub §1.5 lists "Final NFL / CHL: Watch from start (catch-up airing found;
  else no slot)", and line 209 says "No enabled slot (postponed; NFL / CHL final without catch-up): nothing happens and
  the footer shows no OK."
- It is not a focus trap. `SportsViewModel.enterPanel` returns null when `firstEnabled` is null (`SportsViewModel.kt:681`),
  and `SportsScreen.enter` then does nothing, so focus stays on the card. If focus is already in the panel when every slot
  goes grey, `LaunchedEffect(panel.slots, …)` (`SportsScreen.kt:293-298`) moves it to `firstEnabled`, or else calls
  `exitPanel()` and returns to the card. While a game is live or pre-game, the frozen set always keeps Follow, which is
  always enabled. A postponed game also has zero slots, in every league; that is also unchanged.
- Today zero slots is reachable only for a final CHL game without a catch-up airing, and for a postponed game in any
  league. Each NFL phase has at least one usable slot (Scoring and Box score on finals; `HubLeagueUiNflTest:373` asserts
  this).
- One older oddity remains, on both `main` and the branch: a final CHL game *with* a catch-up airing gets "▶ Watch from
  start" in slot 1 but no channel line explaining which airing. Owner's call; not filed.
- The CHL final rules lost their only test. See m11.

---

## Blocker

None.

## Major

**M1. The Matchup sheet shows the live game's stats as "Season leaders", and the injury report shows the game-day
inactives.**
`MatchupSheet.kt:127-160` (`nflMatchupRows`), `:242-262` (`nflLeaderLine`), `:282` (`hasMatchupRows`);
`HubPanel.kt:282` (`forAction(MATCHUP)`); `SportsScreen.kt:802`.

- **What the viewer sees.** The viewer is in a pre-game NFL panel at kickoff, so the slots are frozen as
  `[FOLLOW, CHANNEL, MATCHUP]`, or has the Matchup sheet open. The board turns LIVE and the detail becomes the live
  summary. `forAction(MATCHUP)` recomputes `enabled = hasMatchupRows(liveDetail)`, which is true because live summaries
  carry *game* leaders (§18.4: all five categories). The sheet, opened now or already open, is fed `panel.detail`. It
  then shows:
  - "Season leaders" with tonight's game numbers;
  - "Injury report" listing the in-play inactives ("Out (coach's decision)");
  - and, from the real capture, a rusher with `displayValue` "3 CAR, - YDS". `nflLeaderLine` turns this into **"- yds"**
    (`"- YDS"`: num = "-", unit YDS → `sports_hub_unit_yds`). This is the integration pass's open defect #2.
- **The trigger is narrow**, but the result is wrong information under a confident heading, and the 30 s board cadence
  makes it likely on any game night when someone browses at kickoff.
- **How found.** By reading, with the GB @ LAR live shape in §18.4. Not run.
- **Fix.**
  - Use the season header only for a pre-game detail. Either check `game.phase` in `nflMatchupRows` / the MATCHUP slot,
    or have `NflHubUi` gate MATCHUP on `pre` (live → disabled, with the existing "matchup" reason).
  - Make the Matchup sheet close itself when the game leaves pre-game (as Box score is final-only).
  - In `nflLeaderLine`, drop any part whose number has no digit, or draw "–". So "3 CAR, - YDS" becomes "–", never
    "- yds". Add the real `"3 CAR, - YDS"` string as a test case.

## Minor

**m1. Highlight captions and slot labels that change with time are not covered by the one timer.**
`NflHubUi.kt:340-373`; `SportsViewModel.kt:813-834`.

- `highlightSoon` and `noHighlightCaption` change at "ended + 2 h" (Highlights soon → No highlights / "ESPN didn't
  post…") and at "ended + 48 h" (→ "ESPN removes videos…"). Neither instant is in `nextOfferChange`. After the final
  wait the repository goes quiet, so a panel left open on a final keeps showing "Highlights soon" and "Highlights about
  15 min after the game" until some other emission arrives (a card focus change, a board poll).
- The 48 h change alters only the caption. Slots are in the model's equality and the caption is not, so even a refresh
  shows the old caption until something else changes the model.
- By reading.
- Fix: return `start + HUB_NFL_GAME_LENGTH_MS + HUB_NFL_HIGHLIGHT_WAIT_MS` (and `+ HUB_CLIP_DEFAULT_LIFE_MS`) from
  `nextOfferChange` when they are in the future and no highlight is offered.

**m2. An embargoed highlight reads as "removed".**
`NflHubUi.kt:357-362` (`anyExpired`).

- `!offered(it, at)` is also true *before* an embargo lifts (`ClipOffer.offered` checks the embargo). So a RECAP clip
  under embargo makes the slot "No highlights" and the caption "ESPN removes videos after a few days", when the right
  reason is "Highlights soon". The timer does re-check when the embargo lifts.
- Rare: no capture so far shows an embargo on a RECAP. By reading.
- Fix: count as expired only `expiresMs − margin <= at`.

**m3. A frozen "▶ Highlights" whose clip expired a moment ago does nothing when pressed.**
`SportsViewModel.kt:717-719`.

- `NflHubUi.highlight(...)?.let { playClips(...) }`: when it is null there is no toast. The test
  `watchRecapNeverPlaysAClipThatIsNoLongerOffered` asserts that nothing streams, but not that the viewer is told.
- §3.4 wants "ESPN removed this video…" (VIDEO_EXPIRED). By reading.
- Fix: `?: toast(HubToastKind.VIDEO_EXPIRED, league = LeagueGroup.NFL)`.

**m4. The grey Matchup slot says "still loading" when the detail has failed.**
`NflHubUi.kt:453`.

- The reason is "loading" whenever `m.detail == null`. A pre-game detail that failed with nothing cached therefore
  speaks "Matchup details are still loading. Try again in a moment." while the panel body says ESPN failed.
- By reading.
- Fix: when `m.detailKind == DetailKind.FAILED`, give the failure reason, or point to Retry.

**m5. `hasMatchupRows` can enable an NFL Matchup sheet that has nothing to focus.**
`MatchupSheet.kt:282`.

- For the NFL, `d.leaders.isNotEmpty()` is true for a pair with both sides null, and for a category outside the five
  drawn. `nflMatchupRows` filters both out. The slot is then usable and opens a sheet whose title holds focus; Back
  still works.
- By reading.
- Fix: for the NFL, enable the slot exactly when `nflMatchupRows(…).any { it.focusable }`. Moving this into
  `HubLeagueUi` also removes one of the #1 checks below.

**m6. After scrolling back up, the section header and column header above the first focusable row stay off screen.**
`GoalsSheet.kt:646-650` (`HubSheetRow`); visible in BoxScoreSheet (`Passing` and the `Player C/ATT YDS…` header),
MatchupSheet (`Season leaders` and `Passing`) and the Scoring sheet (an info row at the top of a final with no points).

- `scrollTo` helps only on open. Once the viewer has scrolled down, moving back up to the first focusable row brings
  only that row into view. Up is then consumed (`first`), or goes to the tab, which is outside the list. In the Box
  score, the first table's title and column header stay hidden.
- The NHL box (SkaterHeader) has the same older pattern.
- By reading, from LazyColumn's bring-into-view behaviour. Not checked on the emulator, so unsure.
- Fix: when the first focusable row gains focus, `animateScrollToItem(labelStart(i))` (or 0).

**m7. A video row that expires while focused sends focus to the sheet's initial row, not to its neighbour.**
`GoalsSheet.kt` `HubSheetFrame` `target` (no `refocus` from `GoalsSheet`).

- When the offer tick removes the focused More videos row, `lost` fires and focus goes to `initialRow`: the latest play
  when live, the first row on a final. The viewer jumps to the top of the list.
- By reading.
- Fix: pass a `refocus` that picks the nearest focusable index to the old one.

**m8. After a clip ends whose slot went grey, focus leaves the panel.**
`SportsScreen.kt:271`, `:246-255`.

- `focusSlot(lastSlot)` tries the now-disabled "No highlights" slot three times, then calls `exitPanel()`. Focus lands
  on the card, not on Scoring. It still has a target.
- By reading.
- Fix: `focusSlot(panel.slots[lastSlot].takeIf { it.usable }?.let { lastSlot } ?: panel.firstEnabled ?: …)`.

**m9. The Last 5 row may overflow its 96 px at Largest.**
`MatchupSheet.kt:87-92`.

- The row holds the team line (22/600) and the results (22/500, `maxLines = 2`) in a fixed `height = 96`.
- At Largest (factor 1.2, about 34 px per line) the five results ("W 23–13 DAL · L 20–17 @ DET · …", about 920 px against
  about 848) wrap to two lines. Three lines plus gaps come to about 104 px. Not covered by any golden (`nfl-hub-matchup`
  is Default and stops at Sacks).
- By reading. Unsure: not rendered.
- Fix: scale the row height with the factor, or add a Largest Matchup golden scrolled to Last 5.

**m10. A new `Path` is allocated on every draw of the play glyph.**
`GoalsSheet.kt:429-436` (`PlayGlyph`).

- `drawBehind { val p = Path()… }` allocates on every draw of every More videos row (focus moves, scrolling). This
  breaks the "no per-frame allocation in draw paths" rule.
- By reading.
- Fix: `drawWithCache { val p = Path()…; onDrawBehind { drawPath(p, ink) } }`.

**m11. The final CHL rules lost their only test, and a test name now claims CHL coverage it does not have.**
`SportsViewModelHubTest.kt:167` `aFinalNflOrChlGameOffersWatchFromStartOnlyWithACatchUpAiring`, and the old
`assertNull(firstEnabled)` for an NFL final.

- The CHL is no longer exercised. A change to `ChlHubUi.slots` (for example, dropping Watch from start, or giving a
  final CHL game an enabled slot) would pass every test.
- By reading.
- Fix: add a CHL final test in both directions (no catch-up: `actions == [null, null, null]` and `enterPanel == null`;
  a catch-up airing: `[WATCH_FROM_START, null, null]`), and rename the NFL test.

**m12. "Video k of n" can be wrong after a skip.**
`SportsScreen.kt:442`, `ClipPlayerLayer.kt:258-266`.

- The sub-line counts within the panel's *current* `moreVideos` list, which changes as clips expire. The queue was fixed
  when OK was pressed, so after a skip or expiry the number shown may not match the queue (for example, "Video 2 of 3"
  for the third entry of a four-entry queue).
- By reading.
- Fix: take k and n from `ClipSession.State` (queue index and size, among PLAY entries).

---

## Open defects from the integration pass (§18.3, §18.4)

**#1: `group == LeagueGroup.NFL` checks outside the `HubLeagueUi` implementations.** These are the hub-related ones; the
day-strip and board checks in `SportsViewModel` (lines 260–642) are about the board, not the hub, and are fine.

| # | Place | Matters now? |
|---|---|---|
| 1 | `HubPanel.kt:255` `Ctx.playableRecap` | No: correct today, but it should be `ui.playableRecap(c)` |
| 2 | `HubPanel.kt:270` `forAction(GOALS)` → `NflHubUi.scoringSlot` | No: duplicates the routing; a `HubLeagueUi.slotFor(action, c)` would remove 2–4 |
| 3 | `HubPanel.kt:276` `forAction(WATCH_RECAP)` → `highlightSlot` | No: as 2 |
| 4 | `HubPanel.kt:284` `forAction(WATCH_FROM_START)` | No: as 2 |
| 5 | `HubPanel.kt:1118` `clipKeyPrefix` | Cosmetic (`HubSource`) |
| 6 | `HubPanel.kt:238` `Ctx.nhl` | Dead code now; delete |
| 7 | `SportsViewModel.kt:717` WATCH_RECAP | **Yes, slightly:** the silent no-op of m3 lives here; it should be `ui.recapRef(…)` with a toast |
| 8 | `SportsViewModel.kt:782` `playNoVideo` league | Cosmetic |
| 9 | `SportsViewModel.kt:799` `clipOffered` | No (the NHL has no expiry) |
| 10 | `SportsViewModel.kt:807` and `SportsScreen.kt:301` (kill switch) | **Yes, slightly:** two copies of the `ESPN_VIDEO_ENABLED` rule; expose one `videoOffLeagues` from the view model that already includes it |
| 11 | `SportsViewModel.kt:819, 822` offer timer | No: should be `ui.nextOfferChange` |
| 12 | `SportsViewModel.kt:1019` ESPN skew | No |
| 13 | `SportsViewModel.kt:1033` headline score | No: should be `ui.detailCarriesScore` |
| 14 | `HubCard.kt:261` `cardHighlights` | No |
| 15 | `MatchupSheet.kt:53`, `BoxScoreSheet.kt:90`, `GoalsSheet.kt:135` | Cosmetic. The Matchup check is where M1's phase guard belongs |
| 16 | `ClipPlayerLayer.kt:258`, `SportsScreen.kt:811` (toast words) | Cosmetic (`HubSource`) |
| 17 | `HubToast.league` defaults to NHL | Low risk: every NFL toast passes `league` today; a new caller that forgets it gets NHL.com words |

None of these is wrong today apart from rows 7 and 10. Moving rows 2–4, 7 and 11 behind `HubLeagueUi` removes most of
the list in a single change.

**#2: Matchup `nflLeaderLine` with a numberless negative value.** It is real ("- yds", confirmed by tracing the split by
hand). It is reachable only through M1, since season lines never carry a negative. It is folded into M1's fix.

---

## Text and screenshots (checked, no finding)

- **Header.** "Green Bay Packers (13) vs. Los Angeles Rams (13)" fits at Default (`n2`, about 634 of 664 px). At Largest
  it falls back to nicknames ("Bengals (17) vs. Commanders (13)", golden). Scores are never cut.
- **Situation line.** "LAR ball · 1st & Goal at GB 1 · Red zone · Timeouts 3–3" fits at Default (`n2`). At Largest,
  Timeouts drops and becomes the extra row (`nfl-hub-panel-live-largest`).
- **Long values.** Three-digit yards, "Time of possession" with "22:14 / 37:46" (`n4`), and the Recap headline
  ellipsized on one line (`n4`) are all fine.
- **Slots and the clip bar.** The slot sets fit (P3 note 18 and `HubPanelFitTest`). "Video: ESPN" is on every ESPN
  sub-line (`ClipPlayerLayer.kt:258-265`, golden).
- **Text floors.** All new text is ≥ 22 design px through `GuideUi.sans/mono` → `textSp`. No `graphicsLayer`, clip or
  blur was added above the video surface; the new `clip(shape)` calls are on thumbnails inside sheets, which are not
  ancestors of the root SurfaceView.
- **Box score.** The box score uses the mono face for its numbers (`n6`), the same as the NHL box score.

## Not reviewed

- `:core`, `:data`, `:player` and the fake panel (the other reviewer): `GameDetail`, `ClipOffer`, `ClipResolver`, and
  whether `NhlClipResolver` ignores `fresh = true` (`ClipSession` now passes it to the NHL on Try again and on the
  expired-link retry).
- RTL rendering and other locales. Strings are built with " · " separators and `%1$s %2$d pass`-style formats; there is
  no RTL golden.
- TalkBack or VoiceView on a device. The spoken strings were read but not heard.
- Behaviour on the device or emulator beyond the six shots; no new emulator run.
- Low-RAM (`isLowRamDevice`) rendering of the Scoring sheet and the panel.
- `NflBug*`, `StackedNfl*` and the other score-bug files: unchanged in this diff.

---

## Resolution (fix pass, 2026-11-26)

Details and tests in `docs/design/m6-nfl-hub.md` §19.2.

| # | Finding | Resolution |
|---|---|---|
| M1 (Major) | Matchup after kick-off shows game stats as season leaders | **Fixed**: Matchup pre-game only; the open sheet closes at the phase change with focus to the first usable slot; a frozen NFL set re-freezes at a phase change; `nflLeaderLine` never draws a numberless part ("3 CAR, - YDS" → "–"). |
| m1 | Caption windows not on the timer | **Fixed**: ended + 2 h and ended + 48 h are offer-timer instants. |
| m2 | Embargoed highlight reads "removed" | **Fixed**: expiry-only check. |
| m3 | Frozen ▶ Highlights whose clip expired does nothing | **Fixed**: the VIDEO_EXPIRED toast. |
| m4 | Grey Matchup says "loading" on failure | **Fixed**: a failed reason (new string). |
| m5 | Matchup enabled with nothing to focus | **Fixed**: the sheet's own focusable-row rule. |
| m6 | Headers stay off screen after scrolling back up (unsure) | **Confirmed real** by a Robolectric scroll test (Matchup and Box score); **fixed** in `HubSheetFrame` / `HubSheetRow`. |
| m7 | Expiring focused video row → initial row | **Fixed**: focus to its neighbour. |
| m8 | Focus leaves the panel after a clip whose slot went grey | **Fixed**: nearest usable slot. |
| m9 | Last 5 row at Largest (unsure) | **Confirmed real when the results wrap** (two lines cut at Larger / Largest; the real GB line fits one); **fixed**: the row scales with the text size, 96 px at Default. |
| m10 | `Path` per draw | **Fixed**: `drawWithCache`. |
| m11 | CHL final rules untested | **Fixed**: test in both directions; NFL test renamed; behaviour unchanged. |
| m12 | "Video k of n" after a skip | **Fixed**: from the session's queue. |
| #1 rows 1–4, 6, 7, 9–14 | League checks outside `HubLeagueUi` | **Moved** behind `HubLeagueUi` (`slotFor`, `playableRecap`, `recapRef`, `offeredRef`, `videoKilled`, `nextOfferChange`, `detailCarriesScore`, `cardHighlights`); `Ctx.nhl` deleted; one `videoOffLeagues`. Every existing golden hash equal. |
| #1 rows 5, 8, 15–17 | Cosmetic checks | **Deferred** (design §15): words and layout switches only; correct today. |
| #2 | `nflLeaderLine` numberless value | **Fixed** with M1. |
| CHL final with no slots (lead question) | Matches `main` | No change (now tested, m11). |
