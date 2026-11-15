# NFL score bug: the "Stacked" style (owner request 2026-11-15)

Status: design signed off by the lead 2026-11-15, after an adversarial review (approve with required changes; all nine
applied). Not built yet. Two owner questions are open; the build goes ahead on their defaults.

## Request
"please create the NFL stacked bug please use answers from previous NHL builds for your baseline". Mock-up:
`design/nfl-stacked/StackedNFL.dc.html` ("Stacked NFL bug — scoring, flags, timeouts & halftime"): teams one above the
other with a possession ball and timeout pips, a yards column, a clock column, and a bar under the box for down and
distance, flags, timeouts, scores and halftime.

## Carried rulings (not re-asked)
- **NHL score bug rulings 1–33** (`docs/decisions/2026-11-05-nhl-score-bug.md`), notably: live by default with the user's
  delay (1); nothing before kick-off (4); clock only from the feed (6, 13); small lines dropped (7); badge = logo (8); no
  sound (9); Barlow for the score word (10); the team's own two colours (12, 27); full animation on every box (20); ESPN
  site API, missing fields degrade (21); possession always on in play, off in breaks and at Final (22); the five scoring
  kinds (23); +7 / +8 as two takeovers (25); RED ZONE (26).
- **NHL Stacked** (`docs/decisions/2026-11-10-nhl-score-bug-stacked.md`): the name "Score bug style", Pill default, the
  two places, the sheet stays open; size × 0.45 (owner 1); goal colours = the team's own (owner 2); dark bars keep true
  colours with a hairline and bright stripes (owner 3); the break look (owner 4a); Large offered (owner 8); right corners
  mirrored with the same order from the screen edge, glyphs never flipped, "/" leans kept, spin negated (2026-11-13);
  fixed navy rows, one colour derivation, stripes ≥ 2 : 1, ΔE76 stripe swap; stale / delayed words; layout pinned for the
  takeover; info text 20 px, bar 33.
- **NFL Pill** (`docs/design/m6-nfl.md`): score kind from the point rise; try bookkeeping; possession inference; field
  by territory; tally "24 – 17"; the shared pop rule; the no-spoiler hold; flashes on the 450 ms grid; "EXTRA POINT"
  between a pair's takeovers; the red half beside red teams accepted (lead default, still with the owner).

## Lead rulings (2026-11-15)
1. **Timeout:** trigger on `lastPlay` type 21; the caller is the side whose count dropped below its per-half low-water
   mark; the bar holds while that 21 is the `lastPlay`. Fallback: a drop of exactly 1 with no 21 seen shows the bar for
   8 s (this also covers a lost challenge). Types 74, 75 and the end-of-period types never show it. The play text is
   never parsed and `lastPlay.team` is never trusted for the caller.
2. **Per-half low-water mark** for the timeout counts (a flap 2 → 3 → 2 marks once; the half reset marks nothing).
3. **Penalty:** type 8, or the text contains upper-case "PENALTY on"; a declined foul ("Penalty on …, declined.") shows
   no flag; the `lastPlay` id must be numerically newer than every id seen in the follow. The flag bar holds 8 s.
4. **Precedence:** pinned score > stale / waiting > break / final > flag > timeout > play.
5. **Stale / delayed in play follow the baseline:** "No update" / "Delayed" in the clock slot, bar closed.
6. **No yards column in v1, and none in the footprint:** 333 × 141 Standard, 416.25 × 176.25 Large. Its spec is kept
   in the design's Appendix A, not built.
7. **Overtime pips:** slots = the highest count seen in that half group (at most 3).
8. **Feed facts** corrected from the 2026-11-15 live samples (team timeouts as `lastPlay` type 21 with the count drop in
   the same poll; penalty forms; the kick-off's raw `isRedZone: true`; summary sizes 175 KB live, 662 KB final), with
   eight trimmed fixtures added under `core/src/test/resources/sports/nfl/`.
9. **A kick-off never reads RED ZONE** (the bar reads the gated value); fixture and assertion.
Also: the pip height is raised from 2.7 to 3.0 design px so it is 2 real px at 720p (recorded deviation); the NFL
Stacked colour table is its own object; IND @ NE and DAL @ NYG (bars that look like the rows) go to the owner's
screenshots; one shared setting is built.

## Owner questions (built on defaults)
1. **One "Score bug style" for every sport?** Anyone who picked Stacked for hockey also gets the Stacked football bug.
   *Built on: yes, one shared setting* (Pill stays the default).
2. **The yards column.** The live scoreboard has no team yards; only the game summary has them (about 175 KB early, 660 KB
   at the end). *Built on: left out.*

## Links
- Design: `docs/design/m6-nfl-stacked.md` ("Lead sign-off (2026-11-15)", §13 owner questions, §8 work packages).
- Review: `docs/review/2026-11-15-nfl-stacked-design-review.md`.
- Mock-up: `design/nfl-stacked/`.
