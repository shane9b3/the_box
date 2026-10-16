# M5 (Setup, settings, parental, backup) — verified API / platform / repo notes

Status: fact sheet for the M5 proposer, reviewer and implementers. Written 2026-10-16 on branch `worktree-agent-aaa9a1bbad7e6a8a6` off `f4b289e`. Facts only; no design opinion.
Tags: **[verified]** read from a source, stub, `api-versions.xml`, pom or repo file named in the line. **[web]** read from a web page named in the line (page text as returned by the fetch tool). **[web-summary]** from a search-result summary only; the page itself was not opened. **[memory]** recalled, no source read. **[inference]** a conclusion drawn from cited facts. Everything tagged [memory], [web-summary] or [inference] that matters is repeated in §15 (gaps).

Conventions: repo paths are relative to the worktree root. Platform = `~/Library/Android/sdk/platforms/android-35/android.jar` (javap) and `.../android-35/data/api-versions.xml` ("since" levels). The stubs have no javadoc, so behaviour claims about the platform are web or memory. Library sources = sources jars in `~/.gradle/caches/modules-2/files-2.1/`. Cite as "M5 notes §n". Format follows `docs/design/m4-media3-ui-api-notes.md`.

## 0. Versions

| Item | Pinned (`gradle/libs.versions.toml`) | Notes / verified how |
|---|---|---|
| AGP / Kotlin / KSP | 9.4.1 / 2.4.21 / 2.3.12 | catalog |
| compileSdk / minSdk / targetSdk | 37 / 25 / 35 | catalog |
| Compose BOM | 2026.09.00 | ui, foundation = **1.12.1** (sources jars in cache) |
| `androidx.tv:tv-material` | **1.1.0** | cache has `-sources.jar`, `.aar`, `.module`; `ui/build.gradle.kts` `api(libs.tv.material)`. `tv-foundation` is not a dependency. |
| Media3 | 1.11.1 | `:ui` now has `libs.media3.ui.compose` and `libs.media3.ui` (`ui/build.gradle.kts`); the M4 sheet §0 said `media3-ui` was absent (changed in the M4 code review). |
| Room / androidx.sqlite | 2.8.5 / 2.6.2 | `library.db` uses `BundledSQLiteDriver` (`LibraryDatabase.build`) |
| WorkManager / androidx.hilt | 2.12.0 / 1.4.0 | sources jar for work-runtime 2.12.0 in cache |
| OkHttp / okio | **5.5.0** / 3.18.1 | `:data`: `okhttp`, `okhttp-dnsoverhttps`; `:player`: `okhttp`. Cache: okhttp, okhttp-android, okhttp-jvm, dnsoverhttps, mockwebserver3 at 5.5.0 |
| kotlinx-serialization-json | **1.11.0** | `:core` `api(...)`, `:data` `implementation(...)`; serialization plugin on `:core` and `:data` |
| Tink | `tink-android` **1.23.0** | `:data` only (cache also holds 1.8.0, transitive) |
| Hilt | 2.60.1 | |
| ACRA | `acra-core` 5.14.2 | `:app` (configuration not read) |
| Coil | 3.6.3 | |
| **DataStore** | **not in the catalog, not in any `build.gradle.kts`** | Cache holds `androidx.datastore:datastore*` and `datastore-preferences*` **1.1.1 and 1.1.7** (sources jars for `datastore-android`, `datastore-core-android`, `datastore-preferences-android`, `datastore-preferences-core-android` 1.1.7). Which pinned library pulls them in was **not identified**. |
| ZXing, NanoHTTPD, Ktor | **not in the catalog, not in the cache** | `ls ~/.gradle/caches/modules-2/files-2.1` filtered for zxing/nano/ktor: empty |
| Moshi | cache has `com.squareup.moshi` (transitive, not declared) | not used by the app |
| Gradle / JDK | 9.8.1 / 21 | `docs/m0-toolchain.md` |

Manifest facts (`app/src/main/AndroidManifest.xml`): permissions are only `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. **No `REQUEST_INSTALL_PACKAGES`, no `ACCESS_WIFI_STATE`, no `CHANGE_WIFI_MULTICAST_STATE`, no `FileProvider`.** `android:allowBackup="false"`; `usesCleartextTraffic="true"`; `networkSecurityConfig` = `res/xml/network_security_config.xml` (`base-config cleartextTrafficPermitted="true"`, trust anchors `system` + `user`). `MainActivity`: `launchMode=singleTask`, `screenOrientation=landscape`, `configChanges` includes `keyboard|keyboardHidden|navigation`. `uses-feature` leanback and touchscreen are `required=false`. WorkManager's auto-initializer is removed; `TheBoxApplication` is the `Configuration.Provider`. Single process (no `android:process`).

---

## 1. Settings storage in M1–M4 and the options for M5

### 1.1 What exists (verified by reading the files)

- **Four in-memory settings holders**, each `StateFlow<T>` + `update(transform)`; none persists:
  - `PlayerSettings` (`core/.../full/PlayerSettings.kt`): `infoBarMs=5000`, `numberEntryWaitMs=1500`, `upDown: UpDownInPlayer{QUICK_BAR_AND_INFO,ZAP,CHANNEL_LIST}`, `invertChannelDirection`, `back: BackInPlayer{GUIDE,CHANNEL_LIST}`, `onLaunch: OnLaunch{GUIDE_WITH_LAST,FULL_LAST,GUIDE_NOTHING}`. Interface `PlayerSettingsSource { settings: StateFlow<PlayerSettings>; update(transform) }`, impl `InMemoryPlayerSettings` (`data/.../player/`, `@Singleton`). KDoc: "In memory until M5 persists and edits them."
  - `GuideSettings` / `GuideSettingsSource` (`core/.../guide/`), impl `InMemoryGuideSettings` (`data/.../guide/`). Fields not listed here.
  - `DataSettingsSnapshot` / `DataSettings` (`data/.../repo/DataSettings.kt`): `epgPastDays=2`, `epgFutureDays=7`, `match: MatchSettings`, `storeDescriptions`, `maxDescriptionChars=2048`, `preferredLanguages`, `keepCachedXmltv: KeepCachedXmltv{ALWAYS,IF_FREE_OVER_1GB,NEVER}`, `epgRefreshIntervalH=12`, `globalShiftMin=0`, `playlistStaleAfterMs=12 h`; impl `InMemoryDataSettings`. KDoc: "M5 backs [DataSettings] with DataStore." (`m1-data-layer.md:1716` and `m2-playback-core.md:1085` say the same.)
  - `PlaybackSettings` (M2) lives in `core/.../playback/PlaybackContracts.kt`; fields not read here. `docs/decisions/2026-10-14-settings-and-branding.md` names `PlaybackSettings.tunneling`, `PlaybackSettings.audioOutput` and a new `AudioOutputMode.FORCE_PASSTHROUGH` for M5.
- **One app SharedPreferences use**: `KeyCapabilitiesImpl` (`ui/.../input/KeyCapabilitiesImpl.kt`): file `keys_seen`, key `codes` (string set of key codes as strings), read once on IO at creation, `apply()` on write. Tink's keyset is the other SharedPreferences user (below).
- **Tink keyset**: `KeystoreAeadProvider` stores the keyset in SharedPreferences file `thebox_keysets`, key `credentials_keyset`, master key `android-keystore://thebox_credentials_master` (`data/.../security/AeadProvider.kt`).
- **Per-channel and per-playlist settings are Room columns**, not preferences: `channel_prefs` (`ChannelPrefsEntity`: PK `(playlist_id, stable_key)`; `hidden`, `locked: Boolean?` (null = auto: `channel.is_adult` / group lock), `custom_name/number/logo/sort_index`, `epg_shift_min`, `aspect_mode`, `audio_lang`, `subtitle_lang`, `video_decoder`, `buffer_profile`, `last_watched_at`, `watch_count`, `updated_at`, learned `playback_flags`, `fps_milli` which "M5 export leaves out") and `playlist` (`PlaylistEntity`: `user_agent`, `extra_headers_json`, `use_playlist_epg`, `update_on_start`, `refresh_interval_h=24`, `numbering_mode`, `order_mode`, `xtream_output`, `xtream_output_learned`, `content_types` bitmask (default 7), `charset_override`, `catchup_mode_setting`, `catchup_days_override`, `token_zone_setting`, `padding_before_sec=60`, `padding_after_sec=300`, `max_connections_override`, `ignore_tls_errors`, `active_base_index`, `name_prefix_mode/custom/ver`, refresh bookkeeping). Other user-data tables: `group_prefs`, `favourite_list`, `favourite`, `reminder`, `stream_source`, `purged_key`, `credential`, `epg_mapping` (per the `Migrations.kt` KDoc).
- **`PlaylistPlaybackInfo`** (`core/.../playback/PlaybackContracts.kt:35`) is a read model built from `PlaylistEntity` (`DataPlaybackSourceProvider.toInfo`, `app/.../playback/DataPlaybackSourceProvider.kt:168`): `playlistId, type, userAgent, extraHeaders, xtreamOutput, xtreamOutputLearned, activeBaseIndex, ignoreTlsErrors, maxConnectionsOverride, needsReauth`. It is not a settings store.
- `RoomChannelMemory` (`app/.../playback/`) persists last-channel memory through Room. Debug receivers (`GuideDebugReceiver`, `PlaybackDebugActivity`) mutate the in-memory settings in tests.
- **No DataStore usage anywhere** in `*.kt`/`*.kts` (grep `DataStore|datastore`: only KDoc and docs mentions).

