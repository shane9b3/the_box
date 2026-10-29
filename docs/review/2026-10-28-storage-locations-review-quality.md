# Save location for pause and rewind: code-quality review (dead code, duplication, efficiency, hygiene)

Scope: `git diff e8b1f7d^ HEAD` at `5d99f57`. Read-only; no Gradle run. Every "unreferenced" claim below was checked with
`git grep` over the whole repo (main, test, testFixtures, androidTest, assets), not only the diff. Findings already in
`docs/review/2026-10-24-storage-locations-review-core.md` / `-ui.md` are not repeated.

## Verdict

The feature is in good shape for code that came from six parallel authors. The strings are clean: every name in
`strings_storage*.xml` is referenced, U-N4's `storage_phone_result_*` is gone, and every `StorageText` key is used. The
ring hot path (`SmbRingStorage.pwrite`/`pread`, `UninterruptibleFileIo`, `FallbackRingStorage`) keeps its promise of
no per-call allocation, and the zap path does no network I/O in `prepare`.

What parallel authorship left behind is mostly small. The exceptions are:

- **Two time-shift tests pass without testing anything (Q-D9).** They are still guarded on the WP-S1 stub (`NoIoRemoteStorage`), which is never selected now. So "a switched cycle counts wear" and "the SMB grace starts at the first write" have no live test.
- **One design trigger is unwired (Q-D1).** The "guide's preview start" wake (§5.3) is a contract method, `RingTargetSource.warm()`, plus `SmbSessionKeeper.wakeNow()`. Nothing calls either.
- **Address parsing has two owners with different grammars (Q-U1).** `ManualAddressParser` and `PhoneApi.splitFolder`/`app.js` disagree. A phone submission can therefore be silently half-applied.
- **The keeper's threads start for every viewer on the first tune (Q-E1).** This includes viewers who never add a network folder.
- **A retired link retries until the full reconnect budget runs out (Q-E3).** It can never come back, so every ring I/O on it waits up to 6 s before it fails over.

Everything else is one-to-ten-line deletions or consolidations.

---

## Dead code

