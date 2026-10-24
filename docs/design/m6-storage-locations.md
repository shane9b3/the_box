# Save location for pause and rewind (this device, USB drive, network folder): design

Status: **proposed 2026-10-24; reviewed 2026-10-24.** Two reviews:
- an architecture/playback review (A, `scratchpad/storage/review-arch.md`): blockers A-B1…A-B4, should-fix A-S1…A-S14, nice-to-have A-N1…A-N7;
- a product/UX review (U, `scratchpad/storage/review-ux.md`): blockers U-B1…U-B4, should-fix U-S1…U-S10, nice-to-have U-N1…U-N6.

Every blocker is applied. Should-fix items are applied in place and marked "(reviewed 2026-10-24, A-Sn / U-Sn)"; where a lead ruling changed one, the mark says so. Nice-to-have items the lead named are applied and marked the same way. §19 lists what was not applied, and why. The proposal was `scratchpad/storage/proposal.md`; the API fact sheet is `scratchpad/storage/api-facts.md` ("facts §n"). The lead commits commit 0 (§17), after spikes S-1 and S-2 (§0a).

Owner request (2026-10-24, verbatim): "I would like to add a feature to add a save location for the auto record for rewind and pause, make sure we can use smb as well and be able to find other computers on teh network".

Inputs:
- `CLAUDE.md`.
- `00-master-plan.md`: §2 (v2 line 48, "internal, USB (SAF) and SMB (smbj)"), §3 ("Live TS transport" line 61, "Platform" line 72), §6.
- `design/m6-timeshift.md` ("TS §n"): §0, §3, §4, §8, §12, §13, §14, §15, §16, Appendix A, the implementation records and the 2026-10-20 audit follow-up.
- `design/m5-setup-settings.md` ("M5 §n"): §2.12 item 8, §3.1, §3.2, §3.3.13, §3.5, §3.15.
- `01-design-reference.md`.
- The code on `main` at `43370d9`.

smbj 0.15.0 and rapid7 dcerpc 0.12.13 sources were read under `scratchpad/storage/src/`, never run. `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` was run on 2026-10-24 for lead ruling 4 (§7.5). Anything verified by neither the fact sheet nor the code is in §21.

This document is the contract between implementers who will not see the conversation that produced it. **Must** means a reviewer rejects code that does otherwise. Every number is a default that lives in one named constant (Appendix A).

Units:
- Sizes on screen are **design px** (1 design px = window width ÷ 1920).
- Times are in ms; rates are in bytes/s.
- Inside the ring, every time stays `MonoClock` (TS §0).

**Words on screen** (TS "Words on screen", lead ruling 5 of 2026-10-19, unchanged):
- The viewer never reads "buffer", "ring", "segment" or "live edge".
- New viewer words: *save to*, *this device*, *USB drive*, *network folder*, *computer*, *shared folder*, *sign in*.
- "SMB" appears once, in the detail of "Add a network folder", because NAS owners search for that word (reviewed 2026-10-24, U-S5).

---

## Lead sign-off (2026-10-24)

**Owner rulings (fixed):**
1. If the folder is unreachable when a channel is tuned, rewind saves on this device, with one toast.
2. The 445 sweep of the local /24 is allowed only while "Find computers" is open.
3. USB is in scope when the device lists the drive (`getExternalFilesDirs`).
4. Phone sign-in is **in scope now**.

**Lead rulings:**
1. Every blocker is accepted as the reviewers wrote it:
   - A-B1: no I/O anywhere on the factory/zap path, including `allocate`/`close`/`delete`. The no-I/O test drives the real factory.
   - A-B2: a dedicated flusher thread per remote ring (a commit-0 contract change).
   - A-B3: an interrupt is a cancel, never link loss.
   - A-B4: the RAM pool is the only stall budget. The I/O timeout is about 8 s. Reconnect happens only on real transport errors. After any reconnect the next cycle gets a fresh file (new name). A start-of-cycle grace means no demotion. A disk-wake write runs when the keeper (re)connects.
   - U-B1: the phone sign-in design.
   - U-B2: a `LocationDetail` page.
   - U-B3: one folder route, with the path in the browse session.
   - U-B4: the current type sizes 28/26/22/18, nothing under 18.
2. U-S1 is accepted in A-B4's form: **the tune never waits.**
   - If the SMB location was last reachable within `SMB_LAST_GOOD_MS` (last-known-good), the cycle selects SMB. It writes into the RAM pool, and the dedicated flusher opens the file lazily.
   - If the open has not succeeded before the pool would run out, that cycle falls back per owner ruling 1.
   - Otherwise this device is chosen at tune time.
3. Discovery: **WS-Discovery is cut.** v1 is NsdManager `_smb._tcp`, the 445 sweep on the Find page (the `LanAddressFinder` rule, VPN-aware per U-S2), NetBIOS node-status / reverse DNS names, and manual entry or paste. The multicast lock is held for the whole Find page.
4. Share listing: keep rapid7 dcerpc if the dependency report confirms Guava is already on the runtime classpath through Media3; otherwise keep it anyway and record the measured APK delta. **Confirmed (§7.5).** A typed share name is always offered. bcprov stays.
5. Memory (A-S7): the remote (SMB/USB) pool is 160 blocks when `memoryClass` ≥ 192 MiB. Below that it is 96 blocks, the hiccup gate is waived on those devices, and the Test verdict says so. One pool size per cycle, fallback included (no reallocation on fallback). Internal stays 64/32.
6. A separate `SMBClient` instance for the ring, always `close(true)` (A-S1). The keeper owns liveness; the storage reports transport errors to it; one owner.
7. Test verdict per U-S3: time-bounded at 6 s, graded against HD and 4K rates, an RTT cap, a Stop test row, "Use anyway", and the plain-words table from the review.
8. Take U-S2…U-S10 and A-S2…A-S14 as written unless they conflict with these rulings.
   - Take the cheap nice-to-haves: rename a location (U-N1), the server-time sweep (A-N2), drop the 100 ms ready wait (A-N4), the multicast lock span (A-S10), "Looking on 192.168.1.x" (U-N2), and the FAT32 cap shown up front (U-N6).
   - Phone sign-in per A-S12 + U-B1: a one-shot POST; the password is never mirrored or logged; the buffer is zeroed; a plain-HTTP note; a separate local account recommended on Windows, worded neutrally.
9. Work packages are disjoint by file. Commit 0 holds every contract change (A-S5, A-S13), and the phone WP has an explicit owner.
   - The tools WP's first task is Homebrew Samba on port 4445, unprivileged, with an `smbclient -L` exit check.
   - The fallback is macOS File Sharing via `10.0.2.2` (A-S14).

---

## 0. Key decisions at a glance

