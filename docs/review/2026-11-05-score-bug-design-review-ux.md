# Score bug and Sports tab: UX/UI review

Reviewer: adversarial UX/UI pass, 2026-11-05. Subject: `docs/design/m6-score-bug.md` (proposed 2026-11-05), against
`docs/decisions/2026-11-05-nhl-score-bug.md` (binding), `design/score-bug-mockup.png`, `docs/01-design-reference.md`,
`ui/.../full/*` (`PlayerUi`, `FullScreenHost`, `QuickBar`, `PickerSheet`), `QuickBarModel`, `HeaderTabs`, decisions
2026-10-15 and 2026-10-19, `m5-setup-settings.md` §3.2.3/§3.3.13, audit 2026-10-20. Data layer and architecture are out
of scope. Mock-up measurements use the 2000-px display of the 2172-px PNG (pill 1536 × 267, as the design does).

---

## Blockers

### B1. §6 + §4.4: changing the corner from the quick bar gives no visible feedback
- **Problem.** The corner row cycles TR → BR → BL → TL with OK and keeps the sheet open, but the sheet (1376, 48, 496 × 880)
  hides both right corners (§4.4, "incl. the score-bug picker itself"). From the default TR, the first OK moves the bug
  to BR: still hidden. The user presses OK twice before seeing anything, and the two right corners can never be seen
  while choosing. Changing the corner is one of the three jobs owner ruling 2 gives the quick-bar item.
- **Evidence.** §6 row 2 and the "The sheet hides a right-corner bug while open" bullet. `PlayerUi.PICKER_L` 1376 <
  the TR/BR bug's x 1416.
- **Fix.** Make "Corner" a place mode instead of a cycling row. OK on "Corner" closes the sheet and shows the bug at its
  current corner with an accent ring, plus a hint line next to it: "← → ↑ ↓ Move · OK Done". The arrows move it between
  the four corners at once, and OK or Back saves the corner and returns to the quick bar. `ScoreBugPlacement` ignores
  hide rules in this mode, apart from the screen-off layer and dialogs. The Settings row stays as a plain choice list.

### B2. §7: four new simple rows break the simple-settings budget
- **Problem.** None of the four Sports rows says whether it is SIMPLE or ADVANCED, so all four default to `SIMPLE`.
  The budget is ≤ 35 and the count is already exactly 35. `SimpleRowCountTest` fails, and the design does not
  re-litigate the budget.
