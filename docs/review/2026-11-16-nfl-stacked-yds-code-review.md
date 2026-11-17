# NFL YDS column: independent code review (branch `nfl-yds`, `92d0122..aa19775`)

Read-only review of the committed tree. I did not run Gradle; screenshot hashes and tests were not executed.
Evidence: the live2 log (`scratchpad/yds/live2/log.tsv` and raw `sb-*.json`), 20:35 to 21:56:54. **The recording ends in halftime, so it does not cover the start of Q3.**

## Verdict: ready after fixes

There are no correctness or spoiler bugs in the data path, the planner or the mapper rule. Two things should change first. The refresh timing does not work for real team timeouts (S1). Three wall-clock assertions are likely to flake (S2). The committed golden hashes are stale; the merge commit says so (B1).

---

## Blocker

**B1. The golden hashes at `aa19775` are stale for the YDS shots that show the ball.**
- Evidence: the merge commit message for `aa19775` says: "The yards looks that show the ball still carry hashes rendered with the old ball; they are regenerated in the integration pass." `93ab21b` regenerated 226 hashes without YDS. `5d4e848` regenerated them with the 13.5 ball.
- Why it matters: `StackedNflScreenshotTest` fails on the committed tree.
- Fix: regenerate `ui/src/test/resources/golden/sports-hashes.txt` once on the integrated tree. Then look at the `yds-*` shots with possession, mirrored and Large, before accepting them. This is already owned by the integration pass and only needs confirming.

## Should-fix

**S1. The refresh misses team timeouts. Each delay also slips by one poll because of how it is measured.**
- Code: `NflYards.kt:134` measures `nowMono - firstOkAt` against `NFL_YDS_REFRESH_MS`. `firstOkAt` is the fetch's completion time (`EspnScoreSource.kt:245` passes `mono.nowMs()`), while `nowMono` is a later poll's `receivedMono`. `NflYards.kt:192` uses the same pattern for the confirm (`pendingAt` is a completion time).
- At the 10 s NFL cadence:
  - The 30 s refresh comes due at the T0+40 poll, not T0+30, because 30 − δ < 30.
  - The 20 s confirm is met at the poll 30 s later, not 20 s.
  - So a refresh is fetched at T0+40 and first used at T0+70.
- Live evidence: the five team timeouts lasted 60, 50, 50, 60 and 50 s on the feed (21:25:33, 21:39:14, 21:40:54, 21:43:24, 21:44:24). As built, a team timeout never shows its refresh.
- The first fetch can be stale. At 21:39:14 the timeout body appeared, and core still read 175/110 at 21:39:04; by 21:39:34 it read 118. So the timeout would show 110, missing the play that came just before it.
- **Your item (d), the 12 s refresh + 5 s confirm for `T` keys: sound.** The real stats lag the scoreboard; they never lead it. In this log: ≤10 s at 21:40:54 and 21:44:24, ≤20 s at 21:39:14. B1's guard is "a later poll still says timeout". At a 10 s cadence, 5 s means exactly "the next poll", as long as the side time stays ≤ 5 s (the call timeout is 2.5 s). With 12 s the refresh lands on the T0+20 poll, which is past the observed 20 s lag, and is used at T0+30. That leaves about 20–30 s of correct digits per timeout.
- Fix:
  1. Take the 12/5 change.
  2. Measure the refresh from the first attempt's `receivedMono` (the `lastAttemptAt` of the attempt that succeeded), not from completion, so the constants mean what they say. Without this, a 20 s value would also slip to 30 s.
  3. Leave `O`, `E` and `H` at 30/20. Official timeouts and the two-minute warning run 2–3.5 min here (21:05:53→21:09:03, 21:26:53→21:30:24), and breaks run minutes.
  4. Add a poll-grid test: a 10 s cadence, a timeout lasting 50 s, stats that update 15 s after T0. Assert the corrected digits appear before the timeout ends. The existing `theRefreshIsUsedOnlyFromAPollTwentySecondsAfterIt` jumps exactly `REFRESH_MS`, so it cannot catch the slip.

**S2. Three wall-clock assertions in `EspnYardsTest` are likely to flake on a loaded CI runner.**
- The assertions:
  - `kotlin.math.abs(a - b) < 250` (`EspnYardsTest.kt:121`)
  - `side < 600` with two 300 ms header delays (`:124`); TCP connection setup for the second connection counts against it
  - `took in 2_400L..2_950L` (`:233`); the board call and two cancellations must all fit inside a 450 ms window
- Fix:
  - Use 1 000 ms delays. Assert `side in 1_000 until 1_900`, and that the second request arrived before the first's delay ended (`< 1 000`).
  - For the stall test, keep the lower bound and raise the upper to around 3 500 ms. The property that matters is "cut by the 2.5 s call timeout, not the 10 s one".

## Notes (no change required unless cheap)