1. **One location model for rewind now and v2 recordings later.**
   - A `StorageLocation` (id, kind, name, address, never the password) lives in a new backed-up settings domain `storage`.
   - Kinds: `INTERNAL` (implicit), `VOLUME` (USB drive or SD card) and `SMB` (a folder in a computer's shared folder). `SAF_TREE` is reserved for v2.
   - A purpose (`TIMESHIFT` now, `RECORDING` later) picks one location each.
2. **USB goes through `getExternalFilesDirs()` only, never SAF.** It needs no permission, gives a real fd, and today's `AndroidRingStorage` runs as is.
   - No preallocation (FAT zero-fills when a file grows).
   - Capped below the 4 GiB FAT32 file limit.
   - Shown only when the device lists a drive (owner ruling 3).
3. **SMB is a new `RingStorage`, `SmbRingStorage` over smbj 0.15.0**, behind a pure-JVM `RemoteLink`/`RemoteFile` seam. Ring, index, writer, reader and the TS §4.4 `RecordStop` table are unchanged; no new `RecordStop`.
   - No I/O in construction, `writeHeader`, `allocate`, `close` or `delete` (A-B1).
   - Own flusher thread per remote ring (A-B2).
   - The file is created once, by the flusher's first write. Reopens never truncate (A-S2).
   - Interrupts are cancels (A-B3).
   - Reconnect only on transport errors (A-B4).
4. **The tune never waits** (lead ruling 2; A-N4: no ready wait).
   - `prepare` does zero network round trips (Must, tested on the real factory).
   - SMB is chosen when the keeper is READY, or when it is last-known-good with no failure seen in this process.
   - A `FallbackRingStorage` switches the cycle to this device if the folder has not taken its first write by `SMB_OPEN_DEADLINE_MS`. That is before the pool runs out, and nothing has reached the folder yet.
5. **The pool is the stall budget** (A-B4, lead ruling 5). The remote pool is 160 blocks (10.0 MiB ≈ 10 s at 1 MB/s) when `memoryClass` ≥ 192 MiB, else 96.
   - `SMB_IO_TIMEOUT_MS` (8 s) sits just inside the 160-block pool.
   - A slow write and a full pool both end as one SLOW path: RAM-only for the cycle, live TV continues.
   - A cycle that reconnected uses a fresh file name next time (stale-write hazard, A-B4).
6. **No hot switch after the first write.** A drop mid-cycle ends saving for that cycle (TS §4.6), and the folder is demoted, except within the start-of-cycle grace. The next channel change saves on this device until the folder is healthy.
7. **Limits per kind.**

   | Kind | Cap | Reserve | Daily wear cap | Auto length |
   |---|---|---|---|---|
   | This device | today's 8 GiB | today's | yes | today's |
   | USB | < 4 GiB | 512 MiB | no | 1 h |
   | SMB | 32 GiB | 2 GiB | no | 1 h |

   Reserves are enforced through `availableBytes()` (A-S4).
8. **Several boxes on one share:** `TheBox-rewind/ring-<deviceId>-<pid>-<n>.bin`. Each box sweeps its own stale files at start. Another box's files go only after 48 h by **server** time, judged against this box's own `.wake` file (A-N2).
9. **Find computers:** NsdManager `_smb._tcp` + a 445 sweep of the Wi-Fi/Ethernet /24 (never the VPN), with names from NetBIOS node status, then reverse DNS. Manual entry or paste always works. The multicast lock is held for the page's lifetime. WS-Discovery is cut (lead ruling 3).
10. **Shares via dcerpc `ServerService.getShares1()`, folders via `DiskShare.list`.** Sign-in tries anonymous, then guest, then asks. A typed share name is always offered.
11. **Phone sign-in** (owner ruling 4; U-B1, A-S12): a new `PhonePurpose.NETWORK_FOLDER`, bound to one browse session.
    - One POST of `/api/network-folder`: the password goes straight into a `CharArray` and is never mirrored or logged.
    - The first successful sign-in ends the session.
    - Plain-HTTP note on both screens.
12. **Credentials:** user name and domain in the location; the password only in `SecureBlobStore` (`smb-<id>`), never in backups. They are registered with the export audit (A-S11).
13. **Test:** time-bounded (6 s write, then read back), graded against HD (1 MB/s) and 4K (3.5 MB/s), with an RTT cap. The verdict is in plain words, there is a Stop test row and "Use anyway" (lead ruling 7), and the disk wake-up time is reported separately (A-B4).
14. **Settings: the simple-row total stays at 35.** "Pause and rewind" becomes a Link to a sub-page (Rewind length, Save to, two advanced rows). Locations get a detail page (U-B2), the folder browser is one route (U-B3), and every size is the current kit's (U-B4).

---

## 0a. Spikes, before any code (lead + WP-S5)

| Spike | Device | Question | Decides |
|---|---|---|---|
| **S-1** USB | Fire TV Stick 4K, 4K Max, Onn 4K | does `getExternalFilesDirs(null)` list a FAT32 and an exFAT stick? `StorageManager.getStorageVolume(dir).uuid` stable across replug? append-growth speed on each? `fstat` nlink = 1 after create and 0 after `adb shell rm` (A-S8)? Does `ACTION_MEDIA_EJECT` arrive before the unmount, or only `BAD_REMOVAL` (A-S8)? | `StorageFlags.VOLUME_ENABLED`; `VolumeStorage.nlinkReliable` per device |
| **S-2** SMB release build | API 25 emulator, Fire OS 6 stick | a release (R8) build: NTLM sign-in, `getShares1()`, list, write, read against Homebrew Samba and macOS smbd (facts §1.3 #538, #807); the APK delta of smbj + bcprov + asn-one + mbassador and of the dcerpc set (§7.5); flusher CPU with signing on the stick (U-5) | keep rules, budgets, §14 CPU gate |

If S-1 finds no listed drive on any of the three devices, USB ships with `VOLUME_ENABLED = false`. §3.2 then waits for v2's SAF work, and nothing else in this design changes.

---

## 1. What exists today (verified in the code)

| Fact | Where |
|---|---|
| `RingStorage`: `capacityBytes`, `allocate(upToBytes)` (file bytes), `pwrite`/`pread(ByteBuffer, filePosition)`, `isUnlinked()`, `availableBytes()`, `writeHeader`, `delete()`; "never called on main" | `core/.../timeshift/TimeshiftContracts.kt:46-65` |
| `TsRingFactory.create(storage, clock, ramBlocks, indexEntries, lengthMs)` | `TimeshiftContracts.kt:108-110` |
| One shared `box-ts-flush` daemon thread loops `flushStep()` over every registered ring (head-of-line blocking, A-B2); `RingFlusher` takes a thread name | `RingFlusher.kt:15-66` |
| The production `TsRingImpl` constructor always uses `RingFlusher.shared`; the internal one takes a flusher | `TsRingImpl.kt:58-69` |
| The flusher calls `isUnlinked()` every `NLINK_CHECK_MS` and then `storage.close()`; a `pwrite` `IOException` → `DISK_ERROR` | `TsRingImpl.kt:471-534` (`:502-505`, `:519`, `:524`) |
| Growth halts at `availableBytes() < FREE_SPACE_RESERVE_MIN / 2` (a fixed 512 MiB, A-S4) | `TsRingImpl.kt:543-561` (`:547`) |
| `claim` waits for a flushed block at most `PUMP_BLOCK_MAX_MS` (checked on `System.nanoTime`, A-S9), then `DISK_STALLED` → `DISK_TOO_SLOW` | `TsRingImpl.kt:200-212`; `RingWriterImpl.kt:305-306` |
| The first disk stop wins; the ring is RAM-only for the generation; the saved file part stays readable while pinned | `TsRingImpl.kt:262-277`, `:41-44` |
| Header written in the ring's constructor and on every `reset()`; `reset()` reuses offsets from 0 | `TsRingImpl.kt:146-151`, `:321-337` |
| The factory calls `closeRingLocked` (→ `st.delete()`/`st.close()`) and `st.allocate(...)` **inline** in `prepare` (A-B1) | `TimeshiftSessionFactoryImpl.kt:182-187`, `:309-325` |
| Reuse at a zap when capacity/index/length match and the storage is usable | `TimeshiftSessionFactoryImpl.kt:176-179` |
| The controller awaits `prepare` right after the resolve, so a slow `prepare` delays the first frame | `PlaybackControllerImpl.kt:769-781`, `:2271-2300` (`:2288`) |
| `AndroidRingStorage`: `posix_fallocate` else sparse `setLength`; `fstat` nlink; `onWritten` feeds the daily counter; no reopen after close | `player/.../timeshift/AndroidRingStorage.kt:77-204` |
| Internal location and sweep (`cacheDir/timeshift/ring-<pid>.bin`) | `TimeshiftStorageImpl.kt:48-101` |
| Pool 64 / 32 blocks by `isLowRamDevice` | `TimeshiftConstants.kt:24-27`; `TimeshiftSessionFactoryImpl.kt:78`, `:187` |
| `memoryClass` already read for the device class | `player/.../engine/DeviceCapabilities.kt:35-39` |
| `LocalBufferState`; `LocalBufferInfo`; `PlayerToast` (append-only, `arg` joined by U+001F) | `PlaybackApi.kt:106-130`; `PlayerContracts.kt:216-229`, `:385-415` |
| Storage toasts chosen in the machine | `PlayerStateMachineImpl.kt:1443` |
| Rows `playback.timeshift` (SIMPLE), `_hold`, `_protect` (ADVANCED), built by `WpaPages.timeshiftRows` | `PlaybackRows.kt:41-56`; `WpaPages.kt:295-327`, `:509-516` |
| Tests that look the three rows up on the Playback page (U-S6) | `WpaPagesTest.kt:55-63`, `SettingsBackLadderTest.kt:151-158` |
| Simple rows = 35; sub-page sources not counted | `SimpleRowCountTest.kt:18-33` |
| Settings kit sizes in code: label 28, value 26 (mono 26), description 22, option 26/22, notice 24, card title 36, badge mono 18 (U-B4) | `ui/.../settings/SettingsUi.kt:78-91` |
| `RowKind.Text(masked)`; `TextHint` has no HOST; `Badge {LOCKED, HIDDEN, ADULT, FAILED, NEW, DEFAULT}` | `SettingsContracts.kt:71-88` |
| `PhonePurpose {SETUP, RESTORE, BACKUP_DOWNLOAD, LOGS_DOWNLOAD}`; `PhoneSubmission` variants (`Passphrase(CharArray)` prints `****`); `PhoneLink.start(purpose)` | `SettingsContracts.kt:49`; `core/.../setup/lan/LanContracts.kt:7-50` |
| `PhoneApi`: routes per purpose (`onlyFor`), `Redact.registerTransient`, `accepted()` zeroes a refused passphrase; `readJson` copies the body through a `ByteArrayOutputStream` | `core/.../setup/lan/PhoneApi.kt:83-90`, `:126-127`, `:145-182`, `:226-262` |
| `PAIR_MAX_WRONG` 5, `PAIR_IDLE_MS`, `PAIR_MAX_MS` | `core/.../setup/lan/LanLimits.kt:10-12` |
| `LanAddressFinder.find()` = a private IPv4 of Wi-Fi/Ethernet; `vpnActive()`; `LanAddressPolicy.isUsable` (RFC 1918 only) | `core/.../setup/lan/LanAddressFinder.kt:1-23` |
| Phone page assets | `data/src/main/assets/phone/app.html`, `app.js`, `app.css` |
| Export audit collects registered secrets | `data/.../diag/DiagnosticsImpl.kt:118-128` |
| `SecureBlobStore` (Tink AEAD, AD = ref); reserved refs | `data/.../contracts/DataContracts.kt:88-107` |
| `:player` → `:core` only; `:data` → `:core`; `:app` → all | `player/build.gradle.kts:28`; `data/build.gradle.kts:56`; `app/build.gradle.kts:132-136` |
| Guava `33.3.1-android` on `releaseRuntimeClasspath` via `media3-common:1.11.1`; no commons-lang3/commons-io/bcprov/smbj; `lifecycle-process` 2.10.0 present via other modules but not declared by `:data` | `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` (2026-10-24) |

---

## 2. Architecture

```
 Settings ─ StoragePages ─ StorageLocations (data) ─ storage domain (JSON) + SecureBlobStore (password)
     │            │
     │            ├─ ComputerFinder: NSD + 445 sweep (Wi-Fi/Ethernet /24) + NBNS/reverse-DNS names   (Find page only)
     │            ├─ SmbBrowser (browse SMBClient): shares (dcerpc srvsvc) + folders + sign-in
     │            ├─ LocationTester (browse SMBClient): 6 s write/read, CRC, wake time, RTT
     │            └─ PhoneSetup(NETWORK_FOLDER) ─ PhoneApi POST /api/network-folder → BrowseSession (CharArray)
     │
 app start (after first frame) / ON_START / preview start / network change
     ▼
 SmbSessionKeeper (box-smb) ─ owns SmbLink(location) on the ring SMBClient: state, epoch, open-file refcount,
                              keepalive, free-space cache, .wake write, own-file sweep, demotion, last-known-good
     │
 PlaybackController ─ prepare (IO, ‖ resolve) ─► TimeshiftSessionFactoryImpl
     │   targets.current(TIMESHIFT): non-blocking, no I/O (Must)
     ├─ INTERNAL → AndroidRingStorage(cacheDir/timeshift)                                  shared box-ts-flush
     ├─ VOLUME   → AndroidRingStorage(<vol>/…/files/timeshift, preallocate = false)          own box-ts-flush-ext
     └─ SMB      → FallbackRingStorage(primary = SmbRingStorage(link, …), secondary = internal on demand)
                                                                                            own box-ts-flush-net
                    first pwrite: create (≤ SMB_OPEN_DEADLINE_MS) else switch to internal (nothing written yet)
                    pread on Media3 loader threads; transport errors → link.reconnect(epoch) (single flight)
                                          ▼
                         TsRingImpl / RingWriterImpl / RingReaderImpl   (unchanged apart from the flusher choice)
```

**Ownership.**
- `StorageLocations` (`:data`, singleton) owns the location list, the per-purpose choice, the passwords and the status per location.
- `SmbSessionKeeper` (`:data`, singleton) owns **the only** `SmbLink` for the location chosen for `TIMESHIFT` (reviewed 2026-10-24, A-S1; lead ruling 6). The link holds the state, the epoch and a reference count of open files.
  - The keeper only *asks* the link for checks. A keepalive failure goes through the same `reconnect(epoch)` as a storage failure.
  - The ring's link uses its **own `SMBClient` instance**. `SmbBrowser` and `LocationTester` use a second one, so a test never shares, or closes, the ring's TCP connection (smbj caches and leases connections per `host:port`, `SMBClient.java:86-107`).
  - Teardown is always `Connection.close(true)`, because `close(false)` only drops a lease (`Connection.java:177-180`).
- `SmbRingStorage` borrows the link and owns one file handle. Closing it releases its reference, never the link.
- `TimeshiftSessionFactoryImpl` (`:player`) still owns the process's one ring and now remembers its `locationId` and its storage.

**Module placement.** `:player` cannot see `:data`. The seam is pure-JVM contracts in `:core`:
- `RingTargetSource`, `RemoteRingStorage` and `RemoteLink`, implemented in `:data`;
- `VolumeCatalog`, implemented in `:player`.

`:player` declares `@BindsOptionalOf RingTargetSource` and uses `InternalRingTargets` (commit 0) when nothing is bound; `:data`'s `StorageModule` binds the real one (reviewed 2026-10-24, A-S5). smbj, dcerpc and bcprov are `:data` dependencies only. `SmbRingStorage` and `FallbackRingStorage` are pure JVM in `:core`, so they run in JVM tests with `TsRingImpl`.

**Threads** (Must; each is a §14 budget):

| Thread | Owner | Work |
|---|---|---|
| `box-smb` | `:data`, single-thread executor | keeper and link control: connect, authenticate, keepalive, `.wake` write, free space, sweep, deferred CLOSE/`rm`. Never main |
| `box-smb-browse` | `:data`, single-thread executor | browser and Test |
| smbj packet reader | smbj | one per `Connection` (facts §1.6): the ring connection's, and the browse one's while browsing |
| `box-ts-flush-net` / `box-ts-flush-ext` | the ring | **one per remote/USB ring**, exits when the ring closes (reviewed 2026-10-24, A-B2): SMB `pwrite`, the lazy create, the deferred header |
| `box-ts-flush` | existing | internal rings only |
| Media3 loader threads | existing | SMB `pread` |
| `box-find` | `:data`, pool of `SWEEP_PARALLEL` | alive only while the Find page is open |

---

## 3. Locations

### 3.1 Model

| Kind | Root | Time-shift folder | v2 recordings |
|---|---|---|---|
| `INTERNAL` (id `internal`, never stored) | `context.cacheDir` | `timeshift/` (today's) | v2 decides |
| `VOLUME` (id `vol-<uuid>`, stored once chosen) | `getExternalFilesDirs(null)[i]`, i > 0, removable | `timeshift/` | v2 decides (app-specific dirs are wiped on uninstall, §11) |
| `SMB` (id `smb-<uuid>`, made on save, never reused) | `\\host\share\path` | `TheBox-rewind/` | `TheBox-recordings/` (v2) |

Every location has a user-editable **name** (reviewed 2026-10-24, U-N1). It defaults to the computer name ("NAS") or the volume label, is capped at `LABEL_MAX_CHARS`, and is what toasts and values say.

### 3.2 USB: app-specific directory, no SAF (owner ruling 3)

- **Listing.** `Context.getExternalFilesDirs(null)`, entries with index > 0 that are:
  - non-null;
  - `Environment.getExternalStorageState(dir) == MEDIA_MOUNTED`;
  - `Environment.isExternalStorageRemovable(dir)`.

  The uuid and label come from `StorageManager.getStorageVolume(dir)` (API 24+). A device that does not list a stick shows no USB option, never a dead end. "Format as internal" (adopted) storage is already internal. NTFS is not mounted by Android (facts §5).
- **Why not SAF:** the tree picker may not resolve on Android TV, and Fire OS 8's is hard to drive with a remote (facts §5); the M5 integration found "the TV image has no DocumentsUI" (M5 §1.11); the fd of a non-primary provider may not be seekable (§18 R1).
- **No preallocation.** `AndroidRingStorage` gains `preallocate: Boolean`. For `VOLUME` it is false: `allocate(upTo)` records and returns `upTo`, with no I/O. The file grows only by sequential writes, so there are no holes before the first wrap. The free-space check before each extent (`TsRingImpl.kt:547`) still halts growth. `ENOSPC` at write → `DISK_ERROR`.
- **FAT32.** The cap is `VOLUME_MAX_RING_BYTES`, the largest whole-block size whose file stays ≤ 4 GiB − 1 (65,648 blocks = 4,294,954,752 bytes), whatever the filesystem (§18 R6).
  - It is shown up front (reviewed 2026-10-24, U-N6): with a USB drive chosen, the "2 hours" option of Rewind length carries the detail "Up to 1 h 9 min on this drive". Auto reads "Auto (1 h 9 min: USB drive is limited)" when the cap shortens it (at the assumed 1 MB/s; recomputed from the measured rate as today).
- **Removal** (reviewed 2026-10-24, A-S8). `VolumeWatcher` registers at runtime (context receiver, scheme `file`) for `ACTION_MEDIA_EJECT`, `_UNMOUNTED`, `_BAD_REMOVAL` and `_REMOVED`. When the volume holds the ring:
  - the receiver calls `ring.stopRecording(STORAGE_EVICTED)` (cheap, takes a lock), then posts `storage.close()` to `box-ts-discard` **at once**. Never close on main: a close racing a `pread` stuck on a vanishing device can block in the kernel. vold kills processes that keep files open on a volume it unmounts.
  - Readers behind live get `RingIoException` → live, with the USB wording (§10).
  - `ACTION_MEDIA_MOUNTED` re-runs the volume sweep (§3.5).
- **nlink.** If S-1 shows `fstat` nlink is not 1 right after create on a device, `VolumeStorage.nlinkReliable = false` for that storage, and `isUnlinked()` answers only from an explicit eject (reviewed 2026-10-24, A-S8).

### 3.3 SMB network folder

- **Address:** `host` (name or IPv4), `port` (445), `share`, `path` (`/`-separated in the model, `\` for smbj), `user`, `domain`, `guest`.
- **Ring folder:** `<path>/TheBox-rewind/`, created on first use.
- **Dialects:** smbj's defaults, 3.1.1 → 2.0.2. SMB1 is impossible (facts §1.4) and gets its own error (§7.7).
- **Authentication:** NTLM only on Android (facts §1.4).

### 3.4 Limits per kind (`StorageLimits`, values in commit 0, A-S13)

| Kind | `maxRingBytes` | reserve | Auto length | Wear cap / counter | Preallocate | Pool (`memoryClass` ≥ 192 MiB / below) |
|---|---|---|---|---|---|---|
| `INTERNAL` (chosen) | `MAX_RING_BYTES` 8 GiB | `max(1 GiB, total × 0.10)` | 30 min ≤ 16 GB, else 1 h | yes / yes | yes | 64 / 32 (`isLowRamDevice`, today's) |
| `VOLUME` | `VOLUME_MAX_RING_BYTES` (< 4 GiB) | `VOLUME_FREE_SPACE_RESERVE` 512 MiB | 1 h | no / no | **no** | `RING_RAM_BLOCKS_EXTERNAL` 160 / `_SMALL` 96 |
| `SMB` | `NETWORK_MAX_RING_BYTES` 32 GiB | `NETWORK_FREE_SPACE_RESERVE` 2 GiB | 1 h | no / no | **no** (no SET_INFO either, A-S6) | 160 / 96 |
| `INTERNAL` as the fallback of a remote choice | as `INTERNAL` | as `INTERNAL` | as the chosen kind's | **yes** / yes | yes | **as the chosen kind** (one pool per cycle, lead ruling 5) |

**Reserve enforcement** (reviewed 2026-10-24, A-S4). The flusher halts growth at `availableBytes() < FREE_SPACE_RESERVE_MIN / 2` (`TsRingImpl.kt:547`, unchanged). So a non-internal storage reports `availableBytes() = free − (reserve − FREE_SPACE_RESERVE_MIN / 2)`, clamped at 0, and the per-kind reserve holds while the ring grows. This is written into the `RingStorage` KDoc in commit 0.

**Why:**
- The wear cap protects the box's eMMC (TS §8.4). USB and NAS wear belongs to a device the owner chose, so `onWritten` is wired for `INTERNAL` only.
- The network cap is lifted because 2 h of a 4K channel at 3.5 MB/s needs about 29 GiB. The length options stay 15 min … 2 h (owner Q3, §20).
- The SMB reserve is flat: 10 % of a 12 TB NAS would be 1.2 TB.

### 3.5 File names and sweeps

| Kind | Ring file | Sweep (who, when) | Deletes |
|---|---|---|---|
| `INTERNAL` | `cacheDir/timeshift/ring-<pid>.bin` (today's) | today's | today's |
| `VOLUME` | `<vol>/…/files/timeshift/ring-<pid>.bin` | `VolumeWatcher`, app start and every `MEDIA_MOUNTED`, on `box-ts-sweep` | every other file there (app-private, one device) |
| `SMB` | `<path>/TheBox-rewind/ring-<deviceId>-<pid>-<n>.bin`, where `n` counts the files this process made at that location (A-B4, §4.5) | `SmbSessionKeeper`, the first READY of each process, on `box-smb`, **after** the `.wake` write | (a) `ring-<deviceId>-*` and `test-<deviceId>-*` not belonging to the current process's open file; (b) another device's `ring-*`/`test-*` whose own LastWriteTime is older than our just-written `.wake-<deviceId>` file's LastWriteTime by more than `FOREIGN_STALE_MS` (48 h). That is server time against server time, immune to clock skew (reviewed 2026-10-24, A-N2). Never `.wake-*` of other devices younger than 48 h. A failed delete is ignored and retried at the next start |

- **`deviceId`** (`DeviceIdentity`, `:data`): 12 lowercase hex digits from `SecureRandom` in `noBackupFilesDir/device-id`. It is never backed up, so two boxes restored from one backup differ. The pid alone collides across devices.
- **Live files of other boxes are protected only by the 48 h rule** (reviewed 2026-10-24, A-N2 correction). The ring is opened with `FILE_SHARE_DELETE` (§4.1), so a delete of another box's live file would *succeed* (delete-pending). That is why (b) is the only foreign rule and why it is time-based.
- The SMB sweep never blocks a tune: names are unique per process, and `prepare` never waits for it.

### 3.6 Header

`RingHeaderCodec` v1 is unchanged; nothing reads a ring header back (TS §7.5).
- **SMB:** `writeHeader(gen, cap)` stores `gen` and `cap` in two volatiles. The flusher encodes and writes the header before its next data write. There is no shared buffer between threads (reviewed 2026-10-24, A-N3).
- **USB:** today's synchronous `writeHeader` (a local file).

---

## 4. `SmbRingStorage`

### 4.1 Mapping (reviewed 2026-10-24, A-B1, A-S2, A-S6)

`SmbRingStorage(link: RemoteLink, relativePath: String, capacityBytes: Long, limits, clock)` implements `RemoteRingStorage` (§12.1).

| Call | Thread | Behaviour |
|---|---|---|
| construction | IO (`prepare`) | records the fields. **No I/O** (Must) |
| `writeHeader(gen, cap)` | IO (ring constructor, `reset`) | sets the two volatiles and `headerDirty`. **No I/O** |
| `allocate(upToBytes)` | flusher | returns `min(upToBytes, HEADER + capacity)`. **No I/O, ever**: sequential writes extend the file, and readers never read past `flushed` (A-S6) |
| `create(deadlineMonoMs): Boolean` | flusher, called by `FallbackRingStorage` before the first `pwrite` (§5.2) | **once per storage** (`created` set on success):<br>1. `link.share(deadline)` (waits for READY no later than the deadline);<br>2. `mkdirs(TheBox-rewind)`;<br>3. `openFile(path, {GENERIC_READ, GENERIC_WRITE, DELETE}, {NORMAL}, {SHARE_READ, SHARE_WRITE, SHARE_DELETE}, FILE_OVERWRITE_IF, {NON_DIRECTORY_FILE, RANDOM_ACCESS})`;<br>4. the header.<br>False on any failure or when the deadline passes; `lastFailure` is set |
| `pwrite(src, pos)` | flusher | requires `created` and `src.hasArray()` (`BlockPool` always gives an array, `BlockPool.kt:35-40`; A-S6). Writes the header first if dirty, then `File.write(src.array(), pos, src.arrayOffset() + src.position(), src.remaining())`. Failures: §4.3 |
| `pread(dst, pos)` | Media3 loader | requires a handle. A reader **never opens or creates** (A-S2): no handle and not reconnecting → `IOException`. Loops `File.read(array, …)` until filled; −1 → returns the count (`TsRingImpl.readFile` turns a short file into `IOException`, `:464`). `dst` without an array (the JVM direct staging, `RingReaderImpl.kt:166`) goes through one reused heap array |
| `isUnlinked()` | flusher | the `gone` flag. **No I/O** |
| `availableBytes()` | flusher (per extent / `FREE_SPACE_CHECK_MS`) | the keeper's cached `getCallerFreeSpace()` when younger than `SMB_SPACE_MAX_AGE_MS`, else one bounded live query that refreshes the cache. Minus the reserve offset (§3.4). A failure answers 0 (growth halts) |
| `close()` | any (the factory under `ringLock`) | sets `closed` and `gone` **at once**, then posts the handle CLOSE and the link reference release to `box-smb`. **Never waits** (Must, A-B1) |
| `delete()` | any | `close()`, then posts `rm(path)` to `box-smb`. Never waits. A failure is logged as a fault name and left to the sweep |

**Reopen after a reconnect** (§4.3) uses `FILE_OPEN`, never `OVERWRITE_IF`, so the saved part is never truncated. `ReopenNeverTruncatesTest` checks it (A-S2).

**Full sharing is deliberate** (facts §1.8). After a silent drop, the server may keep our old handle until its own timeout. An exclusive reopen would then fail with `STATUS_SHARING_VIOLATION`.

No `FILE_DELETE_ON_CLOSE` on the ring (§18 R9).

### 4.2 Threads, concurrency, flusher

- **The flusher.** A remote or USB ring gets a dedicated `RingFlusher` (reviewed 2026-10-24, A-B2):
  - `RingStorage.blocking: Boolean` (commit 0, default false) is true for `SmbRingStorage`, `FallbackRingStorage` and the `VOLUME` storage;
  - `TsRingImpl`'s production constructor then makes `RingFlusher("box-ts-flush-net" | "-ext", ownedBy = this)`;
  - that flusher thread exits once its ring is unregistered and closed.
  - An SMB stall therefore never delays the internal ring of the next cycle. The old flusher may still be inside the old ring's `pwrite`, but it no longer shares a thread with anything else.
- **Releasing a closed storage.** `SmbRingStorage` checks `closed` before each retry and before waiting on a reconnect, so a closed storage frees its flusher within one I/O timeout.
- **Concurrent I/O.** Flusher writes and reader reads run concurrently on one handle. smbj matches responses by message id (facts §1.6), and a 65,424-byte block needs one credit even on SMB 2.0.2 (facts §1.5). This is checked by `SmbConcurrencyTest` (§16.2; U-1).
- **Copies** (accepted for SMB; gates in §14): the write path makes one smbj packet-buffer copy per request; the read path allocates `response.getData()` and copies (facts §1.6). That is about 2 MB/s of garbage at 1 MB/s. TS §15's "0 allocation per block" stays a gate for `INTERNAL` and `VOLUME`.

### 4.3 Failure classes, timeouts and reconnect (reviewed 2026-10-24, A-B3, A-B4; lead ruling 1)

`SmbjRemoteLink` configures smbj (Must; facts §1.5):
- `withSocketFactory(ProxySocketFactory(SMB_CONNECT_TIMEOUT_MS))`;
- `withSoTimeout(SMB_SO_TIMEOUT_MS)`, which is larger than `SMB_KEEPALIVE_MS`;
- read/write/transact timeout = `ioTimeoutMs(pool)`: `SMB_IO_TIMEOUT_MS` (8,000) with the 160-block pool, `SMB_IO_TIMEOUT_SMALL_POOL_MS` (4,000) with 96;
- `withBufferSize(SMB_MAX_IO_BYTES)`;
- `withSecurityProvider(BCSecurityProvider())`;
- signing enabled and not required; encryption as the server demands;
- the default blocking transport (async needs API 26, facts §1.4).

**Classification (`RemoteLink.classify`), in this order:**

| # | Condition | Class | Effect |
|---|---|---|---|
| 1 | `Thread.currentThread().isInterrupted`, or an `InterruptedException`/`InterruptedIOException` anywhere in the cause chain (smbj's `Futures.get` turns an interrupt into a `TransportException`, `Futures.java:29-49`) | **CANCEL** | throw `InterruptedIOException`. No reconnect, no link close, no `lastFailure`, epoch unchanged (Must, A-B3). The interrupted request stays outstanding in smbj; its late response is matched and dropped |
| 2 | `TransportException` from a closed or dead transport (`PacketReader` exit, socket reset, EOF), or the statuses `STATUS_NETWORK_NAME_DELETED`, `STATUS_USER_SESSION_DELETED`, `STATUS_NETWORK_SESSION_EXPIRED`, `STATUS_FILE_CLOSED` | **LINK_LOST** | `link.reconnect(epoch)` (below), then retry the same operation at the same offset |
| 3 | an I/O wait timeout (`TimeoutException`, smbj's timeout `TransportException` with a live transport) | **SLOW** | fail the operation: `pwrite` → `IOException` → `DISK_ERROR` with `lastFailure = SLOW`; `pread` → `RingIoException` (SLOW). **No reconnect, no teardown**: a NAS disk spinning up or a Wi-Fi roam keeps its TCP connection |
| 4 | `STATUS_DISK_FULL`, `STATUS_QUOTA_EXCEEDED` | FULL | fail |
| 5 | `STATUS_ACCESS_DENIED`, `STATUS_LOGON_FAILURE`, `STATUS_ACCOUNT_*`, `STATUS_PASSWORD_EXPIRED` | ACCESS | fail; the keeper goes to AUTH_FAILED for logon statuses |
| 6 | `OBJECT_NAME_NOT_FOUND` / `DELETE_PENDING` / `FILE_DELETED` on a reopen | REMOVED | `gone = true` → `STORAGE_EVICTED` |

**Reconnect (single flight, owned by the link).**
1. The first thread to see LINK_LOST at epoch *e* asks `link.reconnect(e, deadline)`. The link (on `box-smb`) calls `close(true)` on the dead `Connection`, then `connect` → `authenticate` → `connectShare` at epoch *e + 1*. Other callers at *e* wait on the same future.
2. The storage reopens with `FILE_OPEN` and retries.
3. The deadline is `SMB_RECONNECT_BUDGET_MS` from the operation's first failure, never past the I/O timeout of that operation's class. When it passes, the operation fails as UNREACHABLE.
4. At most one reconnect per `SMB_RECONNECT_MIN_GAP_MS`.
5. Any reconnect sets `needsFreshFile` (§4.5). The keeper is told through the link's state; there is one owner (lead ruling 6).

### 4.4 The pool is the stall budget (A-B4; lead ruling 5)

- **Normal load.** At 1 MB/s the flusher sends about 15.3 synchronous 64 KiB WRITEs a second: about 31 % busy at a 20 ms round trip. Readers behind live read in parallel on their own threads. No write pipelining (§18 R10).
- **A stall.** Nothing is flushed, so the pool fills.
  - 160 blocks = 10.47 MB ≈ 10.5 s at 1 MB/s. `SMB_IO_TIMEOUT_MS` (8 s) fires first: SLOW → `DISK_ERROR (SLOW)`.
  - At higher rates the pool fills first: `PUMP_BLOCK_MAX_MS` (2 s) → `DISK_TOO_SLOW`.
  - Either way the viewer sees **one** SLOW path, through `lastFailure` and the wording (§10): RAM-only for the cycle, live TV continues.
- **A transport loss with a quick reconnect** (≤ `SMB_RECONNECT_BUDGET_MS`) is invisible: the flusher catches up from the pool.
- **The 96-block pool** (`memoryClass` < 192 MiB) covers about 6.3 s. Its I/O timeout is 4 s. The hiccup gate (I-SL-7) is waived on those devices, and the Test verdict says so (§7.8).
- **`PUMP_BLOCK_MAX_MS` stays 2 s.** A longer pump wait holds back the upstream socket, and panels drop slow readers (§18 R17).
- **Catch-up burst** after a recovered stall: the backlog (≤ 10 MB) goes out at link speed, one 64 KiB write in flight. I-SL-12 measures the effect on the live stream (U-12).
- **Readers.**
  - At live and up to about 10 s behind, readers are served from RAM.
  - Further behind, each 64 KiB `pread` is one round trip (5–25 ms on Wi-Fi), so 2.6–13 MB/s.
  - Seeks need 16–32 round trips before the first frame, hence the looser network seek gate (§14). No read-ahead now (§18 R11).

### 4.5 Fresh file after any reconnect or timeout (A-B4)

A WRITE that timed out, or that was in flight when the transport died, may still be applied by the server later. Samba runs one `smbd` per connection, so there is no ordering against our new connection. If `reset()` (a zap) reused the file from offset 0, such a stale write could overwrite new-generation bytes the ring already counts as flushed.

So:
- After any LINK_LOST reconnect, or any SLOW timeout on a write, `needsFreshFile = true`.
- `TimeshiftSessionFactoryImpl`'s reuse test refuses a storage with `needsFreshFile`. It rebuilds with `n + 1` in the name; the old file is deleted later (posted).
- The rebuild costs one CREATE on the new storage's flusher, never on `prepare`.

### 4.6 At live, behind live, paused: the TS §4.6 table for SMB (reviewed 2026-10-24, A-S3, U-S5)

| State at the failure | Behaviour |
|---|---|
| At live | the reader continues from RAM. `available = false (STORAGE_ERROR)` for the cycle. Toast REWIND_STOPPED_* with the network wording |
| Behind live | the reader reads the file while `pread` (with reconnect) succeeds. Past the budget: `RingIoException` → live, toast with the **"so you're back at live"** variant (§10) |
| Paused | `claim` waits for a flush (`flushWait`), so the stop the viewer gets is **`DISK_TOO_SLOW`** (or `DISK_ERROR (SLOW)` at the I/O timeout). Only then does the RAM-only ring answer FULL → `FULL_WHILE_PAUSED`, which releases the provider connection. The frozen frame and the pill stay. **Resume** first tries the file: the keeper kept reconnecting, and RAM blocks after `flushed` were never recycled, so file plus RAM is contiguous. If the file cannot be read: live, with the new PAUSE_RESUME_LOST_STORAGE (§10) |

---

## 5. Choosing the target per cycle, the fallback storage, the keeper

### 5.1 `RingTargetSource.current()`: no I/O, no wait (Must; lead ruling 2; A-N4)

`prepareLocked` calls `targets.current(TIMESHIFT)` → `RingTargetChoice(target, chosenId, fallback, lastKnownGood)`:

| Chosen | Keeper / volume state | Target | `fallback` | Toast (`SAVE_LOCATION_FALLBACK`) |
|---|---|---|---|---|
| `internal` | — | INTERNAL | null | — |
| `vol-*` | mounted | VOLUME | null | — |
| `vol-*` | not listed | INTERNAL | `NOT_CONNECTED` | once per process per (location, reason) |
| `smb-*` | READY, last good round trip ≤ `SMB_FRESH_MS` ago | SMB (via `FallbackRingStorage`) | null | — |
| `smb-*` | IDLE/CONNECTING, last-known-good ≤ `SMB_LAST_GOOD_MS` ago (persisted across processes), **and no failure seen in this process** | SMB (via `FallbackRingStorage`) | null; may become `UNREACHABLE` at the deadline (§5.2) | at the switch, once |
| `smb-*` | CONNECTING with no last-known-good | INTERNAL | `NOT_READY_YET` | **none** (still connecting, not unreachable) |
| `smb-*` | BACKOFF after a failure in this process, or no network | INTERNAL | `UNREACHABLE` | once per process (owner ruling 1) |
| `smb-*` | DEMOTED (§5.4) | INTERNAL | `UNSTABLE` | once per process |
| `smb-*` | AUTH_FAILED / password missing | INTERNAL | `SIGN_IN_NEEDED` | the first `SIGN_IN_TOAST_PROCESSES` (3) processes after it starts, then never (reviewed 2026-10-24, U-S7) |
| any | target space < `MIN_RING_BYTES` + reserve | INTERNAL | `NO_SPACE_THERE` | once per process |
| any | fallback chosen and internal space < `MIN_RING_BYTES` | — | — | today's `Off(NO_SPACE)` |

- **Last-known-good:** `noBackupFilesDir/smb-last-good` (`<locationId> <wallMs>`), written by the keeper on every successful keepalive or first write.
- `current()` kicks `keeper.warm()` whenever it is not READY.
- **Pool size:** chosen once per cycle from the **chosen** kind (§3.4), so a fallback to INTERNAL keeps the remote pool size, and recovery does not reallocate.
- **Reuse at a zap** adds to `TimeshiftSessionFactoryImpl.kt:176-179`:
  - same `locationId`;
  - same pool size;
  - storage not `gone`, not `needsFreshFile`;
  - a `FallbackRingStorage` that has not switched.

  A different target rebuilds. The old storage's `close()`/`delete()` never wait (§4.1), and the internal storage's delete stays on `box-ts-discard`.
- **Sizing inputs** per target replace `TimeshiftSessionFactoryImpl.kt:124-133`:
  - INTERNAL: today's;
  - VOLUME: `StatFs` on the volume directory;
  - SMB: the cached free space, or `Long.MAX_VALUE` when older than `SMB_SPACE_MAX_AGE_MS`;
  - SMB capacity is `min(SMB sizing, internal sizing)` when `lastKnownGood` selected it without READY, so a switch to internal fits (§5.2).

  The new policy overloads are in §12.1, implemented by WP-S2 (A-S13).

### 5.2 `FallbackRingStorage`: the early switch (lead ruling 2)

`FallbackRingStorage(primary: RemoteRingStorage, secondary: () -> RingStorage, deadlineMs, clock)` (pure JVM, `core/.../storage/`) is the `RingStorage` the ring sees for every SMB cycle.
- **First `pwrite`** (flusher): `primary.create(now + deadlineMs)`, with `deadlineMs` = `SMB_OPEN_DEADLINE_MS` (6,000) for the 160-block pool and `SMB_OPEN_DEADLINE_SMALL_POOL_MS` (3,000) for 96. The deadline is measured from that first `pwrite`, which comes ≤ `FLUSH_PARTIAL_MS` after the first commit, so the switch lands inside the pool's ≈ 10.5 s / 6.3 s.
  - **True:** delegate everything to the primary from then on.
  - **False** (deadline passed or any failure), and **nothing has reached the folder** (always true here): close the primary (posted), build the secondary on the flusher (a local `AndroidRingStorage` in the internal directory, with `onWritten = addWritten`), write the header, and delegate to it. Set `switchedTo = INTERNAL` and `fallbackReason` from the primary's `lastFailure` (UNREACHABLE, SIGN_IN_NEEDED, NO_SPACE_THERE). The session publishes it on the next ≤ 1 Hz `LocalBufferState`, and the machine toasts SAVE_LOCATION_FALLBACK once (§10).
- **After a successful create** there is no switch: later failures are §4.3 and §4.6.
- **Capacity after a switch:** the ring's capacity was sized as `min(SMB, internal)` (§5.1), and the secondary reports the internal reserve through `availableBytes()` (§3.4), so the internal storage is never filled past its reserve. The daily wear counter counts the secondary's writes. This cycle has no daily *cap* (it was decided for SMB at prepare); the next cycle's prepare applies it.
- **`blocking`** = true, so the dedicated flusher stays for the cycle.

### 5.3 `SmbSessionKeeper`

```
IDLE ──(chosen = smb, network)──► CONNECTING ──ok──► READY ──transport loss / keepalive fails──► BACKOFF(n) ──trigger──► CONNECTING
  ▲                                   │ logon status          │ ring stop outside the grace          ▲
  └── chosen ≠ smb / background > SMB_BACKGROUND_DISCONNECT_MS and no open file                    │
                                   AUTH_FAILED (until the sign-in changes)   DEMOTED (≥ SMB_DEMOTE_MS) ┘
```

- **Triggers:**
  - after the first frame, or `SMB_FIRST_CONNECT_DELAY_MS` after launch, whichever is first (reviewed 2026-10-24, A-N6: BC class loading and NTLM must not compete with the cold-start tune);
  - a location change;
  - `NetworkCallback.onAvailable` / `onLost` (default network);
  - `ProcessLifecycleOwner` ON_START (`:data` adds `androidx.lifecycle:lifecycle-process`, commit 0, A-S13);
  - the guide's preview start;
  - `current()` while not READY;
  - the Save to page opening.
- **Backoff:** 5 s, 15 s, 60 s, then every 300 s, reset by any trigger.
- **On every (re)connect and on ON_START / preview start:** write 4 KiB to `TheBox-rewind/.wake-<deviceId>` (reviewed 2026-10-24, A-B4). A sleeping NAS disk then spins up before the viewer pauses. Its LastWriteTime is the sweep's server clock (§3.5).
- **Keepalive** while READY in the foreground: `getShareInformation()` every `SMB_KEEPALIVE_MS` (smbj has no echo). It refreshes free space and last-known-good, and detects a dead link before a tune.
- **Background:** disconnect after `SMB_BACKGROUND_DISCONNECT_MS` unless the link's open-file count is > 0 (A-S1).
- **First READY per process:** `.wake`, then the sweep (§3.5).

### 5.4 Demotion and the start-of-cycle grace (A-B4, A-N5)

- A disk stop on an SMB ring (`DISK_ERROR`, `DISK_TOO_SLOW`, `STORAGE_EVICTED`) reaches `targets.onStorageFailure(locationId, stop, failure, sinceCycleStartMs)` **synchronously, before** the session publishes the stop (reviewed 2026-10-24, A-N5). The next `current()` then already sees the new state.
- **Grace:** a stop that starts within `SMB_FIRST_WRITE_GRACE_MS` (20 s) of the cycle's first successful write does **not** demote. The cycle stays RAM-only, and the next zap tries SMB again.
- Outside the grace: DEMOTED for `SMB_DEMOTE_MS`, doubled for a repeat within `SMB_DEMOTE_REPEAT_WINDOW_MS`, capped at `SMB_DEMOTE_MAX_MS`. The folder is preferred again after a READY plus one keepalive.

### 5.5 Factory and storage changes (`:player`, WP-S2)

- `TimeshiftStorageImpl` keeps the internal directory, counter and sweep. It gains `openLocal(target: RingTarget.Local, capacity, preallocate, countsWear)`.
- The factory:
  - builds `FallbackRingStorage(target.open(name, capacity), secondary = { openLocal(internal, …) })` for SMB;
  - takes the pool size from `limits.ramBlocks(memoryClassMb)`;
  - wires `dailyLimit`/`limitedToday` only when the target (or a switched secondary) counts wear;
  - copies the target, the fallback and the storage rates into `LocalBufferState` on every publish;
  - calls `onStorageFailure` per §5.4.
- `closeRingLocked` and the rebuild never wait on a remote `close`/`delete` (A-B1).
- **`SmbPrepareNoIoTest`** drives the real `TimeshiftSessionFactoryImpl.prepare` over `FakeRemoteLink` and asserts 0 link calls (Must, A-B1). It covers:
  - first build;
  - reuse;
  - rebuild;
  - SMB → INTERNAL;
  - INTERNAL → SMB;
  - SMB → SMB with `needsFreshFile`;
  - a broken ring;
  - discard.

### 5.6 Wi-Fi bandwidth

- At live: stream in plus write out = 2*r* of airtime.
- Behind live: add the read-back, 3*r* in all (24 Mbit/s for a 1 MB/s channel).
- A 5 GHz stick has the headroom; weak 2.4 GHz may not.
- §4.4 is the protection: when the folder falls behind, saving stops and the SMB traffic with it. **Live TV is never traded for rewind.**
- The Test grades the link, and the stats line shows the folder's rate.

---

## 6. USB details (`VolumeWatcher`, `:player`)

- `VolumeWatcher` (singleton, started by `TimeshiftStartup.onAppStart`) implements `VolumeCatalog`: `MountedVolume(uuid, label, dir, total, free)`, refreshed on `box-ts-sweep` at start and on every `MEDIA_*` broadcast.
- Directory `File(volumeFilesDir, "timeshift")`; sweep per §3.5; removal per §3.2.
- The factory exposes `onVolumeGone(uuid)`.
- A drive with less than `MIN_RING_BYTES + reserve` free → `NO_SPACE_THERE` ("the USB drive is full").
- A restored VOLUME location whose uuid this box has never seen: on restore, the choice resets to `internal` while the location stays in the list, so it never toasts at every launch (reviewed 2026-10-24, A-N7).

---

## 7. Finding computers, signing in, shares and folders

### 7.1 Finders (`ComputerFinder`, `:data`; lead ruling 3; reviewed 2026-10-24, A-S10, U-S2, U-S8)

`ComputerFinder.search(): Flow<FindState>` starts when `FindComputers` opens. It stops (Must) when the page closes (`SettingsPageSource.closed`), or after `DISCOVERY_TOTAL_MS`. The "searching" state ends as soon as the sweep finishes. **The `WifiManager.MulticastLock` is held from the page's open to its close**: Wi-Fi drivers filter multicast in power save, and that affects the system mdnsd too. Permissions: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (normal, no prompt).

| Finder | Finds | How |
|---|---|---|
| **NSD** | Mac, NAS with Bonjour, Linux + avahi | `NsdManager.discoverServices("_smb._tcp.", PROTOCOL_DNS_SD, listener)` with a **new listener per search** (one cannot be reused). `stopServiceDiscovery` only after `onDiscoveryStarted` (stopping a listener whose start failed throws `IllegalArgumentException`). A stop racing a late `onDiscoveryStarted` stops it on arrival. Callbacks pre-33 arrive on main and are posted to `box-find`. Resolves run **one at a time** below API 34 (`FAILURE_ALREADY_ACTIVE`, facts §4.2); `registerServiceInfoCallback` from 34, unregistered on stop |
| **445 sweep** (owner ruling 2) | anything with file sharing on, including every Windows PC usable over SMB (445 is open on the Private profile) | the address from `LanAddressFinder.find()`: Wi-Fi/Ethernet only, **never `TRANSPORT_VPN`**, RFC 1918 only (`LanAddressPolicy`). Scans the /24 that contains it (≤ `SWEEP_MAX_HOSTS`), skipping our own address, with `SWEEP_PARALLEL` sockets at `SWEEP_CONNECT_TIMEOUT_MS`. The connect is closed at once and no SMB bytes are sent. **The stop path closes every pending socket** (`Socket.connect(timeout)` is not interruptible) |
| **Names** | addresses without an mDNS name | NBNS node status (`NBSTAT *`, UDP 137, `NBNS_TIMEOUT_MS`): the first unique `<00>` name. Else reverse DNS on a bounded future (`REVERSE_DNS_TIMEOUT_MS`). Else none |

- **Merge:** by IPv4 address. Name preference: NSD instance, then NetBIOS, then reverse DNS.
- **Order** (reviewed 2026-10-24, U-S9): saved hosts first, with the new `Badge.SAVED`; then named computers A–Z; then unnamed ones by address. An unnamed computer shows its address as the label and "No name" as the value. Windows default names (`DESKTOP-7F3K2LQ`) stay as they are, with the address in mono beside them.
- **Android 17 local-network protection** gates all of this, SMB and LAN panels once targetSdk ≥ 37 (facts §4.1). The plan gets a note (commit 0).

### 7.2 Manual entry and paste (reviewed 2026-10-24, U-S4)

- **Editor:** "Computer name or address" (`TextHint.HOST`, new), helper line "For example: nas, 192.168.1.20, or \\NAS\TV\Rewind", chips `\\` · `\` · `.local` · `smb://`.
- **Accepted forms:** `nas`, `nas.local`, `192.168.1.20`, `192.168.1.20:4445`, `\\NAS\TV\Rewind`, `smb://nas/TV/Rewind` (`ManualAddressParser`, `:core`).
- **Landing** (each step goes through sign-in when needed):
  - host only → `ComputerShares`;
  - host + share → `NetworkFolder` at the share root;
  - host + share + path → `NetworkFolder` at that path, focus on **Use this folder**.
- **A missing path:** "There's no folder "Rewind" in \\NAS\TV. Choose one below." The page lands on the deepest folder that exists.
- **`.local` names** the system resolver does not answer are matched against NSD results seen in this process (U-8).

### 7.3 Sign-in on the TV (reviewed 2026-10-24, U-S10, U-S5, A-S12)

- **Order of tries** with no saved sign-in: anonymous, then guest. Ask when both fail, when guests need signing (`SMB2GuestSigningRequiredException`, Windows 11 24H2's default), or when listing needs credentials (Windows refuses anonymous srvsvc).
- **`ComputerSignIn` rows, top to bottom:**

| # | Row | Kind / detail |
|---|---|---|
| 1 | **Sign in from your phone** | Action, detail "Scan a code and type the password on your phone." Focused first when the TV has no keyboard (U-B1) |
| 2 | User name | `TextHint.USERNAME` |
| 3 | Password | `TextHint.PASSWORD`, masked, with a **Show** chip |
| 4 | **Sign in** | Action |
| 5 | Use as guest | Action |
| 6 | Workgroup or domain | description "Leave empty unless you know you need it." |

  Info line: "On Windows, use the user name and password of an account on that PC. A separate local account for The Box is a safer choice than your main sign-in. On a Mac, use the Mac's user name and password." (lead ruling 8: neutral wording).
- **Wrong password:** clear Password, focus it, and show the §7.7 text as a BAD Info row at the top.
- **No keyboard** (M5 F5): "No keyboard on this TV: sign in from your phone, or use a folder that allows guests."
- **Password in memory:** a `CharArray` in the `BrowseSession` (§12.2), zeroed when the session ends. It is never in a route, a `RowIntent` log or a `toString`.

### 7.4 Sign-in from the phone (owner ruling 4; reviewed 2026-10-24, U-B1, A-S12; lead ruling 8)

**Entry points:**
- row 1 of `ComputerSignIn` / `LocationSignIn` (host and share known);
- **Add from your phone** on `FindComputers` (nothing known).

Both push `PhoneSetup(purpose = NETWORK_FOLDER, browse = token)`. The M5 §3.5.1 QR screen is reused, with the title "Add a network folder from your phone" and the plain-HTTP line below. The page starts with `PhoneLink.start(NETWORK_FOLDER, PhoneContext(host, share, shares))`. The context is what the TV knows, **never a credential**, provided by `StoragePages` through `PhoneContextProvider` (§12.3).

**Phone page** (a new tab in `app.html`, shown alone for this purpose, same theme), top to bottom:
1. "Computer (name or address)": read-only when the context has a host, with a "Change" link;
2. "Shared folder": a `<select>` of `shares` when known, else text. It accepts a pasted `\\NAS\TV\Rewind` or `smb://…` and splits it with the same rules as `ManualAddressParser` (mirrored in `app.js`, with test vectors shared through `PhoneApiTest` fixtures);
3. "Folder inside it (optional)";
4. "User name" (`autocomplete="username"`);
5. "Password" (`type=password` with Show, `autocomplete="current-password"`; the phone's password manager may fill it, and our server stores nothing);
6. collapsed "More: Workgroup or domain";
7. "This folder allows guests (no password)";
8. **Send to TV**.

The other fields keep M5's `autocomplete="off"`. Under the password: the §7.3 info line. Footer: M5's plain-HTTP caveat, plus "Your password crosses your home Wi-Fi once, unencrypted, to the TV. The TV keeps it encrypted."

**Server rules** (`PhoneApi`, Must):

| Rule | Detail |
|---|---|
| Endpoint | `POST /api/network-folder` `{host, share?, path?, user?, domain?, password?, guest}`, accepted only for `NETWORK_FOLDER` (`onlyFor`), with the M5 gate (pairing, `X-TheBox`, Host check) |
| Request body | read into one fixed `ByteArray` (≤ `HTTP_MAX_JSON_BYTES`), parsed, then **zeroed**. Not the `ByteArrayOutputStream` of `readJson`, whose internal buffer cannot be zeroed. The password is copied into a `CharArray` for `PhoneSubmission.NetworkFolder`. `Redact.registerTransient(user)` and `(password)` |
| Residue | the JSON parser's transient `String` of the password cannot be zeroed. It is the same residue as today's Xtream password (§19 N-3) |
| Mirroring | `/api/state` **never** carries the password or the full user name. It shows "Received from your phone: user sh***" and the TV's result lines: "Signed in to NAS. Choose the folder on the TV." / "NAS didn't accept that user name or password. Check them and send again." / "Can't reach NAS (192.168.1.20). Check it's on and on the same network." |
| One shot | the **first successful** sign-in stops the server. A failed sign-in keeps it, up to `PAIR_MAX_WRONG` failed sign-ins, then it stops with "Too many failed sign-ins. Start again on the TV." Idle and absolute limits as M5 §3.5.2 |
| Logs / debug | the received-JSON debug dump excludes this endpoint's body |

**TV after Send:** `StoragePages` (which owns the session) runs the same sign-in and listing. On success it **replaces** `PhoneSetup` with `ComputerShares`, or with `NetworkFolder` at the path when one was given, focus on **Use this folder**. BACK never returns to the QR. On failure it stays on `PhoneSetup` and mirrors the error.

### 7.5 Shares (lead ruling 4)

- `SmbShareLister.list(session)`: `SMBTransportFactories.SRVSVC.getTransport(session)` → `ServerService(transport).getShares1()`. Keep `STYPE_DISKTREE`; drop `$` names and `IPC$`; sort A–Z.
- `ACCESS_DENIED` → sign-in, or, when already signed in, the access error.
- Any other RPC failure → "This computer didn't list its shared folders." plus **Type the shared folder's name**, which is always the last row and is checked with `connectShare`.
- **Dependency check (2026-10-24, lead ruling 4).** `releaseRuntimeClasspath` already carries `com.google.guava:guava:33.3.1-android` (via `androidx.media3:media3-common:1.11.1`). dcerpc's `33.4.0-jre` therefore resolves to a single Guava, one minor version up, and Gradle's module metadata should pick the `-android` variant (S-2 confirms the resolved variant). The new jars are dcerpc itself, commons-lang3 and commons-io, plus smbj's bcprov / mbassador / asn-one. Neither commons library is on the classpath today. S-2 records the measured release-APK delta of each set here. The budgets in §14 stand; if dcerpc's set exceeds its budget, the lead decides between the measured cost and dropping enumeration (typed names only). No hand-written NDR codec (A-N1).
- **bcprov stays:** `SmbConfig.builder()` constructs `BCSecurityProvider`, and Android's JCE lacks AES-CMAC (A-N1).
- **mbassador keep rules** include `SMBClient.connectionClosed` (A-N1).

### 7.6 Folders (one route; reviewed 2026-10-24, U-B3)

- `SmbFolderBrowser.list(share, path)`: directories only. It skips `.`, `..`, hidden/system entries and `SMB_SKIP_FOLDERS`; sorts case-insensitively; shows at most `SMB_LIST_MAX_ENTRIES` ("Showing the first 2,000 folders.").
- **One route** `NetworkFolder(browse, share)`. The **path is browse-session state**: entering a folder replaces the path and pushes nothing.
  - **←** = up one folder; at the share root, ← pops to `ComputerShares`.
  - **BACK** = leave the browse to `SaveLocation`: the session is cancelled and the password zeroed.
  - The subtitle shows `\\NAS\TV\Recordings\Rewind`, mono, middle-ellipsis.
- **Rows, top to bottom:**
  - **Use this folder** (value mono `\\NAS\TV\Rewind`);
  - **".. Up one folder"**, only below the share root;
  - subfolders as Items (OK or → enters).
- No "New folder": `TheBox-rewind` is made inside the chosen folder.

### 7.7 Errors while browsing (reason + next step, Must; reviewed 2026-10-24, U-S2, U-S5)

| Cause | Text |
|---|---|
| connect timeout / refused | "Can't reach NAS (192.168.1.20). Check it's on and on the same network, then try again." With a VPN up, append: " A VPN app is on: allow local network access in it." |
| SMB1 only | "NAS only speaks an old file-sharing version (SMB1), which The Box doesn't support. Turn on SMB2 or SMB3 in its settings." |
| `STATUS_LOGON_FAILURE` | "NAS didn't accept that user name or password. Check them and try again." When NSD reported an Apple host, append: " On a Mac, also tick this user under File Sharing → Options → Windows File Sharing." |
| guests refused / signing required | "NAS doesn't allow guests. Sign in with a user name and password." |
| `ACCESS_DENIED` on a share or folder | "Your account can't open this folder on NAS. Choose another folder, or give your account access on NAS." |
| write refused in Test (user) | "Your account can read this folder but can't save to it. Choose another folder, or give your account write access on NAS." |
| write refused in Test (guest) | "Guests can open this folder on NAS but can't save to it. Sign in with a user name and password, or allow guests to write on NAS." |
| no network | "This device isn't connected to a network. Connect it, then try again." |
| VPN up (Info row on `FindComputers`, amber) | "A VPN app is on. Allow local network access in it (sometimes called LAN access or split tunnelling), or pause it, so The Box can see computers at home." |
| no result | "No computers found. Check the computer is on and file sharing is turned on (on Windows: set this network to Private). If The Box is on a guest Wi-Fi, connect it to your main Wi-Fi. Or enter the computer's name or address." |

### 7.8 Test (lead ruling 7; reviewed 2026-10-24, U-S3, A-B4)

`LocationTester.test(target, progress)` runs on `box-smb-browse` over the browse `SMBClient`:
1. Connect, sign in, open the share, make `TheBox-rewind/`.
2. **Wake:** time the first 4 KiB write of `.wake-<deviceId>` as `wakeMs`. It is excluded from every rate.
3. **Write phase:** create `test-<deviceId>-<nanoTime>.bin` with `FILE_DELETE_ON_CLOSE`. Write `RING_BLOCK_BYTES` blocks synchronously (the ring's pattern) for `min(SMB_TEST_MAX_BYTES, TEST_WRITE_MS)`.
4. **Read phase:** read back what was written in `READER_STAGING_BYTES` reads, for at most `TEST_READ_MS`. Compare CRC32s.
5. RTT: the median of `TEST_RTT_SAMPLES` one-block reads.
6. Close (the server deletes the file); free space.

- A timeout during a rate phase grades the rate measured so far (TOO_SLOW at worst). **FAILED** only for §7.7 causes, a CRC mismatch ("NAS changed the test data. Choose another folder.") or no space ("NAS has only 1.1 GB free; rewind needs 2.1 GB. Free up space or choose another folder.").
- `rate = min(write, read)`.

| Verdict | Rule | Label (`RowLabel`) | Next step (`RowDesc`) | Details (mono value) |
|---|---|---|---|---|
| GOOD_4K | rate ≥ 3 × `UHD_BPS` and RTT ≤ `TEST_RTT_SLOW_MS` | "Good for HD and 4K channels" | "1.2 TB free on NAS." | `10.5 MB/s · 4 ms · Wi-Fi` |
| GOOD_HD | rate ≥ 3 × `HD_BPS` and RTT ≤ cap | "Good for HD channels" | "4K channels may stop saving now and then. A wired connection to NAS helps." | `4.1 MB/s · 6 ms · Wi-Fi` |
| SLOW | rate ≥ 1.5 × `HD_BPS`, or the RTT cap exceeded | "Slow: rewind may stop now and then" | "A wired connection, or a Wi-Fi router closer to this TV, helps. You can still use it." | `2.1 MB/s · 18 ms · Wi-Fi` |
| TOO_SLOW | below | "Too slow for rewind" | "Rewind would often stop. Use this device instead, or improve the connection to NAS." | `0.9 MB/s · 35 ms · Wi-Fi` |
| FAILED | §7.7 / CRC / space | the cause | its next step | — |

Extra lines:
- `wakeMs ≥ TEST_WAKE_NOTE_MS`: "The drive took 9 s to wake up. The Box wakes it when you open the TV, so rewind isn't affected."
- A 96-block-pool device (lead ruling 5): "On this TV, short network drops stop rewind for the channel."

Rows and buttons:
- While running: Info progress, plus one focusable Action **Stop test**. BACK = Stop test; nothing is saved.
- After: GOOD_* focuses **Use this folder**. SLOW / TOO_SLOW focus **Choose another folder**, with **Use anyway** second.
- `lastTest` stores the verdict, rates, RTT, `wakeMs` and the time.

---

## 8. Credentials, logs, audit, backup (reviewed 2026-10-24, A-S11)

- **The location** holds `user` and `domain`. **The password:** `SecureBlobStore.putBlob("smb-<locationId>", utf8)` (reserved prefix `REF_SMB_PREFIX`). The UTF-8 array is **zeroed** after `putBlob`, and after `getBlob` once it has been copied into the `CharArray`. Remove deletes the blob first.
- **The keeper** reads the blob on `box-smb` at connect, builds `AuthenticationContext` from a `CharArray`, and zeroes it after `authenticate`.
- **`getBlob` outcomes:** MISSING (restore) or `KEY_INVALIDATED` → AUTH_FAILED (`SIGN_IN_NEEDED`); `UNAVAILABLE` → BACKOFF.
- **Export audit:** `DiagnosticsImpl` (`:data/diag`, WP-S1) adds each location's user name and password (read once on IO) to the audit's secrets, like playlist secrets (`DiagnosticsImpl.kt:118-128`).
- **Logs** (Must): `BoxLog` carries location ids, kinds, `RemoteFault` class names and `StorageFailure`s. **Never** user names, passwords, domains, or smbj `e.message`, which carries `\\host\share\path` (home shares contain the user name).
  - smbj's slf4j stays unbound (no-op) in every build.
  - Hosts and share names may appear on screen, not in logs.
- **Backup:** the `storage` domain is backed up: locations with user and domain, choices, names. **Never** a password.
  - SMB locations restore as "Sign in needed".
  - VOLUME choices with an unknown uuid reset to internal (A-N7).

---

## 9. Settings and UI

### 9.1 Rows and the simple-row budget (reviewed 2026-10-24, U-S6)

| Page | Row id | Label | Kind | Vis | Effect | Value / description |
|---|---|---|---|---|---|---|
| Playback | `playback.pause_rewind` (new) | Pause and rewind | Link → `PauseRewind` | **SIMPLE** | NONE | value `<length> · <place>`: "1 hour · NAS", "Auto (1 h 9 min) · USB drive", "Off". While falling back: **"1 hour · This device (NAS offline)"** in amber. Description "Pause live TV and rewind. Choose how long and where it's saved." |
| Pause and rewind (sub-page) | `playback.timeshift` (moved) | **Rewind length** (new string `settings_pb_timeshift_length`) | Choice (today's values) | SIMPLE (sub-page) | NEXT_TUNE | today's, with the storage figure for the chosen place: "…Uses up to 1.9 GB on this device while you watch…" / "…on NAS…" / "…on the USB drive…". USB: the 2 h option shows "Up to 1 h 9 min on this drive" (U-N6) |
| same | `playback.timeshift_location` (new) | Save to | Link → `SaveLocation` | SIMPLE (sub-page) | NEXT_TUNE | value = the place's name. Description by state: "Where rewind is saved while you watch. Applies from the next channel change." / amber "Can't reach NAS: saving on this device for now." / "Sign in needed for NAS: saving on this device for now." Disabled when the length is Off, with `disabledReason` "Turn on rewind first."; the keeper stays IDLE |
| same | `playback.timeshift_hold` (moved) | Keep recording while paused | Choice | ADVANCED | LIVE | today's |
| same | `playback.timeshift_protect` (moved) | Protect built-in storage | Choice | ADVANCED | LIVE | today's; when the place is not this device, append "Not used while saving to NAS." |

**Budget.**
- Playback's simple rows stay 5 (Buffer, AFR, Audio output, Picture shape, Pause and rewind), and the total stays 35.
- The moved rows live in `PauseRewindRows`, listed in `SimpleRowCountTest`'s `SUB_PAGE_SOURCES` (commit 0).
- **M5 §3.3.13 amendment** (WP-S5 writes it): "A SIMPLE Link to a sub-page counts as one simple row; the sub-page's simple rows are not counted, provided the sub-page holds only rows of the one setting it is named after."

**Why a sub-page:** the owner asked for the setting, so it must be in the simple view; a sixth row would re-open the earlier ruling (§18 R13, R14).

### 9.2 Pages and routes (additive `SettingsRoute`s, section PLAYBACK)

| Route | Title / subtitle | Rows |
|---|---|---|
| `PauseRewind` | "Pause and rewind" / "Pause live TV, then pick up where you left off." | §9.1 |
| `SaveLocation(purpose = TIMESHIFT)` | "Save to" / "Where rewind is saved while you watch." | Item **This device** ("Built-in storage · 5.1 GB free"; OK chooses at once). One Item per mounted USB drive ("USB drive · SanDisk · 41 GB free"; OK chooses at once). One Item per SMB location or remembered USB drive (detail = status, OK → `LocationDetail`). Action **Add a network folder** (detail "A shared folder on a computer or NAS (SMB)") → `FindComputers`. The chosen row carries `Badge.IN_USE` |
| `LocationDetail(locationId)` (reviewed 2026-10-24, U-B2) | the location's name / its address | Info Status (Tone): "In use for rewind" / "Can't reach NAS right now" / "Sign in needed" / "Good for HD and 4K channels · tested 3 days ago". Info Address (mono `\\NAS\TV\Rewind`). Action **Use for rewind** (hidden when in use; detail "From the next channel change."; runs the Test first when `lastTest` is older than `TEST_FRESH_MS` or worse than SLOW). Action **Test this folder** → `LocationTest(locationId)`. Text **Name** (`TextHint.NAME`, U-N1). Link **Sign in again** (when Sign in needed) / **Change sign-in** → `LocationSignIn(locationId)`. Action **Remove** (confirm: "Remove NAS › TV › Rewind? Rewind will save to this device.", safe button focused). A remembered USB drive shows Status, Use (disabled while not connected, `disabledReason` "Connect the drive, then try again.") and Remove |
| `FindComputers` | "Find computers" / "Computers on your network that share folders." | Info (not focusable): "Looking for computers on 192.168.1.x… 3 found" (U-N2), then the result or no-result text; the VPN Info row when a VPN is up. Then found computers. Then Action **Enter a name or address**, Action **Add from your phone**, Action **Search again** (U-S9) |
| `ComputerShares(browse)` | computer name / "Shared folders" | Info "Connecting to NAS…" while connecting; Items for shares; Action **Type the shared folder's name**; Action **Sign in** (not signed in) / **Sign in as someone else** (signed in) (U-B2, U-S5) |
| `ComputerSignIn(browse)`, `LocationSignIn(locationId)` | "Sign in to NAS" | §7.3 |
| `NetworkFolder(browse, share)` | `\\NAS\TV` / the current path | §7.6 |
| `LocationTest(browse)` / `LocationTest(locationId)` | "Testing NAS › TV › Rewind" | §7.8 |
| `PhoneSetup(purpose = NETWORK_FOLDER, browse)` | "Add a network folder from your phone" | M5 §3.5.1 screen |

- **`browse: Long`** is a token into `StoragePages`' in-memory `BrowseSession` map (as `RestorePreview(token)`). Routes never carry a credential (Must).
- **Menus** (Long OK / Menu) stay accelerators only. Every option is also a row (M5 §3.15 (d), U-B2): on a location Item, Test · Change sign-in · Remove; on a found computer, "Sign in first".
- **Focus on `FindComputers`** (U-S9): "Enter a name or address" until the first computer arrives; then the first computer, as long as no key has been pressed.

### 9.3 D-pad flow

| Step | Keys | Result |
|---|---|---|
| Playback → Pause and rewind → Save to | OK, ↓, OK | `SaveLocation` |
| Add a network folder | ↓…, OK | `FindComputers`; the lock is taken and the finders start |
| pick a computer | ↑/↓, OK | `ComputerShares`, or `ComputerSignIn` **replacing** it when a sign-in is needed |
| sign in on the TV | OK User name → editor, Done; ↓ OK Password → editor (Show chip), Done; ↓ OK **Sign in** | shares |
| or sign in from the phone | OK on row 1 → QR → type on the phone → Send | `ComputerShares` replaces `PhoneSetup` |
| pick a share | OK (or →) | `NetworkFolder` at the root |
| into a folder / up | OK (or →) / ← (or ".. Up one folder") | the path changes in place |
| use it | ↑ to the top, OK on **Use this folder** | `LocationTest` runs |
| save | OK on **Use this folder** (GOOD) or ↓ **Use anyway** | the location is saved, chosen, and `popTo(SaveLocation)`; notice "Rewind will save to NAS › TV › Rewind, from the next channel change." |
| leave | BACK anywhere in the browse | `SaveLocation`; the session is cancelled and the password zeroed |

- BACK during "Connecting…" cancels the connect.
- `CH±` / `PAGE_UP/DOWN` page ±8 rows.
- BACK on `LocationTest` while running = Stop test.
- **Reachability:** `SettingsReachabilityTest` gains a browse fixture of depth 8: ≤ 12 BACKs leave Settings (U-B3).

Press counts (U's table): a guest NAS 2 levels deep is about 25 presses; a signed-in Mac or Windows PC is about 18 on the TV plus typing on the phone.

### 9.4 Sizes (design px, 1920 canvas; reviewed 2026-10-24, U-B4)

All rows use the settings kit as it is in code (`SettingsUi.kt:78-91`):

| Style | Size |
|---|---|
| row label | 28/500 |
| value | 26 (mono 26) |
| description | 22 |
| option | 26 / 22 |
| notice | 24 |
| card title | 36 |
| badge `BadgeText` | mono 18 |

There is no new text style below 22, except the badge (18).

| Element | Geometry | Style |
|---|---|---|
| `Badge.IN_USE`, `Badge.SAVED` | chips as M5 badges, `BadgeText` 18 | 1 px accent `#5CA8F2` border; text FG `.9` on a normal row, **canvas colour `#0A0D13` on a focused (FG-filled) row** |
| Searching Info row | row 76 | `RowLabel` at `.7`; count and scanned network in `RowValueMono`; three dots 24 px at x + 1,220, alpha 0.3 → 1, 400 ms per dot; static when `ANIMATOR_DURATION_SCALE == 0`; `contentDescription` "Searching" (U-N5) |
| Test progress | Info row 104 | `RowLabel`; a 1,228 × 8 px track `#FFFFFF` 18 % with the done part in the accent; "9 of 16 MB" in `RowValueMono`; `contentDescription` "Testing, 9 of 16 MB" |
| Verdict | Info row 104 with `Tone` | the `RowLabel` line = the label, the `RowDesc` line = the next step, one line each; details in the value column, mono |

No `Popup` or `Dialog` (M5 §3.1).

### 9.5 Files the sub-page move touches (reviewed 2026-10-24, U-S6)

`PlaybackRows.kt`, the new `PauseRewindRows.kt`, `SettingsTree.kt` (sources, `sectionOf`) and `SimpleRowCountTest.kt` are commit 0.

WP-S4 owns:
- `WpaPages.kt` (the link row; `timeshiftRows` moves to `StoragePages`);
- `WpaPagesTest.kt:55-63`, `:229-289` (look the rows up on `PauseRewind`);
- `SettingsBackLadderTest.kt:151-158`;
- `SettingsTexts.kt` (Rewind length);
- the strings (§10).

`TimeShiftBufferTest` drives the setting through `GuideDebugReceiver` and is unaffected.

---

## 10. Viewer strings (reviewed 2026-10-24, U-S5)

Rules as TS §13: ≤ 2 lines, reason then next step, paths with " → ", no hidden row.

**Toast arguments.** `arg` for REWIND_STOPPED_STORAGE, REWIND_STOPPED_SLOW, PAUSE_NO_REWIND_STORAGE and the new kinds is `"<StorageKind>\u001F<StorageFailure|>\u001F<name>\u001F<jumped 1|>"`. A null arg means today's internal text.

| Key | When | Text |
|---|---|---|
| SAVE_LOCATION_FALLBACK *(new)* / UNREACHABLE | tune, or the §5.2 switch | "Rewind is saving on this device: NAS isn't answering. Check it's on, or change Settings → Playback → Pause and rewind → Save to." |
| / SIGN_IN_NEEDED | | "Rewind is saving on this device: NAS needs you to sign in again. Sign in at Settings → Playback → Pause and rewind → Save to → NAS." |
| / UNSTABLE | demoted | "Rewind is saving on this device for now: NAS kept dropping out. The Box tries NAS again in a few minutes." |
| / NOT_CONNECTED | USB missing | "Rewind is saving on this device: the USB drive isn't connected. Connect it, or change Settings → Playback → Pause and rewind → Save to." |
| / NO_SPACE_THERE | target full | "Rewind is saving on this device: NAS has only 1.1 GB free. Free up space there, or change Settings → Playback → Pause and rewind → Save to." |
| REWIND_STOPPED_STORAGE (SMB, UNREACHABLE), at live | | "Rewind stopped: NAS stopped answering. Live TV continues; from your next channel change, rewind saves on this device until NAS is back." |
| same, behind live (jumped) | | "NAS stopped answering, so you're back at live. Live TV continues; rewind saves on this device from your next channel change." |
| REWIND_STOPPED_STORAGE (SMB, FULL) | | "Rewind stopped: NAS is full. Live TV continues. Free up space there, or change Settings → Playback → Pause and rewind → Save to." |
| REWIND_STOPPED_STORAGE (SMB, ACCESS) | | "Rewind stopped: NAS no longer lets The Box save there. Live TV continues. Check the folder's permissions, then choose Test this folder in Settings → Playback → Pause and rewind → Save to → NAS." |
| REWIND_STOPPED_STORAGE (SMB, REMOVED) | | "Rewind stopped: the saved file on NAS was deleted. Live TV continues; change channel and back to start again." |
| REWIND_STOPPED_SLOW (SMB) | `DISK_TOO_SLOW` or `DISK_ERROR (SLOW)` | "Rewind stopped: NAS couldn't keep up over your network. Live TV continues; from your next channel change, rewind saves on this device for a while." Inside the grace (§5.4): "…; the next channel tries NAS again." |
| REWIND_STOPPED_STORAGE (VOLUME, REMOVED) | eject | "Rewind stopped: the USB drive was removed. Live TV continues; rewind saves on this device from your next channel change." |
| REWIND_STOPPED_SLOW (VOLUME) | | "Rewind stopped: the USB drive can't keep up with this channel. Live TV continues. A faster drive helps, or choose This device in Settings → Playback → Pause and rewind → Save to." |
| PAUSE_NO_REWIND_STORAGE (SMB/VOLUME) | pause after a stop | "Saving for rewind failed on NAS, so Play returns to live. Change channel and back to try again." (USB: "on the USB drive") |
| PAUSE_RESUME_LOST_STORAGE *(new)* | resume, file unreadable (§4.6) | "NAS stopped answering while you were paused, so you're back at live. Rewind saves on this device from your next channel change." |
| REWIND_STOPPED_SLOW (internal, existing, `strings_player.xml:169`) | | "…Try Pause only in Settings → Playback → Pause and rewind → Rewind length." |
| PAUSE_NO_REWIND_SPACE (existing) | | "…choose a shorter rewind in Settings → Playback → Pause and rewind → Rewind length, or save to a USB drive or network folder there." |
| REWIND_LIMITED_TODAY (existing) | | path → "Settings → Show advanced settings, then Playback → Pause and rewind → Protect built-in storage." |
| Stats line (TS §13 U-N3) | stats on | "Rewind: 18:20 saved of 30:00 · 1.1 GB · NAS 3.4 MB/s"; falling back: "… · this device (NAS offline)" or "… (NAS connecting)" (U-N4) |

- "NAS" stands for the location's name (≤ `LABEL_MAX_CHARS`, middle ellipsis), or "the USB drive".
- **Who toasts.** The machine shows SAVE_LOCATION_FALLBACK when a cycle's `LocalBufferInfo.fallback` first becomes non-null (at the first Buffer event or at the §5.2 switch). It asks `PlayerEffects.fallbackToastDue(locationId, reason)`, which `:app` answers per the §5.1 rules. The counter for SIGN_IN_NEEDED is device state and not backed up.

---

## 11. Reuse for the v2 DVR

- `StoragePrefs.recordingLocationId` is reserved. `SaveLocation(purpose)`, `LocationDetail`, `FindComputers`, the browse routes and the Test take a purpose from day one.
- `RemoteLink`/`RemoteFile` give a recorder what it needs: sequential writes at growing offsets, `rm`, `list`, free space, single-flight reconnect, `FILE_OPEN` reopen at the same offset.
- The v2 recorder is one more pinned `RingReader` (TS §0 item 13), copying into `TheBox-recordings/…`.
- v2 work not done here:
  - one `SmbLink` per distinct location in use;
  - SAF trees for USB recordings that must survive uninstall;
  - durable handles or a multi-hour soak (the plan's "~87 min" line).

---

## 12. Contracts (commit 0)

### 12.1 `core/.../storage/StorageContracts.kt` (new, pure JVM)

```kotlin
package app.thebox.core.storage

@kotlinx.serialization.Serializable enum class StorageKind { INTERNAL, VOLUME, SMB, SAF_TREE }   // append-only; SAF_TREE never produced
@kotlinx.serialization.Serializable enum class StoragePurpose { TIMESHIFT, RECORDING }            // append-only

@kotlinx.serialization.Serializable
data class SmbAddress(
    val host: String, val port: Int = StorageConstants.SMB_DEFAULT_PORT, val share: String, val path: String = "",
    val user: String? = null, val domain: String? = null, val guest: Boolean = false, val lastAddress: String? = null,
) { override fun toString() = "SmbAddress(host=$host, port=$port, share=$share, path=$path, user=${if (user == null) "null" else "<set>"}, guest=$guest)" }

@kotlinx.serialization.Serializable enum class TestVerdict { GOOD_4K, GOOD_HD, SLOW, TOO_SLOW, FAILED }
@kotlinx.serialization.Serializable
data class LocationTestResult(val verdict: TestVerdict, val writeBps: Long, val readBps: Long, val rttMs: Int,
                              val wakeMs: Int, val freeBytes: Long, val wifi: Boolean, val atWallMs: Long)

@kotlinx.serialization.Serializable
data class StorageLocation(
    val id: String,                    // "vol-<uuid>" | "smb-<uuid>"; INTERNAL is implicit ("internal")
    val kind: StorageKind,
    val name: String,                  // user-editable (U-N1), ≤ LABEL_MAX_CHARS
    val volumeUuid: String? = null,
    val smb: SmbAddress? = null,
    val lastTest: LocationTestResult? = null,
)

@kotlinx.serialization.Serializable
data class StoragePrefs(
    val locations: List<StorageLocation> = emptyList(),
    val timeshiftLocationId: String = StorageConstants.INTERNAL_ID,
    val recordingLocationId: String? = null,     // v2
)

enum class LocationFallback { NOT_READY_YET, UNREACHABLE, UNSTABLE, SIGN_IN_NEEDED, NOT_CONNECTED, NO_SPACE_THERE }   // append-only
enum class StorageFailure { UNREACHABLE, FULL, ACCESS, REMOVED, SLOW }                                                 // append-only

/** Per-kind sizing (§3.4). Values are constants, defined here in commit 0 (A-S13). */
data class StorageLimits(
    val maxRingBytes: Long, val reserveBytes: Long, val countsWear: Boolean, val preallocate: Boolean,
    val autoLengthLarge: Boolean, val ramBlocks: Int, val ramBlocksSmall: Int,
) {
    /** Remote kinds: [ramBlocks] when memoryClass ≥ REMOTE_POOL_MEMORY_CLASS_MB, else [ramBlocksSmall] (lead ruling 5). */
    fun ramBlocks(memoryClassMb: Int, lowRamDevice: Boolean): Int
    companion object { fun internal(totalBytes: Long): StorageLimits; val VOLUME: StorageLimits; val SMB: StorageLimits }
}

enum class LocationState { READY, CONNECTING, UNREACHABLE, SIGN_IN_NEEDED, NOT_CONNECTED, DEMOTED, UNTESTED }
data class LocationStatus(val location: StorageLocation, val state: LocationState, val freeBytes: Long? = null, val failure: StorageFailure? = null)

/** :data (StorageLocationsImpl). Reads main-safe; writes suspend on IO. */
interface StorageLocations {
    val status: kotlinx.coroutines.flow.StateFlow<List<LocationStatus>>
    fun chosen(purpose: StoragePurpose): String
    suspend fun choose(purpose: StoragePurpose, locationId: String)
    /** Stores [password] in SecureBlobStore ("smb-<id>"), zeroes it and the UTF-8 copy (A-S11). */
    suspend fun saveSmb(address: SmbAddress, name: String, password: CharArray?, test: LocationTestResult?): StorageLocation
    suspend fun updateSignIn(locationId: String, user: String?, domain: String?, password: CharArray?, guest: Boolean)
    suspend fun rename(locationId: String, name: String)
    suspend fun remove(locationId: String)
}

/** USB volumes (§6). :player (VolumeWatcher) implements; :app binds it for :data. */
interface VolumeCatalog {
    data class Volume(val uuid: String, val label: String, val dir: java.io.File, val totalBytes: Long, val freeBytes: Long)
    val volumes: kotlinx.coroutines.flow.StateFlow<List<Volume>>
}

/** Browse, sign-in and Test for the pages (§7). :data implements; every call runs on box-smb-browse. */
interface NetworkFolders {
    fun find(): kotlinx.coroutines.flow.Flow<FindState>       // cold; collection = the page's lifetime (lock, sockets)
    suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult
    suspend fun shares(session: BrowseHandle): ShareListing
    suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing
    suspend fun test(session: BrowseHandle, share: String, path: String, progress: (TestProgress) -> Unit): LocationTestResult
    fun close(session: BrowseHandle)                           // zeroes the password, closes the browse connection
}
// FindState, FoundComputer, BrowseResult, BrowseHandle, ShareListing, FolderListing, TestProgress, BrowseError:
// plain data classes in this file, listed in full by commit 0 from §7 (fields: name, address, source, saved, vpnUp,
// scannedNetwork, searching; error = BrowseError(kind: StorageFailure | AUTH | SMB1 | NO_NETWORK | NOT_FOUND, deepestPath)).
```

### 12.2 `core/.../storage/smb/RemoteContracts.kt` (new, pure JVM)

```kotlin
package app.thebox.core.storage.smb

interface RemoteFile : AutoCloseable {
    @Throws(java.io.IOException::class) fun write(src: ByteArray, off: Int, len: Int, fileOffset: Long)
    /** ≥ 1, or −1 at EOF; may be short. */
    @Throws(java.io.IOException::class) fun read(dst: ByteArray, off: Int, len: Int, fileOffset: Long): Int
    /** Posted to box-smb; never waits (A-B1). */
    fun closeAsync()
}
enum class OpenMode { CREATE_OVERWRITE, OPEN_EXISTING }   // CREATE once per storage; reopen = OPEN_EXISTING (A-S2)
interface RemoteShare {
    val epoch: Long
    @Throws(java.io.IOException::class) fun open(path: String, mode: OpenMode, deleteOnClose: Boolean = false): RemoteFile
    @Throws(java.io.IOException::class) fun mkdirs(path: String)
    @Throws(java.io.IOException::class) fun list(path: String, max: Int): List<RemoteEntry>
    @Throws(java.io.IOException::class) fun delete(path: String)
    @Throws(java.io.IOException::class) fun freeBytes(): Long
    @Throws(java.io.IOException::class) fun lastWriteMs(path: String): Long        // server time (A-N2)
}
data class RemoteEntry(val name: String, val directory: Boolean, val hidden: Boolean, val sizeBytes: Long)
enum class FaultClass { CANCEL, LINK_LOST, SLOW, FULL, ACCESS, REMOVED }        // §4.3, in classification order
data class RemoteFault(val cls: FaultClass) { val failure: app.thebox.core.storage.StorageFailure? }

/** One owner of liveness (A-S1, lead ruling 6): the keeper and the storages both go through it. */
interface RemoteLink {
    val epoch: Long
    /** Waits (bounded by [deadlineMonoMs]) for READY; throws IOException(UNREACHABLE) when it passes. */
    @Throws(java.io.IOException::class) fun share(deadlineMonoMs: Long): RemoteShare
    /** Single flight for [failedEpoch]; others wait; the dead Connection is close(true)d first. */
    @Throws(java.io.IOException::class) fun reconnect(failedEpoch: Long, deadlineMonoMs: Long): RemoteShare
    /** CANCEL first (interrupt anywhere in the chain, A-B3), then transport, then timeout, then statuses. */
    fun classify(t: Throwable): RemoteFault
    fun retain(); fun release()                    // open-file refcount (background disconnect, §5.3)
    val ioTimeoutMs: Long                           // SMB_IO_TIMEOUT_MS or _SMALL_POOL_MS (§4.3)
}
```

### 12.3 `core/.../playback/timeshift/TimeshiftContracts.kt` (additive)

```kotlin
interface RingStorage : AutoCloseable {
    // … existing members unchanged; KDoc amended (A-S4, A-B1, A-B2):
    //  - availableBytes(): a non-internal storage reports free − (its reserve − FREE_SPACE_RESERVE_MIN / 2), ≥ 0.
    //  - close()/delete(): a [blocking] storage returns at once and finishes on its own thread.
    /** True when pwrite/pread may block on a network or removable device: the ring then gets its own flusher (A-B2). */
    val blocking: Boolean get() = false
}

/** A remote ring storage (§4). */
interface RemoteRingStorage : RingStorage {
    val gone: Boolean
    val lastFailure: app.thebox.core.storage.StorageFailure?
    /** A reconnect or a write timeout happened: never reuse this file across reset() (A-B4). */
    val needsFreshFile: Boolean
    /** Bytes ever written to the remote file (0 = nothing reached the folder; FallbackRingStorage may switch). */
    val remoteBytesWritten: Long
    /** Once per storage, on the flusher; false on failure or when [deadlineMonoMs] passes (§5.2). */
    fun create(deadlineMonoMs: Long): Boolean
    fun ratesBps(): LongArray                              // [write, read] over the last 10 s (stats)
}

sealed interface RingTarget {
    val locationId: String; val kind: app.thebox.core.storage.StorageKind; val limits: app.thebox.core.storage.StorageLimits; val name: String
    data class Local(override val locationId: String, override val kind: app.thebox.core.storage.StorageKind,
                     override val limits: app.thebox.core.storage.StorageLimits, override val name: String,
                     val dir: java.io.File, val totalBytes: Long, val availableBytes: Long) : RingTarget
    class Remote(override val locationId: String, override val kind: app.thebox.core.storage.StorageKind,
                 override val limits: app.thebox.core.storage.StorageLimits, override val name: String,
                 val availableBytes: Long, val ready: Boolean,
                 /** No I/O (Must, A-B1). */
                 val open: (fileName: String, capacityBytes: Long) -> RemoteRingStorage) : RingTarget
}
data class RingTargetChoice(val target: RingTarget, val chosenId: String, val chosenKind: app.thebox.core.storage.StorageKind,
                            val fallback: app.thebox.core.storage.LocationFallback?, val lastKnownGood: Boolean)

/** :data (StorageTargetsImpl). [current] never blocks and does no I/O (Must, §5.1). No awaiting variant (A-N4). */
interface RingTargetSource {
    fun current(purpose: app.thebox.core.storage.StoragePurpose): RingTargetChoice
    fun warm()
    /** Synchronous, before the stop is published (A-N5); [sinceFirstWriteMs] drives the grace (§5.4). */
    fun onStorageFailure(locationId: String, stop: RecordStop, failure: app.thebox.core.storage.StorageFailure?, sinceFirstWriteMs: Long)
    /** The file named for a target in this process: "ring-<deviceId>-<pid>-<n>.bin" for SMB (§3.5). */
    fun nextRemoteFileName(locationId: String): String
}
```

`TimeshiftPolicy` (additive overloads; the old ones delegate with `StorageLimits.internal(total)`; WP-S2 implements, A-S13):

```kotlin
fun capacityBytes(lengthMs: Long, bytesPerSec: Long, availableBytes: Long, limits: app.thebox.core.storage.StorageLimits, currentFileBytes: Long): Long
fun lengthMs(setting: app.thebox.core.settings.TimeshiftLength, totalStorageBytes: Long, limits: app.thebox.core.storage.StorageLimits): Long?
```

### 12.4 Other commit-0 edits (complete list, A-S13)

**`:core`**
- `RingFlusher.kt` / `TsRingImpl.kt`:
  - `RingFlusher(threadName, ownedBy: TsRingImpl? = null)`: an owned flusher's thread exits after its ring unregisters;
  - the production `TsRingImpl` constructor picks `if (storage.blocking) RingFlusher("box-ts-flush-net", this) else RingFlusher.shared`;
  - signatures only, bodies in WP-S1.
- `TimeshiftConstants.kt`: `RING_RAM_BLOCKS_EXTERNAL` (160), `RING_RAM_BLOCKS_EXTERNAL_SMALL` (96), `REMOTE_POOL_MEMORY_CLASS_MB` (192).
- New `core/.../storage/StorageConstants.kt` (Appendix A), and `StorageLimits` values.
- `core/.../storage/FallbackRingStorage.kt`: stub (WP-S2 fills).
- `core/.../storage/smb/SmbRingStorage.kt`: stub (WP-S1 fills).
- `SettingsStore.kt`: `Domains.STORAGE = "storage"`. `SettingsDomains.kt`: `StorageDomain` (backed up), registered by WP-S1's module.
- `SettingsContracts.kt` (append-only):
  - the routes of §9.2;
  - `SettingsRoute.PhoneSetup` gains `browse: Long? = null`;
  - `PhonePurpose.NETWORK_FOLDER`;
  - `TextHint.HOST`;
  - `Badge.IN_USE`, `Badge.SAVED`.
- `tree/PlaybackRows.kt`: `PAUSE_REWIND` added; the three time-shift ids moved out. New `tree/PauseRewindRows.kt` (TIMESHIFT, TIMESHIFT_LOCATION SIMPLE; HOLD, PROTECT ADVANCED). `tree/SettingsTree.kt`: sources and `sectionOf`. `SimpleRowCountTest.kt`: `SUB_PAGE_SOURCES`.
- `setup/lan/LanContracts.kt`:
  - `PhoneSubmission.NetworkFolder(host, share, path, user, domain, password: CharArray?, guest)` with `toString` = host, share, `user=<set>`, `password=****`;
  - `data class PhoneContext(host: String?, share: String?, shares: List<String>)`;
  - `PhoneLink.start(purpose, context: PhoneContext? = null)`;
  - `fun interface PhoneContextProvider { fun context(browse: Long): PhoneContext? }`.
- `full/PlayerContracts.kt`:
  - `LocalBufferInfo` gains `storage`, `storageFailure`, `fallback`, `storageName`, `locationId`, all with defaults;
  - `PlayerToast` appends `SAVE_LOCATION_FALLBACK`, `PAUSE_RESUME_LOST_STORAGE`;
  - `PlayerEffects.fallbackToastDue(locationId, reason): Boolean = false`.

**`:player`**
- `api/PlaybackApi.kt`: `LocalBufferState` gains the same fields, plus `storageRatesBps` (excluded from equality).
- `internal/Seams.kt`: `TimeshiftGrant` gains `choice: RingTargetChoice` (defaulted).
- `TimeshiftModule.kt`: `@BindsOptionalOf RingTargetSource`.
- New `timeshift/InternalRingTargets.kt` (commit 0 only, A-S5).

**`:data`**
- `contracts/DataContracts.kt`: `SecureBlobStore.REF_SMB_PREFIX = "smb-"`.

**Build**
- `gradle/libs.versions.toml`: `smbj = "0.15.0"`, `dcerpc = "0.12.13"`, `bouncycastle = "1.85.2"`; libraries `smbj`, `dcerpc`, `bcprov-jdk18on`.
- `data/build.gradle.kts`: `implementation(libs.smbj)`, `implementation(libs.dcerpc)`, `implementation(libs.androidx.lifecycle.process)` (A-S13); slf4j unbound.
- `app/build.gradle.kts`: `packaging { resources { excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF" } }`.
- `app/proguard-rules.pro`: a placeholder block for smbj/mbassador/dcerpc (WP-S1 fills it from S-2).
- `app/src/main/AndroidManifest.xml`: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.

**`:ui`** (sequenced `when` edits, as TS §18)
- `full/PlayerText.kt`: the new toast kinds;
- `setup/PhoneSetupScreen.kt` `titleOf`: `NETWORK_FOLDER`.

**Docs**
- `00-master-plan.md` §2/§3: the pointer line, the smbj version note and the targetSdk 37 `ACCESS_LOCAL_NETWORK` note.

---

## 13. Failure modes

| Failure | Detection | Behaviour |
|---|---|---|
| Folder unreachable at tune, failure known this process | keeper BACKOFF / no network | this device; toast once (owner ruling 1) |
| Folder not yet confirmed at launch, last-known-good | `lastKnownGood` | SMB chosen; if not created by `SMB_OPEN_DEADLINE_MS`: switch to this device, toast once (§5.2) |
| No last-known-good, still connecting | keeper CONNECTING | this device, silently |
| Password missing or refused | `getBlob` / `LOGON_FAILURE` | AUTH_FAILED; this device; toast for 3 processes; amber value |
| Viewer seeks while a reader is in an SMB READ | interrupt | CANCEL: no reconnect; the writer's concurrent WRITE continues (A-B3) |
| NAS disk asleep at the first write | slow CREATE/WRITE | normally hidden by the `.wake` write. Else within the grace: RAM-only for the cycle, no demotion; I-SL-21 |
| Wi-Fi roam, TCP survives | WRITE completes late | the pool absorbs it; no reconnect |
| Transport lost, reconnect ≤ budget | LINK_LOST | invisible; `needsFreshFile` |
| Transport lost, no reconnect | budget passes | `DISK_ERROR (UNREACHABLE)`; RAM-only; toast; demoted (outside the grace) |
| Folder slower than the stream | I/O timeout or pool full | one SLOW path: `DISK_ERROR (SLOW)` / `DISK_TOO_SLOW`; toast; demoted (outside the grace) |
| Stale write after a timeout or reconnect | — | never reaches a reused file: fresh name next cycle (§4.5) |
| Folder full | free-space watch / `DISK_FULL` | growth halts at the reserve; at write: `DISK_ERROR (FULL)` |
| Our file deleted on the server | reopen | `STORAGE_EVICTED (REMOVED)` |
| Access revoked | `ACCESS_DENIED` | `DISK_ERROR (ACCESS)` |
| Old handle lingers after a silent drop | reopen | shared flags, `FILE_OPEN`: no sharing violation, no truncation |
| Behind live and the link dies | `pread` past the budget | live, with the "back at live" toast |
| Paused and the link stalls | `flushWait` | `DISK_TOO_SLOW`, then FULL_WHILE_PAUSED; resume from the file if readable, else PAUSE_RESUME_LOST_STORAGE (A-S3) |
| SMB stall at a zap to internal | — | dedicated flusher: the internal ring is unaffected (A-B2) |
| Zap while the link is half-dead | — | no I/O on `prepare`; close/delete posted (A-B1) |
| smbj thread hang (facts §1.3 #875) | bounded waits; keeper watchdog `SMB_WATCHDOG_MS` | the link is `close(true)`d and rebuilt |
| Two boxes on one folder | distinct names | own sweep only; foreign files after 48 h of server time |
| Process death | — | own files swept at the next start; else 48 h |
| USB removed while saving | `MEDIA_EJECT` / EIO | stop on the receiver; close on `box-ts-discard` at once |
| USB chosen, not plugged | `VolumeCatalog` | this device; toast once |
| Restored unknown USB | restore | choice reset to internal (A-N7) |
| VPN up while finding | `vpnActive()` | the sweep uses Wi-Fi/Ethernet only; amber VPN line |
| SMB1-only server, srvsvc refused, nothing found | §7.7 | reason + next step; typed share name |
| Phone page: wrong password | sign-in fails | mirrored error; up to `PAIR_MAX_WRONG`, then the server stops |
| Phone page: success | — | server stops; TV replaces the QR page |

---

## 14. Performance and memory gates

| Gate | Value | Measured by |
|---|---|---|
| Zap with SMB chosen (**hard**) | P50 and P90 ≤ 1.05 × the same device with internal | I-SL-3 |
| Link calls on the `prepare` path | **0** (the real factory, every case of §5.5) | `SmbPrepareNoIoTest` (Must) |
| Cold start with SMB chosen | first frame ≤ internal; first connect after the first frame | I-SL-2 |
| Extra heap, remote pool 160 | ≤ 10.7 MiB (pool 10.0 + index ≤ 0.51 + staging) + smbj ≤ 1.5 MiB; pool 96: ≤ 6.6 + 1.5 | heap dump I-SL-10 |
| Heap on a target switch | ≤ steady + 1 pool (the pool size is unchanged across fallback; old and new pools coexist only until GC) | I-SL-22 (A-S7) |
| Allocation rate, SMB steady at 1 MB/s | ≤ `SMB_ALLOC_BPS_MAX` (3 MB/s); 0 GC pauses > 50 ms in 30 min | I-SL-10 |
| Flusher CPU, SMB at 1 MB/s, signing on | ≤ 8 % of one core, Fire TV Stick 4K | I-SL-11 |
| Seek → first frame, reading from SMB | P50 ≤ `SEEK_NETWORK_P50_MS` (800), P90 ≤ `SEEK_NETWORK_P90_MS` (1,500); 5 GHz, NAS wired | I-SL-6 |
| Hiccup absorbed (pool 160 devices only) | a 6 s outage at 1 MB/s: no stop, nothing visible | I-SL-7 |
| Live rebuffers caused by saving to SMB | ≤ an Off session's, 30 min | I-SL-12 |
| Discovery | NSD result ≤ 2 s; sweep ≤ 4 s; 0 sockets and lock released ≤ 500 ms after the page closes | I-SL-15, `ComputerFinderTest` |
| APK delta (release, R8) | smbj + bcprov + asn-one + mbassador ≤ `SMBJ_APK_BUDGET`; dcerpc + commons-lang3 + commons-io (+ any Guava bump) ≤ `SHARE_LISTER_APK_BUDGET`; measured values written into §7.5 | S-2 |

---

## 15. Spikes

S-1 and S-2 are specified in §0a and run before commit 0. Their results go into §7.5 (APK deltas), §3.2 (`VOLUME_ENABLED`, nlink) and §14 (CPU).

---

## 16. Tests

### 16.1 JVM (`:core`, pure)

**`FakeRemoteLink` / `FakeRemoteShare` / `FakeRemoteFile`** (core testFixtures): per-call latency, `stallUntil`, `dropTransport()` (LINK_LOST until reconnect), `timeoutNext()` (SLOW), reconnect latency, `deleteFile()`, `fullAt()`, `denyWrites()`, epoch, a call counter, and a record of every open mode.

| Test | Owner | Checks |
|---|---|---|
| `SmbRingStorageTest` | WP-S1 | no I/O in construction, `writeHeader`, `allocate`, `close`, `delete`; `create` once with CREATE_OVERWRITE; header before data; `pread` loops; a reader with no handle never opens; `close`/`delete` return before the posted work runs |
| `ReopenNeverTruncatesTest` | WP-S1 | A-S2 |
| `SmbFaultTest` | WP-S1 | the classification order of §4.3; interrupt → CANCEL with the epoch unchanged; a writer `write` concurrent with an interrupted reader succeeds; the interrupted request's late response is dropped (A-B3) |
| `SmbReconnectTest` | WP-S1 | single flight across flusher + reader; same offset; budget; min gap; `needsFreshFile` after a reconnect and after a write timeout |
| `TsRingOverSmbTest` | WP-S1 | **real dedicated `RingFlusher` thread and real time** (A-S9): a 3 s stall with a proportional pool is absorbed; the same stall with a smaller pool → SLOW path; a transport loss past the budget → `DISK_ERROR`, RAM-only, the file part readable after recovery. Deterministic mode only for the TS §3.2 invariants |
| `DedicatedFlusherTest` | WP-S1 | an SMB ring stalled in `pwrite` does not delay a new internal ring's flushes; the owned flusher thread exits after close (A-B2) |
| `FallbackRingStorageTest` | WP-S2 | create ok → primary; deadline passes → secondary, header first, `fallbackReason`; no switch once `remoteBytesWritten > 0`; capacity respects the internal reserve; the wear counter counts the secondary |
| `StorageLimitsTest` | commit 0 / WP-S2 | caps, reserves, `availableBytes` offset, pool sizes by `memoryClass`, Auto length per kind |
| `ManualAddressParserTest` | WP-S1 | every §7.2 form and the errors; the same vectors as `app.js` |
| `NbnsCodecTest`, `DiscoveryMergeTest` | WP-S3 | |

### 16.2 `:player`, `:data`, `:ui`

| Test | Owner | Checks |
|---|---|---|
| `SmbPrepareNoIoTest` | WP-S2 (Must) | the real factory, §5.5's list |
| `FactoryTargetTest` | WP-S2 | reuse/rebuild rules of §5.1 (`needsFreshFile`, pool size, location id); one pool size across fallback; `onStorageFailure` called before the stop is published |
| `VolumeWatcherTest` | WP-S2 | Robolectric broadcasts; close posted off main |
| `RingTargetSourceTest` | WP-S1 | every row of §5.1; last-known-good persisted; grace; demotion doubling; toast counters |
| `SmbSweepTest` | WP-S1 | own other-pid/other-n files; foreign by server time vs `.wake`; failures ignored |
| `StorageLocationsImplTest` | WP-S1 | password only in `SecureBlobStore`; arrays zeroed; no password in the domain export; the audit gets user name and password; restore → SIGN_IN_NEEDED; unknown USB → internal |
| `SmbjRemoteLinkTest` | WP-S1 | `SmbConfig` timeouts equal the constants; two `SMBClient` instances; teardown uses `close(true)` |
| `ComputerFinderTest` | WP-S3 | NSD listener quirks (new per search, no stop before started, late start after stop); the sweep uses the `LanAddressFinder` address and skips VPN; sockets closed by the stop path; lock held for the flow's lifetime |
| `PhoneApiNetworkFolderTest` | WP-S6 | the endpoint only for NETWORK_FOLDER; body buffer zeroed; password never in `/api/state`; `Redact` registration; one shot after success; `PAIR_MAX_WRONG` failures stop it; the debug dump excludes the body |
| `StoragePagesTest` | WP-S4 | rows and texts; §9.3 journeys through `SettingsMachineImpl` (TV and phone sign-in, typed path landing, missing folder, ← up, BACK out); no credential in routes or `toString`; focus rules of U-S9 |
| `SettingsReachabilityTest` | WP-S4 | browse fixture depth 8 (U-B3); every menu option also a row |
| `SimpleRowCountTest`, `EffectTimingCoverageTest`, `WpaPagesTest`, `SettingsBackLadderTest` | WP-S4 | updated (§9.5) |
| Machine rows | WP-S4 | SAVE_LOCATION_FALLBACK when fallback first appears, the once/3-process rules; arg encoding; PAUSE_RESUME_LOST_STORAGE |

**Opt-in real-server tests** (`:data`, skipped unless `THEBOX_SMB_URL` is set; the password comes from the environment, never logged):
- `SmbIntegrationTest`: list, `getShares1`, write, read, rm, `lastWriteMs`;
- `SmbConcurrencyTest`: a writer and two reader threads on one handle, 60 s at 2 MB/s, with CRC.

### 16.3 Servers on this Mac (macOS 15.0.1, no Docker; reviewed 2026-10-24, A-S14; lead ruling 9)

| Server | How | Use |
|---|---|---|
| **Homebrew Samba** (primary; **WP-S5's first task, ≤ 1 h**) | `brew install samba`; `tools/smbtest/smb.conf` sets every path (`lock dir`, `state directory`, `cache directory`, `pid directory`, `private dir`, `ncalrpc dir`) under a scratch dir, `smb ports = 4445`, a guest share and a user share (`smbpasswd -c`). `smbd -F --debug-stdout --configfile=…`. On Samba ≥ 4.17, srvsvc runs in `samba-dcerpcd`, which `smbd` must find and spawn. **Exit check:** `smbclient -L //127.0.0.1 -p 4445 -U test` lists the shares | JVM opt-in tests; the emulator over `10.0.2.2:4445` (manual entry with a port) |
| **macOS File Sharing** (fallback; `/usr/sbin/smbd`) | System Settings → General → Sharing → File Sharing; add a folder; Options → "Windows File Sharing" for a test account. Port 445; guest off | everything except guest cases if Samba fails; the emulator over `10.0.2.2:445`; NSD on the LAN |
| **`tools/smbproxy/smbproxy.py`** (new) | a TCP proxy controlled over HTTP like the fake panel: `delay_ms`, `rate_bps`, `stall_ms`, `drop` (RST), `blackhole`, `close_after_bytes`, **`delay_first_ms`** (disk spin-up, A-B4) | every fault case |
| Windows 11 PC (owner's, if available) | a shared folder with a local user | sweep + NBNS naming, guest signing, NTLM |

Discovery cases are device-only: the emulator's NAT carries no multicast, and its /24 is virtual. The fake Xtream panel supplies the stream.

### 16.4 Device suite (`app/src/androidTest/.../SaveLocationTest.kt`, WP-S5)

| Case | What |
|---|---|
| I-SL-1 | manual entry to Samba, Test GOOD, pause 5 min, resume where paused; the server file has our name pattern |
| I-SL-2 | cold start with SMB chosen: first frame ≤ internal; the first connect after the first frame |
| I-SL-3 | zap gate (hard), 30 zaps, SMB READY and last-known-good |
| I-SL-4 | server stopped before launch, failure seen → this device, one toast, amber value |
| I-SL-4b | server stopped, last-known-good, fresh process → SMB chosen, switch at the deadline, one toast, rewind works on this device |
| I-SL-5 | wrong password → Sign in needed; fix via `LocationDetail`; the next zap saves to SMB |
| I-SL-6 | seek latency reading from SMB |
| I-SL-7 | `stall_ms=6000` at 1 MB/s → no stop (pool-160 devices) |
| I-SL-8 | `blackhole` 20 s → `DISK_ERROR`, live continues, toast, demoted, then SMB again after `SMB_DEMOTE_MS` + keepalive |
| I-SL-9 | `rate_bps=600000` → SLOW wording |
| I-SL-10 | 30 min behind live, wrapping, on SMB: heap and GC gates |
| I-SL-11 | flusher CPU (hardware) |
| I-SL-12 | live rebuffers SMB vs Off, including a forced drop and catch-up |
| I-SL-13 | paused 3 min, `drop` and restore within 30 s, resume → continues from the file |
| I-SL-14 | file deleted on the server → REMOVED wording |
| I-SL-15 | on the LAN (Fire TV): NSD finds the Mac; the sweep finds the Windows PC and a NAS without Bonjour; NBNS names; VPN app on → amber line, sweep on Wi-Fi; sockets and lock gone after BACK |
| I-SL-16 | two boxes on one folder: distinct files; kill and restart one: its own stale file swept; the other's untouched |
| I-SL-17 | USB (if S-1 passes): save, eject behind live → live + toast; FAT32 cap; close off main |
| I-SL-18 | backup/restore on a second install: Sign in needed, toast for 3 processes then none; unknown USB → internal |
| I-SL-19 | five-key reachability of Save to, `LocationDetail` and the browse (depth 8) |
| I-SL-20 | `TimeShiftBufferTest` with internal: no regression |
| I-SL-21 | `delay_first_ms=12000` (disk asleep) → first tune: no stop, or SLOW without demotion (A-B4) |
| I-SL-22 | heap across SMB → fallback → SMB cycles (A-S7) |
| I-SL-23 | phone sign-in against macOS File Sharing: QR, Send, wrong password then right, TV replaces the QR page; `/api/state` never shows the password (U-B1) |
| I-SL-24 | seek repeatedly behind live on SMB for 2 min: epoch unchanged, no reconnect (A-B3) |

---

## 17. Work packages (disjoint files; lead ruling 9)

Rules as TS §18: commit 0 first and green; no WP edits another WP's file, a contract or a build file; agents do not commit.

**Commit 0 (lead):** §12 verbatim; stubs bound in Hilt; `InternalRingTargets` makes the app behave exactly as today until the WPs land.

| WP | Model | Files (new unless "edit") | Tests |
|---|---|---|---|
| **WP-S1** SMB backend, locations, keeper | Opus | `core/.../storage/smb/SmbRingStorage.kt` (body), `core/.../storage/ManualAddressParser.kt`; edits `core/.../playback/timeshift/RingFlusher.kt`, `TsRingImpl.kt` (owned flusher bodies only); `data/.../storage/StorageLocationsImpl.kt`, `StorageTargetsImpl.kt`, `DeviceIdentity.kt`, `StorageModule.kt`, `NetworkFoldersImpl.kt` (browse, shares, folders), `smb/SmbjRemoteLink.kt`, `smb/SmbSessionKeeper.kt`, `smb/SmbSweeper.kt`, `smb/SmbShareLister.kt`, `smb/LocationTester.kt`, `smb/SmbFaults.kt`; edit `data/.../diag/DiagnosticsImpl.kt` (audit secrets); edit `app/proguard-rules.pro` (the smbj block) | §16.1/§16.2 rows owned by S1 |
| **WP-S2** player integration, USB, policy | Opus | `core/.../storage/FallbackRingStorage.kt` (body); edit `core/.../playback/timeshift/TimeshiftPolicyImpl.kt` (overloads); edits `player/.../timeshift/TimeshiftSessionFactoryImpl.kt`, `TimeshiftStorageImpl.kt`, `AndroidRingStorage.kt` (`preallocate`, `blocking`, posted close for volumes), `TimeshiftStartup.kt`, `controller/PlaybackControllerImpl.kt` (`LocalBufferState` fields only); new `player/.../timeshift/VolumeWatcher.kt`, `VolumeStorage.kt`; edit `app/.../playback/LocalTimelineBackend.kt` (field copy) | rows owned by S2 |
| **WP-S3** discovery | Opus for the finder and its lifetime; Sonnet may write the NBNS codec under this spec | `core/.../storage/discovery/NbnsCodec.kt`, `DiscoveryMerge.kt`; `data/.../storage/discovery/ComputerFinderImpl.kt`, `NsdFinder.kt`, `Port445Sweep.kt`, `NameLookup.kt` | rows owned by S3 |
| **WP-S4** settings pages, strings, machine | Sonnet under this spec, Opus review | `core/.../settings/state/StoragePages.kt` (incl. `BrowseSession`, `PhoneContextProvider` impl, phone submission handling), `StoragePageEnv.kt`; edits `core/.../settings/state/WpaPages.kt`, `SettingsTexts.kt`, `core/.../full/state/PlayerStateMachineImpl.kt` (fallback toasts, args); `app/.../settings/StoragePageEnvImpl.kt`, edit `SettingsBindingsModule.kt`, `app/.../playback/FallbackToastPolicy.kt` (new, `fallbackToastDue`); edits `ui/.../full/PlayerText.kt` (bodies), `ui/.../full/StatsOverlay.kt`, `ui/.../settings/SettingsUi.kt` (badges, progress row, dots); new `ui/src/main/res/values/strings_storage.xml`; edits `strings_player.xml`, `strings_settings.xml`; test edits `WpaPagesTest.kt`, `SettingsBackLadderTest.kt`, `SettingsReachabilityTest.kt` | rows owned by S4 |
| **WP-S5** tools, device suite, docs | Opus for integration; Sonnet for the proxy and README | **first:** `tools/smbtest/smb.conf`, `tools/smbtest/README.md` (the exit check); `tools/smbproxy/smbproxy.py`, `test_smbproxy.py`, `README.md`; `app/src/androidTest/.../SaveLocationTest.kt`; edit `app/src/debug/.../GuideDebugReceiver.kt` (choose a location, force keeper states, print the target and rates); docs: this file's implementation records, the M5 §3.3.13 amendment, `00-master-plan.md` | §16.4 |
| **WP-S6** phone sign-in | **Opus** (credential handling; owner of the phone path) | edits `core/.../setup/lan/PhoneApi.kt` (endpoint, zeroed body, one-shot), `core/.../setup/lan/LanPhoneLink.kt` (context, purpose), `core/.../setup/lan/PhoneScreenModel.kt`; edit `data/.../setup/PhoneLinkImpl.kt`; edits `data/src/main/assets/phone/app.html`, `app.js`, `app.css`; edits `ui/.../setup/PhoneSetupScreen.kt` (title/info bodies), `ui/.../backup/PhoneSetupViewModel.kt` (start with context); new `ui/src/main/res/values/strings_storage_phone.xml` | `PhoneApiNetworkFolderTest`; I-SL-23 with WP-S5 |

**Integration order:**
1. §0a spikes, plus WP-S5's Samba task.
2. WP-S1, WP-S3, WP-S6 and the WP-S5 tools in parallel. WP-S2 against `FakeRemoteLink`; WP-S4 against fake `StorageLocations`/`NetworkFolders`/`PhoneLink`.
3. Lead integration on the emulator (I-SL-1…14, 16, 18–24), then on the Fire TV and Onn (I-SL-3, 6, 10–12, 15, 17), then the code-review pass and the gates (plan §6, plus §14's hard zap gate).

---

## 18. Rejected, and why

| # | Alternative | Why not |
|---|---|---|
| R1 | SAF for USB now | the picker may not exist on TV (M5 §1.11, facts §5); non-primary fds may not be seekable. v2 revisits it for recordings |
| R2 | Hot switch to internal after the first remote write | two storages under one `fileValidFrom..flushed` range (`TsRingImpl.kt:33`); the early switch of §5.2 covers the common case (nothing written yet) |
| R3 | Opening the SMB file in `prepare` | zap-path round trips; violates the hard gate (A-B1) |
| R4 | jcifs-ng | LGPL-2.1; the plan pins smbj |
| R5 | Mirroring the ring to internal | doubles writes on the flash the owner wants to spare |
| R6 | Detecting exFAT to lift the USB cap | FUSE hides the filesystem on API 30+; a wrong guess means `EFBIG` mid-cycle |
| R7 | `ANDROID_ID` as the device id | changes per signing key, privacy-sensitive; a random install id suffices |
| R8 | Header format v2 with the device id | nothing reads it back |
| R9 | `FILE_DELETE_ON_CLOSE` on the ring | a dropped session would delete the file the reconnect needs |
| R10 | Pipelined `writeAsync` | changes the flusher model; sync 64 KiB writes have ≥ 2.6× headroom at 25 ms |
| R11 | Read-ahead in `SmbRingStorage` | heap; decided by I-SL-6 |
| R12 | jmdns | its own sockets and lock; NsdManager is the platform's |
| R13 | "Save to" as a sixth simple row | re-opens the earlier row ruling |
| R14 | "Save to" as an ADVANCED row | the owner asked for it |
| R15 | A background /24 sweep for "recent computers" | owner ruling 2 |
| R16 | NFS, WebDAV, FTP | not asked for |
| R17 | A longer `PUMP_BLOCK_MAX_MS` for network targets | stalls the upstream socket; the pool absorbs stalls |
| R18 | WS-Discovery | lead ruling 3: the Windows service that answers it is often not running, and the sweep + NBNS find every PC usable over SMB (A-S10, U-S8). A follow-up if I-SL-15 finds a PC they miss |
| R19 | A 4 s I/O timeout plus reconnect on every timeout | fights the pool; breaks NAS spin-up and roams (A-B4) |
| R20 | A hand-written srvsvc NDR codec | more risk than dcerpc, whose Guava is already present (A-N1, lead ruling 4) |
| R21 | `setLength`/SET_INFO at create | buys nothing; zero-fills on router-USB shares (A-S6) |

---

## 19. Not applied (and why)

| Item | Status | Reason |
|---|---|---|
| A-S7 option "hide SMB on `isLowRamDevice`" | not applied | lead ruling 5 chose the 96-block pool with the gate waived instead |
| U-S1 fallback stats/value wording "(NAS was still connecting)" as the *primary* fix | applied only as the U-N4 stats suffix | lead ruling 2 made the mechanism (last-known-good + lazy open) the fix |
| U-B1 "`PAIR_MAX_WRONG` failed sign-ins" vs A-S12 "the session closes after one accepted sign-in" | both applied | one shot = the first *successful* sign-in ends the session; failures count up to `PAIR_MAX_WRONG` (§7.4) |
| U-N3 share ordering heuristics and hiding `homes` | not applied | plain A–Z is predictable; hiding a share the user may want is risky; cheap to add after I-SL-15 if owners ask |
| U-N5 | applied | content descriptions and a static dot row with animations off (§9.4) |
| A-S12 naming `SMB_SIGNIN` and `/api/smb` | renamed | `PhonePurpose.NETWORK_FOLDER` and `/api/network-folder` (U-B1), because the same purpose also adds a folder from the phone with no host chosen ("Add from your phone"). A-S12's rules all hold |
| N-3: zeroing the password's JSON `String` | not possible | kotlinx.serialization produces an immutable `String` for the field. The body buffer and every `CharArray` are zeroed. It is the same residue as the Xtream password path today; a streaming char parser is out of proportion |
| SMB passwords in "Include logins" backups | not applied | the owner's brief says backups carry no password |
| Owner Q3 (4 h rewind on network folders) | open | §20 |

---

## 20. Open question for the owner

1. **When saving to a network folder, offer a longer rewind (4 hours)?** Recommended: not now. Keep 15 min … 2 hours everywhere and revisit after the hardware week.

---

## 21. Unverified (the lead checks before code depends on it)

| # | Assumption | Check |
|---|---|---|
| U-1 | smbj 0.15.0 is safe with one writer and several readers on one `File` (source reading only, facts §1.6) | `SmbConcurrencyTest` on Samba, macOS smbd, Windows |
| U-2 | `ProxySocketFactory(ms)` bounds connect, and `withTimeout` bounds every wait; smbj's timeout exception is distinguishable from a dead transport (§4.3 class 3 vs 2) | `SmbFaultTest` against smbproxy `blackhole` vs `drop` |
| U-3 | After `close(true)` of a dead cached `Connection`, a new connect/auth/`connectShare` and `FILE_OPEN` continue at the same offsets | I-SL-8, I-SL-13 |
| U-4 | Servers accept a shared-flags reopen while our old handle lingers | I-SL-13 per server |
| U-5 | Signing (and required encryption) CPU on armeabi-v7a sticks fits §14 | S-2, I-SL-11 |
| U-6 | A `getShareInformation()` every 20 s keeps a Windows session alive (autodisconnect) | Windows soak |
| U-7 | NsdManager discovery with our `MulticastLock` works on Fire OS 6–8 | I-SL-15 |
| U-8 | `.local` names are not resolved by the system before API 34; matching NSD results suffices | I-SL-15 |
| U-10 | `getExternalFilesDirs` lists sticks on the target TVs; append-growth on vfat/exFAT; nlink behaviour; EJECT ordering | S-1 |
| U-12 | The catch-up burst after a recovered stall does not make live rebuffer on 5 GHz | I-SL-12 |
| U-13 | R8 keep rules (mbassador `@Handler` incl. `SMBClient.connectionClosed`, dcerpc); the OSGI exclude | S-2 |
| U-14 | NTLM on API 25 with desugaring (facts §1.3 #807) | S-2 |
| U-15 | dcerpc 0.12.13 runs on smbj 0.15.0 (compiles clean, never run), and against `samba-dcerpcd` | `SmbIntegrationTest` |
| U-17 | NBNS node status is answered by Windows (NetBT default) and Samba `nmbd` | I-SL-15 |
| U-18 | Homebrew `samba` runs unprivileged on 4445 on macOS 15 with srvsvc | WP-S5 first task |
| U-19 | `getStorageVolume(File).uuid` is stable for FAT/exFAT sticks on Fire OS | S-1 |
| U-20 | Media3's loader tolerates a `pread` blocked up to the I/O timeout | I-SL-13 |
| U-21 | Gradle resolves dcerpc's `guava:33.4.0-jre` to the `-android` variant alongside Media3's `33.3.1-android` | S-2 (`:app:dependencies` after commit 0) |
| U-22 | Writing 4 KiB to `.wake-<deviceId>` spins up a hibernated NAS disk (Synology/QNAP/TrueNAS), and the keepalive statfs does not | I-SL-21 on a real NAS if available |
| U-23 | The server's LastWriteTime of `.wake` is updated per write on Samba, Windows and macOS (the sweep's clock) | `SmbSweepTest` opt-in |

---

## Appendix A. Constants

`core/.../storage/StorageConstants.kt` unless noted. Sizes in bytes, times in ms.

| Constant | Value | Used in |
|---|---|---|
| `INTERNAL_ID` | `"internal"` | §3.1 |
| `SMB_DEFAULT_PORT` | 445 | §3.3 |
| `TIMESHIFT_DIR_SMB` / `RECORDINGS_DIR_SMB` | `"TheBox-rewind"` / `"TheBox-recordings"` | §3.1, §11 |
| `WAKE_FILE_BYTES` | 4,096 | §5.3, §7.8 |
| `DEVICE_ID_HEX_CHARS` | 12 | §3.5 |
| `FOREIGN_STALE_MS` | 172,800,000 (48 h) | §3.5 |
| `LABEL_MAX_CHARS` | 24 (the link value uses ≤ 16) | §3.1, §9.1, §10 |
| `FAT32_MAX_FILE_BYTES` | 4,294,967,295 | §3.2 |
| `VOLUME_MAX_RING_BYTES` | `(FAT32_MAX_FILE_BYTES − RING_HEADER_BYTES) / RING_BLOCK_BYTES × RING_BLOCK_BYTES` = 4,294,954,752 | §3.2, §3.4 |
| `VOLUME_FREE_SPACE_RESERVE` | 536,870,912 | §3.4 |
| `NETWORK_MAX_RING_BYTES` | 34,359,738,368 (32 GiB) | §3.4 |
| `NETWORK_FREE_SPACE_RESERVE` | 2,147,483,648 | §3.4 |
| `RING_RAM_BLOCKS_EXTERNAL` (`TimeshiftConstants`) | 160 (10.0 MiB) | §3.4, §4.4 |
| `RING_RAM_BLOCKS_EXTERNAL_SMALL` (`TimeshiftConstants`) | 96 (6.0 MiB) | §3.4 |
| `REMOTE_POOL_MEMORY_CLASS_MB` (`TimeshiftConstants`) | 192 | §3.4 |
| `SMB_CONNECT_TIMEOUT_MS` | 3,000 | §4.3 |
| `SMB_IO_TIMEOUT_MS` / `SMB_IO_TIMEOUT_SMALL_POOL_MS` | 8,000 / 4,000 | §4.3, §4.4 |
| `SMB_SO_TIMEOUT_MS` | 45,000 | §4.3 |
| `SMB_RECONNECT_BUDGET_MS` | 6,000 | §4.3 |
| `SMB_RECONNECT_MIN_GAP_MS` | 2,000 | §4.3 |
| `SMB_MAX_IO_BYTES` | 262,144 | §4.3 |
| `SMB_OPEN_DEADLINE_MS` / `SMB_OPEN_DEADLINE_SMALL_POOL_MS` | 6,000 / 3,000 | §5.2 |
| `SMB_LAST_GOOD_MS` | 604,800,000 (7 days) | §5.1 |
| `SMB_FRESH_MS` | 90,000 | §5.1 |
| `SMB_SPACE_MAX_AGE_MS` | 120,000 | §4.1, §5.1 |
| `SMB_FIRST_CONNECT_DELAY_MS` | 2,000 (or the first frame, whichever is first) | §5.3 |
| `SMB_KEEPALIVE_MS` | 20,000 | §5.3 |
| `SMB_WATCHDOG_MS` | 15,000 | §13 |
| `SMB_BACKOFF_MS` | 5,000 / 15,000 / 60,000 / 300,000 | §5.3 |
| `SMB_BACKGROUND_DISCONNECT_MS` | 30,000 | §5.3 |
| `SMB_FIRST_WRITE_GRACE_MS` | 20,000 | §5.4 |
| `SMB_DEMOTE_MS` / `SMB_DEMOTE_MAX_MS` | 120,000 / 1,800,000 | §5.4 |
| `SMB_DEMOTE_REPEAT_WINDOW_MS` | 600,000 | §5.4 |
| `SIGN_IN_TOAST_PROCESSES` | 3 | §5.1 |
| `SMB_LIST_MAX_ENTRIES` | 2,000 | §7.6 |
| `SMB_SKIP_FOLDERS` | `$RECYCLE.BIN`, `System Volume Information`, `#recycle`, `@eaDir`, `.Trashes`, `.TemporaryItems` | §7.6 |
| `SMB_TEST_MAX_BYTES` | 16,777,216 (whole blocks) | §7.8 |
| `TEST_WRITE_MS` / `TEST_READ_MS` | 6,000 / 6,000 | §7.8 |
| `TEST_RTT_SAMPLES` | 5 | §7.8 |
| `TEST_RTT_SLOW_MS` | 40 | §7.8 |
| `TEST_WAKE_NOTE_MS` | 3,000 | §7.8 |
| `HD_BPS` / `UHD_BPS` | 1,000,000 (= `ASSUMED_BYTES_PER_SEC`) / 3,500,000 | §7.8 |
| `TEST_GOOD_FACTOR` / `TEST_SLOW_FACTOR` | 3.0 / 1.5 | §7.8 |
| `TEST_FRESH_MS` | 2,592,000,000 (30 days) | §9.2 |
| `DISCOVERY_TOTAL_MS` | 8,000 | §7.1 |
| `SWEEP_MAX_HOSTS` | 254 | §7.1 |
| `SWEEP_PARALLEL` | 32 | §7.1 |
| `SWEEP_CONNECT_TIMEOUT_MS` | 400 | §7.1 |
| `NBNS_PORT` / `NBNS_TIMEOUT_MS` | 137 / 600 | §7.1 |
| `REVERSE_DNS_TIMEOUT_MS` | 800 | §7.1 |
| `SEEK_NETWORK_P50_MS` / `SEEK_NETWORK_P90_MS` | 800 / 1,500 | §14 |
| `SMB_ALLOC_BPS_MAX` | 3,000,000 | §14 |
| `SMBJ_APK_BUDGET` / `SHARE_LISTER_APK_BUDGET` | 2,500,000 / 1,500,000 (S-2 records the measured values in §7.5) | §7.5, §14 |
| `StorageFlags.VOLUME_ENABLED` | true (false if S-1 fails) | §0a, §3.2 |


---

## Implementation record — commit 0 (2026-10-24)

In worktree `agent-a368be74533143090` (branch `worktree-agent-a368be74533143090`, base `e8b1f7d`), uncommitted (the lead commits). No emulator run. `ffmpeg/libs/*.aar` and `local.properties` were copied in and are gitignored.

**Builds.** `./gradlew :core:test :app:assembleDebug` plus `:data`, `:ui`, `:app` unit tests and `:app:compileDebugAndroidTestKotlin`: **green**. `:player:testDebugUnitTest` was run on its own: **green** (no hang this time). `:app:minifyReleaseWithR8`: **green** after two `-dontwarn` lines (below). `:app:assembleRelease` stops at `validateReleaseSigning`, because this worktree has no `keystore.properties`. That is expected; R8 itself was run through `minifyReleaseWithR8`.

**Tests** (all pass, none skipped):

| Task | Count |
|---|---|
| `:core:test` | 3,464 |
| `:core:stateOnRealNavigator` | 358 |
| `:data:testDebugUnitTest` | 340 |
| `:player:testDebugUnitTest` | 480 |
| `:ui:testDebugUnitTest` | 380 |
| `:app:testDebugUnitTest` | 69 |

New tests:
- `core/.../storage/StorageLimitsTest` (§16.1, the commit-0 part): caps, reserves, the `availableBytes` offset, pools by `memoryClass`, Auto length per kind, and the policy overloads against the old ones.
- `core/.../storage/StorageContractsTest`: the `storage` domain's defaults (`{}` reads as today), a location round trip with no password, no credential in any `toString`, `RemoteFault.failure`, `blocking` defaults to false, the Appendix A values, and the new routes' section and parents.
- `core/.../storage/smb/FakeRemoteLinkTest`: the fake does what §16.1 says.

**Dependencies (U-21, answered).** In `releaseRuntimeClasspath`:
- Guava resolves to `33.4.0-jre` by conflict resolution with Media3's `33.3.1-android`. `dependencyInsight` shows that Gradle picks the **`androidRuntimeElements` variant** (`org.gradle.jvm.environment = android`), so the jar is the Android flavour, one minor version up. U-21 holds.
- Also on the classpath: smbj 0.15.0 (dcerpc asks for 0.12.2), bcprov-jdk18on 1.85.2 (another module asks for 1.78.1), asn-one 0.6.0, mbassador 1.3.2, slf4j-api 2.0.18 (no binding), commons-io 2.7, commons-lang3 3.4, and lifecycle-process 2.10.0.

**R8 (early read for S-2).** With nothing calling smbj yet, R8 failed on missing classes:
- `javax.el.*`, from mbassador's optional EL filters;
- `org.ietf.jgss.*`, from smbj's Kerberos `SpnegoAuthenticator`.

`-dontwarn javax.el.**` and `-dontwarn org.ietf.jgss.**` in the placeholder block fix it; both paths are unused on Android (NTLM only). This does **not** test the keep rules: no code reaches smbj yet, so R8 strips it all. The APK delta and the `@Handler` rules stay S-2's and WP-S1's.

### What was done (§12.4, item by item)

`:core`
- **New `storage/StorageContracts.kt`.** §12.1, plus the browse types §12.1 leaves to commit 0:
  - `NameSource`, `FoundComputer` (with `apple` for the §7.7 Mac hint, and `port`), `FindState`;
  - `BrowseErrorKind` + `BrowseError` (kinds `STORAGE` + `StorageFailure`, `AUTH`, `GUEST_REFUSED`, `SMB1`, `NO_NETWORK`, `NOT_FOUND`, `LIST_FAILED`, `WRITE_REFUSED`, `DATA_MISMATCH`; with `deepestPath`, `vpnUp`, `freeBytes`/`needBytes`);
  - `SignInMode`, `BrowseHandle`, `BrowseResult` (`Opened` / `NeedsSignIn` / `Failed`), `ShareListing`, `FolderListing`, `TestPhase`, `TestProgress`.
- **New `storage/smb/RemoteContracts.kt`.** §12.2, plus one addition (deviation 2).
- **New `storage/StorageConstants.kt`.** Appendix A, plus `StorageFlags.VOLUME_ENABLED`. `SMB_BACKOFF_MS` is a `List<Long>` and `SMB_SKIP_FOLDERS` a `Set<String>`.
- **`TimeshiftContracts.kt`.** §12.3 verbatim: `RingStorage` KDoc and `blocking`; `RemoteRingStorage`, `RingTarget`, `RingTargetChoice`, `RingTargetSource`. The `TimeshiftPolicy` overloads are abstract.
- **`TimeshiftConstants.kt`.** The three pool constants.
- **`RingFlusher.kt` / `TsRingImpl.kt`.**
  - The owned-flusher signature (deviation 1).
  - `RingFlusher.forStorage(storage)` makes the production choice: shared unless `blocking`; then `box-ts-flush-net` for a `RemoteRingStorage` or a `FallbackRingStorage`, else `box-ts-flush-ext`.
  - The production `TsRingImpl` constructor calls it. Nothing is `blocking` yet, so every ring still uses the shared flusher.
- **`TimeshiftPolicyImpl.kt`.** The overloads are implemented (deviation 4), and the old ones delegate with `StorageLimits.internal(total)`. The existing `TimeshiftPolicyTest` passes unchanged.
- **Stubs:**
  - `storage/FallbackRingStorage.kt` (WP-S2): the constructor of §5.2, `switchedTo`, `fallbackReason`, `primary`, `blocking = true`; bodies `TODO("WP-S2")`.
  - `storage/smb/SmbRingStorage.kt` (WP-S1): the constructor of §4.1, `blocking = true`; bodies `TODO("WP-S1")`.
  - `storage/ManualAddressParser.kt` (WP-S1): `Result.Parsed(host, port, share?, path)` / `Result.Invalid(Reason)`; `parse` is `TODO`. WP-S4 and WP-S6 compile against it.
- **Settings.**
  - `Domains.STORAGE`, and `StorageDomain` (backed up; default `StoragePrefs()` = today).
  - `SettingsContracts`: the §9.2 routes (`LocationTest(browse: Long? = null, locationId: String? = null)` serves both forms), `PhoneSetup.browse`, `PhonePurpose.NETWORK_FOLDER`, `TextHint.HOST`, `Badge.IN_USE` / `SAVED`.
  - `PlaybackRows.PAUSE_REWIND` (SIMPLE, NONE).
  - New `PauseRewindRows`: TIMESHIFT and TIMESHIFT_LOCATION SIMPLE NEXT_TUNE; HOLD and PROTECT ADVANCED LIVE; the ids are unchanged.
  - `SettingsTree`: the source, plus `sectionOf` (new routes → PLAYBACK; `PhoneSetup` with NETWORK_FOLDER → PLAYBACK).
  - `SimpleRowCountTest.SUB_PAGE_SOURCES` gains `PauseRewindRows`. Playback stays at 5 and the total at 35.
- **`LanContracts.kt`.** `PhoneSubmission.NetworkFolder`, `PhoneContext`, `PhoneLink.start(purpose, context = null)`, `PhoneContextProvider`. The three implementers (`LanPhoneLink`, `PhoneLinkImpl`, `FakePhoneLink`) take the parameter and ignore it. `FakePhoneLink` records `contexts`.
- **`PlayerContracts.kt`.** The five `LocalBufferInfo` fields (defaults = this device), `SAVE_LOCATION_FALLBACK` and `PAUSE_RESUME_LOST_STORAGE` (KDoc carries the §10 arg format), and `PlayerEffects.fallbackToastDue(…) = false`.
- **testFixtures.**
  - `storage/smb/FakeRemoteLink.kt`: `FakeRemoteLink`, with its inner classes `FakeRemoteShare` and `FakeRemoteFile`. It provides every §16.1 knob: latency, `stallUntil`, `dropTransport`, `timeoutNext`, `reconnectLatencyMs`/`reconnectFails`, `deleteFile`, `fullAt`, `denyWrites`, epoch, `calls`/`ioCalls`/`log`, `opens`, `refs`, `ready`, and `queuePosted` + `runPosted`. It also has the fault exceptions `FakeTransportException`, `FakeTimeoutException` and `FakeStatusException(FakeStatus)`, and a server clock for `lastWriteMs`.
  - `storage/fakes/StorageFakes.kt`: `FakeStorageLocations`, `FakeNetworkFolders`, `FakeVolumeCatalog` and `FakeRingTargetSource` (records `onStorageFailure`; names files `ring-<device>-<pid>-<n>.bin`).

`:player`
- `PlaybackApi.kt`: the `LocalBufferState` fields. `storageRatesBps` is a `StorageRates(writeBps, readBps)` whose `equals` is always true, so it is excluded from equality.
- `Seams.kt`: `TimeshiftGrant.choice` (default `InternalRingTargets.UNSIZED`).
- `TimeshiftModule.kt`: `@BindsOptionalOf RingTargetSource`, and `@Binds VolumeCatalog` → `VolumeWatcher` (deviation 6).
- New `InternalRingTargets.kt`: always this device, with StatFs figures from `TimeshiftStorageImpl`.
- New `VolumeWatcher.kt` (WP-S2 stub): lists no volume.
- `TimeshiftSessionFactoryImpl` is **not** touched. It does not consult a target yet, so playback is byte-for-byte as before. WP-S2 injects `Optional<RingTargetSource>` and falls back to `InternalRingTargets`.

`:data`
- `SecureBlobStore.REF_SMB_PREFIX`.
- `RoomSettingsStore.KNOWN_DOMAINS` gains `storage`, so a restore keeps the document before WP-S1 registers the domain.
- **Stubs:**
  - `storage/StorageLocationsImpl.kt` (no locations, `chosen` = internal, writes `TODO`);
  - `storage/NetworkFoldersImpl.kt` (`find()` delegates to `ComputerFinder`, the rest `TODO`);
  - `storage/discovery/ComputerFinderImpl.kt` (WP-S3; holds the `ComputerFinder` interface and emits one empty, finished `FindState`);
  - `storage/StorageModule.kt` (binds those three; **no** `RingTargetSource`).

Build, `:app`, `:ui`, docs
- `libs.versions.toml`: `smbj`, `dcerpc`, `bouncycastle` (1.85.2) and the three libraries.
- `data/build.gradle.kts`: smbj, dcerpc, `bcprov-jdk18on` (declared to pin smbj's provider version), lifecycle-process. slf4j stays unbound.
- `app/build.gradle.kts`: the OSGi `MANIFEST.MF` exclude.
- `app/proguard-rules.pro`: the placeholder block, marked `--- smbj/mbassador/dcerpc rules (WP-S1) ---`, with the two `-dontwarn` lines.
- Manifest: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
- `SettingsBindingsModule`: `PhoneContextProvider { null }` and `FallbackToastGate { false }`. WP-S4 replaces both.
- `PlayerText`: the two new toasts map to the existing storage text, marked "WP-S4 replaces".
- `PhoneSetupScreen.titleOf(NETWORK_FOLDER)`: the setup title for now, marked "WP-S6 replaces".
- `SettingsUi.badgeLabel` and `strings_settings.xml`: "IN USE", "SAVED".
- `00-master-plan.md` §3 Platform: smbj is now v1 (with dcerpc and bcprov in `:data`), and a note that targetSdk 37 must add `ACCESS_LOCAL_NETWORK`. The §2 pointer line was already there.

### Deviations from §12 (each needed to compile, be green, or unblock a WP)

1. **`RingFlusher(threadName, owned: Boolean = false)`, not `ownedBy: TsRingImpl?`.**
   - Kotlin cannot pass `this` to a delegating constructor call, which is where §12.4's `RingFlusher("box-ts-flush-net", this)` would have to go.
   - The choice moved to `RingFlusher.forStorage(storage)`, which also picks `-ext` for a USB storage (§2's thread table).
   - WP-S1 implements the exit: an owned flusher's thread ends after its one ring unregistered and closed. Until then an owned flusher behaves like a shared one, and nothing creates one.
2. **`RemoteLink.post(work: Runnable)` (added).**
   - §4.1 has `close()`/`delete()` post the CLOSE, the reference release and `rm` to `box-smb`, but neither §12.2 nor `SmbRingStorage`'s constructor gave the storage an executor.
   - `RemoteShare.freeBytes()` KDoc now says the link may answer from the keeper's cache (§4.1 `availableBytes`). That is not a signature change.
3. **`LocationTestResult.error: BrowseError? = null` (added).** A FAILED verdict needs its cause for "the cause | its next step" (§7.8). `BrowseError` and `BrowseErrorKind` are therefore `@Serializable`.
4. **`TimeshiftPolicyImpl` overloads implemented in commit 0** (they are WP-S2's body in §17).
   - They are 15 lines, the old methods had to delegate to them, and `StorageLimitsTest` (commit 0) checks Auto length per kind.
   - `player/src/test/.../TimeshiftFakes.FakePolicy` got the two overrides.
   - WP-S2 may revise both.
5. **The Pause and rewind sub-page is live in commit 0.** Moving the ids and listing `PauseRewindRows` in `SUB_PAGE_SOURCES` (both commit 0 per §9.5) made `SimpleRowCountTest`'s "published" check fail, because Playback would publish only 4 counted rows. Commit 0 therefore made these edits in WP-S4's files:
   - `WpaPages`: the `PAUSE_REWIND` Link (label `PB_TIMESHIFT`, value = the length) and `handles(PauseRewind)`, which builds today's three rows there. `apply` is unchanged (owner "A").
   - `WpaPagesTest`: the timeshift rows are read from `PauseRewind`, and a new `pauseRewindSubPage` test.
   - `SettingsBackLadderTest`: focuses `PAUSE_REWIND`.
   - `EffectTimingCoverageTest`: builds `PauseRewind` too.
   - `SettingsContractsTest`: 15 sources.

   `TIMESHIFT_LOCATION` is registered with owner **"S4"**: no page builds it yet, and an owner-"A" row nobody builds fails `EffectTimingCoverageTest`. Viewer-visible effect: Playback → "Pause and rewind" now opens a sub-page with the same three rows. "Rewind length", "Save to" and the texts are still WP-S4's.
6. **`VolumeCatalog` is bound in `:player`'s `TimeshiftModule`, not in `:app`.** It is the same graph, and `VolumeWatcher` is `internal` to `:player`.
7. **`PhoneSubmission.NetworkFolder` is a plain class, not a data class.** A generated `copy` would duplicate the password array, and `equals` would compare by identity anyway.
8. **Small additions:**
   - `StorageConstants.VOLUME_ID_PREFIX`, `SMB_ID_PREFIX` and `LABEL_LINK_MAX_CHARS` (16, from the `LABEL_MAX_CHARS` row);
   - `StorageLimits.reportedAvailableBytes(free)`: the §3.4 offset, shared by `VolumeStorage` (WP-S2) and `SmbRingStorage` (WP-S1);
   - `StorageLimits.of(kind, total)`;
   - `SettingsMachineImpl.parentOf` for the new routes (Pause and rewind → Playback; Save to → Pause and rewind; Find / LocationDetail → Save to; `LocationSignIn` / `LocationTest(locationId)` → LocationDetail; browse routes and the NETWORK_FOLDER `PhoneSetup` → FindComputers). This file is in no WP's list.
   - `HintValidator`: `TextHint.HOST` = not empty (WP-S4 adds the parser as a row rule).
9. **`FallbackToastGate` (added to `StorageContracts.kt`).** `PlayerEffects.fallbackToastDue` is implemented by `:ui`'s `PlayerEffectsImpl`, which cannot see `:app`'s FallbackToastPolicy, and `PlayerEffectsImpl` is in no WP's list. Commit 0 adds:
   - `fun interface FallbackToastGate`;
   - `EffectDelegates.fallbackToasts` (test call site updated);
   - `PlayerEffectsImpl.fallbackToastDue` delegating to it;
   - the `{ false }` binding in `SettingsBindingsModule`.

   WP-S4's `FallbackToastPolicy` implements the gate and replaces the binding.

### What each WP must know

- **WP-S1.**
  - Fill `SmbRingStorage`, `ManualAddressParser` and the owned-flusher exit (`RingFlusher.owned`).
  - Implement `RemoteLink.post` in `SmbjRemoteLink` on `box-smb`.
  - In `StorageModule`, add `@Binds RingTargetSource` (StorageTargetsImpl) and register `StorageDomain`. Binding `RingTargetSource` is the switch that turns the feature on in the factory once WP-S2 consults it.
  - Replace the placeholder R8 block. Keep or extend the two `-dontwarn` lines.
- **WP-S2.** Inject `Optional<RingTargetSource>` and `InternalRingTargets` into the factory (`targets.orElse(internal)`). `AndroidRingStorage.blocking` and `preallocate` are yours. `FakeRemoteLink.ioCalls` is the counter for `SmbPrepareNoIoTest`.
- **WP-S3.** `ComputerFinder` (interface) and `ComputerFinderImpl` are in your file `data/.../storage/discovery/ComputerFinderImpl.kt`; the interface can move to its own file of yours.
- **WP-S4.** Take over `PauseRewind` from `WpaPages` (`timeshiftRows` + `pauseRewindLink`) and change `PauseRewindRows`' owner from "A" when `StoragePages` builds them. `TIMESHIFT_LOCATION` is already owner "S4". Replace the `PhoneContextProvider` and `FallbackToastGate` bindings, and the two `PlayerText` bodies.
- **WP-S6.** `PhoneLink.start(purpose, context)` is in place; `titleOf(NETWORK_FOLDER)` is a placeholder.

### Questions for the lead

1. **Deviation 5** changes Settings behaviour in commit 0 (the sub-page appears with today's three rows). The alternative was a red `SimpleRowCountTest` until WP-S4. Accept, or prefer the old layout plus a temporary test exception?
2. **Deviation 9** edits two files no WP owns (`EffectDelegates`, `PlayerEffectsImpl`) to give WP-S4 a seam. Accept the seam, or assign those files to WP-S4?
3. **`RemoteLink.post` (deviation 2) is the only change to §12.2's signatures.** Please confirm, since WP-S1 and the fake both implement it.

---

## Implementation record — WP-S6 (2026-10-24)

In worktree `agent-a190412bb6bf58a4b` (branch `worktree-agent-a190412bb6bf58a4b`, fast-forwarded to `main` at `502804f`), uncommitted (the lead commits). No emulator run. Only the files of the WP-S6 row in §17 were edited, plus the new test.

**Gate.** `PhoneApiNetworkFolderTest` (19 tests) and `./gradlew :core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug`: **green**. `LanHttpServerImplTest` (25) passes unchanged. `app.js`'s `splitFolder` was run through the test's 28 shared vectors with node (a scratch script that reads both files): 0 mismatches.

### What was done

- **`PhoneApi`**
  - `POST /api/network-folder`: only for NETWORK_FOLDER (`onlyFor`, 409 otherwise), behind the M5 gate (pairing cookie from the paired IP, `X-TheBox`, Host, Origin).
  - The body is read into **one fixed `ByteArray`** of exactly Content-Length (a `FixedSink`, never a `ByteArrayOutputStream`), parsed, and zeroed in a `finally`. A test hook (`bodyBufferProbe`, internal) proves every buffer is all zeros afterwards, on success and on 400.
  - The password goes into the submission's `CharArray` only. On a 503 (nothing on the TV collects), that array is zeroed. User and password are `Redact.registerTransient`ed.
  - **One at a time:** a second POST while the TV signs in gets 409.
  - **`reportStatus` is the sign-in result** for NETWORK_FOLDER: `done = true` means signed in; `done = false` means one failed sign-in. After a success, or after `PAIR_MAX_WRONG` failures, the endpoint answers 410.
  - `/api/state` gains `folder` (the `PhoneContext`: host, share, up to 200 shares), `received` (`{user: "sh***", host}` or `{guest: true, host}`), `failures`, `maxFailures` and `ended` (`signed_in` / `too_many_sign_ins`). It **never** carries the password, the full user name or the domain (tested). The new `tv` values are `Retry` and `Ended`. Other purposes' state is unchanged.
  - `PhoneApi.splitFolder(host, share, path)` re-splits a pasted `\\host\share\path`, `//…` or `smb://…` (in either field), or `TV\Rewind`, on the server too. It rejects `..`, bad hosts and ports, a path without a share, and over-long parts.
  - Nothing of the body is logged. The M5 code has no received-JSON debug dump to exclude; a test with a capturing `BoxLog` sink shows no user, password or path in any line.
- **`LanPhoneLink`**
  - The context goes to `PhoneApi`, for NETWORK_FOLDER only. Each `start` is a new server and a new pairing, so a session is bound to one browse context.
  - After a success it stops after `doneGraceMs` (new constructor parameter, default `DONE_GRACE_MS`).
  - After the last failed sign-in it ends the session as `TOO_MANY_WRONG_CODES` after the same grace.
  - After an earlier failure the phase goes back to PAIRED.
  - **`stop()` lingers once a NETWORK_FOLDER result is final.** The TV replaces the QR page at once, but the phone should still read "Signed in to NAS…", so the server stays up (accepting nothing, 410) until the scheduled grace stop. `start()` always stops at once.
  - `FAILED` states now keep `pairedFrom`.
- **`PhoneScreenModel.tooManySignIns(state, purpose)`**: NETWORK_FOLDER + FAILED + `TOO_MANY_WRONG_CODES` + a paired phone. Wrong pairing codes can only happen before pairing, so the existing `LanFailure` tells the two apart without a contract change.
- **`PhoneLinkImpl`**: passes the context through.
- **Phone page** (`app.html` / `app.js` / `app.css`, same style as M5):
  - A "Network folder" tab, shown alone, with fields top to bottom: Computer (read-only with **Change** when the TV knows it), Shared folder (a `<select>` of the listed shares plus "Another shared folder (type or paste it)", else text; paste accepted), Folder inside it, User name (`autocomplete="username"`), Password (`autocomplete="current-password"`, Show), the §7.3 Windows/Mac line (neutral wording), "More: Workgroup or domain" (`<details>`), "This folder allows guests (no password)", and **Send to TV**.
  - The fields sit in a `<form>` (submit is intercepted), so phone password managers offer to fill them.
  - The password field is cleared once the TV has the request (202).
  - It shows "Received from your phone: user sh***", each TV result, "Tries left: n", and on the last failure "Too many failed sign-ins. Start again on the TV.". The final text stays on screen after the session ends.
  - The footer adds "Your password crosses your home Wi-Fi once, unencrypted, to the TV. The TV keeps it encrypted."
- **TV screen** (`PhoneSetupScreen`, `strings_storage_phone.xml`):
  - The title is "Add a network folder from your phone", with the plain-HTTP line below it (22 px; the existing footer stays).
  - PAIRED gets a hint line (22 px); RECEIVED shows "Received from your phone. Signing in…".
  - The FAILED page says "Too many failed sign-ins…" when `tooManySignIns`.
  - The new sizes are 22 and 26. Nothing is under 18.
- **`PhoneSetupViewModel` / `PhoneSetupHost`**:
  - `open(purpose, nowMs, browse)` asks `PhoneContextProvider` for the browse's context and calls `phone.start(purpose, context)`. A different browse token restarts the session.
  - `PhoneSetupHost(…, browse = null)` is a new trailing parameter.
  - The provider is a defaulted constructor parameter, so existing tests compile.

### For WP-S4 (the contract as built)

- Collect `PhoneSubmission.NetworkFolder` while `PhoneSetup(NETWORK_FOLDER, browse)` is open. **Zero `password` when done.** `host` may carry `:port`; `path` is `/`-separated or null.
- Answer **every** submission with exactly one `PhoneLink.reportStatus(text, done)`: `done = true` for signed in, `false` for failed. Do not report progress, because each `done = false` counts as a failed sign-in.
- Texts for the results are in `strings_storage_phone.xml`: `storage_phone_result_signed_in`, `_auth`, `_unreachable`, `_no_guests`, `_no_access`.
- After a success, replace the route. The server lingers for the phone by itself.

### Deviations

1. `LanPhoneLink` gained a `doneGraceMs` constructor parameter (defaulted), so the tests run in about 1 s instead of 5 s.
2. Too many failed sign-ins reuse `LanFailure.TOO_MANY_WRONG_CODES`, told apart by `pairedFrom` (no contract change).
3. A failed sign-in is not shown as text on the TV's QR page, only on the phone. `LanServerState` has no message field, and the result text is the phone's. The TV shows the phase.

### Questions for the lead

1. **Wiring.** `SettingsScreen.kt:88` calls `phone(route.purpose)`, and `MainActivity.kt:108` builds `PhoneSetupHost(phone, purpose)`. Neither passes `SettingsRoute.PhoneSetup.browse`, and neither file is WP-S6's. Until one of them passes `route.browse` to `PhoneSetupHost(…, browse = …)`, the phone page gets no context: it still works, with every field typed. Assign this one-line change to WP-S4, or make it during integration?
2. **Residue in `StreamBody.copyTo`** (`HttpParser.kt`, not WP-S6's): its 64 KB chunk buffer holds the request body, password included, and is never zeroed. A `buf.fill(0)` in a `finally` there would close it. That residue is in the same class as §19 N-3.
3. `stop()` lingering up to `DONE_GRACE_MS` (5 s) after a final NETWORK_FOLDER result means the socket can stay bound for those seconds after ON_STOP. It accepts no submission in that time. Acceptable against M5 U-S7?

## Implementation record — WP-S2 (2026-10-24)

In worktree `agent-a40749d8cc21805b1` (branch `worktree-agent-a40749d8cc21805b1`, fast-forwarded to `main` 502804f), uncommitted. No emulator. WP-S1 ran in parallel, so `SmbRingStorage` was still the stub; every remote test drives `FakeRemoteLink`.

**Builds.**
- `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest`: **green**. `:core:test` ran 3,474 tests (3,464 before, plus 10 new) and `:app` ran 69; the debug APKs were built.
- `:player:testDebugUnitTest` on its own: **green, 499 tests** (480 before, 19 new).
- The existing time-shift tests (`TimeshiftSessionTest`, `TimeshiftStorageTest`, `ControllerTimeshiftTest`, `ControllerRealRingTest`, the ring tests) pass unchanged.

### What was done (§17 row WP-S2; only those files)

- **`core/.../storage/FallbackRingStorage.kt`** (§5.2). It decides once, at the first `pwrite` on the flusher, through `primary.create(now + deadlineMs)`.
  - On true, everything goes to the primary.
  - On false, it checks that `remoteBytesWritten == 0` (otherwise `IOException`: no hot switch, §18 R2) and closes the primary, which posts its own work. It then builds the secondary on the flusher, replays the `allocate` high-water mark and the last header, and delegates to the secondary. `switchedTo = INTERNAL`. `fallbackReason` comes from `lastFailure`: ACCESS → SIGN_IN_NEEDED, FULL → NO_SPACE_THERE, anything else → UNREACHABLE.
  - Before the decision nothing touches the network. `writeHeader` goes to the primary (which only records it, §3.6). `allocate` returns the bound. `availableBytes` returns `Long.MAX_VALUE` (the cycle was sized at prepare). `isUnlinked` returns false. `pread` throws.
  - `close`/`delete` never wait. The primary posts its own work, and the secondary's close or delete runs on `background`.
- **`TimeshiftPolicyImpl`**: commit 0's two overloads are kept unchanged.
- **`TimeshiftSessionFactoryImpl`** (§5.1, §5.4, §5.5).
  - **Wiring.** `@Inject` takes `Optional<RingTargetSource>` and `InternalRingTargets` (`orElse`), plus `memoryClass`. The primary constructor gains `targets` and `memoryClassMb` with defaults, so the existing tests compile untouched.
  - **Sizing per target.**
    - INTERNAL: as today.
    - VOLUME: `StatFs` on `<dir>/timeshift` with `StorageLimits.VOLUME`.
    - SMB: the target's cached free space. The capacity is `min(SMB, internal)` when the target is not `ready` (last-known-good).
    - A non-internal target under `MIN_RING_BYTES` becomes this device with `NO_SPACE_THERE`.
  - **Pool and length** come from `StorageLimits.of(chosenKind)`: 160/96 by `memoryClass` for remote, 64/32 by `isLowRamDevice` for internal. They are kept across a fallback. The open deadline follows the pool (6 s for 160, 3 s for 96).
  - **Daily cap.** It sets the mode only when the target counts wear. `limitedToday` holds for a counting target or a switched SMB cycle.
  - **Reuse** also needs the same location, the same pool and a usable storage. A `FallbackRingStorage` is usable when it has not switched, is not gone and does not need a fresh file.
  - **Rebuilds.** A blocking (USB/SMB) storage is always deleted on a rebuild, and so is this device's file when the cycle moves elsewhere.
  - **No network I/O on prepare.** SMB gets `FallbackRingStorage(target.open(targets.nextRemoteFileName(id), cap), secondary = { openLocal(internal, asFallback = true) })`. Nothing is allocated, created or written in `prepare`.
  - **Failure report.** A non-internal cycle's writer is wrapped (`ReportingWriter`, delegation). Its listener calls `targets.onStorageFailure(locationId, stop, failure, sinceFirstWriteMs)` on the pump thread, once per cycle, **before** the session records the stop and before the controller posts it (A-N5). `sinceFirstWriteMs` is 0 when the cycle has not written yet, which counts as inside the grace. A cycle already switched to this device does not report.
  - **`storageFields(grant, snap)`** fills `storage`, `storageFailure`, `fallback`, `storageName`, `locationId` and `storageRatesBps`. Failure mapping:
    - SMB: DISK_TOO_SLOW → SLOW, else `lastFailure`, else EVICTED → REMOVED, else UNREACHABLE.
    - USB: SLOW, or REMOVED.
    - This device: null.
  - **`onVolumeGone(uuid, mountPath)`** calls `ring.stopRecording(STORAGE_EVICTED)`, then `VolumeStorage.eject()` (gone at once, fd close posted). It reads a volatile, so it takes no factory lock on main.
- **`TimeshiftStorageImpl`.**
  - `openLocal(target, capacity, preallocate, countsWear, asFallback, closer)`.
    - For this device it is today's `openStorage`.
    - The fallback secondary is `ring-<pid>-fb<n>.bin`. It is preallocated, counts wear, and its `availableBytes = StorageLimits.internal(total).reportedAvailableBytes(free)` (the internal reserve, §5.2).
    - VOLUME is a `VolumeStorage` on `ring-<pid>-<n>.bin`, with no wear counter.
  - Also `spaceOf(dir)` and `volumeRingDir(target)`.
- **`AndroidRingStorage`** gains three parameters:
  - `preallocate`: when false, `allocate` records the bound with no I/O;
  - `blocking`;
  - `closer`: a posted fd close, and a posted unlink after it.

  It also gains `nlinkNow()`.
- **New `VolumeStorage`.** `blocking`, so the ring gets `box-ts-flush-ext`. `availableBytes` keeps the volume reserve. `isUnlinked` = ejected, or nlink when `nlinkReliable`, which is set when nlink reads 1 right after create (A-S8). `firstWriteMonoMs` drives the grace.
- **`VolumeWatcher`** (real body).
  - Listing: `getExternalFilesDirs(null)` entries with i > 0 that are mounted and removable. uuid and label come from `getStorageVolume(dir)`, falling back to the `/storage/<uuid>/` path segment. `StorageFlags.VOLUME_ENABLED` is honoured.
  - A runtime receiver (scheme `file`, `RECEIVER_NOT_EXPORTED` on 33+) handles EJECT, UNMOUNTED, BAD_REMOVAL and REMOVED. On main it only calls `onVolumeGone`; the re-list runs on `box-ts-sweep`.
  - `MEDIA_MOUNTED` re-lists and runs the sweep, which deletes every file in `<vol>/timeshift/` not starting with `ring-<this pid>-`.
- **`TimeshiftStartup.onAppStart`** also starts `VolumeWatcher` (lazy).
- **`PlaybackControllerImpl`**: one line in `localBufferState`, which runs the snapshot through `storageFields`.
- **`LocalTimelineBackend`**: the five `LocalBufferInfo` fields are copied in both branches.

### Tests (new)

| Test | Checks |
|---|---|
| `core/.../storage/FallbackRingStorageTest` (10) | create ok → primary, create once, deadline = now + deadlineMs; deadline/failure → secondary with the header first and the allocate high-water replayed; `fallbackReason` per `lastFailure`; no switch once `remoteBytesWritten > 0`; no hot switch after create; the wear counter sees only the secondary; close/delete never wait (secondary on background); `pread` before the first write fails; the first write per cycle; a real `TsRingImpl` (manual flusher) switches before its first flush and stays readable |
| `SmbPrepareNoIoTest` (Must, A-B1) | the **real** factory and the real `TsRingImpl` (dedicated flusher) over a `FakeRemoteLink` that is unreachable and stalled with posts queued. First build, reuse, rebuild, SMB → internal, internal → SMB, `needsFreshFile`, a broken ring, a tune-time fallback, last-known-good, and discard: **0** link calls (`calls` and `ioCalls`), each prepare < 2 s |
| `FactoryTargetTest` (11) | pool 160/96/64/32 and the open deadline; a tune-time fallback keeps 160 and reports INTERNAL + UNREACHABLE + name; reuse rules (location, pool, `needsFreshFile`, fresh names); a switched cycle is rebuilt, its secondary counts wear, `ring-<pid>-fb1.bin`; the secondary keeps the internal reserve; NO_SPACE_THERE for SMB and USB; last-known-good capacity ≤ this device; the daily cap only on this device; `onStorageFailure` before the session holds the stop, once per cycle, with the grace input (0 with no write; 25 s after a write); an internal cycle reports nothing; USB: no preallocation, no wear, `onVolumeGone` → STORAGE_EVICTED, close posted, REMOVED |
| `VolumeWatcherTest` (5, Robolectric) | an EJECT broadcast reaches `onGone` on main with the uuid and path, then re-lists; every removal action; MOUNTED sweeps other processes' files only; uuid from the path; `eject()` closes the fd on `box-ts-discard`, not on the caller's thread |

### Deviations

1. **`SmbPrepareNoIoTest` runs against `NoIoRemoteStorage`** (test-only, `SaveLocationFakes.kt`) while `SmbRingStorage` is a stub. `remoteStorage()` switches to the real `SmbRingStorage` as soon as its members stop throwing `NotImplementedError`, so the same test then drives WP-S1's class. **The lead must re-run it after merging WP-S1.** The factory itself, the ring and the flusher are the real ones (A-B1).
2. **File names.**
   - USB rings are `ring-<pid>-<n>.bin` (not `ring-<pid>.bin`, §3.5).
   - The fallback secondary is `ring-<pid>-fb<n>.bin` in the internal folder.

   With distinct names, a posted delete of the old file can never unlink a newer file at the same path. The internal ring keeps `ring-<pid>.bin`. The volume sweep keeps `ring-<this pid>-*`.
3. **`FallbackRingStorage` additions** beyond the commit-0 stub: an optional `background` constructor parameter, a public `deadlineMs`, `secondaryStorage`, `firstWriteMonoMs` and `beginCycle()`. The stub's constructor is unchanged otherwise.
4. **Controller seam.** `PlaybackControllerImpl` reaches `storageFields` by `(timeshift as? TimeshiftSessionFactoryImpl)`, because `TimeshiftSessionFactory` (`Seams.kt`) is not a WP-S2 file. The report to `onStorageFailure` is done by wrapping the writer in the factory, so the controller's stop path is untouched.
5. **NO_SPACE_THERE is also decided in the factory**, from the sized capacity. The source may decide it too; the factory's check is the backstop.

### Questions for the lead

1. **The name for a tune-time fallback.** `RingTargetChoice` has no chosen-location name. The factory takes the name from `target.name`. WP-S1's `StorageTargetsImpl` should therefore put the chosen location's name ("NAS") in the INTERNAL target's `name` when it falls back, or the §10 wording loses the name.
2. **`RingTarget.Local.dir` for VOLUME.** The factory treats it as the volume's app files dir (`VolumeCatalog.Volume.dir`) and appends `timeshift/`, unless it is already named `timeshift`. WP-S1 should pass `Volume.dir`.
3. **Mapping ACCESS → SIGN_IN_NEEDED** for the early switch's `fallbackReason`. A folder-permission refusal then reads as "needs you to sign in again". Accept, or add a reason (append-only enum, a contract change)?
4. **The grace with no write yet.** A stop before the cycle's first successful write reports `sinceFirstWriteMs = 0` (inside the grace, no demotion). Confirm that this is the intent of §5.4.
5. **Owned flusher exit (WP-S1).** Until it lands, every SMB/USB ring leaves one `box-ts-flush-net`/`-ext` thread behind after it closes.
## Implementation record — WP-S3 (2026-10-24)

In worktree `agent-a9f2f5ea0e4918560` (branch `worktree-agent-a9f2f5ea0e4918560`, fast-forwarded to main `502804f`), uncommitted (the lead commits). No emulator or device run: discovery cases are device-only (§16.3), so I-SL-15 is still open.

**Files** (only WP-S3's list in §17, plus new test files):
- `core/.../storage/discovery/NbnsCodec.kt`: the RFC 1002 node-status request and response.
- `core/.../storage/discovery/DiscoveryMerge.kt`: `DiscoveryMerge`, `Sighting`, `SavedHosts`, `LanSubnet`, `Ipv4`.
- `data/.../storage/discovery/ComputerFinderImpl.kt`: `ComputerFinder`, `ComputerFinderImpl`, `FinderEnvironment`, `AndroidFinderEnvironment`.
- `data/.../storage/discovery/NsdFinder.kt`: `NsdApi`, `AndroidNsdApi`, `NsdFinder`.
- `data/.../storage/discovery/Port445Sweep.kt`: `Port445Sweep`, `LanSockets`, `NetworkLanSockets`, and the close-on-cancel helper.
- `data/.../storage/discovery/NameLookup.kt`: `NameLookup`, `NetworkNameLookup`.
- Tests:
  - `core/src/test/.../storage/discovery/NbnsCodecTest.kt` (22 tests);
  - `DiscoveryMergeTest.kt` (13 tests in `DiscoveryMergeTest`, 5 in `LanSubnetTest`);
  - `data/src/test/.../storage/discovery/ComputerFinderTest.kt` (28 tests in four classes: `NsdFinderQuirksTest`, `Port445SweepTest`, `ComputerFinderTest`, `NetworkNameLookupTest`).

**How it works:**
- **Lifetime.** `search()` is a cold `channelFlow`, conflated and run on IO. Collecting it takes the `MulticastLock` (application context, not reference-counted). The finders run for at most `DISCOVERY_TOTAL_MS`. `searching` turns false when the sweep ends; with no sweep, when the finders stop. A final state is emitted at the total time. The flow then **stays open, holding the lock, until the collector cancels** (lead ruling 3, A-S10: the lock covers the whole page). It never completes on its own. A NO_NETWORK state is emitted at once, and the lock is still held until the page closes.
- **Sweep** (owner ruling 2):
  - **Address and targets.** It starts from `LanAddressFinder.find()` (Wi-Fi/Ethernet, never VPN, RFC 1918) and runs only when `LanSubnet.of(own)` accepts the address. Targets are .1–.254 of that /24, minus our own address, capped at `SWEEP_MAX_HOSTS`. Each worker re-checks `host in subnet` before it connects. Addresses are literal (`InetAddress.getByAddress`), so no DNS is used.
  - **Limits.** At most `SWEEP_PARALLEL` (32) connects at once, each with a `SWEEP_CONNECT_TIMEOUT_MS` (400) timeout. The worst case is about 3.2 s.
  - **Sockets.** They come from the non-VPN `Network` that owns the address (`network.socketFactory`, `bindSocket`). If the VPN refuses the bypass, the socket is left unbound. Each connect is closed at once and no bytes are sent.
  - **Stop.** A watcher coroutine on `Dispatchers.Unconfined` closes every pending socket from the cancelling thread. A cancel therefore ends a sweep blocked in `connect` in under 500 ms (tested with sockets that hang for 30 s).
- **NSD:**
  - **Listeners.** Each search creates new listeners. `stopServiceDiscovery` runs only after `onDiscoveryStarted`. A stop that comes first is applied when the late start arrives. A failed or refused start is never stopped.
  - **Callback thread.** All callbacks are posted to a per-search `box-find` thread. That thread exits when idle and is never shut down, so a late `onDiscoveryStarted` still runs.
  - **Resolves.** Below API 34 they run one at a time, SMB first, with at most 2 retries on `FAILURE_ALREADY_ACTIVE`. From API 34, `registerServiceInfoCallback` is used, and every callback is unregistered on stop.
  - **Results.** Only IPv4 addresses are kept, and the NSD port is kept with them.
- **Names.** These run only for computers NSD did not name, at most 8 at a time:
  1. NBNS `NBSTAT *` unicast to UDP 137, accepted only from that address and only with our transaction id, within `NBNS_TIMEOUT_MS`. The socket is closed at once on cancel. The name is the first unique, non-conflicting `<00>` name.
  2. Reverse DNS on a bounded pool (4 threads, queue of 32, then dropped) behind `REVERSE_DNS_TIMEOUT_MS`. A late answer is dropped, and an answer that only echoes the address counts as no name.
- **Merge and order:** as §7.1 and U-S9.
  - Computers are merged by IPv4 address. The name comes from NSD first, then NetBIOS, then reverse DNS. The port is 445 when seen, else the advertised one.
  - The list is saved computers first, then named ones A–Z (case-insensitive, ties by address), then unnamed ones by numeric address.
  - `saved` matches a stored location's host or `lastAddress`, as an address or as a name (`.local` and case ignored).
- **Logs.** At INFO, only "start (lan=…, vpn=…)" and "done in N ms, K computers (nsd a, sweep b open of c)". No address or host list is logged at any level.

**Gate:** `./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug --rerun-tasks` is green: `:core` 3,504 tests, `:data` 368, and the APKs build.
- The first Robolectric test in each JVM takes 20–30 s to start the sandbox. The discovery tests themselves take under 2 s each.
- One earlier run failed only on `EditorPerfTest.budgets` (an M5 timing budget, not WP-S3 code). The machine's load average was about 200 then, with other agents building. The test passes on its own, and the full re-run passed.

### Deviations and additions

1. **`_device-info._tcp` is discovered as well.** This is what sets `FoundComputer.apple` for the §7.7 Mac hint: `_smb._tcp` carries no model. A device-info sighting is hint-only. It never creates a row; it only marks a row the sweep or NSD found. Its resolves queue behind the SMB ones.
   - Samba's default "MacSamba" is excluded.
   - A NAS that poses as a Mac model (TrueNAS) still gets the hint, which adds one sentence and gives no wrong next step.
2. **`ComputerFinder.recentAddress(name)` (added to my own interface).** It returns the IPv4 that an NSD instance named `name` had in this process, for WP-S1's §7.2 `.local` fallback (U-8). It holds instance names only, not mDNS host names.
3. **`ComputerFinderImpl` injects `Provider<StorageLocations>`**, to avoid a construction cycle if WP-S1's `StorageLocationsImpl` ever needs `NetworkFolders`. It reads `status.value` once per search.
4. **The fact sheet's "33-byte" NBSTAT request** (facts §4.4) is wrong. RFC 1002 gives 50 bytes (12 + 34 + 4); that is the golden in `NbnsCodecTest`. The golden response packets were built by an independent Python `struct` script, and the RFC 1001 §14.1 "FRED" vector is checked.
5. **Saved hosts that were not found are not added to the list.** `Badge.SAVED` only marks found rows, since the saved ones are already on `SaveLocation`.

### Questions for the lead

1. **The flow does not complete after `DISCOVERY_TOTAL_MS`.** The commit-0 stub's KDoc said it "ends by itself", but the lock must cover the whole page (lead ruling 3). WP-S4 must collect it in the page's scope and must not wait for completion. "Search again" cancels and collects again. Confirm.
2. **NSD is not bound to the LAN `Network`.** The per-network `discoverServices` overloads are API 33+. With a VPN up, NSD relies on the platform. Acceptable for v1?
3. **NO_NETWORK** is reported only when there is no usable LAN address **and** no active network with INTERNET. A VPN-only state shows the VPN row with no error, and NSD still runs. Matches §7.7?


---

## Implementation record — WP-S4 (2026-10-24)

In worktree `agent-a4a1d1bf20d761405` (branch `worktree-agent-a4a1d1bf20d761405`, base `3991415`, commit 0 plus the design), uncommitted (the lead commits). No emulator run. Written against fake `StorageLocations`, `NetworkFolders`, `VolumeCatalog` and `PhoneLink` (`core` testFixtures); WP-S1, S3 and S6 were not merged, so `ManualAddressParser.parse` is still the `TODO` stub and the pages take the parser as a constructor argument (the tests pass a small stand-in).

**Gate:** `./gradlew :core:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`: **green** (after merging main 6055fcb; one unrelated flaky `GuideViewModelTest` failure on one run passed on rerun). That includes `SimpleRowCountTest` (Playback stays 5 simple rows, total 35), `SettingsBackLadderTest`, `SettingsReachabilityTest`, `WpaPagesTest` and `EffectTimingCoverageTest`.

### What was done (by file)

`:core`
- **`settings/state/StoragePages.kt` (new).** One `SettingsPageSource` (also `PhoneContextProvider` and `SettingsNavigationAware`) for `PauseRewind`, `SaveLocation`, `LocationDetail`, `FindComputers`, `ComputerShares`, `ComputerSignIn`, `LocationSignIn`, `NetworkFolder`, `LocationTest` and `PhoneSetup(NETWORK_FOLDER)`.
  - **Rows and pages as §9.2:** Save to (This device, one Item per mounted drive, one per stored folder with `Badge.IN_USE`, "Add a network folder"); the detail page (Status Info with `Tone`, mono Address, Use for rewind (hidden when in use; runs the Test first when `lastTest` is older than `TEST_FRESH_MS` or worse than GOOD), Test this folder, Name (`TextHint.NAME`), Sign in again / Change sign-in, Remove with the confirm card and Cancel focused); Find computers (searching Info row, VPN row, computers saved-first then named A-Z then unnamed by address with `Badge.SAVED`, Enter a name or address with the `\\` `\` `.local` `smb://` chips, Add from your phone, Search again).
  - **`BrowseSession`** (`token` only in routes; the password is a `CharArray`, zeroed when the session ends; `toString` prints the token). A session ends on BACK out of the browse, on save, and when its last route has closed (after `REAP_GRACE_MS`, because a replace closes one route before the next builds).
  - **One folder route** `NetworkFolder(browse, share)`: the path is session state; "Up one folder" row below the share root; BACK leaves the whole browse to Save to (`onBack` → `popTo`), the session is cancelled and the connection closed (U-B3).
  - **Typed path** (`\\NAS\TV\Rewind`, `smb://…`, `host:port`): host only lands on the shares; host + share on the folder; with a path, "Use this folder" is focused; a missing folder lands on the deepest one that exists with the §7.2 note.
  - **Sign in** (§7.3): rows in spec order, Sign in disabled until a user name is typed, wrong password clears the field and focuses it with the BAD reason row on top, the no-keyboard note (phone row first), the neutral Windows/Mac info lines. **Phone**: `Sign in from your phone` and `Add from your phone` push `PhoneSetup(NETWORK_FOLDER, browse)`; while that page is open the pages collect `PhoneSubmission.NetworkFolder` (the server refuses with 503 when nobody collects), answer each submission with exactly one `reportStatus` (done = signed in, false = a failed try; no progress line), zero the submission's password, and on success **replace** the QR page with the shares (or the folder at the given path).
  - **Test** (§7.8): a progress Info row ("Saving test data…" + "9 of 16 MB") with one **Stop test** row; BACK while running = Stop test (nothing saved); verdict row (label, next step, mono details); the wake and 96-block-pool notes; GOOD focuses "Use this folder", SLOW and TOO_SLOW focus "Choose another folder" with "Use anyway" second; FAILED shows the §7.7 cause. Saving stores the location, chooses it, posts "Rewind will save to NAS › TV › Rewind, from the next channel change." and `popTo(SaveLocation)`.
  - **§7.7 errors** are rows of reason (label) and next step (lines, one per line), with the VPN and Mac hints appended where the cause says so.
- **`settings/state/StoragePageEnv.kt` (new).** `StoragePageEnv` (keyboard, small pool, `runOnMain`), `StorageMarks` (a zero-width mark that tells the UI a value or description is amber), `SettingsNavigationAware`, the `StorageText` keys + `StorageTexts` (`KEYS` for tests) and `TimeshiftPlaces` (the place rewind saves to, from the stored locations and the drives; "offline" when the chosen place cannot be reached).
- **`WpaPages.kt`.** `timeshiftRows(prefs, place)` is public and place-aware: sizes read "Uses up to 1.9 GB on NAS …", a USB drive's 2 h option carries "Up to 1 h 11 min on this drive", Auto reads "Auto (1 h 11 min: USB drive is limited)", "Protect built-in storage" says "Not used while saving to NAS." The Playback link now reads `<length> · <place>` ("1 hour · NAS"), "Off", or amber "1 hour · This device (NAS offline)", with the §9.1 description. `handles(PauseRewind)` is false (StoragePages builds it, adding the Save to row); `build(PauseRewind)` still builds the three length rows for the contract tests, and `apply` still writes them (owner "A"). `rowRules` gains the "Enter a name or address" form check. `lengthText` reads "1 h 11 min" for non-whole hours.
- **`SettingsTexts.kt`.** `PB_TIMESHIFT_LENGTH` ("Rewind length"), the sized-for-a-place description, the protect note, the link's description and values, `TS_HOURS_MIN`, `TS_UP_TO_ON_DRIVE`, `TS_AUTO_LIMITED_USB`, `ERR_HOST`.
- **`full/state/PlayerStateMachineImpl.kt`.** `SAVE_LOCATION_FALLBACK` when a cycle's `LocalBufferInfo.fallback` first becomes non-null, asked once per (cycle, reason) through `PlayerEffects.fallbackToastDue` (the host decides); the arg is `"<StorageKind>␟<LocationFallback>␟<name>␟"` (kind from the location id's prefix). The storage-stop toasts (`REWIND_STOPPED_STORAGE`, `PAUSE_NO_REWIND_STORAGE`) carry `"<StorageKind>␟<StorageFailure>␟<name>␟<jumped>"` when the cycle saved on a USB drive or a network folder, and no arg (today's text) for this device, also after the §5.2 switch.

`:app`
- **`settings/StoragePageEnvImpl.kt` (new)** (keyboard, memory class, main handler) and **`ResourceStorageTexts`** (an exhaustive `when` over `StorageText` onto `strings_storage.xml`).
- **`playback/FallbackToastPolicy.kt` (new).** Implements `FallbackToastGate`: once per process per (location, reason); `NOT_READY_YET` never; `SIGN_IN_NEEDED` in the first `SIGN_IN_TOAST_PROCESSES` (3) processes, counted in `noBackupFilesDir/smb-signin-toasts` (device state, never backed up).
- **`SettingsBindingsModule.kt`.** Binds `StorageTexts`, `TimeshiftPlaces`, `StoragePages` (into the page-source set), `PhoneContextProvider` (= `StoragePages`; the commit-0 `{ null }` is gone) and `FallbackToastGate` (= `FallbackToastPolicy`; the commit-0 `{ false }` is gone), and passes the place source to `WpaPages`.

`:ui`
- **`PlayerText.kt`.** The §10 bodies for `SAVE_LOCATION_FALLBACK`, `REWIND_STOPPED_STORAGE`, `REWIND_STOPPED_SLOW`, `PAUSE_NO_REWIND_STORAGE` and `PAUSE_RESUME_LOST_STORAGE`, decoding the arg; a null arg keeps today's text. `rewindPlaceStat` builds the stats line's place suffix ("NAS 3.4 MB/s", "this device (NAS offline)", "(NAS connecting)"). **`StatsOverlay.kt`** takes it as `rewindPlace`.
- **`SettingsUi.kt` / `SettingsRowViews.kt`.** Accent-border `IN_USE` and `SAVED` badges (text FG .9, canvas colour on a focused row), the progress track (1,228 × 8) on the Test's progress row, the three searching dots (static when `ANIMATOR_DURATION_SCALE` is 0), amber for a marked value or description, mono for a network path / "9 of 16 MB" / the Test details, and storage Info rows draw each next-step line whole (height grows by line). Every size is the kit's (28/26/22, badge 18); nothing is below 18.
- **`strings_storage.xml` (new)**, `strings_settings.xml` (Rewind length, the sized description, link texts, "Searching", the host error), `strings_player.xml` (the paths now name the sub-page: "…Pause and rewind → Rewind length", "…→ Protect built-in storage"), `ResourceSettingsTexts.kt` (the nine new keys).

Tests (new): `StoragePagesTest` (rows and texts, the §9.3 journeys through the real `SettingsMachineImpl`, credentials never in a route or `toString`, focus rules, the phone path, the Test and its Stop), `StorageToastMachineTest`, `FallbackToastPolicyTest`, `StorageTextTest` (the §10 strings verbatim, every `StorageText` has a string that formats, the kit's helpers). Edited: `WpaPagesTest`, `SettingsBackLadderTest` (the Save to chain, BACK out of the browse), `SettingsReachabilityTest` (the save-location pages by five-key enumeration; a browse eight levels deep leaves within 12 BACKs from every depth), `SettingsWorld` and `PlayerWorld` (fixtures), `TimeshiftViewModelTest` (the three paths that now name the sub-page).

### Deviations

1. **Files outside the §17 WP-S4 row, each needed to build or to make a stated behaviour reachable:** `ui/.../settings/vm/ResourceSettingsTexts.kt` (an exhaustive `when` over `SettingsText`, nine new keys), `ui/.../settings/SettingsRowViews.kt` (the drawing the new `SettingsUi` helpers need: badges, progress track, dots, amber), `core/src/test/.../SettingsWorld.kt` and `PlayerWorld.kt` (test fixtures), `ui/src/test/.../TimeshiftViewModelTest.kt` (three asserted strings changed by §10).
2. **Page texts are `StorageText` keys, not `SettingsText` keys.** About 150 keys do not belong in `ResourceSettingsTexts`; `:app` maps them (`ResourceStorageTexts`) onto `strings_storage.xml`. The nine keys of the existing Playback/Pause and rewind rows are `SettingsText`.
3. **`PauseRewindRows` owner stays "A"** and `WpaPages.apply` still writes the three length rows: the owner is a commit-0 tree file, and `EffectTimingCoverageTest` needs every owner-"A" row built by `WpaPages`. `TIMESHIFT_LOCATION` (owner "S4") is shown by `StoragePages`.
4. **"Test this folder" is a Link row, not an Action** (the same navigation, nothing to run), which makes it reachable in the enumeration; "Sign in again / Change sign-in" is a Link as specified.
5. **The searching row has its own id** (`storage.find.searching`) so the UI draws the dots on it; it becomes `storage.find.status` when the search ends.
6. **The amber value/description uses a zero-width mark** (`StorageMarks.WARN`) because `SettingsRow` has no tone for a Link's value and the contract is not mine. The UI strips it. `SettingsRow.value` of those rows therefore starts with U+200B.
7. **`NO_SPACE_THERE` reads "NAS is almost full"**, not "NAS has only 1.1 GB free": `LocalBufferInfo` carries no free-space figure and the arg format of §10 has no slot for one. The "inside the grace: the next channel tries NAS again" variant of the slow toast is also not produced (no flag in the arg).
8. **The password row's editor starts empty** and its value column shows a mask; the password is never in a `PageModel`. The machine does, for one rebuild, put the typed text into the row it edited (`replaceRow`), as for every text row.
9. **Focus on Find ("then the first computer, as long as no key has been pressed")**: the page asks for focus on the first computer once, when the first computer arrives. A key pressed before that is unobservable by a page source, so the cursor can jump.

### Questions for the lead

1. **Navigation seam (needed to run on a device).** `StoragePages` implements `SettingsNavigationAware`; `SettingsViewModel` must hand it the machine, one line after `machine` is created: `ordered.filterIsInstance<SettingsNavigationAware>().forEach { it.attachNavigation { machineRef as? SettingsShellLink } }`. It is not in WP-S4's list. Without it the pages show, but moving on after a suspend (shares after a connect, the sign-in landing, `popTo(Save to)` after a save, BACK out of the browse) does nothing. The tests wire it through `SettingsWorld`.
2. **← (LEFT) on a folder page pops one page; it does not go up one folder.** `SettingsMachineImpl.popPage(fromBack = false)` never asks the source. The page offers ".. Up one folder", and BACK leaves the browse as specified. For ← to go up, the machine must ask the source first on LEFT, as `backHandler` does on BACK (a `SettingsMachineImpl` edit). Likewise → on an Item with `primary` (a folder, a share row of Save to) does nothing: the machine's RIGHT opens only `Link` and `Item.route`; OK works.
3. **Contract gaps in `StorageLocations` / `NetworkFolders`.** (a) There is no call to remember a USB drive: the pages call `choose(TIMESHIFT, "vol-<uuid>")` and rely on WP-S1 to create the `VOLUME` location (name from `VolumeCatalog`) on choose. (b) A stored location's Test and sign-in check call `NetworkFolders.open(location.smb, null)`: WP-S1's `open` must then use the stored credential (no password reaches the pages). If you prefer an explicit `openStored(locationId)`, it is a contract change.
4. **Stats line.** `PlayerText.rewindPlaceStat` and `StatsOverlay(rewindPlace = …)` exist; `PlayerViewModel.rewindStat` and `FullScreenHost` (not WP-S4's) must pass them: the place name and fallback are on `LocalBufferInfo`, the write rate is only on `LocalBufferState.storageRatesBps`.
5. **`PAUSE_RESUME_LOST_STORAGE` and the "back at live" (`jumped`) arg** are raised by the host that sees the read fail (WP-S2's controller); the machine only encodes the stop arg. `PlayerText` decodes both.
6. **`SettingsRowViews`** was edited (deviation 1); say if you would rather take that file into a WP.
---

## Implementation record — WP-S1 (2026-10-24)

In worktree `agent-a08ef6c93e6603f3c` (branch `worktree-agent-a08ef6c93e6603f3c`), fast-forwarded to `main` dcf95cc (WP-S2 and WP-S6 merged) before the final gate. Uncommitted (the lead commits). No emulator and no real SMB server: every Must is tested through `FakeRemoteLink`; the real-server tests are opt-in.

**Builds.**
- `:core:test :data:testDebugUnitTest :app:assembleDebug`: core 3,521 green; data 388 run, 3 skipped (opt-in), 1 failure in `EditorPerfTest.budgets`, a timing budget unrelated to this WP that failed while several worktrees were building on the machine. Re-run alone, it passes (2/2). The universal debug APK was built.
- `:player:testDebugUnitTest` alone: 499 green, including WP-S2's `SmbPrepareNoIoTest` and `FactoryTargetTest` (13), now driving the real `SmbRingStorage`.
- `:app:minifyReleaseWithR8`: **green** with smbj reachable. The release dex holds the `com.hierynomus`, `net.engio` and `com.rapid7` classes (186 type references), so the keep rules are exercised. Whether they are sufficient at runtime remains S-2's check (U-13).

**New tests** (74; all pass; 3 opt-in skipped):

| Module | Test | Count | Covers |
|---|---|---|---|
| `:core` | `SmbRingStorageTest` | 10 | no I/O in construction, `writeHeader`, `allocate`, `close`, `delete` (`ioCalls` = 0, A-B1); create once with CREATE_OVERWRITE; header before data; `pread` loops and direct-buffer staging; a reader never opens; posted close/delete; close during an in-flight create; the reserve offset; a failed space query answers 0 |
| | `SmbReconnectTest` (incl. the `ReopenNeverTruncatesTest` case) | 8 | single flight across a flusher and a reader; same offset; OPEN_EXISTING keeps every byte (A-S2); the budget; `needsFreshFile` after a reconnect and after a write timeout; SLOW never reconnects; REMOVED on the reopen; a closed storage stops retrying |
| | `SmbFaultTest` | 3 | interrupt → CANCEL first; an interrupted reader with a concurrent writer: epoch unchanged, no reconnect, the write completes (A-B3); FULL/ACCESS/REMOVED |
| | `TsRingOverSmbTest` | 3 | the real ring with its real dedicated flusher in real time (A-S9): a 3 s stall absorbed by a 64-block pool; the same stall with 8 blocks → DISK_TOO_SLOW; a transport loss past the budget → DISK_ERROR (UNREACHABLE), RAM-only, then the pinned saved part reads back once the server returns |
| | `DedicatedFlusherTest` | 2 | an SMB ring stuck in `pwrite` does not delay a new internal ring; the owned `box-ts-flush-net` thread exits after close; an owned flusher restarts on a new registration (A-B2) |
| | `ManualAddressParserTest` | 2 | 35 vectors in `VECTORS`, for WP-S6's `app.js` mirror |
| `:data` | `RingTargetSourceTest` | 9 | §5.1 rows: internal; USB mounted/missing/full; SMB READY; last-known-good; a failure seen → UNREACHABLE; AUTH and restored-without-password → SIGN_IN_NEEDED; NO_SPACE_THERE; rewind Off keeps the keeper IDLE. Also: `current()` and `open` make 0 link calls; file names; the grace; demotion doubling; the fallback name |
| | `SmbSessionKeeperTest` | 6 | the wake write before the first-READY sweep; last-known-good; AUTH_FAILED until the sign-in changes; a missing password; BACKOFF and a trigger retry; choosing this device disconnects; a storage-side reconnect goes through the keeper's link and re-writes `.wake` |
| | `SmbjRemoteLinkTest` | 8 | `SmbConfig` timeouts, buffers and socket factory equal the constants; two `SMBClient`s; `close(true)`; `share()` waits and asks the keeper, and never waits on box-smb; reconnect single flight + min gap; interrupt → InterruptedIOException; `post` runs on box-smb |
| | `SmbFaultsTest` | 7 | the §4.3 order over real smbj exception shapes (an interrupt wrapped by `Futures.get`, EOF/reset, "transport is disconnected", a timeout on a live vs a dead transport, statuses, guest signing); log names carry no path or user; §7.7 browse errors; share and folder filters |
| | `SmbSweepTest` | 4 | own other-pid files; foreign files by server time against our `.wake`; without our `.wake` nothing foreign is touched; failures ignored |
| | `StorageLocationsImplTest` | 6 | password only in `SecureBlobStore`; the caller's and the UTF-8 arrays zeroed; not in the domain export or any `toString`; the audit gets user, domain and password; update/rename/remove (blob first); restore → SIGN_IN_NEEDED; unknown USB → internal (A-N7) |
| | `LocationTesterTest` | 5 | the grading table; phases; the test file deleted on close; the time bound; a refused write; no space; Stop test |
| | `SmbIntegrationTest` (2), `SmbConcurrencyTest` (1) | 3 | opt-in: `THEBOX_SMB_URL=smb://host:port/share/path`, `THEBOX_SMB_USER`, `THEBOX_SMB_PASSWORD` (never printed), `THEBOX_SMB_SOAK_S` (default 60) |

### What was done

`:core`
- `storage/smb/SmbRingStorage.kt`: §4.1–§4.5 as specified.
  - `pwrite`/`pread` copy through a scratch array only when the buffer has none (one per flusher, one `ThreadLocal` per loader thread).
  - The reopen lock is a `ReentrantLock` taken with `tryLock(deadline)`, so a waiting reader stays interruptible (A-B3).
  - `ratesBps()` is a 10-bucket per-second counter, allocation-free.
- `storage/ManualAddressParser.kt`: the §7.2 rules (listed in its KDoc).
- `RingFlusher.kt`: an owned flusher's thread ends once no ring is registered. The check runs under the `ensureThread` monitor, so a racing `register` starts a new thread. `TsRingImpl.kt`: only an `internal val ringFlusher` for the tests.

`:data`
- `smb/SmbFaults.kt`: the §4.3 classifier; `SmbIoException` (its message is the fault name only); `LinkUnavailableException`; the §7.7 `browseError`; a log-safe `faultName`.
- `smb/SmbjRemoteLink.kt`:
  - `SmbExecutor`: a named single thread; a throw is logged by fault name.
  - `SmbjClients`: the §4.3 config, the ring and browse clients, `forceClose` = `close(true)`, and smbj's password copy zeroed after `authenticate`.
  - The `ShareOpener`/`OpenedShare` seam, and `SmbjShareOpener` (its SMBClient is made lazily on box-smb, A-N6).
  - `SmbjShare`/`SmbjFile`: the §4.1 open flags with full sharing.
  - `SmbjRemoteLink`: the epoch, the refcount, the free-space cache, the `share` wait, and a single-flight `reconnect` with the min gap (inline on box-smb).
- `smb/SmbSessionKeeper.kt`:
  - the §5.3 states, triggers and backoff;
  - the wake write on every (re)connect and on ON_START / preview start;
  - the first-READY sweep, after the wake;
  - the keepalive (SLOW keeps the connection);
  - the background disconnect, unless a file is open;
  - the demotion window (§5.4) and a connect watchdog (`box-smb-watch`);
  - `LastGoodStore`;
  - `KeeperTriggers`: the lifecycle, the default network callback, and `StartupGate` or 2 s.
- `smb/SmbSweeper.kt`, `smb/SmbShareLister.kt` (dcerpc `getShares1`), `smb/LocationTester.kt` (§7.8).
- `StorageLocationsImpl.kt`, `StorageTargetsImpl.kt` and `DeviceIdentity.kt`.
- `NetworkFoldersImpl.kt`: browsing on `box-smb-browse`; every call is `runInterruptible`, so BACK or Stop test cancels.
- `StorageModule.kt`: binds `RingTargetSource`, which turns the feature on. The `storage` domain registers on first use, through `StorageLocationsImpl`.
- `diag/DiagnosticsImpl.kt`: network-folder user names, domains and passwords join the audit's secrets.

`:app`
- `proguard-rules.pro`, the smbj/mbassador/dcerpc block:
  - mbassador kept whole, plus every `@Handler` method;
  - smbj's event classes, enums, file-information classes and NTLM authenticator;
  - dcerpc's enums;
  - `-dontwarn` for `javax.security.auth` and the slf4j 1.x binder, beside commit 0's two lines.

### Lead rulings applied (message of 2026-10-24, after WP-S2)
1. A tune-time fallback carries the chosen location's name in the INTERNAL target's `name` (tested).
2. USB: `RingTarget.Local.dir = Volume.dir` (tested).
3. ACCESS → SIGN_IN_NEEDED as the fallback reason: WP-S2's mapping, unchanged here.
4. A stop before the cycle's first write (`sinceFirstWriteMs < 0`) is inside the grace (tested).
5. The owned flusher exits after its ring closes (`DedicatedFlusherTest`).

### Deviations
1. **`SmbRingStorage.pwrite` creates on first use** (with a deadline of one I/O timeout) when nobody called `create` first. `FallbackRingStorage` always calls `create`, so this only serves tests and tools. A failed create is still never retried.
2. **Read failures leave `lastFailure` alone.** Only writes and the create set it, so a reader's SLOW never decides the ring's wording.
3. **The sweep keeps every `ring-<deviceId>-<pid>-*` file of the current process**, not only the open file, because an older file's posted delete may still be queued. The Test file is `test-<deviceId>-<pid>-<nanoTime>.bin` (pid added) for the same reason.
4. **`SmbRingStorage` retries a failed reconnect** every 250 ms within the budget. The link spaces the real attempts by `SMB_RECONNECT_MIN_GAP_MS`.
5. **Last-known-good is written at most every 10 min per location** (`LastGoodStore.WRITE_EVERY_MS`). A keepalive every 20 s would otherwise write the file every 20 s. The in-memory value is always current.
6. **`StorageLocationsImpl` exposes keeper-side members** beyond the contract: `keptLocation`, `readPassword`, `publishKeeper`, `signInNeeded`, `signInVersion`, `auditSecrets`, `addChangeListener`. They are for `:data` only.
7. **`ref(id)` = `"smb-" + locationId`**, and location ids already start with `smb-`, so refs read `smb-smb-<uuid>` (§8 taken literally). Harmless; it is noted in case the lead prefers `ref = locationId`.
8. **The keeper starts from its `@Provides`**, on the first injection of `StorageTargetsImpl` or the keeper, not from an app-start hook: `TheBoxApplication` is in no WP-S1 file.

### Questions for the lead
1. **An app-start hook for the keeper** (deviation 8). Add `storageKeeper.get()` to `TheBoxApplication`'s start, or rely on the factory's injection of `Optional<RingTargetSource>`?
2. **The time base.** `SmbRingStorage`'s deadlines use `SystemClock.elapsedRealtime` (`StorageModule.MONO`), as `PlaybackModule`'s `MonoClock` does. `FallbackRingStorage` must keep computing `create(deadline)` from that clock.
3. **`LocationState.DEMOTED`** is published only while the keeper is READY. When the folder is also unreachable, the status says UNREACHABLE. Acceptable for the Save to page?
4. **`SIGN_IN_TOAST_PROCESSES`** (the 3-process counter) belongs to WP-S4's `FallbackToastPolicy`, so `RingTargetSourceTest` does not cover the "toast counters" of §16.2.
5. **`EditorPerfTest.budgets`** failed once under load during the gate and passed when re-run alone. It is unrelated to this WP, but it is load-sensitive when worktrees build in parallel.

---

## Integration pass (2026-10-24)

On `main` at `a7f5543` (commit 0 + WP-S1…S6), the main working tree, uncommitted (the lead commits). Emulator `TheBox_TV_API30` (emulator-5554), cold-booted first (its clock had drifted 8 h). **Every instrumented run uninstalls the app, so the owner's playlist and Keystore key on the emulator are gone**; it now carries the debug build with the synthetic panel playlist. Logs, JUnit XML and screenshots: session scratchpad `integ/device/<run>/` and `integ/shots/`. MacPorts Samba was **not** installed during the pass (`/opt/local/sbin/smbd` absent), so no case ran against a real share.

### Lead rulings applied

| # | Ruling | What was done |
|---|---|---|
| 1 | S6-Q1 | `SettingsScreen(phone: (PhonePurpose, Long?) -> Unit)`, `AppRoot.settingsPhone` likewise; `MainActivity` passes `browse` to `PhoneSetupHost(…, browse =)`, so the phone page gets the browse's `PhoneContext`. |
| 2 | S6-Q2 | `StreamBody.copyTo` zeroes its 64 KB chunk in a `finally`; `LanHttpServerImpl` reads through a `WipingBufferedInputStream` whose 8 KB read-ahead (it also holds body bytes) is zeroed when the connection ends. `PhoneApi`'s `FixedSink` was already zeroed (WP-S6). Test: `HttpParserTest.theChunkBufferIsZeroedAfterTheCopy` (success and truncated body, and the read-ahead wipe). |
| 3 | S6-Q3 | Accepted as built (5 s linger, accepting nothing). |
| 4 | S1-Q1 | `TheBoxApplication` builds `RingTargetSource` (and so the keeper) on `Dispatchers.IO` after the settings read, only when the TIMESHIFT choice is `smb-…`. The factory now takes `Optional<Provider<RingTargetSource>>` and resolves it on the first `prepare` (IO), so constructing the controller on main never builds the storage graph. The keeper's own first connect still waits for the first frame (A-N6), and it idles/disconnects in the background as §5.3 says (unchanged). |
| 5 | S1-Q2 | Verified, no change: `PlaybackModule.monoClock`, `StorageModule.MONO`, the factory's clock (→ `FallbackRingStorage`, `VolumeStorage`) and `SmbRingStorage`/the keeper are all `SystemClock.elapsedRealtime`. (`NetworkFoldersImpl` uses `nanoTime` for browse timing only, never compared with the ring's.) |
| 6 | S1-Q3 | Accepted: §5.4 sets the target choice, which honours DEMOTED whatever the keeper state (`StorageTargetsImpl.demoted`); the page shows UNREACHABLE while the folder is also unreachable, which is the more useful reason. |
| 7 | S4-Q1 | `SettingsViewModel` calls `attachNavigation` on every `SettingsNavigationAware` source. |
| 8 | S4-Q2 | `SettingsPageSource.onLeft(route)` (default false) and `SettingsShellLink.leftHandler` (default no-op); `SettingsMachineImpl.popPage(fromBack = false)` asks it; `StoragePages.onLeft` goes up one folder below the share root, else ← pops. `SettingsBackLadderTest`, `SettingsReachabilityTest` and the M5 settings tests are green; `StoragePagesTest.leftOnAFolderPageGoesBackOneLevelAndTheRowOffersUp` covers ←. |
| 9 | S4-Q3 | (a) holds: `choose(TIMESHIFT, "vol-<uuid>")` creates the VOLUME location (name from `VolumeCatalog`). (b) was **not** true: `open(location.smb, null)` signed in with an empty password. `NetworkFoldersImpl.open` now, when no password is given and the address has a user, reads the matching stored location's password from `SecureBlobStore` (zeroed after use); a missing or unreadable one answers `NeedsSignIn`. |
| 10 | S4-Q4 | `PlayerViewModel.rewindStat` appends `PlayerText.rewindPlaceStat` (kind, name, fallback from `LocalBufferState`, write rate read per stats tick, since the rates are outside its equality). `StatsOverlay.rewindPlace` stays unused (the suffix is already in the line). |
| 11 | S4-Q5 | `PlaybackNotice.SavingStopped` gains `jumpedToLive` and `resumeLost` (defaulted). The controller's `RingIoException` branch sets them (paused, or a read failure ≤ 10 s after a buffered resume = `resumeLost`; behind live or paused = `jumpedToLive`), once per cycle. `PlayerViewModel` turns a disk stop of a USB/SMB cycle into `PAUSE_RESUME_LOST_STORAGE` / `REWIND_STOPPED_STORAGE` / `REWIND_STOPPED_SLOW` with the §10 arg (place named; "back at live" variant); this device keeps today's text. Tests: `ControllerTimeshiftTest.aReadFailureOnTheResume…`, `…AtLiveKeepsThePlainNotice`, `TimeshiftViewModelTest.storageStopsOnANetworkFolderNameIt`. |
| 12 | S4-Q6 | Accepted. |

**Other open questions, decided here:**
- Commit 0 Q1–Q3: accepted as built (sub-page live in commit 0; the `FallbackToastGate` seam; `RemoteLink.post`).
- S3-Q1: accepted; `StoragePages` collects `find()` in the page scope and cancels on `closed(FindComputers)`. S3-Q2 (NSD not network-bound below API 33) and S3-Q3 (NO_NETWORK semantics): accepted for v1.
- S1-Q4: accepted (the 3-process counter is `FallbackToastPolicyTest`'s). S1 deviation 7 (`smb-smb-<uuid>` refs): kept; renaming would orphan stored passwords.
- S4 deviation 7 ("NAS is almost full" without a size): accepted for v1.
- **Not done, fix-pass item:** → (RIGHT) on an Item with `primary` (a folder, a share) does nothing; OK works. §9.3 says "OK (or →)". Changing the machine's RIGHT for every Item is an M5-wide behaviour change; it wants a source hook like `onLeft`.

**Found and fixed in the pass:**
- `:app:minifyReleaseWithR8` failed: dcerpc references `java.rmi.UnmarshalException` (absent on Android). Added `-dontwarn java.rmi.**`; `SmbShareLister` now also catches `LinkageError` (that path is a `NoClassDefFoundError` at runtime) and answers LIST_FAILED. R8 is green.
- Debug aids: `TimeshiftFactory` logs one DEBUG line per prepare (target, chosen, fallback, pool, capacity, reuse/build, ms); the keeper logs (DEBUG) when it skips a connect in the background; `StorageDebugEntryPoint` (debug source set); `thebox-debug://settings?route=pauserewind|saveto|findcomputers`.

### Unit gate

`./gradlew :core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`: green. `:core` 3,617, `:data` 416 (3 opt-in skipped), `:ui` 392, `:app` 72. `:player:testDebugUnitTest` alone: 501, green. `EditorPerfTest.budgets` and `GuideViewModelTest.hidingTheFocusedChannelKeepsTheIndex` did not flake this time. `:app:minifyReleaseWithR8`: green after the fix above.

### Device suite `SaveLocationTest` (new; WP-S5 had not written it)

`app/src/androidTest/kotlin/app/thebox/player/SaveLocationTest.kt`. Run with the fake panel as for `TimeShiftBufferTest`, plus `python3 tools/smbproxy/smbproxy.py --listen 0.0.0.0:4446 --upstream 127.0.0.1:4445 --control 0.0.0.0:4447` (the emulator drives faults over `10.0.2.2:4447`). Nothing listens on 4445 without Samba, so `10.0.2.2:4445` is "unreachable" (refused); the proxy's `blackhole` is "accepts TCP, never answers SMB". A missing precondition logs "not run" and returns (AGP counts an assumption failure as a failure).

Final class run: **8/8 pass, 2 of them "not run" (Samba)**.

| Case | Result | Numbers |
|---|---|---|
| I-SL-19 (part) Save to by keys from Playback, Find computers ends, BACK | pass | 2 OKs from Playback; Find ended in 3.0 s with "1 computer found" (the Mac at 10.0.2.2, port 445 open: macOS File Sharing), not the empty state |
| I-SL-4 refused folder, failure seen → this device, one toast, no second toast on the next tune, rewind works | pass | failure seen 0.2 s after choosing; 10 s pause → BEHIND 11.2 s |
| I-SL-4b last-known-good + blackholed → SMB chosen without waiting, switch at the deadline, one toast, rewind works | pass | switched 8.7–11.7 s after choosing (tune ~3–5 s + the 6 s deadline); toast 0.2–0.4 s after; BEHIND 11.3 s after a 10 s pause |
| I-SL-3 (emulator form) 15 CH+ zaps, blackholed folder vs this device | pass | blackholed P50 301 / 334 / 346 ms vs this device 358 / 369 / 401 ms (three runs); prepare reuse 0 ms, no link calls |
| keeper switch: a hanging connect to A does not keep the keeper from B | pass | B's failure seen 6.0 s after choosing B (A's 8 s I/O timeout ends the hang first) |
| I-SL-8a demotion (simulated: `onStorageFailure` 30 s after the first write) → next tune UNSTABLE + toast; inside the grace no demotion | pass | toast at once |
| I-SL-1 (Samba), I-SL-8 (Samba + proxy, real mid-cycle drop) | not run | need `-e samba 1` and Samba on 4445 |

**Emulator finding for the TS owner (not the folder):** first media on a zap is bimodal on the emulator, ≈ 0.35 s or ≈ 1.3 s per 15-zap block. **This device with a 1-h ring (pool 64) was slow in 3 of 3 runs** (P50 1,277–1,362 ms vs 358–401 ms at 30 min); the journal puts the extra ≈ 800 ms between SNIFF and FIRST_FRAME (bytes in, reader/extractor slow), not in prepare. The folder's 1-h cycles (pool 160) were slow in 2 runs and fast in 1. Auto is 1 h on devices > 16 GB and on every SMB cycle, so this wants the hardware zap gate at 1 h.

**Seen once, not reproduced:** in the first class run the keeper never tried a newly chosen folder (no log for 30 s) right after a blackholed connect; two later class runs and the dedicated keeper-switch case pass. The likeliest reading is `foreground = false` (the keeper then stays IDLE silently); the new DEBUG line will say so if it recurs.

### Regression classes (each alone)

All green: `TimeShiftBufferTest` I-TS-1, 2, 3, 9, 14, 20a, 28; `TimeShiftTest` 5/5; `StatsOverlayTest` 1/1; `FiveKeyJourneyTest` 1/1; `FiveKeySettingsTest` 1/1; `PhoneSetupTest` 6/6; `SmokeAndZapTest` 7/7. The whole ~90-min `TimeShiftBufferTest` class was not rerun.

### Manual smoke (adb, screenshots)

- Settings › Playback › Pause and rewind › Save to: This device (IN USE, 8.5 GB free), "USB drive · SDCARD" (the emulator's SD card is listed through `getExternalFilesDirs`, 0.5 GB free), Add a network folder. **UI defect:** the page subtitle ("Where rewind is saved while you watch.") is overlapped by the first row. The breadcrumb skips "Pause and rewind" when the page is opened by route.
- Find computers: the sweep found 10.0.2.2 ("No name"); manual entry, Search again and Add from your phone listed.
- Manual entry `\\10.0.2.2\rewind` (chips shown): reached the Mac's own smbd on 445, which refuses guests → "Sign in to 10.0.2.2" with the reason/next step. The "Which account?" info line is cut with an ellipsis (the §9.4 rule draws next-step lines whole).
- Sign in from your phone: the QR page "Add a network folder from your phone" with the plain-HTTP note, URL and code.

### Still open

- Hardware: S-1 (USB on Fire TV / Onn), S-2 (release build on Fire OS 6 against a real server, APK delta, flusher CPU), I-SL-15 (discovery on a real LAN), the zap gate (now also at 1 h), I-SL-11 CPU.
- Samba: I-SL-1, 2, 5, 6, 7, 8 (real drop), 9, 10, 12, 13, 14, 16, 21–24 once `sudo port install samba4` is done (`tools/smbtest/run-samba.sh` + `check.sh`, then `-e samba 1`).
- Fix pass: → on folder/share Items; the Save to subtitle overlap; the cut "Which account?" line; "Use as guest" still offered after the server refused guests; the 1-h first-media finding (TS owner).
