# Goal animation: design review

Reviewer: design review (Opus), 2026-11-07. Subject: `docs/design/m6-goal-animation.md` ("GA §n"), proposed 2026-11-07,
against `main` `817338e`, owner rulings 1–10 (ruling 8 as amended), the mock-up CSS
(`design/score-bug-goal-animation.template.html:318–462`) and the code. Line references are `file:line`.

Overall: the rendering approach is sound and the tree argument holds. The timeline is a faithful reading of the CSS,
and the contrast table's arithmetic checks out (I recomputed every C1–C4, glow and pop value, and all match). The
weak points are in four places:
- the UI trigger, which the current `ScoreBug` structure cannot deliver as the table claims;
- commit 0, which cannot pass its own gate as listed;
- the flash-safety claim;
- two costs that the frame-cost estimate leaves out.

---

## Blockers

### B1. The §5.2 trigger table can't be built on the current `ScoreBug` structure, and two of its claims are false
GA:302–307, 322–324; `ScoreBug.kt:113, 171, 183–197`.

1. **A goal that arrives while the bug is hidden is replayed when the bug reappears.** Today the bookkeeping
   (`holder.goalSeq = …`) runs inside a `LaunchedEffect` placed *after* the early return at `ScoreBug.kt:171`. While the
   bug is hidden, that effect is not in the composition, so `lastSeq` never sees the new seq. When the bug comes back
   inside the 4.5 s mark, the trigger finds `goalSeq != lastSeq`, `goalSide != null` and `box != null`, and starts a late
   takeover. GA:322 says this must not happen. The design must say that `lastSeq` is read and updated **in
   composition, above the visibility return**, for every non-null view: shown, hidden, placing or sample. It must not
   copy the existing effect pattern.
2. **Cancelling a takeover leaves it frozen, not finished.** When the bug finishes fading out, the effect leaves the
   composition and its coroutine is cancelled. The `Animatable` then stops at whatever value it had. Nothing snaps it
   to the end, because `snapTo` is a suspend call and cannot run in a cancelled scope. On the next reappearance the draw
   reads that value and paints a half-wipe, which is exactly what GA:323 forbids. Required fix: the draw renders the
   takeover only while a plain holder flag (`takeoverOn`) is set. Clear that flag synchronously on hide, follow change,
   place mode and size change (B1.3, S7). Do not rely on the `Animatable` value.
3. **"Follow changed or stopped mid-takeover: cancel; the holder is reset with the view" (GA:324) is not true.**
   `Holder` is `remember { Holder() }` with no key (`ScoreBug.kt:113`), and it lives across follows. The trigger only
   *restarts* when `goalSide != null`. A new follow's first view (seq 0, `goalSide` null) therefore leaves the old
   takeover running with the old team's colours and tally. Rule: **any** change of seq or game id cancels the running
   takeover, and a restart follows only if `goalSide != null`.
4. **Seq collision across follows.** `ScoreBugMachine.reset()` sets `goalSeq = 0` (`ScoreBugMachine.kt:399`). If B1.1
   is not fixed, the old follow's `lastSeq` can equal the new follow's first goal seq, and that goal is missed.
   Cheapest guard: make `goalSeq` monotonic for the machine's lifetime by not resetting it in `reset()`, or key
   `lastSeq` on `(game id, seq)`.
5. **The UI must own the timeline once it starts.** The mark is `at + 4 500` on the machine's mono clock
   (`ScoreBugMachine.kt:340`). The UI's run is scaled by `MotionDurationScale` (GA:408), so at animator scale 2 it
   lasts 9 s. Also, a wake that fires late shortens the mark. State that after `begin()` the UI never reads
   `goalSide` again: the side, tally and colours are captured at start, and the end of the mark only changes the
   description.

The design's intent is right. As written, though, a Sonnet implementer following "the existing pattern" would ship
the two visible bugs in B1.1 and B1.2. Rewrite §5.2 as an algorithm (what runs in composition, what runs in effects,
and what the draw gates on), not just a table.

### B2. Commit 0 cannot pass its own gate, and the disjointness claim is wrong for commit 0
GA:483–514, 540–543.

