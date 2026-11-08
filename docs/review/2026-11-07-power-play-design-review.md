# Power-play tab: design review

Reviewer: design review (Opus), 2026-11-07. Subject: `docs/design/m6-power-play.md` ("PP:n" = its line n), proposed
2026-11-07, against `main` `7981743`, owner rulings 1–16, the mock-up CSS (`design/score-bug-power-play.template.html`,
"the CSS"), the goal-animation design with its sign-off and fix pass ("GA"), and the code. Line references are
`file:line`.

Overall: the data source is right, and the fixtures back it. `landing-live-int.json` carries `situation` with the
documented shape, and its penalty list matches the reported time: an MTL minor at 19:19 of the 1st leaves 2:00 − 0:41 =
1:19, frozen in the intermission. The geometry, the fit and the colour arithmetic check out. I re-measured the label
widths from the bundled font and recomputed every C5 value; all of them match.

The weak points are in the UI runner and in the commit-0 contract:
- the runner as written freezes a tab half-open;
- the painter has no defined source for the text it draws during a close, a side change or a pin;
- an existing frame-cost gate would silently measure the wrong animation.

---

## Blockers

### B1. The `collectLatest` runner freezes the tab mid-transition on any recomposition
PP:161–174; `ScoreBug.kt:209, 229–245, 399–415`.

- The design says a `SideEffect` kicks the runner after every composition, and a new kick cancels the running
  transition (PP:161–163).
- `step()` then compares `target` with `shown` (PP:164).
- The table says "X → X′, same side, same kind: content only, no animation" (PP:172).

Put together, any composition during the 1.9 s open (or the 600 ms close) cancels the transition. `step()` then finds
`target == shown` and starts nothing. `ppDriver` stays wherever it was, for example at 700 ms: the tab is half out,
the text half faded and the shine stopped. It stays like that until the next side or kind change.

`ScoreBug` recomposes for many reasons other than a snapshot:
- `placement` and `geometry` change (a toast, a bar, the number entry);
- `fadeDone` flips (`ScoreBug.kt:228`);
- the goal mark ends;
- the stale state changes;
- the pin itself (PP:369–372), which is set in a composition and kicks.

A snapshot arrives every 20 s and an open lasts 1.9 s, so roughly one open in ten would also meet a seconds update
alone.

**Required.**
- Kick only when the *transition identity* changes: null ↔ non-null, side, kind, pinned, or the tab being allowed.
  Use a `distinctUntilChanged` key, or compare in composition before kicking.
- A content-only change (new seconds) never kicks. It redraws through the new frame (see B2).
- Add one rule: "a kick whose target equals the in-flight transition's target never cancels it."
- Add a `ScoreBugDrawTest` case: a seconds update, a toast, and a `fadeDone` flip, each landing at 700 ms into an
  open. The tab must still reach rest at 1 900 ms.

### B2. Commit 0 does not define what the tab draws, so WP-2 and WP-3 will diverge, and a close draws an empty tab
PP:244–259, 379, 610–613; `ScoreBugPainter.kt:30–46, 230–247`; `ScoreBug.kt:400–404, 434`.

The design takes the tab's words from **`ScoreBugFrame`** (`ppSide`, `ppKind`, `ppSecs`; PP:379, 613). The frame is
rebuilt from the *current* view (`ScoreBug.kt:400–403`). That is wrong in four cases:
1. **Close.** The view that closes the tab has `powerPlay == null`, so the frame has `ppSide == null`. Yet the close
   fades the text over 200 ms and slides the tab for 550 ms (PP:201–204). Drawn from the frame, it slides back
   **empty**. (The mock keeps the old label during its close.)
2. **Side change.** The close of X and the open of Y (PP:174) run while the frame already names Y. The tab, still
   showing X's gradient from `begin(X)`, would read "CGY POWER PLAY".
3. **Pinned, not `ppGoal`, with the current view's power play null or on the other side** (for example, the scorer's
   opponent's minor expires in the goal snapshot). PP:381 says "the current view's power play", which is undefined for
   null and wrong for another side.
