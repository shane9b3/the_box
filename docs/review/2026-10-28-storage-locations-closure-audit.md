# Closure audit: save location fix pass (main @ 5d99f57)

Read-only. No Gradle, no edits. Evidence is `file:line` on main.

## Verdict

**Mostly closed, with open items.** Every blocker and should-fix in both reviews (C-B1..3, C-S1..7, U-B1, U-S1..10) is on main. So are the nice-to-haves the records say were taken (C-N1, C-N2, C-N3, C-N4 in part; U-N2, U-N3, U-N4, U-N6, U-N9, U-N10). Every named regression test exists. Four of the integration pass's "Still open" UI items were taken.

The merge itself is clean:
- Topology: `3b8d5cd`'s parent is `b67f5e4`, so the core half was rebased onto the UI merge. `5d99f57`'s tree equals `3b8d5cd`'s, and `git diff 3b8d5cd 5d99f57` is empty.
- No duplicate strings, imports or overrides were found.
- No stale symbols, no TODO/FIXME and no scratch files.

Open:
- **Cross-half items nobody took:** U-N1 display, C-N4 parser side, C-N5, C-N7.
- **New gaps between the halves:**
  - The C-S3 failure reason is published to `LocationStatus.failure`, but no page reads it.
  - The `app.js` mirror now diverges from `PhoneApi.splitFolder` on control characters.
- **Needs a lead ruling:** the C-B1 UNREACHABLE wording.
- **Partial:** the U-S9 keeper hook works only within one process.
- **Gate coverage:** neither half ran `:ui:testDebugUnitTest` or `:app:compileDebugAndroidTestKotlin` on the combined tree. The core half's gate after its merge left out `:ui`. The lead's gate now covers this.

## 1. Findings → fix on main