Commit 0 removes `CentreText.Goal`, `GOAL_TEXT_MS`, `GOAL_BOX_*`, `GOAL_IN|HOLD|OUT_MS` and `sports_goal`, and swaps
the `drawBody` signature. Every one of these call sites then fails to compile, so the gate
(`smoke build + lintAll`) cannot go green until each is edited **in commit 0**:

| File | Lines | What breaks |
|---|---|---|
| `core/.../ScoreBugMachine.kt` | 224, 340 | `CentreText.Goal(goal)`, `GOAL_TEXT_MS` |
| `ui/.../SportsText.kt` | 76 | `is CentreText.Goal`, `R.string.sports_goal` |
| `ui/.../ScoreBugPainter.kt` | 42, 143, 221–241, 267 | `bottomGoal`, `GOAL_BOX_*`, the `drawBody` signature |
| `ui/.../ScoreBug.kt` | 183–197, 221, 311 | `GOAL_IN/HOLD/OUT_MS`, the `drawBody` call, `Describe`'s `CentreText.Goal` branch |
| `core/src/test/.../ScoreBugMachineTest.kt` | 312, 500, 502 | asserts on `CentreText.Goal` |
| `ui/src/test/.../SportsTextTest.kt` | 45 | `CentreText.Goal` |
| `ui/src/test/.../ScoreBugTestSupport.kt` | 45–46 | `goalHome` / `goalAway` fixtures, shared by the WP-2 and WP-3 tests |
| `ui/src/test/.../ScoreBugScreenshotTest.kt` | 88, 122 | the old `drawBody` arguments |

Consequences:
- "`ScoreBugPainter.kt` is WP-2 only; `ScoreBug.kt` is WP-3 only" holds only *after* commit 0.
- WP-1's "`SportsText.kt`'s removed branch" (GA:519–520) belongs to commit 0.
- `ScoreBugTestSupport.kt` needs a single owner, because WP-2 and WP-3 will both want new fixtures there. Suggestion:
  commit 0 adds `goalMark(side)` fixtures and nobody else edits the file.

Commit 0 is also missing some declarations:
- **`GlowMasks`** appears in the stub signature but is not declared.
- **Who turns `GoalFrame`'s pop mix into `popColour: Int`** (painter or wiring) is not stated.
- **The draw-order composite** is not placed (see S5).

With those additions, WP-1/2/3 are disjoint and can run in parallel.

### B3. The flash-safety claim does not hold as stated. Run the flash at no more than 3 Hz.
GA:377, 390–391.

Each flash cycle is a WCAG flash. White at .45 over any base moves relative luminance by 0.17 or more (for example
navy 0.013 → 0.222, PHI 0.245 → 0.452), and the darker state is far below 0.8. Four cycles of 300 ms make
**3.33 flashes/s**, so the frequency clause fails, and the design falls back on the general-flash *area* clause. That
clause depends on viewing geometry. For a 16:9 screen at 1080p, 25 % of a 10° field is about **29 100 · r² design px²**,
where r is viewing distance ÷ screen diagonal, whatever the screen size:

| Viewing | r | Threshold | Standard pill (≈ 27.2 k px²) | Large pill (≈ 42.9 k px²) |
|---|---|---|---|---|
| 55" at 2.5 m (the doc's case) | 1.79 | 93 k | passes | passes |
| THX-style 40° seating (65" at 2 m) | 1.2 | 42 k | passes | **at or just over the threshold** |
| 75" at 2 m | 1.05 | 32 k | passes by a little | **fails** |

Reading "any 10° field" as a circle laid over the wide pill gives about the same margins. So "well under" is not
right: Large is marginal at common living-room distances.

The broadcast rule (ITU-R BT.1702 / Ofcom, 25 % of the *screen*) passes easily at 1.4–2.1 %. The saturated-red
clause narrowly does not apply: the highest R/(R+G+B) is 0.79 (DET, NJD, CAR, CHI), just under 0.8.

**Fix:** keep the 420–1 620 window but use **3 cycles of 400 ms (2.5 Hz)**, or 4 cycles of at least 334 ms. That passes
WCAG 2.3.1 on the frequency clause alone, with no assumption about distance. It is a one-constant change. Add a
`GoalTimelineTest` assertion that no 1 s window holds more than 3 flash cycles. The still version already has no
flash (GA:399).

---

## Should-fix

### S1. The glow spills onto the bars and the overlays the bug avoids
GA:57–58, 140–145; `ScoreBugPlacement.kt:28–29, 43, 61–66`; `FullScreenHost.kt:117–141`.

- **The bars.** A bottom-corner bug *slides above* a user bar or the quick bar with only `SCORE_BAR_GAP` = 16 px
  between them. The bug is composed after the bars (`FullScreenHost.kt:141`, after 117–135), so its glow is drawn
  **on top of** them. 16 px from the pill, the peak glow alpha is about `.8 · ½·erfc((16−8)/(20√2))` ≈ **0.28**, a
  team-coloured haze over the top of the quick bar while the user is working it.
- **The overlays.** The other-overlay rules test only the *pill* box (`ScoreBugPlacement.kt:43`). Stats, the channel
  list or the group card can sit inside the glow's 68–76 px margin and get tinted.
- **The 2 % screen-edge argument (GA:140–145)** assumes the nearest neighbour is the 56 px screen inset. That is false
  in these cases.

Fix, either of:
- (a) no glow, or only the low mask at reduced alpha, while the box is lifted (`bar != NONE`) or an active overlay lies
  within the glow margin;
- (b) a takeover-only placement check that inflates the pill by the glow margin.

(a) is simpler. Add a screenshot frame with the quick bar open.

### S2. Text drawn at an animated scale is re-rasterised every frame, and the estimate leaves this out
GA:109–115, 166–168.

GOAL's four letters and their shadows change scale continuously for about 2 s (the drop-in from 420 to 1 080 ms, then
the pulse from 1 150 to 2 650 ms). The pop digit does the same for 900 ms.
- **API 25 HWUI (GL):** the `FontRenderer` caches glyphs per transformed size, so every frame rasterises new 50–100 px
  glyphs, uploads them to the cache texture, and eventually flushes the cache.
- **API 28+ Skia pipeline:** every frame creates a new strike.

On a Fire TV Stick's A53/A7 cores this is RenderThread CPU time, plausibly 1–2 ms per frame plus cache flush stalls, and
none of it appears in §2.6. Fix: when `GoalPainter` is built, **pre-render the four letters with their hard shadows
into one bitmap** at about 1.5× the rest size, then draw each letter with `drawBitmap(src, dst, paint)` under the
transform. A white `ALPHA_8` can be tinted. This costs a few tens of KB, gives the same cost on every API level, and
needs no glyph cache. Downscaling with filtering looks clean. The brief 1.9× start is at low alpha, so upscaling there
does not show. Leaving the pop digit as text is acceptable.

### S3. The bug's layer does not clip, so every takeover frame damages the whole window. Bound the layer (this also settles §11 Q5).
GA:54–67, 162–172.

HWUI's `RenderNode::damageSelf` marks the whole window dirty (`DIRTY_MIN … DIRTY_MAX`) for any node whose
clip-to-bounds is off. Compose's `graphicsLayer` defaults to `clip = false`, and that is the node the `Spacer` records
into (`ScoreBug.kt:209`). So each of the roughly 270 takeover frames repaints the **full 1080p window**, not the pill:
- the RenderThread replays the whole FULL tree;
- the GPU clears and fills about 2 M px;
- partial update cannot help.

That is the opposite of GA:167's "replays a short display list". Fix:
- Make the bug's outer `Box` the pill **plus `max(glow peak margin, shadow margin)`** on every side.
- Use `graphicsLayer { alpha = fade; clip = true }` on it.
- Offset the `Spacer` by the margin inside it, and shift `placeAt` by −margin.

Effects:
- the damage becomes about 540 × 206 px;
- the fade's offscreen layer contains the glow and the glass shadow, so the existing shadow cut during fades goes away
  too;
