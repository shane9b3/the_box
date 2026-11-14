# CHL commit 0: code review (2026-11-13)

Reviewed: the uncommitted tree in `.claude/worktrees/agent-afea52464ddd5c1bc` against `5472974` (18 modified files, 13
new files). Spec: `docs/design/m6-chl-leagues.md` (§1, §1.4, §1.6, §4.1, §5.1, §6, §8), the decision note
`docs/decisions/2026-11-13-chl-leagues.md`, and design review B1 and B3.

## Verdict

**Merge after fixes.**
- The NHL and NFL paths are unchanged. I checked this by reading every converted branch and by re-running the screenshot
  suites: all 1260 PNG hashes match the list.
- Persistence and compatibility are right.
- Strings and settings keys are nearly complete.
- Four problems should be fixed before the freeze, because the five packages build against this tree in parallel:
  - **B1:** a defect in the unknown-PP hold, on the most likely CHL path (a goal while the `goals` node lags).
  - **B2–B4:** three contract gaps that WP-1, WP-2, WP-3 and WP-5 cannot build around without reopening frozen files.
- Each fix is a few lines.

---

## Blockers

### B1. A power-play goal inside an unknown run is followed by a false "Power play over." and a stale PP bar

`core/src/main/kotlin/app/thebox/core/sports/ScoreBugMachine.kt:557-571` (the `scored` check and the two branches) and
`:311` (`view().powerPlay`).

**Sequence:** H6 (`goals` count ≠ `goalTotal` while a counted row serves) fires on the poll where `goalssummary` already
has the goal but the `goals` node does not. Every PP goal can produce this poll. Steps:

1. t0, known: KIT (home) on the PP, 1–2.
2. t5, `powerPlayUnknown`, 1–3. The goal is flagged and `goalPp` is right (from `p.powerPlay`).
   - The unknown branch keeps `lastShownPp = ppHome`.
   - `view()` keeps drawing "KIT POWER PLAY 1:19" under the goal mark.
   - In Stacked, the bar goes back from "KIT POWER PLAY GOAL" to "KIT POWER PLAY 1:19" at 3 600 ms.
3. t10, known, 1–3. The minor is released, so `pp = null`.
   - `p` is the t5 snapshot, which already has the new score, so `scored = false` and `flagged = false`.
   - `ppEndedEntry = q`, and `ScoreBugShell` says **"Power play over."**

The PP was ended by a goal. The power-play rule (N5) and B3's own aim say that is never announced as "over". The bar
was also wrong for the whole held stretch.

**Fix** (in the unknown branch):
- When the snapshot changes the score (`flagged || scored`) and the scorer is `lastShownPp.side`, set
  `lastShownPp = null` silently: a PP goal releases the minor.
- Carry a `scoredInUnknownRun` flag, and treat it as `scored` on the first known snapshot after the run, so the known
  "none" that follows never says "over".
- A short-handed goal (scorer ≠ PP side) can keep the hold.
- Add the test that would have caught it, next to `aGoalInsideTheHoldIsJudgedByTheHeldPowerPlay`: PP → unknown + home
  goal → known none. Assert `views.none { it.ppEnded }`, and that `powerPlay` is null once the mark ends.

### B2. No contract field carries "Time TBD"

`SportsContracts.kt:107` (`GameSnapshot.startUtcMs: Long`) and `HockeyTechContracts.kt:35`.
- §3.2 maps `time_tbd == "1"` to "midnight local and 'Time TBD'".
- Commit 0 ships `sports_time_tbd`, but nothing from WP-1's mapper can tell WP-5's card or picker row that the time is
  TBD.
- WP-5 would have to guess from "local midnight", which is ambiguous for a real 00:00 start and not zone-safe across
  WHL / QMJHL. Otherwise WP-1 and WP-5 must reopen `SportsContracts.kt`.

**Fix:** append `val startTbd: Boolean = false` to `GameSnapshot`, after `powerPlayUnknown`. Its KDoc: CHL only, always
false for the NHL and NFL. Add it to `SportsFixtures.game` / `chlGame`.

