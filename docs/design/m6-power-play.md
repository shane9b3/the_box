# NHL score bug: power-play tab: design

Status: **signed off 2026-11-08**.
- Proposed 2026-11-07 and reviewed 2026-11-07 (`docs/review/2026-11-07-power-play-design-review.md`: B1–B3, S1–S10,
  N1–N14).
- Revised under the lead's dispositions (the "Lead sign-off" section at the end).

Owner request and rulings:
- `docs/decisions/2026-11-05-nhl-score-bug.md`, rulings 13–19 and the lead defaults under "Owner rulings: power play".
- Rulings 1–12 still apply, especially 6 (no local countdown), 9 (the goal takeover) and 12 (true team colours).

Mock-up: `design/score-bug-power-play.html`. Its readable markup, CSS and state JS are in
`design/score-bug-power-play.template.html` ("the CSS"): `.pp-tab*`, `.pp-text*`, `.pp-shine`, `.pp-label`, `ppShine`,
`ppPulse`, `.tint*` and `.logo.glow-*`.

Builds on `docs/design/m6-score-bug.md` ("SB §n"), `docs/design/m6-goal-animation.md` ("GA §n", with its sign-off and
fix pass) and `main` `7981743`. Units are **design px** (window width ÷ 1920; `1.dp == 1 design px`) and ms. **Must**
means a reviewer rejects code that does otherwise. Every number lives in one constant (§11).

---

## 0. Decisions at a glance

1. **The data is the landing's `situation` object.**
   - The side and the advantage are decoded from `situationCode` alone, with a pulled goalie's extra attacker
     discounted.
   - So an empty net at even strength, 4-on-4 and a delayed penalty show nothing, and a 6-on-4 keeps its tab
     (ruling 18).
   - No new endpoint, and no change to polling (§1, §2).
2. **PP GOAL comes from the landing's `summary.scoring`**, where each goal has `strength == "pp"`. If the scoring list
   has no entry yet, the fallback is "the previous snapshot had the scorer on the power play" (§1.4).
3. **When it shows:** the tab hides while stale or waiting on the delay. It stays through intermission, frozen
   (ruling 19). It never shows in the shootout or a final (§2).
4. **Two tracks, one draw node.**
   - The **tab track** carries slide, shadow, text, shine and pulse.
   - The **pill track** carries the tint and the logo halo.
   - Each track has one `Animatable<Float>`, read only in draw, and is sampled by one pure `PowerPlayTimeline.sample`
     into a preallocated `PpFrame`.
   - A runner is kicked **only when a transition's identity changes**. A seconds update just redraws (§3).
5. **The run owns what the tab says.** It holds a `PpContent` (side, label, seconds), captured when each transition
   starts. A close slides back the words it opened with, and a pin holds its own words (§3.1, §5).
6. **The tab is drawn after the glass, clipped to the outside of the pill** with one `clipRect`, snapped to a device px
   (§4.3).
7. **No real-time blur.** The tab shadow, the label masks and the logo halo are `ALPHA_8` bakes. The text shadow is a
   hard offset (§3.4).
8. **The geometry fits the existing layer margin `M`.** The layer and `RootTreeRuleTest` are unchanged. If the tab
   would meet an overlay, the tab closes and the pill track stays (§4).
9. **The takeover pins the tab**, and its end at 3 600 ms releases it. The release is seen by watching the takeover's
   driver from a child coroutine, so the takeover keeps its single tween (§5).
10. **Colours (rulings 12 and 17):**
    - the tint and the halo use `glow`;
    - the tab uses `tab`: `glow` if white passes, else the team's other colour;
    - the gradient's far stop is `mixBlack(.10)` for every team, so EDM, ANA and PHI keep orange tabs;
    - C5 (tab text) passes for all 32 teams with no exceptions;
    - a new C6 caps the tint strength per team, so white digits stay readable over white ice (§6).
11. **The still version** snaps between rest states. Low-end mode also drops the shadow and the halo (§3.6).
12. **The fake panel's power plays are opt-in** (`sports_pp`). The default featured timeline is unchanged (§8).
13. **Commit 0 fixes every cross-WP signature** and builds green. Then WP-1, WP-2 and WP-3 run in parallel (§10).

---

## 1. Data

### 1.1 What the feed returns

| Fact | Status | Evidence |
|---|---|---|
| `situation` is on the game object in both `/v1/score/{date}` and `/landing`, with the same shape. | **Verified** | Fixtures `landing-live-int.json` and `score-2026-11-05.json` (UTA–MTL `1541`, VAN–WPG `1451`) |
| Its shape: `{situationCode, timeRemaining, secondsRemaining, awayTeam: {abbrev, strength[, situationDescriptions: ["PP"]]}, homeTeam: {…}}` | **Verified** | Fixtures |
| The code is 4 digits: away goalie, away skaters, home skaters, home goalie. A delayed penalty shows as the extra attacker (`0651`). | **Verified** | `pbp-excerpt-2026-11-07.json` (FLA@WSH, STL@NSH, NYR@SEA) |
| `situation` is absent at even strength. | **Verified** | `score-now-excerpt-2026-11-07.json` |
| The power play is still reported during intermission, with its time frozen. | **Verified** | `landing-live-int.json`: MTL minor at 19:19 of the 1st leaves 1:19, with `inIntermission: true` |
| The landing's `summary.scoring[].goals[]` carries `strength` (`ev`/`pp`/`sh`), the code at the goal, and `awayScore`/`homeScore` after it. | **Verified** | `landing-live-int.json`: UTA's 09:24 goal is `pp`, `1541`, 2–0 |
| The code is unchanged across stoppages. | **Verified** | Play-by-play excerpt |
| At 5-on-3, `secondsRemaining` is the time to the first expiry. | **Inferred, not relied on** | The tab shows what is reported (ruling 13) |
| The scoring entry arrives in the same update as the score. | **Inferred** | Device item (§10) |
| A live PP landing, as opposed to one in intermission | **Not yet seen** | 26 min of 20 s samples of NYR@SEA held none (`live-sample-2026-11-07.log`). Device item: record one. |

**Evidence files.** The three files named above are in the lead's scratchpad (`…/scratchpad/pp/`). Commit 0 copies
them into `core/src/test/resources/sports/nhl/` (N11).

### 1.2 Decoding (`NhlMapper.powerPlay`, pure)

```
code: a 4-character string, or a JSON integer of 3–4 digits left-padded to 4 ("651" → "0651"); anything else → none
must match [01][3-6][3-6][01]
effAway = awaySkaters − (1 − awayGoalie);  effHome = homeSkaters − (1 − homeGoalie)
both must be in 3..5, else none                    // rejects 0331, 1361 and penalty-shot codes
diff == 0 → none;  side = the larger;  kind = TWO_MAN if |diff| == 2 else ONE_MAN
cross-check: "PP" in the *other* side's situationDescriptions → none
secs = secondsRemaining ?: parse(timeRemaining); kept if 0..PP_SECS_MAX (599), else null
```

- There is **no strength fallback** (S9). A missing or bad code means no power play.
- `TWO_MAN` is always 5-on-3, so its label is a fixed string.

