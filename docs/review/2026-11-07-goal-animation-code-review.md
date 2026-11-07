# Goal animation: code review (2026-11-07)

Scope: `git diff acba193..HEAD -- . ':!design' ':!docs'`, which covers commit 0 (`29cba04`), WP-1 (`bd27c6a`), WP-3
(`3f3e129`) and WP-2 (`506d167`), merged on `main` at `489c0e0`. The spec is `docs/design/m6-goal-animation.md`; its
"Lead sign-off" section wins where the two differ. Owner rulings 7–12 are in `docs/decisions/2026-11-05-nhl-score-bug.md`.

The review focused on the seams between the three packages, which were built in parallel against stubs.

The tests were run as part of this review and pass:
- `:core:test` (`GoalTimelineTest`, `ScoreBugMachineTest`);
- `:ui:testDebugUnitTest` (`ScoreBugDrawTest`, `GoalAnimationScreenshotTest`).

Measured values from those runs:
- `GoalPainter.draw` × 300 allocates 608 B;
- the "10" pop is clamped to 1.524 (Standard) and 1.509 (Large);
- the GOAL atlas is 172 × 65 (Standard) and 205 × 80 (Large);
- the EDM glow alpha is .239 at 20 px and .0078 at 56 px.

I also viewed the contact-sheet frames by eye.

**Verdict: no blockers.** The seams line up. There are three should-fixes, all in tests (a missing seam test, an
unasserted low-end rule, and an instrumented test that cannot hit its own gates), plus a list of nits.

---

## Seam checks (verified, no finding unless noted)

### WP-3 → WP-2
- **`GoalHooks.begin`** (`ScoreBug.kt:134`) has the signature `(GoalPainter, Side, TeamColours, Bitmap?, String, Int, Int)`.
  It forwards in order to `GoalPainter.begin(side, colours, badge, abbrev, away, home)` (`GoalPainter.kt:200`).
- **`GoalHooks.bake`** returns `GlowMasks?`, and `bakeGlow()` returns a non-null `GlowMasks`.
- **The `sample` seam** is `GoalSampler.Real` (`ScoreBug.kt:149`). It dispatches to `GoalTimeline.sample` or
  `sampleStill` on `run.still`. Because `GoalSampler` is a `fun interface` with primitive parameters, the call does not
  box.
- **`drawFrame` arguments** (`ScoreBug.kt:388–391`) match `ScoreBugPainter.drawFrame` (`ScoreBugPainter.kt:228–239`).
  `goal` and `g` are non-null only while `takeoverOn && !f.done`. `drawFrame` adds its own guard: a null
  `goal.colours` (a painter that was never begun) or `g.done` draws the plain bug.
- **The order in `drawFrame`** is glow (`drawGlow`) → glass → body with pop → `drawTakeover`, which matches §2.1. The
  pop reads `goal.side`, which `begin` sets.
- **The `large` parameter is inferred, not passed.** `ScoreBug.kt:259–262` does not pass `large`, so `GoalPainter.kt:58`
  infers it from `pillW / s`. That gives 404.8 for Large and 404 for Standard, against a threshold of 404.4. It is
  correct at every density, but fragile (see N1).
- **Pill size and scale are consistent.** Both painters get the same `pillW` and `pillH` (design size × density), and
  `s` = density × 1.25 for Large.
- **The glow masks fill the layer exactly.** The peak margin is `(spread + 3σ) · d` with the Large factor removed,
  which is 74 / 76 design px. That equals `GOAL_LAYER_MARGIN` / `_LARGE` (`M`), so the mask fills the clipped layer
  exactly. The screenshot test asserts `peakMargin <= m`.
- **`glowOn`** (`ScoreBug.kt:301`) is `!lowEnd && glowAllowed(placement, geometry, box, M)`. It is recomputed on every
  composition while a run is active, so a bar opening mid-takeover turns the glow off on the next frame (it is drawn
  from a plain field, and the per-frame driver read keeps the node redrawing).

