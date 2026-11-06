# NHL score bug and Sports tab (owner request 2026-11-05)

Status: design signed off 2026-11-05; implementation in progress (`docs/design/m6-score-bug.md`).

## Request
A small score bug in a corner of the user's choosing: both team logos, both scores,
the period and the time left in it. The user subscribes to one game and the bug shows
over any channel. Start with the NHL. Look: dark glass, very modern, fully rounded
corners. Mock-up: away logo · away score | clock over period | home score · home logo
(`design/score-bug-mockup.png`).

## Owner rulings (2026-11-05)
1. **Live by default.** The main use is following a game you are *not* watching, so
   updates show as soon as the feed has them. A **user-set delay** is an option
   (default 0 s) for when you are watching the game on a lagging stream.
2. **Both entry points.** A new **Sports** tab in the top bar (today's games and
   scores, the start of a sports hub; subscribe from there) and a **Score bug** item
   in the full-screen player's quick bar (pick a game, change the corner, turn it off).
3. **Full-screen player, always on** while the subscribed game is live, on any
   channel. It clears itself a few minutes after the final. Not shown over the
   guide's mini player.

## Constraints carried from the plan
- No real-time blur: "glass" is a translucent dark fill + top highlight + hairline
  border, drawn above the root `SurfaceView` without touching its ancestors.
- Nothing is fetched until the user opens Sports or subscribes (no background
  traffic by default; consistent with the no-telemetry rule).
- Overlay sizes in design px (screen width ÷ 1920).

## Owner rulings after the design review (2026-11-05)
4. **Pre-game: nothing until puck drop.** The full-screen player shows no bug (no
   peek, no start-time pill) while the followed game is FUT or PRE; it appears when
   the game goes live. The Sports tab still lists the start time.
5. **Goal: highlight + tag, no sound.** The scoring side's accent box for 3 s and a
   "GOAL CGY" tag for 8 s, following the user's delay.

## Lead rulings (2026-11-05)
- No remote kill switch for the unofficial NHL feed in this release. If the feed
  breaks, the bug goes stale/hidden and the Sports tab names the reason; a fix ships
  as an app update (ESPN scoreboard is the documented fallback source).
- Every Sports settings row is ADVANCED; the simple-settings budget stays at 35.
- Reviews: `docs/review/2026-11-05-score-bug-design-review-{arch,ux}.md`; every
  finding's disposition is in the design's "Lead sign-off (2026-11-05)" section.