| Code | Result |
|---|---|
| `1541` / `1451` | AWAY / HOME, ONE_MAN |
| `1351` / `1531` | HOME / AWAY, TWO_MAN ("5-ON-3") |
| `1431` (overtime) | AWAY, ONE_MAN |
| `0641` | AWAY, ONE_MAN (ruling 18) |
| `1550`, `0551`, `1460` | AWAY, HOME, HOME (review checks) |
| `1441`, `1331`, `0651`, `1560` | none |
| missing, `"15x1"`, `"1571"`, `0331`, `0101`, `1010` | none |

**Last goal** (`NhlMapper.lastGoal`, landing only): from `summary.scoring`, take the entry with the highest
`awayScore + homeScore`. It becomes `GoalFact(side = isHome ? HOME : AWAY, away, home, pp = strength == "pp")`. Odd or
missing entries give null.

### 1.3 Contracts (`SportsContracts.kt`, commit 0; trailing and defaulted)

```kotlin
enum class PowerPlayKind { ONE_MAN, TWO_MAN }                                  // append-only
data class PowerPlay(val side: Side, val kind: PowerPlayKind, val secondsRemaining: Int?)
data class GoalFact(val side: Side, val away: Int, val home: Int, val pp: Boolean)
GameSnapshot(…, val powerPlay: PowerPlay? = null, val lastGoal: GoalFact? = null)
ScoreBugView(…, val powerPlay: PowerPlay? = null, val goalPp: Boolean = false, val ppEnded: Boolean = false)
```

`TeamLine.name`'s KDoc is corrected: the landing gives "Edmonton Oilers", not the common name (N4).

### 1.4 Machine (`ScoreBugMachine`)

- **`view().powerPlay`** is the shown snapshot's power play when:
  - the phase is LIVE or INTERMISSION;
  - the period kind is not SO;
  - the view is neither stale nor waiting.

  Otherwise it is null. `knownView()` always has null.
- **`goalPp`**, set where a goal is flagged, only if the previous snapshot `p` is LIVE or INTERMISSION, and neither `p`
  nor the goal snapshot is SO (fix pass S4: a power play carried over an intermission and scored on early in the next
  period is compared against the intermission poll; the shootout winner never gets PP GOAL):
  1. If `s.lastGoal` has the scorer's side and equals the new score, use `lastGoal.pp`.
  2. Otherwise (the summary lags) use the fallback, `p.powerPlay?.side == side`.

  It is stored with the mark, and cleared with the mark and in `reset()` (N12).
- **The fallback's known misses**, written in its KDoc:
  - a minor that expires inside a poll gap followed by an even-strength goal from the same team gives a false PP GOAL;
  - a power play that starts and is scored on inside one gap gives no PP GOAL;
  - if `situation` clears a bucket before the score, there is no PP GOAL.
- **`ppEnded`** (for §7): set on the view when the previous fresh view had a power play, this fresh view has none, and
  this view flagged no goal **and changed no score** (fix pass N5: an unflagged goal, under UNSEEN demand or after a
  long gap, is never "Power play over."). It is the only source of "Power play over.".
- Polling, stale, hide and the delay queue are unchanged.

---

## 2. Clock and freshness (rulings 13 and 19)

- **The seconds** are the shown snapshot's, drawn `m:ss` in tabular cells. They change only with a new visible
  snapshot. A reported `0:00` is shown as is.
- **Delay:** automatic, because the power play rides on the snapshot.
- **Stale or waiting:** the tab and the pill track close. A stale time is wrong. They reopen with a reveal when fresh
  data arrives.
- **Intermission:** the tab stays, frozen (ruling 19). It is static and costs no frames.
- **Polling:** unchanged. A live follow already polls each 20 s bucket, and nothing is fetched with nothing followed.

---

## 3. Animation

### 3.1 Runner, content and identity (B1, B2, S3)

`PpRun` is a plain holder in `ScoreBug.kt`. It owns:
- `tabDriver` and `pillDriver`, two `Animatable<Float>` of elapsed ms, read only in draw;
- the preallocated `PpFrame` instances `from` and `out`, so draw never allocates;
- `PpContent tab`: `side`, `label` (`ONE_MAN` / `TWO_MAN` / `PP_GOAL`) and `secs` (−1 = none);
- `pillSide`.

**Inputs, written in composition after the GoalRun bookkeeping:**
- `target` = `view.powerPlay`. It drives the pill track.
- `tabOn` = `tabAllowed(…) && target != null && labels baked for this size and these teams`.
- `pinned` (§5).

**Kick keys.** `SideEffect` kicks a track only when its key changes:
- the pill key is `(target != null, target.side)`;
- the tab key is `(tabOn, target.side, target.kind, pinned)`.

Any other recomposition (placement, `fadeDone`, a toast, the mark ending, a new seconds value) never kicks. A kick
whose target equals the in-flight transition's target never cancels it.

**Seconds.** While not pinned, and while `target` and `tab` agree on side and kind, composition writes `tab.secs`
from the view. The draw lambda is rebuilt in that composition, as the clock's is, so the tab redraws with no
animation.

**Each track's runner** is one long-lived effect: `kicks.collectLatest { step() }`.
- `step()` captures `from` by sampling the interrupted transition at its current `t`.
- It captures `PpContent` from `target` when it starts an open.
- It then runs one transition.

| Track: shown → target | Transition |
|---|---|
| tab: closed → X | **OPEN**, with X's content |
| tab: X → closed (`tabOn` false, or the PP ends) | **CLOSE**, keeping X's content |
| tab: X → X′, same side, kind changed | the label switches, plus one **PULSE** (450 ms); no slide |
| tab: X → Y, other side | CLOSE with X's content, then OPEN with Y's; `begin(side)` before each |
| pill: off → side / side → off / A → B | tint and halo in / out / out, then in |

**Hiding (fix pass N4).** In place mode both tracks park at once (the sample bug shows). When the bug hides
(`box == null`), nothing kicks: the tab and the tint stay as they are and fade with the bug's 300 ms fade, and the tracks
park once the fade is done.

**Interrupts (fix pass S1).** A new transition samples the interrupted one into its own `next` frame, snaps the driver
to 0 and only then assigns `from`, `kind` and `to`. The snap can suspend while the cancelled `animateTo` still holds the
driver's mutex; until it lands, draw keeps sampling the old transition at its frozen time, so no frame ever draws the
new transition at the old elapsed time.

**Snaps** (no animation, straight to rest), in these cases:
- the first **non-waiting** view of a follow, or a new game (N5);
- the bug reappearing;
- a size change;
- leaving place mode;
- the labels becoming ready after a size change's bake (the tab was closed meanwhile; S8).

### 3.2 Timeline API (`:core`, commit 0)

```kotlin
enum class PpTrack { TAB, PILL }
enum class PpTransition { OPEN, CLOSE, PULSE, STILL }
class PpFrame {                                    // every field named in commit 0
    var slide = 0f; var shadowAlpha = 0f; var textAlpha = 0f; var textDy = 0f    // tab; slide 0 = hidden, 1 = out
    var shineOn = false; var shineX = 0f; var labelScale = 1f
    var tint = 0f; var halo = 0f                                                 // pill
    var tabClosed = true; var pillClosed = true
    fun copyFrom(o: PpFrame)
}
object PowerPlayTimeline {
    /** Writes only [track]'s channels. [to]: STILL's rest state (open or closed). */
    fun sample(track: PpTrack, kind: PpTransition, tMs: Float, from: PpFrame, to: Boolean, out: PpFrame): PpFrame
}
```

