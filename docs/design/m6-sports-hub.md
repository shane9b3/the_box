# Sports Hub: the new Sports page, NHL game detail, video and the channel link (design)

Status:
- **Proposed 2026-11-24** (proposer, Opus).
- **Reviewed 2026-11-24**: `docs/review/2026-11-24-sports-hub-design-review-arch.md` (A-…) and
  `docs/review/2026-11-24-sports-hub-design-review-ux.md` (U-…).
- **Revised 2026-11-24** after the owner's key-model ruling and the lead's rulings. Every finding is applied in place and
  marked with its id (A-B1, U-S4, …); the table is §12 "Review reconciliation". Lead sign-off: §13.
- **Re-reviewed 2026-11-24** (the "Re-review" section of the architecture review): commit 0 cleared with conditions; the
  R-S1 … R-S5 and R-N1 … R-N4 items are folded in and marked (R-S1, …). **Signed off by the lead** (§13).
- **Integrated 2026-11-25** on `sports-hub` (WP-1 … WP-5 merged); the pass's fixes, emulator states and open items are
  §15. Decision note `docs/decisions/2026-11-24-sports-hub.md`.

Inputs:
- `CLAUDE.md`; `docs/00-master-plan.md`; `docs/01-design-reference.md` (1 design px = width ÷ 1920; tokens).
- `docs/design/m6-score-bug.md` §5 (today's Sports tab), `docs/decisions/2026-11-05-nhl-score-bug.md`,
  `docs/design/m6-nfl.md` §7, `docs/design/m6-chl-leagues.md` §6, `docs/decisions/2026-11-24-text-sizes.md`,
  `docs/decisions/2026-11-19-weather-page.md`.
- The owner's mock-ups `nhl.jpeg` and `nfl.jpeg` (2026-11-24, session scratchpad; not in the repo).
- The lead's feed probe of 2026-11-24 (`FACTS.md` and samples in the session scratchpad: `score-y.json`,
  `score-now.json`, `2026020382-*` (final), `2026020385-*` (not started), `story-382.json`, `v-*.json` (video lookups)).
  §10.1 says how the samples become repo fixtures; §10.5 lists the recordings the lead still has to make.

Units: design px on the 1920 × 1080 canvas (`BoxDesignScale`). "24/600" = 24 px text at weight 600; every text size is
multiplied by the Text size factor (0.9 / 1.0 / 1.1 / 1.2, `textSp`). Times in ms unless marked s. Code line numbers are
at `main` `d0a5235`.

Words on screen: the existing set plus *Goals*, *Recap*, *Condensed game*, *Box score*, *Matchup*, *Channels*, *Watch*.
Never "feed", "API", "key", "Brightcove", "policy", "guide data". The credit line names NHL.com. Contractions and plain
verbs ("didn't", "Select") in messages (U-S11).

---

## 0. Decisions at a glance

### 0.1 The design in one screen

| Topic | Decision |
|---|---|
| Shape | Title row (Sports Hub, NHL · NFL · CHL, date and time), a **detail panel** on the left (720 × 812), on the right a heading, a **day strip** and a **two-column grid** of 472 × 304 cards. The mock-up's NEW ribbon, PREVIEW chip, NBA, Soccer, All Sports, search and profile icons are not built. |
| Panel follows focus | Pill, headline and matchup art switch **in the same frame** as card focus (local data). Detail, poster and channel match start after 600 ms of rest, or at once when OK enters the panel (U-S2, U-S4). |
| **Key model** (owner ruling) | **OK or Menu on any card**, in either column, every league and phase, moves focus to the panel's first enabled button. Left from the left column does the same. Three fixed slots that never reorder; Left / Right between them; Back, or Right from the last enabled slot, returns to the same card. No long press, no actions list (§2). |
| Slots | Before and during a game: **Follow · Watch / Channels · Goals (NHL live) or Matchup (NHL pre-game)**. Final NHL: **Watch recap · Goals · Box score**. Final NFL / CHL: **Watch from start** (catch-up) only. Follow is not offered for a final game (§2.4). |
| Day strip | NHL and CHL: 15 days (−7 … +7), one request on OK, none on focus. NFL: Last / This / Next week (S6), cards show the weekday. |
| NHL detail | `gamecenter/{id}/right-rail` + `gamecenter/{id}/landing` for the shown game only; live every 30 s on the 20 s CDN bucket; pre-game 5 min; final once (then every 5 min for 3 h until the recap is posted); box score on demand. Nothing with Show Sports off, the device asleep, the app in the background, or a clip playing. |
| Video | Route A (owner ruling): clear HLS (MP4 720p fallback) through the one ExoPlayer on the root surface, full screen, through the existing transport and broker with a `clip:<host>` key. A `ClipSession` in `:ui` snapshots what to resume, owns the player by tune id, resumes through the guarded tune or `ArchivePlayback`, and yields to any other tune (A-B1, A-B2, A-S1). Key read at run time, memory only, masked by `Redact.registerSecret` (A-S4). |
| Summary sentence | No written recap exists. Final: the **game-winning goal** (its clip's `long_description` or a built sentence, may wrap) and "Stars: …" names. Live: the latest goal and the period scores. Pre-game: the season series and the points leaders (U-S8). |
| Channel link | League-independent: broadcaster codes → **exact** channel-name aliases after noise stripping with a listed-country-tag check (no aliases for streaming-only codes; local affiliates are guide-only), plus guide titles naming both teams in the game window, plus a direct guide-window read of the alias-matched channels. Three confidence levels with plain wording; the channel is named in an info line above the buttons, not in a button label (A-B3, A-S8, U-B3, U-S10). Selecting tunes and goes full screen; it does **not** follow (owner ruling). |
| NFL and CHL | Same shell, cards and panel, filled from data the app already fetches or parses (scores by period, records, game leaders, shots), no new request (lead ruling 2, U-S7). No video and no box score for them in this version. |
| Settled by the lead | Corner / Delay chips leave the page (Settings › Sports and the quick bar keep them). Range −7 … +7. Next goal auto-plays. Captions follow the player's subtitles setting. Guessed regional channels show with a warning. English clips only. No reminders. Catch-up "watch from the start" is kept (§5.6: it reuses Search's launch path and A-B2's archive resume costs nothing extra). |

### 0.2 Owner rulings folded in (2026-11-24)

1. **Video is route A**: the app's own player, with the web player's key read at run time. Rejected: **route B** (the
   embed page in a WebView: a second video pipeline beside the one surface, heavy on a Fire TV Stick, web focus fights
   the D-pad); **route C** (no video: the owner wants recaps and goals).
2. **Picking a channel tunes only**; it does not start following the game.
3. The red NEW ribbon is not built. PREVIEW, NBA, Soccer, All Sports, search and profile icons are removed.
4. **Key model**: the UX reviewer's §R (OK or Menu on any card enters the panel's first button; three fixed slots; Back or
   Right from the last button returns to the same card; no long press, no actions list; the Goals sheet lists goals first
   and stays open under a clip).

### 0.3 Risk the owner has accepted with route A

The key is the one nhl.com's public web player ships; the app reads it as a browser does (no `Origin` or `Referer`
spoofing, A-N6). The NHL can rotate it (handled: §6.2 re-read) or block non-browser use (handled: scores and stats keep
working and the page says videos are unavailable, §6.8). Nothing in the repo, tests, tools or logs contains the key.

---

## 1. Screen layout (1920 × 1080)

The mock-up is 2000 px wide with dead bands above and below. The layout keeps its proportions (panel ≈ 37 % of the
width, two card columns) on the app's 96 px side margins, 56 px title-safe top and bottom (U-S9) and the 22 px text
floor; no mock-up number is copied.

### 1.1 Regions

