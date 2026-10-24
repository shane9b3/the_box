# Save location for pause and rewind: code review, core half (2026-10-24)

Reviewer C (correctness, robustness, security). Scope: `core/.../storage/**`, the `core/.../playback/timeshift/**` changes
(`RingFlusher`, `TsRingImpl`, contracts), `data/.../storage/**`, `player/.../timeshift/**`, the controller changes,
`TheBoxApplication` and the R8 rules, the phone path (`PhoneApi`, `HttpParser`, `LanHttpServerImpl`, `LanPhoneLink`,
`PhoneLinkImpl`, `assets/phone/*`), credential storage and redaction, backup and diagnostics. Settings, UI and strings are
reviewer U's.

Baseline: `docs/design/m6-storage-locations.md` (contract, lead sign-off, every implementation record, "Integration pass
(2026-10-24)") and `docs/design/m6-timeshift.md` §3–§8. Code: `main` at `c727b1d` (feature range `e8b1f7d^..c727b1d`).
smbj 0.15.0 sources: the session scratchpad copy that the design cites (`storage/src/smbj/`).

Method: read-only for production code. Every claim below was checked in the code; paths are relative to the repo root.
Five claims are backed by probe tests in this worktree. Each probe asserts the behaviour the design asks for, so a red
probe is the finding:
- `core/src/test/kotlin/app/thebox/core/storage/smb/ReviewCoreProbeTest.kt` (3 probes, **3 red**);
- `data/src/test/kotlin/app/thebox/data/storage/smb/ReviewCoreDataProbeTest.kt` (2 probes, **2 red**).

Run them with `./gradlew :core:test --tests '*ReviewCoreProbeTest' :data:testDebugUnitTest --tests '*ReviewCoreDataProbeTest'`.
They are review artefacts, not for commit as they stand. In the fix pass each one becomes the regression test named
under its finding.

## Verdict

**Not ready to ship.** The structure is right:
- one link owner;
- no network I/O on `prepare` (the no-I/O test drives the real factory);
- a dedicated flusher that exits;
- interrupts are cancels inside `SmbRingStorage`;
- reopens never truncate;
- the phone endpoint is gated, one-shot and zeroes its buffers;
- logs carry fault names only.

Three defects block the release:

- **C-B1.** The §5.2 early switch is not bounded by the RAM pool. `create` can overrun its deadline by up to one I/O
  timeout per step. The deadline itself is longer than the 160-block pool lasts at about 2 MB/s or more. A 4K channel, or a
  sleeping NAS disk on an HD channel, therefore ends in `DISK_TOO_SLOW` before the switch, and the viewer gets no rewind
  anywhere. That breaks owner ruling 1 and lead ruling 2. Probe red.
- **C-B2.** Browse sessions to one computer share a single smbj `Connection`, and `close(session)` force-closes it. A
  re-sign-in inside a browse closes the old session, and with it the new one's transport. That happens after an
  anonymous share listing on a Samba-based NAS, and on the phone sign-in (U-B1). The user then reads "Can't reach NAS".
- **C-B3.** A Media3 load cancel interrupts the loader thread. If that interrupt lands inside `FileChannel.read`, the
  channel closes, and the channel is the ring file's only fd. The flusher's next `pwrite` then fails and rewind stops with
  a storage error. This was already true for this device's ring; the new USB storage inherits it, and it breaks A-B3's
  principle that an interrupt is a cancel and never a storage failure. Probe red.

---

## Blockers

### C-B1. The early switch (§5.2) is not bounded by the pool: `create` overruns its deadline, and the deadline exceeds the pool at ≥ 2 MB/s

**Problem.** Two separate gaps.

1. **`create` checks the deadline only between steps** (`core/.../storage/smb/SmbRingStorage.kt:93-104`):
   - `checkDeadline` runs after `link.share` (`:94`) and after `mkdirs` (`:96`);
   - the post-`open` check is at `:104`.
   Each step is one or more smbj round trips, and each round trip is bounded only by `SMB_IO_TIMEOUT_MS` (8 s,
   `SmbjRemoteLink.kt:80`). `SmbjShare.mkdirs` is one `folderExists` per path component, plus a `mkdir` (`SmbjRemoteLink.kt:196-204`).
   So a CREATE that answers at 5.9 s after a `mkdirs` that took 5.9 s returns at about 11.8 s against a 6 s deadline. The
   flusher stays blocked inside `FallbackRingStorage.decide` (`FallbackRingStorage.kt:131`) the whole time.
2. **The deadline does not follow the stream rate.**
   - `SMB_OPEN_DEADLINE_MS` is 6 s (`StorageConstants.kt:60`; `TimeshiftSessionFactoryImpl.kt:367`), measured from the
     first `pwrite`, which comes ≤ `FLUSH_PARTIAL_MS` (1 s) after the first commit.
   - The 160-block pool is 10.47 MB. At 3.5 MB/s (4K) it lasts 3.0 s, and the pump then waits `PUMP_BLOCK_MAX_MS` (2 s,
     `TimeshiftConstants.kt:47`) before `DISK_TOO_SLOW`: about 5 s against a switch at about 7 s.
   - At 2 MB/s the two are about equal (≈ 7.2 s against ≈ 7 s).
   - §5.2's "lands inside the pool's ≈ 10.5 s" holds only at the assumed 1 MB/s.

**Evidence.** `ReviewCoreProbeTest.theEarlySwitchLandsByItsDeadline`: `FakeRemoteLink.latencyMs = 900`, deadline 1,000 ms.
The first `pwrite` returns after **1,811 ms**: `mkdirs` passes the deadline check at 900 ms, then the CREATE takes another 900 ms.

**Failure scenario.**
1. A 4K channel, or an HD sports channel at 2 MB/s, with the folder on last-known-good and the NAS blackholed or its disk asleep.
2. The pool fills, then `DISK_TOO_SLOW`, and the ring goes RAM-only.
3. `ReportingWriter.report` (`TimeshiftSessionFactoryImpl.kt:605-621`) sees `switchedTo == null` and reports the stop
   inside the grace: no demotion.
4. The flusher then finishes `decide` and switches, which sets `fallbackReason`.

The viewer can get **two** toasts: REWIND_STOPPED_SLOW, then SAVE_LOCATION_FALLBACK ("saving on this device"). Neither
place saves anything. The same happens on every zap until the keeper learns the folder is down (see C-S3). I-SL-4b passed
only because the emulator's stream rate is low.

**Fix.**
- **Make the decision independent of `create` returning.** The flusher posts `primary.create(deadline)` to a helper
  thread: one per storage, short-lived, never `box-smb`. It then waits on that future, in `TICK_MS` slices, until **the
  earlier of**:
  - the deadline;
  - the pool falling below a threshold. Give `FallbackRingStorage` a `poolFree: () -> Int` from the ring
    (`TsRingImpl.pool`), or have the ring call `storage.onPoolPressure()`, and switch at ≤ 25 % free.
- **On a late `true`**, close the primary (posted) as on `false`. Nothing was written to it.
- **Start the create earlier.** Post it at the first `writeHeader` after construction. That is still no I/O on the
  prepare thread (A-B1) and gains the 1 s `FLUSH_PARTIAL_MS`.
- **Bound the deadline by the pool.** Keep `SMB_OPEN_DEADLINE_MS` as the upper bound, but cap it by
  `poolBytes / max(ASSUMED_BYTES_PER_SEC, rate seen so far) − PUMP_BLOCK_MAX_MS`.
- **In `ReportingWriter`**, do not report or toast a storage stop of a cycle whose `FallbackRingStorage` is still undecided;
  let the switch decide the wording, so there is one toast.

**Regression tests.**
- The probe turned green: `FallbackRingStorageTest.switchLandsByTheDeadlineWhateverEachStepTakes`.
- `TsRingOverSmbTest.aFourKRateWithABlackholedFolderStillSavesOnThisDevice`: the real ring, a 160-block pool, a pump at
  3.5 MB/s and `FakeRemoteLink.stallUntil(+30 s)`. Assert that `switchedTo == INTERNAL`, that no `DISK_TOO_SLOW` occurs
  and that exactly one `fallbackReason` is published.

### C-B2. Browse sessions to one computer share an smbj `Connection`; closing one kills the others

**Problem.**
- `NetworkFoldersImpl` uses **one** browse `SMBClient` (`data/.../storage/NetworkFoldersImpl.kt:71`), and every `open`
  calls `client.connect(host, port)` (`:107`).
- smbj caches connections per `host:port` and hands out a lease of the cached `Connection`
  (`SMBClient.getEstablishedOrConnect`, `SMBClient.java:86-107`).
- `close(session)` runs `SmbjClients.forceClose(b.connection)` (`:202`), that is `Connection.close(true)`. That
  disconnects the transport for every lease (`Connection.java:177-197`).
- `openWith`'s NeedsSignIn and error paths force-close too (`:132`, `:135`, `:139`, `:143`).

**Evidence.** `StoragePages.onOpened` (`core/.../settings/state/StoragePages.kt:1001`) closes the *previous* handle after
a new sign-in succeeds: `s.handle?.let { old -> if (old.id != r.handle.id) folders.close(old) }`. The old and new
handles hold the same `Connection` object, so the new session's next `shares()` / `folders()` / `test()` fails on a dead
transport. `NetworkFoldersImpl` has no unit test at all (see "Test gaps").

**Failure scenario.**
1. A Samba-based NAS (Synology, QNAP, TrueNAS, any `restrict anonymous = 0`) lets the first open succeed anonymously
   (`openWith` tries ANONYMOUS first, `:115`), and the shares are listed.
2. The viewer picks a private share. `connectShare` is refused (ACCESS) and the page asks for a sign-in (§7.5), on the TV
   or from the phone (U-B1).
3. The sign-in succeeds and `close(old)` is posted. The new session's share listing or folder list then fails with
   "Can't reach NAS (192.168.1.20). Check it's on…".

That is the wrong reason and a dead end. Two overlapping sessions to one host fail the same way, for example a stored
location's Test from `LocationDetail` while a browse is open.

**Fix.** Give each browse session its own connection lifetime:
- **Preferred:** a fresh `SMBClient(config)` per `openWith` (cheap: the config is shared and the BC provider is a static
  provider), with `close(true)` kept.
- **Or:** close per lease. `session.close()` (logoff), then `connection.close()` (`close(false)` drops one lease), and
  `close(true)` only when the transport is already dead. NeedsSignIn and the error paths must not force-close a
  connection another session leases.

**Regression test.** Add a `BrowseConnections` seam (connect → lease) to `NetworkFoldersImpl`, then
`NetworkFoldersImplTest.reSignInInsideABrowseKeepsTheNewSessionsConnection`: open anonymously, open again as a user to
the same host, close the first, and assert that the second session's `shares()` still runs on a live connection.
Also run the opt-in `SmbIntegrationTest` case against Samba: anonymous, then user, then list folders.

### C-B3. An interrupted reader closes the ring file's `FileChannel` (this device, and now USB)

**Problem.**
- `AndroidRingStorage.pread` and `pwrite` use `FileChannel` positional I/O
  (`player/.../timeshift/AndroidRingStorage.kt:141-160`); `FileRingStorage` makes the same calls (`:100-103`).
- `FileChannel` is an `InterruptibleChannel`. A thread interrupted inside `read` gets `ClosedByInterruptException`,
  and **the channel is closed for every thread**.
- Media3's `Loader` interrupts the loading thread on cancel. `RingDataSource` relies on exactly that
  (`RingDataSource.kt:42`).
- `RingReaderImpl.kt:68` checks the interrupt flag *before* a read, which narrows the window but does not close it.
- `ensureOpen()` (`AndroidRingStorage.kt:234-236`) never reopens.

**Evidence.** `ReviewCoreProbeTest.anInterruptedReaderDoesNotCloseTheRingFile`: an interrupted `pread`, then the
flusher-side `pwrite` throws `ClosedChannelException`.

**Failure scenario.**
1. The viewer is 20 min behind live, so reads come from the file, not RAM, and scrubs or skips: Media3 cancels the
   in-flight load.
2. The interrupt lands inside `channel.read`, and the fd is closed.
3. The flusher's next `pwrite` raises `DISK_ERROR`, the ring goes RAM-only, and the viewer reads "rewind stopped" with a
   disk reason, on this device or on a USB drive.

This is the local twin of A-B3. It predates the feature on this device, but WP-S2's `VolumeStorage` is built on it.
SMB is not affected: smbj turns the interrupt into a CANCEL.

**Fix.** Do positional I/O through `android.system.Os.pread` / `Os.pwrite` on the `FileDescriptor`. They are not
interruptible, and `RingPosix` already wraps `Os` for `fallocate` and `nlink`. Keep the direct `ByteBuffer`s:
`Os.pread(fd, ByteBuffer, offset)` exists from API 23. Do the same in `FileRingStorage`, or give it a
non-interruptible path such as a `RandomAccessFile` per call under a lock, so that the JVM tests mirror the device.

**Regression test.** The probe turned green, in `core` for `FileRingStorage` and as an instrumented
`AndroidRingStorageTest.anInterruptedPreadLeavesTheFileUsable`. Also add `TimeShiftBufferTest` I-TS-x: behind live from
the file, 20 rapid seeks, then assert there is no `DISK_ERROR`.

---

## Should-fix

### C-S1. A retired link is resurrected by its ring and never closed; a rename relinks mid-cycle

**Problem.**
- The keeper's link key is `location to signInVersion` (`data/.../smb/SmbSessionKeeper.kt:198`). `StorageLocation` is a
  data class that includes `name` and `lastTest`.
- So `rename` (`StorageLocationsImpl.kt:146-150`) changes the key. So do `updateSignIn`, and choosing another location or
  this device (`evaluate`, `:249-258`).
- When the key changes, `linkFor` makes a new link and posts `old.link.disconnect()` (`:212-216`).
- A ring opened on the old link still holds it. Its next WRITE fails (LINK_LOST), and `SmbRingStorage.reopen` calls
  `old.reconnect(...)` (`SmbRingStorage.kt:303`). `SmbjRemoteLink.reconnect` reconnects unconditionally
  (`SmbjRemoteLink.kt:311-322`, `:386-393`).
- The keeper no longer knows that link, so nothing ever disconnects it: no keepalive, no background disconnect, no
  teardown when the storage closes. The socket and smbj's PacketReader thread live until the process dies.

**Evidence.** `ReviewCoreDataProbeTest.aRetiredLinkNeverKeepsAConnection`: after the rename, the old link drops. The
"ring" reconnects it. Choosing this device disconnects only the current link, and `old.isReady` is still true.

**Failure scenario.** A viewer renames the folder "NAS" to "Office" while watching on it:
- The ring's connection is cut mid-cycle. That is a reconnect and `needsFreshFile`, but invisible.
- A second SMB session stays on the NAS for good. Each further rename or sign-in change adds one.

On Windows with a per-user session cap, or on a NAS that counts sessions, that is a leak the user can see. The same
happens when the viewer switches the save place while watching.

**Fix.**
1. **Key the link on what the connection needs:** `(id, host, port, share, user, domain, guest, signInVersion)`. A rename
   or a Test result must not relink.
2. **Retire, don't orphan.** On a relink, mark the old `SmbjRemoteLink` retired:
   - `reconnect` on a retired link throws `LinkUnavailableException` (the ring then fails over as UNREACHABLE and the next
     zap uses the new link);
   - `release()` dropping to 0 on a retired link posts `disconnect()`.

**Regression test.** The probe turned green, plus `SmbSessionKeeperTest.aRenameDoesNotRelink` (same link object, no extra
connect).

### C-S2. Any unknown server status on a live transport is classified LINK_LOST, which tears down a healthy connection

**Problem.**
- `SmbFaults.classify` ends with "anything else is LINK_LOST" (`data/.../smb/SmbFaults.kt:78`, KDoc `:45`).
  `STATUS_INVALID_PARAMETER`, `STATUS_SHARING_VIOLATION`, `STATUS_INSUFFICIENT_RESOURCES`,
  `STATUS_FILE_LOCK_CONFLICT`, `STATUS_NOT_SUPPORTED` and similar statuses therefore all mean "reconnect".
- `SmbRingStorage.retrying` (`SmbRingStorage.kt:254-265`) then runs `reopen` → `link.reconnect` → `connectBlocking` →
  `dropCurrent()`, a `close(true)`. That kills the connection for the readers and the keeper too, and it repeats every
  `SMB_RECONNECT_MIN_GAP_MS` for the 6 s budget.
- Lead ruling 1 / A-B4 say: "Reconnect happens only on real transport errors."

**Evidence.** `ReviewCoreDataProbeTest.aServerStatusOnALiveTransportIsNotALinkLoss`: `STATUS_INVALID_PARAMETER` on a live
transport is classified as **LINK_LOST**.

**Failure scenario.** Windows answers `STATUS_INSUFFICIENT_RESOURCES` under memory pressure (paged pool), or a NAS
antivirus hook answers a lock conflict on one WRITE. Each such answer drops the TCP connection, forces a fresh file and,
past the budget, ends with `DISK_ERROR (UNREACHABLE)` and the wrong wording ("Can't reach…" for a reachable server).

**Fix.**
- An `SMBApiException` with a status not in any table, **on a live transport**, is a server refusal. Give it its own class,
  or fold it into SLOW for writes ("fail the operation, no reconnect, `needsFreshFile`"). Keep LINK_LOST for `isDeadTransport`, the
  link statuses, `transportAlive == false` and `LinkUnavailableException`.
- Add `STATUS_INSUFFICIENT_RESOURCES` and `STATUS_IO_TIMEOUT` to SLOW explicitly.

**Regression test.** The probe turned green, plus `SmbReconnectTest.aServerRefusalNeverReconnects`
(`FakeRemoteLink.classify` gains a REFUSED status: 0 reconnects, the write fails once).

### C-S3. A create or switch failure never reaches the keeper or the targets; a READY keeper makes every zap retry the folder and rebuild the ring

**Problem.**
- When `FallbackRingStorage.decide` switches (`FallbackRingStorage.kt:139-168`), nothing calls `targets.onStorageFailure`,
  `keeper.onRingFailure` or the demotion. `ReportingWriter` returns early for a switched storage
  (`TimeshiftSessionFactoryImpl.kt:608`).
- The keeper's keepalive (`getShareInformation`, `SmbSessionKeeper.kt:309-332`) still succeeds when the folder is
  reachable but refuses CREATE. That covers: ACCESS after a permission change, the chosen path deleted, a per-folder quota,
  and a share mounted read-only.
- So `current()` keeps answering SMB/READY (`StorageTargetsImpl.kt:140-159`), and on every zap:
  - the switched storage is "unusable" (`TimeshiftSessionFactoryImpl.kt:342`), so the ring is **rebuilt on the zap path**:
    a new `BlockPool` of 160 `allocateDirect` blocks (10 MiB), while the old pool is still referenced until its readers go;
  - a new SMB name is used;
  - a new `-fb<n>` file is created on this device.
- Separately, `create` never retries a LINK_LOST within its deadline (`SmbRingStorage.kt:111-119`). A READY keeper whose
  connection died since the last keepalive (a standby under `SMB_BACKGROUND_DISCONNECT_MS`, a router reboot) therefore
  switches at once, with a toast, though a reconnect would take about 100 ms.

**Failure scenario.**
1. The owner removes the box's write access on the NAS. Every channel change spends about 10 MiB of fresh allocation and
   a CREATE round trip.
2. A toast appears once, then silence. The Save to page still says READY.
3. Rewind works, on this device, but the folder is never demoted and the status never explains why.

**Fix.**
1. When the switch happens, call `targets.onStorageFailure(locationId, DISK_ERROR, reason, since = 0)` or a dedicated
   `onCreateFailed(locationId, failure)`. For ACCESS, FULL and REMOVED, demote the location (no grace: nothing was written)
   and publish `LocationState` with the failure, so `current()` falls back at tune time with the right reason. For
   UNREACHABLE, kick `keepaliveTick` (mark `failureSeen`).
2. In `create`, treat LINK_LOST like `retrying` does: one `link.reconnect(epoch, deadline)`, then retry, within the deadline.
3. Optional: when the target is still the same SMB location and the previous storage was switched, reuse the ring with
   its secondary instead of rebuilding.

**Regression tests.**
- `FactoryTargetTest.aCreateRefusedOnAReadyFolderDemotesItAndTheNextZapIsInternalWithoutRebuild`.
- `SmbRingStorageTest.createReconnectsOnceWithinItsDeadline`.

### C-S4. A failed free-space query shrinks the ring for the rest of the cycle

**Problem.**
- `SmbRingStorage.availableBytes` answers 0 on any IOException (`SmbRingStorage.kt:187-190`).
- `TsRingImpl.ensureAllocated` then sets `growthStop` (`TsRingImpl.kt:552-566`), and `maybeShrink` caps `cap` at the
  current head **permanently** for the cycle (`:593-597`; only `reset()` restores it).
- The live query happens only when the keeper's cache is older than `SMB_SPACE_MAX_AGE_MS`, that is exactly when the
  keepalive has been failing.

**Failure scenario.**
1. The NAS is busy (a scrub, a backup) and two keepalives time out (SLOW keeps the connection, `:320`).
2. Two minutes into a 1-hour cycle, the flusher's extent check does a live query that times out.
3. The rewind window of this cycle is now 2 min, and stays so after the NAS recovers.

**Fix.**
- Answer 0 only for a FULL answer.
- On SLOW, LINK_LOST or CANCEL, answer the last known value whatever its age (`link.lastFree()`), or "unknown, do not
  halt" (`Long.MAX_VALUE / 4`, as before `create`).
- On FULL, `pwrite` already fails with `DISK_ERROR (FULL)`, so nothing is lost.

**Regression test.** `TsRingOverSmbTest.aTimedOutSpaceQueryDoesNotShrinkTheRing`: stale cache, `timeoutNext()` on
`freeBytes`, and `cap` unchanged after the next extent.

### C-S5. The 445 sweep is routed through the VPN when binding to Wi-Fi fails

**Problem.**
- `NetworkLanSockets` falls back to an **unbound** socket when `network` is null or the bind throws
  (`data/.../discovery/Port445Sweep.kt:37-45`).
- `AndroidFinderEnvironment.networkOf` returns null on a `SecurityException`, when no non-VPN network carries the address,
  and on API 25 Ethernet boxes where `allNetworks` hides the network (`ComputerFinderImpl.kt:217-227`).
- With a full-tunnel VPN up, the system routes `192.168.1.x:445` SYNs and NBNS packets into the tunnel. The KDoc's "which
  still only ever targets addresses in our own /24" holds for the addresses, not for the route.
- §7.1 and U-S2 say: "never `TRANSPORT_VPN`".

**Failure scenario.** A viewer with an always-on VPN app opens Find computers. 253 SYNs to 445 and up to 253 NBNS packets
go to the VPN provider's network, where an RFC 1918 /24 may well exist. Results from the far side show up as "computers".

**Fix.**
- When `vpnUp()` and `networkOf(own) == null`, skip the sweep and the NBNS lookups: NSD and manual entry still run, and the
  amber VPN Info row already explains.
- When a bind throws, close that socket and skip it; never fall back to an unbound one while a VPN is up.

**Regression test.** `ComputerFinderTest.withAVpnAndNoBindableNetworkTheSweepDoesNotRun`.

### C-S6. `NetworkFoldersImpl`: a cancel after a successful sign-in leaks the session (connection and password copy); share listing swallows the cancel

**Problem.**
- `openWith` runs in `runInterruptible(dispatcher)` (`NetworkFoldersImpl.kt:105`) and stores the session in the singleton
  map before returning (`:128`). If the caller is cancelled after the block completed (BACK just as the server answers),
  `withContext` discards the result.
- The `Browse` entry is then never closed: its connection stays open, and `password?.copyOf()` stays in memory for the
  process's life. That copy is not even needed: nothing reads `Browse.password` except `close` (`:201`).
- `SmbShareLister.list` catches `Exception` (`SmbShareLister.kt:24`), so an interrupt during srvsvc becomes `LIST_FAILED`
  instead of a cancel.
- The srvsvc pipe transport is never closed (`:22-23`).

**Fix.**
- Do not keep a password copy in `Browse`.
- Register the session in a way the caller confirms. Either hand back the handle through a `CompletableDeferred` and close
  it in `invokeOnCompletion` when cancelled, or make `open` non-cancellable after `authenticate` succeeds
  (`withContext(NonCancellable)` for the registration) and let the page close it.
- In the lister, rethrow when `SmbFaults.classify(e) == CANCEL`, and close the transport in `finally`.

**Regression test.** `NetworkFoldersImplTest.cancelAfterSignInClosesTheSession` (with the C-B2 seam).

### C-S7. The sweep deletes another box's files on time alone; an exclusive-open fence is cheap; a hung server blocks `box-smb`

**Problem.**
- Rule (b) deletes a foreign `ring-*` / `test-*` / `.wake-*` file when its LastWriteTime is more than 48 h older than our
  `.wake` (`SmbSweeper.kt:41-48`). The ring is opened with `FILE_SHARE_DELETE`, so a delete of a live foreign ring
  succeeds (§3.5).
- The premise is unverified: that a ring being written always shows a fresh LastWriteTime to *another* client. Windows
  (NTFS) may defer the LastWriteTime update until the writer's handle closes. A box that keeps one ring file open across
  zaps for days would then look stale.
- Each `lastWriteMs` / `delete` can take up to 8 s on a hung server, and the loop never stops early. With many entries
  this holds `box-smb`, and with it the ring's reconnects, posted CLOSEs and keepalives, for minutes. The watchdog only
  aborts a sign-in (`SmbSessionKeeper.kt:225-233`).

**Fix.**
- Delete a foreign file only through `open(path, DELETE, shareAccess = none, FILE_OPEN, FILE_DELETE_ON_CLOSE)` then CLOSE.
  If the other box holds it open, the server answers `STATUS_SHARING_VIOLATION`: skip the file.
- Stop the sweep at the first SLOW or LINK_LOST.
- Add to §21 / S-2: check the LastWriteTime freshness of a held-open file on Windows and on macOS smbd.

**Regression test.** `SmbSweepTest.aForeignFileHeldOpenIsKept` (the fake gains share-mode checks) and
`SmbSweepTest.aTimeoutEndsTheSweep`.

---

## Nice-to-have

### C-N1. A stale WRITE can still meet a reused file (TOCTOU between the reuse check and the in-flight write)

**Problem.**
- The factory checks `needsFreshFile` at `prepare` (`TimeshiftSessionFactoryImpl.kt:342-343`), then calls `reset()`.
- If the flusher is inside an old-generation WRITE when the transport dies, `freshV` is set *after* the check. The
  write is then retried on the new connection and the new generation keeps the file, while the old `smbd` may still
  apply the original WRITE.
- The overlap needs that offset to come up again soon in the new generation, so it is narrow.

**Fix.** In `SmbRingStorage`, remember the generation of the last `writeHeader`. The first `pwrite` of a new generation
with `freshV` set throws `IOException`, and the cycle goes RAM-only (`DISK_ERROR`, inside the grace).

**Test.** `SmbReconnectTest.aReconnectDuringTheZapNeverWritesTheNewGenerationIntoTheOldFile`.

### C-N2. `delete()` of a never-created storage posts an `rm` round trip

**Problem.** `SmbRingStorage.delete` always posts `share(now).delete(path)` (`SmbRingStorage.kt:198-203`). After a
switch, or for a storage never written, that is one wasted round trip on `box-smb`: up to 8 s on a hung READY server,
once per rebuilt zap.

**Evidence.** `ReviewCoreProbeTest.deletingANeverCreatedStorageSendsNothing`: the calls are `[share, delete:…]`.

**Fix.** Post the `rm` only when a CREATE was sent (`createAttempted` past `open`).

**Test.** The probe turned green.

### C-N3. `retrying` reads `file` and `fileEpoch` non-atomically

**Problem.** `SmbRingStorage.kt:244-245`. A reopen between the two reads pairs the old handle with the new epoch. The
handle's failure then asks `reconnect(newEpoch)`, which tears down the healthy new connection.

**Fix.** Keep `(file, share, epoch)` in one immutable holder in a single volatile, read once.

### C-N4. `ManualAddressParser` accepts `..`, `.` and control characters that the phone mirror refuses

**Problem.**
- `PhoneApi.splitFolder` rejects `..` and drops `.` (`PhoneApi.kt` `splitFolder`).
- `ManualAddressParser` does not (`ManualAddressParser.kt:44-56`), and `smb://` percent-decoding can produce `%00`.
- The server refuses an escape from the share, so this is not a traversal, but the "same vectors" promise is broken, and
  `ringDir` can become `../TheBox-rewind`.

**Fix.** Reject `..`, drop `.`, and refuse control characters in both parsers. Add the vectors to `VECTORS`.

### C-N5. A phone submission can be dropped while another collector subscribes

**Problem.**
- `PhoneApi` accepts when **any** collector of `PhoneLink.submissions` exists (`LanPhoneLink.kt:99`).
- `StoragePages` filters for `NetworkFolder` only (`StoragePages.kt:1142`). If another collector is subscribed (the
  setup flow, a retained VM) while `phoneJob` is not active, the submission is delivered elsewhere and filtered out.
- Its `CharArray` is then never zeroed, and `folderChecking` stays true, so the phone gets 409 until the idle limit.

**Fix.** Gate `canAccept` per purpose (a NETWORK_FOLDER collector registered by `StoragePages`). Zero the password of an
unconsumed submission in `stop()`.

### C-N6. R8: the rules match the sources, but nothing at runtime proves them

**Problem.**
- The block in `app/proguard-rules.pro` covers:
  - mbassador, whole;
  - `@Handler` methods, including `SMBClient.connectionClosed`;
  - smbj's events, enums, file-information classes and NTLM;
  - dcerpc's enums;
  - `java.rmi`.
- I found no other reflection in smbj 0.15.0, but asn-one (SPNEGO wrapping for NTLM) is not kept and was not read.
- If `connectionClosed` were stripped, `SMBClient`'s table would keep dead connections. `getEstablishedOrConnect`
  re-checks `isConnected`, so that case is survivable.

**Fix.** S-2 as planned, plus a release-variant instrumented smoke case: anonymous `connect` → `authenticate` →
`connectShare` → `list` → `getShares1`, against the macOS smbd at `10.0.2.2`, in `SaveLocationTest`.

### C-N7. `LocationTester` is not "time-bounded at 6 s"

**Problem.** Every single round trip can take up to 8 s:
- `mkdirs`, the wake write and the CREATE are not inside a phase budget;
- a rate phase can overrun by one timeout;
- the RTT phase stops at the first timeout.
The worst case is about 40 s with the Stop test row as the only exit.

**Fix.** Bound the whole run with a deadline checked between round trips. Show "Stopped: NAS took too long" when it is hit.

### C-N8. Local file reads on the `current()` path

**Problem.** `LastGoodStore.get` reads a file on its first call, `DeviceIdentity.id` on first use, and `StatFs` runs on
every call (`StorageTargetsImpl.kt:111`, `:142`). All of them run on the prepare thread (IO), so none is a network wait.
The §5.1 Must says "no I/O", and the KDoc says "no network I/O".

**Fix.** Align the words, or warm both files in `TheBoxApplication`'s off-main start.

---

## Checked and fine

- **No I/O on `prepare`:** `SmbPrepareNoIoTest` drives the real factory. `close` and `delete` only post
  (`SmbRingStorage.kt:198-218`). `FallbackRingStorage` before the decision answers from memory.
- **Owned flusher exit:** the `exitIfIdle` / `ensureThread` monitor closes the register race (`RingFlusher.kt:58-79`).
- **Interrupts in `SmbRingStorage`** (A-B3): CANCEL first everywhere, including the reopen lock (`tryLock`) and the link
  waits (`awaitReady`, `lockInterruptibly`). The epoch is untouched.
- **Reopen:** OPEN_EXISTING, full sharing, and the old handle closed by posting.
- **The fallback decides once:** `decided`, the `remoteBytesWritten > 0` guard, no hot switch after a create, and the
  close-during-decide paths delete the secondary.
- **Sweeps:**
  - the SMB sweep keeps every `-<pid>-` name of this process and never touches names outside its prefixes;
  - the USB sweep keeps `ring-<pid>-*` (the trailing `-` prevents prefix collisions);
  - this device's fallback files are `ring-<pid>-fb<n>.bin`, distinct from `ring-<pid>.bin`.
- **USB eject:** the receiver only stops the ring and posts the fd close (`TimeshiftSessionFactoryImpl.kt:577-586`,
  `VolumeStorage.eject`); the listing is refreshed on `box-ts-sweep`.
- **Discovery bounds:**
  - `check(host in subnet)`;
  - literal addresses, no DNS;
  - pending sockets closed on cancel;
  - NSD stop-before-started handled;
  - NBNS answers accepted only from the asked host with our transaction id;
  - the multicast lock is released in `finally`.
  The VPN fallback is C-S5.
- **Phone endpoint:**
  - the M5 gate (Host, `X-TheBox`, Origin, pairing cookie);
  - `onlyFor(NETWORK_FOLDER)`;
  - one submission at a time (409);
  - 410 after a success or `PAIR_MAX_WRONG` failures;
  - a fixed body buffer zeroed, plus the chunk buffer and the read-ahead (`HttpParser`, `WipingBufferedInputStream`);
  - the password only in a `CharArray`;
  - the `/api/state` mirror masks the user;
  - `app.js` builds the DOM without `innerHTML`, and the CSP blocks inline script;
  - `..` refused in `splitFolder`.
- **Credentials:**
  - Tink blob `smb-<id>` with the UTF-8 copies zeroed;
  - `PasswordRead.Ok` and `SmbAddress` mask their `toString`;
  - smbj's `AuthenticationContext` copy is zeroed after `authenticate`;
  - the backup reads only named blobs (`BackupServiceImpl`), so `smb-*` never leaves the box;
  - the diagnostics audit adds the user, domain and password to the redaction set.
- **Logs:** every storage log line carries an id, a kind, a count or a `faultName`; `SmbIoException`'s message is the
  fault name; no stack traces with smbj messages are logged on these paths.

## Test gaps (Musts without a test)

| Must | Gap |
|---|---|
| §5.2 switch lands inside the pool (lead ruling 2) | only the 1 MB/s case in I-SL-4b; nothing at a 4K rate or with slow steps (C-B1) |
| §7.3–§7.5 browse/sign-in | `NetworkFoldersImpl` has **no** unit test: re-sign-in, overlapping sessions, cancel, stored-password zeroing (C-B2, C-S6) |
| A-B3 for local storages | no test interrupts a `pread` on `AndroidRingStorage` / `FileRingStorage` (C-B3) |
| A-B4 "reconnect only on transport errors" | `SmbFaultsTest` covers known statuses only, not the default branch (C-S2) |
| A-S1 "one owner" | no test of a relink with an open ring (C-S1) |
| §4.2 concurrent read/write on one handle | `SmbConcurrencyTest` is opt-in and was not run (no Samba in the integration pass) |
| S-2 keep rules | no release-variant runtime check (C-N6) |
