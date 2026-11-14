# NHL score bug: goal animation: design

Status: **signed off 2026-11-07**.
- Proposed 2026-11-07 and reviewed 2026-11-07 (`docs/review/2026-11-07-goal-animation-design-review.md`: B1–B3,
  S1–S10, N1–N12).
- Revised under the lead's dispositions (the "Lead sign-off" section at the end).

Owner request and rulings:
- `docs/decisions/2026-11-05-nhl-score-bug.md`, rulings 7–12 and the flash ruling. Rulings 1–6 still apply.
- **Ruling 8 as amended (2026-11-07):** no white disc and no ring. The badge is the team's `_dark` logo, drawn straight
  on the base panel, with the mock's spin-in.

Mock-up: `design/score-bug-goal-animation.html`. Its keyframes are in `design/score-bug-goal-animation.template.html`
lines 318–462 ("the CSS").

Builds on `docs/design/m6-score-bug.md` ("SB §n") and `main` `817338e`. Units are **design px** (window width ÷ 1920,
`1.dp == 1 design px`) and ms. **Must** means a reviewer rejects code that does otherwise. Every number lives in one
constant (§10).

---

## 0. Decisions at a glance

1. **One draw node.** The bug's `Spacer` `drawBehind` calls one composite, `ScoreBugPainter.drawFrame`. One
   `Animatable<Float>` (elapsed ms, linear) is read **only in draw**. Nothing recomposes per frame.
2. **A pure timeline in `:core`.** `GoalTimeline.sample(tMs, out)` turns the CSS keyframes into a preallocated
   `GoalFrame`. The CSS easing applies **per keyframe segment**. JVM-tested.
3. **Wipes are `clipRect` inside the pill's canvas.** Panels use `drawRoundRect`, which is anti-aliased. Streaks use a
   soft-stop gradient band. There is no `clipPath` and no `saveLayer`.
4. **The bug's own layer is bounded and clipped:** the pill plus the glow margin, with `clip = true` (S3). Per-frame
   damage is about 550 × 220 px, not the whole window. The layer is a leaf, never an ancestor of the `SurfaceView`.
5. **The glow is two pre-baked `ALPHA_8` masks**, tinted at draw time. No real-time blur. It is ≤ 2 % alpha at the
   screen edge, and **off while the bug is lifted above a bar** or an overlay sits inside its margin (S1).
6. **GOAL letters are pre-rendered** into one white-plus-shadow bitmap per size (S2). No glyph re-rasterisation under
   the animated scale.
7. **Team colours are the teams' own** (ruling 12): base = the primary, accent = the official secondary. There are no
   contrast substitutions. C1 (white on base) is a test with a named exception list (PHI). C2 and C3 are informational.
   Sources are recorded per team (§3.3).
8. **The badge is the bug's held `_dark` logo bitmap.** No outline treatment (lead check of the real SVGs). Fallback:
   the abbreviation in white.
9. **The GOAL font is Barlow Condensed Black Italic**, bundled, subset to "GOAL", with the OFL licence on the Licences
   page.
10. **The machine:**
    - `CentreText.Goal` and the 8 s text are gone;
    - `goalSide` is a 4.5 s *goal mark*;
    - `goalSeq` is monotonic for the machine's lifetime;
    - the view carries `gameId`;
    - **a shootout winner's +1 plays the takeover** (ruling 11).
11. **The UI trigger is an algorithm** (§5.2, B1):
    - bookkeeping runs in composition, above the visibility return;
    - the holder is keyed on the game;
    - a synchronous `takeoverOn` gate;
    - hide, place mode, a size change or a new game cancel and clear.
12. **The flash is 3 × 400 ms (2.5 Hz)**, below 3 flashes/s on frequency alone (flash ruling, B3).
13. **A still version** for low-end mode and animation scale 0, detected through the driver's `MotionDurationScale`
    (S8).
14. **Commit 0 builds green on its own** (B2), then WP-1/2/3 run in parallel (§9).

---

## 1. Where the bug sits (nothing clips an ancestor of the surface)

```
AppRoot  Box(fillMaxSize)                        no layer, alpha or clip (RootTreeRuleTest)
├── VideoLayer  ── the one SurfaceView
├── AmbientBackground / HoleChrome / SubtitleLayer
└── Box(key sink)
    └── FullScreenHost  Box(fillMaxSize)         no clip, no layer
        ├── ScoreBug  Box(placeAt(−M) · graphicsLayer{ alpha = fade; clip = true } · size(w + 2M, h + 2M))   ← leaf layer
        │     ├── Spacer(offset(M, M) · size(w, h) · testTag(SCORE_BUG_TAG) · drawBehind { drawFrame(…) })
        │     └── Spacer(offset(M, M) · size(w, h) · semantics{ liveRegion = Polite })    goal announcement (§5.3)
        └── PlaceHint                            sibling of ScoreBug (moved out: the clipped layer would cut it)
```

- **Margin `M`** = max(peak glow margin, glass shadow margin 24 + 8) = **74** at Standard and **76** at Large.
  `placeAt` places the outer box at (box.l − M, box.t − M), so the pill stays exactly where `ScoreBugPlacement` puts
  it.
- `SCORE_BUG_TAG` stays on the pill-sized `Spacer`, so `ScoreBugBoundsTest` is unchanged.
- **The clip sits on the bug's own leaf layer** (a sibling subtree of `VideoLayer`). The takeover adds only canvas
  operations inside `save()`/`restore()`. `RootTreeRuleTest` must pass unchanged.
- **Effects of the bounded layer:**
  - HWUI's damage for a takeover frame is the layer rect, about 552 × 218 px Standard (it was the full window, because
    an unclipped node damages everything; S3);
  - during the 150 ms fade, the offscreen layer now contains the glass shadow and the glow, so neither is cut (this
    fixes the existing shadow cut too).
- **The place hint** used to sit inside the bug's box, laid out outside its bounds. It becomes a sibling in
  `FullScreenHost`, positioned from the same `holder.box`/`pos`. Its look and position are unchanged.

---

## 2. Rendering

### 2.1 The composite (`ScoreBugPainter.drawFrame`, WP-2)

All coordinates are pill-local, with the canvas already translated by `M`. The order is fixed here, so the screenshot
test and the app draw the same thing (S5):

1. **Glow** (§2.4): only while `takeoverOn && glowOn`, and never in low-end mode. It is behind the glass.
2. **Glass** bitmap (unchanged).
3. **Body** (unchanged). During the pop, the scorer's digit is drawn at `popScale` in a colour lerped from `pop` to
   white by `GoalFrame.popMix`. `drawFrame` computes the lerp, so the painter owns the colour arithmetic. The old goal
   box is removed.
4. **Takeover**, only while `takeoverOn`:
   - the lead (accent);
   - then, under the panel's wipe clip: panel (base), streak 1 (accent .6), streak 2 (white .35), flash (white),
     badge, GOAL, tally.

```kotlin
fun drawFrame(c: Canvas, glass: Bitmap, f: ScoreBugFrame, logos: FollowLogos?, logoWaited: Boolean, placing: Boolean,
              goal: GoalPainter?, g: GoalFrame?, glow: GlowMasks?, glowOn: Boolean)   // goal/g null = no takeover
```

### 2.2 Wipes, panels, streaks

- **Lead.** `save(); clipRect(leadL, 0, leadR, pillH); drawRoundRect(pill, r, r, accentPaint); restore()`.
  - Entering: `[0, w·p]`. Leaving: `[w·p, w]`. Both wipe left to right, like the CSS `inset()`.
