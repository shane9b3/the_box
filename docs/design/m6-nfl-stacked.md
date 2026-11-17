# NFL score bug: "Stacked" style: design

Status: **proposed 2026-11-15; reviewed 2026-11-15 (approve with required changes); revised under the lead's sign-off
2026-11-15.** Review: `docs/review/2026-11-15-nfl-stacked-design-review.md` (RC1–RC9, recommendations 1–9). Decision:
`docs/decisions/2026-11-15-nfl-score-bug-stacked.md`. Items marked "(RCn)" / "(Rec n)" apply that finding in place.

**(YDS 2026-11-16) The YDS column is built:** `docs/design/m6-nfl-stacked-yds.md` (decision `docs/decisions/2026-11-16-nfl-stacked-yards-column.md`).
Where this document and that one differ on the column, the footprint or the end-of-quarter shape, that one wins.

Owner request (2026-11-15, verbatim): "please create the NFL stacked bug please use answers from previous NHL builds for
your baseline". Mock-up: `design/nfl-stacked/StackedNFL.dc.html` ("the mock"; README and two logo PNGs beside it). Its
inline styles and `<helmet><style>` block are "the CSS"; its `Component` class is "the mock JS".

Builds on (binding, not re-asked):
- `docs/decisions/2026-11-05-nhl-score-bug.md` rulings 1–33 ("R n");
- `docs/decisions/2026-11-10-nhl-score-bug-stacked.md` (the owner's eight answers of 2026-11-11, "SR n"; the 2026-11-13
  mirror rulings; the lead sign-off and fix-pass rulings);
- `docs/design/m6-score-bug-stacked-theme.md` ("ST §n"), `docs/design/m6-nfl.md` ("NFL §n"),
  `docs/design/m6-goal-animation.md` ("GA §n"), `docs/design/m6-chl-leagues.md` ("CHL §n"),
  `docs/review/2026-11-13-stacked-row-mirror-review.md`;
- code on `main` at `412446e`.

Units are **design px** (screen width ÷ 1920; `1.dp == 1 design px`) and ms; "mock px" are the mock's CSS px. **Must**
means a reviewer rejects code that does otherwise. Every number lives in one named constant (§8.5).

**Scope rule (NFL §0, ST §2):** the NHL / CHL Stacked bug and the NFL Pill bug render byte-identically. Checked by
`./gradlew :ui:sportsHashGate` against `ui/src/test/resources/golden/sports-hashes.txt` (1 260 PNGs) with **no edit to
an existing line**; new entries are appended (§9.3).

---

## Lead sign-off (2026-11-15)

All nine required changes are applied, with these rulings where the review left a choice:

1. **Timeout (RC1).** Trigger on `lastPlay` type **21**; the caller is the side whose count dropped (below its
   low-water mark). The bar holds **while that type-21 play is the shown `lastPlay`**. Fallback: a drop of exactly 1
   with no 21 seen shows the bar for **8 s**. Types 74 (official timeout), 75 (two-minute warning) and the end-of-period
   types (2, 65, 66) never show it. `lastPlay.text` is never parsed and `lastPlay.team` is never trusted for the caller.
2. **Per-half low-water mark** for the timeout counts (RC2).
3. **Penalty (RC3):** type 8, or `lastPlay.text` containing upper-case `"PENALTY on"`. A declined foul ("Penalty on …,
   declined.", mixed case) shows **no** flag. The `lastPlay` id must be **numerically newer** than every id seen in the
   follow. Flag bar hold **8 s** (lead default; not an owner question).
4. **Precedence (RC4):** pinned score > stale / waiting > break / final > flag > timeout > play. Table and prose agree.
5. **Stale / delayed (RC5): aligned with the baseline.** In play, "No update" / "Delayed" goes in the clock slot and the
   bar **closes**, as NHL Stacked and the NFL Pill do. No deviation.
6. **YDS column (RC6): out of v1 and out of the footprint.** Footprint **333 × 141** Standard, **416.25 × 176.25** Large;
   every dependent number re-derived. The column's spec is kept in Appendix A, marked "not built". *(YDS 2026-11-16:
   superseded by the owner's approval; the column is built, footprint 396 × 141 / 495 × 176.25, `docs/design/m6-nfl-stacked-yds.md` §3.2.)*
7. **Overtime pips (RC7):** slots = the highest count seen in that half group (≤ 3).
8. **Feed facts (RC8)** corrected from the review's live samples; proven items moved out of "not determined"; the still
   unproven ones (halftime `situation`, OT counts, offsetting-foul text, the caller in `lastPlay.team`, a declined foul
   on the scoreboard) are listed with the defensive behaviour for each (§12).
9. **Kick-off never reads RED ZONE (RC9):** fixture and assertion added.

Recommendations taken: dark bars reported by ΔE76 and IND @ NE and DAL @ NYG added to the screenshot set (Rec 2); a pip
legibility floor at 720p (Rec 3: pip height 2.7 → **3.0** design px, the smallest change that reaches 2 real px at
1280 × 720; recorded as S-D12); the NFL colour table as its own object, no CHL/NHL code path touched (Rec 4); mirror
wording fixed (Rec 5); a lost challenge's fallback bar recorded (Rec 6); the extra fake-panel events (Rec 7); the neutral
down bar recorded as a deviation (Rec 8); announcements gated (Rec 9). Flag hold removed from the owner questions
(Rec 1). Owner questions left: (1) one shared Style setting, built shared; (2) yards column, built without (§13).

---

## 0. Decisions at a glance

1. **One style setting for both sports.** "Score bug style" (`SportsPrefs.nhlStyle`, Pill default) now also applies to
   NFL games. Stored name unchanged, no migration. The sheet's Style row shows for every league (§7). Owner Q1.
2. **The data and machine are the NFL Pill's**, plus three additions from fields the scoreboard already sends (all
   proven live, §3.1): timeouts (`situation.awayTimeouts/homeTimeouts`), a **timeout mark** from `lastPlay` type 21
   confirmed by the count, and a **flag** from `lastPlay` type 8 or "PENALTY on".
3. **YDS column (YDS 2026-11-16):** team total yards from ESPN's per-team statistics, fetched at stoppages and attached
   to the snapshot (`docs/design/m6-nfl-stacked-yds.md`). v1 shipped without it.
4. **Size and look are the NHL Stacked's:** mock × 0.45 (SR1), info text 20 px and bar 33 (ST S5), fixed navy rows
   `#12284F`, team-true colours through `NhlStackedColours.derive` (SR2, SR3), hairline on every bar, ΔE76 stripe swap.
   Footprint **333 × 141** Standard, **416.25 × 176.25** Large (SR8); with the YDS column (YDS 2026-11-16) **396 × 141**
   / **495 × 176.25**.
