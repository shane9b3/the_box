# M0 toolchain

Status: project skeleton builds green on macOS x86_64, 2026-10-13. All versions live in
`gradle/libs.versions.toml`; every one was checked against Google Maven / Maven Central metadata
on 2026-10-13.

## Pinned versions

| Component | Version | Note |
|---|---|---|
| Gradle (wrapper) | **9.8.1** | AGP 9.4 needs ≥ 9.6.0; 9.8.1 is the current stable. Wrapper has `distributionSha256Sum`. |
| Android Gradle Plugin | **9.4.1** | as planned; uses AGP 9 **built-in Kotlin** (no `org.jetbrains.kotlin.android` plugin) |
| Kotlin (KGP, compose compiler, serialization) | **2.4.21** | as planned; root `build.gradle.kts` puts KGP 2.4.21 on the classpath, overriding the 2.2.10 AGP depends on |
| KSP | **2.3.12** | KSP2, versioned independently of Kotlin since 2.3.0; 2.3.10+ has the AGP 9 built-in-Kotlin and Kotlin 2.4 fixes |
| JDK (build) | **21** | AGP 9.4 minimum is 17, but `:data` unit tests use Robolectric SDK 35 which needs JDK 21 (M1 design review). Bytecode target is 17 in all modules |
| compileSdk / minSdk / targetSdk | **37** / 25 / 35 | compileSdk changed from 36 (see substitutions) |
| SDK Build Tools | 36.0.0 | AGP 9.4 default |
| Compose BOM | **2026.09.00** | as planned (Compose 1.12) |
| androidx.tv:tv-material | **1.1.0** | as planned |
| Media3 (exoplayer, hls, datasource-okhttp, session, ui-compose) | **1.11.1** | as planned |
| Room (runtime, ktx, compiler, gradle plugin) | **2.8.5** | as planned |
| androidx.sqlite (sqlite-bundled, sqlite-ktx) | **2.6.2** | matches what Room 2.8.5 is built against (2.7.1 exists; not taken) |
| WorkManager | **2.12.0** | as planned |
| Hilt / Dagger | **2.60.1** (KSP) | as planned; androidx.hilt (hilt-work) 1.4.0 |
| OkHttp (+ dnsoverhttps) | **5.5.0** | as planned |
| Coil 3 (coil-compose, coil-network-okhttp; coil-svg in :app for the score bug, pulls androidsvg-aar 1.4) | 3.6.3 | latest stable |
| kotlinx-coroutines | 1.11.0 | latest stable |
| kotlinx-serialization-json | 1.11.0 | latest stable |
| Tink (tink-android) | 1.23.0 | latest stable |
| ACRA (acra-core) | 5.14.2 | latest stable |
| core-library desugaring (desugar_jdk_libs) | 2.1.5 | |
| androidx core-ktx / activity-compose / lifecycle | 1.19.1 / 1.13.0 / 2.10.0 | |
| androidx.benchmark (macro-junit4) / baselineprofile plugin | 1.5.0 / 1.5.0 | |
| profileinstaller | 1.4.1 | |
| androidx.test ext-junit / uiautomator | 1.3.0 / 2.4.0 | |
| JUnit (BOM) + kotlin-test-junit5 | 6.1.3 + 2.4.21 | `:core` unit tests on the JUnit Platform |

## Substitutions and deviations from the plan

1. **compileSdk 37, not 36.** Compose BOM 2026.09.00 (Compose 1.12), `androidx.core` 1.19.1 and
   ACRA 5.14.2 declare `minCompileSdk = 37` in their AAR metadata; with 36 the build fails at
   `checkDebugAarMetadata`. AGP 9.4 supports up to API 37. Installed `platforms;android-37.0`
   locally (sdkmanager) and CI installs it explicitly. targetSdk stays 35, minSdk 25.