### 1.2 Room schema and migration pattern (verified)

- `LibraryDatabase.VERSION = 3`, `exportSchema = true`; schemas committed as `data/schemas/app.thebox.data.db.LibraryDatabase/{1,2,3}.json`. 17 entities (`LibraryDatabase.kt:61-79`).
- 1→2 is `@AutoMigration(from = 1, to = 2)`; 2→3 is the hand-written `Migrations.MIGRATION_2_3` (`Migration(2, 3)` whose `migrate(connection: SQLiteConnection)` runs `connection.execSQL(...)` for five `ALTER TABLE … ADD COLUMN` and two `CREATE INDEX`); `Migrations.ALL = arrayOf(MIGRATION_2_3)`. Reason for hand-writing: Room's auto-migration rebuilds a table when an index is added. — `data/.../db/Migrations.kt`.
- **Policy in the code KDoc**: every schema change bumps `VERSION`, commits the exported JSON, ships an AutoMigration or a listed Migration plus an instrumented `MigrationTestHelper` test; `fallbackToDestructiveMigration*` is **forbidden**; `channel`, `channel_group`, `channel_fts`, `epg_match` are re-derivable (a migration may drop them and force a full refresh via `playlist.etag = NULL`, `refresh_seq = 0`); user-data tables must always be migrated.
- The Migration API in use is the `SQLiteConnection` one (androidx.sqlite 2.6.2 driver API), not `SupportSQLiteDatabase`.
- `epg.db` is raw `androidx.sqlite`, generation-named (CLAUDE.md). Not a settings store.

### 1.3 Jetpack DataStore

- **Versions**: Google Maven `androidx/datastore/datastore-preferences/maven-metadata.xml` (fetched 2026-10-16): **latest stable 1.2.1**, latest release 1.3.0-rc01, lastUpdated 2026-10-12. [web] Cache has 1.1.7 (sources read below). Licence Apache 2.0. [memory]
- **Flow semantics (1.1.7 `commonMain/androidx/datastore/core/DataStore.kt`)**: `data: Flow<T>` "will always either emit a value or throw an exception encountered when attempting to read from disk. If an exception is encountered, collecting again will attempt to read the data again." and "Do not layer a cache on top of this API… use `data.first()` to access a single snapshot." `updateData(transform)` is an atomic read-modify-write, serialized; completes after the data is durably persisted; throws `IOException` on write failure. **There is no synchronous getter**; a `StateFlow`-shaped API like the repo's `settings: StateFlow<T>` needs an initial value plus a collector (`stateIn`). [inference]
- **One instance per file**: `preferencesDataStore(name, corruptionHandler, produceMigrations, scope = CoroutineScope(Dispatchers.IO + SupervisorJob()))` returns a singleton delegate that creates the store lazily at `applicationContext.preferencesDataStoreFile(name)` (`files/datastore/<name>.preferences_pb`) — `androidMain/.../PreferenceDataStoreDelegate.android.kt`. `FileStorage` throws `IllegalStateException("There are multiple DataStores active for the same file: …")` if two instances of one file are active (`commonJvmMain/.../FileStorage.kt:53`). With Hilt the store must be a single `@Singleton`.
- **Migration from SharedPreferences**: `SharedPreferencesMigration(context, sharedPreferencesName, keysToMigrate = MIGRATE_ALL_KEYS)` (`androidMain/.../preferences/SharedPreferencesMigration.android.kt`) passed via `produceMigrations`. `DataMigration` KDoc: `shouldMigrate/migrate/cleanUp` "may be called multiple times, so their implementations must be idempotent"; if `migrate` fails nothing is committed. Types: boolean, float, int, long, string, string set. `keys_seen.codes` fits `stringSetPreferencesKey`.
- **Corruption**: `ReplaceFileCorruptionHandler<Preferences>` resets to defaults on `CorruptionException`; without it the flow throws.
- **Multi-process**: `MultiProcessDataStoreFactory` and `InterProcessCoordinator` exist (`androidMain/.../core/MultiProcessDataStoreFactory.android.kt`); the default store is single-process. The app has one process (manifest).
- **Proto DataStore** needs a `Serializer<T>` (`commonJvmMain/.../Serializer.kt`); a kotlinx-serialization JSON serializer is possible with the pinned library, but no `datastore` dependency exists to carry it. The Preferences store is typed per key (no nested objects); a list or map needs a string key holding JSON. [inference]
- **Alternatives already in the project**: Room (`library.db`, migration discipline above); the plain `SharedPreferences` pattern of `keys_seen`.

---

## 2. Text entry on TV

