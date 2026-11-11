# Design review: NHL score bug "Stacked" style (adversarial)

Reviewed: `docs/design/m6-score-bug-stacked-theme.md` (proposed 2026-11-10, "the proposal" below) against the
mock-up `design/nhl-stacked/Stacked.dc.html`, rulings 1–33 (`docs/decisions/2026-11-05-nhl-score-bug.md`), and code on
`main` at `87e22d4`. Reviewer did not build, run Gradle, the emulator or tests. The colour figures below come from a
throw-away script that re-implements `Contrast.ratio`, `mixBlack` and `towardWhite` over `NhlTeamColours.TABLE`. The
stripe column matches the proposal's §5 list exactly, so the script agrees with the proposer's.

**Verdict.** The overall shape is sound: fork only the drawing layer, keep the machine, use the pure `:core`
samplers, keep the leaf-only layer and baked masks. The mock-up transcription is accurate: every CSS duration, delay,
easing and size in §4.2 / §7 checks out against the mock. Four things must change before commit 0:
- **B1.** The view does not carry what the stale and delayed states need.
- **B2.** Commit 0 does not compile as listed.
- **B3.** One default contradicts a binding owner ruling.
- **B4.** The glow nine-slice is specified with the wrong bake geometry.

The rest are should-fix items and owner-question changes.

---

## Blockers

### B1. Stale and delayed views lose the period; "the machine does not change" is not true as specified
- **Evidence.**
  - `ScoreBugMachine.view()` builds the period label at `ScoreBugMachine.kt:278`. In play the label goes only into
    `bottom = CentreText.Period(label)` (`:286`).
  - Then `waiting -> bottom = Delayed` / `stale -> bottom = NoUpdate` (`:289–292`) **overwrite** it. A stale or
    waiting in-play view is `top = Clock, bottom = NoUpdate|Delayed`, with no period anywhere.
  - So §8's rows "period / **No update**" and "period / **Delayed**", and `StackedTargets.period`, cannot be derived from
    `ScoreBugView`. §2.1's claim "the view already carries all that Stacked draws" is false for these two states.
  - The Pill gets away with it because it shows clock over "No update". Stacked's clock column has a dedicated period
    slot.
- **Also uncovered.** These stale combinations have no row in §8:
  - stale + EndOf (`top = EndOf`, `bottom = NoUpdate`);
  - stale + Shootout;
  - waiting + Intermission (`top = Intermission`, `bottom = Delayed`).
- **Change.**
  - Add one trailing field `ScoreBugView.period: PeriodLabel? = null`, set from `label` at `:297` for the NHL only.
  - Say plainly that the machine changes by one line. Add `ScoreBugMachineTest` cases: the label survives stale and
    waiting, in play and in a break.
  - The Pill ignores the field, and it changes only when the period changes, so the SB §4.7 recomposition budget holds.
  - If the lead refuses any machine change, the alternative is: Stacked shows the clock in the period slot and the
    status in the clock slot while stale or waiting. Pick one and write the §8 rows for every stale × state pair.

### B2. Commit 0 is not "green on its own"
- **Evidence.**
  - Adding `PickerChoice.ScoreStyleRow` (commit 0, `PlayerContracts.kt`) breaks two exhaustive `when`s:
    - `PlayerText.choice` (`ui/.../full/PlayerText.kt:478–501`, an expression `when` over `PickerChoice`);
    - the statement `when` at `PlayerStateMachineImpl.kt:957–961` (a sealed subject, so non-exhaustive is an error).
  - Adding the new `SettingsText` keys (commit 0, `SettingsTexts.kt`) breaks `ResourceSettingsTexts.idOf`
    (`ui/.../settings/vm/ResourceSettingsTexts.kt:17`, exhaustive).
  - All three are WP-4 files, so WP-1–3 cannot compile against commit 0.
- **Change.** Commit 0 adds:
  - the three `when` arms: `Unit` in the machine, the row string in `PlayerText`, the string ids in
    `ResourceSettingsTexts`;
  - the `strings_sports.xml` / settings strings they point at.

  WP-4 then owns only behaviour. The commit-0 table should list `PlayerText.kt`, `PlayerStateMachineImpl.kt` (the one
  arm) and `ResourceSettingsTexts.kt`.

