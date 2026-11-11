# Review: parental lockout clock fix (security F7), 2026-11-11

Commit under review: `54aefd1` "Stop the wall clock from ending a parental lockout" (parent `c0f0b3f`).
Scope: `core/.../parental/LockoutPolicy.kt`, `data/.../parental/ParentalPinStoreImpl.kt`,
`data/.../parental/ParentalSessionImpl.kt`, their tests, `docs/design/m5-setup-settings.md` §3.9.4–§3.9.5 and the
2026-11-09 review README / security.md.

Verdict: **merge after the one should-fix** (it is a code or doc change of a few lines). No blocker: I found no
sequence in a release build that ends a lockout early or resets the ladder step.

Verification run: `./gradlew :core:test --tests '*parental*' :data:testDebugUnitTest --tests '*parental*'` passes.
`:app:hiltJavaCompileDebug` passes (the nullable `@ApplicationScope` constructor parameter resolves in the graph).
I also ran throwaway Robolectric probes (deleted afterwards; their results are cited below).

---

## Blocker

None.

## Should-fix

### S1. Lenient decoding can end a wait or reset the ladder, against the documented guarantee

- **Where:** `data/src/main/kotlin/app/thebox/data/parental/ParentalPinStoreImpl.kt:53-62` (`int()` / `long()` return
  the default on `ClassCastException`). The claim it breaks: `docs/design/m5-setup-settings.md:1899` ("a damaged file
  never throws and never ends a wait early") and the KDoc at `ParentalPinStoreImpl.kt:47-48`.
- **Sequence (reproduced with a probe):**
  - `wait_ms` stored as an `Int` (`putInt("wait_ms", 1_800_000)`) with a valid anchor reads back as `waitMs = 0`, so the
    30-minute wait is gone.
  - `failures` stored as a `String` reads back as `failures = 0`, so the ladder drops from the 30-minute step to the start.
  - On the parent commit both threw `ClassCastException`, so `verify` answered `Unavailable` every time (fail closed,
    but a lock-out-forever for the parent).
- **Reachability:** none for a child with the remote. `parental_attempts` is app-private. It is excluded from cloud
  backup, device transfer and full backup (`backup_rules.xml`, `data_extraction_rules.xml`, `allowBackup="false"`). The
  app's `.thebox` restore does not touch it. Writing a wrong-typed value needs root, or `run-as` on a debuggable build.
  Anyone with that access can delete the file anyway. So this is a mismatch between the stated guarantee and the code,
  not an exploit.
- **Fix (pick one):**
  - **Code (preferred, about 3 lines):** on a `ClassCastException`, fall back to the conservative value, not the
    neutral one. Mistyped `failures` becomes `12` (the top step). Mistyped `wait_ms` becomes `WAIT_MAX_MS`, then
    `resolve` re-anchors it because the anchor may be unusable. With these values a damaged file can only make the wait
    longer, never shorter, and still never locks for good.
  - **Doc only:** reword 1899 to "a missing or mistyped key falls back to its default (a damaged file never throws; a
    mistyped counter or wait can reset the lockout, which needs root to cause)".

## Later

### L1. "Served twice" is more likely than the doc says

- **Where:** the doc at `docs/design/m5-setup-settings.md:1898` and `README.md` say a wait is served twice only "if it
  ran out while the app was not running". The timer is at `ParentalPinStoreImpl.kt:237-246`.
- **What else causes it:**
  - The timer runs on `@ApplicationScope` with the Default dispatcher, so `delay` counts `System.nanoTime`
    (`CLOCK_MONOTONIC`), which stops in deep sleep.
  - A cached process can be frozen (Android 12+ freezer, Google TV) or killed.
- **Sequence:**
  1. A child reaches step 12 at 20:00.
  2. The parent presses Home, and the TV goes to standby (deep sleep) at 20:05.
  3. Overnight the device restarts: power cut, an OTA, or a Fire TV stick powered from the TV's USB port. Its wait ran
     out during the sleep, but neither the timer nor a read cleared it.
  4. Next morning the parent opens a locked group and waits 30:00 again.