2. **Gradle 9.8.1** rather than AGP 9.4's default 9.6.0 (newer patch line of the same major).
3. **Fonts are upstream static TTFs, not the Google Fonts repo files.** `google/fonts` only has
   *variable* Instrument Sans / JetBrains Mono, and font variation axes need API 26 (minSdk is
   25). The static 400/500/600/700 and 400/500 instances come from the upstream repos Google
   Fonts builds from (`Instrument/instrument-sans`, `JetBrains/JetBrainsMono`); the OFL texts are
   from `google/fonts`. OFL files live in `ui/src/main/assets/licenses/` (aapt rejects non-font
   files in `res/font`), which also ships them in the APK as the OFL requires.
4. **Extra convention plugin** `thebox.android.test` for the two `com.android.test` modules,
   alongside the five requested (`thebox.android.application`, `.android.library`,
   `.android.compose`, `.jvm.library`, `.hilt`).
5. **Package / applicationId** `app.thebox` (debug: `app.thebox.debug`). Module namespaces are
   `app.thebox.{core,data,player,ui,ffmpeg,benchmark,baselineprofile}`. Generic, easy to rename
   once the trademark check is done.
6. `FfmpegAvailability.isAvailable` checks `androidx.media3.decoder.ffmpeg.FfmpegLibrary`
   reflectively, so it is `false` today and becomes truthful once CI drops the AAR into
   `ffmpeg/libs/` (no code change needed).
7. Release and benchmark builds strip `lib/x86*/` native libs (from `sqlite-bundled`); debug keeps
   them so the universal debug APK runs on an x86_64 emulator.

## Build

```sh
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # Homebrew JDK 21
export ANDROID_HOME=$HOME/Library/Android/sdk
# local.properties (gitignored): sdk.dir=/Users/<you>/Library/Android/sdk
#   thebox.tmdbKey=<TMDB v3 API key>   # optional: the app's own key for programme posters (or env THEBOX_TMDB_KEY)
# One-time: sdkmanager "platforms;android-37.0"

./gradlew --no-daemon :core:test :data:testDebugUnitTest :app:assembleDebug   # CI smoke build (M1 onwards)
./gradlew --no-daemon :ui:assembleDebug :player:assembleDebug :benchmark:assembleBenchmark :core:perfTest   # extras
```

Also verified: `:app:assembleBenchmark` (R8 minify + resource shrink),
`:baselineprofile:assemble`, `help --task :benchmark:connectedBenchmarkAndroidTest`.

Outputs (`app/build/outputs/apk/debug/`): `app-universal-debug.apk` (~25 MB, unminified, includes
x86 libs), `app-armeabi-v7a-debug.apk`, `app-arm64-v8a-debug.apk` (~21.6 MB each). Minified
benchmark universal APK is ~3.6 MB.

Baseline profiles: `./gradlew :app:generateBaselineProfile` with an API 28+ device attached
(`automaticGenerationDuringBuild = false`). Macrobenchmarks:
`./gradlew :benchmark:connectedBenchmarkAndroidTest` (targets `:app`'s `benchmark` build type).

CI: `.github/workflows/android.yml` (ubuntu-latest, Temurin 21, `gradle/actions/setup-gradle`
cache, runs `:core:test :app:assembleDebug`, uploads the debug APKs).

Release signing: create `keystore.properties` (gitignored) at the repo root with `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`; otherwise release is signed with the debug key.

## Known warnings (harmless at M0)

- `Configuration.setVisible(boolean) has been deprecated … removed in Gradle 11` — emitted by
  AGP 9.4.1 itself (`BasePlugin.createAndroidJdkImageConfiguration`, `SourceSetManager`), not by
  our scripts. Goes away with a future AGP.
- Manifest merger: `provider#org.acra.attachment.AcraContentProvider … tagged to replace other
  declarations but no other declaration present` — comes from ACRA's own manifest.
- `sdkmanager` prints that it is deprecated in favour of the `android sdk` CLI; it still works.

## Follow-ups (not done in M0)

- Per-ABI `versionCode` offsets (so arm64 outranks armeabi-v7a on upgrade) when the updater lands.
- ktlint/detekt not configured.
- `:ffmpeg` local-AAR dependency cannot be bundled into an AAR of `:ffmpeg` itself
  (`bundleReleaseAar`); app builds are fine. See `ffmpeg/README.md`.