### B3. Q8's default overrides ruling 19 without saying so
- **Evidence.** Ruling 19: "Intermission keeps the tab with the frozen time the feed reports." §8 (row "Intermission
  with a power play carried over") and Q8 drop the time ("not shown until play resumes").
- **What it is.** That is an amendment to a binding ruling, not a default for an unshown state. It needs the owner's
  explicit yes, and the design should offer a default that keeps the ruling.
- **Change.** Make the default honour both ruling 19 and ruling 33:
  - the clock column stays open and reads "1ST" over "INT", with no clock (ruling 33);
  - the SOG column is open;
  - the bar stays the team's power-play bar with the frozen time, "EDM POWER PLAY 1:12" (ruling 19);
  - the width is 391.5, the same as a power play in play, so nothing else moves;
  - when the PP time is consumed or play resumes, the normal transitions run.

  Q8 then asks the owner whether they prefer the proposal's "1ST INTERMISSION" bar (time hidden, an amendment to
  ruling 19).

### B4. The glow nine-slice bake geometry is wrong, and §4.2 contradicts §6 about it
- **Evidence.**
  - §6 bakes "a small rounded rect (2·margin + 1 px square plus the radius), inflated by the spread" and stretches its
    1-px centre strip. A nine-slice of a Gaussian blur is only right if the baked shape extends at least about 3σ past
    each slice line. Otherwise the stretched edge profile is the blur of a 1–6 px sliver, not of a long edge.
  - For the peak glow (σ 22) the edge would come out at roughly a tenth of its proper intensity. The glow would look
    like four corner blobs.
  - The §4.2 table says the opposite: "Glow | GA masks reused (§6.3)". There is no §6.3, and GA's masks are baked for
    the stadium-shaped pill (`GoalPainter.bakeGlow`), so they cannot be reused on a 2.7-radius box.
- **Better design** (simpler, and exact):
  - **The glow only ever draws while the box size is fixed.** §2.3's pin snaps the width tracks at takeover start.
    Extend the pin to snap the bar-height track too: only its text and stripes keep running. Then bake the glow for
    the exact box in the takeover setup, off the main thread like `bakeGlow`.
  - Cache it by `(width, height, size)`. There are at most 3 widths × 2 heights per size.
  - The bake is the GA bake, so GA's ≤ 2 % edge proof can be re-run on the real rects, not inherited (§4.3 currently
    inherits "≤ 1.4 %" from the pill's geometry).
  - Keep `NineSlice` only for the **drop shadow** (σ 8.1). That shadow does follow the box through layout transitions.
    Bake it from a source rect that is at least 6σ + 1 (≈ 50 px) in each direction. A 108-px box height is above 6σ,
    so the result is exact.
  - The memory statement changes accordingly. The glow masks are ≈ (w + 2M) × (h + 2M) ALPHA_8 each, about
    540 × 286 ≈ 150 KB, and are held only for the current size. That is the same order as GA's 63 + 120 KB.

---

## Should-fix

### S1. The runner leaves out Pill rules that Stacked needs, most visibly the held old score
- **Held previous body (GA §5.2 step 4).** The Pill keeps `held.value = holder.lastFrame` (`ScoreBug.kt:596`) and draws
  it until `LEAD_COVERED_MS` (`:720–723`). Without this, the goal view shows the new score (and the new SOG) for about
  216 ms, plus the frames before the effect starts, before the lead covers it.
- **Why §2.3 is not enough.** §2.3 says the trigger "copies GA §5.2", but it never mentions the hold. Stacked's held
  state is larger: the rows, the SOG digits and the targets. It cannot live in `Holder.lastFrame`, which is typed
  `ScoreBugFrame?` (`ScoreBug.kt:1195`).
- **Other rules to state:**
  - the first non-waiting view of a follow snaps every track (PP N5, `ScoreBug.kt:575`);
  - a hidden bug freezes its tracks, and they snap on reappearance (`:566–573`);
  - place mode parks the tracks;
  - the B2.4 override for back-to-back goals, when an earlier "POWER PLAY GOAL" is still on the bar (`ScoreBug.kt:964`);
  - goal + PP start in the same snapshot, and goal + INT in the same snapshot (pinned, then released at 3 600).
- **§7.2 inconsistency.** §7.2's "a goal first seen in an intermission view spans the 301.5 width" contradicts the pin:
  the takeover is laid out on the pre-goal width.
- **Change.** A "carried from the Pill runner" list in §2.3, each item with a `StackedDrawTest` case.

