# Power-play tab: code review (2026-11-08)

Scope: `git diff b30ef7a..HEAD -- . ':!docs' ':!design'` (commit 0 `6b9c94e`, the WP-1, WP-3 and WP-2 merges, and the
integration commit `7817ff8`). Checked against `docs/design/m6-power-play.md` (the Lead sign-off section wins where it
differs), owner rulings 13–19, and CLAUDE.md. I read all of this code. I did not run the build or the tests.

**Verdict: no blockers. Five should-fixes, of which two are spec gaps for the lead to decide. Then nits.**

---

## What holds (checked, no finding)

- **Kick keys.** `PpRun.tabWant` = `1 + side*2 + kind` and `sideOfTab` decode the four (side, kind) pairs correctly. The
  tab key adds `PIN_BIT` and `effTab()` gates on `ready`. The pill key is `1 + side`, gated only on the painter
  (`ScoreBug.kt:784-785, 802-827, 995-997`).
- **Seconds are redraw-only.** A seconds change keeps `tabWant`, so nothing kicks. `tab.content.secs` is written in
  composition only while unpinned and while the side and label agree (`ScoreBug.kt:536-538`). The redraw comes from
  `frame = remember(shownView, painter)`. `ScoreBugFrame` is a plain class (identity equality), so a new view always
  rebuilds the `drawBehind` lambda.
- **Sampler arguments.** `sampleInto` and `play` pass each track's own `kind`, `driver.value`, `from` and `to`. `from`
  and `out` are always distinct instances (`scratch` / `out`). Before the first transition the track is STILL/closed,
  so the first OPEN starts from `textDy = −4`, not the `PpFrame()` default of 0.
- **Content and close.** An OPEN calls `setTab` before `play`. A CLOSE keeps the content it opened with. A side change
  runs CLOSE(X), then `setTab(Y)`, `begin(Y)`, OPEN(Y). The tab and the pill call `begin` on their own sides, and the
  painter keeps per-side shader slots, so a closing X tab never takes Y's colours (`PowerPlayPainter.kt:281-304`).
- **`drawFrame` order.** Glow, glass, `pp.draw` (tab, tint, halo), body, takeover, on both branches
  (`ScoreBugPainter.kt:255-278`). All parameters are passed in order (`ScoreBug.kt:652-656`).
- **The pin.** Release at 3,600 comes from a child `snapshotFlow` on the driver, so it is correct at
  `MotionDurationScale` 2. The still path releases after frame B. The guarded `finally`, the no-painter skip and the
  cancel sites release. A new goal mid-pin does not. The hold shows `pinContent` until `LEAD_COVERED_MS`
  (`ScoreBug.kt:505-526, 548-591, 835-874`). `GoalSeamTest`'s new test checks the tab region pixel for pixel against the
  real painter and timeline, and first asserts the region is inked.
- **Drawing.** `PowerPlayPainter.draw` uses only save/restore/clipRect/translate/scale/drawRoundRect/drawRect/
  drawBitmap/drawText(char[]). It allocates nothing (allocation test < 4 KB over 300 frames). Nothing animates at rest:
  the drivers stop at 1,900/600/450, and intermission is static.
- **Bakes.** Shadow, shine, labels and halo all run on `hooks.background` (Default). Low-end mode bakes no shadow and
  no halo, and `sampleInto` zeroes both channels.
- **Geometry.** The clip is `[round(pillH), round(pillH)+M·d]` below, or `[−M·d, 0]` above, inside the leaf layer. The
  reach is 65 ≤ 74 (Standard) and 75 + 1 ≤ 76 (Large). The inset and depth come from `ScoreBugPlacement`, so placement
  and drawing cannot drift. Mirroring flips position, slide, text offset and gradient. It never flips the text, the
  shine or the shadow dy (N7).
- **Binding rules.** There are no new layers or modifiers, and `RootTreeRuleTest` and `ScoreBugBoundsTest` are
  untouched. The blur is baked only. Label type floors at 18 and the digits are 20. No API above 25 is used.
- **Machine.** Gating works: LIVE/INT, not SO, fresh only, never in `knownView`. `goalPp` is stored with the mark and
  cleared in `step()` and `reset()`. `ppEnded` is only an in-play end with no flagged goal. Delay growth resets
  `ppEndedEntry`/`lastShownPp`. All of this matches §1.4.
- **Fake panel.** `featured()` returns early only when `sports_pp` is set. `state()` and `game()` add `situation` or
  `summary` only when the timeline or a script supplies them. The default bodies are unchanged.

---

## Should-fix

### S1. An interrupted transition draws one frame of the new transition at the old driver time

`ScoreBug.kt:962-967` (`PpRun.play`):

```kotlin
t.from.copyFrom(scratch)
t.kind = kind; t.to = to          // visible to draw now
...
t.driver.snapTo(0f)               // may suspend
```

