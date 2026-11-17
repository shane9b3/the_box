# Review: NFL Stacked YDS column design (`docs/design/m6-nfl-stacked-yds.md`)

Adversarial design review, 2026-11-16, against `main` at `92d0122` and the lead's live ESPN sampling
(`…/scratchpad/yds/`: `live1/log.tsv` 20:03–20:48, `live2/log.tsv` 20:35:32–21:07:43, `corestats.json`).

## Verdict: **ready after fixes**

The source choice, the contract shape, the geometry and the golden-hash claim hold up against the code. Fix one
blocker before the build: the spoiler argument is wrong given the design's own evidence. Several should-fixes
change behaviour the owner will see (END OF nTH barely exists on ESPN's feed; TV timeouts never show yards) or would
make the build fail or the checklist unusable.

---

## What live2 shows (as of 21:07:43; still recording)

- **No team timeout and no halftime yet.** Counts are 3/3 in every row and no `lastPlay` type 21 appears. The game is in
  Q2 12:33. Freshness at a team timeout and the halftime feed shape are **still unproven**.
- **Official (TV) timeouts, type 74:** five so far, each 2.5–3 min on the board (20:36:42–20:39:22, 20:42:32–20:45:12,
  21:05:53–21:07:43+, plus live1's 20:24:48–20:27:48 and 20:31:18–20:33:48). Yards stay flat through every one (67/9,
  67/12, 112/82) and equal the summary. These are the longest in-game stoppages. Under the design they never open the
  column.
- **END OF 1ST lasts one sample.** `STATUS_END_PERIOD` appears only at 20:58:13 (10–20 s). From 20:58:23 the board reads
  `STATUS_IN_PROGRESS`, P2 15:00, `lastPlay` type 2 "End Period", and keeps it for **3 min** (until the first Q2 snap at
  21:01:23). `situation` is fully populated throughout (down 1 & 10 "LAC 37", possession, counts 3/3), and
  `competitors[].statistics` is empty. Yards were already final (112/40) at the sample *before* END_PERIOD.
- **Corrections land on the same `lastPlay.id`:** 751 re-typed 3→24 with core 70→100→101 (20:47:52–20:48:32); 826
  re-typed 24→8 (penalty) with core 102→101 (20:49:32–20:50:03).
- **Core vs summary:** core is at the board's sample, the summary one sample behind (20:48:02 100 vs 70; 21:04:03 80 vs
  56; 21:05:03 82 vs 80). This confirms §1.2.

---

## Blocker

### B1. The refresh, and the stated "residual", are not spoiler-safe (§1.6 `attach`, §1.8)
**Evidence.** §1.1's own row: at 20:18:48 core already had BAL 6, while the board still showed `lastPlay …105`. The play
(`…128`) reached the board only at 20:18:58, so the stats lead the board by at least one 10-s sample. Live2 20:41:32 (core 12 at
`…666`, where live1 20:41:18 read 11) shows the same kind of skew. The machine shows an entry at
`max(anchor + delay, received)` (`ScoreBugMachine.kt:292, :633`). The Delay is therefore calibrated against the
**board**, not against the stats.

**Why it matters.** Take the refresh read at `t_r`, near the end of a stoppage. If the next snap happened just before
`t_r`, the stats already include it, while the board lags and the next poll (`t_r` + one cadence, 10 s at the shortest
interval) can still show the stoppage. `attach` then applies the refresh to a TIMEOUT / HALFTIME view, and a big gain
shows before the board, and before the Delayed video, shows the play. The first fetch has the same exposure: a
first poll that lands in the **last** seconds of a stoppage, as the board shows it. §1.8's "would need a play inside a
stoppage's first seconds" has the direction backwards: because the board lags, the risk sits at the stoppage's end.

**Change.**
- Accept `pending` only from a poll with `received ≥ pendingAt + NFL_YDS_CONFIRM_MS` (20 000; larger than the lead
  observed) that is still in the same key.
- Record the first-fetch residual honestly: it needs a first poll inside the last ~10–20 s of the board's stoppage
  window, so it is possible only when the cadence is longer than the stoppage minus the board's lag. Accept it, or
  apply the same confirm rule to the first fetch at the cost of one poll.
- Drop "spoiler-proof by construction" from §0.3.

---

## Should-fix

### S1. END OF nTH is a 10–20 s state on ESPN; the design treats it as a minutes-long break (§0.7, §1.5, §1.8, §1.10)
**Evidence.** Live2 20:58:13, above. `EspnMapper.kt:149-156` maps only `STATUS_END_PERIOD` to INTERMISSION. The
following 3 minutes are LIVE with `lastPlay` type 2, which `stoppageKey` maps to null.

