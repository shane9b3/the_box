# NHL score bug and Sports tab: design

Status: **proposed 2026-11-05; reviewed 2026-11-05; revised 2026-11-05.** Two design reviews:
- architecture/data (A): `docs/review/2026-11-05-score-bug-design-review-arch.md`: blockers A-B1, A-B2; should-fix
  A-S1…A-S12; nits A-N1…A-N12;
- UX/UI (U): `docs/review/2026-11-05-score-bug-design-review-ux.md`: blockers U-B1…U-B4; should-fix U-S1…U-S12; nits
  U-N1…U-N13.

Every finding is applied in place and marked "(A-Sn)" / "(U-Bn)". The lead's rulings come first in the sign-off
(end of the document), and the sign-off lists every finding ID with its disposition.

Owner request and rulings: `docs/decisions/2026-11-05-nhl-score-bug.md` (binding). Mock-up: `design/score-bug-mockup.png`.
API facts: `scratchpad/scorebug/api-facts.md` ("facts §n"), from live fetches made around 19:50 ET on 2026-11-05.
Raw captures are in `scratchpad/scorebug/raw/`. A-review's live check also shows that the ETag is a 20-second CDN
bucket id, not a content hash. Anything still unverified is listed in §11.

Inputs:
- `CLAUDE.md`; plan §3 and §6.
- `01-design-reference.md`; architecture review §E.
- `m4-player-ux.md` §3.9 (now `PlayerUi`).
- Decision 2026-10-15 (keys, quick bar) and its 2026-10-19 amendment; decision 2026-10-19 (Back ladder).
- `m5-setup-settings.md` §3.2.3 (simple-row budget).
- `TmdbProgrammeArtwork`, `ImageModule`, `LogoCache`, `LifecycleCoordinator`, `SmbSessionKeeper`.
- Code on `main` at `41f0bb3`.

Units: sizes are **design px**. 1 design px = window width ÷ 1920 (`BoxDesignScale`, so `1.dp == 1 design px`). Times
are in ms unless marked s. Every number is a default that lives in one named constant (Appendix A). **Must** means a
reviewer rejects code that does otherwise.

