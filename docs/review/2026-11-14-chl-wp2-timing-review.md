# CHL WP-2 (timing, meter, placement, settings row, score sheet) — code review

Commit `1f793e6` against `ae2a3ea`. Spec: `docs/design/m6-chl-leagues.md` §1.6 rows 8, 9, 11, 12, 14–16, 18, 19,
21–27, 31–34, §2.5, §2.6, §8; rulings P1, P8, L3, L4 in `docs/decisions/2026-11-13-chl-leagues.md`.

## Verdict

**OK.** No blockers. Merge it, but read Should-fix S1 first: it decides when this can reach a release. The two test
gaps (S2, S3) are cheap to close in the same package.

## How this was verified

- **Run:** `:core:test --tests '*Chl*' --tests '*ScoreBugMachine*' --tests '*SportsIntervalRow*' --tests '*PollMeter*'`
  in the worktree. All green, with fresh timestamps: ChlScoreSheet 3, SportsIntervalRowChl 4, SportsIntervalRow 10,
  ChlContracts 5, PollMeterChl 2, PollMeter 14, ScoreBugMachineChl 8, ScoreBugMachineChlView 12,
  ScoreBugMachineInterval 15, ScoreBugMachineNfl 20, ScoreBugMachine 52, ScoreBugPlacementChl 2.
- **Revert run:** in a throwaway `git archive` copy under the session scratchpad (outside the repo), the five main
  files were reset to `ae2a3ea`, keeping the new tests and the `FakePlayer` line, and the five new test classes were run.
  16 of 19 fail on the old code. 3 pass on the old code; see "Test quality".
- **Read only:** NHL and NFL before/after traces, REFUSED interactions, repository wiring (`ScoreRepositoryImpl`,
  `PlayerEffectsImpl`; WP-3 and WP-5 files at this commit), string resources.

## NHL / NFL unchanged: the traces

- **`timed()` / `nfl()` against the old `fast()`** (`ScoreBugMachine.kt:428-431`).
  - `League.timing` is BUCKET for the NHL and COMPLETION for the NFL (`SportsContracts.kt:60-64`).
  - So, for the NHL and the NFL, `timed()` ≡ the old `fast()` and `nfl()` ≡ the old `fast()`.
- **Older-body hold** (`:191`): `nfl()`, so only the NFL, as before. `olderNfl` also returns false for any league other
  than the NFL (`:450`).
- **Failed base** (`:233`): `timed() && kind != 429`.
  - The NFL at a set interval gets 5 s; the NFL on a 429 gets 20 s; the NHL gets 20 s. Unchanged.
  - The new REFUSED branch (`:234-238`) is not reachable for the NHL or the NFL: nothing in `:data` produces
    `FailKind.REFUSED` yet, and the NHL and ESPN sources will never produce it.
- **Pre-game** (`:392-399`).
  - NFL `kickoffDue` → `livePoll`. Unchanged.
  - The NFL otherwise, and the NHL: `timed() && !nfl()` is false, so both stay on the bucket branch with
    `maxOf(floor, preWake)`. Unchanged.
- **Live and breaks** (`:400-407`, `:416-423`): only `fast()` → `timed()`, which is the same thing for the NHL and the
  NFL. `breakMs()` is untouched (L3).
- **`PollMeter`:**
  - The 429 floor is now `timing == COMPLETION`: for the NFL as before, and the NHL is still excluded.
  - The estimate is now `when (group)`, with the same values for the NHL and the NFL.
  - The existing `PollMeterTest` (14 tests) passes.
- **`WpaPages` interval row:**
  - The NHL and the NFL are single-member groups, so the "followed in the shown group" league is always `p.league`.
  - Labels, the floor note, the slowed and fallback notes, the league line (the old second argument is still passed)
    and the title (`group.name` = `league.name`) are byte-identical. `SportsIntervalRowTest` (10 tests) passes.
- **Sheet:** NHL → NFL is unchanged. NFL → NHL became NFL → CHL. That is the intended change in row 33.

## CHL timing: checked by reasoning and by the tests

- **Live polls:** `doneMono + interval`, from completion; the UNSEEN floor is applied.
- **The 5 s floor and Auto:** come from `INTERVAL_CHOICES_CHL_S` (first entry 5) via `fit()`.
- **The 429 floor:** 10 s for the timed leagues.
- **Breaks:** `doneMono + max(2 × interval, 20 s)`.
- **Pre-game:** `max(done + 40 s, floor, start − 2 min)`.
  - One poll on Follow, then asleep until the wake.
  - The failure path also respects the wake (`:384`).