### 3.3 Open, close and pulse (t = 0 at the start; easing per keyframe segment)

`ease` = `(.25,.1,.25,1)`, `ease-in-out` = `(.42,0,.58,1)`, `snap` = `(.2,.8,.2,1)`, `std` = `(.4,0,.2,1)`.

| Track | Channel | OPEN start / duration / easing | CLOSE start / duration | From → to |
|---|---|---|---|---|
| TAB | slide | 0 / 550 / snap | 0 / 550 / snap | 0 ↔ 1 |
| TAB | shadow | 0 / 400 / ease | 0 / 400 | α 0 ↔ .5 |
| TAB | text alpha | 380 / 300 / ease | 0 / 200 / ease | 0 ↔ 1 |
| TAB | text offset | 380 / 350 / ease | 0 / 200 / ease | −4 ↔ 0 (toward the pill) |
| TAB | shine | 500 / 900 / std | none | left edge −72 → 425 (tab-local) |
| TAB | label pulse | 1 000 / 2 × 450 / ease-in-out per half | none | ×1 → 1.07 → 1 |
| PILL | tint, halo | 0 / 600 / ease | 0 / 600 / ease | 0 ↔ 1 |

- An OPEN lasts 1 900 ms and a CLOSE 600.
- **PULSE** is the label pulse once (450 ms), with every other channel held at rest.
- Every channel starts from `from` (CSS transition semantics).
- The shine and the pulse play only on an OPEN that starts with `from.slide ≤ .05`.
- **The close follows the CSS** (lead, Q4): the text fade and the slide start together.

### 3.4 Drawing without blur (`PowerPlayPainter`, `:ui`)

**Per size** (in the off-main-thread setup step, beside `GoalPainter`):
- the tab shadow mask: `ALPHA_8`, the tab's round rect through `BlurMaskFilter`. Its radius is `(σ·d − 0.5) / 0.57735`
  with σ in **design px × d** (N1);
- the shine band shader (GA S4 soft stops);
- paints and the `m:ss` digit table.

**Per size and teams**, keyed on `(s, shownLarge, away.abbrev, home.abbrev)` (S8):
- five label masks: `{away|home} POWER PLAY`, `{away|home} 5-ON-3`, and `PP GOAL`;
- each `ALPHA_8` at 1.25×;
- each drawn twice: black .35 at the hard offset, then white.

**Per logo bitmap identity:** the halo mask (alpha → blur, σ 4 / 5 the same way) intersected with the pill (`DST_IN`).
It is never baked in low-end mode.

**Per transition start** (`begin(side, colours)`, in the runner):
- the tab's vertical gradient (§6.1);
- the tint's horizontal gradient.

**Draw** uses `save`, `restore`, `clipRect`, `translate`, `scale`, `drawRoundRect`, `drawBitmap` and
`drawText(char[])` only.
- **The shine** (N10) is the tab's round rect drawn with the band shader under `translate(dx)`, offset by `−dx` (GA
  §2.2). So the shine gets the tab's rounded corners with no `clipPath`.

### 3.5 Composite (`ScoreBugPainter.drawFrame`)

```kotlin
fun drawFrame(c, glass, f, logos, logoWaited, placing, goal, g, glow, glowOn,
              pp: PowerPlayPainter?, p: PpFrame?, content: PpContent?, pillSide: Side?, up: Boolean)
```

The order:
1. goal glow;
2. glass;
3. **tab**, under the outside `clipRect`: shadow, gradient round rect, shine, then the text block. The text block is
   the label mask at `labelScale`, the divider and the `m:ss` digits. `content.label == PP_GOAL` or `secs < 0` draws
   the label alone, centred. The tab is skipped when `p.tabClosed` or `content == null`;
4. **tint**: the pill round rect with the tint shader at `p.tint × tintScale`;
5. **halo**: under the `pillSide` logo, at `.95 × p.halo × tintScale`;
6. body;
7. takeover.

`up` mirrors the tab's position, slide, text offset and gradient. It never mirrors the text, the shine's lean or the
shadow's dy (N7).

### 3.6 Still version

Low-end mode, or `MotionDurationScale == 0` in the runner (GA S8):
- `STILL` snaps each track to rest;
- there is no shine and no pulse;
- the PP GOAL swap happens at frame A.

Low-end mode never bakes the shadow or the halos.

### 3.7 Cost

- An open is about 114 frames (60 Hz), a close 36.
- Damage stays within the bounded layer.
- At rest there are no frames.

---

## 4. Geometry

### 4.1 Sizes (design px; Large = Standard × 1.25; the mock is 780 × 128)

| Item | Mock | Standard (404 × 70) | Large (506 × 88) |
|---|---|---|---|
| Tab side inset | 60 | 31 → width **342** | 39 → width **428** |
| Visible depth | 60 | **36** | **45** |
| Hidden overlap | 32 | 20 | 25 |
| Far-corner radius | 34 | 18 | 22.5 |
| Type | 25/800 | **20**/700, tracking .05 em | **25**/700 |
| Gap / divider | 20 / 2 × 26 at .5 | 10 / 1 × 16 white .5 | 12.5 / 1.25 × 20 |
| Text shadow (hard) | 0 1 2 .35 | dy 1, black .35 | dy 1.25 |
| Text centre from the pill edge | 29 | 18 | 22.5 |
| Slide travel / text offset | 66 / 8 | 40 / 4 | 50 / 5 |
| Shine width / travel | 60 / −140 → 820 | 31 / −72 → 425 | 39 / −90 → 531 |
| Tab shadow | 0 10 30 .5 | dy 5, σ 8 (× d), α .5 | dy 6, σ 8 (× d, capped) |
| Halo | drop-shadow 14 | σ 4, α .95 | σ 5 |
| Tint stops from the power-play end | .9 @ 0, .5 @ 25 %, 0 @ 58 % | same, × `tintScale` | same |

**Fit**, measured from `instrument_sans_bold.ttf` (the review re-measured it):

| Size | Widest ("WPG POWER PLAY") | "0:00" slot | Total | Room |
|---|---|---|---|---|
| Standard | 203 | 47 | 271 | 306 |
| Large | 254 | 58 | 339 | 383 |

- The seconds slot is fixed. With `PP_SECS_MAX` at 599, the time is never 5 glyphs (N2).
- A string that does not fit steps 2 px down to the 18 px floor, then drops its tracking. There is no ellipsis.
- All type is ≥ 18 px.

### 4.2 Direction (ruling 16)

The direction follows the **effective** corner, after `textTrackOn`:
- top corners drop the tab below the pill;
- bottom corners open it upward.

### 4.3 Clip, layer, SurfaceView

- **The clip.** It is `clipRect(0, edge, pillW, edge + M·d)` below the pill, or the mirror above, with
  `edge = round(pillH)` (or 0) in device px (N9).
  - The tab's round rect starts 20 px inside the pill.
  - The curve gap at the inset is 0.23 / 0.28 px.
  - There is no `clipPath` and no `saveLayer`.
  - It is drawn after the glass, so the 14 % transparency of the glass never shows the tab, and the tab's colour is
    true.