| Finding | Record claim | On main? | Evidence |
|---|---|---|---|
| C-B1 | Create on its own thread; switch at deadline / pool time × 0.6 / 75 % full; delete primary; one toast | yes | `FallbackRingStorage.kt:58` (`box-smb-create`), `:104` `abandonUndecided`, `:108` `attachPool`, `:244-267` bound + `POOL_MARGIN = 0.6`; `RingPoolWatcher.kt:10`; `TimeshiftSessionFactoryImpl.kt:639`. Tests `FallbackRingStorageTest.kt:213,230,244,263,290`, `TsRingOverSmbTest.kt:122`, `FactoryTargetTest.kt:303` |
| C-B2 | One connection per browse session, fresh `SMBClient` per connect | yes | `NetworkFoldersImpl.kt:84` `SmbjBrowseConnections`. Tests `NetworkFoldersImplTest.kt:87,104` |
| C-B3 | `Os.pwrite/pread`; `UninterruptibleFileIo` fallback; `FileRingStorage` off `FileChannel` | yes | `AndroidRingStorage.kt:37-45,71-92`; `UninterruptibleFileIo.kt:13`; `FileRingStorage.kt:62` (no `FileChannel` left). Tests `RingFileInterruptTest.kt:28,43,88`, `AndroidRingStorageInterruptTest.kt:40,59` |
| C-S1 | Link key without name/path/test; `retire()` | yes | `SmbSessionKeeper.kt:130-137,241-245`; `SmbjRemoteLink.kt:298-366`. Tests `SmbSessionKeeperTest.kt:113,133` |
| C-S2 | Unknown status on live transport = SLOW; INSUFFICIENT_RESOURCES SLOW | yes | `SmbFaults.kt:41,73`. Tests `SmbFaultsTest.kt:79`, `SmbReconnectTest.kt:122` |
| C-S3 | `RingTargetSource.onCreateFailed`; keeper demotes on ACCESS/FULL/REMOVED; reuse switched ring | yes (page side: see O-6) | `TimeshiftContracts.kt:126`; `StorageTargetsImpl.kt:103,145-150`; `SmbSessionKeeper.kt:224-235,458`; `TimeshiftSessionFactoryImpl.kt:399`. Tests `FactoryTargetTest.kt:326`, `RingTargetSourceTest.kt:192`, `SmbRingStorageTest.kt:61`, `FallbackRingStorageTest.kt:276` |
| C-S4 | Only FULL answers 0 | yes | `SmbRingStorage.kt:71,231-235`. Tests `SmbRingStorageTest.kt:80`, `TsRingOverSmbTest.kt:148` |
| C-S5 | No sweep with VPN and no bindable net; strict sockets | yes | `ComputerFinderImpl.kt:77`; `Port445Sweep.kt:39` (`strict`). Test `ComputerFinderTest.kt:441` |
| C-S6 | Cancel after sign-in closes; no password copy; lister rethrows | yes | `NetworkFoldersImpl.kt:2` (header), `SmbShareLister.kt` changed in `3b8d5cd`. Test `NetworkFoldersImplTest.kt:116` |
| C-S7 | Exclusive open + delete-on-close; stop at first SLOW | yes | `RemoteContracts.kt:26-29`; `SmbSweeper.kt:19`. Tests `SmbSweepTest.kt:37,55` |
| C-N1 | Generation per write | yes | `SmbRingStorage.kt:60`. Test `SmbReconnectTest.kt:140` |
| C-N2 | `rm` only if CREATE sent | yes | Test `SmbRingStorageTest.kt:50`; `SmbPrepareNoIoTest.kt` changed |
| C-N3 | Immutable `Handle` | yes | `SmbRingStorage.kt:75` |
| C-N4 | `splitFolder` refuses control chars (parser side not taken) | partial | `PhoneApi.kt:500-501`. Test `PhoneApiNetworkFolderTest.kt:289`. `ManualAddressParser.kt:27` still has only `EMPTY, BAD_HOST, BAD_PORT, UNSUPPORTED_SCHEME`. `app.js:269-285` has no control-char check (see O-2) |
| U-B1 | Shares-page WARN note; `typedJob`; NOT_FOUND / LIST_FAILED texts; `ERR_GENERIC` gone | yes | `StoragePages.kt:98,113,477-485,548,906,1158,1345,1386`; `StoragePageEnv.kt:225`. Tests `StorageReviewUiProofTest.kt:94,114,126,142` |
| U-S1 | `storage.folder.item.` / `storage.share.item.` | yes | `StoragePages.kt:1404-1405,480-481,566,962`. Test `:159` |
| U-S2 | Find focus once per visit plus key signal; sticky sign-in focus | yes | `StoragePages.kt:100,175,426,539,942-946,1101,1210-1219`; `SettingsMachineImpl.kt:262,412`; `SettingsViewModel.kt:92`. Tests `:174,189,202` |
| U-S3 | Guest row disabled with reason after GUEST_REFUSED | yes | `StoragePages.kt:527-531`; `strings_storage.xml:166`. Tests `:222,234` |
| U-S4 | Domain row `TextHint.PATTERN` | yes | `StoragePages.kt:533`. Test `:242` |
| U-S5 | `StorageLocations.recordTest` | yes | `StorageContracts.kt:143`; `StorageLocationsImpl.kt:153-156`; `StoragePages.kt:743,1019`. Tests `:262,276`, `StorageLocationsImplTest.kt:76` |
| U-S6 | String rewrites; 80/60 rule; `widthIn(max=560)`; mono details | yes | `SettingsRowViews.kt:159,198`; `SettingsUi.kt:114-115`; `strings_storage.xml:112`. Tests `StorageTextTest.kt:140`, `StorageRowsUiTest.kt:62,72` |
| U-S7 | `ROWS_TOP_SUBTITLE = 132` | yes | `SettingsUi.kt:85-89`. Tests `StorageRowsUiTest.kt:90,100,110` |
| U-S8 | `onRight` / `rightHandler` | yes | `SettingsPages.kt:58,111`; `SettingsMachineImpl.kt:260,435`; `SettingsViewModel.kt:91`; `StoragePages.kt:1196-1208`. Tests `:291,309` |
| U-S9 | `FallbackToastGate.signedIn`; policy resets counter; pages call it | yes (keeper side: partial, see X-1) | `StorageContracts.kt:151-158`; `FallbackToastPolicy.kt:40-44`; `StoragePages.kt:858,1018,1115`; `SettingsBindingsModule.kt:74`. Test `FallbackToastPolicyTest.kt:45` |
| U-S10 | Full breadcrumb, middle ellipsis | yes | `SettingsScreen.kt:144,175-213`. Tests `StorageRowsUiTest.kt:121,139` |
| U-N2 | Sign-in Playback value; DEMOTED description | yes | `WpaPages.kt:345`; `StoragePages.kt:266-267`. Test `:323` |
| U-N3 | Row `contentDescription` | yes | `SettingsRowViews.kt:127,168,215`. Test `StorageRowsUiTest.kt:151` |
| U-N4 | `storage_phone_result_*` deleted | yes | no hit in `git grep`. Only a historical mention remains at `m6-storage-locations.md:1563` |
| U-N6 | Typed path keeps shares under it | yes | `StoragePages.kt:1137` |
| U-N9 | Route read on main | yes | `StoragePages.kt:1084,1127`. Test `:339` |
| U-N10 | Finders paused under browse | yes | `StoragePages.kt:178,223,251,438,455`. Test `:353` |

