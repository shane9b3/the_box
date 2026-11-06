# Score bug code review: UI, player wiring, settings (reviewer U)

Range: `cba71c3..730b7c5` (main at `730b7c5`). Scope: `ui/.../sports/*`; the FullScreenHost, AppRoot, MainActivity,
HeaderTabs, KeyRouterImpl and GuideStateMachineImpl changes; the score parts of PlayerStateMachineImpl, QuickBarModel,
PickerSheet and QuickBar; SportsRows, SettingsMachineImpl and WpaPages; `strings_sports.xml`; licences; key tables and
their tests. Reviewer C covers the repository, the machine and the data layer.

Method: I read the code against CLAUDE.md, the 2026-11-05 decision note, `docs/design/m6-score-bug.md` (including the
sign-off and the integration pass), the mock-up and the emulator screenshots. I did not run Gradle; every finding comes
from reading the code. Confidence: **H** = traced end to end in code; **M** = depends on framework behaviour that I
reasoned about but did not run; **L** = plausible, worth a check.

Clean, as far as I can tell:
- **SurfaceView rule.** The bug's `graphicsLayer`s (fade, clock) and GameCard's `clip` sit on sibling or leaf nodes. Nothing
  touches an ancestor of the root surface, and nothing uses real-time blur: the shadow is baked once with
  `BlurMaskFilter` on a software canvas.
- **FullScreenHost on bare video.** It does not recompose on polls: `PlayerStateMachineImpl.scores()` returns early
  unless the quick bar, a score sheet or place mode is showing.
- **Overlap rules.** Place mode only enters PLACE_SCORE from the sheet.
- **Text floors.** Every type size I found is ≥ 18 design px.
- **Settings.** The Sports section is advanced-only, and the operator precedence in WpaPages `apply` is right.
- **Tabs.** `TAB_*` and `SHOWN_TABS` are consistent.

---

## Blockers

### U-B1: the picker shows another day's games under "Today", indefinitely, after the Sports tab was left on Yesterday or Tomorrow (H)
**Where:** `ui/.../full/vm/PlayerEffectsImpl.kt` `scorePicker()` only reports demand. The reset that would fix this is in
`data/.../ScoreRepositoryImpl.kt:379` `pushDemand()`, and it only runs when `dayWanted == null`. The label comes from
`core/.../PlayerStateMachineImpl.kt` `scoreChoices()` (it always adds `ScoreTodayDivider`) and `strings_sports.xml`
`sports_picker_today`.

**Scenario:**
1. The user opens Sports and presses Yesterday. `showDay("2026-11-04")` sets `dayWanted = "2026-11-04"`.
2. The user presses Back and watches a channel.
3. The user opens the quick bar → Score bug. `SCORE_PICKER` demand rises, but `dayWanted != null`, so `dayKey` stays on
   yesterday and `/score/now` is never requested.
4. The sheet lists yesterday's finals under "Today". OK on one follows a finished game, and the user gets "That game is
   over…".

Tonight's games cannot be followed from the player until the user reopens the Sports tab: `SportsViewModel.onEntered()`
is the only caller of `showDay(null)`. The integration pass logged this as "open (minor)", on the assumption that "each
picker open re-requests `/score/now`". That assumption is wrong once `dayWanted` is set. This breaks the main flow the
owner asked for (follow a game you are not watching, from the player).

**Fix:** either of these.
- `scorePicker(true)` calls `repo.showDay(null)` (today, once per open, A-N10).
- `SportsViewModel.onLeft()` calls `showDay(null)` so no day sticks.

Also, label the divider from the board instead of a constant: "Today" only when `board.dayKey` is the board learned from
`/score/now`, else that board's date. Add a ScoreRepositoryImpl test for "tab picked Yesterday, then picker opens →
`/score/now`".

### U-B2: no team logos at all on API 25 (Fire OS 6), because `LogoTrim` reads `Bitmap.Config.HARDWARE` (H on the error; M on the outcome)
**Where:** `ui/.../sports/LogoTrim.kt:44`: `if (src.config == Bitmap.Config.HARDWARE) …`

**What breaks:** `Bitmap.Config.HARDWARE` is API 26. `minSdk` is 25. On API 25 the `getstatic` throws `NoSuchFieldError`
inside `transform()` on every logo, for both the bug and the cards. I expect Coil's `execute` (catch `Throwable` →
`ErrorResult`) and `FollowLogos.load` / `LogoPlate` (`runCatching`) to swallow it. If so, nothing crashes, but every API 25
device shows only the abbreviation discs. If any path does not catch an `Error`, it crashes.