### B3. The quick-bar League row cannot cycle NFL → CHL: `ScoreFacts` has no remembered CHL sub-league

`SportsContracts.kt:560-576` (`ScoreFacts`).
- Row 33 (WP-2, `PlayerStateMachineImpl`) cycles NHL → NFL → **CHL (`chlShown`)** → NHL.
- `ScoreFacts.league` is only `SportsPrefs.league`. While the NFL is shown, the machine cannot know which sub-league
  "CHL" means.
- Two ways out, both bad: WP-2 invents a meaning for `setScoreLeague(OHL)`, or WP-2 and WP-5 (row 50, `PlayerViewModel`)
  both reopen `ScoreFacts`.

**Fix:** append `val chlLeague: League? = null` (null = not reported, else `SportsPrefs.chlShown`) to `ScoreFacts` in
commit 0. Document that WP-5 fills it and WP-2 reads it. The other way is for commit 0 to rule that a CHL argument to
`setScoreLeague` means "the CHL group" and that `PlayerEffectsImpl` substitutes `chlShown`. Either is fine if it is
written down now.

### B4. `HockeyTechMapper.overlay` cannot apply §3.4 without the board's game facts

`core/src/main/kotlin/app/thebox/core/sports/HockeyTechMapper.kt:49`:
`overlay(board: DayBoard, runningclock, goalssummary): DayBoard`.

The overlay "replaces the clock, status and scores", so it must run the §3.4 clock table. Two rows of that table need
data the signature does not give:
- **Stopped at 0:00, period 4, tied:** this is a shootout in the regular season (§3.3 (c)) but an intermission in the
  playoffs. That needs `ChlGameFacts.playoff`.
- **`r == L`, P ≥ 2 (S12):** in OT this needs the OT length (`periodLengthsS[4]`, else 300 / 1200 by `playoff`).

Without them, Today's cards read the 3rd → OT break, when the operator has set the clock to "5:00 OT", as live 5:00 OT.
A tied 0:00 regular-season OT could also show "OT INT" instead of "SO". WP-1 would have to change the signature.

**Fix:** `overlay(board: ChlBoard, runningclock: JsonObject?, goalssummary: JsonObject?): ChlBoard`, so facts and board
stay together. The other way is to add `facts: Map<String, ChlGameFacts>`.

---

## Should-fix

### S1. The 4-letter goal-badge fallback overflows its slot, the test does not catch it, and no package owns the fix

`ui/src/test/kotlin/app/thebox/ui/sports/ChlCodeFitMeasureTest.kt:76-97, 153-182`.
- **Measured:** BRAM's takeover fallback text at `GOAL_BADGE_TEXT_PX` (24) is **71 px**. The Pill slot is 64 and the
  Stacked slot 67.5. FLNT is 61 and NIAG 58.
- BRAM's logo falls back on day one (S10), so this draws on day one.
- `stackedTakeoverBadgeAndTally` asserts `badgeLeft` / `badgeRight`, which are the **slot**
  (`StackedGoalPainter.kt:464-465`), not the text ink. The Pill badge is only printed.
- §6.4 asks for "Takeover badge fallback ... for every within-league pair", and Q4 extends the 2 px step-down to codes.
- `GoalPainter.kt` and `StackedGoalPainter.kt` are in no package in §8. If the step-down is needed, nobody owns it.
- **Fix:**
  - Assert the text width ≤ slot (minus the ring) at both sizes.
  - Pin the BRAM failure as a design O3 escalation, as was done for the capsule.
  - Give `GoalPainter` / `StackedGoalPainter` (badge text step-down) to WP-5 explicitly in §8.

### S2. The capsule assertion is weaker than §6.4, so FLNT's result is unverified

`ChlCodeFitMeasureTest.kt:84-90`.
- §6.4: "it may overhang by up to 4 px each side, and **the gap to the score is asserted ≥ 6 px**". The test asserts
  `gapToDigitBox ≥ 0`, where the gap is `SCORE_LOGO_GAP − overhang`.