- **Panel.** The same, with its own clip. Everything in the panel is drawn under that clip, so it leaves with the
  wipe. The rounded ends come from the anti-aliased round rect. There is no `clipPath`: HWUI's GL pipeline (API 25)
  draws path clips through the stencil without anti-aliasing.
- **Streaks (S4).** A `LinearGradient` band with colours `[T, C, C, T]` at stops `[0, ε, 1−ε, 1]`, where ε = 1 device
  px ÷ band width (soft edges, no stair-steps). `CLAMP`. Built once per takeover with the team colour.
  - **Geometry.** CSS `skewX(−24deg)` about the element's centre leans the band "/": its top edge is right of its
    bottom by `pillH·tan 24°`.
  - The band's centre line crosses the pill's vertical centre at `x = left + w/2`, where `left` is the animated
    translate.
  - The gradient vector is perpendicular to the edges: (cos 24°, sin 24°) in screen space (y down).
  - **Each frame:** `translate(dx, 0)`, then `drawRoundRect` of the pill offset by `−dx`. This avoids
    `Shader.setLocalMatrix`, which reallocates the native shader on API 26+.
- **Flash:** `drawRoundRect(pill)` in white at `flashAlpha`.

### 2.3 Badge, GOAL, tally

- **Badge** (ruling 8 as amended): the badge bitmap (§4) is drawn at the badge centre under `translate`, `rotate` and
  `scale`, with a **dedicated** `badgePaint` (N4: the body's `bmpPaint` is never re-alpha'd) using
  `FILTER_BITMAP | ANTI_ALIAS`.
  - The bitmap is a copy of the logo with **1 px of transparent padding** (N3), so the rotated quad's edge falls on
    empty pixels. The copy is made once per logo bitmap identity, in `GoalPainter.begin`; that is per goal at most,
    never per frame.
- **GOAL (S2).** When `GoalPainter` is built (per size), it pre-renders the four letters with their hard shadows
  (dy 2 / 2.5, black .28) into **one `ARGB_8888` atlas** at 1.5× the rest size, white letters with no tint. It also
  stores each letter's source rect and its rest-size destination centre.
  - Each frame, per letter: `save`, `translate(centre + dy)`, `scale(sc · wordPulse)` about the word centre composed
    with the letter's own, `drawBitmap(atlas, src, dst, letterPaint[alpha])`, `restore`.
  - Atlas size: about 180 × 95 px × 4 B ≈ 68 KB at Standard (1080p); Large is about 1.6×.
  - The pop digit stays as text: one glyph, 900 ms. Accepted.
- **Tally:** `"{away} – {home}"`, built into a `CharArray` in `begin()`. It is drawn right-aligned with `translate(dx)`
  and paint alpha.
- **Pop clamp (N5):** `popScale` is clamped so the popped digit string's width stays ≤ the 48 px digit column + 2 × 8
  = 64 px (`GOAL_POP_MAX_W`), and the clamp never pushes it below 1. A one-digit score pops at the full 1.55×; "10"
  (about 42 px at 40 px bold) pops at about **1.52×** (Standard, measured) / **1.51×** (Large), so it fills the 64 px
  without crossing into the logo slot.

### 2.4 Glow without blur

- **Two `ALPHA_8` masks per size**, low and peak. Each is the pill inflated by the spread, run through
  `BlurMaskFilter`, with the pill cut out (`clipPath(DIFFERENCE)` on the software bake canvas; the `bakeGlass`
  precedent).
- **Baking:** off the main thread (`Dispatchers.Default`), in the same step that builds the painter, once a game is
  followed (not on FULL entry: fix pass N3), then held for the size like `GlassCache`.
  - Never baked in low-end mode.
  - If the masks are not ready at takeover start, that goal draws without a glow.
- **Drawing:** the paint colour tints the `ALPHA_8` bitmap. Low mask α = `aLow·(1−m)`, peak mask α = `aPeak·m`.

