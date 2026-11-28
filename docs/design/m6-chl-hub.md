# CHL in the Sports Hub: pre-game and final screens from HockeyTech (design)

**Status:** reconciled with `docs/review/2026-11-27-chl-hub-design-review.md` under the lead ruling of 2026-11-27 (§9).
Nothing here is implemented yet.

**Scope:**
- The OHL, WHL and QMJHL hub panel and its sheets **before the game** (Matchup) and **after the final** (Goals, Box score).
- **Live is unchanged in this milestone.** The live state keeps today's board rows. The live upgrade is a later step
  with its own gate (§7).

**Baseline:**
- `docs/design/m6-sports-hub.md`: the hub (§4 NHL detail, §6 sheets, §7.1).
- `docs/design/m6-nfl-hub.md`: how a second league plugged in.
- `docs/design/m6-chl-leagues.md`: the CHL feed rules (caps, keys, redaction, no persistence).

**Worked examples.** Every example value in this note was checked by hand against the fixtures in
`core/src/testFixtures/resources/sports/chl/hub/`. Implementers turn them into tests (§8).

---

## 0. Decisions at a glance

| # | Decision |
|---|---|
| D1 | **The CHL gets a detail source,** for pre-game and final only. It uses HockeyTech `lscluster`, the board's host and keys. Pre-game reads `feed=gc&tab=preview` plus goalies per team (`topgoalies`) and the two league tables (`statviewfeed` `teams`, for ranks). The final reads `feed=gc&tab=gamesummary`. These are the views the leagues' own game-centre pages use (§1.2). |
| D2 | **NHL parity where the data exists.** Pre-game: Record, Last 10, GF/GP, GA/GP, PP%, PK% with league ranks; the season series; points leaders (season); tonight's goalies (the posted starter marked) with W-L-OTL-SOL, GAA and SV%; the last 5. Final: game-winning goal, three stars, team stats, the goal list with assists and strength, the box score (skaters, goalies, a goals-and-shots line score), venue and attendance. |
| D3 | **No video.** HockeyTech has no clip feed. Final slots: Watch from start only when a catch-up airing exists, then Goals, then Box score. With no catch-up the order is Goals · Box score · empty. The media is always the matchup art, with the caption "Venue · Attendance N". Highlights come **only through a sanctioned feed**, never by getting past the site's bot check (Q2). |
| D4 | **The `HubLeagueUi` seam takes the league choice.** The sheets branch on the data present, never on the league (§3). `HubSource` gains `HOCKEYTECH`. |
| D5 | **Live and postponed send no detail request.** The repository publishes `BoardOnly` for a CHL game whose effective phase is LIVE, INTERMISSION or POSTPONED. Pre-game re-reads every 30 min. The final is read once, then every 5 min while the three stars are missing, for at most 1 h. The box score comes from the same `gamesummary` parse. |
| D6 | **Memory only.** No body, detail or URL is persisted or logged. Bodies are capped at `MAX_BODY_BYTES` (512 KB; the largest body read is 48 KB). They are decoded whole (m6-chl-leagues §2.7). The `Parameters` echo, which carries the key, is never read. Only **mapped** ranks are cached, never table bodies. |
| D7 | **`CHL_HUB_ENABLED`** is a build-time kill switch (as `ESPN_VIDEO_ENABLED`). Commit 0 lands it `false`. Integration of P1–P3 sets it `true`, its default from then on. There is no user setting. |

---

## 1. What the feed offers (fact-find, 2026-11-27)

### 1.1 How it was checked

- Requests made:
  - 26 GETs to `lscluster.hockeytech.com`, at least 3 s apart, with a generic UA and the app's keys (scrubbed from
    every saved body);
  - 3 HEADs to `assets.leaguestat.com`;
  - about 10 page and JS fetches from the league sites. ontariohockeyleague.com, whl.ca and theqmjhl.ca redirect to
    `chl.ca/{ohl,whl,lhjmq}`.
- Games used:

  | League | Final | Pre-game |
  |---|---|---|
  | OHL | 29239 (OTT 5 @ BAR 3) | 29246 (NIAG @ LDN) |
  | WHL | 1023337 (RD 4 @ CGY 3) | 1023348 (CGY @ MH) |
  | QMJHL | 32952 (Chi 5 @ NFL 4, OT) | 32953 (Chi @ NFL) |

- Fixtures and their trimming: the README beside them.
- **Note:** the fact-find got past Cloudflare on chl.ca with a browser UA. The app never does that (Q2).

### 1.2 What the league web sites request

All three leagues share one WordPress site on `chl.ca`, with the `fanreach-hockey` plugin (`build/gamecentre.js`,
`build/index.js`).

| Site feature | Request | Confirmed with the app's key |
|---|---|---|
| Scoreboard strip | `modulekit&view=scorebar` (every 30 s) | known (m6-chl-leagues; not used) |
| Game centre box score, scoring, penalties, stars, officials | `feed=gc&tab=gamesummary` | yes, all 3 |
| Game centre preview / head-to-head | the server-rendered `preview` object (= `feed=gc&tab=preview`) | yes, all 3 |
| Running clock | `feed=gc&tab=clock` | known (`lscluster/gc-clock-ohl-29191-2042.json`) |
| Play by play, shot map | `feed=gc&tab=pxpverbose` (every 30 s) | yes, OHL (not used) |
| Standings, stats leaders | rendered on the server. The same data is in `statviewfeed&view=teams` (`special=true` for PP/PK) and `modulekit&view=statviewtype&type=topgoalies` | `teams`: OHL both, WHL special, QMJHL standard; `topgoalies`: all 3 |
| Highlight videos | WordPress `video` posts (Brid.tv player, `target-video.com` MP4) behind Cloudflare, tied to a game by title only | **no feed** |
| Official game report, lineup sheet | `lscluster.hockeytech.com/game_reports/…`, `/game-lineups/…pdf` | HTML/PDF; not used |

- The sites embed a different, site-level key. It is not used and not written anywhere.
- The sites hide hits for the OHL and hide "dangerous shots".

### 1.3 The views, per league

Each entry is raw size / gzip size on the wire.

| View | OHL | WHL | QMJHL | Cache-Control |
|---|---|---|---|---|
| `gc` `preview` | 29.9 / 4.8 KB | 17.6 / 3.6 KB | 30.9 / 4.8 KB | `max-age=60` |
| `gc` `gamesummary` (final) | 42.8 / 5.7 KB | 37.7 / 5.0 KB | 48.3 / 6.2 KB | `max-age=60` |
| `topgoalies` + `team_id` | 12.0 / 2.1 KB | 4.3 / 1.3 KB | 4.4 / 1.3 KB | `max-age=240` |
| `statviewfeed` `teams` (standard / special) | 12.8 / 2.2, 11.9 / 1.8 KB | – / 13.3 / 2.0 KB | 11.4 / 2.0 / – KB | `no-store`; JSONP `([ … ])` around an **array** |

Facts the design depends on, all from the fixtures:
- **Hits:** tracked only by the QMJHL (`current_season.sopt_track_hits`). Blocked shots and skater TOI are tracked by
  no league. Goalie TOI exists.
- **Shots:** `totalShots` is the shots on goal in every league. `totalShotsOn` is 0 in the WHL.
- **Preview lineups:** the home lineup is `pv.home_team.lineup`. The **visitor's is `pv.visiting_team.lineup`**, a
  separate top-level key; `pv.visitor_team` has none. The WHL's are null.
- **Starters:** they are marked by `start == "1"` in the OHL 29246 visitor (Haffner), both QMJHL 32953 sides (Chi
  Lee, NFL Denault) and both OHL 29239 sides.
- **The preview is frozen at the game's start.** Read after the final it still says BAR 5-17
  (`season_records.as_of_game`), while the board says 5-18. It is a pre-game view only.
- **`topgoalies` with `team_id`** lists everyone who played for the team this season, traded and inactive players
  included. OHL t14 has Medvedev (`latest_team_id "6"`, `num_teams "2"`, `active "0"`) and Nadalin (`active "0"`).
  `games_played` is a string.
- **Goalie stats:** `gs.goalies.{side}` has one entry **per stint**, so OHL 29239 lists Nikonovich twice.
  `gs.*_team_lineup.goalies` has one line per goalie with the stints added (Nikonovich 3501 s "58:21", SA 38, SV 33).
- **League tables:** each `statviewfeed` row joins to a team by `data[].prop.team_code.teamLink` (= the team id). The
  QMJHL codes are mixed case and its names read "Chicoutimi, Saguenéens".
- **Rank column:** the tables' `rank` is the sort position, not a rank. LDN and SOO are both 19/87 on the power play;
  the feed says 10th, and the true shared rank is 9th.
- **Headshots:** `https://assets.leaguestat.com/{client}/240x240/{player_id}.jpg` answered 200 for one player per
  league. `topgoalies.photo` (60×60) confirms the path.
- **`meta` key sets differ** (the QMJHL has no `capacity`, WHL lineups have no `line`). The mapper is lenient.

### 1.4 What cannot be done