| Region | Geometry (design px) | Content |
|---|---|---|
| Page | padding left/right 96, top 56, bottom 56 | Background: `Canvas` plus one static linear gradient from top-left `#123D2C` (α 0.85) to transparent at 60 % of the diagonal (a `Brush`; no blur, no `graphicsLayer`). |
| Title row | y 56 → 120 | "Sports Hub" 44/600 at x 96. League chips 32 after the title: **NHL · NFL · CHL**, then with CHL shown a 1 px divider and **OHL · WHL · QMJHL** (unchanged logic, CHL §6.3). Right-aligned to 1824: date 26/500 `Fg70` ("Tue, Nov 24"), 16 gap, time 36/600 ("5:35 PM", or "17:35" with the 24-hour setting). Date and time are not focusable. |
| Detail panel | x 96, y 148, w 720, h 812 (to 960); radius 24; fill `White05`, 1 px `White08` border; padding 28 (inner 664) | §1.3 |
| Right column | x 856 (96 + 720 + 40), w 968 (to 1824) | |
| Heading | y 148, h 32 | "NHL SCORES & SCHEDULE" (league-named) 24/700, letter-spacing 0.04 em, `Fg70`. |
| Day strip | y 196, h 60 | §3. Arrow glyphs (‹ ›, 32/500, `Fg55`, not focusable) in 40 px columns at x 856 and x 1784; chips scroll in x 908 → 1772. |
| Games grid | y 280 → 960 (viewport 680) | 2 columns × 472, gap 24; cards 304 tall, row gap 24: two rows in full and a 48 px peek of the third. `LazyVerticalGrid` (≤ 16 items). The focused row keeps 48 px inside the viewport (today's `ensureVisible`). |
| Footer | y 976 → 1024 | Left: `FooterHints` 24 (key caps 22), §2.6, with `maxWidth = 1728 − creditWidth − 32` (U-S9). Right-aligned to 1824: the credit 22 `Fg55` ("Scores, stats and video: NHL.com"; per league as today) and, while a re-poll fails, "Updated 2 min ago" 22 `Warn`. |

Colours: `DesignTokens.Colors` (`Canvas #0A0D13`, `Accent #5CA8F2`, `AccentText #B6D4F7`) and the `GuideUi` alphas.
Selected league chip: fill `#2F6FD0`, white text.

**Focus language (U-S12): exactly one thing looks pressed.**
- Focused card: 4 px `Accent` ring drawn inside the bounds and fill `#2A3340`.
- The card whose panel has focus ("selected"): 2 px `Accent` outline, normal fill.
- Focused button: `#EEF1F6` fill, `#0B0E14` text **and** a 3 px `Accent` ring. Unfocused buttons are all secondary
  (`White10` fill, `White16` border, white text); the "primary" white look appears only on focus.
- Selected day chip: `#EEF1F6` at 85 % opacity, dark text. Focused day chip: 3 px ring and a 4 px `Accent` underline;
  a focused unselected chip has the `White16` fill.

### 1.2 Game card (472 × 304)

Radius 20, fill `#171C24`, 1 px `White08` border; focus and selection per §1.1.

| Part | Geometry | Text |
|---|---|---|
| Padding | 24 all round | |
| Away row | y 24, h 88 | Logo slot 88 × 64 (fit, no plate; the dark variant as today) at x 24. Name column x 128, width 220: two lines, place over nickname ("Winnipeg" / "Jets"), 28/600, line height 34. Score column 76 wide, right edge at 448: 52/700 tabular. |
| Home row | y 128, h 88 | Same. |
| Bottom row | y 236, h 44 | Status pill (h 44, radius 12, padding 16, 22/700 caps): `FINAL`, `FINAL/OT`, `FINAL/SO`, `● LIVE · 2ND 10:22` (red dot 12, fill `#5A1F24`), `1ST INT`, `7:00 PM`, `POSTPONED`, `TIME TBD`. NFL upcoming: `SUN 1:00 PM`, plus the date when outside the shown week (`MON NOV 30 8:15 PM`) (U-S6). Then 12 gap and at most one chip (h 44, radius 22, padding 16, 22/600): "✓ Following" (`AccentText` on `White08`) or, NHL only, "● Highlights" (a clip exists, from `GameSnapshot.media`). Following wins. |

- No OK chip on the card: the footer names OK (U-S1). When the pill and the chip do not both fit, the **chip** is hidden;
  the pill never shrinks.
- **Refused** state (U-S13): when the followed game's feed refused the app (`GameCardModel.refused`), the two team rows
  are replaced as today by the matchup line and the reason (`sports_failed_refused`) in `Warn`.
- Score text: before the game none; live both white; final the winner white and the loser `Fg55`.
- Names: `HubNames` (§4.6). Fit rule (`HubCardFitTest`: all 32 NHL, 32 NFL and 61 CHL teams at the four text sizes):
  28 px, shrinking in 2 px steps to 24 × factor, then an ellipsis. At Larger and Largest the logo slot is 72 × 52 and
  its gap 12 (name column 248). The score uses the same shrink rule from 52 to 44 × factor (U-N9).
- **Order is frozen** while the page shows a day (U-S3): `SportsViewModel.order` runs on board load, day change and page
  entry; re-polls update cards in place; a new game is appended at the end; a game that vanishes is removed (focus moves
  to its neighbour by index). After any change to the list, `ensureVisible` runs again on the focused key after layout.

### 1.3 Detail panel (720 × 812, inner 664)

Top to bottom, y from the panel's top, default text size (U-S9 height 812):

| y | h | Item |
|---|---|---|
| 28 | 36 | Status pill, centred (the card's pill). |
| 76 | 44 | Headline, centred, one line, laid out as `[away name][ (n)] vs. [home name][ (n)]` (the owner's "Away (n) vs. Home (n)"). Only the names give way: full names at 32/600; else names shrink to 26 × factor; else nicknames; else the names ellipsize. **The scores never shrink or ellipsize** (U-B4). Before the game: "Vegas Golden Knights vs. Edmonton Oilers". |
| 136 | rest | **Media area**, 664 wide, radius 16, `ContentScale.Crop` anchored 30 % from the top (heads stay in frame, U-N4). Height = what the rows below leave: 212 for live and final, 174 pre-game (default size). Floors: 160 for a poster, 120 for matchup art (§1.4). |
| +16 | 32 | Stats header: away nickname (left) · "TEAM STATS" / "SEASON" / "BY PERIOD" (centre) · home nickname (right), 22/600 `Fg55`. |
| | 5 × 38 (6 × 38 pre-game) | Stat rows: away value (left), label (centre), home value (right), 24/500, 1 px `White08` rule under each. The better value is white, the other `Fg70`. |
| +16 | ≤ 64 | Summary, 24/400 `Fg70`, label 24/600 white (§6.7). Line 1 may wrap to two lines; then line 2 is not drawn (U-S8). |
| +16 | 30 | **Channel line** (U-B3), 22/500 `Fg70`: "On TV: Sportsnet Ontario · in your guide · +2 more"; upcoming: "On TV at 7:00 PM: Sportsnet Ontario"; while matching "Finding a channel…"; none: "No channel found in your playlists". Not drawn for final NHL games without a catch-up airing. |
| +12 | 60 | Buttons: three fixed slots (§1.5). |
| | 28 | Bottom padding. Total 812. |

**Loading** (U-S2): the pill, headline and matchup art come from the board snapshot and switch with focus. Until a detail
for **this** game id is ready, the stat rows show the phase's labels with "–" values in `Fg40`, no summary, the channel
line "Finding a channel…" once matching started, and matchup art until a poster decodes. The layout never jumps.
`HubPanelModel` is built only from a `DetailState` whose `id` equals the shown game's id; a late answer for another game
is dropped.

**Which game the panel shows** (U-S2): the focused card's; with focus on a chip or in the title row, the game shown
last **on this board**; on a new board (day or league change), that board's initial-focus game (followed, else first live,
else the first card); an empty board shows an empty panel (the matchup art's place filled with `White05`, no buttons).

Media area contents (no game photo exists):

| Phase | Media area |
|---|---|
| Final (NHL) | The **recap's poster** (§6.3). Tag top-left "RECAP" (22/700 caps, `Black60` plate, radius 8); a centre 88 px play disc (`Black50`, white triangle), decoration only, never focusable (U-N11); bottom-left caption over a static black gradient (0 → 70 %): "Game recap (4:55)" 26/600. Recap not posted yet: matchup art and the caption "Recap not posted yet". |
| Live, intermission (NHL) | The **latest goal clip's poster**, tag "LATEST GOAL", caption "Vilardi strikes first (0:46)". No clip yet: matchup art, caption "Goal videos appear a few minutes after each goal". |
| Scheduled, pre-game, postponed; NFL and CHL in every phase | **Matchup art** (`MatchupArt.kt`): the area split on a 12° diagonal into the teams' primary colours (`NhlTeamColours`, `NflTeamColours`, `ChlTeamColours`; darkened until white text has 4.5 : 1, `Contrast.kt`), logos 120 px (88 below a 160 px area) centred in each half, a centre disc 120 px (88) `#0B0E14` with the start time 26/700 over the day 22/500. Bottom caption 22/500: the venue when known. Postponed: the disc reads "Postponed". |

### 1.4 Larger text sizes

The panel's height is fixed; the media area absorbs growth (pill +5, headline +9, header +6, each stat row +8, summary
lines +7 each, channel line +5, buttons +6 at Largest).

| Phase at Largest | Media area | Dropped |
|---|---|---|
| Live, final NHL | 212 − 84 = 128 < 160 → drop summary line 2 → 167 | summary line 2 (line 1 one line) |
| Pre-game NHL | 174 − 92 = 82 < 120 → drop summary line 2 → 121 | summary line 2 (U-N5) |
| NFL / CHL | ≥ 160 in every phase (fewer rows) | nothing |

Order of giving way: media area to its floor, then summary line 2, then summary line 1 wraps to one line, then summary
line 1. Stat rows never drop below the phase's count. `HubPanelFitTest` asserts every phase at the four sizes with the
fixtures' longest names (incl. "Blue Jackets", "Golden Knights", "Maple Leafs", "Buccaneers", "Commanders" and the
longest CHL names) and asserts that the scores in the headline are never cut.

### 1.5 Buttons (three fixed slots)

Height 60, radius 30, 24/600, gap 16, padding 28 at Default and Smaller, **20 at Larger and Largest with a 12 gap**. A
glyph (24 px) on slot 1 only (U-B3). Labels are short and fixed; the channel name is in the channel line, never in a
label.

| Phase | Slot 1 | Slot 2 | Slot 3 |
|---|---|---|---|
| Scheduled, pre-game (all leagues) | **Follow** / **Following ✓** | **Channels (n)** (none: disabled "Channels") | **Matchup** (NHL; empty for NFL / CHL) |
| Live, intermission (all leagues) | **Follow** / **Following ✓** | **Watch** (one CONFIRMED channel, live or starting within 30 min: tunes) or **Channels (n)** (opens the sheet); disabled "Finding…" while matching; disabled "Channels" when none | **Goals (n)** (NHL; disabled "Goals" while no goal has a clip; empty for NFL / CHL) |
| Final NHL | **Watch recap** (disabled "Recap soon" until posted) | **Goals (n)** | **Box score** |
| Final NFL / CHL | **Watch from start** (catch-up airing found, §5.6; else no slot) | – | – |
| Postponed | none | | |

Widths (estimates at 0.55 em; the fit test is the authority): live "Follow" + "Channels (3)" + "Goals (6)" ≈ 580 px at
Default and ≈ 655 at Largest with the reduced padding; final "Watch recap" + "Goals (6)" + "Box score" ≈ 610 at Default
and ≈ 645 at Largest. Both fit 664, so no slot ever moves into a sheet (the reviewer's "third button into the sheet"
fallback is not needed; U-B3 departure explained in §12). A fourth button is never allowed.

Disabled slots are drawn at 40 % and skipped by focus; an empty slot draws nothing but keeps its place (the slots never
close up, U-S4). "Following ✓" activates Stop following.

---

## 2. Focus and keys (D-pad only; owner ruling)

### 2.1 Initial focus

Unchanged rule (`SportsViewModel.initialFocusIndex`): the followed game, else the first live card, else the Today chip.

### 2.2 Cards

| Key | Effect |
|---|---|
| D-pad | Moves between cards; the panel switches in the same frame (§1.3). Up from the first row → the selected day chip, after scrolling the strip to it (`scrollToItem`, one frame, then `requestFocus`; U-S5). Right from the right column: nothing. |
| **OK or Menu** | Focus moves to the panel's **first enabled slot** of this card's game, in either column, every league and phase. The match, the detail and the clip info start at once (no 600 ms wait). No enabled slot (postponed; NFL / CHL final without catch-up): nothing happens and the footer shows no OK. |
| Left from the left column | Same as OK (a shortcut). |
| Back | Guide grid (unchanged: one Back from the page). |

No long press anywhere on the page (U-N10: Fire TV's held OK sends repeated centre events).

### 2.3 Panel

| Key | Effect |
|---|---|
| Left / Right | Between enabled slots. Right from the last enabled slot → back to the same card. Left from the first: nothing. |
| Up / Down | Consumed. |
| OK | The slot's action. |
| Back | Back to the same card. |

While focus is in the panel the panel stays on its game; the slot set is **frozen while focus is in the panel**: a phase
change (pre-game → live, live → final) or a matcher result updates labels in place but never moves an action to another
slot until focus leaves the panel. Across a re-poll or rebuild focus is restored by **action identity**, else the same
slot index, else the card (U-S4).

### 2.4 Follow and finished games (owner ruling: "where Follow sits for a final game that is still followable")

- Follow is always slot 1 while a game is not over.
- A finished game offers no Follow: following it only showed the final score for five minutes (U-N6). Its follow, if
  any, runs out as today (silent expiry after the final), the card keeps "✓ Following" until then, and the quick bar's
  score sheet has Stop following.
- The one case of a finished game that is still followable from the page: the game ends **while focus is in its panel**.
  The frozen slot set (§2.3) keeps Follow / Following ✓ in slot 1, working, until focus leaves the panel; on the next
  entry slot 1 is Watch recap.

### 2.5 Sheets

All sheets share the §6.4 geometry and take focus; the grid behind is not focusable.

| Sheet | Opened by | Focus on open | Back |
|---|---|---|---|
| Goals | slot 3 (live), slot 2 (final NHL) | live: the latest goal; final: the first goal | the slot that opened it |
| Channels | slot 2 (Channels (n)), slot 1 (final NFL / CHL with several catch-up rows) | the first row | the slot that opened it |
| Box score | slot 3 (final NHL) | the Away tab | the slot |
| Matchup | slot 3 (pre-game NHL) | the first row | the slot |

The Goals sheet **stays open (not drawn) under a clip**; Back from the clip returns to it with focus on the clip just
played, so "the next goal" is one Down and OK (U-S14). Every return restores by game id and action, never by index, with
the card as the fallback.

### 2.6 Footer hints

| Focus | Hints |
|---|---|
| Card | `← → ↑ ↓ Browse` · `OK Options` (absent when the game has no enabled slot) · `Back Guide` |
| Panel | `← → Choose` · `OK {slot label}` · `Back Games` |
| Chips | `← → ↑ ↓ Browse` · `OK Show` · `Back Guide` |
| Sheet | `↑ ↓ Choose` · `OK Play` / `OK Watch` / `← → Away / Home` (box score) · `Back Close` |

### 2.7 Title row, chips, Back

- Up from the day strip → the shown league's chip (unchanged). Up on the title row is consumed. Down from the strip →
  the card focused last on this day, else the first card (U-S5).
- Corner and Delay chips and their choice lists are removed from the page (lead ruling 3).
- Back: from a card or chip → guide; from the panel → its card; from a sheet → the slot that opened it; from the clip
  player → where it was started (§6.5).

### 2.8 Press counts for the common jobs (from the initial focus on a live card)

| Job | Presses |
|---|---|
| Follow a live game | 2 (OK, OK) |
| Go to the channel carrying a live game | 3 (OK, Right, OK), +1 with the sheet |
| Watch a recap (final NHL) | 2 (OK, OK) |
| Watch goal k of a final game | OK, Right, OK, Down × (k−1), OK = 3 + k |
| Tomorrow's schedule | Up, Right, OK = 3 from the first row |

---

## 3. Day navigation

### 3.1 Range and labels

| League | Chips | Labels (24/600) |
|---|---|---|
| NHL, OHL, WHL, QMJHL | 15 chips, today −7 … +7 (`HUB_DAY_RANGE`), Today selected and centred on entry | "Yesterday · Nov 23", "Today · Nov 24", "Tomorrow · Nov 25", other days "Sat · Nov 21". A day the board says has no games is dimmed (`Fg40`) and stays selectable; "No games" is in its content description, not its label (U-S5). |
| NFL | 3 chips (S6: keys only from the board) | "Last week · Wk 11", "This week · Wk 12", "Next week · Wk 13"; an unnamed neighbour is disabled. Arrows hidden. |

Chip: height 60, radius 30, padding 24, gap 12. The strip is a `LazyRow`. Left / Right move between chips; at Default and
Smaller the strip keeps one chip of context on each side of the focused chip; at Larger and Largest it centres the focused
chip instead (U-S5). The arrows show only while chips are hidden on that side.

### 3.2 Request cost

- **Focus costs nothing.** OK on a chip calls `ScoreRepository.showDay(dayKey)`: one `GET /v1/score/{date}` (5–45 KB
  raw, about 4–8 KB gzip), or CHL's `gamesbydate`.
- Day keys: NHL and CHL = the league's today (`SportsDays.today`, the NHL's Eastern date) plus the offset. The
  `yesterdayKey` / `tomorrowKey` learning stays only for the NFL.
- The shown day polls by today's `DayPollPolicy`.
- Past-day boards are kept in a per-open memory map (≤ 15 boards), so stepping back across final days does not refetch.

### 3.3 "No games" days

`DayBoard.gameCounts` (§4.1) from the NHL body's `gameWeek[] {date, numberOfGames}` (7 days around the board). Only days
known to be empty are dimmed. Other leagues leave it empty.

---

## 4. Data

### 4.1 Contract additions (commit 0, `:core`, append-only and defaulted)

`SportsContracts.kt`:

```kotlin
// GameSnapshot, appended (defaulted, so every existing constructor call compiles):
val broadcasts: List<TvBroadcast> = emptyList(),   // NHL tvBroadcasts[]; NFL board broadcasts (§7.2); CHL empty
val media: GameMedia? = null,                      // NHL only
val extras: BoardExtras? = null,                   // NFL and CHL: what their boards already carry (§7.1)

// DayBoard, appended:
val gameCounts: Map<String, Int> = emptyMap(),

/** One listed broadcaster. [network] trimmed ("ABTV " → "ABTV"); [country] upper-case ISO-2 or null. */
data class TvBroadcast(val network: String, val market: Market, val country: String?)
enum class Market { NATIONAL, HOME, AWAY }   // N / H / A; any other market: the entry is dropped by the mapper

/** NHL board video facts: ids from `threeMinRecap` / `condensedGame` (trailing number of the path) and `goals[].highlightClip`. */
data class GameMedia(val recapId: Long?, val condensedId: Long?, val goalClips: Int)

/** NFL / CHL board facts the app already receives (§7.1). Every field optional. */
data class BoardExtras(
    val awayRecord: String?, val homeRecord: String?,       // "8-3"
    val periods: List<PeriodScore> = emptyList(),           // quarters / periods, when the board has them
    val leaders: List<BoardLeader> = emptyList(),           // NFL game leaders
    val awayShots: Int? = null, val homeShots: Int? = null, // CHL board shots
)
data class BoardLeader(val category: String /* passing | rushing | receiving */, val side: Side, val name: String, val line: String)
```

`GameSnapshot` and `DayBoard` are not `@Serializable` (A-N4); the persisted form stays `FollowedGame`. Equality now changes
when a broadcaster or clip appears; that is wanted for the card chip. The score bug ignores these fields.

New `core/src/main/kotlin/app/thebox/core/sports/hub/HubContracts.kt` (names frozen at commit 0):

```kotlin
data class GameDetail(
    val id: GameId,
    val fetchedPhase: GamePhase,
    /** Server `Date` of the newest answer used (right-rail or landing); the panel's score source rule, §4.3. */
    val serverDateMs: Long?,
    val awayScore: Int?, val homeScore: Int?,  // landing scores, for the newest-wins rule
    val venue: String?,
    val gameStats: List<StatRow>,              // right-rail teamGameStats (live, final)
    val season: SeasonComparison?,             // right-rail teamSeasonStats + last10Record + landing team record
    val periods: List<PeriodScore>,            // right-rail linescore.byPeriod
    val goals: List<GoalItem>,                 // landing summary.scoring
    val stars: List<Star>,                     // landing summary.threeStars
    val leaders: List<LeaderPair>,             // landing matchup.skaterComparison.leaders
    val goalies: GoaliePair?,                  // landing matchup.goalieComparison
    val series: SeriesFacts?,                  // right-rail seasonSeries + seasonSeriesWins
    val recapId: Long?, val condensedId: Long?,
    val broadcasts: List<TvBroadcast>,
)
enum class StatKind { SOG, POWER_PLAY, HITS, BLOCKED, FACEOFF_PCT, PIM, GIVEAWAYS, TAKEAWAYS }
sealed interface StatValue {
    data class Count(val n: Int) : StatValue
    data class Fraction(val made: Int, val of: Int) : StatValue
    data class Pct(val value: Double) : StatValue
}
data class StatRow(val kind: StatKind, val away: StatValue, val home: StatValue)
data class Ranked(val value: Double, val rank: Int?)
data class Last10(val record: String, val streakType: String?, val streak: Int?)
data class SeasonComparison(
    val awayRecord: String?, val homeRecord: String?, val awayLast10: Last10?, val homeLast10: Last10?,
    val awayGf: Ranked?, val homeGf: Ranked?, val awayGa: Ranked?, val homeGa: Ranked?,
    val awayPp: Ranked?, val homePp: Ranked?, val awayPk: Ranked?, val homePk: Ranked?,
)
data class PeriodScore(val label: PeriodLabel, val away: Int, val home: Int)
enum class Strength { EV, PP, SH }
data class GoalItem(
    val period: PeriodLabel, val timeInPeriod: String, val side: Side, val scorer: String, val scorerLast: String,
    val scorerTotal: Int?, val assistsLast: List<String>, val strength: Strength, val emptyNet: Boolean,
    val away: Int, val home: Int, val clipId: Long?, val headshot: String?,
)
data class Star(val rank: Int, val name: String, val lastName: String, val team: String, val headshot: String?)
data class Leader(val name: String, val value: Int, val headshot: String?)
data class LeaderPair(val category: String, val away: Leader?, val home: Leader?)
data class Goalie(val name: String, val record: String?, val gaa: Double?, val svPct: Double?, val headshot: String?)
data class GoaliePair(val away: List<Goalie>, val home: List<Goalie>)
data class SeriesFacts(val awayWins: Int, val homeWins: Int, val meetingsPlayed: Int)
data class BoxScore(val away: TeamBox, val home: TeamBox)
data class TeamBox(val skaters: List<SkaterLine>, val goalies: List<GoalieLine>)
data class SkaterLine(val name: String, val pos: String, val g: Int, val a: Int, val p: Int, val plusMinus: Int?, val sog: Int?, val toi: String?)
data class GoalieLine(val name: String, val sa: Int?, val saves: Int?, val svPct: Double?, val toi: String?)

interface GameDetailSource {                    // :data NhlDetailSource
    suspend fun detail(id: GameId): Fetch<GameDetail>
    suspend fun boxScore(id: GameId): Fetch<BoxScore>
}
sealed interface DetailState {
    data object None : DetailState
    data class Loading(val id: GameId) : DetailState
    /** [stale]: older than 2 × the phase's poll interval (A-S7). */
    data class Ready(val detail: GameDetail, val fetchedAtMono: Long, val failing: Boolean, val stale: Boolean) : DetailState
    data class Failed(val id: GameId, val kind: FailKind) : DetailState
    /** NFL and CHL: the panel uses the board's `BoardExtras` only. */
    data class BoardOnly(val id: GameId) : DetailState
}
interface GameDetailRepository {                // :data GameDetailRepositoryImpl
    val detail: StateFlow<DetailState>
    fun show(game: GameSnapshot?)               // the panel's game; null = page closed
    fun pause(paused: Boolean)                  // true while a clip plays
    fun retry()
    suspend fun boxScore(id: GameId): Fetch<BoxScore>
    fun trim()
}
```

Video types: §6.1. Channel types: §5.1. `SportsEffect` (`:ui`, commit 0, A-S6):

```kotlin
sealed interface SportsEffect {
    data class Tune(val channel: GuideChannel) : SportsEffect
    data class PlayArchive(val channel: GuideChannel, val info: ProgrammeInfo) : SportsEffect
    /** ClipSession's resume (§6.6). */
    data class Resume(val point: ResumePoint) : SportsEffect
}
```

### 4.2 Endpoints and sizes (NHL; `SportsEndpoints.apiBase`, the host the app already uses)

| Request | When | Size (sample) |
|---|---|---|
| `GET v1/gamecenter/{id}/right-rail` | shown game, every phase | 4–5 KB raw, ≈ 1 KB gzip |
| `GET v1/gamecenter/{id}/landing` | shown game, every phase | 12–21 KB raw, 2–4 KB gzip |
| `GET v1/gamecenter/{id}/boxscore` | Box score opened | 13 KB raw, ≈ 2.4 KB gzip |

`wsc/game-story` is not used (no video ids, no line score). The two detail calls go out together; the result is `Ok`
when the right-rail answered (a failed landing leaves goals, stars, leaders and goalies empty and sets `failing`), else the
right-rail's failure.

Transport: commit 0 extracts `NhlScoreSource`'s private `call`, `classify`, `readCapped`, `retryAfterS`, `ageS`,
`serverDateMs` and `isHtml` into `internal class NhlHttp` (same file behaviour, no change to the score path) and moves
`SportsEndpoints` into its own file (A-S6). `NhlDetailSource` uses `NhlHttp`. Bodies are capped
(`SportsConstants.MAX_BODY_BYTES`) and decoded with `Json.parseToJsonElement`, every field optional: the NHL path's
bounded reading, which meets the streaming rule's intent for 21 KB bodies (A-N1).

### 4.3 When the detail is fetched (`DetailPollPolicy`, `:core`, pure)

The repository fetches only for the game the panel shows (`show(game)`: after 600 ms of rest, or at once when OK enters
the panel). A move to another card cancels an in-flight call.

| Phase | Requests |
|---|---|
| SCHEDULED, PRE | at once (cache hit: none), then every 5 min (`HUB_DETAIL_IDLE_MS`) |
| LIVE, INTERMISSION | at once, then every 30 s bucket-aligned on the 20 s CDN bucket with the install's `pollPhaseMs` (`SportsTiming.nearestAligned`) |
| FINAL | once; while `recapId` is null and the game ended < 3 h ago (`HUB_RECAP_WAIT_MS`), every 5 min |
| POSTPONED | once |

- Failures back off as the day list does (`SportsTiming.backoffAt`, `Retry-After` as a floor).
- No request while Show Sports is off, the device is not interactive (`DeviceAwake`), the process is in the background,
  there is no network (`DetailState.Failed(OFFLINE)`), or `pause(true)` (a clip plays).
- Cache: LRU of 24 details keyed by game id. A hit is shown at once; a `Ready` older than 2 × the phase's interval is
  marked `stale` and shows "Stats from 8:12 PM" (22 `Warn`) under the stats header until the refetch lands (A-S7).
- **Score source** (A-S7): the panel's headline score is the board snapshot's or the detail's, whichever answer has the
  newer server `Date`; a tie takes the board. The card always uses the board.

### 4.4 Beside `ScoreRepository`

`GameDetailRepositoryImpl` is a separate `@Singleton` with its own single-threaded actor and `NhlHttp` over the same
`@ApiClient` client with `WireMeter`. It never touches the follow machine, its `PollMeter`, its demand owners or the day
list. When the panel shows the followed live game both fetch its landing (≈ 3 KB gzip per 30 s extra), accepted.

While a clip plays the Sports view model reports `DemandOwner.SPORTS_TAB` **off** and calls `detail.pause(true)`, so the
day list stops for the clip's length (A-S7). A followed game is not stopped: with no `SEEN` owner its demand drops to
`UNSEEN` and it keeps polling at the `POLL_UNSEEN_MS` floor (`ScoreRepositoryImpl.kt:480-486`, `ScoreBugMachine.kt:1248`),
which is today's behaviour everywhere outside FULL and Sports and is accepted (R-N2).

### 4.5 Failure texts (reason + next step; per `FailKind`, A-S7, U-S11)

| Situation | Where | Text |
|---|---|---|
| TIMEOUT, IO, HTTP_5XX, HTTP_OTHER, nothing cached | panel, in place of the stat rows; slot 1 becomes **Retry** for this state (OK on the card lands on it) | "Couldn't load game stats from NHL.com. Trying again automatically. Select Retry to try now." |
| HTTP_429, REFUSED | same, no Retry (the back-off runs) | "NHL.com is limiting requests right now. Stats load again in a few minutes." |
| BAD_BODY | same, no Retry | "NHL.com changed how it sends game stats. Scores still update; stats need an app update." |
| OFFLINE | same | "No internet connection. Stats load again when the network is back." |
| Failing or stale with a detail shown | under the stats header, 22 `Warn` | "Stats from 8:12 PM" |
| Box score failed | sheet, with a Retry row | "Couldn't load the box score from NHL.com. Select Retry, or press Back to close." |
| Day board failed | grid | today's texts (score-bug design §5.4) |

When the Retry state replaces slot 1 the other slots keep their places (Retry is slot 1's action for that state, not a new
button).

### 4.6 Names (`HubNames`, `:ui`; `NhlTeamPlaces`, `ChlTeamPlaces`, `:core`)

- NHL: the board's `name` is the nickname; the place comes from `NhlTeamPlaces` (32 entries, commit 0).
- NFL: `TeamLine.name` minus its `shortName` suffix ("Kansas City" / "Chiefs").
- CHL: `ChlTeamPlaces` (61 entries: team code → place, nickname), so French-order names ("Saguenéens de Chicoutimi") split
  the same way as English ones. A place that does not fit the name column at the shrink floor gets a short form in the
  table, chosen by WP-4 with the fit test (for example "Blainville-Boisbriand" → "Boisbriand"; never a truncated
  abbreviation). The table is the single source (U-S15).
- Unknown team: one line.

---

## 5. Channel link

### 5.1 Contract (`HubContracts.kt`)

```kotlin
enum class MatchConfidence { CONFIRMED, LIKELY, POSSIBLE }
sealed interface MatchReason {
    /** The channel's guide names the game in the window. */
    data class Guide(val title: String, val startMs: Long, val endMs: Long, val inArchive: Boolean) : MatchReason
    /** The channel's guide has a sport-word airing in the window that names no teams (A-S8). */
    data class SportListing(val title: String, val startMs: Long) : MatchReason
    /** The channel's name equals a broadcaster alias NHL.com (or ESPN) lists. Never a local affiliate (R-S3). */
    data class Broadcaster(val network: String, val market: Market, val country: String?) : MatchReason
}
data class ChannelCandidate(
    val channel: GuideChannel,
    val playlistName: String,
    val confidence: MatchConfidence,
    val reasons: List<MatchReason>,
    val variants: List<GuideChannel>,   // collapsed quality variants ("Also: FHD, 4K")
    val airing: ProgrammeInfo?,          // for catch-up (§5.6)
)
interface GameChannelMatcher {           // :data GameChannelMatcherImpl
    suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess): List<ChannelCandidate>
    fun clear()                          // page open, access change
}
```

### 5.2 Method

**A. Broadcaster aliases** (`BroadcastAliases`, `:core`, table + pure matcher; A-B3):

1. **Residue.** From the channel's display name: drop a leading country tag (`^TAG\s*[|:\-]`, and `[TAG]` / `(TAG)` at
   either end) **only when TAG is in the explicit list `CountryTags`** (R-S2): the ISO-3166 alpha-2 codes plus the
   provider spellings `UK` (= GB), `USA` (= US), `CAN` (= CA), `LAT`, `EU`, `ARB`, `AFR`, `EX-YU`, seeded from the fake
   catalog and one real playlist; **minus every token that is an alias token of any code in `BroadcastAliases`** (so
   `SN`, `TSN`, `CBC`, `ABC`, `TNT`, `MSG`, `RDS`, `CTV` and the like are never read as tags; "SN" is Senegal in ISO but
   never a tag here). Then fold (`NameNormalizer.fold`), split on non-letters/digits, drop trailing noise tokens
   (`hd fhd uhd sd 4k hevc h265 raw 50fps 60fps backup alt 1080p 720p` and a trailing `(backup)`). The residue is the
   remaining tokens joined by one space. "SN - Ontario" → residue "sn ontario"; "TSN: 3" → "tsn 3"; "CA| SN ONTARIO FHD"
   → tag CA, residue "sn ontario".