- **Repo precedent**: `SearchScreen.kt` `SearchField` uses `BasicTextField(singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }))` plus an `onPreviewKeyEvent` that moves focus Down on `Key.DirectionDown` KeyDown. Comment: "Device bug G5 (§5.11): the single-line field consumed D-pad Down, so the result rows were unreachable with a remote." No other text field exists in `ui`/`app` main code (grep `TextField|KeyboardOptions|ImeAction`).
- **Gboard on TV** [web: `developer.android.com/training/tv/get-started/onscreen-keyboard`]: supported input types on TV are **email, password, text, numeric** (set as on mobile); the keyboard **floats** (left/right/center) and is positioned with `android:privateImeOptions="horizontalAlignment=right"`; voice input (STT) runs inside Gboard and needs no app permission. The page says nothing on focus hand-off, IME option semantics or password behaviour.
- **Fire TV's keyboard**: not documented in anything fetched. [gap]
- **Compose TextField + D-pad on TV**: no official Google document found. A search returned only third-party/anecdotal items (Compose alpha-era soft-keyboard-not-shown reports; one non-official playbook claiming a TV text field can refuse to open the keyboard without explicit focus management). [web-summary] The repo's G5 comment is the only verified evidence.
- **Compose API in the pinned versions** (foundation 1.12.1 sources): `androidx.compose.foundation.text.KeyboardOptions` (`commonMain/.../text/KeyboardOptions.kt`), `KeyboardActions`, `BasicTextField`; `LocalSoftwareKeyboardController` is already used in `SearchScreen`. `androidx.tv.material3` 1.1.0 has **no text field** (see §9).
- **Password fields**: IME behaviour for password inputs on Gboard TV / Fire OS keyboards not documented in anything fetched. [gap]
- **Custom on-screen keyboards in other TV apps**: no source found. The only TiviMate-related finding (a third-party slide deck [web-summary]) says users paste long M3U URLs through the Fire TV or Google TV phone remote apps. [gap]
- **Plan context** (`docs/00-master-plan.md:25`): onboarding = "TV form with URL helpers, phone-assisted setup via LAN web page + QR, paste a full `get.php` URL → auto-convert to Xtream, UA probe".

---

## 3. Embedded LAN HTTP server for phone-assisted setup

### 3.1 Platform facts

- **`com.sun.net.httpserver` is not in Android**: `unzip -l android-35/android.jar | grep -c com/sun/net/httpserver` = 0 [verified]. (`javap -cp android.jar com.sun.net.httpserver.HttpServer` still prints a class because javap falls back to the host JDK.) `java/net/ServerSocket` and `javax/net/ssl/SSLServerSocket` are in the jar.
- **LAN address discovery** [verified, stubs + `api-versions.xml`]: `ConnectivityManager.getActiveNetwork()`, `getLinkProperties(Network)` (since 21), `LinkProperties.getLinkAddresses(): List<LinkAddress>`, `LinkProperties.getInterfaceName()`, `registerDefaultNetworkCallback(cb)` (since 24; `Handler` overload 26), `WifiManager.createMulticastLock(String)` (since 4), `WifiManager.getConnectionInfo()`.
- **No public API detects Wi-Fi client/AP isolation**: nothing inspected in `android.net` / `android.net.wifi` stubs exposes it. [inference from absence; not an exhaustive search] Only a phone actually reaching the page proves reachability.
- **NsdManager** (`api-versions.xml`): class since 16. `registerService(NsdServiceInfo,int,RegistrationListener)` and `discoverServices(String,int,DiscoveryListener)` since 16; the **`Executor` overload of `registerService` and `resolveService` since 33**; `resolveService` deprecated 34; `registerServiceInfoCallback` since 34; `discoverServices(DiscoveryRequest,…)` since 35. With `minSdk 25` the executor overloads need an API-level branch.
- **Multicast lock** [web-summary of the NsdManager reference]: on Android 12 and below, and Android 13 without T extensions 7, apps must hold a `WifiManager.MulticastLock` to receive mDNS packets; from T extensions 7 the system manages it for foreground apps. The lock needs `CHANGE_WIFI_MULTICAST_STATE`. [memory] NsdManager/mDNS availability on Fire OS: no source found. [gap]
- **Local network permission, Android 17 / API 37** [web: `developer.android.com/about/versions/17/behavior-changes-17`]: new runtime permission `ACCESS_LOCAL_NETWORK` (group `NEARBY_DEVICES`), mandatory for apps **targeting API 37+**; the page does not say whether inbound connections are covered and lists no exemptions. The project targets 35, so it does not apply until `targetSdk` is raised. [inference]
- **Cleartext**: the app allows cleartext app-wide (manifest + NSC). That governs the app's outgoing traffic; a server socket the app opens is unaffected. A phone browser opening `http://<ip>:<port>` is plain HTTP.

### 3.2 Server library options

| Option | Version / date | Size | Licence | Notes |
|---|---|---|---|---|
| NanoHTTPD `org.nanohttpd:nanohttpd` | **2.3.1**, last updated 2016-08-12 [web, Maven Central metadata] | jar **51,211 B**, sources 23,924 B [web, directory listing] | BSD-3-Clause per project [memory]; the 2.3.1 pom fetched does not declare it | Pure Java; `serve(IHTTPSession)`; test-scope deps only (httpclient/httpmime 4.2.5); no maintenance since 2016. |
| Ktor server CIO `io.ktor:ktor-server-cio` | **3.6.0**, 2026-09-16 [web] | not measured. Pom runtime deps: `slf4j-api 2.0.19`, `kotlinx-coroutines-core-jvm 1.11.0`, `ktor-server-core`, `ktor-http-cio`, `ktor-websockets`, `ktor-network`, `kotlin-stdlib 2.3.21` [web, pom] | Apache 2.0 [memory] | Pom states no Java target or minSdk; Android minSdk of Ktor 3 CIO **not found**. Coroutine-native. |
| `ServerSocket` + hand-written HTTP/1.1 | platform | 0 | n/a | In `android.jar`. Multipart, chunking, Content-Length, size caps, keep-alive are the implementer's. |
| `com.sun.net.httpserver` | — | — | — | **Not available.** |
| OkHttp `mockwebserver3` | 5.5.0 in cache | — | Apache 2.0 | Test library (catalog entry `okhttp-mockwebserver`); not a production server. [inference] |

### 3.3 HTTPS on the LAN

- A device cannot obtain a publicly trusted certificate for a bare LAN IP; a self-signed or private-CA certificate makes browsers warn, and the "Not secure" indicator persists even after the warning is bypassed (vendor docs from Sennheiser and ArcGIS). [web-summary]
- Workarounds seen: a real domain with a DNS record pointing to a private IP plus a (wildcard) public certificate; needs DNS and certificate issuance and distributes the private key to every device (forum objection in the same search). Plex's `plex.direct` per-server subdomain scheme appears in forum posts only; no first-party description was read. [web-summary]
- **Conclusion supported by what was read**: no zero-warning HTTPS path for an offline app serving a bare LAN IP was found. [inference; one query]
- Secure-context consequences of plain HTTP for the phone page (clipboard, camera, `crypto.subtle`) were not researched. [gap]

---

## 4. QR code generation

- **ZXing `com.google.zxing:core`**: latest **3.5.4**, lastUpdated 2025-11-11 [web, Maven Central metadata]. Jar **610,364 B** (sources 509,348 B) [web, directory listing]. Licence **Apache 2.0** (pom header comment) [web]. Pure Java; pom has only test-scope junit and build plugins (bnd, jar, proguard profile). Java bytecode target not stated in the pom fetched. [gap]
- API (memory, no jar in cache): `QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, w, h, hints)` → `BitMatrix` (`get(x, y)`, `width`, `height`); hints `EncodeHintType.MARGIN`, `ERROR_CORRECTION`, `CHARACTER_SET`. [memory]
- **Compose bitmap**: build an ARGB `IntArray`, `Bitmap.createBitmap(IntArray, w, h, Config.ARGB_8888)`, then `asImageBitmap()`. [memory] A pure-Compose alternative is drawing the `BitMatrix` modules with `DrawScope.drawRect` (the project custom-draws on Canvas; M3 notes §1). [inference]
- Design-px sizing: under `BoxDesignScale` 1.dp == 1 design px (`ui/.../theme/BoxDesignScale.kt`). Whether a given module size scans from couch distance was not tested. [gap]

---

## 5. Key capture for button mapping

### 5.1 Platform fields (stubs + api-versions, verified)

