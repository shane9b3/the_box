# Time-shift buffer: code review, playback half (2026-10-19)

Scope: `core/.../playback/timeshift/*`, `player/.../timeshift/*`, the controller, classifier, `LiveTsDataSource`,
`TransportFactoryImpl`, `BoxLoadErrorHandlingPolicy`, `MediaSourceBuilder`, `Seams.kt`, `PlaybackApi.kt`,
`TheBoxApplication`, `PersistedPlaybackSettings`, and their tests. Baseline: `docs/design/m6-timeshift.md` §2–§9 and §14–§16,
plus the commit-0, WP-A, WP-B, reconciliation and WP-C records. Working tree on `main` at 70da111, uncommitted.
Read-only review: no Gradle run and no emulator run. Every claim below was checked in the code; paths are relative to
the repo root.

## Verdict

**Not ready to ship.** The architecture matches the design: one pump, the ring, a reader that blocks at the head, a
generation fence, a recorder-only ladder path, and the M2 pipeline untouched when Off. The threading is mostly sound.
Three defects block the release:

- **B1** is the field finding. A pause of about 20 s turns into a false `SLOT_LOST`.
- **B2.** The A-C2 "first-open retry keeps the cycle buffered" path breaks the tune on a real ring. Single-connection
  accounts hit it on every zap where the panel still counts the old socket.
- **B3.** The PLAYHEAD pin is kept while the viewer is at live. After a disk stop this throttles or deadlocks live HD
  playback. In PAUSE_ONLY it defeats the flash-wear cap.

All three slip past the suites because the controller tests run on WP-B's fake ring and writer, and the core tests
never set a controller-style PLAYHEAD pin.

---

## Field finding: false SLOT_LOST through the reconnect cap (analysis)

**Observed.** On the emulator, against a real provider whose stream reconnects every 10–20 s on our own "no bytes"
watchdog cancels, a 20 s pause ended in SLOT_LOST ("another device is using your provider connection").

**Which reconnects feed `reconnectAllowed()`.** `LiveTsDataSource.reconnectLoop` calls the hook before **every attempt
of every burst, whatever the cause** (`player/.../datasource/LiveTsDataSource.kt:440-445`). It is reached from:

| Cause | Where it enters `reconnectLoop` | Kind |
|---|---|---|
| Our ByteWatchdog cancel (no bytes for 4 s, or no first byte for 4 s on a reconnect) | `checkWatchdog` `:583-596` → `cancelAll` → the read throws → `ioFailure` `:514` → `Fail.NO_BYTES` | **ours** |
| Server close (EOF) | `pump()` `:363-367` → `Fail.EOF` | server |
| Reset or read error | `pump()` `:356-361` → `ioFailure` → `READ_ERROR` | server or network |
| A sniff body cut, a first-open silent server | `onFirstConnectFailed` `:277` | mixed |
| Each failed attempt inside the burst (connect error, reconnectable status 403/458/503/509/408/429/5xx, NO_BYTES, TINY with 0 bytes) | the loop `:472-490` → the next iteration asks again | mixed |

Three problems in the writer's counter (`core/.../timeshift/RingWriterImpl.kt:143-153`) combine:

1. **It records before it checks "behind".** Lines 145-146 stamp every call, including those at the head, and only
   then test `behind` (147-148). At live, a reconnect every 10–20 s fills the 3-slot array (`:58`) with timestamps from
   the last 60 s. The first reconnect after a pause then counts n = 3 > `PAUSED_RECONNECTS_MAX` (2) and refuses. That is
   exactly a 20 s pause → SLOT_LOST.
2. **It counts attempts, not losses, and counts every cause.** Watchdog cancels (our side), network connect errors and
   successful reconnects that go on to deliver bytes all count. Three attempts in one burst also trip it alone.
3. **The ring path makes the watchdog fire during pauses.** In M2 a paused player's loader parks, so `blockedSinceMs`
   is 0 and the watchdog never runs during a pause. With the ring, the pump is always inside `read()` (§4.2 calls this a
   feature), so a provider with periodic gaps of 4 s or more now reconnects all through the pause. Those reconnects are
   exactly what the cap counts.

Smaller misclassification: the EOF branch (`LiveTsDataSource.kt:363-367`) and the sniff's end of stream (`:319-337`,
which gives `TINY` with 0 bytes, "limit-like") do not check `cancelledByWatchdog`. If OkHttp ends a cancelled body with
−1 instead of throwing, our own cancel reads as a server close or a refusal.

**Downstream impact of one false SLOT_LOST:**
- The connection is released (`TimeshiftSessionImpl.kt:108`) and the wrong toast or header shows (`PlaybackControllerImpl.kt:2605-2618`).
- Resume restarts the writer with a `GAP` segment (`:2439-2449`). On a catch-up channel the A-C5 rule then sends the
  viewer to the provider archive at the gap (`:2820-2830`); on other channels the gap is skipped. Everything from the
  stop to the resume is lost.

The fix is B1.

---

## Blockers

### B1. The reconnect cap counts our own watchdog cancels, successful reconnects and at-head history (field finding)

Where: `RingWriterImpl.kt:143-153` (the counter); `LiveTsDataSource.kt:440-445` (the hook takes no cause);
`Seams.kt` `WriterHooks.reconnectAllowed: () -> Boolean`.

**Fix.** Count **server-initiated losses and refusals only**, once per loss, and only while behind:

