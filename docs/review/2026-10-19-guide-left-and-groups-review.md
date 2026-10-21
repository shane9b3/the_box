# Review — guide Left, Groups and the Back ladder (2026-10-19)

Scope: the uncommitted change that implements `docs/decisions/2026-10-19-guide-left-and-groups.md` (rulings 1–9 and
both implementation records). Other uncommitted work in the tree (channel editor, TMDB posters, spelling, boot blank)
was ignored except where it touches the same lines. Read-only review; Gradle and the emulator were not run. Line
numbers are for the working tree. `GSM` = `core/src/main/kotlin/app/thebox/core/guide/state/GuideStateMachineImpl.kt`.

## Verdict

**Changes requested: one blocker, then fit for the owner's trial.** Rulings 1, 2, 4, 5, 6, 7 and 8 are implemented
as written and the tests mostly cover them. Specifically:

- The probe is the Left rule. `GuideNavigatorImpl.left` and `leftEdge` share `leftEdgeAt`
  (`GuideNavigatorImpl.kt:107-121`).
- The clip cue cannot be on while Left would open Groups (`GSM:2229`, `clipCue = active && !leftEdge && …`).
- The 18 Oct clipped-edge check is gone (`GSM:568-600`).
- `viaBack` survives `onSources` and CH± inside the panel, because both use `o.copy(...)` (`GSM:1693`, `GSM:1394`).
  Back from that panel still goes to TABS on Search.
- Groups opened by the Group chip, Yellow, Left or the Guide key closes to the zone it was opened from, because
  `closeOverlay` leaves `zone` alone (`GSM:1325-1330`, `GSM:1367`).
- No allocation per publish while the probe's key is unchanged, and none per frame in the renderer.
- No row recording depends on the cues (`RowRecordingTest.theLeftCuesChangeNoRowKey`).

The blocker is a dead end in the Back ladder. It existed before this change, but it now cuts off Groups, Search and
the exit.

## Blockers

### B1. Back never leaves a "not home" state when `now` falls in a sliver on the home row

`isHome()` requires the focused cell to contain `now` (`GSM:1256`). `goHome()` places the focus with
`navigator.focusAt(homeRow, nowMin)` (`GSM:1262-1265`). On the real navigator, `focusAt` resolves a sliver to the
*next* focusable cell (`GuideNavigatorImpl.resolveAt`, `:166-176`), and that cell starts after `now`.

Slivers are GAP pieces shorter than 5 min (`RowCellsBuilderImpl.kt:62-67`, `GridConstants.SLIVER_MIN = 5`). They
are routine:

- a 1–4 min gap between two programmes;
- the stub of any gap cut at a local hour boundary, for example 18:58–19:00.

While `now` is inside such a piece on the tuned row (or the current row when nothing is tuned):

- **Back is stuck.** Every Back runs `goHome()` (`GSM:1222`) and lands on the same focus. That focus is still not
  home, so Back never reaches Groups, Search or the exit step. Each press also re-runs `rebuildPanel(newCell = true)`
  and re-arms the description load.
- **The footer is wrong.** It says `Back · Now` (`GSM:2284`) and offers `[Yellow] Groups` instead of `Back · Groups`.
- **The Guide key does nothing visible.** It calls `jumpToNow()` instead of opening Groups (`GSM:553-557`).

This lasts up to 4 minutes at a time, several times an hour on EPGs with small gaps. It breaks the decision's core
promise that Back reaches Groups in 1–2 presses, and the plan's "no dead ends".

**Why the tests miss it:**

- `noDeadEndsBackAlwaysReachesExit` and every `GuideBehaviourTest` walk run on `FakeNavigator`, which has no sliver
  handling and no sliver rows.
- The real navigator only runs the key table (`stateOnRealNavigator`), whose fixture has no gap at `now`.

**Fix:**