- `KeyEvent`: `getKeyCode()`, `getScanCode()`, `getDeviceId()`, `getSource()`, `getFlags()`, `getRepeatCount()`, `isLongPress()`, `isCanceled()`, `startTracking()`, `isTracking()`, static `keyCodeToString(int)` (since 12). `InputDevice`: `getName()`, `getDescriptor()` (since 16), `getVendorId()`/`getProductId()` (since 19), `getSources()`, `getControllerNumber()`, `isVirtual()`, `isExternal()` (API level not checked), `hasKeys(int...)`, static `InputDevice.getDevice(id)` / `getDeviceIds()` (M3 notes §2.3).
- Compose has `Key.*` constants for most TV keys, none for `KEYCODE_TV_NUMBER_ENTRY` (234), `DVR` (173), `TV` (170) (M3 notes §2.1). The repo's `Keys` object (`core/.../input/InputContracts.kt`) lists: CH± 166/167, GUIDE 172, INFO 165, CAPTIONS 175, LAST_CHANNEL 229, MEDIA_AUDIO_TRACK 222, TV_ZOOM_MODE 255, PROG_* 183-186, MEDIA_* (85-90, 126, 127, 130, 272-275), PAGE_UP/DOWN 92/93, MENU 82, SEARCH 84.
- Other TV-relevant codes in the android-35 stubs (`javap -constants`): `TV`170, `TV_INPUT`178, `TV_POWER`177, `STB_POWER`179, `STB_INPUT`180, `AVR_POWER`181, `AVR_INPUT`182, `BOOKMARK`174, `SETTINGS`176, `DVR`173, `TV_NUMBER_ENTRY`234, `TV_TELETEXT`233, `TV_DATA_SERVICE`230, `TV_CONTENTS_MENU`256, `TV_MEDIA_CONTEXT_MENU`257, `TV_TIMER_PROGRAMMING`258, `TV_AUDIO_DESCRIPTION`252, `MEDIA_TOP_MENU`226, `ALL_APPS`284, `ASSIST`219, `VOICE_ASSIST`231, `APP_SWITCH`187, `HOME`3, `POWER`26, `SLEEP`223, `WAKEUP`224, `SOFT_SLEEP`276, `NOTIFICATION`83, `SYSRQ`120, `MUSIC`209, `TV_INPUT_HDMI_1..4` 243-246.

### 5.2 What remotes send and what the system intercepts

- **Fire TV** [web: `developer.amazon.com/docs/fire-tv/remote-input.html`, Input Event Reference]: Home = system event, **cannot be intercepted**; Microphone/Search (voice remote only) = system event, **cannot be intercepted**; Back = `KEYCODE_BACK`; Menu = `KEYCODE_MENU` (default invokes OptionsMenu; app receives it); Select = `KEYCODE_DPAD_CENTER`; D-pad; Play/Pause = `KEYCODE_MEDIA_PLAY_PAUSE`; Rewind = `KEYCODE_MEDIA_REWIND`; Fast Forward = `KEYCODE_MEDIA_FAST_FORWARD`; Channel up/down = `KEYCODE_CHANNEL_UP/DOWN`. Notes on the page: media keys may be absent on some remotes; **volume, power and app-specific buttons (Netflix, Prime) cannot be mapped to events in third-party apps.**
- **Google TV / Chromecast remote, Onn, Shield, HDMI-CEC, generic HID remotes**: **no source read** on what they send. [gap] The repo records that Chromecast with Google TV and Onn remotes lack CH± (`docs/decisions/2026-10-15-player-keys-and-quick-bar.md:21`) as a known trade-off, not verified here.
- **Long press**: Compose delivers repeats as additional `KeyDown`s with `nativeKeyEvent.repeatCount`; tv-material treats `repeatCount == 1` as a long click (M3 notes §2.2). The platform sets `FLAG_LONG_PRESS` on the first repeat for Views that call `startTracking()` in `onKeyDown` and implement `onKeyLongPress`. [memory] Compose has no equivalent hook: use `repeatCount` plus a timer.
- **Repo implementation of the same problem** [verified]: `KeyGestureDetectorImpl` (`LONG_PRESS_MS = 500`, timer fallback for remotes that never auto-repeat OK); `KeyGesture{Press, LongPress, ReleaseAfterRepeat}`; `KeyMap.short/long(context, keyCode)`, `DefaultKeyMap` (`core/.../input/`); root sink `KeyRouter.sinkModifier` / `KeyRouterImpl.onKey`, `BoxKeySink` (`ui/.../input/`). `BoxAction(id, remappable)`: non-remappable are UP/DOWN/LEFT/RIGHT/SELECT/BACK/DIGIT; all others `remappable = true` (`InputContracts.kt`). `KeyContext{GUIDE, PLAYER}`.
- **Activity vs Compose**: the Compose key pipeline (pre-visit root→focused, post-visit focused→root, first `true` wins) is in M3 notes §2.1; keys not consumed fall to the Activity. `MainActivity` handles `keyboard|keyboardHidden|navigation` config changes itself.
- **InputDevice for BT vs IR vs CEC**: nothing verified. [gap] (memory: IR arrives via a vendor input node, BT as an external HID device, CEC through a virtual device.) The decision note wants `InputDevice.name` shown on capture.
- **Persistence today**: `KeyCapabilities.seen` (SharedPreferences `keys_seen`) records codes the device has reported; there is no persisted binding table (`DefaultKeyMap` is code).

---

## 6. Parental PIN storage

- **Primitives in the project** [verified]: Tink `Aead` AES256-GCM via `AndroidKeysetManager` wrapped by an Android Keystore master key (`KeystoreAeadProvider`); `TinkCredentialStore.put/get/delete` stores `AEAD(JSON(PlaylistSecret))` with associated data = ref in table `credential`; failure policy: permanent key loss wipes credentials and sets `needs_reauth`; transient failures retry once then `UNAVAILABLE`; `usingKeystore` is false if Tink fell back to an unencrypted keyset ("Fire OS Keystore flakiness"). `Redact.registerSecret` masks secrets in logs. **No password-hashing code exists.**
- **Lock data in the model**: `channel_prefs.locked: Boolean?`, `group_prefs`, `channel.is_adult`; plan: "4-digit PIN, adult groups auto-locked" (`docs/00-master-plan.md:33`).
- **PBKDF2 availability** [web-summary of the SecretKeyFactory reference and a library issue thread]: `PBKDF2WithHmacSHA1` from API 10; **`PBKDF2WithHmacSHA256` (also 224/384/512) from API 26**. With `minSdk 25`, `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` is expected to throw `NoSuchAlgorithmException` on API 25. [inference] The SHA-1 variant exists on all supported levels. BouncyCastle would be a new dependency. Core-library desugaring does not add JCA algorithms. [inference]
- `PBEKeySpec(char[], salt, iterations, keyLenBits)`, `SecureRandom`, `MessageDigest.isEqual` (constant time) are platform. [memory]
- **Argon2 / scrypt are not in the platform.** [memory; not searched]
- A 4-digit PIN has 10,000 values: any hash is brute-forceable offline if the stored hash and salt are readable; protection comes from the Keystore-wrapped Tink AEAD around it and from lock-out counters. [inference]
- No lock-out counter, "forgot PIN" or session-unlock code exists.

---

## 7. Backup / restore file I/O on TV

