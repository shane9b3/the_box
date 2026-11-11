# Stacked score bug style: code review C (logic, state, wiring)

Reviewer C, 2026-11-10. Scope: `6d43501..af08859` (commits `bd52493`, `72aa3b7`, `fe35ddf`, `b4dc26a`), restricted to the
core sports types, settings and quick-bar wiring, the settings store, `ScoreBug.kt`'s seam, the runner (`StackedBug.kt`),
`FullScreenHost`, the VM and effects, the fake panel, and the tests for all of these. The painters (`StackedPainter`,
`StackedGoalPainter`, `NineSlice`) and their draw cost belong to reviewer B. They are read here only where the runner drives
them. This was a read-only review: nothing was built or run, because the gate build was running in this checkout.

**Verdict: no blockers.** The Pill path is unchanged where it matters, and the S1 rules are carried over carefully. Four
should-fix items are listed below. Two of them are visible glitches in the runner, one is a test leaving device state
behind, and one is a flaky Pill test that the gate keeps hitting.

---

## What was checked and holds

- **The Pill is untouched.** `ScoreBugWith`, `NflBug` and the Pill painters have no diff. `ScoreBug.kt` changes only in
  the seam and in the `LiveText` move, which is a byte-identical body made `internal`.
  - With Pill prefs, `FullScreenHost` hands down `style = PILL`, so `placed` is the same instance as before.
  - `cornerBox(corner, size, league, style)` computes exactly what the old overload did for PILL and NFL.
  - `NflBug` always gets `style = PILL`, because `ScoreBug` copies the placement whenever the league is NFL.
  - `GoalSpec.CLASSIC` is compared against a frozen copy, not against itself. `LegacyGoalTimeline.kt` was diffed against
    `GoalTimeline.kt` at `6d43501`. The only differences are inlined constants (same values), a private copy of the bezier,
    and the dropped `GoalFrame` class.
  - `GoalSpecTest` compares bitwise on the 0.25-ms grid plus NaN and +∞. `StackedSeamTest.thePillsSamplerIsTheClassicSpec`
    pins `GoalSampler.Real`.
  - One small difference is listed under N1.
- **The fake panel golden.** It was re-captured, so from now on it compares against itself. I checked the re-capture once
  by hand: the old and new golden files were decoded and their bodies compared with `sog` removed. They are identical.
  `sog` changed only in the FINAL/SO game's bodies (CGY 25 → 21, EDM 27 → 21: the shootout winner's +1 and period 5 no
  longer count). The default timeline is otherwise byte-identical.
- **Leaf layer.** `graphicsLayer { alpha; clip = true }` (`StackedBug.kt:468`) sits on the bug's own `Box`. That `Box`'s
  only children are the draw `Spacer` and the live-region `Spacer`, and it lives where the Pill's identical layer lives
  (`ScoreBug.kt:762`). It is never an ancestor of the `SurfaceView`.
- **Glow and shadow bakes off the main thread.**
  - `bakeMask` and `NineSlice(s, large)` build only local `Bitmap`, `Canvas` and `Paint` objects. Nothing they share is
    mutated while the main thread draws.
  - The cache (`glowKeys`, `glowMasks`, `baking`) is touched only on the main thread: the `rememberCoroutineScope` body
    and composition.
  - A bake that lands for a replaced painter is dropped (`goalPainter === p`).
  - Nothing is recycled, so no bitmap can be freed while in use.
- **State is read in draw, not composition.** Every driver, `takeoverOn`, `held`, `epoch`, `glowEpoch`, `shadow` and
  `logoWaited` is read in `fillArgs()` only. The composition reads `fadeDone` (as the Pill does) and plain fields. The
  per-frame draw allocates nothing: `BodyMerge` allocates once per change of inputs, and `sampleForDraw` is an array loop.