- **Evidence.** `m5-setup-settings.md` §3.2.3 ("≤ 35"), §3.3.13 ("= 35"), and integration ruling row 260 ("total
  **35**"). Precedents: Hidden channels was moved to A (2026-10-18), and On launch was moved to A (2026-10-19).
- **Fix.** Make **Sports** (on/off) the only SIMPLE row. Make Corner, Delay and Size ADVANCED: corner and delay are
  already one press away in the quick-bar sheet. Then either move one existing simple row to A, or record an owner
  ruling that raises the budget to 36. Name the trade explicitly in §7, and update `SimpleRowCountTest` and
  §3.3.13.

### B3. §4.2: the .74 fill reads as grey on white ice, and the period line falls to about 3.5 : 1
- **Problem.** The owner will first see this over a hockey broadcast, which is mostly white ice. The fill is `#0A0D13`
  at .74, so over white the pill composites to about `#4A4C50`, a mid grey and not the near-black glass of the
  mock-up. The period line is `Fg` at .55, which composites to about `#A4A5A7` on that grey.
- **Contrast.**
  - Period line: about **3.6 : 1**. That is below 4.5 : 1 for 20 px secondary text, and it falls further under the .10
    top highlight.
  - Clock and score: about 7.6 : 1, which is fine.
  - The white hairline (.16 → .06) is invisible over ice, so the pill loses its edge.
  - `_dark` logos have white parts (`MTL_dark.svg`: 3 × `fill="#fff"`), and those go soft on grey.
- **Evidence.** Arithmetic above (sRGB relative luminance). The mock-up's pill is visibly near-black over a dark
  background, and the design never tests a bright background. `PlayerUi.PanelFill` (.86) already exists for exactly
  this kind of surface.
- **Fix.**
  - Fill: `PlayerUi.PanelFill` (.86). Over white the period line then measures about 4.8 : 1, and over dark video the
    pill looks the same as before.
  - Period line: `Fg` at **.70**, not .55.
  - Top highlight: .06, not .10.
  - Edge: add an **outer** 1 px `Black .30` ring under the white hairline, so the edge holds on ice.
  - Tests: add a bright-background screenshot to the `ScoreBugDrawTest` set (a white frame and a 50 % grey frame).
    Add "legible on white ice" to the §11 device checks.

### B4. §5.2/§5.3: OK on a game card in an IPTV app reads as "watch this game"
- **Problem.** In every other screen of this app, OK on a programme or channel tunes it (guide, Search, channel list).
  In Sports, OK on "CGY 0 – 2 EDM" silently arms an overlay, and the only feedback is a small "On screen" label. "On
  screen" also reads as "this game is on a channel" or "is showing now". The owner's first press in the new tab will
  hit this. The footer hint "OK Show on screen" is 17 px, which is under the floor (see S9), and it does not change
  when the game is already chosen, where OK means "turn off".
- **Evidence.** §5.2 "On screen" label, §5.3 OK row, and the §0 words list. Every other OK-on-a-card in the app tunes
  (`SearchScreen` cards, guide cells).
- **Fix.**
  - Verb: **Follow** / **Following**. It is the common sports-app verb, it does not imply playback, and it is not on
    the banned list.
  - Focused card: a right-aligned action chip, "OK Follow score" or "OK Stop following".
  - Chosen card: a "✓ Following" chip. It must not be the red dot (S6).
  - Footer: dynamic, built through `FooterHints`.
  - First-time toast: "Following CGY – EDM. The score shows in a corner while you watch any channel. Change it from
    the player's quick bar."
  - Quick-bar wording: "Follow a game", "Stop following".

---

## Should-fix

### S1. §4.1: proportions drift from the mock-up (stretched pill, small logos, heavy digits)
- **Problem.**
  - The mock-up pill is **5.75 : 1** and the spec is **6.9 : 1** (440 × 64). It will look stretched next to the
    mock-up.
  - The 72 × 48 logo slot holds a 960 × 640 viewBox whose art fills only about 68 % × 70 % (MTL art bbox 151..808 ×
    95..544, rendered with `rsvg-convert`). The visible crest is therefore about 49 × 34, against about 43 tall in the
    mock-up (67 % of the pill height).
  - The score at 44/700 has a cap height of about 31, which is 48 % of the pill. The mock-up shows 39 %.
  - Net effect: the spec is wider, with smaller logos and heavier numbers than the image the owner drew.
- **Fix.**
  - Trim each logo's transparent margin once, at rasterisation (a Coil `Transformation` that crops to the alpha bbox,
    cached with the bitmap).
  - Fit the trimmed art into a **64 × 44** slot.
  - Score **40/700**, with a 48 slot.
  - Pill: `16 | 64 | 8 | 48 | 12 | 1 | 106 | 1 | 12 | 48 | 8 | 64 | 16` = **404 × 64**, about 6.3 : 1.
  - Divider: keep it at 32.
  - Verify the result as a 1 : 1 overlay on the mock-up before WP-3 starts.
  - Clock 26 and period 20 stay (the period is held up by the floor).

### S2. §4.3: the 64 px side inset is inside the TV overscan zone, not outside it
- **Problem.** The design says 64/56 is "inside the 5 % overscan area". 5 % of 1920 is **96**, not 64. Corner elements
  are the first to be cut: a TL bug's away logo starts at x = 80, so on a TV with 5 % overscan it is 16 px into the
  clipped band. The 2026-10-20 audit already flagged 96 × 54 for this app.
- **Evidence.** `docs/review/2026-10-20-app-audit.md:105` ("96×54 in this app's 1920×1080 design units").
- **Fix.** `SCORE_INSET_X` **96**. `SCORE_INSET_Y` 56 is fine (≥ 54). Correct the sentence in §4.3, and update the
  corner table: TR/BR x = 1920 − 96 − W. Re-check §4.4: the picker at 1376 still hides TR at x 1420, and the number
  entry still overlaps.

### S3. §4.4: the bottom-corner bug jumps 220 px on every zap
- **Problem.** The info bar shows on every zap (a passive bar of a few seconds) and on OK. Each time, a bottom-corner
  bug moves from 960 to 740 and back again later. The spec gives no motion, so it teleports twice per zap, which is
  the most distracting thing a corner element can do. "It does not move" is the stated principle, and this is the
  exception that fires most often.
- **Fix.**
  - Spec the lift as a 150 ms `FastOutSlowIn` translate (the toast precedent), snapped in low-end mode.
  - Hide (fade) instead of lifting for the passive post-zap bar, and lift only for a bar the user opened (OK, the
    scrub bar, the quick bar).
  - Record it in §10 Q2. A bottom default would make this the common case, so it also argues for keeping TR as the
    default.

### S4. §6: the quick-bar sheet puts up to 16 game rows before Corner/Delay, and Delay only steps upward
- **Problem.**
  - Rows are ordered games first, then Corner, Delay, Turn off. On a 16-game night, reaching "Delay" takes up to
    16 Downs. `PickerSheet` shows a window of rows with no section headers, so the action rows are not even visible
    on open.
  - Delay steps up with OK and wraps. Going from 30 s to 20 s takes 8 OKs through 120. Left is taken by Back
    (`PlayerStateMachineImpl:896`), so there is no in-row decrement.
