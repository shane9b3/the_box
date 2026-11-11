# NHL score bug: "Stacked" style: design

Status: **proposed 2026-11-10; reviewed 2026-11-10; revised under the lead's rulings 2026-11-10.**
- Review: `docs/review/2026-11-10-stacked-theme-design-review.md` (B1–B4, S1–S11, N1–N8, M1–M5).
- Every finding is applied in place and marked "(B1)", "(S4)" and so on. The "Lead sign-off (2026-11-10)" section at the
  end gives each finding's disposition.
- Items marked **pending owner** are built with the stated default. §12 lists them.

Owner request (2026-11-10): "an option for a different theme for the NHL score bug". The mock-up is
`design/nhl-stacked/Stacked.dc.html`; its README is beside it. `support.js` and `vendor/` are only the viewer runtime. The
mock-up's inline styles and its `<style>` block are "the CSS". Its logic class is "the mock JS".

Builds on:
- `docs/decisions/2026-11-05-nhl-score-bug.md`, rulings 1–33 (binding);
- `docs/design/m6-score-bug.md` ("SB §n"), `m6-goal-animation.md` ("GA §n"), `m6-power-play.md` ("PP §n"),
  `m6-nfl.md` ("NFL §n"), `m6-sports-update-interval.md`;
- code on `main` at `87e22d4`.

Units are **design px** (window width ÷ 1920; `BoxDesignScale`, so `1.dp == 1 design px`, `docs/01-design-reference.md`)
and ms. **Must** means a reviewer rejects code that does otherwise. Every number lives in one named constant
(`SportsConstants`, §13).

The mock-up's sizes are "mock px". §4 maps them to design px.

---

## 0. Decisions at a glance