- **`StackedTargets.of` against §8 and the rulings.**
  - The clock is `clockSecs` only (ruling 6). PP time is `powerPlay.secondsRemaining` only.
  - A PP carried into the intermission keeps the frozen bar and the clock column reads period / INT (ruling 19, B3).
  - Stale and waiting views get no PP (the machine nulls `powerPlay`).
  - Nothing is drawn before puck drop (`view()` is null).
  - A final never carries a PP bar (`brk`).
  - 6-on-4 and 4-on-3 in OT are plain `POWER_PLAY`.
  - SOG out of `0..199` is dropped in the mapper.
  - No reachable view gives a bar time and a clock that come from different snapshots.
- **Colours.** All 32 teams, the fallback, the swapped variants, all 32 × 31 ordered pairs, fallback × every team, and two
  unknown teams are asserted. `uppercase()` is locale-invariant.
- **Settings gating.** `followed?.league ?: browsed league ?: NHL` matches §1.1. `ScoreBugPlayerTest` covers all five
  cases and the toggle with the sheet staying open.
- **Simple budget.** `STYLE` is ADVANCED, `SimpleRowCountTest` is untouched, and `SportsStyleRowTest` pins it.
- **Five-key depth (18 → 19) is sound.** The Style row sits between Delay and the games, so the Corner row is one more Up
  from the game the sheet opens on. Reaching `setScoreCorner` grew from 17 to 18 keys under Up/Down and from 18 to 19
  under ZAP / CHANNEL_LIST. The Style row itself is one Up from the games. One extra press to Corner and Delay is the cost
  of the row's agreed place; no row became unreachable. If Corner is used more than Style, consider putting Style after
  Stop (N8).
- **Text.** Every new user-facing string uses American spelling ("Score bug style", "Shots on goal: …"). The stale words
  ("No update", "NO UPDATE") are a state, not an error, and they match the Pill's existing standing wording.

---

## Blockers

None.

---

## Should-fix

### S1. Unfollowing, or the 5-minute stale hide, collapses the box and swaps in fallback colours during the fade-out

`ui/src/main/kotlin/app/thebox/ui/sports/StackedBug.kt:225–228` and `:281–284`

**Failing scenario.** Follow EDM on a power play: SOG open, bar open, EDM stripe orange. Then press Stop following, or let
the feed go stale for 5 minutes so that `view()` returns null. On the composition where `bug == null`, two things happen:

- `run.reset()` → `park()` sets `want = false` and kind `STILL` on every track. The 150-ms fade therefore draws the box
  snapped to the bare rows: 243 wide, with no SOG, no clock and no bar.
- `pair = remember(awayAbbrev, homeAbbrev)` is keyed on `bug?.away?.abbrev ?: ""`. Both are now `""`, so the rows'
  stripes switch to `NhlStackedColours.pair(null, null)` (the fallback colours) for the whole fade.

This breaks rule 3 ("A hidden bug's tracks freeze where they are and fade with the bug"). The Pill has neither glitch:
its colours come from the held view. No test covers it, because `rule3…` hides through `screenOff`, never through
`bug = null`.