- **Reach past the pill** = depth + shadow dy + 3σ:
  - Standard: 36 + 5 + 24 = **65** ≤ `M` 74;
  - Large: 45 + 6 + 24 = **75** ≤ 76.

  σ is × d, not × s (N1).
- **Nothing new clips an ancestor of the SurfaceView.** The tab is drawn inside the existing pill `Spacer`'s
  `drawBehind`, in the existing leaf layer. There are no new modifiers, layers or nodes. `RootTreeRuleTest` and
  `ScoreBugBoundsTest` stay unchanged.

### 4.4 Placement (`ScoreBugPlacement`)

```kotlin
fun tabBox(input: PlacementInput, box: Box): Box
fun tabAllowed(input: PlacementInput, geo: OverlayGeometry, box: Box): Boolean
```

`tabAllowed` is false in place mode, or when an active overlay (channel list, stats, group card, picker, number entry,
probing line) intersects `tabBox` inflated by `PP_TAB_NEIGHBOUR` = 29, the shadow's reach (N8).
- When it is false, only the tab track closes. The pill track stays (S3).
- **Bars** lift a bottom bug from below, and its tab opens upward, so no bar meets a tab.
- At rest, the tab's edges are at y 162 (top corners) and y 918 (bottom corners), Standard.

---

## 5. With the goal takeover (ruling 15)

**Pin.**
- When `GoalRun` starts a takeover (GA §5.2 step 4), `PpRun` captures:
  - `pinned = true`;
  - `pinContent` = a copy of what the tab is drawing now. On a second goal mid-pin, that is the current content,
    `PP GOAL` included, held only until that goal's lead covers the pill (the B2.4 override below);
  - `pinTabOn` = the tab is open **and** `tabAllowed` at goal time;
  - `ppGoal = v.goalPp && pinTabOn && goalSide == pinContent.side` (PP GOAL only on the scorer's own tab);
  - `ppGoalContent.side = pinContent.side`, set here (fix pass N3), so a release always slides PP GOAL out in the
    right colours.
- **While pinned, the tab track is frozen.**
  - The kick keys see `pinned` and stay unchanged until release.
  - `tabOn` changes are deferred. **Lead (S3):** the pin may keep the tab over an overlay that opens mid-takeover,
    until 3 600, only because `tabAllowed` was true at goal time. If `pinTabOn` was false, the tab stays closed.
  - **The pill track holds until covered (fix pass S2, amending "may follow `target`").** While the takeover is pending,
    or at t < `LEAD_COVERED_MS`, the pill is not kicked: the tint and the halo keep the previous frame's values, like
    the tab and the body. When the lead covers the pill (a child of the takeover watching `driver.value`, or frame A on
    the still path) the pill follows `target`. Every end of the takeover (cancel, skip, release, `finally`) also ends
    the hold.
- **A second goal during a PP GOAL takeover gets its own label (lead override of review B2.4).** Until its lead covers
  the pill, the tab holds what was drawn (PP GOAL). Then:
  - if the new goal's `goalPp` is true for the tab's side, the tab says PP GOAL;
  - otherwise, if the power play goes on for the tab's side, the tab cuts to its current words and seconds (no pulse,
    no slide);
  - otherwise (it is over, or it is the other side's), the tab CLOSEs, sliding PP GOAL out, and stays closed until the
    release, which then opens the other side's power play if there is one.

**Draw**, from the takeover's `t`:

| t | Tab content |
|---|---|
| pending, or < `LEAD_COVERED_MS` (216) | `pinContent` (the spoiler hold) |
| 216 ≤ t, still pinned, `ppGoal` | `PP_GOAL`, a plain swap with no pulse (lead, Q5) |
| 216 ≤ t, still pinned, not `ppGoal` | `pinContent` with `secs` updated only if the view's power play is the same side (rule: never another side's words on this tab) |

**Release (S4).**
- GA's single `animateTo(TAKEOVER_TOTAL_MS)` stays.
- The takeover effect also launches a child coroutine:
  `snapshotFlow { driver.value >= GOAL_TAKEOVER_MS }.first { it }; ppRun.release(token)`. It is cancelled with its run.
- The still path calls `release(token)` after its `delay(GOAL_TAKEOVER_MS)`.

`release` is called from:
1. the child coroutine or the still path, at 3 600;
2. the guarded `finally` (`run.token == token`);
3. the "no painter" skip;
4. `cancelTakeover()`, **except** on the new-goal path (`cancelTakeover()` then restart): there the pin continues.

Then `pinned = false` and both keys are re-evaluated:
- **at 3 600:** the tab CLOSEs if the power play is over; a side change closes then opens. If the same side is still
  on, its content swaps to the current label and seconds as a plain cut, with no pulse (N6).
- **via a cancel (hide, place mode, size, new game):** the tab snaps.

| Case | Result |
|---|---|
| PP goal (minor), the PP ends in the goal snapshot | hold → PP GOAL → CLOSE from 3 600 |
| PP goal at 5-on-3 | PP GOAL → "EDM POWER PLAY m:ss" cut at 3 600 |
| Shorthanded goal (`strength` `sh`) | no PP GOAL; the opponent's tab stays, its seconds updating |
| Even-strength goal, PP starts in the goal snapshot | tab closed through the takeover; OPEN at 3 600 |
| Tab not allowed at goal time | no PP GOAL, and the tab stays closed |
| Overlay opens mid-takeover | the tab stays until 3 600, then CLOSEs |
| A second goal mid-pin | the pin continues; `pinContent` = what is drawn until the new lead covers; `ppGoal` recomputed |
| A second, non-PP goal during PP GOAL | held, then the current power play's words, or a CLOSE if it is over (B2.4 override) |

---

## 6. Colours (rulings 12 and 17)

### 6.1 Derivation (`NhlTeamColours`)

`TeamColours` gains two fields: `tab: Int` (default `base`) and `tintScale: Float` (default 1). Both are derived in
`derive`.
- **The tint and halo colour is `glow`.** The mock's EDM blue tint is a recorded deviation (D1): true navy does not
  read on the glass.
- **The tab colour is `tab`:** `glow` if C5 passes on it, otherwise the other team colour.
- **The tab gradient** runs over the tab's full rect, dark at the pill side:
  `mixBlack(tab, .70)` @ 0 → `mixBlack(tab, .28)` @ .5 → **`mixBlack(tab, .10)` @ 1** (ruling 17, review S10).
  The darker far stop is a shade of the team's own colour, as the mock's own gradient is, so ruling 12 holds.

### 6.2 C5: white tab text (asserted, floor 4.5, `C5_EXCEPTIONS` empty)

- **The measured point** is the text's far cap edge, gradient position .80. With the new far stop that is
  `mixBlack(tab, .172)`, i.e. `tab` × 0.828.
- **Rationale for 4.5:** 20 design px on a TV is not "large text" (GA S6).

### 6.3 C6: white digits on the tinted glass over white ice (asserted, floor 4.5; S2)