| Wanted | Why not |
|---|---|
| Recap, condensed game, goal clips | No HockeyTech video feed. The chl.ca highlights are not a sanctioned feed (Q2). |
| Skater TOI, blocked shots, giveaways/takeaways; hits outside the QMJHL | Not tracked. |
| Goals and assists leaders | `leadingScorers` is the team's top 5 by points. v1 shows Points (season) only. A `type=topscorers&team_id` capture would settle the rest (lead task, §7). |
| Assist season totals | Only `scorer_goal_num` is given. |
| Injuries, broadcasts list | Not in any view. |
| Live detail | No live `gamesummary` was captured. Live keeps today's board rows (§7). |

---

## 2. Panel content (geometry unchanged: 720 × 864, inner 664, `HubPanelPlan`)

Abbreviations:
- `pv` = `GC.Preview`, `gs` = `GC.Gamesummary`;
- `tg` = `SiteKit.Statviewtype` (topgoalies), `tm` / `ts` = the standard / special league tables;
- `V` = visitor (away), `H` = home.

### 2.1 Header rows

**Pre-game** (header "SEASON"; the NHL's six rows):

| Row | Value | Source | Missing / zero |
|---|---|---|---|
| Record | "W-L-OTL-SOL" | `pv.{visitor,home}_team`: `wins`-`losses`-`ot_losses`-`shootout_losses` | "–" (no board fallback: the board has no record field, and it is current while `pv` is as of the game's start) |
| Last 10 | `last10` as given | `pv.*.last10` | "–" when absent or when `gp == 0` |
| Goals for per game (rank) | `goals_for / gp`, `%.2f` (Java HALF_UP) | `pv.*`; rank over `tm` | `gp == 0` → "–", no rank |
| Goals against per game (rank) | `goals_against / gp` | `pv.*`; rank over `tm`, lower is better | same |
| Power play % (rank) | `power_play_goals / power_plays` as 0..1 | `pv.*`; rank over `ts` | `power_plays == 0` → "–", no rank |
| Penalty kill % (rank) | `1 − power_play_goals_against / times_short_handed` as 0..1 | `pv.*`; rank over `ts` | `times_short_handed == 0` → "–", no rank |

**Rank rule** (§4.4: computed in `:core` from integers):
- Ranks are standard competition ranks: ties share a rank, and the next rank skips.
- Values are compared cross-multiplied, never the rounded strings or the feed's `rank`.
- Rows with `games_played == 0` or a zero denominator are left out.
- A team missing from the table, a failed table read with no cached ranks, or the **playoffs** → the value without a
  rank.

**Worked examples** (fixtures):

| Game | Away | Home |
|---|---|---|
| OHL 29246 NIAG @ LDN | 10-10-3-0 · 3-5-2-0 · 3.09 (15th) · 4.04 (18th) · 17.0% (19th) · 70.9% (18th) | 13-8-1-2 · 4-5-0-1 · 3.75 (8th) · 3.63 (12th) · 21.8% (9th) · 68.3% (20th) |
| WHL 1023348 CGY @ MH | 13-9-1-1 · 5-3-1-1 · 4.08 · 3.58 · 25.3% (5th) · 82.2% (6th) | 14-6-1-2 · 7-1-1-1 · 4.09 · 3.13 · 24.6% (8th) · 82.5% (4th) |
| QMJHL 32953 Chi @ NFL | 12-9-0-1 · 6-3-0-1 · 3.64 (7th) · 3.36 (9th) · 17/80 · 84.9% | 14-8-2-2 · 5-3-1-1 · 3.38 (11th) · 3.15 (7th) · 14.4% · 77.5% |

Notes on the examples:
- LDN GA 87/24 = 3.625 exactly, which `%.2f` rounds HALF_UP to "3.63".
- The WHL GF/GA and QMJHL PP/PK tables were not captured, so those cells have no rank in the fixture tests.
- Chi's 17/80 = 0.2125 is a rounding edge for `%.1f`. The test pins the formatter's output, not this note.
- The `Ranked.value` of every rate is 0..1 (B3e), so `HubPanelContents.pct` never misreads a 1.0.

**Final** (header "TEAM STATS"; live is §7):

| Row | Source (`gs`) | Rule |
|---|---|---|
| Shots on goal | `totalShots` | always |
| Power play | `powerPlayGoals` / `powerPlayCount` → `Fraction` | always |
| Faceoff % | `totalFaceoffs.*.won / att` → `Pct` 0..1 | only when both `att > 0` |
| Penalty minutes | `pimTotal` | always; lower is better |
| Hits | `totalHits` | only when the league is in `CHL_HITS_LEAGUES` (QMJHL) |

Worked examples:

| Game | SOG | PP | FO% | PIM | Hits |
|---|---|---|---|---|---|
| OHL 29239 (OTT–BAR) | 38–21 | 2/4–0/2 | 53.2%–46.8% | 6–10 | – |
| WHL 1023337 (RD–CGY) | 36–28 | 1/2–0/3 | 44.3%–55.7% | 10–8 | – |
| QMJHL 32952 (Chi–NFL) | 28–27 | 1/4–0/3 | 46.0%–54.0% | 14–16 | 2–10 |

While the final detail loads, or when it failed with nothing cached, the board's Shots row is shown under the
state line (today's CHL content). The panel never shows less than today.

### 2.2 Summary (at most two lines; `NhlHubUi.summary`'s formatter and strings)

| Phase | Line 1 | Line 2 |
|---|---|---|
| Pre | season series from `pv.HeadToHeadRecord` | "Points leaders: …" (the **season** string, §5) from `pv.*.leadingScorers[0]` (`last_name points`, away first) |
| Final | game-winning goal: `gs.goals[]` with `game_winning == "1"`, else `HubSummary.winningGoal`; drawn by `goalSentence` | "Stars: …" from `gs.mvps[0..2].last_name`, skipping null entries; all null → no line |
| Final, shootout | "Won in the shootout." (board `finalKind == SO`) | Stars |

Series rules:
- Wins = `HeadToHeadRecord.{visiting_team,home_team}.w`, which already includes `otw`.
- Meetings = `previous_meetings.size`. When that is empty but there are wins, meetings = the sum of wins.
- Playoffs (`pv.current_season.playoff == "1"`): no series line (Q8).

Worked examples:

| Game | Line 1 | Line 2 |
|---|---|---|
| OHL 29246 | "First meeting this season" | "Points leaders: Edgar 22 · Zocco 27" (ties Edgar/Smith and Zocco/Brown resolve to entry `[0]`) |
| WHL 1023348 | "Season series: CGY leads 2–1" | "Points leaders: Sanderson 31 · Ruck 33" |
| QMJHL 32953 | "Season series: Chi leads 1–0" | "Points leaders: Lecompte 29 · Goguen 27" |
| OHL 29239 final | "Game-winning goal: Dietsch (2nd) from Houben, 3rd period 10:53." | "Stars: Manza, Bowes, Dietsch" |
| WHL 1023337 final | "Game-winning goal: Hamilton (17th) from Andersen and Kuzma, 3rd period 19:25." | "Stars: Hamilton, Kuzma, Anaka" |
| QMJHL 32952 final | "Game-winning goal: Dubé (4th) from Dufour and Hartl, OT period 4:16." | "Stars: Dubé, Lecompte, Norman" |

The OT wording is today's `goalSentence` with `periodWord(OT)`; it is not new to the CHL.

### 2.3 Media and caption

| Phase | Media | Caption |
|---|---|---|
| Pre | matchup art | the board's `venue` (as today) |
| Final | matchup art | `sports_hub_caption_venue_attendance` from `gs.venue` and `gs.meta.attendance` (grouped); no attendance → the venue |
| Live, postponed | today's | today's |

Final examples:
- "Sadlon Arena · Attendance 2,801"
- "Scotiabank Saddledome - Calgary, AB · Attendance 2,226"
- "Mary Brown's Centre · Attendance 4,852"

There is never a poster, play disc or "Recap not posted yet".

### 2.4 Slots (three, frozen while focus is in the panel)

| Phase | Slots |
|---|---|
| Pre | Follow (Retry when the detail failed and is retryable) · Channels · Matchup (enabled by `hasMatchupRows`) |
| Live | today's: Follow · Channels · empty |
| Final, catch-up airing | Watch from start · Goals · Box score |
| Final, no catch-up | Goals · Box score · empty |
| Final, detail failed (retryable) | `[Watch from start if catch-up] + Retry + Goals + Box score`, first three |
| Postponed | empty · empty · empty |

- `slotReasons`: all null.
- The final channel line shows the catch-up airing, which `channelLine` already does for any league with `detailed`.
- `failureText`'s next step reads the slots on screen, as today.

### 2.5 Postponed and playoffs

- **Postponed:**
  - no detail request (D5);
  - rows: the existing CHL board state line plus one reason line, `sports_hub_postponed_chl`: "The %1$s lists this
    game as postponed. Its new date shows here once the league sets it.";
  - slots empty.
- **Playoffs**, when `pv.current_season.playoff` is "1":
  - the league tables are not requested;
  - no ranks and no series line;
  - every other row as usual.

  These are the safe fallbacks until a playoff capture exists (Q8).
- **Pre-season and first games** (`gp == 0`): the zero guards of §2.1.

---

## 3. Sheets (data-driven: no new `league` branch in a shared sheet)

### 3.1 Matchup (pre-game; `MatchupSheet.kt`)

- The existing `nfl` branch stays as it is.
- The non-NFL branch, `matchupRows(d)`, gains three things, each driven by data the NHL never fills:
  - the Leaders header is "Leaders · this season" when `d.leadersSeason` (otherwise "Leaders · last 5 games");
  - a goalie with `Goalie.starting` shows the "Starting" mark;
  - a Last 5 section, built with the NFL's recent-row composable, appears when `d.recent` is non-empty.

| Section | Rule | Source |
|---|---|---|
| Leaders | one row, category "points": headshot, name, value | `pv.*.leadingScorers[0]` |
| Goalies (**B1**) | **1.** When the side's preview lineup lists goalies (`pv.home_team.lineup.goalies` / `pv.visiting_team.lineup.goalies`), use them, in feed order but the `start == "1"` goalie first, marked "Starting" only then. Join each to `tg` by `player_id` for record/GAA/SV%; not in `tg` → name only. **2.** Else `tg` rows with `active == "1"` and `latest_team_id == team_id`, ordered by `games_played` as an **int**, descending. At most 2 per side. | `pv`, `tg` |
| Last 5 | `RecentGame` per side, oldest first: W/L by `winner == team_id`; `atOpponent` = this team is the visitor; score winner first | `pv.*.lastGames[]` |

- No section is shown when its source is empty. A failed `tg` read leaves the names without stats.
- A goalie line reads "W-L-OTL-SOL · GAA · SV%". The record is
  `wins`-`losses`-`ot_losses`-`shootout_losses`, where `losses` is regulation only. GAA is `goals_against_average`
  and SV% is `save_percentage`, both strings in the feed.

Worked examples:
- **OHL 29246 LDN** (lineup, no starter marked): Cooke "8-4-0-0 · 3.60 GAA · .879 SV%", then Jaswal "1-1-0-1 · 2.89
  GAA · .893 SV%". Rule 2 gives the same pair: Medvedev and Nadalin are excluded, and Weiss has 1 GP.
- **OHL 29246 NIAG:** Haffner (Starting), then Yermolenko, names only (NIAG's `tg` was not captured).
- **QMJHL 32953 NFL:** Denault (Starting) "6-6-1-1 · 2.84 GAA · .907 SV%", then Proulx "8-2-1-1 · 3.11 GAA · .895
  SV%".
- **QMJHL 32953 Chi:** Lee (Starting), then Plumins. Lee is the backup by games played.
- **WHL 1023348 MH** (lineup null → rule 2): Casey "12-5-0-1 · 2.77 GAA · .898 SV%", then Christie "2-1-1-1 · 3.65
  GAA · .872 SV%".
- **LDN Last 5:** L 6–5 at SAG (11-14), W 4–3 vs SOO, L 4–2 at GUE, L 5–0 vs PBO, W 2–0 at ER (11-25). There is no
  OT/SO mark: `lastGames` has no OT flag (§10).

### 3.2 Goals (final; `GoalsSheet.kt` goal rows, `ScoringRow.Event` with `clip = null`)

| Field | Source (`gs.goals[]`, in feed order, which is game order) |
|---|---|
| period, clock | `period_id` → `PeriodLabel` by `HockeyTechMapper`'s period rule (4 = OT …); `time` |
| side | `home == "1"` → HOME |
| scorer, season goal number | `goal_scorer.first_name/last_name`, `scorer_goal_num` |
| assists | `assist1_player`, `assist2_player`. An unassisted slot is an **object with all fields null** (QMJHL 32952 Arsenault, Dufour), so it is kept only when `last_name` is non-blank |
| strength | `power_play == "1"` → PP; `short_handed == "1"` → SH; else EV. `empty_net` → `emptyNet` |
| running score | computed in order |
| headshot | the headshot builder (§4.4) with `goal_scorer.player_id` |

- The catch-up rows follow. There are no video rows and no shootout rows in v1 (no capture).
- **Example, OHL 29239:** 8 goals. The 5th is Mogull (8) from Matheson only. The 7th is Dietsch (2) from Houben, the
  game winner.

### 3.3 Box score (final; `BoxScoreSheet.kt`)

| Column | Skaters: `gs.{visitor,home}_team_lineup.players[]` | Goalies (**B2**): `gs.*_team_lineup.goalies[]` with `seconds > 0` |
|---|---|---|
| name · pos | `first_name[0]. last_name` · `position_str`; "D", "LD", "RD" → the defence group | name |
| G / A / P | `goals`, `assists` (ints), P = G + A (lineup `points` is null) | – |
| +/- | `plusminus`: int (-1, 0) or string ("+1") | – |
| SOG | `shots` (a string) | – |
| TOI | **hidden when every skater's `toi` is null** (data rule; the NHL always has it) | `time` ("58:21") |
| SA / SV / SV% | – | `shots_against`, `saves`, `saves / shots_against` (SA 0 → "–") |

**Line score** (data rule):
- `BoxScore.periods` and `BoxScore.periodShots` are non-empty, so the sheet draws a hockey line score. There are two
  rows per team, Goals and Shots, with columns 1st, 2nd, 3rd, OT…, T and the labels from `periodWord`, never "Q%d".
- The NFL's `LineScore` (board quarters) is unchanged.

**Worked examples:**
- **OHL 29239:**
  - goals OTT 1 1 3 = 5, BAR 2 1 0 = 3;
  - shots OTT 15 17 6 = 38, BAR 11 5 5 = 21;
  - goalies: Nelson 60:00, SA 21, SV 18, .857; **Nikonovich on one line**, 58:21, SA 38, SV 33, .868. LaGambina and
    Bach did not play and are not shown.
- **QMJHL 32952:** an OT column. Goals Chi 0 3 1 1, NFL 1 1 2 0. Shots Chi 5 11 9 3, NFL 17 4 5 1.
- Error text: `HubLeagueUi.boxScoreError`, if set (CHL: `sports_hub_error_box_score_chl`), else today's rule.

---

## 4. Architecture

### 4.1 Flow

```
SportsViewModel ─show(game)─▶ GameDetailRepositoryImpl (loop, gate, LRU, dwell, back-off)
                                 │ CHL: BoardOnly when the effective phase is LIVE / INTERMISSION / POSTPONED
                                 │ DetailFetcher.of(nhl, espn, chl)
                                 ▼
                         ChlDetailSource (:data) ── HockeyTechHttp (call/read moved from HockeyTechScoreSource)
                                 │ JsonElement (cap 512 KB; JSONP "( … )" stripped in :data; Parameters never read)
                                 ▼
                         ChlDetailMapper (:core, pure) ─▶ GameDetail (+ BoxScore at the final), ChlRanks
```

**Pre-game answer.** Two steps:
1. `gc preview`.
2. Then, in parallel with at most 3 in flight:
   - `topgoalies` × 2, using `pv.*.team_id` and `pv.current_season.id`;
   - the two league tables, only when the per-(league, season) cached `ChlRanks` is older than
     `HUB_CHL_TABLES_TTL_MS` (30 min), and never when `pv.current_season.playoff == "1"`.

The board's `ChlGameFacts` carry no team ids (`TeamLine` has only the code), so the preview goes first. The cost is
one extra round trip, and `ChlDetailSource` stays independent of `HockeyTechScoreSource`.

Rules:
- A failed preview fails the answer.
- A failed `topgoalies` or table read gives a **partial** answer: `DetailAnswer.partial = true`, `landingFailed =
  false`.
- The repository publishes it `Ready` with `failing = false`, so the rows show without ranks or the names without
  stats.
- It re-reads at `min(normal next, receivedMono + HUB_CHL_PARTIAL_RETRY_MS)` (120 s).
- A table failure reuses the last good `ChlRanks` of that (league, season), however old.

**Final answer.** `gc gamesummary` only. `DetailAnswer.box` carries the `BoxScore` of the same parse. `boxScore(id)`
for non-NHL leagues already goes through the loop.

**Errors** (`HockeyTechScoreSource`'s mapping):

| Answer | Result |
|---|---|
| "Invalid key." / "Client access denied." | REFUSED (300 s floor; Retry-After honoured up to 3600 s) |
| 404 `{"error":"No such game"}` | `Failed(HTTP_OTHER)` |
| non-JSON, over the cap, unmappable | BAD_BODY |
| 429 | as today |

**Logging:** `"chl-detail" → state` only, with exception class names. Never a URL, header or body. The keys are
registered by `HockeyTechKeys.init`; `ChlDetailSource`'s companion touches it. Detail requests are not CHL meter
samples (the follow's wire meter is untouched).

### 4.2 `DetailCadence.CHL` and the request budget

```kotlin
val CHL = DetailCadence(preMs = HUB_NFL_PRE_MS /*30 min*/, liveMs = DAY_POLL_LIVE_MS /*unused in v1: live is BoardOnly*/,
    liveAligned = false, finalWaitMs = HUB_CHL_STARS_WAIT_MS /*1 h*/, playsWaitMs = null,
    gameLengthMs = 9_000_000 /*2 h 30*/, phaseChangeFloorMs = HUB_NFL_REFETCH_FLOOR_MS)
```

- `FinalFacts.recapPosted` for the CHL means `d.stars.isNotEmpty()`.
- POSTPONED is never fetched: the repository's BoardOnly rule comes before `nextAt`, so the policy's `lastOk == null`
  first fetch does not apply.

| Shown game | Requests | Wire (gzip) |
|---|---|---|
| Pre-game, first show | 1 preview + 2 topgoalies + 2 tables (shared per league, 30 min) = **5** | ≈ 12 KB |
| Pre-game, every 30 min | 3 (+2 when the tables are due) | ≈ 7.5 KB |
| Pre-game, partial | + the failed reads after 120 s | small |
| Live, postponed | **0** | 0 |
| Final, first show | 1 gamesummary (box included) | ≈ 6 KB |
| Final, stars missing | +1 every 5 min, ≤ 12 | ≤ 72 KB |
| Browse 12 pre-game cards | 12 × 3 + 2 = 38 | ≈ 90 KB |

- The board's 240 s `gamesbydate` cache and the Firebase follow are untouched.
- The gate, the LRU (`HUB_DETAIL_LRU`) and the dwell apply as for the NHL.

### 4.3 Mapper (`:core`, `ChlDetailMapper`, pure, lenient)

Signatures, frozen in commit 0:

```kotlin
object ChlDetailMapper {
    fun preview(pv: JsonObject, awayGoalies: JsonObject?, homeGoalies: JsonObject?, ranks: ChlRanks?,
                playoff: Boolean, headshot: (playerId: String) -> String?): GameDetail?
    fun summary(gs: JsonObject, league: League, headshot: (playerId: String) -> String?): Pair<GameDetail, BoxScore>?
    fun ranks(standard: JsonArray?, special: JsonArray?): ChlRanks?
}
/** Competition ranks by team id (`prop.team_code.teamLink`); absent = no rank. */
data class ChlRanks(val gf: Map<String, Int>, val ga: Map<String, Int>, val pp: Map<String, Int>, val pk: Map<String, Int>)
```

**`preview` fills:**
- `season`: `SeasonComparison` with 0..1 rates and nullable ranks;
- `series`: null in the playoffs;
- `leaders`: one "points" `LeaderPair`, with `leadersSeason = true`;
- `goalies`: §3.1;
- `recent`;
- `fetchedPhase = SCHEDULED`.

**`summary` fills:**
- `gameStats`, `goals`, `stars`, `venue`, `attendance`;
- `fetchedPhase` by **`HockeyTechMapper.classify`**, never restated (status 4 and 5 are final; status 3 is
  unverified);
- the `BoxScore`, with `periods` and `periodShots`.

**Leniency (tested, S2):**
- Int-or-string everywhere (`plusminus` int or "+1"; skater `shots` a string).
- `minutes_played` is an int (OHL) or "844:58" (QMJHL); it is not used.
- An all-null assist object counts as no assist.
- `mvps` may hold null entries.
- `penalties` is null pre-game.
- A missing field gives "–", never a crash.
- An unmappable body gives null, which the source turns into BAD_BODY.

**Score:** `GameDetail.awayScore` and `homeScore` stay null, and `ChlHubUi.detailCarriesScore = false`. The board
gives the headline.

### 4.4 Commit 0 decisions on the review's gaps (B3)

| Gap | Decision |
|---|---|
| (a) Partial answers | `DetailAnswer.partial: Boolean = false`. Partial → `Ready`, not `failing`, with the 120 s re-read of §4.1. The source caches only mapped `ChlRanks` per (league, season), never bodies (m6-chl-leagues §2.2). |
| (b) JSONP array transport | `HockeyTechHttp.element(raw): JsonElement?` (in `:data`): trims whitespace, strips one leading `(` and its matching trailing `)`, then parses. The caller requires `JsonArray` for `statviewfeed` and `JsonObject` otherwise. Cap: `MAX_BODY_BYTES`. `HockeyTechHttp.lscluster(league, params)` adds only `key` and `client_code`; each caller passes its own `feed`, `view`/`tab` and `lang` or `lang_code` (`gc` takes `lang_code`). `HockeyTechScoreSource.feed()`/`parse` are unchanged. |
| (c) Table join | `data[].prop.team_code.teamLink` = `pv.*.team_id` = the board's team id. Never `team_code` or `name`. |
| (d) Headshot URLs | **Built in `:data`, passed in.** `HockeyTechEndpoints.headshot(league, playerId): String?` builds `{logoBase}{client}/240x240/{id}.jpg` for digits-only ids, so it honours the debug override (`{origin}/leaguestat/`). `ChlDetailSource` passes `{ endpoints.headshot(league, it) }` to the mapper. Tests pass a fake builder. |
| (e) Rates | The mapper emits every rate (PP%, PK%, FO%, SV% as fraction) as 0..1. Zero denominator → null. |
| (f) Ranks (S1) | Computed in `ChlDetailMapper.ranks` from integers, cross-multiplied, with competition ranking, excluding `games_played == 0` and zero denominators. |

### 4.5 Every place that branches on league, and the change

| # | Place | Change | Pkg |
|---|---|---|---|
| 1 | `HubLeagueUi.kt` | `HubSource.HOCKEYTECH`; member `fun boxScoreError(res: Resources, game: GameSnapshot): String? = null` | C0 |
| 2 | `ChlHubUi.kt` | rewritten per §2: `detailed = CHL_HUB_ENABLED`, `source = HOCKEYTECH`, `detailCarriesScore = false`, `cardHighlights = false`, `boxScoreError` | P3 |
| 3 | `HubPanel.kt` `failureText` | + `league: League? = null` (defaulted, so `NhlHubUi`/`NflHubUi` stay untouched); `HOCKEYTECH` → `*_chl` strings | P3 |
| 4 | `HubPanel.kt` `detailRetry`, `channelLine` | none (they follow `detailed`) | – |
| 5 | `MatchupSheet.kt` | data rules of §3.1 (no league check) | P3 |
| 6 | `BoxScoreSheet.kt` | data rules of §3.3; error text via `HubLeagueUi.boxScoreError` | P3 |
| 7 | `GoalsSheet.kt` | none expected. P3 owns it (it holds `HubHeadshot`, which already shows an empty disc for a missing or failed image) | P3 |
| 8 | `SportsViewModel.kt` | none (`BoardOnly` in live maps to `BOARD_ONLY`, line 1053) | – |
| 9 | `GameDetailRepositoryImpl.kt` | `detailed()` + CHL when `CHL_HUB_ENABLED`; BoardOnly for CHL LIVE/INTERMISSION/POSTPONED; `cadence()`; `recapPosted` stars rule; partial re-read | P2 (C0: the `of` parameter, `DetailAnswer.partial`) |
| 10 | `DetailPollPolicy.kt` | `DetailCadence.CHL` | C0 |
| 11 | `HockeyTechScoreSource.kt` → `HockeyTechHttp.kt` | `call`/`read` moved verbatim (the `EspnHttp` precedent); + `lscluster`, `element` | C0 |
| 12 | `HockeyTechEndpoints.kt` | `headshot(league, playerId)` | C0 |
| 13 | `SportsModule.kt` | binds `ChlDetailSource` | C0 |

---

## 5. Contracts and strings (commit 0; append-only, defaulted)

**Contracts:**
- `HubContracts.kt`:
  - `GameDetail.attendance: Int? = null`;
  - `GameDetail.leadersSeason: Boolean = false`;
  - `Goalie.starting: Boolean = false`;
  - `BoxScore.periods: List<PeriodScore> = emptyList()`;
  - `BoxScore.periodShots: List<PeriodScore> = emptyList()`.
- `ChlDetailMapper.kt`: the signatures of §4.3 and `ChlRanks`, with bodies returning null.
- `DetailPollPolicy.kt`: `DetailCadence.CHL`.

**`SportsConstants`:**

| Name | Value |
|---|---|
| `CHL_HUB_ENABLED` | `false` in C0, `true` at integration |
| `CHL_HUB_LIVE_ENABLED` | `false` (the later live step, §7) |
| `HUB_CHL_STARS_WAIT_MS` | 3 600 000 |
| `HUB_CHL_TABLES_TTL_MS` | 1 800 000 |
| `HUB_CHL_PARTIAL_RETRY_MS` | 120 000 |
| `CHL_HITS_LEAGUES` | `setOf(League.QMJHL)` |

**`:data` (commit 0):**
- `HockeyTechHttp.kt` (moved code plus `lscluster`, `element`);
- `HockeyTechEndpoints.headshot`;
- `ChlDetailSource.kt` stub (answers `HTTP_OTHER`, no request);
- `DetailFetcher.of(nhl, espn, chl)`;
- `DetailAnswer.partial`.

**`:ui` (commit 0):** `HubSource.HOCKEYTECH`; `HubLeagueUi.boxScoreError`.

**Strings** (`strings_sports_hub.xml`). `%1$s` is the league's short name (OHL, WHL, QMJHL), taken from the existing
league names:

| Key | Text |
|---|---|
| `sports_hub_error_detail_retry_chl` | "Couldn't load game stats from the %1$s. Trying again automatically. Select Retry to try now." |
| `sports_hub_error_detail_back_chl` | "Couldn't load game stats from the %1$s. Trying again automatically. To try now, press Back, then OK, then select Retry." |
| `sports_hub_error_detail_limited_chl` | "The %1$s is limiting requests right now. Stats load again in a few minutes." |
| `sports_hub_error_detail_changed_chl` | "The %1$s changed how it sends game stats. Scores still update; stats need an app update." |
| `sports_hub_error_box_score_chl` | "Couldn't load the box score from the %1$s. Select Retry, or press Back to close." |
| `sports_hub_caption_venue_attendance` | "%1$s · Attendance %2$s" |
| `sports_hub_postponed_chl` | "The %1$s lists this game as postponed. Its new date shows here once the league sets it." |
| `sports_hub_summary_points_leaders_season` | "Points leaders" |
| `sports_hub_matchup_leaders_season` | "Leaders · this season" |
| `sports_hub_matchup_starting` | "Starting" |
| `sports_hub_box_row_goals` | "Goals" |
| `sports_hub_box_row_shots` | "Shots" |

The NHL's "(last 5)" / "last 5 games" leader labels would be wrong for season totals, hence the two `_season` keys.

**Gate:** every existing test and golden hash is equal. `HockeyTechScoreSourceTest` and the wire-byte (`headerBytes`)
tests are unchanged.

---

## 6. Work packages (strictly disjoint files after commit 0)

| Pkg | Files (owned exclusively) | Tests | Model |
|---|---|---|---|
| **C0** (lead) | `core/.../sports/hub/HubContracts.kt`, `DetailPollPolicy.kt`, `core/.../sports/SportsConstants.kt`, `core/.../sports/hub/ChlDetailMapper.kt` (stub), `data/.../sports/HockeyTechHttp.kt` (new, moved code), `HockeyTechScoreSource.kt`, `HockeyTechEndpoints.kt`, `ChlDetailSource.kt` (stub), `GameDetailRepositoryImpl.kt` (`of` parameter, `DetailAnswer.partial` only), `SportsModule.kt`, `ui/.../sports/HubLeagueUi.kt`, `ui/src/main/res/values/strings_sports_hub.xml` | all existing tests green and goldens hash-equal; `HockeyTechHttpTest` (JSONP array and object, cap, `lscluster` params, no key in `toString`) | Opus |
| **P1 core** | `ChlDetailMapper.kt` (bodies) | `core/src/test/.../ChlDetailMapperTest.kt`: every worked example in §2–§3; zero guards (synthetic `gp0`); ranks with ties (LDN 9th), feed `rank` ignored; goalie rules 1 and 2 (LDN, NIAG, NFL, Chi, MH; traded Medvedev excluded; int GP sort); lineup goalie join (Nikonovich 58:21); all-null assists; `plusminus` int/string; null `mvps`; `classify` status; playoff → no ranks or series; WHL `totalShotsOn` 0 | Sonnet under spec; Opus review |
| **P2 data** | `ChlDetailSource.kt` (body), `GameDetailRepositoryImpl.kt` (CHL branches), `tools/fakepanel/sports_chl.py`, `tools/fakepanel/test_sports_chl.py`, `data/src/test/.../ChlDetailSourceTest.kt`, `GameDetailRepositoryChlTest.kt`, `HockeyTechExportRedactionTest.kt` | MockWebServer: pre-game set of 5, ≤ 3 in flight, partial (`tg` / tables fail → Ready not failing, 120 s re-read, stale ranks reused), cap → BAD_BODY, "Invalid key." → REFUSED, 404 → `HTTP_OTHER`, `gc` URL and key absent from logs; repository: no request in LIVE, INTERMISSION or POSTPONED; stars wait ≤ 1 h; box from the same parse; `CHL_HUB_ENABLED` false → BoardOnly | Opus |
| **P3 ui** | `ChlHubUi.kt`, `HubPanel.kt`, `MatchupSheet.kt`, `BoxScoreSheet.kt`, `GoalsSheet.kt`, `ui/src/test/resources/golden/sports-hashes.txt`, `ui/src/test/.../HubLeagueUiChlTest.kt`, `ChlSheetsTest.kt`, `HubPanelFitTest.kt` | rows, summary, captions and slots per phase with the §2 values; final slot orders (catch-up / none / failed); postponed line; Matchup goalie order and Starting mark; Box TOI hidden by data, line score with OT; NHL and NFL rows unchanged; fit at Largest (QMJHL 5 rows; Box sheet with the two-row line score) | Sonnet under spec; Opus review |

- P1 builds against the C0 stub of `ChlDetailMapper` signatures. P2 builds against those signatures (its tests use the
  real mapper only after P1 merges; before that a fake `GameDetail`). P3 builds on fake `GameDetail`s.
- **Goldens (P3):** new `sports-tab/chl-hub-pre-ohl.png` (29246), `chl-hub-pre-whl.png` (1023348, no ranks for GF/GA
  in the fixture-backed fake), `chl-hub-final-ohl.png` (29239, catch-up), `chl-hub-final-nocatchup-whl.png`
  (1023337), `chl-hub-final-ot-qmjhl.png` (32952, hits), `chl-hub-postponed.png`, `chl-matchup-qmjhl.png` (32953,
  both starters), `chl-goals-final-ohl.png`, `chl-box-final-ohl.png` (Nikonovich one line).
  - With `CHL_HUB_ENABLED` false, the existing CHL hashes are equal.
  - The live CHL golden is unchanged.
  - Every NHL and NFL hash is equal.

### 6.1 Fake panel (P2, `tools/fakepanel/sports_chl.py`)

- **Built from its own games.**
  - `feed=gc&tab=preview` and `tab=gamesummary` are generated from the fake board's game state: team ids, codes,
    names and score. Goals are spread over periods with scorers from a per-team roster, and stars from those goals.
  - The fixtures serve as templates for the shape (keys, JSON types, nulls).
  - `topgoalies` and `statviewfeed` `teams` (JSONP array) come from the fake teams.
- **Fixture mode** `chl_hub=fixtures`:
  - the fake board lists the six fixture games with their real ids and teams;
  - it serves the fixture bodies unchanged, adding the missing `tg` reads (NIAG, CGY, Chi) and the missing WHL
    standard and QMJHL special tables, generated and marked as such;
  - so the emulator shows exactly the §2–§3 values.
- **Switches:** `chl_detail=invalid_key | no_such_game | slow | oversize | goalies_fail | tables_fail`, plus `gp0`,
  `postponed`, `playoff`, `traded_goalie`, `pulled_goalie`.
- **Tests:**
  - `test_sports_chl.py`: every route, every switch, the JSONP wrapper, and that no response contains a real key.
  - The shape of the generated bodies is pinned against the fixtures: keys and types.

### 6.2 Emulator checklist (1080p TV emulator, fake panel; targeted states only)

`chl_hub=fixtures` unless a switch is named.

1. **OHL 29246 panel:** the six rows exactly as the §2.1 table (LDN "3.63 (12th)", "21.8% (9th)"), "First meeting
   this season", "Points leaders: Edgar 22 · Zocco 27". Matchup: LDN Cooke then Jaswal, NIAG Haffner "Starting", LDN
   Last 5 ending "W 2–0 at ER".
2. **WHL 1023348:** "Season series: CGY leads 2–1"; MH goalies Casey then Christie (no Starting mark).
3. **QMJHL 32953 Matchup:** Lee "Starting" (Chi) and Denault "Starting" (NFL).
4. **OHL 29239 final, catch-up airing present:**
   - slots: Watch from start · Goals · Box score;
   - "Game-winning goal: Dietsch (2nd) from Houben, 3rd period 10:53.";
   - "Stars: Manza, Bowes, Dietsch";
   - "Sadlon Arena · Attendance 2,801";
   - rows 38–21, 2/4–0/2, 53.2%–46.8%, 6–10;
   - Box: Nikonovich on one line at 58:21 (SA 38, SV 33), no TOI column for skaters, line score OTT 1 1 3 / 15 17 6.
5. **WHL 1023337 final, no catch-up:** slots Goals · Box score · empty; focus on Goals.
6. **QMJHL 32952 final:** Hits 2–10; OT column (Chi 1 goal, 3 shots); OT winner line.
7. **`invalid_key`:** the REFUSED text. **`slow`:** "–" loading, then rows. **`tables_fail`:** rows without ranks,
   no error. **`goalies_fail`:** goalie names without stats.
8. **`postponed`:** the board state plus "The OHL lists this game as postponed…", empty slots, no detail request in
   the fake panel log. **`playoff`:** no ranks, no series line.
9. **Largest text:** the QMJHL final panel fits; the Box sheet with the two-row line score fits.

---

## 7. Live: a later, separately gated step (not in this milestone)

- **Today's live board rows stay:** Shots from the board, and the board's power-play countdown row. The slots stay
  Follow · Channels · empty, and there are no live detail requests.
- **The upgrade, behind `CHL_HUB_LIVE_ENABLED`:**
  - reads `gamesummary` every 60 s (its `max-age`);
  - shows SOG from the **board** (Firebase, 5 s, fresher) and PP fraction, FO%, PIM and Hits from `gs`;
  - drops the countdown row (owner Q4, effective only then);
  - adds latest goal + periods to the summary and the Goals slot;
  - costs about 350 KB/h while a live panel is open.
- **Captures needed first (lead task):**
  - one live `gamesummary` in a period, and one in an intermission;
  - one during a power play, and one with an empty net (pulled goalie);
  - a shootout final (`shootoutDetail`);
  - a playoff `preview` (series semantics, ranks);
  - `type=topscorers&team_id` for the goals and assists leaders.

## 8. Tests at a glance

- Unit:
  - P1 `ChlDetailMapperTest` (§6), built from §2–§3's worked examples;
  - P2 `ChlDetailSourceTest`, `GameDetailRepositoryChlTest`, `HockeyTechExportRedactionTest` cases;
  - C0 `HockeyTechHttpTest`;
  - P3 `HubLeagueUiChlTest`, `ChlSheetsTest`, `HubPanelFitTest`.
- Golden hash gate (`:ui:sportsHashGate`): §6.
- Fake panel: §6.1. Emulator: §6.2.
- Release gates per the plan §6 at the end.

## 9. Owner questions (defaults stand; work proceeds on them)

| # | Question | Default (lead ruling) |
|---|---|---|
| Q1 | Records as the leagues write them (W-L-OTL-SOL)? | Yes, four parts. |
| Q2 | Highlight videos? | Not in this milestone. **Only through a sanctioned feed; never by scraping or bypassing the site's bot check.** |
| Q3 | League ranks (two league-wide reads per 30 min)? | Kept. |
| Q4 | Drop the live power-play countdown row? | Only when the live upgrade lands (§7), not now. |
| Q5 | Player headshots? | Yes; an empty disc when missing. |
| Q6 | Show tonight's posted goalies and the marked starter? | Yes (§3.1). |
| Q7 | A dead first slot on a final with no catch-up? | No: Goals · Box score · empty (§2.4). |
| Q8 | Playoffs? | No ranks; no series line until a playoff capture shows the feed's series facts. |

## 10. Not verified

- The live `gamesummary` (§7).
- Shootout data.
- The playoff `preview`.
- The `lastGames` OT marker.
- Whether a lineup with no starter marked is tonight's lineup or the last game's. It is the dressed pair either way,
  so it is harmless.
- What the headshot host answers for a player without a photo.
- The tables not fetched: WHL standard, OHL/QMJHL special for the QMJHL (§1.3).
- A `topscorers` view.

---

## 11. Lead ruling and reconciliation (2026-11-27)

Review: `docs/review/2026-11-27-chl-hub-design-review.md` (approve with changes). The lead accepted B1–B4 and S1–S8
in full. What changed from the proposer draft:

| Item | Change |
|---|---|
| B1 Matchup goalies | Tonight's lineup goalies first (visitor from `pv.visiting_team.lineup`), "Starting" only when `start == "1"`; else active goalies still on the team, games played as an int (§3.1). Q6 added. |
| B2 Box goalies | From the lineup lines with `seconds > 0`, one line per goalie with stints added (§3.3). The draft's opposite note is gone. |
| B3 Commit 0 gaps | Decided in §4.4: a partial answer that keeps what loaded (Ready, not failing, 120 s re-read, mapped ranks cached and reused); JSONP-array transport in `:data` under the existing cap; join by `teamLink`; headshot URLs built in `:data` and passed into the pure mapper as a builder; rates 0..1; integer ranks. |
| B4 Worked examples | Every example re-derived from the fixtures by hand (§2, §3, §6.2). "CGY leads 2–1"; "Dietsch (2nd) from Houben"; per-game leaders lines. |
| S1 Zero denominators, ranks | §2.1 guards and rank rule; the feed's `rank` is never used. |
| S2 Real shapes | §3.2, §3.3, §4.3 name them, and P1's tests cover them. |
| S3 Live | Live keeps today's board rows and makes no detail request. The upgrade is §7, behind `CHL_HUB_LIVE_ENABLED`, with the capture list. |
| S4 Record fallback | Dropped; "–". |
| S5 State rules | `HockeyTechMapper.classify` is called, not restated. Postponed: no request, the board state plus a reason line. Playoffs: no ranks, no series line (§2.5). |
| S6 Sheets | Data-driven: TOI hidden when all null, the hockey line score from `BoxScore.periods`/`periodShots`, the leader header from `leadersSeason`, the Starting mark from `Goalie.starting`, the box error via a defaulted `HubLeagueUi` member, `failureText`'s `league` defaulted. |
| S7 File sets | `GoalsSheet.kt` (`HubHeadshot`) is owned by P3. The test files are named per package. |
| S8 Fake panel, checklist | The fake panel generates the bodies from its own games, plus a fixture mode. The new switches are `gp0`, `postponed`, `playoff`, `traded_goalie`, `pulled_goalie`, `goalies_fail`. The checklist uses exact values. |
| Final slots | Watch from start only with a catch-up airing; otherwise Goals · Box score · empty. |
| `CHL_HUB_ENABLED` | A build-time kill switch, `false` in C0, `true` (default) at integration. |
| Highlights | "Sanctioned feed only" (Q2). |
| Found while reconciling | The NHL's leader labels say "last 5 games", which is wrong for CHL season totals: new `_season` strings. `HubHeadshot` shows an empty disc, not initials, for a missing image (the draft said initials). Review N5 assumed the board gives the team ids, but `ChlGameFacts` carries none (only `TeamLine.abbrev`). So the preview goes first, then the other four reads (≤ 3 in flight), and the score source stays untouched. |
| Not taken up | The review's N1 extras (current streak, goalie decisions, shootout counts, season splits, rookie, most PIM) stay out of v1: no NHL-parity slot shows them. |

---

## 12. Commit 0 rulings (2026-11-27)

Commit 0 landed the contracts of §4.4–§5 with `CHL_HUB_ENABLED = false`: every CHL game is still `BoardOnly`, no CHL
detail request is made, and the NHL, NFL and CHL panels, score bug and board are unchanged. Where the body left a
contract detail open, commit 0 decided it as below. These rulings are frozen for P1–P3; changes go through the lead.

| # | Item | Ruling |
|---|---|---|
| R1 | Mapper signatures | `preview` and `summary` take the **`GameId` first**: `preview(id, pv, awayGoalies, homeGoalies, ranks, playoff, headshot)`, `summary(id, gs, headshot)` (the league is `id.league`). §4.3's draft had no id for `preview`, and the body has none at its top level. `ranks(standard, special)` is as drafted. Each mapper input is the **whole decoded body** (`{"GC":…}`, `{"SiteKit":…}`); the tables are the decoded arrays with the JSONP stripped. |
| R2 | Partial state | Besides `DetailAnswer.partial` (`:data`), `DetailState.Ready` gains `partial: Boolean = false`. P2 publishes `Ready(failing = false, partial = true)`; P3 shows no error for it (rows without ranks, names without stats). |
| R3 | Game winner | `GoalItem.gameWinning: Boolean = false` (appended). The CHL summary uses the first goal so marked, else `HubSummary.winningGoal` (§2.2). The NHL leaves it false and keeps its rule. |
| R4 | Field formats | Stated in `ChlDetailMapper`'s KDoc and pinned by `ChlHubFixtures`: names "K. Dietsch" (NHL.com's form), `*Last` = `last_name`; team codes upper-cased as the board's `TeamLine.abbrev` (QMJHL "Chi" → "CHI"); every rate 0..1, a zero denominator null; `Ranked.value` the raw double (rounding is the UI's); `Last10(record, null, null)`; a first meeting `SeriesFacts(0, 0, 0)`; `Star.rank` = `mvps` index + 1; `RecentGame.score` winner first, away side's games first. Detail scores and `serverDateMs` stay null in the mapper. |
| R5 | Goalie order with a lineup (B1 rule 1) | Starter first; then by the goalie's `topgoalies` `games_played` (int) descending; goalies without a `topgoalies` row after, in feed order (a stable sort). This is what gives §3.1's "LDN Cooke, then Jaswal" (the feed lists Jaswal first). |
| R6 | `phaseHint` | For a CHL game the repository passes the **effective** phase to `ChlDetailSource.detailAnswer` (P2, CHL branch only; the NHL and NFL keep the board phase). SCHEDULED / PRE → the pre-game reads; FINAL → `gamesummary`; anything else → `Failed(HTTP_OTHER)` without a request. |
| R7 | Transport | `HockeyTechHttp` (`:data`, internal) holds the moved `call`/`read`/`Raw` and the companion's `statusKind`, `clampRetryAfter`, `headerBytes` (the score source keeps delegating members, so its tests are unchanged). Added: `lscluster(endpoints, league) { params }` (the caller's parameters in order, then `key`, `client_code`); `element(raw)` (one `( … )` stripped; only an object or an array is a body); `json(url, cap = MAX_BODY_BYTES): JsonAnswer` with `HockeyTechScoreSource.feed`'s error mapping (401/403 and the "Invalid key" / "Client access denied" bodies REFUSED, 404 HTTP_OTHER, cap / non-JSON BAD_BODY, 429, 5xx). `JsonAnswer` prints no URL or key. The score source's `feed()` and `parse` are unchanged. |
| R8 | `ChlDetailSource` | `@Singleton`, constructor-injected like `EspnDetailSource` (no `SportsModule` binding is needed; `GameDetailSource` stays bound to the NHL's). Internal constructor `(client, mono, endpoints, mapPreview, mapSummary, mapRanks, callTimeoutMs)`, the mappers defaulting to `ChlDetailMapper`'s, so P2 tests with stand-ins before P1 lands. Entry points `detailAnswer(id, phaseHint)`, `boxScore(id)` (never a request: the loop answers from the final's parse), `trimMemory()` (wired into the repository's `onTrim`). The stub answers `Failed(HTTP_OTHER)`. |
| R9 | Routing | `DetailFetcher.of(nhl, espn, chl)` routes OHL / WHL / QMJHL to `chl` (an exhaustive `when`). With the switch off, `detailed()` is unchanged, so the loop never calls it. |
| R10 | Headshots | `HockeyTechEndpoints.headshot(league, playerId)`: 1–12 digits only, else null; under `logoBase`, so the debug override serves it from `{origin}/leaguestat/{client}/240x240/{id}.jpg` (P2's fake panel route). |
| R11 | Fakes | `core/src/testFixtures/.../hub/ChlHubFixtures.kt`: fixture paths, `text` / `obj` / `table` loaders, the fake headshot builder `HEADSHOT` (`https://headshots.test/…`), and the expected `GameDetail` / `BoxScore` of all six fixture games (`preOhl29246`, `preWhl1023348`, `preQmjhl32953`, `finalOhl29239` + `boxOhl29239`, `finalWhl1023337` + `boxWhl1023337`, `finalOtQmjhl32952` + `boxOtQmjhl32952`). They restate §2–§3 under R4–R5 (the box skaters are the fixtures' trimmed 5 per side). The lead owns the file: P1 compares against it and reports a disagreement; the worked examples decide. |
| R12 | Strings | The 12 keys of §5, English only, in `strings_sports_hub.xml`; unused until P3. |
| R13 | `HubSource.HOCKEYTECH` | Added; `ChlHubUi` keeps `NONE` until P3 switches it with `CHL_HUB_ENABLED`. No existing `when` over `HubSource` is exhaustive, so nothing else changed. |

Tests added in commit 0: `data/.../HockeyTechHttpTest.kt` (JSONP array and object on the fixtures, one wrapper only,
the cap, `lscluster` parameters, the error mapping, nothing printed carries a key or the URL, headshot URLs) and
`core/.../hub/ChlHubContractsTest.kt` (the cadence, the switches off, the appended defaults, the stub, the fakes' key
worked-example values).

---

## 13. Integration record (2026-11-27)

Branch `chl-hub-int` (commit 0 + P1 + P2 + P3, merged cleanly at a06aeee7). No adb, no emulator, no request to a real
host: the fixtures over MockWebServer and the local fake panel only.

**Turned on.** `SportsConstants.CHL_HUB_ENABLED = true` (still a build-time kill switch). Tests that assumed false now
state the position they test: `ChlHubContractsTest` asserts on; `GameDetailRepositoryChlTest` tests the off position
with `detailed(league, chlHub = false)` and the harness's `chlHub = false`, plus a new test that the build default is on;
`SportsViewModelHubTest`'s CHL-final slot test is split into an explicit "hub switched off" case (`ChlHubUi.enabled =
false`) and a "hub on" case (Goals · Box score · empty; ▶ From start first with a catch-up airing).

**Wiring checked in code.**
- DI: `GameDetailRepositoryImpl`'s `@Inject` constructor takes `ChlDetailSource`, whose `@Inject` constructor passes no
  mappers, so the defaults are `ChlDetailMapper::preview / ::summary / ::ranks`; the headshot builder is
  `{ endpoints.headshot(league, it) }` (`HockeyTechEndpoints`, so the debug override serves `{origin}/leaguestat/…`).
  `DetailFetcher.of` routes OHL / WHL / QMJHL to it; `onTrim` reaches `chl.trimMemory()`.
- Routing: `detailed()` is true for the CHL with the switch on; LIVE / INTERMISSION / POSTPONED are BoardOnly before the
  policy; the hint is the effective phase (R6).
- View model: `buildPanel` maps `Ready` (partial or not) to `DetailKind.READY` with `failing` as published (a partial is
  `failing = false`, so no error text), `Failed(kind)` to FAILED with the kind, `BoardOnly` to BOARD_ONLY. Followed for
  OHL 29246 (preview → `pre()` → mapper → `Ready(partial)` → `ChlHubUi.rows/summary`) and OHL 29239 (gamesummary →
  `final()` → `Ready` + box slot → final rows, GWG/stars, Box score sheet from the same parse).

**New tests.**
- `data/.../ChlHubEndToEndTest` (5): the six fixture bodies over HTTP → real `ChlDetailSource` (default mappers, real
  headshot builder) → real repository loop → `DetailState`, equal to the `ChlHubFixtures` values (headshots on the
  endpoints' host); the three finals' box scores from the one `gamesummary`; tables failing → partial `Ready` without
  ranks, not failing, filled by the 120 s re-read; a postponed-looking game (below).
- `ui/.../ChlHubRealMapperTest` (3): the OHL pre-game and final panels and the Matchup, Goals and Box score sheets drawn
  from the real mapper's output at Default and Largest: nothing cut or outside the panel / sheet; every final (three
  leagues) draws the stars on line 2 with the winning goal on one line. Review PNGs: `ui/build/chl-hub/real-*.png`.
- `ui/.../ChlHubUiSwitchTest` (2): the switch reads the constant; no production source writes it.

**Seams resolved.**
- (a) Tables without `fmt=json`: transport, parser and fake panel agree. The source sends the captured request (no
  `fmt`); `HockeyTechHttp.element` strips the one `( … )`; the fake panel answers `([ … ])` as
  `application/javascript` whatever the `fmt`, as the captures. The end-to-end test asserts no `fmt` on `statviewfeed`.
  Whether the real host answers plain JSON with `fmt=json` is not needed and not checked.
- (b) Postponed: `HockeyTechMapper.classify` maps status 1 (the only postponed marking seen, `game_status
  "Postponed"`) to SCHEDULED, so the board never says POSTPONED for the CHL and the reason line cannot show. No status
  mapping was invented. Such a game reads as a scheduled one: Ready, no crash, no box request, the 30 min pre-game
  cadence (end-to-end test, also with the fake panel's `hub_postponed` bodies). Found on the way: a partial answer
  re-read the whole set every 120 s **for as long as the secondary read kept failing** (the fixtures' NIAG goalies, a
  table view down). Fixed: `SportsConstants.HUB_CHL_PARTIAL_RETRIES = 3` fast re-reads per game, then the phase's own
  cadence (`GameDetailRepositoryImpl.partialTries`; test `partialAnswersInARowReReadFastAtMostThreeTimes…`). **Lead to
  ratify** (it bounds §4.1's 120 s rule). **Open:** a captured postponed CHL game; until then `sports_hub_postponed_chl`
  and the postponed golden are reachable only from a POSTPONED board phase that the CHL mapper never produces.
- (c) `ChlHubUi.enabled` is no longer a copy: its getter is `testOverride ?: SportsConstants.CHL_HUB_ENABLED`, the setter
  is `@VisibleForTesting`, and `ChlHubUiSwitchTest` scans `ui`, `app` and `data` main sources for any write. Data
  (`GameDetailRepositoryImpl.detailed`) and UI read the one constant.
- (d) Three stars at the final: the owner asked for them, and a wrapped winning-goal sentence dropped line 2 (OHL and
  QMJHL). The CHL final's winning-goal line is now always one line, the first that fits 664 px as drawn (label 600 +
  text 400 at 24 px × the text size): "Game-winning goal · Dietsch (2nd) from Houben, 3rd period 10:53." → "GWG ·
  Dietsch (2) from Houben, 3rd 10:53" → "GWG · Dietsch (2), 3rd 10:53"; a shortened line is spoken as the full
  sentence. The stars line is `oneLine`. New strings `sports_hub_summary_winning_goal_short`, `…_assisted`, `…_solo`.
  The NHL's summary is untouched.
- (e) P1 choices against the fixtures: every `save_percentage` in the captures is a 0..1 fraction (".879", "1.000" maps
  to 1.0), so the "> 1 → ÷ 100" branch is a guard no capture reaches; no `lastGames` entry has equal goal counts or a
  missing `winner`, so T is a never-seen fallback (W/L come from `winner` first); no captured goal row is dropped (all
  have `period_id` and a scorer), and the running scores end at the finals (5–3, 4–3, 5–4 OT). Counting a dropped row
  keeps the later rows' scores true to the board. All three are kept.

**Fake panel.** Started locally (`--sports --sports-chl`, `sports_chl_hub: true`), each new route curled once for all
three leagues (4 previews, 8 `topgoalies`, the evicted final's and the postponed game's `gamesummary`, both tables, one
headshot: 200 `image/jpeg`; an unknown id 404). All 48 bodies went through the real mapper in a throwaway JVM test (not
kept): every preview SCHEDULED with records, ranks, leaders and goalies (starter marked where the lineup says); the
finals FINAL with goals, one GWG, three stars, venue, attendance and box (QMJHL with Hits); the postponed
`gamesummary` SCHEDULED. Not built by P2 and still open: the `chl_hub=fixtures` mode and the `pulled_goalie` switch
(§6.1). A fake-panel leader id (9884, copied from a fixture) has no headshot there (empty disc, as designed).

**Goldens.** Three hashes re-recorded, each PNG looked at: `sports-tab/chl-hub-final-ohl.png` ("GWG · Dietsch (2) from
Houben, 3rd 10:53" / "Stars: Manza, Bowes, Dietsch"), `chl-hub-final-nocatchup-whl.png`, `chl-hub-final-ot-qmjhl.png`.
Every NHL, NFL, Stacked and other CHL hash is unchanged.

**Gate (2026-11-27, this host).** `lintAll` clean (no new issues). Unit tests, 0 failures: `:core` 5023 (2 skipped),
`:data` 1033 (5 skipped, incl. the real-host `HockeyTechWireProbe`), `:player` 662, `:ui` 1266, `:app` 115.
`:ui:sportsHashGate` 1730 PNGs: the three finals above differed as intended and were re-recorded, then the gate passed.
`:app:assembleDebug` built. Fake panel Python: `test_sports*.py` 187, `test_fakepanel.py` 151, `test_events.py` 9, all OK.

**Still open.**
- A captured postponed CHL game (status / `game_status`), and so the reason line (b).
- The `HUB_CHL_PARTIAL_RETRIES` bound needs the lead's ruling (b).
- The emulator checklist §6.2 (not run here: no device); the fake panel's `chl_hub=fixtures` mode and `pulled_goalie`
  switch that §6.2 assumes were not built by P2.
- Everything in §10 (live `gamesummary`, shootouts, playoff preview, `lastGames` OT marker, headshot host's answer for a
  missing photo, the uncaptured tables).

---

## 14. Fix pass (2026-11-27)

After the emulator run on the real feed (lead) and the two code reviews (no blockers). No adb, no emulator, no IPTV
provider; a handful of GETs to the public HockeyTech hosts only (below). Nothing sports-related is persisted.

**A1 Headshots.** The cause was not found in code. `ChlLogoCheck` was never on the headshot path: `HubHeadshot` is an
`AsyncImage` on the shell loader with no CHL check. The URL the app builds is right: `https://assets.leaguestat.com/ohl/240x240/9330.jpg`
answered 200 `image/jpeg`, a baseline 240 × 240 JPEG, also with the app's own User-Agent. The same composable with a
fake fetcher loads it in Robolectric (`ChlHeadshotTest`). So on the real feed the empty discs come from the device
fetch or decode, and the reason is **unverified**. Changes made:
- `ChlLogoCheck.isChlHeadshotUrl` accepts exactly `https://assets.leaguestat.com/{ohl|whl|lhjmq}/240x240/{1–12 digits}.jpg`,
  and the fake panel's `{override}/leaguestat/{client}/240x240/{id}.jpg` on the override's own origin. `headshotAllowed`
  refuses every other URL on the CHL host from a headshot slot. NHL and ESPN headshots are untouched.
- `HubHeadshot` logs one redacted line per failure kind ("headshot failed: assets.leaguestat.com HTTP 403" or the
  exception class; never the path or id). The next emulator run's logcat (`HubHeadshot`) names the cause.
- A focused CHL row (Goals, Matchup leaders and goalies) draws the empty disc in `HEADSHOT_ON_LIGHT` (Canvas at 14 %),
  so it stays visible on the light fill. NHL and NFL rows are unchanged.
- The fake panel's headshot route served a 4-byte marker that decodes to nothing, so every fake-panel headshot was an
  empty disc. It now serves a real 16 × 16 JPEG (`HEADSHOT_JPEG`).

**A2 OHL logos shown as codes.** The fixtures cannot tell. In the recorded `teamsbyseason` (2026-11-13), every logo
URL of NB, BRAM, OSH, SOO, OS and NIAG has the shape `CHL_LOGO` accepts, after the mapper's JPEG swap (`ChlLogoTest.theOhlTeamsSeenAsCodesHaveLogoUrlsTheCheckAccepts`).
BRAM's `18.png` was a recorded 404, so its code is the designed fallback. Live today, `ohl/logos/19.png` (NB) and `4.png`
(OSH) answered 200 with 500 × 500 RGBA PNGs and transparent corners, so the no-alpha check passes them too. The check
is not the cause and was not changed. The cause is unverified; it may be the same device fetch failure as A1.

**B1** `ChlHubUi.slotSetKey` = `final && detailRetry`: the frozen final set re-freezes when the retry state flips (test
`SportsViewModelHubTest.aChlFinalsFrozenSetReFreezesWhenItsRetrySucceeds`).
**B2** The hockey line score is laid out like the table below it: full width, 16 px sides, 20 px gaps, value columns of
56 right-aligned. Its T column now ends on the table's right edge. The header words are sans 22/600 Fg70; the values
stay mono. Only the CHL box-score goldens were re-recorded.
**B3** A grey Matchup or Goals slot speaks a reason that names the league: loading, failed or none
(`sports_hub_reason_{matchup,goals}_{loading,failed,none}_chl`).
**B4** `ChlHubUi.leaderSurname`: "J. Van Der Berg" gives "Van Der Berg"; "A. Smith Jr." gives "Smith" (Jr, Sr and
II–V are dropped).
**B5** The NHL and NFL Largest panels (`hub-panel-{live,final,pre}-largest`, `nfl-hub-panel-{pre,live,final-highlight}-largest`)
were rendered with the widening on and with it forced off. The PNGs are byte-identical: no NHL or NFL fixture value is
wider than a quarter of the row, so the widening never triggers for them. Nothing is worse.
**C1** A partial pre-game answer keeps what the earlier good read of the same game had and this read lacks: a goalie's
record, GAA and SV% (matched by name on the same side), or a whole side's goalie list
(`GameDetailRepositoryImpl.keepPartial`).
**C2** Shootouts, defensive; no sample has been captured. A period whose `short_name` is "SO" maps to `PeriodKind.SO`.
It gets no shots column and no goal row. If the feed flags a shootout (`meta.shootout`, the body's `shootout` field,
or an SO period) but gives no SO column, and `totalGoals` is exactly one goal ahead of the period sums for one side, an
SO column of 1–0 is added. The line's total then agrees with the headline. Otherwise nothing is invented. The classifier
gets `shootout`, so the final kind is SO. Assumed: the "SO" `short_name`, and that `totalGoals` may count the winning
shootout goal.
**C3** `meta.final` goes to `HockeyTechMapper.classify` as `final`, so a status-3 body that the feed flags final is
FINAL.
**C4** A 429 or refused secondary read (goalies or tables) is named in `DetailAnswer.secondaryRefused`. The repository
then takes the back-off path: the refusal floor, Retry-After, and Retry ignored. The 120 s partial re-reads do not run.
The answer stays Ready, partial and not failing. A 5xx or a timeout on a secondary read keeps the 120 s re-read.

**D Live capture.** OHL games were live on 2026-11-27 (19:00 ET slate). Five GETs went to the public feed (two boards, three `gamesummary`): `gc-gamesummary-live-ohl-29246.json` (1st period, 0–0), `gc-gamesummary-int-ohl-29241.json` (1st intermission: still status 2 with the clock at 0:00, no intermission flag, `goals` null) and `gc-gamesummary-live-goals-ohl-29248.json` (three goals). The bodies are trimmed and key-free; the hub README lists them. No game was in a shootout.
`ChlDetailMapperTest` maps each live body without a crash, as LIVE (or as the live phase the feed names). The live
screen upgrade (§7) is not built.

**Requests made.** Four GETs to `assets.leaguestat.com`: two headshots for the A1 check and two OHL logos for A2. Five
GETs to the keyed `lscluster` feed for D. All used a generic User-Agent, and no key or keyed URL was written to any
file.

**Goldens.** Three CHL hashes were re-recorded, and each PNG was looked at: `chl-box-final-ohl`, `chl-goals-final-ohl`
and `chl-matchup-qmjhl`. No NHL, NFL, Stacked or other CHL hash changed.

**Gate (2026-11-27, this host).** `lintAll` found no new issues. Unit tests had 0 failures: `:core` 5026 (2 skipped),
`:data` 1036 (5 skipped), `:player` 662, `:ui` 1273 and `:app` 115. `:ui:sportsHashGate` passed. `:app:assembleDebug`
built. In the first full run, the new `ChlHeadshotTest` load test timed out at 5 s. It now waits up to 30 s on a
thread-safe log, and it passed alone and in the full `:ui` rerun with the gate. The fake panel Python suites passed:
`test_sports*` 190, `test_fakepanel` 151, `test_events` 9, and the hub and golden suites 12.

**Still open.**
- A1 and A2 on a device: check logcat for `HubHeadshot` on the next emulator run.
- A shootout body.
- Whether `totalGoals` counts the shootout winner's goal.
