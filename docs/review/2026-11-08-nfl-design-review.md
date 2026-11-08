# NFL score bug: design review

Reviewer: adversarial design review (Opus), 2026-11-08. Subject: `docs/design/m6-nfl.md` ("NFL:n" = its line n),
proposed 2026-11-08, against `main` `73b7cdb`, owner rulings 1–24 (`docs/decisions/2026-11-05-nhl-score-bug.md`), the
mock-up `design/nfl/Touchdown.dc.html` ("the mock"), the ESPN fixtures and live log under
`core/src/test/resources/sports/nfl/` ("the log"), the SB / GA / PP designs and their reviews, and the code. Line
references are `file:line`.

Overall: the data work is careful, and most of it holds up. I replayed all 189 lines of the log. Every row of the §1.2
table matches:
- the score arriving before the play at 14:41:58;
- the XP never appearing as `lastPlay`;
- the +7 at 14:50:07, after a 107 s freeze;
- home-relative `yardLine` for both kickers;
- `possession` missing after all four kick-offs;
- no `Age` header on any answer.

The pill arithmetic (520), the rest boxes, the tab reach (65 ≤ 74) and the mock's keyframe conversions (× .531 / × .486)
all check out. So do the letter stagger (860 / 970) and 13 of the C1 / C5 values, which I recomputed.

The weak points are:
- the field position, which the contract cannot represent in the most common state;
- the try / two-point bookkeeping, which the design's own fixture breaks;
- the mapper / decoder boundary between two parallel WPs;
- the `ScoreBug.kt` seam, which carries most of the NHL-regression risk and is described in one table row;
- a ball sprite that leaves the pill's rounded ends.

---

## Blockers

### B1. `FieldPos` is resolved in the mapper from a possession that ESPN omits after every kick-off

NFL:178–195 (§1.3 `possession`, `field`), NFL:208 (§1.4), NFL:401–407 (§3.3 inference), NFL:293–294 (§2 `FieldPos`).

- §1.3 turns `possessionText` ("CIN 25") into `OWN`/`OPP` by comparing its abbreviation with the possessing team's.
  `FieldPos(zone, yard)` keeps only the result.
- In the log, the first `Down` after **every** kick-off has no `possession`: 14:36:07, 14:45:48, 14:53:10 and 15:07:25
  (4 of 4). See also `scoreboard-live-no-possession-after-kickoff-2026-11-08.json`: `possessionText "ATL 40"`, no
  `possession`.
- So at that point the mapper cannot decide OWN or OPP. It must emit `field = null` (or guess).
- §3.3 infers the side later, in the machine. But the machine only has a `FieldPos` with no territory, so it cannot
  rebuild the tail.

The result, on the most common transition in the game: the tab opens as "CIN BALL" with no field. On the next poll it
cuts to "CIN BALL | OWN 25". Worse, an implementer who "fixes" this in the mapper by guessing the receiver gets the
zone backwards whenever the guess is wrong. That happens on an onside recovery, or a fumbled return.

**Required.**
- Make the territory explicit and resolve the zone late:
  - `data class FieldPos(val territory: Side?, val yard: Int)`, with `territory` = whose half the ball is in and
    `yard` 1..50.
  - From `possessionText`, the territory is the competitor whose `team.abbreviation` matches the text's prefix. This is
    independent of `possession`.
  - From a kick-off `yardLine` (home-relative): `yl ≤ 50` → HOME at `yl`, else AWAY at `100 − yl`. 50 is midfield.
- In `:core` (machine or a pure helper), `TabTail.Field` becomes `OWN` if `territory == tab.side`, `OPP` otherwise, and
  `MIDFIELD` at 50. That uses the inferred side.
- Add an `EspnMapperTest` / `ScoreBugMachineNflTest` case on the no-possession fixture: after
  `scoreboard-live-plus7-away-kickoff`, the expected tab is "ATL BALL | OWN 40". The receiver is inferred, and the
  territory comes from the text.

### B2. `pendingTry` is cleared by the very snapshot that sets it, and the "release to PAT" rows are false on the fixture