4. **A second goal mid-pin** (PP:402). GA restarts with `held = holder.lastFrame`, which is the *first* goal view's
   frame (`ScoreBug.kt:354`). For 216 ms the tab drops from "PP GOAL" to that frame's content, perhaps nothing.

The commit-0 contract also cannot carry what WP-2's painter needs:
- `PpFrame` (PP:610) has animation channels only. It has no side, kind, seconds or "PP GOAL" choice.
- `drawFrame(…, pp, p)` (PP:257–258) has no mirror (`up`) parameter, though `PowerPlayPainter.draw(c, f, p, up)`
  (PP:612) needs one.
- §0.4 names `PowerPlayTimeline.sample(t, out)`; commit 0 names `sampleOpen/sampleClose/sampleStill(t, from, out)` with
  `from` untyped.
- There is no sampler for the 450 ms kind-change pulse (PP:173) or the PP GOAL pulse (PP:380).

WP-2 (timeline, painter) and WP-3 (runner) run in parallel, so each will invent its own version.

**Required (commit 0).**
- Give the tab's content to **`PpRun`**, captured when each transition starts, and pass it in a preallocated
  `PpContent { side, label: NONE | ONE_MAN | TWO_MAN | PP_GOAL, secs }`.
  - The run updates `secs` only while target and shown agree on side and kind.
  - While pinned, the content is "held, then PP GOAL, else held" (rule: never show another side's name on this side's
    tab).
- Keep the `ScoreBugFrame` fields only for the GA spoiler hold, or drop them. The hold is then "content captured at
  pin" and needs no frame fields.
- Fix the signatures:
  - `drawFrame(…, pp: PowerPlayPainter?, p: PpFrame?, content: PpContent?, up: Boolean)`;
  - `PowerPlayTimeline.sample(kind: OPEN | CLOSE | PULSE | STILL, t, from: PpFrame, out)`;
  - `PpFrame.copyFrom(other)`, used to capture `from`.
- Write down that the runner owns `PpFrame from`, so draw never allocates.

### B3. The fake-panel power plays fool `GoalFramesTest`'s takeover detector
PP:486–493, 596; `GoalFramesTest.kt:37–42, 131, 167`; `sports.py:69–81`.

`GoalFramesTest` re-anchors the featured game and treats **the first burst of `BURST_DRAWS` (20) bug draws inside
500 ms as the CGY goal's takeover**. Its KDoc gives the reason: "at rest the bug draws once per snapshot … is only the
takeover" (`GoalFramesTest.kt:39–41`).

With the new timeline, the EDM power play opens at 1st 35 s, about 80 s after the anchor. That is a 1.9 s burst of
about 114 draws, 85 s before the CGY goal. The test would measure the power-play reveal as "the goal takeover". It
would probably *pass*, so the release gate silently stops covering the takeover. The close (2nd 5 s) and the CGY open
(2nd 8 s) are more bursts before the goal. PP:596 adds a PP window but does not address the detector.

**Required.** Choose one:
- make the featured power plays opt-in (`sports_pp: true` in config, off by default, with `test_sports.py` covering both
  settings) and have `GoalFramesTest` leave it off;
