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

## Owner rulings: power play (2026-11-07)
Request: a power-play tab on the bug with a reveal and a close animation. Mock-up: `design/score-bug-power-play.html`
(readable markup and CSS in `design/score-bug-power-play.template.html`): the tab slides out from under the pill
(.55 s), team-colour gradient, "EDM POWER PLAY | 1:32", text fades in, a shine sweeps, the label pulses twice; the
pill tints from the power-play team's side and that team's logo glows; the close reverses it.
13. **Power-play time only from the feed.** The tab's clock shows exactly what the latest update reports and changes
    only when an update arrives; no local countdown (as ruling 6 for the game clock).
14. **Situations: power plays only, 5-on-3 noted.** A two-man advantage reads "EDM 5-ON-3"; empty net and 4-on-4 show
    no tab.
15. **Power-play goal: the tab says "PP GOAL".** During the goal takeover the tab stays open with "PP GOAL", then
    closes when the takeover ends. No label in the takeover (ruling 7 stands).
16. **Bottom corners: the tab opens upward** out of the top of the pill (a mirror image); top corners drop down.
Lead defaults: team colours per ruling 12; the logo glow is baked (no real-time blur); type ≥ 18 px; follows the
user's delay; nothing before puck drop.
17. **Orange tabs as in the mock-up** (owner, 2026-11-07): EDM, ANA and PHI keep an orange tab. Met without a contrast
    exception by darkening the gradient's far stop 10 % for every team (review Q1: EDM 4.69, ANA 4.91, PHI 4.95 : 1).
18. **6-on-4 keeps the tab**: the power-play team pulling its goalie is still a power play. Only an empty net at even
    strength shows nothing.
19. **Intermission keeps the tab** with the frozen time the feed reports.

## Owner rulings: 2026-11-08
20. **Full animation on every box** (owner: "give them all animation"). The score bug, goal takeover and power-play
    tab ignore the low-end device class (memoryClass ≤ 192, which includes Fire TV Sticks): fades, slides, glow,
    shadow and baked masks all run. Only the system "Reduce animations" setting gives the still version. Other player
    overlays keep their low-end snaps. To check on a real Stick: frame time during a takeover.
Bug fix, same day: the NHL feed's `OVER` game state (just ended, before `FINAL`/`OFF`) was read as "not started", so
the bug vanished in the closing minutes of the owner's test game. `OVER` now maps to FINAL.

## Owner rulings: NFL bug (2026-11-08)
Request: "lets add nfl". Mock-up: `design/nfl/Touchdown.dc.html` (assets in `design/nfl/assets/`): a wider pill with
logo + score each side, quarter + clock, a down-and-distance column ("1st & 10"); a tab under the pill always showing
possession and field position ("KC BALL | OWN 35", "KC BALL | EXTRA POINT", "KC KICKOFF"); a 3.6 s scoring takeover
in the team's colours (lead wipe, streaks, a ball run for a touchdown, an arc and goalposts for kicks, letter-by-letter
word, logo, "+6" and the tally), the pill glow, the tab flashing "TOUCHDOWN | KC +6", then the score pop.
21. **Data: ESPN's public site API** (site.api.espn.com; free, no key, undocumented). A missing or changed field degrades
    the bug (e.g. no down column) instead of failing it.
22. **The possession tab is always on during play**; hidden in breaks (between quarters, halftime) and at Final.
23. **Scores animated: touchdown +6, extra point +1, field goal +3, plus safety +2 and two-point conversion +2.** A
    defensive or return touchdown is TOUCHDOWN.
24. **Game list: a league switch** (NHL / NFL) at the top of the follow list; one league shown at a time; one game
    followed at a time.
Owner answers to the design's open questions (2026-11-08):
25. **Touchdown and extra point in one update (+7 / +8): two takeovers**, TOUCHDOWN +6 then EXTRA POINT +1 (or
    TWO-POINT +2), back to back, as if the updates had arrived separately.
26. **Red zone on the tab**: while the offence is inside the opponent's 20, the tab's right half turns red and reads
    RED ZONE.
27. **Colour picks**: BAL purple + gold, DAL navy + silver, JAX teal + gold, TB red + pewter.
28. **The NFL list is the ESPN week** (Thursday, Sunday and Monday games together), with Last week · This week · Next
    week chips.

## Update interval (owner, 2026-11-08; design docs/design/m6-sports-update-interval.md)
Request: "an option for the sports for the update time between each one with a recomended depending on how big the
data is it is receiving"; Recommended is to follow "the amount of time it takes to grab the data parse it and then
display it", starting low "like 2-4 seconds so its almost live".
29. **One update-time setting per league**; the Sports settings row shows the league on screen.
30. **NHL list starts at its feed floor, 20 s** (the NHL CDN serves `max-age=19` buckets; faster polls get the same
    answer). NHL: Auto · 20 · 40 · 60 s.
31. **New installs start on Auto**: Recommended from the feed's refresh and the box's measured fetch + parse + display
    time.
32. **NFL floor is 5 s** (ESPN rate limits are unknown; the owner chose the safer floor over 2 s). The proposal's 2/3/4 s
    NFL choices are dropped and Auto never recommends under 5 s.

## Hockey breaks (owner, 2026-11-08)
33. **No clock in a hockey break.** The centre column is one line: "End 1st" at 0:00 before the intermission starts,
    then "1st INT" through the intermission (no countdown, no frozen clock). Amends ruling 6 for breaks only; play keeps
    the feed's clock.
