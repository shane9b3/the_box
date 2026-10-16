# M5 setup, settings, parental and backup design: adversarial review

This review covers `docs/design/m5-setup-settings.md` (proposed 2026-10-16, 1,996 lines, read in full) and its fact sheet `docs/design/m5-api-notes.md` (312 lines, read in full). It was done on 2026-10-16 against the code at `f4b289e`. Neither the design, the fact sheet nor the code was edited. Every fix below is text for the lead to apply.

**Checked against:**
- `CLAUDE.md`.
- `00-master-plan.md` §2 (v1 rows), §3, §5, §6 and §8.
- `01-design-reference.md`.
- `review/product-review.md` §2.1, §2.3 (S1–S17) and §4.1–§4.12, row by row.
- `decisions/2026-10-14-settings-and-branding.md`. The button-mapping spec in it is binding.
- `decisions/2026-10-15-player-keys-and-quick-bar.md`.
- `design/m3-guide.md` §8 item 12 and the G5 row.
- `review/m3-code-review.md` H-S4 and H-S5.

**Code read** (grep plus reads; line numbers are from `f4b289e`):
- `core/.../input/{InputContracts,DefaultKeyMap}.kt`
- `ui/.../input/{KeyRouter,KeyRouterImpl,InputModule}.kt`
- `core/.../guide/GuideStateContracts.kt`
- `GuideStateMachineImpl.kt`: `:108-125`, `:225-229`, `:263`, `:290-307`, `:344-350`, `:548-612`, `:1541-1554`
- `PlayerStateMachineImpl.kt:173-296`
- `core/.../full/PlayerSettings.kt`, `GuideFeedContracts.kt:113-133`, `player/.../api/PlaybackProviders.kt:59-104`
- `PlaybackControllerImpl.kt`: `:254`, `:405-430`, `:745-777`, `:809`, `:840`, `:1342-1345`, `:1570-1620`
- `player/.../engine/{PassthroughGateAudioSink,PlayerFactory,BoxRenderersFactory,PlaybackWatchdog}.kt`
- `FailureClassifier.kt:51-218`, `core/.../playback/stall/StallDetectors.kt:46-47`
- `PlaybackContracts.kt:116`, `:255-279`; `PlaybackLogic.kt:32`
- `data/.../db/{LibraryDatabase,Migrations}.kt`; entities `Playlist`, `ChannelPrefs`, `GroupPrefs`, `Channel`, `ChannelGroup`, `Credential`
- `ChannelDao.kt:238`, `GuideQueries.kt:297`, `GuideQueries.kt:337`
- `TinkCredentialStore.kt`, `DataContracts.kt`, `Redact.kt`, `BoxLog.kt`
- `TheBoxApplication.kt:60-61`
- `ArchivePlaybackImpl.kt` (location only)
- `GuideEffectsImpl.kt:120-150`, `PlayerEffectsImpl.kt:30-60`
- `AppRoot.kt:71-83`, `AppRoot.kt:238-310`
- `HttpModule.kt:18-23`, `PlaybackHttpClients.kt:34-66`
- `app/build.gradle.kts:25`, `app/build.gradle.kts:44`; `AndroidManifest.xml`
- `tools/fakepanel/{fakepanel,catalog}.py` (grep)

The design is cited as `m5:N` (line numbers). No Gradle runs and no device were used.

## Summary

| | Count |
|---|---|
| A. Blockers | 4 |
| B. Should fix before commit 0 | 16 |
| C. Nits | 16 |
| D. Fact cross-check (F1–F28) | 3 verified, 9 partial, 15 unverified, 1 contradicted |
| E. Lead decisions | 18 |

**What holds:**
- A pure `SettingsMachine` composed into the guide machine exactly as M4 composed the player. This keeps one key sink and one Back ladder, and five-key reachability can be proven by enumeration.
- Button mapping as data behind `KeyMap` plus a capture mode of the one router. That satisfies "no second routing path".
- `app_setting` in Room for atomic restore.
- `playlist.uid` for portable identity.
- JSON Lines backups with an authenticated secrets record.
- A signed manifest for the updater.
- An own LAN server with Host and custom-header checks.
- Every row in product §4 is accounted for, apart from the three undeclared drops in N4 and N5.