1. **Give the hook a cause.** Change it to `reconnectAllowed: ((cause: ReconnectCause, firstOfLoss: Boolean) -> Boolean)?`
   (contract in `TimeshiftContracts.kt`; `RingWriter.reconnectAllowed(cause, firstOfLoss)`). In `reconnectLoop`, map the
   pending `Failure` `f`:

   | `f` | Cause | Counted |
   |---|---|---|
   | `NO_BYTES` (the watchdog: `cancelledByWatchdog`, or a reconnect first-byte timeout) | `WATCHDOG` | never |
   | `EOF` with `!cancelledByWatchdog` | `SERVER_CLOSED` | yes |
   | `READ_ERROR` with `net == RESET` | `RESET` | yes |
   | `STATUS` 403/458/509, and `TINY` with `bytes == 0` (closed before the first byte) | `REFUSED` | yes, every attempt |
   | `NETWORK` / `READ_ERROR` timeouts, 5xx/408/429 | `NETWORK` | never |

   503 is in M2's `LIMIT_STATUSES` (`:635`), but the owner's list (403/458/509) leaves it out. Decide it explicitly; it
   is more often overload than a slot.

   `firstOfLoss = (attempt == 0)` on the iteration that opened the burst.
2. **Rewrite the writer's counter:**
   ```kotlin
   override fun reconnectAllowed(cause: ReconnectCause, firstOfLoss: Boolean): Boolean {
       val behind = paused || impl?.anyReaderAtHead() == false
       if (!behind) { clearHistory(); return true }              // at the head: no history carried into a pause
       val counted = cause == REFUSED || (firstOfLoss && (cause == SERVER_CLOSED || cause == RESET))
       if (!counted) return true                                  // our watchdog, network errors: never a slot fight
       record(clock.nowMs())
       if (countWithin(PAUSED_RECONNECT_WINDOW_MS) > PAUSED_RECONNECTS_MAX) { slotLostPending = true; return false }
       return true
   }
   ```
   Also clear the history in `onPause` and `onResume`; restart already clears it (`:213`). A kick-oldest ping-pong is
   still caught, because each kick is a server close of a connection we had re-taken.
3. **Close the watchdog-as-EOF holes.** In `LiveTsDataSource.pump()` `:363`, use
   `if (cancelledByWatchdog) Failure(Fail.NO_BYTES, net = TIMEOUT) else Failure(Fail.EOF)`. In `sniff()`, an `eos` after
   `cancelledByWatchdog` is `NO_BYTES`, not `TINY`.
4. **Residual risk, to note in the design (§4.4).** A panel that "kicks" by going silent without closing the socket
   looks like a watchdog cancel and is no longer counted. Add a fake-panel `kick_mode=silent` and decide on the device
   (I-TS-25) whether a silence right after a *successful* reconnect (a connection that lived under 5 s) should count.

Tests to add are listed in §7.

### B2. The A-C2 pre-first-byte recreate leaves the ring generation ended: the writer never records and the tune hangs

**Where:**
- `PlaybackControllerImpl.kt:727-729` closes the session with `keepGrant`.
- `:834` starts a new session on the **same grant**.
- `TimeshiftSessionFactoryImpl.kt:165-186` (`start`) neither resets nor rejoins.

**Sequence:**
1. `ts.close()` runs `writer.stop(SESSION_END)` and then `writer.close()` (`TimeshiftSessionImpl.kt:250-264`).
2. The old pump exits through `takeCommands()` and calls `ring.stopRecording(SESSION_END)` on the **shared generation**
   (`RingWriterImpl.kt:190-193`). That sets `ended = true` (`TsRingImpl.kt:259-262`). Nothing joins the old pump, so this
   can also land after the new pump has started.
3. The new writer's first `claim` returns `STOPPED` (`TsRingImpl.kt:164`). Only `startSegment` clears `ended`, and a
   fresh writer never calls it. The pump has just opened a provider connection; it now closes it (`RingWriterImpl.kt:276-280`)
   and idles forever.
4. The new reader's `open()` throws `RingEndException(SESSION_END)` (`RingReaderImpl.kt:94`). The controller drops it
   as stale (`PlaybackControllerImpl.kt:1268-1272`).
5. The cycle sits in Connecting until the switch timeout, then shows the card.

**Who hits it:** every first-open `RetrySame`/`ReResolve`/UA fallback (`:2669-2671`) and `selectServer` before the
first byte (`:621`). A 403/509 at zap time on a single-connection account (the panel still counts the old socket) is the
common trigger, and M2 recovers from it with one retry. `ControllerTimeshiftTest.aFirstOpenRetrySameKeepsTheCycleBuffered`
passes only because `FakeRingWriter`/`FakeTsRing` model neither `ended` nor the pump exit.

**Fix (preferred).** Before the first byte, do not recreate the session: restart its writer. In `executeBuffered`
`:2670` and `selectServer` `:621`, run the normal reader launch path, but after the resolve call
`c.ts!!.restartWriter(resolved, SegmentReason.WRITER_RESTART)` instead of close + start:
- the pump's restart path terminates, closes and reopens in order;
- the empty first segment is reused (`RingIndexImpl.beginSegment` `:278-280`);
- `onFirstBytes` still fires once.

Drop `Plan.recreateTimeshift` and `keepGrant`.

**Alternative.** Give the factory `restart(grant)`, which on IO joins the old pump (`awaitExit`) and calls
`ring.reset()` before `start`. That adds up to `PUMP_JOIN_MAX_MS` to a retry.

**Also.** Fence `RingWriterImpl`'s exit path (`:192`, and `close()` `:135`) on "this writer is the ring's current
writer", not only on the generation. Today two writers can share a generation.

### B3. The PLAYHEAD pin follows the playhead while at live: RAM-only stalls live, and PAUSE_ONLY writes everything

**Where:**
- `PlaybackControllerImpl.kt:2844-2845`: the 1 Hz poll pins the floor RAP of the playhead whenever playing, including
  at live.