**The cause.** When a running transition is interrupted, `job?.cancel()` leaves the old `animateTo` holding the
`MutatorMutex` until its cancelled continuation is dispatched. The new coroutine starts `UNDISPATCHED` and sets
`t.kind` and `t.to`. Then `snapTo(0f)` suspends on the mutex, and `driver.value` keeps the old elapsed time meanwhile.
The kick usually comes from a recomposition with a new view, which rebuilds the draw lambda. So the draw in that same
Choreographer frame samples the **new** kind at the **old** `t`.

**Failure scenario.**
1. A tab is 700 ms into an OPEN.
2. A view arrives that ends the power play, or the bug goes stale.
3. `play(CLOSE)` is sampled at t = 700 ≥ `PP_CLOSE_MS`, so the frame has `tabClosed = true` and the tab vanishes for a
   frame.
4. The next frame, at t = 0 from `from` = OPEN@700, draws it nearly open again and it slides closed: a visible blink.

The reverse case, CLOSE@300 interrupted by an OPEN, jumps the slide forward for a frame.

**Fix.** Call `snapTo(0f)` first, then assign `kind`/`to`. Or keep the new kind in a pending field that draw ignores
until the snap lands.

**Status.** This comes from reading the code. It is not reproduced: the wiring tests use a fake sampler (see S5).

### S2. The pill track moves on the goal view before the lead covers the pill (held-frame rule)

`ScoreBug.kt:540-546` and `ScoreBug.kt:646`.

**The cause.** The pin freezes only the tab track. The pill key is evaluated in the goal view's own composition. For
the most common PP goal (a minor that the goal ends), `target` is null in that view, so the pill CLOSE (tint and halo,
600 ms ease) starts on frame 1.

**Failure scenario.**
1. EDM (home) scores on the power play.
2. While the takeover is pending, and for 0–216 ms while the lead wipes in from the left, the tint at the right end
   fades by roughly 40–50 %. The EDM logo's halo also dims.
3. That is a tell that the power play just ended, shown before the takeover hides the pill. It is exactly what the
   GA "held" body rule exists to prevent.

`GoalSeamTest.aPowerPlayGoalHolds…` checks only the tab region. The older pill-region seam test runs without a power
play. So no test catches this.

**Spec status.** §5 says "the pill track … may follow `target`" while pinned, so this is a spec gap.

**Suggested fix.** Defer pill kicks while `pinned` and not yet covered: re-run `step()` at the covered instant, or let
the release child also watch for `LEAD_COVERED_MS`. Extend the seam test to the pill.

### S3. Halo masks leak across games

`PowerPlayPainter.kt:252-279` and `ScoreBug.kt:439-451`.

**The cause.** The halo is cached per **side** (`haloMask[i]`, `haloSource[i]`) on a painter that lives for the whole
follow session. The setup effect is keyed on `hasFollow` (a Boolean), not on the game. On a new follow,
`FollowLogos.start` sets `slot.image = null`. `watch()` ignores null images, and `pr.reset()` never clears the
painter's halos. So the previous game's mask stays until a new logo bitmap is baked.

**Failure scenario.**
1. The user switches from TOR@MTL to CGY@EDM, and the EDM logo fails or is still loading.
2. An EDM power play draws a halo in the shape of **MTL's logo** under EDM's disc.
3. If the logo fails, this lasts the whole game.

`haloSource` also keeps the old logo bitmaps alive.

**Fix.** Clear `haloMask`/`haloSource` (a `clearHalos()`) when the logo URLs change or on a game change, or when
`watch` sees `img == null`.

### S4. A PP goal right after an intermission gets no PP GOAL

`ScoreBugMachine.kt:398-399`:

```kotlin
if (p.phase != GamePhase.LIVE || p.periodKind == PeriodKind.SO) return false
```

**The cause.** Intermission polls run every 2 buckets (`INTERMISSION_BUCKET_SKIP = 1`). `GOAL_FRESH_MS` is ≥ 80 s, so a
goal in the first ~40 s of a period is flagged against an INTERMISSION `p`. Ruling 19 keeps the power play through the
intermission, and the feed reports it (§1.1).

**Failure scenario.**
1. A minor at 19:30 of the 2nd carries over the intermission.
2. EDM scores 15 s into the 3rd.
3. `summary.scoring` says `pp` and matches the score, but `goalWasPp` returns false before reading it.
4. The tab shows the plain hold and no PP GOAL. Carry-over PP goals early in a period are common.

**Spec status.** §1.4 and S1 specify "p LIVE and not SO". The intent is to exclude the shootout winner, so this needs
a spec amendment.

**Suggested rule.** Use "p is LIVE or INTERMISSION, and neither is SO".

### S5. The wiring tests can't see `from`, the halo path, or the drawn words

All in `ui/src/test/kotlin/app/thebox/ui/sports/ScoreBugDrawTest.kt`.

- **`from` is never exercised.** `PpTestSamplers.Fake` (`:972-986`) ignores `from` entirely, and it is the sampler of
  every `ScoreBugDrawTest` PP case. CSS-from capture on interrupts (OPEN→CLOSE, CLOSE→OPEN, a side change mid-open)
  and S1 are therefore never exercised through the runner. The real sampler runs through the wiring only in the single
  `GoalSeamTest` scenario.