Fire OS 6 (API 25) is a stated target. §11 lists "API 25 SVG" as unverified. The codebase already guards this case:
`ui/.../ambient/HueExtractor.kt:22` checks `SDK_INT >= 26 &&` before reading the field.

**Fix:** guard the check with `Build.VERSION.SDK_INT >= 26`, or drop it, since the request already sets
`allowHardware(false)`. Then check that lint `NewApi` is not baselined or disabled for `:ui`: it should have caught this.

---

## Should-fix

### U-S1: hiding the bug throws away its painter, baked glass and logos, so each reappearance re-bakes the glass and re-fetches the logos (H)
**Where:** `ui/.../sports/ScoreBug.kt:140`. The early `return` comes before:
- `remember(s, shownLarge) { ScoreBugPainter }` (:147);
- `remember(painter, lowEnd) { bakeGlass }` (:153);
- `remember(loader) { FollowLogos }` plus `DisposableEffect { logos.release() }` (:161, :172).

**What happens:** every time `place()` returns null, those slots leave the composition, `FollowLogos.release()` runs and
the glass bitmap is dropped. Hiding is frequent:
- the passive info bar on every zap, with a bottom corner;
- the channel list, with a left corner;
- the picker, with a right corner;
- toasts.

When the bug comes back:
1. It rebuilds the painter (text measuring loops) and re-bakes the shadow with a 24 px `BlurMaskFilter` on a software
   canvas, on the main thread, in the frame it reappears.
2. It re-rasterises both SVGs at 4× from the disk cache. The memory cache is off by design.
3. Until those decodes land, both logo slots are empty, and after 1.5 s the discs show.

So on every zap the bug fades back in with no logos for a moment. This contradicts A-S11: logos "held for the life of the
follow".

**Fix:** move the painter, glass and `FollowLogos` above the visibility return, keyed by size and follow URLs. Or keep
the node composed and drive visibility only through `fade`. Release the logos when the follow's URLs change, not when the
bug hides.

### U-S2: ScoreBug recomposes on every FullScreenHost recomposition, for example at the stats rate (H on the mechanism, M on the frequency)
**Where:**
- `ui/.../full/FullScreenHost.kt` in the `ScoreBug(...)` call: `scorePlacementInput(...)` and `PlayerUi.overlayGeometry()`
  build new instances on every host recomposition;
- `ui/.../full/PlayerUi.kt` `overlayGeometry()`.

**Why:** `PlacementInput`, `OverlayGeometry` and `Box` live in `:core`, which is not compiled with the Compose compiler,
and there is no stability config. So they are unstable, and strong skipping (Kotlin 2.4) compares them by identity. Any
FullScreenHost recomposition therefore recomposes ScoreBug. Each time, it re-runs `place()`, the `remember` lookups and
the effect keys. While the stats overlay is on, `ui.stats` republishes `PlayerUiState` about twice a second (the host's
own U-S5 comment), so the bug recomposes about twice a second too.

**Why the test misses it:** §4.7 asks for zero ScoreBug recompositions per tick and per poll. `ScoreBugDrawTest`'s
stand-in host never recomposes, so it cannot catch this.

**Fix:**
- hold `overlayGeometry()` in a `val` (it is constant);
- `remember` the `PlacementInput` keyed by its fields, or pass a `() -> PlacementInput` read inside;
- or add `:core`'s `app.thebox.core.sports.*` to a Compose stability config file;
- add a draw-test case where the host recomposes for an unrelated reason.

### U-S3: Retry in the Sports tab removes the focused node, and focus is never restored (H)
**Where:** `ui/.../sports/SportsScreen.kt`:
- `Message` (:420–436): the Retry chip only exists in `Failed`;
- the initial-focus `LaunchedEffect` (:138–148): `settled` is already true after the first non-Loading body.

**Scenario:**
1. The first load fails, and the Failed body shows.
2. The user presses Down to Retry, then OK.
3. The body becomes `Loading`, so the focused chip is disposed.
4. The board arrives and no initial focus runs, because `settled` is already true.

Focus is now on nothing. The first D-pad press has to recover it from the root, and the user sees no focus ring. If the
retry fails again, the new Retry chip is not focused either.

