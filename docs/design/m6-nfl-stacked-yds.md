# NFL Stacked score bug: the YDS column (team total yards): design

Status: **proposed 2026-11-16; reviewed 2026-11-16 (ready after fixes); revised under the lead's rulings 2026-11-16;
built (foundation, WP-1, WP-2, WP-3); integrated 2026-11-16 under the "Integration rulings" at the end.** Code review:
`docs/review/2026-11-16-nfl-stacked-yds-code-review.md`. Review: `docs/review/2026-11-16-nfl-stacked-yds-design-review.md` (B1, S1–S10,
N1–N10). Decision: `docs/decisions/2026-11-16-nfl-stacked-yards-column.md`. Every finding's change is listed in
"Review rulings" at the end; items marked "(Sn)" / "(Bn)" / "(Nn)" apply it in place.

Owner approval (2026-11-16): build the YDS column of the Stacked NFL bug, which v1 left out (lead sign-off item 6 / RC6
and owner question 2 of `docs/design/m6-nfl-stacked.md`, "NFL Stacked §n" below). **No on/off switch** (owner,
2026-11-16). Appendix A of NFL Stacked holds the mock-derived numbers; this document confirms or corrects each of them
(§3) and adds the data path (§1).

Builds on (binding, not re-asked): NFL Stacked (the whole doc and its sign-off, commit-0, integration and fix-pass
rulings); `docs/design/m6-score-bug-stacked-theme.md` ("ST §n"), whose **SOG column is the precedent** for every column
rule here (geometry, motion, mirror, stale, missing data, pin); `docs/design/m6-nfl.md` ("NFL §n");
`docs/design/m6-sports-update-interval.md` ("UI §n", the meter). The code is `main` at `92d0122`.

Units are design px (screen width ÷ 1920) and ms. **Must** means a reviewer rejects code that does otherwise.

---

## 0. Decisions at a glance

1. **Source: ESPN's per-team statistics feed** (`sports.core.api.espn.com/.../competitors/{teamId}/statistics`),
   the first `totalYards`. It is net of sacks, the broadcast "total yards" (N1). Two requests of 8–10 KB gzip each.
   It runs on the scoreboard's sample, and sometimes one sample ahead of it. The game summary is rejected: 16–51 KB,
   one sample behind (§1.2).
2. **The fetch lives in `EspnScoreSource` and rides on the follow poll.** When the polled snapshot is in a
   **stoppage**, the source fetches both teams in parallel and attaches the yards to that snapshot
   (`LeagueDetail.Nfl.awayYards/homeYards`). Stoppages are:
   - a team timeout (type 21);
   - an official timeout or the two-minute warning (74 / 75; S2, lead default);
   - END OF nTH, including the change-of-ends shape (S1, lead default);
   - HALFTIME and FINAL.
3. **Delay-safe within a stated residual (B1).** The yards ride on their snapshot through the delay queue, so they
   show exactly when its score shows. A refresh is used only from a poll at least 20 s after it (**5 s at a team
   timeout**, integration ruling I1) that is still in the same stoppage. A first fetch at the very end of a stoppage can
   carry the next play's yards (§1.8).
4. **Per stoppage: one fetch, then one refresh at least 30 s later (12 s at a team timeout, I1).** Both delays count
   from the polls' `receivedMono` (I2).
   - A non-stall failure retries after 15 s, with at most 3 attempts.
   - A timeout or IO failure ends that stoppage's attempts.
   - A 429 or 403, or two timeout / IO failures in a row, stop the yards for 10 min (S4). The scoreboard is never
     touched.
   - Calls time out at 2.5 s (S4). FINAL gets exactly one fetch (S10).
5. **Fetched only when it can be seen:** an NFL follow, Style **Stacked**, and the full-screen player showing the follow
   (`FULL_WITH_FOLLOW`, demand SEEN). Pill never fetches.
6. **Parsing goes through the existing ESPN path (S3):** a four-class kotlinx DTO through `call()` / `classify()`, with
   the cap, the drain, the error mapping and `logTransition` as every ESPN read. The value is kept in −99..999.
7. **When the column opens:** whenever the view carries both teams' yards, except under a flag and in a stale or
   waiting play state. The source attaches yards only to stoppage snapshots, so this means:
   - a team timeout, an official timeout, the two-minute warning;
   - END OF nTH, HALFTIME, FINAL;
   - a break that goes stale or waiting (the NHL SOG rule).
8. **Late yards open the column late.** A refresh changes the digits in place. A stoppage that ends first never opens
   it.
9. **Geometry as Appendix A, confirmed against the mock.**
   - Column 63 (140 mock); digits 21.6 tabular; "YDS" 18 px on the row line; the label's rules dropped.
   - Open: width 450 Std, contents after 300.
   - Close: contents 200, then the width 450 after **200**.
10. **Widths:** 396 at an in-play stoppage (rows + YDS + clock); 306 in a break or at Final (rows + YDS). The footprint
    is **396 × 141** Standard and **495 × 176.25** Large: 4 (Large 5) px wider than the NHL Stacked's. The rows never
    move.
11. **Mirror:** `[clock][YDS][rows]` from the screen edge inwards on the right. Contents anchor to the rows-side edge
    and the slide is mirrored. Glyphs never flip.
12. **Negative yards** draw with U+2212 "−". One to three digits and "−99" fit at 21.6 with 6 px of air.
13. **Existing pixels are unchanged.** `STACKED_NFL_W_MAX` keeps 333 (the play box and the takeover's `arcK`
    reference); the footprint is a separate constant. All 1 666 golden lines stay; **26** new
    `score-bug-stacked-nfl/yds-*` shots are appended (N7).
14. **Data meter:** the per-poll sample stays the scoreboard only (`wireBytes`; `sideMs` is excluded from the work
    sample). The settings "MB an hour" figure is unchanged (N7). The yards add about 0.27 MB/h with S2 (§1.10).
15. **Work:** a foundation (built), then three disjoint packages: data + mapper + machine (Opus), drawing (Opus), and
    the fake panel (Sonnet).

---

## 1. Data path

### 1.1 Evidence (the lead's live sampling 2026-11-16, LAC @ BAL; not redone)

Files under `/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/yds/`:
- `sampler.py`: the exact URLs;
- `live1/log.tsv` (20:03–20:48) and `live2/log.tsv` (20:35–21:12+, still recording): scoreboard samples every 10 s,
  both yards sources every third sample, with gzip sizes;
- `corestats.json`: a final per-team body, ARI in LAR @ ARI 401873061.

| Fact | Evidence |
|---|---|
| Per-team `totalYards` tracks plays on the same sample, sometimes one later | live1 20:20:18 (11-yd run `…5172`), 20:21:48 (`…5234`), 20:40:48 (sack `…5646`) |
| It can be **one sample ahead** of the board (B1) | live1 20:18:48: core BAL 6 while the board's `lastPlay` is still `…5105`; the pass `…5128` reaches the board at 20:18:58; live2 20:41:32 |
| Corrections land on the same `lastPlay.id` | live2 `…751` re-typed 3→24 with core 70→100→101 (20:47:52–20:48:32); `…826` re-typed 24→8, core 102→101 |
| The summary is one sample behind | live1 20:18:48 (−2 vs 6); live2 20:48:02 (70 vs 100), 21:04:03 (56 vs 80) |
| Negative values occur | live1 20:17:48 BAL −2 |
| 404 until the first scrimmage play | live1 20:03:17, 20:16:48 |
| Size | 16.4–18.3 KB gzip for both teams (≈ 8–9 KB each); the final body 10.4 KB gzip / 126 KB raw |
| **Official timeouts (74) are the long in-game stoppages** (S2) | five of 2.5–3 min in about 1.3 quarters (live1 20:24:48–20:27:48, 20:31:18–20:33:48; live2 20:36:42–20:39:22, 20:42:32–20:45:12, 21:05:53–); yards flat through each |
| **END OF 1ST is one sample** (S1) | live2: `STATUS_END_PERIOD` at 20:58:13 only. From 20:58:23 the board reads `STATUS_IN_PROGRESS`, period 2, 15:00, `lastPlay` `…1101` type 2 "END QUARTER 1", `situation` populated (1 & 10, possession, 3/3). This lasts **3 min**, until the first Q2 snap at 21:01:23, which still shows **15:00** (the clock lags the play) |
| No team timeout (21), no halftime, no Q3 start | live1 and live2 through Q2 10:16 (21:12:53): counts 3/3 throughout. (Superseded by the three rows below: live2 ran on to 22:00:25.) |
| **Team timeouts: the previous play's yards often reach the feed late** (I1) | live2 21:25–21:45, five team timeouts (21:25:33, 21:39:14, 21:40:54, 21:43:24, 21:44:24), each 50–60 s on the board. In three the feed caught up 10–20 s after the type 21 appeared: 21:39:14 timeout, core BAL 110 at 21:39:04 → **118 at 21:39:34**; 21:40:54 → 131 by 21:41:04; 21:44:24, 143 at 21:44:04 → 161 at 21:44:34. The feed lagged the board; it never led it at a timeout |
| **Halftime** (I3) | live2 21:45:24–21:59:45: `STATUS_HALFTIME`, period 2, 0:00, `lastPlay` type **65** "END QUARTER 2"; `situation` stays populated (1 & 10 at 35); core 175 / 161 at the first halftime sample (21:45:34), unchanged to the end; the timeout counts read 1 / 0 until **21:46:44**, then 3 / 3 (about 80 s in) |
| **The start of Q3** (I3) | 21:59:45 is still `STATUS_HALFTIME` with the kick-off (type 53) as `lastPlay` (key `"H2"`, mapped as halftime); 21:59:55 is `STATUS_IN_PROGRESS` P3 14:55 with that kick-off (LIVE, no key); the first scrimmage play at 22:00:25. No change-of-ends shape at the start of Q3 |
| The stats host answers the app's client | the lead, 2026-11-16: HTTP 200 with `okhttp/4.12.0` and with a plain user agent (N10) |

