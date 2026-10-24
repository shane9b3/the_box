# Save location for pause and rewind: code review, product/UI/state-machine half (U)

Date: 2026-10-24. Reviewer: U (product, UI, settings state machine; Opus). Code: `main` at `c727b1d`, feature range `e8b1f7d..c727b1d`. Spec: `docs/design/m6-storage-locations.md` (§7, §9, §10, every implementation record, "Integration pass (2026-10-24)"), `docs/design/m5-setup-settings.md` conventions, m6-timeshift "Words on screen", `docs/01-design-reference.md`. Reviewer C covers storage, SMB, player and security. No emulator was used.

Every finding was checked in the code. Findings marked **(proved)** have a unit test in `core/src/test/kotlin/app/thebox/core/settings/state/StorageReviewUiProofTest.kt` (this worktree). Each test states the behaviour this review asks for, so **all 8 fail on `c727b1d`**. That failure is the proof. Once a fix lands, keep the test as the regression test. Run it with `./gradlew :core:test --tests app.thebox.core.settings.state.StorageReviewUiProofTest`.

## Verdict

**Fix B1 and S1–S8 before the hardware pass.** The browse flow works end to end, but these problems remain:

- A mistyped share name fails silently, and it leaves an error behind that breaks the next share the viewer opens (B1).
- Two focus rules move the cursor away from where the viewer put it (S2).
- Several next-step lines are cut off by width (S6, including the five lead-known integration defects).

Checked and correct (no action):
- **Ladders.** `SettingsMachineImpl.parentOf` walks Save to → Pause and rewind → Playback (`SettingsMachineImpl.kt:1151-1157`).
  - BACK anywhere in the browse ends the session, zeroes the password and closes the handle (`StoragePages.kt:1069-1089`).
  - ← below the share root goes up a folder in place (`:1092-1100`). `SettingsReachabilityTest` holds the depth-8 / 12-BACK bound.
- **Credentials.** Routes carry only the token, `BrowseSession.toString` prints only the token, and the password row's editor starts empty (`StoragePages.kt:76-121, 483-487`).
- **Cancellation.** Find stops on `closed(FindComputers)`, and Test and phone collection stop on `closed` (`:1102-1118`). Connect, list and Test are tracked jobs over `runInterruptible` calls (`NetworkFoldersImpl.kt:57, 105, 147, 181`), so a late result after BACK cannot navigate. The one exception is the typed share (B1).
- **Test page.** BACK and Stop test both stop the Test and save nothing (`:1080-1086, 930-933`). GOOD focuses Use; SLOW and TOO_SLOW focus Choose another, with Use anyway second (`:609-617`).
- **Budget.** Playback keeps 5 simple rows (35 in total), and the sub-page holds only rewind rows (`PauseRewindRows.kt`, `SimpleRowCountTest`).
- **Words.** No "buffer", "ring", "segment" or "live edge" appears in `strings_storage.xml`, `strings_storage_phone.xml` or the changed `strings_player.xml` / `strings_settings.xml` lines. "SMB" appears in "Add a network folder"'s detail and in the SMB1 error, as §7.7 allows. Every toast path names a SIMPLE row, and "Protect built-in storage" is reached through "Show advanced settings" first (`strings_player.xml:170`).
- **Toasts.** The machine asks once per (cycle, reason) (`PlayerStateMachineImpl.kt:1433-1441`). The policy allows one toast per process per (location, reason), never NOT_READY_YET, and SIGN_IN_NEEDED for 3 processes (`FallbackToastPolicy.kt:21-34`). A disk stop on this device keeps today's text (`PlayerViewModel.storageStopToast`).
- **Type sizes.** Every new style is the kit's: 28/26/22, badge mono 18, phone plain-HTTP line 22. Nothing is below 18.
- **The pause step.** PAUSE_NO_REWIND_STORAGE keeps its next step through `noRewindStep` (`PlayerText.kt:189`).

---

## Blockers

### U-B1. A mistyped share name fails silently, then breaks the next share (proved)

