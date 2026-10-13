# M0 review

Reviewer pass over the M0 skeleton (Gradle, modules, theme, core models, CI, FFmpeg script, fake panel), 2026-10-13.
Line numbers refer to the files **before** the fixes below unless marked "now". Build after fixes:
`./gradlew -q :core:test :app:assembleDebug` green (6/6 core tests); fake panel suite 21/21.

Overall: the skeleton is solid. The toolchain pins, convention plugins, desugaring, ABI splits, manifest
(leanback + launcher, banner, PiP, cleartext, FGS type) and the FFmpeg pipeline are right. No blockers.
The defects that matter are in the TimeBase semantics, the density helper's behaviour outside a plain
16:9 root, and four fake-panel bugs that would make catch-up and HLS tests give wrong results.

## Blocker

None.

## Should fix now (all applied)

| # | Where | Problem | Fix applied |
|---|---|---|---|
| S1 | `core/.../model/TimeBase.kt:12` | `clockSkew` was documented as "server clock minus device clock (Xtream `time_now − timestamp_now`)". `time_now − timestamp_now` is the panel's **UTC offset** (the protocol-reference fallback for a missing or non-IANA `timezone`), not the clock skew. Implemented as written, M1 would compute a skew of ±hours, and with `serverZone == null` there was no field for the derived offset. Nothing encoded "correction is subtracted" or "token zone defaults to the panel zone" as code either. | Added `serverOffset: ZoneOffset?`. `clockSkew` is now documented as `timestamp_now − device now`. Added `effectiveTokenZone` (tokenZone → serverZone → serverOffset → null, never `systemDefault()`), `archiveStart/archiveStop` (programme UTC − correction ∓ padding, with per-channel override) and `offsetFromServerInfo()` (15-min snap). Three new tests pin the rules (`-4.0` gives 4 h later, zone fallback, offset snapping). |
| S2 | `ui/.../theme/BoxDesignScale.kt:27-41` | (a) Popup and Dialog windows re-provide `LocalDensity` from their own `AndroidComposeView`, so tv-material dialogs and menus fall back to system dp (half size on a 1080p TV). Re-wrapping them in `BoxDesignScale` would measure the **popup's** container and get the wrong scale. (b) On a non-16:9 window (phones, which the manifest allows, and ultrawide), width ÷ 1920 makes the 1080-px canvas taller than the screen, so the guide and footer are clipped. (c) A nested call would read the already-overridden `LocalDensity` in its fallback path. | The root design density now lives in a private `LocalBoxDesignDensity`. Nested or popup `BoxDesignScale` calls reuse it. The scale is `min(w/1920, h/1080)`, which equals `w/1920` on every 16:9 TV, so the plan rule holds on target hardware. **Lead:** record the fit-inside refinement and the "wrap Popup/Dialog content in BoxDesignScale" rule in `docs/01-design-reference.md`. |
| S3 | `app/build.gradle.kts:49-53` | The custom `benchmark` build type is non-debuggable and not profileable. On API 29+, Macrobenchmark refuses a non-profileable target (`NOT-PROFILEABLE`), so `StartupBenchmark` would fail on Fire OS 8+/Google TV devices. | `isProfileable = true` on the `benchmark` build type. AGP adds `<profileable shell>` to that variant only. |
| S4 | `tools/ffmpeg/build-ffmpeg-aar.sh:189,262-265,101` | The licence guard checked GPL and nonfree but not `version3`. `FFMPEG_LICENSE` = "LGPL version 3 or later" still matched `LGPL*`, while the NOTICE claims "LGPL v2.1+, no --enable-version3". | The guard now also rejects `--enable-version3` in the upstream script and `version3` in the decoder list. It requires `CONFIG_VERSION3 0` and an exact licence of "LGPL version 2.1 or later". Verified against the local `config.h` (it passes). |
| S5 | `tools/fakepanel/fakepanel.py:420` | The M3U gives ch4 `catchup="flussonic"` with `catchup-source=…/live/u/p/4/archive-${start}-${duration}.m3u8`, but no route matched four path segments under `/live/`, so it always returned **404**. A correct Flussonic builder would look broken. | Added a `/live/u/p/{id}/{archive\|index\|video\|mono}-{FROM}-{DUR\|now}.{m3u8\|ts}` route. FROM is unix UTC and is checked against `timeshift_expect`/strict mode. |
| S6 | same, live route | ch3 is `catchup="shift"` (`liveUrl?utc=…&lutc=…`), but the query was ignored and **live** video came back. A SHIFT test would pass while playing live. | `?utc=` on a live path now serves archive (unix UTC start, same checks). |
| S7 | `fakepanel.py:633-646` | HLS segment requests (`{id}_seg{n}.ts`) went through the live `allowed_output_formats` and `Range` checks. With `allowed_output_formats: ["m3u8"]` (a common XUI setup) every segment got a **405**. | Segments skip both checks. |
| S8 | `fakepanel.py:611-615, 785` | When a live `.m3u8` was redirected to the token port, the relative segment URLs had no token, so every segment got a **403** (`token_invalid`). The `redirect` fault therefore broke all HLS. | The LB playlist appends `?token=` to segment URLs. The token is checked against the playlist path and does not consume a single-use count. |
| S9 | `fakepanel.py:800` | `timeshift_playlist` took the stream id from the URL's last path segment, which is wrong for any non-`/timeshift/…/{id}.m3u8` path (it produced `/vodseg/archive-…_0.ts`, a 404). | The stream id is now passed explicitly. |