- **The measured point** is white against `glow` at tint alpha × `tintScale`, over `GLASS_WHITE_ICE`. It is taken at
  the digit column's outer edge (x 92 of 404, where the tint alpha is .536).
- **The scale:** `tintScale` starts at 1 and drops in 5 % steps until C6 passes. The halo alpha uses the same scale.
- **Logos** are multicoloured and not asserted. The TOR at-rest frame over white ice is an eye check (N14).

### 6.4 The table (all 32 teams and the fallback; computed 2026-11-08)

| Team | glow | C5 on glow | Tab | C5 | tintScale | C6 | | Team | glow | C5 on glow | Tab | C5 | tintScale | C6 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ANA | `#FE4111` | 4.91 | **glow** | 4.91 | 1 | 6.95 | | NJD | `#CE1126` | 7.46 | glow | 7.46 | 1 | 9.55 |
| BOS | `#FFB81C` | 2.54 | base `#000000` | 21.0 | .90 | 4.55 | | NYI | `#00539B` | 9.71 | glow | 9.71 | 1 | 10.39 |
| BUF | `#FFB81C` | 2.54 | base `#003087` | 13.66 | .90 | 4.55 | | NYR | `#CE1126` | 7.46 | glow | 7.46 | 1 | 9.55 |
| CGY | `#D2001C` | 7.47 | glow | 7.47 | 1 | 9.81 | | OTT | `#DA1A32` | 6.75 | glow | 6.75 | 1 | 8.88 |
| CAR | `#CE1126` | 7.46 | glow | 7.46 | 1 | 9.55 | | PHI | `#F74902` | 4.95 | **glow** | 4.95 | 1 | 6.92 |
| CHI | `#CF0A2C` | 7.52 | glow | 7.52 | 1 | 9.70 | | PIT | `#FCB514` | 2.60 | base `#000000` | 21.0 | .90 | 4.64 |
| COL | `#236192` | 8.48 | glow | 8.48 | 1 | 9.30 | | SJS | `#006D75` | 7.97 | glow | 7.97 | 1 | 9.07 |
| CBJ | `#CE1126` | 7.46 | glow | 7.46 | 1 | 9.55 | | SEA | `#99D9D9` | 2.31 | base `#001628` | 18.94 | .85 | 4.50 |
| DAL | `#006847` | 8.77 | glow | 8.77 | 1 | 9.59 | | STL | `#FCB514` | 2.60 | base `#002F87` | 13.78 | .90 | 4.64 |
| DET | `#CE1126` | 7.46 | glow | 7.46 | 1 | 9.55 | | TBL | `#FFFFFF` | 1.50 | base `#002868` | 15.53 | **.60** | 4.82 |
| EDM | `#FF4C00` | 4.69 | **glow** | 4.69 | 1 | 6.65 | | TOR | `#FFFFFF` | 1.50 | base `#00205B` | 16.81 | **.60** | 4.82 |
| FLA | `#C8102E` | 7.76 | glow | 7.76 | 1 | 9.78 | | UTA | `#69B3E7` | 3.28 | base `#010101` | 20.87 | 1 | 4.84 |
| LAK | `#A2AAAD` | 3.38 | base `#111111` | 19.30 | 1 | 4.95 | | VAN | `#00843D` | 6.49 | glow | 6.49 | 1 | 7.91 |
| MIN | `#A6192E` | 9.55 | glow | 9.55 | 1 | 10.85 | | VGK | `#B4975A` | 3.95 | base `#333F42` | 12.77 | 1 | 5.54 |
| MTL | `#AF1E2D` | 8.86 | glow | 8.86 | 1 | 10.32 | | WSH | `#C8102E` | 7.76 | glow | 7.76 | 1 | 9.78 |
| NSH | `#FFB81C` | 2.54 | base `#041E42` | 17.53 | .90 | 4.55 | | WPG | `#004C97` | 10.46 | glow | 10.46 | 1 | 10.80 |
| | | | | | | | | *fallback* | `#5CA8F2` | 3.60 | base `#2A3140` | 14.58 | 1 | 5.19 |

- **Which colour goes where:**
  - **21 teams** use one colour for the tab, the tint and the halo. That is 18 plus EDM, ANA and PHI, now orange.
  - **11 teams** have a dark tab with a bright tint and halo: BOS, BUF, LAK, NSH, PIT, SEA, STL, TBL, TOR, UTA and VGK.
    Example: NSH has a navy tab with a gold tint (×.9).
- **The tint is weakened for 8 teams:** TBL and TOR (×.60), SEA (×.85), and BOS, BUF, NSH, PIT and STL (×.90).

---

## 7. Accessibility (S5, N3, N4)

- **The live region** (the persistent `Spacer`, GA §5.3) takes the first of these that applies:
  1. while the goal mark is set: the goal string;
  2. on a fresh view whose power play **began or changed kind**: "Edmonton Oilers power play." or "Edmonton Oilers
     five on three.";
  3. on a fresh view with `ppEnded` (§1.4): "Power play over.";
  4. a stale or waiting view leaves the text unchanged;
  5. any other new view sets it to "", including the view where a goal mark clears.

  So each start, kind change and real end is said once. It is never repeated after a goal, never said on going stale,
  and never said for a `tabAllowed` closure. A new follow resets it to "".
- **`Describe`** gains `sports_desc_pp` ("%1$s power play, %2$s left") or, with no seconds, `sports_desc_pp_no_time`
  ("%1$s power play"). They are joined in code with ". ". There is no leading-space resource.
- **The names** are `TeamLine.name` ("Edmonton Oilers"), as in the goal string.
- **Device item:** VoiceView.

---

## 8. Fake panel (`tools/fakepanel/sports.py`, `test_sports.py`; B3, S6)

**Opt-in.** The power plays are behind a `sports_pp` config key (`--sports-pp` on the command line, also through
`POST /admin/config`), off by default.
- **Off:** the featured timeline, bodies and goals are **byte-for-byte as today**. GA's emulator script and
  `GoalFramesTest` are untouched.
- **On:** `PP_SEGMENTS` replaces `SEGMENTS`. The landing then carries `situation` and `summary.scoring`, and the day
  list carries `situation`.

`PP_SEGMENTS`:
- every visible window is ≥ 20 s (one CDN bucket at least);
- seconds run 1 s/s with the clock and freeze in intermission;
- the score still ends tied, so the shootout is unchanged.

| Segment (length) | Events | Proves |
|---|---|---|
| 1st LIVE (60) | **CGY goal at 15, even strength** (`ev`); EDM `1451` 35–60, secs 30 → 5 | a plain takeover with no tab; open |
| 1st INT (30) | EDM `1451`, frozen at 5 | intermission (ruling 19) |
| 2nd LIVE (60) | EDM `1451` 0–5 (5 → 0); CGY `1541` 8–30, secs 100 → 78; **CGY PP goal at 30** (`pp`; `situation` gone) | close; PP GOAL via `summary.scoring` |
| 2nd INT (20) | none | |
| 3rd LIVE (110) | EDM `1451` 0–20 (secs 120 → 100); **EDM `1351` 20–40, secs 40 → 20** (the first expiry); **EDM PP goal at 40** (`pp`); EDM `1451` 40–**85**, secs 80 → 35 (the remaining minor, **45 s**); **EDM goal at 100, even strength** | the pulse on 5-on-4 → 5-on-3; PP GOAL at 5-on-3, then the cut back; a second plain goal |
| OT (30) | CGY `1431` 5–25 | 4-on-3 reads POWER PLAY |
| SO, OFF, FINAL | as today (EDM wins) | none in the shootout |