2. **Exact equality.** A channel matches a code when its residue **equals** one of the code's aliases, or an alias plus
   **one** region word from that code's list (Sportsnet: `ontario east west pacific`). No subsequence matching: "Sportsnet
   360" is not SN, "ESPN 2" is not ESPN, "MSG Sportsnet" is not MSG, "TNT Sports 1" is not TNT.
3. **Country.** A channel whose tag names a country different from `TvBroadcast.country` is dropped; a channel with no tag
   is kept one level lower (LIKELY → POSSIBLE).
4. **Streaming-only codes have no aliases** and never match: SN+, Prime Video, HBO MAX / Max, ESPN+, Peacock, Paramount+,
   and every club or regional "+" service (Mammoth+, Wild+, FOXLA+, …; any code ending in "+").
5. **Unknown codes**: POSSIBLE only, and only when the residue equals the code itself (≥ 3 characters).
6. **Local affiliates are guide-only** (R-S3, lead ruling): ABC, CBS, FOX, NBC and CTV stations, CBC regionals and other
   over-the-air affiliates are **never** matched by name alias. Those codes have no aliases; a channel carrying the game
   on an affiliate is found only by the guide probes (B, and the window read of C on any channel B finds). "US: ABC 7 NEW
   YORK" with no guide listing for the game is therefore not a candidate.

| Code(s) | Aliases (residues) | Level when matched |
|---|---|---|
| SN | "sportsnet" + region word; "sn" + region word; "sportsnet" | national: LIKELY |
| SN1 | "sportsnet one", "sn1", "sportsnet 1" | LIKELY |
| SN360 | "sportsnet 360", "sn360", "sn 360" | LIKELY |
| TVAS, TVAS2 | "tva sports", "tvas"; "tva sports 2", "tvas2" | LIKELY |
| TNT, TRUTV | "tnt"; "trutv", "tru tv" | LIKELY (country US) |
| ESPN, ESPN2 | "espn"; "espn2", "espn 2" | LIKELY |
| NHLN | "nhl network", "nhln" | LIKELY |
| MSG, MSGSN, MSG2 | "msg", "msg network"; "msg sportsnet", "msgsn"; "msg2", "msg 2" | regional: POSSIBLE |
| NBCSCA, NBCSBA, NBCSP, NBCSWA … | "nbc sports california", … (one per code) | regional: POSSIBLE |
| FDSN… / BSSO… | "fanduel sports" / "bally sports" + the code's region word | regional: POSSIBLE |
| TSN1–TSN5, RDS, RDS2 (seeded, A-N7) | "tsn 1" … "tsn 5", "tsn1" …; "rds", "rds 2", "rds2" | POSSIBLE at most |
| NFL set (§7.2): NFLN, TSN | "nfl network", "nfln"; "tsn" | LIKELY |
| ABC, CBS, FOX, NBC, CTV, CBC, KONG, KCOP and every other over-the-air station or affiliate | **none** (guide only, rule 6) | — |

Market H or A lowers any match to POSSIBLE (a regional broadcast), whatever the table says.

