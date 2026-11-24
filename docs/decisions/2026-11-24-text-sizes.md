# Text sizes: the TV type scale's floor, and a "Text size" setting (2026-11-24)

Owner request: follow the Android TV text-size guidance (the tv-material type scale) everywhere in the app, and give
each user an opt-in to make text smaller or larger.

## Units

The app keeps its design-px canvas (1 design px = screen width ÷ 1920; `BoxDesignScale`). On a 1080p TV at xhdpi,
2 design px are 1 sp, so the type scale's smallest sizes are:

| Token | sp | design px | Used for |
|---|---|---|---|
| labelSmall | 11 | 22 | labels, captions, badges, times, counts |
| bodySmall | 12 | 24 | descriptions and hints (sentences) |

## What changed

- **Floor 22 px.** Every Compose text style under 22 px was raised to 22 (weather labels, hours, dates and credits; the
  Sports page's credit, week and "updated" lines; the player's glyphs, picker notes and error detail; update, reminder and
  editor notes; the guide's NEW, LIVE and ON NOW tags; the Settings badge). The guide grid's NEW corner tag went from 18
  to 22 (tag height 26 → 30). The number-entry overlay's name and programme lines went from 19 and 15 to 24 and 22, and
  the box grows with them. The scrub bar's canvas labels went from 15 and 18 to 22 and 24 (bubble height 26 → 31).
- **Descriptions 24 px.** Settings row descriptions and option details, the channel editor's detail text, the weather
  city hint, the phone-setup hints, the welcome note and "Forgot PIN?" went from 22 to 24.
- **"Text size" row** (Settings › Interface): Smaller 0.9, Default 1.0, Larger 1.1, Largest 1.2. Stored as
  `GuidePrefs.textSize`; the process's value is `UiTextScale.running`.

## How the setting is applied

- Every Compose text style is built through `textSp(designPx)` (`GuideUi.sans/mono`, the `BoxTextStyles` tokens, the few
  hand-built styles), which multiplies by the factor. It is **not** the density's `fontScale`: above 1.03 Compose applies
  Android's non-linear font scaling, which leaves sizes this large almost unchanged (seen on the emulator: 1.2 gave
  about 1.03). `fontScale` stays pinned to 1, so the system font-size setting still does nothing.
- The guide grid multiplies in `GuidePaints` (measurer and canvas).
- **Takes effect at the next start** (`EffectTiming.RESTART`, with a "Restart now" row while the stored size differs
  from the running one). The guide's measured widths are cached per process in three places (pager, renderer, canvas),
  so a live change would need all of them invalidated together. The settings store loads on IO after the first frame may
  be drawn, so the size is mirrored into a one-key preferences file (`TextSizeBoot`) that the application reads before
  `super.onCreate()`, i.e. before Hilt builds the guide's text measurer.

## Layout changes for the larger sizes

Checked on the 1080p TV emulator at Largest: guide, Settings, Weather (forecast, cities, radar), Sports, search, the
info bar, the programme sheet and the channel menu.

- Settings title stands on the breadcrumb's top and grows upwards.
- Guide ruler: a context label ("THIS AFTERNOON") that reaches past a dropped time label also pushes the next one.
- Weather tiles and the hourly strip keep their heights; at Larger and Largest they give up padding and icon size.
  Fixed-width weather buttons, the radar's time column, the programme sheet and the stats overlay's lines grow with the
  factor. The precipitation tile's label is "Rain / snow" (the old word did not fit at 22 px with tracking).
- The info bar keeps its 220 px; at Larger and Largest its vertical padding is 12 instead of 24.
- The Sports credit row grows with its text.

## Not done

- **Score bugs** (Pill, Stacked, NFL) are unchanged and do not follow the setting. They are broadcast-style graphics with
  their own Size row and owner-ruled fit floors (18 px, 16 for four-letter codes); their period, power-play and tally
  text is 20 px and smaller in places.
- The scrub bar's canvas text has the 22 px floor but does not follow the setting.
- The system font-size setting is still ignored (fixed 1920 × 1080 canvas).
- Lines longer than the guidance's 50 to 70 characters (Settings rows span a 1,340 px pane) are unchanged.
- Not looked at on a device at a larger size: the quick bar, the channel list, pickers, the stats overlay, the channel
  editor, the PIN pad, setup and backup pages. At Largest some weather tile sub-lines end in an ellipsis.