- **N1. Item (a), the change-of-ends rule against the real Q1 to Q2 sequence: correct.**
  - 20:58:13 is `STATUS_END_PERIOD` 1 at 0:00, lastPlay `…1101` with type "2". From 20:58:23 to 21:01:13 there are 18 bodies of `STATUS_IN_PROGRESS` 2 with `clock` 900.0, the same lastPlay `…1101` and type "2". All of them match `EspnMapper.kt:145-150` and map to INTERMISSION, period 1, 0:00, END_PERIOD.
  - The stoppage key stays `E1`, so there is one fetch and no restart.
  - The older-body guard sees the same lastPlay id at the same (period, clock), so nothing is held.
  - 21:01:23 is the first play (type 5, clock still 15:00). The rule stops on the lastPlay type and the bug goes LIVE "2nd 15:00".
  - Nothing at halftime can match: `STATUS_HALFTIME` is excluded, the lastPlay type is "65", and it is period 2 with clock 0.
  - **Q3 start:** not recorded. By construction period 3 never matches, so the pre-kickoff Q3 body maps LIVE "3rd 15:00" as before (not a regression). That inconsistency with the change of ends is acceptable.
  - Strand risk: the rule needs `clock ≥ 900` and lastPlay type "2" together. Either one moving ends it, and in the log the first play was logged before the clock moved.
  - Overtime (period 5 after `END_PERIOD` 4) is out of the rule's scope and keeps today's behaviour.
- **N2. Item (b), the clock glitch: worse than one row, harmless to the yards.**
  - The clock column reads 0:31 from 21:15:23 to 21:26:03, eleven minutes across 15 plays, and turns to 2:15 at 21:26:13 on the same lastPlay `…1766`.
  - The planner keys on the lastPlay id only, so the yards are unaffected. The mapper rule needs ≥ 900, so a low stuck clock can never trigger it.
  - The machine's older-body guard (`ScoreBugMachine.kt:544-559`) treats 0:31 → 2:15 on the same id as "older" (the clock rose, same score) and holds it. After `NFL_OLDER_HOLD_MAX_MS` it is still a no-op because score and phase are unchanged. So 2:15 is never shown; the 21:26:33 play (a higher id) moves the bug on.
  - Side effect: a refresh confirmed on such a held poll (`lastOk = f.copy(value = held)`, `:276`) adds no queue entry, so it surfaces only if a later body in the same stoppage arrives.
  - This is behaviour from before this branch. I would not change it here.
- **N3. Item (c), timeouts reset to 3/3 at 21:46:44 during halftime: no effect on the yards.** The key `H2` does not depend on the counts. The pips animate to 3 as an ordinary count change. There is no burn, because the burn needs a timeout mark and counts rising never make one. That is correct for the second half.
- **N4. Failure containment: sound.**
  - Both stats calls run inside `coroutineScope`. A throw in either one cancels the other and is caught as `BAD_BODY` (`EspnScoreSource.kt:287`). `withYards` catches everything except cancellation (`:251`).
  - Cancellation reaches OkHttp through `invokeOnCancellation`.
  - `statsClient` (`:146`) is `client.newBuilder()`. It shares the pool and dispatcher, keeps the WireMeter listener and `cache(null)`, and its 2.5 s `callTimeout` bounds connect, read and body.
  - A 403 or 429 on the stats never reaches `measure()`: the board's `Ok` is returned, so the follow is never slowed.
  - `workMs` excludes `sideMs` (`ScoreRepositoryImpl.kt:386`), and `wireBytes` is the board's only.
  - The stale rule and the Delay anchor on the board's `receivedMono`.
  - Two residual effects, both within the design's 2.5 s bound and limited to at most three attempts per stoppage:
    - `machine.fetched` runs up to about 2.5 s later at an attempt poll.
    - `doneMono` includes the side time, so the timed cadence slips by the same amount.
  - Logs carry path labels only. The planner and `yardsFollow` are touched only from the follow loop, which is the sole caller of `game()`; `today()` and `day()` never touch them.
- **N5. Item 3, the blast radius of the mapper rule.**
  - The Pill and the Sports-tab cards now read "End of 1st" for the whole change of ends instead of "2nd 15:00". That is intended and consistent.
  - `DayPollPolicy`, picker grouping and `isLive` treat INTERMISSION like LIVE.
  - The break cadence (`breakMs()` = max(2 × 10 s, 20 s)) now covers the 3-minute change of ends, so the first Q2 play can show up to 10 s later than before. This matches halftime and the old END_PERIOD sample. ESPN itself logged the first snap about 30 s before the clock moved. Acceptable.
  - Stale stays at max(60 s, 2 × 20 s) = 60 s, unchanged.
  - CHL and NHL are untouched: the `game(id, start, extras)` default ignores extras.