`corestats.json`: `totalYards` (and `netTotalYards`) are team values copied identically into the passing, rushing and
receiving categories (all 123); `general` has none (S3, N1). Every category carries an `athletes` array of `$ref`
links, which the fixtures drop.

### 1.2 Source: per-team statistics (the lead's recommendation, confirmed)

| | Per-team statistics | Game summary |
|---|---|---|
| Freshness | the board's sample, sometimes one ahead or one behind | one sample behind |
| Bytes per fetch | ≈ 17–20 KB gzip (two requests) | 16 → 51 KB gzip (one request), growing all game |
| Host | a second ESPN host (`sports.core.api.espn.com`) | the scoreboard's host |
| Risk | undocumented host and shape | the same, and the lag shows the yards from before a timeout's last play |

Chosen: per-team statistics. The column exists for the stoppage just after a play, and the summary would usually still
be missing that play.

### 1.3 Where it lives

```
ScoreRepositoryImpl.loop (unchanged shape)                                   data/.../ScoreRepositoryImpl.kt:371-386
  └─ source.game(id, startUtcMs, FollowExtras(nflYards = …))                 §1.4, §1.9
       EspnScoreSource.game(…, extras)                                        data/.../EspnScoreSource.kt:155-162
         ├─ eventGame / boardGame → Fetch.Ok(snapshot)                        :199-247, :263-285
         │    (EspnMapper maps the change-of-ends shape to END_PERIOD, §1.12)
         └─ extras.nflYards && Ok → the yards step (try/catch-all, S5):
              planner.due(snapshot, received) → key?                          core NflYardsPlanner, §1.6
              key → two stats GETs in parallel (2.5 s each)                   §1.7
              planner.fetched(key, now, result)                               (never inside the async blocks)
              Ok.copy(value = planner.attach(snapshot, received), sideMs = …)
  └─ machine.fetched(f)  → delay queue → NflView.awayYards/homeYards          core ScoreBugMachine.kt:810-821
       → StackedNflTargets.of(v).ydsOpen → runner YDS track → painter         §2, §3
```

There is no new actor, scope, timer or wake-up. The step is inline in the follow poll (A-S8), so a stoppage poll takes
at most `NFL_YDS_CALL_TIMEOUT_MS` (2.5 s) longer. A "bar first, yards later" shape would need a second
`machine.fetched` per poll, which breaks the one-answer-per-poll invariants (`lastOk`, `doneMono`, the older-body guard,
the meter), so it is rejected (S4). `ScoreBugSource` is unchanged.

### 1.4 Contract changes (foundation; trailing, defaulted; **built**)

`core/src/main/kotlin/app/thebox/core/sports/SportsContracts.kt`:
- `LeagueDetail.Nfl.awayYards` / `homeYards: Int? = null`: attached by the source, never by `EspnMapper`.
- `Fetch.Ok.sideMs: Long = 0L`: the yards step's time, excluded from the meter's work sample.
- `data class FollowExtras(val nflYards: Boolean = false)` with `NONE`.
- `ScoreSource.game(id, startUtcMs, extras): Fetch<GameSnapshot>`, an additive overload whose default ignores the
  extras.
- `NflView.awayYards` / `homeYards: Int? = null`: Stacked only; the Pill never reads them.

`core/.../sports/EspnDto.kt`: `EspnStatsDto` → `EspnStatsSplitsDto(categories)` → `EspnStatsCategoryDto(name, stats)` →
`EspnStatDto(name, value, displayValue)`. The leaves are `JsonElement?`, decoded with `ignoreUnknownKeys` (S3).

`core/.../sports/NflYards.kt` (new):
- `object NflYards`:
  - `stoppageKey(GameSnapshot): String?` (stub null);
  - `teamIds(EspnEventDto): Pair<String, String>?` (stub null);
  - `totalYards(EspnStatsDto): Int?` (stub null);
  - `text(Int): String`, **implemented** ("−2" with U+2212, ASCII digits; N5); `MINUS`.
- `sealed interface YardsResult { Ok(away, home); Failed(kind: FailKind) }`. A 403 is reported as `HTTP_429`.
- `class NflYardsPlanner { due(s, nowMono): String?; fetched(key, atMono, r); attach(s, receivedMono): GameSnapshot }`.
  The stub is never due and attaches nothing.

Pill impact (as ST C N1 / NFL Stacked §3.2): the new `NflView` fields are part of its equality. A stoppage poll that
adds or changes yards therefore emits one view, and the Pill recomposes to the same frame.

### 1.5 The trigger: stoppage keys

`NflYards.stoppageKey(s)` (WP-1); `d = s.detail as? LeagueDetail.Nfl`, and a null `d` gives null:

| Snapshot (as mapped, §1.12) | Key | Notes |
|---|---|---|
| `phase == FINAL` | `"F"` | **exactly one fetch**: the machine stops polling at FINAL (`ScoreBugMachine.kt:481`), so no refresh and no retry; a failed or lagging fetch leaves FINAL without yards (S10) |
| `INTERMISSION`, `breakKind == HALFTIME` | `"H" + period` | halftime's feed is unproven |
| `INTERMISSION`, otherwise | `"E" + period` | `STATUS_END_PERIOD`, and the change-of-ends shape mapped to it (S1): about 3 min, so the refresh runs |
| `LIVE`, `lastPlay.typeId == "21"` | `"T" + lastPlay.id` | team timeout |
| `LIVE`, `lastPlay.typeId` `"74"` or `"75"` | `"O" + lastPlay.id` | official timeout / two-minute warning (S2, lead default); 2.5–3 min live |
| anything else (types 8, 2 outside S1, 65, 66; PRE; SCHEDULED; NHL) | null | no fetch |

- The 8-s fallback timeout mark has no key of its own: it fetches nothing. It shows yards only when its snapshot is
  itself a stoppage, for example a 74.
- A key is never logged or persisted.

### 1.6 The planner (`NflYardsPlanner`, `:core`, pure; one per follow in `EspnScoreSource`; WP-1)

**State:**
- One **current** key, with `attempts`, `successes`, `lastAttemptAt`, `lastFailedRetryable`, `firstOkAt`,
  `values (away, home)?`, `pending (away, home)?` and `pendingAt`. A new key replaces it (N7: the board fallback's
  older-body flip-flop costs at most one refetch).
- Follow-wide: `blockedUntil` and `stalls` (consecutive timeout / IO failures).

**`due(s, now)`:**
- Let `key = stoppageKey(s)`. Return null when the key is null, when `now < blockedUntil`, or when
  `attempts ≥ NFL_YDS_ATTEMPTS_MAX` (3).
- Otherwise return the key, and set `attempts++` and `lastAttemptAt = now`, when one of these holds:
  1. `attempts == 0`;
  2. the last attempt failed **retryably** (not a timeout / IO) and `now − lastAttemptAt ≥ NFL_YDS_RETRY_MS` (15 000);
  3. `successes < NFL_YDS_SUCCESSES_MAX` (2), `now − firstOkAt ≥ NFL_YDS_REFRESH_MS` (30 000; **`NFL_YDS_TIMEOUT_REFRESH_MS`
     12 000 for a team-timeout `"T"` key**, I1) and the key is not `"F"` (the one refresh).
- A key whose last attempt was a timeout / IO failure is never due again (S4b).