| Mask | Standard: spread / σ / α | Large: spread / σ / α | Margin (spread + 3σ) |
|---|---|---|---|
| Low | 2 / 10 / .55 | 2.5 / 12.5 (Standard × 1.25) / .55 | 32 / 40 |
| Peak | 8 / **22** (= the mock's 40 × .547, N1) / .80 | 10 / **22** (capped; ×1.25 would be 27.5) / .80 | 74 / 76 |

- `BlurMaskFilter` radius = (σ − 0.5) / 0.57735, in device px.
- **Screen edge (56 px inset).** Alpha = `.8 · ½·erfc(d / σ√2)` at distance d past the spread:
  - Standard, d = 48: **1.3 %**;
  - Large, d = 46: **1.4 %**.

  Both are ≤ 2 % in every corner.
- **Off when a neighbour is close (S1).** `glowOn` is false while:
  - the bug is lifted above a bar (`PlacementInput.bar` is `USER_BAR` or `QUICK` with a bottom corner);
  - **or** an active overlay box (stats, channel list, group card, picker, number entry) intersects the pill inflated
    by the peak margin.

  This is a pure function `ScoreBugPlacement.glowAllowed(input, geo, box, margin)` in `:core`. The UI reads it in
  composition into a holder field. `drawFrame` reads that field, so a bar opening mid-takeover turns the glow off on
  the next frame. The field is **latched off** for the rest of that takeover (fix pass N4): when the bar closes again,
  the glow does not snap back on at its current timeline alpha; the next goal starts with it on.
- **Colour:** the team's `glow` (§3.1). This is a recorded **deviation from the mock (N2)**, which uses a fixed red
  `rgba(230,30,50)` for both teams. Ruling 12 asks for the scoring team's colours, so EDM glows orange.
- **Memory** at 1080p: Standard 63 + 120 KB, Large 79 + 158 KB. Only the shown size is held.

### 2.5 Allocation-free draw

- **Built once per size:** paints, rects, the letter atlas, the glow masks.
- **Built once per takeover** (`GoalPainter.begin`, called from the start effect, never in draw):
  - the streak shaders;
  - the tally chars;
  - the resolved `TeamColours`;
  - the padded badge copy.
- **Draw:** only `save`/`restore`/`clipRect`/`translate`/`rotate`/`scale`/`draw*` with primitives.
- `GoalFrame` is a mutable holder. The bezier solver is pure arithmetic.
- `Animatable.value` boxes a Float on each read, and Compose's frame loop allocates anyway (N11). The allocation tests
  therefore cover only `GoalTimeline.sample` and `GoalPainter.draw`.

### 2.6 Expected frame cost (Fire TV Stick, Fire OS 6 / API 25; estimate, measured in §8.4)

| Stage | Estimate | Reasoning |
|---|---|---|
| Main thread | ≤ 0.5 ms | A draw-phase invalidation of one node, about 40 ops recorded |
| RenderThread | ≤ 1 ms | The damage is the bounded layer (S3); the display list is short; no glyph uploads (S2) |
| GPU fill | 1–3 ms | About 7 layers over the 28 k px pill, plus the glow masks; the scissor is the ~120 k px damage rect |
| Window composition | device item | SurfaceFlinger / HWC over the video overlay while the app layer changes |

Expected total: ≤ 5 ms of the 16.7 ms frame. One takeover is about 270 frames (4.5 s at 60 Hz). Idle FULL is
unchanged.

---

## 3. Team colours

### 3.1 API (`:core`, `app.thebox.core.sports`)

```kotlin
/** ARGB ints. base: panel; accent: lead, streak 1; glow: halo; pop: the score's start colour on the glass. */
data class TeamColours(val base: Int, val accent: Int, val glow: Int, val pop: Int)
object NhlTeamColours {
    fun of(abbrev: String?): TeamColours                 // trim + upper-case; unknown/null → FALLBACK
    val FALLBACK: TeamColours
    internal val TABLE: Map<String, Pair<Int, Int>>      // abbrev → (base, accent); 32 entries
    internal val C1_EXCEPTIONS: Set<String> = setOf("PHI")   // owner ruling 12
}
object Contrast { fun luminance(argb: Int): Double; fun ratio(a: Int, b: Int): Double }  // WCAG 2.x sRGB
```

**`pop` belongs to `:core`** (B2). The painter only lerps it to white. `glow` and `pop` are derived, not tabled:
- **`glow`** = `base` if `ratio(base, GLASS_DARK) ≥ 2.0`, else `accent`. A navy or black panel's halo would vanish on
  dark video, so EDM glows orange.
- **`pop`:**
  1. Take whichever of `accent` and `base` has the higher contrast against `GLASS_WHITE_ICE`.
  2. Mix it toward white in 5 % steps until `ratio(pop, GLASS_WHITE_ICE) ≥ 4.5`.

  Because the source can be the base, a black accent (PHI, NJD) still pops in team colour, not grey.
- `GLASS_DARK` = `#090B10` (PanelFill .86 over black); `GLASS_WHITE_ICE` = `#2C2F34` (over white, SB §4.2).

### 3.2 Contrast rules (`NhlTeamColoursTest`)

| Rule | Pair | Floor | Kind |
|---|---|---|---|
| C1 | white GOAL / tally on `base` | 4.5 : 1 | **asserted**, except `C1_EXCEPTIONS` (PHI 3.6, owner ruling 12) |
| C2 | `accent` on the dark bug | 3.0 | **informational:** computed and printed, not asserted (true team colours can be dark on dark; a faint lead over a dark bug is accepted) |
| C3 | `accent` against `base` | 1.5 | **informational** |
| C4 | `pop` on `GLASS_WHITE_ICE` | 4.5 | **asserted** (by construction) |

The C1 floor is 4.5, not 3.0: on a TV the 22 px tally is about 15 CSS px, not "large text" (S6). GOAL passes 3 : 1
under any reading. The exception is the owner's: the tally is transient and repeats the score that the bug shows a
moment later.

### 3.3 The table (ruling 12; verified 2026-11-07)

> **CHL amendment (2026-11-14, `docs/design/m6-chl-leagues.md` §1.5, §6.2).** Colours are looked up by `(League, code)` through
> `TeamColourTable.of(league, abbrev)`: NHL → `NhlTeamColours` (this table, unchanged), NFL → `NflTeamColours`, OHL / WHL /
> QMJHL → `ChlTeamColours` (61 teams, `docs/research/chl-team-colours.md`). CGY, EDM, OTT, SEA, VAN, CHI and VIC exist in
> more than one league, so a lookup without its league is a bug.

Sources:
- **TCC** = `https://teamcolorcodes.com/<slug>/`, a secondary source, accessed 2026-11-07.
- ANA: the Ducks' own brand guide.
- No NHL.com hex was retrievable for the other teams.
- A gold or light primary takes the accent slot (NSH, VGK, PIT, BOS: per ruling 12).

| Abbr | Base | Accent | C1 | C2 | C3 | Glow | Pop | Source |
|---|---|---|---|---|---|---|---|---|
| ANA | `#171C19` | `#FE4111` | 17.3 | 5.6 | 4.9 | accent | `#FE6741` | brand.anaheimducks.com/brand-guides/intro-copy%201 (2024 rebrand; digital orange) |
| BOS | `#000000` | `#FFB81C` | 21.0 | 11.4 | 12.1 | accent | `#FFB81C` | TCC boston-bruins-color-codes |
| BUF | `#003087` | `#FFB81C` | 11.8 | 11.4 | 6.8 | accent | `#FFB81C` | TCC buffalo-sabres-color-codes |
| CGY | `#D2001C` | `#FAAF19` | 5.6 | 10.5 | 3.0 | base | `#FAAF19` | TCC calgary-flames-color-codes (2020 palette) |
| CAR | `#CE1126` | `#A4A9AD` | 5.6 | 8.3 | 2.4 | base | `#A4A9AD` | TCC carolina-hurricanes-color-codes |
| CHI | `#CF0A2C` | `#FFD100` | 5.6 | 13.5 | 3.9 | base | `#FFD100` | TCC chicago-blackhawks-color-codes |
| COL | `#6F263D` | `#236192` | 10.4 | 3.0 | 1.6 | accent | `#7BA0BE` | TCC colorado-avalanche-color-codes |
| CBJ | `#002654` | `#CE1126` | 14.9 | 3.5 | 2.7 | accent | `#E47C88` | TCC columbus-blue-jackets-color-codes |
| DAL | `#006847` | `#8F8F8C` | 6.8 | 6.1 | 2.1 | base | `#9A9A98` | TCC dallas-stars-color-codes |
| DET | `#CE1126` | `#FFFFFF` | 5.6 | 19.7 | 5.6 | base | `#FFFFFF` | TCC detroit-red-wings-color-codes |
| EDM | `#041E42` | `#FF4C00` | 16.5 | 5.9 | 5.0 | accent | `#FF6726` | TCC edmonton-oilers-color-codes |
| FLA | `#C8102E` | `#041E42` | 5.9 | 1.2 | 2.8 | base | `#E17C8C` | TCC florida-panthers-color-codes (primary order: open item 2) |
| LAK | `#111111` | `#A2AAAD` | 18.9 | 8.3 | 8.0 | accent | `#A2AAAD` | TCC los-angeles-kings-color-codes |
| MIN | `#154734` | `#A6192E` | 10.6 | 2.6 | 1.4 | accent | `#CE808C` | TCC minnesota-wild-color-codes (primary order: open item 2) |
| MTL | `#AF1E2D` | `#192168` | 6.9 | 1.4 | 2.1 | base | `#D3838C` | TCC montreal-canadiens-color-codes |
| NSH | `#041E42` | `#FFB81C` | 16.5 | 11.4 | 9.6 | accent | `#FFB81C` | TCC nashville-predators-color-codes (gold primary → accent) |
| NJD | `#CE1126` | `#000000` | 5.6 | 1.1 | 3.7 | base | `#E47C88` | TCC new-jersey-devils-color-codes |
| NYI | `#00539B` | `#F47D30` | 7.7 | 7.3 | 2.9 | base | `#F47D30` | TCC new-york-islanders-color-codes |
| NYR | `#0038A8` | `#CE1126` | 9.9 | 3.5 | 1.8 | accent | `#E47C88` | TCC new-york-rangers-color-codes |
| OTT | `#DA1A32` | `#B79257` | 5.0 | 6.8 | 1.7 | base | `#B79257` | TCC ottawa-senators-color-codes (2020 palette) |
| PHI | `#F74902` | `#000000` | **3.6 (exception)** | 1.1 | 5.9 | base | `#F96D35` | TCC philadelphia-flyers-color-codes |
| PIT | `#000000` | `#FCB514` | 21.0 | 11.0 | 11.8 | accent | `#FCB514` | TCC pittsburgh-penguins-color-codes |
| SJS | `#006D75` | `#EA7200` | 6.1 | 6.5 | 2.0 | base | `#EB790D` | TCC san-jose-sharks-color-codes |
| SEA | `#001628` | `#99D9D9` | 18.3 | 12.4 | 11.6 | accent | `#99D9D9` | TCC seattle-kraken-colors |
| STL | `#002F87` | `#FCB514` | 12.0 | 11.0 | 6.7 | accent | `#FCB514` | TCC st-louis-blues-color-codes |
| TBL | `#002868` | `#FFFFFF` | 14.0 | 19.7 | 14.0 | accent | `#FFFFFF` | TCC tampa-bay-lightning-color-codes |
| TOR | `#00205B` | `#FFFFFF` | 15.5 | 19.7 | 15.5 | accent | `#FFFFFF` | TCC toronto-maple-leafs-color-codes |
| UTA | `#010101` | `#69B3E7` | 20.9 | 8.6 | 9.1 | accent | `#69B3E7` | **unconfirmed** (no official hex): sportsteamcolorcodes.com/utah-mammoth-color-codes/ |
| VAN | `#00205B` | `#00843D` | 15.5 | 4.1 | 3.2 | accent | `#4CA977` | TCC vancouver-canucks-color-codes |
| VGK | `#333F42` | `#B4975A` | 10.9 | 7.0 | 3.9 | accent | `#B4975A` | TCC vegas-golden-knights-colors (gold primary → accent) |
| WSH | `#041E42` | `#C8102E` | 16.5 | 3.3 | 2.8 | accent | `#E17C8C` | TCC washington-capitals-color-codes |
| WPG | `#041E42` | `#004C97` | 16.5 | 2.3 | 2.0 | accent | `#739DC6` | TCC winnipeg-jets-color-codes |
| *fallback* | `#2A3140` | `#5CA8F2` (`DesignTokens.Accent`, copied) | 13.0 | 7.8 | 5.2 | accent | `#5CA8F2` | — |

Changes from the proposal (ruling 12, verified colours):
- **Retired palettes replaced:** CGY, OTT.
- **Exact values:** VGK `#333F42`, ANA `#FE4111` (and its brand black `#171C19`), CAR `#A4A9AD`.
- **Substitutions removed:** PHI, NJD (→ black), MTL (→ `#192168`), COL (silver → blue `#236192`), MIN (wheat → red
  `#A6192E`), WPG (silver → aviator blue `#004C97`), FLA (tan → navy `#041E42`).

---

## 4. Badge (ruling 8 as amended)

1. **Variant: `_dark`, the bug's own logo.** The lead rendered the real SVGs (TOR, EDM, CGY, VAN, PIT, NSH, COL, SEA,
   VGK, UTA) on each team's base:
   - the `_dark` art carries a light outer border or is white itself;
   - SEA's pale S reads on navy;
   - the `_light` variants are worse on team colours.

   **There is no outline, shadow or variant switching.** The badge uses the bitmap `FollowLogos` already holds, so
   there is no new request or contract field.