### WP-1 ↔ WP-3
- **The first-snapshot rule with a monotonic `goalSeq`.** A new game (`real.gameId != run.gameId`) or an unseen run
  (`!run.seen`) adopts `lastSeq = goalSeq` and does not start a takeover (`ScoreBug.kt:281–284`).
  - On a re-follow of the same game, `ScoreBugMachine.reset()` clears `shown` (`ScoreBugMachine.kt:399`). The first
    snapshot after the re-follow therefore cannot bump `goalSeq`, and the monotonic seq never replays.
  - `goalSeqDoesNotResetAcrossAReFollow` covers this on the machine side.
- **WP-3's `run.seen = false` when the view goes null** (`ScoreBug.kt:279`) is stricter than §5.2 step 1, which does
  not reset. A goal would be swallowed only if the same game's view came back from null carrying a fresh seq. I checked
  every null path in `ScoreBugMachine.view()` (lines 202–210), and none of them can do that:
  - the stale hide comes after `HIDE_AFTER_MS` = 300 s, but goals are flagged only inside `GOAL_FRESH_MS` (about 80 s,
    line 412);
  - SCHEDULED/PRE comes before any non-null view of the game.

  The reset is harmless, but it is undocumented (N6).
- **The 4.5 s mark and the 4.5 s takeover are independent, as B1.5 requires.**
  - When the mark ends, a recomposition happens with the same seq, so the takeover is not cancelled.
  - With `MotionDurationScale` = 2, the takeover outlives the mark (this is tested).
- **The shootout.** The winner's +1 on the FINAL snapshot bumps the seq like any other rise (`ScoreBugMachine.kt:337–345`).
  The UI needs no special case.
- **`gameId`** is set on both view builders (`ScoreBugMachine.kt:229` and `:243`).

### Timeline vs driver
- **The animated path covers the whole timeline.** `TAKEOVER_TOTAL_MS` = `GOAL_TAKEOVER_MS + GOAL_POP_MS` = 4 500
  (`ScoreBug.kt:408`), which equals `GoalTimeline.END`. `done` is set at `t >= END` (`GoalTimeline.kt:302`). The last
  animated frame is therefore the plain bug, and the `finally` then clears `takeoverOn`.
- **The still path works.** It runs `snapTo(0)` (frame A), then `delay(3 600)` and `snapTo(3 600)` (frame B: `popOn`,
  scale 1, mix 0, no panel and no lead), then `delay(900)` and the `finally`.
  - That is three draws, using `sampleStill(0)` and `sampleStill(3 600)`.
  - The still path never reaches `done`, and the `finally` ends it.

### Threading and resources
- **The bake is safe off the main thread.** It runs on `Dispatchers.Default` (`ScoreBug.kt:333`). `bakeGlow`/`bakeMask`
  read only immutable state (`pillW`, `pillH`, `radius`, `d`, `large`, the never-mutated `pill` rect) and allocate
  their own `Canvas` and `Paint`.
- **The result is published on the main thread** into snapshot state. There is no shared mutable state with
  `begin()`/`draw`, so the bake cannot race the draw.
- **A failed bake means no glow.** On a size change, the old effect is cancelled and its result discarded.
- **Bitmaps are not recycled, but nothing leaks.** The atlas, the masks and the badge copy are never `recycle()`d, but
  nothing holds them after the painter is dropped (it is remembered on `s` and the size). GC reclaims them. The logo
  request sets `allowHardware(false)` (`TeamLogo.kt:34`), so the software `Canvas(copy).drawBitmap(badge)` in `begin`
  (`GoalPainter.kt:219`) cannot hit a HARDWARE bitmap.

### Allocation and idle
- **There is no idle work.** With no takeover, the draw lambda reads `takeoverOn` (false), `glowMasks` and
  `logoWaited`, and draws the plain bug only when invalidated. `driver.value` is read only while `takeoverOn`, so
  nothing redraws per frame at rest.
