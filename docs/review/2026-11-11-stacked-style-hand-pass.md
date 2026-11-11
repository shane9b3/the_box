# Stacked score bug style: emulator hand pass (2026-11-11)

Build: `app-universal-debug.apk` from `af08859`, installed with `adb install -r -d` (data kept). AVD `TheBox_TV_API30`
(1920 × 1080, API 30 TV, `-gpu host` with the usual feature flags). Fake panel:
`fakepanel.py --port 8080 --sports --sports-pp --sports-stacked --sports-timeline 720` (the emulator's playlist is the
13-channel catalog panel at 10.0.2.2:8080; the `files/debug/sports_base` override already pointed at it). Design:
`docs/design/m6-score-bug-stacked-theme.md` §8, §10.4.

Screenshots live in the session scratchpad,
`/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/stacked-int/`
(called `S/` below). Motion was judged from `adb shell screenrecord` clips (`goal1.mp4`, `p2.mp4`, `p3a/b.mp4`, `lg.mp4`,
`still*.mp4`), which the emulator captured at about 20 fps: enough to see the order and the end states of each animation,
not enough to judge easing or smoothness.

The emulator ran the whole pass on one boot: no gfxstream crash and no clock drift. Logcat shows no FATAL or app
exception, and the app process (pid 3134) lived through the whole pass. The only errors were the fake panel's 10 s clip
loop (`UnexpectedDiscontinuityException` audio timestamps) and emulator `ACodec` decoder errors, both known and unrelated.

## Results

| # | Check | Result | Screenshot(s) |
|---|---|---|---|
| 1a | Plain play (rows, clock column "1ST" / "1:00", 333 wide) | pass | `S/16c.png`, `S/34-play-even.png` |
| 1b | Power play opening: SOG opens, bar drops, stripes and text arrive in that order, PP stripe widens | pass (order seen at 8 fps; easing not judgeable) | `S/run1-pp-open.png`, `S/42-pp-topright.png` |
| 1c | 5-on-3 ("EDM 5-ON-3 0:48") | pass | `S/37-5on3-sog-missing-both.png` |
| 1d | PP goal: "CGY POWER PLAY GOAL" in the bar during the takeover, then the bar closes | pass | `S/22-ppgoal-takeover.png`, `S/p2/sheet1.png` |
| 1e | Even-strength goal: wipe, GOAL + badge + tally, glow, then score pop and row flash | pass. The glow is visible: a pixel beside the box goes from (0,251,252) to (57,180,189) at the peak and back by 3.6 s. Streaks and the white flash are too fast for 20 fps capture | `S/20-goal-peak-glow.png`, `S/21-goal-pop-rowflash.png`, `S/goal1/takeover-sheet.png`, `S/goal1/glow-sheet.png` |
| 2a | End of period "1ST" / "END" (here with the EDM PP still on, so the bar stays) | pass | `S/16c.png` |
| 2b | Ordinary intermission: clock column folded, SOG open, "1ST INTERMISSION" | pass | `S/32-ordinary-int.png` |
| 2c | PP carried into intermission: "1ST" / "INT", PP bar with the frozen time | pass | `S/30-pp-into-int.png`, `S/run1-int1.png` |
| 2d | Stale in intermission: "NO UPDATE" in the bar at reduced alpha; the words return | pass | `S/33-stale-int.png` |
| 2e | Stale in play: "No update" in the clock column | **minor defect D1** (fits, but edge to edge) | `S/35-stale-play.png`, `S/38-large-stale-play.png` |
| 2f | Stale in a PP carried into INT (2nd INT 503 window): PP bar closes, SOG stays, "2ND" / "No update" | pass (same D1 crowding) | `S/31-stale-pp-int.png` |
| 2g | Shots missing on one side or both: the SOG column never opens, the PP bar still shows | pass | `S/36-pp-sog-missing-one.png`, `S/37-5on3-sog-missing-both.png` |
| 2h | OT (clock column "OT", 4-on-3 bar) | pass, from the 1 fps recording only; no defect seen | `S/p3/sheetB.png` |
| 2i | Shootout: SOG open (23 / 23, no jump), clock folded, "SHOOTOUT" | pass | `S/39-shootout.png` |
| 2j | Shootout winner takeover, then "FINAL/SO" text swap | pass | `S/40-shootout-winner-takeover.png`, `S/41-final-so.png`, `S/run1-so-final.png` |
| 3a | Corners: place mode at TL / BL / BR; rows stay put in BR between PP (391.5) and even strength (333); the bar opens above in a bottom corner | pass | `S/43-place-topleft-full.png`, `S/44-place-bottomleft-full.png`, `S/47-bottomright-pp.png`, `S/48-bottomright-even.png` |
| 3b | Large (BR): PP, goal takeover, pop | pass | `S/76-large-bottomright-pp.png`, `S/23-large-goal-takeover.png`, `S/24-large-pop.png`, `S/lg-sheet.png` |
| 3c | Delay 30 s: the change shows about 30 s late, then normally | pass | `S/49b-delay-before.png` (old EDM PP 25 s after the change), `S/49c-delay-after.png` (CGY PP 55 s after) |
| 4a | Quick-bar sheet Style row: OK toggles Pill ⇄ Stacked, the sheet stays open, focus stays on the row | pass | `S/15-sheet-style-stacked.png`, `S/81-sheet-style-pill.png` |
| 4b | Row shown with nothing followed (browsed league NHL) | pass | `S/review3.png` (left) |
| 4c | Row hidden when an NFL game is followed | **not checked** (would need a panel restart with `--sports-nfl`; skipped) | |
| 4d | Settings → Sports → Score bug style row, detail text, Pill / Stacked picker | pass | `S/72-sports-settings.png`, `S/74-style-picker.png` |
| 4e | Pill after switching back: PP tab, layout unchanged | pass | `S/82-pill-pp-topright-large-full.png`, `S/81-sheet-style-pill.png` |
| 5 | Still version (system `animator_duration_scale` 0): transitions snap; frame A is the panel at rest with no glow or streaks and "EDM POWER PLAY GOAL" in the bar; frame B shows the tinted scorer row and orange digit; then the plain box | pass | `S/25-still-frameA.png`, `S/still-sheet3.png` |
| 6 | Logos in the rows and in the takeover badge at both sizes | pass for the fake panel's placeholder SVG shields (crisp at both sizes, drop shadow present). **The real NHL SVGs were not checked**: the panel serves its own placeholders, and checking real art means removing the `sports_base` override and following a real game | `S/47-bottomright-pp.png`, `S/23-large-goal-takeover.png` |
| 7 | Look against the mock-up and the painter renders | see below | |

