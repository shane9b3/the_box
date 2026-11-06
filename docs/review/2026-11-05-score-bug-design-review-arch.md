# Score bug design review: architecture, data and lifecycle

Reviewer: architecture/data (adversarial). Subject: `docs/design/m6-score-bug.md` (proposed 2026-11-05), against
`CLAUDE.md`, decision `docs/decisions/2026-11-05-nhl-score-bug.md` (binding), the API fact sheet
(`scratchpad/scorebug/api-facts.md`, "facts"), and code on `main` at `41f0bb3`. Visual polish and navigation UX are out of
scope. Line numbers are the design's unless a path is given.

Live check made for this review (2026-11-06 01:04 UTC, `curl` against `api-web.nhle.com`): the landing for
2026020251 and `/v1/score/2026-11-05`, fetched in the same second, both returned `etag: W/"6aed27a8--gzip"`, then
both `W/"6aed27bc--gzip"`. `0x6aed27bc` = 1793927100 = 01:05:00 UTC exactly, `0x6aed27a8` = 01:04:40. The raw captures
show the same thing (`raw/land.h` and `raw/score.h` both `W/"6aed24d8--gzip"`). **The ETag is the hex Unix time of the
end of a 20-second CDN bucket aligned to :00/:20/:40. It is not a content hash.** S1 and S2 rest on this.

---

## Blockers

### B1. The loop has no wake for time-driven view changes: "Final" never clears, delay and stale never advance
- **Section:** §2.7 loop (l. 262–265), §2.3 `FINAL | stop` (l. 165), §2.4 stale/hide (l. 182–184), §2.5 (l. 191–198),
  §2.6 expiry (l. 215–217).