- **FINAL:** returns null, so polling stops (unchanged).
- **REFUSED** (`:234-238`).
  - It recovers. The next poll is at +300 s. An `Ok` clears `failures` and `backoffAt` (`:188`), and the cadence
    returns to `doneMono + interval`.
  - A rise to SEEN does not poll early: `demand()` checks `backingOff` (`:164`). Neither does `online(true)`.
  - Under UNSEEN the poll is `max(+300 s, failAt + 120 s)`. Before the wake it is `max(…, preWake)`.
  - The repository feeds `fetched` synchronously in its actor loop (`ScoreRepositoryImpl.kt:349-355`). A stale REFUSED
    from a previous follow therefore cannot land on a new follow's 300 s clock.
- **The unknown-power-play hold wake:** `holdEndsAt()` is still a separate `later()` in `nextWakeAtMono`, so a 300 s
  back-off does not delay the end of the hold.
- **The delay setting** and **demand NONE** (background) are untouched.
- **The day-list REFUSED back-off:** that is row 37, owned by WP-3 (`ScoreRepositoryImpl`). This commit covers only the
  machine's follow path, which is correct for WP-2.

## Blockers

None.

## Should-fix

### S1. The CHL step in the sheet is reachable before any CHL source exists (merge and release order)

- **Where:** `PlayerStateMachineImpl.kt:2170-2174`, together with `ScoreRepositoryImpl.kt:189` and
  `PlayerEffectsImpl.kt:102` at this commit.
- **Scenario:**
  1. On a build cut from main with WP-2 but without WP-3 and WP-5, the user opens the score sheet with the NFL shown
     and presses OK on the League row.
  2. `setScoreLeague(OHL)` writes `SportsPrefs.league = OHL`.
  3. `sourceOf(OHL)` silently falls back to the first source (the NHL), so an "OHL" board of NHL games appears.
  4. The interval row on the Sports tab becomes "CHL" and writes `chlIntervalS`. `pushInterval` ignores
     `chlIntervalS` until WP-3 lands row 36 (`ScoreRepositoryImpl.kt:416`).
