# NFL Stacked score bug: review U (runner, painters, UI)

Reviewer U, 2026-11-16. Branch `nfl-stacked` at `e91af67`, diff `main...nfl-stacked`, limited to `ui/.../sports/`
(StackedNflBug, StackedNflContracts, StackedNflPainter, StackedNflScorePainter, NflText, NflBug and ScoreBug edits),
`strings_sports.xml`, `ui/build.gradle.kts`, the golden hash additions, `StackedNflFramesTest` and the ui tests. `core/`
belongs to the other reviewer; cross-boundary notes are marked **(core)**.

## Verdict: ready after fixes

The runner is a close and careful copy of the NHL Stacked runner. Its rules 1 to 7, the pin, the glow cache and the
idle behaviour match the precedent, and I found no lifecycle or threading bug that the precedent does not share. The
surface rule holds. The painters do not allocate on the draw path. The one real problem is visual. In the XP and FG
takeovers the goalposts sit on the "+N" / tally block, and in a mirrored (right-hand) corner a goalpost upright runs
through the points digit, so "+3" reads as "+8". Top right is the default corner, so every field goal and extra point
shows this to most users for about 1.4 s. The mock does not do this: its posts overlap the end of the word, not the
block. A small painter fix solves it (U-B1). The other findings are test and golden-file accuracy, one accessibility
assumption that only a device can confirm, and nits.

## Blocker

### U-B1: the goalposts cross the "+N" and the tally, and in a mirrored corner "+3" reads as "+8"

- **Where:** `StackedNflScorePainter.kt:441-452` (posts at `spec.postsX * s * k`) and `layout()` / `G.takeover`
  (`StackedNflLayout.kt:387-398`, block at `boxW − pad − blockW`).
- **Seen:** `nfl-stacked-takeover-strip-large-br.png`, row 3 (FIELD GOAL), columns t = 1 500 and t = 2 025: the
  left-hand post upright runs through the "3" of "+3", which reads "+8", and the other upright crosses the "7" of
  "27 – 17". Row 2 (EXTRA POINT) at the same times: the upright goes through "+1". `nfl-stacked-takeover-strip-std-tl.png`,
  rows 2 and 3, columns t = 1 500 / 2 025: the upright sits between "POINT"/"GOAL" and the tally, touching "25"/"27",
  and the crossbar runs through the T of POINT. During the posts hit (1 800–3 150) the posts reach α .95 white, the same
  as the white "+N", so the text is not separated from them.
