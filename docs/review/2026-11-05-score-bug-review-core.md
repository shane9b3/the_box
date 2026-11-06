# NHL score bug: code review C (core, data, wiring)

Reviewer C, 2026-11-05. Tree: `main` at `730b7c5` (feature range `cba71c3..730b7c5`).
Scope: `core/.../sports/*` (machine, day policy, mapper, placement, contracts), `data/.../sports/*` (source, repository,
module, signals), `DeviceAwakeState` / `LifecycleCoordinator`, the score wiring in `PlayerViewModel` / `PlayerEffectsImpl`,
settings domains and backup exclusion, `tools/fakepanel` sports, coil-svg / `ImageModule`, and their tests. Reviewer U
covers the overlay, the Sports tab and the player machine's sheet. Binding inputs: `CLAUDE.md`, the decision note
`docs/decisions/2026-11-05-nhl-score-bug.md`, `docs/design/m6-score-bug.md` (sign-off and integration pass), and the API
fact sheet.

**How the findings were checked**
- `:core:test --tests app.thebox.core.sports.*` and `:data:testDebugUnitTest --tests app.thebox.data.sports.*` pass.
  `tools/fakepanel` `python3 -m unittest test_sports` passes (17 tests).
- C-S1 to C-S4 and C-S6 were reproduced with throwaway probe tests. They were compiled into `:core` / `:data` through a
  Gradle `--init-script` that pointed at the session scratchpad, so no repo file changed. Each finding gives its probe's
  output.
- C-S5 and the nits come from reading the code.

**Counts:** 0 blockers · 6 should-fix · 7 nits.

---

## Ruling asked for: do stale, hide and the cap shift by the delay? (WP-1 reading; integration item "left for the reviewer")

**Confirmed: the WP-1 reading is right**, and the design needs it even though §2.4/§2.5 do not say so. Confidence high.

- With delay D, the newest snapshot (received at R, anchor R − age) is first shown at R − age + D.
- **Stale, without the shift.** Stale would fire at R + 60 s. With D ≥ 60 s the bug would read "No update", dim, and
  later hide, while the queue still held up to D seconds of good snapshots that had not been shown yet. The display would
  call itself stale while it was still moving through fresh data.
- **Stale, with the shift (`ScoreBugMachine.kt:391–401`).** The shown snapshot gets the same 60 s + age of freshness as at
  D = 0, so the stale rule means the same thing on the delayed timeline.
- **The cap, without the shift.** The 40 s cap would freeze the shown clock at R + 40 s. The next queued snapshot takes
  over only at its own anchor + D, so with D > 40 s every 20 s step would be a freeze followed by a jump.
- **The cap, with the shift (`:379`).** This is the delayed equivalent of A-S3.
- **What the design should say.** The design text lists only goals, Final and expiry as running on the delayed timeline.
  Add one sentence to §2.5: "stale, hide and the extrapolation cap are measured from `lastReceived + delay`".
- **One defect in this area.** The shifted cap still has a defect in how the hold reads the *previous* cap. See C-S1.

---

## Blockers

None found.

---

## Should-fix

### C-S1: With a delay, a gap of more than 40 s between answers makes the clock jump up (and first jump down)
`core/src/main/kotlin/app/thebox/core/sports/ScoreBugMachine.kt:321` (with `:372–388`). Confidence high (reproduced).

**Cause.**
- `advanceDisplay` computes the outgoing value as `liveAnchor(prev, at, prevOkReceived)`, that is, with the cap the
  previous snapshot had *before the newest answer arrived*.
- With delay 0 that matches what was on screen, because the newest answer is shown the moment it arrives.
- With delay > 0, `anchor()` recomputes the cap from `lastOk` as soon as an answer *arrives*, which can be D seconds before
  that answer is *shown*.
- Two things follow:
  1. When the answer arrives, the frozen clock is released and drops by the length of the freeze. It jumps down, and it
     hints that an answer arrived before it is shown.
  2. When the answer comes due, `before` is evaluated with the old, earlier cap. It returns the higher frozen value, so the
     hold (or the snap to the new value) moves the shown clock **up**. This breaks A-S4, "never jumps up within a period".

**Scenario.**
- Delay 30 s, live, running. One poll fails or times out, so 85 s pass between two `Ok`s (back-off 20 s plus jitter plus
  a 10 s timeout is enough). There was a stoppage in between.
- Probe trace (displayed seconds):
  `70 000=582 … 80 000=582, 85 000=567, … 110 000=542, UP at 115 000: 538 → 582, … 125 000=580`.
- The same input at delay 0 is monotonic (probe P1b: no "UP").