- **Impact:** the wait is still bounded at 30 minutes once per reboot, and it lands on the parent, not the child.
- **Fix:**
  - At minimum, widen the doc sentence ("…while the app was not running, was frozen or the device was asleep…").
  - Optional: an `AlarmManager.ELAPSED_REALTIME` (non-wakeup) alarm at the wait's end. It fires on the next wake even
    if the process died, and goes to a small receiver that calls `settleLockout`. This closes everything except a
    power-off before the wait ran out, which is the accepted cost.

### L2. The debug clock offset can make a wait endless or restart it (debug builds only)

- **Where:** `ParentalPinStoreImpl.kt:103` and `ParentalSessionImpl.kt:96`. Both add `DebugOverrides.clockOffsetMs`
  to `elapsedRealtime`.
- **Release builds:** confirmed safe. The only writer is `app/src/debug/.../GuideDebugReceiver.kt:190`. That activity is
  in the debug manifest only and guarded by `DUMP`. In release `clockOffsetMs` stays `0`.
- **Sequence 1, in debug (reproduced with a probe):**
  1. Set a negative offset larger than uptime, for example `clock_offset_ms=-10000000`.
  2. The elapsed reading is now negative, so the anchor is negative.
  3. `write` stores it, and `read` maps any negative value to `null` (`takeIf { it >= 0 }`).
  4. Each read then sees "no anchor", re-anchors at the negative value and writes it again.
  5. The wait never ends while the offset stays.
- **Sequence 2, in debug:** resetting `clock_offset_ms=0` during a lockout that started under a positive offset makes
  elapsed go backwards. That counts as a reboot, so the wait restarts in full. Today's `ParentalTest` does not hit this
  (its lockout is served before the reset), but a future harness test could.
- **Fix:**
  - Coerce the clock to be non-negative (`(elapsedRealtime() + offset).coerceAtLeast(0)`), or let the offset only move
    forward for the elapsed clock.
  - Note sequence 2 in the §6.5 harness doc.

### L3. When the wall clock moves back mid-countdown, the pad stays disabled for the extra time

- **Where:**
  - `core/.../guide/state/GuideStateMachineImpl.kt:1058-1066` (`tickPin` compares the wall clock with `pinLockedUntil`).
  - `ui/.../parental/PinPadView.kt:113-125`.
  - The doc sentence at `docs/design/m5-setup-settings.md:1900`.
- **Sequence:**
  1. A countdown is showing, ending at wall time T.
  2. The wall clock moves back by an hour. This happens with an NTP correction after a reboot with a wrong RTC, or a
     manual change.
  3. The store would accept the PIN at T − 1 h (elapsed time), but the pad stays disabled and shows about 60 more
     minutes.
  4. The doc's "the store's answer on the next submit wins" cannot apply, because no submit is possible.
  5. Closing and reopening the prompt does fix it: `openPin` calls `queryLockout`, which takes the store's answer.
- **Context:** this predates the commit. Moving the clock forward is harmless: the pad re-enables, and the submit
  returns `LockedOut` with a fresh end. The digits are not counted.
- **Fix:**
  - Carry `remainingMs` (or an elapsed-based end) in the prompt state, and count it down on `SystemClock.elapsedRealtime`.
  - Or re-query `lockedUntil` when the tick sees the wall clock jump back by more than a few seconds.
  - Correct the doc sentence either way.

### L4. The settle timer and the Android clock are not covered by committed tests

- **What is missing:**
  - The commit's tests never pass a `scope`, so `scheduleSettle` never runs.
  - `AndroidLockoutClock` (the `BOOT_COUNT` read and its `RuntimeException` path) is not covered.
  - `ParentalSessionImpl.refresh` → `settleLockout` is not covered.
- **What I checked with probes on a `TestScope`:**
  - The timer clears a served wait, and only after the 1 s slack.
  - A later reboot then does not restart it.
  - A timer that fires while elapsed time says the wait is still running reschedules itself: it cancels its own job and
    launches the next, and the next one clears the wait.
