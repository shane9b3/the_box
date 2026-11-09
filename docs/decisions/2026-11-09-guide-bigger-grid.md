# 2026-11-09 — No footer in the guide; a bigger grid

Status: **implemented on main (not committed); lead decisions, owner request.**

## The request

Owner, 2026-11-09, verbatim: "could we remove the remote helper text on the bottom of the guide page and scale the
guide grid to fill the space making all the text (including the time bar and channel group text) larger and easier to
read"

## Rulings (lead)

1. **The guide's footer hint row is removed.** `GuideScreen` draws no footer. Its supporting logic goes with it: the
   state machine's `footer()` / `groupsKeyName()` / `keyHintsTail()` / `refreshFooter()` and the footer key in
   `refreshLeftEdge()`; `GuideUiState.footer`; `GuideEvent.KeysSeen` and `GuideEvent.KeyHints` (and the view model's
   `keyHints()` / `HINTED` wiring and its `KeyCapabilities` / `KeyBindingStore` dependencies); the footer texts in
   `GuideTexts`; `FooterKeyNames`; `DefaultKeyMap.seenWithColourFamily` (ruling 4 of 2026-10-19 existed only for the
   footer); `GuideGeometry.FOOTER_L/FOOTER_B`. The cues drawn in the grid itself stay: `EDGE_CHEVRON`, `CLIP_CHEVRON`
   and the label-column ↺ (2026-10-19 ruling 7), and the `GuideNavigator.leftEdge` probe that drives them. The shared
   `FooterHints` composable (measure-and-drop layout) stays: Sports and the channel editor use it. This supersedes
   ruling 8 of `2026-10-19-guide-left-and-groups.md` and m3-guide §3.18.3.
2. **The grid panel runs down to the bottom safe margin**: `SAFE_B` = `HOLE_T` = 56, the margin the layout keeps at the
   top, so the panel is (64, 500) – (1856, 1024).
3. **Still 5 rows; everything in the grid ≈ 1.2× larger**, sized by fitting the ruler and 5 rows into the new height
   (1 + 57 + 5 × 93 + 1 = 524, so no sliver of a sixth row shows) and rounded to whole design px. Every number lives in `GuideGeometry`,
   `GuideTextStyle` or `GridConstants`; the renderer's private size constants (dots, glyphs, gaps, progress insets,
   context gap) moved into `GuideGeometry`, and the Compose chips, day strip, Groups panel and empty state now read it
   instead of literal dp.

## Before / after (design px at 1920 × 1080)