2. **Readiness (N8).** The logo is usually loaded long before a goal, because the first view never animates. It is not
   guaranteed: a slow or failed fetch, or a size change that re-fetches, can leave it missing. The fallback covers
   that.
3. **Size:** the bug's logo slot, at 1:1 at rest: 64 × 46 Standard, 80 × 58 Large. Its corners stay inside the rounded
   end, including at the 1.12× / +8° overshoot (review check).
4. **Fallback:** the abbreviation in Instrument Sans Bold, white, 24 / 30 px, with the same spin. It is chosen in
   `begin()` and never swapped mid-takeover.

---

## 5. Machine, trigger, accessibility

### 5.1 `ScoreBugMachine` and contracts

- **Removed:**
  - `CentreText.Goal` (with its branches in `SportsText`, `ScoreBugPainter.frame` and `Describe`);
  - `GOAL_TEXT_MS`, `GOAL_BOX_*`, `GOAL_IN|HOLD|OUT_MS`;
  - the `sports_goal` string;
  - `ScoreBugFrame.bottomGoal`.

  `CentreText` is not persisted, so the append-only note does not apply.
- **The goal mark.** `goalSide` is non-null for `GOAL_MARK_MS` = 4 500 after the goal becomes visible on the delayed
  timeline. `goalUntil` is renamed `markUntil`, and its wake stays. The bottom line is never replaced.
- **`goalSeq` is monotonic** for the machine's lifetime: `reset()` no longer zeroes it (B1.4).
- **`ScoreBugView.gameId: GameId?`** (default null; null for the place-mode sample) lets the UI key on the game.
- **Shootout (ruling 11).** The winner's +1 at the end of a shootout counts: it is flagged like any rise, and it
  usually lands on the FINAL snapshot (Final/SO).
- **Accepted miss (N9):** a tying goal and the shootout win inside one missed poll arrive as both sides rising, which
  is silent, as is any two-sided rise (C-N1).
- **KDoc updates (N12):** `ScoreBugMachine.kt` header lines 26–27, the `nextWakeAtMono` comment ("goal-text end" →
  "goal-mark end"), and `SportsContracts.kt:148`.

### 5.2 The trigger algorithm (`ScoreBug.kt`, WP-3; B1, S7)

The state is a plain `GoalRun` holder, plus one state value read only in draw:

```kotlin
private class GoalRun {
    var seen = false                        // a view has been seen since the last null view (step 1)
    var gameId: GameId? = null; var lastSeq = -1; var size: ScoreBugSize? = null
    var startToken = 0                      // bumped to start; the effect keys on it
    var side: Side? = null; var away = 0; var home = 0   // captured at start; never re-read (B1.5)
}
val takeoverOn = remember { mutableStateOf(false) }   // read ONLY in draw; written in composition and in the effect
```

**In composition, above the visibility return** (it runs for every composition with a real view, whether shown,
hidden or placing):

1. `v = bug` (the real view). If `v == null`, set `takeoverOn = false`, set `run.seen = false`, and stop. The reset
   makes the next non-null view a "first snapshot" again (step 2 adopts its seq and does not start), so an unfollow and
   re-follow of the same game never replays a mark. It cannot swallow a real goal: every null path of
   `ScoreBugMachine.view()` (stale hide after 300 s, SCHEDULED/PRE) is far longer than a goal's freshness window, and
   a re-follow resets the machine's `shown` (code review 2026-11-07, seam checks).
2. **New game or first snapshot.** If `!run.seen` or `v.gameId != run.gameId`: set `run.seen = true`, set `takeoverOn = false`, then `run.gameId = v.gameId`,
   `run.lastSeq = v.goalSeq`, `run.size = size`. Do not start. *(This covers the first snapshot, a follow change and
   an app restart.)*
3. **Cancel conditions**, checked every composition: `box == null` (hidden) **or** `placement.placing` **or**
   `size != run.size`. If any holds: `takeoverOn = false`, `run.size = size`. Clearing is synchronous: the next draw
   is the plain bug, with no frozen half-wipe whatever the `Animatable` holds (B1.2).
4. **New seq.** If `v.goalSeq != run.lastSeq`: set `run.lastSeq = v.goalSeq` and `takeoverOn = false` (any running
   takeover is cancelled). Then, **only if** `v.goalSide != null && box != null && !placing`, capture
   side/away/home and `run.startToken++`. A seq change with a null side (a delay shrink past an expired mark) does
   nothing.

**Effect** (placed above the visibility return, keyed on `run.startToken`):
- skip if the token is 0;
- `goal.begin(side, colours, badge, abbrev, away, home)`;
- `takeoverOn = true`;
- if `coroutineContext[MotionDurationScale]?.scaleFactor == 0f` or low-end mode, run the **still** path (§7);
- else `driver.snapTo(0f); driver.animateTo(4 500f, tween(4 500, easing = LinearEasing))`;
- finally `takeoverOn = false`.