The score is 2–0 after CGY's two goals and 2–2 after EDM's two, so the game still goes to the shootout.

**Script keys.**
- `STATE_KEYS` gains `situationCode` (4 digits or `null`), `situationSeconds` (0..599) and `goalStrength`
  (`ev`/`pp`/`sh`, which overrides the latest scoring entry).
- These script a shorthanded goal, an empty net or a bad code.
- `check_config` rejects bad values.

**Tests** (`test_sports.py`):
- **Default:** no `situation` and no `summary` at any featured sample, and the existing assertions unchanged.
- **`sports_pp` on:**
  - every row above, in both bodies;
  - the intermission freeze;
  - the CGY score rising in the same bucket `situation` disappears;
  - `summary.scoring` strengths `ev`, `pp`, `pp`, `ev` with post-goal scores;
  - `1351` → `1451` around the 3rd PP goal;
  - the tie into the shootout.
- **Scripts:** an override and a `null` clear; the config rejections.

---

## 9. Tests

### 9.1 `:core`

- **`NhlMapperTest`:**
  - the fixtures (`1541` 79 s, `1451` 34 s);
  - `lastGoal` from `landing-live-int.json`: UTA 09:24 `pp` and 2–0 are present, and the latest entry (MTL 2–2 `ev`)
    is the one picked;
  - every row of §1.2, including numeric `651` → `0651`, `1361` → none, and the PP cross-check;
  - a broken `situation` still maps the score;
  - new synthetics `landing-pp-*.json` (commit 0).
- **`ScoreBugMachineTest`:**
  - the gating (LIVE and INTERMISSION; not SO, FINAL, stale or waiting);
  - the delay;
  - `goalPp` from `lastGoal` (`pp` → true, `sh` → false, `ev` while `p` had a power play → false);
  - the fallback when `lastGoal` lags, and each documented miss;
  - `p` in SO → false;
  - `reset()` clears `goalPp`;
  - `ppEnded` only on a fresh end with no goal;
  - polls identical with and without a power play.
- **`ScoreBugPlacementTest`:** `tabBox` for 4 corners × 2 sizes and with `textTrackOn`; `tabAllowed` with each
  overlay at 28 and 30 px; reach ≤ `M` for both sizes.
- **`NhlTeamColoursTest`:**
  - C5 ≥ 4.5 and C6 ≥ 4.5 for the 32 teams and the fallback, with `C5_EXCEPTIONS` empty;
  - `tab` pinned: CGY `#D2001C`, EDM `#FF4C00`, NSH `#041E42`, PHI `#F74902`;
  - `tintScale` pinned: TBL and TOR .60, SEA .85, NSH .90, CGY 1.
- **`PowerPlayTimelineTest`:**
  - every instant in §3.3, with mid-segments against a reference bezier (1e-3);
  - the PULSE;
  - interrupted OPEN → CLOSE and CLOSE → OPEN starting from `from`;
  - no shine on a reversed close;
  - STILL;
  - each track writes only its own channels;
  - `sample` allocates nothing (10 000 calls, delta < 1 KB).

### 9.2 `:ui` `PowerPlayScreenshotTest` (native graphics, through `drawFrame`)

- **Frames:**
  - OPEN at 0, 150, 300, 550, 700, 1 000, 1 225, 1 450, 1 900;
  - CLOSE at 100, 300, 600;
  - PULSE at 225;
  - STILL;
  - for CGY, EDM, NSH, PHI and **TOR**, at Standard and Large, top-right and bottom-left, over dark and white ice;
  - plus 1280 × 720 frames for the seam row (N9).
- **Assertions:**
  - at rest, the far-end tab pixel ≈ `mixBlack(tab, .10)` (±3); C5 and C6 measured from the pixels;
  - **no tab pixel inside the pill**, and no seam row at 720p;
  - the tint at the power-play end is `glow` × `tintScale`, and the other end is plain glass;
  - the halo lies inside the pill;
  - CLOSE frames still show the opening content's text (B2);
  - after a close, the frame equals the plain bug;
  - nothing is drawn outside the pill + `M`;
  - low-end frames have no shadow or halo.
- **Allocation:** 300 `drawFrame` calls with the tab open and pulsing, delta < 4 KB.

### 9.3 `:ui` `ScoreBugDrawTest` additions

- **B1:** a seconds update, a toast and a `fadeDone` flip, each at 700 ms into an OPEN. The tab still reaches rest at
  1 900 and the driver is never re-started.
- **Recompositions:** a power-play start gives 1 recomposition and 0 during the reveal. A seconds update gives no kick.
- Stale → both tracks close; fresh → reveal.
- First non-waiting view with a delay: a snap.
- Hidden, then shown: a snap.
- Place mode: no tab.
- `tabAllowed` false: the tab closes and the tint stays.
- Labels pending after a size change: tab closed, then snapped open.
- A kind change: PULSE only. A side change: CLOSE with X's words, then OPEN with Y's.
- **The PP GOAL interplay:**
  - the hold before 216, then PP GOAL;
  - open at 3 599 and closing at 3 700;
  - SH: no PP GOAL;
  - a 5-on-3 goal: a cut back with no pulse;
  - an overlay opening mid-takeover keeps the tab to 3 600;
  - not allowed at goal time: stays closed;
  - a second goal mid-pin keeps PP GOAL;
  - a cancel snaps;
  - the still path swaps at frame A;
  - with `MotionDurationScale` 2, the release still happens at driver 3 600.
- **Takeover timing:** GA's existing takeover tests pass unchanged (one tween).
- **Live region:** start → goal → mark clears to "" → real end "Power play over." → stale (text unchanged).

### 9.4 `GoalSeamTest`

A real PP goal through `ScoreBugWith`: every tab pixel before `LEAD_COVERED_MS` equals the previous frame, then the
`PP GOAL` mask is drawn.

### 9.5 Instrumented (`app/src/androidTest/.../GoalFramesTest.kt`)

- **The takeover window** arms on the **score change**: poll the panel's `/admin/sports` for the featured game's score
  rise, then wait for the draw burst. It no longer arms on the first draw burst (B3). `sports_pp` stays off.
- **A separate PP window** turns `sports_pp` on and measures the 1st-period reveal with the GA §8.4 gates. The device
  run is open.

---

## 10. Work packages

### Commit 0 (lead, Opus). Gate: `:core:test :ui:testDebugUnitTest :app:assembleDebug lintAll`