NFL:365–377 (§3.2), NFL:393–399 (§3.3 table), NFL:614–625 (§5.4 table), NFL:825–827 and NFL:840 (§9.2).

1. **Order.** §3.2 clears `pendingTry` on "a shown snapshot whose play is `Down`". The +6 at 14:41:58 arrives on a body
   whose play **is** a `Down`: the stale "1st & Goal CIN 1", possession ATL, `lastPlay` Penalty.
   - If the clear runs after the set, `pendingTry` dies at once, and a later two-point conversion reads SAFETY.
   - If it runs before the set, it survives.
   - The design does not say which. Two implementers will diverge, and only one of them passes the fake's 100–110 s
     window.
2. **An unseen try shape.** §1.6 item 3 admits the two-point try's `situation` is unknown. If ESPN shows it as a real
   down ("1st & Goal at OPP 2", entirely plausible), clear-on-`Down` turns **every** two-point conversion into SAFETY.
   That breaks ruling 23 on the case the rule exists for.
3. **Release content.** §5.4 says a TD releases to "KC BALL | EXTRA POINT" with "PAT". On the fixture, the TD snapshot is
   the stale `Down`. The release therefore shows "ATL BALL | OPP 1" and "1st & Goal" for about 15–20 s, then flips to
   PAT. For a defensive TD it is worse: the stale `Down` names the *other* side, so the release cuts to the wrong team's
   colours, then CLOSE / OPENs one poll later.
4. **Gaps.**
   - `pendingTry` is set only on a *flagged* +6. Under `UNSEEN` demand, or past `GOAL_FRESH_MS`, a +6 sets nothing.
   - "Leaving LIVE" clears it, but a TD on the last play of a half has its try after the clock reads 0:00. ESPN may
     already be at `END_PERIOD` / `HALFTIME`; §1.2 shows 0:02 going straight to END_PERIOD.
   - `Kickoff` is not in the clear list.

**Required.** Define the try bookkeeping as a small rule set:
- **Set** `pendingTry = X` on any rise of exactly 6 for X, flagged or not. Also set it from a shown `Dead` whose
  `lastPlay` is a TD for X (`scoreValue == 6`): this covers the app starting during the try.
- **Clear** it on:
  - any other score change;
  - a shown `Kickoff`;
  - a shown `Down` whose `clock` or `period` differs from the clock and period recorded at the set (the clock does not
    run during a try: 11:12 throughout 14:41:58–14:45:48 in the log);
  - `reset()`.

  Do not clear it on a phase change alone.
- **Order:** the clear rules run on the *previous* state, before the set for the current snapshot.
- **Down column and tab:** while `pendingTry != null`, a `Down` identical to the previous shown `Down` (the stale body)
  renders as the try: "PAT" and "X BALL | EXTRA POINT". The §5.4 rows then hold.
- **Where the state lives:** `pendingTry`, the possession-inference memory and the held tab are written only in
  `advanceDisplay` when the shown entry changes, never in `view()`. The "delay grew: back in time" path
  (`ScoreBugMachine.kt` `advanceDisplay`) resets all three.
- **Tests:** replay the log's 14:41:58 → 14:42:44 rows with a two-point (+2) substituted for the +1, once with the
  try as `Dead` and once as a `Down` at the 2. Both must read TWO-POINT.

### B3. The mapper / decoder boundary is undefined between WP-1 and WP-2, and typed DTOs break ruling 21

