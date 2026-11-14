# Review: Stacked mirrored row, same order from the screen edge (second pass)

Date: 2026-11-13. Reviewer: independent agent (Opus). Diff reviewed: the uncommitted worktree against `2f9cb25`
(`StackedPainter.kt`, `StackedGoalPainter.kt`, `StackedContracts.kt`, `StackedFitTest.kt`,
`StackedScreenshotTest.kt`, the decision note and design §4.3).

Owner request: "i think i would like the right hand side stacked scorebug to follow the same order from the edge of the
tv as the one on the left side". Lead ruling: every element's position is the reflection of the left layout; glyphs and
bitmaps are never flipped; alignments mirror; left corners stay pixel-identical; draw stays allocation-free.

## Verdict

**Merge.** No blockers and no should-fix findings. The Low items below are optional, and none of them changes what is
drawn on screen except L1, which needs a ruling.

## What I verified (ran) and what I only read

Verified by running:
- `:ui:testDebugUnitTest --tests '*Stacked*'` in the worktree with `--no-build-cache --rerun`, so the results are not
  from the cache: every test passes (Draw 26, Screenshot 23, Fit 11, Seam 6, Bounds 5, Allocation 3, Logo 2, Origin 1).
  The allocation test reports 0 B per frame for both mirrored and unmirrored draws (plain, takeover and still).
- **The left corners are byte-identical.** I extracted `2f9cb25` with `git archive` into the scratchpad and ran
  `StackedScreenshotTest` there with no build cache. I then ran `cmp` on every PNG. 306 of 308 files are byte-identical.
  The 2 that differ are `stacked-mirror-sheet-{std,large}.png`, which contain mirrored cells. The 2 new files are the
  owner's `stacked-mirror-states-*` sheets.
- Printed numbers: the narrowest mirrored score-to-code ink gap is 36.6 design px (WPG / 10, Standard), against 41.6
  on the left. These match the figures in design §4.3.
- I looked at the sheets in `scratchpad/stacked-mirror2/`. The mirrored rows read [score][code][logo][stripe]. The
  takeover reads tally, GOAL, badge, with the badge at the screen edge. Nothing overlaps at even strength, power play,
  5-on-3, intermission, final or no update.

Read only (checked by reasoning, not by a test of my own):
- **Unmirrored arithmetic.** `logoCentre(stripe)` = `stripe + logoMl + logoSlotW / 2f` and
  `codeAnchor(stripe)` = `stripe + logoMl + logoSlotW + codePl` evaluate in the same order as the old inline
  `start + …` with `start = stripe`. `rowX(x, false)` returns `x` unchanged. The score anchor and the pop pivot
  `scoreRight − scoreSlotW / 2f` are unchanged. Left-corner float results are therefore identical, which the `cmp` run
  confirms. The goal painter's unmirrored branches pass the same `badgeCx`, `wordCx`, `tallyX + tallyDx * s` and
  `tallyPaint` as before.
- **Mirrored paths.**
  - Logo centre, code (right-aligned), score (left-aligned) and pop pivot are all `rowsW − x`.
  - The pop scales a left-aligned score about the centre of the reflected slot, which is the exact reflection of the
    left pop.
  - Row flash and stripes were already handled in the first pass and are unchanged.
  - The power-play stripe moves the logo and code; the score keeps its place.
  - Tally: right-aligned at `boxW − tallyX − tallyDx·s`. That puts the block's long line at `[pad, boxW − tallyX]`, the
    reflection of `[tallyX, boxW − pad]`. `tallyDx` runs +16.2 → 0, so the mirrored tally slides in from the inner side.
  - GOAL: the reflected centre equals the midpoint of the reflected middle span, with and without the tally.
  - The badge rotates about the reflected centre.
  - The fallback disc is centred, so it is only placed.
  - The tally being dropped (narrow box) is handled by the same `showTally`.