- the clip sits on the bug's own leaf layer, never on an ancestor of `VideoLayer`, so the CLAUDE.md rule and
  `RootTreeRuleTest` are untouched;
- keep `testTag` on the pill-sized `Spacer` so the existing bounds tests still hold.

Measure the damage with "Show GPU view updates" or `dumpsys gfxinfo` in `GoalFramesTest`.

### S4. Hard-stop gradient bands are aliased. Specify soft stops and the skew geometry.
GA:95–101.

A `[T, C, C, T]` gradient at `[0, 0, 1, 1]` is a step function evaluated per pixel. Gradients get no edge
anti-aliasing, so the two slanted 24° edges stair-step visibly at 1080p on both pipelines. Use stops
`[0, ε, 1−ε, 1]` with ε = 1 device px ÷ band width. Also write down two geometry details:
- **The skew sign.** CSS `skewX(−24deg)` leans the band "/", so its top is to the right of its bottom.
- **The skew origin.** It is the element's centre (CSS `transform-origin` 50 % 50 %), so the band's centre line meets
  the pill's vertical centre at `left + w/2 + tx`.

Otherwise the implementer must reverse-engineer these from the mock. The translate trick that avoids `setLocalMatrix`
is correct, and it allocates nothing.

### S5. Put the draw-order composite in WP-2, not in `ScoreBug.kt`
GA:73–82, 441–448, 523–538.

The order glow → glass → body (with pop) → takeover lives in the `drawBehind` lambda, which is WP-3. The WP-2
`GoalAnimationScreenshotTest` must still render "real `ScoreBugPainter` + `GoalPainter`", so it would copy that order
and test the copy. Add one `drawFrame(c, …)` in the painter layer (WP-2) that `ScoreBug.kt` calls in a single line,
and declare its signature in commit 0.

### S6. The C1 "large text" argument treats design px as CSS px. That does not hold on a TV.
GA:202; §11 Q2.

WCAG's 18.66 px bold assumes CSS reference pixels (about 0.0213°). On a 55" screen at 2.5 m, one 1080p design px is
about 0.0146°, so the 22 px tally is about **15 CSS px**: *not* large text, and it would need 4.5 : 1. GOAL at 52 px
(about 35 CSS px) is large under any reading. PHI's tally at 3.6 : 1 therefore fails the rule the table claims to
apply. The table's other rows are fine. The fix is to the rationale, not the number:
- accept PHI's tally on the grounds that it is transient and repeats the score the bug shows straight afterwards (my
  recommendation, see Q2);
- record that as an owner exception;
- add **PHI** to the screenshot set. It is the C1 worst case, and NSH is only the hard case for gold-to-accent.

### S7. Place mode entered mid-takeover and a size change mid-takeover are not covered
GA:309–325; `ScoreBug.kt:144–151`.

The table covers a goal arriving *in* place mode, but not place mode starting *during* a takeover. That case leaves
the place ring partly covered and the takeover sliding around with the user's input. A Standard↔Large change rebuilds
the painter (`remember(s, shownLarge)`), so the `GoalPainter`'s `begin()` state and the glow masks are lost while the
driver keeps running. Rule: entering place mode or changing size cancels, using the same synchronous flag as B1.2.

### S8. Animations-off detection is read once and can disagree with Compose
GA:406–409; `SettingsUi.kt:211–219`.

`animationsOff()` is `remember(context) { Settings.Global.getFloat(...) }` and never re-reads. Compose's
`MotionDurationScale` *does* follow the setting live. So a user who turns animations off while FULL is composed gets an
`animateTo` that completes in one frame: no takeover and no still, only the end state. Inside the driver effect, read
`coroutineContext[MotionDurationScale]?.scaleFactor == 0f`. That is the exact value Compose applies, it needs no
`Settings` read, and it can never disagree. If the shared helper moves to `Motion.kt`, keep it only for
`SearchingDots`.

### S9. The TalkBack live region as specified is unreliable
GA:333–336; §11 Q4.

A zero-size node that *appears* with `liveRegion` set is an edge case:
- appearance-time announcements differ between TalkBack versions;
- zero-bounds nodes can be reported as not visible to the user and skipped;
- Fire TV's screen reader is **VoiceView**, not TalkBack.

