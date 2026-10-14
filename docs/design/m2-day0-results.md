### 6.9 Day-0 results (commit 0)

Draft for `m2-playback-core.md`. The lead folds this section in after §6.8. Run on 2026-10-13 on the dev Mac (Intel x86_64, JDK 21.0.2, Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21), in the commit-0 worktree, with every stub in place.

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug` with every stub | **Pass** | `:core`: 849 tests in 53 classes, including the new `TemplateExpanderTest.doubleBracePlaceholdersPassThroughUntouched`. `:data`: 124 tests in 18 classes, including the new `LibraryDatabaseMigrationTest` (2) and `PlaybackMemoryDaoTest` (2). `:player`: 4 tests in 1 class (`Day0RealExoPlayerTest`). The Hilt graph is checked at compile time: `PlaybackDebugActivity` injects `PlaybackController`, so `PlaybackControllerImpl` and its whole dependency set must resolve. That set includes the unqualified `OkHttpClient` from `:data` and the three `:app` provider bindings. `:player:lintDebug`, `:ui:lintDebug`, `:ffmpeg:lintDebug` and `:app:lintDebug` are clean (no `UnsafeOptInUsageError`) |
| 2 | `:player:debugUnitTestRuntimeClasspath` | **Pass** | okhttp `4.12.0 -> 5.5.0`; robolectric `4.16 -> 4.17`; kotlinx-coroutines(-test) `1.9.0 -> 1.11.0`; `mockwebserver3:5.5.0`. There is no `com.squareup.okhttp3:mockwebserver:4.*`: the `exclude` on both `media3-test-utils` dependencies works |
| 3 | Real `ExoPlayer` under Robolectric playing `h264_aac_2s.ts` to the first rendered frame | **Pass**, 4/4 cases, stable over 4 consecutive runs. Details below | `player/src/test/kotlin/app/thebox/player/Day0RealExoPlayerTest.kt` |
| 4 | `:app:assembleRelease` (R8) | **Pass** | `apkanalyzer dex packages app-universal-release.apk`: no `app.thebox.debug` package and no `PlaybackDebugActivity`. The release manifest has no `thebox-debug` scheme. The debug APK has both (`Hilt_PlaybackDebugActivity`, two `thebox-debug` `<data>` entries) |
| 5 | `MigrationTestHelper` 1 → 2 on the API 30 AVD | **Not run** (emulator; the lead runs it) | Test in place: `data/src/androidTest/.../I1LibraryDatabaseTest.migrate1To2` (`BundledSQLiteDriver`). It compiles (`:data:compileDebugAndroidTestKotlin`) |
| 6 | `FfmpegAvailability.isAvailable` on the TV emulator | **Not run** (emulator) | `FfmpegAvailability.supports(mimeType)` was added (reflective, false without the AAR). The consumer R8 rule now also keeps `supportsFormat(String)` |
| 7 | `:data` Robolectric migration test sees `1.json`/`2.json` as assets; the migration SQL is exactly the three `ALTER TABLE`s | **Pass**, after one build change | The Room Gradle plugin wires schemas into `androidTest` assets only. The first run failed with `FileNotFoundException`. `data/build.gradle.kts` now also adds `schemas/` to the host-test assets through the variant API (`hostTest.sources.assets?.addStaticSourceDirectory("schemas")`). The generated `LibraryDatabase_AutoMigration_1_2_Impl` runs `ALTER TABLE playlist ADD COLUMN xtream_output_learned TEXT DEFAULT NULL`, `ALTER TABLE channel_prefs ADD COLUMN playback_flags INTEGER NOT NULL DEFAULT 0` and `ALTER TABLE channel_prefs ADD COLUMN fps_milli INTEGER DEFAULT NULL`. That is the §2.10 set in a different order and with an explicit `DEFAULT NULL`, which is semantically identical. There is no table rebuild. The test asserts the new columns, the defaults, preserved user rows, the unchanged PK/FK, and a read through `LibraryDatabase.build` |
| 8 | Merged release manifest | **Pass** | `apkanalyzer manifest print`: no `PlaybackService`, no `androidx.media3.session.MediaSessionService` filter, no `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. `WAKE_LOCK` is present. `MainActivity` has `supportsPictureInPicture="false"`. `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` remain, from `:app` (the M1 import workers) |
| 9 | Check 3 uses `Surface(SurfaceTexture(1))`, `IdlingMediaCodecAdapterFactory` and `Clock.DEFAULT` | **Done** | See below. A `FakeClock` is used where no socket is involved |

