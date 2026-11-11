# Stacked score bug: code review U (drawing, fidelity, performance)

Reviewer U, 2026-11-10, against `main` at `af08859` (commits `bd52493`, `72aa3b7`, `c23fcf4`, `b4dc26a`). Read-only. I did
not run Gradle or the emulator.

Scope: `StackedPainter.kt`, `StackedGoalPainter.kt`, `NineSlice.kt`, `StackedContracts.kt`, the drawing side of
`StackedBug.kt`, `StackedTimeline.kt`, `GoalSpec.STACKED`, the on-screen colours of `NhlStackedColours`, the
`ScoreBugPlacement` footprint, the fonts, and the six named tests. Core state mapping is reviewer C's scope.

**Method.**
- Every mock value is taken from `design/nhl-stacked/Stacked.dc.html` (inline styles and the `<style>` block) × 0.45.
- Text widths are measured from the bundled TTFs (`instrument_sans_bold*.ttf`, `barlow_condensed_black_italic.ttf`)
  with a small `hmtx` / `glyf` / `post` reader in the scratchpad. No kerning is applied, which is within about 1 px for
  these strings.
- The team colours come from a Python port of `NhlStackedColours` / `NhlTeamColours.derive`. It reproduces the
  design's §5.1 numbers exactly (EDM A 3.53, B 2.16; TOR B `#66799D` 3.83).
- I looked at the contact sheets one by one (`stacked-contact-sheet-{std,large}-{dark,ice}.png` and
  `stacked-goal-contact-sheet-{std-dark,large-ice}.png`).

**Verdict.**
- The drawing is faithful to the mock almost everywhere.
- The engineering rules hold, checked in the code.
- The performance budget is comfortable on a Fire TV Stick.
- There is one blocker: the clock is not tabular, so a 10:00–20:00 clock (half of every period) fills the clock column
  edge to edge.
- Four should-fixes follow.

---

## Blockers

### B1. The clock uses proportional widest-digit cells, not `tnum`, so `mm:ss` fills the 90-px column with about 0.5 px to spare
`StackedPainter.kt:115` builds `clockPaint` without `tnum`. Lines 167–185 then build a "tabular" cell from the widest
**proportional** digit. In Instrument Sans Bold, "0" is 0.682 em, much wider than the rest; "1" is 0.385 em.

| at 28.8 px (Standard) | width | free per side in 90 |
|---|---|---|
| drawn today: 4 × 19.64 ("0") + ":" 8.21 | **86.8** | 1.6, which the 1-px inset line and the 1-px border eat |
| with `tnum` (`zero.tf` … are 0.600 em = 17.28) + ":" 8.21 | **77.3** | **6.4** |
| the mock (Inter tabular 64 px in 200, × .45) | ≈ 79 | ≈ 5.2 |

**Evidence.**
- Every `10:12` cell in the contact sheets touches the right border:
  - `std-dark` row 1, column 3;
  - `large-ice` rows 1 and 5, column 3, where the "2" sits on the border.
- The fit loop (line 174) uses `columnBudget()` = 90 − 2 × `COLUMN_BREATHING` (1), so it never steps the size down.
- `StackedFitTest.theClockColumnStringsFitAtEighteenOrMore` passes because the budget is 88.

The mock says `font-variant-numeric: tabular-nums`. Design §4.2 says "Clock, 64 / 700 tabular". The scores, the SOG
digits and the bar time already set `'tnum'`; the clock is the only tabular text that does not.

**Fix.**
1. Give `clockPaint` `fontFeatureSettings = "'tnum'"` and measure `digitAdvance` / `digitCell` with it. All ten
   advances become 17.28, so the per-digit centring becomes a no-op. Keep it in case the font changes.
2. Raise the clock's budget to the mock's air: `COLUMN_BREATHING` ≥ 5 design px. 77.3 ≤ 80, so the clock stays at 28.8.
3. Add `StackedFitTest` assertions on the drawn width: `clockWidth(19*60+58) ≤ clockW − 2 × 5 × s` at both sizes.

---

## Should-fix

### S1. Clock-column words get 1 px of breathing. "Delayed" is fitted to 22.8 px with 0.6 px a side; "No update" sits at 18 px with 0.1 px.
`StackedPainter.kt:198`, `:206–214`, `:666`.
- `fitColumn` starts each slot word at 28.8 and stops at the first size whose ink is ≤ 88.
  - "Delayed": 22.8 px, ink 86.8.
  - "No update": 18 px (the floor), ink 87.8.
- Both touch the inset line and the border (sheet row 3, columns 1–2, both sizes).
- They are also the only mixed-case words in an all-caps column ("2ND", "END", "INT").

The known issue is wider than "No update": the 1-px breathing is shared by every slot word.

**Proposed fix**, within the §8 states table (the slot stays the slot, the words stay `sports_no_update` /
`sports_delayed`):
1. Set `COLUMN_BREATHING` to 6 design px (budget 78), the same constant as B1. Then:
   - "Delayed" steps 28.8 → 18.8 (ink 71.5, 3.3 a side past the lines);
   - "END" and "INT" are unaffected (58 and 45 ink at 28.8).