### S2. The navy-on-navy problem covers 13 teams, not 5, and stripe A disappears for 12
Measured against the row navy `#12284F`, using the proposal's own rules.

**PP bar (`mixBlack(tab, .172)`) vs the row.** These teams are below 1.5 : 1:

| Team | Bar | Bar vs row |
|---|---|---|
| BUF | `#002870` | 1.07 |
| STL | `#002770` | 1.06 |
| TBL | `#002156` | 1.07 |
| TOR | `#001A4B` | 1.15 |
| NSH | `#031937` | 1.20 |
| VGK | `#2A3437` | 1.14 |
| SEA | `#001221` | 1.30 |
| LAK | `#0E0E0E` | 1.33 |
| WPG | `#003F7D` | 1.39 |
| UTA | `#010101` | 1.43 |
| BOS | `#000000` | 1.44 |
| PIT | `#000000` | 1.44 |
| fallback | | 1.00 |

**Stripe A** ("darker of other colour / `mixBlack(tab, .5)`") vs its own bar:

| Team | Stripe A vs bar |
|---|---|
| BOS | 1.00 |
| PIT | 1.00 |
| UTA | 1.01 |
| LAK | 1.04 |
| SEA | 1.05 |
| NSH | 1.10 |
| TOR | 1.12 |
| TBL | 1.18 |
| fallback | 1.22 |
| STL | 1.26 |
| BUF | 1.27 |
| VGK | 1.29 |

So stripe A is invisible for 12 of 32 bars. The mock's whole bar motif is two stripes that slide in.

**The neutral bar.** The intermission / final bar `#1B2440` is 1.05 : 1 against the rows. So every game's
intermission has the same seam problem, and §5 adds the hairline only to "navy teams".

**Change.**
- Always draw the 1-px white .22 hairline at the bar's inner edge, for every bar.
- Choose stripes A and B from the candidates {other team colour, `towardWhite(tab, .4)`, `mixBlack(tab, .5)`,
  white} so that each is ≥ 2.0 : 1 against `barBg`. These are decorative, so 3 : 1 is not required, but visibility is.
- Assert this in `NhlStackedColoursTest` for all 32 teams and the fallback.
- Correct Q6's team list and show the owner TOR, BOS and VGK screenshots.

### S3. Same-colour stripes in common matchups
The derived stripes collide:

| Stripe | Teams |
|---|---|
| white | DET, TBL, TOR |
| gold `#FFB81C` / `#FCB514` | BOS, BUF, NSH, PIT, STL |
| silver | CAR, LAK |
| `#D53547` | CBJ, NJD, NYR |
| `#D34058` | FLA, WSH |

NYR–NJD, FLA–WSH, TOR–DET, BOS–PIT and BUF–BOS are routine games. The stripe is the only team-colour mark on the
rows, so in those games it carries no information.

**Change.** A collision rule in `NhlStackedColours.pair(away, home)`: if the two stripes are equal, or under about
1.3 : 1 against each other, the home team uses its other colour, lightened to 3 : 1 by the same rule. Test the listed
pairs.

### S4. Right corners move the team rows sideways on every power play and break
- **The problem.** §4.3 anchors the box's right edge at 1824 in the right corners, so the box "grows leftwards as the
  SOG column opens". The column order is rows | SOG | clock. Opening SOG (+58.5) or collapsing the clock (−90) therefore
  **slides the logos, codes and scores** horizontally over 450 ms.
- **This is not the mock.** The mock's box is left-anchored: the rows never move, and the columns grow to their right.
  It is also unlike the Pill, whose content never moves.
- **Why it matters.** It is the default corner, and the moving content is what the eye tracks.
- **Change.** Anchor the rows (the box's left edge) at the footprint's left edge in every corner. At even strength the
  box's right edge then sits 59 px inside the right inset (333 of 392). Ask the owner (new question M1). If the owner
  prefers a flush right edge, say so in the design as a deliberate departure from the mock.

### S5. The bar's information text sits on the floor while the Pill's sits above it
- **The numbers.** Bar text is 18 px, the floor. The Pill's PP tab text is 20 (`PP_TEXT_PX`) and its tally 22
  (`GOAL_TALLY_PX`). Stacked's tally is raised only to 18.
- **Why it matters.** The power-play time is the most time-sensitive text on the box. On a 55-inch 1080p set at 3 m,
  18-px caps subtend about 9 arc-minutes. Android TV's own guidance is a 24-px minimum for body text at 1080p (12 sp at
  960 dp).
