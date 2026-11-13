# Review: mirrored Stacked score bug in the right-hand corners (19d53ae)

Reviewer: independent agent, 2026-11-13. Commit under review: `19d53ae` (parent `3c91006`).
Scope: `ScoreBugPlacement.kt`, `StackedPainter.kt`, `StackedGoalPainter.kt`, `StackedContracts.kt`, `StackedBug.kt`, the
tests, design §4.3 and the decision note.

**Verdict: merge after the two should-fixes.** Both are small: one is a broken list in the decision note, the other
needs an owner call on the streak's lean (or a one-line change). Nothing blocks.

## How I checked it

- `./gradlew --max-workers=2 -q :core:test --tests '*sports*' :ui:testDebugUnitTest --tests '*sports*'` at HEAD: passes.
  Test output: the rows' pixels in the runner are `(1581, 58)–(1820, 162)` at even strength and on the power play
  (Standard), and `(1520, …)–(1820, …)` at Large, in both right corners. The mirror property ran 565 element checks.
  The narrowest mirrored bar slack is 18.2 px ("WPG POWER PLAY 19:59", Standard, 333).
- **Left corners are pixel-identical.** I checked out the parent's `ui/src` and `core/src` in this worktree, rendered
  `StackedScreenshotTest` and ran `diff -rq` against HEAD's `ui/build/score-bug-stacked/`. All 306 PNGs that both
  versions produce are byte-identical. The only extra files are the two new `stacked-mirror-sheet-*.png`. I restored
  the HEAD sources afterwards; the tree is clean.
- I read the code paths for `mirrored = false` line by line: `l = 0`, `drawBar(…, 0, w)`, `ax`/`bx`/`tx` unchanged,
  `row()` with `start = stripe`, `drawSog` `cl = rowsW`, the clock column `x0 = cl = rowsW + sog`, the takeover called
  without a translation, `tagX = footprint.l − footprint.l = 0` exactly, `originDx = 0f`, and the place hint
  `bx = round((o.x + 0) · d)`. Every value is the same as before.
- I looked at `stacked-mirror-sheet-std.png` (cropped into three parts) and checked every row: even strength, power
  play, 5-on-3, intermission, the goal at 150, 700 and 1800 ms, no update, no update in a break, shootout, final, the
  power play opening at 300 ms, the bar above, intermission with the bar above, and the goal at 700 ms with the bar
  above.
- Rounding in the runner, by calculation at 1280, 1920 and 3840 px wide (the 333, 391.5 and 301.5 boxes, at both
  sizes): see L1 below.

## Blockers

None.

## Should-fix

### S1. The decision note's numbered list is cut in two
`docs/decisions/2026-11-10-nhl-score-bug-stacked.md:37-54`. The new section `## Right-hand corners mirrored` was
inserted between items 7 and 8 of "Look-and-feel defaults". Item **8. Large size** now sits under the mirror section,
so it reads as part of the 2026-11-13 request, and the list of "all eight confirmed" defaults ends at 7.

**Fix:** move the new section below item 8, before `## Lead rulings`.

### S2. The goal streak leans the opposite way to GOAL and the bar stripes (needs an owner call)
`ui/src/main/kotlin/app/thebox/ui/sports/StackedGoalPainter.kt:371-375`, design §4.3 item 5 (`m6-…-theme.md:518`).

The streaks are drawn under `withScale(-1f, 1f, boxW / 2f, 0f)`, which flips their **lean** as well as their
direction of travel. In the right corners the band leans "\" across the italic "/" GOAL.

The same commit deliberately keeps the bar's stripes leaning "/" "to match the italic words" (`:515`). The two rules
contradict each other. Top right is the **default corner**, so this is the goal animation most users will see.

Seen in `stacked-mirror-sheet-std.png`, rows "Goal 700 ms (streak)" and "Bottom, goal 700 ms": the left band is "/",
the right band is "\" and crosses the "/" letters of "GOA".