- **The goal path does not allocate.**
  - `GoalPainter.drawGlow` and `drawTakeover` are allocation-free (608 B per 300 calls, from Robolectric shadows).
  - `withClip`, `withTranslation` and `withScale` are inline.
  - `lerpColour` is integer arithmetic.
  - `clampPop` makes one `measureText` call.
  - The one remaining allocation (`Animatable.value` boxing) is accepted by N11.
- **The body allocates** (N2).

### Rules
- **No real-time blur.** `BlurMaskFilter` is used only on the software bake canvas.
- **The layer rule holds.** `graphicsLayer { alpha; clip = true }` sits on the bug's own leaf `Box`, which is not an
  ancestor of the `SurfaceView`.
- **1 design px = width ÷ 1920.** Every size goes through `LocalDensity` under `BoxDesignScale`, times `K.*`.
- **Type is at least 18 px:** GOAL is 52/65, the tally 22/28 and the badge fallback 24/30.
- **API 25 is fine.** Every API used is available there: `setFontFeatureSettings` (21), the float `drawRoundRect` (21),
  `ALPHA_8`, `ResourcesCompat.getFont`. `FrameMetrics` in the androidTest needs 24.

### Edge cases
- **Low-end:** the still path, the shadowless glass, no bake (`ScoreBug.kt:331`) and `glowOn` false.
- **Animations off:** the still path through `motionOff()`. With masks present, glow alpha 0 draws nothing
  (`GoalPainter.kt:254–256`).
- **Hidden, place mode, a size change or a follow change mid-takeover:** a synchronous cancel to the plain bug, and no
  replay. A view going null is also handled (`ScoreBug.kt:277–279`).
- **A logo not loaded:** the white abbreviation, chosen in `begin`, never swapped mid-takeover. It is tested in
  screenshots.
- **Large vs Standard:** the painter, the masks, `M` and the glow margin all follow `holder.large`. The screenshot
  rig covers both.
- **The pop clamp for two digits** respects `GOAL_POP_MAX_W` (see N5).

---

## Should-fix

### S1. No test exercises the real WP-3 → WP-2 path; the seam is guarded only by reading
- **Where:** `ui/src/test/.../ScoreBugDrawTest.kt:160–174`. `begin = { _, _, _, _, _, _, _ -> }`, `bake = { null }`,
  `glowAllowed = { … true }` and a fixed `colours`.
- **What happens:** the comment still calls these "the commit-0 stubs", but WP-1 and WP-2 are now merged. Because
  `begin` is a no-op, `goalPainter.colours` stays null. Any draw of the node would therefore be the plain bug
  (`ScoreBugPainter.kt:241`).