- or teach the detector to wait for the goal (poll the panel's `/admin/sports` for the score change before arming).

The PP frame window in §9.5 then turns `sports_pp` on.

---

## Should-fix

### S1. Use the landing's own goal strength for PP GOAL; the previous-snapshot rule is wrong in both directions
PP:67, 120–125; `ScoreBugMachine.kt:308–345`; `landing-live-int.json` `summary.scoring`.

The recorded landing already labels every goal. In `summary.scoring[].goals[]`:
- `strength` is `"ev"` / `"pp"` / `"sh"`;
- there is a per-goal `situationCode` (UTA's 09:24 goal: `"pp"`, `1541`);
- `awayScore` / `homeScore` give the score after each goal.

The proposed rule, `goalPp = p.powerPlay?.side == scorer`, has three failure modes:
- **False positive.** A minor expires between polls and the same team scores at even strength inside the gap. `p` still
  shows the power play, so the tab says "PP GOAL" for an even-strength goal. With 20 s buckets this is a real case:
  any even-strength goal in the up-to-20 s after a minor expires but before the next poll.
- **False negative.** A power play that starts and is scored on inside one gap. The doc accepts this miss (PP:124).
- **Feed ordering.** If the landing's `situation` clears one bucket before the score updates (not ruled out:
  "inferred", PP:67), `p` has no power play. The tab then closes 20 s before the takeover. That is a spoiler, and the
  goal gets no PP GOAL.

**Fix.** The mapper extracts the latest goal's `strength` and its post-goal score into the snapshot, for example
`GameSnapshot.lastGoal: GoalFact(side, away, home, pp: Boolean)?`. The machine sets `goalPp` from the goal fact whose
score equals the new score. Only when the summary lags (no matching fact) does it fall back to the previous-snapshot
rule. The landing is about 8 KB, well inside the 512 KB cap, and the mapper already holds the whole body.
- Add the 09:24 UTA goal as a mapper test.
- Make the feed ordering (does the scoring entry arrive with the score?) a live-sample device item.

Whatever the source, gate `goalPp` to a previous snapshot that is LIVE and not SO.

### S2. The tint makes the white score digits and light logos unreadable for TBL, TOR, SEA and the gold teams
PP:251–252, 299, 412; `ScoreBugPainter.kt:296–299`.

C5 measures only the tab. The tint is drawn **under the body** (PP:251–253) at .9 → .5 → 0 over the first 58 % of the
pill from the power-play end. The away score column is x 88–136 (22–34 %) and the logo is x 16–80. I recomputed white
against `glow` composited at the tint alpha over `GLASS_WHITE_ICE`:

| `glow` | Digit column outer edge (x 92) | Digit centre (x 112) | White logo at its centre (x 48) |
|---|---|---|---|
| TBL, TOR `#FFFFFF` | **2.68** | **3.28** | **1.77** |
| SEA `#99D9D9` | **3.76** | 4.45 | **2.63** |
| BOS, BUF, NSH `#FFB81C` | **4.08** | 4.78 | **2.87** |
| PIT, STL `#FCB514` | **4.15** | 4.92 | **2.9** |

- Over dark video the figures are higher (TBL 4.2 at the centre), but white ice is the case SB §4.2 designs for.
- The 40 px bold digits fail even a 3 : 1 large-text floor for TBL and TOR.
- A white tint and a white halo around Toronto's white-on-blue dark logo wash it out completely.

**Fix.** Add **C6** (asserted in `NhlTeamColoursTest`, plus one screenshot assertion): white on the tinted glass over
white ice, at the digit column's outer edge, is at least 4.5.
- Derive a per-team `tintScale` the way `pop` is derived: lower the peak alpha in 5 % steps until C6 passes. My numbers
  give roughly ×0.6 for TBL and TOR, ×0.8 for SEA and about ×0.85 for the golds.
- The colour stays the team's own (ruling 12); only its strength drops.
- Optionally cap the halo alpha by the same scale.

### S3. "Tint and halo stay when `tabAllowed` is false" cannot be built with one target, and the pin overrides it
PP:161, 357, 369–372, 575; `ScoreBugPlacement.kt:54–64`.

- One `ppRun.target` (the view's power play if `tabAllowed`, else null; PP:161) drives one timeline. That timeline
  carries the tab, the tint and the halo (PP:188–197). A null target therefore closes the tint and the halo too, which
  contradicts PP:357 and the test at PP:575.
- **The pin also ignores `tabAllowed`.** An overlay that opens near the tab mid-takeover only updates `pending`
  (PP:372), so the tab stays over the overlay until 3 600. GA latched its glow off at once in the same situation (GA
  N4, `ScoreBug.kt:360`).

**Fix.** Use two inputs:
- `ppRun.target` (the power play: drives the tint and halo);
- `ppRun.tabOn` (`tabAllowed`).

The tab channels sample as closed while `!tabOn`, with a 200 ms close or a snap. `tabOn` false applies at once, even
while pinned. PP GOAL then needs `tabOn && shown != null`.

### S4. The takeover split adds a held frame and moves GA's pop; observe the driver instead
PP:383–389; `ScoreBug.kt:384, 388`.

- `animateTo(3 600)` ends on the first frame at or after 3 600, with the value clamped to 3 600, so up to one frame of
  time is lost.
- The following `animateTo(4 500)` starts at playTime 0 on the *next* frame, so 3 600 is drawn twice. The pop's first
  frame repeats, and the takeover ends one or two frames late.
- The doc also does not give the second tween's duration. Left at its default it would be a 300 ms `tween`, not 900.
- Existing `ScoreBugDrawTest` timings on the test clock may shift by a frame.

**Fix.** Keep GA's single `animateTo(TAKEOVER_TOTAL_MS)`. Inside the same effect, launch a child
`launch { snapshotFlow { driver.value >= GOAL_TAKEOVER_MS }.first { it }; ppRun.release(token) }`; it is cancelled with
the run. The still path calls `release(token)` after its `delay(GOAL_TAKEOVER_MS)`, as proposed.

Spell out where release happens:
- the guarded `finally` (`run.token == token`, `ScoreBug.kt:388`);
- the "no painter" skip (`ScoreBug.kt:366`);
- `cancelTakeover()`, **except** on the new-goal path (`ScoreBug.kt:342–355`, which calls `cancelTakeover()` and then
  restarts). There the pin must continue (PP:402). A release that snaps there would close the tab for one frame.

### S5. The live region announces "Power play over." after unrelated events
PP:464–471; `ScoreBug.kt:413–415`.

Precedence 3 ("after one ends in this follow: Power play over.") never expires:
- when the goal mark clears after **any** later goal, the text goes from the goal string back to "Power play over.", and
  a polite live region announces it again;
- the stale path closes the power play in the view (§2), so data going stale announces "Power play over." though nothing
  ended;
- a `tabAllowed` closure must not announce anything.

**Fix.** Key the live region on the *machine's* `powerPlay` for fresh views only:
- "Power play over." only on a fresh non-null → null transition with no goal in that view;
- it reverts to "" on the next view change;
- after the mark clears with no power play, go to "", not rule 3;
- stale and waiting views leave the text unchanged.

### S6. Fake-panel timeline: one window is too short to sample, and the featured game loses its even-strength goals
PP:482–493; `sports.py:69–81, 236–242`.

- **EDM `1451` 40–55 in the 3rd is 15 s**, under the doc's own 20 s rule (PP:484). The goal snapshot is the first
  bucket start in [40, 60), so about one run in four lands in [55, 60). The goal then has no `situation`, and "PP GOAL
  at 5-on-3, then back to POWER PLAY" fails intermittently. Run it 40–60, the segment's end.
- **Seconds for the 3rd period's `1451` and `1351` rows are unspecified.** Under the inferred meaning (PP:66) the 5-on-3
  seconds count to the first expiry, and the following 5-on-4 shows the *remaining* minor. Specify both. For example,
  `1351` counts 20 → 0 over 20–40, then `1451` restarts at 60 and counts down from 40, so the emulator pass shows the
  real step up.
- **Both featured goals become power-play goals.** The GA emulator pass (CGY in the 2nd, EDM in the 3rd, EDM shootout)
  then never shows a plain takeover with no tab. Keep the CGY goal in the 2nd a PP goal and make the EDM 3rd-period
  goal **even strength**: end the 5-on-3 window at 38 and use a script for the 5-on-3 goal. Alternatively, add an
  even-strength goal to OT.
- The existing `test_sports.py` assertions (phases, clock, goals at 2nd 30 and 3rd 40, the shootout winner) do not touch
  `situation`, so they keep passing. B3 is the real breakage.

### S7. `SportsFixtures` is unowned, but WP-1 and WP-3 both need it
PP:615–618; `core/src/testFixtures/kotlin/app/thebox/core/sports/FakeScoreRepository.kt:97–125`.

- `SportsFixtures.game(…)` builds `GameSnapshot` positionally and has no `powerPlay` parameter.
- `liveView(game)` never copies the game's power play into the view.
- WP-1's `ScoreBugMachineTest` (comparing views) and WP-3's `ScoreBugDrawTest` (driving `FakeScoreRepository`) would
  both edit this file.

**Fix.** Add it to commit 0:
- `game(…, powerPlay: PowerPlay? = null)`;
- `liveView` copies `powerPlay`;
- a `ppGame(side, kind, secs)` helper.

### S8. The label bake must key on the abbreviations, not on `hasFollow`
PP:221–228, 645; `ScoreBug.kt:297–322`.

- GA's setup effect is keyed on `(s, shownLarge, hasFollow, lowEnd)`. The goal painter does not depend on the teams,
  but the label masks do ("{away} POWER PLAY").
- Following another game replaces the follow without a null in between (`ScoreBugMachine.follow`), so `hasFollow` stays
  true. The new game would then draw the old game's labels.

Key the power-play bake on `(s, shownLarge, away.abbrev, home.abbrev)`, with halos on logo identity as proposed. Say
also that the masks are per **size**, not only per follow (PP:221). And define what draws while a size change's bake is
pending: the tab closed, then snapped open when ready, with the tint unaffected.

### S9. The strength fallback widens the decoder for no evidence; tighten the code path
PP:73–82, 93, 95.

- The fallback (strengths plus the "PP" tag) fires exactly when the code is malformed or odd, which is when the object
  is least trustworthy. That includes a penalty shot (`0101` / `1010`, the latter not in the table), whose strengths are
  unverified. No fixture shows a `situation` without a code. **Drop the fallback**, or limit it to `situationCode`
  *absent*.
- The regex admits codes that are impossible after the discount (`0331` → effective 2; `1361` → 6). That makes "TWO_MAN
  is always 5-on-3" (PP:95) false as written. Require both effective counts in 3..5 after the discount, else none.
- A numeric `situationCode` loses its leading zero (`0651` cannot be a JSON number; it would be `651`). Either
  left-pad a 3-digit number to 4 or reject numbers. State which.
- Cross-check: when `situationDescriptions` carries "PP" on the side the code disfavours, prefer none. This is cheap and
  guards against a malformed code.

### S10. The tab colour rule: recommend a gradient stop rather than navy for EDM, ANA and PHI (open question 1)
PP:417–421, 448–456.

The C5 numbers are right: I recomputed all 32 teams plus the fallback, and each matches within 0.1. The three orange
teams fail only because the text's far cap edge sits on the gradient's light end (×0.888).

Moving the far-end stop from `mixBlack(0)` to **`mixBlack(.10)` for every team** makes the measured point ×0.828. That
gives EDM 4.69, ANA 4.91 and PHI 4.95, all passing with **orange** tabs and an empty `C5_EXCEPTIONS`. Every other team
gets a barely darker far edge. It is a shade of the team's own colour, as the mock's own gradient is for every team, not
a substitute, so ruling 12 holds.

Without this, EDM's tab is navy beside an orange tint: two colours in one moment, and the opposite of the mock. Offer
the owner this option first.

---

## Nits

- **N1. BlurMaskFilter takes a radius.** "σ 8" (PP:217, 297) must go through GA's conversion,
  `(σ − 0.5) / 0.57735` (`GoalPainter.kt:190`). Passing 8 straight in gives σ ≈ 5.1 and a tighter shadow than the
  mock. Also write σ in **design px × d, not × s**. At Large, `s` includes the 1.25 factor: σ 10 would reach
  45 + 6 + 30 = 81 > `M` 76, and the layer clip would cut it.
- **N2. `PP_SECS_MAX` 600 renders "10:00"**, five glyphs, wider than the fixed "0:00" slot (PP:309, 677). The longest
  real situation is 5:00. Use 599, or 300 + slack.
- **N3. `sports_desc_pp` with a leading space** (PP:614). aapt trims unquoted leading whitespace, so quote it or join in
  code. `sports_desc_live` ends with no period ("…left in the 2nd"), so the suffix needs ". " before it. Also add a
  variant with no seconds for a null time.
- **N4. "Edmonton power play."** The landing's `TeamLine.name` is "Edmonton Oilers" (`NhlMapper.kt:107`; the test
  asserts "Utah Mammoth"), despite the KDoc's "common name" (`SportsContracts.kt:22`). Either say "Edmonton Oilers
  power play." or use the place name. Fix the KDoc either way.
- **N5. The "first snapshot snaps" rule should say first *non-waiting* view.** With a delay, the first view is
  `Delayed` with no power play, so the first real snapshot with one would animate a reveal instead of snapping
  (PP:176–180).
- **N6. The release from PP GOAL to "EDM POWER PLAY" after a 5-on-3 goal** goes TWO_MAN → ONE_MAN, which by PP:173
  plays a pulse. PP:388 and PP:398 say "no slide" but not whether it pulses, and the text change is a hard cut. Decide
  and test it.
- **N7. Shadow direction when mirrored** (PP:322). Flipping dy for bottom corners puts the tab's light source below,
  while the pill's own baked shadow (`ScoreBugPainter.kt:181`) falls downward. Keep dy positive. The visible part is
  then a soft top edge, which reads as the same light.
- **N8. `tabAllowed` inflates by 8 px**, but the shadow reaches 29 px past the tab's edge (PP:355). GA inflated its
  glow check by the full margin. Either inflate by the shadow reach or accept a faint shadow on a neighbour, and say
  which.
- **N9. Clip edge on 720p.** `clipRect` at `pillH × d` falls on a fractional device px (46.67 at 1280 wide), while the
  glass edge is anti-aliased. Snap the clip to a device px and check for a seam row in the 1280 × 720 screenshot.
- **N10. Shine technique.** Say that the shine is the tab's round rect drawn with the band shader, translated (GA §2.2,
  S4). That is how it gets the tab's rounded clip without `clipPath`.
