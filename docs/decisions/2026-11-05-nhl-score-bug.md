# NHL score bug and Sports tab (owner request 2026-11-05)

Status: done 2026-11-05 (fix pass 7677403); see the design's Integration and Fix pass sections (`docs/design/m6-score-bug.md`).

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

## Owner ruling after the integration pass (2026-11-05)
6. **No local countdown.** The bug shows the clock exactly as the latest feed update
   reports it and changes only when an update arrives (steps of roughly 15–30 s during
   play, the feed's own cadence). No extrapolation between polls, no 40 s cap, no
   "never count up" hold. Reason: a local countdown runs on through whistles the
   client has not heard about yet (seen on the real NSH–COL game: it ran past a
   stoppage, held, then jumped back up to 5:18). The feed value is never ahead of the
   real game. Applies to intermission too. The user's delay still applies (the
   snapshot queue is unchanged). Consequence: no per-second clock redraw, so the
   ticking-bug frame budget and `DRAWS_SCORE_CLOCK` device item are moot; the bug
   redraws only on a new snapshot.

## Owner rulings: goal animation (2026-11-07)
Request: a goal-scoring animation in the scoring team's colours. Mock-up: `design/score-bug-goal-animation.html`
(bundled page; readable markup and CSS keyframes in `design/score-bug-goal-animation.template.html`). A 3.6 s
takeover of the whole pill: accent-colour lead wipe, base-colour panel wipe, accent and white streaks, white flash
pulses, a badge that spins in, "GOAL" letters dropping in then pulsing, the tally sliding in; the bug glows; after it,
the scorer's score pops (scale 1.55 → 1, accent → white).
7. **Small lines dropped.** At the real bug size the team-name line and the "10:22 · 1ST" line fall below the 18 px
   type floor, so the takeover shows only the badge, GOAL and the tally. The bug keeps its size.
8. **Badge: the team logo on the team colour.** The real NHL logo drawn straight on the base-colour panel, no white
   disc or ring (owner change 2026-11-07; first answer was "logo on white").
9. **Replaces ruling 5's highlight and tag.** The takeover plus the score pop replace the 3 s accent box and the 8 s
   "GOAL CGY" line. Still no sound; still follows the user's delay.
10. **GOAL font: Barlow Condensed Black Italic**, bundled (SIL OFL 1.1, one weight, used only for GOAL).
Defaults (lead): team colours from a bundled 32-team table (the feed has none); low-end mode and Android's "remove
animations" get a still version.
11. **Shootout winner plays the takeover** (owner, 2026-11-07; the proposer and reviewer recommended no). The winner's
    +1 at the end of a shootout counts as a goal for the animation.
12. **Always the team's own colours** (owner, 2026-11-07: "it should always be the teams color not just orange, for
    all goals"). Every team's takeover uses its two official colours: base = the primary (a gold or light primary goes
    to the accent slot: NSH, VGK, and similar), accent = the official secondary. No substitute colours for contrast:
    PHI is orange + black (white GOAL on orange, 3.6 : 1, accepted), NJD red + black, MTL red + blue. Colours verified
    2026-11-07 (teamcolorcodes.com; Anaheim's own brand guide); `scratchpad` report folded into the design §3.3.
Lead: the white flash runs 3 cycles of 400 ms (2.5 Hz), below the 3-flashes-per-second limit on frequency alone
(review B3).