**Problem.**
- `typedShare` stores the failure in `s.error` (kind NOT_FOUND) and in `s.note` (`StoragePages.kt:860-864`).
- `computerShares` draws neither while a handle exists: the error row needs `s.handle == null` (`:443`), and the note is never drawn there.
- `networkFolder` then uses `s.error` as the folder error whenever the listing has none (`list?.error?.takeIf{…} ?: s.error`, `:517`), and it draws `s.note` on top (`:515`). Changing the share does not clear either (`:510`).
- The typed-share coroutine is not tracked (`scope.launch` with no job, `:856`):
  - two OKs push two folder pages;
  - a late success after BACK pushes `NetworkFolder` for an ended session ("page not available").
- The NOT_FOUND / LIST_FAILED text is also vague: "Something went wrong with NAS." (`:1229`, `storage_err_generic`).

**Evidence.** `uB1_…` shows the rows are unchanged after typing "Nope". `uB1b_…` shows the rows on the good share TV afterwards: `[SHARE_NOT_FOUND(Nope,NAS), FOLDER_USE, ERR_GENERIC(NAS), TRY_AGAIN]`, with Use disabled and no folders listed.

**Scenario.** A Windows PC whose listing fails (§7.5 says the typed name is the way through):
1. The viewer types "Medai". Nothing happens.
2. They type "Media", or pick a listed share.
3. The folder page says there is no shared folder "Medai", says "Something went wrong with PC", and greys out Use this folder.

**Fix.**
1. In `computerShares`, draw `s.note` as a WARN Info row above the Type row, with the reason as the label and the next step as a line. Do not put a typed-share failure into `s.error`; keep a separate `s.shareError` that `computerShares` draws.
2. Clear `s.note` and the share error in `networkFolder` when `s.share != route.share` (`:510`), and on any share Item.
3. Track the job (`s.sharesJob` or a new `s.typedJob`): cancel it before starting another and in `endSession`. Ignore its result unless the session still exists and `currentRoute() == ComputerShares(token)`.
4. Use texts with a reason and a next step:
   - NOT_FOUND label: "There's no shared folder "%1$s" on %2$s." Line: "Check the spelling, or choose one from the list."
   - LIST_FAILED: "%1$s didn't answer the folder list." Line: "Try again, or type the shared folder's name below."
   - Replace `storage_err_generic` for these two kinds.

**Regression test.** Keep `uB1_` and `uB1b_`. Add a variant: type the name, BACK before the fake answers (gate `foldersAnswer` on a latch), and assert that the route stays `SaveLocation` and nothing is pushed.

---

## Should-fix

### U-S1. A folder named "use", "up", "note", "status" or "error" collides with a fixed row (proved)

**Problem.**
- Folder rows are `RowId("storage.folder.$f")` (`StoragePages.kt:527`). The fixed rows share that prefix: `storage.folder.use`, `.up`, `.note`, `.status` and `.error` (`:1276-1280`).
- `folderIntent` tests `ROW_F_USE` and `ROW_F_UP` before the folder prefix (`:889-894`). So OK on a folder called "use" starts the Test of the current folder, and OK on "up" goes up instead of in.
- `SettingsRowKeys` survives the duplicate key, so nothing crashes; the behaviour is simply wrong.

**Evidence.** `uS1_…`: the rows are `[storage.folder.use, storage.folder.use, storage.folder.up]`.

**Scenario.** A Linux NAS with lowercase folders, `/srv/tv/use` or `/up`. "Up" and "Status" folders are plausible on home NAS boxes.

**Fix.** Give data rows their own namespace: `RowId("storage.folder.item.$f")` and `RowId("storage.share.item.$name")`, and parse with `removePrefix`. Do the same for `storage.find.pc.` (safe today, because an address cannot contain a dot-word).

**Regression test.** `uS1_…`, plus "OK on the folder named use enters it" (the path becomes "use").

### U-S2. Focus jumps away from the row the viewer chose (proved, two cases)