5. **The bar is open in fresh play** (the mock's structure; R22). It reads down & distance and field ("1ST & 10 | KC 35")
   in the possession team's bar colours and becomes the flag, timeout, score, halftime, end-of-quarter and final bar on
   those events. It closes when stale or waiting in play, and when the feed has no `situation`. Every content change
   replays the mock's 550-ms **re-key intro** (§4.1).
6. **Right corners mirrored exactly as the NHL Stacked** (2026-11-13): from the screen edge stripe, logo, code, ball,
   score, then the clock column. New elements follow the same rule: positions reflect, glyphs and bitmaps never flip,
   "/" leans kept, rotations negated (§5.3).
7. **Scoring takeover** = `NflScoreTimeline` through a new `NflScoreSpec` (PILL bit-identical, STACKED the mock's), as
   `GoalSpec` split `GoalTimeline`. Kinds, pairs, try bookkeeping and the no-spoiler hold are the Pill's (R23, R25).
8. **Flash rule:** panel flash and post hits on the 450 ms grid (NFL §5.3); the pip burn 4 × 400 ms (mock 5 × 320).
9. **New files only** for the drawing and runner (`StackedNflBug`, `StackedNflPainter`, `StackedNflScorePainter`), pure
   `StackedNflLayout` / `StackedNflTimeline` / `NflStackedColours` in `:core`. The NHL Stacked and NFL Pill files are not
   edited, except `NflScoreTimeline.kt` (the spec split, gated bitwise).

---

## 1. Carried-over rulings

| Ruling (short) | Source | How it applies to NFL Stacked |
|---|---|---|
| Live by default, user delay | R1 | every view and mark (score, flag, timeout) follows the delayed shown snapshot |
| Nothing before kick-off | R4; NFL §1.3 S8, lead sign-off 1 | no bug while PRE (including `in` before the first logged play) |
| No local countdown | R6, R13 | the clock column shows the snapshot's clock; the mock's `tick()` is mock behaviour |
| Small lines dropped | R7 | the takeover's 17 px team-name line is dropped |
| Badge = logo on the team colour | R8 | |
| No sound | R9 | |
| Score words in Barlow Condensed Black Italic | R10 | as the Pill and the mock |
| Always the team's own two colours; BAL/DAL/JAX/TB picks | R12, R27 | panel = base, lead = accent (`NflTeamColours`) |
| Full animation on every box; only "Reduce animations" stills | R20 | |
| ESPN site API; a missing field degrades | R21 | every new field degrades to "not drawn" |
| Possession always on in play, hidden in breaks and at Final | R22 | the ball, the wide stripe and the possession bar follow `NflView.tab`. Unlike the Pill (tab closed when possession is unknown), a known down with an unknown side shows a **neutral** bar: Stacked has no separate down column (S-D10, Rec 8) |
| Animated kinds TD, XP, FG, safety, two-point; defensive TD = TOUCHDOWN | R23 | five kinds; the mock shows three |
| +7/+8 = two takeovers | R25 | §4.3 |
| RED ZONE inside the opponent's 20, red from the divider | R26, NFL lead decision 2 | the sub part of the bar on red; never on a kick-off (RC9) |
| Update interval; NFL floor 5 s | R29, R32 | unchanged |
| Style name, Pill default, two places (Settings ADVANCED + sheet row), sheet stays open, never "theme" | ST decision | the same setting (§7) |
| Size × 0.45 | SR1 | §2 |
| Goal colours = the team's own | SR2 | |
| Dark bars keep true colours + hairline + bright stripes | SR3 | 7 teams + fallback have a bar within ΔE76 20 of the rows (§6) |
| Break look: clock column folds, neutral bar names the break | SR4a | halftime (the mock) and end of quarter by analogy |
| "1ST / END" at 0:00 | SR5 | **n/a**: ESPN goes from the last play to `STATUS_END_PERIOD` (NFL §1.2) |
| Large offered (× 1.25) | SR8 | |
| Right corners mirrored, same order from the edge, glyphs never flipped, "/" kept, spin negated | decision 2026-11-13; review 2026-11-13 L1, S2 | §5.3 |
| Fixed navy rows; team glow; one derivation; hairline; stripes ≥ 2 : 1; ΔE76 < 20 home swap | ST lead sign-off | §6 |
| Info text 20 px, bar 33, tally 20 | ST S5 | |
| Bar words in Instrument Sans Bold Italic | ST lead (N2) | |
| Stale / delayed: in play the clock slot says "No update" / "Delayed" and the bar closes; in a break the bar says "NO UPDATE" / "DELAYED"; no reopening | ST §8, ST lead; NFL §3.4 | §3.4 (RC5) |
| Layout pinned for the takeover; glow baked for the exact box | ST B4 | |
| Clock tabular, 5 px air; column words 6 px air; "No update" two lines | ST fix pass | |
| RTL locale cannot move the box; follow end fades with its own look | ST fix pass | |
| Unknown enum name reads as default | ST S7 | no new persisted enum |
| Score kind = point rise; try bookkeeping; possession inference; field by territory | NFL §3.2–3.4 | unchanged |
| Tally "24 – 17"; shared pop rule | NFL D4, D2 | |
| No-spoiler hold until 216 ms | NFL D10, GA §5.2 | the pinned bar too |
| Flashes on the 450 ms grid | NFL §5.3, GA B3 | |
| "EXTRA POINT" between a pair's takeovers | NFL lead decision 3 | |
| League/style change swaps with no fade | NFL §4.6, ST §2.2 | key `(league, style)` |
| Red half beside KC/ATL/ARI/SF/TB red: accept | NFL §14 (lead default, still with the owner) | carried, not re-asked |

---

## 2. Mock-up inventory and conversion

### 2.1 Scale
- **0.45** (SR1). Same family as the NHL mock: top part 240 (two 120 rows), bar 66, rows 540, clock column 200.
- Widest **built** box: rows + clock = 540 + 200 = **740 → 333** (the YDS column, 140 mock, is not built; Appendix A).
  *(YDS 2026-11-16: rows + YDS + clock = 880 → **396**; the play box stays 333, `STACKED_NFL_W_MAX`.)*
  Height 108 + bar 33 = **141**.
- Large = × `SCORE_LARGE_SCALE` 1.25.
- Raised values (ST S5): bar 29.7 → **33**, bar text 17.1 → **20**, tally 6.75 → **20**.

### 2.2 Fonts
Bundled (`ui/src/main/res/font/`): Instrument Sans regular/medium/semibold/bold/bold italic, JetBrains Mono, Barlow
Condensed Black Italic. Inter is not bundled and not added (ST §11). Inter 800/700 → Instrument Sans Bold; Inter 800
italic (bar) → Instrument Sans Bold Italic; Barlow 900 italic → Barlow Condensed Black Italic. Widths are summed `hmtx`
advances of the bundled files (no kerning; the method reproduces NFL §5.2's "TOUCHDOWN 229 at 48").

### 2.3 The box (mock → Standard design px)

| Element | Mock | Standard | Notes |
|---|---|---|---|
| Top part | 240 | 108 | |
| Team row | 540 × 120, `#12284F` | 243 × 54 | `STACKED_ROWS_W` |
| Row divider | 1 px white .22 | 1 | not scaled |
| Row stripe / possession stripe | 8 / 14 | 3.6 / 6.3 | widened for the possession side |
| Logo slot: margin-left, width | 20, 120 | 9, 54 | logo fit inside 50.4 × 43.2, from the takeover-size bitmap (ST S6) |
| Centre block padding-left | 26 | 11.7 | code starts at stripe + 74.7: **78.3** (81.0 with the 6.3 stripe) |
| Team code | 56 / 800, .01 em, line-height 1 | **25.2** Instrument Sans Bold | widest "WSH" 62.7, narrowest "SF" 31.8 |
| Code line box top | (120 − 70) / 2 = 25 | 11.25 | block = code 56 + gap 8 + pips 6 |
| Possession ball | 30 SVG, fill `#8B4513`, stroke white 1.6 / 24 | **13.5**, stroke 0.9 | gap 12 → 5.4 after the code; centred on the code line |
| Timeout pips | 3 × (22 × 6, r 2), gap 6, 8 below the code line | **9.9 × 3.0**, r 0.9, gap 2.7, top **40.05** (bottom 43.05) | height raised 2.7 → 3.0 (S-D12, §2.3.1) |
| Pip colours | lit `#FFD34D`, used white α .22, transition .4 s | same | lit 10.18 : 1 on the row; lit vs used 5.09 |
| Pip burn | `toBurn` .32 s × 5 | **4 × 400 ms** | flash rule (§4.2) |
| Score slot | 100, margin-right 32, 78 / 800 tabular | **45**, 14.4, **35.1** | "88" 42.1; 3 digits step down (§5.4) |
| Clock column | 200, `#15191F`, inset line 1 px `#2B3240` | **90** | `STACKED_CLOCK_W` |
| Quarter slot | 112, padding-bottom 14, 48 / 800, .02 em | 50.4, 6.3, **21.6** | "4TH" 44.3, "2OT" 46.0 |
| Rule | 2 px `#5B6170`, margin 26 | 1, 11.7 | |
| Clock | 62 / 700 tabular | **27.9** | "15:00" 74.9 + 2 × 5 air ≤ 90 |
| Box border, radius | 1 `#2B3550`, 6 | 1, 2.7 | |
| Box shadow | 0 12 36 .55 | nine-slice dy 5.4, σ 8.1, α .55 | ST §6 bake |
| YDS column | 140 | **63** (YDS 2026-11-16) | `m6-nfl-stacked-yds.md` §3.1 |

#### 2.3.1 Pip legibility at 720p (Rec 3)
1 design px = 1280 / 1920 = 0.667 real px at 720p. The mock's 2.7-px pip is **1.8 real px** tall there, under 2.
The smallest change that reaches 2 real px is a height of **3.0** design px (2.0 px at 720p, 3.0 at 1080p, 6.0 at 4K;
Large 3.75 → 2.5 px at 720p). Width, gap and radius are the mock's. The pip block still ends at 43.05, above the 54
row. `StackedNflFitTest` asserts ≥ 2 real px at 1280, 1920 and 3840 wide. A larger floor (the review's 3.6) is an eye
check on a TV in the hand pass, not a design change.

### 2.4 The bar

| Element | Mock | Standard | Notes |
|---|---|---|---|
| Height | 66 | **33** | ST S5 |
| Background | per state (§2.6) | per state | transition .35 s ease |
| Hairline | none | 1 px white .22 at the inner edge, every bar | ST S2 |
| Stripes A / B | left 46 / 84, w 26, top/bottom −10, skew −30°, travel −140 | 20.7 / 37.8, 11.7, 4.5, −30°, −63 | NHL constants |
| Text | padding-left 140, gap 24, 38 / 800 italic, .01 em | 63, **10.8**, **20** Bold Italic | |
| Divider | 2 × 30, white .5 (flag: black .4) | 1 × 15.8 | 30/38 of the text height |
| Flag icon | 30 × 34, `#111111`, pole stroke 3 | **15.8 × 17.9**, pole 1.6 | scaled with the text; before the words, gap 10.8 |
| Text shadow | none | the NHL bar's hard 1 px black .25 (not on the flag bar) | one bar family |

Fit at 20 px (room = width − 63 − overhang ≈ 4):

| String | Width | Fits |
|---|---|---|
| icon + "FLAG ON THE PLAY" | 26.6 + 193.1 = 219.7 | 333 |
| "TIMEOUT WSH" \| "1 LEFT" | 147.5 + 22.6 + 62.3 = 232.4 | 333 |
| "WSH KICKOFF" \| "WSH 35" | 143.7 + 22.6 + 77.5 = 243.8 (worst) | 333 (≥ 18 spare) |
| "4TH & GOAL" \| "RED ZONE" | 123.1 + 22.6 + 102.0 = 247.7 | 333 |
| "EXTRA POINT" \| "WSH +1" | 136.7 + 22.6 + 77.6 = 236.9 | 333 |
| "TOUCHDOWN" \| "WSH +6" | 141.8 + 22.6 + 77.6 = 242.0 | 333 |
| "END OF 4TH", "HALFTIME", "FINAL/OT", "NO UPDATE", "DELAYED" | ≤ 119.7 | 243 (break width) |

### 2.5 The scoring takeover (top part only, as ST §7.2)

| Element | Mock | Standard |
|---|---|---|
| Padding / gap | 0 36 / 30 | 16.2 (tight 8) / 13.5 |
| Badge | 130, drop-shadow 0 5 0 .25 | **58.5**, hard shadow dy 2.25, .25 |
| Team-name line | 17 / 700 | **dropped** (R7) |
| Word | Barlow 900 italic, .03 em, lh .9, shadow 0 5 0 .28; TD 86, XP 74, FG 80 | TD **38.7**, XP **33.3**, FG **36**, SAFETY **38.7**, TWO-POINT **36**; shadow dy 2.25 |
| "+N" | 58 / 800 | **26.1** |
| Tally | 15 / 600 "KC 24 · BUF 17" | **20** Bold tabular "24 – 17" (NFL D4); slot "88 – 88" 67.7 |
| Block gap | 6 | 2.7 |
| Streaks 1 / 2 | w 110 / 40, overhang 40, accent .55 / white .3 | 49.5 / 18, 18 |
| Ball | 70, white stroke 1.6/24 | **31.5** |
| Goalposts | 90 × 120 at (520, 50), viewBox 60 × 80, stroke 3 | **40.5 × 54** at (234, 22.5) on the 333 box, stroke 2.03 |
| Glow | fixed red | the scorer's `glow`, GA masks baked for the exact box |
| Pop | ×1.5 → 1, `#FFB81C` → white | team `pop` → white |
| Row flash | rgba(227,24,55,.6) → 0, 1.2 s | the row stripe at the C7 α → 0 |

**Fit** (the mock itself clips "+6" and the tally at its right edge: screenshot `22-td-1500.png`). At 333: fixed parts
2 × 16.2 + 58.5 + 2 × 13.5 + 67.7 = 185.6, room 147.4; TOUCHDOWN at 38.7 is 184.6. In `begin()`, against the pinned
width:
1. padding 16.2 → 8 (room 163.8);
2. the word scales down to fit, floor **28 px**: TOUCHDOWN → 34.3, EXTRA POINT → 32.2; FIELD GOAL (157.0), TWO-POINT
   (159.1), SAFETY (115.0) keep their size;
3. still under the floor: the tally is dropped (block = "+N", 30 wide);
4. still under the floor (only a takeover pinned at the 243 break width): the badge is dropped.
The ×1.05 word pulse overruns by ≤ 4.3 px into the 13.5 gaps (accepted). The scores return on the box at 3 600.

### 2.6 Bar colours per state

| State | Mock bg / A / B / text | Here (§6) |
|---|---|---|
| Play, PAT, kick-off | possession team's bar | that team's `barBg` / `barA` / `barB`, white text |
| Play, side unknown | — | neutral `#1B2440`, away / home row stripes |
| Score | scorer's bar | scorer's colours |
| Timeout | caller's bar | caller's colours |
| Flag | `#FFD100` / `#111111` / white; text `#111111` | `#FFD100` / `#111111` / **`#806800`** (white is 1.46 : 1 on yellow; `mixBlack(#FFD100, .5)` is 3.69 against the bar, 3.51 against A; S-D6) |
| Halftime, end of quarter, final, NO UPDATE / DELAYED in a break | `#1B2440` / KC stripe / BUF stripe | `STACKED_BAR_NEUTRAL`, away / home row stripes |
| Red-zone sub | — | `#A60D26` (`mixBlack(#C8102E, .172)`), white 7.76 |

### 2.7 Mock states and their CSS

| Mock state | What changes | CSS |
|---|---|---|
| `play` | ball on, stripe 14, bar "1ST & 10 \| KC 35" | `.ball`, `.stripe`, re-key |
| `flag` | yellow bar, icon waves, contents shake | `flagShake` .5 s ease-in-out, delay .15, × 2 (±6, ±4 mock); `flagWave` .6 s alternate infinite ±8° about (4, 26) |
| `timeout` | pip burns, bar "TIMEOUT BUF \| 1 LEFT", YDS opens | `toBurn`; `.yds` (built, YDS 2026-11-16) |
| `half` | ball off, stripes 8, clock folds, YDS opens, "HALFTIME" | `.clockcol` .45 s Std (fold delay 0, open .35); contents .15 / .3 after .5 |
| `score` / `after` | 3.6 s takeover; then row flash 1.2 s, pop .9 s, bar "EXTRA POINT …" / "KC KICKOFF …" | `s-*`, `sPop*`, `sRow*` |
| any change | `barKey` bump re-mounts the bar contents | `barIn` .45 s Spring delay .1 (α 0, x −26); `stripeIn` .45 s Spring (x −140), B +.08; bg .35 s ease |
| reduced motion | transitions .01 s; no flash, streak, tumble, arc, pulse, shake, wave, burn | §4.4 |

Rendered mock frames (Chrome headless, animations frozen on a scratch copy):
`/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/nfl-stacked/`
`01-play.png`, `28-flag-shake.png`, `20…23-td-*.png`, `24/25-xp-*.png`, `26-timeout-burn.png`, `27-timeout-bar.png`,
`29-halftime.png`.

---

## 3. State model

### 3.1 Feed facts (cited; "live" = the review's samples of 2026-11-15, trimmed into fixtures)

New fixtures (`core/src/test/resources/sports/nfl/`, beside the existing NFL fixtures; one event each, trimmed to the
existing fixtures' key sets by the proposer's `trim.py`; public scoreboard bodies, no headers):

| Fixture | Event, moment | Proves |
|---|---|---|
| `scoreboard-live-penalty-type8-2026-11-15.json` | LAR @ ARI, Q1 7:23 | `lastPlay` type 8 "Penalty", id `401873061503`, text "… PENALTY on LA, Illegal Formation, 5 yards …"; the situation already moved on (4th & 6 "ARI 40"); counts away 3 / home 2 |
| `scoreboard-live-team-timeout-type21-2026-11-15.json` | LAR @ ARI, Q1 6:56 | `lastPlay` type 21 "Timeout", id `…520`, text "Timeout #1 by LA at 06:56." (legacy code "LA"), `team.id` 14 (also the possession team); `awayTimeouts` 3 → **2 in the same poll** |
| `scoreboard-live-team-timeout-held-2026-11-15.json` | the next poll, 30 s later | the same type-21 `lastPlay` and clock 6:56: the 21 is held |
| `scoreboard-live-after-timeout-2026-11-15.json` | the poll after | new `lastPlay` id `…536` (type 24), clock 6:55, 1st & 9 "ARI 9", `isRedZone` true: the timeout ends on the new play |
| `scoreboard-live-official-timeout-kickoff-redzone-2026-11-15.json` | SEA @ LV, Q1 7:41 | kick-off after a score: `down` −1, `distance` 0, `yardLine` 35, possession LV (13), `lastPlay` 74 "Official Timeout", **`isRedZone: true`**; held > 3 min live (RC9) |
| `scoreboard-live-kickoff-return-no-possession-2026-11-15.json` | SEA @ LV, Q1 7:41 | `lastPlay` 53 Kickoff (team 26 SEA), 1st & 10 "SEA 27", **no `possession`** (inference rule 1 of NFL §3.4) |
| `scoreboard-live-clock-lag-a-2026-11-15.json` / `-b-` | LAR @ ARI, Q1 10:41 | the clock held 10:41 across a new play (`lastPlay` 74 `…329` → 24 `…345`): the clock is not a play signal |

| Need | ESPN field | Parsed today | Evidence |
|---|---|---|---|
| Score, quarter, clock, phase, break | `competitors[].score`, `status.*` | yes | `EspnMapper.kt:110-181` |
| Possession, down, field, red zone, kinds, try | `situation.*`, `lastPlay` | yes | `EspnMapper.kt:231-286`, `ScoreBugMachine.kt:747-764` |
| **Timeouts** | `situation.awayTimeouts/homeTimeouts` | no | present in every in-progress body, live and in the 2026-11-08 fixtures; persist after use (ARI 2 from Q1 13:44 on); decrement in the same poll as the type 21 (fixtures above). Absent at Final and before kick-off (no `situation`) |
| **Team timeout** | `lastPlay.type.id == "21"` | id yes | `…team-timeout-type21…`, held ≥ 60 s (`…held…`), ended by the next snap (`…after-timeout…`) |
| **Official timeout / two-minute warning** | type 74 / 75 | id yes | 74 in `…official-timeout-kickoff-redzone…` and 43 times in `live-sample-2026-11-08.log`; 75 in the summaries |
| **Penalty, no play** | type 8, "PENALTY on" in the text | id yes, text no (`EspnMapper.kt:214-224`) | `…penalty-type8…`; `scoreboard-live-td-score-first-2026-11-08.json` |
| **Penalty, play stands** | the play's own type, "PENALTY on" in the text | text no | `live-sample-2026-11-08.log` 14:36:07 (Kickoff 53), 14:40:27 (Rush 5); summary final (Punt 52, Pass Reception 24) |
| **Declined foul** | "Penalty on KC-…, Offensive Holding, declined." (mixed case), type 3 | — | summary final `summary-final-401873058` (review espn/); on the scoreboard inferred (same play text), not seen |
| Flag visible mid-play | no: logged with the resolved play | — | live + 2026-11-08 log |
| **Team yards** | not on the scoreboard (`competitors[].statistics` = `[]`, full board, per-event `scoreboard/{id}` (9 KB) and final) | — | `sbEvent.json`, `ev_401873045.json`, live `event-401873061-162614.json`; summary `boxscore.teams[].statistics.totalYards` exists: 175 KB in Q1, 662 KB final. *(YDS 2026-11-16)* per-team `…/competitors/{id}/statistics` `totalYards`, 8–10 KB gzip each, on the board's sample (lead sampling 2026-11-16; `m6-nfl-stacked-yds.md` §1.1) |
| Clock lag | the clock can hold across plays, then jump | — | `…clock-lag-a/b…` |
| End of quarter | `STATUS_END_PERIOD`, situation kept, `lastPlay` 2 | phase yes | `scoreboard-live-end-period-2026-11-08.json` |
| *(YDS 2026-11-16, S1)* End of quarter, after its one sample | `STATUS_IN_PROGRESS`, next period, 15:00, `lastPlay` type 2, ≈ 3 min until the first snap; mapped to END_PERIOD of the previous period for periods 2 / 4 (lead default, pending the owner) | WP-1 | `scoreboard-live-change-of-ends-2026-11-16.json`; `m6-nfl-stacked-yds.md` §1.12 |

The follow reads the per-event `scoreboard/{id}` first (`data/.../EspnScoreSource.kt:58, 139-150, 212`, review); it
carries the same `situation`.

### 3.2 Contract additions (trailing, defaulted)

- `LeagueDetail.Nfl(…, awayTimeouts: Int? = null, homeTimeouts: Int? = null)`: lenient `int()`, kept in
  `0..NFL_TIMEOUTS_MAX` (3), else null.
- `NflLastPlay(…, penalty: Boolean = false)`: `typeId == "8"` **or** `text` contains `"PENALTY on"` (case-sensitive).
  The text is not stored. A declined foul ("Penalty on …") is false.
- `data class NflTimeoutMark(val side: Side, val left: Int, val confirmed: Boolean)`: `confirmed` = from a type 21 (the
  announcement is made only then, Rec 9); false = the fallback.
- `NflView(…, awayTimeouts: Int? = null, homeTimeouts: Int? = null, timeoutSlots: Int? = null,
  timeout: NflTimeoutMark? = null, flagId: String? = null)`: `flagId` is the penalised `lastPlay` id while the flag mark
  holds (the live region announces once per id).
- `ScoreBugView.period` is also set on the NFL path (`ScoreBugMachine.kt:722`, the B1 line's twin) so the clock column
  keeps "4TH" while stale or waiting.
- **Pill impact (recorded, as ST C N1):** the new fields are in the views' equality, so a count change or a mark start/end
  emits a view; the Pill recomposes and draws the same frame. A mark's end is a view without a snapshot, as the goal mark's
  end is today. Existing NFL tests build `NflView` positionally and make no whole-view NFL assertions (review).

### 3.3 Machine rules (`ScoreBugMachine`, NFL branch; state written only in `advanceDisplay`, NFL §3.6; reset by `reset()` and the "back in time" path)

**Play-id high-water.** `maxPlayId: Long?` = the largest `lastPlay.id.toLongOrNull()` seen in the follow (ids are event
id + sequence, e.g. `401873061503` < `…520` < `…536`). "A new play" means an id strictly greater than `maxPlayId`. A
non-numeric id is never new (no mark). *(Fix pass C-N1)* An id in the ring of the last 32 shown ids is never new; after 3
consecutive shown entries below the high-water (a permanent downward correction), the high-water drops to the highest
of them.

**Counts and the low-water mark (RC2).** Half groups: periods 1–2, 3–4, then one per pair of overtime periods (5–6,
7–8, …; fix pass C-N3). Per group and side keep `low` = the lowest count seen and `slots` = the highest count seen
(≤ 3). A new group resets both to the first counts seen in it. Increases never mark and never raise `low`, with one
exception (fix pass C-S2): in a regulation group, while its re-seed window is open (from the group's first entry until
the first new play after that entry, or the group's first mark), both counts at 3 re-seed `low` at 3 / 3 and forget a
pending 21. `NflView.away/homeTimeouts` are the shown entry's counts when present, else the
group's last shown counts (held through stale, waiting and a break with no `situation`), else null (pips hidden).
`timeoutSlots` = the group's `slots` (RC7: 3 in regulation; in OT whatever ESPN starts the group at).

**Timeout mark (RC1).** On a newly shown fresh entry (SEEN demand, not the follow's first view):
- its `lastPlay` is type **21** with a new id → the caller is the side whose count is **below its `low`**; `low` updates;
  mark `NflTimeoutMark(side, count, confirmed = true)`. If neither count has dropped yet, the check repeats on each
  later entry while that 21 is still the `lastPlay`. If both dropped, no mark;
- **fallback**: no new 21, and exactly one side's count is exactly 1 below its `low` → `NflTimeoutMark(side, count,
  confirmed = false)` for `NFL_TIMEOUT_FALLBACK_MS` **8 000**. This covers a 21 that fell in a poll gap and a **lost
  challenge** (charged with no 21): "TIMEOUT X | n LEFT" for 8 s is accurate there (Rec 6, do not "fix").
- A confirmed mark lasts **while that type-21 play is the shown `lastPlay`**, and ends on a new `lastPlay` id, a flagged
  score, a phase other than LIVE, a stale or waiting view, or `NFL_TIMEOUT_MARK_MAX_MS` **180 000**. The clock is not
  used (clock lag, §3.1).
- Types 74, 75, 2, 65, 66 never set a mark. `lastPlay.text` and `lastPlay.team` are never used for the caller.
- *(Fix pass C-N2)* The confirmed (type-21) start has **no freshness check**, deliberately: a new type 21 is itself
  proof that a team timeout happened, and the mark is bounded by `NFL_TIMEOUT_MARK_MAX_MS` (180 s) and by the next
  `lastPlay`. After a long gap the caller is still the side below its low-water mark; if only the older drop is
  visible, the older caller can be named, for at most one 21's hold. The flag and the fallback keep the 100-s gate.

**Flag mark (RC3).** On a newly shown fresh entry (SEEN demand, not the first view) whose `lastPlay` has a **new id** and
`penalty` true → `flagId = id` for `NFL_FLAG_MARK_MS` **8 000** on the machine clock (from when the entry is shown). Not
started when the same entry flags a score (the score wins). A newer `lastPlay` id ends it (a new penalty starts a new
one). A flag and a timeout never coexist: the newer `lastPlay` ends the older mark (RC4).

**Unchanged:** inference, try, red-zone gating, kinds, the goal mark, stale/hide/expiry.

### 3.4 `StackedNflTargets.of(view)` (`:core`, pure; the UI never re-derives state)

Precedence (RC4): **pinned score (runner) > stale / waiting > break / final > flag > timeout > play.**

| View state | Ball / wide stripe | Clock column | Bar (kind, colours, words) | Source |
|---|---|---|---|---|
| PRE / SCHEDULED | nothing drawn (`view()` null) | | | R4 |
| **Stale, in play** | none | open: quarter over **"No update"** (ST two-line fit), column .55 | **closed** | ST §8, NFL §3.4 (RC5) |
| **Waiting, in play** | none | open: quarter over **"Delayed"**, .55 | **closed** | ST §8 (RC5) |
| Stale / waiting in a break | none | folded | NO_UPDATE / DELAYED, neutral, words .55 (re-key from the break bar) | ST §8 |
| Waiting at Final (Delay > 0) | none | folded | DELAYED, then FINAL | ST C N10 |
| END_PERIOD | none | folded | END_PERIOD, neutral: "END OF 1ST" … "END OF 4TH", "END OF OT" | SR4a analogy |
| HALFTIME | none | folded | HALFTIME, neutral | mock |
| FINAL | none | folded | FINAL, neutral: "FINAL" / "FINAL/OT" | ST final look |
| Flag mark | as play | quarter / clock | FLAG, yellow: [flag] "FLAG ON THE PLAY", shake + wave | mock |
| Timeout mark | as play | quarter / clock | TIMEOUT, caller: "TIMEOUT BUF \| 1 LEFT" ("0 LEFT" at zero); that side's pip burns | mock |
| LIVE, a down, side known | possession side | quarter / clock | PLAY, possession team: "1ST & 10 \| KC 35"; "3RD \| KC 35" (distance unknown); "4TH & GOAL \| BUF 2"; "… \| 50" midfield; no field: label alone | mock |
| … in the red zone (NFL §3.5 gating) | possession | as play | PLAY, sub "RED ZONE" on red | R26 |
| LIVE, a down, side unknown | none | as play | PLAY, **neutral**, same words: the field by its territory, "1ST & 10 \| KC 35" (`NflView.field`, fix pass C-N4) | S-D10 |
| LIVE, the try (`DownText.Pat`) | the trying side | as play | PAT, that team: "EXTRA POINT" (no spot: not reliable in the feed); "2-PT TRY" when the tab's tail is `TabTail.TwoPointTry` (the key's `tail`) | mock, S-D7, owner 2026-11-16 |
| LIVE, kick-off | kicker | as play | KICKOFF, kicker: "LV KICKOFF \| LV 35"; **never RED ZONE** (the bar reads the gated `redZone`/`TabTail`, never `isRedZone`; RC9) | mock |
| LIVE, dead ball after FG / safety | held side | as play | the held bar (no change): the down shown with the held tab (`NflView.heldDown`, Stacked only; fix pass C-S1) | NFL §3.4 |
| LIVE, no `situation` | none | as play | **closed** | R21 |
| Pinned score (runner) | pinned | pinned | from 216 ms "TOUCHDOWN \| KC +6", scorer's colours | mock + D10 |
| Between a pair's takeovers | pinned | pinned | "EXTRA POINT", scorer's colours | NFL lead 3 |
| Place mode, no follow | KC 24 – BUF 17, "4TH" / "3:42", "1ST & 10 \| KC 35", ball KC, pips 3/2 of 3 | open | PLAY | NFL §4.6 sample |

**YDS column (YDS 2026-11-16):** open whenever the view carries both teams' yards, except on the flag row and stale /
waiting in play; the bar is unchanged (an official timeout keeps its play / kick-off bar). `m6-nfl-stacked-yds.md` §2.

Pips: drawn whenever the view carries counts and slots, in every row above with rows (they are row content, never
dimmed); hidden when the counts are null.

Clock words: `SportsText.period(res, label, League.NFL).uppercase(Locale.ROOT)` ("1ST" … "4TH", "OT", "2OT"). ESPN shows
0:00 during untimed downs; it is drawn as 0:00 (NFL §3.1).

**Two-point try (owner ruling 2026-11-16).** The try's bar reads "2-PT TRY" (string `sports_tab_2pt_try`, upper case
like "EXTRA POINT") instead of "EXTRA POINT" when the machine marks the tab `TabTail.TwoPointTry` (NFL §3.3): a pending
try reads as a **two-point try** only when the shown play is a real `Down` at `tryAt` that is neither the `Down` shown
with the snapshot that set the try (the stale body that carried the score) nor the last `Down` shown before it (both
frozen when the try is set, so repeated polls of the same try-down keep the reading), is goal-to-go, and has the ball in
the opponent's half at yard ≤ 5 (`TWO_POINT_YARDS`; an unknown field is not). Same `PAT` kind, side and colours; the key
carries `tail = TwoPointTry` (a REKEY from "EXTRA POINT"). The pair's in-between bar stays "EXTRA POINT". A `Dead` try
(the only shape seen from ESPN) cannot be told from a kick and still reads "EXTRA POINT".

What the honest behaviour costs (recorded): the flag appears **after** the play (ESPN logs the foul with the resolved
play) for 8 s; a foul whose play is overtaken within one poll gap is missed (the newest `lastPlay` wins); a declined foul
shows no flag; a timeout whose 21 falls in a poll gap shows for 8 s (fallback).

---

## 4. Timeline

### 4.1 Layout tracks (`StackedNflTimeline`, `:core`; CSS-transition semantics, ST §7.1; allocation-free)

New object; `StackedTimeline` is not edited. CLOCK and the row stripes delegate to `StackedTimeline.sample` (identical
CSS); the rest is implemented here.

| Track | Transition | Property | Duration | Delay | Easing | Still |
|---|---|---|---|---|---|---|
| CLOCK | fold / open | as ST §7.1 (450 Std; open delay 350; contents 150 out, 300 in after 500) | | | | snap |
| STRIPE_AWAY/HOME | widen / narrow | 3.6 ⇄ 6.3 | 400 | 0 | ease | snap |
| BALL_AWAY/HOME | on | α 0 → 1 (300 ease); scale .4 → 1, rotate −40° → 0 (400 Spring) | | 0 | | snap |
| | off | the reverse | | 0 | | snap |
| PIPS_AWAY/HOME | count change | each pip lit ⇄ used (α .22) | 400 | 0 | ease | snap |
| | BURN (pip index = new count) | 4 × 400: lit → white α .15 at 200 → lit; then 400 ease to used | 2 000 | 0 | ease-in-out per half | used at once |
| BAR | OPEN (from closed) | height 0 → 33 | 400 | 0 | Std | snap |
| | | then the REKEY intro from 300 | | | | |
| | CLOSE | ST §7.1 close: height 400 Std; stripes 300 ease-in; text 150 ease | | | | snap |
| | REKEY (any content change while open) | old words and stripes gone at 0; background old → new | 350 | 0 | ease | cut |
| | | stripe A x −63 → 0 / stripe B | 450 / 450 | 0 / 80 | Spring | |
| | | text α 0 → 1 and x −11.7 → 0 (both on the keyframe's Spring) | 450 | 100 | Spring | |
| | | length 550 | | | | |
| FLAG (sub-track) | shake | text, divider, stripes x: 0 → −2.7 (20 %) → +2.7 (40 %) → −1.8 (60 %) → +1.8 (80 %) → 0, each segment ease-in-out | 500 × 2 | 150 | | none |
| | wave | icon −8° ⇄ +8° about (2.1, 13.8), alternate, while the flag bar shows (≤ 8 s) | 600 / half | 0 | ease-in-out | 0° |

- *(YDS 2026-11-16)* YDS track: open width 450 Std, contents α 300 / x 350 ease after 300; close contents 200, width
  450 Std after 200 (`m6-nfl-stacked-yds.md` §3.5).
- Interrupted transitions start from the current value with full duration and delay.
- REKEY rather than the NHL change/swap: the mock re-mounts the bar on every change, and the NFL bar changes each play
  (about every 30–40 s). A clock-only update never kicks it (target-key equality).
- Into a timeout: REKEY to the caller's bar and the BURN start together. Into halftime: ball and wide stripe off, clock
  folds, REKEY to "HALFTIME". Into stale in play: the bar CLOSEs and the clock slot changes; back to fresh: OPEN + intro.
- The wave is the one open-ended motion; it is bounded by the 8-s flag hold, so the idle "no redraw without a snapshot"
  budget (R6) holds outside it.

### 4.2 Flashes

| Element | Mock | Here | Rate |
|---|---|---|---|
| Panel flash | 4 × 300 from 420, .4 | 3 × 450 from 450, .40 (peaks 675, 1 125, 1 575) | 2.2 Hz |
| Posts hit | 3 × 300 from 1 650 | 3 × 450 from 1 800 (peaks 2 025, 2 475, 2 925) | 2.2 Hz |
| Pip burn | 5 × 320 | 4 × 400 | 2.5 Hz |
| Bar pulse | — | none | |

### 4.3 Scoring takeover (`NflScoreTimeline` + `NflScoreSpec.STACKED`; t = 0 at the takeover)

`NflScoreSpec` holds every number the timeline reads from `SportsConstants.NFL_*` today. `PILL` = today's values; the
existing `sample`, `samplePair`, `sampleStill` delegate to the `(NflScoreSpec.PILL, …)` overloads. A frozen
`LegacyNflScoreTimeline` (test source, copy at `412446e`) is compared **bitwise** on every `NflScoreFrame` field, t from
−1 to 9 700 in 0.25-ms steps plus NaN and +∞, every kind, single and pair, animated and still.

| Element | Start | Duration | Easing | STACKED (Standard px on the 333 box; ×k = x × boxW / 333) | PILL |
|---|---|---|---|---|---|
| Lead wipe | 0 | 3 600 | Wipe | in 0–216, out 3 384–3 600 | same |
| Panel wipe | 0 | 3 600 | Wipe | in 108–396, out 3 168–3 492 | same |
| Glow | 0 | 3 600 | ease-in-out | GA low/peak, baked for the exact pinned box | GA pill masks |
| Streak 1 (accent .55, w 49.5) | 350 | 1 000 | Std | x −90 → 450, skew −24° | .6, −85 → 616 |
| Streak 2 (white .30, w 18) | 1 800 | 800 | Std | as streak 1 | .35 |
| Ball run (TD, 2-PT, SAFETY; α ≤ .45, 31.5) | 300 | 1 300 | Roll | (−36, 38.25) → (450, 38.25), 0 → 900°; α 0 → .45 (15 %) → .45 (85 %) → 0 | .4, 34, (−42, 18) → (562, 18) |
| Ball arc (XP, FG; α ≤ .60) | 300 | 1 400 | ease | (13.5, 72) ×1 0° → 50 %: (135, −4.5) ×.75 360° → (238.5, 23.85) ×.45 720° (x ×k); α 0 → .6 (10 %) → .6 (85 %) → 0 | NFL §5.1 |
| Posts in | 200 | 400 | ease-out | α 0 → .4, dy 7.2 → 0, at (234 ×k, 22.5) | dy 8 |
| Posts hit | 1 800 | 3 × 450 | ease-in-out | .4 → .95 → .4 | same |
| Flash | 450 | 3 × 450 | ease-in-out | 0 → .40 → 0 | .42 |
| Badge | 450 | 600 | Spring | α 0, dx −22.5, ×.4, −25° → 70 %: α 1, dx +1.8, ×1.1, +4° → rest | −32 / +2 |
| Letters | 420 + 55 i | 420 | Spring | α 0, dy +27, ×1.9 → 60 %: dy −2.25, ×.94 → rest | +22 / −2 |
| Word pulse | 1 400 | 3 × 500 | ease-in-out | ×1.05 | same |
| "+N" and tally | 1 100 | 500 | Spring | α 0, dx +16.2, ×.6 → 70 %: dx −1.35, ×1.12 → rest | +19 / −1.6 |
| Bar | 216 | REKEY | | "TOUCHDOWN \| KC +6", scorer's colours, no pulse | Pill tab pulses |
| Score pop | 3 600 (first state from 3 168, GA §6.2) | 900 | Spring | ×1.5 → .9 → 1.06 → 1; `pop` → white | same |
| Row flash | 3 600 | 1 200 | ease-out | stripe colour at the C7 α → 0 | none |
| Release | 3 600 | | | bar REKEY to the current state; layout unpinned | |
| End | 4 800 | | | | 4 500 |
| Pair second / end | 4 800 / 9 600 | | | | 4 500 / 9 000 |

- `NflScoreSpec` fields: streak alphas/from/to, run (from, to, y, px, α), arc (points, scales, rotations, α), posts
  (x, y, w, h, dy), flash α, badge dx/mid dx, letter drop/overshoot, points dx/mid dx, `tabPulseCycles` (STACKED 0),
  `rowFlashMs` (STACKED 1 200, PILL 0), `endMs`, `pairSecondMs`, `pairEndMs`, `stillFrameBMs` (1 200 / 900).
- `NflScoreFrame.rowFlashAlpha: Float = 0f` (envelope 0..1); PILL writes 0 always.
- The machine marks are unchanged (`GOAL_MARK_MS`, `NFL_DOUBLE_MARK_MS`); the runner captures `nfl.scores` at the start
  and runs on the UI clock.
- Pair: 0–3 600 A; 3 600–4 800 tally A pops, row flash, bar "EXTRA POINT"; 4 800–8 400 B (bar "EXTRA POINT \| KC +1" or
  "TWO-POINT \| KC +2" from 5 016); 8 400–9 600 B's pop; release at 8 400. Digits follow the segment tally until 5 016.
- Trigger, hold, pin, release, back-to-back and cancel: ST §2.3 rules 1–7 and the pin (B4). At takeover start every layout
  track mid-transition snaps to its end (clock width, bar height); no layout track starts until the release; the bar's
  REKEY to the score label is the only bar change while pinned. A flag or timeout mark during the pin is shown at the
  release only if still active.

### 4.4 Still version (`motionOff()`, R20)

| Part | Still |
|---|---|
| §4.1 transitions | snap; REKEY cuts; no shake, wave (icon 0°) or burn (the pip goes straight to used) |
| Frame A (0–3 600) | panel at rest: badge, word, +N, tally; posts α .4 for XP/FG; bar shows the score label |
| Frame B (3 600–4 800) | plain box, the scorer's digit in `pop` at ×1, row tinted at `STACKED_STILL_ROW_TINT` |
| Pair | A1 0–3 600, B1 –4 800, A2 –8 400, B2 –9 600 |

### 4.5 TV-performance rules

No blur (GA masks baked for the exact box; ST nine-slice shadow; hard-offset logo shadow; no `setShadowLayer`). One leaf
layer (footprint + 2 M = **481 × 289** Standard, 568.25 × 328.25 Large; with the YDS column **544 × 289** / 647 × 328.25, YDS 2026-11-16), `clipRect` only, never an ancestor of the
surface; the rectangle box makes the Pill's stadium bands (NFL B5) unnecessary. Sprites baked once per size (`ALPHA_8`,
1.5×): the possession ball (fill + stroke), the takeover ball, the posts, the flag icon. The word atlas is the Pill's
de-duplicated glyph atlas at the Stacked sizes. Allocation-free draw (GA §2.5). Glow masks at the largest box ≈ 481 × 289
ALPHA_8 ≈ 139 KB at 1080p each (LRU 2 per size, ST §6).

---

## 5. Layout

### 5.1 Widths and footprint (RC6)

| State | Box width |
|---|---|
| Play, flag, timeout, stale / waiting in play | 333 (rows + clock) |
| Halftime, end of quarter, final, stale / waiting in a break | **243** (rows only) |
| *(YDS 2026-11-16)* an in-play stoppage with yards / a break or Final with yards | **396** / **306** |

`STACKED_NFL_W_MAX = STACKED_ROWS_W + STACKED_CLOCK_W` = **333**; height `STACKED_H + STACKED_BAR_H` = 141. Large
416.25 × 176.25. *(YDS 2026-11-16: the footprint is `STACKED_NFL_FOOTPRINT_W` = 396 × 141, Large 495 × 176.25; the table
below is v1's, superseded by `m6-nfl-stacked-yds.md` §3.2: top right (1428, 56)–(1824, 197), Large (1329, 56)–(1824,
232.25); top left right edge 492 / 591; halo (1354, −18)–(1898, 271), Large (1253, −20)–(1900, 308.25). The rows do not
move.)*

| Corner | Standard footprint | Large footprint | Rows | Bar |
|---|---|---|---|---|
| Top right (default) | (1491, 56)–(1824, 197) | (1407.75, 56)–(1824, 232.25) | mirrored, 1581–1824 (Large 1520.25–1824) | below |
| Top left | (96, 56)–(429, 197) | (96, 56)–(512.25, 232.25) | 96–339 (Large 96–399.75) | below |
| Bottom right | (1491, 883)–(1824, 1024) | (1407.75, 847.75)–(1824, 1024) | mirrored | above (R16) |
| Bottom left | (96, 883)–(429, 1024) | (96, 847.75)–(512.25, 1024) | 96–339 | above |

Margins 96 / 56 hold everywhere. Every SB §4.4 rule runs on the footprint: hide on an intersecting overlay (the picker
sheet (1376, 48)–(1872, 928) still covers both right footprints, so a right-corner change shows when the sheet closes,
ST §1.1); toast hides bottom left, reminder bottom right, passive bar hides a bottom bug; a user or quick bar lifts a
bottom footprint above it; subtitles move a bottom corner to the top. `glowAllowed` uses the footprint inflated by M
74 / 76: top right **(1417, −18)–(1898, 271)** Standard, (1331.75, −20)–(1900, 308.25) Large. The NFL Pill's 520-wide box
reached x 96–616 on the left; the Stacked footprint reaches 96–429, so the top-left channel list, stats and group card
hide it less often. The effective corner is from the placed box (`stackedBarUp`, `stackedMirrored`); the rows' outer
edge is snapped to a whole pixel (`stackedOriginDx`, ST L1); layout direction pinned `Ltr`. Place mode draws at the
anchored 333 box and positions the hint from the drawn box (ST N8).

### 5.2 The row (left corners; Standard)

| x from the row's left edge | Element |
|---|---|
| 0 – 3.6 (6.3 with possession) | stripe |
| stripe + 9 … + 63 | logo slot |
| stripe + 74.7 | code start (78.3 / **81.0**: the wider stripe moves logo, code, ball, pips by 2.7; the score stays, ST §4.3) |
| code end + 5.4 | ball 13.5 |
| code start | pips, top 40.05 |
| 183.6 – 228.6 | score slot, right-aligned at 228.6 |

Narrowest gap: 81.0 + 62.7 ("WSH") + 5.4 + 13.5 = 162.6 against "88"'s ink at ≈ 186.5: 23.9 px. `StackedNflFitTest`
asserts ≥ 12 for all 32 codes with "88" and the 3-digit fit, both sizes, both mirror states.

### 5.3 Right-hand mirror

The 2026-11-13 rules unchanged: row positions reflect about the rows' centre (x ↦ 243 − x); columns, bar and takeover
about the box (x' = box right − (x − box left) − w); alignments swap; nothing under a canvas reflection.

| Element | Mirrored |
|---|---|
| Stripe, logo, code, score | [score][ball][code][logo][stripe]; code right-aligned at stripe + 74.7 from the right edge; score left-aligned 14.4 from the left |
| Ball | its right edge 5.4 left of the code's ink; sprite not flipped; pop-in rotation negated (+40° → 0) |
| Pips | right-aligned at the code's right anchor; index 0 (first lit) nearest the logo, so lit pips are on the screen-edge side on both sides; the burn index mirrors |
| Clock column | outermost-left; centred stack, re-anchored only |
| Bar | full width; stripes 20.7 / 37.8 from the bar's **right** end, sliding in from it, "/" lean kept; words right-aligned 63 from the right end, reading left to right; REKEY's text slide starts on the **screen-edge (outer) side** (dx +11.7 → 0) (Rec 5) |
| Flag icon | before the words in reading order, right-aligned as a group; not flipped; shake dx negated; wave unchanged |
| Red-zone patch | under the sub text (the part after the divider in reading order) on both sides |
| Takeover | wipes start at the right; streaks right to left, "/" kept; badge, word, block at x ↦ boxW − x; block right-aligned, sliding from the inner side; badge spin negated |
| Ball run / arc | x' = boxW − x − 31.5 (right to left); rotations negated |
| Goalposts | x' = boxW − x − 40.5 (symmetric drawing) |
| Row flash, pop | the reflected row / slot |
| *(YDS 2026-11-16)* YDS column | `[clock][YDS][rows]` from the screen edge; contents at the rows-side edge, slide mirrored |

### 5.4 Codes, logos, scores
- Codes are 2–3 letters (WSH, LAR, LAC, JAX checked live). A longer one steps the code down 2 px to 18; never ellipsised.
- Missing logo: the `LogoDisc` (`StackedPainter.kt:603-617` behaviour): a 2–3 letter code in a translucent white disc,
  as the hockey bug; a 4+ letter code in the stripe-colour capsule (fix pass U-N5 corrected this sentence). The badge
  falls back to the same disc at 58.5.
- Scores 0–99 at 35.1 in 45; three digits scale to fit 43, floor 24 ("100" → 24.5). The tally slot widens by §2.5.

---

## 6. Colours

### 6.1 Decision
- Rows fixed navy `#12284F` (mock; ST lead ruling); columns `#15191F`, border `#2B3550`.
- Team colours: `NflTeamColours` through `NhlStackedColours.derive` (one derivation): `stripe`, `barBg` =
  `mixBlack(tab, .172)` (NFL `tab` = base for all 32, NFL S1), `barA`, `barB`, true `goal` colours, `rowFlashAlpha` (C7).
- **A separate `NflStackedColours` object** (Rec 4) holds the 32 resolutions + fallback (`NflTeamColours.FALLBACK`) and
  its own pair cache, calling `NhlStackedColours.derive` / `collides` (internal, unedited). `StackedColourTable`'s NFL arm
  (`StackedColourTable.kt:23`) becomes `League.NFL -> NflStackedColours.pair(away, home)`; the CHL `LeagueTable` and
  `NhlStackedColours` are untouched.
- Every ST §5 rule is asserted for the NFL table (`NflStackedColoursTest`).

### 6.2 The 32 teams (the review verified this table bit-for-bit against the Kotlin derivation)

ΔE row = CIELAB ΔE76 between the bar and the row navy (Rec 2).

| Team | Base | Accent | Stripe (from) | :row | Swap stripe | Bar bg | White:bar | Bar A | A:bar | Bar B | B:bar | ΔE row | Flash α | Pop |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ARI | `#97233F` | `#000000` | `#B15A6F` (base) | 3.16 | `#737373` | `#7D1D34` | 10.01 | `#000000` | 2.10 | `#C17B8C` | 3.09 | 52.6 | 0.45 | `#C68695` |
| ATL | `#A71930` | `#000000` | `#BD5264` (base) | 3.16 | `#737373` | `#8A1528` | 9.47 | `#000000` | 2.22 | `#CA7583` | 2.88 | 64.3 | 0.45 | `#CF808D` |
| BAL | `#241773` | `#9E7C0C` | `#9E7C0C` (accent) | 3.70 | `#7C74AB` | `#1E135F` | 15.99 | `#9E7C0C` | 4.07 | `#FFFFFF` | 15.99 | 28.6 | 0.40 | `#B1963D` |
| BUF | `#00338D` | `#C60C30` | `#D13D59` (accent) | 3.14 | `#597AB5` | `#002A75` | 13.22 | `#C60C30` | 2.20 | `#6685BB` | 3.55 | 24.1 | 0.50 | `#E0798D` |
| CAR | `#0085CA` | `#101820` | `#0085CA` (base) | 3.61 | `#707479` | `#006EA7` | 5.54 | `#101820` | 3.23 | `#66B6DF` | 2.46 | 31.9 | 0.40 | `#40A4D7` |
| CHI | `#0B162A` | `#C83803` | `#CE4C1C` (accent) | 3.24 | `#6D737F` | `#091223` | 18.71 | `#C83803` | 3.58 | `#FFFFFF` | 18.71 | **18.4** | 0.50 | `#DB7E5B` |
| CIN | `#FB4F14` | `#000000` | `#FB4F14` (base) | 4.32 | `#737373` | `#D04111` | 4.72 | `#000000` | 4.45 | `#FD9572` | 2.19 | 100.1 | 0.40 | `#FC6937` |
| CLE | `#311D00` | `#FF3C00` | `#FF3C00` (accent) | 4.09 | `#837766` | `#291800` | 17.14 | `#FF3C00` | 4.82 | `#FFFFFF` | 17.14 | 42.1 | 0.45 | `#FF6333` |
| DAL | `#041E42` | `#869397` | `#869397` (accent) | 4.60 | `#68788E` | `#031937` | 17.53 | `#869397` | 5.54 | `#FFFFFF` | 17.53 | **9.0** | 0.30 | `#8C989C` |
| DEN | `#FB4F14` | `#002244` | `#FB4F14` (base) | 4.32 | `#667A8F` | `#D04111` | 4.72 | `#002244` | 3.39 | `#FD9572` | 2.19 | 100.1 | 0.40 | `#FC6937` |
| DET | `#0076B6` | `#B0B7BC` | `#B0B7BC` (accent) | 7.17 | `#0D7DBA` | `#006297` | 6.57 | `#B0B7BC` | 3.24 | `#FFFFFF` | 6.57 | 26.8 | 0.40 | `#B0B7BC` |
| GB | `#203731` | `#FFB612` | `#FFB612` (accent) | 8.29 | `#6E7D79` | `#1A2E29` | 14.32 | `#FFB612` | 8.15 | `#798783` | 3.82 | 31.7 | 0.45 | `#FFB612` |
| HOU | `#03202F` | `#A71930` | `#BD5264` (accent) | 3.16 | `#687982` | `#021A27` | 17.80 | `#A71930` | 2.39 | `#687982` | 3.94 | 20.1 | 0.45 | `#CF808D` |
| IND | `#002C5F` | `#A2AAAD` | `#A2AAAD` (accent) | 6.16 | `#597697` | `#00244F` | 15.38 | `#A2AAAD` | 6.51 | `#66809F` | 3.77 | **3.9** | 0.35 | `#A2AAAD` |
| JAX | `#006778` | `#D7A22A` | `#D7A22A` (accent) | 6.30 | `#267E8C` | `#005563` | 8.47 | `#D7A22A` | 3.67 | `#FFFFFF` | 8.47 | 30.8 | 0.40 | `#D7A22A` |
| KC | `#E31837` | `#FFB81C` | `#FFB81C` (accent) | 8.41 | `#E31837` | `#BC142E` | 6.41 | `#FFB81C` | 3.70 | `#EE7487` | 2.28 | 84.4 | 0.45 | `#FFB81C` |
| LAC | `#0080C6` | `#FFC20E` | `#FFC20E` (accent) | 9.00 | `#0080C6` | `#006AA4` | 5.84 | `#FFC20E` | 3.61 | `#FFFFFF` | 5.84 | 30.4 | 0.45 | `#FFC20E` |
| LAR | `#003594` | `#FFA300` | `#FFA300` (accent) | 7.27 | `#4D72B4` | `#002C7B` | 12.76 | `#FFA300` | 6.37 | `#6686BF` | 3.48 | 26.5 | 0.40 | `#FFA300` |
| LV | `#000000` | `#A5ACAF` | `#A5ACAF` (accent) | 6.32 | `#737373` | `#000000` | 21.00 | `#A5ACAF` | 9.12 | `#666666` | 3.66 | 32.0 | 0.35 | `#A5ACAF` |
| MIA | `#008E97` | `#FC4C02` | `#FC4C02` (accent) | 4.28 | `#008E97` | `#00767D` | 5.40 | `#66BBC1` | 2.43 | `#FFFFFF` | 5.40 | 44.9 | 0.40 | `#FC6728` |
| MIN | `#4F2683` | `#FFC62F` | `#FFC62F` (accent) | 9.27 | `#8467A8` | `#411F6C` | 12.83 | `#FFC62F` | 8.17 | `#957DB5` | 3.59 | 29.6 | 0.45 | `#FFC62F` |
| NE | `#002244` | `#C60C30` | `#D13D59` (accent) | 3.14 | `#667A8F` | `#001C38` | 17.17 | `#C60C30` | 2.86 | `#FFFFFF` | 17.17 | **9.6** | 0.50 | `#E0798D` |
| NO | `#101820` | `#D3BC8D` | `#D3BC8D` (accent) | 7.87 | `#707479` | `#0D141A` | 18.55 | `#D3BC8D` | 10.02 | `#707479` | 3.94 | 25.2 | 0.40 | `#D3BC8D` |
| NYG | `#0B2265` | `#A71930` | `#BD5264` (accent) | 3.16 | `#6D7AA3` | `#091C54` | 16.08 | `#A71930` | 2.16 | `#6D7AA3` | 3.80 | **14.4** | 0.45 | `#CF808D` |
| NYJ | `#125740` | `#000000` | `#4D8170` (base) | 3.25 | `#737373` | `#0F4835` | 10.51 | `#719A8C` | 3.35 | `#FFFFFF` | 10.51 | 45.7 | 0.45 | `#7DA396` |
| PHI | `#004C54` | `#A5ACAF` | `#A5ACAF` (accent) | 6.32 | `#4D8287` | `#003F46` | 11.67 | `#A5ACAF` | 5.07 | `#FFFFFF` | 11.67 | 28.6 | 0.35 | `#A5ACAF` |
| PIT | `#101820` | `#FFB612` | `#FFB612` (accent) | 8.29 | `#707479` | `#0D141A` | 18.55 | `#FFB612` | 10.56 | `#707479` | 3.94 | 25.2 | 0.45 | `#FFB612` |
| SEA | `#002244` | `#69BE28` | `#69BE28` (accent) | 6.24 | `#667A8F` | `#001C38` | 17.17 | `#69BE28` | 7.35 | `#667A8F` | 3.88 | **9.6** | 0.35 | `#69BE28` |
| SF | `#AA0000` | `#B3995D` | `#B3995D` (accent) | 5.29 | `#C44D4D` | `#8D0000` | 9.85 | `#B3995D` | 3.58 | `#FFFFFF` | 9.85 | 82.6 | 0.30 | `#B3995D` |
| TB | `#D50A0A` | `#34302B` | `#DB2F2F` (base) | 3.09 | `#7B7875` | `#B00808` | 7.28 | `#E66C6C` | 2.33 | `#FFFFFF` | 7.28 | 93.5 | 0.55 | `#E87878` |
| TEN | `#0C2340` | `#4B92DB` | `#4B92DB` (accent) | 4.46 | `#6D7B8C` | `#0A1D35` | 16.93 | `#4B92DB` | 5.19 | `#FFFFFF` | 16.93 | **11.4** | 0.30 | `#5D9DDF` |
| WSH | `#5A1414` | `#FFB612` | `#FFB612` (accent) | 8.29 | `#946666` | `#4B1111` | 15.14 | `#FFB612` | 8.61 | `#9C7272` | 3.65 | 46.9 | 0.45 | `#FFB612` |
| fallback | `#2A3140` | `#5CA8F2` | `#5CA8F2` (accent) | 5.78 | `#757983` | `#232935` | 14.58 | `#5CA8F2` | 5.79 | `#7F838C` | 3.84 | **18.9** | 0.35 | `#5CA8F2` |

- Stripe ≥ 3.09 : 1 against the row (TB), ≥ 3.25 / 3.17 (swap) against the neutral bar; white on every bar ≥ 4.72
  (CIN, DEN); A ≥ 2.10, B ≥ 2.19, A–B ≥ 1.51; flash α .30–.55.
- **Bars that look like the rows** (ΔE76 < 20, the project's look-alike threshold): **IND 3.9, DAL 9.0, NE 9.6, SEA 9.6,
  TEN 11.4, NYG 14.4, CHI 18.4**, fallback 18.9; the neutral bar is 7.8. SR3 covers them (hairline + bright stripes), but
  the NFL bar is open through all play, so **IND @ NE** and **DAL @ NYG** are in the screenshot set and the owner's
  screenshots. (22 bars are under 1.5 : 1 by luminance, but 15 of those differ clearly in hue.)
- **Stripe collisions:** 59 unordered pairs; all 118 ordered collisions resolve (116 by the home swap; DAL @ LV and
  DAL @ PHI by the away swap). Routine ones: BUF–NE, NE–NYG, CIN–CLE, KC–LAC, GB–MIN, DAL–PHI.
- **Deviations from the mock** (from the carried rule): KC's stripe is gold (mock red), BUF's red `#D13D59` (mock blue);
  BUF's bar `#002A75` (mock `#1D4ED8`); BUF's pop pink (NFL D2). KC's and BUF's goal panels match the mock.

### 6.3 Fixed colours

| Use | Colour | Check |
|---|---|---|
| Rows | `#12284F` | white 14.56 |
| Columns | `#15191F`, border `#2B3550`, inset `#2B3240`, rule `#5B6170` | |
| Neutral bar | `#1B2440`, away / home row stripes | white 15.30 |
| Flag bar | `#FFD100`; text, divider (.4), icon, stripe A `#111111`; stripe B `#806800` | text 12.92; A–B 3.51 |
| Red-zone sub | `#A60D26` | white 7.76; beside KC's bar ΔE 7.5 (carried NFL §14 default) |
| Pips | lit `#FFD34D`, used white α .22 | 10.18 |
| Ball | fill `#8B4513`, stroke white | |

---

## 7. Settings and sheet

One shared setting (Owner Q1 default). Reasons: CHL P12 extended the one setting to every hockey league rather than
adding per-league ones; "Score bug style" is sport-neutral; one sheet row; a Pill user (the default) sees no change. Cost:
a user who chose Stacked for hockey gets the Stacked NFL bug after the update. The alternative (`nflStyle`, default Pill)
is one trailing field and a second gate.

| Where | Change |
|---|---|
| `SportsPrefs.nhlStyle` | name unchanged; KDoc "every league"; no migration |
| `ui/.../sports/ScoreBug.kt:185` | `val style = prefs.nhlStyle`; `Sport.FOOTBALL -> if (style == STACKED) StackedNflBug(…) else NflBug(…)` (NFL + PILL keeps the same call and key) |
| `ScoreBugPlacement.width/height(size, league, style)` (`:162-174`) | a new first branch `league == NFL && style == STACKED` → `STACKED_NFL_W_MAX` / `STACKED_H + STACKED_BAR_H` × scale; the existing conditions untouched |
| `PlayerStateMachineImpl.kt:2112` | the Style row shows whenever `style != null` |
| `SportsContracts.kt:572` KDoc | "forced to PILL … not hockey" removed |
| `strings_sports.xml` `settings_sports_style_desc` | "Stacked shows the teams one above the other, with a bar for power plays, possession and breaks." |

Words unchanged: "Score bug style", "Style", "Pill", "Stacked". Never "theme".

---

## 8. Contracts and file plan

### 8.1 Commit 0 (lead, Opus): exact contracts and stubs, so every WP compiles alone

**`core/.../sports/SportsContracts.kt`** (trailing, defaulted):
```kotlin
// LeagueDetail.Nfl gains:
val awayTimeouts: Int? = null, val homeTimeouts: Int? = null,
// NflLastPlay gains:
val penalty: Boolean = false,
/** (NFL Stacked §3.3) A team timeout on the bar: [confirmed] = from a type-21 lastPlay (announced); false = the 8-s fallback. */
data class NflTimeoutMark(val side: Side, val left: Int, val confirmed: Boolean)
// NflView gains:
val awayTimeouts: Int? = null, val homeTimeouts: Int? = null, val timeoutSlots: Int? = null,
val timeout: NflTimeoutMark? = null, val flagId: String? = null,
```
**`core/.../sports/ScoreBugMachine.kt`:** only `period = label` in the NFL `ScoreBugView(...)` at `:722`.

**`core/.../sports/StackedNflLayout.kt`** (new):
```kotlin
enum class NflBarKind { PLAY, PAT, KICKOFF, FLAG, TIMEOUT, END_PERIOD, HALFTIME, FINAL, NO_UPDATE, DELAYED, SCORE } // append-only
data class NflBarKey(
    val kind: NflBarKind, val side: Side?, val down: DownText? = null, val tail: TabTail? = null, val left: Int? = null,
    val period: PeriodLabel? = null, val finalKind: PeriodKind? = null, val score: NflScoreKind? = null, val points: Int? = null,
)
data class StackedNflTargets(
    val clockOpen: Boolean, val bar: NflBarKey?, val possSide: Side?, val period: PeriodLabel?, val clockSlot: ClockSlot,
    val columnDim: Boolean, val barDim: Boolean, val awayTimeouts: Int?, val homeTimeouts: Int?, val timeoutSlots: Int,
    val burnSide: Side?,
) { companion object { fun of(v: ScoreBugView): StackedNflTargets /* stub: play state */ } }
```
**`core/.../sports/StackedNflTimeline.kt`** (new):
```kotlin
enum class StackedNflTrack { CLOCK, BAR, STRIPE_AWAY, STRIPE_HOME, BALL_AWAY, BALL_HOME, PIPS_AWAY, PIPS_HOME } // append-only
enum class StackedNflTransition { OPEN, CLOSE, REKEY, BURN, STILL }                                                // append-only
class StackedNflFrame {
    var clockWidth = 1f; var clockAlpha = 1f
    var barHeight = 0f; var stripeAX = K.STACKED_BAR_STRIPE_TRAVEL; var stripeBX = K.STACKED_BAR_STRIPE_TRAVEL
    var barTextAlpha = 0f; var barTextDx = K.STACKED_NFL_REKEY_TEXT_DX; var barBgMix = 1f; var barClosed = true
    var shakeDx = 0f; var waveDeg = 0f
    var stripeAway = 0f; var stripeHome = 0f
    var ballAwayAlpha = 0f; var ballAwayScale = 1f; var ballAwayDeg = 0f; var ballHomeAlpha = 0f; var ballHomeScale = 1f; var ballHomeDeg = 0f
    val pipAway = FloatArray(3) /* 1 lit … 0 used */; val pipHome = FloatArray(3); val pipAwayBurn = FloatArray(3); val pipHomeBurn = FloatArray(3)
    val boxW: Float get() = K.STACKED_ROWS_W + clockWidth * K.STACKED_CLOCK_W
    val barH: Float get() = barHeight * K.STACKED_BAR_H
    fun copyFrom(o: StackedNflFrame)
}
object StackedNflTimeline {
    fun sample(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame
    fun samplePips(side: Side, kind: StackedNflTransition, fromLit: Int, toLit: Int, tMs: Float, from: StackedNflFrame, out: StackedNflFrame): StackedNflFrame
    fun sampleFlag(tMs: Float, still: Boolean, out: StackedNflFrame): StackedNflFrame
    fun samplePinned(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame
    fun totalMs(track: StackedNflTrack, kind: StackedNflTransition): Float
}   // stubs write the rest state; totalMs 0
```
**`core/.../sports/NflScoreTimeline.kt`:** `class NflScoreSpec(…fields of §4.3…) { companion object { val PILL; val STACKED /* = PILL placeholder */ } }`;
`sample(spec, kind, tMs, out)`, `samplePair(spec, first, second, tMs, out)`, `sampleStill(spec, first, second, tMs, out)`;
the old overloads delegate with `PILL`; `NflScoreFrame.rowFlashAlpha = 0f` (in `copyFrom`). Checked bit-identical by a
throwaway legacy comparison before commit (ST commit-0 note 9).

**`core/.../sports/NflStackedColours.kt`** (new): `object NflStackedColours { fun pair(away: String?, home: String?): StackedPair /* stub: NhlStackedColours.pair(null, null) */ }`.

**`core/.../sports/ScoreBugPlacement.kt`:** the `league == NFL && style == STACKED` branch returning the **Pill** sizes
(stub, ST commit-0 note 14), so the commit-0 fallback (the Pill drawn by `StackedNflBug`) is placed as the Pill.

**`core/.../sports/SportsConstants.kt`:** the §8.5 block; `StackedNflMock`.

**`ui/.../sports/StackedNflContracts.kt`** (new, lead-owned; the WP-3 ↔ WP-4 seam):
```kotlin
internal class StackedNflFrameState(
    val awayAbbrev: String, val homeAbbrev: String, val awayScore: String, val homeScore: String,
    val periodText: String, val periodPx: Float, val slot: ClockSlot, val slotText: String, val slotPx: Float,
    val clockSecs: Int, val columnDim: Boolean, val targets: StackedNflTargets,
    val slotBreak: Int = -1, val slotDy1: Float = 0f, val slotDy2: Float = 0f,
)
internal class NflBarContent {      // captured by the runner per REKEY / OPEN; filled by the painter's fillBar
    var key: NflBarKey? = null; val label = CharArray(MAX); var labelLen = 0; val sub = CharArray(MAX); var subLen = 0
    var redZone = false; var flag = false; var dim = false; var bg = 0; var a = 0; var b = 0; var fg = 0
    fun clear(); companion object { const val MAX = 48 }
}
internal class StackedNflDrawArgs {
    var frame: StackedNflFrame? = null; var state: StackedNflFrameState? = null
    var bar: NflBarContent? = null; var barPrev: NflBarContent? = null; var colours: StackedPair? = null
    var awayLogo: Bitmap? = null; var homeLogo: Bitmap? = null; var mirrored = false; var up = false; var still = false
    var score: NflScoreFrame? = null; var scoreSide: Side? = null; var takeoverOn = false
}
```
**`ui/.../sports/StackedNflPainter.kt`** (stub, final signatures):
`internal class StackedNflPainter(s: Float, large: Boolean, sansBold: Typeface, barFont: Typeface) { fun frame(v: ScoreBugView, targets: StackedNflTargets, res: Resources): StackedNflFrameState; fun fillBar(content: NflBarContent, key: NflBarKey, pair: StackedPair?, awayAbbrev: String, homeAbbrev: String, res: Resources); fun bakeShadow(): NineSlice?; fun drawFrame(c: Canvas, a: StackedNflDrawArgs) }`.

**`ui/.../sports/StackedNflScorePainter.kt`** (stub):
`internal class StackedNflScorePainter(s: Float, large: Boolean, wordFont: Typeface, sansBold: Typeface, spec: NflScoreSpec = NflScoreSpec.STACKED) { fun begin(side: Side, scores: List<NflScore>, colours: TeamColours, badge: Bitmap?, awayAbbrev: String, homeAbbrev: String, boxW: Float); fun bakeGlow(w: Float, h: Float): GlowMasks?; fun drawGlow(c: Canvas, g: NflScoreFrame, glow: GlowMasks?, x: Float, y: Float); fun drawTakeover(c: Canvas, g: NflScoreFrame, mirrored: Boolean) }`.

**`ui/.../sports/StackedNflBug.kt`** (stub; WP-4 owns the body):
```kotlin
@Composable internal fun StackedNflBug(source: ScoreBugSource, placement: PlacementInput, geometry: OverlayGeometry,
    modifier: Modifier, place: (PlacementInput, OverlayGeometry) -> DesignBox?, hooks: StackedNflHooks)  // body: NflBug(...)
internal class StackedNflHooks(
    val colours: (String?, String?) -> StackedPair = { a, h -> StackedColourTable.pair(League.NFL, a, h) },
    val sampler: StackedNflSampler = StackedNflSampler.Real,
    val scoreSampler: NflScoreSampler = NflScoreSampler { t, still, k1, k2, out -> /* NflScoreTimeline with STACKED */ out },
    val bakeGlow: (StackedNflScorePainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null, val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default, val probe: StackedNflProbe? = null,
) { companion object { val Default = StackedNflHooks() } }
```
**Other commit-0 files:** `ui/.../sports/ScoreBug.kt` (the §7 branch), `core/.../full/state/PlayerStateMachineImpl.kt:2112`
(the gate), `SportsContracts.kt:572` KDoc, `SportsPrefs.kt` KDoc, `ui/src/main/res/values/strings_sports.xml` (§8.4 and
the description), `ui/build.gradle.kts` (`sportsShotDirs += "score-bug-stacked-nfl"`), `ScoreBugPlayerTest.kt` (the one
changed case: "NFL followed → Style row shown"), and the eight fixtures of §3.1 (added by this revision).

### 8.2 Work packages (disjoint files; each gate: `./gradlew :core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug lintAll :ui:sportsHashGate`, plus `python3 -m unittest tools/fakepanel/test_sports_nfl.py` where touched)

| WP | Model | Files (only these) | Tests (new unless stated) |
|---|---|---|---|
| **WP-1 data + machine** | Opus | `core/.../sports/EspnMapper.kt`, `core/.../sports/ScoreBugMachine.kt` | `EspnMapperStackedTest.kt`, `ScoreBugMachineNflStackedTest.kt` |
| **WP-2 pure layout, timelines, colours, placement** | Opus (timeline spec, layout); Sonnet may take colours | `StackedNflLayout.kt`, `StackedNflTimeline.kt`, `NflScoreTimeline.kt` (STACKED values), `NflStackedColours.kt`, `ScoreBugPlacement.kt` (real NFL footprint), `SportsConstants.kt` (additions only) | `StackedNflLayoutTest.kt`, `StackedNflTimelineTest.kt`, `NflScoreSpecTest.kt` + `LegacyNflScoreTimeline.kt`, `NflStackedColoursTest.kt`, `NflStackedPlacementTest.kt` |
| **WP-3 painters** | Opus (score painter), Sonnet (box painter) | `ui/.../sports/StackedNflPainter.kt`, `StackedNflScorePainter.kt` | `StackedNflScreenshotTest.kt` (hand-built frames), `StackedNflFitTest.kt`, `StackedNflPainterAllocationTest.kt` |
| **WP-4 runner** | Opus | `ui/.../sports/StackedNflBug.kt` | `StackedNflDrawTest.kt`, `StackedNflSeamTest.kt`, `StackedNflBoundsTest.kt`, `StackedNflLogoTest.kt`; `app/src/androidTest/.../StackedNflFramesTest.kt` |
| **WP-5 fake panel, sheet tests** | Sonnet | `tools/fakepanel/sports_nfl.py`, `tools/fakepanel/test_sports_nfl.py`, `tools/fakepanel/README.md` (the keys) | `NflStyleRowTest.kt` (core) |

- No file is in two WPs. WP-2 owns `SportsConstants.kt` additions; the others read constants and never edit it.
- Order: WP-4 lands with or before WP-2's placement (ST commit-0 note 14: the real footprint and the runner go together).
- **Integration (lead):** `StackedNflStatesScreenshotTest` (real targets and colours); append the golden hashes after the
  owner approves the shots; the §9.5 hand pass; code review; fix pass.

### 8.3 Files that must not change
`StackedBug.kt`, `StackedPainter.kt`, `StackedGoalPainter.kt`, `StackedContracts.kt`, `StackedTimeline.kt`,
`StackedLayout.kt`, `NhlStackedColours.kt`, `ChlTeamColours.kt`, `GoalTimeline.kt`, `NflBug.kt`, `NflBugPainter.kt`,
`NflScorePainter.kt`, `NflTabPainter.kt`, `ScoreBugPainter.kt`, `GoalPainter.kt`, `PowerPlayPainter.kt`; the
`StackedColourTable` CHL `LeagueTable` path (only the NFL arm line changes). Reused unedited: `NineSlice`, `LiveText`,
`FollowLogos`, `LogoDisc`, `LogoTrim`, `Describe`, `Motion`, `stackedOriginDx`.

### 8.4 Strings (commit 0)
`sports_stacked_nfl_flag` "FLAG ON THE PLAY"; `sports_stacked_nfl_timeout` "TIMEOUT %1$s"; `sports_stacked_nfl_left`
"%1$d LEFT"; `sports_stacked_nfl_kickoff` "%1$s KICKOFF"; `sports_stacked_nfl_field` "%1$s %2$d"; `sports_stacked_nfl_mid`
"50"; `sports_stacked_nfl_end_of` "END OF %1$s"; `sports_stacked_nfl_halftime` "HALFTIME"; `sports_stacked_nfl_points`
"%1$s +%2$d"; `sports_desc_nfl_timeouts` "Timeouts left: %1$s %2$d, %3$s %4$d"; `sports_live_nfl_flag` "Flag on the
play." (once per `flagId`); `sports_live_nfl_timeout` "Timeout, %1$s." (confirmed marks only, Rec 9). Reused
upper-cased (`Locale.ROOT`): the Pill's down texts, "EXTRA POINT", "RED ZONE", the five score words, `sports_final*`,
`sports_no_update` / `sports_delayed` (clock slot), `sports_stacked_no_update` / `sports_stacked_delayed` (break bar).

### 8.5 Constants (`SportsConstants`; Standard, Large × 1.25)
`STACKED_NFL_W_MAX` 333 (= rows + clock; the play box), `STACKED_NFL_YDS_ENABLED` false (YDS 2026-11-16: **true**, with
`STACKED_NFL_YDS_*`, `STACKED_NFL_FOOTPRINT_W`, `NFL_YDS_*`; `m6-nfl-stacked-yds.md` §5.0), `STACKED_NFL_CODE_PX` 25.2,
`STACKED_NFL_CLOCK_PX` 27.9, `STACKED_NFL_BALL` 13.5, `STACKED_NFL_BALL_GAP` 5.4, `STACKED_NFL_PIP_W` 9.9, `_H` **3.0**,
`_R` .9, `_GAP` 2.7, `_TOP` 40.05, `STACKED_NFL_PIP_LIT` `#FFD34D`, `STACKED_NFL_PIP_USED_ALPHA` .22,
`STACKED_NFL_BURN_CYCLE_MS` 400, `_CYCLES` 4, `STACKED_NFL_BAR_TEXT_GAP` 10.8, `STACKED_NFL_FLAG_BG/FG/B`,
`STACKED_NFL_FLAG_ICON_W/H` 15.8 / 17.9, `STACKED_NFL_REKEY_BG_MS` 350, `_STRIPE_MS` 450, `_STRIPE_B_DELAY_MS` 80,
`_TEXT_DELAY_MS` 100, `_TEXT_MS` 450, `_TEXT_DX` −11.7, `_MS` 550, `STACKED_NFL_SHAKE_*` (150, 500 × 2, 2.7 / 1.8),
`STACKED_NFL_WAVE_*` (600, 8°, pivot 2.1 / 13.8), `STACKED_NFL_WORD_FLOOR_PX` 28, `STACKED_NFL_SCORE_FLOOR_PX` 24,
`NFL_TIMEOUTS_MAX` 3, `NFL_TIMEOUT_MARK_MAX_MS` 180 000, `NFL_TIMEOUT_FALLBACK_MS` 8 000, `NFL_FLAG_MARK_MS` 8 000,
`NFL_TYPE_PENALTY` "8", `NFL_TYPE_TIMEOUT` "21". Takeover numbers in `NflScoreSpec.STACKED`; mock geometry in
`StackedNflMock`.

---

## 9. Test plan

### 9.1 `:core`
- **`EspnMapperStackedTest`:** counts 3/3 from every 2026-11-08 live fixture; away 3 / home 2 from `…penalty-type8…`;
  away 2 / home 2 from `…team-timeout-type21…`; null without `situation`; null for −1, 4, "x"; "2" → 2. `penalty` true
  for `…penalty-type8…` and `scoreboard-live-td-score-first-2026-11-08.json`; true for a synthetic type-24 body with
  "PENALTY on"; **false** for a synthetic "Penalty on KC-…, declined." (type 3) and for the first-down fixture.
  **RC9:** `…official-timeout-kickoff-redzone…` maps to `Kickoff(HOME LV, field)` with `redZone == false` despite
  `isRedZone: true`. `…kickoff-return-no-possession…` maps to a `Down` with no possession.
- **`ScoreBugMachineNflStackedTest`:** the sequence penalty-type8 → team-timeout-type21 → team-timeout-held →
  after-timeout gives: flag (id `…503`) for 8 s, then a confirmed `TIMEOUT LAR | 2 LEFT` held across the held poll, ended
  by `…536`; the caller is LAR from the count (not `lastPlay.team`). Low-water: 3, 2, 3, 2 → one mark; the 1 → 3 reset at
  Q3 → none; a −2 or a two-sided drop → none. Fallback: a −1 with no 21 → 8-s unconfirmed mark (the lost challenge).
  Types 74/75/2/65/66 → none. A re-sent older id → no flag, no mark. A score in the same entry as a penalty → score only.
  The clock-lag pair ends nothing. Pips held through stale and through a break with no `situation`; slots = the
  highest count in the group (an OT group starting at 2 → 2 slots). `period` set on stale/waiting NFL views. Existing NFL
  machine tests unedited.
- **`StackedNflLayoutTest`:** every §3.4 row and the precedence; the kick-off fixture view → KICKOFF with no red;
  stale/waiting in play → bar null, slot `NoUpdate`/`Delayed`, `columnDim`.
- **`StackedNflTimelineTest`:** every §4.1 row (start, delay end, mid, end), REKEY, BURN, flag sub-track, interruption
  from the current value, allocation-free.
- **`NflScoreSpecTest`:** bitwise PILL vs `LegacyNflScoreTimeline`; STACKED differences; ≤ one flash peak per 450 ms (both
  specs, pairs); `rowFlashAlpha` 0 under PILL.
- **`NflStackedColoursTest`:** the §6.2 table, every ST §5 assertion for 32 + fallback, ΔE ≥ 20 after `pair()` for all
  ordered pairs (DAL @ LV, DAL @ PHI away swaps); `NhlStackedColoursTest` and the CHL colour tests unedited.
- **`NflStackedPlacementTest`:** §5.1 footprints, 4 corners × 2 sizes; every hide/lift rule; `glowAllowed` halo;
  `ScoreBugPlacementTest` and `NflPlacementTest` unedited.
- **`ScoreBugPlayerTest`** (one changed case, commit 0) and **`NflStyleRowTest`** (OK toggles, sheet stays open, focus kept).

### 9.2 `:ui` (Robolectric, `@GraphicsMode(NATIVE)`)
- **`StackedNflScreenshotTest`** → `ui/build/score-bug-stacked-nfl/`: every §3.4 state for KC @ BUF, plus **IND @ NE**,
  **DAL @ NYG**, BUF–NE, DAL @ PHI, LV–PIT, CIN–DEN; 4 corners × Standard / Large; over dark, white-ice and 50 % grey;
  takeover frames at 0 / 216 / 450 / 1 000 / 1 500 / 2 025 / 3 600 / 4 200 for each kind and a +7 pair at 3 600 / 5 016 /
  8 400; flag at 150 / 400; burn at 200 / 1 600; still frames; the pips rendered at 1280-wide. Asserted: white on rows
  ≥ 7, bar words ≥ 4.5, flag words ≥ 4.5; no ball/post pixel outside the top part.
- **`StackedNflFitTest`:** §2.4 strings × 32 codes at 20 / 25; the §2.5 fit at 243 / 333; §5.2 gaps; 3-digit scores;
  "15:00"; **pip height ≥ 2 real px at 1280, 1920 and 3840 wide**.
- **`StackedNflDrawTest`:** one composition per snapshot; a clock-only update redraws without a kick; ST S1 rules 1–7; the
  pin snaps clock width and bar height; score label at 216; marks deferred while pinned; style flip mid-takeover; no
  burn on the first view; stale in play closes the bar; a subtitle-moved bottom corner drops its bar down; one live
  announcement per `flagId`, none for an unconfirmed timeout.
- **`StackedNflSeamTest`** (NFL + STACKED → `StackedNflBug`; NFL + PILL → `NflBug`, no Stacked NFL painter built),
  **`StackedNflBoundsTest`** (layer = footprint + 2 M; `SCORE_BUG_TAG` = drawn box), **`StackedNflLogoTest`** (logos at
  58.5 × d).
- Unedited and green: every existing `Stacked*`, `Nfl*`, `ScoreBug*`, `Goal*`, `PowerPlay*`, `RootTreeRuleTest`,
  `NflRootTreeRuleTest`.

### 9.3 Hash gate
`./gradlew :ui:sportsHashGate` passes with the existing 1 260 lines unchanged. After the owner approves the shots, the
lead appends the `score-bug-stacked-nfl/` hashes (same host header).

### 9.4 Fake panel (`tools/fakepanel/sports_nfl.py`)
- `STATE_KEYS` += `awayTimeouts`, `homeTimeouts` (0..3 or `null` to omit), `lastPlayText`, `lastPlayId` (to script
  re-sent and older ids); `check_config` rejects others. `situation()` stops hard-coding 3/3 (`:479`).
- `PLAY_TYPES` += `"8": ("Penalty", "PEN")`, `"21": ("Timeout", "TO")`, `"3": ("Pass Incompletion", "INC")`,
  `"52": ("Punt", "PUNT")`, `"75": ("Two-minute warning", "2MW")`.
- Default timeline, **no new windows** (985 s, every window ≥ 25 s except the existing 10-s one):

  | Window | Event |
  |---|---|
  | Q1 75–100 (KC 3rd & 2 "BUF 38") | type 8, "… PENALTY on BUF-…, Defensive Holding, 5 yards …" → flag |
  | Q1 50–75 (KC 1st & 10 "KC 25") | type 24 with "PENALTY on BUF-…, Unnecessary Roughness, 15 yards" (accepted, play stands) → flag |
  | Q1 210–235 (BUF 2nd & 7) | type 3, "Penalty on KC-…, Offensive Holding, declined." → **no** flag |
  | Q1 160–185 (the KC kick-off, already `isRedZone` true; score 6 → 7) | `lastPlayType` 74 "Official Timeout" → "KC KICKOFF \| KC 35", no red (RC9); its text carries an **accepted** "PENALTY on KC-…, Unnecessary Roughness, 15 yards, enforced on the kickoff." → no flag (a flagged score: the score wins; fix pass C-N6) |
  | Q1 125–135 (the 10-s TD window) | `lastPlayText` with ESPN's mixed-case declined foul "Penalty on BUF-…, declined." → TOUCHDOWN only, no flag (fix pass C-N6: the old upper-case "declined" string does not occur) |
  | Q2 75–100 (KC red zone, two-minute warning) | type 75 "Two-minute warning", clock from 2:00, counts unchanged 3/3 → no bar (75 never marks; fix pass C-N7, moved from Q3) |
  | Q2 125–175 (BUF 1st & 10 "BUF 25") | `homeTimeouts` 2, type 21 team BUF ("Timeout #1 by BUF at 01:35.") → "TIMEOUT BUF \| 2 LEFT", burn |
  | Q3 0–25 | 3/3 (the half reset; no mark) |
  | Q4 0–25 (KC 3rd & 9) | `awayTimeouts` 2 with type 21 → mark |
  | Q4 25–50 | `awayTimeouts` 3 (a flap, no mark) |
  | Q4 50–75 (BUF 2nd & 6, a rush) | `awayTimeouts` 2 again, `lastPlayType` 5 (no 21, not a score): **no** mark (not below the low-water 2; fix pass C-N8, was on the FG body) |
  | Q4 75–100 (FG dead ball) | +3, type 59, counts unchanged (away 2); the Q4 BUF kick-off window was removed to keep 985 s |
  | Q4 100–125 (BUF 3rd & 9) | `homeTimeouts` 2 with no 21 → fallback 8-s mark (lost challenge) |
  | OT 0–25 / 25–50 | 2/2 (an OT group of 2 slots); OT 25–50 `homeTimeouts` 1, type 21 → mark, 2 slots drawn |
- `test_sports_nfl.py`: the new keys and rejections; each row above in the week and dated bodies; existing assertions on
  those windows' `lastPlay` updated in this NFL-only file and listed in the WP report.

### 9.5 Emulator / device checklist (`TheBox_TV_API30`, `fakepanel.py --port 8080 --sports --sports-nfl`)
1. Style → Stacked from the sheet while following KC @ BUF (the row shows for an NFL follow); no-fade swap.
2. Kick-off: KC ball, "1ST & 10 | KC 25", pips 3/3.
3. Q1: the two flags (8 s each, shake, wave), the declined foul with no flag, the TD with a penalty (TOUCHDOWN only).
4. RED ZONE → TOUCHDOWN (run, glow, row flash, pop) → "EXTRA POINT" → arc and posts → "KC KICKOFF | KC 35", no red.
5. End 1st: clock folds, "END OF 1ST", ball off.
6. Q2 FG, the +7 pair, the BUF timeout (burn, held bar), halftime.
7. Q3 safety, defensive TD, two-point; Q4 flap (one mark), fallback timeout (8 s); OT 2-slot pips; walk-off on the Final
   body; "FINAL/OT".
8. Each corner (right corners hug 1824, mirrored order), Large, subtitles on, Delay 30 s, place mode, still version,
   stale in play (bar closes, "No update" in the clock slot) and at halftime ("NO UPDATE" bar), via `sports_nfl_faults
   {"status": 503}`.
9. Device items: `StackedNflFramesTest` on a Fire TV Stick (takeover, flag wave, REKEY, Standard and Large); idle FULL ≤ 1
   frame in 10 s outside the flag hold; TalkBack/VoiceView; IND @ NE and DAL @ NYG over real video; pip legibility at
   3 m on a 720p panel; a live Sunday sample (`sample_espn.py`) for the §12 items.

---

## 10. Deviations from the mock (recorded)

| ID | Mock | Here | Why |
|---|---|---|---|
| S-D1 | YDS column at timeouts and halftime | not built *(YDS 2026-11-16: built; deviations Y-D1–Y-D5 in `m6-nfl-stacked-yds.md` §9)* | no team yards on the scoreboard; Owner Q2; Appendix A |
| S-D2 | tally "KC 24 · BUF 17" 15 px; name line | "24 – 17" at 20; name dropped | NFL D4, R7; it does not fit |
| S-D3 | word at 86/74/80 | fitted to ≥ 28 | the mock overflows its own box |
| S-D4 | flash 4 × 300, posts 3 × 300, burn 5 × 320 | 450 grid; burn 4 × 400 | flash rule |
| S-D5 | fixed red glow; KC red / BUF blue stripes; BUF bar `#1D4ED8`; BUF pop blue | team glow; derived stripes; true-colour bars; shared pop | SR2, SR3, ST, NFL D2 |
| S-D6 | flag stripe B white | `#806800` | ST S2 (2 : 1) |
| S-D7 | "EXTRA POINT \| KC 15" | "EXTRA POINT" | the try's spot is unreliable in the feed |
| S-D8 | infinite flag wave | while the flag bar shows (≤ 8 s) | idle redraw budget |
| S-D9 | no hairline, no text shadow | hairline on every bar; the NHL bar's 1 px shadow | one bar family (ST S2) |
| S-D10 | — | a known down with an unknown side shows a **neutral** bar with the words (the Pill closes its tab) | Stacked has no separate down column (Rec 8) |
| S-D11 | clock ticks | snapshot clock only | R6 |
| S-D12 | pips 22 × 6 mock (9.9 × 2.7) | **9.9 × 3.0** | 2.7 is 1.8 real px at 720p; 3.0 is the smallest height that gives 2 px (Rec 3) |
| S-D13 | no RED ZONE, END OF QUARTER, FINAL or stale states | added | rulings (R26, SR4a, ST §8) |

---

## 11. Risks

- **The caller of a timeout** is taken from the count, not from `lastPlay.team` (unproven); a type 21 arriving before the
  count drop waits for it (§3.3), a missing drop shows nothing.
- **Seven team bars (IND, DAL, NE, SEA, TEN, NYG, CHI) look like the rows** and the bar is open through all play. SR3
  covers it; IND @ NE and DAL @ NYG go to the owner's screenshots.
- **59 stripe collisions**; all resolve, but many teams draw their secondary stripe in those games.
- **The flag wave and a REKEY every play** are new redraw costs (≤ 8 s; 550 ms about every 30–40 s): frame test.
- **The `NflScoreTimeline` spec split** touches the Pill: guarded bitwise and by the hash gate.
- **One shared setting** changes the NFL look for existing Stacked users on update (Owner Q1).
- *(YDS 2026-11-16)* The YDS column's risks: `m6-nfl-stacked-yds.md` §7.

## 12. Still unproven, with the defensive behaviour

| Item | Behaviour until proven |
|---|---|
| Halftime `situation` (and when the counts reset to 3/3) | counts held from the last entry of the half; a new half group resets on the first counts seen; with no `situation` the bar shows HALFTIME and the pips show the held counts |
| Overtime counts (regular season 2) | slots = the highest count seen in the 5+ group (≤ 3); no counts → pips hidden |
| Offsetting fouls' text | the rule (type 8, or "PENALTY on") is applied as written; a synthetic test pins both outcomes |
| `lastPlay.team` on a type 21 = the caller? | never used; the caller comes from the count |
| A declined foul as a scoreboard `lastPlay` | same play text as the summary (inferred); mixed-case "Penalty on" never flags |
| Glyph kerning | widths are advance sums (NFL §4.3 method); `StackedNflFitTest` measures the real `Paint` |
| *(YDS 2026-11-16)* END OF nTH lasts one sample, then ≈ 3 min of the next period at 15:00 with `lastPlay` type 2 (live 2026-11-16) | periods 2 / 4 mapped to the end-of-period break (S1, lead default); halftime / Q3 start / OT unproven, unchanged |

## 13. Owner questions (built on defaults)

1. **One "Score bug style" for every sport?** Anyone who picked Stacked for hockey also gets the Stacked football bug;
   Pill stays the default, so most users see no change. *Built on: yes, one shared setting.*
2. **The yards column.** ESPN's live scoreboard has no team yards. They exist only in the game summary, a second download
   of about 175 KB early in a game, growing to about 660 KB by the end. *Built on: the yards column is left out;
   everything else in the mock-up is shown.* **Answered 2026-11-16: build it, with no on/off switch**
   (`m6-nfl-stacked-yds.md`).

---

## Appendix A. The YDS column (**not built** in v1; built 2026-11-16: see `m6-nfl-stacked-yds.md` §3, which corrects "312" to 38.9, the source to the per-team statistics feed, and "When")

Kept so that turning it on is a one-constant change (`STACKED_NFL_YDS_ENABLED`) plus a data source (the summary's
`boxscore.teams[].statistics.totalYards`, 175–662 KB per fetch, one play behind; it would be fetched only at a team
timeout and at halftime).

| Element | Mock | Standard |
|---|---|---|
| Column | 140, `#15191F`, inset 1 px `#2B3240` on its rows-side edge | 63 (`STACKED_NFL_YDS_W`) |
| Digits | 48 / 700 tabular, centred in each 120 row | 21.6 ("312" 40.2) |
| Label | "YDS" 18 / 700, .08 em, `#C3C7D0`; rules 2 px `#5B6170`, inset 14, gap 8 | 18 px; rules dropped (NHL SOG precedent) |
| Open | width 0 → 63, 450 Std, delay 0; contents α 300 / x −5.4 → 0 350 ease after 300 | |
| Close | contents 200 ease; width 450 Std after **200** (NHL SOG 350) | |
| When | timeout mark, halftime, end of quarter, final (both counts known) | |
| Footprint | 396 × 141 / 495 × 176.25; widths: timeout 396, breaks 306 | |
| Mirror | `[clock][YDS][rows]`, opens leftwards, contents' slide +5.4 | |

---

## Commit 0 rulings (2026-11-15)

Where commit 0 had to choose (the design was silent, ambiguous, or would have put one file in two work packages), the
choice that keeps WP-1 … WP-5 disjoint was taken. Each item: what, and why.

1. **The PILL freeze lands in commit 0, as its own test.** `core/src/test/.../LegacyNflScoreTimeline.kt` (the frozen
   `NflScoreTimeline` of `412446e`, constants inlined, own bezier) and `NflScoreSpecLegacyTest.kt` (bitwise on every
   `NflScoreFrame` field, every kind, single, pair and still, old and spec overloads, −1 … 9 700 at 0.25 ms plus NaN and
   +∞; PILL always writes `rowFlashAlpha = 0`). §8.2 had `LegacyNflScoreTimeline.kt` under WP-2, but the lead brief and
   §4.3 make the PILL split commit 0's and "checked bit-identical before commit". WP-2 keeps `NflScoreSpecTest.kt` (the
   STACKED values, the flash-grid peaks, the STACKED row flash) and **never edits** the two commit-0 files.
2. **`NflScoreSpec` is a data class with these fields** (all `Float` except `tabPulseCycles: Int`): `streak1Alpha`,
   `streak2Alpha`, `streakFrom`, `streakTo`, `runFrom`, `runTo`, `runY`, `runPx`, `runAlpha`, `arcP0X/Y`, `arcP1X/Y`,
   `arcP2X/Y`, `arcS0..2`, `arcR0..2`, `arcAlpha`, `postsX`, `postsY`, `postsW`, `postsH`, `postsDy`, `flashAlpha`,
   `badgeDx`, `badgeMidDx`, `letterDrop`, `letterOvershoot`, `pointsDx`, `pointsMidDx`, `tabPulseCycles`, `rowFlashMs`,
   `endMs`, `pairSecondMs`, `pairEndMs`, `stillFrameBMs`. Painter-only values (streak alphas, `runPx`, posts geometry) are
   carried but not read by the timeline. The pop (900 ms, `NFL_POP_MS`) is shared skeleton, not spec. One score's still
   frame B ends at `3 600 + stillFrameBMs`; a pair's B1 / B2 at `pairSecondMs` / `pairEndMs`. The generic row-flash
   envelope (ease-out from 3 600 over `rowFlashMs`, 1 through a still frame B) is written in commit 0 and is inert under
   PILL; `NflScoreSpec.STACKED = PILL` until WP-2. The timeline's private `PAIR_SECOND`, `PAIR_END`, `TAB_PULSE_END` and
   `ARC_*` copies are gone (read from the spec).
3. **The full §8.5 constant block and `StackedNflMock` are in commit 0**, not only the ones the stubs read: WP-1 needs
   `NFL_TIMEOUTS_MAX`, `NFL_TIMEOUT_*`, `NFL_FLAG_MARK_MS`, `NFL_TYPE_*` and may not edit `SportsConstants.kt`. Extra
   names: `STACKED_NFL_REKEY_*` spelled out (`_BG_MS`, `_STRIPE_MS`, `_STRIPE_B_DELAY_MS`, `_TEXT_DELAY_MS`, `_TEXT_MS`,
   `_TEXT_DX`, `_MS`), `STACKED_NFL_SHAKE_DELAY_MS / _MS / _CYCLES / _DX1 / _DX2`, `STACKED_NFL_WAVE_MS / _DEG /
   _PIVOT_X / _PIVOT_Y`, `STACKED_NFL_BURN_CYCLES`, `STACKED_NFL_PIP_H` 3.0. WP-2 may append to the block (and to
   `StackedNflMock`); nobody edits a commit-0 value without the lead.
4. **`StackedColourTable`'s NFL arm** (`League.NFL -> NflStackedColours.pair(away, home)`) changes in commit 0 (§8.3 says
   only that line changes, and no WP lists the file). `StackedColourTableTest`'s NFL line now asserts the delegation
   (`assertSame(NflStackedColours.pair("BUF", "CHI"), StackedColourTable.pair(League.NFL, "BUF", "CHI"))`), which holds
   for the stub and for WP-2's cached table, so WP-2 does not touch that test. `NflStackedColours.pair` stub = the NHL
   fallback pair (the arm's old value).
5. **The placement stub** is two private helpers in `ScoreBugPlacement` (`nflStackedWidth` / `nflStackedHeight`, the
   NFL Pill's 520 × 70 / 650 × 87.5), reached by a new first branch `league == NFL && style == STACKED` in
   `width/height(size, league, style)`. WP-2 replaces the two helper bodies (marked `// WP-2`), landing with WP-4.
6. **Every test that pinned the hockey-only Style gate changes in commit 0** (all are "NFL followed / browsed → Style
   row shown" now): `ScoreBugPlayerTest` (two assertions: browsing the NFL, and an NFL follow), `ChlScoreSheetTest`
   (an NFL follow while browsing a CHL list), and `ScoreBugMachineTest.periodSurvivesStaleAndWaitingInPlayAndInABreak`
   (it asserted `period == null` for the NFL; now REG 4 fresh and stale). `StackedSeamTest`'s NFL case now asserts the
   NFL + STACKED branch composes `StackedNflBug` (counter below) and neither hockey bug; it no longer asserts `NflBug`, so
   WP-4's runner does not break it.
7. **Debug counters and the layer tag live in `StackedNflContracts.kt`** (lead-owned): `COMPOSITIONS_STACKED_NFL_BUG`,
   `FRAMES_STACKED_NFL_BUG`, `DRAWS_STACKED_NFL_BUG`, `STACKED_NFL_LAYER_TAG`, so the seam test, WP-4's tests and the
   instrumented `StackedNflFramesTest` share them. The commit-0 `StackedNflBug` bumps the composition counter and calls
   `NflBug(…, NflHooks.Default)`.
8. **Typefaces are nullable** in both painter constructors (`Typeface?`), as `StackedPainter` / `StackedGoalPainter`:
   fonts load through `sansTypeface`, which returns null on failure. Painter constructor parameters are `val`s.
9. **`NflBarContent` adds** `wordsAdvance` (device px, set by `fillBar`; a mirrored bar right-aligns by it, as
   `BarContent.wordsAdvance`) and `copyFrom(o)` (the runner's `barPrev` copy).
10. **The runner-only seam types are in `StackedNflBug.kt`** (WP-4 owns and may extend them): `StackedNflSampler`
    (`Real`, `Pinned`), `NflScoreSampler` (`sample(tMs, still, first, second, out)`, `Real` = `NflScoreTimeline` under
    `NflScoreSpec.STACKED`), `StackedNflProbe` (`args`, `draw`). WP-3 does not use them.
11. **Stub behaviour.** `StackedNflTargets.of` = the play state (clock open, snapshot clock or blank, no bar, no
    possession, the view's counts, nothing burning). `StackedNflTimeline` writes each track's rest state at once,
    `totalMs` = 0. `StackedNflFrame.PIPS` = `NFL_TIMEOUTS_MAX`; pip index 0 is nearest the logo. The painters draw
    nothing. Nothing of these is reachable in the app with commit 0 alone.
12. **Shared fixtures** (core `testFixtures`): `SportsFixtures.nflDetail(…, awayTimeouts, homeTimeouts)`,
    `nflView(…, period = null)`, `nflPeriod(game)` (the machine's label), `nflStackedView(…)` (period, counts 3 / 2 of 3,
    optional marks), and `NflStackedFixtures` (the eight §3.1 names, `dto`, `snapshot`, `idOf`; the bodies are `:core`
    test resources, so `dto` / `snapshot` work in `:core` tests only). `NflStackedFixturesTest` checks all eight map.
13. **Settings:** the Style row in Settings was already unconditional; only its comment and
    `settings_sports_style_desc` change (§7). `FullScreenHost`'s placement comment is corrected (no code change there).

---

## Integration rulings (2026-11-15)

Taken in the integration pass (foundation + WP-1 … WP-5 on `nfl-stacked`, each built against stubs of the others). Each
item: what, and why. Where a number differed between two packages, this design decides and the ruling names the winner.
The NHL / CHL Stacked and NFL Pill drawing is unchanged (the 1 260 existing hash lines are unedited and green).

1. **One takeover mechanism, the hockey order.** WP-3 drew the takeover inside `StackedNflPainter.drawFrame` from two
   public painter vars; WP-4 drew it from outside (glow before, takeover after `drawFrame`), which would have drawn it
   twice and put the border under the takeover. Kept: the hockey contract. `StackedNflDrawArgs` gains `takeover`,
   `glow`, `glowOn` (as `StackedDrawArgs.goal` / `glow` / `glowOn`); `drawFrame` draws glow, shadow, box, bar, rows,
   clock column, takeover, **border on top**. The painter vars and the runner's own glow / takeover calls are removed.
   The glow is drawn at the drawn box's top-left (device px; mirrored `rowsW − w`; bar up `−barH`), which under the pin
   is the baked box (the CLOCK / BAR layout channels are pinned), so the runner's and the painter's rect conventions
   are one. Tests: `StackedNflScreenshotTest.theTakeoverCoversTheTopPartButNeverTheBorder` (border pixel and glow on all
   four sides, four corners), `StackedNflEndToEndTest` (border over the takeover through the runner).
2. **Shadow.** `StackedNflDrawArgs.shadow` carries the bake of the **drawing** painter instance (`remember(painter)`);
   the draw reads it as state, so the frame after the bake lands draws it. The painter no longer caches a bake.
3. **`takeoverOn` lifetime.** The runner holds `takeoverOn` until the driver reaches `endMs` (4 800; a pair 9 600) and
   hands a frame only while `!done`; the painter's row flash and pop (3 600–4 800) are gated on it. Checked:
   `StackedNflRunnerAssumptionsTest.theStackedSpecLengths`, the end-to-end pop / row-flash assertion at 3 800.
4. **Mirrored red-zone patch.** WP-2 / WP-3 ran it from the divider to the bar's end on both sides; mirrored, that end
   holds the two stripes, and a team whose stripe A is red (BUF, ATL, ARI, TB, …) lost it on the red patch (seen in the
   screenshots). Mirrored, the patch now stops one text gap (10.8) after the sub, the divider's gap reflected, so the
   stripes keep the team's bar under them as in a left corner. Left corners unchanged. `StackedNflGeometry.barGroup`
   (`G_RED_TO`), `StackedNflGeometryTest`.
5. **The flag wave at 8 s.** The runner stops the sub-track at the 8-s hold and rests the icon at 0° (`still`); the
   wave is not at 0° then. Accepted: the flag mark ends at 8 s on the machine clock, so the bar re-keys away at that
   moment and the REKEY hides the old words at its 0 %.
6. **Missing logo.** As the NHL Stacked bug: while a followed team's logo is loading and `LOGO_WAIT_MS` (1 500) has not
   passed, the slot is empty (`StackedNflDrawArgs.awayLogoPending` / `homeLogoPending`); then the `LogoDisc`. No URL, a
   failed load, no loader, or place mode: the disc at once. `StackedNflLogoTest.aSlowLogoWaitsThenTheDisc`.
7. **Back-to-back flags.** A live region speaks only when its text changes; a flag directly after another (a new
   `flagId`, no "" between) kept the same text and was not announced. *(Superseded by the fix pass, U-S2: the zero-width
   space is gone. A flag directly after a "Flag on the play." reads "Another flag on the play."
   (`sports_live_nfl_flag_again`), then the wordings alternate; a flag after a gap is "Flag on the play." again.)*
   `StackedNflDrawTest.backToBackFlagsAreEachAnnounced`.
8. **`ScoreBugPlacementTest.pillAndNflIgnoreTheStyle`** (red after WP-2's real footprint): the NFL loop is limited to
   PILL, with an explicit NFL + STACKED expectation (top right Standard (1491, 56)–(1824, 197)). The only edit to that
   file.
9. **Timeout slots** (WP-1, confirmed): 3 in regulation always; in overtime the highest count seen in the period-5+
   group (≤ 3). The other WP-1 readings in the notes (a 21 with a drop of 2 names the side; a drop one poll before its
   21 upgrades the running fallback in place; the fallback is not ended by a plain new play; any score change in the
   entry suppresses the flag; the 100-s freshness gate covers flag and fallback; a new half with no counts hides the
   pips; flag and timeout never coexist, the flag wins in one entry; confirmed mark ≤ 180 s) are accepted as built.
10. **Both orders of a 21 and its drop** burn exactly one pip, on the caller's side: 21 first → no mark until the drop,
    then mark and drop in one view (one BURN); drop first → the fallback mark and the BURN, then the 21 upgrades it in
    place (same bar key: no REKEY, no second burn). If the fallback has expired before the 21 arrives (a poll gap over
    8 s), the 21 marks nothing: still one burn. `StackedNflEndToEndTest` drives both through the real machine.
11. **`STACKED_NFL_WAVE_PIVOT_Y` = 13.69** (the mock's 26 × 17.9 / 34), not 13.8 (a rounding in §4.1 / §8.5; nothing
    pinned 13.8). The test's tolerance is now 0.01.
12. **One rule, one place (the duplicated rules).** The painters call `StackedNflGeometry` (row positions, stripe width,
    code / score fit, bar stripes, the words group and red patch, `G.dx` / `G.deg` for the mirror, the takeover fit and
    layout, `arcK`) and `NflStackedColours` (`bar(key, pair)`, `RED_ZONE_SUB`, the fallback pair); their private copies
    are deleted. Where the numbers differed:
    - **Score fit threshold:** the painter fitted a score wider than 43, WP-2 one wider than the 45 slot. §5.4 ("0–99 at
      35.1 in 45; three digits scale to fit 43") → WP-2. No two-digit score changes ("88" is 42.1).
    - **Code fit:** the painter stepped down only codes of 4+ letters, WP-2 any code too wide for its room. §5.4 ("a
      longer one steps the code down") → WP-2; every NFL code fits at 25.2, so nothing moves (`StackedNflFitTest`).
    - **Fallback colours:** the painter's null-pair look was the NHL fallback pair; §6.1 → `NflStackedColours.pair(null,
      null)` (`NflTeamColours.FALLBACK`).
    - **Divider width:** 1 design px (geometry) drawn at least 1 device px wide (as every hairline).
    Painter-private design numbers moved to `SportsConstants`: the ball fill (`STACKED_NFL_BALL_FILL`, new), and to the
    existing WP-2 names: code line top, divider alphas, burn α, score fit, "+N" size, block gap, word / badge shadow dy,
    word sizes, streak skew and widths, word tracking, badge, padding and gap, clock air. Left in the painters (drawing
    details, not design numbers): the SVG view boxes, sprite bake scale, tracking of the codes / period / bar words,
    word breathing, slot line pitch, pop clamp, the disc alphas.
13. **`NflBug.kt`'s private helpers** (`waiting`, `shortOf`, `kindWord`, `nflScoreText`) move verbatim to `NflText.kt`
    (internal, `nfl*` names) and both NFL bugs call them; an exception to §8.3 (`NflBug.kt` "must not change") taken so
    the wording exists once. NFL Pill behaviour and pixels are unchanged (its tests and hash lines are green).
14. **Tests on the real code.** `StackedNflTestKit` now builds real views and reads `StackedNflTargets.of`,
    `StackedNflPainter.frame`, `NflStackedColours`, `StackedNflTimeline` and `NflScoreSpec.STACKED` (no hand-built
    frames). The runner tests (`StackedNflTestSupport`) use the real table, transitions, lengths, spec and score sampler;
    only the painters' once-per-change calls and the glow bake stay recording fakes. Their stale fixture now has "No
    update" on the top line, as the NFL machine writes it (it had it on the clock line, which the real table reads as
    play). `StackedNflEndToEndTest`: the real machine with the eight 2026-11-15 fixtures and scripted snapshots, the real
    runner and painters, rendered and asserted (play, possession, flag, timeouts, both orders, TD, +7, FG, safety, score
    during a flag, end of quarter, halftime, stale, delayed, final; four corners × two sizes; draw allocations; idle).
15. **The "bar with no words" cell** in WP-3's contact sheet (row 7, first column) is `open-t200`: the bar 200 ms into
    OPEN (half height; its words start at 400). Deliberate; kept and labelled.
16. **Golden hashes.** The `score-bug-stacked-nfl/` shots (827 PNGs from the real pipeline, deterministic over two clean
    runs) are appended to `sports-hashes.txt` under a dated comment; no existing line changed. The owner's review of the
    shots (§9.3) is still due; a change after it re-records only these lines.
17. *(Replaced by the fix pass, U-B1.)* **The goalposts never touch the score.** The mock keeps its posts behind the
    end of the **word** (its tally is about 13 px wide and starts after them); here the tally slot is 67.7 wide, so the
    mock's x (234 on the 333 box) put the posts' ink on the "+N" and the tally ("+3" read "+8" in a right-hand corner).
    The posts now end one `STACKED_NFL_TAKEOVER_GAP` (13.5) before the block, the arc's end bends by the same shift so
    the ball still drops between the uprights, and the posts stay under the word (`StackedNflGeometry.postsShift` /
    `bendArc`; `StackedNflFitTest.theGoalpostsNeverCrossTheScoreAndTheBallPassesBetweenThem`).

---

## Review fix pass (2026-11-16)

The two code reviews (`docs/review/2026-11-16-nfl-stacked-review-core.md`, C-…; `…-review-ui.md`, U-… and V1–V7) were
reconciled by the lead. Each item: what was decided, and why. The original 1 260 golden lines are unchanged. The NHL
Stacked, NHL Pill, CHL and NFL Pill code paths are untouched (`NflScoreSpecLegacyTest` and `LegacyNflScoreTimeline` are
unedited). The NFL Pill's view gains two trailing fields that it never reads.

1. **U-B1 (blocker; V1–V3), the goalposts.** The posts end one `STACKED_NFL_TAKEOVER_GAP` (13.5) before the "+N" /
   tally block's left edge, and never cover it in any corner. `StackedNflGeometry.postsShift(blockLeft, postsX, postsW, k)`
   = `min(0, blockLeft − 13.5k − inkRight)`, where `inkRight` is the upright's outer ink (`NflShapes.POSTS_INK_RIGHT`, x 51.5
   of the 60-unit view box). The arc's x is bent by `bendArc`: 0 at the arc's start, the full shift at its end. The ball
   therefore still drops between the uprights. The draw order is unchanged: the posts stay under the word, as in the mock.
   - Standard numbers, 333 box, FIELD GOAL "+3" over the "88 – 88" slot (padding 8):
     - Before: the block started at 257.3, the posts' ink ran 239.7 – 268.8, and the right upright (267.8) crossed "+3"
       and the tally.
     - After: the shift is ≈ −25.0, the ink runs 214.8 – 243.8 (13.5 clear of the block), and the ball's end centre
       moves 254.3 → 229.3, between the inner edges 216.8 – 241.8.
     - Large is the same × 1.25.
     - At 243 the shift is computed the same way, from that box's own block.
   - `StackedNflFitTest.theGoalpostsNeverCrossTheScoreAndTheBallPassesBetweenThem` checks:
     - every XP and FG, single and as a pair's second, in 4 corners × 2 sizes × 333 / 243, with a wide and a narrow
       tally;
     - every 5 ms, plus the still frame;
     - the sprite's real lit columns against the block as drawn (slide and 1.12 overshoot included);
     - and that the ball's last visible frame is inside the uprights.
   - Integration ruling 17 is replaced (above).
2. **C-S1, the held bar.** `NflView.heldDown` is a trailing field, Stacked only, and the Pill never reads it.
   - The machine sets it on a `Dead` body rendered with the held tab: the down that was shown with that tab, held the
     same way as `heldTab`.
   - `StackedNflTargets.of` reads `down ?: heldDown` while a tab is held. After a field goal or a safety, the release
     therefore re-keys to the held bar ("4TH & 3 | KC 20"). The bar stays open until the kick-off body re-keys it to
     "KC KICKOFF".
   - A dead ball with no held tab (a follow that starts on it) stays closed.
   - Tests: `ScoreBugMachineNflStackedTest.theBarIsHeldOnTheDeadBallAfterAFieldGoal`,
     `StackedNflEndToEndTest.aFieldGoalOnADeadBallHoldsTheBarUntilTheKickoff` (no CLOSE between the score and the
     kick-off), and the `fg-dead-ball-held` look.
3. **C-S2, the re-seed window.** In a regulation group, the window opens with the group's first entry. It closes on the
   first new play id after that entry, or on the group's first mark (timeout, fallback or flag). While it is open, both
   counts at 3 re-seed `low` at 3 / 3 and forget a pending 21.
   - The opening entry does not close the window, because ESPN's stale `situation` usually arrives with that first body.
   - Overtime groups never re-seed.
   - The Q4 2 → 3 → 2 flap still never re-marks: it comes after the half's plays and marks.
   - `theHalftimeResetAndTheNewHalfNeverMark` was changed: it had pinned "3 → 2 after the reset is not below the stale
     low 2", and now asserts the fallback mark.
   - New tests: the reviewer's scenario (`staleCountsAtTheStartOfAHalfDoNotSilenceItsTimeouts`, confirmed and fallback;
     a pending 21 forgotten; OT), and `aFlapToThreeThreeLaterInTheHalfNeverReSeeds`.
   - Known limit: if stale counts outlast the half's first new play, the window has closed. The marks then wait for a
     count below the stale low, as before (tested).
4. **C-N1, a permanent downward id correction: fixed (the smallest safe form).**
   - The fix:
     - The machine keeps a ring of the last 32 numeric `lastPlay` ids shown. An id in it is never new.
     - After 3 consecutive shown entries whose id is below the high-water, the high-water drops to the highest of them.
   - Why this is safe:
     - A body that flips back to an already-shown id never re-marks, because the id is in the ring.
     - Two stray older bodies change nothing.
     - The residual risk is that a never-shown older id gets marked after three consecutive older shown bodies. The
       older-body guard already prevents that sequence.
   - Test: `aPermanentDownwardIdCorrectionLowersTheHighWater`.
5. **C-N2.** This is documented in §3.3: the confirmed type-21 start has no freshness check, deliberately. The 21 is its
   own proof, the 180-s cap bounds it, and so does the next `lastPlay`.
6. **C-N3, playoff overtime.** `halfGroup(p ≥ 5) = 2 + (p − 5) / 2`. OT periods pair into halves (5–6, 7–8, …), each with
   its own low-water mark and slots. 3OT starts fresh. Test: `playoffOvertimePairsAreTheirOwnHalves`.
7. **C-N4, the neutral PLAY bar keeps the field.**
   - `NflView.field` is a trailing field, Stacked only: the shown `Down`'s raw field.
   - A neutral key's tail is the field relative to the away side (`StackedNflTargets.neutralTail`). The painter reads a
     null side as "away" for the field only, so the bar reads "3RD & 7 | BUF 35", or "… | 50" at midfield.
8. **C-N5.** `sampleFlag(+∞)` rests the icon at 0°. NaN was already cleaned to 0. Tested.
9. **C-N6 / C-N7 / C-N8, fake panel** (§9.4 rows updated):
   - C-N6: the Q1 TD body carries the mixed-case declined foul. The Q1 XP / kick-off window carries an accepted
     upper-case foul enforced on the kick-off, so "a flagged score never flags" is still exercised.
   - C-N7: the two-minute warning moved to Q2 75–100. Its clock starts at 2:00 through a new per-window `clock0`, and the
     Q2 timeout text now says 01:35.
   - C-N8: the Q4 re-drop to 2 is on a live, non-scoring rush (type 5). To keep 985 s, the Q4 BUF kick-off window was
     removed. The Q1 kick-off window covers the same RC9 case.
   - The README table and `test_sports_nfl.py` are updated.
10. **U-S1, the takeover looks come from the runner's own schedule.** `StackedNflPinSchedule` (pure, in
    `StackedNflBug.kt`) now holds the cover, release and pair-B cover times, the rows' body (pre-score, the pair's
    first tally, scored), the painted segment, and the pinned bar key.
    - The runner's coroutines and draw use it with unchanged values: 216, 3 600 / 8 400, 5 016; still: 0, 4 800.
    - The screenshot test builds every takeover look from it and from the machine's own `NflScore`s:
      - the pair's A2 is "EXTRA POINT | KC +1" over 31 – 17, and B2 is the released bar over 31 (V4);
      - the break box shows the score label (V5).
    - Doing this exposed a real defect: "TOUCHDOWN | KC +6" does not fit the 243 bar (§2.4), and it clipped to
      "TOUCHDOWN | K". Pinned at the break width, the score label is now the word alone ("TOUCHDOWN"); the "+6" is on the
      panel. `StackedNflPinSchedule.label`; the fit test now covers these keys at 243.
11. **U-S2, back-to-back flags.** The zero-width space is gone. A new `flagId` that directly follows a "Flag on the
    play." reads "Another flag on the play." (`sports_live_nfl_flag_again`), and the wordings alternate after that. A
    flag after a gap is "Flag on the play." again. The test asserts the exact plain-ASCII strings. The one-time TalkBack
    check on a Fire TV is still due (device-only).
12. **U-S3, a proportionate golden set.** What is hashed now:
    - every look in TL (unmirrored, bar below) and BR (mirrored, bar above) at both sizes;
    - 4 looks (play, flag, timeout, halftime) also in TR and BL;
    - ice and grey in TL only;
    - the takeover strips' cells in TL and BR at both sizes, with no t = 0 cell (it was the `play` look);
    - the 6 still and break shots.

    The 21 contact sheets and strips are no longer hashed. They are written to `ui/build/score-bug-stacked-nfl-review/`
    (not a gated folder) and to the scratchpad. Golden lines for `score-bug-stacked-nfl/`: 827 → 406. Every other line
    of `sports-hashes.txt` is byte-identical (`git diff` touches only the NFL block and its comment).
13. **U-N1.** `NflBarContent.labelW` / `subW` are measured once in `fillBar` and copied in `copyFrom`. The draw path no
    longer measures text. −1 means "not measured" (hand-filled test content).
14. **U-N2, a score before the takeover painter exists.** This follows the NHL Stacked runner (`StackedBug`): it is not
    queued. The new score shows at once, snapped, with no takeover and no pin. It is no longer silent: the probe counts
    it (`scoresWithoutTakeover`). Test: `StackedNflDrawTest.aScoreBeforeTheTakeoverPainterShowsTheScoreAtOnce`, with
    the painter's dispatcher paused.
15. **U-N4.** The instrumented idle gate allows one frame per composition seen in the 10-s window, plus one. A
    clock-only snapshot recomposes and redraws once by design.
16. **U-N5 (V6).** The screenshot test draws the real `LogoDisc` for every team without a mock asset
    (`StackedNflTestKit.logoOrNull`). A `fallback-capsule` look (the 4-letter "WASH") covers the capsule. The §5.4
    sentence is corrected.
17. **U-N6, digit formatting.**
    - The NFL Pill builds the tally ("24 – 17") and "+N" in code with ASCII digits. The Stacked takeover does the same,
      so they stay code (matching the Pill).
    - The Stacked bar's `%d` resources ("N LEFT", the yard line, "& 10", "KC +6") are now formatted with `Locale.ROOT`
      (`StackedNflPainter.rootFmt`). An Arabic-locale device no longer mixes Arabic-Indic digits into the bar.
    - The Pill's own `sports_down_and` call is unchanged.
18. **U-N10.** `pin()` no longer allocates an array per score.
19. **Not in this pass (lead):** U-N3 / V7 (the y snapping inherited from `StackedBug`; fix both together), U-N7 (a
    resumed takeover replays late), U-N8 (three digits at the floor: owner eye check), U-N9 (720p legibility: device).