| | Before (2026-10-17 … 11-08) | After (2026-11-09) |
|---|---|---|
| Grid panel | (64, 500) – (1856, 960), 460 high | (64, 500) – (1856, **1024**), **524** high |
| Footer row | hints at y ≈ 1025 (64, bottom 40) | **none** |
| Ruler row | 48 | **57** |
| Rows | 74, gap 8, pitch 82, 5 rows (549 … 951) | **85**, gap 8, pitch **93**, 5 rows (558 … 1015) |
| Label column | 240 (x 81 – 321), pad 12, gap 14 | **280** (x 81 – 361), pad **14**, gap **17** |
| Timeline | 1518 px / 180 min = 8.433 px/min (tick 253) | **1478** px / 180 min = **8.211** px/min (tick 246.3); 3 h still visible |
| Cell title | 30/600 | **36**/600 |
| Cell time (unused since 10-17) / no-info | 20 mono / 22 | **24** / **26** |
| Ruler times / TONIGHT | 20 mono / 18/600 | **24** / **22**/600 |
| Channel number / name | 20 mono / 26/600 | **24** / **31**/600 |
| Logo slot | 130 × 64 | **150 × 74** (same share of the row) |
| Group chip | (93, 507), ≤ 222 × 36, 24/600, ▾ 20 | (**95**, 507), ≤ **252 × 45**, **29**/600, ▾ **22** |
| Now / Day chips | 36 high, 22/600, ▾ 18, end x 1827 | **45** high, **26**/600, ▾ **22**, end x **1825** |
| Day strip | (281, 501) – (1839, 549) (stale prototype x) | (**361**, 501) – (1839, **558**), pills 45 / 26 px |
| Groups panel | (64, 500) – (424, 960) | (64, 500) – (424, **1024**) |
| Label ↺ / cell ↺ | 20 / 12 (gap 6) | **24** / **14** (gap **7**) |
| Reminder dot / tuned dot | 8 (gap 6) / 7 | **10** (gap **7**) / **8**; label icon gap 8 → **10** |
| Now line | overhang 6, dot 10 | overhang **7**, dot **12** |
| Cell padding | 16 (8 below 150), no text below 30 inner | **19** (**10** below **180**), no text below **36** |
| Progress bar | 3, inset 4 | **4**, inset **5** |
| Ruler tick label pad / context gap | 10 / 12 | **12** / **14** |
| Edge / clip chevrons | 12 × 24 | unchanged (the 16 px gutter fixes the edge cue's width) |

Smallest type in the grid is now 22 px (the TONIGHT label), above the 18 px floor.

## Trade-offs

- **Fewer characters per cell.** A 30-minute cell is 246 px wide instead of 253 and the title is 36 px instead of 30,
  so it shows about 11 characters instead of 14 before the ellipsis. The window stays 180 minutes; narrowing it to
  keep characters was rejected because the owner asked for larger type, not a shorter window.
- **The label column takes 40 px from the timeline** so 31 px names keep the characters the 26 px names had.
- **The clip chevron no longer pushes the title**: with the 19 px pad the title already starts 3 px after the chevron
  (the rule moves it only below 2 px), so the push now applies only at other scales.
- **Ruler context label vs. the first tick label.** At 22 px, "THIS MORNING" plus the pushed first tick label ran
  into the next tick's label (seen on the emulator at 04:00: "THIS MORNING 3:30" overprinting "4:00"). The renderer now
  drops the pushed first label when it would reach the next tick's label, so every label drawn sits on its own rule
  ("THIS MORNING · 4:00 · 4:30 …"). Shorter labels (TONIGHT) still keep it. Test:
  `GuideRendererTest.longContextLabelDropsThePushedTickLabelInsteadOfOverlapping`.
- **Logo cache**: the slot is 150 × 74, so the 4 MB byte cap holds about 94 full logos at 1080p (it held about 126);
  the 160-entry cap was already bound by bytes.
- **Exit hint** (`Back` once more): still at (64, bottom 96); it now overlaps the grid's last row instead of the footer
  area. It is transient and opaque.
- **Discoverability of keys**: the footer was the only place that named Hold OK / Hold Back / Red / CH± / 0–9 in the
  guide. The owner chose the space over the hints; the in-grid cues stay.

## Files

Core: `GuideModel.kt` (`GuideGeometry`, `GuideTextStyle`), `GridConstants.kt`, `GuideMetricsImpl.kt` (comment),
`GuideRendererImpl.kt` (sizes from `GuideGeometry`; ruler label drop), `GuideStateMachineImpl.kt`, `GuideStateContracts.kt`, `GuideTexts.kt`, `ActionCatalog.kt`,
`DefaultKeyMap.kt`. UI: `GuideScreen.kt`, `GridHeaderChips.kt`, `DayStrip.kt`, `GroupsPanel.kt`, `EmptyState.kt`,
`ContextMenu.kt` (comment), `GuideAssets.kt`, `GuideViewModel.kt`. Docs: m3-guide §3.1, §3.15, §3.18.3, Appendix A.1;
`docs/01-design-reference.md`.

Tests: `GuideMetricsTest` (new numbers; new `gridReachesTheBottomSafeMargin`, `typeSizesMeetTheBiggerGridTargets`),
`GuideContractsTest`, `GuideRendererTest`, `TextFitTest`, `OverlayBoundsTest.anchoringRule`, `AndroidGuideCanvasTest`,
`LogoCacheTest`, `GuideGridHostTest`, `GoldenFrameTest` (golden `guide-reference-1080p.png` re-recorded: every grid
pixel moves). Deleted: `FooterHintsFromKeyMapTest` and the footer assertions in `GuideBehaviourTest`,
`SliverAtNowTest`, `GuideSettingsAttachTest`, `ShellRedrawTest`, plus `DefaultKeyMapTest.colourKeysCountAsOneFamily`.
`FooterHintsTest` stays (the shared layout).
