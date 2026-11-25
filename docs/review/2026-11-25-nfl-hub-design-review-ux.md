# NFL hub design review: UX and product (2026-11-25)

Reviewed: `docs/design/m6-nfl-hub.md` (proposed 2026-11-25), against `docs/design/m6-sports-hub.md` (§1, §6.4–6.8, §15.6),
`docs/design/m6-event-channels.md` §18.3, the code at `main` (v0.1.21: `HubPanel.kt`, `GoalsSheet.kt`, `SportsText.kt`,
`HubPanelFitTest.kt`, `strings_sports_hub.xml`), the ESPN samples in the session scratchpad `nfl/` and the emulator shots
`ev-emu/f5.png`, `f10.png`, `e4-f.png`, `hub-emu3/19-…`, `28-C-…`, `29-C-…`.

Scope: what a football viewer sees at each phase and in each sheet. The data, parsing, contracts, cadence and
playback plumbing are covered by the second reviewer. The owner's ruling on in-app playback is taken as given.

Method for widths: advance widths read from the bundled `instrument_sans_*.ttf` (no kerning), which is how
`HubPanelFitTest` measures. Text sizes × the factor (Default 1.0, Largest 1.2). Panel geometry is from `HubPanelPlan`
(`HubPanel.kt:815-864`). The stat row is split 1 : 2 : 1, giving 166 / 332 / 166 px (`HubPanel.kt:1097-1099`). Slot
padding is 28 with a 16 px gap, or 20 with a 12 px gap above factor 1.05 (`HubPanel.kt:1232-1233`). The sheet row has
880 px inside its padding (`GoalsSheet.kt:407-499`). I re-derived the §2.6 height table: every media height in it is
correct.

**Verdict: approve with changes.** The shape is right, and the stat choices are the ones a football fan checks. One
label set does not fit the panel and must change before commit 0 freezes the strings. The live state and the final
summary need reordering so the most important football facts survive. Disabled slots need their reasons spoken.

---

## Blocker

### B1. The final slot labels do not fit the 664 px button row (§2.3 "Widths")

- **Evidence.** This is `HubPanelFitTest.threeButtonsFitInEveryPhaseAtEverySize`'s own arithmetic: label + 2 × padding,
  plus "▶" + 8 on slot 1, plus 2 × gap.

  | Slots | Default | Largest |
  |---|---|---|
  | "▶ Watch highlights" · "Scoring (10)" · "Box score" | 196.0 + 16 + 8 + 56 = 276 · 136.4 + 56 = 192 · 111 + 56 = 167 · + 32 = **667** | 235.2 + 19.2 + 8 + 40 = 302 · 163.7 + 40 = 204 · 133.2 + 40 = 173 · + 24 = **703** |
  | "▶ Watch highlights" · "Scoring (6)" · "Box score" | 660 | **694** |
  | "▶ Watch from start" · "Scoring (6)" · "Box score" (§2.3 row 3) | 659 | **693** |
  | "▶ Highlights soon" · "Scoring (6)" · "Box score" | 640 | **671** |

  The design's "≈ 640 at Default, ≈ 650 at Largest" is wrong. The MNF sample has 10 scoring plays, so a real ESPN game
  already fails at Default. `slotGlyph` keys the ▶ off the action, not off `enabled` (`HubPanel.kt:914-919`), so the
  disabled "Highlights soon" also draws a ▶.
- **What the viewer sees.** At Larger and Largest, and at Default on a 10+ score game, the row is compressed: "Box score"
  is squeezed or clipped. The fit test that gates the release fails.
- **Change.**
  - Slot 1 becomes **"▶ Highlights"**. That gives 580 px at Default and 599 at Largest with "Scoring (12)". It matches the card
    chip "● Highlights" and the tag HIGHLIGHTS.
  - Slot 1 for a catch-up airing on an NFL final with the other two slots present becomes **"▶ From start"**: 601 px at Largest.
  - Disabled slots draw **no glyph**, which follows hub review M4's "never a play disc for something that can't play".
    "Highlights soon" is then 644 px at Largest.
  - Add the NFL final, NFL live ("Following ✓" · "Channels (36)" · "Scoring (12)") and these slot sets to
    `HubPanelFitTest` with **two-digit counts** (f5.png shows "Channels (36)"). Do this before commit 0, because the strings
    freeze there.

---

## Major

### M1. Live: down, distance, field position and possession are optional, and all of them vanish at Largest (§2.2, §2.6)