A new token cancels the previous run, because the effect relaunches.

**The UI owns the timeline once it starts (B1.5).** After `begin()`, the side, tally and colours are fixed. The end of
the machine's mark (`goalSide → null`) changes only the description and the live region (§5.3). At animator scale 2
the takeover simply runs 9 s.

**Draw:** `drawFrame(…, goal = if (takeoverOn) goalPainter else null, g = timeline.sample(driver.value))`.

**No spoiler (fix pass, emulator finding).** The composition that receives the goal view already rebuilds the body
with the new score, but the start effect runs after it, and the lead needs `LEAD_COVERED_MS` (216 ms) to cover the
pill. So step 4 also holds the **previous** body frame (`held`, state read only in draw). The draw uses it while the
takeover is pending (`takeoverOn` false) or, on the animated path, while `t < LEAD_COVERED_MS`; the still path's
frame A covers the pill from its first draw. A cancel, a skipped goal (no painter) or the run's end clears it.
`GoalSeamTest` asserts that, from the first frame after the goal view, every pill pixel right of the lead equals the
previous frame until the lead covers it.

| Case | Result |
|---|---|
| First snapshot of a follow / app start | none (step 2) |
| Score corrected down | none (no mark) |
| One side +2 in one update | one takeover; the tally shows the new score |
| Both sides rose | none (C-N1) |
| Goal reassigned (−1 / +1) | takeover for the side that rose |
| Second goal during a takeover | restart at t = 0 with the new side and tally (step 4) |
| User delay | starts when the delayed snapshot becomes visible |
| OT winner on the FINAL snapshot | takeover, then Final/OT |
| **Shootout winner's +1** | **takeover** (ruling 11), then Final/SO |
| Snapshot only turns FINAL | none |
| Goal arrives in place mode | none; `lastSeq` updated, not replayed later |
| Goal arrives while hidden | none; `lastSeq` updated in composition, so it is **not replayed** when the bug reappears (B1.1) |
| Hidden / place mode entered / size changed mid-takeover | cancel and clear to the plain bug; no half-wipe on reappearance, no late replay (S7) |
| Follow changed or stopped mid-takeover | cancel and clear (step 2, or step 1 when the view is null) |
| Stale arriving mid-takeover | runs on; the dim applies under it |

### 5.3 Accessibility (S9)

- `Describe` uses `v.goalSide != null` (the mark) for its goal form. `sports_desc_goal` becomes
  **"%5$s goal. %1$s %2$d, %3$s %4$d"**, with %5 = the team's common name.
- **The live region is persistent.** A pill-sized `Spacer` (not zero-size), always composed while the bug is, with
  `liveRegion = Polite`. Its `contentDescription` is `""` normally and the goal string while the mark is set. A
  content change on a persistent live region is the path screen readers announce reliably. It changes twice per goal
  and never per frame.
  - **Fallback**, if the VoiceView check fails: `LocalView.current.announceForAccessibility(…)` once at takeover
    start, guarded by `AccessibilityManager.isEnabled`.
- This is speech only when the user runs a screen reader, so ruling 9's "no sound" holds. **Device item:** a VoiceView
  check on Fire TV.

---

## 6. Layout and timeline

### 6.1 Sizes (design px; Large = Standard × 1.25 on 506 × 88; vertical centre = pill centre)

| Item | Mock (780 × 128) | Standard (404 × 70) | Large (506 × 88) |
|---|---|---|---|
| Side padding | 44 | 24 | 30 |
| Badge (logo slot) | 80 | 64 × 46 at x 24 | 80 × 58 at x 30 |
| Badge fallback text | — | 24/700 white | 30/700 |
| Gap badge → GOAL | 28 | 14 | 18 |
| GOAL (atlas at 1.5×) | 92 | **52**, tracking 2, centred in x 102…289 | **65**, tracking 2.5 |
| GOAL shadow | 0 4 0 .28 | dy 2 | dy 2.5 |
| Tally | 38/800 | **22**/700 tabular, right edge at W − 24, fixed slot "00 – 00" (about 77) | **28**/700, W − 30 |
| Streak 1 / 2 width | 90 / 34 | 49 / 19 | 62 / 23 |
| Streak travel (left edge) | −160 → 980 | −83 → 508 | −104 → 636 |
| Letter drop / overshoot | 46 / −4 | 25 / −2 | 31 / −2.5 |
| Tally slide | 36 | 20 | 25 |

All type sizes are ≥ 18. Ruling 7 drops the team-name line and the clock line.

### 6.2 Timeline (t = 0 at takeover start; easing per keyframe segment, as in the CSS)

