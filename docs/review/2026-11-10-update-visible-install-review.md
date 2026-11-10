# Review: M5 updater visible install (`docs/design/m5-update-visible-install.md`)

Reviewer, 2026-11-09. Scope: the proposal and the code it cites (`app/.../update/`, `core/.../update/`,
`ui/.../update/UpdateCard.kt`, `GuideStateMachineImpl.kt` card handling, `ui/.../diag/DiagPages.kt`,
`AndroidManifest.xml`, `res/xml/file_paths.xml`, `m5-setup-settings.md` §3.12, the instrumented `UpdaterTest`).
No code changed, nothing installed, cleared or uninstalled.

Evidence tags: **[src30]/[src33]** AOSP PackageInstaller sources shipped in `~/Library/Android/sdk/sources/android-30|33/com/android/packageinstaller/`
(API 34 and 36 SDK sources do **not** include the installer app, so the v2 installer is not checked here);
**[emu]** read-only on the running `emulator-5554` (AOSP TV, API 30): `GooglePackageInstaller.apk` pulled and dumped with
`aapt2`, `cmd package query-activities/resolve-activity`, `dumpsys package queries`; **[code]** this repo;
**[recall]** not verified here.

## Verdict

**Approve with changes.** The diagnosis is right and path b is the only way to get the platform's "Installing…" and
"App installed: Done / Open" screens on a self-update. The sources confirm the parts that matter most: the content URI is
read **before** the confirmation, while our process is alive, so our process being killed later does not matter; the TV
success screen offers Open from `getLaunchIntentForPackage`. One Blocker: the `ACTION_VIEW` intent must be pinned to the
system installer (on this image the installer's VIEW filter resolves at priority 0, so any third-party APK handler
produces a chooser or wins as "Always"). The Shoulds are mostly state-machine holes the new states open, and two
first-run problems that the new strings would make worse.

## 1. Platform claims, checked

| Claim (note §) | Result |
|---|---|
| `InstallStart` takes `VIEW` and `INSTALL_PACKAGE` for `content:` + APK MIME (§2b) | **Confirmed** [emu manifest dump, line 52 filter]. |
| Staging copies the URI before the confirmation (§2b, §2 TOCTOU) | **Confirmed** [src30/src33]: `InstallStart` → `InstallStaging.onResume` copies `getIntent().getData()` into the installer's own file → `DeleteStagedFileOnResult` → `PackageInstallerActivity`. Our FileProvider is read while our process is in front. The commit (and so the kill of our process) happens only in `InstallInstalling`, after Install is pressed. The grant only has to last through staging. |
| The caller is identified by launching uid, so our `canRequestPackageInstalls()` pre-check is the installer's check (§2b) | **Confirmed** [src33 `InstallStart.getOriginatingUid`]: `getCallingPackage()` is null for a plain `startActivity`, so `getLaunchedFromUid` is used. `REQUEST_INSTALL_PACKAGES` must be declared (targetSdk ≥ 26) or `InstallStart` aborts silently with no UI (`mAbortInstall`). We declare it. |
| `EXTRA_NOT_UNKNOWN_SOURCE` only for privileged callers (§2b) | **Confirmed** [src33 lines 81-84]. In practice it is never read for us, because `sourceInfo` is null. |
| No `EXTRA_RETURN_RESULT`, otherwise Done/Open disappears (§2b) | **Confirmed** [src33 `InstallSuccess.onCreate`]. Open is enabled when `getLaunchIntentForPackage` resolves. That is `CATEGORY_LAUNCHER` on AOSP, so keep `LAUNCHER` on `MainActivity`. |
| Session confirmation just finishes (§1.4, §2a) | **Confirmed** [src33 `PackageInstallerActivity` 417-421]. |
| The installer screens survive our process death (§2b) | **Plausible, not shown.** They run in the installer's own process and task, and `InstallInstalling` disables Back once committed [src33 `onResume`, `onBackPressed`]. On AOSP a package replace force-stops our package's activities, not other packages' [recall]. **Device check.** |
| On TV, our activity is paused (not stopped) behind the installer (§4, review "worth a look") | **Mixed** [emu themes]: `InstallStaging`, `PackageInstallerActivity`, `InstallInstalling`, `InstallSuccess` and `InstallFailed` are dialog themes (`Theme.DeviceDefault.Light.Dialog.Alert`), so we only **pause** while staging. Then `DeleteStagedFileOnResult` on `television` is `Theme.DeviceDefault.Light.NoActionBar` (opaque) and stays under the confirmation, so on this image we are **stopped** from the confirmation onward. The existing stop→start watcher would already fire after a Cancel on API 30 TV. Fire OS and the v2 installer: unknown. |
| Android 14+ "PackageInstaller v2" (`InstallLaunch`, Kotlin) still accepts VIEW content URIs, stages first, shows Installing / Done / Open | **Unverified** (sources not in the SDK). [recall] v2 stages the URI into a session ("Preparing…") before the confirmation, and its success fragment has Open. Needs an API 34/35 TV image or a device. |
| Android 15/16 targetSdk 35 limits on this intent | None known for an exported, filter-matching activity [recall]. Matching stays safe if we use `setPackage` with action, data and type that match the filter (not a bare `setComponent` without an action). |
| Fire OS 6/7/8 installer shows Installing → App installed Done/Open for VIEW | **Unverified** [recall: Downloader-style sideloads and self-updating sideloaded apps on Fire TV show that sequence]. Fire OS 6 is Android 7.1; AOSP `InstallStaging` (content-URI support) dates from N, and `file://` is forbidden for targetSdk ≥ 24 anyway, so content is the only VIEW option on API 25. |
| Granting "Install unknown apps" kills our process on API 30 | **Supported by this repo**: `UpdaterTest.kt:163-164` ("changing the app-op kills the app process"). The note does not account for it (see S3). |