- **Fix.**
  - Order: the followed game (✓, with its live line) → **Corner** (B1 place mode) → **Delay** → **Stop following** →
    a "Today" divider → the other games.
  - Delay opens a nested choice list (the existing picker pattern, with a check on the current value). Show "Off"
    for 0 s.
  - Initial focus: the followed game when there is one, otherwise the first live game.

### S5. §6: the quick-bar item sits about 11 Rights from where the bar opens
- **Problem.** `QuickBarModel` order is Pause · Audio · Picture shape · Subtitles · Favourite · Start over ·
  Previous · Stream info · Decoder · Screen off · (Sources) · Settings, and the row does not wrap
  (`coerceAtMost`, `PlayerStateMachineImpl:774`). Inserted "before SOURCES", Score bug is item 10–12: Up plus about
  10 Rights plus OK. The five-*key* rule passes, but a feature the owner asked for by name is the slowest item to
  reach. §6 also says "display order is enum order", which is stale since the M6 amendment: the order is the explicit
  list in `QuickBarModel`.
- **Fix.** Place `SCORE_BUG` right after Subtitles (position 4–5) while a game is followed, and before Settings
  otherwise. Correct the §6 sentence to name `QuickBarModel`. Update `PlayerFiveKeyReachabilityTest` and
  `player_keys.csv`.

### S6. §5.2: card colours fight the guide's meanings and the logos
- **Problem.**
  - The followed game gets a `color.live` red dot. Red dot already means LIVE, the tuned channel and recording in
    this app (design reference: LIVE pill, tuned dot, ● record), and live games in the same grid get no live mark of
    their own.
  - On the focused card (solid `#EEF1F6`), the accent "On screen" text (`#5CA8F2` on `#EEF1F6`) is about 2.4 : 1.
  - The `_dark` logos have white strokes (MTL_dark `fill="#fff"` ×3), which vanish on the near-white focused fill.
- **Fix.**
  - Live games get the red dot plus "LIVE" in the state column.
  - The followed game gets a "✓ Following" chip: on unfocused cards, accentText `#B6D4F7` on `White08`; on focused
    cards, `#0A0D13` on accent .22.
  - Logos sit on a fixed dark 64 × 44 radius-8 plate (`#0A0D13`) inside the card, so `_dark` art works in both
    states. Alternatively, swap to `{ABBR}_light.svg` while focused (both variants exist; this costs a second
    request per team).

### S7. §5.1/§5.3: Sports D-pad flow has gaps
- **Problem.** The spec never says:
  - where focus starts on open;
  - what Up does on the day chips (the top bar is not drawn on a COVERED screen, as in Search);
  - where Back from "Score bug settings" returns;
  - what happens when focus reaches the bottom row, given that the grid runs to 216 + 6 × 152 = 1128 > 1080 under a
    footer at bottom 40.

  "Back from anywhere → guide" also skips a stop that Search has (results → field → guide).
- **Fix.**
  - Initial focus: the followed game, else the first live card, else the Today chip.
  - Up on the chips does nothing; there is no top bar.
  - Back from the settings section returns to Sports with the chip focused.
  - Back from the grid goes to the guide, as specified. One Back is right here, because there is no text field.
    State it so nobody copies the Search ladder.
  - The grid's viewport ends 16 above the footer and scrolls with the focused row kept 64 inside its edges.
  - Add these rows to `SettingsBackLadderTest` / the key table.

### S8. §4.5 + §10 Q1: following a game that has not started shows nothing in the player
- **Problem.** The user follows the 7 PM game at 5 PM and goes to full screen, and nothing appears: SCHEDULED hides
  the bug. Only the quick-bar value shows the choice took. The owner's first test of the feature will almost always
  be with a game that has not started yet.
- **Fix.** Whatever the answer to Q1, show the bug for 5 s on each entry to FULL while SCHEDULED or PRE, with
  "7:00 PM" on the top line and "Today" on the bottom line (dimmed .70), then hide it until PRE or LIVE. In PRE, show
  "–" in the score slots rather than empty 48 px gaps: empty slots read as broken layout. Ask the owner Q1 with this
  as the default.