Deliberately not taken (stated in the records): U-N1 TV display, U-N5, U-N7, U-N8 ("not in the lead's list"); C-N5, C-N6, C-N7, C-N8.

## 2. Cross-half items

| Item (from) | Who | Status / evidence |
|---|---|---|
| U-S9 keeper side: `signedIn` when SIGN_IN_NEEDED→READY without the pages (UI → core) | core half | **Done, in-process only.** `SmbSessionKeeper.kt:124,151-152,423-427` (`signInPending` set on AUTH_FAILED, `onSignedInAgain` on READY); `StorageModule.kt:41` `@BindsOptionalOf`, `:64,73`; app binds the gate at `SettingsBindingsModule.kt:74`. Test `SmbSessionKeeperTest.kt:44` (asserts `w.signedIn`). Gap X-1: `signInPending` is per process. Example: a folder was SIGN_IN_NEEDED in an earlier process (counter at 3) and comes back READY in a new process, say a restore or a fix on the server side, without AUTH_FAILED in this process. Its persisted counter (`FallbackToastPolicy.kt:28-31`, `FileSignInToastCounters`) is never reset. |
| U-N1 state: `LanServerState.lastSignInStatus/lastSignInOk` (UI → core) | core half | Done. `LanContracts.kt:58-63`; `LanPhoneLink.kt:200,219,243-244`. Test `PhoneApiNetworkFolderTest.kt:396-404` |
| U-N1 display: `PhoneSetupScreen` draws it in Warn (core → UI) | **nobody** | `git grep lastSignInStatus` matches only `core/`. No hit in `ui/.../setup/PhoneSetupScreen.kt` or `PhoneSetupViewModel.kt`. **Open (O-1)** |
| C-N4 parser `Reason` for `..` / control chars + string + `app.js` mirror (core → UI) | **nobody** | `ManualAddressParser.kt:27` unchanged. Also `app.js:269-285` was not updated when `PhoneApi.kt:500-501` started refusing control characters, so the two "mirrored" functions now differ (the phone page accepts and the server answers 400 "bad folder"). **Open (O-2)** |
| C-N5 NETWORK_FOLDER collector in `StoragePages` (core → UI) | **nobody** | `LanPhoneLink.kt:100` still `canAccept = { _submissions.subscriptionCount.value > 0 }`. No password zeroing of an unconsumed submission in `stop()`: no `fill(` in `LanPhoneLink.kt`. **Open (O-3)** |
| C-N7 "Stopped: NAS took too long" string (core → UI) | **nobody** | No hit for "took too long" outside docs. `LocationTester.kt` has no whole-run deadline. **Open (O-4)** |
| C-B1 pool-bound switch reads UNREACHABLE "Can't reach / isn't answering" (core → UI/lead) | **nobody** (informational) | `FallbackRingStorage.kt:190,195` maps no-failure to UNREACHABLE; toast `strings_storage.xml:181`. Needs a lead ruling: accept it, or add an append-only `LocationFallback.SLOW` with UI strings. **Open (O-5), decision** |
| `StorageContracts.kt` two UI defaults vs the core edit (UI → core) | n/a | Clean. The core commit `3b8d5cd` does **not** touch `StorageContracts.kt`: its new default `onCreateFailed` went into `TimeshiftContracts.kt:126`. `StorageContracts.kt:143` (`recordTest`) and `:158` (`signedIn`) appear once each. `LocationStatus.failure` (`:127`) predates both halves (blame `39914158`). `StorageFakes.kt` was edited by both: `:70` `recordTest` and `:158` `onCreateFailed`, no duplicate. |
| Device check: the 132-px top, mono details, deep breadcrumb (UI → lead) | deferred | Device pass |
| §21 / S-2: LastWriteTime of a held-open file on Windows/macOS smbd (core) | deferred | Device / Samba pass |
| Instrumented `AndroidRingStorageTest`, I-TS-x (core) | deferred | Device pass (`:player` has no androidTest) |
| C-B2 opt-in Samba case (core) | deferred | Samba not installed |