## 2. Findings

### Blocker

**B1. Pin the VIEW intent to the system installer; add the `<queries>` entry.**
Evidence [emu]: `cmd package query-activities -a VIEW -d content://…/x.apk -t application/vnd.android.package-archive`
returns `com.google.android.packageinstaller/…InstallStart` at **priority=0**, even though the manifest declares 1. `VIEW`
is a protected action, so its filter priority is clamped [recall on the mechanism; the 0 is observed]. Any third-party
app with an APK `VIEW` filter (TV file managers, "APK installer" apps, a hostile app) is therefore equal. The user gets an
"Open with" chooser on a TV, or a previous "Always" choice sends our update to that app. The session path never had this
exposure: `CONFIRM_INSTALL` goes to the platform installer. A hostile receiver would get a read grant to a public,
verified APK (low data value) and could show a fake "installed" UI (phishing value). It cannot install anything as us.
Resolution:
- Build the intent, then `pm.queryIntentActivities(intent, MATCH_DEFAULT_ONLY or MATCH_SYSTEM_ONLY)`. Keep only results
  whose `applicationInfo.flags and FLAG_SYSTEM != 0` and whose package holds `INSTALL_PACKAGES`
  (`pm.checkPermission(INSTALL_PACKAGES, pkg) == GRANTED`). Then `setPackage(thatPackage)`. Zero candidates means
  "no installer activity" (use the session). Several (rare) means take the first and log it.
- Add a `<queries><intent>` for `VIEW` + `content` + `application/vnd.android.package-archive`. The AOSP/Google installer
  is `forceQueryable` [emu: `dumpsys package queries`], so the emulator works without it. A vendor installer (Fire OS,
  OEM) may not be, and then `resolveActivity` would return null on API 30+ and **silently** keep everyone on the session
  path, so the fix would do nothing.
- Make the choice a pure function in `:core` (`InstallRoute.choose(sdk, systemInstallerFound, overrides)`) and unit-test it.

### Should

**S1. `Verifying` opens a race that today's code does not have.** During `verifyArchive` the state is still
`Downloading`, so `runCheck` (`UpdaterImpl.kt:191`), `download()` (`:127`) and the About row (`DiagPages.kt:127`
`enabled`) all treat it as busy. With a new `Verifying` state, OK on the About row goes to `check(manual = true)`
(`DiagPages.kt:434-438`, the `else` branch). `runCheck` then overwrites the state with `Checking` → `Available` while the
download coroutine carries on and later sets `ReadyToInstall` and opens the installer. Resolution: one
`UpdateState.isBusy` (Downloading, Verifying, Installing) used by all three guards. Add a JVM test for each guard.