- **N6. Drawing: checked against the code.**
  - `ydsOpen` is `known` in breaks, Final and stale or waiting breaks. It is false on the flag bar and on a stale or waiting view in play (`StackedNflLayout.kt:139-167`). That matches what the source attaches, which is stoppage snapshots only.
  - A timeout found only from a drop in the counts (no type-21 lastPlay) gets no key, so no column. That is consistent.
  - Mirrored geometry: the clip, the inset line on the rows' side, and `ydsDx` negated (`StackedNflPainter.kt` `drawYds`); the clock column after `yo` in both directions.
  - Pin and glow use `yds.want`.
  - `NflBodyMerge` picks clock texts and digits independently from their own last-open bodies.
  - No per-frame allocation: the label is resolved per body, and `ydsInk` is used by tests only.
  - The content description is added only while `ydsOpen`.
  - The larger ball (21) is not hard-coded anywhere:
    - Painter, sprite and `BALL_TOP` derive from the constant.
    - `codePx` room is now 64.2; WSH measures 62.7, so no code steps down.
    - The ball box (13.35–34.35) clears the pips (40.05).
    - The narrowest gap to the score is 16.4, not the "19 px" in the `93ab21b` message.
    - The takeover's ball uses its own `spec.runPx`, and `arcK` and the pin maths do not involve the ball.
- **N7.** `NFL_YDS_SUCCESSES_MAX` (`SportsConstants.kt:737`) is unused. The planner hard-codes `successes == 1` (`NflYards.kt:134`). Use it or delete it.
- **N8.** The fake panel route matches the app exactly: `{origin}/v2/sports/football/leagues/nfl/events/{eid}/competitions/{eid}/competitors/{tid}/statistics`, from both `fromOverride` and `STATS_PREFIX`. The global `status` fault also hits the stats route, which is fine. `README.md:328` calls the body "season-to-date statistics". It is the per-game (event) statistics; fix the wording.
- **N9.** `catch (t: Throwable)` in the yards step also swallows `Error`s (an OOM, for example). That is acceptable for a side feature, but catching `Exception` would be more conventional.
- **N10. Item 7, over-build:** nothing worth cutting before merge. The fake panel's `jump` and `windows` are cheap and make the manual gate practical.

## Tests

- Coverage is good:
  - planner rules (17 tests)
  - mapper shapes, including the fourth-quarter shape and the end of the sequence
  - machine views under Delay, waiting and stale break
  - repository gating and the work sample
  - transport, catch-all, 403/429 block and log redaction
- No existing test was weakened. The changed assertions (333 → 396 footprint, the placement boxes, the override endpoint triple) follow the approved footprint change.
- Missing:
  - the poll-grid timing test (S1)
  - a replay of the real live2 sequence through mapper and planner: E1 across END_PERIOD into the change of ends, back-to-back T keys at 21:43:24 and 21:44:24, H2 with the 3/3 reset

## Could not verify

- The start of Q3 and halftime → Q3; the recording ends at 21:56:54.
- Any Gradle result: tests, lint, screenshot hashes.
- Rendered pixels of the YDS shots with the 21 ball.
- ESPN core-API behaviour beyond these samples: 403 frequency, and whether a competition id always equals the event id.

---

## Fix pass (integration, 2026-11-16)

- **B1:** `sports-hashes.txt` updated on the merged tree. 13 `yds-*` shots draw the ball and were replaced: `yds-timeout` (TL, BR, TR, BL Std; TL, BR Large), `yds-timeout-still`, `yds-official-timeout` ×2, `yds-open-t225` ×2 and `yds-close-t425` ×2. The other 13 `yds-*`, all 406 other `score-bug-stacked-nfl/` and all 210 `nfl-screenshots/` (Pill) lines hash equal on the merged tree. Two runs gave the same hashes. The file still has 1 692 entries, and no line outside `score-bug-stacked-nfl/yds-*` changed except the comment note.
- **S1:** accepted with the 12 s / 5 s change for `"T"` keys (`NFL_YDS_TIMEOUT_REFRESH_MS`, `NFL_YDS_TIMEOUT_CONFIRM_MS`). O, E, H and F stay at 30 s / 20 s. `firstOkAt` and `pendingAt` are now the attempt's poll `receivedMono`, not the completion time. New poll-grid tests (10 s and 5 s grids, a 50 s timeout whose yards change 15 s in) show the corrected digits from T0+30 and T0+20. `EspnYardsTest` gains a team-timeout case. Design: "Integration rulings" I1 and I2.
- **S2:** `EspnYardsTest` now uses 1 s delays, `sideMs` in 1 000..1 899 and an overlap under 1 000 ms. The stall's upper bound is 3 500 ms.
- **N7:** `NFL_YDS_SUCCESSES_MAX` is now used by `NflYardsPlanner.due`.
- **N8:** the fake panel README says per-game (per-event) statistics, not season-to-date.
- **Tests, the live2 replay:** `NflYardsLive2ReplayTest` covers 20:58–22:00:25 (the recording does run to 22:00:25), using a 14 KB trimmed fixture, `scoreboard-live2-replay-2026-11-16.json`. It runs through the mapper and planner and checks four things:
  - E1 across 19 change-of-ends polls;
  - the late yards at 21:39:14 (110, then 118 from 21:39:44);
  - back-to-back T keys (161 from 21:44:54);
  - H2 through the 3/3 reset and the Q3 kick-off still on a halftime body, then LIVE P3 with no fetch.
- **N2, N9:** not changed (from before this branch / acceptable). N2's side effect is recorded as an open item: a refresh confirmed on a poll the older-body guard holds is not shown until a later body in the same stoppage arrives.