**Why it matters.** The "E" key gets a fetch on at most one poll. The column is open for at most one cadence, or never
at 20–30 s intervals; the refresh never runs; and "breaks last minutes" (§1.8) and "≈ 9 stoppages" (§1.10) are wrong.
This also shows that the **shipped** END OF 1ST bar lasts at most one poll, followed by a 3-min PLAY bar at Q2 15:00.
That belongs to NFL Stacked, not to this increment, but the lead should know.

**Change.** Choose one and record it:
- (a) Drop the E key from the fetch triggers and from the §2 table. This is honest and the simplest.
- (b) Key the change-of-ends break on LIVE with `lastPlay.type == "2"` and treat it as a break. This changes the
  shipped bar too, so it needs an owner call.

Fix §1.8 and §1.10 either way.

### S2. Missing owner-visible decision: TV timeouts (type 74) and the two-minute warning (75) never show yards (§1.5, §8)
**Evidence.** Five 2.5–3 min type-74 stoppages in about 1.3 quarters, against zero team timeouts (live1 and live2). Team
timeouts cluster late in halves.

**Why it matters.** Under S1(a), the owner would see the column in the first half almost only at halftime. That is a
product call, not a data rule, and §8 says nothing about it.

**Change.** Add an owner question: "Yards also during official TV timeouts and the two-minute warning? Default: no
(the mock shows them only at team timeouts and breaks)." If yes, the T key extends to types 74 and 75 with the open bar
unchanged.

### S3. The hand-written streaming scanner is unjustified (§0.6, §1.7, §7)
**Evidence.** Every ESPN body is decoded with kotlinx `decodeFromStream` into DTOs with `ignoreUnknownKeys`, under
`CappedStream`, then drained (`EspnScoreSource.kt:405-449`). The design **also drains the rest** (§1.7 "Body"), so
stopping at byte 30 K saves no wire bytes and no connection time, only the CPU to parse about 100 KB a few times a game.
`corestats.json` shows that `totalYards` (and `netTotalYards`) are team-level values copied identically into the
passing, rushing and receiving categories (all 123; `general` has none). So the "passing first, else fallback,
stats-before-name" logic has no semantic basis.

**Change.**
- Add a four-class DTO: `splits.categories[].{name, stats[].{name, value, displayValue}}`.
- Fetch through the existing `call()`/`classify()` with `events = { null }`. This reuses 404 JSON/HTML, 429, 5xx, the
  cap, the drain and `logTransition` for free.
- Take the first `totalYards` in any category, preferring `value` when finite and integral, else `displayValue`
  `-?\d{1,4}`.
- Delete `NflYards.totalYards(BufferedSource)`, `NFL_YDS_SCAN_DEPTH_MAX`, the depth and escape tests and the
  stats-before-name fixture. Keep the negative and no-total fixtures.
- Use a separate client `newBuilder().callTimeout(…)` of the built `client`. It keeps the WireMeter listener, because
  the factory is copied.

### S4. The inline step needs a tighter budget and a stall breaker (§1.3, §1.7, §7)
**Evidence.**
- Show time anchors to the board's `receivedMono` (`ScoreBugMachine.kt:292, :633`). With Delay ≥ the step's time the
  step is **invisible**. At Delay 0 the TIMEOUT / HALFTIME bar arrives late by the step's time.
- The stats host is a second origin, idle between stoppages, so most stoppage polls pay a fresh DNS + TLS handshake.
- A stall that is not a 429/403 costs the full 5 s on each of up to **3 attempts per stoppage**. Nothing ever blocks
  timeouts.
- `doneMono = now` (`:271`) pushes the next poll back by the same amount.
- Inputs wait meanwhile (`ScoreRepositoryImpl.kt:371-386`).

A "bar first, yards later" shape needs a second `machine.fetched` per poll or a new machine input. That breaks the
one-answer-per-poll invariant (`lastOk`, `doneMono`, the older-body guard, meter samples) and is not simpler.

**Change.** Keep it inline, but:
- (a) Set `NFL_YDS_CALL_TIMEOUT_MS` to 2 500.
- (b) After a TIMEOUT/IO failure, make no further attempt in that stoppage. After two in a row, block yards for
  `NFL_YDS_BLOCK_MS`, as for 429/403.
- (c) State the Delay-anchoring fact in §1.8 / §7, so nobody "fixes" it later.