| Element | Start | Duration | Easing | Keyframes |
|---|---|---|---|---|
| Lead wipe (accent) | 0 | 3 600 | `cubic-bezier(.7,0,.2,1)` | in 0–216; full; out 3 384–3 600 |
| Panel wipe (base) | 0 | 3 600 | same | hidden to 108; in 108–396; full; out 3 168–3 492 |
| Glow | 0 | 3 600 | `ease-in-out` | 0 → 540 low .55 → 1 800 peak .8 → 3 060 low .55 → 3 600 0 |
| Streak 1 (accent .6) | 350 | 1 000 | `cubic-bezier(.4,0,.2,1)` | −160 → 980 (mock units) |
| **Flash** (white) | 420 | **3 × 400** (to 1 620) | `ease-in-out` | each cycle 0 → .45 (at 200) → 0; **2.5 Hz** (flash ruling, B3) |
| GOAL G / O / A / L | 420 / 500 / 580 / 660 | 420 each | `cubic-bezier(.2,.8,.2,1)` | 0: α 0, dy +46, ×1.9 → 60 %: α 1, dy −4, ×.94 → rest |
| Badge (logo) | 450 | 550 | `cubic-bezier(.2,.8,.2,1)` | 0: α 0, ×.3, −120° → 70 %: α 1, ×1.12, +8° → rest |
| Tally | 900 | 450 | `cubic-bezier(.2,.8,.2,1)` | α 0, dx +36 → rest |
| GOAL pulse | 1 150 | 3 × 500 (to 2 650) | `ease-in-out` | 1 → 1.07 → 1 |
| Streak 2 (white .35) | 1 750 | 800 | `cubic-bezier(.4,0,.2,1)` | −160 → 980 |
| Score pop (scorer's digit) | 3 600 | 900 | `cubic-bezier(.2,.8,.2,1)` | ×1.55 → (360) .9 → (630) 1.06 → 1, clamped (N5); colour `pop` → white eased once over 0–900 |

- **Pop reveal (accepted deviation from the mock).** From 3 168 the scorer's digit is drawn in the pop's first state.
  It is hidden under the lead until the **lead's** exit (3 384–3 600) reveals it, ready to pop. This avoids the mock's
  visible jump at 3 600 (N6 wording).
- `fill-mode: both` is honoured where the CSS uses it. The flash and the pulse have no fill.

---

## 7. Still version (low-end mode, or `MotionDurationScale == 0`)

- **Frame A (0–3 600):** the panel at rest (base, badge, GOAL, tally). No lead, streaks, flash, pulse or glow.
- **Frame B (3 600–4 500):** the plain bug with the scorer's digit in `pop` at ×1.
- **Then** the plain bug.

There are three draws in all, driven by `driver.snapTo(…)` plus `delay(…)`; the `delay` is not scaled.

**Detection (S8):** inside the driver effect, `coroutineContext[MotionDurationScale]?.scaleFactor == 0f`. This is the
exact value Compose applies, it follows the setting live, and it can never disagree with `animateTo`. `SettingsUi`'s
`animationsOff()` stays as it is, for the searching dots.

---

## 8. Tests

### 8.1 `:core`

- **`NhlTeamColoursTest`:**
  - the key set equals the fake panel's team table plus a recorded standings fixture
    (`core/src/test/resources/sports/nhl/teams-fixture.json`, independent of the table; S10);
  - C1 ≥ 4.5 for all except `C1_EXCEPTIONS`, and the exception list is exactly `{PHI}`;
  - C4 ≥ 4.5 everywhere;
  - C2 and C3 printed as a report;
  - `of(null)`, `of("XYZ")`, `of(" cgy ")`;
  - `glow` and `pop` pinned for CGY, EDM, NSH and PHI (pop from the base: `#F96D35`).
- **`ContrastTest`:** white/black = 21; `#767676`/white = 4.54.
- **`GoalTimelineTest`:**
  - every keyframe instant, and mid-segments against a reference bezier (1e-3);
  - fill before and after;
  - the wipe edges at 0/108/216/396/3 168/3 384/3 492/3 600;
  - **no 1 s window holds more than 3 flash cycles** (B3);
  - `sample` allocates nothing (10 000 calls, `getThreadAllocatedBytes` delta < 1 KB).
- **`ScoreBugPlacementTest`:** `glowAllowed` is false for a bottom corner with `USER_BAR`/`QUICK`, false for an
  overlay inside the margin, and true for a bare corner.
- **`ScoreBugMachineTest`:**
  - no `CentreText.Goal`;
  - the mark lasts exactly 4 500 on the delayed timeline, and a wake fires at its end;
  - an OT-winner FINAL is marked; a **shootout winner's +1 is marked**;
  - +2 gives one seq; a drop gives none; both rising gives none;
  - a 30 s delay marks at `anchor + 30 s`;
  - **`goalSeq` does not reset across a re-follow**;
  - `gameId` is set on every view.

### 8.2 `:ui` Robolectric: `GoalAnimationScreenshotTest` (native graphics)

- **Frames:**
  - through `drawFrame` (the composite, S5) with the bundled fonts, over the dark gradient and over white ice;
  - t = 100, 300, 600, 1 000, 1 400, 2 000, 3 300, 3 550, 3 700, 4 000, plus still frames A/B;
  - for **CGY, EDM, NSH and PHI** (S6), at Standard and Large.
- **Extra frames:** the badge fallback (no logo); the quick bar open (`glowOn = false`); low-end mode.
- **Output:** `ui/build/score-bug/goal-*.png` plus `goal-contact-sheet.png`. These are for review by eye, not goldens.
- **Assertions:**
  - t = 100: a lead pixel is `accent`, and the far right is still glass.
  - t = 1 400: a panel pixel is `base` (±3); a GOAL stem pixel is white; C1 is measured from the pixels.
  - t = 1 800: 20 px above the pill is `glow` with alpha > .3. **56 px above, the alpha is ≤ 2 %** (Standard and
    Large).
  - With the quick bar open, there are no glow pixels outside the pill.
  - t ≥ 4 500: pixel-equal to the plain bug.
  - The still frames have no streak or glow pixels.
  - In low-end mode, `bakeGlow` is never called.
  - Nothing is drawn outside the pill + `M`.

### 8.3 `:ui` Robolectric: `ScoreBugDrawTest` (compose test clock)

- **Recompositions:** a goal gives exactly 2 `ScoreBug` recompositions (mark start, mark end) and 0 in between.
- **B1.1:** a goal while hidden, with the bug reappearing 1 s later inside the mark: no takeover.
- **B1.2:** hidden mid-takeover, the fade done, then reappearing: `takeoverOn` is false and the plain bug shows (no
  frozen frame).
- **B1.3:** a follow changed mid-takeover: cancelled.
- **S7:** place mode entered, and a size change, mid-takeover: cancelled.
- **Seq without side:** a delay shrink that bumps the seq while `goalSide` is null: nothing.
- **B1.5:** with `MotionDurationScale` = 2 in the test context, the pop still completes after the mark has ended.
- **S8:** with scale 0, the still path is used (snaps only).
- **S9:** the live-region description goes "" → goal string → "".
- **Tree:** `RootTreeRuleTest` unchanged and green. `ScoreBugBoundsTest` unchanged (the tag is on the pill).
- **Allocation:** 300 `GoalPainter.draw` calls with a delta < 4 KB. Best-effort: `@Ignore` it with a note if
  Robolectric's shadows allocate.

### 8.4 Instrumented: `GoalFramesTest` (beside `FullIdleFramesTest`)

- **Setup:** FULL with live video and a fake source that emits a goal; `FrameMetrics` over 5 s. The test re-anchors
  the fake panel's featured game (`POST /admin/sports {"restart": true}`), waits for the takeover to **start** (a burst
  of `DRAWS_SCORE_BUG`), then opens the 5 s window; the idle window starts 1 s after the takeover's measured end
  (fix pass S3).
- **Records:** P90 `TOTAL_DURATION`, GPU duration, and the damage area (`dumpsys gfxinfo` / the debug overlay; S3).
- **Gates:**
  - emulator: ≥ 200 frames;
  - device: P90 < 16.7 ms and ≤ 3 frames over 33 ms on a Fire TV Stick (API 25), and idle ≤ 1 frame per 10 s within
    1 s of the end.
- **Status:** the device run is open (no hardware yet).

---

## 9. Work packages

### Commit 0: contracts, compile fixes, assets (lead, Opus). Gate: smoke build + `lintAll` green (B2)