- **N11. "Verified" rows without an artefact** (PP:60–65). The FLA@WSH and STL@NSH play-by-play and the 22:55 UTC
  `/score/now` sample are not in the repo. Save the relevant excerpts under `core/src/test/resources/sports/nhl/`
  (they are small) so the claims can be re-checked. Record a real **live** (not intermission) PP landing on the next
  device pass.
- **N12. `reset()` must clear `goalPp`** as well as the mark (`ScoreBugMachine.kt:169, 404`). It is implied by "cleared
  with it" (PP:122), but there are two sites.
- **N13. PP:0.4 versus PP:610** name the timeline API differently. B2 settles it.
- **N14. The tint draws over the glass's top highlight and hairline** at the power-play end, at up to .9. That is
  probably fine, but the "pixel-equal after close" assertion is the only guard. Add an at-rest screenshot over white ice
  for TOR (see S2).

---

## Checks that passed

- **Feed shape and fixtures.** Both `score-2026-11-05.json` (UTA–MTL `1541` 79 s; VAN–WPG `1451` 34 s, home WPG
  tagged "PP") and `landing-live-int.json` (`1541`, `inIntermission: true`) match PP:58–63. The intermission freeze is
  consistent with the fixture's penalty list (MTL minor at 19:19, so 1:19 left).