NFL:145–148 and NFL:200–201 (§1.3), NFL:242–247 (§1.5), NFL:965 (commit 0 `EspnMapper` "stub signatures:
`week(json…)`…"), NFL:985 and NFL:995.

- §1.3 puts a pure `EspnMapper` in `:core`, owned by WP-1. §1.5 puts `@Serializable` DTOs, decoded by
  `decodeFromStream`, in `:data`, owned by WP-2.
- Commit 0 fixes the mapper's input only as "`json…`".
- The NHL precedent maps from `JsonObject` (`NhlMapper.kt:25`). If WP-1 maps `JsonObject` and WP-2 produces DTOs, they
  do not meet. If each defines the DTO, the two definitions collide at merge.
- Separately, a typed DTO contradicts §1.3's "anything not matching its shape becomes null **for that field only**"
  (ruling 21). With kotlinx, `ignoreUnknownKeys` covers new keys, but not a changed type. Examples:
  - `possession` as a number;
  - `clock` as a string;
  - `situation.lastPlay` as an array.

  Any of these throws for the whole body. That is `BAD_BODY`: the bug goes stale and then hides, the opposite of
  "degrades the bug instead of failing it".

**Required.**
- Commit 0 defines the DTO in `:core` (`EspnDto.kt`, `@Serializable`; core already depends on kotlinx serialization),
  and the final signature `EspnMapper.board(dto: EspnBoardDto, serverDateMs: Long?, logoBase: String): DayBoard?`.
  The `serverDateMs` is needed for the PRE window.
- Every leaf the bug reads that ESPN could plausibly re-type is a `JsonElement?`: status, clock, score, every
  `situation` field, and `lastPlay`. The mapper reads them with lenient helpers, like `NhlMapper.primitive`. Only the
  structural spine (`events`, `competitions`, `competitors`) is typed.
- WP-2 only streams into that DTO. `EspnMapperTest` decodes the same fixtures through the same DTO.
- Add a test: a body where `situation.possession` is a number and `lastPlay` is an array still yields a board with
  score and clock.

### B4. The `ScoreBug.kt` league seam is one table row; NHL behaviour, placement and memory all hinge on it

NFL:31–35 (scope rule), NFL:974 (commit 0 `ScoreBug.kt` row), NFL:1014 (WP-4 "only inside the commit-0 seam"),
NFL:317 (`PlacementInput.league`), NFL:513–523 (§4.4). Code: `ScoreBug.kt:279–736`, `FullScreenHost.kt:143, 229–260`,
`ScoreBug.kt:1208`.

`ScoreBugWith` is one 450-line composable. Above the visibility return, it:
- builds `ScoreBugPainter` with NHL widths (`:330–335`);
- builds `GoalPainter` and `PowerPlayPainter` (`:419–480`);
- runs `GoalRun` on `goalSeq` / `goalSide` (`:544–565`);
- feeds `LiveText` and `Describe` (`:664–667`);
- sizes the layer from `K.SCORE_W` (`:659–715`).

The design says "pill size, glass and painter chosen by `view.league`; the NFL path calls `NflBug(...)`". It leaves
these points open:
- **Where the branch sits.** If it sits below line 363, the NHL `GoalRun` sees the NFL `goalSeq` / `goalSide` and plays
  a **GOAL** takeover for a touchdown. `LiveText` then speaks the NHL goal string. Both the GA and the NFL painters and
  glow masks get built (twice the bake memory on a 1 GB stick).
- **Which league while hidden.** If it sits at the top, it branches on `view.league`, but `view` is null while hidden or
  fading. The branch then flips to the NHL default mid-fade, and `remember`ed state is torn down.
- **Who sets `PlacementInput.league`.** Nobody does. It is built only in `FullScreenHost.scorePlacementInput`
  (`FullScreenHost.kt:229`), which is in no WP and not in commit 0. With the default NHL, `place()` returns a 404-wide
  box for a 520-wide NFL bug. Overlay intersection, `glowAllowed` and `tabAllowed` then all test the wrong rectangle.
- **Place mode.** It draws `sampleView` (NHL, 404 wide; `ScoreBug.kt:1208`) even when the followed or selected league
  is NFL. The user places a box that is not the one that will show.
- **Following an NFL game while an NHL bug is on screen.** The league change mid-composition is unspecified (reset,
  slide, or fade).

**Required.**
- Commit 0 branches at the **top** of `ScoreBug(...)`, before any `remember`:
  - `league = source.bug.value?.league ?: lastLeague ?: followedLeague ?: prefs.league`;
  - `key(league) { if (NFL) NflBug(...) else ScoreBugWith(...) }`.

  `ScoreBugWith` is then byte-unchanged, so the NHL draw, seam and bounds tests are untouched by construction.
- `NflBug` owns its own copy of the shell: fade, slide, logos, place hint, live region. Alternatively, commit 0
  extracts that shell as a pure move, gated by the unchanged `ScoreBugDrawTest`. Pick one and write it down.
- Commit 0 adds `league` to `scorePlacementInput` from `ScoreFacts.game?.league ?: prefs.league`. Add
  `FullScreenHost.kt` to the commit-0 table.
- Commit 0 adds an NFL sample view for place mode, chosen by the same league rule.
- Add a `ScoreBugDrawTest` case: an NFL view with `goalSeq` bumped never starts the NHL `GoalRun` and never builds a
  `GoalPainter`.

### B5. The ball run and the arc leave the pill's rounded ends; the design's own screenshot assert fails

NFL:541–542 (§5.1 ball run and arc), NFL:555–557 ("the panel clip suffices, with no `clipPath`"), NFL:914 (assert "no
ball or post pixel outside the round rect"). Mock: `tBall`, `tArc`. The mock's pill clips with `overflow: hidden` on a
rounded box, which is the stadium shape. `clipRect` is the bounding box.

- **Run.** The ball is 34 px at y 18 (centre 35), from x −42 to 562, with α .4 between 15 % and 85 %.
  - The stadium's left edge at |dy| = 15 is x = 35 − √(35² − 15²) = 3.4.
  - A ball centred at x 10 has pixels at (2, 50): inside the `clipRect`, outside the pill.
  - With `roll` easing, the centre is near x 10 at about 15 %, when α is already .4.
  - The right cap is crossed again near 85 %.
- **Arc.** It starts at (21, 44), scale 1, so the sprite spans y 44–78 in the bottom-left cap. At (21, 70) the stadium
  edge is x 35, so the arc's first frames paint video-side corners at α up to .55.
- A painter told "no `clipPath`, no `saveLayer`" and failing that assert will improvise, and `clipPath` is the obvious
  wrong fix.

**Required.** Pick one and write it into §5.5:
- **(a) A shader.** Draw each ball as `drawRoundRect(pill, r, r, ballPaint)`, where `ballPaint.shader` is a
  `BitmapShader` of the baked sprite.
  - Give the sprite a 1 px transparent border, so `CLAMP` extends transparent.
  - Set `setLocalMatrix` from one preallocated `Matrix` (translate, rotate, scale).
  - The round rect clips to the exact stadium, allocation-free, with no `clipPath`. This is GA's streak technique
    (`GoalPainter.kt:286–289`).
- **(b) Inner bands.** Clip the run to x ∈ [r − √(r² − 17²), W − that] = [4.4, 515.6] (Standard). Move the arc's P0
  to x ≥ 35 + 17, or start its alpha after it enters the straight section.

Keep the screenshot assert, at both sizes.

---

## Should-fix

### S1. The shared `derive` puts the tab on `glow`, not `base`: BUF's tab comes out red

NFL:482–486 (§4.3 "tab = base"), NFL:656 and NFL:666 (§6 "the same `derive`"; "`tab` = base if C5 passes").
`NhlTeamColours.kt:105, 115`.

- `derive` sets `tab = glow` if C5 passes, else the other colour.
- `glow` is the accent for every base under 2.0 : 1 against the glass. The §6 table marks 17 teams "accent", BUF
  included.
- So "the same `derive`" gives BUF a red `#C60C30` tab. The mock and §4.3 want blue. The same goes for CHI, DAL, NE,
  NYG, SEA and the others marked "accent".
- An implementer who "fixes" `derive` changes every NHL tab and breaks the PP goldens.

**Fix.**
- `NflTeamColours` calls `derive`, then `copy(tab = if (c5(base) ≥ 4.5) base else accent)`. `derive` stays untouched.
- `NflTeamColoursTest` pins `tab == base` for all 32 teams.
- Add an NHL assertion: `NhlTeamColours.of("EDM").tab` is unchanged.

Correct NFL:484 as well. The mock's BUF gradient ends at `#0040b0`, lighter than the base, so "base for both" is true
for KC only (N3).

### S2. The tab runner's identity key includes `pinned`, which contradicts "cut"; `pinTabOn` is defined two ways

NFL:501 (key `(tabOn, tab.side, pinned)`), NFL:606–611 (pin and release are cuts), NFL:620–621 (HALFTIME / FINAL
rows).

- **The key.** With `pinned` in the identity key, the pin start and the release are both identity changes, so they
  kick. PP's kick on a side change is CLOSE then OPEN, so the release of a safety (the BUF label, then "KC KICKOFF") would
  slide closed and open again instead of cutting as §5.4 says.
- **`pinTabOn`.** It is "the tab is open". Open on the previous frame, or on the new view? The halftime FG and the
  OT walk-off arrive on a snapshot whose view has `tab == null`, while the previous frame's tab was open. §5.4 says
  "the tab was closed (pinTabOn false)", which is only true under the "new view" reading. Under PP's reading (the drawn
  tab, `pr.pin(..., tabAllowedNow, coveredNow)` at `ScoreBug.kt:561`), the tab would show "FIELD GOAL | BUF +3" and
  then CLOSE at 3 600.

**Fix.**
- The key is `(tabOn, tab.side)`, evaluated only while not pinned. Pin start and release rebase the run's `from` /
  content without a kick.
- At release, a side differing from the pre-pin side is a cut, and `tabOn == false` is a CLOSE.
- Define `pinTabOn` = the tab drawn at rest or opening on the previous frame, `&& tabAllowed`. Then rewrite the two
  §5.4 rows: the label shows, and then CLOSEs at 3 600. That is better than a dead tab during a halftime FG.
- Add `NflBugDrawTest` cases for both rows and for the safety release.

### S3. `clockSecs` is gated on `top == Clock`, so the NFL layout loses its clock; `knownView` is NHL-shaped

NFL:343–356 (§3.1). `ScoreBugMachine.kt:239, 254–261`.

- `clockSecs` is computed only when `top == CentreText.Clock`. The NFL puts `Period` on top and `Clock` on the bottom,
  so `clockSecs` is null, and the painter has no digits for any LIVE, stale or waiting state.
- `knownView()` always builds `(Clock, NoUpdate)` with `league = NHL` (the default) and `nfl = null`. The quick-bar
  value line and picker read it while the bug is hidden, through `ScoreFacts.lastScore`.
- In a break while stale, the table gives "No update" over a frozen `0:00`.

**Fix.**
- Compute `clockSecs` when either line is `Clock`.
- `knownView()` sets `league` and the NFL line order, and uses a one-line `NoUpdate` in NFL breaks.
- Add machine tests.

### S4. Many files the NFL needs are in no WP (SportsText, PlayerText, FullScreenHost, the bounds tests)

NFL:322–325 (§2 `sports_period_4`), NFL:741–742, NFL:775–778, NFL:954–1024 (§11).

| File | What the NFL needs | Evidence |
|---|---|---|
| `ui/.../sports/SportsText.kt` | `period()` has no league, so REG 4 is "P4" (`SportsText.kt:41`). `state()` gives "1st INT" in NFL breaks, not "End 1st" / "Halftime" (`:113–124`). `failure()` says NHL.com (`:155`). `removed(NOT_LISTED)` uses the NHL toast. `dayLabel("2026-2-9")` prints the raw key in the picker divider (`:133`). `centre()` needs a `Halftime` branch (an exhaustive `when`, so commit 0 cannot compile without it) | the code |
| `ui/.../full/PlayerText.kt` | the `ScoreLeagueRow` label; the failure and divider texts | `PlayerText.kt:482–490` |
| `ui/.../full/FullScreenHost.kt` | `PlacementInput.league` | B4 |
| `ui/src/test/.../ScoreBugBoundsTest.kt`, `ui/src/test/.../root/RootTreeRuleTest.kt` | "gain an NFL row each" (NFL:430) | unowned |

**Fix.** Add these to commit 0 (signatures, the `Halftime` branch, a `league` parameter on `period` / `state` /
`failure`) and to WP-5 (bodies, tests).

### S5. `TeamLine` has one name, and the cards and the live region need two

NFL:172–176 (§1.3 "`shortDisplayName` is the card name"), NFL:456, NFL:743–744, NFL:786–787.

- §1.3 sets `TeamLine.name = displayName` ("Kansas City Chiefs") and calls `shortDisplayName` ("Chiefs") the card
  name.
- But §2 adds no field for it. The live string "Kansas City Chiefs touchdown. **Chiefs** 13, **Bills** 7" and
  `Describe` ("Bengals 0, Falcons 7") need both names.
- One implementer adds a field to the NHL `TeamLine`, another uses `displayName` on cards ("Washington Commanders",
  which `GameCardFitTest` would then have to absorb).

**Fix.** In commit 0, add `val shortName: String? = null` to `TeamLine`. It stays null for the NHL, so every NHL
caller is unchanged. Use it for NFL cards, the live region and `Describe`.

### S6. The week chips fall back to date arithmetic, so "Last / Next week" can request a single day, and the Super Bowl chip is never disabled

NFL:731–745 (§7.2), NFL:150–151 (week key). `SportsViewModel.kt:141–144, 162–164`.

- `dayKeyFor` falls back to `easternToday().minusDays(1)` / `plusDays(1)` whenever `prevDay` / `nextDay` are null:
  - before the first board loads;
  - on an `Empty` board;
  - at the calendar edge.
- For the NFL that sends "2026-11-07" to `day()`, which fetches a **dated** board: one Saturday. §7.2's "Super Bowl →
  none (the chip is disabled)" never happens. The VM computes a date instead.
- The off-season is undefined. The calendar's "Off Season" has no entries (week fixture, `leagues[0].calendar[3]`), so
  neither chip has a neighbour.

**Fix.**
- The VM's fallback is league-aware: the NFL has no fallback. A null neighbour disables the chip.
- Give week keys a grammar no date can match, for example `w2026-2-9`, and parse it in one place.
- State the off-season board: "No NFL games this week" with the chips disabled.
- WP-5 tests these.

### S7. "Every NHL test passes unchanged" is false as specified

NFL:31, NFL:329–330 and NFL:894 (the scope rule). `ScoreBugDrawTest.kt:193`, `ScoreRepositoryImplTest.kt:181, 600,
647`.

- `GoalHooks.colours: (League, String?) -> TeamColours` does not accept `ScoreBugDrawTest`'s `colours = { TeamColours(…)
  }`: a parameterless lambda only fits a function type of 0 or 1 parameters.
- `ScoreRepositoryImpl(Map<League, ScoreSource>)` breaks the three constructor calls in `ScoreRepositoryImplTest`.

**Fix.** Keep both source-compatible:
- leave `GoalHooks.colours: (String?)` as is (the NHL path never needs a league), and give `NflBug` its own hook;
- add a secondary constructor `ScoreRepositoryImpl(source: ScoreSource, …)` that wraps `mapOf(source.league to
  source)`.

Then "NHL tests unchanged" is literally true and checkable by `git diff --stat`.

### S8. The bug appears at the coin toss, before kick-off (ruling 4)

NFL:7–8 (ruling 4 "nothing before kick-off"), NFL:444 (the state table), NFL:821. The log: 14:35:21, `in`, clock
15:00, `lastPlay` 70 Coin Toss, before the kick-off.

ESPN goes `in` at the coin toss, so the design shows "1st / 15:00" with an empty tab before the ball is kicked. Ruling 4
(owner, "nothing until puck drop") is listed as one the design leans on, but this is a deviation and it is not
recorded.

**Fix.** Either treat `LIVE ∧ play == None ∧ period 1 ∧ both scores 0` as pre-game (no bug, `PRE` polling), or add it to
§15 as D8 and ask the owner. The first is one line in the NFL branch and matches the ruling.

### S9. The fake panel's timeline has several sub-poll windows and one football-inconsistent possession

NFL:816–850 (§9.2), NFL:1076 (checklist item 7).

- **Short windows.** "Windows ≥ 25 s except the deliberate 10 s one" is not what the table says. These windows are
  also 10–15 s:
  - Q1 150–160 (no possession);
  - Q2 50–60 (BUF kick-off);
  - Q2 60–75 (KC 2nd & 4);
  - Q4 50–60 (BUF 3rd & 9).

  At about 21.5 s polls, each is sampled roughly half the time. Checklist item 7 (the inference, "no flicker back to
  KC") is then a coin flip, and so is the Q2 kick-off side change.
- **Possession.** Q3 25: "BUF safety; KC free kick". Then Q3 50–75: "KC 1st & 10". After KC's free kick, BUF receives.
  So the "side change at the kick-off" in the Proves column is only the pinned → released cut, and inference rule 1 is
  never exercised against a real side change.
- **The two-point try.** Q3 75–100 must be the try (`Dead`). Write its shape down, since §1.6 item 3 is unknown and
  B2's tests depend on it.

**Fix.**
- Make every window ≥ 25 s except one marked 10 s. Two consecutive polls of the no-possession state are what prove
  "no flicker".
- Give BUF the ball after the Q3 free kick.
- Spell out the try's `situation` in each scoring row.

---

## Nits

- **N1. The ESPN week boundaries.** NFL:102 says "a week runs Wednesday 07:00Z to 06:59Z". That holds in EDT only.
  - Week 9 itself is `2026-11-04T08:00Z`–`2026-11-11T07:59Z`; both are 3 am ET.
  - Week 1 is `2026-09-06T07:00Z`–`09-16`, a Sunday start and 10 days ("Sep 6-15").
  - Say "3 am ET, from the calendar's own `startDate`", and never compute it.
- **N2. Atlas memory.** NFL:577–578 says about 5 × 50 KB. One ARGB word at 1.5× is about 344 × 90 × 4 ≈ 124 KB at
  1080p ("TOUCHDOWN", 229 × ~60 design px), so all five are about 600 KB. At a 2160p panel (d = 2) that is about
  2.5 MB.
  - Either bake a de-duplicated glyph atlas (20 distinct glyphs), or bake only the current kind's word in `begin()`.
  - Restate the figure, and add the atlas to `NflScreenshotTest`'s size check, as GA S2 did.
- **N3. Mock fidelity.**
  - The mock's BUF tab gradient ends at `#0040b0`, not the base (S1).
  - The mock's tab tracking is .02 em, not .05: an unrecorded deviation.
  - The tail is 600 weight, but PP bakes its labels from Instrument Sans **Bold** only (`ScoreBug.kt:447`). Say which
    typeface the "OWN 35" masks use.