- **Why it matters:** Robolectric runs no draw pass (the test's own header). Nothing in the suite would catch a seam
  regression such as:
  - `begin` not called, or called after `takeoverOn = true`;
  - the wrong side or tally passed;
  - `goal`/`g` wired from the wrong state;
  - `glowOn` never set.

  `GoalAnimationScreenshotTest` drives `drawFrame` directly with hand-built `TeamColours`
  (`GoalAnimationScreenshotTest.kt:54–59`), not with `NhlTeamColours.of` or the composable.
- **Failure scenario:** a later edit swaps `run.away` and `run.home` in the `hooks.begin` call (`ScoreBug.kt:310`), or
  moves `takeoverOn.value = true` above `hooks.begin`. Every test stays green, and the device shows the wrong tally or a
  frame drawn with the previous goal's colours.
- **Fix:**
  - Add one `ScoreBugDrawTest` case on `GoalHooks.Default` that asserts `goalPainter.side`, `colours` (=
    `NhlTeamColours.of("CGY")`) and the tally after a goal. Use the probe to hand out the painter.
  - Or move the `drawBehind` body into an internal function taking a `Canvas`, and call it from a native-graphics
    Robolectric test with real hooks at a sampled `driver` value.
  - Update the stale comment at lines 160–162.

### S2. "In low-end mode, `bakeGlow` is never called" (§8.2) is not asserted anywhere
- **Where:**
  - `ScoreBugDrawTest.kt:387–393` (`lowEndModeUsesTheStillPathToo`) asserts only `still`, and its `bake` stub ignores
    calls.
  - `GoalAnimationScreenshotTest.kt:393` says "WP-3 never calls bakeGlow there" but only passes `glow = null` itself.
- **Failure scenario:** someone drops the `if (lowEnd) return@LaunchedEffect` guard (`ScoreBug.kt:331`). Low-end
  devices (Fire TV Stick, API 25) then bake about 240 KB of masks on entering FULL, and no test fails. `glowOn` stays
  false, so nothing visible changes either.
- **Fix:** count calls in the `bake` stub and assert 0 with `lowEnd = true`, and 1 with `lowEnd = false`.

### S3. `GoalFramesTest` cannot reliably meet its own gates: the 5 s window is not anchored to the takeover
- **Where:**
  - `app/src/androidTest/.../GoalFramesTest.kt:22–25` tells the operator to run it "within about 4 s of the CGY goal".
  - Measuring starts only after `fullOn(10)`, `awaitController` and `awaitPlayer(…, 15_000)` (lines 41–43), whose
    duration is unknown.
  - The idle phase begins a fixed 6 s after measuring starts (lines 68–76).
- **Failure scenario:** the goal lands 3 s into the window. Only about 2 s of takeover (about 120 frames) is
  recorded, so the emulator gate "≥ 200 frames" fails. The takeover then runs on into the idle phase, so the
  "≤ 1 frame per 10 s" gate fails as well, with no regression in the app. If the goal lands before the window, the P90
  is measured on an idle window and passes vacuously.
- **Fix:**
  - Wait for the takeover to start before opening the 5 s window, for example by polling
    `GuideDebugCounters.count(DRAWS_SCORE_BUG)` for a burst, or by the first frame-metrics callback after a quiet
    period.
  - Start the idle window from the takeover's measured end, not a fixed offset.
  - Alternatively, re-anchor the fake panel from the test (`POST /admin/sports`) so the goal lands a known time after
    the window opens.

---

## Nits

- **N1. Pass `large` explicitly.**
  - **Where:** `ScoreBug.kt:259–262` builds `GoalPainter` without `large = shownLarge`, and `GoalPainter.kt:58` infers it
    from `pillW / s > 404.4`.
  - **Risk:** if `SCORE_LARGE_W` or the scale ever changes so that 506/1.25 lands on the wrong side of 404.4, Large
    silently gets the Standard glow table. The caller already knows the answer.
- **N2. `drawBody` allocates `Paint.FontMetrics` on every draw, and the "allocates nothing" KDoc is false.**
  - **Where:** `ScoreBugPainter.kt:315–316` and `:322` call `p.fontMetrics` / `period.fontMetrics`, and
    `getFontMetrics()` returns a new object.
  - **Effect:** a live view (clock on top, a period line) makes 2 allocations per frame, about 540 small objects per
    takeover. The code predates this feature (`0a4f7f8`), but it now runs at 60 Hz for 4.5 s.
  - **Contradicted KDocs:** `ScoreBugPainter.kt:52` and `:226`.
  - **Fix:** use a cached `FontMetrics` with `getFontMetrics(fm)`, or precompute the baselines in `frame()`.
- **N3. Main-thread setup and a glow bake on every FULL entry, even with no followed game.**
  - **Where:** `ScoreBug.kt:258–263` and `:330–339`.
  - **What happens:** the Barlow typeface load (the full 110 KB TTF, not subset) and the GOAL atlas render run in
    composition. The glow bake runs on every FULL entry with Show Sports on.
  - **Fix:** measure on the Fire TV Stick. If it shows up on the FULL-entry frame, create both lazily on the first real
    view, and subset the font (§9 already allows it).
- **N4. The glow snaps back abruptly when a bar closes mid-takeover.**
  - **What happens:** `run.glowOn` turns true again (`ScoreBug.kt:301`), and the glow reappears at its current
    timeline alpha (up to .8 at the peak) in one frame.
  - **Spec:** §2.4 specifies only turning it off.
  - **Fix:** accept and record this, or latch `glowOn` off for the rest of the run.
- **N5. The pop clamp barely engages, against the spec's "about 1.2×".**
  - **Measured:** "10" pops at 1.524 (Standard) and 1.509 (Large), against 1.55 unclamped.
  - **Assessment:** the code implements the stated rule correctly: width ≤ 48 + 2 × 8 (`ScoreBugPainter.kt:267–271`).
    The §2.3 estimate ("about 1.2×") is wrong, not the code. At 1.52 the popped "10" spans the full 64 px, touching
    the logo slot edge, with 4 px left before the divider. The two-digit frame looks fine by eye
    (`ui/build/goal-animation/goal-two-digit-pop-std.png`).
  - **Fix:** correct the doc, or tighten the constant if the owner wants more clearance.
- **N6. Undocumented deviations from §5.2 and §2.4.**
  - **§5.2:** `run.seen = false` on a null view (`ScoreBug.kt:279`) is an extra rule. It is harmless (see the seam
    checks); record it in §5.2.
  - **§2.4:** the Large low glow is 2.5 / 12.5 (`GoalPainter.kt:167–168`, per §10's "× 1.25"), while the table says
    3 / 12 and a margin of 39. The code's margin is 40. Both are inside `M`. Align the table.
- **N7. `sports_goal_word` is unused.** It is declared at `ui/src/main/res/values/strings_sports.xml:42`, but the painter
  draws the constant `WORD = "GOAL"` (`GoalPainter.kt:323`). Lint will flag UnusedResources (a warning). Use the
  resource in `GoalPainter`'s construction, or delete it.
- **N8. A duplicate margin computation.** `ScoreBug.kt:270` (`margin`) and `:364` (`m`) compute the same value. Use one.
- **N9. `GoalPainter.draw` is used only by tests.** Production calls `drawGlow` and `drawTakeover` through `drawFrame`
  (`GoalPainter.kt:245–248`). Either note it as a test convenience or make `drawFrame` the thing the allocation test
  measures. The second option would also have caught N2.
- **N10. Missing trigger test: the follow stopped (view → null) mid-takeover.** Only "follow changed" is tested
  (`ScoreBugDrawTest.kt:271–282`). The code path is `ScoreBug.kt:277–279`.
- **N11. The screenshot test duplicates the colour table.** It hand-builds the four teams' `TeamColours`
  (`GoalAnimationScreenshotTest.kt:54–59`) instead of calling `NhlTeamColours.of`. `NhlTeamColoursTest` pins `glow` and
  `pop` for the same four teams, so drift is partly caught, but the screenshot comment "WP-1 owns `NhlTeamColours`"
  is now stale. Switch to the real table.

---

## Summary

**Blockers:** none.

**Should-fix:**
1. **S1:** no test runs the real WP-3 → WP-2 path. `ScoreBugDrawTest` stubs `begin`, `bake`, `glowAllowed` and the
   colours, and Robolectric draws nothing, so seam regressions such as a swapped tally, a `begin` ordering bug or a
   wrong `glowOn` would pass.
2. **S2:** "`bakeGlow` is never called in low-end mode" is not asserted anywhere.
3. **S3:** `GoalFramesTest`'s 5 s window and idle window are not anchored to the takeover, so its gates fail or pass
   by timing, not by performance.

**Nits:**
- N1: pass `large` explicitly.
- N2: `FontMetrics` allocation per frame in `drawBody`.
- N3: main-thread font and atlas setup, and the bake on every FULL entry.
- N4: the glow snaps back when a bar closes.
- N5: the "about 1.2×" pop doc is wrong; the clamp is 1.52.
- N6: undocumented `run.seen` reset; the Large low-glow table mismatch.
- N7: unused `sports_goal_word`.
- N8: duplicate margin.
- N9: `GoalPainter.draw` is test-only.
- N10: no "follow stopped mid-takeover" test.
- N11: the screenshot test duplicates the colour table.