- **Change.** Keep `STACKED_SCALE = 0.45` for the structure, and raise only the information text:
  - bar text 20 px, with the bar 33 tall (the mock's 40/66 proportion);
  - tally 20.

  The footprint becomes 392 × 141 (Large 490 × 176). Every bar string still fits: "EDM POWER PLAY GOAL" at 20 px is
  ≈ 220 + 63 px of padding, inside 391.5; "2ND INTERMISSION" is ≈ 247, inside 301.5. Fit tests at 20 must include the
  faux-italic overhang (about 0.21 × ascent).
- **Q2's alternative.** 0.40 is not viable: bar 16, period 19.2 and tally 12 all need raising. Replace it with **0.50**
  (435 × 120 + bar; the NFL bug is already 520 wide).
- **Large.** State whether the raised texts scale at Large (they should: 18 × 1.25 = 22.5).

### S6. The goal logo would be upscaled
- `FollowLogos.start(…, slotW, slotH)` loads at one slot size (`FollowLogos.kt:35`). Ruling 8 / GA reuse that bitmap as
  the badge.
- Stacked's row slot is 50.4 × 43.2 but its takeover logo is **67.5** (Large 84). If it loads at the row size, the
  badge is upscaled by about 1.6×, which is soft on a TV.
- **Change.** Load at the takeover size (67.5 × d tall, Large 84 × d) and draw downscaled in the rows. Add a
  `ScoreBugLogoTest` analogue that asserts the requested size.

### S7. Settings: fix the unknown-enum reset in the store, not per field
- **The proposer's reading is right in substance, but there are two failure paths, not one.**
  - **Import into a registered slot.** `importSlotLocked` (`RoomSettingsStore.kt:274–280`) fails to decode and logs
    "kept". The **whole** restored `sports` document is skipped, so the device keeps its old corner, delay and size.
  - **Import into an unregistered slot.** `sports` registers on first use, so this happens. The raw JSON is stored
    (`:197–199`), and `decode` (`:349–359`) later falls back to `domain.default`. Everything resets, and the next write
    makes that permanent.
- **The risk is general.** `ScoreCorner`, `ScoreBugSize` and `League` in this document carry it today, and so does
  every enum in every other settings domain.
- **Change** (commit 0, `data/.../settings/RoomSettingsStore.kt`, a file no in-flight worktree touches):
  - set `coerceInputValues = true` on `RoomSettingsStore.JSON` (`:367`). Every settings field has a default, so an
    unknown enum name decodes to that field's default and the other fields are kept;
  - drop the bespoke `NhlBugStyleSerializer`;
  - tests in the data module: `decode`, and `importInTransaction` with both a registered and an unregistered `sports`
    slot, with `"nhlStyle":"FUTURE"` and `"corner":"MIDDLE"`. All other fields survive.
- **Older apps.** v0.1.5 restoring a Stacked-era backup is already safe: the key is unknown and `ignoreUnknownKeys`
  drops it. Only future enum values are at risk, which is why the general fix belongs in the first release that adds
  the field.

### S8. The quick-bar Style row: gating, and "the bug switches at once"
- **Gating.** The row is gated on "the sheet's league" (`ScoreFacts.league` = `SportsPrefs.league`, the list being
  browsed), not the followed game's league.
  - Following an NHL game while browsing the NFL list hides the row.
  - Following an NFL game while browsing NHL shows a row that changes nothing on screen.
  - The fix: show the row when the followed game is NHL, or when nothing is followed and the sheet league is NHL.
- **"Switches at once".** The picker box is (1376, 48)–(1872, 928) (`PlayerUi.kt:84–87`). It intersects both
  right-corner footprints, so `ScoreBugPlacement.place` hides the bug while the sheet is open (`:41`, `:45`). In the
  default corner the user sees nothing change until the sheet closes. Either:
  - close the sheet on OK, so the change is seen; or
  - say in the design that the change shows when the sheet closes.

### S9. `GoalSpec`: what the equivalence test must pin
Parameterising `GoalTimeline` is the right call: one sampler, and the dozen differing numbers become data. For it to be
safe for the Pill:
- **Spec contents.** `GoalSpec` must also carry:
  - `END` (Stacked 4 800 vs CLASSIC 4 500);
  - the pop duration;
  - the still sampler's frame-B length.

  `sampleStill` (`GoalTimeline.kt:316`) needs a spec overload too. §2.3's
  `GoalTimeline.sample(GoalSpec.STACKED, t, still, out)` matches neither the existing API nor §2.1's
  `sample(spec, tMs, out)`.
- **`rowFlashAlpha`.** CLASSIC must **write 0** to `rowFlashAlpha` on every sample. Do not rely on "never writes".
- **The golden.** Keep a frozen copy of the `87e22d4` sampler in test sources (`LegacyGoalTimeline`) instead of a
  generated resource, and compare with **bitwise** float equality (`toBits`). Cover:
  - every `GoalFrame` field, booleans and `done` included;
  - t from −1 to 4 700 in 0.25-ms steps, because the driver is a float;
  - NaN and +∞;
  - `sampleStill` on the same grid.
- **Unchanged.** `ScoreBug.kt`'s `TAKEOVER_TOTAL_MS` (`:1180`) stays on the CLASSIC constants. Assert that
  `GoalSampler.Real` is unchanged.

### S10. Defaults for unshown states that create avoidable motion (see the owner questions for the full list)
- **Shootout (Q11).** The proposal closes SOG in the shootout and reopens it at FINAL/SO a minute later. Treat the
  shootout as a break instead:
  - clock column folded;
  - SOG open, showing the regulation + OT shots;
  - neutral bar "SHOOTOUT".

  FINAL/SO is then a bar-text change only.
- **5-on-4 → 5-on-3 (Q8a/Q13).** Same side and same colours, so swap the text only (150 ms out, 350 ms in). Keep the
  900-ms "change" sequence for changes of colour or side (PP ↔ break, side swap).
- **Stale in intermission (Q12).** Reopening the clock column to say "No update" is a layout animation on a degraded
  state. Instead keep the layout, put "NO UPDATE" in the neutral bar's text at .55, and restore the words when updates
  resume.

### S11. Test plan gaps
- **`:ui`:**
  - a `StackedBoundsTest` (the layer is footprint + 2M; the glow and shadow never leave it), as
    `ScoreBugBoundsTest` / `NflBoundsTest`;
  - the logo test (S6);
  - `StackedDrawTest` cases for every S1 rule;
  - a style flip mid-takeover;
  - the subtitle-moved bottom corner (the bar must follow the **effective** corner, as the Pill computes `up` from the
    placed box at `ScoreBug.kt:704`).
- **`:core` / data:**
  - the S7 store tests;
  - the B1 machine cases;
  - the S2 and S3 colour assertions;
  - C7 measured with the **pop** colour on the flash blend at t = 3 600, not only white. Today the scorer's digit
    starts at 2.1–2.6 : 1 on the blend for most teams (EDM 2.46, LAK 2.14), which is brief but below the C7 floor the
    design sets.
- **Fake panel:**
  - a scripted scenario that covers End-of-period, stale in INT, a PP carried into INT, `sog` set to `null` mid-game,
    and a SOG-only change in INT (the budget case);
  - `team()`'s formula `3 + 2·score + 4·period` (`sports.py:402`) adds 4 shots when period 5 (the shootout) starts.
    The NHL does not count shootout attempts, so the shootout should not add.
- **Device:** run `StackedFramesTest` at **Large** too (the biggest layer and masks), and a bottom corner with the quick
  bar up (lifted).

---

## Later / nits

- **N1. Citation errors** (everything else cited checks out: `NhlMapper.kt:169`, `RoomSettingsStore.kt:357/367`,
  `SettingsDomains.kt:44`, `WpaPages.kt:584/742/745`, `PlayerContracts.kt:637`, `PlayerStateMachineImpl.kt:2074/2143`,
  `FullScreenHost.kt:153/263`, `Motion.kt:12`, `GoalTimeline.kt:69`, `ScoreBug.kt:657`, the fixtures' `sog` values, the
  fake panel's `sog` line, the `--sports-pp` flag).
  - **§10.1 "Null for … `"12"`".** `NhlMapper`'s `JsonObject.int` (`NhlMapper.kt:216`) is `content.toIntOrNull()`, so
    the JSON string `"12"` maps to 12. Either expect 12, or add a strict helper for `sog` only.
  - **§4.2 "GA masks reused (§6.3)".** There is no §6.3, and the masks cannot be reused (B4).
  - **§2.2 "prefs already collected here".** Only `.league` is read (`ScoreBug.kt:163`). Keep the whole value.
  - **§2.1 helper reuse "with no code change".** It does not work for `Holder` (S1). Make `placeAt`, `PlaceHint` and
    `LiveText` internal by a pure move into a new `ScoreBugShell.kt`, and give Stacked its own holder. That is the
    "later refactor" NFL §11 item 7 anticipated. The coupling is fine: placement and the hint should behave the same in
    both styles. Leave `NflBug`'s copy alone.
  - **§3.3 "SOG changes only during play".** It also changes in breaks (stat corrections), when an INT view has no
    clock (ruling 33). The budget still holds (one view per snapshot), but correct the sentence.
  - **§8 "period / No update", "period / Delayed".** Not carried (B1).
- **N2. Faux italic.** Instrument Sans ships a real italic under the same OFL licence: a static Bold Italic TTF is
  about 87 KB, the size of `instrument_sans_bold.ttf` today, and the Licences entry already exists. That is a cheaper
  and better option than Inter (about 600 KB, a new family). Faux skew at −0.21 is acceptable for an all-caps 18–20 px
  line, so this is optional. If faux stays, the fit tests must include the skew overhang.
- **N3. Frame cost.** It is plausible:
  - about 10 `save/clipRect/drawRoundRect/restore` region fills;
  - nine shadow blits;
  - text drawn from cached strings;
  - one leaf layer.

  Rect clips need no stencil, and nothing allocates if the §6 lists hold. Path fills for the bar stripes are fine:
  build them once per size and translate. One thing to add: the per-region rounded-corner trick is needed only on the
  regions that touch a corner (stripes, the last column, the bar). Others can be plain `drawRect`.
- **N4. Photosensitivity checks out.**
  - The flash is 3 × 400 ms at α .40 (2.5 Hz, under the limit of 3 per second). The last cycle ends at 1 620 ms.
  - The row flash is one onset at 3 600 ms. The glow ramps over about 1.3 s. The GOAL pulse and the score pop are
    scale, not luminance.
  - No 1-s window holds more than 3 flashes.
  - Even at Large, the flashing panel (490 × 135 ≈ 66 k px²) is under the WCAG area threshold at 3 m on a 55-inch set
    (about 136 k px² for 25 % of a 10° field). The 2.5 Hz rule is therefore margin, not the only defence. Keep Q14
    as written.
- **N5. Exhaust the §8 table.** Add rows for: stale × {EndOf, Shootout, Final}; waiting × Intermission; OT intermission
  ("OT INTERMISSION"); a goal while the bar is mid-open.
- **N6. Glow edge proof.** Re-run the ≤ 2 % edge-falloff proof on the Stacked rects (B4). The top inset 56 is less than
  M = 74, so the glow is clipped at the top screen edge.
- **N7. Overdraw.** The layer is footprint + 2M (540 × 286 Standard, 642 × 325 Large) and mostly transparent. Fine.
  Say that the shadow nine-slice skips its centre as the glow did (the box fill covers it).
- **N8. Place mode.** Draw the sample at the footprint's anchored box. `PlaceHint` should get the drawn box, not the
  footprint, so the hint sits beside the box.

---

## In-flight conflicts

None of the proposal's files overlap the in-flight worktrees:
- `TimeshiftSessionFactoryImpl.kt`
- `LiveSettingsApplierImpl.kt` (it has no sports arm; Sports rows are read live through the prefs flow)
- `DataPlaybackSourceProvider.kt`
- `ChannelRepository.kt`
- `EpgRepository.kt`
- `RefreshContracts.kt`
- `PlaylistDifferImpl.kt`
- `RoomMergeSink.kt`
- `LogoCache.kt` / `GuideGridHost.kt`

`FollowLogos.kt` mentions `LogoCache` only in a comment. S7's `RoomSettingsStore.kt` is also untouched by the in-flight
work.

**WP disjointness** holds by file once B2 moves the three `when` arms into commit 0. One ordering caveat:
`StackedScreenshotTest` (WP-2) draws "every §8 state", which needs WP-1's real `StackedTargets.of` and colours. Run
WP-2's screenshot pass after WP-1 lands, or have WP-2 build frames by hand.

---

## Owner questions

"Lead" means engineering or safety, so the lead can rule. "Owner" means look and feel, which needs the owner's eye
(show screenshots).

| # | Proposal default | Review | Who |
|---|---|---|---|
| 1 | "Score bug style", Pill / Stacked; Pill default | **Agree.** No change for existing users. | Lead |
| 2 | × 0.45; alternative 0.40 | **Agree with 0.45, plus S5's selective raise** (bar 20 px, tally 20). 0.40 is not viable; offer **0.50** as the alternative, with side-by-side screenshots over real video. | Owner |
| 3 | Same navy for both rows | **Agree.** It is the mock. | Lead |
| 4 | Goal colours as the Pill (EDM navy panel, orange wipe) | **Agree** for consistency and ruling 12, but this departs visibly from what the owner drew. Show the EDM takeover both ways. | Owner |
| 5 | Glow in team colour, not red | **Agree.** Ruling 12 ("team's color … for all goals") already decides it. | Lead |
| 6 | Navy teams' bar: true navy + hairline | **Agree with true colours**, but the framing is wrong: 13 teams are affected (black, grey and navy), stripe A vanishes on 12, and the neutral bar has the same seam (S2). Hairline on every bar plus the stripe-visibility rule, then show TOR, BOS and VGK. | Owner (after S2) |
| 7 | Small label lines dropped; PP goal shown in the bar | **Agree.** Ruling 7 precedent; ruling 15's "PP GOAL" maps to "EDM POWER PLAY GOAL". | Lead |
| 8 | PP into INT: "1ST INTERMISSION", time hidden | **Disagree:** it contradicts ruling 19 (B3). Default to the clock column "1ST / INT" plus the PP bar with the frozen time; ask the owner only if they want the amendment. | Owner (it amends a ruling) |
| 8a | "EDM 5-ON-3 1:37" | **Agree** (ruling 14). Make the 5-on-4 → 5-on-3 transition a text-only swap (S10). | Lead |
| 9 | End of period: clock column "1ST" over "END" | **Weak agree.** The alternative, consistent with ruling 33's "a break has no clock", folds the clock column, opens SOG and shows a neutral bar "END OF 1ST"; INT is then a bar-text change only. Either is defensible. | Owner |
| 10 | Final: neutral bar, clock folded, SOG shown | **Agree.** Same shape as intermission. | Lead |
| 11 | Shootout: "SO" alone, no bar, no SOG | **Disagree** (S10): treat it as a break, with SOG open, the clock folded and a neutral bar "SHOOTOUT". FINAL/SO is then a text change. | Owner |
| 12 | Stale / delayed: dimmed "No update" / "Delayed" in the clock column; PP bar closes | **Agree for play** (needs B1). **Disagree for intermission**: keep the layout and put "NO UPDATE" at .55 in the bar instead of reopening the column (S10). | Lead |
| 13 | Bar change: ~900 ms out/in animation (the mock cuts) | **Agree in principle.** Full sequence only when the bar's colour or side changes; a same-colour change is a text swap. | Lead (then the owner sees it in the hand check) |
| 14 | Flash 3 × 400 ms, not the mock's 4 × 300 | **Agree.** Not a choice (N4). | Lead (safety) |
| 15 | Hockey only | **Agree.** | Lead |
| 16 | App font + faux italic, no Inter | **Agree, no Inter.** Prefer Instrument Sans Bold Italic (about 87 KB, same licence) over the faux skew (N2). | Lead |

**Questions the proposer missed**

| # | Question | Who |
|---|---|---|
| M1 | In the right corners, should the team rows stay still and the box's right edge move as the columns open and close (the mock's behaviour, S4), or the reverse (the proposal)? Recommend rows still. | Owner |
| M2 | Same-colour stripes (NYR–NJD, FLA–WSH, TOR–DET, BOS–PIT…): the home team switches to its other colour (S3). | Lead (owner sees a sample) |
| M3 | Large Stacked is 490 × 176, about a quarter of the screen width and 16 % of its height. Offer Large for Stacked, or cap Stacked at Standard? | Owner |
| M4 | The Style row in the quick-bar sheet: close the sheet on OK, so the change is visible in the default corner (S8)? | Lead |
| M5 | Bottom corners over broadcasts: many NHL broadcasters place their own score graphic along the bottom or top-left. A twice-as-tall bug in a bottom corner covers more of it. Keep top-right as the default (it is), and have the hand check include a real broadcast in each corner. | Lead (hand check) |