**`fetched(key, at, r)`** (an unknown key is ignored):
- `Ok(a, h)`:
  - `stalls = 0`, `successes++`;
  - the first success: `values = (a, h)`, `firstOkAt = lastAttemptAt` (the poll's `receivedMono` that `due` was asked
    with, not `at`; I2);
  - the refresh: `pending = (a, h)`, `pendingAt = lastAttemptAt` (I2).
- `at` (the completion) only times a block.
- `Failed(HTTP_429)`: `blockedUntil = at + NFL_YDS_BLOCK_MS` (600 000).
- `Failed(TIMEOUT | IO)`: no retry in this key; `stalls++`, and at `NFL_YDS_STALLS_TO_BLOCK` (2) the same block.
- `Failed(anything else)`: retryable; `stalls = 0`.

**`attach(s, received)`:**
- If `stoppageKey(s) == current` and `pending != null` and `received ≥ pendingAt + NFL_YDS_CONFIRM_MS` (20 000;
  **`NFL_YDS_TIMEOUT_CONFIRM_MS` 5 000 for a `"T"` key**, I1), then `values = pending` and `pending = null` (B1).
- On the NFL's 10 s grid a team timeout first seen at T0 reads at T0, refreshes at T0 + 20 and shows the refresh from
  T0 + 30; on a 5 s grid T0 + 15 and T0 + 20. Every other stoppage refreshes at T0 + 30 and shows it from T0 + 50.
- Then, if `values != null`, return `s` with `d.copy(awayYards, homeYards)`; otherwise return `s` unchanged.

### 1.7 The request (WP-1)

- **URL:** `EspnEndpoints.statsBase` + `events/{eventId}/competitions/{eventId}/competitors/{teamId}/statistics`
  (`addPathSegment`, no query).
  - `statsBase` defaults to `STATS_BASE` = `https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/`. It is a
    new trailing field on `EspnEndpoints` (`EspnScoreSource.kt:56`).
  - `fromOverride` (`:63-69`) maps the debug `files/debug/sports_base` origin to
    `"$origin/v2/sports/football/leagues/nfl/"`. The fake panel serves that path.
- **Team ids:** `NflYards.teamIds(event)` on the decoded event, captured per follow in `eventGame` (`:213-216`) and
  `gameFrom` (`:325-333`). With no ids nothing is fetched (logged once per follow, without the id).
- **Transport (S3):**
  - `call(url, EspnStatsDto.serializer()) { null }`, with `call()` / `classify()` taking the client as a parameter;
  - the statistics client is `client.newBuilder().callTimeout(NFL_YDS_CALL_TIMEOUT_MS)` of the built `client`
    (it keeps the `WireMeter` listener factory);
  - `CappedStream`, the decode, the drain and the 404 / 400 / 429 / 5xx mapping are unchanged;
  - a 403 (`answer.code`) is reported as `HTTP_429`, as `:225`.
- **Parallel, and crash-proof (S5):**
  - Both teams run under `coroutineScope { async {…}; async {…} }`, each returning a `Fetch`.
  - The whole step is wrapped in `try { … } catch (e: CancellationException) { throw e } catch (t: Throwable) {
    Failed(BAD_BODY) }`.
  - The planner is touched only after both complete, never inside an `async`.
  - The scoreboard's `Ok` is returned whatever happens.
- **Outcome:**
  - both calls `Ok` and both `NflYards.totalYards` non-null → `YardsResult.Ok`;
  - otherwise `Failed(kind)`: 429 (or 403) if either call had it, else TIMEOUT / IO if either had it, else the first
    other kind (`BAD_BODY` for a null value).
- **Value (`NflYards.totalYards`, WP-1):**
  - the first stat named `totalYards` in any category;
  - its `value` when finite and integral, else its `displayValue` when it matches `-?\d{1,4}`;
  - null when outside −99..999.
- **The follow is untouched by a yards failure.** The machine's back-off never sees one, and a stats 429 / 403 does not
  set the follow's `slowed`.
- **Logging:** `logTransition("competitors/{team}/statistics", …)`, state changes only, never an id, URL or body.

### 1.8 Delay and spoilers (B1, S4c, S7)

- The yards are a field of the snapshot they were fetched with, so the Delay applies to them exactly as to that
  snapshot's score: never earlier than the score of the same snapshot. A **waiting** view (the delay has not run
  out, the first view, a restart) shows the oldest queued entry (`ScoreBugMachine.kt:383-384`), and its yards with it,
  as it already shows that entry's score (S7).
- **Show time anchors on the scoreboard's `receivedMono`** (`ScoreBugMachine.kt:292, :633`).
  - At a Delay at least as long as the step, the step's time is invisible.
  - At Delay 0 a stoppage's bar appears later by the step's time, at most 2.5 s because the two calls run in parallel.
  - This is deliberate; do not "fix" it with a second answer per poll (S4c).
- **The stats can lead the board by a sample.** Two consequences:
  - **Refresh:** read at `t_r`, it is used only from a poll at `≥ t_r + 20 s` that still shows the same stoppage. If the
    next snap happened just before `t_r`, the board shows it within about 10–20 s, the key changes, and the refresh is
    dropped.
  - **Team timeout (I1):** read at `t_r ≥ T0 + 12 s`, used from a poll at `≥ t_r + 5 s` still showing the same type 21.
    No play runs during a team timeout and it lasts at least 30 s, so the refresh normally reads the timeout's own
    (late) yards. Residual: the refresh is not capped to the first 30 s of the timeout, so a first poll that lands late
    in a long timeout can refresh near its end; the 5 s confirm then guards only by one poll (stated, not changed).
  - **First fetch, the residual (accepted):** a first poll that lands in the last ≈ 10–20 s of the board's stoppage
    window can read stats that already include the next play, and the column then shows them with the stoppage. For
    that, the poll cadence must be longer than the stoppage minus the board's lag. At the NFL's 5–30 s intervals and
    stoppages of 30 s to minutes, it needs a first poll that misses almost the whole stoppage. The cost of the
    alternative (confirming the first fetch too) is one poll for every stoppage.

### 1.9 Who fetches, and when not

`ScoreRepositoryImpl` builds the extras per follow poll (`:374`):

```kotlin
FollowExtras(nflYards = SportsConstants.STACKED_NFL_YDS_ENABLED && game.league == League.NFL &&
    sports.nhlStyle == NhlBugStyle.STACKED && followDemand == Demand.SEEN && DemandOwner.FULL_WITH_FOLLOW in owners)
```

- **Pill:** nothing is fetched; bytes and timings are unchanged.
- **Sports tab or picker only:** nothing is fetched. Opening the player mid-stoppage fetches on the next poll.
- **First view / restart:** the planner is per follow and per process. The machine snaps the first view (rule 2), so a
  halftime with yards appears open. A team timeout on the first view is not marked by the machine, but its
  yards still open the column with the play bar (§2), which is right for a stoppage.
- **Stale:** with no `Ok` there is no step. The planner keeps its key; on recovery in the same stoppage, the values
  attach again.

### 1.10 Data meter (UI §3.2; N7)

- `Fetch.Ok.wireBytes` stays the **scoreboard** body: the per-poll sample whose median `PollMeter.bytesPerPoll` uses.
- `sideMs` is excluded from the work sample: `workMs = mono.nowMs() − t0 − sideMs` (`ScoreRepositoryImpl.kt:383`).
  Otherwise stoppage polls can become the window's p90 (rank 18 of 20).
- **The settings figure is unchanged** (N7; no constant). The yards cost, recorded here:
  - stoppages per game: ≈ 5 team timeouts, ≈ 15 official timeouts and two-minute warnings (S2; five seen in 1.3
    quarters), END OF 1ST / 3RD, HALFTIME and FINAL, so ≈ 24;
  - each costs ≤ 2 fetches × ≈ 18 KB;
  - total ≈ 0.85 MB per ≈ 3.2-h game ≈ **0.27 MB/h**, about 10–25 % of the NFL figure at 30–10 s intervals.

### 1.11 The machine (`ScoreBugMachine`, NFL view only; WP-1)

`nflView` (`:770-823`) sets the yards from the view's own entry `e`, in every NFL view (fresh, stale, waiting):

```kotlin
val yd = d?.takeIf { it.awayYards != null && it.homeYards != null }
NflView(…, awayYards = yd?.awayYards, homeYards = yd?.homeYards)
```

- There is no hold across snapshots.
- `knownView()`, the Pill and `olderNfl` / `holdsOlder` are unchanged. An older body is never shown, so its yards never
  are.

### 1.12 The change-of-ends shape (S1, lead default; `EspnMapper`, WP-1)