- **Fix:** commit those two probes (they are about 25 lines, using a `kotlinx.coroutines.test.TestScope` as the
  `scope` argument).

---

## What is sound

- **F7 is fixed.**
  - Sequence: three wrong PINs, then a reboot (boot count + 1, elapsed back to 1 s), then the wall clock set a day ahead.
  - On the parent commit, `lockedUntil` returns `null` (probe failed: "still locked").
  - On `54aefd1` the full step runs again and the right PIN is refused with `LockedOut`.
- **The wall clock never affects the decision.** `resolve` / `remainingMs` read only `elapsedMs` and `bootCount`
  (`LockoutPolicy.kt:69-97`). `wallMs` is only added to the display end (`:100`, `:108`).
- **No sequence ends a wait early.** I tried wall-clock moves, force-stops, reboots and waiting:
  - **Same boot:** `passed = elapsed − anchor` with `elapsed ≥ anchor`, so the wait is never over-credited.
  - **Boot count differs, or elapsed went backwards:** the wait re-anchors at the full step.
  - **Undetected reboot** (boot count unknown on either side, elapsed above the anchor):
    - Credit is `elapsed₂ − anchor`, which is less than `elapsed₂`.
    - `elapsed₂` is at most the real time since the wait began.
    - So the author's argument holds, including repeated undetected reboots.
- **Only a served wait is ever cleared.** The only clearing branch is `passed >= waitMs` on the same boot
  (`LockoutPolicy.kt:87-88`). Nothing a child can trigger moves elapsed realtime forward in a release build.
- **The ladder step survives everything but a correct PIN.**
  - The step lives in `failures`, which `resolve` always keeps.
  - `set()` and `clear()` reset it, but both sit behind the PIN: the Parental page and the PIN-change flow.
  - Backup and transfer exclude every domain. `BackupRestorer` does not touch `parental_attempts` or the PIN blob.
- **No path locks the parent out for good** (outside the debug offset, L2).
  - `waitMs` is clamped to 30 min on read.
  - A missing anchor re-anchors once, and the next read counts it down.
  - `Long` elapsed values cannot overflow here.
  - Repeated reboots during a wait each restart it. That is the accepted cost, at most 30 min after the last restart.
- **Concurrency.**
  - `verify`, `lockedUntil`, `settleLockout` and `set` / `clear` all take the same `Mutex`.
  - `settleJob` is only read and written under it.
  - There is at most one pending timer, and a new one cancels the old.
  - A timer that runs while `verify` holds the lock suspends on `withLock` and can be cancelled there.
  - Self-rescheduling works (probe).
  - The app scope is `SupervisorJob` and is never cancelled. A timer left on a cleared record is a no-op.
  - A correct PIN during a lockout is still refused (`ParentalPinStoreImpl.kt:167`, tested), and the digits are not
    counted as a failure.
- **"Stay unlocked: 1 hour".**
  - It uses `elapsedRealtime` (sleep included), never `uptimeMillis`. I grepped: `uptimeMillis` appears only in the key
    router and the channel editor, neither parental.
  - It relocks at whichever clock reaches an hour first. Moving the clock back cannot stretch it. Moving it forward
    relocks sooner, as before.
  - Process death and reboot clear the in-memory `unlocked` flag.
  - Standby → ON_STOP already relocks, so deep sleep cannot cause an over-eager relock.
- **Format compatibility.**
  - Key names and types are unchanged.
  - A pre-F7 record decodes. Its wait continues on the same boot, restarts after a reboot, and `locked_wall` is removed
    on the next write (tested).
- **UI after the jump back to full length.** `queryLockout` on prompt open returns the re-anchored end, and "Too many
  wrong PINs. Try again in 30:00." still reads correctly. The countdown is display only: every decision is the store's.
- **Tests.**
  - The new `LockoutPolicyTest`, `ParentalLockoutClockTest` and the 1-hour session test use the new API, so on the
    parent they fail to compile.
  - Behaviourally, the F7 scenario fails on the parent and passes on the head (my probe).
  - The 1-hour test would also fail on the parent, which used the wall clock only.