**Problem.**
1. **Find computers.** `focus` is the first-sorted computer on every build (`StoragePages.kt:409`). The machine applies a page's focus whenever it differs from the previous build's (`SettingsMachineImpl.kt:894-895`). Each time the first-sorted computer changes, the cursor is taken again: a NetBIOS or NSD name arrives, a named computer sorts before an unnamed one, or a saved host appears. Deviation 9 of the WP-S4 record says "once"; the code asks again on every change.
2. **Sign in.** After a wrong password, focus is Password (`:499-503`). Typing the new password clears `wrongPassword` (`:876`), so the focus request flips back to User name and the cursor jumps up. The viewer then presses ↓ OK expecting "Sign in" and opens the password editor again.

**Evidence.**
- `uS2_…`: the viewer moves to "Enter a name or address", "DESKTOP-1" arrives, and the cursor is on `storage.find.pc.192.168.1.30`.
- `uS2b_…`: after the password is typed, the cursor is on `storage.signin.user`.

**Scenario.** On Find, the sweep runs for seconds after the first result. A viewer who pressed ↓ toward "Enter a name or address" is yanked back, and an OK in that moment opens the wrong computer.

**Fix.**
- Find: keep a `findFocused` flag (the field exists but is unused, `:164`). Request focus on the first computer only once per Find visit; after that, leave `focus` as it was (unchanged, so the machine does not re-apply it). Better still, add `SettingsPageSource.keyPressed(route)` from the machine so the page can drop its request after any key (U-S9's "as long as no key has been pressed").
- Sign in: make `focus` sticky.
  - `wrongPassword` → Password until a submit.
  - After the Password editor commits with a user name present → `ROW_SI_GO`, which is what the viewer wants next.
  - Never fall back to User name on a rebuild.

**Regression test.** `uS2_…` and `uS2b_…`. For the second, assert the cursor is on `storage.signin.go` once the fix moves it there.

### U-S3. "Use as guest" is still offered after the server refused guests (proved; lead-known)

**Problem.** `ROW_SI_GUEST` is always added, with `enabled = !s.connecting` (`StoragePages.kt:493`). After GUEST_REFUSED (`ERR_GUEST`: "NAS doesn't allow guests. Sign in with a user name and password.") the row still invites a retry that cannot work. Each retry costs a full round trip and repaints the same error.

**Evidence.** `uS3_…`; integration screenshot `integ/shots/10-result.png`.

**Fix.** Keep the row (§7.3 row order), but disable it while `s.error?.kind == GUEST_REFUSED`, with `disabledReason` = "%1$s doesn't allow guests." This follows the "disabled with a reason" convention of M5 §3.2. When guests were never tried, nothing changes.

**Regression test.** `uS3_…`: the row is present but `enabled == false`, and its `disabledReason` names the host.

### U-S4. "Workgroup or domain" cannot be cleared (proved)

**Problem.**
- The domain row uses `TextHint.NAME` (`StoragePages.kt:495`), and `HintValidator` rejects an empty NAME with "This can't be empty" (`SettingsTexts.kt:311`).
- The row's own description is "Leave empty unless you know you need it."
- `s.domain = …ifEmpty { null }` (`:877`) is unreachable.

**Scenario.** A viewer who typed "WORKGROUP" by mistake gets AUTH failures with no way back, short of BACK out of the whole browse. The same happens on `LocationSignIn` for a stored folder.

**Fix.** Add a `rowRules` entry for `StoragePages.ROW_SI_DOMAIN` that accepts "" (`{ _ -> null }`). Precedence: `rowRules` returns early only on error, so give the row `TextHint.PATTERN`, which already accepts "". Or add `TextHint.OPTIONAL`. The value then reads "Not set".

**Regression test.** `uS4_…`.

### U-S5. A stored folder's Test result is never saved, so Status stays stale (proved)

**Problem.**
- `useResult` for a stored location only `choose`s (`StoragePages.kt:939-941`).
- "Test this folder" stores nothing at all.
- A new walk that matches a saved address calls `updateSignIn` but drops the result (`:947-949`).
- `StorageLocations` has no way to write `lastTest` (`StorageContracts.kt:130-138`). §7.8: "`lastTest` stores the verdict, rates, RTT, `wakeMs` and the time."

**Scenarios.**
- "Good for HD and 4K channels · tested 40 days ago" stays even after a Test today reads "Too slow".
- "Use for rewind" re-runs the Test on every use once the stored one is older than `TEST_FRESH_MS` (`:791-794`).

**Fix.**
- Add `suspend fun recordTest(locationId: String, result: LocationTestResult)` to `StorageLocations` (additive contract, default no-op for fakes). Call it from `ensureTest` when a stored session's Test ends with a non-stopped result, and from the `same != null` branch of `useResult`.
- `StorageLocationsImpl` writes it, and `recompute` picks it up.

**Regression test.** `uS5_…`.

### U-S6. Next-step text is clipped by width (the "Which account?" class, broader than reported)

**Problem.** `GuideUi.Line` is `maxLines = 1` with an ellipsis (`GuideUi.kt:70`). The storage Info rows draw each `\n` line as one `Line` (`SettingsRowViews.kt:152`), so a long line is cut, not wrapped. Where the cuts come from:

| Where | Why it is cut |
|---|---|
| Verdict row (`StoragePages.kt:665`) | Carries a value, and the value column is a **fixed** `VALUE_MAX_W = 560` (`SettingsRowViews.kt:187, 201`). The label column is therefore about 650 px. The SLOW / TOO_SLOW / GOOD_HD next steps are 75–85 characters at 22 px (≈ 830–950 px), so the next step is cut exactly on the verdicts that need it. |
| Long sentences used as a `RowLabel` (28 px, one line, ≈ 1,228 px) | `TEST_WAKE_NOTE` (~96 characters ≈ 1,340 px). `FOLDER_MISSING` and `SHARE_NOT_FOUND` with real paths. `SIGN_IN_NO_KEYBOARD` (84 characters, at the limit). |
| `SIGN_IN_INFO` line 1 | 142 characters (screenshot 10). |
| `FIND_NONE_STEP` lines | 98 and 103 characters, at the limit. Any longer host name or translation (the 1.3× text-growth margin) cuts them. |

**Fix.**
1. In `RowBody`, draw the storage Info lines with `maxLines = 2`, and size `rowHeight` from the line count × 2 for those rows. Or keep one line and enforce ≤ 80 characters per line with a unit test over `strings_storage.xml`: split on `\n`, check each line's length.
2. For the verdict row, do not put the details in the value column. Make them a third line in mono (`RowValueMono` 22 is not in the kit, so use the line style), or let the value column shrink to the text (`widthIn(max = 560)` instead of `width(560)`).
3. Rewrite the long labels as a short reason label plus a next-step line:
   - Wake note: label "The drive took %1$s s to wake up." Line: "The Box wakes it when you open the TV, so rewind isn't affected."
   - Folder missing: label "There's no folder "%1$s" here." Line: "Choose one below." (the path is already in the subtitle).
   - No keyboard: label "No keyboard on this TV." Line: "Sign in from your phone, or use a folder that allows guests."
   - `storage_sign_in_info`: "On Windows, use an account on that PC.\nA separate local account for The Box is safer than your main sign-in.\nOn a Mac, use the Mac's user name and password."
   - `storage_find_none_step`: "Check the computer is on and file sharing is turned on.\nOn Windows, set this network to Private. On a guest Wi-Fi, connect The Box to your main Wi-Fi.\nOr enter the computer's name or address."

**Regression test.**
- `StorageTextTest`: every `storage_*` line (split on `\n`) ≤ 80 characters, and every Info **label** ≤ 60.
- A Compose test (`StorageRowsUiTest`) renders the TOO_SLOW verdict row and asserts `onNodeWithText(step).assertIsDisplayed()` with no ellipsis (`TextLayoutResult.hasVisualOverflow == false` through `onTextLayout`).

### U-S7. The page subtitle is overlapped by the first row (lead-known; root cause is the kit)

**Problem.**
- `PagePane` puts the rows at a fixed `ROWS_TOP − PAGE_PAD_V` = 72 px (`SettingsScreen.kt:277`).
- Title (36 px) + 6 + subtitle (24 px) takes about 85 px of line height, so any subtitle is overlapped.
- Every storage page has one, which is why the defect shows now. `ChannelOptionsPage`, `RestorePreview` and the setup pages that set `subtitle` are affected the same way.

**Fix.**
- Add `SettingsUi.ROWS_TOP_SUBTITLE = 132` and use it when `page.subtitle != null`.
- Use the same offset in `focusedRowY` (`SettingsScreen.kt:171`, the menu anchor).
- Check that the machine's `VISIBLE_ROWS` / `fixTop` still keeps the focused row in view with 36 px less pane (it scrolls by index through `LazyListState`, so a row may sit under the notice band; verify on the 8-row Find page).

**Regression test.** A Compose test on `SettingsRoute.SaveLocation()`: the subtitle node's `bounds.bottom` ≤ the first row node's `bounds.top`.

### U-S8. → on a folder or ".. Up one folder" does nothing (lead-known)

**Problem.** The machine's RIGHT opens only `Link` and `Item.route` (`SettingsMachineImpl.kt:429-433`). Folder rows and the Up row are `Item(primary = …)` (`StoragePages.kt:519, 527`). §9.3 says "OK (or →)". Share rows work, because they carry `route`.

**Fix.** Mirror `onLeft`:
- add `SettingsPageSource.onRight(route, row): Boolean` (default false) and `SettingsShellLink.rightHandler`;
- the machine asks it before its Link/Item handling;
- `StoragePages.onRight` enters `storage.folder.item.*` and returns true.

Do not change RIGHT for every primary Item: on Save to, → on "This device" must not choose it.

**Regression test.** `StoragePagesTest`: `press(folder, BoxAction.RIGHT)` changes the subtitle to the subfolder, and RIGHT on Save to's "This device" leaves the choice unchanged.

### U-S9. "Sign in again" toasts stop for good after three processes, even after a re-sign-in

**Problem.**
- `FileSignInToastCounters` counts processes per location and is never reset (`FallbackToastPolicy.kt:28-32`; no caller of `setProcesses` outside the policy).
- §5.1 says "the first 3 processes **after it starts**", that is, per episode.

**Scenario.** The NAS password changes in March. The viewer sees three toasts and signs in again. In June the password changes again: no toast, ever. Rewind silently saves on this device, and the only sign is the amber value deep in Settings.

**Fix.**
- Add `SignInToastCounters.reset(locationId)`.
- Call it when a stored location's sign-in succeeds (`StorageLocations.updateSignIn`, or the keeper's READY after AUTH_FAILED) and when the location is removed.
- `FallbackToastGate` gains `signedIn(locationId)`, defaulting to a no-op.