**Fix:** pick one rule and ask the owner to confirm it.
- Either keep the "\" lean and say why in §4.3 item 5.
- Or keep the "/" lean and mirror only the sweep: drop the `scale(-1, 1)`, draw the band at the reflected position
  (`boxW − dx − bandW`, with `bandW` the band's horizontal extent) and run its `dx` timeline backwards. This also
  removes the only canvas reflection in the feature.

## Later

### L1. At 720p the mirrored rows' outer edge falls between two pixels
`StackedBug.kt:451-455`. `originDx = (m + footW − rowsW·k)·d − round((m + tagX)·d)` makes the rows independent of
`tagX`. That part is correct and proven: the rows do not move between the 333, 391.5 and 301.5 boxes at any density,
and nothing shimmers, because `tagX` changes only when the targets change.

The rows' **outer** edge, though, is `round((footprint.l − m)·d) + (m + footW)·d`. My calculation:

| Screen | Size | Mirrored right edge | Ideal | Left corner's left edge |
|---|---|---|---|---|
| 1280 | Standard | 1215.667 | 1216 | 64 (whole px) |
| 1280 | Large | 1216.333 | 1216 | 64 (whole px) |
| 1920, 3840 | both | whole px | — | whole px |

On a 720p device the right edge's stripe and border are anti-aliased across one pixel. The left corner's outer edge
lands on a whole pixel.

**Fix:** use `round((m + footW)·d) − rowsW·k·d − round((m + tagX)·d)`. It is still independent of `tagX`, and the
outer edge snaps like the left corner's. I did not render this on a device; it is calculation only.

### L2. Changing corner flips the layout at the start of the slide
`StackedBug.kt:410, 434`. `mirrored` (and `tagX`) come from `holder.box`, the new footprint, while `pos` is still
animating from the old corner (`:177-183`). Moving top left → top right in place mode, the box mirrors on the first
frame and then slides. The visible box jumps by `footW − boxW` (59 px in play) before it moves.

`up` already behaves this way, so this is consistent with the existing code, and it only happens in place mode or on
a settings change (a takeover is cancelled while placing).

**Optional fix:** decide by the sliding position (`holder.offset(pos)` plus half the footprint) instead.

### L3. Two rules for "right side"
`StackedBug.kt:428` uses `rightSide = last.l > 960f` for the place hint; `stackedMirrored` uses the footprint's centre.
They agree for every real placement (four corners, lifted, subtitles), but a future centred placement could split
them.

**Fix:** use `stackedMirrored(last)` for the hint too.

### L4. The badge sits on the inner side in the right corners
In the mirrored takeover the badge, GOAL and tally keep their left-to-right places, so the badge is over the clock
column, toward the screen centre, rather than near the screen edge. This is documented and intended ("not mirrored");
it is noted only so the owner sees it on the sheet ("Goal 1800 ms"). It looks fine.

### L5. Test gaps
- **Streak and wipe directions** are covered only by the sheet; there is no assertion. A probe would be cheap: the
  extent of the lead colour at 150 ms must touch the box's right edge mirrored and its left edge unmirrored, and the
  band's centre at two times must decrease in x.
- **Mid-transition column contents:** `theMirroredBoxIsTheReflectionOfTheLeftOne` skips the inset lines and centred
  text when `look.moving` (`StackedScreenshotTest.kt:910, 928`). The direction of the SOG contents' 5.4 px slide, and
  the bar words' and stripes' slide-in on a change (`look.prev != null` is skipped), are not asserted. I checked the
  formulas by hand: `sogDx`, `stripeAX/BX` and `barTextDx` are all negative travel and are correctly reflected.
- **The runner is tested at density 1 only.** `theMirroredBoxEndsAtTheRightMarginAndTheRowsNeverMove`
  (`StackedBoundsTest.kt:159`) covers even strength and the power play, but not the break box (301.5), and no other
  density. See L1.
- **Not tautological:** the mirror test compares two independent renders' pixel extents (x ↦ w − x). It is a real
  check, not the same code path compared with itself. The core reflection test in `ScoreBugPlacementTest` reflects
  the left box by hand (`1920 − l.r`). That is also independent.

## What is sound

- **The left corners** are unchanged in code and byte-identical in all 306 renders.
- **The placement rule:**
  - `stackedMirrored` by the footprint centre is correct for all four corners at both sizes (Standard centres 292 /
    1628, Large 341 / 1579), and for the lifted and subtitle positions.
  - The `stackedTopBox` right-corner numbers (1491 / 1432.5 / 1522.5, right edge 1824) are correct.
  - The updated expectations in `ScoreBugPlacementTest`, `StackedBoundsTest` and `StackedSeamTest` encoded the old
    floating right edge. Changing them is legitimate, not a weakening: the containment check even became stronger
    (`top.l >= fp.l` added).
- **The painter's mirror:**
  - Box `[rowsW − w, rowsW]`.
  - Stripes clipped at the rows' right edge.
  - SOG clip `[−open, 0]` with contents anchored at the rows' edge.
  - Clock clip `[−sog − open, −sog]` with contents anchored at its inner edge, which moves with the SOG column.
  - Inset lines on the inner edges.
  - Bar stripes and words anchored to the right end, with travel reflected.
  - Hairline, border, ring, shadow (the nine-slice offsets only in y, so one bake serves both sides) and glow at `l`.
  - Nothing is drawn outside the box or over the rows mid-animation: the clips confine each column to its own open
    width (sheet row "PP opening 300 ms").
- **Text and logos are never reflected.** The only `scale(−1, 1)` wraps the two streak `drawRoundRect`s, which are
  shader bands with no text. `withScale` saves and restores (balanced). It is in a leaf `Spacer.drawBehind`, not on
  any ancestor of the video surface. No new layer, clip or blur.
- **The takeover:**
  - The takeover is translated by `l`.
  - `f.boxW` equals `pinBoxW` while pinned, because `pinSample` snaps the moving tracks, so the panel and glow fit
    the box. The glow cache key `(w, h)` does not depend on the side, and the masks are symmetric.
  - A power play ending during a takeover: the stripe tracks do not move while pinned (`step()` returns). They move
    after the release, as on the left.
- **No allocation in draw:** the allocation test really sets `a.mirrored` in the loop and passes `mirrored` to
  `drawTakeover`.
- **The bar words** are right-aligned by `wordsAdvance`, which is measured in `fillBar` (not per frame) and copied in
  `copyFrom`. A power play's time is tabular `m:ss`, so the label does not jitter as the seconds tick. The 2.7 px
  score shift when the stripe widens is the mirror of the left's 2.7 px logo and code shift; on the sheet it is barely
  noticeable and acceptable.
- **Other consumers:** no production code reads `stackedTopBox` or the drawn box apart from `StackedBug`. Avoidance,
  hiding, banners, subtitles and `glowAllowed` all use the unchanged footprint. The NFL bug and the Pill are untouched:
  no diff in their files, and `ScoreBugPlacement` only gained functions and the Stacked branch.
- **Docs:** §4.3 matches the code (63 / 20.7 / 37.8 anchors, column order, the origin rule), and the superseded Q7
  default is clearly marked in §4.3, §12 Q7 and decision item 7.

## What I could not verify

- **On-device rendering** at 720p or 4K. L1 is calculation only.
- **Motion in real time.** I looked at still frames (the sheet plus the mid-transition looks), not an animation
  capture, so jitter is judged by the formulas, not by eye.
- **A live corner change** while the bug is showing (L2 is from reading the code).
- **Two quick goals, stale or delayed mid-takeover, and period changes in the mirrored runner.** None of these code
  paths depend on the side apart from the painter, which I checked. No dedicated mirrored test exists for them.

## Fix pass (2026-11-13)

Lead rulings applied on top of `19d53ae` (uncommitted, for the lead).

| Finding | Disposition |
|---|---|
| S1 decision list cut in two | **Fixed.** Item 8 is back in place under item 7, and the "Right-hand corners mirrored" section now follows the list, before "Lead rulings". The section also records the S2 ruling, the L1 fix and L2 as accepted. |
| S2 streak leans "\" | **Fixed, per the lead's ruling: the "/" lean is kept on both sides.** The canvas `scale(−1, 1)` is gone, so nothing in the feature is drawn under a reflection. Mirrored, each streak band is drawn at the reflected position, `boxW − dx − bandW`: its extent on the centre line is reflected, and it sweeps right to left. The lead wipe and the panel wipe still start at the screen edge. Design §4.3 item 4 now gives one rule for the bar stripes and the streaks. |
| L1 720p half-pixel outer edge | **Fixed.** `stackedOriginDx` is now a pure, internal function in `StackedBug.kt`. Mirrored: `round((M + footW)·d) − rowsW·d − round((M + tagX)·d)`. The rows' outer edge is a whole pixel and does not depend on `tagX`. Unmirrored: still exactly `0f`, so the left corners do not change. The new `StackedOriginTest` (pure JUnit) checks 720p, 1080p and 4K, both sizes, all four corners and the 333 / 391.5 / 301.5 boxes. Right corners: the outer edge is a whole pixel, the same one at every width, the rows' left edge is the same, and on screen the edge is within half a pixel of `1824·d`. Left corners: the origin is 0 and `tagX` is 0. |
| L2 corner change flips before the slide | **Open, accepted (no change).** Consistent with `up`, and visible only in place mode or on a settings change. Recorded in the decision note. |
| L3 two "right side" rules | **Accepted (no change).** The two rules agree for every real placement. |
| L4 badge on the inner side | **Accepted (no change).** Intended: badge, GOAL and tally keep their left-to-right places. Documented in §4.3 item 4. |
| L5 test gaps | **Fixed.** See the tests listed below. |

Tests added for L5:
- `StackedScreenshotTest.theWipesStartAtTheScreenEdgeOnBothSides`: the lead wipe (60 / 140 ms) and the panel wipe (200 / 300 ms), drawn alone.
  - Left corners: the wipe is anchored at x 0 and its leading edge moves right.
  - Mirrored: it is anchored at the right edge and its leading edge moves left.
  - It is the reflection within 1 px, at both sizes.
- `StackedScreenshotTest.theStreakSweepsAwayFromTheScreenEdgeAndLeansTheSameWay`: the streak at 600 / 800 ms.
  - Its centre moves right on the left and left mirrored, and the centre-line extent is the reflection within 1.5 px.
  - It leans "/" on both sides: the top of the band is about 39 px (Standard) to the right of the bottom.
- `StackedScreenshotTest.theMirroredSlidesRunTheOtherWay`: mid-transition frames.
  - The SOG contents slide ∓2.7 toward the rows.
  - Bar stripe A slides in ∓10 from the bar's outer end.
  - The bar words slide ∓5.4 from the outer end.
  - Each mirrored move is the opposite of the left one, at both sizes.
- `StackedBoundsTest.theMirroredBoxEndsAtTheRightMarginAndTheRowsNeverMove` now includes the 301.5 break box (intermission). The rows' pixels are identical in all three states.

Re-checked:
- Left corners: I re-rendered `StackedScreenshotTest.everyLook…` and `theTakeover…` at the `3c91006` sources (forced rerun) and on the fix-pass code. `cmp` of all 306 PNGs: 306 identical, 0 different.
- Contact sheets regenerated. Goal rows: at 150 ms the lead wipe comes in from the right on the right side. At 700 ms the streak crosses "GOA" leaning "/" on both sides, in the same direction as the italic letters and the bar stripes. This holds with the bar below and with the bar above. The 1800 ms frame is unchanged.
