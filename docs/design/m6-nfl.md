# NFL score bug: design

Status: **proposed 2026-11-08** (proposer, Opus). Review pending.

Owner request and rulings:
- `docs/decisions/2026-11-05-nhl-score-bug.md`, rulings 21–24 ("Owner rulings: NFL bug").
- Rulings 1–20 still apply where they fit. The ones this design leans on:
  - 1 (live by default, user delay);
  - 4 (nothing before kick-off);
  - 6 and 13 (clocks only from the feed, no local countdown);
  - 9 (no sound);
  - 12 (always the team's own two colours, no contrast substitutes);
  - 16 (the tab opens upward in bottom corners);
  - 17 (the tab gradient's far stop is `mixBlack(.10)`);
  - 20 (full animation on every box; only system "Reduce animations" gives the still version).

Mock-up: `design/nfl/Touchdown.dc.html` ("the mock"). The values are in its inline styles and in the `<helmet><style>`
keyframes `tLead`, `tWipe`, `tFlash`, `tStreak`, `tBall`, `tArc`, `tPosts`, `tPostsHit`, `tLetter`, `tWord`, `tSlideR`,
`tLogo`, `tGlow`, `tTab` and `tPop*`. The assets are under `design/nfl/assets/` (KC and BUF logos, reference only, not
shipped).

Builds on:
- `docs/design/m6-score-bug.md` ("SB §n");
- `docs/design/m6-goal-animation.md` ("GA §n");
- `docs/design/m6-power-play.md` ("PP §n");
- their sign-offs and fix passes, and `main` `97126b4`.

Units are **design px** (window width ÷ 1920; `1.dp == 1 design px`) and ms. **Must** means a reviewer rejects code
that does otherwise. Every number lives in one constant (§12).

**Scope rule:** every NHL behaviour, body, golden and test stays as it is. The NFL is added beside it, through:
- one league value;
- one sealed per-league detail on the snapshot;
- one NFL view on `ScoreBugView`;
- league-keyed lookups (sizes, colours, painters).

---

## 0. Decisions at a glance

1. **One source: ESPN's site scoreboard** (ruling 21). It is used both for the week list and for the followed game
   (`scoreboard?dates={ET day}` filtered to the event id). One request carries everything the bug draws: status,
   clock, score, `situation` (possession, down, distance, field text, red zone) and `lastPlay`.
   - The **summary endpoint is not polled.** It lagged the scoreboard by one play in the live sample. It has no
     `situation`. It folds the extra point into the touchdown's scoring play. It reaches 555 KB on a final (§1).
2. **The machine is reused unchanged in its timing:** delay queue, polls, stale, hide, removal and expiry. ESPN sends
   `Date` with no `Age`, so the existing arithmetic polls about every 21.5 s with no change (§1.5). A league branch
   adds the NFL centre texts, the NFL view (down column and tab) and the score kind (§3).
3. **Score kind = the point rise, not the feed's play type** (§3.2). In the live sample the score arrived one poll
   **before** `lastPlay` changed, and the extra point never appeared as a `lastPlay` at all.
   - The rise maps as: 6 → TOUCHDOWN; 3 → FIELD GOAL; 1 → EXTRA POINT.
   - 2 → TWO-POINT when that side has a try pending (it scored +6 and nothing has happened since), else SAFETY.
   - **7 or 8 in one poll → one TOUCHDOWN takeover reading +7 / +8** (owner question Q1). This is common, not a
     corner case: the feed itself delivered CIN's TD and XP as one +7 update after a 105 s freeze (§1.2).
   - Any other rise updates the score with no takeover.
4. **The pill is 520 × 70** (Large 650 × 88). It has the NHL's segments plus a 111 px down column after the centre.
   The quarter sits over the clock, as in the mock. Breaks and Final use one line across the centre and down columns
   (§4.1, §4.2).
5. **The possession tab is the power-play tab's geometry and track, with NFL content** (§4.3):
   - the content is "KC BALL | OWN 35", "KC BALL | EXTRA POINT" or "KC KICKOFF | OWN 35";
   - it is always on during play (ruling 22);
   - it is closed in breaks, at Final, while stale or waiting, and when the possession is unknown;
   - a possession change is CLOSE then OPEN (PP §3.1), and a field-position change is a plain cut;
   - bottom corners open it upward (ruling 16).
6. **The scoring takeover** follows the mock's keyframes on the GA approach: one draw node, one driver read only in
   draw, a pure timeline in `:core` and baked sprites. It runs 3.6 s plus a 0.9 s pop (§5):
   - TD, two-point and safety run the ball; XP and FG run the arc and the goalposts;
   - the word drops in letter by letter, then the logo, +N and the tally;
   - the pill glows (the GA masks);
   - the tab is pinned to "TOUCHDOWN | KC +6" in the scorer's colours, pulsing.
7. **Flash rule:** the mock's panel flash (4 × 300 ms, 3.3 Hz) and goalpost pulse (3 × 300 ms) exceed the limit.
   Every flashing element runs on **one 450 ms grid (2.2 Hz)**, in phase, so the pill and the tab never flash out of
   step (§5.3).
8. **Colours:** a bundled 32-team table from teamcolorcodes.com, checked 2026-11-08 (§6).
   - The lookups are keyed by **league**: BUF, CAR, CHI, DAL, DET, MIN, PHI, PIT and SEA exist in both leagues.
   - The tab uses the team's **base** colour, as in the mock (BUF blue, KC red).
   - C1 exceptions under ruling 12: CIN, DEN, MIA, CAR, LAC. C5 passes for all 32.
9. **League switch** (ruling 24): `SportsPrefs.league` (remembered, backed up) chooses which league the Sports tab
   and the picker list. NHL / NFL chips sit at the top. There is one followed game, of either league (§7).
10. **An NFL "day" is the ESPN week** (Wednesday to Tuesday, as ESPN's calendar defines it). The chips read
    Last week · This week · Next week, so Thursday, Sunday and Monday games are on one list (§7.2).
11. **The fake panel** gets a new module `tools/fakepanel/sports_nfl.py` behind `--sports-nfl`. Its 660 s timeline
    covers every state and scoring kind. `sports.py` is untouched, so the NHL golden is byte-identical by
    construction (§9).
12. **Commit 0 fixes every cross-WP signature** and builds green. Then five WPs run in parallel worktrees with
    disjoint files (§11).

---

## 1. Data (ESPN, ruling 21)

### 1.1 What the API returns (fetched 2026-11-08, 14:28–15:12 UTC)

Base: `https://site.api.espn.com/apis/site/v2/sports/football/nfl/`. There is no key and no auth; the API is
undocumented.

| Fact | Status | Evidence (fixtures in `core/src/test/resources/sports/nfl/`) |
|---|---|---|
| `scoreboard` with no query = **the current ESPN week** (week 9: Thursday 11-06 final, Sunday, Sunday night, Monday 11-10 01:15Z), with `season {type 2, year}` and `week {number, teamsOnBye}` | **Verified** | `scoreboard-week9-pre-2026-11-08.json` (15 events, 271 982 B raw, 23 KB gzip) |
| `scoreboard?dates=YYYYMMDD` = one **US Eastern** day. Monday night's 01:15Z game is in `dates=20261109`. An empty day gives `events: []`. `dates=2026110` gives HTTP 400 `{code, message}` | **Verified** | `scoreboard-day-pre-2026-11-08.json` (3 events kept); curl probes of `dates=20261105/09/10` |
| `scoreboard?seasontype=2&week=N` = that week (8, 9, 10 checked); an unknown week gives `events: []` | **Verified** | probes |
| `leagues[0].calendar` lists each season type's weeks with `label` ("Week 9"), `value`, `startDate`/`endDate` (a week runs Wednesday 07:00Z to the next Wednesday 06:59Z) | **Verified** | week fixture |
| Event: `id` ("401873038", digits), `date` (ISO Z), `shortName` ("CIN VS ATL" at a neutral site, "DAL @ IND"), `competitions[0].neutralSite` | **Verified** | fixtures |
| Status: `status.type.{id, name, state, completed, shortDetail}`, `status.period`, `status.clock` (seconds, float), `status.displayClock` ("14:54") | **Verified**: `STATUS_SCHEDULED`/`pre`, `STATUS_IN_PROGRESS`/`in`, `STATUS_FINAL`/`post` | fixtures |
| `STATUS_END_PERIOD` (`in`, `shortDetail` "End of 1st", clock 0:00, `lastPlay` 2 End Period); **the situation is kept through the break** (1st & 10 "ATL 30", possession CIN), so breaks are told by the status, never by the situation. The quarter went from 0:02 straight to END_PERIOD | **Verified** (15:11:33) | `scoreboard-live-end-period-2026-11-08.json` |
| `STATUS_HALFTIME` (`in`); Final after OT (`post`, period 5, "Final/OT") | **Documented, not yet seen** (§1.6) | device item |
| Competitor: `id` (team id, string), `homeAway`, `score` (**a string**, "7"), `team.{abbreviation, displayName, shortDisplayName, color, alternateColor, logo}`; `linescores` from kick-off | **Verified** | fixtures |
| `situation` appears only once the game is `in`: `down`, `distance`, `yardLine`, `downDistanceText` ("1st & 10 at ATL 32"), `shortDownDistanceText` ("1st & 10", "1st & Goal"), `possessionText` ("ATL 32"), `isRedZone`, `homeTimeouts`, `awayTimeouts`, `possession` (**the team id**, "1"), `lastPlay` | **Verified** | `scoreboard-live-*.json` |
| `lastPlay`: `id`, `type.{id, text, abbreviation}`, `text`, `scoreValue`, `team.id`, `start`/`end.yardLine` | **Verified** | live fixtures; ids seen: 70 Coin Toss, 53 Kickoff, 5 Rush, 24 Pass Reception, 3 Pass Incompletion, 8 Penalty, 67 Passing Touchdown, 74 Official Timeout |
| Final play-by-play ids (summary): 68 Rushing TD, 67 Passing TD, 59 Field Goal Good, 26 Pass Interception Return, 29 Fumble Recovery (Opponent), 65 End of Half, 66 End of Game, 2 End Period, 75 Two-minute warning, 21 Timeout | **Verified** | `summary-final-excerpt-401873037.json` |
| `situation.yardLine` is **home-relative** (yards from the home team's goal line): ATL (home) at "ATL 32" → 32, at "CIN 19" → 81; CIN at "CIN 25" → 75; the ATL kick-off → 35; CIN's (away) kick-off from CIN 35 → **65** | **Verified on one game**, home and away sides | live log; `scoreboard-live-plus7-away-kickoff-2026-11-08.json` |
| Team colours `color` / `alternateColor` (hex without `#`) | **Verified**, but they **disagree with the teams' own palettes** (KC alt `ffb612` vs `#FFB81C`; BUF alt `d50a0a` vs `#C60C30`) | `teams-2026-11-08.json`; not used (§6) |
| Logos `https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/{abbr}.png` and `/500-dark/scoreboard/`: 500 × 500 PNG, **40 KB (KC) to 491 KB (LV dark)**. The combiner `https://a.espncdn.com/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/kc.png&h=200&w=200` gives 11.5 KB. An unknown abbreviation is a 404 `text/html` | **Verified** | curl |
| Cache headers: `Cache-Control: max-age=1..10` (varies per answer), `Date`, **no `Age`, `ETag` or `Last-Modified`**; gzip; `Access-Control-Allow-Origin: *` | **Verified** (60 answers) | `live-sample-2026-11-08.log` |
| Summary (`summary?event=`): `header.competitions[0].status` and the competitors (`possession: false` for both while live); `scoringPlays[]` with `type` ("Passing Touchdown"), `scoringType.abbreviation` (TD/FG), `awayScore`/`homeScore`, `team`; **the XP is folded into the TD's entry** (0–6 changed to 0–7 in place; text "(Cam Little Kick)"); `drives` with every play. Size: 118 KB pre-game, **555 195 B on a final** (over the existing 512 KiB cap) | **Verified** | `summary-*-excerpt*.json` |

### 1.2 The live sequence (CIN @ ATL, Madrid, 2026-11-08; 15 s samples)

| UTC | Clock | Score (CIN–ATL) | `situation` | `lastPlay` | Reading |
|---|---|---|---|---|---|
| 14:35:21 | 15:00 | 0–0 | down 0, dist 0, yl 0, no possession | 70 Coin Toss | `in`, before the kick-off: **tab closed, bug shown** |
| 14:36:07 | 15:00 | 0–0 | 1st & 10, "ATL 20", **no possession** | 53 Kickoff | after a touchback, the possession comes one poll later |
| 14:36:52 | 14:54 | 0–0 | 1st & 10, "ATL 32", possession ATL | 24 Pass Reception | the normal case |
| 14:37:38 | 13:56 | 0–0 | **1st & 10 "CIN 49"** (old) | 5 Rush (old) | the clock moved before the situation did: one body can be half-updated |
| 14:39:10 | 12:31 | 0–0 | 1st & 10 "CIN 19", `isRedZone: true` | 5 Rush | |
| 14:40:27 | 11:49 | 0–0 | 1st & Goal "CIN 4" (dist 4, yl 96) | 5 Rush | "Goal" is in the short text; distance = 100 − yl |
| **14:41:58** | 11:12 | **0–6** | 1st & Goal "CIN 1" (**old**) | 8 Penalty (**old**) | **the score arrives before the play** |
| 14:42:13 | 11:12 | 0–6 | **down −1, dist 10, yl 100**, no possession | **67 Passing Touchdown, `scoreValue` 6** | the try |
| **14:42:44** | 11:12 | **0–7** | **down −1, dist 0, yl 35, possession ATL** | **74 Official Timeout** | the kick-off. **The XP never appears as `lastPlay`** |
| 14:45:48 | 11:12 | 0–7 | 1st & 10 "CIN 25" (yl 75), **no possession** | 53 Kickoff | as at 14:36:07 |
| 14:46:33 | 11:07 | 0–7 | 2nd & 6 "CIN 29", possession CIN | 24 Pass Reception | |
| 14:48:20–14:49:52 | 9:39 | 0–7 | 3rd & 2 "ATL 47", CIN (unchanged for **105 s**, 7 polls) | 24 Pass Reception | **the feed froze** during CIN's scoring play (the polls themselves were `Ok`, so nothing went stale) |
| **14:50:07** | 8:49 | **7–7** | **down −1, dist 0, yl 65, possession CIN** | 74 Official Timeout | **TD and XP in one update (+7)**, already in the kick-off state; CIN (away) kicks from CIN 35 = home-relative 65 |
| 14:53:10 | 8:49 | 7–7 | 1st & 10 "ATL 40", **no possession** | 53 Kickoff | the third kick-off, the third missing possession |
| 15:03:21 | 3:05 | 7–7 | 4th & Goal "CIN 13" | 3 Pass Incompletion | "4th & Goal" from the feed |
| **15:04:07** | 2:56 | **7–10** | **down −1, dist 10, yl 100, no possession** | **59 Field Goal Good, `scoreValue` 3** | **the same dead-ball shape as the try**: it is not a PAT by itself. This time the score and `lastPlay` came together |
| 15:04:22 | 2:56 | 7–10 | down −1, dist 0, yl 35, possession ATL | 74 Official Timeout | the kick-off |
| 15:07:25 | 2:56 | 7–10 | 1st & 10 "CIN 30", **no possession** | 53 Kickoff | the fourth missing possession |

- **The clock steps only when a play is logged**, about every 30–45 s (15:00 → 14:54 → 14:25 → 13:56 → 13:13 → 12:31).
  It is not a running clock. Ruling 6 shows it as reported, so the bug changes about once per play.
- `isRedZone` stays `true` through the try and the kick-off, so it is trusted only with a real down (1–4).
- The scoreboard body stayed between 232 and 236 KB raw (20–21 KB gzip) while one game was live.

### 1.3 Mapping (`EspnMapper`, `:core`, pure)

**Board.** Each event becomes one `GameSnapshot`. An event that fails to map is dropped from the board; the board
itself fails only when `events` is missing.
- `dayKey`: the week key `"{year}-{seasontype}-{week}"` ("2026-2-9"). For a dated body it is the ET date
  ("2026-11-08").
- `prevDay`/`nextDay`: the neighbouring calendar entries (§7.2).

**Status** (`state`, then `name`):

| `state` | `type.name` | `GamePhase` | `NflBreak` | Centre (§4.2) |
|---|---|---|---|---|
| `pre` | any | `SCHEDULED`, or `PRE` within `NFL_PRE_WINDOW_MS` (60 min) of the start by the body's server `Date` | — | bug hidden (ruling 4) |
| `pre`/`post` | `STATUS_POSTPONED`, `STATUS_CANCELED` | `POSTPONED` | — | removed (SB §2.6) |
| `in` | `STATUS_IN_PROGRESS` and any unknown `in` name (a weather delay) | `LIVE` | — | quarter over clock |
| `in` | `STATUS_END_PERIOD` | `INTERMISSION` | `END_PERIOD` | "End 1st" … "End 4th", "End OT" |
| `in` | `STATUS_HALFTIME` | `INTERMISSION` | `HALFTIME` | "Halftime" |
| `post` | anything else | `FINAL`; `finalKind = OT` if `period ≥ 5` | — | "Final" / "Final/OT" |
| missing or unknown `state` | | the event is dropped (a `BAD_BODY` for `game()`) | | |

**Period.**
- Periods 1–4 are `PeriodKind.REG` with number = period, labelled "1st" … "4th".
- Period ≥ 5 is `PeriodKind.OT`, with `otNumber = period − 4` ("OT", "2OT" in the playoffs).

**Clock.** `GameClock(secondsRemaining = floor(status.clock) or parse(displayClock), running = false,
inIntermission = phase == INTERMISSION)`. ESPN has no running flag; the NFL branch never reads `running` (§3.1).

**Teams.**
- `TeamLine(abbrev, name = displayName, score = score.toIntOrNull(), logo)`. The score is null before `in`, because
  ESPN sends "0" pre-game.
- `shortDisplayName` is the card name ("Commanders").
- `logo` is the competitor's `team.logo` (light variant). The source derives the dark combiner URL (§1.5).

**`LeagueDetail.Nfl`**, from `situation` (absent → `play = None`, everything null):
- `possession`: the competitor whose `id` equals `situation.possession`, else null.
- `play`:
  - `down ∈ 1..4` → `Down(down, distance (1..99, else null), goalToGo, field)`. `goalToGo` is true when
    `shortDownDistanceText` matches `(?i)^(1st|2nd|3rd|4th) & goal$`, or when `distance == 100 − yards-to-go` from
    the field.
  - `down ∉ 1..4`, `distance == 0`, possession present → `Kickoff(kicker = possession, field)`.
  - `down ∉ 1..4`, `distance > 0`, no possession → `Dead(scorer, scoreValue)`, the dead ball after a score. **It is
    the same after a touchdown (the try) and after a field goal** (§1.2, 14:42:13 and 15:04:07), so the mapper does
    not call it a try. `scorer`/`scoreValue` are `lastPlay`'s when its `scoreValue > 0`, else null. The machine decides
    (§3.3).
  - Otherwise (before the opening kick-off: down 0, distance 0, no possession) → `None`.
- `field` (a `FieldPos`):
  1. **from `possessionText`** `^([A-Z]{2,3}) (\d{1,2})$`: the possessing team's own abbreviation gives `OWN n`,
     the other `OPP n`, and n == 50 gives `MIDFIELD`;
  2. else, for a kick-off, **from `yardLine`**, which is home-relative: a home kicker is `OWN yl`, an away kicker
     `OWN (100 − yl)`;
  3. else null.
- `redZone`: `isRedZone == true` and `play is Down`.
- `breakKind`: as in the status table.
- `lastPlay`: `NflLastPlay(id, typeId, typeText, side (from team.id), scoreValue)`, or null.

**Defensive mapping.** Anything not matching its shape becomes null for that field only (ruling 21). Every field is
optional except `id`, `date`, `status.type.state` and the two competitors with `homeAway`.

### 1.4 Missing or flaky, and how the bug degrades (ruling 21)

| Missing or odd | Effect |
|---|---|
| `situation` absent while `in` | no down column, tab closed; score, clock and quarter as usual |
| `possession` absent with a real down (seen after every kick-off return) | the machine infers it: the receiver after a kick-off, else the previous possession for the same drive (§3.3); otherwise the tab stays closed |
| `possessionText` absent | the tab shows "KC BALL" alone, centred (as PP's label with no seconds) |
| `down`/`distance` out of range | down column blank |
| `lastPlay` stale or absent | nothing depends on it (§3.2) except the try's side, which the machine has anyway |
| a half-updated body (the clock ahead of the situation) | shown as is; the next poll fixes it (ruling 6: the feed is the truth) |
| `score` not an integer | that side's score is null, and no takeover can flag |
| `status.clock` absent | `displayClock` parsed; if neither is present, no clock (a one-line quarter) |
| an unknown `type.name` while `in` | `LIVE` |
| the body over `ESPN_MAX_BODY_BYTES`, non-JSON, or an HTML 404 | `Failed(BAD_BODY / HTTP_OTHER)`: the existing stale and hide path, with "ESPN did not answer" in Sports |
| the event missing from its dated board | `NotFound`; two of them 20 s apart remove the follow (SB §2.6, "ESPN no longer lists KC – BUF") |
| the colour fields | not read (the table is bundled, §6) |

### 1.5 HTTP (`EspnScoreSource`, `:data`)

- **The same transport as `NhlScoreSource`:** the `@ApiClient`, a 10 s call timeout, no cache and no ETag, the
  `Date`/`Age` reading, the 404 / HTML-404 / 429 / 5xx classification, and state-change logging of the path only.
  - Factor out a shared `SportsHttp` helper **in commit 0 only if it is a pure move**. Otherwise copy; the NHL source
    is not edited in a WP.
- **Endpoints:**

  | Call | Request |
  |---|---|
  | `today()` | `scoreboard` |
  | `day(key)` | `scoreboard?seasontype={t}&week={n}` for a week key, or `scoreboard?dates={yyyymmdd}` for a date key |
  | `game(id)` | `scoreboard?dates={the game's ET date}`, filtered to `id` |

  The ET date comes from `FollowedGame.startUtcMs` through `America/New_York`.
- **Timing.** `Age` is absent, so `ageS = 0`. `SportsTiming.nextAligned` then puts the next live poll at
  `received + 20 s + POLL_PHASE_MS`, about every 21.5 s. ESPN's `max-age` is ≤ 10 s, so each poll gets a fresh body.
  **No machine or policy change.** (10 s polling would halve the lag at twice the traffic; not proposed.)
- **Traffic:** about 21 KB gzip per poll, about 3.6 MB per hour of a followed live game. Nothing is fetched with
  nothing followed and Sports closed (SB §2.3, unchanged).
- **Body cap:** `ESPN_MAX_BODY_BYTES = 1 MiB`, because the week board is already 272 KB pre-game. The NHL keeps its
  512 KiB.
- **Decode by streaming** (the project's streaming-parser rule):
  - `Json { ignoreUnknownKeys = true }.decodeFromStream(EspnDto.serializer(), source.inputStream())`, with small
    `@Serializable` DTOs holding only the §1.3 fields;
  - `leaders`, `odds`, `headlines`, `broadcasts` and the rest are skipped by the reader and never built;
  - the cap is enforced by a counting `Source` that throws at the limit;
  - the NHL keeps its tree parse (unchanged).
- **Logos** (validated against `logoBase = https://a.espncdn.com/`, SB A-N2):
  `combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/{abbr}.png&h=200&w=200`. That is 4 × the 46 px slot
  height, raster in, about 12 KB each.
  - The abbreviation is lower-cased and must match `^[a-z]{2,3}$`.
  - The logo goes through the existing Coil path and `LogoTrim` (PNG instead of SVG; `FollowLogos` is unchanged).
- **Endpoint override** (debug builds, SB A-N3). The existing `files/debug/sports_base` origin serves both leagues:
  - `EspnEndpoints.fromOverride(origin)` = `{origin}/apis/site/v2/sports/football/nfl/`;
  - the logo base is `{origin}/`, with the fake panel serving `/combiner/i`.

### 1.6 Still to sample live (device items, after 1 pm ET on a Sunday)

1. `STATUS_HALFTIME`: the name, and whether `situation` is kept (it is through `STATUS_END_PERIOD`).
2. A **missed XP** and a **missed FG** (a field goal was seen: §1.2, 15:04:07).
3. A **safety** and a **two-point try**: is the try `down −1, distance 2`? Is a free kick from the 20 `yl 20`/`80`?
4. Final after OT: the `name` (`STATUS_FINAL` or a `…_OVERTIME` name) and `shortDetail` "Final/OT".
5. `yardLine` on a second game (home-relative was seen for both sides of one game).
6. `possessionText` at the 50 ("50"? "MID"?).
7. A defensive or return TD's `lastPlay.type.id` (36 / 39 / 32 / 34 expected).
8. A Sunday-afternoon board with 8 live games: its size against the 1 MiB cap.

`tools/fakepanel/sample_espn.py` (WP-5, a copy of the proposer's sampler) records them:
`python3 -I tools/fakepanel/sample_espn.py --event {id} --out {dir}`. It writes a JSONL log and keeps each raw
body whose state changed.

---

## 2. Contracts (`SportsContracts.kt`, commit 0; trailing and defaulted, NHL call sites unchanged)

```kotlin
@Serializable enum class League { NHL, NFL }                                  // append-only (persisted)

/** (NFL §1.3) League-specific snapshot detail. The NHL keeps its own fields (powerPlay, lastGoal). */
sealed interface LeagueDetail {
    data class Nfl(
        val play: NflPlay, val possession: Side?, val redZone: Boolean,
        val breakKind: NflBreak?, val lastPlay: NflLastPlay?,
    ) : LeagueDetail
}
enum class NflBreak { END_PERIOD, HALFTIME }
sealed interface NflPlay {
    data class Down(val down: Int, val distance: Int?, val goalToGo: Boolean, val field: FieldPos?) : NflPlay
    data class Dead(val scorer: Side?, val scoreValue: Int?) : NflPlay        // after a TD (the try) or a FG: same shape
    data class Kickoff(val kicker: Side?, val field: FieldPos?) : NflPlay
    data object None : NflPlay
}
enum class FieldZone { OWN, OPP, MIDFIELD }
data class FieldPos(val zone: FieldZone, val yard: Int)                       // MIDFIELD: yard 50
data class NflLastPlay(val id: String, val typeId: String?, val typeText: String?, val side: Side?, val scoreValue: Int?)

GameSnapshot(…, val detail: LeagueDetail? = null)

// ---- what the bug draws (machine output, §3)
enum class NflScoreKind { TOUCHDOWN, EXTRA_POINT, FIELD_GOAL, SAFETY, TWO_POINT }   // append-only
data class NflScore(val kind: NflScoreKind, val points: Int)                 // points = the actual rise (6, 7, 8, 3, 1, 2)
sealed interface DownText {
    data class Down(val down: Int, val distance: Int?, val goal: Boolean) : DownText   // "1st & 10", "4th & Goal", "3rd"
    data object Pat : DownText
    data object Kickoff : DownText
}
enum class TabLead { BALL, KICKOFF }
sealed interface TabTail {
    data class Field(val pos: FieldPos) : TabTail                            // "OWN 35", "OPP 12", "MIDFIELD"
    data object ExtraPoint : TabTail
}
data class NflTab(val side: Side, val lead: TabLead, val tail: TabTail?)
data class NflView(val down: DownText?, val tab: NflTab?, val score: NflScore?)

ScoreBugView(…, val league: League = League.NHL, val nfl: NflView? = null)
CentreText.Halftime                                                           // new data object, one line
PlacementInput(…, val league: League = League.NHL)
SportsPrefs(…, val league: League = League.NHL)                               // the shown league (ruling 24)
```

- **Strings stay in `:ui`.** `:core` emits enums and ints, as `CentreText` does.
- **`CentreText`** gains only `Halftime`. The NFL reuses `Period(label)` (REG 4 = "4th": new string
  `sports_period_4`, so the NHL's `sports_period_n` "P4" is untouched), `EndOf(label)`, `Final(kind)`, `NoUpdate` and
  `Delayed`. The **layout** (two lines quarter-over-clock, or one line across two columns) is the NFL painter's
  (§4.2).
- `ScoreBugView.score…` reuses the NHL goal mark: `goalSeq` and `goalSide` flag the NFL score, and `nfl.score` carries
  its kind for the length of the mark.
- **Colour lookup:** `TeamColourTable.of(league, abbrev)` delegates to `NhlTeamColours.of` or `NflTeamColours.of`.
  `GoalHooks.colours` becomes `(League, String?) -> TeamColours`. This is the one NHL-file signature change; the NHL
  call passes `League.NHL`.

---

## 3. Machine (`ScoreBugMachine`)

### 3.1 What is reused and what branches

- **Unchanged:** the delay queue, anchors, polling, back-off, demand, stale/hide, NotFound/removal, expiry
  (final + 5 min, start + 8 h), `goalSeq` monotonic, the goal mark (`GOAL_MARK_MS` 4.5 s = 3.6 s takeover + 0.9 s pop)
  and the C-N1 freshness rule (`GOAL_FRESH_MS`).
- **Branch on `game.league == NFL`** in `view()`:

  | Snapshot | `top` | `bottom` |
  |---|---|---|
  | `FINAL` | `Final(finalKind)` | — |
  | `INTERMISSION` + `HALFTIME` | `Halftime` | — |
  | `INTERMISSION` + `END_PERIOD` | `EndOf(label)` | — |
  | `LIVE` | `Period(label)` | `Clock` (`clockSecs` from the snapshot) |
  | waiting | `Delayed` | `Clock` |
  | stale | `NoUpdate` | `Clock` |

  - In two-line states the NFL puts the quarter on **top** (as the mock), the reverse of the NHL. `top`/`bottom` keep
    their meaning ("line 1", "line 2"), and the painter reads them in order.
  - The NHL's "clock 0:00 and not running → EndOf" rule is **not** applied: ESPN shows 0:00 during untimed downs.
  - The NHL shootout and power-play gates are skipped; `powerPlay` is always null for the NFL.
- **`nfl`** is set on `view()` only (`knownView()` has none). It is non-null only for an NFL game; its fields are
  gated by §3.3.

### 3.2 Score kind (where a goal is flagged; the NHL rules for when one is flagged are unchanged)

A score is flagged when exactly one side rose against a fresh previous snapshot under `SEEN` demand (as the NHL). Then:

| Rise for side X | Kind | `points` | Notes |
|---|---|---|---|
| 6 | TOUCHDOWN | 6 | `pendingTry = X`. Any TD, including defensive and return TDs (ruling 23) |
| 7, 8 | TOUCHDOWN | 7 / 8 | TD and try inside one poll; one takeover (Q1); `pendingTry = null` |
| 3 | FIELD_GOAL | 3 | |
| 1 | EXTRA_POINT | 1 | `pendingTry = null` |
| 2 | TWO_POINT if `pendingTry == X`; else SAFETY | 2 | tie-break below; `pendingTry = null` |
| 4, 5, ≥ 9, or a fall | no flag | | the score just updates (a correction, or several scores in one missed poll) |

- **`pendingTry`** is a machine field: X after a +6.
  - It is cleared by any other flagged or unflagged score change, by a shown snapshot whose play is `Down` (the try
    and the kick-off are over), by leaving `LIVE`, and in `reset()`.
- **The 2-point tie-break.** With no `pendingTry` (for example the app started during the try), a `lastPlay` that is
  new since the previous snapshot and belongs to X can decide it: `typeText` containing "two-point" or "2pt" →
  TWO_POINT, containing "safety" → SAFETY. Else SAFETY.
- **Known misses** (in the KDoc):
  - a defensive two-point return scored by the defence during the other side's try reads SAFETY;
  - a TD and a missed try followed by a safety inside one gap reads as an unflagged +8 for different sides (a
    two-sided rise is silent);
  - a field goal plus a safety inside one gap (+5) is silent.
- **Why the rise and not `lastPlay`:** at 14:41:58 the score was 0–6 while `lastPlay` was still the earlier penalty,
  and the XP never appeared as a `lastPlay` (§1.2).
- `nfl.score` is stored with the mark and cleared with it (like `goalPp`).

### 3.3 The down column and the tab (gating and inference)

`nfl.down` and `nfl.tab` are non-null only when the view is **fresh** (not stale, not waiting) and the phase is
**`LIVE`**. They are null in breaks, at Final, stale or waiting (ruling 22), as the power-play tab closes on stale
(PP §2).

| Shown `play` | `down` | `tab` |
|---|---|---|
| `Down(d, dist, goal, field)` | `Down(d, dist, goal)` | `(poss, BALL, field?.let(::Field))` |
| `Dead(…)` with a try pending (`pendingTry != null`, or `scoreValue == 6`) | `Pat` | `(pendingTry ?: scorer, BALL, ExtraPoint)` |
| `Dead(…)` with no try pending (after a field goal or a safety) | blank | **held**: the last shown tab, unchanged, so no close/open flicker before the kick-off state arrives one poll later |
| `Kickoff(kicker, field)` | `Kickoff` | `(kicker, KICKOFF, field?.let(::Field))` |
| `None` | null | null |

**Possession inference** (ESPN drops `possession` on the first poll after every kick-off return, §1.2): when a `Down`
has no possession,
1. if the previously shown play was `Kickoff(k)`, use the other side;
2. else, if the previous shown `Down` had a possession, keep it;
3. else the tab is null.

The inferred side never leaks into anything but the tab. A tab whose side is still null is null.

---

## 4. The bug

### 4.1 Pill geometry (Standard 520 × 70, radius 35; Large × 1.25 = 650 × 88)

```
| 16 | logo 64×46 | 8 | score 50 | 12 | ┃1 | centre 106 | ┃1 | down 111 | ┃1 | 12 | score 50 | 8 | logo 64×46 | 16 | = 520
```

- **From the NHL** (SB §4.1): the height, radius, logo slot, glass, type families, centre width and dividers. The bug
  family reads as one.
- **Score slot 50** (the NHL's is 48): NFL scores are two digits; "88" at 40/700 measures 48.5.
- **Down column 111** after the centre. The mock's down column is 1.14 × its centre (160 / 140); 111 holds
  "1st & Goal" at 22 px (102.8).
- **Ratio** 7.4 : 1 (the mock is 6.8 : 1, at its 74 px score and 78 px logos). The NFL's type is the NHL's, so the
  floor holds (deviation D1).
- **Rest boxes:**
  - top right (1304, 56), top left (96, 56), bottom right (1304, 954), bottom left (96, 954);
  - Large: right corners x 1174, bottom y 936.
- **Layer margin `M`** is unchanged (74 / 76): the glow masks and the tab reach are the NHL's (§4.3, §5.5).
  `RootTreeRuleTest` and `ScoreBugBoundsTest` gain an NFL row each and are otherwise unchanged.

### 4.2 Columns and states

| Column | Content | Type |
|---|---|---|
| Logos, scores | as the NHL | score 40/700 tabular |
| Centre, two lines | **quarter** ("1st" … "4th", "OT", "2OT") 20/600 `Fg .70` on top (box 22), **clock** `m:ss` / `mm:ss` 26/**700** tabular below (box 30); 9 above and below | the mock's order; the clock's weight per the mock |
| Down | "1st & 10", "4th & Goal", "PAT", "KICKOFF" | 22/700 `Fg`, centred; a string wider than 111 − 6 steps 2 px down to 18 ("4th & Goal" → 20, 100.5) |
| One line (Final, Final/OT, Halftime, End 1st…End 4th, End OT) | across centre + divider + down (218), the middle divider hidden | 24/600, centred |

| State | Centre | Down | Tab |
|---|---|---|---|
| `SCHEDULED`, `PRE` | **no bug** (ruling 4) | | |
| `LIVE`, before the opening kick-off (`None`) | "1st" / "15:00" | blank | closed |
| `LIVE`, a down | "4th" / "3:42" | "1st & 10" | "KC BALL \| OWN 35" |
| `LIVE`, the try | quarter / clock | "PAT" | "KC BALL \| EXTRA POINT" |
| `LIVE`, the kick-off | quarter / clock | "KICKOFF" | "KC KICKOFF \| OWN 35" |
| `END_PERIOD` | "End 1st" (one line) | (merged) | closed |
| `HALFTIME` | "Halftime" (one line; fits at 24: 98.2) | (merged) | closed |
| `FINAL` | "Final", "Final/OT" (one line) | (merged) | closed |
| Stale | "No update" over the old clock, dimmed `.55` | blank | closed |
| Waiting on the delay | "Delayed" over the oldest clock, dimmed | blank | closed |

- "Halftime" rather than "Half": it fits, and it reads without the context of a period label.
- The clock changes only with a snapshot (ruling 6). ESPN updates it per logged play (§1.2).
- **Semantics** (`Describe`): "Bengals 0, Falcons 7, 11:12 left in the 1st. Falcons ball, 1st and 10." It adds
  `sports_desc_nfl_ball` "%1$s ball" and `sports_desc_nfl_down` "%1$s and %2$s" when present.

### 4.3 The possession tab (ruling 22)

**Geometry** (PP §4.1 with an NFL inset):

| Item | Mock (980 × 144 pill) | Standard (520) | Large (650) |
|---|---|---|---|
| Side inset | 70 | **37** → width **446** | **46** → width **558** |
| Visible depth / hidden overlap / far radius | 46 / 34 / 36 | **36 / 20 / 18** (PP) | 45 / 25 / 22.5 |
| Ball icon (the mock's SVG path, stroke 1.7 / 24) | 28 | 20, stroke 1.4 | 25 |
| Lead text ("KC BALL", "KC KICKOFF") | 24/800 | **20/700**, tracking .05 em | 25/700 |
| Divider | 2 × 26 at .45 | 1 × 16 white .5 | 1.25 × 20 |
| Tail text ("OWN 35", "EXTRA POINT") | 24/600 | **20/600**, tracking .05 em | 25/600 |
| Gaps (icon–lead, lead–divider, divider–tail) | 18 | 10 | 12.5 |
| Text centre from the pill edge | 23 | 18 (PP) | 22.5 |
| Shadow | 0 8 28 .45 | PP's: dy 5, σ 8 × d, α .5 | dy 6 |
| Text shadow | none | PP's hard dy 1, black .35 | |

- **Fit** (measured from the bundled Instrument Sans advances; the method reproduces PP's 203 for "WPG POWER PLAY"):
  - the widest content, "WSH BALL | EXTRA POINT", is 308.5;
  - the score label "TWO-POINT | WSH +2" is 264;
  - the room is 446 − 2 × 18 = 410, so everything fits at 20 px with no step-down;
  - type ≥ 18 everywhere.
- **Reach past the pill:** 36 + 5 + 24 = 65 ≤ `M` 74; Large 75 ≤ 76 (as PP).
- **Colours.** The gradient is PP §6.1's: `mixBlack(tab, .70 / .28 / .10)`, dark at the pill side.
  - Here **`tab = base`** when C5 passes on it, else the accent. C5 passes on the base for all 32 (§6).
  - The mock uses base for both of its teams (KC `#4a0710 → #a8122a → #E31837`, about `mixBlack .68 / .26 / 0`).
  - Dark bases (CHI, LV, NO, PIT) give a tab whose silhouette is faint over dark video. The text still passes C5 (an
    eye check on a TV).
- **Direction:** below the pill at top corners, above at bottom corners (ruling 16). The effective corner is taken
  after `textTrackOn`, as PP §4.2.
- **Drawing** (PP §3.4, §4.3): after the glass, under the outside `clipRect` snapped to a device px; no `clipPath`, no
  `saveLayer`.
  - The label masks are baked `ALPHA_8` at 1.25×, keyed on `(s, size, away.abbrev, home.abbrev)`: per side "{ABBR}
    BALL" and "{ABBR} KICKOFF"; shared "EXTRA POINT", "OWN", "OPP", "MIDFIELD" and the five score words; per side
    "+N" for N in {1, 2, 3, 6, 7, 8}.
  - The yard digits come from a pre-measured digit table, as the PP seconds.
  - The ball icon is one baked `ALPHA_8` sprite.

**The track** is PP's `PowerPlayTimeline` TAB track: `OPEN` (slide 550 snap, text in from 380), `CLOSE` (600) and
`STILL`. The NFL painter **ignores** `shineOn` and `labelScale`; the mock has no shine and no label pulse.
- **`NflTabRun`** (in `NflBug.kt`) mirrors `PpRun`'s rules:
  - one long-lived runner;
  - kicks only on an identity change; for the NFL the key is `(tabOn, tab.side, pinned)`;
  - the S1 interrupt order (snap the driver before assigning `from`/`kind`/`to`);
  - the snaps of PP §3.1 (the first non-waiting view, reappearing, a size change, leaving place mode, labels ready).
- **Content changes:**
  - a lead or tail change on the same side (yard line, BALL ↔ KICKOFF, EXTRA POINT) is a plain cut;
  - a **side change** is CLOSE with the old content, then OPEN with the new (PP B2), so a turnover reads as one;
  - a side change at a kick-off (KC KICKOFF → BUF BALL) is the same.
- **Pill track:** none. The NFL has no tint and no logo halo.
- **`tabAllowed`** is PP §4.4's with the NFL tab box: an overlay within 29 px closes the tab, and place mode never
  shows it.
- **Red zone:** nothing extra (the mock has none). See Q2.

### 4.4 Placement (`ScoreBugPlacement`)

- `width(size, league)` / `height(size)`: NFL 520 / 650 and 70 / 88. `cornerBox`, `place`, `glowAllowed`, `tabBox`
  and `tabInset(size, league)` read `PlacementInput.league`. The NHL default keeps every NHL result identical (the
  existing tests pass unchanged).
- **The overlay rules are the NHL's** (SB §4.4). The wider pill meets them as follows:
  - the picker sheet (x ≥ 1376) hides the right corners, as the NHL;
  - top left spans 96–616 (Large 746), so the channel list, stats and group card hide it on intersection, as the NHL;
  - a bottom-corner bug lifts above a user bar with its tab opening upward, so no bar meets a tab;
  - the toast hides the bottom-left bug and the reminder card the bottom-right one.
- At rest the tab's edges are at y 162 (top) and y 918 (bottom), Standard, as PP.

---

## 5. Scoring takeover (ruling 23)

### 5.1 Timeline (t = 0 at the takeover start; easing per keyframe segment; mock x × 520/980 = .531, y × 70/144 = .486)

`wipe` = `(.7,0,.2,1)`, `std` = `(.4,0,.2,1)`, `spring` = `(.2,.8,.2,1)`, `ease` = `(.25,.1,.25,1)`,
`roll` = `(.3,0,.3,1)`, `ease-in-out` = `(.42,0,.58,1)`, `ease-out` = `(0,0,.58,1)`.

| Element | Start | Duration | Easing | Keyframes (Standard px) | Kinds |
|---|---|---|---|---|---|
| Lead wipe (accent) | 0 | 3 600 | wipe | in 0–216, full, out 3 384–3 600 (= GA, `tLead`) | all |
| Panel wipe (base) | 0 | 3 600 | wipe | hidden to 108, in 108–396, out 3 168–3 492 (= GA, `tWipe`) | all |
| Glow | 0 | 3 600 | ease-in-out | GA masks and keyframes (`tGlow` 15/50/85 % = `gGlow`); colour `glow` | all |
| Streak 1 (accent .6, w 51) | 350 | 1 000 | std | left edge −85 → 616, skew −24° | all |
| Streak 2 (white .35, w 19) | 1 800 | 800 | std | −85 → 616 | all |
| **Ball run** (white α ≤ .4, 34 px) | 300 | 1 300 | roll | top-left (−42, 18) → (562, 18); rotation 0 → 900°; α 0 → .4 at 15 % → .4 at 85 % → 0 | TD, 2-PT, SAFETY |
| **Ball arc** (white α ≤ .55) | 300 | 1 400 | ease | (21, 44) ×1 0° → 50 %: (202, −7) ×.75 360° → (366, 12) ×.45 720°; α 0 → .55 at 10 % → .55 at 85 % → 0 | XP, FG |
| **Goalposts** in (white, 43 × 52 at x 355, y 9) | 200 | 400 | ease-out | α 0 → .4, dy 8 → 0 | XP, FG |
| Goalposts hit | **1 800** | **3 × 450** | ease-in-out | α .4 → .95 → .4 per cycle | XP, FG |
| **Panel flash** (white) | **450** | **3 × 450** (to 1 800) | ease-in-out | each 0 → .42 → 0 | all |
| Logo badge | 450 | 600 | spring | α 0, dx −32, ×.4, −25° → 70 %: α 1, dx +2, ×1.1, +4° → rest (`tLogo`); hard shadow dy 2, black .25 | all |
| Word letters | 420 + i × 55 | 420 each | spring | α 0, dy +22, ×1.9 → 60 %: α 1, dy −2, ×.94 → rest (`tLetter`) | all |
| Word pulse | 1 400 | 3 × 500 | ease-in-out | ×1 → 1.05 → 1 (`tWord`) | all |
| +N and tally | 1 100 | 500 | spring | α 0, dx +19, ×.6 → 70 %: α 1, dx −1.6, ×1.12 → rest (`tSlideR`) | all |
| **Tab pulse** (pinned tab, §5.6) | 0 | **8 × 450** (to 3 600) | ease-in-out | brightness ×1 → 1.45 → 1 (`tTab`) | all |
| Score pop (scorer's digits) | 3 600 | 900 | spring | ×1.5 → .9 (40 %) → 1.06 (70 %) → 1, clamped to the 50 + 2 × 8 column; colour `pop` → white; revealed under the lead from 3 168 (GA §6.2) | all |

- **Letter stagger:** "TOUCHDOWN"'s last letter starts at 860; "EXTRA POINT" (11 with the space, which is skipped)
  at 970.
- **The ball stays inside the pill's stadium** except where the panel's `clipRect` already cuts it: the run's
  centre line is the pill's, and the arc's peak is mid-pill, where the top edge is straight. So the panel clip
  suffices, with no `clipPath`. The screenshot test asserts no ball pixel outside the round rect.

### 5.2 Words and the score block

| Kind | Word (Barlow Condensed Black Italic 48, tracking .03 em) | Width | Tab during the takeover |
|---|---|---|---|
| TOUCHDOWN | TOUCHDOWN | 229 | "TOUCHDOWN \| KC +6" (+7, +8 per Q1) |
| EXTRA_POINT | EXTRA POINT | 244 | "EXTRA POINT \| KC +1" |
| FIELD_GOAL | FIELD GOAL | 209 | "FIELD GOAL \| KC +3" |
| SAFETY | SAFETY | 143 | "SAFETY \| KC +2" |
| TWO_POINT | TWO-POINT | 212 | "TWO-POINT \| KC +2" |

- The bundled Barlow file is the full font (112 592 B): every glyph is there, and no re-subset is needed.
- **Layout** (Standard; Large × 1.25):
  - pad 24, then the badge (the held logo) 64 × 46 at x 24, then a 14 gap;
  - the word centred in x 102…412 (room 310);
  - the score block right-aligned at W − 24 in a fixed 74 slot: "+6" 32/700 (37.6) over the tally "7 – 0" 20/700
    tabular (slot "00 – 00" 74.3), with a 2 gap, the block vertically centred.
  - **Ruling 7's rule:** the mock's 15 px team-name line is dropped (under the floor). The mock's 14 px tally is kept
    by raising it to 20 (it is the score itself). The mock's "KC 24 · BUF 17" becomes "24 – 17", as the NHL tally.
- **Atlas:** each word's letters with their hard shadow (dy 2, black .28) go into one `ARGB_8888` atlas per size at
  1.5×, as GA S2, about 5 words × 50 KB at 1080p.
  - The ball and goalpost SVG paths are baked once per size into `ALPHA_8` sprites at 1.5×.
  - No path is stroked per frame.

### 5.3 Flash rule (the GA flash ruling, applied to the NFL)

- **In the mock:**
  - the panel flash is 4 × 300 ms (3.3 Hz);
  - the goalpost hit is 3 × 300 ms;
  - the tab pulse is 8 × 450 ms (2.2 Hz) and runs at the same time.
- **Here:** every flashing element is on one 450 ms grid from t = 0:
  - the tab peaks at 225 + 450k;
  - the panel flash at 675, 1 125 and 1 575;
  - the goalposts at 2 025, 2 475 and 2 925.

  So the screen never shows more than one flash per 450 ms (2.2 Hz) **across the pill and the tab together**. The
  limit is 3 per second.
- The panel peak is the mock's .42. The tab's brightness ×1.45 is drawn as a second pass of the same gradient with
  `PorterDuff.Mode.ADD` at alpha .45 × p, which equals `brightness(1 + .45p)` exactly (clamped). No `ColorFilter` is
  built per frame.

### 5.4 Trigger, pin and release (GA §5.2, PP §5)

**Trigger.** The GA algorithm is reused as is in `NflRun`: `seen`, `gameId`, `lastSeq`, the cancel conditions, the
start token, `takeoverOn`, and the no-spoiler hold of the previous body until `LEAD_COVERED_MS`. On start it captures
`side`, away, home, `kind` and `points`.

**Tab pin** (PP §5, simplified because there is no PP GOAL logic):
1. **At start:** `pinTabOn` = the tab is open and `tabAllowed`. If it is false (Final, a break, an overlay), the tab
   stays closed for the takeover.
2. **Before 216 ms** (pending, or `t < LEAD_COVERED_MS`): the tab holds the content it was drawing (the spoiler hold).
3. **From 216 to 3 600:** the tab shows the score label in the **scorer's** tab colours, pulsing (§5.1).
4. **Release at 3 600**, from a child watching `driver.value`, or from the still path. The tab then **cuts** to the
   current content in the current side's colours, with no slide, as the mock. If the tab is now off, it CLOSEs.
5. **A cancel** (hide, place mode, size, new game) snaps the tab.

| Case | Result |
|---|---|
| TD (+6) | TOUCHDOWN; release to "KC BALL \| EXTRA POINT", down column "PAT" |
| XP 20–60 s later (+1) | EXTRA POINT; release to "KC KICKOFF \| OWN 35" |
| FG (+3), the dead-ball body | FIELD GOAL; release to the held tab, then "KC KICKOFF \| OWN 35" on the next poll (a cut) |
| +7 in one poll | one TOUCHDOWN reading +7 (Q1) |
| FG on the last play of the half, the snapshot already `HALFTIME` | takeover; the tab was closed (pinTabOn false) and stays closed; the centre reads "Halftime" after the pop |
| OT walk-off TD on the FINAL snapshot | takeover, then "Final/OT"; the tab closed |
| Safety | SAFETY; release to "{conceding team} KICKOFF \| OWN 20" |
| A second score during a takeover | restart (GA step 4). It cannot happen at a 20 s cadence except through a delay change |
| Both sides rose in one poll | no takeover (C-N1) |
| Score corrected down | no takeover |

### 5.5 Drawing (`NflScorePainter`, `:ui`; GA §2 rules)

- **The composite** (`NflBugPainter.drawFrame`), in order:
  1. glow;
  2. glass (the NFL pill size);
  3. tab (with its ADD pulse pass while pinned);
  4. body;
  5. takeover: the lead; then under the panel clip the panel, streak 1, streak 2, the posts, the ball, the flash, the
     badge, the word and the score block.
- **Glow masks:** the GA bake (`ALPHA_8`, low and peak, the same spread, σ and α) for the NFL pill size. The margin
  stays 74 / 76; the edge alpha at the 56 px inset is the GA's 1.3 / 1.4 %.
- **Built once per size:** paints, the atlas, the sprites, the masks and the digit tables. Off the main thread, as GA.
- **Built per takeover** in `begin()`: the streak shaders, the tally chars, the colours and the padded badge.
- **Draw** uses only `save`/`restore`/`clipRect`/`translate`/`rotate`/`scale`/`drawRoundRect`/`drawBitmap`/
  `drawText(char[])`. Allocation-free.
- **Cost:** about 270 frames per score at 60 Hz, as a GA takeover. Two takeovers per TD drive (TD, then the try about
  30–60 s later) are about 9 s of animation. Device item: frame time on a Fire TV Stick (ruling 20).

### 5.6 Still version (system Reduce animations only, ruling 20; `MotionDurationScale == 0`)

- **Frame A (0–3 600):** the panel at rest: badge, word, +N and tally. XP/FG also show the posts at α .4. There is no
  lead, streaks, ball, flash, pulse or glow. The tab shows the score label, with no pulse.
- **Frame B (3 600–4 500):** the plain bug with the scorer's digits in `pop` at ×1. The tab is released (cut) at 3 600.
- **Then** the plain bug.

The tab's OPEN and CLOSE snap (`STILL`).

---

## 6. Colours (ruling 12; `NflTeamColours`, the same `derive` as the NHL)

**Sources.** **TCC** = `https://teamcolorcodes.com/<slug>/`, read 2026-11-08 from each page's "HEX codes are …"
sentence (the slugs are in the table's source comments in code). It is a secondary source, as for the NHL. ESPN's own
`color`/`alternateColor` disagree with the teams' palettes in places (§1.1), so they are not used.

**Rules:**
- base = the primary, accent = the official secondary;
- a gold or light primary takes the accent slot (NO, PIT), as ruling 12 did for NSH and PIT;
- `glow`, `pop` and C1–C3 as GA §3.1–3.2;
- **`tab` = base** if C5 passes on it, else the accent (§4.3);
- C5 is white at `mixBlack(tab, .172)` (PP §6.2).

| Abbr | Base | Accent | C1 | C2 | C3 | Glow | Pop | Tab C5 | Note |
|---|---|---|---|---|---|---|---|---|---|
| ARI | `#97233F` | `#000000` | 7.99 | 1.1 | 2.6 | base | `#C68695` | 10.01 | |
| ATL | `#A71930` | `#000000` | 7.43 | 1.1 | 2.8 | base | `#CF808D` | 9.47 | |
| BAL | `#241773` | `#9E7C0C` | 14.53 | 5.0 | 3.7 | accent | `#B1963D` | 15.99 | **gold, not TCC's second (black)**: purple and black are both dark, so the glow would vanish (Q3) |
| BUF | `#00338D` | `#C60C30` | 11.30 | 3.3 | 1.9 | accent | `#E0798D` | 13.22 | matches the mock |
| CAR | `#0085CA` | `#101820` | **4.03** | 1.1 | 4.4 | base | `#40A4D7` | 5.54 | C1 exception |
| CHI | `#0B162A` | `#C83803` | 18.07 | 3.8 | 3.5 | accent | `#DB7E5B` | 18.71 | |
| CIN | `#FB4F14` | `#000000` | **3.37** | 1.1 | 6.2 | base | `#FC6937` | 4.72 | C1 exception (orange) |
| CLE | `#311D00` | `#FF3C00` | 16.08 | 5.5 | 4.5 | accent | `#FF6333` | 17.14 | |
| DAL | `#041E42` | `#869397` | 16.54 | 6.2 | 5.2 | accent | `#8C989C` | 17.53 | **navy + silver** (TCC lists royal `#003594` first, then navy: two blues; Q3) |
| DEN | `#FB4F14` | `#002244` | **3.37** | 1.2 | 4.7 | base | `#FC6937` | 4.72 | C1 exception (orange) |
| DET | `#0076B6` | `#B0B7BC` | 4.92 | 9.7 | 2.4 | base | `#B0B7BC` | 6.57 | |
| GB | `#203731` | `#FFB612` | 12.71 | 11.2 | 7.2 | accent | `#FFB612` | 14.32 | |
| HOU | `#03202F` | `#A71930` | 16.78 | 2.6 | 2.3 | accent | `#CF808D` | 17.80 | |
| IND | `#002C5F` | `#A2AAAD` | 13.77 | 8.3 | 5.8 | accent | `#A2AAAD` | 15.38 | |
| JAX | `#006778` | `#D7A22A` | 6.54 | 8.5 | 2.8 | base | `#D7A22A` | 8.47 | **teal primary** (TCC lists black first; Q3) |
| KC | `#E31837` | `#FFB81C` | 4.72 | 11.4 | 2.7 | base | `#FFB81C` | 6.41 | matches the mock |
| LAC | `#0080C6` | `#FFC20E` | **4.28** | 12.2 | 2.6 | base | `#FFC20E` | 5.84 | C1 exception |
| LAR | `#003594` | `#FFA300` | 10.84 | 9.8 | 5.4 | accent | `#FFA300` | 12.76 | TCC "los-angeles-rams-team-colors" |
| LV | `#000000` | `#A5ACAF` | 21.0 | 8.5 | 9.1 | accent | `#A5ACAF` | 21.0 | TCC "oakland-raiders-color-codes" (titled Las Vegas) |
| MIA | `#008E97` | `#FC4C02` | **3.95** | 5.8 | 1.2 | base | `#FC6728` | 5.40 | C1 exception (aqua) |
| MIN | `#4F2683` | `#FFC62F` | 10.84 | 12.5 | 6.9 | accent | `#FFC62F` | 12.83 | |
| NE | `#002244` | `#C60C30` | 16.00 | 3.3 | 2.7 | accent | `#E0798D` | 17.17 | |
| NO | `#101820` | `#D3BC8D` | 17.89 | 10.6 | 9.7 | accent | `#D3BC8D` | 18.55 | old-gold primary → accent |
| NYG | `#0B2265` | `#A71930` | 14.68 | 2.6 | 2.0 | accent | `#CF808D` | 16.08 | |
| NYJ | `#125740` | `#000000` | 8.53 | 1.1 | 2.5 | base | `#7DA396` | 10.51 | |
| PHI | `#004C54` | `#A5ACAF` | 9.71 | 8.5 | 4.2 | base | `#A5ACAF` | 11.67 | midnight green + silver |
| PIT | `#101820` | `#FFB612` | 17.89 | 11.2 | 10.2 | accent | `#FFB612` | 18.55 | gold primary → accent |
| SEA | `#002244` | `#69BE28` | 16.00 | 8.4 | 6.9 | accent | `#69BE28` | 17.17 | |
| SF | `#AA0000` | `#B3995D` | 7.75 | 7.2 | 2.8 | base | `#B3995D` | 9.85 | TCC "san-francisco-49ers-team-colors" |
| TB | `#D50A0A` | `#34302B` | 5.42 | 1.5 | 2.4 | base | `#E87878` | 7.28 | **pewter** (TCC lists Bay orange second; Q3); TCC "tampa-bay-buccaneers-team-colors" |
| TEN | `#0C2340` | `#4B92DB` | 15.79 | 6.0 | 4.8 | accent | `#5D9DDF` | 16.93 | |
| WSH | `#5A1414` | `#FFB612` | 13.59 | 11.2 | 7.7 | accent | `#FFB612` | 15.14 | TCC "washington-redskins-color-codes" (the page is titled Commanders) |
| *fallback* | `#2A3140` | `#5CA8F2` | 13.03 | 7.8 | 5.2 | accent | `#5CA8F2` | 14.58 | as the NHL |

- **C1 exceptions** (`NflTeamColours.C1_EXCEPTIONS`, white word on base < 4.5) are applied under ruling 12's "no
  substitute colours", as PHI is for the NHL: CIN 3.37, DEN 3.37, MIA 3.95, CAR 4.03, LAC 4.28. The word passes
  3 : 1 for all.
- **C5** (white tab text on base) passes for all 32: the lowest are CIN and DEN at 4.72. `C5_EXCEPTIONS` is empty.
- **No C6:** the NFL has no pill tint.
- **Deviation D2:** the mock's BUF pop is a light blue `#4f8bff`; the shared rule gives `#E0798D`. Ruling 12 and the
  shared rule win, and the KC pop `#FFB81C` matches the mock.
- The numbers were computed with the `derive` arithmetic (WCAG 2.x, round half to even). `NflTeamColoursTest` pins
  them.

---

## 7. League switch, Sports tab and picker (ruling 24)

### 7.1 State

- **`SportsPrefs.league`** (default NHL, backed up) is the league the Sports tab and the picker list. Writing it is
  the switch, and it is remembered.
- **The repository** collects the prefs already: a league change resets the day list to that league's today
  (`Loading` → the board).
- **`ScoreRepositoryImpl`** takes `Map<League, ScoreSource>` (Hilt `@IntoMap`). The day list uses
  `sources[prefs.league]`; the followed game uses `sources[game.league]`.
- **One followed game**, either league (`FollowedGame.league` exists). Following an NFL game replaces an NHL follow
  with the usual toast. The follow survives a league switch; the bug shows whatever league is followed.
- **Demand, polling and the screen-off rule are unchanged.** One machine; the day list polls one league.

### 7.2 NFL "today" = the ESPN week

- **"This week"** is the undated scoreboard: ESPN's current week, which runs Wednesday 3 am ET to Tuesday. It holds
  Thursday night, the London/Madrid morning games, Sunday, Sunday night and Monday night on one list.
  - On a Tuesday it still shows the week's finals. On Wednesday it moves to the coming week.
- **Last week · This week · Next week** chips use the calendar neighbours (`seasontype` + `week`). At the season's
  edges, Week 18 → Wild Card and Super Bowl → none (the chip is disabled).
  - The board title shows the calendar label ("Week 9", "Wild Card", "Divisional Round", "Conference Championship",
    "Super Bowl") from the feed. It is the one feed string drawn as is besides the team names.
- **Order:** live, then upcoming by start, then final, then postponed (the existing `groupOf`).
- **Card header** for a scheduled NFL game: "Sun 1:00 PM" (weekday + time, device zone) when not today, else
  "1:00 PM". Other states as the NHL: "LIVE · 2nd · 10:22", "Halftime", "End 3rd", "Final/OT".
- **Card names:** `shortDisplayName` ("Commanders", "Buccaneers"). They are shorter than "Golden Knights";
  `GameCardFitTest` gets the 32 NFL names.
- **A week has at most 16 games**, inside the grid's ≤ 16-item assumption (SB §5.2).

### 7.3 UI

- **Sports tab title row:** "Sports", then two chips **NHL · NFL** (the selected one filled, 22/600, height 52),
  replacing the static "NHL" chip, then Corner and Delay as now.
  - Up from the day chips lands on the league chips. OK on a chip switches.
  - Initial focus is unchanged (the followed game if it is on the shown league's list, else the first live card, else
    the This week / Today chip).
- **Credit line:** "Scores and logos: NHL.com" or "Scores and logos: ESPN", following the league.
- **Picker sheet** (quick bar "Score bug"), the rows in order:
  1. the followed game (any league);
  2. **League · NFL** (OK toggles; the board reloads under a Loading row; focus stays on the League row);
  3. Corner;
  4. Delay;
  5. Stop following;
  6. the divider ("Today" or "This week", or the board's day/week);
  7. the other games.

  `PickerChoice.ScoreLeagueRow(league)` is new.
- **Strings** (`strings_sports.xml`, commit 0):
  - `sports_league_nfl` "NFL", `sports_period_4` "4th", `sports_halftime` "Halftime";
  - `sports_end_of` reused;
  - `sports_down_1`…`sports_down_4` "1st"…"4th", `sports_down_and` "%1$s & %2$d", `sports_down_goal` "%1$s & Goal",
    `sports_down_pat` "PAT", `sports_down_kickoff` "KICKOFF";
  - `sports_tab_ball` "%1$s BALL", `sports_tab_kickoff` "%1$s KICKOFF", `sports_tab_own` "OWN %1$d",
    `sports_tab_opp` "OPP %1$d", `sports_tab_mid` "MIDFIELD", `sports_tab_xp` "EXTRA POINT";
  - `sports_nfl_td` "TOUCHDOWN", `sports_nfl_xp` "EXTRA POINT", `sports_nfl_fg` "FIELD GOAL", `sports_nfl_safety`
    "SAFETY", `sports_nfl_2pt` "TWO-POINT", `sports_nfl_points` "%1$s +%2$d";
  - `sports_week_last` / `_this` / `_next` "Last week" / "This week" / "Next week";
  - `sports_empty_week` "No NFL games this week. Pick another week above.";
  - `sports_failed_espn` "ESPN did not answer. Press OK to try again; scores may be down for a few minutes.";
  - `sports_credit_espn` "Scores and logos: ESPN";
  - `sports_toast_not_listed_espn`;
  - live and description strings (§8);
  - `settings_sports_show_desc` gains "and ESPN".

---

## 8. Accessibility

- **The live region** (GA §5.3, persistent `Spacer`): while the mark is set, "Kansas City Chiefs touchdown. Chiefs 13,
  Bills 7" (`sports_live_nfl_score` "%5$s %6$s. %1$s %2$d, %3$s %4$d", %6 = the lower-case kind word). Otherwise "".
  - Possession and down changes are **not** announced; once a play would be noise. They are in `Describe` (§4.2).
- It stays speech-only (ruling 9). **Device item:** VoiceView.

---

## 9. Fake panel (`tools/fakepanel/sports_nfl.py`, new; `test_sports_nfl.py`, new)

### 9.1 Routes and switches

- **Routes:**
  - `GET /apis/site/v2/sports/football/nfl/scoreboard`, with `dates=YYYYMMDD` (Eastern), `seasontype` + `week`, or
    nothing for this week. A bad `dates` gives 400 `{code, message}`, as ESPN.
  - `GET /combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/{abbr}.png&h=200&w=200`: a generated placeholder PNG
    (pure-Python zlib writer, no team art).
  - Unknown → 404 HTML, as ESPN.
- **Headers:** `Date`, `Cache-Control: max-age=5`, no `Age`, no ETag.
- **Switches:**
  - `sports_nfl` (`--sports-nfl`, default off; needs `--sports`);
  - `sports_nfl_timeline_s` (default 660);
  - `sports_nfl_script` (`{event id: {key: value}}`; the keys of `state()`: `state`, `name`, `period`, `clock`,
    `away`, `home`, `possession`, `down`, `distance`, `yardLine`, `possessionText`, `shortDownDistanceText`,
    `isRedZone`, `lastPlayType`, `lastPlayScoreValue`, `situation: null`, `gone`);
  - `sports_nfl_faults` (the NHL fault keys).
- `GET /admin/sports-nfl` shows the slate, and `restart` is the NHL's.
- **`sports.py`, its tests and `testdata/sports_default_golden.json` are untouched.** `fakepanel.py` adds only the
  flag, the defaults and one route dispatch. The NHL golden is byte-identical by construction, and the existing golden
  test proves it.

### 9.2 The featured game (KC @ BUF, id `401899001`; segment seconds at 660; windows ≥ 25 s except the deliberate 10 s one)

| Segment (length) | Window | State | Proves |
|---|---|---|---|
| pre (40) | | `STATUS_SCHEDULED`, start = anchor + 40 | no bug (ruling 4); `PRE` within the hour |
| **Q1** (175) | 0–25 | coin toss: down 0, no possession, `lastPlay` 70 | bug shown, tab closed |
| | 25–50 | KC 1st & 10, "KC 25" | tab opens; down column |
| | 50–75 | KC 3rd & 2, "BUF 38" | OPP |
| | 75–100 | KC 1st & Goal, "BUF 4", red zone | "1st & Goal" |
| | **100–110** | **score 6–0, situation and `lastPlay` still the old ones** | the score before the play (§1.2) |
| | 110–130 | try: down −1, distance 10, no possession, `lastPlay` 67 sv 6 | PAT; the tab side from `pendingTry` |
| | 130–150 | **7–0**, kick-off: down −1, distance 0, yl 65, possession KC | EXTRA POINT, then "KC KICKOFF \| OWN 35" |
| | 150–175 | BUF 1st & 10 "BUF 25", **no possession for 0–10** | the inference; CLOSE/OPEN to BUF |
| END_PERIOD (25) | | `STATUS_END_PERIOD`, period 1 | "End 1st"; tab closed |
| **Q2** (110) | 0–25 | BUF 1st & 10 "KC 30" | |
| | 25–50 | BUF +3 (7–3) in the **dead-ball shape** (down −1, dist 10, no possession, `lastPlay` 59 sv 3) | FIELD GOAL (arc, posts); not "PAT"; the tab held |
| | 50–60 | BUF kick-off (down −1, dist 0, possession BUF) | "BUF KICKOFF \| OWN 35" |
| | 50–75 | KC 2nd & 4 "BUF 12" | |
| | 75 | **KC +7 in one bucket (14–3)**, then KC kick-off | +7 TOUCHDOWN (Q1) |
| HALFTIME (25) | | `STATUS_HALFTIME` | "Halftime" |
| **Q3** (125) | 0–25 | KC 2nd & 8 "KC 2" | |
| | 25 | **BUF safety** +2 (14–5); KC free kick "KC 20" | SAFETY; a side change at the kick-off |
| | 50–75 | KC 1st & 10 "KC 40" | |
| | 75 | **BUF interception-return TD** +6 (14–11), `lastPlay` 36 | defensive TD = TOUCHDOWN |
| | 100 | **BUF two-point** +2 (14–13), then BUF kick-off | TWO-POINT via `pendingTry` |
| END_PERIOD (25) | | period 3 | "End 3rd" |
| **Q4** (75) | 0–25 | BUF ball "KC 20" | |
| | 25 | BUF +3 (14–16) | a second FG |
| | 50–60 | BUF 3rd & 9 "BUF 2" | |
| | 60 | **KC safety** +2 (16–16); BUF free kick | an away safety; a tie |
| END_PERIOD (20) | | period 4 | "End 4th" |
| **OT** (40) | 0–25 | KC 1st & 10 "KC 25", period 5 | "OT" |
| | 25–40 | KC 1st & Goal "BUF 8" | |
| | 40 | **KC TD +6 in a `post` body** (22–16, period 5) | walk-off on FINAL → "Final/OT" |
| FINAL | | `STATUS_FINAL` | expiry after 5 min |

- **Clocks** run 1 s/s in each LIVE window and freeze in breaks. ESPN steps per play; the fake steps at each window
  start, so ruling 6's step behaviour is visible.
- **The week board** also lists:
  - a Thursday final (NYJ 20 – NE 23);
  - a Sunday 4:25 pm scheduled game (SF @ SEA);
  - a Monday scheduled game (LV @ DEN).
- **Day boards:** the featured game's ET date has it and the Sunday game; other dates are empty.

### 9.3 Tests (`test_sports_nfl.py`)

- every row above, in the week body and the dated body;
- scores are strings;
- `situation` is absent pre-game, and its shape matches the fixtures' keys;
- `yardLine` is home-relative;
- the +7 bucket and the 10 s window;
- the `post` body with period 5;
- the 400 for a bad `dates`;
- the PNG is valid;
- script overrides and `situation: null`, plus the config rejections;
- `--sports` without `--sports-nfl` serves no NFL route, and **the NHL golden test passes unchanged**.

---

## 10. Tests

### 10.1 `:core`

- **`EspnMapperTest`**, on the fixtures:
  - every row of §1.3, each fixture of §1.2;
  - the week key and the calendar neighbours;
  - "1st & Goal" from the text and from the numbers;
  - `possessionText` → OWN/OPP; the kick-off field from the home-relative `yardLine` for a home and an away kicker;
  - a missing `situation`, a bad `down`, a string score, and an unknown status name while `in`;
  - a dropped bad event keeps the board.
- **`ScoreBugMachineTest`** (NFL):
  - the centre table of §3.1, including no EndOf at 0:00 in play;
  - the gating of §3.3 (stale, waiting, breaks, Final);
  - every row of §3.2, including `pendingTry`'s set and clear, the 2-point tie-break, and silent 4/5/9 rises;
  - the possession inference (after a kick-off; held; none);
  - `Dead` after a TD → PAT, after a FG (`scoreboard-live-fg-dead-ball-2026-11-08.json`) → no PAT and the tab held;
  - the delay;
  - `reset()` clears `pendingTry`;
  - **the NHL tests pass unchanged.**
- **`ScoreBugPlacementTest`:** NFL boxes for 4 corners × 2 sizes, `tabBox` and `tabAllowed` with each overlay at 28
  and 30 px, reach ≤ `M`; the NHL results identical.
- **`NflTeamColoursTest`:** the §6 table pinned (base, accent, glow, pop, tab); C1 ≥ 4.5 except `C1_EXCEPTIONS`; C5
  ≥ 4.5 for all; the NHL/NFL collision (`TeamColourTable.of(NFL, "BUF")` ≠ `of(NHL, "BUF")`).
- **`NflScoreTimelineTest`:**
  - every instant of §5.1 against a reference bezier (1e-3);
  - the 450 ms grid (no two flash peaks closer than 450 across panel, posts and tab);
  - ball vs arc by kind; the letter stagger for each word;
  - the still frames;
  - `sample` allocates nothing (10 000 calls, delta < 1 KB).

### 10.2 `:ui`

- **`NflScreenshotTest`** (native graphics, through `NflBugPainter.drawFrame`):
  - the plain bug in each §4.2 state;
  - the tab for BALL, KICKOFF and EXTRA POINT, top and bottom corners, Standard and Large;
  - takeover frames at 0, 216, 400, 700, 1 000, 1 400, 1 800, 2 500, 3 168, 3 600, 4 000 and 4 500 for TD, XP, FG,
    SAFETY and TWO-POINT;
  - teams KC, BUF, CIN, LV and DAL, over dark video and white;
  - **asserts:** no tab pixel inside the pill; no ball or post pixel outside the round rect; nothing outside pill + `M`;
    the tab far stop ≈ `mixBlack(base, .10)`; C5 measured from pixels; at rest the frame equals the plain bug.
- **Allocation:** 300 draws mid-takeover with the tab pulsing, delta < 4 KB.
- **`NflBugDrawTest`** (compose test clock):
  - one recomposition per snapshot, none during a takeover;
  - the tab kicks only on identity changes (a yard-line change never kicks);
  - a side change is CLOSE then OPEN;
  - the pin and release at 3 600, also at `MotionDurationScale` 2;
  - the spoiler hold before 216;
  - pinTabOn false keeps the tab closed;
  - the still path;
  - a cancel snaps;
  - the live region.
- **`SportsScreenTest` / `PlayerStateMachineImplTest`:** the league chips and the picker League row; the switch
  persists; the board reloads; the followed row stays across a switch; the week chips; "Sun 1:00 PM";
  `GameCardFitTest` with the NFL names.

### 10.3 `:data`

- **`EspnScoreSourceTest`** (MockWebServer):
  - the paths per call; the ET date of `game()`;
  - the event filter, and NotFound when it is absent;
  - the 1 MiB cap (the fake's `pad_bytes`);
  - an HTML 404; a 400; no `Age` gives 0;
  - a streaming decode of the full real week body (the 272 KB pre-game body, generated in the test by padding the
    trimmed fixture);
  - the logo URL validation.
- **`ScoreRepositoryImplTest`:**
  - a league switch resets the day list to the new league's today;
  - the follow polls its own league's source;
  - an NFL follow replaces an NHL one;
  - nothing is fetched with nothing followed and Sports closed (unchanged).

### 10.4 Instrumented

`GoalFramesTest` gains an NFL window (`--sports --sports-nfl`). It arms on the featured game's score change through
`/admin/sports-nfl` and measures a TOUCHDOWN takeover with the GA §8.4 gates. The device run is open.

---

## 11. Work packages (worktrees; commit 0 by the lead, then WP-1…WP-5 in parallel with disjoint files)

### Commit 0 (lead, Opus). Gate: `./gradlew :core:test :ui:testDebugUnitTest :data:testDebugUnitTest :app:assembleDebug lintAll`

| File | Change |
|---|---|
| `core/.../sports/SportsContracts.kt` | §2: `League.NFL`, `LeagueDetail`, the `Nfl*` types, `GameSnapshot.detail`, `ScoreBugView.league` / `.nfl`, `CentreText.Halftime`, `PlacementInput.league` |
| `core/.../sports/SportsPrefs.kt` | `league = NHL` |
| `core/.../sports/SportsConstants.kt` | §12 |
| `core/.../sports/TeamColourTable.kt` (new) | `of(league, abbrev)` |
| `core/.../sports/NflTeamColours.kt` (new) | stub: `of`, `FALLBACK`, empty `TABLE`, the exception sets |
| `core/.../sports/EspnMapper.kt` (new) | stub signatures: `week(json…)`, `board(…)`, `game(…)`, `weekKey(…)`, `isoEtDay(…)` |
| `core/.../sports/NflScoreTimeline.kt` (new) | `NflScoreFrame` (every field, letter arrays of `NFL_LETTERS_MAX` 12, `copyFrom`), `sample(kind, tMs, out)`, `sampleStill(...)` = `TODO()` |
| `core/.../sports/ScoreBugPlacement.kt` | `width(size, league)` and `tabInset(size, league)` overloads; the NFL branch stubbed to the NHL values |
| `core/.../full/PlayerContracts.kt` (`PickerChoice`) | `ScoreLeagueRow(league)` |
| `core/src/testFixtures/.../FakeScoreRepository.kt` (`SportsFixtures`) | `nflGame(…)`, `nflView(…)`, `detail` and `league` passthrough |
| `core/src/test/resources/sports/nfl/` | the fixtures (already written by the proposer: §1.1) + `live-sample-2026-11-08.log` |
| `data/.../sports/EspnScoreSource.kt` (new) | stub `ScoreSource` returning `Failed(HTTP_OTHER)`; `EspnEndpoints` |
| `data/.../sports/SportsModule.kt` | `@IntoMap @LeagueKey` for both sources; `ScoreRepositoryImpl` takes `Map<League, ScoreSource>` (the NHL behaviour identical) |
| `data/.../sports/ScoreRepositoryImpl.kt` | the constructor change only (the map; the day source from `prefs.league`): **the lead does this in commit 0**, because it is small and every WP compiles against it |
| `ui/.../sports/ScoreBug.kt` | the league seam: pill size, glass and painter chosen by `view.league`; the NFL path calls `NflBug(...)` (a stub that draws the glass); `GoalHooks.colours: (League, String?)` |
| `ui/.../sports/NflBug.kt`, `NflBugPainter.kt`, `NflScorePainter.kt`, `NflTabPainter.kt` (new) | stubs with the final signatures |
| `ui/src/main/res/values/strings_sports.xml` | §7.3 strings |
| `tools/fakepanel/fakepanel.py` | `--sports-nfl`, the defaults and the route dispatch to `sports_nfl.handle` (a stub module answering 404) |

After commit 0, every file below belongs to exactly one WP.

### WP-1: core (Opus)

| File | Change |
|---|---|
| `core/.../sports/EspnMapper.kt` | §1.3 |
| `core/.../sports/ScoreBugMachine.kt` | §3 (the NFL branch, the score kind, `pendingTry`, gating, inference) |
| `core/.../sports/ScoreBugPlacement.kt` | the NFL widths, `tabBox`, `tabAllowed` |
| `core/.../sports/NflTeamColours.kt` | §6 |
| `core/src/test/.../EspnMapperTest.kt`, `ScoreBugMachineNflTest.kt`, `ScoreBugPlacementTest.kt`, `NflTeamColoursTest.kt` | §10.1 |

### WP-2: data (Opus)

| File | Change |
|---|---|
| `data/.../sports/EspnScoreSource.kt` | §1.5: transport, streaming DTOs, cap, logos, override |
| `data/.../sports/ScoreRepositoryImpl.kt` | the league switch reset; the follow's source by league |
| `data/src/test/.../EspnScoreSourceTest.kt`, `ScoreRepositoryImplTest.kt` | §10.3 |

### WP-3: timeline and painters (Opus for the timeline, Sonnet for the painters)

| File | Change |
|---|---|
| `core/.../sports/NflScoreTimeline.kt`, `core/src/test/.../NflScoreTimelineTest.kt` | §5.1, §5.3, §5.6 |
| `ui/.../sports/NflBugPainter.kt` | the body (§4.1, §4.2) and `drawFrame` (§5.5) |
| `ui/.../sports/NflScorePainter.kt` | the takeover (§5.2, §5.5): atlas, sprites, glow masks |
| `ui/.../sports/NflTabPainter.kt` | the tab (§4.3, §5.3 ADD pulse) |
| `ui/src/test/.../NflScreenshotTest.kt` | §10.2 |

### WP-4: overlay wiring (Sonnet)

| File | Change |
|---|---|
| `ui/.../sports/NflBug.kt` | `NflRun` (the GA trigger), `NflTabRun` (PP rules), pin and release, bakes, the live region and `Describe` |
| `ui/.../sports/ScoreBug.kt` | only inside the commit-0 seam (the NFL call); nothing in the NHL path |
| `ui/src/test/.../NflBugDrawTest.kt`, `app/src/androidTest/.../GoalFramesTest.kt` | §10.2, §10.4 |

### WP-5: league switch UI, picker, fake panel (Sonnet)

| File | Change |
|---|---|
| `ui/.../sports/SportsScreen.kt`, `SportsViewModel.kt`, `GameCard.kt` | §7 |
| `core/.../full/state/PlayerStateMachineImpl.kt` | the League row, the week divider |
| `ui/src/test/...` Sports and picker tests, `GameCardFitTest` | §10.2 |
| `tools/fakepanel/sports_nfl.py`, `test_sports_nfl.py`, `sample_espn.py`, `README.md` | §9, §1.6 |

**Gates:**
- Every WP ends with `./gradlew :core:test :ui:testDebugUnitTest :data:testDebugUnitTest :app:assembleDebug lintAll`.
- WP-5 also runs `python3 -I -m unittest tools/fakepanel/test_sports.py tools/fakepanel/test_sports_nfl.py`, and the
  NHL golden must pass.
- The feature ends with a code review, the emulator checklist (§13) and the plan §6 release gates.

---

## 12. Constants (`SportsConstants`; Standard design px, Large × 1.25 unless stated)

| Name | Value |
|---|---|
| `NFL_SCORE_W` / `NFL_SCORE_LARGE_W` | 520 / 650 (height and radius: the NHL's) |
| `NFL_SCORE_DIGITS_W` / `NFL_DOWN_W` | 50 / 111 |
| `NFL_CLOCK_PX` (700) / `NFL_QUARTER_PX` (600, α .70) / `NFL_DOWN_PX` (700) / `NFL_ONE_LINE_PX` | 26 / 20 / 22 / 24 |
| `NFL_DOWN_FIT_AIR` | 6 |
| `NFL_PRE_WINDOW_MS` | 3 600 000 |
| `ESPN_MAX_BODY_BYTES` | 1 048 576 |
| `ESPN_LOGO_PX` | 200 |
| `NFL_TAB_INSET` | 37 (Large 46); depth, hidden, radius, shadow, type and gaps: the `PP_*` values |
| `NFL_TAB_ICON` / `_STROKE` | 20 / 1.4 |
| `NFL_TAKEOVER_MS` / `NFL_POP_MS` | 3 600 / 900 (the GA values; the mark stays `GOAL_MARK_MS` 4 500) |
| `NFL_FLASH_GRID_MS` / `NFL_FLASH_START_MS` / `NFL_FLASH_CYCLES` / `NFL_FLASH_ALPHA` | 450 / 450 / 3 / .42 |
| `NFL_POSTS_IN_MS` / `_IN_DELAY_MS` / `_HIT_START_MS` / `_HIT_CYCLES` / `_ALPHA` / `_HIT_ALPHA` | 400 / 200 / 1 800 / 3 / .4 / .95 |
| `NFL_TAB_PULSE_CYCLES` / `_GAIN` | 8 / .45 |
| `NFL_STREAK1_W` / `NFL_STREAK2_W` / `NFL_STREAK_FROM` / `_TO` / `NFL_STREAK2_START_MS` | 51 / 19 / −85 / 616 / 1 800 |
| `NFL_BALL_PX` / `NFL_RUN_FROM` / `_TO` / `_Y` / `_ROT` / `_ALPHA` / `NFL_RUN_START_MS` / `_MS` | 34 / −42 / 562 / 18 / 900° / .4 / 300 / 1 300 |
| `NFL_ARC_P0` / `_P1` / `_P2` / scales / rotations / `_ALPHA` / `NFL_ARC_MS` | (21, 44) / (202, −7) / (366, 12) / 1, .75, .45 / 0, 360, 720 / .55 / 1 400 |
| `NFL_POSTS_X` / `_Y` / `_W` / `_H` / `_DY` | 355 / 9 / 43 / 52 / 8 |
| `NFL_WORD_PX` / `NFL_WORD_TRACK_EM` / `NFL_LETTER_STAGGER_MS` / `NFL_LETTER_DROP` / `NFL_WORD_PULSE_START_MS` / `NFL_WORD_PULSE` | 48 / .03 / 55 / 22 / 1 400 / 1.05 |
| `NFL_LOGO_START_MS` / `_MS` / `_DX` / `_FROM_SCALE` / `_FROM_DEG` / `_MID_DX` / `_MID_SCALE` / `_MID_DEG` | 450 / 600 / −32 / .4 / −25 / 2 / 1.1 / 4 |
| `NFL_POINTS_PX` / `NFL_TALLY_PX` / `NFL_BLOCK_W` / `NFL_POINTS_START_MS` / `_MS` / `_DX` | 32 / 20 / 74 / 1 100 / 500 / 19 |
| `NFL_POP_FROM_SCALE` | 1.5 |
| `NFL_LETTERS_MAX` | 12 |

---

## 13. Emulator verification checklist (`TheBox_TV_API30`, `python3 tools/fakepanel/fakepanel.py --port 8080 --sports --sports-nfl`, debug `files/debug/sports_base`)

1. Sports tab: NHL / NFL chips. Pick NFL; restart the app; NFL is still selected. Week 9-style board with Thursday
   final, the featured game, Sunday and Monday cards ("Sun 4:25 PM", "Mon 8:15 PM").
2. Follow KC @ BUF before the start: the toast; no bug in FULL until the coin toss (ruling 4).
3. Coin toss: the bug at top right, "1st / 15:00", no tab, a blank down column.
4. KC drive: the tab slides out "KC BALL | OWN 25"; "3rd & 2" and "OPP 38"; "1st & Goal". The clock steps per window,
   never counting down between polls.
5. The 10 s score-before-play window (seen or not, depending on the poll phase), then the **TOUCHDOWN** takeover:
   - the ball run, the letters, the logo, "+6" and the tally "6 – 0" (the score at the TD, before the try);
   - the pill glows; the tab "TOUCHDOWN | KC +6" pulses in KC red;
   - then the pop, then "KC BALL | EXTRA POINT" with "PAT".
6. **EXTRA POINT** (arc and posts), then "KC KICKOFF | OWN 35" and "KICKOFF".
7. BUF ball with no possession for 10 s: the tab closes then opens in BUF blue, with no flicker back to KC.
8. "End 1st" across both columns; the tab closed. Q2: FIELD GOAL; the **+7** takeover. "Halftime".
9. Q3: SAFETY (a side change at the free kick); TOUCHDOWN by BUF (defensive); TWO-POINT. Q4: FG; SAFETY by KC; the
   16–16 tie; "End 4th".
10. OT: "OT"; the walk-off TOUCHDOWN on the Final body, then "Final/OT", the tab closed, and the bug gone 5 min later.
11. All four corners and Large, with a goal in each: the tab opens upward at the bottom corners; the info bar lifts a
    bottom bug; the picker hides the right corners; subtitles move a bottom bug to the top.
12. Quick bar → Score bug: the League row toggles NHL/NFL; the followed NFL game stays on top while NHL is listed.
13. Developer options → animator duration scale off: the still version (frame A, the pop colour, plain).
14. A user delay of 30 s: everything shifts 30 s, including the takeovers and the tab.
15. `--sports` alone (no `--sports-nfl`): the NHL behaves exactly as before; the NFL tab shows "ESPN did not answer".

**Device items:**
- frame time of a takeover on a Fire TV Stick (ruling 20);
- VoiceView;
- the colours on a real TV (dark tabs: CHI, LV, NO, PIT);
- the LV dark logo legibility;
- the live samples of §1.6.

---

## 14. Open questions for the owner

1. **TD and try in one update (+7 or +8).** This happens: ESPN sent CIN's touchdown and extra point as a single
   0 → 7 update in the live sample. Proposed: one TOUCHDOWN takeover that says "+7" (or "+8"), so the numbers match
   the score. The alternative is TOUCHDOWN "+6" and then EXTRA POINT "+1" back to back (about 9 s, and
   the second animates a kick the viewer may not have seen).
2. **Red zone.** Proposed: nothing extra, as in the mock. The alternative is the tab's right half in red with
   "RED ZONE" when the offence is inside the 20.
3. **Four colour picks** where the first two colours on teamcolorcodes are not the obvious pair:
   - BAL purple + gold (not black, which would make its glow invisible);
   - DAL navy + silver (not royal + navy);
   - JAX teal + gold (not black + gold);
   - TB red + pewter (not red + orange).

   Each is a one-line change.

---

## 15. Deviations from the mock (recorded)

| ID | Mock | Here | Why |
|---|---|---|---|
| D1 | 980 × 144 pill, 74 px score, 30 px down, 26 px quarter | 520 × 70, the NHL's type, down 22 | one bug family; the 18 px floor at TV size |
| D2 | BUF pop `#4f8bff` | the shared pop rule (`#E0798D`) | ruling 12 and one rule for 64 teams |
| D3 | flash 4 × 300 ms, posts 3 × 300 ms | one 450 ms grid | under 3 flashes/s (GA B3) |
| D4 | team-name line 15 px, tally "KC 24 · BUF 17" at 14 px | name dropped; tally "24 – 17" at 20 | ruling 7's rule; the type floor |
| D5 | the tab gradient's far stop = base | `mixBlack(.10)` | ruling 17, one gradient for both leagues |
| D6 | the pill glow fixed KC red for both teams | the scorer's `glow` | ruling 12 (as GA N2) |
| D7 | the tab is instant on every change | slide in and out at breaks and on a possession change (PP track); cuts otherwise | the tab is otherwise a static strip; a turnover should read |
