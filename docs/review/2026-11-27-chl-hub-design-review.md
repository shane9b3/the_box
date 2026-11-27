# CHL hub design review (adversarial), 2026-11-27

Reviewed: `docs/design/m6-chl-hub.md` (proposer draft, uncommitted) and its fixtures in
`core/src/testFixtures/resources/sports/chl/hub/`. Judged from the fixtures and the code only: no network, no device.
Code read: `HubLeagueUi.kt`, `ChlHubUi.kt`, `NhlHubUi.kt`, `MatchupSheet.kt`, `BoxScoreSheet.kt`, `HubContracts.kt`,
`DetailPollPolicy.kt`, `GameDetailRepositoryImpl.kt`, `HockeyTechScoreSource.kt`, `HockeyTechEndpoints.kt`,
`HockeyTechMapper.kt`, `tools/fakepanel/sports_chl.py`.

**Verdict: approve with changes.** The source choice (`gc` preview / gamesummary plus `topgoalies` and the two
`statviewfeed` tables) is right, and it is the smallest design that gets NHL parity. The panel and sheet shapes are
right too. But the fixtures show two mappings that put wrong content on screen in the proposal's own test games (B1,
B2). Commit 0 also leaves contract gaps that the parallel packages would each fill differently (B3). Two worked
examples in the spec are wrong (B4). Fix the B items in the proposal before commit 0.

## Hand checks against the fixtures (what is right)

