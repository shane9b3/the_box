# NFL Stacked: code review C (data, state machine, pure core)

Branch `nfl-stacked` at `e91af67`, diff `main...nfl-stacked`. Scope: `core/.../sports/` (EspnMapper, ScoreBugMachine,
SportsContracts, SportsConstants, StackedNflLayout, StackedNflTimeline, NflScoreTimeline, NflStackedColours,
StackedColourTable, ScoreBugPlacement, SportsPrefs), `PlayerStateMachineImpl.kt`, `WpaPages.kt`,
`tools/fakepanel/sports_nfl.py`, and their tests. Reviewer B covers `ui/`.

## Verdict

**Ready after fixes.** No blocker. Two should-fixes: one is a state the design table names but the code does not
build, the other is a feed-timing risk that can turn off the timeout bar for a whole half. The rest are nits. The NHL /
CHL paths and the NFL Pill are untouched where it matters: the PILL takeover is held bit-for-bit by a strong test, and
the new machine fields are inert outside the NFL.

## Findings

### Blocker

None.

### Should-fix

**C-S1. The bar closes on the dead ball after a field goal. §3.4 says the bar is held.**
`ScoreBugMachine.kt:896-899` (the `Dead` branch sets `nflDown = null` and keeps only `heldTab`) together with
`StackedNflLayout.kt:156-157` (`n.down == null` → `bar = null`, closed).
- Scenario, run in a scratch test: 4th & 3 "BUF 20" (bar `PLAY` AWAY), then the FG body (`Dead`, +3, lastPlay 59).
  The view has `down = null`, `tab = NflTab(AWAY, BALL, OPP 20)`, and `StackedNflTargets.of(v).bar == null`. The
  re-sent dead body and the view after the goal mark also give `null`. So at the takeover's 3 600 release the bar plays
  CLOSE, the ball and wide stripe stay on, and the bar plays OPEN again on the kick-off body 30–60 s later. §3.4's row
  "LIVE, dead ball after FG / safety | held side | as play | the held bar (no change)" is not built, and no core or ui
  test covers this state (`StackedNflLayoutTest`, `ScoreBugMachineNflStackedTest` and `StackedNflEndToEndTest` have no
  `Dead` case).
- Fix: the lead picks one. (a) Build the row: add a trailing Stacked-only field to `NflView` (for example `heldDown:
  DownText?`, set from `lastDown` in the `Dead` branch, null on the Pill path). `of()` then returns the held `PLAY` key
  when `down == null && tab != null`. The Pill does not read the new field, so its frame is unchanged. (b) Amend §3.4 to
  "closed until the kick-off". The held content would be the pre-score down ("4TH & 3 | BUF 20"), which is arguably
  wrong after the score. Either way, add a layout test and an end-to-end FG → kick-off assertion.

**C-S2. If the first counts of a new half are stale, the low-water mark is stuck for the whole half.**
`ScoreBugMachine.kt:955-972`. On a group change, `low` is seeded from the first counts seen. After that, increases never
raise it.
- Scenario, run in a scratch test: Q2 ends at 1/1. The first period-3 body still carries 1/1 (the old half's
  `situation`), and the next one has the reset 3/3. Then a real Q3 timeout: 3 → 2 with a new type 21. Result:
  `timeout == null`. The 21 waits as "pending" for a drop below 1 that never comes. The fallback 3 → 2 also gives null.
  No timeout bar shows for the rest of the half, and the pips burn nothing. §12 lists "when the counts reset to 3/3" as
  unproven, so this is a live risk, not a theoretical one. The existing test
  `theHalftimeResetAndTheNewHalfNeverMark` (`e`: 2/3 then 3/3 in Q3) pins this behaviour without noticing the
  consequence.
- Fix (keeps the flap rule): in a regulation group, re-seed `low` (and clear `pending`) when both counts reach
  `NFL_TIMEOUTS_MAX` within the group's first plays. For example, before the first new play id seen in the group, or
  while no mark has been set in the group. A 2 → 3 flap in Q4 (`aFlapTwoThreeTwoMarksOnce`) still never re-arms,
  because it comes after marks and plays in the group. Add the scenario above as a test.

### Nit