- §4.5 says that in PAUSE_ONLY at live the PLAYHEAD pin is **not** set.

**RAM-only after any disk stop** (`DISK_ERROR`, `STORAGE_EVICTED`, `DISK_TOO_SLOW`; `TsRingImpl.kt:254-258`):
- A block can be recycled only once `tail` passes it (`:178-181`, `bound = recycleEnd`), and `tail` may not pass the
  pin.
- The pin sits at least one GOP plus the live latency behind the head. The pool is 64 × 65,424 B (about 4.2 MB, 2.1 MB
  on low-RAM devices), which holds about 4 s at 1 MB/s.
- So `claim` answers FULL, and the pump waits (`RingWriterImpl.kt:258-271`) instead of reading.
- If the GOP in bytes is larger than the pool (a 1080p channel at 8 Mbit/s with a 5 s GOP is 5 MB), the next RAP can
  never be written before the pin moves, and the pin cannot move until the playhead passes that RAP.

Result: live stalls. After `PIN_STALL_MS` the pump drops upstream bytes (a GAP), the stall detectors re-prepare at the
same pin, and the ladder escalates into L27 decoder downgrades. §4.6 promises "at live the reader continues from RAM".

**PAUSE_ONLY at live** (the "Pause only" value and the 12 GB/day cap):
- `updatePersistence` (`TsRingImpl.kt:531-544`) turns persistence on whenever a pin is in the older half of the RAM
  window, about 2 s at 1 MB/s.
- The PLAYHEAD pin is always further back than that, so the file is written continuously at live.
- The daily cap and "Pause only" protect nothing. `FlushTest` ("PAUSE_ONLY writes 0 bytes at live") does not set a pin.

**Fix:**
- In `pollTimeshift`, call `setPlayheadPin` only when `c.paused || c.localShift`, and `ring.clearPin(PLAYHEAD)` when the
  viewer reaches live (where `localShift` is cleared, `:2846`, and in `jumpToLiveLocal` once the new reader is at the head).
- `pauseBuffered` (`:2430-2437`) and `launchReader` (`:2423-2428`) keep setting it.
- Add a controller-level test on the **real** `TsRingImpl` (RAM-only and PAUSE_ONLY, a 5 s GOP at 1 MB/s, playing at
  live for 60 s). Assert no FULL wait, 0 persisted bytes in PAUSE_ONLY, and no gap.

---

## Should-fix

### S1. "At the head" for ladder routing is the **loader** cursor, not the playhead

**Where:**
- `readerAtHead(c)` (`PlaybackControllerImpl.kt:2211`) and the `localShift` reset (`:2846`) use
  `RingReaderImpl.atHead` (`RingReaderImpl.kt:62`), which tests whether the loader cursor is within one block of the
  head.
- After a short rewind (up to about 20–50 s, the `AdaptiveLoadControl` max buffer), the loader reaches the head within
  seconds while the viewer is still that far behind.

**Consequences:**
- A writer `Fail` calls `fail()` at once instead of stashing it (`:2643`), and `endSession` discards up to the player's
  buffer of unseen content.
- A deferred cycle-scoped decision runs early (`:2848`).
- A limit-like refusal goes to the ladder, which reconnects and fights the other device (`:2598`).
- `localShift` turns false, so A-C5 treats the viewer as not time-shifted (`:2824`).

Point (3) of the brief, "the recorder-only path never kills a reader that still has buffered content", is therefore
violated whenever the viewer is less than one player buffer behind.

**Fix:**
- Once `c.firstByteSeen`, always stash a writer `Fail` and stop with `UPSTREAM_FAILED`. Media3 surfaces the reader's
  `RingEndException(UPSTREAM_FAILED)` only after the buffered media has played out (§5.3, M2 §3.3.4), so the card comes
  at the right time, at live too, with no "head" test needed.
- For SLOT_LOST, the deferral and `localShift`, define "behind" as
  `newestPtsMono − playheadMono > liveLandingMs() + GAP_MIN_MS`, not by the loader cursor.
- Keep the loader cursor for the §5.2 telemetry gating, where it is the right notion.

### S2. A segment storm escalates through L25/L27 into decoder downgrades instead of a writer `ReResolve`

**Where:**
- `classifyWriter` maps a storm to `PcrDiscontinuity` (`FailureClassifier.kt` `classifyWriter`). The ladder answers it
  with **L25 `Reprepare`** (`core/.../errors/ErrorLadderImpl.kt:451-453`); once L25 is exhausted, `isL27Signal` counts it
  (`:270`), giving DisableTunneling (persisted for the channel, `PlaybackControllerImpl.kt:1645-1648`), compat raise and
  software video.
- `executeBuffered` sends `Reprepare` to the reader path (`:2693`), so the writer is never re-resolved. R2's note
  ("exactly what `PcrDiscontinuity` gets from the ladder") is wrong.

**Why it matters for the field finding.** The same provider reconnects every 10–20 s. If its reconnects jump the PCR
(fake panel `reconnect_mode=jump`), each one is a `PCR_JUMP` segment, and 6 in 60 s is a storm.

**Fix:**
- In `executeBuffered`, when `fromWriter && signal is FailureSignal.PcrDiscontinuity`, restart the writer on
  `writerJob` (`restartWriterScoped(..., reason = WRITER_RESTART)` with a fresh resolve), and record it in the pcr window.
- Only a repeat within 5 min goes to the ladder, and then only as cycle-scoped (unbuffered relaunch). Never let a writer
  signal reach L27.
- Optionally, do not count `PCR_JUMP` segments that directly follow a watchdog reconnect towards the storm.