- **Problem:** the repository loop only steps the machine when a poll returns or an input changes:
  `val at = machine.nextPollAtMono() ?: awaitInputChange()`. Several view changes happen *between* polls, or after
  polling has stopped, and nothing wakes the loop for them:
  1. **FINAL.** Polling stops (`nextPollAtMono() == null`), so the loop parks in `awaitInputChange()`. The expiry at
     `finalSeen + 5 min` is "checked on every machine step", and no step comes. The bug shows "Final" over every channel
     until the user leaves FULL or changes a setting. This breaks owner ruling 3 ("clears itself a few minutes after
     the final").
  2. **Delay > 0.** A queued snapshot becomes eligible at `anchorMono + delay`, which is not a poll instant. So the
     visible delay is `delay` plus up to 20 s, and the goal pulse fires late. When the real feed goes FINAL, polling
     stops and the delayed view **never reaches** the final snapshot. It stays on the last in-play snapshot with a
     capped, frozen clock.
  3. **Stale and hide.** `STALE_AFTER_MS` and `HIDE_AFTER_MS` are thresholds in time. They surface only when a back-off
     poll happens to land after them, and then late.
  4. **Clock anchor.** `clockAnchor` must switch to `ticking=false` at stale time and to the next snapshot at
     `anchor + delay`. Neither is a poll event.
- **Evidence:** l. 243–244 show `view()` and `nextPollAtMono()` as the only outputs. l. 264–265 say "nothing in `:data`
  wakes every second", and nothing wakes on a schedule at all. The §8 machine tests drive `view()` by hand with a fake
  clock, so they pass while the loop is broken.
- **Fix:** add `fun nextWakeAtMono(): Long?` to the machine contract in commit 0. It returns the minimum of: the next
  poll, the next snapshot to qualify (`anchor + delay`), the stale and hide thresholds, the extrapolation cap instant,
  `finalSeen + EXPIRE_AFTER_FINAL_MS` on the delayed timeline, and `start + HARD_EXPIRY_MS`. The loop then becomes
  `at = machine.nextWakeAtMono() ?: awaitInputChange(); delayUntil(at); if (machine.pollDue()) fetch; publish(view)`.
  Add a repository test: with polling stopped at FINAL, the subscription expires and `bug` becomes null at
  final + 5 min with no other input. Add one more: with a 30 s delay, the score changes at `anchor + 30 s`, not at the
  next poll.

### B2. Commit 0 does not define the contracts that WP-3 and WP-4 need, so the work packages collide or cannot compile
- **Section:** §9 (l. 546–577), §4.4 (l. 343–367), §6 (l. 464–480), §2.4/§2.6 toasts (l. 179–180, 166), §2.7
  `ScoreRepository` (l. 253–259).
- **Problem:**
  1. **`ScoreBugPlacement` cannot be built where it is placed.** It lives in `:core` and its test "no returned rect
     intersects any visible overlay rect (`PlayerUi` constants)" (l. 523–524). But `PlayerUi` is in `:ui`
     (`ui/src/main/kotlin/app/thebox/ui/full/PlayerUi.kt:12`), and `:core` cannot depend on `:ui`.
  2. **Placement needs facts that `FullScreenHost` does not receive.** It needs: toast showing, reminder card showing,
     PIN pad, and text track selected. Toasts and the reminder card belong to the root
     (`FullScreenHost.kt` KDoc: "The reminder card and toasts are the root's"; `AppRoot.kt:280` holds `toast`).
     `PlayerUiState` (`core/.../full/PlayerContracts.kt:373-388`) has no subtitle-track fact. WP-3 owns
     `FullScreenHost.kt` and WP-4 owns `AppRoot.kt`, so feeding these facts needs edits in both.
  3. **Nothing says how `ScoreRepository` reaches `FullScreenHost`.** The host gets only `GuideUiState` and
     `PlayerHost?` (`FullScreenHost.kt:67-73`, called at `AppRoot.kt:366`). There is no Hilt injection point in that
     composable.
  4. **The player machine has no port for the quick-bar item and the picker.** The picker's game rows, the
     "Loading games…" row and the value line "CGY 0–2 EDM" are built by `PlayerStateMachineImpl` (`:core`), which takes
     only `effects`, `listRows` and `nowMs` (`PlayerStateMachineImpl.kt:88-92`). It has no input that delivers the day
     list or the bug state asynchronously, and `PlayerEffects` has no `chooseScoreGame` / `setScoreCorner` /
     `setScoreDelay` effect. Every existing picker is a synchronous list; this one loads.
  5. **No event channel.** The design toasts on 404 ("no longer listed"), on postponed, and on choosing a final game,
     but `ScoreRepository` (l. 253–259) has no `events` flow, so `:data` has no way to raise them.
- **Fix:** add the following to commit 0, compiled and stubbed:
  - Move the geometry `ScoreBugPlacement` needs into a `:core` object (e.g. `PlayerGeometry`, or `ScoreBugGeometry` that
    copies the `PlayerUi` numbers it uses), or move placement and its test to `:ui`. In either case, add a `:ui` test
    asserting the copied constants equal `PlayerUi`'s.
  - Add a `ScoreBugHostFacts` (toast, reminder, PIN pad, text track) parameter to `FullScreenHost`, plumbed in
    `AppRoot`, with commit 0 owning both edits.
  - Add a `ScoreBugSource` (or a field on `PlayerExtras`) carrying `bug`, `clockAnchor` and `reportDemand`.
  - Add a `PlayerStateMachine.scores(ScoreFacts)` input and the `PlayerEffects` score effects.
  - Add `ScoreRepository.events: Flow<ScoreEvent>` (`Removed(reason)`, `ChosenFinal`).
  - After that, WP-3 and WP-4 touch disjoint files.

---

## Should-fix

### S1. ETag and 304 handling is built on a false premise; drop it
- **Section:** §0.2 (l. 31), §2.2 (l. 140–141), `Fetch.NotModified` (l. 91), §8 tests (l. 526).
- **Problem:** the ETag is a 20-second time-bucket id shared by every URL (see the live check above). It is not a
  content validator. A 20 s poll against a 19 s TTL almost never gets a 304. When it does, the 304 says only "same
  bucket", which the poll interval already rules out. The "304 keeps the old anchor" rule (l. 140) adds a code path and
  a test row for nothing. The facts' own claim that the ETag "changes every cache refresh" (facts l. 99) was the clue.
- **Fix:** remove `If-None-Match`, `Fetch.NotModified` and the per-URL ETag map, and record why in §2.2. If you keep
  them, record that 304 is expected to be rare and count it as a success for the stale timer (the design does not say
  this either way).

### S2. Align polls to the CDN bucket: about 10 s fresher on average at no extra cost
- **Section:** §2.3 (l. 162–173).
- **Problem:** objects expire on fixed 20 s boundaries (:00/:20/:40). A free-running 20 s timer lands at a random phase
  and often receives `age` 15–19 (`raw/land.h` `age: 18`). The worst case in l. 172 ("20 s (CDN) + 20 s (poll)") is
  the typical case, not the outlier.
- **Fix:** schedule the next live or intermission poll at `bucketEnd + POLL_PHASE_MS` (1–3 s random, fixed per
  install). Derive `bucketEnd` from the response as `serverDate − age + 20 s` (server `Date`, not the device clock),
  falling back to the free-running timer. Test: a mocked response with `Date`/`Age` yields a next poll at the predicted
  instant.

### S3. The clock cap is measured from the wrong origin and will freeze the clock before most polls
- **Section:** §0.5 (l. 37–39), §2.3 (l. 162), §2.5 (l. 195).
- **Problem:** the extrapolation starts at `anchorMono = receivedMono − age`. The typical age at receipt is up to 19 s,
  plus the 20 s poll interval, plus up to a 10 s `callTimeout`, so the anchor is 39–49 s old just before the next fresh
  body arrives. The 40 s cap (`EXTRAPOLATE_CAP_S`) will therefore trip routinely and freeze a running clock for several
  seconds before most polls, especially with the jitter of S2.
- **Fix:** apply the cap to `now − receivedMono` (time since we last heard anything), not to `now − anchorMono`, and
  keep using `anchorMono` for the value. Add a machine test: age 19, a poll every 20 s and a 5 s RTT never freeze a
  running clock.

### S4. The clock will jump upwards on resyncs; make the displayed clock monotonic
- **Section:** §0.5, §2.5.
- **Problem:** the origin updates `secondsRemaining` in 15–30 s steps (facts l. 106: `15:16, 15:16, 15:00, 14:45` at a
  12 s cadence). `age` measures time in the CDN, not how stale the origin value was. Each fresh snapshot can therefore
  sit up to about 15 s *behind* the local extrapolation, and the shown clock jumps *up* every 20 s. On a TV score bug
  that reads as broken.
- **Fix:** in `view()`, while the phase, period and ticking state are unchanged, never show a value higher than the
  value last shown. If a resync is higher, hold the shown value until the extrapolation catches up, for at most
  `CLOCK_HOLD_MAX_S` (15 s); after that, snap. Jump immediately only on a period or phase change, or a stop/start. Add
  machine tests for both cases.

### S5. Standby or display-off without `ON_STOP` keeps polling every 20 s for hours
- **Section:** §0.3 (l. 32–34), §2.3 demand table (l. 152).
- **Problem:** demand `NONE` is keyed on `ProcessLifecycleOwner` `ON_STOP` and the screen-off layer. The app already
  knows of standby paths where the process stays STARTED: the default display turning off, HDMI-CEC TV off and HDMI
  unplug (`player/.../controller/LifecycleCoordinator.kt:25-29`, "B4"). In those cases playback stops after the 30 s
  grace, FULL stays the screen mode, the bug counts as "on screen", and the box polls at 20 s until final + 5 min or
  start + 8 h. That contradicts §0.3 ("standby, screen-off … mean no requests").
- **Fix:** make the repository's demand also `NONE` while the display is not ON or `PowerManager.isInteractive()` is
  false. Expose `LifecycleCoordinator`'s device facts (or a small `:core` `DeviceAwake` flow fed by the same receivers)
  rather than re-registering receivers in `:data`. Add a test row: display off with the process STARTED gives zero
  requests.

### S6. The BACKGROUND cadence overrides the phase rules, and "on screen" flaps with transient hiding
- **Section:** §2.3 (l. 153–154, 160–166), §2.4 stale (l. 182).
- **Problem:**
  1. `BACKGROUND` polls "every 120 s" regardless of phase. A game chosen for tomorrow gets polled every 2 min all day
     in the guide, while the `SCHEDULED` row says to sleep until start − 10 min. The same conflict exists for `FINAL`
     ("stop").
  2. `BACKGROUND`'s 120 s interval exceeds `STALE_AFTER_MS` (60 s). On entering FULL, the first frames read
     **No update** until the catch-up poll returns.
  3. If `BUG_ON_SCREEN` is reported only while the bug is drawn, every channel-list or picker open (which hides the bug,
     §4.4) drops demand to 120 s.
- **Fix:**
  - BACKGROUND cadence = `max(phaseCadence, POLL_UNSEEN_MS)`. `SCHEDULED` and `FINAL` keep their own rules.
  - A rise to `ON_SCREEN` polls at once when the last success is older than the live cadence.
  - The stale threshold is relative to the current demand: `max(STALE_AFTER_MS, 2 × cadence)`, and "No update" is
    suppressed until the first poll after a demand rise completes.
  - Define `BUG_ON_SCREEN` as "`ScreenMode.FULL` with a choice", independent of placement hiding.
  - Add these as rows in `ScoreBugMachineTest`.

### S7. The day-list cadence sits outside the pure machine
- **Section:** §2.3 (l. 168–170), §2.7 (l. 224–232).
- **Problem:** §0.7 says state lives in one pure machine, but the day-list polling rules (30 s / 5 min / once for
  all-finals / stop on close) are in no `:core` type. They would land in `ScoreRepositoryImpl` in `:data`, where they
  are tested only through MockWebServer and wall time.
- **Fix:** add a pure `DayPollPolicy.nextAt(board, demand, lastOkMono, failures): Long?` in `:core` (WP-1), with a
  table test. The repository only executes it.

### S8. A threading rule for the machine is missing
- **Section:** §2.7 (l. 237–265).
- **Problem:** inputs arrive from the main thread (`choose`, `reportDemand`, prefs), from the network callback thread
  and from the loop on `Dispatchers.Default` (`DispatchersModule.kt:28-29`). The machine is a plain mutable class.
- **Fix:** state in the contract that the repository serialises every input through one `Channel`/actor (or a
  `Mutex`) and that the machine is single-threaded. The §8 test for "ON_STOP stops within one step" then becomes
  deterministic under `runTest` with a `MonoClock` backed by `testScheduler.currentTime`. Say that explicitly too, since
  the loop's `delay` and `MonoClock` must share a time base in tests.

### S9. Persistence details commit 0 must get right
- **Section:** §2.6 (l. 204–217), §7 (l. 502), §9 commit 0 (l. 552).
- **Problem:**
  1. A `SettingsDomain<T>` needs a non-null default (`core/.../settings/SettingsStore.kt:17-26`), and the store has no
     delete. "Expiry clears the domain" therefore needs a wrapper document, e.g.
     `@Serializable data class SportsSubDoc(val sub: ScoreSubscription? = null)`, and clearing means
     `update { SportsSubDoc() }`.
  2. `Domains.SPORTS` must also be added to `RoomSettingsStore.KNOWN_DOMAINS`
     (`data/.../settings/RoomSettingsStore.kt:370-375`). Otherwise a restore made before Sports is first used drops the
     backed-up `sports` document (`:197`). Commit 0 lists `NOT_BACKED_UP_DOMAINS` but not this file.
  3. The `sports_sub` `SettingsDomain` itself must pass `backedUp = false`. The set alone only covers unregistered
     rows (`:172`, `:211`).
  4. `startUtcMs` is frozen at choose time, so the 8 h hard expiry ignores a re-timed start.
- **Fix:** put items 1–3 in commit 0. Update the stored `startUtcMs` from each fresh snapshot (a rare write: only when
  it changes).

### S10. `FullScreenHost` recomposes on every poll; the bug's tick has no layer of its own in low-end mode
- **Section:** §4.7 (l. 398–409), §2.7 `clockAnchor` (l. 255).
- **Problem:**
  1. `clockAnchor` changes on every fresh poll (every 20 s). If it, or `bug`, is collected in `FullScreenHost`
     (where every other flow is collected, `FullScreenHost.kt:75-91`), the whole host recomposes every 20 s. U-S5 kept
     that host from recomposing on 2 Hz stats. If it is read in `ScoreBug`'s composition, `ScoreBug` recomposes every
     20 s. The 10-second test (l. 532) cannot catch either.
  2. The design relies on `AnimatedVisibility`'s fade layer to contain the tick. In low-end mode `Fade` uses
     `EnterTransition.None` (`FullScreenHost.kt:192-193`), so there may be no layer, and each tick re-records the
     nearest ancestor layer: the shell root, with every visible FULL overlay.
  3. Reading the tick state inside the glass `drawWithCache` block rebuilds the cache (path, brushes) every second.
- **Fix:**
  - Collect `bug` and `clockAnchor` inside `ScoreBug` only, and hold `clockAnchor` as a `State` read in the draw lambda.
  - Give the clock text node its own `Modifier.graphicsLayer()`. It is not an ancestor of the surface, so CLAUDE.md
    allows it.
  - Read the tick only in a separate `drawBehind`/`onDraw`, never in `onBuildDrawCache`.
  - Extend `ScoreBugDrawTest` to 60 s with three polls: 0 recompositions of `FullScreenHost` and of `ScoreBug`; and add
    the low-end variant.
  - Amend `FullIdleFramesTest` and plan §6 as the design proposes, with a separate "ticking bug" case.

### S11. Logos are evicted from the shared 2 MB memory cache; the disc will flash on every return to FULL
- **Section:** §3 (l. 277–284).
- **Problem:** the bug's logos share `ImageModule`'s memory cache: 2 MB, 1 MB on low-end
  (`app/.../image/ImageModule.kt:30,36`). The panel and mini-player posters live there too, and the guide's own logos
  bypass it for this reason (`LogoCache`, same KDoc). After browsing the guide, both bitmaps are gone. Every
  guide → FULL then re-parses and re-rasterises two SVGs, and the fallback disc shows for a few frames each time, which
  also costs extra frames. The design also says the logos are "rasterised once into Coil's caches" and Coil "respects
  `max-age=7200`". In Coil 3 the disk cache stores the SVG source, not the raster, and by default it ignores
  `Cache-Control` (that needs `coil-network-cache-control`). Treat that as unverified here and confirm it in the
  3.6.3 sources.
- **Fix:** keep the two rasterised `ImageBitmap`s in a small holder scoped to the subscription, inside the
  `ScoreBug`/`TeamLogo` state, following the `LogoCache` precedent. Load them with `memoryCachePolicy(DISABLED)` and
  release them on choice change or expiry. Correct the §3 wording on caches. `memoryCacheKey` "includes the size" is
  already Coil's default key behaviour, so drop it.

### S12. Rare signals are treated as fatal or absolute
- **Section:** §2.4 (l. 179–181), §2.2 (l. 142).
- **Problem:**
  1. One 404 removes the subscription. A captive portal, a proxy or a CDN hiccup can return a 404 with an HTML body.
  2. "Offline = no validated network" (l. 181). On boxes where the platform's connectivity check is blocked (Pi-hole,
     some regions, Fire OS checks against Amazon) `VALIDATED` never arrives, and the feature would be silently dead.
  3. `age > 60 → 0` turns a stale CDN object into a "fresh" one.
