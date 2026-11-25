# Sports Hub code review: UI side (2026-11-25)

Reviewer scope: `ui/src/main/kotlin/app/thebox/ui/sports/**` (SportsScreen, SportsViewModel, HubPanel, HubCard, DayStrip,
MatchupArt, HubNames, SportsText, ClipPlayerLayer, ClipSession, GoalsSheet, ChannelSheet, BoxScoreSheet, MatchupSheet,
SportsEffect), the clip hole in `AppRoot`, `strings_sports_hub.xml`, and their tests. Branch `sports-hub` at 85ce40b.
Method: code reading against `docs/design/m6-sports-hub.md` (§1–§2, §6, §15), the owner's mock-ups and the integration
screenshots in the session scratchpad `hub-emu/`. Every screenshot is older than 85ce40b (taken 22:00–22:15, commit 23:02).
Nothing was run on the emulator; no tests were run. Every finding below comes from reading the code. Where the outcome
depends on Compose runtime behaviour that I did not run, the finding says "unverified".

## Summary

| Rank | # | Finding |
|---|---|---|
| High | H1 | Box score: OK on Retry removes the focused row. Focus is lost, Back is held, and the grid behind the sheet can take focus. |
| High | H2 | The hidden Goals sheet can take focus from the clip layer during a clip. The clip then cannot be controlled or stopped. |
| High | H3 | A detail that fails after OK entered the panel tells a live or pre-game user to "Select Watch recap", which isn't on screen. |
| Medium | M1 | A sheet whose game leaves the panel stops drawing but stays open. The page then has no focus and Back does nothing. |
| Medium | M2 | `focusSlot` relies on two frames passing. If the slot request fails, the page is in a half-entered state. |
| Medium | M3 | The detail and the matcher keep running for the last game after a day or league change to an empty or failed board. |
| Medium | M4 | Poster media at Larger and Largest: the play disc and the caption crowd each other; the poster spec stays while videos are off or before a poster arrives. |
| Medium | M5 | Headline: whole-line measure against a four-`Text` layout. Edge cases ellipsize the away name; the ellipsis case falls back to equal weights. |
| Medium | M6 | A focused card that leaves the board is not replaced by its neighbour (§1.2). Focus is lost. |
| Medium | M7 | The panel's game is not shown again to the detail repo on re-polls. A game going live while the user rests keeps the pre-game cadence and no match runs again. |
| Medium | M8 | Tests: the fit test missed the headline bug; the box-score "retries" test never presses Retry; nothing tests the `entered` gate or focus after a clip; no goldens. |
| Low | L1–L9 | See below. |

---

## High

### H1. Box score Retry (or a failure while a row is focused) loses focus, and Back is then dead

- `BoxScoreSheet.kt:79-82, 125-127`; `SportsViewModel.kt:638-654`; `SportsScreen.kt:188`.
- **Scenario:** final NHL game, offline or a 5xx on the box score. Press OK, Right, Right to reach Box score, then OK. The sheet shows
  the error and a Retry row. Press Down to Retry, then OK. `retryBoxScore()` → `openBoxScore()` sets `box = null`, so `boxRows`
  becomes `[Loading]`. The focused Retry row leaves composition, and `requesters` is re-remembered because `rows.size` changed.
  `HubSheetFrame` is called with `takeInitialFocus = false`, so nothing focuses again when the answer arrives.
- **Result:** nothing on the page has focus. The router holds Back (`sheet != null`), so Back does nothing. The sheet's own Back
  handler only runs when focus is inside the frame. A D-pad press now runs Compose's initial focus search. That search can
  land on a card or chip behind the sheet, because the page never turns off focus behind an open sheet. §2.5 says the grid
  behind the sheet is not focusable; the code does not enforce that. From a card under the sheet, Back is still held and
  does nothing.
- The same loss happens when `fetch` changes from Ok to Failed while a skater row is focused (`HubSheetsTest:180`
  does exactly this and never asserts where focus went).
- **Fix:**
  1. In `BoxScoreSheet`, after any change to `rows`, if focus was inside the list, re-request focus: the tab of `side`, or the Retry
     row when it exists. A `LaunchedEffect(rows)` guarded by a `hadFocus` flag is enough.
  2. In `SportsScreen`, make the page behind a sheet unfocusable: `Modifier.focusProperties { canFocus = sheet == null }` on the
     cards' and chips' parents, or a `focusGroup` with `onEnter` cancel.
  3. Add a test: Failed → press Retry → the tab or Retry row is focused → Back calls `onClose`.

### H2. The hidden Goals sheet can take focus from the clip layer