- **Decoding cases.** `0651` → 5-on-5 none; `1560` → none; `0641` → AWAY. A shorthanded team pulling its goalie, `1550`,
  gives effective 5-on-4, AWAY on the power play, which is correct. A delayed penalty on the PP team, `0551`, gives
  HOME, still correct. A delayed penalty against the shorthanded team, `1460`, gives HOME ONE_MAN, correct. 4-on-3 in
  OT gives ONE_MAN.
- **Compile safety.**
  - `GameSnapshot.powerPlay = null` and `ScoreBugView.powerPlay` / `goalPp` are trailing and defaulted. Every
    positional caller (`FakeScoreRepository.kt:109`, `PlayerStateMachineImpl.kt:2111`, `ScoreBug.kt:543`) still
    compiles.
  - `TeamColours.tab: Int = base` is a legal trailing default.
  - Existing fixture tests assert fields, not whole snapshots (`NhlMapperTest.kt:62`, `NhlScoreSourceTest.kt:255`), so
    mapping the fixture's power play breaks none of them.
- **Machine.** Gating on LIVE / INTERMISSION, not SO, not stale, not waiting fits `view()` (`ScoreBugMachine.kt:201–229`).
  Riding on the snapshot follows the delay queue unchanged. `nextWakeAtMono` and `pollDue` do not change.