### S5. A throwable in the yards step kills the whole score loop (§1.7)
**Evidence.**
- `call()` resumes with the exception for any non-IO `Throwable` from the body read (`EspnScoreSource.kt:393-397`).
- Inside `coroutineScope { async; async }` that exception cancels the sibling and rethrows from `game()`.
- `ScoreRepositoryImpl.loop` has no catch around `source.game` (`:374`). The outer catch logs "score loop stopped" and
  ends the bug (`:309-314`).

**Change.** The yards step must catch every non-`CancellationException` throwable and turn it into `Failed(false)`.
Never touch the planner inside the `async` blocks. Add a test: a stats body that makes the parser or mapper throw still
returns the board's `Ok`.

### S6. `NflBodyMerge` will silently blank the digits during a clock fold (§3.8)
**Evidence.** `NflBodyMerge.of` builds a new `StackedNflFrameState` field by field (`StackedNflBug.kt:1326-1339`). The
new trailing `awayYds`/`homeYds` default to "". "Into halftime from play" (§3.5) folds the clock (merge active) while
YDS opens with contents from 300 ms. The merged body has no yards, so the column shows empty digits until the fold
ends at 450, then the digits pop in.

**Change.** Spell it out:
- The clock merge copies the body's (or the yards source's) `awayYds`/`homeYds`.
- The YDS merge and the clock merge compose, each from its own last body.

Add a `StackedNflDrawTest` case: play → halftime with yards, sampled at 350 ms, digits drawn.

