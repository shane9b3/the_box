# Player keys and the quick bar (owner decision, 2026-10-15)

Owner ruling given during the M4 design phase, after the design review raised the zap-direction question (`docs/review/m4-design-review.md` E3). It overrides product review S8's "↑ / ↓ zap" default and the design's §7 item 3.

## Guide (unchanged from M3)

- D-pad Up moves focus to the row above, which is the **lower** channel number. Down moves to the higher number.
- CH+ pages six rows towards the lower numbers; CH− towards the higher numbers. Same direction as the D-pad, because in the guide both keys move through a list.

## Full-screen player

- **CH+ = next higher channel number (+1), CH− = next lower (−1).** Also MEDIA_NEXT / MEDIA_PREVIOUS and PAGE_UP / PAGE_DOWN, as the review's S6 requires.
- **D-pad Up opens the quick bar**, not a zap. The quick bar is a horizontal row at the bottom of the screen on a translucent background (no blur, per the plan), with the player shortcuts the user needs while watching: audio track, aspect ratio, subtitles / closed captions, then the other common items (favourite, start over / jump to live, previous channel, stream info, video decoder, screen off), and a **Settings** item that opens the full settings screen (`ScreenMode.SETTINGS`; the M3 placeholder until M5 builds the tree, the same route the guide's gear already uses).
- **D-pad Down opens the active info bar** (lead's choice, so the two bottom bars pair: Up for controls, Down for what is on). Product S8's ← channel list, → info bar and OK channel list stay.
- The "Up/Down in player" setting (product §4.4) keeps the alternatives: **Quick bar and info bar (default)** / Change channel / Open channel list. "Invert channel direction" applies only when "Change channel" is chosen, and only to the D-pad; CH± never invert.
- In the scrub bar (catch-up and time-shift) Up stays "jump to live" while the bar is open; Back to bare video then Up reaches the quick bar, so the five-key rule holds in every state without a second route.

## Why

- The owner, a daily TiviMate user, wants the D-pad in full screen for controls, not channel changes, and the dedicated CH± keys for zapping in TV order.
- Remotes without CH± (Chromecast with Google TV, Onn) zap from full screen through the channel list (OK) or the guide, or by switching the setting to "Change channel". Recorded as a known trade-off; the parity session (plan §7) checks how often such users miss D-pad zapping.

## Effect on the M4 design

Folded into `docs/design/m4-player-ux.md` in the review-reconciliation pass of 2026-10-15: §3.1 key tables, §3.4.1 quick menu becomes the quick bar, the `PlayerSettings` enum for Up/Down, the five-key reachability proof, and `player_keys.csv`.
