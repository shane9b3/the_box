# NFL in the Sports Hub: stats, scoring plays and highlights from ESPN (design)

Status:
- Proposed 2026-11-25 (proposer, Opus).
- **Revised 2026-11-25** after the architecture review (`docs/review/2026-11-25-nfl-hub-design-review-arch.md`) and the UX
  review (`docs/review/2026-11-25-nfl-hub-design-review-ux.md`), with the lead's rulings and the owner's ruling on Q1.
  §13 records each finding's resolution, and §15 lists what was cut from the first version. This text is the
  implementation spec. The decision note follows at integration (§9.4).

Inputs:
- `CLAUDE.md`; `docs/design/m6-sports-hub.md` (the hub as built for the NHL: §1 layout, §4 data, §6 video, §7 NFL / CHL,
  §15 integration, §15.6 footer removed and panel 864 tall); `docs/design/m6-event-channels.md` §18 (hub changes);
  `docs/decisions/2026-11-24-sports-hub.md` (owner rulings, route A).
- The lead's ESPN probe of 2026-11-25: `FACTS.md` and the samples in the session scratchpad `nfl/` (`sum-final.json`
  MIA@BUF, `sum-sun-401873076.json` PIT@PHI, `sum-mnf-401873078.json` CIN@WSH on ESPN, `sum-pre.json` GB@LAR, `sb11.json`,
  `sbnow.json`, `clips-mnf/*.json`, `master-*.m3u8`, `hdr/`). §14 lists what FACTS got wrong or left out.
- The live capture tonight (lead): three summaries of GB@LAR, `nfl/live-sum-401873079-*.json` (§8.1).
- The code at `main` `c333d3c` (v0.1.21). Line numbers below refer to that commit.

Owner's request (2026-11-25): "start working on the NFL highlights and stats, I think espn has all that info".

Units: design px on the 1920 × 1080 canvas. Text sizes are multiplied by the Text size factor (Default 1.0, Larger 1.1,
Largest 1.2). Times are in ms unless marked s, min or h. "V" and "NV" refer to FACTS.md's verified and not-verified marks.
Widths are advance-width sums from the bundled `instrument_sans_*.ttf`, without kerning, which is the method
`HubPanelFitTest` uses.

Words on screen (new): *Scoring*, *Highlights*, *Game highlights*, *More videos*, *Recap*, *Leaders*, *Injury report*.
Never "feed", "API", "summary", "endpoint" or "clip id". ESPN is named where the NHL design names NHL.com.

---

## 0. Decisions at a glance

