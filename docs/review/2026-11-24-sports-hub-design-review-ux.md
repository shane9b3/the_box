# Sports Hub design review: screen, keys and ease of use (2026-11-24)

Reviewer: adversarial UX reviewer (Opus). Subject: `docs/design/m6-sports-hub.md` (proposed 2026-11-24).
Scope: the screen, the D-pad key model, ease of use and fit to the owner's request. Architecture, data and playback are
covered by the second reviewer and are skipped here except where they show on screen.

Read: `CLAUDE.md`; the proposal in full; the owner's mock-ups `nhl.jpeg` and `nfl.jpeg`; `SportsScreen.kt`,
`SportsViewModel.kt`, `GameCard.kt`, `FooterHints.kt`; `docs/design/m6-score-bug.md` §5; `docs/01-design-reference.md`;
`docs/decisions/2026-11-24-text-sizes.md`; the lead's `FACTS.md`.

Text widths below are estimates at 0.50 to 0.55 em per character for Instrument Sans mixed case at weight 500 to 600
(about 0.6 em for caps at 700). Each overflow called out below is still an overflow at the low end of that range.

**Verdict: not ready for WP-4.** The shell (title row, panel, strip, two-column grid) matches the mock-ups, and the
owner's removals are all honoured. But the key model is wrong in two ways. First, it cannot reach the panel for half the
cards. Second, it gives OK three meanings. And the panel's button row and headline do not fit 664 px at the default text
size. Fix the four blockers and the should-fix items. Adopt the key model in §R or something equally simple. Then WP-4
can start.

---

## Press counts for the five common jobs

Start: the page has just opened and focus is on the first live card (the initial-focus rule). "k" is the goal's position
in the list.

| Job | Proposal | Recommended model (§R) |
|---|---|---|
| Follow a live game | **1** (OK) | 2 (OK, OK) |
| Go to the channel carrying a live game | Left column: 2 (Left, OK), +1 with the sheet. **Right column: 3 or 4 via hold OK** (hold, Down, OK, +sheet); Left is a dead end (U-B1) | 3 (OK, Right, OK), +1 with the sheet |
| Watch a recap (final NHL) | **1** (OK), but only once the recap is posted; before that the same OK follows | 2 (OK, OK) |
| Watch one goal of a final game | Left column: Left, Right, OK, Down, Down, then Down × (k−1), OK = **5 + k**. Right column: hold OK, about 5 Downs, OK, Down, Down, then Down × (k−1), OK = **9 + k** | OK, Right, OK, then Down × (k−1), OK = **3 + k** (in the goals sheet, the goals come first) |
| Check tomorrow's schedule | Up × rows, Right, OK = 3 from the first row | same, 3 |

In the proposal, the jobs the owner asked for (channel, goal video) cost more presses, and how many depends on which
column the card is in. Only Follow is cheaper, by one press.

---

## Blockers

### U-B1. The panel is unreachable from the right-column cards, and Left there depends on timing

**Evidence:** §2.2: "Left from the left column → the panel's primary button". For a right-column card, Left moves focus
to its left-column neighbour, and 250 ms later (§2.2 dwell) the panel switches to that neighbour's game. A second Left
then enters the panel of the *wrong* game. If the user presses Left twice inside 250 ms, the panel never switched, so the
buttons belong to the right-column game, and Back (§2.5 "back to the card the panel shows") lands on a card the user
never entered from.

**Why it matters:** Half the games' Watch on, Highlights, Box score and Matchup buttons can only be reached through hold
OK, which the owner says is hard to discover. Speed of pressing changes what the buttons do. That is the definition of
unpredictable.

**Fix:** Make entering the panel independent of the column. Adopt §R: OK on any card moves focus into that card's panel.

### U-B2. OK on a card has three meanings, and none of them is the panel's white button

**Evidence:**
- §2.3: OK means Follow (not over), Watch recap (final NHL with a recap id) and Follow again (final without a recap).
  A final NHL game therefore changes what OK does when the recap is posted, minutes to hours after the final horn. Until
  then, OK follows a finished game, which only shows "That game is over. Its score shows for 5 minutes."
- §1.5: the live panel's primary (white) button is **Watch on {channel}**, but OK on the live card is **Follow**. The
  pre-game primary is Follow; the final one is Watch recap. The white button is styled as the default action, yet it
  matches OK in only two of five phases.