**Fix.**
- Evaluate the cap of a snapshot that has not been shown yet from the arrival of the newest answer that *is shown or
  older*, not from `lastOk`.
- For example, give each `Entry` a `capFrom` and compute it when it becomes `shown`. Use the `receivedMono` of the newest
  entry whose `anchor + delay ≤ now`, which is the shown entry itself.
- Then pass that same value to `before`. Both the display and the hold then read the cap that was actually drawn.
- Add a regression test: delay 30, an 85 s gap with a stoppage, and the display sampled every second must not increase.

### C-S2: A rise to SEEN while offline suppresses "No update" and hide indefinitely
`ScoreBugMachine.kt:91–97` (sets `suppressStale`), `:259–262` (`pollAt` returns null while offline), `:391–392`.
Confidence high (reproduced).

**Cause.**
- `demand(SEEN)` sets `eagerAt` and `suppressStale = true` without checking `online`.
- No poll can run while offline, and only `fetched()` clears `suppressStale`.
- So stale is off until the network returns. While offline the bug shows the last score **undimmed**, with the clock frozen
  at the cap and a normal period line, and it never hides.

**Scenario.**
- A follow is UNSEEN (the user is in the guide). The Wi-Fi drops, and after 250 s the bug is correctly stale.
- The user goes to full screen, which is a rise to SEEN.
- Probe: one hour later, still offline, `view()` has `bottom = Period(2nd)` and `dim = false`, and the only wake is the 8 h
  expiry.
- The same happens after standby, when `Awake(true)` usually arrives before the default network is back. This breaks
  §2.4 (stale and hide) and the reason-and-next-step rule for errors.

**Fix.**
- Set `suppressStale` only when a poll can actually be sent, i.e. `online` (and demand not NONE).
- Clear it in `online(false)`.
- Optionally bound it in time, for example to `SPORTS_CALL_TIMEOUT_MS` after the rise.
- Add an offline variant to `riseToSeenPollsAtOnceAndSuppressesNoUpdate`. Today that test turns `online(true)` on before
  the rise (see C-N6).

### C-S3: A rise to SEEN skips the back-off and `Retry-After`
`ScoreBugMachine.kt:91–96` and `:262` (`eagerAt?.let { return it }` runs before the `failures > 0` branch at `:264`).
Confidence high (reproduced).

**Cause.**
- Every UNSEEN → SEEN edge with no recent `Ok` sets `eagerAt = now`, and `pollAt` returns it ahead of `backoffAt`.
- The edge is caused by entering FULL, opening the Sports tab or opening the score sheet. Any of these sends a request at
  once, whatever the back-off or the server's `Retry-After` says.

**Scenario.**
- The API answers 429 with `Retry-After: 300`.
- Probe: just after the 429, `pollDue = false` and the next poll is at 300 000. Two seconds later,
  `demand(UNSEEN); demand(SEEN)` makes `pollDue = true`.
- A user zapping between the guide and full screen therefore sends one request per entry.
- §2.4 says "`Retry-After` wins", and the fact sheet asks for at most one poll per 20 s per client. This is the one path
  by which the client can exceed both against an unofficial feed.

**Fix.**
- In `demand()`, do not set `eagerAt` while `failures > 0` and `now < backoffAt` (the back-off or `Retry-After` is still
  running).
- Or keep `eagerAt` but have `pollAt` return `max(eagerAt, backoffAt)` when `failures > 0`.
- Test: a 429 with `Retry-After` 300 followed by a UNSEEN → SEEN edge must poll nothing before 300 s.

### C-S4: `Retry-After: 0` (or any value under 1 s) means polling at about 1 Hz
`core/src/main/kotlin/app/thebox/core/sports/DayPollPolicy.kt:71–72`,
`data/src/main/kotlin/app/thebox/data/sports/NhlScoreSource.kt:239–241` (clamps to [0, 300]), and
`ScoreRepositoryImpl.kt:273–281`. Confidence: high for the mechanism; medium that a CDN sends 0.

**Cause.**
- `backoffAt(failMono, n, retryAfterS = 0)` returns `failMono`, so the next poll is due at once.
- The repository's stuck-wake guard turns that into a 1 s hold, never a spin. The result is a request about every
  1 s + RTT, for the followed game and for the day list alike, for as long as the server keeps answering
  `Retry-After: 0`.

**Probe.**
- Machine: `Failed(5XX, retryAfterS = 0)` at 5 000 gives `nextWakeAtMono = 5000` and `pollDue = true`.
- Day policy: `nextAt(…, failures = 1, lastFailMono = 5000, retryAfterS = 0)` returns 5000.