**S2. BACK in a busy phase does not hide the card, and "Later" leaks into later phases.** `onAction` BACK →
`Pressed(LATER)` → the guide nulls `updateCard` and calls `updater.later()` (`GuideStateMachineImpl.kt:428-430`,
`UpdateActionsImpl.kt:17`). `cardFor` honours `later` only for `Available` (`UpdateCardModel.kt:20`), so the next 256 KB
progress tick, then `Verifying` and `Installing`, publish a card again, and the guide takes it as a new unfocused card
(`:396-398`). The card is back within a second. The note does not say what `NotInstalled` does with `later = true`.
Resolution: `cardFor` returns null for OFFER / DOWNLOADING / VERIFYING / OPENING_INSTALLER while `later` is set.
FAILED and NOT_INSTALLED clear `later` and show, because a reason and a next step must reach the user. A manual check
still clears it (0.1.4 behaviour kept). The tests must cover "BACK during download → no card for the rest of the
download → card on failure".

**S3. NeedsPermission on API 30 (Fire OS 8, Android 11 TVs): the new text is wrong after the grant.** Granting "Install
unknown apps" kills our process [code: `UpdaterTest.kt:163-164`]. The user returns to a cold start: `offer` is null, the
state is `Idle`, and the next automatic check is up to 24 h away (`UpdatePolicy.firstCheckDelayMs` with the
`lastCheckMs` just written). So there is no card to "press Update again" on. The owner's step 6 hits exactly this.
Resolution, without a contract change: on start, if `filesDir/update/{vc}.apk` exists with `vc > running`, schedule a
silent check at `UPDATE_FIRST_CHECK_DELAY_MS`, or right after the first frame, whatever the interval. Treat a verified
offer whose file is already there as `ReadyToInstall` without downloading again (re-run `verifyArchive` on the file).
This also covers process death during a hand-off. Keep the new `update_needs_permission` line for API 26-29 and 31+.

**S4. A dead end when the unknown-sources screen cannot open.** `runInstall` logs and returns when
`ACTION_MANAGE_UNKNOWN_APP_SOURCES` throws (`UpdaterImpl.kt:364-368`). The card would then say "Allow…, then press
Update again" forever, for example on operator-tier TVs or under `DISALLOW_INSTALL_UNKNOWN_SOURCES`. Resolution: if
`UserManager.hasUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES[_GLOBALLY])`, or the settings activity fails to start →
`Failed(INSTALL_BLOCKED)` (it already has the "verified developers / Install unknown apps" text plus the release page).
On this emulator `MANAGE_UNKNOWN_APP_SOURCES` resolves to `com.android.tv.settings/…ExternalSourcesActivity` [emu].

**S5. Define the watcher and the deadline exactly; do not lean on `ProcessLifecycleOwner` for the deadline.**
`ProcessLifecycleOwner` delays ON_PAUSE/ON_STOP by 700 ms and drops a pause→resume shorter than that [recall, androidx
`TIMEOUT_MS`]. That is fine for "returned from the installer". It is exactly the case the deadline exists for:
`InstallStart` aborting silently (`mAbortInstall`, a blocked VIEW start, a vendor no-op) bounces us in under 700 ms, so no
event arrives. Specify:
- Return trigger: the first ON_START after ON_STOP **or** ON_RESUME after ON_PAUSE (process level), after the hand-off.
  Then the versionCode check. Then `NotInstalled`.
- Deadline: starts at hand-off and runs only while **`MainActivity` itself** is resumed (activity callbacks, not process
  ones). The first activity `onPause` cancels it for good. On expiry with the old versionCode → `NotInstalled`. Use
  **15 s**, not 10 s (see Q5).
- Never published before the installer can appear: no `NotInstalled` while the activity is paused or stopped.
- Before `NotInstalled`, look for an active installer session for our package (`packageInstaller.allSessions.any {
  it.appPackageName == packageName && it.isActive }`). If one exists, keep `Installing` and re-arm. This covers "Home
  during Installing…, then reopened The Box" on a slow Stick, where a false "Not installed" would be followed by our
  process dying. Visibility of the installer's sessions to us is unverified: check on the emulator (it is read-only).