- **Evidence.** §2.2 makes the situation an "extra": Down ("2nd & 7" in the side with the ball) and Timeouts. §2.6:
  "Live, play still … Dropped at Largest: Down, Timeouts", which leaves 179 px.
  - The board already carries the full situation: `LeagueDetail.Nfl.play` (`NflPlay.Down` with `field: FieldPos`),
    `possession`, `redZone`, `lastPlay` and the timeouts (`SportsContracts.kt:190-222`).
  - The design never shows the field position or the red zone. Today's panel has a separate Possession row; the design
    removes it.
  - "2nd & 7 at WSH 35" is 189 px at 24/500, so it cannot fit a 166 px side column. That is why the yard line was
    dropped.
- **What the viewer sees.** With the score and clock in the pill and headline, the next thing a fan looks for is who has
  the ball, where, and on what down. The design gives a 166 px fragment ("2nd & 7") without field position. At Largest,
  or whenever a stale note appears, it gives nothing. Meanwhile "Quarters · Q1 0–0 · Q2 10–7" keeps its line. A down
  without a yard line ("1st & 10": own 20 or their 3?) is the least useful half of the fact.
- **Change.** Make the situation **summary line 1 for live**. It is a full-width line, one line, fitted like the leaders line:
  - fitted, it reads "CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3" (580 px Default, 696 px Largest);
  - when that is too wide, the line drops "Timeouts" and then "Red zone".
  - Use "1st & Goal at WSH 4" when the down is goal-to-go.
  - Use "Kickoff · WSH" or "After the score" for `NflPlay.Kickoff` / `Dead`.
  - Use "Halftime · CIN gets the ball" only if the board says so; otherwise omit the line at halftime.

  Line 2 becomes "Latest score · …". Drop the "Quarters" line on the live panel: the running score is on every Scoring row.

  The rows become the 5 mandatory rows plus the Timeouts extra only when the line had to drop it. The order of giving way
  keeps line 1, so the situation survives Largest. This also removes the question of which column "2nd & 7" sits in.

  Spoken form: "Bengals ball, second and 7 at the Commanders 35, red zone".

### M2. Final: the recap headline always wraps, so the leaders line is never shown (§2.1, §2.4, §2.6)

- **Evidence.**
  - Every recap headline in the samples is 63–103 characters: 13 games in `sb11.json` `headlines[0]` plus the summaries.
  - At 24 px the shortest, "Recap · Bills' defense feasts in 26-9 win over injury-depleted Dolphins", is 758 px against
    664. The design's own example (99 characters) is 1174 px at Default, and 1409 px at Largest, which is more than two
    lines.
  - The panel rule is that a wrapped line 1 hides line 2 (`HubPanel.kt:1110-1113`). So "Leaders · …" is drawn on
    **0 of 13** sample finals at Default.
  - At Largest the plan keeps one line (§2.6: "172 after dropping Possession and summary line 2"). There the headline is
    cut at about 45 %: "Recap · Jayden Daniels rallies Commanders in fina…".
- **What the viewer sees.** Final panels show a half-read AP sentence and never the game's passing, rushing or receiving
  leaders. Those leaders are the NFL substitute for the NHL's three stars (§2.1 "Three stars → leaders").
- **Change.**
  - Final line 1 is **Leaders**, fitted to one line as specified: 3, then 2, then 1 categories.
  - Final line 2 is **"Recap · {headline}"**, `maxLines = 1`, ellipsized. It is the line dropped at Largest.
  - If the owner rules no recap (Q2), line 2 becomes "Latest score"-style "Winning score · Drew Stevens 26 Yd Field Goal,
    4th 0:00".
  - `HubPanelFitTest` must use a real 99-character headline and assert the leaders line is drawn at Default.

### M3. After the highlight expires, the recap picture poses as a video (§2.1, §2.3, §2.4)