- **SAF intents** (`api-versions.xml`): `Intent.ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT` since API 19; `ACTION_OPEN_DOCUMENT_TREE` since 21. `androidx.activity` **1.13.0** is pinned (`rememberLauncherForActivityResult`, `ActivityResultContracts.CreateDocument/OpenDocument`). [memory for contract names]
- **Documented SAF gaps on TV**:
  - CommonsWare 2017: on Android TV the Storage Access Framework is a mixed bag (Shield TV has it, Xiaomi Mi Box does not); on devices without it the intent can still resolve to an activity that only shows "no app can do this", so `resolveActivity()` does not reveal the gap. [web-summary: commonsware.com/blog/2017/12/27/storage-access-framework-missing-action.html]
  - WireGuard mailing list, **January 2026**: on a **Fire OS 8 (Android 11 based)** device, `OpenDocument` produced "Please install a file management utility to browse files"; the app had moved from `GetContent` to `OpenDocument`. [web-summary: lists.zx2c4.com/pipermail/wireguard/2026-January/009486.html, 009487.html]
  - PPSSPP docs: many Android TV / Google TV devices ship without DocumentsUI; remedy: a third-party file manager (AnExplorer, X-plore). A Dolphin forum post claims a Skyworth/Tencent Android 12 build removed SAF. [web-summary]
  - Not found: a first-party statement for Fire OS or Google TV; whether Chromecast with Google TV or Onn ships DocumentsUI. [gap]
- **App-specific storage** (stubs): `Context.getExternalFilesDir(type)` (API 8), `getExternalFilesDirs` (19), `getNoBackupFilesDir()`. Android 11+ blocks `ACTION_OPEN_DOCUMENT` from picking files inside `Android/data` and `Android/obb`. [web-summary]
- **`MediaStore.Downloads`**: class since API 29. [verified] Own-entry writes need no permission on 29+ [memory]; visibility on a TV with no file manager is not established. Below 29 the path is legacy external storage. [memory]
- **Sharing via the LAN page**: the backup JSON can be served for download by the same embedded server (§3), at the cost of needing a phone on the same Wi-Fi without AP isolation. [inference]
- **Serialization in the project**: `kotlinx-serialization-json` **1.11.0**; `Converters.json = Json { ignoreUnknownKeys = true; …; explicitNulls = false }` (`data/.../db/Converters.kt:20-23`); contract types are `@Serializable`. No new dependency is needed.
- **Export constraints from repo facts**: `channel_prefs.playback_flags` and `fps_milli` are "learned, not a user choice (M5 export leaves it out)". Credentials are Tink ciphertext tied to this device's Keystore key (`credential` table) and are not portable; the plan says "credentials under passphrase" (`docs/00-master-plan.md:37`). User data is keyed `(playlist_id, stable_key)` where `playlist_id` is a local autoincrement `PlaylistEntity.id`; a remapper exists, `data/.../repo/UserDataRemapper.kt` (not read).
- **Weekly auto-backup via WorkManager**: `RefreshScheduler` already uses `PeriodicWorkRequestBuilder`, `Constraints` (`NetworkType.CONNECTED`, `setRequiresStorageNotLow(true)`) and `enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)`. WorkManager 2.12.0 clamps a periodic interval below `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` (`WorkSpec.kt:204-213`; the value, 15 min, is [memory]). Behaviour of weekly periodic work under Fire OS / TV standby: not verified. [gap]

---

## 8. In-app updater (sideload)

### 8.1 Platform APIs (verified in stubs / `api-versions.xml`)

| API | Since |
|---|---|
| `PackageManager.canRequestPackageInstalls()` | 26 |
| `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` | 26 |
| `Intent.ACTION_INSTALL_PACKAGE` | 14, **deprecated 29** |
| `PackageInstaller.SessionParams.setRequireUserAction(int)`; `USER_ACTION_{UNSPECIFIED,REQUIRED,NOT_REQUIRED}` | **31** |
| `SessionParams.setRequestUpdateOwnership(boolean)` | **34** |
| `SessionParams.setPackageSource(int)` | 33 |
| `PackageManager.getPackageArchiveInfo(String, int)` | deprecated 33; `(String, PackageInfoFlags)` since 33 |
| `PackageManager.GET_SIGNING_CERTIFICATES`, `PackageInfo.signingInfo`, `SigningInfo` (`getApkContentsSigners()`, `getSigningCertificateHistory()`, `hasMultipleSigners()`, `hasPastSigningCertificates()`) | **28** |
| `PackageManager.GET_SIGNATURES` (64), `PackageInfo.signatures` | legacy (API 25-27 path) |