2. **Two lines for a two-word slot string.** "No update" wraps at its space into "No" / "update", both at one fitted
   size, centred as a block on `clockCy`.
   - "update" fits at 20.8 (ink 69.9).
   - Two lines at 20.8 need about 51 of the 56.6-px lower slot. 18.8 leaves more room.
   - This keeps the 18-px floor with real clearance. No new string is needed (split at the space; break at the first
     space only).
3. Do not use a shorter word. "No update" is the app-wide term for a stale feed (the Pill and the bar say it), and the
   error rule wants the reason spelled out.

Upper-casing the slot words to match the column would be nicer, but "DELAYED" is 85 px of ink at 18 and only fits on
two lines. I would leave mixed case and the owner screenshot decides.

### S2. In a right-to-left locale the drawn box is right-aligned inside its footprint: the rows move and the hint can overlap
`StackedBug.kt:464–496`.
- The layer is `Box` with the default `TopStart` alignment, and its two `Spacer`s use `Modifier.offset(m.dp, …)`. Both
  are layout-direction aware.
- The manifest sets `android:supportsRtl="true"` and nothing pins `LocalLayoutDirection`.
- Under an RTL system locale the spacer is placed at `layerW − tagW − m`, so the drawn box sits `footW − tagW` (59 px at
  play, 90.5 at a break) right of where the painter and `StackedPlaceHint` (which uses plain `place`) expect it.
- Effects:
  - every SOG / clock open or fold moves the rows, breaking §4.3;
  - in the left corners the place hint (`bx + tagW + gap`) lands on top of the box;
  - `SCORE_BUG_TAG` bounds move with it.
- The Pill uses the same pattern but is not affected, because its spacer is as wide as the layer minus 2 M.

**Fix:** `absoluteOffset` on both spacers plus `contentAlignment = Alignment.TopStart` under
`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`, or just the latter around the whole
`StackedBug` body. Add a `StackedBoundsTest` case under RTL.

### S3. The screenshot tests cannot catch a visual regression
`StackedScreenshotTest` renders the contact sheets and asserts only:
- three contrast checks;
- the shadow profile;
- the empty layer edge;
- round corners;
- "the bar is visible".

A moved code, a resized clock, a lost stripe or a changed bar offset all pass.

**Cheap pin.** Robolectric NATIVE rendering is deterministic for a fixed Robolectric and JDK:
1. **Geometry probes, no images.** Assert the ink bounding boxes found by scanning the rendered bitmap against the
   §4.2 table, ±1 device px at 1080p. Cover:
   - the code's left ink edge (77.4 / 80.1 with the PP stripe);
   - the score's right ink edge (227.7);
   - the SOG digit and label centres (x 272.25; y 27 / 54 / 81);
   - the period baseline (38.7) and the rule (y 50.4, x 254.7–321.3);
   - the clock's ink extent (B1);
   - the bar stripes' x at rest (20.7 / 37.8) and the text's x (63).

   There are a dozen numbers per size, so no binary files.
2. **Six low-resolution goldens** (Standard dark: play, PP, break with "NO UPDATE", PP-in-INT, takeover at 1 800, frame
   B), downscaled 4× to grayscale PNGs of about 2 KB each under `ui/src/test/resources/stacked-golden/`.
   - Compare with a per-pixel tolerance of ±12 and at most 0.5 % of pixels over it.
   - Add a `-Pstacked.record` switch to re-record.

   That catches colour or layout drift without font-hinting flakiness.

The probes (option 1) are the higher-value one. Option 2 is optional.

### S4. The contact sheets show states the timeline never produces, so eye review is misleading
The sheets are built by hand from `StackedFixtures.frame(...)`, which sets channels independently:
- the "mid-fold" cell (`std-dark` row 4, column 3) has the SOG at about 50 % width with its contents at full alpha,
  clipped to "1(" / "SO";
- the dimmed cell (row 5, column 1) shows a dimmed team PP bar, which §8 never produces (a stale PP bar closes; only
  neutral bars dim).

On the real timeline:
- the SOG contents are at α 0 before the width starts folding (close: α out over 0–200, width 350–800);
- on open they are < .2 while the width is under 85 %;
- only the clock column (open: width 350–800, contents 500–800) shows clipped half-alpha text, which matches the CSS.

**Fix:** build the transition cells from `StackedTimeline.sample(track, kind, t, …)` at named instants (for example,
SOG close at 400 ms and clock open at 600 ms), and the dimmed cell from a NO UPDATE neutral bar.

A side note on that cell: white text at .55 on a team bar would be 2.3 : 1 on EDM's `#D33F00`. It never happens, but
the test should not suggest it does.

---

## Later (nits)

**Drawing**
- **N1.** The inset line of the clock column ignores `columnDim`: `StackedPainter.kt:537` uses `f.clockAlpha` and the
  text uses `alpha`. Harmless; make it consistent with the SOG column (line 521) or comment it.
- **N2.** `SOG_LABEL = "SOG"` is hard-coded (`StackedPainter.kt:655`), although commit 0 added `sports_stacked_sog`.
  Resolve it once in the constructor or in `frame()`.
- **N3.** `drawBarWords` calls `barPaint.measureText` on every frame (`StackedPainter.kt:419`). Minikin's layout cache
  makes it cheap, but `fillBar` could store the label's advance in `BarContent`, since the painter fills it anyway.