1. **Define home as the focus `goHome()` produces.** Add a non-allocating
   `GuideNavigator.homeCellStart(row, atMin, ctx): Int` that runs `resolveAt` on the scratch fields and returns
   `cS`. Then either:
   - cache it next to `homeWs`, keyed on `edgeKey` plus `tunedIndex`, and make `isHome()` compare
     `f.row == homeRow() && f.windowStartMin == homeWs && f.cellStart == homeCellStart`; or
   - keep the `now ∈ cell` test and OR it with `f.cellStart == homeCellStart`.
2. **Belt and braces in `backLadder`:** if `goHome()` would produce a focus equal to the current one, fall through to
   `openGroups(viaBack = true)`.
3. **Tests:**
   - On the real navigator: a row with a 2-min gap containing `now`, tuned. Back opens Groups in one press, and the
     footer says `Back · Groups`.
   - A real-navigator variant of `noDeadEndsBackAlwaysReachesExit` over rows with sliver gaps.

## Should-fix

### S1. Held Up/Down now rebuilds the footer per row

`setFocus` skips `rebuildPanel` while `holdingVertical` (`GSM:2071`) so a 25 Hz hold does no per-row UI work.
`refreshLeftEdge` undoes that:

- Its footer key `fk` includes `leftEdge` and `isHome()` (`GSM:2236-2240`).
- `leftEdge` flips between archive and non-archive rows, and `isHome()` flips when the hold leaves the tuned row.
- Each flip runs `refreshFooter()`, which allocates a `Target`, an `ArrayList` and the `FooterHint`s.
- It then emits a new `GuideUiState` and recomposes the footer, mid-hold.
- It also lets the footer describe a row the panel does not show yet.

**Fix:** while `holdingVertical || holdingHorizontal`, record the new `fk` and set `footerPending = true` without
calling `refreshFooter()`. `release()` already ends in `rebuildPanel` → `refreshFooter`. Add a
`RendererAllocationTest`-style count over a held DOWN across mixed archive and non-archive rows.

### S2. Ruling 3 contradicts the code and m3-guide on returning from Search

- **Ruling 3 says:** "Returning from Search or Settings keeps the tab you left from."
- **The code:** `onSearchClosed` forces `zone = GRID, tabFocused = TAB_GUIDE` (`GSM:1965`, I-19 device bug G4).
- **m3-guide §3.4.3 (new paragraph) says:** "every exit from Search lands in the grid with Guide current (I-19)".

So after Back, Back, OK (Search), the next Back lands in the grid, not on the Search tab as the note promises.

**Fix:** amend ruling 3 in the decision note to say Search exits to the grid with Guide current (I-19 wins; give the
reason). Alternatively, take the change to I-19 back through review. Do not leave the note and the code disagreeing.

### S3. Ruling 9 is not in m3-guide; the same defect remains just before midnight

- **Doc drift:** `docs/design/m3-guide.md:1637` (§3.5.2) still gives the old formula (`hour < 12 ? "THIS MORNING"`).
  The decision's "Supersedes and amends" list does not name §3.5.2.
- **Same defect, 23:30–23:59:** from 23:30 to 23:59 the home window is `ws = 23:00` with `mid = 00:30` the next day.
  `TimeTables.contextLabel` takes the `1 -> TOMORROW` branch (`TimeTables.kt:91`). The window holding the now line
  therefore reads "TOMORROW". This is the same class of defect as the owner's screenshot, half an hour earlier.

**Fix:**

- Update §3.5.2 and add it to the note's amend list.
- Ask the owner whether `dMid − dNow == 1 && hour(mid) < 5 && hour(now) >= 18` should also read TONIGHT.
- If yes, add a `TimeLabelsTest` case at 23:45.

### S4. The I-4 clipped-row sequence can fail instead of skipping