**Words on screen** (U-B4, U-S10):
- *Sports*, *Follow*, *Following*, *Stop following*, *Score bug* (the quick-bar label, the owner's word), *Corner*,
  *Delay*, *LIVE*, *GOAL*, *Final*, *INT*, *End*, *OT*, *SO*, *No update*, *Delayed*.
- Never "subscribe", "poll", "feed", "API", or "On screen".

---

## 0. Key decisions at a glance

1. **NHL only, behind a two-type seam.** `League` (one value) and `ScoreSource` (one implementation). Everything above
   the source speaks a normalised `GameSnapshot`. ESPN is the documented fallback source and is not built.
2. **Two endpoints, plain GETs.**
   - Day list: `/v1/score/now` once per open of the Sports tab or the picker. It seeds the authoritative "today"
     (A-N10). After that, `/v1/score/{date}`.
   - The followed game: `/v1/gamecenter/{id}/landing`.
   - **No ETag or If-None-Match** (A-S1).
3. **Zero traffic by default.** Requests happen only while the app is foreground, the device is awake, and either a
   game is followed or the Sports tab/picker is open.
   - Standby, display off, HDMI/CEC off, the screen-off layer and "Show Sports: Off" all mean zero requests (A-S5).
4. **Polls are phase-aligned to the CDN's 20 s buckets.** A live or intermission poll fires 1–2 s after the bucket
   boundary, which is derived from the server's `Date − Age` (A-S2).
   - Before puck drop the bug is **never drawn** (owner ruling 2026-11-05). Pre-game polling only detects the switch
     to live: one poll on Follow, then sleep until 2 min before the start, then one poll per 40 s (every other
     bucket) until the game is live.
   - A final game stops polling.
   - When FULL is not showing: `max(phase cadence, 120 s)` (A-S6).
5. **One pure machine with a wake-up contract.**
   - `ScoreBugMachine` in `:core` exposes `nextWakeAtMono()`.
   - The repository loop sleeps until the next poll *or* the next time-driven change: a delayed snapshot coming due,
     stale/hide, expiry, or the extrapolation cap (A-B1).
   - Every input is serialised through one actor (A-S8).
6. **The clock ticks locally and never jumps up within a period.** Extrapolation runs from `anchor = received − Age`.
   It is capped 40 s after the *last response* (A-S3). The displayed value is monotonic, with a hold of up to 15 s
   (A-S4).
7. **Delay is a timestamped snapshot queue.** Default 0 s (owner ruling 1); the user can set 0–120 s. Goals, "Final"
   and expiry run on the delayed timeline, and the machine wakes for each of them.
8. **The followed game survives a restart.** It expires 5 min after the final (delayed timeline), 8 h after the
   start (start updated from each snapshot, skew-corrected by the server `Date`), or at once if postponed. Each
   removal raises an event, and the event becomes a toast with a reason and a next step (A-B2, U-S12).
9. **Logos** are `{ABBR}_dark.svg` via coil-svg 3.6.3.
   - Each logo is trimmed to its art's alpha bounds once, and drawn in a 64 × 46 slot (U-S1).
   - The two bitmaps are held for the life of the follow, outside Coil's shared memory cache (A-S11).
   - Fallback: the team abbreviation in a disc.
10. **The bug** is a 404 × 70 fully rounded pill (5.77 : 1, matching the mock-up's 5.75 : 1; U-S1).
    - Glass at `.86` with an outer dark ring, legible on white ice (U-B3).
    - Inset 96/56, outside overscan (U-S2). Default corner top right.
    - One redraw per second, on its own layer, with zero recompositions per tick (A-S10).
11. **It does not fight other overlays.**
    - Bottom corners hide for the passive post-zap bar, and slide (150 ms) above a bar the user opened (U-S3).
    - A bottom corner flips to the top while subtitles are on.
    - Anything else that overlaps the bug hides it.
    - **Place mode** moves the bug with the arrows (U-B1).
12. **Follow / Following** (U-B4).
    - In the Sports tab (Guide · Search · Sports · ⚙), OK on a game toggles Follow.
    - The **Score bug** quick-bar item sits right after Subtitles while a game is followed (U-S5). It opens a sheet
      with the followed game, Corner (place mode), Delay, Stop following, then today's games.
13. **Settings:** every Sports row is **ADVANCED**, so the simple budget stays at 35 (lead ruling, U-B2). Corner and
    delay are reachable day to day from the quick bar and the Sports tab.
14. **Commit 0 defines every cross-WP contract** (A-B2): placement input, host facts, the bug source on
    `PlayerExtras`, the player machine's score input and effects, events, strings, and domains.

---

## 1. Scope

**In:**
- NHL regular season, playoffs and preseason.
- One followed game at a time.
- Full-screen player only (owner ruling 3). Never over the mini player, the guide, Search, Sports or Settings.
- The Sports tab with three days of games, the quick-bar item, and the advanced settings rows.

**Out:**
- Other leagues; several games at once.
- Notifications outside FULL; sound.
- Shots and power play (the data exists, facts §2).
- Standings; favourite teams.
- Any server of ours; the ESPN fallback; a remote kill switch (lead ruling, §10).

### 1.1 The league seam (`:core`, `app.thebox.core.sports`)

```kotlin
enum class League { NHL }
data class GameId(val league: League, val id: String)               // NHL "2026020251"
enum class GamePhase { SCHEDULED, PRE, LIVE, INTERMISSION, FINAL, POSTPONED }
enum class PeriodKind { REG, OT, SO }
data class TeamLine(val abbrev: String, val name: String, val score: Int?, val logo: String?)
data class GameClock(val secondsRemaining: Int, val running: Boolean, val inIntermission: Boolean)
data class GameSnapshot(
    val id: GameId, val dayKey: String, val startUtcMs: Long, val phase: GamePhase,
    val away: TeamLine, val home: TeamLine,
    val period: Int?, val periodKind: PeriodKind?, val otNumber: Int?,      // 2 → "2OT"
    val clock: GameClock?, val finalKind: PeriodKind?,
)
data class DayBoard(val dayKey: String, val prevDay: String?, val nextDay: String?, val games: List<GameSnapshot>)

/** One HTTP answer. [serverDateMs]/[ageS] come from `Date`/`Age`; [receivedMono] is stamped by the source. */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T, val receivedMono: Long, val serverDateMs: Long?, val ageS: Int) : Fetch<T>
    data object NotFound : Fetch<Nothing>                                  // 404 with a JSON or empty body
    data class Failed(val kind: FailKind, val retryAfterS: Int?, val receivedMono: Long) : Fetch<Nothing>
}
enum class FailKind { TIMEOUT, IO, HTTP_429, HTTP_5XX, HTTP_OTHER, BAD_BODY }

interface ScoreSource {
    val league: League
    suspend fun today(): Fetch<DayBoard>                       // NHL: /v1/score/now (follows the 307)
    suspend fun day(dayKey: String): Fetch<DayBoard>
    suspend fun game(id: GameId): Fetch<GameSnapshot>
    fun logoUrl(team: TeamLine): String                        // dark-background variant, validated (A-N2)
}
```

`NhlMapper` (JSON to snapshot) is pure, lives in `:core`, and is tested with fixtures. A second league needs one
source, one mapper and one `League` value. There is no league switch in the UI until that league exists.

---

## 2. Data

### 2.1 Endpoints (facts §1, §2)

| Use | Request | Body |
|---|---|---|
| "Today" (first load per open of the tab/picker) | `GET /v1/score/now`: OkHttp follows the 307; `currentDate` in the body is authoritative (A-N10) | 6.6 KB gzip |
| Another day, or day re-polls | `GET /v1/score/{yyyy-MM-dd}` (from `prevDate`/`nextDate`/`currentDate`) | 6.6 KB gzip |
| The followed game | `GET /v1/gamecenter/{id}/landing` | 2 KB gzip |
| Logo | `GET https://assets.nhle.com/logos/nhl/svg/{ABBR}_dark.svg` | 1–5 KB |

**Mapping:**
- Live is `LIVE`/`CRIT`. `INTERMISSION` is live with `clock.inIntermission`. Final is `OFF`/`FINAL`, and `finalKind`
  comes from `gameOutcome.lastPeriodType`.
- `gameScheduleState != "OK"` → `POSTPONED`. Anything unknown → `SCHEDULED`.
- `score` is absent before the game. The clock of a final game is stale and is never shown.
- Read `default` for every localised field, ignore unknown keys, and make every field nullable.
- Names: the landing uses `placeName + commonName`; the day list uses `name.default` (the common name, U-N8).
- Games are grouped by `gameDate`; start times are shown in the device zone, 12 h or 24 h per the clock setting.

**Body cap:** `MAX_BODY_BYTES` (512 KB), enforced on `body.source()` with `request(limit + 1)` before decoding
(A-N5). A larger body is `BAD_BODY`. Bodies are decoded whole as a `JsonObject`. The streaming-parser rule covers
unbounded playlists and EPGs; these bodies are bounded.

**Logo URL** (A-N2): the landing's `darkLogo` is used only when it starts with `SportsEndpoints.logoBase`. Otherwise
the URL is derived from the abbreviation.

### 2.2 HTTP client

- Built from `@ApiClient` `newBuilder()` with `callTimeout` 10 s. It inherits `BoxDns`, the default UA, redirects and
  gzip.
- **No ETag, no `If-None-Match`, no 304 path** (A-S1). The ETag is a 20 s time-bucket id shared by every URL, so it
  says nothing a phase-aligned poll does not already know.
- Read `Date` (→ `serverDateMs`) and `Age`, clamped to [0, 60] (A-S12).
- Base URLs live in one `SportsEndpoints` object. A debug build can override them; the override is read only behind
  `BuildConfig.DEBUG` in `:data` (A-N3).
- **Logging** (A-N1, A-N12):
  - Path only; never a body.
  - One line per state change (ok → failing → ok), with the `FailKind`, `content-type` and length.
- Nothing about the user, playlist or device leaves the box. A request carries only the IP, the UA, and a game id or
  date.

### 2.3 Demand and cadence

**Demand.** The actor computes it from what the UI and the platform report:

| Demand | When |
|---|---|
| `NONE` | process not STARTED; **device not awake** (`DeviceAwake`: display off, `!isInteractive`, CEC/HDMI off, A-S5); screen-off layer; Sports off; nothing followed and no Sports UI open |
| `UNSEEN` | awake and foreground, a game followed, not in FULL |
| `SEEN` | `ScreenMode.FULL` with a followed game, **whether or not placement hides the bug** (A-S6), or the Sports tab or score picker open |

**Followed game, per phase** (`SEEN`; `UNSEEN` uses `max(cadence, POLL_UNSEEN_MS)`, except the two "own rule" rows):

| Phase | Next poll | Clock |
|---|---|---|
| `SCHEDULED`, `PRE` | one poll on Follow; then sleep until `start − PRE_WAKE_MS` (2 min); then one poll per 40 s (bucket end + `POLL_PHASE_MS`, every other bucket) until `LIVE`. Its only job is to detect the switch to live. `UNSEEN`: `max(40 s, 120 s)` after the wake | — (nothing drawn) |
| `LIVE`, running | next bucket end + `POLL_PHASE_MS` | ticks, monotonic (§2.5) |
| `LIVE`, stopped | same | frozen |
| `INTERMISSION` | next bucket end + `POLL_PHASE_MS`, skipping one bucket (40 s) | counts down, not capped, floored at 0 |
| `FINAL` | none; own rule in `UNSEEN` | not shown |
| `POSTPONED` | none; removal event | — |

**Bucket alignment** (A-S2):
- `bucketEndServer = serverDateMs − ageS·1000 + CDN_BUCKET_MS` (20 000).
- `nextPollMono = receivedMono + (bucketEndServer − serverDateMs) + POLL_PHASE_MS`.
- `POLL_PHASE_MS` is 1 000–2 000 ms, drawn once per install.
- If `Date` is missing, fall back to `receivedMono + 20 000`.
- Server time only; never the device clock.

**Rise in demand** (A-S6): when demand rises to `SEEN` and the last success is older than the live cadence, poll at
once. "No update" is suppressed until that poll completes.

**Day list** (Sports tab or picker open): the pure `DayPollPolicy.nextAt(board, demand, lastOkMono, failures)` (A-S7)
decides.
- 30 s, bucket-aligned, while any game that day is live or pre-game.
- 5 min otherwise.
- Once for an all-final day.
- Nothing once the tab and picker are closed.

Freshness: about 20 s of CDN bucket plus about 2 s of phase. That is about 10 s fresher on average than a free-running
timer.

### 2.4 Failures, offline, stale

- **Back-off** after `Failed`: `20 s × 2^n`, capped at 300 s, ±20 % jitter. `Retry-After` wins. An `Ok` resets it.
- **NotFound** (A-S12): the follow is removed only after **two** `NotFound`s at least 20 s apart, each with a JSON or
  empty body. A 404 with an HTML body is `Failed(HTTP_OTHER)`.
- **Online** means a default network exists (`ConnectivityManager` default-network callback, as in `SmbSessionKeeper`).
  `VALIDATED` is not required (A-S12). With no default network, nothing is sent; requests resume when one appears.
- **Stale:** no `Ok` for `max(STALE_AFTER_MS, 2 × current cadence)` (A-S6) while live or in intermission.
  - The bug freezes the clock.
  - The **whole centre column dims** to `.55` (U-S11), and the bottom line reads **No update**.
- **Hide:** after `HIDE_AFTER_MS` (5 min) stale, the bug hides. The follow is kept, and the bug returns on the next
  `Ok`.

### 2.5 Clock, delay, queue

- **Queue:** each `Ok` snapshot enters with `anchorMono = receivedMono − ageS·1000`. The queue keeps 130 s of history.
  It **never prunes the newest snapshot that qualifies** under the current delay (A-N6).
- **Delay:** `delaySec` ∈ {0 (shown as "Off"), 5, 10, 15, 20, 30, 45, 60, 90, 120}.
  - `view(now)` takes the newest snapshot with `anchorMono ≤ now − delay`.
  - Before one qualifies, the bug shows the oldest snapshot, dimmed, with the bottom line **Delayed**.
  - Changing the delay takes effect at once.
- **Value:** `secs = snap.secondsRemaining − (now − delay − anchorMono)/1000`, while `running || inIntermission`;
  floored at 0.
- **Cap** (A-S3): in play, extrapolation stops when `now − lastReceivedMono > EXTRAPOLATE_CAP_S` (40 s), measured
  from the last response, not from the anchor. Intermission is not capped.
- **Monotonic** (A-S4): while phase, period and the running state are unchanged, the shown value never goes up. A
  higher resync value is held until the extrapolation reaches it, for at most `CLOCK_HOLD_MAX_S` (15 s), then it
  snaps. A period change, phase change, or stop/start snaps at once.
- **Time base** (A-N7): one time base, `MonoClock` = `SystemClock.elapsedRealtime`. The UI's per-second tick uses
  the same clock, never frame time or wall time.
- **Goal:** `goalSeq` increases once for each score rise on the delayed timeline, and only while demand is `SEEN`. A
  score that dropped (a goal taken back) updates silently.

### 2.6 The followed game (persistence)

```kotlin
@Serializable data class FollowedGame(
    val league: League, val gameId: String, val startUtcMs: Long,
    val away: String, val home: String, val finalSeenUtcMs: Long? = null,
)
@Serializable data class SportsFollowDoc(val game: FollowedGame? = null)   // non-null domain default (A-S9)
```

- **Domain:** `sports_follow` is a `SettingsDomain(..., backedUp = false)`, and is also in `NOT_BACKED_UP_DOMAINS`
  (A-S9). Clearing means `update { SportsFollowDoc() }`.
- **Restart:** a follow survives process death and reboot. The first poll waits for demand.
- `startUtcMs` is rewritten when a snapshot shows a new start (rare).
- **Expiry**, evaluated at every wake:
  - `finalSeen + 5 min` on the delayed timeline;
  - `start + 8 h`;
  - `POSTPONED`.
- **Wall-clock times** use `wall + skew`, where skew = server `Date` − device time from the last `Ok` (A-N4).
- **Events** (`ScoreRepository.events`):
  - `Removed(NOT_LISTED | POSTPONED | SPORTS_OFF)` → toast.
  - `FollowedFinal` → toast.
  - Expiry after the final is silent (owner: "it clears itself").

### 2.7 Where state lives, threading, wake-ups

```
:core  app.thebox.core.sports  contracts (§1.1, §2.7), SportsPrefs, FollowedGame, SportsConstants,
                               ScoreBugMachine, DayPollPolicy, NhlMapper, ScoreBugPlacement, DeviceAwake
:data  app.thebox.data.sports  NhlScoreSource, ScoreRepositoryImpl (@Singleton, actor on @ApplicationScope), SportsModule
:ui    app.thebox.ui.sports    ScoreBug, TeamLogo, LogoTrim, SportsScreen, SportsViewModel, GameCard
```

```kotlin
class ScoreBugMachine(mono: MonoClock, wall: () -> Long) {          // single-threaded (A-S8)
    fun follow(game: FollowedGame?); fun prefs(delaySec: Int); fun demand(d: Demand)
    fun fetched(f: Fetch<GameSnapshot>)
    fun step()                                         // advance to mono.nowMs(): expiry, stale, queue (A-B1)
    fun pollDue(): Boolean
    fun nextWakeAtMono(): Long?                        // min(next poll, next snapshot due, stale, hide,
                                                       //     cap instant, expiry, hold end); null = idle (A-B1)
    fun view(): ScoreBugView?; fun anchor(): ClockAnchor?
    fun drainEvents(): List<ScoreEvent>
}
data class ClockAnchor(val secs: Int, val atMono: Long, val ticking: Boolean, val stopAtMono: Long?)
data class ScoreBugView(
    val away: TeamLine, val home: TeamLine, val top: CentreText, val bottom: CentreText?,
    val dim: Boolean, val goalSeq: Int, val goalSide: Side?,                 // view() is null before LIVE
)
sealed interface ScoreEvent { data class Removed(val why: RemoveReason, val game: FollowedGame) : ScoreEvent
                              data class FollowedFinal(val game: FollowedGame) : ScoreEvent }
interface ScoreRepository : ScoreBugSource {
    val day: StateFlow<DayState>          // Loading | Board(board, fetchedAtMono, failing) | Failed(kind) (A-N11)
    val followed: StateFlow<FollowedGame?>
    val events: Flow<ScoreEvent>
    fun follow(game: GameSnapshot?); fun showDay(dayKey: String?); fun retry()
}
interface ScoreBugSource {                // what FullScreenHost's ScoreBug reads (A-B2)
    val bug: StateFlow<ScoreBugView?>     // re-emitted only when a field other than the clock changes
    val clock: StateFlow<ClockAnchor?>    // changes per poll; read only in the draw lambda (A-S10)
    fun reportDemand(owner: DemandOwner, on: Boolean)   // FULL_WITH_FOLLOW, SPORTS_TAB, SCORE_PICKER
}
```

The repository loop is a single actor, so every input is serialised (A-S8):

```
for (;;) {
    select { inputs.onReceive { apply(it) }; onTimeout(machine.nextWakeAtMono() ?: ∞) { } }
    machine.step()
    if (machine.pollDue()) machine.fetched(source.game(id))
    publish(view, anchor, events)
}
```

Inputs are: follow, demand, prefs, network, awake, lifecycle and day requests. Tests run under `runTest` with a
`MonoClock` backed by `testScheduler.currentTime`, so `delay` and the clock share one time base (A-S8).

---

## 3. Logos

- **Dependency:** `io.coil-kt.coil3:coil-svg:3.6.3` in **`:app` only**, where `ImageModule` is (A-N8).
  `ImageModule` adds `SvgDecoder.Factory()` explicitly. ServiceLoader also registers one; the duplicate is harmless,
  and the explicit entry keeps the wiring visible. Measure the size on the R8 release universal APK and confirm
  `androidsvg` needs no keep rules (§11).
- **URL:** `{ABBR}_dark.svg`, derived from the abbreviation, or the validated `darkLogo` (§2.1).
- **Rasterisation:**
  - Request at 4× the slot size, with `memoryCachePolicy(DISABLED)`.
  - Then `LogoTrim`, a Coil `Transformation`, crops the bitmap to its alpha bounds. The art fills only about 70 % of
    the 960 × 640 viewBox (U-S1).
  - Then it scales to fit the 64 × 46 slot.
- **Lifetime** (A-S11, the `LogoCache` precedent): the two trimmed `ImageBitmap`s are held in a `FollowLogos` holder
  for the life of the follow. They are released when the follow changes or expires. Coil's disk cache keeps the SVG
  source. Whether Coil 3 honours `Cache-Control` without `coil-network-cache-control` is unverified (§11), and
  nothing here relies on it.
- **Fallback:** if loading fails, or no logo has arrived after 1.5 s, draw a 44 px disc (`White08` fill, 1 px
  `Border12`) with the abbreviation in Instrument Sans 18/700 `Fg`. There is no cross-fade.
- **Accessibility** (U-N12): each logo's `contentDescription` is the team name.
- **Not bundled:** nothing goes in the APK or repo (trademark). The NHL.com credit is in the Sports tab (§5.2).

---

## 4. The score bug overlay

### 4.1 Geometry (Standard; design px; U-S1)

Mock-up pill 1536 × 267 → 5.75 : 1. Logo art is about 67 % of the pill height; score cap about 39 %; clock cap 26 %.

```
| 16 | logo 64×46 | 8 | score 48 | 12 | ┃1 | centre 106 | ┃1 | 12 | score 48 | 8 | logo 64×46 | 16 | = 404 × 70
```

| Item | Value |
|---|---|
| Pill | 404 × 70, radius 35 (5.77 : 1) |
| Logo | trimmed art fitted in 64 × 46, centred |
| Score | Instrument Sans **40/700**, tabular, `Fg`, centred in a fixed 48 slot (two digits) |
| Divider | 1 × 34, white `.18` |
| Centre, two lines | clock **26/600** tabular `Fg` (box 30) over period **20/600** `Fg .70` (box 22; U-B3); 9 above, 9 below |
| Centre, one line | **24/600** `Fg`, vertically centred: "Final", "Final/OT", "Final/SO", "7:00 PM", "SO" |
| Fit (U-N3) | every centre string is measured at both sizes; anything over 106 steps down by 2 px, never below 18; no ellipsis |
| Large | ×1.25 → 506 × 88 (period 25) |

There is no Small size, because the period text would fall under the 18 px floor.

### 4.2 Fake glass (no real-time blur; U-B3)

All of this is in one `drawWithCache` node. The path, brushes and shadow are built once per size.

1. **Shadow:** a bitmap baked **once** per size: the pill shape plus a 24 px margin, run through a 24 px
   `BlurMaskFilter` on a software canvas. Drawn at (0, 8), `.45` black. Skipped in low-end mode.
2. **Outer ring:** 1 px `Black .30`, just outside the pill, so the edge holds over white ice.
3. **Fill:** `PlayerUi.PanelFill` (`#0A0D13` at **.86**).
4. **Top highlight:** white `.06` → 0 over the top half.
5. **Hairline:** 1 px inside stroke, white `.16` → `.06`.
6. **Ancestors:** no `graphicsLayer`, clip or blur on any ancestor of the surface. The bug is a sibling inside
   `FullScreenHost`. Its own layers (the fade, and the clock's layer in §4.7) are not ancestors of the surface.

Contrast over pure white (sRGB relative luminance; the pill composites to about `#2C2F34`):
- clock and score ≈ 12 : 1;
- period `.70` ≈ 6.8 : 1 (the 4.5 : 1 floor holds with margin, also under the highlight).

Bright frames are a test case (§8).

### 4.3 Corners and safe area (U-S2, U-N13)

`SCORE_INSET_X` is **96** and `SCORE_INSET_Y` is 56. The x inset is 5 % overscan, the 2026-10-20 audit value.

| Corner | Rect at Standard size |
|---|---|
| **Top right (default)** | (1420, 56) |
| Top left | (96, 56) |
| Bottom right | (1420, 954) |
| Bottom left | (96, 954) |

Why top right:
- Top left collides with the channel list, the stats overlay and the group card.
- Bottom corners collide with the bars on every zap.
- Top right is not clear of broadcasters' station logos either. The user can move the bug in one press.

### 4.4 Coexistence (pure `ScoreBugPlacement` in `:core`; A-B2)

```kotlin
data class Box(val l: Float, val t: Float, val r: Float, val b: Float)
data class OverlayGeometry(          // built in :ui by PlayerUi.overlayGeometry(); :core never imports PlayerUi
    val channelList: Box, val stats: Box, val groupCard: Box, val picker: Box, val numberEntry: Box,
    val infoBarTop: Float, val quickBarTop: Float,
)
data class PlacementInput(
    val corner: ScoreCorner, val size: ScoreBugSize, val placing: Boolean,
    val bar: BottomBar,              // NONE | PASSIVE_INFO | USER_BAR (active info, scrub) | QUICK
    val channelList: Boolean, val stats: Boolean, val groupCard: Boolean, val picker: Boolean,
    val numberEntry: Boolean, val probing: Box?, val toast: Boolean, val reminder: Boolean,
    val modal: Boolean,              // exit dialog, PIN pad
    val textTrackOn: Boolean, val screenOff: Boolean,
)
object ScoreBugPlacement { fun place(input: PlacementInput, geo: OverlayGeometry): Box? }  // null = hidden
```

| Other overlay | Effect on the bug |
|---|---|
| Passive info bar (after a zap; `InfoBarState.active == false`) | bottom corners **hide** (fade), with no movement (U-S3) |
| Active info bar, scrub bar | bottom corners **slide** so the bottom edge is at `BAR_T − 16` = 804: 150 ms `FastOutSlowIn`, snapped in low-end mode |
| Quick bar | bottom corners slide to `QUICK_T − 16` = 936 |
| Text track selected | a bottom corner shows at the top corner on the same side (the settings row says so, U-N4) |
| Channel list, stats, group card | hide the left corners they intersect |
| Picker sheet (x ≥ 1376), number entry | hide the right corners they intersect |
| Probing line | hides only on actual rect intersection (U-N1) |
| Toast / reminder card | hide bottom left / bottom right |
| Exit dialog, PIN pad | hide |
| Error card, paused dim | no effect |
| Screen-off layer | not drawn; demand `NONE` |
| **Place mode** (`placing`) | only `modal` and `screenOff` apply; nothing else hides the bug (U-B1) |

Draw order in `FullScreenHost`: after the bars and stats; before the channel list, the picker, the paused card, the
error card, the transient lines, the guide overlays and screen-off. Only in `ScreenMode.FULL`.

**Host facts** (A-B2):
- `FullScreenHost` gains a parameter `hostFacts: ScoreBugHostFacts(toast, reminder, modal)`, which `AppRoot` computes
  from what it already holds.
- `PlayerExtras` gains:
  - `textTrackOn: StateFlow<Boolean>`;
  - `scoreBug: ScoreBugSource?`.
  Both have defaults; `PlayerViewModel` implements them.

### 4.5 States (centre column)

| Phase | Top | Bottom | Scores |
|---|---|---|---|
| `SCHEDULED`, `PRE` | **nothing drawn** (owner ruling 2026-11-05); `view()` returns null. The bug first appears when the game goes `LIVE` | | |
| `LIVE` REG | `10:22` | `1st` / `2nd` / `3rd` | shown |
| `LIVE` OT | `3:41` | `OT` (`2OT`, `3OT`…) | shown |
| `LIVE` SO | `SO` (one line) | — | shown |
| `INTERMISSION` | `15:58` | **`1st INT`** (U-N2) | shown |
| Clock 0:00, not yet intermission | `0:00` | `End 1st` | shown |
| Goal (U-S10) | clock as usual | **`GOAL CGY`** for 8 s on the delayed timeline | shown |
| `FINAL` | `Final` / `Final/OT` / `Final/SO` (one line) | — | shown until expiry |
| Stale | frozen clock, **dimmed** | `No update` | shown (column `.55`, U-S11) |
| Delayed, waiting | frozen clock, dimmed | `Delayed` | shown |

- Clock format: `m:ss` below 10 minutes, `mm:ss` from 10:00.
- `:core` emits `CentreText` enums; the strings are in `:ui`.
- Semantics (U-N12): one merged string per state, for example "Calgary 0, Edmonton 2, 10:22 left in the 1st".

### 4.6 Goal (U-S10)

The trigger is `goalSeq` (§2.5).

- **Box:** a 48 × 46 box behind the changed score, radius 12, accent `.35`, with the digit staying `Fg`. 150 ms in,
  2 400 ms hold, 450 ms out (3 s in total).
- **Text:** the bottom line reads `GOAL {ABBR}` for 8 s.
- **Cost:** draw-phase only (one `Animatable` read in draw).
- **Low-end mode:** no box, but the "GOAL" text stays.
- No sound and no motion.

### 4.7 Draw-cost budget (A-S10)

- **Collection:** `bug` and `clock` are collected **inside `ScoreBug`**, never in `FullScreenHost`.
  - `bug` re-emits only on a text change.
  - `clock` is held as `State` and read only in the draw lambda.
- **Tick:** the per-second tick is a `produceState` aligned to the anchor's second boundary.
  - It runs only while `ticking` and before `stopAtMono`.
  - It is read in a **separate** `drawBehind` on the clock text node. That node has **its own `graphicsLayer()`**
    (not an ancestor of the surface), so a tick re-records only that layer, in low-end mode too.
  - The glass `drawWithCache` never reads the tick.
- **Digits:** the clock uses a pre-measured digit table (the `TimeChars` precedent), so a tick allocates nothing.
- **Budget:**
  - ≤ 0.3 ms draw per tick on the API 30 emulator.
  - Zero `FullScreenHost` and `ScoreBug` recompositions per tick or per poll.
  - Plan §6 and `FullIdleFramesTest` gain a case: "idle FULL, ticking bug: ≤ 1.1 frames/s". The case without the bug
    stays at ≤ 1 frame in 10 s.

---

## 5. Sports tab

### 5.1 Place and keys (U-S7)

- **Top bar:** Guide · Search · **Sports** · ⚙ (`GuideTab.SPORTS`, `TAB_COUNT` 4). The header-fit test is re-run at
  1024 px with the longest sync-chip text (U-N10).
- **Mode:** OK → `ScreenMode.SPORTS`, `HoleMode.COVERED` (as in Search).
- **Key routing:** Compose focus. The router passes every key through except BACK.
- **Initial focus:** the followed game; else the first live card; else the Today chip.
- **Up on the day chips:** nothing (the top bar is not drawn).
- **Back:** from anywhere in Sports, **one Back returns to the guide grid** with the Guide tab current. There is no
  text field, so no Search-style ladder.
- **Settings:** Back from the settings section opened here returns to Sports with the chip focused.

### 5.2 Layout (design px)

| Region | Geometry |
|---|---|
| Title row | (96, 56): "Sports" 34/600, an "NHL" chip (`White08`, r 8, 18/600). Right-aligned to 1824: **Corner · Top right** and **Delay · Off** chips (U-N6), 32 apart from the title |
| Credit | under the title, 18 px `.55`: "Scores and logos: NHL.com" (U-S9) |
| Day chips | (96, 156), height 52, gap 12: **Yesterday · Today · Tomorrow** (22/500) |
| Games grid | top 232, viewport bottom = footer top − 16; 3 columns × 564 (96 → 1824, gap 18); card height 128, row gap 24. The focused row stays 64 inside the viewport while it scrolls. A `LazyVerticalGrid` (≤ 16 items) |
| Footer | `FooterHints` at 22 (U-S9): `← → ↑ ↓ Browse · OK Follow score` / `OK Stop following` (from the focused card) `· Back Guide` |

**Card** (U-S6, U-N7, U-N8):
- **Teams:** two rows, away over home. Each row has a logo on a fixed `#0A0D13` 64 × 44 radius-8 plate, the team
  name (common name, 26/600), and the score at 34/700 tabular.
- **State column:** right-aligned, 22/500 `.75`.
  - Live games show the red dot plus **LIVE**, then `2nd · 10:22`. The clock ticks from the board snapshot's anchor,
    with the same rules as the bug.
  - Other games show `1st INT`, `Final/OT` or `7:00 PM`.
- **Focused:** `#EEF1F6` fill, 3 px accent ring, and a right-aligned action chip reading **OK Follow score** or **OK
  Stop following**.
- **Followed:** a **✓ Following** chip. Unfocused: accentText `#B6D4F7` on `White08`. Focused: `#0A0D13` on accent
  `.22`. Never the red dot.
- **Order:** live, then pre-game, then scheduled by start time, then final.

The Corner and Delay chips each open a small choice list (the context-menu pattern). They write the same prefs as the
quick bar.

### 5.3 Keys

| Key | Effect |
|---|---|
| D-pad | moves focus between cards. Up from the first row → day chips. Up from the chips → title chips. Down reverses |
| OK on a card | Follow / Stop following. Following another game replaces the current one |
| OK on a day chip | loads that day |
| OK on Corner / Delay | choice list |
| Back | guide grid |

Toasts:
- First Follow: "Following CGY – EDM. The score shows in a corner while you watch any channel. Change it from the
  player's quick bar." Later follows: "Following CGY – EDM."
- Following a final game: "That game is over. Its score shows for 5 minutes."

### 5.4 Empty and error states

| State | Text |
|---|---|
| Loading | five static skeleton cards (`White05`) |
| No games | "No NHL games {yesterday / today / tomorrow}. Pick another day above." (U-N5) |
| No network | "No internet connection. Check the network, then press OK to try again." + Retry |
| Failure | "NHL.com did not answer. Press OK to try again; scores may be down for a few minutes." + Retry |
| Failing with a board shown | the board stays; under the title: "Updated 2 min ago" |

---

## 6. Quick bar item "Score bug"

- **Position** (U-S5): `QuickBarModel.items` (the explicit order list, not the enum order) adds `SCORE_BUG`.
  - **Right after Subtitles** (or after Picture shape when there are no subtitles) while a game is followed.
  - **Just before Settings** otherwise.
  - Shown on every channel while Show Sports is on.
- **Value line:** `Follow a game` / `CGY 0–2 EDM` / `Starts 7:00 PM`.
- **OK** → picker sheet `PickerKind.SCORE_BUG` at the existing picker geometry. While it is open, demand includes
  `SCORE_PICKER`.
- **Rows** (U-S4, U-N9):
  1. The followed game (✓, its live line), when there is one.
  2. **Corner · Top right** → place mode (below).
  3. **Delay · Off** → a nested choice list of §2.5 values with a check on the current one. Back returns to this
     sheet.
  4. **Stop following**, while a game is followed.
  5. A non-selectable **Today** divider, then the other games: "CGY 0–2 EDM · 2nd 10:22" or "CGY – EDM · 7:00 PM".
     Order: live, upcoming, final.
  6. The non-selectable row "Loading games…", or the §5.4 failure row, where OK retries.
- **Initial focus:** the followed game; else the first live game.
- **OK on a game:** Follow (replacing any current follow), then close to bare video.
- **Stop following:** clears the follow and closes.
- **Keys:** Back and Left return to the quick bar (`fromQuickBar`).

**Place mode** (U-B1). It is a `PlayerLayer.PLACE_SCORE` (append-only) with `PlayerUiState.placeScore: ScorePlaceState`.
- **Entry:** OK on Corner closes the sheet and shows the bug at its corner.
  - The bug gets a 3 px accent ring.
  - A hint pill sits beside the bug, toward the screen centre: "← → ↑ ↓ Move · OK Done", 22 px on
    `PlayerUi.PanelFill`.
- **Arrows:** Left/Right swap the side; Up/Down swap top and bottom. Each move is immediate (no animation in low-end
  mode, else the 150 ms slide).
- **Exit:** OK or Back saves the corner (`PlayerEffects.setScoreCorner`) and returns to the quick bar.
- **Placement:** hide rules are suspended (§4.4).
- **Not following:** with no followed game, the row reads "Corner · Top right" and place mode draws a sample bug
  ("AWY 0 | 0:00 | HME 0" with fallback discs).

**Player machine port** (A-B2):

```kotlin
// PlayerStateMachine (append-only)
fun scores(facts: ScoreFacts)   // followed: ScoreBugView? + FollowedGame?, day: DayState, corner, delaySec, enabled
// PlayerEffects (default no-op bodies, so fakes keep compiling)
fun followGame(id: GameId?) {}; fun setScoreCorner(c: ScoreCorner) {}; fun setScoreDelay(sec: Int) {}
fun scorePicker(open: Boolean) {}; fun retryScores() {}
```

`PlayerViewModel` collects the repository and calls `scores(...)`. The picker rows update when `day` changes.

**Reachability:** `player_keys.csv` and `PlayerFiveKeyReachabilityTest` gain the item, the sheet, the delay list and
place mode.

---

## 7. Settings (lead ruling on U-B2: all ADVANCED)

New section **Sports** after Player (`SectionId.SPORTS`, `SportsRows.kt`). **Every row is `Visibility.ADVANCED`**, so
the simple count stays at 35 and `SimpleRowCountTest` is untouched. In simple mode the section is absent, as for
NETWORK. Day-to-day corner and delay live in the quick bar (§6) and the Sports tab chips (§5.2).

| Row | Values | Default |
|---|---|---|
| Show Sports (U-N11) | On / Off. Off hides the tab and the quick-bar item, stops all requests, and ends a follow with the toast "Sports is off. The score is hidden." (U-S12) | On |
| Score bug corner | Top right · Top left · Bottom right · Bottom left; detail: "A bottom corner moves to the top while subtitles are on." | Top right |
| Score bug delay | Off · 5 s … 120 s; detail: "Use this if the game on your screen is behind the live score." | Off |
| Score bug size | Standard · Large | Standard |

```kotlin
@Serializable data class SportsPrefs(
    val enabled: Boolean = true, val corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
    val delaySec: Int = 0, val size: ScoreBugSize = ScoreBugSize.STANDARD,
)
// Domains.SPORTS = "sports": backed up, and added to RoomSettingsStore.KNOWN_DOMAINS (A-S9).
// Domains.SPORTS_FOLLOW = "sports_follow": backedUp = false, and in NOT_BACKED_UP_DOMAINS.
```

The prefs are app-wide, never per playlist. The app has no profiles yet.

---

## 8. Testing

**`:core`** (JVM):
- **`NhlMapperTest`** runs on recorded fixtures in `core/src/test/resources/sports/nhl/`:
  - `score-2026-11-05.json`, `landing-live-int.json`, `score-2026-11-04.json`;
  - `*-synthetic.json` variants: running clock, CRIT, SO, 2OT, FINAL, PPD, missing fields, unknown keys, an HTML body.
- **`ScoreBugMachineTest`**, with a fake `MonoClock` and wall clock:
  - The cadence table, with bucket alignment from `Date`/`Age`.
  - `UNSEEN = max(cadence, 120 s)`, with the FINAL own rule.
  - Pre-game: one poll on Follow, nothing until `start − 2 min`, then 40 s until `LIVE`; `view()` is null in
    `SCHEDULED`/`PRE` at every instant, and the first `LIVE` snapshot (on the delayed timeline) makes it non-null.
  - An immediate poll on a rise to `SEEN`; "No update" suppressed until that poll.
  - Demand `NONE` (asleep, stopped) → `nextWakeAtMono() == null` and no `pollDue`.
- **Wake tests with no input events** (A-B1). The test only advances the clock to `nextWakeAtMono()` and calls
  `step()`:
  - FINAL clears exactly 5 min after the delayed final.
  - With a 30 s delay, the score changes at `anchor + 30 s`, and the delayed FINAL lands on time after polling has
    stopped.
  - Stale fires at its threshold and hide at 5 min.
  - The 8 h hard expiry fires.
  - The 15 s clock hold ends on time.
- **Clock tests:**
  - Age 19, a poll every 20 s and a 5 s RTT never freeze a running clock (A-S3).
  - A higher resync is held, then snapped after 15 s; a period change snaps at once (A-S4).
  - Intermission counts down without a cap.
- **Queue and follow tests:**
  - `goalSeq`: once per rise on the delayed timeline; not on a drop; not while `UNSEEN`.
  - The newest qualifying snapshot is never pruned (A-N6).
  - Two-404 removal (A-S12); POSTPONED; the back-off sequence and `Retry-After`; skew from `Date` (A-N4).
- **`DayPollPolicyTest`**: the table in §2.3 (A-S7).
- **`ScoreBugPlacementTest`**:
  - Every row of §4.4 × four corners × two sizes, with a fixture `OverlayGeometry`.
  - No returned box intersects an active overlay box, and every box lies inside 96/56.
  - Place mode ignores the hide rules.

**`:data`** (`mockwebserver3`, `runTest`):
- `/score/now` redirect handling; dated URLs; no `If-None-Match` sent.
- `Date`/`Age` → a predicted next poll instant (A-S2).
- The 512 KB cap enforced on the source (A-N5).
- **Zero requests** with no follow and no demand; with the display off while the process is STARTED (A-S5); offline.
- Expiry at final + 5 min with polling stopped and no other input (A-B1).
- Events emitted.
- The `RoomSettingsStore` restore keeps a `sports` document before the domain registers (A-S9).

**`:ui`** (Robolectric, 1920 × 1080):
- **`ScoreBugBoundsTest`**:
  - Four corners × {bare, passive bar, active bar, quick bar, subtitles, channel list, picker, toast} → expected box
    or absent.
  - Large size; place mode with its ring and hint.
  - `PlayerUi.overlayGeometry()` equals the `:core` fixture (A-B2).
- **`ScoreBugDrawTest`**:
  - 60 s with three polls → 0 recompositions of `FullScreenHost` and `ScoreBug`, and 60 clock-layer draws.
  - The low-end variant.
  - A stopped clock → 0 draws.
- **Bright-background screenshots** (U-B3): a white frame and a 50 % grey frame under the pill. The period line's
  measured contrast is at least 4.5 : 1.
- **Centre-string fit** at both sizes (U-N3).
- **Sports screen:** initial focus, the chip and grid paths, a single Back, the empty and error texts, and the
  following chip's colours.
- **Header fit** at 1024 px with four tabs (U-N10).

**Fake panel:** `tools/fakepanel --sports` serves:
- `/v1/score/now` (307), `/v1/score/{date}`, `/v1/gamecenter/{id}/landing`;
- a neutral placeholder SVG of ours at the logo path;
- a compressed timeline (FUT → PRE → LIVE → INT → LIVE → OT → SO → FINAL in about 6 min) with real `Date`/`Age`
  bucket behaviour.

**Device:** one real game, covering the §11 checks.

---

## 9. Work packages (A-B2: disjoint by file)

### Commit 0: contracts, wiring, stubs, fakes, deps (lead, Opus)

- **`:core`** (`core/.../sports/`):
  - `SportsContracts.kt`: every type in §1.1, §2.7, §4.4 and §6, plus `CentreText`, `Demand`, `DemandOwner`,
    `ScoreEvent` and `DayState` (with `fetchedAtMono` and `failing`).
  - `SportsPrefs.kt` and `SportsConstants.kt` (Appendix A).
  - `DeviceAwake.kt` (`StateFlow<Boolean>`).
  - Stubs with `TODO()` bodies: `ScoreBugMachine`, `DayPollPolicy`, `NhlMapper`, `ScoreBugPlacement`.
- **Settings plumbing:** `Domains.SPORTS` and `SPORTS_FOLLOW`; both `SettingsDomain`s, the follow one with
  `backedUp = false`; `NOT_BACKED_UP_DOMAINS`; `RoomSettingsStore.KNOWN_DOMAINS`.
- **Append-only enums and contracts:** `ScreenMode.SPORTS`, `GuideTab.SPORTS`, `QuickItem.SCORE_BUG`,
  `PickerKind.SCORE_BUG`, `PickerChoice` score rows, `PlayerLayer.PLACE_SCORE`, `PlayerUiState.placeScore`,
  `SectionId.SPORTS`, `PlayerStateMachine.scores`, `PlayerEffects` score effects (no-op defaults). Every exhaustive
  `when` gets a placeholder branch.
- **Wiring:**
  - `PlayerExtras.textTrackOn` / `scoreBug`, implemented in `PlayerViewModel`. It collects the repository into
    `scores(...)`, maps `events` to the existing toast path, and maps effects to the repository and the store.
  - `FullScreenHost`: the `hostFacts` parameter and the `ScoreBug(...)` call at its z-position.
  - `AppRoot`: passes `ScoreBugHostFacts`, plus a placeholder `SPORTS` branch and `holeModeFor`.
  - `DeviceAwake` binding fed by `LifecycleCoordinator`'s display/CEC facts.
  - `PlayerUi.SCORE_*` (aliases of `SportsConstants`) and `PlayerUi.overlayGeometry()`.
- **Dependencies and fixtures:**
  - `coil-svg` in the catalog and `:app`; `SvgDecoder.Factory()` in `ImageModule`.
  - `ui/src/main/res/values/strings_sports.xml`, with **every** string of this design.
  - `FakeScoreRepository`; the fixtures.
- **Gate:** the smoke build is green.

### WP-1: pure core (Opus)
- `ScoreBugMachine.kt`, `DayPollPolicy.kt`, `NhlMapper.kt`, `ScoreBugPlacement.kt` and their tests.

### WP-2: data and fake panel (Opus)
- `data/.../sports/NhlScoreSource.kt`, `ScoreRepositoryImpl.kt`, `SportsModule.kt`, and their tests.
- `tools/fakepanel` sports routes and their test.

### WP-3: the overlay (Sonnet)
- `ui/.../sports/ScoreBug.kt` (glass, states, goal, clock layer, place-mode ring and hint, semantics), `TeamLogo.kt`,
  `LogoTrim.kt`, `FollowLogos.kt`.
- `ScoreBugBoundsTest`, `ScoreBugDrawTest`, the screenshots and the fit test.

### WP-4a: Sports tab (Sonnet)
- `ui/.../sports/SportsScreen.kt`, `SportsViewModel.kt`, `GameCard.kt`.
- `HeaderTabs.kt`; the tab and route lines in `GuideStateMachineImpl.kt`; the `SPORTS` branch body in `AppRoot.kt`;
  `KeyRouterImpl.kt`.
- The guide key-table rows, the header-fit test, the Sports screen tests.

### WP-4b: quick bar, picker, place mode, settings (Sonnet)
- `QuickBarModel.kt`; `PlayerStateMachineImpl.kt` (item, sheet, delay list, place mode, `scores` input).
- `PickerSheet.kt` (non-selectable rows, divider, nested list).
- `core/settings/tree/SportsRows.kt` and its root-list entry.
- `player_keys.csv`, `PlayerFiveKeyReachabilityTest`, the settings key and back-ladder rows.

**Disjointness check:**
- `FullScreenHost.kt` and `PlayerViewModel.kt` are commit 0 only.
- `AppRoot.kt` is touched by commit 0, then WP-4a (sequentially).
- Strings are commit 0 only.
- `ui/sports` files are split by name between WP-3 and WP-4a.
- Nothing else is shared.

Every WP ends with `./gradlew :core:test :data:test :ui:testDebugUnitTest :app:assembleDebug`. The milestone ends with
a code review and the §6 gates in §4.7.

---

## 10. Owner and lead rulings on the open questions (2026-11-05)

1. **Pre-game (owner):** nothing in the full-screen player until puck drop. No peek, no start-time pill while the
   followed game is FUT/PRE. The bug appears when the game goes live. The Sports tab still lists start times, and the
   quick-bar value line still reads "Starts 7:00 PM".
2. **Goal emphasis (owner):** as designed (§4.6): an accent box for 3 s plus "GOAL CGY" for 8 s, no sound.
3. **Remote kill switch (lead):** none. If the unofficial feed breaks, the tab says "NHL.com did not answer" until an
   app update.

No open questions remain.

---

## 11. Assumptions to verify (device, before closing the milestone)

1. `clock.running: true` in play, and a freeze on whistles.
2. The `CRIT` and `FINAL` states; `SO`; a playoff `2OT` (`number` 5); `gameScheduleState` for postponed games.
3. The ETag/`Date`/`Age` bucket model holds over a whole evening (bucket ends at :00/:20/:40).
4. All 32 `_dark` logos render through AndroidSVG on API 25 (at least SEA, PIT, VGK), and the trim crops them
   correctly.
5. The bug is legible on white ice on a real TV (U-B3).
6. The coil-svg + AndroidSVG size delta on the R8 release universal APK (expected 150–250 KB), and no keep rules
   needed.
7. Whether Coil 3.6.3's disk cache honours `Cache-Control` (not relied on).
8. Whether the "End 1st" window exists (0:00 with `inIntermission:false`).
9. `DeviceAwake` goes false on Fire OS display-off and CEC standby without `ON_STOP`.

---

## Appendix A: constants

| Name | Value |
|---|---|
| `CDN_BUCKET_MS` / `POLL_PHASE_MS` | 20 000 / 1 000–2 000 (once per install) |
| Intermission bucket skip | 1 (one poll per 40 s) |
| `PRE_WAKE_MS` / pre-game poll | 120 000 / 40 000 (every other bucket) |
| `POLL_UNSEEN_MS` | 120 000 |
| Day list: live / idle | 30 000 (aligned) / 300 000 |
| `BACKOFF_BASE_MS` / `BACKOFF_MAX_MS` / jitter | 20 000 / 300 000 / ±20 % |
| `NOT_FOUND_CONFIRM_MS` | 20 000 (two 404s) |
| `AGE_MAX_S` | 60 (clamp) |
| `EXTRAPOLATE_CAP_S` / `CLOCK_HOLD_MAX_S` | 40 (since the last response) / 15 |
| `STALE_AFTER_MS` / `HIDE_AFTER_MS` | max(60 000, 2 × cadence) / 300 000 |
| `QUEUE_KEEP_MS` | 130 000 (newest qualifying never pruned) |
| `DELAY_STEPS_S` | 0, 5, 10, 15, 20, 30, 45, 60, 90, 120 |
| `EXPIRE_AFTER_FINAL_MS` / `HARD_EXPIRY_MS` | 300 000 / 28 800 000 |
| `GOAL_TEXT_MS` | 8 000 |
| `SPORTS_CALL_TIMEOUT_MS` / `MAX_BODY_BYTES` | 10 000 / 524 288 |
| `LOGO_WAIT_MS` | 1 500 |
| Pill (`SCORE_W` × `SCORE_H`, radius) | 404 × 70, 35; Large ×1.25 = 506 × 88 |
| Pill segments | pad 16, logo 64 × 46, gap 8, score 48, gap 12, divider 1 × 34, centre 106 |
| Type | score 40/700 · clock 26/600 · period 20/600 `.70` · single line 24/600; floor 18 |
| `SCORE_INSET_X` / `SCORE_INSET_Y` / `SCORE_BAR_GAP` / slide | 96 / 56 / 16 / 150 ms FastOutSlowIn |
| Glass | outer ring Black .30 1 px · fill PanelFill .86 · highlight .06→0 over top 50 % · hairline .16→.06 · shadow 24 px baked, (0, 8), .45 |
| Goal box | 48 × 46, r 12, accent .35; 150 in / 2 400 hold / 450 out |
| Place-mode ring / hint | 3 px accent / 22 px on PanelFill |
| Sports card | 564 × 128, logo plate 64 × 44 r 8 `#0A0D13`, name 26/600, score 34/700, state 22/500 `.75` |

---

## Lead sign-off (2026-11-05)

**Lead rulings applied:**
1. A-B1 is taken: `nextWakeAtMono()` is in the contract, the loop sleeps until it, and wake tests with no input events
   are in §8.
2. A-B2 is taken: commit 0 defines every cross-WP contract. The WP file lists were re-checked and are disjoint (§9).
3. A-S1 and A-S2 are taken: ETags are gone, and live polls are phase-aligned 1–2 s after the 20 s bucket.
4. U-B2 is taken-modified: **all** Sports rows are ADVANCED, including Show Sports. The budget stays at 35, and
   `SimpleRowCountTest` is untouched.
5. U-B1, U-B3 and U-B4 are taken.
6. Proportions (5.77 : 1), logo trim and the 96 px inset are fixed.
7. Owner questions were reduced to three; the owner and the lead have since ruled on all three (§10).

| ID | Disposition | Note |
|---|---|---|
| A-B1 | taken | §2.7 `nextWakeAtMono`/`step`; §8 wake tests |
| A-B2 | taken | §4.4 `OverlayGeometry`/`PlacementInput`; `ScoreBugHostFacts`; `PlayerExtras.scoreBug`; §6 machine port; `events`; §9 |
| A-S1 | taken | ETag, 304 and `NotModified` removed (§2.2) |
| A-S2 | taken | bucket alignment from `Date − Age` (§2.3) |
| A-S3 | taken | cap measured from the last response (§2.5) |
| A-S4 | taken | monotonic clock, 15 s hold (§2.5) |
| A-S5 | taken | `DeviceAwake` fed by `LifecycleCoordinator` (§2.3, commit 0) |
| A-S6 | taken | `UNSEEN = max`, rise-to-`SEEN` poll, relative stale, `SEEN` independent of hiding |
| A-S7 | taken | `DayPollPolicy` in `:core` |
| A-S8 | taken | single actor; `runTest` time base |
| A-S9 | taken | wrapper doc, `KNOWN_DOMAINS`, `backedUp = false`, start refresh |
| A-S10 | taken | collection inside `ScoreBug`; clock layer; tick never in the draw cache; 60 s test |
| A-S11 | taken | `FollowLogos` holder, memory cache disabled; cache wording corrected |
| A-S12 | taken | two-404 rule; default network = online; `Age` clamp |
| A-N1 | taken | logging on state change |
| A-N2 | taken | `darkLogo` prefix check |
| A-N3 | taken | override behind `BuildConfig.DEBUG` |
| A-N4 | taken | server-`Date` skew for expiry |
| A-N5 | taken | cap on `source()` |
| A-N6 | taken | newest qualifying snapshot kept |
| A-N7 | taken | one `elapsedRealtime` base |
| A-N8 | taken | coil-svg in `:app` only; double registration noted; R8 measurement |
| A-N9 | taken-modified | owner ruling: no pre-game bug, so the wake is at start − 2 min and only detects live |
| A-N10 | taken | `/score/now` once per open; rollover guess dropped |
| A-N11 | taken | `DayState.Board(fetchedAtMono, failing)` in commit 0 |
| A-N12 | taken | log content-type and length only |
| U-B1 | taken | place mode (§6) |
| U-B2 | taken-modified | lead ruling: every row ADVANCED, not "Show Sports simple + budget change" |
| U-B3 | taken | `.86` fill, period `.70`, highlight `.06`, outer ring, bright-frame tests |
| U-B4 | taken | Follow / Following everywhere; dynamic footer; toasts |
| U-S1 | taken-modified | 404 × **70** (5.77 : 1) rather than 404 × 64 (6.3 : 1), so the ratio matches the mock-up; trim, 64 × 46 logos, 40/700 score |
| U-S2 | taken | inset x 96 |
| U-S3 | taken | hide for the passive bar, 150 ms slide for user bars |
| U-S4 | taken | row order, nested delay list, initial focus |
| U-S5 | taken | after Subtitles while following; before Settings otherwise |
| U-S6 | taken | LIVE dot, ✓ Following chip, dark logo plate |
| U-S7 | taken | initial focus, chips, single Back, scrolling viewport |
| U-S8 | not taken | owner ruling 2026-11-05: nothing until puck drop (no peek, no pre-game bug); the quick-bar value line confirms the follow |
| U-S9 | taken | `FooterHints` at 22; credit at 18 |
| U-S10 | taken | 3 s accent box + "GOAL CGY" 8 s; confirmed by the owner |
| U-S11 | taken | whole centre column dims |
| U-S12 | taken | postponed and Sports-off copy |
| U-N1 | taken | intersection-based |
| U-N2 | taken | "1st INT" |
| U-N3 | taken | measured fit with step-down |
| U-N4 | taken | row detail text |
| U-N5 | taken | day-aware empty text |
| U-N6 | taken-modified | Corner/Delay chips moved to the title row (the settings chip is gone: all rows are advanced) |
| U-N7 | taken | card clock ticks from the anchor |
| U-N8 | taken-modified | common name ("Canadiens"): the day list has no `placeName` (facts §2) |
| U-N9 | taken | non-selectable rows; retry; row text |
| U-N10 | taken | header fit test |
| U-N11 | taken | "Show Sports" |
| U-N12 | taken | logo descriptions, merged semantics |
| U-N13 | taken | rationale corrected (§4.3) |
