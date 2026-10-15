# M2 "Playback core": design

Status: **proposed, 2026-10-13; reviewed 2026-10-13** (`review/m2-design-review.md`: 6 blockers and 14 should-fix items applied in place, each marked "(reviewed 2026-10-13: …)"). Lead designer for M2. The lead commits the shared contracts and build changes (commit 0, §1.8) and starts the four work packages after a reviewer pass and the lead's answers to §8.
Inputs: `00-master-plan.md` §3, §6, §8 (M2 row); `review/architecture-review.md` B1, B4, B5, B6, C, D (D1–D7), E; `research/android-tv-architecture.md` §2 and §4; `research/protocols-m3u-xmltv-xtream.md` §3.3–3.5, §5, §6.3, §6.6; `research/user-issues-and-mitigations.md` §1–§3 and the top-25 list; `review/product-review.md` §2.3 S8, §2.4, §3.8, §4.2, §4.4, §4.9, §4.12; `design/m1-data-layer.md` §0, §1.1, §3.8 and its amendments; `review/m1-code-review.md` Later 21–28; the M1 code in `core/` and `data/`; `tools/fakepanel/README.md`. Every Media3 class and method named here was checked against the 1.11.1 `-sources.jar`s (Appendix A).

This document is the contract between four implementers. Where it says **must**, a reviewer rejects code that does otherwise. Where it gives a number (timeout, threshold, budget), the number is the default and lives in one named constant (Appendix B lists them all with their owner).

## Lead sign-off (2026-10-13)

Approved after the design review (`docs/review/m2-design-review.md`; 6 blockers and 14 should-fix items applied in place). Lead decisions on the review's list:
- **Q1 Home vs standby:** Home / `onStop` while the device is interactive stops playback at once; the 30 s grace applies only to screen-off, HDMI unplug and display-off, detected with `PowerManager.isInteractive()` and the display state (B4).
- **Q2 Fake panel v1:** landed on `main` (`11d2c3f`) with continuous timestamps, `reconnect_mode` semantics, `stall_after`, `request_log` and `max_open_seen`. The S13 remainder (prompt client-EOF detection, audio-only channel 13) is a follow-up task before the instrumented suite runs.
- **Q3 FFmpeg on the emulator:** a three-ABI debug AAR (`x86 armeabi-v7a arm64-v8a`) is built and installed in `ffmpeg/libs/` locally; release packaging keeps stripping x86. The shipped notice file still describes the ARM-only artifact.
- **Q4 VLC user-agent one-shot retry on 403:** kept, as a setting, default on, never persisted automatically, only after the account gate shows an active account with a free slot, and never for a channel that sets its own UA.
- **Q5** buffer decay after 10 min stable. **Q6** learned audio facts stay in memory in M2; M5 persists them keyed by route type + product name. **Q7** the additive `library.db` v2 migration ships in M2 with the S10 conditions (column-targeted writes, exact `ALTER TABLE`s, tests in `:data`).
- **Q8 HIDDEN viewport:** audio and the connection are kept with no timeout in M2; an inactivity prompt is M4/M5 work.
- **Q9 PCR tolerance:** symmetric 2 s re-prepare in M2; D4 logs decide whether forward jumps with audio are ridden through later.
- **Q10** `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` off by default (S5). **Q11** no resolution switching work. **Q12** `interlacedHint` from the H.264 SPS `frameMbsOnlyFlag` in WP-C; HEVC stays false.
- **B3** no idle player release in v1. **B5** no `MediaSessionService` and no `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission in the v1 manifest; the `MediaSession` lives in the controller and refuses play/next/previous while the app is in the background. This amends the master plan §3 "Process" row (recorded there). **S5** dead-track guard is WP-C scope. **B6** `DECODER_STALL_MAX_MEDIA_GROWTH_MS = 500` initial value.

---

## 0. Key decisions at a glance

1. **Pure decision logic lives in `:core` (`app.thebox.core.playback`), Media3 glue lives in `:player`, and `:player` does not depend on `:data`.** URL resolution rules, credential expansion, the connection-broker state machine, the error ladder, the buffer-profile FSM, the stall detectors, the TS sniffer/packet aligner/PCR tracker, the AFR mode picker and the video-rect maths are plain Kotlin with JUnit 5 tests and a fake clock. `:player` declares three small provider interfaces (`PlaybackSourceProvider`, which also re-logs-in an Xtream account; `ChannelMemory`; `PlaybackSettingsSource`) that `:app` binds to the M1 repositories in Hilt. `:ui` depends on `:player` for the root video layer.
2. **One `PlaybackController` `@Singleton` owns one `ExoPlayer` for the life of the process** (created lazily on the main looper and never released before process death in v1; reviewed 2026-10-13, blocker B3: `surfacePlayer` and the session player wrap a fixed `ExoPlayer`, so a re-created player would never get the `SurfaceView` back. `stop()` already releases the codecs and, through `LoadControl.onStopped`, the media buffer). Every tune creates a short-lived **`TuneSession`** (resolved stream + broker lease + telemetry + its own `DataSource.Factory`) and a fresh `MediaSource`; the player, renderers and surface are reused. Every runtime fallback (passthrough off, software audio for one MIME type, software video, tunneling off, buffer profile, TS compatibility level) is a **runtime switch read by long-lived components** (a gated `AudioSink`, a mode-aware `MediaCodecSelector`, track-selector parameters, a mutable `LoadControl`, the per-session extractor factory), so no fallback rebuilds the player. The single exception is the global "software audio fallback (FFmpeg)" toggle, which changes the renderer list.
3. **Live TS reconnects inside `LiveTsDataSource`, invisible to ExoPlayer.** `open()` ignores `DataSpec.position`/`length`, never sends `Range`, and always returns `C.LENGTH_UNSET` (so neither `ProgressiveMediaPeriod` nor `TsExtractor`'s duration reader ever asks for a byte offset). Bytes reach the extractor only as whole 188-byte packets, so a reconnect splices at a packet boundary, and after a splice every PID's continuity counter is forced to jump so `TsExtractor` always resets its PES readers (reviewed 2026-10-13, blocker B1). A PCR tracker compares the first PCR after a reconnect with the expected value; post-splice packets are held back until that verdict; a jump of more than 2 s throws a typed `LiveStreamException(PCR_DISCONTINUITY)` and the controller re-prepares. Up to 3 reconnects within 3 s, then the controller's ladder takes over ("Reconnecting n/5").
4. **`StreamConnectionBroker` (one per playlist) is the only gate to upstream sockets.** Every OkHttp `Call` is created through a broker-aware `Call.Factory` and attached to a **lease**. A zap pre-empts the old playback lease by `Call.cancel()` (synchronous socket close) before the new open; a lease is released only after its call is cancelled and its body closed, never on `player.stop()`. `max_connections` = playlist override → account → 1. Held CH± keys are coalesced by a 350 ms debounce (a single press tunes at once; repeats tune once, 350 ms after the last press). Leases have a kind (`PLAYBACK`, `PREVIEW` (v1.1), `RECORDING` (v2), `PROBE`), which is the v2 recorder seam together with the `DataSink` slot in `LiveTsDataSource`.
5. **Errors go through two layers.** `BoxLoadErrorHandlingPolicy` (loader level) makes 401/403/404/405/406/410/458/509 and every `LiveStreamException` non-retryable, retries 5xx/IO with 500/1000/2000 ms ±20 % jitter, and retries an HLS *segment* 404 twice at 500 ms. The controller's **`ErrorLadder`** (pure, table-driven, §4.3) maps a classified `FailureSignal` to one `LadderDecision` (retry, re-resolve, flip TS↔HLS, rotate base URL, seek to live, software decoder, passthrough off, tunneling off, user-agent fallback, fail). Re-resolves are rate-limited to 2 per minute per channel. Users see a `PlaybackError` with a stable code, a detail and next-step actions; M4 owns the copy.
6. **Adaptive buffering without rebuilding the player:** `AdaptiveLoadControl` delegates the allocator to a `DefaultLoadControl` but implements `shouldContinueLoading`/`shouldStartPlayback` from a `@Volatile` profile. Auto mode starts at 1 s and climbs four levels (rebuffer resume threshold 3 → 5 → 10 → 20 s) after rebuffers, per playlist, and decays one level after 10 min without a rebuffer. Byte cap 24 MB on low-RAM devices, 48 MB otherwise. Manual Small/Medium/Large/Extra large map to 2/5/10/20 s.
7. **Four stall detectors run in a 1 Hz watchdog on the main thread** (no bytes; decoder stuck while bytes arrive; frozen picture while audio advances; audio never starts or underruns on a passthrough or decoder path), each with named thresholds, a cool-down and explicit "not while paused/seeking/hidden/tunneled" guards. Media3 1.11's built-in `StuckPlayerDetector` stays on as a backstop (stuck-buffering timeout lowered from 600 s to 30 s).
8. **Decoders and audio:** video `HW → platform SW` through `BoxMediaCodecSelector` plus `setEnableDecoderFallback(true)`; audio `EXTENSION_RENDERER_MODE_ON` (platform first, FFmpeg second), with per-MIME "prefer FFmpeg" done at runtime by hiding platform decoders for that MIME in the selector. Passthrough is allowed only for encodings the sink reports and can be denied per encoding at runtime by `PassthroughGateAudioSink` (a `ForwardingAudioSink`). AAC-LATM needs no FFmpeg: Media3's `LatmReader` unwraps LATM/LOAS and emits raw AAC access units with an AudioSpecificConfig under `MimeTypes.AUDIO_AAC` (whose value is `"audio/mp4a-latm"`, Android's name for plain AAC), which every platform AAC decoder takes (verified). Multi-program LATM and `audioMuxVersionA == 1` are rejected (`ParserException`); `audioMuxVersion == 1` is parsed but **never emits a `Format`**, so the TS prepare would hang (reviewed 2026-10-13, S5: covered by the dead-track guard of §3.8.5). MP2 maps to FFmpeg's `mp3` decoder name (verified in the AAR), and FFmpeg's `mp3` decoder decodes Layer I/II frames (verified in the FFmpeg source, `mpegaudiodec_template.c` dispatches on the frame header's layer). Tunneling off by default. TS flags `ALLOW_NON_IDR_KEYFRAMES | DETECT_ACCESS_UNITS` (reviewed 2026-10-13, S5: `ENABLE_HDMV_DTS_AUDIO_STREAMS` dropped from the defaults; stream type 0x82 is SCTE-27 subtitles on North American cable restreams), with a learned per-channel compatibility level (`MODE_MULTI_PMT`).
9. **AFR is gated by viewport.** Off / Seamless only (default; ExoPlayer's own `ONLY_IF_SEAMLESS`, which estimates fps itself) / Always. "Always" switches only in `FULL` after a 2 s dwell: on **API 31+** through `Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` on the root `SurfaceView` (the 3-argument overload and `CHANGE_FRAME_RATE_ALWAYS` are API 31, not 30), with ExoPlayer's strategy set to `OFF`; **below API 31** through `WindowManager.LayoutParams.preferredDisplayModeId`, holding `playWhenReady` until `onDisplayChanged` or 3 s. TS gives no `Format.frameRate` (verified: the TS H.264/H.265 readers never set it), so fps comes from presentation-time deltas, snapped and cached per channel. HDR content in the `MINI` viewport disables the video track (poster) by default.
10. **No foreground service for live TV in v1.** The controller owns the `MediaSession`; in v1 no `MediaSessionService` is declared at all (reviewed 2026-10-13, blocker B5: a media-button `startForegroundService` on API < 31 would crash a service that never calls `startForeground`), and the session refuses play/next/previous while the app is in the background. Home/`onStop` while the device is interactive stops playback at once (TV norm, frees the provider connection); screen-off/standby (an `ON_STOP` while `PowerManager.isInteractive()` is false), HDMI unplug and display-off stop after a 30 s grace (reviewed 2026-10-13, blocker B4). Resume always re-resolves and prepares a fresh session at the live edge.
11. **Surface:** `media3-ui-compose` **`PlayerSurface(SURFACE_TYPE_SURFACE_VIEW)`**, fed with `controller.surfacePlayer` (a `ForwardingPlayer` that exposes only the surface commands and lets the controller capture the `SurfaceView` for AFR), placed once at the root behind Compose by `:ui`'s `VideoLayer`. Mini ↔ full is a rect change; the surface is never swapped.
12. **Credentials are expanded last.** `{{base}}` and `{{ext}}` are expanded first, then any catch-up `TemplateExpander` pass, then `{{u}}`/`{{p}}` (path- or query-encoded by position) at the moment of connecting. Resolved URLs are never persisted or logged; every retry re-resolves from the stored template. (Applies M1 code review Later 28; commit 0 amends `TemplateExpander` to pass `{{…}}` through untouched and corrects `CatchupTarget.liveUrl`'s KDoc.)
13. **Tests:** JUnit 5 for all `:core` logic; Robolectric + `mockwebserver3` for `LiveTsDataSource`, the HTTP factory and the load policy; Robolectric + `media3-test-utils-robolectric` for real-`ExoPlayer` assembly tests (feasible, gated by a day-0 check); an instrumented suite on the API 30 TV emulator against the fake panel (D3, D4, D5, token expiry, Range, 405 flip, HTML splash, UA whitelist, MP2, stalls, lifecycle). Perf numbers are measured and logged, never asserted, until low-end hardware exists. The fake panel needs two fixes first (continuous timestamps across clip loops; a mid-stream stall fault, §5.6).
14. **A debug-only harness** (`PlaybackDebugActivity` in `app/src/debug`, deep link `thebox-debug://play`) hosts the root video layer exactly as M3 will, shows the controller state, stats and event log, and is what the instrumented tests launch.
15. **One small `library.db` migration (v1 → v2, additive)** stores what M2 learns per channel and per playlist: `channel_prefs.playback_flags`, `channel_prefs.fps_milli`, `playlist.xtream_output_learned`. They are written with column-targeted upserts, never whole-row writes (reviewed 2026-10-13, S10).
16. **"First media" replaces "first frame" as the tune milestone** (reviewed 2026-10-13, blocker B2): `onRenderedFirstFrame` when a video track is selected and enabled; otherwise (radio, HDR poster, HIDDEN viewport) the first `onAudioPositionAdvancing`. Every rule below that says "first frame" means this milestone.

---

## 1. Packages, files and ownership

### 1.1 Rules of engagement

Copied from M1 §1.1, adapted:

- **Commit 0 (lead, before any WP starts):** everything in §2 marked "commit 0", the `:core` amendments (§2.9), the `library.db` v2 migration (§2.10), all build changes (§6), the TS fixtures (§5.2) and a stub for every implementation class named in §1.4–§1.7 at its final path (`TODO("WP-x")` bodies). After commit 0, `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug` must be green.
- **Stubs:** each stub is replaced by its owning WP only. WP-D binds the stubs in Hilt on day one, so `:app` compiles before A, B and C land.
- **No WP edits a file owned by another WP, a contract file or a build file.** Contract problems go to the lead, who amends commit 0 for everyone (additive changes, with a default for every new parameter).
- **Packages are disjoint** (tables below). Merge conflicts can only happen in lead-owned files.
- **Tests:** each WP owns `src/test/.../<its packages>` (and `src/androidTest` for WP-D) plus its fixture directories.
- **Merge order:** A, B and C in any order; D last (D works against fakes of A/B/C until they land). Every WP rebases on `main` before review. The integration pass (§1.9) follows.
- **Models:** WP-A, WP-B and WP-D on Opus (protocol, playback, lifecycle). WP-C on Opus for the renderer/audio/AFR parts; its mechanical parts (stats mapping, track lists) may be delegated to Sonnet under this spec.

### 1.2 Module graph (decision §0.1)

```
:app ──► :ui ──► :player ──► :core
  │        └──────────────────►│
  ├──► :data ──────────────────►│      (:player never sees :data)
  └──► :player, :ffmpeg
:player ──► :ffmpeg (FfmpegAvailability, the AAR on the runtime classpath)
```

- `:player` → `api(project(":core"))` (public API types such as `ChannelIdentity`, `PlaybackError`, `ArchiveRequest` come from `:core`), `implementation(project(":ffmpeg"))`, Media3, OkHttp. It injects the **unqualified `OkHttpClient`** that `:data`'s `HttpModule.baseClient()` provides; the binding is resolved in `:app`'s graph, so no compile dependency is needed.
- `:ui` → `implementation(project(":player"))` and `media3-ui-compose`, for `VideoLayer` (M2) and the M3/M4 screens that observe the controller.
- `:app` → binds `:player`'s provider interfaces to `:data` repositories (`PlaybackBindingsModule`), installs the zap gate into `ImportMutex.gate`, hosts the debug harness and the instrumented suite.

Why not `:player → :data`: `:player` tests would need Room (Robolectric) for every resolver and controller test; M3/M4 consume `:player`'s API and should not see repositories through it; and the provider seam is exactly the shape the v2 `:dvr` module needs (§7 D1).

### 1.3 Shared contracts (commit 0, lead)

| File | Contents | Section |
|---|---|---|
| `core/src/main/kotlin/app/thebox/core/playback/PlaybackContracts.kt` | Value types shared by logic and glue: formats, resolver inputs/outputs, error codes, `PlaybackError`, `FailureSignal`, `LadderDecision`, buffer/AFR/viewport enums, `StallKind`, `LeaseKind`, `ConnectionHandle` | §2.1 |
| `core/src/main/kotlin/app/thebox/core/playback/PlaybackLogic.kt` | Interfaces of the pure components (resolver core, credential expander, broker core, ladder, re-resolve limiter, buffer FSM, detectors, sniffer, packet aligner, PCR tracker, AFR picker, rect maths, event log) | §2.2 |
| `player/src/main/kotlin/app/thebox/player/api/PlaybackApi.kt` | `PlaybackController` and every type M3/M4 consume | §2.3 |
| `player/src/main/kotlin/app/thebox/player/api/PlaybackProviders.kt` | `PlaybackSourceProvider`, `ChannelMemory`, `PlaybackSettingsSource`, `PlaybackSettings`, `InMemoryPlaybackSettings` | §2.4 |
| `player/src/main/kotlin/app/thebox/player/internal/Seams.kt` | `internal` interfaces between WPs inside `:player` (session context, telemetry, broker registry, player bundle, watchdog, display mode) | §2.5 |
| `ui/src/main/kotlin/app/thebox/ui/playback/VideoLayer.kt` | Signature only in commit 0; WP-D owns the body | §2.6 |

### 1.4 WP-A: pure playback logic, resolver and broker

Modules `:core` (logic) and `:player` (the Android-free adapters). Consumes `PlaybackContracts`, `XtreamUrls`, `XtreamStreamUrl`, `Redact`, `BoxLog`. Exposes the `:core` logic to WP-B/C/D and `StreamUrlResolver`/`StreamBrokerRegistry` to WP-D.

| File | Contents |
|---|---|
| `core/.../playback/resolve/StreamUrlBuilder.kt` | `StreamUrlBuilderImpl`: §3.2 steps 2–7 (pure) |
| `core/.../playback/resolve/CredentialExpander.kt` | `CredentialExpanderImpl`: `{{base}}`, `{{ext}}`, then `{{u}}`/`{{p}}` with position-aware encoding (§3.2.4) |
| `core/.../playback/resolve/HeaderPolicy.kt` | Case-insensitive header precedence and UA defaulting (§3.2.5) |
| `core/.../playback/resolve/FormatHints.kt` | URL → `StreamFormat` hint (§3.2.6) |
| `core/.../playback/broker/BrokerCore.kt` | `ConnectionBrokerCore`: leases, pre-emption, waiters, snapshot (§3.5) |
| `core/.../playback/broker/ZapDebouncer.kt` | Leading-edge + 350 ms trailing coalescer (§3.5.4) |
| `core/.../playback/errors/ErrorLadderImpl.kt` | The decision table of §4.3 |
| `core/.../playback/errors/ReResolveLimiter.kt` | 2 per 60 s per channel, sliding window |
| `core/.../playback/errors/LoadRetryRules.kt` | Pure status/exception-kind → retry delay rules used by WP-B's policy (§4.2) |
| `core/.../playback/buffer/BufferProfileFsm.kt` | Levels, escalation, decay, manual mapping (§3.6) |
| `core/.../playback/stall/StallDetectors.kt` | The four detectors over a `WatchdogSample` (§3.7) |
| `core/.../playback/afr/DisplayModePicker.kt` | Mode choice and fps snapping (§3.9) |
| `core/.../playback/surface/VideoRectCalculator.kt` | Aspect maths for M3/M4 (§3.11) |
| `core/.../playback/diag/PlaybackEventLog.kt` | 200-entry redacted ring + 50-entry zap history (§3.13) |
| `player/.../resolve/StreamUrlResolverImpl.kt` | `StreamUrlResolver`: fetches inputs from `PlaybackSourceProvider`, calls the builder, builds `CatchupTarget`s (§3.2) |
| `player/.../broker/StreamBrokerRegistry.kt` | One `ConnectionBrokerCore` per `BrokerKey`, configured from playlist/account (§3.5) |

Tests: `core/src/test/kotlin/app/thebox/core/playback/{resolve,broker,errors,buffer,stall,afr,surface,diag}/…` (JUnit 5), `player/src/test/kotlin/app/thebox/player/{resolve,broker}/…` (JUnit 4, no Robolectric needed: these files must not touch `android.*`).

### 1.5 WP-B: transport (TS sniffing, LiveTsDataSource, HTTP factory, load policy)

| File | Contents |
|---|---|
| `core/.../playback/ts/StreamSniffer.kt` | First-bytes classifier (§3.3.3) |
| `core/.../playback/ts/TsPacketAligner.kt` | Sync search and whole-packet delivery (§3.3.4) |
| `core/.../playback/ts/PcrTracker.kt` | PCR PID pick, 33-bit PCR base, jump test (§3.3.5) |
| `player/.../datasource/LiveTsDataSource.kt` | The reconnecting live TS source and its `Factory` (§3.3) |
| `player/.../datasource/StreamHttpDataSourceFactory.kt` | HLS and archive (finite) HTTP sources over the brokered call factory (§3.4) |
| `player/.../datasource/BrokeredCallFactory.kt` | `Call.Factory` that tags requests, attaches every `Call` to the session lease and records status/redirects/final host. (reviewed 2026-10-13, S2: it registers a per-call `EventListener` with `Call.addEventListener` (OkHttp 5.5.0); `callEnd`/`callFailed` detach the handle and release the lease once it has no handles; `responseHeadersEnd` records status/host) |
| `player/.../datasource/PlaybackHttpClients.kt` | Live/segment `OkHttpClient` variants derived from the injected base client; the per-playlist "ignore TLS errors" variant |
| `player/.../datasource/ByteWatchdog.kt` | One shared timer thread checking every live session's last-byte age (§3.3.6) |
| `player/.../datasource/LiveStreamException.kt` | The typed failure (§3.3.8) |
| `player/.../datasource/BoxLoadErrorHandlingPolicy.kt` | §4.2 |
| `player/.../datasource/NoOpDataSink.kt` | The v1 sink behind the `TeeDataSource` seam |

Tests: `core/src/test/.../playback/ts/…` (JUnit 5 with byte fixtures), `player/src/test/kotlin/app/thebox/player/datasource/…` (Robolectric `sdk=35` + `mockwebserver3`).

### 1.6 WP-C: ExoPlayer assembly (engine)

| File | Contents |
|---|---|
| `player/.../engine/PlayerFactory.kt` | Builds the `PlayerBundle` (ExoPlayer + track selector + load control + selector + audio gate) (§3.8, App. A) |
| `player/.../engine/BoxRenderersFactory.kt` | `DefaultRenderersFactory` subclass: extension mode, decoder fallback, selector, gated audio sink with the downmix processor |
| `player/.../engine/BoxMediaCodecSelector.kt` | HW/SW ordering, per-channel override, per-MIME software-audio gate (§3.8.1) |
| `player/.../engine/PassthroughGateAudioSink.kt` | `ForwardingAudioSink` that denies encoded formats per policy (§3.8.3) |
| `player/.../engine/StereoDownmixProcessor.kt` | Toggleable `ChannelMixingAudioProcessor` wrapper (§3.8.3) |
| `player/.../engine/AdaptiveLoadControl.kt` | §3.6.3 |
| `player/.../engine/MediaSourceBuilder.kt` | `TuneSession` → `MediaSource` (progressive TS, HLS, generic progressive, archive) (§3.1.3) |
| `player/.../engine/TsExtractorsFactory.kt` | TS-only extractor list with the session's compat level, wrapped in the dead-track guard (§3.8.5; reviewed 2026-10-13, S5) |
| `player/.../engine/TrackController.kt` | `TrackInfo` mapping, selection, preferences, HDR poster track disable (§3.12) |
| `player/.../engine/StatsCollector.kt` | `AnalyticsListener` + telemetry → `StreamStats` at ≤ 2 Hz (§3.13) |
| `player/.../engine/PlaybackWatchdog.kt` | Samples the player once a second and runs the `:core` detectors (§3.7) |
| `player/.../engine/FpsEstimator.kt` | `VideoFrameMetadataListener` → fps (§3.9.3) |
| `player/.../engine/DisplayModeController.kt` | AFR application on both API paths, display listener, revert (§3.9) |
| `player/.../engine/WindowDisplayModeBinder.kt` | Applies `preferredDisplayModeId` to an Activity window (API < 31) |
| `player/.../engine/DeviceCapabilities.kt` | Low-RAM class, decoders, HDR caps, display modes, FFmpeg version, audio caps (§3.15) |
| `player/.../engine/Media3LogBridge.kt` | Routes `androidx.media3.common.util.Log` through redacting `BoxLog` |

Tests: `player/src/test/kotlin/app/thebox/player/engine/…` (Robolectric; real-ExoPlayer tests with `media3-test-utils-robolectric`, §5.4).

### 1.7 WP-D: controller, lifecycle, service, bindings, harness, instrumented suite

| File | Contents |
|---|---|
| `player/.../controller/PlaybackControllerImpl.kt` | The state machine of §3.1, ladder execution, zap list, previous channel, pause/resume, archive seam |
| `player/.../controller/TuneSession.kt` | Per-tune context; owns the lease, telemetry and the `StreamSessionContext` |
| `player/.../controller/FailureClassifier.kt` | `PlaybackException` + last load error → `FailureSignal` (§4.1) |
| `player/.../controller/LifecycleCoordinator.kt` | Process start/stop, screen, HDMI, display state, idle release, resume (§3.10) |
| `player/.../controller/ZapImportGate.kt` | `ImportGate` implementation (§3.5.5) |
| `player/.../controller/SessionPlayer.kt` | `ForwardingSimpleBasePlayer` given to the `MediaSession` |
| `player/.../controller/SurfacePlayer.kt` | `ForwardingPlayer` given to `PlayerSurface`; captures the `SurfaceView` |
| `player/.../controller/PlaybackModule.kt` | Hilt `@Binds PlaybackController` and singletons inside `:player` |
| `player/.../PlaybackService.kt` | Existing file. (reviewed 2026-10-13, B5: deleted from the v1 manifest by the lead in commit 0 together with `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; the class may stay as dead code for v1.1, §3.10.4) |
| `ui/.../playback/VideoLayer.kt` | Root video composable (§2.6) |
| `app/.../playback/PlaybackBindingsModule.kt` | Hilt bindings of the provider interfaces over `:data` (§2.4.2) |
| `app/.../playback/DataPlaybackSourceProvider.kt`, `RoomChannelMemory.kt` | The two provider implementations |
| `app/src/debug/kotlin/app/thebox/debug/PlaybackDebugActivity.kt` + `app/src/debug/AndroidManifest.xml` | Debug harness (§3.16) |
| `app/src/androidTest/kotlin/app/thebox/playback/…` | Instrumented suite (§5.5) and `FakePanelAdmin` client |
| `tools/fakepanel/*` | Fake panel v1 changes (§5.6) and `run-instrumented.sh` |

### 1.8 Commit-0 list (lead)

1. **Catalog** (§6.1): `media3-test-utils`, `media3-test-utils-robolectric`, `androidx-lifecycle-process`. No `media3-exoplayer-dash` (§7 D20).
2. **Build files** (§6.2–§6.5): `:player` (`api(project(":core"))`, test dependencies, unit-test options), `:ui` (`:player`, `media3-ui-compose`), `:app` (debug source set, androidTest dependencies).
3. **Contracts** §2.1–§2.6, verbatim.
4. **Stubs** for every class in §1.4–§1.7.
5. **`:core` amendments** (§2.9): `TemplateExpander` passes `{{…}}` through literally; `CatchupTarget.liveUrl` KDoc. **`:ffmpeg`:** `FfmpegAvailability.supports(mimeType: String): Boolean` (§3.8.1).
6. **`library.db` v2** (§2.10): entity fields, `@AutoMigration(from = 1, to = 2)`, exported `2.json`, a Robolectric migration test and an instrumented `MigrationTestHelper` case **in `:data`** next to M1's `I1LibraryDatabaseTest`, plus the column-targeted DAO methods of §2.10 (reviewed 2026-10-13, S10: `:data` is lead-owned, so `RoomChannelMemory` cannot add them).
6a. **Manifests** (reviewed 2026-10-13, B4/B5): remove the `PlaybackService` `<service>` element and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` from `player/src/main/AndroidManifest.xml` (keep `WAKE_LOCK`); set `android:supportsPictureInPicture="false"` on `MainActivity` (PiP is not in v1; with it, Home can enter PiP and change what `ON_STOP` means).
7. **TS fixtures** (§5.2) generated with the recorded `ffmpeg` commands and checked in.
8. **Day-0 checks** (§6.8) run and recorded in this document.

### 1.9 Integration sequence

1. A, B, C merge (any order). Their unit/Robolectric suites run in `:core:test` and `:player:testDebugUnitTest`.
2. D replaces its fakes with the real A/B/C classes; the Robolectric controller tests (§5.3 WP-D) switch from fakes to the real `PlayerFactory`.
3. Fake panel v1 lands (WP-D), then the instrumented suite runs on `TheBox_TV_API30` (§5.5). Results are recorded in a new "Integration results" subsection of §5, as M1 did.
4. Code review pass (CLAUDE.md), then the plan §6 gates that M2 can measure (zap time: logged only).

---

## 2. Public contracts

Pasted as written in commit 0. KDoc is part of the contract. `:core` code follows M1 §6.6 (no dispatchers, injected clocks, `BoxLog` only). Monotonic time in `:core` is `fun interface MonoClock { fun nowMs(): Long }` (elapsed-realtime milliseconds); `:player` binds it to `SystemClock.elapsedRealtime()`.

### 2.1 `core/.../playback/PlaybackContracts.kt` (commit 0)

```kotlin
package app.thebox.core.playback

import app.thebox.core.model.Catchup
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.EntryKind
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamStreamRef

/** Monotonic milliseconds (SystemClock.elapsedRealtime on device, a fake in tests). */
fun interface MonoClock { fun nowMs(): Long }

// ------------------------------------------------------------------ formats and inputs

/** Container/transport of a stream. Decided by sniffing (§3.3.3); the URL only gives a hint. */
enum class StreamFormat { TS, HLS, PROGRESSIVE_OTHER, DASH, UNKNOWN }

/** playlist.xtream_output and playlist.xtream_output_learned (persisted by name). */
enum class XtreamOutput(val extension: String?) { AUTO(null), TS("ts"), HLS("m3u8") }

/**
 * Secrets for one playlist, mapped by :app from :data's PlaylistSecret. Never logged: toString() is redacted.
 * [baseUrls] is the Xtream login host followed by backups; empty for M3U.
 */
class StreamCredentials(
    val baseUrls: List<String>,
    val username: String?,
    val password: String?,
    val secretHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "StreamCredentials(bases=${baseUrls.size}, user=${if (username == null) "none" else "***"})"
}

/** Non-secret per-playlist facts (playlist row). */
data class PlaylistPlaybackInfo(
    val playlistId: Long,
    val type: PlaylistType,
    /** playlist.user_agent; null = app default. */
    val userAgent: String?,
    /** playlist.extra_headers_json (non-secret headers). */
    val extraHeaders: Map<String, String> = emptyMap(),
    val xtreamOutput: XtreamOutput = XtreamOutput.AUTO,
    /** playlist.xtream_output_learned (§2.10): set after a 405/406 flip succeeded; only read when [xtreamOutput] is AUTO. */
    val xtreamOutputLearned: XtreamOutput? = null,
    val activeBaseIndex: Int = 0,
    val ignoreTlsErrors: Boolean = false,
    val maxConnectionsOverride: Int? = null,
    val needsReauth: Boolean = false,
)

/** What the resolver needs from one channel row (the URL is still a template). */
data class ChannelStreamInfo(
    val identity: ChannelIdentity,
    val name: String,
    /** channel.url: `{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}` (Xtream API), `{{u}}`/`{{p}}` templated (Xtream-shaped M3U) or literal. */
    val urlTemplate: String,
    /** Channel.headers (#EXTVLCOPT, #KODIPROP, pipe headers); values may contain `{{u}}`/`{{p}}`. */
    val headers: Map<String, String> = emptyMap(),
    val xtreamStreamId: Long? = null,
    val containerExt: String? = null,
    val kind: EntryKind = EntryKind.LIVE,
    val catchup: Catchup = Catchup.NONE,
)

/** Everything [StreamUrlBuilder.build] needs. [baseIndex]/[output] override the persisted values for rotation and flips. */
data class ResolveInput(
    val channel: ChannelStreamInfo,
    val playlist: PlaylistPlaybackInfo,
    val credentials: StreamCredentials?,
    val account: XtreamAccountInfo?,
    val defaultUserAgent: String,
    val baseIndex: Int = playlist.activeBaseIndex,
    val output: XtreamOutput? = null,
    /** One-shot UA fallback (ladder step L5); overrides playlist and app UA but never a channel UA. */
    val userAgentOverride: String? = null,
)

/**
 * A playable URL. [url] and [headers] carry credentials and tokens: never persist them, never log them
 * (toString() is redacted), and re-resolve from the template on every retry.
 */
class ResolvedStream(
    /** null for TuneTarget.DirectUrl. */
    val identity: ChannelIdentity?,
    /** Broker key: "p:<playlistId>" or "d:<host>" for direct URLs. */
    val brokerKey: String,
    val url: String,
    /** Final request headers, including User-Agent. Keys are canonical-cased (§3.2.5). */
    val headers: Map<String, String>,
    val formatHint: StreamFormat,
    /** Xtream address of this channel (clear credentials) for CatchupTarget.xtream; null when not Xtream-shaped. */
    val xtream: XtreamStreamRef?,
    /** Extension actually used for an Xtream URL, else null. */
    val output: XtreamOutput?,
    val baseIndex: Int,
    val baseCount: Int,
    /** `host[:port]` of [url], for stats and logs (not secret). */
    val host: String,
    val ignoreTlsErrors: Boolean,
    /** [url] before `{{u}}`/`{{p}}` expansion: CatchupTarget.liveUrl (§2.9). */
    val liveUrlTemplate: String,
    /** Headers before `{{u}}`/`{{p}}` expansion: CatchupTarget.headers. */
    val headerTemplates: Map<String, String>,
) {
    override fun toString(): String = "ResolvedStream($brokerKey, $host, $formatHint, base=$baseIndex/$baseCount)"
}

// ------------------------------------------------------------------ errors

/** Next-step actions offered with an error (M4 renders them; string keys `playback_action_<name lower-case>`). */
enum class NextStep {
    RETRY, ACCOUNT_DETAILS, EDIT_LOGIN, STOP_AND_WAIT, TRY_BACKUP, PREVIOUS_CHANNEL, CHANGE_USER_AGENT,
    PLAYLIST_SETTINGS, USE_SECURE_DNS, DIAGNOSTICS, TRY_SOFTWARE_DECODER, OPEN_EXTERNAL, AUDIO_OPTIONS,
    WATCH_LIVE, TRY_OTHER_CATCHUP_MODE, REFRESH_PLAYLIST,
}

/**
 * User-facing failure taxonomy (product §2.4). [key] is stable (logs, diagnostics, M4 string keys
 * `playback_error_<key with '.' → '_'>_title` / `_detail`). Append-only.
 */
enum class PlaybackErrorCode(val key: String, val defaultSteps: List<NextStep>) {
    AUTH_REJECTED("play.auth_rejected", listOf(NextStep.ACCOUNT_DETAILS, NextStep.EDIT_LOGIN)),
    ACCOUNT_EXPIRED("play.account_expired", listOf(NextStep.ACCOUNT_DETAILS)),
    ACCOUNT_DISABLED("play.account_disabled", listOf(NextStep.ACCOUNT_DETAILS)),
    NEEDS_REAUTH("play.needs_reauth", listOf(NextStep.EDIT_LOGIN)),
    CREDENTIALS_UNAVAILABLE("play.credentials_unavailable", listOf(NextStep.RETRY)),
    CONNECTION_LIMIT("play.connection_limit", listOf(NextStep.RETRY, NextStep.STOP_AND_WAIT)),
    CONNECTION_LIMIT_LOCAL("play.connection_limit_local", listOf(NextStep.RETRY)),
    FORBIDDEN("play.forbidden", listOf(NextStep.RETRY, NextStep.CHANGE_USER_AGENT, NextStep.DIAGNOSTICS)),
    CHANNEL_OFFLINE("play.channel_offline", listOf(NextStep.TRY_BACKUP, NextStep.PREVIOUS_CHANNEL, NextStep.RETRY)),
    FORMAT_REFUSED("play.format_refused", listOf(NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS)),
    WEB_PAGE_INSTEAD("play.web_page", listOf(NextStep.ACCOUNT_DETAILS, NextStep.RETRY)),
    EMPTY_RESPONSE("play.empty_response", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    NO_DATA_TIMEOUT("play.no_data", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    STREAM_INTERRUPTED("play.interrupted", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    SERVER_ERROR("play.server_error", listOf(NextStep.RETRY, NextStep.TRY_BACKUP)),
    DNS_FAILURE("play.dns", listOf(NextStep.USE_SECURE_DNS, NextStep.DIAGNOSTICS)),
    CONNECT_FAILED("play.connect", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    TLS_FAILURE("play.tls", listOf(NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS)),
    CODEC_UNSUPPORTED("play.codec_unsupported", listOf(NextStep.TRY_SOFTWARE_DECODER, NextStep.OPEN_EXTERNAL)),
    STREAM_UNDECODABLE("play.undecodable", listOf(NextStep.RETRY, NextStep.OPEN_EXTERNAL)),
    AUDIO_OUTPUT_FAILED("play.audio_output", listOf(NextStep.AUDIO_OPTIONS)),
    UNSUPPORTED_SOURCE("play.unsupported_source", listOf(NextStep.OPEN_EXTERNAL)),
    CATCHUP_NOT_IN_ARCHIVE("play.catchup_missing", listOf(NextStep.WATCH_LIVE, NextStep.TRY_OTHER_CATCHUP_MODE)),
    CHANNEL_NOT_FOUND("play.channel_missing", listOf(NextStep.REFRESH_PLAYLIST)),
    INTERNAL("play.internal", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
}

/** Facts shown in the error card's detail line. All strings are already redacted (hosts only, no paths/queries). */
data class ErrorDetail(
    val httpStatus: Int? = null,
    val host: String? = null,
    /** e.g. "video/hevc Main10" or "audio/mpeg-L2". */
    val codec: String? = null,
    val decoderName: String? = null,
    /** Connection-limit hint from the freshest account card (or the local broker). */
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    /** Raw Media3 PlaybackException.errorCode, for diagnostics. */
    val media3ErrorCode: Int? = null,
    /** Stable FailureSignal name, for diagnostics. */
    val signal: String? = null,
)

data class RetryInfo(val attemptsMade: Int, val maxAttempts: Int, val nextAttemptInMs: Long?)

/** A user-facing failure. [actions] is [code].defaultSteps filtered by availability (e.g. TRY_BACKUP only with a backup). */
data class PlaybackError(
    val code: PlaybackErrorCode,
    val detail: ErrorDetail = ErrorDetail(),
    val actions: List<NextStep> = code.defaultSteps,
    val retry: RetryInfo? = null,
)

/** Non-fatal conditions shown while playback continues (a chip/toast in M4). */
enum class PlaybackWarning { NO_AUDIO, AUDIO_UNSUPPORTED, SOFTWARE_VIDEO, PASSTHROUGH_DISABLED }

/** Where an HTTP failure happened. */
enum class LoadOrigin { PROGRESSIVE, HLS_MANIFEST, HLS_SEGMENT, ARCHIVE, UNKNOWN }

enum class NetworkFailureKind { DNS, CONNECT, TIMEOUT, TLS, RESET, CLEARTEXT, OTHER }

enum class StallKind { NO_BYTES, DECODER_STUCK, POSITION_STUCK, FROZEN_VIDEO, AUDIO_NOT_STARTED, AUDIO_UNDERRUN_STORM, HLS_NO_PROGRESS }

/** Classified failure (output of :player's FailureClassifier, input of the ladder). §4.1. */
sealed interface FailureSignal {
    val name: String get() = this::class.simpleName ?: "Unknown"

    data class Http(val status: Int, val origin: LoadOrigin) : FailureSignal
    data class Network(val kind: NetworkFailureKind, val beforeFirstByte: Boolean) : FailureSignal
    /** LiveTsDataSource gave up after its in-source reconnects. [lastStatus] is the last HTTP status seen, if any. */
    data class ReconnectExhausted(val lastStatus: Int?, val noBytes: Boolean) : FailureSignal
    data object HtmlInsteadOfStream : FailureSignal
    data class TinyBody(val bytes: Int) : FailureSignal
    data object FormatIsHls : FailureSignal
    data object FormatUnrecognized : FailureSignal
    data class PcrDiscontinuity(val deltaMs: Long) : FailureSignal
    data object BehindLiveWindow : FailureSignal
    data class Parsing(val manifest: Boolean) : FailureSignal
    data class DecoderInit(val video: Boolean, val mimeType: String?, val decoderName: String?) : FailureSignal
    data class DecodingFailed(val video: Boolean, val mimeType: String?) : FailureSignal
    data class FormatUnsupported(val video: Boolean, val mimeType: String?) : FailureSignal
    data class AudioTrackInit(val encoded: Boolean, val channelCount: Int, val mimeType: String?) : FailureSignal
    data object AudioWrite : FailureSignal
    data object ResourcesReclaimed : FailureSignal
    /** Media3 StuckPlayerException (built-in detector). */
    data class Stuck(val type: Int) : FailureSignal
    /** One of our watchdog detectors fired (§3.7). */
    data class Stall(val kind: StallKind) : FailureSignal
    /** STATE_ENDED on a live item. */
    data object LiveEnded : FailureSignal
    data class Credentials(val permanent: Boolean) : FailureSignal
    /** The local broker refused a lease (another lease kind holds the slots). */
    data class LocalConnectionLimit(val active: Int, val max: Int) : FailureSignal
    data object ChannelMissing : FailureSignal
    data class Unknown(val media3ErrorCode: Int) : FailureSignal
}

/** What the controller does next (output of the ladder). §4.3. */
sealed interface LadderDecision {
    /** Re-open the same resolved stream (fresh session and lease, same URL template expansion). */
    data class RetrySame(val delayMs: Long) : LadderDecision
    /** Full re-resolve from the template (fresh token/redirect), optionally refreshing the account and rotating the base URL. */
    data class ReResolve(val delayMs: Long, val refreshAccount: Boolean = false, val rotateBase: Boolean = false) : LadderDecision
    data class FlipXtreamOutput(val to: XtreamOutput) : LadderDecision
    /** Rebuild the MediaSource as another type for the same URL (sniffed HLS behind a .ts URL, generic progressive). */
    data class SwitchSourceType(val to: StreamFormat) : LadderDecision
    data object SeekToLiveAndPrepare : LadderDecision
    /** New session for the same resolved stream, optionally raising the channel's TS compatibility level. */
    data class Reprepare(val raiseTsCompat: Boolean = false) : LadderDecision
    data object DisableTunnelingAndReprepare : LadderDecision
    data object SoftwareVideoAndReprepare : LadderDecision
    data class DisablePassthroughAndReprepare(val mimeType: String?) : LadderDecision
    data class SoftwareAudioAndReprepare(val mimeType: String) : LadderDecision
    data object StereoAndReprepare : LadderDecision
    /** Continue without audio (warning), e.g. no decoder for the audio codec. */
    data class DropAudioAndReprepare(val warning: PlaybackWarning) : LadderDecision
    data object UserAgentFallback : LadderDecision
    data object TryBackupSource : LadderDecision
    data class Fail(val error: PlaybackError) : LadderDecision
}

// ------------------------------------------------------------------ buffer, viewport, AFR, decoders

enum class BufferMode { AUTO, SMALL, MEDIUM, LARGE, EXTRA_LARGE, NONE }   // NONE: amended 2026-10-14 (user request), §3.6.1

/** Effective LoadControl thresholds. All in milliseconds except [targetBytes]. */
data class BufferProfile(
    val startMs: Int,
    val afterRebufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBytes: Int,
    /** Auto level 0..3, or -1 for a manual mode. */
    val level: Int,
)

enum class Viewport { NONE, MINI, FULL, HIDDEN }
enum class AfrMode { OFF, SEAMLESS, ALWAYS }
enum class HdrPreviewMode { POSTER, ALLOW }
enum class HdrKind { SDR, HDR10, HLG, DOLBY_VISION, UNKNOWN_HDR }
enum class AspectMode { FIT, FILL, ZOOM, RATIO_16_9, RATIO_4_3 }
enum class VideoDecoderMode { AUTO, HARDWARE_ONLY, SOFTWARE_PREFERRED }
/** Product §4.4 "Audio output". */
enum class AudioOutputMode { AUTO, ALWAYS_DECODE, STEREO }

// ------------------------------------------------------------------ broker

enum class LeaseKind { PLAYBACK, PREVIEW, RECORDING, PROBE }

/** Something that can abort an in-flight upstream connection synchronously (an OkHttp Call in :player). */
fun interface ConnectionHandle { fun cancel() }

data class BrokerSnapshot(val brokerKey: String, val active: Int, val closing: Int, val max: Int, val waiting: Int, val byKind: Map<LeaseKind, Int>)

/** Thrown by a lease acquire when the local limit is held by leases that may not be pre-empted. */
class ConnectionLimitException(val active: Int, val max: Int) : RuntimeException("local connection limit $active/$max")
```

### 2.2 `core/.../playback/PlaybackLogic.kt` (commit 0)

Interfaces of the pure components. Implementations are `*Impl` in the sub-packages of §1.4/§1.5 and are constructed directly (no DI in `:core`).

```kotlin
package app.thebox.core.playback

import app.thebox.core.refresh.ChannelIdentity

// ------------------------------------------------------------------ resolution (WP-A, §3.2)

/** Pure resolution. Throws nothing: an impossible input yields [ResolveOutcome.Failed]. */
interface StreamUrlBuilder {
    fun build(input: ResolveInput): ResolveOutcome
}

sealed interface ResolveOutcome {
    data class Ok(val stream: ResolvedStream) : ResolveOutcome
    /** e.g. an Xtream template with no credentials, or a `{{u}}` template on a playlist without a username. */
    data class Failed(val code: PlaybackErrorCode, val reason: String) : ResolveOutcome
}

/**
 * Template expansion in the M2 order (§3.2.4): non-secret placeholders first, then credentials, each credential
 * encoded for its position (path segment before '?', query value after). Single pass; substituted values are never
 * re-scanned. Unknown `{{x}}` placeholders are left as written.
 */
interface CredentialExpander {
    fun expandNonSecret(template: String, base: String?, extension: String?): String
    fun expandCredentials(template: String, username: String?, password: String?): String
    /** Header values: credentials substituted raw (no URL encoding). */
    fun expandHeaderValue(value: String, username: String?, password: String?): String
}

// ------------------------------------------------------------------ broker (WP-A, §3.5)

interface ConnectionBrokerCore {
    val key: String
    fun configure(maxConnections: Int)
    /** Cancels every handle of ACTIVE PLAYBACK leases (synchronously) and marks them CLOSING. Returns how many. */
    fun preemptPlayback(reason: String): Int
    /**
     * Blocking acquire for [kind] on behalf of tune [ownerTuneId]. Pre-empts another owner's PLAYBACK lease when
     * needed, waits up to [waitMs] for CLOSING leases to release, then grants. Throws [ConnectionLimitException]
     * when the slots are held by leases this kind may not pre-empt. Thread-safe; called on loader threads.
     */
    fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease
    fun snapshot(): BrokerSnapshot
}

interface CoreLease {
    val id: Long
    val kind: LeaseKind
    val ownerTuneId: Long
    /** True once pre-empted: the holder must stop reconnecting and close. */
    val isPreempted: Boolean
    /** Registers an in-flight connection. Returns false (and the caller must cancel it) if the lease is already pre-empted. */
    fun attach(handle: ConnectionHandle): Boolean
    fun detach(handle: ConnectionHandle)
    /** Idempotent. Call only after every attached connection is cancelled/closed. */
    fun release()
}

/** Leading-edge + trailing coalescer for zaps (§3.5.4). Pure; the controller drives it with a clock and a scheduler. */
interface ZapDebouncer {
    /** Returns IMMEDIATE (tune now) or DEFERRED (a trailing tune is (re)scheduled at [ZapPlan.fireAtMs]). */
    fun onZap(nowMs: Long, isRepeat: Boolean): ZapPlan
    fun onFired()
    fun reset()
}
data class ZapPlan(val immediate: Boolean, val fireAtMs: Long)

// ------------------------------------------------------------------ errors (WP-A, §4)

data class LadderContext(
    val nowMs: Long,
    val msSinceTuneStart: Long,
    /**
     * The "first media" milestone of §0.16 (reviewed 2026-10-13, B2): first rendered video frame when a video track is
     * selected and enabled, else the first onAudioPositionAdvancing (radio, HDR poster, HIDDEN).
     */
    val firstFrameRendered: Boolean,
    val isLive: Boolean,
    val isArchive: Boolean,
    val format: StreamFormat,
    val isXtream: Boolean,
    val baseCount: Int,
    val output: XtreamOutput?,
    /** Attempt counters for the current tune cycle (reset on tune(), on 30 s of healthy playback, §4.5). */
    val counters: LadderCounters,
    val reResolveAllowed: Boolean,
    val reconnectAttemptsSetting: Int,
    val tunnelingActive: Boolean,
    val passthroughActive: Boolean,
    val passthroughMime: String?,
    val audioChannelCount: Int,
    val videoDecoderMode: VideoDecoderMode,
    val softwareVideoAvailable: Boolean,
    val ffmpegSupportsMime: (String) -> Boolean,
    val uaFallbackAvailable: Boolean,
    val backupAvailable: Boolean,
    val tsCompatLevel: Int,
    /** Fresh account facts when the ladder asked for a refresh, else the cached card. */
    val accountActive: Int?,
    val accountMax: Int?,
    val accountStatus: AccountGate,
    val lastHost: String?,
)

enum class AccountGate { UNKNOWN, ACTIVE, EXPIRED, DISABLED, AUTH_FAILED }

data class LadderCounters(
    val limitSteps: Int = 0,
    val forbiddenSteps: Int = 0,
    val formatFlips: Int = 0,
    val sourceTypeSwitches: Int = 0,
    val transientRetries: Int = 0,
    val baseRotations: Int = 0,
    val pcrResets: Int = 0,
    val behindLiveWindow: Int = 0,
    val parsingRetries: Int = 0,
    val decoderSteps: Int = 0,
    val audioSteps: Int = 0,
    val stallRecoveries: Int = 0,
    val unknownRetries: Int = 0,
    val uaFallbackTried: Boolean = false,
)

interface ErrorLadder {
    /** Pure. Also returns the counters to store for the next failure in this cycle. */
    fun decide(signal: FailureSignal, ctx: LadderContext): Pair<LadderDecision, LadderCounters>
}

/** At most [maxPerWindow] re-resolves per [windowMs] per channel (§4.5). */
interface ReResolveLimiter {
    fun tryAcquire(key: ChannelIdentity?, nowMs: Long): Boolean
}

/** Loader-level retry rules (§4.2), shared by WP-B's LoadErrorHandlingPolicy. Returns null for "do not retry". */
interface LoadRetryRules {
    fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long?
}

// ------------------------------------------------------------------ buffer (WP-A, §3.6)

interface BufferProfileFsm {
    /** Profile for the next tune of [playlistKey] under [mode] (a per-channel BufferMode override wins). */
    fun profileFor(playlistKey: String, mode: BufferMode, lowRam: Boolean): BufferProfile
    /** A rebuffer (READY → BUFFERING not caused by tune/seek/re-prepare). Returns the new profile when the level changed. */
    fun onRebuffer(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile?
    /** Called once a minute while playing without a rebuffer. Returns the new profile when the level decayed. */
    fun onStableTick(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile?
}

// ------------------------------------------------------------------ stall detection (WP-A, §3.7)

/** One watchdog sample. Filled in place by :player once a second (no allocation per tick). */
class WatchdogSample {
    var nowMs = 0L
    var playbackState = 0               // Player.STATE_* values
    var playWhenReady = false
    var isPlaying = false
    var suppressed = false              // playbackSuppressionReason != NONE
    var isLive = true
    var positionMs = 0L
    var bufferedDurationMs = 0L
    var bytesTotal = 0L                 // monotonic, from telemetry
    var lastByteAgeMs = 0L
    var inDataSourceReconnect = false
    var isHls = false
    var videoExpected = false           // video track selected, surface attached, viewport MINI/FULL, not poster
    var firstFrameRendered = false
    var videoRenderedCount = 0L
    var videoDroppedCount = 0L
    var tunneling = false
    var audioExpected = false           // audio track selected and not disabled
    var audioAdvancing = false          // onAudioPositionAdvancing seen since the audio renderer (re)started
    var msSinceFirstFrame = 0L
    /** (reviewed 2026-10-13, S11) Time since isPlaying last became true; d1 measures from max(first frame, this). */
    var msSincePlayStart = 0L
    var audioUnderrunsLast3s = 0
    var audioPassthrough = false
    var msSinceSeekOrPrepare = 0L
}

data class StallVerdict(val kind: StallKind, val evidence: String)

interface StallDetectors {
    /** Feed one sample; returns a verdict when a detector fires (at most one per call, cool-downs applied). */
    fun onSample(s: WatchdogSample): StallVerdict?
    /** Reset baselines (tune, seek, re-prepare, viewport/track change). */
    fun reset(nowMs: Long)
}

// ------------------------------------------------------------------ TS (WP-B, §3.3)

enum class SniffResult { TS, HLS_PLAYLIST, HTML, TINY, UNRECOGNIZED, NEED_MORE }

interface StreamSniffer {
    /** Classify the first [length] bytes. [endOfStream] = no more bytes will come. [tsOffset] valid when TS. */
    fun sniff(buf: ByteArray, length: Int, endOfStream: Boolean): Sniff
}
data class Sniff(val result: SniffResult, val tsOffset: Int = 0)

/**
 * Accepts arbitrary byte runs and releases only whole 188-byte packets that start with 0x47.
 * After [resync] it drops bytes until three consecutive sync bytes are seen again.
 * (reviewed 2026-10-13, B1) After [resync] it also forces a continuity-counter gap on every PID: the first packet with
 * payload of each PID gets CC = (last CC emitted on that PID + 2) & 0xF, and every later packet of that PID
 * (adaptation-only packets included, so they keep repeating the last payload CC as ISO 13818-1 requires) is shifted by
 * the same offset (mod 16) for the rest of the session. TsExtractor checks CCs on payload packets only. Without this, TsExtractor drops the first packet as a "duplicate" (1 in 16) or glues the new PES onto the
 * cut one (1 in 16) per PID per splice.
 * Packets written after [resync] are held back (not readable) until [releaseHeld] is called or [heldBytes] exceeds the
 * caller's bound, so the PCR verdict comes before any post-splice sample reaches the extractor.
 */
interface TsPacketAligner {
    /** Appends [len] bytes; returns how many bytes of whole packets are now available. */
    fun write(src: ByteArray, off: Int, len: Int): Int
    /** Copies up to [len] bytes of whole packets (never a partial packet unless [len] < 188). */
    fun read(dst: ByteArray, off: Int, len: Int): Int
    fun available(): Int
    /** Drop any partial packet and require a fresh 3-sync lock (called on reconnect). Returns bytes dropped. */
    fun resync(): Int
    /** (reviewed 2026-10-13, S1) Bytes written since the last [resync] that are not yet readable. */
    fun heldBytes(): Int
    /** Make the held packets readable (PCR verdict "no jump", or no PCR within the hold bound). */
    fun releaseHeld()
    /** Drop the held packets (PCR verdict "jump": they carry the new timestamps). */
    fun dropHeld()
}

interface PcrTracker {
    /** Inspect one whole packet at [off] (cheap header bit tests). */
    fun onPacket(pkt: ByteArray, off: Int, nowMs: Long)
    /** Mark a reconnect at [nowMs]: the next PCR on the PCR PID is checked against the extrapolated value. */
    fun markReconnect(nowMs: Long)
    /** Non-null once the first post-reconnect PCR was seen and deviates by more than the tolerance (signed ms). */
    fun pendingJumpMs(): Long?
    /** (reviewed 2026-10-13, S1) True after [markReconnect] until the first PCR on the PCR PID is seen. */
    fun awaitingPcr(): Boolean
}

// ------------------------------------------------------------------ AFR (WP-A, §3.9)

/** A display mode as data (built by WP-C from android.view.Display.Mode). */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

interface DisplayModePicker {
    /** Snap a measured or declared frame rate to a broadcast/film rate, or null when none is within tolerance. */
    fun snapFps(fps: Float): Float?
    /** Mode to request for [fps] keeping the current resolution, or null when the current mode already fits / none fits. */
    fun pick(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Float): DisplayModeInfo?
}

// ------------------------------------------------------------------ surface maths (WP-A, §3.11)

data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class VideoPlacement(val videoRect: RectPx, val cropToFill: Boolean)

interface VideoRectCalculator {
    /** [pixelRatio] = VideoSize.pixelWidthHeightRatio. Never returns a rect larger than [hole]. */
    fun place(hole: RectPx, videoWidth: Int, videoHeight: Int, pixelRatio: Float, mode: AspectMode): VideoPlacement
}

// ------------------------------------------------------------------ diagnostics (WP-A, §3.13)

enum class PlaybackEventType {
    TUNE, RESOLVED, LEASE, OPEN, FIRST_BYTE, SNIFF, READY, FIRST_FRAME, REBUFFER, RECONNECT, RECONNECT_OK, NO_BYTES,
    PCR_JUMP, ERROR, DECISION, STALL, AUDIO_FALLBACK, VIDEO_FALLBACK, BUFFER_LEVEL, AFR, LIFECYCLE, STOP,
}

/** [detail] is passed through Redact.text and cut to 160 characters by the log, never by the caller. */
data class PlaybackEvent(val wallMs: Long, val elapsedMs: Long, val tuneId: Long, val type: PlaybackEventType, val code: String, val detail: String)

data class ZapRecord(
    val tuneId: Long,
    val stableKey: String?,
    val format: StreamFormat,
    val resolveMs: Long,
    val leaseMs: Long,
    val firstByteMs: Long?,
    val readyMs: Long?,
    val firstFrameMs: Long?,
    val outcome: String,
)

interface PlaybackEventLog {
    fun add(type: PlaybackEventType, tuneId: Long, code: String, detail: String = "")
    fun addZap(record: ZapRecord)
    fun events(): List<PlaybackEvent>
    fun zaps(): List<ZapRecord>
}
```

### 2.3 `player/.../api/PlaybackApi.kt` (commit 0)

Everything M3/M4 use. All methods are `@MainThread`; flows may be collected anywhere.

```kotlin
package app.thebox.player.api

import androidx.media3.common.Player
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.playback.*
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The one playback engine of the app (plan §3 "Process"). Implemented by WP-D's PlaybackControllerImpl. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    /** The channel a debounced zap will tune, for the banner; null when no zap is pending. */
    val pendingZap: StateFlow<ChannelIdentity?>
    val video: StateFlow<VideoInfo>
    val tracks: StateFlow<TrackInfo>
    /** Updated at ≤ 2 Hz while [setStatsEnabled] is true; otherwise frozen at the last value. */
    val stats: StateFlow<StreamStats>
    /** Toast-level events (replay 0, buffer 8, DROP_OLDEST). */
    val notices: SharedFlow<PlaybackNotice>
    /** The UI layer sets FLAG_KEEP_SCREEN_ON from this (§3.10.6). */
    val keepScreenOn: StateFlow<Boolean>
    /** API < 31 AFR: the window's preferredDisplayModeId (0 = system default). Applied by WindowDisplayModeBinder. */
    val displayModeRequest: StateFlow<Int>
    /** Give this to media3-ui-compose PlayerSurface and nothing else: it exposes only the video-surface commands. */
    val surfacePlayer: Player
    val diagnostics: PlaybackDiagnostics

    /** Tune now (no debounce). [zapList] becomes the CH±/Up/Down list when non-null. */
    fun tune(target: TuneTarget, zapList: ZapList? = null)
    /** ±n within the current zap list. Repeats and presses within 350 ms are coalesced (§3.5.4). */
    fun zap(delta: Int, isRepeat: Boolean = false)
    fun setZapList(list: ZapList)
    /** Tunes the previously played channel (KEYCODE_LAST_CHANNEL). False when there is none. */
    fun previousChannel(): Boolean
    /** Archive seam (§3.14): plays one ArchiveRequest built by CatchupUrlBuilder. ArchivePlayback proper is M4. */
    fun tuneArchive(tune: ArchiveTune)
    /** Native seek inside the current archive request only. No-op on live. */
    fun seekTo(positionMs: Long)
    fun position(): PositionSnapshot
    /** Live: freeze now, release the connection after 10 s; resume() re-tunes at the live edge. Archive: real pause. */
    fun pause()
    fun resume()
    fun stop(reason: StopReason = StopReason.USER)
    /** Error card "Retry": restart the current target with a fresh ladder cycle. */
    fun retry()
    fun setViewport(viewport: Viewport)
    fun setStatsEnabled(enabled: Boolean)
    fun selectAudioTrack(track: TrackRef?, remember: Boolean = true)
    /** null = subtitles off. */
    fun selectTextTrack(track: TrackRef?, remember: Boolean = true)
    fun setAspectMode(mode: AspectMode, remember: Boolean = true)
    /** null = follow the global setting. Takes effect with a re-prepare of the current channel. */
    fun setVideoDecoderOverride(mode: VideoDecoderMode?, remember: Boolean = true)
    /** Creates the player and warms the HTTP pool (startup path, architecture review C9). */
    fun prewarm()
}

sealed interface TuneTarget {
    data class Channel(val identity: ChannelIdentity) : TuneTarget
    /** Debug harness and external "play this URL" intents. Never persisted. [brokerKey] groups connection limits. */
    data class DirectUrl(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val maxConnections: Int = 1,
        val formatHint: StreamFormat = StreamFormat.UNKNOWN,
    ) : TuneTarget {
        override fun toString(): String = "DirectUrl(${app.thebox.core.common.Redact.text(url)})"
    }
}

/** Ordered channel list for zapping, supplied by the UI (group, favourites, search results). */
interface ZapList {
    val id: String
    /** The channel [delta] steps from [from], wrapping; null when the list is empty. Skips hidden/locked channels (the list does). */
    suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity?
}

/** [request] comes straight from CatchupUrlBuilder.build (credentials still templated; M2 expands them last). */
data class ArchiveTune(val identity: ChannelIdentity, val request: ArchiveRequest, val startOffsetMs: Long = 0)

data class PositionSnapshot(val positionMs: Long, val bufferedMs: Long, val durationMs: Long?, val isSeekable: Boolean, val archiveStartUtcMs: Long?)

enum class StopReason { USER, BACKGROUND, STANDBY, HDMI_UNPLUGGED, DISPLAY_OFF, LIVE_PAUSE_TIMEOUT, ERROR, RELEASE }

/** What is on screen. [tuneId] increases with every tune/retry. */
data class NowPlaying(
    val tuneId: Long,
    val identity: ChannelIdentity?,
    val title: String?,
    val isArchive: Boolean,
    val format: StreamFormat,
)

enum class ConnectPhase { RESOLVING, WAITING_FOR_SLOT, CONNECTING, BUFFERING_INITIAL, SWITCHING_DISPLAY_MODE }
enum class BufferingCause { REBUFFER, RECONNECTING, RECOVERING }

data class ReconnectProgress(val attempt: Int, val maxAttempts: Int)

sealed interface PlaybackState {
    val target: NowPlaying?

    data object Idle : PlaybackState { override val target: NowPlaying? get() = null }
    data class Connecting(override val target: NowPlaying, val phase: ConnectPhase, val attempt: Int, val sinceElapsedMs: Long) : PlaybackState
    data class Buffering(override val target: NowPlaying, val cause: BufferingCause, val reconnect: ReconnectProgress?) : PlaybackState
    data class Playing(override val target: NowPlaying, val paused: Boolean = false, val warning: PlaybackWarning? = null) : PlaybackState
    /** A detector or recoverable error fired and the ladder is acting ([step] is the LadderDecision name). */
    data class Stalled(override val target: NowPlaying, val kind: StallKind?, val step: String, val attempt: Int, val maxAttempts: Int) : PlaybackState
    data class Stopped(override val target: NowPlaying?, val reason: StopReason, val resumable: Boolean) : PlaybackState
    data class Error(override val target: NowPlaying, val error: PlaybackError) : PlaybackState
}

data class VideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    val pixelRatio: Float = 1f,
    /** Snapped fps, or null while unknown. */
    val fps: Float? = null,
    val hdr: HdrKind = HdrKind.SDR,
    /** True while the HDR poster rule keeps the video track disabled (M3 shows the poster). */
    val posterMode: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val interlacedHint: Boolean = false,
)

/** Stable within one tune: [groupId] is TrackGroup.id, [index] the track index in the group. */
data class TrackRef(val type: Int, val groupId: String, val index: Int)

data class AudioTrackOption(
    val ref: TrackRef,
    val language: String?,
    val label: String?,
    /** "E-AC-3", "AC-3", "AAC", "MP2", … */
    val codec: String,
    val channels: Int,
    val describesVideo: Boolean,
    val selected: Boolean,
    val supported: Boolean,
)

data class TextTrackOption(val ref: TrackRef, val language: String?, val label: String?, val kind: String, val selected: Boolean)

data class TrackInfo(
    val audio: List<AudioTrackOption> = emptyList(),
    val text: List<TextTrackOption> = emptyList(),
    val textEnabled: Boolean = false,
)

data class StreamStats(
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val videoDecoder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val hdr: HdrKind = HdrKind.SDR,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
    val audioDecoder: String? = null,
    val audioPassthrough: Boolean = false,
    /** Measured over the last 2 s at the data source (bits/s). Media3's bandwidth meter does not report on endless loads. */
    val streamBitrateBps: Long = 0,
    val bufferMs: Long = 0,
    val droppedFrames: Long = 0,
    val httpStatus: Int? = null,
    /** Final host after redirects (host[:port] only). */
    val host: String? = null,
    val redirects: Int = 0,
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val reconnects: Int = 0,
    val broker: BrokerSnapshot? = null,
    val bufferProfile: BufferProfile? = null,
    val afr: AfrMode = AfrMode.SEAMLESS,
    val displayRefreshHz: Float? = null,
    val tunneling: Boolean = false,
    val updatedAtElapsedMs: Long = 0,
)

sealed interface PlaybackNotice {
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : PlaybackNotice
    data object Reconnected : PlaybackNotice
    data class SwitchedToBackupServer(val baseIndex: Int) : PlaybackNotice
    data class SwitchedFormat(val to: StreamFormat) : PlaybackNotice
    data class AudioDecoderSwitched(val toSoftware: Boolean, val mimeType: String) : PlaybackNotice
    data class PassthroughDisabled(val mimeType: String?) : PlaybackNotice
    data object VideoDecoderSwitchedToSoftware : PlaybackNotice
    data object TunnelingDisabled : PlaybackNotice
    data class UserAgentSwitched(val preset: String) : PlaybackNotice
    data class BufferIncreased(val level: Int) : PlaybackNotice
    /** Live resumed after pause (no time-shift on this channel). */
    data object LiveResumed : PlaybackNotice
    data class Warning(val warning: PlaybackWarning) : PlaybackNotice
}

interface PlaybackDiagnostics {
    fun events(): List<PlaybackEvent>
    fun zaps(): List<ZapRecord>
    /** Device capability dump for S16 (decoders, HDR, display modes, audio encodings, FFmpeg). */
    suspend fun capabilities(): Map<String, String>
}
```

### 2.4 `player/.../api/PlaybackProviders.kt` (commit 0)

#### 2.4.1 Contracts

```kotlin
package app.thebox.player.api

import app.thebox.core.common.Outcome
import app.thebox.core.playback.*
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Data the player reads. Bound in :app over the M1 repositories (§2.4.2). All calls are main-safe (impl switches to IO). */
interface PlaybackSourceProvider {
    /** null when the channel no longer exists (purged) → CHANNEL_NOT_FOUND. Parental filtering is the UI's job, not this one's. */
    suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo?
    suspend fun playlist(playlistId: Long): PlaylistPlaybackInfo?
    /** Ok(null): the playlist has no credentials (plain M3U). Err(SourceError.Credentials(…)) as in M1 §3.9. */
    suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?>
    /** Cached account card (xtream_account.info_json); null for plain M3U. */
    suspend fun account(playlistId: Long): XtreamAccountInfo?
    /**
     * Logs in now (player_api.php, Xtream and Xtream-shaped M3U), stores the card and returns it. The implementation
     * coalesces calls: at most one login per playlist per 10 s (ACCOUNT_REFRESH_MIN_INTERVAL_MS), later callers get the
     * cached result.
     */
    suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo>
    suspend fun saveActiveBaseIndex(playlistId: Long, index: Int)
    suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?)
    /** v1.1 failover (stream_source). v1 implementations return an empty list. */
    suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity>
}

/** Per-channel playback memory (channel_prefs, keyed by (playlist_id, stable_key)). */
data class ChannelPlaybackMemory(
    val audioLanguage: String? = null,
    /** null = follow settings; "" = the user turned subtitles off on this channel. */
    val subtitleLanguage: String? = null,
    val aspect: AspectMode? = null,
    val videoDecoder: VideoDecoderMode? = null,
    val bufferMode: BufferMode? = null,
    /** Learned TS compatibility level 0..1 (§3.8.5). */
    val tsCompatLevel: Int = 0,
    /** Last tune saw HDR video: pre-disable video in the MINI viewport (§3.9.5). */
    val knownHdr: Boolean = false,
    /** Last snapped fps × 1000 (AFR pre-switch, §3.9.3). */
    val fpsMilli: Int? = null,
)

interface ChannelMemory {
    suspend fun read(identity: ChannelIdentity): ChannelPlaybackMemory
    /** Serialised read-modify-write (fixes M1 code review Later 6 for these columns). */
    suspend fun update(identity: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory)
    /** channel_prefs.last_watched_at / watch_count (startup "last channel"). */
    suspend fun recordWatched(identity: ChannelIdentity)
}

/** Global playback settings (product §4.4, §4.9). Per-playlist values come from PlaylistPlaybackInfo. */
data class PlaybackSettings(
    val bufferMode: BufferMode = BufferMode.AUTO,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    /** Media3 FFmpeg audio extension in the renderer list (EXTENSION_RENDERER_MODE_ON vs OFF). Changing it rebuilds the player. */
    val softwareAudioFallback: Boolean = true,
    /** Prefer FFmpeg for every MIME type it supports (EXTENSION_RENDERER_MODE_PREFER semantics, applied per MIME at runtime). */
    val preferSoftwareAudio: Boolean = false,
    val audioOutput: AudioOutputMode = AudioOutputMode.AUTO,
    val tunneling: Boolean = false,
    val afrMode: AfrMode = AfrMode.SEAMLESS,
    val hdrInPreview: HdrPreviewMode = HdrPreviewMode.POSTER,
    /** ISO 639 codes, most preferred first (max 3). Empty = device locale. */
    val preferredAudioLanguages: List<String> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val preferredTextLanguages: List<String> = emptyList(),
    val defaultAspect: AspectMode = AspectMode.FIT,
    /** 3, 5, 10, or -1 = unlimited (product §4.4). */
    val reconnectAttempts: Int = 5,
    /** No first media (§0.16: first frame, or first audio position for audio-only/poster/hidden) within this after tune() → error card (product/protocol "stream switch timeout"). */
    val streamSwitchTimeoutMs: Long = 15_000,
    /** One-shot retry with the VLC preset UA on a 403 that is not a connection limit (§4.3 L5). */
    val autoUserAgentFallback: Boolean = true,
    val userAgentFallbackPreset: String = "VLC/3.0.20 LibVLC/3.0.20",
    /** Sent when neither the channel nor the playlist sets a UA. :app passes HttpModule.DEFAULT_USER_AGENT. */
    val defaultUserAgent: String = "TheBox/1.0 (Android TV)",
    /** v1.1 failover toggle; read by the ladder (TryBackupSource). */
    val failoverToBackup: Boolean = true,
    /** Product §4.4 "After standby over 15 min": true = resume playback, false = guide with nothing playing. */
    val resumeAfterStandby: Boolean = true,
    /** Test hook: compresses lifecycle timers in instrumented tests. 1.0 in production. */
    val lifecycleTimeScale: Float = 1.0f,
)

interface PlaybackSettingsSource {
    val settings: StateFlow<PlaybackSettings>
    val current: PlaybackSettings get() = settings.value
    fun update(transform: (PlaybackSettings) -> PlaybackSettings)
}

/** In-memory default (M2), like M1's InMemoryDataSettings. M5 binds a DataStore-backed implementation. */
@Singleton
class InMemoryPlaybackSettings @Inject constructor() : PlaybackSettingsSource {
    private val state = MutableStateFlow(PlaybackSettings())
    override val settings: StateFlow<PlaybackSettings> = state
    override fun update(transform: (PlaybackSettings) -> PlaybackSettings) = state.update(transform)
}
```

#### 2.4.2 Bindings in `:app` (WP-D, `PlaybackBindingsModule`)

| Interface | Implementation | Backed by |
|---|---|---|
| `PlaybackSourceProvider` | `DataPlaybackSourceProvider` | `ChannelRepository.get` (→ `ChannelStreamInfo`, reading `container_ext` from the row), `PlaylistRepository.get/secret/account`, `PlaylistDao.setActiveBaseIndex` and the new `setXtreamOutputLearned` (reviewed 2026-10-13, S10: never the whole-row `PlaylistRepository.update`, which races with refresh; `baseUrls` is empty for M3U because `PlaylistSecret.urls[0]` is then the secret M3U URL), `XtreamApi.login` + `PlaylistRepository.saveAccount` for `refreshAccount`, `stream_source` (empty in v1). `PlaylistSecret` → `StreamCredentials` (urls, username, password, headers) |
| `ChannelMemory` | `RoomChannelMemory` | `ChannelPrefsRepository.get/recordWatched` plus the column-targeted upserts of §2.10 (reviewed 2026-10-13, S10: `edit` is a whole-row read-modify-write without a lock and would revert a concurrent `setHidden`/`setLocked`; the Mutex only serialises `RoomChannelMemory`'s own writes). Columns: `audio_lang`, `subtitle_lang`, `aspect_mode` (`AspectMode.name`), `video_decoder` (`VideoDecoderMode.name`), `buffer_profile` (`BufferMode.name`), `playback_flags` (bits 0–1 `tsCompatLevel`, bit 2 `knownHdr`), `fps_milli` |
| `PlaybackSettingsSource` | `InMemoryPlaybackSettings` | in memory; `defaultUserAgent` initialised from `HttpModule.DEFAULT_USER_AGENT` at app start |
| `ImportGate` | `ZapImportGate` (from `:player`) | `TheBoxApplication.onCreate` sets `importMutex.gate = zapImportGate` (M1 amended §1.6) |

Parental rule: `DataPlaybackSourceProvider.channel()` does **not** filter locked channels (a tune by identity comes from a UI that already applied the PIN), but `ZapList` implementations built by M3 must come from the parental-filtered queries (architecture review C7/C8).

### 2.5 `player/.../internal/Seams.kt` (commit 0, `internal`)

The interfaces the four WPs code against inside `:player`. Signatures are binding; bodies are the owners'.

```kotlin
package app.thebox.player.internal

import android.os.Looper
import android.view.SurfaceView
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.thebox.core.playback.*
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------- WP-A: resolver and broker

internal interface StreamUrlResolver {
    /** Provider reads + StreamUrlBuilder. Never throws; failures are ResolveOutcome.Failed. */
    suspend fun resolve(target: TuneTargetKey, baseIndex: Int? = null, output: XtreamOutput? = null, userAgentOverride: String? = null): ResolveOutcome
    /** CatchupTarget for M4 (liveUrl and headers keep {{u}}/{{p}}; xtream carries clear credentials). */
    suspend fun catchupTarget(identity: app.thebox.core.refresh.ChannelIdentity): app.thebox.core.catchup.CatchupTarget?
    /** Expands credentials in an ArchiveRequest built by CatchupUrlBuilder (the M2 "last" step, §3.14). */
    suspend fun expandArchive(identity: app.thebox.core.refresh.ChannelIdentity, request: app.thebox.core.catchup.ArchiveRequest): ResolveOutcome
}

/** Tune target without UI types: a channel identity or a direct URL. */
internal sealed interface TuneTargetKey {
    data class Channel(val identity: app.thebox.core.refresh.ChannelIdentity) : TuneTargetKey
    data class Direct(val url: String, val headers: Map<String, String>, val maxConnections: Int, val formatHint: StreamFormat) : TuneTargetKey
}

internal interface StreamBrokerRegistry {
    fun broker(key: String): ConnectionBrokerCore
    /** max = override ?: account.maxConnections ?: 1, clamped to 1..MAX_CONNECTIONS_CAP. */
    fun configure(key: String, maxConnections: Int)
}

// ---------------------------------------------------------------- WP-B: transport

/** Live counters of one session, written by data sources (any thread), read by stats/watchdog (main). */
internal interface StreamTelemetry {
    val bytesTotal: Long
    val lastByteAtMs: Long
    val firstByteAtMs: Long
    val bitrateBps: Long
    val httpStatus: Int
    val finalHost: String?
    val redirects: Int
    val inReconnect: Boolean
    val reconnects: Int
    val sniffed: StreamFormat
}

/** Everything a data source of one tune needs. Created by WP-D's TuneSession, consumed by WP-B. */
internal class StreamSessionContext(
    val tuneId: Long,
    val resolved: ResolvedStream,
    val broker: ConnectionBrokerCore,
    val leaseKind: LeaseKind,
    /** v2 recorder seam: null = NoOpDataSink. */
    val sinkFactory: DataSink.Factory?,
    /** Session events for the event log and the controller (called on loader threads; must not block). */
    val onEvent: (type: PlaybackEventType, code: String, detail: String) -> Unit,
)

internal interface TransportFactory {
    /** Progressive live TS: TeeDataSource(LiveTsDataSource core, sink). */
    fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** HLS manifests/segments and finite archive files: brokered OkHttp, Range allowed, no reconnect. */
    fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** Ends the session's transport: cancels every call, waits for close, releases the lease. Idempotent, main-safe. */
    fun endSession(tuneId: Long)
}

// ---------------------------------------------------------------- WP-C: engine

internal class PlayerBundle(
    val player: ExoPlayer,
    val trackSelector: DefaultTrackSelector,
    val loadControl: AdaptiveLoadControlHandle,
    val codecPolicy: CodecPolicyHandle,
    val audioPolicy: AudioPolicyHandle,
)

internal interface PlayerFactoryApi {
    /** Main looper. [softwareAudioFallback] chooses EXTENSION_RENDERER_MODE_ON vs OFF. */
    fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle
}

internal interface AdaptiveLoadControlHandle { var profile: BufferProfile }

internal interface CodecPolicyHandle {
    var videoMode: VideoDecoderMode
    /** MIME types for which platform decoders are hidden so the FFmpeg renderer is chosen. */
    var softwareAudioMimes: Set<String>
}

internal interface AudioPolicyHandle {
    /** Encodings (as MIME types) never sent as passthrough on the current route. "*" = all. */
    var deniedPassthrough: Set<String>
    var forceStereo: Boolean
}

internal enum class TsCompat { DEFAULT, MULTI_PMT }

internal interface MediaSourceBuilderApi {
    fun build(ctx: StreamSessionContext, format: StreamFormat, compat: TsCompat, transport: TransportFactory, archive: app.thebox.core.catchup.ArchiveRequest?): MediaSourceWithTelemetry
}
internal class MediaSourceWithTelemetry(val source: MediaSource, val telemetry: StreamTelemetry)

internal interface PlaybackWatchdogApi {
    fun start(onStall: (StallVerdict) -> Unit)
    fun stop()
    fun bind(telemetry: StreamTelemetry?)
    fun reset()
    /** Viewport/poster/track changes that make video or audio (un)expected. */
    fun setExpectations(videoExpected: Boolean, audioExpected: Boolean)
}

internal interface DisplayModeControllerApi {
    val request: StateFlow<Int>
    val refreshHz: StateFlow<Float?>
    fun attachSurfaceView(view: SurfaceView?)
    fun onTune(knownFpsMilli: Int?)
    fun onFps(fps: Float)
    fun onViewport(viewport: Viewport)
    /** Suspends playback start until the display settled (API < 31 ALWAYS path); returns immediately otherwise. */
    fun awaitSettled(onSettled: () -> Unit)
    fun clear()
}
```

### 2.6 `ui/.../playback/VideoLayer.kt` (signature commit 0; body WP-D)

```kotlin
package app.thebox.ui.playback

/**
 * The one video surface of the app (plan §3 "Video surface", architecture review B1.2). Put it first in the root Box,
 * behind all other content, with **no** ancestor that applies graphicsLayer, alpha, clip, offscreen compositing or a
 * RenderEffect. [videoRect] is in window pixels (VideoRectCalculator output); null keeps the surface attached at 1×1 px
 * in the top-left corner (viewport HIDDEN/NONE), so the decoder keeps its surface and no reconfiguration happens.
 */
@androidx.compose.runtime.Composable
fun VideoLayer(
    surfacePlayer: androidx.media3.common.Player,
    videoRect: app.thebox.core.playback.RectPx?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)
```

Body (WP-D): `PlayerSurface(player = surfacePlayer, modifier = modifier.layout { … place at videoRect … }, surfaceType = SURFACE_TYPE_SURFACE_VIEW)`. The modifier uses `Modifier.layout` with a constant-size constraint per rect; it never animates. Letterbox bars, rounded corners and the ring are painted by the M3 background layer around the hole (B1.1), not here.

(reviewed 2026-10-13, B3) `PlayerSurface` 1.11.1 does **not** clear the video view when it leaves composition with a non-null player (`PlayerSurface.kt` `PlayerSurfaceInternal`: only the `player == null` branch calls `clearVideoView`), and its `LaunchedEffect(view, player)` re-runs only when the view or the player instance changes. Because the `ExoPlayer` is a process singleton, a destroyed Activity's `SurfaceView` would stay referenced by the player (an Activity leak). `SurfacePlayer` therefore adds a `View.OnAttachStateChangeListener` to every view it is given and, on `onViewDetachedFromWindow`, calls `exo.clearVideoSurfaceView(view)` if that view is still the current one. `surfacePlayer` is one stable instance for the process (§0.2), so a recomposition never re-attaches.

### 2.7 Threading and ownership rules

| Thing | Thread | Owner |
|---|---|---|
| `ExoPlayer` calls, `Player.Listener`, `AnalyticsListener`, state flows' writers | main (application looper) | WP-D controller (calls), WP-C listeners |
| `LoadControl.shouldContinueLoading/shouldStartPlayback` | ExoPlayer playback thread | WP-C (reads `@Volatile` profile) |
| `MediaCodecSelector.getDecoderInfos`, `AudioSink.supportsFormat` | playback thread | WP-C (reads `@Volatile` sets) |
| `DataSource.open/read/close` | ExoPlayer loader threads | WP-B |
| Broker acquire/release/pre-empt | loader threads + main (pre-empt) | WP-A core is lock-based (`ReentrantLock` + `Condition`) |
| `Call.cancel()` | any thread (OkHttp is thread-safe) | broker, byte watchdog |
| Byte watchdog | one daemon thread `box-ts-watchdog` for the process | WP-B |
| Resolution, provider calls | `Dispatchers.IO` via the provider implementations; the controller launches on its main-scoped `CoroutineScope` (`SupervisorJob + Dispatchers.Main.immediate`) | WP-D |
| Detectors (`:core`) | main (1 Hz) | WP-C runs them, WP-A owns the logic |

No `runBlocking` anywhere in `:player`. Loader threads may block (that is what they are for) but never on the main thread or a coroutine.

### 2.8 Hilt graph

- `:player` `PlaybackModule` (WP-D, `@InstallIn(SingletonComponent)`): `@Binds PlaybackController → PlaybackControllerImpl` (`@Singleton`); `@Provides MonoClock`; `@Binds` the internal seams to their implementations (`StreamUrlResolverImpl`, `StreamBrokerRegistryImpl`, `TransportFactoryImpl`, `PlayerFactory`, `MediaSourceBuilder`); `@Provides PlaybackEventLog` (`@Singleton`). The `:core` logic objects are constructed in `@Provides` methods with their named constants.
- `:player` needs from the graph: `@ApplicationContext Context`, `OkHttpClient` (unqualified, from `:data`'s `HttpModule`), `PlaybackSourceProvider`, `ChannelMemory`, `PlaybackSettingsSource` (all bound in `:app`).
- Hilt `internal` bindings: `:player`'s module and the internal types are in the same Gradle module, so `@Binds internal …` compiles. Public API types only are exposed.

### 2.9 `:core` amendments (commit 0, lead)

1. **`TemplateExpander.expand`** (M1 code review Later 28): when it meets `{{`, it copies everything up to and including the next `}}` literally and continues after it. `{{u}}`/`{{p}}`/`{{base}}`/`{{ext}}` therefore survive a catch-up expansion untouched and no longer appear in `CatchupDiagnostic.UnknownPlaceholders` (today `{{u}}` is reported as the unknown token `{{u}`). Test: a source `http://h/{{u}}/{{p}}/{utc}.ts` expands `{utc}` only, with no diagnostics; a password containing `{utc}` substituted afterwards stays literal.
2. **`CatchupTarget.liveUrl` KDoc** becomes: "Live URL with `{{base}}`/`{{ext}}` expanded and `{{u}}`/`{{p}}` **still templated** (M2's `ResolvedStream.liveUrlTemplate`). `ArchiveRequest.url` therefore may contain `{{u}}`/`{{p}}`; M2 expands them after `build()` (M1 code review Later 28)." `CatchupTarget.headers` likewise. The `xc` variants keep using `target.xtream` (clear credentials, built by `XtreamUrls` with its own encoding), so their output has no templates.

### 2.10 `library.db` v2 (commit 0, lead)

Additive columns, `@AutoMigration(from = 1, to = 2)`, `LibraryDatabase.VERSION = 2`, exported `data/schemas/…/2.json`, `MigrationTestHelper` instrumented case plus a Robolectric open-and-read test (M1 §2.1.3 policy: no destructive fallback).

| Table | Column | Type | Default | Meaning |
|---|---|---|---|---|
| `channel_prefs` | `playback_flags` | `INTEGER NOT NULL` | `0` | bits 0–1: learned TS compatibility level; bit 2: last tune was HDR |
| `channel_prefs` | `fps_milli` | `INTEGER` | `NULL` | last snapped fps × 1000 |
| `playlist` | `xtream_output_learned` | `TEXT` | `NULL` | `TS`/`HLS` after a successful 405/406 flip while the setting is `AUTO`; cleared when the user changes `xtream_output` |

`UserDataRemapper` (M1 §3.3.6) moves `channel_prefs` rows whole, so the new columns follow key remaps without changes.

(reviewed 2026-10-13, S10) Details the lead must get right in commit 0:
- Entity fields: `@ColumnInfo(name = "playback_flags", defaultValue = "0") val playbackFlags: Int = 0`, `@ColumnInfo(name = "fps_milli") val fpsMilli: Int? = null`, `@ColumnInfo(name = "xtream_output_learned") val xtreamOutputLearned: String? = null`. Room's auto-migration rejects a `NOT NULL` column without a `defaultValue`. The generated migration must be exactly:
  ```sql
  ALTER TABLE `channel_prefs` ADD COLUMN `playback_flags` INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE `channel_prefs` ADD COLUMN `fps_milli` INTEGER;
  ALTER TABLE `playlist` ADD COLUMN `xtream_output_learned` TEXT;
  ```
  (no table rebuild: no index or FK changes; `channel_prefs` keeps `PRIMARY KEY(playlist_id, stable_key)` and its `ON DELETE CASCADE` FK). Review `2.json` against this.
- Column-targeted writes (new, `:data`, lead): `ChannelPrefsDao.upsertPlaybackFlags(playlistId, stableKey, flags, now)` and `upsertFpsMilli(...)` as `INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, <col>) VALUES (?, ?, 0, 0, ?, ?) ON CONFLICT(playlist_id, stable_key) DO UPDATE SET <col> = excluded.<col>, updated_at = excluded.updated_at`; `PlaylistDao.setXtreamOutputLearned(id, value)`; and `PlaylistDao.setXtreamOutput(id, value)`, which also sets `xtream_output_learned = NULL` (today nothing implements "cleared when the user changes `xtream_output`"). The audio/subtitle/aspect/decoder/buffer memory columns get the same targeted upserts.
- Learned columns are not user choices: M5's backup/export of `channel_prefs` must leave out `playback_flags` and `fps_milli`.
- The migration tests live in `:data` (Room's schema assets are wired there; `I1LibraryDatabaseTest` already has the `MigrationTestHelper` pattern), not in `:app`.

---
### 2.11 Contract amendments (integration, 2026-10-13)

All additive; defaults keep every existing implementation and fake compiling.

- (amended 2026-10-13) `ErrorLadder.beginChannel(channelKey: String?)` (default no-op; `ErrorLadderImpl` overrides) — the per-channel escalation windows (5-min compat, PCR/behind-live, reclaim) outlive a cycle's `LadderCounters`; the controller calls it on every tune with the stable key (WP-A gap 2).
- (amended 2026-10-13) `ReResolveLimiter.wouldAllow(key, nowMs)` (default `true`) and `reset(key)` (default no-op) — `LadderContext.reResolveAllowed` is filled by a peek before the decision, a slot is consumed (`tryAcquire`) only when the ladder returns `ReResolve` (format flip and UA fallback are not rate-limited), and card Retry resets the channel (WP-A gap 7, WP-D gap 1).
- (amended 2026-10-13) The stream-switch timeout reaches the ladder through `ErrorLadderImpl(streamSwitchTimeoutMs = { settings.current.streamSwitchTimeoutMs })` (provided in `PlaybackModule`), not through `LadderContext` (WP-A gap 1).
- (amended 2026-10-13) `StreamTelemetry.waitingForLease: Boolean` (default `false`; `SessionTelemetry` overrides) — drives `Connecting(WAITING_FOR_SLOT)`; it leaves on the `LEASE` event. `positionIgnoredCount`/`noBytesCount` stay diagnostics-only on `SessionTelemetry` (WP-B gap 8).
- (amended 2026-10-13) Reading `LiveStreamException` (WP-B convention, adopted): `RECONNECT_EXHAUSTED.noBytes = info.networkKind == TIMEOUT`; `LOCAL_LIMIT` active/max from the `ConnectionLimitException` in `e.suppressed` (the classifier also accepts it in the cause chain); `TINY_BODY` carries `info.bytesRead`; `PREEMPTED` is stale.
- (amended 2026-10-13) `EngineEnvironment` (the `:player` controller seam) gained `applySettings(bundle, settings)` and `observeAudioRoute(onChanged)`; `EngineParts` gained `beginSession(telemetry, format, isLive, isHls)`, `setFirstMediaListener`, `setStatsEnabled`, `applyTuneDefaults`, `onAfrModeChanged`, `setDisplaySwitchListener`, `release`, and `tracks()` became `tracks(tracks: Tracks)` — so the controller drives WP-C's real constructors and lifecycles (`PlaybackWatchdog`, `StatsCollector`, `TrackController`, `DisplayModeController.create`, `FpsEstimator`) as the WP-C hand-off describes.
- (amended 2026-10-13) Passthrough denials learned by L33 are kept per **audio route** in the controller (survive tunes) and cleared by `DeviceCapabilities.observeAudioRoute`; settings-derived switches come from `PlayerFactory.applySettings` and the ladder's learned values are unioned on top at every prepare.
- (amended 2026-10-13) `MediaSourceBuilder` throws `UnsupportedStreamFormatException` for DASH; the controller maps it to `UNSUPPORTED_SOURCE`. Load origins confirmed against WP-B's policy: live TS `PROGRESSIVE`, live HLS `HLS_MANIFEST` family, archive `ARCHIVE`; the archive URL is `ctx.resolved.url` (never the templated request).
- (amended 2026-10-13) `TsCompat.MULTI_PMT` (compat level 1) also turns `FLAG_DETECT_ACCESS_UNITS` off (`TsExtractorsFactory.flagsFor`). Media3's Javadoc says the flag "should be disabled if the stream contains access unit delimiters (AUDs)", and WP-B saw an ffmpeg (AUD-carrying) IDR split into several samples. Whether a stream has AUDs is unknown before its first PES, so the design default (flag on) stays for level 0; a channel that escalates (L27) gets slice-header detection off as part of the level. The device pass did not show a problem attributable to the flag (I-3 stalls identically with it off, §5.8).
- (amended 2026-10-13) State table §3.1.2: `Buffering(RECONNECTING)` ends on `RECONNECT_OK` (or `inReconnect` false at the next poll) when the player is still READY — an in-source reconnect usually never starves the player, so no READY callback would end it.
- (amended 2026-10-13) After an L3 `ReResolve(refreshAccount)`, a refreshed EXPIRED/DISABLED/AUTH_FAILED account runs the §4.3 G1 gate **before** the next open (device I-17: one fewer refused request).
- (amended 2026-10-13) `ZapRecord.firstByteMs` comes from `StreamTelemetry.firstByteAtMs` (WP-B emits no `FIRST_BYTE` event). `readyMs` stays null when READY follows the first frame (emulator, §6.9 finding 2).
- (amended 2026-10-13) Owner constants: the controller's `SEEK_GRACE_MS`, `LADDER_HEALTHY_RESET_MS`, `MAX_CONNECTIONS_CAP`, poll interval and `StandaloneScope.PREEMPT_RELEASE_WAIT_MS` now point at `StallConstants`, `ErrorConstants`, `BrokerConstants`, `StatsCollector.STATS_INTERVAL_MS`; the healthy reset uses `ErrorLadderImpl.countersAfterHealthy`; `max_connections` uses `BrokerConstants.effectiveMax/clamp`. `StallDetectorsImpl(StallThresholds(tsNoBytesBackstopMs = 2 × LiveTsDataSource.NO_BYTES_TIMEOUT_MS))`.

**Code review amendments (2026-10-14, `review/m2-code-review.md`).** All additive.

- (amended 2026-10-14, E-B1 = N-S2) `StreamTelemetry.readPendingSinceMs: Long` (default `0`; `SessionTelemetry` overrides). `LiveTsDataSource` sets it when a `read()` has to wait on the socket (the aligner holds no whole packet) and clears it when the read returns, with or without bytes. `PlaybackWatchdog` fills `WatchdogSample.lastByteAgeMs = now − max(lastByteAtMs, readPendingSinceMs)` only while it is non-zero, else 0, so the TS no-bytes backstop (§3.7 a) never fires on a loader parked on a full buffer (`AdaptiveLoadControl` parks it from `maxBufferMs` down to `minBufferMs`, 10–20 s). This is what §3.3.6 always said; the watchdog's input did not follow it. Sources other than `LiveTsDataSource` (HLS, archive, `PROGRESSIVE_OTHER`) report 0, so the TS backstop is off for them; HLS keeps its own detector, and the others rely on OkHttp's read timeout and Media3's stuck-buffering detector.
- (amended 2026-10-14, N-S4) `StallDetectors.newStream(nowMs)` (default: `reset`); `PlaybackWatchdog.beginSession` calls it. b1's byte threshold is relative to the stream's own rate (see §3.7 b1 and Appendix B `DECODER_STALL_MIN_BYTES_UNKNOWN_RATE`, `STREAM_RATE_LEARN_MEDIA_MS`): a network slower than real time never reaches it, so a slow network is never classified as a stuck decoder. B6's `DECODER_STALL_MAX_MEDIA_GROWTH_MS` is unchanged.
- (amended 2026-10-14, N-S1) `HeaderPolicy.isValidName` (RFC 7230 token), `isValidValue` (printable ASCII and tab) and `sanitize`. `StreamUrlBuilderImpl.build`/`buildArchive` drop every header whose name or **expanded** value is invalid (CR/LF, NUL, other control characters, non-ASCII from percent-decoded pipe headers or a raw password) and list the names in the new `ResolvedStream.droppedHeaders` (default empty); the controller logs a `RESOLVED headers_dropped <names>` event. Backstop: `LiveTsDataSource.open()` and `BrokeredHttpDataSource.open()` turn OkHttp's `IllegalArgumentException` into `LiveStreamException(NETWORK, networkKind = OTHER)` with no cause and no value in any message, and `LiveTsDataSource.open()` shuts down (releases the lease) on **any** throwable.
- (amended 2026-10-14, N-S3) `MediaSourceBuilder.build` throws `UnsupportedStreamFormatException(format, scheme)` for any URL scheme other than `http`, `https` and `thebox-session` (`rtmp`, `rtsp`, `udp`, `rtp`, `mms`, `mmsh`, …) before any transport is created; the controller maps it to `UNSUPPORTED_SOURCE` (signal `scheme <name>`, action OPEN_EXTERNAL) with no ladder step and no network attempt. A `LiveStreamException` cause, and the cause of `BrokeredHttpDataSource.hide()`, is never a `DataSourceException` (`safeCause`), so no exception in the chain carries the real `DataSpec`.
- (amended 2026-10-14, N-S5) `ResolveSecrets.registerUrl` registers only the URL's userinfo and token-like query values (names in a fixed list, or values ≥ 16 characters), never the whole URL. `Redact` keeps credentials and these token components in two bounded LRU sets (`CREDENTIAL_CAP` 1,024, `TRANSIENT_CAP` 64) and masks from an immutable snapshot, longest secret first.
- (amended 2026-10-14, N-S6) The re-resolve limiter key is `ReResolveKeys.of(identity, brokerKey)`: the channel identity, or for a direct URL a synthetic identity per broker key (`d:<host>`), as §4.5 says. The `ReResolveLimiter` contract is unchanged.
- (amended 2026-10-14, E-B2) `LifecycleCoordinator`: `ACTION_SCREEN_ON`, display on and an HDMI re-plug cancel the grace and keep playing only while the process is STARTED; while it is not (woken to the launcher), they stop playback at once with `BACKGROUND`, as Home does (Q1/B4). A later `ON_START` resumes it. The 30 s grace is unchanged.
- (amended 2026-10-14, E-S4) `LifecycleCoordinator.Target.onGraceChanged(active)` (default no-op) and `SurfacePlayer(onSurfaceValid)`: the controller's video expectation (`WatchdogSample.videoExpected`) also requires a valid surface and no running grace; the watchdog baselines are re-taken when the surface comes back or the grace ends. Detector c therefore never fires, and never counts toward the L27 compat escalation, while ExoPlayer is skipping frames for lack of a surface.
- (amended 2026-10-14, E-S2) `ensureEngine()` pushes the controller's current viewport into `DisplayModeController` once (it starts at `NONE`, and `setViewport` ignores an unchanged value).
- (amended 2026-10-14, E-S3) `FailureClassifier`: an `AudioSink.InitializationException` is `AudioTrackInit(encoded = true)` when its `format` (the sink **input**) is a passthrough encoding (AC-3, E-AC-3, E-AC-3 JOC, DTS, DTS-HD, TrueHD), so L34 (passthrough off) runs before L35/L36. `passthroughActive` is no longer used for this: it is always false at an init failure.
- (amended 2026-10-14, I-6) §5.5 I-6's "≤ 2 stream requests per burst" counts **tunes**: the debouncer turns the burst into ≤ 2 tunes (1 in the test, where every press is a repeat). With `linger: 2` each tune's first request is refused, and the ladder adds L2 `RetrySame` at 1.5 s (still inside the linger) and L3 `ReResolve` at 3.0 s, so 3 stream requests is correct by design for that configuration. The device test asserts ≤ 2 tunes and ≤ 3 stream requests.
- (amended 2026-10-14, follow-up) **Readiness stall** (§3.7 b1r): `WatchdogSample` gains `isLoading`, `readyThresholdMs`, `firstMediaThisSession`, `framesSinceDisruption`, `msSinceSeek`, `videoDecoderInits` and `videoQueuedMinusOutput` (diagnostic); `PlaybackWatchdog(…, profile = { loadControl.profile })` fills them and logs the video decoder counters at every rebuffer. Verdict `DECODER_STUCK` (no new `StallKind`).
- (amended 2026-10-14, follow-up) **Deferred compat persistence** (§4.3 L27b): `PlaybackControllerImpl` keeps a raise pending and saves it after `COMPAT_PERSIST_HEALTHY_MS` (30 s) healthy at the raised level, only for decoder-type triggers; `isDecoderTypeSignal` is the predicate.
- (amended 2026-10-14, follow-up) **`BufferMode.NONE`** (500 / 500 / 2,000 / 5,000, manual), §3.6.1.
- (amended 2026-10-14, follow-up) **Debug-only TS override:** `app.thebox.player.debug.PlayerDebugHooks.detectAccessUnitsOff` (in `player/src/debug`, absent from release) turns off only `FLAG_DETECT_ACCESS_UNITS`; used by the O-1 diagnosis harness (§5.10).
- (amended 2026-10-14, follow-up, half of Later N-L2) `ErrorLadder.beginChannel` is called with `"<playlistId>/<stableKey>"`, so the same stable key in two playlists (every device test makes a new playlist) no longer shares the 5-minute escalation windows. The single-slot window (the other half of N-L2) is unchanged.

## 3. Components and algorithms

### 3.1 Controller: tune pipeline and state machine (WP-D)

#### 3.1.1 Tune pipeline

`tune(target, zapList)` on the main thread:

1. `tuneId++`. Cancel the pending tune job, the zap debounce timer and the live-pause timer. Record `t0 = elapsedRealtime()`, add a `TUNE` event, start a `ZapRecord`. Close the import gate (§3.5.5). Emit `Connecting(RESOLVING, attempt = 1)`.
2. **End the old session first:** `transport.endSession(oldTuneId)` pre-empts the old lease (synchronous `Call.cancel()` on every attached call, §3.5.2). Then `player.stop()` (keeps the last frame on the `SurfaceView`; never `clearVideoSurface`). The old lease is released by WP-B's close path, not here.
3. **Resolve** (`StreamUrlResolver.resolve`, IO). `Failed` → ladder with `FailureSignal.ChannelMissing`/`Credentials`, which ends in `Error`.
4. **Configure the broker** for `resolved.brokerKey` (max from override → account → 1).
5. **Read memory and settings** (`ChannelMemory.read`): audio/subtitle language, aspect, decoder override, buffer override, TS compat level, `knownHdr`, `fpsMilli`. Apply runtime switches: `codecPolicy.videoMode`, `loadControl.profile = bufferFsm.profileFor(...)`, track-selector parameters (§3.12), poster pre-disable (§3.9.5), `displayMode.onTune(fpsMilli)`.
6. **Build the session**: `TuneSession(tuneId, resolved, ctx = StreamSessionContext(...))`, then `MediaSourceBuilder.build(ctx, format = hint-or-TS, compat, transport, archive = null)` (§3.1.3).
7. `player.setMediaSource(source, /* resetPosition = */ true)`, `player.prepare()`, `player.playWhenReady = true` (or after `displayMode.awaitSettled`, §3.9.4). Emit `Connecting(CONNECTING)`; the lease wait shows as `WAITING_FOR_SLOT` when the telemetry reports it.
8. Events drive the state machine below. On the **first-media** milestone (§0.16; reviewed 2026-10-13, B2: `onRenderedFirstFrame` when a video track is selected and enabled, else the first `onAudioPositionAdvancing`; without this a radio channel, an HDR channel tuned in MINI (poster) or a tune while HIDDEN never reaches the milestone, hits the 15 s switch timeout, is never recorded as watched and never resets the ladder): record `firstFrameMs`, log the `ZapRecord`, schedule the import-gate release, schedule `recordWatched` after 5 s, reset the ladder cycle after 30 s of healthy playback. Which kind of milestone is awaited is decided from `onTracksChanged` (video group selected and the video track type not disabled); a later track or viewport change does not reset it.

`zap(delta, isRepeat)`: asks the `ZapDebouncer` (§3.5.4). Immediate → resolve `zapList.neighbour(current, delta)` and `tune()`. Deferred → update `pendingZap` (the banner) by walking the neighbours, and tune when the timer fires. The current stream keeps playing until then (no second connection).

`previousChannel()`: the controller keeps `previousIdentity` = the last channel that reached first frame and played ≥ 1 s before the current one.

**Stale errors** (reviewed 2026-10-13, N): `onPlayerError` can arrive after `setMediaSource` for an error the playback thread raised for the previous session. The controller ignores a `PlaybackException` whose `timestampMs` (elapsed realtime at creation) is earlier than the current session's start, in addition to the `LiveStreamException` tuneId check. Every `MediaItem` (TS, HLS, archive, progressive) sets `mediaId = "<tuneId>"`.

`stop(reason)`: cancel jobs, `transport.endSession`, `player.stop()`, `displayMode.clear()` when the reason is not `LIVE_PAUSE_TIMEOUT`, emit `Stopped(target, reason, resumable = reason != USER)`. `Stopped` keeps `NowPlaying` so lifecycle resume (§3.10) and M4's "Retry" can re-tune.

#### 3.1.2 State machine

States are `PlaybackState` (§2.3). "Ladder(x)" means: classify x (§4.1), `ErrorLadder.decide`, execute the decision (§4.3). Every executed decision adds a `DECISION` event.

| State | Event | Action | Next |
|---|---|---|---|
| Idle / Stopped / Error | `tune` | pipeline §3.1.1 | Connecting(RESOLVING) |
| any | `stop` | §3.1.1 stop | Stopped |
| Connecting | resolved OK | build session, prepare | Connecting(CONNECTING) |
| Connecting | resolve failed | Ladder(ChannelMissing / Credentials) | Error (or Connecting after a retry) |
| Connecting | telemetry: waiting for lease | — | Connecting(WAITING_FOR_SLOT) |
| Connecting | first byte | — | Connecting(BUFFERING_INITIAL) |
| Connecting | `STATE_READY` + playWhenReady | — | Playing (first frame may still be pending; M3 keeps the logo cover until `video.width > 0` or first frame) |
| Connecting | AFR switch pending | hold `playWhenReady` | Connecting(SWITCHING_DISPLAY_MODE) |
| Connecting / Buffering / Playing | `onPlayerError` | Ladder(signal) | per decision: Connecting(attempt+1) / Stalled / Error |
| Playing / Buffering | session event `PCR_JUMP` | (reviewed 2026-10-13, S1) nothing immediate: the loader error surfaces only when the buffered pre-jump media has played out (verified, see §3.3.5); the ladder then runs L25 | — |
| Connecting / Playing | `streamSwitchTimeoutMs` elapsed without first media (§0.16) | Fail with the last classified error, else `NO_DATA_TIMEOUT` | Error |
| Playing | READY → BUFFERING, not caused by us | `bufferFsm.onRebuffer` (may raise the profile, notice `BufferIncreased`); `REBUFFER` event | Buffering(REBUFFER) |
| Playing / Buffering | telemetry `inReconnect` true | — | Buffering(RECONNECTING, null) — in-source reconnects are silent: no notice |
| Buffering | BUFFERING → READY | — | Playing |
| Playing / Buffering | watchdog verdict | Ladder(Stall(kind)) | Stalled → Connecting/Playing |
| Playing | `STATE_ENDED` on live | Ladder(LiveEnded) | Stalled |
| Playing | `pause()` (live) | `playWhenReady = false`, start `LIVE_PAUSE_RELEASE_MS` timer | Playing(paused = true) |
| Playing(paused) | timer fires | `transport.endSession`, `player.stop()` | Stopped(LIVE_PAUSE_TIMEOUT, resumable) |
| Playing(paused) / Stopped(resumable) | `resume()` (live) | re-tune the same target, notice `LiveResumed` | Connecting |
| Error | `retry()` | re-tune with a fresh ladder cycle and limiter reset for this channel | Connecting |
| any active | lifecycle stop (§3.10) | stop(reason) | Stopped |

"Not caused by us" means: not within `SEEK_GRACE_MS` after a seek, prepare or track change, and not while the telemetry is in a reconnect (those are counted separately as `RECONNECT`).

#### 3.1.3 MediaSource per format (WP-C `MediaSourceBuilder`)

| Format | Source | Data source | Notes |
|---|---|---|---|
| TS (default for UNKNOWN) | `ProgressiveMediaSource.Factory(liveTs, TsExtractorsFactory(compat))` with `setLoadErrorHandlingPolicy(BoxLoadErrorHandlingPolicy)` and `setContinueLoadingCheckIntervalBytes(DEFAULT)` | `transport.liveTs(ctx)` | `MediaItem.setUri(Uri.parse("thebox-session://<tuneId>"))` (the real URL never enters Media3 objects), `setMediaId("<tuneId>")` |
| HLS | `HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).setLoadErrorHandlingPolicy(policy).setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))` | `transport.http(ctx)` | `MediaItem` with the real URI (HLS needs it to resolve relative segment URLs), `setMimeType(APPLICATION_M3U8)`, `LiveConfiguration` min/max speed 0.97/1.03 |
| PROGRESSIVE_OTHER | `ProgressiveMediaSource.Factory(http, DefaultExtractorsFactory())` | `transport.http(ctx)` | only after the sniffer said UNRECOGNIZED (a live MP4/FLV/AAC radio stream) |
| DASH | not built in v1 | — | `UNSUPPORTED_SOURCE` (§7 D20) |
| Archive TS/HLS | as TS/HLS but over `transport.http` (finite; Range always allowed, reviewed 2026-10-13, S8) | `transport.http(ctx)` | §3.14 |

(reviewed 2026-10-13, S3) Each `MediaSource` gets its **own** `BoxLoadErrorHandlingPolicy` instance constructed with the session's `LoadOrigin` family (progressive live, HLS, archive): `ProgressiveMediaPeriod.onLoadError` reports `C.DATA_TYPE_MEDIA` for every progressive error (`ProgressiveMediaPeriod.java` `onLoadError`), so `dataType` alone cannot tell an HLS segment from a progressive stream.

`TsExtractorsFactory`: returns only the `TsExtractor` instances that `DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS).setTsExtractorMode(mode)` creates (mode `MODE_SINGLE_PMT`, or `MODE_MULTI_PMT` at compat level 1). The sniffer already proved TS, so no other extractor needs to sniff. `TS_FLAGS = FLAG_ALLOW_NON_IDR_KEYFRAMES | FLAG_DETECT_ACCESS_UNITS` (reviewed 2026-10-13, S5: `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` removed, §3.8.5). `DefaultExtractorsFactory`'s defaults keep text-track transcoding on, so DVB subtitles reach the `TextRenderer` as `application/x-media3-cues` (verified, `DefaultExtractorsFactory.java:166, 586–593`; with transcoding off, `TextRenderer` would throw "Legacy decoding is disabled" for `application/dvbsubs`, `TextRenderer.java:673–679`). Do not turn it off.

The progressive TS `MediaItem` URI is the session URI because `LiveTsDataSource` ignores `dataSpec.uri` (it connects to `ctx.resolved.url`). This keeps credentials and tokens out of `MediaItem`, `DataSpec`, `LoadEventInfo` and every Media3 exception message.

### 3.2 `StreamUrlResolver` (WP-A)

#### 3.2.1 Inputs

`StreamUrlResolverImpl.resolve(target)`:
- `Channel(identity)`: `provider.channel(identity)` (null → `Failed(CHANNEL_NOT_FOUND)`), `provider.playlist(id)` (`needsReauth` → `Failed(NEEDS_REAUTH)`), `provider.credentials(id)` (`Err(Credentials(UNAVAILABLE))` → `Failed(CREDENTIALS_UNAVAILABLE)`; `KEY_INVALIDATED`/`CORRUPT`/`MISSING` → `Failed(NEEDS_REAUTH)`), `provider.account(id)`. Then `StreamUrlBuilder.build(ResolveInput(...))`.
- `Direct(url, headers, …)`: no provider calls; the builder treats it as a literal M3U URL with `brokerKey = "d:<host>"`.

The resolver keeps **no cache of resolved URLs**. Every `ReResolve`, `RetrySame`, reconnect-after-exhaustion and lifecycle resume calls `resolve` again (decision §0.12). In-source reconnects (§3.3) reuse `ctx.resolved.url`, which is the **pre-redirect** URL, so OkHttp follows the redirect again and gets a fresh token.

#### 3.2.2 Kind detection

| Playlist type | Template | Kind |
|---|---|---|
| `XTREAM` | contains `{{base}}` | **Xtream API**: base from `credentials.baseUrls[baseIndex]`, extension from §3.2.3 |
| `M3U_URL`/`M3U_FILE` | contains `{{u}}` or `{{p}}` | **Xtream-shaped M3U**: base and extension literal in the template; the extension may still be flipped (§3.2.3) when `XtreamStreamUrl.parse(expanded, allowShort = true)` succeeds and the extension is `ts`/`m3u8`/absent |
| any | literal | **Plain**: used as is |

`ResolvedStream.xtream` = `XtreamStreamRef(base, username, password, streamId, extension)` for both Xtream kinds (from the playlist for API playlists; from `XtreamStreamUrl.parse` of the credential-expanded URL for shaped M3U, which is what M1's mapper does for `CatchupTarget.xtream`).

#### 3.2.3 Xtream output (TS/HLS)

```
setting = playlist.xtreamOutput, learned = playlist.xtreamOutputLearned, allowed = account?.allowedOutputFormats (lower-cased)
output =
  input.output (ladder flip) ?:
  when (setting) {
    TS   -> TS
    HLS  -> HLS                      // the user forced it; a 405/406 still flips (once per tune)
    AUTO -> learned ?: if (allowed.isNotEmpty() && "ts" !in allowed && "m3u8" in allowed) HLS else TS
  }
```

- Xtream API: `{{ext}}` → `output.extension`.
- Xtream-shaped M3U: the literal extension is replaced only when the URL's own extension is `ts`/`m3u8`/absent; for an absent extension and output TS nothing is appended (the short form `http://h/u/p/123` is TS by convention).
- A flip that leads to first frame calls `provider.saveLearnedOutput(id, output)` when the setting is `AUTO` (§2.10). A flip never changes `xtream_output` (the user's setting).

#### 3.2.4 Expansion order (the security rule)

1. `expandNonSecret(template, base, ext)`: `{{base}}` → `XtreamUrls.normalizeBase(base)`, `{{ext}}` → extension. → `liveUrlTemplate`.
2. Catch-up only: `CatchupUrlBuilder.build` runs on `liveUrlTemplate` (via `CatchupTarget`), and `TemplateExpander` runs on `catchup-source` (still templated, §2.9).
3. `expandCredentials(url, username, password)` last: each `{{u}}`/`{{p}}` occurrence before the first `?` is replaced by `XtreamUrls.pathSegment(value)`, after it by `XtreamUrls.query(value)` (the M1 encoding rules, §3.7.5 of M1). Header values use `expandHeaderValue` (raw). Single pass, no re-scan, so a password containing `{utc}` or `{{p}}` stays literal.

`{{u}}` with `credentials?.username == null` → `Failed(NEEDS_REAUTH, "template needs credentials")`. Expansion happens in memory right before the connection; nothing expanded is stored.

#### 3.2.5 Headers (`HeaderPolicy`)

Merged case-insensitively, highest priority first, keys canonicalised to `User-Agent`, `Referer`, `Origin`, `Cookie`, otherwise as first seen:

1. channel headers (`Channel.headers`: `#EXTVLCOPT:http-user-agent/http-referrer`, `#KODIPROP` headers, `|User-Agent=…&Referer=…`, all parsed by M1),
2. playlist secret headers (`StreamCredentials.secretHeaders`),
3. playlist `extra_headers_json`,
4. `User-Agent` from `input.userAgentOverride` (ladder L5) — only when the channel did not set one,
5. `User-Agent` from `playlist.userAgent`,
6. `User-Agent` = `settings.defaultUserAgent`.

`Range`, `Accept-Encoding`, `Host`, `Connection` and `Icy-MetaData` are dropped from user-supplied headers (we own them). `Accept-Encoding: identity` is set by `OkHttpDataSource` itself (verified). Every header value goes through `expandHeaderValue`.

#### 3.2.6 Format hint (`FormatHints`)

From the expanded URL's path (query and fragment ignored), lower-cased: `.m3u8`/`.m3u` → HLS; `.ts`, `.mpegts`, `/mpegts` suffix, no extension → TS; `.mpd` → DASH; `.mp4`, `.mkv`, `.flv`, `.aac`, `.mp3` → PROGRESSIVE_OTHER; anything else → UNKNOWN (treated as TS and sniffed). The hint is only the first guess: the sniffer decides (§3.3.3), and Content-Type is ignored except in diagnostics (protocols §5.1).

#### 3.2.7 Base-URL rotation

For Xtream API playlists with `baseCount > 1`, the ladder asks for `ReResolve(rotateBase = true)` on connect-level failures **before the first byte** (DNS, connect refused/unreachable, connect timeout, TLS, 5xx at open) — at most `baseCount − 1` rotations per tune cycle. The controller passes `baseIndex = (current + 1) % baseCount`. When a rotated index reaches first frame, `provider.saveActiveBaseIndex(id, index)` persists it and a `SwitchedToBackupServer` notice is emitted. Redirect targets are never stored (protocols §5.4). Xtream-shaped M3U and plain URLs do not rotate (their host is literal).

#### 3.2.8 Logging and redaction

The resolver logs `ResolvedStream.toString()` only (broker key, host, format, base index). `BoxLog`'s `Redact.text` masks `/live/<u>/<p>/`, query strings and registered secrets anyway, but code must not rely on it for URLs it builds. M1's `TinkCredentialStore.get` already registers the username/password with `Redact`.

(reviewed 2026-10-13, S9) That registration covers only the **raw** values (`TinkCredentialStore.kt:121–125`), and `Redact.text` matches raw substrings (`Redact.kt:21`) plus the long-form `/(live|movie|series|timeshift)/u/p/` path. The encoded forms the expander produces (`p%231`, `a%2Bb`), the short form `http://h/u/p/123` and values under 3 characters are not masked. The resolver therefore also calls `Redact.registerSecret(XtreamUrls.pathSegment(x))` and `Redact.registerSecret(XtreamUrls.query(x))` for every credential it expands. `LiveTsDataSource.getUri()` returns the session placeholder and `getResponseHeaders()` returns an empty map: `ProgressiveMediaPeriod` copies both into `LoadEventInfo` (`ProgressiveMediaPeriod.java` `onLoadError`: `setUri(dataSource.getLastOpenedUri())`, `setResponseHeaders(...)`), which every `AnalyticsListener` sees, and an `icy-metaint` header there would also make it wrap the source in `IcyDataSource` (`:1308–1313`).

Note on template positions (reviewed 2026-10-13): M1's `RefreshRepository.template()` (`:446–451`) replaces raw credential strings anywhere in header values and other attributes, so `{{u}}`/`{{p}}` can also sit outside a path segment or query value (for example in a host). The position rule (before the first `?` → path encoding) gives the same bytes for plain values; values with reserved characters in such positions are a known limitation.

### 3.3 `LiveTsDataSource` (WP-B)

#### 3.3.1 Where the reconnect lives (decision §0.3)

| | Reconnect **inside** the DataSource (chosen) | Reconnect in the **controller** (re-prepare) |
|---|---|---|
| Visible effect of a routine server close | none when the buffer (≥ 1 s) covers the reconnect (typically 150–400 ms); otherwise a short rebuffer with the last frame on screen | every close: `stop` + `prepare` → extractor re-init, PAT/PMT wait, keyframe wait, decoder flush: 1–2 s of frozen picture or black on some SoCs |
| Buffer | kept | discarded |
| Range / position | never an issue: the extractor sees one continuous byte stream | must make sure the retry starts at 0 (ExoPlayer does for unknown length; not when the server sent a Content-Length) |
| Broker churn | same lease, one new call | new lease each time |
| Timestamps | continuous when the server continues the stream; a reset or jump is caught by the PCR check (≤ ~100 ms) and turned into a re-prepare when the buffer drains (reviewed 2026-10-13, S1) | always clean, but always slow |
| Visibility of failures | must be surfaced explicitly (telemetry + typed exception on exhaustion) | automatic |

The in-source design is what the architecture review (B4.2) and D4 ask for. Its one real risk is a timestamp discontinuity after the splice, handled by §3.3.5 and, as a backstop, detectors (b)/(c) of §3.7.

**What happens to `TsExtractor` across a splice.** The aligner (§3.3.4) drops the partial packet, so the extractor sees whole packets. (reviewed 2026-10-13, blocker B1: the original text said "each PID's continuity counter jumps". It does not have to: the server restarts at an arbitrary CC. `TsExtractor.read` drops a packet whose CC **equals** the previous one as a duplicate (`TsExtractor.java:497–501`) and calls `payloadReader.seek()` only when the CC is not previous + 1 (`:502–505`). So per PID and per splice there is a 1-in-16 chance that the first new packet (often the payload-unit-start of the new PES) is dropped and the following packets are appended to the cut PES, and a 1-in-16 chance that the new data is glued onto the cut PES directly. With four PIDs (PAT, PMT, video, audio) about 40 % of splices would corrupt one access unit, which fragile Amlogic/MediaTek decoders can turn into a decoder error.) The aligner therefore **forces** a CC gap on every PID after a splice (`TsPacketAligner` KDoc, §2.2): `TsExtractor` then calls `payloadReader.seek()` for each PID, the PES reader goes back to "finding header" and skips payload until the next payload-unit-start, and the next PES resumes cleanly. The `TimestampAdjuster` (created with offset 0 in `MODE_SINGLE_PMT`) keeps adjusting new PTS relative to the last one with 33-bit wrap handling; it does **not** detect resets. So: continuous upstream PTS → seamless; a PTS reset to an earlier value → video samples become "late" (dropped) and the audio position stalls (renderer positions are monotonic) — the classic frozen picture; a forward jump → samples "early", the player waits. Hence the explicit PCR check.

(reviewed 2026-10-13, S1: corrected against the 1.11.1 sources.) With an audio track, `DefaultAudioSink.handleBuffer` treats a jump of more than 200 ms as an `UnexpectedDiscontinuityException` (reported through `onAudioSinkError`, not fatal), re-bases `startMediaTimeUs` and reports `onPositionDiscontinuity` (`DefaultAudioSink.java:1034–1062`); `MediaCodecAudioRenderer` then lets its position jump (`MediaCodecAudioRenderer.java:1220–1226`). So a **forward** jump is mostly ridden through when audio is present. A **backward** jump is worse than a freeze: `SampleQueue.largestQueuedTimestampUs` is a running maximum (`SampleQueue.java:877`), so the buffered position stays at the old high value, `bufferedDurationUs` is overstated by the jump, the `LoadControl` stops loading once the overstated duration passes `maxBufferMs`, and when the real queue drains the player sits in BUFFERING while refusing to load; the "not loading" stuck check does not fire because it also requires a small buffered duration (`ExoPlayerImplInternal.java:1560–1570`), so only the stuck-buffering backstop (30 s) or our detectors end it. Post-splice packets must therefore never reach the extractor before the PCR verdict (§3.3.4).

#### 3.3.2 Structure

```
Media3 loader ──► LiveTsDataSource (DataSource; what the factory returns)
                    └─ TeeDataSource(upstream = ReconnectingCore, sink = ctx.sinkFactory?.createDataSink() ?: NoOpDataSink)
                          └─ ReconnectingCore: lease, connect/reconnect loop, sniffer, aligner, PCR tracker, telemetry
                                └─ OkHttpDataSource (created per connection) over BrokeredCallFactory(client, lease, telemetry)
```

The sink sits **above** the reconnect logic, so a v2 recorder of the watched channel receives the same continuous, packet-aligned TS the extractor gets, from one connection (plan §3 "Live TS transport").

#### 3.3.3 `open(dataSpec)`

1. Ignore `dataSpec.uri`, `position` and `length`. If `position != 0` increment `positionIgnoredCount` (telemetry, asserted 0 in tests) and log once per session. Never build a `Range` header: the inner `DataSpec` is always `DataSpec.Builder().setUri(resolved.url).setPosition(0).setLength(C.LENGTH_UNSET).setHttpRequestHeaders(resolved.headers).setFlags(0)`.
2. **Lease:** `lease = ctx.broker.acquire(ctx.leaseKind, ctx.tuneId, PREEMPT_RELEASE_WAIT_MS)`; the core pre-empts another tune's playback lease and waits up to 300 ms for CLOSING leases (§3.5.2). `ConnectionLimitException` → `LiveStreamException(LOCAL_LIMIT)`. Telemetry `waitingForLease` is true while blocked (controller shows `WAITING_FOR_SLOT`).
3. **Connect** (§3.3.6 timeouts): `OkHttpDataSource.Factory(brokeredCallFactory).createDataSource().open(inner)`. `InvalidResponseCodeException(code)` → `LiveStreamException(HTTP_STATUS, status = code)` (not retried here on the first open: the ladder owns first-open statuses). `HttpDataSourceException` (IO) → `LiveStreamException(NETWORK, networkKind)`. (reviewed 2026-10-13, S3) `networkKind` comes from the **root** cause: `OkHttpDataSource.executeCall` wraps every open failure as `IOException(ExecutionException(cause))` (`OkHttpDataSource.java:429–452`) and `HttpDataSourceException.createForIOException` tests only the outer type, so a connect timeout, `UnknownHostException`, `ConnectException` or `SSLException` at open all arrive as `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (2001), never `_TIMEOUT`. Walk `cause` until a non-wrapper is found. A wait in the broker that is interrupted (`Loader.cancelLoading` interrupts the loader thread) is rethrown as `InterruptedIOException`; any `RuntimeException` (for example `ConnectionLimitException`) must be mapped before it leaves `open()`, because `Loader` wraps runtime exceptions in `UnexpectedLoaderException` (`Loader.java:470–482`).
4. **Sniff** (`StreamSniffer`): read into a 2 KB sniff buffer until a verdict or `SNIFF_MAX_BYTES` (2048) or EOF, bounded by `FIRST_BYTE_TIMEOUT_MS`:
   - **TS**: the first offset `o < 188` with `buf[o] == buf[o+188] == buf[o+376] == 0x47` (needs ≥ 565 bytes). Bytes before `o` are dropped.
   - **HLS_PLAYLIST**: after an optional UTF-8 BOM and ASCII whitespace, the bytes start with `#EXTM3U` → `LiveStreamException(FORMAT_IS_HLS)`; the controller rebuilds the source as HLS for the same URL (`SwitchSourceType(HLS)`, not counted as a failure). An Xtream `.ts` URL that 302s to an `.m3u8` playlist lands here.
   - **HTML**: after BOM/whitespace, case-insensitive `<html`, `<!doctype`, `<head`, `<body`, or `<?xml` followed by `<html` within 512 bytes → `LiveStreamException(HTML_PAGE)`. The body is not kept (it may echo credentials).
   - **TINY**: EOF before 565 bytes and none of the above → `LiveStreamException(TINY_BODY, bytes = n)`.
   - **UNRECOGNIZED**: 2048 bytes without a verdict → `LiveStreamException(FORMAT_UNRECOGNIZED)`; the controller tries `SwitchSourceType(PROGRESSIVE_OTHER)` once.
   - The verdict is recorded in telemetry (`sniffed`) and as a `SNIFF` event with the Content-Type (diagnostics only).
5. Return **`C.LENGTH_UNSET`** always, even when the server sent `Content-Length` (some panels send a huge fake length; a known length would make `ProgressiveMediaPeriod` resume with an offset and `TsExtractor` run its duration reader, which seeks near the end: both produce a `Range`).

Re-open after close (ExoPlayer may call `open` again on the same instance after an error retry) is a fresh connect with a new lease.

#### 3.3.4 `read()` and the aligner

`read(buffer, off, len)`:
1. If the aligner has whole packets, copy `min(len, available)` (whole packets when `len ≥ 188`) and return.
2. Otherwise read from the current `OkHttpDataSource` into a reusable 16 KB chunk, `aligner.write(chunk)`, feed each new whole packet to the `PcrTracker`, update telemetry (`bytesTotal`, `lastByteAtMs`, bitrate window), loop.
3. On `C.RESULT_END_OF_INPUT` or an `IOException` from the inner source → **first** check `lease.isPreempted` and `cancelledByWatchdog` (reviewed 2026-10-13, S3: a cancelled call surfaces as `SocketException("Socket closed")` wrapped as error code 2001, indistinguishable from a server reset, so this check is a **must**), then the **reconnect loop** (§3.3.6). A live stream never returns `RESULT_END_OF_INPUT` to Media3; only `close()` ends it.
4. (reviewed 2026-10-13, S1) **Post-splice hold.** After a reconnect the aligner holds every new packet (`heldBytes()`) while `pcr.awaitingPcr()` is true. When the first PCR arrives: no jump → `aligner.releaseHeld()`; jump → `aligner.dropHeld()` and throw `LiveStreamException(PCR_DISCONTINUITY, deltaMs)`. If no PCR arrives within `PCR_HOLD_MAX_BYTES` (256 KiB) or `PCR_HOLD_MAX_MS` (500 ms; MPEG-2 requires a PCR at least every 100 ms), release and disable the check for this splice. Without the hold, audio PES with the new timestamps reach the extractor before the PCR verdict (the audio PID usually precedes the next PCR-carrying video packet).

Timing note (reviewed 2026-10-13, S1, verified): once the period is prepared, a fatal loader error is thrown to the player only from `maybeThrowRendererStreamError`, i.e. when a renderer can no longer play (`ExoPlayerImplInternal.java:1502, 1557`) — after the media already buffered has played out. A `PCR_DISCONTINUITY` or `RECONNECT_EXHAUSTED` therefore reaches `onPlayerError` 1–20 s after the event (the current buffer), while playback continues from the buffer. That is the desired behaviour (no early cut), but tests and the state machine must not expect the ~100 ms reaction the earlier text implied; I-4/I-5 measure from the rebuffer, not from the `PCR_JUMP` event. Before the period is prepared (first open, sniff) errors surface at once through `maybeThrowPrepareError` (`:1511`).

Aligner memory: one 64-packet ring (12,032 bytes) plus the 16 KB read chunk per session. `resync()` on every reconnect drops the partial packet and requires a fresh three-sync lock, so the splice is always on a packet boundary.

#### 3.3.5 PCR continuity check

- The first packet with an adaptation field carrying a PCR (`(b[3] & 0x20) != 0`, `b[4] > 0`, `(b[5] & 0x10) != 0`) fixes `pcrPid`. Only that PID is tracked afterwards; the per-packet cost is two bit tests.
- `pcrBase90k` = the 33-bit base (bytes 6–10). The tracker keeps the last PCR and its wall time.
- `markReconnect(now)`: the next PCR on `pcrPid` is compared with `expected = last + (now − lastWall) × 90` (mod 2³³). `delta` = the signed 33-bit difference in ms. `|delta| > PCR_JUMP_REPREPARE_MS` (2000) → pending jump.
- Without any PCR (some radio streams), the check is disabled and detectors (b)/(c) are the only guard.
- **Tolerance rationale:** a well-behaved panel that continues the stream after a reconnect gives `delta` ≈ minus the server-side buffer it re-sends (0–2 s); a restarted encoder or a looped file gives seconds to hours. D4 measures this; the constant may move.

#### 3.3.6 Reconnect loop, timeouts and the byte watchdog

Reconnectable (inside the source): end of input; `IOException` while reading (reset, `SocketException`, timeout); a cancel by the byte watchdog; on a **reconnect** open: connect/DNS/timeout errors, 5xx, and 403/458/509/503 (our own previous socket may still be counted by the panel). Not reconnectable: a cancel because the lease was pre-empted (the session is ending: return/throw quietly as `LiveStreamException(PREEMPTED)`, which the controller ignores for that stale `tuneId`), 401, 404, 405, 406, 410, HTML/TINY/HLS sniff results on a reconnect (the panel's answer changed: surface it).

```
attempt = 0; windowStart = now
loop:
  if lease.isPreempted -> throw PREEMPTED
  if attempt >= RECONNECT_MAX_ATTEMPTS (3) or now - windowStart > RECONNECT_WINDOW_MS (3000) -> throw RECONNECT_EXHAUSTED(lastStatus, noBytes)
  sleep(RECONNECT_DELAYS_MS[attempt])            // 0, 500, 1000 ms; a 403/458/509/503 answer uses RECONNECT_LIMIT_STATUS_DELAY_MS (1000) instead
  attempt++; telemetry.inReconnect = true; event RECONNECT(attempt)
  close the previous inner source (call.cancel FIRST, then body close: reviewed 2026-10-13, S2), aligner.resync(), pcr.markReconnect(now)
  open the inner source on ctx.resolved.url (fresh redirect, fresh token); sniff must say TS again
  on success: telemetry.inReconnect = false; reconnects++; event RECONNECT_OK(ms); return to read()
```

`attempt` and `windowStart` reset after `RECONNECT_BUDGET_RESET_MS` (30 s) of uninterrupted bytes. The three attempts fit the plan's "transparent 3 s reconnect".

(amended 2026-10-13) The budget is read as **bursts**: at most `RECONNECT_MAX_ATTEMPTS` (3) attempts inside one `RECONNECT_WINDOW_MS` (3 s) burst; a loss that happens after the window, with bytes flowing again since the last reconnect, starts a new burst (`attempt = 0; windowStart = now`). `RECONNECT_BUDGET_RESET_MS` is therefore unused (kept in App. B only as a historical value). Why: the literal 30-s reset would exhaust the budget on a panel that closes every 15 s (I-3 `close_every: 15`), which the plan requires to stay transparent. This is WP-B's implementation, accepted by the lead at integration.

| Timeout | Value | Where |
|---|---|---|
| connect | `LIVE_CONNECT_TIMEOUT_MS` = 5,000 | live `OkHttpClient` (`base.newBuilder().connectTimeout(…)`) |
| read (socket) | `LIVE_READ_TIMEOUT_MS` = 8,000 | live client (architecture review B4.3: not 15–20 s) |
| first byte after open | `FIRST_BYTE_TIMEOUT_MS` = 8,000 (initial), `RECONNECT_FIRST_BYTE_TIMEOUT_MS` = 4,000 | byte watchdog |
| no bytes while flowing | `NO_BYTES_TIMEOUT_MS` = 4,000 | byte watchdog |

**Byte watchdog** (`ByteWatchdog`): one daemon thread for the process ticks every `WATCHDOG_TICK_MS` (500). For each registered live session it compares `now − lastByteAtMs` (or `now − openStartedAt` before the first byte) with the applicable timeout and, when exceeded, sets `cancelledByWatchdog` and calls `call.cancel()` on the current call. That unblocks the loader thread's `read()` at once (Java socket reads ignore `Thread.interrupt()`, and Media3's `Loader.cancelLoading` only interrupts). The read loop sees the flag and reconnects with `noBytes = true` (event `NO_BYTES`). When the buffer is full, `ProgressiveMediaPeriod` parks the loader in `loadCondition.block()` and stops calling `read()` (verified, `ProgressiveMediaPeriod.java:1331–1336`), so silence is only measured **while a `read()` call is in progress**: the session sets `readEnteredAtMs` on entry and clears it on return, and the watchdog fires when `readEnteredAtMs != 0 && now − max(lastByteAtMs, readEnteredAtMs) > timeout`. A paused player or a full buffer therefore never trips it.

#### 3.3.7 `close()`

Called by Media3 on load end, cancel or error. Order (the broker rule; reviewed 2026-10-13, S2): `call.cancel()` on the current call first (synchronous socket close on HTTP/1.1, idempotent), then close the inner source (response body close), then close the tee sink. The lease is released by the call's `EventListener.callEnd`/`callFailed` (§1.5 `BrokeredCallFactory`), which OkHttp fires after it closed the socket (`RealCall.callDone`, `RealCall.kt:408–437`). Why this order: for a chunked or fixed-length (fake huge `Content-Length`) body, `ResponseBody.close()` first tries to drain the stream for up to `DISCARD_STREAM_TIMEOUT_MILLIS` = 100 ms (`Http1ExchangeCodec.kt:460–467, 530–536`) — on a flowing live stream that is 100 ms of reading on every zap and reconnect. With the socket already closed the drain fails at once. `close()` never throws for an already-cancelled call.

#### 3.3.8 Typed failure

```kotlin
internal enum class LiveFailureKind { HTTP_STATUS, NETWORK, RECONNECT_EXHAUSTED, HTML_PAGE, TINY_BODY, FORMAT_IS_HLS, FORMAT_UNRECOGNIZED, PCR_DISCONTINUITY, LOCAL_LIMIT, PREEMPTED }

internal data class FailureInfo(
    val status: Int?,               // last HTTP status seen
    val host: String?,              // final host:port after redirects, never a path
    val bytesRead: Long,            // this session, all connections
    val attempt: Int,               // in-source reconnect attempt that failed
    val phase: Phase,               // OPEN, SNIFF, READ, RECONNECT
    val networkKind: NetworkFailureKind?,
    val deltaMs: Long?,             // PCR jump
    val elapsedMs: Long,
) { enum class Phase { OPEN, SNIFF, READ, RECONNECT } }

/**
 * Message is "live <kind> <status?>" only: never a URL. [dataSpec] is the session placeholder (thebox-session://<tuneId>),
 * so Media3 logging cannot print the real URL.
 */
internal class LiveStreamException(
    val kind: LiveFailureKind,
    val info: FailureInfo,
    dataSpec: DataSpec,
    cause: IOException?,
) : HttpDataSource.HttpDataSourceException(
    "live ${kind.name.lowercase()} ${info.status ?: ""}".trim(), cause, dataSpec, kind.media3Reason(), info.phase.media3Type(),
)
```

`media3Reason()`: `HTTP_STATUS` → `ERROR_CODE_IO_BAD_HTTP_STATUS`; `NETWORK`/`RECONNECT_EXHAUSTED` → `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (or `_TIMEOUT` for timeouts/no bytes); `HTML_PAGE`/`TINY_BODY`/`FORMAT_UNRECOGNIZED` → `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`; `FORMAT_IS_HLS` → `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`; `PCR_DISCONTINUITY` → `ERROR_CODE_IO_UNSPECIFIED`; `LOCAL_LIMIT` → `ERROR_CODE_IO_UNSPECIFIED`; `PREEMPTED` → `ERROR_CODE_IO_UNSPECIFIED`. `type`: OPEN/SNIFF → `TYPE_OPEN`, READ/RECONNECT → `TYPE_READ`. Media3 surfaces `DataSourceException.reason` as `PlaybackException.errorCode` (verified, `ExoPlayerImplInternal.java:992`), and the exception itself is the `PlaybackException` cause, so `FailureClassifier` reads `kind` and `info` directly.

### 3.4 HLS and finite (archive) transport (WP-B `StreamHttpDataSourceFactory`)

- `OkHttpDataSource.Factory(brokeredCallFactory).setDefaultRequestProperties(resolved.headers)` — **no** `setUserAgent` (it would `addHeader` a second User-Agent next to ours, verified `OkHttpDataSource.java:403–410`).
- Range is allowed (HLS byte-range segments, archive seeks). No reconnect loop: the load policy and the HLS loader retry.
- **Lease:** HLS counts as one panel session (protocols §3.5), so the session acquires **one** lease at the first open (manifest) and keeps it until `endSession`; every manifest/segment `Call` attaches to it. Pre-emption cancels all of them.
- **Telemetry:** bytes, status, final host and redirects as for TS; `inReconnect` stays false. Bitrate is measured from bytes over 2 s, as for TS.
- The HLS playlist tracker uses `DefaultHlsPlaylistTracker.FACTORY` (default) with our load policy.
- Token-in-segment URLs (redirected live HLS whose segments carry the token, as the fake panel does): a 403 on a segment reaches the ladder (§4.3 L7: re-resolve once, i.e. a new MediaSource from the original URL). A `ResolvingDataSource` token rewriter (android-tv-architecture §2.4 point 3) is **not** built in M2 (§7 D21).

### 3.5 `StreamConnectionBroker` (WP-A core + registry; WP-B attaches calls)

#### 3.5.1 Lease model

`ConnectionBrokerCoreImpl` (one per broker key) holds `max` and a list of leases `{id, kind, ownerTuneId, state ∈ ACTIVE|CLOSING|RELEASED, handles}` under one `ReentrantLock` with a `Condition released`.

- `count = ACTIVE + CLOSING` leases. A CLOSING lease still counts: its socket may be cancelled, but the panel may not know yet.
- `max` = `playlist.maxConnectionsOverride ?: account.maxConnections ?: DEFAULT_MAX_CONNECTIONS (1)`, clamped to `1..MAX_CONNECTIONS_CAP (50)`. `max_connections = 0` from a quirky panel is treated as unknown (1). Direct URLs use the harness value.
- The registry reconfigures a broker on every tune (account cards change).

#### 3.5.2 Acquire (`acquire(kind, owner, waitMs)`)

```
lock:
  if count < max -> grant ACTIVE
  if kind in {PLAYBACK, PROBE}:
     for each ACTIVE PLAYBACK/PROBE lease with owner != this owner: preempt (cancel handles, state = CLOSING)
  if CLOSING leases exist: await `released` up to waitMs (300 ms); re-check count < max
  if count < max -> grant
  if CLOSING leases still exist (sockets cancelled, release lagging) -> grant anyway, log "broker.grant_after_wait", count may read max+1 briefly
  else -> throw ConnectionLimitException(active, max)   // RECORDING/PREVIEW leases hold the slots
```

- `preemptPlayback(reason)` (controller, step 2 of the tune pipeline) does the pre-empt part eagerly on the main thread so the old socket closes before the new resolve even starts. `Call.cancel()` closes the socket synchronously (OkHttp `RealCall.cancel` → `exchange.cancel` / `connection.cancel`). (reviewed 2026-10-13, S2, verified in `okhttp-jvm-5.5.0` sources: `RealCall.kt:168–178` → `Exchange.cancel` → `Http1ExchangeCodec.cancel` → `RealConnection.cancel` → `rawSocket.closeQuietly()`; during connect, `ConnectPlan.cancel` closes the half-open socket. Two exceptions: **HTTP/2** (an HTTPS panel that negotiates h2) sends `RST_STREAM` and keeps the TCP connection (`Http2ExchangeCodec.kt:124–127`), which still ends the panel's stream; and a cancel **during DNS** does not unblock until the lookup returns (`RealRoutePlanner.kt:168–173`) — no panel connection exists yet, so the grant-after-wait path covers it. OkHttp's `retryOnConnectionFailure` (default on) may silently re-send a GET after a stale pooled connection; same `Call`, so the broker's accounting is unaffected.)
- `release()` removes the lease and signals `released`. It is called **after** `call.cancel()` and body close — concretely from the per-call `EventListener.callEnd`/`callFailed` once the lease has no attached handle and is CLOSING or its session ended (reviewed 2026-10-13, S2; if Media3 never stores a response because `open()` was interrupted, `callDone` never fires, so the 300 ms grant-after-wait path below stays necessary) — never on `player.stop()` (plan §3 "Connections": `player.stop()` alone does not close the socket in time — the loader may be blocked in `read()` until the read timeout).
- PREVIEW (v1.1) never pre-empts and is refused when `max == 1`; RECORDING (v2) is never pre-empted by PLAYBACK. `PlaybackController` exposes nothing for these in v1; the kinds exist so `:dvr` plugs in without a broker change.

#### 3.5.3 The server-side linger ladder

The broker guarantees *our* side. A panel may still count a closed session for 2–120 s (fact-check: panel-configured). That shows up as 403/458/509/503 (or a 200 that closes at once) on the **new** open, and the ladder handles it (§4.3 L2–L6): wait 1.5 s and retry; then re-resolve with an account refresh (fresh `active_cons`/`max_connections`) at 3 s; then fail with `CONNECTION_LIMIT` and `active/max` in the detail. D5 measures the success rate with linger 0/2/10/30 s.

(reviewed 2026-10-13, S7) "A 200 that closes at once" reaches the controller as `TinyBody(0)` (sniff EOF before 565 bytes), not as a status, so L22 must route a zero-byte body inside `ZAP_LIMIT_WINDOW_MS` or before first media into the limit rows L2–L4. HLS sessions are worse: panels count an HLS viewer until a session timeout after the last request (the fake panel's `hls_timeout` is 20 s, `fakepanel.py:133–160`), so on a 1-connection account a zap **away from** an HLS channel keeps failing L2/L3 for up to that timeout. That case ends in `CONNECTION_LIMIT` with `STOP_AND_WAIT`; the detail should say the previous channel was HLS.

#### 3.5.4 Zap debounce (`ZapDebouncerImpl`)

```
onZap(now, isRepeat):
  if !isRepeat && now - lastZapAt > ZAP_DEBOUNCE_MS && !trailingPending -> immediate; lastZapAt = now
  else -> trailingPending = true; fireAt = now + ZAP_DEBOUNCE_MS (350); lastZapAt = now
```

A single press tunes at once (no added latency); a held key or quick presses tune only once, 350 ms after the last press, on the channel the banner shows. Five quick zaps therefore cost at most two connections (the leading one, which is pre-empted, and the trailing one). D5 measures it.

#### 3.5.5 Zap import gate (`ZapImportGate`, WP-D)

M1 importers call `ImportGate.awaitClear()` between batches (M1 §1.6, `ImportMutex.gate`). The gate is **closed** at `tune()` and opens at the earliest of: first frame + `ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS` (500), tune + `ZAP_IMPORT_HOLD_MAX_MS` (3,000), error, stop. `awaitClear()` blocks the importer thread on a `ReentrantLock` condition for at most `IMPORT_GATE_MAX_WAIT_MS` (3,000) per call, so a stuck tune never starves an import. This is the "≤ 15 % zap degradation during EPG import" mechanism (plan §6).

#### 3.5.6 The mini player and connection limits (product §3.8)

- The mini player is the tuned stream: same player, same lease. It costs nothing on 1-connection accounts.
- M2 forbids any second consumer when `max == 1`: no PREVIEW leases, no pre-connect of the next/previous channel, no prefetch. The broker's `snapshot()` (`active/max`) is in `StreamStats` and is what M3's v1.1 preview-on-focus will check.
- `PROBE` leases (M4's catch-up probe) pre-empt playback like a tune, because a probe replaces what is playing.

### 3.6 Adaptive buffer (WP-A FSM, WP-C `AdaptiveLoadControl`)

#### 3.6.1 Profiles

Live TS after the panel's initial burst arrives at roughly real-time rate, so the buffer a session has is the buffer it waited for at start or after a rebuffer (architecture review B4.1). The start threshold sets zap time; the after-rebuffer threshold sets resilience.

| Mode / level | `startMs` | `afterRebufferMs` | `minBufferMs` | `maxBufferMs` |
|---|---|---|---|---|
| Auto L0 (default) | 1,000 | 3,000 | 10,000 | 20,000 |
| Auto L1 | 1,500 | 5,000 | 15,000 | 30,000 |
| Auto L2 | 2,500 | 10,000 | 30,000 | 45,000 |
| Auto L3 | 5,000 | 20,000 | 40,000 | 60,000 |
| Small | 2,000 | 2,000 | 10,000 | 20,000 |
| Medium | 5,000 | 5,000 | 15,000 | 30,000 |
| Large | 10,000 | 10,000 | 30,000 | 45,000 |
| Extra large | 20,000 | 20,000 | 40,000 | 60,000 |
| None (amended 2026-10-14, user request) | 500 | 500 | 2,000 | 5,000 |

- `targetBytes` = `LOW_RAM_TARGET_BYTES` (24 MiB) when `DeviceCapabilities.lowRam` (`isLowRamDevice || memoryClass ≤ 192 || totalMem ≤ 2 GiB`, architecture review C14), else `NORMAL_TARGET_BYTES` (48 MiB). `prioritizeTimeOverSizeThresholds = false` semantics: the byte cap wins.
- Back buffer 0 for live (`getBackBufferDurationUs` → 0). Archive uses Medium. (reviewed 2026-10-13, S4: the "15 s archive back buffer" is dropped. `ExoPlayerImplInternal` reads `getBackBufferDurationUs(playerId)` and `retainBackBufferFromKeyframe(playerId)` **once**, in its constructor (`ExoPlayerImplInternal.java:330–331`), so a per-profile value is never honoured by the long-lived player. A short archive seek-back re-requests with `Range`. A fixed non-zero back buffer would also eat the live byte cap.)
- Product §4.4 says Auto "starts at about 1.5 s … grows to 5 → 10 → 20 s after rebuffers": the 5 → 10 → 20 s steps are the after-rebuffer thresholds of L1–L3; the start threshold grows more slowly (1 → 1.5 → 2.5 → 5 s) so zapping stays fast on a playlist that once rebuffered. Protocols §6.3's manual seconds (2/5/10/20) map onto the start threshold.
- HLS live: `shouldStartPlayback` uses `min(threshold, targetLiveOffsetUs / 2)` so a short live window never waits longer than half its target offset.
- (amended 2026-10-14) **None**: live TV with the smallest profile that still plays (the decoder needs one full frame); it rebuffers more often on unstable providers. A manual mode like Small–Extra large (no adaptive climb, `level = -1`); the default stays Auto and archive keeps Medium. `AdaptiveLoadControl` floors only the low-water mark at 500 ms (below None's 2 s), never the start threshold, and HLS's `min(500, offset / 2)` stays 500 ms for any target offset ≥ 1 s. The settings UI (M5) labels it "None (live TV, fastest zapping)", and a repeated-rebuffer hint points at Auto. `RoomChannelMemory` stores the mode by name, so no schema change; an unknown stored name reads back as null (no override).

#### 3.6.2 Auto FSM (`BufferProfileFsmImpl`, per playlist key, in memory)

| Event | Rule |
|---|---|
| rebuffer at L0 | → L1 immediately (L0 is the optimistic zap level) |
| rebuffer at L1–L2 | count rebuffers in a sliding `REBUFFER_WINDOW_MS` (10 min); the 2nd within the window → level + 1, window cleared |
| rebuffer at L3 | stay |
| `onStableTick` (every 60 s of playing) | when `now − lastRebufferAt ≥ DECAY_STABLE_MS` (10 min) and level > 0 → level − 1, `lastRebufferAt = now` (one level per 10 min) |
| manual mode (global or channel) | fixed profile, FSM untouched |

A level change emits `BufferIncreased(level)` (up only; decay is silent) and a `BUFFER_LEVEL` event. Rebuffers caused by in-source reconnects count (they are network rebuffers); rebuffers right after a tune, seek, re-prepare or track change (`SEEK_GRACE_MS`) do not. State is per playlist and resets at process death (the review's per-playlist persistence is not needed: escalation takes one rebuffer).

#### 3.6.3 `AdaptiveLoadControl` (decision §0.6)

`LoadControl` is fixed at `ExoPlayer.Builder.setLoadControl` time. Instead of rebuilding the player (decoder re-init, surface re-attach, lost last frame, 300–800 ms), WP-C implements `LoadControl` itself:

- `delegate = DefaultLoadControl.Builder().setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)).setTargetBufferBytes(maxTarget).setBufferDurationsMs(40_000, 60_000, 1_000, 2_000).setBackBuffer(0, false).build()` (the durations are irrelevant: our two decisions do not call the delegate's).
- Delegated: `getAllocator`, `onPrepared`, `onTracksSelected`, `onStopped`, `onReleased`, `retainBackBufferFromKeyframe`, `getBackBufferDurationUs` (always 0; reviewed 2026-10-13, S4). **Override the `PlayerId`/`Parameters` overloads only**: in 1.11.1 they are `default` methods that forward to deprecated overloads whose default bodies throw `IllegalStateException("… not implemented")` (`LoadControl.java:148–160, 171–178, 232–243, 288–299, 322–333, 346–359, 392–427`); `getAllocator(PlayerId)` is the only abstract method. The delegate's target bytes (`setTargetBufferBytes`) is the device-class cap (`LOW_RAM_TARGET_BYTES` or `NORMAL_TARGET_BYTES`), so the allocator's pooled-but-free segments never exceed it; `onStopped` → `DefaultLoadControl.removePlayer` → `allocator.reset()` frees the media buffer on every `player.stop()` (`DefaultLoadControl.java:738–747, 917–923`).
- `@Volatile var profile: BufferProfile` (written on main, read on the playback thread).
- `shouldContinueLoading(p: LoadControl.Parameters)` (per `PlayerId` state, like `DefaultLoadControl`):

```
targetReached = allocator.totalBytesAllocated >= profile.targetBytes
minUs = max(profile.minBufferMs * 1000, 500_000)
if (p.bufferedDurationUs < minUs) isLoading = !targetReached
else if (p.bufferedDurationUs >= profile.maxBufferMs * 1000 || targetReached) isLoading = false
return isLoading
```

- `shouldStartPlayback(p)`: `threshold = if (p.rebuffering) afterRebufferMs else startMs`; HLS live clamp (§3.6.1); `return p.bufferedDurationUs >= threshold * 1000 || allocator.totalBytesAllocated >= profile.targetBytes`.
- A new profile takes effect on the next call, i.e. within one playback-loop iteration. Lowering the byte cap below what is allocated simply stops loading until playback drains it.

### 3.7 Stall detectors (WP-A logic, WP-C `PlaybackWatchdog`)

`PlaybackWatchdog` posts a 1 Hz runnable on the main looper while a session is active and `playWhenReady` is true. Each tick fills the reusable `WatchdogSample` (no allocation): player state, `currentPosition`, `totalBufferedDuration`, telemetry (`bytesTotal`, last-byte age measured as in §3.3.6 — (code review 2026-10-14, E-B1/N-S2) only while `StreamTelemetry.readPendingSinceMs != 0`, else 0 —, `inReconnect`), `getVideoDecoderCounters()` after `ensureUpdated()` (`renderedOutputBufferCount`, `droppedBufferCount`), the audio flags maintained by `AnalyticsListener` callbacks (`onAudioPositionAdvancing`, `onAudioUnderrun`, `onAudioTrackInitialized` → passthrough when the config's encoding is not PCM), `isTunnelingEnabled`, and the expectations set by the controller (viewport, poster, track selection). `StallDetectorsImpl.onSample` returns at most one verdict.

**Global guards (no detector runs):** `!playWhenReady`; `suppressed`; `msSinceSeekOrPrepare < SEEK_GRACE_MS` (2,000); state IDLE/ENDED; within `STALL_COOLDOWN_MS` (15,000) after the previous verdict of the same detector; the controller is executing a ladder decision.

| # | Detector | Inputs | Fires when | Verdict → ladder action | Avoiding false positives |
|---|---|---|---|---|---|
| a | **No bytes** (HLS and backstop) | `bytesTotal`, `inReconnect`, `isHls`, state | HLS: state BUFFERING and `bytesTotal` unchanged for `HLS_NO_PROGRESS_MS` (8,000). TS: only if the byte watchdog failed (`lastByteAgeMs > 2 × NO_BYTES_TIMEOUT_MS` while not reconnecting; the age is non-zero only while a `read()` is pending, code review 2026-10-14 E-B1/N-S2) | `NO_BYTES` / `HLS_NO_PROGRESS` → `ReResolve` (counts as a reconnect attempt) | TS primary detection is in the data source (§3.3.6), which knows when a read is pending |
| b1 | **Decoder stuck while bytes arrive** | state, `bytesTotal` delta, buffered position (`positionMs + bufferedDurationMs`) | state BUFFERING for ≥ `DECODER_STALL_BUFFERING_MS` (6,000) while `bytesTotal` grew by ≥ `DECODER_STALL_MIN_BYTES` (256 KiB) over that time — (code review 2026-10-14, N-S4) and by ≥ the window's worth of media at the stream's own learned rate (bytes per buffered-media second, learned over ≥ 2 s spans of buffered-position growth); `DECODER_STALL_MIN_BYTES_UNKNOWN_RATE` (2 MiB) before a rate is learned — **and the buffered position grew by less than `DECODER_STALL_MAX_MEDIA_GROWTH_MS` (500)** (reviewed 2026-10-13, blocker B6: without the last clause every network slower than the stream bitrate — a normal rebuffer with bytes flowing — fires b1, re-prepares twice, persists compat level 1 and ends in `STREAM_UNDECODABLE`; the fake panel's `slow: 100000` I-16 does exactly this) | `DECODER_STUCK` → `Reprepare`; the 2nd within `COMPAT_ESCALATE_WINDOW_MS` (5 min) for the channel → `Reprepare(raiseTsCompat = true)`; the 3rd → `Fail(STREAM_UNDECODABLE)` | bytes must be flowing (otherwise it is (a)); not within 2 s of prepare |
| b1r | **Readiness stall** (amended 2026-10-14, replaces the rejected O-1 "b3") | state, `totalBufferedDuration`, `isLoading`, current profile, first media of the session, rendered count since the last seek/prepare/track change, time since the last seek, `onVideoDecoderInitialized` count, `videoExpected` | state BUFFERING continuously for ≥ `READINESS_STALL_MS` (5,000) **and** media available to start (`totalBufferedDuration ≥ max(profile.afterRebufferMs, READINESS_MIN_BUFFER_MS)`, read fresh each tick, **or** `!isLoading` with ≥ `READINESS_NOT_LOADING_MIN_BUFFER_MS` (2,000) buffered) **and** this session reached first media **and** frames were rendered since the last seek/prepare/track change **and** ≥ `READINESS_SEEK_GRACE_MS` (6,000) since a seek **and** `videoExpected` (valid surface, no grace, not audio-only, not the HDR poster). Media3 goes READY as soon as every renderer is ready and the start threshold is met, so BUFFERING with that much media means a renderer is not ready although its source has data. Computed on main at the 1 Hz tick, never from `shouldStartPlayback` (ExoPlayer does not ask the LoadControl while a renderer is not ready) | `DECODER_STUCK` (shared 15 s cool-down with b1) → L27; evidence: buffering ms, buffered ms, `queued − output` of the video `DecoderCounters` (diagnostic only) | baseline restarts on a decoder re-init and on `newStream()`; a starved rebuffer (buffer below the threshold), start buffering (no first media), a seek, no surface, the grace, radio and the poster never fire it. Every READY → BUFFERING logs the video decoder counters at DEBUG (D4 data) |
| b2 | **Position stuck while "playing"** | `isPlaying`, `positionMs`, bytes | READY, `isPlaying`, `positionMs` advanced < 500 ms over `POSITION_STUCK_MS` (4,000) while bytes arrive | `POSITION_STUCK` → `Reprepare` (same escalation as b1) | `isPlaying` is false while paused/suppressed; archive with a native seek resets the baseline |
| c | **Frozen picture with audio** | `videoRenderedCount`, `videoDroppedCount`, `positionMs`, `videoExpected`, `firstFrameRendered`, `tunneling` | READY, `isPlaying`, `videoExpected`, `firstFrameRendered`, not tunneling, rendered count unchanged for `FROZEN_VIDEO_MS` (3,000) while position advanced ≥ `FROZEN_MIN_AUDIO_ADVANCE_MS` (2,500) | `FROZEN_VIDEO` → `DisableTunnelingAndReprepare` if tunneling was on, else `Reprepare` (same escalation as b1) | video disabled (poster, HIDDEN), no surface, or before the first frame → not expected ((code review 2026-10-14, E-S4) "no surface" is the `SurfaceHolder`'s surface being invalid, and a standby/HDMI/display-off grace also makes video not expected); tunneled frames bypass `DecoderCounters` → detector off when tunneling; a static picture still renders frames (the decoder outputs every frame) |
| d1 | **Audio never starts** | `audioExpected`, `audioAdvancing`, `msSinceFirstFrame`, `audioPassthrough` | `audioExpected`, first frame rendered, `isPlaying`, no `onAudioPositionAdvancing` within `AUDIO_START_TIMEOUT_MS` (3,000; `AUDIO_START_TIMEOUT_PASSTHROUGH_MS` = 5,000 when the audio track is passthrough, for AVRs that lock late) of `max(first frame, last isPlaying start)` (reviewed 2026-10-13, S11: the first frame is rendered before playback starts, so an AFR hold or a slow READY would otherwise count against the audio) | `AUDIO_NOT_STARTED` → `DisablePassthroughAndReprepare(mime)` when passthrough; else `SoftwareAudioAndReprepare(mime)` when FFmpeg supports the MIME; else `DropAudioAndReprepare(NO_AUDIO)` | only after a first frame (so slow starts do not count); reset on track change |
| d2 | **Audio underrun storm** | `audioUnderrunsLast3s` | ≥ `AUDIO_UNDERRUN_STORM_COUNT` (5) underruns within 3 s while READY | `AUDIO_UNDERRUN_STORM` → same as d1 | underruns during a rebuffer are excluded (state must be READY) |

What Media3 does and does not expose (honest limits):
- `DecoderCounters.renderedOutputBufferCount` counts buffers released to the surface; it is the only frame-level signal for SurfaceView playback and it is not updated in tunneling mode. `onVideoFrameProcessingOffset` reports average early/late offsets and is used only in stats (a late-frame trend), not as a detector.
- `onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs)` fires when the `AudioTrack` position starts moving. A sink that accepts passthrough and plays **silence with an advancing position** (a mis-reporting soundbar) is undetectable; the user path is product §4.4 "Audio output: Always decode (PCM)". The classic failure (an `AudioTrack` whose head position never moves, or init failure) is detected.
- Media3 1.11's own `StuckPlayerDetector` (ExoPlayer builder) stays enabled as a backstop: `setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_BACKSTOP_MS = 30_000)` (default 600 s); stuck-playing keeps its default (10 s on devices, 30 s on emulators, verified). It ends playback with `ERROR_CODE_TIMEOUT` + `StuckPlayerException`, which the ladder treats as `Stuck` → `Reprepare` (§4.3 L39).

A PTS reset after a reconnect is caught first by the PCR check (§3.3.5: detected within one PCR interval, ≤ 100 ms; acted on when the buffer drains, reviewed 2026-10-13, S1); if the stream carries no PCR or the jump is within tolerance but still breaks playback, b2 (position stuck, 4 s) or c (frozen, 3 s) fire. `AnalyticsListener.onAudioSinkError` with an `AudioSink.UnexpectedDiscontinuityException` is logged as a `PCR_JUMP`-class event (diagnostics only).

(reviewed 2026-10-13) Media3's backstop, verified: `StuckPlayerDetector` (`common/util/StuckPlayerDetector.java`) checks BUFFERING with `playWhenReady` and no suppression, and fires only if the **buffered position** did not change for the timeout (`:159–197`); stuck-playing fires when `isPlaying` and the position did not change (`:229–258`). Both are off while paused, suppressed or held for AFR. Our detectors fire first (3–6 s vs 10/30 s) and every ladder action re-prepares, which resets Media3's timers, so they do not double-fire. Robolectric's shadow audio decoders drop every sample (`ShadowMediaCodecConfig.java:186–190, 330–340`), so real-player Robolectric tests set `audioExpected = false`.

### 3.8 Decoders and audio (WP-C)

#### 3.8.1 `BoxMediaCodecSelector : MediaCodecSelector`

`getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)`:
- `list = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)` (what `MediaCodecSelector.DEFAULT` does).
- **Video MIME**, mode = channel override ?: `codecPolicy.videoMode`:
  - `AUTO`: stable-partition hardware first (`MediaCodecInfo.hardwareAccelerated`; Media3 derives it below API 29 from the name), then software (`softwareOnly`: `c2.android.*`, `OMX.google.*`).
  - `HARDWARE_ONLY`: drop `softwareOnly` entries (empty → the renderer reports unsupported → `CODEC_UNSUPPORTED`).
  - `SOFTWARE_PREFERRED`: software first (`MediaCodecUtil.getDecoderInfosSortedBySoftwareOnly`, as `MediaCodecSelector.PREFER_SOFTWARE`).
- **Audio MIME** in `codecPolicy.softwareAudioMimes` and `FfmpegAvailability.supports(mime)` (new in commit 0: `isAvailable && FfmpegLibrary.supportsFormat(mime)`, looked up reflectively like `isAvailable` so `:ffmpeg` still compiles without the AAR; `supportsFormat(String)` is `public static` in the 1.11.1 AAR, verified) → **empty list**: `MediaCodecAudioRenderer` reports `FORMAT_UNSUPPORTED_SUBTYPE`, and the track selector picks `FfmpegAudioRenderer`. This is per-MIME "PREFER" at runtime without rebuilding the renderer list. Passthrough is unaffected because `MediaCodecAudioRenderer.supportsFormat` checks `audioSink.supportsFormat` before it asks the selector (verified, `MediaCodecAudioRenderer.java:357–363`): to force decoding of a passthrough-capable codec, deny it in the audio gate too (§3.8.3).
- The selector is consulted at track selection and codec init, so a change takes effect at the next prepare (the ladder always re-prepares).

`BoxRenderersFactory` (subclass of `DefaultRenderersFactory`): `setExtensionRendererMode(if (settings.softwareAudioFallback) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_OFF)`, `setEnableDecoderFallback(true)`, `setMediaCodecSelector(boxSelector)`, `buildAudioSink(...)` overridden to return `PassthroughGateAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setAudioProcessors(arrayOf(stereoDownmix)).build())`. `ExperimentalFfmpegVideoRenderer` is present in the locally built AAR and will be added by the factory in ON mode, but its `supportsFormat` always returns `FORMAT_UNSUPPORTED_TYPE` (verified by `javap` on the AAR), so it is inert.

`softwareAudioMimes` = (`settings.preferSoftwareAudio` → every MIME FFmpeg supports) ∪ (MIME types learned failing on this device this session, from ladder L31/d1). **Auto-PREFER for MP2:** none needed: in ON mode the platform renderer reports MP2 (`audio/mpeg-L2`) unsupported where no platform decoder exists and the FFmpeg renderer is chosen. `DeviceCapabilities` records whether a platform MP2 decoder exists, for diagnostics. **LATM:** no FFmpeg path needed (decision §0.8); FFmpeg's `aac_latm` decoder cannot help either, because Media3 never hands raw LATM to a decoder.

#### 3.8.2 Decoder fallback

- `setEnableDecoderFallback(true)` makes `MediaCodecRenderer` try the next decoder of the selector's list when one fails to initialise. Only when all fail does `ERROR_CODE_DECODER_INIT_FAILED` reach the ladder (L28–L32).
- Software video decoding loses deinterlacing and HDR and cannot do 1080p50 HEVC on an A53 (architecture review B4.7). The ladder switches to `SOFTWARE_PREFERRED` for that channel only for the current session; a remembered per-channel override is the user's choice (`setVideoDecoderOverride`). A `SOFTWARE_VIDEO` warning is shown.

#### 3.8.3 Passthrough and output (`PassthroughGateAudioSink`, `StereoDownmixProcessor`)

- `PassthroughGateAudioSink : ForwardingAudioSink`. `supportsFormat(format)` and `getFormatSupport(format)` return false / `SINK_FORMAT_UNSUPPORTED` for an **encoded** format (`sampleMimeType` not `audio/raw`) when `deniedPassthrough` contains its MIME or `"*"`; otherwise they delegate. Effect: `MediaCodecAudioRenderer` decodes instead of bypassing (it uses `shouldUseBypass = audioSink.supportsFormat(format)`, verified), and `FfmpegAudioRenderer` outputs PCM. PCM formats are always delegated.
- `deniedPassthrough` = `"*"` when `settings.audioOutput != AUTO`, plus encodings learned failing on the current audio route (d1/L33), kept in memory per route id (`AudioDeviceInfo` id or "default") for the process lifetime (persisting it is §8 Q6).
- Media3's `DefaultAudioSink` (1.11: through `AudioTrackAudioOutputProvider`) tracks HDMI capability changes itself (`ACTION_HDMI_AUDIO_PLUG` on API < 29, `AudioTrack.isDirectPlaybackSupported`/`getDirectProfilesForAttributes` on newer). M2 does not register its own `AudioCapabilitiesReceiver` for playback; `DeviceCapabilities` registers one only to refresh the diagnostics dump and to clear the learned per-route denials when the route changes.
- `StereoDownmixProcessor`: an `AudioProcessor` wrapping `ChannelMixingAudioProcessor` with `ChannelMixingMatrix.createForConstantGain(n, 2)` for n = 3..8. It is **active only when `forceStereo` is true at `configure()` time** (it is reconfigured on every format change and flush, so a re-prepare applies a change). Used for `audioOutput == STEREO` and ladder L34 (a multichannel PCM `AudioTrack` that fails to initialise).
- Audio offload stays disabled (default). Float output disabled (some Fire OS sinks mishandle it).

#### 3.8.4 Tunneling

`DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled(settings.tunneling)` (default off). Media3 enables it only when the selected audio and video renderers both support it. Any video decoder error, audio-track error or frozen-video verdict while `player.isTunnelingEnabled` is true → `DisableTunnelingAndReprepare` first (sets the parameter false for the rest of the process, emits `TunnelingDisabled`). M5 may then turn the setting off for good.

#### 3.8.5 TS extractor flags and compatibility

- Defaults (every TS session): `FLAG_ALLOW_NON_IDR_KEYFRAMES` (many IPTV encoders send no IDR; without it the player can sit in BUFFERING), `FLAG_DETECT_ACCESS_UNITS` (streams without AUD NAL units). `MODE_SINGLE_PMT`. (reviewed 2026-10-13, S5: `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` is **not** set. DVB carries DTS as stream type 0x06 with a DTS descriptor, which the PMT reader maps to `TS_STREAM_TYPE_DTS` without the flag (`TsExtractor.java:878–896`); 0x82 is the Blu-ray (HDMV) code and, on North American cable restreams, SCTE-27 subtitles. With the flag, a 0x82 subtitle PID becomes a `DtsReader` that never finds a sync word and never emits a `Format`, which blocks prepare — see the guard below.)
- **Dead-track guard** (reviewed 2026-10-13, S5). `ProgressiveMediaPeriod.maybeFinishPrepare` returns until **every** sample queue has a format (`ProgressiveMediaPeriod.java:978–986`). A PMT entry whose PID carries no data (common in restreams: a second audio PID that is never fed), a 0x82 PID, or an AAC-LATM stream with `audioMuxVersion == 1` (`LatmReader.java:191–234`: the format is only output in the version-0 branch) therefore keeps the tune in BUFFERING forever. `TsExtractorsFactory` wraps each `TsExtractor`'s `ExtractorOutput`: `track(id, type)` returns a lazy `TrackOutput` that creates the real track only on its first `format()` call, and `endTracks()` is forwarded once every declared track has a format or `DEAD_TRACK_GRACE_BYTES` (1 MiB) of TS has been read since the PMT, whichever is first; tracks still without a format then become discarding outputs, and a `DEAD_TRACK` event (PID, stream type) is logged. The guard is pure bookkeeping (no copy of sample data) and is unit-tested with a fixture whose PMT lists an empty AC-3 PID.
- Compat level 1 (learned per channel by b1/b2/c escalation, stored in `playback_flags`): `MODE_MULTI_PMT` (MPTS feeds where the first PMT is not the wanted programme). Level 0 → 1 only; there is no level 2 in v1.
- Not set: `FLAG_IGNORE_*` (protocols §2.2). Timestamp-search bytes stay default; they are unused because the length is unknown (verified: `canReadDuration = inputLength != C.LENGTH_UNSET && !isModeHls`, `TsExtractor.java:431`).

#### 3.8.6 Preferred languages and roles

`TrackSelectionParameters`: `setPreferredAudioLanguages(memory.audioLanguage ?: …settings.preferredAudioLanguages ?: device locale)`, `setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)` (deprioritises DVB audio description), `setPreferredTextLanguages(...)` and `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)`. Explicit user choices become `TrackSelectionOverride`s for the current tune and are remembered as a language (overrides are per-`TrackGroup` and do not survive a re-tune).

### 3.9 AFR and HDR (WP-C `DisplayModeController`, WP-A `DisplayModePicker`)

#### 3.9.1 Modes

| Setting | ExoPlayer `setVideoChangeFrameRateStrategy` | App-side switching |
|---|---|---|
| OFF | `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF` | none |
| SEAMLESS (default) | `VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` | none. Media3's `VideoFrameReleaseHelper` estimates the frame rate itself (`FixedFrameRateEstimator`) and calls `Surface.setFrameRate(…, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` on API 30+; the platform only switches when seamless. Below API 30 this is a no-op (product §4.4 hides it there) |
| ALWAYS | `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF` (Media3 docs: required when the app uses `CHANGE_FRAME_RATE_ALWAYS` itself, verified) | §3.9.2 |

#### 3.9.2 ALWAYS: gating and the two API paths

- **Gating:** only when `viewport == FULL` **and** `AFR_DWELL_MS` (2,000) passed since the last `tune()`/`zap()` **and** an fps is known (cached `fpsMilli` or measured). Never in MINI (switching blanks the TV while the user browses, B1.8). Returning to MINI keeps the content mode (no ping-pong); `AFR_REVERT_IDLE_MS` (30,000) in MINI/HIDDEN, `stop()` or app background reverts to the default mode.
- **API ≥ 31:** `surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)` on the root `SurfaceView`'s `holder.surface` (captured by `SurfacePlayer.setVideoSurfaceView`, §3.11). Revert: `setFrameRate(0f, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`. The 3-argument overload and both `CHANGE_FRAME_RATE_*` constants are **API 31** (`api-versions.xml`); API 30 has only `setFrameRate(float, int)`, whose switching behaviour for non-seamless modes is not specified (**UNVERIFIED**), so API 30 uses the window path.
  (reviewed 2026-10-13, S12) Three corrections. (1) The vote belongs to the `Surface`, so it is lost when the `SurfaceView`'s surface is destroyed (standby, Home); `DisplayModeController` adds a `SurfaceHolder.Callback` to the captured view and re-applies the current vote in `surfaceCreated`. (2) `VideoFrameReleaseHelper` guards **both** `updateSurfacePlaybackFrameRate` and `clearSurfaceFrameRate` with `changeFrameRateStrategy == OFF` (`VideoFrameReleaseHelper.java:308–313, 335–341`), so switching the strategy from `ONLY_IF_SEAMLESS` to `OFF` at runtime leaves ExoPlayer's last vote on the surface; when the AFR setting changes to ALWAYS or OFF, the controller first calls `setFrameRate(0f, FRAME_RATE_COMPATIBILITY_DEFAULT)` (or `clearFrameRate()` on API 34+). With `OFF` set, ExoPlayer never touches the surface's frame rate again, including on surface changes, so it does not fight our vote (verified). (3) On API 31+ the platform also honours the user's "Match content frame rate" preference (`DisplayManager.getMatchContentFrameRateUserPreference()`, API 31); whether `CHANGE_FRAME_RATE_ALWAYS` overrides a "Seamless only" preference is **UNVERIFIED** (D6). `DeviceCapabilities` records the preference in the S16 dump and the AFR stats line shows it.
- **API < 31 (Fire OS 6/7/8):** `DisplayModePicker.pick(current, supported, fps)` (below) → `request.value = mode.id`; `WindowDisplayModeBinder` (bound by the Activity) sets `window.attributes.preferredDisplayModeId`. The controller sets `playWhenReady = false`, emits `Connecting(SWITCHING_DISPLAY_MODE)`, and resumes on `DisplayManager.DisplayListener.onDisplayChanged(displayId)` when `display.mode.modeId == requested`, or after `AFR_SWITCH_TIMEOUT_MS` (3,000). Live TS keeps loading while held, so the buffer grows by the hold time (bounded by the byte cap). Revert: `request.value = 0`.
- **Mode choice (`DisplayModePickerImpl`):** candidates = supported modes with the **current physical width/height** (resolution switching is off, protocols §6.3). Prefer, in order: refresh = fps (±0.1 %), refresh = 2 × fps, refresh = k × fps for k = 3..5 (each ±0.1 %), choosing the lowest refresh ≥ 48 Hz among equals. If the current mode already satisfies the first matching rule, return null (no switch). 25 fps → 50 Hz; 29.97 → 59.94 Hz; 23.976 → 23.976 Hz if offered else 47.95 else none.

#### 3.9.3 Where fps comes from

- `Format.frameRate` when set (HLS `FRAME-RATE`, MP4). **Live TS never sets it**: the TS H.264/H.265 readers do not fill `frameRate` (verified: no `setFrameRate` in `extractor/ts/*`).
- Otherwise `FpsEstimator` (`ExoPlayer.setVideoFrameMetadataListener`, playback thread): median of `FPS_ESTIMATE_FRAMES` (30) consecutive presentation-time deltas, ignoring deltas > 200 ms, then `DisplayModePicker.snapFps` to {23.976, 24, 25, 29.97, 30, 47.952, 48, 50, 59.94, 60} within `FPS_SNAP_TOLERANCE` (2 %). The listener is attached only while AFR is ALWAYS or the stats overlay is on, and detached after a stable estimate.
- **Interlaced caveat:** 1080i50 arrives as 25 frames/s (two fields per frame) and the SoC deinterlaces to 50p or 25p. Choosing 50 Hz for 25 fps (rule "refresh ≥ 48 Hz") is right in both cases. `VideoInfo.interlacedHint` is true when the H.264/H.265 SPS says field coding; Media3 does not expose it, so v1 leaves it false (**UNVERIFIED** whether it can be derived cheaply). (reviewed 2026-10-13: it can, for H.264. `H264Reader` puts the SPS NAL unit, start code included, in `Format.initializationData[0]` (`H264Reader.java:222–225`), and the public `NalUnitUtil.parseSpsNalUnit(data, 3, data.size).frameMbsOnlyFlag` (`media3-container`, `NalUnitUtil.java:808, 882`) is false for field/MBAFF coding. A few lines in `TrackController`, once per format; HEVC stays false. See §8 Q12.)
- The snapped fps is cached per channel (`fps_milli`) so the next tune of that channel in FULL can switch right after the dwell without waiting for 30 frames.

#### 3.9.4 Interaction with play()

`DisplayModeControllerApi.awaitSettled(onSettled)` is called by the controller before `playWhenReady = true` only when a switch is already pending at tune time (FULL viewport, cached fps, dwell satisfied: e.g. CH+ after 2 s in full screen). Otherwise play starts immediately and a later switch pauses briefly as above.

#### 3.9.5 HDR in the mini viewport

- `VideoInfo.hdr` from the selected video `Format`: `video/dolby-vision` → `DOLBY_VISION`; `ColorInfo.isTransferHdr(colorInfo)` with `colorTransfer == COLOR_TRANSFER_ST2084` → `HDR10`, `COLOR_TRANSFER_HLG` → `HLG`, other HDR → `UNKNOWN_HDR`.
- `settings.hdrInPreview == POSTER` and `viewport == MINI` and HDR → `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`: audio keeps playing, the connection stays, and no HDR output reaches the display (no HDMI mode switch while browsing, B1.6). `VideoInfo.posterMode = true`; M3 draws the poster ("HDR · press OK for full screen"). Viewport FULL → video re-enabled (decoder init, first frame).
- (amended 2026-10-14, M3 code review H-B1) **The poster is the controller's state and the viewport stays MINI.** Poster mode is decided here, from the viewport the shell set, and is never a reason for the shell to change the viewport: the controller keeps the video track off and the shell draws the poster in the MINI hole while `setViewport(MINI)` stays in force. The shell must not map `posterMode` to its HIDDEN "still" (Still setting, low memory, nothing tuned): HIDDEN ends poster mode, which made the shell ask for MINI again, an endless MINI ↔ HIDDEN loop on every HDR channel in the guide (M3 `review/m3-code-review.md` H-B1). Test: `PlaybackControllerStateTest.hdrPosterIsStableWhileTheViewportStaysMini`.
- (amended 2026-10-14, M3 code review H-S10) `VideoInfo.renderedFirstFrames` counts `onRenderedFirstFrame` callbacks (kept across tunes): the shell lifts its still → FULL scrim on the next one instead of waiting for a `Playing` state that does not change when the track is re-enabled.
- The first tune of an HDR channel learns HDR only after the video format arrives, so a short HDR output may happen once; the channel's `knownHdr` bit pre-disables video on the next MINI tune. D6 on real hardware decides whether this is enough.
- "Force SDR" / tone-mapping is out of scope for v1 (user-issues §3).

### 3.10 Lifecycle and process (WP-D `LifecycleCoordinator`)

#### 3.10.1 Ownership

- `PlaybackControllerImpl` is a Hilt `@Singleton`. It creates the `PlayerBundle` lazily on the main looper (first `tune()`, `prewarm()` or surface attach) and owns it for the life of the process.
- (reviewed 2026-10-13, blocker B3) **No idle release in v1.** The earlier rule (release on `TRIM_MEMORY_COMPLETE` or after 5 min in the background, re-create on the next tune) breaks video: `surfacePlayer` and `SessionPlayer` wrap one fixed `ExoPlayer` (`ForwardingPlayer`/`ForwardingSimpleBasePlayer` take the player in their constructor), the `MediaSession` holds the old player, and `PlayerSurface`'s `LaunchedEffect(view, player)` does not re-run when only the hidden `ExoPlayer` changed, so the new player would never get the `SurfaceView` (audio, black picture). What the release would have freed is already freed by `stop()`: without foreground mode it resets the renderers (`ExoPlayerImplInternal.stopInternal`, `:2041–2048`), which releases every `MediaCodec` (`MediaCodecRenderer.onReset` → `releaseCodec`, `:867–874`), and `loadControl.onStopped` resets the allocator. What remains is a few hundred KB of player objects. If a later milestone wants the release back, `surfacePlayer` must become a re-targetable facade (a `SimpleBasePlayer` that forwards `handleSetVideoOutput` to the current player and re-applies the recorded view on re-creation) and the session must call `MediaSession.setPlayer`.
- `PlaybackService` never owns the player (architecture review B6.2).

#### 3.10.2 Stop and resume rules

| Trigger | Detection | Action |
|---|---|---|
| App to background (Home, another app, Activity `onStop` with no other started Activity) **while the device is interactive** | `ProcessLifecycleOwner` `ON_STOP` and `PowerManager.isInteractive() == true` and the default display is `STATE_ON` | `stop(BACKGROUND)` **immediately** (TV norm; frees the provider connection, research §4). The target is kept |
| Screen off / standby while the app is foreground | `ON_STOP` with `isInteractive() == false` or the display `STATE_OFF`/`STATE_DOZE*`, or `Intent.ACTION_SCREEN_OFF` (whichever comes first) | start `STANDBY_GRACE_MS` (30,000); on expiry `stop(STANDBY)`; `ACTION_SCREEN_ON` / `ON_START` within the grace cancels it. During the grace only audio plays (the `SurfaceView`'s surface is destroyed at `onStop`) |
| TV switched off with its own remote (device stays awake) | `AudioManager.ACTION_HDMI_AUDIO_PLUG` with `EXTRA_AUDIO_PLUG_STATE = 0`, or the default `Display` state becomes `STATE_OFF` (`DisplayListener`) | same 30 s grace → `stop(HDMI_UNPLUGGED / DISPLAY_OFF)` (plan §3 "Process") |
| Back to foreground | `ON_START` with a kept, resumable target | if `settings.resumeAfterStandby` or the stop was < 15 min ago (`RESUME_REAUTH_AFTER_MS`): `refreshAccount` first when the stop was ≥ 15 min ago (Xtream re-auth, plan §3), then **re-resolve and prepare a fresh session at the live edge** (never resume a stale TS connection). Else stay `Stopped` (M3 shows the guide, nothing playing) |
| Display back on / HDMI plugged within grace | events above | cancel the grace timer; if playback was stopped, resume as above |
| Inactivity "Still watching?" (B4.9) | — | M4/M5 (needs UI); M2 exposes nothing extra |

(reviewed 2026-10-13, blocker B4) The earlier table keyed "background" on `ON_STOP` alone. On AOSP, Android TV included, going to sleep (power key, CEC standby, `UiDevice.sleep()`) **stops the top Activity**, and `ProcessLifecycleOwner` dispatches `ON_STOP` 700 ms after the last Activity paused (`lifecycle-process` 2.10.0, `ProcessLifecycleOwner.kt:69, 110–131`). Screen-off would therefore always have hit the immediate `stop(BACKGROUND)` row, the 30 s grace was dead code, and I-20 would have seen `Stopped(BACKGROUND)`. `isInteractive()` turns false before the Activity stops, so checking it in the `ON_STOP` handler separates the two cases. Fire OS behaviour on CEC TV-off (sleep vs. display-off only) is **UNVERIFIED** (D7); both paths lead to the grace.

The M2 brief's "`ACTION_SCREEN_OFF`/`onStop` → stop after 30 s grace" is deliberately split: a 30 s grace on `onStop` would keep audio playing over the launcher and hold the account's only connection after the user pressed Home. The plan's 30 s grace is for TV-off/HDMI-unplug, where the user did not leave the app (§8 Q1).

#### 3.10.3 Surface across standby

`PlayerSurface` re-attaches when the `SurfaceView` is recreated; ExoPlayer clears its output on `surfaceDestroyed` itself. The controller never caches a `Surface` (research §4). AFR's captured `SurfaceView` reference is weak and replaced on every `setVideoSurfaceView`.

#### 3.10.4 MediaSession and `PlaybackService`

- The controller builds `MediaSession.Builder(context, sessionPlayer).setId("thebox").build()` when it creates the player. `SessionPlayer` is a `ForwardingSimpleBasePlayer` (Media3's recommended way to hide commands: `ForwardingPlayer` would also need every listener wrapped so `onAvailableCommandsChanged` does not leak, verified in its Javadoc) whose `getState()` advertises `COMMAND_PLAY_PAUSE`, `COMMAND_STOP`, `COMMAND_SEEK_TO_NEXT`, `COMMAND_SEEK_TO_PREVIOUS`, `COMMAND_GET_CURRENT_MEDIA_ITEM`, `COMMAND_GET_METADATA`; seeking commands only for archive. `handleSetPlayWhenReady` → `resume()/pause()`, `handleSeek` with `COMMAND_SEEK_TO_NEXT/PREVIOUS` → `zap(+1/−1)`, `handleStop` → `stop(USER)`; each returns an immediate future.
- ~~`PlaybackService.onGetSession` returns `controller.session` … overrides `onUpdateNotification` to do nothing …~~ (reviewed 2026-10-13, blocker B5) **No `MediaSessionService` in the v1 manifest.** The override alone does suppress Media3's notification path (`MediaSessionService.java:863–868`: the manager is only called when the deprecated 1-arg method ran), but it leaves two failures:
  1. With a `MediaSessionService` declared and no `MediaButtonReceiver`, media3 gives the platform session a media-button `PendingIntent.getForegroundService(...)` on API < 31, which is every Fire TV (`MediaSessionLegacyStub.java:207–245`). After the process dies with our session as the last media-button session, a CEC/Bluetooth PLAY calls `startForegroundService(PlaybackService)`; `onStartCommand` (`MediaSessionService.java:524–555`) gets our session from `onGetSession`, nothing calls `startForeground`, and the app crashes with `ForegroundServiceDidNotStartInTimeException` (ANR below API 31). Media3 itself avoids this only for a `null` session (`:530–536, 578–608`).
  2. The session stays active after Home; the platform routes media keys to the last active session even when its app is in the background, so a CEC PLAY would call `resume()` and re-tune live TV in the background, holding the account's connection — the opposite of D15/D16.
  Fix: a `MediaSession` works without a service. With no service and no receiver, media3 registers a runtime, non-exported media-button receiver and a platform session (`MediaSessionLegacyStub.java:224–235`), so Assistant and CEC keys still reach it while the app is foreground; keys the Activity does not consume are forwarded by the window to the session. The lead removes the `<service>` element and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in commit 0 (§1.8 item 6a). `SessionPlayer` advertises `COMMAND_PLAY_PAUSE`, `COMMAND_SEEK_TO_NEXT/PREVIOUS` only while `ProcessLifecycleOwner` is at least STARTED (it invalidates its state on `ON_START`/`ON_STOP`), and `handleSetPlayWhenReady(true)`/`handleSeek` are no-ops in the background. v1.1 (background audio, PiP) and v2 (DVR) re-add the service with a real `startForeground` path and `onGetSession` returning `null` unless `backgroundPlaybackActive`. `SessionPlayer` exposes a sanitised `MediaItem` (mediaId and `MediaMetadata` only, never a URI).
- Key scope: M2 handles only media-session commands (play/pause/stop/next/previous from CEC or Assistant). D-pad, CH±, digits, `KEYCODE_LAST_CHANNEL`, `KEYCODE_GUIDE`, colour keys and long-press OK are M4 (Activity `dispatchKeyEvent`), calling the controller API.

#### 3.10.5 Audio focus, noisy, wake

- `ExoPlayer.Builder.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* handleAudioFocus = */ true)`.
- `setHandleAudioBecomingNoisy(true)`: Bluetooth headphones disconnecting pause playback; for live this is a `pause()` (freeze, release after 10 s).
- `setWakeMode(C.WAKE_MODE_NETWORK)`: partial wake lock + Wi-Fi high-performance lock while playing (stops Wi-Fi power save throttling on sticks). Uses the `WAKE_LOCK` permission `:player`'s manifest already declares. (reviewed 2026-10-13, verified: `WakeLockManager`/`WifiLockManager` need only `WAKE_LOCK`; the locks are held only in READY/BUFFERING with `playWhenReady` and released on stop (`ExoPlayerImpl.java:3116–3130`). No FGS is needed; the CPU lock matters during the 30 s standby grace.)

#### 3.10.6 Screen-on

The UI layer owns `FLAG_KEEP_SCREEN_ON` (`:app` `MainActivity` and the debug harness collect `controller.keepScreenOn`). `keepScreenOn` = state is Connecting, Buffering, Stalled, or Playing and not paused. It is false in Idle, Stopped, Error and live pause, so screensavers and ambient mode can start (research §4).

### 3.11 Surface rule (decision §0.11)

- **One** `SurfaceView`, created by `PlayerSurface(surfaceType = SURFACE_TYPE_SURFACE_VIEW)` inside `VideoLayer`, first child of the root `Box`, behind all content. No `graphicsLayer`, alpha, clip, offscreen compositing or `RenderEffect` on any ancestor (CLAUDE.md, B1.2). Screen transitions fade their own content, never a parent of the surface.
- **Why `PlayerSurface`** and not a raw `AndroidView(SurfaceView)`: `PlayerSurface` (1.11.1, verified) attaches/clears the player's video view with the composable lifecycle and carries the API 34 `SurfaceSyncGroup` workaround for SurfaceView resizes (androidx/media#1237) — Fire OS 14 is API 34 and mini ↔ full is a resize. `ContentFrame` is rejected because it sizes the surface itself; we need `videoRect` control (B1.5d).
- **Why a `ForwardingPlayer`** (`SurfacePlayer`) instead of the `ExoPlayer`: M3 must not be able to call `prepare()` or `seekTo()` behind the controller's back. `SurfacePlayer` is a plain `ForwardingPlayer`: `isCommandAvailable`/`getAvailableCommands` report only `COMMAND_SET_VIDEO_SURFACE`, every mutating method other than `setVideoSurfaceView`/`clearVideoSurfaceView` is a no-op, `setVideoSurfaceView(view)` records the view for AFR (§3.9.2) and forwards, and listener registration is forwarded (`PlayerSurface` only listens for `onSurfaceSizeChanged`; nothing else consumes this player, so the listener-wrapping caveat does not matter here). (reviewed 2026-10-13, verified against `media3-ui-compose` 1.11.1 `PlayerSurface.kt`: it needs exactly `COMMAND_SET_VIDEO_SURFACE` (checked with `isCommandAvailable` before `setVideoSurfaceView`), creates a plain `SurfaceView` in an `AndroidView` with no clip or `graphicsLayer` of its own, and the API 34 `SurfaceSyncGroup` workaround is real (`SDK_INT == 34`, skipped under Robolectric). A plain `ForwardingPlayer` is the right base: `SimpleBasePlayer`'s state model adds nothing here. Its one limit is the fixed delegate, which is why the `ExoPlayer` is never re-created (§3.10.1, B3).)
- **HIDDEN / NONE viewport** (a screen covers the video, or no UI is attached): the controller disables the video track (`setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`: no decoding, no surface output), audio continues and the connection is kept; `VideoLayer` keeps the surface attached at 1×1 px. Back to MINI/FULL re-enables the track (decoder init and first frame, ~300 ms, the last frame is not available). Whether a long HIDDEN period should stop playback is §8 Q8.
- **Mini ↔ full** is a change of `videoRect` in one layout pass (B1.5a: snap behind a 120 ms scrim drawn by M3). The surface, the decoder and the connection are untouched.
- **Aspect:** `VideoRectCalculator.place(hole, w, h, pixelRatio, mode)`: FIT → largest rect of the display aspect `w × pixelRatio : h` centred in the hole; RATIO_16_9/RATIO_4_3 → that aspect centred; FILL/ZOOM → the whole hole with `cropToFill = true`, and the controller sets `player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)` (otherwise `SCALE_TO_FIT`). M3/M4 paint the part of the hole outside `videoRect` opaque black (B1.5b).
- **What M2 ships vs M3:** M2 ships `VideoLayer`, `VideoRectCalculator`, `controller.video` (size, aspect, poster) and the debug harness's full-screen use. M3 ships the ambient background with the cleared rounded hole, the ring, the scrim and the viewport rect state for the guide.

### 3.12 Tracks and per-channel memory (WP-C `TrackController`, WP-D wiring)

- `TrackInfo` is rebuilt from `onTracksChanged(Tracks)`: audio groups → `AudioTrackOption` (language, label, codec name from MIME: `audio/eac3` "E-AC-3", `audio/ac3` "AC-3", `audio/mp4a-latm` (`MimeTypes.AUDIO_AAC`) "AAC", `audio/mpeg-L2` "MP2", `audio/mpeg` "MP3", `audio/vnd.dts*` "DTS", `audio/opus` "Opus"), channels, `ROLE_FLAG_DESCRIBES_VIDEO`, `isTrackSelected`, `isTrackSupported`); text groups → `TextTrackOption` (DVB subtitles, CEA-608/708, WebVTT). `TrackRef` = `(type, TrackGroup.id, index)`.
- `selectAudioTrack(ref, remember)`: `TrackSelectionOverride(group, index)` via `setOverrideForType`; `remember` → `ChannelMemory.update { it.copy(audioLanguage = format.language) }`.
- `selectTextTrack(null)` → text disabled; `remember` stores `""` (off). Subtitle *rendering* (`SubtitleView`/cues) is M4.
- `setAspectMode(mode, remember)` → `video.aspectMode`, scaling mode (§3.11), memory `aspect`.
- `setVideoDecoderOverride(mode, remember)` → memory `videoDecoder`, `codecPolicy` for this channel, then `Reprepare`.
- Defaults at tune: §3.8.6 and `settings.defaultAspect`.

### 3.13 Diagnostics (WP-C stats, WP-A log, WP-D wiring)

- **`StreamStats` cadence:** `StatsCollector` publishes at most every `STATS_INTERVAL_MS` (500) and only while `setStatsEnabled(true)`; collection of counters continues regardless (cheap fields). One `StreamStats` instance per publish (~300 bytes) → ≤ 0.6 KB/s. Strings (MIME, codecs, decoder names, host) are captured on change events (`onVideoInputFormatChanged`, `onAudioInputFormatChanged`, `onVideoDecoderInitialized`, `onAudioDecoderInitialized`, telemetry host change), not rebuilt per tick.
- **Fields:** decoder names from `onVideoDecoderInitialized`/`onAudioDecoderInitialized`; size from `onVideoSizeChanged`; fps from §3.9.3; HDR from the format; bitrate from telemetry (Media3's `DefaultBandwidthMeter` only reports when a transfer *ends*, verified, so it never reports on an endless TS load); buffer ms = `player.totalBufferedDuration`; dropped frames accumulated from `onDroppedVideoFrames`; HTTP status, final host, redirects and reconnects from telemetry; broker snapshot; buffer profile; AFR mode and current refresh rate; tunneling.
- **Event log:** `PlaybackEventLogImpl`, a ring of `EVENT_LOG_CAPACITY` (200) `PlaybackEvent`s. `detail` goes through `Redact.text` and is cut to `EVENT_DETAIL_MAX_CHARS` (160) inside `add`. Plus a ring of `ZAP_HISTORY` (50) `ZapRecord`s (architecture review C1: "last 50 zaps with per-phase timings"). M5's diagnostics screen and log export read them through `controller.diagnostics`.
- **`BoxLog` tags:** `PlaybackController`, `StreamResolver`, `StreamBroker`, `LiveTsDataSource`, `StreamHttp`, `LoadPolicy`, `ErrorLadder`, `Watchdog`, `BufferFsm`, `PlayerFactory`, `CodecPolicy`, `AudioPolicy`, `DisplayMode`, `Lifecycle`, `Media3` (the bridged Media3 log). INFO: one line per tune (`ZapRecord`), per decision, per reconnect, per fallback. DEBUG: per state change. Never per packet, per read or per tick.
- **Media3 logging:** `Media3LogBridge` calls `androidx.media3.common.util.Log.setLogger(...)` at player creation so Media3's own messages (e.g. "Playback error" with cause chains) go through `BoxLog` and its redaction; `Log.setLogLevel(LOG_LEVEL_WARNING)` in release, `LOG_LEVEL_ALL` in debug.
- **Trace:** `Trace.beginAsyncSection("zap", tuneId)` … `endAsyncSection` at first frame on API ≥ 29 (B4.6) for Perfetto; plain logs below.

### 3.14 Catch-up seam (`tuneArchive`, WP-D with WP-A's resolver)

M2 implements the transport for one archive request; M4 builds `ArchivePlayback` (virtual wall-clock timeline, probing variants, auto-continue, jump to live, scrub bar) on top.

- `StreamUrlResolver.catchupTarget(identity)` → `CatchupTarget(liveUrl = resolved.liveUrlTemplate, headers = resolved.headerTemplates, catchup = channel.catchup, xtream = resolved.xtream, winner = playlist.catchup_probe_winner)` — M4 passes it to `CatchupUrlBuilder.build`.
- `tuneArchive(ArchiveTune(identity, request, startOffsetMs))`: `StreamUrlResolver.expandArchive` runs `expandCredentials` over `request.url` and header values (**after** the catch-up expansion, Later 28), then the normal pipeline with `isArchive = true`, the format hint from the expanded URL, transport `http` (finite; **Range always allowed**), `LeaseKind.PLAYBACK`, and `BufferMode.MEDIUM` (no back buffer, §3.6.1). `startOffsetMs > 0` → `player.seekTo(startOffsetMs)` before prepare when seekable. (reviewed 2026-10-13, S8: `ArchiveRequest.nativeSeekRange` is in **epoch seconds** (`CatchupContracts.kt:101`) and is null for Xtream `.ts` timeshift even though those files are served with `Content-Length` and 206 (`CatchupUrlBuilderImpl.kt:129`), so it cannot gate Range. `PositionSnapshot.isSeekable` comes from Media3's seek map (`player.isCurrentMediaItemSeekable`); the native seek window in player milliseconds is `(epochSec − requestStart.epochSecond) × 1000`.)
- Errors: HTTP 404/410 on an archive request → `CATCHUP_NOT_IN_ARCHIVE` (M4's probe then tries the next variant and persists the winner via `PlaylistRepository.setProbeWinner`). Other statuses use the live ladder rows.
- `position()` → `archiveStartUtcMs = request.requestStart.toEpochMilli()`, so M4 computes the wall-clock position as `archiveStartUtcMs + positionMs` (B5.1). `seekTo` works inside the request only.
- Pause on an archive request is a real pause (no 10 s release).

### 3.15 Performance and memory budgets (all WPs)

Budgets for a 1.5 GB device (Fire TV Stick 4K, `memoryClass` 192, low-RAM class). Measured in M2 on the emulator and logged; enforced as gates in M6 on hardware.

| Budget | Value | How it is met / measured |
|---|---|---|
| Media buffer (Java heap, `DefaultAllocator` 64 KiB segments) | ≤ 24 MiB (low-RAM), ≤ 48 MiB otherwise | `targetBytes`; `allocator.totalBytesAllocated` in stats |
| Other playback Java heap (player, sessions, flows, logs) | ≤ 8 MiB retained | one player, one live session; event ring ~60 KB; no per-tune leaks (12 h soak, M6) |
| Per live session transport buffers | ≤ 64 KiB | 16 KiB read chunk + 12 KiB aligner ring + 2 KiB sniff + OkHttp's segment pool |
| Allocation rate, stats overlay off | ≤ 2 KB/s | reusable `WatchdogSample`, no boxing in detectors, counters in primitives |
| Allocation rate, stats overlay on | ≤ 8 KB/s | one `StreamStats` per 500 ms, strings cached |
| Threads added by M2 | 1 (`box-ts-watchdog`) | plus ExoPlayer's playback/loader threads and OkHttp's dispatcher |
| Zap time (tune() → `onRenderedFirstFrame`) | P50 ≤ 1.2 s, P90 ≤ 2.0 s on Fire TV Stick 4K (plan §6) | `ZapRecord` phases: resolve, lease, first byte, READY, first frame; logged per zap, P50/P90 over the last 50 in diagnostics. **Not asserted** on the emulator |
| Import interference | zap P50 degradation ≤ 15 % during an EPG import | zap import gate (§3.5.5); measured in M6 |

Zap budget per phase (B4.6, Fire TV Stick 4K, warm DNS): end previous ≤ 50 ms (pre-empt), resolve ≤ 20 ms (Room reads; no network), request + 302 150–400 ms, first bytes to PAT/PMT 50–150 ms, keyframe 0–2,000 ms (non-IDR flag helps), start threshold 1,000 ms of media (mostly covered by the panel's burst), codec init 100–300 ms. The `ZapRecord` exposes each phase so D3 can see which one dominates.

`DeviceCapabilities` (WP-C, computed once on a background thread, cached): low-RAM class; `MediaCodecList` decoders for H.264, HEVC, MPEG-2, VP9, AV1, MP2, AC-3, E-AC-3 with HW/SW flags; `Display.getHdrCapabilities()` (API 24+); `Display.getSupportedModes()`; current audio capabilities; `FfmpegAvailability.isAvailable` + version. Feeds the S16 diagnostics dump (`capabilities()`), the selector and the MP2 note.

### 3.16 Debug harness (WP-D, debug builds only)

- `app/src/debug/kotlin/app/thebox/debug/PlaybackDebugActivity.kt`, an `@AndroidEntryPoint ComponentActivity`, declared in `app/src/debug/AndroidManifest.xml` with `exported="true"` and an intent filter for `thebox-debug://play` and `thebox-debug://stop`. Release builds do not contain it (source set), and the `thebox-debug` scheme is not registered there.
- Deep link parameters (all URL-encoded): `url=` (+ optional `ua=`, `max=`, `format=ts|hls`) → `TuneTarget.DirectUrl`; or `playlist=<id>&key=<stableKey>` → `TuneTarget.Channel`; `zap=<key1>,<key2>,…` → a `ZapList` over those keys of the same playlist; `stats=1`. Example: `adb shell am start -a android.intent.action.VIEW -d 'thebox-debug://play?url=http%3A%2F%2F10.0.2.2%3A18080%2Flive%2Ftest%2Ftest%2F1.ts&max=1'`.
- Layout, obeying the surface rule: `Box(Modifier.fillMaxSize()) { VideoLayer(controller.surfacePlayer, fullScreenRect) ; DebugOverlay(...) }` with no ancestor `graphicsLayer`; the overlay is plain `Text` (monospace, 12 design px), transparent background except a 60 % black strip at the bottom: state (`PlaybackState.toString()`, redacted), the stats block when enabled, the last 8 events.
- Keys: DPAD_UP/DOWN and CH± → `zap(∓1, isRepeat = event.repeatCount > 0)`; DPAD_CENTER → `retry()`; MENU or `S` → stats on/off; BACK → `stop()` and finish. It sets `FLAG_KEEP_SCREEN_ON` from `keepScreenOn` and binds `WindowDisplayModeBinder`.
- Viewport: `FULL` while resumed; a `?viewport=mini` parameter shrinks the rect to the design's mini player (1 design px = width/1920; the M3 mini rect from `01-design-reference.md`) for AFR-gating and HDR-poster checks.
- Instrumented tests launch it with `ActivityScenario` and drive the controller through a Hilt `EntryPoint` in the same process (§5.5).

---
## 4. Error taxonomy and decision tables

### 4.1 Classification (`FailureClassifier`, WP-D)

Input: the `PlaybackException` from `onPlayerError`, plus the last `AnalyticsListener.onLoadError` (its `MediaLoadData.dataType` tells manifest from media for HLS), plus session facts. The cause chain is walked from the top; the **first** matching row wins.

| Cause in chain / error code | `FailureSignal` |
|---|---|
| `LiveStreamException(HTTP_STATUS, status)` | `Http(status, PROGRESSIVE)` (archive session: `ARCHIVE`) |
| `LiveStreamException(NETWORK, kind)` | `Network(kind, beforeFirstByte = bytesRead == 0)` |
| `LiveStreamException(RECONNECT_EXHAUSTED)` | `ReconnectExhausted(lastStatus, noBytes)` |
| `LiveStreamException(HTML_PAGE / TINY_BODY / FORMAT_IS_HLS / FORMAT_UNRECOGNIZED / PCR_DISCONTINUITY / LOCAL_LIMIT)` | `HtmlInsteadOfStream` / `TinyBody` / `FormatIsHls` / `FormatUnrecognized` / `PcrDiscontinuity` / `LocalConnectionLimit` |
| `LiveStreamException(PREEMPTED)` or any error whose `tuneId` is not current | ignored (stale session) |
| `HttpDataSource.InvalidResponseCodeException(code)` | `Http(code, origin)`: `HLS_MANIFEST` when the last load error's `dataType == C.DATA_TYPE_MANIFEST`, `HLS_SEGMENT` for `DATA_TYPE_MEDIA` on an HLS session, `ARCHIVE` on archive sessions |
| `HlsPlaylistTracker.PlaylistStuckException` (no playlist update for 3.5 × target duration) | `Stall(HLS_NO_PROGRESS)` (reviewed 2026-10-13, S6: otherwise `Unknown` → INTERNAL) |
| `HlsPlaylistTracker.PlaylistResetException` (media sequence went backwards: panel restarted) | `BehindLiveWindow` (fresh prepare at the live edge) |
| (reviewed 2026-10-13, S3) The network rows below test the **root** cause: walk `cause` through `HttpDataSourceException`, `IOException` and `ExecutionException` wrappers (`OkHttpDataSource.executeCall` wraps every open failure as `IOException(ExecutionException(cause))`); the Media3 error code (2001 for nearly everything) is not used for the kind | — |
| `HttpDataSourceException` caused by `UnknownHostException` | `Network(DNS)` |
| … `ConnectException`, `NoRouteToHostException`, `PortUnreachableException` | `Network(CONNECT)` |
| … `SocketTimeoutException` / `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` | `Network(TIMEOUT)` |
| … `javax.net.ssl.SSLException` | `Network(TLS)` |
| `CleartextNotPermittedException` / `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` | `Network(CLEARTEXT)` (should never happen: cleartext is allowed app-wide) |
| other `HttpDataSourceException` | `Network(RESET)` |
| `BehindLiveWindowException` / `ERROR_CODE_BEHIND_LIVE_WINDOW` | `BehindLiveWindow` |
| `MediaCodecRenderer.DecoderInitializationException` / `ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DECODER_QUERY_FAILED` | `DecoderInit(video = mimeType is video, mimeType, codecInfo?.name)` |
| `ERROR_CODE_DECODING_FAILED` | `DecodingFailed(video, mime of the renderer's format)` |
| `ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES`, `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` | `FormatUnsupported(video, mime)` |
| `AudioSink.InitializationException` / `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` | `AudioTrackInit(encoded = format.sampleMimeType != audio/raw && passthrough active, format.channelCount, mime)` |
| `ERROR_CODE_AUDIO_TRACK_WRITE_FAILED` | `AudioWrite` |
| `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` | `ResourcesReclaimed` |
| `ParserException` / `ERROR_CODE_PARSING_CONTAINER_*` | `Parsing(manifest = false)` |
| `ERROR_CODE_PARSING_MANIFEST_*` | `Parsing(manifest = true)` |
| `StuckPlayerException` (`ERROR_CODE_TIMEOUT`) | `Stuck(stuckType)` |
| anything else | `Unknown(errorCode)` |

Non-exception signals: watchdog verdicts → `Stall(kind)`; `STATE_ENDED` on a live item → `LiveEnded`; resolver `Failed` → `ChannelMissing` / `Credentials(permanent)`; broker `ConnectionLimitException` → `LocalConnectionLimit`.

### 4.2 Loader-level policy (`BoxLoadErrorHandlingPolicy`, WP-B, rules from WP-A's `LoadRetryRules`)

`getRetryDelayMsFor(info)`:

| Exception (`info.exception` chain) | Origin (`info.mediaLoadData.dataType`) | Result |
|---|---|---|
| any `LiveStreamException` | progressive | `C.TIME_UNSET` (the source already retried) |
| `InvalidResponseCodeException` 401, 403, 405, 406, 410, 451, 458, 509 | any | `C.TIME_UNSET` (surface fast; the ladder decides) |
| 404 | `DATA_TYPE_MEDIA` on HLS (segment) | `HLS_SEGMENT_404_DELAY_MS` (500) while `errorCount ≤ HLS_SEGMENT_404_RETRIES` (2), then `C.TIME_UNSET` |
| 404 | manifest / progressive / archive | `C.TIME_UNSET` |
| 416 | any | `C.TIME_UNSET` (Media3 already treats it as non-retryable) |
| 408, 429, 5xx (incl. 520–524) | any | `LOAD_RETRY_BASE_DELAYS_MS[errorCount − 1]` (500, 1000, 2000) × (1 ± `LOAD_RETRY_JITTER` 0.2) while `errorCount ≤ LOAD_RETRY_MAX` (3), then `C.TIME_UNSET` |
| other `IOException` (reset, timeout, DNS) | HLS manifest/segment, archive | same backoff as 5xx |
| `ParserException`, `FileNotFoundException`, `CleartextNotPermittedException`, `Loader.UnexpectedLoaderException` | any | `C.TIME_UNSET` (as Media3's default) |

"Origin" comes from the policy instance's session family (§3.1.3), refined by `dataType` inside HLS: `C.DATA_TYPE_MANIFEST` (4) for playlist loads (`DefaultHlsPlaylistTracker`), `C.DATA_TYPE_MEDIA` (1) for media segments, `DATA_TYPE_MEDIA_INITIALIZATION` (2) for init segments and `DATA_TYPE_DRM` (3) for keys (`HlsSampleStreamWrapper.onLoadError` passes `loadable.type`). (reviewed 2026-10-13, S3/S6)

`getMinimumLoadableRetryCount(dataType)`: `DATA_TYPE_MEDIA_PROGRESSIVE_LIVE` → 1; everything else → 3. `getFallbackSelectionFor` → null (no location/track fallback in v1; HLS track exclusion of a whole variant on a 403 would hide a connection-limit problem). `onLoadTaskConcluded` is a no-op.

### 4.3 Controller ladder (`ErrorLadderImpl`, WP-A; executed by WP-D)

Rows are evaluated top to bottom; the first whose condition holds wins. "Fail(X)" emits `Error(PlaybackError(X, detail, actions))`. Counters (`LadderCounters`) are per tune cycle. Every `ReResolve` first asks the `ReResolveLimiter`; when it refuses, the row's "limiter refused" fallback applies (`RetrySame` with the same delay for transient failures, otherwise the row's Fail).

| # | Signal | Condition | Decision | Fallback / notes |
|---|---|---|---|---|
| L0 | any | the error belongs to a stale `tuneId` | ignore | — |
| L1 | `Http(401)` | — | `ReResolve(0, refreshAccount = true)` once; on the refreshed account: `auth == false` → Fail(AUTH_REJECTED); status EXPIRED → Fail(ACCOUNT_EXPIRED); BANNED/DISABLED → Fail(ACCOUNT_DISABLED); ACTIVE → handle as 403 (L6) | **No loop**: a 2nd 401 in the cycle → Fail(AUTH_REJECTED). Plain M3U (no account): Fail(AUTH_REJECTED) at once |
| L2 | `Http(403/458/509/503)` or `ReconnectExhausted(lastStatus ∈ those)` | `msSinceTuneStart ≤ ZAP_LIMIT_WINDOW_MS` (5,000) or no first frame yet; `limitSteps == 0` | `RetrySame(LIMIT_RETRY_DELAY_MS = 1,500)` | the previous lease is already released (broker); this covers the panel's linger |
| L3 | same | `limitSteps == 1` | `ReResolve(delay = until 3,000 ms after the first failure, refreshAccount = true)` | limiter refused → `RetrySame(1,500)` |
| L4 | same | `limitSteps ≥ 2` and (status is 458/509, or `accountActive ≥ accountMax`, or the account is unknown) | Fail(CONNECTION_LIMIT, detail `active/max` from the refreshed card or the broker) | actions: RETRY, STOP_AND_WAIT |
| L5 | `Http(403)` | `limitSteps ≥ 2`, account shows a free slot, `uaFallbackAvailable` (setting on, channel and playlist set no UA, not tried) | `UserAgentFallback` → `ReResolve(0, userAgentOverride = preset)`; on first frame: notice `UserAgentSwitched(preset)`. **Not persisted** (M5 offers "use this UA for the playlist") | — |
| L6 | `Http(403)` | otherwise (after L5, or L5 not available) | Fail(FORBIDDEN, actions RETRY, CHANGE_USER_AGENT, DIAGNOSTICS) | — |
| L7 | `Http(403/458/509)` | after first frame and > 5 s since tune (mid-playback: token expiry), `forbiddenSteps == 0` | `ReResolve(0)` | limiter refused → L4/L6 rules |
| L8 | `Http(503)`, mid-playback | — | treated as 5xx (L15) | — |
| L9 | `Http(405/406)` | `isXtream`, `formatFlips == 0` | `FlipXtreamOutput(TS ↔ HLS)` → `ReResolve(0, output = flipped)`; on first frame: notice `SwitchedFormat`, `saveLearnedOutput` (AUTO only) | product §2.4: "Retry silently with TS↔HLS first" |
| L10 | `Http(405/406)` | otherwise | Fail(FORMAT_REFUSED) | — |
| L11 | `Http(404/410)`, origin PROGRESSIVE or HLS_MANIFEST | `backupAvailable && settings.failoverToBackup` (v1.1) | `TryBackupSource` | v1: never available |
| L12 | `Http(404/410)`, origin HLS_SEGMENT | `transientRetries == 0` (the loader already retried twice) | `ReResolve(0)` (playlist moved on: fresh session at live) | then Fail(CHANNEL_OFFLINE) |
| L13 | `Http(404/410)` | archive session | Fail(CATCHUP_NOT_IN_ARCHIVE) | M4's probe moves to the next variant |
| L14 | `Http(404/410)` | otherwise | Fail(CHANNEL_OFFLINE) (actions: TRY_BACKUP only when available, PREVIOUS_CHANNEL when one exists, RETRY) | — |
| L15 | `Http(5xx, 408, 429)` | `transientRetries < attemptsSetting` | `ReResolve(CONTROLLER_RETRY_DELAYS_MS[n] ± 20 %, rotateBase = beforeFirstByte && baseRotations < baseCount − 1)`; notice `Reconnecting(n+1, m)` once first frame had been rendered | exhausted → Fail(SERVER_ERROR) |
| L16 | `Network(DNS)` | `baseRotations < baseCount − 1` | `ReResolve(0, rotateBase = true)` | then one `ReResolve(1,000)`; then Fail(DNS_FAILURE) (action USE_SECURE_DNS) |
| L17 | `Network(CONNECT/TIMEOUT/RESET)` before first byte | as L15 with rotation | as L15 | exhausted → Fail(CONNECT_FAILED) (TIMEOUT: NO_DATA_TIMEOUT) |
| L18 | `Network(TLS)` | — | Fail(TLS_FAILURE) (action PLAYLIST_SETTINGS: "Ignore TLS errors") | no retry: certificates do not heal |
| L19 | `ReconnectExhausted` (not a limit status) or `Network(*)` after first byte, or `Stall(NO_BYTES / HLS_NO_PROGRESS)` | `transientRetries < attemptsSetting` (`-1` = unlimited) | `ReResolve(CONTROLLER_RETRY_DELAYS_MS[n] ± 20 %)`, notice `Reconnecting(n+1, m)`, state `Buffering(RECONNECTING, progress)` | unlimited: every `UNLIMITED_RETRY_INTERVAL_MS` (5,000) forever, with the error card shown after `streamSwitchTimeoutMs` but retries continuing |
| L20 | same | exhausted | Fail(STREAM_INTERRUPTED) (or NO_DATA_TIMEOUT when no byte ever arrived) | — |
| L21 | `HtmlInsteadOfStream` | Xtream or account known | `refreshAccount`; status EXPIRED → Fail(ACCOUNT_EXPIRED); otherwise Fail(WEB_PAGE_INSTEAD) | never retried in a loop (product §2.4) |
| L22a | `TinyBody(0)` (200 then immediate close) | within `ZAP_LIMIT_WINDOW_MS` of the tune or before first media (reviewed 2026-10-13, S7) | handled as `Http(509)`: L2–L4 | the panel's "close" form of a lingering session (fake panel `linger_status: "close"`) |
| L22 | `TinyBody` | `transientRetries == 0` | `ReResolve(1,000)` | then Fail(EMPTY_RESPONSE) |
| L23 | `FormatIsHls` | `sourceTypeSwitches == 0` | `SwitchSourceType(HLS)` (same resolved URL, no failure counted, no notice) | a 2nd → Fail(UNSUPPORTED_SOURCE) |
| L24 | `FormatUnrecognized` | `sourceTypeSwitches == 0` | `SwitchSourceType(PROGRESSIVE_OTHER)` | then Fail(UNSUPPORTED_SOURCE) |
| L25 | `PcrDiscontinuity` | `pcrResets < 3` within 60 s | `Reprepare()` (same resolved URL, fresh session at live) | then as L27 escalation |
| L26 | `BehindLiveWindow` | `behindLiveWindow < 3` within 60 s | `SeekToLiveAndPrepare` (`seekToDefaultPosition(); prepare()`, Media3's recipe) | then `ReResolve(0)` once, then Fail(STREAM_INTERRUPTED) |
| L27 | `Parsing(*)`, `Stall(DECODER_STUCK/POSITION_STUCK/FROZEN_VIDEO)`, `Stuck(*)`, `LiveEnded` | tunneling active and the signal is video-related | `DisableTunnelingAndReprepare` | — |
| L27a | same | first in 5 min for the channel | `Reprepare()` | — |
| L27b | same | second in 5 min, `tsCompatLevel == 0`, TS | `Reprepare(raiseTsCompat = true)` (amended 2026-10-14: applied to the cycle at once; persisted (§2.10) only after `COMPAT_PERSIST_HEALTHY_MS` (30 s) of healthy playback at the raised level — same session, Playing, no failure or stall verdict, no rebuffer — and only when the signal was decoder-type: `Stall(DECODER_STUCK)` (b1 and the readiness stall), `Stall(FROZEN_VIDEO)` or `Parsing(container)`; never for `POSITION_STUCK`, PCR exhaustion, NO_BYTES or network) | a stopped session or any later failure drops the pending save |
| L27c | same | otherwise | Fail(STREAM_UNDECODABLE) | `LiveEnded` on HLS with ENDLIST: Fail(STREAM_INTERRUPTED) |
| L28 | `DecoderInit(video)`, `FormatUnsupported(video)`, `DecodingFailed(video)` | tunneling active | `DisableTunnelingAndReprepare` | — |
| L29 | same | `videoDecoderMode != SOFTWARE_PREFERRED`, a software decoder exists for the MIME, `decoderSteps == 0` | `SoftwareVideoAndReprepare` (session-only), warning SOFTWARE_VIDEO, notice | — |
| L30 | same | otherwise | Fail(CODEC_UNSUPPORTED, codec = "MIME + profile", decoder name) | `DecodingFailed` first gets one plain `Reprepare` before L29 |
| L31 | `DecoderInit(audio)`, `FormatUnsupported(audio)`, `DecodingFailed(audio)`, `Stall(AUDIO_NOT_STARTED/AUDIO_UNDERRUN_STORM)` | passthrough active for the MIME | `DisablePassthroughAndReprepare(mime)`, notice `PassthroughDisabled` | — |
| L32 | same | `ffmpegSupportsMime(mime)` and the MIME is not yet software | `SoftwareAudioAndReprepare(mime)`, notice `AudioDecoderSwitched(true, mime)` | — |
| L33 | same | otherwise | `DropAudioAndReprepare(AUDIO_UNSUPPORTED or NO_AUDIO)` (`setTrackTypeDisabled(AUDIO, true)` for this tune), `Playing(warning)` | product §2.4: card only when the fallback also fails; video keeps playing |
| L34 | `AudioTrackInit` | encoded (passthrough) | `DisablePassthroughAndReprepare(mime)` | — |
| L35 | `AudioTrackInit` | PCM with `channelCount > 2` and not yet stereo | `StereoAndReprepare` | — |
| L36 | `AudioTrackInit` | otherwise | `DropAudioAndReprepare(NO_AUDIO)`; a second in the cycle → Fail(AUDIO_OUTPUT_FAILED) | — |
| L37 | `AudioWrite` | `audioSteps == 0` | `Reprepare()` | then L36 |
| L38 | `ResourcesReclaimed` | — | `Reprepare()` | twice in 60 s → Fail(STREAM_UNDECODABLE) |
| L39 | `Credentials(permanent = false)` | first | `RetrySame(1,000)` | then Fail(CREDENTIALS_UNAVAILABLE) |
| L40 | `Credentials(permanent = true)` | — | Fail(NEEDS_REAUTH) | — |
| L41 | `LocalConnectionLimit(active, max)` | — | Fail(CONNECTION_LIMIT_LOCAL, `active/max`) | v2: "a recording is using the connection" |
| L42 | `ChannelMissing` | — | Fail(CHANNEL_NOT_FOUND) | — |
| L43 | `Network(CLEARTEXT)` | — | Fail(INTERNAL) | configuration bug |
| L44 | `Unknown` | `unknownRetries == 0` | `Reprepare()` | then Fail(INTERNAL, media3ErrorCode) |

**Global rules (applied before the table):**
- **Account gate after a refresh** (reviewed 2026-10-13, S7): whenever a decision refreshed the account (L1, L3, L7, L21), the next failure in the cycle first checks the fresh card: `auth == false` → Fail(AUTH_REJECTED); status EXPIRED → Fail(ACCOUNT_EXPIRED); BANNED/DISABLED → Fail(ACCOUNT_DISABLED). Without it a panel that answers an expired account with 403 on the stream (the fake panel does, `fakepanel.py:618–619`) walks L2 → L3 → L6 and shows FORBIDDEN after three or more requests instead of ACCOUNT_EXPIRED. When the cached card already says EXPIRED/BANNED/DISABLED at tune time, a 401/403 goes straight to that Fail.
- **Stream switch timeout:** if `msSinceTuneStart ≥ settings.streamSwitchTimeoutMs` (15 s) and no first frame yet, the next decision is replaced by Fail(the code that decision would eventually produce, or NO_DATA_TIMEOUT for transient signals). A pending ladder delay is cut at the timeout. Unlimited reconnects (L19) are the exception (retries continue behind the card).
- **Healthy reset:** 30 s (`LADDER_HEALTHY_RESET_MS`) of `Playing` without an error resets `LadderCounters` (not the limiter, not the 5-min compat window).
- **Retry from the card:** `retry()` resets the counters and the limiter entry for the channel.
- **Executing a decision:** every decision except `Fail` and `DropAudio…` ends the current session (`transport.endSession`, lease released) and starts a new one through steps 3–7 of §3.1.1 (`RetrySame` and `Reprepare` skip resolution and reuse the `ResolvedStream`; `ReResolve` resolves again). The state while waiting is `Stalled(kind, step, attempt, max)` for detector-driven and decoder/audio decisions, `Connecting(attempt+1)` before first frame, and `Buffering(RECONNECTING)` for L19.

### 4.4 Product mapping (product §2.4 → codes)

| Product condition | Signal → rows | Code | Next steps |
|---|---|---|---|
| Auth failure (401, `auth:0`) | Http 401 → L1 | AUTH_REJECTED | Account details · Edit login |
| Expired (`status:Expired`) | L1 / L21 | ACCOUNT_EXPIRED | Account details |
| Connection limit (403/458/509 after a zap, or `active ≥ max`) | L2–L4 | CONNECTION_LIMIT (`active/max`) | Retry · Stop and wait |
| Offline channel (404) | L11–L14 | CHANNEL_OFFLINE | Try backup source (v1.1) · Previous channel · Retry |
| Format refused (405/406), both failed | L9–L10 | FORMAT_REFUSED | Playlist settings · Diagnostics |
| HTML or tiny body instead of a stream | L21/L22 | WEB_PAGE_INSTEAD / EMPTY_RESPONSE | Account details · Retry |
| Timeout / no data in 15 s | timeout rule, L17, L20 | NO_DATA_TIMEOUT | Retry · Diagnostics |
| Codec unsupported | L28–L30 | CODEC_UNSUPPORTED (codec + decoder) | Try software decoder · Open in external player |
| No audio after 3 s of video | d1 → L31–L33 | warning NO_AUDIO, card only if fallback fails (AUDIO_OUTPUT_FAILED) | Audio options |
| Catch-up 404 | L13 | CATCHUP_NOT_IN_ARCHIVE | Watch live · Try other catch-up mode |
| DNS failure | L16 | DNS_FAILURE | Use secure DNS · Diagnostics |

New codes beyond the product table (each with a reason and a next step): NEEDS_REAUTH, CREDENTIALS_UNAVAILABLE, CONNECTION_LIMIT_LOCAL, FORBIDDEN (UA hint), STREAM_INTERRUPTED, SERVER_ERROR, CONNECT_FAILED, TLS_FAILURE, STREAM_UNDECODABLE, UNSUPPORTED_SOURCE, CHANNEL_NOT_FOUND, INTERNAL, ACCOUNT_DISABLED. M4 writes the copy for all of them (`playback_error_<key>_title/_detail`), with `ErrorDetail` filling placeholders (status, host, codec, `active/max`).

### 4.5 Rate limits and counters

| Limit | Value | Scope |
|---|---|---|
| Re-resolves | `RE_RESOLVE_MAX_PER_WINDOW` = 2 per `RE_RESOLVE_WINDOW_MS` = 60,000 | per `ChannelIdentity` (direct URLs: per broker key), sliding window, survives re-tunes of the same channel |
| Controller retry delays | `CONTROLLER_RETRY_DELAYS_MS` = 1,000, 2,000, 4,000, 4,000, 4,000, … ± 20 % | per tune cycle; count = `settings.reconnectAttempts` (5 ≈ 15 s, product §4.4) |
| Account refreshes | 1 per 10 s per playlist (`ACCOUNT_REFRESH_MIN_INTERVAL_MS`) | provider implementation |
| Format flips | 1 per tune cycle | — |
| PCR / behind-live re-prepares | 3 per 60 s | per channel |
| Compat escalation window | 5 min (`COMPAT_ESCALATE_WINDOW_MS`) | per channel |
| In-source reconnects | 3 within 3 s, budget reset after 30 s healthy | per session |

---
## 5. Tests

### 5.1 Where tests run (decision)

| Suite | Runner | Runs | Why |
|---|---|---|---|
| `:core:test` | JVM, JUnit 5 | every `app.thebox.core.playback.*` component: builder/expander/headers/hints, broker core (with real threads), debouncer, ladder table, limiter, load-retry rules, buffer FSM, detectors, sniffer, aligner, PCR tracker, mode picker, rect maths, event log | Sub-second feedback, fake `MonoClock`, no Android. Already in the smoke build |
| `:player:testDebugUnitTest` | JVM, JUnit 4; plain for `resolve`/`broker`, **Robolectric `sdk=35`** for everything touching Media3/Android | resolver against fake providers; `LiveTsDataSource`, HTTP factory, brokered call factory, load policy against `mockwebserver3`; `PlayerFactory`/`MediaSourceBuilder`/`AdaptiveLoadControl`/codec selector/audio gate/watchdog/stats/display mode; controller state machine with fakes and with a real ExoPlayer | Media3's `DataSpec` and friends need `android.net.Uri`; Robolectric covers it. Robolectric's SDK 35 jar needs JDK 21 (as in M1) |
| `:app:connectedDebugAndroidTest` | `TheBox_TV_API30` (Android TV, x86) + the fake panel on the Mac | §5.5 | Real decoders, real surfaces, real sockets, real lifecycle. Run locally (no emulator in CI), like M1 |

Smoke build becomes `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug` (CI `android.yml` too).

### 5.2 Fixtures

Generated once by the lead with `ffmpeg` (commands recorded in `core/src/test/resources/ts/README.md`), checked in (each < 400 KB):

| File | Content | Used by |
|---|---|---|
| `ts/h264_aac_2s.ts` | 2 s, 320×180, 25 fps, H.264 Baseline, IDR every 25 frames, AAC-LC stereo, PCR on the video PID | sniffer, aligner, PCR tracker, LiveTs continuity, Robolectric ExoPlayer |
| `ts/h264_mp2_2s.ts` | as above with MP2 (`-c:a mp2`) | MIME mapping, selector gating |
| `ts/pcr_jump.bin` | `h264_aac_2s.ts` followed by the same file with PCR/PTS shifted +10 s (written by a small Kotlin test helper, not ffmpeg) | PCR tracker jump |
| `ts/garbage_prefix.ts` | 97 random bytes + `h264_aac_2s.ts` | sniffer offset, aligner resync |
| `http/splash.html`, `http/hls_live.m3u8`, `http/tiny.bin` (200 bytes), `http/mp4_head.bin` (`ftyp` box) | sniffer verdicts | sniffer, LiveTs |

Command: `ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -f lavfi -i sine=frequency=1000 -t 2 -c:v libx264 -profile:v baseline -g 25 -pix_fmt yuv420p -c:a aac -b:a 64k -f mpegts -mpegts_flags +resend_headers h264_aac_2s.ts` (the lead records the exact `ffmpeg -version` used).

### 5.3 Test matrix per WP

**WP-A (`:core`, JUnit 5; `:player` resolve/broker, JUnit 4)**

| Area | Must cover |
|---|---|
| `StreamUrlBuilder` | Xtream API: `{{base}}`/`{{ext}}`/`{{u}}`/`{{p}}`, base index and rotation, output table §3.2.3 (all settings × allowed formats × learned), forced HLS; Xtream-shaped M3U: extension flip, short form, literal base kept; plain URL untouched; `{{u}}` without credentials → `Failed(NEEDS_REAUTH)`; `xtream` ref filled for both Xtream kinds; `liveUrlTemplate` keeps `{{u}}`/`{{p}}`; `toString()` never contains the password (property test with random passwords) |
| `CredentialExpander` | path vs query encoding (`/`, `?`, `#`, `%`, space, `@`, `+`, `&`, non-ASCII); a password containing `{utc}`, `{{p}}` and `}}` stays literal; header values raw; single pass |
| `HeaderPolicy` | precedence §3.2.5 (6 levels), case-insensitive keys, dropped headers, UA override never beats a channel UA |
| `FormatHints` | the table of §3.2.6 incl. query/fragment noise and upper case |
| `ConnectionBrokerCore` | grant under max; pre-empt of another owner's playback lease cancels every handle exactly once; CLOSING counts; wait ≤ 300 ms then grant after release (real threads, latch); grant-after-wait when release lags; RECORDING never pre-empted → `ConnectionLimitException`; PREVIEW refused at max 1; `attach` after pre-empt returns false; `release` idempotent; snapshot counts; stress: 50 threads × 200 acquire/release cycles, no deadlock, count never exceeds max + closing |
| `ZapDebouncer` | single press immediate; repeat → trailing at last + 350; 5 presses in 1 s → 1 immediate + 1 trailing |
| `ErrorLadder` | **one test per row L1–L44** plus the global rules (timeout replacement, healthy reset, limiter refusal fallbacks, unlimited reconnects); golden table test: a CSV of (signal, context) → decision checked into `core/src/test/resources/playback/ladder.csv` |
| `ReResolveLimiter` | sliding window, per key |
| `LoadRetryRules` | §4.2 table, jitter bounds with `random` = 0 and 1 |
| `BufferProfileFsm` | L0→L1 on first rebuffer; 2 in 10 min → +1; 1 per window → no change; decay one level per 10 min; manual modes fixed; low-RAM bytes |
| `StallDetectors` | per detector: fires at threshold, not 1 tick before; each guard (paused, suppressed, seek grace, hidden/poster, before first frame, tunneling, in-reconnect, cool-down); escalation counters; zero allocation per `onSample` (allocation counter via `ThreadMXBean.getThreadAllocatedBytes` in a JUnit test, budget 0 bytes after warm-up) |
| `DisplayModePicker` | 25→50, 50→50, 29.97→59.94, 23.976 with and without a 24p mode, current-mode no-op, no matching mode → null, resolution never changes; `snapFps` tolerance |
| `VideoRectCalculator` | FIT letterbox and pillarbox, anamorphic `pixelRatio`, 16:9/4:3 forced, FILL/ZOOM crop flag, odd pixel rounding inside the hole |
| `PlaybackEventLog` | ring wrap at 200/50, redaction of `/live/u/p/`, `?token=`, registered secrets; 160-char cut |
| `StreamUrlResolverImpl` (`:player`, JUnit 4) | provider failures → outcomes (§3.2.1); `needsReauth`; `catchupTarget` and `expandArchive` order (catch-up expansion before credentials; a source containing `{{u}}`) |

**WP-B (`:core` JUnit 5; `:player` Robolectric + `mockwebserver3`)**

| Area | Must cover |
|---|---|
| `StreamSniffer` | TS at offset 0 and 97; HLS with/without BOM and leading whitespace; HTML variants (`<!DOCTYPE html>`, `<html`, `<?xml …><html`); tiny body; MP4 → UNRECOGNIZED; NEED_MORE below 565 bytes |
| `TsPacketAligner` | arbitrary chunk sizes (1, 187, 188, 189, 4096) reproduce the input packets exactly; `resync` drops the partial packet and re-locks on a 3-sync run; never emits a partial packet when `len ≥ 188`; (reviewed 2026-10-13, B1/S1) after `resync`, for every PID and for each of the 16 possible restart CCs, the first emitted payload packet has CC = last + 2 and later packets (adaptation-only included) keep the same offset; held packets are not readable until `releaseHeld`, and `dropHeld` discards them; plus one `:player` Robolectric test (WP-B, `media3-test-utils` is an Android library, so not in `:core`) that feeds a spliced fixture through a real `TsExtractor` with `FakeExtractorInput`/`FakeExtractorOutput` and checks that no sample spans the splice |
| `PcrTracker` | PCR PID choice; continuous after a 1 s gap → no jump; +10 s and −3,600 s → jump with the right sign; 33-bit wrap near 2³³ → no false jump; no PCR → disabled |
| `LiveTsDataSource` | **no `Range` header on any request** (assert every `RecordedRequest`), even when Media3 calls `open` with `position > 0` (`positionIgnoredCount` = 1); `open` returns `LENGTH_UNSET` with a `Content-Length` response; server closes after N bytes → one reconnect, output bytes = packet-aligned concatenation, exactly 2 requests to the **original** URL; 302 → `/tok?token=a` then on reconnect 302 → `token=b` (never a request to `token=a` again); 302 then 403 on the reconnect (expired token, three times) → `LiveStreamException(RECONNECT_EXHAUSTED, lastStatus = 403)`; 403 on the first open → `HTTP_STATUS 403` without retry; HTML/tiny/HLS/MP4 bodies → the right kinds, no body retained; silent server (headers then nothing) → `NO_BYTES` reconnect after 4 s (fake watchdog clock); reconnect budget (3 in 3 s) and reset after 30 s; `close()` cancels the call and releases the lease after the socket closed (MockWebServer sees the disconnect before `release` returns); pre-emption during a blocked read unblocks it at once (< 200 ms) and ends without reconnecting; exception messages and `dataSpec.uri` contain no URL; the tee sink receives the same aligned bytes as the reader; (reviewed 2026-10-13) `getUri()` is the session URI and `getResponseHeaders()` is empty; a PCR jump on reconnect throws only after dropping the held packets and no post-splice byte was readable before it; an `UnknownHostException` at open yields `NETWORK` with kind DNS (root-cause walk); `close()` on a chunked body returns in < 20 ms (cancel before close); the lease is released by `callEnd`/`callFailed` |
| `BrokeredCallFactory` | every call attached to the lease; request tag; status/redirect count/final host recorded; a call created after pre-emption is cancelled immediately |
| `StreamHttpDataSourceFactory` | headers sent once (one User-Agent); Range passes through for archive; one lease for the whole HLS session |
| `BoxLoadErrorHandlingPolicy` | §4.2 rows with real `LoadErrorInfo` objects |

**WP-C (`:player` Robolectric)**

| Area | Must cover |
|---|---|
| `PlayerFactory` | builder settings (§6.3 table): load control, renderers factory, wake mode, audio attributes with focus, noisy, stuck-buffering 30 s, frame-rate strategy per AFR mode, track-selector tunneling default off |
| `BoxMediaCodecSelector` | HW-first ordering, HW-only filter, SW-preferred, per-MIME software-audio gate returns empty only when FFmpeg supports the MIME (fake `FfmpegAvailability`). (reviewed 2026-10-13, S14: `ShadowMediaCodecConfig` registers codecs without hardware/software flags (`ShadowMediaCodecConfig.java:254–259`), so these tests register their own with `ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder…setIsHardwareAccelerated/setIsSoftwareOnly)`, present in Robolectric 4.17) |
| `PassthroughGateAudioSink` | encoded format denied per MIME and `"*"`; PCM always delegated; `getFormatSupport` consistent with `supportsFormat` |
| `StereoDownmixProcessor` | inactive by default; 6→2 matrix when enabled at configure; passes stereo through |
| `AdaptiveLoadControl` | continue/start decisions per profile; profile switch takes effect on the next call; byte cap wins; HLS live clamp; (reviewed 2026-10-13, S4) a real `ExoPlayer` runs prepare/stop/release without hitting a deprecated overload (`IllegalStateException`); back buffer is 0 |
| `TsExtractorsFactory` dead-track guard (reviewed 2026-10-13, S5) | a fixture whose PMT lists an extra AC-3 PID with no packets prepares within 1 MiB with video + AAC; a 0x82 PID is ignored; `DEAD_TRACK` event logged |
| `MediaSourceBuilder` | TS → progressive with TS-only extractors and the session URI; HLS → `HlsMediaSource` with chunkless preparation; compat level → MULTI_PMT |
| `TrackController` | `Tracks` → `TrackInfo` (codec names, AD role, supported flag); overrides; text off |
| `PlaybackWatchdog` | sample filling from a `FakePlayer`/real player; start/stop with `playWhenReady` |
| `DisplayModeController` | SEAMLESS/OFF strategies; ALWAYS on API 31 (`@Config(sdk = 31)`, shadow `Surface.setFrameRate` call captured) vs API 28 (`preferredDisplayModeId` request flow, `onDisplayChanged` and 3 s timeout); dwell and viewport gating; revert after 30 s in MINI |
| Real ExoPlayer (`media3-test-utils-robolectric`) | §5.4 |

**WP-D (`:player` Robolectric; `:app` instrumented)**

| Area | Must cover |
|---|---|
| Controller state machine | every row of §3.1.2 with fake resolver/transport/player bundle and a fake clock (`TestScope`), incl. stale `tuneId` errors ignored, zap debounce and `pendingZap`, previous channel, live pause → release after 10 s → resume re-tunes, stream switch timeout |
| Ladder execution | each `LadderDecision` performs the right side effects (session end before re-open, flip persisted only on first frame, base index saved only on first frame, notices emitted once) |
| `FailureClassifier` | each row of §4.1 with real Media3 exception objects |
| `LifecycleCoordinator` | `ON_STOP` while interactive → immediate stop; (reviewed 2026-10-13, B4) `ON_STOP` with `isInteractive() == false` → grace, not stop; screen-off/HDMI/display-off grace (fake clock) and cancellation; resume re-resolves (resolver called again) and refreshes the account after 15 min |
| First-media milestone (reviewed 2026-10-13, B2) | audio-only TS (radio): `ZapRecord` completes on `onAudioPositionAdvancing`, no switch timeout, `recordWatched` after 5 s; HDR poster and HIDDEN tunes likewise |
| `ZapImportGate` | closed at tune, opens at first frame + 500 ms or 3 s, `awaitClear` bounded at 3 s |
| `SurfacePlayer` / `SessionPlayer` | command sets; `setVideoSurfaceView` captured and forwarded; session play/pause/next map to controller calls; (reviewed 2026-10-13, B3/B5) a detached view is cleared from the player; after `ON_STOP` a session PLAY/NEXT does not tune and the commands are not advertised |
| Manifest (reviewed 2026-10-13, B5) | the merged debug and release manifests contain no `androidx.media3.session.MediaSessionService` filter and no `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| Providers in `:app` | `DataPlaybackSourceProvider`/`RoomChannelMemory` on Robolectric + Room (as M1's `:data` tests): mapping of every column, `playback_flags` bits, serialised updates |

### 5.4 Real ExoPlayer under Robolectric (feasibility and plan)

Verified in the 1.11.1 artifacts (Appendix A): `media3-test-utils` ships `TestExoPlayerBuilder` (with `setClock`, `setLoadControl`, `setRenderersFactory`, `setMediaSourceFactory`, `setStuckPlayingDetectionTimeoutMs`), `FakeClock`, `FakeDataSource`, `FakeMediaSource`; `media3-test-utils-robolectric` ships `TestPlayerRunHelper` (`runUntilPlaybackState`, `runUntilError`, `runUntilRenderedFirstFrame`, `play`, `advance`), `ShadowMediaCodecConfig` (`withAllDefaultSupportedCodecs()` covers H.264, H.265, MPEG-2, VP9 and AV1 video and AAC (`audio/mp4a-latm`), MP3, MP2 (`audio/mpeg-L2`), AC-3, E-AC-3, E-AC-3-JOC, AC-4, Opus, Vorbis, FLAC, ALAC and raw audio — reviewed 2026-10-13: not AMR-NB, `ShadowMediaCodecConfig.java:164–182`) and `CapturingRenderersFactory`.

(reviewed 2026-10-13, S14) Constraints the WP-C/WP-D real-player tests must respect, verified in the 1.11.1 test sources:
- The shadow **audio** decoders drop every sample and the video ones pass input through (`ShadowMediaCodecConfig.java:186–190, 330–340`): `onAudioPositionAdvancing` never fires, so these tests set `audioExpected = false` (d1/d2 off) and assert nothing about audio output.
- A first frame needs a `Surface`: `player.setVideoSurface(Surface(SurfaceTexture(1)))`, as Media3's own tests do.
- `PlayerFactory` needs an `internal` test seam to inject a `Clock` and `IdlingMediaCodecAdapterFactory` (without it the async codec threads run freely and the tests flake, `IdlingMediaCodecAdapterFactory.java:37–45`).
- Network-bound tests (R-1 to R-4) use `Clock.DEFAULT` (an auto-advancing `FakeClock`, the `TestExoPlayerBuilder` default, jumps ahead while loader threads wait on sockets); the run helpers already time out on wall time (`RobolectricUtil.DEFAULT_TIMEOUT_MS` = 10 s).
- `TestPlayerRunHelper` requires the main looper as the application looper (`TestPlayerRunHelper.java:1158–1162`); the design uses it.

Dependencies of concern (POMs): `media3-test-utils` pulls `mockito-core 3.12.4`, `truth`, `junit 4.13.2`, `kotlinx-coroutines-test 1.9.0`, `media3-transformer`, `media3-effect`, `media3-inspector` and **`com.squareup.okhttp3:mockwebserver 4.12.0`**; `media3-test-utils-robolectric` pulls `robolectric 4.16`. Gradle resolves coroutines to 1.11.0 and Robolectric to 4.17 (our pins) and `okhttp` to 5.5.0; the OkHttp 4 `mockwebserver` jar would then run against OkHttp 5 classes. Recommendation: add both as `testImplementation` in `:player` with `exclude(group = "com.squareup.okhttp3", module = "mockwebserver")` (we use `mockwebserver3`), and run day-0 check 3 (§6.8). If the day-0 check fails, these tests move to the instrumented suite and the Robolectric suite keeps fakes only.

Planned real-player tests (`player/src/test/.../engine/RealPlayerTest.kt`):
- R-1: `PlayerFactory` player + `MediaSourceBuilder` TS source over `LiveTsDataSource` serving `h264_aac_2s.ts` looped by a `mockwebserver3` dispatcher → `runUntilRenderedFirstFrame` (shadow codecs), `STATE_READY`, decoder names reported.
- R-2: server closes mid-stream → no `onPlayerError`, `reconnects == 1`, playback position keeps advancing (real clock: network threads do not follow `FakeClock`; network-bound tests use the system clock with bounded waits).
- R-3: 405 on the progressive open → `onPlayerError` with a `LiveStreamException(HTTP_STATUS 405)` cause and `ERROR_CODE_IO_BAD_HTTP_STATUS`, not retried (exactly one request).
- R-4: `FormatIsHls` (a `.ts` URL serving `hls_live.m3u8`) → classified, rebuilt as HLS by the controller (with the real controller and fakes for providers). (reviewed 2026-10-13: R-4 needs WP-D's controller, which merges last, so it lives in `player/src/test/.../controller/` and is owned by WP-D, not in WP-C's `RealPlayerTest`.)
- R-5: `AdaptiveLoadControl` profile change mid-playback changes `shouldStartPlayback` behaviour after a forced rebuffer (`FakeMediaSource` + `FakeClock`).

Shadow codecs pass bytes through and report frames; they do not prove decoding. Decoder behaviour (MP2 via FFmpeg, decoder fallback) is instrumented-only.

### 5.5 Instrumented suite vs the fake panel (API 30 TV emulator)

**How the emulator reaches the panel.** The panel runs on the Mac. The emulator's `10.0.2.2` is the host's loopback, so `http://10.0.2.2:<port>` reaches a panel bound to `127.0.0.1` or `0.0.0.0`; the redirect port (`port + 1`) is reachable the same way, and the panel builds redirect URLs from the request's `Host` header. Cleartext is allowed app-wide.

**How tests start and configure it.** `tools/fakepanel/run-instrumented.sh` (WP-D) starts `python3 tools/fakepanel/fakepanel.py --port 18080` in the background (ffmpeg media generated on first run), waits for `GET /admin/config`, runs `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.fakepanel=http://10.0.2.2:18080`, then kills the panel. Tests read the `fakepanel` argument and `assumeTrue` it is present (so a plain `connectedDebugAndroidTest` skips them). `FakePanelAdmin` (OkHttp, in `androidTest`) wraps `POST /admin/reset`, `POST /admin/config`, `POST /admin/kick`, `GET /admin/stats`. Every test resets the panel in `@Before`, and every test's `@After` asserts `stats.range_requests_seen == 0`.

(reviewed 2026-10-13, S13) **Config shape.** The panel rejects unknown top-level keys with HTTP 400 (`fakepanel.py:236–239`). In the table below, `max_connections`, `status`, `allowed_output_formats`, `token_ttl`, `hls_timeout` and `active_cons_override` are top-level; `close_every`, `reconnect_mode`, `linger`, `linger_status`, `stall`, `stall_after`, `slow`, `ua_whitelist`, `html_splash`, `405_on_m3u8` and `range_rejects` are **fault** keys and go under `faults` (all streams) or `stream_faults: {"<id>": {…}}`. `FakePanelAdmin` exposes typed builders so tests cannot get the nesting wrong.

**Setup.** Each test class adds an Xtream playlist `it-<uuid>` for the panel (`PlaylistRepository.addXtream("http://10.0.2.2:18080", "test", "test")`) and refreshes it synchronously through `RefreshRepository` (as M1's I-4), then launches `PlaybackDebugActivity` with `ActivityScenario` and gets the controller from a Hilt `@EntryPoint`. `@After` deletes the playlist. Lifecycle timers use `settings.lifecycleTimeScale = 0.1`.

| # | Spike / topic | Panel config | Steps | Assertions (measured values are logged, not asserted) |
|---|---|---|---|---|
| I-1 | smoke | default | tune ch 1 | `Playing` and first frame within 10 s; `ZapRecord` complete; stats show decoder names, `host = 10.0.2.2:18080`, `httpStatus = 200` |
| I-2 | **D3** zap correctness | `max_connections: 1` | 40 zaps across ch 1, 2, 4 (50 fps), 3 (MP2), 2 s apart | every zap reaches first frame; panel `connections.active ≤ 1` at every 250 ms sample; ~~`rejections` empty~~ `rejections` logged, not asserted (reviewed 2026-10-13, S13: the v0 panel only notices a client close on its next write, so a leading-edge zap can race it and get a 403 that the ladder then absorbs; with the v1 EOF check, `linger: 0` should give none); P50/P90 of tune→first frame logged |
| I-3 | **D4a** transparent reconnect | `close_every: 15`, `reconnect_mode: continue` | play ch 1 for 70 s | no `Error`; telemetry `reconnects ≥ 4`; no `Reconnecting` notice; per reconnect, time in `Buffering` ≤ 1 s (logged); A/V position still advancing at the end |
| I-4 | **D4b** PTS reset | `close_every: 15`, `reconnect_mode: reset` (fake panel v1, real PTS reset) | play 40 s | event log has `PCR_JUMP` then a `Reprepare` decision; back to `Playing` within 5 s **of the rebuffer that follows the jump** (reviewed 2026-10-13, S1: the error surfaces when the buffer drains); no `Error` |
| I-5 | **D4c** PTS jump | `reconnect_mode: jump` (v1: +5 s PTS offset) | play 40 s | as I-4 |
| I-6 | **D5** linger | `max_connections: 1`, `linger: 2`, `linger_status` ∈ {403, 458, 509, "close"} (parameterised) | 10 zaps 300 ms apart with `isRepeat = true`, then 10 single zaps 1 s apart | debounced zaps cause ≤ 2 stream requests per burst (panel stats); every final channel reaches `Playing` (ladder L2/L3); success rate logged |
| I-7 | D5 long linger | `linger: 30`, `linger_status: 458` | zap twice | second zap ends in `Error(CONNECTION_LIMIT)` with `maxConnections = 1` in the detail within ≤ 4 s; `actions` contain RETRY |
| I-8 | 302 token expiry | `stream_faults: {"1": {"redirect": true, "token_ttl": 5}}`, `close_every: 8` | play 40 s | every reconnect requests the original `/live/…` URL first (panel log), no request with an expired token is rejected (`rejections.token_expired` absent), playback continues |
| I-9 | Range | (all tests) | — | `range_requests_seen == 0` after every test |
| I-10 | 405 flip | `405_on_m3u8: true`; playlist `xtream_output = HLS` | tune ch 1 | `Playing`; notice `SwitchedFormat(TS)`; with setting AUTO and `allowed_output_formats: ["m3u8"]` the first request is already `.m3u8` |
| I-11 | HTML splash | `stream_faults: {"2": {"html_splash": true}}` | tune ch 2 | `Error(WEB_PAGE_INSTEAD)` within 3 s; ≤ 2 stream requests (no loop). (reviewed 2026-10-13: the "combined with `status: Expired`" variant cannot be produced: the panel checks the account status before the splash, `fakepanel.py:618` vs `:672`) |
| I-12 | UA whitelist | `ua_whitelist: ["VLC"]` | tune ch 1 with `autoUserAgentFallback` on, then off | on: `Playing` + notice `UserAgentSwitched`; off: `Error(FORBIDDEN)` with CHANGE_USER_AGENT. M3U playlist variant: ~~ch 5~~ **ch 6** (pipe UA `Mozilla/5.0 (Fake)`) with `ua_whitelist` set to that UA → `Playing` (channel header used). (reviewed 2026-10-13, S13: ch 5's `#EXTVLCOPT` UA is `VLC/3.0.20 LibVLC/3.0.20` (`catalog.py:247`), identical to the fallback preset, so it cannot tell the channel header from the fallback; the test adds the panel's `get.php` M3U as a second playlist in setup) |
| I-13 | MP2 audio | default | tune ch 3 for 10 s | if `FfmpegAvailability.isAvailable` (x86 AAR present, §5.6): `stats.audioDecoder` contains "ffmpeg", audio position advancing; else: `Playing` with warning `AUDIO_UNSUPPORTED` **or** a platform MP2 decoder in `stats.audioDecoder` — never `Error` |
| I-14 | stall (start) | `stream_faults: {"1": {"stall": 6}}` | tune ch 1 | the byte watchdog reconnects after 8 s first-byte timeout or the panel delivers at 6 s; either way `Playing` within 15 s, no `Error` |
| I-15 | stall (mid-stream) | v1 fault `stall_after: [10, 8]` | play 30 s | event `NO_BYTES` at ≈ 14 s, reconnect, `Playing` again; no `Error` |
| I-16 | slow network | `slow: 100000` (B/s, below the stream bitrate) | play 60 s | rebuffers; `BufferIncreased` notice; `stats.bufferProfile.level ≥ 1`; (reviewed 2026-10-13, B6) **no** `DECODER_STUCK` verdict and no `Error` |
| I-17 | auth | `status: Expired` | tune | `Error(ACCOUNT_EXPIRED)`, ≤ 2 stream requests (needs the account gate of §4.3, reviewed 2026-10-13, S7) |
| I-18 | 404 | tune a channel whose stream id the panel does not know (debug direct URL `/live/test/test/999.ts`) | — | `Error(CHANNEL_OFFLINE)` within 2 s |
| I-19 | lifecycle Home | default | play, `UiDevice.pressHome()` | `Stopped(BACKGROUND)` within 2 s (reviewed 2026-10-13: `ON_STOP` arrives 700 ms after the pause); panel `connections.open == 0` within 3 s; relaunch → `Playing` with a new request (re-resolve) |
| I-20 | lifecycle screen off | default | play, `UiDevice.sleep()` | **no** `Stopped(BACKGROUND)` (reviewed 2026-10-13, B4); after the scaled grace (3 s) `Stopped(STANDBY)`; `wakeUp()` → `Playing` again |
| I-20a | radio (reviewed 2026-10-13, B2) | direct URL to an audio-only TS (`ffmpeg … -vn` clip added to the panel as ch 13, or a `DirectUrl` to a fixture served by the panel) | tune, wait 20 s | `Playing`, no `Error`, `ZapRecord.firstFrameMs` set from the audio milestone |
| I-21 | hole punch smoke | default | play ch 1 in the harness | `UiAutomation.takeScreenshot()`: the centre pixel region is not uniform black and changes between two screenshots 1 s apart |
| I-22 | AFR gating (correctness only) | ch 4 (50 fps) | `afrMode = ALWAYS`, viewport MINI then FULL | MINI: no `displayModeRequest`/`setFrameRate`; FULL after 2 s: a request when the AVD offers a 50 Hz mode, else none (the TV AVD usually has one 60 Hz mode: then assert "no switch" and log) |
| I-23 | migration | — | `MigrationTestHelper` 1 → 2 | columns exist with defaults; user data intact. (reviewed 2026-10-13, S10: runs in `:data`'s androidTest, not here) |

**Perf on the emulator:** I-2 logs P50/P90 zap times, I-3 logs reconnect gaps, I-16 logs rebuffer counts. None is asserted (plan §7: correctness only until hardware exists). The same tests, pointed at a panel on the LAN (`fakepanel=http://<mac-ip>:18080`), are the M6 device runs.

### 5.6 Fake panel v1 (WP-D, `tools/fakepanel`)

The v0 panel loops a 10 s clip **without rewriting PCR/PTS or continuity counters** (README "Limitations"), so every 10 s the stream jumps back 10 s. With M2's design that would fire the PCR check (it only runs after reconnects, but detectors b2/c would fire every loop) and make D4 meaningless. Changes (standard library only, with tests in `test_fakepanel.py`):

1. **Continuous timestamps:** when the live loop restarts, add `loop_count × clip_duration` (90 kHz) to every PTS/DTS in PES headers with `PTS_DTS_flags`, and to the PCR base (27 MHz extension untouched) of adaptation fields with a PCR; rewrite continuity counters per PID so they continue. Packets are 188-byte aligned in the generated clips (`-f mpegts`). (reviewed 2026-10-13, S13, exact rules:)
   - **PCR** (adaptation field, `adaptation_field_control` 10/11, length > 0, PCR flag `0x10`): 6 bytes = 33-bit base, 6 reserved bits, 9-bit extension. Add the offset to the base mod 2³³; keep reserved bits and extension.
   - **PTS/DTS**: only in packets with PUSI set whose payload starts `00 00 01` and a stream id of an elementary stream (not PSI: PAT/PMT payloads start with a `pointer_field`), PID ≠ 0x1FFF. Each is a 5-byte field (`'0010'`/`'0011'`/`'0001'` prefix nibble, three marker bits); re-encode mod 2³³ keeping the prefix and markers. The optional PES header always fits in the first packet.
   - **Continuity counters**: advance only on packets that carry payload (`adaptation_field_control` 01/11); adaptation-only packets (10, ffmpeg's PCR-only packets) repeat the previous CC. Rewrite CCs on every PID, PAT and PMT included, so the loop seam is continuous.
   - **Offset**: derive it from the clip's video PTS span (250 frames at 25 fps = 10.000 s), not from the wall clock. The AAC track spans 469 × 1024 samples ≈ 10.0107 s (MP2 ≈ 10.008 s), so each loop overlaps the audio by about 10 ms; trim the last audio PES of the clip at generation time or accept it (well under the 200 ms audio-sink tolerance).
   - **State per stream id**: keep `offset` and `loop_count` next to `live_pos` (`fakepanel.py:757, 787`). `reset` zeroes both, `jump` adds 5 × 90,000 to the offset.
   - Each connection still starts with the 1 s burst (`:764`), so after N reconnects the panel runs N seconds ahead of real time. Expected; I-3 logs it.
2. **`reconnect_mode` semantics:** `continue` keeps the offset and loop position; `reset` restarts the clip **and** the offset at 0 (a true PTS reset); `jump` adds +5 s to the offset (a true PTS jump) instead of skipping bytes.
3. **`stall_after: [after_s, duration_s]`:** serve normally for `after_s`, then hold the socket open without writing for `duration_s`, then continue. Added to `FAULT_DOC` as a fault key.
4. **`/admin/stats` additions:** a bounded `request_log` list (path with credentials masked, User-Agent, token present, status, time) next to the existing `requests` counter (today an int); `connections.max_open_seen`.
5. `fallback_null.ts` behaviour is unchanged (no timestamps to rewrite).
6. (reviewed 2026-10-13, S13) **Prompt client-disconnect detection:** before each write, check the socket for EOF (`select` with timeout 0, then `recv(1, MSG_PEEK)` == b""), and do the same inside `stall`/`stall_after` sleeps, so a slot is freed when the client closes rather than on the next failed write (0.1–0.3 s later today, `fakepanel.py:378–391`). Without it `linger: 0` is not 0 and I-2 races.
7. An audio-only channel (ch 13, `-vn`, AAC) for I-20a.

**FFmpeg on the emulator.** The checked-in local AAR (`ffmpeg/libs/media3-decoder-ffmpeg-1.11.1-armeabi-v7a_arm64-v8a.aar`) has ARM natives only, and `TheBox_TV_API30` is **x86**; the universal debug APK contains `lib/x86` (from `sqlite-bundled`), so the installer picks x86 and `libffmpegJNI.so` is absent → `FfmpegAvailability.isAvailable == false` on the emulator. To exercise I-13's FFmpeg path, build a debug-only AAR with `tools/ffmpeg/build-ffmpeg-aar.sh --abis "x86 armeabi-v7a arm64-v8a"` (the script supports `--abis`); release packaging already strips x86 (`app/build.gradle.kts`). §8 Q3.

### 5.7 What M2 does not test

Real HDMI behaviour (AFR re-sync time, HDR switching, passthrough to a soundbar), frame-time impact on the guide, zap time on low-end hardware, Fire OS-specific lifecycle (CEC), and 12 h soak. These are D1, D6, D7 and M6 items on hardware.

---
### 5.8 Integration results (2026-10-13)

Run on the API 30 TV emulator (`TheBox_TV_API30`, x86, software `OMX.google.*` decoders, local FFmpeg AAR with x86 natives) against `tools/fakepanel/fakepanel.py` on port 18480 via `run-instrumented.sh`, one class per call. Numbers are emulator measurements, **not gate-relevant**. I-9 (`range_requests_seen == 0`) is asserted in every test's `@After` and held in every run.

**JVM suite** (`:core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :player:lintDebug :app:lintDebug`): green. `:core` 1,295 tests, `:data` 124, `:player` 271, 0 skipped (the two WP-D `LiveStreamException` classifier rows are un-skipped). Lint: no errors (warnings only, pre-existing kinds). New: `RealEngineControllerTest` (R-4): the controller over the real `PlayerFactory`, `MediaSourceBuilder`, `DefaultEngineEnvironment`, ladder, limiter, buffer FSM and broker registry; (1) TS fixture to first media, (2) a zap while connecting (old session ended before the new build, zap records `superseded`/`ok`), (3) the real WP-B transport against `mockwebserver3` answering 403: L2 `RetrySame` → L3 `ReResolve(refreshAccount)` → L6 `Fail(FORBIDDEN)`, 3 requests. These use `Clock.DEFAULT` (an auto-advancing `FakeClock` never advanced the loader while the controller's coroutine delays ran on the Robolectric clock).

| Class | Pass | Fail | Ignored | Notes |
|---|---|---|---|---|
| `PanelAdminTest` | 1 | 0 | 0 | |
| `ProvidersDeviceTest` | 4 | 0 | 0 | real Room |
| `SmokeAndZapTest` (I-1, I-2, I-6 ×4, I-7) | 7 | 0 | 0 | |
| `ReconnectTest` (I-3, I-4, I-5, I-8, I-14, I-15, I-16) | 6 | 0 | 1 | I-3 `@Ignore`d, open item O-1 (AGP's XML writes the ignored case as an empty `<failure/>`; the Gradle task passes) |
| `ErrorPathTest` (I-10, I-11, I-12 ×2, I-17, I-18) | 6 | 0 | 0 | |
| `MediaAndLifecycleTest` (I-13, I-19, I-20, I-20a, I-21, I-22) | 6 | 0 | 0 | I-22 runs its no-50 Hz branch (the AVD has one 60 Hz mode) |
| `:data` `I1LibraryDatabaseTest` (I-23) | 3 | 0 | 0 | |

**Measured (emulator)**
- I-1 tune → first media: 530–950 ms over three runs (resolve 200–356, lease 220–408, first byte 313–598 ms). `ready` is null in the zap record: READY follows the first frame here (§6.9 finding 2).
- I-2, 40 zaps at `max_connections = 1`: P50 365–572 ms, P90 778–1,352 ms; panel `maxOpen` 1, no rejections.
- I-6 (linger 2 s, statuses 403/458/509/close): a 10-press repeat burst makes 3 stream requests; 10/10 single zaps reach first media. I-7 (linger 30 s, 458): `CONNECTION_LIMIT` after 3.5 s.
- I-3 (`close_every: 15`, continue): reconnect gaps from the event log 10–30 ms; `Buffering(RECONNECTING)` lasts ~20 ms; 4 reconnects in 70 s when no decoder stall occurs. I-4/I-5: 2 (jump) and 4 (reset) `PCR_JUMP`s, each followed by a `Reprepare`. I-15: 2 `NO_BYTES` events, back to Playing. I-14: Playing after 6.3–6.5 s (6 s stall at start). I-16: 3 rebuffers, profile raised to L2.
- Connections (I-5 family and every class): panel `max_open_seen` 1 in every test.
- I-11 `WEB_PAGE_INSTEAD` 59–174 ms; I-17 `ACCOUNT_EXPIRED` 3.0 s with 2 refused requests; I-18 `CHANNEL_OFFLINE` < 10 ms; I-19 `Stopped(BACKGROUND)` 0.75–1.25 s; I-20 `Stopped(STANDBY)` after 4.1 s (grace scaled to 3 s).

**Day-0 checks 5 and 6**
- Check 5, `MigrationTestHelper` 1 → 2 on the API 30 AVD: **pass** (`I1LibraryDatabaseTest.migrate1To2`, plus `exportedSchemaVersion1Creates` and `bundledDriverFtsAndForeignKeysOnEveryConnection`).
- Check 6, `FfmpegAvailability.isAvailable` on the emulator: **true**. I-13 (MP2) decodes with `ffmpegLavc60.3.100-mp3`.

**Device-only bugs found and fixed**

| # | Where | Symptom | Cause | Fix | Test |
|---|---|---|---|---|---|
| D1 | `PlaybackControllerImpl` | After an in-source reconnect the state stayed `Buffering(RECONNECTING)` for minutes while video played | The state table had no exit: the player never left READY, so no READY callback came | `RECONNECT_OK`, or `inReconnect` false at the next poll with the player READY, returns to Playing (§2.11) | `PlaybackControllerStateTest.reconnectThatNeverStarvedThePlayerReturnsToPlaying`; I-3 logs |
| D2 | `PlaybackControllerImpl` | I-17 made 3 refused requests (≤ 2 expected) | The account refreshed by L3 was only checked at the next failure | G1 runs right after the refresh, before the next open | `LadderExecutionTest.refreshedTerminalAccountFailsBeforeAnotherOpen`; I-17 |
| D3 | `LifecycleCoordinator` | Screen off reported `DISPLAY_OFF` instead of `STANDBY` | On API 30 the `DisplayListener` fires before `ACTION_SCREEN_OFF`; the first trigger named the stop | `STANDBY` renames a pending `DISPLAY_OFF` grace | `LifecycleTest.screenOffAfterTheDisplayTurnedOffIsStandby`; I-20 |
| D4 | `PlaybackControllerImpl` | Zap records had `firstByte = null` | WP-B emits no `FIRST_BYTE` event; the 500 ms poll often missed it before first media | Taken from `StreamTelemetry.firstByteAtMs` at first media | I-1 log |
| D5 | `PlaybackDebugActivity` (debug) | `ActivityScenario.close()` timed out after a second deep link (I-10, I-12) | `onNewIntent` called `setIntent`, so the scenario ignored later lifecycle events | No `setIntent` in the harness | I-10, I-12 |
| D6 | `ErrorPathTest` I-10 | Flaky `NoSuchElementException` | `awaitPlaying` matched the previous channel's Playing | `PanelTestBase.awaitPlayingOn(channel)` | I-10 |
| D7 | `SmokeAndZapTest` I-6 | "0 stream requests for a 10-press burst" (assertion trivially true) | `request_log` is capped at 200 entries, and the count was read before the debounced zap fired | Panel `stream_requests` monotonic counter (`FakePanelAdmin.streamRequests`); wait for the trailing zap's first media | I-6 now reports 3 |
| D8 | `MediaAndLifecycleTest` I-20 | One failure left the screen off and failed every later test | `wakeUp()` was after the assertions | `try/finally` | I-20 |

**Open items**
- **O-1 (I-3, ignored).** On the emulator, after some in-source splices (`close_every: 15`, continue mode) `OMX.google.h264.decoder` keeps accepting input but produces no output: the rendered count stays flat, the position freezes just before the splice, and the buffer fills to `maxBufferMs`. The decoder stall is not caught by the §3.7 detectors: b1 needs flat media growth, and extraction keeps growing. Recovery only comes from the TS `NO_BYTES` backstop (the loader parks on a full buffer), then L19 `ReResolve`, about 30 s later. The same happens with `FLAG_DETECT_ACCESS_UNITS` off. Without faults, ch1 plays 70 s cleanly. Two things are needed before un-ignoring: a run on real hardware (HW decoder), and a reviewed decision on a decoder-output stall detector (for example BUFFERING ≥ 4 s with queued video input growing and the rendered count flat → `DECODER_STUCK` → Reprepare).
- O-2: with `FLAG_DETECT_ACCESS_UNITS`, ffmpeg (AUD) streams queue about 2 video samples per frame on the emulator (`queuedInputBufferCount` ≈ 2 × rendered). Playback is unaffected. Kept as the default (§2.11); revisit with real broadcast captures.
- O-3: the emulator's audio for ch1 logs `UnexpectedDiscontinuityException` (~213 ms) at every 10.24 s loop of the fake panel's clip. This is a fake-panel media artefact (the audio is shorter than the video per loop) and is non-fatal.
- O-4: I-22 cannot exercise a real AFR switch (one display mode). HDR (§3.9.5) is not covered on the emulator.

### 5.9 Code review fixes (2026-10-14)

Both halves of the M2 code review (network/TS/logic and engine/controller/UI) are merged in `docs/review/m2-code-review.md`: 2 blockers and 11 should-fix items (E-B1 and N-S2 are one defect), all fixed with a regression test each; the "later" items are listed there and not applied. Contract amendments are in §2.11 ("Code review amendments"). The O-1 decoder-output stall detector (b3) is **not** implemented: its proposal is under a separate review, and I-3 stays `@Ignore`d.

Consequence for O-1: §5.8 says O-1's ~30 s recovery came from the TS `NO_BYTES` backstop once the loader parked. That was E-B1, the backstop firing on a parked loader. (Follow-up 2026-10-14, lead decision after both reviewers debated O-1.) The proposed b3 (queued-input growth) was rejected: output is flat in every BUFFERING and the input floor depends on undocumented codec slot counts, and it goes silent once the buffer is full. It is replaced by the **readiness stall** (§3.7 b1r), which catches O-1 about 5 s into the stall. Its verdicts escalate through L27 (Reprepare, then a compat raise whose save is deferred, then STREAM_UNDECODABLE). I-3 stays ignored; see §5.10.

Device suite (E-S5, E-S6): every wait and event assertion is scoped to the test (a tune-id and clock cursor taken in `@Before`); I-1, I-4/I-5, I-6, I-8, I-13 and I-22 assert what §5.5 says again; I-22 and the new I-22b (FULL from the start) skip the switch itself with `assumeTrue` when the display has no 50 Hz mode; new I-3b covers D4a on the audio-only ch 13 while I-3 is ignored; I-20 asserts no `FROZEN_VIDEO` during the standby grace.

### 5.10 O-1 diagnosis (2026-10-14)

**Question:** is O-1 (the emulator's `OMX.google.h264.decoder` keeps taking input but produces no output after an in-source splice; position frozen, buffer filling) caused by the AUD split of `FLAG_DETECT_ACCESS_UNITS` (O-2), or by something else?

**Method:** `O1DiagnosisTest` (skipped unless `-e o1diag <mode>` is given) replays I-3 (ch 1, `close_every: 15`, `continue`, 70 s) and logs every freeze (position flat ≥ 3 s), the stall verdicts, the decisions and the video decoder counters at every rebuffer. Modes: `aud-off` (only `FLAG_DETECT_ACCESS_UNITS` off, via `PlayerDebugHooks`; compat level and PMT mode unchanged), `compat1` (the channel at TS compat level 1: MULTI_PMT **and** the flag off), `default` (design flags). API 30 TV emulator, fixes of this pass in place.

| Run | O-1 stalls (freeze with the buffer filling) | Video counters at the stall's rebuffer (queued / rendered) | Readiness stall | Rebuffer → Playing again | End |
|---|---|---|---|---|---|
| aud-off #1 | 2: ~6.6 s after the 2nd reconnect, and 1.7 s after the next session's 1st reconnect | 969 / 761; 438 / 369 | fired both times (5.0 s, 8.0 s into BUFFERING) | 6.2 s; 10.4 s | Playing |
| aud-off #2 | 1, plus a rebuffer 3.4 s after the next session's 1st reconnect still filling to the raised threshold when the run ended (no verdict yet) | 970–1,081 / 747; 479 / 382 | fired once (5.0 s) | 7.7 s | Buffering |
| compat1 | 2, same positions as aud-off | 969 / 750; 441 / 370 | fired both (5.0 s, 8.0 s) | 6.5 s; — | **Error(STREAM_UNDECODABLE)** at 70 s: the 2nd verdict within 5 min at compat 1 is L27c |
| default #1 | 0 (an `AUDIO_UNDERRUN_STORM` → software audio re-prepare at 10 s shifted the session) | — | — | — | Playing |
| default #2 | 2, each ~1 s after a session's 1st reconnect | 903 / 401; 908 / 391 | fired both (5.0 s) | 6.8 s; 6.6 s | Playing |
| default #3 | 2: ~7 s after the 2nd reconnect; 1.8 s after the next session's 1st reconnect | 1,978 / 753; 895 / 368 | fired both (5.0 s, 8.0 s) | 6.9 s; 10.2 s | Playing |

Every run also had a startup rebuffer at ~4 s (`queued ≈ 106–287`, `rendered ≈ 64–96`, ~35 dropped): the emulator's soft decoder falling behind at first media. It recovered by itself in ~4.5 s and the readiness stall correctly stayed silent (buffer below the after-rebuffer threshold).

**Reading:**
- **O-1 is not the AUD split.** It occurs with `FLAG_DETECT_ACCESS_UNITS` off (aud-off, compat1) exactly as with it on, at the same stream positions: first session after ~750 rendered frames (30 s of the 25 fps clip, three 10 s loops), later sessions after ~370–400 frames, each 1–7 s after an in-source reconnect. The flag only changes the input/output ratio (≈ 2.6 queued per rendered frame with it on, ≈ 1.3 with it off: O-2), not whether the decoder wedges.
- The position dependence points at the content the splice delivers. After a `continue`-mode reconnect the panel resumes mid-GOP at a point that depends on where the loop seam and the 1 s connect bursts fall, and the extractor (with `FLAG_ALLOW_NON_IDR_KEYFRAMES`, after the forced CC gap) restarts on what it treats as a keyframe. The likeliest cause is `OMX.google.h264.decoder` accepting a non-IDR restart it cannot decode and then never producing output. This is a hypothesis: a hardware decoder run (D4) and a capture of the splice bytes are needed to confirm it. Nothing points at the extractor flags, so the default (flag on) stays.
- **The readiness stall fired in 9 of the 10 O-1 occurrences**: 5.0 s into BUFFERING when the buffer was already above the threshold, about 8 s when it first had to fill to a raised profile's threshold. The tenth was still filling to the raised threshold when its run ended. Rebuffer-to-playing recovery drops from ~30 s (Media3's stuck-buffering backstop) to 6.2–10.4 s.
- **Escalation on the emulator:** O-1 recurs within 5 minutes, so the second verdict raises the compat level (L27b; the save is deferred and never happened in a 70 s run) and the third, or the second at compat 1, ends in STREAM_UNDECODABLE. That is the ladder working as designed for a decoder that keeps wedging. It also means I-3 cannot pass on this emulator with or without the detector. **I-3 stays `@Ignore`d** until a hardware run.

## 6. Build wiring (commit 0, lead)

### 6.1 Catalog additions (`gradle/libs.versions.toml`)

All exist on Google Maven today (HTTP 200 checked 2026-10-13).

```toml
[libraries]
media3-test-utils = { module = "androidx.media3:media3-test-utils", version.ref = "media3" }
media3-test-utils-robolectric = { module = "androidx.media3:media3-test-utils-robolectric", version.ref = "media3" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "androidxLifecycle" }   # 2.10.0, ProcessLifecycleOwner
```

`media3-ui-compose` is already in the catalog (its POM imports Compose BOM 2026.05.00; ours, 2026.09.00, wins). Not added: `media3-exoplayer-dash` (§7 D20), `media3-ui` (no `PlayerView`; subtitles are M4's decision).

### 6.2 `:core`

No new dependencies. New packages under `app.thebox.core.playback` (§1.4/§1.5). Test resources live in `core/src/test/resources/playback/` (ladder CSV) and `core/src/test/resources/ts/` and `…/http/` (the fixtures of §5.2, owned by WP-B), so the pure-JVM tests own them; `:player`'s host tests add `../core/src/test/resources` as a resource directory (the M1 §6.3 variant-API trick, §6.3).

### 6.3 `:player`

```kotlin
plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
}

android {
    namespace = "app.thebox.player"
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.resources?.addStaticSourceDirectory("../core/src/test/resources") }
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":ffmpeg"))

    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    api(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)          // mockwebserver3 5.5.0
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.media3.test.utils) { exclude(group = "com.squareup.okhttp3", module = "mockwebserver") }
    testImplementation(libs.media3.test.utils.robolectric) { exclude(group = "com.squareup.okhttp3", module = "mockwebserver") }
}
```

`PlayerFactory` builder settings (WP-C), each verified against the 1.11.1 sources (Appendix A):

| Builder call | Value |
|---|---|
| `ExoPlayer.Builder(context, renderersFactory)` | `BoxRenderersFactory` |
| `setMediaSourceFactory` | not used (the controller builds each `MediaSource`); default kept |
| `setTrackSelector(DefaultTrackSelector(context, params))` | tunneling per setting, preferred languages, `ROLE_FLAG_MAIN` |
| `setLoadControl(AdaptiveLoadControl)` | §3.6.3 |
| `setAudioAttributes(USAGE_MEDIA / AUDIO_CONTENT_TYPE_MOVIE, handleAudioFocus = true)` | §3.10.5 |
| `setHandleAudioBecomingNoisy(true)` | §3.10.5 |
| `setWakeMode(C.WAKE_MODE_NETWORK)` | §3.10.5 |
| `setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)` | changed per aspect mode at runtime |
| `setVideoChangeFrameRateStrategy(…)` | per AFR mode (§3.9.1) |
| `setSeekBackIncrementMs(30_000)`, `setSeekForwardIncrementMs(30_000)` | archive only (product §2.3 S8: FF/RW ±30 s) |
| `setReleaseTimeoutMs(RELEASE_TIMEOUT_MS = 1_000)` | release must not stall the main thread on broken Amlogic codecs (default 500 ms; 1 s tolerates slow sticks) |
| `setStuckBufferingDetectionTimeoutMs(30_000)` | backstop (§3.7) |
| `setUseLazyPreparation(true)` | default |
| `setLooper(Looper.getMainLooper())` | MediaSession and Compose use the main looper |
| `setName("thebox")` | per-player load-control/allocator state |

### 6.4 `:ui`

```kotlin
dependencies {
    implementation(project(":core"))
    implementation(project(":player"))
    implementation(libs.media3.ui.compose)
    // … existing
}
```

### 6.5 `:app`

- `implementation(libs.androidx.lifecycle.process)` (for `ProcessLifecycleOwner` in tests and `MainActivity` wiring if needed; `:player` has it already).
- `app/src/debug/AndroidManifest.xml` + `app/src/debug/kotlin/app/thebox/debug/PlaybackDebugActivity.kt` (debug source set; nothing in `main`).
- `androidTestImplementation`: `androidx.test.ext.junit`, `androidx.test.runner`, `androidx.test.core`, `androidx-test-uiautomator`, `okhttp`, `kotlinx-coroutines-test` (reviewed 2026-10-13: `room-testing` stays in `:data`, where I-23 runs). The default `AndroidJUnitRunner` is kept (tests use the real `TheBoxApplication` and a Hilt `@EntryPoint`, not `HiltTestApplication`, so WorkManager and the bindings are the real ones).
- `TheBoxApplication.onCreate`: `importMutex.gate = zapImportGate` (injected; `ZapImportGate` is public in `:player` for this). `Media3LogBridge` is installed by `PlayerFactory` itself, and the controller registers its own `ComponentCallbacks2` on the application context for `TRIM_MEMORY_COMPLETE`, so no other application change is needed.
- `MainActivity`: collects `keepScreenOn` and binds `WindowDisplayModeBinder` (two lines; M3 replaces the placeholder screen). (reviewed 2026-10-13: `MainActivity.kt`, `TheBoxApplication.kt` and the manifests are lead-owned commit-0 edits, done against the WP-C/WP-D stubs, so no WP touches them.)

### 6.6 `:data`

Commit 0 only (§2.10): entity fields, `LibraryDatabase.VERSION = 2`, `autoMigrations = [AutoMigration(from = 1, to = 2)]`, exported `2.json`, migration tests. (reviewed 2026-10-13, S10) `ChannelPrefsDao`/`ChannelPrefsRepository` gain the column-targeted upserts and `PlaylistDao` gains `setXtreamOutputLearned`/`setXtreamOutput` (§2.10); `RoomChannelMemory` does **not** use `edit`.

### 6.7 Conventions (all WPs)

- M1 §6.6 applies (dispatchers, clocks, `BoxLog` only, no per-row logging, no regex in hot paths, `*Impl` naming, `internal` by default).
- **Media3 unstable API:** files that use `@UnstableApi` symbols annotate the class (or file) with `@androidx.annotation.OptIn(markerClass = [UnstableApi::class])`. Lint's `UnsafeOptInUsageError` stays enabled.
- **No URL in any log, exception message, `MediaItem`, `DataSpec` given to Media3 for live TS, or `toString()`.** Hosts (`host[:port]`) are fine. Every log line goes through `BoxLog` (redacted); Media3's logger is bridged (§3.13).
- **No `Thread.sleep` on the main thread; no `runBlocking`.** Loader-thread waits use `Condition.await(timeout)`.
- **Time:** `MonoClock` in `:core`; `SystemClock.elapsedRealtime()` only inside the binding.
- **Allocation:** nothing allocates per packet or per read in WP-B's hot loop beyond what OkHttp/Okio do; nothing allocates per watchdog tick in WP-A/WP-C.

### 6.8 Day-0 checks (commit 0)

| # | Check | Expected |
|---|---|---|
| 1 | `./gradlew :core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug` with every stub | green |
| 2 | `:player:debugUnitTestRuntimeClasspath` resolution | okhttp 5.5.0, robolectric 4.17, coroutines 1.11.0, no `com.squareup.okhttp3:mockwebserver:4.*` |
| 3 | A Robolectric test builds `TestExoPlayerBuilder(context).setRenderersFactory(...)` with `ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()`, plays `h264_aac_2s.ts` from a `FakeDataSource`/`mockwebserver3`, `runUntilRenderedFirstFrame` | passes; otherwise §5.4 fallback |
| 4 | `:app:assembleRelease` (R8) with `:player` and `:ui` changes | green; `PlaybackDebugActivity` absent from the release APK (`apkanalyzer dex packages`) |
| 5 | `MigrationTestHelper` 1 → 2 on the API 30 AVD | passes |
| 6 | Universal debug APK on `TheBox_TV_API30`: `FfmpegAvailability.isAvailable` | false with the ARM-only AAR (documents §5.6); true with the x86 debug AAR (§8 Q3) |
| 7 | (reviewed 2026-10-13) `:data` Robolectric migration test sees `schemas/…/1.json` and `2.json` as assets; `2.json`'s migration SQL is exactly the three `ALTER TABLE`s of §2.10 | passes |
| 8 | (reviewed 2026-10-13, B5) merged release manifest (`apkanalyzer manifest print`): no `MediaSessionService` filter, no `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `MainActivity` without PiP | as stated |
| 9 | (reviewed 2026-10-13, S14) check 3 uses `Surface(SurfaceTexture(1))`, `IdlingMediaCodecAdapterFactory` and `Clock.DEFAULT` | — |

---
### 6.9 Day-0 results (commit 0)

Run on 2026-10-13 on the dev Mac (Intel x86_64, JDK 21.0.2, Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21), in the commit-0 worktree, with every stub in place.

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

---

## 7. Decisions made and alternatives rejected

| # | Decision | Rejected alternative | Why |
|---|---|---|---|
| D1 | `:player` defines provider interfaces; `:app` binds them to `:data` | `:player` depends on `:data` | JVM/Robolectric tests of the resolver and controller without Room; M3/M4 see `:player`'s API, not repositories; the same seam serves v2 `:dvr` |
| D2 | Pure decision logic in `:core` (`app.thebox.core.playback`) | All logic inside `:player` (Android library, JUnit 4) | JUnit 5 and sub-second tests, no Android types in rules; M1 precedent (parsers and the EPG engine in `:core`). Cost: WP-A and WP-B touch two modules |
| D3 | Reconnect **inside** `LiveTsDataSource`, packet-aligned, with a PCR check | Reconnect by re-preparing in the controller | §3.3.1: a routine server close becomes invisible instead of a 1–2 s freeze; Range is impossible by construction. Risk (PTS jumps) is detected within one PCR interval, post-splice packets are held until the verdict, and the re-prepare happens when the buffer drains (reviewed 2026-10-13, S1). CC gaps are forced at the splice (B1) |
| D4 | `open()` returns `C.LENGTH_UNSET` even with a Content-Length | Trust the server's length | A known length makes `ProgressiveMediaPeriod` resume at an offset (Range) and `TsExtractor` read the duration from the end (Range). Both verified in source |
| D5 | Live TS `MediaItem` URI is a session placeholder; the real URL lives in the session | Real URL in `MediaItem`/`DataSpec` | Keeps credentials and tokens out of every Media3 object, event and exception (CLAUDE.md redaction rule) |
| D6 | Tee sink **above** the reconnect core | Tee between OkHttp and the reconnect logic (literal reading of "upstream → TeeDataSource(sink)") | A v2 recorder then receives one continuous packet-aligned TS instead of N connections with partial packets |
| D7 | Broker lease released after `Call.cancel()` + body close (in that order, released from the call's `EventListener`; reviewed 2026-10-13, S2), pre-empt eagerly on the main thread at tune | Release on `player.stop()`; no pre-emption | `stop()` does not close the socket in time (loader blocked in `read()`); plan §3 "Connections" |
| D8 | Leading-edge + 350 ms trailing zap debounce | Pure trailing debounce (every zap waits 350 ms) | A single press keeps zap latency; held keys cost ≤ 2 connections |
| D9 | `AdaptiveLoadControl` with a volatile profile | Rebuild the player on profile change; fixed DefaultLoadControl | No decoder/surface churn; profile changes apply within one loop iteration |
| D10 | Per-MIME FFmpeg preference via the codec selector; passthrough denial via a forwarding sink | `EXTENSION_RENDERER_MODE_PREFER` globally; rebuilding renderers on fallback | Runtime, per codec, no rebuild; platform decoders stay first for everything else (they do passthrough and hardware AAC) |
| D11 | Auto-PREFER for MP2/LATM is **not** implemented as a mode switch | Detect "no MP2 decoder" at startup and set PREFER | ON mode already selects FFmpeg when the platform has no decoder; LATM is AAC after `LatmReader` (verified). Only failures need learning (L31–L32) |
| D12 | Four detectors in a 1 Hz main-thread watchdog + Media3's stuck detector as backstop | Media3's `StuckPlayerDetector` alone (10 s/600 s) | Faster (3–6 s), detector-specific actions (compat level, PCM fallback), guards for paused/hidden/tunneled |
| D13 | AFR ALWAYS: `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)` on API 31+, `preferredDisplayModeId` below 31 | `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)` from API 30 (M2 brief and research §2.7 wording) | The 3-argument overload and `CHANGE_FRAME_RATE_ALWAYS` are API 31 (`api-versions.xml`); API 30's 2-argument call has unspecified non-seamless behaviour |
| D14 | fps from presentation-time deltas, snapped, cached per channel | Wait for `Format.frameRate` | TS readers never set it (verified) |
| D15 | Home/`onStop` stops immediately; 30 s grace only for screen-off/HDMI/display-off | 30 s grace on `onStop` too | TV norm (research §4), frees the account's only connection, no audio over the launcher; the plan's 30 s is for TV-off |
| D16 | No FGS for live TV in v1; the controller owns the session; **no `MediaSessionService` declared in v1** (reviewed 2026-10-13, B5) | MediaSessionService with Media3's automatic foreground notification; a declared service that suppresses `startForeground` | Android 12+ FGS restrictions, a pointless notification on TV, no background playback in v1. A declared service receives media-button `startForegroundService` intents on API < 31 and would crash without `startForeground`. v1.1/v2 re-add it with a real FGS path |
| D17 | `PlayerSurface(SURFACE_VIEW)` with a surface-only `ForwardingPlayer` | Raw `AndroidView(SurfaceView)`; `ContentFrame`; `PlayerView` | Keeps Media3's API 34 resize workaround and lifecycle handling; `ContentFrame` takes away rect control (B1.5d); `PlayerView` brings a View UI we do not use |
| D18 | HDR poster by disabling the video track in MINI | Let HDR play in the mini window | No HDMI mode switch while browsing (B1.6). D6 may refine per device |
| D19 | One additive `library.db` migration for learned per-channel/per-playlist state | Keep learned state in memory until M5 | Learned TS compatibility and fps are cheap to persist and expensive to re-learn (a stall each time) |
| D20 | No DASH module in v1 | Add `media3-exoplayer-dash` now | No v1 source produces DASH (Widevine/`#KODIPROP` is v1.1); saves APK size; `UNSUPPORTED_SOURCE` with "Open in external player" covers it |
| D21 | HLS segment tokens refreshed by re-resolving the source | `ResolvingDataSource` rewriting segment tokens in place | Needs per-panel knowledge of the token parameter; a 403 re-resolve costs ~1 s and is rare. Revisit with real panels |
| D22 | One-shot VLC UA fallback on a 403 that is not a connection limit, never persisted automatically | No automatic UA change; or always impersonate an IPTV app | Product §4.2 ("auto-switch to VLC on a 403 probe, then explained"); VLC is not an IPTV app, and the user decides on persistence (M5) |
| D23 | Stats bitrate measured in the data source | `onBandwidthEstimate` | Media3's bandwidth meter samples only at transfer end (verified), never on an endless TS load |
| D24 | Media3 logs bridged into `BoxLog` | Leave Media3 logging to `android.util.Log` | Media3 logs exception chains that may contain hosts and, for HLS, URLs; redaction must apply to everything |
| D25 | Fake panel gets continuous timestamps before D4 runs | Test D4 against the v0 looping clip | The v0 clip jumps back 10 s every 10 s, which makes every reconnect test meaningless |
| D26 | (reviewed 2026-10-13, B3) The `ExoPlayer` lives for the process; no idle release | Release after 5 min in the background or on `TRIM_MEMORY_COMPLETE` and re-create | `surfacePlayer`/`SessionPlayer` wrap a fixed player and `PlayerSurface` does not re-attach to a hidden replacement; `stop()` already frees codecs and the media buffer |
| D27 | (reviewed 2026-10-13, B2) "First media" milestone: first video frame, or first audio position when no video track is enabled | `onRenderedFirstFrame` only | Radio channels, the HDR poster and HIDDEN tunes never render a frame |
| D28 | (reviewed 2026-10-13, S5) Dead-track guard around `TsExtractor`'s output; `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` off | Trust every PMT entry | `ProgressiveMediaPeriod` waits for a format on every track; dead PIDs, SCTE-27 on 0x82 and LATM v1 would hang the tune |

---

## 8. Open questions for the lead

(reviewed 2026-10-13: the reviewer's recommendation on each question is in `review/m2-design-review.md`, "Lead decisions needed". Q1's split now relies on the `isInteractive()` check of B4; Q10 is resolved by S5 (flag off by default); Q12 is cheap for H.264 (§3.9.3).)

1. **Home/`onStop` behaviour (D15).** This design stops at once on `onStop` and applies the 30 s grace only to screen-off/HDMI-unplug/display-off. The M2 brief asked for "`ACTION_SCREEN_OFF`/`onStop` → stop after 30 s grace". Confirm the split.
2. **Fake panel v1 (§5.6, D25)** is a prerequisite for I-3…I-5 and I-15. It is WP-D work (~200 lines of Python + tests). Approve, or move the TS rewriting to a separate small task before WP-D starts.
3. **FFmpeg on the x86 TV AVD.** The local AAR is ARM-only, so the MP2-via-FFmpeg test (I-13) cannot pass its FFmpeg branch on `TheBox_TV_API30`. Options: (a) build a debug x86 AAR locally (`--abis "x86 armeabi-v7a arm64-v8a"`), never shipped; (b) accept that the FFmpeg path is only verified on ARM hardware (M6); (c) add an ARM64 TV AVD image (slow on an Intel Mac). Recommendation: (a).
4. **Automatic UA fallback (D22).** Is a one-shot retry with `VLC/3.0.20 LibVLC/3.0.20` acceptable as default-on, given the "do not impersonate another IPTV app by default" rule?
5. **Buffer decay period.** Product §4.4 says 10 min stable; architecture review B4.1 said 24 h. This design follows the product (approved baseline wins). Confirm.
6. **Persisting learned audio facts per device route** (passthrough denials, software-audio MIMEs). In memory per process in M2; persist in M5 DataStore? (reviewed 2026-10-13: `AudioDeviceInfo.getId()` is not stable across reboots or re-plugs, so a persisted key must be the route's type + product name, not the id; §3.8.3's in-memory key may keep the id.) A Fire stick with a misreporting soundbar would otherwise relearn after every app restart (one 3 s silent start).
7. **`library.db` v2 in M2 (D19).** OK for M2 to ship a schema migration, or defer the three columns to M5's settings work?
8. **HIDDEN viewport.** Defined as "video track disabled, audio continues, connection kept". Should a full-screen settings page instead stop playback after a timeout (frees the connection on 1-connection accounts)?
9. **PCR jump tolerance (2 s) and "re-prepare vs ride through".** D4 must measure what the panel and the extractor actually do; if Media3 rides through forward jumps cleanly on hardware, the tolerance may become asymmetric (re-prepare on backward jumps only).
10. ~~**`FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` on by default.**~~ (reviewed 2026-10-13, S5: **off** by default. DVB DTS uses stream type 0x06 + descriptor, mapped without the flag; 0x82 is SCTE-27 subtitles on North American cable restreams and would create a format-less DTS track that blocks prepare.)
11. **Resolution switching with AFR** stays off (protocols §6.3). Confirm no M2 work for 4K/1080 mode switching.
12. **`interlacedHint`** is always false in v1 (Media3 does not expose field coding). Acceptable, or should WP-C parse the SPS `frame_mbs_only_flag` in a custom `TsPayloadReader` wrapper (not cheap)?

---
## Appendix A. Media3 1.11.1 API facts (verified against sources)

Method: the 1.11.1 `-sources.jar`s of `media3-common`, `-container`, `-database`, `-datasource`, `-datasource-okhttp`, `-decoder`, `-exoplayer`, `-exoplayer-hls`, `-extractor`, `-session` from the Gradle cache, plus `media3-ui-compose`, `media3-test-utils`, `media3-test-utils-robolectric` downloaded from Google Maven, unzipped into the session scratchpad and read. The FFmpeg AAR in `ffmpeg/libs/` was inspected with `javap`. Platform API levels come from `platforms/android-37.0/data/api-versions.xml`. Paths are relative to `androidx/media3/`; line numbers are 1.11.1.

### A.1 Data sources and HTTP

| Fact | Where |
|---|---|
| `OkHttpDataSource.Factory(Call.Factory callFactory)` | `datasource/okhttp/OkHttpDataSource.java:91` |
| `Factory.setDefaultRequestProperties(Map<String,String>)` (`final`, replaces earlier defaults) | `:99` |
| `Factory.setUserAgent(String?)` → `builder.addHeader(USER_AGENT, …)`: **adds** a second UA if the header map has one. Do not use it | `:115`, `:408` |
| Request headers (`makeRequest`, :373): factory defaults, then `setRequestProperty`, then `DataSpec.httpRequestHeaders` via `builder.header` (replace); then `addHeader(RANGE, …)` from `HttpUtil.buildRangeRequestHeader(position, length)` (null only when `position == 0 && length == LENGTH_UNSET`); `Accept-Encoding: identity` unless `FLAG_ALLOW_GZIP` | `:373–411`, `datasource/HttpUtil.java:57` |
| A 200 answer to a positioned request makes `OkHttpDataSource` read and discard `position` bytes | `:323` |
| Non-2xx → `HttpDataSource.InvalidResponseCodeException(responseCode, message, cause, headerFields, dataSpec, responseBody)`, error code `ERROR_CODE_IO_BAD_HTTP_STATUS`, `TYPE_OPEN`; the body is read fully first | `:281–312`, `datasource/HttpDataSource.java:416–431` |
| Calls are made with `call.enqueue` + a future (`executeCall`, :429); interruption cancels the call and throws `InterruptedIOException` (→ `ERROR_CODE_FAILED_RUNTIME_CHECK`). `close()` (:362) closes the response body (`closeConnectionQuietly`, :534); it does not cancel the call | `:261–365`, `:429–450`, `:534–539` |
| No redirect logic: OkHttp's `followRedirects`/`followSslRedirects` (default true) decide, cross-protocol included | `OkHttpDataSource.java` (no redirect code) |
| `HttpDataSourceException(String, IOException?, DataSpec, @ErrorCode int, @Type int)`; `TYPE_OPEN = 1`, `TYPE_READ = 2`, `TYPE_CLOSE = 3`; `createForIOException`: `SocketTimeoutException` → 2002, `InterruptedIOException` → 1004, cleartext → 2007, else 2001 | `datasource/HttpDataSource.java:194–370` |
| `DataSourceException.reason` becomes `PlaybackException.errorCode` (`catch (DataSourceException e) { handleIoException(e, e.reason); }`); `BehindLiveWindowException` → `ERROR_CODE_BEHIND_LIVE_WINDOW`; other `IOException` → `ERROR_CODE_IO_UNSPECIFIED` | `exoplayer/ExoPlayerImplInternal.java:992–997` |
| `DataSpec` fields `uri`, `position`, `length`, `httpRequestHeaders`, `flags`, `customData`; flags `FLAG_ALLOW_GZIP = 1`, `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN = 2`, `FLAG_ALLOW_CACHE_FRAGMENTATION = 4`, `FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED = 8`; `Builder.setPosition/setLength/setHttpRequestHeaders/setFlags`; `withAdditionalHeaders`, `withRequestHeaders`, `withUri`, `subrange` | `datasource/DataSpec.java:171–219, 275–292, 332–413, 517–595` |
| `TeeDataSource(DataSource upstream, DataSink dataSink)`; the sink is opened only for a non-empty range, with `subrange(0, bytesRemaining)` when the length becomes known | `datasource/TeeDataSource.java:42–66` |
| `DataSink` (`open`, `write`, `close`) and `DataSink.Factory` | `datasource/DataSink.java:23–61` |
| `ResolvingDataSource.Factory(DataSource.Factory, Resolver)`; `Resolver.resolveDataSpec(DataSpec)` (not used in M2) | `datasource/ResolvingDataSource.java:32–81` |

### A.2 Progressive loading and TS

| Fact | Where |
|---|---|
| `ProgressiveMediaPeriod.ExtractingLoadable.load()` opens `buildDataSpec(positionHolder.position, etag)` with `Icy-MetaData` and, when the previous response had a strong ETag, `If-Range`; a known length (`open() != LENGTH_UNSET`) calls `onLengthKnown()` | `exoplayer/source/ProgressiveMediaPeriod.java:1291–1395` |
| While buffers are full the loader waits in `loadCondition.block()` and does not call `read()` | `:1331–1336` |
| `configureRetry`: length or duration known → resume at the extractor's input position (**Range**); unknown and prepared → deferred retry from 0 when the buffer drains (`maybeStartDeferredRetry`, :727); unknown and not prepared → immediate retry from 0 | `:1093–1125` |
| `onLoadError` asks `loadErrorHandlingPolicy.getRetryDelayMsFor(new LoadErrorInfo(...))`; `C.TIME_UNSET` → `Loader.DONT_RETRY_FATAL` | `:845–895` |
| `Loader.LoadTask.cancel` calls `loadable.cancelLoad()` and interrupts the loader thread (socket reads ignore interrupts) | `exoplayer/upstream/Loader.java:409–430` |
| `ProgressiveMediaSource.Factory(DataSource.Factory, ExtractorsFactory)`, `setLoadErrorHandlingPolicy`, `setContinueLoadingCheckIntervalBytes`, `setLoadOnlySelectedTracks` | `exoplayer/source/ProgressiveMediaSource.java:134, 193, 214, 283` |
| `TsExtractor`: `MODE_MULTI_PMT = 0`, `MODE_SINGLE_PMT = 1`, `MODE_HLS = 2`; `TS_PACKET_SIZE = 188`; `TS_SYNC_BYTE = 0x47`; `DEFAULT_TIMESTAMP_SEARCH_BYTES = 600 × 188`; stream types incl. `AAC_LATM = 0x11`, `HDMV_DTS = 0x82`, `MPA = 0x03` | `extractor/ts/TsExtractor.java:84–150` |
| `TsExtractor.read`: with a known input length (and not HLS) the duration reader runs and seeks (RESULT_SEEK) — a positioned open; `SeekMap.Unseekable` otherwise | `:426–449`, `:540–556` |
| Continuity check: a repeated counter drops the packet; a gap calls `payloadReader.seek()` (PES reset) | `:493–506` |
| Constructor `TsExtractor(mode, extractorFlags, SubtitleParser.Factory, TimestampAdjuster, TsPayloadReader.Factory, timestampSearchBytes)` | `:323` |
| `DefaultTsPayloadReaderFactory` flags: `FLAG_ALLOW_NON_IDR_KEYFRAMES = 1`, `FLAG_IGNORE_AAC_STREAM = 2`, `FLAG_IGNORE_H264_STREAM = 4`, `FLAG_DETECT_ACCESS_UNITS = 8`, `FLAG_IGNORE_SPLICE_INFO_STREAM = 16`, `FLAG_OVERRIDE_CAPTION_DESCRIPTORS = 32`, `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS = 64` | `extractor/ts/DefaultTsPayloadReaderFactory.java:68–109` |
| `DefaultExtractorsFactory.setTsExtractorMode(int)`, `setTsExtractorFlags(int)` (payload-reader flags), `setTsExtractorTimestampSearchBytes(int)` | `extractor/DefaultExtractorsFactory.java:335, 349, 378` |
| `LatmReader` emits `MimeTypes.AUDIO_AAC` (= `"audio/mp4a-latm"`); throws `ParserException` for `numProgram/numLayer != 0` and `audioMuxVersionA == 1`; (reviewed 2026-10-13) for `audioMuxVersion == 1` it parses the config but never calls `output.format` (only the version-0 branch does), and `frameLengthType == 2` throws `IllegalStateException` | `extractor/ts/LatmReader.java:191–260`, `common/MimeTypes.java:76` |
| MPEG audio in TS → `MpegAudioReader` → `audio/mpeg-L1/L2` or `audio/mpeg` by layer | `extractor/ts/DefaultTsPayloadReaderFactory.java:152–155`, `extractor/MpegAudioUtil.java:101, 240` |
| The TS H.264/H.265 readers never set `Format.frameRate` (no `setFrameRate` in `extractor/ts/`) | grep over `extractor/ts/*.java` |
| `BehindLiveWindowException extends IOException` | `exoplayer/source/BehindLiveWindowException.java:23` |

### A.3 Load error policy and LoadControl

| Fact | Where |
|---|---|
| `LoadErrorHandlingPolicy`: `getFallbackSelectionFor(FallbackOptions, LoadErrorInfo)`, `getRetryDelayMsFor(LoadErrorInfo)` (`C.TIME_UNSET` = fatal), `onLoadTaskConcluded(long)` (default), `getMinimumLoadableRetryCount(int dataType)`; `LoadErrorInfo` fields `loadEventInfo`, `mediaLoadData`, `exception`, `errorCount` | `exoplayer/upstream/LoadErrorHandlingPolicy.java:54–229` |
| `DefaultLoadErrorHandlingPolicy`: retry delay `min((errorCount − 1) × 1000, 5000)` unless a non-retriable cause (`ParserException`, `FileNotFoundException`, `CleartextNotPermittedException`, `UnexpectedLoaderException`, 416); **`InvalidResponseCodeException` is retried by default**; min retry count 3, 6 for `DATA_TYPE_MEDIA_PROGRESSIVE_LIVE` | `DefaultLoadErrorHandlingPolicy.java:37–43, 128–183` |
| `MediaLoadData.dataType`; `C.DATA_TYPE_MEDIA = 1`, `DATA_TYPE_MANIFEST = 4`, `DATA_TYPE_MEDIA_PROGRESSIVE_LIVE = 7` | `exoplayer/source/MediaLoadData.java:31`, `common/C.java:991–1014` |
| `LoadEventInfo.uri`, `responseHeaders`, `bytesLoaded`, `loadDurationMs` | `exoplayer/source/LoadEventInfo.java:191–219` |
| `LoadControl`: `getAllocator(PlayerId)`, `onPrepared(PlayerId)`, `onTracksSelected(Parameters, TrackGroupArray, ExoTrackSelection[])`, `onStopped(PlayerId)`, `onReleased(PlayerId)`, `getBackBufferDurationUs(PlayerId)`, `retainBackBufferFromKeyframe(PlayerId)`, `shouldContinueLoading(Parameters)`, `shouldStartPlayback(Parameters)`; `Parameters` has `playerId`, `bufferedDurationUs`, `playbackSpeed`, `playWhenReady`, `rebuffering`, `targetLiveOffsetUs`, `lastRebufferRealtimeMs` | `exoplayer/LoadControl.java:41–440` |
| `DefaultLoadControl.Builder`: `setAllocator`, `setBufferDurationsMs(min, max, forPlayback, afterRebuffer)` (validates `min ≥ forPlayback`, `min ≥ afterRebuffer`, `max ≥ min`), `setTargetBufferBytes`, `setPrioritizeTimeOverSizeThresholds`, `setBackBuffer(ms, retainFromKeyframe)`, `build()`; defaults 50,000/50,000/1,000/2,000 ms | `exoplayer/DefaultLoadControl.java:64–130, 257–525` |
| `DefaultLoadControl.shouldContinueLoading`: `minBufferUs = max(minBufferUs, 500_000)`; below min → load unless the byte target is reached; at max or byte target → stop | `:766–809` |
| `Allocator.getTotalBytesAllocated()` | `exoplayer/upstream/Allocator.java:68` |
| `DefaultBandwidthMeter` notifies a bandwidth sample in `onTransferEnd` (and on a network change), never during an endless transfer | `exoplayer/upstream/DefaultBandwidthMeter.java:430–502` |

### A.4 ExoPlayer, renderers, audio

| Fact | Where |
|---|---|
| `ExoPlayer.Builder`: `setRenderersFactory` :641, `setMediaSourceFactory` :656, `setTrackSelector` :672, `setLoadControl` :688, `setLooper` :723, `setAudioAttributes(AudioAttributes, boolean handleAudioFocus)` :811, `setWakeMode` :839, `setHandleAudioBecomingNoisy` :858, `setVideoScalingMode` :906, `setVideoChangeFrameRateStrategy` :928 (Javadoc: for `CHANGE_FRAME_RATE_ALWAYS` set `OFF` and call `Surface.setFrameRate` yourself), `setSeekBackIncrementMs` :978, `setSeekForwardIncrementMs` :994, `setReleaseTimeoutMs` :1049, `setStuckBufferingDetectionTimeoutMs` :1088, `setStuckPlayingDetectionTimeoutMs` :1109, `setName` :1327 | `exoplayer/ExoPlayer.java` |
| Stuck detection is on by default: buffering 600,000 ms, playing `isRunningOnEmulator() ? 30_000 : 10_000`, not-ending 60,000, suppressed 600,000; a trigger stops the player with `ERROR_CODE_TIMEOUT` and a `StuckPlayerException` (`common/util/StuckPlayerException.java`, types 0–4) | `ExoPlayer.java:228–258`; `ExoPlayerImpl.java:3649–3655` |
| `ExoPlayer.setVideoScalingMode` :1816, `setVideoChangeFrameRateStrategy` :1836, `setVideoFrameMetadataListener` :1854, `getVideoDecoderCounters()` :2017, `isTunnelingEnabled()` :2103, `addAnalyticsListener` :1422, `getTrackSelector` :1473 | `exoplayer/ExoPlayer.java` |
| `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF = Integer.MIN_VALUE`, `…_ONLY_IF_SEAMLESS = Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`; `C.WAKE_MODE_NONE/LOCAL/NETWORK = 0/1/2`; `C.VIDEO_SCALING_MODE_SCALE_TO_FIT`, `…_WITH_CROPPING` | `common/C.java:808–840, 1517–1538` |
| `VideoFrameReleaseHelper` estimates fps (`FixedFrameRateEstimator`) and calls `Surface.setFrameRate` only on API 30+, with the strategy | `exoplayer/video/VideoFrameReleaseHelper.java:114, 303–396` |
| `DefaultRenderersFactory`: `EXTENSION_RENDERER_MODE_OFF/ON/PREFER = 0/1/2`; `setExtensionRendererMode` :158, `setEnableDecoderFallback` :216, `setMediaCodecSelector` :230; overridable `buildVideoRenderers` :535, `buildAudioRenderers` :664, `buildAudioSink(Context, boolean, boolean)` :938; FFmpeg audio and the experimental FFmpeg video renderer are loaded by reflection when the mode is not OFF | `exoplayer/DefaultRenderersFactory.java:86–102, 620–645, 764–777` |
| `MediaCodecSelector.getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)`; `DEFAULT = MediaCodecUtil::getDecoderInfos`; `PREFER_SOFTWARE` | `exoplayer/mediacodec/MediaCodecSelector.java` |
| `MediaCodecInfo` public fields `name`, `mimeType`, `hardwareAccelerated`, `softwareOnly`, `vendor`, `tunneling`, `secure` | `exoplayer/mediacodec/MediaCodecInfo.java:92–166` |
| `MediaCodecRenderer.DecoderInitializationException` fields `mimeType`, `secureDecoderRequired`, `codecInfo`, `diagnosticInfo`, `fallbackDecoderInitializationException` | `exoplayer/mediacodec/MediaCodecRenderer.java:142–161` |
| `MediaCodecAudioRenderer.supportsFormat`: `audioSink.supportsFormat(format)` → handled as passthrough **before** the selector is asked; empty decoder list → `FORMAT_UNSUPPORTED_SUBTYPE`; `shouldUseBypass` = `audioSink.supportsFormat(format)` (no offload) | `exoplayer/audio/MediaCodecAudioRenderer.java:348–500` |
| `ForwardingAudioSink(AudioSink)` with `supportsFormat`/`getFormatSupport` overridable; `AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY/…_WITH_TRANSCODING/…_UNSUPPORTED` | `exoplayer/audio/ForwardingAudioSink.java:37–63`, `AudioSink.java:575–599` |
| `DefaultAudioSink.Builder(Context)` :311, `setAudioProcessors(AudioProcessor[])` :338, `setEnableFloatOutput` :367; `AudioSink.InitializationException` fields `audioTrackState`, `isRecoverable`, `format` | `exoplayer/audio/DefaultAudioSink.java`, `AudioSink.java:441–447` |
| `ChannelMixingAudioProcessor.putChannelMixingMatrix(ChannelMixingMatrix)`; `ChannelMixingMatrix.createForConstantGain(int in, int out)` | `common/audio/ChannelMixingAudioProcessor.java:45`, `ChannelMixingMatrix.java:67` |
| `AudioCapabilitiesReceiver(Context, Listener, AudioAttributes, AudioDeviceInfo?)`, `register()`, `unregister()`; `AudioCapabilities.getCapabilities(Context, AudioAttributes, AudioDeviceInfo?, List<Integer>)` (1-/3-arg forms deprecated) | `exoplayer/audio/AudioCapabilitiesReceiver.java:88–191`, `AudioCapabilities.java:118–150` |
| `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled` :1638; `TrackSelectionParameters.Builder.setPreferredAudioLanguages` :621, `setPreferredAudioRoleFlags` :633, `setMaxAudioChannelCount` :658, `setPreferredTextLanguages` :776, `setOverrideForType` :891, `clearOverridesOfType` :906, `setTrackTypeDisabled` :948; `TrackSelectionOverride(TrackGroup, int)` | `exoplayer/trackselection/DefaultTrackSelector.java`, `common/TrackSelectionParameters.java`, `common/TrackSelectionOverride.java:64` |
| `Tracks.Group.getMediaTrackGroup()`, `getTrackFormat(i)`, `isTrackSelected(i)`, `isTrackSupported(i)`, `getType()`; `TrackGroup.id` (`@UnstableApi String`) | `common/Tracks.java:82–190`, `common/TrackGroup.java:60` |
| `Format.frameRate`, `colorInfo`, `codecs`, `sampleMimeType`, `language`, `roleFlags`, `channelCount`, `bitrate`; `ColorInfo.isTransferHdr(ColorInfo?)`; `C.COLOR_TRANSFER_ST2084`, `COLOR_TRANSFER_HLG`; `VideoSize.width/height/pixelWidthHeightRatio` | `common/Format.java:980–1243`, `common/ColorInfo.java:322`, `common/C.java:1291–1314`, `common/VideoSize.java:36–56` |
| `DecoderCounters.renderedOutputBufferCount`, `droppedBufferCount`, `ensureUpdated()` | `exoplayer/DecoderCounters.java:50–130` |
| `Player.Listener`: `onEvents` :877, `onTracksChanged` :919, `onIsLoadingChanged` :949, `onPlaybackStateChanged` :995, `onIsPlayingChanged` :1031, `onPlayerError` :1066, `onPositionDiscontinuity` :1102, `onAudioSessionIdChanged` :1158, `onVideoSizeChanged` :1219, `onSurfaceSizeChanged` :1233, `onRenderedFirstFrame` :1242 | `common/Player.java` |
| `AnalyticsListener`: `onLoadError` :914, `onBandwidthEstimate` :949, `onAudioDecoderInitialized` :1007, `onAudioInputFormatChanged` :1031, `onAudioPositionAdvancing(EventTime, long playoutStartSystemTimeMs)` :1045, `onAudioUnderrun` :1057, `onAudioSinkError` :1121, `onAudioCodecError` :1138, `onAudioTrackInitialized(EventTime, AudioSink.AudioTrackConfig)` :1148, `onVideoDecoderInitialized` :1210, `onVideoInputFormatChanged` :1234, `onDroppedVideoFrames` :1249, `onVideoFrameProcessingOffset` :1285, `onVideoCodecError` :1303, `onRenderedFirstFrame(EventTime, Object, long)` :1315 | `exoplayer/analytics/AnalyticsListener.java` |
| `PlaybackException` codes: `ERROR_CODE_TIMEOUT = 1003`, `BEHIND_LIVE_WINDOW = 1002`, `IO_NETWORK_CONNECTION_FAILED = 2001`, `…_TIMEOUT = 2002`, `IO_BAD_HTTP_STATUS = 2004`, `IO_CLEARTEXT_NOT_PERMITTED = 2007`, `IO_READ_POSITION_OUT_OF_RANGE = 2008`, `PARSING_CONTAINER_MALFORMED = 3001`, `PARSING_MANIFEST_MALFORMED = 3002`, `PARSING_CONTAINER_UNSUPPORTED = 3003`, `PARSING_MANIFEST_UNSUPPORTED = 3004`, `DECODER_INIT_FAILED = 4001`, `DECODER_QUERY_FAILED = 4002`, `DECODING_FAILED = 4003`, `…_FORMAT_EXCEEDS_CAPABILITIES = 4004`, `…_FORMAT_UNSUPPORTED = 4005`, `…_RESOURCES_RECLAIMED = 4006`, `AUDIO_TRACK_INIT_FAILED = 5001`, `AUDIO_TRACK_WRITE_FAILED = 5002` | `common/PlaybackException.java:160–293` |
| `Player.seekToDefaultPosition()` | `common/Player.java:2684` |
| `androidx.media3.common.util.Log.setLogger(Logger)` :181, `setLogLevel(int)` :158 | `common/util/Log.java` |

### A.5 HLS, MediaItem

| Fact | Where |
|---|---|
| `HlsMediaSource.Factory(DataSource.Factory)` :146; `setExtractorFactory` :195, `setLoadErrorHandlingPolicy` :202, `setPlaylistTrackerFactory` :260, `setAllowChunklessPreparation` :300 | `exoplayer/hls/HlsMediaSource.java` |
| `DefaultHlsExtractorFactory(int payloadReaderFactoryFlags, boolean exposeCea608WhenMissingDeclarations)` | `exoplayer/hls/DefaultHlsExtractorFactory.java:99` |
| `DefaultHlsPlaylistTracker.FACTORY` | `exoplayer/hls/playlist/DefaultHlsPlaylistTracker.java:71` |
| `MediaItem.Builder.setUri/setMediaId/setMimeType/setLiveConfiguration` :149–496; `MediaItem.LiveConfiguration.Builder.setTargetOffsetMs/setMinOffsetMs/setMaxOffsetMs/setMinPlaybackSpeed/setMaxPlaybackSpeed` :1357–1405 | `common/MediaItem.java` |

### A.6 Session, Compose, players

| Fact | Where |
|---|---|
| `MediaSessionService extends LifecycleService`; `onGetSession(ControllerInfo)` :338; `onUpdateNotification(MediaSession, boolean startInForegroundRequired)` :824 (overridable; the default starts the service in the foreground while playing) | `session/MediaSessionService.java` |
| `MediaSession.Builder(Context, Player)` :284, `setId` :321, `build()` :550 | `session/MediaSession.java` |
| `ForwardingPlayer(Player)` :64 with `isCommandAvailable` :217, `getAvailableCommands` :229; `ForwardingSimpleBasePlayer(Player)` :77 | `common/ForwardingPlayer.java`, `common/ForwardingSimpleBasePlayer.java` |
| `PlayerSurface(player: Player?, modifier, surfaceType = SURFACE_TYPE_SURFACE_VIEW)`: creates its `SurfaceView` in an `AndroidView`, attaches with `setVideoSurfaceView` in a `LaunchedEffect` after checking `COMMAND_SET_VIDEO_SURFACE`, adds an API 34 `SurfaceSyncGroup` workaround on `onSurfaceSizeChanged`, and does **not** clear the surface when leaving composition with a non-null player | `media3-ui-compose: ui/compose/PlayerSurface.kt:60–186` |
| `ContentFrame(...)` exists (sizes the surface itself) | `ui/compose/ContentFrame.kt:51` |

### A.7 FFmpeg extension (local AAR, `javap`)

| Fact | Evidence |
|---|---|
| Classes: `FfmpegAudioRenderer`, `FfmpegAudioDecoder`, `FfmpegLibrary`, `FfmpegDecoderException`, `ExperimentalFfmpegVideoRenderer` | `unzip -l classes.jar` |
| `FfmpegLibrary`: `public static boolean isAvailable()`, `getVersion()`, `supportsFormat(String)`, `setLibraries(String...)` | `javap` |
| Codec-name mapping: `audio/mpeg`, `audio/mpeg-L1`, **`audio/mpeg-L2` → `"mp3"`**; `audio/mp4a-latm` → `"aac"`; `audio/ac3` → `"ac3"`; `audio/eac3(-joc)` → `"eac3"`; `audio/vnd.dts*` → `"dca"`; `audio/true-hd` → `"truehd"`; `video/avc`/`video/hevc` → `"h264"`/`"hevc"` | `javap -c FfmpegLibrary` constant pool order |
| `ExperimentalFfmpegVideoRenderer.supportsFormat` returns `RendererCapabilities.create(0)` (`FORMAT_UNSUPPORTED_TYPE`) and `createDecoder` returns null: inert | `javap -c` |
| Natives: `jni/arm64-v8a/libffmpegJNI.so`, `jni/armeabi-v7a/libffmpegJNI.so` only (no x86) | `unzip -l` |

### A.8 Test utilities

| Fact | Where |
|---|---|
| `TestExoPlayerBuilder(Context)` with `setClock`, `setLoadControl`, `setRenderersFactory`, `setMediaSourceFactory`, `setTrackSelector`, `setLooper`, `setStuckPlayingDetectionTimeoutMs`, `build()` | `media3-test-utils: test/utils/TestExoPlayerBuilder.java:71–471` |
| `FakeClock(long initialTimeMs, boolean isAutoAdvancing)`, `advanceTime(long)` | `test/utils/FakeClock.java:222, 262` |
| `TestPlayerRunHelper.runUntilPlaybackState`, `runUntilError(ExoPlayer)`, `runUntilRenderedFirstFrame(ExoPlayer)`, `playUntilPosition`, `advance(ExoPlayer)…` | `media3-test-utils-robolectric: test/utils/robolectric/TestPlayerRunHelper.java:760–1153` |
| `ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()/withCodecs(...)` (a JUnit 4 `ExternalResource`) | `test/utils/robolectric/ShadowMediaCodecConfig.java` |
| POM dependencies: test-utils → `mockito-core 3.12.4`, `truth`, `junit 4.13.2`, `mockwebserver 4.12.0`, `media3-transformer`, `media3-effect`, coroutines-test 1.9.0; robolectric module → `robolectric 4.16` | `media3-test-utils-1.11.1.pom`, `media3-test-utils-robolectric-1.11.1.pom` |

### A.9 Platform API levels (`api-versions.xml`, android-37.0)

| API | Since |
|---|---|
| `Surface.setFrameRate(float, int)` | 30 |
| `Surface.setFrameRate(float, int, int)`, `Surface.CHANGE_FRAME_RATE_ALWAYS`, `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` | **31** |
| `Surface.FRAME_RATE_COMPATIBILITY_DEFAULT`, `…_FIXED_SOURCE` | 30 |
| `WindowManager.LayoutParams.preferredDisplayModeId` | 23 |
| `Display.getMode()`, `getSupportedModes()` | 23 |
| `Display.getHdrCapabilities()` | 24 |

### A.10 UNVERIFIED (the reviewer should check)

1. FFmpeg's `mp3` decoder name decodes MPEG-1 Layer II frames (the mapping `audio/mpeg-L2 → "mp3"` is verified; decoding is not). I-13 on ARM hardware or an x86 debug AAR settles it.
2. `media3-test-utils(-robolectric)` 1.11.1 work with Robolectric 4.17, OkHttp 5.5.0 and our classpath, and `ShadowMediaCodecConfig` yields `onRenderedFirstFrame` for a TS stream (day-0 check 3).
3. API 30's two-argument `Surface.setFrameRate` behaviour for non-seamless switches (the design avoids relying on it).
4. ExoPlayer's exact behaviour on a backward or forward PTS jump inside one progressive TS load (frozen picture/stuck position is inferred from the renderers' monotonic positions and the audio sink's discontinuity handling, not run). D4 measures it.
5. OkHttp 5 closes the socket synchronously in `Call.cancel()` for a response body being read on another thread (OkHttp's documented behaviour; the design also closes the body first).
6. Whether H.264/H.265 field coding (interlaced) can be detected without a custom payload reader (§3.9.3).
7. Whether `ProcessLifecycleOwner`'s `ON_STOP` fires on Fire OS when the TV is switched off by CEC (D7/M6).
8. Whether the Android TV API 30 emulator image offers more than one display mode (I-22 logs it).

(reviewed 2026-10-13) Status of the items above after the review:
1. **Source-verified**, not executed: `libavcodec/mpegaudiodec_template.c` `mp_decode_frame` switches on the frame header's layer (`case 1/2/3`), and the `mp3` decoder (`mpegaudiodec_fixed.c:98–105`) uses that `decode_frame`; `mp2` is also in the build's decoder list (`build-ffmpeg-aar.sh:44`). I-13 on the x86 debug AAR confirms it at runtime.
2. Partly verified: dependency graph and Robolectric 4.17 shadows (`MediaCodecInfoBuilder`, `ShadowMediaCodec`, `ShadowMediaCodecList`) are fine; the TS first-frame run is still day-0 check 3, with the constraints of §5.4.
3. Unchanged (avoided by design).
4. **Verified in source** (§3.3.1 note): forward jumps ride through via `DefaultAudioSink`'s discontinuity re-base when audio is present; backward jumps overstate the buffered position. Behaviour on hardware is still D4.
5. **Verified** for HTTP/1.1 (`RealCall.cancel` → `RealConnection.cancel` → `rawSocket.closeQuietly()`); HTTP/2 sends `RST_STREAM`; DNS lookups are not interruptible (§3.5.2).
6. **Resolved** for H.264 (§3.9.3).
7. Partly answered: on AOSP sleep stops the Activity, so `ON_STOP` fires on standby (B4). Fire OS CEC TV-off is still D7.
8. Unchanged.

### A.11 Cross-check with `docs/design/m2-media3-api-notes.md`

The Sonnet fact sheet (written in parallel) was compared item by item for every class used here. Agreements: OkHttp header/Range construction and the duplicate `User-Agent`/`Range` hazard of `addHeader`; `configureRetry`'s three branches and the Content-Length hazard; `TsExtractor`'s duration reader only with a known length; `DefaultLoadErrorHandlingPolicy` retrying `InvalidResponseCodeException`; `LoadControl` method set (and that the `Renderer[]` overloads are dead); stuck-player defaults; `setVideoChangeFrameRateStrategy` Javadoc on `CHANGE_FRAME_RATE_ALWAYS`; `LatmReader` → `AUDIO_AAC`; FFmpeg MIME → codec-name mapping and the open MP2 question; test-utils dependencies and the Robolectric 4.16 pin; `ShadowMediaCodecConfig` is a JUnit 4 rule; `ForwardingSimpleBasePlayer` is the recommended way to hide session commands (adopted in §3.10.4).

Differences and how this design resolves them:
- **`PlayerSurface` vs "one SurfaceView at the root".** The fact sheet says `PlayerSurface` creates its own `SurfaceView` in an `AndroidView`, "not a root-level SurfaceView behind Compose", and suggests calling `player.setVideoSurfaceView(rootSurfaceView)` directly if the rule stands. This design keeps `PlayerSurface`: the architecture review's B1.2 tree places exactly `PlayerSurface(player, SURFACE_TYPE_SURFACE_VIEW)` as the first child of the root `Box`, which is what "at the root, behind Compose" means here (the `SurfaceView` window layer sits behind the app window regardless of where the `AndroidView` is in the tree; what matters is that nothing above it in the tree applies `graphicsLayer`/clip/alpha). Using `PlayerSurface` keeps the API 34 resize workaround (D17).
- **`ExperimentalFfmpegVideoRenderer`.** The fact sheet verified only that it is loaded by reflection; `javap` here shows `supportsFormat` always returns `FORMAT_UNSUPPORTED_TYPE` and `createDecoder` returns null, so it is inert (§3.8.1).
- **`TestExoPlayerBuilder.build()`** sets `ExoPlayer.Builder.experimentalEnableStuckPlayingDetection = true` globally and defaults dynamic scheduling to false (fact sheet §18). Not in conflict; WP-C's real-player tests must set `setStuckPlayingDetectionTimeoutMs` explicitly when they expect long stalls.
- The fact sheet records that `media3-ui-compose` depends on `media3-common-ktx`, which is not in the Gradle cache yet; the first build after commit 0 downloads it (Google Maven).

### A.12 Reviewer-verified facts (reviewed 2026-10-13)

| Fact | Where |
|---|---|
| Unknown length + unknown duration: a prepared period defers the retry and restarts from position 0; an unprepared one retries from 0 at once; seeks into an unseekable map go to 0. So `open()` is only ever asked for position 0 when `LENGTH_UNSET` is returned | `ProgressiveMediaPeriod.java:589–590, 727–741, 1093–1124` |
| `onLoadError` builds `MediaLoadData(C.DATA_TYPE_MEDIA, …)` for every progressive error and `C.TIME_UNSET` from the policy → `Loader.DONT_RETRY_FATAL`; `Loader` keeps the same exception instance as `fatalError` | `ProgressiveMediaPeriod.java:842–895`, `Loader.java:532` |
| After prepare, loader errors are thrown only from `maybeThrowRendererStreamError`, i.e. when a renderer cannot play | `ExoPlayerImplInternal.java:1502, 1511, 1557` |
| `DataSourceException` → `PlaybackException.errorCode = reason`, cause = the exception itself; runtime exceptions from `load()` become `UnexpectedLoaderException` | `ExoPlayerImplInternal.java:992–993, 1016–1025`, `Loader.java:470–482` |
| `TsExtractor.sniff` needs 5 sync bytes at 188-byte spacing (940 bytes) at one of the first 188 offsets | `TsExtractor.java:355–373` |
| `TsExtractor` CC handling: equal CC → packet dropped as duplicate; any other non-+1 CC → `payloadReader.seek()`; CCs are checked on payload packets only | `TsExtractor.java:487–505` |
| `ProgressiveMediaPeriod` finishes preparing only when every sample queue has an upstream format | `ProgressiveMediaPeriod.java:978–986` |
| `DefaultAudioSink` re-bases on a > 200 ms timestamp discontinuity and reports `onPositionDiscontinuity`; the audio renderer accepts the position jump | `DefaultAudioSink.java:1034–1062`, `MediaCodecAudioRenderer.java:1220–1226` |
| `SampleQueue.largestQueuedTimestampUs` is a running maximum | `SampleQueue.java:877` |
| `LoadControl` `PlayerId`/`Parameters` overloads are `default` and forward to deprecated overloads that throw; `getAllocator(PlayerId)` is abstract; back-buffer values are read once per player | `LoadControl.java:148–427`, `ExoPlayerImplInternal.java:330–331` |
| `DefaultLoadControl` streaming defaults 50,000/50,000/1,000/2,000 ms; local-playback variants apply only to `file`/`asset`-like schemes (our `thebox-session` URI counts as streaming) | `DefaultLoadControl.java:64–109, 952–961` |
| HLS segment errors carry `loadable.type` as `dataType`; preload-hint 404/410 is retried without the policy; HLS playlist errors use `DATA_TYPE_MANIFEST`; a fatal playlist refresh error surfaces through `maybeThrowPlaylistRefreshError`; `PlaylistStuckException` after 3.5 × target duration, `PlaylistResetException` when the media sequence goes back | `HlsSampleStreamWrapper.java:1049–1112`, `DefaultHlsPlaylistTracker.java:77, 1049–1110, 1165–1185`, `HlsPlaylistTracker.java:100–116` |
| Track mapping gives a group to the renderer with the highest format-support level; ties go to the earlier renderer (platform before FFmpeg in `ON` mode) | `MappingTrackSelector.java:505–524`, `DefaultRenderersFactory.java:556–581, 687–` |
| `FfmpegAudioRenderer.supportsFormatInternal`: HANDLED only when `FfmpegLibrary.supportsFormat(mime)` and the sink takes 16-bit or float PCM | `decoder_ffmpeg/FfmpegAudioRenderer.java:133–146` (repo `tools/ffmpeg/work`) |
| `VideoFrameReleaseHelper` never calls `Surface.setFrameRate` or clears it while the strategy is `OFF` | `VideoFrameReleaseHelper.java:308–313, 335–341` |
| `StuckPlayerDetector` buffering check uses the buffered position; playing check uses the position; both need `playWhenReady` and no suppression | `common/util/StuckPlayerDetector.java:159–262` |
| `PlayerSurface` attaches via `LaunchedEffect(view, player)` after `isCommandAvailable(COMMAND_SET_VIDEO_SURFACE)`; clears only when the player becomes null; API 34 workaround on `onSurfaceSizeChanged` | `media3-ui-compose/PlayerSurface.kt:60–186` |
| `DefaultExtractorsFactory` transcodes TS subtitles to `application/x-media3-cues` by default; `TextRenderer` rejects legacy subtitle MIME types unless legacy decoding is enabled (CEA-608/708 excepted) | `DefaultExtractorsFactory.java:166, 582–593`, `TextRenderer.java:671–679` |
| `NalUnitUtil.SpsData.frameMbsOnlyFlag` is public; `H264Reader` puts the SPS in `initializationData[0]` | `container/NalUnitUtil.java:167, 808, 882`, `H264Reader.java:222–225` |
| Platform API: `Surface.clearFrameRate()` 34; `DisplayManager.getMatchContentFrameRateUserPreference()` and `MATCH_CONTENT_FRAMERATE_*` 31 | `platforms/android-37.0/data/api-versions.xml` |
| OkHttp 5.5.0: `Call.addEventListener` exists; `callEnd`/`callFailed` fire after the socket is closed; closing a chunked/fixed-length body first drains for up to 100 ms | `Call.kt:92–102`, `RealCall.kt:408–437`, `Http1ExchangeCodec.kt:460–467, 530–536` |

---

## Appendix B. Named constants

Owner = the WP whose file defines the constant (`:core` constants in a `PlaybackConstants` object per package; `:player` ones in their component's companion).

| Constant | Value | Owner | Section |
|---|---|---|---|
| `DEFAULT_MAX_CONNECTIONS` | 1 | A | §3.5.1 |
| `MAX_CONNECTIONS_CAP` | 50 | A | §3.5.1 |
| `PREEMPT_RELEASE_WAIT_MS` | 300 | A | §3.5.2 |
| `ZAP_DEBOUNCE_MS` | 350 | A | §3.5.4 |
| `ZAP_IMPORT_HOLD_MAX_MS` | 3,000 | D | §3.5.5 |
| `ZAP_IMPORT_RELEASE_AFTER_FIRST_FRAME_MS` | 500 | D | §3.5.5 |
| `IMPORT_GATE_MAX_WAIT_MS` | 3,000 | D | §3.5.5 |
| `RE_RESOLVE_MAX_PER_WINDOW` / `RE_RESOLVE_WINDOW_MS` | 2 / 60,000 | A | §4.5 |
| `ACCOUNT_REFRESH_MIN_INTERVAL_MS` | 10,000 | D (`:app` provider) | §2.4 |
| `ZAP_LIMIT_WINDOW_MS` | 5,000 | A | §4.3 L2 |
| `LIMIT_RETRY_DELAY_MS` | 1,500 | A | §4.3 L2 |
| `LIMIT_RERESOLVE_AT_MS` | 3,000 | A | §4.3 L3 |
| `CONTROLLER_RETRY_DELAYS_MS` | 1,000, 2,000, 4,000 (then 4,000) | A | §4.5 |
| `CONTROLLER_RETRY_JITTER` | 0.2 | A | §4.3 |
| `UNLIMITED_RETRY_INTERVAL_MS` | 5,000 | A | §4.3 L19 |
| `LADDER_HEALTHY_RESET_MS` | 30,000 | A | §4.3 |
| `PCR_BEHIND_LIVE_MAX_PER_MIN` | 3 | A | §4.3 L25/L26 |
| `HLS_SEGMENT_404_RETRIES` / `HLS_SEGMENT_404_DELAY_MS` | 2 / 500 | A (rules), B (policy) | §4.2 |
| `LOAD_RETRY_BASE_DELAYS_MS` / `LOAD_RETRY_JITTER` / `LOAD_RETRY_MAX` | 500, 1,000, 2,000 / 0.2 / 3 | A | §4.2 |
| `LIVE_CONNECT_TIMEOUT_MS` | 5,000 | B | §3.3.6 |
| `LIVE_READ_TIMEOUT_MS` | 8,000 | B | §3.3.6 |
| `FIRST_BYTE_TIMEOUT_MS` / `RECONNECT_FIRST_BYTE_TIMEOUT_MS` | 8,000 / 4,000 | B | §3.3.6 |
| `NO_BYTES_TIMEOUT_MS` | 4,000 | B | §3.3.6 |
| `WATCHDOG_TICK_MS` | 500 | B | §3.3.6 |
| `RECONNECT_MAX_ATTEMPTS` / `RECONNECT_WINDOW_MS` | 3 / 3,000 | B | §3.3.6 |
| `RECONNECT_DELAYS_MS` | 0, 500, 1,000 | B | §3.3.6 |
| `RECONNECT_LIMIT_STATUS_DELAY_MS` | 1,000 | B | §3.3.6 |
| `RECONNECT_BUDGET_RESET_MS` | 30,000 | B | §3.3.6 |
| `SNIFF_MAX_BYTES` / `TS_SYNC_RUN` / `TS_MIN_SNIFF_BYTES` | 2,048 / 3 / 565 | B | §3.3.3 |
| `ALIGN_RING_PACKETS` / `READ_CHUNK_BYTES` | 64 / 16,384 | B | §3.3.4 |
| `PCR_JUMP_REPREPARE_MS` | 2,000 | B | §3.3.5 |
| `PCR_HOLD_MAX_BYTES` / `PCR_HOLD_MAX_MS` (reviewed 2026-10-13, S1) | 262,144 / 500 | B | §3.3.4 |
| `DEAD_TRACK_GRACE_BYTES` (reviewed 2026-10-13, S5) | 1,048,576 | C | §3.8.5 |
| `BITRATE_WINDOW_MS` | 2,000 | B | §3.13 |
| Buffer profiles | table §3.6.1 | A | §3.6.1 |
| `LOW_RAM_TARGET_BYTES` / `NORMAL_TARGET_BYTES` | 24 MiB / 48 MiB | A | §3.6.1 |
| `LOW_RAM_MEMORY_CLASS_MB` / `LOW_RAM_TOTAL_MEM_BYTES` | 192 / 2 GiB | C | §3.6.1 |
| `REBUFFER_WINDOW_MS` / `REBUFFERS_TO_ESCALATE` / `DECAY_STABLE_MS` | 600,000 / 2 / 600,000 | A | §3.6.2 |
| ~~`ARCHIVE_BACK_BUFFER_MS`~~ | removed (reviewed 2026-10-13, S4: back buffer is read once per player) | — | §3.6.1 |
| `WATCHDOG_INTERVAL_MS` | 1,000 | C | §3.7 |
| `SEEK_GRACE_MS` | 2,000 | A | §3.7 |
| `STALL_COOLDOWN_MS` | 15,000 | A | §3.7 |
| `HLS_NO_PROGRESS_MS` | 8,000 | A | §3.7 a |
| `DECODER_STALL_BUFFERING_MS` / `DECODER_STALL_MIN_BYTES` | 6,000 / 262,144 | A | §3.7 b1 |
| `DECODER_STALL_MAX_MEDIA_GROWTH_MS` (reviewed 2026-10-13, B6) | 500 | A | §3.7 b1 |
| `DECODER_STALL_MIN_BYTES_UNKNOWN_RATE` (code review 2026-10-14, N-S4) | 2,097,152 | A | §3.7 b1, before the stream rate is learned |
| `STREAM_RATE_LEARN_MEDIA_MS` (code review 2026-10-14, N-S4) | 2,000 | A | §3.7 b1, buffered-position growth per rate sample |
| `READINESS_STALL_MS` (2026-10-14) | 5,000 | A | §3.7 b1r, continuous BUFFERING |
| `READINESS_SEEK_GRACE_MS` (2026-10-14) | 6,000 | A | §3.7 b1r, after a seek |
| `READINESS_NOT_LOADING_MIN_BUFFER_MS` (2026-10-14) | 2,000 | A | §3.7 b1r, "media available" when the loader stopped |
| `READINESS_MIN_BUFFER_MS` (2026-10-14) | 1,000 | A | §3.7 b1r, floor of the profile threshold (None starts at 500 ms) |
| `COMPAT_PERSIST_HEALTHY_MS` (2026-10-14) | 30,000 | D | §4.3 L27b, healthy playback before a compat raise is saved |
| `POSITION_STUCK_MS` | 4,000 | A | §3.7 b2 |
| `FROZEN_VIDEO_MS` / `FROZEN_MIN_AUDIO_ADVANCE_MS` | 3,000 / 2,500 | A | §3.7 c |
| `AUDIO_START_TIMEOUT_MS` / `AUDIO_START_TIMEOUT_PASSTHROUGH_MS` (reviewed 2026-10-13, S11) | 3,000 / 5,000 | A | §3.7 d1 |
| `AUDIO_UNDERRUN_STORM_COUNT` / window | 5 / 3,000 ms | A | §3.7 d2 |
| `COMPAT_ESCALATE_WINDOW_MS` | 300,000 | A | §3.7 |
| `STUCK_BUFFERING_BACKSTOP_MS` | 30,000 | C | §3.7 |
| `AFR_DWELL_MS` | 2,000 | C | §3.9.2 |
| `AFR_SWITCH_TIMEOUT_MS` | 3,000 | C | §3.9.2 |
| `AFR_REVERT_IDLE_MS` | 30,000 | C | §3.9.2 |
| `FPS_ESTIMATE_FRAMES` / `FPS_SNAP_TOLERANCE` / max delta | 30 / 0.02 / 200 ms | A (snap), C (estimator) | §3.9.3 |
| `REFRESH_MATCH_TOLERANCE` | 0.001 | A | §3.9.2 |
| `STANDBY_GRACE_MS` | 30,000 | D | §3.10.2 |
| `LIVE_PAUSE_RELEASE_MS` | 10,000 | D | §3.1.2 |
| ~~`IDLE_RELEASE_PLAYER_MS`~~ | removed (reviewed 2026-10-13, B3: no idle release) | — | §3.10.1 |
| `RESUME_REAUTH_AFTER_MS` | 900,000 | D | §3.10.2 |
| `RECORD_WATCHED_AFTER_MS` | 5,000 | D | §3.1.1 |
| `PREVIOUS_CHANNEL_MIN_PLAY_MS` | 1,000 | D | §3.1.1 |
| `RELEASE_TIMEOUT_MS` | 1,000 | C | §6.3 |
| `STATS_INTERVAL_MS` | 500 | C | §3.13 |
| `EVENT_LOG_CAPACITY` / `ZAP_HISTORY` / `EVENT_DETAIL_MAX_CHARS` | 200 / 50 / 160 | A | §3.13 |
| `STREAM_SWITCH_TIMEOUT_MS` (setting default) | 15,000 | settings | §2.4 |
| `RECONNECT_ATTEMPTS` (setting default) | 5 | settings | §2.4 |