- Measured:
  - BRAM: overhang 4.5, gap 3.5. It fails both rules; the test pins it.
  - FLNT: overhang 1.5, gap 6.5. It passes ≥ 6 only because the digit box is used; the gap to the score's ink is
    larger, so it is probably fine, but this is not measured.
- **Fix:** measure the gap to the score's ink (or say in the test why the digit box is the right edge) and assert ≥ 6,
  so the O3 pin is checked against the rule as written.

### S3. The deviations are not recorded in the design or decision note

No `docs/` file changed. CLAUDE.md requires the reconciled decision to be written down. Five places need it:
- §4.1: `<` (deviation 1).
- §4.1 "goalWasPp: unchanged": now wrong; the `prevPp` parameter (deviation 2) is needed because `p.powerPlay` is null
  on an unknown snapshot. The design's premise was false.
- §4.1: no hold at FINAL / SO (deviation 3).
- §2.3: NHL / NFL with no rollover (deviation 4; this matches the §2.3 table, but the formula line reads as if it
  applied to all five leagues).
- §6.4 / §10 O3: the BRAM escalation.
- Also record the hold fix from B1.

### S4. The hold can outlast 30 s when polls slow down

`ScoreBugMachine.kt:561-567`.
- The hold is checked only when the next snapshot is shown.
- Polls can slow: in a break (`breakMs` = max(2 × interval, 20 s)), at a 30 s interval, or during a failure back-off.
- Example: a held tab whose first unknown arrives just before a break is drawn until the next shown snapshot, about
  35–40 s later. §0 says "for up to 30 s".
- **Fix:** add `ppUnknownSince + PP_UNKNOWN_HOLD_MS` (on the delayed timeline) to `nextWakeMono`'s `later(...)` set.
  Make `view()` drop the held PP when `now ≥ since + delay + HOLD`, and have `advanceDisplay` clear it silently. Or
  accept this in S3's note. It is minor.

### S5. Stale-file and host pinning for the hash gate

`ui/src/test/resources/golden/sports-hashes.txt:1-4`.
- **Determinism:** the PNGs come from `Bitmap.compress` in Robolectric's NATIVE graphics, so the JDK is not involved.
  The bytes depend on that host's Robolectric native runtime, android-all and CPU paths (Skia, FreeType, libpng / zlib).
  - On this host they are stable: I deleted the six output folders and re-ran the six suites on the commit-0 tree, and
    got 1260 / 1260 OK.
  - Across machines they are not guaranteed: an arm64 Mac or Linux CI may differ by AA rounding.
- **Fix:**
  - Record the host (OS / arch, JDK, Robolectric and android-all versions) in the header.
  - Have the reader test skip, not fail, on another host, or regenerate the baseline from `5472974` on that host.
  - The reader, or whoever runs the gate, must **delete the six output folders first**: a stale PNG left by a renamed
    or removed case still hashes OK.
- Leaving the reader test to integration is acceptable. Ask WP-4 and WP-5 to run the shell check before they hand off,
  because they touch `:ui` drawing:
  `cd ui/build && grep -v '^#' ../src/test/resources/golden/sports-hashes.txt | shasum -a 256 -c --quiet`.

---

## Low

- **L1** `ScoreBugMachine.kt:503`: the delay's back-in-time path sets `lastShownPp = gatedPp(q.snap)`.
  - If `q.snap` is unknown, the held tab disappears silently, and the next known PP is announced again as "Power play,
    …".
  - Use the last known PP at or before `q`, or accept it and document it.
  - It is also untested: no test grows the delay in the middle of a run.
- **L2** `ScoreBugMachineChlViewTest.theHoldRunsOnTheDelayedTimeline`: anchors and receive times are spaced the same, so
  the test passes whether the hold is timed on `anchorMono` or on display time. Use `Age` > 0 (anchor ≠ receive) so the
  two give different answers.