**C-N1. A play id that goes down for good stops new flags and timeouts until ids pass the old maximum.**
`ScoreBugMachine.kt:951-952`. `maxPlayId` only rises. When the older-body guard lets a lower-id correction through (after
`NFL_OLDER_HOLD_MAX_MS`, with a changed score: the overturned-and-deleted TD case its own KDoc describes), every later
play below the old max is "not new". Shown with the guard off in a scratch test: max 900 → shown 880 → a type-8 at 885
gives no flag. ESPN sequence numbers most likely keep rising after a deletion, so the impact is low. Fix: when a shown
entry's numeric id is below `maxPlayId` and the entry was not a back-in-time re-show, set `maxPlayId` to it.

**C-N2. The confirmed (type-21) start has no freshness gate. The flag and the fallback have one.**
`ScoreBugMachine.kt:998`. After a long SEEN gap (offline for minutes, then a body whose `lastPlay` is a new 21), the
caller is measured against a low-water mark from minutes ago. If only the older drop is visible (the new caller's drop
lags a poll), the older caller is named. Integration ruling 9 accepts the gate on "flag and fallback" only. Either note
in §3.3 that this is deliberate, or gate on `freshCompare` too.

**C-N3. Playoff overtime: group 5+ never resets.** `ScoreBugMachine.kt` `halfGroup`. Playoff OT gives 3 timeouts per OT
"half" (periods 5–6, 7–8, …). At period 7 the counts rise back (no re-arm), so 3OT timeouts show only after the counts
fall below the 1OT/2OT low. Regular season (one OT) is fine. Fix: `2 + (period − 5) / 2` for periods ≥ 5. This is rare,
so it is a nit.

**C-N4. The neutral `PLAY` bar loses the field.** §3.4 says "PLAY, neutral, same words". The tail is built relative to the
possession side (`tail(play.field, poss)`, `ScoreBugMachine.kt:1070`), so with no side the tab is null and the bar reads
"1ST & 10" with no "| KC 35". The design half-anticipates this (the "no field: label alone" wording), but a known
territory is lost. Either amend the S-D10 row or give the key a raw `FieldPos`.

**C-N5. `sampleFlag(+∞)` writes `waveDeg = NaN`.** `StackedNflTimeline.kt:252-255`: `∞ % 1200` is NaN. The class KDoc says
"+∞ is the end". The runner stops the sub-track at 8 s, so this is not reachable today. Clamp with `if (t.isInfinite())
0f`.

**C-N6. The penalty text rule has no "declined" guard, and the fake panel's own TD body contradicts the evidence.**
`EspnMapper.kt:232-233` and `sports_nfl.py:162-163`. The script writes "…TOUCHDOWN. PENALTY on BUF-J.Poyer, …,
declined." (upper case, declined) to exercise score-wins. The design's evidence is that ESPN writes declined fouls in
mixed case. If ESPN ever upper-cases a declined foul on a non-scoring play, it flags. The rule as signed off is honoured.
Use the mixed-case declined text plus a type 8 (or an accepted foul) for the score-wins window, so the fake panel does
not model a string the app claims never occurs.

**C-N7. Fake panel: the two-minute warning (type 75) is scripted in Q3** (`sports_nfl.py:186-187`, "q3 KC 1st at KC 40").
The real feed sends it only in Q2 and Q4. It exercises "75 never marks" correctly, but the timeline is unrealistic. Move
it to a Q2 or Q4 window.

**C-N8. Fake panel: §9.4's Q4 75–100 row** ("`awayTimeouts` 2 again, `lastPlayType` 5: no mark") is on the FG
dead-ball window instead (lastPlay 59, +3). That entry is a flagged score, so the machine returns before the fallback
check for a different reason than the row tests. The low-water assertion still holds through the next window, but the
row's intent ("not below the low-water mark") is not what stops it there.

## Checked and found correct

- **Wake scheduling.** `nextWakeAtMono` includes `flagUntil` and `timeoutUntil`, which covers the 8-s flag, the 8-s
  fallback and the 180-s cap. Run: with the flag set and the device offline (no poll due), the next wake is exactly the
  flag's end. `stepAt` → `expireNflMarks` clears both, so nothing is left to wake for. A FINAL or non-LIVE entry ends the
  marks, `reset()` / `remove()` / expiry clear them, and `demand == NONE` returns null. No wake leak found.
