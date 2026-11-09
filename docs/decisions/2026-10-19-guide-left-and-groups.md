# 2026-10-19 — Left in the guide grid, and how Groups, Search and Settings are reached

Status: **reconciled by the design meeting, implemented for the owner's trial; awaiting the owner's ruling.** It
supersedes the first bullet of `2026-10-18-guide-navigation-recents-and-artwork.md` ("Left on the cell at the
window's left edge opens Groups at once") and amends its second bullet (the Back ladder gains a stop).

## The problem

The owner asked twice, for two things that one key cannot both do:
- 18 Oct: "how we get into the groups from the guide and up to the search and settings … it is hard to get over into
  it especially if there is a bunch of channels that have back in time as it just scrolls the guide."
- 19 Oct: "when i am on a channel with catch up and try to scroll left on the guide it just goes over to the group
  picker."

The 18 Oct answer made a single Left on the cell clipped by the window's left edge open Groups. The window starts at
`floor30(now) − 30`, so the on-now programme is clipped whenever it began more than 30–59 minutes ago, and always for
long programmes. On a catch-up channel the previous programme was then unreachable with a single press. Worse, the
rule depended on the clock: the same row opened Groups at 12:22 and browsed at 12:10, and once it browsed it walked
back through every catch-up day, which is the 18 Oct complaint again.

## The meeting

Proposer (Opus) with five candidates; survey of thirteen competitor guides (Sonnet); ease-of-use review and UI /
interaction-architecture review (both Opus); reconciled by the lead. Records: the meeting files are not kept in the
repo; the outcome is this note. Key facts the meeting established:
- Both reviewers accept the M3 Left rule (`docs/design/m3-guide.md` §3.4.2 LEFT, lead §7 item 4): Left browses while
  an earlier **watchable** cell exists, and opens Groups only when none does.
- TiviMate's documented guide behaviour (third-party help pages; no official key table) is **Left = back in time on
  catch-up channels, Back = Groups**. The product review's "Left at the channel column opens categories" is not
  supported. No surveyed guide overloads the first Left press while a past still exists to the left.
- The proposer's route "Back to the top bar, then Left opens Groups" fails off the tuned row (Back goes home first,
  and the next Left browses catch-up on the tuned channel), and asks a TiviMate user to learn a two-step gesture whose
  second step points the wrong way on screen. The ease-of-use review's TiviMate ladder won.
- The 18 Oct edge rule was an uncommitted hunk with no test, which is how the clipped-cell case got through.

## Rulings (the spec)

1. **Left in the grid follows the M3 rule.** Left moves to the previous cell while that cell is watchable; `LeftEdge`
   (no earlier watchable cell) opens Groups: on a row without usable archive (`archiveDays == 0` or suspect), Left from
   the cell containing now; on an archive row, Left at the archive limit (`watchableFrom`) or at `earliest`. Whether
   the focused cell is clipped by the window is irrelevant. A cell already in the non-watchable past (reached by RW
   or the day strip) moves Left normally until `earliest`. A held Left (`repeat > 0`) never opens Groups. The 18 Oct
   check in `GuideStateMachineImpl.gridKey` is deleted.
2. **Back from home opens Groups** (TiviMate). The Back ladder is: overlay or non-grid zone → grid; scrolled or
   non-home grid → home (tuned row, now); **home → Groups panel** on the current source (`Overlay.Groups.viaBack =
   true`); Groups opened by Back → top bar with **Search** focused; top bar → the exit step (hint, dialog or exit per
   the setting). Groups opened any other way (Left at the edge, Yellow, the Group chip, the Guide key) closes on Back
   or Right to where it was opened from, as today. OK on an entry applies the source and closes into the grid.
   Hold Back → full screen (ruling of 2026-10-17) is unchanged.
3. **Top bar focus.** The ladder lands on Search. Up from the panel or the header lands on Guide. Returning from
   Settings keeps the gear. Every exit from Search lands in the grid with the Guide tab current (I-19, device bug G4:
   Back from Search used to leave the user in the top bar, which read as a dead end on the device; that ruling is older
   and was tested on hardware, so it wins). Back in the top bar is the exit step from any tab. *(Amended by the review
   fix pass 2026-10-19, S2: the first text said "Returning from Search or Settings keeps the tab you left from", which
   neither the code nor m3-guide §3.4.3 did.)*