- **N4.** The score pop is visible (× 1.5, pop colour) from 3 168 ms, uncovered by the lead's exit from 3 384
  (`GoalTimeline.kt:396–398`, the GA "pop reveal" rule). The mock shows the plain white score during the exit and pops
  at 3 600. GA sanctioned this for CLASSIC; §7.2's "Score pop … 3 600" table for STACKED does not mention it. Record it
  or leave it.
- **N5.** Row-stripe widths go through `clipRect`, which HWUI scissors to whole pixels (GL pipeline, API 25–27).
  - 3.6 px draws as 4, and the 3.6 → 6.3 widen steps in whole pixels.
  - At 720p (s .667) it is 2.4 → 2 or 3 px.
  - Invisible at 1080p, and the corner trick needs the clip. Noted only.
- **N6.** `glowPaint` has no `FILTER_BITMAP_FLAG`, and the mask origins are fractional at 720p (`lowMargin` 21.3).
  Nearest sampling shifts the glow by up to 0.5 px. Invisible; add the flag for parity with `shadowPaint`.
- **N7.** `LogoDisc` text (the place sample's "AWY" / "HME" at 18 px in the 44 disc) touches the disc's edge. This is
  shared with the Pill; noted only.

**Performance**
- **N8.** The nine-slice's side and top / bottom slices reach 25–30 px inside the box under its opaque fill, roughly
  55 % of the box's area blended twice. At about 20 k px per frame this is negligible on a Fire TV Stick. "N7: centre
  skipped" is true only for the centre slice. Clipping the slices at the box edge (the corners excepted) would save it
  if needed; not worth it now.
- **N9.** The glow LRU (2 entries) and the prewarm on every layout rest mean a normal game re-bakes the pair on most
  PP ↔ break changes. Each bake is two σ ≈ 10 / 22 blurs on about 250 KB, off-thread, plus GC churn. There are up to 5
  distinct rest boxes per size (333 × 108, 391.5 × 141, 301.5 × 141, 391.5 × 108, 333 × 141). A cache of 3–4 entries
  (about 1 MB at 1080p) would stop most re-bakes. Low priority.
- **N10.** `begin()` makes the badge's padded copy and `extractAlpha` on the main thread at takeover start
  (`StackedGoalPainter.kt:231–235`). Both are small (70² px), but they upload on the first frame the badge shows
  (450 ms). Call `prepareToDraw()` on both.
- **N11.** `StackedPainter` is built in composition (`StackedBug.kt:186–188`), and that includes the first load of the
  new `instrument_sans_bold_italic.ttf` (88 KB) on the main thread the first time Stacked shows. Load it next to the
  shadow bake, or pre-load it in `SportsUiModule`.

**Tests**
- **N12.** `StackedPainterAllocationTest` does not cover:
  - the runner's per-frame path (`fillArgs` → `run.sampleForDraw()`, `BodyMerge.of`, the `GoalSampler`);
  - the `LogoDisc` path (images are always present);
  - `placing = true`.

  Drive `StackedProbe.draw` for 300 frames in `StackedDrawTest`. On the brief's question: the minimum of two passes
  does **not** hide a per-frame allocation. Each pass is 300 frames against a 4 KB limit, so anything over about 14 B
  per frame fails both passes; the minimum only drops one-off warm-up allocations.
- **N13.** Missing cases:
  - a 720p (`s` = 0.667) and a 4K (`s` = 2) render of the play and PP cells, for hairline, stripe and text sanity;
  - an RTL bounds case (S2);
  - a takeover at 301.5 with the widest real tally ("WSH 10" / "CBJ 10"), checking that the tally is dropped rather
    than overlapping GOAL;
  - the still frame B with the `.25` row tint on a white-stripe team (TOR, DET, TBL), where the tint is a grey wash.

---

## Engineering rules: checked in the code

| Rule | Where | Result |
|---|---|---|
| No `graphicsLayer`, clip or blur on an ancestor of the `SurfaceView` | `StackedBug.kt:468` (`graphicsLayer { alpha; clip = true }`) is on the bug's own leaf `Box`, sized footprint + 2 M; `:1108` is the place hint's own leaf | **Pass**: both are leaves inside the bug, siblings of the video |
| No real-time blur | `BlurMaskFilter` appears only in `NineSlice` (ctor, `:65`) and `StackedGoalPainter.bakeMask` (`:326`). The ctor runs via `painter.bakeShadow()` under `withContext(hooks.background)` (`StackedBug.kt:190–198`); `bakeGlow` under `withContext(hooks.background)` (`:1000`). Both draw into a software `Canvas(bitmap)` | **Pass**: bake-only, off the main thread, never per frame |
| No `saveLayer`, `clipPath`, `setShadowLayer` in draw | grep: none in any `Stacked*.kt` / `NineSlice.kt`. Clips are `androidx.core.graphics.withClip(float…)` = `save` / `clipRect` / `restore` | **Pass**. The fade's `graphicsLayer` alpha < 1 takes an offscreen layer for 150 ms, as the Pill does |
| `ALPHA_8` drawn with a colour paint on a hardware canvas | shadow (`shadowPaint` black .55), glow (`glowPaint` team colour), badge shadow (`extractAlpha`, black) | **Pass**: HWUI tints A8 textures by the paint colour on both pipelines; no software fallback path is taken |
| Allocation-free draw | read `drawFrame`, `drawBar`, `drawRows`, `row`, `logo`, `drawSog`, `drawClockColumn`, `drawClock`, `drawGlow`, `drawTakeover`, `drawBadge`, `drawWord`, `NineSlice.draw`, `LogoDisc.draw`, `fillArgs` | **Pass** (inline `withClip` / `withTranslation` / `let`; `asAndroidBitmap()` unwraps; strings live in the body) |

---

## Performance on a Fire TV Stick (1080p)

**Bitmaps** (1080p; 4K × 4):

| Bitmap | Std | Large | Built | Freed |
|---|---|---|---|---|
| Shadow nine-slice, `ALPHA_8` | 102 × 108 ≈ 11 KB | 126 × 132 ≈ 17 KB | per painter (size), off-thread | with the painter |
| Glow pair, `ALPHA_8`, at the widest box 391.5 × 141 | peak 540 × 289 = 156 KB + low 456 × 205 = 93 KB ≈ **250 KB** | 642 × 328 + 569 × 256 ≈ **356 KB** | per rest box, LRU 2 | evicted or on a size change (GC) |
| Glow cache, at most | ≈ 500 KB | ≈ 712 KB | | |
| GOAL atlas, ARGB, × 1.5 | ≈ 200 × 80 × 4 ≈ 64 KB | ≈ 100 KB | per size, off-thread, `prepareToDraw` | with the goal painter |
| Badge copy + alpha | 70² × 5 ≈ 25 KB | 86² × 5 ≈ 37 KB | per takeover, when the logo identity changes | next takeover |
| Two row logos (FollowLogos, 67.5 px) | 2 × 18 KB | 2 × 28 KB | per follow | with the follow |
| **Total** | **≈ 640 KB** | **≈ 925 KB** | | |

This compares with the Pill's ≈ 180 KB of glow plus its atlas. Stacked costs more because it bakes per exact box and
keeps two pairs. It is still well within budget.

**Per frame.**
- One invalidated node.
- About 12 fills, 8 shadow blits, and 2 `drawPath`s (convex 4-point parallelograms; HWUI tessellates them each frame,
  which is trivial).
- Text from cached strings, shaped once through Minikin's layout cache, with HWUI's glyph cache.
- Logos are 67.5 → 43.2 bilinear (ratio .64, no mip needed).
- The heaviest frame is the glow at its peak: two full-layer A8 blends (≈ 250 k px) plus about 8 box-sized layers
  (≈ 300 k px), about 0.27 screens of fill.
- On a Mali-450-class GPU that is well under 1 ms. The design's ≤ 5 ms target holds.

**Against the Pill.**
- Costlier in glow memory and bakes (per box, re-baked on most layout rests: N9).
- Costlier in live text (the PP tab bakes its label into A8 masks; the bar draws text directly).
- About level on paths.
- Cheaper on shadows (one 11 KB nine-slice against the tab's per-size bake).

None of this is a concern. `StackedFramesTest` on the device is the real check.

---

## Photosensitivity (WCAG 2.3.1 general flash)

| Effect | Change | Cadence | Area (1080p) | Result |
|---|---|---|---|---|
| White flash, α .40 over the panel base (EDM `#041E42` → `#68788E`, L .013 → .183; TOR .018 → .189) | ΔL ≈ .17 (> .10), darker L < .8: **counts as a flash** | 3 cycles of 400 ms = **2.5 flashes / s** | Std 333 × 108 = 1.7 % of screen; Large 2.7 % | **Pass on frequency** (≤ 3 / s). See the area note below |
| Glow ramp | at most one up / down over 3.6 s | < 1 Hz | the box + ≤ 74 px | Not a flash |
| Row flash | single 1.2 s decay (stripe at .30–.55 over navy) | once | one row, 243 × 54 | Not a flash |
| Score pop | single scale / colour ease, 0.9 s | once | one digit | Not a flash |
| Lead wipe (accent) | one transition in, one out, 3.4 s apart | | the top part | Not a flash |

**Area note.** On a 55-inch TV at 3 m, a 10° field is about 0.217 m², and 25 % of it is 0.054 m².
- Standard panel: 0.014 m². Large: 0.023 m². Both are under the area limit.
- On a 75-inch TV at 2.5 m, the Large panel (0.042 m²) exceeds 25 % of the field (0.038 m²).

So the **3 × 400 ms cadence is load-bearing** (the flash ruling). It must never go faster than 3 / s. Red-flash check:
red-base panels (DET, CBJ lead) go red ↔ pink at the same 2.5 / s, which also passes.

---

## Colours on screen (the app's table through `NhlStackedColours`)

| Team | Stripe (vs row navy) | PP bar bg: white text / vs rows | Bar A / B (vs bar) | Row flash α: white / pop on the blend | Panel base / accent (lead) | Notes |
|---|---|---|---|---|---|---|
| TOR | `#FFFFFF` 14.6 | `#001A4B` 16.8 / **1.15** | white 16.8 / `#66799D` 3.83 | .50: 3.07 / 3.07 | `#00205B` / white | the flash is a grey wash (`#8894A7`), not a colour; the panel is nearly the row navy |
| NYI | `#F47D30` 5.42 | `#004580` 9.71 / 1.50 | 3.62 / 9.71 | .35: 8.5 / 3.17 | `#00539B` / `#F47D30` (2.89) | fine |
| SEA | `#99D9D9` 9.21 | `#001221` 18.9 / 1.30 | 12.0 / 3.90 | .45: 4.8 / 3.02 | `#001628` / `#99D9D9` | fine |
| VGK | `#B4975A` 5.21 | `#2A3437` 12.8 / **1.14** | 4.57 / 12.8 | .30: 9.1 / 3.25 | `#333F42` / gold | weakest flash (.30) |
| LAK | `#A2AAAD` 6.16 | `#0E0E0E` 19.3 / 1.33 | 8.17 / 3.90 | .35: 7.5 / 3.16 | `#111111` / silver | fine |
| PIT | `#FCB514` 8.15 | `#000000` 21 / 1.44 | 11.8 / 3.66 | .45: 5.5 / 3.07 | black / gold | fine |
| EDM | `#FF4C00` 4.36 | `#D33F00` 4.69 / 3.11 | 3.53 / 2.16 | .40: 9.3 / 3.18 | `#041E42` / `#FF4C00` | **inverted against the mock** (the mock's panel is orange `#C94A06` with a navy lead) |
| PHI | `#F74902` 4.10 | `#CD3C02` 4.95 / 2.94 | 4.24 / 2.21 | .45: 8.8 / 3.03 | `#F74902` / black | GOAL white on orange is 3.55 (the C1 owner exception; large text, OK) |
| ANA | `#FE4111` 4.15 | `#D2360E` 4.91 / 2.96 | 3.51 / 2.17 | .45: 8.8 / 3.04 | `#171C19` / orange | fine |
| CBJ | `#D53547` **3.07** | `#AB0E1F` 7.46 / 1.95 | **2.00** / 2.43 | .55: 8.8 / 3.17 | `#002654` / `#CE1126` (2.66) | thinnest stripe margin and the lowest bar A |
| WPG | `#4079B1` 3.18 | `#003F7D` 10.5 / 1.39 | 3.27 / 10.5 | .45: 8.7 / 3.06 | `#041E42` / `#004C97` (**1.95**) | blue on navy: a low-salience 3.6-px stripe; the lead barely differs from the panel |
| COL | `#4479A2` 3.12 | `#1D5079` 8.48 / 1.72 | 3.08 / 8.48 | .50: 8.3 / 3.02 | `#6F263D` / `#236192` (**1.58**) | the lead wipe hardly reads against the panel |

Every number meets its design threshold (§5.1). What the owner will see:
- the TOR / VGK / NSH / SEA / LAK / BUF / STL / TBL / UTA bars sit at 1.1–1.5 : 1 against the rows, and the hairline
  alone carries the edge (Q4, already an owner question);
- WPG and COL lead wipes are near-invisible against their panels. This is shared with the Pill (ruling 12) and not
  Stacked-specific;
- white-stripe teams (TOR, DET, TBL) get a grey row flash.

**Over white ice** (the common background):
- the rows (`#12284F`) are 13.2 : 1 and the columns (`#15191F`) 16 : 1 against `#F2F4F7`, so the box's own fill carries
  the edge;
- the border (`#2B3550`, 11 : 1 on ice) and the shadow are secondary there, as in the mock.

**Over dark video** the box is about 1.3–1.6 : 1 against black, and the border (`#2B3550`, 1.2 : 1 against the row
navy) does little. This matches the mock and goes on the hand pass.

---

## Robustness

| Case | Result |
|---|---|
| Missing logo | Blank slot until `LOGO_WAIT_MS`, then the 44-px disc (as the Pill). OK |
| Wide team code | The widest real codes (WSH 64.6, EDM 59.3 ink at 27) end at x ≈ 147 with the PP stripe. A 2-digit score's ink starts at ≈ 191, so the gap is ≥ 43. `StackedFitTest` covers it. OK |
| 2-digit scores | "10" = 37.5 in the 45.9 slot; the 1.5× pop stays inside the row (238.6 < 243) and clears the code. OK |
| 2/3-digit SOG | "199" = 38.7 ink in 58.5. OK (`SOG_MAX` 199) |
| One SOG null | The column never opens (targets). The painter also skips empty strings. OK |
| "10:12" | **B1** |
| "0:05.3" (tenths) | Cannot reach the painter: `clockSecs` is `Int` (ruling 6). It would show "0:05". OK |
| OT / 2OT / SO | "2OT" = 43.9 ink at 21.6 in 90. OK |
| Large font / display size | `BoxDesignScale` pins `fontScale = 1`, and the painter uses design px only. Ignored, as required. OK |
| RTL | **S2** |
| 720p / 4K | All sizes are × `s`; hairlines `coerceAtLeast(1)`. 720p text is 12 device px (the design rule's floor). Clip rounding: N5. 4K glow cache ≈ 2–2.8 MB. OK |

---

## Fidelity table (mock × 0.45 against the code)

✓ = matches. **S** = deviation the design sanctions (§ / ruling). **✗** = unsanctioned.

### Box, rows, columns

| Item | Mock | × .45 | Code | |
|---|---|---|---|---|
| Top part | 240 | 108 | `STACKED_H` 108 | ✓ |
| Row | 540 × 120, `#12284F` | 243 × 54 | 243 × 54, `#12284F` | ✓ |
| Box fill / columns | `#15191F` | | `STACKED_COL_BG` | ✓ |
| Border | 1 `#2B3550` | 0.45 | 1 device-ind. design px, drawn after the takeover | **S** (§4.2 lines not scaled). The order is the mock's (the border is on `.bug`, outside the takeover) ✓ |
| Radius | 6 | 2.7 | 2.7 | ✓ |
| Shadow | 0 12 36 .55 | dy 5.4, σ 8.1 | 5.4 / 8.1 / .55 nine-slice | ✓ |
| Divider | 1 white .22 under row 1, across the stripe | 0.45 | 1 px, 0..243 | **S** |
| Stripe | 8 / 14, 400 ease | 3.6 / 6.3 | 3.6 / 6.3, 400 ease; the logo and code move with it | ✓ |
| Logo slot | ml 22, w 112, h 96 / 98 | 9.9 / 50.4 / 43.2 (44.1) | 9.9 / 50.4 / 43.2 (both) | ✓ (TOR 0.9 px shorter, a mock quirk) |
| Code | pl 30, 60 / 800, ls .01 em, Inter | 13.5 / 27 | 13.5 / 27, .01, **Instrument Sans 700** | **S** (§11) |
| Score | w 90, mr 34, 78 / 800, tnum, lh 1 | 40.5 / 15.3 / 35.1 | **45.9** / 15.3 / 35.1, tnum, 700 | **S** (§4.2 widened; §11) |
| SOG col | 130, inset 1 `#2B3240` | 58.5 | 58.5, 1 px | ✓ |
| SOG digits | 54 / 700 tnum | 24.3 | 24.3 tnum | ✓ |
| SOG label | 19 / 700, .08 em, `#C3C7D0`, two 2-px `#5B6170` rules, gap 8 | 8.55 | **18**, .08, `#C3C7D0`, **no rules** | **S** (§4.2). Visibly 2× the mock's relative size |
| Clock col | 200 | 90 | 90 | ✓ |
| Period | slot 112, pb 14, 48 / 800, .02 em | 50.4 / 6.3 / 21.6 | baseline 44.1 − descent = 38.7 (mock ≈ 38.9) | ✓ (700, §11) |
| Rule | 2 `#5B6170`, margin 26 | 0.9 / 11.7 | 1 / 11.7 | **S** |
| Clock | 64 / 700 **tabular**, centred in the lower 126 | 28.8, centre y 79.65 | 28.8, centre 79.7, **proportional widest-digit cells** | **✗ B1** |
| Slot words | n/a (the mock has none) | | "END" / "INT" 28.8; "No update" 18; "Delayed" 22.8 | S1 |

### Bar

| Item | Mock | × .45 | Code | |
|---|---|---|---|---|
| Height | 66 | 29.7 | **33** | **S** (S5) |
| Content anchoring | bottom-anchored (`bottom: 0`) | | anchored at the outer edge (below: bottom; above: top) | ✓ (§6) |
| Stripes | left 46 / 84, w 26, ±10 overhang, `skewX(−30°)` about the centre, `/` lean | 20.7 / 37.8, 11.7, 4.5 | 20.7 / 37.8, 11.7, height 42, top shifted right by tan 30° × 21 | ✓ |
| Text | pl 140, gap 26, 40 / 800 italic, .01 em, white, `0 2px 2px rgba(0,0,0,.25)` | 63 / 11.7 / 18 | 63 / 11.7 / **20**, Instrument Sans **Bold Italic**, .01; shadow **1 px hard** .25 | **S** (S5, §11, §6) |
| Time | tabular | | `'tnum'` | ✓ |
| Hairline | none | | 1 white .22 on the inner edge, every bar | **S** (S2) |
| Colours | EDM `#D9520A` / `#12284F` / `#FF9A5A`; TOR `#1D4ED8` / `#0B1F4D` / white; INT `#1B2440` / `#FF4C00` / `#2563EB` | | derived (EDM `#D33F00` / `#041E42` / `#FF9466`; TOR `#001A4B` / white / `#66799D`; neutral `#1B2440` + the row stripes) | **S** (ruling 12, §5.1) |

### Takeover

| Item | Mock | × .45 | Code | |
|---|---|---|---|---|
| Extent | the top part only, bar visible | | the top part, the bar visible | ✓ |
| Lead / panel colours | EDM accent navy / base `#C94A06`; TOR white / `#1D4ED8` | | `NhlTeamColours` base / accent: EDM **navy panel, orange lead**; TOR `#00205B` / white | **S** (ruling 12, Q2 pending). The most visible change |
| Padding / gap | 40 / 30 | 18 / 13.5 | 18 → 8 under 18 slack; gap 13.5 | **S** (§4.2 fit). With the 20-px tally the default 333 box usually takes pad 8 (≈ 2.8 px of slack at 18 by my measure) |
| Badge | 150, `drop-shadow(0 6px 0 .25)` | 67.5, dy 2.7 | 67.5, A8 copy at dy 2.7, .25 | ✓ |
| Name line | 18 / 700, .22 em | 8.1 | dropped | **S** (lead) |
| GOAL | Barlow Cond. 900 italic 140, lh .86, .04 em, `0 6px 0 .28` | 63, shadow 2.7 | 63 atlas × 1.5, .04, 2.7 / .28 | ✓ |
| Tally | 30 / 800, gap 6, left | 13.5 / 2.7 | **20** / 700, 2.7, left, tnum | **S** (S5) |
| Streak 1 / 2 | 110 / 40, accent .55 / white .3, `skewX(−24°)`, overhang 40 | 49.5 / 18 | `STREAK1/2_W` × .45, .55 / .30, −24°, soft 1-px stops | ✓ |
| Flash | white .4, 4 × 300 from 420 | | white .40, **3 × 400** from 420 | **S** (flash ruling) |
| Glow | red `rgba(230,30,50)`: low 40 blur / 6 spread .55; peak 90 / 18 .8 | low σ 9 / 2.7; peak σ 20.25 / 8.1 | **team glow colour**; GA σ 10 / 2 and σ 22 / 8 (Large peak 22 / 10); a cross-fade of two masks | **S** (Q5, B4, GA §2.4) |
| Row flash | EDM `rgba(255,76,0,.55)`, TOR `rgba(37,99,235,.6)`, 1.2 s ease-out | | the scorer's **stripe** at its C7 peak .30–.55 | **S** (§5.1 C7) |
| Pop | 1.5 → .9 @ 40 % → 1.06 @ 70 % → 1; `#FF8A3D` / `#7AA7FF` → white, .9 s spring, from 3 600 | | same keyframes; `TeamColours.pop`; visible from 3 168 under the lead's exit | **S** (ruling 12) / N4 |
| Border over the takeover | not covered (on `.bug`) | | drawn after | ✓ |

### Animation (`StackedTimeline`, `GoalSpec.STACKED`)

**Layout transitions** (ms; Std = `cubic-bezier(.4,0,.2,1)`, Spring = `(.2,.8,.2,1)`):

| Channel | Mock CSS | Code | |
|---|---|---|---|
| SOG width open / close | .45 Std 0 / .45 Std .35 | 450 Std 0 / 450 Std 350 | ✓ |
| SOG contents open | α .3 @.3, x .35 @.3 (ease), −12 | 300 @300, 350 @300, ease, −5.4 | ✓ |
| SOG contents close | α / x .2 ease | 200 / 200 ease | ✓ |
| Clock width close / open | .45 Std 0 / .45 Std .35 | ✓ | ✓ |
| Clock contents close / open | α .15 / α .3 @.5 | 150 / 300 @500 | ✓ |
| Bar height open / close | .4 Std @.4 / .4 Std 0 | ✓ | ✓ |
| Bar stripes open | .45 Spring @.7 / @.78, from −140 | 450 Spring 700 / 780, −63 | ✓ |
| Bar stripes close | .3 ease-in | 300 `EASE_IN` | ✓ |
| Bar text open | α .35 @.85 (ease), x .45 Spring @.85, −24 | 350 / 450 @850, −10.8 | ✓ |
| Bar text close | .15 / .15 ease | ✓ | ✓ |
| Bar background on a change | instant (no transition) | 300 ms ease lerp, content swaps at 300 | **S** (§7.1 change row) |
| Text swap | n/a | out 150, in 350 / 450 | **S** (S10) |
| Row stripe | .4 ease | 400 `EASE` | ✓ |

**Goal** (3 600 base; CSS easing per keyframe segment; code per segment):

| Channel | Mock | Code | |
|---|---|---|---|
| Lead | Wipe; 0–6 % in, 94–100 % out | 0–216, 3 384–3 600, `WIPE` | ✓ |
| Panel | 3–11 %, 88–97 % | 108–396, 3 168–3 492 | ✓ |
| Glow | ease-in-out 0 / 15 / 50 / 85 / 100 % | 540 / 1 800 / 3 060, `EASE_IN_OUT` | ✓ |
| Streak 1 | 1 s Std @.35, −200 → 1 000 | 1 000 @350, −90 → 450 | ✓ |
| Streak 2 | .8 s @1.75 | 800 @1 750 | ✓ |
| Flash | .3 × 4 @.42, ease-in-out, .4 | 400 × 3 @420, ease-in-out halves, .40 | **S** |
| Logo | .6 Spring @.45; 70 %: α 1, × 1.1, 6°; from α 0, × .3, −120° | 600 @450, 0.7, 1.1, 6°, .3, −120 | ✓ |
| Letters | .45 Spring @.42 / .5 / .58 / .66; 60 %: −5, × .94; from +60, × 1.9 | 450 @420 + 80 i; 0.6; −2.25, .94; 27, 1.9 | ✓ |
| Word pulse | .5 × 3 @1.15, ease-in-out, 1.06 | 500 × 3 @1 150, 1.06 | ✓ |
| Tally | .45 Spring @.9, +36 | 450 @900, +16.2 | ✓ |
| Name line | .45 @.72 | dropped | **S** |
| Pop | .9 Spring @3.6 (the `after` phase); 40 / 70 %; colour over 0–100 % | 900 @3 600, the same keys; colour `SPRING.ease(p)`; first state from 3 168 | ✓ / N4 |
| Row flash | 1.2 s ease-out @3.6 | 1 200 `EASE_OUT` @3 600 | ✓ |
| End | 4.8 s (`phase: idle`) | `endMs` 4 800 | ✓ |
| Reduced motion | `.01s`; streak / flash / word off | §7.3 still frames A / B | **S** |

---

## The three differences from the mock the owner will notice first

1. **Goal colours.** EDM's takeover is a navy panel with an orange lead, the reverse of the mock's orange panel with a
   navy lead. TOR is navy and white, not the mock's blue. The glow is the team colour, not red. (Ruling 12; Q2 is still
   pending with the owner.)
2. **The clock column is crowded.** "10:12" (and "Delayed" / "No update") run edge to edge. The mock has about 5 px of
   air (B1, S1).
3. **The SOG label is about twice the mock's relative size, with no flanking rules.** The bar is slightly taller with
   larger text (33 / 20 against 29.7 / 18). Both are sanctioned (S5).

---

## Fix pass (2026-11-11)

Each finding was checked in the code at `af08859` before it was fixed. None was rejected. The contact sheets were
regenerated (`ui/build/score-bug-stacked/`) and the Standard dark sheet looked at: every clock now has air inside its
column, "Delayed" fits on one line, and "No update" reads as two centred lines.

| ID | Disposition |
|---|---|
| B1 | **Fixed.** `clockPaint` has `'tnum'`; the digit table is measured with it. `CLOCK_BREATHING` 5 (the clock's advance), so the clock stays at 28.8 ("19:58" ≈ 77 of 80). New `StackedFitTest.theClockIsTabularAndKeepsItsAir`: the clock px, the budget `clockW − 2 × 5 × s`, equal widths across values, and for seven values the drawn width and the ink (`StackedPainter.clockInk`) inside the 5-px air, at both sizes. |
| S1 | **Fixed.** `WORD_BREATHING` 6 for every column word (budget 78). New `StackedPainter.fitSlot`: a slot string with a space that does not fit on one line at 28.8 breaks at its first space onto two lines at one fitted size (pitch 1.05 em), the block's ink centred on the lower slot and 6 px inside it, when that gives the larger size. "No update" → "No" / "update" at 20.8 (block 41.1 of 44.6 Standard); "Delayed" one line (20.8 Standard, 18.8 Large). `StackedFrameState` gains `slotBreak` / `slotDy1` / `slotDy2` (defaulted); `BodyMerge` carries them. `StackedFitTest` asserts each line's ink, the break, the block height, its centring (±1) and the gap between lines. Strings and the §8 slot unchanged. |
| S2 | **Fixed.** `StackedBug` lays out its layer and hint under `LocalLayoutDirection provides Ltr`. The test showed a second cause: a right-to-left parent `Box` aligns a smaller child to its right, moving the whole layer (2738 instead of 1358). `stackedPlaceAt` and the hint's layout now take their parent's whole bounded space and place at absolute offsets. New `StackedBoundsTest.aRightToLeftLocaleMovesNothing` (RTL vs LTR, hosted in a full-screen box: layer, drawn box, live region and hint at both sizes, four corners, play and PP, placing on and off). The Pill (`placeAt`, `PlaceHint`) shares the parent-alignment cause and is not changed here. |
| S3 | **Fixed.** New `StackedScreenshotTest.theDrawnGeometryMatchesTheDesignTable`: 31 probes per size from the rendered pixels (both codes' left edge 77.4 / 80.1, both scores' right edge 227.7, SOG digits x / y on both rows, the label's x / y, the period's baseline (38.7) and centre, the rule's left / right / top, the clock's four ink edges and its air, both bar stripes' edges at rest, the words' left at 63). Expected text ink is the §4.2 origin plus the glyph outlines (`StackedPainter.inkBounds`). Shapes ±1 px; text ±1.5 px (the renderer snaps glyph origins to whole pixels; measured worst 1.17). Grayscale goldens skipped (lead). |
| S4 | **Fixed.** The mid-transition cells are built by `StackedFixtures.sampled(rest, Move…)` through the real `StackedTimeline.sample` at named instants: `pp-open-t300`, `pp-open-t900`, `pp-close-t400`, `int-to-play-t600`, `change-pp-to-int-t100` (replacing `pp-opening`, `int-folding`, `change-pp-to-int`). The only dimmed bar left is the neutral NO UPDATE (`stale-int`). `aChangeDrawsThePreviousContentUntilItsSwap` reads the sampled `barBgMix`. |
| N1 | **Commented.** The inset line is the column's edge: it fades with the column but is not dimmed. |
| N2 | **Fixed.** The label comes from `sports_stacked_sog`, resolved in `frame()`. |
| N3 | **Fixed.** `BarContent.labelAdvance`, set by `fillBar` (and copied); the draw no longer measures. |
| N4 | **Doc.** Recorded in design §7.2: the code follows the Pill's GA "pop reveal" rule. |
| N5 | **Left** (noted only). |
| N6 | **Fixed.** `glowPaint` has `FILTER_BITMAP_FLAG`. |
| N7 | **Left** (shared with the Pill). |
| N8 | **Left** (not worth it now). |
| N9 | **Left.** Glow re-bake policy unchanged (LRU 2); a 3–4 entry cache is a constant and a test change, deferred. |
| N10 | **Fixed.** `begin()` calls `prepareToDraw()` on the badge copy and its alpha. |
| N11 | **Fixed.** `StackedFonts.preload(context)` loads the Bold Italic into `ResourcesCompat`'s cache; `MainActivity` calls it on `Dispatchers.Default` after the first frame. If Stacked composes before it lands (straight into FULL on a cold start), the load is on the main thread as before. |
| N12 | **Partly.** `StackedDrawTest` gains no 300-frame allocation run. **Left.** |
| N13 | **Partly.** The RTL bounds case is added (S2). **Left:** 720p / 4K renders, the widest-tally takeover at 301.5, the white-stripe still frame B. |