- Everything else sits behind hold OK or Menu (§2.4). The owner said long-press is hard to discover, and Google TV and
  Chromecast remotes have no Menu key.

**Why it matters:** The owner uses a remote, not a manual. A rule that needs a footer to explain it every time ("the
card's focused chip and the footer always say which") is a rule users will get wrong. Pressing OK on a final game today
follows it; after this change it starts a full-screen video and stops the live channel.

**Fix:** §R. OK has one meaning on every card: open the panel's buttons. The first button is the most likely action, and
the buttons sit in fixed positions.

### U-B3. The live and pre-game button rows do not fit 664 px, even after the "Watch on TV" fallback

**Evidence:** §1.5 buttons are 24/600, a 24 px glyph, padding 28 each side, gap 16.
- Live row with the longest allowed label: "Watch on " + 18 characters ≈ 27 characters ≈ 340 px, + glyph and gap
  34 + padding 56 = **430**; "Follow score" ≈ 150 + 56 = **206**; "Highlights (3)" ≈ 175 + 56 = **231**. With gaps
  32, the total is about **900 px**.
- After the shortening rule ("Watch on TV" ≈ 135 + 34 + 56 = 225), the total is 225 + 206 + 231 + 32 ≈ **694 > 664** at
  the default size, and about 800 at Largest.
- Final NHL ("Watch recap", "Highlights", "Box score", each with a glyph as the mock-up's Box Score has): about 640 at
  default, about **750 at Largest**.
- The fallback also throws away the one fact the owner asked to see, which channel carries the game. "Watch on TV" on a
  TV app says nothing.

**Why it matters:** The third button would be clipped or pushed off the panel at the default size in the most important
phase (live).

**Fix:**
- Put the channel on its own line above the buttons, not in a button label. For example, "On TV: Sportsnet Ontario ·
  in your guide" at 22/500 `Fg70`, with "+2 more" when there are more candidates. This line also carries the
  confidence wording (see U-S10).
- Keep button labels short and fixed: **Follow** / **Following ✓**, **Watch** (CONFIRMED: tunes) or **Channels (n)**
  (opens the sheet), **Goals (n)**, **Matchup**, **Watch recap**, **Box score**.
- Glyph only on the first button.
- `HubPanelFitTest` must assert that the row fits at all four sizes for every phase. A fourth button is never allowed;
  at Largest the third button moves into the sheet of the second.

### U-B4. The headline can lose the score, and the panel shows the score nowhere else

**Evidence:** §1.3 headline: 32/600 on one line, full names, else nicknames, else an ellipsis.
- "Blue Jackets (3) vs. Golden Knights (4)" is 39 characters, about 650 to 690 px at default, so it is at the edge or
  over.
- At Largest (38.4 px), "Maple Leafs (3) vs. Golden Knights (4)" is about 800 px, so it is cut. The ellipsis falls on
  the *home score*.
- The panel has no other scoreline: the stats table and the media area carry no score.

**Why it matters:** The most important number on the panel disappears for common matchups, and always at Largest.

**Fix:** Lay the headline out as `[away name][ (n)] vs. [home name][ (n)]`. Only the names may shrink (32 → 26 × factor),
then fall back to nicknames, then ellipsize; the scores never do. Alternatively, allow two lines (away on line 1, home on
line 2; the media area absorbs 44 px). Put "Blue Jackets", "Golden Knights" and "Maple Leafs" (NHL), "Buccaneers" and
"Commanders" (NFL) and the longest CHL names in the fit test at 1.2×.

---

## Should fix

### U-S1. The focused card's bottom row overflows on live games

**Evidence:** §1.2: the inner width is 424. "● LIVE · 2ND 10:22" (22/700 caps) is about 200 + padding 32 = 232. Add a
12 gap and "OK Follow score" (about 172 + 32 = 204) and the row is **448**. "OK Stop following" makes it about 480. NFL
"● LIVE · Q3 10:22" is the same. The proposal only admits an ellipsis at Largest.

**Fix:** With §R the OK chip is not needed; the footer names OK. Keep the mock-up's chips: the status pill plus at most
one of "✓ Following" or "● Highlights". Specify that the chip, never the pill, gives way.

### U-S2. The panel can show the wrong game, and the loading state is not specified

**Evidence:**
- §2.2: for 250 ms after focus moves, the panel shows the previous game beside the newly focused card.
- §2.1: with focus on a chip the panel "keeps the last game shown". After OK on "Tomorrow", the panel shows a game from
  another day beside tomorrow's grid.
- §4.5 covers failure, but nothing says what the stats block, summary and media area show between the switch and the
  detail arriving (600 ms dwell plus the network: a second or more on a Stick).
- A late `Ready` for the previous game must not paint under the new headline; §4.1's `DetailState.Ready` carries the id,
  but the spec does not say that the UI checks it.

**Fix:**
- Switch the pill, headline and matchup art in the same frame as focus. That data is local, so no debounce is needed.
  Keep the 600 ms dwell for requests and for the poster.
- Loading: the phase's stat labels with "–" values in `Fg40`, no summary lines, and the matchup art until the poster
  decodes. The layout must not jump.
- `HubPanelModel` is built only from detail whose `id` equals the shown game.
- On a new board, the panel shows the initial-focus game of that board (followed, else first live, else the first
  card).

### U-S3. A re-poll reorders the cards under the user's focus

**Evidence:** `SportsViewModel.order` sorts live first, then pre-game, scheduled and final. On a busy night a game going
live or final moves its card to another row on the next 30 s poll. The lazy grid keeps focus on the key, but
`ensureVisible` runs only on a focus change (`SportsScreen.kt` `onFocus`), so the focused card can end up off-screen. The
card the user was about to move to also changes.

**Fix:** Freeze the order while the page is open on that day. Compute it on board load, day change and page entry; keep
it across re-polls; append new games at the end. Phase is shown by the pill, not by position. If the order must change,
re-run `ensureVisible` on the focused key after the layout pass.

### U-S4. The panel's buttons change under focus

**Evidence:**
- §1.5: pre-game is [Follow, Watch on, Matchup]; live is [Watch on, Follow, Highlights]. A user resting on button 1 of a
  pre-game panel at face-off presses OK expecting Follow and is tuned away to full screen.
- The channel button appears only after the matcher finishes (600 ms dwell + 30–150 ms, §5.7), so the row shifts while
  the user is pressing.
- "Highlights (n)" changes width as clips post.

**Fix:**
- Fixed positions in every phase: slot 1 Follow (Watch recap when final), slot 2 the channel (shown as "Finding
  channel…" in place while the matcher runs; "No channel found" as plain text when it finds none), slot 3 Goals or
  Matchup or Box score.
- Restore focus by action identity across rebuilds; if that action is gone, keep the index; with no buttons, return to
  the card.
- Start the match the moment OK opens the panel, not only after the dwell.

### U-S5. Day strip: focus landing and widths

**Evidence:**
- Up from the grid goes to "the selected day chip" (§2.2). If the user scrolled the `LazyRow` without pressing OK, that
  chip may not be composed, `requestFocus` fails and focus is lost.
- Down from a chip is left to geometry.
- Widths: "Yesterday · Nov 23" ≈ 286, "Tomorrow · Nov 25" ≈ 272, "Sat · Nov 21 · No games" ≈ 350 in an 864 px viewport.
  About three chips show at default and about 2.5 at Largest, so §3.1's "one chip of context on each side" cannot hold
  at Largest.
- Reaching +7 takes 7 presses plus OK. That is acceptable, but the chips give no week landmark.

**Fix:**
- Before Up's `requestFocus`, scroll the strip to the selected chip (`scrollToItem`, then a frame, then the request).
- Down from the strip goes to the card focused last on this day, else the first card.
- Drop "· No games" from the visible label: dim the chip and put "No games" in its content description.
- At Largest, centre the focused chip and drop the context rule.
- Labels: "Sat · Nov 21" is fine. The mock-up's caps ("YESTERDAY (NOV 23)") are a style choice; see U-N1.

### U-S6. NFL upcoming cards say "1:00 PM" with no day

**Evidence:** §1.2: the status is "`7:00 PM` (no day: the strip names it)". For the NFL the strip names a *week*, so
Thursday, Sunday and Monday games all read "1:00 PM" or "8:15 PM".

**Fix:** NFL cards and the NFL panel pill show "Sun 1:00 PM" (the day of week, plus the date when the game is outside the
current week). The NHL and CHL keep the time only.

### U-S7. The NFL and CHL panels look half-empty beside the mock-up

**Evidence:**
- §7.1: a final NFL game is a pill, a headline and a 664 × ~560 block of matchup art, with no stats, no summary and, when
  there is no catch-up airing, no buttons.
- The owner's NFL mock-up shows team stats, a summary, Watch Recap and Box Score.
- CHL final shows at most "Shots · 21 – 15".

**Why it matters:** The NFL is the owner's second league and the mock-up he drew. A panel that is mostly a coloured
diagonal will read as broken.

**Fix:**
- Fill the NFL panel from the scoreboard body the app already fetches (WP-1 to verify against a recording): the line
  score by quarter as the table (Q1 to Q4, OT, T), team records, and the game leaders (passing, rushing, receiving) as the
  summary lines. That is a "stats table" with no new request.
- CHL: the line score by period, and shots, when the board has them.
- When nothing is available, shrink the panel to the pill, headline, art and buttons, and say once, in one line, "More
  game details come for the NHL first." Add an owner question that says plainly the NFL and CHL get no video or box score
  in this version.

### U-S8. The summary lines are cut mid-thought

**Evidence:** §1.3: two lines of 24/400, one line each, with an ellipsis, in 664 px (about 55 characters).
- "Winning goal · " takes 15 of them, so a clip's `long_description` shows about 40 characters: "Kyle Connor sends the
  feed to Gabriel V…".
- "Stars · S. Skinner (2.00 GAA, .943) · G. Vilardi (3 G) · K. Connor (2 A)" is 72 characters.
- The built sentence "G. Vilardi (4), 1st period 5:11, from K. Connor and J. Morrissey." is 66.
- At Largest, about 46 characters fit.
- The mock-up's summary is a wrapped 2–3 line sentence.

**Fix:**
- Line 1 may wrap to two lines (the wrapped sentence replaces line 2 when they cannot both fit).
- Stars become "Stars: Skinner, Vilardi, Connor" (names only; stat lines go in the box score).
- The built goal sentence becomes "Vilardi (4th) from Connor and Morrissey, 1st period 5:11".
- Use "Game-winning goal" (the NHL's term) instead of "Winning goal".
- Measure every phase's lines in the fit test with the fixtures.

### U-S9. Safe margins and the footer

**Evidence:**
- §1.1: top padding 48 and a footer to y 1048. The 5 % title-safe area is 54 px top and bottom (1026). The guide uses 56
  (`01-design-reference.md` grid.panel). Today's Sports page ends its footer at 1040.
- The footer shares its row with a right-aligned credit and "Updated 2 min ago", but `FooterHints` measures against
  `FOOTER_MAX_W` (1792), not the space left by the credit. At 1.2×, "Hold OK More" (the only hint for the hidden list)
  is the hint that gets dropped or overlapped.

**Fix:**
- Use 56 top and bottom: title row 56–120, panel and right column from 148, footer 976–1024, grid viewport 280–960
  (two rows of 304 + 24 + a 48 px peek), panel height 812 (the media area gives up 24 px).
- Pass `maxWidth = 1728 − creditWidth − 32` to `FooterHints`, or move the credit back under the title as today.

### U-S10. The channel wording claims too much

**Evidence:**
- §1.5: the button reads "Watch on SN Ontario" for the top candidate even when it is LIKELY or POSSIBLE, or when OK
  opens a sheet of eight.
- Pre-game and scheduled panels (tomorrow's game too) offer "Watch on {channel}", which tunes now to whatever is airing.
- §5.3's "Listed broadcaster: Sportsnet (national, Canada). Not in your guide data." uses jargon ("guide data").

**Fix:**
- Name a channel on a button only when the match is CONFIRMED and the game is live or starts within 30 minutes. In all
  other cases use the info line of U-B3 and the label "Channels (n)".
- Upcoming games: "On TV at 7:00 PM: Sportsnet Ontario" as text, no tune.
- Rewordings:
  - LIKELY: "Sportsnet carries this game in Canada. Your guide doesn't list it on this channel."
  - POSSIBLE: "Home team's local channel. It may show a different game where you live."
- Keep the closing blackout line.

### U-S11. Error texts point at the wrong key or lack a next step

**Evidence:**
- §4.5: "Game stats did not load: NHL.com did not answer. They retry by themselves; press OK to try now." With focus on
  the card, OK follows the game; Retry is reached by Left, and only from the left column.
- §6.7 "This video is not available in your region." has no next step.

**Fix:**
- Stats failure: "Couldn't load game stats from NHL.com. Trying again automatically. Select Retry to try now." With §R,
  OK on the card lands on Retry.
- Region: "This video isn't available in your region. The scores and stats still work."
- Prefer contractions and plain verbs throughout ("didn't", "Select").

### U-S12. Two focus-looking things at once

**Evidence:**
- The app's focus language is the `#EEF1F6` light fill (guide cells, today's focused card).
- The proposal makes the panel's primary button and the *selected* day chip permanently `#EEF1F6`, while focus on cards
  becomes a 4 px ring on a fill only one step lighter (`#171C24` → `#1F2530`).
- From 3 m away, the white button and the white chip read as the focus, and the real focus (a blue ring) is the weaker
  signal.

**Fix:**
- Keep the mock-up's look, but make focus unmistakable. Focused card: ring 4 px plus a fill of `#2A3340` or lighter.
  Focused button: the white fill *and* the ring.
- While focus is outside the panel, draw the primary button as secondary (White10) with a white label, so that only one
  thing on screen looks pressed.
- Selected day chip: keep white, but at 85 % opacity, and give the focused chip the ring plus an `Accent` underline.

### U-S13. Accessibility order, the panel heading and the missing "refused" state

**Evidence:**
- §8.2 traversal: title, *panel buttons*, heading, strip, grid. The panel buttons belong to a game the user has not
  chosen yet.
- "Its heading is read when focus enters the panel", but focus enters on a button.
- The current `GameCard` has a refused state (the feed refused the app: the reason in `Warn` and a matchup line); the
  new card spec (§1.2) drops it.

**Fix:**
- Traversal: title row, strip, grid, panel, footer.
- Make the panel a focus group with `paneTitle` "Game details: Mammoth at Jets", so it is announced on entry.
- Card description: add the panel's first action ("Press OK for options: Follow, Watch, Goals").
- Carry the refused state into `HubCard` (it replaces the two team rows as today) and into the panel (the reason in place
  of the stats).

### U-S14. Where focus goes back after sheets and the clip player

**Evidence:**
- §2.7: "from a sheet or list → what opened it", but §2.4's list closes itself when a row runs, so Back from a sheet
  opened from the list has nothing to return to.
- §6.5: Back from a clip returns "focus on the row … that started it", but the row is inside a sheet the spec does not
  say stays open.
- The board can re-poll or reorder while a clip plays.

**Fix:**
- The Goals sheet stays open under the clip (not drawn), and Back from the clip returns to it with focus on the clip
  just played, so "watch the next goal" is one Down plus OK.
- Back from the sheet goes to the panel button that opened it.
- Every return restores by game id and action, never by index, with the card as the fallback.

### U-S15. Long CHL names and French name order

**Evidence:**
- §1.2: the name column is 220 (248 at Larger and Largest), with a shrink to 24 × factor and then an ellipsis.
- "Blainville-Boisbriand" is about 260 to 275 px at 24 px, so it ellipsizes at every size. "Sault Ste. Marie" fits only
  after shrinking.
- §4.6 derives the place line as "the full name minus the card name suffix". QMJHL names written in French order
  ("Saguenéens de Chicoutimi") have no such suffix and fall to one line, so the cards are inconsistent.

**Fix:**
- Allow the place line to break at a hyphen into the nickname line's style only when the nickname is short, or show a
  short place from a table ("Blainville-Bois." is poor; better a `ChlTeamPlaces` table like `NhlTeamPlaces`).
- List the 61 CHL place / nickname pairs in the test, as the NHL's 32 are.

---

## Notes

- **U-N1. Departures from the mock-up the owner may notice.**
  - The day chips are mixed case ("Yesterday · Nov 23"); the mock-up uses caps ("YESTERDAY (NOV 23)"). The tag reads
    "RECAP"; the mock-up reads "RECAP & STATS".
  - The focused card shows "OK Watch recap" instead of the mock-up's "● Highlights".
  - The mock-up's headline puts the home team (Jets, 4) first; the proposal puts away first.
  - Keep away first (it matches the cards and NHL.com), but "Mammoth (2) vs. Jets (4)" with away first is unusual in
    American usage. "Mammoth 2, Jets 4" or "Mammoth at Jets" (before the game) reads more naturally. Ask the owner in
    one line.
- **U-N2. Fewer games on screen.** Today's page shows about 9 cards (3 columns × 208). The new one shows 4 plus a peek.
  A 14-game Saturday needs 6 Downs to reach the last row, and the panel changes at each. Accepted for the mock-up's look,
  but put it to the owner. A "Live now" filter chip would give a short way through.
- **U-N3.** "Watch" tunes and goes to full screen, and Back then goes to the guide, not back to Sports (§5.5, as Search
  does). Say so in the decision note; the owner may expect Back to Sports.
- **U-N4.** At Largest the media area is 175 tall × 664 wide (3.8 : 1); a 16:9 poster loses more than half its height
  and heads are cut. Consider anchoring the crop at 30 % from the top instead of the centre.
- **U-N5.** §1.4's "250 − 75 = 175" does not count the pill and the button row growing. In the pre-game panel at
  Largest, the media area falls to about 129 and drops a summary line. That is fine, but state it.
- **U-N6.** "Follow" on a final or postponed game (NFL and CHL today, and the panel's slot 1) only shows a finished score
  for five minutes. Hide Follow once a game is final or postponed.
- **U-N7.** The Box score and Matchup sheets have no specified contents or layout (§6.4 gives only the frame). A box
  score of about 20 skaters per team plus goalies in 968 × 836 needs a decision (two tabs, Away | Home, Left / Right to
  switch).
- **U-N8.** The cards never show whether a channel was found (the match runs only for the settled game). That is fine
  for cost; a later version could mark cards the guide confirms.
- **U-N9.** The 76 px score column at 52/700 × 1.2 holds two tabular digits with about 1 px to spare; give the score
  the same shrink rule as the names.
- **U-N10.** The footer hint "Hold OK More" becomes unnecessary under §R; on Fire TV, where hold OK sends repeated
  centre events, removing it also removes a class of accidental opens.
- **U-N11.** The play disc on the poster is decoration, which is right for D-pad use; the media area must not be
  focusable (it is not), or users will try to "click the video".

---

## §R. The key model I would ship

One rule per place, no long-press, no column-dependent paths.

**Grid (cards)**
- The arrows move between cards. The panel's pill, headline and art switch in the same frame. The detail, the poster
  and the channel match start after 600 ms of rest.
- **OK, or Menu: focus moves into the panel onto slot 1**, from any column, league or phase. The card keeps a 2 px
  `Accent` outline ("selected") while the panel has focus, so the user can see which game the panel belongs to.
- Left from the left column does the same as OK (a shortcut, not the only way). Right from the right column does
  nothing.
- Up from the first row: the selected day chip (scrolled into view first). Back: the guide.
- Footer: `← → ↑ ↓ Browse · OK Options · Back Guide`.

**Panel**

| Phase | Slot 1 (white when focused) | Slot 2 | Slot 3 |
|---|---|---|---|
| Scheduled, pre-game | Follow / Following ✓ | Channels (n) or "On TV at 7 PM: …" text | Matchup (NHL) |
| Live, intermission | Follow / Following ✓ | Watch (one CONFIRMED channel) or Channels (n); "Finding channel…" while matching | Goals (n) (NHL) |
| Final NHL | Watch recap ("Recap not posted yet" disabled until it is) | Goals | Box score |
| Final NFL and CHL | Watch from start (catch-up) | – | – |
| Postponed, or nothing to do | no buttons: OK on the card does nothing, and the footer has no OK | | |

- Left and Right move between buttons. Right from the last button returns to the card. Up and Down do nothing. Back
  returns to the card.
- Slots never reorder. Across a re-poll, focus stays on the same action; if that action is gone, the same slot; if there
  are no buttons, the card.
- Footer: `← → Choose · OK {label} · Back Games`.

**Sheets**
- Goals: the goals first (focus on the latest goal for a live game, the first goal for a final one), then "Game recap"
  and "Condensed game".
- Channels: the candidates, with the catch-up rows last.
- OK plays or tunes. Back returns to the button that opened the sheet. The Goals sheet survives a clip and gets focus on
  the clip just played.
- The §2.4 actions list is dropped: everything it held is in a slot or a sheet.

**Why:** OK always means "show me what I can do with this game", and the most likely answer is one more OK. The cost is
one extra press for Follow (2 instead of 1). The gains:
- every card reaches its own panel;
- no long-press;
- no action changes under the thumb;
- the jobs the owner asked for (channel, goal video) get shorter.

If the owner insists on one-press Follow, the only predictable variant is OK = Follow on every card, always (no recap
exception), with Menu entering the panel. That leaves Google TV remotes, which have no Menu key, without the panel, so I
do not recommend it.