- **When the install confirmation is skipped**: no source read. Memory/summaries say `USER_ACTION_NOT_REQUIRED` is honoured only in restricted conditions (update of an app the caller installed, update ownership, privileged permission). [memory] For a sideloaded app updating itself the system prompt is expected. [inference]
- **`REQUEST_INSTALL_PACKAGES`** must be declared (targetSdk ≥ 26) to use `PackageInstaller` / `ACTION_INSTALL_PACKAGE`; the user then enables "Install unknown apps" for the app (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`). [web-summary of the Play policy page]
- **Fire OS**: Fire OS 7/8 use a per-app "Install unknown apps" list; Fire OS 5/6 a single "Apps from Unknown Sources" switch; Fire OS 7.7.1.6 and 8.1.8.2 had a bug where apps (Downloader reported) were missing from the per-app list. [web-summary: aftvnews.com] Fire OS ↔ API mapping (Fire OS 6 = API 25, Fire OS 7 = API 28; Fire OS 5 = API 22, below minSdk) is [memory]; "Fire OS 8 = Android 11" is in the WireGuard thread. The Android TV / Google TV equivalent screen is not documented in anything fetched. [gap]
- **Verifying the APK before install**: `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` → `signingInfo` (API 28+); API 25-27 uses `GET_SIGNATURES` → `signatures`. Running app: `getPackageInfo(packageName, same flags)`. Compare SHA-256 of the certificate bytes (`Signature.toByteArray()`); use `getSigningCertificateHistory()` for rotation (28+). Whether `getPackageArchiveInfo(GET_SIGNATURES)` populates `signatures` on API 25-27 without extra steps: not verified. [gap]
- **APK signed by a different key**: the platform rejects the update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, reported to a session as a `STATUS_FAILURE_*` code). [memory]
- **Download**: `@DownloadClient` OkHttp (connect 10 s, read 120 s; `HttpModule.kt`); `XtreamClient.downloadOnce` shows the repo's `dest.part` + atomic-rename pattern. `DownloadManager.enqueue` is platform; its behaviour on TV is not researched.
- **Distribution plan** (`docs/00-master-plan.md:15,40`): GitHub Releases + Downloader code + in-app updater with signed manifest + SHA-256 + cert check.

### 8.2 Google developer verification (state at 2026-10-16)

Sources: `developer.android.com/developer-verification` and `/developer-verification/guides/faq` [web]; search summaries (Android Authority, 9to5Google, GSMArena, aftvnews, Cord Cutters) [web-summary].

- **Timeline [web]**: August 2026 — developer APIs, limited-distribution accounts and the user **advanced flow** launch. **30 Sep 2026** — user protections for apps installed from *participating stores* in **Brazil, Indonesia, Singapore, Thailand**, on certified devices running Android 7+. **2027+** — expansion to all apps on certified devices globally.
- **Scope [web, FAQ]**: certified Android devices running Android 7 or higher. For "Android TV, Google TV, Wear OS": enforcement starting 30 Sep 2026 "applies only to **mobile and tablet** form factors in selected regions"; Play-distributed apps must register all form factors; for non-Play distribution, registering all form factors is "recommended for future availability". **Apps from other stores or sideloaded directly "aren't subject to [the Sep 2026 deadline] yet."** The FAQ does not mention Fire OS or non-certified devices. A Tom's Guide summary says uncertified devices (modified OS images) would not be subject. [web-summary] Older press (Cord Cutters, Nov 2025) says Google TV could be affected. [web-summary]
- **Registration [web, FAQ]**: Full Distribution account in the Android Developer Console, **$25** (waived for **Limited Distribution**, up to **20 devices**, no ID or fee [web, overview]). Registration is tied to **signing keys**; multiple keys per package; a lost key means the package cannot be registered. Apps distributed only outside Play register through the Android Developer Console.
- **Effect once enforced [web, FAQ]**: "unregistered apps can only be installed or updated when the **advanced flow** is enabled or via **ADB**"; if the advanced flow is off, updates to unregistered apps fail; registered apps keep the normal experience. ADB installs are exempt; the 24-hour wait does not apply to ADB; no ADB command bypasses it.
- **Advanced flow [web, FAQ]**: enable Developer Mode; confirm you are not being coached; restart and reauthenticate; **wait 24 hours**; confirm with biometrics or device PIN; install unverified apps for 7 days or indefinitely (warning still shown).
- The FAQ "doesn't address self-updating apps directly" (fetch tool's reading). Whether the advanced flow exists on Android TV / Google TV, and how a TV completes it, was not found. [gap]
- Press (2025-11) reports Google eased the plan after feedback (student/hobbyist tier, advanced flow). Sources differ on the advanced flow's exact launch month ("August 2026 most common"). [web-summary]

### 8.3 Play policy on self-updating apps

- Device and Network Abuse policy: an app distributed through Google Play may only update through Play's mechanism and may not download executable code (dex, JAR, .so) from outside Play; code interpreted in a WebView is allowed. `REQUEST_INSTALL_PACKAGES` is a restricted permission requiring a Play Console declaration and a core-purpose justification. Enforcement examples reported: Total Commander, Zoom SDK, DCloud SDK. [web-summary of `support.google.com/googleplay/android-developer/answer/16559646` and related; policy pages not opened]
- Consequence: the updater is for the sideload build only; a Play build would have to omit it. [inference]

---

## 9. Compose TV for the settings UI

- **tv-material 1.1.0** inventory and default sizes are in M3 notes §3.1-§3.2. Additions verified from the sources jar (`androidx/tv/material3/`):
  - **`ListItem`** (M3 notes said "check exact arg order"): `ListItem(selected: Boolean, onClick: () -> Unit, headlineContent: @Composable () -> Unit, modifier, enabled = true, onLongClick: (() -> Unit)? = null, overlineContent, supportingContent, leadingContent: (@Composable BoxScope.() -> Unit)?, trailingContent, tonalElevation, shape, colors, scale, border, glow, interactionSource)` — `ListItem.kt:78-95`; `DenseListItem` at :170 has the same parameters. `selected` is required (selectable Surface).
  - **`Switch(checked, onCheckedChange: ((Boolean) -> Unit)?, modifier, thumbContent, enabled, colors, interactionSource)`** — `Switch.kt:90`. `SwitchTokens`: track **40 × 24 dp**, handle 18 dp selected / 12 dp unselected, icon 12 dp (`tokens/SwitchTokens.kt:44-72`). Fixed dp.
  - **`RadioButton(selected, onClick: (() -> Unit)?, modifier, enabled, colors, interactionSource)`** — `RadioButton.kt:69`; icon **20 dp**, padding 2 dp, dot 12 dp, stroke 2 dp.
  - **`Checkbox`** — `Checkbox.kt:85`; 20 dp, padding 2 dp, stroke 2 dp, corner 2 dp.
  - **No `Slider`, no `Dialog`/`AlertDialog`, no text field, no progress indicator** in tv-material 1.1.0 (file listing of the sources jar; `grep Slider` over the tree empty).
  - Under `BoxDesignScale` 1.dp = 1 design px, so a Switch is 40×24 design px and a RadioButton 20; both are small for the 1080p canvas. [inference]
- **Focus restoration** (ui 1.12.1 sources): `Modifier.focusRestorer(fallback: FocusRequester = Default)` in `androidx.compose.ui.focus` (`FocusRestorer.kt:109`); the lambda overload is `@Deprecated`. It remembers the previously focused child when focus leaves the group and restores it on re-entry; falls back to `fallback`. The saved child is keyed by the layout node's composite key hash through `LocalSaveableStateRegistry`; the saving path differs when `ComposeUiFlags.isFocusRestorationEnabled` (`FocusRestorer.kt:23-90`).
- **Scroll-to-focus in lazy lists**: `LocalBringIntoViewSpec` / `BringIntoViewSpec` (foundation 1.12.1, `gestures/BringIntoViewSpec.kt`): `calculateScrollDistance(offset, size, containerSize)` is called every frame of the scroll animation; `scrollAnimationSpec` is **deprecated** ("Animation spec customization is no longer supported"). tv-material has `BringIntoViewIfChildrenAreFocused.kt`. `rememberLazyListState` is in foundation.
- **`Dialog`/`Popup` and design scale**: Popup/Dialog windows re-provide `LocalDensity` from their own window; content inside must be wrapped in `BoxDesignScale` again (it reuses the root density from `LocalBoxDesignDensity`) — KDoc of `ui/.../theme/BoxDesignScale.kt`. `BoxDesignScale` also pins `fontScale = 1`. There is no `Dialog(` / `Popup(` call in `ui`/`app` main code today (grep).
- **Existing placeholder**: `SettingsPlaceholder.kt` (`ui/.../root/`), tag `settings_placeholder`, shown for Mode SETTINGS, opaque, covers the hole; Back is handled by the state machine through the root key sink.
- **Keys**: `onPreviewKeyEvent` sees keys root→focused before children (M3 notes §2.1); the root key sink uses it. Two-pane hand-off tools: `Modifier.focusProperties { left/right = requester }`, `FocusRequester.requestFocus()` (returns false and warns if the modifier is not attached yet), `focusRestorer` (M3 notes §2.1, §2.4).
- **tv-material focus visuals** (scale 1.1, 2 dp border, `graphicsLayer` clip): M3 notes §2.5. Never an ancestor of the video hole (CLAUDE.md).

---

## 10. Network settings

- **OkHttp 5.5.0**. Shared base client in `HttpModule`: connect 10 s, `followRedirects`/`followSslRedirects` true, interceptor `defaultUserAgent` (adds `TheBox/1.0 (Android TV)` when the request has no `User-Agent`); `@ApiClient` read 60 s, `@DownloadClient` read 120 s. KDoc: "DoH hook point: M5 sets `.dns(DnsOverHttps…)` on the base client from a user setting." `okhttp-dnsoverhttps:5.5.0` is already a `:data` dependency.
- **Per-playlist user agent**: `playlist.user_agent` → `PlaylistPlaybackInfo.userAgent`; set per request ("playlist UAs are set per request", `HttpModule` KDoc); `playlist.extra_headers_json` (non-secret) and `PlaylistSecret.headers` (secret). A one-shot UA fallback exists (`ResolveInput.userAgentOverride`, ladder step L5). The fake panel supports `ua_whitelist`.
- **Proxy / DNS**: `OkHttpClient.Builder.proxy(Proxy)`, `proxySelector`, `proxyAuthenticator`, `dns(Dns)` are OkHttp API. [memory] Media3 uses OkHttp through `media3-datasource-okhttp` and the player builds its own clients (`player/.../datasource/PlaybackHttpClients.kt`, `BrokeredCallFactory.kt`; not read), so a proxy or DNS override must reach both the `:data` clients and the `:player` clients. [inference]
- **Ignore TLS errors**: `playlist.ignore_tls_errors` and `PlaylistPlaybackInfo.ignoreTlsErrors` exist; the trust-all implementation was not read.
- **TLS 1.2 on API 25**: platform TLS 1.2 is enabled by default from API 20. [memory] The app trusts system and user CAs (NSC).
- **Connection test as implemented**: `XtreamClient.login(conn)` does a **GET** of `playerApi(null)` = `{base}/player_api.php?username=…&password=…` (no `action`); body read capped (`MAX_SMALL_BODY`), decoded by `decoder.account(...)`. Auth failure = HTTP 401/513, HTTP 200 with `auth=0`, `[]` or an empty body → `SourceError.Auth`; HTML on login → `Auth(UNKNOWN)`; base rotation on IO failures and 5xx/520-524; 429 back-off 2/4/8 s (`Retry-After` up to 30 s); "Content-Type is never trusted"; per-playlist concurrency capped at `MAX_CONCURRENT` (`XtreamClient.kt:51-61`). No HEAD probe exists; the fake panel routes `HEAD` like `GET` (`do_HEAD = do_POST = do_PUT = do_GET`, `fakepanel.py:834`). Real-panel HEAD behaviour: unknown. [gap]
- **Error taxonomy for "reason + next step"** (`core/.../common/Outcome.kt`): `SourceError{Network(kind: DNS|CONNECT|TIMEOUT|TLS|RESET|OTHER, detail), Http(status), Auth(reason: BAD_CREDENTIALS|EXPIRED|BANNED|DISABLED|UNKNOWN), NotASource(kind: HTML_PAGE|HLS_MANIFEST|EMPTY|BINARY|WRONG_FORMAT), SuspiciousShrink, Parse, DiskSpace, Credentials(reason: MISSING|KEY_INVALIDATED|CORRUPT|UNAVAILABLE), Unsupported, Cancelled, Internal}`, each with a stable `code` ("net.dns", "http.403", "auth.expired", "format.html_page", "cred.key_invalidated", …). KDoc: "The UI (M5) maps each case to 'reason + next step'." `ui/.../playback/ErrorText.kt` exists for playback errors (content not read).

---

## 11. Logs

- **`BoxLog`** (`core/.../common/BoxLog.kt`): `object BoxLog { fun interface Sink { log(level, tag, message, error) }; DEBUG=3 INFO=4 WARN=5 ERROR=6; @Volatile sink (default no-op); @Volatile minLevel = INFO; d/i (lambda), w, e; emit() = sink.log(level, tag, Redact.text(msg), t) }`. KDoc: ":app installs an android.util.Log sink (M5 adds the ring buffer)." Only `msg` is redacted; the `Throwable` is passed on **unredacted**. [verified]
- **`Redact`** (`core/.../common/Redact.kt`): registered secrets (LRU 1,024 credentials, 64 transient, minimum length 3, longest first) → `***`; then regexes for userinfo (`scheme://***@`), Xtream paths (`/live|movie|series|timeshift/***/***/`), any URL query string (`?***`), and `authorization|cookie|x-token|token` headers; `Redact.host(url)`. `Media3LogBridge` exists in `player/.../engine/`.
- **Own-process logcat**: an app can read its own process's log without `READ_LOGS` since API 16, and `logcat --pid` exists on API 24+ — both [memory], neither verified from a source. A captured text would need `Redact.text` before writing. [inference] A `BoxLog` ring buffer does not see Media3 or system messages unless routed (`Media3LogBridge`). [inference]
- **Registration gap**: secrets are registered with `Redact` when stored or decrypted (`TinkCredentialStore.register`); a password typed in onboarding and not yet stored is not registered. [inference from call sites]
- Sharing a log: LAN page (§3) or SAF / Downloads (§7). ACRA (`acra-core` 5.14.2) is in `:app`; its configuration was not read. [gap]

---

## 12. Xtream URL conversion and `server_info`

- **Existing converter**: `XtreamLoginUrl.parse(url): XtreamLoginInput?` (`core/.../xtream/XtreamLoginUrl.kt`). Accepts `get.php`, `player_api.php`, `panel_api.php`, `xmltv.php`, `enigma2.php` URLs with `username`/`password` (regex `^(https?://host)(/path)?/(get|player_api|panel_api|xmltv|enigma2).php(?query)?$`, case-insensitive) and long-form stream URLs (`/live|movie|series/u/p/id.ext`, via `XtreamStreamUrl.parse(s, allowShort = false)`). A scheme-less string gets `http://`. Query values are percent-decoded once; `+` stays literal. `output` is carried only for `get.php` (and inferred from `.m3u8`/`.ts` for stream URLs). Returns `XtreamLoginInput(base, user, pass, output)`; base = `XtreamUrls.normalizeBase` (`scheme://host[:port]` only; any path dropped).
- **URL builders** (`XtreamUrls(base, user, pass)`): `playerApi(action?, params)` = `{base}/player_api.php?username=&password=[&action=…]`; `getPhp(type = "m3u_plus", output = "ts")` = `{base}/get.php?username=&password=&type=&output=`; `xmltv()` = `{base}/xmltv.php?username=&password=`; `live/movie/episode(id, ext)` = `{base}/live/{u}/{p}/{id}.{ext}`; `timeshiftPath`, `timeshiftPhp`. Query values encode everything outside the unreserved set; path segments encode outside RFC 3986 `pchar`.
- Deriving `player_api.php` from a pasted `get.php` URL therefore exists already as `XtreamLoginUrl.parse` + `XtreamUrls.playerApi(null)`.
- **`server_info` fields parsed** (`XtreamDecoders.kt:62-72` → `XtreamServerInfo`): `url`, `port`, `https_port`, `server_protocol`, `timezone` (raw, may be non-IANA like "UTC+3"), `timestamp_now`, `time_now` ("YYYY-MM-DD HH:MM:SS", panel-local), `xui` (bool), `version`.
- **Account card** (`XtreamAccountInfo`, stored as `xtream_account.info_json`, no credentials): `auth`, `status: AccountStatus{ACTIVE,BANNED,DISABLED,EXPIRED,UNKNOWN}`, `statusRaw`, `expiresAtSec` (null = unlimited: `exp_date` null/"0"/""), `isTrial`, `activeConnections`, `maxConnections`, `allowedOutputFormats`, `createdAtSec`, `message`, `server`, `measuredAtDeviceMs`. `PlaylistRepository.account(playlistId): Flow<XtreamAccountInfo?>`, `saveAccount`, `saveServerTime`.
- **Playlist add API** (`PlaylistRepository`): `addM3uUrl(name, url, userAgent, secretHeaders)`, `addM3uFile(name, uri, displayName)`, `addXtream(name, baseUrls: List<String>, username, password, userAgent)` → `Outcome<Long>`; `update`, `setEnabled`, `delete`, `secret(...)`, `replaceSecret(playlistId, secret)`, `observePlaylists()`. `PlaylistRefresher.refresh(playlistId, trigger, forceAcceptShrink, progress)` → `RefreshResult`. Onboarding refresh is `REPLACE`, expedited (`RefreshScheduler` KDoc).
- `PlaylistEntity.displaySource` is the redacted host string for the UI ("panel.example.com:8080").

---

## 13. Fake panel capabilities for M5 tests

Sources: `tools/fakepanel/fakepanel.py`, `catalog.py`, `README.md`, `config.example.json` (read: `auth_fail`, `creds_ok`, `player_api`, `get_php`, `xmltv_php`, `catalog.user_info`, config key list, README faults table).

- **Endpoints**: `player_api.php` (login and all `get_*` actions), `get.php` (M3U), `xmltv.php`, live/movie/series streams, timeshift forms, `/admin/*`. Default credentials `test`/`test` (config `username`, `password`). HTTP only, HTTP/1.0 close-delimited, no keep-alive (README Limitations).
- **Auth modes** (`auth_mode`, forced on `player_api`, `get.php`, `xmltv.php` and streams): `ok | auth0 | empty | array | 401 | 403 | 513`. `auth_fail`: `auth0` → `{"user_info":{"auth":0}}`; `empty` → 200 empty `text/html`; `array` → `[]`; numeric → that status. **Wrong credentials**: `bad_creds_mode` (default `auth0`, same value set); on `get.php`/`xmltv.php` a wrong password with `auth0` becomes **403**. So a wrong-password mode exists.
- **Account status**: `status` = `Active | Expired | Banned | Disabled` (reflected in `user_info.status`; non-Active makes streams 403 `account_<status>`); `exp_days` (default 30; `null` → `exp_date: null`), `max_connections` (default 2), `active_cons_override`, `allowed_output_formats`, `quirks` (numbers as strings, `exp_date` string).
- **`user_info` emitted** (`catalog.user_info`): `username, password, message "Fake panel", auth 1, status, exp_date, is_trial, active_cons, created_at (now − 90 d), max_connections, allowed_output_formats`. **`server_info` emitted**: `url, port, https_port "25463", server_protocol "http", rtmp_port "25462", timezone, timestamp_now, time_now`. **No `xui`, no `version`** in the fake `server_info`.
- **458** appears only as `linger_status` (rejected stream open) and `timeshift_range_rejects`; it is not an API-level response.
- **Stream faults** (README): `redirect`, `linger`/`linger_status`, `close_every`, `stall`, `stall_after`, `slow`, `ua_whitelist`, `wrong_content_type`, `html_splash`, `405_on_m3u8`, `range_rejects`, `token_kill`, timeshift forms. Admin: `POST /admin/config`, `GET /admin/stats`, `/admin/reset`, `/admin/kick`, `/admin/now`.
- **Missing for M5 onboarding/diagnostics tests**: no slow or delayed response for `player_api.php`, `get.php` or `xmltv.php` (`stall`/`slow` are stream faults; grep for API delays found none); no timeout mode for the API; no redirect on API paths; no HTTPS; no `xui`/`version` in `server_info`; no 200-with-HTML splash on `player_api.php` (`html_splash` is a stream fault; `auth_fail("empty")` returns an empty `text/html` body, not a page); no HEAD-specific behaviour; no 429 / `Retry-After` mode on the API; no Xtream-vs-plain-M3U host distinction (a `player_api.php` probe on a non-Xtream host cannot be simulated). The README says: "HTTP only (no TLS)". [verified by reading the handlers and config keys; not every code path read]
- Run: `python3 -m unittest tools/fakepanel/test_fakepanel.py`; instrumented suites via `tools/fakepanel/run-instrumented.sh`.

---

## 14. Other repo facts the design touches

- `docs/decisions/2026-10-14-settings-and-branding.md`: owner-requested M5 items (tunneled playback, stream-format label, "Always passthrough", channel-name prefix rule, hide/show, **Button mapping screen** pulled forward to v1: "Press a key now" capture showing key name and `InputDevice.name`; reserved: D-pad/OK keep navigation outside the player, Back = close top layer, Home OS-owned; conflicts shown inline, later binding wins; reset per key and globally; per-device profiles stay v1.1).
- `docs/00-master-plan.md:121-125`: M5 = "Onboarding + phone page/QR, settings tree (incl. Always passthrough, stream format label, prefix rule, button-mapping screen), PIN, backup/restore, diagnostics, updater".
- `StartupGate` and `WallClock` live in `data/.../di/`; qualifiers `@ApiClient`, `@DownloadClient`, `@IoDispatcher`. Toasts: `ui/.../root/Toasts.kt`.

---

## 15. Gaps (could not verify), numbered

1. Which pinned library pulls DataStore 1.1.1/1.1.7 into the cache, and whether adding DataStore to `:data`/`:app` resolves cleanly with the toolchain. Latest stable on Google Maven is 1.2.1 (1.3.0-rc01 exists); licence assumed Apache 2.0.
2. Fire OS keyboard behaviour with Compose text fields (focus hand-off, D-pad Down/Back with the IME open, password masking); Gboard-TV password behaviour; any official Compose-on-TV TextField guidance. Only the repo's G5 note and Gboard-TV input types are verified.
3. Whether TiviMate or other TV apps ship a custom on-screen keyboard: nothing found.
4. Ktor 3.6.0 CIO minSdk and added APK size; NanoHTTPD licence text (BSD-3 from memory; the 2.3.1 pom fetched does not declare it); ZXing 3.5.4 bytecode target.
5. mDNS on Fire OS / Google TV: NsdManager availability, multicast-lock need per device, whether a phone browser resolves `*.local`.
6. AP/client-isolation detection: "impossible" rests on absence in stubs plus general knowledge; no authoritative statement.
7. HTTPS on the LAN: only anecdotal sources (vendor docs, forum posts); nothing on Chrome/Android warnings or on secure-context features lost over HTTP.
8. Remote key codes for Google TV/Chromecast remote, Onn, Shield, HDMI-CEC and generic HID remotes; `InputDevice` name/sources/`isExternal()` for BT vs IR vs CEC (and `isExternal()`'s API level); whether `FLAG_LONG_PRESS` is set on the first repeat and the first-repeat timeout.
9. Which keys are intercepted on Google TV (Assistant, YouTube/Netflix buttons), Shield and Onn; only Fire TV is sourced.
10. `PBKDF2WithHmacSHA256` API 26 floor is from a search summary, not the Android reference table; the API 25 exception type is inferred.
11. SAF on Android TV / Google TV / Fire OS: only third-party reports; no first-party device list; whether `ACTION_GET_CONTENT` works where `OPEN_DOCUMENT` fails is only suggested; `MediaStore.Downloads` permissions and file-manager presence on TV unverified.
12. `PackageInstaller` confirmation-skip rules (`USER_ACTION_NOT_REQUIRED`, update ownership) from no source; install-time behaviour on Android TV and Fire OS; `ACTION_MANAGE_UNKNOWN_APP_SOURCES` on Fire OS 6/7/8; `getPackageArchiveInfo(GET_SIGNATURES)` on API 25-27.
13. Developer verification: advanced flow on Android TV / Google TV and how it works without biometrics; Fire OS (non-certified) status (FAQ silent; one press summary says uncertified devices are not subject); when enforcement reaches non-store sideloading (2027 global phase); exact advanced-flow launch month. Play policy pages were not opened (summaries only).
14. Own-process logcat (`READ_LOGS` not needed; `logcat --pid` on API 24+): memory only; Fire OS behaviour unknown. ACRA configuration not read.
15. WorkManager minimum periodic interval (15 min) from memory; weekly periodic work under Fire OS / TV standby unknown.
16. TLS 1.2 default on API 25; the `ignore_tls_errors` implementation; proxy/DoH wiring into the player's OkHttp clients (`PlaybackHttpClients`, `BrokeredCallFactory`) not read.
17. `PlaybackSettings` and `GuideSettings` field lists; `UserDataRemapper` and `PlaylistRepository.add*` internals not read.
18. Real-panel behaviour: `HEAD` on `player_api.php`, panels answering HTML on `player_api.php`, `get.php` URLs with extra parameters.
19. QR scan-ability at couch distance; ZXing API names are from memory (no jar in cache).
20. Fire OS ↔ API level mapping (Fire OS 6 = API 25, 7 = API 28; 8 = API 30) from memory except "Fire OS 8 = Android 11".
