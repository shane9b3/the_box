# Keep decoders across zaps (performance H1): design

Status: **proposed 2026-11-11** (proposer). Waiting for the adversarial review. Nothing is implemented.

Owner decision (2026-11-11): "Build it, switched off". Decoders are kept across zaps behind a player-internal flag that ships **OFF**. The flag is turned on only after measurement on a real Fire TV Stick. So OFF must behave exactly as today, and ON must be safe enough to trial.

Inputs:
- `docs/review/2026-11-09-whole-code-review/performance.md:30` (H1) and `:90` (the reviewer's sketch).
- `docs/review/m2-design-review.md:277` (N1, the first time this idea came up).
- `docs/design/m2-playback-core.md:1361` (zap step 2) and `:1861` ("no idle release in v1", which assumed `stop()` resets the renderers).
- `docs/design/m4-player-ux.md:40, 1322, 1933` (last frame after `stop()`, sticky video size).
- `docs/design/m6-timeshift.md` §6 step 3 (ring reader re-prepare with no `stop()`).
- The code at `c0f0b3f`.
- **Media3 1.11.1** (`gradle/libs.versions.toml:22`). The sources jar is `~/.gradle/caches/modules-2/files-2.1/androidx.media3/media3-exoplayer/1.11.1/785e2f15…/media3-exoplayer-1.11.1-sources.jar`, unpacked and read for this document. Media3 paths below are relative to `androidx/media3/exoplayer/` in that jar.

Line numbers for our code are at `c0f0b3f`. H1 cites `PlaybackControllerImpl.kt:1089-1097`, but `endSession` is now at `:1101-1109`.

---

## 0. Summary

- **Mechanism:** `ExoPlayer.setForegroundMode(true)` is armed for each qualifying zap, before the `player.stop()` that `endSession` already makes. It is disarmed (`setForegroundMode(false)`) at the new channel's first media, and before every other kind of stop.
  - Every call site, the transport order and the IDLE gap between channels stay as they are. The only difference is that `stop()` no longer resets the renderers during a zap.
  - Foreground mode is never on while the player sits stopped for any reason other than a zap in flight. That bounds the idle memory without timers.
- **What it keeps:**
  - The video `MediaCodec`, when the next channel has the same MIME type, the same colour info, a resolution that fits, and the same tunnelling mode.
  - The platform audio `MediaCodec`, when the audio format matches.
  - It **never** keeps the `AudioTrack` (released on every flush) or the FFmpeg audio decoder (released on disable). The expected gain is therefore the video codec init (and sometimes the audio codec init), not the 100–300 ms H1 estimated. Measurement decides.
- **What forces a full reset when ON:**
  - every ladder restart;
  - every user or lifecycle stop and every error card;
  - a change in the decoder policy between channels (decoder mode, tunnelling, audio policy);
  - tunnelling turned on (not part of the trial);
  - a decoder failure in a session that started on kept decoders. That one gets one extra full-reset re-prepare before the ladder sees it, and two such failures switch the feature off for the process.
- **OFF:** the controller never calls `setForegroundMode` and logs no new events. The existing controller tests run unchanged, and a new test pins the absence of the call.
- **Pre-existing defect found on the way (§9):** with pause and rewind on, the ladder's decoder repairs (software video, plain re-prepare after `DECODER_STUCK`) re-prepare the ring reader without `stop()`. Media3 then **reuses the same codec**, so the repair can be a no-op. This is independent of the flag; it is not fixed here (OFF must stay unchanged) and goes to the lead (Q1).

---

## 1. Facts: our code

| # | Fact | Where |
|---|---|---|
| C1 | `endSession(stopPlayer = true)`: clears cues, sets `session = null`, stops the watchdog, ends the transport (`s.end()` → `transport.endSession`, the synchronous `Call.cancel()`), **then** `player?.stop()`. It returns early when there is no session. | `PlaybackControllerImpl.kt:1101-1109`; `TuneSession.kt` `end()` |
| C2 | Every new session goes through `launchSession`, which calls `endSession(stopPlayer = plan.reader == null \|\| c.ts == null)`. Ring reader re-prepares skip `stop()`. | `PlaybackControllerImpl.kt:753-760` |
| C3 | Callers of `launchSession`: `startCycle` (tune, zap, previous, retry, resume after lifecycle, backup source, ring relaunch); `restart` (every ladder decision); `reapplySettings`; `selectServer`. | `:718`, `:1793`, `:608`, `:648` |
| C4 | Other `endSession()` callers, all with a stop: `fail()`, `stop(reason)` (user, lifecycle, external hand-off), the live-pause connection release. | `:1821`, `:1942`, `:1885` |
| C5 | Between `endSession` and the new `setMediaSource`/`prepare`, `runSession` does IO: facts, resolve, account refresh, time-shift storage. The player is IDLE for tens to hundreds of ms. | `:777-915` |
| C6 | Per-channel decoder facts are only known **after** `endSession`: `applyFacts` (memory: `tunnelingOff`, `videoDecoder`, `tsCompatLevel`) runs in `runSession`, then `applySwitches` writes the codec policy, the audio policy and tunnelling at `:857`. `setMediaSource` + `prepare` follow at `:914-915`. | `:970-980`, `:1056-1088` |
| C7 | The codec policy is read by `BoxMediaCodecSelector.getDecoderInfos`. Its KDoc says the switches apply "at the next track selection or codec init (the ladder always re-prepares)". A reused codec never calls the selector again (M4 below). | `engine/BoxMediaCodecSelector.kt` (class KDoc, `getDecoderInfos`) |
| C8 | These ladder steps rely on getting a new decoder: `SoftwareVideoAndReprepare`, `DisableTunnelingAndReprepare`, `SoftwareAudioAndReprepare`, `DisablePassthroughAndReprepare`, `Reprepare` after `DECODER_STUCK`/`FROZEN_VIDEO`, `StereoAndReprepare`. All of them go through `restart` → `launchSession`. | `:1682-1736`, `:1771-1793` |
| C9 | `onPlayerFailure` and `onPlayerState` return at once when `session == null`, so a player error raised between sessions is ignored. | `:1237-1239`, `:1293-1295` |
| C10 | First media is `onRenderedFirstFrame` (video) or `onAudioPositionAdvancing` (no video). `onFirstMedia` writes the zap record. | `:1201-1205`, `:1220-1224`, `:1384-1403` |
| C11 | `isDecoderTypeSignal` = `DECODER_STUCK`, `FROZEN_VIDEO`, container parsing, `DecodingFailed`. | `:3234-3239` |
| C12 | One player per process, never released. `PlayerFactory` sets `setReleaseTimeoutMs(1_000)` and never calls `setForegroundMode`. | `engine/PlayerFactory.kt:85,107`; `docs/design/m2-playback-core.md:1861` |
| C13 | Renderers: `DefaultRenderersFactory` with decoder fallback. Extension mode ON adds `FfmpegAudioRenderer` (a `DecoderAudioRenderer`). The sink is `PassthroughGateAudioSink(DefaultAudioSink)`. | `engine/BoxRenderersFactory.kt:36-61` |
| C14 | Tunnelling is the per-tune setting `s.tunneling && !c.tunnelingOff`. The default setting is off. | `:1070`; `api/PlaybackProviders.kt:72`; `core/.../settings/SettingsStore.kt:143` |
| C15 | Viewport HIDDEN/NONE and HDR-poster MINI disable the video track; audio continues. | `:2039-2048`; `docs/design/m2-playback-core.md:1907` |
| C16 | Home: `onProcessStop` while interactive → `stopForLifecycle` → `stop(BACKGROUND)`. Standby, display off and HDMI unplug do the same after the grace. `LifecycleCoordinator.isForeground` is the flag. | `controller/LifecycleCoordinator.kt:73,103-118`; `PlaybackControllerImpl.kt:1978-1984` |
| C17 | The external-player hand-off stops our stream first (`stop(USER)`). The other app then needs the hardware decoder. | `:616-621` |
| C18 | The watchdog's readiness-stall baseline restarts when `videoDecoderInits` changes. It needs first media in the session. Nothing requires a decoder init per session. | `engine/PlaybackWatchdog.kt:245,319-326`; `core/.../stall/StallDetectors.kt:241-258` |
| C19 | Debug-only knobs: an `internal object` in `main` (`TsDebugOverrides`) is written only by the debug source set (`PlayerDebugHooks`). The debug receiver sets it from `thebox-debug://guide?…` (`capture=`). | `engine/TsExtractorsFactory.kt:409-413`; `player/src/debug/.../PlayerDebugHooks.kt`; `app/src/debug/.../GuideDebugReceiver.kt:196` |
| C20 | Test seams: `FakeExoPlayer` is a `Proxy` that journals `setMediaSource`, `prepare` and `player.stop`; anything it does not know returns a default. `ControllerFixture` sets `ioDispatcher` as a property after construction. `RealEngineControllerTest` drives the real `PlayerFactory` under Robolectric with `ShadowMediaCodecConfig`/`IdlingMediaCodecAdapterFactory`. | `test/.../controller/fakes/FakeExoPlayer.kt:44-75`; `fakes/ControllerFixture.kt:59-62`; `controller/RealEngineControllerTest.kt`; `engine/EngineTestSupport.kt:57` |
| C21 | Zap harness: `ZapRecord(resolveMs, leaseMs, firstByteMs, readyMs, firstFrameMs, outcome)`. I-17 `ZapThroughGuideTest` logs P50/P90 and takes `-e timeshift`. Plan gate: zap P50 ≤ 1.2 s, P90 ≤ 2.0 s, no black frame (Fire TV Stick 4K). | `core/.../playback/PlaybackLogic.kt:317-327`; `app/src/androidTest/.../guide/ZapThroughGuideTest.kt`; `docs/00-master-plan.md:103` |
| C22 | Zap timing notes: measure with fake panel `reconnect_mode: reset`. With `continue`, channels resume mid-GOP and whichever leg runs second looks about 1.3 s slower (resolved 2026-10-24, `094f3a4`). | project notes; `tools/fakepanel/README.md:100` |
| C23 | Fake panel media: H.264 720p25 + AAC (most channels), MP2-audio variants (3, 12), 50 fps (4), an HDR10-signalled variant, AAC-only radio (13), and `media_override: ac3`. There is **no 1080p or HEVC clip**, and **every channel shows the same picture**. | `tools/fakepanel/README.md:15-36,224` |
| C24 | No DRM anywhere in `player/`, so no secure decoders. | `grep -ri drm player/src/main` (empty) |

## 2. Facts: Media3 1.11.1

| # | Fact | Where |
|---|---|---|
| M1 | `stop()` → `stopInternal(forceResetRenderers=false)` → `resetInternal(resetRenderers = forceResetRenderers \|\| !foregroundMode, …)`. `resetInternal` always **disables** the renderers. It **resets** them only when `resetRenderers` is true. | `ExoPlayerImpl.java:1122-1126,2161-2171`; `ExoPlayerImplInternal.java:2041-2050,2072-2104` |
| M2 | `setForegroundMode(true)` only posts a message (non-blocking). `setForegroundMode(false)` posts and **blocks the app thread** until it is handled, for up to `setForegroundModeTimeoutMs`, which equals `releaseTimeoutMs` (ours: 1,000 ms). On a timeout, `ExoPlayerImpl` stops with `ERROR_CODE_TIMEOUT`. `ExoPlayerImpl` ignores a call that does not change the mode. | `ExoPlayerImplInternal.java:612-626,316`; `ExoPlayerImpl.java:1107-1119,393` |
| M3 | Handling foreground → false calls `RendererHolder.reset()` on every renderer. That resets only renderers that are **not enabled**: an enabled, playing renderer is left alone. `BaseRenderer.reset()` requires `STATE_DISABLED`. | `ExoPlayerImplInternal.java:2011-2023`; `RendererHolder.java:755-774`; `BaseRenderer.java:267-271` |
| M4 | `MediaCodecRenderer.onDisabled` → `flushOrReleaseCodec()` (flush and keep, unless a known flush workaround forces a release). `onReset` → `releaseCodec()`. `maybeInitCodecOrBypass` returns at once when a codec exists, so the `MediaCodecSelector` is consulted only when a codec is created. `releaseCodec` clears `availableCodecInfos`. | `mediacodec/MediaCodecRenderer.java:855-874,890-907,600-604,1041-1077,1154-1172` |
| M5 | When the next format arrives with a codec present, `onInputFormatChanged` calls `canReuseCodec(codecInfo, oldFormat, newFormat)`. `REUSE_RESULT_NO` → `drainAndReinitializeCodec`; after a flush, `codecReceivedBuffers` is false, so it re-initialises at once (release then init; the selector runs again). The YES results keep the codec (flush, in-band reconfiguration or nothing). | `MediaCodecRenderer.java:1800-1925,2229-2240,2628-2631,1130` |
| M6 | Video reuse (`MediaCodecInfo.canReuseCodec` + `MediaCodecVideoRenderer.canReuseCodec`) is **NO** on: a MIME change; a rotation change; a resolution change on a non-adaptive codec; a colour-info change (SDR ↔ HDR10/HLG); a Dolby Vision profile change; width/height above `codecMaxValues` (for a single-format live TS this is the **first** format's size, so 720p → 1080p re-initialises and 1080p → 720p reuses); max input size exceeded; a frame-rate change with AFR SEAMLESS, only on API ≥ 30 and not MiTV. Different SPS/PPS gives `YES_WITH_RECONFIGURATION` (in-band CSD). | `mediacodec/MediaCodecInfo.java:466-530`; `video/MediaCodecVideoRenderer.java:1413-1439,2720-2742,2979-3010` |
| M7 | Audio reuse is NO on: a MIME change; channel count; sample rate; PCM encoding; a CSD change (except xHE-AAC, AC-4, E-AC-3); a flush workaround; "bypass now possible" (passthrough became allowed); max input size. | `MediaCodecInfo.java:531-600`; `audio/MediaCodecAudioRenderer.java:524-546` |
| M8 | Tunnelling: `MediaCodecVideoRenderer.onEnabled` releases the codec when the renderer's tunnelling mode changed. A change of tunnelling audio session id also releases it. | `video/MediaCodecVideoRenderer.java:954-962,1268-1272` |
| M9 | **`AudioTrack` never survives.** `MediaCodecAudioRenderer.onDisabled` → `audioSink.flush()`, and `DefaultAudioSink.flush()` releases the audio output on every flush ("known AudioTrack flush issues"; release runs on a background thread). | `audio/MediaCodecAudioRenderer.java:792-805`; `audio/DefaultAudioSink.java:1588-1609`; `audio/AudioTrackAudioOutput.java:309-320,480-515` |
| M10 | **The FFmpeg audio decoder never survives.** `DecoderAudioRenderer.onDisabled` → `releaseDecoder()` and `audioSink.reset()`. | `audio/DecoderAudioRenderer.java:751-765` |
| M11 | Renderer errors already force a reset: an `ExoPlaybackException` of `TYPE_RENDERER` that is not recoverable, and any `RuntimeException`, call `stopInternal(forceResetRenderers = true)`, so the codec is released even in foreground mode. Source IO errors call `stopInternal(false)`, so in foreground mode they leave the renderers disabled but **not reset**. | `ExoPlayerImplInternal.java:965-971,1001-1008,1016-1025` |
| M12 | `enableRenderers` resets every renderer that the new track selection does not enable, before enabling the others. So a disabled video track (HIDDEN, poster, radio) or a move from MediaCodec audio to FFmpeg audio releases the old codec, whatever the foreground mode. | `ExoPlayerImplInternal.java:3662-3672` (call sites `:1869,2478,3459`) |
| M13 | `setMediaSource` on a non-IDLE player (no `stop()`) changes the playing period → `seekToPeriodPosition` → `disableRenderers()` with **no** reset → the codec is reused by M5. This is what the time-shift reader re-prepare already relies on. | `ExoPlayerImplInternal.java:1076-1091,1832-1871` |
| M14 | Video `onDisabled` clears `reportedVideoSize` and `haveReportedFirstFrameRenderedForCurrentSurface`, so a reused codec still fires `onRenderedFirstFrame` and a fresh size for the next channel. `onEnabled` re-arms the first-frame release instruction. | `video/MediaCodecVideoRenderer.java:1202-1216,975-993` |
| M15 | The Javadoc requires the app to be in the foreground, the same player to be reused, and gaps between playbacks (`stop` … `prepare`). The app must turn the mode off when those conditions stop holding. | `ExoPlayer.java:1934-1963` |
| M16 | `AnalyticsListener` reports `onVideoInputFormatChanged(…, DecoderReuseEvaluation)`, `onVideoDecoderInitialized(…, initializationDurationMs)`, `onVideoDecoderReleased`, and the audio versions. | `analytics/AnalyticsListener.java:1007-1067,1210-1258` |

## 3. Mechanism options

| Option | Video codec | Audio codec | AudioTrack | FFmpeg audio | Between channels | Verdict |
|---|---|---|---|---|---|---|
| **Today**: `stop()`, foreground off | released (M1 → M4 `onReset`) | released | released (M9) | released | IDLE; last frame expected to stay (never `clearVideoSurface`, C1). Unverified on SoCs (m4 §1933) | baseline |
| **(a) `setForegroundMode(true)` + `stop()`** | kept, flushed (M1, M4); reused if M6 allows | kept; reused if M7 allows | released (M9) | released (M10) | IDLE, as today. The surface's producer stays connected and the codec is only flushed, so the last displayed buffer should stay (expectation, unverified) | **recommended**, armed per zap (§4) |
| **(b) no `stop()`; `setMediaSource` + `prepare` straight away** | kept (M13) | kept | released (flush) | released | The old channel **keeps playing** its buffered seconds during the resolve gap (C5). A source IO error from the cancelled transport arrives as `stopInternal(false)`, which with foreground mode off **resets** the renderers (M1, M11), so whether the codec is kept depends on a race. | Rejected for zaps. Kept as it is for ring reader re-prepares (C2), where there is no gap |
| **(b′) pause, then `setMediaSource` + `prepare`** | kept, unless the race above | kept | released | released | Frozen frame; old audio paused | Rejected: same race; needs a new player state between sessions (cues, watchdog and pause state assume IDLE) |
| **(c) playlist replace / `ConcatenatingMediaSource2`** | as (b) | as (b) | released | released | as (b) | Rejected: the same thing as (b); also conflicts with one `MediaSource` per lease (m4 §0 item 4) |
| **(d) secondary-renderer prewarming / `PreloadManager`** | needs a **second** codec instance and the next channel known | — | — | — | — | Rejected: it opens a second upstream connection (breaks `max_connections = 1`), costs memory on 1 GB, and a zap target is unknown until the key |

What none of the options can keep: the `AudioTrack` (M9) and the FFmpeg decoder (M10). Media3 marks the `AudioTrack` release as a TODO experiment (`DefaultAudioSink.java:1598`); changing it is not in scope.

### 3.1 Why "arm per zap" rather than "on while in the foreground"

Option (a) can be used in two ways.

**Always on in the foreground.** This holds codecs whenever the player is stopped: error cards, user stop, live-pause release. It then needs idle timers, `onTrimMemory` and Settings hooks to give them back. A source IO error during normal playback (M11) would also leave idle codecs that the next ladder restart must free with the **blocking** `setForegroundMode(false)` (M2), which waits for a codec release.

**Armed per zap.** Foreground mode is on only from the zap's `stop()` until the new channel's first media.
- At first media the renderers are enabled, so turning it off releases nothing (M3). It is a single message round trip to the playback thread.
- Before any other stop it is turned off first, then `stop()` resets as today.

The idle hold is therefore bounded by the zap itself: at most the stream-switch window (`streamSwitchTimeoutMs`, 15 s, `api/PlaybackProviders.kt:83`), while the player is trying to play the next channel and would need a decoder anyway. No timers are needed.

## 4. Design (ON path)

### 4.1 New pieces

**`controller/DecoderKeeper.kt` (new, main thread only).** It owns all of the state and is the only code that calls `setForegroundMode`.
- `armed: Boolean`: true between a qualifying zap's `stop()` and the disarm.
- `trusted: Boolean`: the current codecs are not suspect. It becomes true at first media. It becomes false on any `onPlayerFailure`, `onStall`, `onFailure` or `fail`, and when the keeper resets.
- `keptSessionTuneId: Long`: the first session of a cycle that started on kept decoders (−1 otherwise).
- `lastKey: DecoderPipelineKey?`: the policy the current codecs were created under, recorded at every `prepare`.
- `fallbacks: Int`; `disabledForProcess: Boolean`.

**`DecoderPipelineKey`.** A data class built from the values `applySwitches` writes:
- `codecPolicy.videoMode` (effective; it includes `c.softwareVideo` and the channel override);
- `trackSelector.parameters.tunnelingEnabled`;
- `codecPolicy.softwareAudioMimes`;
- `audioPolicy.deniedPassthrough`, `forceStereo` and `forcePassthrough`.

Only the video mode and tunnelling can actually leave a wrong codec behind (C7, M4; M8 covers tunnelling, M7/M12 cover audio). The rest are included anyway: the trial should be conservative, and comparing six fields costs nothing.

**`PlaybackExperiments` (new `internal object` in `player/src/main/.../engine/`).** It holds `const val KEEP_DECODERS_DEFAULT = false` and `@Volatile var keepDecodersAcrossZaps = KEEP_DECODERS_DEFAULT` (§6).

**`PlaybackControllerImpl`** gets an `internal var keepDecodersFlag: () -> Boolean = { PlaybackExperiments.keepDecodersAcrossZaps }`. This is a property, like `ioDispatcher`: a new constructor parameter would need a Dagger binding (C20).

**`Plan`** gets a field `val newCycle: Boolean = false`. Only `startCycle` (`:718`) passes `true`.

### 4.2 Rules

**R1: qualifying zap.** `endSession` keeps decoders only when all of these hold:
- `keepDecodersFlag()` and `!disabledForProcess`;
- `plan.newCycle` (a user-level cycle start, never a ladder restart, `reapplySettings` or `selectServer`; C3);
- `lifecycle.isForeground` (C16; M15);
- `trusted` (the codecs reached first media and nothing failed since);
- `!settings.current.tunneling` (tunnelling is not in the trial; Q3);
- a session exists (`endSession` returns early otherwise, C1).

Rapid zaps (A → B → C before B reaches first media) stay armed: the kept codecs are still A's, which were trusted.

**R2: order inside `endSession`.** It keeps today's order (cues, `session = null`, watchdog stop, `s.end()`), then:
- **Qualifying zap:** `keeper.arm()`, which calls `setForegroundMode(true)` (non-blocking, M2) and journals it. Then `player.stop()` disables without resetting (M1).
- **Not a qualifying zap:** `keeper.disarm(reason)`, which calls `setForegroundMode(false)` only if `armed`. Then `player.stop()`, which resets as today.

The transport is ended **before** either call. The one-connection rule (`StreamConnectionBroker`, old call cancelled before the next opens) is untouched. `disarm` runs after `session = null`, so a timeout error (M2) is dropped by C9.

**R3: pipeline check.** In `runSession`, right after `applySwitches` (`:857`) and before `setMediaSource` (`:914`): if `armed` and `keeper.lastKey != key(e.bundle)`, then `keeper.disarm("pipeline")`. The player is IDLE with disabled, unreset renderers, so this **blocks the main thread for one codec release** (M2/M3). That only happens when a channel's decoder memory differs from the previous channel's (rare) and only when ON; the time is logged.

After the check, `keeper.onPrepared(key, tuneId)` records `lastKey`. If the cycle started armed, it also sets `keptSessionTuneId = tuneId` for the cycle's first session.

**R4: disarm at first media.** `onFirstMedia` sets `trusted = true` and posts `keeper.disarm("first_media")` to the main handler, so the call does not run inside the listener. The renderers are enabled then, so nothing is released (M3). If a new arm happened in between, the posted disarm is a no-op.

**R5: every other stop resets.** `stop(reason)`, `fail`, the live-pause release, lifecycle stops and `handOffExternal` all reach `endSession` without `newCycle`, so R2 disarms before `stop()`. This also gives the hardware decoder back to an external player (C17) and to other apps on Home (C16).

**R6: ladder restarts reset.** `restart`, `reapplySettings` and `selectServer` never set `newCycle`, so every ladder step in C8 gets the full reset it gets today. `TryBackupSource` and the ring relaunch go through `startCycle`, but they follow a failure, so `trusted` is false and R1 does not hold.

**R7: reuse fallback.** In `onFailure` (`:1576`), before `ladder.decide`, all of these must hold:
- the failing session is `keptSessionTuneId`;
- the signal is a decoder-type signal (C11), or the `PlaybackException` is `TYPE_RENDERER`;
- this cycle has not used the fallback yet.

When they do, the keeper logs `DECISION keep_decoders_fallback <signal>` and runs `fallbacks++`; at 2 it sets `disabledForProcess = true`. It then calls `restart(c, LadderDecision.Reprepare(raiseTsCompat = false), signal, Plan(reuseResolved = …))`, which is a full reset (R6). The ladder counters are **not** touched, and nothing is remembered for the channel. Otherwise a reuse artefact would teach the channel "software video", `tunnelingOff` or `tsCompat` for good (`:1683-1711`).

Media3 has already reset the renderers for a renderer exception (M11). The fallback exists for stalls (`DECODER_STUCK`/`FROZEN_VIDEO`), which raise no exception, and so that the first failure is never charged to the channel. The fallback restart takes the cycle's one switch-window extension (`extendSwitchWindow`, `:741`) when it is still unused (Q5).

**R8: flag turned off at runtime.** `disarm` does not check the flag. If the debug link turns the flag off while armed, the next `endSession` (not a qualifying zap any more) disarms. With the flag off from the start of the process, `armed` is never true, so no call is ever made.

**R9: events, ON only.** `DECISION keep_decoders` with `kept`, `reset:<reason>` and `fallback`. Also, through an `AnalyticsListener` the keeper adds only while the flag is on, one event per kept session: `reuse v=<result>/<discardReasons> a=<…>` from `DecoderReuseEvaluation` (M16), and `fg_off_ms=<n>` for every disarm that took more than 5 ms. With the flag OFF, none of these exist (§7).

### 4.3 Sketch

```kotlin
private fun endSession(stopPlayer: Boolean = true, newCycle: Boolean = false) {
    if (_cues.value.isNotEmpty()) _cues.value = emptyList()
    val s = session ?: return
    session = null
    settledSession = null
    eng?.parts?.watchdog?.stop()
    s.end()                                             // transport first (unchanged)
    if (!stopPlayer) return                             // ring reader re-prepare (unchanged)
    val exo = eng?.bundle?.player
    if (exo != null && keeper.shouldKeep(newCycle, lifecycle.isForeground, settings.current.tunneling)) keeper.arm(exo)
    else if (exo != null) keeper.disarm(exo, "stop")   // no-op unless armed
    player?.stop()
}
```

With the flag OFF, `shouldKeep` returns false before reading anything else, and `disarm` returns because `armed` is false. The method then behaves exactly as `:1101-1109` does today.

### 4.4 What the user sees between channels

**OFF.** As today: `stop()`, IDLE, and the last frame is expected to stay (C1; unverified on SoCs, m4 §1933).

**ON.** Same IDLE gap. The codec is flushed, not released, so the surface's producer stays connected and the last displayed buffer should stay at least as reliably as after a release. The next channel's first frame replaces it:
- `onRenderedFirstFrame` still fires (M14), so `AppRoot`'s sticky size and the zap card's first-frame counter work unchanged (`:1201-1202`; m4 §3.6).
- `onVideoSizeChanged(0×0)` still arrives on disable (M14) and is ignored as today (`:1216`).

There is no `clearVideoSurface` and no shutter in either path.

## 5. Risks and how the design handles them

| Risk | Handling | Residual |
|---|---|---|
| **Memory on a 1 GB Stick.** Idle decoders hold output buffers, mostly in the media codec service process (not our PSS). | Foreground mode is armed only during a zap (§3.1). Every non-zap stop disarms first (R5). HIDDEN, poster and radio release the video codec anyway (M12, C15). Settings covers the video → HIDDEN → released. Home → R5. No timers or `onTrimMemory` are needed, because the hold never outlives the zap. | The peak during a zap should equal today's (REUSE_NO releases, then inits; M5). Verify on the Stick (§8). |
| **SoC codec quirks** (MediaTek, Amlogic): flush bugs, a stale frame of the old channel, in-band SPS reconfiguration. **This is the main risk.** Different channels almost always have different SPS/PPS → `YES_WITH_RECONFIGURATION` (M6), which is exactly where vendor decoders misbehave. Media3's built-in workarounds cover only listed codec names. | R7: one full-reset fallback per cycle, not charged to the channel. After 2 fallbacks the feature is off for the process. The device trial (§8) includes a visual stale-frame check with distinct pictures. | A stale frame or green flash that raises no error and no stall is invisible to R7. Only the visual check and soak find it. Q4 adds per-channel labels to the fake panel. |
| **A/V sync after reuse.** | The `AudioTrack` is new on every zap in both arms (M9), so the audio clock starts fresh. The video renderer's first-frame state is reset on enable (M14). | Lip-sync check on real channels (§8). |
| **Tunnelling on/off across channels; the per-channel `tunnelingOff` bit.** | Media3 releases the codec on any tunnelling change (M8). Also, R1 refuses to arm while the tunnelling setting is on, so the trial never reuses tunnelled codecs (the default is off, C14). | A tunnelled trial is a separate experiment (Q3). |
| **Passthrough ↔ PCM changes.** | The `AudioTrack` is recreated on every zap anyway (M9). "Bypass now possible" discards the codec (M7). A denial clears bypass on disable (M4 `onDisabled` → `disableBypass`). The pipeline key also covers it (R3). | None known. |
| **FFmpeg audio renderer.** | Never kept (M10). A move between MediaCodec audio and FFmpeg resets the disabled renderer (M12). `softwareAudioMimes` is in the key (R3). | None. |
| **Ladder steps that need a fresh decoder (C8).** | Every ladder restart is not a qualifying zap → disarm → `stop()` resets (R6). A decoder failure on kept decoders gets R7 first. | None in unbuffered cycles. For buffered cycles see §9 (pre-existing; not changed by the flag). |
| **Time-shift, archive, catch-up; one connection.** | A zap into or out of a buffered cycle still goes through `stop()` (`c.ts` is null at launch, `:760`), so R1/R2 apply. Ring reader re-prepares are untouched (no `stop()`, M13). Archive and catch-up cycle starts (`tuneArchive` → `startCycle`, continuations included) qualify under R1. The transport ends before any player call (R2), so the broker order is unchanged. | Whether archive continuations should keep decoders is Q2 (default yes: same channel and format, the likeliest win). |
| **Guide mini player / second consumer.** | There is one player and one root `SurfaceView` (CLAUDE.md). The mini player is the same player, so a guide OK tune is a `startCycle` and R1 applies. HDR poster in MINI disables video → released (M12). No other `ExoPlayer` exists in production code (`grep ExoPlayer.Builder`: only `PlayerFactory.kt:75`). Other apps get the decoders on Home (R5) and on the external hand-off (C17). | None. |
| **Blocking `setForegroundMode(false)`** (M2). | Usual case (R4, at first media): a message round trip only. It blocks for a codec release only in R3 (pipeline change) and when a stop follows an IO error during a zap. It is bounded by `RELEASE_TIMEOUT_MS` = 1 s (C12). The time is logged (R9). A timeout raises `ERROR_CODE_TIMEOUT` while `session == null`, so it is ignored (C9), and the next `prepare` clears it. | A main-thread stall of one codec release, only when ON. The trial measures `fg_off_ms`. Pass criterion §8.4. |
| **The reused codec throws.** | Media3 resets the renderers itself (M11). R7 re-prepares once without telling the ladder. After 2 in the process, the feature is off until restart. | Not persisted across launches (Q6). |
| **IO error during the zap gap** (new channel dead) while armed. | M11: renderers disabled, not reset. The ladder restart is not a qualifying zap → disarm (one blocking codec release) → `stop()`. | Same as the blocking row. |
| **Debug build in the trial.** | Both arms run the same build, alternating ABBA (§8). | Flipping the default needs a release-build smoke run (§8.4). |

## 6. The flag

**Where it lives.** `internal object PlaybackExperiments` in `player/src/main` holds `const val KEEP_DECODERS_DEFAULT = false` and a `@Volatile var keepDecodersAcrossZaps`. It is not a user setting and not in `PlaybackSettings`. It is not persisted.

**How a tester turns it on.** Through the existing debug-link path, like `capture=` (C19):
- `PlayerDebugHooks.keepDecodersAcrossZaps` (debug source set) exposes the variable.
- `GuideDebugReceiver` accepts `keep_decoders=1|0` on `thebox-debug://guide?…`, e.g. `adb shell am start -d 'thebox-debug://guide?keep_decoders=1'`.
- `GuideTestBase` passes `-e keep_decoders` into its switches, the way I-17 passes `-e timeshift`.

The new value applies from the next `endSession`.

**Why a debug link, not a build-time constant.**
- One installed build can run both arms back to back in ABBA order, so warm-up, clip phase (C22) and thermal state cancel out.
- Release builds cannot change it: nothing in `main` writes the variable, and the debug source set does not exist in release (C19).

Turning it on for everyone later is a one-line change to `KEEP_DECODERS_DEFAULT`, reviewed and recorded in a decision note.

**Proof that OFF is unchanged:** §7.

## 7. OFF is byte-for-byte today

The OFF path is guaranteed structurally and by tests.

1. **Structure.** With the flag false:
   - `shouldKeep` returns false first.
   - `armed` stays false, so `disarm` returns before touching the player.
   - R3 is guarded by `armed`.
   - R4's posted disarm is a no-op, and `trusted` is only bookkeeping.
   - R7 is guarded by `keptSessionTuneId`, which stays −1.
   - The R9 listener is added only while the flag is on.
   - `Plan.newCycle` changes no other behaviour.

   So no `ExoPlayer` call, event or state change differs.
2. **Existing tests stay green, unedited.** These pin today's journals, including where `player.stop` appears and where it does not: `PlaybackControllerStateTest`, `LadderExecutionTest`, `LifecycleTest`, `ReapplyAndHandOffTest`, `ControllerTimeshiftTest` (`:593`, `:632`: no `stop()` on reader re-prepares), `RealEngineControllerTest` and `PlayerFactoryTest` (builder fields). They all run with the default flag, which is OFF.
3. **New pin `KeepDecodersOffTest`.** `FakeExoPlayer` journals `setForegroundMode:<b>`. A test that never sends it leaves its journal unchanged, which is what item 2 relies on. The test drives zap → first media → zap ×3, a ladder `SoftwareVideoAndReprepare`, `stop(USER)`, `stopForLifecycle`, `fail`, the external hand-off, a buffered zap and an archive tune. It asserts:
   - no `setForegroundMode` in the journal;
   - no `keep_decoders*` events;
   - the journal equals a golden string recorded from the same scenario on the current `main` (committed with the test, so any later drift fails).

## 8. Measurement plan (Fire TV Stick)

### 8.1 Setup

- **Device.** Fire TV Stick with 1 GB RAM: Fire OS 7 (API 28) Stick 4K or 3rd gen. Note the SoC (`getprop ro.board.platform`). Wired or 5 GHz.
- **Build.** One debug build of the WP-1/WP-2 commit, used for both arms. Tunnelling off, AFR at the default, "Pause and rewind" Off for the main run; one extra pair of runs with it on.
- **Fake panel.** On the LAN: `python3 tools/fakepanel/fakepanel.py --port 8080`, with `faults = {"reconnect_mode": "reset"}` (C22), no `close_every`, `max_connections = 1`.
- **Channel mix** (C23). Channels 1/2/5/6: same format, the reuse case. 3/12: MP2 audio, an audio MIME change, so the audio codec re-initialises. 4: 50 fps. The HDR10 variant: a colour-info change, so the video codec re-initialises. 13: radio, video renderer reset (M12). One `media_override: ac3` channel: passthrough on the Stick's HDMI route.
- **Real provider.** One pass against the owner's provider, which has a 720p/1080i/HEVC mix the fake panel lacks.

### 8.2 Runs (existing harness)

- **I-17 `ZapThroughGuideTest`** with `-e keep_decoders 1|0`: 10 guide OK tunes and 20 FULL CH+ zaps per run.
  - Run order A B B A A B B A, which is 4 runs per arm: 120 zaps per arm.
  - Read: `ZapRecord.firstFrameMs` P50/P90, separately for guide and FULL; `readyMs`; the outcome counts (I-17 logs all of these). Also `controller.diagnostics.events()` counts of `ERROR`, `DECISION` (ladder), `STALL`, `keep_decoders kept/reset/fallback`, and the `reuse` results (R9, ON arm).
- **Format-change pairs:** a new androidTest `KeepDecodersZapTest` on `PanelTestBase` (WP-2). It uses `launch(…, zap = …)` over fixed pairs (1→2, 2→3, 3→4, 4→HDR, HDR→13, 13→1), 10 rounds per arm.
  - For each pair: `firstFrameMs`, plus the ON arm's reuse result. This shows where reuse actually happens and what it is worth per case.
- **Idle and peak memory.**
  - Measure `adb shell dumpsys meminfo` (system "Free RAM"/"Used RAM") and `dumpsys meminfo <pid>` for the codec service. Find it with `adb shell ps -A | grep -iE 'codec|omx'`; the process name varies by Fire OS version, so look it up rather than assume it.
  - Take readings (a) 60 s after `stop(USER)`, (b) during a 1 Hz CH+ storm, (c) 60 s after Home. ON and OFF must match within noise at (a) and (c).
- **Soak.** 30 min with a zap every 10 s from the guide mini player and FULL, and the guide open half the time. Count error cards, `keep_decoders_fallback`, low-memory kills (`adb logcat -b events | grep am_kill`), and ANRs.
- **Visual.** Record 20 ON zaps per pair with a phone at 240 fps, or HDMI capture. Look for a stale old-channel frame after the new picture, a green or garbled first frame, a black flash, and lip sync on two real channels.
  - The fake panel's channels all show the same picture (C23), so stale frames can only be seen on the real provider, or after Q4.

### 8.3 What the emulator cannot answer

The emulator cannot answer any item in §8.2. Emulator decoders are software (`c2.android.*`/goldfish), so init is cheap and there are no vendor quirks. There is no 1 GB memory pressure, no HDMI passthrough and no tunnelling.

### 8.4 Pass criteria for flipping the default

All of these must hold:
1. FULL CH+ `firstFrameMs` P50 improves by ≥ 80 ms **or** ≥ 10 %, and P90 is not worse by more than 20 ms (the ABBA median of run medians).
2. Non-ok outcomes ON ≤ OFF across ≥ 120 zaps per arm. No ladder decision appears in ON that does not appear in OFF.
3. Zero `keep_decoders_fallback` on the fake panel; at most 1 in the 30-min real-provider soak, with no error card.
4. No stale frame, garbled frame, new black flash or lip-sync offset in the visual check.
5. Memory at (a) and (c) equal within noise; no low-memory kill and no ANR in the soak; every `fg_off_ms` ≤ 50 ms at P99, none ≥ 1 s.
6. A smoke run of 20 zaps on a **release** build with `KEEP_DECODERS_DEFAULT = true` (signed with the debug key, not shipped).

If criterion 1 fails but the others pass, the feature stays OFF and the decision note records the measured gain.

## 9. Pre-existing defect (independent of the flag)

In a buffered cycle (pause and rewind on), `execute` lets reader-scoped decisions fall through to the normal path (`executeBuffered`, `:2786-2789,2849`). `launchSession` then turns the plan into a reader re-prepare (`:757`) and calls `endSession(stopPlayer = false)` (`:760`).

So `SoftwareVideoAndReprepare`, `Reprepare` after `DECODER_STUCK`, `DisablePassthroughAndReprepare` and the other repairs in C8 reach the player as `setMediaSource` + `prepare` with no `stop()`. Media3 disables the renderers without resetting them (M13) and **reuses the same codec** when the format allows it (M5/M6). That is always the case on the same channel.

As a result:
- The software-video switch never consults the selector again (M4), so it does nothing.
- A stuck decoder is flushed, not replaced.
- Tunnelling (M8) and audio-renderer changes (M12) are still applied, and renderer exceptions are still reset by Media3 (M11). Only the stall-driven repairs and the video-mode change are affected.

Fixing it changes OFF behaviour, so this design does not fix it (Q1).

## 10. Test plan off-device

**JVM, `FakeExoPlayer`** (`player/src/test/.../controller/`). Each test's journal assertion covers order.
- `KeepDecodersOffTest`: §7 item 3.
- `KeepDecodersOnTest`, with the flag on through the property:
  - zap after first media → `endSession:<id>` before `setForegroundMode:true` before `player.stop`;
  - first media → posted `setForegroundMode:false`;
  - rapid zap before first media → stays armed (no extra calls);
  - zap after a failure → `setForegroundMode` absent, `player.stop` present;
  - `stop(USER)` / `fail` / `stopForLifecycle` / live-pause release / `handOffExternal` while armed → `setForegroundMode:false` before `player.stop`;
  - a ladder `SoftwareVideoAndReprepare` while armed → disarm before stop;
  - per-channel `videoDecoder` override on the next channel → `setForegroundMode:false` after `resolve:` and before `setMediaSource` (R3);
  - tunnelling setting on → no arm;
  - `lifecycle.isForeground == false` → no arm;
  - R7: a `DecodingFailed` signal in the first session of a kept cycle → `DECISION keep_decoders_fallback`, a new session, `ladder` not called (spy) and channel memory unchanged (`FakeMemory`); a second fallback in the process → the next zap does not arm;
  - flag flipped off while armed → the next `endSession` disarms;
  - buffered cycle: zap → arm; reader re-prepare → no `setForegroundMode`, no `player.stop` (as `ControllerTimeshiftTest:593`).
- `DecoderKeeperTest`: the pure state machine (arm/disarm idempotence, key comparison, the process disable counter), against a recording `ExoPlayer` proxy.

**Robolectric with the real engine** (`RealEngineKeepDecodersTest`, modelled on `RealEngineControllerTest`):
- Two live tunes of the same TS fixture through the real `PlayerFactory` with `ShadowMediaCodecConfig`, counting `onVideoDecoderInitialized`/`onVideoDecoderReleased` through an added `AnalyticsListener`. Expect 2 inits and 1 release OFF; 1 init and 0 releases ON.
- A third tune after `stop(USER)`, expecting a release in both arms.
- This proves that the controller's call order really reaches M1/M4 in Media3.
- **Uncertain:** whether Robolectric's shadow codec supports `flush()` cleanly. If it does not, assert `DecoderReuseEvaluation` from `onVideoInputFormatChanged` instead and record the gap.

**Emulator (API 30 TV)**:
- Shows: I-17 and `KeepDecodersZapTest` pass in both arms, with no new errors or ladder decisions; `reuse` events appear for same-format pairs and NO results for MP2/HDR/50 fps where M6/M7 say so; foreground mode is off after Home (`setForegroundMode:false` in the event log).
- Does not show: the zap-time gain (software codecs), vendor codec behaviour, memory on 1 GB, last-frame retention on a SoC, passthrough or tunnelling.

## 11. Work packages

| WP | Files (disjoint) | Content | Size | Model |
|---|---|---|---|---|
| WP-1 | `player/src/main/.../controller/DecoderKeeper.kt` (new); `player/src/main/.../engine/PlaybackExperiments.kt` (new); `player/src/main/.../controller/PlaybackControllerImpl.kt`; `player/src/test/.../controller/fakes/FakeExoPlayer.kt` (journal `setForegroundMode`); `player/src/test/.../controller/KeepDecodersOffTest.kt`, `KeepDecodersOnTest.kt`, `DecoderKeeperTest.kt` (new) | §4 and the JVM tests of §10 | ~180 lines of production code (keeper about 110; controller about 50 across `Plan`, `endSession`, `runSession`, `onFirstMedia`, `onFailure`, `onPlayerFailure`/`onStall` trust bit); ~400 lines of tests | Opus |
| WP-2 | `player/src/debug/.../PlayerDebugHooks.kt`; `app/src/debug/.../GuideDebugReceiver.kt` (`keep_decoders=`); `app/src/androidTest/.../guide/GuideTestBase.kt` (`-e keep_decoders`); `app/src/androidTest/.../playback/KeepDecodersZapTest.kt` (new) | §6 switch; §8.2 harness | ~120 lines | Sonnet |
| WP-3 | `player/src/test/.../controller/RealEngineKeepDecodersTest.kt` (new) | Robolectric real-engine proof (§10) | ~150 lines | Sonnet (Opus review) |
| WP-4 (optional, Q4) | `tools/fakepanel/fakepanel.py`, `README.md`, `test_fakepanel.py` | Per-channel `drawtext` labels and a 1080p H.264 clip (`media_override`) for stale-frame and resolution-change cases | ~80 lines of Python | Sonnet |
| WP-5 | `docs/decisions/2026-11-xx-keep-decoders.md`; `docs/00-master-plan.md` M6 row | Lead records the reconciled design, then the measurement verdict | — | lead |

Dependencies: WP-2 and WP-3 depend only on WP-1's names (`PlaybackExperiments.keepDecodersAcrossZaps`, `keepDecodersFlag`). Fix those names first, then all three can run in parallel.

Total: small to medium. About 300 lines of production code and debug wiring, and about 550 lines of tests. A review pass on WP-1 and the lint/smoke gates are required (CLAUDE.md).

## 12. Open questions for the lead

| # | Question | Recommended default |
|---|---|---|
| Q1 | Fix the §9 buffered-cycle defect now, separately from the flag (e.g. `stop()` before a reader re-prepare for decoder-class decisions only, accepting a possible lost last frame there)? | **Yes, as its own small change with its own review.** It is a real ladder bug when pause and rewind is on, and it is not covered by "OFF unchanged". |
| Q2 | Should archive, catch-up and time-shift continuation cycles qualify under R1? | **Yes.** Same channel and format, so reuse is likely; R7 covers failures. |
| Q3 | Trial with tunnelling on as well? | **No.** Arm only with tunnelling off (the default). Tunnelled reuse is a later, separate experiment on a device where tunnelling is known to work. |
| Q4 | Add WP-4 (labelled channels, a 1080p clip) to the fake panel? | **Yes**, if the real-provider visual pass is not available. Without it, stale frames cannot be seen on the fake panel. |
| Q5 | Should the R7 fallback use the cycle's one switch-window extension? | **Yes.** It is a pipeline repair (`:735-740` rationale), and with a 15 s window a late `DECODER_STUCK` would otherwise end the cycle before the repair runs. |
| Q6 | Remember reuse failures across launches (per device) rather than per process? | **No for the trial.** The event log and export bundle record them; persistence can be added when flipping the default if the soak shows any. |
| Q7 | Flip the default after the Stick alone, or also require one Amlogic/Google TV box? | **Stick plus one other SoC** (Onn or Chromecast with Google TV) before a release ships it on; the Stick alone is enough to decide whether to continue. |
| Q8 | Is `DecoderPipelineKey` too broad (six fields), causing needless resets? | **Keep it broad for the trial.** Narrow it to video mode + tunnelling only if the ON arm's `reset:pipeline` count is material. |

## 13. Not established

- **The size of the gain on the Stick.** Codec init time on its MediaTek/Amlogic decoders is not measured. The `AudioTrack` is recreated in both arms (M9), so H1's 100–300 ms is an upper bound at best.
- **Last frame after `stop()`.** Whether the SoC keeps it, with the codec released (today) or flushed (ON), is still open from m4 §1933.
- **Vendor behaviour on flush and in-band SPS reconfiguration** (`YES_WITH_RECONFIGURATION`) across channels. The source shows Media3's logic, not the vendor decoders'.
- **The codec service process name and memory accounting on Fire OS 7/8.** §8.2 says how to find them.
- **Robolectric shadow codec and `flush()`**: §10 has the fallback assertion.
- **How often the fake panel or a real provider gives `REUSE_RESULT_NO`** (resolution above the first channel's, colour info, frame rate on API ≥ 30). The ON arm's `reuse` events will show it.