- **Paint state.**
  - `codeMirroredPaint` and `scoreMirroredPaint` are copied after `text()` has set size, typeface, letter spacing and
    `'tnum'`.
  - The `init` block only measures `codePaint` and `scorePaint`; it never mutates them.
  - At draw time the only mutation is `color`, and the mirrored paints set it on every draw.
  - `tallyMirroredPaint` is copied after `tallyPaint` is fully built; `begin` / `layout` only measure it. Alpha is set
    on the chosen paint each frame.
  - No text size changes at run time (a painter is per size), so a stale second paint cannot occur today.
- **Footprint and hit code.** `StackedBug.kt` and `stackedOriginDx` place the rows' box only, never their contents.
  `StackedBoundsTest` passes unchanged.

## Blocker

None.

## Should-fix

None.

## Low

### L1. Badge spin direction is not mirrored (ruling requested)
`StackedGoalPainter.kt:413` (`rotate(g.badgeRotDeg)`): −120° → +6° → 0° on both sides.
- On Android a positive angle is clockwise, so the badge turns clockwise into place. On the left that rolls the same way
  as the wipe, which runs from the screen edge inwards (left to right).
- In a right corner the wipe runs right to left, but the badge still turns clockwise, so it rolls against the wipe.
- Every other motion in this feature mirrors: wipes, streak sweep, tally slide, SOG slide, bar-stripe slide. Only shapes
  keep their orientation (the "/" lean, glyphs, bitmaps).
- A rotation's sign is motion, not a flip. Mirroring it never draws the logo reversed, and at rest (0°) the frame is
  identical.

**Recommendation: mirror it.** In `drawBadge`, use `rotate(if (mirrored) -g.badgeRotDeg else g.badgeRotDeg)`, passing
`mirrored` through. It costs one branch, adds no allocation and leaves the left untouched. The hard shadow is offset
inside the rotated frame, so it follows.

This is a taste call, not a defect. If the owner prefers the same spin on both sides, keep it, and keep the sentence
"the badge's spin … keep[s] [its] own orientation" in the decision note and §4.3. If it is mirrored, change those two
sentences, and the KDoc at `StackedGoalPainter.kt:366`, to say the spin direction mirrors.

### L2. No test pins the mirrored score pop
`StackedPainter.kt:584-588`. The mirrored pop pivot (`rowX(scoreRight − scoreSlotW / 2f, m)`) is correct by
inspection, but no test checks it.
- `theMirroredRowIsTheReflectionOfTheLeftOne` renders only at-rest looks.
- The mirror sheets are only checked for size.

Failure scenario: a later edit reverts the pivot to `scoreRight − scoreSlotW / 2f` without `rowX`. A mirrored pop would
then scale about a point roughly 190 px to the right, sliding the score across the code during the pop, and every test
would still pass.

Fix: render one look at peak `popScale` (left and mirrored) and assert that the score's ink is the reflection, using the
existing `text(...)` helper.

### L3. The fit test's reflection check is partly self-referential
`StackedPainter.kt:790-808` (`rowExtents`) and `StackedFitTest.kt:214-222`.
- `rowExtents` shares `rowX`, `codeAnchor` and `logoCentre` with `row()`, which is good. However, it re-encodes the
  mirrored alignments by hand (`− codeW if mirrored`, `− scoreW if not`) instead of reading the paints that `row()`
  uses.
- As a result, the "every element is the reflection" assertions in `aMirroredRowIsTheReflectionAndEveryCodeClearsEveryScore`
  check `rowExtents` against itself: both sides come from the same function. The `MIN_GAP` check depends on that
  hand-copied alignment matching the draw.

Failure scenario: someone changes `codeMirroredPaint` to `CENTER`. The fit test stays green and would still report
36.6 px.

This is mitigated, not open: the pixel test `theMirroredRowIsTheReflectionOfTheLeftOne` measures drawn ink and would
fail in that scenario.

Fix (optional): derive the alignment offsets in `rowExtents` from `codeMirroredPaint.textAlign` /
`scoreMirroredPaint.textAlign`, the way `inkBounds` does. Alternatively, reword the KDoc so it does not claim the fit
test proves the draw.

### L4. Parameters shadow fields in `drawBadge` / `drawWord`
`StackedGoalPainter.kt:409, 431`: the new parameters `badgeCx` / `wordCx` have the same names as the class properties.