New tests cover S5 to S8. The README documents the new routes.

## Later

| # | Where | Item |
|---|---|---|
| L1 | `app/build.gradle.kts:22,56-63` | All three APKs share `versionCode 1`. Add per-ABI offsets (universal lowest, arm64 highest) before the updater ships (already listed in m0-toolchain follow-ups). |
| L2 | `data/build.gradle.kts`, `TheBoxApplication.kt` | `hilt-work` is wired, but the Application does not implement `Configuration.Provider` and the manifest does not remove `androidx.work.WorkManagerInitializer` (`tools:node="remove"` on the startup provider). The first `@HiltWorker` (M1 EPG import) will crash with "could not create worker" without both. |
| L3 | `app/.../TheBoxApplication.kt` | ACRA is on the classpath but not initialised. When it is, configure it local-only (no sender, or a dialog/mail sender the user triggers), per "no telemetry by default". |
| L4 | `app/src/main/res/xml/network_security_config.xml:5-8` | `src="user"` in `base-config` lets any user-installed CA intercept credentials app-wide. It was accepted in the plan for panels behind user CAs. Consider making it a per-playlist opt-in (custom `TrustManager`) instead of global. |
| L5 | `app/src/main/res/drawable/banner.xml` | Vector banner and launcher icon are placeholders. Ship PNG `drawable-xhdpi/banner.png` 320×180 (Fire TV and some older leanback launchers render PNG banners more reliably) plus a mipmap launcher icon. |
| L6 | `ui/.../BoxTheme.kt` | tv-material component defaults (Button height, focus scale, Surface paddings) are in dp. Under the design density they render at half size on a 1080p TV (system density 2.0, design density 1.0). Every tv-material component used in M3 needs explicit design-px sizes. |
| L7 | `build-logic/.../KotlinAndroid.kt`, `AndroidComposeConventionPlugin.kt` | Not configured yet: Compose compiler reports/metrics (`composeCompiler { reportsDestination/metricsDestination }` behind a `-Pthebox.composeReports` flag), `freeCompilerArgs` (opt-ins, `-Xjsr305=strict` if needed), `testInstrumentationRunner` on library modules, JUnit 5 / `unitTests.isReturnDefaultValues` for Android-module unit tests, a `lint {}` block (`warningsAsErrors` for the TV checks), ktlint/detekt. |
| L8 | `.github/workflows/ffmpeg.yml:41-109` | Uses older majors (checkout/setup-java/cache/upload/download @v4, setup-android@v3, gh-release@v2) that run on the deprecated Node 20, while `android.yml` uses checkout@v7, setup-java@v6, upload-artifact@v7. Align them when next touched (download-artifact v7+ pairs with upload v7). The Gradle cache key `gradle-media3-$TAG` never refreshes; use `gradle/actions/setup-gradle@v6` there as well. |
| L9 | `.github/workflows/android.yml` | CI builds without the FFmpeg AAR (`ffmpeg/libs/` is gitignored), so the CI APK always has `FfmpegAvailability == false`. Download the latest `ffmpeg-*` release asset into `ffmpeg/libs/` (verify the `.sha256`) before assembling. Also add `:app:lintDebug`. |
| L10 | `core/.../model/CatchupMode.kt:35` | `flussonic-hls` and `flussonic-ts` collapse into one `FLUSSONIC`, which loses the container hint that URL derivation needs. Add a variant field (or two enum values) when the builder lands. |
| L11 | `core/.../model/Channel.kt:32` | `Catchup.isAvailable` requires `days > 0`. Per protocols §4.5, Xtream `tv_archive == 1` with a missing duration should fall back to the playlist default rather than off. Resolve the days in the M1 mapper. |
| L12 | `tools/fakepanel/catalog.py:66-69` | In non-quirks mode `exp_date`/`is_trial` are ints. Real panels send strings, so the "clean" mode is cleaner than any real panel. That is fine as a baseline, but the client tests should run both modes. |
| L13 | `tools/fakepanel/fakepanel.py` `timeshift_check` | Without `timeshift_expect`, a client that sends UTC to a panel east of UTC (e.g. Tokyo) is not flagged: the time reads as N hours earlier, which is still inside the archive window. Catch-up tests should always set `timeshift_expect` (README says so; make the D-experiment fixtures do it). |
| L14 | `app/src/main/AndroidManifest.xml:23` | `android:usesCleartextTraffic="true"` is redundant on API 24+ (the network security config wins). It is harmless, so keep it or drop it, as long as both say the same thing. |