**Implicit gap the records did not list (O-6):** for C-S3, the record says "The `LocationStatus` carries the failure for the page". The keeper publishes it (`SmbSessionKeeper.kt:458` → `StorageLocationsImpl.kt:203-205`), but `StoragePages.statusLine` (`StoragePages.kt:326-333`) and the Save to description (`:264-268`) never read `LocationStatus.failure`. A folder demoted because the ring file was refused (ACCESS or FULL) therefore reads "%1$s kept dropping out. The Box tries it again in a few minutes." (`strings_storage.xml:35`). Meanwhile playback falls back as SIGN_IN_NEEDED or NO_SPACE_THERE (`StorageTargetsImpl.kt:145-150`), so the page and the toast give different reasons.

### Open list (nobody implemented)

1. **O-1 U-N1 display.** In `PhoneSetupScreen` (NETWORK_FOLDER purpose), draw `LanServerState.lastSignInStatus` in Warn under the status line when `lastSignInOk == false`, then add a UI test.
   - Files: `ui/src/main/kotlin/app/thebox/ui/setup/PhoneSetupScreen.kt`, `ui/src/main/kotlin/app/thebox/ui/backup/PhoneSetupViewModel.kt` (or its screen model), `ui/src/test/kotlin/app/thebox/ui/setup/PhoneSetupScreenTest.kt`.
2. **O-2 C-N4.** Add `Reason.BAD_PATH` to `ManualAddressParser`: reject a `..` segment and control characters, and drop `.` segments. Add the viewer string and the `StorageText` mapping. In `app.js` `splitFolder`, add the control-character refusal so it mirrors `PhoneApi.kt:500-501`, and move the control-character cases into `SPLIT_VECTORS`.
   - Files: `core/.../storage/ManualAddressParser.kt` + `ManualAddressParserTest.kt`, `core/.../settings/state/StoragePageEnv.kt`, `StoragePages.kt` (reason → text), `app/.../settings/StoragePageEnvImpl.kt`, `ui/src/main/res/values/strings_storage.xml`, `data/src/main/assets/phone/app.js`, `core/src/test/.../setup/lan/PhoneApiNetworkFolderTest.kt`.
3. **O-3 C-N5.** Gate `canAccept` per purpose. Use a NETWORK_FOLDER-scoped registration that `StoragePages.ensurePhone` takes and releases with `phoneJob`, instead of `subscriptionCount > 0`. Zero the `CharArray` of any undelivered submission in `stop()`.
   - Files: `core/.../setup/lan/LanContracts.kt`, `LanPhoneLink.kt`, `PhoneApi.kt`, `core/.../settings/state/StoragePages.kt`, `PhoneApiNetworkFolderTest.kt`.
4. **O-4 C-N7.** Give `LocationTester` a whole-run deadline (about 6 s plus the wake) checked between round trips. On hitting it, return a STOPPED-like result with the new text "Stopped: NAS took too long."
   - Files: `data/.../storage/smb/LocationTester.kt` + test, `core/.../storage/StorageContracts.kt` (a verdict or stop reason, append-only), `StoragePageEnv.kt`, `StoragePages.kt`, `StoragePageEnvImpl.kt`, `strings_storage.xml`.
5. **O-5 C-B1 wording (lead ruling).** Either record in the design doc that a pool-bound switch reads as "isn't answering", or add an append-only `LocationFallback.SLOW` with a toast and Save to strings and map a no-failure, pool-bound switch to it.
   - Files: `core/.../storage/StorageContracts.kt`, `FallbackRingStorage.kt:190`, `app/.../playback/FallbackToastPolicy.kt`, `strings_storage.xml`.
6. **O-6 C-S3 page reason.** In `statusLine` and the Save to / Pause and rewind descriptions, when `state == DEMOTED && failure != null`, show the failure's reason. ACCESS gives "no permission / sign in", FULL "full", REMOVED "folder missing", reusing the existing `storage_err_*` texts.
   - Files: `core/.../settings/state/StoragePages.kt`, `StoragePageEnv.kt` (`place.problem`/`failure` at `:274`), `WpaPages.kt:344`, `StorageReviewUiProofTest.kt` or `StoragePagesTest.kt`.