4. **Yellow opens Groups in the guide** (`GROUPS_PANEL`, guide context only; the player keeps Yellow = Audio). For
   the footer, the four colour keys count as one family: once any of Red, Green, Yellow or Blue has been seen, all
   four may be named.
5. **The Guide key inside the guide:** at home it opens Groups; elsewhere it jumps to now (as today). Inside Groups
   (however opened) it closes the panel to the grid at home, so the key that opened Groups also closes it (review fix
   pass, L3). From full screen it returns to the guide (unchanged).
6. **Down from the panel buttons lands on the Day chip.** Up from grid row 0 still lands on the Group chip. (Days from
   home stays 5 presses: Back, Back, Down, Down, OK.)
7. **Cues next to the focus.** Two 12 × 24 design px chevrons, drawn as static assets in the renderer's chrome pass
   (outside every row recording, no animation, no blur):
   - `EDGE_CHEVRON`, `color.accent`, at x 67–79 in the grid's left gutter, centred on the focused row, while the next
     single Left opens Groups (grid focused, no overlay).
   - `CLIP_CHEVRON`, in the focused title's colour (`color.canvas`; the focused cell is filled with `fg`), at the focused cell's left inner edge, while the focused cell starts before the window
     and Left will move (an earlier watchable cell exists). It says "there is more to the left" on a clipped catch-up
     cell that otherwise shows nothing to its left.
   - The label-column ↺ glyph grows from 12 to 20 design px so "this channel has catch-up" reads at 10 feet.
   The "Left opens Groups" probe is `GuideNavigator.leftEdge`, computed once per publish behind a cheap key over its
   inputs (focus, window data, rows version, now, earliest, suspect count, lineup size), never by `move(LEFT)`.
8. *(Superseded 2026-11-09: the guide has no footer, `2026-11-09-guide-bigger-grid.md`.)* **The footer names the next Left and the next Back stop**, in priority order from core; the UI measures and drops
   hints from the end, never wrapping. Sets (`[…]` = only when that key was seen or bound, as today):

   | State | Hints |
   |---|---|
   | GRID, home, Left browses | `← → ↑ ↓` Browse · `OK` Watch · `Back` Groups · `Hold OK` Options · `Hold Back` Full screen¹ · [Red] · [CH±] · [0–9] |
   | GRID, home, Left opens Groups | `← → ↑ ↓` Browse · `OK` Watch · `←` Groups · `Back` Groups · `Hold OK` Options · `Hold Back` Full screen¹ · [Red] · [CH±] · [0–9] |
   | GRID, not home, Left browses | `← → ↑ ↓` Browse · `OK` Watch · `Back` Now · [`Yellow` Groups] · `Hold OK` Options · `Hold Back` Full screen¹ · [Red] · [CH±] · [0–9] |
   | GRID, not home, Left opens Groups | `← → ↑ ↓` Browse · `OK` Watch · `←` Groups · `Back` Now · `Hold OK` Options · `Hold Back` Full screen¹ · [Red] · [CH±] · [0–9] |
   | GRID, empty source | `←` Groups · `Back` Groups |
   | Groups panel, opened by Back | `↑ ↓` Choose · `OK` Open · `Back` Search, Settings · `→` Guide |
   | Groups panel, otherwise | `↑ ↓` Choose · `OK` Open · `Back` Guide |
   | TABS | `← →` Choose · `OK` Open · `Back` Exit |
   | PANEL, HEADER, other overlays | unchanged |

   ¹ only when something is tuned, as today. "← Earlier" is not shown: the ↺ glyph, the bright past cells and the
   clip chevron already say it.