`KeysOnDeviceTest.findClippedArchiveRow()` runs once, before all sequences. The "LEFT on a clipped catch-up cell"
sequence (#17) runs later, then re-asserts `start.cellStart < start.windowStartMin` in its `expect`. If a :00/:30
boundary passes or the on-now programme ends in between, the home window moves and the sequence *fails*.

The search also does not exclude suspect channels. On a suspect channel Left opens Groups by design.

**Fix:**

- In the loop, for `fromClippedRow`, re-check right after `backToGrid()` that `start` is clipped and contains `now`.
  Log and skip if not.
- In `findClippedArchiveRow`, require that the previous cell is watchable. Using `channelAt(r)` with a non-suspect
  archive is enough.

### S5. The ladder-length claim is wrong and the dead-end test sits at its bound

The decision note (implementation record), m3-guide §3.4.6 and `GuideBehaviourTest.kt:313` all say "longest ladder
is 7". The real worst case is 8: open Groups from the Group chip, open a group menu, scroll off home, then:

1. menu
2. Groups → HEADER
3. HEADER → GRID
4. home
5. Groups (via Back)
6. TABS
7. exit hint
8. exit

A reminder card (+1) and a PIN prompt (+1) make 10, which is exactly the `backs < 10` bound. Player layers in FULL
(sheet, quick bar) are counted by nothing in the comment. The walk is also blind to B1 (see above).

**Fix:**

- Correct the count to 8 in all three places.
- Raise the bound to 12 so a future stop does not silently tip it.
- Add the real-navigator variant described in B1.

### S6. Key-table coverage of the new rows

`core/src/test/resources/guide/keys.csv`:

| Row | Problem | Fix |
|---|---|---|
| `panel,panel,UP` | Check is `-`, so ruling 3 ("Up from the panel lands on Guide") is not pinned on either navigator. | Check `tab:0`. |
| HEADER → UP with no panel buttons | Same ruling, no row at all. | Add a row checking `tab:0`. |
| (missing) | No row shows `viaBack` surviving `applySource`. | `groups_via_back` + `ZAP_DOWN` precondition, then `BACK` → `TABS`, `tab:1`. |
| (missing) | No row shows "closes to where it was opened from" for a non-grid zone. | Yellow in TABS → Groups, then `BACK` → `TABS` with no overlay. |
| `grid_home,OPEN_GUIDE` | Does not pin that Groups opened by the Guide key is not `viaBack`. | Add a `groups_via_guide` precondition with a `BACK` → `GRID` row. |

`GuideBehaviourTest.backAfterLeftOpenedGroupsReturnsToGrid` covers the last item, but only on `FakeNavigator`.

## Later

**L1. `returnedFromFull` is dead state.** It is only written (`GSM:227`, `:447`, `:805`); the ladder no longer reads
it. The `returnToGuide` KDoc (`GSM:797`) still advertises it. Remove both.

**L2. Footer fallback before the first `KeyHints`.** `groupsKeyName()` names "Yellow" whenever any colour key was
seen (`GSM:2308`), whatever the key map says. The same fallback's Red hint (`keyHintsTail`) does not apply the
colour family, while the production path (`GuideViewModel.keyHints`) applies it to Red too. Fix: use
`DefaultKeyMap.seenWithColourFamily` in both fallback branches, or drop the fallback once `KeyHints` is always sent
at start.

**L3. The Guide key is swallowed inside Groups.** `groupsKey` maps it to `else -> Unit` (`GSM:1397`). On a Sony
remote, Guide opens Groups at home but cannot close it. Consider OPEN_GUIDE closing a non-`viaBack` panel, after an
owner ruling.

**L4. Number entry, then Yellow or Guide at home.** `numberKey` commits and lets the key run (`GSM:1614`), which opens
Groups. When the lookup answers, `onNumber` (`GSM:1635-1658`) moves the focus under the open panel, or replaces the
panel with the "not found" Number overlay. The pattern predates this change, but the new bindings make it easy to
hit. Fix: while `numberCommitting`, either do not open overlays, or close a non-Number overlay in `onNumber`.

**L5. Cues and footer under modal cards.** `refreshLeftEdge`'s `active` (`GSM:2227`) ignores `u.reminder`, `u.pin`
and a focused update card. The edge chevron stays drawn and the footer says `Back · Groups` while Back actually
dismisses the card. Fix: add `u.reminder == null && u.pin == null` to `active`, and treat those as "other overlays"
in `footer()`.

**L6. "Back · Guide" is wrong for Groups opened from the top area.** For Groups opened from TABS, PANEL or HEADER
(Yellow or the chip), Back returns to that zone, not the grid. Fix: use "Back · Close", or name the zone.

**L7. The probe key's suspect input.** It hashes `suspect.size` (`GSM:2222`), so a swap of one suspect channel for
another at the same size would keep a stale probe. Fix: use a suspect-set version counter.

**L8. Redundant footer refresh in `tabsKey`.** The `refreshFooter()` calls in `tabsKey` Left/Right (`GSM:686-687`)
are redundant: `fk` already includes `tabFocused`, and the TABS set does not depend on the tab.

**L9. Clip cue on non-watchable past cells.** The clip cue also shows on a clipped *non-watchable* past cell, where
Left moves to another non-watchable cell. Ruling 7 says "an earlier watchable cell exists". This is harmless (there
is more to the left). Align the ruling text with the code.

## Checked and found correct

- **Ruling 1:**
  - The 18 Oct `gridKey` check is deleted.
  - A held Left never opens Groups (`GSM:595`, `repeat == 0`).
  - `PREV_PROGRAMME` never opens Groups.
  - Probe equals the Left result: real-navigator property test (`GuideNavigatorTest`, more than 50,000 probes) and
    the state-level walk (`cueEqualsTheLeftResultOverARandomWalk`).
  - `grid_home_clipped_archive` sets up the world it claims: clock +100 min, tuned row 0 (7-day archive) is still the
    home row, and the `check` guards both the row and the clip on both navigators.
- **Ruling 2:**
  - Ladder order: `GSM:1219-1225`, `GSM:1367`, `GSM:688`.
  - From a non-home archive row the ladder only goes home and never opens Groups. `backLadderReachesGroupsThenSearchFromAnyRow`
    covers rows 0–9 × {none, FF, RW, Right}.
  - An empty source counts as home.
  - OK on an entry closes into the grid (`GSM:1378-1380`, and the PIN grant at `GSM:1094-1097`).
- **Ruling 4:**
  - Guide-only Yellow (`DefaultKeyMap.kt:51`); the player keeps AUDIO.
  - `HINTED` plus the colour family in `GuideViewModel.keyHints`.
  - The footer names the key bound to `GROUPS_PANEL`, never a hard-coded Yellow once `KeyHints` has arrived.
- **Ruling 5:**
  - Under a reminder card the card is modal first (`GSM:443`).
  - Under the exit hint, the hint closes and the key then runs.
  - From TABS, PANEL or HEADER it jumps to now.
- **Ruling 6:** `GSM:706`.
- **Ruling 7:**
  - The probe key covers every input `leftEdgeAt` reads (focus cell, data, rows, now, earliest, suspect, lineup size).
  - `isHome()` no longer allocates (`homeWs` is row-independent: `focusAt`'s ws depends only on now and the limits).
  - Both cues are static masks outside every row recording.
  - The edge cue is drawn after `restoreToCount(outer)` in its own section.
  - CLAUDE.md: no blur, no `graphicsLayer`, and dp = design px through `BoxDesignScale`.
- **Ruling 8:** every row of the table matches `footer()` (`GSM:2252-2300`). The UI drops hints from the end and
  never wraps.
- **Device tests:**
  - `GuideTestBase.goHome` and `KeysOnDeviceTest.backToGrid` close Groups with Right and leave TABS with OK on Guide,
    so neither walks into the exit step.
  - I-8 asserts the new ladder (Groups `viaBack` → TABS tab 1 → exit hint → exit).
  - I-7's Back closes a Left-opened panel.

## Fix pass — 2026-10-19

Each claim was checked in the code before fixing; all of B1 and S1–S6 held as written. Not committed. Tests:
`:core:test` 3,235 and `:core:stateOnRealNavigator` 358 (both +14), `:ui:testDebugUnitTest` and
`:app:compileDebugAndroidTestKotlin` green; the device suites were not run (emulator in use).

### Fixed

- **B1.** Home is now the focus `goHome()` produces. `isHome()` keeps its row and `homeWs` checks, accepts the cell
  containing now, and otherwise compares with a cached home cell (`homeCellStart`/`homeCellEnd`). The cache is
  re-resolved by `navigator.focusAt` only when a key over home row, now, earliest, latest, window data and lineup size
  changes, so it is allocation-free between changes. The cached start/end replace the proposed
  `GuideNavigator.homeCellStart`, so the navigator API is unchanged. Belt and braces: `backLadder` calls
  `goHome(onlyIfMoved = true)`, which returns false when the focus would not change, and the ladder then opens Groups
  (`viaBack`). The footer and the Guide key at home follow. New `SliverAtNowTest`, always on `GuideNavigatorImpl`:
  - a 2-min sliver at now on the tuned row: Back → Groups → TABS (Search) → exit hint → exit;
  - from another row: Back → home (`Back · Groups`) → Groups;
  - nothing tuned;
  - the Guide key opens Groups and closes it;
  - a 200-walk no-dead-ends property over rows with a sliver every 30 min.
  With the fix reverted, 5 of its 6 tests fail.
- **S1.** `refreshLeftEdge` stops before the footer key while `holdingVertical`. `release()` rebuilds the panel and the
  footer, and the next publish re-keys it. Test: `Hold.verticalHoldDoesNotRebuildTheFooter`, a held DOWN over rows
  1–6 (no-archive, no-EPG and archive rows) keeps the same footer instance, which is rebuilt on release. This is an
  identity check rather than the `RendererAllocationTest`-style count the review suggested.
- **S2.** Ruling 3 now matches the code and §3.4.3: Search exits to the grid with Guide current (I-19), and only
  Settings keeps the gear.
- **S3.** §3.5.2 gives the ruling-9 formula (hour < 5 → TONIGHT), and §3.5.2 is in the note's amend list. The
  23:30–23:59 "TOMORROW" case is recorded as an owner item in the note. Behaviour is unchanged.
- **S4.** `KeysOnDeviceTest`:
  - finds the clipped row when the sequence runs;
  - after the reset, re-checks row, contains-now, clipped, `frame.clipCue` (Left moves) and not suspect, and skips
    with a log line if any fails;
  - skips suspect channels (`frame.suspectArchive`) in the search.
- **S5.** The longest ladder is 8, or 10 with a reminder card and a PIN prompt. This is corrected in the note, in
  m3-guide §3.4.6 and in the test comment. The bound is 12.
- **S6.** `keys.csv` changes:
  - `panel,UP` → `tab:0`;
  - `panel_stale_tab` and `header_no_panel` Up → `tab:0`;
  - `groups_via_back_zapped` (CH− in a Back-opened panel) Back → TABS `tab:1`;
  - `groups_from_tabs` (Yellow in the top bar) Back → TABS;
  - `groups_via_guide` Back → GRID `same`, and OPEN_GUIDE → GRID `home` (L3).
- **L1.** `returnedFromFull` and its KDoc mention are removed. `KeySinkTest` in `:ui` has an unrelated local of the
  same name.
- **L2.** Both fallback branches use `DefaultKeyMap.seenWithColourFamily`. Red is now named by the family too, as on
  the production path. Two `FooterHintsFromKeyMapTest` expectations gain the Red hint.
- **L3.** The Guide key inside Groups (any opener, menu included) closes the panel to the grid at home. Ruling 5 and
  m3-guide §3.4.2 are amended.
- **L4.** In the guide, Yellow and the Guide key during number entry commit the entry like OK and stop there, so
  Groups never opens under the jump. In FULL they still commit and run. Test:
  `NumberEntryFlow.yellowAndGuideCommitTheEntryWithoutOpeningGroups`.

### Later (not done)

- **L5:** cues and footer under the reminder card or PIN prompt. This needs `footer()` to treat these as other
  overlays, which changes the footer sets.
- **L6:** "Back · Guide" for Groups opened from the top area. This is a wording change that needs a ruling.
- **L7:** suspect-set version counter in the probe key.
- **L8:** the redundant `refreshFooter()` in `tabsKey`. Harmless.
- **L9:** align ruling 7's clip-cue text with the code (`!leftEdge`).
- The review's suggested `RendererAllocationTest`-style allocation count for S1 (the identity test covers the
  behaviour).