**Query** (A-B3, A-N2): `channel_fts` is FTS4 (`ChannelFtsEntity`): one query per code with the aliases as quoted phrases
joined by upper-case `OR`, no `*`, no parentheses, **no `ORDER BY`**, `LIMIT 200`, through a new
`GuideQueries.channelsByNameFts(match, access, limit)` that applies the lineup predicate and `PARENTAL_RAW` as
`searchChannels` does; then the residue rule in Kotlin.
*(Code review 2026-11-25, M3, L3.)* One token can be shared by hundreds of channels ("espn", "sn"), so a single page of
200 could cut the national channel before the residue rule ran. The query now pages by keyset on the FTS rowid
(`rowid > last ORDER BY rowid`, FTS4's own order, no sort) until a page comes back short, 96 residue matches are in hand
(C's channel cap), or 1 000 rows were read per code (`ALIAS_FTS_MAX_ROWS`, 5 pages). Renamed channels are matched too:
`channel_fts` holds the provider name only, so one extra read of the lineup's `channel_prefs.custom_name` rows (≤ 200,
`GuideQueries.channelsWithCustomName`) per match goes through the same residue rule.

**B. Guide titles** (Search's EPG FTS; A-S8, A-N8):
- Two queries: the two nicknames ("Jets Mammoth"), then the two places ("Winnipeg Utah"). `limit` 80, `perText` 40
  (`HUB_MATCH_PER_TEXT`), so one airing text on 4 regionals × 3 variants × 2 playlists is kept whole.
- Live and upcoming via `EpgReader.search`; ended via `searchPast` with `pastFromMin = now − min(7, archiveDaysAll(access))
  days`, then the per-channel archive rule (Search's code; `archiveDaysAll` already exists, so one span for the query and
  the per-channel cut after it is the same answer as A-N8's "largest among candidates" without needing A first).
- Keep airings overlapping `[start − 30 min, start + 4 h]` (display minutes with the channel's EPG shift; no device or
  panel zone, A-N9).
- Refs → channels with `GuideQueries.channelsForRefs(refs, access)` and Search's confirmation rule.
- A title naming both teams confirms the game. One team alone counts only with a sport word ("NHL", "Hockey", "LNH",
  "Hockey Night"; NFL: "NFL", "Football") in the title or subtitle (Jets, Panthers, Rangers, Kings collide across
  leagues).

**C. Window read of A's channels** (A-S8, R-S4): no FTS. For the channels A matched (≤ 8 candidates × their variants),
resolve their EPG refs (`EpgReader.resolve`) and read the game window directly with one
`EpgReader.window(channels, shiftMin, fromMin = start − 30 min, toMin = start + 4 h, out)` call (`EpgContracts.kt:253`).
In Kotlin, per airing:
- names both teams (or one team + a sport word): the channel is CONFIRMED (`Guide` reason), exactly as B would have
  found it; so B's FTS is needed only for channels A did not find;
- a sport word ("NHL", "Hockey", "LNH", "Hockey Night"; NFL "NFL", "Football") but no team names: the network is
  confirmed, not the game: LIKELY with the `SportListing` reason, never CONFIRMED;
- the airing covering the scheduled start names two other teams: dropped (§5.3).
Generic sport words never go through `EpgReader.search`, whose start-ordered per-text caps would fill with "NHL Tonight"
airings from now and miss a game later tonight or days ahead. (`epg_fts` indexes title and subtitle only; the window
read looks at the same two fields.)

### 5.3 Confidence, ranking and wording (U-S10)

| Level | Rule | Wording (sheet and channel line, 22/500) |
|---|---|---|
| CONFIRMED | B matched | "In your guide: NHL Hockey: Golden Knights at Oilers, 9:00–11:30 PM" |
| LIKELY | A matched a national code (country agrees or is the channel's tag), or C's window read found a sport listing without team names on an A channel | network: "Sportsnet carries this game in Canada. Your guide doesn't list it on this channel." · sport listing: "In your guide: NHL Hockey at 7:00 PM. The listing doesn't name the teams." |
| POSSIBLE | A matched a regional (H / A) code or TSN / RDS, an untagged channel one level down, or an unknown code | regional: "Home team's local channel. It may show a different game where you live." · other: "May carry this game. Check the channel." |
| dropped | the channel's airing that covers the scheduled start names two other teams | not shown |

Order: confidence, favourite, watch count, national before regional, playlist order, provider order. Quality variants of
one residue collapse to the best-ranked channel ("Also: FHD, 4K" on its row). At most 8 candidates (`HUB_MATCH_MAX`). The
sheet always ends with "Regional channels may show a different game where you are."

Channel line (§1.3): the top candidate's name and a short reason ("in your guide", "national broadcaster", "local
channel, may differ") and "+n more". Upcoming games (start more than 30 min away): "On TV at 7:00 PM: Sportsnet Ontario".

### 5.4 Hidden, locked, several playlists, nothing found, cache

- Hidden channels and groups never appear (lineup predicate). Locked ones follow `ParentalAccess` as Search does; tuning
  still goes through the `TuneGuard`.
- All playlists are searched; rows show the playlist name when candidates come from more than one.
- Nothing found: slot 2 disabled "Channels"; channel line "No channel found in your playlists".
- Cache (A-S5): key `(game id, ParentalAccess, EPG generation)`, LRU 24, and `clear()` on every page open and on any
  access change (relock), so hide / lock / rename edits made between visits are always seen.

### 5.5 What selecting does

Slot 2 **Watch** (one CONFIRMED candidate, live or starting within 30 min) tunes it; otherwise slot 2 opens the Channels
sheet (§6.4 geometry): rows 96 tall with the channel logo 96 × 54, name 26/600, the reason 22/500 `Fg70`, the playlist
line. OK on a row of a live or imminent game tunes it. For a game more than 30 min away the rows are informational; OK
shows the toast "The game starts at 7:00 PM. Come back then to watch it." (U-S10).

Tuning:
1. `SportsViewModel` emits `SportsEffect.Tune(channel)`.
2. `GuideViewModel.onSportsEffect` runs `guarded.tune(TuneTarget.Channel(identity), lineup zap list)` (PIN prompt on a
   refusal via `onRefused`), then dispatches **`GuideEvent.SportsClosed(channel, tuned = true, archive = false)`**, a new
   event added in commit 0 (A-S6). The existing BACK path (`BoxAction.BACK` → `onSportsClosed()`) stays as it is.
3. `GuideStateMachineImpl` handles `SportsClosed` as `onSearchClosed` handles a tuned channel: focus the channel in the
   grid and go FULL on its first `Tuned`.

It does **not** follow the game. Back from full screen is the player's normal Back (to the guide), not to Sports (U-N3;
recorded in the decision note).

### 5.6 Catch-up of a finished game (kept, lead ruling 3)

When B found an ended airing inside the channel's archive, the candidate carries the airing:
- final NHL: the Goals sheet ends with "Full game from the start · Sportsnet Ontario (catch-up)" rows;
- final NFL / CHL: slot 1 "Watch from start" (one row: plays it; several: the Channels sheet with those rows).

OK emits `SportsEffect.PlayArchive(channel, info)` → the existing `effects.playArchive` path (Search's) and
`SportsClosed(channel, tuned = true, archive = true)`. **Why it stays:** the launch is Search's existing path, the airing
comes from query B that runs anyway, and nothing in it touches the clip resume; A-B2's archive resume (§6.6) is a separate,
equally existing route. Cost: one sheet row and one effect.

### 5.7 Cost on a 20,000-channel playlist (Fire TV Stick)

Per game: up to 6 channel-FTS phrase queries (`LIMIT 200` pages, up to 5 per code after the 2026-11-25 review, M3;
usually 1), one renamed-channel read, 2 live + 2 past team-name EPG FTS queries
(B), one `EpgReader.window` read for A's channels (C, R-S4), one `channelsForRefs` batch. Estimate 30–150 ms on IO. Runs only for the panel's game (after rest or OK), cancelled on
move, cached (§5.4). **WP-3 measures p50/p95** on the 20 k synthetic playlist on the Fire TV Stick with the final query
shape (A-N8); gate p95 ≤ 250 ms, else the alias lookups become one `UNION`. WP-3 also records the B / C hit rate against
the fake panel's EPG and one real XMLTV.

---

## 6. Video (route A)

### 6.1 Contract

`HubContracts.kt`:

```kotlin
enum class ClipKind { RECAP, CONDENSED, GOAL }
data class ClipRef(val videoId: Long, val kind: ClipKind, val gameId: GameId)
/** Metadata; cached ≤ HUB_CLIP_META_TTL_MS in memory; URLs are signed and never persisted. */
data class ClipInfo(val ref: ClipRef, val title: String, val description: String?, val durationMs: Long?,
                    val posterUrl: String?, val thumbnailUrl: String?)
/** From a lookup made just before playing; never cached, stored or logged. */
data class ClipStream(val url: String, val format: ClipFormat, val captionsUrl: String?) {
    override fun toString() = "ClipStream(${Redact.host(url)}, $format)"
}
enum class ClipFormat { HLS, MP4 }
sealed interface ClipResult<out T> {
    data class Ok<T>(val value: T) : ClipResult<T>
    data object NotFound : ClipResult<Nothing>
    data object Geo : ClipResult<Nothing>
    data object Refused : ClipResult<Nothing>
    data class Failed(val kind: FailKind) : ClipResult<Nothing>
}
interface ClipResolver {                         // :data NhlClipResolver
    suspend fun info(ref: ClipRef): ClipResult<ClipInfo>
    suspend fun stream(ref: ClipRef): ClipResult<Pair<ClipInfo, ClipStream>>
}
/** What ClipSession resumes (§6.6). */
sealed interface ResumePoint {
    data object Nothing : ResumePoint
    data class Live(val identity: ChannelIdentity) : ResumePoint
    /**
     * (R-N3) [rawEndMin] is derived from [programme] (`rawStartMin + (endMin − startMin)`; `ArchiveStatus` has none);
     * [timeShift] is `ArchiveStatus.timeShift`, so a session started from live pause or rewind comes back with −MM:SS,
     * not the CATCH-UP pill; [playheadMs] is re-read from `status.atMs` just before `playClip`, not before the resolve.
     */
    data class Archive(
        val channel: GuideChannel, val programme: ProgrammeInfo, val rawEndMin: Int, val timeShift: Boolean, val playheadMs: Long,
    ) : ResumePoint
}
```

`ui/.../sports/SportsEffect.kt` also defines (commit 0, R-S5):

```kotlin
/** What AppRoot reads about the clip layer (§6.5). */
@Immutable
data class ClipUi(
    /** The layer is up and the hole is FULL (true only after `playClip` returned). */
    val showing: Boolean = false,
    /** The clip cycle's tune id; AppRoot keeps the hole black until a frame renders after it. */
    val tuneId: Long = -1,
)
```

`player/.../api/PlaybackApi.kt` (uses the `:core` `ClipFormat` directly; no re-export, A-N5):

```kotlin
data class ClipTune(val url: String, val format: ClipFormat, val captionsUrl: String?, val title: String, val startMs: Long = 0) {
    override fun toString() = "ClipTune(${Redact.host(url)}, $format)"
}
interface PlaybackController {
    /** Plays [tune] on the one player; returns the clip cycle's tune id. Default for fakes: -1. Nothing about resuming (A-B2). */
    fun playClip(tune: ClipTune): Long = -1
}
// NowPlaying, appended: val isClip: Boolean = false
```

`core/.../playback/PlaybackContracts.kt` (R-S5): `PlaybackErrorCode` gains `CLIP_EXPIRED` (a 403 / 410 on the clip's
media) with `defaultSteps = listOf(NextStep.RETRY)`. Because `ErrorText.headline` is an exhaustive `when`
(`ErrorText.kt:20-46`) and `ErrorTextTest`, `PlayerOverlayBoundsTest` and `PlayerFiveKeyReachabilityTest` iterate
`PlaybackErrorCode.entries`, commit 0 also adds the `ErrorText` arm and its two strings (headline "The video link
expired.", step "Select Try again to load it again.") next to the existing error strings, so every per-code test passes.
`ClipSession` normally handles the code itself (one re-resolve, §6.6); the error card text is the fallback.

The guarded controller is `PlaybackController by inner` (`ParentalGuideLibrary.kt`), so `playClip` passes through without a
change (A-S6.6).

### 6.2 The key (`NhlVideoKeySource`, `:data`; A-S4, A-N6)

- Source: `https://players.brightcove.net/6415718365001/default_default/index.min.js` (constants in
  `NhlVideoEndpoints`; the key never is). **https only**; no `Origin`, no `Referer`.
- **Memory only.** Read once per process on first need (the first `info()` or the first Play); no file, no backup or
  export concern.
- Scan: `body.source()` (OkHttp's transparent gzip, so the limit counts decoded bytes) wrapped in a 2 MB limit
  (`HUB_VIDEO_SCRIPT_MAX`); `BufferedSource.indexOf` for each of `policyKey:"`, `policyKey:'`, `"policyKey":"`
  (whitespace around `:` allowed); read the value to the closing quote; a candidate that is not 20–400 chars of
  `[A-Za-z0-9_\-.]` (an empty default, a placeholder) is skipped and the scan **continues** to the next candidate.
- On read: `Redact.registerSecret(key)` at once, so the value is masked in any log, message or throwable text.
- A 401 or 403 (not `CLIENT_GEO`) from the playback service → drop the key, re-read once, retry the lookup once; still
  refused → `Refused`.
- **Hold** (`HUB_VIDEO_KEY_REREAD_MS`, 10 min) after a read that found no key, a refused re-read, or a script answer
  that means "not here" (404, a redirect, an address outside https): `Refused` meanwhile, and the page hides its video
  slots for the same 10 min (§6.8).
- **Backoff** after a transient script failure (timeout, IO including offline, 5xx, 429, another status): 30 s
  (`HUB_VIDEO_KEY_BACKOFF_MIN_MS`), doubling to 1 min (`HUB_VIDEO_KEY_BACKOFF_MAX_MS`), with `Retry-After` as a floor
  (at most 10 min); the failure's kind is answered meanwhile, so the toast is §6.8's "Try again in a minute", which the
  1 min cap keeps true. A success resets the backoff. *(Code review 2026-11-25, M1: a Fire TV waking before its Wi-Fi
  used to turn video off for 10 minutes under a "try again in a minute" toast.)*
- `BoxLog` says "video key: read" / "video key: not found" / "video key: refused"; never a value, prefix or length.
  Tests and the fake panel use a fake key only.

### 6.3 Lookups (`NhlClipResolver`)

- `GET https://edge.api.brightcove.com/playback/v1/accounts/6415718365001/videos/{id}` with
  `Accept: application/json;pk={key}`, **https only**, `@ApiClient`, call timeout 10 s, body cap 64 KB
  (`HUB_VIDEO_BODY_MAX`), decoded by `BrightcoveMapper` (`:core`, pure).
- Fields: `name`; `long_description` (else `description`); `duration` (ms); `poster`, `thumbnail`; `sources[]`;
  `text_tracks[]` (`kind == "captions"`, `srclang == "en"`).
- Source order: https HLS without `key_systems`; https MP4 with height ≤ 720; then the http variants in the same order
  (cleartext is allowed app-wide; media only, the key never travels there); DASH and anything with `key_systems` ignored.
  None left → `Failed(BAD_BODY)`.
- `Redact.registerTransient(token)` for the signed query token of each `ClipStream` URL and caption URL as soon as the
  lookup returns (A-S4).
- `info()` caches `ClipInfo` 10 min (`HUB_CLIP_META_TTL_MS`), LRU 40; `stream()` always looks up again.
- Images: Coil requests with `memoryCacheKey` / `diskCacheKey` = `"nhl-clip:{videoId}:{w}x{h}"` (signed URLs change per
  lookup), size = the drawn size. Normal devices: hardware bitmaps (8888, ≈ 560 KB for 664 × 212). Low-end
  (`DeviceClassSource.lowEnd`, 1 MB Coil memory cache): the `thumbnail` instead of the poster, `bitmapConfig(RGB_565)`,
  `allowHardware(false)` (A-N3).

What the panel asks for (only the panel's game; after rest or OK):

| Phase | `info()` lookups | Used for |
|---|---|---|
| Final | recap; the game-winning goal's clip | poster + caption; the summary sentence |
| Live, intermission | the latest goal's clip | poster + caption; the summary sentence |
| Others | none | |

At most two lookups (≈ 3 KB gzip each) per game, cached 10 min.

### 6.4 Sheets (shared frame) and the Goals sheet

Frame for every sheet: over the right column, x 856, y 148, w 968, h 812, radius 24, `OverlayFill` + 1 px
`OverlayBorder`, padding 28; title 32/600 ("Goals · UTA at WPG"); a `LazyColumn` of focusable rows from y 84.

**Goals sheet** (goals first, U-B2 / owner ruling):
1. **Goals** in game order, 96 tall, 16 gap: the scorer's headshot 64 round (from the feed), line 1
   "G. Vilardi (4) · PP" 26/600 (tags PP, SH, EN; nothing at even strength), line 2 "1st 5:11 · WPG leads 1–0 ·
   Connor, Morrissey" 22/500 `Fg70` ("Tied 2–2" when tied), right: "▶" or the duration once known. A goal without a clip
   id: line 2 ends "· Video soon", row dimmed; OK shows "This goal's video isn't posted yet. It usually appears a few
   minutes after the goal."
2. **Game recap** (final): thumbnail 128 × 72, "Game recap", "4:55".
3. **Condensed game** (final): "Condensed game · 9:50".
4. **Full game from the start** rows (final, catch-up airing found, §5.6).

From a goal row the queue is that goal and every later goal with a clip; from the recap or condensed game, that clip alone.

**Box score sheet** (U-N7): two tab chips "Away team" / "Home team" (team nicknames) at y 84; Left / Right on the tabs
switch; Down enters the rows. Skaters table: Player 26/500 (name, number, position), G, A, P, +/−, SOG, TOI (22/500
tabular, columns right-aligned), rows 44 tall, focusable for scrolling, forwards then defence; then a "Goalies" header
and rows SA, SV, SV%, TOI. Retry row on failure (§4.5).

**Matchup sheet** (pre-game NHL): "Leaders · last 5 games" three rows (Points, Goals, Assists), each with the away and home
leader: headshot 72 round, name 24/600, value 26/700; then "Goalies · this season": up to two per team with headshot 64,
record, GAA, SV%.

**Channels sheet**: §5.5.

### 6.5 Clip player (`ClipPlayerLayer`, `ClipSession`, `:ui/sports`)

**Where it plays** (A-S2). Full screen on the one `ExoPlayer` and the one root `SurfaceView`, mode stays
`ScreenMode.SPORTS`:
- `AppRoot` gets a `sportsClip: State<ClipUi>` parameter (commit 0, wired in `MainActivity` in commit 0).
  `holeModeFor` returns FULL for SPORTS while `ClipUi.showing`.
- `beneath[0]` is **not** updated while the FULL hole comes from a clip, so after the clip the live channel gets back the
  viewport it had under the page.
- `ClipUi.showing` turns true only after `playClip` returns; from then the hole is drawn black (scrim 1) until
  `VideoInfo.renderedFirstFrames` moves or `STILL_TO_FULL_MAX_WAIT_MS` passes, so neither the live picture nor its last
  frame flashes.
- `SubtitleLayer` is composed for SPORTS while a clip shows, as for FULL.
- The root draws no `Canvas` box while a clip shows; `SportsScreen` composes `ClipPlayerLayer` (transparent; transport bar
  at the bottom) and keeps the page's state, including an open Goals sheet, without drawing it.
- `ClipPlayerLayer` calls `onBackFreeChanged(false)` for its whole life, so Back never reaches `onSportsClosed()`.
- No `graphicsLayer`, clip or alpha on any ancestor of the surface; `RootTreeRuleTest` gains the clip state.

**Controller branches for `Spec.Clip`** (A-B1; `PlaybackControllerImpl.kt`). `Cycle.isVod = isArchive || isClip`;
`NowPlaying.isArchive` stays false for clips, so `ArchivePlayback` and `ArchiveVisibility` never take a clip as catch-up.

| Branch (line) | Live | Archive | **Clip** |
|---|---|---|---|
| `startCycle` (`:704-717`) | ring kept on zap; `previousIdentity = old` | ring discarded | `closeTimeshift(old, discardFile = true)`; **`previousIdentity` not updated when either the old or the new cycle is a clip** (A-B2.4) |
| resolve (`when`, `:1046`) | `StreamUrlResolver` | archive request | no resolver: a local `ResolvedStream` (identity null, `brokerKey = "clip:" + host`, url, default UA only, format HLS or progressive, no Xtream) |
| broker configure (`:879`) | playlist key | playlist key | `clip:<host>` configured with `HUB_CLIP_MAX_CONNECTIONS` (4); never the provider's key (A-S1) |
| session context (`:905-916`) | broker lease, `transport.http(ctx)` | same | same transport and lease for the clip key, so `TuneSession.end()` cancels segment calls on Back or a zap (A-S1). Range requests pass through for VOD as for archive (WP-2 verifies on the MP4 path). |
| `beginSession` (`:953`) | `isLive = true` | `false` | `false` |
| `applySwitches` (`:1088-1122`) | settings + channel memory | buffer MEDIUM | buffer MEDIUM; tunnelling **off**; global decoder setting, no channel override; **no `setChannelAfr`** |
| display mode (`:961`, `setViewport` `:2110-2116`) | `onTune`, `awaitSettled`; FULL turns AFR on | same | **skipped**: no `onTune`, no `awaitSettled`, and `setViewport` leaves the display mode alone while the cycle is a clip; the resumed channel gets its own `onTune` |
| `STATE_ENDED` (`:1325-1331`) | `LiveEnded` → ladder | `Playing(paused, ended = true)` | as archive |
| `pause` / `resume` (`:1943-1986`) | live-pause job, 10 s release, `LiveResumed` | real pause | as archive (no live-pause job, no `LiveResumed`) |
| `onRebuffer` (`:1337-1349`) | buffer FSM, `BufferIncreased` | same | no FSM bump, no notice |
| classifier (`:1382-1392`) | live facts | archive facts | `isVod`, `isClip` facts; a 403 / 410 on media → `fail(CLIP_EXPIRED)` |
| `onFailure` / `execute` (`:1714-1830`) | ladder (tunnelling, decoders, UA, re-resolve, failover; learning persisted) | ladder | **`ladder.decide` skipped**: IO or HTTP 5xx → at most 2 `launchSession(reuseResolved)` restarts at the playhead, then `fail()`; nothing written to channel memory or settings |
| watchdog `onStall` (`:1401`) | live expectations | archive | archive expectations; a stall verdict takes the clip restart rule above |
| `stopForLifecycle` / `resumeAfterLifecycle` (`:2057-2079`) | resumable | resumable | the clip cycle stops **not resumable**; `resumeAfterLifecycle` has nothing to replay; `ClipSession` ends (§6.6) |
| `specForRestart` | target | tune at playhead | clip at playhead |
| `retry()` / `resume()` (`:668-674`, `:1977-1986`) | restart the cycle | same | **never restart a clip cycle on their own** (R-S1): while the current or last cycle is a clip, `retry()` and a `resume()` after a stop are no-ops; only `ClipSession` restarts a clip (Try again re-resolves and calls `playClip`). `resume()` after a user pause of a playing clip resumes as archive. |
| other `when (spec)` (`:3250`, diagnostics) | — | — | a `Clip` arm ("clip", host only) |
| time-shift writer, recordings | per settings | no | never |
| `zap` / `previousChannel` during a clip | — | — | not reachable from the page (the layer consumes those keys); from elsewhere they act on the zap list as usual and end the clip by ownership (§6.6) |

`NowPlaying` for a clip: identity null, `isClip = true`, title = the clip title. `GuideViewModel` and `PlayerViewModel`
filter clip states before `TunedStateMapper` and before the `parentalControls.watching` collector (`GuideViewModel.kt:664`)
(A-B2.5, A-N5, R-S1):
- **active** clip states (`Connecting`, `Buffering`, `Playing`, `Stalled` whose target `isClip`) are **dropped**: while
  the clip plays the machines keep the pre-clip channel, so `bindTunedFrom` and the score bug are untouched and a resume
  of the same channel is not an identity change;
- **terminal** clip states (`Stopped`, `Error`, `Idle` after a clip) are **forwarded as `PlaybackState.Idle`**, so after a
  clip that ends without a resume (Home or standby mid-clip, `ResumePoint.Nothing`, an ownership loss) the machines see
  "nothing tuned", which is the truth: no stale PLAYING on the mini player, no OK that goes FULL without tuning, no guide
  Retry aimed at the clip (and `retry()` would not restart it anyway, table above).
- Recents and watch counts are recorded inside the controller by identity (`recordWatchedJob`,
  `PlaybackControllerImpl.kt:1466`); the clip's null identity already excludes them, so the filter is not what protects
  them.

(Departure from A-B2's "the guide machine ignores an isClip Tuned": one filter at the two collectors instead of guards
inside the 3 400-line machine; same effect; §12. This rule is part of WP-3's spec before WP-3 starts; it changes no
commit-0 contract.)

**Captions** (A-S3): WP-2 checks the recorded master playlist (§10.5). If it lists an `EXT-X-MEDIA TYPE=SUBTITLES`
rendition, the clip uses it and selects it through the existing `applyTuneDefaults(…, subtitlesEnabled)`; nothing is
side-loaded. If not, the clip branch builds `MergingMediaSource(hls, SingleSampleMediaSource(vtt))`. The MP4 fallback
always merges. On / off follows the player's subtitles setting (lead ruling 3).

**Keys** (the layer owns them):

| Key | Effect |
|---|---|
| OK, Play/Pause, Play, Pause | pause / resume |
| Left / Right | −10 s / +10 s; held: repeats every 300 ms |
| Rewind / Fast-forward | −30 s / +30 s |
| Channel down / up, Media previous / next | previous / next clip in the queue |
| Up / Down | show the transport bar |
| Menu | captions on / off for this clip (when it has them) |
| Back | end the clip session with resume (§6.6), back to the page: the Goals sheet with focus on the clip just played, or the slot that started it |

**Transport bar** (y 852 → 1024 over a static black gradient 0 → 75 %): title 32/600, sub-line 24/500 `Fg70` ("Goal 1 of
6 · UTA at WPG · 1st 5:11"; "Game recap · UTA at WPG"), progress bar 1728 × 6 at y 990, times 22 mono, hints 22 ("OK
Pause · ← → 10 s · Back Done"). Shows 4 s at start and on any key; stays while paused or buffering.

**End.** After a queued goal: "Up next: Connor scores" for 1.5 s, then the next clip is resolved and played. After the
queue or a recap: the session ends with resume.

### 6.6 `ClipSession` (`:ui/sports`, owned by the Sports view model; A-B2)

States: `Idle` → `Resolving(ref, job)` → `Playing(ownedTuneId, queue, index)` → `Idle`.

- **Snapshot before anything changes.** On the first OK of a session, before the resolve, it records a `ResumePoint`:
  - catch-up from the panel's archive (`ArchivePlayback.status` active, `source == PROVIDER`, owned by the current tune):
    `Archive(channel, programme, rawEndMin = programme.rawStartMin + (programme.endMin − programme.startMin),
    timeShift = status.timeShift, playheadMs)`, with `playheadMs = status.atMs` **re-read just before `playClip`** (the
    catch-up keeps playing during a resolve of up to 10 s; R-N3);
  - the device's own time-shift (`source == LOCAL`) or any live target with an identity: `Live(identity)` (the ring is
    discarded by the clip, so a local rewind resumes at live; stated in the decision note);
  - `Idle`, `Stopped`, `Error`, nothing tuned: `Nothing`.
- **Resolve** runs in the session's scope: cancelled on Back, on leaving the page, on a new OK, and on any ownership loss.
  `playClip` is called only if the session is still current; its returned tune id becomes `ownedTuneId`. A next clip in
  the queue updates `ownedTuneId`; the `ResumePoint` is kept.
- **Ownership.** The session watches `controller.state`. When the target's `tuneId` passes `ownedTuneId` and the target
  is not a clip (an intent, a launcher or Watch Next tune, a PIN success tune, `LiveSettingsApplier`, any other tune),
  the session ends **without resuming** and the layer hides. A `Stopped` on the clip target (lifecycle: Home, standby,
  HDMI) ends it the same way; on return nothing replays (owner question 4 offers resuming).
- **Resume** on Back, end of queue or end of a recap: emits `SportsEffect.Resume(point)`; `GuideViewModel` runs it through
  the public paths:
  - `Live(identity)`: `guarded.tune(TuneTarget.Channel(identity), lineup zap list)`; a refusal opens the PIN prompt
    through `onRefused`;
  - `Archive(…)`: `archive.playProgramme(channel, programme, rawEndMin, timeShift)` then the seek to `playheadMs` (the
    route Search's `PlayArchive` takes, plus the seek), so the resumed catch-up is a full `ArchivePlayback` session with
    continuation, programme-frame scrub and variant fallback. A `seekTo` issued before the new session's first media is
    planned without a position (`ArchivePlaybackImpl.kt:458-469`), so WP-3 either confirms that path lands on
    `playheadMs` (test) or issues `seekTo(playheadMs, seq)` on the session's first `PLAYING` status (R-N3);
  - `Nothing`: `controller.stop(USER)`.
- **Expired URL**: `CLIP_EXPIRED` → one re-resolve and `playClip` with `startMs` = the playhead; a second failure shows
  the error card.
- **View model cleared mid-clip**: `onCleared` cancels the resolve and ends the session **without resuming** (R-N1): an
  effect emitted there would not be delivered (`MainActivity`'s collector runs in a `lifecycleScope` that is already
  gone), and the activity's stop has already stopped the player (`BACKGROUND`). `ClipSessionTest` asserts exactly this.
- While `Playing`: `DemandOwner.SPORTS_TAB` off and `GameDetailRepository.pause(true)` (§4.4).

### 6.7 Poster, durations and the summary (U-S8)

- Final panel: recap poster and "Game recap (m:ss)"; live panel: the latest goal clip's poster and `name`.
- Final, line 1: "Game-winning goal · " + the winning goal clip's `long_description`, wrapping to two lines; without it:
  "Game-winning goal · Vilardi (4th) from Connor and Morrissey, 1st period 5:11." Shootout: "Won in the shootout." The
  winning goal is the winner's goal number (loser's final score + 1) in game order.
- Final, line 2 (drawn only when line 1 used one line): "Stars: Skinner, Vilardi, Connor".
- Live: line 1 "Latest goal · " + that clip's `long_description` or the built sentence; line 2 "Periods · 1st 2–3 ·
  2nd 0–0 · 3rd 0–1" (away–home).
- Pre-game: line 1 "Season series · EDM leads 1–0" / "First meeting this season"; line 2 "Points leaders (last 5) ·
  Eichel 5 · McDavid 6".
- The generic recap `long_description` is never used.
- Every phase's lines are measured in `HubPanelFitTest` with the fixtures.

### 6.8 Errors (reason + next step)

| Situation | Where | Text |
|---|---|---|
| Lookup timed out / 5xx / IO (or the key's script, inside its ≤ 1 min backoff, §6.2) | toast | "The NHL video service didn't answer. Try again in a minute." |
| Offline | toast | "No internet connection. Videos play again when the network is back." |
| `Refused` | toast; video slots and "● Highlights" chips hide for 10 min | "NHL videos aren't available in this app right now. Scores and stats still work." |
| `Geo` | toast | "This video isn't available in your region. The scores and stats still work." (U-S11) |
| `NotFound` | toast | "The NHL removed this video. Pick another clip." |
| No playable source | toast | "This video can't play on this device. Scores and stats still work." |
| Playback error mid-clip | centred card over the clip (the player's error-card look): **Try again** / **Back** | "The video stopped loading. Select Try again, or press Back to return to the game." (Try again re-resolves.) |
| Clip id missing | toast | §6.4 |

---

## 7. NFL and CHL on the new layout (lead ruling 2, U-S7)

### 7.1 What they get, from data already fetched or parsed

Same title row, heading, strip, grid, cards and panel. **No new NFL or CHL request.** `BoardExtras` (§4.1) is filled by
the existing mappers from bodies the app already receives:

| Field | NFL (ESPN scoreboard, already fetched) | CHL (HockeyTech `gamesbydate`, already fetched) |
|---|---|---|
| Records | `competitors[].records[0].summary` | — |
| Periods | `competitors[].linescores[].value` by `period` | — (the board has none) |
| Leaders | `competitions[].leaders[]` (passing / rushing / receiving yards: athlete short name, `displayValue`) | — |
| Shots | — | `home_shots` / `visiting_shots` (read today by `HockeyTechMapper`) |
| Broadcasts | `competitions[].broadcasts[] {market, names[]}` (§7.2) | — |

The ESPN fields are **unverified against a recording** (the fake panel's `linescores` are synthetic). WP-1 reads them as
`JsonElement?` by path (A-N4: an absent or odd field can never fail a board) and must verify each against a recorded
board (§10.5 lead task 2) before the panel relies on it; an unverified field ships disabled.

| Region | NFL | CHL |
|---|---|---|
| Cards | §1.2; upcoming pill with weekday (U-S6) | §1.2; "Time TBD" when `startTbd` |
| Media area | matchup art | matchup art (logos through `ChlLogoCheck`) |
| Stats block | header "BY QUARTER": Q1–Q4 (+OT) and T as rows when `periods` exist; live adds "Possession", "Down" ("2nd & 7 at OPP 35") and "Timeouts" from `LeagueDetail.Nfl`; pre-game: "Record" | live / final: "Shots"; a followed live game also gets "Power play" from its snapshot |
| Summary | final / live: "Passing · Mahomes 278 · Murray 201"; "Rushing · …" (two lines) | none |
| When nothing is available | the panel shrinks to pill, headline, art and slots, with one line 22 `Fg55`: "More game details come for the NHL first." | same |
| Slots | Follow · Watch / Channels · – ; final: Watch from start (catch-up) | same |

NFL and CHL have no video and no box score in this version (owner question 5 says so plainly).

### 7.2 Broadcasters for the NFL

From the board already fetched: `broadcasts[].market` "national" → NATIONAL, "home" / "away" → HOME / AWAY; country US
(ESPN's US scoreboard). Absent → guide titles only. CHL: guide titles only.

### 7.3 Regressions guarded

- NFL weeks keep S6, the week label, the league chips and the CHL sub-switch.
- Follow and its toasts are unchanged for every league (it is now slot 1 instead of OK on the card).
- The `sportsHashGate` goldens are re-recorded; the old `GameCard` and its tests are removed in WP-4.

---

## 8. Strings, accessibility, performance

### 8.1 Strings (commit 0; new `ui/src/main/res/values/strings_sports_hub.xml`; American spelling; frozen after commit 0)

Key groups (`sports_hub_*`): title; heading ("%1$s SCORES & SCHEDULE"); day labels; pills (incl. NFL weekday forms);
stat labels (NHL game and season, NFL "Q1"…"T", "Possession", "Down", "Timeouts", "Record", CHL "Shots", "Power play");
ordinals; summary labels ("Game-winning goal", "Stars:", "Latest goal", "Periods", "Season series", "Points leaders (last
5)", "Passing", "Rushing", "Receiving"); slots ("Follow", "Following ✓", "Watch", "Channels", "Channels (%1$d)",
"Finding…", "Goals", "Goals (%1$d)", "Watch recap", "Recap soon", "Box score", "Matchup", "Watch from start", "Retry");
channel line and sheet wording (§5.3, §5.5); card chips; sheet titles and rows; every error text (§4.5, §6.8); footer
hints; content descriptions (§8.2); "More game details come for the NHL first." The page-only Corner / Delay strings are
deleted from `strings_sports.xml` (the quick bar's are kept).

### 8.2 Accessibility (TalkBack / VoiceView; U-S13)

- Traversal: title row, strip, grid, panel, footer.
- Card: one merged node, e.g. "Utah Mammoth 2, at Winnipeg Jets 4. Final. Following. Press OK for options: Watch recap,
  Goals, Box score."
- Panel: a focus group with `paneTitle` "Game details: Mammoth at Jets", announced when focus enters it; one node per stat
  row ("Shots on goal: Mammoth 28, Jets 32"); slots with their labels and "disabled" for disabled ones. The panel is not a
  live region.
- Media area: "Game recap, 4 minutes 55" / "Matchup: Golden Knights at Oilers, 7 PM".
- Day chip: "Saturday, November 21, no games" when known empty.
- Clip player: controls described; progress "12 seconds of 46".
- Channel rows: "{name}, {playlist}. {reason}."

### 8.3 Performance and memory

- Drawn over the root surface as today; no blur; gradients and scrims are static brushes; no `graphicsLayer` on any
  ancestor of the surface.
- ≤ 16 cards; logos through the cards' negative cache. One poster, ≤ 10 headshots, sheet thumbnails at a time (§6.3).
- No request on focus moves (600 ms rest); none on strip focus.
- `onTrimMemory` ≥ RUNNING_LOW: detail LRU, clip-info LRU and match LRU cleared.
- `HubPanelModel` is `@Immutable`; a live re-poll recomposes the changed rows only.

### 8.4 Privacy and redaction

- Requests carry the IP, the UA and a date, a game id or a video id.
- The key: `Redact.registerSecret` on read; signed tokens: `Redact.registerTransient` per lookup (A-S4). `Redact.text`
  already strips query strings, so no new URL rule is added. `ExportRedactionTest` gains a case that logs a throwable whose
  message carries the key and a signed URL and asserts both are masked.
- Logs: path kinds and state changes; video lines name the video id and the result kind only.

---

## 9. Departures from the mock-ups (U-N1)

- Day chips are mixed case ("Yesterday · Nov 23"), not caps; the media tag reads "RECAP", not "RECAP & STATS".
- Cards carry "✓ Following" or "● Highlights", never an OK chip.
- The headline puts the away team first (the owner's "Away (n) vs. Home (n)"; the mock-up shows the home team first);
  owner question 2.
- The summary is the game-winning goal and the stars, not a written recap (none exists).
- Four cards and a peek instead of the mock-up's partial third column; owner question 3.

---

## 10. Tests, fake panel, recordings

### 10.1 Fixtures (`core/src/testFixtures/resources/sports/nhl/hub/`)

The scratchpad samples, copied by commit 0 with every signed query string replaced by `?redacted=1`: `score-2026-11-23.json`,
`score-now-2026-11-24.json`, `gc-2026020382-right-rail.json`, `gc-2026020382-landing.json`,
`gc-2026020382-boxscore.json`, `gc-2026020385-*.json`, `clip-recap.json`, `clip-condensed.json`, `clip-goal.json`. Live
fixtures come from the lead's recording (§10.5); until then the fake panel's shapes are synthetic and **WP-1's live-phase
mapper tests are gated on the recording before release** (A-S9).

### 10.2 Unit tests (JVM)

| Test | Covers |
|---|---|
| `NhlMapperHubTest` | broadcasts (trim, markets, unknown dropped), `GameMedia` ids, `gameCounts`; `NhlMapperTest` unchanged |
| `EspnBoardExtrasTest`, `HockeyTechBoardExtrasTest` | records, quarters, leaders, broadcasts, shots; absent / odd fields never fail a board |
| `NhlDetailMapperTest` | final and future fixtures in full; the recorded live fixture (gated); partial bodies |
| `DetailPollPolicyTest` | every phase, alignment, recap wait, back-off, pause, every "no request" condition, staleness |
| `BrightcoveMapperTest` | source order, DRM skipped, http fallback, captions, duration, nothing playable |
| `KeyScanTest` | synthetic scripts only: an empty default before the real candidate, quote and spacing variants, a match across a buffer edge, no match, over the 2 MB decoded cap, gzip body |
| `NhlClipResolverTest` | MockWebServer: one read per process, 401 → one re-read → retry, second 401 → `Refused`, hold after not-found and after a script failure, geo 403, 404, metadata cache, `stream()` uncached, https only, no `Origin` / `Referer`, a log capture finds no key and no token |
| `NhlDetailSourceTest` | parallel calls, landing failure → `failing`, caps, cancel |
| `BroadcastAliasesTest` | positives per code, incl. **"SN - Ontario" → SN, "TSN: 3" → TSN3, "CA\| SN ONTARIO FHD" → SN** (R-S2); **negatives** (A-B3): "Sportsnet 360" ≠ SN, "ESPN 2" ≠ ESPN, "UK: TNT Sports 1" ≠ TNT, "MSG Sportsnet" ≠ MSG, every Sportsnet channel ≠ SN+, "SNL" / "Snooker" ≠ SN; "CBC - Toronto", "ABC 7 New York", "FOX 5 WNYW", "CBS 2" match nothing (R-S3: affiliates are guide-only); no alias token is ever read as a country tag; country mismatch dropped; untagged lowered; streaming codes match nothing |
| `GameChannelMatcherTest` | in-memory Room + test `epg.db`: CONFIRMED (B, and C's window read) / LIKELY (network; sport listing from the window read, incl. a listing at 23:00 and one 3 days ahead behind 100 "NHL Tonight" airings, R-S4) / POSSIBLE / dropped; **`score-y.json` games 2026020379, 0380, 0382 produce no LIKELY candidate**; "US: ABC 7 NEW YORK" absent without a guide listing and CONFIRMED with one naming the game (R-S3); nickname collisions; variant collapse with 24 airings of one text; hidden; locked under both accesses; cache cleared on relock; two playlists; archive airing; empty |
| `SportsViewModelHubTest` | OK / Menu / Left enter slot 1 from both columns; frozen slots across a phase change; restore by action; frozen card order across re-polls; panel model id check; strip keys per league; NFL S6; failure states; `Tune` effect without follow |
| `ClipSessionTest` (A-B2) | snapshot live / provider catch-up (derived `rawEndMin`, `timeShift`, playhead re-read after a 10 s resolve, R-N3) / local time-shift / nothing; an intent tune mid-clip (no resume, layer hides); a lifecycle stop mid-clip; Back during the resolve (no `playClip`); a second OK during the resolve; catch-up resume through `ArchivePlayback` landing on the playhead; expired URL re-resolve; `onCleared` ends **without** resuming (R-N1) |
| `GuideStateMachineSportsTuneTest` | `SportsClosed(tuned)` → FULL on first Tuned; refused tune; archive; BACK path unchanged |
| `GuideViewModelClipFilterTest` (R-S1) | active clip states never reach `TunedStateMapper` or `watching`; terminal clip states arrive as `Idle`; `tunedFrom` and Last channel intact after a clip and resume; **Home mid-clip** (mini player not PLAYING, OK on X tunes X); **`ResumePoint.Nothing` after an error card** (machines see Idle); **Retry after a clip** (no clip cycle restarts) |
| `PlaybackControllerClipTest` (`:player`) | each row of §6.5's table: ENDED, pause over 10 s, rebuffer, IO twice then fail, 403 → `CLIP_EXPIRED`, stall verdict, lifecycle stop not resumable, AFR untouched, ring discarded, broker key `clip:<host>` and lease cancelled on end, `previousIdentity` kept, `retry()` / `resume()` after a clip stop do not restart it (R-S1) |
| `AppRootClipTest` | `beneath` unchanged after a clip; black hole until the first frame; `SubtitleLayer` composed; `RootTreeRuleTest` with the clip state |
| `HubCardFitTest`, `HubPanelFitTest` | §1.2, §1.4, §1.5, §6.7 at four sizes; headline scores never cut |
| Hash gate | card (5 phases × 3 leagues + refused), panel (each phase, loading, failure, NFL and CHL), sheets, clip bar |

### 10.3 Fake panel

- `tools/fakepanel/sports.py` (WP-1): score feed `tvBroadcasts`, `gameWeek`, goals with `highlightClip`,
  `threeMinRecap` / `condensedGame`; routes `gamecenter/{id}/right-rail` and `/boxscore`; landing `matchup` and
  `summary.threeStars`; one game per phase plus a postponed one; live shapes copied from the recording once it exists.
- `tools/fakepanel/sports_nfl.py` (WP-1): board `records`, real per-quarter `linescores`, `leaders`, `broadcasts`.
- `tools/fakepanel/sports_chl.py` (WP-1): board shots.
- `tools/fakepanel/sports_video.py` (WP-2): `/bc/player/index.min.js` (a small script with an empty `policyKey:""`
  default before `policyKey:"FAKE-KEY-FOR-TESTS"`), `/bc/playback/v1/accounts/{acct}/videos/{id}` (requires that key in
  `Accept`), media from `ffmpeg -f lavfi testsrc` into `tools/fakepanel/media/` (git-ignored) with a WebVTT track; admin
  `POST /admin/sports_video {"mode": "ok" | "refuse" | "rotate" | "geo" | "missing" | "slow" | "expire"}`; "expire" serves
  the manifest, then 403s segments after N s (A-S9).
- `tools/fakepanel/catalog.py` (WP-3): "CA| SPORTSNET ONTARIO HD", "CA| SN ONTARIO FHD", "SN - Ontario",
  "CA| SPORTSNET 360", "CA| TVA SPORTS", "US: TNT", "UK: TNT SPORTS 1", "US: ABC 7 NEW YORK", "US: ESPN 2", hidden
  "CA| SPORTSNET WEST", locked "US: NHL NETWORK". XMLTV for the featured game VGK @ EDM (broadcasters TNT, HBO MAX, SN,
  TVAS as in `score-now.json`): "NHL Hockey: Golden Knights at Oilers" on Sportsnet Ontario; a bare "Hockey LNH" on
  TVA Sports (a sport listing on a channel A matches, R-N4); nothing for the game on Sportsnet 360 (SN360 is not listed)
  or on ABC 7 (affiliates are guide-only and it has no listing, R-S3); a second game on the fake board lists ABC and ABC 7
  carries "NHL Hockey: Kings at Ducks" for it (guide-only CONFIRMED); a past airing inside a 3-day archive.
- `tools/fakepanel/fakepanel.py` (commit 0 only): route table entries and `DEFAULTS` for `sports_video` and the catalog's
  sports switches, so WP-2 and WP-3 never edit it.
- The debug override (`files/debug/sports_base`) also redirects the two video hosts to `{origin}/bc/...` (`SportsEndpoints`,
  WP-2).

### 10.4 Emulator check list (targeted states only; 1080p TV emulator, fake panel)

1. NHL today: initial focus on the live card; OK enters slot 1 (Follow); Right, Right; Back returns to the same card.
2. Right-column card: OK enters its own panel; Right from the last slot returns to it.
3. Four quick moves across cards: no detail request until rest (fake-panel stats); the panel header switches each move.
4. Final card: OK, OK plays the recap; Left / Right seek; Back returns to slot 1; live audio resumes; Last channel
   still goes to the channel before.
5. Goals sheet: play goal 2, auto-advance to goal 3, Back mid-clip lands on goal 3's row.
6. Video "refuse", "rotate" and "expire" modes: texts and recoveries.
7. Catch-up playing under Sports → recap → Back: catch-up resumes at its playhead.
8. Pre-game card: matchup art, six season rows, Matchup sheet.
9. Channels for VGK @ EDM: Sportsnet Ontario CONFIRMED with "Also: FHD" ("SN - Ontario" collapsed or listed under it);
   TVA Sports LIKELY (sport listing); US TNT LIKELY (network); Sportsnet 360, UK TNT, ESPN 2 and ABC 7 absent (R-N4,
   R-S3). For the Kings at Ducks game, ABC 7 CONFIRMED through its guide listing. Watch tunes, full screen, no follow
   started.
10. Locked NHL Network absent while locked, present after unlocking.
11. Day strip −7 and +7: one request per OK, none per focus; Up from the grid after scrolling the strip.
12. NFL This week and CHL OHL today: cards, panel extras, Follow in slot 1.
13. Largest text size: panel and card at final and pre-game.
14. Fire TV Stick (device): matcher timing on the 20 k playlist; a 10-minute condensed game without stalls; AFR "Always"
    shows no mode switch for a clip.

### 10.5 Lead tasks before real code relies on live shapes (A-S9)

1. **Record a real live NHL game**: VGK @ EDM, game **2026020385**, 2026-11-25 01:00 UTC. During the first period, an
   intermission, the third period and after the final: `score/now`, `gamecenter/2026020385/right-rail`, `/landing`,
   `/boxscore`; the playback lookup of one goal clip posted mid-game and of the recap after the final (scrub signed
   query strings); one HLS **master playlist** of a goal clip to settle captions (A-S3). Generic client, nothing about
   the user or project sent, the key never written down.
2. **Record one ESPN NFL scoreboard** with a live and a final game, to verify `records`, `linescores`, `leaders` and
   `broadcasts` (§7.1) before WP-1 enables them.

---

## 11. Work packages (file ownership disjoint; A-S6)

**Commit 0 (lead, Opus).** Owns and lands:
- `core/.../sports/SportsContracts.kt` (§4.1 appended fields and types), `SportsConstants.kt` (Appendix A), new
  `core/.../sports/hub/HubContracts.kt` (§4.1, §5.1, §6.1), new `core/.../sports/NhlTeamPlaces.kt`;
- `player/.../api/PlaybackApi.kt` (`ClipTune`, `playClip`, `NowPlaying.isClip`);
- `core/.../playback/PlaybackContracts.kt` (`PlaybackErrorCode.CLIP_EXPIRED` with `defaultSteps`, R-S5),
  `ui/.../playback/ErrorText.kt` (its arm) and its two strings in the existing player error strings file;
- `core/.../guide/GuideStateContracts.kt` (**adds** `GuideEvent.SportsClosed(channel, tuned, archive)`);
- new `ui/.../sports/SportsEffect.kt` (`SportsEffect` and `ClipUi`, R-S5);
- stub members in existing files (R-S5): `SportsViewModel.kt` gains `val clipUi: StateFlow<ClipUi>` (always
  `ClipUi()`) and `val effects: SharedFlow<SportsEffect>` (never emits); `GuideViewModel.kt` gains
  `fun onSportsEffect(effect: SportsEffect) = Unit`. WP-4 and WP-3 then own those files as before;
- the `NhlScoreSource.kt` refactor: `SportsEndpoints` → new `data/.../sports/SportsEndpoints.kt`, the transport → new
  `data/.../sports/NhlHttp.kt` (no behaviour change; `NhlScoreSourceTest` green);
- `ui/.../root/AppRoot.kt`: the `sportsClip: State<ClipUi>` parameter and its pass-through only;
  `app/.../MainActivity.kt`: both lines (`sportsVm.clipUi` into `AppRoot`, `sportsVm.effects` collected into
  `host.onSportsEffect`);
- `tools/fakepanel/fakepanel.py` (routes and `DEFAULTS` for every new module);
- stubs with frozen signatures under the WPs' file names (mappers, policy, sources, repository, resolver, key source,
  matcher, `ClipSession`, the four sheets and `ClipPlayerLayer`), bound in `SportsModule.kt` (bindings only);
- fakes in `core/src/testFixtures`: `FakeGameDetailRepository`, `FakeClipResolver`, `FakeGameChannelMatcher`;
- fixtures (§10.1); `strings_sports_hub.xml` (frozen).

| WP | Owns (files) | Model | Depends on | Size |
|---|---|---|---|---|
| **WP-1** detail and board data | `core/.../sports/NhlMapper.kt`, `EspnDto.kt`, `EspnMapper.kt`, `HockeyTechMapper.kt` (board extras only), `core/.../sports/hub/NhlDetailMapper.kt`, `DetailPollPolicy.kt`; `data/.../sports/NhlHttp.kt`, `NhlDetailSource.kt`, `GameDetailRepositoryImpl.kt`; their tests; `tools/fakepanel/sports.py`, `sports_nfl.py`, `sports_chl.py`, `test_sports.py`, `test_sports_nfl.py`, `test_sports_chl.py` | Opus | C0; lead tasks 1–2 for the live / ESPN gates | ~1 100 + ~900 tests |
| **WP-2** video | `core/.../sports/hub/BrightcoveMapper.kt`; `data/.../sports/NhlVideoKeySource.kt`, `NhlClipResolver.kt`, `NhlVideoEndpoints.kt`, `SportsEndpoints.kt`; `player/.../controller/PlaybackControllerImpl.kt`, `FailureClassifier.kt`, `player/.../engine/MediaSourceBuilder.kt`; `ExportRedactionTest` cases; their tests; `tools/fakepanel/sports_video.py`, `test_sports_video.py` | Opus | C0; lead task 1 (master playlist) for captions | ~1 100 + ~900 tests |
| **WP-3** channel link and guide side | `core/.../sports/hub/BroadcastAliases.kt`; `data/.../sports/GameChannelMatcherImpl.kt`; `data/.../guide/GuideQueries.kt` (`channelsByNameFts` only); `core/.../guide/state/GuideStateMachineImpl.kt` (`SportsClosed`); `ui/.../guide/vm/GuideViewModel.kt` (`onSportsEffect`, Resume, the `isClip` filter, `parentalControls.watching`), `ui/.../guide/vm/GuideEffectsImpl.kt` (archive resume), `ui/.../full/vm/PlayerViewModel.kt` (`isClip` filter); their tests; the 20 k benchmark; `tools/fakepanel/catalog.py` | Opus | C0 | ~800 + ~700 tests |
| **WP-4** page | `ui/.../sports/SportsScreen.kt`, `SportsViewModel.kt`, `SportsText.kt`, new `HubCard.kt` (replaces `GameCard.kt`, deleted with `GameCardFitTest`), `HubPanel.kt`, `MatchupArt.kt`, `DayStrip.kt`, `HubNames.kt`; `core/.../sports/hub/ChlTeamPlaces.kt`; `strings_sports.xml` deletions listed in §8.1 (one-time, by the lead's list); view-model and fit tests; goldens | Sonnet under this spec; Opus review | C0 | ~1 400 + ~800 tests |
| **WP-5** sheets and clip player | `ui/.../sports/GoalsSheet.kt`, `ChannelSheet.kt`, `BoxScoreSheet.kt`, `MatchupSheet.kt`, `ClipPlayerLayer.kt`, `ClipSession.kt`; `ui/.../root/AppRoot.kt` (hole, `beneath`, scrim, `SubtitleLayer`); `ClipSessionTest`, `AppRootClipTest`, `RootTreeRuleTest` case; goldens | Opus for `ClipSession` and `AppRoot`, Sonnet for the sheets | C0; WP-2 for the device check | ~1 100 + ~600 tests |
| **Integration + review** | merge; §10.4 pass; Fire TV Stick items; code reviews (data, player, guide side, UI); release gates (plan §6); §11.1 docs | Opus | all | — |

- No file has two owners after commit 0. `NhlScoreSource.kt`, `PlaybackContracts.kt`, `ErrorText.kt`, `MainActivity.kt`
  and `fakepanel.py`: commit 0 only. `AppRoot.kt` (WP-5), `SportsViewModel.kt` (WP-4) and `GuideViewModel.kt` (WP-3) are
  touched by commit 0 and then by one package each, in sequence. `sports_nfl.py` and `sports_chl.py`: WP-1.
- Commit 0 compiles and passes `:core:test :app:assembleDebug` and `lintAll` on its own (R-S5); each package then builds
  on commit 0 alone.
- R-S1 (the clip filter and `retry()` / `resume()` rule), R-S2, R-S3 and R-S4 are in WP-3's and WP-2's specs here
  before those packages start; they change no frozen contract.
- Work happens on branch `sports-hub`; `main` stays releasable (§13).
- WP-4 calls WP-5 only through the frozen composable signatures and `ClipSession`'s public API (stubs in commit 0).
- `SportsContracts.kt`, `HubContracts.kt`, `PlaybackApi.kt`, `GuideStateContracts.kt`, `SportsEffect.kt` and the strings
  are frozen after commit 0; changes go through the lead.

### 11.1 Docs to amend at integration

`docs/design/m6-score-bug.md` §5 (superseded); `docs/decisions/2026-11-05-nhl-score-bug.md` (Follow moves from OK on
the card to slot 1); `docs/design/m6-nfl.md` §7.3 and `m6-chl-leagues.md` §6.3 (Corner / Delay chips gone);
`docs/00-master-plan.md` M6 row; `tools/fakepanel/README.md`; `CLAUDE.md` is unchanged (clips use the broker, A-S1);
a decision note `docs/decisions/2026-11-24-sports-hub.md` with the owner's answers, Back-after-Watch (U-N3) and the local
time-shift resume (§6.6).

---

## 12. Review reconciliation (2026-11-24)

| Finding | Decision | Where |
|---|---|---|
| A-B1 `Spec.Clip` branches | Accepted as written (per-branch table, `isVod`, no ladder, no AFR, ring discarded, not resumable on lifecycle) | §6.5 table; §10.2 `PlaybackControllerClipTest` |
| A-B2 resume ownership | Accepted: `ClipSession` snapshot, tune-id ownership, guarded / `ArchivePlayback` resume, resolve cancel, `previousIdentity` kept. **One departure**: instead of the guide machine ignoring an `isClip` Tuned, `GuideViewModel` and `PlayerViewModel` drop `isClip` states before `TunedStateMapper`; one filter at the two collectors keeps the 3 400-line machine and the player machine unchanged and covers recents, watch counts, `parentalControls.watching` and the score bug in one place | §6.1, §6.5, §6.6; WP-3, WP-5 |
| A-B3 alias matching | Accepted: residue equality, country check, no streaming aliases, FTS4 phrases, `LIMIT 200` without `ORDER BY`, negative tests from the recorded games | §5.2, §5.3, §10.2 |
| A-S1 broker | Accepted: transport and broker with `clip:<host>` | §6.5 table |
| A-S2 AppRoot | Accepted in full (`beneath`, scrim, `SubtitleLayer`, wiring in commit 0, Back held, root tree test) | §6.5, §11 |
| A-S3 captions | Accepted: manifest check first, merge fallback | §6.5, §10.5 |
| A-S4 key | Accepted: continue past invalid candidates, decoded cap, `registerSecret` / `registerTransient`, memory only, hold on every failure, https only | §6.2, §6.3, §8.4 |
| A-S5 matcher cache | Accepted: key with `ParentalAccess`, clear on page open and access change | §5.4 |
| A-S6 WP split | Accepted: `SportsEffect` and `SportsClosed` in commit 0; `NhlHttp` / `SportsEndpoints` extraction; `MainActivity`, `fakepanel.py` in commit 0; `sports_nfl.py` to WP-1; guarded pass-through removed | §11 |
| A-S7 detail repository | Accepted: staleness, newest-`Date` score, demand off and pause under a clip, per-kind texts | §4.3, §4.4, §4.5 |
| A-S8 guide probe | Accepted: `perText` 40, sport listings raise to LIKELY only, hit-rate notes; the probe itself became a window read (R-S4) | §5.2 B and C, §5.7 |
| A-S9 tests | Accepted: "expire" mode, session and AppRoot tests, negative matcher set, live gate on a recording | §10.2, §10.3, §10.5 |
| A-N1 | Noted, unchanged | §4.2 |
| A-N2 | Accepted (FTS4 syntax) | §5.2 |
| A-N3 | Accepted (hardware on normal devices, RGB_565 + thumbnail on low-end) | §6.3 |
| A-N4 | Accepted (`JsonElement?` by path) | §4.1, §7.1 |
| A-N5 | Accepted (`:core` type used directly; `isClip` filter pins `TunedStateMapper` and `watching`) | §6.1, §6.5 |
| A-N6 | Accepted (no `Origin` / `Referer`, https only for key and lookup) | §0.3, §6.2, §6.3 |
| A-N7 | Accepted (TSN / RDS seeded, POSSIBLE at most) | §5.2 |
| A-N8 | Accepted with a variant: `searchPast` uses Search's existing `archiveDaysAll` span and the per-channel cut, because B runs before A's candidates are known; measurement uses the final query shape | §5.2 B, §5.7 |
| A-N9, A-N10 | Noted, unchanged | §5.2 |
| U-B1, U-B2 | Accepted through the owner's key-model ruling (§R) | §2 |
| U-B3 button fit | Accepted: channel in its own line, short fixed labels, glyph on slot 1, fit test. **Departure**: the "third button moves into a sheet at Largest" fallback is replaced by reducing padding to 20 and the gap to 12 at Larger / Largest; the estimates (§1.5) show three short labels then fit 664, and a slot that moves would break the owner's "never reorder" | §1.3, §1.5 |
| U-B4 headline | Accepted: scores never cut; names shrink, then nicknames, then ellipsize; fit test names | §1.3, §1.4 |
| U-S1 card bottom row | Accepted (no OK chip; chip gives way) | §1.2 |
| U-S2 wrong game / loading | Accepted | §1.3 |
| U-S3 reordering | Accepted (frozen order) | §1.2 |
| U-S4 buttons change | Accepted (fixed slots, frozen while focused, restore by action, match starts on OK) | §1.5, §2.3 |
| U-S5 day strip | Accepted | §2.2, §2.7, §3.1 |
| U-S6 NFL weekday | Accepted | §1.2 |
| U-S7 NFL / CHL panels | Accepted with lead ruling 2: filled from data already fetched or parsed, verified against a recording | §7.1, §10.5 |
| U-S8 summary | Accepted | §6.7 |
| U-S9 margins, footer | Accepted (56 / 56, panel 812, grid 280–960, footer `maxWidth`) | §1.1 |
| U-S10 channel wording | Accepted | §5.3, §5.5 |
| U-S11 error texts | Accepted | §4.5, §6.8 |
| U-S12 focus language | Accepted | §1.1 |
| U-S13 accessibility, refused | Accepted | §1.2, §8.2 |
| U-S14 returns | Accepted (Goals sheet stays under the clip) | §2.5, §6.5 |
| U-S15 CHL names | Accepted (`ChlTeamPlaces`, 61 pairs in the test) | §4.6 |
| U-N1 mock-up departures | Mixed case and away-first kept; the headline form is owner question 2 | §9, §14 |
| U-N2 fewer cards | Owner question 3 | §14 |
| U-N3 Back after Watch | Recorded for the decision note | §5.5, §11.1 |
| U-N4, U-N5 | Accepted | §1.3, §1.4 |
| U-N6 Follow on finished games | Accepted (no Follow on final games) | §2.4 |
| U-N7 box score layout | Accepted (tabs) | §6.4 |
| U-N8 | Noted (later version) | — |
| U-N9 score shrink | Accepted | §1.2 |
| U-N10, U-N11 | Accepted (no long press; media not focusable) | §2.2, §1.3 |

Re-review (2026-11-24) items. The partly closed first-round findings close through them: A-B2 by R-S1, R-N1 and R-N3;
A-B3 by R-S2 and R-S3; A-S6 by R-S5; A-S8 by R-S4.

| Finding | Decision | Where |
|---|---|---|
| R-S1 clip filter leaves a stale channel | Accepted as written: drop only active clip states; forward terminal clip states as `Idle`; `retry()` / `resume()` never restart a clip cycle on their own (only `ClipSession` does); the recents / watch-count claim corrected; three new filter test cases | §6.5 (filter paragraph, controller table `retry()` / `resume()` row), §10.2 |
| R-S2 country-tag regex | Accepted: explicit `CountryTags` list (ISO-2 + provider spellings), never an alias token (SN, TSN, CBC, …); "SN - Ontario", "TSN: 3" positives | §5.2 A rule 1, §10.2 |
| R-S3 affiliates unreachable | Lead ruling: local affiliates (ABC / CBS / FOX / NBC / CTV stations, CBC regionals) are matched **through the guide probes only**, never by name alias; their table rows replaced by one "none (guide only)" row; fixture and emulator check corrected (ABC 7 absent without a listing, CONFIRMED with one) | §5.1, §5.2 A rule 6 and table, §5.3, §10.2, §10.3, §10.4 |
| R-S4 probe C via FTS misses the window | Accepted: C is a direct `EpgReader.window` read of A's channels with the sport-word and team tests in Kotlin; it also confirms games on A's channels; B's FTS stays for channels A did not find; measured in the WP-3 benchmark | §5.2 C, §5.7, §10.2 |
| R-S5 commit 0 does not compile alone | Accepted: `CLIP_EXPIRED` moved to `:core` `PlaybackContracts.kt` with `defaultSteps`, the `ErrorText` arm and strings; `ClipUi` defined in `SportsEffect.kt`; stub members `clipUi`, `effects` in `SportsViewModel` and `onSportsEffect` in `GuideViewModel` | §6.1, §11 |
| R-N1 effect from `onCleared` | Accepted: `onCleared` ends without resuming; test asserts it | §6.6, §10.2 |
| R-N2 demand sentence | Accepted: corrected (a follow drops to `UNSEEN` and keeps polling at the floor) | §4.4 |
| R-N3 catch-up snapshot | Accepted: `rawEndMin` derived from `ProgrammeInfo`, `timeShift` carried, playhead re-read just before `playClip`, seek confirmed or issued on the first `PLAYING` | §6.1 `ResumePoint.Archive`, §6.6, §10.2 |
| R-N4 emulator check 9 | Accepted: the sport listing moves to TVA Sports (a code the featured game lists); Sportsnet 360 now expected absent | §10.3, §10.4 |

---

## 13. Lead sign-off

Signed off by the lead 2026-11-24 after two design reviews and one re-review; owner rulings: in-app video with the web
player's key read at run time; channel pick tunes only; OK on a card enters the panel's three fixed buttons; defaults
accepted (Corner/Delay chips leave the page, days -7..+7, next goal auto-plays, captions follow the subtitles setting,
guessed regional channels shown with a warning, English clips, no reminders). Build on branch sports-hub; main stays
releasable.

---

## 14. Open questions for the owner (with the default this design uses)

Answered already (2026-11-24): video route, tune-only channel pick, key model, Corner / Delay chips, ±7 days, auto-play,
captions, guessed regional channels, English only, no reminders, catch-up kept.

1. **Load the video picture and sentence while you browse** (two small requests to the NHL's video service for the game
   you stop on), or only when you press Play. *Default: while browsing.*
2. **Headline wording.** "Utah Mammoth (2) vs. Winnipeg Jets (4)" (your wording) or "Mammoth 2, Jets 4" / "Mammoth at
   Jets" before the game. *Default: your wording, away team first.*
3. **Fewer games on screen.** The new look shows 4 games and a peek (today: about 9). Add a "Live now" chip to jump
   between live games? *Default: no chip in this version.*
4. **After Home or standby during a video**, nothing plays when you come back (the video and the channel under it are not
   restarted). Should the channel you were watching come back instead? *Default: nothing plays, as designed.*
5. **NFL and CHL** get scores, records, period scores and leaders on the new page, but no videos and no box score in this
   version. *Default: accept; NHL first.*
6. **Green background** of the mock-ups for every league. *Default: yes.*

---

## 15. Integration pass (2026-11-25)

All five packages merged on `sports-hub`; one writer, one emulator (`TheBox_TV_API30`, fake panel on port 8090 through
`files/debug/sports_base`, removed afterwards). Decision note: `docs/decisions/2026-11-24-sports-hub.md`.

### 15.1 Fixed (merged-tree items handed over by the packages)

| Item | Fix |
|---|---|
| Captions under a clip | `PlayerViewModel.cues` passes cues while the guide is FULL **or the playback target is a clip** (`PlayerViewModelTest.cuesPassDuringAClip`). Checked on the emulator. |
| `sports_hub_summary_sep` | Quoted `" · "` in the strings file; `hubSep()` and the panel's padding removed. |
| HLS captions (A-S3) | The recorded master carries `#EXT-X-MEDIA:TYPE=SUBTITLES,…,NAME="en",DEFAULT=NO,LANGUAGE="en"`, `CLOSED-CAPTIONS=NONE`: `CLIP_HLS_MASTER_CARRIES_CAPTIONS = true` (HLS uses the master's rendition, MP4 still side-loads). The controller adds English to a clip's preferred text languages so the subtitles setting selects a DEFAULT=NO rendition (`PlaybackControllerClipTest.aClipPrefersEnglishText…`). The fake panel's master now has the same shape (`subs.m3u8`). |
| `ClipPlayerLayer` sub-line | The page passes `game` and `goals` when the clip belongs to the panel's game ("Goal 1 of 2 · TOR at BOS · 1st 05:00" on the emulator). |
| Two sheet frames | The page wrapped `HubSheetFrame` (which places itself at 856, 148) in a second 968 × 812 box: the host is now the full page. |
| Focus under a clip | A change of the clip just played (the next goal of a queue) re-ran the Goals sheet's focus request and pulled focus out of the clip layer into the hidden sheet. The sheet's focus target is frozen while a clip plays, and the sheet is re-keyed when a clip ends, so focus lands on the clip just played (also when it was the same clip). |
| Back hold | Checked: the layer holds Back for its life; on dispose the hold falls back to "sheet open or panel focused". Back mid-clip returned to the Goals sheet on the clip's row; Back after a recap returned to slot 1. |
| Double rest | The page rested 600 ms and then `show()` made `GameDetailRepositoryImpl` rest another 600 ms. The page now shows twice (`showNow`): per the repository's contract the second show of the resting game skips its rest. OK still fetches at once. `pause(true / false)` around clips matches the repository's gate. |
| Watch recap on a failed detail | A final game whose board (or detail) carries a recap id keeps **Watch recap** in slot 1 when the stats fail (unless videos are off); the body says "…Trying again automatically. Select Watch recap to see the game." Retry takes slot 1 only when no recap can play (§4.5's rule otherwise unchanged). |
| Empty day | Days other than yesterday / today / tomorrow read "No NHL games on Tuesday, November 17. Pick another day above." (`sports_empty_day_league`). |
| Resume ordering | `GuideViewModel` runs a Live or Archive resume only once the root has given the controller a viewport other than the clip's FULL (at most 500 ms, `CLIP_RESUME_VIEWPORT_WAIT_MS`), so the resumed channel prepares under the page. On the emulator the resumed channel's first session had video disabled (HIDDEN), i.e. no full-screen prepare. |
| Live recording | `HubFixtures.LIVE` gains `pp` (2nd period, EDM power play 1451), `p3` (3rd period, 4–3, seven goals, three without a clip yet) and `final` (regulation, VGK 5–3, eight goals, three stars, no recap posted yet); the game had no overtime. `NhlDetailMapperTest` and `NhlMapperHubTest` assert all three. The recap-posted final was not recorded into the repo (the recap appears later). |

### 15.2 Found on the emulator and fixed

- **Headline cut a long away name** ("Toronto Maple … (1) vs. Boston Bruins (1)"): both names had equal weights, capping each at half the free width
  although the plan said the full line fits. The home name now takes its own width and the away name the rest.
- **Matchup disc under the play disc**: with a poster spec the art behind it still drew its centre disc ("LIVE · 2ND 15:51") under the play
  disc and its own caption. The art under a poster draws no disc and no caption.
- **Empty panel slab**: an empty board's media place was `White08.copy(alpha = 0.5f)` (white at 50 %); now `White05`.
- **Focus trap in the panel**: Left from the title row's first chip (and any D-pad search toward the panel) landed on slot 1 without the
  page entering the panel (chip footer, Right went nowhere, Back left the page). The buttons now take focus only while the page has
  entered the panel (`HubPanel(entered)`), and the page focuses a slot two frames after entering.

### 15.3 Emulator states (fake panel; screenshots in the session scratchpad `hub-emu/`)

Checked: NHL today with the first live card focused; OK → Follow, Right, Right → Goals; Goals sheet (latest goal focused);
a goal queue playing with the transport bar and captions (Menu); auto-advance; Back mid-clip → the played row; queue end →
the played row; channel sheet (owner's playlist: Sportsnet East / Ontario / Pacific / West as LIKELY sport listings with the
regional warning); final card (recap poster, "Won in the shootout", stars); recap → Back → slot 1; box score; pre-game
(matchup art, six season rows); Matchup sheet; day −7 (one request per OK, none per focus move); empty day; NFL This week;
CHL OHL today; detail failure (live: Retry; final with recap: Watch recap); board failure; video "refuse" (Watch recap
disabled, Highlights chips hidden); Largest text size (one start through the boot mirror, restored by itself).
Not run: Watch / a Channels row tuning (it would change the owner's last channel), locked and hidden channels (they need a
change to the owner's data), "rotate" and "expire" modes, catch-up resume (no catch-up under the page), the Fire TV Stick
items (§10.4 14).

### 15.4 Left open

- Screenshot goldens for the hub's cards, panel, sheets and clip bar (§10.2 "Hash gate") were not written by WP-4 / WP-5; the
  `sportsHashGate` covers only the old suites plus `sports-tab/sports-tab.png`.
- At Larger and Largest the live media area is about 165 px: without a real poster the backdrop's 88 px logos sit under the
  "LATEST GOAL" tag and the caption (cosmetic; real posters cover them).
- A poster still shows its play disc while videos are off (Watch recap disabled).
- The code reviews (data, player, guide side, UI) and the release gates.

### 15.5 Gate

`lintAll` clean (no new issues); `:core:test` 4659 (one skipped); `:data:testDebugUnitTest` 810 (four skipped);
`:player:testDebugUnitTest` 559; `:ui:testDebugUnitTest` 1000; `:app:testDebugUnitTest` 93; `:app:assembleDebug` and the
`:app` / `:data` androidTest compilations pass. In the gate run `StorageLocationsImplTest.theSmbUserAndDomainAreRedacted`
(known load-flaky) failed and passed alone; the two new recording tests failed only because their fixtures were copied in
while that run was going, and `:core:test` passed in full afterwards. Fake panel: 306 Python tests pass. `sportsHashGate`:
`sports-tab/sports-tab.png` is the only changed entry (re-recorded; the test now pins the title row's clock, which had made
the PNG follow the host's time); the other 1691, including every score-bug entry, hash equal. Key grep: no real video key
or signed URL in the tree; only `FAKE-KEY-FOR-TESTS*` literals.

---

## Appendix A: new constants (`SportsConstants`)

| Name | Value | Use |
|---|---|---|
| `HUB_FETCH_DWELL_MS` | 600 | detail, match and clip-info requests start (or at once on OK) |
| `HUB_DAY_RANGE` | 7 | strip −7 … +7 |
| `HUB_DETAIL_IDLE_MS` | 300 000 | scheduled / pre-game re-read |
| `HUB_RECAP_WAIT_MS` | 10 800 000 | final re-read until the recap appears |
| `HUB_DETAIL_LRU` | 24 | detail and match caches |
| `HUB_MATCH_BEFORE_MIN` / `HUB_MATCH_AFTER_MIN` | 30 / 240 | game window for guide titles |
| `HUB_MATCH_PER_TEXT` | 40 | EPG airings per text |
| `HUB_MATCH_MAX` | 8 | candidates shown |
| `HUB_IMMINENT_MIN` | 30 | "Watch" and tunable rows only for live or starting within this |
| `HUB_VIDEO_KEY_REREAD_MS` | 600 000 | hold after any failed key read |
| `HUB_VIDEO_SCRIPT_MAX` | 2 097 152 | decoded script scan cap |
| `HUB_VIDEO_BODY_MAX` | 65 536 | lookup body cap |
| `HUB_CLIP_META_TTL_MS` | 600 000 | clip info cache |
| `HUB_CLIP_MAX_CONNECTIONS` | 4 | the `clip:<host>` broker key |
| `HUB_CLIP_RESTARTS` | 2 | IO / 5xx restarts before failing a clip |
| `HUB_CLIP_SEEK_MS` / `HUB_CLIP_SEEK_LONG_MS` | 10 000 / 30 000 | transport |
| `HUB_CLIP_BAR_MS` | 4 000 | bar auto-hide |
| `HUB_UP_NEXT_MS` | 1 500 | next goal |

## Appendix B: what in the existing code shaped this design

- `NhlMapper.day` drops `tvBroadcasts`, `goals`, `threeMinRecap` and `gameWeek`; hence the appended fields.
- Sports is `HoleMode.COVERED` and keeps the live channel's audio under the page (`viewportFor(hole, screenOff, beneath)`);
  the clip needs the SPORTS + clip → FULL case and must not overwrite `beneath`.
- `Spec` has two variants and nearly every branch keys on `isArchive`; `TuneTarget.DirectUrl` is `Spec.Live`; hence
  `Spec.Clip` and its table.
- `ArchivePlayback` ends any session whose state it does not own, so a catch-up can only be resumed through
  `playProgramme` + `seekTo`, never by replaying the controller's archive spec.
- `onSportsClosed` has no tuned branch and no `SportsClosed` event exists; `onSearchClosed` is the template.
- Search already has the matcher's parts: `channel_fts` (FTS4) with the lineup and parental predicate, the EPG FTS
  (`search`, `searchPast`, title and subtitle only) and `channelsForRefs`.
- `SportsDay` is a three-value enum shared with the NFL's S6 rule.
- Coil's memory cache is 2 MB (1 MB on low-end); signed poster URLs change per lookup.
- `Redact` already masks registered secrets and per-URL transient tokens and strips query strings.
- The guarded controller delegates `PlaybackController` by `inner`.
- The score bug draws only in FULL, so it never sits over a clip.