- **Geometry.**
  - Inset 31 → width 342; 342 − 2 × 18 = 306 room. Large: 428 / 383.
  - Measured from `instrument_sans_bold.ttf` advances with .05 em tracking:
    - "WPG POWER PLAY" is 203.2 at 20 px and 254.0 at 25 px; WPG is the widest of the 32 (WSH 202.0);
    - "0:00" tabular is 46.6 / 58.3;
    - "WPG 5-ON-3" is 137.4 and "PP GOAL" 95.4;
    - total 271 ≤ 306, with room for the 1.07 pulse (217).
  - The curve gap at the inset is 0.23 px (Standard) and 0.28 px (Large).
  - Reach is 65 ≤ 74 and 75 ≤ 76.
  - At rest, top tab to y 162 and bottom tab from y 918.
  - Type 20 / 25 is at least 18 px.
- **CSS reading.** Every timing, easing and ×0.518 scale matches the CSS:
  - insets 60 → 31;
  - shine −140 → 820 → −72 → 425, width 31;
  - shadow 0 10 30 → dy 5 and σ 8 (CSS blur ÷ 2);
  - drop-shadow 14 → σ 3.6;
  - the close transitions have no delay;
  - the pulse is ease-in-out per half;
  - the gradient ratios .70 / .28 come from CGY's `#3a0710 / #8f0f24 / #C8102E`.