**S6. Do not hand off while in the background.** If the user presses Update, then Home, while downloading, the
verify-then-install `startActivity` runs from the background (BAL on API 29+, blocked with no exception) [recall]. Today
the session path has the same problem through `PENDING_USER_ACTION`. With b the state becomes `Installing` with nothing
on screen. Resolution: if `ProcessLifecycleOwner` is below STARTED when the file is verified, publish `ReadyToInstall`
and hand off on the next ON_START. Keep `installWhenReady`.

**S7. Skip on the returning card does not skip.** `UpdateActionsImpl.kt:19` reads the versionCode only from
`UpdateState.Available`. On `NotInstalled` (and today on `ReadyToInstall` / `NeedsPermission`, which also show the
buttons) Skip acts as Later. Resolution: use `updater.offer?.first?.versionCode`, or have `UpdateCardState` carry the
versionCode. Add a test.

**S8. The instrumented suite is written for the session path.** `UpdaterTest` waits for
`STATUS_PENDING_USER_ACTION` in `probe.installer.statusesSeen` and calls `cancelPendingInstall()` (`:176-187`).
`confirmInstallsTheUpdate` asserts `Installing` after 5 s (`:211-213`). Under b no status ever arrives, so both fail. The
note's test plan lists only JVM tests. Resolution: in the note, give the replacement I-M5-19 cases. Use UiAutomator for
Cancel on the system screen, then `NotInstalled`. Use a forced-session switch (debug override) so the fallback path stays
tested. The confirm case expects `Installing` while the activity is stopped, the installer's "Installing…" text, then
"App installed" (UiAutomator `By.pkg(installer)`).

**S9. The About row needs the new states, not only `updateValue`.** `onIntent` (`DiagPages.kt:434-438`): `NotInstalled`
→ `install()`. `updateDesc`: `NotInstalled` → `about_update_desc_ready`. `enabled`: add `Verifying` (S1). The note lists
only the values.

**S10. Fix the owner script (see §5).** As written it cannot be completed: step 6 needs an offer, but the update was
installed in step 4.

### Nit

- **N1.** The installer of record changes. Under b, `InstallInstalling` creates the session itself
  (`EXTRA_INSTALLER_PACKAGE_NAME` is not passed) [src33]. So after the first update, About → "Installer" probably shows
  the package installer instead of `app.thebox` [recall on the service default]. It is harmless (self-updates qualify
  for `USER_ACTION_NOT_REQUIRED` regardless, `PackageInstaller.java` API 34 KDoc), but write it down and check it on the
  device.
- **N2.** The stale installer task. After Home on the confirmation, the installer task stays in recents with a live
  confirmation, and a second Update stacks another one. It is harmless (same verified bytes). Mention it in §4.
- **N3.** Playback is stopped before the hand-off (`UpdaterImpl.kt:372`) and not resumed on `NotInstalled`. Consider
  re-tuning the last channel on `NotInstalled`, or at least record that it stays stopped.
- **N4.** `about_update_value_failed` ("Last check failed") is shown for install and download failures too. Fix the
  wording when touching `DiagPages`.
- **N5.** The note says "Owner report (2026-11-10)" under a 2026-11-09 status. The brief speaks of seven open
  questions, but the note has six.
- **N6.** `UpdateCardPhase.FAILED` duplicates `failed != null`. Either derive one from the other or document that
  `failed` holds the text and the phase holds the layout.
- **N7.** Stale-file rule: "older than 24 h" needs a clock that suits a TV (use the file's `lastModified` against wall
  time, and accept a skewed clock). Simpler: delete on any start where no hand-off is pending in this process, since
  the installer keeps its own staged copy [src33]. Keep 24 h only if Fire OS is shown to read the URI late.

## 3. Regression and per-capability choice (question 2)

- Where the session works today it gives one prompt and the launcher. Under b it gives up to four screens (Preparing →
  confirm → Installing → App installed). That is what the owner asked for. No functional loss on AOSP/Google TV except
  the exact status codes (Q1).