- **State resets.** `reset()` (any new follow, `followIfKept`, remove, expiry) calls `resetNflStacked()`. Two games in a
  row start clean, including the high-water mark. Nothing new is persisted, so a restart begins as a first view with no
  mark, which is correct.
- **Score delay.** Marks start at the shown instant `at` on the machine clock. When the delay grows, the machine goes back
  in time, re-seeds as a first view, and replays (entries wake one by one through the queue-due wake). When the delay
  shrinks, it jumps to the newest entry: a newer id ends the flag, and a −2 jump gives no fallback.
- **UNSEEN.** No starts, and the low-water mark keeps moving (tested).
- **Precedence.** `StackedNflTargets.of` follows RC4: stale or waiting, then break or Final, then flag, timeout and play.
  Stale or waiting in play closes the bar, and in a break gives the neutral NO_UPDATE / DELAYED. A kick-off never carries
  `RedZone` (the guard is in both the mapper and `of`). Pips clamp to the slots.
- **NHL / CHL untouched.** `period = label` is only in `nflView`. The hockey `view()` is unchanged. The new `later(...)`
  calls and `endNflMarks()` are inert (null) for hockey. `StackedColourTable` changes only the NFL arm.
- **PILL bit-identity.** `NflScoreSpecLegacyTest` compares every frame field bitwise, from dirty frames, through the old
  and the spec overloads, from −1 to 9 700 at 0.25 ms plus NaN and +∞, for single, pair and still. This is strong
  coverage. STACKED: end 4 800, pair second at 4 800 and end at 9 600, still frame B 1 200. These match §4.3. The
  machine's 4 500 / 9 000 goal marks end before the Stacked takeover does, which relies on the runner capturing
  `nfl.scores` at start (integration ruling 3). That is cross-boundary, for reviewer B.
- **Mapper.** Counts are lenient (number or string), in range 0..3, else null. A type id given as a number reads as "8"
  through `prim().content`. Null text is safe. With no `situation`, counts and `lastPlay` are null.
- **Colours.** All 32 fixture abbreviations (WSH, LAR, LAC, JAX, LV, …) are keys in `NflTeamColours.TABLE`. The legacy
  "LA" appears only in play text, which is never read. Unknown or null codes give the fallback. The pair cache
  (`AtomicReferenceArray` CAS, equal values) is thread-safe, and the swap order is the NHL rule.
- **Placement.** The NFL + STACKED branch comes first. Footprints are 333 × 141 and 416.25 × 176.25, in design px, so
  1280-wide screens scale the same way. Every SB §4.4 rule runs on the footprint unchanged.
- **Settings / sheet.** The Style row shows whenever the style is reported, for every league. The default is still Pill
  (`encodeDefaults = false`, no migration). No remaining hockey-only gate was found in core or in the ui call sites
  (`ScoreBug.kt:185-195`, `FullScreenHost.kt:282`).
- **CLAUDE.md rules.** Streaming parsers are untouched. No telemetry. Play text is not stored.

## Verified by running vs by reading

- **Run:** a throwaway `ZzScratchReviewCTest` (deleted) confirmed C-S1 (the bar is null on the FG dead ball, on the
  re-sent body and after the mark), C-S2 (no confirmed and no fallback mark in Q3 after a stale first body), C-N1 (no
  flag below the old max) and the wake/offline result above. Also run: `:core:test` filtered to `app.thebox.core.sports.*`
  and `app.thebox.core.full.state.*` (green), and `python3 -m unittest tools/fakepanel/test_sports_nfl.py` (50 OK).
- **Read only:** the timeline maths (segment joins, interrupt-from-current, `totalMs` against what the samples need), the
  geometry and fit helpers, the colour table derivation, the fake-panel payload shape against the 2026-11-15 fixtures
  (`situation.awayTimeouts/homeTimeouts`, `lastPlay.id/type/text/team`).

## Not checked