| File | Change |
|---|---|
| `core/.../sports/SportsContracts.kt` | `PowerPlayKind`, `PowerPlay`, `GoalFact`; `GameSnapshot.powerPlay`, `.lastGoal`; `ScoreBugView.powerPlay`, `.goalPp`, `.ppEnded`; `TeamLine.name` KDoc (N4) |
| `core/.../sports/SportsConstants.kt` | §11 |
| `core/.../sports/NhlTeamColours.kt` | `TeamColours.tab = base`, `tintScale = 1f` (trailing defaults); `C5_EXCEPTIONS = emptySet()` |
| `core/.../sports/PowerPlayTimeline.kt` (new) | `PpTrack`, `PpTransition`, `PpFrame` (every field and `copyFrom`), `PpContent` (`side`, `label: PpLabel { ONE_MAN, TWO_MAN, PP_GOAL }`, `secs`, `copyFrom`); `sample(…)` = `TODO()` |
| `core/.../sports/ScoreBugPlacement.kt` | stubs `tabBox`, `tabAllowed` |
| `core/src/testFixtures/.../FakeScoreRepository.kt` (`SportsFixtures`) | `game(…, powerPlay = null, lastGoal = null)`; `liveView` copies `powerPlay`; `ppGame(side, kind, secs)` (S7) |
| `core/src/test/resources/sports/nhl/` | `landing-pp-*.json` synthetics; the three evidence files (N11) |
| `ui/.../sports/PowerPlayPainter.kt` (new) | stub: `PowerPlayPainter(s, d, pillW, pillH, sansBold)`, `bakeSize()`, `bakeLabels(away, home, res)`, `bakeHalo(side, logo)`, `begin(side, colours)`, `draw(c, p, content, pillSide, up)`; no-op bodies |
| `ui/.../sports/ScoreBugPainter.kt` | the `drawFrame` signature (§3.5); new params pass through. No PP fields in `ScoreBugFrame` (B2) |
| `ui/src/main/res/values/strings_sports.xml` | `sports_pp_label` "%1$s POWER PLAY", `sports_pp_two_man` "%1$s 5-ON-3", `sports_pp_goal` "PP GOAL", `sports_live_pp` "%1$s power play.", `sports_live_pp_two_man` "%1$s five on three.", `sports_live_pp_over` "Power play over.", `sports_desc_pp` "%1$s power play, %2$s left", `sports_desc_pp_no_time` "%1$s power play" |
| `ui/src/test/.../ScoreBugTestSupport.kt` | `ppView`, `ppGoalView` (commit 0 only) |

After commit 0, every file below belongs to exactly one WP.

### WP-1: core and fake panel (Opus for core, Sonnet for the panel)

| File | Change |
|---|---|
| `core/.../sports/NhlMapper.kt` | `powerPlay`, `lastGoal` (§1.2) |
| `core/.../sports/ScoreBugMachine.kt` | gating, `goalPp`, `ppEnded`, `reset` (§1.4) |
| `core/.../sports/ScoreBugPlacement.kt` | `tabBox`, `tabAllowed` |
| `core/.../sports/NhlTeamColours.kt` | `tab`, `tintScale` (§6) |
| `core/src/test/.../NhlMapperTest.kt`, `ScoreBugMachineTest.kt`, `ScoreBugPlacementTest.kt`, `NhlTeamColoursTest.kt` | §9.1 |
| `tools/fakepanel/sports.py`, `fakepanel.py` (the `--sports-pp` flag only), `test_sports.py`, `README.md` | §8 |

### WP-2: timeline and painter (Opus for the timeline, Sonnet for the painter)

| File | Change |
|---|---|
| `core/.../sports/PowerPlayTimeline.kt`, `core/src/test/.../PowerPlayTimelineTest.kt` (new) | §3.2, §3.3, §3.6 |
| `ui/.../sports/PowerPlayPainter.kt` | §3.4, §4 |
| `ui/.../sports/ScoreBugPainter.kt` | the `drawFrame` order (§3.5) |
| `ui/src/test/.../PowerPlayScreenshotTest.kt` (new) | §9.2 |

### WP-3: wiring (Sonnet)

| File | Change |
|---|---|
| `ui/.../sports/ScoreBug.kt` | `PpRun` (two tracks, kick keys, content, runners); the pin, the release child and release sites (§5); the bake jobs keyed per §3.4; `tabAllowed`; the live region and `Describe` (§7); `GoalHooks` seams `ppSampler`, `tabAllowed`, `bakeHalo` |
| `ui/src/test/.../ScoreBugDrawTest.kt`, `GoalSeamTest.kt` | §9.3, §9.4 |
| `app/src/androidTest/.../GoalFramesTest.kt` | §9.5 |

Every WP ends with `./gradlew :core:test :ui:testDebugUnitTest :app:assembleDebug lintAll`. WP-1 also runs
`python3 -I -m unittest tools/fakepanel/test_sports.py`.

The feature ends with:
- a code review;
- an emulator pass, with `--sports-pp`, through every row of §8;
- the device items:
  - frame time over the reveal;
  - VoiceView;
  - the colours on a real TV;
  - a recorded **live** PP landing;
  - whether `summary.scoring` arrives with the score.

---

## 11. Constants (`SportsConstants`; Standard design px, Large × 1.25 unless stated)

| Name | Value |
|---|---|
| `PP_OPEN_MS` / `PP_CLOSE_MS` / `PP_PULSE_MS` | 1 900 / 600 / 450 |
| `PP_SLIDE_MS` / `PP_SHADOW_MS` / `PP_TINT_MS` | 550 / 400 / 600 |
| `PP_TEXT_DELAY_MS` / `PP_TEXT_ALPHA_MS` / `PP_TEXT_MOVE_MS` / `PP_TEXT_OUT_MS` | 380 / 300 / 350 / 200 |
| `PP_SHINE_DELAY_MS` / `PP_SHINE_MS` / `PP_PULSE_DELAY_MS` / `PP_PULSE_SCALE` | 500 / 900 / 1 000 / 1.07 |
| `PP_REVERSE_SLIDE_MAX` (no shine or pulse above this) | .05 |
| `PP_TAB_INSET` / `PP_TAB_DEPTH` / `PP_TAB_HIDDEN` / `PP_TAB_RADIUS` | 31 / 36 / 20 / 18 |
| `PP_TEXT_PX` / `PP_TRACK_EM` / `PP_GAP` / `PP_DIVIDER_W`, `_H`, `_ALPHA` | 20 / .05 / 10 / 1, 16, .5 |
| `PP_TEXT_SHADOW_DY` / `_ALPHA` | 1 / .35 |
| `PP_TRAVEL` / `PP_TEXT_DY` | 40 / 4 |
| `PP_SHINE_W` / `_ALPHA` / `_FROM` / `_TO` | 31 / .25 / −72 / 425 |
| `PP_SHADOW_DY` / `_SIGMA` (× d, both sizes) / `_ALPHA` | 5 (Large 6) / 8 / .5 |
| `PP_HALO_SIGMA` / `_ALPHA` | 4 (Large 5) / .95 |
| `PP_TINT_STOPS` / `PP_TINT_ALPHAS` | 0, .25, .58 / .9, .5, 0 |
| `PP_GRADIENT_BLACK` | .70 @ 0, .28 @ .5, **.10** @ 1 |
| `PP_C5_MIN` / `PP_C5_POINT`; `PP_C6_MIN` / `PP_C6_X` | 4.5 / .80; 4.5 / 92 (of 404) |
| `PP_TINT_SCALE_STEP` | .05 |
| `PP_SECS_MAX` / `PP_TAB_NEIGHBOUR` / `PP_LABEL_ATLAS_SCALE` | 599 / 29 / 1.25 |