- **Fix:**
  - Remove the subscription only after two consecutive 404s at least 20 s apart, both with a JSON or empty body.
  - Treat "a default network exists" as online and let request failures drive back-off (the `SmbSessionKeeper` callback
    at `data/.../smb/SmbSessionKeeper.kt:519` is the precedent).
  - Clamp `age` to `[0, 60]` instead of zeroing it.

---

## Nits

- **N1** (§2.2 l. 143, §2.4 l. 185) Rate-limit failure logs: one line per state change (ok → failing → ok), not per
  poll. A 20 s failure loop fills `BoxLog`'s ring.
- **N2** (§3 l. 275) The landing's `darkLogo` URL comes from a remote body and goes straight to Coil. Accept it only when
  it starts with the logo base (`https://assets.nhle.com/logos/nhl/svg/` or the debug override); otherwise derive it.
  This keeps the "only the IP leaves the box, only to NHL/Cloudflare" claim true.
- **N3** (§8 l. 537) `DebugOverrides` is in `:core` main and ships in release
  (`core/.../diag/DebugOverrides.kt:9`). Read the sports base override only behind `BuildConfig.DEBUG` in `:data`, as
  the `allowHttpDoh` comment implies.
- **N4** (§2.1 l. 122) "Today" and the 8 h expiry use the device wall clock. Fire TV boxes can boot with an unsynced
  clock. After the first response, use the server `Date` minus device time as a skew for `today()` and expiry (debug
  `clockOffsetMs` already exists).