Use a **persistent** zero-content live-region node whose text changes from "" to the goal string at mark start and back
at mark end. Content changes on a live region are the path that is announced reliably. Alternatively, call
`LocalView.current.announceForAccessibility(...)` once at takeover start, guarded by `AccessibilityManager.isEnabled`;
it is deprecated only at API 36. Add a manual VoiceView check to the device items.

### S10. Test-plan gaps (§8)
Add these. Each one maps to a finding above.

- **`ScoreBugDrawTest`:**
  - a goal while hidden, with the bug reappearing 1 s later inside the mark: no takeover (B1.1);
  - hidden mid-takeover, fade done, then reappearing: the plain bug, with no frozen frame (B1.2);
  - follow changed mid-takeover: cancelled (B1.3);
  - entering place mode, and a size change, mid-takeover: cancelled (S7);
  - a delay shrink that bumps seq while `goalSide` is null: nothing;
  - a `MotionDurationScale` of 2 in the test clock's context: the pop still completes after the mark has ended (B1.5);
  - scale 0 through the effect context: the still path (S8).
- **`GoalTimelineTest`:** at most 3 flash cycles in any 1 s window (B3).
- **`GoalAnimationScreenshotTest`:**
  - PHI (S6);
  - the badge fallback with no logo;
  - a frame with the quick bar open, asserting no glow over the bar (S1);
  - low-end mode: no masks baked (`bakeGlow` never called).
- **`GoalFramesTest`:** record the damage area or GPU time, not just P90 total (S3).
- **`NhlTeamColoursTest`:** "exactly 32 keys equal to the current franchise list" is circular if the list lives in the
  test. Pin it to an independent fixture, such as a recorded NHL standings response or the fake panel's team table.

---

## Nits

- **N1** (GA:134–137). The Standard peak σ is 20, but the mock scales to 40 × 0.547 ≈ **22**. At 22 the edge bound
  still passes: `½·erfc(48/(22√2))·.8` ≈ 1.3 %. Either match the mock or give the reason.
- **N2** (GA:132, 193). The mock's glow is a fixed `rgba(230,30,50)` for *both* teams (template `gGlow`). A
  team-coloured glow follows the owner's "in the scoring team's colours", but record it as a deviation from the mock,
  and EDM glowing orange is new.
- **N3** (GA:106–108). The trimmed logos have ink touching the bitmap edge (`LogoTrim.kt:51–56`). A rotated
  `drawBitmap` on API 25 GL has hard quad edges, so the 550 ms spin shows slight jaggies on square-ish logos. Either
  give the badge a copy with 1 px of transparent padding (one allocation per logo load) or accept it.
- **N4** (GA:107). Give the badge its own `Paint`. Don't set alpha on the shared `bmpPaint`
  (`ScoreBugPainter.kt:77`), which also draws the body logos.
- **N5** (GA:383). At 1.55 × 40 px, a two-digit score is about 74 px wide. It crosses the 48 px digit column
  (`SportsConstants.kt:78`) into the logo and the divider. This is rare in hockey; clamp the pop scale to the column
  for two digits, or accept it.
- **N6** (GA:385–387). The digit is revealed by the **lead's** exit (3 384–3 600), not the panel's: the lead sits under
  the panel and covers the body until 3 384. Drawing the 1.55× state from 3 168 does no harm, because it is hidden
  until then. Correct the wording.
- **N7** (GA:63). At 150 ms, `ease-in-out` over 0–540 gives about 0.17 of the way to .55, so the glow alpha is about
  0.09: low, but not "near zero". This is harmless once S3 bounds the layer.
- **N8** (GA:263–264). "The bitmap is ready before any takeover" is likely but not guaranteed: logo fetches can be slow
  or fail, and a size change re-fetches. The abbreviation fallback covers it, so soften the claim.
- **N9** (GA:296–298). A tying goal in play and a shootout win that both fall inside one missed poll show up as a
  single FINAL/SO change, so neither is celebrated. Accept it and say so.