| Item | Fixture value | Proposal mapping gives |
|---|---|---|
| Record W-L-OTL-SOL | NIAG 10-10-3-0 (gp 23), LDN 13-8-1-2 (24), CGY 13-9-1-1, MH 14-6-1-2, Chi 12-9-0-1, NFL 14-8-2-2: each sums to `gp` | right |
| Last 10 | NIAG `last10` "3-5-2-0" = `past_10_*` fields | right (given directly) |
| GF/GP, GA/GP (rank) | LDN 90/24 = 3.75 (8th), 87/24 = 3.62 (12th); NIAG 3.09 (15th), 4.04 (18th); `pv` agrees with the `tm` row | right |
| PP%, PK% | LDN 19/87 = 21.84 % (given), 68.32 %; NIAG 17.05, 70.89 | right (given directly) |
| PP rank | LDN and SOO are both 19/87: shared 9th. The feed's `rank` column says 10th: it is the sort order, not a rank | right only if computed from raw ints (S1) |
| Goalie record | `losses` is regulation only: `total_losses` = L + OTL, `non_reg_losses` = OTL + SOL, in all 7 rows | four-part record right |
| Season series | QMJHL "Chi leads 1–0" (`w` includes `otw`); OHL 29246 first meeting | right |
| Game-winning goal | OHL Dietsch (2) 10:53 3rd, from Houben; WHL Hamilton (17) 19:25 3rd PP; QMJHL Dubé (4) 4:16 OT. `game_winning` agrees with the computed rule in all three | right (the spec's example text is wrong, B4) |
| Stars, period goals, shots by period | OHL OTT 1-1-3 / BAR 2-1-0; shots 15+17+6 = 38 and 11+5+5 = 21, equal to the goalies' SA | right |
| Team stats (final) | OHL PP 2/4 vs 0/2; FO 33/62 = 53.2 %; PIM 6–10; QMJHL hits 2–10 | right |

## Blockers

**B1. The Matchup goalies are wrong in the OHL fixture.** `mk-topgoalies-ohl-t14` lists 5 London goalies. Two are
`active "0"`, and Medvedev has `latest_team_id "6"`, `num_teams "2"`: he was traded. "Sorted by `games_played` desc"
gives Cooke and Medvedev. The right pair is Cooke (13 GP) and Jaswal (4 GP). `games_played` is also a string, so a
string sort orders "8" > "4" > "13".

Rule to write into the spec:
1. When `pv.*.lineup.goalies` is present, use tonight's dressed goalies, joined to `tg` by `player_id`. Put the
   goalie with `start == "1"` first and mark him as starting.
2. Else use `tg` filtered to `active == "1"` and `latest_team_id == team_id`, ordered by the int value of
   `games_played`.

The proposal says starters are "seen for one OHL visitor, not reliable". The fixtures show more: starters are marked
in 3 of the 4 previews (OHL 29246 visitor; QMJHL 32953 **both** sides, where Chi's starter Lee is the backup by games
played; OHL 29239 both). Only the WHL has `lineup` null. This is parity the owner will expect.

**B2. The Box score shows a goalie twice.** In OHL 29239, `gs.goalies.home` has Nikonovich twice: 57:54 (SA 38) and
00:27 (SA 0), because he was pulled and came back. Use the lineup goalies instead
(`gs.*_team_lineup.goalies`, `seconds > 0`). They hold one line per goalie with the stints added up (58:21, SA 38,
SV 33). Also fix §3.3's note, which says the opposite.

**B3. Commit 0 contract gaps; P1, P2 and P3 would each guess.**
- *(a) Partial answers.* In `GameDetailRepositoryImpl`, `landingFailed = true` sets `failing = true`. That is the
  opposite of §4.1's "not `failing`". `keepLanding` also does not keep ranks. Specify these:
  - a failed goalie or table read leaves `landingFailed` false;
  - the source keeps the last good **mapped** ranks per (league, season) and reuses them when a refresh fails;
  - per m6-chl-leagues §2.2, a body is never kept after mapping, so the cache holds no table bodies.
- *(b) `statviewfeed` is JSONP around a JSON **array*** (`([ {sections…} ])`). `HockeyTechScoreSource.parse`/`feed()`
  only accept a `JsonObject`, and `feed()` hard-codes `feed=modulekit` and `lang`, where `gc` uses `lang_code`. Put the
  wrapper strip in `:data`, before the parse. The mapper's input type for the tables must change too: the proposed
  `Pair<JsonObject?, JsonObject?>` cannot hold the array.
- *(c) Table join key.* Use `data[].prop.team_code.teamLink` (= `pv.*.team_id`; checked for LDN 14, NIAG 20, MH 206,
  CGY 202, NFL 2, Chi 10). Never join on `team_code` or `name`: the QMJHL codes are mixed case, and names read
  "Chicoutimi, Saguenéens".
- *(d) Headshots.* `HockeyTechEndpoints.headshot` lives in `:data`, but `ChlDetailMapper` is pure `:core` and fills
  `GoalItem.headshot` / `Leader.headshot`. Pass a `(playerId) -> String?` into the mapper. It must honour the debug
  override (`{origin}/leaguestat/`).
- *(e) Percent scale.* `HubPanelContents.pct` treats `v <= 1.0` as a fraction, so a feed value of 1.0 (one PP goal in
  100) prints "100.0%". The mapper should emit 0..1, as the NHL mapper does.

**B4. Two worked examples are wrong, and the packages are "Sonnet under spec".**
- Emulator item 2 says "MH leads 2–1". The fixture says **CGY leads 2–1**: H2H CGY `w 2`, MH `w 1`. The three
  meetings agree: 10-23 MH won 5-4 at CGY, 10-24 CGY won 5-2 at MH, 11-13 CGY won 3-2 at MH.
- §2.2 gives "Dietsch (5) 10:53 3rd, from Bowes, Manza". The fixture says Dietsch (2), assisted by Houben only.
- §2.2's "Points leaders: Zocco 27 · Edgar 24" mixes two games.

Replace every example with fixture-derived values; the hand-check table above has them.

## Should fix

- **S1. Zero denominators and ranks.**
  - When `gp == 0` (every team's first game, all pre-season), GF/GP and GA/GP are NaN and every team ties for "1st".
  - When `power_plays == 0` or `times_short_handed == 0`, PP% or PK% is a false 0.0 %.
  - Show "–" with no rank in each case. Leave out of the ranks any table row with `games_played == 0`.
  - Compute every rank from integers (cross-multiplied), never from the rounded "21.8%" strings or the feed's `rank`
    column.
  - A team with no `lastGames` has no Last 5 section (already in the spec), and its Last 10 shows "–" rather than
    "0-0-0-0".
- **S2. The mapping table must name the real shapes.**
  - An unassisted goal is an **object with all-null fields** (QMJHL 32952, Arsenault and Dufour), not an absent field
    or an empty one.
  - `plusminus` is an int (-1, 0) or a string ("+1").
  - Skater `shots` is a string. QMJHL `position_str` is already "D".
  - Lineup `points` is null, so compute G + A.
  - QMJHL `topgoalies.minutes_played` is "844:58"; the OHL's is an int.
  - Add each of these to `ChlDetailMapperTest`.
- **S3. The live state is unverified.** Precedent: m6-sports-hub §10.5 (a lead task to record a live game) and §7.1
  ("an unverified field ships disabled").
  - Add a lead task: record one live `gamesummary` (in a period and in a break) and, when one occurs, a shootout
    final.
  - Until then, the live phase keeps today's board rows; pre-game and final ship.
  - Live shots: the board's Firebase shots (5 s) are fresher than a 60 s `gamesummary`. Use the board's shots while
    they exist, or state the lag.
- **S4. The Record fallback does not exist.** `visiting_stats.record` is not a field (the board has
  wins/losses/ot_losses/shootout_losses parts), and `HockeyTechMapper` parses no records ("the board has no records").
  The board is also current, while `pv` is as of the game's start. Drop the fallback and show "–".
- **S5. State rules.**
  - §4.3 restates the status rule as "3/4 FINAL". `HockeyTechMapper` does not treat 3 as final: status 3 is unverified,
    and only 4 and 5 are final. Call the mapper's classifier and do not restate it.
  - POSTPONED: `nextAt` still fetches once (`lastOk == null`). Specify no fetch and the board's postponed text in the
    rows.
  - Playoffs and pre-season: `pv` and `tg` follow the current season id, so in the playoffs a team shows "4-1-0-0" with
    ranks over 16 teams. H2H semantics are unverified.
  - Default: in the playoffs (`current_season.playoff == "1"`), no ranks and no series line until a capture is made.
- **S6. Keep league checks out of shared sheets.** Rows 6 and 7 of §4.4 add CHL branches beside the `nfl` flags.
  Make them data-driven or `HubLeagueUi` members instead:
  - the TOI column is hidden when every `toi` is null;
  - the line score is drawn from `detail.periods` / `periodShots` when present. Today's `LineScore` labels regular
    periods "Q%d", so hockey needs period words.
  - `failureText`'s new `league` parameter must be defaulted, so `NhlHubUi` and `NflHubUi` (not in P3's list) stay
    untouched.
- **S7. File sets.** The P3 "headshot loader" is `HubHeadshot` in `GoalsSheet.kt`, and that file is in no package. Name
  the owner.
- **S8. Fake panel and checklist.**
  - "Ids rewritten" is not enough. The fake board's games have other teams and other scores, so the served
    preview/gamesummary must take the fake game's team ids, codes, score and goals. A live `gamesummary` must be built
    from the fake's own state.
  - Add switches for the states the fixtures lack: `gp0`, `postponed`, `playoff`, `traded_goalie`, `pulled_goalie`.
  - Give exact expected values in the emulator list:
    - LDN goalies Cooke and Jaswal;
    - Nikonovich on one line at 58:21;
    - "CGY leads 2–1";
    - the QMJHL starter mark;
    - Largest text in the Box sheet with the two-row line score.

## Notes

- **N1. The feed gives these directly; the design omits or derives them.**
  - Omitted: the current streak (`streak` "0-1-0-0" → `Last10.streakType`/`streak`), goalie decisions
    (`win`/`loss`/`ot_loss`), `homeShootout`/`visitorShootout`, `season_records` splits, `rookie`, `mostPIM`.
  - `topgoalies.photo` (60×60) confirms the headshot path.
  - Nothing needed for parity is derived when the feed gives it.
- **N2. Goals and assists leaders.** Points only is a safe default. One `type=topscorers&team_id` capture (a lead
  task) would settle the other two categories; the owner expects them.
- **N3. Commit 0 move.** Moving `call`/`read` into `HockeyTechHttp` is acceptable on the `EspnHttp` precedent.
  - Leave `feed()` and `parse` in the score source.
  - The detail requests must not become CHL meter samples.
  - Gate: `HockeyTechScoreSourceTest` plus the wire-byte (`headerBytes`) tests unchanged.
- **N4. `CHL_HUB_ENABLED`.** It is fine as the parallel-work gate. Flip it at integration and keep it as a build kill
  switch (as `ESPN_VIDEO_ENABLED`). No hidden user flag.
- **N5. Budget.** The budget fits the m6-chl-leagues rules: the gate, ≤ 3 in flight, 512 KB cap (largest body used is
  48 KB), whole-`JsonObject` decode per §2.7. The topgoalies reads need no preview: the board gives the team ids and
  `season_id`. Optional: run all five reads in parallel.
- **N6. Redaction and odds.** No key or 16-hex token in the fixtures or the proposal, and no odds or gambling fields.
  The site-level key is not used.
  - The fact-find got past Cloudflare on chl.ca with a browser UA. The app must never do that. Write that into Q2.
- **N7. Small points.**
  - "Field presence identical" is overstated: `meta` key sets differ (QMJHL has no `capacity`; WHL lineups have no
    `line`). This is harmless with a lenient mapper.
  - Points-leader ties are common (LDN Zocco/Brown 27, NIAG Edgar/Smith 22, Chi Lecompte/Dufour 29). Taking `[0]` is
    acceptable.
  - Text sizes stay ≥ 22 design px. The QMJHL's 5 rows equal the NHL's count.

## Owner questions

- Q1 (four parts), Q3 (ranks), Q4 (drop the PP countdown) and Q5 (headshots): agree.
- Q2: agree, not in this milestone. Reword it: "only through a sanctioned feed; never by scraping or bypassing the
  site's bot check".
- Missing questions:
  - **Q6.** Show tonight's posted starting goalie when the lineup marks one? Default: yes (B1).
  - **Q7.** On a final with no catch-up airing, slot 1 is empty and focus lands on Goals. Keep the fixed slot
    positions? Default: yes, per m6-sports-hub §1.5.
  - **Q8.** Playoffs: no ranks or series line until a capture is made? Default: yes (S5).