**Fix.**
- Make `Retry-After` a floor on the back-off, not a replacement for it:
  `failMono + max(retryAfterS * 1000, backoffMs(failures, jitter))`.
- If "wins" has to stay literal, at least clamp `retryAfterS` to `[BACKOFF_BASE_MS / 1000, 300]` in `retryAfterS()`.
- Add a case to `backoffSequenceAndRetryAfter` and to `DayPollPolicyTest.backoffAfterFailures`.

### C-S5: The screen-off layer is UNSEEN demand, not NONE, so the followed game keeps polling
`ui/src/main/kotlin/app/thebox/ui/full/vm/PlayerViewModel.kt:420–422` and `ScoreRepositoryImpl.kt:363–369`.
Confidence high (code reading).

**Cause.**
- §0.3 and the §2.3 demand table both list "screen-off layer" under `NONE` (zero requests).
- `forwardScores()` only withdraws `FULL_WITH_FOLLOW` when the layer is `SCREEN_OFF`.
- `pushDemand()` then computes `UNSEEN`: a game is followed and the process is foreground and awake.
- `DeviceAwake` stays true, because the layer is the app's own black screen, not a display-off event.

**Scenario.**
- A user follows a game, then turns the screen off from the quick bar to listen to audio overnight.
- The landing is polled every 120 s (pre-game: every 120 s after start − 2 min) until the game ends.
- This breaks a "Must" zero-traffic case of the signed-off design.

**Fix.**
- Pass the screen-off fact to the repository as its own input. Either add a `ScoreBugSource.reportScreenOff(on)`, or add a
  `DemandOwner`-style "suppress" flag that `PlayerViewModel` sets from the same `combine`.
- Have `pushDemand()` return `NONE` while it is set.
- Add a `ScoreRepositoryImplTest` case: screen-off with a follow sends 0 requests over 10 min.

### C-S6: After the Sports tab showed Yesterday or Tomorrow, the score sheet never asks for today again
`data/src/main/kotlin/app/thebox/data/sports/ScoreRepositoryImpl.kt:317–321` and `:379`. Confidence high (reproduced).

**Cause.**
- `ShowDay(key)` stores `dayWanted = key`, and nothing but `SportsViewModel.onEntered()` (`showDay(null)`) clears it.
- `pushDemand()` re-resolves `/score/now` on a new open only when `dayWanted == null`.
- The player sheet only reports `SCORE_PICKER` demand. It never calls `showDay(null)`.

**Scenario.**
- Sports tab → Tomorrow → leave → full screen → Score bug sheet.
- Probe: the calls are `[today, day:2026-11-06, --- picker opens]` and the board is `2026-11-06`.
- The sheet lists tomorrow's games (or yesterday's finals) under its "Today" divider. With a live day it keeps re-polling
  that other day. Following from the sheet then follows a game from the wrong day.
- The integration pass lists this as "until that answer lands"; there is no such answer.

**Fix.**
- On a new open of `SCORE_PICKER` (the NONE → SEEN edge with `SCORE_PICKER` in the owners), set `dayWanted = null` and
  `dayKey = null`.
- Or have `PlayerEffectsImpl.scorePicker(true)` call `repo.showDay(null)` next to `reportDemand`.
- Then update the integration-pass row.

---

## Nits

- **C-N1. Goal on a stale comparison.**
  - Where: `ScoreBugMachine.kt:326–337`. Confidence high.
  - After a long `NONE` or `UNSEEN` stretch, such as an hour in the background or a hidden bug, the first answer on return
    is compared with the hours-old `shown` snapshot. The result is "GOAL CGY" and the accent box for a goal scored long
    ago. When both teams scored meanwhile, only `HOME` is flagged.
  - Fix: flag a goal only when `prev` was received within about 2 × the live cadence of `q` (otherwise update silently),
    and prefer no tag when both sides rose.
- **C-N2. `FULL_WITH_FOLLOW` is never withdrawn.**
  - Where: `PlayerViewModel.kt:420–422`.
  - When the view model is cleared, the last `true` stays in `owners`, so demand is SEEN in every mode until the next view
    model reports. On a TV that heals itself after an activity recreate, and it is harmless while backgrounded (NONE).
  - Fix: `addCloseable { repo.reportDemand(FULL_WITH_FOLLOW, false) }`, or do it in `onCleared()`.
- **C-N3. Inputs wait behind a fetch.**
  - Where: `ScoreRepositoryImpl.kt:296–302`.
  - Both fetches run inline in the actor, so Stop following, Show Sports off and `Awake(false)` wait behind a stalled
    request: up to 10 s each, about 20 s when both the game and the day are due.
  - This is acceptable per A-S8. A note in the KDoc, or launching the call and posting the result as an `Input`, would
    remove it.