- **N5** (§2.1 l. 131) Enforce `MAX_BODY_BYTES` by reading `body.source()` with a limit (`Okio` `peek`/`request`), not
  `body.string()` followed by a check.
- **N6** (§2.5 l. 192) Never prune the newest snapshot whose `anchorMono ≤ now − delay`, even when it is older than
  `QUEUE_KEEP_MS`. Otherwise a long stale spell with a 120 s delay empties the shown slot.
- **N7** (§2.7 l. 237) `ScoreBugMachine(mono: MonoClock, …)`: say that the UI's per-second tick computes from the same
  `SystemClock.elapsedRealtime` (`core/.../playback/PlaybackContracts.kt:10-11`), not from frame time or
  `System.currentTimeMillis`, or the anchor arithmetic drifts.
- **N8** (§3 l. 271–273) `coil-svg` is needed only in `:app` (where `ImageModule` is), plus `testImplementation` in
  `:ui` if a test references `SvgDecoder`. Adding `SvgDecoder.Factory()` explicitly while ServiceLoader also registers
  it gives two factories. That is harmless, but note it. Measure §11.6 on the **release** (R8) universal APK, and check
  that `androidsvg` needs no keep rules.
- **N9** (§2.3 l. 160) `PRE` begins about an hour before puck drop (facts l. 52), but `SCHEDULED` sleeps until
  start − 10 min. If §10 Q1 is accepted, the bug appears 10 min before, not when the game turns `PRE`. Pick one: wake at
  start − 60 min at the 60 s cadence, or document it.
- **N10** (§2.1 l. 122) The rollover hour is U. Consider one `/v1/score/now` per Sports-tab open (a 307 with the
  authoritative date in `Location`) to seed "today", keeping dated URLs for every poll. This is cheap, and it removes
  the guess.
- **N11** (§5.4 l. 460) `DayState.Board` needs `fetchedAtMono` and a `failing` flag for "Updated 2 min ago". Add them in
  commit 0's contract, not in WP-4.
- **N12** (§2.4 l. 185) "the first 80 chars of the body's top-level keys" is ambiguous for an HTML body, which has no
  keys. Log `content-type` and the length only.