- **Evidence.**
  - §2.1 gives the recap image the tag RECAP, no disc, and the caption "Recap: ESPN". §2.3 says the media area "shows the
    recap image with the reason in its caption (§2.4)". These are two different captions for one state.
  - In the NHL panel, RECAP is the tag of a **playable** recap poster (`HubPanel.kt:737`, e4-f.png / 29-C).
  - The real `article.images[0]` is a frame of the highlight video itself on MIA@BUF ("Miami Dolphins vs. Buffalo Bills
    Game Highlights") and PIT@PHI.
  - On MNF it is "Jayden Daniels reacts to Countdown crew picking Bengals over Commanders": a pre-game picks-show still,
    not the game.
  - In `sb11.json`, 11 of 13 week-11 finals have no highlight two days later. This is the **default look of most final
    games**.
- **What the viewer sees.** A video frame tagged RECAP, the same word that means "press OK to play" on the NHL side,
  while slot 1 reads "No video". On MNF it shows a studio picks segment.
- **Change.** With no offered highlight, the final media area is **matchup art with the reason caption**, as the NHL does
  for "Recap not posted yet" (`HubPanel.kt:740`). Drop the recap-image fallback and the "Recap: ESPN" caption. If the
  owner wants imagery, use the image only under a neutral tag ("STORY"), never RECAP or HIGHLIGHTS, with the §2.4 reason
  as its caption.

  Also shorten the reason captions so they fit at Largest. Poster captions are 26/600, art captions 22/500, and the
  width is 632 px:
  - "Highlights usually appear about 15 minutes after the game" is 709 px at **Default** on a poster and 710 px at
    Largest on art. Replace it with "Highlights about 15 min after the game".
  - "ESPN removes game videos after a few days" is 645 px at Largest on a poster. Replace it with "ESPN removes videos
    after a few days".

### M4. Disabled slots are not explained to a TalkBack / VoiceView user (§2.3; event channels §18.3)

- **Evidence.**
  - The grey-chip rule (§18.3) says the slot that takes focus on entering the panel speaks the reason for a grey slot.
    It is implemented only for `SlotAction.CHANNEL` (`HubPanel.kt:1243-1248`).
  - The design adds disabled "Highlights soon" and "No video" in slot 1, which is skipped by focus, and disabled
    "Scoring" on a live 0–0 game. None has a spoken reason; their only visible reason is the media caption, which M3
    shows is cut.
  - §8 of the design adds no NFL content descriptions at all.
- **What the viewer sees.** OK on a final card lands on "Scoring (9)". A screen-reader user hears "Scoring 9" and never
  learns that slot 1 exists or why it is grey.
- **Change.** Generalise `greyReason` to any disabled slot with a reason.
  - Slot 1's reason is the §2.4 caption text. The first usable slot is described "Scoring (9). Highlights unavailable:
    ESPN didn't post highlights for this game".
  - The grey slot itself is described "Highlights soon, unavailable. Highlights about 15 min after the game".
  - Live "Scoring" disabled: "Scoring, unavailable. No points yet."
  - Add these strings in commit 0. Apply the same to the NHL's "Recap soon", which has the same gap, for one product.

---

## Minor

### m1. Leader names: the suffix rule and the example (§2.4, §2.1)

- **Evidence.**
  - "names are ESPN's `shortName` last-name part". Real `shortName`s include "J. Cook III", "O. Gordon II",
    "J. Trotter Jr.", "L. Van Ness" and "C.J. Stroud".
  - The NHL precedent `substringAfterLast(' ')` (`HubPanel.kt:597`) would print "III 89 rush" on the MIA@BUF final, where
    Cook is the game's rushing leader.
  - The design's MNF example "Daniels 289 pass · Croskey-Merritt 80 rush · Diggs 90 rec" does not match the sample. The
    real game leaders are Burrow 300 pass (CIN), Daniels 55 rush and A. Williams 122 rec (WSH).
  - The pre-game example keeps initials ("J. Love 2,838") while the final drops them.
- **Change.**
  - The name rule becomes "`shortName` after the first '. '", keeping suffixes and particles ("Cook III", "Van Ness",
    "Stroud").
  - Append the team as today's board line does: "Burrow 300 pass (CIN)". Drop the team before dropping a category.
  - Use one name form in both phases.
  - Fix the example.

### m2. Scoring row line 2 (§3.1)

- **Evidence.**
  - ESPN's safety abbreviation is **"SF"** (`sum-thu-401873066.json`, Q3 10:22). "SF" also means the 49ers, and SF played
    in week 11.
  - Line 2 uses "2nd 1:55" while the pill and the design's own Quarters line use "Q2".
  - Line 2 names the leader ("CIN leads 10–3"), not the scorer, so after a trailing team's field goal only the 64 px logo
    says who scored.
- **Change.** Line 2 becomes "Q2 1:55 · WSH FG · CIN leads 10–3". Take the type word from `PlayScore` (TD, FG, Safety,
  2-pt, Def TD), never from the raw abbreviation. Use "OT 4:12" and "Tied 10–10" as written.

### m3. Pre-game labels are not parallel (§2.2)

- **Evidence.** "Points per game · Points allowed · Yards per game · Yards allowed": two rows say "per game" and two do
  not, yet all four values are per-game averages ("22.1", "340.4"). The NHL rows say "Goals for per game" and "Goals
  against per game" (e4-f.png).
- **Change.** Use "Points scored per game", "Points allowed per game", "Yards gained per game" and "Yards allowed per
  game". The widest is 328 px at Largest, which fits the 332 px centre column.

### m4. End of quarter reads "Q1 INT"

- **Evidence.** `SportsText.intermissionPill` uses `sports_hub_pill_intermission` "%1$s INT" (`SportsText.kt:340`,
  strings line 35) for an NFL `END_PERIOD`. To a football viewer "INT" is an interception. The score bug already says
  "END OF 1ST" (`sports_stacked_nfl_end_of`). This is pre-existing, but this design specifies the end-of-quarter state
  (§2.2).
- **Change.** Football uses "END Q1" ("END OT").

### m5. "More plays" lists things that are not plays (§3.1, §3.3)

- **Evidence.** The MNF OnePlay clips include "Stadium lights go out in third quarter…", "Commanders crowd goes wild…" and
  "ESPN rules analyst Russell Yurk explains…". The player sub-line calls them "Play 2 of 4".
- **Change.** Header "More videos", sub-line "Video 2 of 4 · CIN at WSH".

### m6. "Video: ESPN" is dropped by the wrong budget (§3.3)

- **Evidence.** Single-play sub-lines carry the attribution "when the sub-line fits `lineBudget`". `lineBudget` is the
  panel's 55-character budget for a 664 px line (`HubPanel.kt:642`). The transport bar is 1728 px.
  "Play 2 of 5 · CIN at WSH · Q2 1:55 · Video: ESPN" is about 600 px at 24 px.
- **Change.** Every NFL clip's sub-line ends "· Video: ESPN". With the footer gone (§15.6) this is the only attribution
  left, and it should be unconditional.

### m7. Matchup sheet lines are cut; the last-5 score order is unstated (§3.6)

- **Evidence.**
  - A side of a two-leader row has about 342 px after the 72 px headshot. "248/372, 2,623 YDS, 19 TD, 10 INT" is 343 px
    at Default and 411 at Largest, so TD / INT, the part fans read, is the first to go.
  - The row has no category word (Passing, Rushing, …).
  - ESPN's `lastFiveGames.score` is winner-first ("@ DET L 20-17"). The design's example prints "L 17–20 @ DET" without
    stating the reordering.
- **Change.**
  - Show "2,623 yds · 19 TD · 10 INT" (drop C/ATT), and put a category label above each pair.
  - State the score order, for example ESPN's convention "L 20–17 @ DET".
  - Order the last five oldest → newest and say so.

### m8. NFL spoken forms are unspecified (§2, §3)

- **Evidence.** `sports_hub_desc_stat` "%1$s: %2$s %3$s, %4$s %5$s" will read "3rd down: Bengals 4-12, …".
  - TalkBack may read "4-12" as a range or a date, and "31:31" as a time.
  - "Yd", "TD", "FG", "G" and "Q2" are read as letters.
  - The scoring row's ▶ (has video) is not described.
- **Change.** Add NFL description strings:
  - "3rd down: Bengals 4 of 12, Commanders 2 of 10";
  - "Time of possession: 28 minutes 29, 31 minutes 31";
  - scoring row: "Bengals touchdown. Tee Higgins 31 yard pass from Joe Burrow. Second quarter, 1:55. Bengals lead 10 to 0. Video, 23 seconds";
  - injury row: "Kevin Zeitler, guard, questionable, triceps".

### m9. The play disc is missing at Default on NFL posters (§2.1, §2.6)

- **Evidence.** The disc is drawn only when the media area is ≥ 200 px (`POSTER_DISC_MIN_H`, `HubPanel.kt:820`, 1184).
  Live with a play still, or final with a highlight still plus Possession and a channel line, is 188 px at **Default**.
  The "playable" cue disappears at the normal size.
- **Change.** Drop the optional rows (Possession; Timeouts) while they would take a playable poster under 200 px. In
  `HubPanelPlan` terms, the floor of a playable poster is `POSTER_DISC_MIN_H`.

### m10. "No video" as a slot label (§2.3)

- **Evidence.** A disabled button reading "No video" names neither the video nor the cause. It also sits next to a channel
  line about TV.
- **Change.** Use "No highlights" (fits; disabled, no glyph, spoken reason per M4).

### m11. Two states have no defined slots or caption (§2.3, §4.1)

- **Evidence.**
  - When the detail has failed with nothing cached, "Scoring (n)" has no n: the board carries no NFL scoring count.
  - When `ESPN_VIDEO_ENABLED = false` (or a refusal sets `videoOff`), slot 1 and the caption are unspecified. The "didn't
    post highlights" caption would then be untrue.
- **Change.**
  - Failed: show "Scoring" (enabled; the sheet shows its §3.2 failure body) and "Box score" (enabled; its Retry row).
  - Video off: slot 1 "No highlights" with the caption "ESPN videos aren't available right now", the twin of
    `sports_hub_caption_videos_off`.

### m12. A weather delay looks like a frozen live game

- **Evidence.** `EspnMapper.kt:163` maps any unknown `in` status, including a delay, to LIVE. The pill shows a clock
  that does not move, and the detail keeps polling every 60 s.
- **Change.** If the board's `status.type.name` / `detail` says delayed, show the pill "DELAYED · Q3 5:32", with
  "Weather delay" (from `status.type.detail`) as summary line 1. Cheap; otherwise list it under Missing.

---

## Cut or defer (a first version the owner would not miss)

- **Clip ↔ play linking (§4.4)**, i.e. ▶ on scoring rows. It only matters on ESPN-aired games (MNF: 3 of 10 scoring plays
  had a clip), costs up to 16 record lookups, and the same clips already play from "More videos". Ship v1 with the
  scoring rows as a plain list and every OnePlay clip under "More videos". Add the links when the owner asks.
- **The live "LATEST PLAY" still in the panel media** (ESPN games only, with a 6–11 min lag). Matchup art with the venue
  is fine for v1.
- **The "Quarters" live summary line.** M1 replaces it. Quarters remain in the Box score.
- **Box score Defense and Kicking tables.** Passing, Rushing and Receiving are what is read. Defense needs the
  interceptions-category merge (§3.5) for little viewer value.
- **The recap image as panel art** (M3). Matchup art is enough.
- Keep: the pre-game six rows, Last 5, the Matchup sheet with injuries (football fans check injuries before kickoff), the
  final highlight, the Scoring sheet and the Box score.

## Missing (what a football fan expects)

| Item | Data | Suggestion |
|---|---|---|
| Situation and last play live (field position, red zone, "J. Burrow pass short right to T. Higgins for 12 yards") | Board `situation` incl. `lastPlay.text` (V by app use, `LeagueDetail.Nfl.lastPlay`) | M1. Last play in the Scoring sheet header while live, if wanted. |
| Broadcast network ("Netflix", "ESPN", "FOX") | `header.competitions[0].broadcasts[].media.shortName` "Netflix" (V, `sum-pre.json`); board `broadcasts` (V) | Pre-game media caption "SoFi Stadium · Netflix". The channel line's "No channel found in your playlists" becomes "No channel found · on Netflix". This explains the many games IPTV playlists don't carry (Netflix / Prime). |
| Division standing ("2nd in NFC North") | `standings.groups[]` in the summary (V, `sum-pre.json`: NFC North and NFC West tables) | One line at the top of the Matchup sheet, or replace "Last 5" with "Division" in the pre-game rows ("2nd NFC North"). Not gambling; cheap to keep instead of skipping. |
| Home / road record | `record[]` type home / road (V) | Matchup sheet only. |
| Next game | Not in the summary; next week's board has it | Defer. |
| Weather for outdoor games | `gameInfo.weather` (V) | Defer (§2.4 reasoning holds for v1). |
| Head-to-head / season series | None (FACTS §3.1) | Not available. |

No gambling content reaches the screen in the design. All 13 recap headlines and 13 clip headlines are clean; odds,
predictor and win probability are skipped. The only exception is the MNF recap image of a studio picks segment, which M3
removes.

## Not reviewed

- Data shapes, the parser, the cadence, contracts, the clip resolver and catalog, playback, the rendition policy, legal
  exposure (second reviewer, or owner Q1).
- The live layout against real live data: no live summary sample exists (§12). The M1 line is built from the board fields
  the app already parses.
- Nothing was run on the emulator. Widths are advance-width sums without kerning, the same method as `HubPanelFitTest`.
  Real Compose layout may differ by a few px, which does not change B1 at Largest (a 30–40 px overflow).
- RTL: no new risk beyond the NHL panel (same three-column row and text joins). Not inspected in depth.
- Box score scrolling length was estimated (about 25 rows per team at 44 px, comparable to the NHL skaters list), not
  measured.