1. **One new setting: "Score bug style", Pill · Stacked.** Pill (today's look) stays the default.
   - Stored as `SportsPrefs.nhlStyle` in the backed-up `sports` domain.
   - Reachable from Settings → Sports (ADVANCED) and from a **Style** row in the quick bar's Score bug sheet (§1).
   - The settings store gains `coerceInputValues = true`, so an unknown enum name in any settings document falls back to
     that field's default instead of resetting the whole document (S7).
2. **NHL only.** An NFL game draws the NFL bug whatever the style (§9).
3. **The shared part changes by two fields.**
   - Feed → `NhlMapper` → `GameSnapshot` → `ScoreBugMachine` → `ScoreBugView` is shared.
   - `TeamLine.sog` carries shots on goal (§3).
   - `ScoreBugView.period` keeps the period label in stale and waiting views (B1). It is **one line** in
     `ScoreBugMachine.view()`. Nothing else in the machine changes.
4. **Only the drawing and animation layer forks.**
   - `ScoreBug(...)` branches once at the top with `key(league, style)`, as the NFL bug already does.
   - The new `StackedBug` has its own holder, runner and painters.
   - It reuses the pure pieces: `GoalTimeline` with a spec, `NhlTeamColours`, `ScoreBugPlacement`, `FollowLogos`,
     `Describe`, `SportsText`, `motionOff`, and `LiveText`, which moves to `ScoreBugShell.kt` by a pure move.
   - `ScoreBugWith` and the Pill painters stay byte-unchanged (§2).
5. **Size: the mock-up × 0.45** (`STACKED_SCALE`). The information text (bar text, takeover tally) is raised to **20 px**
   (S5).
   - The footprint is 392 × 141 Standard and 490 × 176 Large.
   - **The team rows never move** (S4, pending owner): the SOG and clock columns open and fold to the rows' right in
     every corner, as in the mock (§4.3).
6. **Shots on goal are already in the feed** (`awayTeam.sog` / `homeTeam.sog`, in both endpoints). The app drops them
   today. `NhlMapper.team()` reads one more field. The fake panel already sends `sog`; it gains script keys and a shootout
   fix (§3).
7. **Colours follow ruling 12 and the existing table**, through one pure derivation, `NhlStackedColours`, asserted for
   all 32 teams.
   - The row background is a fixed navy, `#12284F`.
   - Every bar has a hairline (S2).
   - Each bar stripe is ≥ 2 : 1 against its bar (S2).
   - Two stripes that look alike are separated by a home-team swap rule (S3) (§5).
8. **No blur, no clip on an ancestor of the surface.**
   - The CSS clip-path wipes and overflow reveals are `clipRect` on the bug's own leaf canvas.
   - The drop shadow is a baked `ALPHA_8` **nine-slice**, because it follows the box through layout transitions.
   - The goal glow is baked **for the exact box** of each takeover and cached by size. The layout is pinned during a
     takeover (B4) (§6).
9. **Animation is the mock-up's CSS**, sampled by pure functions in `:core`. Each track has one `Animatable<Float>`,
   read only in draw.
   - The flash stays at the lead's 3 × 400 ms.
   - "Reduce animations" gives a still version (§7).
10. **Owner questions** (§12) hold only look-and-feel items. Each one is built with its default.

---

## 1. The option: name, place, storage

### 1.1 What it is called and where it lives (lead ruling: name, default and both places)

| Where | What the user sees | Notes |
|---|---|---|
| Settings → Sports (ADVANCED) | **Score bug style** · *Pill* / *Stacked*. Detail: "For hockey games. Stacked shows the teams one above the other, with shots on goal during power plays and breaks." | New row `SportsRows.STYLE = RowId("sports.style")`, after `SIZE`. ADVANCED, as every Sports row is (SB §7), so the simple count stays at 35 and `SimpleRowCountTest` is untouched. |
| Quick bar → Score bug sheet | **Style · Pill**. OK toggles Pill ⇄ Stacked. The **sheet stays open** and focus stays on the row, as the other rows behave (`ScoreLeagueRow`, `PlayerStateMachineImpl.kt:2154`) (S8 / M4). | New `PickerChoice.ScoreStyleRow(style)`, after the Delay row. |

**When the sheet's Style row shows** (S8). The row follows the **followed game's league** (`ScoreFacts.game?.league`)
where there is one:
- an NHL game is followed: the row shows;
- an NFL game is followed: the row is hidden;
- nothing is followed: the row shows while the sheet's browsed league (`ScoreFacts.league`) is NHL or not reported.

So following an NHL game while browsing the NFL list keeps the row, and an NFL follow never shows a row that changes
nothing on screen.

**When the change shows** (S8). The picker sheet's box is (1376, 48)–(1872, 928) (`PlayerUi.kt:84–87`). It intersects
both right-corner footprints, so `ScoreBugPlacement.place` hides the bug while the sheet is open.
- **Right corners** (including the default): the new style shows when the sheet closes.
- **Left corners**: it shows at once, behind the sheet.

The design no longer claims the change is seen at once.

Why the sheet row: Sports settings rows are hidden in simple mode, so without it a simple-mode user could never pick the
new look. Corner and Delay are reachable both ways for the same reason (SB §7).

Words on screen: *Score bug style*, *Style*, *Pill*, *Stacked*. Never "theme".

### 1.2 Default

**Pill** (lead ruling). Nothing changes for existing users after the update.

### 1.3 Storage, backup, restore (S7)

```kotlin
// core/.../sports/SportsContracts.kt (append-only enum; persisted by name; plain @Serializable, see below)
@Serializable enum class NhlBugStyle { PILL, STACKED }
// core/.../sports/SportsPrefs.kt: trailing, defaulted
val nhlStyle: NhlBugStyle = NhlBugStyle.PILL,
```

**Domain.**
- `Domains.SPORTS` (`SportsDomain`, `core/.../settings/state/SettingsDomains.kt:44`) is backed up and in
  `RoomSettingsStore.KNOWN_DOMAINS`. No new domain.
- An old document or backup lacks the key and decodes to `PILL` (`ignoreUnknownKeys`, `encodeDefaults = false`;
  `data/.../settings/RoomSettingsStore.kt:367`).
- A Pill user's document never contains the key.
- **v0.1.5 restoring a Stacked-era backup** is safe: the key is unknown there and is dropped.

**The unknown-enum risk is general, and it is fixed in the store** (S7, lead ruling). An enum name the app does not know
fails in two places. Both were verified in the code:
- **Import into a registered slot.** `importSlotLocked` (`RoomSettingsStore.kt:274–280`) fails, logs "kept", and skips
  the **whole** restored document. Corner, delay and size stay as they were on the device.
- **Import into a slot not yet registered.** `sports` registers on first use, so this path is real. The raw JSON is
  stored (`:195–200`). `decode` (`:349–360`) later falls back to `domain.default`, so everything resets, and the next
  write makes that permanent.

`ScoreCorner`, `ScoreBugSize` and `League` carry this risk today, and so does every enum in every other domain.

**Fix (commit 0).**
- `RoomSettingsStore.JSON` becomes `Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }`.
  An unknown enum name, or a JSON `null` for a non-null field, now decodes to **that property's default**, and the other
  fields are kept.
- The `SettingsStore.kt:15` doc comment is updated to match.
- **This makes the bespoke `NhlBugStyleSerializer` of the first draft redundant**, so it is dropped. `NhlBugStyle` is a
  plain `@Serializable` enum like the others.
- **Limits, stated:**
  - `coerceInputValues` acts only on properties that have a default. An unknown value **inside a collection or as a
    map key** still fails the document. The one such field in the Sports documents is `SportsFollowDoc.meters:
    Map<League, PollMeasure>`; it is not backed up, and `League` is append-only.
  - A settings field without a default behaves as today: no regression.
  - The store test pins this.
- **Tests** (commit 0, data module, new `data/src/test/.../settings/RoomSettingsStoreCoerceTest.kt`):
  - `decode` with `"nhlStyle":"FUTURE"` and with `"corner":"MIDDLE"`;
  - `importInTransaction` into a **registered** `sports` slot and into an **unregistered** one, with the same two
    documents.

  In every case all the other fields survive, and the unknown field reads as its default.
- **Parental policy excepted (fix pass 2026-11-11, review C N9, lead ruling).** `coerceInputValues` must not weaken a
  parental setting on restore: both parental enums default to the less strict choice (`SHOW_WITH_LOCK`,
  `UNTIL_APP_CLOSES`). `SettingsDomain` gains `coerce: Boolean = true`; `ParentalDomain` sets it false and the store
  decodes it with `RoomSettingsStore.STRICT_JSON` (no coercion). A restored parental document holding a value this build
  does not know fails as a whole and the device's document is kept whole ("unreadable; kept"), on both restore paths: into
  a registered slot, and before the slot registers (the raw document is checked against the strict decode before it is
  stored). `RoomSettingsStoreCoerceTest` pins both, with `replace` on and off. The core test fixture's `SETTINGS_JSON`
  now also has `coerceInputValues`, and `FakeSettingsStore` decodes a non-coerced domain strictly and skips an unreadable
  document as the store does, so fakes and production agree.

**Writes.**
- The Settings row writes through `WpaPages.applySports`, in a new `SportsRows.STYLE` arm beside `SIZE` (`WpaPages.kt:745`).
- The sheet row writes through `PlayerEffects.setScoreStyle(style)`. It is new and has a default no-op body, as
  `setScoreLeague` does (`PlayerContracts.kt:637`).
- `PlayerEffectsImpl` implements it as `vm.updateSports { it.copy(nhlStyle = s) }`.

**The machine port.** `ScoreFacts` gains `style: NhlBugStyle? = null` (null = not reported, no Style row).
`PlayerViewModel` fills it from `SportsPrefs`.

The setting is app-wide, never per playlist (SB §7).

---

## 2. Architecture: how a second style plugs in

### 2.1 What is shared, what forks

```
:data  NhlScoreSource ──► :core NhlMapper (+sog) ──► GameSnapshot ──► ScoreBugMachine (+period) ──► ScoreBugView ─┐
:ui    ScoreBug(source, placement, geometry, …)  ── key(league, style) ──────────────────────────────────────────┘
          ├─ NFL ............................ NflBug(...)                      unchanged
          ├─ NHL, PILL ...................... ScoreBugWith(...)                byte-unchanged
          └─ NHL, STACKED ................... StackedBug(...)                  NEW
                 ├─ StackedHolder, StackedRun (layout tracks, the pin)  ui/sports/StackedBug.kt
                 ├─ StackedPainter (box, rows, columns, bar, shadow)    ui/sports/StackedPainter.kt
                 ├─ StackedGoalPainter (takeover, exact-box glow)       ui/sports/StackedGoalPainter.kt
                 └─ NineSlice (drop shadow only)                        ui/sports/NineSlice.kt
       shared shell:  LiveText (pure move)                              ui/sports/ScoreBugShell.kt (new)
       pure, :core:   StackedTargets.of(view) · StackedTimeline.sample(...) · GoalTimeline.sample/sampleStill(GoalSpec, …)
                      NhlStackedColours.pair(away, home) · ScoreBugPlacement (style-aware sizes)
```

| Type | Fate | Why |
|---|---|---|
| `ScoreBugMachine` | **one line** in `view()`: `period = label` on the NHL path (`ScoreBugMachine.kt:297`) (B1) | In play, the period label goes only into `bottom = CentreText.Period(label)` (`:286`). The waiting / stale branch then **overwrites** `bottom` with `Delayed` / `NoUpdate` (`:289–292`), so a stale or waiting view loses the period. Stacked's clock column has its own period slot and needs it. The NFL path and `knownView()` are unchanged. |
| `ScoreBugView` | **one trailing field** `period: PeriodLabel? = null` (B1) | Null for the NFL and the place-mode sample. The Pill ignores it. It changes only when the period changes, so the SB §4.7 budget (one view per snapshot) holds. |
| `CentreText`, `PowerPlay`, `GoalFact`, `DayState`, `ScoreRepository`, `ScoreRepositoryImpl`, `NhlScoreSource` | reused, unchanged | |
| `TeamLine` | **one trailing field** `sog: Int? = null` | Read by `NhlMapper` (§3); null for the NFL and before puck drop. **Known difference for the Pill (review C N1):** `sog` is part of `TeamLine` equality, so a shots-only stat correction (for example during an intermission, no clock) now gives a new view where it used to give an equal one; the Pill recomposes once and rebuilds its frame, drawing nothing different. Within the one-view-per-snapshot budget. |
| `NhlMapper` | **one line** in `team()` | §3.3 |
| `GoalTimeline` | **split by a spec** (S9): `sample(spec, tMs, out)` and `sampleStill(spec, tMs, out)`. The existing `sample(tMs, out)` / `sampleStill(tMs, out)` call them with `GoalSpec.CLASSIC` | The Stacked takeover has the same keyframe skeleton. About a dozen numbers differ (§7.2), plus the end time, the pop length and the still frame-B length, all of which are in the spec. |
| `GoalFrame` | **one field** `rowFlashAlpha: Float = 0f` | CLASSIC **writes 0** on every sample and still sample (S9), never "leaves it". |
| `GoalSampler.Real`, `ScoreBug.kt`'s `TAKEOVER_TOTAL_MS` (`:1180`) | unchanged; they stay on the CLASSIC entry points and constants (S9) | asserted in `GoalSpecTest` |
| `GoalPainter`, `ScoreBugPainter`, `PowerPlayPainter`, `PowerPlayTimeline`, `PpRun`, `GoalRun`, `Holder` | **not reused** | They are built on the pill's geometry (stadium shape, tab outside the pill), and `Holder.lastFrame` is typed `ScoreBugFrame?` (`ScoreBug.kt:1195`) (S1). |
| `LiveText` (private in `ScoreBug.kt:1150`) | **pure move** into `ui/.../sports/ScoreBugShell.kt` as `internal`; the text is byte-identical | Stacked shares it. It has no dependency on the Pill's holder. |
| `placeAt`, `PlaceHint` (private, typed on the Pill's `Holder`) | **stay in `ScoreBug.kt`, unchanged**; Stacked has its own `stackedPlaceAt` / `StackedPlaceHint` (about 40 lines) | Sharing them would mean generalising `Holder`, which edits `ScoreBugWith` (N1, partly rejected; see the sign-off). `NflBug`'s copy is left alone. |
| `Describe` (internal) | reused | `StackedDescribe` appends the shots phrase (§10). |
| `ScoreBugPlacement` | **style-aware sizes**: `width/height(size, league, style)` | Pill and NFL values unchanged (§4.3). |
| `PlacementInput` | trailing `style: NhlBugStyle = PILL` | Set in `FullScreenHost.scorePlacementInput` from `prefs.nhlStyle` (`FullScreenHost.kt:263`). |

### 2.2 The seam (`ui/.../sports/ScoreBug.kt:150`)

```kotlin
// N1: collect the whole prefs value (today only `.league` is read, :163); one collection, two reads.
val prefs = source.prefs.collectAsState().value
val prefsLeague = prefs.league
// … the league rule as today …
val style = if (league == League.NHL) prefs.nhlStyle else NhlBugStyle.PILL
val placed = if (placement.league == league && placement.style == style) placement else placement.copy(league = league, style = style)
key(league, style) {
    when {
        league == League.NFL -> NflBug(source, placed, geometry, modifier, place, NflHooks.Default)
        style == NhlBugStyle.STACKED -> StackedBug(source, placed, geometry, modifier, place, StackedHooks.Default)
        else -> ScoreBugWith(source, placed, geometry, lowEnd, modifier, place, GoalHooks.Default)
    }
}
```

- **A style change swaps the bug with no fade.** This includes a change mid-takeover: the old composable leaves, and its
  takeover is cancelled with it. The new one enters with its own first-show fade, and the goal is not replayed: the new
  runner's first view sets `lastSeq` (S1). This happens only on a user action, like the league swap (NFL §4.6).
- **No double bake.** Pill painters and masks are never built while Stacked is shown, and the reverse.
- **Budget unchanged:** one `ScoreBug` recomposition per snapshot (SB §4.7).

### 2.3 `StackedBug` (the runner)

```kotlin
@Composable internal fun StackedBug(source: ScoreBugSource, placement: PlacementInput, geometry: OverlayGeometry,
                                    modifier: Modifier, place: (PlacementInput, OverlayGeometry) -> DesignBox?,
                                    hooks: StackedHooks)
internal class StackedHooks(   // test seams, like GoalHooks / NflHooks; defaults are the real objects
    val colours: (String?, String?) -> StackedPair = NhlStackedColours::pair,
    val sampler: StackedSampler = StackedSampler.Real,          // fun interface → StackedTimeline.sample
    val goalSampler: GoalSampler = GoalSampler { t, still, out ->
        if (still) GoalTimeline.sampleStill(GoalSpec.STACKED, t, out) else GoalTimeline.sample(GoalSpec.STACKED, t, out) },
    val bakeGlow: (StackedGoalPainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null, val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default, val probe: StackedProbe? = null,
) { companion object { val Default = StackedHooks() } }
```

**Shell.**
- Its own `StackedHolder`: the footprint box, the drawn box, `positioned`, `drawn`, `lastView`, `lastFollowed`, and
  `lastFrame: StackedFrameState?`.
- 150 ms fade on its own leaf `graphicsLayer { alpha; clip = true }`, sized footprint + 2 M.
- `SCORE_SLIDE_MS` corner slide.
- Logos held across hides (U-S1), loaded at the **takeover size** (S6, §4.2).
- One draw node, `Spacer.drawBehind { StackedPainter.drawFrame(...) }`, tagged `SCORE_BUG_TAG` on the **drawn box**.
- The shared `LiveText` live region, `SCORE_BUG_LIVE_TAG`.

**Tracks.** Each track has one `Animatable<Float>` of elapsed ms, read only in draw. Each keeps a `from` frame captured
on interruption (CSS-transition semantics, as `PpTrackRun`). Each is kicked only when its **target key** changes (PP §3.1
B1: a seconds-only update just redraws).

| Track | Target key (from `StackedTargets.of(view)`) | Transitions |
|---|---|---|
| `sog` | `sogOpen` | open / close |
| `clock` | `clockOpen` | open / close |
| `bar` | `bar: BarKey?` = (kind, side), null = closed | open / close / **change** / **text swap** (§7.1) |
| `stripeAway`, `stripeHome` | `ppSide == side` | widen / narrow |
| `goal` | `goalSeq` (the trigger) | takeover 0–3 600, after 3 600–4 800 |

**The run owns what the bar says** (PP §3.1 B2). A `BarContent` (kind, side, seconds, period label, dim) is captured when
an open, change or text swap starts. A close slides out the words it opened with. An open bar's seconds update in place
when the key is unchanged (ruling 13).

**Carried from the Pill runner** (S1). Each item has a `StackedDrawTest` case (§10.2).

1. **Held previous body** (GA §5.2 step 4; `ScoreBug.kt:596`, `:720–723`). When a goal view arrives, the draw keeps the
   **pre-goal** state until the lead covers the box (`GoalTimeline.LEAD_COVERED_MS`, or frame A on the still path).
   That state is the rows' scores, the SOG digits, the clock column and the layout targets, held in
   `StackedHolder.lastFrame: StackedFrameState?`. Nothing new is seen before the takeover hides it.
2. **First fresh view snaps** (PP N5; `ScoreBug.kt:575`). The first non-waiting view of a follow snaps every track to
   its rest. Nothing animates in on follow.
3. **Hide freezes, reappearance snaps** (`ScoreBug.kt:566–573`). A hidden bug's tracks start nothing new and fade
   with the bug (a transition already running finishes under the 150-ms fade, as the Pill's do: review C N3). Once the
   fade is done they park. On reappearance every track snaps to the current targets. **(Fix pass 2026-11-11, review C
   S1.)** A follow that ends (Stop following) or a stale feed past the 5-minute hide (the source's view goes null) is
   such a hide: the box keeps its layout, its bar words and the game's colours (the colour pair is keyed on the **shown**
   view, never on the source's) through the fade, and the runner resets only once the fade is done (or at once in place
   mode, or when the next follow's first view resets it).
4. **Place mode parks** every track and shows the sample (§4.3).
5. **Back-to-back goals** (the B2.4 override, `ScoreBug.kt:964`).
   - A new goal mid-takeover restarts the takeover and keeps the pin.
   - If the bar still says an earlier goal's "… POWER PLAY GOAL" and this goal is not a power-play goal for that side,
     then once this goal's lead covers the box the bar takes the current power play's words (same side: a text cut) or
     closes.
   - **Rule 5 against B4 (fix pass 2026-11-11, review C S2, lead ruling).** While the pin holds, "closes" means the
     words only: they fade out (a text swap to nothing) and the bar keeps its height, so the pinned box and its glow
     keep their size. The bar itself closes at the 3 600 release, through the normal re-evaluation. B4's "no layout
     track starts a transition until 3 600" therefore holds for the bar too.
6. **Goal + a layout change in the same snapshot** (a goal and a PP start; a goal and INT; a goal and the PP ending).
   - The layout is **pinned at the pre-goal targets** for the whole takeover.
   - At the 3 600 release, every track re-evaluates against the current view, and the normal transitions run.
   - The takeover is therefore laid out on the **pre-goal width**. The first draft's "spans the intermission width"
     sentence was wrong (S1).
7. **Cancel** (hide, placing, new game, size change, style flip): the takeover is cancelled and released with snaps.
   `lastSeq` is updated above the visibility return, so a goal while hidden is never replayed.

**The pin** (PP §5, simplified; B4).
- **Takeover start.** Every layout track **that is mid-transition is snapped to the end of that transition**. That covers
  the SOG width, the clock width, **and the bar height**. From then until 3 600 ms no layout track starts a transition.
  The bar's stripes and text may finish what they were doing. The box size is therefore fixed for the whole takeover,
  so the glow is baked for exactly that box (§6).
- **Ruling 15.** If `view.goalPp` and the open bar is the scorer's power play, the bar's words cut to
  "EDM POWER PLAY GOAL" at `LEAD_COVERED_MS`. No slide.
- **Release** at driver ≥ 3 600, watched from a child coroutine as in `ScoreBug.kt:657`, so the takeover keeps its single
  tween. Every track re-evaluates.

**Still version.** It applies when `motionOff()` (`ui/.../sports/Motion.kt:12`). Every layout transition snaps; the
takeover is the still frames of §7.3. There is no `lowEnd` parameter: ruling 20 gives every box the full animation, and
`FullScreenHost` passes `lowEnd = false` (`FullScreenHost.kt:153`).

**Bar direction** (S11). The bar opens below the box for an **effective** top corner and above it for a bottom one. The
effective corner is computed from the **placed** box (`box.t + h/2 > 540`), as the Pill computes `up` at
`ScoreBug.kt:704`. So with subtitles on, a bottom corner moved to the top drops its bar downwards.

### 2.4 The pure layout targets (`:core`, `core/.../sports/StackedLayout.kt`, new)

```kotlin
enum class BarKind { POWER_PLAY, TWO_MAN, PP_GOAL, INTERMISSION, SHOOTOUT, FINAL, NO_UPDATE, DELAYED }  // append-only
data class BarKey(val kind: BarKind, val side: Side?)       // side null for the neutral kinds
sealed interface ClockSlot { data object Clock : ClockSlot; data object End : ClockSlot; data object Int : ClockSlot
                             data object NoUpdate : ClockSlot; data object Delayed : ClockSlot; data object Blank : ClockSlot }
data class StackedTargets(
    val sogOpen: Boolean, val clockOpen: Boolean, val bar: BarKey?, val barPeriod: PeriodLabel?, val barSecs: Int?,
    val ppSide: Side?, val period: PeriodLabel?, val clockSlot: ClockSlot, val columnDim: Boolean, val barDim: Boolean,
) { companion object { fun of(v: ScoreBugView): StackedTargets } }
```

`of(v)` is the whole state table of §8 as a pure, JVM-tested function. The UI never re-derives a state from
`ScoreBugView`.
- **Text swap vs change** is decided by the runner from two `BarKey`s and their colours, using
  `StackedTargets.sameLook(a, b)`: same side, and both team kinds (POWER_PLAY / TWO_MAN / PP_GOAL), or both neutral
  kinds.
- `NO_UPDATE` and `DELAYED` are neutral bar kinds, used only when a break bar is already the shown bar (§8).

---

## 3. Data: shots on goal

### 3.1 What the feed returns

- **`/v1/score/{date}` and `/v1/score/now`.** `games[].awayTeam.sog` / `homeTeam.sog` are integers on live and final
  games and absent on `FUT` / `PRE`. Sources:
  - `core/src/testFixtures/resources/sports/nhl/score-2026-11-05.json`: 9 LIVE games with `sog`; ANA–EDM PRE and NSH–COL
    FUT without;
  - `score-2026-11-04.json`: OFF games, e.g. OTT 30, BUF 25;
  - **one live check** on 2026-11-10 of `GET https://api-web.nhle.com/v1/score/now`: six LIVE games carry `sog` (e.g.
    COL 22, TOR 19); the FUT game NSH–LAK has none. No other live calls were made.
- **`/v1/gamecenter/{id}/landing`.** `awayTeam.sog` / `homeTeam.sog` are integers in `landing-live-int.json` (UTA 5,
  MTL 8, recorded in intermission). This is the followed game's endpoint (SB §2.1), so SOG costs no new request.
- **Shootout attempts are not counted** in `sog`. Stacked shows the regulation + OT shots through the shootout (§8).

### 3.2 What the app keeps today

**Nothing.** `NhlMapper.team()` (`core/.../sports/NhlMapper.kt:169`) drops `sog`.

### 3.3 Changes

- **Parser.** The NHL bodies are bounded (512 KB, `MAX_BODY_BYTES`) and decoded whole (SB §2.1). The CLAUDE.md
  streaming-parser rule covers unbounded playlists and EPGs. In `NhlMapper.team()`:
  `sog = t.int("sog")?.takeIf { it in 0..SportsConstants.SOG_MAX }`, with `SOG_MAX = 199`.
  - `JsonObject.int` is `content.toIntOrNull()` (`NhlMapper.kt:215`), so a JSON string `"12"` maps to 12. That is the
    same leniency as every other field, and the test expects 12 (N1).
  - Non-numeric or out-of-range values give null.
- **Model.** `TeamLine.sog: Int? = null`, trailing and defaulted. `EspnMapper` leaves it null.
- **Machine.** No change for SOG. It rides on the shown snapshot's `TeamLine`, on the same delayed timeline as the
  score (ruling 1).
  - Correction (N1): SOG usually changes during play, alongside the clock, but it can also change in a break (a stat
    correction in an intermission view, which has no clock under ruling 33).
  - Either way it changes at most once per snapshot, so the budget (one view per snapshot) holds. `ScoreBugMachineTest`
    gains the SOG-only-change-in-INT case.
- **Missing SOG** (either side null) keeps the SOG column closed in every state (§8).
- **Fake panel** (`tools/fakepanel/sports.py`):
  - **Script keys.** New `awaySog` / `homeSog` in `STATE_KEYS`: an int 0..199, or `null` to omit the field.
    `check_config` rejects other values.
  - **Shootout fix** (S11). `team()` computes `3 + 2·score + 4·period` (`sports.py:402`), so the shootout (period 5)
    adds 4 shots, and the shootout winner's +1 adds 2. The NHL counts neither. New formula:
    `3 + 2·regScore + 4·min(period, 4)`, where `regScore` is the score without the shootout winner's +1.
    - This changes bytes only in the SO, OVER, OFF and FINAL samples of the default timeline.
    - `test_sports.py` does not pin `sog` values today (checked), so no existing assertion changes.
    - The PP §8 "default bytes unchanged" rule is amended for this one field, recorded here.
  - **Tests** (`test_sports.py`):
    - `sog` present on LIVE / INT / OFF samples, absent on FUT / PRE;
    - no rise from the 3rd period into SO, OVER, OFF or FINAL;
    - the overrides; `null` omits the field;
    - the config rejections.

---

## 4. Size and position

### 4.1 The scale (S5; Q1 pending owner)

The mock-up is drawn at 870 × 240 (+ 66 bar) for viewing. **`STACKED_SCALE = 0.45`** (Standard). Large is
0.45 × `SCORE_LARGE_SCALE` = 0.5625. Why 0.45:

- The widest box (rows + SOG + clock, 870 mock px) becomes **391.5**, just under today's 404-px pill.
- The headline sizes match the pill: score 35 (pill 40), clock 28.8 (pill 26), team code 27.

**Information text sits above the floor** (S5).
- The bar text and the takeover tally are **20 px**, matching the pill's PP tab text (`PP_TEXT_PX` 20) rather than the
  18 px floor. The bar is **33 tall**, the mock's 40 / 66 text-to-bar proportion.
- **Large scales the raised texts too:** 25 px, bar 41.25.

The cost is height: the box is **108 tall (141 with the bar)**, against the pill's 70 (106 with the PP tab). The owner
alternative is **0.50**:
- box 435 × 120 + bar 33, footprint 435 × 153;
- bar text 20 is then exactly the mock's proportion;
- the NFL bug is already 520 wide.

0.40 is not offered: bar text, period and tally would all need raising (S5).

### 4.2 Geometry (mock px × 0.45 unless stated; Large × 1.25; one constant each)

| Item | Mock | Standard | Note |
|---|---|---|---|
| Top part height | 240 | 108 | |
| Team row | 540 × 120 | 243 × 54 | background `#12284F` (§5) |
| Stripe / PP stripe | 8 / 14 | 3.6 / 6.3 | full row height |
| Logo slot (margin-left 22, width 112), logo height 96 | | 9.9, 50.4, 43.2 | the **takeover-size** bitmap (67.5), drawn downscaled with `FILTER_BITMAP` (S6) |
| Team code (padding-left 30), 60 / 800 | | 13.5, 27 | Instrument Sans Bold (§11) |
| Score slot (mock width 90 → **102**), margin-right 34, 78 / 800 | | 45.9, 15.3, 35.1 | widened so "10" fits (`StackedFitTest`) |
| Row divider | 1 px white .22 | 1 | not scaled |
| SOG column | 130 | 58.5 | `#15191F`, left inset line 1 px `#2B3240` |
| SOG digits, 54 / 700 | | 24.3 | |
| SOG label, 19 / 700, track .08 em, `#C3C7D0`, 2-px rules | | **18**, rules **dropped** | 38 px of label in 58.5 leaves no room for the rules |
| Clock column | 200 | 90 | `#15191F`, left inset line |
| Period slot (112, bottom pad 14), 48 / 800 | | 50.4, 6.3, 21.6 | |
| Rule (2 px `#5B6170`, side margin 26) | | 1, 11.7 | |
| Clock, 64 / 700 tabular | | 28.8 | `m:ss` / `mm:ss` |
| Bar | 66 | **33** (S5) | hairline at its inner edge, 1 px white .22, on **every** bar (S2) |
| Bar stripes (left 46 / 84, width 26, overhang 10, skew −30°, travel −140) | | 20.7 / 37.8, 11.7, 4.5 (height 42), −30°, −63 | |
| Bar text (padding-left 140, gap 26), 40 / 800 italic | | 63, 11.7, **20** (S5) | Instrument Sans **Bold Italic** (§11) |
| Box border, radius | 1 `#2B3550`, 6 | 1, 2.7 | |
| Box shadow | 0 12 36 `.55` | dy 5.4, σ 8.1, α .55 | nine-slice (§6) |
| Goal panel padding / gap | 40 / 30 | 18 / 13.5 | |
| Goal logo | 150 | **67.5** (Large 84.4) | **loaded at this size** (S6); hard drop shadow dy 2.7, black .25 |
| GOAL, Barlow Condensed 900 italic | 140, track .04 em | 63 | atlas, as GA §2.3 |
| Team name / "POWER PLAY GOAL" line | 18 / 700 | **dropped** (8.1 px) | ruling 7 precedent (lead ruling); the PP goal goes to the bar |
| Takeover tally "EDM 3" / "TOR 1", 30 / 800 | | **20** (S5) | two lines, gap 2.7 |
| Streaks 1 / 2 | 110 / 40 wide, overhang 40 | 49.5 / 18, 18 | |
| Glow | low 40 blur / 6 spread; peak 90 / 18 | GA's glow constants (`GOAL_GLOW_*`), baked for the exact box (B4, §6) | |

**Widths:**
- play: 333 (rows + clock);
- power play, and a PP carried into intermission: 391.5 (rows + SOG + clock);
- ordinary intermission, shootout and final: 301.5 (rows + SOG).

**Fit rules** (SB U-N3):
- every string is measured at both sizes;
- clock-column strings step down 2 px to 18, never below, with no ellipsis;
- **the clock column keeps air (fix pass 2026-11-11, review U B1 / S1, lead ruling).** The clock is drawn with tabular
  figures (`'tnum'`, as the mock's `tabular-nums`; Instrument Sans Bold's proportional "0" is 0.682 em, its tabular
  digits 0.600 em), so `mm:ss` is ≈ 77 px at 28.8 and stays at 28.8. The clock's advance keeps **5** px each side
  (`CLOCK_BREATHING`); every word in the column (the period, "END", "INT", "Delayed", "No update") keeps **6** px of ink
  each side (`WORD_BREATHING`). Both clear the 1-px inset line and the 1-px border; both were 1. "Delayed" fits on one
  line (20.8 Standard, 18.8 Large, measured). A slot string with a space that does not fit on one line at 28.8 breaks at
  its **first** space onto two lines at one fitted size (baselines 1.05 em apart), the block's ink centred on the lower
  slot and kept 6 px inside it, when that gives the larger size: "No update" draws as "No" / "update" at 20.8 (block
  41 of the 44.6 allowed, Standard). The strings and the §8 slot are unchanged
  (`StackedPainter.fitSlot`; `StackedFitTest` asserts the drawn widths, the block and its centring);
- **bar strings must fit at 20 px, including the italic overhang** (the glyphs' measured right-side bearing past the
  advance), for all 32 abbreviations. `StackedFitTest` asserts it. The worst cases are "EDM POWER PLAY GOAL" (≈ 220 +
  63 padding, in 391.5) and "2ND INTERMISSION" (≈ 247, in 301.5);
- the takeover content is measured in `begin()` against the pinned width. Under 18 px of slack at 301.5, the padding
  drops from 18 to 8, and then the tally is dropped. The scores return on the box at 3 600 ms.

### 4.3 Where it sits: the rows never move (S4 / M1, pending owner)

`cornerBox` returns the **footprint**: the largest the box can be.
- Standard **392 × 141**, Large **490 × 176**, from `width/height(size, NHL, STACKED)`.
- It sits at the corner exactly as today: right corners at `1920 − 96 − w`, bottom corners at `1080 − 56 − h`.

**Inside the footprint, the box's left edge (the rows) is anchored at the footprint's left edge in every corner.** The
SOG and clock columns open and fold on the rows' right, so the rows never move: the mock's behaviour.
- **Left corners:** the box's right edge moves, just as in the mock.
- **Right corners:** the box's **right edge floats**:

  | State | Box width | Right edge (top right, Standard) | Gap to the 96-px inset |
  |---|---|---|---|
  | Play | 333 | 1765 | 59 |
  | Power play, PP carried into INT | 391.5 | 1823.5 | 0 |
  | Intermission, shootout, final | 301.5 | 1733.5 | 90.5 |

  The bug is therefore not flush with the right inset at even strength. That is the price of still rows. The rejected
  alternative (right edge flush, rows sliding sideways by up to 90 px on every power play and break) is recorded in
  §12 Q7.
- **Vertical:**
  - top corners: the box top is at the footprint top, and the bar drops below it;
  - bottom corners: the box bottom is at the footprint bottom, and the bar opens **above** it (ruling 16's mirror).

| Corner | Footprint (Standard) | Rows' left edge | Box top / bar |
|---|---|---|---|
| Top right (default) | (1432, 56)–(1824, 197) | 1432 | top 56; bar below (164–197) |
| Top left | (96, 56)–(488, 197) | 96 | top 56; bar below |
| Bottom right | (1432, 883)–(1824, 1024) | 1432 | bottom 1024; bar above (883–916) |
| Bottom left | (96, 883)–(488, 1024) | 96 | bottom 1024; bar above |

**Placement rules.**
- Every existing rule runs unchanged on the footprint: hiding (overlay intersections, toast, reminder, passive bar),
  lifting above a user bar or the quick bar, subtitles moving a bottom corner to the top, and `glowAllowed` with margin
  M. So a column or the bar opening never runs into an overlay. It hides slightly more often than the drawn box needs.
  Accepted.
- `tabAllowed` and `tabBox` are not used.
- **Bounds:** `SCORE_BUG_TAG` is on the **drawn box**; the layer is the footprint + 2 M (`StackedBoundsTest`, S11).
- **Place mode** draws the sample at the anchored play-width box (333 × 108) in the footprint, and the place hint is
  positioned from the **drawn** box, not the footprint (N8). The sample is "AWY 0 / HME 0", clock "0:00", period slot
  empty, fallback discs. **(Fix pass 2026-11-11, review C N11: text fixed to match the code.)** The sample is drawn only
  with **no follow**; with a followed game, place mode draws that game's real view at its rest layout, as the Pill does
  (`view = bug ?: sample`), and rule 4's tracks rest on that view's targets.
- **Layout direction (fix pass 2026-11-11, review U S2).** The bug's layer and the place hint are laid out
  left-to-right whatever the system locale (`LocalLayoutDirection` pinned to `Ltr` around them), so a right-to-left
  locale never mirrors the drawn box inside its footprint. `StackedBoundsTest.aRightToLeftLocaleMovesNothing` pins it.
- **Pill and NFL unchanged:** `style` defaults to `PILL`. `ScoreBugPlacementTest` and `NflPlacementTest` keep their
  existing cases unedited.

**Large** (M3, pending owner Q8): offered for Stacked, 490 × 176. That is about a quarter of the screen width and 16 %
of its height. The hand pass (§10.4) judges it over real video.

---

## 5. Team colours

The mock-up hard-codes EDM and TOR:
- rows `#12284F`;
- stripes `#FF4C00` / `#2563EB`;
- PP bars EDM `#D9520A` and TOR `#1D4ED8`;
- intermission bar `#1B2440`;
- goal EDM base `#C94A06` accent `#12284F`, TOR base `#1D4ED8` accent white;
- glow red;
- pop `#FF8A3D` / `#7AA7FF`.

TOR's blues are not Toronto's colours. Ruling 12 rules them out.

### 5.1 Derivation (`core/.../sports/NhlStackedColours.kt`, new, pure)

```kotlin
data class StackedColours(val stripe: Int, val barBg: Int, val barA: Int, val barB: Int,
                          val goal: TeamColours, val rowFlashAlpha: Float)
data class StackedPair(val away: StackedColours, val home: StackedColours)
object NhlStackedColours {
    fun of(abbrev: String?): StackedColours            // the team's own resolution; resolved once per team
    fun pair(away: String?, home: String?): StackedPair  // applies the S3 collision rule; cached per pair
}
```

| Slot | Rule | EDM | TOR |
|---|---|---|---|
| Row background | fixed `STACKED_ROW_BG = #12284F` for both rows (lead ruling, Q3 closed) | | |
| Columns, box fill | fixed `#15191F`; border `#2B3550` | | |
| **Stripe** | whichever of base / accent contrasts more with the row navy, mixed toward white in 5 % steps until ≥ **3.0 : 1** against it (WCAG 1.4.11) | `#FF4C00` (4.36) | `#FFFFFF` (14.6) |
| **PP bar background** | `mixBlack(tab, .172)`: the point PP §6.2's C5 already asserts, so white bar text is ≥ 4.5 : 1 for all 32 teams with no exception | `#D33F00` (4.69) | `#001A4B` (16.8) |
| **Bar stripe A** (S2) | the first of [other team colour, `mixBlack(tab, .5)`, `towardWhite(tab, .4)`, white] that is ≥ **2.0 : 1** against `barBg` | navy `#041E42` (3.53) | white (16.8) |
| **Bar stripe B** (S2) | the first of [`towardWhite(tab, .4)`, other team colour, white] that is ≥ 2.0 : 1 against `barBg` **and** ≥ 1.5 : 1 against A | `#FF9466` (2.16) | `#66799D` (3.83) |
| **Bar hairline** (S2) | 1 px white .22 at the bar's inner edge, on **every** bar (team and neutral) | | |
| Neutral bar (intermission, shootout, final, no update) | fixed `STACKED_BAR_NEUTRAL = #1B2440`; stripe A = the away row stripe, B = the home row stripe (after S3) | | |
| Goal panel / lead | `NhlTeamColours.of(abbrev)`: base = panel, accent = lead and streak 1 (ruling 12, the same as the Pill; Q2 pending owner) | `#041E42` / `#FF4C00` | `#00205B` / white |
| Glow | `TeamColours.glow` (lead ruling, Q5 closed; ruling 12) | orange | white |
| Score pop | `TeamColours.pop` → white | `#FF6726`-ish (derived) | white |
| Row flash | the row stripe at peak α starting at `.55`, stepped down by .05 until **C7** passes: both **white** and the scorer's **pop** colour ≥ **3.0 : 1** on the flash blend over the row navy at t = 3 600 (S11) | α .40 | α .50 |

**What the derivation gives** (computed 2026-11-10 with the `Contrast` formula; asserted by `NhlStackedColoursTest`):
- **Stripe lightening:**
  - unchanged team colour for 23 teams and the fallback;
  - 3 steps: COL `#4479A2`, CBJ / NJD / NYR `#D53547`;
  - 4 steps: FLA / WSH `#D34058`, MTL `#BF4B57`;
  - 5 steps: MIN `#BC5262`, WPG `#4079B1`.

  On the neutral bar every stripe is ≥ 3.18 : 1.
- **Bar stripes (S2).** With the candidate rule, stripe A is ≥ 2.0 : 1 against its bar for all 32 teams and the fallback.
  The lowest is CBJ at 2.00, navy on red. B is ≥ 2.16. A against B is ≥ 1.51 (the fallback).
  - First-draft stripes that vanished (BOS, PIT, UTA, LAK, SEA, NSH, TOR, TBL, STL, BUF, VGK, fallback) now take gold,
    light blue, silver, or white for A. Example: BOS has gold A and a grey `#666666` B on a black bar.
- **Row flash C7 (S11).** Peak α comes out between .30 (OTT, VGK) and .55 (CBJ, MTL, NJD, NYR) for the 32 primary
  stripes. At that α every team has white ≥ 3.07 and pop ≥ 3.02. The first draft's fixed .55 left the pop digit at
  2.06–2.99 : 1 for 28 teams (EDM 2.46).

**Navy, black and grey bars against the navy rows (S2).** These PP bars are under 1.5 : 1 against the rows: BUF, STL,
TBL, TOR, NSH, VGK, SEA, LAK, WPG, UTA, BOS, PIT, and the fallback. That is 12 teams, not the first draft's 5. The
neutral bar `#1B2440` is 1.05 : 1 too.
- The always-on hairline and the visible stripes carry the edge.
- The true colours stay (ruling 12). Q4 shows the owner TOR, BOS and VGK.

### 5.2 Stripe collisions (S3, lead ruling)

The row stripe is the only team-colour mark on the rows, so two stripes that look alike carry no information. Examples:
DET / TBL / TOR (white), the five golds, CBJ / NJD / NYR (`#D53547`), FLA / WSH.

**The measure is CIELAB ΔE76 between the two stripes, collision when ΔE < 20** (`STACKED_STRIPE_DE_MIN`). The review
proposed "< 1.3 : 1 contrast ratio", which is rejected (see the sign-off). A luminance ratio ignores hue: it flags 126
team pairs, including red against blue (COL `#4479A2` vs NYR `#D53547`). ΔE < 20 flags the 50 pairs that really look
alike. Among them are every listed routine matchup: NYR–NJD, FLA–WSH, TOR–DET, BOS–PIT, BUF–BOS, and also ANA–EDM–PHI,
CAR–LAK, OTT–VGK, NYI–SJS, COL–WPG.

**`pair(away, home)`:**
1. If the two stripes collide, the **home** team uses its other colour, lightened to 3 : 1 against the row by the stripe
   rule.
2. If that still collides (computed: only COL–WPG, DAL–LAK and CAR–LAK with the problem team at home), home keeps its
   primary and the **away** team uses its other colour instead.

Computed over all 32 × 31 ordered pairs, every collision resolves by step 1 or step 2. `NhlStackedColoursTest` asserts
that the result is ≥ 20 for every ordered pair.

**The swapped stripe is used everywhere that team's stripe is used**: the row stripe, the neutral bar's stripes, and the
row flash (whose C7 α is computed for the swapped colour too). The goal panel keeps the true base and accent.

---

## 6. Drawing without blur or ancestor clips

- **Tree.** It is GA §1:
  - `StackedBug` is a sibling inside `FullScreenHost`'s box;
  - its only `graphicsLayer` is its own leaf (`alpha = fade`, `clip = true`), sized footprint + 2 M, with M =
    `GOAL_LAYER_MARGIN` 74 / 76;
  - it is never an ancestor of the `SurfaceView`, so `RootTreeRuleTest` passes unedited.
- **`clip-path: inset()` wipes.** `save(); clipRect(l, 0, r, h); drawRoundRect(box, r, r, paint); restore()` on the
  bug's own canvas (GA §2.2). No `clipPath` (HWUI API 25 draws path clips through the stencil without anti-aliasing), no
  `saveLayer`.
- **`overflow:hidden` reveals** (the SOG width, the clock collapse, the bar height) are a `clipRect` around the region.
  In the CSS the bar content is bottom-anchored and "drops down" with the bar's leading edge; at a bottom corner it is
  top-anchored and grows upward.
- **Rounded box corners** (radius 2.7).
  - Only the regions that touch a box corner use `clipRect(region) + drawRoundRect(boxRect, r, r, fill)`: the row
    stripes, the last open column, and the bar.
  - Every other region is a plain `drawRect` (N3).
- **Skewed stripes.** The bar stripes are anti-aliased `drawPath` parallelograms, built once per size and moved with
  `translate`. The streaks use the GA soft-stop gradient band.
- **Drop shadow (nine-slice, B4).**
  - `NineSlice` bakes one `ALPHA_8` mask per size on a software canvas with `BlurMaskFilter`, off the main thread.
  - The source rect is **≥ 6σ + 1 in each direction**: 50 × 50 design px Standard (σ 8.1) and 62 Large. Every slice line
    therefore lies ≥ 3σ inside the shape, and the stretched edge profile is exact. Radius 2.7, offset dy 5.4.
  - It is drawn as 8 `drawBitmap(src, dst)` calls stretched to the current box rect. **The centre is skipped**, because
    the box fill covers it (N7).
  - It follows the box through every layout transition.
- **Goal glow (exact box, B4).**
  - The pin (§2.3) fixes the box size for the whole takeover. `StackedGoalPainter.bakeGlow(w, h)` therefore bakes the GA
    pair (low and peak) for **that exact rect**: radius 2.7, GA's spreads and σ (`GOAL_GLOW_*`), with the box cut out.
    It runs off the main thread, as `GoalPainter.bakeGlow` does.
  - **Cache:** keyed `(w, h, size)`, LRU of 2 entries. There are at most 3 widths × 2 heights per size.
  - **Prewarm:** whenever the layout comes to rest (every width and height track idle), a bake for the current rect is
    queued, so a goal normally finds its masks ready.
  - If the masks are not ready at takeover start, the glow joins on the first frame after they land. The GA rule was
    "no glow that goal". The glow alpha is a slow ramp (0 → .55 over 540 ms), so a late start is not visible as a pop.
  - **Memory:** each mask is (w + 2M) × (h + 2M) ALPHA_8. At the biggest Standard box that is 540 × 289 ≈ 156 KB at
    1080p, so a pair is ≈ 312 KB and the cache at most ≈ 624 KB. 4K is × 4, Large × 1.25². This is the same order as GA
    (63 + 120 KB per size).
  - **Edge falloff (N6), re-run on the Stacked rects.** The box's nearest screen edge is 56 px away (top or bottom
    inset). That leaves 48 px past the peak spread of 8 (Standard) and 46 past 10 (Large), with σ 22. The edge alpha is
    `.8 · ½·erfc(d / σ√2)` = **1.3 %** Standard and **1.4 %** Large. The right edge is ≥ 96 px away.
  - The top inset (56) is less than M (74), so the glow **is** cut by the screen edge, at ≤ 1.4 % alpha, which is
    invisible.
  - The straight sides of a 2.7-radius box have the 1-D erfc profile; the corners fall off faster. The ≤ 2 % rule holds.
- **`Paint.setShadowLayer` is not used**: hardware canvases before API 28 ignore it on shapes, and minSdk is 25.
- **Text shadows are hard offsets.**
  - The bar text: 1 px, black .25.
  - GOAL: 2.7 px, black .28, in the atlas.
  - The logo's drop shadow: an `extractAlpha` copy drawn black at .25, offset 2.7. It is made once per takeover in
    `begin()`.
- **Allocation-free draw** (GA §2.5):
  - **per size:** paints, paths, the shadow mask, the GOAL atlas;
  - **per layout rest:** the glow bake;
  - **per takeover:** colours, streak shaders, tally chars, the padded badge and its shadow;
  - **per bar open, change or swap:** the bar string's `CharArray` and the colours, in `BarContent`.
- **Frame cost on a Fire TV Stick** (N3; GA §2.6):
  - one invalidated node; about 10 region fills; 8 shadow blits; text from cached strings;
  - one bounded leaf layer: 540 × 289 Standard and 642 × 328 Large at 1080p, mostly transparent (N7);
  - expect ≤ 5 ms of 16.7 ms;
  - idle FULL is unchanged (one redraw per snapshot, ruling 6);
  - measured by `StackedFramesTest` (§10.4).

---

## 7. Animation spec (from the CSS; t = 0 at the change)

CSS easings:
- `ease` = `cubic-bezier(.25,.1,.25,1)`;
- `ease-in` = `(.42,0,1,1)`;
- `ease-out` = `(0,0,.58,1)`;
- `ease-in-out` = `(.42,0,.58,1)`;
- **Std** = `(.4,0,.2,1)`;
- **Spring** = `(.2,.8,.2,1)`;
- **Wipe** = `(.7,0,.2,1)`.

Each is a `CubicBezier` in `:core` (`GoalTimeline.kt:69`).

### 7.1 Layout transitions (`StackedTimeline`, `:core`, new)

These are CSS transitions: an interrupted transition starts the new one **from the current value**, with full duration
and delay. The reversing-shortening factor is not applied (PP §3.3).

| Track | Direction | Property | Duration | Delay | Easing | Still |
|---|---|---|---|---|---|---|
| SOG column | open | width 0 → 58.5 | 450 | 0 | Std | snap |
| | close | width → 0 | 450 | 350 | Std | snap |
| SOG contents | open | opacity 0 → 1 / x −5.4 → 0 | 300 / 350 | 300 / 300 | ease | snap |
| | close | opacity → 0 / x → −5.4 | 200 / 200 | 0 | ease | snap |
| Clock column | close | width 90 → 0 | 450 | 0 | Std | snap |
| | open | width → 90 | 450 | 350 | Std | snap |
| Clock contents | close | opacity → 0 | 150 | 0 | ease | snap |
| | open | opacity → 1 | 300 | 500 | ease | snap |
| Bar | open | height 0 → 33 | 400 | 400 | Std | snap |
| | close | height → 0 | 400 | 0 | Std | snap |
| Bar stripe A / B | open | x −63 → 0 (skew −30°) | 450 | 700 / 780 | Spring | snap |
| | close | x → −63 | 300 | 0 | ease-in | snap |
| Bar text | open | opacity 0 → 1 / x −10.8 → 0 | 350 / 450 | 850 / 850 | ease / Spring | snap |
| | close | opacity → 0 / x → −10.8 | 150 / 150 | 0 | ease | snap |
| Bar **change** (another colour or side: PP ↔ break, one side's PP → the other's, a break → the scorer's "POWER PLAY GOAL" never happens) | | stripes and text close (as above); bar background lerps old → new over 300 ms; content swaps at 300; stripes open with delays 300 / 380 and text with 450 | ≈ 900 | 0 | as above | cut |
| Bar **text swap** (same colour and side, `sameLook`: 5-on-4 ⇄ 5-on-3, INT → FINAL, SHOOTOUT → FINAL, break ⇄ NO UPDATE / DELAYED) (S10, lead ruling on Q13) | | text out (opacity, x) 150, swap, text in 350 / 450; stripes and background untouched | 600 | 0 | ease / Spring | cut |
| Row stripe | widen / narrow | width 3.6 ⇄ 6.3 | 400 | 0 | ease | snap |

- **The order falls out of the delays**, as in the mock.
  - Into a power play: SOG opens (0–450), the bar drops (400–800), stripes slide in (700–1 230), text (850–1 300).
  - Out of it: text and stripes leave, the bar lifts (0–400), SOG closes (350–800).
- **Ruling 13 / 6:** the bar's PP time and the clock change only when a snapshot arrives. Nothing ticks; the mock JS
  `tick()` is mock-up behaviour.

### 7.2 Goal (`GoalTimeline.sample(GoalSpec.STACKED, …)`)

| Element | Duration | Delay | Easing | Keyframes | STACKED vs CLASSIC |
|---|---|---|---|---|---|
| Lead wipe (accent) | 3 600 | 0 | Wipe | in 0–6 %, out 94–100 % | same |
| Panel wipe (base) | 3 600 | 0 | Wipe | in 3–11 %, out 88–97 % | same |
| Glow (box incl. bar) | 3 600 | 0 | ease-in-out | 0 → low 15 % → peak 50 % → low 85 % → 0 | same |
| Streak 1 (accent .55) | 1 000 | 350 | Std | x −90 → 450, skew −24° | CLASSIC .6, −83 → 508 |
| Streak 2 (white .30) | 800 | 1 750 | Std | as streak 1 | CLASSIC .35 |
| White flash | **3 × 400** | 420 | ease-in-out | 0 → .40 → 0 | flash ruling (not the mock's 4 × 300); CLASSIC .45 |
| Logo | 600 | 450 | Spring | α 0, × .3, −120° → 70 %: α 1, × 1.1, +6° → rest | CLASSIC 550, 1.12, 8° |
| G, O, A, L | 450 each | 420 / 500 / 580 / 660 | Spring | α 0, dy +27, × 1.9 → 60 %: dy −2.25, × .94 → rest | CLASSIC 420, 25 / −2 |
| GOAL pulse | 500 × 3 | 1 150 | ease-in-out | × 1 → 1.06 → 1 | CLASSIC 1.07 |
| Tally slide-in | 450 | 900 | Spring | α 0, dx +16.2 → rest | CLASSIC 20 |
| Name line | | | | | **dropped** |
| Score pop | 900 (`spec.popMs`) | 3 600 | Spring | × 1.5 → .9 @ 40 % → 1.06 @ 70 % → 1; pop → white | CLASSIC × 1.55 |
| Row flash (new) | 1 200 | 3 600 | ease-out | α (C7 peak) → 0 | CLASSIC writes 0 |
| End (`spec.endMs`) | 4 800 | | | | CLASSIC 4 500 |

`GoalSpec` (S9) holds every number in this table that differs, plus `endMs`, `popMs` and `stillFrameBMs` (CLASSIC 900,
STACKED 1 200).
- The takeover covers **the top part only** (108 tall); the bar stays visible.
- `GOAL_MARK_MS` (4.5 s, the live-region mark) is unchanged.
- The takeover is laid out on the **pinned pre-goal width** (S1 item 6).
- **Score pop visibility (fix pass 2026-11-11, review U N4: recorded, as built).** The pop's first state (× 1.5, the pop
  colour) is visible from 3 168 ms, uncovered by the panel's exit and the lead's exit (3 384), under GA's "pop reveal"
  rule (`GoalTimeline.kt:396–398`), as the Pill does; the mock shows the plain white score during the exit and pops at
  3 600. The code follows the Pill's established behaviour; this line records it.
- **Rule 5 against B4 (fix pass 2026-11-11):** a back-to-back goal during the pin hides an earlier "… POWER PLAY GOAL"
  bar's words only; the bar closes at the 3 600 release (§2.3 rule 5).

### 7.3 Still version ("Reduce animations"; `motionOff()`)

| Part | Still |
|---|---|
| Every §7.1 transition | snaps to rest (the mock's `prefers-reduced-motion` .01 s) |
| Takeover frame A (0–3 600) | the panel at rest: base, logo, GOAL, tally; no lead, streaks, flash, pulse or glow (GA §7) |
| Frame B (3 600–4 800, `stillFrameBMs`) | the plain box; the scorer's digit in `pop` at × 1; the scoring row tinted at a fixed .25 × its flash colour |
| Then | the plain box |
| PP GOAL bar words | cut at frame A, released at 3 600 |

---

## 8. Every state (`StackedTargets.of`)

The **period slot** shows `ScoreBugView.period` (B1) whenever the clock column is open. "Pending owner" marks a look the
mock-up does not show that §12 asks about.

| State (as `ScoreBugView` says it) | Rows | SOG col | Clock col (period / slot) | Bar | Source |
|---|---|---|---|---|---|
| Pre-game (FUT / PRE) | **nothing drawn** (`view()` is null; ruling 4) | | | | ruling |
| LIVE, even strength | stripes 3.6 | closed | "2ND" / "10:22" | closed | mock |
| LIVE, power play | PP side 6.3 | open | as play | "EDM POWER PLAY 1:37" (`sports_pp_label` + time if known) | mock |
| 5-on-3 | as PP | open | as play | "EDM 5-ON-3 1:37" (ruling 14); 5-on-4 ⇄ 5-on-3 is a **text swap** (S10) | lead |
| 6-on-4 (ruling 18) | as PP | open | as play | as PP | ruling |
| 4-on-3 in OT | as PP | open | "OT" / clock | "EDM POWER PLAY 0:48" | ruling |
| Empty net at even strength, 4-on-4 | as play | closed | | closed | ruling 14 |
| End of period (0:00, stopped, before INT) | as play | closed | "1ST" / "END" | closed | **pending owner Q5** |
| Intermission (no PP carried) | stripes 3.6 | open | **folded** | "1ST INTERMISSION", neutral | mock |
| OT intermission (playoffs) (N5) | as intermission | open | folded | "OT INTERMISSION" / "2OT INTERMISSION" | lead |
| **Intermission with a PP carried over** (B3) | PP side 6.3 | open | **open**: "1ST" / "INT", no clock (ruling 33) | **the team's PP bar with the frozen time**, "EDM POWER PLAY 1:12" (ruling 19) | **pending owner Q4b** |
| … that PP's time runs out or play resumes | normal transitions to the new state | | | | |
| Goal (even strength) | takeover over the top part, then row flash + pop | pinned | pinned | pinned | mock |
| Power-play goal | as goal | pinned | pinned | words cut to "EDM POWER PLAY GOAL" at `LEAD_COVERED_MS` (ruling 15); at 3 600 it closes or changes per the data | lead |
| Shorthanded goal against the PP | as goal | pinned | pinned | the PP team's words stay, pinned | |
| Goal while the bar is mid-open (N5) | as goal | pinned | pinned | the bar height **snaps** to open at takeover start; its stripes and text finish their own transition (B4) | lead |
| OT | as play | | "OT" / "2OT" / clock | | |
| **Shootout** (S10) | stripes 3.6 | **open** (reg + OT shots) | **folded** | "SHOOTOUT", neutral | **pending owner Q6** |
| Shootout winner (+1, ruling 11) | **takeover plays** | | | | ruling |
| Final | stripes 3.6 | open | folded | "FINAL" / "FINAL/OT" / "FINAL/SO"; from SHOOTOUT it is a text swap | lead |
| Delayed, waiting (0–120 s delay), in play | as the oldest snapshot | closed (no PP while waiting) | period / "Delayed", column at .55 | closed | lead |
| Delayed, waiting, in a break (N5) | as the oldest snapshot | open | folded | "DELAYED" at .55, a text swap | lead |
| Stale ("No update"), in play | as last snapshot | closed (`powerPlay` is null when not fresh) | period / "No update", column at .55 | closed (as the PP tab, PP §0.3) | lead |
| Stale in an intermission (S10) | as last | open | folded (no reopening) | **"NO UPDATE"** at .55, a text swap; the words come back on the next fresh view | lead |
| Stale in a PP carried into INT | as last; PP stripe narrows (no PP when stale) | open | open: "1ST" / "No update", .55 | closed | lead |
| Stale at end of period (N5) | as play | closed | "1ST" / "No update", .55 | closed | lead |
| Stale in the shootout (N5) | as shootout | open | folded | "NO UPDATE" at .55 | lead |
| Stale at final | **cannot happen**: `staleAt()` returns null outside LIVE / INTERMISSION (`ScoreBugMachine.kt:800–808`) | | | | |
| Waiting at final (fix pass 2026-11-11, review C N10: the first draft said it cannot happen) | following an already-final game with Delay > 0 gives a **waiting** view (`shown == null`) with `top = Final` | open | folded | "DELAYED" at .55 for up to the delay, then a text swap to "FINAL" | code |
| Hidden after 5 min stale | the box fades out (`view()` null) | | | | machine |
| Missing SOG (either side null) | | never opens | | | lead |
| Place mode (no follow) | sample "AWY 0 / HME 0" | closed | "" / "0:00" | closed | |

**Notes.**
- **Period words:** `SportsText.period(res, label).uppercase(Locale.ROOT)` gives "1ST", "2ND", "3RD", "OT", "2OT".
- **New strings** (`strings_sports.xml`, commit 0):
  - `sports_stacked_end` "END";
  - `sports_stacked_int` "INT";
  - `sports_stacked_intermission` "%1$s INTERMISSION";
  - `sports_stacked_shootout` "SHOOTOUT";
  - `sports_stacked_no_update` "NO UPDATE";
  - `sports_stacked_delayed` "DELAYED";
  - `sports_stacked_pp_goal` "%1$s POWER PLAY GOAL";
  - `sports_stacked_sog` "SOG";
  - `sports_desc_sog` "Shots on goal: %1$s %2$d, %3$s %4$d";
  - `sports_picker_style` "Style · %1$s";
  - `settings_sports_style`, `settings_sports_style_desc`, `settings_sports_style_pill`, `settings_sports_style_stacked`.
- **Away is the top row, home the bottom**, as on the Sports-tab cards and the pill's left-to-right order.

---

## 9. NFL (lead ruling)

The style applies to the **NHL only**:
- `ScoreBug` computes `style = PILL` whenever the league is NFL (§2.2), so `NflBug` is byte-unchanged;
- `PlacementInput.style` is ignored for the NFL;
- the Settings row says "For hockey games";
- the sheet row follows §1.1.

---

## 10. Tests

### 10.1 `:core` and `:data` (JVM)

- **`NhlMapperTest`.**
  - `sog` from `landing-live-int.json` (5 / 8) and `score-2026-11-05.json` (VGK 14 / DET 8).
  - Null on PRE / FUT. Null for −1, 200, `"x"`, `null`. **12 for the JSON string `"12"`** (N1).
  - `EspnMapper` leaves it null.
- **`ScoreBugMachineTest`** (B1):
  - `view().period` survives stale and waiting, in play (REG and OT) and in a break, and is null for the NFL;
  - a SOG-only change in INT emits one view for that snapshot;
  - the Pill's existing cases are unedited.
- **`SportsContractsTest`:** `SportsPrefs` without `nhlStyle` decodes to PILL, and a Pill document encodes without the key.
- **`RoomSettingsStoreCoerceTest`** (data, commit 0; S7): §1.3's four cases.
- **`StackedLayoutTest`:**
  - every row of §8 through `StackedTargets.of`, including the N5 rows;
  - `sameLook` for each text-swap pair and each change pair.
- **`StackedTimelineTest`:**
  - every §7.1 row at start, delay end, midpoint and end;
  - interruption from the current value;
  - the change and text-swap sequences;
  - the pinned snap;
  - allocation-free `sample`.
- **`GoalTimelineTest`**: unedited, green.
- **`GoalSpecTest`** (S9):
  - **`LegacyGoalTimeline`**, a frozen copy of `GoalTimeline` at `87e22d4` in `core/src/test/.../sports/`, compared
    **bitwise** (`Float.toBits`; booleans and `done` by equality) on **every `GoalFrame` field**;
  - inputs: t from −1 to 4 700 in 0.25-ms steps, plus NaN and +∞;
  - for `sample(CLASSIC, …)`, the old `sample(…)` overload, `sampleStill(CLASSIC, …)` and the old `sampleStill`;
  - CLASSIC writes `rowFlashAlpha = 0` after a STACKED sample into the same frame;
  - STACKED: each §7.2 difference;
  - the flash is exactly 3 cycles of 400 ms (≤ 3 Hz) for both specs;
  - `GoalSampler.Real` and `TAKEOVER_TOTAL_MS` still reference CLASSIC (a reflection-free equality check of
    `TAKEOVER_TOTAL_MS == 4 500`).
- **`NhlStackedColoursTest`** (all 32 + fallback; S2, S3, S11):
  - stripe ≥ 3.0 against the row and ≥ 3.0 against the neutral bar;
  - white on `barBg` ≥ 4.5 with no exceptions;
  - **A and B each ≥ 2.0 against `barBg`; A against B ≥ 1.5**;
  - **C7** (white **and** pop ≥ 3.0 on the flash blend, primary and swapped stripes);
  - **ΔE ≥ 20 after `pair()` for all 32 × 31 ordered pairs**, with named cases NYR–NJD, FLA–WSH, TOR–DET, BOS–PIT,
    BUF–BOS, CAR–LAK, COL–WPG, DAL–LAK;
  - the §5 table values pinned for EDM / TOR / BOS / WPG.
- **`ScoreBugPlacementTest`**, new cases only:
  - the Stacked footprint at 4 corners × 2 sizes, inside 96 / 56;
  - every SB §4.4 hide and lift rule on the footprint;
  - `glowAllowed` with M.
- **Player** (`ScoreBugPlayerTest`):
  - the Style row: NHL followed → shown; NFL followed → hidden; nothing followed + sheet NHL → shown; nothing followed +
    sheet NFL → hidden; `style == null` → hidden (S8);
  - OK calls `setScoreStyle`, the sheet stays open, and focus stays on the row.
- **`player_keys.csv`** and `PlayerFiveKeyReachabilityTest` gain the row.
- **Settings** (`SportsStyleRowTest`, new): the `sports.style` row is ADVANCED and writes `nhlStyle`;
  `SimpleRowCountTest` stays at 35.

### 10.2 `:ui` (Robolectric, `@GraphicsMode(NATIVE)`; PNGs for eye review, not goldens)

- **`StackedScreenshotTest`** (WP-2):
  - frames **built by hand** from fixture `StackedFrameState`s and colours, so it does not wait on WP-1's real targets
    (review ordering note);
  - over dark, white-ice and 50 % grey frames, Standard and Large;
  - takeover frames at 0 / 216 / 1 000 / 1 800 / 3 600 / 4 200 ms;
  - output to `ui/build/score-bug-stacked/` with a contact sheet;
  - asserted: white on the row ≥ 7 : 1, bar text ≥ 4.5, SOG label ≥ 4.5.
- **`StackedStatesScreenshotTest`** (integration, after WP-1 and WP-2 land): every §8 row through the real
  `StackedTargets.of` and `NhlStackedColours`, for EDM–TOR, TOR–DET, BOS–PIT and VGK–LAK. The owner's screenshots for
  §12 come from here.
- **`StackedFitTest`** (WP-2):
  - every bar string × 32 abbreviations at 20 / 25 px, **with the italic's measured overhang**;
  - "10" in the score slot;
  - "19:58", "No update" and "Delayed" in the clock column at ≥ 18;
  - the takeover at 301.5 / 333 / 391.5.
- **`StackedPainterAllocationTest`** (WP-2): `StackedPainter.drawFrame` and `StackedGoalPainter.draw` allocate nothing.
- **`StackedDrawTest`** (WP-3; compose test clock):
  - one composition per snapshot, zero between;
  - a seconds-only update redraws without a track kick;
  - **each S1 rule 1–7 as its own case**: the held old score until 216 ms; first-view snap; hide freeze + reappear snap;
    place parking; back-to-back goals with an old "POWER PLAY GOAL"; goal + PP start, goal + INT, goal + PP end in one
    snapshot; cancel;
  - the pin snaps width **and bar height**;
  - PP GOAL words at `LEAD_COVERED_MS`;
  - the text swap vs change;
  - the still path under `MotionDurationScale(0f)`;
  - a style flip mid-takeover (S11);
  - a subtitle-moved bottom corner drops its bar downward (S11).
- **`StackedSeamTest`** (WP-3):
  - STACKED never builds a `GoalPainter`, `ScoreBugPainter` or `PowerPlayPainter`; PILL never builds a Stacked painter;
  - an NFL view with STACKED prefs takes `NflBug`;
  - a style flip swaps without a fade.
- **`StackedBoundsTest`** (WP-3; S11): the layer is footprint + 2 M; `SCORE_BUG_TAG` is the drawn box; the shadow and the
  baked glow never leave the layer at any width and height.
- **`StackedLogoTest`** (WP-3; S6, the `ScoreBugLogoTest` analogue): `FollowLogos.start` is asked for the takeover size
  (67.5 × d, Large 84.4 × d).
- **Unedited and green:** `ScoreBugDrawTest`, `ScoreBugBoundsTest`, `GoalSeamTest`, `GoalAnimationScreenshotTest`,
  `PowerPlayScreenshotTest`, `NflBugDrawTest`, `RootTreeRuleTest`.

### 10.3 Fake panel

`test_sports.py`: §3.3's cases. Run with `python3 -m unittest tools/fakepanel/test_sports.py`.

### 10.4 Emulator and device hand checks (lead; not run by the proposer)

**Setup.** `python3 tools/fakepanel/fakepanel.py --port 8080 --sports --sports-pp`, with the debug `sports_base`
override. Follow CGY @ EDM and set Score bug style → Stacked from the sheet. The new style shows when the sheet closes
(top-right default).

**Walk the PP §8 timeline:**
- 1st: CGY even-strength goal (the held old score); EDM PP (SOG opens, the bar drops).
- 1st INT: EDM `1451` frozen at 5. This is **B3's PP-in-INT**: clock column "1ST / INT", the PP bar with the frozen
  time.
- 2nd: the EDM PP expires; then a CGY PP, and a CGY PP GOAL ("CGY POWER PLAY GOAL", then the bar closes).
- 2nd INT: the ordinary intermission.
- 3rd: 5-on-4 → 5-on-3 text swap; the EDM PP goal at 5-on-3; a plain EDM goal.
- OT: the 4-on-3 bar.
- SO: "SHOOTOUT", the shootout winner's takeover, then the "FINAL/SO" text swap. SOG must not jump in the shootout (S11).

**Scripted extras** through `POST /admin/config` `sports_script` (S11):
- end of period: `secondsRemaining: 0, running: false, inIntermission: false`;
- `awaySog: null` mid-game (the column closes);
- an `awaySog` change during an INT (the budget case);
- stale in INT: `sports_faults {"status": 503}` during an intermission (the bar says "NO UPDATE", and the words return);
- the same in play (the PP bar closes; "No update" in the clock column).

**Repeat:**
- at each corner: rows still in the right corners, the bar above at the bottom;
- at Large;
- with Delay 30 s;
- with subtitles on in a bottom corner.

**Also check:**
- place mode;
- the Settings row;
- backup → restore keeps the style;
- the system "Remove animations" still version;
- TalkBack / VoiceView (the shots phrase).

**Broadcast check (M5).** Watch a real NHL broadcast with the bug in each corner. Note whether the 141-tall Stacked box
covers the broadcaster's own score graphic (often along the bottom or top-left). Top right stays the default; report
anything for the owner.

**Device items.**
- `StackedFramesTest` (instrumented, `app/src/androidTest/kotlin/app/thebox/player/StackedFramesTest.kt`, beside
  `GoalFramesTest`): frame time during a takeover and a PP open, **at Standard and Large**, and **in a bottom corner
  with the quick bar up** (lifted) (S11). On a Fire TV Stick (ruling 20's check).
- Idle FULL "≤ 1 frame in 10 s" with the Stacked bug (the `FullIdleFramesTest` shape).
- The navy, black and grey bars over real video (§5.1, Q4).

---

## 11. Fonts (lead ruling on Q16)

- **Bundled today** (`ui/src/main/res/font/`): Instrument Sans regular / medium / semibold / bold, JetBrains Mono
  regular / medium, and Barlow Condensed Black Italic. Barlow is the full font, not subset (GA §9).
- **Inter is not bundled and is not added.**
- **GOAL:** Barlow Condensed Black Italic. This is exactly the mock's font, already bundled.
- **Everything else:** Instrument Sans Bold (700).
- **The bar text: a real Instrument Sans Bold Italic** (N2, lead ruling), bundled as
  `ui/src/main/res/font/instrument_sans_bold_italic.ttf` in commit 0. The handling matches the existing Instrument Sans
  files (checked against `docs/m0-toolchain.md` item 3 and the Licences page):
  - It must be the **upstream static instance** from `Instrument/instrument-sans`, as the four existing weights are,
    not the `google/fonts` variable file. Font variation axes need API 26 and minSdk is 25. The commit records the
    upstream commit and the file's SHA-256 here.
  - **Licence:** the family is already covered. `ui/src/main/assets/licenses/InstrumentSans-OFL.txt` ("Copyright 2022
    The Instrument Sans Project Authors", OFL 1.1, no Reserved Font Name) is listed as
    `Licence("InstrumentSans-OFL.txt", R.string.licences_instrument, R.string.licences_ofl)` (`DiagPages.kt:555`). The
    OFL covers every file of the family, so there is **no new Licences entry and no new licence file**. If the upstream
    italic carries a different copyright line, the existing text is replaced by the upstream `OFL.txt` that covers both.
  - It is loaded only by the Stacked painters through `sansTypeface(context, R.font.instrument_sans_bold_italic)`. It is
    not added to `BoxFonts.Sans`, so nothing else changes.
  - **Fallback:** if upstream publishes no static Bold Italic, the bar uses Bold with `textSkewX = −0.21`, and
    `StackedFitTest` keeps the overhang check (N2).
  - About 87 KB.
  - **Recorded (commit 0, 2026-11-10): the real italic, not the fallback.** Source:
    `Instrument/instrument-sans` at commit `7fa22308a3d0c94ee2b3cd537a1196b65db34a3e` (`master`, 2023-06-14; the file's
    last change is `24690a4`, 2023-04-12), `fonts/ttf/InstrumentSans-BoldItalic.ttf`, 88 200 bytes, SHA-256
    `c423640296fc5c59442320aa673f4214010a2989b93cc83396bed9f341f8322b`. Static (no `fvar` table). The bundled
    `instrument_sans_bold.ttf` is byte-identical to that commit's `InstrumentSans-Bold.ttf` (SHA-256 `735badeb…dffad3`),
    so it is the same source as the existing weights. Its name table carries the same copyright line, and that commit's
    `OFL.txt` is identical to `InstrumentSans-OFL.txt`: no licence change. The fallback skew stays as
    `STACKED_FAUX_ITALIC_SKEW_X` for a face that fails to load.

---

## 12. Owner questions

Only the items that need the owner's eye. Each is built with the default shown, so the build does not wait on an
answer. The screenshots come from `StackedStatesScreenshotTest` and the hand pass.

1. **Size.** The Stacked box is about as wide as today's bug (about a fifth of the screen width) and about twice as tall:
   141 of 1080 with the bottom bar open. A slightly bigger version (× 0.50: 435 wide, 153 tall) is easier to read but
   covers more of the picture. *Default: the smaller one (× 0.45).*
2. **Goal colours.** The goal animation uses each team's own two colours, the same way the current bug does, so
   Edmonton's goal is a navy panel with an orange sweep. Your mock-up shows Edmonton the other way round (an orange
   panel). We will show you both. *Default: the same colours as the current goal animation.*
3. **Power-play bar for navy, black and grey teams.** For 12 teams (Toronto, Buffalo, St. Louis, Tampa Bay, Nashville,
   Winnipeg, Seattle, Los Angeles, Vegas, Utah, Boston, Pittsburgh), the power-play bar is almost the same dark shade as
   the box. A thin light line separates them, and the bar's two sliding stripes use a bright colour (white, gold, light
   blue or silver), so the bar always reads. We will show you Toronto, Boston and Vegas. The alternative is a brighter
   shade that is not the team's real colour. *Default: the teams' real colours with the line and bright stripes.*
4. **A power play that carries into intermission.**
   - 4a. **Ordinary intermission:** exactly as your mock-up. The bar says "1ST INTERMISSION" and the clock column folds
     away.
   - 4b. **If a team's power play carries over the break:** the bar keeps showing that power play with its frozen time
     ("EDM POWER PLAY 1:12"), as the current bug does. The clock column stays and reads "1ST" over "INT". Your mock-up
     does not show this case. The alternative is the plain "1ST INTERMISSION" bar, which hides the remaining power-play
     time. *Default: keep the power-play bar with the frozen time.*
5. **End of a period** (the clock has reached 0:00 but intermission has not started yet, usually a few seconds). *Default:
   the clock column shows "1ST" over "END".* The alternative already looks like intermission: the clock column folds,
   the shots open, and the bar says "END OF 1ST".
6. **Shootout.** *Default: treated like a break.* The clock column folds, the shots on goal (from the game) stay open, and
   the bar says "SHOOTOUT". At the end the bar just changes to "FINAL/SO", with nothing else moving. The winning
   shootout goal still plays the goal animation.
7. **Right-hand corners: what moves.** In your mock-up the team rows never move, and the box gets wider or narrower on
   its right side as the shots and clock columns open and close. In a right-hand corner that means the box's right edge
   moves in and out (by up to about 90 px), and at even strength the box sits a little in from the screen edge. The
   alternative keeps the right edge fixed against the screen edge, but then the logos and scores slide sideways each
   time a power play or break starts. *Default: rows stay still, as in your mock-up.*
8. **Large size.** Score bug size "Large" makes the Stacked box 25 % bigger: about a quarter of the screen width and a
   sixth of its height. *Default: offered as for the current bug.* We will check it over real games; the alternative
   is to keep Stacked at the standard size only.

Decided by the lead, not asked:
- the name, the Pill default and the two places for the setting;
- fixed navy rows;
- the team-colour glow;
- dropping the small line above GOAL;
- the final look;
- stale and delayed;
- the bar swap rule;
- the 3-flash limit;
- hockey only;
- the real italic font;
- the Style row's gating;
- the stripe swap rule for look-alike teams.

---

## 13. Constants (`SportsConstants`, new; Standard design px, Large × 1.25 unless stated)

- **Scale, footprint and raised texts:**
  - `STACKED_SCALE` .45;
  - `STACKED_W_MAX` 392, `STACKED_H` 108, `STACKED_BAR_H` 33 (footprint 392 × 141);
  - `STACKED_BAR_TEXT_PX` 20, `STACKED_TALLY_PX` 20, `STACKED_SOG_LABEL_PX` 18;
  - `STACKED_GOAL_LOGO` 67.5;
  - the mock-px geometry of §4.2 in one `StackedMock` block.
- **Colours:**
  - `STACKED_ROW_BG` `#12284F`, `STACKED_COL_BG` `#15191F`, `STACKED_BORDER` `#2B3550`, `STACKED_INSET_LINE` `#2B3240`,
    `STACKED_RULE` `#5B6170`, `STACKED_SOG_LABEL` `#C3C7D0`, `STACKED_BAR_NEUTRAL` `#1B2440`;
  - `STACKED_BAR_HAIRLINE_ALPHA` .22, `STACKED_DIM_ALPHA` .55;
  - `STACKED_STRIPE_MIN` 3.0, `STACKED_BAR_STRIPE_MIN` 2.0, `STACKED_BAR_AB_MIN` 1.5, `STACKED_STRIPE_DE_MIN` 20;
  - `STACKED_C7_MIN` 3.0, `STACKED_ROW_FLASH_ALPHA` .55, `STACKED_ROW_FLASH_STEP` .05, `STACKED_STILL_ROW_TINT` .25;
  - `STACKED_BAR_A_BLACK` .5, `STACKED_BAR_B_WHITE` .4.
- **Shadow and glow:**
  - `STACKED_SHADOW_DY` 5.4, `STACKED_SHADOW_SIGMA` 8.1, `STACKED_SHADOW_ALPHA` .55, `STACKED_SHADOW_SRC` 50 (Large 62);
  - `STACKED_GLOW_CACHE` 2.
- **Timings:**
  - every §7.1 duration and delay;
  - `STACKED_BAR_SWAP_OUT_MS` 150, `STACKED_BAR_SWAP_IN_MS` 350 / 450, `STACKED_BAR_CHANGE_OUT_MS` 300;
  - `STACKED_ROW_FLASH_MS` 1 200, `STACKED_BAR_SKEW_DEG` −30.
  - `GoalSpec.STACKED` holds the takeover numbers.
- **Data:** `SOG_MAX` 199.

---

## 14. Work packages

**Gate for every step:** `./gradlew :core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug lintAll`,
plus `python3 -m unittest tools/fakepanel/test_sports.py` where the panel is touched.

### Commit 0: contracts, stubs, the store fix and the font (lead, Opus). Builds green on its own (B2).

| File | Change |
|---|---|
| `core/.../sports/SportsContracts.kt` | `TeamLine.sog`; `ScoreBugView.period`; `NhlBugStyle`; `PlacementInput.style`; `ScoreFacts.style` |
| `core/.../sports/ScoreBugMachine.kt` | the one line `period = label` in `view()` (B1) |
| `core/.../sports/SportsPrefs.kt` | `nhlStyle` |
| `core/.../sports/SportsConstants.kt` | the §13 block |
| `core/.../sports/StackedLayout.kt` (new) | `BarKind`, `BarKey`, `ClockSlot`, `StackedTargets` (`of` stub: the play state; `sameLook` stub) |
| `core/.../sports/StackedTimeline.kt` (new) | `StackedTrack`, `StackedFrame`, `sample` signature (stub: rest values) |
| `core/.../sports/NhlStackedColours.kt` (new) | `StackedColours`, `StackedPair`, `of` / `pair` signatures (stub: fallback) |
| `core/.../sports/GoalTimeline.kt` | `GoalSpec` (CLASSIC from today's constants incl. `endMs` 4 500, `popMs` 900, `stillFrameBMs` 900; STACKED = CLASSIC placeholder); `sample(spec, …)` / `sampleStill(spec, …)`; the old overloads delegate; `GoalFrame.rowFlashAlpha`, written 0 by CLASSIC |
| `core/.../sports/ScoreBugPlacement.kt` | `width/height(size, league, style)` signatures; PILL paths unchanged |
| `core/.../full/PlayerContracts.kt` | `PickerChoice.ScoreStyleRow`; `PlayerEffects.setScoreStyle {}` |
| `core/.../full/state/PlayerStateMachineImpl.kt` | **only** the `is PickerChoice.ScoreStyleRow -> Unit` arm in the exhaustive `when` at `:957–961` (B2) |
| `core/.../settings/tree/SportsRows.kt` | `STYLE` row id (not yet in `rows`) |
| `core/.../settings/state/SettingsTexts.kt` | the four `SPORTS_STYLE*` keys |
| `core/.../settings/SettingsStore.kt` | the doc comment on the JSON flags (S7) |
| `data/.../settings/RoomSettingsStore.kt` | `coerceInputValues = true` (S7) |
| `data/src/test/.../settings/RoomSettingsStoreCoerceTest.kt` (new) | §1.3's four cases |
| `ui/.../sports/ScoreBug.kt` | the `key(league, style)` branch; collect the whole `prefs`; `LiveText` removed (moved) |
| `ui/.../sports/ScoreBugShell.kt` (new) | `LiveText`, a pure move, now `internal` |
| `ui/.../sports/StackedBug.kt` (new) | `StackedBug`, `StackedHooks` signatures; the body falls back to `ScoreBugWith` until WP-3 |
| `ui/.../full/FullScreenHost.kt` | `scorePlacementInput` sets `style` |
| `ui/.../full/PlayerText.kt` | the `ScoreStyleRow` arm in `choice` (`:478–501`), with its row text (B2) |
| `ui/.../settings/vm/ResourceSettingsTexts.kt` | the four `idOf` arms (B2) |
| `ui/src/main/res/values/strings_sports.xml` | every §8 string, the settings strings and `sports_picker_style` |
| `ui/src/main/res/font/instrument_sans_bold_italic.ttf` (new) | §11; source commit and SHA-256 recorded in §11 |

### WP-1: core (Opus). `core/.../sports/` and `core/src/test/.../sports/` only

**Files:** `NhlMapper.kt`, `StackedLayout.kt`, `StackedTimeline.kt`, `NhlStackedColours.kt`, `GoalTimeline.kt`
(`GoalSpec.STACKED` values), `ScoreBugPlacement.kt` (style geometry, footprint).

**Tests:**
- `NhlMapperTest`, `ScoreBugMachineTest`, `SportsContractsTest`, `ScoreBugPlacementTest` (new cases);
- new: `StackedLayoutTest`, `StackedTimelineTest`, `GoalSpecTest`, `LegacyGoalTimeline.kt` (test source),
  `NhlStackedColoursTest`.

### WP-2: painters (Opus for `NineSlice` and the goal painter; Sonnet for `StackedPainter`). `ui/.../sports/`

**Files:** `StackedPainter.kt`, `StackedGoalPainter.kt` (incl. `bakeGlow(w, h)`), `NineSlice.kt`.

**Tests:** `StackedScreenshotTest`, `StackedFitTest`, `StackedPainterAllocationTest`.

### WP-3: wiring (Opus). `ui/.../sports/StackedBug.kt`

**Files:** `StackedBug.kt`: `StackedHolder`, `StackedRun`, the pin, S1 rules 1–7, `stackedPlaceAt`, `StackedPlaceHint`,
`StackedDescribe`, the glow cache and prewarm, logos at the takeover size.

**Tests:**
- `StackedDrawTest`, `StackedSeamTest`, `StackedBoundsTest`, `StackedLogoTest` (`ui/src/test/.../sports/`);
- `app/src/androidTest/kotlin/app/thebox/player/StackedFramesTest.kt`, written here and run on a device by the lead.

### WP-4: settings, quick bar, VM, fake panel (Sonnet)

**Files:**
- `core/.../settings/state/WpaPages.kt` (`sportsRows` `:584`, `applySports` `:742`);
- `core/.../settings/tree/SportsRows.kt` (add `STYLE` to `rows`);
- `core/.../full/state/PlayerStateMachineImpl.kt` (`scoreChoices` `:2074`, `scoreSelect` `:2143`; the S8 gating);
- `ui/.../full/vm/PlayerEffectsImpl.kt`;
- `ui/.../full/vm/PlayerViewModel.kt` (`ScoreFacts.style`);
- `tools/fakepanel/sports.py` (sog keys, the shootout formula, the docstring).

**Tests:**
- `core/src/test/resources/player_keys.csv`, `PlayerFiveKeyReachabilityTest.kt`, `ScoreBugPlayerTest.kt`;
- new `core/src/test/.../settings/SportsStyleRowTest.kt`;
- `tools/fakepanel/test_sports.py`.

### Integration (lead, after WP-1–4)

- `ui/src/test/.../sports/StackedStatesScreenshotTest.kt`;
- the §10.4 hand pass;
- the code review;
- record the owner's answers in `docs/decisions/2026-11-10-nhl-score-bug-stacked.md`.

### Disjointness (checked after the revision)

- **WP-1:** `core/.../sports/*` and its tests.
- **WP-2:** the three painter files and their tests.
- **WP-3:** `StackedBug.kt` and its tests.
- **WP-4:** settings, player-machine, VM and fake-panel files and their tests.

No file is in two WPs. Commit 0 lands first; its touches to `GoalTimeline.kt`, `ScoreBugPlacement.kt`, `StackedLayout.kt`,
`SportsRows.kt` and `PlayerStateMachineImpl.kt` are sequential, not concurrent. WP-2 and WP-3 code against commit 0's
stubs; WP-2's screenshot test uses hand-built frames.

---

## Lead sign-off (2026-11-10)

Each review finding was checked against `main` `87e22d4` before it was applied.

### Blockers

| ID | Disposition |
|---|---|
| B1 | **Accepted.** Verified at `ScoreBugMachine.kt:278–292`: stale and waiting overwrite `bottom`, so the period is lost. Added `ScoreBugView.period` and the one `view()` line, said plainly in §0, §2.1 and commit 0. Machine tests added. Every stale × state and waiting × state pair now has a §8 row. Stale × final cannot happen (`staleAt`, `:800–808`). |
| B2 | **Accepted.** Verified: the expression `when` in `PlayerText.choice` (`:478–501`), the statement `when` at `PlayerStateMachineImpl.kt:957–961`, and `ResourceSettingsTexts.idOf` (`:17`) are exhaustive. Commit 0 adds all three arms and their strings; WP-4 owns behaviour only. |
| B3 | **Accepted.** A PP carried into INT keeps the PP bar with its frozen time (ruling 19), and the clock column reads period / "INT" with no clock (ruling 33). The ordinary intermission follows the mock exactly. Pending owner (Q4b). |
| B4 | **Accepted.** The pin now snaps the bar height too. The glow is baked for the exact box per takeover (cached by `(w, h, size)`, LRU 2, prewarmed at layout rest). `NineSlice` is kept for the drop shadow only, with a ≥ 6σ + 1 source. The §4.2 "GA masks reused (§6.3)" claim is removed. The edge proof is re-run (§6). |

### Should-fix

| ID | Disposition |
|---|---|
| S1 | **Accepted in full.** §2.3 "Carried from the Pill runner", rules 1–7, each with a test. Stacked has its own holder; the Pill's `Holder.lastFrame` is not shared. The §7.2 "spans the intermission width" sentence was wrong and is replaced by the pinned pre-goal width. |
| S2 | **Accepted.** Verified the 12 + fallback list. Hairline on every bar; stripe-candidate rule with A and B ≥ 2.0 against the bar (computed: all pass, minimum 2.00 CBJ, A/B ≥ 1.51); asserted for all 32. Q6 (now Q3) corrected and given screenshots. |
| S3 | **Accepted with a different measure.** The home-team swap rule is adopted (lead decision, not an owner question), with an away fallback for the 3 pairs where the home swap is not enough. **Rejected: the review's "< 1.3 : 1" collision test.** Contrast ratio is luminance-only: it flags 126 pairs including red against blue (COL `#4479A2` vs NYR `#D53547`) and would swap stripes that are plainly different. CIELAB ΔE76 < 20 flags 50 pairs, every listed matchup among them, and all resolve. |
| S4 / M1 | **Accepted** as the default, pending owner (Q7). Rows anchored at the footprint's left edge in every corner; the right corners' floating right edge is worked out in §4.3 (gaps 59 / 0 / 90.5). |
| S5 | **Accepted.** Scale 0.45, bar text and tally 20 (Large 25), bar 33, footprint 392 × 141. The owner alternative is 0.50; 0.40 is withdrawn. |
| S6 | **Accepted.** Logos load at the takeover size; `StackedLogoTest`. |
| S7 | **Accepted.** Verified both restore paths (`RoomSettingsStore.kt:195–200`, `:274–280`, `:349–360`). General fix `coerceInputValues = true` in commit 0, with store tests for both paths. The per-field `NhlBugStyleSerializer` is **dropped as redundant**. The limits (collections and map keys) are stated. |
| S8 / M4 | **Accepted.** The row follows the followed game's league; the sheet stays open on toggle; "switches at once" is replaced by the per-corner statement. Verified the picker box at `PlayerUi.kt:84–87`. |
| S9 | **Accepted.** `GoalSpec` carries `endMs`, `popMs` and `stillFrameBMs`; `sampleStill` has a spec overload; CLASSIC writes 0 to `rowFlashAlpha`; `LegacyGoalTimeline` is compared bitwise on the 0.25-ms grid plus NaN and +∞; `TAKEOVER_TOTAL_MS` and `GoalSampler.Real` are pinned. The §2.3 sampler signature is fixed. |
| S10 | **Accepted as defaults.** The shootout is a break (pending owner, Q6); 5-on-4 → 5-on-3 is a text swap; stale (and waiting) in a break says "NO UPDATE" / "DELAYED" in the bar, with no reopening. |
| S11 | **Accepted.** Every listed test was added (§10). C7 now covers the pop colour: computed α .30–.55, every team ≥ 3.0. The fake panel's shootout SOG formula is fixed, with its byte change recorded. |

### Nits

| ID | Disposition |
|---|---|
| N1 | **Accepted, one part modified.** Fixed: `"12"` → 12; the "§6.3" glow reference; the whole `prefs` collected; the §3.3 SOG sentence; the §8 period rows (B1). **Modified:** only `LiveText` moves to `ScoreBugShell.kt`. `placeAt` and `PlaceHint` take the Pill's `Holder`, so sharing them would mean editing `ScoreBugWith`'s holder type; Stacked keeps its own ≈ 40-line copies instead. `NflBug` is left alone. |
| N2 | **Accepted** (lead ruling on Q16): a real Instrument Sans Bold Italic. Licence handling checked (§11). |
| N3 | **Accepted.** The rounded-corner trick only on corner regions; plain `drawRect` elsewhere. |
| N4 | **Noted.** The flash analysis agrees; the 3-flash limit stands (lead). |
| N5 | **Accepted.** Rows added: stale × end of period, shootout and PP-in-INT; waiting × break; OT intermission; goal while the bar is mid-open. Stale × final is shown to be impossible. |
| N6 | **Accepted.** The edge proof is re-run on the Stacked rects: 1.3 % / 1.4 %; the top-edge cut is stated. |
| N7 | **Accepted.** The shadow skips its centre; the layer sizes are stated. |
| N8 | **Accepted.** The sample is drawn at the anchored box; the hint uses the drawn box. |

### Missed questions

| ID | Disposition |
|---|---|
| M1 | Folded into S4; owner Q7. |
| M2 | Folded into S3; a lead decision. |
| M3 | Large offered for Stacked; the hand pass judges it; owner Q8. |
| M4 | Folded into S8; a lead decision (the sheet stays open). |
| M5 | Added to the hand pass (§10.4, broadcast check). |

### The review's owner-question table

The lead rulings close Q1, Q3, Q5, Q7, Q8a, Q10, Q12, Q13, Q14, Q15 and Q16. §12 keeps only:
- size;
- goal colours;
- the navy, black and grey teams' bars;
- the PP carried into intermission;
- end of period;
- shootout;
- still rows in the right corners;
- Large.

### Noted, out of scope

None. The first draft's out-of-scope note on the corner and size enums is now fixed by S7.

---

## Commit 0 notes (2026-11-10)

What commit 0 decided where this design was silent, ambiguous or wrong against the code. Each item is the smallest
choice that keeps the four WPs disjoint. With Pill (the default) the app behaves exactly as before; choosing Stacked
draws the Pill until WP-3 lands.

### Files: what exists after commit 0, and who owns it

1. **New, owned by the lead (no WP edits it): `ui/.../sports/StackedContracts.kt`.** It holds the WP-2 ↔ WP-3 seam:
   `StackedFrameState`, `BarContent` and `StackedDrawArgs`.
   - Why: WP-3 builds these and WP-2 draws them. If they lived in `StackedPainter.kt`, WP-2 could change a type that
     WP-3 constructs.
   - This adds a file to commit 0's §14 table. No WP's file list changes because of it.
2. **WP-2's three files exist as stubs with final signatures**: `StackedPainter.kt`, `StackedGoalPainter.kt` and
   `NineSlice.kt`.
   - Why: `StackedHooks` (in WP-3's `StackedBug.kt`) names `StackedGoalPainter` and `GlowMasks`. WP-3 also builds both
     painters and holds a `NineSlice`. Without the stubs, WP-3 could not compile alone.
   - WP-2 replaces the bodies. It may add private members. It must not change a non-private signature.
   - `NineSlice`'s insides are WP-2's: only the painters read them. WP-3 only holds the object.
3. **WP-3's `StackedBug.kt` exists.** It holds `StackedBug` (its body calls `ScoreBugWith`, exactly as the Pill branch
   does), `StackedHooks` (the design's list), `StackedSampler` and a minimal `StackedProbe`.
   - WP-3 owns all of it. It may add hooks and probe fields, as long as every default stays the real object.
   - `ScoreBug.kt` references only `StackedBug(...)` and `StackedHooks.Default`.
4. **`PlayerText.kt`** gains `scoreStyleRow(res, style)` and `styleName(res, style)` next to the `choice` arm (B2).
5. **Strings.** Besides the §8 list:
   - `sports_style_pill` and `sports_style_stacked` are added. They are the sheet's own value words, kept apart from the
     `settings_sports_style_*` strings as Size already is (`sports_size_*` vs `settings_sports_size_*`).
   - "FINAL", "FINAL/OT" and "FINAL/SO" reuse `sports_final*` upper-cased (`Locale.ROOT`). The PP bar words reuse
     `sports_pp_label` and `sports_pp_two_man`. "No update" and "Delayed" in the clock slot reuse `sports_no_update`
     and `sports_delayed`.
6. **Fixture.** `SportsFixtures.liveView` (`core/src/testFixtures/.../FakeScoreRepository.kt`) now sets `period` for an
   NHL game.
   - Three existing tests compare whole views against the machine: `centreColumnStates`,
     `powerPlayIsShownWhileLiveOrInIntermissionOnly` and `anNhlGameNeverCarriesAnNflView`. They failed on the new field
     alone.
   - Their test bodies are unedited.

### Contracts that the design left open

7. **`GoalFrame.rowFlashAlpha` is an envelope (0..1), not an alpha.** One field has to serve two cases: the per-team C7
   peak on the animated path, and the fixed .25 tint on the still path (§7.3). So:
   - the painter draws the stripe colour at α = `rowFlashAlpha × StackedColours.rowFlashAlpha` (animated);
   - it draws at α = `rowFlashAlpha × STACKED_STILL_ROW_TINT` when `StackedDrawArgs.still` is set;
   - STACKED's sampler writes 1 → 0 over `rowFlashMs` (ease-out), and 1 during the still version's frame B;
   - CLASSIC writes 0 on every sample.
8. **`GoalSpec` fields.**
   - The fields: `streak1Alpha`, `streak2Alpha`, `streakFrom`, `streakTo`, `flashAlpha`, `badgeMs`, `badgeMidScale`,
     `badgeMidDeg`, `letterMs`, `letterDrop`, `letterOvershoot`, `pulsePeak`, `tallySlide`, `popFromScale`, `popMs`,
     `rowFlashMs` (0 = none), `endMs` and `stillFrameBMs`.
   - The streak alphas are read by the painters, not the timeline. The Stacked streak widths stay painter constants
     (`StackedMock.STREAK*`).
   - Ends of each phase:
     - the pop runs from 3 600 to `3 600 + popMs`;
     - the animated `done` is `t ≥ endMs`;
     - the still version's frame B ends, and `done` turns true, at `3 600 + stillFrameBMs`.
   - For CLASSIC every one of these sums equals the old `END` (4 500) exactly.
   - `GoalSpec.STACKED` is a `CLASSIC.copy()` placeholder. Its §7.2 values are WP-1's.
9. **CLASSIC is bit-identical.**
   - Commit 0 checked it with a throwaway test. That test compared a frozen copy of the `87e22d4` `GoalTimeline` with
     the new one on every `GoalFrame` field (`Float.toBits`, booleans and `done` by equality).
   - Coverage:
     - inputs: t from −1 to 4 700 in 0.25-ms steps, plus NaN and +∞ (18 807 instants);
     - entry points: the old `sample` and `sampleStill` overloads and the `(GoalSpec.CLASSIC, …)` ones;
     - `rowFlashAlpha` stayed 0 throughout.
   - All passed. The test was then deleted, because the committed `LegacyGoalTimeline` + `GoalSpecTest` are WP-1's
     files (§14).
   - `GoalTimelineTest` is unedited and green.
10. **`StackedTimeline`.**
    - Commit 0 adds `StackedTransition { OPEN, CLOSE, CHANGE, SWAP, STILL }`. `CHANGE` and `SWAP` apply to the bar
      only; `STILL` snaps to `to`.
    - It also adds `totalMs(track, kind)`, the driver length including delays. The runner needs it, and the timeline
      owns the numbers.
    - `StackedFrame` channels are fractions of the open size: `sogWidth`, `clockWidth`, `barHeight` and the row stripes
      (0 = 3.6, 1 = 6.3).
    - Offsets are in Standard design px: `sogDx`, `stripeAX` / `stripeBX` and `barTextDx`.
    - `barBgMix` and `barSwapped` drive a change or a swap. The painter draws `StackedDrawArgs.barPrev` while
      `barSwapped` is false.
    - `boxW` and `barH` are real in commit 0: rows + fraction × each column, and fraction × `STACKED_BAR_H`.
    - The stub `sample` writes each transition's end state at once, and `totalMs` returns 0.
11. **`ClockSlot.Int` is renamed `ClockSlot.Intermission`.** A nested `Int` shadows `kotlin.Int` for anyone who
    star-imports `ClockSlot`.
12. **`StackedTargets` details.**
    - For `FINAL`, `barPeriod` is `PeriodLabel(finalKind, 1)` (REG → "FINAL", OT → "FINAL/OT", SO → "FINAL/SO").
    - `barSecs` is the PP's `secondsRemaining`, or null.
    - The stub `of()` returns the play state. The stub `sameLook()` is `a == b`. WP-1 implements both.
13. **Painter API** (WP-2 implements, WP-3 calls).
    - `StackedPainter(s, large, sansBold, barFont)` takes `s` with the Large factor folded in, as `ScoreBugPainter`
      does.
    - Its methods:
      - `frame(v, targets, res)`, once per shown view;
      - `fillBar(content, pair, awayAbbrev, homeAbbrev, res)`, when a bar content is captured;
      - `bakeShadow(): NineSlice?`, off the main thread;
      - `drawFrame(c, args)`;
      - `StackedPainter.barTypeface(context)`, which loads `R.font.instrument_sans_bold_italic`.
    - `StackedGoalPainter(s, large, goalFont, sansBold, word, spec = GoalSpec.STACKED)` has:
      - `begin(side, colours, badge, awayAbbrev, homeAbbrev, away, home, boxW)`, where `boxW` is the pinned
        Standard design px;
      - `bakeGlow(w, h): GlowMasks?`, where `w` and `h` are **device px** of the whole pinned box, the top part plus the
        open bar;
      - `drawGlow(c, g, glow, x, y)`;
      - `drawTakeover(c, g)`.
    - **Origin:** `drawFrame` draws with (0, 0) at the top-left of the box's top part. The bar is drawn below it, or
      above it at negative y when `up`.
14. **Placement.**
    - `ScoreBugPlacement` gains `cornerBox(corner, size, league, style)` and `width/height(size, league, style)`.
    - `place()` now calls the style overload.
    - The STACKED branch is a stub that returns the Pill's sizes. That way the commit-0 fallback (the Pill drawn by
      `StackedBug`) is placed exactly as the Pill.
    - For WP-1: the Large footprint is 490 × (135 + 41.25) = **176.25**. §4.3's "176" is rounded; use the exact value
      unless WP-1 records a reason to round.
    - **Integration order:** once WP-1's footprint lands, the commit-0 fallback would draw the 404-px pill inside the
      392-px footprint, 12 px past the right inset. So WP-3 must land with or before WP-1, never WP-1 alone on `main`.
15. **Store test.** `RoomSettingsStoreCoerceTest` has the four §1.3 cases: decode, a registered import and an
    unregistered import, each run with both documents. It also pins the stated limits:
    - a `null` for a non-null field coerces;
    - an unknown `League` map key in `SportsFollowDoc.meters` still fails that document.
16. **Constants.** §13 is in `SportsConstants`, and the mock geometry is in a top-level `StackedMock`.
    - Commit 0 also names the remaining §4.2, §6 and §7.1 numbers:
      - `STACKED_ROWS_W` / `STACKED_SOG_W` / `STACKED_CLOCK_W` (243 / 58.5 / 90, exact, not mock × 0.45f);
      - fit and padding: `STACKED_FIT_STEP_PX`, `STACKED_FIT_MIN_PX`, `STACKED_TAKEOVER_PAD_TIGHT`,
        `STACKED_TAKEOVER_SLACK_MIN`, `STACKED_HAIRLINE_W`;
      - fallback and colour rules: `STACKED_FAUX_ITALIC_SKEW_X`, `STACKED_STRIPE_WHITE_STEP`, `STACKED_BAR_BG_BLACK`;
      - text shadows: `STACKED_*_SHADOW_DY`, `STACKED_TEXT_SHADOW_ALPHA`;
      - every §7.1 duration and delay, split per property (`STACKED_SOG_*`, `STACKED_CLOCK_*`, `STACKED_BAR_*`,
        `STACKED_BAR_CHANGE_*`, `STACKED_BAR_SWAP_*`, `STACKED_ROW_STRIPE_MS`).
    - WP-1 may add constants in `SportsConstants.kt`. WP-2 and WP-3 read them and never edit the file.
17. **Font: the real italic** (recorded in §11). No faux-italic fallback was needed.