- **Why:** the posts are at the mock's x (234 to 274.5 on the 333 box). The block is wider than the mock's because the
  tally is 20 px with an "88 – 88" slot of 67.7 (ST S5), so it starts at 333 − 16.2 − 67.7 = **249.1**. That puts about
  25 design px of the posts on the block. In the mock the tally is about 13 px and starts at about 271, the posts end at
  about 268, and the posts overlap only the last letters of the word. **Integration ruling 17 ("the mock overlaps and
  clips there too") is therefore not right about what overlaps.** The mock overlaps the word, not the score.
- **Fix (keeps the mock's intent: the posts behind the end of the word, the ball arcing through them):** in `layout()`,
  compute `postsDx = min(0, blockLeft − gap − (postsX·k + postsW)·s)`, which is about −54 Std px, and shift the posts by
  `postsDx` (mirrored: by `−postsDx`). Bend the arc's end by the same amount, so the ball still goes through the
  uprights: `x' = x + postsDx · clamp((x − p0x·k)/(p2x·k − p0x·k), 0, 1)`. Draw order stays the same (posts under the
  word), which is exactly what the mock does. Add a `StackedNflFitTest` assertion that the posts' drawn rect never
  intersects `blockLeft…blockRightEdge` for XP and FG, at both sizes and both mirror states, at 243 and 333 wide.

## Should-fix

### U-S1: the still and pair golden shots record states the runner never draws

- **Where:** `StackedNflScreenshotTest.kt:310-318` and `:296-306`. **Seen:** `nfl-stacked-takeover-still-break-sheet.png`.
- **Scenarios:**
  - **Still pair:** the cells at t = 6 000 (A2, the EXTRA POINT segment) and t = 9 000 (B2) show the tally "30 – 17" and
    the digit 30, where the real values are 31. The bar also still reads "TOUCHDOWN | KC +6" where it should read
    "EXTRA POINT | KC +1". The test builds the segment's `NflScore` from the shown body's digits (`stt.awayScore`), and
    the look's bar key is fixed.
  - **Takeover on the 243 break box:** the bar keeps "HALFTIME" during the takeover. The runner re-keys an open pinned
    bar to the score label once the lead covers the box.

  These shots are in the hash list. The owner's §9.3 review is still due, so they will be read as the intended look.
- **Fix:**
  - In `render`, take the segment's `NflScore` (tallies and kind) from the `Take` and not from the shown body.
  - Give the still and break looks the same bar key schedule as the pair strip (score label from 216 or frame A,
    "EXTRA POINT" between the segments, label B from B's cover).
  - Re-record those 6 shots and the sheet.

### U-S2: the zero-width-space re-announcement (integration ruling 7) is unproven and fragile

- **Where:** `StackedNflBug.kt:1318-1324`, `FLAG_TEXT_MARK`.
- **What happens:** Compose does send `TYPE_WINDOW_CONTENT_CHANGED` (CONTENT_DESCRIPTION) when the string differs only
  by a U+200B. Whether TalkBack then speaks it again depends on how it de-duplicates live-region utterances. If the
  utterance is compared after format characters (Unicode Cf) are stripped or after normalisation, the second
  back-to-back flag is still silent. `backToBackFlagsAreEachAnnounced` only proves the strings differ. Nothing proves
  TalkBack speaks.
- **Cleaner fix (also better UX):** make the difference a real word. Use a second string, `sports_live_nfl_flag_again` =
  "Another flag on the play.", for a flag that directly follows another (the same parity rule). It is distinct for any
  de-duplication and it tells the listener something true. If the wording must stay identical, alternating the final
  punctuation ("Flag on the play." / "Flag on the play!") is more robust than an invisible character. Whatever is
  chosen needs a one-time TalkBack check on a Fire TV (device-only, see below).

### U-S3: the golden set is out of proportion: 827 lines, 72 exact duplicates, 21 sheets that duplicate shots

- **Where:** `sports-hashes.txt` (+830 lines; NHL Stacked has 310).
- **Measured:** 755 unique hashes among the 827 new lines. Eight hashes appear 7 times each: the t = 0 cell of every
  kind and of the pair is the pre-score "play" body, so it is hashed 7 times per corner and size. The 21 contact sheets
  are hashed too, so any pixel change re-records both the shot and every sheet that contains it. The grey sheets repeat
  the ice and dark sheets for the top corners. Every future tweak will churn hundreds of lines, which makes the gate
  noise.
- **Fix:**
  - Drop t = 0 from the takeover strips (it is the `play` look).
  - Keep the sheets as review artefacts, but exclude `*contact-sheet*` and `*strip*` from `sportsHashGate` (or write
    them outside `score-bug-stacked-nfl/`).
  - Hash grey for the TL corner only.

  That roughly halves the set with no loss of coverage.

## Nits

- **U-N1, per-frame text measurement in the bar** (`StackedNflPainter.kt:550`): `drawBar` calls `labelW(cur)` and
  `subW(cur)` (two `measureText` calls) on every frame, and `barInk` does the same. It does not allocate, but it is
  avoidable CPU during every REKEY and the 8-s flag hold. Cache `labelW` and `subW` in `NflBarContent` at `fillBar`
  (next to `wordsAdvance`) and copy them in `copyFrom`.
- **U-N2, a score before the takeover painter exists is dropped silently** (`StackedNflBug.kt:330-346`). The painter
  is built off the main thread after the first follow composition and after every size change (the Barlow load). A
  `goalSeq` bump in that window updates `lastSeq`, releases the pin and never plays. That is unlikely (a follow starts
  at a non-score view), but it is silent. Log it in debug builds, or keep the bump pending until `scorePainter` lands
  if it lands within `LOGO_WAIT_MS`.
- **U-N3, the origin y is not pixel-snapped** (`StackedNflBug.kt:482`). `originDy = barRest·d` is 41.25 px for a
  Large bottom corner at 1080p (33 × 1.25), so the rows, the 1-px border and the row divider sit on a quarter pixel and
  smear over two device rows. x is snapped (`stackedOriginDx`) but y is not. This is inherited from `StackedBug`
  (`:451`); fix both with `roundToInt()` on `(m + tagY)·d + barRest·d`, minus the spacer's rounded offset.
- **U-N4, the instrumented idle gate may be flaky** (`StackedNflFramesTest.kt:120-134`). "≤ 1 frame in 10 s" after the
  burst assumes no new snapshot arrives. The NFL poll floor is 5 s, and a clock-only snapshot recomposes and redraws
  once (by design: "one composition per snapshot"). If the fake panel advances the clock between plays, two polls in
  10 s give two frames and the gate fails. Either gate on `DRAWS_STACKED_NFL_BUG` versus the snapshot count, or allow
  one frame per poll.
- **U-N5, the gap in the contact-sheet coverage for the fallback disc:** every sheet uses `StackedNflTestKit.logo`, a
  synthetic oval with a white bar for any team without an asset (IND, NE, DAL, …; the "⊖" discs in rows 8 and 9). The
  real `LogoDisc` (translucent disc with the code) appears only in the e2e frames. Add one look with `awayLogo = null`
  and one with a 4-letter capsule code so the hash gate covers the real fallback. Also, the spec text "LogoDisc in the
  stripe colour" (§5.4) matches only the capsule case. The 2-to-3-letter disc is translucent white, as in the hockey bug.
  That is fine, but correct the sentence.
- **U-N6, hard-coded English on the draw path:** the tally format `"${away} – ${home}"` and `"+${points}"`
  (`StackedNflScorePainter.kt:269-272`) and the `WORDS` atlas are not resources. The WORDS atlas is inherited from the
  Pill and accepted. `sports_stacked_nfl_left` ("%1$d LEFT") is not a `<plurals>`. Both are fine for the English-only
  v1. They are also reached through `res.getString(…, %d)`, which formats with the device locale: on an Arabic-locale
  device the bar's yard line and "N LEFT" use Arabic-Indic digits (fallback glyphs, not Instrument Sans) beside Western
  score digits. The Pill shares this through `sports_down_and`. Format digits with `Locale.ROOT`, or build them as
  chars.
- **U-N7, a resumed takeover replays late:** the drivers run on the composition's frame clock, which pauses at
  `ON_STOP`. A takeover (or the flag hold) that was running when the app went to the background finishes when it comes
  back, possibly minutes later. Screen-off already hides the bug, so this is only Home and back. The precedent shares
  it. If wanted, cancel the takeover from a lifecycle `ON_STOP` observer (same path as hide).
- **U-N8, three digits at the floor size:** "100" is drawn at 24.5 against "99" at 35.1 (`three-digit` cells). This
  follows §5.4 but looks unbalanced. It is an owner eye-check item, not a code defect.
- **U-N9, 720p legibility (device-only):** at s = 0.667 the possession ball is about 9 px, the pips are 2 px tall
  (3 × .667), and the stripe widening is 2.4 → 4.2 px. This matches the mock, but check it on a 720p Fire TV Stick.
- **U-N10, a small allocation in `pin()`** (`StackedNflBug.kt:950`): `arrayOf(clock, barTrack)` allocates once per
  score (copied from `StackedBug`). It is harmless but trivially avoidable.

## Visual defects list (sheet file and cell; cells are read left to right, top to bottom)

| # | Sheet | Cell | Defect |
|---|---|---|---|
| V1 | `nfl-stacked-takeover-strip-large-br.png` | row 3 (FG), t 1 500 and 2 025 | the post upright through the "3" of "+3", which reads "+8"; the second upright through the "7" of "27 – 17" (U-B1) |
| V2 | `nfl-stacked-takeover-strip-large-br.png` | row 2 (XP), t 1 500 and 2 025 | the upright through "+1" and the start of "EXTRA POINT" (U-B1) |
| V3 | `nfl-stacked-takeover-strip-std-tl.png` | rows 2 and 3, t 1 500 and 2 025 | the posts fill the gap between the word and the tally; the crossbar crosses the T of POINT and the right upright touches "25"/"27" (U-B1) |
| V4 | `nfl-stacked-takeover-still-break-sheet.png` | row 1, cells 3 and 4 (still pair t 6 000 / 9 000) | tally "30 – 17" and digit 30 for the extra point (should be 31); bar still "TOUCHDOWN \| KC +6" (U-S1, test artefact) |
| V5 | `nfl-stacked-takeover-still-break-sheet.png` | row 2 (break, t 450 / 1 500) | bar "HALFTIME" under the takeover; the runner shows the score label (U-S1, test artefact) |
| V6 | all contact sheets | rows 8 and 9 (IND, NE, DAL, NYG, PHI, LV, PIT, CIN, DEN) | the logos are the test kit's synthetic ovals, not the real `LogoDisc`; the real fallback is not in the gate (U-N5) |
| V7 | `…-large-dark-bl/br` | every cell | rows on a quarter-pixel y (41.25) at Large bottom corners: the border and divider are slightly soft (U-N3; inherited) |

Checked and correct:
- Proportions and the row element order against `01-play.png` / `05-after-td-pop.png` / `25-xp-1700.png`.
- Mirrored order from the screen edge (stripe, logo, code, ball, score, then the clock column).
- Glyphs, logos and the ball are never flipped; the "/" lean is kept on the bar stripes in all four corners.
- The red-zone patch is under the sub text with the stripes kept, mirrored, per ruling 4.
- The flag icon comes before the words in reading order on both sides.
- The wipes start at the right when mirrored; the TD ball runs right to left when mirrored.
- The bar is above the rows in bottom corners (R16, as the NHL baseline).
- The break box (243) aligns to the outer edge.
- Stale and delayed: the column says "No update" on two lines or "Delayed"; in a break the bar says NO UPDATE /
  DELAYED.
- The `open-t200` empty-bar cell is deliberate (ruling 15).
- The dark-bar matchups (IND–NE, DAL–NYG, LV–PIT) are legible through the hairline and the bright stripes.
- The gold KC stripe and red BUF stripe differ from the mock, as documented in §6.
- Ice and grey backgrounds: the shadow and border keep the box distinct.
- The pop and row flash at 3 600 (olive KC row, gold 30), as specified ("stripe colour at C7 α").

## Runner, surfaces, painters, shared code: what I checked and found sound

- **Surface rule:**
  - The only `graphicsLayer { alpha; clip = true }` is at `StackedNflBug.kt:549`. It sits on the bug's own `Box`, whose
    only children are two `Spacer`s (the `drawBehind` node and the live region). It is a leaf and cannot be an ancestor
    of the root `SurfaceView` (an Android `View` outside this Compose subtree).
  - The place hint (`:1373`) is a sibling with alpha only.
  - This is the same structure as `StackedBug.kt:506/1201`.
  - No blur at draw time: the glow masks are baked off the main thread (`BlurMaskFilter` only inside `bakeMask`), and the
    shadow is a nine-slice.
- **Transitions:** each track is keyed on its own target (`evalBool`, `evalBar`, `evalPips`). A clock-only snapshot
  changes no key, so it only rebuilds the body (`remember(shownView, …)`) and redraws once. The drivers are read only
  in draw. When idle, nothing is scheduled; the flag hold ends at 8 s and `flagDone` stops reading the driver.
- **Hide, place mode, size, new game, follow end:** each cancels the takeover, releases the pin with a snap, parks the
  tracks after the fade (`park` also stops the flag job) and snaps on reappearance. A score while hidden updates
  `lastSeq` and is never replayed.
- **Takeover sequencing:** single (release at 3 600, end at 4 800); pair (interim at 3 600, B begins at 4 800, B
  covers at 5 016, release at 8 400, end at 9 600); restart mid-takeover keeps the pin (the `finally` clears only for
  the current token); a score during a flag re-keys the pinned bar and stops the flag; marks that start during the pin
  show at the release only if still active. The still path uses frames A and B with unscaled delays.
- **Threads and bitmaps:**
  - The painters and masks are built on `Dispatchers.Default` from immutable painter fields; results are published on
    main and the stale ones are discarded by an identity check.
  - The glow LRU holds 2 masks per size, dropped on size change and follow end; GC-managed with no `recycle`, as in the
    precedent.
  - Worst case Large at 4K (s = 2.5): about 1 136 × 656 ALPHA_8 ≈ 745 KB per mask, 2 masks × 2 boxes ≈ 3 MB.
    Acceptable.
- **Painters:**
  - No allocation in `drawFrame`, `drawGlow` or `drawTakeover`: inline KTX `withClip` / `withTranslation`, preallocated
    rects, Paths and frames, chars drawn from arrays, score and code fits cached per string.
  - Every save is inside a `with*` block, so save/restore is balanced.
  - Hairlines are ≥ 1 device px; the divider is ≥ 1 px.
  - The clock is tabular, digit by digit.
  - Draw order: glow, shadow, fill, bar, rows, column, takeover, then the border last (ruling 1).
  - A null typeface falls back to the system font, with faux italic for the bar.
  - `lerpColour` works on opaque channels only, and alpha is applied once through `argb`.
- **Shared code:**
  - `ScoreBug.kt` now applies `prefs.nhlStyle` to the NFL, which is intended (decision 1). Pill users get the same call
    and key.
  - The `NflBug.kt` helpers moved verbatim (`kindWord` still `lowercase(Locale.getDefault())`). Live text, description
    and timing of the Pill are unchanged.
  - A hockey bug's style key is unchanged.
- **Accessibility:** a semantics description on the drawn box (plus the timeouts) and a separate polite live region,
  both on the drawn box. Layout direction is pinned LTR. Bar text is upper-cased with `Locale.ROOT`.

## Tests

- **Strong:**
  - `StackedNflDrawTest` covers rules 1 to 7, the pin, pairs, stills and idle through the real tables, transitions and
    score sampler.
  - `StackedNflEndToEndTest` runs the real machine, runner and painters.
  - The allocation test exercises the takeover, REKEY, burn and flag, mirrored and up.
- **Weak:**
  - `everyStateInEveryCorner…` and `theTakeoverForEveryKind…` assert only that a file is more than 10 KB (the hash gate
    is the real check).
  - The painters' once-per-change calls are recording fakes in the runner tests, so the begin arguments are trusted
    there and checked only end to end.
  - `backToBackFlagsAreEachAnnounced` checks strings, not speech (U-S2).
  - The allocation limit of 4 096 B per 300 frames would let a 12-byte-per-frame leak through. This is shared with the
    precedent.
- **Instrumented `StackedNflFramesTest`:**
  - It needs the fake panel and a manual follow, and its assumptions are documented.
  - It arms on panel state and then on a draw burst, so the measured window starts about 0.5 s into the takeover and
    runs past its end. That is acceptable.
  - See U-N4 for the idle gate.
  - Style is restored in `finally`.

## Strings

- The new strings are consistent with the NFL Pill: upper-case bar words, the Pill's down texts reused, "Timeout,
  Bills." / "Flag on the play." in the Pill's live-text style, and sentence-case column words "No update" / "Delayed"
  per the ST ruling.
- The settings description was updated for both sports.
- Hard-coded text is only on the paths noted in U-N6.

## How each item was verified

- **Looked at (Read tool, crops with ffmpeg):**
  - The contact sheets: std dark TL, std dark BR, large dark TR, large ice.
  - The takeover strips: std TL, large BR.
  - The still and break sheet, and the e2e frames 01 to 13 and the corner frames.
  - Against the mock frames 01, 05, 07 and 25.
- **Read:** every in-scope source file listed above, the spec §0, §1, §4.3 to §5.4, the Commit 0 and Integration
  rulings, and the NHL precedent at the matching points.
- **Ran:** no Gradle tests. The defects are visible in the rendered output and follow from the constants; no scratch
  test was needed. I computed the duplicate count in the golden list with `git diff | sort | uniq`.
- **Not checkable here (device-only):**
  - Whether TalkBack speaks the ZWSP variant (U-S2).
  - Frame cost on a Fire TV (the `StackedNflFramesTest` matrix).
  - 720p legibility of the ball and pips (U-N9).
  - Whether the frame clock pauses on Home on Fire OS (U-N7).
  - Font fallback on a device without the bundled fonts.

## Fix pass (2026-11-16)

Lead rulings applied. The decisions are in `docs/design/m6-nfl-stacked.md` § "Review fix pass (2026-11-16)". The
regenerated sheets are in `/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/nfl-stacked/fix/`.

| ID | Result | Where |
|---|---|---|
| U-B1 (V1–V3) | **Fixed.** The posts' ink ends 13.5 before the "+N" / tally block, the arc's end bends by the same shift, and the posts stay under the word. At Standard 333 FG the shift is ≈ −25. The posts' ink was 239.7–268.8 against a block at 257.3; it is now 214.8–243.8. | `StackedNflGeometry.postsShift` / `bendArc` (`StackedNflLayout.kt:432`, `:441`), `NflShapes.POSTS_INK_*`, `StackedNflScorePainter.kt:326`, `:465`, `:474`. Tests: `StackedNflFitTest.theGoalpostsNeverCrossTheScoreAndTheBallPassesBetweenThem` (`:209`: XP / FG × 4 corners × 2 sizes × 333 / 243 × 2 tallies, every 5 ms, pair and still, real sprite ink against the drawn block, ball inside the uprights) and `theGoalpostsShiftNumbers` (`:274`). Integration ruling 17 replaced. The strips were checked by eye in all four corners at both sizes. |
| U-S1 (V4, V5) | **Fixed through the runner's own sequencing.** The new pure `StackedNflPinSchedule` (`StackedNflBug.kt:603`) holds the cover, release and B-cover times, the body, the segment and the pinned bar key. The runner uses it with unchanged values, and the screenshot test builds every takeover look from it and from the machine's `NflScore`s (`StackedNflScreenshotTest.kt:334`). A2 is now "EXTRA POINT \| KC +1" over 31, and B2 is the released bar over 31. **Found by this:** the break box's score label "TOUCHDOWN \| KC +6" clipped at 243. Pinned at the break width, the label is now the word alone (`StackedNflBug.kt:617`), and the fit test covers it. | |
| U-S2 | **Fixed:** the zero-width space is gone. A flag right after "Flag on the play." reads "Another flag on the play." (`strings_sports.xml:319`), alternating; a flag after a gap reads the first wording again. | `StackedNflBug.kt:1376-1383`; `StackedNflDrawTest.backToBackFlagsAreEachAnnounced` (`:643`). The TalkBack check on a Fire TV is still due (device). |
| U-S3 | **Fixed:** NFL Stacked golden lines 827 → 406. There is no t = 0 cell. Sheets and strips are not hashed: they go to `ui/build/score-bug-stacked-nfl-review/` and the scratchpad. Ice and grey are hashed in TL only. Looks are hashed in TL and BR at both sizes, plus 4 looks in TR and BL. Strips are hashed in TL and BR. No other line of `sports-hashes.txt` changed. | `StackedNflScreenshotTest.kt:277`, `:334`. |
| U-N1 | **Fixed:** `labelW` / `subW` are cached at `fillBar` and copied in `copyFrom`. | `StackedNflContracts.kt:88`, `StackedNflPainter.kt:447`. |
| U-N2 | **Fixed as the NHL Stacked runner does:** the new score shows at once with no takeover (not queued). The probe counts it. | `StackedNflBug.kt:345`, `:772`; `StackedNflDrawTest.aScoreBeforeTheTakeoverPainterShowsTheScoreAtOnce` (`:669`). |
| U-N3 / V7 | **Not applied (lead):** inherited from `StackedBug`; fix both together. | |
| U-N4 | **Fixed:** the idle gate allows one frame per composition in the window, plus one. | `StackedNflFramesTest.kt:127`. |
| U-N5 (V6) | **Fixed:** the real `LogoDisc` is drawn for teams without a mock asset (`StackedNflTestKit.logoOrNull`), and a `fallback-capsule` look ("WASH") was added. The §5.4 sentence is corrected. | |
| U-N6 | **Fixed (matched to the Pill):** the Pill builds the tally and "+N" in code with ASCII digits, so those stay code. The Stacked bar's `%d` resources are formatted with `Locale.ROOT` (`StackedNflPainter.kt:438`). The Pill is unchanged. | |
| U-N7, U-N8, U-N9 | **Not applied (lead):** U-N7 is inherited, U-N8 is an owner eye check, U-N9 is device-only. | |
| U-N10 | **Fixed:** no array per pin. | `StackedNflBug.kt:1007`. |