**What breaks** (one line each; details in §A):
- **B1.** A relocked parental session is bypassed by "previous channel", by the zap list and by the automatic resume after Home or standby. These are the three tune paths that do not go through the guide's lineup.
- **B2.** "Always passthrough" (owner-mandated, named in the plan's M5 row) has no work-package owner. Its mechanism, a deny-only gate, cannot force passthrough. The "3 s silence watchdog" its promise relies on does not exist.
- **B3.** The plan's v1 commitment "adult groups auto-locked" is silently weakened. With no PIN, adult channels join All channels and zapping (today they are hidden), while the row still reads "Lock adult content automatically: On". Restore and AddSource never offer a PIN.
- **B4.** The work packages are not disjoint and some files have no owner, so commit 0 cannot hand out the WPs as written.

---

## A. Blockers

### B1. Parental bypass: previous channel, the zap list and lifecycle resume tune locked channels after a relock

- **Where:** §3.9.2 "Nothing else asks…" (`m5:1530`); §3.9.5 (`m5:1558`); §2.6 `RelockReason`.
- **Problem.** The design guards only the entry points that go through the guide's or the player's lineup (Groups panel, channel list, number entry, reminder card). The controller has three tune paths that never consult a lineup or a predicate:
  1. `PlaybackControllerImpl.previousChannel()` (`:414-418`) tunes `previousIdentity` directly. It is reachable from LAST_CHANNEL/Blue in the guide (`GuideStateMachineImpl.kt:350` → `GuideEffectsImpl.previousChannel` → `controller.previousChannel()`), from PREVIOUS_CHANNEL in the player, from the quick bar, and from the error card's PREVIOUS_CHANNEL button.
  2. **The zap list.** `zap(delta)` walks the `LineupZapList` captured at the last tune (`PlayerEffectsImpl.tune`). After an unlock, that list is the locked group's lineup. When the session expires (1 hour), CH± keeps landing on locked channels. §3.9.5 says only that "an expiry while a locked channel is playing waits until the user tunes away", but tuning away inside that list is still a locked channel.
  3. **`resumeAfterLifecycle()`** (`:1579-1592`) restarts the last cycle on return from Home, standby or HDMI-off. That is the very event that relocks the default "Until The Box closes" mode (ON_STOP). The design does not ask again on resume.
- **Effect.** A parent unlocks with the PIN, watches an adult channel and presses Home. A child opens The Box and the adult channel resumes full screen, with no PIN. Alternatively the child presses Blue (previous channel) after any relock. That defeats the PIN, so it is a blocker under "security of credentials/PIN".
- **Fix (contract, commit 0):**
  - Add a `TuneGuard` seam in `:core`: `fun interface TuneGuard { fun allowed(identity: ChannelIdentity): Boolean }`.
    - It is implemented in `:data` from the same predicate as §4.3 (`ParentalAccess`, one indexed lookup per identity, cached per `queryKey`).
    - It is injected into `PlaybackControllerImpl`.
  - `previousChannel()`, the debounced zap neighbour lookup and `resumeAfterLifecycle()` call it before `startCycle`. A refused target:
    - **previous:** returns a new `PreviousResult.Locked(identity)`, and the guide machine opens the PIN prompt with `Tune(channel, owner)`.
    - **zap:** skips to the next allowed entry, the same rule the M3 predicate already applies to lineups.
    - **resume:** does not start. The controller emits `Stopped(reason = LOCKED)`, and the guide machine opens the PIN prompt with `Tune` for that channel (Back leaves the guide on All channels, nothing playing).
  - The relock also clears the zap list (`setZapList(null)` → the next zap rebuilds from the current, filtered lineup).
  - The "never cut off" promise in §3.9.5 is kept only for a timer expiry while the app is in the foreground. It never covers a relock on ON_STOP.
- **Tests:**
  - `ParentalBypassTest` (controller with a fake guard): previous, zap and resume each refuse a locked identity after relock, and each allows it while unlocked.
  - `PinRoutingTest` gains "Blue after relock → PIN prompt" and "ON_STOP → ON_START with a locked tuned channel → PIN prompt, no `startCycle`".
  - Instrumented: I-M5-14 extended with "Home and back while on the adult channel → PIN pad, no video".
- **WPs:** commit 0 (seam), WP-C (guard, machine routing), WP-A (controller edits; WP-A already owns `PlaybackControllerImpl`).

### B2. "Always passthrough" has no owner, an unworkable mechanism and a false promise

- **Where:** §2.12.7 (`m5:1008`); §3.3.3 row `playback.audio_output` (`m5:1182`); §1.4–§1.8 (no WP lists `PassthroughGateAudioSink.kt`, `BoxRenderersFactory.kt` or `PlayerFactory.kt`); §1.2 sizing (no line item); §6.3 (no test).
- **Problem (1): no owner.** The plan's M5 row names this item, and the owner asked for it on 2026-10-14. No WP's file table includes the engine files, and no day is budgeted for it. WP-A edits only `PlaybackControllerImpl` (`reapplySettings`, `handOffExternal`).
- **Problem (2): the mechanism cannot work as written.**
  - `PassthroughGateAudioSink` is a `ForwardingAudioSink` that can only **deny**. It returns `!policy.denies(format) && super.supportsFormat(format)` (`PassthroughGateAudioSink.kt:46-50`).
  - "The gate ignores `AudioCapabilities`" would mean returning true where `DefaultAudioSink` says no. But `DefaultAudioSink.configure` derives the output encoding from its **own** capabilities. A forwarding `supportsFormat` of true only makes the renderer try bypass, and the wrapped sink then fails configuration or initialisation.
  - Forcing passthrough needs a capabilities override inside `DefaultAudioSink` (built in `BoxRenderersFactory.buildAudioSink`, `:39-45`). In Media3 1.11.1 that is a builder parameter, so the change may only take effect when the sink is rebuilt. If so, the row is `RESTART`, not `REPREPARE`. F19 (which API) is **unverified**; the fact sheet does not cover it.
- **Problem (3): the safety net it promises does not exist.**
  - The design and the decision note rely on "the 3 s silence watchdog (M2)". No such detector exists. `grep silence` in `:player` finds comments only.
  - What exists is:
    - `AUDIO_NOT_STARTED` (`StallDetectors.kt:46-47`, 3 s, or 5 s with passthrough), which fires only when the audio position never advances;
    - ladder L34 on an `AudioTrack` initialisation failure (`FailureClassifier.kt:98-116`, `PlaybackControllerImpl.kt:1342-1345`).
  - The real "misreporting AVR" case is a sink that accepts the AC-3 bitstream, so the position advances, while the receiver outputs nothing. Nothing detects that.
  - So the row text "If you hear nothing, The Box switches back by itself" is false in exactly the case the option exists for. A user can be left with silence and no recovery other than finding the setting.
- **Fix:**
  - Give the item to WP-A: +1.5 days, owning `BoxRenderersFactory.kt`, `PassthroughGateAudioSink.kt` and `PlayerFactory.kt`.
  - Commit 0 verifies F19 (the 1.11.1 `DefaultAudioSink.Builder` capabilities API) and sets the row's `EffectTiming` from the answer (RESTART if the sink must be rebuilt).
  - Rewrite the promise to match what exists: "If the TV or receiver refuses the sound, The Box switches back to Auto. If you hear nothing, choose Auto." Keep the toast for the L34 and `AUDIO_NOT_STARTED` fallbacks.
  - Add a one-tap "Back to Auto" on the passthrough stats line or in the audio picker while FORCE_PASSTHROUGH is on.
  - Add I-M5-27: on the emulator's PCM-only sink, FORCE_PASSTHROUGH with an AC-3 fixture → L34 or `AUDIO_NOT_STARTED` → PCM, toast, and the denial is remembered for the route. The decision note asks for exactly this test.
  - Fix the decision note's wording ("3 s silence watchdog") in the same commit.

### B3. "Adult groups auto-locked" (plan v1 row) becomes "unlocked until someone sets a PIN", and the UI says otherwise

- **Where:** §0.8 (`m5:21`); §2.6 `ParentalState.access`; §3.3.8 row `parental.auto_lock` default On (`m5:1245`); §3.4.6 (`m5:1374`); §3.4.1 Restore → GUIDE; §8 (no question covers it).
- **Problem.**
  - The plan (§2, "Groups & parental: … 4-digit PIN, adult groups auto-locked") is the approved baseline. M3 §8 item 12 asked M5 to "ask for a PIN or turn auto-lock off". The design does neither:
    - "Not now" leaves `autoLockAdult = true` shown as **On**, while `access == OPEN` puts every adult channel into All channels, Favourites, Recent, Search and CH± zapping.
    - Today, with `debugUnlocked = false`, those channels are hidden (`ChannelDao.kt:238`). So M5 regresses family safety for every user who skips the PIN.
  - Three paths never show the PIN offer:
    - AddSource outside first run (GroupsReview is first-run only, `m5:1362`);
    - Restore (backups carry `group_prefs.locked` but never the PIN, so a restored "locked" state is silently unenforced);
    - phone setup into a second playlist.
- **What the owner loses either way:**
  - Enforcing locks with no PIN is the M3 trap: content can never be reached, and there is no way to set a PIN from where you are.
  - Not enforcing means a kid zapping All channels on a fresh install lands on adult channels, and the settings page claims they are locked.
- **Fix (lead decision; recommended middle state):**
  - Add `ParentalAccess.FILTERED_NO_PIN` (ordinal 3, appended). It is in effect when no PIN exists and auto-lock is on.
    - Locked groups and channels are excluded from the **mixed** lineups: All channels, Favourites, Recent, Search, zapping, number entry and reminders.
    - Locked groups stay listed in the Groups panel with the `18+` badge. OK on one opens a one-time card: "This group is not protected. [Set a PIN] [Open anyway]". "Open anyway" opens it until the app closes.
  - The row's value reads "On · not protected: set a PIN" in amber.
  - The PIN offer card appears:
    - after any import that found adult groups while no PIN exists (first run or AddSource);
    - after a restore that carried locks;
    - at most once per playlist.
  - `ParentalPredicateTest` gains the fourth access value.
  - If the lead prefers the design as written, the minimum is: the row shows "On · not enforced until you set a PIN", the plan's §2 row is amended with the reason, and the offer card appears on AddSource and after a restore.

### B4. Work packages are not disjoint, and several edited files have no owner

- **Where:** §1.3 "No WP edits a file owned by another WP" (`m5:75`); §1.4–§1.8.
- **Problem:**
  - **No owner:**
    - `ui/.../guide/vm/GuideEffectsImpl.kt` must implement four new `GuideEffects` methods (`verifyPin`, `openSettings(route)`, `stopPlayback`, `updateAction`).
    - `ui/.../full/vm/PlayerEffectsImpl.kt` must implement the error-route buttons and the Sources picker.
    - `data/.../security/TinkCredentialStore.kt` must implement `SecureBlobStore`; today `put` only serialises `PlaylistSecret`, and `wipe()` is private and must write `pin_lost_to_key_reset`.
    - The passthrough engine files (B2).
    - ACRA initialisation (D3 relies on local crash reports, but `acra-core` is only declared, never configured).
  - **Two owners:**
    - `app/.../TheBoxApplication.kt`: WP-A must start the `SettingsStore` load there (§0.5), and WP-E installs `LogRing` and schedules update checks.
    - `ui/.../guide/vm/GuideViewModel.kt`: owned by WP-C (§1.6), but the "startup awaits `SettingsStore.ready`" gate is sized in WP-A (§1.2), and the first-run `OpenSetup` decision belongs to WP-B (§3.4.1).
    - `data/.../db/dao/ChannelDao.kt` and `GroupDao.kt`: owned by WP-C, but WP-D needs new paged queries (hidden channels joined with names and rule badges; group list with counts and badges; favourite reordering) and WP-B needs `searchEpgChannels`, which does not exist (grep).
    - `core/.../settings/tree/SettingsTree.kt` (WP-A) must list WP-C's and WP-D's row ids, visibility and `EffectTiming`. Who adds them?
  - **Not in commit 0's amendment list:**
    - `okhttp-dnsoverhttps` is already a `:data` dependency (fact sheet §0), so commit-0 build item 4 is half done.
    - `KeyRouter.kt` is in `:ui`, not `:core` (`ui/.../input/KeyRouter.kt`).
- **Effect:** at the first merge two WPs edit the same file, or a contract method has no implementation and the stub's `TODO` crashes at runtime.
- **Fix:** add an "Owner of every edited file" table to §1, and give commit 0 every shared edit:
  - **commit 0 (lead):**
    - `GuideEffectsImpl`/`PlayerEffectsImpl` method skeletons that delegate to injected WP interfaces;
    - `TheBoxApplication` hooks (`settingsStore.load()`, `logRing.install()`, `updates.schedule()`) as calls into stubs;
    - the `GuideViewModel` startup gate and first-run decision;
    - DAO query signatures (bodies by the using WP, in new DAO files: `HiddenChannelsDao` (D), `GroupAdminDao` (D), `EpgChannelSearch` (B));
    - `SettingsTree` as a registry that each WP fills from its own `*Rows.kt`.
  - **WP-C:** the `TinkCredentialStore` blob and `wipe()` edits.
  - **WP-E:** ACRA local configuration.

---

## B. Should fix before commit 0

### S1. The settings load timeout can overwrite stored settings with defaults
- **Where:** §0.5, §2.2 (`SettingsStore.update`, `ready`), §5.7.
- **Problem.** After `SETTINGS_LOAD_TIMEOUT_MS` the store publishes defaults. Any `update()` after that (the debug receiver, `PersistedPlaybackSettings`' `defaultUserAgent` seed, which today runs at binding time in `PlaybackBindingsModule.kt:24-26`, or a user change) applies `transform` to the defaults and persists the whole document over the real row.
  - The first start after installing M5 runs `MIGRATION_3_4` on open. That is exactly when a Fire TV Stick is most likely to miss 1 s.
  - Separately, a debounced pending write (250 ms) can land **after** a restore commit and undo it.
- **Fix:**
  - Until the row has been read, `update` queues transforms and never writes. After a timeout, the late read wins and queued transforms are replayed on it.
  - `importInTransaction` cancels pending writes for the domains it replaces.
  - Seeds that are not user choices (`defaultUserAgent`) stay out of the store (they are mapped in `PersistedPlaybackSettings`).
- **Test:** `RoomSettingsStoreTest`: a slow read beyond the timeout, then `update`, gives the stored document plus the change; a restore during a pending write keeps the restored value.

### S2. "Lock settings" can be bypassed through SyncStatus
- **Where:** §3.2.1 (any route pushes `[Root, …, route]`), §3.2.5 (SyncStatus is exempt from the PIN), §3.3.2 SyncStatus menu "Edit".
- **Problem.** When the sync chip shows a failure: OK on the chip → SyncStatus (no PIN) → BACK → Root, which is all of Settings without the PIN. The SyncStatus "Edit" menu item also leads to source editing.
- **Fix:** a route entered without the PIN opens with a detached stack, so BACK leaves Settings. Its links into PIN-gated routes ask for the PIN.
- **Test:** `PinRoutingTest`: chip → SyncStatus → BACK lands outside SETTINGS; Edit asks for the PIN.

### S3. Text editor: the router lets BACK through to an Activity with no back handler, and the G5 workaround is not specified
- **Where:** §2.12.5 ("BACK is not intercepted either"); §3.2.2 editor paragraph; §6.2 `TextEditorOverlayTest`.
- **Problem.**
  - Nothing in `ui`/`app` handles back (no `BackHandler` or `onBackPressed`). If the editor island loses focus (the IME closes, a recomposition drops the `FocusRequester`), BACK reaches `Activity` default handling and leaves the app mid-onboarding.
  - Intercepting BACK is safe anyway. While the IME is shown it consumes BACK in the IME input stage, before any `onPreviewKeyEvent` sees it.
  - The single-line `BasicTextField` swallowed D-pad Down (G5, `SearchScreen.kt`). The editor needs the same `onPreviewKeyEvent` Down handling, and Up from the chips back to the field. The design describes the outcome but never cites the bug or the workaround.
- **Fix:**
  - While `textInputActive`, the router passes every key except BACK. BACK becomes `SettingsEvent.EditorBack` (Cancel, or close the chips focus).
  - Specify the G5 handler (Down → chips, Up → field, IME Next/Done → Done) and add a no-IME fallback: when `getEnabledInputMethodList()` is empty and no hardware keyboard is present, the editor says "This TV has no keyboard app. Use Set up from phone or plug in a USB keyboard". F5 is listed but never used.
- **Test:** `TextEditorOverlayTest` with focus cleared → BACK still closes the overlay; Down from the field reaches the chips.

### S4. Phone setup: the screensaver stops the server mid-entry; VPN and upload-size gaps
- **Where:** §3.5.2 lifetime ("stops … on app stop (ON_STOP)"); §3.5.1 isolation hint; `PHONE_UPLOAD_MAX_BYTES`.
- **Problems:**
  - Nothing keeps the screen on during PhoneSetup or ImportProgress (keep-screen-on is tied to playback). While the user types on the phone, the TV receives no key. The Fire TV / Android TV screensaver starts after its delay, and the Activity stops. ON_STOP kills the server, and the phone gets connection errors.
  - Many IPTV users run an always-on VPN on the TV. The server binds the Wi-Fi address, but many VPN apps block inbound LAN traffic. The 90-second hint mentions only client isolation.
  - 64 MB rejects the largest M3U files the plan's §5 #7 benchmark targets (250k entries).
- **Fix:**
  - Set `FLAG_KEEP_SCREEN_ON` while PhoneSetup, ImportProgress and the restore flow are shown.
  - Add the sentence "If a VPN app is on, allow local network access or pause it" to the hint.
  - Raise the M3U upload cap to 256 MB (it is streamed to disk anyway), or state the limit on the page.
  - State in §3.5.4 that for BACKUP_DOWNLOAD and RESTORE the passphrase and the ciphertext cross the same plain-HTTP LAN, so a LAN sniffer gets both.

### S5. The parental predicate keeps an unlocked adult group's channels hidden
- **Where:** §4.3 SQL (`m5:1744-1746`); §3.9.1 "A user unlock of an adult group (`locked = 0`) wins over detection".
- **Problem.** The channel clause is `COALESCE(cp.locked, CASE WHEN :access = 1 THEN c.is_adult ELSE 0 END) = 0`. M1 sets `channel.is_adult` per stream (`ChannelEntity.kt:58`), so every Xtream adult stream has it.
  - A user who unlocks the "Adult" group (`gp.locked = 0`) sees the group with its count, but every channel stays hidden.
  - The group-level win never reaches the channels.
- **Fix:** `COALESCE(cp.locked, CASE WHEN :access = 1 AND gp.locked IS NULL THEN c.is_adult ELSE 0 END) = 0`. That is: an explicit group decision overrides channel auto-detection, and an explicit channel lock still wins.
- **Test:** `ParentalPredicateTest` row: group `is_adult = 1`, `gp.locked = 0`, channel `is_adult = 1` → visible under LOCKED_AUTO.

### S6. The weekly backup does not survive a reinstall on API 30+ TVs without a file picker
- **Where:** §3.10.5, Q14; F16 and F17.
- **Problem.**
  - Product #20 justifies the weekly backup by "lost setup on reinstall". After an uninstall, an API 30+ app (Fire OS 8, Google TV / Onn) cannot list the previous install's non-media files in `Download/` through MediaStore (memory; F16 unverified).
  - Fire OS has no SAF picker (fact sheet §7).
  - The offered fallback, "Send it from your phone", does not apply: the file is on the TV, not on the phone.
  - So on most target devices the weekly backup restores only into the same install, where it is least needed.
- **Fix (with a lead decision, E10):**
  - State the real coverage per API level in §3.10.5 and on the row ("Survives a reinstall on this TV: yes / only with a file manager").
  - Add a monthly nudge on the Backup page, "Keep a copy on your phone", which opens BACKUP_DOWNLOAD.
  - Verify F16 on the emulator (API 30 and 33: install, write, uninstall, reinstall, query) in commit 0 before WP-D builds the Downloads lister.

### S7. Updater: the cert check on API 25–27, the debug key, the debug package id and manifest replay
- **Where:** §3.12.2–§3.12.5, I-M5-19.
- **Problems:**
  - **Cert check on API 25–27.** If `getPackageArchiveInfo(path, GET_SIGNATURES)` returns null signatures on API 25–27 (fact sheet gap 12), `ApkVerifier` returns WRONG_CERT on every Fire OS 6 stick, the oldest reference device. The cert check is defence in depth: the manifest's signed SHA-256 already authenticates the bytes, and the platform rejects a different signer at install.
  - **Debug key.** Debug builds need a test public key in the debug source set, and release must never contain it. As written, the fake panel cannot sign a manifest the app accepts.
  - **Debug package id.** The debug `applicationId` is `app.thebox.debug` (`app/build.gradle.kts:44`). "`packageName == BuildConfig.APPLICATION_ID` (release id)" and I-M5-19's "valid" path disagree.
  - **Replay.** A replayed older signed manifest can offer a release that was later withdrawn to a client below it.
- **Fix:**
  - On API < 28, a null signature list means "skip the cert check, log it", never WRONG_CERT.
  - Add `UPDATE_PUBLIC_KEYS` per build type.
  - Compare against `BuildConfig.APPLICATION_ID` of the running build.
  - Add `UpdateManifest.sequence: Long`, monotonic. The client stores the highest sequence it has accepted and ignores lower ones.
  - Verify F13's redirect host list. GitHub release assets are now served from `release-assets.githubusercontent.com` as well as `objects.githubusercontent.com` (memory).

### S8. Q3 "fail open on a Keystore reset" leaves file playlists playing with no PIN
- **Where:** §3.9.6.
- **Problem.**
  - The argument is that "every playlist needs its login re-entered before anything plays". That holds for Xtream, and for M3U URLs only if the URL lives in the secret.
  - `addM3uFile(name, uri, displayName)` takes no secret (`PlaylistRepository.kt:60`). A file playlist keeps playing after a key reset, so its adult groups open with no PIN.
- **Fix:**
  - On `pinLostToKeyReset`, keep `access = FILTERED_NO_PIN` (B3) for content until a new PIN is set, and open settings normally (no lockout).
  - Verify where `addM3uUrl` stores the URL, and amend the text to match.

### S9. `reapplySettings()` cannot reach `ArchivePlayback`, and it runs only on BACK at the root
- **Where:** §2.12.6; §2.3 `onClosed`.
- **Problem.**
  - `ArchivePlaybackImpl` lives in `:app` (`app/.../playback/ArchivePlaybackImpl.kt`), above `:player`. A controller method that re-prepares "archive: at the playhead through `ArchivePlayback`" inverts the layering.
  - `onClosed` fires only when `act(BACK)` returns false. Leaving SETTINGS any other way (a future mode switch, a reminder "Switch now", process death) drops pending REPREPARE changes silently.
- **Fix:**
  - `SettingsEffectsImpl.onClosed` decides: when `archive.status.phase` is active, call `archive.resume()`; otherwise call `controller.reapplySettings()` (live only).
  - The guide machine calls `settings.onLeave()` on every exit from SETTINGS, not only on BACK.
  - `ReapplyOnExitTest` gains an archive case.

### S10. Thread `ParentalAccess` through the signatures instead of reading global state inside queries
- **Where:** §2.12.9; §4.3.
- **Problem.** Callers pass `unlocked = access == OPEN`, then `:data` re-reads `parentalSession.state.value` at query time. A relock between the VM's `queryKey` restart and the query gives a lineup built under a different access than its key. `ParentalPredicateTest` cannot drive it directly.
- **Fix:** amend `GuideLibrary`/`GuideSearch`/`EpgMatchRepository.unmatched` to take `access: ParentalAccess`. There are six call sites, listed by the code check:
  - `GuideViewModel.kt:335`
  - `PlayerViewModel.kt:263` and `PlayerViewModel.kt:389`
  - `SearchViewModel.kt:108`
  - `GuideEffectsImpl.kt:83` and `GuideEffectsImpl.kt:212`

  It is a commit-0 contract change, and also documents that `sources()` now lists locked groups with `locked = true`, a semantic change for an existing caller.

### S11. Attach order of the settings machine
- **Where:** §2.13 (`SettingsMachine` created by `SettingsViewModel` and "attached … before `startup()`").
- **Problem.** `GuideStateMachine` has no `startup()`; only `GuideViewModel.startup()` (`:453`) exists. Two activity-scoped VMs have no creation order. A first-run `OpenSetup` reaching a guide machine whose `settings == null` puts it in SETTINGS with every key dead. This is the M3 H-S5 class of bug.
- **Fix:** the guide machine treats `settings == null` in SETTINGS as "waiting" (BACK still exits on FirstRun), and `GuideViewModel.startup()` awaits a `settingsAttached` signal.
- **Test:** `ComposedSettingsGuideTest` attaches after `OpenSetup` and asserts the Welcome keys work.

### S12. Contract types that duplicate or contradict existing code
- `DataPrefs.matchLevel: core.settings.MatchLevel { EXACT, … }` duplicates `core.match.MatchLevel { EXACT_ONLY, EXACT_AND_NAMES, SUGGEST_FUZZY, AUTO_FUZZY }` (`MatchContracts.kt:32`). Use the existing type.
- `DataPrefs.epgPastDays = 0 (Auto)` changes today's default of 2 (`DataSettings.kt`). The mapping to the importer's value, max catch-up days clamped to 1–7, must live in `PersistedDataSettings` with a test.
- `GuidePrefs.miniStill: Boolean?` and `ambient: Boolean?` map onto non-null `GuideSettings` fields (`miniStill = false`, `ambient = true`). Specify the low-end resolution in one place.
- `GuideStateContracts.kt` is at `core/.../guide/`, not `guide/state/`.
- There is no `StreamConnectionBroker` type. It is `ConnectionBrokerCore.acquire(kind, ownerTuneId, waitMs)` (`PlaybackLogic.kt:32`) behind `StreamBrokerRegistry` (`player/.../internal/Seams.kt:35`). `StreamProbe` needs an `ownerTuneId` for a probe (use a reserved negative id).
- The router method is `onKey`, not `onNative` (§2.12.5).

### S13. `LogRing` must redact the `Throwable` itself
- **Where:** §3.11.3 ("stack traces by the existing `Redact.text` call").
- **Problem.** That call sits inside the logcat sink lambda (`TheBoxApplication.kt:61`). `BoxLog` passes the `Throwable` unredacted (fact sheet §11). OkHttp exceptions carry full URLs (`/live/user/pass/…`), and `filesDir/logs/current.log` would hold them in clear. The export's second pass catches them, but the on-disk file does not.
- **Fix:** the composite sink formats `Redact.text(Log.getStackTraceString(t))` once and hands the result to all three sinks.
- **Test:** `LogRingTest` with an `IOException("…/live/u/p/1.ts")`.

### S14. Key capture details
- **Where:** §3.8.2, §3.8.3, §2.12.5.
- **Problems and fixes:**
  - Reject `KEYCODE_UNKNOWN` (0). Some CEC and IR remotes report 0 for several buttons, so binding one would bind all of them.
  - After capture the router disarms while a "hold" key is still down. Specify "swallow every event of the captured code until its UP, even after disarm"; otherwise the repeats re-enter routing as a fresh press.
  - Capture-timeout text: "No button arrived. Some buttons (Home, voice, app buttons, volume) are handled by the TV and can't be used."
  - Record a deviation from the binding spec. The owner reserved D-pad and OK "outside the player"; the design also reserves them in the player. That is technically required while overlays read UP/DOWN from the same `PLAYER` context, but it is a lead decision (E14).

### S15. The PBKDF2 budgets are likely wrong on the reference stick
- **Where:** §2.13 (`PIN_KDF_BUDGET_MS` 150 at 100,000 iterations); §3.10.3 (600,000 iterations); §6.8.
- **Problem.** An RFC 8018 loop over `javax.crypto.Mac` makes two JNI `doFinal` calls per iteration. On a Cortex-A53 that is plausibly 1–3 µs each, so 0.2–0.6 s per PIN check and 1.2–4 s per backup attempt (estimate, unmeasured).
- **Fix:** commit 0 adds a microbenchmark run on the API 30 emulator, and §2.13 states the scaling rule, "iterations chosen so the emulator takes ≤ 50 ms; stored in the blob". The real protection is the Keystore AEAD plus lockout (Q12), so 10,000–20,000 iterations for the PIN are defensible.

### S16. Test gaps
- **Onboarding failures.** The fake panel cannot simulate a slow or timing-out `player_api.php`, HTML on `player_api.php`, 429, or a non-Xtream host (fact sheet §13). §6.4 adds only `sleep_login`. Add `api_fault: {html_splash, timeout, 429, redirect}` and I-M5 rows for HTML-splash login and timeout. `SetupMapperCompletenessTest` covers mapping, not transport.
- **458 at login.** Absent. It is a stream-open status, and only the probe covers it (I-M5-6). That is acceptable; say so.
- **Missing tests for this review's findings:**
  - B1: no test.
  - B2: no passthrough test.
  - S8: no Keystore-reset notice test; add a debug route `thebox-debug://keystore-reset`.
- **Capture with a device.** I-M5-12 injects `PROG_YELLOW` "with a device id", but `Instrumentation.sendKeySync` events carry device id −1 or virtual (F1, unverified). Assert "from an unknown device" as the expected result on the emulator, not as a fallback.

---

## C. Nits

- **N1.** `ActionGroup { WATCHING, CHANNELS, GUIDE, PLAYBACK, APP }` (§2.7) vs the five groups named in §3.8.1 ("Player overlays"). Align them.
- **N2.** `BackupRecord.EpgSource` says playlist-derived sources are keyed by "(playlist uid, origin, ordinal)", but the record has no ordinal field.
- **N3.** `catchup_correction_h` is exported as "user-settable" (§3.10.2), but no settings row edits it. Either it is learned from the playlist (then do not export it), or a row is missing.
- **N4.** Product §4.3 "Update interval … daily at a set time" is dropped without being listed as a deferral.
- **N5.** Product §4.4 hides AFR below API 30; the design shows Off/Always. That is a reasonable change (the plan has `preferredDisplayModeId` below 30), but record it.
- **N6.** `playlist.uid` is nullable in the entity. Inserts outside `PlaylistRepository.add` (tests, debug receiver, fixtures) leave NULL, which the unique index allows, while `BackupRecord.Playlist.uid` is non-null. Give `PlaylistEntity.uid` a generating default.
- **N7.** Say explicitly that the settings kit must not use tv-material `Switch` (40×24 dp), `RadioButton` (20 dp) or `ListItem`: fixed dp, and scale 1.1 with a `graphicsLayer`. The design draws its own 56×30 toggle and 18 px radio, which is correct; make it a rule.
- **N8.** `okhttp-dnsoverhttps` is already in `:data` (commit-0 item 4). `PlaybackHttpClients` builds from `base.newBuilder()` (`:34-66`), so a `dns()` on the injected base client already reaches the playback clients. The separate injection in §3.13 is only needed if the base differs. Check which client is injected.
- **N9.** RESTART rows ("Software audio fallback", B2 if it applies) should offer **Restart now**. A TV process can live for weeks.
- **N10.** LAN DoS. Any LAN device can burn the ten code attempts (the server stops) or hold all four sockets for 15 s each. That is acceptable; state it in §3.5.4.
- **N11.** In SETTINGS, OPEN_GUIDE, EXIT and OPEN_SEARCH are swallowed (only BACK at the root returns false). Decide whether GUIDE leaves Settings.
- **N12.** ImportProgress "Stop" in first run: the destination is not specified (form? Welcome?).
- **N13.** "Include logins" was a toggle defaulting On (product §4.10). In the flow step, focus should default to "Include logins" so the product default survives.
- **N14.** The `FileProvider` exposes `cacheDir/phone`. Nothing needs a content URI there, so expose only `filesDir/update`.
- **N15.** §3.1 equates `oklch(0.72 0.14 240)` with `#5CA8F2`. M3 §0 item 12 ruled the hex normative and the oklch value different. Cite the hex only.
- **N16.** The licences page: `ui/src/main/assets/licenses/` holds only the two font OFL texts today. FFmpeg LGPL, Tink, OkHttp, Media3 and qrcodegen texts are WP-E work; list them in WP-E's table.

---

## D. Fact cross-check (design §9 → `m5-api-notes.md`)

Verdicts:
- **verified:** the fact sheet confirms it from a source or the code.
- **partial:** the API exists, but the behaviour the design relies on is not confirmed.
- **unverified:** not covered, or covered only by memory or a web summary.
- **contradicted:** a cited source says otherwise.

| # | Verdict | Note |
|---|---|---|
| F1 | partial | `getDevice`/`InputDevice` APIs verified (§5.1); null for injected events and `isVirtual` semantics are a gap (gap 8) |
| F2 | partial | `keyCodeToString` (API 12), SETTINGS 176, HOME 3, POWER 26, SLEEP 223, WAKEUP 224 and TV_POWER 177 verified; volume/mute codes not listed |
| F3 | unverified | Not covered. The design's choice (the router returns false) does not depend on it; S3 changes BACK |
| F4 | partial | Gboard-TV input types [web]; Fire OS keyboard, password behaviour and extract mode are gaps (gap 2) |
| F5 | unverified | Not covered; and the design never uses it (S3) |
| F6 | partial | APIs verified; Fire OS / Google TV behaviour not |
| F7 | unverified | Gap (§3.3). Reviewer agrees from knowledge (`crypto.subtle` needs a secure context) |
| F8 | partial | NanoHTTPD 2.3.1, 2016 verified [web]; the CVE claim is not covered (reviewer recalls CVE-2022-21230, temp files) |
| F9 | unverified | The sheet researched ZXing, not `io.nayuki:qrcodegen`; version, licence and bytecode open |
| F10 | unverified | `PBKDF2WithHmacSHA256` from API 26 is [web-summary] (gap 10). The design's own loop makes it moot |
| F11 | unverified | Tink `Ed25519Verify` on API 25 is not covered |
| F12 | partial | API levels verified (§8.1); `GET_SIGNATURES` on 25–27 is gap 12 (S7); PendingIntent mutability not covered |
| F13 | unverified | Not covered (S7) |
| F14 | partial | `canRequestPackageInstalls`/`ACTION_MANAGE_UNKNOWN_APP_SOURCES` (26) verified; Fire OS paths [web-summary]; Play policy [web-summary] |
| F15 | verified (scope) | FAQ [web]: TV and Google TV are outside the Sep 2026 enforcement; non-Play is "not subject yet"; 2027+ global. Fire OS is not mentioned (gap 13). The design is consistent |
| F16 | unverified | `MediaStore.Downloads` (29) verified; permission and reinstall visibility are [memory] (S6) |
| F17 | **contradicted** | CommonsWare [web-summary]: on TVs without DocumentsUI the intent can still resolve to a stub activity, so `resolveActivity()` does not reveal the gap. §3.10.5 shows the File target "only when `resolveActivity` finds a document UI". Fix: also check that the resolved package is a known DocumentsUI (`com.android.documentsui`, `com.google.android.documentsui`), else hide; keep the phone path first |
| F18 | partial | The artifact is already a dependency (verified); `DnsOverHttps.Builder` and `Chain.withConnectTimeout` are [memory] |
| F19 | unverified | Not covered; B2 depends on it |
| F20 | verified (code) | Tunneling and buffer policy are applied per prepare in `applySwitches` (`PlaybackControllerImpl.kt:745-777`, tunneling `:759`) |
| F21 | unverified | Not covered; "Forced only" stays conditional, as the design says |
| F22 | partial | Room 2.8.5 and `BundledSQLiteDriver` verified; `randomblob` in a migration and nullable unique index validation not covered |
| F23 | verified (pattern) | `PeriodicWorkRequestBuilder` + `setRequiresStorageNotLow` already used (`RefreshScheduler`); TV-standby behaviour a gap |
| F24 | unverified | Not covered |
| F25 | unverified | Not covered |
| F26 | unverified | Not covered |
| F27 | unverified | Not covered |
| F28 | unverified | Not covered (only `BringIntoViewSpec` facts) |

Fact-sheet items tagged [memory] or [web-summary] that the design leans on:
- Fire OS SAF absence [web-summary]: the design handles it with the phone path, but see F17.
- Fire OS 6 global "Unknown sources" path [web-summary].
- The Fire OS ↔ API mapping [memory], also in plan §3.
- WorkManager's 15-minute minimum [memory]: irrelevant here.
- MediaStore no-permission writes on 29+ [memory]: the Downloads target and S6 depend on it.
- PBKDF2's API 26 floor [web-summary]: moot.
- Logcat self-read [memory]: not used. The consequence is that Media3 and system log lines reach the export only through `Media3LogBridge` → `BoxLog`. State this in §3.11.

---

## E. Verdicts on the proposer's deferrals and open questions

**Deferrals:**
- **D1** (per-channel Locked rows in unlocked groups) → v1.1: **accept**. The toast and the number-entry PIN path cover the gap. B1's guard must still treat an individually locked channel as locked.
- **D2** (Language): **accept**.
- **D3** (crash sending): **accept**, but ACRA is not configured at all today (B4). The "local crash reports in the export" promise needs WP-E to initialise ACRA local-only.
- **D4** (logos from the guide): **accept**.
- **D5** ("Still watching?"): **accept**. It is not in the M5 row.
- **D6** (store flavour): **accept**.

**Q1 (scope and the M5a/M5b split).**
- Keep all five WPs in M5, after the B4 ownership table and B2's +1.5 days. That makes it about 42 agent-days; expect 15–20 % overrun, as M4 had.
- **Critical path: WP-C.** It edits the two most-tested machines, the router, the parental SQL and three view models; B1, S2, S5, S10 and S11 land in it; and A, B and D merge after it touches the guide machine. WP-D is the second risk: an untested HTML/JS page, a server and a crypto codec in eight days.
- If a split is needed, the proposed M5a (A, B, C) / M5b (D, E) works. M5a then has no group manager (WP-D), so locks are set only at onboarding and from the Groups-panel menu. That is acceptable for a beta.

**Open questions:**
- **Q2:** yes (see D1).
- **Q3:** accept fail-open for **settings** only. Keep content filtered (B3/S8) until a new PIN exists.
- **Q4:** accept (owner rulings).
- **Q5:** accept for v1. Record the D-pad reservation in the player (S14).
- **Q6:** accept.
- **Q7:** accept. Add that developer registration is tied to signing keys, so the manifest key and the APK key custody belong in the same owner task.
- **Q8:** accept GitHub only, plus the S7 sequence field.
- **Q9:** accept.
- **Q10:** accept.
- **Q11:** accept.
- **Q12:** prefer a lower iteration count set by measurement (S15).
- **Q13:** accept.
- **Q14:** accept, but see S6. The real problem is API 30+.
- **Q15:** accept. On Fire TV, Search is a system key and never arrives (fact sheet §5.2), so the binding helps keyboards and TV-maker remotes only.

---

## F. What the lead must decide

1. **B3:** the adult-content state with no PIN. Choose the recommended `FILTERED_NO_PIN` plus PIN offers on AddSource and restore, or the design as written with an honest row text and a plan §2 amendment.
2. **B1:** accept the `TuneGuard` seam in the controller, and the rule that a relock on ON_STOP is never "mid-programme".
3. **B2:** owner, budget and `EffectTiming` for Always passthrough once F19 is verified. Correct the "silence watchdog" wording in the 2026-10-14 decision note.
4. **B4:** the file-ownership table; which commit-0 skeletons the lead writes (effects impls, Application hooks, `GuideViewModel` gate, DAO splits, `SettingsTree` registry).
5. **S1:** the settings-store rule "never write before the first read", and its effect on the 1 s startup bound.
6. **S2:** detached stacks for PIN-free entry routes.
7. **S3:** the router intercepts BACK while the editor is open, and the no-IME fallback text.
8. **S4:** keep-screen-on during phone setup, import and restore; the M3U upload cap.
9. **S6:** what the weekly backup honestly promises on API 30+; the "copy to phone" nudge.
10. **S7:** skip the cert check when signatures are null on API 25–27; per-build-type update keys; the manifest `sequence` field.
11. **S8 / Q3:** content stays filtered after a Keystore reset until a new PIN exists.
12. **S10:** amend `GuideLibrary`/`GuideSearch` to take `ParentalAccess` (six call sites).
13. **S14:** record the deviation from the owner spec: D-pad and OK reserved inside the player too.
14. **S15:** PIN and backup KDF iteration counts set from an emulator microbenchmark in commit 0.
15. **Q1:** one M5 with about 42 agent-days, or the M5a/M5b split. Critical path WP-C.
16. **N4 / N5:** the "daily at a set time" EPG interval and AFR below API 30. Accept as recorded deviations or restore them.
17. **N11:** whether the GUIDE and EXIT keys leave Settings.
18. **F17:** detect DocumentsUI by package, not by `resolveActivity` alone.