- Real-feed behaviour at halftime and the Q3 reset (C-S2's trigger), OT counts, and offsetting-foul text. These are the
  §12 items and need a live Sunday sample.
- `ui/` runner and painters (reviewer B). In particular, whether the runner holds the bar across a null target in a way
  that would hide C-S1 (a grep found no such handling).
- `:ui:sportsHashGate`, `lintAll`, `:app:assembleDebug`, and device and emulator items.

## Fix pass (2026-11-16)

Lead rulings applied. The decisions are in `docs/design/m6-nfl-stacked.md` § "Review fix pass (2026-11-16)".

| ID | Result | Where |
|---|---|---|
| C-S1 | **Fixed:** built the held bar (option a). | `NflView.heldDown` (`SportsContracts.kt:458`); the machine sets it on a `Dead` body with the held tab (`ScoreBugMachine.kt:115`, `:935`); `StackedNflTargets.of` reads `down ?: heldDown` (`StackedNflLayout.kt:157`). Tests: `ScoreBugMachineNflStackedTest.theBarIsHeldOnTheDeadBallAfterAFieldGoal` (`:294`) and `StackedNflEndToEndTest.aFieldGoalOnADeadBallHoldsTheBarUntilTheKickoff` (`:519`, FG → held bar at the release → no CLOSE → KICKOFF). |
| C-S2 | **Fixed:** a re-seed window per regulation group. It runs from the group's first entry to the first new play after it, or to the group's first mark. Both counts at 3 inside the window re-seed `low` at 3 / 3 and forget a pending 21. OT groups never re-seed. | `ScoreBugMachine.kt:150` (state), `:1022` (rule). The flap test still holds. `theHalftimeResetAndTheNewHalfNeverMark` (`:183`) now asserts the mark after the reset. New tests: `staleCountsAtTheStartOfAHalfDoNotSilenceItsTimeouts` (`:206`, the scenario above plus its limits) and `aFlapToThreeThreeLaterInTheHalfNeverReSeeds` (`:239`). |
| C-N1 | **Fixed (smallest safe form):** a ring of the last 32 shown ids, which are never new. After 3 consecutive shown entries below the high-water, it drops to the highest of them. A flip back to an already-shown id cannot re-mark. | `ScoreBugMachine.kt:136`, `:997`, `:1092`; test `aPermanentDownwardIdCorrectionLowersTheHighWater` (`:271`). |
| C-N2 | **Documented:** a confirmed type-21 start has no freshness check, deliberately (the 180-s cap and the next `lastPlay` bound it). | Design §3.3 (`m6-nfl-stacked.md:344`). |
| C-N3 | **Fixed:** `halfGroup(p ≥ 5) = 2 + (p − 5) / 2`, so OT periods pair into halves; the OT slots rule applies to every OT group. | `ScoreBugMachine.kt:1127`; test `playoffOvertimePairsAreTheirOwnHalves` (`:254`). |
| C-N4 | **Fixed:** the raw field is carried as `NflView.field`. A neutral key's tail is relative to the away side (`StackedNflTargets.neutralTail`), and the painter reads a null side as away for the field only. | `SportsContracts.kt:465`, `ScoreBugMachine.kt:126`, `StackedNflLayout.kt:161`, `:177`, `StackedNflPainter.kt:475`; tests `theNeutralBarKeepsTheField` (`:322`) and `StackedNflScreenshotTest.theBarWordsAreTheDesignsWords`. |
| C-N5 | **Fixed:** a non-finite t rests the icon at 0°. | `StackedNflTimeline.kt:253`; `StackedNflTimelineTest.theFlagShakeAndWave`. |
| C-N6 | **Fixed:** the TD body carries the mixed-case declined foul. An accepted upper-case foul on the Q1 XP / kick-off window keeps "score wins" exercised. | `sports_nfl.py:162`, `:167`; README; `test_sports_nfl.py`. |
| C-N7 | **Fixed:** the two-minute warning moved to Q2 75–100, with its clock from 2:00 (new per-window `clock0`). Length unchanged (985 s); windows stay ≥ 25 s. | `sports_nfl.py:177-179`, `:252`, `:367`. |
| C-N8 | **Fixed:** the Q4 re-drop to 2 is on a live, non-scoring rush (type 5) before the FG. The Q4 BUF kick-off window was removed to keep 985 s. | `sports_nfl.py:206`; design §9.4 rows. |

`python3 -m unittest` in `tools/fakepanel`: 275 tests, OK.