- **`assertEquals(0, halos)` is vacuous** (`:862`). No test provides a `LocalShellImageLoader`, so `FollowLogos` never
  sets an image and `bakeHalo` is never called in any mode. The halo `watch` path (`ScoreBug.kt:439-456`), including
  re-baking per bitmap identity and the S3 leak, has no test.
- **The drawn words aren't checked.** The fake `bakeLabels` never fills the painter, so the draws in those tests have no
  label masks. The pin-content assertions go through `probe.pp.drawnContent`, which re-implements the "covered"
  condition (`ScoreBug.kt:384-386`) instead of reading what `drawBug` passed (`lastDrawContent`).

**Ask:**
- Add one runner test with `PpSampler.Real`: an interrupted open, asserting no frame with `tabClosed` while `want != 0`.
- Add a fake `LogoFetch`/loader so the halo tests mean something.
- Assert `lastDrawContent` rather than `drawnContent`.
- Remove the stale comment at `:481`: "`PowerPlayTimeline.sample` (WP-2) is a stub in this tree".

---

## Nits

- **N1. Label bake race** (`ScoreBug.kt:361-379`, `PowerPlayPainter.kt:241`). Two `bakeLabels` can run at once on
  Default if the teams change while a bake is in flight, for example a follow switch within a bake's few ms. A
  cancelled bake is not cooperative: it still writes `painter.labels = l` when it finishes. If the old bake finishes
  last, `pr.labelKey` says the new teams are ready but the masks are the old teams', and nothing re-bakes. The fix is
  to return the `Labels` from the bake and publish it on main only when the key still matches. The probability is low.
- **N2. Second goal mid-pin keeps "PP GOAL"** (`ScoreBug.kt:841-845, 871-873`). If CGY scores at even strength during
  EDM's PP GOAL takeover, EDM's tab keeps saying "PP GOAL" through CGY's takeover. `pinContent` = what is drawn, and the
  not-ppGoal branch never swaps a `PP_GOAL` label. This matches §5/B2.4 and is enshrined by
  `aNewGoalMidPinKeepsPpGoalAndDoesNotRelease`, but it reads oddly. The lead should confirm it is intended.
- **N3. `ppGoalContent.side` is set only inside `drawContent(covered = true)`** (`ScoreBug.kt:870`), and `release`
  copies it (`:858`). If no covered draw ran between pin and release, the close slides out PP GOAL in the gradient of
  whichever side was last set. Set `side` in `pin()` instead.
- **N4. Hiding parks both tracks at once** (`ScoreBug.kt:494-495`). So the tab and the tint vanish on the first frame of
  the bug's 300 ms fade-out, while the glass is still visible. This is consistent with §3.1, but it is a visible pop.
- **N5. "Power play over." after an unflagged goal.** `ppEnded` excludes only *flagged* goals
  (`ScoreBugMachine.kt:374`). A score rise that is not flagged (demand UNSEEN, or a gap > `GOAL_FRESH_MS`) that also
  ends the power play is announced as "Power play over."
- **N6. Label fit always reserves the seconds slot** (`PowerPlayPainter.kt:212`). This includes labels drawn alone
  because `secs < 0`, so a no-time label may step down in size when it doesn't need to.
- **N7. `GoalFramesTest.featuredScore` builds a new `OkHttpClient` per poll** (`GoalFramesTest.kt:135`): every 500 ms
  for up to 5 min, each with its own pool. Hoist one client.
- **N8. "Byte-for-byte" default timeline.** `test_sports.py` (`TestPowerPlayOff`) asserts that `situation`/`summary`/
  `situationCode` are absent, not that the bodies are byte-for-byte equal. The code path is untouched, so this is fine.
  A golden body for a few instants would make §8's claim literal.
- **N9. `PowerPlayPainter` KDoc** says "The bakes publish through `@Volatile` fields", but the halo arrays are plain.
  This is safe in practice because `withContext` resumption orders the writes before main reads. Fix the comment, or
  make the arrays consistent.

---

## Summary

**Blockers:** none.

**Should-fix:**
1. **S1:** `PpRun.play` sets `kind`/`to` before `snapTo(0)`, so an interrupted transition draws one frame of the new
   transition at the old time (a tab blink on OPEN→CLOSE). `ScoreBug.kt:965-967`.
2. **S2:** The pill tint and halo start closing on the goal view before the lead covers the pill, which tells the
   viewer about a PP goal. This is a spec gap in §5. `ScoreBug.kt:540-546`.
3. **S3:** Halo masks persist across games (keyed per side, never cleared), so the previous team's logo-shaped halo
   can appear. `PowerPlayPainter.kt:252-279`, `ScoreBug.kt:439-451`.
4. **S4:** `goalPp` requires the previous snapshot to be LIVE, so a PP goal soon after an intermission (a carried-over
   power play) gets no PP GOAL. This needs a spec amendment. `ScoreBugMachine.kt:399`.
5. **S5:** The wiring tests use a sampler that ignores `from`, never load logos (so the "no halo" assertion is vacuous),
   and assert a re-implemented draw condition. `ScoreBugDrawTest.kt:862, 972-986`.