**Regression test.** `FallbackToastPolicyTest.aNewSignInStartsAFreshThree`: three trues, `signedIn("smb-1")`, then a new process → true.

### U-S10. The breadcrumb never shows the pages between the section and the current page

**Problem.**
- `Header` builds `Settings › <section> › <page title>` and nothing else (`SettingsScreen.kt:178-183`). This is the cause of "skips Pause and rewind when opened by route": it happens on every route, opened by route or not (screenshot 10: "Settings › Playback › Sign in to 10.0.2.2").
- M5's three-part rule fit pages one level deep. The browse is 5–7 deep, and the viewer loses where they are (Save to › Find computers › NAS › …).

**Fix.**
- Build the breadcrumb from `ui.stack`'s cached page titles: Section, then each title, then the current page.
- Elide from the middle to fit 1,792 px (`TextOverflow.MiddleEllipsis`).
- Skip `PhoneSetup` (it is full screen).
- Keep the three-part form when the stack is two deep, so M5 pages are unchanged.

**Regression test.** A Compose test that opens `ComputerSignIn` through Save to and expects "Settings › Playback › Pause and rewind › Save to › Find computers › Sign in to NAS". Elide the middle if it is long.

---

## Nice-to-have

- **U-N1. The TV says nothing after a failed phone sign-in.**
  - After `reportSignIn(done = false)` the TV returns to "Connected from …" plus "Type the user name and password on your phone…" (`LanPhoneLink.kt:186-189`, `PhoneSetupScreen.kt:116-119`). The reason only reaches the phone.
  - Fix: show the last `reportStatus` text on the TV in Warn colour under the status line.
