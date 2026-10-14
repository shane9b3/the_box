# The Box — Guide Screen Design Reference

Source: `design/TV Guide.dc.html` (Claude Design export, 2026-10-13). Thumbnail: `design/thumbnail.webp`.
Logo: `design/logo/The_Box.png` (owner-supplied wordmark, 2026-10-14); derived assets are specified in `docs/decisions/2026-10-14-settings-and-branding.md`.
This document translates the HTML prototype into measurements and tokens for the Android TV implementation.
Canvas is 1920×1080; the prototype scales uniformly to fit, so all values below are **design px on a 1920-wide canvas**.
**Units rule (corrected 2026-10-13 after architecture review):** Android TV renders a 1080p UI at xhdpi, so the screen is only 960×540 dp. Design px are therefore *not* dp. Implement with a root `CompositionLocalProvider(LocalDensity provides Density(screenWidthPx / 1920f * ...))` style override (or a `designPx` helper: `dp = px * screenWidthDp / 1920`) so 1 design px = screen width ÷ 1920. Text sizes follow the same scale. Implementation (`ui/.../theme/BoxDesignScale.kt`, M0 review): the scale is `min(windowWidth / 1920, windowHeight / 1080)` with `fontScale` pinned to 1, so `1.dp == 1.sp == 1 design px`; on 16:9 TVs this equals width ÷ 1920, and on other aspect ratios the 1920×1080 canvas fits inside the window instead of being clipped. Popups and dialogs reset density to system dp, so **wrap every `Popup`/`Dialog` content in `BoxDesignScale`** (it reuses the root scale rather than re-measuring the popup window). tv-material components carry their own dp sizes and will render at half size under this density; give them explicit design-px sizes or use our own components. Derived grid geometry: 1656 px of grid width minus the 200 px channel column = 1456 px for 180 min ≈ 8.09 px/min at the inner grid, and a 68 px row pitch (60 + 8 gap) gives about 6 rows in the 460 px panel.

## Layout (1920×1080)

| Region | Position / size | Notes |
|---|---|---|
| Ambient background | full screen | Base `#0A0D13`; two blurred radial blobs coloured from the *tuned channel's* hue (`oklch(0.45 0.12 h)` top-left 1300×1000 at (-200,-260) blur 140, opacity .55; `oklch(0.40 0.10 h+40)` right 1100×900 at (right -300, top 200) blur 160, opacity .35), 1.2 s colour transition; vertical gradient overlay from transparent → `rgba(10,13,19,.6)` at 45 % → `.92` at 100 %. In the app: derive hue from channel logo dominant colour (Palette) or video frame, fall back to a hash of the channel id. |
| **Mini player** | left 64, top 56, **720×405** (16:9), radius 20 | Shadow `0 30px 80px rgba(0,0,0,.55)` + 1 px white ring `.14`. Overlays: top-left pill group (LIVE pill with pulsing red dot `#FF4D5E`, `CH {num}` mono pill), top-right faint caption, bottom gradient panel with programme title (30 px/600), channel name (18 px/.7 alpha), then a mono time row `start — progress bar (4 px, white on 20 % white) — end`. |
| Top-right header | left 832, top 56, right 64, height 405 | Row 1: tab pills **Guide / Recordings / Search** (18 px; active = white 12 % bg + 16 % border, radius 999) left; date (18 px, .65 alpha) + clock (34 px/500, tabular nums) right. |
| Focused-programme panel | bottom-aligned inside header region | Meta row: channel number mono chip (16 px, 8 % white bg, 12 % border, radius 8) · channel name · dot · genre · optional **ON NOW** pill (`oklch(0.72 0.14 240 / .22)` bg, `oklch(0.86 0.09 240)` text, 15 px/600, letter-spacing .06em). Title 64 px/600, line-height 1.02, letter-spacing -.025em, balanced wrapping. Time line mono 18 px `.75` alpha: `19:30 – 20:00 · 30 min`. Description 21 px, line-height 1.45, `.78` alpha, max-width 860. Buttons: primary **Watch now / Watching / Watch from start** (white `#EEF1F6` bg, dark text, 19 px/600, padding 14×26, radius 14, play triangle); secondary **Record / Recording set** (10 % white, 16 % border, blur 20, dot turns `#FF4D5E` when set). |
| **Guide grid** | left 64, right 64, top 500, **height 460**, radius 24 | Glass panel: `rgba(255,255,255,.045)` bg, 1 px `.09` border, backdrop blur 30, padding `0 16 12`. |
| Grid header row | height 48 | Left label column 200 wide: section label **TONIGHT** (15 px/600, letter-spacing .1em, .5 alpha). Time ticks every 30 min, each with 1 px left rule `.1` and mono 15 px label `.55` alpha. Visible window = **180 min** (6 ticks). |
| Channel rows | height **60**, gap 8 | Left cell 200 wide, radius 12; number mono 15 px `.5` alpha, name 19 px/600; focused row gets `rgba(255,255,255,.08)` bg; tuned channel shows a 7 px red dot at the right. |
| Programme cells | absolute within row, 3 px horizontal padding, radius 12, padding 0 16 | Title 18 px/600 single-line ellipsis; time mono 13 px `.6` opacity. States: **default** `rgba(255,255,255,.05)` bg, `.08` border; **on-now** `.11` bg + 3 px progress bar at bottom (`.6` white); **past** text `.45` alpha; **focused** solid `#EEF1F6` bg, dark text `#0A0D13`, 3 px accent ring `oklch(0.72 0.14 240)` + `0 12px 30px rgba(0,0,0,.4)` shadow, progress bar turns accent. Recording-scheduled cells prefix `● `. Transition 150 ms on bg/shadow. |
| Now line | 2 px vertical, accent colour, glow `0 0 12px`, 10 px dot at top | Spans from grid header to bottom; x = 200 + (width-200) × (now/180). |
| Footer key hints | left/right 64, bottom 40 | 17 px `.55` alpha; key caps mono 14 px with `.22` border radius 7: `← → ↑ ↓ Browse`, `OK / Enter Watch`, `R Record`. On Android TV map R to a long-press-OK or the dedicated menu/colour button; keep the hint row driven by actual key availability. |