- **L3** `ChlCodeFitMeasureTest.stackedBarStrings`:
  - It prints negative slacks for team bars at 301.5. That width is never used for a team bar (`StackedFitTest:75`);
    §6.4's "333 / 301.5" is a design typo, but the printout reads like failures. Drop the 301.5 line or label it
    "not a team-bar width".
  - It tests only `1:32`. Add `19:59`, `4:59` and `NO_SECS`, as `StackedFitTest:81-85` does.
- **L4** `ChlColourSeamTest:82, 102` and `ChlContractsTest:190-191` assert "≠ the Senators' colours". The Ottawa 67's are
  also red / black / white, so after WP-4 this may be true only by luck. Assert equality with the
  `ChlTeamColours(OHL, …)` / `StackedColourTable(OHL, …)` result, which is already done, and drop the inequality, or
  pick a code whose CHL and NHL rows must differ.
- **L5** `HockeyTechContracts.kt:26`: the `ChlGameFacts.away/home` KDoc says "as the board names them" and also
  "`TeamLine.name` = City Nickname". The board's own lines carry the nickname (P10). Say "full name (P10), not the card
  name", and note that in board mode (S5) the source must return the board game with `away/home` replaced by the
  facts' lines.
- **L6** `HockeyTechMapper` has no helper that says when to request `shootouts`. §2.4 depends on "the previous snapshot
  was a possible shootout (§3.3 (b)/(c))", so WP-3 will re-implement (c) in `:data`. Consider a pure
  `HockeyTechMapper.wantsShootouts(prev: GameSnapshot?, facts: ChlGameFacts): Boolean` stub now.
- **L7** `ChlNow` has non-null `period` and `remainingS`, so H1's "no clock" can only be checked by the mapper before it
  calls `derive`. Add a line to the `ChlPowerPlay.derive` KDoc so WP-1 does not look for it inside `derive`.
- **L8** `ChlContractsTest:68-69` copies `RoomSettingsStore.JSON` / `STRICT_JSON` by hand. If the store's config
  changes, the §1.4 test still passes. Move these cases to `:data` against `RoomSettingsStore.JSON`, or reference it.
- **L9** Deviation 6: until WP-2 lands row 19, `StackedBug` sizes a CHL Stacked bug with the Pill footprint. That code is
  unreachable now (no source is bound). Tell WP-5 not to approve CHL Stacked screenshots taken before WP-2 merges.
- **L10** `settings_sports_show_desc` names HockeyTech now. Do not cut a release from `main` between commit 0 and
  integration, or keep that string change for WP-5.

---

## Notes (checked, no change needed)

**Persistence.** Places checked:
- `League` is stored by name in `SportsPrefs.league` / `chlLeague` and in `FollowedGame`, and as the map key in
  `SportsFollowDoc.meters`.
- There is no `valueOf`, `ordinal` or `entries` use on `League` in main sources, except `SportsScreen`, which now uses
  `CHIP_LEAGUES`.
- No team code is ever read as a league; "NFL" as a team code reaches only `TeamLine.abbrev` / `FollowedGame.away`.
- The §1.4 rows are tested: v0.1.7 coerces `league` and ignores the new keys; a build without `coerceInputValues` finds
  the document unreadable; a downgrade drops the follow and meters; `SCHEMA` = 1.
- `encodeDefaults = false` keeps an NHL-only user's document byte-identical.

**NHL / NFL behaviour.** Every converted branch gives the old result:
- `intervalChoices` / `intervalDefault` / `intervalS` by group;
- `TeamColourTable`;
- `ScoreBug` style and the sport `when`;
- `StackedColourTable(NHL)` is `NhlStackedColours`;
- `goalWasPp`'s `prevPp` is `p.powerPlay` for the NHL, as before;
- `powerPlayUnknown = false` takes the old `else` path unchanged;
- `league = s.id.league` is NHL for NHL snapshots.