- **U-N2. "offline" is the wrong word for sign-in and demotion.**
  - The link value reads "1 hour · This device (NAS offline)" for SIGN_IN_NEEDED and DEMOTED too (`StoragePageEnv.kt` `TimeshiftPlaces`, `WpaPages.pauseRewindLink`).
  - Fix: add `PB_PAUSE_REWIND_VALUE_SIGN_IN` = "%1$s · This device (sign in to %2$s)".
  - For DEMOTED, the Save to description says "Can't reach NAS"; use `STATUS_DEMOTED`'s text.
- **U-N3. Accessibility.**
  - Every row's `contentDescription` is its label only (`SettingsRowViews.kt:123`). TalkBack therefore misses the next-step lines, the verdict details and the amber state.
  - The progress track's description is hard-coded English "Testing, …" (`:159`), which breaks the "no hard-coded user text" rule.
  - Fix: build the description from label + lines + value, and move "Testing, %1$s" to `strings_storage.xml`.
- **U-N4. Dead strings.** `storage_phone_result_*` (`strings_storage_phone.xml:11-16`) duplicate `storage_phone_*` in `strings_storage.xml` and are never used. Delete them.
- **U-N5. A remembered USB drive that is plugged in cannot be opened or removed.** Save to skips its stored row while it is mounted (`StoragePages.kt:288`), and the mounted row is a choose-Item with no menu. Fix: give mounted drives `menu = [Remove]`, or a detail route.
- **U-N6. A typed path skips the shares page on ←.** `afterSignedIn` *replaces* `ComputerShares` with `NetworkFolder` (`:1041-1048`), so ← at the share root pops to Find, against §7.6 ("at the share root, ← pops to ComputerShares"). Fix: push `NetworkFolder` over `ComputerShares` when it came from a typed path.
- **U-N7. The progress track is not 1,228 px wide.** It sits in the label column next to a 560-px value, so it renders at about 650 px (`SettingsRowViews.kt:159`). Fix: draw it outside the Row (full width) or drop the value column on that row.
- **U-N8. Stored-folder Test page wording.**
  - On the folder already in use, a GOOD Test focuses "Use for rewind" and posts "Rewind will save to …" again. Fix: hide that row when the folder is in use.
  - "Use for rewind" re-tests when `lastTest` is SLOW (`:791-792`), where §9.2 says only "worse than SLOW". This is the record's deviation; decide it.