## Design tokens

```
color.bg            #07090D (outer) / #0A0D13 (canvas)
color.fg            #EEF1F6
color.accent        oklch(0.72 0.14 240)   ≈ #5CA8F2 (sRGB fallback)
color.accentText    oklch(0.86 0.09 240)   ≈ #B6D4F7
color.live          #FF4D5E
glass.panel         rgba(255,255,255,.045) + border rgba(255,255,255,.09) + blur 30
font.sans           Instrument Sans 400/500/600/700  (bundle as res/font; fallback Roboto)
font.mono           JetBrains Mono 400/500           (bundle as res/font; fallback Roboto Mono)
radius              pill 999 · panel 24 · mini-player 20 · button 14 · cell/row 12 · chip 8/7
type.title          64/600 lh 1.02 ls -.025em
type.miniTitle      30/600
type.clock          34/500 tabular
type.body           21 lh 1.45
type.cellTitle      18/600
type.cellTime       13 mono
```

## Interaction model captured in the prototype

- Focus is a (row, column) pair over *programmes*, not pixels. ← / → move between programmes on the row; ↑ / ↓ move rows and pick the programme that overlaps the **start of the currently focused programme + 1 min** (anchor snapping) — falls back to last programme on that row.
- OK / Enter **tunes** the focused row's channel (mini player switches channel, ambient colours animate to the new channel hue, mini player fades in with a short brightness flash).
- Primary button label logic: `Watching` when focused row is tuned and programme is on now; `Watch now` when on now; `Watch from start` for a programme in the past (catch-up) — note the prototype shows past programmes as still focusable, which is exactly the archive/catch-up affordance we need; future programmes should instead offer **Remind** / **Record**.
- R toggles a record mark on the focused programme (cell gets a `●` prefix, button becomes "Recording set" with red dot).
- Double-click on a cell tunes; single click focuses (touch/mouse fallbacks only — D-pad is primary).
- Time window fixed at 180 min starting at the top of the hour before "now" in the prototype. The real app needs horizontal paging/scrolling of time (see architecture doc), a "jump to now" action, and day navigation, while preserving this visual density (30-min tick ≈ 1/6 of grid width).

## Things the prototype does not show (must be designed in the same language)

- Group/category selector (left rail or a pill row under the tabs) and favourites filter.
- Channel logos in the left column (prototype uses number + name only; logos are optional, user-toggleable).
- Past/archive region shading and a "catch-up available" indicator per channel (e.g. a small ↺ glyph next to the channel name, past cells slightly more opaque when archive exists vs dimmed when not).
- Full-screen player overlays: info bar, channel list side panel, audio/subtitle picker, number-entry overlay, time-shift scrub bar.
- Setup / onboarding (playlist or Xtream login), settings, parental PIN, error and empty states, loading skeletons for EPG.
- Recordings and Search tabs.