Colour-hook call sites: `ScoreBugDrawTest` and every test that builds `GoalHooks` / `StackedHooks` compile and pass.
`DayBoard.league` defaults to null and nothing reads it yet (the inference fix is WP-5's row 54).

**The hold.** The state resets on a follow change (`reset()`, tested), on a back-in-time delay change, and on any known
snapshot. Behaviour checked:
- PP → unknown → PP says nothing (tested).
- The tab closes silently at the end of the hold (tested).
- PP → unknown → known none says "over" exactly once (tested). The exception is B1.
- No hold at FINAL / SO.

**Deviations 1–4.**
- **1 (`<`):** agree. The design's own test ("unknown × 7 … closes at the 30 s anchor") and §0 "up to 30 s" need `<`;
  `≤` would hold through the 7th poll.
- **2 (`prevPp`):** agree. It is required.
- **3 (no hold at FINAL / SO):** agree.
- **4 (SportsDays):** agree. NHL / NFL `today` equals `LocalDate.now(EASTERN)`. The CHL 05:00 rollover on the wall clock
  is right on DST days (tested at both changes).

**Deviation 5 (compile-only stubs in other packages' files).** All are minimal and marked TODO(WP-n): `ScoreBugPlacement`,
`PlayerStateMachineImpl`, `PlayerText`. The `SportsScreen` chip list and the `getValue` guard are behaviour, not
compile, but they keep a CHL `prefs.league` (from a future backup) from crashing or showing three chips with no source.
That is acceptable in a WP-5-owned file.

**Deviation 7 (strings).**
- `settings_sports_interval_league` now has one `%1$s`, and `WpaPages.kt:654` still passes two arguments.
  `ResourceSettingsTexts.get` spreads the varargs into `Resources.getString`, and `String.format` ignores extra
  arguments: no crash.
- Lint cannot see the count through the key map, and there are no `values-*` translations. `lintAll` passed.
- `SportsIntervalRowTest` uses a fake text source, so it is unaffected.

**Deviation 8 (types and JSON).**
- `JsonObject` parameters are right per §2.7 and match `NhlScoreSource`: cap on `body.source()`, then
  `parseToJsonElement`, mapped by the pure `NhlMapper(JsonObject, …)`. ESPN streams with a typed serializer under
  `CappedStream` only because its bodies reach 1 MiB.
- Measured fixture sizes:

  | Body | Size | Cap |
  |---|---|---|
  | `gamesbydate` | 34–40 KB raw | `MAX_BODY_BYTES` 512 KB |
  | `seasons` | ≤ 21 KB | 512 KB |
  | `teamsbyseason` | ≤ 6.6 KB | 512 KB |
  | Per-game `penalties` node | **up to 19 KB** (the design says 1–6 KB) | `CHL_NODE_MAX_BYTES` 128 KB |
  | Per-game `goals` node | **up to 12.6 KB** (the design says ≤ 10 KB) | 128 KB |
  | `runningclock` | about 160 B | 128 KB |

  All are bounded and well under the caps. The streaming rule is for playlists and EPG.
- `ChlNodes` separates the cases correctly:
  - a 304 is passed as the confirmed body (unchanged);
  - `penaltiesFailed` / `goalsFailed` (H8) is distinct from a `null` node (H6 / H7);
  - a failed optional node is null;
  - required-node failures and the S5 `runningclock == null` cases stay in the source, which has `facts.status` /
    `facts.final`.
- `followed()` has what S5, S6 and S12 need:
  - status fallback: `facts.status` / `final`;
  - `Started` from the nodes;
  - period lengths and `playoff` for (c) and the OT length;
  - `feedPeriod` derivable.
- Apart from B2, B4, L5 and L6, WP-1 and WP-3 can build against these types without changing them.

**Strings.** Everything §6.5 / §6.6 asks for is present:
- `sports_league_*`; `sports_empty_*_league` with consistent placeholders; `sports_failed_league` / `_refused`;
  `sports_toast_not_listed_league` (`%3$s` = league); `sports_picker_chl_league`; `sports_time_tbd`;
  `sports_credit_chl` plus the three names (`translatable="false"`, LHJMQ verbatim per Q2);
  `settings_sports_interval_slowed_league` with its key.
- HockeyTech appears only in the credit and the Show Sports description.
- I found no missing key for WP-2, WP-3 or WP-5, apart from B2's field (the string exists).

**Fit geometry.** `rowGap(code, score, mirrored)` is main's helper over `rowExtents`: the exact reflection
[score][code][logo][stripe] at the PP stripe width. The mirrored row least gap is BRAM Large 28.4 design px, so it fits.
The Pill PP labels and Stacked team bars at 333 fit at 20 px for all four codes.

**Test quality.** These would fail if their behaviour were removed: the league stamp, the hold, `prevPp`, the follow
reset, the colour seams, the §1.4 rows and SportsDays. The gaps are L1, L2 and B1's missing case.

**Ownership.** Every file edited is commit 0's per §8, apart from the compile-only stubs (deviation 5). Gaps:
- `GoalPainter.kt` / `StackedGoalPainter.kt` have no owner (S1).
- `ScoreFacts` (B3) must be commit 0's, or WP-2 and WP-5 will both touch `SportsContracts.kt`.

---

## Rulings requested from the lead

1. **B3:** add `ScoreFacts.chlLeague`, or define a CHL argument to `setScoreLeague` as "the CHL group" (resolved to
   `chlShown` in `PlayerEffectsImpl`).
2. **Design O3, BRAM capsule:** measured 73 px (overhang 4.5 > 4; gap to the digit box 3.5 < §6.4's 6). Options:
   - allow a 5 px overhang;
   - capsule pad 8 instead of 10 for 4-letter codes (then 69 px, overhang 2.5, gap 5.5);
   - step the capsule text below the 18 px floor (not allowed by Q4).
3. **S1, 4-letter goal-badge fallback** (BRAM 71 px in a 64 / 67.5 slot): apply the Q4 step-down to the badge text, and
   name the package that owns `GoalPainter` / `StackedGoalPainter`.
4. **Deviations 1–4 and 6:** accept, and amend §4.1 / §2.3 (S3).
5. **Hash gate:** pin it to the host that produced the list, or regenerate per host from `5472974` (S5).
6. **L10:** no release from `main` between commit 0 and integration.

## Verified by running vs read only

**Ran** (JDK 21, `--max-workers=2`, foreground):
- `:core:test` for `*Chl*`, `*SportsDays*`, `*ScoreBugMachine*`, `*SportsIntervalRow*`: pass.
- `:ui:testDebugUnitTest` for the six screenshot suites, `ChlCodeFitMeasureTest`, `ChlColourSeamTest` and
  `ScoreBugDrawTest`: pass. All `:ui` tests compiled.
- Hash gate: I deleted the six output folders, regenerated them on the commit-0 tree and ran `shasum -a 256 -c` against
  the list: **1260 / 1260 OK**.
- `lintAll`: pass.
- Fixture sizes and the codes that are not 3 letters (from `teamsbyseason`).
- The fit numbers above come from the test's printed output.

**Read only:**
- B1, traced by hand through `advanceDisplay` / `view()` / `LiveText`. I did not write a test because this review may
  not edit sources; the test is specified in B1.
- B2–B4, S4, L1, the persistence paths, the ownership table and the diagnostics export (no `League` data found there).

---

## Fix pass (2026-11-13)

Same worktree, uncommitted. Lead rulings applied; the design (§1.2, §2.3, §3.2, §4.1, §6.4, §8, §10) and the decision
note record them.

| Item | Disposition |
|---|---|
| B1 | Fixed. In an unknown run, a score rise by the held side drops the hold silently; any score change in the run sets `scoredInUnknownRun`, which the first known snapshot treats as a score change (no "over"). A short-handed goal keeps the hold. Tests: `aPowerPlayGoalInsideAnUnknownRunNeverSaysOverAndDropsTheBar` (no `ppEnded`, `goalPp` true, `powerPlay` null from the goal on, the bar the Pill and Stacked draw), `aShortHandedGoalInsideAnUnknownRunKeepsTheHold`. |
| B2 | Fixed: `GameSnapshot.startTbd: Boolean = false` appended; fixtures take it. |
| B3 | Fixed per ruling: `ScoreFacts.chlLeague: League? = null` appended (WP-5 fills, WP-2 reads); `setScoreLeague` KDoc: always a concrete league, the CHL step passes `chlLeague ?: OHL`, and a CHL league also writes `SportsPrefs.chlLeague`. |
| B4 | Fixed: `overlay(board: ChlBoard, runningclock, goalssummary): ChlBoard`. |
| S1 | Fixed per ruling: `GoalPainter` / `StackedGoalPainter` (4-letter fallback text) assigned to WP-5 in §8. `goalBadgeFallbackTextFitsItsSlot` measures the text **ink** against the slot with the ruled step-down and pins the sizes: BRAM 22 px (ink 70 at 24 → 63 at 22; Pill slot 64, Stacked 67.5), FLNT / NIAG / NFL 24. |
| S2 / O3 | Fixed per ruling: `LOGO_FALLBACK_CODE4_FLOOR_PX = 16`, `LOGO_CAPSULE_OVERHANG_MAX = 4`, `LOGO_CAPSULE_SCORE_GAP_MIN = 6`, and `CodeFit.stepDown` / `fallbackFloorPx`. The capsule's gap is now to the score's measured **ink** (widest of 0–19). BRAM at 18 px: capsule 73, overhang 4.5 (fails), gap 6.5 (Large 6.3). **BRAM at 16 px: capsule 67, overhang 1.5, gap 9.5 (Large 9.3): passes.** FLNT 18 px (overhang 1.5, gap 9.5), NIAG 18 px (0.5, 10.5). Pinned in `pillCapsuleFitsUnderTheRuledFloor` for WP-5. |
| S3 | Done: deviations 1–4 and 6, B1–B4, S4, the BRAM ruling and the ownership changes are in the design and the decision note. |
| S4 | Fixed: `holdEndsAt()` = `ppUnknownSince + delay + PP_UNKNOWN_HOLD_MS`; `step()` ends the hold silently at it, `view()` never draws a held tab past it, `nextWakeAtMono` wakes for it. Test: `theHoldEndsByElapsedTimeWhenPollsSlowDown`. |
| S5 | Done: the hash list header records the host (macOS 15.0.1 x86_64, OpenJDK 21.0.2, Robolectric 4.17, android-all-instrumented 15-robolectric-13954326-i7) and the gate one-liner, which deletes the six output folders first. The reader test stays with integration; §8 asks WP-4 / WP-5 to run the one-liner before handing off. |
| L1 | Fixed: `rewindHold(q)`: a known `q` holds its own power play; an unknown `q` holds the last known power play before its run (dropped if the held side scored in the run), from that run's first anchor. Test: `aDelayGrownInsideARunHoldsTheLastKnownPowerPlay`. |
| L2 | Fixed: the delay test uses `Age` 3 s, so an arrival-timed hold (45 s) and the anchor-timed one (42 s) differ; it asserts 41 999 held, 42 000 closed. |
| L3 | Fixed: team bars are measured at 333 / 416.25 only (301.5 is not a team-bar width, said in the test), both sides, with `1:32`, `19:59`, `4:59` and no seconds. |
| L4 | Fixed: the "≠ the Senators" assertions are gone; equality with the CHL lookups stays. |
| L5 | Fixed: `ChlGameFacts.away/home` KDoc says full name (P10), not the card name, and that board mode returns the board game with these lines. |
| L6 | Fixed: `HockeyTechMapper.wantsShootouts(prev: GameSnapshot?, facts: ChlGameFacts): Boolean` stub. |
| L7 | Fixed: `ChlPowerPlay.derive` KDoc: H1's "no clock" / "period < 1" are the mapper's checks. |
| L8 | Fixed: the §1.4 cases moved to `:data` (`ChlSportsCompatTest`) against `RoomSettingsStore.JSON` / `STRICT_JSON`. |
| L9 | Done: §8 tells WP-5 not to approve CHL Stacked screenshots before WP-2 merges. |
| L10 | Done: the decision note records the lead's "no release from `main` between commit 0 and integration". |

Gate after the fix pass: the full command in the foreground, and the hash gate with the old PNGs deleted first (results
in the implementer's report).
