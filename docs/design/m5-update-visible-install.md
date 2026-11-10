# M5 updater: visible progress, a clear result, and "Open" (proposal)

Status: APPROVED WITH CHANGES and implemented, 2026-11-10 (proposal 2026-11-09; review
`docs/review/2026-11-10-update-visible-install-review.md`; dispositions in "Lead sign-off (2026-11-10)" at the end,
which wins over the sections above where they differ). Amends §3.12.3 and §3.12.4 of `docs/design/m5-setup-settings.md`.

Owner report (2026-11-09): "the updater ... ask to update but doesn't show the progress and just goes back to the home
screen; shouldn't it show the progress and when done say app updated and the option to open the app".

Evidence tags: **[code]** read in this repo; **[emu]** checked on the running `sdk_google_atv_x86` emulator (API 30,
installer `com.google.android.packageinstaller`, pulled and read with `aapt2`/`dexdump`, nothing installed or cleared);
**[recall]** my memory of AOSP or vendor behaviour, not verified here.

## 1. Why no progress was seen

Trace [code]:
- `UpdaterImpl.runDownload` publishes `Downloading(v, 0, total)` (`UpdaterImpl.kt:290`) and then every 256 KB
  (`:305-308`, `PROGRESS_STEP_BYTES` `:460`). `card` maps it through `UpdateCardModel.cardFor` (`UpdaterImpl.kt:105-110`,
  `UpdateCardModel.kt:21-24`); the guide keeps the card after Update (`GuideStateMachineImpl.kt:428-431` clears it only
  for Later/Skip) and redraws it on every `GuideEvent.Update` (`:394-399`, `publish()` → `refreshUpdate()` `:332, :415`).
  `UpdateCard` draws the bar and "Downloading N %" (`UpdateCard.kt:62-70`). So the card **is** updated during a download.
  Nothing hides it, and the 3 s focus timer only affects keys.
- Why it was still not seen:
  1. **It is short.** The release APK is about 10 MB (`app-arm64-v8a-release.apk` 10,373,097 bytes). On a normal home
     line, that takes 1 to 3 s.
  2. **A second press skips the download.** After the user backs out of the confirmation, the state is `ReadyToInstall`
     (`UpdaterImpl.kt:391, :413`). `updateNow()` then installs straight away (`:151-152`), so no progress is shown at all.
     I think this is the owner's likeliest path (it fits e347ea6, "install an update that was put off with Back").
  3. **No status text while checking or installing.** While the archive is checked (`:323-339`), the card still shows
     the last published value, e.g. "Downloading 97 %": the final bytes are never published. `ReadyToInstall`
     and `Installing` both show `progress = 1f` (`UpdateCardModel.kt:26-27`), which draws "Opening the installer…"
     (`strings_diag.xml:189`). This text stays during the session copy too. After a decline it is **still** shown, with no
     reason and no next step, which breaks the project rule.
  4. **After the user confirms, nothing is shown at all (the real complaint).** For a session install, the platform's
     confirmation activity only calls `setPermissionsResult(id, true)` and `finish()`. It never opens its
     "Installing…" or "App installed" screens. [emu: `PackageInstallerActivity.lambda$bindUi$0`: `mSessionId != -1` →
     `setPermissionsResult` + `finish`; `startInstall()` → `InstallInstalling` is used only without a session.] The
     install then replaces the package and kills our process [recall], so the user lands on the launcher.
  5. `NeedsPermission` shows only the notes (`UpdateCardModel.kt:25`). The string `update_needs_permission`
     (`strings_diag.xml:201`) is never used, so after "Install unknown apps" the card gives no hint to press Update again.

Minimal fix: give the card an explicit phase, so that every moment has a status line under "Version X":
`Downloading 42 %` → `Checking the download…` → `Opening the installer…`, then the system screens take over (§2). After
a return without the update: `Not installed. Press Update to try again.` Busy phases show no buttons (§5).

## 2. The install hand-off

**a. PackageInstaller session (today).** [emu] confirmation → finish. The platform shows no progress and no result
screen. The process is killed on success [recall]; the result broadcast goes to a process that is gone. On the
self-update path, Google TV (API 31-35) and Fire OS behave the same way as far as I know [recall]. **This is why the
user lands on the launcher.**