7. **X-1 U-S9 across processes (small).** Seed `signInPending` from `locations.signInNeeded(id)` (persisted) at keeper start, or have `StorageLocationsImpl` call the gate when it clears the stored sign-in-needed flag. Either way a READY in a new process resets the counter.
   - Files: `data/.../storage/smb/SmbSessionKeeper.kt`, `SmbSessionKeeperTest.kt`.

Deferred by the records and still deferred: U-N5, U-N7, U-N8, C-N6 (release smoke), C-N8, the 1-h first-media finding (TS owner), and the hardware and Samba I-SL items.

## 3. Merge sanity

- **Topology.** `bb7d233` is based on `8235d70`, merged as `b67f5e4`. `3b8d5cd` is based on `b67f5e4`: the core half merged main into its worktree before committing. `5d99f57` has parents `b67f5e4` and `3b8d5cd` and the same tree as `3b8d5cd`. So git never resolved a conflict; the core agent's in-worktree merge is the only resolution.
- **Duplicate string resources.** None. `cat ui/src/main/res/values/*.xml app/src/main/res/values/*.xml | grep -o '<string name=…' | sort | uniq -d` is empty.
- **Duplicate imports.** None in any `.kt` file changed between `094f3a4` and `5d99f57`. The repeated `override fun` names found there are in separate anonymous objects or classes (fakes, `SmbjRemoteLink` File/Share wrappers), not true duplicates.
- **Renamed symbols.** The UI half removed `ERR_GENERIC`, `ERR_GENERIC_STEP` and `storage_phone_result_*`; they have no references left in code. `SaveLocationTest` uses only `storage.find.*`, `storage.loc.*` and `playback.*` ids (`SaveLocationTest.kt:164-181`), so the U-S1 renames do not touch it.
- **TODO/FIXME/XXX/HACK.** None added: `git diff 094f3a4 5d99f57 | grep '^+.*TODO|FIXME|XXX|HACK'` is empty.
- **Integration "Still open" (design doc `:1942-1945`), fix pass items:**
  - "→ on folder/share Items": taken. Folders use `StoragePages.kt:1196-1208`; share rows carry `route` (`:481`), so the machine's own RIGHT handles them.
  - "Save to subtitle overlap": taken (`SettingsUi.kt:85-89`).
  - "Cut 'Which account?' line": taken (`strings_storage.xml:111-112` split into three ≤ 80-character lines; `StorageTextTest.kt:140`).
  - "Use as guest after refusal": taken, disabled with a reason (`StoragePages.kt:527-531`).
  - "1-h first-media finding": TS owner, not this pass; still open.
- **Interaction check, good.** The UI half's `recordTest` (U-S5) writes `lastTest` into `StorageDomain` (`StorageLocationsImpl.kt:153-156`) and does not bump `signInVersion` (`:129,141` only). With the core half's C-S1 `LinkKey` (`SmbSessionKeeper.kt:241`), a Test therefore no longer relinks mid-cycle. Without C-S1, U-S5 would have made every Test relink.
- **Gate coverage gap.** The UI half's gate ran without the core half's code. The core half's gate on the merged tree ran `:core :data :app` tests, `assembleDebug`, R8 and `:player`, but **not** `:ui:testDebugUnitTest` or `:app:compileDebugAndroidTestKotlin`. The core half's changes to `LanServerState` and the timeshift contracts are additive, but the combined tree is unverified for `:ui` until the lead's gate finishes.
- **Doc staleness (minor).** `m6-storage-locations.md:1563` (WP-S6 record) still lists `storage_phone_result_*`, which were deleted by U-N4.

## 4. Hygiene

- `ReviewCoreProbeTest` and `ReviewCoreDataProbeTest` are not tracked (`git ls-files | grep -i probe` lists only older M4/M5 files: `ProbeTest.kt` from `7643d44`, `ProbeSetupTest.kt` from `5b24c62`, plus debug probes). They are not in the working tree either (`find`), and `git status` shows only `gradle.properties` and `gradle/gradle-daemon-jvm.properties`.
- `StorageReviewUiProofTest.kt` **is** on main, added by `bb7d233`. This was deliberate: the record (`m6-storage-locations.md:1953`) and its KDoc (`StorageReviewUiProofTest.kt:43-47`) make it the U regression class. Suggest renaming it to `StorageReviewUiRegressionTest` so "proof" does not read as a scratch probe (cosmetic).
- No `scratchpad/`, `*.orig` or `*.rej` in `git ls-files` or in the two feature commits' file lists.