## Defects

**D1 (minor, look): "No update" in the clock column has no side margin.**
At Standard and Large the string runs from the column's left inset line to the box's right edge, with about 0–2 px
clear on each side. The 2-px step-down to 18 px keeps it inside the column, but it reads cramped next to every other
string in the bug.
Steps: follow CGY @ EDM with Style Stacked during play, then `POST /admin/config {"sports_faults":{"status":503}}` and
wait about 70 s. See `S/35-stale-play.png` (Standard) and `S/38-large-stale-play.png` (Large). The same happens in a
PP carried into intermission (`S/31-stale-pp-int.png`).
Options: a shorter word ("Stale", or "—" under the period), or a minimum side padding in the fit rule. The fit test
should assert that padding.

## Observations (not defects)

- **Over bright video**, here the test card's cyan and magenta: the navy rows, the black SOG and clock columns, the
  hairline under the bar and the nine-slice shadow all read cleanly. The shadow is visible as a soft dark halo on cyan.
  The goal glow tints the video around the box and fades back fully.
- **The takeover panel uses the scorer's primary colour**: CGY is a red panel, EDM a navy panel with an orange sweep. This
  is owner question Q2 (the mock-up shows EDM orange). Nothing changed here.
- **Right-corner float (Q7).** In bottom right at even strength the box ends about 90 px short of the PP width (`S/48`
  against `S/47`). It looks intentional but is noticeable when the bar comes and goes.
- **Large (Q8)** at 490 × 176 is large but legible over the card. Judge it over a real broadcast.
- **Sheet over a right corner.** With the bug in bottom right at Large, the Pill stays visible below the sheet (it does not
  intersect the sheet box), so the toggle back to Pill showed at once. Consistent with §1.1.
- **Motion fidelity.** At about 20 fps capture the transition order matched §7.1 (SOG → bar → stripes → text; out in reverse)
  and the goal sequence matched §7.2's keyframes at the sampled instants. Easing curves, the 3 × 400 ms flash and the
  streak speed need a real device or a 60 fps capture.

## Settings changed and restored

| Setting | Before | During | After |
|---|---|---|---|
| Score bug style | Pill | Stacked | Pill |
| Score bug corner | Top right | TL / BL / BR (place mode), BR | Top right |
| Score bug size | Standard | Large | Standard |
| Score bug delay | Off | 30 s | Off |
| System `animator_duration_scale` | unset (null) | 0 | unset (deleted) |
| Followed game | none | CGY @ EDM | none (Stop following) |
| `files/debug/sports_base` | `http://10.0.2.2:8080` | unchanged | unchanged |
| Show advanced settings | on | toggled off by a stray key, at once back on | on |
| News One favourite | not a favourite | added by a stray OK in the quick bar, removed at once | not a favourite |

Performance (Interface) and the app's Reduce animations setting were not touched. The app was never uninstalled or
cleared, and no instrumented test ran. The fake panel was stopped and the emulator shut down with `adb emu kill`.