---

## Lead sign-off (2026-11-08)

Review: `docs/review/2026-11-07-power-play-design-review.md`. Owner rulings 17–19 are in the decision note.

| ID | Disposition | Where |
|---|---|---|
| B1 | **accepted:** kicks only on a transition-identity change (null/non-null, side, kind, pin, tab allowed); seconds only redraw; a same-target kick never cancels; the 700 ms recomposition test | §3.1, §9.3 |
| B2 | **accepted:** the run owns `PpContent`, captured at kick; a close slides its opening content; a side change closes then opens, each with its own content; the pin holds what is drawn. Commit 0 fixes `PpFrame` (with `copyFrom`), `PpContent`, the `drawFrame` signature with `up`, and one typed `PowerPlayTimeline.sample(track, kind, t, from, to, out)` including PULSE; no PP fields in `ScoreBugFrame` | §3.1, §3.2, §3.5, §5, §10 |
| B3 | **accepted:** featured power plays opt-in (`sports_pp` / `--sports-pp`); the default timeline is unchanged; `GoalFramesTest` arms on the score change | §8, §9.5 |
| S1 | **accepted:** PP GOAL from `summary.scoring` `strength == "pp"` matched to the score; the previous-snapshot rule only as a fallback, its misses documented; gated on `p` LIVE and not SO (amended by the fix pass S4: LIVE or INTERMISSION, never SO) | §1.2, §1.4 |
| S2 | **accepted:** C6 with a per-team `tintScale` (TBL and TOR .60, SEA .85, five golds .90); the halo scaled the same way | §6.3, §6.4 |
| S3 | **accepted, modified by the lead:** separate pill (`target`) and tab (`tabOn`) tracks. The pin keeps the tab over a mid-takeover overlay until 3 600 only if `tabAllowed` was true at goal time; otherwise it stays closed | §3.1, §4.4, §5 |
| S4 | **accepted:** one tween; release from a child watching `driver.value`; release sites listed; no release on a new-goal restart | §5 |
| S5 | **accepted:** "Power play over." only on a real, fresh end with no goal, while shown; never after goals or on stale | §1.4, §7 |
| S6 | **accepted:** the 3rd's post-goal 5-on-4 is 45 s; the 5-on-3 seconds are specified; plain even-strength goals kept (1st CGY, 3rd EDM); still a tie into the shootout | §8 |
| S7 | **accepted:** `SportsFixtures` changes in commit 0 | §10 |
| S8 | **accepted:** the label bake is keyed on `(s, size, away.abbrev, home.abbrev)`; per size; the tab stays closed until ready, then snaps | §3.1, §3.4 |
| S9 | **accepted:** no strength fallback; effective skaters 3..5; numeric codes left-padded; a PP cross-check | §1.2 |
| S10 | **owner ruling 17:** far stop `mixBlack(.10)` for every team; EDM 4.69, ANA 4.91, PHI 4.95; no exceptions | §6 |
| N1 | taken: radius conversion; σ × d | §3.4, §4.3 |
| N2 | taken: `PP_SECS_MAX` 599 | §1.2, §11 |
| N3 | taken: the suffix joined in code; a no-time variant | §7 |
| N4 | taken: "Edmonton Oilers"; `TeamLine.name` KDoc fixed | §1.3, §7 |
| N5 | taken: the first **non-waiting** view snaps | §3.1 |
| N6 | decided: the release swap is a cut with no pulse | §5 |
| N7 | taken: the shadow dy is never mirrored | §3.5 |
| N8 | taken: neighbour inflation 29 (the shadow reach) | §4.4 |
| N9 | taken: the clip is snapped to a device px; a 720p seam check | §4.3, §9.2 |
| N10 | taken: the shine technique written down | §3.4 |
| N11 | taken: evidence excerpts saved; commit 0 commits them; a live PP landing is a device item | §1.1, §10 |
| N12 | taken: `reset()` clears `goalPp` | §1.4 |
| N13 | settled by B2 | §3.2 |
| N14 | taken: a TOR at-rest frame over white ice | §6.3, §9.2 |
| Q1 | owner ruling 17 (as S10) | §6 |
| Q2 | owner ruling 18: `0641` keeps the tab | §1.2 |
| Q3 | owner ruling 19: the tab stays frozen in intermission | §2 |
| Q4 | lead: follow the CSS close | §3.3 |
| Q5 | lead: no PP GOAL pulse | §5 |

**Still open:**
1. **Unverified feed facts.** A live (not intermission) PP landing, the 5-on-3 seconds semantics, and whether
   `summary.scoring` lands in the same update as the score are not yet observed. All are device items. The design does
   not depend on the first two, and the S1 fallback covers the third.
2. **The fake panel's sports_pp 3rd period runs 110 s, not 60.** That is opt-in only, but it lengthens the timeline by
   50 s at `sports_timeline_s` 360.

## Fix pass (2026-11-08)

Review: `docs/review/2026-11-08-power-play-code-review.md`. All items taken; the lead overrides B2.4.

| ID | Disposition | Where |
|---|---|---|
| S1 | `PpRun.play` snaps the driver before assigning `from`/`kind`/`to`; a runner test with the real timeline interrupts an open with a close at 700 ms and draws in the kick's own frame (a probe hook after `step()`); the slide never jumps (it fails on the old order) | §3.1 |
| S2 | spec amended: the pill holds until covered; `GoalSeamTest` checks every pill pixel right of the lead (tint included, first shown to be tinted) | §5 |
| S3 | halos cleared on a logo-URL change and on a game change (then re-baked); a halo is drawn only under the very bitmap it was baked from (`drawFrame` passes the side's current logo); built off the main thread, published on it | §3.4 |
| S4 | spec amended: `p` LIVE or INTERMISSION, never SO (`p` or the goal snapshot); a machine test for a PP carried over intermission | §1.4 |
| S5 | the fake sampler honours `from`; a fake logo loader makes the halo watch run (so low-end's zero halos means something); `GoalSeamTest` and `PowerPlayScreenshotTest` run the real halo bake; the pin checks read what the draw passed (`lastDrawContent`), and the re-derived `drawnContent` probe is gone | §9 |
| B2.4 | lead override: a second goal's own label (PP GOAL only for its own `goalPp`), else the data, after its lead covers | §5 |
| N1 | labels built off the main thread and published on it only while the painter and teams still match the bake | §3.4 |
| N3 | `ppGoalContent.side` set in `pin()` | §5 |
| N4 | hiding fades the tab and tint with the bug, then parks | §3.1 |
| N5 | `ppEnded` also requires no score change; it is the only "Power play over." path | §1.4 |
| N6 | left as is (the label fit reserves the seconds slot) | — |
| N7 | `GoalFramesTest` reuses one HTTP client | §9.5 |
| N8 | `test_sports.py` compares every default body at every second 0–420 (and three full instants) to a golden captured from the pre-power-play `sports.py`, byte for byte | §8 |
| N9 | the KDoc now says what is `@Volatile` (the size bake) and what is published on the drawing thread (labels, halos) | §3.4 |