9. **The ruler's contextual label** reads TONIGHT, not THIS MORNING, for windows centred between midnight and 05:00
   on the current day (seen on the owner's screenshot: "12:22 AM" beside "THIS MORNING 11:30"). Open for the owner
   (review S3): from 23:30 to 23:59 the home window is centred after midnight, so the window holding the now line reads
   "TOMORROW" (`TimeTables.contextLabel`'s next-day branch). Not changed; see *Owner items*.
10. **Not done, on purpose:** a one-time "Back opens Groups" toast (the footer says it permanently at home); the
    proposer's "‹ Groups" label in the tab row (looks like a tab, collides with the wordmark); Left on the Guide tab
    opening Groups (the focus would jump from the top bar to the bottom-left); an archive-limit tick (the archive band
    already draws one); clamping the window jump onto a long programme (a separate navigator change).

## Press counts from home (D-pad only, no hold; Fire TV, Onn, Sony)

| Journey | Before (18 Oct) | Now | TiviMate (documented) |
|---|---|---|---|
| Previous programme on a catch-up channel | unreachable when the on-now cell is clipped (RW ×2) | 1 | 1 |
| Groups | 1 if clipped, else 4 | **1** (Back); Sony also Yellow or Guide | 1 (Back) |
| Groups from a row that is not the tuned row | 1 or 4 | 2 (Back, Back) | 1 |
| Search | 3 | 3 (Back, Back, OK) | n/v |
| Settings | 4 | 4 | n/v |
| Day strip | 5 | 5 (Back, Back, Down, Down, OK); 3 from row 0 (Up, Right, OK) | n/v |
| Exit, "Back twice" setting | 3 Backs | 4 Backs | 3 |

## Supersedes and amends

- `2026-10-18-guide-navigation-recents-and-artwork.md`, first bullet: superseded by ruling 1. Second bullet: the
  ladder gains the Groups stop (ruling 2) and lands on Search (ruling 3).
- `2026-10-14-settings-and-branding.md`, remote button mapping: Yellow's guide default is Groups (ruling 4).
- `docs/design/m3-guide.md` §3.4.2 LEFT, §3.4.3 TABS and PANEL, §3.4.4 Groups, §3.4.6 back ladder, §3.4.8, §3.5.2
  ruler contextual label (ruling 9), §3.6.1
  draw order, §3.8.3 item 1, §3.18.2 PROG_YELLOW, §3.18.3 footer; I-7 and I-8.
- `docs/review/product-review.md` §3.3's TiviMate claim ("Left at the channel column opens categories") is corrected
  by the survey: TiviMate's Left browses catch-up and Back opens Groups.

## Implementation record

Filled in by the work packages (WP-A core state machine, navigator, key map, key table, tests, §3.4 docs; WP-B
renderer cues, ↺ size, canvas assets, footer layout, ruler label, renderer and UI tests, §3.6/§3.18 docs).

### Implementation record — WP-B (renderer cues, ↺, canvas assets, footer layout, ruler label)

- **Edge cue** (`GuideRendererImpl.drawEdgeCue`): step 9 of the draw, in its own `guide-draw-chrome` section after
  `restoreToCount(outer)`, because the cue sits in the 16 px gutter left of `labelLeft` (81) that the content clip cuts
  away. One `drawAsset(EDGE_CHEVRON, …, ACCENT)` at x `panelLeft + round(3 s)` → `+ round(12 s)` (= 67 → 79 at 1080p),
  y = focused row mid ± 12 s; skipped when that mid is within 12 s of the rows area's top or bottom. Only when
  `leftEdgeCue && gridFocused`. Not in `drawLabel` (clipped) and not in any row pass, so no row key changes.
- **Clip cue**: drawn inside `drawFocused` (the focused cell is never recorded, so again no row key changes) when
  `clipCue && gridFocused`, at `round(max(cellLeft, timelineLeft) + 4 s)` (= `timelineLeft + 4` while clipped), 12 × 24
  centred on the row. The 16 px pad left a 0 px gap to the title, so while the cue is on the focused cell's title (and its
  leading ↺ / reminder dot) starts 4 px after the chevron (x `timelineLeft + 20`); off, it stays at the pad.
  **Deviation:** ruling 7 says `color.fg`, but the grid-focused cell is filled with `FG`, so an `FG` chevron is invisible.
  It uses the focused title's colour (`CANVAS`, #0A0D13), which is what the ease-of-use review asked for ("the title's
  colour"). The edge cue keeps `color.accent`.
- **↺**: the label-column glyph is a new constant `LABEL_GLYPH = 20` (solid and hollow); the in-cell glyph keeps
  `CELL_GLYPH = 12`. `LABEL_ICON_GAP` (8) unchanged, so the name's width shrinks by 8 px. `GuideAssets` now rasterises
  the ↺ mask 2× at 20 px (40 px at 1080p) so neither size is upscaled.
- **Assets**: `GuideAssets.chevron()` rasterises the polyline (9.5, 2.5) → (2.5, 12) → (9.5, 21.5), stroke 3, round
  caps and joins, as an `ALPHA_8` mask at its drawn size (`ceil(12 s) × ceil(24 s)`); `CLIP_CHEVRON` shares the
  `EDGE_CHEVRON` bitmap. `AndroidGuideCanvas` needed no change (non-shadow assets go through the generic stretched
  mask path); the counting/recording canvases have no exhaustive `when`.
- **Footer** (`FooterHints`): a measuring `Layout`. Hints in list order, 36 px apart; the first one whose right edge
  would pass `min(parent width, 1792)` ends the row, with everything after it. Never wraps. Each hint carries the test
  tag `footer_hints_<i>`.
- **Ruler** (`TimeTables.contextLabel`): same-day windows whose mid hour is 0–4 read TONIGHT (unless EARLIER TODAY
  applies first).
- **Tests**: `GuideRendererTest` +3 (edge cue once at 67 × s, not when unfocused, not off-screen, 134 at 2×; clip cue
  position, colour, title push, absent when off or unfocused; label ↺ 20 / in-cell 12, solid and hollow);
  `RowRecordingTest` +1 (toggling either cue re-records nothing, every row pass replays); `RendererAllocationTest` +1
  (both cues on, < 1 KB over 1,000 frames); `TimeLabelsTest` +1 (00:22 with a 23:30 window → TONIGHT, mid 04:59 →
  TONIGHT, 05:00 → THIS MORNING, 09:00 with a 00:30 window → EARLIER TODAY); `AndroidGuideCanvasTest` +1 (chevron ink,
  tint, open right side, 24 × 48 mask at 2×, shared mask, 40 px ↺ mask); new `FooterHintsTest` (3: overflowing fake
  list drops from the end at 1792, a narrower parent wins, a short list is placed whole).
- **Golden**: `guide-reference-1080p.png` re-recorded; 287 pixels changed, all at x 249–349 (the 20 px label ↺ and the
  names it narrows, and the in-cell ↺ now drawn from the finer mask). Intended.
- **Docs**: `docs/01-design-reference.md` tokens (`glyph.archive.label`, `glyph.archive.cell`, `cue.edgeChevron`,
  `cue.clipChevron`, `footer.width`). The m3-guide §3.6.1 / §3.6.3 / §3.18.3 / §5.1 text is handed to the lead to merge.

## Implementation record — WP-A (2026-10-19)

Core state machine, navigator, key map, key table, tests and the §3.4 docs. Not committed; the lead commits.

**What changed**
- `GuideStateMachineImpl`: the 18 Oct clipped-edge check in `gridKey` is deleted; `LeftEdge` with `repeat == 0` is the
  only grid route to Groups by Left. `refreshLeftEdge()` runs in every `publish()`: a `Hash64` key over focus (row,
  cellStart, cellEnd), `frame.data` (seq + identity), `frame.rows` (version + identity), `nowMin`, `earliest`,
  `suspect.size` and `lineupSize()`; the probe (`navigator.leftEdge`, or `lineupSize() == 0` with no focus) runs only
  when the key changes. It sets `frame.leftEdgeCue` / `frame.clipCue` (GUIDE, no overlay, zone GRID, focus present),
  dirties the frame on change, and refreshes the footer when a second key over its non-focus inputs (mode, zone, tab,
  overlay kind + `viaBack`, probe, home) changes. `isHome()` compares against `homeWs`, cached in `recomputeLimits()`
  (which `tick()` runs every minute): no allocation. Back ladder: home → `openGroups(viaBack = true)`; Groups `viaBack`
  Back → close + `enterZone(TABS, tab = TAB_SEARCH)`, otherwise `closeOverlay()`; Right unchanged. `enterZone` gained
  `tab` (default Guide) and `chip` (default Group); panel Down → Day chip. Guide key in GUIDE: GRID at home → Groups,
  else zone GRID + jump to now. `footer()` emits the ruling 8 sets in priority order; `tabsKey` Left/Right refresh it.
- `GuideNavigatorImpl`: `left()` is `leftEdgeAt()` + `horizontal()`; `override fun leftEdge()` calls the same
  `leftEdgeAt()` (scratch fields only, no allocation).
- `FakeNavigator`: Left uses the real `curWatchable`/`targetWatchable` rule (`leftEdge` override, not recorded in
  `moves`); RESOLVE keeps the window like the real navigator (see deviations).
- `DefaultKeyMap`: guide Yellow = `GROUPS_PANEL` (player keeps `AUDIO`); `COLOUR_KEYS` and `seenWithColourFamily()`.
  `GuideViewModel.HINTED` += `GROUPS_PANEL`, and `keyHints()` applies the colour family to `seen`.
- `keys.csv`: grid_home BACK → Groups `groups:3`; new `grid_home_clipped_archive` LEFT → moved; grid_home OPEN_GUIDE →
  Groups; `tabs_search` LEFT → `tab:0`; panel DOWN → `chip:DAY`; new `groups_via_back` BACK → TABS `tab:1` and RIGHT
  → GRID home; back rows `full_returned`/`grid_idle_home`/`ask_home`/`none_home` → GRID Groups (the `home` check still
  holds: Back at home moves no focus); new `grid_empty_source` BACK → Groups; settings BACK → `tab:2`. Preconditions:
  `exit_hint`, `exit_dialog`, `exit_dialog_cancel` gain a Back; new `grid_home_clipped_archive` (clock + 100 min, with a
  `check` that the focused row-0 cell is clipped) and `groups_via_back`. Both navigators run the table
  (`:core:test` finalizes `stateOnRealNavigator`).
- Tests: probe = Left result in the navigator's 10 000-walk property test; `GuideBehaviourTest.LeftEdgeCue` (cue =
  Left result random walk, archive failure / tick / window staleness, `leftAtTheEarliestCellOpensGroups`, clip cue,
  cues only in GRID, held Left); `BackLadder` rewritten (`backLadderReachesGroupsThenSearchFromAnyRow`,
  `backAfterLeftOpenedGroupsReturnsToGrid`, `rightClosesGroupsOpenedByBackIntoTheGrid`, `topBarLandingTab`, exit in
  4/3 Backs); `noDeadEndsBackAlwaysReachesExit` keeps its 10-Back bound (longest ladder now 7 — *corrected by the fix pass: 8, 10
  with a reminder card and a PIN prompt; the bound is now 12*); one
  `FooterHintsFromKeyMapTest` case per state; `DefaultKeyMapTest` split for Yellow plus the colour family.
  `FiveKeyReachabilityTest` unchanged and green. `KeysOnDeviceTest` (I-4): "LEFT on a clipped catch-up cell → moved"
  (skipped with a log line when no catch-up row has a clipped on-now cell at that time of day), "BACK from home →
  Groups", "BACK, BACK → TABS with Search focused"; its reset no longer walks the ladder (`backToGrid()`: Right closes
  Groups, OK on Guide leaves the top bar). Not run on a device.

