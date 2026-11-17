# NFL Stacked score bug: the YDS (team total yards) column (owner approval 2026-11-16)

Status: the owner approved the column on 2026-11-16, with **no on/off switch**. The design is
`docs/design/m6-nfl-stacked-yds.md`. It was reviewed adversarially (`docs/review/2026-11-16-nfl-stacked-yds-design-review.md`,
ready after fixes) and revised under the lead's rulings. The foundation is built; WP-1 to WP-3 are next. Two lead
defaults are pending the owner (S1, S2) and are built unless the owner objects.

## What
The mock-up's YDS column (`design/nfl-stacked/StackedNFL.dc.html`) opens between the team rows and the clock column at
stoppages. v1 left it out (`docs/design/m6-nfl-stacked.md` sign-off item 6 and owner question 2), because the
scoreboard has no team yards.

## Decisions
1. **Source:** ESPN's per-team statistics, `sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/{id}/competitions/{id}/competitors/{team}/statistics`,
   the first `totalYards`, which is net of sacks (the broadcast total). It runs on the scoreboard's sample. The game
   summary was rejected: it is one sample behind and grows to about 51 KB. The lead's live sampling of 2026-11-16 is the
   evidence; the host answers the app's OkHttp client.
2. **Fetch:** inline in the NFL follow poll, two requests in parallel with a 2.5 s timeout each.
   - It runs only when the polled snapshot is in a stoppage and the bug can be seen: Style Stacked, the full-screen
     player showing the follow.
   - Per stoppage there is one fetch and one refresh 30 s later. A refresh is used only from a poll at least 20 s after
     it.
   - A 429, a 403, or two stalls in a row stop the yards for 10 min. The scoreboard is never affected, and a crash in the
     step is caught.
   - FINAL gets one fetch.
3. **The Delay:** the yards ride on their snapshot, so they never show before that snapshot's score. The one residual is
   recorded: a first fetch in the last seconds of a stoppage can include the next play.
4. **Look:** column 63 design px, digits 21.6, "YDS" 18.
   - The footprint is 396 × 141 (Large 495 × 176.25); the rows never move.
   - Mirrored: `[clock][YDS][rows]`.
   - Negative yards use a real minus.
   - The 1 666 existing golden hashes are unchanged; 26 new shots are added.
5. **Cost:** ≈ 18 KB per fetch, about 0.85 MB a game (≈ 0.27 MB an hour), only with Stacked. The settings data figure is
   left as it is, per-poll for the scoreboard.

## Lead defaults pending the owner
- **S1, the end of the 1st and 3rd quarters.** ESPN shows "End of 1st" for one sample, then about 3 min of the next
  quarter at 15:00 with an "End Period" last play.
  - Default: treat that shape as the end-of-quarter break, in the mapper, so both bug styles read it the same way. The
    shape is periods 2 / 4, last play type 2, clock 15:00.
  - It is cleared by the first play of the new quarter, which may show up to one break-cadence poll late (already true
    for every break).
  - Halftime and overtime are unchanged until the live data shows the same shape.
- **S2, yards during TV timeouts and the two-minute warning** (types 74 / 75; 2.5–3-min stoppages, about 15 a game).
  - Default: yes. The column opens beside the normal down / kick-off bar, which stays exactly as it is.
  - There is no TIMEOUT bar and no pip burn.

## Why
- The column is the mock-up's.
- The per-team feed is the only fresh source of the numbers.
- Riding on the snapshot keeps the user's Delay.
- The SOG column of the NHL Stacked bug is the precedent for its geometry, motion, mirror and stale rules.