Failure scenario: a later refactor drops the parameter. The body still compiles, silently reads the unmirrored field,
and the badge or word lands back on the inner side. The pixel test would catch this, but the code reads ambiguously.

Fix: rename the parameters to `cx`.

## Notes

- N1. The previous review's **L4 ("the badge sits on the inner side in the right corners", accepted, no change)** is
  obsolete. This pass places the badge nearest the screen edge. The old review is left as written; the decision note and
  §4.3 item 4 now carry the new rule, and no stale "reading order is kept", "inner side" or "one left-to-right block"
  statement remains in code or design docs. (grep over `ui/src` and `docs` outside `docs/review/`.)
- N2. The test seams `tallyAdvance`, `tallyInk`, `tallyBaselines`, `badgeSlotWidth`, `rowsWidth`, `stripeWidth`,
  `ppStripeWidth`, `advance` and `rowExtents` allocate or measure only, and are never called from draw. `tallyInk` is
  documented as allocating.
- N3. Code width is not mirror-specific. A code longer than three letters would grow toward the score on both sides
  equally. The fit test covers `StackedFixtures.TEAMS` and scores 0–19 at Standard and Large; the narrowest mirrored
  gap is 36.6 against a 2-px floor.
- N4. Design §4.3 and the decision note match the code: the 77.4 / 80.1 code anchors, the score 15.3 from the rows'
  left edge, the pop about the reflected slot centre, and the tally sliding in from the inner side (`−tallyDx`). The
  decision note's "306 … byte-identical … after the same-order pass" is confirmed by my independent `cmp` run.
- N5. Takeover test tolerances (1 px for shapes, 1.5 px for text ink) are tight. The row test compares the drawn left
  against the drawn right, so an unmirrored row (offsets of tens of px) or unmirrored alignment (an offset of a code's
  width) would fail it.

## Fix pass (2026-11-13)

Lead rulings applied in the same worktree (uncommitted):

- **L1, fixed (mirror the spin).** `StackedGoalPainter.drawBadge` takes `mirrored` and draws
  `rotate(if (mirrored) -g.badgeRotDeg else g.badgeRotDeg)`. The decision note, design §4.3 item 4 and the
  `drawTakeover` KDoc now say the spin direction mirrors. New test `StackedScreenshotTest.theMirroredBadgeSpinsTheOtherWay`
  uses an asymmetric stand-in badge (a horizontal white bar). Mid-spin (510 ms, −52°) it leans one way on the left and
  the other way mirrored by the same amount: the covariance lean is −93.1 against +92.9 at Standard and −144.6 against
  +144.5 at Large. An unmirrored spin would give the same sign on both sides and fail. At rest (1 800 ms, 0°) both
  sides are upright and the extents reflect.
- **L2, fixed.** New test `theMirroredScorePopScalesAboutTheReflectedSlot`. At 3 550 ms (panel out, pop at scale 1.5,
  before the row flash) the popped score's ink is its rest ink scaled about the pivot, on both sides at both sizes.
  The pivot is `243 − 15.3 − 22.95` from the rows' left edge, or the same distance from their right edge when
  mirrored. Standard mirrored: rest 107–124, popped 97–123, pivot 128.25. An unmirrored pivot would move the score by
  about 95 px.
- **L3, fixed.** `StackedPainter.rowExtents` now measures with the paints `row()` draws with
  (`codeMirroredPaint` / `scoreMirroredPaint` when mirrored). It derives each text's left edge from that paint's own
  `textAlign`, so a change such as `codeMirroredPaint` → `CENTER` now moves the extents and fails the fit test's
  reflection check.
- **L4, fixed.** The `drawBadge` / `drawWord` parameters are renamed to `cx`.
- N1–N5: no action.

Checks:
- `./gradlew --max-workers=2 -q :ui:testDebugUnitTest :ui:lintDebug` passes.
- All 306 unmirrored Stacked PNGs are still byte-identical to the 2f9cb25 baseline (`diff -rq` against the saved
  render).