- **Docs.** §3.9.4 / §3.9.5, the README entry and the security.md status match the code, apart from the sentences named
  in S1, L1 and L3.

## What I could not verify

- **`Settings.Global.BOOT_COUNT` on real Fire OS / Google TV (F26 is still unverified).**
  - The policy is safe without it: unknown means the elapsed-backwards test only.
  - A vendor whose boot count changes without a reboot would only restart waits (harmless to security).
- **How the timer behaves on a real device:** freezer, deep sleep, Doze. The L1 analysis is from the platform's
  documented clock semantics, not observed.
- **Instrumented `ParentalTest`.** Not run, per instructions. I read it: it still works, because the offset moves both
  the elapsed clock and the session's hour.
- **`./gradlew lintAll` and a full `:app:assembleDebug`.** Not run. Only `:app:hiltJavaCompileDebug` was run.

---

## Fix pass (2026-11-11)

Lead rulings applied on top of `54aefd1`, uncommitted.

- **S1: fixed in code.** `PrefsAttemptsStore.read()` now tells a missing key (neutral default) from a damaged one
  (wrong type, or a negative counter or wait), and decodes damage to the worst case for that field:
  - `failures` becomes `LockoutPolicy.TOP_FAILURES` (12, the top step), so the counter is never reset.
  - `wait_ms` becomes `WAIT_MAX_MS` (30 min), re-anchored now.
  - A damaged `locked_elapsed` or `locked_boot` leaves no anchor, so a running wait restarts in full.

  Decoding still never throws, and a wait is still clamped to 30 min. The first resolve rewrites the record with the
  right types. The doc sentence in §3.9.4 and the KDoc were rewritten to match.

  Tests in `ParentalLockoutClockTest`:
  - `wrongTypedFailuresDecodesToTheTopStep`
  - `wrongTypedWaitDecodesToThirtyMinutesReAnchoredNow`
  - `wrongTypedAnchorRestartsTheWaitInFull`
  - `everyKeyWrongTypedAtOnceNeverThrowsAndLocksAtMostThirtyMinutes`
- **L1: doc corrected, no alarm (ruling).** §3.9.4 and the 2026-11-09 README entry now say exactly when a served wait
  is served again after a reboot: none of the three settle paths (a read, refresh, the in-process timer) ran between the
  wait's end and the reboot. That covers a dead or frozen process, and deep sleep, because `delay` does not count it.
  The typical overnight standby case is included.
- **L2: fixed.** The new `elapsedWithDebugOffset()` (`ParentalPinStoreImpl.kt`) is `elapsedRealtime + offset`,
  clamped at 0. Both `AndroidLockoutClock` and `ParentalSessionImpl.elapsed` use it, so a negative anchor cannot occur.
  Sequence 2 (lowering the offset during a lockout restarts it) is documented at the `thebox-debug://clock` entry of
  §6.5. Test: `androidClockReadsBootCountAndNeverGoesNegative`. It also covers the `BOOT_COUNT` read; the
  `RuntimeException` path is still untested.
- **L3: doc corrected, no code (ruling).** The §3.9.4 sentence now says:
  - A wall clock moved forward re-enables the pad early, and the next submit gets `LockedOut` again.
  - A wall clock moved back keeps the pad disabled until the prompt is reopened.
  - Decisions are always the store's.
  - This is known and not fixed; it predates F7.
- **L4: tests committed.**
  - `settleTimerClearsAServedWaitOnlyAfterTheSlackAndARebootThenFindsNothing`: on a `TestScope`, the wait is not
    cleared at slack − 1 ms, is cleared just after, and a later reboot finds nothing.
  - `settleTimerThatFiresEarlyReschedulesAndClearsLater`: the timer fires while elapsed time says 20 s remain,
    reschedules, and clears later.
  - `ParentalSessionTest.refreshSettlesTheStoredLockout`: refresh → `settleLockout` re-anchors a stored wait.
  - `SETTLE_SLACK_MS` is now `internal` for these tests.