### Q-D1. The §5.3 "guide's preview start" trigger has no caller: `RingTargetSource.warm()` and `SmbSessionKeeper.wakeNow()`
- **What:** `RingTargetSource.warm()` (`core/.../playback/timeshift/TimeshiftContracts.kt:119`) is implemented by `StorageTargetsImpl.kt:88` (→ `keeper.warm()`) and `InternalRingTargets.kt:36`. `SmbSessionKeeper.wakeNow()` (`SmbSessionKeeper.kt:199`; KDoc `:95` "the guide's preview start ([wakeNow])") is also defined. Nothing calls either.
- **Proof:** `git grep -n 'wakeNow\|\.warm()'` → the only `.warm()` calls are inside `StorageTargetsImpl.smb()` (`:141`, the keeper's own `warm`) and the keeper's `onDemand` (`:256`). No call to `RingTargetSource.warm` or to `wakeNow` exists anywhere. The fake's counters `FakeRingTargetSource.warms` and `currentCalls` (`StorageFakes.kt:146-147`) are never read: `git grep -n 'warms\|currentCalls'` matches only `StorageFakes.kt`.
- **Why it matters:** the design requires it (§5.3 list, `m6-storage-locations.md:459`, `:463`: "on ON_START / preview start: write 4 KiB to `.wake-…`"). The intent was a contract method, so this is not "unused but append-only": it is a missing wire.
- **Fix (lead decides):**
  - Either wire the guide's preview start to `RingTargetSource.warm()` and make `StorageTargetsImpl.warm()` call `keeper.wakeNow()` (it calls `keeper.warm()` today, which never writes the wake file);
  - or record the cut in the design, delete `wakeNow()`, and leave `warm()` as an append-only no-op.
  - Either way, delete the two unread fake counters.

### Q-D2. `SmbjClients.browseClient()` is unreferenced since C-B2
- **What:** `data/.../smb/SmbjRemoteLink.kt:91-92`. C-B2 moved the browse side to `SmbjBrowseConnections`, which builds its own `SMBClient(SHARED_CONFIG)` per connect (`NetworkFoldersImpl.kt:84-110`).
- **Proof:** `git grep -n browseClient` → the definition only (no main or test use).
- **Fix:** delete it. Also update the `SmbjClients` KDoc (`:74`), "The two `SMBClient` instances … the browser/Test's", which no longer describes the code.

### Q-D3. `ComputerFinder.recentAddress` and its `nsdNames` cache are never read
- **What:** `ComputerFinderImpl.kt:63-64` (interface), `:102-104`, `:145` (write per NSD sighting), `:180` `norm`. The KDoc promises the "§7.2 `.local` fallback, U-8".
- **Proof:** `git grep -n recentAddress` → the interface and the override only. There are no callers in `NetworkFoldersImpl` or `StoragePages`, and no tests.
- **Fix:** delete `recentAddress`, `nsdNames` and `norm`, and keep `nsdCount` for the log line. If the `.local` fallback is still wanted, wire it into `NetworkFoldersImpl.openBlocking` on `UnknownHostException` instead. That is a lead call.

### Q-D4. `StoragePages.endAll()` has no caller
- **What:** `core/.../settings/state/StoragePages.kt:1245` ("tests, and a process that is going away").
- **Proof:** `git grep -n endAll` → the definition only. The tests use `openSessionCount()`, not `endAll()`.
- **Fix:** delete it.

### Q-D5. `StoragePages.mask()` is a private function nobody calls, and it disagrees with `PhoneApi.maskUser`
- **What:** `StoragePages.kt:1300-1304`. For names of length ≤ 2 it gives `"s***"`. The live masker, `PhoneApi.maskUser` (`PhoneApi.kt:473`), gives `"***"`.
- **Proof:** `grep -n 'mask(' StoragePages.kt` → line 1300 only. The `MASK` constant is a different symbol and is used (`:520`).
- **Fix:** delete it.

### Q-D6. `SettingsUi.DOTS_RIGHT_X` is unreferenced
- **What:** `ui/.../settings/SettingsUi.kt:146`. The searching dots are placed with `Modifier.padding(end = 8.dp)` (`SettingsRowViews.kt:182`).
- **Proof:** `git grep -n DOTS_RIGHT_X` → the definition only.
- **Fix:** delete it, or use it in `SearchingDots` if 1220 px was the intended position.

### Q-D7. `StorageConstants` values that nothing reads
- **Proof:** for each name, `git grep -lw NAME -- '*.kt'` minus `StorageConstants.kt` → 0 files:
  - `LABEL_LINK_MAX_CHARS` (`:28`);
  - `SEEK_NETWORK_P50_MS`, `SEEK_NETWORK_P90_MS` (`:120-121`);
  - `SMB_ALLOC_BPS_MAX` (`:123`);
  - `SMBJ_APK_BUDGET`, `SHARE_LISTER_APK_BUDGET` (`:125-126`);
  - `RECORDINGS_DIR_SMB` (`:19`).
- **Split:**
  - `RECORDINGS_DIR_SMB` is v2 (§11) and part of Appendix A. Keep it.
  - `LABEL_LINK_MAX_CHARS` is a broken promise. The KDoc at `:26` says "the Playback link value uses at most [LABEL_LINK_MAX_CHARS]", but `WpaPages.pauseRewindLink` never truncates `place.label`/`offlineName`. Either apply it there (one `take`/middle-ellipsis) or delete it and the sentence.
  - The five gate numbers are documentation. The file header (`:1-2`) says "tests assert against the names", but no test does. Either reference them from the gate tests or device suite, or move them to the design's gate table and delete them.

### Q-D8. `WpaPages.build(PauseRewind)` is a commit-0 path that ships nowhere
- **What:** `core/.../settings/state/WpaPages.kt:167-170` builds the Pause and rewind page. `handles()` (`:147-149`) no longer claims `PauseRewind` ("PauseRewind is StoragePages' page now; [build] still builds it … for the contract tests"), so the settings machine never reaches it.
- **Proof:** `git grep -n 'build(SettingsRoute.PauseRewind'` → only `WpaPagesTest.kt:50,72,277` and `EffectTimingCoverageTest.kt:30`. Those tests pin a page without the Save to row or the subtitle, which is not the page the viewer sees (`StoragePages.pauseRewind`, `StoragePages.kt:259-281`).
- **Fix:** delete the branch. Point the four test sites at `wpa.timeshiftRows(prefs)` (public, `:365`) or at `StoragePages.build(PauseRewind, …)`.

### Q-D9. Commit-0 test scaffolding that makes two tests vacuous (the most important item here)
- **What:** `player/src/test/.../timeshift/SaveLocationFakes.kt:20-89`: `NoIoRemoteStorage`, `smbRingStorageImplemented()` and `remoteStorage()`. They were "used only while WP-S1's SmbRingStorage is a stub". `SmbRingStorage` is real now, so `remoteStorage()` always returns `SmbRingStorage` and every `is NoIoRemoteStorage` branch is dead:
  - `FactoryTargetTest.aSwitchedCycleIsNeverReusedAndSavesOnThisDeviceWithTheWearCounter` (`FactoryTargetTest.kt:177-200`): the whole body after setup is under `if (fb.primary is NoIoRemoteStorage)` (`:184`), so it **asserts nothing**.
  - `FactoryTargetTest.theFirstWriteOfTheCycleDrivesTheGrace` (`:365-379`): `if (fb.primary !is NoIoRemoteStorage) return` (`:371`) always returns, so it **asserts nothing**.
  - `FactoryTargetTest` `:166-172`: the "fresh name on every rebuild" check maps `opened` through `as? NoIoRemoteStorage`, so `names` is empty and `assertEquals(names.size, names.toSet().size)` is 0 == 0.
  - `SmbPrepareNoIoTest.kt:146` (`markNeedsFreshFile`): the `NoIoRemoteStorage` arm is dead (the `else` is the live one).
- **Proof:** `smbChoice` → `remoteStorage` → `smbRingStorageImplemented()` builds a real `SmbRingStorage` and reads `needsFreshFile` (a plain getter, `SmbRingStorage.kt:97`), so it returns true. `git grep -n NoIoRemoteStorage` shows the only constructor call is in `remoteStorage`'s false branch.
- **Coverage lost:** `git grep -n 'writtenToday'` in tests → the only "the secondary counts wear" assertion is `FactoryTargetTest.kt:189`, which is dead. The only SMB `sinceFirstWriteMs == 25_000` assertion is `:378`, also dead. So §5.5 (a switched SMB cycle counts wear) and the §5.4 grace from the first remote write have no live test.
- **Fix:**
  - Delete `NoIoRemoteStorage`, `smbRingStorageImplemented` and `remoteStorage`, and build `SmbRingStorage` in `smbChoice` directly.
  - Remove the guards. Drive the two tests through the real storage: `link.dropTransport(); link.reconnectFails = true`, then `fb.pwrite(…)`. Give the factory an inline `background` so the switch is synchronous. `FallbackRingStorage`'s `creator` defaults to a new thread, so pass the test's storage a deadline it can reach quickly, or use a `FakeMonoClock`.
  - Replace `:171` with the `relativePath` of `SmbRingStorage` (`val relativePath`, `SmbRingStorage.kt:45`).

### Q-D10. Over-built seams that are always bound the same way (abandoned commit-0 switches)
- **`TimeshiftModule` `@BindsOptionalOf RingTargetSource`** (`player/.../TimeshiftModule.kt:36`), plus the `Optional<Provider<RingTargetSource>>` and `InternalRingTargets` parameters of the `@Inject` constructor (`TimeshiftSessionFactoryImpl.kt:118-124`).
  - `:player`'s module is only in the app's Hilt graph, and `:data`'s `StorageModule.kt:39` always binds `RingTargetSource` there. Proof: `player/src` has no `androidTest`, and the only `@HiltAndroidApp` is `app/.../TheBoxApplication.kt`. `data/build.gradle.kts` does not depend on `:player`, so `:data`'s androidTest graph has no `TimeshiftModule`.
  - The "while nothing is bound" branch is dead in production. The JVM tests use the primary constructor, where `InternalRingTargets(storage)` stays as the default (`:128`).
  - **Fix:** inject `Provider<RingTargetSource>` directly, and drop `@BindsOptionalOf` and the `internalTargets` parameter. Keep `InternalRingTargets` for the primary constructor's default.
- **`StorageFlags.VOLUME_ENABLED`** (`StorageConstants.kt:130-133`) is a `const val true`, read once (`VolumeWatcher.kt:65`). It is a spike switch (S-1). Keep it until S-1 is recorded closed, then delete it: a `const true` guard compiles to nothing, so this is only clarity.

---

## Duplication

### Q-U1. Two address parsers with different grammars: `ManualAddressParser` vs `PhoneApi.splitFolder` (+ `app.js`)
- **Where:**
  - `core/.../storage/ManualAddressParser.kt:32-74`: host grammar, IPv4 check, refuses IPv6 (`:49`), percent-decodes `smb://`.
  - `core/.../setup/lan/PhoneApi.kt:483-528`: its own `uncParts`/`segments`. `HOST_OK` (`:527`) **accepts bracketed IPv6**. It does no percent-decoding, but refuses `..` and control characters.
  - `data/src/main/assets/phone/app.js:252-284` mirrors `splitFolder`, but **not** its C-N4 control-character check (`grep -n '\\u007F\|control' app.js` → none).
- **Consequence:** `StoragePages.onPhone` (`StoragePages.kt:1266`) re-parses `sub.host` with `ManualAddressParser`. For a host `PhoneApi` accepted but the parser refuses (e.g. `[fe80::1]`), `parsed` is null. The old host is then kept silently while `sub.share`/`sub.path` are still applied (`:1272-1273`). The phone says "sent", and the TV signs in to the previous computer with the new share.
- **Stale claim:** `ManualAddressParser.kt:1-2,10` and `ManualAddressParserTest.kt:9` say `app.js` mirrors the parser and runs `VECTORS`. It actually mirrors `splitFolder` and runs `SPLIT_VECTORS` (`app.js:251`, `PhoneApiNetworkFolderTest.kt:445-477`).
- **Single owner:** `ManualAddressParser`, which is pure, in `:core` and already the §7.2 rules.
  - `splitFolder` should take `host[:port]` from `ManualAddressParser.parse(h)`, keeping only the three-field merge and the length caps.
  - `app.js` mirrors that.
  - **Minimal now:** drop the `\[…]` alternative from `HOST_OK` (Kotlin and JS), and fix the two stale comments.

### Q-U2. Found computers are sorted and "saved"-matched twice
- **Where:** `DiscoveryMerge.computers()` already sorts with `ORDER` and sets `saved` through `SavedHosts` (`DiscoveryMerge.kt:84-100`, `:143-168`). `SavedHosts` matches the address, `lastAddress` and the host name, with a trailing dot and `.local` stripped. `StoragePages.findComputers` (`StoragePages.kt:396-400`) then:
  - re-sorts with its own comparator;
  - recomputes `saved` from `locations.status` with a weaker rule (lower-cased host only: no `lastAddress`, no `.local`);
  - computes `isSaved` a second time per row (`:419`);
  - reimplements IPv4 ordering in `addressKey` (`:433`), duplicating `Ipv4.compareIpv4` (`DiscoveryMerge.kt:26`).

  Separately, `ComputerFinderImpl.norm` (`:180`) is a copy of `SavedHosts.norm`.
- **Cost:** two orders can disagree, e.g. a saved `nas.local` vs a found `NAS`. The comparator also lower-cases strings on every compare, on every `FindState` emission.
- **Fix:** the page uses `f.computers` in the order given and `c.saved` as-is, and drops `saved`, the comparator and `addressKey`. If a folder saved during an open search must badge at once, let the finder read the saved hosts per publish, not per search.

### Q-U3. The storage-toast `arg` is encoded in two places; location id → kind in two
- **Encoders:** `PlayerStateMachineImpl.storageToastArg` (`core/.../full/state/PlayerStateMachineImpl.kt:1454-1455`) and the inline `listOf(...).joinToString(PlayerText.ARG_SEP…)` in `PlayerViewModel.storageStopToast` (`ui/.../full/vm/PlayerViewModel.kt:466-467`). The decoder is `PlayerText.storageArg` (`PlayerText.kt:86-91`). The 4-field layout is spelled out in KDoc in four files.
- **Kind from id:** `PlayerStateMachineImpl.kindOfLocation` (`:1444-1448`) and `TimeshiftPlaces.current` (`StoragePageEnv.kt:256`).
- **Fix:** one `StorageToastArg(kind, second, name, jumped)` with `encode()`/`decode()` in `:core` next to `PLAYER_TOAST_ARG_SEP` (`PlayerContracts.kt:440`), and one `StorageConstants.kindOf(locationId)`.

### Q-U4. `StorageFailure` → `LocationFallback` mapping written twice
- **Where:** `FallbackRingStorage.kt:187-191` (ACCESS → SIGN_IN_NEEDED, FULL → NO_SPACE_THERE, else UNREACHABLE) and `StorageTargetsImpl.kt:145-150` (the same, plus REMOVED → UNREACHABLE, else UNSTABLE).
- **Fix:** `fun StorageFailure?.asFallback(otherwise: LocationFallback)` in `StorageContracts.kt`, used by both.

### Q-U5. "Is the remote pool small?" is decided in four places
- **Where:**
  - `StorageModule.kt:66-67` (memoryClass → I/O timeout);
  - `TimeshiftSessionFactoryImpl.kt:123` (memoryClass read) and `:387` (`pool >= RING_RAM_BLOCKS_EXTERNAL` → open deadline);
  - `StoragePageEnvImpl.kt:28-31` (memoryClass → the Test's small-pool note);
  - `StorageLimits.ramBlocks` (`StorageContracts.kt:82`).
- **Risk:** the I/O timeout, the open deadline and the page note can disagree if any one changes, which is lead ruling 5's coupling.
- **Fix:** one `fun smallRemotePool(memoryClassMb: Int)` on `StorageLimits` or `TimeshiftConstants` (or one `@Provides` boolean). Derive the I/O timeout, the open deadline and the note from it, and have the factory pass the pool flag rather than re-deriving it from `pool`.

### Q-U6. The wake write and the ring directory are built twice
- **Where:**
  - `SmbSessionKeeper.wake` (`SmbSessionKeeper.kt:406-416`, shared `WAKE_BYTES` `:466`) and `LocationTester.run` (`LocationTester.kt:57-60`, which allocates a fresh 4 KiB array per Test);
  - `ringDir` in `SmbSessionKeeper.kt:469-472` and inline in `LocationTester.kt:50`.
- **Fix:** `SmbSweeper` already owns the names (`wakeName`, `ringName`, `testName`). Add `ringDir(path)` and `writeWake(share, dir, deviceId)` there and call them from both.

### Q-U7. The tiny device-state file is written four ways
- **Where:**
  - tmp + rename with the same fallback: `DeviceIdentity.kt:26-29` and `LastGoodStore.put` (`SmbSessionKeeper.kt:80-83`);
  - direct, non-atomic `writeText`: `FileSignInToastCounters.setProcesses` (`app/.../FallbackToastPolicy.kt:65`) and `StorageLocationsImpl.markSeen` (`:292`).
- **Fix:** one `writeTextAtomically(file, text)` in `data/storage` (`:app` depends on `:data`), used by all four.

### Q-U8. IPv4 text → bytes written twice
- **Where:** `Port445Sweep.kt:133` and `NameLookup.kt:96-97`, which are identical. `Ipv4` (`DiscoveryMerge.kt:10-35`) has `parse`/`format` but no `bytes`.
- **Fix:** `Ipv4.bytes(s): ByteArray?`. That also removes the `bytesOf(address)!!` defaults (`NameLookup.kt:50,63`).

### Q-U9. `:ui` re-declares `:core`'s row ids and re-parses `:core`'s progress text
- **Where:** `SettingsRowViews.kt:65-66` `PROGRESS_ROW`/`SEARCHING_ROW` duplicate `StoragePages.ROW_T_PROGRESS`/`ROW_SEARCHING` (`StoragePages.kt:1406, 1377`). `SettingsUi.progressFraction` (`SettingsUi.kt:151-155`) re-parses "9 of 16 MB" with a regex it compiles on every call, next to the equivalent `PROGRESS` regex (`:132`).
- **Fix:** reference the `StoragePages` constants (`:ui` already imports `:core`), and hoist the regex. Better still, carry the fraction in the row: the page builds the string from `doneBytes`/`totalBytes` (`StoragePages.kt:669-679`), and parsing it back depends on the translation's word order.

### Q-U10. Smaller copies (one-liners each)
- **Three "FAILED" result factories with different sentinels:** `StoragePages.failed` (`:755`: `freeBytes 0`, `wifi true`, `atWallMs 0`), `NetworkFoldersImpl.failed` (`:316`: `-1`, real Wi-Fi, now) and `LocationTester.failed` (`:146`). Fix: `LocationTestResult.failed(error, wifi, atWallMs)`.
- **`StorageLimits` by kind:** `WpaPages.timeshiftRows` re-maps kind → limits (`WpaPages.kt:368-372`) instead of using `StorageLimits.of` (`StorageContracts.kt:117`).
- **"Unknown free space" sentinel:** `Long.MAX_VALUE / 4` in `SmbRingStorage.kt:236,242` and `WpaPages.kt:738` (`UNKNOWN_FREE`). Fix: one named constant.
- **Storage stop set:** `STORAGE_STOPS` (`TimeshiftSessionFactoryImpl.kt:668`) and the inline check in `StorageTargetsImpl.kt:92`.
- **`firstWriteMonoMs`/`beginCycle`:** written twice (`FallbackRingStorage.kt:69,98,124`; `VolumeStorage.kt:36-47`) and dispatched with `when` type switches (`TimeshiftSessionFactoryImpl.kt:370-375, 643-647`). Fix: a two-member interface.
- **NT status probes:** `NetworkFoldersImpl.isBadShareName` (`:309-311`) re-walks the cause chain for one NT status, where `SmbFaults.isSharingViolation` is the pattern. Fix: move it into `SmbFaults`.

---

## Efficiency

### Q-E1. Every viewer gets the SMB keeper's two threads and a 5-s watchdog wake-up after the first tune
- **Hot path:** the first `prepare` of the process.
  - `TimeshiftSessionFactoryImpl.targets` (`:127-129`) resolves `Provider<RingTargetSource>`, which builds `StorageTargetsImpl` and therefore `SmbSessionKeeper` (`StorageModule.kt:56-77`).
  - `KeeperTriggers.install` (`SmbSessionKeeper.kt:481-500`) then starts `box-smb` (via `start()`'s scheduled task) and the `box-smb-watch` executor, which runs `scheduleWithFixedDelay` every `SMB_WATCHDOG_MS / 3` = 5 s for the life of the process (`:275-283`).
  - It also registers a default-network callback and a process-lifecycle observer.
- **This happens with no network folder at all.** `TheBoxApplication` only pre-builds when one is chosen (`TheBoxApplication.kt:97-101`), but `prepare` builds it regardless.
- **Cost:** two idle threads, one timer wake every 5 s forever, and a connectivity callback on every network change, on a TV that may never use the feature.
- **Fix (minimal):**
  - Take the keeper as `dagger.Lazy<SmbSessionKeeper>` in `StorageTargetsImpl`, touched only in `smb()`, `onStorageFailure`/`onCreateFailed` (already SMB-gated) and `warm()`.
  - Start the watchdog from the first `linkFor` instead of `install`, or arm a one-shot check per `connectBlocking`.

### Q-E2. Every `storage` settings write decrypts every stored SMB password
- **Hot path:** `StorageLocationsImpl.onPrefs` (`:231-251`) runs on every `StorageDomain` emission: `choose`, `rename`, `recordTest` (now after every stored-folder Test, U-S5), `saveSmb` and `remove`. For each SMB location with a user it calls `blobs.getBlob` (Tink AEAD over a Keystore key), only to learn whether the password exists, then zeroes it.
- **Cost:** one Keystore-backed decrypt per location per write, plus a plaintext password in memory each time for no use.
- **Fix:** keep the per-id verdict. Re-check only ids that are new in `p.locations`, or whose sign-in changed (`signInVersion`). Or add a `hasBlob(ref)` to `SecureBlobStore` that does not decrypt.

### Q-E3. A retired link burns the whole reconnect budget on every ring I/O
- **Hot path:** `SmbRingStorage.reopen` (`SmbRingStorage.kt:359-403`) on the flusher and on Media3 loaders after a LINK_LOST.
- **Mechanism:**
  - After a sign-in or address change, the ring still holds the old link, which is now `retire()`d (`SmbSessionKeeper.kt:251`).
  - `link.reconnect` then throws `LinkUnavailableException("retired")` at once (`SmbjRemoteLink.kt:318-320`). `SmbFaults.classify` maps that to LINK_LOST (`SmbFaults.kt:68`).
  - `reopen` therefore sleeps `RECONNECT_RETRY_PAUSE_MS` (250 ms) and retries (`:373-382`) until `min(SMB_RECONNECT_BUDGET_MS, ioTimeoutMs)` (up to 6 s) passes.
  - A retired link never reconnects.
- **The non-retired path never reaches the pause loop:** `reconnect` waits in `awaitReady` until the deadline and throws only then (`SmbjRemoteLink.kt:426-439`), so `left <= 0` returns false. The 250-ms retry therefore has no case where it helps.
- **Cost:** after a relink, every write and every concurrent reader of that cycle stalls up to 6 s before UNREACHABLE. For the flusher that is the stall budget C-B1 cared about.
- **Fix:** add an additive `RemoteLink.retired: Boolean` (default false; `SmbjRemoteLink.isRetired` exists at `:352`) and `return false` from `reopen` and `retrying` when it is set. Then drop the pause/retry branch, or keep it only for an early throw that is not retirement.

### Q-E4. Regexes compiled per call
- **Where:**
  - `ManualAddressParser.parse` compiles `Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")` on every call (`:35`). It runs on each edit of "Enter a name or address" (validator `WpaPages.kt:773` → `StoragePages.validHost`) and on every phone submission.
  - `SettingsUi.progressFraction` (`:152`) compiles on every recomposition of the progress row.
- **Fix:** hoist both to `private val`. Small, but free.

**Checked and not worth changing on the hot paths:** see the last section (the `SmbRingStorage` and `UninterruptibleFileIo` buffers, the sweep, the Test progress rebuilds, the zap path).

---

## Hygiene

### Q-H1. `StoragePages.validHost` still has its stub fallback
- **Where:** `StoragePages.kt:1416-1421`: `try { ManualAddressParser.parse(…) } catch (e: Throwable) { return true }` ("non-blank while it is a stub").
- **Problem:** the parser is real. A bug that throws now silently accepts any text, and the catch also swallows `Error`s.
- **Fix:** call the parser directly. It has no throwing paths for a `String`.

### Q-H2. `SmbjRemoteLink` loose ends
- **`onConnected(current!!)`** (`:386`): reads the `@Volatile` after the lock is released. It is safe today only because every `dropCurrent` runs on `box-smb`. Fix: capture the share in a local inside `lock.withLock` (`:379-385`).
- **`SmbjOpenedShare.session`** (`:170`) is stored and never read (`grep -n 'session' SmbjRemoteLink.kt` → `:155-158`, `:170` only). Fix: drop the parameter.
- **`SmbjFile.closeNow`** (`:256`) wraps `closeSilently()`, which already swallows, in a second empty catch. Fix: keep one.
- **`lastFree()`** (`:405`) allocates a `Pair` whose `.second` nobody reads (`git grep -n 'lastFree('` → `SmbSessionKeeper.kt:449`, `.first` only). Fix: return `Long?`.

### Q-H3. The player reaches past its seam for the storage fields
- **Where:** `PlaybackControllerImpl.kt:3054`: `(timeshift as? TimeshiftSessionFactoryImpl)?.storageFields(ts.grant, out) ?: out`.
- **Problem:** any other `TimeshiftSessionFactory` (fakes, `NoTimeshiftFactory`) silently drops the §10 fields.
- **Fix:** add `fun storageFields(grant, snap) = snap` as a default method on `TimeshiftSessionFactory` (`player/.../internal/Seams.kt`) and call it without the cast.

### Q-H4. `NetworkFoldersImpl.folders` swallows a cancel in the NOT_FOUND recovery
- **Where:** `NetworkFoldersImpl.kt:265-269`: `runCatching { disk.folderExists(…) }` and `runCatching { folderNames(view.list(…)) }` catch everything.
- **Problem:** BACK during the "deepest existing folder" walk keeps probing up to N parents on `box-smb-browse` and returns a listing nobody wants. Every other path here rethrows via `cancelled(e)`.
- **Fix:** catch `Exception`, rethrow when `cancelled(e)`.

### Q-H5. Silent catches with no reason or log
- **`FallbackRingStorage.kt:201` and `:215`:** `catch (t: Throwable) { }` around a background `sec.delete()`. The sibling catches at `:163,:165,:193,:195` carry a comment. Fix: same comment, or `BoxLog.w` with the class name.
- **`StorageLocationsImpl.kt:279`:** `notifyListeners` drops a listener's `RuntimeException` silently. The listener is the keeper's `evaluate` trigger, so a bug there would be invisible. Fix: log the class name.
- **`StorageLocationsImpl.kt:292`:** `markSeen` fails silently, but A-N7 depends on that file. Fix: log the class name.

### Q-H6. Comments that describe abandoned paths
- **What:**
  - `ManualAddressParser.kt:1-2,10` and `ManualAddressParserTest.kt:9` (see Q-U1);
  - `SmbjClients` KDoc (`SmbjRemoteLink.kt:74`);
  - `StorageConstants.kt:1-2` ("tests assert against the names");
  - `TimeshiftModule.kt:32-35` and `InternalRingTargets.kt:1,18` ("while `:data` binds none");
  - `StorageModule.kt:1-2` ("falls back to InternalRingTargets when it is absent");
  - `SaveLocationFakes.kt:20-24`;
  - `Seams.kt:226` KDoc ("a grant built before WP-S2 copies the real choice in").
- **Fix:** update each along with the code changes above. No behaviour.

---

## Not worth changing (considered, and why it stays)

- **The strings are clean.** A script listed every `name=` in `strings_storage.xml` and `strings_storage_phone.xml`, and every resource name the diff added in `ui/` and `app/` res. Each one has a `R.string.<name>` or `@string/<name>` reference outside tests. `storage_phone_result_*` (U-N4) is deleted. Every `StorageText` key has a `StorageText.<KEY>` use in main code besides its `ResourceStorageTexts` mapping.
- **Append-only contract members with no producer yet stay:** `StorageKind.SAF_TREE`, `StoragePurpose.RECORDING`, `SaveLocation(purpose)`, `StoragePrefs.recordingLocationId`, `RECORDINGS_DIR_SMB` (§11, v2).
- **The debug aids are referenced:**
  - the `thebox-debug://settings?route=pauserewind|saveto|findcomputers` routes (`SettingsDebugProbe.kt:180-182`) are the documented manual entry points (design `:1902`);
  - `StorageDebugEntryPoint` is used by `app/src/androidTest/.../SaveLocationTest.kt:54` and below.
- **`StorageModule`'s `@BindsOptionalOf FallbackToastGate`** (`:41`) is needed. `:data`'s androidTest Hilt graph (`data/src/androidTest/.../HiltTestRunner.kt`) has no `:app` binding.
- **Test-only accessors stay:** `FallbackRingStorage.undecided`, `TimeshiftSessionFactoryImpl.currentStorage`, `StoragePages.openSessionCount`, `Port445Sweep.pendingCount`, `RingFlusher.liveThread`, `TsRingImpl.ringFlusher`, `SmbjRemoteLink.connects` and `isRetired`. Each is documented "(tests)" and costs a getter.
- **`FakeRemoteLinkTest` asserts on a fake by design.** It is the commit-0 contract of the fake that `SmbRingStorageTest`, `SmbFaultTest`, `TsRingOverSmbTest` and `:data`'s keeper tests rely on. It does not duplicate `SmbFaultsTest` (`:data`), which tests the real `SmbFaults.classify`.
- **`RingPoolWatcher` has one implementation.** It is a 1-method `:core` seam that keeps `TsRingImpl` free of `FallbackRingStorage`. Cheap; leave it.
- **The `runBlocking` in the keeper's password lambda** (`SmbSessionKeeper.kt:255`) runs on `box-smb`, never main, bounded by one `getBlob`. Making it suspend would push coroutines into `SmbjRemoteLink`'s blocking design. There is no `GlobalScope` anywhere in the feature.
- **`SmbRingStorage.pwrite`/`pread` keep the "one reused array" promise.** A direct buffer is staged into `writeScratch` (flusher only, grown once, `:191`) or a per-loader-thread `readScratch` (`:211`). Heap buffers go straight through. `retrying` is `inline`, so the lambda is not allocated. `Rates` is two `AtomicLongArray`s. Per call, only smbj's own request objects remain.
- **`UninterruptibleFileIo`:** per-thread scratch, one lock per handle. The reader and writer never contend. Only the JVM/Robolectric path uses it on a device that lacks native `Os`.
- **`FallbackRingStorage`:** one short-lived `box-smb-create` thread and one `ArrayBlockingQueue(1)` per SMB cycle, and the 10-ms poll runs only during the first-write decision. Not a steady-state cost.
- **Find page:**
  - The sweep is 32 coroutines on `Dispatchers.IO.limitedParallelism(32)` (`Port445Sweep.kt:82`), not 254 threads.
  - Name lookups are capped at 8 (`ComputerFinderImpl.kt:184`), and reverse DNS uses a 4-thread bounded pool (`NameLookup.kt:101-104`).
  - `merge.computers()` re-sorts at most ~254 entries per sighting under a `Mutex`, into a `conflate()`d flow.
  - The page-side re-sort is in Q-U2.
- **The Test's progress callback** emits `_changes` per 64 KiB block (`StoragePages.kt:735`), but `SettingsViewModel` conflates and throttles rebuilds to one per 250 ms (`SettingsViewModel.kt:116-128`, `REBUILD_MIN_GAP_MS = 250`). That is at most 4 page rebuilds a second; gating on the displayed MB would save little.
- **The zap path does no network I/O in `prepare` (§5 Must, confirmed):**
  - `StorageTargetsImpl.current` reads only the keeper snapshot (`:137-158`). `linkFor` constructs objects only. `LastGoodStore` reads one local file once. `StatFs` is local.
  - `RingTarget.Remote.open` → the `SmbRingStorage` constructor does no I/O (`:43-91`). `writeHeader` only records (`:248-250`), `allocate` is arithmetic (`:167`), and the `FallbackRingStorage` constructor does no I/O.
  - `SmbPrepareNoIoTest` asserts it.
  - The one caveat is Q-E1: the first prepare constructs the keeper graph (threads and binder registrations, not network).
- **`NetworkFoldersImpl.folders` lists the whole directory** (`view.list(p, Int.MAX_VALUE)`, `:255`) before the 2,000 cap. smbj's `DiskShare.list` returns the full list anyway, and the cap must apply after filtering to directories.
- **The `!!` uses in the feature that remain are proven non-null by the line before:**
  - `StorageLocationsImpl.kt:138` (filtered `kind == SMB`);
  - `SmbShareLister.kt:58` (filtered `name != null`);
  - `ComputerFinderImpl.kt:145` (checked on the same line; the smart cast is lost across modules);
  - `StoragePages.kt:567` (the `when` arm).

  They can stay, or become `?.let` while touching the code.