**Fix:** after a retry, move focus to the selected day chip before the body changes. Or reset `settled` on
`onRetry` / day change, so the initial-focus rule runs again for the next board.

### U-S4: the quick-bar value and the picker's followed row read as "Starts 7:00 PM" for a game that is live but whose bug is hidden (H)
**Where:**
- `ui/.../sports/SportsText.kt:141–149` `quickValue`: view null → "Starts …";
- `core/.../PlayerStateMachineImpl.kt` `stubOf()`: view null → phase `SCHEDULED`.

**Scenario:** the machine's `view()` returns null for the stale-hide case too (`ScoreBugMachine.view()`:
`now >= staleAt + HIDE_AFTER_MS`), not only before puck drop. When the feed stalls mid-game, the quick bar says
"Score bug · Starts 7:00 PM" and the sheet's ✓ row reads "CGY – EDM · 7:00 PM". The game is in its 2nd period.

**Fix:** decide by the followed game's own phase or start time, not by whether a bug exists:
- `now < startUtcMs` → "Starts …";
- otherwise → the last known score, or "No update".

### U-S5: the picker's time format ignores the app's clock setting (H)
**Where:** `ui/.../full/PickerSheet.kt` (the `clock24` line): `DateFormat.is24HourFormat(context)`.

**What happens:** the app has its own `ClockMode` (SYSTEM / H12 / H24), and FullScreenHost already passes
`settings.clock24` to `QuickBar`. With the app set to 24 h on a device set to 12 h:
- the quick bar says "Starts 19:00";
- the sheet one level down says "CGY – EDM · 7:00 PM".

**Fix:** add a `clock24` parameter to `PickerSheet` and pass it from FullScreenHost, like `QuickBar`.

### U-S6: Sports-tab cards re-rasterise every SVG logo on every composition, with no memory cache (M)
**Where:** `ui/.../sports/GameCard.kt:160–168` `LogoPlate` calls `coilLogoFetch`, which uses
`memoryCachePolicy(DISABLED)` and a 4× raster.

**What happens:** each card composition decodes two SVGs from the disk cache with AndroidSVG. That means up to 32 decodes
when the tab opens, again on every day-chip change, and again when `LazyVerticalGrid` recomposes items scrolled back into
view. The abbreviation shows until each decode lands, so logos pop in on every open. On a Fire TV Stick this is visible.
The memory-cache bypass was meant only for the bug's two held bitmaps (A-S11).

**Fix:** give the cards a small in-process LRU of trimmed bitmaps keyed by URL and size (about 64 entries, a few hundred
KB). Or let card requests use Coil's memory cache with the `LogoTrim` cache key.

### U-S7: the clock layer is not the only reader of `secs`, because the merged semantics read it too (M)
**Where:** `ui/.../sports/ScoreBug.kt:224`: `.semantics(mergeDescendants = true) { contentDescription = desc.text(secs.intValue) }`

**What happens:** semantics reads are snapshot-observed in current Compose. Every tick invalidates the bug's semantics.
Whenever semantics are being computed (TalkBack, or content capture on some devices), each tick:
- formats a string (`getString` with six args);
- sends a content-changed accessibility event about once a second over video.

That breaks §4.7's "a tick re-records only that layer". It also means `ScoreBugDrawTest`'s "clock-layer draws" count
(which counts `TICKS_SCORE_CLOCK` state writes, see U-N6) cannot see this.

**Fix:** build the description from the snapshot clock, not the ticking second. Update it on view changes, or at most
once a minute.

### U-S8: the follow toast "Sports is off. The score is hidden." has no next step (H)
**Where:** `strings_sports.xml` `sports_toast_sports_off`.

**Why it matters:** CLAUDE.md requires every user-facing error to carry a reason and a next step. This toast tells the
user their follow was cleared, but not how to get it back.

**Fix:** "Sports is off, so the score is hidden. Turn on Show Sports in Settings → Sports to follow a game again."

`sports_toast_followed_final` is informational and is fine as it is.

---

## Nits

### U-N1: the clock's fit check ignores the divider clearance (H)
**Where:** `ScoreBugPainter.kt:100`. The `widest()` loop compares "88:88" against `SCORE_CENTRE_W`, not
`SCORE_CENTRE_W - FIT_BREATHING`. The centre strings got 8 px of clearance in the integration pass, but a wide-digit
fallback typeface could still let "18:88" touch the dividers.