- **C-N4. Day rollover with the tab open.**
  - Where: `ScoreRepositoryImpl.kt:422`.
  - The "today" key is resolved once per open. A Sports tab left open across the Eastern-time rollover keeps showing the
    old day as Today; once that day is all final, it stops polling.
  - This follows the design (A-N10). If wanted, re-resolve `/score/now` when the board is all final and `dayWanted == null`.
- **C-N5. The `assumeTrue(wp1Implemented())` guards are now dead.**
  - Where: `ScoreRepositoryImplTest.kt:571–578` and the three `realMachine_*` tests.
  - If a regression ever made the machine throw `NotImplementedError` again, the tests would silently skip instead of
    failing. Remove the guards.
- **C-N6. The rise-to-SEEN test does not cover offline.**
  - Where: `ScoreBugMachineTest.riseToSeenPollsAtOnceAndSuppressesNoUpdate`.
  - It turns `online(true)` on before `demand(SEEN)`, which is why C-S2 was missed. `backoffSequenceAndRetryAfter` never
    raises demand during a back-off, which is why C-S3 was missed.
  - Add both cases.
- **C-N7. The design text lags the code.**
  - Where: `docs/design/m6-score-bug.md` §2.5.
  - Record the delay-shift ruling above. Also note that the 8 h expiry before the first `Ok` after a restart uses the device
    clock. That is accepted in the integration pass, but a box that boots with its clock *ahead* drops the follow silently
    before any poll.

---

## Checked and fine

- **Wake scheduling.**
  - `nextWakeAtMono()` covers the poll, the next queued snapshot, stale, hide, the cap, the final and hard expiry, the hold
    end and the goal-text end.
  - Wake tests drive it with no input.
  - The 1 s stuck-wake guard cannot spin.
  - No legitimate wake is lost: inputs still wake `select`, and the delay is at most 1 s.
- **Timing arithmetic.**
  - The bucket math (`received − Age + 20 s + phase`) matches A-S2.
  - The back-off doubles from 20 s and caps at 300 s ±20 %.
  - `NotFound` needs two answers at least 20 s apart (an HTML 404 is `HTTP_OTHER`), and an `Ok` in between resets it.
  - The pre-game sleep runs until start − 2 min and then polls every 40 s.
  - FINAL and POSTPONED stop polling.
- **Restart.** A restored follow polls once at the first demand. A persisted `finalSeenUtcMs` expires on time.
  `followed()` changes are persisted in `publish()`.
- **Zero traffic.**
  - Demand is NONE with nothing followed and no Sports UI open, with the process stopped, with the device not awake
    (display off, screen off, not interactive, an HDMI unplug transition but not the sticky broadcast), and with Show
    Sports off. Offline sends nothing.
  - The day list runs only while the tab or the picker is open.
  - The exception is C-S5.
- **HTTP.**
  - The body cap is enforced before decoding, and the HTML sniff reads 512 bytes.
  - `callTimeout` is 10 s. There is no cache, ETag or `If-None-Match`, and the call is cancelled with the coroutine.
  - `@ApiClient` adds only the UA and timeouts: no credentials or keys leak to NHL hosts.
  - Logs carry the path, the kind, the content type and the length only.
  - The `darkLogo` prefix check uses a base with a trailing slash, so no host-suffix trick works.
  - The debug override is read only behind `BuildConfig.DEBUG`.
- **Hilt.**
  - `Optional<ScoreRepository>` (`:ui`) and `Optional<DeviceAwake>` (`:data`) are bound by `SportsModule` and
    `PlaybackModule`.
  - `DeviceAwakeState` is one `@Singleton` shared by the controller's coordinator and the repository.
  - The `AndroidSportsSignals` callbacks live for the process; that is the intended lifetime of a singleton, so they need
    no unregistering.
- **Settings.**
  - `sports_follow` has `backedUp = false`, is in `NOT_BACKED_UP_DOMAINS`, and is in `KNOWN_DOMAINS`.
  - `sports` is restored before its owner registers it.
  - Clearing a follow keeps `pollPhaseMs` and `firstFollowShown`.
- **coil-svg.** The dependency is in `:app` only, and the double decoder registration is harmless. Licences were added.
- **Mapper.** It reads `default` for localised fields, makes every field nullable and tolerates unknown keys. It handles
  OT numbering (2OT from `number − regPeriods`), `Final/OT` and `Final/SO` from `gameOutcome`, POSTPONED when
  `gameScheduleState != OK`, and the Eastern fallback when `gameDate` is missing.