| File | Change |
|---|---|
| `core/.../sports/SportsConstants.kt` | remove `GOAL_TEXT_MS`, `GOAL_BOX_*`, `GOAL_IN/HOLD/OUT_MS`; add §10 |
| `core/.../sports/SportsContracts.kt` | remove `CentreText.Goal`; add `ScoreBugView.gameId: GameId? = null`; KDoc of the mark (N12) |
| `core/.../sports/ScoreBugMachine.kt` | compile fixes only: drop the `Goal` bottom (line 224); `GOAL_TEXT_MS` → `GOAL_MARK_MS` (340); fill `gameId`; KDoc (N12) |
| `core/.../sports/NhlTeamColours.kt`, `Contrast.kt`, `GoalTimeline.kt` (with `GoalFrame`, every field named) | stubs, `TODO()` bodies |
| `core/.../sports/ScoreBugPlacement.kt` | stub `glowAllowed(input, geo, box, margin): Boolean = TODO()` |
| `core/src/test/.../ScoreBugMachineTest.kt` | lines 312, 500, 502: assert the mark (`goalSide`), not `CentreText.Goal` |
| `ui/.../sports/SportsText.kt` | remove the `Goal` branch (line 76) |
| `ui/.../sports/ScoreBugPainter.kt` | remove `bottomGoal` and the goal box; new `drawBody(…, popSide, popScale, popColour)`; `drawFrame` (§2.1) = glass + body, takeover hooks no-op |
| `ui/.../sports/GoalPainter.kt` | stub `GoalPainter(s, pillW, pillH, goalFont, sansBold)` with `bakeGlow(): GlowMasks`, `begin(side, colours, badge, abbrev, away, home)`, `draw(c, g, glow, glowOn)`; `class GlowMasks(val low: Bitmap, val peak: Bitmap, val lowMargin: Float, val peakMargin: Float)` |
| `ui/.../sports/ScoreBug.kt` | remove the goal `Animatable`; call `drawFrame`; `Describe` keys on `goalSide`; the outer box + `M` + `clip = true` (§1); `PlaceHint` moved to a sibling |
| `ui/.../full/FullScreenHost.kt` | host the moved `PlaceHint` (same position source) |
| `ui/src/test/.../SportsTextTest.kt` | line 45 removed |
| `ui/src/test/.../ScoreBugTestSupport.kt` | `goalHome`/`goalAway` → `goalMark(side)`, plus `goalView(team, …)` fixtures and a `gameId`. **Owned by commit 0 only; WP-2 and WP-3 do not edit it** |
| `ui/src/test/.../ScoreBugScreenshotTest.kt` | new `drawBody` arguments (lines 88, 122); the goal-box case removed |
| `ui/src/main/res/values/strings_sports.xml` | remove `sports_goal`; change `sports_desc_goal`; add `sports_goal_word` "GOAL" (`translatable="false"`) |
| `ui/src/main/res/values/strings_diag.xml`, `ui/.../diag/DiagPages.kt` | `licences_barlow` "Barlow Condensed font"; `Licence("BarlowCondensed-OFL.txt", R.string.licences_barlow, R.string.licences_ofl)` |
| `ui/src/main/res/font/barlow_condensed_black_italic.ttf` | from `google/fonts` `ofl/barlowcondensed/BarlowCondensed-BlackItalic.ttf`, pinned commit and SHA-256 recorded here; `pyftsubset --text="GOAL"` keeping the name table, **only if** the upstream `OFL.txt` has no Reserved Font Name (otherwise the full file) |
| `ui/src/main/assets/licenses/BarlowCondensed-OFL.txt` | upstream `OFL.txt`, verbatim |
| `core/src/test/resources/sports/nhl/teams-fixture.json` | the independent team list (S10) |

**Font as shipped (commit 0, 2026-11-07).** Downloaded from `google/fonts` `main` at commit
`86aced91de05cd4f96a06838124cca45be337b2b` (the file's last change upstream: `89f5431ff0db41bd2fe3f7ba21a723a01622428b`,
2018-12-05).
- `BarlowCondensed-BlackItalic.ttf`: 112 592 bytes, SHA-256
  `e1ed5d7e50c6a4498454d40509ce2362384a2c7870df3418fdbd010f81dffad3`.
- `OFL.txt`: 4 377 bytes, SHA-256 `186d750eb496a4c17a76385f82be6aea2ac1cf2de074a811d63786cf374ea73f`. It names
  **no Reserved Font Name** (only "Copyright 2017 The Barlow Project Authors").
- **Not subset:** `pyftsubset` (fontTools) is not installed on the build machine, so the full file ships (about 110 KB).
  Subsetting to "GOAL" is allowed by the licence and can follow as a one-file change.

After commit 0, every file below belongs to exactly one WP.

### WP-1: core (Opus)

- `NhlTeamColours.kt` (the §3.3 table with its source KDoc), `Contrast.kt`, `glowAllowed` in `ScoreBugPlacement.kt`.
- `ScoreBugMachine.kt` behaviour: monotonic `goalSeq`, the mark window, the shootout counting as a goal.
- Tests: `NhlTeamColoursTest`, `ContrastTest`, `ScoreBugPlacementTest`, `ScoreBugMachineTest`.

### WP-2: timeline and painter (Opus for the timeline, Sonnet for the painter)

- `GoalTimeline.kt` + `GoalTimelineTest`.
- `GoalPainter.kt`: masks, letter atlas, badge with padding and its own paint, streaks, tally.
- `ScoreBugPainter.kt`: `drawFrame` in full, the pop with its clamp.
- `GoalAnimationScreenshotTest`.

### WP-3: wiring and still (Sonnet)

- `ScoreBug.kt`: §5.2 (holder, `takeoverOn`, effect, cancels), the glow-mask job, the `glowOn` field, the still path,
  the persistent live region.
- `ScoreBugDrawTest`, `GoalFramesTest` (androidTest).

Every WP ends with `./gradlew :core:test :ui:testDebugUnitTest :app:assembleDebug lintAll`. The feature ends with:
- a code review;
- an emulator pass (fake panel `--sports`: a CGY goal in the 2nd period, an EDM goal in the 3rd, then the shootout);
- the device items.

**Device items:**
- the frame time and damage area (§8.4);
- Fire OS HWC while the overlay animates;
- the 22 teams whose real `_dark` logos the lead has not yet checked, on their bases (a contact sheet);
- VoiceView announcing the goal;
- the flash judged on a real TV.

---

## 10. Constants (`SportsConstants`; Standard design px, Large × 1.25 unless stated)

| Name | Value |
|---|---|
| `GOAL_TAKEOVER_MS` / `GOAL_POP_MS` / `GOAL_MARK_MS` | 3 600 / 900 / 4 500 |
| `GOAL_FLASH_CYCLES` / `GOAL_FLASH_CYCLE_MS` / `GOAL_FLASH_DELAY_MS` / `GOAL_FLASH_ALPHA` | 3 / 400 / 420 / .45 |
| `GOAL_PAD` / `GOAL_GAP` | 24 / 14 |
| `GOAL_WORD_PX` / `GOAL_TRACK` / `GOAL_SHADOW_DY` / `GOAL_SHADOW_ALPHA` / `GOAL_ATLAS_SCALE` | 52 / 2 / 2 / .28 / 1.5 |
| `GOAL_TALLY_PX` / `GOAL_BADGE_TEXT_PX` | 22 / 24 |
| `GOAL_STREAK1_W` / `GOAL_STREAK2_W` / `GOAL_STREAK_SKEW_DEG` / alphas | 49 / 19 / −24 / .6, .35 |
| `GOAL_LETTER_DROP` / `GOAL_LETTER_OVERSHOOT` / `GOAL_TALLY_SLIDE` | 25 / −2 / 20 |
| `GOAL_POP_MAX_W` (pop clamp) | digit column 48 + 2 × 8 |
| Glow low / peak (spread, σ, α) | (2, 10, .55) / (8, 22, .80); Large peak (10, 22, .80) |
| `GOAL_LAYER_MARGIN` (`M`) | 74 Standard / 76 Large |
| `GOAL_GLOW_EDGE_MAX_ALPHA` (test) | .02 |
| Colours | C1 4.5 (asserted, exceptions {PHI}); C2 3.0 and C3 1.5 (reported); C4 4.5; glow threshold 2.0; `GLASS_DARK` `#090B10`; `GLASS_WHITE_ICE` `#2C2F34` |

---

## Lead sign-off (2026-11-07)

Review: `docs/review/2026-11-07-goal-animation-design-review.md`. Owner rulings 11, 12 and the flash ruling are in
the decision note.