- `GoalsSheet.kt:104-110`, `HubSheetFrame` `GoalsSheet.kt:322-330`; `SportsScreen.kt:356-369`.
- The sheet stays composed under a clip (by design). Its focus request is `LaunchedEffect(focusKey, rowCount)`. The page froze
  `focusKey`, the clip just played, during the clip (integration fix §15.1). It did not freeze `rowCount`. `rows` depends on
  `detail` and, for a final game, on `catchUp = panel.candidates.filter { airing != null }`.
- **Scenario A (live):** `detail.pause(true)` closes the gate but does not cancel a fetch already in flight
  (`GameDetailRepositoryImpl.apply(Pause)` only sets `paused`). If that fetch returns with a new goal after the clip started,
  `rowCount` changes and the effect calls `requesters[i].requestFocus()` on a row that is not drawn.
- **Scenario B (final):** OK enters the panel, which starts the match at once. On a big playlist (§5.7) the match can take
  longer than the four quick presses OK, Right, OK, OK. When the catch-up rows arrive mid-clip, `rowCount` grows.
- **Result:** keys go to the invisible sheet, and the clip layer's `onPreviewKeyEvent` is no longer on the focus path. OK on a
  hidden row starts another clip. Back closes the sheet (`onClose` → `focusSlot`/`exitPanel`) while the clip keeps playing.
  The layer still holds Back for the router, so further Backs walk the invisible page and the user cannot stop the clip
  except by waiting for it to end or pressing Home. Found by reading the code; not reproduced.
- **Fix:**
  - Pass `takeInitialFocus = !clipActive`, or a `focusKey` that also includes `clipEpoch`, and make the effect a no-op while a clip
    plays.
  - Better, make everything under the clip unfocusable while `clipActive`: `focusProperties { canFocus = !clipActive }` on
    the page's content `Box`. That also covers any future focus request.
  - Add a `SportsScreenTest` case: while a clip plays, change the detail's goal count, and assert `CLIP_LAYER_TAG` is still focused.

### H3. Failure text offers "Select Watch recap" on live and pre-game games