- **N10**. The white flash and streak 2 cross the white GOAL, dipping PHI to about 2.1 : 1 at the flash peak. This is
  transient and acceptable, but B3's change reduces it too.
- **N11**. `Animatable<Float>.value` boxes on each read. Compose's own animation loop allocates per frame anyway, so
  keep the allocation assertion scoped to `GoalPainter.draw` and `GoalTimeline.sample`, as written.
- **N12**. Update the KDoc at `ScoreBugMachine.kt:26–27` and `SportsContracts.kt:148`, and the `nextWakeAtMono`
  comment "goal-text end" (`ScoreBugMachine.kt:177`).

---

## Checks that passed

- **Tree (GA §1).** `AppRoot.kt:349–378` matches the diagram. `FullScreenHost` sits under the key-sink `Box`, a sibling
  of `VideoLayer`. The bug's `graphicsLayer` (`ScoreBug.kt:209`) is a leaf subtree. The takeover adds only canvas
  operations. Nothing new clips or layers an ancestor of the surface. S3's `clip = true` on the bug's own layer keeps
  that true.
- **Wipes.**
  - `clipRect` plus an anti-aliased `drawRoundRect` reproduces `inset()` on a rounded element, and needs no
    `clipPath`/stencil (the API 25 aliasing concern is correct).
  - The wipe edges snap to whole pixels, which is fine.
  - The intervals match the CSS exactly: lead 0/216/3 384/3 600; panel 108/396/3 168/3 492.
- **`ALPHA_8` masks tinted by the paint colour.** Correct on HWUI GL and on Skia. A software bake with
  `BlurMaskFilter` and `clipPath(DIFFERENCE)` is the `bakeGlass` precedent. Masks are 63 + 111 KB (Standard) and
  79 + 158 KB (Large), as stated. The edge figures (0.7 % / 1.4 %) are correct for the screen-inset case.
- **`setLocalMatrix` avoidance.** The reasoning is correct, and the translate/−dx trick allocates nothing.
- **Timeline vs CSS.** Every keyframe, delay, duration and easing in GA:373–383 matches `template.html:322–343`:
  - easing applies per segment;
  - the pop colour is eased once over 0–100 %, because only 0 % and 100 % specify it;
  - `fill-mode: both` is honoured where the CSS uses it;
  - the flash and pulse have no fill;
  - transform composition order is right: translate-then-scale for letters, and scale·rotate (which commute) for the
    badge.
- **Machine behaviour** traced in `ScoreBugMachine.kt:308–348`:
  - first snapshot: no previous snapshot, so no goal;
  - a correction down: no goal;
  - +2 in one update: one seq;
  - both sides rising: no goal;
  - a reassignment: the side that rose;
  - an OT winner turning FINAL: flagged, because there is no phase check;
  - a goal on the delayed timeline: shown at `max(anchor + delay, received)`;
  - a delay that grows: silent;
  - a delay that shrinks: seq++ with an already expired mark, so `goalSide` is null and the "`goalSide != null`"
    condition correctly drops it.

  The shootout rule (`periodKind == SO || finalKind == SO`) matches what `NhlMapper.kt:52, 73–74` produces.
- **Removing `CentreText.Goal`.** The Sports tab does not reference it: only `SportsText`, `ScoreBugPainter` and
  `ScoreBug` do, and `sports_goal` is used only by `SportsText.kt:76`. The `values` locale is the only one. Nothing
  else breaks beyond the B2 list. The new `sports_desc_goal` can use `TeamLine.name`, which is already the common name
  (`SportsContracts.kt:22–23`).
- **Layout.**
  - Standard: 24 + 64 + 14 = 102, and 380 − 77 − 14 = 289.
  - Large scales by 1.25 against the real 506 W.
  - The badge corners stay inside the rounded end at rest and at the 1.12×/±8° overshoot (the worst case is about
    32 px from the arc centre, inside the radius of 35).
  - Every type size is at least 18: the smallest are the tally at 22 and the pop digit at its 0.9 dip, 36. The 18 px
    floor holds at both sizes.