## Checked and fine

- Density: `fontScale = 1f` with `Density(scale, 1f)` gives a linear converter, so `.sp` equals `.dp` equals design px. `MainActivity`'s `16.dp` sits inside `BoxTheme`, so it is design px. Nothing uses system dp for guide geometry (the guide does not exist yet).
- Manifest: LAUNCHER + LEANBACK_LAUNCHER, `android:banner`, leanback/touchscreen/landscape `required=false` (so it installs on phones too), PiP + `resizeableActivity` + the right `configChanges`, `singleTask`, the exported activity, an exported `MediaSessionService` with its intent filter, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `foregroundServiceType="mediaPlayback"`, cleartext via the network security config.
- API 25: desugaring is on in every Android module and `:core`'s `java.time` is desugared when the app is dexed. Static (non-variable) fonts. No API-26+ calls.
- R8: `ffmpeg/consumer-rules.pro` keeps `FfmpegLibrary.isAvailable()` for the reflective lookup. Media3, OkHttp 5, kotlinx-serialization, Room, Tink and ACRA ship consumer rules (the lead's `:app:assembleBenchmark` minified build passed).
- Hilt/KSP: KSP2 2.3.12 plus `hilt-compiler`/`androidx.hilt-compiler`. The Room plugin and `room-compiler` are wired through KSP. `sqlite-bundled` is a dependency of `:data` (driver not wired yet, which is expected at M0).
- ABI splits (armeabi-v7a + arm64-v8a + universal). x86 libs are stripped outside debug.
- Convention plugins: a single `configureKotlinAndroid` (no duplication), Java/JVM target 17 everywhere, JUnit Platform on `:core`.
- StableKey priority and prefixes match plan §3. CatchupMode covers xc/default/append/shift(+timeshift)/flussonic(+fs)/vod/off.
- android.yml: the action majors exist (checked with `git ls-remote`), and the sdkmanager path is valid on ubuntu-latest.
- Fake panel vs protocols §3: `user_info`/`server_info` fields, string `max_connections`/`active_cons`, `allowed_output_formats`, base64 `title`/`description`, `start`/`end` in the panel zone plus UTC `*_timestamp`, the timeshift path `/{min}/{Y-m-d:H-M}/{id}.ts` and `timeshift.php`, and the server-zone check.