### S9. §5.2/§5.4: the Sports footer is 17 px and hand-rolled
- **Problem.** The footer is "17 px .55". The owner's floor is 18 (U-B4), and the shipped footer is 22 at
  `Fg55` (`FooterHints.kt:44`, after the 2026-10-17 type raise; the design reference's 17 is out of date).
- **Fix.** Use the `FooterHints` component at 22. Hints: `← → ↑ ↓ Browse · OK Follow score / Stop following ·
  Back Guide`. Put "Scores and logos: NHL.com" as a separate 18 px `.55` line under the title, or at the right of
  the chip row.

### S10. §4.6: the goal highlight is too faint for the main use case
- **Problem.** Owner ruling 1 says the main use is following a game you are *not* watching. The goal is the one event
  that matters, and the spec gives it a 1.2 s, white-.22 box behind a white digit, in a corner of another channel's
  picture. It is easy to miss with a blink, and white-on-lighter-white lowers the digit's contrast at the moment it
  should be read.
- **Fix.**
  - Duration: 3 s total (150 ms in, 2 400 ms hold, 450 ms out).
  - Box: accent .35, with the new digit kept `Fg`.
  - Centre bottom line: "GOAL" with the team abbreviation for 8 s on the delayed timeline, replacing the period. Add
    "GOAL" to the §0 words list.
  - Still no sound and no motion; low-end mode keeps the "GOAL" text (it costs nothing).
  - Put it to the owner as §10 Q5 with this as the recommendation, not the subtle variant.

### S11. §4.5: the stale and delayed states still look live
- **Problem.** "No update" freezes the clock but draws it at full `Fg`. In a corner glance, a frozen 10:22 at full
  brightness reads as a live clock, and the 20 px line underneath is the only signal.
- **Fix.** While stale or delayed-waiting, draw the clock at `Fg` .55 as well, so the whole centre column dims.

### S12. §2.4/§2.6: the postponed and expiry copy is missing
- **Problem.** POSTPONED → "subscription removed, toast", but no text is specified. The rules require a reason and a
  next step.
- **Fix.** "CGY – EDM was postponed. Follow another game in Sports." Also specify the toast when the Sports switch is
  turned off while a game is followed: "Sports is off. The score is hidden."

---

## Nits

- **N1. §4.4, probing line.** The table hides *both* top corners while the catch-up probing line shows. The line is
  centred at top 72 and roughly 500 wide, so it never reaches x 1416 or x 504. Hide only on actual rect
  intersection; `ScoreBugPlacement` already has the rects.
- **N2. §4.5, wording.** "INT 1st" is not how broadcasts say it: they use "1st INT" or "END 1st". Use **"1st INT"**
  to match the "End 1st" line above it.
- **N3. §4.5, width.** "10:00 PM" at 24/600 measures about 99 of the 106 centre width (estimate). 24 h and 12 h
  strings, "Final/OT", "No update" and "Delayed" need a measured-fit test at both sizes, with a step down to 22
  (never below 18) instead of an ellipsis.
- **N4. §4.4, subtitles.** A bottom corner silently flips to the top while subtitles are on, which looks like the
  setting was ignored. Add to the corner row's detail: "Moves to the top while subtitles are on."
- **N5. §5.4, empty state.** "No NHL games on {day}. Try Tomorrow." is wrong when the day *is* Tomorrow. Use "No NHL
  games {yesterday|today|tomorrow}. Pick another day above."
- **N6. §5.2, chips.** "Score bug settings" sits as a fourth chip in a row of day chips, so Right from Tomorrow lands
  on navigation. Separate it with a 32 gap and a gear glyph, or move it to the title row's right.
- **N7. §5.2, card clock.** "10:22 · 2nd" on a card refreshed every 30 s jumps in 30 s steps. Either tick it from the
  same anchor as the bug, or show only "2nd" plus the clock with no seconds expectation ("2nd · 10 min left" reads
  worse). Ticking is preferred.
- **N8. §5.2, team names.** Cards show abbreviations only, but there is room for names: each card is 576 wide. Use
  "Calgary" / "Edmonton" (placeName) at 26/600, with the abbreviation as the fallback. New users don't know all 32
  codes.
- **N9. §6, picker rows.** The picker needs non-selectable rows for "Loading games…" and the error (`PickerSheet`
  has none), and OK on the error row should retry. Specify the game row text: "CGY 0–2 EDM · 2nd 10:22" or
  "CGY – EDM · 7:00 PM".
- **N10. §5.1, header width.** A fourth header tab takes about 110 px from the sync chip's `weight(1f)` box, and that
  chip's text was just shortened to fit (`f15b27e`). Re-run the header fit test at 1024 px with the longest sync
  text and "Sports".
- **N11. §7, labels.** The row "Sports" inside the section "Sports" is redundant. Call it "Show Sports".
- **N12. §3, accessibility.** Logos need `contentDescription` = the team name, and the bug needs a merged semantics
  string ("Calgary 0, Edmonton 2, 10:22 left in the 1st") for TalkBack users.
- **N13. §4.3, default corner.** Many channels' station logos sit top-right. TR is still the best default (bottom
  corners collide with the bars, S3), but the §4.3 rationale should not claim TR is clear of broadcaster graphics.
