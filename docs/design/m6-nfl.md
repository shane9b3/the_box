# NFL score bug: design

Status: **signed off by the lead** (2026-11-08); see "Lead sign-off" at the end.
- Proposed 2026-11-08 (proposer, Opus).
- Reviewed 2026-11-08 (`docs/review/2026-11-08-nfl-design-review.md`: B1–B5, S1–S9, N1–N10).
- Revised the same day under owner rulings 25–28 and the review. Every item's disposition is in the "Review
  reconciliation" section at the end.

Owner request and rulings:
- `docs/decisions/2026-11-05-nhl-score-bug.md`, rulings 21–24 ("Owner rulings: NFL bug") and 25–28 (the answers to
  this design's questions: two takeovers for +7/+8, RED ZONE on the tab, the four colour picks, the ESPN week).
- Rulings 1–20 still apply where they fit. The ones this design leans on:
  - 1 (live by default, user delay);
  - 4 (nothing before kick-off: the bug appears at the opening kick-off, not at the coin toss; §1.3);
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
- league-keyed lookups (sizes, colours, painters);
- one branch at the top of `ScoreBug(...)`, so the NHL composable `ScoreBugWith` is byte-unchanged (§4.6).

**Checkable:** after the feature, `git diff main -- '*Test.kt'` shows no change to any existing NHL test case. The
NHL-facing signatures stay source-compatible (§2, S7), and new cases go into new NFL test files.

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
   - 2 → TWO-POINT when that side has a try pending (§3.3's set/clear rules), else SAFETY.
   - **7 or 8 in one update → two takeovers back to back** (ruling 25): TOUCHDOWN +6, then EXTRA POINT +1 or
     TWO-POINT +2, each with its own tally and pop, as if the updates had arrived separately (§5.7). This is common:
     the feed delivered CIN's TD and XP as one +7 update after a 105 s freeze (§1.2).
   - Any other rise updates the score with no takeover.
4. **The pill is 520 × 70** (Large 650 × 88). It has the NHL's segments plus a 111 px down column after the centre.
   The quarter sits over the clock, as in the mock. Breaks and Final use one line across the centre and down columns
   (§4.1, §4.2).
5. **The possession tab is the power-play tab's geometry and track, with NFL content** (§4.3):
   - the content is "KC BALL | OWN 35", "KC BALL | EXTRA POINT" or "KC KICKOFF | OWN 35";
   - it is always on during play (ruling 22);
   - it is closed in breaks, at Final, while stale or waiting, and when the possession is unknown;
   - a possession change is CLOSE then OPEN (PP §3.1), and a field-position change is a plain cut;
   - bottom corners open it upward (ruling 16);
   - inside the opponent's 20 on a real down, the tail half turns red and reads "RED ZONE" (ruling 26, §4.5), with a
     250 ms cross-fade and never during the try or the kick-off.
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
   - The tab uses the team's **base** colour (BUF blue, KC red), set by `NflTeamColours` after the shared `derive`,
     which stays untouched (§6).
   - C1 exceptions under ruling 12: CIN, DEN, MIA, CAR, LAC. C5 passes for all 32.
   - The four picks are owner ruling 27 (BAL purple + gold, DAL navy + silver, JAX teal + gold, TB red + pewter).
9. **League switch** (ruling 24): `SportsPrefs.league` (remembered, backed up) chooses which league the Sports tab
   and the picker list. NHL / NFL chips sit at the top. There is one followed game, of either league (§7).
10. **An NFL "day" is the ESPN week** (ruling 28; 3 am ET boundaries from the calendar's own dates). The chips read
    Last week · This week · Next week, so Thursday, Sunday and Monday games are on one list. Week keys (`w2026-2-9`)
    can never be read as dates, and a missing neighbour disables its chip (§7.2).
11. **The fake panel** gets a new module `tools/fakepanel/sports_nfl.py` behind `--sports-nfl`. Its 985 s timeline
    covers every state and scoring kind, with every window ≥ 25 s except one deliberate 10 s window. `sports.py` is
    untouched, so the NHL golden is byte-identical by construction (§9).
12. **Commit 0 fixes every cross-WP signature** and builds green: the contracts, the lenient ESPN DTO and the mapper
    signature, and the league branch at the top of `ScoreBug(...)`. Then five WPs run in parallel worktrees with
    disjoint files (§11).
13. **Field position is resolved late** (B1): the mapper records whose half the ball is in; `:core` turns it into
    OWN / OPP after the possession is known or inferred (§3.4).

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
| `leagues[0].calendar` lists each season type's weeks with `label` ("Week 9"), `value`, `startDate`/`endDate`. Regular weeks run Wednesday 3 am ET to the next Wednesday 2:59 am ET (week 9: `2026-11-04T08:00Z`–`2026-11-11T07:59Z`, in EST). Week 1 is longer (`2026-09-06T07:00Z`–`09-16`, "Sep 6-15"). The app reads the boundaries from these fields and never computes them | **Verified** | week fixture |
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
| 14:35:21 | 15:00 | 0–0 | down 0, dist 0, yl 0, no possession | 70 Coin Toss | `in`, before the kick-off: mapped to **`PRE`, no bug** (ruling 4, §1.3) |
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

**The boundary (B3).** Commit 0 defines both the DTO and the mapper signatures in `:core`. WP-2 only streams bytes
into the DTO; WP-1 only maps the DTO. `EspnMapperTest` decodes the fixtures through the same DTO.

```kotlin
// core/.../sports/EspnDto.kt (commit 0). Only the spine is typed; every leaf the bug reads is a JsonElement?.
@Serializable class EspnBoardDto(val leagues: List<EspnLeagueDto>? = null, val season: JsonElement? = null,
                                 val week: JsonElement? = null, val events: List<EspnEventDto>? = null)
@Serializable class EspnGameDto(val events: List<EspnEventDto>? = null)          // game(): no leagues/calendar (N10)
@Serializable class EspnLeagueDto(val calendar: JsonElement? = null)
@Serializable class EspnEventDto(val id: JsonElement? = null, val date: JsonElement? = null,
                                 val competitions: List<EspnCompetitionDto>? = null)
@Serializable class EspnCompetitionDto(val competitors: List<EspnCompetitorDto>? = null,
                                       val status: JsonElement? = null, val situation: JsonElement? = null)
@Serializable class EspnCompetitorDto(val id: JsonElement? = null, val homeAway: JsonElement? = null,
                                      val score: JsonElement? = null, val team: JsonElement? = null)

object EspnMapper {
    fun board(dto: EspnBoardDto, serverDateMs: Long?, logoBase: String): DayBoard?
    fun game(dto: EspnGameDto, id: GameId, serverDateMs: Long?, logoBase: String): GameSnapshot?
    fun weekKey(year: Int, type: Int, week: Int): String     // "w2026-2-9"
    fun parseWeekKey(key: String): Triple<Int, Int, Int>?    // the one parser (S6)
}
```

- Leaves are read with lenient helpers, as `NhlMapper.primitive` does: a string, a number or a missing value all
  work, and anything else is null **for that field only** (ruling 21). So `possession` sent as a number, `clock` as a
  string, or `lastPlay` as an array cannot fail the body.
- A spine that changes type (`events` not a list) fails the decode: `BAD_BODY`, the existing stale path.

**Board.** Each event becomes one `GameSnapshot`. An event that fails to map is dropped from the board; the board
itself fails only when `events` is missing.
- `dayKey`: the week key `w{year}-{seasontype}-{week}` ("w2026-2-9", S6). The `w` prefix means no week key can ever
  be read as a date. For a dated body it is the ET date ("2026-11-08").
- `prevDay`/`nextDay`: the neighbouring calendar entries, or null at an edge or in the off-season (§7.2).

**Status** (`state`, then `name`):

| `state` | `type.name` | `GamePhase` | `NflBreak` | Centre (§4.2) |
|---|---|---|---|---|
| `pre` | any | `SCHEDULED`, or `PRE` within `NFL_PRE_WINDOW_MS` (60 min) of the start by the body's server `Date` | — | bug hidden (ruling 4) |
| `pre`/`post` | `STATUS_POSTPONED`, `STATUS_CANCELED` | `POSTPONED` | — | removed (SB §2.6) |
| `in` | any, **before the opening kick-off**: `period == 1`, both scores 0, and `lastPlay` absent or of type 70 (Coin Toss) | **`PRE`** (S8) | — | **no bug** (ruling 4) |
| `in` | `STATUS_IN_PROGRESS` and any unknown `in` name (a weather delay) | `LIVE` | — | quarter over clock |
| `in` | `STATUS_END_PERIOD` | `INTERMISSION` | `END_PERIOD` | "End 1st" … "End 4th", "End OT" |
| `in` | `STATUS_HALFTIME` | `INTERMISSION` | `HALFTIME` | "Halftime" |
| `post` | anything else | `FINAL`; `finalKind = OT` if `period ≥ 5` | — | "Final" / "Final/OT" |
| missing or unknown `state` | | the event is dropped (a `BAD_BODY` for `game()`) | | |

**The kick-off rule (S8, ruling 4).**
- ESPN goes `in` at the coin toss, about 45 s before the kick-off (14:35:21 in the log).
- The bug appears with the **first logged play after the coin toss**: the first `in` body whose `lastPlay` is
  present and is not type 70, or whose score is not 0–0, or whose period is past 1.
- In the log that is 14:36:07 (`lastPlay` 53 Kickoff). The coin-toss fixture maps to `PRE`, and the first-down
  fixture maps to `LIVE`.
- `PRE` polls at the machine's pre-game cadence (every other bucket from start − 2 min), so the bug can appear up to
  about 40 s after the kick-off. Accepted: ruling 4's spirit is "nothing before the game starts", and the first play
  takes longer than that.
- On the Sports card the game reads as starting (`PRE`), not LIVE, for those 45 s.

**Period.**
- Periods 1–4 are `PeriodKind.REG` with number = period, labelled "1st" … "4th".
- Period ≥ 5 is `PeriodKind.OT`, with `otNumber = period − 4` ("OT", "2OT" in the playoffs).

**Clock.** `GameClock(secondsRemaining = floor(status.clock) or parse(displayClock), running = false,
inIntermission = phase == INTERMISSION)`. ESPN has no running flag; the NFL branch never reads `running` (§3.1).

**Teams.**
- `TeamLine(abbrev, name = displayName, score = score.toIntOrNull(), logo, shortName = shortDisplayName)`. The score
  is null before `in`, because ESPN sends "0" pre-game.
- `shortName` (S5, new and defaulted to null, so it is always null for the NHL) is the card name ("Commanders") and
  the short name in the live region and `Describe` ("Chiefs 13, Bills 7").
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
- `field` (a `FieldPos(territory, yard)`: **whose half the ball is in**, never OWN/OPP; B1):
  1. **from `possessionText`** `^([A-Z]{2,3}) (\d{1,2})$`: the territory is the competitor whose
     `team.abbreviation` equals the prefix. This does not depend on `possession`, which ESPN omits after every
     kick-off. A yard of 50 gives `territory = null` (midfield).
  2. else **from `yardLine`**, which is home-relative: `yl < 50` → (HOME, yl); `yl > 50` → (AWAY, 100 − yl);
     `yl == 50` → (null, 50). This is how a kick-off gets its field: the kick-off bodies have no `possessionText`.
  3. else null.

  OWN / OPP / MIDFIELD is decided in `:core` from the territory and the tab's side, after the possession is known or
  inferred (§3.4).
- `redZone`: `isRedZone == true` and `play is Down` (the machine adds its own checks, §4.5).
- `breakKind`: as in the status table.
- `lastPlay`: `NflLastPlay(id, typeId, typeText, side (from team.id), scoreValue)`, or null.

**Defensive mapping.** Anything not matching its shape becomes null for that field only (ruling 21). Every field is
optional except `id`, `date`, `status.type.state` and the two competitors with `homeAway`.

### 1.4 Missing or flaky, and how the bug degrades (ruling 21)

| Missing or odd | Effect |
|---|---|
| `situation` absent while `in` | no down column, tab closed; score, clock and quarter as usual |
| `possession` absent with a real down (seen after every kick-off return) | the machine infers it (§3.4): the `lastPlay` Kickoff's team (the receiver), else the other side of the last kick-off, else the previous possession; otherwise the tab stays closed. The field is still right, because the territory comes from `possessionText` (B1) |
| a leaf re-typed by ESPN (a number for a string, an array for an object) | null for that field only (§1.3 lenient helpers) |
| `possessionText` absent | the tab shows "KC BALL" alone, centred (as PP's label with no seconds) |
| `down`/`distance` out of range | down column blank |
| `lastPlay` stale or absent | nothing depends on it (§3.2) except the try's side, which the machine has anyway |
| a half-updated body (the clock ahead of the situation) | shown as is; the next poll fixes it (ruling 6: the feed is the truth) |
| `score` not an integer | that side's score is null, and no takeover can flag |
| `status.clock` absent | `displayClock` parsed; if neither is present, no clock (a one-line quarter) |
| an unknown `type.name` while `in` | `LIVE` |
| the body over `ESPN_MAX_BODY_BYTES`, non-JSON, or an HTML 404 | `Failed(BAD_BODY / HTTP_OTHER)`: the existing stale and hide path, with "ESPN did not answer" in Sports |
| the event missing from its dated board (a game re-dated for weather, N7) | `game()` retries once against the current week board before answering. If the event is there, its snapshot is returned (and its new start rewrites the follow, as today). Only an event missing from both is `NotFound`; two of them 20 s apart remove the follow (SB §2.6, "ESPN no longer lists KC – BUF") |
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
  | `game(id)` | `scoreboard?dates={the game's ET date}`, filtered to `id`; if absent, once more against the week board (N7) |

  The ET date comes from the event's own `date`, which the source remembers per id from every body it maps (the
  week list, an earlier `game()`). The `ScoreSource` interface is unchanged. With nothing remembered (after an app
  restart), `game()` reads the current week board first and remembers the date from it.
- **Timing.** `Age` is absent, so `ageS = 0`. `SportsTiming.nextAligned` then puts the next live poll at
  `received + 20 s + POLL_PHASE_MS`, about every 21.5 s. ESPN's `max-age` is ≤ 10 s, so each poll gets a fresh body.
  **No machine or policy change.** (10 s polling would halve the lag at twice the traffic; not proposed.)
- **Traffic:** about 21 KB gzip per poll, about 3.6 MB per hour of a followed live game. Nothing is fetched with
  nothing followed and Sports closed (SB §2.3, unchanged).
- **Body cap:** `ESPN_MAX_BODY_BYTES = 1 MiB`, because the week board is already 272 KB pre-game. The NHL keeps its
  512 KiB.
- **Decode by streaming** (the project's streaming-parser rule):
  - `Json { ignoreUnknownKeys = true }.decodeFromStream(EspnBoardDto.serializer(), source.inputStream())` (or
    `EspnGameDto` for `game()`, which skips the 4 KB calendar; N10), into the commit-0 DTO of §1.3;
  - `leaders`, `odds`, `headlines`, `broadcasts` and the rest are skipped by the reader and never built;
  - the leaves are `JsonElement` subtrees, but only small ones (`status`, `situation`, `team`, `score`);
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
/** (B1) Whose half the ball is in ([territory] null = midfield, yard 50); [yard] 1..50. Never OWN/OPP. */
data class FieldPos(val territory: Side?, val yard: Int)
data class NflLastPlay(val id: String, val typeId: String?, val typeText: String?, val side: Side?, val scoreValue: Int?)

TeamLine(…, val shortName: String? = null)                                    // (S5) NFL "Chiefs"; always null for the NHL
GameSnapshot(…, val detail: LeagueDetail? = null)

// ---- what the bug draws (machine output, §3)
enum class NflScoreKind { TOUCHDOWN, EXTRA_POINT, FIELD_GOAL, SAFETY, TWO_POINT }   // append-only
/** One takeover: its kind, its points (6, 1, 3, 2) and the score after it (the takeover's tally). */
data class NflScore(val kind: NflScoreKind, val points: Int, val away: Int, val home: Int)
sealed interface DownText {
    data class Down(val down: Int, val distance: Int?, val goal: Boolean) : DownText   // "1st & 10", "4th & Goal", "3rd"
    data object Pat : DownText
    data object Kickoff : DownText
}
enum class TabLead { BALL, KICKOFF }
enum class FieldZone { OWN, OPP, MIDFIELD }
sealed interface TabTail {
    data class Field(val zone: FieldZone, val yard: Int) : TabTail           // "OWN 35", "OPP 12", "MIDFIELD"
    data object ExtraPoint : TabTail
    data object RedZone : TabTail                                            // (ruling 26) "RED ZONE" on red
}
data class NflTab(val side: Side, val lead: TabLead, val tail: TabTail?)
/** [scores]: empty, one score, or two for a +7/+8 update (ruling 25), in play order; set with the goal mark. */
data class NflView(val down: DownText?, val tab: NflTab?, val scores: List<NflScore> = emptyList())

ScoreBugView(…, val league: League = League.NHL, val nfl: NflView? = null)
CentreText.Halftime                                                           // new data object, one line
PlacementInput(…, val league: League = League.NHL)
SportsPrefs(…, val league: League = League.NHL)                               // the shown league (ruling 24)
```

- **Strings stay in `:ui`.** `:core` emits enums and ints, as `CentreText` does.
- **`CentreText`** gains only `Halftime`. The `when` in `SportsText.centre()` is exhaustive, so commit 0 adds its
  branch (S4).
  - The NFL reuses `Period(label)` (REG 4 = "4th": new string `sports_period_4`, so the NHL's `sports_period_n` "P4"
    is untouched), `EndOf(label)`, `Final(kind)`, `NoUpdate` and `Delayed`.
  - The **layout** (two lines quarter-over-clock, or one line across two columns) is the NFL painter's (§4.2).
- **The goal mark** (`goalSeq`, `goalSide`) flags the NFL score as it does the NHL goal. `nfl.scores` carries the
  kinds and tallies for the length of the mark.
- **Colour lookup** (S7):
  - `TeamColourTable.of(league, abbrev)` delegates to `NhlTeamColours.of` or `NflTeamColours.of`;
  - `GoalHooks.colours` stays `(String?) -> TeamColours` (the NHL path never needs a league);
  - `NflBug` has its own `NflHooks.colours = NflTeamColours::of`.
- **The repository** (S7) gains a primary constructor taking `Map<League, ScoreSource>`. A secondary constructor
  `ScoreRepositoryImpl(source: ScoreSource, …)` wraps `mapOf(source.league to source)`, so the three existing calls in
  `ScoreRepositoryImplTest` compile unchanged.
- **`SportsText`** (S4) functions that depend on the league (`period`, `state`, `failure`, `removed`, `dayLabel`)
  gain a trailing `league: League = League.NHL` parameter. The NHL calls are unchanged.

---

## 3. Machine (`ScoreBugMachine`)

### 3.1 What is reused and what branches

- **Unchanged:** the delay queue, anchors, polling, back-off, demand, stale/hide, NotFound/removal, expiry
  (final + 5 min, start + 8 h), `goalSeq` monotonic and the C-N1 freshness rule (`GOAL_FRESH_MS`).
- **The mark** is `GOAL_MARK_MS` (4.5 s = 3.6 s takeover + 0.9 s pop) for one score, and `NFL_DOUBLE_MARK_MS`
  (9.0 s) for a +7/+8 pair (§5.7).
- **Branch on `game.league == NFL`** in `view()` and `knownView()`:

  | Snapshot | `top` | `bottom` |
  |---|---|---|
  | `FINAL` | `Final(finalKind)` | — |
  | `INTERMISSION` + `HALFTIME` | `Halftime` | — |
  | `INTERMISSION` + `END_PERIOD` | `EndOf(label)` | — |
  | `LIVE` | `Period(label)` | `Clock` |
  | waiting, `LIVE` | `Delayed` | `Clock` |
  | stale, `LIVE` | `NoUpdate` | `Clock` |
  | waiting or stale in a break | `Delayed` / `NoUpdate` (one line) | — |

  - In two-line states the NFL puts the quarter on **top** (as the mock), the reverse of the NHL. `top`/`bottom` keep
    their meaning ("line 1", "line 2"), and the painter reads them in order.
  - **`clockSecs`** (S3) is set when **either** line is `Clock`. The NHL condition, `top == Clock`, is a special case
    of it, so the NHL result is the same.
  - **`knownView()`** (S3), the quick-bar and picker value while the bug is hidden, sets `league`, uses the NFL line
    order (`NoUpdate` over the clock), and a one-line `NoUpdate` in a break, never a frozen 0:00. `nfl` is null there.
  - The NHL's "clock 0:00 and not running → EndOf" rule is **not** applied: ESPN shows 0:00 during untimed downs.
  - The NHL shootout and power-play gates are skipped; `powerPlay` is always null for the NFL.
- **`nfl`** is set on `view()` only. It is non-null only for an NFL game; its fields are gated by §3.4.

### 3.2 Score kind (where a goal is flagged; the NHL rules for when one is flagged are unchanged)

A score is flagged when exactly one side rose against a fresh previous snapshot under `SEEN` demand (as the NHL). Then:

| Rise for side X | `nfl.scores` | Notes |
|---|---|---|
| 6 | [TOUCHDOWN +6] | any TD, including defensive and return TDs (ruling 23) |
| **7** | **[TOUCHDOWN +6 (tally: before + 6), EXTRA_POINT +1 (tally: after)]** | ruling 25: two takeovers back to back (§5.7); mark 9.0 s |
| **8** | **[TOUCHDOWN +6, TWO_POINT +2]** | ruling 25 |
| 3 | [FIELD_GOAL +3] | |
| 1 | [EXTRA_POINT +1] | |
| 2 | [TWO_POINT +2] if a try is pending for X (§3.3), else [SAFETY +2] | the tie-break below |
| 4, 5, ≥ 9, or a fall | no flag | the score just updates (a correction, or several scores in one missed poll) |

- **The correction guard (N5, NFL only).** A rise that returns a side to a score it showed in a snapshot received
  within `QUEUE_KEEP_MS` is not flagged. A reversed TD (14 → 7) then restored (7 → 14) never replays a takeover.
- **The 2-point tie-break.** With no try pending (for example the app started during the try), a `lastPlay` that is
  new since the previous snapshot and belongs to X decides it: `typeText` containing "two-point" or "2pt" →
  TWO_POINT, containing "safety" → SAFETY. Else SAFETY.
- **Known misses** (in the KDoc):
  - a defensive two-point return scored by the defence during the other side's try reads SAFETY;
  - a TD and a missed try followed by a safety inside one gap reads as an unflagged +8 for different sides (a
    two-sided rise is silent);
  - a field goal plus a safety inside one gap (+5) is silent.
- **Why the rise and not `lastPlay`:** at 14:41:58 the score was 0–6 while `lastPlay` was still the earlier penalty,
  and the XP never appeared as a `lastPlay` (§1.2).
- `nfl.scores` is stored with the mark and cleared with it (like `goalPp`).

### 3.3 The try bookkeeping (B2)

`pendingTry: Side?` and `tryAt: (period, clockSecs)?` are machine fields. They are processed for each newly shown
entry, in this order.

**1. Clear**, judged against the state before this snapshot's set:
- any score change for either side, other than the rise of exactly 6 handled in step 2;
- a shown `Kickoff`;
- a shown `Down` whose period or clock differs from `tryAt`. The clock does not run during a try: it read 11:12
  from 14:41:58 to 14:45:48 in the log. A two-point try that ESPN draws as a real down ("1st & Goal at OPP 2")
  therefore does **not** clear it;
- `reset()`, and the "delay grew: back in time" path of `advanceDisplay` (§3.6).

It is **not** cleared by a phase change alone. A TD on the last play of a half has its try after 0:00, and ESPN may
already be at `END_PERIOD` / `HALFTIME` (§1.2: 0:02 went straight to END_PERIOD).

**2. Set** `pendingTry = X`, `tryAt` = this snapshot's (period, clock):
- on **any** rise of exactly 6 for X, flagged or not (UNSEEN demand and stale comparisons included);
- on a shown `Dead` whose `lastPlay` is a TD for X (`scoreValue == 6`), when nothing is pending. This covers an app
  started during the try.

A rise of 7 or 8 sets nothing: the try is over.

**3. Rendering while a try is pending:**
- a `Dead` renders as the try;
- a `Down` **equal to the previous shown `Down`** also renders as the try. That is the stale body that carries the
  score (14:41:58), or the stale body naming the other side after a defensive TD;
- so is a `Down` whose period and clock equal `tryAt` (the two-point try drawn as a down).

The try's rendering is down column "PAT" and tab "X BALL | EXTRA POINT", in X's colours.

**Tests** (B2): replay the log's 14:41:58 → 14:42:44 rows with a +2 instead of the +1:
- once with the try as `Dead`;
- once as a `Down` at the 2 with the clock unchanged.

Both must read TWO-POINT, and both must render PAT from the TD snapshot on. The defensive-TD row of §9.2 must render
"BUF BALL | EXTRA POINT" from its first snapshot.

### 3.4 The down column and the tab (gating, inference, field)

`nfl.down` and `nfl.tab` are non-null only when the view is **fresh** (not stale, not waiting) and the phase is
**`LIVE`**. They are null in breaks, at Final, stale or waiting (ruling 22), as the power-play tab closes on stale
(PP §2). The try exception: a pending try keeps PAT through a break that arrived before it (step 3 above).

| Shown `play` | `down` | `tab` |
|---|---|---|
| rendered as the try (§3.3) | `Pat` | `(pendingTry, BALL, ExtraPoint)` |
| `Down(d, dist, goal, field)` | `Down(d, dist, goal)` | `(poss, BALL, tail(field, poss))`, or `RedZone` (§4.5) |
| `Dead(…)` with no try pending (after a FG or a safety) | blank | **held**: the last shown tab, unchanged, so there is no close/open flicker before the kick-off body one poll later |
| `Kickoff(kicker, field)` | `Kickoff` | `(kicker, KICKOFF, tail(field, kicker))` |
| `None` | null | null |

**Possession inference** (ESPN drops `possession` on the first poll after every kick-off return, §1.2). When a `Down`
has no possession, take the first that applies:
1. the shown `lastPlay`, if its type is 53 (Kickoff) or a kick-return type: its `team` is the **receiver** (N6; in
   `scoreboard-live-no-possession-after-kickoff-2026-11-08.json`, `lastPlay` 53 has `team.id` "1", ATL, which
   received CIN's kick). This gets onside recoveries right;
2. the other side of the previously shown `Kickoff(k)`;
3. the previously shown `Down`'s possession;
4. otherwise the tab is null.

**Field tail** (B1): `tail(field, side)` = `Field(MIDFIELD, 50)` when `field.territory == null`; `Field(OWN, yard)`
when `field.territory == side`; else `Field(OPP, yard)`. It is computed **after** the inference, so the first poll
after a kick-off already reads right. The test: after `scoreboard-live-plus7-away-kickoff`, the no-possession
fixture gives "ATL BALL | OWN 40".

A tab whose side is still null is null. The inferred side is used only for the tab.

### 3.5 Red-zone gating (ruling 26; drawing in §4.5)

The tail is `RedZone` only when every one of these holds:
- the shown play is a real `Down` (1–4), not rendered as the try;
- the tab's side is known (given or inferred);
- `isRedZone` is true;
- the field, if known, is the opponent's territory at yard ≤ 20.

It is never `RedZone` for `Dead`, `Kickoff`, the try rendering, a break or Final. ESPN leaves `isRedZone` true through
the try and the kick-off (§1.2).

### 3.6 Where the NFL state lives

`pendingTry`/`tryAt`, the inference memory (the last shown `Kickoff`/`Down`) and the held tab are written **only in
`advanceDisplay`** when the shown entry changes, never in `view()`. The "delay grew: back in time" path resets all
three (and `ppEndedEntry`, as today). `reset()` clears them.

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
  The NFL bounds and root-tree checks go into a new `NflBoundsTest` (S4/S7), so `RootTreeRuleTest` and
  `ScoreBugBoundsTest` are not edited.

### 4.2 Columns and states

| Column | Content | Type |
|---|---|---|
| Logos, scores | as the NHL | score 40/700 tabular |
| Centre, two lines | **quarter** ("1st" … "4th", "OT", "2OT") 20/600 `Fg .70` on top (box 22), **clock** `m:ss` / `mm:ss` 26/**700** tabular below (box 30); 9 above and below | the mock's order; the clock's weight per the mock |
| Down | "1st & 10", "4th & Goal", "PAT", "KICKOFF" | 22/700 `Fg`, centred; a string wider than 111 − 6 steps 2 px down to 18 ("4th & Goal" → 20, 100.5) |
| One line (Final, Final/OT, Halftime, End 1st…End 4th, End OT) | across centre + divider + down (218), the middle divider hidden | 24/600, centred |

| State | Centre | Down | Tab |
|---|---|---|---|
| `SCHEDULED`, `PRE` (including `in` before the opening kick-off, §1.3) | **no bug** (ruling 4) | | |
| `LIVE`, `None` (no situation) | "1st" / "15:00" | blank | closed |
| `LIVE`, a down | "4th" / "3:42" | "1st & 10" | "KC BALL \| OWN 35" |
| `LIVE`, a down in the red zone | "2nd" / "4:12" | "1st & Goal" | "KC BALL \| RED ZONE" (red tail, §4.5) |
| `LIVE`, the try | quarter / clock | "PAT" | "KC BALL \| EXTRA POINT" |
| `LIVE`, the kick-off | quarter / clock | "KICKOFF" | "KC KICKOFF \| OWN 35" |
| `END_PERIOD` | "End 1st" (one line) | (merged) | closed |
| `HALFTIME` | "Halftime" (one line; fits at 24: 98.2) | (merged) | closed |
| `FINAL` | "Final", "Final/OT" (one line) | (merged) | closed |
| Stale | "No update" over the old clock, dimmed `.55` (in a break: "No update" on one line) | blank | closed |
| Waiting on the delay | "Delayed" over the oldest clock, dimmed (in a break: one line) | blank | closed |

- "Halftime" rather than "Half": it fits, and it reads without the context of a period label.
- The clock changes only with a snapshot (ruling 6). ESPN updates it per logged play (§1.2).
- **Semantics** (`Describe`): "Bengals 0, Falcons 7, 11:12 left in the 1st. Falcons ball, 1st and 10, in the red
  zone." It uses `TeamLine.shortName` (S5) and adds `sports_desc_nfl_ball` "%1$s ball", `sports_desc_nfl_down`
  "%1$s and %2$s" and `sports_desc_nfl_red_zone` "in the red zone" when present.

### 4.3 The possession tab (ruling 22)

**Geometry** (PP §4.1 with an NFL inset):

| Item | Mock (980 × 144 pill) | Standard (520) | Large (650) |
|---|---|---|---|
| Side inset | 70 | **37** → width **446** | **46** → width **558** |
| Visible depth / hidden overlap / far radius | 46 / 34 / 36 | **36 / 20 / 18** (PP) | 45 / 25 / 22.5 |
| Ball icon (the mock's SVG path, stroke 1.7 / 24) | 28 | 20, stroke 1.4 | 25 |
| Lead text ("KC BALL", "KC KICKOFF") | 24/800, tracking .02 em | **20/700** Instrument Sans Bold, tracking .05 em (PP's; D9) | 25/700 |
| Divider | 2 × 26 at .45 | 1 × 16 white .5 | 1.25 × 20 |
| Tail text ("OWN 35", "EXTRA POINT", "RED ZONE") | 24/600, tracking .02 em | **20/600** Instrument Sans **SemiBold** (masks baked from `sansSemi`, N3), tracking .05 em | 25/600 |
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
  - Here **`tab = base`** when C5 passes on it, else the accent. C5 passes on the base for all 32 (§6). The shared
    `derive` would give `glow` (BUF red), so `NflTeamColours` overrides `tab` after it (S1, §6).
  - The mock's KC gradient is about `mixBlack .68 / .26 / 0` of the base (`#4a0710 → #a8122a → #E31837`). Its BUF
    gradient ends lighter than the base (`#0040b0`), so the mock is not consistent. One rule (ruling 17's) is used
    here.
  - Dark bases (CHI, LV, NO, PIT) give a tab whose silhouette is faint over dark video. The text still passes C5 (an
    eye check on a TV).
- **Direction:** below the pill at top corners, above at bottom corners (ruling 16). The effective corner is taken
  after `textTrackOn`, as PP §4.2.
- **Drawing** (PP §3.4, §4.3): after the glass, under the outside `clipRect` snapped to a device px; no `clipPath`, no
  `saveLayer`.
  - The label masks are baked `ALPHA_8` at 1.25×, keyed on `(s, size, away.abbrev, home.abbrev)`:
    - per side, from Bold: "{ABBR} BALL", "{ABBR} KICKOFF";
    - shared, from Bold: the five score words;
    - from SemiBold: "EXTRA POINT", "OWN", "OPP", "MIDFIELD", "RED ZONE", and per side "{ABBR} +N" for N in
      {1, 2, 3, 6}.
  - The yard digits come from a pre-measured digit table, as the PP seconds.
  - The ball icon is one baked `ALPHA_8` sprite.

**The track** is PP's `PowerPlayTimeline` TAB track: `OPEN` (slide 550 snap, text in from 380), `CLOSE` (600) and
`STILL`. The NFL painter **ignores** `shineOn` and `labelScale`; the mock has no shine and no label pulse.
- **`NflTabRun`** (in `NflBug.kt`) mirrors `PpRun`'s rules:
  - one long-lived runner;
  - kicks only on an identity change. For the NFL the key is `(tabOn, tab.side)`, **evaluated only while not
    pinned** (S2). The pin start and the release rebase the run's `from` and content **without** a kick (§5.4);
  - the S1 interrupt order (snap the driver before assigning `from`/`kind`/`to`);
  - the snaps of PP §3.1 (the first non-waiting view, reappearing, a size change, leaving place mode, labels ready).
- **Content changes:**
  - a lead or tail change on the same side (yard line, BALL ↔ KICKOFF, EXTRA POINT) is a plain cut, except into
    and out of RED ZONE, which cross-fades (§4.5);
  - a **side change** is CLOSE with the old content, then OPEN with the new (PP B2), so a turnover reads as one;
  - a side change at a kick-off (KC KICKOFF → BUF BALL) is the same.
- **Pill track:** none. The NFL has no tint and no logo halo.
- **`tabAllowed`** is PP §4.4's with the NFL tab box: an overlay within 29 px closes the tab, and place mode never
  shows it.
- **Red zone:** ruling 26, §4.5.

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
- `PlacementInput.league` is set by `FullScreenHost` (§4.6); the NFL placement checks are in a new `NflPlacementTest`, so
  `ScoreBugPlacementTest` is not edited.

### 4.5 RED ZONE (ruling 26)

- **When:** the gating of §3.5. In short, a real down with the offence inside the opponent's 20; never during the
  try, the kick-off, a break or Final, whatever `isRedZone` says.
- **What changes:** only the tail half. The lead ("KC BALL", the ball icon) keeps the team's gradient. The tail half
  reads **"RED ZONE"** (20/600 SemiBold, tracking .05 em; 108.4 wide, so the content fits in 410 with room to spare).
- **The red half's extent:** from the divider's x to the tab's right edge, through the tab's full depth. It is drawn
  as the tab's own round rect with the red gradient under one `clipRect(dividerX, …)`. The tab's rounded far corner
  is kept, and there is no `clipPath`.
- **The red:** `RED_ZONE = #C8102E` through the same vertical gradient as the tab (`mixBlack .70 / .28 / .10`).
  White against it:

  | Gradient point | Contrast |
  |---|---|
  | the far stop (`.10`, the lightest) | **6.92** |
  | the C5 point (`.172`) | 7.76 |
  | flat | 5.88 |

  Every point is ≥ 4.5 : 1. `NflTeamColoursTest` asserts it at every stop.
- **Red teams** (KC, ATL, ARI, SF, TB): the red half sits next to a red lead half. The divider (white .5) and the
  words carry the meaning, and the colour is not relied on alone. An eye check on a TV.
- **The transition:** a **250 ms cross-fade**, `ease`, in both directions.
  - The red layer's alpha goes 0 → 1, and "OPP 12" fades out under "RED ZONE" fading in.
  - It is one transition, never repeated or pulsed, so it is not a flash.
  - It runs on a third `Animatable` in `NflTabRun` (`rzDriver`), read only in draw.
  - It never kicks the slide track.
  - Reduce animations: a cut.
- **With the other tab changes:**
  - a possession change while red: CLOSE (red, with the old words), then OPEN in the new state;
  - a score from the red zone: the pin (§5.4) shows the score label over the scorer's gradient. Red is not drawn while
    pinned, and the release cuts to the try (PAT), not back to red;
  - leaving the red zone by a penalty (OPP 12 → OPP 27): a cross-fade out.

### 4.6 The league seam in `ScoreBug.kt` (B4)

Commit 0 branches at the **top** of `ScoreBug(...)`, before any `remember` of `ScoreBugWith`:

```kotlin
@Composable
fun ScoreBug(
    source: ScoreBugSource, placement: PlacementInput, geometry: OverlayGeometry, lowEnd: Boolean,
    modifier: Modifier = Modifier, place: (PlacementInput, OverlayGeometry) -> DesignBox? = ScoreBugPlacement::place,
    followedLeague: League? = null,                           // new, trailing, defaulted: FullScreenHost passes ScoreFacts.game?.league
) {
    val lastLeague = remember { mutableStateOf<League?>(null) }
    val shown = source.bug.collectAsState().value?.league
    val league = shown ?: lastLeague.value ?: followedLeague ?: source.prefs.value.league
    SideEffect { if (shown != null) lastLeague.value = shown }
    key(league) {
        if (league == League.NFL) NflBug(source, placement, geometry, modifier, place, NflHooks.Default)
        else ScoreBugWith(source, placement, geometry, lowEnd, modifier, place, GoalHooks.Default)
    }
}
```

Reading `source.bug` here recomposes `ScoreBug` once per snapshot. `ScoreBugWith` already does that (SB §4.7), and
`key(league)` keeps its slot, so the budget of one recomposition per snapshot is unchanged. `NflBugDrawTest` counts
it.

- **`ScoreBugWith` is byte-unchanged.** So the NHL draw, seam, bounds and allocation tests are untouched by
  construction. An NFL view can never reach the NHL `GoalRun`, `GoalPainter`, `PowerPlayPainter` or `LiveText`, and
  the NHL painters and masks are never built while an NFL game is followed (no double bake memory).
- **While the bug is hidden or fading** (`view` null), the branch holds the last shown league (`lastLeague`), so it
  never flips mid-fade.
- **Following an NFL game while an NHL bug is on screen** (or the reverse) changes the key. The old bug leaves at
  once, with no fade, and the new one enters with its own first-show fade. This only happens on a user's follow
  action, with the Sports tab or the picker on screen. Accepted, and recorded.
- **`NflBug` owns its own copy of the shell** (the fade, the corner slide, `FollowLogos`, the place hint, the live
  region) rather than extracting a shared shell from `ScoreBugWith`. That costs about 150 duplicated lines and buys a
  zero-risk NHL path. A later refactor may extract the shell as a pure move, gated by the unchanged `ScoreBugDrawTest`.
- **`PlacementInput.league`** is set in `FullScreenHost.scorePlacementInput` (`FullScreenHost.kt:229`), which gains a
  trailing `followedLeague: League? = null`: `league = followedLeague ?: prefs.league`. That is commit 0, and it puts `FullScreenHost.kt` in the commit-0
  table. With it, `place()`, `glowAllowed` and `tabAllowed` test the 520-wide box.
- **Place mode** draws an NFL sample view (KC 24 – BUF 17, "4th" / "3:42", "1st & 10", the tab "KC BALL | OWN 35")
  when the league rule above gives NFL. Commit 0 adds `nflSampleView(res)` beside `sampleView`.
- **The test:** `NflBugDrawTest` drives `ScoreBug(...)` with an NFL view whose `goalSeq` bumps. The NHL `GoalRun`
  never starts and no `GoalPainter` is built (a counting hook). This is a new test file; `ScoreBugDrawTest` is not
  edited.

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
| **Ball arc** (white α ≤ .55) | 300 | 1 400 | ease | **(33, 44)** ×1 0° → 50 %: (202, −7) ×.75 360° → (366, 12) ×.45 720°; α 0 → .55 at 10 % → .55 at 85 % → 0. P0 moved 12 px right of the mock's (21, 44) so the ink starts in the straight section (B5, D8) | XP, FG |
| **Goalposts** in (white, 43 × 52 at x 355, y 9) | 200 | 400 | ease-out | α 0 → .4, dy 8 → 0 | XP, FG |
| Goalposts hit | **1 800** | **3 × 450** | ease-in-out | α .4 → .95 → .4 per cycle | XP, FG |
| **Panel flash** (white) | **450** | **3 × 450** (to 1 800) | ease-in-out | each 0 → .42 → 0 | all |
| Logo badge | 450 | 600 | spring | α 0, dx −32, ×.4, −25° → 70 %: α 1, dx +2, ×1.1, +4° → rest (`tLogo`); hard shadow dy 2, black .25 | all |
| Word letters | 420 + i × 55 | 420 each | spring | α 0, dy +22, ×1.9 → 60 %: α 1, dy −2, ×.94 → rest (`tLetter`) | all |
| Word pulse | 1 400 | 3 × 500 | ease-in-out | ×1 → 1.05 → 1 (`tWord`) | all |
| +N and tally | 1 100 | 500 | spring | α 0, dx +19, ×.6 → 70 %: α 1, dx −1.6, ×1.12 → rest (`tSlideR`) | all |
| **Tab pulse** (pinned tab, §5.4) | 0 | **8 × 450** (to 3 600) | ease-in-out | brightness ×1 → 1.45 → 1 (`tTab`) | all |
| Score pop (scorer's digits) | 3 600 | 900 | spring | ×1.5 → .9 (40 %) → 1.06 (70 %) → 1, clamped to the 50 + 2 × 8 column; colour `pop` → white; revealed under the lead from 3 168 (GA §6.2) | all |

- **Letter stagger:** "TOUCHDOWN"'s last letter starts at 860; "EXTRA POINT" (11 with the space, which is skipped)
  at 970.
- **Keeping the ball inside the stadium (B5)**, with no `clipPath`, no `saveLayer` and no `setLocalMatrix` (GA §2.2:
  it reallocates the native shader on API 26+). Two inner bands:
  - **The ink radius.** The ball sprite's ink (the mock's SVG football, rotated about the sprite centre) lies within
    **15 px** of the centre at Standard (10.6 of 24 viewBox units × 34 / 24). Any rotation stays inside that circle.
    `NflScoreTimelineTest` measures the baked sprite's ink radius (≤ 15, Large ≤ 18.75).
  - **The run** is drawn under `clipRect(4.4, 0, W − 4.4, H)` (Large 5.5 / W − 5.5) inside the panel clip. Its centre
    line is the pill's (y 35). At x = 4.4 the stadium's half-height is √(35² − 30.6²) = 17 ≥ 15, so every ink pixel
    inside the band is inside the stadium.
  - **The arc and the posts** are drawn under `clipRect(35, 0, W − 35, H)` (Large 43.75), the straight section, where
    the stadium is the rectangle. With P0 at (33, 44), the arc's centre starts at x 50, and its ink at x ≥ 35.
  - **The assert stays, at both sizes:** no ball or post pixel outside the pill's round rect, in every frame of
    `NflScreenshotTest`.

### 5.2 Words and the score block

| Kind | Word (Barlow Condensed Black Italic 48, tracking .03 em) | Width | Tab during the takeover |
|---|---|---|---|
| TOUCHDOWN | TOUCHDOWN | 229 | "TOUCHDOWN \| KC +6" (always +6; a +7/+8 update is two takeovers, §5.7) |
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
- **Atlas (N2):** one **de-duplicated glyph atlas** per size. The five words use 20 distinct glyphs
  (`ACDEFGHILNOPRSTUWXY-`). Each glyph is baked once with its hard shadow (dy 2, black .28), `ARGB_8888` at 1.5×, as
  GA S2. A word is drawn glyph by glyph from the atlas with its stored advances.
  - About 20 × 40 × 90 px × 4 B ≈ **290 KB at 1080p**, and about 1.2 MB on a 2160p panel (d = 2). That compares with
    about 600 KB / 2.5 MB for five whole-word atlases.
  - `NflScreenshotTest` checks the atlas byte size at both densities, as GA S2 did.
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
`side` and `nfl.scores` (one score, or the pair of §5.7).

**Tab pin** (PP §5, simplified because there is no PP GOAL logic; S2):
1. **At start:** `pinTabOn` = the tab **as drawn on the previous frame** was at rest open or opening, **and**
   `tabAllowed`. This is PP's reading (`pr.pin(…, tabAllowedNow, …)`). If it is false (already closed, closing, or an
   overlay), the tab stays closed for the takeover.
2. **Before 216 ms** (pending, or `t < LEAD_COVERED_MS`): the tab holds the content it was drawing (the spoiler hold).
3. **From 216 to 3 600:** the tab shows the score label in the **scorer's** tab colours, pulsing (§5.1). The content
   and colours are **rebased** with no kick: the slide track stays at rest.
4. **While pinned** the identity key is not evaluated, so no view change can slide the tab.
5. **Release at 3 600**, from a child watching `driver.value`, or from the still path. Then the key is evaluated
   again:
   - `tabOn` is true: a **cut** to the current content in the current side's colours, with no slide, **even if the
     side differs from the pre-pin side** (a safety's free kick, a defensive TD);
   - `tabOn` is false (a break, Final, an overlay): a **CLOSE**, sliding the score label out.
6. **A cancel** (hide, place mode, size, new game) snaps the tab.

| Case | Result |
|---|---|
| TD (+6), the score on the stale `Down` body (14:41:58) | TOUCHDOWN; release to "KC BALL \| EXTRA POINT", down column "PAT" at once (the stale body renders as the try, §3.3) |
| Defensive TD (+6) on a stale body naming the other side | TOUCHDOWN in the scorer's colours; release to "BUF BALL \| EXTRA POINT" in BUF's colours (a cut) |
| XP 20–60 s later (+1) | EXTRA POINT; release to "KC KICKOFF \| OWN 35" |
| FG (+3), the dead-ball body | FIELD GOAL; release to the held tab, then "KC KICKOFF \| OWN 35" on the next poll (a cut) |
| **+7 / +8 in one update** | two takeovers back to back (§5.7) |
| FG on the last play of the half, the snapshot already `HALFTIME` | the tab was open on the previous frame, so it is pinned: "FIELD GOAL \| BUF +3" pulses, then CLOSEs at 3 600. The centre reads "Halftime" after the pop |
| OT walk-off TD on the FINAL snapshot | the same: "TOUCHDOWN \| KC +6", then CLOSE at 3 600; "Final/OT" |
| Safety | SAFETY in the scorer's colours; release (a cut) to "{conceding team} KICKOFF \| OWN 20" in its colours |
| A score from the red zone | the pin's label replaces RED ZONE; the release cuts to the try (PAT) |
| A new score during a takeover or a pair | restart with the new score (GA step 4): the latest wins (§5.7) |
| Both sides rose in one poll | no takeover (C-N1) |
| Score corrected down, or restored within `QUEUE_KEEP_MS` | no takeover (N5) |

`NflBugDrawTest` covers the halftime-FG row, the walk-off row and the safety release (S2).

### 5.5 Drawing (`NflScorePainter`, `:ui`; GA §2 rules)

- **The composite** (`NflBugPainter.drawFrame`), in order:
  1. glow;
  2. glass (the NFL pill size);
  3. tab (with its ADD pulse pass while pinned);
  4. body;
  5. takeover: the lead; then under the panel clip the panel, streak 1, streak 2, the posts and the arc (under the
     straight-section clip) or the run (under the inner band), the flash, the badge, the word and the score block
     (§5.1, B5).
- **Glow masks:** the GA bake (`ALPHA_8`, low and peak, the same spread, σ and α) for the NFL pill size. The margin
  stays 74 / 76; the edge alpha at the 56 px inset is the GA's 1.3 / 1.4 %.
- **Built once per size:** paints, the atlas, the sprites, the masks and the digit tables. Off the main thread, as GA.
- **Built per takeover** in `begin()`: the streak shaders, the tally chars, the colours and the padded badge.
- **Draw** uses only `save`/`restore`/`clipRect`/`translate`/`rotate`/`scale`/`drawRoundRect`/`drawBitmap`/
  `drawText(char[])`. Allocation-free.
- **Cost:** about 270 frames per score at 60 Hz, as a GA takeover. Two takeovers per TD drive (TD, then the try about
  30–60 s later, or the §5.7 pair) are about 9 s and 540 frames of animation. Device item: frame time on a Fire TV Stick (ruling 20).

### 5.6 Still version (system Reduce animations only, ruling 20; `MotionDurationScale == 0`)

- **Frame A (0–3 600):** the panel at rest: badge, word, +N and tally. XP/FG also show the posts at α .4. There is no
  lead, streaks, ball, flash, pulse or glow. The tab shows the score label, with no pulse.
- **Frame B (3 600–4 500):** the plain bug with the scorer's digits in `pop` at ×1. The tab is released (cut) at 3 600.
- **A pair** (§5.7) is four frames: A1, B1, A2, B2.
- **RED ZONE** changes are cuts.
- **Then** the plain bug.

The tab's OPEN and CLOSE snap (`STILL`).

### 5.7 Two takeovers for one +7 / +8 update (ruling 25)

The owner's rule is "as if the updates had arrived separately". The machine splits the rise (§3.2), so the view's
`nfl.scores` has two entries:

| | Kind | Points | Tally (away – home) |
|---|---|---|---|
| A | TOUCHDOWN | +6 | the score before the update, with the scorer + 6 |
| B | EXTRA_POINT or TWO_POINT | +1 / +2 | the score in the update |

**Sequence** (one driver, `t` from the start; the mark is `NFL_DOUBLE_MARK_MS` 9 000):

| t (ms) | Pill | Tab (pinned) | Down column | Scorer's digits |
|---|---|---|---|---|
| 0–3 600 | takeover A (TOUCHDOWN: ball run, "+6", tally A) | 0–216 the old content; then "TOUCHDOWN \| KC +6", pulsing | the old text until 216, then "PAT" | hidden under the takeover |
| 3 600–4 500 | the plain bug; the pop of **tally A** (the intermediate score, e.g. 6 – 0) | **"KC BALL \| EXTRA POINT"**, no pulse (what separate updates would show between them) | "PAT" | tally A's digit, popping |
| 4 500–8 100 | takeover B (EXTRA POINT: arc and posts, "+1", tally B; or TWO-POINT: ball run, "+2") | from 4 716 (B's lead covered): "EXTRA POINT \| KC +1", pulsing | as A | hidden |
| 8 100–9 000 | the pop of **tally B** (the final score) | released at 8 100 (a cut to the current content, or CLOSE) | the view's | the final digit, popping |

- **The body's score override.** While the pair runs, `NflRun` draws the scorer's digits from the current segment's
  tally, not from the view, until B's lead covers the pill (4 716). The final score is never seen before B. This is
  the GA no-spoiler hold, extended to the intermediate tally.
- **The flash grid restarts at 4 500**, so B's first tab peak is at 4 725, 1 350 ms after A's last (3 375).
  The 450 ms rule holds across the pair.
- **A new rise during the pair** (possible only through a delay change, or two updates 20 s apart reaching the
  screen together after a stall): GA step 4, the latest wins. The pair is cancelled where it stands, and the new
  score's takeover (or pair) starts at once. Its tallies come from the view, so the cancelled segment's points are not
  lost from the bug, only from the animation. There is no queue beyond the pair.
- **Delay:** the pair starts when the delayed snapshot becomes visible, like any takeover. The 9 s runs on the UI
  clock (the machine only holds the mark for 9 s).
- **Stale arriving mid-pair:** it runs on; the dim applies under it (GA). **Hidden, place mode or a size change:**
  cancel both segments and clear; there is no replay of B later. **A new game:** cancel (GA step 2).
- **Still version (ruling 20, Reduce animations):** frame A1 (0–3 600, A's panel at rest), frame B1 (3 600–4 500,
  tally A's digit in `pop` at ×1), frame A2 (4 500–8 100, B's panel), frame B2 (8 100–9 000), then the plain bug.
- **Accessibility:** one announcement for the pair: "Kansas City Chiefs touchdown and extra point. Chiefs 7, Bills 0"
  (`sports_live_nfl_pair` "%5$s %6$s and %7$s. %1$s %2$d, %3$s %4$d").
- **Separate updates** (the usual case, about 30–60 s apart) are two ordinary takeovers; nothing above applies.
- **Tests:** `NflScoreTimelineTest` (the pair's sample at 0, 3 600, 4 500, 4 716, 8 100 and 9 000);
  `ScoreBugMachineNflTest` (+7 and +8 give two scores with the right tallies; the 9 s mark); `NflBugDrawTest` (the
  override never shows the final digit before 4 716; a new seq at 5 000 restarts; hide at 2 000 clears, with no
  replay).

---

## 6. Colours (rulings 12 and 27; `NflTeamColours`)

**Sources.** **TCC** = `https://teamcolorcodes.com/<slug>/`, read 2026-11-08 from each page's "HEX codes are …"
sentence (the slugs are in the table's source comments in code). It is a secondary source, as for the NHL. ESPN's own
`color`/`alternateColor` disagree with the teams' palettes in places (§1.1), so they are not used.

**Rules:**
- base = the primary, accent = the official secondary;
- a gold or light primary takes the accent slot (NO, PIT), as ruling 12 did for NSH and PIT;
- `glow`, `pop` and C1–C3 from the shared `NhlTeamColours.derive`, **untouched** (GA §3.1–3.2);
- **`tab` = base** if C5 passes on it, else the accent (§4.3). `derive` would set `tab = glow` (BUF red), so
  `NflTeamColours` does `derive(base, accent).copy(tab = if (c5(base) ≥ 4.5) base else accent)` (S1). The PP
  `tintScale` is irrelevant to the NFL and left as derived;
- the red-zone red `#C8102E` (§4.5) is asserted ≥ 4.5 : 1 with white at every gradient stop;
- C5 is white at `mixBlack(tab, .172)` (PP §6.2).

| Abbr | Base | Accent | C1 | C2 | C3 | Glow | Pop | Tab C5 | Note |
|---|---|---|---|---|---|---|---|---|---|
| ARI | `#97233F` | `#000000` | 7.99 | 1.1 | 2.6 | base | `#C68695` | 10.01 | |
| ATL | `#A71930` | `#000000` | 7.43 | 1.1 | 2.8 | base | `#CF808D` | 9.47 | |
| BAL | `#241773` | `#9E7C0C` | 14.53 | 5.0 | 3.7 | accent | `#B1963D` | 15.99 | **gold, not TCC's second (black)**: purple and black are both dark, so the glow would vanish (ruling 27) |
| BUF | `#00338D` | `#C60C30` | 11.30 | 3.3 | 1.9 | accent | `#E0798D` | 13.22 | matches the mock |
| CAR | `#0085CA` | `#101820` | **4.03** | 1.1 | 4.4 | base | `#40A4D7` | 5.54 | C1 exception |
| CHI | `#0B162A` | `#C83803` | 18.07 | 3.8 | 3.5 | accent | `#DB7E5B` | 18.71 | |
| CIN | `#FB4F14` | `#000000` | **3.37** | 1.1 | 6.2 | base | `#FC6937` | 4.72 | C1 exception (orange) |
| CLE | `#311D00` | `#FF3C00` | 16.08 | 5.5 | 4.5 | accent | `#FF6333` | 17.14 | |
| DAL | `#041E42` | `#869397` | 16.54 | 6.2 | 5.2 | accent | `#8C989C` | 17.53 | **navy + silver** (TCC lists royal `#003594` first, then navy: two blues; ruling 27) |
| DEN | `#FB4F14` | `#002244` | **3.37** | 1.2 | 4.7 | base | `#FC6937` | 4.72 | C1 exception (orange) |
| DET | `#0076B6` | `#B0B7BC` | 4.92 | 9.7 | 2.4 | base | `#B0B7BC` | 6.57 | |
| GB | `#203731` | `#FFB612` | 12.71 | 11.2 | 7.2 | accent | `#FFB612` | 14.32 | |
| HOU | `#03202F` | `#A71930` | 16.78 | 2.6 | 2.3 | accent | `#CF808D` | 17.80 | |
| IND | `#002C5F` | `#A2AAAD` | 13.77 | 8.3 | 5.8 | accent | `#A2AAAD` | 15.38 | |
| JAX | `#006778` | `#D7A22A` | 6.54 | 8.5 | 2.8 | base | `#D7A22A` | 8.47 | **teal primary** (TCC lists black first; ruling 27) |
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
| TB | `#D50A0A` | `#34302B` | 5.42 | 1.5 | 2.4 | base | `#E87878` | 7.28 | **pewter** (TCC lists Bay orange second; ruling 27); TCC "tampa-bay-buccaneers-team-colors" |
| TEN | `#0C2340` | `#4B92DB` | 15.79 | 6.0 | 4.8 | accent | `#5D9DDF` | 16.93 | |
| WSH | `#5A1414` | `#FFB612` | 13.59 | 11.2 | 7.7 | accent | `#FFB612` | 15.14 | TCC "washington-redskins-color-codes" (the page is titled Commanders) |
| *fallback* | `#2A3140` | `#5CA8F2` | 13.03 | 7.8 | 5.2 | accent | `#5CA8F2` | 14.58 | as the NHL |

- **C1 exceptions** (`NflTeamColours.C1_EXCEPTIONS`, white word on base < 4.5) are applied under ruling 12's "no
  substitute colours", as PHI is for the NHL: CIN 3.37, DEN 3.37, MIA 3.95, CAR 4.03, LAC 4.28. The word passes
  3 : 1 for all.
- **C5** (white tab text on base) passes for all 32: the lowest are CIN and DEN at 4.72. `C5_EXCEPTIONS` is empty.
- **Tests (S1):** `NflTeamColoursTest` pins `tab == base` for all 32 teams, and that `NhlTeamColours.of("EDM").tab`
  is still `#FF4C00` (the shared `derive` is unchanged).
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
- **`ScoreRepositoryImpl`** takes `Map<League, ScoreSource>` (Hilt `@IntoMap`), with the S7 secondary constructor for
  one source. The day list uses `sources[prefs.league]`; the followed game uses `sources[game.league]`.
- **One followed game**, either league (`FollowedGame.league` exists). Following an NFL game replaces an NHL follow
  with the usual toast. The follow survives a league switch; the bug shows whatever league is followed.
- **Demand, polling and the screen-off rule are unchanged.** One machine; the day list polls one league.

### 7.2 NFL "today" = the ESPN week (ruling 28)

- **"This week"** is the undated scoreboard: ESPN's current week, from Wednesday 3 am ET (the calendar's own
  `startDate`, never computed; N1). It holds Thursday night, the London/Madrid morning games, Sunday, Sunday night
  and Monday night on one list.
  - On a Tuesday it still shows the week's finals. On Wednesday it moves to the coming week.
- **Last week · This week · Next week** chips use the board's `prevDay`/`nextDay`, which are week keys
  (`w2026-2-8`, `w2026-2-10`) taken from the calendar (S6).
  - At the season's edges, Week 18 → Wild Card, and after the Super Bowl there is no next week.
  - **A null neighbour disables its chip.**
- **No date fallback for the NFL (S6).** `SportsViewModel.dayKeyFor` falls back to `easternToday() ± 1 day` when a
  neighbour is null. That fallback becomes league-aware: the NHL keeps it as is; the NFL has none. Before the first
  board, on an `Empty` board or at an edge, the NFL chips are disabled. An NFL request is never a single date.
- **Week keys** are parsed in one place, `EspnMapper.parseWeekKey`. `EspnScoreSource.day(key)` accepts a week key
  (`seasontype` + `week`) or a date key (`dates`, used only by `game()`), and fails anything else with
  `HTTP_OTHER`, as `NhlScoreSource` does.
- **The off-season** (the calendar's "Off Season" entry has no weeks): an empty board, "No NFL games this week." with
  both neighbour chips disabled; This week stays focusable.
  - The board title shows the calendar label ("Week 9", "Wild Card", "Divisional Round", "Conference Championship",
    "Super Bowl") from the feed. It is the one feed string drawn as is besides the team names.
- **Order:** live, then upcoming by start, then final, then postponed (the existing `groupOf`).
- **Card header** for a scheduled NFL game: "Sun 1:00 PM" (weekday + time, device zone) when not today, else
  "1:00 PM". Other states as the NHL: "LIVE · 2nd · 10:22", "Halftime", "End 3rd", "Final/OT".
- **Card names:** `TeamLine.shortName` (S5; "Commanders", "Buccaneers"). They are shorter than "Golden Knights"; a
  new `NflGameCardFitTest` checks the 32 NFL names.
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
  6. the divider ("Today" or "This week", or the board's day / the week label, never a raw `w…` key; S4);
  7. the other games.

  `PickerChoice.ScoreLeagueRow(league)` is new. Its label and the NFL divider and failure texts are in
  `PlayerText.kt` (S4).
- **Strings** (`strings_sports.xml`, commit 0):
  - `sports_league_nfl` "NFL", `sports_period_4` "4th", `sports_halftime` "Halftime";
  - `sports_end_of` reused;
  - `sports_down_1`…`sports_down_4` "1st"…"4th", `sports_down_and` "%1$s & %2$d", `sports_down_goal` "%1$s & Goal",
    `sports_down_pat` "PAT", `sports_down_kickoff` "KICKOFF";
  - `sports_tab_ball` "%1$s BALL", `sports_tab_kickoff` "%1$s KICKOFF", `sports_tab_own` "OWN %1$d",
    `sports_tab_opp` "OPP %1$d", `sports_tab_mid` "MIDFIELD", `sports_tab_xp` "EXTRA POINT", `sports_tab_red_zone`
    "RED ZONE";
  - `sports_nfl_td` "TOUCHDOWN", `sports_nfl_xp` "EXTRA POINT", `sports_nfl_fg` "FIELD GOAL", `sports_nfl_safety`
    "SAFETY", `sports_nfl_2pt` "TWO-POINT", `sports_nfl_points` "%1$s +%2$d";
  - `sports_week_last` / `_this` / `_next` "Last week" / "This week" / "Next week";
  - `sports_empty_week` "No NFL games this week. Pick another week above.";
  - `sports_failed_espn` "ESPN did not answer. Press OK to try again; scores may be down for a few minutes.";
  - `sports_credit_espn` "Scores and logos: ESPN";
  - `sports_toast_not_listed_espn`, `sports_week_off` "No NFL games this week.";
  - live and description strings (§8);
  - `settings_sports_show_desc` gains "and ESPN".

---

## 8. Accessibility

- **The live region** (GA §5.3, persistent `Spacer`): while the mark is set, "Kansas City Chiefs touchdown. Chiefs 13,
  Bills 7" (`sports_live_nfl_score` "%5$s %6$s. %1$s %2$d, %3$s %4$d", %5 = `name`, %6 = the lower-case kind word, the
  scores with `shortName`; S5). A +7/+8 pair is one announcement (§5.7). Otherwise "".
  - RED ZONE is in `Describe` only, never announced.
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
  - `sports_nfl_timeline_s` (default 985; about 16.5 min);
  - `sports_nfl_script` (`{event id: {key: value}}`; the keys of `state()`: `state`, `name`, `period`, `clock`,
    `away`, `home`, `possession`, `down`, `distance`, `yardLine`, `possessionText`, `shortDownDistanceText`,
    `isRedZone`, `lastPlayType`, `lastPlayScoreValue`, `situation: null`, `gone`);
  - `sports_nfl_faults` (the NHL fault keys).
- `GET /admin/sports-nfl` shows the slate. Restart: §9.2 (N8).
- **`sports.py`, its tests and `testdata/sports_default_golden.json` are untouched.** `fakepanel.py` adds only the
  flag, the defaults and one route dispatch. The NHL golden is byte-identical by construction, and the existing golden
  test proves it.

### 9.2 The featured game (KC @ BUF, BUF at home, id `401899001`; segment seconds at the default 985)

**Every window is ≥ 25 s**, so at about 21.5 s polls each is seen at least once, and two consecutive polls see the
no-possession windows. The one exception is the deliberate **10 s** window, marked. Each scoring row spells out the
body's `situation`. `yl` is home-relative (BUF's goal line); KC kicks from KC 35 = yl 65.

| Segment (length) | Window | Body (score KC–BUF; `situation`; `lastPlay`) | Proves |
|---|---|---|---|
| pre (40) | | `STATUS_SCHEDULED`, start = anchor + 40 | no bug; `PRE` within the hour |
| **Q1** (235) | 0–25 | `in`, 0–0; down 0, dist 0, no possession; `lastPlay` 70 Coin Toss | **no bug yet** (S8) |
| | 25–50 | 0–0; KC 1st & 10 "KC 25", **no possession**; `lastPlay` 53 Kickoff, team KC | bug appears; tab "KC BALL \| OWN 25" by N6 inference, on two polls with no flicker |
| | 50–75 | KC 1st & 10 "KC 25", possession KC | the same tab (no kick) |
| | 75–100 | KC 3rd & 2 "BUF 38" | OPP |
| | 100–125 | KC 1st & Goal "BUF 4", `isRedZone` true | **RED ZONE** cross-fade in |
| | **125–135 (10 s)** | **6–0**, the situation and `lastPlay` still the 100–125 ones | the score before the play: TOUCHDOWN; the stale body renders PAT (B2) |
| | 135–160 | 6–0; try: down −1, dist 10, no possession, `isRedZone` true; `lastPlay` 67 sv 6 team KC | PAT, **no RED ZONE** |
| | 160–185 | **7–0**; kick-off: down −1, dist 0, yl 65, possession KC, `isRedZone` true | EXTRA POINT; "KC KICKOFF \| OWN 35", no RED ZONE |
| | 185–210 | BUF 1st & 10 "BUF 25", **no possession**; `lastPlay` 53 team BUF | a side change (CLOSE / OPEN) to "BUF BALL \| OWN 25", inferred |
| | 210–235 | BUF 2nd & 7 "BUF 28", possession BUF | |
| END_PERIOD (25) | | `STATUS_END_PERIOD`, period 1; situation kept | "End 1st"; the tab closed |
| **Q2** (175) | 0–25 | BUF 1st & 10 "KC 30" | |
| | 25–50 | **7–3**; dead ball: down −1, dist 10, no possession; `lastPlay` 59 sv 3 team BUF | FIELD GOAL (arc, posts); **not** PAT; the tab held |
| | 50–75 | BUF kick-off: down −1, dist 0, yl 35, possession BUF | "BUF KICKOFF \| OWN 35" (a cut, same side) |
| | 75–100 | KC 2nd & 4 "BUF 12", possession KC, `isRedZone` true | a side change; RED ZONE |
| | 100–125 | **14–3 in one body**; kick-off: down −1, dist 0, yl 65, possession KC; `lastPlay` 74 | **ruling 25: TOUCHDOWN +6 (tally 13 – 3), then EXTRA POINT +1 (14 – 3)** |
| | 125–175 | BUF 1st & 10 "BUF 25", possession BUF | |
| HALFTIME (25) | | `STATUS_HALFTIME` | "Halftime" |
| **Q3** (210) | 0–25 | KC 2nd & 8 "KC 2", possession KC | |
| | 25–50 | **14–5**; KC free kick: down −1, dist 0, yl 80, possession KC; `lastPlay` type "Safety" team BUF | SAFETY; release cuts to "KC KICKOFF \| OWN 20" |
| | 50–75 | BUF 1st & 10 "BUF 45", possession **BUF** (BUF received the free kick; S9) | a side change |
| | 75–100 | KC 1st & 10 "KC 40", possession KC | |
| | 100–125 | **14–11**; the situation still KC 1st & 10 "KC 40" (stale); `lastPlay` 36 team BUF | defensive TD = TOUCHDOWN; **renders "BUF BALL \| EXTRA POINT" at once** (B2) |
| | 125–150 | 14–11; the two-point try **as a real down**: 1st & Goal "KC 2", possession BUF, **clock unchanged** | still PAT; `pendingTry` survives (B2) |
| | 150–175 | **14–13**; BUF kick-off: down −1, dist 0, yl 35, possession BUF | TWO-POINT via `pendingTry` |
| | 175–210 | KC 1st & 10 "KC 25", possession KC | |
| END_PERIOD (25) | | period 3 | "End 3rd" |
| **Q4** (150) | 0–25 | KC 3rd & 9 "KC 30" | |
| | 25–50 | BUF 1st & 10 "KC 18", possession BUF, `isRedZone` true | a turnover: CLOSE (KC) / OPEN (BUF, RED ZONE) |
| | 50–75 | **14–16**; dead ball; `lastPlay` 59 sv 3 team BUF | a second FG from the red zone: the pin replaces RED ZONE |
| | 75–100 | BUF kick-off: yl 35, possession BUF | |
| | 100–125 | BUF 3rd & 9 "BUF 2", possession BUF | |
| | 125–150 | **16–16**; BUF free kick: down −1, dist 0, yl 20, possession BUF; `lastPlay` "Safety" team KC | an away safety; the tie |
| END_PERIOD (25) | | period 4 | "End 4th" |
| **OT** (75) | 0–25 | KC 1st & 10 "KC 25", period 5 | "OT" |
| | 25–50 | KC 1st & Goal "BUF 8", `isRedZone` true | RED ZONE |
| | 50–75 | **22–16 in a `post` body**, period 5, `STATUS_FINAL` | walk-off TOUCHDOWN on the Final body; the tab pinned, then CLOSE; "Final/OT" |
| FINAL | | `STATUS_FINAL` | expiry after 5 min |

- **Clocks** run 1 s/s in each LIVE window and freeze in breaks and through the try windows (B2's `tryAt`). ESPN
  steps per play; the fake steps at each window start, so ruling 6's step behaviour is visible.
- **The +8 pair** (TOUCHDOWN then TWO-POINT) is not on the featured timeline, to keep the tie into OT. It is covered by
  `ScoreBugMachineNflTest` and by a `sports_nfl_script` example in the README (score +8 in one body).
- **The week board** also lists:
  - a Thursday final (NYJ 20 – NE 23);
  - a Sunday 4:25 pm scheduled game (SF @ SEA);
  - a Monday scheduled game (LV @ DEN).
- **Day boards:** the featured game's ET date has it and the Sunday game; other dates are empty.
- **Restart (N8):** `fakepanel.py`'s dispatch resets the NFL anchor whenever `POST /admin/sports {"restart": true}`
  resets the NHL one (and `POST /admin/sports-nfl {"restart": true}` resets only the NFL). `sports.py` stays untouched.

### 9.3 Tests (`test_sports_nfl.py`)

- every row above, in the week body and the dated body;
- scores are strings;
- `situation` is absent pre-game, and its shape matches the fixtures' keys;
- `yardLine` is home-relative;
- the +7 body and the 10 s window; **every other window ≥ 25 s** (asserted from the segment table, S9);
- the clock frozen through each try window; BUF's possession after KC's free kick;
- the restart of both anchors (N8);
- the `post` body with period 5;
- the 400 for a bad `dates`;
- the PNG is valid;
- script overrides and `situation: null`, plus the config rejections;
- `--sports` without `--sports-nfl` serves no NFL route, and **the NHL golden test passes unchanged**.

---

## 10. Tests

All NFL cases go into **new** test files, so no existing NHL test file changes (scope rule, S7).

### 10.1 `:core`

- **`EspnMapperTest`**, decoding the fixtures through the commit-0 DTO (B3):
  - every row of §1.3, each fixture of §1.2;
  - the coin-toss fixture maps to `PRE` and the first-down fixture to `LIVE` (S8);
  - the week key grammar and `parseWeekKey`; the calendar neighbours, null at the edges and in the off-season (S6);
  - "1st & Goal" from the text and from the numbers;
  - **the territory** from `possessionText` with and without `possession` (B1); the kick-off field from the
    home-relative `yardLine` for a home and an away kicker; 50 → midfield;
  - a missing `situation`, a bad `down`, a string score, and an unknown status name while `in`;
  - **a re-typed body** (`situation.possession` a number, `lastPlay` an array, `clock` a string) still yields the
    board with score and clock (B3);
  - a dropped bad event keeps the board.
- **`ScoreBugMachineNflTest`** (new):
  - the centre table of §3.1, including no EndOf at 0:00 in play; `clockSecs` with the clock on the second line;
    `knownView()` for the NFL (S3);
  - the gating of §3.4 (stale, waiting, breaks, Final);
  - every row of §3.2: +7 and +8 give two scores with their tallies and the 9 s mark (ruling 25); silent 4/5/9
    rises; the correction guard (N5); the 2-point tie-break;
  - **the try bookkeeping of §3.3** (B2): set on unflagged +6; the order; no clear on a phase change; the clock rule;
    the two replays (try as `Dead`, try as a `Down` at the 2) both TWO-POINT; PAT rendered from the TD snapshot; the
    defensive-TD rendering;
  - the possession inference in its N6 order, and the field tail after inference: the no-possession fixture after
    the +7 fixture gives "ATL BALL | OWN 40" (B1);
  - `Dead` after a FG (`scoreboard-live-fg-dead-ball-2026-11-08.json`) → no PAT and the tab held;
  - the red-zone gating of §3.5, including `isRedZone` true through the try and the kick-off fixtures giving no RED
    ZONE (ruling 26);
  - the delay, including the back-in-time path resetting the NFL state (§3.6); `reset()`.
- **`NflPlacementTest`** (new): NFL boxes for 4 corners × 2 sizes, `tabBox` and `tabAllowed` with each overlay at 28
  and 30 px, reach ≤ `M`.
- **`NflTeamColoursTest`** (new):
  - the §6 table pinned (base, accent, glow, pop) and **`tab == base` for all 32** (S1);
  - `NhlTeamColours.of("EDM").tab == #FF4C00` (the shared `derive` is unchanged);
  - C1 ≥ 4.5 except `C1_EXCEPTIONS`; C5 ≥ 4.5 for all; the red-zone red ≥ 4.5 at every stop;
  - the NHL/NFL collision (`TeamColourTable.of(NFL, "BUF")` ≠ `of(NHL, "BUF")`).
- **`NflScoreTimelineTest`** (new):
  - every instant of §5.1 against a reference bezier (1e-3);
  - the 450 ms grid (no two flash peaks closer than 450 across panel, posts and tab), also across a §5.7 pair;
  - ball vs arc by kind; the letter stagger for each word; the pair's instants (§5.7);
  - the ball sprite's ink radius ≤ 15 / 18.75 (B5);
  - the still frames, single and pair;
  - `sample` allocates nothing (10 000 calls, delta < 1 KB).

### 10.2 `:ui`

- **`NflScreenshotTest`** (new; native graphics, through `NflBugPainter.drawFrame`):
  - the plain bug in each §4.2 state;
  - the tab for BALL, KICKOFF, EXTRA POINT and RED ZONE (and RED ZONE at its cross-fade midpoint), top and bottom
    corners, Standard and Large;
  - takeover frames at 0, 216, 400, 700, 1 000, 1 400, 1 800, 2 500, 3 168, 3 600, 4 000 and 4 500 for TD, XP, FG,
    SAFETY and TWO-POINT;
  - teams KC, BUF, CIN, LV and DAL, over dark video and white;
  - **asserts:** no tab pixel inside the pill; **no ball or post pixel outside the round rect, at both sizes** (B5);
    nothing outside pill + `M`; the tab far stop ≈ `mixBlack(base, .10)`; C5 and the red's contrast measured from
    pixels; at rest the frame equals the plain bug; the glyph atlas size at d = 1 and 2 (N2).
- **Allocation:** 300 draws mid-takeover with the tab pulsing, delta < 4 KB.
- **`NflBugDrawTest`** (new; compose test clock, through `ScoreBug(...)`):
  - **the seam (B4):** an NFL view with `goalSeq` bumped never starts the NHL `GoalRun` and never builds a
    `GoalPainter`; the league held while hidden; a league change of the follow swaps the bug;
  - one recomposition per snapshot, none during a takeover or a pair;
  - the tab kicks only on identity changes (a yard-line change never kicks); a side change is CLOSE then OPEN;
  - **the pin (S2):** no kick at pin or release; the safety release is a cut to the other side; the halftime-FG and
    walk-off rows (label shown, then CLOSE at 3 600); `pinTabOn` from the previous frame;
  - the pin and release at 3 600, also at `MotionDurationScale` 2; the spoiler hold before 216;
  - **the pair (ruling 25):** the intermediate digit; no final digit before 4 716; the tab's PAT between; a new seq at
    5 000 restarts; a hide at 2 000 clears with no replay;
  - **RED ZONE:** the cross-fade does not kick the slide; a cut with animations off;
  - the still path; a cancel snaps; the live region (one announcement per pair).
- **`NflSportsTest`** and **`NflPickerTest`** (new): the league chips and the picker League row; the switch persists;
  the board reloads; the followed row stays across a switch; the week chips disabled at an edge, on an empty board
  and in the off-season, and never a date request (S6); the divider label; "Sun 1:00 PM".
- **`NflGameCardFitTest`** (new): the 32 NFL `shortName`s fit.
- **`NflBoundsTest`** (new): the NFL bug's bounds and the root-tree rule with an NFL bug on screen (S4).

### 10.3 `:data`

- **`EspnScoreSourceTest`** (new; MockWebServer):
  - the paths per call; the remembered ET date of `game()`, and the week board when none is remembered;
  - the event filter; the week-board retry before NotFound (N7);
  - the 1 MiB cap (the fake's `pad_bytes`);
  - an HTML 404; a 400; no `Age` gives 0;
  - a streaming decode of the full real week body (the 272 KB pre-game body, generated in the test by padding the
    trimmed fixture), into `EspnBoardDto`, and of `game()` into `EspnGameDto`;
  - a week key and a date key accepted by `day()`, anything else `HTTP_OTHER`;
  - the logo URL validation.
- **`ScoreRepositoryLeagueTest`** (new):
  - a league switch resets the day list to the new league's today;
  - the follow polls its own league's source;
  - an NFL follow replaces an NHL one;
  - nothing is fetched with nothing followed and Sports closed;
  - the single-source secondary constructor (S7).

### 10.4 Instrumented

`NflFramesTest` (new, beside `GoalFramesTest`, `--sports --sports-nfl`) arms on the featured game's **score change
only**, through `/admin/sports-nfl`. It measures the window [score, score + 4.5 s] (or + 9 s for the Q2 pair) with
the GA §8.4 gates. The tab's CLOSE / OPEN at every kick-off is not a takeover burst (N9). The device run is open.

---

## 11. Work packages (worktrees; commit 0 by the lead, then WP-1…WP-5 in parallel with disjoint files)

### Commit 0 (lead, Opus). Gate: `./gradlew :core:test :ui:testDebugUnitTest :data:testDebugUnitTest :app:assembleDebug lintAll`

| File | Change |
|---|---|
| `core/.../sports/SportsContracts.kt` | §2: `League.NFL`, `LeagueDetail`, the `Nfl*` types, `FieldPos(territory, yard)`, `TeamLine.shortName`, `GameSnapshot.detail`, `ScoreBugView.league` / `.nfl`, `CentreText.Halftime`, `PlacementInput.league` |
| `core/.../sports/SportsPrefs.kt` | `league = NHL` |
| `core/.../sports/SportsConstants.kt` | §12 |
| `core/.../sports/TeamColourTable.kt` (new) | `of(league, abbrev)` |
| `core/.../sports/NflTeamColours.kt` (new) | stub: `of`, `FALLBACK`, empty `TABLE`, the exception sets |
| `core/.../sports/EspnDto.kt` (new) | **the lenient DTO of §1.3, complete** (B3) |
| `core/.../sports/EspnMapper.kt` (new) | **the final signatures of §1.3** (`board`, `game`, `weekKey`, `parseWeekKey`), bodies `TODO()` |
| `core/.../sports/NflScoreTimeline.kt` (new) | `NflScoreFrame` (every field, letter arrays of `NFL_LETTERS_MAX` 12, `copyFrom`), `sample(kind, tMs, out)`, `samplePair(first, second, tMs, out)`, `sampleStill(...)` = `TODO()` |
| `core/.../sports/ScoreBugPlacement.kt` | `width(size, league)` and `tabInset(size, league)` overloads; the NFL branch stubbed to the NHL values |
| `core/.../full/PlayerContracts.kt` (`PickerChoice`) | `ScoreLeagueRow(league)` |
| `core/src/testFixtures/.../FakeScoreRepository.kt` (`SportsFixtures`) | `nflGame(…)`, `nflView(…)`, `detail`, `shortName` and `league` passthrough |
| `core/src/test/resources/sports/nfl/` | the fixtures (already written by the proposer: §1.1) + `live-sample-2026-11-08.log` |
| `data/.../sports/EspnScoreSource.kt` (new) | stub `ScoreSource` returning `Failed(HTTP_OTHER)`; `EspnEndpoints` |
| `data/.../sports/SportsModule.kt` | `@IntoMap @LeagueKey` for both sources |
| `data/.../sports/ScoreRepositoryImpl.kt` | the map constructor **plus the single-source secondary constructor** (S7); the day source from `prefs.league` |
| `ui/.../sports/ScoreBug.kt` | **only** the new `ScoreBug(...)` body of §4.6 (the branch, `followedLeague`), `nflSampleView`; `ScoreBugWith` and `GoalHooks` unchanged (B4, S7) |
| `ui/.../full/FullScreenHost.kt` | `scorePlacementInput(…, followedLeague)` and the `ScoreBug(…, followedLeague)` call (B4) |
| `ui/.../sports/SportsText.kt` | the `league` parameters (defaulted NHL), the `Halftime` branch in `centre()` (S4) |
| `ui/.../full/PlayerText.kt` | the `ScoreLeagueRow` label signature and the NFL divider / failure text hooks (S4) |
| `ui/.../sports/NflBug.kt`, `NflBugPainter.kt`, `NflScorePainter.kt`, `NflTabPainter.kt` (new) | stubs with the final signatures; `NflBug` draws the glass only |
| `ui/src/main/res/values/strings_sports.xml` | §7.3 strings |
| `tools/fakepanel/fakepanel.py` | `--sports-nfl`, the defaults, the route dispatch to `sports_nfl.handle` (a stub module answering 404), and the restart hook (N8) |

After commit 0, every file below belongs to exactly one WP.

### WP-1: core (Opus)

| File | Change |
|---|---|
| `core/.../sports/EspnMapper.kt` | §1.3 |
| `core/.../sports/ScoreBugMachine.kt` | §3 (the NFL branch, the score kind and pair, the try bookkeeping, gating, inference, field, red zone) |
| `core/.../sports/ScoreBugPlacement.kt` | the NFL widths, `tabBox`, `tabAllowed` |
| `core/.../sports/NflTeamColours.kt` | §6 |
| `core/src/test/.../EspnMapperTest.kt`, `ScoreBugMachineNflTest.kt`, `NflPlacementTest.kt`, `NflTeamColoursTest.kt` (all new) | §10.1 |

### WP-2: data (Opus)

| File | Change |
|---|---|
| `data/.../sports/EspnScoreSource.kt` | §1.5: transport, streaming into the commit-0 DTO, cap, remembered dates, the week retry, logos, override |
| `data/.../sports/ScoreRepositoryImpl.kt` | the league switch reset; the follow's source by league |
| `data/src/test/.../EspnScoreSourceTest.kt`, `ScoreRepositoryLeagueTest.kt` (new) | §10.3 |

### WP-3: timeline and painters (Opus for the timeline, Sonnet for the painters)

| File | Change |
|---|---|
| `core/.../sports/NflScoreTimeline.kt`, `core/src/test/.../NflScoreTimelineTest.kt` | §5.1, §5.3, §5.6, §5.7 |
| `ui/.../sports/NflBugPainter.kt` | the body (§4.1, §4.2) and `drawFrame` (§5.5) |
| `ui/.../sports/NflScorePainter.kt` | the takeover (§5.2, §5.5): glyph atlas, sprites, bands, glow masks |
| `ui/.../sports/NflTabPainter.kt` | the tab (§4.3, §4.5 red half, §5.3 ADD pulse) |
| `ui/src/test/.../NflScreenshotTest.kt` | §10.2 |

### WP-4: overlay wiring (Sonnet)

| File | Change |
|---|---|
| `ui/.../sports/NflBug.kt` | the shell copy (§4.6), `NflRun` (the GA trigger and the §5.7 pair), `NflTabRun` (PP rules, S2 key, `rzDriver`), pin and release, bakes, the live region and `Describe` |
| `ui/src/test/.../NflBugDrawTest.kt`, `NflBoundsTest.kt`, `app/src/androidTest/.../NflFramesTest.kt` (new) | §10.2, §10.4 |

### WP-5: league switch UI, picker, texts, fake panel (Sonnet)

| File | Change |
|---|---|
| `ui/.../sports/SportsScreen.kt`, `SportsViewModel.kt` (the league-aware fallback, S6), `GameCard.kt` | §7 |
| `ui/.../sports/SportsText.kt`, `ui/.../full/PlayerText.kt` | the NFL bodies behind the commit-0 signatures (S4) |
| `core/.../full/state/PlayerStateMachineImpl.kt` | the League row, the week divider |
| `ui/src/test/.../NflSportsTest.kt`, `NflPickerTest.kt`, `NflGameCardFitTest.kt` (new) | §10.2 |
| `tools/fakepanel/sports_nfl.py`, `test_sports_nfl.py`, `sample_espn.py`, `README.md` | §9, §1.6 |

**Gates:**
- Every WP ends with `./gradlew :core:test :ui:testDebugUnitTest :data:testDebugUnitTest :app:assembleDebug lintAll`.
- `git diff main --stat -- '*Test.kt'` lists only new files (scope rule).
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
| `NFL_COIN_TOSS_TYPE` | "70" (S8) |
| `NFL_KICKOFF_TYPE` | "53" (N6) |
| `ESPN_MAX_BODY_BYTES` | 1 048 576 |
| `ESPN_LOGO_PX` | 200 |
| `NFL_TAB_INSET` | 37 (Large 46); depth, hidden, radius, shadow and divider: the `PP_*` values |
| `NFL_TAB_ICON` / `_STROKE` / `_ICON_GAP` | 20 / 1.4 / 10 |
| `NFL_TAB_LEAD_WEIGHT` / `NFL_TAB_TAIL_WEIGHT` / `NFL_TAB_TRACK_EM` | 700 (Bold) / 600 (SemiBold) / .05 |
| `NFL_RED_ZONE` / `NFL_RED_ZONE_FADE_MS` | `#C8102E` / 250 |
| `NFL_TAKEOVER_MS` / `NFL_POP_MS` / `NFL_DOUBLE_MARK_MS` / `NFL_PAIR_SECOND_START_MS` | 3 600 / 900 / 9 000 / 4 500 |
| `NFL_FLASH_GRID_MS` / `NFL_FLASH_START_MS` / `NFL_FLASH_CYCLES` / `NFL_FLASH_ALPHA` | 450 / 450 / 3 / .42 |
| `NFL_POSTS_IN_MS` / `_IN_DELAY_MS` / `_HIT_START_MS` / `_HIT_CYCLES` / `_ALPHA` / `_HIT_ALPHA` | 400 / 200 / 1 800 / 3 / .4 / .95 |
| `NFL_TAB_PULSE_CYCLES` / `_GAIN` | 8 / .45 |
| `NFL_STREAK1_W` / `NFL_STREAK2_W` / `NFL_STREAK_FROM` / `_TO` / `NFL_STREAK2_START_MS` | 51 / 19 / −85 / 616 / 1 800 |
| `NFL_BALL_PX` / `NFL_BALL_INK_R` | 34 / 15 |
| `NFL_RUN_FROM` / `_TO` / `_Y` / `_ROT` / `_ALPHA` / `_ALPHA_IN` / `_ALPHA_OUT` / `NFL_RUN_START_MS` / `_MS` / `NFL_RUN_BAND` | −42 / 562 / 18 / 900° / .4 / 15 % / 85 % / 300 / 1 300 / 4.4 |
| `NFL_ARC_P0` / `_P1` / `_P2` / scales / rotations / `_ALPHA` / `_ALPHA_IN` / `_ALPHA_OUT` / `NFL_ARC_START_MS` / `NFL_ARC_MS` / `NFL_ARC_BAND` | **(33, 44)** / (202, −7) / (366, 12) / 1, .75, .45 / 0, 360, 720 / .55 / 10 % / 85 % / 300 / 1 400 / 35 |
| `NFL_POSTS_X` / `_Y` / `_W` / `_H` / `_DY` | 355 / 9 / 43 / 52 / 8 |
| `NFL_WORD_PX` / `NFL_WORD_TRACK_EM` / `NFL_WORD_SHADOW_DY` / `_ALPHA` | 48 / .03 / 2 / .28 |
| `NFL_LETTER_START_MS` / `NFL_LETTER_MS` / `NFL_LETTER_STAGGER_MS` / `NFL_LETTER_DROP` / `NFL_LETTER_MID` / `_FROM_SCALE` / `_MID_SCALE` / `_OVERSHOOT` | 420 / 420 / 55 / 22 / .6 / 1.9 / .94 / −2 |
| `NFL_WORD_PULSE_START_MS` / `_CYCLE_MS` / `_CYCLES` / `NFL_WORD_PULSE` | 1 400 / 500 / 3 / 1.05 |
| `NFL_LOGO_START_MS` / `_MS` / `_DX` / `_FROM_SCALE` / `_FROM_DEG` / `_MID` / `_MID_DX` / `_MID_SCALE` / `_MID_DEG` / `_SHADOW_DY` / `_SHADOW_ALPHA` | 450 / 600 / −32 / .4 / −25 / .7 / 2 / 1.1 / 4 / 2 / .25 |
| `NFL_TAKEOVER_PAD` / `NFL_TAKEOVER_GAP` | 24 / 14 |
| `NFL_POINTS_PX` / `NFL_TALLY_PX` / `NFL_BLOCK_W` / `NFL_BLOCK_GAP` / `NFL_POINTS_START_MS` / `_MS` / `_DX` / `_FROM_SCALE` / `_MID` / `_MID_DX` / `_MID_SCALE` | 32 / 20 / 74 / 2 / 1 100 / 500 / 19 / .6 / .7 / −1.6 / 1.12 |
| `NFL_POP_FROM_SCALE` / `NFL_POP_K1` / `_K1_SCALE` / `NFL_POP_K2` / `_K2_SCALE` | 1.5 / .4 / .9 / .7 / 1.06 |
| `NFL_LETTERS_MAX` / `NFL_ATLAS_SCALE` | 12 / 1.5 |

---

## 13. Emulator verification checklist (`TheBox_TV_API30`, `python3 tools/fakepanel/fakepanel.py --port 8080 --sports --sports-nfl`, debug `files/debug/sports_base`)

1. Sports tab: NHL / NFL chips. Pick NFL; restart the app; NFL is still selected. A week board with the Thursday
   final, the featured game, Sunday and Monday cards ("Sun 4:25 PM", "Mon 8:15 PM"). Last / Next week work; at an
   edge the chip is disabled.
2. Follow KC @ BUF before the start: the toast; **no bug in FULL through the coin toss** (ruling 4, S8).
3. The first window after the coin toss: the bug appears with the tab "KC BALL | OWN 25" (inferred, no flicker over
   two polls).
4. KC drive: "3rd & 2" and "OPP 38"; "1st & Goal" with the tail cross-fading to red **RED ZONE**. The clock steps per
   window, never counting down between polls.
5. The 10 s score-before-play window (seen or not, depending on the poll phase), then the **TOUCHDOWN** takeover:
   - the ball run (inside the pill's rounded ends), the letters, the logo, "+6" and the tally "6 – 0";
   - the pill glows; the tab "TOUCHDOWN | KC +6" pulses in KC red;
   - then the pop, then "KC BALL | EXTRA POINT" with "PAT", **no RED ZONE**.
6. **EXTRA POINT** (arc and posts), then "KC KICKOFF | OWN 35" and "KICKOFF", no RED ZONE.
7. BUF ball with no possession: the tab closes, then opens in **BUF blue** "BUF BALL | OWN 25", with no flicker back to
   KC.
8. "End 1st" across both columns; the tab closed. Q2: FIELD GOAL (no PAT after it); then the **+7 pair**:
   TOUCHDOWN "+6" (13 – 3), the 13 popping, "KC BALL | EXTRA POINT", then EXTRA POINT "+1" (14 – 3). "Halftime".
9. Q3: SAFETY (the release cuts to "KC KICKOFF | OWN 20"; then BUF's ball); the defensive TOUCHDOWN by BUF (the tab
   reads "BUF BALL | EXTRA POINT" at once); the try drawn as a down still reads PAT; TWO-POINT. Q4: a turnover into
   BUF's red zone; FG; SAFETY by KC; the 16–16 tie; "End 4th".
10. OT: "OT"; RED ZONE; the walk-off TOUCHDOWN on the Final body (the tab pulses, then closes), "Final/OT", the bug
    gone 5 min later.
11. All four corners and Large, with a score in each: the tab opens upward at the bottom corners; the info bar lifts
    a bottom bug; the picker hides the right corners; subtitles move a bottom bug to the top. Place mode shows the
    NFL sample at the NFL width.
12. Quick bar → Score bug: the League row toggles NHL/NFL; the followed NFL game stays on top while NHL is listed.
    Follow an NHL game while the NFL bug shows: the NFL bug leaves, and the NHL bug arrives when its game is live.
13. Developer options → animator duration scale off: the still version (frames A/B; the pair's four frames; RED ZONE
    cuts).
14. A user delay of 30 s: everything shifts 30 s, including the takeovers, the pair and the tab.
15. `--sports` alone (no `--sports-nfl`): the NHL behaves exactly as before; the NFL tab shows "ESPN did not answer".
16. `POST /admin/sports {"restart": true}` restarts both timelines (N8).

**Device items:**
- frame time of a takeover and of a pair on a Fire TV Stick (ruling 20);
- VoiceView (one announcement per pair);
- the colours on a real TV (dark tabs: CHI, LV, NO, PIT; RED ZONE beside red teams: KC, ATL, ARI, SF, TB);
- the LV dark logo legibility;
- the live samples of §1.6.

---

## 14. Owner answers, and what remains

The owner answered this design's questions on 2026-11-08 (rulings 25–28):

| Ruling | Answer | Where |
|---|---|---|
| 25 | +7 / +8 in one update: **two takeovers back to back** (TOUCHDOWN +6, then EXTRA POINT +1 or TWO-POINT +2) | §3.2, §5.7 |
| 26 | **RED ZONE** on the tab's right half, in red, inside the opponent's 20 | §3.5, §4.5 |
| 27 | Colour picks: BAL purple + gold, DAL navy + silver, JAX teal + gold, TB red + pewter | §6 |
| 28 | The NFL list is the ESPN week, with Last · This · Next week chips | §7.2 |

**Lead decisions taken in this revision** (each can be overturned cheaply):
1. **The kick-off rule** (S8): the bug appears with the first logged play after the coin toss, polled at the pre-game
   cadence, so up to about 40 s late.
2. **The red half starts at the divider**, not at the tab's geometric centre ("the right half"), so the red frames the
   words. `#C8102E` (white 6.9–7.8 : 1 over the gradient). The cross-fade is 250 ms.
3. **The pair's in-between tab** is "KC BALL | EXTRA POINT" (unpulsed), even when the second score turns out to be a
   two-point conversion. That is what separate updates would have shown.
4. **The arc starts 12 px right of the mock** (D8), to stay inside the pill without `clipPath`.
5. **The fake timeline is now 985 s** (16.5 min), to give every window ≥ 25 s.
6. **A league change of the follow swaps the bug with no fade** (§4.6).

**Owner question (one):**
1. **Red teams in the red zone.** For KC, ATL, ARI, SF and TB the red half sits beside a red lead half, and only the
   words "RED ZONE" tell it apart. Is that acceptable, or should the red half for those five teams use a deeper red
   (for example `#7A0019`)? Proposed: accept, as ruling 12 keeps the team's own colour on the lead half.

---

## 15. Deviations from the mock (recorded)

| ID | Mock | Here | Why |
|---|---|---|---|
| D1 | 980 × 144 pill, 74 px score, 30 px down, 26 px quarter | 520 × 70, the NHL's type, down 22 | one bug family; the 18 px floor at TV size |
| D2 | BUF pop `#4f8bff` | the shared pop rule (`#E0798D`) | ruling 12 and one rule for 64 teams |
| D3 | flash 4 × 300 ms, posts 3 × 300 ms | one 450 ms grid | under 3 flashes/s (GA B3) |
| D4 | team-name line 15 px, tally "KC 24 · BUF 17" at 14 px | name dropped; tally "24 – 17" at 20 | ruling 7's rule; the type floor |
| D5 | the tab gradient's far stop = base (KC); lighter than base (BUF) | `mixBlack(.10)` for all | ruling 17, one gradient for both leagues |
| D6 | the pill glow fixed KC red for both teams | the scorer's `glow` | ruling 12 (as GA N2) |
| D7 | the tab is instant on every change | slide in and out at breaks and on a possession change (PP track); cuts otherwise; RED ZONE cross-fades | the tab is otherwise a static strip; a turnover should read |
| D8 | the arc starts at (40, 90) mock = (21, 44) | (33, 44) | the ball stays inside the stadium without `clipPath` (B5) |
| D9 | tab tracking .02 em | .05 em | PP's tab tracking, one tab family (N3) |
| D10 | the tab shows the score label immediately | the old content until 216 ms | the GA no-spoiler hold |

---

## Review reconciliation (2026-11-08)

Review: `docs/review/2026-11-08-nfl-design-review.md`. Owner rulings 25–28 are folded in (§14).

| ID | Disposition | What changed | Where |
|---|---|---|---|
| B1 | **accepted** | `FieldPos(territory, yard)`: whose half, from `possessionText`'s prefix (independent of `possession`) or the home-relative `yardLine`; OWN / OPP / MIDFIELD decided in `:core` after inference. Test: "ATL BALL \| OWN 40" on the no-possession fixture | §1.3, §2, §3.4, §10.1 |
| B2 | **accepted** | the try bookkeeping as a rule set: set on any +6 (flagged or not) and from a `Dead` with a TD `lastPlay`; clear on other scores, `Kickoff`, a `Down` with a different period/clock, reset, the back-in-time path; never on a phase change alone; clear before set; a stale or same-clock `Down` renders as the try; state written only in `advanceDisplay`. Tests: the two +2 replays; the defensive TD | §3.3, §3.6, §5.4, §9.2, §10.1 |
| B3 | **accepted** | commit 0 defines the lenient DTO in `:core` (`EspnDto.kt`, typed spine, `JsonElement?` leaves) and the final `EspnMapper` signatures; WP-2 only streams into it; a smaller `EspnGameDto` for `game()`. Test: a re-typed body still maps | §1.3, §1.5, §11 |
| B4 | **accepted** | the branch at the top of `ScoreBug(...)` with `key(league)`; `ScoreBugWith` byte-unchanged; the league held while hidden; `NflBug` owns a copy of the shell (the choice written down); `PlacementInput.league` set in `FullScreenHost` (commit 0); an NFL place-mode sample; a seam test in `NflBugDrawTest` | §4.6, §11, §10.2 |
| B5 | **accepted, option (b)** | option (a) needs `setLocalMatrix`, which GA §2.2 rejected (it reallocates on API 26+). So: inner bands. The run under `clipRect` 4.4 / W − 4.4 with a measured ink radius ≤ 15; the arc and the posts under the straight-section clip 35 / W − 35, the arc's P0 moved to (33, 44) (D8). The assert is kept at both sizes | §5.1, §5.5, §10.1, §10.2, §12 |
| S1 | **accepted** | `NflTeamColours` = `derive(...).copy(tab = base or accent)`; `derive` untouched; `tab == base` pinned for 32; the EDM tab pinned; the "base for both" sentence corrected | §4.3, §6, §10.1 |
| S2 | **accepted** | the key is `(tabOn, tab.side)`, evaluated only while not pinned; the pin and release rebase without a kick; the release cuts across a side change, or CLOSEs; `pinTabOn` from the previous frame's drawn tab; the halftime-FG and walk-off rows rewritten (label, then CLOSE); tests | §4.3, §5.4, §10.2 |
| S3 | **accepted** | `clockSecs` when either line is `Clock`; `knownView()` NFL-aware, with a one-line `NoUpdate` in breaks; tests | §3.1, §4.2, §10.1 |
| S4 | **accepted** | `SportsText.kt` (league params, `Halftime` branch), `PlayerText.kt`, `FullScreenHost.kt` in commit 0, their bodies in WP-5; the bounds and root-tree checks in a new `NflBoundsTest` (WP-4) | §2, §11 |
| S5 | **accepted** | `TeamLine.shortName: String? = null` (NFL only), for the cards, the live region and `Describe` | §1.3, §2, §4.2, §8 |
| S6 | **accepted** | week keys `w2026-2-9`, parsed in one place; the NFL has no date fallback; a null neighbour disables the chip; the off-season board defined; tests | §1.3, §7.2, §10 |
| S7 | **accepted** | `GoalHooks.colours` unchanged (NFL has its own hook); the single-source secondary constructor; all NFL tests in new files, checkable with `git diff --stat` | §2, §10, §11 |
| S8 | **accepted, rule chosen** | `in` with period 1, 0–0, and `lastPlay` absent or 70 (Coin Toss) maps to `PRE`: no bug until the first logged play after the coin toss (the kick-off). The coin-toss fixture is `PRE`, the first-down fixture `LIVE` | §1.2, §1.3, §4.2, §10.1, §12 |
| S9 | **accepted** | the timeline rebuilt: every window ≥ 25 s but the one 10 s window; BUF receives KC's free kick; each scoring row spells out its `situation`; the two-point try drawn as a down; 985 s | §9.2, §9.3 |
| N1 | taken | "3 am ET from the calendar's own dates"; week 1's 10 days noted | §1.1, §7.2 |
| N2 | taken | a de-duplicated glyph atlas (20 glyphs): about 290 KB at 1080p, 1.2 MB at 2160p; the size check in the screenshot test | §5.2, §10.2 |
| N3 | taken | BUF's mock gradient noted; the tracking difference recorded (D9); the tail masks are baked from SemiBold | §4.3, §15 |
| N4 | taken | §12 completed (letters, pad, gaps, icon gap, weights, alpha keyframes, pop keyframes, pair, red zone, bands) | §12 |
| N5 | taken | the correction guard: no flag for a rise back to a score shown within `QUEUE_KEEP_MS` (NFL only) | §3.2 |
| N6 | taken | `lastPlay` 53's team (the receiver) is the first inference source | §3.4 |
| N7 | taken | `game()` retries once against the week board before `NotFound`; the source remembers each event's date | §1.4, §1.5 |
| N8 | taken | the restart hook lives in `fakepanel.py`'s dispatch; `/admin/sports` restarts both | §9.1, §9.2, §11 |
| N9 | taken | the instrumented NFL test arms on the score change only and measures [score, score + 4.5 s] (+ 9 s for a pair) | §10.4 |
| N10 | taken | the `EspnGameDto` for `game()` skips the calendar | §1.3, §1.5 |
| Q1–Q3 | owner rulings 25–27 | see §14 | §14 |

## Lead amendment (WP-5)
Commit 0 lacked what the picker's League row needs. The lead approved these additions, all trailing and defaulted (no
existing test changes): `ScoreFacts.league: League? = null` (null = no League row); `PlayerEffects.setScoreLeague(League)`
(no-op default; `PlayerEffectsImpl` writes `updateSports { copy(league = it) }`, `FakePlayer` records it);
`PlayerViewModel.forwardScores` passes `league = p.league`; `PickerChoice.ScoreFailed(kind, league = NHL)` and
`PickerChoice.ScoreDayDivider(dayKey, current = false)`, so the NFL texts ("ESPN did not answer", "This week" / the week
label) reach `PlayerText` without a `PickerSheet` change.

## Lead sign-off (2026-11-08)
Accepted as revised, with these rulings on §14:
1. **Pre-game cadence after the coin toss: overridden.** A body whose ESPN state is `in` but which the S8 rule maps to
   PRE polls at the LIVE cadence (no bucket skip), so the bug appears within one poll of the first logged play, not up
   to ~40 s late. Only genuinely `pre` bodies keep the pre-game cadence. WP-1 owns it (machine `pollAt`), with a test.
2. Red half from the divider: accepted.
3. "EXTRA POINT" on the tab between a pair's takeovers, even before a two-point try: accepted.
4. D8 (arc start +12 px): accepted.
5. Fake panel at ~16.5 min, +8 pair in unit tests and a script example: accepted.
6. League swap with no fade: accepted.
7. Shell copy in `NflBug` (~150 lines) to keep the NHL path byte-unchanged: accepted; a later refactor may share it.
Owner question (red zone beside KC/ATL/ARI/SF/TB's own red): lead default = accept (the words carry it); raised to the
owner with the build.
Process: commit 0 by an Opus agent in a worktree to the table in §11, merged by the lead; then WP-1…WP-5 in parallel
worktrees; integration pass on main (emulator owner); code review; fix pass.
