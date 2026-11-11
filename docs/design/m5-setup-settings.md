# M5 "Setup, settings, parental, backup": design

Status: **proposed 2026-10-16; reviewed 2026-10-16; signed off 2026-10-16.** The adversarial review (`review/m5-design-review.md`: 4 blockers B1–B4, 16 should-fix S1–S16, 16 nits N1–N16, the fact cross-check and 18 lead decisions) and the lead's rulings are applied in place, each marked "(reviewed 2026-10-16, Bn/Sn/Nn: …)"; the final section **"Lead sign-off (2026-10-16)"** lists every ruling. The lead commits commit 0 (§1.9) and starts the five work packages. Nothing here is implemented.
Inputs: `00-master-plan.md` §1, §2 (Onboarding, Groups & parental, Backup, Diagnostics, Network, Distribution rows), §3, §5, §6, §8 (M5 row); `01-design-reference.md` (tokens, the 1 design px rule, "Things the prototype does not show"); `review/product-review.md` §2.1–§2.4 (S1–S6, S11–S17), **§4 in full**, §5 (#15, #17, #18, #20, #22, #23, #24), §7; `decisions/2026-10-14-settings-and-branding.md`; `decisions/2026-10-15-player-keys-and-quick-bar.md`; `design/m1-data-layer.md` (§2.1, §3.9, §3.10), `design/m2-playback-core.md` (§2.4, §3.1.1, §3.8, D22), `design/m3-guide.md` (§2.9, §2.10, §3.12, §3.18, §8), `design/m4-player-ux.md` (§0.10, §0.12, §0.13, §3.7, §8); `research/user-issues-and-mitigations.md`, `research/tivimate-feature-audit.md`, `research/android-tv-architecture.md` (distribution, SAF caveat); the code on `main` at `f4b289e` (Appendix A). Platform and library facts are collected in **§9 "Facts to verify"**: a parallel API fact sheet (`docs/design/m5-api-notes.md`) is being written and the reviewer must cross-check every item there. Anything verified neither in the code nor in §9 is marked **(verify)**.

This document is the contract between five implementers who will not see the conversation that produced it. **Must** means a reviewer rejects code that does otherwise. Every number (timeout, size, limit) is a default living in one named constant (Appendix B).

Units: every size is a **design px** (1 design px = window width ÷ 1920 through `BoxDesignScale`, `ui/.../theme/BoxDesignScale.kt`) unless it says "device px". Times: `…Ms` = milliseconds. Paths abbreviate `core/src/main/kotlin/app/thebox/core` as `core/...`, likewise `data/...`, `ui/...`, `app/...` (`app/src/main/kotlin/app/thebox`), `player/...`.

---

## 0. Key decisions at a glance

1. **Settings and onboarding are one pure `SettingsMachine` in `:core` (`app.thebox.core.settings`), composed into the M3 `GuideStateMachine` for `ScreenMode.SETTINGS` exactly as M4 composed the player machine for FULL.** The router, the one key sink and the single `Act` path are unchanged; in SETTINGS the guide machine hands every action to `settings.act(…)` first and acts itself only on what returns `false` (BACK at the settings root, which keeps the existing "return to FULL when opened from the quick bar" rule, `GuideStateMachineImpl.kt:293-296`). Onboarding is the same machine started on the route `SettingsRoute.FirstRun`. *Why:* settings are a deep tree of focusable rows with Back semantics, a five-key rule and remappable keys; Compose focus (`tv-material` lists) would be a second key path the button-mapping screen could not remap, and its reachability could not be proven on the JVM. The machine is table-tested by enumeration like the guide (155 rows) and the player (242 rows). *Rejected:* a new `ScreenMode.SETUP` (every exhaustive `when` on the mode changes for no behaviour gain); Compose `FocusRequester` navigation (untestable; text fields already need a focus island, below).
2. **Text entry uses the system IME inside one in-tree editor overlay; we build no on-screen keyboard.** While the editor is open the router passes every key **except BACK** to Compose (the existing SEARCH rule, generalised as `GuideStateMachine.textInputActive`); BACK becomes `SettingsEvent.EditorBack`, so a lost focus can never let BACK reach the Activity (reviewed 2026-10-16, S3). The editor owns a small focus island (field, URL chips, Done/Cancel) with M3's G5 D-pad Down workaround from the search field, and shows "No keyboard on this TV: use your phone" with the phone-page action when no keyboard app exists. The phone page is the answer to typing pain, and the URL chips (`http://`, `:8080`, `.com` …) plus `get.php` splitting cut what is left. Numbers and the PIN use our **numeric pad** (a 3 × 4 grid, D-pad friendly), never the IME. *Why:* an own QWERTY keyboard is a maintenance sink (layouts, languages, accessibility, voice) and the product review already ruled against it (S3). *Rejected:* own keyboard; the IME for the PIN (TV keyboards echo digits in clear and some learn them).
3. **Phone-assisted setup is an own minimal HTTP/1.1 server (`:core`, pure JVM, about 400 lines) bound to one private LAN IPv4, with a 128-bit token in the QR URL and a 6-digit code for a typed URL, one paired phone per session, a Host-header check against DNS rebinding, a custom-header check against cross-site posts, and a 10-minute idle / 30-minute absolute lifetime.** It is plain HTTP: HTTPS is impossible on a LAN IP without a certificate the phone trusts, and in-page encryption is impossible too, because browsers disable WebCrypto on insecure origins (§9 F7) and the page itself arrives over the same channel. The TV screen and the page footer say so. *Rejected:* NanoHTTPD (unmaintained since 2016; its multipart temp-file handling, exactly the upload path we need, has had CVEs, verify §9 F8); Ktor server (megabytes for six endpoints); a cloud relay (breaks "credentials never leave the LAN" and needs a server we do not run).
4. **All global settings live in one new `library.db` table, `app_setting` (one JSON document per domain), not in DataStore.** Per-playlist settings stay columns on `playlist` (they exist since M1–M3); per-channel settings stay in `channel_prefs`. *Why:* restore must replace settings, playlists and user data in **one transaction**, which two stores cannot give; DataStore would be a fourth persistence mechanism next to Room, raw sqlite and SharedPreferences; the domains are tiny (< 16 KB together) and read once at start. The M1/M2 KDoc that said "M5 binds DataStore" (`DataSettings.kt:13`, `PlaybackProviders.kt:98`) was a placeholder, not a plan decision. Every existing `*SettingsSource` interface keeps its shape; only the Hilt binding changes.
5. **Persisted settings load before the first tune.** `SettingsStore.ready` opens after one `SELECT * FROM app_setting` on IO, started in `Application.onCreate`; `GuideViewModel.startup()` awaits it (bounded by `SETTINGS_LOAD_TIMEOUT_MS`, 1,000, then defaults and a warning). *Why:* "On launch", "Open guide on", the clock form and "Up/Down in player" decide the first frame. (reviewed 2026-10-16, S1) The timeout only affects the first frame, never persistence: **nothing is written before a domain's first read completes**; `update()` calls made earlier are queued and replayed on the stored document when the (late) read lands, and a restore cancels pending writes for the domains it replaces.
6. **A settings change never rebuilds the player unless its row says so.** Every row declares an `EffectTiming` (§2.1): LIVE, NEXT_TUNE, REPREPARE (one `LiveSettingsApplier.apply()` when the user leaves Settings **by any route**, at most once per visit, only if such a row changed; the `:core` seam next to `ArchivePlayback` is implemented in `:app`, so an archive session is re-prepared at its playhead too; reviewed 2026-10-16, S9), NEXT_REFRESH, NEXT_EPG_IMPORT or RESTART ("Software audio fallback", a `PlayerFactory.create` parameter, `PlaybackControllerImpl.kt:809`). "Always passthrough" is REPREPARE: F19 (commit 0) found the runtime hook, an `AudioOutputProvider` read on every prepare (§2.12.7; lead ruling 2026-10-16, §1.9a). RESTART rows offer **Restart now** (N9). The row's description says so whenever the effect is not live.
7. **Button mapping is data, consumed by the existing router through a `RemappedKeyMap : KeyMap`** (defaults from `DefaultKeyMap` plus user overrides; an immutable snapshot swapped on change, so lookups stay O(1) HashMap reads with no allocation). `InputModule` binds it instead of `DefaultKeyMap()` (`InputModule.kt:32`). The only router change is a **capture hook** (`KeyRouter.capture(…)`) that hands the next physical key to the mapping screen with its `InputDevice` name; it is a mode of the one router, not a second routing path. D-pad, OK, Back, digits, volume, power and Home are reserved; long-press OK stays the context menu (five-key rule).
8. **(reviewed 2026-10-16, B3) Adult content is auto-*filtered* until a PIN exists, then auto-*locked*.** Four access states (§2.6): OPEN, LOCKED_AUTO, LOCKED_MANUAL_ONLY and **FILTERED_NO_PIN**. With no PIN and auto-lock on (the default), locked groups stay out of every mixed list (All channels, Favourites, Recent, Search, zap lists, number entry, previous channel, reminders), as today, but the Groups panel lists them with the `18+` badge and OK opens a card "This group is not protected. [Set a PIN] [Open anyway]"; the Parental row reads "On · not protected: set a PIN" in amber. The PIN offer appears in the onboarding groups review, after any playlist add or import from Settings that found adult groups, and after a restore that carried locks. This keeps the plan's "adult groups auto-locked" as "auto-filtered until a PIN exists" and removes M3 §8 Q12's trap (auto-locked adult groups unreachable forever). (B1) Every tune funnels through a **`TuneGuard`** in the controller path, so previous channel, the zap list and resume-after-standby cannot reach a locked channel. The PIN verifier (PBKDF2-HMAC-SHA256, 16-byte salt) is stored inside the Tink/Keystore AEAD as a `credential` row with the reserved ref `parental-pin`, never in plain prefs. Locked **groups** show in the Groups panel and the player's group column with the lock glyph (product "Show with lock") and ask for the PIN on OK. Recovery is "clear app data, then restore a backup": backups never contain the PIN (product §4.8 "Honest. No backdoor.").
9. **The PIN prompt is one modal layer owned by the guide machine** (`GuideUiState.pin`), drawn above every mode; the player and settings machines ask for it through a `PinGate` the guide machine injects and get the answer back by purpose. One prompt, one lockout counter, one place to test.
10. **Backups are versioned JSON Lines** (`.thebox` files: one header line, then one record per line), written and read as streams, keyed by `(playlist uid, stable_key)`; credentials ride in one record encrypted with AES-256-GCM under a key derived by PBKDF2-HMAC-SHA256 (iterations set by a commit-0 benchmark and stored in the file, S15) from the user's passphrase. Restore **merges** by default (the backup wins for the rows it carries; nothing else changes) or **replaces** user data on request. A new `playlist.uid` (random, backfilled by the migration) gives playlists an identity that survives a reinstall. Targets: the phone page (download and upload: the path that always works), a SAF document where a picker exists, and `Download/TheBox/` for the weekly automatic backup.
11. **The updater is sideload-only: a signed manifest (Ed25519, public key compiled in) on GitHub Releases; SHA-256, package name, version code and signing-certificate digest of the APK are checked before `PackageInstaller` is opened.** No silent installs, no downgrades (Android refuses them); rollback is roll-forward plus a `withdrawn` list. A store build, if one ever exists, compiles the updater out. Google's developer verification is a release-blocking risk M5 cannot engineer away; §3.12.6 states what we can and cannot do.
12. **Diagnostics are a support tool that never phones home.** No telemetry. The update check is the only outbound request not addressed to the user's own provider; it is a visible toggle and carries no identifier. Log export runs a second redaction pass and a test searches the bundle for every registered secret.
13. **Scope:** everything in the plan's M5 row is designed here. Six small items are deferred (accepted at sign-off, §1.1); the largest is per-channel "Locked" rows inside unlocked groups (v1.1). Size after the review: **44.5 agent-days** = commit 0 (2.5) + five parallel work packages of 8.0–9.5 days (§1.2). One M5, no split (lead ruling Q1). Every file has exactly one owner (§1.3a, B4).

---

## 1. Scope, sizing and work packages

### 1.1 Scope: in, and proposed deferrals

Every item of the plan's M5 row is in: onboarding with the phone page and QR, the settings tree (including Always passthrough, the stream-format label, the prefix rule and the button-mapping screen), the PIN, backup/restore, diagnostics and the updater. Also in, because earlier milestones handed them to M5: the "Fix unmatched channels" screen (plan §8 M1 row), Settings routes for the error cards' text steps (M4 §3.7, §7 item 8), the "Sources" server picker (M4 E23), persisting the M1–M4 in-memory settings, `OPEN_SEARCH` as a bindable action (M3 §8 Q3), EXIT stopping playback (M3 H-L3, M4 §7.1), the prefix-regex time bound (M3 G-L19), "Channel info" with the DST note (M4 §8 Q15), the external-player hand-off (product §4.4), "use this UA for the playlist" after the M2 one-shot VLC fallback (M2 D22, L5), the onboarding "Add as a single channel" offer for HLS manifests (M1 M24) and the shrink-guard text (M1 §3.6).

Proposed deferrals (nothing is dropped silently; the lead decides at sign-off):

| # | Item | Proposal | Why | What the owner loses until then |
|---|---|---|---|---|
| D1 | Per-channel "Locked" rows inside an **unlocked** group (product S14: title "Locked", mini player still, OK asks for the PIN) | v1.1 | Needs `GuideChannel.locked`, a locked pseudo-cell kind in the custom renderer and pager, zap-list skipping and number-entry changes across the M3/M4 machines: about 1.5 agent-days and a regression surface in the two most tested components. Groups, where adult content lives (`channel_group.is_adult`), get "Show with lock" in M5 | A channel the user locks individually inside a visible group disappears from the lineup until the PIN is entered (today's M3 predicate). The lock toast says so. Number entry on such a channel still opens the PIN prompt and tunes it (one extra lookup, in scope, §3.9.2) |
| D2 | Interface → Language | when a second translation exists | v1 ships English strings only; a language row with one choice is a dead row | Nothing (the app follows the system locale once translations exist) |
| D3 | About → "Send crash reports" | until a crash processor is chosen | Product §7.1: opt-in sending needs a privacy notice and a processor agreement; none exists. M5 keeps crash reports local (ACRA local-only, plan §3) and **includes them in the log export** | Crash reports reach us only when a user exports logs |
| D4 | TV guide → "Logos from: Playlist / Guide" (M3 §8 Q4) | v1.1 | Needs `EpgReader` icon lookups and a logo-source switch in `LogoCache`; cosmetic | Playlists without logos show names, not guide logos |
| D5 | "Still watching?" inactivity prompt (M2 Q8, review B4.9) | M6, with the soak test | Not in the M5 row; its timing needs soak data | Unattended streams keep running |
| D6 | A store build flavour | when a store listing is pursued | No store listing is planned (plan §1); a flavour doubles variants and CI task names now | Nothing for sideload users |

All six deferrals were **accepted at sign-off** (D3 with the note that ACRA's local-only set-up lands in commit 0 so WP-E's export can read local reports). Two product rows the proposal had changed silently are restored or recorded (reviewed 2026-10-16, N4/N5): the guide update interval keeps "Daily at a set time" (§3.3.2), and frame-rate matching is shown below API 30 with a note (§3.3.3). §3.3.13 maps every row of product §4.

### 1.2 Sizing (agent-days, one implementer each, JVM tests included; revised after the review)

| Area | Days | WP |
|---|---|---|
| Commit 0: contracts, migration v4, stubs, fakes, catalog, delegation wiring, **the shared skeletons of §1.3a** (effects hooks, blob API and key-reset flag, ACRA local-only, DAO stubs, `TheBoxApplication` hooks, `GuideViewModel` startup wait and first-run decision, `SettingsTree` registry, `AppRoot` slots, manifest), the KDF microbenchmark (S15), F16/F19 checks | 2.5 | lead |
| Settings machine (routes, panes, rows, inline choice, confirm, move mode, editor and number hand-off, detached routes S2, `onLeave` S9), `settings_keys.csv`, reachability proof | 2.5 | A |
| `SettingsStore` (queued writes until the first read, S1) and the persisted adapters, `LiveSettingsApplier` (S9), Restart now | 1.5 | A |
| Settings UI kit (two-pane shell, own toggle/radio/rows, editor overlay with G5 and the no-keyboard fallback, numeric pad, confirm card) | 2.0 | A |
| Plain pages: TV guide, Playback, Interface, Remote rows, Network (`BoxDns` on the base client), external player, Channel options page | 2.0 | A |
| **Always passthrough** (B2): forced encodings through the sink's capabilities, fallback toasts, "Back to Auto" in the audio picker, I-M5-27 | 1.5 | A |
| Onboarding S1, S3–S6, `SourceInputNormalizer`, `SetupResultMapper`, `StreamProbe` (UA probe through the broker), import progress, matching report, PIN offers after AddSource | 4.0 | B |
| Playlists pages: list, detail/account, edit source in place, refresh and report, per-playlist rows, prefix with preview, backup servers, delete | 2.0 | B |
| Fix unmatched channels and the mapping picker (incl. `EpgChannelSearchDao`) | 2.0 | B |
| PIN store, lockout, `ParentalSession` with the FILTERED_NO_PIN state (B3), PIN pad model and view, "not protected" card, settings lock | 3.0 | C |
| `TuneGuard` (B1) and interception in the guide/player machines, locked entries in the Groups panel and the channel list, menu items, footer hints from the key map, `ParentalAccess` through the library signatures (S10) | 2.5 | C |
| Button mapping: overrides store, `RemappedKeyMap`, router capture hook (S14), mapping pages | 2.5 | C |
| Error-card routes and one-tap actions; Sources picker | 0.5 | C |
| LAN server, pairing, page assets (HTML/JS/CSS), QR, phone setup screen S2, keep-screen-on, isolation and VPN hints | 3.0 | D |
| Backup codec, crypto, merge planner, restore preview, targets (phone, SAF by package detection, Download), weekly worker and the "keep a copy on your phone" nudge | 3.0 | D |
| Channels and groups management: group manager, hidden channels, hide rules (+ `rule_hidden` pass), favourites lists | 2.0 | D |
| About and the device panel, network test with verdict, `LogRing` (redacts throwables, S13) and the export bundle, licence texts | 3.0 | E |
| Updater: manifest with sequence, per-build-type keys, signature, download, verification, install flow, prompt card | 2.5 | E |
| Fake panel additions (update manifest and APK, DoH endpoint, adult category, `api_fault`), debug harness, instrumented suite I-M5-1…I-M5-30, integration pass | 2.5 | E |
| **Total** | **44.5** | |

Per WP: **A 9.5, B 8.0, C 8.5, D 8.0, E 8.0**. Elapsed: commit 0 (2.5) + parallel WPs (≈ 9.5, WP-A is the longest) + integration (2) + review (1) ≈ **15 working days**; expect a 15–20 % overrun as in M4. Risk critical path: **WP-C** (the two most tested machines, the router, the parental SQL and three view models), then WP-D (an untested page, a server and a crypto codec). One M5, no M5a/M5b split (lead ruling Q1).

### 1.3 Rules of engagement

As M4 §1.1, unchanged: commit 0 lands every contract of §2 verbatim, the migration, the build and manifest changes, the fakes, the shared skeletons of §1.3a and a `TODO("WP-x")` stub for every implementation class at its final path, bound in Hilt; afterwards `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` is green. **No WP edits a file owned by another WP, a contract file, a commit-0 skeleton or a build/manifest file**; contract problems go to the lead (additive amendments with defaults). Each WP owns the Hilt module that binds its classes. No `runBlocking`; no `BoxLog` of a URL, credential, PIN, passphrase or pairing token; no hard-coded user text (one strings file per WP). Models: Opus for WP-A (machine, passthrough), WP-B (setup error mapping and the probe), WP-C (machines and parental), WP-D (server and crypto) and WP-E's updater and integration; Sonnet may implement page builders, views and strings under this spec.

### 1.3a Owner of every edited or new file (reviewed 2026-10-16, B4)

**Commit 0 (lead) writes these and nobody edits them afterwards** (they delegate to interfaces the WPs implement):

| File | What commit 0 puts there | Implemented by |
|---|---|---|
| every contract file of §2 and every amendment of §2.12 | verbatim | – |
| `data/.../db/Migrations.kt`, `LibraryDatabase.kt`, entities `AppSettingEntity`, `HideRuleEntity`, `ChannelEntity`, `ChannelGroupEntity`, `PlaylistEntity` | v4 (§4.2) | – |
| `data/.../security/TinkCredentialStore.kt` | `SecureBlobStore` (`putBlob/getBlob/deleteBlob`) and the `pin_lost_to_key_reset` write in `wipe()` | – |
| `ui/.../guide/vm/GuideEffectsImpl.kt`, `ui/.../full/vm/PlayerEffectsImpl.kt` | the new effect methods as one-line delegations to `SettingsNavigator` (A), `ParentalEffects` (C), `ErrorRouteActions` (C), `UpdateActions` (E) | the named WPs, in their own files |
| `app/.../TheBoxApplication.kt` | hooks: `settingsStore.load()` first, `logRing.install()` (before anything logs), `crash.installLocalOnly()`, `updates.scheduleCheck()` | A, E, E, E |
| `app/.../crash/CrashConfig.kt` | ACRA local-only configuration (no sender), reports in `filesDir/acra` | read by WP-E's export |
| `ui/.../guide/vm/GuideViewModel.kt` (first edit) | startup awaits `SettingsStore.ready` and `settingsAttached` (S11), then the first-run decision (§3.4.1) | – (later edits: WP-C) |
| `core/.../settings/tree/SettingsTree.kt` | a registry; each WP registers its rows from its own `*Rows.kt` (row id, visibility, `EffectTiming`, section) | A, B, C, D, E |
| `ui/.../root/AppRoot.kt` (first edit) | slots: `ui.pin?.let { PinPadView(it) }`, `ui.update?.let { UpdateCard(it) }`, `SETTINGS → SettingsScreen` | – (later edits: WP-A) |
| DAO files with signatures and placeholder queries: `data/.../db/dao/AppSettingDao.kt` (A), `HideRuleDao.kt`, `HiddenChannelsDao.kt`, `GroupAdminDao.kt` (D), `EpgChannelSearchDao.kt` (B; `searchEpgChannels` does not exist today) | signatures | the named WP writes the real `@Query`s |
| the six `GuideLibrary`/`GuideSearch` call sites (S10): `GuideViewModel.kt:335`, `PlayerViewModel.kt:263, 389`, `SearchViewModel.kt:108`, `GuideEffectsImpl.kt:83, 212` | mechanical `access = …` argument | – |
| `GuideStateMachineImpl.kt` (first edit) | delegation wiring (§1.9 item 5) | – (later edits: WP-C) |
| `gradle/libs.versions.toml`, `*/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/file_paths.xml` | §6.6 | – |

**Work packages (disjoint):**

| WP | Owns (new files unless "edit") |
|---|---|
| A | `core/.../settings/state/*`; `core/.../settings/tree/{GuideRows,PlaybackRows,InterfaceRows,RemoteRows,NetworkRows,ChannelOptionsRows}.kt`; `core/.../net/BoxDnsPolicy.kt`; `data/.../settings/*` (store, persisted adapters, `SettingsDataModule`); `data/.../net/BoxDns.kt`, `data/.../net/HttpModule.kt` (edit: `.dns(boxDns)` on the base client only, N8); `app/.../settings/*` (`PersistedPlaybackSettings`, `LiveSettingsApplierImpl`, `RestartNow`, `SettingsBindingsModule`); `app/.../playback/PlaybackBindingsModule.kt` (edit); `player/.../controller/PlaybackControllerImpl.kt` (edit: `reapplySettings`, `handOffExternal`, `TuneGuard` calls); `player/.../engine/{BoxRenderersFactory,PassthroughGateAudioSink,PlayerFactory}.kt` (edit, B2); `ui/.../settings/{SettingsScreen,SettingsRowViews,TextEditorOverlay,NumberPad,ConfirmCard,SettingsUi}.kt`, `ui/.../settings/vm/*`; `ui/.../root/AppRoot.kt` (later edits), `ui/.../root/SettingsPlaceholder.kt` (delete); `app/.../external/ExternalPlayerLauncher.kt`; `strings_settings.xml` |
| B | `core/.../setup/*` (except `lan/`); `core/.../settings/tree/{SetupRows,PlaylistRows,MatchingRows}.kt`; `data/.../setup/{SourceSetupImpl,PlaylistSettingsRepository,SetupModule}.kt`; `data/.../db/dao/PlaylistDao.kt` (edit); `data/.../db/dao/EpgChannelSearchDao.kt` (queries); `data/.../repo/EpgMatchRepository.kt` (edit); `core/.../epg/store/EpgReaderImpl.kt` (edit, only if a name search is needed there); `player/.../probe/*`; `ui/.../setup/*` except `PhoneSetupScreen.kt`, `QrImage.kt`; `strings_setup.xml` |
| C | `core/.../parental/*` (except the contract); `core/.../input/{RemappedKeyMap,ActionCatalog,BindingRules}.kt`, `core/.../input/DefaultKeyMap.kt` (edit); `core/.../settings/tree/{ParentalRows,ButtonMappingRows}.kt`; `core/.../guide/state/GuideStateMachineImpl.kt` (later edits); `core/.../full/state/{PlayerStateMachineImpl,QuickBarModel,ErrorCardModel}.kt` (edit); `data/.../parental/*` (PIN store, session, `TuneGuardImpl`, `ParentalModule`); `data/.../input/KeyBindingStoreImpl.kt`; `data/.../db/dao/{ChannelDao,GroupDao,FavouriteDao}.kt` (edit: predicate only), `data/.../guide/{GuideQueries,GuideLibraryImpl,GuideSearchImpl}.kt` (edit); `ui/.../input/{KeyRouterImpl,InputModule}.kt` (edit); `ui/.../guide/vm/GuideViewModel.kt` (later edits), `ui/.../full/vm/PlayerViewModel.kt`, `ui/.../search/SearchViewModel.kt` (edit); `ui/.../parental/*`, `ui/.../settings/CaptureOverlay.kt`, `ui/.../guide/overlay/GroupsPanel.kt`, `ui/.../full/ChannelListPanel.kt` (edit); the `ParentalEffects` and `ErrorRouteActions` implementations; `strings_parental.xml` |
| D | `core/.../setup/lan/*`; `core/.../backup/*` (except the contract); `core/.../settings/tree/{ChannelsRows,BackupRows}.kt`; `data/.../setup/LanAddressFinderImpl.kt`, `data/.../setup/PhoneLinkImpl.kt`; `data/src/main/assets/phone/*`; `data/.../backup/*`; `data/.../rules/*`; `data/.../db/dao/{HideRuleDao,HiddenChannelsDao,GroupAdminDao}.kt` (queries); `data/.../repo/RoomMergeSink.kt` (edit); `ui/.../setup/{PhoneSetupScreen,QrImage}.kt`, `ui/.../settings/MoveModeRow.kt`, `ui/.../backup/*`; `strings_backup.xml` |
| E | `core/.../diag/*`, `core/.../update/*` (except contracts); `core/.../settings/tree/AboutRows.kt`; `data/.../diag/*`; `app/.../update/*` (incl. `InstallResultReceiver`, the `UpdateActions` implementation); `app/.../diag/LogRingInstaller.kt`; `ui/.../diag/*`, `ui/.../update/UpdateCard.kt`; `ui/src/main/assets/licenses/*` (FFmpeg LGPL with version and build-script tag, Tink, OkHttp, Media3, qrcodegen; N16); `tools/fakepanel/*`; `app/src/debug/.../GuideDebugReceiver.kt` (edit), `SettingsDebugProbe` (debug + release no-op); `app/src/androidTest/kotlin/app/thebox/settings/*`; `strings_diag.xml` |

The tables of §1.4–§1.8 describe each WP's contents; where they and this table disagree, **this table wins**.

### 1.4 WP-A: settings shell, store, plain pages and Always passthrough

Contents: the settings machine and its key table (§3.2), the store with queued writes (§2.2, S1), the persisted adapters (`PersistedGuideSettings`, `PersistedPlayerSettings`, `PersistedDataSettings` with the Auto past-days mapping (S12), `PersistedNetworkSettings`, `PersistedPlaybackSettings` in `:app`, which keeps `defaultUserAgent` and the test hooks out of the store), `LiveSettingsApplierImpl` (live re-prepare or `ArchivePlayback.resume()` at the playhead, S9), Restart now (N9), the UI kit (§3.1; **must not use tv-material `Switch`, `RadioButton` or `ListItem`**: fixed dp and a scale `graphicsLayer`, N7), the plain pages of §3.3, `BoxDns` on the base client (§3.13), the external player hand-off (§3.3.4), the Channel options page (§3.3.12) and **Always passthrough** (§2.12.7).
Tests (§6.1): `settings_keys.csv`, `SettingsReachabilityTest`, `SettingsBackLadderTest`, page-builder tables, `RoomSettingsStoreTest`, `ReapplyOnExitTest` (incl. archive and every leave route), `BoxDnsTest`, `EffectTimingCoverageTest`, `SimpleRowCountTest`, `ForcedPassthroughTest`.

### 1.4a WP-A notes (2026-10-16)

**Built.** `SettingsMachineImpl` (panes, route stack with parents, cursor memory by `RowId`, one-row lookahead, inline choice/multi choice, menu, confirm, number pad, move mode, editor and capture hand-offs, PIN gate, detached routes, `onLeave` once per visit; it also implements `SettingsShellLink`: `attachGuide`, `openFromFull`, `push/replace/pop/popTo/leaveToGuide/confirm`, `backHandler`, `shellEvents`, `editorBacks`). `RoomSettingsStore` (S1 queue-until-read, debounce, flush, export, import with write cancellation by generation). Domains in `state/SettingsDomains.kt` (`NetworkDomain` uses `Domains.NETWORK` + `NetworkSettings.serializer()`, so WP-B's `SetupNetworkDomain` resolves to the same document). Persisted adapters (`data/.../settings/PersistedAdapters.kt`, `app/.../settings/PersistedPlaybackSettings.kt`) bound in `SettingsDataModule` / `SettingsBindingsModule`; the in-memory bindings were removed from `DataModule`, `PlayerDataModule`, `GuideDataModule` (one line each) and `PlaybackBindingsModule`. `FirstRunDeciderImpl`. `WpaPages` (TV guide, Playback, Interface, Remote, Network) and `ChannelOptionsPage` + `ChannelOptionsPageSource` (`:app`). `LiveSettingsApplierImpl`, `RestartNow`, `SettingsLifecycle` (ON_STOP flush, EPG reschedule), `ExternalPlayerLauncher`. UI kit, AppRoot wiring, `SettingsPlaceholder.kt` deleted. `BoxDns` + `BoxDnsPolicy`. Always passthrough (`ForcedPassthroughOutputProvider` in `PassthroughGateAudioSink.kt`), controller `reapplySettings`, `handOffExternal`, `TuneGuard` calls.

**Seams for the other WPs.** Pages plug in as `SettingsPageSource` (`core/.../settings/state/SettingsPages.kt`) bound `@IntoSet` (WP-A declares the set in `SettingsUiModule`); a route nobody handles shows a placeholder page. User text of WP-A pages: `SettingsText` enum → `strings_settings.xml` (`ResourceSettingsTexts`). Machine conventions: `sections` empty = no sections pane (FirstRun, detached); `sectionCursor == sections.size` = the advanced toggle (`ADVANCED_ROW`); row indices run over `rows` then the paged list (`loaded` keyed by page number); a menu option `move` enters move mode; Action rows `capture:<actionId>:<SHORT|LONG>` arm capture and OK emits `Menu(row, "bind:<keyCode>")` (WP-C may replace this); ←/→ move the cursor on `FirstRun` (Welcome cards). WP-B's `SetupPageProvider`/`PageHost`/`PageAction` (merged after this WP started) map 1:1 onto `SettingsPageSource` + `SettingsPageNavigation`: the integration adapter publishes through `SettingsEvent.Page/Items`, maps `PageAction` onto `push/replace/pop/popTo/leaveToGuide/confirm`, `Notice` → `SettingsEvent.Notice`, `CreatePin` → `Section(PARENTAL)`, `onBack` → `backHandler`, `close` → `SettingsPageSource.closed`. WP-D's `SectionPages` maps the same way (`page`/`items` → the source, `onIntent` → its `PageAction`: notice → `SettingsEvent.Notice`, `open` → `push`, `reload` → rebuild; `invalidations` → `changes`); PhoneSetup draws WP-D's `PhoneSetupHost`, move mode WP-D's `MoveModeRow`. These adapters could not be compiled on this branch (WP-B/WP-D merged after it was cut) and are left to the integration pass. The store fails fast if two WPs declare one domain name with two types (today all agree).

**Deviations.** `guide.daily_hour` (Number) is the hour of "Daily at a set time" and runs as a 24 h period (`RefreshScheduler` is not WP-A's); `playback.audio_language` sets the first language only; "Forced only" subtitles not offered (F21); `network.timeouts` is two rows; `playback.restart_now` row (N9); `guide.fix_unmatched` is built on the guide page but registered by WP-B. Archive re-apply is `ArchivePlayback.seekTo(playhead, 0)` (a native seek inside the request keeps the old pipeline until the next re-request; a paused session is left paused. (amended 2026-11-10) Except a session paused in the local rewind buffer: it re-prepares at the pin now and stays paused (m6-timeshift §7.6).): an `ArchivePlayback.reapply()` amendment would make it exact. API client read timeout follows `network.read_timeout` (default 20 s, was 60 s); downloads keep a 120 s floor. Passthrough fallback text and Back to Auto (picker, stream info, error card) are WP-C's files.

### 1.5 WP-B: setup and playlists

Contents: §3.4 (onboarding, normaliser, result mapper, import progress, UA probe, groups review, matching report, the PIN offer card after AddSource imports), §3.6 (playlist pages, Fix unmatched). The probe acquires from `StreamBrokerRegistry`/`ConnectionBrokerCore.acquire(PROBE, ownerTuneId = PROBE_OWNER_ID (−2), waitMs)` (`PlaybackLogic.kt:32`, `Seams.kt:35`; S12: there is no `StreamConnectionBroker` type).

### 1.5a WP-B notes (2026-10-16)

**Built.** `core/.../setup/`: `SourceInputNormalizerImpl` (§3.4.3), `SetupResultMapper` (§3.4.4, plus `errorFromCode` for stored `refresh_log` codes and the §3.6.1 "reason · next step" line), `SetupText` + `SetupStrings` (every user text is a key; `:ui`'s `AndroidSetupStrings` maps each to `strings_setup.xml` with an exhaustive `when`), `SetupConstants` (Appendix B values), `SetupDraftStore`, the data ports `SetupPorts.kt` (`PlaylistAdmin`, `GroupsReviewSource`, `GuideMatching`), and `pages/` (Welcome, AddSource chooser, Xtream/M3U forms, ImportProgress with `ImportTracker`, GroupsReview + matching report, Playlists section, Playlist, PlaylistSource, FixGuide list and picker). `data/.../setup/`: `SourceSetupImpl` (+ `ImportWork` seam over WorkManager), `PlaylistSettingsRepository` (implements the three ports), `SetupModule`; `PlaylistDao` column setters; `EpgChannelSearchDao` over `EpgReader.findChannels` restricted to enabled sources. `player/.../probe/StreamProbeImpl`. `ui/.../setup/`: `AndroidSetupStrings`, `SetupUiModule` (+ `SetupPagesFactory`), `WelcomeScreen`. Rows: `PlaylistRows` (the §3.3.1 table, 7 simple), `MatchingRows` (`guide.fix_unmatched`), `SetupRows` empty (onboarding rows are not settings; ids start `setup.`).

**Seam for WP-A (not in §2).** `core/.../setup/pages/SetupPageContracts.kt`: `SetupPageProvider` (`handles`, `open`, `close`, `onIntent`, `onBack`, `loadItems`, `onFilePicked`), `PageHost` (`publish` → `SettingsEvent.Page`, `items` → `SettingsEvent.Items`, `act`) and `PageAction` (Push, Replace, Pop, PopTo, LeaveToGuide, Notice, Confirm, PickM3uFile, CreatePin, Reload). `SettingsViewModel` builds it with `SetupPagesFactory.create(viewModelScope)`. FirstRun: draw `WelcomeScreen(page, cursor)`; ← / → move the cursor over the four Link rows (default card `WelcomePage.defaultCard(hasKeyboard)`).

**Deviations.** No `SettingsRoute.MatchingReport`: the GroupsReview page turns into the report after Done (Back = Later). "Now: …" on picker candidates is one batched lookup per page build, not per focus (the machine reports no focus to pages). The probe checks `snapshot()` before `acquire(PROBE)` because the core's PROBE acquire pre-empts other owners' PLAYBACK; a tune taking the last slot between the two calls is a residual race. Progress waits 3 quiet ticks (1.5 s) after the refresh before calling the guide done. M3U form: no extra headers, catch-up mode or time shift fields (they are on the playlist page after the add); its guide links become USER EPG sources. "Add as a single channel" writes `filesDir/playlists/single-*.m3u` (the stream URL is in that private file, not in the Tink store). Restore backup on Welcome links to `SettingsRoute.Backup`.

**Amendments requested.** `PinPurpose.Create` (or a PARENTAL route focus) for `PageAction.CreatePin`; `SettingsRoute.MatchingReport(playlistId, firstRun)`; `M3uForm.fileName`. WP-A must not also register `guide.fix_unmatched` (registered in `MatchingRows`).

### 1.6 WP-C: machines, parental and button mapping

Contents: §3.8 (mapping), §3.9 (parental incl. `TuneGuard` and FILTERED_NO_PIN), §3.14 (error routes), the Sources picker (§3.6.2), the predicate (§4.3), the attach-order rule (S11) and the guide-machine edits listed in §1.3a.

### 1.6a WP-C notes (2026-10-16)

**Built.** Parental: `PinVerifier`/`Pbkdf2` (the `Mac("HmacSHA256")` RFC 8018 loop, S15; blob version ‖ salt ‖ iterations ‖ hash, constant-time compare), `ParentalPinStoreImpl` (lockout via the pure `LockoutPolicy`, counter in `parental_attempts`, BOOT_COUNT + elapsed, wall clock capped at the step), `ParentalSessionImpl` (three stay-unlocked modes, a timer never relocks mid-programme, app stop = last activity stopped, Open anyway, key-reset flag), `LockedChannelIndex` + `TuneGuardImpl` (an in-memory snapshot of the §4.3 lock clauses, rebuilt on IO on invalidation; `check` is one map lookup, never IO on main), the §4.3 predicate (`ChannelDao.PARENTAL_ACCESS`; the Boolean `PARENTAL` kept for M1–M4 callers as OPEN/LOCKED_AUTO), `GroupDao.panelGroups` (locked flag), `GuideQueries`/`GuideLibraryImpl`/`GuideSearchImpl` on `ParentalAccess`. Guide machine: the PIN layer (`PinPadModel`; every §3.9.2 trigger; the not-protected card; Easy-to-guess confirm; Lock/Unlock in both menus; Fix guide data / Channel options; "Lock settings" at every entry; relock moves a locked current group to All; N11; `OPEN_SEARCH`/`OPEN_SETTINGS`; footer hints from `KeyHints`). Player machine: locked groups in the channel list and the list's Lock through `pinGate`. `ErrorCardModel` routes (§3.14). Button mapping: `ActionCatalog`, `BindingRules`, `RemappedKeyMap` (bound in `InputModule`), `KeyBindingStoreImpl` (`remote` domain), the router's capture hook (S14), `CaptureFlow`, `CaptureOverlay`. Pages as builders: `ParentalRows`, `ButtonMappingRows` (+ `ParentalPageHost`, `MappingPageHost`, `ParentalStrings`). `:ui` glue: `ParentalGuideLibrary` (the session's access in every query, flows restart on `queryKey`/opened-anyway, Hide, D1 number entry), `GuardedPlaybackController` (tune / previous / archive ask the guard; a refusal opens the prompt), the zap list cleared on relock, `Stopped(LOCKED)` → prompt.

**Deviations.** (1) The two `GuideEffectsImpl` call sites (commit-0 skeleton) are fed through the library decorator, not edited. (2) Seams the contract lacks live in WP-C files: `ParentalHost` (the guide machine's `request`/`parentalAnswer`/`startPinSetup`, reached by cast), `ParentalActions`, `ParentalControls` (Open anyway, `watching`, `leftSettings`, lock writes), `PinMessage` codes in `PinPromptState.message` (the not-protected card = `NOT_PROTECTED`, `focusedKey` = button). (3) Player lock with no PIN and auto-lock off locks without creating a PIN first (the guide does create one). (4) Groups panel shows the padlock; the amber `18+` of FILTERED_NO_PIN needs the access in `GuideUiState`. (5) `OPEN_EXTERNAL` opens Settings → Playback until WP-A's launcher exists. (6) `ParentalDomain`/`RemoteDomain` are WP-C objects (WP-A must not redefine them); `ErrorRouteActionsImpl` holds a same-named `network` domain copy until WP-A's.

**Contract amendments requested.** `PickerChoice.Server` + `PlayerEffects.selectServer(index)` + the base-URL labels (the Sources picker, §3.6.2, is blocked: `QuickItem.SOURCES` not offered, the SERVER exclusion stays); `ErrorButton.BACK_TO_AUTO` (B2 card); `GuideToast` for lock feedback; `GuideEvent.TuneRefused(channel)` or `ParentalHost` on `GuideStateMachine`; `ParentalSession.openAnyway/watching`; route-specific labels for `OPEN_SETTINGS` in `ErrorText` (not WP-C's file).

**For the others.** WP-A: `PlaybackControllerImpl` calls `guard.check` before `startCycle` (zap neighbours skip refused targets; `previousChannelResult` → `Locked`; a refused `resumeAfterLifecycle` emits `Stopped(target = the refused identity, reason = LOCKED)`; the effects' path is already guarded); route `parental.*` and `mapping.*`/`binding.*` intents to the hosts above, embed `CaptureFlow` and draw `CaptureOverlay(state, flow.focusedButton, flow.testReport, flow.testMode)`; `SimpleRowCountTest` must count section pages only (`ButtonMappingRows` registers sub-page rows; the `parental.forgot` Info row is unregistered). WP-B: `EpgMatchRepository.unmatched(access)` → `channelDao.unmatchedAccess(access.ordinal)`; PIN offers through `ParentalHost.startPinSetup(false)`. WP-D: group-manager locks through `ParentalControls.setGroupLocked`; restore carries `ParentalDomain`/`RemoteDomain`. WP-E: I-M5-14/28/29 drive the prompt, `Stopped(LOCKED)` and the key-reset flag.

### 1.7 WP-D: phone page, backup and user-data management

Contents: §3.5, §3.7, §3.10.

### 1.7a WP-D notes (2026-10-16)

**Built (all files in the §1.3a row D).** `:core` `setup/lan/`: `HttpParser` (431 head > 8 KB or > 40 headers, 411 no length or any `Transfer-Encoding`, 400 raw non-ASCII/control bytes, 505), `LanTransport` seam + `SocketLanTransport`, `LanHttpServerImpl` (accept thread, 4 workers, 503 beyond, `Connection: close`, progress watchdog → 408), `PairingSession` (128-bit token + 6-digit code, single use, cookie bound to the phone's IP, 5 wrong → rotate once, second exhaustion stops, idle/absolute TTL on a clock seam), `PhoneApi` (421 Host, 403 `X-TheBox`/Origin, 401/403/410, CSP and the other headers, 64 KB JSON, uploads streamed to disk after the 2 × free-space check, downloads once to the paired phone), `LanPhoneLink` (the whole `PhoneLink` in pure JVM), `LanAddressFinder` + `LanAddressPolicy`, `PhoneScreenModel`. `backup/`: `BackupFormat` (Appendix B constants, `BackupDomain`, `BackupJson` with `t`), `BackupCodec` (writer/reader, limits, unknown types skipped, TRUNCATED/NEWER_SCHEMA/NOT_A_BACKUP), `BackupCrypto` (S15: RFC 8018 loop over `Mac("HmacSHA256")`, iterations and KDF name in the record, AES-256-GCM, AD = header line), `MergePlanner` (uid → source → new; column whitelists; `AutoBackupRetention`). `settings/tree/{ChannelsRows,BackupRows}.kt`: row specs, pure page builders, `BackupText`/`BackupTexts`, and the seams `SectionPages`, `PageAction`, `PhoneBackupFlow`, `DocumentPicker`. `:data`: DAO queries, `rules/` (`RuleMatcher`, `TimeBoxedCharSequence`, `RegexSafety`, `RuleHiddenPass`, `HideRulesImpl`, `ChannelsPagesImpl`), `RoomMergeSink` (pass at `finish` inside the merge transaction; inserts default `rule_hidden = 0`, the UPDATE/ON CONFLICT paths never list it, test-proved), `backup/` (exporter, three-pass restorer in one transaction incl. `importInTransaction`, storage, weekly worker + scheduler, `DocumentsUiDetector`, `BackupPagesImpl`), `setup/{LanAddressFinderImpl,PhoneLinkImpl}`, the phone page assets. `:ui`: `PhoneSetupScreen`, `QrImage`, `MoveModeRow`, `backup/{BackupTextsImpl,BackupUiModule (+ BackupDocuments),PhoneSetupViewModel (+ PhoneSetupHost)}`, `strings_backup.xml`.

**Deviations.** No per-group page (no route exists): Show/Hide is the item's OK, Lock/Unlock/Move/Reset its menu; Rename and "Hide all except…" are not offered. The backup/restore "flows" are stages of the Backup page (rows appear under Back up now / Restore), not routes. Downloads below API 29 without the storage permission fall back to app storage and say so (the permission prompt needs the activity). Phone restore: the passphrase is taken on the phone, Merge/Replace on the TV's RestorePreview. Lock writes go to `group_prefs.locked` directly (`GroupAdminDao.upsertLocked`).

**Needs from others (amendments).** (1) Lead/manifest: `<queries>` for `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` (or `<package>` for the two DocumentsUI names), else API 30+ never sees DocumentsUI and "To a file" stays hidden. (2) Lead/`TheBoxApplication`: call `AutoBackupScheduler.sync(store.flow(BackupDomain).value.weekly)` once after the store is ready (weekly is on by default; today it is only scheduled when the row is toggled). (3) WP-A: `SettingsEffectsImpl` injects `Set<@JvmSuppressWildcards SectionPages>` and delegates `loadPage/loadItems/onIntent` for the routes each `handles`, reloads on `invalidations`, follows `PageAction.open`; places `PhoneSetupHost` for `PhoneSetup` and hands its keys to `PhoneSetupViewModel.act`; draws `MoveModeRow` for `MoveState.row`; keys its store by domain name (WP-D uses `BackupDomain` and a by-name `guide` domain for `openGuideOn`) and excludes `ChannelsRows.NOT_COUNTED` from `SimpleRowCountTest`. (4) MainActivity owner: launch `BackupDocuments.intentFor(DocumentPicker.requests)` and answer `picked`. (5) WP-B: move an `M3uFileUploaded` file out of `cacheDir/phone/` before the screen stops the server. (6) WP-E: `PhoneLinkImpl.portOverride` for the debug route.

**Integration pass must verify on the emulator.** F16 (Downloads listing after reinstall, API 30/33: expected only this install's files; an unreadable listing shows "Send it from your phone"); the MediaStore display name keeps `.thebox` with `application/octet-stream`; the real phone flow (I-M5-9, I-M5-17); AES-GCM and the `Mac` loop on API 25 (JVM-tested only); the `PBKDF2` 50,000-iteration time on hardware (M6). **Regex safety on Android (finding).** Android's `java.util.regex.Matcher` hands ICU `input.toString()`, so `TimeBoxedCharSequence` only bounds a match on the JVM; on the device a catastrophic match cannot be stopped mid-way. WP-D therefore adds `RegexSafety` (refuses quantified groups containing a quantifier or an alternation, and back-references, as "too slow") and checks the 50 ms page budget after each match. The same applies to M3's prefix regex (G-L19), which is not WP-D's file: it should call `RegexSafety` too.
: diagnostics, updater, verification

Contents: §3.11, §3.12, §6.3–§6.5, the licence texts, the integration pass.

### 1.8a WP-E notes (2026-10-16)

**Built.** `:core` (pure, JVM-tested): `update/{UpdatePolicy (cadence, UpdateDomain, UpdateTimings), ManifestVerifier (+ SignatureVerifier, UpdateEndpoint, RunningBuild), ApkVerifier (+ Sha256Counter), UpdateCardModel, UpdateCardSource}`, `diag/{LogRingBuffer + CompositeLogSink (S13), NetworkTest (runner, NetworkVerdict, NetworkProbes, NetworkTests), ExportBundle (builder + RedactionAudit), DiagSources (+ StreamSampler), DebugOverrides}`, `AboutRows` (5 simple + `about.auto_check`). `:data/diag`: `LogRingImpl` (ring, 10 s file batches, `current.log`/`previous.log` at 1 MB, flush when the last activity stops), `DiagnosticsImpl` (device facts, test, export inputs incl. ACRA reports and every stored login as audit secrets), `NetworkProbesImpl` (system DNS, Cloudflare DoH, sockets, `player_api.php`/M3U head), `DiagSourcesImpl` (sync rows, export to phone/Downloads, reset), `TinkEd25519Verifier`, `LibraryWipe`/`MigrationCheck` (debug harness only). `:app/update`: `UpdaterImpl` (also `UpdateCardSource`), `ApkInspector`, `InstallResultReceiver` (runtime-registered, no manifest entry; an unanswered confirmation abandons the session), `UpdateActionsImpl`, `UpdateCheckSchedulerImpl` + `UpdateCheckWorker` (plain worker + entry point: `:app` has no androidx.hilt compiler), `UpdateEndpoints`/`ReleaseUpdateConfig`; debug: `DebugUpdateEndpoint` (`@IntoSet` override), `SettingsDebugProbe`, `GuideDebugReceiver.handleM5`. `:ui`: `diag/DiagPages` (About, Diagnostics, NetworkTest, SyncStatus, Licences page models + intents), `PlaybackStreamSampler` + `DiagUiModule`, `UpdateCard`, `strings_diag.xml`, licence assets (FFmpeg 6.0.2 LGPL-2.1 with the build-script tag, Apache-2.0 for Tink/OkHttp/Media3, qrcodegen MIT). Fake panel §6.4 complete (+ `ed25519.py`, `make_update_apk.py`, `run-instrumented.sh --update-fixtures`, an AC-3 clip). Instrumented suite I-M5-1…30 in `app/src/androidTest/kotlin/app/thebox/settings/`.

**Deviations.** Manifest `sequence`: equal is accepted (the contract KDoc says "rejects lower"; §3.12.2's ">" would refuse every daily re-check). The verdict has no "name does not exist" key: `SERVER_UNREACHABLE` with no args means it, with the port means "does not answer". `SLOW_CONNECTION` only when the platform's link estimate is below what the channel needs (the emulator reports 4 Mbit/s, F27). Step details are machine `key=value` (no user text in `:core`). The debug private key is never checked in: `tools/fakepanel/update_test_key` is gitignored; the panel otherwise signs with a per-run key that the suite registers (`update_key`). Debug links ride on the `guide` host (the debug manifest lists no M5 hosts). `wipe` uses Room `clearAllTables` + the parental/guide-session prefs; `keystore-reset` replays `TinkCredentialStore.wipe()`'s steps. The SAF "To a file" export target is left to WP-D's target code (export offers phone and Downloads). I-M5-10 runs in two invocations (`m5_phase=1|2`, APKs kept installed); I-M5-9's second client IP is JVM-only (WP-D).

**Integration pass must:** (1) WP-A's `SettingsEffectsImpl` collects `DiagPages.page(route)` for `DiagPages.handles(route)` and routes intents to `DiagPages.onIntent` (`DiagOutcome.Notice` → `SettingsEvent.Notice`, `Open` → push the route); the About header Info row is drawn with the wordmark. (2) WP-C: `GuideViewModel` collects `UpdateCardSource.card` → `GuideEvent.Update`, toasts `updatedNotice`; `GuideStateMachineImpl` sets `ui.update` (GUIDE only, never over the PIN prompt or reminder; keeps `focused`) and routes keys through `UpdateCardModel.onAction`. (3) WP-C/WP-D read `DebugOverrides.clockOffsetMs`/`phonePort`. (4) WP-A may fold `UpdateDomain` and the `network` domain object into its registry (same names). (5) Run the suite: `run-instrumented.sh --update-fixtures` for `UpdaterTest`, `--synthetic 2000` for `ChannelManagementTest`, catalog mode otherwise; option ids in the tests are the enum names of the stored fields, row discovery is by kind/route (align with WP-B ids where a text match is used).

**Contract amendments requested:** `VerdictKey.NAME_NOT_FOUND` (append); the debug manifest's `GuideDebugReceiver` filter gains hosts `settings, wipe, clock, update, phone, keystore-reset`; optionally `androidTest` Compose test deps for `performTextInput` (the suite uses `sendStringSync`).

**Owner decisions (resolved 2026-11-08, `docs/decisions/2026-11-08-release-pipeline.md`):** were Q8 repository (`ReleaseUpdateConfig.UPDATE_REPO = null`: release updater disabled) and Q7 release manifest keys (`UPDATE_PUBLIC_KEYS_HEX` empty; offline custody of the release keystore and the manifest key). Now: `UPDATE_REPO = "shane9b3/the_box"`, the current and next manifest keys compiled in, releases cut on the owner's Mac by `tools/release/release.py`.

### 1.9 Commit-0 list (lead)

1. **Contracts** §2.1–§2.11 verbatim (new files under `core/.../settings`, `setup`, `setup/lan`, `parental`, `backup`, `diag`, `update`, `net`, and `core/.../input/KeyBindingContracts.kt`).
2. **Amendments** §2.12: `InputContracts.kt`, `core/.../guide/GuideStateContracts.kt` (S12: the file is in `guide/`, not `guide/state/`), `GuideFeedContracts.kt` (incl. the `ParentalAccess` signatures, S10), `PlayerContracts.kt`, `PlayerSettings.kt`, `PlaybackApi.kt`, `PlaybackContracts.kt`, `ui/.../input/KeyRouter.kt` (in `:ui`), `DataContracts.kt` (secure blobs), `core/.../catchup/archive/LiveSettingsApplier.kt` (S9) and `core/.../playback/TuneGuard.kt` (B1).
3. **Migration** `library.db` v3 → v4 (§4.2), hand-written like `MIGRATION_2_3`, with a 3→4 `MigrationTest` in `:data` and the exported schema JSON.
4. **Build** (§6.6): `io.nayuki:qrcodegen` (version verify §9 F9). `okhttp-dnsoverhttps` 5.5.0 is already a `:data` dependency (fact sheet §0; N8). No DataStore. Manifest additions.
5. **Delegation wiring** in `GuideStateMachineImpl`: the `settings` property (attached like `full`; S11: while `settings == null` in SETTINGS the machine waits and only BACK on FirstRun acts (exit)), `act` → `settings.act(a)` in SETTINGS, `settings.onLeave()` on **every** exit from SETTINGS (S9), `textInputActive`, the `pin` layer's first-refusal slot (body WP-C), no-op branches for the new events, and `GuideEvent.OpenSetup` → mode SETTINGS on `FirstRun`. Router: `textInputActive` passes every key except BACK (§2.12.5).
6. **Shared skeletons** of §1.3a.
7. **Stubs** for every class of §1.3a's WP rows, bound in Hilt. The persisted adapters' stubs delegate to the existing in-memory classes, so the app runs unchanged after commit 0.
8. **Fakes** in `core/src/testFixtures/kotlin/app/thebox/core/settings/fakes/`: `FakeSettingsStore`, `RecordingSettingsEffects`, `FakePinGate`, `FakeParentalSession`, `FakeTuneGuard`, `FakeKeyBindingStore`, `FakeLanTransport` (in-memory socket pairs), `FakeSecureBlobStore`, `FakeLiveSettingsApplier`, `SettingsFixtures` (two playlists, 40 groups of which two adult, 2,000 channels, 30 hidden rows, three favourites lists, one reminder per playlist).
9. **Measurements before WPs start:** (S15) a PBKDF2 microbenchmark on `TheBox_TV_API30` sets `PIN_KDF_ITERATIONS` and `BACKUP_KDF_ITERATIONS` (§3.9.6, §3.10.3); (S6) F16 on API 30 and 33 emulators (write to `Download/TheBox`, uninstall, reinstall, query); (B2) F19 (the Media3 1.11.1 capabilities hook) decides `playback.audio_output`'s `EffectTiming`.
10. **`SettingsPlaceholder.kt`** stays until WP-A's screen merges; WP-A deletes it.

### 1.9a Commit 0 notes (2026-10-16)

**Written.** Contracts §2.1–§2.11 verbatim (`core/.../settings`, `setup`, `setup/lan`, `parental`, `input/KeyBindingContracts.kt`, `backup`, `diag`, `update`, `net`) and every §2.12 amendment; `core/.../catchup/archive/LiveSettingsApplier.kt`; `core/.../playback/TuneGuard.kt`. Seams the design names but does not define, written as commit-0 contracts: `SettingsNavigator` (A), `ParentalEffects` (C), `ErrorRouteActions` (C, `core/.../full/`), `UpdateActions` and `UpdateCheckScheduler` (E, `core/.../update/UpdateHooks.kt`), `LogRing` (E, `core/.../diag/LogRing.kt`); `ui/.../root/EffectDelegates.kt` bundles the four effect delegates for the two view models. `library.db` v4 (`MIGRATION_3_4`, `4.json`), `AppSettingEntity`, `HideRuleEntity`, the entity columns, the five DAO files. `SecureBlobStore` on `TinkCredentialStore` and `KeyResetFlag` (`PrefsKeyResetFlag`: `parental_attempts` / `pin_lost_to_key_reset`, written first in `wipe()`). The skeletons of §1.3a, the `SettingsTree` registry with one empty `*Rows.kt` per WP already registered, stubs bound in each WP's own module (`SettingsDataModule`, `ParentalModule` (also `KeyBindingStore`), `SetupModule`, `ProbeModule`, `BackupModule` (also `PhoneLink`), `DiagModule`, `UpdateModule`, `SettingsBindingsModule`, `SettingsUiModule`, `ParentalUiModule`, `ErrorRoutesModule`), fakes under `core/src/testFixtures/.../{settings,parental,setup,backup,update,diag}/fakes/` (plus `RecordingSettingsMachine`), `tools/kdfbench/KdfBench.java`. Tests: `SettingsContractsTest` (access truth table, SQL ordinals, `queryKey`, legacy access, EffectTiming list, `sectionOf`, registry, encodeDefaults, PlayerSettings and key-override round trips, backup serial names, KDF constants, fixtures), `GuideSettingsAttachTest`, `KeyRouterTest.textInputActive…`, `migrate3To4…`, three blob/flag tests in `TinkCredentialStoreTest`.

**ACRA.** Verified: `acra-core` 5.14.2 was a dependency and `ACRA.init` was never called, so crashes were not recorded. `app/.../crash/CrashConfig.kt` now initialises it with a `SimplePluginLoader` holding only `LocalFileSender` (redacted JSON in `filesDir/acra`, newest 10 kept), no logcat/device id/shared preferences/dumpsys fields. ACRA with no sender would add its `NullSender` and delete the report, hence the local sender. ACRA delivers stored reports in its own `:acra` process; `TheBoxApplication.onCreate` returns early there after `installLocalOnly`. An R8 keep rule covers the reflective factory.

**KDF (S15).** `PIN_KDF_ITERATIONS = 10_000`, `BACKUP_KDF_ITERATIONS = 50_000` (in `ParentalContracts.kt` / `BackupContracts.kt`, figures in §3.9.6/§3.10.3 and Appendix B). Measured on `TheBox_TV_API30` (x86, API 30), which was already running (uptime 1 h 12 min, idle), not cold-booted as asked; the host was under build load, so the first run was discarded and two runs are reported. The design's `Mac` loop is slow on Android (Conscrypt JNI per call); a pure-Java HMAC-SHA256 would allow 50,000 / 400,000 inside the same budgets. **Lead decision:** keep the `Mac` loop with these counts, or adopt the pure-Java variant (and raise the constants) before WP-C/WP-D implement it. The counts are stored with the blob and in the file, so a later change needs no migration.

**F10:** verified on a throwaway API 25 emulator (no `platforms/android-25`; the API 25 system image exists): `PBKDF2WithHmacSHA256` throws `NoSuchAlgorithmException`; on API 30 it exists (BC 1.61). **F19:** see §2.12 item 7: the hook is an `AudioOutputProvider` and it is runtime-switchable at the next re-prepare, so `playback.audio_output` is REPREPARE (the lead confirms and tells WP-A; §0.6 and the sign-off still say RESTART "unless F19 shows a runtime hook"). **F9:** qrcodegen 1.8.0, MIT (pom), class-file major 52 (Java 8), jar has a `module-info.class`. **F22:** `randomblob`/`hex` and the nullable unique `uid` index pass `runMigrationsAndValidate` under Robolectric's `AndroidSQLiteDriver` (the repo's JVM pattern); the bundled-driver on-device run stays I-M5-22. **F16** (Downloads after reinstall, API 30/33) was not measured in commit 0; it stays with WP-D before the Downloads lister.

**Deviations, and why.**
- `TuneGuard`/`TuneVerdict` are in package `app.thebox.core.playback` (the file path §2.6 and §1.9 name); `TuneGuard.ALLOW_ALL` added.
- `SettingsStore.load()` added (the Application hook needs it; §2.2 has no such member). `SettingsTimings` holds the three Appendix B settings constants.
- `QuickItem.SOURCES` sits **before** `SETTINGS`, not after: QuickItem is never persisted and M4's tested rule "display order is the enum order, Settings always last" must keep holding.
- `PreviousResult` is new, and `previousChannel()` keeps its Boolean: `PlaybackController.previousChannelResult()` has a default that maps the Boolean (never `Locked`) until WP-A overrides it; changing the M2 signature would touch every caller.
- `GuideEffects` gains the four §2.12 methods with default bodies, `PlayerEffects` gains `openSettings(route)`, `errorAction(button, card)` and `setChannelLocked` (not listed in §2.12 item 4 but needed for the delegations of §1.3a), `PlayerEvent` gains `Parental`, `PinResult`, `KeyHints`, and `PlayerStateMachine` gains `var pinGate` (§0.9: injected by the guide machine), all additive with defaults.
- `FakeSecureBlobStore` is not in `core` testFixtures (`SecureBlobStore` is a `:data` contract `:core` cannot see); `:data` tests use the real `TinkCredentialStore` over the fake AEAD.
- `EpgChannelSearchDao` is a plain class, not a Room DAO: EPG channels live in `epg.db`.
- `ErrorRouteActionsImpl` is in `:app` (`app/.../errors/`): it needs `:data` and `:player`, which `:ui` cannot see.
- The persisted-adapter stubs of §1.9 item 7 are not written: the M1–M4 in-memory settings stay bound where they are, and WP-A moves the bindings in `SettingsDataModule`.
- The first-run decision (§3.4.1) is a stub returning null (always the guide): it needs the `setup` `SettingsDomain` object (WP-A's state package) and a playlist count. Someone must own filling it in; `GuideViewModel` later edits are WP-C's.
- S11 interpretation: while `settings == null` in SETTINGS the guide machine consumes every key except BACK; BACK on FirstRun exits, and BACK elsewhere still leaves Settings (M4), so a missing machine can never trap the user. `GuideViewModel.startup()` bounds both waits (store ready, settings attached) by one `SETTINGS_LOAD_TIMEOUT_MS`.
- N11 (OPEN_GUIDE leaves Settings, EXIT opens the exit dialog) is not wired: with the stub machine declining everything it would change today's behaviour. It belongs to WP-C's first `GuideStateMachineImpl` edit.
- Existing tests changed only mechanically: the explicit `access` argument (`unlocked = true/false` → `OPEN`/`LOCKED_AUTO`), `keys.csv`/`player_keys.csv` rows for the two new actions, the v4 table/index list in `LibraryDatabaseTest`, the quick-bar order, and exclusions of the not-yet-offered M5 menu items, error buttons and the SERVER picker from the five-key reachability sets (WP-C removes them when it offers them).

**For the WPs.** Labels for the appended enum values are in `ui/src/main/res/values/strings_m5_enums.xml` (WPs may reword them). The lock menu items need `GuideEffects` members that do not exist yet (`ParentalEffects.setChannelLocked/setGroupLocked` exist): WP-C asks the lead for that amendment. `RoomMergeSink` does not list `rule_hidden`, so inserts get the default 0; WP-D must make sure its upsert path keeps an existing value. Rows inserted with raw SQL and no `uid` stay legal (NULLs never collide in the unique index), but `PlaylistEntity` always generates one.

**Lead rulings on commit 0 (2026-10-16).**
- **KDF (S15):** keep the `Mac("HmacSHA256")` loop with `PIN_KDF_ITERATIONS = 10_000` and `BACKUP_KDF_ITERATIONS = 50_000` for M5. No hand-written HMAC in this milestone; the PIN's protection is the attempt counter and the Keystore-wrapped AEAD, the backup's is a passphrase of 8+ characters. The pure-Java HMAC-SHA256 variant (50,000 / 400,000 in the same budgets, RFC 4231 + RFC 7914 vectors) is an **M6 hardening item**, re-measured on the Fire TV Stick; the counts are stored with the blob and in the file, so raising them later needs no migration.
- **F19 (B2):** confirmed, `playback.audio_output` is **REPREPARE** for every value; §0.6, the row table, §3.14 and the sign-off are amended above. RESTART stays for `playback.ffmpeg` and `about.reset` only. WP-A builds the `ForwardingAudioOutputProvider` subclass of §2.12.7.
- **First-run decision:** extracted to the `:core` seam `settings/FirstRunDecider.kt` (`suspend fun route(): SettingsRoute?`), injected into `GuideViewModel` (default `NONE` in tests), stub-bound in `SettingsDataModule`. **WP-A** implements `data/.../settings/FirstRunDeciderImpl.kt` (the `setup` domain + `PlaylistDao.all()`) and binds it; `core/.../settings/state/SetupDomain.kt` seeds the `setup` `SettingsDomain` (WP-A's file; WP-B writes `SetupState.completed`/`lastAddedPlaylistId` through it at import stage 3 and after a restore, WP-C writes `pinOfferedFor`). `GuideViewModel` is not edited for this again.
- **Lock / Unlock:** `GuideEffects.setChannelLocked(channel, locked)` and `setGroupLocked(entry, locked)` added with default bodies; `GuideEffectsImpl` delegates to `ParentalEffects`. WP-C dispatches them from the two menus.
- **Test exclusions:** each WP removes the exclusions of the menu items, error buttons and the SERVER picker it starts offering (five-key reachability sets). **`rule_hidden`:** WP-D (`RoomMergeSink` owner). **F16:** WP-D before the Downloads lister. **N11:** WP-C's first `GuideStateMachineImpl` edit.

### 1.10 Integration sequence

Merge order **A, C, B, D, E**. A gives the shell every page renders in; C changes the guide/player machines and the router (it depends on commit 0 only); B and D add pages and data; E last. WP-E runs the integration pass on `TheBox_TV_API30` against the fake panel (§6.3), with the M2–M4 suites as regression (one class per `run-instrumented.sh` call, M3 §5.11). Then two code reviewers split `:core`+`:data`+`:player`+`:app` and `:ui`+tests, and the gates of §6.7 are checked.


### 1.11 Integration results (2026-10-16)

Integration of commit 0 and WP-B, WP-D, WP-A, WP-C, WP-E on `main` (base `c9ba4ae`), one integrator, uncommitted. Device: API 30 TV emulator `TheBox_TV_API30` (x86, cold-booted with `-gpu host -no-snapshot-load -feature -Vulkan,-GLDirectMem,-HasSharedSlotsHostMemoryAllocator,-HardwareDecoder,-GLDMA,-GLDMA2`, clock in sync with the Mac), fake panel through `tools/fakepanel/run-instrumented.sh`, one class per call (`--synthetic 2000` for `ChannelManagementTest` and the M3 classes, `--update-fixtures` for `UpdaterTest`, M4 classes with `--synthetic 2000 --set player_fixtures=true --set timeshift_mode='"duration"'`, I-M5-10 as two invocations `m5_phase=1|2` with the APKs kept). Every number is an **emulator** number: proxies for M6's hardware gates. `range_requests_seen == 0` held in every `@After` (every I-M5 class, every regression class).

**Hosting (work item 1).** The pages of WP-B, WP-C, WP-D and WP-E are hosted in WP-A's shell through adapters in `ui/.../settings/vm/SettingsPageAdapters.kt` (built per settings machine by `SettingsViewModel`, after WP-A's own `Set<SettingsPageSource>`): `SetupPagesSource` (WP-B's push-model `SetupPageProvider`; its `PageAction`s map onto `SettingsShellLink`, `PickM3uFile` → the activity's document picker, `CreatePin` → `PinPurpose.CreatePin`), `SectionPagesSource` (each of WP-D's `Set<SectionPages>`; `invalidations` → `changes`, `PageAction.notice/open`), `ParentalPagesSource` + `MappingPagesSource` (WP-C's `ParentalRows`/`ButtonMappingRows` builders over `ParentalPageHost`/`MappingPageHost`; Set/Change PIN → `PinPurpose.CreatePin`; Capture/Test a key → `SettingsShellLink.startCapture`, Save → `KeyBindingStore.bind`), `DiagPagesSource` (WP-E's one `Flow<PageModel>` per route, collected while the route is open; `DiagOutcome.Notice/Open`), and `PhoneSetupBridge` (below). `SettingsMachineImpl` embeds WP-C's `CaptureFlow` (WP-A's provisional `capture:`/`bind:` rows stay as a generic path for its key-table tests and run through the same flow); `SettingsScreen` draws `WelcomeScreen` on FirstRun, `PhoneSetupHost` on PhoneSetup (keys through `SettingsShellLink.keyHandler` → `PhoneSetupViewModel.act`), `CaptureOverlay(state, captureButton, testReport, testMode)`, `MoveModeRow` for the lifted row (shown at its target index), and the wordmark on About's header row. `MainActivity` launches `BackupDocuments.intentFor(DocumentPicker.requests)` and WP-B's M3U pick (persisting the read grant) and owns the phone page's view model. Duplicate domain objects now point at WP-A's (`SetupNetworkDomain`, `NetworkProbesImpl`, `DiagPages`, `ErrorRouteActionsImpl` network; `ChannelsPagesImpl.GUIDE`); `ParentalDomain`/`RemoteDomain` stay WP-C's (no duplicates existed). `GuideViewModel` forwards `UpdateCardSource.card` (`GuideEvent.Update`) and toasts `updatedNotice`; `GuideStateMachineImpl` shows `ui.update` in GUIDE only, never over the PIN prompt or a reminder, and routes keys through `UpdateCardModel.onAction`. `DebugOverrides` is read by the PIN lockout clock (`AndroidLockoutClock`, `ParentalEffectsImpl.clock`, the 1-hour timer tick, the pad's countdown), the LAN session lifetime (`PhoneLinkImpl`) and the phone port (`LanPhoneLink`).

**Contract amendments (additive, with defaults; work item 3).**

| Amendment | File | Why |
|---|---|---|
| `SettingsRoute.MatchingReport(playlistId, firstRun)`; WP-B's report moved to `MatchingReportPage`, GroupsReview's Done replaces itself with it | `SettingsContracts.kt`, `SettingsTree.kt` (section), `SettingsMachineImpl.parentOf`, `GroupsReviewPage.kt`, `SetupPages.kt` | WP-B request; Back on the report is Later, the route is addressable |
| `PageModel.focus: RowId?` (cursor moves when the value changes from the previous version); WP-B's forms set it to the result card's primary button | `SettingsContracts.kt`, `SettingsMachineImpl.onPage`, `PageSupport.kt`, `SourceFormPages.kt` | I-M5-4 "Use Xtream login instead focused"; a new card takes the focus |
| `PinPurpose.CreatePin(change)`; the guide machine starts create (or verify + create) and answers `SettingsEvent.PinResult` | `ParentalContracts.kt`, `GuideStateMachineImpl.request/grant`, `PlayerStateMachineImpl` (exhaustive branch) | One PIN entry for WP-B's offer and WP-C's Parental page without casts |
| `GuideStateMachine.parentalHost` (impl returns itself) | `GuideStateContracts.kt`, `GuideStateMachineImpl`, `GuideViewModel`, `PlayerViewModel` | Replaces WP-C's `machine as? ParentalHost` casts |
| `ParentalSession.openAnyway(source)`, `watching(identity)` with no-op defaults | `ParentalContracts.kt` | WP-C request (`ParentalSessionImpl` already implemented both) |
| `M3uForm.fileName` | `SetupContracts.kt`, `SourceFormPages.kt`, `SourceSetupImpl.kt` | The picked document's display name becomes the playlist's source label |
| `PickerChoice.Server(index, label)`, `PlayerEffects.selectServer`, `TrackMenu.servers/server`, `PlaybackController.servers`/`selectServer`, `ServerList`, `ResolvedStream.baseHosts`; Sources in the quick bar with ≥ 2 servers; the SERVER exclusion removed from `PlayerFiveKeyReachabilityTest` | `PlayerContracts.kt`, `PlaybackApi.kt`, `PlaybackContracts.kt`, `StreamUrlBuilder.kt`, `StreamUrlResolverImpl.kt`, `PlaybackControllerImpl.kt`, `QuickBarModel.kt`, `PlayerStateMachineImpl.kt`, `PlayerViewModel.kt`, `PlayerEffectsImpl.kt`, `PlayerText.kt` | The Sources picker (§3.6.2, M4 E23); `selectServer` re-resolves the same cycle on that base, the guard still applies, the base is kept as active once it plays |
| `NextStep.BACK_TO_AUTO`, `ErrorButton.BACK_TO_AUTO`; the controller prepends it to AUDIO_OUTPUT_FAILED while "Always passthrough" is on; `ErrorRouteActionsImpl` sets `playback.audioOutput = AUTO` and re-prepares; `PlaybackNotice.PassthroughDisabled(mimeType, forced)` and the toast names the way back | `PlaybackContracts.kt`, `PlayerContracts.kt`, `PlaybackControllerImpl.enrich`, `ErrorCardModel.kt`, `PlayerStateMachineImpl.errorButton`, `ErrorRouteActionsImpl.kt`, `PlaybackApi.kt`, `PlayerText.kt`, `ErrorText.kt` | B2 one tap (§3.14); toasts here carry no buttons, so the toast text names Settings → Playback → Audio output → Auto |
| `GuideToast.CHANNEL_LOCKED/UNLOCKED, GROUP_LOCKED/UNLOCKED, LOCKED_NOT_PROTECTED` | `GuideStateContracts.kt`, `GuideStateMachineImpl.lockToast`, `Toasts.kt` | Lock feedback from the guide's menus (B3 wording without a PIN) |
| `ErrorText.button(button, route)`: OPEN_SETTINGS labelled Edit login / Account details / Change user agent / Playlist settings / Connection test | `ErrorText.kt`, `PlayerErrorCard.kt` | Route-specific labels (WP-C request) |
| `VerdictKey.NAME_NOT_FOUND` (both resolvers failed) | `DiagnosticsContracts.kt`, `NetworkTest.kt`, `DiagPages.kt` | WP-E request (was SERVER_UNREACHABLE with no args) |
| `ArchivePlayback.reapply(playheadMs)` (default `seekTo(playhead, 0)`; `ArchivePlaybackImpl` re-requests at the playhead), used by `LiveSettingsApplierImpl` | `ArchiveContracts.kt`, `ArchivePlaybackImpl.kt`, `LiveSettingsApplierImpl.kt` | Exact archive re-apply (WP-A §1.4a) |
| `SettingsUiState.captureButton/captureTest/captureReport` | `SettingsMachine.kt` | The overlay's button focus and Test-a-key report change without the `CaptureState` changing |
| `SettingsShellLink.keyHandler`, `startCapture`, `captureSaved`, `requestPinSetup` (defaults) | `SettingsPages.kt` (WP-A seam) | Phone page keys, WP-C's capture flow, the PIN entry |
| Debug manifest hosts `settings, wipe, clock, update, phone, keystore-reset`; release manifest `<queries>` for `OPEN_DOCUMENT` / `CREATE_DOCUMENT` | `app/src/debug/AndroidManifest.xml`, `app/src/main/AndroidManifest.xml` | WP-E / WP-D requests (the TV image has no DocumentsUI: "To a file" stays hidden, correctly) |
| `TheBoxApplication` syncs `AutoBackupScheduler` once after the store is ready and the first frame drew | `TheBoxApplication.kt` | WP-D request (weekly on by default) |
| Lead rulings: `NetworkSettings.readTimeoutSec` default **30** (choices 10/20/30/40/60; downloads keep the 120 s floor); manifest `sequence` **equal accepted** (§3.12.2 amended, contract KDoc); **debug-only plain-http DoH** (`DebugOverrides.allowHttpDoh`, set by the harness's `doh_http=1`; release stays https-only); `SimpleRowCountTest` counts **section pages only** (Channel options and the button-mapping sub-pages excluded, `ChannelsRows.NOT_COUNTED` referenced): the per-section counts equal the §3.3.13 table, total **35** | `NetworkSettings.kt`, `WpaPages.kt`, `UpdateContracts.kt`, §3.12.2, `DebugOverrides.kt`, `BoxDnsPolicy.kt`, `GuideDebugReceiver.kt`, `SettingsDebugProbe.kt`, `SimpleRowCountTest.kt` | Lead rulings of the integration brief |

**Cross-package handshakes (work item 2).** Verified: `PlaybackControllerImpl` asks the guard before `startCycle`, zap skips refused neighbours, `previousChannelResult` returns `Locked`, a refused resume emits `Stopped(LOCKED)` (`ParentalBypassTest`, 10 rows, green; on the device I-M5-14's Home-and-back and Blue rows). `EpgMatchRepository.unmatched` now uses `channelDao.unmatchedAccess(access.ordinal)` (was the legacy Boolean). WP-D's group-manager Lock/Unlock goes through `ParentalControls.setGroupLocked` for live groups. Restore carries the `parental` and `remote` domains (registered slots are exported and imported; I-M5-16). `RoomMergeSink` keeps `rule_hidden` (WP-D's test plus `ParentalPredicateTest`). WP-D's regex finding: `RegexSafety` moved to `core/.../text/` and now also guards M3's custom prefix regex (`ChannelNamePrefixImpl` falls back to Auto; the playlist page refuses the pattern with "This pattern could make the app slow…"; G-L19).

**Fixes made (on main, uncommitted).**

| # | Where (owner) | Symptom | Cause / fix | Caught by |
|---|---|---|---|---|
| X1 | `SettingsMachineImpl` (A) | An intent on a page reopened after Back did nothing (Connect on the Xtream form after leaving and re-entering) | The machine kept the popped page in its cache while the source closed it; WP-B drops intents for pages it no longer holds. Popped/replaced routes are now forgotten; the adapter reopens a missing page before an intent | I-M5-30 |
| X2 | `SettingsMachineImpl.setAdvanced` (A) | Toggling "Show advanced settings" never showed the advanced rows of the open page | The rebuild read the *published* `advanced` flag (`PageContext`) before the machine published; it now publishes first | I-M5-14 (`parental.stay_unlocked`) |
| X3 | `SettingsMachineImpl.pageKey` (A) | On Welcome, → past the last card left no card highlighted (focus on the footer note) | ←/→/↑/↓ on FirstRun move between the Link rows only | screenshots |
| X4 | Phone setup (B/D seam) — `PhoneSetupBridge` (new, `ui/.../settings/vm`) | A login, link or M3U file sent from the phone was never imported | Nobody collected `PhoneLink.submissions` for the setup kinds. The bridge (while `PhoneSetup(SETUP)` is open) saves through `SourceSetup`, moves an uploaded M3U out of `cacheDir/phone/` to `filesDir/playlists/`, reports to the phone, opens ImportProgress with "Received from your phone" | I-M5-9, real-socket check |
| X5 | `SettingsNavigatorImpl` (A) | Restore from the phone never reached RestorePreview | `openSettings(route, fromFull = false)` was a no-op; it now pushes the route while Settings is shown | I-M5-17 |
| X6 | `SetupResultMapper` (B) | `api_fault: html_splash` on login said "rejected the username or password" | M1's decoder reports an HTML login answer as `Auth(UNKNOWN)`; that reason now maps to the "web page instead of data" card | I-M5-30 |
| X7 | `PlaylistPages.accountCard` (B) | An Expired/Banned/Disabled account with a future date was not red | The tone followed only the date; the panel's status now makes it BAD | I-M5-3 |
| X8 | `SourceSetupImpl.uaProbe` (B) | No LIMIT note when the panel answers a full account with 403 (the fake panel's default) | 403 on both user agents with the stored account at `active ≥ max` is reported as the limit | I-M5-6 |
| X9 | `MainActivity` (lead) | No keep-screen-on during ImportProgress (S4); the phone page set only `View.keepScreenOn` | The window flag follows `controller.keepScreenOn` **or** route ∈ {PhoneSetup, ImportProgress} | I-M5-30 |
| X10 | `ErrorCardModel` (C) | 401 card offered "Account details", not "Edit login"; no way to retry after fixing the login | Edit login takes the card's one route before Account details; a route that edits the playlist (PlaylistSource, a focused playlist row) is followed by Retry (§3.14 "Retry applies the fix"; account-only routes keep M4's F5 rule) | I-M5-21 |
| X11 | `PlaybackControllerImpl` (A/M2) | No AUDIO_FALLBACK / VIDEO_FALLBACK events ever logged | The M2 event types were declared and never emitted; the L34/software/drop/software-video branches now log them | I-M5-27 |
| X12 | `AutoBackupScheduler` (D) | 31 orphaned `TheBox-auto-*.thebox` in `Download/TheBox` after the device pass's reinstalls | A periodic request runs at once when first enqueued; the first weekly run now waits `FIRST_DELAY_HOURS = 24` | F16 check |
| X13 | `SettingsRowViews.valueText` (A) | WP-D's Downloads entries showed "restore" as their value | An Item's `primary` is an action id, not text | screenshots |
| X14 | Debug harness `SettingsDebugProbe.wipe` (E) | After a wipe the app skipped Welcome; the PIN and Open-anyway survived | `importInTransaction(replace)` leaves device-bound domains; the wipe now resets `setup`/`backup`/`update`, WP-B's drafts, and relocks/refreshes the parental session | I-M5-1, I-M5-29 |
| X15 | `TextEditorOverlay` (A) | The on-screen keyboard stayed up after the editor closed and ate the next D-pad keys | `DisposableEffect(editor.row) { onDispose { keyboard?.hide() } }` | sweep 4 |

**Test corrections** (the merged app was right; `app/src/androidTest/.../settings/` unless named): text is committed with `EditorClosed` (the overlay's Done) instead of IME key injection, which dropped `:` in about half the runs (§6.3 asked for `performTextInput`); WP-B ids where a kind match was ambiguous (Connect = `setup.xtream.connect`/`setup.m3u.add`; More options precedes it); the result card's Continue/Edit/Keep as M3U buttons (§3.4.4); the output row under More options; WP-B's wordings ("identify as VLC", "Another device is using your connection", "Try again"); HTML via `player_api.php` under `html_splash` (the panel's 8-byte PNG has no NUL byte); `firstRun()` between M3U cases (Back on the import asks "Stop the import?"); a created PIN unlocks the session, so tests relaunch to relock; WP-C's Easy-to-guess confirm (← then OK); HOME → `moveToState(CREATED)` (injected HOME never reaches the system); the group chip instead of ← at the edge (the first channel has catch-up); 2.5 s of play before a channel can be "previous" (M2 rule); advanced rows through `showAdvanced()`; option ids (`one_hour`); the capture card read from `SettingsUiState.capture` (it is an overlay, not page rows) with OK = Save and the `binding.add_long` row; paged-list counts; the restored playlist's re-import before the idempotence snapshot; WP-D's phone JSON (`download.name`), passphrase-first for BACKUP_DOWNLOAD, Restore now on RestorePreview; `PlayerErrorCardTest` (`app/.../player/`) also accepts CONNECTION_LIMIT for "status Expired" (the app still holds the linger case's 0-of-0 account). New: `GatesTest` (§6.7 numbers below). JVM tests changed with the fixes: `SimpleRowCountTest`, `SetupPagesTest` (report route), `ModelTests` (ErrorCardModel rows, Sources), `PlayerFiveKeyReachabilityTest` (Sources, BACK_TO_AUTO), `ForcedPassthroughTest` (+2), `ChannelNamePrefixTest` (+1), `NetworkVerdictTest`, `DiagPagesTest`, `WpaPagesTest`, `RoomSettingsStoreTest`, `ReapplyOnExitTest`.

**JVM gate** (`:core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :ui:lintDebug`, `:player` alone): green. `:core` 2,854, `:data` 249, `:player` 362, `:ui` 273, `:app` 53; 0 skipped. Lint 0 errors (`:app` 36 warnings, `:ui` 40). No flake reproduced in the final runs.

**I-M5 suite** (API 30 emulator; first green run after the fixes and the confirming sweep):

| Class | Cases | Result | Notes |
|---|---|---|---|
| `OnboardingTest` | I-M5-1…3, 7, 29, 30 | 6/6 | `#m3uAndGuide` lost one key once (IME, see incidents) and passed on rerun |
| `ProbeSetupTest` | I-M5-5, 6 | 2/2 | |
| `ParentalTest` | I-M5-4, 8, 14, 15, 19 | 5/5 | |
| `PersistenceTest` | I-M5-10 (both phases), 11, values stored | 4/4 | settings loaded in 30 ms |
| `ButtonMappingTest` | I-M5-12, 13 | 2/2 | |
| `BackupRoundTripTest` | I-M5-16, 17 | 2/2 | |
| `PhoneSetupTest` | I-M5-9, 17 (phone), 20 | 3/3 | |
| `DiagnosticsTest` | I-M5-18, 23, 24, 25, 26, 28 | 6/6 | DoH over debug-only plain http (I-M5-25) |
| `FiveKeyTest` | I-M5-27 (reachability) | 1/1 | |
| `ErrorCardRouteTest` | I-M5-21 | 1/1 | |
| `MigrationOnDeviceTest` | I-M5-22 | 1/1 | |
| `NetworkAndPlaybackTest` | I-M5-11, 26, 27 | 3/3 | |
| `ChannelManagementTest` | channel pages | 2/2 | |
| `UpdaterTest` | update fixtures | 7/7 | `#apkNotSigned…WrongCert` socket timeout on the APK upload once; passed on rerun |
| `GatesTest` | §6.7 | 2/2 | new |

`range_requests_seen == 0` in every class. The sweep was repeated after a cold boot (see incidents); the table is that sweep plus the two single-case reruns.

**Regression** (M2–M4 classes WP-E listed): M2 `ErrorPathTest` 6/6, `SmokeAndZapTest` 7/7; M3 `HarnessTest` 5/5, `StartupAndSurfaceTest` 3/3, `GroupsAndBackTest` 2/2, `SearchTest` 1/1; M4 `InfoBarTest#passiveBarOnZap` and `#activeBar` (separate runs) 2/2, `ChannelListTest` 3/3, `PlayerErrorCardTest` 1/1 (after the expectation above), `FullIdleFramesTest` 1/1.

**Measured (§6.7; emulator proxies).**

| Gate | Target | Measured | Verdict here |
|---|---|---|---|
| Settings load before first tune | ≤ 50 ms | 30 ms (I-M5-10 phase 2, `settings loaded: 3 documents in 30 ms`) | met |
| PIN check (`Mac` loop, 10,000) | ≤ 150 ms | 86–93 ms warm (5 runs, two sweeps); 116–201 ms on a cold first run | met (median); outliers on cold JIT |
| Backup key derivation (50,000) | ≤ 1 s | 416–647 ms (3 runs, two sweeps) | met |
| Settings open → first page | ≤ 150 ms | 257–295 ms from the injected key to the first non-placeholder page (includes the Playlists preview's IO); `SettingsOpenBenchmark` not run | not met on this proxy; benchmark open |
| First frame after a REPREPARE setting changed | – | 1,130–1,149 ms from closing Settings to playing again (one re-prepare) | recorded |
| No player rebuild on LIVE rows | 0 | 0 re-prepares, same tuneId (I-M5-11) | met |
| Heap over 20 journeys | < 2 MB | I-M5-26 green | met |
| Redaction | 0 secrets | I-M5-18 green | met |

**Device checks.** F16 (API 30): a manual backup lands in `Download/TheBox/TheBox-backup-….thebox` with `application/octet-stream` (MediaStore keeps `.thebox`) and is listed under Restore → From Downloads; after uninstall + reinstall the list is empty ("No backups found in Downloads. Not listed? Send it from your phone."): only this install's files are visible, as WP-D expected. The phone page over a real socket (`nc` from the device shell; a host `curl` cannot reach the emulator's Wi-Fi address 10.0.2.16 through slirp redirection): `GET /` 200 with the CSP headers, `POST /pair` with the 6-digit code 204 + cookie, the same code again 401, `POST /api/xtream` 202 → the TV imports (Import progress, 13 channels). AES-GCM + the `Mac` loop: API 30 in `GatesTest`; API 25 (throwaway `google_apis` AVD, `-partition-size 2048`): PIN check 57–86 ms, backup derivation 365–403 ms, AES-GCM round trip ok. F16 on API 33: not measured (the swiftshader API 33 image stayed offline for more than 20 minutes; AVD deleted). Open for M6.

**Emulator incidents.** None of M4's E-1…E-5 recurred; no emulator crash. (1) A host stall left the guest clock 89 minutes behind; the sweep running then was discarded (`expiredAccount` waited 5,394 s) and the AVD cold-booted; the first cold boot hit `multiinstance.lock` and was restarted. (2) After the cold boot Gboard kept a stale input view that swallowed injected D-pad keys; the pass's wrapper force-stopped Gboard and selected the Leanback IME before each class (`tools/fakepanel/run-instrumented.sh` itself is unchanged; worth adding there). (3) The API 25 AVD needed `-partition-size 2048` to install the debug + test APKs.

**Open for the fix pass or M6.**
- Back to Auto exists on the AUDIO_OUTPUT_FAILED card and in the fallback toast's text; the audio picker and the stream-info line (§2.12.7) do not offer it yet (needs a picker choice and the audio-output mode in the player machine).
- `SettingsRoute.EpgSources` has no page builder (placeholder "not available"); §3.3.2's guide-sources page is unowned. When it is built it must **show** restore placeholders (`EpgSourceEntity.isPlaceholder`), with the address line "Waiting for the playlist's first refresh" (`docs/design/m5-restore-placeholder-guides.md` §3).
- Follow-up (placeholder review 2026-11-09 §7): an M3U download whose header lists no guide URLs (provider glitch, a header the parser missed, a temporary edit) makes `syncSources` delete every `PLAYLIST_HEADER` guide of that playlist, and the cascade takes their mappings. Pinned as intended by `xtvgUrlChangeKeepsSourceIdAndMappings`; restore placeholders make one bad first download lose all restored mappings at once. Needs a grace period like `missing_since` for header guides (its own design; not fixed). **Resolved 2026-11-11** by owner decision "keep the last known links": a header with no guide links now leaves the header guides and their mappings alone (`docs/decisions/2026-11-11-keep-header-guide-links.md`).
- WP-C: "Open anyway" opens the whole group; the Groups panel shows the padlock, not the amber `18+` of FILTERED_NO_PIN; the player's channel-list Lock has no toast.
- WP-A's unverified facts F21 (forced-only subtitles) and F24 (external-player extras).
- `max_connections = 0` reads as "full" in M2's ladder (L4/L7b): some panels use 0 for unlimited (M2 question).
- Settings open → first page 257–295 ms on the emulator; `SettingsOpenBenchmark` (Macrobenchmark) not run.
- UI nits seen on screenshots: an Off toggle is invisible on a focused (white) row; ImportProgress repeats each stage's value in its description; "To Downloads on this TV" says "Kept inside the app" while the file goes to `Download/TheBox`; the "Keep a copy on your phone" nudge shows on a fresh install.
- WP-B's form does not prefill the EPG field from `url-tvg` (I-M5-7 expected it; §3.4.3 has no such step — product decision).
- PBKDF2 50,000-iteration timing and the full phone flow with a real phone: hardware, M6.

---

**Lead rulings on the integration pass (2026-10-16).** (1) X10 stands: an error card whose Settings route edits the playlist (the login form, or a focused playlist row) offers **Retry** right after that button, because BACK from the detached Settings layer returns to the card and Retry applies the fix; account-only routes keep M4's F5 rule (no Retry that cannot help). "Edit login" wins over "Account details" for the card's one route. (2) X12 stands: the weekly auto-backup's first run waits `FIRST_DELAY_HOURS = 24`. (3) The amendments table above is accepted as the contract; the code reviewers check the implementations against it. (4) Settings open → first page (257–295 ms on the emulator against ≤ 150 ms) goes to the fix pass as a measurement item: run `SettingsOpenBenchmark`, move the Playlists preview's IO off the first-page path if that is what the benchmark shows, and otherwise carry the gate to M6 hardware. (5) `max_connections = 0` as "unlimited" is logged for M2's owner; not changed here.


### 1.12 Code-review fix pass (2026-10-16)

Phase 1 of the M5 code-review fix pass (`:core`, `:data`, `:player`, `:app`; the small `:ui` hand-overs named below), on `main` from `dbd2ae2`, uncommitted. Findings and rulings: `docs/review/m5-code-review.md` (assembled by phase 2) and the lead's decisions. Every fix carries a `// C-xx` / `// D-xx` / `// U-xx` comment and a regression test named in the finding's KDoc. Phase 2 does the `:ui` and androidTest halves (U-S1…U-S5, U-S7, U-S9, U-S13…U-S15, the `:ui` callers of U-S6, U-S11, U-S12), the device pass and the benchmark.

**Contract and design amendments (additive and defaulted unless stated).**

| Item | File | Reason |
|---|---|---|
| `ChoiceOption.needsPin` (a menu option asks for the PIN first; the intent only after `PinResult(granted)`) | `SettingsContracts.kt`, `SettingsMachineImpl.menuKey` | C-B2: group-manager Unlock/Reset, Playlists menu Edit/Remove (with Lock settings) |
| `BackupPageState.pinSet`, `BackupPages.PreviewState.pinSet/restored/pinOfferGroups`; Restore and Restore now `needsPin = pinSet` | `BackupRows.kt` | C-B2 = D-B1 (§3.9.2) |
| `PageAction.leaveToGuide`, `PageAction.setPin` (WP-D seam), mapped by `SectionPagesSource` to `SettingsShellLink.leaveToGuide()` / `requestPinSetup(false)` | `BackupRows.kt`, `ui/.../SettingsPageAdapters.kt` | C-S8: Start watching after a restore, the §3.4.6 PIN offer |
| Rows `backup.passphrase_remove` (destructive, confirm, advanced, shown while a passphrase is stored), `restore.start_watching`, `restore.pin_offer/pin_set/pin_later`; `BackupText` keys for them; "Leave the field empty to remove it" dropped from the passphrase description | `BackupRows.kt`, `BackupTextsImpl.kt`, `strings_backup.xml` | C-S7, C-S8 |
| `SettingsShellLink.pushGated(route)` (default: `push`) | `state/SettingsPages.kt` | U-S6: a page source's own navigation into a gated route (Sync status → Edit); the `:ui` caller is phase 2 |
| `GuideEvent.OpenSettingsDetached(route)`: SETTINGS without the PIN, the machine opened detached, BACK → FULL; `openFromFull(detached = true)` sends it and no longer opens the route itself | `GuideStateContracts.kt`, `GuideStateMachineImpl`, `SettingsMachineImpl.openFromFull` | C-S1 = U-S8 (§3.2.5, S2) |
| `UpdateCardState.focused = −1` = shown, not focused (KDoc; no type change) | `UpdateContracts.kt` | C-S2 (§3.12.3) |
| `TrackMenu.audioOutputForced`, `PickerChoice.AudioOutputAuto`, `PlayerEffects.audioOutputAuto()`; `PlayerText.choice` labels it "Back to Auto" | `PlayerContracts.kt`, `ui/.../PlayerText.kt` | U-S12 (B2); feeding the flag, the effect and the stats line are phase 2 |
| `PlayerToast.CHANNEL_LOCKED`, `LOCKED_NOT_PROTECTED` (append-only; texts are the guide's) | `PlayerContracts.kt`, `ui/.../PlayerText.kt` | U-S10 |
| `PlayerEvent.KeyHints` **removed** (with `PlayerStateMachineImpl.keyHints` and `PlayerViewModel`'s feed and its `keyBindings` parameter) | `PlayerContracts.kt`, `ui/.../PlayerViewModel.kt` | U-L18 (ruling): stored, never read |
| `GuideSourceEntry.notProtected`, set by `ParentalRules.visibleEntries` for locked entries in FILTERED_NO_PIN | `GuideFeedContracts.kt`, `ParentalSeams.kt` | U-S11; the amber `18+` chip is phase 2 |
| `toString` masks: `RowKind.Text` (masked), `EditorState` (masked), `RowIntent.Edit`, `SettingsEvent.EditorClosed` (always), `XtreamForm`, `NormalizedInput.Xtream`, `PhoneSubmission.Xtream/M3u` (hosts only) | `SettingsContracts.kt`, `SettingsMachine.kt`, `SetupContracts.kt`, `LanContracts.kt` | U-L10 = C-L14 = D-L2 |
| `SettingsStore.commitImport(committed = true)`; `importInTransaction` writes rows only and its KDoc says what Replace does to a backed-up domain the file lacks (reset to default, row deleted) | `SettingsStore.kt`, `RoomSettingsStore`, `BackupRestorer` | D-S4 (§3.10.4 step 5) |
| `HttpResponse.onSent` (runs after the whole response was written) | `LanContracts.kt`, `LanHttpServerImpl` | D-S2: a download is deleted after its one GET |
| `TuneGuard.ready`, `TuneGuard.awaitReady(timeoutMs)`; `GuardedPlaybackController(…, scope)` waits ≤ `SETTINGS_LOAD_TIMEOUT_MS` for the first load; before it `TuneGuardImpl` answers NEEDS_PIN while access is not OPEN | `TuneGuard.kt`, `TuneGuardImpl`, `ui/.../ParentalGuideLibrary.kt` | D-S1 (§3.9.6 fail closed) |
| `TestTarget.userAgent` | `diag/NetworkTest.kt` | D-L9 |
| `BACKUP_KDF_MAX_ITERATIONS` 5,000,000 → **500,000**; `BackupCrypto.pbkdf2/deriveKey/open(…, isActive)` checked every 1,000 iterations | `BackupFormat.kt`, `BackupCrypto.kt` | C-L9 = D-L10 |
| `SettingsTree.sectionOf`: FixGuide, EpgSources, SyncStatus → TV guide; NetworkTest → Playlists (= `parentOf`) | `SettingsTree.kt` (commit-0 file, lead-approved) | C-L1 (§3.2.1) |
| §3.12.3 as built: the update card appears unfocused; 3 s with no key focuses it on Update; a key before that keeps it unfocused and restarts the wait | `GuideStateMachineImpl` (`UPDATE_CARD_IDLE_MS`) | C-S2 |
| Phone page: a submission with nothing on the TV collecting it is answered 503 ("The TV is not ready for this yet…") | `PhoneApi`, `LanPhoneLink`, `assets/phone/app.js` | D-L4 |

**Fixes and their tests** (`:core` unless named).

- C-B1 `SettingsMachineImpl.onPage` eviction `firstOrNull … ?: break`: `SettingsMachinePageCacheTest` (3, adopted), `SettingsBackLadderTest.everyOverlayAndDepth` to depth 5.
- C-B2 = D-B1 = D-B2: `ChoiceOption.needsPin` + machine gate; `ChannelsPages.groupItem` Unlock/Reset, `PlaylistsSectionPage` Edit/Remove, Backup Restore/Restore now, DiagPages "Edit login" `needsPin = lockSettings()` (`:ui`); defence in depth: `ParentalSessionImpl.setGroupLocked(false)`, `ChannelsPagesImpl` Unlock/Reset and `BackupPagesImpl` Restore now refuse while a PIN is set and access is not OPEN. Tests: `SettingsMachineReviewFixTest.menuOptionWithNeedsPinRunsOnlyAfterTheGrant`, `ReviewFixRowsTest.groupManagerUnlockAsksForThePin` (adopted), `.unlockAndResetNeedThePinWhenSet`, `.restoreAndRestoreNowNeedThePinWhenSet` (adopted), `SetupPagesTest.playlistMenuEditAndRemoveAskWithLockSettings`, `:data` `ParentalReviewFixTest.groupManagerUnlockDoesNotBypassTheLockedSession` (adopted).
- D-B3 + D-S2: `DiagSourcesImpl.PHONE_DIR = "phone-out"`; `LanPhoneLink` keeps offers made before `start()` and hands them to the new `PhoneApi`; offered files are deleted after their GET (`onSent`) and on stop/fail; `BackupPagesImpl.attach` clears `backup-out`. Tests: `LanHttpServerImplTest.logsOfferedBeforeTheServerStartsReachThePhone` (adopted), `.offeredFileIsDeletedAfterItsDownloadAndOnStop`. The `:ui` export-then-open order stays.
- D-B4: `UnansweredConfirmationWatcher` acts on a start that follows a stop: `:app` `UnansweredConfirmationWatcherTest` (2; from the reviewer's mechanism test, on `LifecycleRegistry` without Robolectric). (2026-11-10) Replaced by the pure `HandOffWatcher` (`:core` `InstallHandOffTest`), see `m5-update-visible-install.md`.
- C-S1 = U-S8: `ComposedSettingsGuideTest.errorCardRouteIsDetachedAndPinFree` (adopted), `.theEventOpenFromFullSendsDoesNotAskForThePin` (adopted from the shell reviewer, on the new event), `SettingsMachineReviewFixTest.openFromFullSendsTheDetachedEventOnly`.
- C-S2: `GuideUpdateCardTest.updateCardDoesNotStealKeysFromABrowsingUser` (adopted), `.focusedOnlyAfterThreeIdleSeconds`.
- C-S3: `parental.pin` is a plain Action: `ParentalPageFlowTest.changePinAsksForTheCurrentPinOnce` (adopted), `ReviewFixRowsTest.changePinRowDoesNotAskOnItsOwn`.
- C-S4: OK on a menu-only Item opens its menu; groups drop `primary`: `SettingsMachineReviewFixTest.okOnAMenuOnlyItemOpensTheMenu`, `ReviewFixRowsTest.okOnAKeyRowOpensItsMenu` (adopted), `.groupItemOkOpensTheMenuWithShowHideFirst`.
- C-S5: the machine drops ADVANCED rows in the simple view except the route's focus row: `SettingsMachineReviewFixTest.advancedRowsAbsentInSimpleViewExceptTheRouteFocus`.
- C-S6: `exitApp()` stops playback first: `GuideBehaviourTest.exitStopsPlaybackBeforeTheAppCloses` (adopted).
- C-S7: `SettingsMachineReviewFixTest.removePassphraseRowConfirmsThenRuns`.
- C-S8: `ReviewFixRowsTest.successfulRestoreOffersStartWatchingAndThePin`; the PIN offer uses the restore's lock records (`BackupRestorer.Applied.lockedUids/lockedGroups`) and `SetupState.pinOfferedFor`. Device: I-M5-16 from a fresh install's Welcome (phase 2).
- C-S9: `RealPages` (WP-B setup pages, WP-C Parental and button mapping, WP-D Channels and Backup, 0/1/2 playlists): `SettingsReachabilityTest.everyWpsRealPagesAreReachableWithFiveKeys` ((a), (c), (d)), `SimpleRowCountTest.everySectionsSplitHoldsAndThePagesPublishIt` (registry split per section and the SIMPLE rows each page publishes with advanced off; About from the registry, its page is `:ui`'s), `EffectTimingCoverageTest.everyWpsBuiltRowsMatchTheirSpecs`; back ladder to depth 5. Found and fixed on the way: five WP-B NEXT_TUNE rows of the playlist page (catch-up mode and zone, padding before/after, M3U max connections) stated no effect.
- D-S1: tri-state PIN in `ParentalSessionImpl` (unknown → pinSet, locked); `TuneGuardImpl` fails closed before the index loaded; `GuardedPlaybackController` waits for it: `:data` `ParentalReviewFixTest.accessIsNeverOpenBeforeThePinIsKnown` (adopted), `.guardFailsClosedBeforeTheIndexLoads`.
- D-S3: `:data` `TinkCredentialStoreReviewFixTest.keyResetWithoutAPinDoesNotClaimAPinWasLost` (adopted), `.keyResetWithAPinMarksThePinLost`.
- D-S4: `:data` `RoomSettingsStoreReviewFixTest.aRolledBackRestoreLeavesNoSettingsInMemory` (adopted), `.aCommittedRestoreIsPublishedAfterTheCommit`.
- D-S5: `RegexSafety.isPolynomial` (two unbounded quantifiers on overlapping atoms in sequence, or more than four in total); the hide-rule pass runs in its own transaction after the merge commits: `RegexSafetyTest` (3, one adopted), `:data` `HideRulesReviewFixTest.polynomialRegexIsRefusedAsTooSlow` (adopted).
- D-S6: `:player` `ParentalBypassTest.aRefusedCatchUpNeverStarts`, `.selectServerOfALockedChannelIsRefused`.
- U-S3 (core half): `SettingsMachineReviewFixTest.aPageAfterLeaveIsDropped`. U-S6 (core half): `.pushGatedAsksForThePin`. U-S10: `PlayerReviewFixTest.lockFromChannelListToasts`. U-S11 (core half): `ParentalRulesNotProtectedTest`. U-S12 (core half): `PlayerReviewFixTest.audioPickerOffersBackToAutoWhenForced`.
- Later items: C-L1 `ReviewFixRowsTest.sectionOfMatchesParentChain`; C-L2 (the `capture:`/`bind:` path removed; the key-table tests run the real `startCapture`/`captureSaved` flow) `SettingsMachineReviewFixTest.captureRowsAreOrdinaryActions`, `SettingsKeyTableTest.captureDeliversTheKeyAndOkBindsIt`; C-L4 `SetupPagesTest.stopFromSettingsPopsToThePlaylistsPage`; C-L5 `.moveModeBackRestoresTheCursor`; C-L6 `.aRouteFocusWinsOverCursorMemory`; C-L7 `.patternsAreValidatedByTheirMode`; C-L8 `.customDohValidatorMatchesThePolicy`; C-L9 `BackupCryptoTest.kdfIterationsAreCappedAtTenTimesTheDefault`, `.derivationStopsWhenCancelled`; C-L10 `SourceInputNormalizerTest.onlyTheHostIsLowercased`; C-L14/U-L10 `CredentialToStringTest`, `SettingsMachineReviewFixTest.maskedValuesNeverPrint`; D-L1 `:data` `SecretHeaderTest`; D-L3 (backup work on `appScope` catches `Exception`, rethrows cancellation, ends in BACKUP_FAILED / FAIL_IO; no JVM test); D-L4 `LanHttpServerImplTest.submissionWithNobodyListeningIsRefused`; D-L5 `:data` `CopyCappedTest` (+ one preview at a time, the copy deleted on a failed restore); D-L9 (no JVM test: `NetworkProbesImpl` sends the header); D-L14 `:app` `UpdateCheckScheduleTest`; U-L18 (deletion).

**Fails-before.** With each fix's behaviour reverted in place (new contract members kept; 58 reversions in four batches by module), 71 of the new or changed tests fail: `:core` 58, `:data` 9, `:app` 2, `:player` 2 (D-S6's rows, with the controller's guard removed: they pin code that was already right). Not covered by a failing JVM test: C-L2 (a deletion), D-L3, D-L9, the `:data` halves of C-B2 (`BackupPagesImpl` refusal) and C-S8 (`pinOfferFor`), D-S2's `backup-out` cleanup on attach; `keyResetWithAPinMarksThePinLost` is a guard.

**Gate** (`:core:test :data:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :ui:lintDebug`, then `:player:testDebugUnitTest` alone): green. `:core` 2,918 (+ `stateOnRealNavigator` 312), `:data` 259, `:ui` 273, `:app` 56, `:player` 364; 0 skipped. Lint 0 errors (`:app` 36, `:ui` 40 warnings, unchanged).

**Left open by phase 1.** §3.15 (d): reordering favourite lists is only in a list's menu (its OK opens the list) — `SettingsReachabilityTest.KNOWN_D_GAPS`. WP-E's About page is outside `:core`'s coverage tests (built in `:ui`).


**Phase 2 (2026-10-16): `:ui`, androidTest, fake panel, the device pass.** On `main` from `bf60cee`, uncommitted. Record: `docs/review/m5-code-review.md`.

| Item | File | Reason |
|---|---|---|
| `PhoneSetupHost` runs the server only between ON_START and ON_STOP (`LifecycleStartEffect`); a return to the app opens it with a fresh code | `ui/.../backup/PhoneSetupViewModel.kt` | U-S7 (§3.5.2 lifetime) |
| Rebuilds are leading-edge (≤ 250 ms spacing only for bursts); a push-model page's first publish signals no change; `SetupPagesSource.page()` waits ≤ 100 ms for a non-placeholder publish; a change that lands while a build is in flight rebuilds that page once it arrives as a placeholder (`staleWhileBuilding`) | `SettingsViewModel`, `SettingsPageAdapters`, `SettingsEffectsImpl` | U-S1 and its device follow-up |
| A displayed route that re-enters the open set after the collector closed it is rebuilt | `SettingsViewModel` | U-S2 |
| `onClosed` cancels every build and closes the routes still loading; a closed slot wakes its `page()` waiter with a placeholder | `SettingsEffectsImpl`, `SetupPagesSource` | U-S3 |
| Intents of one effects instance run one at a time (`Mutex`) | `SettingsEffectsImpl` | U-L1 |
| `:ui` helpers `SettingsShellLink.shows(origin)` / `pushFrom(origin, route, gated)`: a page's navigation after a suspend is dropped once its route is not shown; a closed page is reopened for an intent only while shown (bounded 1 s) | `SettingsPageAdapters` | U-L2 |
| `DiagOutcome.Open.needsPin` (`:ui`, additive; Sync status → Edit with Lock settings) → `pushGated` | `ui/.../diag/DiagPages.kt` | U-S6 |
| Phone bridge: Xtream submissions and links carrying an Xtream login run Connect first; on failure the phone gets reason + step (`done = false`), the TV shows the Xtream form filled from the phone 1.5 s later and runs its Connect; saves are `NonCancellable`; a failed upload's kept file is deleted; new texts `phone_status_link_no_login`, `phone_status_spool_failed` | `PhoneSetupBridge`, `strings_backup.xml` | U-S4, U-S5, U-S15 |
| §3.1 toggle row amended: Off on a focused row is a `.18` canvas fill with a canvas knob | `SettingsRowViews.kt` (`togglePillColors`) | U-S9 |
| `NotProtectedChip` (amber `18+`) in the Groups panel and the player's group column for `GuideSourceEntry.notProtected` | `parental/LockGlyph.kt`, `GroupsPanel`, `ChannelListPanel` | U-S11 |
| `AudioOutputForced` seam (bound from `PlaybackDomain`); `PlayerExtras.audioOutputForced`; the stats line appends "Always passthrough · Back to Auto in the Audio menu" (the overlay takes no keys, so not "OK: Back to Auto"); `PlayerEffectsImpl.audioOutputAuto()` → `ErrorRouteActions` BACK_TO_AUTO | `ui/.../full/**`, `SettingsUiModule` | U-S12 |
| Async trace section `settings-open` (first composition of `SettingsScreen` → first frame with a real page; API 29+) and `benchmark/.../SettingsOpenBenchmark` | `SettingsScreen.kt`, `benchmark/` | measurement item (§6.7) |
| `fakepanel.py --update-apk-dir` and `GET /admin/update_apks`; `run-instrumented.sh --update-fixtures` loads `installed`/`v2`/`other` there; it also selects the Leanback IME and force-stops Gboard before each class | `tools/fakepanel/` | U-S14, U-S13 = D-L16 |
| `PhoneSubmission.Link.toString` prints the host only | `LanContracts.kt` | phase 1 flag 7 |

Later items taken: U-L1, U-L2, U-L3, U-L5, U-L6, U-L7, U-L8, U-L9, U-L15, U-L16. Fails-before (phase 2): 35 reversions in three batches, 26 tests fail before (`:ui` 24, `:core` 1, fake panel 1). Device: every I-M5 class and the §1.11 regression set green on the emulator; `OnboardingTest` 9/10, `UpdaterTest` 5/5; D-B4 confirm → install observed (`PACKAGE_REPLACED`); settings open → first page met on the emulator (§6.7).

## 2. Contracts

All contract files carry the header `// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.n. Verbatim; changes go through the lead.` Everything in `:core` is pure Kotlin (no Android types).

### 2.1 `core/.../settings/SettingsContracts.kt` (WP-A implements the machine)

```kotlin
package app.thebox.core.settings

import app.thebox.core.input.BoxAction
import app.thebox.core.refresh.ChannelIdentity

enum class SectionId { PLAYLISTS, GUIDE, PLAYBACK, CHANNELS, INTERFACE, REMOTE, PARENTAL, BACKUP, ABOUT, NETWORK }

/** Where Settings opens, and every page's address. Data classes so a route is a stable key for cursor memory. */
sealed interface SettingsRoute {
    data object Root : SettingsRoute
    /** First run (no playlist yet): Welcome, Back = exit the app (product S1). */
    data object FirstRun : SettingsRoute
    data class Section(val id: SectionId) : SettingsRoute
    /** Add a source; [firstRun] decides where Back and "Start watching" go. */
    data class AddSource(val kind: SourceKind? = null, val firstRun: Boolean = false) : SettingsRoute
    data class PhoneSetup(val firstRun: Boolean = false, val purpose: PhonePurpose = PhonePurpose.SETUP) : SettingsRoute
    data class ImportProgress(val playlistId: Long, val firstRun: Boolean) : SettingsRoute
    data class GroupsReview(val playlistId: Long, val firstRun: Boolean) : SettingsRoute
    data class Playlist(val playlistId: Long, val focus: RowId? = null) : SettingsRoute
    data class PlaylistSource(val playlistId: Long) : SettingsRoute
    data class Groups(val playlistId: Long) : SettingsRoute
    data class HiddenChannels(val playlistId: Long) : SettingsRoute
    data class HideRules(val playlistId: Long) : SettingsRoute
    data object FavouriteLists : SettingsRoute
    data class FavouriteList(val listId: Long) : SettingsRoute
    data object EpgSources : SettingsRoute
    /** [channel] null = the unmatched list; non-null = the picker for that channel. */
    data class FixGuide(val channel: ChannelIdentity? = null) : SettingsRoute
    data class Channel(val identity: ChannelIdentity) : SettingsRoute
    data object ButtonMapping : SettingsRoute
    data class ActionBindings(val action: BoxAction) : SettingsRoute
    data object Diagnostics : SettingsRoute
    data class NetworkTest(val playlistId: Long) : SettingsRoute
    data object SyncStatus : SettingsRoute
    data object Licences : SettingsRoute
    data object Backup : SettingsRoute
    data class RestorePreview(val token: Long) : SettingsRoute
}

enum class SourceKind { XTREAM, M3U }
enum class PhonePurpose { SETUP, RESTORE, BACKUP_DOWNLOAD, LOGS_DOWNLOAD }

/** Stable row id, e.g. "playback.audio_output". Persisted nowhere; used for routes, tests and focus restore. */
@JvmInline value class RowId(val key: String)

enum class Visibility { SIMPLE, ADVANCED }

/** When a change takes effect (decision §0.6). The row's description states every value other than LIVE. */
enum class EffectTiming { LIVE, NEXT_TUNE, REPREPARE, NEXT_REFRESH, NEXT_EPG_IMPORT, NEXT_LAUNCH, RESTART, NONE }

data class ChoiceOption(val id: String, val label: String, val detail: String? = null, val enabled: Boolean = true)

sealed interface RowKind {
    data class Toggle(val on: Boolean) : RowKind
    /** Inline radio list (product S11: "not a modal"). */
    data class Choice(val options: List<ChoiceOption>, val selected: Int) : RowKind
    /** Several of [options] (favourite lists of a channel, enabled EPG sources). */
    data class MultiChoice(val options: List<ChoiceOption>, val selected: Set<Int>) : RowKind
    data class Text(val value: String, val hint: TextHint, val masked: Boolean = false, val chips: List<String> = emptyList()) : RowKind
    data class Number(val value: Int, val min: Int, val max: Int, val unit: String? = null) : RowKind
    data class Action(val destructive: Boolean = false, val confirm: ConfirmSpec? = null, val needsPin: Boolean = false) : RowKind
    data class Link(val route: SettingsRoute, val needsPin: Boolean = false) : RowKind
    /** Read-only lines (account card, device facts). Focusable so the page can scroll to it. */
    data class Info(val lines: List<String>, val tone: Tone = Tone.NORMAL) : RowKind
    /** An entry in a management list (group, hidden channel, binding, rule). OK opens [route] or runs [primary]. */
    data class Item(val route: SettingsRoute? = null, val primary: String? = null, val menu: List<ChoiceOption> = emptyList(), val movable: Boolean = false, val badges: Set<Badge> = emptySet()) : RowKind
}

enum class TextHint { URL, USERNAME, PASSWORD, NAME, PATTERN, PASSPHRASE, HEADER, NUMBER }
enum class Tone { NORMAL, GOOD, WARN, BAD }
enum class Badge { LOCKED, HIDDEN, ADULT, FAILED, NEW, DEFAULT }

data class ConfirmSpec(val title: String, val body: String, val confirmLabel: String, val cancelLabel: String)

data class SettingsRow(
    val id: RowId,
    val label: String,
    /** Current value on the right at .65 alpha (product S11). */
    val value: String? = null,
    val description: String? = null,
    val kind: RowKind,
    val enabled: Boolean = true,
    /** Shown in place of [description] when [enabled] is false; disabled rows stay focusable. */
    val disabledReason: String? = null,
    val visibility: Visibility = Visibility.SIMPLE,
    val effect: EffectTiming = EffectTiming.LIVE,
)

/** One page. Big lists (groups, hidden channels, unmatched, EPG channels) are paged: [rows] holds the fixed rows, [list] the rest. */
data class PageModel(
    val route: SettingsRoute,
    val title: String,
    val subtitle: String? = null,
    val rows: List<SettingsRow>,
    val list: PagedList? = null,
    /** Bumped whenever the content changes; cursor restore uses RowId first, then the index. */
    val version: Long,
    /** Shown instead of rows while loading or when empty ("No hidden channels"). */
    val placeholder: String? = null,
)

data class PagedList(val count: Int, val pageSize: Int = PAGE_SIZE, val loaded: Map<Int, List<SettingsRow>>) {
    companion object { const val PAGE_SIZE = 32 }
}

/** What the user did on a row; [SettingsEffects.onIntent] executes it (writes go to the store on IO). */
sealed interface RowIntent {
    val row: RowId
    data class Toggle(override val row: RowId, val on: Boolean) : RowIntent
    data class Choose(override val row: RowId, val optionId: String) : RowIntent
    data class ChooseMany(override val row: RowId, val optionIds: Set<String>) : RowIntent
    data class Edit(override val row: RowId, val text: String) : RowIntent
    data class SetNumber(override val row: RowId, val value: Int) : RowIntent
    data class Run(override val row: RowId) : RowIntent
    data class Menu(override val row: RowId, val optionId: String) : RowIntent
    data class Move(override val row: RowId, val toIndex: Int) : RowIntent
}
```

### 2.2 `core/.../settings/SettingsStore.kt` (WP-A implements in `:data`)

```kotlin
package app.thebox.core.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * One persisted document per domain (`app_setting` row, decision §0.4). Values are kept in memory as StateFlows;
 * [update] is main-safe: it applies in memory at once and persists on IO, coalesced per domain
 * (SETTINGS_WRITE_DEBOUNCE_MS) and flushed on ON_STOP.
 * (reviewed 2026-10-16, S1) Nothing is written for a domain before its first read completed: earlier [update]s are
 * queued and replayed on the stored document when the read lands (also after the ready timeout, which only unblocks
 * the first frame with defaults). [importInTransaction] cancels pending writes of the domains it replaces. Values that
 * are not user choices (PlaybackSettings.defaultUserAgent, test hooks) never enter the store. JSON is `ignoreUnknownKeys`, `encodeDefaults = false`, so a
 * value equal to its default is not stored and a later default change reaches users who never touched it.
 */
class SettingsDomain<T>(
    val name: String,
    val serializer: KSerializer<T>,
    val default: T,
    /** Carried by backups (§3.10.2). Learned or device-bound domains say false. */
    val backedUp: Boolean = true,
    /** Bumped on an incompatible change; the store passes old documents through [migrate]. */
    val schema: Int = 1,
    val migrate: (fromSchema: Int, json: JsonElement) -> JsonElement = { _, j -> j },
)

interface SettingsStore {
    /** True once every domain was read (or SETTINGS_LOAD_TIMEOUT_MS passed: defaults, logged). */
    val ready: StateFlow<Boolean>
    suspend fun awaitReady()
    fun <T> flow(domain: SettingsDomain<T>): StateFlow<T>
    fun <T> update(domain: SettingsDomain<T>, transform: (T) -> T)
    /** Writes pending changes now (ON_STOP, before a backup, before process kill in tests). */
    suspend fun flush()
    /** Backup: every [SettingsDomain.backedUp] domain as stored JSON. */
    suspend fun export(): Map<String, JsonElement>
    /** Restore inside the caller's library.db transaction (§3.10.4). Unknown domains are ignored. */
    suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean)
}

/** The domains (single source of the names; §4.1 lists their fields). */
object Domains {
    const val GUIDE = "guide"          // GuidePrefs below (mapped onto core.guide.GuideSettings)
    const val PLAYER = "player"        // core.full.PlayerSettings
    const val PLAYBACK = "playback"    // PlaybackPrefs below
    const val DATA = "data"            // DataPrefs below
    const val NETWORK = "network"      // core.net.NetworkSettings
    const val PARENTAL = "parental"    // core.parental.ParentalPolicy
    const val REMOTE = "remote"        // core.input.KeyBindingOverrides
    const val BACKUP = "backup"        // BackupPrefs (not backed up: device-bound)
    const val UPDATE = "update"        // core.update.UpdatePrefs (not backed up)
    const val SETUP = "setup"          // SetupState (not backed up)
}

enum class ClockMode { SYSTEM, H12, H24 }

/** Stored form of the guide/interface rows; PersistedGuideSettings maps it onto GuideSettings (SYSTEM → DateFormat.is24HourFormat). */
@kotlinx.serialization.Serializable
data class GuidePrefs(
    val clock: ClockMode = ClockMode.SYSTEM,
    val showLogos: Boolean = true,
    val showNumbers: Boolean = true,
    val performance: app.thebox.core.guide.PerformanceMode = app.thebox.core.guide.PerformanceMode.AUTO,
    /** null = automatic (Still in low-RAM mode, product §4.6). */
    val miniStill: Boolean? = null,
    /** null = automatic (Off in low-end mode). */
    val ambient: Boolean? = null,
    val exitConfirmation: app.thebox.core.guide.ExitConfirmation = app.thebox.core.guide.ExitConfirmation.BACK_TWICE,
    val openGuideOn: app.thebox.core.guide.OpenGuideOn = app.thebox.core.guide.OpenGuideOn.LAST,
    val reminderAutoSwitch: Boolean = false,
    val reminderLeadSec: Int = 60,
    /** "Show advanced settings" (product S11). */
    val advancedSettings: Boolean = false,
)

enum class SubtitleMode { OFF, ON, FORCED_ONLY }

/** User-settable part of :player's PlaybackSettings (the rest are test hooks or derived). */
@kotlinx.serialization.Serializable
data class PlaybackPrefs(
    val bufferMode: app.thebox.core.playback.BufferMode = app.thebox.core.playback.BufferMode.AUTO,
    val videoDecoderMode: app.thebox.core.playback.VideoDecoderMode = app.thebox.core.playback.VideoDecoderMode.AUTO,
    val softwareAudioFallback: Boolean = true,
    val preferSoftwareAudio: Boolean = false,
    val audioOutput: app.thebox.core.playback.AudioOutputMode = app.thebox.core.playback.AudioOutputMode.AUTO,
    val tunneling: Boolean = false,
    val afrMode: app.thebox.core.playback.AfrMode = app.thebox.core.playback.AfrMode.SEAMLESS,
    val hdrInPreview: app.thebox.core.playback.HdrPreviewMode = app.thebox.core.playback.HdrPreviewMode.POSTER,
    val preferredAudioLanguages: List<String> = emptyList(),
    /** OFF/ON map to PlaybackSettings.subtitlesEnabled; FORCED_ONLY needs a Media3 check (§9 F21) or ships as OFF/ON only. */
    val subtitles: SubtitleMode = SubtitleMode.OFF,
    val preferredTextLanguages: List<String> = emptyList(),
    val defaultAspect: app.thebox.core.playback.AspectMode = app.thebox.core.playback.AspectMode.FIT,
    val reconnectAttempts: Int = 5,
    val failoverToBackup: Boolean = true,
    val resumeAfterStandby: Boolean = true,
    /** Package name of the external player, null = none (product §4.4). */
    val externalPlayer: String? = null,
)

/** User-settable part of :data's DataSettingsSnapshot. */
@kotlinx.serialization.Serializable
data class DataPrefs(
    /** 0 = Auto (max catch-up days, minimum 1; product §4.3). */
    val epgPastDays: Int = 0,
    val epgFutureDays: Int = 7,
    /** (reviewed 2026-10-16, S12) The existing core.match.MatchLevel (MatchContracts.kt:32), not a new enum. */
    val matchLevel: app.thebox.core.match.MatchLevel = app.thebox.core.match.MatchLevel.SUGGEST_FUZZY,
    val storeDescriptions: Boolean = true,
    val epgRefreshIntervalH: Int = 12,
    /** (N4) "Daily at a set time": hour 0–23; null = use [epgRefreshIntervalH]. */
    val epgDailyAtHour: Int? = null,
    val globalShiftMin: Int = 0,
)

@kotlinx.serialization.Serializable
data class SetupState(val completed: Boolean = false, val lastAddedPlaylistId: Long? = null, val pinOfferedFor: Set<String> = emptySet())

@kotlinx.serialization.Serializable
data class BackupPrefs(
    val weekly: Boolean = true,
    val lastAutoAtMs: Long? = null,
    val lastAutoFile: String? = null,
    /** A Keystore-wrapped backup passphrase exists (secure blob `backup-passphrase`), so weekly backups include logins. */
    val passphraseStored: Boolean = false,
)
```

`PlayerSettings` (`PlayerSettings.kt:22-32`) gains `@Serializable` (commit-0 amendment, §2.12) and is stored as is. `GuideSettings` (`GuideFeedContracts.kt:118-131`) is **not** serialised: `GuidePrefs` is its stored form, so `debugUnlocked` and the derived values (24-hour from the system, low-end automatic values) never reach disk or a backup. **`PlaybackSettings` stays in `:player`**: `PersistedPlaybackSettings` (`:app`) maps `PlaybackPrefs` onto it and keeps `defaultUserAgent`, `lifecycleTimeScale`, `streamSwitchTimeoutMs`, `autoUserAgentFallback` and `userAgentFallbackPreset` as they are. `MatchLevel` maps onto `MatchSettings` in `PersistedDataSettings`. (reviewed 2026-10-16, S12) Mappings that live in one place each, with a test: `DataPrefs.epgPastDays = 0` (Auto) → the importer's value = max catch-up days over enabled playlists clamped to 1–7 (today's fixed default is 2), in `PersistedDataSettings`; `GuidePrefs.miniStill/ambient = null` (automatic) → `GuideSettings.miniStill = lowEnd`, `ambient = !lowEnd`, resolved in `PersistedGuideSettings` from the device class and `performance`.

### 2.3 `core/.../settings/SettingsMachine.kt` (WP-A)

```kotlin
package app.thebox.core.settings

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.CapturedKey
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import kotlinx.coroutines.flow.StateFlow

enum class Pane { SECTIONS, PAGE }

data class EditorState(val row: RowId, val label: String, val initial: String, val hint: TextHint, val masked: Boolean, val chips: List<String>, val error: String? = null)
data class NumberPadState(val row: RowId, val label: String, val digits: String, val min: Int, val max: Int, val focusedKey: Int = 4, val error: String? = null)
data class ConfirmState(val spec: ConfirmSpec, val intent: RowIntent, val focused: Int = 1 /* cancel */)
data class ChoiceState(val row: RowId, val focused: Int)
data class MoveState(val row: RowId, val fromIndex: Int, val index: Int)
data class MenuState(val row: RowId, val options: List<ChoiceOption>, val focused: Int)

data class SettingsUiState(
    val route: SettingsRoute = SettingsRoute.Root,
    val stack: List<SettingsRoute> = emptyList(),
    val pane: Pane = Pane.SECTIONS,
    val sections: List<SectionId> = emptyList(),
    val sectionCursor: Int = 0,
    val page: PageModel? = null,
    val cursor: Int = 0,
    /** First visible row index (one-row lookahead scrolling, §3.2.4). */
    val top: Int = 0,
    val advanced: Boolean = false,
    val choice: ChoiceState? = null,
    val menu: MenuState? = null,
    val confirm: ConfirmState? = null,
    val editor: EditorState? = null,
    val numberPad: NumberPadState? = null,
    val move: MoveState? = null,
    /** "Press a key now" (button mapping, §3.8.3); the router's capture hook is armed while non-null. */
    val capture: CaptureState? = null,
    /** Transient line at the bottom of the page ("Saved", "Copied from link"). */
    val notice: String? = null,
)

/** Effects the machine requests; :ui's SettingsEffectsImpl implements them. Main-safe, non-blocking. */
interface SettingsEffects {
    fun loadPage(route: SettingsRoute)
    fun loadItems(route: SettingsRoute, from: Int, to: Int)
    fun onIntent(intent: RowIntent)
    /** Settings left by any route (S9): apply REPREPARE-class changes once through LiveSettingsApplier (§0.6), flush the store. */
    fun onClosed(changedTimings: Set<EffectTiming>)
    fun armCapture(armed: Boolean)
    fun exitApp()
    fun announce(text: String)
}

sealed interface SettingsEvent {
    data class Page(val page: PageModel) : SettingsEvent
    data class Items(val route: SettingsRoute, val version: Long, val from: Int, val rows: List<SettingsRow>) : SettingsEvent
    /** The editor overlay closed (Compose owned the keys meanwhile). */
    data class EditorClosed(val row: RowId, val text: String?, val committed: Boolean) : SettingsEvent
    /** (reviewed 2026-10-16, S3) BACK while the editor is open: the router turns it into this event (Cancel, or chips → field). */
    data object EditorBack : SettingsEvent
    data class Captured(val key: CapturedKey) : SettingsEvent
    data class CaptureTimedOut(val token: Long) : SettingsEvent
    data class PinResult(val purpose: PinPurpose, val granted: Boolean) : SettingsEvent
    data class Notice(val text: String) : SettingsEvent
    /** Advanced view toggled elsewhere or restored. */
    data class Advanced(val on: Boolean) : SettingsEvent
    data class Tick(val nowMs: Long) : SettingsEvent
}

interface SettingsMachine {
    val ui: StateFlow<SettingsUiState>
    /**
     * (reviewed 2026-10-16, S2) [detached]: the route opens as a standalone layer with nothing of Settings behind it
     * (no Root in the stack, no sections pane); BACK at it leaves SETTINGS, and its links into PIN-gated routes ask for
     * the PIN. Used for PIN-free entries (sync chip → SyncStatus, error-card routes while "Lock settings" is on).
     */
    fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean = false)
    /**
     * The guide machine's Act in SETTINGS. False for BACK at the root (or at a detached route), and for OPEN_GUIDE and
     * EXIT (N11): the guide machine then leaves SETTINGS to the guide, or opens its exit dialog, as in M3/M4.
     */
    fun act(action: GuideEvent.Act): Boolean
    /** (S9) Called by the guide machine on every exit from SETTINGS, whatever the route; emits onClosed at most once per visit. */
    fun onLeave()
    fun release(action: BoxAction) = Unit
    fun dispatch(event: SettingsEvent)
    /** True while [SettingsUiState.editor] is open: the router passes every key to Compose (§2.12.5). */
    val textInputActive: Boolean
    val nextDeadlineMs: StateFlow<Long>
    var pinGate: PinGate?
}

fun interface SettingsMachineFactory {
    fun create(effects: SettingsEffects, nowMs: () -> Long): SettingsMachine
}
```

### 2.4 `core/.../setup/SetupContracts.kt` (WP-B)

```kotlin
package app.thebox.core.setup

import app.thebox.core.common.Outcome
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.Flow

/** Result of normalising what the user typed or pasted (§3.4.3). Pure. */
sealed interface NormalizedInput {
    data class Xtream(val server: String, val username: String?, val password: String?, val output: String?, val filledFromLink: Boolean) : NormalizedInput
    data class M3u(val url: String, val looksLikeXtream: Xtream?) : NormalizedInput
    data class Invalid(val reason: InputProblem) : NormalizedInput
}
enum class InputProblem { EMPTY, NOT_A_URL, UNSUPPORTED_SCHEME, STALKER_PORTAL, SPACES_IN_HOST }

interface SourceInputNormalizer {
    fun server(raw: String): NormalizedInput
    fun m3uUrl(raw: String): NormalizedInput
    /** Removes zero-width, NBSP and trailing whitespace; never changes inner characters of a password. */
    fun field(raw: String): String
}

/** The result card (product S3) and every failure as reason + next step. */
data class SetupCard(
    val tone: app.thebox.core.settings.Tone,
    val headline: String,
    val lines: List<String>,
    val primary: SetupAction,
    val secondary: SetupAction?,
)
enum class SetupAction { CONTINUE, SAVE_ANYWAY, EDIT, RETRY, USE_XTREAM_INSTEAD, ADD_AS_SINGLE_CHANNEL, USE_SECURE_DNS, PHONE_SETUP, CANCEL }

data class XtreamForm(val name: String?, val server: String, val username: String, val password: String, val output: String = "AUTO", val userAgent: String? = null, val backups: List<String> = emptyList())
data class M3uForm(val name: String?, val url: String?, val fileUri: String?, val epgUrls: List<String>, val userAgent: String? = null)

sealed interface SetupProgress {
    data class Stage(val stage: ImportStage, val done: Boolean, val count: String?) : SetupProgress
    data class ReadyToWatch(val playlistId: Long) : SetupProgress
    data class Failed(val card: SetupCard) : SetupProgress
    data class Finished(val report: ImportSummary) : SetupProgress
}
/** Product S5 stage rows. */
enum class ImportStage { DOWNLOADING_PLAYLIST, READING_CHANNELS, GROUPS, DOWNLOADING_GUIDE, MATCHING_GUIDE }
data class ImportSummary(val playlistId: Long, val live: Int, val vodKept: Int, val groups: Int, val adultGroups: Int, val epgMatched: Int, val epgTotal: Int, val uaNote: String?)

/** :data implements. All suspend calls are main-safe. */
interface SourceSetup {
    /** player_api.php login only; nothing is stored. */
    suspend fun testXtream(form: XtreamForm): Outcome<XtreamAccountInfo>
    /** Creates the playlist (or updates [existingId] in place, keeping playlist_id) and starts the import. */
    suspend fun saveXtream(form: XtreamForm, existingId: Long? = null): Outcome<Long>
    suspend fun saveM3u(form: M3uForm, existingId: Long? = null): Outcome<Long>
    /** Stage updates for [playlistId]'s current import; completes after MATCHING_GUIDE or a failure. */
    fun progress(playlistId: Long): Flow<SetupProgress>
    /** Groups review (S6) writes; one transaction. */
    suspend fun applyGroupsReview(playlistId: Long, hidden: Set<String>, locked: Set<String>, unlocked: Set<String>)
    suspend fun cancelImport(playlistId: Long)
}

/** One short stream open through the broker (UA probe, decision §3.4.5). :player implements. */
interface StreamProbe {
    /**
     * Opens [url] with [userAgent] through the playlist's broker (StreamBrokerRegistry → ConnectionBrokerCore.acquire(
     * LeaseKind.PROBE, ownerTuneId = PROBE_OWNER_ID, waitMs); S12: no `StreamConnectionBroker` type exists), reads up to PROBE_BYTES or
     * PROBE_MS, sniffs (0x47 TS sync / #EXTM3U / HTML), and **always** closes the call before returning.
     * Never sends Range. Fails fast with LIMIT when the broker has no slot (never pre-empts PLAYBACK).
     */
    suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult
}
data class ProbeResult(val verdict: ProbeVerdict, val httpStatus: Int?, val bytes: Long, val ms: Long)
enum class ProbeVerdict { MEDIA, HLS, HTML, FORBIDDEN, LIMIT, NOT_FOUND, TIMEOUT, NETWORK, OTHER }
```

### 2.5 `core/.../setup/lan/LanContracts.kt` (WP-D)

```kotlin
package app.thebox.core.setup.lan

import kotlinx.coroutines.flow.StateFlow

/** What the phone sent (validated by the server before it reaches the TV UI). */
sealed interface PhoneSubmission {
    data class Xtream(val form: app.thebox.core.setup.XtreamForm) : PhoneSubmission
    data class M3u(val form: app.thebox.core.setup.M3uForm) : PhoneSubmission
    /** Any pasted link: the TV normalises it (get.php → Xtream). */
    data class Link(val url: String, val name: String?) : PhoneSubmission
    /** A backup file was uploaded and spooled to [path] (app cache); passphrase follows separately. */
    data class BackupUploaded(val path: String, val bytes: Long) : PhoneSubmission
    data class Passphrase(val value: CharArray) : PhoneSubmission { override fun toString() = "Passphrase(****)" }
    /** An M3U file uploaded and spooled to [path] (for devices without a file picker). */
    data class M3uFileUploaded(val path: String, val bytes: Long, val name: String?) : PhoneSubmission
}

enum class ServerPhase { STARTING, WAITING, PAIRED, RECEIVED, CLOSED, FAILED }

data class LanServerState(
    val phase: ServerPhase,
    /** http://192.168.1.20:47100 (typed form). Null until bound. */
    val baseUrl: String? = null,
    /** baseUrl + "/s/" + token (QR form). */
    val qrUrl: String? = null,
    val code: String? = null,
    /** IPv4 of the paired phone, shown on the TV so the user sees who connected. */
    val pairedFrom: String? = null,
    val lastRequestAtMs: Long? = null,
    val wrongCodes: Int = 0,
    val failure: LanFailure? = null,
    // Additive since 2026-10-24/28 (save location, U-N1): the last NETWORK_FOLDER sign-in report, for the TV screen.
    val lastSignInStatus: String? = null,
    val lastSignInOk: Boolean? = null,
    // Additive 2026-11-01: TOO_MANY_WRONG_CODES came from a paired phone's failed folder sign-ins, not from pairing codes.
    val signInsExhausted: Boolean = false,
)
enum class LanFailure { NO_LAN_ADDRESS, BIND_FAILED, TOO_MANY_WRONG_CODES, EXPIRED }

/** One server per session; started by the phone setup screen, stopped when it closes. :data implements over :core's server. */
interface PhoneLink {
    val state: StateFlow<LanServerState>
    val submissions: kotlinx.coroutines.flow.Flow<PhoneSubmission>
    fun start(purpose: app.thebox.core.settings.PhonePurpose)
    fun stop()
    /** Offers a file for one download (backup, logs) to the paired phone; removed after download or stop. */
    fun offerDownload(name: String, path: String, contentType: String)
    /** The TV's answer shown on the phone page ("Checking…", "Added 2,140 channels", or the failure card text). */
    fun reportStatus(text: String, done: Boolean)
}

/** Pure server core (`LanHttpServerImpl`), testable with FakeLanTransport. */
interface LanHttpServer {
    fun start(bind: LanBind, handler: LanHandler)
    fun stop()
}
data class LanBind(val address: String, val preferredPort: Int, val maxConnections: Int)
fun interface LanHandler { fun handle(request: HttpRequest): HttpResponse }
data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val remoteAddress: String, val body: BodySource?)
interface BodySource { val length: Long; fun copyTo(sink: java.io.OutputStream, max: Long): Long }
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray? = null, val file: java.io.File? = null)
```

### 2.6 `core/.../parental/ParentalContracts.kt` (WP-C)

```kotlin
package app.thebox.core.parental

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSourceEntry
import kotlinx.coroutines.flow.StateFlow

enum class LockedInGuide { SHOW_WITH_LOCK, HIDE }
enum class StayUnlocked { UNTIL_APP_CLOSES, ONE_HOUR, ASK_EVERY_TIME }

@kotlinx.serialization.Serializable
data class ParentalPolicy(
    val autoLockAdult: Boolean = true,
    val lockedInGuide: LockedInGuide = LockedInGuide.SHOW_WITH_LOCK,
    val lockSettings: Boolean = false,
    val stayUnlocked: StayUnlocked = StayUnlocked.UNTIL_APP_CLOSES,
)

/**
 * How library queries treat locks (§4.3). Ordinals are bound into SQL: append only.
 * (reviewed 2026-10-16, B3) FILTERED_NO_PIN: no PIN exists and auto-lock is on (or the PIN was lost to a Keystore
 * reset, S8): locked content is kept out of every mixed list exactly as LOCKED_AUTO, but the Groups panel may open a
 * locked group after the "not protected" card ("Open anyway", until the app closes).
 */
enum class ParentalAccess { OPEN, LOCKED_AUTO, LOCKED_MANUAL_ONLY, FILTERED_NO_PIN }

data class ParentalState(
    val pinSet: Boolean,
    val unlocked: Boolean,
    val policy: ParentalPolicy,
    /** Set when a Keystore reset wiped the PIN (§3.9.6); cleared when a new PIN is set. */
    val pinLostToKeyReset: Boolean = false,
    /** Groups opened with "Open anyway" in FILTERED_NO_PIN (until the app closes). */
    val openedAnyway: Set<app.thebox.core.guide.GuideSource> = emptySet(),
) {
    /** Contract body (no stub; reviewed 2026-10-16, B3/S8). */
    val access: ParentalAccess get() = when {
        pinSet && unlocked -> ParentalAccess.OPEN
        pinSet && policy.autoLockAdult -> ParentalAccess.LOCKED_AUTO
        pinSet -> ParentalAccess.LOCKED_MANUAL_ONLY
        policy.autoLockAdult || pinLostToKeyReset -> ParentalAccess.FILTERED_NO_PIN
        else -> ParentalAccess.OPEN
    }
    /** The Parental row shows "On · not protected: set a PIN" (amber) in this state. */
    val unprotected: Boolean get() = access == ParentalAccess.FILTERED_NO_PIN
    /** Feeds and VMs restart their library flows when this changes. */
    val queryKey: Int get() = access.ordinal * 2 + if (policy.lockedInGuide == LockedInGuide.HIDE) 1 else 0
}

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val attemptsBeforeLockout: Int) : PinCheck
    data class LockedOut(val untilMs: Long) : PinCheck
    /** Keystore unavailable right now (CredentialFailure.UNAVAILABLE): nothing unlocks; "Try again" (§3.9.6). */
    data object Unavailable : PinCheck
}

/** :data implements. The verifier is PBKDF2-HMAC-SHA256(pin, salt, PIN_KDF_ITERATIONS) inside the AEAD store. */
interface ParentalPinStore {
    suspend fun isSet(): Boolean
    /** [pin] is exactly 4 digits; zeroed by the caller after the call. */
    suspend fun set(pin: CharArray): app.thebox.core.common.Outcome<Unit>
    suspend fun verify(pin: CharArray, nowMs: Long): PinCheck
    suspend fun clear()
    /** Lockout state for the prompt on open (a prompt opened during a lockout shows the countdown at once). */
    suspend fun lockedUntil(nowMs: Long): Long?
}

interface ParentalSession {
    val state: StateFlow<ParentalState>
    fun unlock(nowMs: Long)
    /** Relock now (app stop, ASK_EVERY_TIME triggers, "Lock now" row). */
    fun relock(reason: RelockReason)
    fun onTick(nowMs: Long)
}
enum class RelockReason { APP_STOPPED, TIMEOUT, LEFT_LOCKED_CONTENT, USER, PIN_CHANGED }

/**
 * (reviewed 2026-10-16, B1) `core/.../playback/TuneGuard.kt`. Every tune funnels through it inside the controller path:
 * guide OK, channel list, number entry, CH± (the zap neighbour lookup), previous channel, reminders, search,
 * catch-up start and resumeAfterLifecycle. :data implements it from the §4.3 predicate (one indexed lookup per
 * identity, cached per ParentalState.queryKey). A refused target never starts a cycle.
 */
fun interface TuneGuard {
    fun check(identity: app.thebox.core.refresh.ChannelIdentity): TuneVerdict
}
enum class TuneVerdict { ALLOWED, NEEDS_PIN, NOT_PROTECTED }

/**
 * What a PIN unlocks; the guide machine routes the result back to [owner] (decision §0.9). In FILTERED_NO_PIN the same
 * purposes open the "not protected" card ([Set a PIN] [Open anyway]) instead of the pad (B3).
 */
sealed interface PinPurpose {
    val owner: PinOwner
    data class OpenSource(val entry: GuideSourceEntry, override val owner: PinOwner) : PinPurpose
    data class Tune(val channel: GuideChannel, override val owner: PinOwner) : PinPurpose
    data class EnterSettings(val route: app.thebox.core.settings.SettingsRoute) : PinPurpose { override val owner = PinOwner.GUIDE }
    data class SettingsAction(val token: Long) : PinPurpose { override val owner = PinOwner.SETTINGS }
}
enum class PinOwner { GUIDE, PLAYER, SETTINGS }

/** Injected by the guide machine into the player and settings machines. */
fun interface PinGate {
    /** False when no PIN is set: the caller proceeds at once. True: a prompt opened; the answer arrives later. */
    fun request(purpose: PinPurpose): Boolean
}

enum class PinPadMode { VERIFY, CREATE_FIRST, CREATE_CONFIRM }
data class PinPromptState(
    val purpose: PinPurpose?,
    val mode: PinPadMode,
    /** Number of digits typed (0–4); the digits themselves stay inside the machine and are never in UI state. */
    val filled: Int,
    /** Focused key of the 3 × 4 grid: 0–8 = 1–9, 9 = ⌫, 10 = 0, 11 = OK. */
    val focusedKey: Int = 4,
    val checking: Boolean = false,
    val wrong: Boolean = false,
    val lockedUntilMs: Long? = null,
    val message: String? = null,
)
```

### 2.7 `core/.../input/KeyBindingContracts.kt` (WP-C)

```kotlin
package app.thebox.core.input

import kotlinx.coroutines.flow.StateFlow

enum class Press { SHORT, LONG }

/** One user change: [action] null = "unbound" (removes a default). Keyed by (context, keyCode, press). */
@kotlinx.serialization.Serializable
data class KeyOverride(
    val context: KeyContext,
    val keyCode: Int,
    val press: Press,
    /** BoxAction.id, or null for unbound. Unknown ids (from a newer backup) are ignored, not crashed on. */
    val action: String?,
    /** InputDevice name at capture time, for the list ("on Amazon Fire TV Remote"). Display only. */
    val capturedFrom: String? = null,
    val atMs: Long,
)

@kotlinx.serialization.Serializable
data class KeyBindingOverrides(val version: Int = 1, val entries: List<KeyOverride> = emptyList())

/** What capture saw (the router fills it from the native KeyEvent). */
data class CapturedKey(val keyCode: Int, val keyName: String, val deviceName: String?, val deviceId: Int, val isVirtual: Boolean)

data class CaptureState(val token: Long, val action: BoxAction, val press: Press, val startedAtMs: Long, val captured: CapturedKey? = null, val verdict: BindingVerdict? = null)

sealed interface BindingVerdict {
    data object Free : BindingVerdict
    /** Saving moves the key from [current] in [contexts] (the later binding wins). */
    data class Replaces(val current: BoxAction, val contexts: Set<KeyContext>) : BindingVerdict
    data class Reserved(val reason: ReservedReason) : BindingVerdict
    /** Adding a long binding makes the short press act on release and disables its auto-repeat (§3.8.2). */
    data class LosesRepeat(val shortAction: BoxAction, val then: BindingVerdict) : BindingVerdict
}
/** (reviewed 2026-10-16, S14) UNKNOWN_CODE: KEYCODE_UNKNOWN (0), which some CEC/IR remotes send for several buttons. */
enum class ReservedReason { NAVIGATION, OK, BACK, DIGIT, SYSTEM, CONTEXT_MENU_LONG_OK, UNKNOWN_CODE }

/** Action metadata for the mapping screen (labels come from :ui resources by [BoxAction.id]). */
data class ActionInfo(val action: BoxAction, val contexts: Set<KeyContext>, val group: ActionGroup)
/** (reviewed 2026-10-16, N1) Same five groups as §3.8.1. */
enum class ActionGroup { WATCHING, CHANNELS, GUIDE, PLAYER_OVERLAYS, APP }

interface KeyBindingStore {
    val overrides: StateFlow<KeyBindingOverrides>
    /** Applies [BindingRules.save] (later binding wins) and persists. */
    fun bind(action: BoxAction, key: CapturedKey, press: Press, nowMs: Long)
    fun unbind(context: KeyContext, keyCode: Int, press: Press, nowMs: Long)
    fun resetKey(keyCode: Int)
    fun resetAction(action: BoxAction)
    fun resetAll()
}

/** The KeyMap the router uses (InputModule binds it). Snapshot rebuilt off the main thread on every change. */
interface EffectiveKeyMap : KeyMap {
    val defaults: KeyMap
    /** Keys bound to [action] in [context] (short and long), for the mapping list and the footer hints. */
    fun keysFor(context: KeyContext, action: BoxAction): List<Pair<Int, Press>>
}
```

### 2.8 `core/.../backup/BackupContracts.kt` (WP-D)

```kotlin
package app.thebox.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Line 1 of every `.thebox` file. */
@Serializable
data class BackupHeader(
    val format: String = FORMAT,
    /** Major: a reader refuses a higher major with a reason; minor additions are ignored by older readers. */
    val schema: Int = SCHEMA,
    val minor: Int = 0,
    val createdAtMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val includesLogins: Boolean,
    /** Counts for the restore preview, so the TV can show what is in the file before reading it all. */
    val counts: Map<String, Int>,
) { companion object { const val FORMAT = "thebox-backup"; const val SCHEMA = 1 } }

/** Lines 2…n: one record each. `t` is the class discriminator. */
@Serializable
sealed interface BackupRecord {
    @Serializable @SerialName("settings") data class Settings(val domain: String, val json: JsonElement) : BackupRecord
    @Serializable @SerialName("playlist") data class Playlist(
        val uid: String, val name: String, val type: String, val displaySource: String, val sortIndex: Int, val enabled: Boolean,
        /** User-settable playlist columns only (§4.4); learned columns (etag, learned output, skew, probe winner) are not exported. */
        val settings: Map<String, JsonElement>,
        /** Key into the Secrets record; null when logins are not included. */
        val secretRef: String?,
    ) : BackupRecord
    /** (N2) [ordinal] keys playlist-derived sources: (playlistUid, origin, ordinal). */
    @Serializable @SerialName("epg_source") data class EpgSource(val playlistUid: String?, val name: String, val origin: String, val ordinal: Int, val priority: Int, val enabled: Boolean, val shiftMin: Int, val assumeZone: String?, val secretRef: String?) : BackupRecord
    @Serializable @SerialName("group_pref") data class GroupPref(val playlistUid: String, val kind: Int, val groupKey: String, val hidden: Boolean, val locked: Boolean?, val sortIndex: Int?, val customName: String?) : BackupRecord
    /** User columns of channel_prefs only (never playback_flags, fps_milli: M2 §2.10). */
    @Serializable @SerialName("channel_pref") data class ChannelPref(val playlistUid: String, val stableKey: String, val fields: Map<String, JsonElement>) : BackupRecord
    @Serializable @SerialName("fav_list") data class FavouriteList(val key: String, val name: String, val sortIndex: Int) : BackupRecord
    @Serializable @SerialName("fav") data class Favourite(val listKey: String, val playlistUid: String, val stableKey: String, val sortIndex: Int, val addedAtMs: Long) : BackupRecord
    /** Pending reminders whose start is in the future at export time. */
    @Serializable @SerialName("reminder") data class Reminder(val playlistUid: String, val stableKey: String, val startMin: Int, val endMin: Int, val titleHash: Long, val title: String, val channelName: String, val leadSec: Int, val shiftMin: Int) : BackupRecord
    @Serializable @SerialName("epg_mapping") data class EpgMapping(val playlistUid: String, val stableKey: String, val sourceKey: String?, val xmltvId: String?) : BackupRecord
    @Serializable @SerialName("hide_rule") data class HideRule(val playlistUid: String, val target: Int, val pattern: String, val isRegex: Boolean, val enabled: Boolean) : BackupRecord
    /** At most one; AES-256-GCM over JSON(Map<secretRef, PlaylistSecret>) with AD = the header line's bytes. */
    @Serializable @SerialName("secrets") data class Secrets(val kdf: String, val iterations: Int, val salt: String, val nonce: String, val ciphertext: String) : BackupRecord
    @Serializable @SerialName("end") data class End(val records: Int) : BackupRecord
}

enum class RestoreMode { MERGE, REPLACE }

data class RestorePreview(
    val header: BackupHeader,
    val playlists: List<PlaylistMatch>,
    val needsPassphrase: Boolean,
    val problems: List<String>,
)
data class PlaylistMatch(val uid: String, val name: String, val target: MatchTarget, val existingId: Long?)
enum class MatchTarget { SAME_UID, SAME_SOURCE, NEW }

sealed interface RestoreResult {
    data class Done(val playlistsAdded: Int, val playlistsUpdated: Int, val rows: Int, val loginsRestored: Int, val needsLogin: Int) : RestoreResult
    data class Failed(val reason: RestoreFailure) : RestoreResult
}
enum class RestoreFailure { NOT_A_BACKUP, NEWER_SCHEMA, TRUNCATED, WRONG_PASSPHRASE, CORRUPT_SECRETS, TOO_LARGE, IO }

interface BackupService {
    /** Streams the backup to [sink]. [passphrase] null = no logins. Never holds the whole file in memory. */
    suspend fun export(sink: java.io.OutputStream, passphrase: CharArray?): app.thebox.core.common.Outcome<BackupHeader>
    suspend fun preview(file: java.io.File): app.thebox.core.common.Outcome<RestorePreview>
    suspend fun restore(file: java.io.File, passphrase: CharArray?, mode: RestoreMode): RestoreResult
    /** Weekly worker body: writes to the auto-backup location, keeps AUTO_BACKUP_KEEP files. */
    suspend fun autoBackup(nowMs: Long): app.thebox.core.common.Outcome<String>
}
```

### 2.9 `core/.../diag/DiagnosticsContracts.kt` (WP-E)

```kotlin
package app.thebox.core.diag

data class DeviceFacts(
    val model: String, val manufacturer: String, val os: String, val sdk: Int, val fireOs: String?,
    val memTotalMb: Int, val memoryClassMb: Int, val lowRam: Boolean, val performanceMode: String,
    val abis: List<String>, val hdmiEncodings: List<String>, val hdrTypes: List<String>, val displayModes: List<String>,
    val decoders: List<DecoderFact>, val ffmpegAudio: Boolean, val keystoreBacked: Boolean,
    val freeStorageMb: Long, val network: String,
)
data class DecoderFact(val mime: String, val name: String, val hardware: Boolean, val maxResolution: String?)

enum class TestStep { DNS_SYSTEM, DNS_SECURE, TCP_CONNECT, HTTP, API_LATENCY, STREAM_THROUGHPUT }
data class StepResult(val step: TestStep, val ok: Boolean, val ms: Long?, val detail: String)
data class NetworkTestResult(val playlistId: Long, val steps: List<StepResult>, val verdict: Verdict)
/** Text keys resolved in :ui; [args] are numbers only (never hosts with credentials). */
data class Verdict(val key: VerdictKey, val args: List<String>)
enum class VerdictKey { ALL_GOOD, DNS_BLOCKED_TRY_SECURE, SERVER_UNREACHABLE, TLS_PROBLEM, AUTH_PROBLEM, SLOW_PROVIDER, SLOW_CONNECTION, IPV6_BROKEN }

interface Diagnostics {
    suspend fun deviceFacts(): DeviceFacts
    fun networkTest(playlistId: Long): kotlinx.coroutines.flow.Flow<StepResult>
    /** Builds the redacted export (§3.11.4) into [sink]. */
    suspend fun exportLogs(sink: java.io.OutputStream): app.thebox.core.common.Outcome<Long>
}
```

### 2.10 `core/.../update/UpdateContracts.kt` (WP-E)

```kotlin
package app.thebox.core.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val schema: Int,
    /** (reviewed 2026-10-16, S7) Monotonic per channel; the client stores the highest accepted and rejects lower ones (replay). */
    val sequence: Long,
    val packageName: String,
    val channel: String = "stable",
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val publishedAt: String,
    val notes: String,
    val apks: List<ApkEntry>,
    /** SHA-256 of the APK signing certificate(s), hex, upper case without colons. */
    val signingCertSha256: List<String>,
    val withdrawn: List<Long> = emptyList(),
    /** 0–100; an install whose random bucket (0–99, stored once) ≥ this ignores the release. */
    val rolloutPercent: Int = 100,
)
@Serializable data class ApkEntry(val abi: String, val url: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class UpdatePrefs(val checkAutomatically: Boolean = true, val lastCheckMs: Long? = null, val skippedVersionCode: Long? = null, val bucket: Int? = null, val lastInstalledNotice: Long? = null, val highestSequence: Long = 0)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val manifest: UpdateManifest, val apk: ApkEntry) : UpdateState
    data class Downloading(val versionName: String, val bytes: Long, val total: Long) : UpdateState
    data class NeedsPermission(val versionName: String) : UpdateState
    data class ReadyToInstall(val versionName: String) : UpdateState
    data object Installing : UpdateState
    data class Failed(val reason: UpdateFailure, val detail: String?) : UpdateState
}
enum class UpdateFailure { NETWORK, BAD_SIGNATURE, BAD_MANIFEST, REPLAYED, NOT_NEWER, HASH_MISMATCH, WRONG_PACKAGE, WRONG_CERT, NO_SPACE, INSTALL_BLOCKED, INSTALL_FAILED, DISABLED }

interface Updater {
    val state: kotlinx.coroutines.flow.StateFlow<UpdateState>
    fun check(manual: Boolean)
    fun download()
    fun install()
    fun skip(versionCode: Long)
}

/** The S17 card in the guide (never in FULL). Buttons: Update / Later / Skip this version. */
data class UpdateCardState(val versionName: String, val notes: String, val progress: Float? = null, val failed: String? = null, val focused: Int = 0)
enum class UpdateAction { UPDATE, LATER, SKIP }
```

`ExternalHandOff` (in `player/.../api/PlaybackApi.kt`, §2.12.6): `data class ExternalHandOff(val url: String, val headers: Map<String, String>, val title: String?)` with `toString()` = `"ExternalHandOff(${Redact.host(url)})"`.

### 2.11 `core/.../net/NetworkSettings.kt` (WP-A)

```kotlin
package app.thebox.core.net

@kotlinx.serialization.Serializable
data class NetworkSettings(
    val dns: DnsChoice = DnsChoice.SYSTEM,
    /** Custom DoH endpoint (https://…/dns-query) when dns == CUSTOM. */
    val customDohUrl: String? = null,
    val preferIpv4: PreferIpv4 = PreferIpv4.AUTO,
    val connectTimeoutSec: Int = 10,
    val readTimeoutSec: Int = 20,
)
enum class DnsChoice { SYSTEM, CLOUDFLARE, GOOGLE, QUAD9, CUSTOM }
enum class PreferIpv4 { AUTO, ON, OFF }
```

### 2.12 Amendments to existing contracts (commit 0, additive)

1. **`InputContracts.kt`**: append `BoxAction.OPEN_SEARCH("open_search", true)` and `BoxAction.OPEN_SETTINGS("open_settings", true)` (append-only ids). `Keys` gains `SETTINGS = 176`, `VOLUME_UP = 24`, `VOLUME_DOWN = 25`, `VOLUME_MUTE = 164`, `MUTE = 91`, `HOME = 3`, `POWER = 26`, `TV_POWER = 177`, `SLEEP = 223`, `WAKEUP = 224` (reserved, never bound). `DefaultKeyMap` gains `guideShort[Keys.SEARCH] = OPEN_SEARCH` and `both(Keys.SETTINGS, OPEN_SETTINGS)` (WP-C edits `DefaultKeyMap.kt`).
2. **`GuideStateContracts.kt`**: `GuideStateMachine` gains `var settings: app.thebox.core.settings.SettingsMachine?` (default `get() = null; set(_) = Unit` like `full`), `val textInputActive: Boolean get() = false`; `GuideUiState` gains `pin: PinPromptState? = null` and `update: UpdateCardState? = null`; `GuideEvent` gains `data class OpenSetup(val route: SettingsRoute)`, `data class Parental(val state: ParentalState)`, `data class PinChecked(val token: Long, val check: PinCheck)`, `data class KeyHints(val hints: Map<BoxAction, List<Int>>)`, `data class Update(val card: UpdateCardState?)`; `GuideEffects` gains `fun verifyPin(token: Long, digits: CharArray)` (the effect zeroes the array), `fun openSettings(route: SettingsRoute)` (overload; the old `openSettings()` = `Root`), `fun stopPlayback()` and `fun updateAction(action: UpdateAction)`; `MenuItem` appends `LOCK_CHANNEL, UNLOCK_CHANNEL, FIX_GUIDE_DATA, CHANNEL_OPTIONS`; `GroupMenuItem` appends `LOCK, UNLOCK`.
3. **`GuideFeedContracts.kt`**: `GuideSourceEntry` gains `val locked: Boolean = false` (true only when the entry is a locked group and access is not OPEN; its `count` is then the group's channel count).
4. **`PlayerContracts.kt`**: `QuickItem` appends `SOURCES`; `PickerKind` appends `SERVER`; `ErrorButton` appends `OPEN_SETTINGS, USE_SECURE_DNS, REFRESH_PLAYLIST, OPEN_EXTERNAL`; `ErrorCardState` gains `val route: SettingsRoute? = null` (the OPEN_SETTINGS target); `ChannelMenuItem` appends `LOCK_CHANNEL, CHANNEL_OPTIONS`. `PlayerSettings.kt`: `@Serializable` on `PlayerSettings`.
5. **`ui/.../input/KeyRouter.kt`** (in `:ui`): `fun capture(listener: ((CapturedKey) -> Unit)?)` (null disarms). While armed the router delivers the next DOWN with repeat 0 of any key to the listener instead of the key map, consumes that DOWN and its UP, and ignores repeats. (reviewed 2026-10-16, S14) After the capture the router **swallows every event of the captured code until its UP**, even after disarm, so a held key's repeats never re-enter routing as a fresh press; focus loss disarms. BACK/ESCAPE/BUTTON_B are delivered too (the screen treats them as "cancel"); keys the framework never delivers (Home, power, voice, app buttons, and volume on most TVs) cannot be captured. **`textInputActive`** (reviewed 2026-10-16, S3): at the top of `onKey` (S12: `onKey`, with the existing test seam `onNative`), while `machine.textInputActive` is true the router returns false for every key **except BACK/ESCAPE/BUTTON_B**, which it consumes and dispatches as `SettingsEvent.EditorBack`. While the IME is shown it consumes BACK in its own input stage first, so this only matters when the IME is hidden or the editor lost focus, and it guarantees BACK never reaches the Activity's default handling (no `BackHandler` exists in `:ui`/`:app`).
6. **`PlaybackApi.kt`** (`PlaybackController`): `fun reapplySettings()` (default no-op): re-prepares the current **live** tune with the current settings at the live edge, counts as one re-prepare in the event log, never a new engine; `fun handOffExternal(): ExternalHandOff?` (default null): stops the stream (frees the connection) and returns the resolved URL and headers for the external-player intent; `PreviousResult.Locked(identity)` and `StopReason.LOCKED` for the `TuneGuard` (B1, §3.9.2). (reviewed 2026-10-16, S9) The archive case cannot live in `:player` (`ArchivePlaybackImpl` is in `:app`), so **`core/.../catchup/archive/LiveSettingsApplier.kt`**: `fun interface LiveSettingsApplier { fun apply() }`, implemented in `:app` by `LiveSettingsApplierImpl`: when `ArchivePlayback.status.phase` is active it calls `archive.resume()` at the playhead (a re-request with the new settings), else `controller.reapplySettings()`. `SettingsEffectsImpl.onClosed` calls it.
7. **`PlaybackContracts.kt`**: `AudioOutputMode` appends `FORCE_PASSTHROUGH` ("Always passthrough", decision 2026-10-14). (reviewed 2026-10-16, B2; WP-A) **What the engine does:** the deny-only `PassthroughGateAudioSink` cannot force anything, because `DefaultAudioSink.configure` derives the output encoding from its own `AudioCapabilities`. FORCE_PASSTHROUGH therefore builds the `DefaultAudioSink` in `BoxRenderersFactory.buildAudioSink` (`BoxRenderersFactory.kt:39-45`) with a capabilities override that adds AC-3, E-AC-3, DTS and TrueHD to what the platform reports (the 1.11.1 builder hook named by F19 in commit 0: `DefaultAudioSink.Builder.setAudioCapabilities(AudioCapabilities)` if it still exists, else a custom `AudioCapabilities`/audio-output provider passed to the builder; if neither exists in 1.11.1, the row ships as "Not available on this version" and the lead is told before WP-A starts). Because the sink is built once per engine, the row's `EffectTiming` is **RESTART** (with Restart now) unless F19 finds a runtime hook. **Fallback:** the existing paths only: an `AudioTrack` initialisation failure (ladder L34, `FailureClassifier.kt:98-116`, `PlaybackControllerImpl.kt:1342-1345`) or `AUDIO_NOT_STARTED` (`StallDetectors.kt:46-47`, the audio clock never advances) drops to PCM for the route with the toast "Your TV or receiver refused surround sound, so The Box decodes it instead." There is **no silence detector** and none is designed: with a bitstream the sink cannot observe whether the receiver outputs sound, so it is not feasible. The row's description says "If you hear no sound, turn this off", and while FORCE_PASSTHROUGH is on the audio picker and the stream-info passthrough line offer a one-tap **Back to Auto**. The decision note's "3 s silence watchdog" sentence is corrected (sign-off, B2).
   **F19 finding (commit 0, 2026-10-16; media3-exoplayer 1.11.1 sources):** `DefaultAudioSink.Builder.setAudioCapabilities(AudioCapabilities)` still exists but is `@Deprecated` and **ignored when the builder has a Context** (`build()` passes `context != null ? null : audioCapabilities`), and `AudioCapabilities(int[], int)` is deprecated too; the real capabilities come from the `AudioCapabilitiesReceiver` that `AudioTrackAudioOutputProvider` registers. The 1.11 hook is the **audio output provider**: `DefaultAudioSink.Builder(context).setAudioOutputProvider(provider)` with `provider = ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build())` subclassed (all `@UnstableApi`, public builders). Passthrough is decided there: `DefaultAudioSink.supportsFormat/getFormatSupport` → `AudioOutputProvider.getFormatSupport(FormatConfig)` (→ `AudioCapabilities.isPassthroughPlaybackSupported`), and `configure` → `getOutputConfig(FormatConfig)` (→ `getEncodingAndChannelConfigForPassthrough`; null throws `AudioOutputProvider.ConfigurationException("Unable to configure passthrough")`); `MediaCodecAudioRenderer.shouldUseBypass` = `audioSink.supportsFormat(format)`. The override, for a forced MIME the delegate rejects: `getFormatSupport` returns `FormatSupport.Builder().setFormatSupportLevel(FORMAT_SUPPORTED_DIRECTLY)`; `getOutputConfig` catches the delegate's `ConfigurationException` and builds `OutputConfig.Builder()` with `MimeTypes.getEncoding(mime, codecs)`, the channel mask, sample rate, a passthrough buffer size from `AudioTrackBufferSizeProvider.DEFAULT` and the attributes/session of the `FormatConfig`. Do not combine `setAudioOutputProvider` with `setAudioTrackBufferSizeProvider`/`setAudioOffloadSupportProvider`/`setAudioTrackProvider` (`build()` `checkState`s it). Where: `BoxRenderersFactory.buildAudioSink` (`BoxRenderersFactory.kt:40-44`); the force set sits next to `deniedPassthrough` in `AudioOutputPolicy` (`PassthroughGateAudioSink.kt:17-36`); the `ForwardingAudioSink` gate (`:46-51`) stays the deny side; `PlayerFactory.kt:66-75` is unchanged. An unsupported encoding at `AudioTrack` creation fails as `AudioOutputProvider.InitializationException` → `AudioSink.InitializationException` (`isRecoverable = false` for passthrough) through `onAudioSinkError`, i.e. the existing L34 path. **Runtime switch: yes.** The provider reads a `@Volatile` mode on every `getFormatSupport`/`getOutputConfig`, and `configure` runs on every prepare, so a change takes effect at the next re-prepare without rebuilding the sink or the engine (the same timing as `deniedPassthrough` today; posting `AudioOutputProvider.Listener.onFormatSupportChanged()` to the playback thread triggers track reselection, but does not flip codec/bypass on a running renderer). So `playback.audio_output` (including FORCE_PASSTHROUGH) is **REPREPARE**, not RESTART; RESTART stays only for "Software audio fallback". Not chosen: `AudioSink.setAudioOutputProvider` / `MSG_SET_AUDIO_OUTPUT_PROVIDER` / `ExoPlayer.Builder.setAudioOutputProvider` (a fresh provider each time, same bypass limitation); swapping a `ForwardingAudioSink` delegate (a private final field).
8. **`DataContracts.kt`**: `interface SecureBlobStore { suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit>; suspend fun getBlob(ref: String): Outcome<ByteArray>; suspend fun deleteBlob(ref: String) }`, implemented by `TinkCredentialStore` on the same AEAD with AD = ref. Reserved refs: `parental-pin`, `backup-passphrase`. `wipe()` (permanent key loss) still deletes every row (so the PIN verifier and the stored backup passphrase go too) and now also writes the flag `pin_lost_to_key_reset` in SharedPreferences `parental_attempts`, which `ParentalSessionImpl` exposes as `ParentalState.pinLostToKeyReset` (§3.9.6).
9. (reviewed 2026-10-16, S10) **`GuideLibrary`/`GuideSearch`/`EpgMatchRepository.unmatched`** replace `unlocked: Boolean` with `access: ParentalAccess`, passed explicitly; no query reads global parental state. The six call sites (`GuideViewModel.kt:335`, `PlayerViewModel.kt:263, 389`, `SearchViewModel.kt:108`, `GuideEffectsImpl.kt:83, 212`) are changed mechanically in commit 0 and fed by WP-C from `ParentalSession.state` (a source in `openedAnyway` is queried with OPEN). Semantic change for an existing caller, documented in the KDoc: `sources(access)` now lists locked groups with `locked = true` instead of omitting them (unless `lockedInGuide = HIDE`).
10. **`PlaylistEntity`**: `uid` gets a generating default (`UUID` at construction), so every insert path (tests, debug receiver, fixtures) has one (N6).

### 2.13 Threading, ownership and Hilt

- Machines (`SettingsMachine`, the guide and player machines) run on main and never block; every store write, PBKDF2, backup and probe runs on IO (`@IoDispatcher`). (reviewed 2026-10-16, S15) KDF iteration counts are **set by a commit-0 microbenchmark** on the API 30 x86 emulator: `PIN_KDF_ITERATIONS` so one PIN check takes ≤ 150 ms there (expected 10,000–20,000: the Keystore AEAD and the lockout are the real protection), `BACKUP_KDF_ITERATIONS` so one derivation takes ≤ 1 s there; both counts are stored with the blob and in the backup header, and re-measured on the Fire TV Stick 4K in M6.
- `SettingsStore`, `ParentalSession`, `TuneGuard`, `KeyBindingStore`, `EffectiveKeyMap`, `PhoneLink`, `Updater`, `Diagnostics`, `BackupService` are `@Singleton`. `SettingsMachine` is created by `SettingsViewModel` (activity-scoped VM, like the player VM). (reviewed 2026-10-16, S11) The two activity VMs have no creation order, so: `GuideViewModel.startup()` (`:453`; `GuideStateMachine` itself has no `startup`) awaits a `settingsAttached` signal set when `SettingsViewModel` attaches its machine, and the guide machine treats `settings == null` in SETTINGS as "waiting" (keys ignored except BACK on FirstRun, which exits).
- Hilt modules: `SettingsDataModule` (A), `SettingsBindingsModule` (A, `:app`), `SetupModule` + `ProbeModule` (B), `ParentalModule` + `InputModule` edit (C), `BackupModule` (D, also binds `PhoneLink`), `DiagModule` + `UpdateModule` (E).

---

## 3. Screens and behaviour

### 3.1 Visual language and layout (WP-A kit; design px on the 1920 × 1080 canvas)

Same language as the guide (`01-design-reference.md`): canvas `#0A0D13`, glass panels `rgba(255,255,255,.045)` with a 1 px `.09` border and **no blur** (plan §3), solid focus `#EEF1F6` with dark text `#0A0D13` and a 3 px accent ring `#5CA8F2` (the hex is normative, M3 §0 item 12; N15), Instrument Sans for text, JetBrains Mono for numbers, times, URLs and codes. SETTINGS is opaque: it covers the hole (viewport HIDDEN, audio continues, as the M3 placeholder did, `AppRoot.kt:75-79`). **Two-pane** layout (product S11), chosen over single-pane because the section list stays visible as a map of where you are, and a 1920-wide canvas has room; the left pane is a stable Back target.

(reviewed 2026-10-16, N7) **The kit must not use tv-material `Switch`, `RadioButton` or `ListItem`**: they carry fixed dp sizes and a focus scale through a `graphicsLayer`; the kit draws its own toggle, radio and rows below.

**No `Popup` or `Dialog` anywhere in M5.** Every overlay (choice list, menu, confirm card, editor, number pad, PIN pad, capture) is an in-tree composable inside the root key-sink `Box`, so `BoxDesignScale` applies without re-wrapping and the root-tree rule (`RootTreeRuleTest`) holds. If a later change needs a `Popup`/`Dialog`, its content must be wrapped in `BoxDesignScale` (design reference). System UI (IME, package installer, SAF picker, permission dialog) is outside our tree and is not styled.

| Element | Geometry | Style |
|---|---|---|
| Header | title at (64, 48), height 56 | "Settings" 40/600; breadcrumb mono 16 `.55` at (64, 104): `Settings › Playback › Audio output`; clock 34/500 tabular at right 64, top 48 (as the guide) |
| Sections pane | x 64, y 144, w 420, h 872 | glass, radius 24, padding 16; rows 64 high, gap 6, radius 12, label 22/600 at x + 24; the "Show advanced settings" toggle pinned at the pane bottom (row 64, 1 px `.09` rule above) |
| Section row states | | focused: FG fill, dark text, accent ring; **current** (focus in the page): `.08` fill + 4 × 32 accent bar at x + 8; normal: transparent, text `.85` |
| Page pane | x 516, y 144, w 1340, h 872 | glass, radius 24, padding 24 × 32; page title 30/600, subtitle 18 `.6` (one line), rows start 96 below the pane top |
| Row | w 1276, h 76 (one line) or 104 (with description) | gap 8, radius 14, padding 0 × 24; label 22/500 `.92`; value right-aligned 20 `.65` (mono for numbers, times, hosts); description 17 `.55` under the label (one line, ellipsis); link chevron `›` 22 `.45` at the right edge |
| Toggle | 56 × 30 pill at the right edge | on: accent fill, knob 24 `#EEF1F6` right; off: `.16` fill, knob left; on a focused row On keeps its colours, the row turns FG, and Off draws a `.18` canvas-colour fill with a canvas-colour knob (U-S9, code review 2026-10-16: the white `.16` fill and the FG knob vanished into the FG row) |
| Inline choice list | under its row, indent 48, rows 60 | radio dot 18 (selected: accent fill; else 2 px `.45` ring); option label 21; detail 17 `.55`; the list pushes the rows below down (no overlay) |
| Disabled row | as a row | text `.4`; still focusable; description shows `disabledReason` |
| Notice line | pane bottom, height 40 | 18 `.7`, fades after `NOTICE_MS` (3,000) |
| Confirm card | centred 720 wide | `rgba(20,25,36,.96)` fill (no blur), 1 px `.12` border, radius 24, padding 32; title 30/600; body 21 lh 1.45 `.8`; buttons as the error card (19/600, padding 14 × 26, radius 14), focus defaults to the **safe** button |
| Context menu (list items) | anchored right of the item, 420 wide | same fill as the confirm card; rows 60 |
| Editor overlay | card 1100 wide, centred horizontally, top 120 | label 22/600; field 64 high, mono 26, 2 px accent underline when focused; chips row (radius 999, mono 18, padding 10 × 18, gap 12); error line 18 amber `#F2B25C`; Done/Cancel buttons; the card sits in the top 45 % so the IME (bottom half) does not cover it |
| Number pad | card 560 wide centred | title 28/600; value mono 44; grid 3 × 4 of keys 112 × 80 (gap 12, radius 14, mono 30): 1–9, ⌫, 0, OK; focused key FG fill |
| PIN pad (WP-C) | card 560 wide centred | as the number pad, but four dots (22 diameter, gap 24, filled = FG) instead of the value; lockout line 19 amber with a mono countdown; "Forgot the PIN?" line 17 `.55` |
| Lists in management pages | as rows; item rows 72 | badges as 14 mono chips (radius 7, `.22` border): `LOCKED`, `HIDDEN`, `18+`; the lock uses `GuideAsset.LOCK_GLYPH` (exists) |
| Move mode | the picked row lifts: 1 px accent border, `.12` fill, a `↕` glyph 22 at the left | |

Visible rows: (872 − 96 − 24) / 84 ≈ 8.9, so about eight one-line rows per screen. Text never wraps in rows; long values ellipsize in the middle (`…`) for URLs and hosts.

### 3.2 Settings shell: navigation and keys (WP-A)

#### 3.2.1 Panes and the route stack

- **Open** (`open(route, fromFull)`): Root → focus in SECTIONS on the last section used this process (else PLAYLISTS). Any other route pushes `[Root, …parents, route]` and focuses PAGE; the sections pane highlights the route's section (`SettingsTree.sectionOf(route)`).
- **Cursor memory**: per route, `RowId` first, then the index (pages rebuild when data changes; the cursor follows the row id).
- **Scrolling**: one-row lookahead (the focused row is never the last visible one unless it is the last row), like the guide's rows (product §3.4). Paged lists request `loadItems(route, from, to)` for `[cursor − 16, cursor + 32)`; unloaded rows draw as placeholders (row height, `.03` fill).

#### 3.2.2 Key tables

SECTIONS pane:

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Previous/next section (no wrap); from the last section ↓ reaches the "Show advanced settings" toggle |
| → / OK | Focus the section's page (OK on the toggle flips it; advanced sections appear/disappear at once, the cursor stays on the toggle) |
| ← | Nothing (left edge) |
| BACK | Leave Settings (`act` returns false → guide machine: GUIDE, or FULL when opened from the quick bar). On FirstRun this pane does not exist |
| Long OK / Menu | Nothing |
| CH± / PAGE_UP/DOWN | Page ±6 sections (accelerator) |

PAGE pane (no overlay open):

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Previous/next row (hold: repeats at the router's rate, no acceleration needed below 200 rows; paged lists accelerate after `HOLD_ACCEL_MS` 600 to 3 rows per repeat) |
| OK | By kind: Toggle flips (intent at once); Choice opens the inline list on the selected option; MultiChoice opens it with checkboxes; Text opens the editor; Number opens the number pad; Action runs (confirm card first when `confirm != null`; PIN first when `needsPin` and a PIN is set); Link pushes its route; Info does nothing; Item opens `route` or runs `primary` |
| → | Link and Item with a route: push (same as OK). Others: nothing |
| ← | Pop to the parent page; on a section's top page: focus SECTIONS |
| BACK | As ←, and from the section's top page focus SECTIONS. A changed REPREPARE row is applied only when Settings closes (§0.6) |
| Long OK / Menu | Item rows with a `menu`: open the context menu. Other rows: nothing |
| CH± / PAGE_UP/DOWN | Page ±8 rows |
| Digits | Number rows: open the number pad with that digit typed; elsewhere nothing |
| PLAY_PAUSE | Nothing (audio of the tuned channel continues behind Settings) |
| OPEN_GUIDE (Guide key) | (reviewed 2026-10-16, N11) leaves Settings to the guide (any pane, any depth; overlays close first) |
| EXIT | opens the exit dialog from anywhere, as in M3/M4 |

Inline choice list:

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Previous/next option (disabled options are skipped) |
| OK | Choice: select, close, intent `Choose`. MultiChoice: toggle the focused option; the list stays open |
| BACK / ← | Close (Choice: unchanged; MultiChoice: one `ChooseMany` with the toggled set) |

Context menu (items), confirm card and the number pad:

| Layer | ↑/↓ | ←/→ | OK | BACK |
|---|---|---|---|---|
| Context menu | options | nothing | run `Menu(optionId)`, close | close |
| Confirm card | nothing | buttons | the focused button (focus starts on the safe one) | Cancel |
| Number pad | grid rows | grid columns | the focused key (OK key commits if in range, else error line) | ⌫ when digits exist, else close unchanged |
| Number pad, digit keys | | | append | |
| Move mode | move the lifted row one place (hold repeats) | nothing | drop: intent `Move(row, index)` | cancel, row returns |

Editor overlay (Compose owns every key except BACK; `textInputActive` true; reviewed 2026-10-16, S3): the field takes focus and the IME opens; IME action **Next/Done** = Done; D-pad ↓ from the field moves to the chips row through the **same `onPreviewKeyEvent` Down handler M3 added for the single-line search field (G5, `SearchScreen.kt`)**, because a single-line `BasicTextField` swallows Down; ↑ from the chips returns to the field; → / ← within chips, OK on a chip inserts it at the cursor (`http://` and `https://` replace an existing scheme), ↓ to Done/Cancel. BACK with the IME showing closes the IME (the IME consumes it first); BACK with the IME hidden reaches the router, which dispatches `EditorBack`: from the chips/buttons → back to the field, from the field → Cancel. **No keyboard app** (`InputMethodManager.getEnabledInputMethodList()` empty and no hardware keyboard, §9 F5): the overlay shows "No keyboard on this TV: use your phone" with a **Set up from phone** action (PhoneSetup) instead of the field. Done validates through the row's validator (URL, regex, header); a failure keeps the overlay open with the error line. `EditorClosed` returns to the machine and the sink takes focus back (`AppRoot` refocus, as after SEARCH).

#### 3.2.3 Simple and advanced

`GuidePrefs.advancedSettings` (default Off). Advanced rows and the NETWORK section are absent from the page models when it is off (not drawn dimmed). Every row id carries its visibility in `SettingsTree`; `SimpleRowCountTest` counts simple rows across all sections, counting the playlist page once: **≤ 35** (product §4 target; §3.3 counts exactly 35).

#### 3.2.4 Back ladder in SETTINGS

`overlay (editor → EditorBack; capture → cancel; number pad / confirm / menu / choice / move → close)` → pop the page stack → focus SECTIONS (a detached route has none: leave at once, S2) → leave Settings (`settings.act(BACK)` returns false; the guide machine applies the M4 rule: FULL if opened from FULL, else GUIDE). FirstRun: the Welcome page's BACK exits the app with no confirmation (product S1); inside the first-run flow BACK pops toward Welcome. `SettingsBackLadderTest` checks every overlay × stack depth.

#### 3.2.5 Entry points

| From | Route | PIN when "Lock settings" is on |
|---|---|---|
| Guide tab-row gear, Groups-panel Settings shortcut (`GroupsShortcut.SETTINGS`) | Root | yes |
| Quick bar → Settings (FULL) | Root, `fromFull` | yes |
| `OPEN_SETTINGS` key (`Keys.SETTINGS`, remappable) | Root | yes |
| Startup with no playlist (`SetupState.completed == false` and no row in `playlist`) | FirstRun | no |
| Guide empty state NO_PLAYLIST ("Add one in Settings", M3 §3.8.4) → OK | AddSource() | yes |
| Error-card OPEN_SETTINGS (§3.14) | the card's route, `fromFull`, **detached** | no PIN for the route itself; its links into gated routes ask (S2) |
| Context menu → Channel options / Fix guide data | Channel(id) / FixGuide(id) | yes |
| Sync chip failure → OK (guide header; M3 chip becomes focusable in Zone.HEADER, WP-C) | SyncStatus, **detached** | no; "Edit" in its menu asks (S2) |

(reviewed 2026-10-16, S2) A **detached** route opens as a standalone layer with nothing of Settings behind it: no sections pane, no Root in the stack; BACK leaves SETTINGS (to GUIDE, or FULL when opened from FULL). So the PIN-free entries cannot be used to walk into the rest of Settings with "Lock settings" on.

### 3.3 The settings tree (every row)

Columns: **Row** (id), **Type**, **Default**, **Scope** (G global, P per playlist, C per channel), **Vis** (S/A), **Storage** (domain.field or table.column), **Read by**, **Effect** (§2.1). Labels are the user text (strings resources). A row's description states its effect whenever it is not LIVE.

#### 3.3.1 Playlists

Section page: one Item row per playlist (`name`, value `Xtream · 2,140 channels · updated 14:02`, badges FAILED / `Needs login` / `Expires in 5 days`), then **Add playlist** (Link → AddSource), **Set up from phone** (Link → PhoneSetup), and (A) **Refresh all now**. Playlist page (`Playlist(id)`): an account card first (Info, not counted: Status · expiry with countdown, amber under 7 days, red when expired · connections `1 / 2` · formats · server time zone and skew), then:

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `playlist.name` | Text | host name | P | S | `playlist.name` | Groups panel headers, lists | LIVE |
| `playlist.source` | Link → PlaylistSource | – | P | S | credential secret, `playlist.display_source` | refresh, resolver | NEXT_REFRESH (a refresh starts on save) |
| `playlist.refresh` | Action "Refresh now"; value = last report ("Today 14:02 · +12 new · −3 removed · 2 URLs changed · favourites kept") | – | P | S | `refresh_log` | `RefreshScheduler.refreshNow(MANUAL)` | LIVE |
| `playlist.update_on_start` | Toggle "Update when The Box starts (if older than 12 h)" | On | P | S | `playlist.update_on_start` | `RefreshScheduler.onAppStart` | NEXT_LAUNCH |
| `playlist.numbering` | Choice Provider / Playlist order / Start at N (N: number pad) | Provider | P | S | `playlist.numbering_mode` | merge | NEXT_REFRESH ("Refresh now" offered in the notice) |
| `playlist.name_prefix` | Choice Auto / Off / Custom; Custom opens the rule editor (literals, one per line, or one regex) with a live preview "Removes the prefix from 1,204 of 2,140 names" | Auto | P | S | `playlist.name_prefix_{mode,custom}` | `DisplayNames.setRule` (one UPDATE pass, M3 §3.12) | LIVE |
| `playlist.remove` | Action, destructive; confirm "Remove {name}? 2,140 channels, 14 favourites and 3 reminders go with it." | – | P | S | `PlaylistRepository.delete` | – | LIVE; PIN when "Lock settings" |
| `playlist.enabled` | Toggle "Show in the guide" | On | P | A | `playlist.enabled` | lineups | LIVE |
| `playlist.interval` | Choice 6 h / 12 h / 24 h / Manual | 24 h | P | A | `playlist.refresh_interval_h` | `RefreshScheduler.schedulePlaylist` | LIVE (reschedules) |
| `playlist.order` | Choice Provider / Number / Name | Provider | P | A | `playlist.order_mode` | merge | NEXT_REFRESH |
| `playlist.playlist_epg` | Toggle "Use guide data from the playlist" | On | P | A | `playlist.use_playlist_epg` | EPG sources | NEXT_EPG_IMPORT |
| `playlist.user_agent` | Choice The Box / VLC / Browser / Custom (Text) | The Box; after a successful onboarding or M2 L5 probe: VLC with the note "Your provider only answers VLC" | P | A | `playlist.user_agent` | resolver, fetcher | NEXT_TUNE, NEXT_REFRESH |
| `playlist.headers` | Item list (key = value) + "Add header" | empty | P | A | `playlist.extra_headers_json`; secret ones (`Authorization`, `Cookie`) in the credential secret | resolver, fetcher | NEXT_TUNE |
| `playlist.stream_format` | Choice "Stream format: Auto / MPEG-TS / HLS" (**Xtream only; absent otherwise**); value "Auto (using HLS: the server refused TS)" when `xtream_output_learned` is set | Auto | P | A | `playlist.xtream_output`; choosing clears `xtream_output_learned` (`PlaylistDao.setXtreamOutput`) | resolver | NEXT_TUNE |
| `playlist.backup_servers` | Item list of base URLs (host shown) + "Add server" (editor with URL chips); "Active: server 2 of 3" | empty | P | A | `PlaylistSecret.urls[1…]` | resolver rotation, Sources picker | NEXT_TUNE |
| `playlist.catchup_mode` | Choice Auto / Xtream / Default / Append / Shift / Flussonic / Off | Auto | P | A | `playlist.catchup_mode_setting`; clears `catchup_probe_winner` | catch-up launcher | NEXT_TUNE |
| `playlist.catchup_days` | Number 0–30 ("0 = as the provider says") | 0 | P | A | `playlist.catchup_days_override` | resolver | NEXT_TUNE |
| `playlist.catchup_zone` | Choice Auto / UTC / Device / Server / Other (IANA id, editor with validation) | Auto | P | A | `playlist.token_zone_setting` | `TimeBase` | NEXT_TUNE |
| `playlist.padding_before`, `playlist.padding_after` | Choice 0/1/2/5 min; 0/2/5/10 min | 1 / 5 | P | A | `playlist.padding_{before,after}_sec` | `TimeBase` | NEXT_TUNE |
| `playlist.max_connections` | Xtream: Info "From your account: 2"; M3U: Number 1–10 | account / 1 | P | A | `playlist.max_connections_override` | broker | NEXT_TUNE |
| `playlist.ignore_tls` | Toggle with confirm ("Only for servers with a broken certificate. Anyone on the network path could read your login.") | Off | P | A | `playlist.ignore_tls_errors` | clients | NEXT_TUNE, NEXT_REFRESH |
| `playlist.network_test` | Link → NetworkTest(id) | – | P | A | – | – | – |

PlaylistSource page (edit in place, `playlist_id` and therefore every `(playlist_id, stable_key)` row stays; product #6): Xtream: Server (Text, URL chips), Username, Password (masked; the editor has a show toggle), **Test and save** (Action → result card §3.4.4; saves with `SourceSetup.saveXtream(form, existingId)` → `replaceSecret`); M3U: Playlist URL or file, EPG URLs (list), **Save**. Subtitle: "Your favourites, hidden channels and settings are kept when you change the source."

#### 3.3.2 TV guide

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `guide.sources` | Link → EpgSources (list: enable, priority by move mode, add URL, per source: time shift ±12 h, zone for offset-less times, remove) | playlist-provided | G | S | `epg_source` | importer, resolver | NEXT_EPG_IMPORT |
| `guide.status` | Link → SyncStatus; value "Updated 06:00 · 412k programmes · 89 % matched" or "Failed: HTTP 403" | – | G | S | `refresh_log`, `SyncStatusRepository` | – | – |
| `guide.fix_unmatched` | Link → FixGuide(); value "228 channels" | – | G | S | `epg_mapping` | matcher | LIVE (per channel, §3.6.3) |
| `guide.interval` | Choice 6 h / 12 h / 24 h / Daily at a set time (hour 00–23 via the number pad) (reviewed 2026-10-16, N4: restored from product §4.3) | 12 h | G | A | `data.epgRefreshIntervalH`, `data.epgDailyAtHour` (new `DataPrefs` field, null = interval) | `RefreshScheduler.scheduleEpgPeriodic` (daily: periodic 24 h with an initial delay to the next such hour, flex 1 h) | LIVE |
| `guide.matching` | Choice Exact only / Exact and names / And fuzzy suggestions / And fuzzy automatic (≥ 0.92) | And fuzzy suggestions | G | A | `data.matchLevel` | matcher | NEXT_EPG_IMPORT |
| `guide.shift` | Choice −12 h … +12 h in 0.5 h | 0 | G (P per source, C per channel) | A | `data.globalShiftMin` | lineup shift (M1 §2.2) | LIVE (lineup re-query) |
| `guide.past_days` | Choice Auto / 1 / 2 / 3 / 7 | Auto (max catch-up days of enabled playlists, minimum 1, maximum 7) | G | A | `data.epgPastDays` (0 = Auto) | importer | NEXT_EPG_IMPORT |
| `guide.future_days` | Choice 3 / 7 / 14 | 7 | G | A | `data.epgFutureDays` | importer | NEXT_EPG_IMPORT |
| `guide.descriptions` | Toggle "Store programme descriptions" | On (Off in low-RAM mode) | G | A | `data.storeDescriptions` | importer | NEXT_EPG_IMPORT |
| `guide.posters` | Toggle "Programme posters" (TMDB, when the guide has no picture; the app's built-in key only, `BuildConfig.TMDB_KEY` from `local.properties`; description: the privacy line, or "Needs a TMDB key: this build has none." when the build has none, or "TMDB rejected the app's key. Update The Box." after a 401; no user-key row) — owner rulings 2026-10-18 and 2026-10-19, `docs/decisions/2026-10-18-tmdb-programme-posters.md` | On | G | A | `guide.posters` | `TmdbProgrammeArtwork` (focused panel) | LIVE |

SyncStatus page (product S15): first row **Update guide now** (Action, `EpgWorkRequester.importEpgNow(MANUAL)`), then one Item per playlist and EPG source with its state (Queued / Downloading 120 MB / Parsing / Done / **Failed: HTTP 403. The URL may have changed or the account expired** with menu Edit · Retry · Dismiss). Dismiss acknowledges (`SyncStatusRepository.acknowledge`). (amended 2026-11-02) A later successful run of the same playlist or guide acknowledges its earlier failures by itself (`RefreshLogDao.insert`), so Dismiss is only needed for a failure that has not been followed by a success.

#### 3.3.3 Playback

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `playback.buffer` | Choice Auto (adaptive) / None (live TV, fastest zapping) / Small / Medium / Large / Extra large | Auto | G (+C) | S | `playback.bufferMode` | controller `applySwitches` | REPREPARE |
| `playback.afr` | Choice Off / Seamless only / Always. **API < 30** (reviewed 2026-10-16, N5: product §4.4 hid the row; the lead ruled to show it): the row is shown with Off / Always and the note "Seamless only needs Android 11; Always switches the TV's mode, which can blank the screen for a moment"; a stored SEAMLESS shows as Off | Seamless only (API 30+), Off below | G | S | `playback.afrMode` | controller AFR collector (`PlaybackControllerImpl.kt:840`) | LIVE |
| `playback.audio_output` | Choice Auto / Always decode (PCM) / Stereo / **Always passthrough** ("Sends surround sound to your TV or receiver even when it does not say it supports it. If you hear no sound, turn this off." reviewed 2026-10-16, B2) | Auto | G | S | `playback.audioOutput` | passthrough gate (Auto/PCM/Stereo); the sink's capabilities override (Always passthrough, §2.12.7) | REPREPARE for every value, Always passthrough included (F19, commit 0: the `AudioOutputProvider` reads the mode on every prepare; lead ruling §1.9a) |
| `playback.aspect` | Choice Fit / Fill / Zoom / 16:9 / 4:3 | Fit | G (+C remembered) | S | `playback.defaultAspect` | controller | NEXT_TUNE |
| `playback.on_launch` | Choice Guide with last channel / Full screen, last channel / Guide, nothing playing | Guide with last channel | G | S | `player.onLaunch` | startup | NEXT_LAUNCH |
| `playback.audio_language` | Page: First / Second / Third language (Choice over ISO 639 names, "Device language" first) | device language | G (remembered per channel) | A | `playback.preferredAudioLanguages` | track selection | NEXT_TUNE |
| `playback.subtitles` | Choice Off / On (preferred language) / Forced only | Off | G (remembered per channel) | A | `playback.subtitles`, `preferredTextLanguages` | track selection | NEXT_TUNE |
| `playback.decoder` | Choice Auto / Hardware only / Software preferred | Auto | G (+C) | A | `playback.videoDecoderMode` | codec selector | REPREPARE |
| `playback.ffmpeg` | Toggle "Software audio fallback" | On | G | A | `playback.softwareAudioFallback` | `PlayerFactory.create` | RESTART ("Takes effect the next time The Box starts" + **Restart now**, N9: relaunches the activity with `FLAG_ACTIVITY_CLEAR_TASK` and ends the process after the store flush) |
| `playback.tunneling` | Toggle "Tunneled playback" ("Can cause a black screen on some TVs") | Off | G | A | `playback.tunneling` | track selector | REPREPARE |
| `playback.reconnect` | Choice 3 / 5 / 10 / Unlimited | 5 | G | A | `playback.reconnectAttempts` | error ladder | LIVE |
| `playback.failover` | Toggle "Switch to a backup server" | On | G | A | `playback.failoverToBackup` | ladder | LIVE |
| `playback.after_standby` | Choice Resume / Open the guide, paused | Resume | G | A | `playback.resumeAfterStandby` | lifecycle | LIVE |
| `playback.hdr_preview` | Choice "HDR channels in the mini player: Poster / Play" | Poster | G | A | `playback.hdrInPreview` | controller | LIVE |
| `playback.info_bar` | Choice 3 / 5 / 8 s | 5 s | G | A | `player.infoBarMs` | player machine | LIVE |
| `playback.number_wait` | Choice 1 / 1.5 / 2.5 s | 1.5 s | G | A | `player.numberEntryWaitMs` | number entry | LIVE |
| `playback.external_player` | Choice None / installed players (from a `PackageManager` query for `ACTION_VIEW` of `video/*` over `http`, §9 F24) | None | G | A | `playback.externalPlayer` | hand-off | NEXT_TUNE |

#### 3.3.4 External player hand-off

"Open in external player" (Channel options; error-card OPEN_EXTERNAL; no default key, bindable later through a v1.1 action if asked): `controller.handOffExternal()` stops our stream first (the provider's connection limit), then `ExternalPlayerLauncher` starts `ACTION_VIEW` with the URL, type `video/*` (or `application/x-mpegURL` for HLS), `Intent.EXTRA_TITLE`, the chosen package, and the UA/Referer as extras the common players read (`headers` for VLC/MX, **verify** §9 F24). The confirm card before the first hand-off says: "The other app receives the stream address, which includes your login." When no player is installed or chosen: the row and the error step route to `playback.external_player`.

#### 3.3.5 Channels and groups (management, §3.7)

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `channels.playlist` | Choice of playlist (only with two or more) | first | – | S* | – | the rows below | – |
| `channels.editor` | Link → ChannelEditor(pid); value "Hide, sort and move channels" — the dedicated editor, first row (owner request 2026-10-18, `docs/design/m6-channel-editor.md`) | – | P | S | `channel_prefs`, `group_prefs`, `favourite` | lineups, Groups panel | LIVE |
| `channels.groups` | Link → Groups(pid); value "64 groups · 12 hidden · 2 locked" | all shown, adult locked (applies with a PIN) | P | S | `group_prefs` | lineups, Groups panel | LIVE |
| `channels.hidden` | Link → HiddenChannels(pid); value count (ADVANCED since 2026-10-18 so the editor row fits the simple budget, `docs/decisions/2026-10-18-channel-editor.md`) | – | P | A | `channel_prefs.hidden` | lineups | LIVE |
| `channels.favourites` | Link → FavouriteLists; value "3 lists" | one list "Favourites" | G | S | `favourite_list`, `favourite` | Groups panel | LIVE |
| `channels.open_guide_on` | Choice All channels / Favourites / Last group | Last group | G | S | `guide.openGuideOn` | guide startup | NEXT_LAUNCH |
| `channels.hide_rules` | Link → HideRules(pid); value "2 rules · 340 channels hidden" | empty | P | A | `hide_rule` | `rule_hidden` pass | LIVE (one pass) |

\* The playlist chooser is a context control, not a setting; it is not counted.

#### 3.3.6 Interface

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `interface.clock` | Choice System / 12 h / 24 h | System | G | S | `guide.clock` | `TimeLabels` | LIVE (the host re-sends Settings, M3 G-S3) |
| `interface.mini_player` | Choice Video / Still image | Video (Still in low-RAM mode) | G | S | `guide.miniStill` | guide | LIVE |
| `interface.performance` | Choice Auto / High quality / Low-end device | Auto | G | S | `guide.performance` | guide, ambient | LIVE |
| `interface.scroll_descriptions` | Toggle "Scroll long descriptions", description "Long program descriptions in the guide scroll slowly so you can read them all." (owner request 2026-11-09) | On | G | S | `guide.scrollDescriptions` | guide panel (off when animator scale is 0) | LIVE |
| `interface.logos` | Toggle "Channel logos in the guide" | On | G | A | `guide.showLogos` | renderer | LIVE |
| `interface.ambient` | Toggle "Ambient background" | On (Off in low-end) | G | A | `guide.ambient` | ambient | LIVE |
| `interface.numbers` | Toggle "Show channel numbers" | On | G | A | `guide.showNumbers` | renderer | LIVE |
| `interface.reminder_switch` | Toggle "Switch channel when a reminder starts" | Off | G | A | `guide.reminderAutoSwitch` | reminder card | LIVE |
| `interface.reminder_lead` | Choice 1 / 2 / 5 min before | 1 min | G | A | `guide.reminderLeadSec` | reminder planner | LIVE (new reminders) |

#### 3.3.7 Remote control

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `remote.mapping` | Link → ButtonMapping; value "3 changes" or "Defaults" | defaults | G | S | `remote` domain | `RemappedKeyMap` | LIVE |
| `remote.updown` | Choice Quick bar and info bar / Change channel / Open channel list | Quick bar and info bar (owner ruling 2026-10-15) | G | S | `player.upDown` | player machine | LIVE |
| `remote.invert` | Toggle "Invert channel direction" (disabled unless Change channel; reason "Only when Up/Down change channel") | Off | G | A | `player.invertChannelDirection` | player machine | LIVE |
| `remote.back` | Choice Back in player: Go to guide / Open channel list | Go to guide | G | A | `player.back` | player machine | LIVE |
| `remote.exit` | Choice Exit: Press Back twice / Ask / Never ask | Press Back twice | G | A | `guide.exitConfirmation` | guide machine | LIVE |

Product §4.7's "Colour keys: read-only map in v1" is superseded by the button-mapping screen (the colour keys are rows there).

#### 3.3.8 Parental controls (the page needs the PIN when one is set)

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `parental.pin` | Action: "Set a PIN" (not set) / "Change PIN" (set; verify, then create twice) | not set | G | S | secure blob `parental-pin` | `ParentalPinStore` | LIVE |
| `parental.auto_lock` | Toggle "Lock adult content automatically" (adult = `channel_group.is_adult` / `channel.is_adult`, M1 detection; this document calls those groups **locked groups**). (reviewed 2026-10-16, B3) Value with no PIN: "On · not protected: set a PIN" in amber, and OK on the value offers Set a PIN | On | G (applies per playlist) | S | `parental.autoLockAdult` | parental predicate | LIVE |
| `parental.locked_in_guide` | Choice Show with a lock / Hide | Show with a lock | G | S | `parental.lockedInGuide` | Groups panel, channel list | LIVE |
| `parental.lock_settings` | Toggle "Lock settings and playlist editing" | Off | G | S | `parental.lockSettings` | entry points (§3.2.5), Remove, Reset | LIVE |
| `parental.stay_unlocked` | Choice Until The Box closes / 1 hour / Ask every time | Until The Box closes | G | A | `parental.stayUnlocked` | `ParentalSession` | LIVE |
| `parental.lock_now` | Action (shown while unlocked) | – | G | A | – | `ParentalSession.relock(USER)` | LIVE |
| `parental.remove_pin` | Action, destructive (shown when set) | – | G | A | deletes the blob | – | LIVE |

An Info row closes the page (not counted): "Forgot the PIN? There is no way to recover it. Clear The Box's data in Android Settings → Apps → The Box, then restore a backup: backups keep your playlists and favourites but never the PIN."

#### 3.3.9 Backup and restore (§3.10)

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `backup.now` | Action → flow: To your phone / To a file / To Downloads → "Include logins?" (passphrase) → done | – | G | S | – | `BackupService.export` | – |
| `backup.restore` | Action → flow: From your phone / From a file / From Downloads (lists `.thebox` files found) → preview → passphrase → Merge / Replace → done | – | G | S | – | `BackupService.restore` | LIVE (refreshes start for new playlists) |
| `backup.weekly` | Toggle "Weekly automatic backup"; value "Last: Sun 03:00 · Download/TheBox" | On | G | A | `backup.weekly` | `AutoBackupWorker` | LIVE (schedules) |
| `backup.passphrase` | Action "Passphrase for automatic backups" (set / change / remove; with one, weekly backups include logins) | not set | G | A | secure blob `backup-passphrase` | worker | LIVE |

Product §4.10's "Include logins" toggle becomes a step of the backup flow (the user decides per backup, and the passphrase is asked at the same moment); focus defaults to **Include logins**, so the product's default (On) survives (N13). The Backup page shows a monthly nudge "Keep a copy on your phone" (→ BACKUP_DOWNLOAD) when the last phone or file backup is older than 30 days (S6).

#### 3.3.10 About and diagnostics (§3.11, §3.12)

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `about.update` | Action "Check for updates"; value "Version 1.0.3 · up to date" / "1.0.4 available" | – | G | S | `update` domain | `Updater.check(manual = true)` | LIVE |
| `about.diagnostics` | Link → Diagnostics | – | G | S | – | – | – |
| `about.export_logs` | Action → To your phone / To a file / To Downloads | – | G | S | – | `Diagnostics.exportLogs` | – |
| `about.licences` | Link → Licences (`ui/src/main/assets/licenses` exists; adds FFmpeg LGPL text with version and the build-script tag, Tink, OkHttp, qrcodegen, Media3) | – | G | S | – | – | – |
| `about.reset` | Action, destructive, PIN if set: "Reset The Box" → confirm → `ActivityManager.clearApplicationUserData()` (§9 F25) | – | G | S | – | – | RESTART |
| `about.auto_check` | Toggle "Check for updates automatically" ("Asks GitHub once a day; sends no information about you") | On | G | A | `update.checkAutomatically` | updater | LIVE |

(2026-11-11, owner request) While a release is on offer or in progress, an unregistered SIMPLE Action row `about.install` ("Install update", value = version or progress) shows directly below `about.update`: OK downloads (Available) or installs (Ready / Needs permission / Not installed); disabled while downloading, verifying or installing; absent otherwise, including Failed (no version in that state). The check row's text then points to it; OK on it still installs. Not registered, so the simple-row count stays 5.

The About page opens with the wordmark (`ui/src/main/res/drawable/wordmark.xml`, white, 56 high) and the version, build type, ABI and "Installed from: {installer package or 'unknown'}".

#### 3.3.11 Network (advanced section)

| Row | Type | Default | Scope | Vis | Storage | Read by | Effect |
|---|---|---|---|---|---|---|---|
| `network.dns` | Choice System / Cloudflare (secure DNS) / Google (secure DNS) / Quad9 (secure DNS) / Custom (URL editor, `https://` only) | System | G | A | `network.dns`, `customDohUrl` | `BoxDns` | LIVE (new lookups; pooled connections keep their address) |
| `network.ipv4` | Choice Auto / On / Off | Auto (switches on after `IPV6_FAILS_FOR_AUTO` = 2 AAAA connect failures in a session) | G | A | `network.preferIpv4` | `BoxDns` | LIVE |
| `network.timeouts` | Choice Connect 5 / 10 / 20 s; Read 10 / 20 / 40 s (two rows) | 10 / 20 | G | A | `network.{connect,read}TimeoutSec` | API and download clients through an interceptor (`Chain.withConnectTimeout/withReadTimeout`, §9 F18); **not** the live-stream clients (M2 B4.3 keeps theirs) | LIVE |

#### 3.3.12 Channel options (from the channel context menu; per channel, not counted)

Route `Channel(identity)`. Rows: Favourite lists (MultiChoice) · Hide channel · Lock channel (needs a PIN to take effect; toast "Locked. It stays hidden until you enter the PIN") · Fix guide data (Link → FixGuide(identity)) · Guide time shift (Choice −12 h … +12 h, "Default") · Aspect (Default / Fit / Fill / Zoom / 16:9 / 4:3) · Video decoder (Default / Auto / Hardware only / Software preferred) · Buffer (Default / Auto / None / Small … Extra large) · Audio language (value = remembered, Action "Forget") · Subtitles (value = remembered, Action "Forget") · Open in external player · Channel info (Info: number, group, playlist, identity kind "provider id / guide id + name / address / name" but never the key itself, stream host only, format, archive mode and days, catch-up winner, EPG match type and source, shift; when the playlist's server zone is a fixed offset: "The provider's clock does not follow daylight saving; catch-up times may be one hour off around a clock change", M4 §8 Q15). Storage `channel_prefs` (existing columns). When the channel is the tuned one, aspect and decoder go through the controller's live setters (`setAspectMode`, `setVideoDecoderOverride` with `remember = true`); buffer is REPREPARE.

#### 3.3.13 Simple row count

Playlist page 7 (name, source, refresh, update on start, numbering, name prefix, remove) + TV guide 3 + Playback 5 (from 2026-10-19: "Pause and rewind" added and "On launch" moved to A so the total holds, `docs/design/m6-timeshift.md` U-B4 / lead ruling 1) + Channels & groups 4 (from 2026-10-18: Channel editor, Groups, Favorites, Open guide on; Hidden channels moved to A, `docs/decisions/2026-10-18-channel-editor.md`) + Interface 3 + Remote 2 + Parental 4 + Backup 2 + About 5 = **35**. (amended 2026-11-09, owner request: "Scroll long descriptions" is a simple Interface row, so Interface is 4 and the total **36**; `docs/decisions/2026-11-09-guide-panel-description.md`.) Moved from product §4 S to A to meet the target: Channel order, Preferred audio language, Subtitles, Channel logos in guide, Stay unlocked, Check automatically; Account status became a card; "Include logins" became a flow step; "Forgotten PIN" became an Info line; Language and Send crash reports are deferred (D2, D3). New simple rows: Channel name prefix (owner, 2026-10-14) and Button mapping plus Up/Down in player (owner rulings).

**Product §4 coverage** (reviewed 2026-10-16; every row appears or is deferred): §4.2 all rows (Account status as the card); §4.3 all rows ("Time zone when the source has no offset" and per-source shift on the EpgSources page; "Daily at a set time" restored, N4; "Logos from" deferred D4); §4.4 all rows (AFR shown below API 30 with a note, N5); §4.5 all rows; §4.6 all rows except Language (D2); §4.7 all rows, "Colour keys: read-only map" superseded by the button-mapping screen; §4.8 all rows ("Forgotten PIN" as the Info line); §4.9 all rows; §4.10 all rows ("Include logins" as a flow step); §4.11 all rows except "Send crash reports" (D3); §4.12 all items on the Channel options page (Rename and Sources order stay v1.1 as the product says).

### 3.4 Onboarding (WP-B; routes FirstRun, AddSource, ImportProgress, GroupsReview)

#### 3.4.1 Flow

```
FirstRun: Welcome ─┬─ Xtream login ──► Result card ──► Import progress ──► Groups review (+PIN offer) ──► Matching report ──► GUIDE
                   ├─ M3U playlist ──► (same)
                   ├─ Set up from phone (S2, §3.5) ──► Received ──► Result card ──► (same)
                   └─ Restore backup ──► source (phone / file / Downloads) ──► Restore preview ──► GUIDE
Settings → Playlists → Add playlist  = AddSource(firstRun = false): the same pages; Back returns to Playlists; "Start watching" returns to the guide.
```

The first-run decision is made once at startup by `GuideViewModel` after `SettingsStore.ready`: no row in `playlist` and `SetupState.completed == false` → `GuideEvent.OpenSetup(FirstRun)` instead of the startup tune. `completed` is set when the first import reaches stage 3 (or a restore finishes), so a user who adds a playlist and later deletes every playlist sees the M3 empty state ("No playlist yet · Add one in Settings"), not the Welcome screen.

#### 3.4.2 Welcome (S1)

Layout reuses the guide composition (product S1): the wordmark on the ambient background in the mini-player slot (64, 56, 720 × 405, radius 20, hue of the brand blue `oklch(0.45 0.12 240)`), the title "Welcome to The Box" 64/600 at (832, 160) with one 21 lh 1.45 line under it ("Add the playlist or login your provider gave you. Nothing is preinstalled."), and four choice cards in a row at y 560: x 64, 496, 928, 1360, each 400 × 260, gap 32 (4 × 400 + 3 × 32 = 1696 ≤ 1792). Card: glass, radius 24, padding 28; title 30/600; body 19 `.7` (two lines); focused = FG fill, dark text, accent ring. Cards: **Set up from phone** ("Type on your phone instead of the remote"), **Xtream login** ("Server, username and password"), **M3U playlist** ("A playlist link or file, and a guide link"), **Restore backup** ("From a .thebox file"). Default focus: Set up from phone, unless a physical keyboard is attached (`Configuration.keyboard == KEYBOARD_QWERTY`), then Xtream login. A line 17 `.55` at y 860 reads "The Box ships no channels or playlists. Use it with a service you already have." (product §7.1).

| Key | Behaviour |
|---|---|
| ← / → | Previous/next card (no wrap) |
| OK | Open the card's route |
| BACK | Exit the app (first run only, no confirm) |
| ↑ / ↓, long OK | Nothing |

#### 3.4.3 Forms (S3 Xtream, S4 M3U) and input normalisation

Forms are pages whose rows are Text rows (OK opens the editor) plus a primary Action row. Fields keep their draft for the process lifetime (`SetupDraftStore`, memory only; never persisted, never logged).

- **Xtream** rows: Name (optional; placeholder = host), **Server** (chips `http://`, `https://`, `:8080`, `:80`, `:25461`, `.com`, `.net`, `.tv`, `/`), **Username**, **Password** (masked; the editor offers Show), then (A, collapsed under "More options") User-Agent, Stream format, Backup servers; primary **Connect**.
- **M3U** rows: Name, **Playlist URL** (chips as above plus `.m3u`, `.m3u8`, `get.php?`), **or a file** (Action: SAF `ACTION_OPEN_DOCUMENT` with `audio/x-mpegurl`, `application/x-mpegURL`, `text/plain`, `*/*`; when no activity resolves (Fire OS, §9 F17) the row reads "This TV has no file browser. Send the file from your phone instead" and opens PhoneSetup with M3U-file upload; an uploaded file is moved from `cacheDir/phone/` to `filesDir/playlists/{uid}.m3u` and added through `addM3uFile` with a `file://` URI, verify that M1's `ContentCopier` accepts it), **Guide (EPG) URLs** (list, "Add another"), (A) User-Agent, Extra headers, Catch-up mode, Guide time shift; primary **Add playlist**.

`SourceInputNormalizer` (pure, table-tested):

| Input in Server / URL | Result |
|---|---|
| Leading/trailing spaces, tabs, NBSP, zero-width (U+200B–U+200D, U+FEFF), a trailing `/` | removed (Server); passwords lose only leading/trailing invisible characters |
| No scheme (`panel.example.com:8080`) | `http://` added (providers are plain HTTP, CLAUDE.md) |
| `get.php?username=u&password=p&type=m3u_plus&output=ts` (or `player_api.php`, `xmltv.php`, a `/live/u/p/1.ts` stream URL) pasted into **Server** | split by `XtreamLoginUrl.parse` into Server, Username, Password (+ Stream format from `output`); notice "Filled from the link" (`NormalizedInput.Xtream.filledFromLink`) |
| The same pasted into the **M3U URL** | `NormalizedInput.M3u(looksLikeXtream = …)`: the page shows a choice card "This is an Xtream link. Use Xtream login instead? It adds catch-up, account details and faster loading" with **Use Xtream login** focused (product S4) and "Keep as M3U" |
| `…/portal.php`, `…/c/`, `stalker_portal` | `Invalid(STALKER_PORTAL)`: "This looks like a Stalker portal. The Box does not support those yet." (product §7.3) |
| Spaces inside the host, an `ftp:`/`rtsp:` scheme | `Invalid(SPACES_IN_HOST / UNSUPPORTED_SCHEME)` with the reason |

#### 3.4.4 Connect and the result card

Connect: `SourceSetup.testXtream(form)` (one `player_api.php`, `XtreamApi.login`), then on success `saveXtream` (creates the playlist through `PlaylistRepository.addXtream`, schedules the refresh with `Trigger.ONBOARDING`) and the ImportProgress route. The result card is an Info block at the top of the page with tone colours (GOOD accent, WARN amber `#F2B25C`, BAD `#FF4D5E` text, never fills) and one or two action rows. `SetupResultMapper` covers every case (`SetupMapperCompletenessTest` enumerates `SourceError` subtypes × `NetworkKind` × `AuthReason` × `NotASourceKind` × `AccountStatus`):

| Condition | Headline | Lines | Primary / secondary |
|---|---|---|---|
| Login OK, `ACTIVE` | "Connected" | "Active · expires 14 Mar 2027 (152 days)" / "Never expires"; "1 of 2 connections in use"; "Formats: MPEG-TS, HLS"; "Server time in sync" or "Server clock is 4 min ahead (catch-up corrects for it)" | Continue / – |
| `EXPIRED` | "Your subscription expired on 3 Oct 2026" | "Renew it with your provider, then refresh." | Save anyway / Edit |
| `BANNED` / `DISABLED` | "Your provider has disabled this account" | provider message if any (`XtreamAccountInfo.message`, cut to 120 chars) | Edit / Save anyway |
| `Auth(BAD_CREDENTIALS)` (incl. `auth:0` with HTTP 200) | "The server rejected the username or password" | "Check for spaces and the difference between 0 and O." | Edit / Phone setup |
| `Http(401/403)` at login | "The server refused the login (HTTP 403)" | "Some providers only allow their own apps or need a different user agent." | Edit / Retry |
| `Network(DNS)` | "Can't find the server panel.example.com" | "Your internet provider may block it." | Use secure DNS (sets Cloudflare DoH, retries) / Edit |
| `Network(CONNECT / TIMEOUT / RESET)` | "The server did not answer" (`… on port 8080`) | "Check the address and port." | Retry / Edit |
| `Network(TLS)` | "The server's secure connection failed" | "Try http:// instead of https://." | Edit / Retry |
| `NotASource(HTML_PAGE)` | "The server sent a web page instead of data" | "Often an expiry notice or a wrong address." | Edit / Retry |
| `NotASource(HLS_MANIFEST)` (M3U) | "This is a single stream, not a playlist" | – | Add as a single channel / Edit |
| `NotASource(EMPTY)` | "The playlist is empty" | – | Edit / Retry |
| `SuspiciousShrink` (edit of an existing source) | "The playlist returned 12 channels instead of 2,140" | "Your channels were kept." | Accept anyway (`forceAcceptShrink`) / Retry |
| `Credentials(UNAVAILABLE)` | "Secure storage is busy" | "Try again in a moment." | Retry / – |
| `Internal` | "Something went wrong while connecting" | code key only | Retry / Edit |

Every card names a reason and a next step (CLAUDE.md); none shows raw exception text.

#### 3.4.5 Import progress (S5) and the UA probe

ImportProgress is a full page (product S5) with five stage rows (Info rows with a mono count on the right): Downloading playlist (MB) · Reading channels "18,402 entries → 2,140 live, 16,262 films and series kept for later" · Groups "64" · Downloading guide (MB) · Matching guide "1,912 of 2,140 matched (89 %)". `SourceSetup.progress(id)` folds `SyncStatusRepository` work infos and the refresh report. **Start watching** (Action) becomes enabled when stage 3 is done; the guide import continues in the background with the header sync chip (never block on a 300 MB XMLTV). In first run, Start watching goes to GroupsReview first when the playlist has more than `GROUPS_REVIEW_MIN_GROUPS` (8) groups or any adult group, else straight to the guide.

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Stage rows / Start watching |
| OK on Start watching | as above (disabled before stage 3: notice "Still reading channels…") |
| BACK | Confirm card "Stop the import? Channels already read are kept." → Stop (`cancelImport`) / Keep going. After Stop (N12): first run → the form the user came from (fields kept); AddSource → the Playlists page |

(reviewed 2026-10-16, S4) PhoneSetup, ImportProgress and the restore flow set `FLAG_KEEP_SCREEN_ON` on the window while shown (today it follows playback only), so the screensaver cannot stop the activity (and with it the server or the import UI) while the user types on the phone or waits.

(reviewed 2026-10-16, B3) **PIN offers outside first run:** when an import started from Settings (AddSource, phone setup into a second playlist) finds adult groups and no PIN exists, the ImportProgress page ends with the same offer card as §3.4.6 ("Set a 4-digit PIN to protect {n} adult groups" [Set PIN] [Not now]); after a restore that carried group or channel locks and no PIN exists, the restore summary carries it. Each offer appears at most once per playlist (`SetupState.pinOfferedFor: Set<String>` of playlist uids).

**UA probe** (product S3; M2 D22 makes persistence the user's choice; here the onboarding *is* that choice): after stage 3, `SourceSetupImpl` picks the first live, non-radio channel and calls `StreamProbe.probe` with the playlist's UA (default "The Box", `HttpModule.DEFAULT_USER_AGENT`). On `FORBIDDEN` it probes once more with the VLC preset (`PlaybackSettings.userAgentFallbackPreset`); if that gives `MEDIA`/`HLS` it stores `playlist.user_agent = VLC preset` and the account card shows "Your provider only answers apps that identify as VLC, so The Box does too (Settings → Playlists → User agent)". `LIMIT` (458/509 or the broker had no slot): no change, the card notes "Another device is using your connection; the stream test was skipped". The probe is one `PROBE` lease: it never pre-empts PLAYBACK (nothing plays during onboarding; in AddSource from Settings the tuned stream may hold the slot, then the probe reports LIMIT and is skipped), never sends `Range`, reads at most `PROBE_BYTES` (256 KB) or `PROBE_MS` (3,000), and **always** closes its call before returning (`StreamProbeImplTest`: the fake call is cancelled on every path, including coroutine cancellation).

#### 3.4.6 Groups review (S6) and the PIN offer

A page of group rows with two columns of state: **Shown / Hidden** and **Lock**. A header row of country quick-picks (chips from `tvg-country` and leading tags like `UK |`, `US:` with counts; OK on a chip = "show only these countries", toggles). Adult groups (`channel_group.is_adult`) start **locked** (badge `18+`, lock glyph). Below the list the offer: "Set a 4-digit PIN to protect the locked groups" → **Set PIN** / **Not now**, and the line "Hiding groups makes the guide faster." (every hidden group shrinks the EPG whitelist). With **Not now**, locked groups are **filtered, not protected** (FILTERED_NO_PIN, decision §0.8, B3): out of every mixed list, openable from the Groups panel after the "not protected" card; the Parental page shows "2 locked groups are not protected: set a PIN".

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Rows (chips row, groups, PIN offer, Done) |
| ← / → | On a group: Shown column ↔ Lock column. On chips: previous/next chip |
| OK | Shown column: toggle hidden. Lock column: toggle lock. Chip: apply. Set PIN: PIN create flow. Done: `applyGroupsReview` (one transaction) → matching report |
| BACK | Same as Done (the review is skippable; nothing is lost) |

#### 3.4.7 Matching report

One page after the guide finished matching (or immediately, with "Matching continues in the background" if it has not): "1,912 of 2,140 channels have guide data (89 %)", the top 10 unmatched channels by name, **Fix now** (→ FixGuide()) and **Later** (→ guide). Never blocks: if the EPG is still downloading after Start watching, the report is skipped and the sync chip carries the result.

### 3.5 Phone-assisted setup (WP-D; route PhoneSetup)

#### 3.5.1 TV screen (S2)

Left: the QR (white card 440 × 440 at (160, 260), 20 padding, modules black on white for scanners; error correction M; encodes `qrUrl`). Right column at x 680: "Scan with your phone's camera" 30/600; "or open" + the typed URL in mono 34 (`http://192.168.1.20:47100`); "and enter the code" + the code mono 64, letter-spacing .2em (`482 913`); status line 21 (`Waiting for your phone…` / `Phone connected: 192.168.1.34` / `Received. Checking…`). Footer 17 `.55`: "Your phone and TV must be on the same Wi-Fi. The page is not encrypted: use it on your home network only."

After `NO_REQUEST_HINT_MS` (90,000) without any request, an amber hint replaces the footer: "Nothing has reached the TV yet. Check that the phone is on the same Wi-Fi (not mobile data or a guest network). Some routers block devices from seeing each other ('client isolation' or 'AP isolation'); if so, type the details on the TV instead." (reviewed 2026-10-16, S4) When `NetworkCapabilities.TRANSPORT_VPN` is active on any network, the hint (and, at once, a line under the status) adds "A VPN app is on: allow local network access in it, or pause it while you set up." with an action row **Type on the TV instead** (→ the form). No other fallback exists: there is no relay server by design (decision §0.3).

| Key | Behaviour |
|---|---|
| OK | "I can't scan": toggles a large view of the URL and code (product S2) |
| ↓ / ↑ | Between the main block and the "Type on the TV instead" row (when shown) |
| BACK | Stop the server, return to the previous page |

#### 3.5.2 Server rules (`LanHttpServerImpl`, `PairingSession`)

| Rule | Value |
|---|---|
| Bind | The LAN IPv4 from `LanAddressFinder`: the active network's `LinkProperties` if it is Wi-Fi or Ethernet, else the first Wi-Fi/Ethernet network with a site-local IPv4 (10/8, 172.16/12, 192.168/16, or 100.64/10 CGNAT is **refused**); never `0.0.0.0`, loopback, a VPN (`TRANSPORT_VPN`) or IPv6. None found → `LanFailure.NO_LAN_ADDRESS`: "This TV is not on a home network (Wi-Fi or Ethernet)." |
| Port | `PHONE_PORT_PREFERRED` (47100), else up to 10 random ports in 49152–65535 |
| Pairing | QR path `/s/{token}` (128-bit `SecureRandom`, base64url) **or** `POST /pair` with the 6-digit code. Either sets `Set-Cookie: tb={session}; HttpOnly; SameSite=Lax; Path=/` (a second 128-bit value; Lax since 2026-11-01, was Strict: a QR link opened from inside a web page, such as a Lens result or an in-app browser, is a cross-site navigation and Chromium withholds a Strict cookie on the 303 hop to `/app`; POSTs stay protected by `X-TheBox` and the `Origin` check) and redirects to `/app`. The token and code are single-use against other phones: after the first successful pairing neither works from any other IP. (2026-11-01) From the paired IP the right token or code pairs again: a new cookie, the old one refused from then on (§3.5.5). A HEAD or a prefetch of `/s/{token}` (`Sec-Purpose` with `prefetch`/`prerender`, `Purpose: prefetch`, `X-Purpose: preview`, `X-Moz: prefetch`) never pairs: 204, no cookie |
| One phone | The session is bound to the pairing client's IP; requests from any other IP get 403 "Another phone is already connected" (on `/s/{token}` and `POST /pair` too, a wrong code from another IP is not counted). The TV shows the paired IP. (2026-11-01, owner finding §3.5.5) The **same** IP presenting the right token or code again re-pairs (new cookie, old cookie → 401 / redirect to `/`); a wrong code from the paired IP still counts. Reason: the phone's QR scanner fetched the link before the browser, so the browser had no cookie and the code then said "another phone" |
| Wrong codes | `PAIR_MAX_WRONG` (5) per server lifetime → the code rotates, the TV shows "Too many wrong codes; here is a new one", counter resets once; a second exhaustion stops the server (`TOO_MANY_WRONG_CODES`) |
| Lifetime | idle `PAIR_IDLE_MS` (600,000), absolute `PAIR_MAX_MS` (1,800,000); stops on success of a SETUP submission, on leaving the screen, on app stop (ON_STOP) |
| DNS rebinding | `Host` must equal `{ip}:{port}` exactly, else 421 |
| Cross-site posts | every POST must carry `X-TheBox: 1` (set by our `fetch`; a cross-site form cannot set it without a CORS preflight, and the server answers no `OPTIONS`/CORS); `Origin`, when present, must equal `http://{ip}:{port}` |
| Parser limits | request line + headers ≤ `HTTP_MAX_HEADER_BYTES` (8 KB), ≤ 40 headers; `Content-Length` required for bodies (no chunked: 411); JSON bodies ≤ 64 KB; uploads (backup, M3U file) ≤ `PHONE_UPLOAD_MAX_BYTES` (256 MB, S4: the plan's 250k-entry M3U benchmark files exceed 64 MB) streamed to `cacheDir/phone/` in 64 KB chunks, **never buffered in memory**, with a free-space check (2 × Content-Length) before reading the body; the page states the limit; socket read timeout 15 s; ≤ 4 concurrent sockets; HTTP/1.1 with `Connection: close` on every response (no keep-alive state) |
| Responses | `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'` |
| Logging | method, path template (`/api/xtream`, never the token path), status, bytes; the token, code, cookie and every body are registered with `Redact.registerTransient` and never logged |

#### 3.5.3 Endpoints (`PhoneApi`)

| Method, path | Needs | Body / result |
|---|---|---|
| `GET /` | – | `pair.html` (code entry) |
| `GET /s/{token}` | valid token | 303 → `/app` + cookie (from the paired IP without a valid cookie: re-pairs with a new cookie, 2026-11-01); HEAD or a prefetch → 204, no cookie, no pairing |
| `POST /pair` | `X-TheBox` | `{"code":"482913"}` → 204 + cookie, or 403 `{"left":3}` |
| `GET /app`, `/app.js`, `/app.css` | cookie | the page (assets, no external resources; a light theme so it is readable on phones; the black-on-white wordmark per the branding decision) |
| `GET /api/state` | cookie | `{"purpose":"SETUP","tv":"Waiting"|"Checking"|"Done"|"Failed","message":"…"}` (polled every 1 s) |
| `POST /api/xtream` | cookie, `X-TheBox` | `{"name","server","username","password","output"}` → 202; the TV shows "Received from your phone: panel.example.com · user ab***" and runs Connect (§3.4.4); the result text is mirrored to `/api/state` |
| `POST /api/m3u` | cookie, `X-TheBox` | `{"name","url","epgUrls":[…]}` → 202 |
| `POST /api/link` | cookie, `X-TheBox` | `{"url","name"}`: any pasted link, normalised on the TV (get.php → Xtream) |
| `POST /api/upload?kind=backup|m3u&name=…` | cookie, `X-TheBox`, `Content-Length` | raw bytes (`application/octet-stream`, the page reads the file with `File.arrayBuffer()`/`fetch(body: file)`: no multipart) → 202 `{"needsPassphrase":true,"summary":"2 playlists, 3 favourite lists"}` for backups |
| `POST /api/passphrase` | cookie, `X-TheBox` | `{"passphrase"}` → 202 (restore or backup) |
| `GET /api/download/{name}` | cookie, offered by the TV | the backup or log bundle, once; `Content-Disposition: attachment` |

The phone page offers four tabs matching the TV's purpose: **Xtream**, **M3U**, **Paste a link**, **Restore** (SETUP); **Restore** only (RESTORE); **Download backup** (BACKUP_DOWNLOAD: passphrase fields first, then the download link the TV offers); **Download logs** (LOGS_DOWNLOAD). Inputs use `autocomplete="off"`, `autocapitalize="none"`, `spellcheck="false"`, `type="url"` / `type="password"` with a Show toggle. The footer repeats the plain-HTTP caveat.

#### 3.5.4 Security posture (what we claim and what we do not)

- Credentials typed on the phone travel **once, in clear, over the home LAN** to the TV. Anyone able to sniff that LAN (a hostile device on the same Wi-Fi, an open hotel network) could read them. The page and the TV say so; the page refuses to load over an IP outside the private ranges (the server never binds there).
- Pairing defeats drive-by use: without the QR token or the code shown on the TV screen, nothing is accepted; DNS rebinding and cross-site forms from other web pages on the phone are refused by the Host and header checks.
- Nothing is stored by the server: submissions go straight to `SourceSetup`, uploads are deleted after use (and on server stop).
- The same server serves downloads (backup, logs) only of files the TV offered in this session, only to the paired phone, once.
- (reviewed 2026-10-16, S4) For BACKUP_DOWNLOAD and RESTORE the passphrase **and** the encrypted backup cross the same plain-HTTP LAN, so a LAN sniffer gets both: the passphrase protects a backup file at rest (on the phone, in a cloud drive), not its transfer. The page says so next to the passphrase field.
- (N10) Denial of service from the LAN is accepted: any device on the network can burn the wrong-code attempts (the server rotates the code once, then stops) or hold the four sockets for 15 s each. The user sees it on the TV and can restart the screen.

#### 3.5.5 Owner finding 2026-11-01 (pairing said "another phone" for the only phone)

Report: scanning the QR and then typing the code on the phone said "Another phone is already connected"; leaving the screen on the TV and typing the new code into the page already open on the phone connected. Cause: the phone's QR scanner (link preview / prefetch) fetched `/s/{token}` before the browser did, so that fetch took the pairing and the cookie; the browser reached `/app` without a cookie, was sent to `/`, and the code was refused because `PairingSession` answered `Busy` to anyone once paired, the same phone included. Fix (lead ruling, contracts unchanged): `PairingSession` re-pairs the paired IP on the right token or code (new cookie, old one refused; `Busy` only for another IP); `PhoneApi` answers a HEAD or prefetch of `/s/{token}` with 204 and never pairs on it, and a GET of `/s/{token}` from the paired IP without a valid cookie re-pairs straight to `/app`. Tests: `PairingSessionTest`, `LanHttpServerImplTest` (`samePhoneRePairsWithTheCodeAfterTheTokenWasUsedWithoutItsCookie`, `samePhoneOpeningTheQrLinkAgainWithoutTheCookieRePairs`, `anotherPhoneIsStillRefusedOnBothPathsAfterAPairing`, `headAndPrefetchesOfTheQrLinkNeverPair`). Accepted trade-off: another device behind the same IP (NAT, a shared hotspot) with the token or the code could take over the session; that needs the secret shown on the TV, the same bar as the first pairing. (Lead ruling: accepted.) Review follow-ups, same day: the cookie is now `SameSite=Lax` (§3.5.2 "Pairing" row); because a paired phone can now enter wrong pairing codes, a NETWORK_FOLDER session that ran out of sign-ins is marked by the additive `LanServerState.signInsExhausted` (set by `LanPhoneLink`) and `PhoneScreenModel.tooManySignIns` reads that flag instead of `pairedFrom` (docs/design/m6-storage-locations.md amended); the unused `PairingSession.isOtherPhone` is gone.

### 3.6 Playlists pages and guide-data matching (WP-B)

#### 3.6.1 Playlist list and detail

As §3.3.1. Item menu (long OK on a playlist): Refresh now · Edit source · Test connection · Remove. Refresh shows progress in the row value ("Refreshing · reading channels"), then the report. A failed refresh shows the reason with a next step from `SetupResultMapper` (same texts as §3.4.4), e.g. "HTTP 403: the URL may have changed or the account expired · Edit source".

#### 3.6.2 Sources picker (M4 E23; WP-C owns the player edit, WP-B the data)

Quick bar item **Sources** (`QuickItem.SOURCES`, shown only when the tuned playlist has ≥ 2 base URLs) opens `PickerKind.SERVER`: "Server 1 · panel.example.com (active)", "Server 2 · backup.example.net". Choosing one calls `PlaybackSourceProvider.saveActiveBaseIndex(playlistId, index)` and re-tunes; the stats overlay's "server n of m" follows.

#### 3.6.3 Fix unmatched channels (S13; M1 moved the screen to M5)

FixGuide() lists `EpgMatchRepository.unmatched(unlocked)` (most watched first, M1 query) and, under a divider, `matchedWithoutData`. Each Item shows the channel name and "No guide data" / "Matched, but the guide has no programmes". OK → FixGuide(channel): the picker page with a search row (Text editor; one editor commit = one search, no search-as-you-type through the IME) over every EPG channel of the enabled sources (`searchEpgChannels(query, limit = 50)`), and **Suggested** rows from `suggest(channel, store, 10)` with a confidence label ("Suggested · 94 %"). Focusing a candidate shows "Now: {title} · 19:30–20:00" from the EPG (one `EpgReader` lookup, debounced 300 ms). OK confirms (`setOverride`), then `matchIncremental` and an EPG patch run (`EpgWorkRequester.patchEpg()`); the row leaves the list. Menu on a matched-by-hand channel: "Remove my choice" (`clearOverride`). A per-channel guide time shift row sits at the bottom of the picker.

### 3.7 Channels and groups management (WP-D; product §4.5 "management, not toggles")

#### 3.7.1 Group manager (Groups(pid))

One Item per group in the user's order (`GroupDao.groups` with custom sort), value "120 channels", badges `HIDDEN`, `LOCKED`, `18+`. OK opens the group's page: Show in guide (Toggle) · Lock (Toggle; with no PIN: "Takes effect when you set a PIN") · Rename (Text; `group_prefs.custom_name`) · Move (enters move mode in the list) · Reset (clears the group's prefs). Long OK on a group: Hide/Show · Lock/Unlock · Move. Header rows: **Show all** · **Hide all except…** (opens the country chips of §3.4.6). Writes: `GroupDao.upsertGroupHidden`, `upsertPrefs`, `setGroupOrder` (one transaction per move drop).

#### 3.7.2 Hidden channels (HiddenChannels(pid))

Paged list of `channel_prefs.hidden = 1` joined with `channel` (name, group); OK or long OK = **Unhide**; header row **Unhide all** (confirm). Also lists channels hidden by a rule, with the badge "rule: {pattern}" and OK → that rule (rules are undone at the rule, not per channel).

#### 3.7.3 Hide rules (HideRules(pid))

Items: one per rule, value "Group name contains 'XXX' · 340 channels". **Add rule** → page: Target (Choice Group name / Channel name) · Pattern (Text, `TextHint.PATTERN`) · Kind (Choice Contains (case-insensitive) / Regular expression) · live preview row "Matches 12 groups, 340 channels" (count from `HideRulesImpl.preview`, debounced 300 ms) · Save. Regex safety (fixes M3 G-L19 for the prefix rule too): length ≤ `RULE_REGEX_MAX_CHARS` (200), compiled once; matching runs over a `TimeBoxedCharSequence` that throws after `RULE_REGEX_BUDGET_MS` (50) per pass page, which disables the rule with the reason "This pattern is too slow". Effect: `channel.rule_hidden` / `channel_group.rule_hidden` recomputed by one paged pass under `ImportMutex` on save, on delete and after every refresh merge (`RoomMergeSink` calls `HideRules.apply(playlistId)` at `finish`); the lineup flows re-emit through the invalidation tracker.

#### 3.7.4 Favourites lists (FavouriteLists, FavouriteList(id))

Lists in order (move mode reorders `favourite_list.sort_index`), **New list** (Text), per list: Rename · Reorder channels (move mode over `favourite.sort_index`, product S10) · Delete (confirm; the default list cannot be deleted while it is the only one). Inside a list: Item per channel, long OK = Remove · Move.

### 3.8 Button mapping (WP-C; decision 2026-10-14 "Remote button mapping")

#### 3.8.1 Model

The effective binding of `(context, keyCode, press)` is the user override when one exists (an override with `action = null` means "unbound"), else the `DefaultKeyMap` binding. Contexts stay `{GUIDE, PLAYER}` (M4 S5/F9). A binding made on the screen applies in **every context of its action** (`ActionCatalog`: e.g. `ZAP_UP` GUIDE+PLAYER, `AUDIO` PLAYER, `DAY_STRIP` GUIDE), so the user never chooses a context. Overrides are capped at `KEY_OVERRIDES_MAX` (200).

`ActionCatalog` lists every `BoxAction` with `remappable = true` (`InputContracts.kt:8-38`, plus `OPEN_SEARCH`, `OPEN_SETTINGS`) in five groups: **Watching** (play/pause, play, pause, seek back/forward, start over, jump to live, time shift, previous/next programme), **Channels** (zap up/down, previous channel, number entry, channel list, groups panel, group next/prev, favourites, favourite toggle), **Guide** (open guide, page up/down, jump to now, day strip, remind), **Player overlays** (info bar, quick menu, audio, subtitles, aspect, video decoder, stats overlay, screen off), **App** (context menu, open search, open settings, exit). That is every action in the decision note's list plus the M3/M4 additions.

#### 3.8.2 Rules (`BindingRules`, pure, table-tested)

| Rule | Detail |
|---|---|
| Reserved: navigation | D-pad UP/DOWN/LEFT/RIGHT **short** in both contexts; D-pad **long** in GUIDE (grid hold-scroll) and UP/DOWN long in PLAYER (list repeat). D-pad LEFT/RIGHT **long in PLAYER** (default group cycle, owner accelerator) may be rebound or unbound |
| Reserved: OK | `DPAD_CENTER`, `ENTER`, `NUMPAD_ENTER`, `BUTTON_A` short (SELECT) and long (CONTEXT_MENU: five-key rule) |
| Reserved: Back | `BACK`, `ESCAPE`, `BUTTON_B`, short and long ("close top layer") |
| Reserved: digits | `0–9`, numpad `0–9` (`BoxAction.DIGIT` is not remappable) |
| Reserved: system | volume up/down/mute, `MUTE`, `HOME`, `POWER`, `TV_POWER`, `SLEEP`, `WAKEUP`, and any code the framework does not deliver (Home never reaches the app; on Fire TV Search is a system key too, fact sheet §5.2) |
| Reserved: unknown code | `KEYCODE_UNKNOWN` (0): some CEC and IR remotes send 0 for several buttons, so binding it would bind all of them (S14) |
| D-pad and OK inside the player (S14, recorded deviation) | The decision note reserved D-pad and OK "outside the player"; the design reserves them **inside the player too**, because the player's overlays read UP/DOWN/LEFT/RIGHT/SELECT from the same `PLAYER` context and remapping them would break list and bar navigation. The only D-pad remap in the player is the "Up/Down in player" setting (owner decision 2026-10-15). The mapping screen says so inline under the Watching group: "D-pad and OK always navigate. To change what Up and Down do while watching, use Remote control → Up/Down in player." |
| Later binding wins | Saving `(ctx, code, press) → A` replaces whatever action the key had in each of A's contexts; the screen shows it before saving (`BindingVerdict.Replaces`). The old action may end with no key; every function still has a five-key path (§3.15) |
| Long press costs repeat | Giving a key a LONG binding makes its SHORT act on release with no auto-repeat (`KeyRouterImpl` `hasLong`, M3 §3.18.1). When the short action repeats today (zap, page, seek), the verdict is `LosesRepeat` and the card says "Holding {key} will no longer repeat {action}" |
| Reset | per key: remove every override with that code; per action: remove overrides that bind A **and** overrides that unbound one of A's default keys; all: clear |
| Unknown actions | An override whose `action` id is unknown (backup from a newer app) is kept on disk and ignored by the map |

#### 3.8.3 Screens

- **ButtonMapping** page: **Test a key** (capture that only reports: key name, device, and what it does now in the guide and the player), **Reset all to defaults** (confirm), then the five groups, one Item per action: label, value = its keys `CH+ · ⏭ · Page Up`, long presses as `Hold ←`, "No button" when none; badge `DEFAULT` when untouched.
- **ActionBindings(action)**: one Item per key (value "Press · captured from Amazon Fire TV Remote" or "Default"); OK opens a menu: Remove · Reset this key to default. Then **Add a button (press)**, **Add a button (hold)**, **Reset {action} to default**.
- **Capture overlay** (card 720 wide centred, same fill as the confirm card): "Press the button for {action} now" 30/600 with a mono countdown from `CAPTURE_TIMEOUT_MS` (10,000). On a key: the key name 34/600 (a string table for about 40 common codes: "Channel up", "Red", "⏭ Next"…; otherwise `KeyEvent.keyCodeToString` without the `KEYCODE_` prefix, §9 F2), "from {InputDevice name}" 19 `.7` (or "from an unknown device" when `getDevice()` is null or virtual, §9 F1), then the verdict line, then buttons **Save** / **Try another** / **Cancel** (focus on Save, or on Try another when Reserved).

| Capture phase | Keys |
|---|---|
| Waiting (router armed) | any key → `Captured` (BACK/ESCAPE/BUTTON_B → cancel); repeats ignored; timeout → close, notice "No button arrived. Some buttons (Home, voice, app buttons, volume) are handled by the TV and can't be used." (S14) |
| Captured, key still held | every further event of that code is swallowed until its UP, even though the router has disarmed (S14) |
| Captured (router disarmed) | ←/→ buttons, OK the button, BACK cancel. Try another re-arms |

#### 3.8.4 How the router consumes it (no second path)

`InputModule` binds `KeyMap` to `RemappedKeyMap(DefaultKeyMap(), store.overrides)`. `RemappedKeyMap` keeps four `HashMap<Int, BoxAction>` (as `DefaultKeyMap` does) in an immutable snapshot, rebuilt on `Dispatchers.Default` when the overrides flow emits and published through a `@Volatile` field; `short/long/bindings/digit` read the current snapshot with no allocation. `KeyRouterImpl` is unchanged except for the capture hook (§2.12.5): context per held key, long-press timers, `horizontalRepeat`, `handles()` and the hold watchdog all work on whatever action the map returns. **Footer hints** (M3 §3.18.3 shows "Red Remind" when `PROG_RED` was seen, `GuideStateMachineImpl.kt:1540-1550`) switch to `GuideEvent.KeyHints`: for each hinted action, the seen keys bound to it (`EffectiveKeyMap.keysFor` ∩ `KeyCapabilities.seen`), so a remapped Red stops advertising Remind. `ui/.../input/KeyRouterImpl.kt` gets one new `KeyRouterImplTest` table for capture (armed → DOWN delivered to the listener, its UP consumed, repeats ignored, BACK delivered, disarm restores routing, focus loss disarms).

### 3.9 Parental controls (WP-C)

#### 3.9.1 Rules (reviewed 2026-10-16, B3, S5, S8)

- **Four access states** (`ParentalState.access`, §2.6): **OPEN** (PIN set and session unlocked, or no PIN with auto-lock off); **LOCKED_AUTO** / **LOCKED_MANUAL_ONLY** (PIN set, session locked, auto-lock on / off); **FILTERED_NO_PIN** (no PIN, and auto-lock on or the PIN was lost to a Keystore reset).
- **Locked content**: adult groups by detection (`channel_group.is_adult`, set by M1's name rule and Xtream `is_adult`; while auto-lock is on) and groups the user locked (`group_prefs.locked = 1`); channels with `channel_prefs.locked = 1`, or `channel.is_adult` under auto-lock **unless their group has an explicit user decision** (`group_prefs.locked` not null): an explicit group unlock wins over per-channel detection, and an explicit channel lock still wins over everything (S5, §4.3).
- **In LOCKED_* and FILTERED_NO_PIN**, locked content is out of every mixed list: All channels, Favourites, Recent, Search, the zap lists, number entry, previous channel and reminders (M3 predicate plus the `TuneGuard`, §3.9.2). The difference is the door: LOCKED_* asks for the PIN; FILTERED_NO_PIN shows the card "This group is not protected. [Set a PIN] [Open anyway]" ("Open anyway" adds the source to `openedAnyway` until the app closes; its lineup is queried with OPEN).
- **Show with a lock** (default): locked groups appear in the Groups panel and the player's group column with the lock glyph (FILTERED_NO_PIN: the `18+` badge in amber) and their count. **Hide**: they are absent too (in FILTERED_NO_PIN they are still listed, because otherwise nothing could reach them without a PIN, the M3 trap).
- **Locking without a PIN** (group or channel menu, group manager): in FILTERED_NO_PIN a lock applies as a filter at once and the toast offers "Set a PIN to protect it"; with auto-lock off and no PIN, the lock action first opens PIN creation.

#### 3.9.2 Where the PIN (or the "not protected" card) is asked

**`TuneGuard`** (B1, §2.6) sits in the controller path that every tune funnels through: guide OK, channel list, number entry, CH± (the debounced zap neighbour lookup), previous channel, reminders, search, catch-up start and `resumeAfterLifecycle`. `PlaybackControllerImpl` (WP-A) calls `guard.check(identity)` before `startCycle`; a refused target never starts:
- **tune / catch-up / reminder / search / number entry:** the guide or player machine receives the refusal and opens the PIN prompt (`Tune(channel, owner)`) or, in FILTERED_NO_PIN, the "not protected" card;
- **previous channel:** returns `PreviousResult.Locked(identity)` → the same prompt;
- **zap:** skips to the next allowed neighbour (the rule lineups already apply);
- **resume after Home, standby or HDMI-off:** does not start; the controller emits `Stopped(reason = LOCKED)` and the guide machine opens the PIN prompt with `Tune` for that channel; BACK leaves the guide on All channels with nothing playing.

On every relock the zap list is cleared (`setZapList(null)`) and rebuilt from the current, filtered lineup at the next tune.

| Trigger | Purpose | After success |
|---|---|---|
| OK on a locked entry in the Groups panel (guide) | `OpenSource(entry, GUIDE)` | session unlocked; sources re-query; the entry is selected (grid reloads at now) |
| OK on a locked group in the player's channel list | `OpenSource(entry, PLAYER)` | the list shows the group |
| A tune refused by `TuneGuard` (any path above) | `Tune(channel, owner)` | tune |
| Entering Settings with "Lock settings" on (every entry of §3.2.5 except FirstRun and the detached routes) | `EnterSettings(route)` | Settings opens on the route |
| Parental page (whenever a PIN is set); Remove playlist, Edit source, Restore backup, Reset The Box (with "Lock settings" on, or always for Restore and Reset when a PIN is set); Lock/Unlock in the channel or group menu | `SettingsAction(token)` | the action runs |

The startup tune never plays a locked channel (M3 N13, unchanged, and now also through the guard).

#### 3.9.3 PIN pad (`PinPadModel`, drawn by `PinPadView`)

| Key | VERIFY | CREATE_FIRST / CREATE_CONFIRM |
|---|---|---|
| Digit keys | append; the 4th digit submits | append; the 4th digit moves to CONFIRM / compares |
| D-pad | move on the 3 × 4 grid (no wrap) | same |
| OK | press the focused key (⌫ deletes, OK submits when 4 digits) | same |
| BACK | delete the last digit; with none, cancel (`granted = false`, the owner stays where it was) | same |
| others | ignored while the pad is open (modal) | same |

Submit → `checking` → `GuideEffects.verifyPin(token, digits)` (IO; the array is zeroed after the check) → `GuideEvent.PinChecked`. Ok → `ParentalSession.unlock`, purpose granted. Wrong → "Wrong PIN" with "{n} tries before a pause" when n ≤ 2. LockedOut → digits disabled, "Too many wrong PINs. Try again in 0:30" (mono countdown; BACK still cancels). Unavailable → "The PIN can't be checked right now. Try again." (fail closed). Create: "Choose a 4-digit PIN" → "Enter it again" → mismatch "The PINs don't match. Try again." (back to CREATE_FIRST); 0000, 1111 … 9999, 1234, 4321 get a soft confirm "Easy to guess. Use it anyway?". The digits live only in the model's `IntArray` (cleared on every exit path); `PinPromptState` carries only the count; nothing is logged.

#### 3.9.4 Lockout (`LockoutPolicy`)

| Consecutive wrong PINs | Wait |
|---|---|
| 1–2 | none |
| 3 | 30 s (product S14) |
| 6 | 2 min |
| 9 | 10 min |
| 12 and every 3 after | 30 min |

The counter and `lockedUntil` persist in SharedPreferences `parental_attempts` (not backed up, not restored), so a restart does not reset them. Time: elapsed-realtime when the boot is the same (`Settings.Global.BOOT_COUNT`, API 24+, §9 F26), else wall clock capped at the step's length, so moving the clock back cannot extend or skip the wait by more than one step. A correct PIN resets the counter.

#### 3.9.5 Session

`unlock(now)` opens the session; it closes (`relock`) by `stayUnlocked`: **Until The Box closes** on ON_STOP (Home, standby, HDMI off after the M2 grace); **1 hour** at now + 60 min or ON_STOP; **Ask every time** when the user leaves the unlocked content (source change to a non-locked source, a tune of a channel outside locked groups, leaving Settings) or ON_STOP. (reviewed 2026-10-16, B1) **Only a timer expiry while the app is in the foreground** waits for the user to tune away from a locked channel that is playing (a programme is never cut off mid-watch); a relock caused by the app stopping is never "mid-programme": on return, `resumeAfterLifecycle` goes through the guard and asks. After any relock: the zap list is cleared, the library flows restart (`queryKey`), `openedAnyway` is emptied on ON_STOP, and if the guide's source is a locked group the guide moves to All channels of that playlist at now.

#### 3.9.6 Storage and failure modes

Verifier blob: version (1 byte) ‖ salt (16) ‖ iterations (4) ‖ PBKDF2-HMAC-SHA256(PIN, salt, `PIN_KDF_ITERATIONS`, set by the commit-0 benchmark, S15) (32), stored with `SecureBlobStore.putBlob("parental-pin", …)` (AEAD under the Keystore-wrapped Tink keyset, AD = ref). Verification compares in constant time. (commit 0, S15) **`PIN_KDF_ITERATIONS = 10,000`**: on `TheBox_TV_API30` (x86, 4 cores, API 30; `app_process`, ART JIT, 3 warm-ups then 5 runs, two runs with the host under build load) the design's RFC 8018 loop over `Mac("HmacSHA256")` measured 10,000 → max 80–82 ms (median 68–76) and 20,000 → max 141–175 ms (over the 150 ms budget). Each `Mac` call crosses Conscrypt's JNI (≈ 7 µs per iteration), so the loop is 2.3× slower than BouncyCastle's factory and 6–7× slower than a pure-Java HMAC-SHA256 with precomputed pads (50,000 → max 73 ms on API 30, 106 ms on API 25); switching to that is a lead decision (≈ 60 lines of crypto, RFC vectors as tests). Re-measured on the Fire TV Stick 4K in M6 (`tools/kdfbench/KdfBench.java`). Threat model, stated in the page's help text: the PIN keeps children with a remote out; it is not protection against someone who can open Android settings (Clear data) or use ADB.

- **Keystore unavailable** (transient, `CredentialFailure.UNAVAILABLE`): `PinCheck.Unavailable`; locked content stays locked.
- **Keystore reset** (permanent, M1 S16; reviewed 2026-10-16, S8/Q3): `TinkCredentialStore.wipe()` deletes every credential row and flags every playlist `needs_reauth`; the PIN blob goes with it. **Settings fail open** (no PIN is asked, so the user can re-enter logins even with "Lock settings" on), but **content stays filtered**: `pinLostToKeyReset` keeps access at FILTERED_NO_PIN until a new PIN is set, whatever the auto-lock toggle says. This matters because file playlists (`addM3uFile`, no secret) keep playing after a reset. On the next start a notice card says "This TV's secure storage was reset. Your logins and the parental PIN were removed. Enter your logins in Settings and set a new PIN." Test: `thebox-debug://keystore-reset` (§6.5), I-M5-28.
- **Forgotten PIN**: no backdoor (product §4.8). Clear data, then restore a backup (§3.10: backups never contain the PIN).

### 3.10 Backup and restore (WP-D)

#### 3.10.1 Format

`.thebox` files, UTF-8 JSON Lines (LF): line 1 `BackupHeader`, then `BackupRecord`s in the order settings → playlist → epg_source → secrets → group_pref → channel_pref → fav_list → fav → reminder → epg_mapping → hide_rule, then `End(records)`. A missing or wrong `End` = TRUNCATED. File name `TheBox-backup-2026-10-16-1402.thebox`. *Why JSON Lines, not one JSON object:* the writer and reader stream record by record in constant memory (a 20,000-channel library's `channel_pref` section is several MB), a truncated file is detected exactly, and an older reader skips record types it does not know. The project rule "streaming parsers only" is kept for this format too.

#### 3.10.2 What is in, what is not

| In | Not in |
|---|---|
| Settings domains with `backedUp = true`: guide, player, playback, data, network, parental **policy**, remote (key bindings) | The PIN; the backup passphrase; device-bound domains (backup, update, setup); `parental_attempts`; `keys_seen` |
| Playlists: uid, name, type, display source, order, enabled, and the **user-settable** columns: `user_agent`, non-secret `extra_headers_json`, `use_playlist_epg`, `update_on_start`, `refresh_interval_h`, `numbering_mode`, `order_mode`, `xtream_output`, `content_types`, `extgrp_sticky`, `charset_override`, `catchup_mode_setting`, `catchup_days_override`, `token_zone_setting`, `padding_before_sec`, `padding_after_sec`, `max_connections_override`, `ignore_tls_errors`, `name_prefix_mode`, `name_prefix_custom` | Learned playlist columns: `catchup_correction_h` (from the playlist, no row edits it; N3), `etag`, `last_modified`, `refresh_seq`, `xtream_output_learned`, `catchup_probe_winner`, `server_zone`, `server_offset_sec`, `clock_skew_sec`, `active_base_index`, `header_json`, counts, statuses; `xtream_account` cards; `refresh_log` |
| Logins (only with a passphrase): every playlist's `PlaylistSecret` (URLs incl. backup servers, username, password, secret headers) and user EPG sources' secrets, in one `Secrets` record | The channel and group tables themselves (a refresh rebuilds them; prefs are keyed by `stable_key` and wait for their channels) |
| EPG sources: USER sources; for playlist-derived sources (PLAYLIST_HEADER, XTREAM) only the user overrides (enabled, priority, shift, zone) keyed by (playlist uid, origin, ordinal). On a playlist that never refreshed (`refresh_seq = 0`) the restore creates placeholder guide rows carrying these overrides; its first refresh fills them in (`docs/design/m5-restore-placeholder-guides.md`) | `epg.db`, XMLTV caches, logo caches |
| `group_prefs`, `channel_prefs` **user** columns (hidden, locked, custom name/number/logo/sort, guide shift, aspect, audio/subtitle language, decoder, buffer, last watched, watch count) | `channel_prefs.playback_flags`, `fps_milli` (learned, M2 §2.10) |
| Favourite lists and entries; manual EPG mappings (`epg_mapping`); hide rules | `epg_match` (recomputed) |
| Pending reminders starting after the export time | Fired, dismissed, orphaned or past reminders |

#### 3.10.3 Crypto (`BackupCrypto`)

Passphrase ≥ `BACKUP_PASSPHRASE_MIN` (8) characters, typed twice on export. Key = PBKDF2-HMAC-SHA256(passphrase UTF-8, 16-byte random salt, `BACKUP_KDF_ITERATIONS`, set by the commit-0 benchmark so one derivation takes ≤ 1 s on the emulator, S15: **50,000**, measured max 453–787 ms (median 404–477) for the `Mac` loop, 100,000 → max 750–1,085 ms; the pure-Java variant would allow 400,000 at max 536–582 ms, lead decision as in §3.9.6; stored in the file, 32 bytes), implemented as an RFC 8018 loop over `javax.crypto.Mac("HmacSHA256")` because the `PBKDF2WithHmacSHA256` key factory is not on API 25 (F10 verified in commit 0: `NoSuchAlgorithmException` on an API 25 emulator, present on API 30 as BC 1.61; the loop passes both RFC 7914 vectors on API 25 and 30); tested against the RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors. Cipher AES-256-GCM (`Cipher("AES/GCM/NoPadding")`, 12-byte random nonce, 128-bit tag), associated data = the exact bytes of the header line, so secrets cannot be moved into another backup. The header stores the KDF name and iteration count, so both can change without a schema bump. A wrong passphrase fails the tag check → WRONG_PASSPHRASE before anything is written. Weekly backups include logins only when a passphrase is stored (`SecureBlobStore` `backup-passphrase`); after a Keystore reset that blob is gone and weekly backups continue without logins, with the row saying so.

#### 3.10.4 Restore

1. **Preview** (one streaming pass up to the `secrets` record): header (refuse NOT_A_BACKUP, NEWER_SCHEMA with "This backup is from a newer version of The Box. Update first."), counts, and playlist matching: **same uid** → update in place; else **same source** (type + normalised host:port and username for Xtream, URL without query for M3U, compared through the backup's secrets when included, else through `display_source`) → update in place; else **new**. The preview page lists each playlist with its target ("Provider A · will update your existing playlist" / "will be added"), the counts ("3 favourite lists, 112 favourites, 4 reminders, 340 channel settings, button mapping, 41 settings") and the mode choice **Merge** (default: "Adds and updates; keeps everything else") / **Replace** ("Makes this TV's settings and lists match the backup").
2. **Passphrase** (when `includesLogins`): number of tries unlimited, but each wrong try costs the KDF time.
3. **Validate** (second streaming pass, no writes): every line parses, `End.records` matches, sizes within `BACKUP_MAX_BYTES` (64 MB) and `BACKUP_MAX_LINE_BYTES` (1 MB).
4. **Apply** under `ImportMutex` in **one** `library.db` transaction: playlists (insert new with the backup's uid; update user columns of matched ones; the backup wins), secrets (`put` for each included one; a new playlist without logins gets `needs_reauth = 1` and appears as "Enter the login for Provider A"), then every keyed record with `playlistUid` mapped to the local id (unknown uid → skipped and listed as a problem). **Merge**: upsert the backup's rows (channel prefs field by field, only the user columns present), favourite lists matched by name (missing ones created), favourites upserted with their order, reminders insert-or-ignore (unique index), mappings upserted, hide rules added unless identical, settings domains replaced by the backup's documents (`importInTransaction(replace = false)` keeps domains the backup lacks). **Replace**: first delete all user rows of the tables above and the backed-up settings domains, then insert; playlists absent from the backup are kept (listed in the summary with "Remove" actions). Secrets go through the Keystore before the transaction commits; if the transaction fails, the secrets written for new refs are deleted.
5. **After commit**: refresh (Trigger `PLAYLIST_CHANGED`) for new playlists and those with changed secrets, the display-name pass for changed prefix rules, the `rule_hidden` pass, and the in-memory settings flows (the store publishes the restored documents after commit). Summary: "Restored 2 playlists, 3 lists, 112 favourites, 4 reminders. Provider B needs its login." `SetupState.completed = true`.

Round-trip property (`BackupRoundTripTest`, I-M5-16): export → wipe → restore(MERGE) gives identical user tables (excluding ids, learned columns and `updated_at`) and identical settings documents; restoring the same file twice changes nothing the second time (idempotent through uids).

#### 3.10.5 Targets

| Target | Export | Import | Notes |
|---|---|---|---|
| Phone page | `PhonePurpose.BACKUP_DOWNLOAD`: the passphrase (optional) is typed on the phone, the TV builds the file and offers one download | `RESTORE` or the Welcome card: upload, passphrase on the phone | Always available; the reliable path for Fire TV and for reinstall |
| File (SAF) | `ACTION_CREATE_DOCUMENT` | `ACTION_OPEN_DOCUMENT` | (reviewed 2026-10-16, F17 contradicted) Shown only when the intent resolves **to a known DocumentsUI package** (`com.android.documentsui`, `com.google.android.documentsui`, or a file manager the user picked once), because on TVs without DocumentsUI the intent can resolve to a stub activity; otherwise hidden and the phone page is offered first |
| Downloads | API 29+: `MediaStore.Downloads` insert into `Download/TheBox/` (no permission). API 25–28: needs `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion = 28`), asked when the user picks this target | Lists `.thebox` files the app can read there (its own files; after a reinstall, earlier files are listed only where the platform allows it, F16, checked in commit 0) | Used by the weekly backup only when the permission (API ≤ 28) or MediaStore (29+) allows it |

(reviewed 2026-10-16, S6) **What the weekly backup honestly promises.** On API 30+ TVs without a document picker (Fire OS 8, most Google TV boxes), a reinstalled app may not see the files an earlier install wrote to `Download/` (F16), and there is no picker to open them; so the weekly backup there **survives updates and data problems, not a reinstall**. Where it cannot write to Downloads (API ≤ 28 without the permission) it writes to app storage (`filesDir/backups`), which survives updates only. The row value says which applies on this TV: "Kept on this TV · survives a reinstall" / "Kept inside the app · survives updates, not a reinstall". **The durable path is the phone-page download**: the monthly nudge on the Backup page ("Keep a copy on your phone") opens BACKUP_DOWNLOAD. "Not listed? Send it from your phone" applies to files the user has on the phone, not to a backup stored on the TV.

`AutoBackupWorker`: periodic 7 days (WorkManager, `KEEP` policy, requires storage-not-low), waits for `ImportMutex`, writes `TheBox-auto-YYYY-MM-DD.thebox`, keeps the newest `AUTO_BACKUP_KEEP` (4) files it wrote and deletes older ones it wrote (never other files).

### 3.11 About, diagnostics and logs (WP-E)

#### 3.11.1 Diagnostics page

Info blocks and links: **This TV** (`DeviceFacts`: model, OS and Fire OS version, SDK, RAM, low-RAM flag and the resolved performance mode, ABIs, free storage, network type); **Sound** (HDMI encodings reported by `AudioCapabilities`, the output mode, passthrough denials this session, last silence fallback); **Picture** (HDR types, display modes, AFR state); **Video decoders** (H.264 / HEVC / MPEG-2 / AV1: name, hardware or software, maximum size); **FFmpeg audio** (available, version); **Security** ("Logins are protected by the Android Keystore", or the honest fallback when `usingKeystore` is false); **Recent channel changes** (Link: the last 50 zaps with phase timings from `PlaybackDiagnostics.zaps()`, M2 §3.13); **Connection test** (one Link per playlist → NetworkTest); **Sync status** (Link); **Export logs** (Action); in debug builds **Debug links enabled** (`thebox-debug://`).

#### 3.11.2 Connection test (`NetworkTestImpl`, `NetworkVerdict`)

Steps stream into the page as they finish (each a row with ✓/✕, ms and detail):

| Step | What | Detail shown |
|---|---|---|
| DNS (system) | `Dns.SYSTEM.lookup(host)` | "2 addresses (IPv4, IPv6) · 18 ms" or the failure |
| DNS (secure) | the same through Cloudflare DoH (skipped when DNS is already secure) | whether the answers agree; a system answer of `0.0.0.0`, `127.x` or a private address for a public name counts as blocked |
| Connect | TCP to host:port per address family, 5 s timeout | "IPv4 42 ms · IPv6 failed" |
| Server | Xtream: `player_api.php` (status, `auth`); M3U: GET of the playlist URL, first 64 KB then cancel | "HTTP 200 · account active" |
| Response time | median of 3 `player_api.php` calls (Xtream only) | "310 ms" |
| Stream speed | only when a channel of this playlist is playing: 10 s of `StreamStats` (delivered bitrate, buffer level) | "The provider delivers 6.1 Mbit/s; this channel needs 8.0 Mbit/s" |

Verdict (first rule that matches): system DNS fails or is blocked and secure DNS works → **"Your internet provider seems to block this server's name. Turn on secure DNS."** (one-tap) · both DNS fail → "The server's name does not exist. Check the address." · no address connects → "The server does not answer on port 8080. It may be down, or the address changed." · TLS fails → "The server's secure connection is broken. Try http://, or allow it in the playlist settings (advanced)." · HTTP 401/403/`auth:0` → "The server refused the login." (→ Edit login) · IPv6 fails and IPv4 works → "IPv6 is broken on this network. Turn on Prefer IPv4." (one-tap) · delivered < 1.2 × needed with buffer drops → "The provider sends this channel too slowly for smooth playback (6.1 of 8.0 Mbit/s). Buffering is likely on the provider's side." · else "Everything looks fine from this TV." We do **not** run a third-party speed test (it would contact a server the user did not choose; §8 Q9), so the verdict speaks about the provider's delivery, and adds the TV's Wi-Fi link speed when the platform reports it (§9 F27).

#### 3.11.3 `LogRing`

`TheBoxApplication` installs a composite `BoxLog.sink`: `android.util.Log` (as today) + `LogRing` (last `LOG_RING_LINES` = 4,000 entries, each cut to 512 chars; messages are already redacted by `BoxLog.emit`, stack traces by the existing `Redact.text` call) + a file writer that appends batches to `filesDir/logs/current.log` every 10 s and at ON_STOP, rotating at 1 MB, two files kept. No log leaves the device unless the user exports it. (reviewed 2026-10-16, S13) `BoxLog` passes the `Throwable` unredacted and OkHttp exceptions carry full URLs, so the composite sink formats **`Redact.text(Log.getStackTraceString(t))`** (message and stack) **once** and hands that string to all three sinks; nothing unredacted reaches `current.log`. Media3 and system log lines reach the ring only through `Media3LogBridge` → `BoxLog` (the app does not read logcat).

#### 3.11.4 Export bundle (`ExportBundleBuilder`)

One text file `TheBox-logs-2026-10-16-1402.txt`, ≤ `LOG_EXPORT_MAX_BYTES` (8 MB, oldest log lines dropped first), sections: header (version, build, installer, time, uptime) · device facts · settings snapshot (enums, booleans and numbers only; every string value printed as `<set>` or `<empty>`) · playlists (type, display host, counts, last status, `needs_reauth`) · sync status and the last 10 refresh reports · playback event log (200) and zaps (50) · log ring and rotated files · the last 5 local crash reports (D3; ACRA local-only configured in commit 0, `filesDir/acra`) · ANR watchdog notes. Every line passes `Redact.text` again, then a **redaction audit** checks the whole bundle for every secret registered in `Redact` (credentials and transient values: passphrases, pairing tokens, codes) and for `username=`/`password=` query values and `/live/u/p/` path credentials; any hit is replaced by `****` and counted in a footer line, and a debug build fails `ExportRedactionTest`. Targets: phone (`LOGS_DOWNLOAD`), file (SAF), Downloads (as backups).

### 3.12 Updater (WP-E)

#### 3.12.1 Release layout

Each GitHub release carries the per-ABI and universal APKs (plan §2), `update.json` (an `UpdateManifest`) and `update.json.sig` (raw 64-byte Ed25519 signature over the exact bytes of `update.json`). The app reads `UPDATE_MANIFEST_URL` = `https://github.com/{owner}/{repo}/releases/latest/download/update.json` (owner/repo: §8 Q8) and `….sig`. Debug builds read the URL from `thebox-debug://update?manifest=…` (fake panel). The manifest signing key pair is generated and kept offline by the owner; the APK embeds `UPDATE_PUBLIC_KEYS` (current and the next key, for rotation). (reviewed 2026-10-16, S7) The keys are **per build type**: release builds carry only the owner's keys; debug builds carry only a debug test key (in the debug source set, checked into `tools/fakepanel/update_test_key` for the fake panel to sign with) and never the release keys, and release builds never contain the debug key. `{owner}/{repo}` is a build-time constant (`BuildConfig.UPDATE_REPO`) with a placeholder until the owner names the repository (Q8).

#### 3.12.2 Check

When: 60 s after the first frame when "Check automatically" is on and the last check is > 24 h old; then at most once per 24 h per app start; and on "Check for updates" (manual). The request carries `User-Agent: TheBox/{versionName}` and nothing else identifying; redirects are followed only to `github.com`, `objects.githubusercontent.com` and `release-assets.githubusercontent.com` (allowlist; verify the current host list, §9 F13). Order of checks: (1) Ed25519 signature against one of `UPDATE_PUBLIC_KEYS` (Tink `Ed25519Verify`, pure Java, works on API 25, §9 F11) → else BAD_SIGNATURE (silent for automatic checks, shown for manual); (2) `schema == 1`, `packageName == BuildConfig.APPLICATION_ID` **of the running build** (`app.thebox`, or `app.thebox.debug` in debug builds; S7) → else BAD_MANIFEST; (2a) `sequence ≥ UpdatePrefs.highestSequence` (amended at integration 2026-10-16, lead ruling: an equal sequence is the daily re-check of the same manifest; only a lower one is a replay) → else REPLAYED (a replayed older signed manifest cannot re-offer a withdrawn release); on acceptance the sequence is stored; (3) `versionCode > installed`, not in `withdrawn`, not `skippedVersionCode` (automatic checks only), `minSdk ≤ SDK_INT`, rollout bucket (`UpdatePrefs.bucket`, drawn once 0–99) `< rolloutPercent`; (4) APK choice: the first entry whose `abi` is in `Build.SUPPORTED_ABIS` order, else `universal`.

#### 3.12.3 Prompt, download and verification

The S17 card (`UpdateCardState`) appears **only in GUIDE** (never in FULL, never over the PIN prompt or a reminder card), bottom-right where the reminder card goes (the reminder card wins when both want the slot): "Version 1.0.4 · Fixes catch-up on Flussonic servers" with **Update** / **Later** / **Skip this version** (←/→, OK; BACK = Later; the card takes keys only while focused: it is focused when it appears only if the grid is idle for 3 s, else it waits). Update → download to `filesDir/update/{versionCode}.apk.part` (free space ≥ 2 × size, else NO_SPACE), SHA-256 computed while streaming, size checked, then `ApkVerifier`: `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` (API 28+; `GET_SIGNATURES` below, §9 F12) → package name equals ours, `versionCode` equals the manifest's, the signer certificate's SHA-256 is in `signingCertSha256` **and** equals the installed app's signer. (reviewed 2026-10-16, S7) On API 25–27 the check uses `GET_SIGNATURES`; if the platform returns a null or empty signature list for the archive, the cert check is **skipped and logged**, never WRONG_CERT: the signed manifest's SHA-256 already authenticates the bytes and the platform rejects a different signer at install. Any failure deletes the file and shows the reason ("The download was damaged. Try again." / "This update was not signed by The Box. It was not installed."). Progress shows on the card and on the About row.

#### 3.12.4 Install

1. API 26+ and `!canRequestPackageInstalls()` → `NeedsPermission`: "Allow The Box to install updates" → `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:{applicationId}`; re-check on return. Fire OS 6 (API 25) uses the global "Apps from Unknown Sources" developer option: if the installer refuses, the card shows "Turn on Settings → My Fire TV → Developer options → Apps from Unknown Sources" (verify the path per Fire OS version, §9 F14).
2. Stop playback (`controller.stop`), then a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName`, size), write the APK, `commit` with a `PendingIntent` to `InstallResultReceiver` (mutable on API 31+, §9 F12). `STATUS_PENDING_USER_ACTION` → start `EXTRA_INTENT` (the system confirmation). Success replaces the app (the process ends); the next start shows "Updated to 1.0.4" once.
3. Failure statuses map to `INSTALL_BLOCKED` (blocked by policy, including developer verification) or `INSTALL_FAILED` with the platform message. On Fire OS, if session installs are unavailable or fail with an incompatibility the session API cannot explain, fall back to `ACTION_VIEW` of a `FileProvider` `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION` (§9 F14).
No silent install is attempted (`setRequireUserAction(USER_ACTION_NOT_REQUIRED)` is not used: the first self-update is never "installer of record" after a Downloader sideload, and a visible confirmation is the honest default).

(2026-11-10, lead sign-off) **Amended by `docs/design/m5-update-visible-install.md`: the order of steps 2 and 3 is reversed.** The primary hand-off is now `ACTION_VIEW` of the `FileProvider` URI, pinned with `setPackage` to a system installer that holds `INSTALL_PACKAGES` (no `EXTRA_RETURN_RESULT`), so the platform shows "Installing…" and "App installed: Done / Open"; the `PackageInstaller` session above is the fallback when no such installer is found (or the debug switch `update_route=session` is on). The card gains phases ("Downloading N %", "Checking the download…", "Opening the installer…", "Not installed. Press Update to try again."), and a hand-off watcher decides "Not installed" because the installer returns no result. Every check of §3.12.3 still runs before any hand-off. See that note for the rules and its "Lead sign-off" section for each review item.

#### 3.12.5 Rollback

Android refuses to install a lower `versionCode` over a higher one for normal installers, so there is no downgrade button. A bad release N is fixed by publishing N+1 (the fix or the previous code) and listing N in `withdrawn` (clients still below N skip it). The manual path is published on the release page: Back up → uninstall → install the older APK → restore; backups within schema 1 restore in both directions (older apps ignore unknown record types and settings fields). Losing the release signing key ends updates for every installed copy (users would have to uninstall): keeping an offline copy of the keystore is an owner task (§8 Q8).

#### 3.12.6 Store builds and developer verification

- **Store builds.** Google Play forbids apps that update themselves outside Play and restricts `REQUEST_INSTALL_PACKAGES` (verify the current policy wording, §9 F14). If a store build is ever made (D6), a `store` flavour leaves out `app/.../update`, removes the permission with `tools:node="remove"`, and the About row reads "Updates come from the store". The sideload build is unchanged.
- **Google developer verification** (plan §1: "the biggest distribution risk"). Google announced that apps installed on certified Android devices, including sideloaded ones, must come from verified developers, rolling out by region from 2026 (verify scope for Android TV / Google TV, dates and exemptions such as an advanced install flow or ADB, §9 F15). **What we can do:** register the developer identity and the package name `app.thebox` with the release certificate before public release (an owner action, §8 Q7); keep one release key and one package name for the life of the app (the updater depends on that anyway); publish the certificate digest on the release page. **What we cannot do:** bypass or pre-empt the platform rule: our updater hands the APK to the system installer, which applies the same checks as any sideload; if unverified installs are blocked, so are our updates, and the card can only say "This TV only installs apps from verified developers." Fire OS is not a Google-certified Android build and is expected to be unaffected (verify, F15); Vega OS devices cannot run APKs at all (plan §3).

### 3.13 Network (WP-A)

`BoxDns : okhttp3.Dns` is one singleton set with `.dns(boxDns)` on `HttpModule`'s base client (the existing DoH hook, `HttpModule.kt:18`). (reviewed 2026-10-16, N8) `PlaybackHttpClients` derives its live and segment clients from that injected base client (`base.newBuilder()`, `PlaybackHttpClients.kt:34-56`), so they inherit the DNS and a DNS-blocked provider's streams resolve too; `PlaybackHttpClients.kt` is not edited. Each `lookup` reads `NetworkSettings` at call time: SYSTEM → `Dns.SYSTEM`; a DoH choice → a cached `DnsOverHttps` (`okhttp-dnsoverhttps`, bootstrap IPs: Cloudflare 1.1.1.1/1.0.0.1, Google 8.8.8.8/8.8.4.4, Quad9 9.9.9.9/149.112.112.112; custom: system DNS bootstraps the DoH host; §9 F18). A DoH failure (resolver unreachable) falls back to the system resolver for that lookup with a one-time toast "Secure DNS is not reachable; using your normal DNS". Prefer IPv4: ON sorts IPv4 first; AUTO sorts IPv4 first once `IPV6_FAILS_FOR_AUTO` (2) IPv6 connect failures were seen this process (a shared `EventListener.Factory` counts `connectFailed` with an IPv6 address); OFF keeps the resolver's order. Timeouts apply through an interceptor (`Chain.withConnectTimeout/withReadTimeout`) on the API and download clients only. Wording: "secure DNS", never "unblock" or "bypass" (product §7.1). One-tap "Use secure DNS" (setup card, error card, connection test) sets CLOUDFLARE and retries.

### 3.14 Error-card routes (WP-C; M4 §3.7, §7 item 8)

`ErrorCardModel` turns the M4 text steps into buttons:

| `NextStep` | Button | Effect |
|---|---|---|
| ACCOUNT_DETAILS | OPEN_SETTINGS "Account details" | route `Playlist(pid)` |
| EDIT_LOGIN | OPEN_SETTINGS "Edit login" | route `PlaylistSource(pid)` |
| PLAYLIST_SETTINGS | OPEN_SETTINGS "Playlist settings" | route `Playlist(pid, focus)`: `playlist.stream_format` for FORMAT_REFUSED, `playlist.ignore_tls` for TLS_FAILURE |
| DIAGNOSTICS | OPEN_SETTINGS "Test the connection" | route `NetworkTest(pid)` |
| CHANGE_USER_AGENT | OPEN_SETTINGS "Change user agent" | route `Playlist(pid, playlist.user_agent)` |
| USE_SECURE_DNS | USE_SECURE_DNS "Use secure DNS" | `network.dns = CLOUDFLARE`, then `retry()`; toast "Secure DNS is on (Cloudflare). Change it in Settings → Network." |
| REFRESH_PLAYLIST | REFRESH_PLAYLIST "Refresh playlist" | `refreshNow(MANUAL)`, toast |
| OPEN_EXTERNAL | OPEN_EXTERNAL "Open in another player" | hand-off (§3.3.4) when a player is chosen or exactly one is installed; else OPEN_SETTINGS to `playback.external_player` |
| TRY_BACKUP | none (filtered in v1, M4) | – |

(reviewed 2026-10-16, B2) While "Always passthrough" is on, an AUDIO_OUTPUT_FAILED card and the passthrough fallback toast carry a one-tap **Back to Auto** (`playback.audioOutput = AUTO`; the row is REPREPARE, so the next prepare applies it).

Settings opened from a card in FULL is a detached layer (S2) and returns to FULL on BACK (the existing `settingsFromFull` rule); the controller's error state is unchanged, so the card is still there and Retry applies the fix. `ErrorCardModelTest` gains: every OPEN_SETTINGS button carries a non-null route; no code is left with a text step except where every step was filtered.

### 3.15 Five-key reachability and Back (WP-A, WP-C)

- **`SettingsReachabilityTest`** (JVM, by enumeration over `SettingsMachineImpl` fed by the real page builders on `SettingsFixtures`; advanced on and off; 0, 1 and 2 playlists; PIN set and not set; actions UP, DOWN, LEFT, RIGHT, SELECT, BACK and CONTEXT_MENU only): (a) every row of every route reachable from Root and from FirstRun is focused in some state; (b) every row's intents are emitted from some state (each Choice option, Toggle both ways, Run, the editor and pad opened, each Link pushed, each menu option); (c) from every state at most 12 BACKs leave Settings (FirstRun: exit), so there are no dead ends; (d) every item-menu option is also a row of the item's page, so the four keys without long OK suffice (long OK is an accelerator, as in M3 S11/E21).
- **Composed tests** with the guide machine: GUIDE → gear → Settings → BACK… → GUIDE; FULL → quick bar → Settings → BACK… → FULL; FULL error card → OPEN_SETTINGS → BACK → FULL with the card; Groups panel → locked entry → PIN → group; FirstRun → Welcome → BACK → exit.
- **Mapping cannot break the rule** (`MappingCannotBreakFiveKeyTest`): with overrides that unbind every non-reserved key, the M3 guide and M4 player five-key tests pass unchanged through `RemappedKeyMap` (reserved keys are never remappable, and every function already has a D-pad/OK/Back/long-OK path).
- **Back** closes the top layer everywhere (§3.2.4); the PIN pad, capture overlay and editor each consume BACK first (the editor through the router's `EditorBack`, S3).
- (reviewed 2026-10-16) Composed tests also cover: the sync chip → SyncStatus (detached) → BACK lands outside SETTINGS, and Edit asks for the PIN (S2); OPEN_GUIDE leaves Settings from any depth and EXIT opens the exit dialog (N11); `settings` attached after `OpenSetup` → Welcome keys work (S11).

---

## 4. Data model

### 4.1 Where every setting lives

| Kind | Store | Keyed by | Backed up |
|---|---|---|---|
| Global settings | `library.db` `app_setting` (new), one JSON document per domain (§2.2 `Domains`) | domain name | domains with `backedUp = true` |
| Per-playlist settings | `playlist` columns (M1–M3) + new `uid`, `hide_rules_ver` | `playlist.id` (local), `playlist.uid` (portable) | user columns (§3.10.2) |
| Per-group | `group_prefs` (M1) | `(playlist_id, kind, group_key)` | yes |
| Per-channel | `channel_prefs` (M1/M2) | `(playlist_id, stable_key)` | user columns |
| Hide rules | `hide_rule` (new) | `playlist_id` | yes |
| Rule results | `channel.rule_hidden`, `channel_group.rule_hidden` (new, derived) | row | no (recomputed) |
| Favourites, reminders, manual EPG mappings | M1 tables | `(playlist_id, stable_key)` | yes (reminders: future pending only) |
| Secrets | `credential` (Tink AEAD + Keystore): playlist and EPG secrets (M1) + `parental-pin`, `backup-passphrase` (new refs) | ref | logins only, under the passphrase |
| PIN attempts, key-reset flag | SharedPreferences `parental_attempts` (new) | – | no |
| Keys seen, guide session | SharedPreferences `keys_seen`, `guide_session` (M3) | – | no |
| Logs | `filesDir/logs/` | – | no |
| Backups, update APK | `Download/TheBox/` or `filesDir/backups/`; `filesDir/update/` | – | – |

User data is keyed by `(playlist_id, stable_key)` (CLAUDE.md); never by channel row ids or EPG ids. Across installs the backup carries `playlist.uid` instead of `playlist_id` and the restore maps uid → local id.

### 4.2 Migration v3 → v4 (`Migrations.MIGRATION_3_4`, hand-written, commit 0)

```sql
CREATE TABLE IF NOT EXISTS `app_setting` (`domain` TEXT NOT NULL, `json` TEXT NOT NULL, `schema_ver` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`domain`));
CREATE TABLE IF NOT EXISTS `hide_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlist_id` INTEGER NOT NULL, `target` INTEGER NOT NULL, `pattern` TEXT NOT NULL, `is_regex` INTEGER NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1, `disabled_reason` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX IF NOT EXISTS `index_hide_rule_playlist` ON `hide_rule` (`playlist_id`);
ALTER TABLE `channel` ADD COLUMN `rule_hidden` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `channel_group` ADD COLUMN `rule_hidden` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `playlist` ADD COLUMN `uid` TEXT;
UPDATE `playlist` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_uid` ON `playlist` (`uid`);
ALTER TABLE `playlist` ADD COLUMN `hide_rules_ver` INTEGER NOT NULL DEFAULT 0;
```

Entities: `AppSettingEntity`, `HideRuleEntity` (new), `ChannelEntity.ruleHidden`, `ChannelGroupEntity.ruleHidden` (`@ColumnInfo(name = "rule_hidden", defaultValue = "0")`), `PlaylistEntity.uid: String? = newUid()` (nullable in the schema so the ADD COLUMN is legal; the Kotlin default generates one at construction, so every insert path, including tests, the debug receiver and fixtures, has a uid; N6) with `@Index(value = ["uid"], unique = true, name = "index_playlist_uid")`, `PlaylistEntity.hideRulesVer`. Like M3's `MIGRATION_2_3` (`Migrations.kt:26-42`) it is a list of SQL strings with a `MigrationTest` (Robolectric, `MigrationTestHelper` against the exported v3 and v4 schemas) and an on-device run (I-M5-22). No table rebuild: two CREATE TABLEs, four ADD COLUMNs, one UPDATE, two CREATE INDEXes. `LibraryDatabase.VERSION = 4`.

### 4.3 Parental and rule predicate (WP-C; replaces `ChannelDao.PARENTAL`, `ChannelDao.kt:238-242`; reviewed 2026-10-16, B3, S5, S10)

```sql
  AND COALESCE(cp.hidden, 0) = 0
  AND COALESCE(gp.hidden, 0) = 0
  AND c.rule_hidden = 0
  AND COALESCE(g.rule_hidden, 0) = 0
  AND (:access = 0 OR (
        COALESCE(cp.locked, CASE WHEN :access IN (1, 3) AND gp.locked IS NULL THEN c.is_adult ELSE 0 END) = 0
    AND COALESCE(gp.locked, CASE WHEN :access IN (1, 3) THEN g.is_adult ELSE 0 END, 0) = 0))
```

`:access` = `ParentalAccess.ordinal` (OPEN 0, LOCKED_AUTO 1, LOCKED_MANUAL_ONLY 2, FILTERED_NO_PIN 3; the enum order is part of the contract, append only). FILTERED_NO_PIN filters exactly as LOCKED_AUTO; the difference is only the door (§3.9.1). (S5) `gp.locked IS NULL` in the channel clause: an explicit group decision overrides per-channel adult detection, so unlocking the "Adult" group shows its channels (M1 sets `channel.is_adult` per Xtream stream, `ChannelEntity.kt:58`), while an explicit channel lock still wins. (S10) `access` arrives as an argument of `GuideLibrary`/`GuideSearch`/`EpgMatchRepository.unmatched`; `GuideQueries.PARENTAL_RAW` binds `bindLong(1, access.ordinal)` where it binds `bindBool(1, unlocked)` today (`GuideQueries.kt:52-71`); no query reads parental state. Groups for the Groups panel in SHOW_WITH_LOCK (and always in FILTERED_NO_PIN): every group with `hidden = 0 AND rule_hidden = 0`, with `locked = (:access != 0) AND COALESCE(gp.locked, CASE WHEN :access IN (1, 3) THEN g.is_adult ELSE 0 END, 0) = 1` and its full count. `TuneGuardImpl` evaluates the same predicate for one identity. No new index is needed; `GuideQueryPlanTest` from M3 must still show the same indexes. `ParentalPredicateTest` is a truth table over the four access values × channel/group adult flags × explicit group/channel locks × hidden × `rule_hidden`, including "group `is_adult = 1`, `gp.locked = 0`, channel `is_adult = 1` → visible under LOCKED_AUTO".

### 4.4 Why `app_setting` and not DataStore (decision §0.4)

| Need | `app_setting` (chosen) | DataStore |
|---|---|---|
| Restore settings + playlists + user data atomically | one Room transaction | impossible across two stores |
| Mechanisms in the app | Room, raw sqlite, SharedPreferences (unchanged) | a fourth |
| Startup read | one `SELECT` of ≤ 10 rows on the already-open database | one file read, plus a new dependency and its own thread |
| Flows | in-memory `StateFlow` per domain (the store owns it; Room invalidation is not needed) | built in |
| Corruption | covered by Room/SQLite and the backups | per-file `CorruptionHandler` |

---

## 5. Performance and platform rules

1. **No main-thread IO.** Store reads and writes, PBKDF2 (PIN and backup), backup streaming, the probe, network tests, QR encoding (`Dispatchers.Default`, once per server start, ≤ 30 ms) and the LAN server (one accept thread `box-lan`, a pool of 4 worker threads, sockets with 15 s timeouts) never run on main. StrictMode in debug builds catches violations (as M3).
2. **Settings reads are flows; machines receive snapshots as events.** Page builders run on `Dispatchers.Default` and publish `PageModel`s with a version; rows are keyed by `RowId` in the `LazyColumn` (no recomposition of unchanged rows).
3. **Settings never rebuild the player** except as their row says (§0.6): one `reapplySettings()` when Settings closes if a REPREPARE row changed; RESTART only for the FFmpeg toggle. `ReapplyOnExitTest` asserts zero controller calls after a LIVE-only session (clock, logos, Up/Down) and exactly one after audio output + tunneling + buffer changes.
4. **The guide is not composed while SETTINGS is shown** (as today, `AppRoot.kt:297-311`); the custom grid's caches are kept (no `onTrimMemory`-like drop on entering Settings).
5. **Key lookups allocate nothing** (`RemappedKeyMap` snapshot), and the router adds one boolean check (`textInputActive`) and one null check (capture) per key.
6. **Memory:** the settings screen retains the current page plus two cached pages (< 1 MB with 32-row list pages); `LogRing` ≤ 2 MB worst case; backup and restore run in constant memory (< 2 MB) whatever the file size; the server holds ≤ 64 KB per socket and spools uploads to disk.
7. **Startup:** `SettingsStore` load ≤ `SETTINGS_LOAD_BUDGET_MS` (50) on the emulator (measured in I-M5-10; hardware in M6), bounded by the 1,000 ms timeout.
8. **No telemetry.** Outbound traffic is only: the user's providers and guide sources; the DoH resolver the user picked; GitHub for the update manifest and APK (toggle, no identifiers). No analytics SDK, no crash upload (D3). The LAN server binds the LAN address only.
9. **Redaction.** Every new log statement goes through `BoxLog` (redacted); passphrases, pairing tokens, codes and phone submissions are registered with `Redact.registerTransient`; the PIN is never in a string.
10. **Accessibility.** Focus changes in Settings announce "label, value, n of m" through the existing `announce` effect (VoiceView on Fire OS honours the platform call, `AppRoot.kt:322-324`); the PIN pad announces "digit entered, 2 of 4", never the digit.
11. **Strings.** No hard-coded user text; each WP owns one strings file; key names come from a string table.

---

## 6. Tests

### 6.1 JVM (JUnit 5) per WP

- **WP-A:** `settings_keys.csv` (one row per pane/overlay × action × relevant state: SECTIONS, PAGE by row kind, inline choice, multi choice, menu, confirm, number pad, move mode, editor hand-off, capture hand-off) driven by `SettingsKeyTableTest`; `SettingsReachabilityTest` and `SettingsBackLadderTest` (§3.15); page-builder tables for every section with fixtures (values, visibility, enabled/disabled reasons, `EffectTiming` per row); `EffectTimingCoverageTest`; `SimpleRowCountTest` (= 35); `RoomSettingsStoreTest` (Robolectric: defaults, `encodeDefaults = false`, unknown keys ignored, schema migrate hook, debounce/flush, ready timeout); `PersistedPlaybackSettingsTest` (mapping onto `PlaybackSettings`, unchanged test hooks); `ReapplyOnExitTest`; `BoxDnsTest` (choice switch at lookup time, IPv4 sort, AUTO after two IPv6 failures, DoH fallback toast once); `ComposedSettingsGuideTest` (§3.15).
- **WP-B:** `SourceInputNormalizerTest` (the §3.4.3 table plus 40 pasted-URL fixtures, invisible characters, `+` in passwords); `SetupMapperCompletenessTest` (every error/status combination → headline + next step; no raw exception text); `StreamProbeImplTest` (fake broker and call: closes on every path including cancellation, never sends `Range`, LIMIT never pre-empts PLAYBACK, verdicts from sniffed bytes); `SourceSetupImplTest` (Robolectric DB: save in place keeps `playlist_id` and prefs; UA fallback persists only after a working VLC probe); `MatchingPagesTest`.
- **WP-C:** `PinPadModelTest` (all keys × modes, digits cleared on every exit, auto-submit); `LockoutPolicyTest` (table, persisted counter, clock moved back, reboot); `ParentalAccessTest` (the contract body); `ParentalSessionTest` (three stay-unlocked modes, never relock mid-programme); `ParentalPredicateTest` (Robolectric: SQL truth table over access × is_adult × group/channel lock × hidden × rule_hidden); `PinRoutingTest` (guide machine: every trigger of §3.9.2 opens the prompt with the right purpose; cancel returns to the previous state); `RemappedKeyMapTest`, `BindingRulesTest` (reserved, later binding wins, LosesRepeat, resets, unknown ids); `KeyRouterImplTest` capture rows; `MappingCannotBreakFiveKeyTest`; `ErrorCardModelTest` additions; `FooterHintsFromKeyMapTest`; the M3 `stateOnRealNavigator` task and the M4 player tables stay green.
- **WP-D:** `HttpParserTest` (malformed request lines, header limits, missing `Content-Length` → 411, oversize → 413, slow sender → timeout, non-ASCII paths); `LanHttpServerImplTest` over `FakeLanTransport` (Host check → 421, missing `X-TheBox` → 403, token single use, code rotation after 5, second exhaustion stops, one phone per session, idle and absolute expiry with a fake clock, downloads only when offered, once); `BackupCodecTest` (round trip, truncated, unknown record type ignored, newer schema refused, line limit); `BackupCryptoTest` (RFC 7914 PBKDF2-HMAC-SHA256 vectors, GCM tamper of ciphertext and header → failure); `MergePlannerTest` (uid / source / new matching, field-wise channel prefs, favourites by name, reminders past/future, Replace semantics, idempotent second restore); `HideRulesTest` (contains/regex, preview counts, the regex time box disables a catastrophic pattern); `AutoBackupWorkerTest` (keeps 4, never deletes foreign files).
- **WP-E:** `ManifestVerifierTest` (RFC 8032 Ed25519 vectors, wrong key, flipped byte, schema, package, withdrawn, skipped, minSdk, rollout bucket, ABI pick); `UpdatePolicyTest` (cadence); `ApkVerifierTest` (Robolectric where `getPackageArchiveInfo` is available, else a seam with fixtures); `NetworkVerdictTest` (rule order); `ExportRedactionTest` (a bundle built from logs containing every secret form must contain none); `LogRingTest`.

- **Review additions (reviewed 2026-10-16, S16):** `ParentalBypassTest` (controller with a `FakeTuneGuard`: previous, zap and resume each refuse a locked identity after a relock and allow it while unlocked; the zap list is cleared on relock; B1); `PinRoutingTest` rows "Blue after relock → PIN prompt", "ON_STOP → ON_START with a locked tuned channel → PIN prompt, no `startCycle`", "FILTERED_NO_PIN → not-protected card, Open anyway" (B1, B3); `ForcedPassthroughTest` (the sink built with the capabilities override under FORCE_PASSTHROUGH, not otherwise; fallback toast and route denial; B2); `RoomSettingsStoreTest` rows "slow read beyond the timeout, then `update` → stored document plus the change" and "restore during a pending write keeps the restored value" (S1); `ReapplyOnExitTest` archive case and every leave route (S9); `TextEditorOverlayTest` with focus cleared → BACK still closes, Down from the field reaches the chips, no-IME fallback (S3); `LogRingTest` with `IOException("…/live/u/p/1.ts")` (S13); `ManifestVerifierTest` rows for sequence replay, debug key vs release key, running-build package id; `ApkVerifierTest` row "API 27, null signatures → skipped, not WRONG_CERT" (S7); `KeyRouterImplTest` rows "captured key held → repeats swallowed after disarm", "KEYCODE_UNKNOWN → reserved" (S14); `SettingsDocumentsUiDetectionTest` (stub resolver → hidden, F17); `PersistedDataSettingsTest` (Auto past days mapping) and `PersistedGuideSettingsTest` (automatic mini still/ambient) (S12).

### 6.2 Robolectric / Compose UI (`:ui`)

`SettingsScreenTest` (two panes, row kinds draw, focus style, notice, disabled reason), `TextEditorOverlayTest` (focus island: field → chips → Done/Cancel; BACK closes; `EditorClosed` dispatched; sink refocus), `NumberPadTest`, `PinPadViewTest` (no digit ever in the semantics tree), `CaptureOverlayTest`, `PhoneSetupScreenTest` (QR present, isolation hint after the timeout with a fake clock), `RootTreeRuleTest` (still one `SurfaceView`, no ancestor layer/alpha/clip, no `Popup`/`Dialog` node).

### 6.3 Instrumented suite (WP-E; API 30 TV emulator `TheBox_TV_API30`, fake panel)

Launched through `MainActivity` with the debug routes of §6.5; state read through `SettingsDebugProbe` (debug only: `SettingsUiState`, `LanServerState` including the token, parental state, store documents). Text entry in tests uses Compose `performTextInput` on the editor field (IME behaviour itself is manual, §6.8). `range_requests_seen == 0` on live TS is asserted in every `@After` (as M2–M4).

| # | Test | Pass criterion |
|---|---|---|
| I-M5-1 | First run, Xtream | fresh data: Welcome shown, focus on "Set up from phone"; → Xtream, `test`/`test`, Connect → "Connected · Active · 0 of 1 connections"; import progress reaches stage 3, Start watching → groups review → guide with the catalog's 13 channels; relaunch goes straight to the guide |
| I-M5-2 | Wrong credentials | `auth_mode auth0`: card "rejected the username or password", no `playlist` row; Edit keeps the typed fields |
| I-M5-3 | Expired account | `--status Expired`: card with the date; Save anyway creates the playlist; the account card is red |
| I-M5-4 | `get.php` paste | the panel's `get.php?username=test&password=test&type=m3u_plus&output=ts` in Server → three fields filled, notice "Filled from the link", output = TS; the same URL in the M3U form → "Use Xtream login instead" focused |
| I-M5-5 | UA probe | stream fault `ua_whitelist: ["VLC"]`: probe 403, VLC probe OK → `playlist.user_agent` = VLC preset and the note shows; admin stats: the probe connection closed (active 0 within linger + 1 s) |
| I-M5-6 | Probe with no free slot | `max_connections 1` and a held admin connection: probe reports LIMIT, onboarding continues with the note, no `PLAYBACK` lease was pre-empted |
| I-M5-7 | M3U and guide | M3U URL with `url-tvg` → EPG field prefilled; a URL serving HTML → "web page instead of data"; a URL serving an HLS manifest → "Add as a single channel" works |
| I-M5-8 | Groups review and PIN offer | fake panel adult category (§6.4): its group starts locked with `18+`; hide two groups → EPG whitelist excludes their channels (importer count); Set PIN → the Groups panel shows the adult group with the lock glyph |
| I-M5-9 | Phone setup | the test acts as the phone (OkHttp in the test process to the emulator's LAN address): `GET /s/{token}` → cookie; `POST /api/xtream` → the TV shows "Received from your phone" and imports; also: wrong code × 5 → new code; wrong `Host` → 421; no cookie → 401; missing `X-TheBox` → 403; a second client IP → 403 (simulated with a bound local alias if the emulator allows, else JVM-only); body over the limit → 413; idle expiry with the debug clock |
| I-M5-10 | Persistence | change clock to 12 h, buffer Large, Up/Down = Change channel, prefix Off → `am force-stop` → relaunch: values restored before the first tune (guide clock form; D-pad Up zaps in FULL); store load ≤ 50 ms logged |
| I-M5-11 | Effect timing | while playing: change interface rows → zero re-prepares and the same `tuneId`; change audio output + tunneling → exactly one re-prepare when Settings closes |
| I-M5-12 | Button mapping, capture | inject `PROG_YELLOW` with `Instrumentation.sendKeySync`: capture shows "Yellow" and, **as the expected result on the emulator**, "from an unknown device" (injected events carry device id −1 or a virtual device; S16, F1); bind to SUBTITLES: the card says it replaces Audio in the player; save; in FULL Yellow opens the subtitles picker; Reset all restores Audio; D-pad → "always navigate" |
| I-M5-13 | Long-press binding | bind CH+ hold → Groups panel (guide): short CH+ still pages on release, hold opens the panel; the card warned about repeat |
| I-M5-14 | PIN prompt and lockout | PIN 1234 set; OK on the locked group → pad; 3 wrong → 30 s countdown (debug clock); right PIN → the group opens; "Until The Box closes": Home and back → locked again; (B1) Home and back **while an adult channel plays** → PIN pad, no video; Blue (previous channel) after a relock → PIN pad; CH± after the 1-hour expiry never lands on a locked channel |
| I-M5-15 | Lock settings | on: gear, quick bar Settings and the OPEN_SETTINGS key ask for the PIN; first run (fresh data) does not |
| I-M5-16 | Backup round trip | seed favourites, prefs, reminders, mapping, hide rules, settings; export with logins and a passphrase to `filesDir`; debug wipe; restore Merge → user tables equal (excluding ids, learned columns, `updated_at`), credentials decrypt; wrong passphrase → reason, nothing written; without logins → playlists flagged "Enter the login"; second restore changes nothing |
| I-M5-17 | Backup via the phone | `RESTORE`: upload the file with `POST /api/upload?kind=backup`, passphrase by `POST /api/passphrase` → restored; `BACKUP_DOWNLOAD`: `GET /api/download/{name}` returns a file that previews correctly, and a second GET is 404 |
| I-M5-18 | Diagnostics and logs | connection test against the panel: DNS ✓, connect ✓, HTTP 200; with `stall`/`slow` faults while playing → the slow-provider verdict; exported bundle contains neither `test` as a password value, nor `username=`, nor `/live/test/test/` |
| I-M5-19 | Updater refusals | fake panel serves manifest + sig + APK: bad signature → ignored (automatic) / reason (manual); SHA mismatch → refused, file deleted; APK signed with another key → WRONG_CERT; valid (the current debug APK rebuilt with versionCode + 1 by the test script, signed with the debug key; the manifest names `app.thebox.debug` and is signed with the **debug** update key, S7) → the session reaches `STATUS_PENDING_USER_ACTION`, the test cancels; a replayed manifest with a lower `sequence` → REPLAYED |
| I-M5-20 | Five-key settings journey | D-pad/OK/Back only: every section, a choice row, a toggle, a link two levels deep, back to the guide; end state equals the JVM table |
| I-M5-21 | Error-card route | `auth_mode 401` in FULL → card with "Edit login" → Settings on the login rows; BACK → FULL with the card; Retry after fixing the password plays |
| I-M5-22 | Migration 3 → 4 on device | install a v3 database fixture: data kept, `uid` unique and non-null, `rule_hidden = 0` |
| I-M5-23 | Hide rules | rule "Group name contains 'Sports'" (synthetic 2,000): preview count equals the affected channels; the guide lineup excludes them; delete restores |
| I-M5-24 | Fix unmatched | synthetic no-EPG channel (`id % 20 == 9`): picker search finds a guide channel, confirm → the row leaves the list and the guide row shows programmes after the patch |
| I-M5-25 | Secure DNS plumbing | custom DoH URL = the fake panel's `/dns-query` (§6.4) answering `panel.test` → 10.0.2.2: a playlist on `http://panel.test:8080` logs in and plays only with the custom DoH on |
| I-M5-26 | Memory | 20 × (open Settings, scroll a 2,000-row hidden list, open/close the PIN pad and capture overlay, close): Java heap growth < 2 MB after GC |
| I-M5-27 | Always passthrough on a PCM-only sink (B2; the decision note asks for it) | emulator sink, FORCE_PASSTHROUGH (after Restart now) with the AC-3 fixture: L34 or `AUDIO_NOT_STARTED` → PCM, the fallback toast with "Back to Auto", the denial remembered for the route; Back to Auto restores AUTO |
| I-M5-28 | Keystore reset (S8) | `thebox-debug://keystore-reset`: notice card on start; Settings open without a PIN; adult groups stay filtered (Groups panel shows the not-protected card); a new PIN restores LOCKED_AUTO |
| I-M5-29 | No PIN, adult content filtered (B3) | fresh install, groups review "Not now": adult channels absent from All channels, Search, CH± and number entry; the Groups panel lists the adult group with `18+`; OK → "not protected" card → Open anyway shows it; the Parental row reads "On · not protected"; AddSource of a second playlist with an adult group shows the PIN offer once |
| I-M5-30 | Onboarding transport failures (S16) | fake panel `api_fault: html_splash` → "web page instead of data"; `api_fault: timeout` → "did not answer"; `api_fault: 429` → Retry card; `sleep_login=8` shows the checking state; keep-screen-on flag set during PhoneSetup and ImportProgress (S4) |

Regression: M3 I-1…I-19, M4 I-M4-*, and the M2 classes, one class per `run-instrumented.sh` call (M3 §5.11).

### 6.4 Fake panel additions (WP-E)

`/update/update.json`, `/update/update.json.sig`, `/update/{name}.apk` (configurable: valid, bad signature, wrong hash, wrong cert); `/dns-query` (RFC 8484 GET and POST, A records from config `dns_records`, e.g. `{"panel.test": "10.0.2.2"}`); an adult category (`is_adult = 1`, name "Adult 18+") in the catalog and in synthetic mode (one group); `--set sleep_login=N` (slow `player_api.php` for the busy state); (S16) `api_fault: {html_splash | timeout | 429 | redirect}` on `player_api.php`; `/admin/hold_connection?stream=ID` (holds one connection slot for I-M5-6); the update endpoints sign with the debug update key (`tools/fakepanel/update_test_key`, S7) and take a `sequence`. 458 at login is not simulated: it is a stream-open status, and only the probe meets it (I-M5-6), which is acceptable (S16). Unit tests in `test_fakepanel.py`.

### 6.5 Debug harness additions (WP-E; debug builds only)

`thebox-debug://settings?route=playback|playlist:{id}|mapping|parental|backup|diag` (open Settings on a route); `thebox-debug://wipe` (deletes library rows, secrets, settings and the parental prefs, then recreates the activity: the in-process equivalent of Clear data for tests); `thebox-debug://clock?offsetMs=` (offsets the clock the PIN lockout, the server lifetime and the isolation hint use); `thebox-debug://update?manifest=URL`; `thebox-debug://phone?port=N` (fixed port); `thebox-debug://keystore-reset` (runs `TinkCredentialStore`'s permanent-loss path: deletes the keyset and every credential row, sets the key-reset flag; S8, I-M5-28). `SettingsDebugProbe` + release no-op.

### 6.6 Build (commit 0)

Catalog: `okhttp-dnsoverhttps = { module = "com.squareup.okhttp3:okhttp-dnsoverhttps", version.ref = "okhttp" }` (5.5.0, §9 F18) is **already** a `:data` dependency (fact sheet §0; N8): no catalog change; `qrcodegen = { module = "io.nayuki:qrcodegen", version = "1.8.0" }` (MIT; verify version and Java 8 bytecode, F9). No DataStore, no HTTP server library, no crypto library (Tink is already present; PBKDF2 and AES-GCM use `javax.crypto`). `:core` gains nothing (pure JVM: server, codec, crypto, machine). Manifest (`:app`): `REQUEST_INSTALL_PACKAGES`; `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`; a `FileProvider` (`${applicationId}.files`, `res/xml/file_paths.xml` exposing `filesDir/update` only; N14: nothing needs a content URI for `cacheDir/phone`); `<queries>` with an `ACTION_VIEW` intent for `video/*` over `http`/`https` (external players, §9 F24). `allowBackup="false"` stays (our own backup replaces Android's).

### 6.7 Gates M5 can measure (emulator; hardware in M6)

| Gate | Target | Where |
|---|---|---|
| Settings load before first tune | ≤ 50 ms | I-M5-10 log (45 ms on the emulator, code-review pass) |
| Settings open → first page drawn | ≤ 150 ms | `SettingsOpenBenchmark` (Macrobenchmark, trace `settings-open`) and `GatesTest`. Emulator after U-S1: key → first real page median 10–19 ms (first open after launch 78–199 ms), `settings-open` median 18.5 ms: met; cold first open → M6 |
| Paged list scroll | no frame over 33 ms CPU during 100 × DOWN at 20 Hz on a 2,000-row list | same benchmark |
| No player rebuild on LIVE rows | 0 re-prepares | I-M5-11 |
| Five-key reachability | 100 % of rows | `SettingsReachabilityTest`, I-M5-20 |
| Redaction | 0 secrets in an export | `ExportRedactionTest`, I-M5-18 |
| Heap | < 2 MB growth over 20 journeys | I-M5-26 |

### 6.8 Not testable without hardware or real networks (owner M6 unless stated)

The full install of an update on Fire OS 6/7/8 and Google TV (user confirmation UI, unknown-sources settings paths, `ACTION_VIEW` fallback); SAF and DocumentsUI presence on Fire OS and Google TV; MediaStore Downloads visibility after a reinstall on API 30+; the Fire OS and Gboard TV IME with Compose `BasicTextField` (IME action Next/Done, fullscreen extract mode covering the editor); real remotes' key codes and `InputDevice` names (Fire TV, CEC through TVs, Bluetooth keyboards, game pads); LAN pairing on real routers, guest networks and client isolation; QR scanning with iOS and Android cameras; "Always passthrough" on a receiver that misreports caps; PBKDF2 timings on a Fire TV Stick 4K (PIN ≤ 150 ms, backup ≤ 3 s); developer-verification behaviour on Google TV.

---

## 7. Decisions proposed (with what was rejected)

| # | Decision | Rejected | Reason |
|---|---|---|---|
| P1 | One `SettingsMachine` in `:core`, composed into the guide machine for SETTINGS; onboarding is a route | Compose focus navigation; a new `ScreenMode.SETUP` | One key path (button mapping, five-key proof by enumeration); no exhaustive-`when` churn |
| P2 | System IME in an in-tree editor overlay; own numeric pad for numbers and the PIN | Own QWERTY keyboard; IME for the PIN | Product S3; maintenance; the phone page covers typing |
| P3 | Own minimal HTTP server in `:core` with token + code pairing, Host and header checks | NanoHTTPD; Ktor; a cloud relay | Small attack surface we control; no multipart; no server of ours |
| P4 | Global settings in `app_setting` (Room) as JSON documents per domain | DataStore; SharedPreferences | Atomic restore; no fourth mechanism (§4.4) |
| P5 | Startup awaits the settings load (≤ 1 s) | Defaults first, then a re-render | "On launch" and the clock decide the first frame |
| P6 | `EffectTiming` per row; one re-prepare on leaving Settings | Re-prepare on every change; rebuild on change | No player churn while browsing settings |
| P7 | `RemappedKeyMap` + a capture hook in the one router | A second key listener on the mapping screen | No second routing path (decision note) |
| P8 | Bindings apply in all of the action's contexts | A per-binding context picker | Simpler screen; the decision note asks per action and key, not per context |
| P9 | (reviewed 2026-10-16, B3) Adult content auto-filtered until a PIN exists (FILTERED_NO_PIN), auto-locked with one; Show with lock at group level in M5; channel-level rows v1.1 (D1); `TuneGuard` on every tune path (B1) | Enforce locks without a PIN (M3 trap); no enforcement without a PIN (the proposal: a regression); full per-row masking now | M3 §8 Q12; plan §2 "adult groups auto-locked"; cost and risk in the renderer (§1.1) |
| P10 | PIN verifier in the Keystore AEAD; on a Keystore reset settings fail open with a notice and content stays filtered until a new PIN (S8) | Plain prefs; fail closed forever | Never plain; a key reset already wipes every login, so nothing plays until the user re-enters them (§3.9.6) |
| P11 | One PIN prompt owned by the guide machine; `PinGate` for the others | A prompt per machine | One lockout counter, one place to test |
| P12 | Backups as JSON Lines with `playlist.uid`; Merge default | One JSON object; Replace default | Streaming, truncation detection, idempotent re-import |
| P13 | PBKDF2-HMAC-SHA256 600k + AES-256-GCM via `javax.crypto` | Tink's password-less keysets; scrypt/Argon2 libraries | Works on API 25 without a native library; parameters stored in the file |
| P14 | Updater: Ed25519-signed manifest, SHA-256 + package + version + cert checks, user-confirmed install, no downgrade | Silent installs; trusting HTTPS alone; a downgrade path | Platform rules; a compromised release host must not push an APK |
| P15 | No third-party speed test in diagnostics | A speed test against a public server | No contact with servers the user did not choose (§8 Q9) |
| P16 | "Include logins" is a step of the backup flow; Language and crash sending deferred | Product rows as written | ≤ 35 simple rows; no dead rows (§3.3.13) |

---

## 8. Open questions for the lead and owner (with recommendations)

All fifteen were answered at sign-off; the rulings are in **Lead sign-off (2026-10-16)** at the end. The text below is the proposal as reviewed.

1. **Scope and sequencing.** 40.5 agent-days, ~12.5 working days elapsed. *Recommendation:* keep everything in M5 with the five WPs. If capacity is short, split into **M5a** (WP-A, WP-B, WP-C: settings, onboarding without the phone page, parental, mapping) and **M5b** (WP-D, WP-E: phone page, backup, diagnostics, updater), both before the M6 beta: nothing leaves v1. The phone page must not slip past v1 (product: the clearest first-five-minutes win).
2. **D1 (per-channel "Locked" rows in unlocked groups) to v1.1?** *Recommendation:* yes; locked groups are visible with the lock in M5, and individually locked channels hide until the PIN, with a toast explaining it.
3. **Fail open on a Keystore reset** (§3.9.6)? *Recommendation:* yes, with the notice card; the alternative locks users out of re-entering their logins.
4. **Remote control section in the simple view** (product had it advanced) with Button mapping and Up/Down in player? *Recommendation:* yes, the owner asked for both explicitly (decision notes 2026-10-14 and 2026-10-15).
5. **Bindings per action context vs a context picker** (P8)? *Recommendation:* per action context; revisit with per-device profiles in v1.1.
6. **Backup passphrase stored for weekly backups with logins** (Keystore blob)? *Recommendation:* yes, opt-in, default not set (weekly backups without logins).
7. **Developer verification:** register the developer identity, package name and release certificate before the public beta? *Recommendation:* yes, as an owner task in M6 at the latest, after checking the programme's current terms (§9 F15). Also: who holds the offline copies of the release keystore and the manifest signing key?
8. **Release host:** GitHub owner/repo for `UPDATE_MANIFEST_URL`, and whether a mirror on our own domain (product #23 mentions one) is wanted. *Recommendation:* GitHub only in v1; the manifest signature makes a mirror safe to add later.
9. **Speed test:** the product's verdict example ("Your connection: 38 Mbit/s") needs a third-party speed test. *Recommendation:* no; report the provider's delivery rate and the Wi-Fi link speed (§3.11.2).
10. **Crash reports (D3):** keep local and in the log export only until a processor is chosen? *Recommendation:* yes.
11. **Default focus on Welcome:** "Set up from phone" (product S1). Keep, given the client-isolation fallback text? *Recommendation:* keep.
12. **PIN iterations:** 100,000 PBKDF2 iterations for a 4-digit PIN add little against offline guessing (10,000 PINs) but cost latency; the Keystore AEAD is the real protection. *Recommendation:* keep 100,000 if ≤ 150 ms on the Fire TV Stick 4K (M6), else lower; the iteration count is stored in the blob.
13. **Restore Replace mode keeps playlists absent from the backup** (offers removal) rather than deleting them. *Recommendation:* keep (no silent deletion of a playlist the user added after the backup).
14. **Fire OS 6 (API 25) Downloads target needs a runtime storage permission.** Ask at the first "To Downloads" choice, and let the default weekly backup fall back to app storage with the row's grant action? *Recommendation:* yes.
15. **`OPEN_SEARCH` on `KEYCODE_SEARCH` in the guide by default** (M3 §8 Q3). *Recommendation:* yes; the player leaves it unbound by default (bindable).

---

## 9. Facts to verify (for the reviewer to cross-check against `docs/design/m5-api-notes.md`)

| # | Fact the design depends on | Where used | Review verdict (2026-10-16) |
|---|---|---|---|
| F1 | `KeyEvent.getDevice()` returns an `InputDevice` with a non-null `getName()` for real remotes; injected events (`Instrumentation.sendKeySync`) with an unknown device id give `null`; `InputDevice.isVirtual()` semantics; Compose's `KeyEvent.nativeKeyEvent` keeps the device id | §3.8.3, I-M5-12 | partial (device APIs verified; null/virtual for injected events is a gap: I-M5-12 now expects 'unknown device') |
| F2 | `KeyEvent.keyCodeToString(code)` returns `KEYCODE_*` names on API 25+; `KEYCODE_SETTINGS = 176`, `KEYCODE_SEARCH = 84`, volume/mute/power/sleep/wakeup codes as listed in §2.12.1 | §3.8, §2.12 | partial (codes for Settings, Home, Power, Sleep, Wakeup, TV power verified; volume/mute not listed) |
| F3 | Compose `onPreviewKeyEvent` on the root sink still receives keys while a `BasicTextField` elsewhere has focus only if it is an ancestor: the editor island must not sit inside the sink, or the router must return false (we do the latter) | §2.12.5 | unverified (moot: the router returns false while editing and intercepts BACK, S3) |
| F4 | Compose `BasicTextField` + `KeyboardOptions(keyboardType = Uri/Password, imeAction = Next/Done)` on Android TV opens the system IME (Gboard TV, Fire TV keyboard); `KeyboardActions` fire on the TV IME's action key; Fire OS IME may run fullscreen (extract mode) | §0.2, §3.2.2 | partial (Gboard TV [web]; Fire OS keyboard and extract mode are gaps: manual §6.8) |
| F5 | `InputMethodManager.getEnabledInputMethodList()` is empty on boxes without an IME (to show "use your phone") | §3.4.3 | unverified (now used: the no-keyboard fallback, S3) |
| F6 | `ServerSocket` bound to a specific site-local IPv4 works without extra permissions on Fire OS and Google TV; `ConnectivityManager.getLinkProperties(network)` and `NetworkCapabilities.TRANSPORT_VPN / WIFI / ETHERNET` behave on API 25–35 | §3.5.2 | partial (APIs verified; Fire OS / Google TV behaviour on device) |
| F7 | Browsers expose `crypto.subtle` only in secure contexts (HTTPS or localhost), so a page on `http://192.168.x.y` cannot use WebCrypto | §0.3 | unverified (reviewer agrees from knowledge) |
| F8 | NanoHTTPD's last release and its upload temp-file CVEs (to justify not using it) | §0.3 | partial (NanoHTTPD 2.3.1, 2016 verified; CVE recalled as CVE-2022-21230) |
| F9 | `io.nayuki:qrcodegen` latest version on Maven Central, MIT licence, Java 8 bytecode (desugaring) | §6.6 | **verified (commit 0)**: 1.8.0 is the latest (maven-metadata 2022-04-17); licence and bytecode see §1.9a |
| F10 | `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` availability by API level (expected API 26+); `Mac("HmacSHA256")` and `Cipher("AES/GCM/NoPadding")` with a 12-byte IV and 128-bit tag on API 25 | §3.10.3, §3.9.6 | **verified (commit 0)**: factory throws `NoSuchAlgorithmException` on an API 25 emulator, exists on API 30 (BC 1.61); the `Mac` loop passes RFC 7914 §11 on both. AES-GCM on API 25 not run (WP-D's codec test) |
| F11 | Tink 1.23 `Ed25519Verify` (subtle, pure Java) works on API 25 under R8; RFC 8032 vectors pass | §3.12.2 | unverified (commit 0 runs the RFC 8032 vectors on the emulator) |
| F12 | `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` returns signers for an uninstalled APK on API 28+ (and `GET_SIGNATURES` below); `PackageInstaller` session flow on API 25–35: `STATUS_PENDING_USER_ACTION` + `EXTRA_INTENT`, `PendingIntent` mutability required for the status receiver on API 31+ | §3.12.3, §3.12.4 | partial (API levels verified; null GET_SIGNATURES on 25-27 now skips the check, S7) |
| F13 | `https://github.com/{o}/{r}/releases/latest/download/{asset}` redirects to GitHub's release-asset host(s) without authentication and without the REST API rate limit; the exact host names for the redirect allowlist | §3.12.2 | unverified (allowlist includes release-assets.githubusercontent.com, S7) |
| F14 | `canRequestPackageInstalls()` + `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:` on Google TV and Fire OS 7/8; Fire OS 6's global unknown-sources path; whether Fire OS honours `PackageInstaller` sessions from a sideloaded app or needs the `ACTION_VIEW` fallback; Google Play's current policy text on self-updates and `REQUEST_INSTALL_PACKAGES` | §3.12.4, §3.12.6 | partial (canRequestPackageInstalls verified; Fire OS paths and Play policy [web-summary]) |
| F15 | Google's Android developer verification: scope (certified devices; Android TV and Google TV included or not), regional dates, exemptions (ADB, an advanced user flow), what registration requires (package name, signing certificate); Fire OS not being a Google-certified build | §3.12.6, §8 Q7 | verified (scope): TV and Google TV outside the Sep 2026 enforcement, non-Play not subject yet, 2027+ global; Fire OS not mentioned |
| F16 | `MediaStore.Downloads` insert without permission on API 29+; visibility of files created by a previous install after reinstall (API 29 vs 30+), and what `READ_EXTERNAL_STORAGE` grants for non-media files on API 29–32 and 33+ | §3.10.5 | unverified (commit 0 measures on API 30 and 33, S6) |
| F17 | Presence of a DocumentsUI handler for `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` on Fire OS 6/7/8 and Google TV builds (`resolveActivity` result) | §3.4.3, §3.10.5 | **contradicted**: resolveActivity can hit a stub; detect DocumentsUI by package (§3.10.5) |
| F18 | OkHttp 5.5.0 `okhttp-dnsoverhttps` artifact coordinates and `DnsOverHttps.Builder` API (`client`, `url`, `bootstrapDnsHosts`, `includeIPv6`); `Interceptor.Chain.withConnectTimeout/withReadTimeout` in OkHttp 5 | §3.13, §6.6 | partial (artifact already a dependency; Builder and per-call timeouts [memory]) |
| F19 | Media3 1.11.1: how to make the audio sink accept AC-3/E-AC-3/DTS/TrueHD passthrough when `AudioCapabilities` does not list them (custom `AudioCapabilities` / `AudioOutputProvider` / `DefaultAudioSink.Builder` API in 1.11), and what `AudioTrack` does with an unsupported encoding (init failure vs silence) so the M2 watchdog catches it | §2.12.7 | **verified (commit 0, sources)**: a `ForwardingAudioOutputProvider` passed to `DefaultAudioSink.Builder.setAudioOutputProvider`; runtime-switchable at the next re-prepare (REPREPARE); unsupported encoding → `AudioSink.InitializationException` (L34) |
| F20 | Changing `DefaultTrackSelector` tunneling, buffer `LoadControl` thresholds and the codec selector take effect on the next `prepare` without an engine rebuild (M2's `applySwitches` assumption) | §0.6, `reapplySettings` | verified (code: applySwitches per prepare) |
| F21 | Media3 forced-subtitle selection ("Forced only"): `TrackSelectionParameters` support for selecting only `SELECTION_FLAG_FORCED` text tracks | §3.3.3 | unverified (Forced only stays conditional) |
| F22 | Room 2.8.5 + bundled SQLite: `randomblob`/`hex` in a migration; a nullable column with a unique index validates against the entity; `MigrationTestHelper` with `BundledSQLiteDriver` | §4.2 | partial (Room and bundled driver verified; randomblob and nullable unique index in MigrationTest) |
| F23 | WorkManager 2.12 periodic work with a 7-day interval and `requiresStorageNotLow` | §3.10.5 | verified (pattern already used in RefreshScheduler) |
| F24 | Package visibility (`<queries>`) needed on API 30+ to list `ACTION_VIEW video/*` handlers; which extras VLC/MX Player/Just Player read for headers and titles | §3.3.4 | unverified |
| F25 | `ActivityManager.clearApplicationUserData()` on API 25–35 (kills the process, clears Keystore entries of the app?) | §3.3.10 | unverified |
| F26 | `Settings.Global.BOOT_COUNT` readable without permission on API 24+ (Fire OS included) | §3.9.4 | unverified |
| F27 | Wi-Fi link speed: `WifiManager.getConnectionInfo().linkSpeed` (deprecated API 31) or `NetworkCapabilities.getLinkDownstreamBandwidthKbps()`; availability on TV devices and Ethernet | §3.11.2 | unverified |
| F28 | Compose `LazyColumn.animateScrollToItem` cost on low-end TVs for settings lists (or use `scrollToItem` without animation) | §3.2.1 | unverified |

---

## Appendix A. Facts relied on (code on `main` at `f4b289e`)

- `KeyRouterImpl` is the one key path; it reads `KeyMap` on every first DOWN and fixes the context per held key; SEARCH passes keys to Compose except BACK while idle (`ui/.../input/KeyRouterImpl.kt:107-145`). `InputModule` binds `KeyMap` to `DefaultKeyMap()` (`InputModule.kt:32`).
- `BoxAction` ids are append-only; `remappable` flags exist (`core/.../input/InputContracts.kt:4-38`); `KeyContext { GUIDE, PLAYER }` (`:40-41`).
- `DefaultKeyMap` has four maps and long bindings only for the OK family and PLAYER LEFT/RIGHT (`core/.../input/DefaultKeyMap.kt:16-57`).
- Footer hints read `keysSeen` and hard-code `PROG_RED`, `CHANNEL_UP`, digits (`GuideStateMachineImpl.kt:1540-1550`).
- SETTINGS today: `openSettings()` sets the mode and calls `effects.openSettings()`; BACK returns to FULL when opened from the quick bar (`GuideStateMachineImpl.kt:263, 293-296, 608-612`); `AppRoot` draws `SettingsPlaceholder` and covers the hole (`AppRoot.kt:75-79, 310`).
- `GuideStateMachine` already composes `full` and `horizontalRepeat` (`GuideStateContracts.kt:199-203`); `handles` (`:180`).
- `GuideSettings` (in memory, `GuideFeedContracts.kt:118-131`, `InMemoryGuideSettings`), `PlayerSettings` (`core/.../full/PlayerSettings.kt:22-38`, `InMemoryPlayerSettings`), `PlaybackSettings` (`player/.../api/PlaybackProviders.kt:59-90`, `InMemoryPlaybackSettings`, bound in `app/.../playback/PlaybackBindingsModule.kt:25`), `DataSettingsSnapshot` (`data/.../repo/DataSettings.kt`) are all in memory; their KDoc names M5 as the persistence milestone.
- The controller applies settings per prepare in `applySwitches` (`PlaybackControllerImpl.kt:745-773`); AFR changes are collected live (`:840`); the engine (and so `softwareAudioFallback`) is created once (`:809`). `AudioOutputMode { AUTO, ALWAYS_DECODE, STEREO }` (`core/.../playback/PlaybackContracts.kt:275`); the passthrough gate denies by MIME set (`player/.../engine/PassthroughGateAudioSink.kt:17-29`, `PlayerFactory.kt:141-142`).
- `library.db` is version 3 with the hand-written `MIGRATION_2_3` (`data/.../db/LibraryDatabase.kt:98`, `Migrations.kt:26-42`); `playlist` already has every per-playlist column of product §4.2 (`PlaylistEntity.kt`), including `xtream_output`, `xtream_output_learned`, `name_prefix_*`, `user_agent`, `extra_headers_json`, catch-up columns and `max_connections_override`.
- Parental predicate `PARENTAL` with `:unlocked` (`ChannelDao.kt:238-242`); `GuideQueries` binds it as `?1` (`GuideQueries.kt:52-71, 337`); `GuideLibraryImpl.sources(unlocked)` omits locked groups (`GuideLibraryImpl.kt:56-84`); callers pass `GuideSettings.debugUnlocked` (`GuideViewModel.kt:335`, `PlayerViewModel.kt:263, 389`, `SearchViewModel.kt:108`). Adult detection: `PlaylistDifferImpl.kt:355, 390`.
- Credentials: `TinkCredentialStore` (AEAD with AD = ref, permanent-loss `wipe()` deletes all rows and flags `needs_reauth`, `data/.../security/TinkCredentialStore.kt:115-119`); `PlaylistSecret.urls` holds the login host then the backup servers (`DataContracts.kt:68-74`).
- `XtreamLoginUrl.parse` splits `get.php`/`player_api.php`/`xmltv.php`/stream URLs (`core/.../xtream/XtreamLoginUrl.kt`); `XtreamAccountInfo` has status, expiry, connections, formats, server info (`XtreamContracts.kt:27-42`); `SourceError` kinds (`core/.../common/Outcome.kt:23-66`).
- `PlaylistRepository.addXtream/addM3uUrl/addM3uFile/replaceSecret/delete` (`data/.../repo/PlaylistRepository.kt:56-131`); `RefreshScheduler.refreshNow/schedulePlaylist/scheduleEpgPeriodic/onAppStart` (`data/.../work/RefreshScheduler.kt:48-114`); `DisplayNames.setRule/previewCount` (`data/.../guide/DisplayNames.kt:45-52`); `GroupDao.setGroupOrder/upsertGroupHidden/upsertPrefs`; `FavouriteDao` list and order methods; `EpgMatchRepository.unmatched/suggest/setOverride/clearOverride/matchIncremental` (`EpgMatchRepository.kt:148-204`).
- `HttpModule` has the DoH hook comment (`data/.../net/HttpModule.kt:18`); `PlaybackHttpClients` builds its own clients (`player/.../datasource/PlaybackHttpClients.kt:46-56`).
- `BoxLog` redacts every message and names M5 for the ring buffer (`core/.../common/BoxLog.kt:3, 25`); `TheBoxApplication` installs the `android.util.Log` sink.
- `LeaseKind.PROBE` exists in the broker (`core/.../playback/PlaybackContracts.kt`, `BrokerCore.kt`).
- `ErrorButton` and the text-step steps M4 left for M5 (`core/.../full/PlayerContracts.kt:258`; `docs/design/m4-player-ux.md` §3.7).
- `QuickItem` list (`PlayerContracts.kt:214-217`); `GuideAsset.LOCK_GLYPH` (`GuideRenderContracts.kt:8`); `ui/src/main/res/drawable/wordmark.xml` and `ui/src/main/assets/licenses` exist.
- Fake panel: `auth_mode`, `status`, `max_connections`, `ua_whitelist`, `html_splash`, `linger_status`, synthetic mode with prefixes and no-EPG channels (`tools/fakepanel/README.md`).
- Manifest: cleartext on, `allowBackup="false"`, no install permission yet (`app/src/main/AndroidManifest.xml`).

## Appendix B. Named constants

| Constant | Value | § |
|---|---|---|
| `SETTINGS_LOAD_TIMEOUT_MS` | 1,000 | 0.5 |
| `SETTINGS_LOAD_BUDGET_MS` | 50 | 5 |
| `SETTINGS_WRITE_DEBOUNCE_MS` | 250 | 2.2 |
| `NOTICE_MS` | 3,000 | 3.1 |
| `HOLD_ACCEL_MS` | 600 | 3.2.2 |
| `PagedList.PAGE_SIZE` | 32 | 2.1 |
| `GROUPS_REVIEW_MIN_GROUPS` | 8 | 3.4.5 |
| `PROBE_BYTES` / `PROBE_MS` | 262,144 / 3,000 | 3.4.5 |
| `PHONE_PORT_PREFERRED` | 47100 | 3.5.2 |
| `PAIR_MAX_WRONG` | 5 | 3.5.2 |
| `PAIR_IDLE_MS` / `PAIR_MAX_MS` | 600,000 / 1,800,000 | 3.5.2 |
| `NO_REQUEST_HINT_MS` | 90,000 | 3.5.1 |
| `HTTP_MAX_HEADER_BYTES` | 8,192 | 3.5.2 |
| `HTTP_MAX_JSON_BYTES` | 65,536 | 3.5.2 |
| `PHONE_UPLOAD_MAX_BYTES` | 268,435,456 (S4) | 3.5.2 |
| `PROBE_OWNER_ID` | −2 (reserved tune id for probe leases, S12) | 1.5 |
| `PIN_OFFER_MAX_PER_PLAYLIST` | 1 (B3) | 3.4.5 |
| `BACKUP_NUDGE_DAYS` | 30 (S6) | 3.3.9 |
| `HTTP_SOCKET_TIMEOUT_MS` | 15,000 | 3.5.2 |
| `HTTP_MAX_SOCKETS` | 6 (4 per address; 2026-11-09 review) | 3.5.2 |
| `RULE_REGEX_MAX_CHARS` | 200 | 3.7.3 |
| `RULE_REGEX_BUDGET_MS` | 50 | 3.7.3 |
| `KEY_OVERRIDES_MAX` | 200 | 3.8.1 |
| `CAPTURE_TIMEOUT_MS` | 10,000 | 3.8.3 |
| `PIN_KDF_ITERATIONS` | **10,000** (commit-0 benchmark: max 82 ms on the API 30 emulator; ≤ 150 ms; stored in the blob) (S15) | 3.9.6 |
| `PIN_KDF_BUDGET_MS` | 150 (emulator proxy; re-measured on the Fire TV Stick 4K in M6) | 2.13 |
| Lockout steps | 3 → 30 s, 6 → 2 min, 9 → 10 min, 12+ → 30 min | 3.9.4 |
| `BACKUP_PASSPHRASE_MIN` | 8 | 3.10.3 |
| `BACKUP_KDF_ITERATIONS` | **50,000** (commit-0 benchmark: max 787 ms on the API 30 emulator; ≤ 1 s; stored in the file) (S15) | 3.10.3 |
| `BACKUP_MAX_BYTES` / `BACKUP_MAX_LINE_BYTES` | 67,108,864 / 1,048,576 | 3.10.4 |
| `AUTO_BACKUP_KEEP` | 4 | 3.10.5 |
| `LOG_RING_LINES` | 4,000 (512 chars each) | 3.11.3 |
| `LOG_EXPORT_MAX_BYTES` | 8,388,608 | 3.11.4 |
| `UPDATE_CHECK_INTERVAL_MS` | 86,400,000 | 3.12.2 |
| `UPDATE_FIRST_CHECK_DELAY_MS` | 60,000 | 3.12.2 |
| `IPV6_FAILS_FOR_AUTO` | 2 | 3.13 |



---

## Lead sign-off (2026-10-16)

Approved after the adversarial review (`docs/review/m5-design-review.md`). Every ruling below is applied in the sections cited; where the lead did not override it, the reviewer's proposal was taken.

**Blockers**
- **B1 (lock bypass)** accept: `TuneGuard` (§2.6) in the controller path every tune funnels through (guide OK, channel list, number entry, CH±, previous channel, reminders, search, catch-up start, resume-after-lifecycle); a refused previous channel returns `PreviousResult.Locked`, a refused zap skips, a refused resume emits `Stopped(LOCKED)` and opens the PIN prompt; the zap list is cleared and rebuilt on relock; a relock caused by the app stopping is never "mid-programme" (§3.9.2, §3.9.5). Tests: `ParentalBypassTest`, `PinRoutingTest` rows, I-M5-14 extended.
- **B2 (Always passthrough)** accept, owned by WP-A (+1.5 days, `BoxRenderersFactory`, `PassthroughGateAudioSink`, `PlayerFactory`): the encodings are forced past `AudioCapabilities` through the sink's capabilities override chosen by F19 in commit 0; `EffectTiming` REPREPARE (commit 0's F19 found the runtime hook, the `AudioOutputProvider` of §2.12.7; lead ruling 2026-10-16, §1.9a); the fallback is the existing L34 init failure / `AUDIO_NOT_STARTED` path; no silence detector (not feasible: the sink cannot observe the receiver's output); the row says "If you hear no sound, turn this off"; Back to Auto in the audio picker, the stream-info line, the fallback toast and the AUDIO_OUTPUT_FAILED card (§2.12.7, §3.3.3, §3.14); I-M5-27 on the emulator's PCM-only sink. **Corrected sentence for `docs/decisions/2026-10-14-settings-and-branding.md`** (the lead edits that file), replacing "The 3 s silence watchdog still drops to PCM, with a toast naming the reason": *"The Box forces AC-3, E-AC-3, DTS and TrueHD past the HDMI capabilities the platform reports. If the TV or receiver refuses the stream (the audio output fails to start, or the audio clock never advances), it falls back to decoding to PCM with a toast naming the reason and remembers that for the audio route. A receiver that accepts the bitstream but plays nothing cannot be detected, so the option's description says 'If you hear no sound, turn this off', and the audio picker offers Back to Auto."*
- **B3 (adult content with no PIN)** accept the fourth state FILTERED_NO_PIN: adult groups stay out of every mixed list (All channels, Favourites, Recent, search, zap lists, number entry, previous channel, reminders) but open from the Groups panel through the "not protected" card; the Parental row reads "On · not protected: set a PIN"; the PIN offer appears in the onboarding groups review, after any playlist add or import from Settings that found adult groups, and after a restore that carried locks (once per playlist). The plan's "adult groups auto-locked" is kept as "auto-filtered until a PIN exists" (§0.8, §2.6, §3.4.5, §3.4.6, §3.9.1, §4.3; I-M5-29).
- **B4 (ownership)** accept: §1.3a lists one owner per file; commit 0 provides the shared skeletons (`GuideEffectsImpl`/`PlayerEffectsImpl` delegations, the `TinkCredentialStore` blob API and key-reset flag, ACRA local-only configuration, DAO query stubs including `searchEpgChannels`, the `TheBoxApplication` hooks, the `GuideViewModel` startup wait and first-run decision, the `SettingsTree` registry, the `AppRoot` slots, the manifest, the six call-site changes of S10). WP file lists restated and disjoint (§1.3a wins over §1.4–§1.8).

**Should fix**
- **S1** accept: nothing written before a domain's first read; queued updates replay on the stored document; the 1 s timeout affects only the first frame; a restore cancels pending writes (§0.5, §2.2).
- **S2** accept: PIN-free entries (sync chip → SyncStatus, error-card routes) open as detached layers with nothing of Settings behind them; their links into gated routes ask (§2.3, §3.2.5).
- **S3** accept: the router intercepts BACK while the editor is open (`EditorBack`); the editor reuses M3's G5 Down workaround; no keyboard app → "No keyboard on this TV: use your phone" with the phone-page action (§0.2, §2.12.5, §3.2.2).
- **S4** accept: `FLAG_KEEP_SCREEN_ON` during phone setup, import and restore; the VPN hint; upload cap 256 MB streamed to disk, never buffered; the LAN-sniffing note for backup transfers (§3.4.5, §3.5).
- **S5** accept: an explicit group decision overrides per-channel adult detection (§4.3).
- **S6** accept: the weekly backup's promise stated per device (app storage survives updates, not reinstalls, on API 30+ without a picker); the phone-page download is the durable path, with a monthly nudge; Downloads when permission and MediaStore allow; DocumentsUI detected by package (F17 contradicted) (§3.10.5).
- **S7** accept: `GET_SIGNATURES` on API 25–27 with null/empty → skip and log; per-build-type manifest keys and the running build's package id; a monotonic `sequence`, replay rejected; the redirect allowlist includes `release-assets.githubusercontent.com` (§2.10, §3.12).
- **S8 / Q3** accept: settings fail open after a Keystore reset with the notice; content stays FILTERED_NO_PIN until a new PIN (§3.9.6; I-M5-28, `thebox-debug://keystore-reset`).
- **S9** accept: `LiveSettingsApplier` in `:core` next to `ArchivePlayback`, implemented in `:app` (archive re-prepared at the playhead); runs when the user leaves Settings by any route, at most once per visit (`onLeave`) (§0.6, §2.3, §2.12.6).
- **S10** accept: `ParentalAccess` passed explicitly through `GuideLibrary`, `GuideSearch` and `EpgMatchRepository.unmatched` (six call sites, commit 0); no global state in queries (§2.12.9, §4.3).
- **S11** accept: `GuideViewModel.startup()` awaits `settingsAttached`; the guide machine waits while `settings == null` in SETTINGS (§2.13).
- **S12** accept: the existing `core.match.MatchLevel`; the Auto past-days and automatic mini-still/ambient mappings in one place each with tests; `GuideStateContracts.kt` path; the broker is `ConnectionBrokerCore.acquire` behind `StreamBrokerRegistry` with `PROBE_OWNER_ID`; the router method is `onKey` (§1.5, §1.9, §2.2, §2.4).
- **S13** accept: the composite sink redacts the `Throwable`'s message and stack once and feeds all three sinks (§3.11.3).
- **S14** accept: `KEYCODE_UNKNOWN` rejected; captured key swallowed until its UP; the timeout text; **recorded deviation**: D-pad and OK are reserved inside the player too, the only D-pad remap in the player is "Up/Down in player" (owner decision 2026-10-15), and the mapping screen says so inline (§2.7, §2.12.5, §3.8.2, §3.8.3).
- **S15 / Q12** accept: PIN and backup KDF iteration counts set by a commit-0 emulator benchmark (PIN ≤ 150 ms on the x86 emulator as the proxy; backup ≤ 1 s), stored with the blob/file, re-measured on the Fire TV Stick 4K in M6 (§1.9, §2.13, Appendix B).
- **S16** accept: `api_fault` in the fake panel and I-M5-30; 458 at login stated as probe-only; B1/B2/S8 tests added (I-M5-27, I-M5-28, `ParentalBypassTest`); I-M5-12 expects "unknown device" on the emulator (§6).

**Nits** (all taken as the reviewer proposed unless noted): N1 `ActionGroup` aligned · N2 `ordinal` in `BackupRecord.EpgSource` · N3 `catchup_correction_h` treated as learned, not exported · **N4** "Daily at a set time" restored (lead) · **N5** frame-rate matching shown below API 30 with a note (lead) · N6 `uid` generating default · N7 no tv-material `Switch`/`RadioButton`/`ListItem` · N8 DNS on the base client only, `PlaybackHttpClients` untouched, dnsoverhttps already a dependency · N9 Restart now · N10 LAN DoS stated · **N11** the Guide key leaves Settings to the guide and Exit opens the exit dialog from anywhere (lead) · N12 Stop destination specified · N13 "Include logins" focused by default · N14 FileProvider exposes `filesDir/update` only · N15 accent cited as hex · N16 licence texts in WP-E.

**Deferrals:** D1 (per-channel Locked rows → v1.1), D2 (Language), D3 (crash sending; ACRA local-only set-up lands before WP-E's export, in commit 0), D4 (logos from guide → v1.1), D5 ("Still watching?" → M6), D6 (store flavour): all **accepted**.

**Open questions:** **Q1** one M5, five parallel WPs, no split (44.5 agent-days, ≈ 15 working days; risk critical path WP-C) · **Q2** yes (D1) · **Q3** settings fail open, content filtered (S8) · **Q4** yes, Remote control in the simple view · **Q5** bindings per action context · **Q6** opt-in stored backup passphrase, default off · **Q7** developer-verification registration is an owner task before the beta; custody of the release keystore and the manifest signing key flagged to the owner · **Q8** GitHub only; the repo is a build-time constant with a placeholder until the owner names it · **Q9** no speed test · **Q10** yes, crash reports local and in the export · **Q11** keep "Set up from phone" as the default Welcome focus · **Q12** by measurement (S15) · **Q13** keep (Replace keeps playlists absent from the backup) · **Q14** yes · **Q15** yes (`OPEN_SEARCH` on `KEYCODE_SEARCH` in the guide; on Fire TV the key never arrives).

**Proposer notes for the lead (interpretations, not disagreements with a ruling):**
1. ACRA: ruling 4 puts the local-only configuration in commit 0 and ruling 20 says it "lands in WP-E first"; the doc puts the configuration file in commit 0 (`app/.../crash/CrashConfig.kt`) and WP-E only reads the reports. Move it to WP-E if that was the intent.
2. With no PIN **and** auto-lock turned off, access is OPEN (the user chose it); locking a group or channel in that state first opens PIN creation, so an unenforced manual lock cannot exist except through a restore or the groups review "Not now", which the PIN offers cover.
3. In FILTERED_NO_PIN, "Locked channels in guide: Hide" still lists locked groups in the Groups panel, because hiding them there would recreate the M3 trap (no way to reach them without a PIN).
4. Error-card routes are always detached (not only with "Lock settings" on): BACK returns to the card in FULL either way, and it keeps one rule.
5. WP-A (9.5 days, with passthrough) is the longest WP and sets the elapsed time; WP-C remains the risk path. Moving the Network page and `BoxDns` (1 day) to WP-E would balance them at 8.5/9.0 if the lead prefers.