**Rule.** In `EspnMapper.event` (`EspnMapper.kt:141-156`), map a body to the end-of-period break of the previous
period when all of these hold:
- `state == "in"`;
- `type.name` is neither `STATUS_END_PERIOD` nor `STATUS_HALFTIME` (and not postponed / cancelled);
- `beforeKickoff` is false;
- `period ∈ {2, 4}`;
- `situation.lastPlay.type.id == NFL_TYPE_END_PERIOD` ("2");
- the clock `secs ≥ NFL_QUARTER_SECS` (900).

It then produces exactly what a `STATUS_END_PERIOD` body produces:
- `phase = INTERMISSION`, `breakKind = END_PERIOD`;
- `period = period − 1`, with `periodKind` / `otNumber` / `finalKind` from that period;
- `clock = GameClock(0, running = false, inIntermission = true)`;
- the `situation` detail mapped as usual (counts, possession, `lastPlay`).

**Why the mapper.** It is the one place both styles read:
- the NFL Pill sees "End 1st" for the whole change of ends, exactly as it does for `STATUS_END_PERIOD`;
- the Stacked bug shows "END OF 1ST" / "END OF 3RD", the folded clock and the break's YDS rule;
- the Sports tab cards read the same mapping.

**When it ends.** At the first new `lastPlay` (any type other than 2), which turns the body back into LIVE. Live2's
first Q2 snap (21:01:23) kept the clock at 15:00, so the play, not the clock, ends it. A clock below 900 also ends it, as
a guard against a play that ESPN fails to log.

**Not affected:**
- halftime (`STATUS_HALFTIME`, period 2) and the start of Q3 (period 3);
- overtime (period ≥ 5).

The live data has not shown this pattern for any of them (live2 ends in Q2). If it does, the period set grows by one
ruling.

**Residual.** The machine polls an INTERMISSION at the break cadence (`breakMs`, `ScoreBugMachine.kt:529`: 2 × the
interval, at least 20 s). So the first play of the new quarter shows up to one break-cadence poll late. That is already
true for every break today.

**Golden hashes.** The screenshots never go through the mapper with this shape, so all 1 666 lines are unchanged.

**Fixtures (built in the foundation, trimmed from live2 `sb-*.json`, `core/src/test/resources/sports/nfl/`):**
- `scoreboard-live-end-period-2026-11-16.json` (20:58:13);
- `scoreboard-live-change-of-ends-2026-11-16.json` (20:58:23: IN_PROGRESS, P2 15:00, type 2);
- `scoreboard-live-first-play-after-change-of-ends-2026-11-16.json` (21:01:23: type 5, still 15:00).

---

## 2. When the column shows (`StackedNflTargets.ydsOpen`; WP-2)

`known = n?.awayYards != null && n.homeYards != null`. In `StackedNflTargets.of` (`StackedNflLayout.kt:111-171`):