**Counts** — `:core:test` 3,187 → 3,221; `stateOnRealNavigator` 317 → 344; all green.

**Deviations**
- The colour-family rule is applied in two places: the fallback in `footer()` and `GuideViewModel.keyHints()` (one
  line beyond `HINTED`). Without the second, the production path (KeyHints = key map ∩ seen) would never name Yellow
  until Yellow itself was pressed, which is the S2 problem.
- `FakeNavigator` RESOLVE now keeps the window (it used to re-page the window to show a clipped cell's start), so the
  `grid_home_clipped_archive` precondition holds on both navigators, as the real navigator's RESOLVE already did.
- The cues also require a focus (`frame.focus != null`): with an empty source there is no row to draw beside, while the
  footer still names `← Groups · Back Groups`.
- The Guide key opens Groups only in zone GRID at home; from TABS/PANEL/HEADER it returns to the grid at now as before.
- m3-guide §3.4.6 still described the 14 Oct "home → FULL" ladder; it is rewritten for the 2026-10-19 ladder.

## Implementation record — review fix pass (2026-10-19)

Fixes for `docs/review/2026-10-19-guide-left-and-groups-review.md` (B1, S1–S6, L1–L4). Not committed; the lead commits.

- **B1 (Back dead end when now is in a sliver):** home is now the focus `goHome()` produces. `isHome()` keeps its
  allocation-free row and `homeWs` checks, accepts the cell containing now, and otherwise compares with the cached home
  cell (`homeCellStart`/`homeCellEnd`, re-resolved through `navigator.focusAt` only when a key over home row, now,
  earliest, latest, window data and lineup size changes). `goHome(onlyIfMoved = true)` in `backLadder` returns false
  when the focus would not change, and the ladder falls through to `openGroups(viaBack = true)`. No navigator API
  change was needed. The footer (`Back · Groups`) and the Guide key at home follow `isHome()`, so both are fixed too.
  New `SliverAtNowTest` (always on `GuideNavigatorImpl`): Back from home with a 2-min sliver at now reaches Groups,
  the top bar on Search, the exit hint and the exit; from another row Back goes home then opens Groups; nothing tuned;
  the Guide key; and a 200-walk no-dead-ends property over rows with a sliver every 30 min. The test fails 5 of 6
  without the fix.
- **S1:** `refreshLeftEdge` returns before the footer key while `holdingVertical`; `release()` rebuilds the panel and
  footer, and the next publish re-keys it. The cues are still set per row (frame state only). Test
  `GuideBehaviourTest.Hold.verticalHoldDoesNotRebuildTheFooter` (held DOWN over rows 1–6, same footer instance, rebuilt
  on release).
- **S2:** ruling 3 amended to the code (Search exits to the grid with Guide current; only Settings keeps the gear).
- **S3:** m3-guide §3.5.2 gives the ruling-9 formula; §3.5.2 added to *Supersedes and amends*; the 23:30–23:59
  "TOMORROW" case is an owner item below (behaviour unchanged).
- **S4:** `KeysOnDeviceTest` finds the clipped catch-up row when the sequence runs, re-checks the start after the reset
  (row, contains now, clipped, clip cue on = Left moves, not suspect) and skips with a log line otherwise; the search
  skips suspect channels (`frame.suspectArchive`). Not run on a device.
- **S5:** the longest ladder is 8 (group menu → Groups → HEADER → GRID → home → Groups via Back → TABS → exit hint →
  exit), 10 with a reminder card and a PIN prompt; corrected here, in m3-guide §3.4.6 and in the test comment; the
  bound is 12.
- **S6:** `keys.csv` rows: `panel,UP` checks `tab:0`; new `panel_stale_tab` (top bar left on Search) and
  `header_no_panel` (empty source, no panel buttons) Up → `tab:0`; `groups_via_back_zapped` (CH− inside a Back-opened
  panel) Back → TABS `tab:1`; `groups_from_tabs` (Yellow in the top bar) Back → TABS; `groups_via_guide` Back → GRID
  `same` and OPEN_GUIDE → GRID `home`. Both navigators.
- **L1:** `returnedFromFull` removed (it was only written).
- **L2:** the footer's fallback (no `KeyHints` yet) applies the colour family to Red as well as Yellow
  (`DefaultKeyMap.seenWithColourFamily`), matching `GuideViewModel.keyHints`; `FooterHintsFromKeyMapTest` updated.
- **L3:** the Guide key inside Groups (menu included) closes the panel to the grid at home (ruling 5 amended).
- **L4:** in the guide, Yellow and the Guide key during number entry commit the entry like OK and stop there, so Groups
  never opens under the jump; in FULL they commit and run as before. Test
  `GuideBehaviourTest.NumberEntryFlow.yellowAndGuideCommitTheEntryWithoutOpeningGroups`.
- **Deferred:** L5–L9 (see the review's fix-pass section).

**Counts** — `:core:test` 3,221 → 3,235; `stateOnRealNavigator` 344 → 358; all green.

## Owner items

- **TOMORROW at 23:30–23:59 (review S3).** From 23:30 the home window starts at 23:00 and is centred at 00:30 the next
  day, so the ruler's contextual label beside the now line reads "TOMORROW" — the same kind of mismatch as the
  "THIS MORNING" screenshot, half an hour earlier. Should a window centred before 05:00 the next day read TONIGHT while
  now is after 18:00? If yes: one branch in `TimeTables.contextLabel` and a `TimeLabelsTest` case at 23:45.
