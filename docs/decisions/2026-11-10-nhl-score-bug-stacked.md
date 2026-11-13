# NHL score bug: the "Stacked" style (owner request 2026-11-10)

Status: built 2026-11-10 (merge `af08859`), fix pass 2026-11-11 after two code reviews. The emulator hand pass and the
hardware checks (design §10.4) are still open.

## Request
"An option for a different theme for the NHL score bug." Mock-up: `design/nhl-stacked/Stacked.dc.html` (the teams one
above the other, a shots-on-goal column, a clock column, and a bar under the box for power plays and breaks).

## The option and its default
- One setting, **Score bug style**: *Pill* · *Stacked*. **Pill stays the default**, so nothing changes for existing users.
- Stored as `SportsPrefs.nhlStyle` in the backed-up `sports` domain; app-wide, never per playlist.
- Reachable from Settings → Sports (ADVANCED) and from a **Style** row in the quick bar's Score bug sheet (OK toggles;
  the sheet stays open). The row shows for a followed NHL game, or, with nothing followed, while the sheet browses the
  NHL.
- NHL only: an NFL game always draws the NFL bug.
- Words on screen: "Score bug style", "Style", "Pill", "Stacked". Never "theme".

## Look-and-feel defaults (design §12): all eight confirmed by the owner, 2026-11-11
Each is built with the default shown; the owner screenshots decide.
1. **Size.** The mock-up × 0.45 (about 392 × 141 with the bar), not the larger × 0.50 (435 × 153). *Confirmed by the owner, 2026-11-11.*
2. **Goal colours.** Each team's own two colours, as the current goal animation uses them (Edmonton: a navy panel with an
   orange sweep, the reverse of the mock-up). *Confirmed by the owner, 2026-11-11.*
3. **Power-play bar for navy, black and grey teams** (Toronto, Buffalo, St. Louis, Tampa Bay, Nashville, Winnipeg,
   Seattle, Los Angeles, Vegas, Utah, Boston, Pittsburgh). The teams' real colours, with a thin light line and bright
   sliding stripes so the bar always reads. *Confirmed by the owner, 2026-11-11.*
4. **A power play that carries into intermission.** 4a: an ordinary intermission is exactly the mock-up ("1ST
   INTERMISSION", the clock column folds). 4b: a power play carried over the break keeps its bar with the frozen time
   ("EDM POWER PLAY 1:12") and the clock column reads "1ST" over "INT". *Confirmed by the owner, 2026-11-11.*
5. **End of a period** (0:00, before intermission). The clock column shows "1ST" over "END". *Confirmed by the owner, 2026-11-11.*
6. **Shootout.** Treated like a break: the clock column folds, shots stay open, the bar says "SHOOTOUT", then changes to
   "FINAL/SO"; the winning shootout goal plays the goal animation. *Confirmed by the owner, 2026-11-11.*
7. **Right-hand corners.** The team rows never move; the box's right edge moves as the columns open and fold, and at even
   strength the box sits a little in from the screen edge. *Confirmed by the owner, 2026-11-11.* **Superseded by the
   owner's request of 2026-11-13** (below): the right corners are mirrored.

## Right-hand corners mirrored (owner request 2026-11-13)
"In the stacked nhl scorebug when it is on the right side of the screen can we mirror the layout so it sits closer to
the edge of the screen like it does on the left, I think it leaves room for the sog when there is a penalty, but if we
mirror the layout it will work and look better on the right, the left layout looks great."

- In the two right-hand corners the layout is mirrored: the rows hug the right margin (1920 − 96, as the left corners
  hug 96) and the SOG and clock columns open and fold to their left, in the order clock, SOG, rows. The rows still never
  move. The box now ends at the margin at even strength, on a power play and in a break; the earlier "box sits about
  59–90 px in on the right" default is gone.
- Reading order is kept: logo, code and score read left to right, and the bar's words read left to right,
  right-aligned. Edge features mirror: the team stripes are on the right edge, the bar's two stripes sit at its right
  end (keeping their "/" lean to match the italic words), the columns and their contents slide out leftwards.
- The goal animation's wipes start at the screen edge and its streak sweeps right to left; the badge, GOAL and the
  score lines are not mirrored.
- The left corners are pixel-identical to before (all 306 existing Stacked screenshots byte-identical).
- Design: `docs/design/m6-score-bug-stacked-theme.md` §4.3. Code: `ScoreBugPlacement.stackedMirrored`,
  `StackedDrawArgs.mirrored`.
8. **Large size.** Offered for Stacked as for the current bug (25 % bigger). *Confirmed by the owner, 2026-11-11.*

## Lead rulings from the design sign-off (2026-11-10)
- The name, the Pill default and the two places for the setting; hockey only; the Style row's gating (followed league,
  else the browsed league); the sheet stays open on toggle.
- Fixed navy rows (`#12284F`); the team-colour glow (not the mock-up's red); team-true colours through one derivation
  (`NhlStackedColours`), every bar with a hairline and stripes ≥ 2 : 1 against it; the home-team stripe swap rule for
  look-alike teams (CIELAB ΔE76 < 20, not a contrast ratio).
- Scale 0.45 with the information text raised to 20 px (bar text, tally), bar 33 px; the small name line above GOAL
  dropped (ruling 7 precedent); the power-play goal goes to the bar ("EDM POWER PLAY GOAL").
- The flash stays at 3 × 400 ms (the flash ruling); a real Instrument Sans Bold Italic for the bar.
- The final look; stale and delayed say "NO UPDATE" / "DELAYED" (no reopening); 5-on-4 ⇄ 5-on-3 and break ⇄ stale are
  text swaps.
- The layout is pinned for the whole takeover and the glow is baked for the exact box (B4); the period label is kept in
  stale and waiting views (`ScoreBugView.period`, B1).
- The settings store reads an unknown enum name as that field's default (`coerceInputValues`, S7), so a newer backup
  never resets a whole domain.

## Fix-pass rulings (2026-11-11, after the code reviews)
- The clock is tabular with 5 px of air a side; clock-column words keep 6 px; "No update" wraps onto two lines in the
  lower slot ("No" / "update").
- A right-to-left locale cannot move the box (layout direction pinned left-to-right).
- A follow that ends fades out with its own colours and layout; the runner resets after the fade.
- A back-to-back goal during the pin hides an earlier "POWER PLAY GOAL" bar's words only; the bar closes at the 3 600
  release, so the pinned box and its glow keep their size (rule 5 reconciled with B4).
- `coerceInputValues` never weakens parental settings: the parental document is decoded strictly, so a restore holding a
  value this build does not know keeps the device's parental document whole.

## Links
- Design: `docs/design/m6-score-bug-stacked-theme.md` (§12 owner questions; "Lead sign-off"; fix-pass markers).
- Design review: `docs/review/2026-11-10-stacked-theme-design-review.md`.
- Code reviews: `docs/review/2026-11-10-stacked-style-review-core.md`, `docs/review/2026-11-10-stacked-style-review-ui.md`
  (each ends with its "Fix pass (2026-11-11)" dispositions).
- Standing rulings: `docs/decisions/2026-11-05-nhl-score-bug.md`.