- **U-N9. A race on the sign-in replace.**
  - `onOpened` reads `currentRoute()` on `Dispatchers.Default` (`:1012-1013, 1054`). The push of `ComputerShares` was only *posted* to main (`:827`).
  - A fast `NeedsSignIn` (a LAN refusal in < 16 ms) then sees `FindComputers` and does not replace. The viewer lands on Shares with an error and a "Sign in" row: not a dead end, but not the §9.3 flow.
  - Fix: decide the replace inside `runOnMain`.
- **U-N10. Find keeps the multicast lock for the whole browse.** `FindComputers` stays on the path under the browse pages, so the lock and NSD run until the viewer leaves the browse (`ComputerFinderImpl.kt:107`). Fix: pause the finders when Find is not displayed, and resume them on return.

---

## Test gaps for the Musts

| Must | Gap | Add |
|---|---|---|
| §7.7 reason + next step | the typed share; LIST_FAILED / NOT_FOUND texts | `uB1_`, `uB1b_`; a text test for the new strings |
| §9.3 OK (or →) | no RIGHT test on folders | U-S8 test |
| §9.2 focus on Find "as long as no key has been pressed" | only the first arrival is tested | `uS2_` |
| §7.3 wrong password: focus it | no test of the step after it | `uS2b_` |
| §7.8 `lastTest` stored | only the new-folder path is asserted | `uS5_` |
| §5.1 sign-in toast "after it starts" | no reset test | U-S9 test |
| §9.4 next steps drawn whole | no width or overflow test | U-S6 tests |
| §7.3 domain optional | none | `uS4_` |