- **N4. §12 is not exhaustive** ("every number lives in one constant"). Missing:
  - the letter start (420) and the letter duration (420);
  - the takeover pad (24) and gap (14);
  - the tab's icon gap (10) and tail weight;
  - the arc and ball alpha keyframes (10 / 15 / 85 %);
  - the pop's 40 / 70 % keyframes;
  - `NFL_LETTER_MS`.
- **N5. A score-correction blip replays a takeover.** A side that falls (a reversed TD, 14 → 7) and is then restored
  (7 → 14) flags a +7 TOUCHDOWN. For the NFL branch only: no flag for a rise that returns to a value that side showed
  within the last `QUEUE_KEEP_MS`.
- **N6. Use `lastPlay` for inference.** In `scoreboard-live-no-possession-after-kickoff`, `lastPlay` 53 Kickoff has
  `team.id "1"` (ATL, the **receiver** of CIN's kick). That is a better first source for the receiving side than "the
  other side of the kicker", and it gets onside recoveries right.
- **N7. Re-dated games.** `game()` reads only the start-date board, so a game ESPN re-dates (a weather postponement
  to Monday) becomes `NotFound` → "ESPN no longer lists…", not `POSTPONED`. Before counting a `NotFound`, retry once
  against the week board (`seasontype` + `week` from the follow).
- **N8. Fake-panel restart.** `/admin/sports-nfl` "restart is the NHL's", but `sports.py` is untouched. The NFL anchor
  reset must live in the `fakepanel.py` dispatch. Say so, or an emulator rerun keeps the old NFL clock.
- **N9. `GoalFramesTest`.** The NFL tab CLOSE / OPENs about every kick-off, and the fake has a kick-off right after
  every score. Its burst detector (PP B3's lesson) must arm on the score change only, and measure the window
  [score, score + 4.5 s].
- **N10. The DTO field list.** §1.5's "small DTOs holding only the §1.3 fields" must include `leagues[0].calendar` and
  `week` for the week key and the neighbours. These are about 4 KB per body, decoded every 21 s for the followed game.
  Skip them in `game()` by a second, smaller DTO.

---

## Checks that passed

- **The §1.2 table vs the log.** Every row matches. The following are all as stated:
  - the score before the play at 14:41:58;
  - the try as `down −1, distance 10, yl 100`, with no possession and `lastPlay` 67 sv 6;
  - the kick-off as `down −1, distance 0`, possession = the kicker, **no** `possessionText` / `shortDownDistanceText`
    (so the `yardLine` fallback is required, and correct);
  - the 107 s freeze;
  - the +7 already in the kick-off state;
  - the FG in the same dead-ball shape;
  - `isRedZone` true through the try and the kick-off;
  - END_PERIOD keeping the situation.
- **`Age`.** Absent in all 189 logged answers. `max-age` is 1–10.
- **Poll cadence.** With `Date` and `Age = 0`, `SportsTiming.nextAligned` gives `received + 20 s + phase (1–2 s)`, so
  21–22 s; there is no machine change. Stale is at `max(60, 2 × 20) = 60 s` past the last receipt.
- **Geometry.**
  - Widths: 16 + 64 + 8 + 50 + 12 + 1 + 106 + 1 + 111 + 1 + 12 + 50 + 8 + 64 + 16 = 520.
  - Rest boxes: 1304 + 520 = 1824 = 1920 − 96; bottom 954 + 70 = 1024.
  - Large: 1174 / 936.
  - Tab: 520 − 2 × 37 = 446, reach 36 + 5 + 24 = 65 ≤ 74.
- **Mock conversions.** These all match:
  - `tBall` (−80, 37) → (−42, 18), 1060 → 562;
  - `tArc` (40, 90) / (380, −14) / (690, 25) → (21, 44) / (202, −7) / (366, 12);
  - `tStreak` −160 / 1160 → −85 / 616;
  - `tLetter` 46 → 22;
  - `tSlideR` 36 → 19;
  - `tLogo` −60 → −32.
- **Flash grid.** The tab peaks at 225 + 450k. The panel peaks (675, 1 125, 1 575) and the post peaks (2 025, 2 475,
  2 925) fall on tab peaks, so it is 2.2 Hz across pill and tab together. ADD over the opaque PP tab gradient does equal
  `brightness(1 + .45p)`.
- **Colours.** Recomputed C1 / C5 / glow ratio for ARI, BUF, CAR, CIN, DEN, DET, JAX, KC, LAC, MIA, PHI, SF and TB, with
  `mixBlack(.172)` = `gradientBlackAt(.80)`. All match the table.
- **Letter stagger and font.** 420 + 8 × 55 = 860; 420 + 10 × 55 = 970. `barlow_condensed_black_italic.ttf` is
  112 592 B.
- **The NHL fake panel.** Isolation by a separate module and flag is the right call. The golden stays byte-identical by
  construction.

---

## Open questions: recommendations

1. **+7 / +8 as one TOUCHDOWN** (Q1): agree. The log shows it is common.
2. **Red zone** (Q2): agree, nothing extra. The flag is unreliable outside real downs (§1.2).
3. **The four colour picks** (Q3): agree on all four. BAL's gold is needed for the glow, and the others match each
   club's current identity.

---

## Verdict

**Not ready for commit 0.** Fix these before implementation starts:
- **B1.** Give `FieldPos` a territory and resolve OWN / OPP in `:core` after inference. ESPN drops possession after every
  kick-off.
- **B2.** Redefine `pendingTry` (set, clear, order, PAT on the stale body). The design's own 14:41:58 fixture breaks it.
- **B3.** Put one lenient DTO and the mapper signature in commit 0.
- **B4.** Specify the `ScoreBug` seam at the top, `PlacementInput.league` in `FullScreenHost`, and an NFL place-mode
  sample.
- **B5.** Keep the ball inside the stadium without `clipPath`.

S1 (BUF's red tab) and S2 (the pin key) are user-visible on the first touchdown. Fold them into the same revision.