| Topic | Decision |
|---|---|
| Shape | The NHL panel, sheets and clip player, unchanged in geometry. The NFL fills them from **one** new request per shown game: ESPN's game summary. No new screen, no new key. |
| Panel per phase | Pre-game: 6 season rows; summary lines for season passing and rushing leaders; matchup art captioned "venue · network". Live and breaks: 5 team-stat rows; summary line 1 is the **situation** from the board ("CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3"); line 2 is the latest score; matchup art. Final: 6 team-stat rows (+ time of possession); line 1 **Leaders**, line 2 **Recap** headline; the highlight's still when a highlight is offered, otherwise matchup art captioned with the reason. §2. |
| Slots | Pre: Follow · Channels · **Matchup**. Live: Follow · Watch / Channels · **Scoring (n)**. Final: **▶ Highlights**, or **▶ From start**, or disabled **Highlights soon** / **No highlights** (no glyph), then **Scoring (n)** · **Box score**. Postponed: none. Every set fits 664 px at every size (§2.3, measured). |
| Scoring sheet | The Goals sheet generalised: scoring plays in game order (no video marks), then **Game highlights**, then **More videos** (every other offered clip, oldest first), then catch-up rows. §3.1. |
| Video | Owner ruling 2026-11-25: ESPN clips play in the app's own player, as the NHL's do (route A: one ExoPlayer, the root surface, the broker's `clip:<host>` key). "Video: ESPN" is on every clip's sub-line. `ESPN_VIDEO_ENABLED` switches all NFL video off. A league router sends NFL refs to a new `EspnClipResolver`. `ClipKind.RECAP` is reused for "Game highlights"; a new `ClipKind.PLAY` covers single-play clips. An expired or embargoed clip is never offered, and the view model re-checks at each expiry instant. §4. |
| Data | `summary?event={id}`, for the shown game only. Pre-game: every 30 min. Live and both kinds of break: a plain **60 s** loop, with back-off always winning. A phase change is due at once. Final: re-read every 5 min until the highlight appears (≤ 2 h), and until 30 min after the end when the game has single-play clips. Streaming decode (kotlinx `decodeFromStream`, `ignoreUnknownKeys`, `CappedStream`); `drives`, `news`, `winprobability` and the rest skipped; 2 MiB decoded cap. The box score comes from the same answer, through the repository's loop. §5. |
| Contracts | Generalise, don't twin. `StatKind` and `StatValue` grow only the members a row uses. A small `ScoringEvent` interface sits over the NHL `GoalItem` and the new `ScoringPlay`. `GameClip`, `RecentGame`, `InjuryLine` and `BoxTable` are appended with defaults. `ClipResolver.stream(ref, fresh)`. `ClipOffer` is the one validity rule. One `HubLeagueUi` per league in `:ui` replaces the `when (group)` branches. Commit 0 makes all three packages compile on their own (§9.1). §6. |
| Not shown | Odds, against-the-spread, ESPN's win probability and matchup predictor (no gambling content; no third-party model numbers); weather; officials; standings; the article body and picture. §2.4. |
| Unchanged | Channel matching, Follow, Watch / Channels, the day strip and the cards. The "● Highlights" card chip now also lights for the NFL from the board, while the highlight is offered. Nothing in this design tunes a channel. §7. |

---

## 1. What ESPN gives, and what it means for the panel

From FACTS.md, checked against the samples:

| Fact | Consequence |
|---|---|
| One `summary?event=` answer holds header, team stats, player box, leaders, scoring plays, article, videos, injuries and the last five games (pre-game) | One request per refresh. No fan-out across ESPN's core API, which would need 5+ calls for the same facts. |
| Final summary 46–58 KB gzip / 495–641 KB decoded, of which `drives` is ≈ 70 %; no ETag, no 304; `max-age` 3–10 s; `exclude=` and friends ignored | Must stream and skip. It cannot be made cheaper on the wire, so it refreshes more slowly than the NHL detail (60 s, not 30 s). |
| Key order varies between samples (`header` comes after `drives` in `sum-final.json`, and before `winprobability`, `article` and `videos` in `sum-mnf-401873078.json`) | No early stop. The decoder reads the whole body every time, and no code may depend on key order. The cap and cancellation must hold for the full read. |
| Team order differs by section: `header.competitions[0].competitors`, `leaders[]` and `injuries[]` are home first; `boxscore.teams[]` and `boxscore.players[]` are away first (all 9 summaries) | Every section is mapped to a side **by team id** through the header's `homeAway`, never by index. |
| Most games: one 52–74 s "Final Game Highlight", posted 14 min (MNF) to 20 min (PIT@PHI) after END GAME. ESPN-aired games add ~10 single-play clips during the game, 6–11 min after each play, and some after the highlight (MNF: one 7 min later) | "Highlights soon" for up to 2 h after the final. Single-play clips exist only on ESPN-aired games. The final re-read continues 30 min past the end when such clips exist. |
| Clips expire 48–96 h after posting and leave the lists | The highlight is a 2–4 day feature. After it expires the panel shows matchup art with the reason. |
| No condensed game, no three stars, no league ranks, no season series | No Condensed row; leaders replace stars; season rows show plain values. |
| A written recap headline (an AP story via ESPN) | NFL final panels get a recap line, which the NHL has no source for. Its picture is not used (§2.1 media). |
| The scoreboard the app already polls carries `highlights[]` (the final highlight only), `situation` (live), `broadcasts` (national network, every game in `sbnow.json`) and `linescores` | The card chip and slot 1 can light from the board before the summary loads. The live situation line and the box score's line score come from the board. The pre-game caption names the network. |

---

## 2. Panel content (geometry unchanged: 720 × 864, inner 664, `HubPanelPlan`)

### 2.1 Element map, NHL → NFL

| NHL hub element (m6-sports-hub §1.3, §6.7) | NFL counterpart | Source (summary unless SB = scoreboard) |
|---|---|---|
| Status pill, headline score | unchanged, from the **board** (the NFL detail carries no score, §6.1). End of quarter reads **"END Q1"** ("END OT"), not "Q1 INT". A delay reads **"DELAYED · Q3 5:32"**. | SB |
| Media, final | **Highlight still** while a highlight is offered and video is on: tag HIGHLIGHTS, play disc (≥ 200 px tall), caption "Game highlights (1:14)", the clip's `thumbnail`. Otherwise **matchup art with the reason caption** (§2.4). The recap's picture is never used: on MNF it is a studio picks-show still, and on other games a frame of a video that is no longer offered. | `videos[]`, SB `highlights[]` |
| Media, live | matchup art; caption "venue · network" | `gameInfo.venue.fullName`; SB `broadcasts` |
| Media, pre-game and postponed | matchup art; caption "venue · network" ("SoFi Stadium · Netflix"). Before the summary arrives: the network alone. | as live |
| Stats header "SEASON" / "TEAM STATS" | same words | — |
| Season rows (Record, Last 10, GF, GA, PP %, PK %) | Record, **Last 5**, Points scored per game, Points allowed per game, Yards gained per game, Yards allowed per game (§2.2) | header `record`, `lastFiveGames`, `boxscore.teams[].statistics` |
| Game rows (SOG, PP, Hits, Blocked, FO %) | Total yards, Passing yards, Rushing yards, (final: First downs), 3rd down, Turnovers; final extra Time of possession; live extra Timeouts, only when the situation line had to drop it | `boxscore.teams[].statistics`; SB `situation` |
| "BY QUARTER" rows (today's NFL panel) | gone. Quarters are in the Box score sheet's line score (from the board). | — |
| Summary, final (game-winning goal / Stars) | line 1 **Leaders · Burrow 300 pass · Daniels 55 rush · Williams 122 rec**, fitted to one line; line 2 **Recap · {headline}**, one line, ellipsized; the first line to give way | `leaders[]`, `article.headline` |
| Summary, live (latest goal / Periods) | line 1 **situation**, fitted to one line, from the board (§2.4); line 2 **Latest score · Tee Higgins 31 Yd pass from Joe Burrow (CIN)** | SB `situation`; `scoringPlays[]` last |
| Summary, pre-game (season series / points leaders) | line 1 **Passing leaders · Love 2,838 · Stafford 2,623**; line 2 **Rushing leaders · Johnson 214 · Williams 616** (away first) | `leaders[]` season |
| Three stars | none; game leaders on summary line 1; per-player lines in the Box score tables | `leaders[]` |
| Condensed game | none (no source) | — |
| Goals sheet | **Scoring** sheet (§3.1) | `scoringPlays[]`, `videos[]` |
| Box score (skaters, goalies) | line score by quarter (+OT), then per team Passing, Rushing, Receiving (§3.5) | `boxscore.players[]`; SB `linescores` |
| Matchup sheet (leaders last 5, goalies) | Season leaders (pass, rush, rec, sacks, tackles) per team, last 5 games, injury report (§3.6) | `leaders[]`, `lastFiveGames`, `injuries[]` |
| "● Highlights" card chip | lit while the board's highlight is offered (`ClipOffer`, §4.3) | SB `highlights[]` |
| Channel line | unchanged; also drawn for a final NFL game with a catch-up airing (as the NHL) | matcher |
| "More game details come for the NHL first." | removed for the NFL; the CHL keeps a reworded line (§6.7) | — |

### 2.2 Stat rows (order, labels, rule)

Labels are centre-column words, 24/500, in the 332 px centre column (the row splits 166 / 332 / 166,
`HubPanel.kt:1097-1099`). The "better" side (white against `Fg70`) comes from `StatRules.better` (§6.1). Values are
ESPN's `displayValue`s parsed into typed values. An absent row draws "–" in both columns, so the phase keeps its row
count. Sides come from team ids (§1).

| Phase | Rows, in order (mandatory) | Extra (dropped first, §2.6) | Better |
|---|---|---|---|
| Pre-game | Record · Last 5 · Points scored per game · Points allowed per game · Yards gained per game · Yards allowed per game | — | none · none · higher · lower · higher · lower |
| Live, halftime, end of quarter, delayed | Total yards · Passing yards · Rushing yards · 3rd down · Turnovers | Timeouts, only when the situation line dropped it (§2.4) | higher · higher · higher · rate higher · lower; Timeouts none |
| Final | Total yards · Passing yards · Rushing yards · First downs · 3rd down · Turnovers | Time of possession ("31:31") | as live; first downs higher; possession none |

Measured label widths at 24/500: the widest, "Points allowed per game", is 273 px at Default and 328 at Largest, which
fits 332. "Time of possession" is 214 / 256. The side values are at most 74 px ("4-5-1" at Largest) against 166.

ESPN names read: pre-game `totalPointsPerGame`, `totalPointsPerGameAllowed`, `yardsPerGame`, `yardsPerGameAllowed`
(`Decimal`, 1 place), the header's `record[type=total].summary` and `lastFiveGames` (`Text`). Live and final:
`totalYards`, `netPassingYards`, `rushingYards`, `firstDowns` (`Count`, which can be negative for passing),
`thirdDownEff` "4-12" (`Fraction(dash = true)`), `turnovers` (`Count`), `possessionTime` "28:29" (`Clock`). Of the 8
pre-game averages, passing and rushing yards for and against are dropped, because four rows of yardage read as noise on
a TV. Of the 25 final rows, the six above are the ones a viewer checks. The rest (4th down, red zone, penalties, sacks,
per-play averages, drives, plays, defensive TDs) is not shown, and its `StatKind` members are not added (§15). The
duplicated `interceptions` entry is not read. "3rd down" shows "4-12"; its better side compares the rate, with the
count made as the tie-break. "Last 5" is computed from `lastFiveGames[].events[].gameResult`: "3-2" or "4-1", and a
tie adds "-1". No league ranks exist, so none are shown.

### 2.3 Slots (three fixed, frozen while focus is in the panel; m6-sports-hub §1.5, §2.3)

| Phase | Slot 1 | Slot 2 | Slot 3 |
|---|---|---|---|
| Scheduled, pre-game | Follow / Following ✓ | Channels (n) | **Matchup**, enabled when the sheet has a row |
| Live, breaks, delayed | Follow / Following ✓ | Watch / Channels (n) / Finding… | **Scoring (n)**, enabled when there is ≥ 1 scoring play or ≥ 1 offered clip, or while the detail is loading or failed. Otherwise it is disabled "Scoring", spoken "Scoring, unavailable. No points yet." |
| Final, highlight offered, video on | **▶ Highlights** (action WATCH_RECAP) | **Scoring (n)**, always enabled on a final | **Box score**, always enabled |
| Final, no offered highlight, a catch-up airing found | **▶ From start** (action WATCH_FROM_START) | Scoring (n) | Box score |
| Final, ended < 2 h, no highlight yet, no catch-up | disabled **Highlights soon**, no glyph | Scoring (n) | Box score |
| Final otherwise (expired, never posted, video off) | disabled **No highlights**, no glyph | Scoring (n) | Box score |
| Postponed or cancelled | none | | |
| Detail failed, nothing cached (retryable kind) | **↻ Retry**. On a final whose board highlight is offered, ▶ Highlights stays (the NHL rule). | live: as live. final: **Scoring** (no count; the sheet shows its failure body) | final: **Box score** (its Retry row) |

Disabled slots draw **no glyph**. `slotGlyph` (`HubPanel.kt:914-919`) keys the ▶ off `enabled` as well as the action.
The NHL's "Recap soon" follows the same rule.

Width check: label + 2 × padding (+ glyph + 8 on slot 1 when drawn) + 2 × gap. Padding is 28 with a 16 px gap, or 20
with a 12 px gap above factor 1.05 (`HubPanel.kt:1232-1233`). All labels are 24/600. Measured, with two-digit counts:

| Slot set | 0.9 | Default | Larger | Largest |
|---|---|---|---|---|
| ▶ Highlights · Scoring (12) · Box score | 550 | 588 | 570 | 608 |
| ▶ From start · Scoring (12) · Box score | 552 | 591 | 573 | 611 |
| Highlights soon · Scoring (12) · Box score | 582 | 624 | 611 | 653 |
| No highlights · Scoring (12) · Box score | 558 | 598 | 582 | 622 |
| ↻ Retry · Scoring · Box score | 457 | 485 | 456 | 484 |
| Following ✓ · Channels (36) · Scoring (12) | 586 | 629 | 616 | **659** |
| + Follow · Channels (36) · Scoring (12) | 555 | 594 | 576 | 615 |
| Following ✓ · Channels (36) · Matchup | 555 | 595 | 578 | 618 |
| *(rejected)* ▶ Watch highlights · Scoring (10) · Box score | 624 | 670 | 660 | 707 |
| *(rejected)* ▶ Watch from start · Scoring (12) · Box score | 621 | 667 | 657 | 703 |

The live set leaves 5 px at Largest. A three-digit channel count would not fit; this is the same limit the NHL's
"Goals (n)" row has, and `HubPanelFitTest` asserts the two-digit sets above at all four sizes.

Final-game Watch (task B): with an offered highlight, OK, OK plays it (2 presses, as the NHL recap). Otherwise OK on the
card lands on slot 1 when it is usable (From start), else on Scoring.

**Spoken reasons for disabled slots** (UX M4; generalises the beyond-guide rule of event channels §18.3,
`HubPanel.kt:1243-1248`). `HubLeagueUi.slotReasons` gives each disabled slot a reason:
- Highlights soon: "Highlights about 15 min after the game".
- No highlights: the §2.4 caption in force (didn't post / removed after a few days / videos aren't available).
- Scoring, live: "No points yet".
- NHL Recap soon: "Recap not posted yet".
- Channels: the existing beyond-guide line.

The grey slot is described "%1$s, unavailable. %2$s" (the existing `sports_hub_desc_slot_unavailable`). The first
usable slot is described "%1$s. %2$s unavailable: %3$s", for example "Scoring (9). Highlights unavailable: ESPN didn't
post highlights for this game". Its reasons are joined when two slots are grey. Content descriptions do not change
pixels, so the NHL goldens stay hash-equal.

### 2.4 Captions and summary words; what is not shown

**Media captions.** Art captions are 22/500 and poster captions 26/600, in 632 px. Widths are given as Default /
Largest.
- offered highlight (poster): "Game highlights (1:14)", 274 / 328;
- no offered highlight, ended < 2 h: "Highlights about 15 min after the game", art 395 / 474;
- none listed, ended ≥ 2 h and < 48 h: "ESPN didn't post highlights for this game", art 418 / 502;
- none listed, ended ≥ 48 h: "ESPN removes videos after a few days", art 387 / 465;
- video off (`ESPN_VIDEO_ENABLED` false, or a refusal sets NFL video off): "ESPN videos aren't available right now", art
  390 / 468;
- pre-game, live and postponed: "{venue} · {network}" (worst seen "Mercedes-Benz Stadium · Prime Video", 393 / 471), the
  first national name in the board's `broadcasts`. The network alone before the summary loads; the venue alone when the
  board lists none.

**Summary lines** are 24 px (label 600, text 400) on the 664 px line. The NFL lines are drawn `maxLines = 1`
(`SummaryLine.oneLine`, §6.6), so a measured line never wraps and hides line 2. "Fitted" means the first candidate whose
measured width is ≤ 664, using the panel's `Measure` (`HubNames.kt:56`; §6.6 passes it to the build).

*Live line 1, the situation.* It comes from the board, `competitions[0].situation`, which `EspnMapper.detail()`
(`EspnMapper.kt:361-399`) already parses into `LeagueDetail.Nfl`:
- `play` is `NflPlay.Down(down, distance, goalToGo, field: FieldPos(territory, yard))` from `down`, `distance`,
  `shortDownDistanceText` and the numbers rule for goal-to-go, with `possessionText` or `yardLine` for the field. It
  can also be `Kickoff(kicker, field)` (distance 0 with possession), `Dead(scorer, scoreValue)` (after a score), or
  `None`.
- `possession` from `possession` (a team id), `redZone` (`isRedZone` and a real down), `breakKind`, `lastPlay`, and
  `awayTimeouts` / `homeTimeouts`.

The line is rebuilt on every board emission, at the board's cadence (30 s live, faster while followed), never at the
summary's 60 s. It needs no detail, so it also shows while the detail loads or has failed. Words:
- Down: "{ABBR} ball · {2nd} & {7} at {WSH} {35} · Red zone · Timeouts {2}–{3}". Goal to go reads "1st & Goal at WSH 4".
  Midfield reads "at midfield". Without a field the " at …" part is left out, and without possession so is
  "{ABBR} ball · ". Red zone appears only when `redZone` is set. Timeouts appear (away–home) when both are known.
- Kickoff: "Kickoff · {kicker ABBR}". Dead: "After the score". None, and halftime: no line 1 (the board does not say
  who receives).
- Delayed (§2.5): "Delayed · Play is stopped; the clock restarts when it resumes".
- Fitting: the full line, then without Timeouts (which then becomes the extra Timeouts row), then without Red zone.
  Measured: "CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3" is 580 at Default and 696 at Largest, which drops
  Timeouts there and leaves 500. "CIN ball · 1st & Goal at WSH 4 · Red zone · Timeouts 2–3" is 593 / 711, and 515 after
  the drop.
- Spoken: "Bengals ball, second and 7 at the Commanders 35, red zone, timeouts 2 and 3".

*Live line 2*: "Latest score · {play text before ' ('} ({ABBR})", ellipsized. "Latest score · Tee Higgins 31 Yd pass from
Joe Burrow (CIN)" is 654 / 784.

*Final line 1, Leaders.* One game leader per category, the higher `value` of the two teams (passing, rushing and
receiving yards are whole numbers). Candidates, in order: 3 categories with team, 3 without, 2 with, 2 without, 1 with.
The category words are "pass", "rush" and "rec". Measured on MNF:
- "Leaders · Burrow 300 pass (CIN) · Daniels 55 rush (WSH) · Williams 122 rec (WSH)" is 887, too wide;
- "Leaders · Burrow 300 pass · Daniels 55 rush · Williams 122 rec" is 656 and fits at Default;
- at Largest the first fit is "Leaders · Burrow 300 pass · Daniels 55 rush" at 562.

*Final line 2, Recap*: "Recap · {article.headline}", one line, ellipsized. It is the first summary line to give way
(§2.6). Every headline in the samples (63–103 characters) is wider than the line ("Recap · Bills' defense feasts in 26-9
win over injury-depleted Dolphins" is 760 at Default), so the visible line is always the start of the headline. That is
accepted (lead ruling 8: headline only, one line). The full headline is the line's content description.

*Pre-game lines*: "Passing leaders · Love 2,838 · Stafford 2,623" (477 / 572) and "Rushing leaders · Johnson 214 ·
Williams 616" (478 / 574), away first, each from its team's `leaders[]` entry.

*Names*, in both phases: ESPN's `shortName` after the first ". ", which keeps suffixes and particles: "J. Cook III" →
"Cook III", "L. Van Ness" → "Van Ness", "C.J. Stroud" → "Stroud", "A. St. Brown" → "St. Brown". Without ". " the
whole `shortName` is used. Never `substringAfterLast(' ')`.

Recap headline (task A): shown on final games (lead ruling 8, the default of Q2). The article's `description`, `story`
and `images` are not read.

Not shown, with the reason:
- **Odds, spread, over/under, money lines** (`pickcenter`, `odds`, `againstTheSpread`, board `odds`): gambling content,
  not asked for. Skipped by the decoder.
- **Win probability** (`winprobability`) and **matchup predictor** (`predictor`): an ESPN model's number with no room to
  explain it on a TV panel, and a chart would be new geometry. Skipped.
- **Weather**, **division standing**, **home / road records**: deferred (§15).
- **Injuries** on the panel: no; they are in the Matchup sheet only (§3.6).
- Officials, attendance, news, ticket info, the recap picture: skipped.

### 2.5 Loading, failure, video off, delay

- **Loading** (no detail for this id yet): the phase's labels with "–" in `Fg40`, as the NHL. The live situation line
  already shows (board).
- **Failed with nothing cached**: the failure text takes the rows' place (`ERROR_ROWS`, 3 rows of room), as the NHL.
  The summary keeps what the board gives: live line 1 is the situation and line 2 the board's passing leader (today's
  `sports_hub_summary_passing` line); final shows today's two board lines (Passing, Rushing); pre-game shows none. The
  reason and the next step depend on `FailKind`:
  - TIMEOUT / IO / 5XX / OTHER: "Couldn't load game stats from ESPN. Trying again automatically." followed by the next
    step from the slots on screen (`failureText`'s rule): "Select Retry to try now.", "Select Highlights to see the
    game." or "Press Back, then OK, to retry now.";
  - 429 / REFUSED (this includes ESPN's WAF block, §5.3): "ESPN is limiting requests right now. Stats load again in a
    few minutes.";
  - BAD_BODY (including over the 2 MiB cap): "ESPN changed how it sends game stats. Scores still update; stats need an
    app update.";
  - OFFLINE: "No internet connection. Stats load again when the network is back."
  The slots are those in §2.3's failure row. The earlier idea of keeping the board's BY QUARTER rows *and* a 3-row note
  was dropped: at Largest it leaves 89 px for the media against the 120 floor.
- **Failing or stale with a detail shown**: "Stats from 8:12 PM" (22 `Warn`), as the NHL.
- **Video off**: slot 1 is disabled "No highlights" (spoken reason "ESPN videos aren't available right now"), the media
  is art with that caption, the card chip is off, and the Scoring sheet lists no video rows but shows an info row with
  the same words. `SportsViewModel.videoOff` becomes per league (§6.6), so an NHL key refusal never hides NFL video, and
  the reverse.
- **Delayed** (UX m12): `EspnMapper` sets `LeagueDetail.Nfl.delayed` when the board's `status.type.name` contains
  "DELAY" (ESPN's `STATUS_DELAYED` / `STATUS_RAIN_DELAY`; NV, no sample). The phase stays LIVE (`EspnMapper.kt:163`).
  The pill reads "DELAYED · Q3 5:32", and line 1 is the delay line (§2.4). The detail keeps the plain 60 s loop.

### 2.6 Height budget (`HubPanelPlan.plan`, PANEL_H 864; worst case, with a channel line)

Media height = 864 − fixed − rows − summary. Default: 534 − 38 × rows − (16 + 32 × lines). Largest (factor 1.2): 503 −
46 × rows − (16 + 39 × lines). Without a channel line add 46 (Default) or 51 (Largest). Floors: a poster 160, art 120.
**New** (UX m9): an extra row is kept only while a playable poster keeps ≥ `POSTER_DISC_MIN_H` (200), so the play disc
survives at Default. `plan()` gains `extraFloor` (§6.6); it equals `mediaFloor` for every NHL and art case.

| Phase | Default | Largest | Given way at Largest |
|---|---|---|---|
| Pre-game (6 rows, 2 lines, art) | 226 | 133 | nothing |
| Live (5 rows, 2 lines, art) | 264 | 179 | nothing at 179. When the situation line drops Timeouts, the Timeouts row is added: 133. |
| Live + stale note | 232 | 140 | the Timeouts extra (it would leave 94) |
| Final, highlight poster (6 + Possession, 2 lines) | 226. Possession dropped, since it would leave 188 < 200. Without a channel line: 234 with Possession. | 172 (1 line) | Possession; Recap line (2 lines would be 133 < 160); no disc (< 200, the existing U-N11 rule) |
| Final, art (6 + Possession, 2 lines) | 188 with Possession | 133 | Possession; both lines stay (133 ≥ 120) |
| Failed (error text = 3 rows, ≤ 2 board lines) | ≥ 340 | ≥ 271 | nothing |

The order of giving way is the existing one: extras, media to its floor, summary line 2, line 1. Mandatory rows never
drop. The Recap is line 2, so it is the line that drops first. At Largest it drops on the highlight poster and stays on
art. `HubPanelFitTest` asserts every row above at the four sizes, using "Commanders", "Buccaneers", "Croskey-Merritt",
the 99-character MNF headline and the MNF leaders. It also asserts that the Leaders line is drawn at Default with a
highlight poster.

---

## 3. Sheets

### 3.1 Scoring sheet (the Goals sheet's twin; shared frame 968 × 864 at y 160; rows have 880 px inside)

Title 32/600: "Scoring · CIN at WSH". Rows, in this order:

1. **Scoring plays**, in `scoringPlays[]` order (chronological), 96 tall with a 16 gap:
   - left: the scoring team's logo at 64 px (`scoringPlays` has no athlete or headshot);
   - line 1 (26/600, one line, ellipsized): the play text before " (", e.g. "Tee Higgins 31 Yd pass from Joe Burrow";
   - line 2 (22/500 `Fg70`): "Q2 1:55 · CIN TD · CIN leads 10–0". The type word comes from `PlayScore`: TD, FG, Safety,
     Def TD, and none for OTHER (§6.1). The score part reads "Tied 10–10" when level, and the period "OT 4:12" in
     overtime. Measured at most 369 / 443;
   - no video mark: rows never show ▶ and never say "Video soon" (the clip-to-play link is deferred, §15). OK on a row
     shows the toast "ESPN posts videos of only a few plays, mostly in games it airs. Any it posted are under More
     videos." The rows stay focusable so they scroll and are read aloud.
2. **Game highlights** (an offered RECAP clip): thumbnail 128 × 72 from `GameClip.thumbUrl` (none on low-end devices: a
   play glyph instead), "Game highlights", "1:14". With none offered: a non-focusable info row (22/500 `Fg55`) with the
   panel caption's words (§2.4).
3. **More videos** (header 22/600 `Fg55`, not focusable, 44 tall): every offered PLAY clip, oldest first
   (`originalPublishDate`), 96 tall: a play glyph at 64, the clip headline (26/600, 2 lines), and "▶ 0:31" on the right.
   The section is absent when there are none.
4. **Full game from the start** rows (final, a catch-up airing found; m6-sports-hub §5.6).

Focus on open: the clip just played (U-S14); else, live, the latest scoring row; else the first row (NHL rule).

Queues (`ClipSession`, "Up next" for 1.5 s between items): from a More videos row, that clip and every later More videos
row; Game highlights alone. When advancing, an entry that `stream()` answers `Expired` is skipped, and the session ends
only when none remain (§4.6).

Back closes to the slot that opened it.

### 3.2 Sheet states (reason + next step)

| State | Body |
|---|---|
| Detail loading | "Loading the scoring plays from ESPN. They show here in a moment; press Back to close." |
| Detail failed, nothing cached | "ESPN didn't send the scoring plays. It tries again on its own; press Back to close and check back shortly." Catch-up rows are still listed below. |
| Live, no score yet | "No scoring yet. Plays appear here as teams score; press Back to close." Video rows are still listed if there are any. |
| Final, no scoring plays | "No points were scored in this game." Then the video and catch-up rows. |
| Video off | no Game highlights or More videos rows; one info row: "ESPN videos aren't available right now" |

### 3.3 Clip player for the NFL (`ClipPlayerLayer`)

Keys and transport are unchanged. Every NFL sub-line ends "· Video: ESPN" unconditionally. With the footer gone (§15.6)
it is the only attribution left, and the 1728 px transport bar has room:
- "Game highlights · CIN at WSH · Video: ESPN" (482 px at 24/500, 578 at Largest);
- "Video 2 of 4 · CIN at WSH · Video: ESPN" (More videos; 429 / 514).

### 3.4 Errors while playing (toasts and the mid-clip card; m6-sports-hub §6.8 with ESPN words)

| Situation | Text |
|---|---|
| Clip expired or embargoed before play (`ClipResult.Expired` → `Failure.EXPIRED` → `VIDEO_EXPIRED`) | "ESPN removed this video; game videos stay up for a few days. Pick another one." |
| Clip within `HUB_CLIP_EXPIRY_MARGIN_MS` of expiry | never offered (no row, no slot, no chip, no disc), so no message |
| Master 403 / 404 / 410 (`CLIP_EXPIRED`) | one re-resolve with `stream(ref, fresh = true)` (§4.2); if that fails, the card: "The video stopped loading. Select Try again, or press Back to return to the game." Try again also passes `fresh = true`. |
| Record lookup 429 / 5xx / IO / WAF (catalog miss, or a fresh lookup) | "ESPN's video service didn't answer. Try again in a minute." |
| Offline | "No internet connection. Videos play again when the network is back." |
| No https HLS in the record | "This video can't play on this device. Scores and stats still work." |
| Geo (NV): a 403 on the first segment with a geo body cannot be told apart here | treated as `CLIP_EXPIRED` (above); recorded as a risk (§12) |

Toasts carry `HubToast.league`, so `SportsScreen` picks the ESPN or the NHL.com wording.

### 3.5 Box score (final; slot 3)

- Top block (not focusable): the line score, 22/500 tabular, team abbreviations by Q1 Q2 Q3 Q4 (OT) T. It comes from the
  board's `BoardExtras.periods` (already parsed, `EspnMapper.kt:231-252`) and the board score.
- Then the existing two tab chips (team nicknames). Per team, three tables from `TeamBox.tables`:
  - Passing: C/ATT YDS TD INT RTG (keys `completions/passingAttempts`, `passingYards`, `passingTouchdowns`,
    `interceptions`, `QBRating`);
  - Rushing: CAR YDS AVG TD LONG;
  - Receiving: REC YDS AVG TD LONG.
  Rows are 44 tall, the name 26/500 (`athlete.displayName`; box athletes carry no `shortName`), and the values 22/500
  right-aligned. Each table shows at most `HUB_NFL_BOX_LINES` (8) athletes, in ESPN's order. Defense and Kicking are
  deferred (§15).
- Source: the same summary answer, through the repository loop (§5.5). Failure: the existing Retry row with "Couldn't
  load the box score from ESPN. Select Retry, or press Back to close."

### 3.6 Matchup (pre-game; slot 3)

- **Season leaders.** Passing, Rushing, Receiving, Sacks and Tackles. Each category has a label row (22/600 `Fg55`) and
  then one row with the away and home leaders:
  - a round headshot at 72 px (`headshot.href`, decoded at 72 px);
  - the name, 24/600, by the §2.4 rule;
  - the line, 22/500, one line: ESPN's `displayValue` split on ", ", with the attempts part dropped ("248/372", "139
    CAR"), units lower-cased and joined with " · ". So "248/372, 2,623 YDS, 19 TD, 10 INT" becomes "2,623 yds · 19 TD ·
    10 INT" (240 / 288 px against the ~342 px side), "70 REC, 715 YDS, 4 TD" becomes "70 rec · 715 yds · 4 TD", and
    sacks and tackles read "5.5 sacks" and "67 tackles". Fractional values come only from `displayValue` (`Leader.line`),
    never from the integer `value`.
- **Last 5 games.** One row per team, oldest to newest as ESPN lists them, with the score winner first as ESPN writes it:
  "W 23–13 DAL · L 20–17 @ DET · W 27–19 CAR · W 45–27 @ NE · L 30–10 MIN" (22/500, wraps to two lines). "vs" games carry
  no prefix.
- **Injury report.** Per team, the players with status Out, Doubtful or Questionable (Injured Reserve is a season list
  and is left out), at most `HUB_NFL_INJURIES_PER_TEAM` (6), then "+n more": "K. Zeitler · G · Questionable (triceps)".
- The slot is enabled when at least one section has a row.

### 3.7 Spoken forms (UX m8)

New description strings, read by TalkBack and VoiceView:
- stat rows: "3rd down: Bengals 4 of 12, Commanders 2 of 10"; "Time of possession: Bengals 28 minutes 29, Commanders 31
  minutes 31"; decimals as written;
- the situation line: §2.4;
- a scoring row: "Bengals touchdown. Tee Higgins 31 yard pass from Joe Burrow. Second quarter, 1:55. Bengals lead 10 to
  0." (" Yd " is read "yard", and the type word is spelled out: touchdown, field goal, safety, defensive touchdown);
- a video row: "Video, 31 seconds. {headline}";
- an injury row: "Kevin Zeitler, guard, questionable, triceps". Position names come from a fixed map of NFL
  abbreviations (QB, RB, FB, WR, TE, OT, T, G, C, DE, DT, NT, LB, OLB, ILB, CB, S, DB, K, P, LS); unknown abbreviations
  are read as written;
- the disabled slots: §2.3.

---

## 4. Video

### 4.1 Owner ruling (Q1 closed)

Owner ruling 2026-11-25: ESPN clips play in the app's own player, as the NHL's do. The exposure the proposal described
stands, and was accepted with that ruling. ESPN's clip media needs no key, token or login and carries no DRM. ESPN's own
players insert a pre-roll advert on the client, so The Box shows ESPN's video without ESPN's advertising. ESPN publishes
no API terms; its site terms forbid re-use of content; `www.espn.com/robots.txt` disallows its video player paths.

Mitigations, all required:
- "Video: ESPN" on every clip's sub-line (§3.3);
- `embargoDate`, `expirationDate` and `deviceRestrictions` are honoured: a clip whose whitelist omits `settop` is never
  offered;
- nothing is downloaded, cached on disk or re-served;
- `SportsConstants.ESPN_VIDEO_ENABLED` (true). When it is false, every NFL video row, slot, chip and poster disc is off,
  and `ClipResolvers` answers `NotFound` for NFL refs. Stats are untouched, and a point release can flip it.

### 4.2 Resolver seam

- `ClipResolver` gains `stream(ref, fresh: Boolean = false)` (arch M3). `NhlClipResolver` ignores `fresh`, because it
  always looks up (`NhlClipResolver.kt:77-78`). `ClipSession` passes `fresh = true` on the `CLIP_EXPIRED` re-resolve
  (`ClipSession.kt:358-362`) and on Try again (`retry()`).
- `ClipResolvers` (`:data`, the router, bound in `SportsModule`) sends `ref.gameId.league`: NHL → `NhlClipResolver`; NFL
  → `EspnClipResolver` (or `NotFound` when `ESPN_VIDEO_ENABLED` is false); anything else → `NotFound`.
- Ids: ESPN clip ids are numeric (`50578630`), so `ClipRef.videoId: Long` is used as is.
- Kinds. `ClipKind.RECAP` is reused for the per-game package (`tracking.coverageType == "Final Game Highlight"`, and an
  in-progress package if ESPN lists one under that type during a game): it has the same role, slot (WATCH_RECAP) and
  single-item queue, and only the words differ by league. **`ClipKind.PLAY`** (appended) is for `coverageType ==
  "OnePlay"`: queued like GOAL ("Up next"), but with no headshot and no goal number. Every other coverage type is
  dropped by `EspnVideoMapper`.
- `EspnClipCatalog` (`:data`, `@Singleton`, memory only, LRU `ESPN_CLIP_CATALOG_MAX` (64) by clip id, `synchronized`):
  `{GameClip, hlsUrl}` entries. It is written by `EspnDetailSource` after each summary parse (the offered entries
  `EspnVideoMapper` returned) and by `EspnClipResolver` after a record lookup. The board does not write to it (§15). It is
  never persisted. The HLS URL lives only here and in the `ClipStream` handed to `playClip`.
- `info(ref)` for ESPN is **catalog-only**: a hit returns `ClipInfo(ref, title, null, durationMs, posterUrl = null,
  thumbnailUrl = thumbUrl)`; a miss returns `NotFound`. Neither makes a request (arch M2). The panel and the sheet build
  their pictures from `GameClip` and never call `info()` for the NFL (`NflHubUi.clipRefs` returns none). `ClipSession`'s
  "Up next" title is a catalog hit.
- `stream(ref, fresh = false)`: a catalog entry, else the record lookup. Then `ClipOffer` at `now + skew` (else
  `Expired`), then `ClipStream(hlsUrl, HLS, captionsUrl = null)`. When the catalog is fresh, nothing is looked up just to
  play: the URLs are unsigned and stable, and the master request is itself the freshness check.
- `stream(ref, fresh = true)`: the record lookup, bypassing the catalog. 404 → `NotFound` ("ESPN removed this video").
  200 with the same HLS URL as the catalog → `Failed(HTTP_OTHER)`, with no replay of the dead URL. 200 with a new URL →
  catalog updated, play. The resolver never reads a summary.
- Record lookup: `GET https://content.core.api.espn.com/v1/video/clips/{id}` (`EspnEndpoints.clipBase`, debug override
  `{origin}/espn-core/v1/video/clips/`), `@ApiClient`, call timeout 10 s, through `EspnHttp` with the
  `ESPN_CLIP_RECORD_MAX` (64 KB) cap. The answer is `{videos: [ {…} ]}`, a one-item list, decoded into
  `EspnClipRecordDto` and mapped by `EspnVideoMapper`. Outcomes:
  - 200 → map;
  - 404 → `NotFound`;
  - 429 / 5xx / IO → `Failed(kind, retryAfterS)`;
  - 401 / 403 / 202 with `x-amzn-waf-action` → `Failed(HTTP_429)`. This is never `Refused` and never sets video off
    (arch M6).
- `ClipStream`'s KDoc becomes "From a lookup (NHL) or the in-memory catalog (ESPN, unsigned URLs); never stored on disk
  or logged".

### 4.3 Validity, and when it is re-checked

`ClipOffer.offered(embargoMs, expiresMs, nowMs)`, implemented in commit 0 and the **one** validity rule, holds when:
- `embargoMs == null || embargoMs ≤ now`; and
- `now + HUB_CLIP_EXPIRY_MARGIN_MS (5 min) < expiresMs`.

`EspnVideoMapper` gives every entry an effective `expiresMs`: `expirationDate`, else `originalPublishDate +
HUB_CLIP_DEFAULT_LIFE_MS` (48 h, the shortest window seen). With neither, the clip is dropped. A clip is offered (sheet
row, slot, chip, poster) only when:
- `ClipOffer` holds;
- `deviceRestrictions` lists `settop` (or is absent);
- the HLS href is https (`http` only from the debug override origin).

**Reference time** (arch m13): `now + skew`. The skew is `serverDate − wall clock at receive` of the last summary answer
(`GameDetail.clockSkewMs`, clamped to ±`HUB_CLOCK_SKEW_MAX_MS` (1 h), 0 without a `Date` header). `EspnMapper` evaluates
board highlights at the board's own `serverDateMs`, so the pure mapper needs no wall clock.

**Re-evaluation** (arch M7): there is no repository re-publish. An equal `DetailState.Ready` is dropped by the
`StateFlow`, and the repository's wake is mono time. `SportsViewModel` owns one `offerTick`. A single coroutine waits
until the earliest `ClipOffer.changesAt(…)` among the shown game's `clips` and the visible cards'
`media.recapExpiresMs`, then increments the tick. The tick is part of the panel's `merge` input (`SportsViewModel.kt:
301-307`) and is passed to the cards. These all call `ClipOffer.offered(…, nowMs + skew)` with `nowMs` read at build
time:
- the card chip (`HubCard.kt:209`; its `nowMs` `remember` key gains the tick);
- `HubSlots`;
- `onSlot(WATCH_RECAP)` (`SportsViewModel.kt:683`, which plays only an offered id);
- the Scoring sheet rows (the `remember` key at `GoalsSheet.kt:118` gains the tick).

The NFL mapper never sets `GameDetail.recapId`, so the NHL `recapPosted` logic is not silently reused with an expired id.
The NFL highlight is `clips.firstOrNull { it.kind == RECAP && offered }`, else the board's `media.recapId` while
offered. The master request remains the backstop for a wrong clock.

### 4.4 Clip-to-scoring-play link

Deferred (§15). In v1 no scoring row carries a clip, and every offered PLAY clip is under More videos. The record's
`plays[]` is not declared in the DTO.

### 4.5 Stream choice, rendition cap, captions, images

- HLS only (`links.source.HLS.href`). Never the MP4 (`links.source.href` and friends: a 1080p59.94 10.7 Mbit/s mezzanine,
  ≈ 100 MB per 74 s). Never `HLS.cmaf`, `HLS.9x16`, `HLS.cmb`, `HLS.shield` or `links.mobile` (a login host that answers
  401).
- Ladder (V): 360p, 480p, 720p30 at 29.97 fps, and 720p at 59.94 fps, two bitrates each. Adaptive selection decides.
- **Rendition cap** (lead ruling 4; arch m11): a constant pair in one file,
  `player/src/main/kotlin/app/thebox/player/controller/ClipRendition.kt`:
  `internal object ClipRendition { const val LOW_RAM_MAX_HEIGHT = 720; const val LOW_RAM_MAX_FPS = 30 }`.
  - Normal devices have no cap, which is today's behaviour for NHL clips.
  - In `PlaybackControllerImpl.applySwitches` (`:1188-1230`), a clip cycle on `engine.isLowRam` sets
    `setMaxVideoSize(1280, LOW_RAM_MAX_HEIGHT)` and `setMaxVideoFrameRate(LOW_RAM_MAX_FPS)`.
  - Every other cycle explicitly clears both (`clearVideoSizeConstraints()`, `setMaxVideoFrameRate(Int.MAX_VALUE)`),
    because the parameters are rebuilt from the player's current value (`:1215`) and a clip's cap would otherwise
    persist into the resumed live channel.
  - The owner's stutter of NHL replays on a Sony Bravia 4K Google TV is **undiagnosed** (no AFR for clips by design,
    hub §6.5). This file is where a cap for all devices would go if the diagnosis points at 60 fps content (owner
    question Q3).
- Captions: the master lists a WebVTT `TYPE=SUBTITLES, LANGUAGE="en", DEFAULT=YES` track. The existing
  `CLIP_HLS_MASTER_CARRIES_CAPTIONS` path selects it by the player's subtitles setting. Nothing is side-loaded
  (`captionsUrl = null`). The muxed timed-ID3 track is ignored.
- Images (Coil; memory and disk key `espn-clip:{id}:{w}x{h}`; size = the drawn size):
  - the panel's highlight poster is the clip's `thumbnail` (1920 × 1080, ≈ 292 KB) decoded at the media area's size.
    Low-end devices never use the 1920 thumbnail: they get art with the "Game highlights (1:14)" caption and no disc
    (`RGB_565`, no hardware bitmaps, the NHL rule A-N3);
  - sheet thumbnails are the same URL at 128 × 72 (none on low-end);
  - headshots (Matchup only) at 72 px.

### 4.6 Playback path

Exactly the NHL route: `ClipSession.play(queue, i)` → `resolver.stream(ref, fresh)` → `controller.playClip(ClipTune(url,
HLS, null, title, startMs))` → a `Spec.Clip` cycle on the one `ExoPlayer` and the root `SurfaceView`.
- The broker key is `clip:cmp-espn.media.dssott.com` (from the URL's host) with `HUB_CLIP_MAX_CONNECTIONS` (4), never a
  provider's key.
- Segment calls go through the session's transport and lease, so Back or a zap cancels them.
- No AFR, no tunnelling, no time-shift ring, nothing learned.
- The resume after the clip is `ClipSession`'s existing `ResumePoint`.

Changes in `ClipSession`:
- "Up next" applies to `ClipKind.GOAL` **and** `PLAY` (today GOAL only, `ClipSession.kt:370`);
- `ClipResult.Expired` → `Failure.EXPIRED`. On an auto-advance it skips to the next entry instead (arch m12);
- `fresh = true` on the expired re-resolve and on Try again.

### 4.7 Never logged, never stored

- Never logged: any clip media URL (HLS, MP4, segment, captions) beyond `Redact.host`, which is already the controller's
  rule (`PlaybackControllerImpl.kt:799`); summary or record bodies; article text; athlete names. ESPN log lines carry a
  path label and the result kind only (the existing ESPN rule: "never a query, a body or an id"). Clip paths are **not**
  registered with `Redact.registerTransient` (arch M5; lead ruling 3), because that LRU holds real secrets.
- Never stored on disk: the catalog, `GameDetail`, any URL. Coil's disk cache holds the images under the keys above.
- Requests carry the IP, the app's existing `@ApiClient` UA, and an event id or a clip id; nothing about the user,
  playlist or device. No telemetry.

---

## 5. Data

### 5.1 What is requested when

| Request | When | Size | Owner |
|---|---|---|---|
| `scoreboard` (week / `dates=`) | as today (`DayPollPolicy`) | 13–23 KB gz | `ScoreRepository` (unchanged; the DTO gains `highlights`) |
| `scoreboard/{id}` | as today (follow) | 3 KB gz | follow machine (unchanged) |
| **`summary?event={id}`** | the shown game only, by §5.2 | pre 20–24 KB gz / 128–148 KB; final 46–58 KB gz / 495–641 KB | `GameDetailRepositoryImpl` via `EspnDetailSource` |
| `content.core …/video/clips/{id}` | only on a catalog miss in `stream()`, or on `fresh = true` | 2 KB gz | `EspnClipResolver` |
| HLS master, media, segments | on play | ≈ 0.7 MB/s at 720p60 | player |
| images | on draw | 48–292 KB | Coil |

The board DTO (`EspnCompetitionDto`) gains `highlights: List<EspnVideoDto>? = null` (the summary's video DTO, ≤ 1 per
game). `EspnMapper` maps an offered highlight, evaluated at the board's `serverDateMs`, to `GameSnapshot.media =
GameMedia(recapId = id, condensedId = null, goalClips = 0, recapExpiresMs = effective expiry)`. The card chip and slot
1 then work from the board before the summary arrives. Playing it before the summary is cached costs one record lookup.

### 5.2 Refresh cadence (`DetailPollPolicy` with a per-league `DetailCadence`)

`DetailPollPolicy` takes a `DetailCadence` (pure, `:core`) instead of NHL constants. `DetailCadence.NHL` reproduces
today's numbers exactly, so its tests are unchanged in substance. `DetailCadence.NFL`:

| Phase | NFL rule | Why |
|---|---|---|
| SCHEDULED, PRE | at once, then every 30 min (`HUB_NFL_PRE_MS`) | injuries and leaders change daily; 20 KB gz each |
| LIVE, INTERMISSION (both kinds), delayed | every **60 s** (`HUB_NFL_LIVE_MS`), not bucket-aligned (ESPN has no CDN bucket; max-age 3–10 s). A plain loop: no nudge on a board score change and no halftime hold (lead ruling 3). | the board already refreshes the score, clock and situation at 30 s; team totals move per drive; 60 s halves the NHL rate on a body 12× larger |
| Phase change (arch m5) | when `effectivePhase != detail.fetchedPhase`, the fetch is due at `max(now, lastOk + HUB_NFL_REFETCH_FLOOR_MS (15 s))` | the final totals and the recap headline must not wait 5 min after LIVE → FINAL |
| FINAL | every 5 min (`HUB_DETAIL_IDLE_MS`) while no offered highlight (detail or board) and the game ended < 2 h ago (`HUB_NFL_HIGHLIGHT_WAIT_MS`). Also every 5 min while the detail lists any PLAY clip and the game ended < 30 min ago (`HUB_NFL_PLAYS_WAIT_MS`, arch m6). Otherwise never (expiry is local, §4.3). | highlight 14–20 min after END GAME (V, two games); one MNF single-play clip came 7 min after the highlight |
| POSTPONED | once (as the NHL) | |

- **Back-off always wins**: `failures > 0` returns the back-off instant before any other rule, including the phase
  change. Back-off is `SportsTiming.backoffAt` with `Retry-After` as a floor. 429 / REFUSED wait at least
  `BACKOFF_MAX_MS` (5 min), as the NHL. The gate is unchanged: nothing with Show Sports off, the device asleep, the app
  in the background, offline, or a clip playing.
- "Ended" for a game first seen final: start + `HUB_NFL_GAME_LENGTH_MS` (3 h 15 min) instead of the NHL's 2 h 30 min
  (`GAME_LENGTH_MS` becomes `cadence.gameLengthMs`).
- Staleness: 2 × the phase's interval, as the NHL. Live shows "Stats from 8:12 PM" after 2 min.
- The 600 ms rest (dwell) applies to NFL games as well (`applyShow`, `GameDetailRepositoryImpl.kt:307`).
- Cancellation: a move to another game cancels the in-flight call (`gen`, `cancelInFlight`). The OkHttp call is
  cancelled, so the 600 KB stream stops at once and its partial DTO is dropped.
- Keys (arch m8): every per-game map is keyed by **`"${league}:${id}"`**: `cache`, `endedAt` (`:178`), `seenUnfinished`
  (`:185`), the trim's `keep` (`:274`), `noteEnd` (`:478`) and `recapPosted`. Today they use `id.id`, so an NHL and an
  ESPN id could collide.
- Offline: `Failed(OFFLINE)` with nothing cached, `Ready(failing)` with a cache; no requests.

### 5.3 Streaming parse

- **HTTP** (`EspnHttp`, §6.5). Commit 0 extracts `EspnScoreSource`'s private `call`, `classify`, `CappedStream`, `isHtml`
  and `logTransition` into `internal class EspnHttp`, with the decoded-body cap as a **per-call parameter** (today
  `classify` hard-codes `ESPN_MAX_BODY_BYTES`, `EspnScoreSource.kt:554-555`; arch m9). There is no behaviour change, and
  `EspnScoreSourceTest` stays green. P2 then adds the WAF rule for every ESPN call (board, summary, records): **403, and
  202 with `x-amzn-waf-action`, → `HTTP_429`**, so the refused floor applies (arch M6). The request sets no
  `Accept-Encoding`, so OkHttp un-gzips transparently and `contentLength()` is −1. The "declared length over the cap"
  check is kept, but it is not counted as protection; `CappedStream` counting decoded bytes is.
- **Parser**: the one `EspnScoreSource` already uses, kotlinx.serialization `Json { ignoreUnknownKeys = true }`
  `decodeFromStream` into a typed DTO through `CappedStream`, drained to the end so the connection is reusable and the
  wire meter counts the whole body.
- **DTO** `core/.../sports/hub/EspnSummaryDto.kt` (commit 0, frozen). It is typed down to the leaves the mapper reads,
  with no `JsonElement` for any list; `JsonElement?` is used only for scalars whose type ESPN varies (`id`, `value`).
  Every section that names a team declares `team.id`. Kept top-level keys:
  - `header`: competitions[0] with `status`, and competitors {`id`, `homeAway`, `record[]`, `team {id, abbreviation}`};
  - `boxscore`: `teams[]` {`team.id`, `statistics[] {name, displayValue}`}; `players[]` {`team.id`, `statistics[]
    {name, keys, labels, athletes[] {athlete {displayName}, stats[]}}`}, of which only passing, rushing and receiving are
    mapped;
  - `scoringPlays`: {`id`, `type {id, abbreviation, text}`, `text`, `period.number`, `clock.displayValue`,
    `awayScore`, `homeScore`, `team.id`};
  - `leaders`: {`team.id`, `leaders[] {name, leaders[0] {displayValue, value, athlete {shortName, headshot.href}}}`};
  - `gameInfo.venue.fullName`; `article.headline`;
  - `videos`: `EspnVideoDto` {`id`, `headline`, `duration`, `originalPublishDate`, `timeRestrictions`,
    `deviceRestrictions`, `thumbnail`, `tracking.coverageType`, `links.source.HLS.href`};
  - `lastFiveGames`: {`team.id`, `events[] {gameDate, atVs, opponent.abbreviation, gameResult, score}`};
  - `injuries`: {`team.id`, `injuries[] {status, athlete {displayName, position.abbreviation}, details.type}`}.
  `EspnClipRecordDto` is `{videos: List<EspnVideoDto>}`.
- **Skipped without materialising** (not declared, so the lexer skips them): `drives`, `news`, `winprobability`,
  `standings`, `pickcenter`, `odds`, `againstTheSpread`, `predictor`, `ticketsInfo`, `meta`, `format`,
  `wallclockAvailable`, top-level `broadcasts` and `header.broadcasts`. Inside the kept sections: every other `links`,
  `logos`, `ad`, the rest of `tracking`, `gameInfo.weather` / `officials`, and every `article` key but `headline`.
  kotlinx 1.11's `skipElement` allocates a `String` for every string token it skips, so skipping `drives` (336–414 KB)
  churns ≈ 1–2 MB per parse. The live set stays small (§5.4). `EspnSummaryDecodeTest` records the bytes allocated per
  decode (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) as the yardstick the device check compares
  against (arch m10).
- **Caps**: decoded bytes `ESPN_SUMMARY_MAX_BYTES` 2 MiB (the largest sample is 641 KB; an overtime MNF game with 12
  clips is estimated under 800 KB). Over the cap, or a spine of the wrong type, is `BAD_BODY`. After decode the mapper
  truncates: `scoringPlays` ≤ 60, `videos` ≤ 24, box athletes ≤ 8 per category, injuries ≤ 6 shown per team (all counted
  for "+n more").
- **Fallback if the device check fails** (parse > 400 ms on a Fire TV Stick class device, or visible GC pauses): a
  hand-written skip over `okio.BufferedSource` for the top-level object, with the kept sections still decoded by
  kotlinx. No new dependency. It is not built unless needed.

### 5.4 Memory budget (low-RAM Fire TV)

| Item | Budget |
|---|---|
| One parse in flight (only the shown game; never two, §5.5) | ≤ 256 KB live set: OkHttp / gzip buffers, the decoder's char buffer, the typed DTO (box ≈ 30 KB, plays ≈ 20 KB, videos ≈ 25 KB); allocation churn 1–2 MB per parse (§5.3) |
| One NFL `GameDetail` retained | ≤ 16 KB (stat rows, ≤ 60 plays, ≤ 24 clip metadata, leaders, ≤ 2 × 12 injuries, 10 recent games, the headline); × 24 LRU ≈ 0.4 MB worst |
| Box score | not in `GameDetail`: one slot in the repository loop for the last parsed game (≤ 32 KB) |
| Clip catalog | ≤ 64 entries × ≈ 1 KB |
| Images | Coil's existing caches (1 MB memory on low-end); one panel picture, ≤ 10 headshots in the Matchup sheet |
| `onTrimMemory` ≥ RUNNING_LOW | detail LRU, box slot, and the catalog (except the playing clip's entry) cleared |

### 5.5 Box score through the loop (arch M4)

- `DetailAnswer` (moved to `GameDetailRepositoryImpl.kt` in commit 0) gains `box: Fetch.Ok<BoxScore>? = null`.
  `EspnDetailSource.detailAnswer` maps the box from the same DTO, with the DTO-to-model mapping on
  `Dispatchers.Default`, never on the view model's Main continuation. The repository keeps the last one in a
  loop-confined slot, keyed `league:id`.
- `boxScore(id)` for a league with a detail source posts `Input.Box(id, deferred)` to the loop:
  - the slot answers when it matches `id` and is no older than the phase's interval (a final: any age);
  - otherwise the loop sets `force` for the shown game and completes the deferred from the next `Fetched` for that id
    (or its failure). The loop's own poll and the sheet share that one parse, so there are never two;
  - a closed gate, or an id that is not shown, gets `Failed(HTTP_OTHER)`, and the sheet shows Retry.
- The NHL keeps its own `boxScore` request, through the same input. The `League.NHL` guard (`:129`) becomes "a league
  with a detail source".
- `EspnDetailSource.boxScore` (the `GameDetailSource` member) is one summary read mapped to the box, kept for the
  interface. The repository does not call it.

### 5.6 Request budget, worst realistic session, against the NHL hub

Session: one hour on the Sports page with a live game in the panel, the Scoring sheet opened three times, three clips
played, and ten cards browsed (each rests ≥ 600 ms).

| | NHL hub (today) | NFL (this design) |
|---|---|---|
| Live detail | 120 × (right-rail + landing) ≈ 120 × 4 KB gz = **0.5 MB/h**; ≈ 3 MB/h decoded | 60 × 50 KB gz = **3.0 MB/h**; ≈ 36 MB/h decoded and mostly skipped |
| Browsing 10 cards | 10 × 4 KB | 10 × 20–58 KB ≈ 0.4 MB (once per game: cache) |
| Clip metadata | ≤ 2 lookups per game shown (3 KB each) | 0 (catalog); 2 KB per catalog miss or expired-link retry |
| Video | per clip | 3 × 25 s × ≈ 0.7 MB/s ≈ 55 MB (dominant, as for the NHL) |
| Pre-game hour | 12 × 4 KB | 2 × 22 KB |
| Final, waiting for video | 12 × 4 KB for ≤ 3 h | 12 × 50 KB for ≤ 2 h, only while that game is shown |

Requests per hour, worst: NHL ≈ 240 small; NFL ≈ 60 summaries plus a few records. ESPN answered ~70 probe requests in
20 min without a 429 (V). Behaviour under hours of this polling is NV (§12).

---

## 6. Contracts (commit 0; `:core` unless marked; append-only, defaulted)

### 6.1 `core/.../sports/hub/HubContracts.kt`

```kotlin
// GameDetail: appended, defaulted (the NHL leaves them at their defaults). KDoc header "game detail (NHL, NFL)".
// NFL use of the existing fields: awayScore / homeScore null (the board's score is the headline, arch m7); recapId and
// condensedId null (the highlight is `clips`, arch M7); goals, stars, goalies, series, season empty or null; leaders =
// the NFL categories below; venue = gameInfo.venue.fullName; periods empty (the box uses the board's); broadcasts empty.
data class GameDetail(
    /* …existing fields unchanged… */
    /** (NFL) `scoringPlays[]`, game order. */
    val plays: List<ScoringPlay> = emptyList(),
    /** (NFL) Pre-game rows RECORD, LAST_FIVE, POINTS_PG, POINTS_ALLOWED_PG, YARDS_PG, YARDS_ALLOWED_PG, in order. */
    val seasonStats: List<StatRow> = emptyList(),
    /** (NFL) RECAP and PLAY clips offered at parse time (§4.3); no URL (URLs stay in `:data`'s catalog). */
    val clips: List<GameClip> = emptyList(),
    /** (NFL) `article.headline` of a Recap article. */
    val recapHeadline: String? = null,
    /** (NFL, pre-game) `lastFiveGames`, both teams, oldest first. */
    val recent: List<RecentGame> = emptyList(),
    /** (NFL, pre-game) Out, Doubtful, Questionable; IR excluded. */
    val injuries: List<InjuryLine> = emptyList(),
    /** (NFL) serverDate − wall clock at receive, clamped to ±HUB_CLOCK_SKEW_MAX_MS; 0 without a Date header. */
    val clockSkewMs: Long = 0,
) {
    /** The league-neutral scoring list. */
    val scoring: List<ScoringEvent> get() = if (goals.isNotEmpty()) goals else plays
}

enum class StatKind {
    SOG, POWER_PLAY, HITS, BLOCKED, FACEOFF_PCT, PIM, GIVEAWAYS, TAKEAWAYS,
    // (NFL) game — only the kinds a row draws (§2.2)
    TOTAL_YARDS, PASS_YARDS, RUSH_YARDS, FIRST_DOWNS, THIRD_DOWN, TURNOVERS, POSSESSION,
    // (NFL) season
    RECORD, LAST_FIVE, POINTS_PG, POINTS_ALLOWED_PG, YARDS_PG, YARDS_ALLOWED_PG,
}

sealed interface StatValue {
    data class Count(val n: Int) : StatValue
    /** [dash]: "4-12" (NFL efficiency) instead of "1/3". */
    data class Fraction(val made: Int, val of: Int, val dash: Boolean = false) : StatValue
    data class Pct(val value: Double) : StatValue
    /** (NFL) "22.6": [places] decimals. */
    data class Decimal(val value: Double, val places: Int = 1) : StatValue
    /** (NFL) Time of possession, "31:31". */
    data class Clock(val seconds: Int) : StatValue
    /** (NFL) Records and forms ("6-4", "4-5-1", "3-2"): never compared. */
    data class Text(val text: String) : StatValue
}

/** Which side a row highlights. Pure; implemented in commit 0; replaces the UI's PIM / GIVEAWAYS special case. */
object StatRules {
    /** null for Text, Clock, ties; THIRD_DOWN compares made/of (of = 0 → null), then made. */
    fun better(row: StatRow): Side?
    /** PIM, GIVEAWAYS, TURNOVERS, POINTS_ALLOWED_PG, YARDS_ALLOWED_PG. */
    fun lowerIsBetter(kind: StatKind): Boolean
}

/** The facts every scoring row shares (NHL goals, NFL scoring plays). */
interface ScoringEvent {
    val period: PeriodLabel
    val clock: String
    val side: Side
    val away: Int
    val home: Int
}
// GoalItem gains `: ScoringEvent`: `override` on period, side, away, home; `override val clock get() = timeInPeriod`.
// No constructor change, so every NHL call site and test compiles.

/** From `type`: abbreviation "FG" → FG; "SF" → SAFETY; "TD" → DEF_TD when `type.text` contains "Interception Return" or
 *  "Fumble Return" (id 36 seen), else TD; anything else → OTHER. No two-point member: never seen as its own play. */
enum class PlayScore { TD, FG, SAFETY, DEF_TD, OTHER }

/** One NFL scoring play. [id]: ESPN's play id, a string ("4018730781583"); the row key. */
data class ScoringPlay(
    val id: String,
    override val period: PeriodLabel,
    override val clock: String,
    override val side: Side,
    val kind: PlayScore,
    /** ESPN's text, trimmed ("Tee Higgins 31 Yd pass from Joe Burrow (Evan McPherson Kick)"). */
    val text: String,
    override val away: Int,
    override val home: Int,
) : ScoringEvent

/** Clip metadata carried by the detail. [expiresMs] is the effective expiry (§4.3). No media URL. */
data class GameClip(
    val videoId: Long,
    val kind: ClipKind,
    val title: String,
    val durationMs: Long?,
    val publishedMs: Long?,
    val embargoMs: Long?,
    val expiresMs: Long,
    /** `thumbnail` (1920 × 1080). */
    val thumbUrl: String?,
)

enum class GameResult { W, L, T }
/** [score]: ESPN's, winner first ("20-17"). */
data class RecentGame(val side: Side, val atOpponent: Boolean, val opponent: String, val result: GameResult, val score: String, val dateMs: Long?)

enum class InjuryStatus { OUT, DOUBTFUL, QUESTIONABLE }
data class InjuryLine(val side: Side, val name: String, val position: String?, val status: InjuryStatus, val detail: String?)

// Leader: appended `val line: String? = null` (ESPN's displayValue: "248/372, 2,623 YDS, 19 TD, 10 INT", "5.5").
// `value` stays Int (yards); fractional values (sacks) are drawn only from `line` (arch m4).
// NFL LeaderPair categories: "passingYards", "rushingYards", "receivingYards", "sacks", "totalTackles".

// TeamBox: appended `val tables: List<BoxTable> = emptyList()` (NFL); the NHL keeps skaters / goalies.
enum class BoxCategory { PASSING, RUSHING, RECEIVING }
data class BoxTable(val category: BoxCategory, val columns: List<String>, val lines: List<BoxLine>)
data class BoxLine(val name: String, val values: List<String>)

enum class ClipKind { RECAP, CONDENSED, GOAL, /** (NFL) one play, or any single clip of the game. */ PLAY }

sealed interface ClipResult<out T> {
    /* …existing… */
    /** (NFL) Past its expiry margin, or embargoed: never played. */
    data object Expired : ClipResult<Nothing>
}

interface ClipResolver {
    suspend fun info(ref: ClipRef): ClipResult<ClipInfo>
    /** [fresh]: bypass any cache (the expired-link re-resolve and Try again). */
    suspend fun stream(ref: ClipRef, fresh: Boolean = false): ClipResult<Pair<ClipInfo, ClipStream>>
}

/** §4.3's rule, shared by `:core`, `:data` and `:ui`. Pure; implemented in commit 0. */
object ClipOffer {
    fun offered(embargoMs: Long?, expiresMs: Long, nowMs: Long): Boolean
    /** The next instant after [nowMs] at which [offered] changes (the embargo, or expiresMs − margin); null for none. */
    fun changesAt(embargoMs: Long?, expiresMs: Long, nowMs: Long): Long?
}
```

`ClipInfo` is unchanged. `ClipStream`'s KDoc is updated (§4.2). `DetailState.BoardOnly`'s KDoc becomes "CHL: the panel
uses the board's `BoardExtras` only".

### 6.2 `core/.../sports/SportsContracts.kt`

- `GameMedia`: appended `val recapExpiresMs: Long? = null` (the NFL board highlight's effective expiry; the NHL leaves
  it null).
- `LeagueDetail.Nfl`: appended `val delayed: Boolean = false` (§2.5).

### 6.3 `core/.../sports/hub/DetailPollPolicy.kt`

```kotlin
data class DetailCadence(
    val preMs: Long,
    val liveMs: Long,
    val liveAligned: Boolean,
    /** FINAL: re-read every HUB_DETAIL_IDLE_MS while no recap / highlight and ended < this. */
    val finalWaitMs: Long,
    /** FINAL: also while the detail lists PLAY clips and ended < this; null = never (NHL). */
    val playsWaitMs: Long?,
    val gameLengthMs: Long,
    /** A phase change (effective vs fetched) is due at once, no earlier than lastOk + this; null = off (NHL). */
    val phaseChangeFloorMs: Long?,
) {
    companion object {
        val NHL = DetailCadence(HUB_DETAIL_IDLE_MS, DAY_POLL_LIVE_MS, true, HUB_RECAP_WAIT_MS, null, 9_000_000, null)
        val NFL = DetailCadence(HUB_NFL_PRE_MS, HUB_NFL_LIVE_MS, false, HUB_NFL_HIGHLIGHT_WAIT_MS, HUB_NFL_PLAYS_WAIT_MS,
            HUB_NFL_GAME_LENGTH_MS, HUB_NFL_REFETCH_FLOOR_MS)
    }
}
/** FINAL facts. [recapPosted]: NHL recap id / NFL offered highlight; [playClips]: the detail lists a PLAY clip. */
data class FinalFacts(val recapPosted: Boolean, val playClips: Boolean, val endedAgoMs: Long?)

// intervalMs(cadence, phase, final), isStale(cadence, fetchedAtMono, phase, final, nowMono),
// staleAt(cadence, fetchedAtMono, phase, final), and
// nextAt(cadence, gate, lastOk, phase, fetchedPhase, final, failures, lastFailMono, retryAfterS, refused, pollPhaseMs,
//        jitter, nowMono). Back-off is checked first, as today.
```

Commit 0 implements the new signatures with NHL behaviour only: `DetailCadence.NFL` is declared, and P1 implements its
rules. The existing `DetailPollPolicyTest` and repository calls pass `DetailCadence.NHL`.

### 6.4 `SportsConstants`

See §10.

### 6.5 `:data` seams (stubs in commit 0)

- `internal class EspnHttp` (`data/.../sports/EspnHttp.kt`): the extraction (§5.3), with a cap parameter.
- `EspnDetailSource : GameDetailSource`, and `internal suspend fun detailAnswer(id, phaseHint): DetailAnswer`. The mapper
  is a constructor parameter (`(EspnSummaryDto, GameId, Long?) -> GameDetail?`, defaulting to `EspnDetailMapper::detail`),
  so P2 tests the transport without P1's mapper.
- `DetailAnswer` moves from `NhlDetailSource` to `GameDetailRepositoryImpl.kt`, with `box` appended (§5.5).
  `DetailFetcher.of(nhl, espn)` routes by league.
- `EspnClipCatalog`, `EspnClipResolver : ClipResolver`, `ClipResolvers : ClipResolver` (the router), bound in
  `SportsModule` (`clipResolver(impl: ClipResolvers)`). The repository takes both sources.
- `EspnEndpoints`: appended `clipBase` (default `https://content.core.api.espn.com/v1/video/clips/`, override
  `{origin}/espn-core/v1/video/clips/`). The summary is `apiBase + "summary?event={id}"`.
- `core/.../sports/hub/EspnVideoMapper.kt` (pure, **implemented in commit 0**):
  `entry(dto: EspnVideoDto, atMs: Long?, httpOk: Boolean): VideoEntry?` with `VideoEntry(clip: GameClip, hlsUrl:
  String)`. It is the one mapping of a video object, used by `EspnDetailMapper` (detail clips), `EspnMapper` (board
  highlight), `EspnDetailSource` (catalog) and `EspnClipResolver` (records). `atMs == null` skips the time check.

### 6.6 `:ui`: one place chooses the league

`ui/.../sports/HubLeagueUi.kt` (commit 0; P3 may extend it, since every implementer is P3's):

```kotlin
internal interface HubLeagueUi {
    /** Header word, mandatory rows, extra rows; or the failure text in their place. */
    fun rows(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName): RowsSpec
    /** Lines, fitted with [measure]; plus any extra row a line gave up (the NFL's Timeouts). */
    fun summary(res: Resources, m: HubPanelModel, g: GameSnapshot, away: HubName, home: HubName, measure: Measure, factor: Float): SummarySpec
    fun media(res: Resources, m: HubPanelModel, g: GameSnapshot, clock24: Boolean, locale: Locale, lowEnd: Boolean): MediaSpec
    fun slots(c: HubSlots.Ctx): List<HubSlot>
    /** One entry per slot: the spoken reason of a disabled slot, else null (§2.3). */
    fun slotReasons(res: Resources, m: HubPanelModel, slots: List<HubSlot>): List<String?>
    /** The clip lookups the panel needs now (the NHL: today's `ensureClipInfos` body; the NFL: none). */
    fun clipRefs(game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>): List<ClipRef>
    fun scoringRows(res: Resources, game: GameSnapshot, detail: GameDetail?, infos: Map<Long, ClipInfo>, catchUp: List<ChannelCandidate>, offerNowMs: Long, videoOn: Boolean): List<ScoringRow>
    /** Whether this league has a detail source (Retry, the channel line on finals, DetailKind). */
    val detailed: Boolean
    /** NHL.com / ESPN wording (string resources chosen per league, never concatenated). */
    val source: HubSource
}
internal object HubLeagues { fun of(group: LeagueGroup): HubLeagueUi = when (group) { NHL -> NhlHubUi; NFL -> NflHubUi; CHL -> ChlHubUi } }
```

- `NhlHubUi.kt` holds today's NHL code, moved **verbatim** in commit 0 from `HubPanelContents` (rows, `summary()`,
  `media()`), `HubSlots.default`, `SportsViewModel.ensureClipInfos`, and `GoalsSheet.kt`'s `goalsSheetRows` /
  `goalQueue`. `ChlHubUi.kt` holds today's CHL branch. `NflHubUi.kt` holds, in commit 0, today's NFL board-only branch
  moved verbatim; P3 replaces it with this design. The goldens hash equal after the move.
- `HubPanelContents.build` gains `measure: Measure`. `HubPanel.kt:944-945` computes `rememberHubMeasure()` first and
  keys the `remember` on it; the fit tests pass the Paint measure.
- `SummaryLine` gains `oneLine: Boolean = false` (NFL lines `true`). `HubPanelPlan.plan` gains `extraFloor: Int =
  mediaFloor` (§2.6).
- `ScoringRow` (declared in `GoalsSheet.kt` in commit 0, which is renamed in place to the scoring sheet; the file name
  stays, to keep history): `Event(event: ScoringEvent, key, line1, line2, desc, icon: RowIcon)`, `Video(ref, label,
  durationMs, thumbUrl)`, `Header(text)`, `Info(text)`, `CatchUp(candidate, n)`. `NhlHubUi.scoringRows` builds today's
  goal rows into it with the same words (the NHL `Event` rows carry the goal's clip as today), so the NHL sheet's pixels
  do not change. `goalQueue` becomes `scoringQueue(rows, from)`: the GOAL or PLAY refs of the same section, from `from`
  on.
- `HubToast` appended `league: LeagueGroup = LeagueGroup.NHL`. `HubToastKind` appended `VIDEO_EXPIRED`,
  `PLAY_NO_VIDEO`. `ClipSession.Failure` appended `EXPIRED`.
- `SportsViewModel.videoOff` becomes `Set<LeagueGroup>` (per league), and `!ESPN_VIDEO_ENABLED` counts as NFL off.
- `SlotLabel` appended `HIGHLIGHTS, HIGHLIGHTS_SOON, NO_HIGHLIGHTS, FROM_START, SCORING, SCORING_N`. `SlotAction` is
  unchanged: WATCH_RECAP is ▶ Highlights, WATCH_FROM_START is ▶ From start, and GOALS opens the scoring sheet (its KDoc
  says so).
- `SportsText.intermissionPill` (`SportsText.kt:336-343`): football END_PERIOD reads "END Q1" / "END OT"
  (`sports_hub_pill_end_quarter`), and hockey keeps "%1$s INT". The LIVE branch reads "DELAYED · {period clock}" when
  `nfl.delayed`.

### 6.7 Every place that says or assumes "NHL only" today

| Where | Today | Change (owner) |
|---|---|---|
| `strings_sports_hub.xml` `sports_hub_more_soon` | NFL and CHL panels | NFL never; CHL reworded "Game stats for this league aren't available yet." (commit 0) |
| `HubPanel.kt` `HubPanelContents.build` `when (group)`: rows, `summary()`, `media()` (`nhl` flags), `channelLine` `nhlFinal`, `failureText` (NHL.com strings) | NHL branches | through `HubLeagues.of(group)` (commit 0 move); the channel line on finals for `detailed` leagues; ESPN failure texts (P3) |
| `HubPanel.kt` `HubSlots.Ctx.nhl` in `detailRetry`, `default()`; `slotGlyph`; `greyReason` | NHL only; glyph regardless of enabled; Channels only | `detailed`; slots from the league UI; no glyph when disabled; reasons for any slot (P3) |
| `HubPanel.kt` `statLabel`, `statRow` (PIM / GIVEAWAYS), `value`, `num`, `compare` | NHL kinds; exhaustive `when` on `StatValue` | arms for the new kinds and values (commit 0); `StatRules.better` (P3) |
| `HubSummary.winningGoal` | NHL | stays NHL (called only by `NhlHubUi`) |
| `SportsViewModel.ensureClipInfos` (`group != NHL` return) | NHL only | `ui.clipRefs(...)` (commit 0 move) |
| `SportsViewModel.buildPanel` `DetailKind` default; `:949-952` detail score overrides the board | NHL | `ui.detailed`; the NFL detail has no score, so the board's stays (P3) |
| `SportsViewModel.markVideoOff` / `videoOff`; `onSlot(WATCH_RECAP)` | global; any id | per league; only an offered id; the `offerTick` (P3) |
| `GameDetailRepositoryImpl`: `League.NHL` at `boxScore` (`:129`), the `applyShow` dwell (`:307`), `step` BoardOnly (`:383`) and `nextWake` (`:453`); `GAME_LENGTH_MS`; the `id.id` keys; `recapPosted` | NHL only | league router; cadence; `league:id` keys; the NFL highlight rule; the Box input (P2) |
| `DetailPollPolicy` | NHL constants | `DetailCadence` (commit 0 signature, P1 NFL rules) |
| `NhlDetailSource.DetailAnswer`, `DetailFetcher.of(source)` | NHL | moved / routed (commit 0) |
| `SportsModule` `clipResolver(NhlClipResolver)`, `gameDetailSource(NhlDetailSource)` | NHL | `ClipResolvers`; the repository takes both sources (commit 0) |
| `GoalsSheet.kt` `GoalsRow.Goal(GoalItem)`, `goalsSheetRows`, `goalQueue` (`ClipKind.GOAL`), the title "Goals ·", `sports_hub_goals_loading` / `_failed` (NHL.com) | NHL | moved to `NhlHubUi` (commit 0); `ScoringRow`, per-league titles and texts (P3) |
| `ClipPlayerLayer.clipSubLine` (`GoalItem` list, exhaustive `when (ClipKind)`) | NHL | PLAY arm (commit 0 stub); `ScoringEvent` list and NFL words (P3) |
| `ClipSession.kt:370` "Up next" only for GOAL; `start()`'s `when` on `ClipResult` | NHL | GOAL and PLAY; `Expired` arm (commit 0 stub); skip-expired and `fresh` (P3) |
| `NhlClipResolver.relay` `when` on `ClipResult`; `stream(ref)` | NHL | `Expired` arm; `stream(ref, fresh)` (commit 0) |
| `BoxScoreSheet.kt` skaters / goalies only | NHL | draws `tables` and the board line score when present (P3) |
| `MatchupSheet.kt` NHL leaders and goalies | NHL | sections from the league UI (P3) |
| `HubCard.kt:209` chip | any `media.recapId` | the NFL highlight only while offered; `nowMs` keyed on the tick (P3) |
| `SportsText.intermissionPill` | "Q1 INT" for football | "END Q1"; "DELAYED" (P3) |
| Video strings ("The NHL video service…", "NHL videos aren't available…", "The NHL removed this video") and `sports_hub_caption_videos_off` | NHL | ESPN twins (commit 0) |
| `docs/decisions/2026-11-24-sports-hub.md` "NFL and CHL have no video and no box score" | | amended by the new decision note (lead) |

### 6.8 Strings frozen in commit 0 (`strings_sports_hub.xml`)

All user-visible text in §2–§4 above, as written there, in these groups:
- slots;
- tags and captions;
- the 13 stat labels;
- summary labels and the situation pieces ("%1$s ball", "%1$s at %2$s %3$d", "%1$s at midfield", "Red zone",
  "Timeouts %1$d–%2$d", "Kickoff · %1$s", "After the score", the delay line);
- the pills "END %1$s" and "DELAYED · %1$s";
- leader pieces;
- the scoring sheet title, type words, lead and tie forms, "More videos", "Game highlights" and the sheet states;
- the clip sub-lines;
- the ESPN video toasts and the `PLAY_NO_VIDEO` toast;
- the ESPN panel failure texts;
- the box and matchup headers, statuses and "+%1$d more";
- the §3.7 description strings and the position map;
- the two generalised slot descriptions;
- the CHL `sports_hub_more_soon` rewording.

P3 adds no string on its own. A missing string goes through the lead.

---

## 7. Channel matching, Follow, tuning

Unchanged. The matcher, its ranking, the Channels sheet, Watch, Watch from start, Follow / Following ✓ and the follow
machine are not touched. No new code path tunes a channel: clips play on the clip cycle, and `ClipSession` resumes what
was playing (the NHL path, §4.6). The summary, records and images are side requests. The NFL board already provides
`broadcasts` for the matcher (hub §7.2), and the panel caption reads the same parsed list. The summary's
`header.broadcasts` is not read.

---

## 8. Testing

### 8.1 Fixtures (`core/src/testFixtures/resources/sports/nfl/hub/`, so `:core` and `:data` tests share them)

The reducer is committed as `tools/espn/reduce_summary.py` (commit 0, arch m14). It reads local samples only, and no
sample is committed. A committed fixture is **< 40 KB** and never a full summary. The reducer:
- keeps only the DTO's top-level keys (§5.3), plus a stub `drives` of 3 plays and a stub `news` / `winprobability` /
  `pickcenter` of one item each, so the skip path runs over real shapes;
- drops every `links`, `logos`, `ad`, `cerebroId`, `dataSourceIdentifier`, `contributingPartner`, `pccId`, `uid`, `guid`,
  `tracking.trackingId` / `trackingName`, `article` key except `headline`, `officials` and ticket info;
- keeps 2 athletes per box category and 3 injuries per team;
- rewrites every media URL to `https://clips.invalid/{clipId}/playlist.m3u8`, every image to `https://img.invalid/{name}.jpg`
  and headshots to `https://img.invalid/h/{athleteId}.png`, so no real UUID paths are committed;
- keeps ids, scores, clocks, texts, `timeRestrictions`, `coverageType` and **the key order and team order as recorded**.

| Fixture | From | Covers | In |
|---|---|---|---|
| `summary-final-401873070.json` | MIA@BUF | final, 0 clips, recap headline, 25 stat rows with the duplicated `interceptions`, "J. Cook III" | commit 0 |
| `summary-final-401873076.json` | PIT@PHI | final, one "Final Game Highlight" (96 h window) | commit 0 |
| `summary-final-espn-401873078.json` | MNF CIN@WSH | final, 4 of the 12 clips (the highlight, two one-plays, the lights-out clip), `header` before `videos`, a tie record "4-5-1", a field goal winner | commit 0 |
| `summary-final-401873066.json` | IND@HOU | a safety ("SF") and the interception-return TD (type 36) | commit 0 |
| `summary-pre-401873079.json` | GB@LAR | pre-game: 8 averages, last five, season leaders with 5.5 sacks, injuries incl. IR, an Analysis clip expiring at kick-off (dropped), no `scoringPlays` / `drives` keys | commit 0 |
| `clip-record-50578630.json`, `clip-record-50579083.json` | `clips-mnf/` | the `{videos:[…]}` wrapper | commit 0 |
| `scoreboard-highlights-wk11.json` | `sb11.json`, two events | board `highlights[]`, `broadcasts` | commit 0 |
| `summary-live-401873079-{1,2,3}.json` | the lead's capture `nfl/live-sum-401873079-*.json` (lead ruling 10) | live team stats, scoring so far, in-game clips if any | P1 |

**If the capture is missing**, P1 writes the three live fixtures by hand from the final sample's shapes (the same keys and
types, with partial values and `status.type.state = "in"`). The live mapper tests are then marked **provisional** (named
`…_provisional`, listed in the integration checklist), and the first real live capture replaces the fixtures before the
decision note closes. Tests inject the wall clock; no test reads the system clock, since the recorded `expirationDate`s
are in the past by release time.

### 8.2 Unit tests (JVM)

| Test | Owner | Covers |
|---|---|---|
| `ClipOfferTest`, `EspnVideoMapperTest`, `StatRulesTest` | commit 0 | embargo, margin, the missing-expiry default, `changesAt`; kinds (RECAP, PLAY, others dropped), settop, https, `atMs` null; every kind's direction, the rate compare, Text / Clock never highlighted |
| `EspnDetailMapperTest` (`:core`) | P1 | each fixture in full; **sides by team id** (a copy of a fixture with `boxscore.teams` and `leaders` reversed maps identically); row order and values per phase; the duplicated stat read once; Last 5 with a tie; leaders (game vs season; `line` for 5.5 sacks); scoring text and kind (FG, TD, SF → SAFETY, type 36 → DEF_TD); the recap headline; injuries filter (IR out); clips offered at the injected clock; scores, `recapId` and `periods` left empty; absent sections never fail; the box (3 tables, ≤ 8 lines); live fixtures (provisional if hand-built) |
| `DetailPollPolicyTest` | P1 | `DetailCadence.NHL` cases unchanged; NFL pre 30 min, live and both breaks 60 s unaligned, a phase change due at once with the 15 s floor and back-off winning, final every 5 min until a highlight ≤ 2 h, the 30 min PLAY-clip wait after the highlight, game length 3 h 15 |
| `EspnMapperHubTest` | P1 | board highlight → `GameMedia(recapId, recapExpiresMs)` at the board's server date; expired, embargoed and non-settop dropped; absent `highlights`; `delayed` on a synthetic `STATUS_DELAYED` / `STATUS_RAIN_DELAY` |
| `EspnHttpTest` (`:data`, MockWebServer) | P2 | per-call cap; drain; 403 and 202 + `x-amzn-waf-action` → `HTTP_429` on board, summary and record calls; HTML 404 |
| `EspnSummaryDecodeTest` | P2 | decode of each fixture; a synthetic 1.5 MB `drives` generated in the test decodes; over 2 MiB → `BAD_BODY`; the same body with `header` first and last decodes equally; cancel mid-body drops the answer; allocated bytes per decode recorded and bounded (< 8 MB) |
| `EspnDetailSourceTest` | P2 | summary path and query; 404 JSON / HTML; 429 with `Retry-After`; 5xx; WAF; catalog filled with offered entries only; skew from `Date`; the box in the answer |
| `EspnClipResolverTest` | P2 | `info` hit and miss make no request; `stream` hit makes no request; a miss → one record lookup → catalog; `fresh`: 404 → `NotFound`, same URL → `Failed(HTTP_OTHER)`, new URL plays; 401 / 403 → `Failed(HTTP_429)`; expired / embargoed → `Expired`; https only; a log capture finds no URL beyond the host |
| `ClipResolversTest` | P2 | routing by league; `ESPN_VIDEO_ENABLED` false → `NotFound` |
| `GameDetailRepositoryImplTest` | P2 | NFL routed to the ESPN fetcher with the dwell; the same numeric id in NHL and NFL kept apart (`league:id`, every map); box via the loop: slot hit, miss → one fetch shared with the poll, a deferred completed on failure, closed gate; NHL tests unchanged |
| `PlaybackControllerClipTest` (`:player`) | P2 | a low-RAM clip cycle carries 720 / 30; the next live cycle clears both; a normal device sets none |
| `HubLeagueUiNflTest`, `ScoringRowsTest` | P3 | rows per phase; situation line words and fitting (Timeouts row when dropped); leaders fit order; recap one line; captions by age and video off; media choice incl. low-end; slots per §2.3, no glyph when disabled; spoken reasons; scoring rows, sections, queues, initial focus, type words |
| `SportsViewModelHubTest` | P3 | the `offerTick` rebuilds at `expiresMs − margin`; WATCH_RECAP never plays a non-offered id; videoOff per league; the kill switch; the NFL headline score stays the board's |
| `ClipSessionTest` | P3 | "Up next" for PLAY; `Expired` → `EXPIRED` toast; `fresh = true` on `CLIP_EXPIRED` and Try again; an expired next entry is skipped |
| `HubPanelFitTest`, `HubCardFitTest` | P3 | the §2.3 slot sets with two-digit counts at four sizes; the §2.6 table; the 99-character headline with Leaders drawn at Default; the §2.2 labels in 332 px |

### 8.3 Golden hashes (`:ui:sportsHashGate`, `ui/src/test/resources/golden/sports-hashes.txt`; P3 only)

New cases (Default unless marked):
- **NFL panel**: pre-game; pre-game Largest; live (situation line); live Largest (Timeouts row); halftime; end of
  quarter ("END Q1"); delayed; final with the highlight poster; final without a highlight (art + reason); final
  "Highlights soon"; final Largest; failed; video off; postponed.
- **Scoring sheet**: an ESPN game (plays, Game highlights, More videos); a non-ESPN game (highlight only); a final with
  no video (info row); live empty.
- **Other**: the Box score NFL tab; Matchup NFL; the clip bar's NFL sub-line.

The NHL and CHL panel and sheet goldens must hash equal after commit 0's move and again at the end of P3.

### 8.4 Fake panel (`tools/fakepanel`)

- `sports_nfl.py` (with `--sports --sports-nfl`; P2): `GET /apis/site/v2/sports/football/nfl/summary?event={id}` for
  every board game.
  - It serves pre-game (season rows, last five, leaders, injuries), live from the featured game's timeline (team stats
    that grow per window, `scoringPlays` from the timeline's scores, home-first and away-first sections as ESPN orders
    them), and final.
  - A padded `drives` of `sports_nfl_summary_pad_kb` (default 400) makes the skip path run on the device. The board gets
    `highlights[]`.
  - Faults: `summary_status` (including 403, and 202 with `x-amzn-waf-action`) and `summary_pad_kb` over the cap.
- `sports_video.py` (P2): ESPN routes `/espn-core/v1/video/clips/{id}` (the `{videos:[…]}` wrapper), an HLS master of the
  ESPN shape (4 variants from the existing lavfi media, one at 59.94 fps, a `SUBTITLES` rendition `DEFAULT=YES`), media
  playlists and TS segments. `sports_nfl_clips` mode (also `POST /admin/config`):
  - `espn`: a highlight plus 3 one-plays;
  - `single`: one highlight;
  - `none`;
  - `expired`: the highlight's `expirationDate` 1 h ago, which the app must not offer;
  - `expiring`: 2 min from now, inside the margin, not offered;
  - `gone`: listed, but the master answers 404 and the record 404 (the `CLIP_EXPIRED` → fresh path);
  - `slow_records`: records answer after 8 s.
- `fakepanel.py`: route table and `DEFAULTS` (commit 0 only). Tests `test_sports_nfl.py` and `test_sports_video.py` are
  extended (P2).
- The debug override `files/debug/sports_base` already serves the ESPN API paths. `EspnEndpoints.fromOverride` maps
  `clipBase` to `{origin}/espn-core/…`. HLS URLs come from the fake's JSON (its own origin, http allowed there only).

### 8.5 Emulator and device checklist (targeted states only; 1080p TV emulator, fake panel)

1. NFL This week, a pre-game card: panel rows, leaders, and the "venue · network" caption. OK, Right, Right → Matchup
   sheet. Back.
2. The featured live game: panel stats and the situation line, which changes with the board. Wait for a score in the
   timeline: the Scoring row appears within ~60 s.
3. Scoring sheet (`espn`): play a More videos row; "Up next" to the next; Back lands on the row.
4. Final (`single`): OK, OK plays Game highlights; captions on with Menu; Back → slot 1.
5. Final (`expired`): art with the reason, "No highlights" (or ▶ From start), no chip; the sheet's info row.
6. Final (`expiring`) and (`gone`): not offered; on `gone`, play from the catalog → master 404 → fresh lookup → the
   removed toast.
7. Fault `summary_status: 503`: the failure text, Retry in slot 1; `403`: the limiting text.
8. Largest text size: pre-game, live and final panels (§2.6 drops).
9. Fire TV Stick (device): parse time of the padded final summary < 400 ms, and no visible GC stall while browsing ten
   cards; a 720p59.94 clip without stalls; on low RAM, the 720p30 cap.
10. The owner's Sony Bravia (owner run, Q3): one ESPN 720p59.94 clip and one NHL clip; note whether either stutters.

---

## 9. Work packages

### 9.1 Commit 0 (lead, Opus): contracts, moves and stubs

After commit 0, each package compiles and tests alone. Checklist:

**`:core`**
- [ ] `HubContracts.kt` (§6.1): the `GameDetail` fields and `scoring`; 13 `StatKind` members; `Fraction.dash`,
  `Decimal`, `Clock`, `Text`; `StatRules` (implemented); `ScoringEvent`, with `GoalItem : ScoringEvent`; `PlayScore`;
  `ScoringPlay`; `GameClip`; `GameResult`, `RecentGame`; `InjuryStatus`, `InjuryLine`; `Leader.line`; `TeamBox.tables`,
  `BoxCategory`, `BoxTable`, `BoxLine`; `ClipKind.PLAY`; `ClipResult.Expired`; `ClipResolver.stream(ref, fresh)`; the
  `ClipStream` and `BoardOnly` KDocs; `ClipOffer` (implemented).
- [ ] `SportsContracts.kt`: `GameMedia.recapExpiresMs`, `LeagueDetail.Nfl.delayed`.
- [ ] `SportsConstants.kt`: every §10 constant.
- [ ] `DetailPollPolicy.kt`: `DetailCadence` (NHL and NFL values), `FinalFacts`, the new signatures with NHL behaviour;
  `DetailPollPolicyTest` passing `DetailCadence.NHL`, green.
- [ ] `EspnSummaryDto.kt` (new, frozen): §5.3, plus `EspnVideoDto` and `EspnClipRecordDto`.
- [ ] `EspnDto.kt`: `EspnCompetitionDto.highlights: List<EspnVideoDto>? = null`.
- [ ] `EspnVideoMapper.kt` (new, implemented) and `VideoEntry`.
- [ ] `EspnDetailMapper.kt` (new stub): `detail(dto, id, atMs): GameDetail?` and `box(dto): BoxScore?` (sides from the
  header's team ids), both returning null.
- [ ] `ClipOfferTest`, `EspnVideoMapperTest`, `StatRulesTest`.
- [ ] testFixtures: `HubFakes.FakeClipResolver.stream(ref, fresh)`; the §8.1 commit-0 fixtures.
- [ ] `tools/espn/reduce_summary.py`.

**`:data`**
- [ ] `EspnHttp.kt` (new): the extraction with a per-call cap; `EspnScoreSource.kt` uses it, with no behaviour change
  (`EspnScoreSourceTest` green). This is the only edit to `EspnScoreSource.kt` in this design.
- [ ] `NhlDetailSource.kt`: `DetailAnswer` moved out.
- [ ] `GameDetailRepositoryImpl.kt`: `DetailAnswer` (+ `box`) moved in; `DetailFetcher.of(nhl, espn)`; policy calls pass
  `DetailCadence.NHL`; behaviour unchanged (non-NHL still BoardOnly).
- [ ] `NhlClipResolver.kt`: `stream(ref, fresh)` (ignores `fresh`); the `Expired` arm in `relay`. These are the only
  edits to this file.
- [ ] Stubs: `EspnDetailSource` (`detailAnswer` → `Failed(HTTP_OTHER)`), `EspnClipCatalog` (empty), `EspnClipResolver`
  (`NotFound`), `ClipResolvers` (the router, real).
- [ ] `EspnEndpoints`: `clipBase` and its override mapping.
- [ ] `SportsModule.kt`: bind `ClipResolver` to `ClipResolvers`; the repository built with both sources.

**`:ui`**
- [ ] `HubLeagueUi.kt` (new): the interface and `HubLeagues.of`.
- [ ] `NhlHubUi.kt`, `ChlHubUi.kt`, `NflHubUi.kt` (new): today's branches moved verbatim from `HubPanel.kt`
  (`HubPanelContents` rows / summary / media, `HubSlots.default`), `SportsViewModel.ensureClipInfos`, and
  `GoalsSheet.kt`'s `goalsSheetRows` / `goalQueue`. **Acceptance: every sports golden hash equal.**
- [ ] `GoalsSheet.kt`: the `ScoringRow` sealed type declared; the moved functions removed.
- [ ] `HubPanel.kt`: the 6 `SlotLabel` members with `slotText` arms; `statLabel` arms for the 13 kinds; `value` / `num`
  arms for `Decimal`, `Clock`, `Text` and `Fraction.dash`; calls through `HubLeagues`.
- [ ] `SportsViewModel.kt`: `HubToastKind.VIDEO_EXPIRED`, `PLAY_NO_VIDEO`; `HubToast.league`; the `Failure.EXPIRED` →
  `VIDEO_EXPIRED` arm; `ensureClipInfos` via `ui.clipRefs`.
- [ ] `SportsScreen.kt`: toast arms for the two kinds.
- [ ] `ClipSession.kt`: `Failure.EXPIRED`; `ClipResult.Expired` → `fail(EXPIRED)` arm.
- [ ] `ClipPlayerLayer.kt`: the `ClipKind.PLAY` arm in `clipSubLine` (stub: null).
- [ ] `strings_sports_hub.xml`: every §6.8 string.

**Tools**
- [ ] `tools/fakepanel/fakepanel.py`: routes and `DEFAULTS` for the summary and `/espn-core/v1/video/clips/{id}`.

**Gate**
- [ ] `./gradlew :core:test :app:assembleDebug`, `lintAll`, `:ui:sportsHashGate` green.

### 9.2 Packages (strictly disjoint files after commit 0)

| WP | Owns (only these files) | Model | Done when |
|---|---|---|---|
| **P1 core** | `core/.../sports/hub/EspnDetailMapper.kt`, `DetailPollPolicy.kt` (NFL rules); `core/.../sports/EspnMapper.kt` (board highlight, `delayed`); live fixtures `core/src/testFixtures/resources/sports/nfl/hub/summary-live-*.json`; tests `EspnDetailMapperTest`, `DetailPollPolicyTest`, `EspnMapperHubTest` | Opus | its tests green on commit 0 + P1 alone; live tests real or marked provisional |
| **P2 data** | `data/.../sports/EspnHttp.kt` (WAF rule), `EspnDetailSource.kt`, `EspnClipCatalog.kt`, `EspnClipResolver.kt`, `ClipResolvers.kt`, `GameDetailRepositoryImpl.kt`; `player/.../controller/ClipRendition.kt` (new), `PlaybackControllerImpl.kt` (the `applySwitches` lines only); `tools/fakepanel/sports_nfl.py`, `sports_video.py`, `test_sports_nfl.py`, `test_sports_video.py`; tests `EspnHttpTest`, `EspnSummaryDecodeTest`, `EspnDetailSourceTest`, `EspnClipResolverTest`, `ClipResolversTest`, `GameDetailRepositoryImplTest`, `PlaybackControllerClipTest` | Opus | its tests green on commit 0 + P2 alone (mapper injected); fake ESPN summary and HLS serve |
| **P3 ui** | `ui/.../sports/HubPanel.kt`, `HubLeagueUi.kt`, `NhlHubUi.kt`, `NflHubUi.kt`, `ChlHubUi.kt`, `SportsViewModel.kt`, `SportsScreen.kt`, `SportsText.kt`, `HubCard.kt`, `GoalsSheet.kt`, `BoxScoreSheet.kt`, `MatchupSheet.kt`, `ClipPlayerLayer.kt`, `ClipSession.kt`; `ui/src/test/resources/golden/sports-hashes.txt`; tests `HubLeagueUiNflTest`, `ScoringRowsTest`, `SportsViewModelHubTest`, `ClipSessionTest`, `HubPanelFitTest`, `HubCardFitTest`, goldens | Sonnet under this spec; Opus for `ClipSession` and the review | NHL / CHL goldens hash equal; NFL goldens recorded; its tests green on commit 0 + P3 alone (fakes) |
| **Integration + review** | the merge; §8.5; device items; code reviews (data, video / player, UI); release gates (plan §6); §9.4 docs | Opus (lead) | all gates green |

**Owners of the files the reviews called unowned or shared.** Commit 0 only: `NhlClipResolver.kt`, `EspnScoreSource.kt`,
`NhlDetailSource.kt`, `SportsModule.kt`, `HubContracts.kt`, `SportsContracts.kt`, `SportsConstants.kt`,
`EspnSummaryDto.kt`, `EspnDto.kt`, `EspnVideoMapper.kt`, `strings_sports_hub.xml`, `fakepanel.py`, `HubFakes.kt`, the
commit-0 fixtures and `tools/espn/reduce_summary.py`. P3 only: `GoalsSheet.kt` (after the move), `SportsScreen.kt`,
`SportsViewModel.kt` with `HubToastKind`, and `sports-hashes.txt`. P2 only: `PlaybackControllerImpl.kt`. Not touched by
this design: `Redact.kt` and `ExportRedactionTest`. A package that needs a change in a commit-0 file asks the lead.

### 9.3 Sequencing

P1, P2 and P3 start together on commit 0. P2 tests `EspnDetailSource` with an injected mapper. P3 builds on hand-made
`GameDetail`s from the fakes. Integration merges P1 → P2 → P3, then runs the emulator checklist on the fake panel.

### 9.4 Docs to amend at integration (lead)

- `docs/design/m6-sports-hub.md` §7 (superseded for the NFL by this design);
- a decision note `docs/decisions/2026-11-2x-nfl-hub.md` (owner rulings Q1, Q2 default, Q3 outcome, the video exposure,
  the cadence, the deferred list);
- `tools/fakepanel/README.md` (new switches);
- `docs/00-master-plan.md` M6 row.
`CLAUDE.md` is unchanged (clips use the broker).

---

## 10. New constants (`SportsConstants`)

| Name | Value | Use |
|---|---|---|
| `ESPN_SUMMARY_MAX_BYTES` | 2 097 152 | decoded summary cap |
| `ESPN_CLIP_RECORD_MAX` | 65 536 | record body cap |
| `ESPN_VIDEO_ENABLED` | true | the NFL video kill switch (§4.1) |
| `HUB_NFL_PRE_MS` | 1 800 000 | pre-game re-read |
| `HUB_NFL_LIVE_MS` | 60 000 | live and break re-read |
| `HUB_NFL_REFETCH_FLOOR_MS` | 15 000 | earliest re-read after the last answer on a phase change |
| `HUB_NFL_HIGHLIGHT_WAIT_MS` | 7 200 000 | final re-read until a highlight appears |
| `HUB_NFL_PLAYS_WAIT_MS` | 1 800 000 | final re-read after the highlight, for late single-play clips |
| `HUB_NFL_GAME_LENGTH_MS` | 11 700 000 | end estimate for a game first seen final |
| `HUB_CLIP_EXPIRY_MARGIN_MS` | 300 000 | never offer a clip this close to expiry |
| `HUB_CLIP_DEFAULT_LIFE_MS` | 172 800 000 | effective expiry without `expirationDate` (48 h after publish) |
| `HUB_CLOCK_SKEW_MAX_MS` | 3 600 000 | clamp of the server-date skew |
| `HUB_NFL_MAX_PLAYS` / `HUB_NFL_MAX_CLIPS` | 60 / 24 | parse truncation |
| `HUB_NFL_BOX_LINES` | 8 | athletes per box table |
| `HUB_NFL_INJURIES_PER_TEAM` | 6 | Matchup sheet |
| `ESPN_CLIP_CATALOG_MAX` | 64 | catalog LRU |

The rendition cap is not a `SportsConstants` entry; it lives in `ClipRendition.kt` (§4.5).

---

## 11. Open questions for the owner

| # | Question | Status |
|---|---|---|
| Q1 | Play ESPN's highlights inside The Box without ESPN's pre-roll ads? | **Closed**: owner ruling 2026-11-25, in-app as the NHL, with "Video: ESPN" on every clip and the kill switch. |
| Q2 | Show the written recap's headline on finished games? | **Closed** by default (lead ruling 8): headline only, one line, second after Leaders. |
| Q3 | Run the two test clips on the Sony Bravia (§8.5 item 10). If the ESPN 720p59.94 clip stutters, a 30 fps cap for all devices goes in `ClipRendition.kt`. | Open. Default: no cap on normal devices, 720p30 on low-RAM devices. |

---

## 12. Risks and what cannot be verified yet

| Risk | Handling |
|---|---|
| **Live summary shape NV** (team stats, `scoringPlays` timing, in-game `videos`) | The lead's capture tonight (§8.1). If it is missing, the live fixtures are built from the final shapes and the live tests are provisional until a real capture. |
| Board score vs the summary's `scoringPlays` lag (NV) | The headline score is always the board's. The Scoring count can trail it by up to 60 s plus ESPN's own lag. |
| Clip URLs after `expirationDate` (NV) | Never offered after expiry; the VM re-checks at each expiry instant; a master 403 / 404 / 410 → one fresh record lookup → the card. |
| `videos[]` selection rule unknown; some tagged clips absent | Show what the summary lists; nothing is searched elsewhere (content.core filters are ignored, V). |
| ESPN-aired Saturday / ABC games get one-plays (NV) | Behaviour is data-driven; nothing assumes which games have clips. |
| Rate limiting under hours of polling (NV) | 60 s live, shown game only, back-off and the 429 floor; the kill switch for video. The summary can only be slowed further. |
| A WAF block on the JSON hosts (seen today only on www.espn.com, as 202 + challenge) | `EspnHttp` maps 403 and 202-with-WAF to `HTTP_429` for every ESPN call: the 5 min refused floor, and the "limiting requests" text. The board and the detail keep independent back-offs (§15). |
| Geo outside Canada (NV; Akamai edge was Ontario, no geo field) | A geo refusal on media lands on the `CLIP_EXPIRED` path; recorded once a non-CA user reports it. |
| Terms: video without ESPN's ads; AP recap text | Owner ruling Q1; attribution; no storage; kill switch; headline only. |
| Parse cost on a low-end Fire TV (600 KB per refresh, 1–2 MB churn) | Device check §8.5 item 9 against the JVM allocation yardstick; the `BufferedSource` top-level skip fallback (§5.3). |
| 1920 × 1080 thumbnails on low RAM | Never on low-end (art instead); decoded at the drawn size. |
| Sony Bravia clip stutter (undiagnosed, NHL) | `ClipRendition.kt` holds the cap; owner Q3. |
| Clock skew on the device | `now + skew` from the server's `Date`; the 5 min margin; the master request is the backstop. |
| Summary over 2 MiB (very long OT game with many clips) | `BAD_BODY` and the failure text; the cap is a constant. |
| Live slot row is 5 px under 664 at Largest | `HubPanelFitTest` asserts the two-digit sets; a three-digit channel count would overflow, as it would for the NHL. |

Not verifiable without a live game: §2 live rows and their values, in-game clip arrival, halftime behaviour, and the live
request budget against ESPN's tolerance. Not verifiable without a real device: parse time and GC on a Fire TV Stick,
720p59.94 smoothness, the Bravia stutter, and the low-RAM cap.

---

## 13. Review reconciliation

Lead's rulings (2026-11-25): (1) accept every Blocker and Major, and every Minor not in conflict; (2) owner ruling: ESPN
clips in-app, Q1 closed; (3) the cut list, §15; (4) `ClipRendition` as a constant pair; (5) `stream(ref, fresh)` stays;
(6) the broadcast network on the pre-game panel, division standing deferred; (7) the live situation line from the
board's `situation`, mandatory; (8) the recap headline second, one line, dropped first; (9) commit 0 makes the packages
compile on their own, with 3 disjoint packages; (10) the live fixtures come from tonight's capture, else they are built
from the final shapes and marked provisional.

**Architecture review** (`2026-11-25-nfl-hub-design-review-arch.md`)

| Id | Finding | Resolution |
|---|---|---|
| M1 | The nudge and the halftime hold have no input path | Cut (ruling 3): the plain 60 s loop, back-off wins (§5.2) |
| M2 | `info()` makes record lookups while browsing | Fixed: `info()` is catalog-only, `GameClip.thumbUrl`, and links are cut (§4.2, §6.1, §15) |
| M3 | The expired re-resolve replays the dead URL | Fixed: `stream(ref, fresh)`; no summary read from the resolver; `ClipStream` KDoc (§4.2, §6.1; ruling 5) |
| M4 | The box score is a second parse off the loop, with a racy slot | Fixed: `Input.Box` through the loop, a loop-confined slot, mapping on Default (§5.5) |
| M5 | `Redact.registerTransient` of clip paths evicts secrets | Cut (ruling 3); clip URLs logged as host only (§4.7) |
| M6 | ESPN's WAF 403 / 202 is misclassified | Fixed: `EspnHttp` → `HTTP_429` for every ESPN call; records never REFUSED (§4.2, §5.3). The optional cross-actor gate is deferred (§15). |
| M7 | The expiry re-publish is a no-op; the board ignores expiry | Fixed: the VM's `offerTick` is the one mechanism; `ClipOffer` everywhere; NFL `recapId` null (§4.3; ruling 3) |
| M8 | The packages do not compile independently | Fixed: the commit-0 checklist and 3 disjoint packages (§9; ruling 9) |
| m1 | Team order differs by section | Fixed: `team.id` in every section, sides by id, a reversed-order test (§1, §5.3, §8.2) |
| m2 | The record's `plays[].id` is a number | Moot: `plays[]` is not declared while links are deferred; §15 notes `JsonElement` for later |
| m3 | Safety is "SF"; DEF_TD from the text | Fixed (`PlayScore`, §6.1; fixture IND@HOU) |
| m4 | `Leader.value: Int` truncates 5.5 sacks | Fixed: `Leader.line`, drawn for fractional values (§3.6, §6.1) |
| m5 | A phase change is not due at once | Fixed: `phaseChangeFloorMs` (§5.2, §6.3) |
| m6 | Final polling stops before late one-plays | Fixed: `HUB_NFL_PLAYS_WAIT_MS` (§5.2) |
| m7 | The headline score must stay the board's | Fixed: the NFL detail has no score (§2.1, §6.1) |
| m8 | The `league:id` key across every map | Fixed (§5.2) |
| m9 | The cap is hard-coded; Content-Length is −1 | Fixed: a per-call cap; the declared-length check is not counted (§5.3) |
| m10 | Allocation, not live set; the fallback library is wrong | Fixed: a ThreadMXBean yardstick; a `BufferedSource` skip (§5.3) |
| m11 | The rendition cap persists into live; use `engine.isLowRam` | Fixed (§4.5; ruling 4) |
| m12 | An expired next clip ends the queue | Fixed: skip on advance (§3.1, §4.6) |
| m13 | A better clock reference | Fixed: `clockSkewMs`; board at its server date (§4.3) |
| m14 | Unused `StatKind`s; the reducer is uncommitted; key order; catalog threads | Fixed: kinds cut (ruling 3); `tools/espn/reduce_summary.py`; "no code depends on key order"; catalog `synchronized`, no board writes (§5.3, §4.2, §8.1) |
| Cut 1 | Clip-to-play linking | Cut (§15) |
| Cut 2 | Catalog fill from the scoreboard source | Cut (§15) |
| Cut 3 | Nudge and halftime hold | Cut (§15) |
| Cut 4 | `ClipRendition` as a policy | Accepted as a constant pair, with a low-RAM cap of 720p30 (ruling 4) |
| Cut 5 | Redact registration | Cut |
| Cut 6 | The repository re-publish | Cut; replaced by the VM tick |
| Cut 7 | Unused `StatKind`s; the JsonReader fallback | Cut; the fallback is named concretely and not built |

**UX review** (`2026-11-25-nfl-hub-design-review-ux.md`)

| Id | Finding | Resolution |
|---|---|---|
| B1 | The final slot labels overflow 664 | Fixed: "▶ Highlights", "▶ From start", no glyph when disabled; measured table (§2.3) |
| M1 | The live situation is optional and lost at Largest | Fixed: live line 1 from the board's `situation`, at the board's cadence; Quarters line cut (§2.4; ruling 7) |
| M2 | The recap wraps, so Leaders never shows | Fixed: Leaders line 1, Recap line 2, one line, first to drop (§2.4, §2.6; ruling 8). Note: on art at Largest both lines still fit (133 px), so the Recap drops at Largest only with the poster. |
| M3 | The recap picture poses as a video | Fixed: matchup art with the reason; the recap image is dropped; captions shortened (§2.1, §2.4) |
| M4 | Disabled slots have no spoken reason | Fixed: `slotReasons` for any grey slot, the NHL "Recap soon" included (§2.3) |
| m1 | Name suffixes; a wrong example | Fixed: the after-first-". " rule; MNF example corrected; team appended (§2.4) |
| m2 | "SF"; "2nd 1:55"; leader instead of scorer | Fixed: "Q2 1:55 · CIN TD · CIN leads 10–0"; `PlayScore` words (§3.1) |
| m3 | Pre-game labels not parallel | Fixed (§2.2; widest 328 px at Largest) |
| m4 | "Q1 INT" | Fixed: "END Q1" (§2.1, §6.6) |
| m5 | "More plays" lists non-plays | Fixed: "More videos", "Video 2 of 4" (§3.1, §3.3) |
| m6 | "Video: ESPN" dropped by the wrong budget | Fixed: unconditional (§3.3; ruling 2) |
| m7 | Matchup lines cut; last-5 order | Fixed: the shortened line, category labels, winner-first, oldest first (§3.6) |
| m8 | NFL spoken forms | Fixed (§3.7) |
| m9 | The play disc is lost at Default | Fixed: `extraFloor` = 200 for a playable poster (§2.6) |
| m10 | "No video" | Fixed: "No highlights" (§2.3) |
| m11 | Failed and video-off states | Fixed: slots and captions defined (§2.3, §2.5) |
| m12 | A weather delay looks frozen | Fixed: `delayed`, the "DELAYED" pill and line (§2.5) |
| Cut: links | — | Cut (§15) |
| Cut: live still | — | Cut (§15) |
| Cut: Quarters line | — | Cut (§15) |
| Cut: Defense / Kicking | — | Cut (§15) |
| Cut: recap image | — | Cut (§2.1) |
| Missing: situation, last play | — | The situation: done (M1). The last-play text: deferred (the mapper does not keep `lastPlay.text`) |
| Missing: broadcast network | — | Done: the pre-game (and live) caption "venue · network" from the board (ruling 6). The channel-line wording is deferred. |
| Missing: division standing | — | Deferred (ruling 6) |
| Missing: home / road record, next game, weather | — | Deferred (§15) |
| Missing: head-to-head | — | Not available (no source) |

**Proposer's own corrections in this revision**:
- the failure state no longer keeps the BY QUARTER rows with a 3-row note, which left 89 px at Largest (§2.5);
- the line score comes from the board's `BoardExtras.periods`, so no new contract is needed;
- `RecapStory` is reduced to `GameDetail.recapHeadline`, since the image is no longer used;
- `PlayScore.TWO_POINT` is dropped (never seen).

---

## 14. Corrections and gaps found in FACTS.md (checked against the samples)

1. The per-clip `content.core …/video/clips/{id}` answer is a **list wrapper** `{resultsCount, resultsLimit,
   resultsOffset, videos: [ {…} ], timestamp, status}`, not a bare clip record.
2. The scoreboard's `highlights[]` has the same object shape as the summary's `videos[]`, but it carries only the Final
   Game Highlight (MNF board: 1, summary: 12).
3. The recap `article.source` is **"AP"**, an Associated Press story distributed by ESPN.
4. `article.images[0]` is not always the game. On MNF it is a still of a studio clip ("Jayden Daniels reacts to
   Countdown crew…"); on MIA@BUF and PIT@PHI it is the highlight's still. Not used (§2.1).
5. Highlight latency: MNF 14 min after END GAME (04:18:36Z → 04:32:11Z) and **PIT@PHI 20 min** (21:09:27Z → 21:29:51Z);
   FACTS gives only the MNF figure. One MNF one-play came 7 min after the highlight (04:39:03Z).
6. Frame rates: the master's top two variants are `FRAME-RATE=59.920`; the other six (360p, 480p, 720p30) are 29.97 fps.
7. **Key order varies**: `header` follows `drives` in `sum-final.json` but precedes `winprobability`, `article` and
   `videos` in `sum-mnf-401873078.json`, and `sum-pre.json` differs from `sum-pre2-401873079.json`. No parser can stop
   early, and no code may depend on key order.
8. The pre-game summary has **no** `scoringPlays` or `drives` keys (absent, not empty arrays); `lastFiveGames` and
   `predictor` exist only pre-game.
9. `scoringPlays[]` items carry no athlete or headshot (only `team`); `id` is a string. In the record, `plays[].id` is a
   JSON number.
10. Summary `videos[]` objects have only the 1920 × 1080 `thumbnail`; the 576 × 324 `posterImages.default` is in the
    record only.
11. Records include ties ("4-5-1", WSH); the existing board `RECORD` regex already allows it.
12. **Team order** differs by section: header, `leaders` and `injuries` are home first; `boxscore.teams` and
    `boxscore.players` are away first.
13. `type.abbreviation` is one of FG, TD and **SF** (safety). A defensive TD is `TD` with `type.text` "Interception
    Return Touchdown" (type id 36).
14. Leader `value`s are fractional for sacks (5.5, 1.5); `displayValue` is the safe text.
15. `lastFiveGames[].events[].score` is winner first ("20-17" for a loss), listed oldest first.
16. Box athletes carry `displayName` and no `shortName`.
17. Insufficient for a decision: no live summary sample (§8.1), no sample of an expired clip's URL, and no check of the
    summary against the app's own User-Agent (the probe used a Chrome UA; the scoreboard already works with the app's
    UA on the same host, so the risk is low).

---

## 15. Deferred from the first version

| Item | Reason | To bring it back |
|---|---|---|
| Clip-to-scoring-play link (▶ on scoring rows) | Only ESPN-aired games have it (MNF: 3 of 10 scoring plays). It costs up to 16 record lookups per game, a budget, concurrency, per-game counters and `ClipInfo.playIds`, and the same clips already play from More videos. | After the live capture shows how often links exist: a `links(gameId, ids)` seam on `EspnClipResolver`, with the budget in the resolver only; declare `plays[].id` as `JsonElement` (a number in the record). |
| Catalog fill from the scoreboard source | The board highlight needs only `GameMedia(recapId, recapExpiresMs)`; a miss costs one 2 KB lookup. Filling it would make the score actor depend on `:data` video code and churn the LRU. | Not planned. |
| The score-change nudge and the half-time hold | Neither has an input path into the repository (arch M1); they save ~13 requests a game. | Repository input on every board snapshot, with back-off still winning. |
| The repository's expiry re-publish | The VM's `offerTick` is the one mechanism (arch M7). | Not planned. |
| Clip paths registered for log redaction | It evicts real secrets from the 64-entry transient LRU; the paths are public. Clip URLs are still logged as host only. | A static `Redact` pattern for `cmp-espn.media.dssott.com`, if ever wanted. |
| `StatKind` members no row uses (4th down, penalties, red zone, per-game passing / rushing) | No row draws them; penalties "5-39" is count-yards, not a fraction. | Append them with the row that uses them (a "Team stats" sheet costs no request). |
| The live "latest play" still in the panel | ESPN games only, 6–11 min late; matchup art is enough. | `GameClip.thumbUrl` already carries the picture. |
| The "Quarters" live summary line | Replaced by the situation line; quarters are in the Box score. | — |
| Box score Defense and Kicking tables | Low viewer value; Defense needs the interceptions-category merge. | Append `BoxCategory` members. |
| The recap picture | It misleads (§2.1). | Under a neutral "STORY" tag, if the owner wants imagery. |
| Division standing, home / road records | Not asked for. `standings` (V) and `record[type=home|road]` (V) are in the pre-game summary. | One Matchup sheet line each. |
| Weather, next game, the last-play text, "No channel found · on Netflix" in the channel line | Weather: unit and dome handling. Next game: next week's board. The last-play text: the mapper keeps only the penalty flag. The channel line: shared NHL code. | Each is a small follow-up. |
| A shared ESPN back-off between the score and detail actors | Arch M6's optional item; the two keep independent back-offs today. | If the live capture or users show WAF blocks. |

---

## 16. Commit 0 notes

Commit 0 (lead's implementer, 2026-11-25) followed §9.1. These are the places where the code needed something the design
did not say, or said differently; each is the smallest change that keeps P1, P2 and P3 independent.

| # | What | Why |
|---|---|---|
| 1 | The type-36 fixture is **`summary-final-401873077.json`** (MIN @ SF, "Interception Return Touchdown"). `summary-final-401873066.json` (IND @ HOU) covers the safety ("SF", type 20) only. | §8.1 put both in IND @ HOU; the sample has no type 36 (checked across all ten summaries). |
| 2 | `HubLeagueUi.clipRefs` gains `goalsOpen: Boolean`. | Today's `ensureClipInfos` looks up the NHL condensed game only while its Goals sheet is open, which the view model knows and the league UI does not. |
| 3 | `HubSlots.default(c)` stays, as `HubLeagues.of(c.group).slots(c)`; `goalsSheetRows` and `goalQueue` moved verbatim to `NhlHubUi.kt` as top-level functions with the same names; `GoalsSheet` still draws `GoalsRow`. `ScoringRow` and `RowIcon` are declared in `GoalsSheet.kt`; every `scoringRows` (NHL included) is a P3 stub. | The view model, `HubEventChannelsTextTest` and `HubSheetsTest` call these by name; keeping the names keeps every existing test untouched. Building the NHL rows "with the same words" needs the sheet's rework, which is P3's file. |
| 4 | `ScoringRow.Event` has `clip: ClipRef? = null` (the NHL goal's clip; null for an NFL play). `RowIcon` is `Headshot(url)`, `TeamLogo(side)`, `None`. | §6.6 named both without a shape. |
| 5 | `sports_hub_more_soon` is **not** reworded in commit 0. `sports_hub_more_soon_chl` holds the new words; P3 switches `ChlHubUi` to it (a `// P3` marker is there). | The lead's acceptance: the CHL panel unchanged byte for byte after commit 0. |
| 6 | `EspnArticleDto` declares `type` as well as `headline`, and the reducer keeps both. | §6.1 shows the headline of a *Recap* article only; pre-game summaries carry a "Preview" article. |
| 7 | `EspnSummaryDto` leaves: `JsonElement?` for every id, `awayScore` / `homeScore`, `period.number`, `value` and `duration` (numbers ESPN may send as text); `String?` / `Boolean?` elsewhere. The header's `status` declares `type` only (the summary carries no period or clock there). | §5.3's rule, applied to the recorded shapes. |
| 8 | `HubPanelContents.build(res, m, clock24, factor, measure, locale, lowEnd = false)`: `measure` is required, `lowEnd` defaulted. `HubPanelContentsTest` passes a stub measure (NHL words never read it). | A defaulted measure would silently measure 0 in production. |
| 9 | `RowsSpec(header, rows, extra, loading, error, moreSoon)` and `SummarySpec(lines, extra)`; `HubSource { NHL_COM, ESPN, NONE }` (NONE: the CHL). | §6.6 named the types without fields. |
| 10 | `NflHubUi.detailed` is `false` in commit 0 (today's NFL is board-only); P3 sets it with the detail. | `detailed` is read by nothing yet; true would misdescribe today's NFL. |
| 11 | `DetailCadence.NHL.gameLengthMs` (9 000 000) replaces the repository's `GAME_LENGTH_MS` constant (kept as an alias). `nextAt` already honours `liveAligned = false` (a plain `lastOk + liveMs`); the phase-change floor and `playsWaitMs` are P1's (`fetchedPhase` and `FinalFacts.playClips` are accepted and unused). | Pure and NHL-neutral; it keeps P1's change inside `DetailPollPolicy.kt`. |
| 12 | `EspnEndpoints.clipBase` is a fourth constructor field; `EspnScoreSourceTest.endpointOverrideParsing` lists it. | The data class's equality includes it. |
| 13 | `DetailAnswer` is `internal` top-level in `GameDetailRepositoryImpl.kt`. `DetailFetcher.of(nhl, espn)` routes NFL to `EspnDetailSource`, but the loop still publishes `BoardOnly` for every non-NHL game (its `step` and `nextWake` guards), so the stub is never called. | P2 removes the guards. |
| 14 | Fake panel: `fakepanel.py` routes the summary path and the `/espn-core/` and `/espn-media/` prefixes to `sports_nfl.handle_summary(h, path)` and `sports_video.handle_espn(h, path)`, found with `getattr`, so P2 adds those functions without touching `fakepanel.py` (until then: 404 "not served yet"). `DEFAULTS` gains `sports_nfl_summary_pad_kb` (400) and `sports_nfl_clips` ("espn", one of `NFL_CLIP_MODES`), validated by `check_nfl_hub_config`. The summary faults (`summary_status`, `summary_pad_kb` over the cap) are `sports_nfl_faults` keys, which live in `sports_nfl.py` (P2). New test file `test_nfl_hub_routes.py`. | §8.4 gave the ESPN HLS media no path; `/espn-media/` is under P2's handler. |
| 15 | Stub shapes for P2: `EspnClipCatalog.get(id)`, `put(entries)`, `trim(keep)`; `EspnDetailSource(client, mono, endpoints, catalog, mapDetail, mapBox)`; `EspnClipResolver(client, mono, endpoints, catalog)`; `ClipResolvers(nhl, espn, espnVideo)` (the `@Inject` one passes `ESPN_VIDEO_ENABLED`). `FakeClipResolver` also records `streamFresh`. `NflHubFixtures` (testFixtures) names every commit-0 fixture and decodes them. | §6.5 named the classes only. |
| 16 | `ClipOffer.changesAt` answers null when the embargo lifts at or after `expiresMs − margin` (never offered, so nothing changes). `EspnVideoMapper` also honours a `blacklist` `deviceRestrictions` (settop must not be listed), applies the https rule to the thumbnail, and keeps a blank headline as "". `StatRules.better` compares fractions other than THIRD_DOWN by `made`, as the panel did. | Edge cases the rules did not cover. |
| 17 | `SummaryLine.oneLine` is already drawn (`maxLines = 1` when set) and `HubPanelPlan.plan`'s `extraFloor` already governs the extra rows; both default to today's behaviour. `StatValue` arms: `Fraction.dash` draws "4-12", `Decimal` its places, `Clock` "m:ss", `Text` as is; Text and Clock are never compared. The panel's `statRow` still uses its PIM / GIVEAWAYS rule; P3 switches it to `StatRules.better`. | The NHL goldens hash equal; the switch belongs with the NFL rows. |
| 18 | Risk: the board DTO's `highlights` is a typed list (as §5.1 asks), so a structural change of that one field by ESPN would fail the whole board decode (`BAD_BODY`, the stale path), not just the chip. Every leaf is nullable, so only a non-list or non-object breaks it. | Noted for the integration review; a `JsonElement?` field mapped through `EspnVideoMapper` would remove the risk. |

### 17.2 P2 notes

P2 (`:data`, the clip rendition in `:player`, the fake panel), 2026-11-25. Places where the code needed something the
design did not say, or said differently. Frozen signatures are unchanged.

| # | What | Why |
|---|---|---|
| 1 | The NHL box score keeps today's on-demand path (`BoxFlight`, one request per game at a time); only the NFL goes through `Input.Box`. | §5.5 said "the NHL keeps its own request, through the same input", but the loop's "an id that is not shown gets `Failed`" rule would change the NHL sheet (its tests ask for a box with no game shown). The NHL never had the two-parse problem: its box is its own endpoint. |
| 2 | NFL `Input.Box`: a slot hit answers at once (final: any age; else ≤ the phase's interval); a miss while `failures > 0` answers the last failure **without** forcing (back-off wins); otherwise it clears the rest, sets `force` (or joins the parse in flight) and is completed from the next `Fetched` (its box, or its failure; an answer without a box is `BAD_BODY`). A move, Sports off or a gate that closes before the fetch starts completes it with `HTTP_OTHER`. | §5.5 left the back-off and rest cases open. |
| 3 | `EspnDetailSource` and `EspnClipResolver` constructors gained trailing defaulted parameters (`wallNow`; the source also `callTimeoutMs`, `mapDispatcher`). `GameDetailRepositoryImpl`'s internal constructor gained `onTrim` (the `@Inject` one passes `EspnDetailSource.trimMemory`). | The skew and the offer check need a wall clock; the tests need a virtual one. Internal constructors only; nothing frozen moved. |
| 4 | The skew lives in `EspnClipCatalog.skewMs`: the newest ESPN `Date` − wall clock of a summary **or** a record answer, clamped to ±1 h. The resolver checks `ClipOffer` at `wallNow() + catalog.skewMs`. | §4.2 said "`ClipOffer` at `now + skew`" without saying where the resolver gets the skew; it never reads a summary (arch M3). |
| 5 | The source sets `GameDetail.clockSkewMs` (always) and `serverDateMs` (when the mapper left it null) on the mapper's answer, and calls the mapper with `atMs = wall + skew`. | `EspnDetailMapper.detail(dto, id, atMs)` has no skew or `Date` input. **P1**: leave both at their defaults; the source owns them. |
| 6 | The catalog remembers the last clip handed to the player (`lastServed`); `trim()` on the repository (the `onTrimMemory` path) calls `catalog.trimToServed()` and drops the box slot. | §5.4 "except the playing clip's entry" needed an owner of that id. |
| 7 | http media (and thumbnails) are allowed whenever the ESPN endpoints are a debug override (`apiBase != API_BASE`); the URL's origin is not compared with the override's. | `EspnVideoMapper.entry` takes one Boolean; a debug build only. |
| 8 | A record that maps to nothing (no https HLS, a device list without `settop`, an unknown coverage type, the wrong id) is `ClipResult.Failed(BAD_BODY)`, which the UI words as "This video can't play on this device" (§3.4). The record is mapped with `atMs = null` and the time rule applied afterwards, so an expired or embargoed record is `Expired`, not `BAD_BODY`. A 401 on a record is mapped to `HTTP_429` by the resolver (EspnHttp's rule is 403 and the 202 only, as §5.3 says). | §4.2 listed the HTTP outcomes but not a 200 that cannot play. |
| 9 | The WAF rule sits in `EspnHttp.classify`, before the status `when`: the day board's 403, which was `HTTP_OTHER`, is now `HTTP_429` (one assertion in `EspnScoreSourceTest.statusCodesAndHtml404s` updated). `EspnScoreSource`'s own `403 → 429` mappings are now no-ops and were left (commit-0 file). `Answer.code` still carries 403. | Arch M6: every ESPN call. |
| 10 | `EspnHttp.decodeCapped(input, serializer, cap)` (internal, the body of `classify`'s 200 branch, unchanged in behaviour) so `EspnSummaryDecodeTest` measures the transport's own decode on the test thread. | The decode otherwise runs on OkHttp's thread, where `ThreadMXBean` cannot attribute it. |
| 11 | NFL `FinalFacts`: `recapPosted` = a RECAP clip in the detail offered at `now + skew`, or the board's `media.recapId` offered by `recapExpiresMs` (a null expiry counts as posted); `playClips` = the detail lists any PLAY clip. The NHL rule is unchanged. | §5.2, arch M7: an expired highlight is not "posted", so it never ends the 2 h wait early (it is ≥ 48 h old anyway). |
| 12 | `ClipRendition` holds three constants: `LOW_RAM_MAX_WIDTH = 1280` beside the height / fps pair. | §4.5 wrote `setMaxVideoSize(1280, …)` with a literal. |
| 13 | Test-only edits outside the P2 list: `player/…/controller/fakes/Fakes.kt` (`FakeEngine.isLowRam` `val` → `var`), the NHL repository test's NFL-BoardOnly case narrowed to the CHL (the NFL cases are in the new `GameDetailRepositoryNflTest`), and the `EspnScoreSourceTest` assertion of row 9. | Needed for the rendition test and for the NFL routing, which the old cases asserted against. |
| 14 | Fake panel: NYJ @ NE (401899003) carries the `sports_nfl_clips` mode's clips; week 8's DEN @ SEA (401898001) always an expired highlight; the featured KC @ BUF never lists clips (the no-clip game, live and final). The 59.94 fps variant is the panel's 50 fps clip declared at 59.94. Summary faults are `sports_nfl_faults.summary_status` / `summary_pad_kb` / `summary_stall_ms` (§16 row 14 named the first two). | §8.4 named the modes, not which game carries them. |
| 15 | Cadence tests that depend on P1's NFL rules (the 15 s phase-change floor, the 30 min PLAY wait) are written to hold with or without them (`liveToFinalSwitchesTheCadence` bounds the final read by the 5 min final cadence). Integration can tighten that bound to 15 s after P1. | P2 tests on commit 0 + P2 alone. |