- **Contrast table.** I recomputed every row with WCAG 2.x sRGB: `GLASS_DARK` = `#090B10` and `GLASS_WHITE_ICE` =
  `#2C2F34` are correct, and every C1–C4, glow choice and pop value matches the doc. The colours themselves still need
  the source check the doc already requires (GA:210–214).
- **Still version.** Cuts only, which matches the mock's `prefers-reduced-motion`. In low-end mode it is cheaper than
  today. It needs the S8 detection fix.
- **Frame cost.** The main-thread estimate is right, and GPU fill is trivial for a Mali-450 or GE8300. With S2 and S3
  fixed, "≤ 6 ms" is plausible. Without them, the RenderThread and full-window costs are unaccounted for. Window
  composition remains the real unknown, correctly listed as a device item.

---

## §11 open questions: recommendations

1. **Shootout winner:** **no takeover** (as proposed). A shootout win is not a goal scored in play, and the bug changes
   to "Final/SO" anyway. If the owner wants *some* acknowledgement, the cheapest honest option is the 900 ms
   **score pop only** on the winner's digit. That needs a `markKind` on the view, so it is an owner call for later, not
   this feature.
2. **† substitutions and PHI:** approve the substitutions **subject to the WP-1 source check**. Keep **PHI orange with
   white**: white on Flyers orange is the team's own look, GOAL passes 3 : 1 by any measure, and the tally repeats the
   score the bug shows a moment later. Record it as an owner exception with the corrected rationale (S6), and put PHI
   in the screenshot set.
3. **Pop reveal at 1.55×:** **accept.** The jump in the mock is visible on a TV. Correct the wording (N6) and clamp
   two-digit scores (N5).
4. **TalkBack live region:** **keep.** The mock itself uses `role="status"`. Implement it as in S9 (a persistent node
   whose text changes, or `announceForAccessibility`) and check it on VoiceView.
5. **Fade layer:** neither `ModulateAlpha` nor "separately". Do **S3 in this feature (WP-3)**: a bug layer bounded to
   the pill plus the margin, with `clip = true`. That one change fixes the glow and shadow cut during fades and bounds
   the per-frame damage. `ModulateAlpha` would fix the cut but leave the full-window damage, and it introduces
   overlapping-alpha artefacts between the glass and the body.
6. **Flash safety:** **not confirmed as specified.** It passes the TV broadcast rule (25 % of the screen) but only
   marginally passes, or fails, the WCAG 2.3.1 general-flash area at common seating for Large. Change it to 3 × 400 ms
   (2.5 Hz), or at least 334 ms per cycle, so it passes on frequency alone (B3).

---

## Summary

**Blockers (3):**
- **B1.** The §5.2 trigger can't be built on today's `ScoreBug` structure:
  - goals that arrive while hidden replay on reappearance;
  - a cancelled takeover freezes mid-wipe;
  - a follow change does not cancel;
  - seqs can collide across follows.

  Specify the `lastSeq` update in composition, a synchronous `takeoverOn` gate, cancel on any change of seq or game,
  and the UI owning the timeline after start.
- **B2.** Commit 0 cannot pass its gate. It must also edit `ScoreBugMachine.kt`, `SportsText.kt`,
  `ScoreBugPainter.kt`, `ScoreBug.kt` and four test files, and declare `GlowMasks`, the pop-colour owner and the
  `drawFrame` composite.
- **B3.** 4 × 300 ms white flashes run at 3.33 Hz, and the area argument is marginal or fails at common seating for
  Large. Use 3 × 400 ms (2.5 Hz).

**Main should-fixes:**
- S1: the glow over the bars and overlays;
- S2: glyph re-rasterisation under animated scale (pre-render GOAL);
- S3: full-window damage (bound and clip the bug's own layer, which also answers Q5);
- S4: aliased hard-stop streaks;
- S5: the composite draw in WP-2;
- S6: the C1 large-text rationale on a TV, and PHI in the screenshots;
- S7: place mode or a size change mid-takeover;
- S8: detecting animations-off through `MotionDurationScale`;
- S9: a reliable live region (VoiceView);
- S10: the test gaps.