- `HubPanel.kt:323-325, 648-655`; `SportsViewModel.enterPanel` `:589-591`; `strings_sports_hub.xml:278`.
- `failureText(retrySlot = slots.any { RETRY })` chooses `sports_hub_error_detail_auto` ("…Trying again automatically. Select
  Watch recap to see the game.") whenever no Retry slot exists. While focus is in the panel the slots are frozen. OK freezes
  them at once, while the detail is still Loading, so slot 1 is frozen as FOLLOW. `forAction(FOLLOW)` never becomes RETRY.
- **Scenario:** live card, OK, and the immediate fetch times out or returns a 5xx. The panel says "Select Watch recap to see the game"
  for a live game with no such button. The same happens for pre-game games, and for a final game whose recap slot is
  disabled while videos are off.
- This breaks the CLAUDE.md rule that the next step must be real. NHL.com flakiness makes this a common path.
- **Fix:** choose the text from the real phase and slots:
  - final with a usable WATCH_RECAP slot → `_auto`;
  - a RETRY slot → `_retry`;
  - otherwise a new string such as "…Trying again automatically. Press Back, then OK, to retry now."

  Alternatively, let a frozen FOLLOW slot become RETRY while the failure lasts. §2.3 allows an in-place change at the same index
  as long as nothing moves. Add a unit test: entered, then Failed(TIMEOUT), on a live game.

---

## Medium

### M1. A sheet whose game leaves the panel stops drawing but stays open
`SportsScreen.kt:741` (`SheetHost` returns when `panel.game?.id != sheet.gameId`); `:756` (`Matchup` / `Goals` need `d != null`).
- **Triggers:**
  - a re-poll drops the game;
  - `boardId` changes, which sets `shownId = null` (`SportsViewModel.kt:496`): the `/score/now` date rolls over while the page is open;
  - the detail loop throws and publishes `None` (`GameDetailRepositoryImpl:189`).
- The sheet's content leaves composition and focus with it. `_sheet` and `panelFocus` stay set, so Back is held and does
  nothing.
- **Fix:** in the view model, close the sheet and leave the panel when the panel's game id changes while either is open, and
  have the screen move focus to the card or first card.

### M2. The `focusSlot` two-frame wait is fragile
`SportsScreen.kt:224-231`; `HubPanel.kt:1038`.
- `entered` reaches the buttons through StateFlow → `collectAsStateWithLifecycle` → recomposition. The two frames assume
  the collector's resumption is dispatched before frame 1. On a busy Fire TV Stick this has not been verified.
- If `requestFocus` fails (it is wrapped in `runCatching` and the Boolean result is ignored), `panelFocused` is true but focus stays
  on the card:
  - the footer shows panel hints;
  - the card is drawn as "selected";
  - Back is held, and the panel's Back handler needs `focusedSlot >= 0`, so Back does nothing until the user presses OK again.
- **Fix:** keep a screen-local `mutableStateOf` `entered` that `enter()` sets synchronously before the request, and pass that to
  `HubPanel`. Or keep `canFocus = slot.enabled` and block unwanted entry with `focusProperties { onEnter = { if (!entered) cancelFocusChange() } }`
  on the panel. Either way, check the result of `requestFocus()` and call `onPanelExited()` if it failed.

### M3. The previous game keeps polling on empty or failed boards
`SportsViewModel.kt:569-573, 713-725`.
- `onBoardShown` only runs when cards exist. When the shown board is Empty or Failed (§15.1 "empty day", or a league switch), the
  view model does not cancel `fetchJob` or `matchJob` and does not call `detail.show(null)`.
- **Scenario:** rest 600 ms on a live game. The repository then fetches it every 30 s. Switch to an empty day (Nov 17): the live
  game is still polled while the page shows "No NHL games…". A pending dwell job can even start a new detail and a match for
  a game no longer on screen.
- This goes against §0.1 ("for the shown game only").
- **Fix:** when `buildPanel()` yields `game == null`, cancel both jobs, set `fetchedId = null` and call `detail.show(null)`.

### M4. Poster media area at Larger and Largest, and while videos are off (items left open in §15.4)
`HubPanel.kt:604-623, 966-1007`.
- **Disc and caption.** At Largest the live media area is about 165 px (screenshot 25 and 28 show it). The 88 px disc spans about
  38–127 px. The caption box (16 + about 40 + 10) starts near 99 px, so its text overlaps the bottom of the disc. Long captions
  ("Vilardi strikes first (0:46)", 26 × 1.2) run straight across the disc's x range. This is the same kind of collision the
  lead flagged in 01 and 09, and a real poster does not cover it.
- **Logos under the tag and caption.** `MediaSpec.Poster` is chosen as soon as a clip id exists, before `info` or any poster
  URL exists. If the lookup is slow, fails, or the key is refused, the matchup art (120 px logos at ≥ 160 px) shows under
  "LATEST GOAL" or "RECAP", the disc and the caption for as long as that lasts. Screenshot 28 shows the tag over the away logo.
- **Videos off.** While videos are off (`videoOff`) the media still shows a play disc and RECAP or LATEST GOAL for something that
  can't play. `HubPanelModel` does not carry `videoOff`.
- **Must fix before merge** (cheap, and the owner will see it):
  - Use `Poster` only when `info?.posterUrl ?: info?.thumbnailUrl` is non-null **and** videos are not off. Otherwise use the
    normal `Art` with its disc and caption ("Recap not posted yet" or the goal-soon caption, or a videos-off caption).
  - Below about 200 px, draw the play disc at 64 px, or drop it (it is decoration only, U-N11).
- **Lead's question on 01 and 09.** Both fixes are in the code.
  - Under a poster, `art` is null, so `MatchupArt` draws no centre disc and no caption (`HubPanel.kt:961, 968`).
    The "LIVE · 2ND 15:51" / "FINAL/SO" collision is gone.
  - The headline now lays out the home name unweighted and the away name with `weight(1f, fill = false)`
    (`HubPanel.kt:877-879`). At Default and Largest "Toronto Maple Leafs (1) vs. Boston Bruins (1)" fits (screenshots 25 and 28).
  - Remaining edge cases are in M5.

### M5. Headline measure versus layout
`HubNames.kt:85-90`; `HubPanel.kt:861-880`.
- The plan measures one string. The layout draws four `Text`s, each rounded up to whole pixels, with the away name measured
  last into what remains.
- When the whole line fits by less than about 3 px, the four-part layout is wider than the measure and the away name ellipsizes
  ("Columbus Blue Jacket…") instead of stepping down 2 px.
- In the `ellipsis` case both names get `weight(1f, fill = false)`. That is the equal-split cap §15.2 removed: a short home
  nickname leaves its half unused while the long away name is cut.
- **Fix:** fit against `maxPx - 4` (or sum the four parts, rounding each up). In the ellipsis case give the home name its own
  width and only the away name the weight, or give weights in proportion to the measured widths.

### M6. A focused card that leaves the board drops focus
`SportsScreen.kt:322-335`.
- §1.2 says: "a game that vanishes is removed (focus moves to its neighbour by index)". The code has no handling for this.
- When `focusedCardId` is not in `cards` after an update, the LazyGrid item leaves composition and focus goes to the root.
  The next D-pad press runs an initial search, which can jump to the title row.
- If the panel had focus on that game, `shownCard()` falls back to the initial-focus game, and the frozen slots now act on a
  **different** game: slot 1 FOLLOW would follow the wrong game.
- Rare for the NHL (games are seldom pulled). Daily for the `/score/now` date roll-over (see M1).
- **Fix:** an effect keyed on the card ids. Remember the focused card's index; if its id is gone, focus the card now at
  `min(index, last)`. Leave the panel if its game is gone.

### M7. The detail repo never sees the newer board snapshot of the shown game
`SportsViewModel.kt:714-715`.
- `startFetch` only re-runs on focus or on entering the panel. Re-polls do not call `detail.show(newSnapshot)`.
- The repository derives its phase from the snapshot it was given plus the detail's own `fetchedPhase`. A pre-game game that
  goes live while the user rests on its card therefore keeps the 5-minute pre-game cadence until the next detail fetch (up to
  5 minutes of season rows on a live game). The channel match (which treats upcoming and imminent differently) is not run again.
- **Fix:** in the merge collector, when the shown game's phase (or snapshot) changed, call `detail.show(game)` once (no dwell)
  and update `fetchedPhase`.

### M8. Test gaps and tests that cannot fail
- **`HubPanelFitTest.theHeadlineNamesGiveWayAndNoScoreIsEverCut`** checks the plan arithmetic only. It passed while the screen cut
  "Toronto Maple …". Add a composed test: set `HubPanel` with TOR at BOS at factors 1.0 and 1.2, and assert that the away name's
  `TextLayoutResult.hasVisualOverflow` is false (via `onTextLayout`, or a `SemanticsNode` text-layout check).
- **`HubSheetsTest.boxScoreFocusesTheAwayTabSwitchesAndRetries`** never presses Retry and asserts `retries == 0`. The Retry part
  cannot fail. After the Failed state it also does not check where focus went (H1).
- **No test** for the integration's focus-trap fix: Left from the first title chip or the first day chip must not land on a slot.
  `SportsScreenTest` has no case for it.
- **No screen-level test** that focus returns to the played row after a clip (re-key on `clipEpoch`), to slot 1 after a recap, or
  stays on the clip layer under sheet changes (H2).
- **No test** for `failureText` with frozen slots (H3).
- **Goldens (§15.4).** None exist for the hub card, panel, sheets or clip bar. Each layout bug in this branch (two sheet frames,
  headline cut, disc under disc, the grey slab) was found only on the emulator. **Before merge:** at least the panel (live, final,
  pre-game) and one card row at Default and Largest. The sheets and the clip bar can follow.

---

## Low

- **L1** `SportsViewModel.onTrimMemory` (`:377`) has no caller: `MainActivity.onTrimMemory` only calls `guideTrim`. §8.3's cache trim
  never runs. Wire it in, or delete it.
- **L2** Hard-coded English outside the strings file:
  - `ClipPlayerLayer.clipHints` (`:238-240`) uses "OK ", "← → ", "Back ". The page uses `sports_footer_keys_ok` / `_back`.
  - The headline's " vs. " and its content description (`HubPanel.kt:870, 878`).
  - `nflLiveRows` " & Goal" / " & " (`HubPanel.kt:486`).
- **L3** `buildPanel()` stamps `nowMs = nowUtcMs()` into every `HubPanelModel`. No two models are ever equal, so every emission of any
  of eight merged flows recomposes the panel and runs `HubPanelContents.build` (about 30 string formats). Cheap on a phone, a
  small cost on a Stick. Round `nowMs` to the minute, or keep it out of `equals`.
- **L4** Channel sheet rows are 96 px with three lines (26 + 22 + 22) when there are several playlists or variants. At Larger and
  Largest that needs about 110 px, so the third line is clipped (unverified: line heights estimated). Let the row grow, or drop
  the third line at large sizes.
- **L5** A failed lookup (Resolving → `fail` → Idle) re-keys the Goals sheet. Focus goes to `lastPlayed` (the clip before), or the
  latest goal, instead of the row the user pressed.
- **L6** `DayStrip.kt:135`: the label falls back to an ISO date (`today.toString()`) when a chip's key does not parse. That should not happen for
  NHL or CHL, but if it does the user sees "2026-11-24".
- **L7** `ClipThumbnail` sizes its Coil request with `width.value.toInt()` (dp, not px). That is correct only while 1 dp = 1 px at 1080p.
  Use `LocalDensity` as `HubMedia` does.
- **L8** Disabled slots draw only their ink at 40 %. The fill and border stay at full strength. §1.5 says the slot is drawn at 40 %. Cosmetic.
- **L9** `rememberHubMeasure()` is called in every `TeamRow`: two text measurers per card, about 32 per board, each with its own cache.
  Hoist one to the grid.

---

## Checked and sound

- **No graphicsLayer, clip or blur on any ancestor of the surface.**
  - The page root uses `drawWithContent` / `drawBehind` only (`SportsScreen.kt:269-273`).
  - `AppRoot` adds a background-only Box for SPORTS and changes the scrim value only. `HubMedia`'s `clip` is inside the page,
    which is a sibling of the SurfaceView, not an ancestor.
  - No `blur` anywhere in scope.
- **Clip hole.**
  - `clipHoleFull` gives FULL only after `playClip` and only while the playback target is the clip or an older tune.
  - `beneath[0]` is not updated while the hole is the clip's.
  - The black scrim is keyed on `tuneId`, so each queued clip is black until its first frame (`CLIP_BLACK_MAX_WAIT_MS` = 10 s).
  - Subtitles are lifted above the bar.
  - `_ui` is cleared before `Resume` is emitted.
- **Back hold.**
  - The layer holds Back for its whole life. On dispose it releases only if no sheet is open and the panel is not focused
    (`holdsNothing`).
  - The page re-asserts the hold when `clipActive` turns false.
  - The panel handles Back on key up and consumes key down.
- **Panel keys.**
  - Left and Right go only between usable slots; Right from the last slot exits; Left from the first is consumed.
  - Up and Down are consumed.
  - Menu on a card acts on key up; Left from the left column on key down.
  - With no enabled slot, `enterPanel` returns null and nothing happens.
- **Frozen slots.** The slot set is frozen by action. Live → final keeps FOLLOW in slot 1 (§2.4). Matcher results change labels
  in place.
- **Fast D-pad and stale detail.** `buildPanel` takes the detail only when `ds.detail.id == g.id`. The dwell job is cancelled per
  move, so another game's late answer is never shown.
- **Fetch double-show.** `showNow` matches the repository's contract (a second `show` of the same id clears `dwellUntil`). OK starts at once.
- **Pause and demand.**
  - `detail.pause(playing)` and `SPORTS_TAB` demand follow the clip state.
  - `onLeft` drops demand, closes the clip session, and stops the detail.
  - `onCleared` closes without resuming (R-N1).
- **Follow.** It calls `repo.follow(if (followed) null else game)`, exactly as `main`'s `onCard`. Picking a channel emits `SportsEffect.Tune`
  only, with no follow (`SportsViewModel.kt:659-661`).
- **Videos off.** `videoOff` disables Watch recap and the live Goals slot and hides the Highlights chips. The Refused toast carries a
  reason and a next step. Exception: the media area (M4).
- **ClipSession.**
  - `CLIP_EXPIRED` gets exactly one fresh lookup per clip index at the playhead; a second expiry shows the error card.
    `retry()` resets the counter.
  - "Up next" shows for `HUB_UP_NEXT_MS` behind a `generation` guard, and Back during it cancels.
  - Ownership loss ends the session without resuming.
  - Captions toggle only when a text track exists; English is preferred.
  - The state never passes through Idle between queued clips, so the layer is not torn down mid-queue.
- **Day strip and boards.**
  - Focus costs nothing; OK makes one request.
  - Past days are cached (≤ 15).
  - A stale board from another day or league shows as Loading (`isFor`, `boardLeague`).
  - The NFL strip uses only the board's keys.
  - `settled` stops the initial-focus rule from pulling focus after a day change or Retry, until the next board.
- **Text.**
  - Every style in scope is built in composition through `GuideUi.sans` / `mono` → `textSp`. No style is built at class load.
  - No `.sp` literals.
  - No text below 22 design px.
  - `.dp` is used as design px under the root's density scale, as the file header says.
  - Card names shrink 28 → 24 and then ellipsize. Screenshot 25 shows "Montréal Canadiens" and "Golden Knights" fit at Largest.
- **Errors.** Every toast and failure string in `strings_sports_hub.xml` carries a reason and a next step (except H3's misuse, and
  the informational channel line "No channel found in your playlists").
- **Mock-up departures.** NEW ribbon, PREVIEW, NBA, Soccer, All Sports and the search and profile icons are absent. Follow is slot 1. The channel
  line ("On TV: … · in your guide · +3 more") and Channels (n) are present. Day chips read "Yesterday · Nov 23" (design §3.1)
  instead of the mock-up's upper case, which is a documented departure.
- **Per-second ticks.** None. The title clock and "Updated" text tick every 15 s inside their own composables. Pills use the
  snapshot clock.