**Fix:** use the same budget as `fit()`.

### U-N2: an error or an external tune in place mode discards the move silently (H)
**Where:** `PlayerStateMachineImpl.closeAll()` sets `place = null`. While placing, a playback error, `leaveFull`, or a
reminder's auto-tune (via `enterFull`) drops the corner the user moved to. Nothing is saved and nothing tells them. Also,
`placeKey` swallows PLAY_PAUSE and the other media keys, so the user cannot pause while placing.

**Fix (low priority):**
- save `place.corner` in `closeAll` when `layer == PLACE_SCORE`;
- let media keys fall through to `bareKey`;
- add `player_keys.csv` rows for PLAY_PAUSE in place mode.

### U-N3: the picker's live game rows never tick (H)
**Where:** `SportsText.pickerGame` → `state(…)` without `secs`. The rows show the board snapshot's clock ("2nd 10:22"),
which is refreshed only at the day-list cadence. The cards and the bug tick; the sheet does not.

**Fix:** accept this and drop the seconds in picker rows (show "2nd" only), or tick them the same way the cards do.

### U-N4: the Corner, Delay and day chips stay active when Show Sports is off (H)
**Where:** `SportsScreen.kt`, with `SportsBody.Off`. The body says Sports is off, but the user can still open the
Corner / Delay lists and switch days. The day switch posts `showDay` to a repository that will not fetch, so the
selection changes and nothing else does.

**Fix:** hide or disable the chips when the body is `Off`, and leave focus on nothing focusable except, at most, a
"Settings" hint.

### U-N5: day-key learning is a side effect inside `combine`, and it is order-dependent (L)
**Where:** `SportsViewModel.kt:93–96`, `155`, `162–175`. `learn(b)` mutates `yesterdayKey` / `tomorrowKey` from inside the
flow transform. `onEntered()` sets `selected = TODAY` before it nulls the keys. Correctness depends on `Main.immediate`
running the combine synchronously in between.

If the combine ran after the reset with the previous Yesterday board still in `repo.day`, it would learn that board's
neighbours. Then `isFor(todayBoard, TODAY)` would be false, and the tab would show skeletons until a chip change.

**Fix:**
- derive the keys from the board the repository says is "today" (for example, `DayBoard.isToday` from `/score/now`);
- or reset the keys before setting `selected`;
- add a test that re-enters with a stale Yesterday board.

### U-N6: what the tests assert (H)
- `ScoreBugDrawTest` checks only that the displayed second changed 60 times (`TICKS_SCORE_CLOCK`). That number is set by
  the loop itself, so it cannot fail for the reason the test is named after: "only the clock layer redraws". Its "three
  polls" change only `clock`, never `bug`, and the stand-in host never recomposes (see U-S2). Rename the test, or add:
  - a `bug` re-emission with equal text;
  - a host recomposition;
  - a semantics-read check (U-S7).
- `player_keys.csv` has no row for the AFTER_SUBTITLES position of the item. `quick@SCORE_BUG RIGHT → SETTINGS` covers
  only the not-following slot. It also has no row for "follow ends while the sheet is open" (focus repair in `scores()`).
  The latter is an event rather than a key, so it belongs in `ScoreBugPlayerTest`.
- `guide/keys.csv` `sports,sports,OPEN_GUIDE,SPORTS,TABS` encodes that the Guide key does nothing in Sports. That matches
  Search, but in Settings the Guide key returns to the grid ("N11"). Confirm this is intended: a Guide key that does
  nothing in a full-screen tab reads as broken. The router never forwards it in SPORTS (`KeyRouterImpl.kt:171`).

### U-N7: the Sports section sits after Network, not after Playback (H)
**Where:** `SettingsContracts.kt` `SectionId` order. The integration pass recorded this and left it as merged. Also record
it in the design §7, so the documented order matches the code.

### U-N8: Large size uses two scale factors (L)
**Where:** `ScoreBug.kt:147–150`. The content scales by `1.25`, but the pill is the hand-set 506 × 88 (404 × 1.25 = 505,
70 × 1.25 = 87.5). The 1 px and 0.5 px differences put the right-side content about 1 device px off centre at Large. This
is cosmetic.

---

## Counts
Blockers 2 (U-B1, U-B2), should-fix 8 (U-S1…U-S8), nits 8 (U-N1…U-N8).