| Row of `of` (RC4 precedence) | `ydsOpen` | Why |
|---|---|---|
| Pinned score (the runner's) | the pre-score value, held (§3.6) | ST S1 rule 6 / B4 |
| Stale / waiting **in play** (bar closed, "No update" / "Delayed" in the clock slot) | false | play is not stopped as far as the view knows |
| Stale / waiting **in a break or at Final** (folded, NO UPDATE / DELAYED bar) | `known` | NHL SOG rule (ST §8; review N6): the break look is held, with the values of the view's own snapshot |
| FINAL, HALFTIME, END_PERIOD (incl. the S1 shape) | `known` | mock (`half`), SR4a analogy |
| Flag | false | never on the flag bar |
| Timeout mark (confirmed or fallback) | `known` | the mock's `timeout`; a mark meets yards only on a stoppage snapshot |
| Play, PAT, kick-off (the bar as today) | `known` | S2: an official timeout or the two-minute warning; also a team timeout the machine does not mark (first view, both counts dropped) |
| No `situation` (bar closed) | `known` (always false in practice: no `lastPlay`, no key) | |

`folded(bar)` passes `ydsOpen = known`. `open(…)` gains `yds: Boolean`, true except on the flag row.

- **With S2 the bar is exactly today's.** An official timeout keeps its play / PAT / kick-off bar ("1ST & 10 | BAL 35",
  "LV KICKOFF | LV 35"). There is no TIMEOUT bar, no pip burn, and no RED ZONE or flag change. The YDS column opens
  beside it, and since yards are not in `NflBarKey` the bar does not REKEY. After a score the 74 snapshot (kick-off
  pending, NFL Stacked §3.1's RC9 fixture) shows the kick-off bar and the column. The score's takeover still wins: it
  pins the pre-score layout, and the column opens at the release.
- **Late yards** start the YDS OPEN alone. A refresh redraws the digits without a transition. The column is **never
  dimmed** (SOG precedent).

---

## 3. Geometry and motion

### 3.1 The column (Appendix A against the mock, `design/nfl-stacked/StackedNFL.dc.html:18-21, 126-134`)

| Element | Mock | Standard | Check |
|---|---|---|---|
| Width | `.yds.on{width:140px}` | **63** (`STACKED_NFL_YDS_W`) | ✓ |
| Background | `#15191f` | the box fill `STACKED_COL_BG` | ✓ |
| Inset line | `.yds-in` `inset 1px 0 0 #2b3240` | 1 px `STACKED_INSET_LINE`, **rows-side** edge | ✓ |
| Digits | 48 / 700 tabular, centred per 120 row | **21.6** Instrument Sans Bold `tnum`, white, centre y 27 / 81 by the "0" bounds (`StackedPainter.kt:170-171`) | Appendix A's "312 40.2" → **38.9** (tabular 0.600 em) |
| Label | "YDS" 18 / 700, .08 em, `#c3c7d0`, box 108 + 24 | **18** (floor), .08 em, `STACKED_SOG_LABEL`, centre y 54 by the "S" bounds | ✓ (the NHL SOG label too) |
| Rules beside the label | 2 px `#5b6170` | **dropped** | label ≈ 42 of 63 (NHL precedent) |
| Open | width .45 s Std; contents α .3 s / x −12 → 0 .35 s after .3 | 450 Std; α 300 / dx −5.4 → 0 350 ease after 300 | ✓ |
| Close | contents .2 s; width .45 s Std after **.2** | 200 ease; 450 Std after **200** | ✓ |
| Reduced motion | `.01s` | snap | ✓ |

Clearances, Standard: the digits' ink spans 19.4–34.6 and 73.4–88.6, the label's 47.7–60.3, so they are ≥ 12.7 apart.

### 3.2 Widths, footprint, placement (foundation: placement **built**)

| State | Standard | Large |
|---|---|---|
| In-play stoppage with yards (team or official timeout, two-minute warning) | **396** | 495 |
| Play, flag, the same without yards, stale / waiting in play | 333 | 416.25 |
| Break / Final with yards (and stale / waiting there) | **306** | 382.5 |
| The same without yards | 243 | 303.75 |

**Constants:**
- `STACKED_NFL_W_MAX` keeps **333** ("the play box"). `StackedNflGeometry.arcK` (`StackedNflLayout.kt:424`),
  `StackedNflPinSchedule.label` (`StackedNflBug.kt:617-618`) and the existing shot layers
  (`StackedNflScreenshotTest.kt:90, 231, 441-448`) read it.
- `STACKED_NFL_FOOTPRINT_W = W_MAX + STACKED_NFL_YDS_W` (396).
- `ScoreBugPlacement.nflStackedWidth` uses the footprint while `STACKED_NFL_YDS_ENABLED` (now **true**).

| Corner | Standard footprint | Large footprint | Rows (unchanged) | Bar |
|---|---|---|---|---|
| Top right | (1428, 56)–(1824, 197) | (1329, 56)–(1824, 232.25) | 1581–1824 (Large 1520.25–1824), mirrored | below |
| Top left | (96, 56)–(492, 197) | (96, 56)–(591, 232.25) | 96–339 (Large 96–399.75) | below |
| Bottom right | (1428, 883)–(1824, 1024) | (1329, 847.75)–(1824, 1024) | mirrored | above |
| Bottom left | (96, 883)–(492, 1024) | (96, 847.75)–(591, 1024) | 96–339 | above |

**Placement:**
- **The rows never move.** In the top right, the drawn box spans 1428 / 1491 / 1518 / 1581 → 1824. A bottom corner's
  bar opens above the full box width, 396 included. Left corners grow to the right.
- **The SB §4.4 rules run on the wider footprint.** The probing line clears both top footprints (Large 591 < 600 and
  1329 > 1320, a 9 px margin; N3). The halo (M 74 / 76): top right (1354, −18)–(1898, 271), Large (1253, −20)–(1900,
  308.25).
- **The layer** is **544 × 289** / **647 × 328.25**. The runner sizes it from `ScoreBugPlacement.width`
  (`StackedNflBug.kt:189`); `stackedOriginDx` cancels the footprint width, so nothing moves at rest (N3). The glow masks
  at the widest box are ≈ 157 KB at 1080p each.
- **Place mode** is unchanged: the sample has no yards, so the box is the 333 one.

### 3.3 Mirror

- **Order from the screen edge inwards:** `[rows][YDS][clock]` on the left, `[clock][YDS][rows]` on the right. The
  column next to the rows is the YDS column on both sides, as ST §4.3 item 1 has the SOG column.
- **Positions** reflect within the box (`boxX`). At 396, mirrored, the clock is at 0–90, YDS at 90–153 and the rows at
  153–396.
- **Contents** anchor to the rows-side edge, revealed by a `clipRect` of the open width. On the right that slide is
  mirrored (+5.4), and the inset line sits on the column's rows-side edge.
- **With S2** the bar keeps its rules at 396: the stripes anchor to the bar's outer end, and the words are left-aligned
  63 in, or right-aligned when mirrored. In a left corner the red-zone patch runs from the divider to the bar's end; in a
  right corner it stops one text gap after the sub (integration ruling 4). Room at 396 is 329 against the worst 248
  (§2.4 of NFL Stacked). A kick-off is never RED ZONE (RC9).

### 3.4 Digits

- The values are −99..999, so at most three glyphs. `NflYards.text` (built) gives "−2" with U+2212 (in
  `instrument_sans_bold.ttf`, 0.551 em) and ASCII digits.
- Widths at 21.6 (tabular 0.600 em): "888" 38.9, "−99" 37.8, "−2" 24.9, "0" 13.0. The budget is 63 − 2 × 6 = **51**:
  everything fits, so there is no step-down rule. `StackedNflFitTest` measures the real `Paint` at both sizes.

### 3.5 The YDS track (`StackedNflTimeline`; **built in the foundation**, N4)

`StackedNflTrack.YDS` is appended (ordinal 8). `StackedNflFrame` gains `ydsWidth` / `ydsAlpha` / `ydsDx` (rest 0 / 0 /
−5.4), `copyFrom`, and `boxW = 243 + ydsWidth × 63 + clockWidth × 90`.

| Transition | Channel | → | Duration | Delay | Easing |
|---|---|---|---|---|---|
| OPEN / REKEY | `ydsWidth` | 1 | 450 | 0 | Std |
| | `ydsAlpha` | 1 | 300 | 300 | ease |
| | `ydsDx` | 0 | 350 | 300 | ease |
| CLOSE | `ydsAlpha` | 0 | 200 | 0 | ease |
| | `ydsDx` | −5.4 | 200 | 0 | ease |
| | `ydsWidth` | 0 | 450 | 200 | Std |
| STILL / BURN | all | `to` | snap | | |

`totalMs` is 650 both ways. `samplePinned` snaps the width (as CLOCK). The track keeps the timeline's allocation-free and
interruption rules.

Sequences (Standard, left corner):
- **into a stoppage:** 333 → 396 (width 0–450, contents 300–650);
- **out of it:** 396 → 333 (contents 0–200, width 200–650);
- **into halftime:** the clock folds and YDS opens together, 333 → 306;
- **halftime → kick-off:** YDS closes over 200–650 and the clock opens over 350–800.

### 3.6 Takeover interplay (S9)

- **The pin holds the pre-score layout.** A score arrives with a new `lastPlay`, so a team timeout ends in the same
  view. The pin holds the pre-score layout, and the YDS track is pinned like CLOCK. The takeover is laid out on 396
  (the common case: a field goal after an "icing" timeout) or on 306 (a score correction in a break). The column
  closes at the release.
- **Fit.** `fitTakeover` and `postsShift` take any width ≥ 243. `arcK = boxW / 333` (1.189 at 396) moves the posts about
  44 px right before `postsShift` pulls them back off the block. The golden `yds-fg-1500-pinned-396` shows this (S9).
  `StackedNflPinSchedule.label` keeps the word-alone label below 333, so 306 gets it and 396 gets the full label.
- **The takeover covers the YDS column**, and the border stays on top.

### 3.7 Painter (`StackedNflPainter`; WP-2)

- **`frame()`** (`:333-366`) fills `awayYds` / `homeYds` with `NflYards.text`. The foundation already added both fields
  to `StackedNflFrameState`, default "". The label comes from `sports_stacked_yds` once per frame (as `sogLabel`,
  `StackedPainter.kt:161-162, 294`).
- **Paints** are made in the constructor: the digits are `STACKED_NFL_YDS_DIGITS_PX` bold `tnum` centred; the label is
  `STACKED_NFL_YDS_LABEL_PX` with `STACKED_NFL_YDS_LABEL_TRACK_EM`. The offsets come from the bounds once.
- **`drawFrame`** (`:497-539`) gains step **5b**, the YDS column, when `f.ydsWidth > 0`. It is `StackedPainter.drawSog`
  (`:625-644`) with NFL names:
  - `open = f.ydsWidth × ydsW`;
  - `x = if (mirrored) −ydsW − f.ydsDx·s else rowsW + f.ydsDx·s`;
  - clip `[if (mirrored) −open else rowsW, +open]`;
  - the inset line on the rows-side edge;
  - alpha `f.ydsAlpha`, never dimmed.
- **`drawClockColumn`** (`:808-843`) shifts by `yo = f.ydsWidth × ydsW`:
  - `x0 = if (mirrored) −yo − clockW else rowsW + yo`;
  - `cl = if (mirrored) −yo − open else x0`;
  - this is `StackedPainter.kt:651-654`'s form, and at `yo = 0` the pixels are identical.
- **`StackedNflGeometry`** gains:
  - `ydsX(boxW, ydsW, mirrored)`;
  - `clockX(boxW, clockW, ydsW, mirrored)`;
  - `clockContentX(boxW, ydsW, mirrored)`;
  - the old forms delegate with `ydsW = 0`.
- **No allocation in draw.**

### 3.8 Runner (`StackedNflBug.kt`; WP-2)

- **The YDS track.** `StackedNflRun` gains `yds = NflTrackRun(StackedNflTrack.YDS)`, appended to `tracks` (index =
  ordinal 8) and to `layout`. `step()` adds `evalBool(yds, tg.ydsOpen, snap)`, and `sampleTrack`'s `else` branch already
  samples it.
- **Rest width.** A new `nflRestBoxW(ydsOpen, clockOpen)`; the old `nflRestBoxW(clockOpen)` stays
  (`StackedNflScreenshotTest.kt:328`). `restW` (`:479`), `pinBoxW` (`:1009`) and `atRest()` (`:1264`) use `yds.want`.
- **`NflBodyMerge` (S6, `:1319-1340`).**
  - The clock merge copies `awayYds` / `homeYds` **from the current body**, not from the clock's last body.
  - A YDS merge is added. While `!body.targets.ydsOpen && f.ydsWidth > 0`, the digits come from `lastYds`, the last
    body that had yards.
  - The two compose, each from its own last body.
  - The output is cached per input triple, so nothing is allocated per frame.
- **`StackedNflDescribe`** (`:1342-1351`) appends `sports_desc_nfl_yards` while `t.ydsOpen` and no score mark is set.
  The live region is unchanged.

### 3.9 Strings (**built**)

- `sports_stacked_yds` "YDS";
- `sports_desc_nfl_yards` "Total yards: %1$s %2$s, %3$s %4$s".

Both stay unused until WP-2; lint's `UnusedResources` should be run after WP-2.

---

## 4. Tests and gates

### 4.1 `:core`

- **`StackedNflYdsFoundationTest`** (foundation, **built**): the §3.5 table, `boxW` and `copyFrom`; `text` under an
  Arabic locale; a view without yards never opens the column; the extras default to off.
- **`EspnMapperChangeOfEndsTest`** (WP-1, new; §1.12):
  - the three 2026-11-16 fixtures map to INTERMISSION / END_PERIOD P1 (0:00), INTERMISSION / END_PERIOD P1, and LIVE
    P2 with type 5;
  - a period-3 body, a period-5 body, a body below 900 and `STATUS_HALFTIME` stay as today;
  - every existing `EspnMapper*` test is unedited.
- **`NflYardsTest`** (WP-1, new):
  - `stoppageKey` for the 21 / held / after fixtures, the official-timeout fixtures (`"O…"`), the RC9 kick-off fixture
    (`"O…"`), the end-period and change-of-ends fixtures (`"E1"`), a halftime built in the test (`"H2"`), a final
    (`"F"`), a 75 body, a type-8 body (null) and NHL (null);
  - `teamIds`;
  - `totalYards`: the final fixture → 123, negative → −2, no-total → null, `displayValue` "1,234" / "x" with no
    `value` → null, 1 000 → null.
- **`NflYardsPlannerTest`** (WP-1, new):
  - the first poll is due and `attach` carries the values on the same poll;
  - the refresh is due at +30 000 and not at +29 999 (O, E and H keys);
  - the refresh is not attached at `pendingAt + 19 999`, and is attached at `+20 000` while still in the key;
  - a `"T"` key: due at +12 000 and not at +11 999; not attached at `pendingAt + 4 999`, attached at `+5 000` (I1);
  - both delays count from the polls, not the completions (I2), with poll-grid cases: a 50 s team timeout on a 10 s
    and a 5 s grid whose yards change 15 s in shows the corrected figure before it ends;
  - a key change drops the pending refresh;
  - a retryable failure is due again at +15 000;
  - a timeout or IO failure is never due again in that key;
  - two timeout / IO failures in a row block for 600 000, while a success between them resets the count;
  - a 429 blocks for 600 000;
  - at most 3 attempts and 2 successes;
  - `"F"` gets no refresh.
- **`ScoreBugMachineNflYardsTest`** (WP-1, new):
  - shown entries carry the yards, both or neither;
  - with Delay 30 s the yards appear in the same view as that snapshot's score;
  - a waiting view equals the score's behaviour (S7);
  - a stale break keeps them; the next play has none;
  - the change-of-ends sequence (end-period → change-of-ends → first play) gives the Pill the same views as two
    `STATUS_END_PERIOD` bodies followed by the play.
- **`StackedNflLayoutTest`** (WP-2, additions): every §2 row.
- **`StackedNflGeometryTest`** (WP-2, additions): `ydsX` / `clockX` / `clockContentX` at 396 and 306 in both mirror
  states; the old forms unchanged.
- **Foundation edits (built):**
  - `NflStackedPlacementTest`: §3.2 numbers and the four box widths;
  - `ScoreBugPlacementTest.pillAndNflIgnoreTheStyle`: the one line;
  - `StackedNflTimelineTest`: an all-open rest is now 396 wide; a default frame is still 333.

### 4.2 `:data` (WP-1)

- **`EspnYardsTest`** (new, `mockwebserver3`):
  - the path for both teams; the two calls overlap;
  - no request when the extras are off, outside a stoppage, or without ids;
  - the snapshot carries the yards; `wireBytes` is the board's only and `sideMs > 0`;
  - 404 JSON / HTML, 500 and a bad body → no yards, retried at 15 s;
  - a stall of 3 s → no yards within ≈ 2.5 s, and no retry in that stoppage; two stalls in a row → blocked;
  - 429 and 403 → no stats request for 10 min, with the scoreboard polled and the follow not slowed;
  - **a body that makes the mapper throw** (S5) → the board's `Ok`, with no yards;
  - `fromOverride` maps `statsBase`; the logs carry no ids.
- **`ScoreRepositoryYardsTest`** (new): `nflYards` only for NFL + STACKED + SEEN + `FULL_WITH_FOLLOW`; the work sample
  excludes `sideMs`.

### 4.3 `:ui` (WP-2)

- **`StackedNflScreenshotTest`**: §4.6's 26 shots. Their layer and mirrored origin use `STACKED_NFL_FOOTPRINT_W`, and
  the existing looks' code is untouched (N2).
- **`StackedNflFitTest`**: §3.4; the label; the clearances; the takeover and goalpost loops at 306 and 396.
- **`StackedNflDrawTest`:**
  - the YDS track kicks only on `ydsOpen`;
  - late yards open without a bar REKEY; an official-timeout view (play bar) opens with no REKEY and no burn;
  - a refresh redraws without a kick; closing keeps the last digits;
  - **play → halftime with yards, sampled at 350 ms, draws the digits** (S6);
  - a field goal after a timeout stays pinned at 396 and closes YDS at the release;
  - the first view at halftime snaps open; Describe.
- **`StackedNflEndToEndTest`** (one case): a type-21 fixture with yards through the real machine and runner gives 396,
  then the next play gives 333 after 650 ms. Its layer uses the footprint constant (N2: the existing `:229-233` stay).
- **`StackedNflPainterAllocationTest`**: frames with the column open, mid-open and closing.
- **Run by the foundation, unedited and green:** `StackedNflBoundsTest`, `StackedNflDrawTest`, `StackedNflEndToEndTest`
  and `StackedNflSeamTest` with the 396 footprint.

### 4.4 Fixtures (`core/src/test/resources/sports/nfl/`; public, no personal data; **built in the foundation**)

- `stats-team-final-2026-11-15.json`: `corestats.json` with every `athletes` array emptied, compact, ≈ 66 KB.
- `stats-team-negative-synthetic.json`: `totalYards` −2.0 / "-2".
- `stats-team-no-total-synthetic.json`: no `totalYards`.
- The five 2026-11-16 scoreboard fixtures, trimmed from `live2` to the 2026-11-15 fixtures' key sets as
  `{"events":[…]}`:
  - the three of §1.12;
  - `scoreboard-live-official-timeout-2026-11-16.json` (20:42:32, type 74 "Official Timeout at 04:39.");
  - `scoreboard-live-official-timeout-held-2026-11-16.json` (20:42:42, the same 74).
- WP-1 adds their names to a new testFixtures object `NflYardsFixtures`; `NflStackedFixtures` is unedited.

### 4.5 Fake panel (`tools/fakepanel/sports_nfl.py`; WP-3)

- **The statistics route:** `GET /v2/sports/football/leagues/nfl/events/{eid}/competitions/{eid}/competitors/{tid}/statistics`.
  - `STATS_PREFIX` is added to `is_nfl_path`. `fakepanel.py:1412` already dispatches there, so it is not edited.
  - It answers 404 JSON `{"error": {"code": 404, "message": "no instance found"}}` for an unknown event, a team not in
    it, `pre`, `gone`, or a null scripted value.
  - Otherwise it answers 200, `Cache-Control: max-age=10`, in ESPN's shape: `$ref`, `competition`, `team`, and `splits`
    with `general` (13 filler stats, no total), then `passing`, `rushing` and `receiving`, each with `totalYards` (float
    `value`, string `displayValue`) among filler stats and `athletes: []`. The body is ≈ 40 KB raw.
  - The global `status`, `stall_*` and `pad_bytes` faults apply to it, and every request is noted in `sports.log`.
- **Yards per window:**
  - `STATE_KEYS` += `awayYards`, `homeYards` (−99..999, or null for a 404). `_carry_yards()` carries them like
    `_carry_timeouts` (`sports_nfl.py:225-236`), starting at 0 / 0.
  - The defaults (KC / BUF) are: "q1 3rd and 2, OPP 38" 41 / 0; "q1 BUF 2nd and 7" 75 / **−2**; "q2 field goal, dead
    ball" 75 / 48; "q2 BUF ball" 142 / 51; "q3 KC ball" 251 / 187; "end 3rd" 268 / 187; "end 4th" 355 / 312; "ot KC 1st
    and goal at 8" 381 / 312; the post windows 389 / 312. Every other window carries the last value.
- **The S1 shape:** "end 1st" and "end 3rd" become 10 s `STATUS_END_PERIOD` windows, each followed by a 15 s window:
  - the new windows are "q2 change of ends" and "q4 change of ends";
  - each is `STATUS_IN_PROGRESS`, in the next period, with the clock frozen at 15:00;
  - each has `lastPlay` type 2 "END QUARTER n" and the situation kept;
  - the timeline stays 985 s.
- **Faults** (`NFL_FAULT_KEYS` +=): `stats_status` (the stats route only) and `stats_stall_ms` (the stats route only).
  There is no `stats_lag_s` (N7).
- **Jump:** `POST /admin/sports-nfl {"jump": "<window label>"}` re-anchors so that window began 1 s ago at the current
  scale. An unknown label gets a 400 that lists the labels; `GET` adds `"windows"`. Jumps are **forward only** in the
  checklist (S8).
- **`test_sports_nfl.py`** covers the route, the 404 cases, the body's shape, the carried defaults, the S1 windows, the
  faults, the jump and the rejections. `README.md` gets the route, the keys and the jump.

### 4.6 Golden hashes (`ui/src/test/resources/golden/sports-hashes.txt`)

All **1 666** existing entries stay unchanged. **26 new** entries go under `score-bug-stacked-nfl/`, appended by the lead
after the owner approves the shots:

| Shots | Looks | Corners × sizes | n |
|---|---|---|---|
| Rest looks | `yds-timeout` (TIMEOUT BUF \| 2 LEFT, 142 / 51), `yds-official-timeout` (PLAY bar "1ST & 10 \| BUF 25" with YDS), `yds-halftime`, `yds-end-1st-negative` (75 / −2), `yds-final-3-digit` (389 / 312), `yds-stale-halftime` | TL, BR × Std | 12 |
| Large | `yds-timeout`, `yds-halftime` | TL, BR × Large | 4 |
| Other corners | `yds-timeout` | TR, BL × Std | 2 |
| Motion | `yds-open-t225`, `yds-close-t425` | TL, BR × Std | 4 |
| Takeover (S9) | `yds-fg-1500-pinned-396` (FIELD GOAL after a timeout) | TL, BR × Std; TL × Large | 3 |
| Still | `yds-timeout-still` | TL × Std | 1 |

### 4.7 Emulator checklist (one forward pass, ≤ 20 min; S8)

**Setup** (`TheBox_TV_API30`, 2 min):
- run `fakepanel.py --port 8080 --sports --sports-nfl`;
- write `files/debug/sports_base` = `http://10.0.2.2:8080`;
- `POST /admin/config {"sports_nfl_timeline_s": 2955}` (×3);
- follow KC @ BUF with Style Stacked, the player full screen, top right, Delay 30 s.

Jump only **forward**. If a step must repeat, restart the panel timeline (`{"restart": true}`) and the app, then follow
again.

1. **Delay and S1.** `{"jump": "q1 BUF 2nd and 7"}`, then wait:
   - "END OF 1ST" with YDS 75 / "−2" (real minus) appears 30 s after `sports.log` shows the stats requests;
   - it stays through "q2 change of ends", with the clock folded;
   - the Pill check comes later. Set Delay back to 0.
2. **Pill and S2.** Style Pill, then `{"jump": "q2 BUF kick-off"}` (type 74):
   - no stats request in the log;
   - switch back to Stacked within the window: the stats requests come on the next poll, and "BUF KICKOFF | BUF 35"
     keeps its bar while YDS opens (396).
3. **Team timeout.** `{"jump": "q2 BUF ball"}`:
   - the TIMEOUT BUF bar, the pip burn and YDS 142 / 51;
   - two stats requests, then two more on the first poll ≥ 12 s later (I1), and nothing after that.
4. **Halftime.** `{"jump": "halftime"}`:
   - the clock folds and YDS stays: a 306 box, ordered clock · YDS · rows from the edge;
   - switch the corner to TL, Size Large, then a bottom corner with subtitles on, one look each. Restore top right
     Standard.
5. **End of 3rd.** `{"jump": "end 3rd"}`: 268 / 187, held through "q4 change of ends", with the refresh in the log.
6. **Faults.**
   - `stats_status: 404`, then `{"jump": "q4 KC 3rd and 9"}`: the column stays closed, with a second pair of requests
     ≈ 15 s later. Clear the fault.
   - `stats_stall_ms: 4000`, then `{"jump": "end 4th"}`: closed, one pair of requests only, and the END OF 4TH bar on
     time. Clear the fault.
7. **429.** `stats_status: 429`, then `{"jump": "ot KC 1st and goal at 8"}`: closed, and no further stats requests.
8. **Final.** `{"jump": "ot walk-off touchdown (post)"}`: the takeover, then FINAL with the column closed (blocked). Clear
   the fault.

**Device-only:** the 21.6-px digits at 3 m on 720p; TalkBack "Total yards".

### 4.8 Gate

Each WP runs:
- the compile tasks and its own test classes;
- `python3 -m unittest tools/fakepanel/test_sports_nfl.py` (WP-3).

The lead's integration runs `./gradlew :core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug
lintAll :ui:sportsHashGate`, then the review and the fix pass.

---

## 5. Work packages

### 5.0 Foundation (lead; **built in the worktree, uncommitted**)

| File | Change |
|---|---|
| `core/.../sports/SportsContracts.kt` | §1.4 contracts |
| `core/.../sports/SportsConstants.kt` | `STACKED_NFL_YDS_ENABLED = true`; the `STACKED_NFL_W_MAX` KDoc; `STACKED_NFL_YDS_W`, `STACKED_NFL_FOOTPRINT_W`, `_DIGITS_PX`, `_LABEL_PX`, `_LABEL_TRACK_EM`, `_MS`, `_CLOSE_DELAY_MS`, `_IN_ALPHA_MS`, `_IN_MOVE_MS`, `_IN_DELAY_MS`, `_OUT_MS`, `_DX`; `NFL_YARDS_MIN/MAX`; `NFL_TYPE_OFFICIAL_TIMEOUT` "74", `NFL_TYPE_TWO_MINUTE_WARNING` "75", `NFL_TYPE_END_PERIOD` "2"; `NFL_YDS_CALL_TIMEOUT_MS` 2 500, `_REFRESH_MS` 30 000, `_CONFIRM_MS` 20 000, `_RETRY_MS` 15 000, `_ATTEMPTS_MAX` 3, `_SUCCESSES_MAX` 2, `_BLOCK_MS` 600 000, `NFL_YDS_STALLS_TO_BLOCK` 2 |
| `core/.../sports/EspnDto.kt` | the four statistics DTO classes |
| `core/.../sports/NflYards.kt` (new) | §1.4: `text` real; the rest stubs |
| `core/.../sports/StackedNflLayout.kt` | `StackedNflTargets.ydsOpen = false` (trailing); `of` unchanged |
| `core/.../sports/StackedNflTimeline.kt` | `YDS` and its full track (§3.5) |
| `core/.../sports/ScoreBugPlacement.kt` | the footprint (§3.2) |
| `ui/.../sports/StackedNflContracts.kt` | `StackedNflFrameState.awayYds/homeYds = ""` |
| `ui/src/main/res/values/strings_sports.xml` | §3.9 |
| tests | `StackedNflYdsFoundationTest` (new); `NflStackedPlacementTest`; one line each of `ScoreBugPlacementTest` and `StackedNflTimelineTest` |
| fixtures | §4.4 (eight files) |

`StackedNflBug.kt` needed no change (its `when (t.id)` has an `else`).

**With the stubs the app behaves as today.** No stoppage key means no fetch, no yards and the column never opens. The
footprint is 63 px wider, so the rows hug the same edge, nothing moves, and overlay hides use the wider footprint.

### 5.1 WP-1 data, mapper and machine (Opus)

- **Files (only these):**
  - `core/.../sports/EspnMapper.kt` (§1.12);
  - `core/.../sports/NflYards.kt` (bodies: `stoppageKey`, `teamIds`, `totalYards`, the planner; `text` unchanged);
  - `core/.../sports/ScoreBugMachine.kt` (§1.11);
  - `data/.../sports/EspnScoreSource.kt` (§1.7: `statsBase`, `fromOverride`, the extras overload, team ids, the
    planner per follow, the stats client and calls, the catch-all, `sideMs`);
  - `data/.../sports/ScoreRepositoryImpl.kt` (§1.9 extras; §1.10 work sample);
  - `core/src/testFixtures/.../sports/NflYardsFixtures.kt` (new).
- **Tests:** `EspnMapperChangeOfEndsTest`, `NflYardsTest`, `NflYardsPlannerTest`, `ScoreBugMachineNflYardsTest`,
  `EspnYardsTest`, `ScoreRepositoryYardsTest`.
- **Relies on:** the §1.4 contracts, the `NFL_YDS_*` / `NFL_TYPE_*` constants, and the §4.4 fixtures.

### 5.2 WP-2 drawing (Opus; Sonnet may build the screenshot looks under this spec)

- **Files (only these):**
  - `core/.../sports/StackedNflLayout.kt` (`of` per §2, and `StackedNflGeometry` per §3.7);
  - `ui/.../sports/StackedNflPainter.kt`;
  - `ui/.../sports/StackedNflBug.kt`.
- **Tests:** `StackedNflLayoutTest`, `StackedNflGeometryTest`, `StackedNflScreenshotTest` (additions only),
  `StackedNflFitTest`, `StackedNflDrawTest`, `StackedNflEndToEndTest`, `StackedNflPainterAllocationTest`, and
  `StackedNflTestKit` / `StackedNflTestSupport`.
- **Relies on:** `NflView.awayYards/homeYards`, `StackedNflTargets.ydsOpen`, the YDS track and frame fields,
  `StackedNflFrameState.awayYds/homeYds`, `NflYards.text`, the `STACKED_NFL_YDS_*` / `FOOTPRINT_W` constants, and the
  strings. It builds views with yards by hand, so it needs nothing from WP-1. It never edits `StackedNflTimeline.kt`.

### 5.3 WP-3 fake panel (Sonnet)

- **Files:** `tools/fakepanel/sports_nfl.py`, `tools/fakepanel/test_sports_nfl.py`, `tools/fakepanel/README.md` (§4.5).
- **Relies on:** the URL path in §1.7 and the fixture shape of `stats-team-final-2026-11-15.json`.

No file is in two packages. `SportsConstants.kt` and `SportsContracts.kt` are the foundation's: an extra constant goes
through the lead.

**Integration (lead):** §4.8, the shots to the owner, append the 26 hashes, §4.7, the review and fix pass.

---

## 6. Amendments to `docs/design/m6-nfl-stacked.md` (**applied** 2026-11-16)

Applied in place, marked "(YDS 2026-11-16)":
- the status header;
- lead sign-off item 6;
- §0 items 3 and 4;
- §2.1, the §2.3 YDS row, and the §2.7 `timeout` / `half` rows;
- the §3.1 "Team yards" row and the new end-of-quarter shape row;
- §3.2 and §3.4 (a YDS note under the table);
- §4.1 (the YDS track) and §4.5 (the layer);
- §5.1 (widths, corners, halo) and §5.3 (the YDS row);
- §8.5;
- §10 S-D1, §11, §12 and §13 Q2;
- the Appendix A heading and corrections.

## 7. Risks

- **The undocumented host.** Failure is contained: the column stays closed, yards are blocked for 10 min on 429 / 403
  or two stalls, the scoreboard is untouched, and a crash in the step is caught (S5).
- **The first-fetch spoiler residual** (§1.8), and **inline latency** at Delay 0 (≤ 2.5 s, S4).
- **Unproven feed states:**
  - ~~freshness at a team timeout~~ (measured, I1); ~~halftime and the start of Q3~~ (measured, I3);
  - whether OT has a change-of-ends shape (§1.12 handles periods 2 and 4 only; halftime and Q3 have none, I3).
- **S1 changes a shipped bar.** The END OF 1ST / 3RD look now lasts the 3-min change of ends instead of one poll, on
  both styles. The first play after it shows up to one break-cadence poll late. It is a lead default, pending the owner.
- **S2 adds fetches** (≈ 0.27 MB/h with yards on) and a 396 box at every TV timeout. It is a lead default, pending the
  owner.
- **The wider footprint** hides the bug behind overlays slightly more often (63 px, within the NHL Stacked size).

## 8. Owner questions

Answered: **a separate switch for the yards column: no** (owner, 2026-11-16).

Remaining. Both are lead defaults, built unless the owner objects:
1. **(S1) The end of the 1st and 3rd quarters.** ESPN shows "End of 1st" for about 10 s, then about 3 min of "2nd
   quarter, 15:00" before the first snap. *Default: treat those 3 minutes as the end-of-quarter break, so both bug
   styles keep "END OF 1ST" and the yards stay up until the first play of the new quarter.*
2. **(S2) Yards during TV timeouts and the two-minute warning** (2.5–3-min stoppages, about 15 a game; the mock-up
   shows yards only at team timeouts and halftime). *Default: yes, the column opens beside the normal down bar; the
   bar itself is unchanged.*

## 9. Deviations from the mock (recorded)

| ID | Mock | Here | Why |
|---|---|---|---|
| Y-D1 | label with 2-px rules | rules dropped | no room (NHL SOG precedent) |
| Y-D2 | label 18 mock px (8.1) | 18 design px | information-text floor (ST S5) |
| Y-D3 | opens at a timeout and at halftime | also END OF nTH, FINAL, official timeouts and the two-minute warning; held in a stale / waiting break | SR4a analogy, S1, S2 (lead defaults), NHL SOG stale rule |
| Y-D4 | opens on every timeout | when ESPN's yards are known (both teams) | R21 |
| Y-D5 | "-" | U+2212 "−" | typography; in the bundled font |

---

## Review rulings (lead, 2026-11-16)

| Finding | Ruling | Change in this document |
|---|---|---|
| **B1** refresh and first-fetch spoilers | accepted | §1.6 `attach` uses `NFL_YDS_CONFIRM_MS` 20 000; §1.8 states the first-fetch residual; "spoiler-proof by construction" dropped (§0.3) |
| **S1** END OF nTH is one sample | lead default (b), pending the owner | §1.12 mapper rule (periods 2 / 4, `lastPlay` type 2, clock ≥ 900) to END_PERIOD of the previous period; three live2 fixtures; fake-panel S1 windows; §8 Q1 |
| **S2** TV timeouts never show yards | lead default: also 74 and 75, pending the owner | `"O"` key (§1.5); §2 opens beside the unchanged bar; §3.3 geometry; cost §1.10; §8 Q2 |
| **S3** hand-written scanner | accepted | kotlinx DTO through `call()` / `classify()`; first `totalYards`; scanner, depth constant and stats-before-name fixture removed (§1.4, §1.7, §4.4) |
| **S4** inline budget | accepted | 2.5 s; no retry after a timeout / IO failure; two in a row block 10 min; the Delay-anchoring fact in §1.8 |
| **S5** a throwable kills the loop | accepted | catch-all in the step, planner outside `async`, test (§1.7, §4.2) |
| **S6** merge blanks the digits | accepted | `NflBodyMerge` carries the yards; composing merges; test at 350 ms (§3.8, §4.3) |
| **S7** the waiting view | accepted | §1.8 wording; §4.1 test |
| **S8** checklist will not run | accepted | one forward order, 429 last (§4.7) |
| **S9** takeover shot at 306 | accepted | `yds-fg-1500-pinned-396` (§4.6); 306 kept in the fit loops |
| **S10** FINAL gets one fetch | accepted | §1.5, §1.6 (no `"F"` refresh), §7 |
| N1 field choice | accepted | §0.1, §1.7 |
| N2 golden claim | noted | §4.3: new shots and the e2e case use `STACKED_NFL_FOOTPRINT_W` |
| N4 runner-assumption test | accepted | the YDS track is built in the foundation with real lengths (`StackedNflRunnerAssumptionsTest` green) |
| N5 `text` in the foundation | accepted | built |
| N7 scope cuts | accepted | single current key; 26 goldens; no `stats_lag_s`; no `EST_NFL_YARDS_MB_H` (figure unchanged); `Fetch.Ok.sideMs` kept |
| N9 owner questions | accepted | the switch question answered (no switch); S1 and S2 added |
| N10 user agent | checked by the lead | HTTP 200 with `okhttp/4.12.0` and a plain agent (§1.1) |

## Integration rulings (lead, 2026-11-16)

From live2's later rows (`scratchpad/yds/live2/log.tsv`, 21:25–22:00:25; §1.1) and the code review
(`docs/review/2026-11-16-nfl-stacked-yds-code-review.md`).

| ID | Ruling | Change |
|---|---|---|
| **I1** | Team timeouts refresh sooner. In three of five team timeouts (21:25:33–21:44:24) the previous play's yards reached the feed 10–20 s after the type 21 was on the board (21:39:14 → 110, 21:39:34 → 118), and team timeouts lasted about 50–60 s. At 30 s + 20 s the corrected figure would rarely be seen. Safe because no play runs during a team timeout, and a team timeout lasts at least 30 s | `"T"` keys only: refresh due `NFL_YDS_TIMEOUT_REFRESH_MS` = 12 000 after the first success's poll, used from the first later poll at least `NFL_YDS_TIMEOUT_CONFIRM_MS` = 5 000 after the refresh's poll that still carries the key (`SportsConstants`, `NflYardsPlanner`). O / E / H / F keep 30 000 / 20 000 (official timeouts and the two-minute warning run 2–3.5 min; breaks run minutes). §0, §1.6, §1.8, §4.1, §4.7 |
| **I2** | (Code review S1) The delays counted from the fetch's completion, so on a poll grid each slipped by one poll (a 30 s refresh came at +40, a 20 s confirm at +30) | `firstOkAt` and `pendingAt` are the attempt's poll `receivedMono`; `NFL_YDS_SUCCESSES_MAX` is now used by `due`. Poll-grid tests (10 s and 5 s) in `NflYardsPlannerTest` |
| **I3** | Halftime and the Q3 start as recorded: `STATUS_HALFTIME`, `lastPlay` type 65, yards settled at halftime's first sample, counts reset to 3 / 3 about 80 s in (21:46:44), `situation` populated; the Q3 kick-off arrives first on a halftime body (still `"H2"`), then LIVE P3. No mapper change | §1.1 rows; `NflYardsLive2ReplayTest` replays 20:58–22:00 (trimmed fixture `scoreboard-live2-replay-2026-11-16.json`) through the mapper and the planner |
| **I4** | (Code review S2) Wall-clock bounds in `EspnYardsTest` | 1 s delays, `sideMs` in 1 000..1 899, overlap < 1 000 ms; the stall's upper bound 3 500 ms |
| **I5** | The e2e case runs through the real path | `StackedNflEndToEndTest`: WP-2's view stand-in removed; snapshots pass through a real `NflYardsPlanner` (the source's step without HTTP) into the real machine. New case: the live2 official timeout → END OF 1ST → change of ends → first Q2 play (396 → 306 held, no move → 333) |