| ID | Disposition | Where |
|---|---|---|
| B1 | **accepted** as the reviewer proposes: bookkeeping in composition above the visibility return; holder keyed on game id (`ScoreBugView.gameId`), with a follow change cancelling and clearing; monotonic `goalSeq`; a synchronous `takeoverOn` read only in draw; the UI owns the timeline after start; hide / place mode / size change / new game cancel and clear (no half-wipe, no late replay) | §5.1, §5.2 |
| B2 | **accepted:** commit 0 removes `CentreText.Goal` / `GOAL_TEXT_MS`, fixes every caller and test so it builds green, and declares `GlowMasks`, the pop-colour owner (`NhlTeamColours.pop`; the painter lerps), the `drawFrame` composite and the shared test-support changes; exact file list | §9 |
| B3 | **accepted:** flash 3 × 400 ms (2.5 Hz), plus a test that no 1 s window has more than 3 cycles | §6.2, §8.1 |
| S1 | **accepted:** glow off while the bug is lifted above a bar or an overlay sits inside its margin (`glowAllowed`); a quick-bar screenshot | §2.4, §8.2 |
| S2 | **accepted:** GOAL letters pre-rendered to a white + shadow atlas per size, no tint; the pop digit stays text | §2.3 |
| S3 | **accepted:** a bounded `clip = true` layer on the bug (pill + `M`); replaces the `ModulateAlpha` question; `PlaceHint` moved to a sibling | §1, §2.6 |
| S4 | **accepted:** soft stops (ε = 1 device px); skew sign and origin written down | §2.2 |
| S5 | **accepted:** `drawFrame` composite in WP-2, declared in commit 0 | §2.1, §9 |
| S6 | **owner exception (ruling 12):** PHI in `C1_EXCEPTIONS`; C1 floor 4.5 with the TV rationale; PHI added to the screenshots | §3.2, §8.2 |
| S7 | **accepted:** place mode or a size change mid-takeover cancels | §5.2 |
| S8 | **accepted:** detection through the driver's `MotionDurationScale` | §7 |
| S9 | **accepted:** a persistent pill-sized live region whose text changes (`announceForAccessibility` as fallback); VoiceView is a device item | §5.3 |
| S10 | **accepted:** every listed test added; the team set pinned to an independent fixture | §8 |
| N1 | taken: Standard peak σ 22 (edge 1.3 %) | §2.4 |
| N2 | taken: team glow recorded as a deviation from the mock | §2.4 |
| N3 | taken: badge copy with 1 px transparent padding | §2.3 |
| N4 | taken: dedicated `badgePaint` | §2.3 |
| N5 | taken: pop scale clamped for two digits | §2.3, §6.2 |
| N6 | taken: wording corrected (the lead's exit reveals the digit) | §6.2 |
| N7 | moot: the bounded layer (S3) contains the glow during fades | §1 |
| N8 | taken: readiness claim softened | §4 |
| N9 | accepted as a known miss | §5.1 |
| N10 | accepted (transient; reduced by B3) | — |
| N11 | taken: allocation tests scoped to `sample` and `draw` | §2.5 |
| N12 | taken: KDoc updates in commit 0 | §5.1, §9 |
| Q1 shootout | **owner ruling 11:** the shootout winner's +1 plays the takeover (overrides the proposer and reviewer) | §5.1, §5.2 |
| Q2 colours | **owner ruling 12:** base = primary, accent = official secondary, no substitutions; C2/C3 informational; a faint lead over a dark bug accepted | §3 |
| Q3 pop reveal | accepted (reviewer agrees) | §6.2 |
| Q4 live region | kept, as in S9 | §5.3 |
| Q5 fade layer | superseded by S3 | §1 |
| Q6 flash | flash ruling: 2.5 Hz | §6.2 |

**Still open:**
1. **UTA colours are unconfirmed.** No official hex is published, and third-party sources disagree (`#69B3E7` vs
   `#6CACE3`). Kept as proposed.
2. **Primary order for FLA, MIN and CHI.** The secondary source lists FLA's navy before red and MIN's red before green,
   and does not say which colour each team calls primary or secondary. The table uses red/navy for FLA, green/red for
   MIN and red/yellow for CHI. The owner may flip any of them; it is a one-line table change.
3. **Every hex except ANA's comes from a secondary source** (teamcolorcodes.com). No NHL.com values were retrievable.

---

## Fix pass (2026-11-07)

Review: `docs/review/2026-11-07-goal-animation-code-review.md`. The doc was changed to match the code (N5, N6), not the
reverse.

| ID | Change |
|---|---|
| S1 | New `GoalSeamTest` (`:ui`, native graphics) drives the real WP-3 → WP-2 path: `ScoreBugWith` with the production hooks (a `begin` wrapper records `takeoverOn` at begin, then calls `GoalHooks.Default.begin`). The drawing node's body moved into a local `drawBug(Canvas)`, which `GoalProbe.draw` hands out; the test renders it. It asserts the painter's `side`, `colours` (= `NhlTeamColours.of`) and tally (`GoalPainter.tallyText`, "2 – 1"), the panel pixel in the team's base, the glow drawn, the pop on the scorer's side only, a second goal begun with the new team, and that `takeoverOn` is false at every `begin`. `ScoreBugDrawTest`'s stale "commit-0 stubs" comment is rewritten. |
| S2 | `ScoreBugDrawTest` counts `bake` calls: 0 in low-end mode, 1 per size otherwise, 0 before a game is followed. |
| S3 | `GoalFramesTest` re-anchors the panel, waits for the takeover's draw burst before opening the 5 s window, tracks the last draw during the window and starts the idle window 1 s after the measured end (`-e sportsPanel` names a separate sports panel). Not run here (instrumented). |
| N2 | `ScoreBugPainter.drawBody` uses one held `Paint.FontMetrics` (`getFontMetrics(fm)`); a `drawFrame` × 300 allocation test is added to `GoalAnimationScreenshotTest`. |
| N3 | The goal painter (Barlow load, GOAL atlas) and the glow bake are built only once a game is followed, in one step off the main thread (`GoalHooks.background`, `Dispatchers.Default`; tests pass an empty context). A goal that arrives before the painter is ready is skipped (cannot happen in practice: the first snapshot never plays). The font is not subset. |
| N4 | `GoalRun.glowOn` latches off for the rest of a takeover once a neighbour closes in (§2.4). |
| N5 | §2.3: the "about 1.2×" estimate replaced by the rule and the measured 1.52× / 1.51×. |
| N6 | §5.2 documents the `run.seen` reset on a null view; §2.4's Large low glow is 2.5 / 12.5, margin 40. |
| N7 | `GoalPainter` takes the atlas word from `sports_goal_word` (it must have 4 letters). |
| N8 | `ScoreBug` computes the layer margin once. |
| N10 | `ScoreBugDrawTest.aFollowStoppedMidTakeoverCancelsAndIsNotReplayed`. |
| N11 | `GoalAnimationScreenshotTest` takes its teams' colours from `NhlTeamColours.of`. |
| N1, N9 | Left as they are (lead's call). |
| Spoiler (lead, emulator) | The plain bug drew the new score (and, for the shootout winner, "Final/SO") before the lead covered it. Code cause: the goal view's composition rebuilds the body at once, while the takeover starts in an effect after that frame and the lead takes 216 ms to cover the pill. Fix: §5.2 "No spoiler" (the previous body is held until the lead covers the pill; `GoalTimeline.LEAD_COVERED_MS` is now public). The painter, atlas and masks are built when a game is followed (N3), and `GoalPainter.prewarm` hands the atlas and masks to the GPU (`prepareToDraw`) before any goal. The ~1 s seen on the shootout-final snapshot was not reproduced off-device. The start path does no main-thread loading or waiting, the bug's only source is the landing (score and FINAL arrive together), and the machine flags the goal on the same view. A first-use texture upload on the emulator is the remaining suspect, which the prewarm addresses. Either way the hold keeps the old body on screen until the lead covers the pill, however late the start is. Device check: the shootout final on the emulator. |