### S3. Discard races prepare; a current reader's `RingEnd(SESSION_END)` is then ignored and the picture freezes for good

**Where:** `TimeshiftSessionFactoryImpl.discardNow` (`:205-213`), run on `box-ts-discard` after stop, lifecycle or
`TRIM_MEMORY_COMPLETE`. It checks `open.isEmpty()`, but a cycle that holds a **grant** from `prepare` and has not yet
called `start` is not in `open`.

**Sequence:**
1. `prepare` resets ring R and returns its grant.
2. `discardNow` closes R and deletes the file.
3. `start` builds on the closed ring. Readers throw `RingEnd(SESSION_END)`, which the controller drops as stale
   (`PlaybackControllerImpl.kt:1268-1272`).
4. Before first media this ends in the switch-timeout card. After first media no detector runs on an IDLE player, so
   the picture freezes indefinitely.

Home → back quickly, or a trim while tuning, opens the window.

**Fix:**
- Make `discardNow`, `prepare` and `start` mutually exclusive: take `lock` in `discardNow`, and count granted-not-started
  grants in `open`, or have the grant pin the ring.
- In the controller, treat `RingEnd(SESSION_END)` as stale only when `ringOpen.generation != ring.generation` or the
  ring is not the current session's. Otherwise relaunch with `startCycle(specForRestart())`.

### S4. One storage failure makes the ring RAM-only for the rest of the process

**Where:**
- `TsRingImpl.reset()` keeps `storageBroken`: `ramOnly = storageBroken` (`:306`), and the header is skipped (`:311`).
- `ringFor` reuses the existing ring whenever the sizes match (`TimeshiftSessionFactoryImpl.kt:139-142`).
- After `STORAGE_EVICTED` the flusher has already closed the fd (`TsRingImpl.kt:461-464`).

**Effect.** After an eviction, ENOSPC, EIO or a slow-disk stop, every later tune in the process is RAM-only. That means
about 4 s of rewind and B3's stall, while `snapshot` reports `available = true`, because `stopReason` is per session
(`TimeshiftSessionImpl.kt:185`).

**Fix:**
- Expose `TsRingImpl.storageBroken` (or `TsRing.storageUsable`).
- In `ringFor`, rebuild (`closeRingLocked` + `openStorage`) when it is broken or `isUnlinked()`.
- If the rebuild fails, answer `Off(STORAGE_ERROR)`.

### S5. A pause before the session exists takes M2's release job, but the recorder keeps running

**Where:**
- A pause during resolve runs `livePauseJob` (`PlaybackControllerImpl.kt:1820-1835`; WP-B deviation 10).
- `startTimeshift` then runs anyway (`:834`) with the writer **not paused**: `pauseBuffered` is never called.
- Ten seconds later the job ends the reader session and shows `Stopped(LIVE_PAUSE_TIMEOUT)`, but `c.ts` stays open.

**Effect.** The recorder holds the provider connection and writes flash with no hold limit and no FULL stop, until a
resume or the screensaver's lifecycle stop.

**Fix.** In `startTimeshift`, when `c.paused`, cancel `livePauseJob` and call `pauseBuffered(c)`. If the job already
fired, it must call `closeTimeshift(c, discardFile = false)`.

### S6. The daily counter allocates on every block write (§15: zero per block)

**Where:** `AndroidRingStorage.pwrite` → `onWritten` (`:135`) → `TimeshiftStorageImpl.addWritten` → `rollDay()` →
`today()` (`:167`). Each call builds an `Instant`, a `ZonedDateTime` and a `LocalDate`, and calls
`ZoneId.systemDefault()`, which clones the default `TimeZone` on Android. It also takes a monitor.

That runs on `box-ts-flush` about 15 times a second at 1 MB/s. `RingAllocationTest` misses it because it runs over
`MemoryRingStorage`.