- **C5 table.** Every value was recomputed. 14 teams use a dark tab and 18 use the `glow` colour. The fallback is 14.0.
- **Rules.** No real-time blur, since all masks are baked. No new layer, clip or modifier, so `RootTreeRuleTest` and
  `ScoreBugBoundsTest` are unaffected. Nothing needs more than API 25: `BlurMaskFilter`, `ALPHA_8`, `DST_IN` on a
  software canvas, `drawText(char[])` and `letterSpacing` are all available. Logos are software bitmaps
  (`TeamLogo.kt:34`, `LogoTrim.kt:48`), so `extractAlpha` for the halo works.
- **Work-package files.** Apart from S7, they are disjoint after commit 0. Commit 0's unused new strings are lint
  warnings, not errors (`KotlinAndroid.kt:57`).

---

## Open questions: recommendations

1. **EDM, ANA, PHI tabs.** Take S10: a far-end stop at `mixBlack(.10)` for all teams keeps the orange tabs, passes C5
   with no exceptions, and stays a shade of the team colour. If the owner declines, keep the rule's navy and black, and
   do not add `C5_EXCEPTIONS` for a box that stays up for the whole power play.
2. **`0641` (the power-play team's goalie pulled).** Show the tab, as proposed. The penalty and its time are real.
   Ruling 14's "empty net" is the even-strength extra attacker, which the discount already hides.
3. **Intermission.** Keep the tab, frozen. It is true, static and costs no frames. Make sure S5 does not announce it
   again.
4. **The close sequence.** Follow the CSS, as proposed. The mock is the owner's reference. Show it once on the emulator
   pass, and add the 150 ms delay only if the owner asks.
5. **The PP GOAL pulse.** Drop it. The swap at 216 ms coincides with the flash (from 420) and the badge spin (450–1 000).
   A second moving thing outside the pill competes with the takeover, and it is one more timeline case for B2. Use a
   plain swap (or a 150 ms cross-fade of the text alpha).

---

## Summary

The data and drawing approach is sound and verified against the fixtures, the font and the colour table. Fix three
blockers before implementation starts:
- **B1.** The `collectLatest` runner cancels on every composition, and "same target" never restarts, so the tab freezes
  half-open. Kick only on a change of transition identity.
- **B2.** Commit 0 leaves the tab's content unowned. Drawing it from the current `ScoreBugFrame` slides an empty tab back
  on every close, mislabels a side change, and is undefined while pinned. `PpFrame`, `drawFrame` and the timeline API
  are incomplete for two work packages running in parallel.
- **B3.** The new featured-game power plays trigger `GoalFramesTest`'s burst detector 85 s before the CGY goal. The
  takeover gate would silently measure the power-play reveal instead.

The should-fix items with the most user-visible effect:
- S1: use the landing's `strength: "pp"` for PP GOAL;
- S2: the tint washes out TBL and TOR's digits and logos (2.7 : 1);
- S3: `tabAllowed` and the pin;
- S5: repeated "Power play over." announcements.
