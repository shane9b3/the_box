# The guide opens at the current half hour (2026-11-18)

Owner ruling: the guide's first column is the half hour now in progress, not the one before it.

## Before

A window placed for now started at `floor30(now) − 30` (the prototype's rule, m3-guide §3.3.2): at 20:40 the grid ran
from 20:00, so the first column showed the half hour already over.

## Now

- A window placed in the current half hour starts at `floor30(now)`: at 20:40 the grid runs from 20:30. This covers
  opening the guide, going home, a row jump (number entry) and a day jump that lands in the current half hour.
- A programme that began before the window is clipped at the left edge, as any cell cut by the window already is.
- Unchanged: paging while browsing left or right, and a jump to another time, keep the 30 minute lead
  (`GridConstants.LEAD_MIN`).

## Code

`GridConstants.NOW_LEAD_MIN = 0`; `GuideNavigatorImpl.placedLead` picks it when the anchor is in the current half
hour; `GuideLimits.nowWindow` follows it.

# NEW corner tag on programme blocks (2026-11-18)

Owner ruling: new episodes get the app's own mark, a corner tag, instead of the provider's "New" written in the title.

- **Tag**: an accent tab (`GuideColors.ACCENT`) in the block's top-right corner, "NEW" in the canvas colour
  (`GuideTextStyle.NEW_TAG`, 18 px/700), 26 px high. Same on a focused block; half strength on a past block. It sits
  above the title line, so it takes no room from the title. A block too narrow to leave 24 px beside it has no tag.
- **Source**: `ProgrammeFlags.NEW`, set by XMLTV `<new/>` (already parsed) or by `NewMarker`, which takes a "new"
  marker out of the title at import: `ᴺᵉʷ` anywhere; `(New)`, `[New]`, `{New}`, `*New*` at either end; a prefix
  `New:` / `New -` / `New |`; a suffix `- New` / `| New`; a last word `NEW` in capitals after a mixed-case title.
  Real titles ("New Amsterdam", "Brand New Day") are left alone.
- `EpgSchema.IMPORTER_VERSION` is 2, so every guide is rebuilt once from its cached file after the update.
- Not done: no NEW mark in the focused-programme panel, the full-screen info bar or search results (titles there are
  clean because the marker is removed at import). A marker in the middle of a title is not recognised.
- Device finding: Android's ICU regex rejects an unescaped `]` or `}` that the desktop JVM accepts; the unit tests
  cannot catch that.
- (Later the same day, owner request) The focused-programme panel shows the same tag beside the time line
  ("10:00 – 11:00 · 1 h  NEW"): `PanelState.isNew`, drawn in `FocusedPanel`.
- (Second ruling, same day) In the grid the tag no longer uses the accent colour: a page full of accent tags was
  distracting. It is drawn in the grid's own greys (`GuideColors.NEW_TAG_*`: white .12 fill with .7 text, .06/.45 on a
  past block, canvas .12/.75 on the focused block). The accent tag stays in the focused-programme panel.

# LIVE tag (2026-11-19)

Owner ruling: live broadcasts get the same tag as new episodes.

- **Source**: `ProgrammeFlags.LIVE`, set by XMLTV `<live/>`, a "Live" category (both already parsed), or `LiveMarker`,
  which takes a "live" marker out of the title at import: the superscript `ᴸᶦᵛᵉ` (also `ᴸᴵⱽᴱ`, `ˡⁱᵛᵉ`) anywhere, and
  `(Live)`, `[Live]`, `{Live}`, `*Live*` at either end. "Live" is common in real titles, so no bare prefix, suffix or
  last word is taken ("Live: PGA Tour", "Saturday Night Live", "NHL Hockey LIVE" stay whole).
- **Grid**: the same grey corner tab as NEW (`drawCornerTag`). A programme that is both shows LIVE left of NEW, 4 px
  apart; if there is no room for the second tag it is left out.
- **Focused-programme panel**: a LIVE pill in the live colour (`DesignTokens.Colors.Live`) after the time line, after
  the NEW pill when both (`PanelState.isLive`).
- `EpgSchema.IMPORTER_VERSION` is 3, so every guide is rebuilt once from its cached file after the update.
- Emulator check on the owner's provider: 6,251 programmes flagged live after the rebuild, no raised letters left in
  any title.