**Fix.**
- Key `pair` on the shown view's abbreviations (`holder.lastView` / `shownView`), not on `bug`.
- On `real == null`, set `run.hidden = true` and cancel the takeover, but defer `run.reset()` until `fadeDone` (or until
  the next follow's first view, which already resets).
- Add a `StackedDrawTest` case: on a PP, set `bug = null`; mid-fade the sampled widths are unchanged and
  `args().colours` is the game's pair.

### S2. A back-to-back goal shrinks the pinned box mid-takeover (rule 5's close breaks B4's fixed box)

`StackedBug.kt:799–806` (`covered()` → `kickBar(null, …)` while pinned), against `:767–777` (the pin) and §2.3 / §6 B4

**Failing scenario.** EDM scores a power-play goal: the bar is cut to "EDM POWER PLAY GOAL" and the box is pinned at
391.5 × 141. Less than 3.6 s later CGY scores. At CGY's lead-covered instant, `covered()` takes rule 5's "closes" branch
and starts a real `CLOSE` on the bar track. `barTrack.pinSample` is false, because the bar was at rest when the first pin
started. The bar height therefore animates from 33 to 0 over 400 ms in the middle of the takeover.

The consequences:
- The box changes height, which B4 rules out: "From then until 3 600 ms no layout track starts a transition … the box
  size is therefore fixed".
- The glow masks were baked for the 141-tall box (`pinH`). They keep drawing around space the bar no longer fills: a
  33-px band of glow with nothing inside it.
- `rule5ABackToBackGoalClosesAnEarlierPowerPlayGoal` asserts the `CLOSE`, so the test pins the bug in place.

**Fix.** While pinned, rule 5's close should hide only the words. Two ways:
- start a text-only fade (a `SWAP` into an empty `BarContent`), keep `barWant` non-null, and let the 3 600 release close
  the bar through the normal `step()`;
- or simply hold "EDM POWER PLAY GOAL" until the release.

Then change the test to assert that the bar height stays at `STACKED_BAR_H` until the release, and that the close happens
there. The design's two sentences (rule 5's "or closes" and B4's "no layout track starts a transition") need one line in
the design reconciling them.

### S3. `StackedFramesTest` leaves the device on Stacked, so later Pill instrumented tests run against the wrong bug

`app/src/androidTest/kotlin/app/thebox/player/StackedFramesTest.kt:72` and `:131–145`

**Failing scenario.** `chooseStacked()` toggles the persisted `SportsPrefs.nhlStyle` to STACKED through the sheet and never
restores it. There is no orchestrator `clearPackageData`, so app data persists between instrumented tests. A later
`GoalFramesTest` in the same session waits on `COMPOSITIONS_SCORE_BUG` / `DRAWS_SCORE_BUG`, and those never move while
Stacked is drawn. It then skips or fails, depending on its assumes. Hand runs for the §10.4 device items will hit this.

**Fix.** Remember the style the sheet showed, and restore it in a `finally` block (toggle back through the sheet, or write
`SportsPrefs` through the test's settings hook). Likewise, have `GoalFramesTest` force Pill at start.

### S4. The flaky `ScoreBugDrawTest.aMinuteWithoutASnapshotRecomposesRebuildsAndDrawsNothing`: the cause and a hardening

`ui/src/test/kotlin/app/thebox/ui/sports/ScoreBugDrawTest.kt:84, 95–121`

**Cause.**
- The test hosts `StableScoreBug` with production hooks, so `GoalHooks.background = Dispatchers.Default`.
- `start()` advances 2 000 ms of **virtual** main-clock time. The off-thread setup (Barlow load, GOAL atlas, glow bake,
  `PowerPlayPainter` and its size bakes, label masks) runs in **wall** time on real threads.
- Under the parallel gate's load, that setup finishes after `registerApplyObserver` is installed. The publication
  (`glowMasks.value = …`, `pr.readyChanged()`, the label and halo publishes) is then counted as an apply:
  `applies > 0`, or a recomposition.

**Do the new tests share it? No.**
- `StackedDrawTest`, `StackedBoundsTest` and `StackedLogoTest` build hooks through `StackedRunFixtures.hooks` with
  `background = EmptyCoroutineContext`, so every bake runs on the test thread inside the virtual clock.
- `StackedSeamTest` uses production hooks through `ScoreBug(...)`, but it asserts only composition counters (`>= 2`, `== 0`
  for the other style) and bounds, not apply counts. It is not timing-sensitive in the same way.

**Hardening, either of these.**
- (a) Host `ScoreBugWith(..., GoalHooks(background = EmptyCoroutineContext))` directly in that test, as
  `StackedSeamTest.thePillsTakeoverLengthIsTheClassicEnd` already does.
- (b) Keep the production path, but after `start()` wait in **real** time until the setup has published, then take the
  baselines. Use `rule.waitUntil(5_000) { probe.pp?.labelBakes ?: 0 > 0 }` or an equivalent "setup done" probe flag.

Option (a) is the smaller change. If you take it, add one separate test that keeps `Dispatchers.Default` and asserts only
that the setup eventually publishes. That covers the off-thread path, which, after (a), no UI test would exercise (N6).

---

## Later

- **N1. The Pill now recomposes on a SOG-only change.** `TeamLine.sog` (`SportsContracts.kt:40`) is part of `TeamLine`
  equality. A stat correction during an intermission (no clock) used to give an equal view, which the StateFlow dropped.
  It now gives a new view, so the Pill recomposes once and rebuilds its frame. This stays within the one-view-per-snapshot
  budget and draws nothing different. It is not literally "bit-for-bit as before", so record it in the design's §2.1 table.
- **N2. `release(snap = true)` runs `step()` inside composition.** This happens at `StackedBug.kt:279` on a size change, new
  game or place mode while pinned, and in `release()` at `:822–830`. `play()` then reads `t.driver.value` with
  observation on and calls `snapTo` from composition. The cost is one extra `StackedBug` recomposition on the next driver
  change, plus a side effect in composition. `pin()` already wraps its `step()` in `Snapshot.withoutReadObservation`. Do
  the same here, or set `snapPending` and let the `SideEffect` step.
- **N3. "Hidden freezes" is only half true.** When `box == null`, `step()` returns early (`:745`), but transitions already
  running keep animating through the 150-ms fade until `park()`. This is harmless and matches the Pill, but the rule-3
  KDoc overstates it.
- **N4. A bar open during a close, with a different key, swaps colour mid-slide.** At `kickBar` `:860–864`, `old == null`
  makes it an `OPEN`. `fill()` immediately replaces the half-closed bar's words and background, for example EDM orange to
  CGY red at 50 % height. When the bar is not yet closed (`!scratch.barClosed`), treating this as a `CHANGE` from the old
  content would match §7.1.
- **N5. Wasted glow bake on a size change.**
  - The `SideEffect` snap completes synchronously, so `atRest()` (`:975–983`) asks for a glow at the **new** `s` on the
    **old** `goalPainter`.
  - `LaunchedEffect(s, …)` has not yet called `setGoalPainter(null)` at that point.
  - The bake is dropped on arrival, but it costs a full-size off-thread bake.
  - Fix: guard `requestGlow` on `p.s == run.s`, or clear `goalPainter` in composition when `s` changes.
- **N6. The off-thread races are untested.** Every Stacked UI test runs with `background = EmptyCoroutineContext`. Nothing
  exercises a bake landing after a size change, a painter swap, or a hide. Add one `StandardTestDispatcher`-backed case
  for the `goalPainter === p` drops.
- **N7. Memory held after an unfollow.** `StackedRun` keeps up to two glow-mask pairs, and the holder keeps the painters,
  for as long as `StackedBug` stays composed after a follow ends. That is about 0.6 MB at 1080p and about 2.5 MB at 4K.
  The Pill does the same. Clear the glow cache in `reset()`.
- **N8. Style row position.** See the five-key note above: Corner and Delay are now one more Up from the games. If the
  owner uses Corner more than Style, put Style after Stop.
- **N9. `coerceInputValues` is global.** `RoomSettingsStore.kt:373` now also applies to `ParentalPolicy` and every other
  domain.
  - Restoring a **newer** build's backup into this build used to keep the device's parental document whole (import
    "unreadable; kept"). It now imports that document with any unknown `LockedInGuide` / `StayUnlocked` name coerced to
    the default.
  - The default is the less strict choice in both cases (`SHOW_WITH_LOCK`, `UNTIL_APP_CLOSES`).
  - Reaching this takes a downgrade-restore, so it is unlikely. Add a `RoomSettingsStoreCoerceTest` case that pins the
    parental behaviour you want, or use a non-coercing `Json` for that domain.
  - Separately, the core test fixture `SETTINGS_JSON` (`core/src/testFixtures/.../FakeSettings.kt:26`) lacks
    `coerceInputValues`. `FakeSettingsStore` therefore no longer decodes like the real store. Add the flag there too.
- **N10. A doc comment says something is unreachable when it is not.** `StackedLayout.kt:89` says a dimmed `Final` is
  not reachable. It is reachable: following an already-final game with Delay > 0 gives a **waiting** view (`shown ==
  null`) with `top = Final`. The bar then reads "DELAYED" (dimmed) over a finished game for up to the delay, and then
  swaps to "FINAL". That is acceptable; fix the comment and the §8 row "Stale / waiting at final cannot happen".
- **N11. Place mode with a follow draws the real view, not the sample.** `StackedBug.kt:146` and the test
  `rule4PlaceModeWithAFollowRestsOnTheView…` do this, unlike §4.3 and rule 4's "shows the sample". It matches the Pill's
  code (`view = bug ?: sample`), so this is a design-text fix, not a code fix.
- **N12. Fake panel realism.** In `--sports-stacked`, the 1st-period "end" window (last 8 s, game clock at 0, stopped)
  overlaps the 1451 situation window, whose PP seconds keep counting 13 → 5 at 1 s/s (`sports.py:383–385` with `:337–343`).
  Its 2nd intermission also carries a 1541 PP that did not exist at the end of the 2nd period. Neither breaks a test, but
  the hand pass will see a PP clock ticking under "END" and a PP appearing from nowhere at the break. Freeze `sit` seconds
  inside an `end` window, and start the 2nd INT's PP from a window that runs to the end of the 2nd.
- **N13. Tests that would pass with the feature broken.**
  - `StackedSeamTest.stackedNeverComposesThePill` claims that no `GoalPainter`, `ScoreBugPainter` or `PowerPlayPainter` is
    built. It checks only the Pill's **composition** counter. Count painter constructions, or probe `pr.painter == null`.
  - `everyStateEndToEndThroughTheMachine` asserts rest states after 2 s per step, so it would pass if every transition
    were a snap. `StackedTimelineTest` covers the shapes, but nothing end-to-end checks that a PP **animates** open after
    the first view (rule 2 snaps only the first). One `moves()` assertion after the PP push would close that.

---

## Fix pass (2026-11-11)

Each finding was checked in the code at `af08859` before it was fixed. None was rejected.

| ID | Disposition |
|---|---|
| S1 | **Fixed.** `StackedBug.kt`: the colour pair and the bar's names are keyed on the **shown** view (`view ?: holder.lastView`, never the place-mode sample), so the fade keeps the game's colours. On `real == null` the takeover is cancelled and `holder.resetPending` is set; `run.reset()` runs once the fade is done (or at once in place mode, or when the next follow's first view resets). Test `StackedDrawTest.rule3AFollowThatEndsFadesWithItsLayoutAndColoursThenResets`: on a PP, `bug = null`; at 16 and 96 ms into the fade the layout is the PP's, the words are "EDM POWER PLAY 1:19", `args().colours` is the same pair; after the fade the run is reset. Rule 3's KDoc and design §2.3 updated. |
| S2 | **Fixed** (lead ruling). `StackedRun.covered()`: while pinned, rule 5's "close" calls the new `hideBarWords()`: a SWAP into an empty content (the key stays), so the bar keeps its height; the 3 600 release closes it through the normal `step()`. The pinned box and its glow masks are unchanged. Test rewritten as `rule5ABackToBackGoalHidesAnEarlierPowerPlayGoalsWordsAndClosesTheBarAtTheRelease`: SWAP at CGY's lead-covered instant, bar height, box width and glow masks unchanged every 250 ms until 3 550, no CLOSE during the pin, the CLOSE at the release (driver ≈ 3 600). Design §2.3 rule 5 and §7.2 carry the reconciling line. |
| S3 | **Fixed.** New `PlayerTestBase.setNhlStyle(want)` (returns the style the sheet showed). `StackedFramesTest` sets Stacked, measures in a `try`, and restores the previous style in `finally`; `GoalFramesTest` forces Pill at start. Compile-checked only (`:app:compileDebugAndroidTestKotlin`); not run (emulator in use). |
| S4 | **Fixed, option (a) through a seam.** The flaky test hosts the real `StableScoreBug`, which has no hooks parameter, so `ScoreBug` now reads the Pill's hooks from `LocalGoalHooks` (default `GoalHooks.Default`: production unchanged). `ScoreBugDrawTest.render` provides `GoalHooks(background = EmptyCoroutineContext)`. All four `StableScoreBug` tests there (the two minute tests, `eachNewSnapshot…`, `anEqualReEmission…`, `aHostRecomposition…`) took baselines after `start()` and shared the weakness; all now host this way. New `theOffThreadSetupStillPublishes` keeps `Dispatchers.Default` and waits in real time for the labels to publish (covers N6 in part). The NFL seam tests assert only ≥ counts and the other style's zero, so they do not share it. |
| N1 | **Doc.** Recorded in design §2.1's `TeamLine` row. |
| N2 | **Fixed.** `release()` runs its `step()` under `Snapshot.withoutReadObservation`. |
| N3 | **Doc.** Rule 3's KDoc and design §2.3 now say running transitions finish under the fade. |
| N4 | **Fixed.** `kickBar`: a bar asked open while still closing (CLOSE, not yet closed, words present) becomes a CHANGE (or SWAP for the same look) from the closing words, so the colour never swaps mid-slide. |
| N5 | **Fixed.** `atRest()` returns unless `goalPainter?.s == s`. |
| N6 | **Partly.** `theOffThreadSetupStillPublishes` exercises the Pill's off-thread path. **Left:** a `StandardTestDispatcher` case for Stacked's `goalPainter === p` drops. |
| N7 | **Fixed.** `StackedRun.dropGlow()` clears the glow cache when the deferred reset runs after a follow ends (not on a game change, where the same boxes are re-used). The painters are still held, as the Pill holds its own. |
| N8 | **Left** for the owner (Style row position). |
| N9 | **Fixed** (lead ruling). `SettingsDomain.coerce` (default true); `ParentalDomain` sets false. `RoomSettingsStore.STRICT_JSON` / `jsonFor(domain)` decode it with no coercion on `decode` and `importSlotLocked`; the unregistered-slot import checks a non-coerced domain strictly before storing the raw JSON and keeps the device's row if unreadable. `RoomSettingsStoreCoerceTest`: three new tests (an unknown `lockedInGuide` and an unknown `stayUnlocked`, `replace` false and true, registered and unregistered: the device's policy and its row are kept whole; a readable parental document still restores on both paths). The core fixture `SETTINGS_JSON` gains `coerceInputValues`; `FakeSettingsStore` decodes a non-coerced domain with `SETTINGS_JSON_STRICT` and skips an unreadable document, as the store does. Design §1.3 updated. |
| N10 | **Fixed.** `StackedLayout.kt` KDoc and the design §8 table: waiting at final is reachable (Delay > 0 on an already-final game: "DELAYED" then a swap to "FINAL"); stale at final is not. |
| N11 | **Doc** (the code follows the Pill's `view = bug ?: sample`). Design §4.3 now says place mode with a follow draws the real view. |
| N12 | **Partly.** `sports.py`: inside a period's "end" window the PP seconds freeze with the game clock (fake-panel tests 230 OK). **Left:** the 2nd intermission's PP that has no source window in the 2nd period (needs a script-data change). |
| N13 | **Partly.** `everyStateEndToEndThroughTheMachine` asserts the SOG and bar animate OPEN after the first view. **Left:** `stackedNeverComposesThePill`'s painter-construction count. |