- Missing or disabled installer activity, vendor installer, restricted launchers: the confirmation of the **session**
  path is the same system installer app (`CONFIRM_INSTALL` is in `InstallStart`'s filters [emu]). If that app is
  disabled, both paths fail. A vendor installer without Done/Open is no worse than today. So a global b with a
  capability test (B1) is right; per-API selection is not needed. Keep one compile-time override (`VIEW_INSTALL_MIN_SDK`,
  default 25) so that API 25 can be moved to the session with a one-line change if the Fire OS 6 device check fails.
- `resolveActivity != null` is **not** sufficient: it is visibility-filtered on API 30+ (B1), it can return the chooser
  (`ResolverActivity`), and it says nothing about the target being the system installer. Use the B1 query.

## 4. Status without a callback (question 3)

With S5 and S6 applied, every exit reaches a state with a reason and a next step:

| Exit | Signal | Card |
|---|---|---|
| Installed (Open or Done) | Process dies; next start sees versionCode ≥ target | "Updated to X" (unchanged, `checkUpdatedNotice`) |
| Cancel / Back on Preparing or the confirmation | Return trigger, old versionCode, no active session | NotInstalled |
| Installer error (signer clash, space, parse) | Return, old versionCode, no active session | NotInstalled + release page (installer showed the reason) |
| Silent abort / blocked start | Deadline (activity resumed 15 s, never paused) | NotInstalled |
| Home on the installer, back later | Return trigger | NotInstalled (or Installing if a session is active, S5) |
| Our process killed while handed off | Cold start: file present, vc > running | Silent check, then ReadyToInstall (S3) |

False "Not installed" risks that remain: an installer that takes over 15 s to appear (cosmetic; any later answer
reconciles). The installer finishing the commit after we resumed is covered by S5's session look-up. A stuck state is
not possible with the deadline in place. A user who sits on the unknown-sources settings screen does not matter: that
is `NeedsPermission`, not `Installing`, and no watcher is armed.

After the update, on the new version: `checkUpdatedNotice` posts the notice, clears `lastInstalledNotice` and empties
`filesDir/update` (`UpdaterImpl.kt:427-438`). The installer deletes its own staged copy (`DeleteStagedFileOnResult.
onDestroy` [src33]). Nothing is left.

## 5. Security (question 4)

- **Equal to the session path** once B1 is in. Every byte check still runs before the hand-off. The platform still
  refuses a different signer.
- **TOCTOU:** `filesDir/update` is private. The FileProvider is not exported, covers `update/` only (`file_paths.xml`)
  and grants read-only. The installer reads the file once, at staging, before the user is asked [src33]. The only writer
  is `runDownload`, which must stay excluded by the S1 busy guard (today `Installing` only; add `NotInstalled` → download
  only through a new check, which re-verifies).
- **Grant lifetime:** the grant is tied to the installer's activity, and staging finishes in the first screen. Revoking
  it on our package replace does not matter.
- **Interception:** fixed by B1. With `setPackage` to a system app holding `INSTALL_PACKAGES`, a hostile app registered
  for the APK MIME type never sees the intent. Never fall back to an un-pinned implicit VIEW.

## 6. Card and contract (question 5)

- `Verifying(versionName)` and `NotInstalled(versionName)` plus `UpdateCardState.phase` (with a default) is the smallest
  readable change. A resolved status string would hide the busy/no-buttons rule in `:ui`. Both `when`s over
  `UpdateState` (`UpdateCardModel.cardFor`, `DiagPages.updateValue`) are exhaustive, so the compiler finds them.
  `FakeUpdater` is unaffected (the interface does not change; tests set `state`). The `GuideStateMachineImplTest` cases
  in the note are right; add S2's "stays hidden" case.
- Busy keys: arrows `NotMine` (the grid moves), BACK = hide (S2). SELECT should be `NotMine` too (the note says "other
  keys"). An OK in a busy phase then tunes the focused channel, which `runInstall` stops again. Acceptable; write it down.
- `NotInstalled` returning with focus on Update: the guide keeps `focused` from the existing card (`:397`). Make
  `cardFor` force `focused = 0` on the transition into NOT_INSTALLED, and the guide machine must not copy the old focus
  over it. Test it.
- 0.1.4 consistency: with S2 a manual check (About) still clears `later` and brings the card back. Consistent.

## 7. Rulings recommended on the open questions

1. **Session as fallback: yes.** The installer shows its own failure text, which is better than our generic one. We lose
   only the exact `INSTALL_BLOCKED` mapping, which matters for developer verification (Q2). Keep the mapping for the
   session fallback.
2. **Developer verification:** the same check runs at commit on both paths. Under b the user sees the platform's
   explanation (and any "advanced flow"), which is the better outcome. F15 puts TV outside the 2026 enforcement. Do not
   block on it. Add an API 35/36 TV image run to F14/F15 when one is available.
3. **Fire OS 6 content URI:** keep b on API 25 (AOSP N has `InstallStaging`, and `file://` is not an option). If the
   device check fails, flip `VIEW_INSTALL_MIN_SDK` to 26 (API 25 → session, no Open). No runtime auto-detection.
4. **Notes line in "Updated to": no.** It is not asked for, and it costs a `UpdatePrefs` field.
5. **Deadline: 15 s, measured only while `MainActivity` is resumed and never paused since the hand-off** (S5). Staging
   on a slow Stick happens with us paused, so it does not count against the deadline.
6. **No "Cancel download" button.** BACK hides (S2), and Cancel on the system screen undoes it. A 10 MB download is
   seconds; the About row shows progress for long ones.

## 8. Test plan and owner script (question 7)

JVM additions: `InstallRoute.choose` (B1); the busy guard for each entry point (S1); the S2 hide matrix; Skip on
NotInstalled (S7); the cold-start resume rule (S3, as a pure `UpdatePolicy` function); the S5 deadline as a pure clock
function (armed / paused / expired); `cardFor` focus on NOT_INSTALLED. Instrumented: S8.

Owner script, corrected (TV on v0.1.4; v0.1.5 **and later v0.1.6** published, or two TVs):
1. Note the TV model and OS (Settings → About of the TV), and whether The Box already has "Install unknown apps".
2. Settings → About → Check for updates. Go back to the guide. The card shows; press Update. Expect "Downloading N %",
   "Checking the download…", "Opening the installer…". (Do not wait for the automatic check: it runs at most once a day.)
3. On the system screen press Cancel. Expect the card "Not installed. Press Update to try again…" with the buttons, and
   Update focused.
4. Press Update, then press **Home** on the system screen. Reopen The Box from the launcher. Expect the same "Not
   installed" card within a second.
5. Press Update, then press BACK while it downloads. The card must stay hidden; the system screen still appears. Confirm
   Install. Expect the system "Installing…" bar, then "App installed" with Done and Open. Note which button has focus.
6. Press Open. Expect The Box to start and show "Updated to 0.1.5". Settings → About shows 0.1.5. Note what the
   "Installer" line says.
7. With v0.1.6 (or the second TV): first turn **off** "Install unknown apps" for The Box. Check for updates, then press
   Update. Expect the settings page. Turn it on and go back. On Android 11 / Fire OS 8 The Box may restart: say whether
   the card came back by itself and what it said. Then Update → Install → **Done**. Expect the home screen; opening The
   Box shows "Updated to 0.1.6".
8. Report anything that differs, with a photo of the system screen.

It is safe: nothing is uninstalled or cleared, and the toggle is restored in step 7.

## 9. Still unverified without a real device

- The Done/Open screen after a **self**-update on Google TV (API 31-35, and the v2 installer on 34+) and on Fire OS 6/7/8.
  That Open launches the new version. Where Done lands. Default focus.
- Fire OS: content-URI support on API 25, the installer's themes (pause or stop), its package name, whether it is
  visible without `<queries>`, and whether it reads the URI before the confirmation.
- Whether our process sees the installer's session (`getAllSessions`) on API 30+ (S5). This can be checked on the
  emulator.
- The process kill on the unknown-sources grant on API 31+ and on Fire OS 8 (seen on the API 30 emulator by the suite).
- The installer of record after a VIEW install (N1).
- Developer-verification UI on either path (no enforcing TV image).