**b. The system installer activity with our content URI.** `ACTION_VIEW`, `content://${applicationId}.files/update/…`,
type `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`. This is
already the shipped fallback (`InstallResultReceiver.kt:123-134`).
- [emu] `InstallStart` takes both `VIEW` and `INSTALL_PACKAGE` for `content:` + that MIME type (same intent filter). The
  flow is `InstallStaging` (copies the URI into the installer's own file) → `PackageInstallerActivity` (confirm) →
  `InstallInstalling` (the platform's "Installing…" progress) → `InstallSuccess`. `InstallSuccess.onCreate` returns a
  result and finishes **only if** `EXTRA_RETURN_RESULT` is true. Otherwise it shows the "App installed" dialog with
  Done and Open. Open is built from `getLaunchIntentForPackage` (the `CATEGORY_LAUNCHER` intent). The installer has
  `television`-qualified styles, so this is the TV variant.
- Open will appear: `MainActivity` declares both `LAUNCHER` and `LEANBACK_LAUNCHER` (`AndroidManifest.xml:76-77`).
  [emu] `cmd package resolve-activity` resolves `app.thebox.debug/.MainActivity` for both categories. Keep `LAUNCHER`:
  dropping it would hide Open on AOSP.
- The installer runs in its own process and task (`NEW_TASK`, installer affinity). Our process being killed does not
  close its screens [recall; the usual Downloader experience on Android TV and Fire TV]. This must be confirmed on a
  device (§5).
- `ACTION_INSTALL_PACKAGE` has been deprecated since API 29, but it still works on 35 [recall]. `ACTION_VIEW` is not
  deprecated and is what Fire OS sideloaders use [recall], so use `ACTION_VIEW`. Do **not** pass
  `EXTRA_RETURN_RESULT`, because it removes the Done/Open screen [emu]. Do not pass `EXTRA_NOT_UNKNOWN_SOURCE`: it is
  honoured only for privileged callers [emu: read in `InstallStart.onCreate`; the privilege rule is recall].
- Permission: the installer checks the caller's `REQUEST_INSTALL_PACKAGES` and the "install unknown apps" app-op
  (API 26+) by launching uid [recall]. We keep our own `canRequestPackageInstalls()` pre-check (`UpdaterImpl.kt:354,
  :362-370`), so the installer's "not allowed" screen should not appear. API 25 (Fire OS 6) uses the global "Unknown
  sources" setting; the installer shows its own block dialog there [recall].
- Fire OS 7/8: Amazon's installer is AOSP-based. With `ACTION_VIEW` it shows "Installing…" and then "App installed" with
  Done / Open [recall]. Fire OS 6 is the same [recall, least sure].
- Result: with `NEW_TASK`, `startActivityForResult` returns `RESULT_CANCELED` at once [recall], and without
  `EXTRA_RETURN_RESULT` there is nothing useful to return anyway. **We get no result.** We learn the outcome from (i) the
  process dying and the next start finding the new versionCode, or (ii) our app coming back to the front with the
  old one (§4).

**c. Keep the session and reopen ourselves.** A manifest receiver for `ACTION_MY_PACKAGE_REPLACED` that starts
`MainActivity` [recall]. It works on API 25 and 28 (Fire OS 6/7), which have no background-activity-start (BAL)
limits. From API 29 (Fire OS 8 = API 30, Google TV) BAL blocks it: a broadcast receiver is not an exemption.
Exemptions are a visible window, a recent foreground start, the `SYSTEM_ALERT_WINDOW` grant, a notification's
PendingIntent or companion-device roles. None of them applies here without asking the user for a special permission.
**Blocked or not acceptable**: full-screen-intent notifications (restricted on 34; TV hardly shows notifications); an
AlarmManager activity PendingIntent (subject to BAL, needs the sender's opt-in on 34+, an abuse pattern); an activity
PendingIntent as the session status receiver (sent by system_server, BAL on 34+, untested). Combined with b on Fire
OS 6/7, c would also open our app over the installer's own Open screen.

**d. Combinations.** b as the primary path on every API, with a as the fallback only when no activity takes the intent
(`ActivityNotFoundException`, or `resolveActivity` is null). That is the reverse of today's order. No c.

**Security stays the same.** All our checks (signed manifest, sha256 while streaming, size, package, versionCode,
signer equal to the installed signer, `UpdaterImpl.kt:323-339`) still run before any hand-off.
- **TOCTOU.** With a session we copy the bytes ourselves. With b, the installer reads the file later, through the URI.
  `filesDir/update` is private to our uid (root aside). The URI grant is read-only, and the FileProvider is not
  exported and covers `update/` only (`file_paths.xml`, `AndroidManifest.xml:95-96`). So only our own process could
  change the file between our check and the installer's read. Our process changes it only in `runDownload`, which
  cannot start while the state is `Installing` (`UpdaterImpl.kt:127, :191`), and in `checkUpdatedNotice`, which runs
  only once the new version is installed.
- **Backstop.** The platform refuses an update with a different signer (all APIs). That covers API 25-27, where our
  cert check is skipped (S7).
- **One addition.** Do not delete `update/` while a hand-off of this process is pending. The staging copy happens
  before the confirmation [emu], but Fire OS may differ.

## 3. What the user sees at the end

- **Google TV / Android TV (API 28-35), path b:** our card shows "Opening the installer…". Then the system screens
  follow: "Preparing…" (staging), "Do you want to install an update to this app?" Install / Cancel, then "Installing…"
  with a bar, then "App installed" with Done / Open. **Open** starts the new version. **Done** returns to the launcher:
  our task was removed when the package was replaced [recall].
- **Fire OS 6/7/8:** the same sequence in Amazon's styling [recall, device check needed].
- **Fallback a (no installer activity):** as today, the user lands on the launcher after confirming. The notice
  appears on the next start.
- **Next start:** keep `updatedNotice` ("Updated to 1.0.5", `UpdaterImpl.kt:427-438`) as it is. It is set before the
  hand-off (`:374`) and works for both paths. Optional, if the reviewer wants it: "Updated to 1.0.5 · <first notes
  line>". That needs the notes line stored in `UpdatePrefs`, which is a contract change, so I leave it out by default.

## 4. Failure and cancel paths (path b)

| Case | What happens | What the card shows |
|---|---|---|
| Cancel or Back on the confirmation | The installer finishes; our app comes back to the front | `Not installed. Press Update to try again.` (Update/Later/Skip) |
| Installer error ("App not installed", for example a signer clash or no space) | The installer shows its own failure screen; Done → back to us | Same line, plus "If it fails again, install it from the release page." |
| "Install unknown apps" off (API 26+) | Our pre-check → `NeedsPermission` → system settings | `Allow The Box to install updates, then press Update again.` (the unused string, now used) |
| Fire OS 6 unknown sources off | The installer's own block dialog → back to us | Not-installed line + `update_fire_os_unknown_sources` (API < 26) |
| No installer activity, then the session fails too | `InstallStatus.Failed` | Existing `update_failed_install_plain` |
| Home pressed on the installer, app reopened later | Watcher fires on return | Not-installed line; the file is kept |
| Process killed mid-download | `.part` is deleted on the next download (`UpdaterImpl.kt:286`) | Card again after the next check |
| Process killed after the hand-off | The installer carries on alone; success → notice on the next start | n/a |
| Stale file (install never happened) | The next download deletes or replaces it (`:286, :327`). On a start where `lastInstalledNotice` > running and the file is older than 24 h: delete it and clear the flag | n/a |

- (review N2) Home on the confirmation leaves the installer task in recents with its confirmation live; a second
  Update stacks another one. Harmless: both read the same verified bytes.
- (review N1) After the first update through the installer activity, About → "Installer" probably names the package
  installer instead of the previous installer of record (the installer creates the session itself). Harmless; check it
  on the device (owner script step 6).

**No stuck "Installing…" (A3 guarantee).** The watcher (`UpdaterImpl.kt:406-416`) stays armed for b, plus two
additions:
- It also fires on `ON_RESUME` after `ON_PAUSE` (`ProcessLifecycleOwner`). That covers an installer dialog that only
  pauses us (review "worth a look", correctness-player-ui-app.md:199).
- A safety deadline: while `Installing`, if the app is resumed for 10 s with the old versionCode installed, the state
  becomes "not installed".

On every return the watcher compares the installed versionCode with the target (`inspector.installedVersionCode()`).
If it is equal or higher, the update has landed and our process simply was not killed: show the notice.

## 5. Recommendation

**Use b (the system installer activity, `ACTION_VIEW` + FileProvider URI, no `EXTRA_RETURN_RESULT`) as the primary
path on every platform. Keep the session (a) as the fallback when no activity takes the intent. Do not do c.** Why:
b is the only path in which the *platform* draws "Installing…" and "App installed: Done / Open". Those screens outlive
our process, so neither BAL nor process death can break them, and on AOSP they are verified to exist and to find our
launch intent. It is also the path already shipped as the Fire OS fallback, so the code risk is small. The cost is
that we get no result callback. The return watcher and the versionCode check cover that.

Changes by file:
- `core/.../update/UpdateContracts.kt` (contract, lead sign-off): add `UpdateState.Verifying(versionName)` and
  `UpdateState.NotInstalled(versionName)` (a return without the update); add `UpdateCardState.phase: UpdateCardPhase`
  (`OFFER, DOWNLOADING, VERIFYING, OPENING_INSTALLER, NEEDS_PERMISSION, NOT_INSTALLED, FAILED`), default `OFFER`.
- `core/.../update/UpdateCardModel.kt`:
  - `cardFor` maps each state to a phase. `Installing` → `OPENING_INSTALLER`, `ReadyToInstall` → `OPENING_INSTALLER`.
  - `onAction`: in DOWNLOADING / VERIFYING / OPENING_INSTALLER, BACK hides the card (the work goes on) and other keys
    are `NotMine`.
- `core/.../update/UpdatePolicy.kt`: pure `InstallOutcome.onReturn(installedVc, targetVc)` → `LANDED | NOT_INSTALLED`.
- `app/.../update/UpdaterImpl.kt`:
  - Publish the final `Downloading(total, total)`, then `Verifying` before `verifyArchive` (`:323`).
  - `runInstall` keeps the order (permission, `stop`, notice flag, `Installing`).
  - Map `InstallStatus.Cancelled` / watcher return to `NotInstalled` (not `ReadyToInstall`); `updateNow()` and the
    About row treat `NotInstalled` like `ReadyToInstall`.
  - Add the resume watcher and the 10 s deadline.
  - Stale-file rule in `checkUpdatedNotice`.
- `app/.../update/InstallResultReceiver.kt`: `install()` tries `systemInstaller(apk)` first (`ACTION_VIEW` as in
  `fallback`; status renamed `SystemInstallerStarted`); on `ActivityNotFoundException` → `commitSession`; KDoc.
- `ui/.../update/UpdateCard.kt`: draw the status line by phase; no buttons in the busy phases; keep the bar for
  DOWNLOADING only.
- `ui/.../diag/DiagPages.kt:182-193`: values for `Verifying` ("Checking the download…") and `NotInstalled` ("%1$s not
  installed · OK to try again"); OK on `NotInstalled` installs.
- `docs/design/m5-setup-settings.md` §3.12.3/§3.12.4: record the ruling; §9 F14 adds the device checks below.

New strings (`ui/src/main/res/values/strings_diag.xml`):
- `update_card_verifying` "Checking the download…"
- `update_card_opening` "Opening the installer…" (rename of `update_card_ready`)
- `update_card_not_installed` "Not installed. Press Update to try again, or install it from the release page."
- `about_update_value_verifying` "Checking the download…"
- `about_update_value_not_installed` "%1$s not installed · OK to try again"
- `update_needs_permission` (existing, now drawn by the NEEDS_PERMISSION phase)

Unit tests (JVM):
- `UpdateCardModelTest` (new): a phase for each state; percent at 0 and at the total; `Verifying` keeps version and
  notes; `NotInstalled` brings the buttons back, focus on Update; busy phases: BACK → LATER, arrows → `NotMine`;
  `Failed` after `NotInstalled` keeps the reason.
- `UpdatePolicyTest`: `InstallOutcome.onReturn` for equal, higher and lower versionCodes.
- `UnansweredConfirmationWatcherTest`: the pause/resume arming; no fire on the synchronous catch-up; one fire only.
- `GuideStateMachineImplTest`: the card stays through DOWNLOADING → VERIFYING → OPENING_INSTALLER after Update; BACK
  in a busy phase hides it.

Only a real device can show (the emulator was not updated here; Fire OS cannot be emulated): the Done/Open screen after
a **self**-update on Google TV and Fire OS; that Open starts the new version; where Done goes; whether the confirmation
pauses or stops our activity; the default focus on the success screen; Fire OS 6/7 content-URI support.

Owner test script (review S10, corrected; TV on v0.1.4, v0.1.5 **and later v0.1.6** published, or two TVs):
1. Note the TV model and OS (Settings → About of the TV), and whether The Box already has "Install unknown apps".
2. Settings → About → Check for updates. Go back to the guide. The card shows; press Update. Expect "Downloading N %",
   "Checking the download…", "Opening the installer…". (Do not wait for the automatic check: it runs at most once a day.)
3. On the system screen press Cancel. Expect the card "Not installed. Press Update to try again." with the buttons, and
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

## 6. Open questions for the reviewer (answered in the lead sign-off below)

1. Is moving the session to fallback acceptable? It loses the exact status codes (`INSTALL_BLOCKED` vs
   `INSTALL_FAILED` with the platform message); the installer shows its own failure text instead.
2. The Android 15/16 developer-verification gate: does the installer-activity path show a different block screen
   from the session path? Verify on an API 35 Google TV image if one is available.
3. Fire OS 6 (API 25) with a content URI: confirm on a device. If it fails, should API 25 use the session (no Open)?
4. Should the "Updated to" notice carry the first notes line (needs a `UpdatePrefs` field)?
5. Are 10 s for the resumed-with-old-version deadline too short on a slow Stick, where the installer's staging can be
   in front of us?
6. Should busy phases offer a "Cancel download" button, or is BACK-to-hide enough?

## Lead sign-off (2026-11-10)

Verdict: the review's "approve with changes" is adopted. The Blocker and every Should are implemented; the open
questions are ruled as the reviewer recommends. This section wins over §1-§6 where they differ.

| Item | Disposition | Where |
|---|---|---|
| B1 pin the VIEW intent | **Done.** `queryIntentActivities(MATCH_DEFAULT_ONLY or MATCH_SYSTEM_ONLY)`, keep system apps holding `INSTALL_PACKAGES`, `setPackage` on the first (several are logged). The pure choice is `InstallRoute.pinnedInstaller` / `InstallRoute.choose` in `:core`. Manifest `<queries>` entry for `VIEW` + `content` + the APK MIME type. No pinned installer → the session (the 0.1.4 flow). Never an un-pinned VIEW. | `InstallHandOff.kt`, `InstallResultReceiver.kt`, `AndroidManifest.xml`; `InstallHandOffTest` |
| S1 one busy rule | **Done.** `UpdateState.isBusy` (Downloading, Verifying, Installing) guards `runCheck`, `download()` and the About row's `enabled`. | `UpdatePolicy.kt`; `UpdatePolicyTest.busyIs…`, `DiagPagesTest` |
| S2 Later / BACK hides busy phases | **Done.** `UpdateCardModel.hiddenBy`: Later hides every phase but FAILED and NOT_INSTALLED, which clear Later (`fail`, `notInstalled`). The updater folds the **unfiltered** card as `previous`, so a card hidden during the download still has its version on a failure. A manual check still clears Later (not while busy). | `UpdateCardModel.kt`, `UpdaterImpl.card`; `UpdateCardModelTest` |
| S3 restart with a downloaded file | **Done, one deviation.** On start, `UpdatePolicy.startupFiles` keeps the newest `{vc}.apk` newer than the running version and deletes everything else; a silent check (`runCheck(resume = true)`, any cadence, once per process) runs 2 s after the first frame. **Deviation:** the result is `Available` (the offer card with buttons), not `ReadyToInstall`: `ReadyToInstall` is the OPENING_INSTALLER phase (no buttons), so a card in it could not take "press Update again". Update then reuses the file **after the full check again** (hash and size of the whole file, package, versionCode, signer); nothing is downloaded. Pressing Update on a version skipped earlier un-skips it, so the restart check does not drop it. | `UpdaterImpl.resumeAfterRestart`, `runDownload`; `UpdatePolicyTest.startKeeps…`, `.aWaitingDownload…` |
| S4 dead end on the settings screen | **Done.** `DISALLOW_INSTALL_UNKNOWN_SOURCES` (and `_GLOBALLY` on API 29+), or a settings screen that cannot start → `Failed(INSTALL_BLOCKED)` (the existing "blocked" text, Fire OS text below API 26). | `UpdaterImpl.runInstall` |
| S5 watcher and deadline | **Done, one addition.** Pure, clock-driven `HandOffWatcher`: return = process ON_START after ON_STOP or ON_RESUME after ON_PAUSE, after the hand-off, acted on only while `MainActivity` is resumed; deadline 15 s from the hand-off while `MainActivity` itself is resumed (activity callbacks), cancelled by an activity pause; one check per arm. Before "Not installed": the versionCode (landed → notice), then `getAllSessions` for another installer's session for our package (`isActive`, or `isCommitted` on API 29+; our own pending session excluded) → wait again. **Addition:** a resume after a pause that the process level never reported (a bounce under 700 ms, e.g. `InstallStart` aborting silently) restarts a fresh 15 s deadline; under the strict rule that case had no deadline left and would stay "Installing". Waiting again is bounded (3 times) so a stale session cannot keep "Installing" up. | `InstallHandOff.kt`, `UpdaterImpl.observeLifecycle`/`onHandOffCheck`; `InstallHandOffTest` |
| S6 no hand-off from the background | **Done.** Below STARTED the verified file stays `ReadyToInstall` and the next process ON_START hands it off (re-checking the file); `installWhenReady` kept. Also covers the unknown-sources settings screen. | `UpdaterImpl.runInstall` |
| S7 Skip on the returning card | **Done.** Skip reads `offeredVersionCode()` (the offer), else acts as Later; `skip()` also clears a non-busy state and the downloaded file. | `UpdateActionsImpl.kt`; `UpdateActionsSkipTest` |
| S8 instrumented suite | **Done (compiled, not run).** `UpdaterTest`: `systemInstallerOpensAndCancelIsNotInstalled` (UiAutomator Cancel on `By.pkg(installer)`), `forcedSessionReachesPendingUserActionAndBackIsNotInstalled` (the debug switch), `confirmInstallsTheUpdate` (Installing while the installer is in front, "Installing…", then Done/Open as far as the process lives). Debug switch: `InstallResultReceiver.forceSession`, `thebox-debug://guide?update_route=session|auto`. All cases launch the activity first (S6). | `UpdaterTest.kt`, `GuideDebugReceiver.kt` |
| S9 About row | **Done.** `onIntent` NotInstalled → `install()`; `updateDesc` NotInstalled → "Press OK to install version X."; `updateValue` Verifying / NotInstalled; `enabled` via S1. | `DiagPages.kt`; `DiagPagesTest` |
| S10 owner script | **Done** (§5, the reviewer's 8 steps). | §5 |
| N1 installer of record | Taken (one line in §4); device check in owner step 6. | §4 |
| N2 stale installer task | Taken (one line in §4). | §4 |
| N3 playback after Not installed | Recorded, not changed: playback stopped for the hand-off stays stopped; the user picks a channel. Re-tuning is not a one-liner. | `UpdaterImpl.notInstalled` comment |
| N4 "Last check failed" for install failures | Taken: download/install failures show "The update failed · OK to try again". | `strings_diag.xml`, `DiagPages.updateValue` |
| N5 dates and question count | Taken (owner report dated 2026-11-09; the brief's "seven" questions are the six in §6 plus the test plan). | header |
| N6 FAILED vs `failed` | Taken as documentation: `failed` holds the text, the phase the layout. | `UpdateContracts.kt` KDoc |
| N7 stale-file rule | Superseded by S3: no 24 h clock; a start deletes every file that is not an APK newer than the running version. | `UpdatePolicy.startupFiles` |

Rulings on §6: (1) the session becomes the fallback, keeping its `INSTALL_BLOCKED` / `INSTALL_FAILED` mapping. (2) No
block on developer verification; an API 35/36 TV image run joins F14/F15 when one exists. (3) Fire OS 6 stays on the
installer activity; `InstallRoute.VIEW_INSTALL_MIN_SDK` (25) is the one-line switch to 26 if the device check fails.
(4) No notes line in "Updated to". (5) 15 s, counted only while `MainActivity` is resumed. (6) No "Cancel download"
button. Also: no `EXTRA_RETURN_RESULT`, no self-reopen receiver, `LAUNCHER` stays on `MainActivity`.

Card wording under "Version X": "Downloading N %" (the final 100 % is published before checking), "Checking the
download…", "Opening the installer…", "Allow The Box to install updates, then press Update again." (`update_needs_permission`),
"Not installed. Press Update to try again." (plus `update_fire_os_unknown_sources` below API 26). Busy phases show no
buttons.

**Emulator check (2026-11-10, `sdk_google_atv_x86` API 30, debug build, fake panel offering the same build as
versionCode 7; nothing uninstalled or cleared).** The card showed "Downloading N %" with the bar and no buttons, then
"Opening the installer…"; the pinned `com.google.android.packageinstaller` opened with no chooser ("Do you want to
install an update to this existing application?" Cancel / Install). Cancel → the card "Not installed. Press Update to
try again." with Update focused (the watcher fired on the return; `getAllSessions` showed no session for us at that
point). Update again → "already downloaded and verified again", no download → Install → `InstallInstalling` (the
installer's session is listed under `dumpsys package` "Active install sessions" while it runs) → "App installed." with
DONE / **OPEN focused**; our process was gone. OPEN started the new version, which showed "Updated to …"; `files/update`
was empty. Installer of record afterwards: `com.google.android.packageinstaller` (N1 confirmed). This run also found a
bug before it shipped: `Sha256Counter.hex()` was called twice on the fresh-download path and the second call hashed
nothing, so every download was refused as damaged; `hex()` is now idempotent (`UpdatePolicyTest.sha256HexIsIdempotent`)
and the digest is computed once. Not exercised here: the forced-session path, Home on the confirmation, the restart
reuse (S3), Google TV 31-35, Fire OS.

### Code review (2026-11-10)

An independent code review of the implementation found one Blocker and six Shoulds; all are fixed.

| Item | Disposition | Where |
|---|---|---|
| B1 self-correcting route | **Done.** `UpdatePrefs.installerRouteVersionCode` + `installerRouteMisses` (defaults null / 0, so older stored documents still read). A hand-off through the installer activity that ends in "Not installed" records a miss for that versionCode (`InstallRoute.recordMiss`); at `MISSES_BEFORE_SWITCH` (2) the next Update of that versionCode takes the session (`InstallRoute.choose(installerRouteFailed = true)`). Cleared on a start that runs that version or newer (`clearedOnStart`). **Cancel choice:** the watcher reports what fired. A deadline (the installer never came to the front: a silent abort, a blocked start, a bounce under 700 ms) is `NEVER_APPEARED` and switches at once. A return (we were left for the installer and came back) is `LEFT_INSTALLER` and counts one, so a first honest Cancel keeps the installer route and Update → Install still ends on Done / Open; a second return without the update for the same version switches, because an installer that fails with its own error screen (Fire OS 6 refusing the content URI, an unverified vendor or Android 14+ installer) looks exactly like a Cancel from here. A miss after an installer session for us was seen counts as `LEFT_INSTALLER`. The card text does not change. | `InstallHandOff.kt`, `UpdateContracts.kt`, `UpdaterImpl.onHandOffCheck`; `InstallHandOffTest.routeMemory…` |
| S1 session route abandons a confirmed install | **Done, with the review's second option.** Acting only on ON_START after ON_STOP was rejected: this repo's instrumented test records that BACK on the session confirmation sends no status, so with a dialog confirmation (a pause only) that rule would leave "Installing" with nothing to end it. Instead, once `PENDING_USER_ACTION` started the confirmation, the watcher runs without a deadline; on a return the updater never abandons its own session at once: it waits again (a fresh 15 s while resumed, at most `MAX_WAITS` = 3) and re-reads the versionCode each time. An approved install replaces the package and ends the process well within that; only after it is the session abandoned and "Not installed" shown. Cost: on the fallback route, BACK / Home on the confirmation shows "Opening the installer…" for up to 45 s of resumed time before "Not installed". Instrumented `forcedSessionInstallIsNotAbandoned` (compiled, not run). | `UpdaterImpl.onHandOffCheck`, `HandOffWatcher.arm(withDeadline)`; `InstallHandOffTest.sessionRoute…` |
| S2 fields after `init` | **Done.** Every field `init` or the lifecycle observers read is declared before `init`; `observeLifecycle` starts from the process state, so nothing depends on `Main.immediate`. | `UpdaterImpl` |
| S3 NeedsPermission hidden after Later | **Done.** `hiddenBy` never hides NEEDS_PERMISSION; the permission branch clears Later. | `UpdateCardModel.hiddenBy`, `UpdaterImpl.handOff`; `UpdateCardModelTest.needsPermissionShowsEvenAfterLater` |
| S4 waiting file checks for ever | **Done.** The restart check records its outcome; the file is kept only while that version is offered or the server could not be reached (`UpdatePolicy.keepsWaitingFile`), otherwise deleted. The one restart check still ignores the daily cadence and "check automatically", on purpose: the user pressed Update for that version and was interrupted (recorded in `resumeAfterRestart`). | `UpdatePolicy.kt`, `UpdaterImpl.resumeAfterRestart`; `UpdatePolicyTest.aWaitingFileFromAPreviousProcess` |
| S5 ReadyToInstall stuck / non-atomic busy | **Done.** ReadyToInstall is busy (no buttons; the hand-off is under way or waits for the foreground). The busy check and the state change are one step under a lock (`claim`): `download()` claims Downloading / Verifying (refused while busy or checking), `runCheck` claims Checking, `skip` claims Idle. ReadyToInstall is published only for the background wait; the deferred hand-off claims it back (Verifying) on the next start. `runInstall` takes the manifest as a parameter (no `offer == null` return) and its "already starting" return publishes NotInstalled. | `UpdaterImpl.claim`, `download`, `ready`, `runInstall`; `UpdatePolicyTest.busyIs…`, `.deferredHandOff` |
| S6 restriction text | **Done.** New `UpdateFailure.INSTALL_RESTRICTED` (contract enum) and `update_failed_restricted`: "This TV does not allow installing apps from outside its store. Install the update from the release page, or ask whoever manages this TV." | `UpdateContracts.kt`, `strings_diag.xml` |
| Nit: state before clearing Later | Done (`fail`, `notInstalled`). | |
| Nit: write after the digest | Done: `check(digest == null)` in `Sha256Counter.update`. | `UpdatePolicyTest.sha256RefusesAWriteAfterTheDigest` |
| Nit: arm at commit | Done: `InstallStatus.SessionCommitted` arms the watcher with a deadline unless the confirmation already started. | |
| Nit: runtime errors | Done: `runDownload` and `runInstall` catch `RuntimeException` → `Failed(INSTALL_FAILED)`; `CancellationException` is rethrown; `createSession`'s `IllegalStateException` → `Failed`. | |
| Nit: listener outside the try | Done (`startSystemInstaller`). | |
| Nit: Fire OS line | The card's Not installed line no longer adds it (a Cancel looks the same). `INSTALL_BLOCKED` shows the Fire OS text only on Amazon devices below API 26; elsewhere the general blocked text. With B1, a repeated miss on Fire OS 6 moves to the session, whose `INSTALL_BLOCKED` carries that text. | `UpdateCard.kt`, `UpdaterImpl.failureText` |
| Nit: real fold under test | Done: `UpdateCardModel.Fold`; `UpdateCardModelTest` drives it. | |
| Nit: restart reuse, deferred hand-off tests | Done as pure tests (`aWaitingFileFromAPreviousProcess`, `deferredHandOff`). | |

Known limits (not changed):
- API < 29: another installer's committed session is not visible as `isCommitted` (only `isActive`), so a slow install in progress after a return can still read as "Not installed" there; any later answer reconciles, and the process dies on success.
- `onHandOffCheck` makes binder calls (`getPackageInfo`, `getAllSessions`) on the main thread, once per return.
- The About row says "Last check failed · OK to try again" after a download NETWORK failure (the reason cannot be told from a check failure without a contract change).

**Emulator re-check after the code review (2026-11-10, same API 30 image; `adb install -r -d` of the debug build,
fixtures versionCode 7 and 8; nothing uninstalled or cleared).** Installer route, Cancel: "Not installed. Press Update
to try again." with Update focused, logged as `LEFT_INSTALLER` (one miss; the route stays). Update again: the file was
re-checked, not downloaded, and the installer route was used again → "Installing…" → "App installed." DONE / OPEN (OPEN
focused) → OPEN started the new version with "Updated to …". Forced session route (`update_route=session`), the S1 case:
the session confirmation (same dialog) → Install. The app came back in front for about a second, the watcher fired
("back from the session confirmation; re-checking before abandoning it"), the session was **not** abandoned, and the
install landed (versionCode 8; installer of record `app.thebox.debug`); the process ended on the launcher (no Open on the
session route, as designed) and the next start showed "Updated to …".

### Second code review (2026-11-10)

A second independent read of the rewrite (on top of ae9b14b) found no Blocker and three Shoulds; all are fixed, with
the nits. Where this table and the one above differ, this one wins (the session route's "no deadline" is gone).

| Item | Disposition | Where |
|---|---|---|
| S-1 session route stuck in "Installing" | **Done.** `PENDING_USER_ACTION` arms the watcher **with** a deadline (the no-deadline mode is removed). The deadline runs only while `MainActivity` is resumed, so not while the confirmation is up; an Install that brings us back is still covered by `keepWaiting` (3 × 15 s, re-reading the versionCode before our own session is abandoned). A confirmation that never shows, or is dismissed in under 700 ms, now ends in "Not installed" after the deadline. A `PENDING_USER_ACTION` without `EXTRA_INTENT` is a failure ("no confirmation", the session abandoned → "The update could not be installed. Try again, or install it from the release page."), never a confirmation that is not there. | `UpdaterImpl.handOff`, `HandOffWatcher`, `InstallRoute.confirmationFailure`, `InstallResultReceiver.onReceive`; `InstallHandOffTest.sessionRoute…`, `.pendingUserActionWithoutAnIntentFails` |
| S-2 (a) misses across processes | **Done.** Right before the installer activity starts, `installerRouteAttemptVersionCode` is written and a flush launched. A start that still runs an older version with an attempt recorded counts it as a `LEFT_INSTALLER` miss; a start that runs the attempted version (an honest successful install) just drops it. An in-process miss, a landed update, and the session route taking over consume the attempt, so nothing counts twice. NEVER_APPEARED still switches that version at once. Limit: the flush is launched, not awaited, before `startActivity`; the installer's screens take far longer to come up than a DataStore write. | `InstallRoute.recordAttempt/onStart/clearAttempt`, `UpdaterImpl.handOff`, `checkUpdatedNotice`; `InstallHandOffTest.attemptAccounting` |
| S-2 (b) device-level memory | **Done.** `installerRouteBrokenOn` holds `Build.FINGERPRINT`; while it equals the running system's, every version starts on the session route. It survives the per-version reset and is dropped when the fingerprint changes (a system update may have fixed the installer). **Choice:** the device is marked only on NEVER_APPEARED (the installer did not come up at all: a capability failure, not a user choice) or when the route had to be given up on **two different versions** (`installerRouteSwitchedVersionCode` remembers the first). Marking on any switch was judged too eager: a user who cancelled twice by hand on one release would lose the Done / Open screen on every later release until a system update. With this rule that user loses it for that one release only; a device whose installer keeps failing with its own error screen is marked after the second release (one extra release of two misses). Trade-off recorded: a user who really cancels twice on each of two releases is also marked, until a system update. | `UpdateContracts.UpdatePrefs`, `InstallRoute.recordMiss/installerRouteFailed/onStart`; `InstallHandOffTest.neverAppearedMarks…`, `.switchesOnTwoVersions…` |
| S-3 statuses not tied to a session | **Done.** The receiver takes a status only for the session in flight (`InstallRoute.acceptsStatus`: `EXTRA_SESSION_ID` equal to the pending id; without an id only while one is pending); a late status of an abandoned or older session is ignored and does not clear the pending id. The updater applies a status only while the state is `Installing`. One job at a time: `SingleFlight` is taken in the same locked step as the busy check and the state change (`download()`, the deferred hand-off) and released in the job's `finally`; the download loop calls `ensureActive()` between reads; each job writes its own `{vc}.apk.{job}.part`; `skip` and the restart cleanup do not delete while a job runs. | `InstallResultReceiver.onReceive`, `UpdaterImpl.download/startWork/handOff`; `InstallHandOffTest.statusesBelong…`, `.singleFlight` |
| Nit: `offer` inside the lock | Done (`download()`). | |
| Nit: `runCheck` returns its outcome | Done (`runCheckWithOutcome`; the shared `lastOutcome` field is gone). | |
| Nit: file I/O off the main thread | Done: `download()` claims "Downloading 0 %" and the job decides on IO whether the stored file can be reused (a reuse shows "Checking the download…" a moment later); `skip` deletes on IO. | |
| Nit: start-up crash | Done: every updater launch carries a `CoroutineExceptionHandler` (`appScope` is a `SupervisorJob`, so it takes effect) that logs; a check ends in Idle, a busy state in `Failed(INSTALL_FAILED)` with its next step. The start-up reads of the package manager were already `runCatching`. | `UpdaterImpl.guard` |
| Nit: `getParcelableExtra` | Done: `IntentCompat.getParcelableExtra`. | |

**Emulator re-check after the second code review (same API 30 image; `adb install -r -d`; fixtures 7 and 8).**
Installer route: Cancel → "Not installed. Press Update to try again." (logged `LEFT_INSTALLER`), Update → file re-checked,
installer route again → "App installed." DONE / OPEN (OPEN focused) → OPEN → versionCode 7. Forced session route: BACK on
the session confirmation sent `STATUS_FAILURE_ABORTED` at once and the card showed "Not installed" within about a second
(on this image BACK does send a status; the watcher's grace path was not needed). Update → Install on the session
confirmation: the app came back in front for about 2 s, the watcher fired and re-checked instead of abandoning, the
install landed (versionCode 8) and the process ended on the launcher; the next start showed "Updated to …".
(A first offer at sequence 7 was refused as REPLAYED because an earlier run had stored 8: the replay rule working.)