- **Status:** this is not a WP-2 code defect, because the spec plans it this way. It is an integration-order hazard.
- **Fix:** do not cut a release or a debug build for testers from main between WP-2 and WP-3 + WP-5. Alternatively,
  merge WP-2 onto an integration branch with WP-3 and WP-5. Record this in the integration checklist (§8, "Integration +
  review").

### S2. `anOlderBodyIsNotHeldForAChlGame` is vacuous

- **Where:** `ScoreBugMachineChlTest.kt:157`.
- **Why:** it passes on `ae2a3ea`. It would also pass if `nfl()` at `ScoreBugMachine.kt:191` were mutated to `timed()`,
  because `olderNfl()` returns false for any league other than the NFL (`:450`). It cannot fail for the reason it names.
- **Fix:** either:
  - drop it; or
  - make it meaningful by asserting the observable effect of `nfl()` that `olderNfl` does not already hide (none today);
    or
  - note in its KDoc that it is a guard on the `olderNfl` league check, and assert that directly.

  The other two tests that pass on the old code are fine as regression guards; see "Test quality".

### S3. Missing REFUSED cases that the spec (§2.6) and the brief call out

- **Where:** `ScoreBugMachineChlTest.kt`.
- **Missing cases:**
  - **(a) Recovery:** after REFUSED and a 300 s retry that returns `Ok`, the next poll is at `doneMono + interval`, and a
    following TIMEOUT starts again from the 5 s base, not from `failures = 2`.
  - **(b) Rise to SEEN:** UNSEEN → SEEN at +60 s inside a REFUSED back-off does not poll early (the `backingOff` guard).
  - **(c) Pre-game:** a REFUSED before `start − 2 min` retries at `max(+300 s, preWake)`.
- **Status:** all three hold by reading the code (above), but nothing pins them. (b) is the one most likely to regress
  if someone touches `demand()`.
- **Fix:** add three short cases using the existing `Rig`.

## Low

### L1. The League-row test re-focuses before every press, so "focus stays on this row" is untested

- **Where:** `ChlScoreSheetTest.kt:34`.
- **Why:** the test calls `focusOn { ScoreLeagueRow }` (DOWN only) before each SELECT. It never asserts that focus is
  still on the League row after NFL → CHL (when the CHL row is inserted below it) or after CHL → NHL (when it is
  removed). The CHL-row test does assert this for its own row.
- **Fix:** after each SELECT, assert `q.choices[q.focused] is ScoreLeagueRow`.

### L2. A CHL follow with `liveMs == null` falls onto the NHL bucket path

- **Where:** `ScoreBugMachine.kt:428`.
- **Why:** this matches the spec's definition of `timed()` (row 15). It cannot happen in production, because
  `pushInterval` always sends a value once the machine exists. A defensive comment would stop someone "simplifying"
  `pushInterval` into sending null for Auto.
- **Fix:** add a one-line KDoc ("CHL relies on the repository always sending an interval").

### L3. REFUSED ignores a `Retry-After` longer than 300 s

- **Where:** `ScoreBugMachine.kt:234`.
- **Why:** row 12 says this explicitly ("whatever Retry-After says"), so the code is per spec. §2.6 says "Never faster",
  which could be read as `max(300 s, Retry-After)`. HockeyTech and Firebase do not send `Retry-After` on refusals, so
  this is moot today.
- **Fix:** none needed. The lead may want to confirm the reading.

### L4. Stale KDocs

- **Where:**
  - `PollMeter.kt:20-21`: "raised to AUTO_429_FLOOR_S for the NFL"; it now applies to every timed league.
  - `ScoreBugMachine.kt:60`: "the NFL's completion-based schedule".
  - `ScoreBugMachine.kt:413-414`: the `livePoll` doc names only the NFL.
- **Fix:** say "timed leagues (NFL, CHL)".

### L5. `WpaPages.kt:661`: an unreachable branch that would show the wrong text if reached

- **Where:** `WpaPages.kt:661`.
- **Why:** `LeagueGroup.NHL -> SPORTS_INTERVAL_SLOWED` is unreachable (`slowed` requires COMPLETION), which is fine.
  If it were ever reached, it would show the ESPN text for the NHL.
- **Fix:** use `SPORTS_INTERVAL_SLOWED_LEAGUE` with the name, or add a comment saying it is unreachable. Cosmetic.

## Notes

### Placement

- `tabInset` and `width` are now `when (league.sport)`. The Stacked width and height use `!isHockey`.
- `ScoreBugPlacementChlTest` compares CHL to NHL boxes for all 4 corners × 2 sizes × 3 leagues. It also checks the
  392 × 141 footprint (× the scale) and the mirrored top-right `l = 1432` through `place()`.
- Its Stacked test fails on the old code (it got the Pill box, `l = 1420`). The Pill test passes on the old code
  because commit 0's TODO branches already mapped the CHL to hockey. That is fine as a regression guard.

### Exhaustiveness

- Nothing in the five files does `== NHL` / `!= NHL`, or an `else` over League, LeagueGroup or Sport.
- **Remaining `== League.NFL` checks:**
  - in the machine: `:299`, `:342`, `:431`, `:450`, `:496`, `:523`, `:550`, `:570`, which are NFL-only rules
    (row 17, "unchanged");
  - `WpaPages.kt:656`: the fallback, NFL only, per row 24;
  - `PlayerStateMachineImpl.kt:2099`: `todayDivider`, confirmed fine by the design reviewer.
- `PlayerStateMachineImpl.kt:2189` (`NHL, NFL -> OHL` in the CHL row handler) is unreachable, because the row is built
  only for `isChl`. It is exhaustive, so it is acceptable.
- No `TODO(WP-2` is left in the repo.

### Score sheet

- **Cycles:**
  - League row: NHL → NFL → (`chlLeague` when it is a CHL league, else OHL) → NHL.
  - CHL row: OHL → WHL → QMJHL → OHL.
- **Focus:** stays on the pressed row via `indexOfFirst`. When the host later calls `scores()`, `sameRow` keeps focus
  by class.
- **Style row:** shown when `followed?.league ?: shown` is hockey. It is hidden for an NFL follow and for NFL
  browsing with no follow.
- **A followed game survives a league change:** the followed row comes from `f.game`, not from the board.
- **Before WP-5:** `ScoreFacts.chlLeague` stays null until WP-5 (row 50), so the NFL → CHL step always lands on OHL.
- **State restore:** there is nothing to restore in the machine. The sheet is rebuilt from the host's `ScoreFacts`.

### Settings row

- **Stored field and choices:** the row writes `chlIntervalS` by `p.league.group`. The choices are 0/5/10/15/20/30,
  and the labels say "after".
- **Notes:** the title is "CHL". There is no NHL floor note. The slowed note names the sub-league, and the league line
  says "Showing CHL." (the resource uses only `%1$s`, so the one-argument call is safe).
- **Which meter (L4):** the followed league's meter when it is in the shown group, else the shown league's.
  - Shown OHL, followed QMJHL: the QMJHL meter, under the shared `chlIntervalS`.
  - Shown OHL, followed NHL: the OHL meter.
  - Both cases are tested.

### `FakePlayer.kt` one-line recorder

- **Acceptable.** It is a test fixture, and `setScoreChlLeague` defaults to `= Unit` in `PlayerEffects`, so without
  the line the CHL-row test could not observe the effect.
- §8 does not list the file for any package. The lead should note it when merging, because WP-5 tests may want the same
  line (a trivial conflict at most).

## Test quality (each new test against `ae2a3ea`)

| Test | Old code | Verdict |
|---|---|---|
| ScoreBugMachineChl.aLivePollIsTimedFromCompletion | fails (21 637 vs 10 137) | good |
| .aFailureBacksOffFromFiveSeconds | fails (23 660) | good |
| .aRateLimitKeepsTheTwentySecondBase | passes | regression guard (it would catch dropping the 429 exception); fine |
| .refusedIsRetriedOnlyAtThreeHundredSeconds | fails (28 660) | good |
| .otherFailuresKeepTheirOwnBases | fails | good, but only OHL and only 5xx |
| .preGameSleepsUntil…FortySeconds | fails (481 500 bucket vs 480 000) | good |
| .breaksPollAtTwiceTheInterval | fails (21 637) | good |
| .anOlderBodyIsNotHeldForAChlGame | passes | **vacuous** (S2) |
| PollMeterChl (2) | both fail | good |
| ScoreBugPlacementChl.aChlPillIsTheNhlPill | passes | regression guard; fine |
| ScoreBugPlacementChl.aChlStackedBug… | fails (1420 vs 1432) | good |
| SportsIntervalRowChl (4) | all fail | good |
| ChlScoreSheet (3) | all fail | good; see L1 for the weak focus check |

**Missing cases:**
- the REFUSED cases in S3;
- CHL pre-game under UNSEEN (the floor at 120 s);
- a sheet test that NFL → CHL lands on the remembered league and that the CHL row then shows it
  (`ScoreChlRow(remembered)`). Today only the `setScoreLeague` argument is checked.

## Fix pass

Made on top of `1f793e6`, uncommitted.

- **S1:** the lead's (integration checklist); nothing done here.
- **S2:** `anOlderBodyIsNotHeldForAChlGame` was vacuous because `olderNfl` hid the league. Removed the league check from
  `olderNfl` (it is only called under `nfl()`; NFL behaviour is identical), so the test now fails if `nfl()` at the call
  site became `timed()`: the older CHL body would be held.
- **S3:** three REFUSED tests added: recovery after Ok, then a TIMEOUT from the 5 s base; no early poll on UNSEEN to
  SEEN inside the back-off; a refusal before the pre-game wake retries at max(+300 s, wake).
- **L1:** the League-row test asserts that focus stays on the League row after NFL -> CHL and after CHL -> NHL.
- **L2:** `timed()` KDoc says the CHL relies on the repository always sending an interval.
- **L3 (lead ruling):** REFUSED retries at `max(300 s, Retry-After)`, never faster. Code and test changed.
  **For the design doc:** row 12 and §2.6 should read "REFUSED -> `backoffAt = receivedMono + max(BACKOFF_MAX_MS,
  Retry-After)`" instead of "whatever Retry-After says"; the same rule applies to row 37 (the repository day back-off, WP-3).
- **L4:** stale KDocs fixed (`PollMeter`, the `doneMono` and `livePoll` docs).
- **L5:** the unreachable NHL branch of the slowed note now uses the league-named text, with a comment.
- **Missing tests added:** CHL pre-game while the bug is not on screen (120 s floor), and the CHL row showing the
  remembered league after the NFL -> CHL step.