### S7. §1.8's promise and the §4.1 Delay test contradict the waiting view (§1.8, §1.11, §4.1)
**Evidence.** `view()` uses `shown ?: queue.firstOrNull()` (`ScoreBugMachine.kt:382-383`). A waiting view (Delay, first
view, restart) therefore shows the oldest **queued** entry before its show time. §1.11 says yards follow it, which is
correct and consistent with the score. §1.8 ("never earlier") and the §4.1 test ("not in any view before its entry is
shown") say otherwise, and the test would fail on a waiting follow.

**Change.** Reword: "never earlier than the score of the same snapshot". Limit the test to the shown-entry path, plus a
waiting case that asserts equality with the score's behaviour.

### S8. The emulator checklist will not run as written (§4.7)
**Evidence.**
- The fake `lastPlay.id` is `eid + window index` (`sports_nfl.py:508`). A backward jump is therefore an "older body",
  held as a no-op for up to `NFL_OLDER_HOLD_MAX_MS` (30 s) and then shown only if score or phase differ
  (`ScoreBugMachine.kt:544-572`).
- Steps 3→4, 4→5 and 6–8 jump backwards.
- After step 3's FINAL the machine **stops polling** (`nextPollAt`: FINAL → null, `:481`), so steps 4–8 see nothing.
- Step 7's 429 sets a 10-min follow-wide block, so step 8 never fetches.
- Step 3's "`final` then `ot walk-off touchdown (post)`" is itself backwards.

**Change.** One forward pass through the timeline at `sports_nfl_timeline_s` 2955:
1. Delay test on "q1 BUF 2nd and 7" / "end 1st" (−2)
2. Pill check
3. "q2 BUF ball" (the timeout, with the refresh)
4. "halftime", with corner, Large and subtitles there
5. `stats_lag_s` on "end 3rd"
6. 404 and stall faults on "q4 KC 3rd and 9"
7. 429 last, on "ot KC 1st and goal at 8"
8. "ot walk-off touchdown (post)"

If a step must repeat, restart the panel timeline and the app (planner and machine reset) and re-follow. This fits
about 20 min. Without a fixed order, the "jump" is not enough.

### S9. The takeover shots test the rare 306 case and skip the common 396 one (§3.6, §4.6)
**Evidence.** A timeout immediately before a field goal ("icing") is routine, so FIELD GOAL pinned at **396** is the
frequent YDS takeover. A score correction at halftime (306) is rare. Arc and posts x scale by `boxW/333`
(`StackedNflLayout.kt:424`, `StackedNflScorePainter.kt:327, :446`). At 396 the posts move about 44 px right before
`postsShift` pulls them back, and no golden shows that.

**Change.** Replace `yds-fg-1500-pinned-306` with `yds-fg-1500-pinned-396` (TL, BR × Std, Large) and keep 306 only in
the FitTest loops.

### S10. FINAL gets exactly one fetch, with no retry and no refresh (§1.5, §1.6)
**Evidence.** `nextPollAt` returns null for FINAL (`ScoreBugMachine.kt:481`). There are no polls after the first FINAL
body, contrary to "the follow lasts 5 min past Final, so one stoppage".

**Why it matters.** A failed fetch leaves FINAL without yards. A walk-off score's yards can be missing if the stats lag
by one sample, as §1.1 says happens.

**Change.** Accept it and document it in §1.5 / §7. Drop the F refresh and retry from the planner tests.

---

## Notes

- **N1 Field choice.** `totalYards` is net of sacks: 123 against `totalYardsFromScrimmage` 142 (gross) in
  `corestats.json`. Net is the broadcast "Total yards", so this is right. Write down why (S3 removes the "passing"
  framing). −2 is observed (20:17:48). The stats return 404 until the first scrimmage play (20:16:48, still the coin
  toss), and the PRE key is null, so nothing is wasted.
- **N2 Golden claim verified.** The file has 1 694 lines, 28 of them comments, so 1 666 entries. `score-bug-stacked-nfl/*`
  comes only from `StackedNflScreenshotTest`, which sizes its layer and the mirrored origin from `STACKED_NFL_W_MAX`
  (`:90, :231, :441-448`), so keeping 333 keeps them stable. The painter's `+ 0f × w` terms are exact. The new yds shots
  and the one e2e case must use `STACKED_NFL_FOOTPRINT_W` for the layer and the mirrored `ox`
  (`StackedNflEndToEndTest.kt:229-232` also hard-codes 333 and `CLOCK_W`). Say so in §4.3.
- **N3 Geometry verified.** The §3.2 corner boxes, halo and probing-line clearance are arithmetically right (Large left
  591 < 600; right 1329 > 1320, a 9 px margin). The real layer adapts through `ScoreBugPlacement.width`
  (`StackedNflBug.kt:189`), and `stackedOriginDx` (`StackedBug.kt:552`) cancels `footW`, so nothing shifts at rest.
  The mirror order matches ST and the mock (`StackedNFL.dc.html:18-21, 126-134`).
- **N4 Foundation breaks a test that belongs to no package.** `StackedNflRunnerAssumptionsTest` asserts
  `totalMs(track, OPEN/CLOSE) > 0` for **every** `StackedNflTrack` (`:19-24`), so a "rest / 0" YDS stub fails it. Have
  the foundation give YDS its real `totalMs`, or name this file in §5.0.
- **N5 WP-2 depends on WP-1's `NflYards.text`.** The stub `toString()` draws "-2" with a hyphen, which would put a wrong
  golden into WP-2's negative shot. Implement `text()` in the foundation (three lines).
- **N6 NHL precedent.** ST §8 does say "Stale in an intermission (S10): SOG open" and "Waiting at final: SOG open"
  (`m6-score-bug-stacked-theme.md:862, :867`). Applying it here is consistent and right: the values are the snapshot's
  own. It departs from the lead's brief, so list it in §8 "Not asked" as a lead decision, which it already is.
- **N7 Scope cuts.**
  - Planner: the 4-key LRU can be a single current key. The older-body flip-flop it protects is a fallback edge case,
    and the machine never shows those bodies.
  - Goldens 45 → about 25: drop the 4 background shots (the column is on the opaque box fill) and 6 of the 8
    other-corner shots (TR and BL differ from BR and TL only in bar direction).
  - Fake panel: drop `stats_lag_s` (it needs a state history; the refresh is covered by `NflYardsPlannerTest` and
    `EspnYardsTest`).
  - Settings: drop `EST_NFL_YARDS_MB_H` (about 3 % of the NFL figure at 10 s; one line in the help text instead).
  - `Fetch.Ok.sideMs` is fine but generic for one league; a `ScoreSource.lastSideMs(id)` getter, like
    `followEndpoint`, would keep `Fetch` untouched. Either works.
- **N8 Work-package split.** It is otherwise file-disjoint after the foundation: WP-1 owns `ScoreBugMachine`, `Espn*`,
  `ScoreRepositoryImpl` and `WpaPages`; WP-2 owns layout, timeline, painter and bug. Only N4 and N5 cross.
- **N9 The owner question** (a separate switch) is genuinely new and the default is sensible. Add S2's question. "END OF
  nTH opens it" is no longer "already answered" once S1 is decided.
- **N10 Unverified.**
  - Freshness at a team timeout (no type 21 sampled) and halftime: live2 has neither yet.
  - Whether `sports.core.api.espn.com` answers the app's own OkHttp User-Agent, since the sampler sent `Mozilla/5.0`.
    Check it with one request from the debug build before WP-1 closes.
  - Cold-connection latency to the second host from a Fire TV.