**Fix:**
- Accumulate in a plain `long` on the flusher thread, and call `addWritten` only every `DAILY_COUNTER_FLUSH_BYTES` (the
  ring's `persistListener` already does exactly this; R4 chose not to use it).
- Or cache `nextMidnightWallMs` and compare it with a `long`, recomputing it only at rollover.

### S7. The first reader of a cycle starts at `tail` (not a RAP), so the origin can be wrong by up to one GOP

**Where:**
- `PlaybackControllerImpl.kt:839-840` opens the reader at `ring.tail`, which is 0 on a fresh generation.
- `resolveOrigin` (`:2361-2369`) takes the first RAP at or after that offset as position 0.

**Why it is off.** The `TimestampAdjuster(0)` anchors on the first PES timestamp the extractor meets. On a mid-GOP
start that is audio or a non-RAP video PES, up to a GOP earlier than the first RAP.

**Effect:**
- `playheadMono` is overestimated by up to one GOP (1–10 s).
- So are the PAUSE pin, `latencyAtTune` (A-C3), BEHIND and the reader-only re-prepare position (a reapply jumps forward).

**Fix.** Either open the first reader at the first RAP (wait in `RingDataSource.open` until `oldestRapEntry` exists),
or have the indexer record the first PTS of any PES at or after the reader's start offset and use that as the origin.

### S8. The zap path can wait up to 1 s for the previous pump

**Where:**
- `decideTimeshift` awaits `prepare` after the resolve (`PlaybackControllerImpl.kt:2243`).
- `prepare` holds `lock` while `ringFor` joins the previous pump for up to `PUMP_JOIN_MAX_MS`
  (`TimeshiftSessionFactoryImpl.kt:134-136`).

A pump in `LiveTsDataSource`'s reconnect sleep (up to 1 s, `:449`; `terminate()` does not interrupt it) or in
`broker.acquire` holds the zap for that long. That is a direct hit on the hard gate of 5 % or less (I-TS-22).

**Fix:**
- Interrupt the pump thread in `RingWriterImpl.close()`/`restart()` after `terminate()`. `LiveTsDataSource.sleep` already
  maps an interrupt.
- Lower the join on the zap path (for example 100 ms): the generation fence already makes `reset()` safe while an old
  pump is alive.
- Fix L3 and L4 so the fence is complete.

### S9. Find out why the provider's stream trips the 4 s watchdog every 10–20 s

B1 removes the false SLOT_LOST, but on this provider every watchdog reconnect is still a splice in the ring. Each splice
is a possible `PCR_JUMP` boundary (S2), a `GAP_SKIPPED` toast for a viewer behind live, and churn on the panel.

- Log in the writer's event log the gap that triggered each cancel (`now − lastByteAtMs`) and the bitrate.
- If the provider is bursty (a restreamer sending one HLS segment at a time, 6–10 s apart), make the writer's
  `noBytesTimeoutMs` adaptive. For example, while no reader is at the head (paused or behind), use
  `max(NO_BYTES_TIMEOUT_MS, 1.5 × the largest inter-burst gap seen with flowing bytes)`, capped at 12 s. At live keep M2's
  4 s. Nothing is starving while the viewer is paused.

---

## Later

- **L1.** `TsRingImpl.anyReaderAtHead` (`:335-343`) reads the plain `readers[]` array from the pump thread without the
  lock. Make the slots an `AtomicReferenceArray`, or keep a volatile `atHeadCount`.
- **L2.** `TsRingImpl.advancePin` (`:400-406`) checks `readers[id] === reader` and then sets the pin without the lock.
  A reader closed concurrently whose slot is reused by a new reader can overwrite the new reader's pin. Take the lock,
  or CAS on a per-slot owner stamp.
- **L3.** The old pump's `finally { impl?.segmentListener = null }` (`RingWriterImpl.kt:311`) clears the **new**
  writer's listener when the old pump outlives the join (S8) or in B2's overlap. Clear it only if it is still its own
  lambda.
- **L4.** The index sink is not generation-fenced. A stale pump that finishes `indexer.scan` after `reset()` writes
  entries and `onClock` samples (which can corrupt `monoOffset`) into the new generation's index. Pass the generation
  to `beginScan` and drop sink calls whose generation does not match.
- **L5.** `TsRingImpl.readFile` (`:419-425`) loops forever if `pread` returns 0 (`AndroidRingStorage.pread` returns a
  partial count on EOF, `:144-150`). `flushStep`'s write loop (`:477-481`) has the same problem when `pwrite` returns 0.
  Treat `n <= 0` as a `RingIoException`/`DISK_ERROR`.
- **L6.** `AndroidRingStorage.allocate` fallocates `[0, fileLength)` on every extent (`:115`). Use
  `(allocated, fileLength − allocated)`.
- **L7.** `ensureAllocated` uses `FREE_SPACE_RESERVE_MIN / 2` (`TsRingImpl.kt:506`). §3.1 says `reserve / 2` with
  `reserve = max(1 GiB, total × 0.10)`.
- **L8.** An ENOSPC on the first extent is reported as `STORAGE_ERROR` (`TimeshiftSessionFactoryImpl.kt:125-128`). §8.2
  says `NO_SPACE`.
- **L9.** There is no capacity re-size after `RATE_PROBE_MS` (§8.2). A 2 MB/s channel on Auto gets about half the window.
  At least surface it in the setting row.
- **L10.** The daily cap's PAUSE_ONLY switch happens only at `prepare`. §8.4 says "at the next moment nothing needs
  persisting".
- **L11.** `RingWriterImpl` fires `onFirstBytes` once per writer (R13). After a writer restart, `c.stopNotice` is never
  reset (`PlaybackControllerImpl.kt:2547-2550`), so a second SLOT_LOST in the cycle shows no toast, and the Reconnected
  notice after a restart at the head never shows. Add `onRestarted()` or reuse `onSegment(WRITER_RESTART|GAP)`.
- **L12.** `RingReaderImpl` allocates a 64 KiB direct staging buffer per reader, so one per seek (`:166`). Keep one in
  the session and hand it to each reader.
- **L13.** `launchPrepareForOff` (`PlaybackControllerImpl.kt:2214-2225`) runs `discardNow()` IO (a pump join and a
  `File.exists`) on **every** Off zap. Do it once per Off transition.
- **L14.** Any `discontinuity_indicator` on the PCR PID is treated as a jump (`TsIndexerImpl` `onPcr`). An encoder that
  sets it spuriously creates segments, and storms. Require a step greater than 100 ms as well.
- **L15.** With the B1 rule, a server that drops us after `PIN_STALL_MS` of TCP backpressure (ring full while behind)
  counts as a server close. Exclude a close that follows a FULL wait of 5 s or more.
- **L16.** `runReaderSession` returns silently when `c.resolved == null` (`:2316`). Fall back to a full relaunch.
- **L17.** Not a time-shift item, but the same diff changes the M2 path: the L33r/L33t stuck-audio work, the
  switch-window extension and the tunnelingOff memory. "Off is byte-identical to M2" holds for the time-shift additions
  only; the release notes should say so.

---

## Point by point

### (1) Threading and the core invariants

| Item | State | Notes |
|---|---|---|
| Pump owns the upstream | OK | terminate from the caller, reopen after the old read returns (`RingWriterImpl.kt:82-106, 206-223`) |
| Flusher writes outside the lock | OK | `pwrite` from the direct view (`TsRingImpl.kt:466-481`) |
| Main thread reads the index | OK | through the seqlock entry forms (R1) |
| Pins and the tail invariant | OK in the ring | broken by the controller's at-live pin (B3) |
| Wrap and block recycling once flushed, with stamp → `pread` fallback | OK | `BlockPool`, `RingReaderImpl.kt:149-173` |
| Blocking at the head with cancellation | OK | interrupt plus flag, 250 ms slices (`RingReaderImpl.kt:68, 112-127`); Media3 clears the interrupt flag after each load |
| EOF rules | OK | the reader never returns −1; `LiveTsUpstream` turns −1 into an `IOException` |
| PSI injection | OK | per segment at open (`RingReaderImpl.kt:53-58, 70-76`) |
| Boundary hand-off | OK | `onRingBoundary`, `:2820-2837` |
| Reconnect cap | B1 | |
| Hold, full and stopped transitions | OK in the writer | S5 for a pause before the session |
| Daily counter | S6 | |
| Deleted-file check | OK as a check | S4 for what follows it |
| Stop/close order | OK | `writer.stop` → `terminate` (cancel, then `forceRelease`) → `endSession` → `writer.close` (`TimeshiftSessionImpl.kt:246-267`); `closeTimeshift` runs before the next `launchSession` in `startCycle` |
| Off path | OK for the time-shift additions | L13, L17 |

### (3) Ladder integration

- **Writer failures with `writerTuneId`.** OK (`FailureClassifier.live`, `onWriterFailure`).
- **Stash, decided once.** OK, but too narrow (S1).
- **First-open retry.** Broken (B2).
- **`launchSession` call sites:**
  - OK: `startCycle`, the ladder `restart` (reader-only conversion `:733`), `reapplySettings`, `setVideoDecoderOverride`,
    seeks, boundaries, `runDeferred`.
  - Broken: `selectServer` before the first byte (B2).
- **Writer-originated reader-scoped decisions.** These leave the writer idle after `onFailure`. Only `PcrDiscontinuity`
  reaches one today (S2).

### (4) Allocation and CPU

- **Pump, indexer, ring and reader.** Allocation-free per packet and per block, as `RingAllocationTest` shows.
- **Daily counter on the storage side.** Allocates per block (S6).
- **Pool size.** The pool is 4 MiB once per ring, plus one quarantined 64 KiB block per zap (fine under a 192 MB heap).
  A ring rebuild holds 8 MiB for a moment.
- **Locks.** No lock is held during I/O.
- **Zap storms.**
  - Pumps exit within about 1 s (a reconnect sleep or a lease wait).
  - `box-ts-discard` threads are one per discard.
  - No thread leaks, but S8 applies to the zap time.

### (5) §14 failure modes against the code

| Failure mode | State |
|---|---|
| Server close or reset, continuous PCR | OK |
| Reconnect with a PCR jump | OK (hook, `checkHold` `:385-393`) |
| Inline PCR/PTS jump, PMT change | OK (indexer; not re-derived here) |
| Connect burst | OK |
| Reconnect budget exhausted | OK, routed to the writer-scoped ladder |
| Slot kicked or refused | B1 (false positives), S1 (the "behind" test) |
| Ladder `Fail` while behind | S1 |
| Ring full while paused or playing | OK in the ring; B3 when RAM-only |
| Disk full, EIO, eviction, slow disk | B3 and S4 |
| Wall-clock step | OK |
| Old pump or old reader after a zap | OK; L3 and L4 are edges |
| Screensaver | OK (keep-awake) |
| Process death | OK (sweep) |
| Segment storm | S2 |

### (6) Storage

| Item | State |
|---|---|
| `posix_fallocate` with the sparse fallback and ENOSPC | OK (L6, L8) |
| `pwrite`/`pread` partial transfers | loop correctly except on 0 (L5) |
| `st_nlink` check | OK, every second, with a re-stat fallback (S4 for what follows) |
| StatFs reserve | L7 |
| The sweep spares this process's file, and `prepare` awaits it | OK |
| Midnight reset | correct; S6 for its cost |

### (7) Tests: what the suites cannot catch, and the missing cases

**What they cannot catch.**
- `ControllerTimeshiftTest` runs on `FakeTsRing` and `FakeRingWriter`. No controller test drives the real
  `TsRingImpl`/`RingWriterImpl`, so B2 and B3 are invisible.
- `RingWriterTest` calls `reconnectAllowed()` without a cause and never mixes at-head and paused calls.

**Add:**

1. A `RingWriterTest` for each of these:
   - reconnects at the head every 15 s for 60 s, then a pause and one reconnect: no SLOT_LOST;
   - paused for 5 min with a WATCHDOG reconnect every 10–20 s: no SLOT_LOST;
   - three SERVER_CLOSED losses within 60 s while paused: SLOT_LOST;
   - one burst of three attempts with NETWORK causes: no SLOT_LOST;
   - a REFUSED (509) on each attempt: SLOT_LOST on the third.
2. `LiveTsWriterHooksTest`: the watchdog cancel arrives as EOF (−1 after cancel) and is reported as WATCHDOG; a
   sniff-time cancel is not TINY.
3. A controller test over the real ring and writer (a `TimeshiftSessionFactoryImpl` over `FileRingStorage` in a temp
   directory): first-open 403 → RetrySame → bytes are recorded and the reader plays (B2).
4. RAM-only and PAUSE_ONLY at live with a 5 s GOP at 1 MB/s for 60 s: no FULL wait, no gap, 0 persisted bytes in
   PAUSE_ONLY (B3).
5. Behind live by less than the player's buffer, a writer `Fail` is stashed and the card shows only after the buffer has
   played out (S1).
6. A segment storm from the writer reaches neither L25 nor L27 (S2).
7. A discard during prepare, then start (S3). Prepare after `STORAGE_EVICTED` rebuilds the storage (S4).
8. A pause during resolve, then the session starts: the writer is paused and the release job does not leave a recorder
   running (S5).
9. `TimeshiftStorageImpl.addWritten` allocates 0 bytes per call in steady state (S6).
10. The first-session origin on a stream that starts mid-GOP, with audio first (S7).
11. Zap time with a pump in a reconnect sleep (S8; I-TS-22 on the device).
12. Device: `kick_oldest` with `kick_mode=silent` (B1's residual risk).

### (8) CLAUDE.md compliance

| Rule | State |
|---|---|
| One ExoPlayer | OK: the reader re-prepares on the same player with no `stop()` (`:2342-2344`) |
| Never send `Range` | OK: the writer uses `LiveTsDataSource` (`resetForOpen` filters `Range`); the reader opens no network |
| Broker for every connection | OK: the writer leases through `LiveTsDataSource.acquireLease` under `writerTuneId` |
| Redacted logs | OK: every new `BoxLog`/event-log line holds only tune ids, enum names, offsets, sizes and exception class names, with no URL, host, header or query (checked: `TimeshiftSessionImpl`, `RingDataSource`, `TimeshiftStorageImpl`, `TimeshiftSessionFactoryImpl`, `AndroidRingStorage`, the controller's new lines) |
| Errors carry a reason and a next step | The notices map to the §13 strings in `PlayerText` (WP-C). B1 makes one of them (SAVING_STOPPED_CONNECTION) state a wrong reason |
| No `graphicsLayer` or blur near the surface | Not touched by these files |

---

## Fix pass — playback (2026-10-19)

This pass covers B1–B3, S1–S9, the controller side of UX-B2, the UX-S1 contract and the controller part of UX-S7. Each claim above was checked in the code before it was fixed. Nothing is committed and no emulator was used.

**Build:** `./gradlew :core:test --tests 'app.thebox.core.playback.timeshift.*' :player:testDebugUnitTest :app:assembleDebug` is **green**.
- Time-shift tests in `:core`: 90 (84 before).
- `:player`: 462 (439 before).
- Nothing skipped.

The design's "Implementation record — fix pass, playback half" lists the contract changes.

### Fixed

| Item | Fix | Tests |
|---|---|---|
| **B1** | `ReconnectCause` and `firstOfLoss` reach `RingWriter.reconnectAllowed`. `LiveTsDataSource` maps them: `NO_BYTES` → WATCHDOG, `EOF` → SERVER_CLOSED, `READ_ERROR`+RESET → RESET, 403/458/509 and TINY with 0 bytes → REFUSED, everything else (503 included) → NETWORK. The writer: at the head it clears the history and allows; behind, it counts REFUSED on every attempt and SERVER_CLOSED/RESET only on the first attempt of a loss. The history is also cleared on pause, resume and restart. A watchdog-cancelled body that ends with −1 is `NO_BYTES` in `pump()` and in `sniff()` (no longer EOF or TINY). §4.4 notes the silent-kick residual risk | `RingWriterTest`: 6 new (the field finding: 5 at-head reconnects then a pause; 5 min of watchdog reconnects; three losses; one burst; REFUSED ×3; pause/resume clear). `LiveTsWriterHooksTest`: causes for EOF, 509 and 503; a watchdog cancel; a watchdog cancel ending with −1; a sniff-time cancel is not TINY (the last two fail without the fix). `ControllerTimeshiftTest.theHookPassesTheCauseToTheWriter` |
| **B2** | Before the first byte, writer-scoped decisions and `selectServer` call `restartWriterScoped`, which restarts the one writer in place. `Plan.recreateTimeshift` and `keepGrant` are removed. Before first media the state is Connecting(CONNECTING), and the writer's first bytes move it on. "Also": the writer marks itself `TsRingImpl.owner`, and its exit ends the generation only while it is the owner | `ControllerRealRingTest.aFirstOpen403ThenRetrySameRecordsAndTheReaderPlays` (real ring, writer, indexer and pump: same generation, `stop` null, a reader reads). The fake-ring test is rewritten to the new contract |
| **B3** | `pollTimeshift` pins only while paused or time-shifted. At live it clears the PLAYHEAD pin once a reader is open | `ControllerRealRingTest`: RAM-only after `DISK_ERROR` and PAUSE_ONLY, 5 s GOP at 1 MB/s, 60 s at live. Asserts: the pump keeps pace every second, no GAP segment, 0 persisted bytes in PAUSE_ONLY. Both fail with the old pin (pump stalled at 9 s; 55.8 MB persisted). `ControllerTimeshiftTest.thePlayheadPinExistsOnlyWhilePausedOrTimeShifted` |
| **S1** | `readerAtHead` = `!paused && (!localShift || newest − playhead ≤ liveLanding + GAP_MIN_MS)`. `localShift` is cleared by the same playhead test. A writer `Fail` after the first byte is always stashed (also at live) | `aShortRewindWithTheLoaderAtTheHeadIsStillBehind` (509 → SLOT_LOST, no ladder); `aWriterFailAtLiveIsShownOnlyAfterTheBufferPlayedOut` |
| **S2** | `onWriterStorm`: the first storm is a writer restart with a fresh resolve. A repeat within `WRITER_STORM_REPEAT_MS` is cycle-scoped (deferred behind live). Neither reaches the ladder, L25 or L27. A pre-first-byte restart (empty segment reused) no longer counts towards a storm | `aSegmentStormNeverReachesTheLadder` |
| **S3** | The factory tracks outstanding grants and in-flight prepares; `discardNow`/`discardIfIdle` refuse while any exists. The new `abandon(grant, discardFile)` is called by `closeTimeshift` for a grant no session used. The controller treats `RingEnd(SESSION_END)` as stale only when the session's ring generation is not the current ring's; otherwise it relaunches the cycle | `aDiscardNeverClosesTheRingUnderAnOutstandingGrant`, `aRingClosedUnderTheCurrentCycleRelaunchesIt` |
| **S4** | `TsRingImpl.storageUsable`. `ringFor` rebuilds (closes, deletes, reopens) a ring whose storage broke or whose file is unlinked; a failed rebuild is `Off(STORAGE_ERROR)` (existing catch) | `aPrepareAfterTheFileWasEvictedRebuildsTheStorage` |
| **S5** | `startTimeshift` with `c.paused`: it cancels `livePauseJob` and calls `pauseBuffered`. The job itself calls `closeTimeshift` if it fires | `aPauseWhileResolvingPausesTheRecorderAndArmsNoReleaseJob` |
| **S6** | `addWritten` compares mono ms with a cached next wall check (once a minute) and the cached day bounds; the date is recomputed only at a day change | `TimeshiftStorageTest.theDailyCounterAllocatesNothingPerWrite` (< 1 KB over 10,000 calls) |
| **S7** | `RingOpen.firstRap`: the cycle's first reader waits (10 ms slices, cancellable, at most `FIRST_RAP_WAIT_MS`) for the generation's first RAP and opens there, matching the origin `resolveOrigin` takes | `RingDataSourceRealRingTest.theFirstReaderOfACycleOpensAtTheFirstRap` (a mid-GOP join) |
| **S8** | `LiveTsDataSource`'s production reconnect sleep waits on a monitor that `terminate()` notifies. The zap-path join is `PUMP_JOIN_ZAP_MS` (100 ms). L3 and L4 make the fence complete | `LiveTsWriterHooksTest.terminateEndsTheReconnectSleepAtOnce` (< 500 ms into a 1 s wait) |
| **S9** | The watchdog cancel logs the silence, the connection's age, the bytes, kbit/s and whether it was a reconnect (tune id and numbers only). The writer's `NO_BYTES` event detail carries `gap=… age=…`, so the 10–20 s pattern can be read from a log export. Thresholds are unchanged | — (diagnostics) |
| **UX-B2** | `PlaybackController.skipGapLocal()` (default false) skips the pending `LocalGap` at the next segment's first RAP and emits `GapSkipped`. A 5 s timer (`GAP_HANDOFF_TIMEOUT_MS`) does the same when no archive tune replaced the cycle | `aLocalGapWithNoFollowUpIsSkippedAfterTheTimeout`, `skipGapLocalSkipsAPendingHandOff` |
| **UX-S1** | `LocalBufferState.holdMs` and `cycleId` (a per-cycle counter). `windowMs` is stable for the session (`min(length, capacity ÷ ASSUMED_BYTES_PER_SEC)`); `stoppedAtMonoMs` was already there | `theLocalBufferCarriesWindowHoldAndCycle` |
| **UX-S7** | `runDeferred`, a cycle-scoped decision at the head and a storm repeat re-decide buffering (`tsDecided = false`, `tsOff = null`) instead of setting `NOT_TS`. "Rewind saves again from now" holds on TS, and HLS reports `NOT_TS` with `hls` | `aDeferredRelaunchOnATsSourceBuffersAgain`; `aCycleScopedDecisionBehindLive…` now asserts the HLS case |
| L1, L3, L4 | `anyReaderAtHead` takes the lock. The pump clears the segment listener only if it is still its own. `beginScan(arrival, generation)` fences every index sink call | covered by the suites above |

### Deferred, with reasons

- **Second fix pass (outside this pass's files):**
  - `LocalBufferInfo.windowMs`, `holdMs`, `stoppedAtMs` and `cycleId` in `core/.../full/PlayerContracts.kt`, filled from the new `LocalBufferState` fields in `LocalTimelineBackend`.
  - `ArchivePlaybackImpl` calling `skipGapLocal()` on its three dead ends.
  - The NOT_TS pause toast (UX-S7 strings).
- **B1 item 4 (the silent kick):** it needs `kick_mode=silent` in the fake panel (`tools/`) and the I-TS-25 device run. It is noted in §4.4.
- **The writer's own "behind"** for the cap is still paused, or no open reader at the head (R3). The writer cannot see the playhead. A viewer less than one player buffer behind is therefore "at the head" for the cap only, and the controller's S1 rule still stops saving there on a refusal.
- **S2 optional** (ignore `PCR_JUMP` segments right after a watchdog reconnect): not done. With S9's log, the field data decides first.
- **S9's adaptive `noBytesTimeoutMs`:** not done (thresholds unchanged, as instructed). Read the new log lines from the provider first.
- **S3 edge:** a `prepare` cancelled by a zap or stop but finishing its IO anyway leaves a grant outstanding until the next `prepare`, which supersedes it. Meanwhile `discardIfIdle` (trim memory) does nothing, and a stop in that window does not delete the file. The next start's sweep handles it.
- **Storm repeat relaunches buffered, not unbuffered** (design record, deviation 1): an unbuffered relaunch would need a new `BufferOff` value, which breaks the exhaustive `when`s in `ui/` and `app/`.
- **L2 and L5–L17:** untouched. L5 (a 0-byte `pread`/`pwrite` loop) and L8 (ENOSPC on the first extent reported as STORAGE_ERROR) are the next cheapest.
- **WP-D's I-TS-6 deviation still holds:** a server close every 15 s while paused is 4 counted losses a minute, so it is still SLOT_LOST. B1 removes the watchdog and at-live cases, not real kicks.