#### Check 3 in detail (decides the WP-C/WP-D test strategy)

Setup, common to every case:
- `TestExoPlayerBuilder(context).setClock(clock).setRenderersFactory(f)`.
- `f` is a `DefaultRenderersFactory` subclass whose `getCodecAdapterFactory()` returns `IdlingMediaCodecAdapterFactory(context, clock)`. This is the S14 seam: `PlayerFactory`/`BoxRenderersFactory` should expose the same override as an `internal` test hook.
- `@get:Rule ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()` and `player.setVideoSurface(Surface(SurfaceTexture(1)))`.
- The design's TS source: a `ProgressiveMediaSource` over an `ExtractorsFactory` that returns only the `TsExtractor`, with `FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS` and `MODE_SINGLE_PMT`. The `MediaItem` uses `thebox-session://1` and `mediaId "1"`.

| Case | Data source / clock | Result |
|---|---|---|
| A | `ByteArrayDataSource(h264_aac_2s.ts)`, auto-advancing `FakeClock` | `runUntilRenderedFirstFrame` passes, then `runUntilPlaybackState(ENDED)` passes. Decoders: `media3.video.avc` (`video/avc` 320×180) and `media3.audio.aac` (`audio/mp4a-latm`, 2 ch). States `[BUFFERING, READY, ENDED]`. Video counters are deterministic across runs (rendered 50, skipped 51, dropped 0) |
| B | `mockwebserver3`, chunked body (no `Content-Length`), `OkHttpDataSource`, `Clock.DEFAULT` | Passes: first frame, READY, ENDED. Exactly one request, with **no `Range` header** (`position 0` + `LENGTH_UNSET`). Duration 2,006 ms once ended |
| C | `mockwebserver3`, chunked and throttled to 16 KB per 100 ms (about real time for this clip), `Clock.DEFAULT` | Passes. First frame after about 135 ms of wall time, then READY and ENDED |
| D | `ByteArrayDataSource(h264_mp2_2s.ts)`, `FakeClock` | Passes. MP2 maps to `audio/mpeg-L2`, and the shadow decoder `media3.audio.mpegl2` is chosen |

Findings that constrain WP-C and WP-D:
1. **Real-player Robolectric tests are feasible. The §5.4 fallback (moving them to the instrumented suite) is not needed.** Each case runs in about 1–3 s, and the whole class takes about 13 s including the Robolectric sandbox start.
2. **The first frame renders while the player is still `STATE_BUFFERING`.** In every case `playbackState == 2` right after `runUntilRenderedFirstFrame`, and READY follows. Tests must not assert READY at the first frame. This also confirms the §3.1.2 note that M3 keeps the cover until the first frame, not until READY.
3. **`onAudioPositionAdvancing` never fires with the shadow codecs** (`audioAdvancing=false` in every case), as S14 predicted. Real-player tests must set `audioExpected = false` and cannot exercise the audio branch of the "first media" milestone (B2). The audio decoder still initialises and consumes input: case A queued 88 buffers and rendered 86 output buffers.
4. **With `Clock.DEFAULT`, frame counters are not reproducible.** Over 5 runs, case B rendered 27–99 output buffers with 1–74 dropped, because frames count as late against the wall clock. Network-bound tests may assert the milestones (first frame, READY, ENDED, error), the request log and the decoder names, never frame or drop counts. Counter-based assertions (dropped frames, stats) need case A's setup: in-memory or `FakeDataSource` bytes with a `FakeClock`.
5. Shadow output-buffer counts are not frame counts. 50 samples in gave rendered + skipped ≈ 101 output buffers in case A. Assert "≥ 1" or relative changes, not 50.
6. The run helpers enforce the main looper as the application looper. `TestExoPlayerBuilder`'s default looper satisfies this, which matches §6.3 (`setLooper(Looper.getMainLooper())`).

Not covered by day 0 (WP-B/WP-C own these): an endless live body with a mid-stream close (R-2), 405 on open (R-3), and a `LoadControl` profile change (R-5). Case C shows that partial, slow delivery through OkHttp works with the real clock, which is the basis for R-2.
