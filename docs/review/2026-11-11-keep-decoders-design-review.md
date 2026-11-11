# Keep decoders across zaps: adversarial design review

Reviewed: `docs/design/m6-keep-decoders-across-zaps.md` at `fe3cbc3` (proposal for performance H1).
Reviewer: adversarial (Opus), 2026-11-11. Review only; nothing implemented, nothing committed.

Sources checked:
- Our code at `fe3cbc3`. The line numbers below are ours at that commit. The design's citations at `c0f0b3f` still match, since `fe3cbc3` only adds the design doc.
- Media3 **1.11.1** sources jars (`media3-exoplayer`, `-decoder`, `-common`), unpacked and read. Media3 paths are relative to `androidx/media3/exoplayer/`.

Owner constraint: the feature ships OFF, OFF must be exactly today's behaviour, and ON must be safe enough to trial.

**Verdict: build with changes, and only after a cheap pre-measurement (S1).** The Media3 analysis in the design is accurate; I confirmed nearly every claim. It has two blockers. First, the disarm can be skipped, which leaves foreground mode (and the idle codecs) on behind an error card, in standby and in the background. Second, the safety net (R7) does nothing in the default configuration, because Pause and rewind is on by default. The expected gain is unproven. On the channel pairs where the codec cannot be reused, ON is probably *slower* than OFF.

Counts: **2 blockers, 11 should-fix, 9 notes.**

---

## Blockers

### B1. Foreground mode leaks whenever a stop happens between the zap's `stop()` and the next session's `session = s`

**Evidence.**
- The design puts arm and disarm in `endSession` *after* `val s = session ?: return` (sketch §4.3). Today's `endSession` returns at `PlaybackControllerImpl.kt:1103` when there is no session.
- After a qualifying zap, `session` stays null through the whole of `runSession`'s IO: facts, resolve, account refresh, time-shift storage (`:777-904`). It is set only at `:905`.
- Every stop path that runs in that window calls `endSession()` with `session == null`. It returns before R2's disarm, so foreground mode stays **on**. The renderers stay disabled but not reset, and the codecs are held.

The paths I found:

| Path | Where |
|---|---|
| Resolve failure → `fail` (or `onFailure` → `Fail`) | `:1042-1043`, `:1813-1821` |
| Account gate `Fail` after a refresh | `:821-825` |
| DASH source | `:851-853` |
| Media-source build failure | `:888-902` (`s.end()` then `fail`, before `session = s`) |
| User stop, external hand-off | `stop(USER)` → `:1942`; `handOffExternal` `:616-621` |
| Home, standby/display-off/HDMI grace expiry | `stopForLifecycle` → `stop` (`:1978-1984`). `isPlaybackActive()` is true in `Connecting` (`:1972-1973`), so the grace path stops, but with no session. |
| Live-pause release | `pause()` is allowed in `Connecting` (`:1862`), and the release job calls `endSession()` at `:1885` |
| Switch timeout | `onSwitchTimeout` → `fail` (`:1570`) |
| A rapid zap that is not a qualifying zap | `startCycle` → `launchSession` → `endSession` returns early |

Worse, once the error card is up (state `Error`, `cardShown == false`), `isPlaybackActive()` is false. So `LifecycleCoordinator.onProcessStop` never calls `stopForLifecycle` (`LifecycleCoordinator.kt:103-118`), and the grace-expiry runnable does nothing either (`:85-92`). The app goes to the background **with foreground mode on and a hardware video decoder allocated**, and stays that way until the next tune. That is exactly what the `ExoPlayer.setForegroundMode` Javadoc forbids (`ExoPlayer.java:1934-1963`), and it is the leak the brief calls a blocker. It also breaks the design's claim (§3.1, §5 memory row) that "the hold never outlives the zap".

**Required change.**
1. The disarm must not depend on `session`. Call `keeper.disarm(reason)` at the top of `endSession`, before the early return, for every call that is not a qualifying zap. Also call it from `fail`, `stop` and the live-pause release, even when they find no session.
2. Disarm unconditionally in `onForegroundChanged(false)` (`:2010-2012`). That is the Home/standby hook, and it runs whatever `isPlaybackActive()` says.
3. In `KeepDecodersOnTest`, add each path in the table above *in the session-less window*. The design's §10 list tests only stops that have a live session.

### B2. With Pause and rewind on (the default), R7's "full reset" and every ladder repair reuse the same kept codec

**Evidence.**
- `TimeshiftLength` defaults to `AUTO` (`core/.../settings/SettingsStore.kt:158`), so a live TS tune is a buffered cycle by default.
- R7 calls `restart(...)` → `launchSession`. Because `c.ts != null`, `launchSession` rewrites the plan into a reader re-prepare (`:757`) and calls `endSession(stopPlayer = false)` (`:760`). `runReaderSession` then calls `setMediaSource` + `prepare` with no `stop()` (`:2466-2468`).
- In Media3 that path disables the renderers **without** a reset (`ExoPlayerImplInternal.java:2655` → `seekToPeriodPosition` `:1859-1872`; and `prepareInternal` `:1061-1068` passes `resetRenderers = false`). The codec is then reused through `canReuseCodec` (`MediaCodecRenderer.java:1859-1925`).
- For a stall (`DECODER_STUCK`/`FROZEN_VIDEO`), which is R7's stated reason to exist (§4.2 R7, second paragraph), Media3 has *not* reset anything (no exception, so no `stopInternal(true)`). So R7's re-prepare runs on the same suspect codec, and so does the ladder's next step (§9).
- So in the default configuration, ON has no working safety net for the failure class the design identifies as "the main risk" (§5 row 2).
- §8.1 also runs the main measurement with Pause and rewind **Off** and gives the default configuration only "one extra pair of runs". The trial would mostly measure a non-default path.

**Required change.**
- Make R7 do a real reset in a buffered cycle: a plan flag that forces `endSession(stopPlayer = true)` (disarm, then `stop()`) before the reader re-prepare, accepting the lost last frame there. This is the same change as the fix for the pre-existing §9 defect. **Land the §9 fix first** and have R7 use it.
- Until then, ON must not arm when `settings.current.timeshift != OFF`.
- Run the main measurement with default settings (Pause and rewind AUTO), and the Off configuration as the extra pair.

---

## Should-fix

### S1. The gain is unproven; on reuse-NO pairs ON is probably slower than OFF; measure the upper bound first

**Evidence.**
- With `REUSE_RESULT_NO`, `onInputFormatChanged` → `drainAndReinitializeCodec` → `reinitializeCodec` = `releaseCodec()` then `maybeInitCodecOrBypass()`. This happens at format arrival, synchronously on the playback thread (`MediaCodecRenderer.java:1873-1874`, `:2235-2242`, `:2628-2631`). That is *after* the HTTP connect and the first bytes, so it is on the first-frame critical path.
- Today the release happens at `stop()` (`resetInternal` → `reset`, `ExoPlayerImplInternal.java:2096-2104`), overlapped with the resolve and facts IO (`:777-904`).
- So for every pair Media3 rejects, ON moves the old codec's release from the overlapped gap onto the critical path. That is a latency regression of up to one codec release. The rejected pairs include:
  - SD/720p → 1080 (the codec's max size is the first format's, `MediaCodecVideoRenderer.java:2720-2741`);
  - H.264 ↔ HEVC;
  - SDR ↔ HDR;
  - MP2 ↔ AAC audio;
  - a non-adaptive decoder with any resolution change (`MediaCodecInfo.java:478-480`).
- On real providers with a 576i/720p/1080i/HEVC mix, these pairs are common.
- The design's §5 memory row looks at the peak but not at this timing. The pass criteria (§8.4) aggregate over all zaps, so a regression on some pairs can hide under a win on others.
- On the YES pairs, the gain is the video init plus any part of today's release that was *not* overlapped (fast Xtream resolves leave little gap). That is plausibly tens of ms to ~150 ms on a ~1 s zap, but nobody has measured it. The `AudioTrack` (M9) and the FFmpeg decoder (M10) are rebuilt in both arms. The platform AAC decoder is software and cheap to start.

**Required change.**
- Add **WP-0** before WP-1: in OFF, record per zap `initializationDurationMs` for the video and audio decoders (`AnalyticsListener.onVideoDecoderInitialized` / `onAudioDecoderInitialized`; `StatsCollector.kt:160,180` already receives them), plus the `endSession` → `prepare` gap. Put them on the `ZapRecord` or in the event log. Run I-17 on the Stick.
- If the median video + audio init is below the design's own 80 ms bar (§8.4 item 1), stop: H1 is closed as "measured, not worth it". This costs about 20 lines and changes no behaviour.
- Add a per-pair pass criterion: no `REUSE_NO` pair (from `KeepDecodersZapTest`) may be slower ON than OFF by more than 20 ms at P50.

### S2. R7 catches failures that have nothing to do with codec reuse

**Evidence.**
- R7 triggers on `isDecoderTypeSignal` or on any `TYPE_RENDERER` exception.
- `isDecoderTypeSignal` includes `FailureSignal.Parsing` (`:3236`), which is a container/source fault.
- `TYPE_RENDERER` includes `AUDIO_TRACK_INIT_FAILED` / `AUDIO_TRACK_WRITE_FAILED` (`FailureClassifier.kt:129-134`). The `AudioTrack` is never kept (M9), so these cannot be reuse artefacts.
- Swallowing them delays the L34 passthrough denial and the audio fallbacks by one cycle. Two refused passthrough zaps would also switch the feature off for the process.

**Required change.** Limit R7 to:
- `FailureSignal.Stall(DECODER_STUCK | FROZEN_VIDEO)`;
- video `DecodingFailed` / `DecoderInit` (`isVideo` is already in the signal);
- and only in the kept session.

### S3. R7 must not use up the cycle's one switch-window extension (Q5)

**Evidence.**
- `extendSwitchWindow` is once per cycle (`:741-747`). It exists for the Sony Bravia case: a `DECODER_STUCK` verdict at about 14 s, then a pipeline repair that the 15 s deadline killed (`:734-740`).
- If R7 takes it, a real decoder fault on a kept cycle goes like this: R7 runs and extends the window → a second `DECODER_STUCK` arrives about one detection window later → the ladder's `SoftwareVideoAndReprepare` finds the extension already used → the deadline fails the cycle before the repair runs. That is the Bravia failure again.

**Required change.** Give R7 its own extension (at most one per cycle), separate from `switchExtended`, or none. Recommended default to Q5: **no** for the shared extension.

### S4. R4 (disarm at first media) blocks the main thread at the first frame, and a timeout there stops the live session

**Evidence.**
- `setForegroundMode(false)` blocks on a `ConditionVariable` until the playback thread handles the message (`ExoPlayerImplInternal.java:618-624`).
- At the first frame, the playback thread is typically still starting the audio codec or the `AudioTrack`. Both run synchronously inside `doSomeWork`, and `AudioTrack` creation for passthrough can take tens to hundreds of ms on Fire TV. So R4 is a recurring main-thread block, every kept zap, while the zap banner is animating. It is not "a single message round trip".
- If the wait reaches the timeout (1,000 ms = `releaseTimeoutMs`, `:316`; `PlayerFactory.kt:85`), `ExoPlayerImpl.setForegroundMode` calls `stopInternal(ERROR_CODE_TIMEOUT)` (`ExoPlayerImpl.java:1111-1117`, `:2161-2179`). That **stops the new channel and raises `onPlayerError`**.
- Unlike R2/R3, R4 runs with `session != null`, so C9 does not drop the error. It goes to the ladder as an unknown or timeout failure.

**Required change.** Drop R4 and disarm lazily:
- at the next endSession that is not a qualifying zap (where `session` is null, so a timeout is dropped by C9);
- on `onForegroundChanged(false)` (B1).

The hold stays bounded: a source error during playback leaves disabled renderers only until the ladder's `restart` → `endSession`, which follows at once. If R4 is kept, defer it (for example 2 s after READY) and log its duration separately from R3.

Either way, consider `ExoPlayer.Builder.experimentalSetForegroundModeTimeoutMs` (`ExoPlayer.java:556`) to cap the block at about 300 ms. This changes only a builder field, which has no effect while OFF, but `PlayerFactoryTest` pins the builder fields.

### S5. Arm before `s.end()`, or the cancelled transport can reset the renderers first

**Evidence.**
- `s.end()` cancels the call synchronously (`TuneSession.kt:51-55` → `TransportFactoryImpl.kt:70-72`). The blocked read then throws `LiveStreamException(PREEMPTED)` (`LiveTsDataSource.kt:373-379`).
- If the loader's error reaches the playback thread before the posted `MSG_SET_FOREGROUND_MODE`, `handleIoException` → `stopInternal(false)` runs with foreground mode still off, and the renderers are **reset** (`ExoPlayerImplInternal.java:1016-1025`, `:2041-2050`). The kept codec is lost silently.
- The same race exists on buffered zaps: `startCycle` closes the old ring (`:694`) before `launchSession`.
- §3 claims option (a) avoids the race that rules out option (b). It narrows it but does not avoid it.

**Required change.** Call `keeper.arm()` (non-blocking, M2) **before** `s.end()`. It opens no connection, so the broker rule ("cancel the old call before opening the next") is untouched. Report the reuse rate per arm from the R9 `reuse` events.

### S6. Diagnostics regress when ON: decoder names go blank after a kept zap

**Evidence.**
- `StatsCollector.bind` clears `videoDecoder` and `audioDecoder` every session (`StatsCollector.kt:80-95`).
- They are set again only in `onVideoDecoderInitialized` / `onAudioDecoderInitialized` (`:160-161`, `:180-181`), which do not fire for a reused codec.
- So the stats overlay and the export bundle show no decoder after every kept zap.

**Required change.** Keep the decoder names across `bind` when the next `onVideoInputFormatChanged` carries a non-NO `DecoderReuseEvaluation`, or fill them from `DecoderReuseEvaluation.decoderName`. This touches `engine/StatsCollector.kt`, which is in no work package.

### S7. "OFF is byte-for-byte" is not true as written

**Evidence.**
- §7 says R4's posted disarm is a no-op when OFF. That means a `Runnable` is still posted to the main handler on every first media in OFF, which is a new main-looper message and changes ordering under Robolectric.
- `keeper.onPrepared(key, …)` builds the key (reading `trackSelector.parameters`) on every prepare.
- No `ExoPlayer` call differs and nothing is logged, so the journal-based proof in §7 still holds. But the claim "no event or state change differs" is too strong.

**Required change.**
- Guard every keeper touch with one `if (!keepDecodersFlag() && !keeper.armed)` check: no post, no key building, no trust bookkeeping when OFF.
- State the invariant as "no new `ExoPlayer` calls, events or main-looper messages", and pin the message part with a Robolectric `ShadowLooper` count around a zap if that is practical.

### S8. The pipeline key cannot see a silent decoder fallback

**Evidence.**
- Decoder fallback is on (`docs/design/m2-playback-core.md:1792`). When channel A's hardware decoder fails to initialise, Media3 moves to the next one in the selector's list and A plays on a software decoder.
- ON then reuses that software decoder for every later channel whose format fits. That turns one channel's init failure into software decoding for the rest of the session: dropped frames at 1080 on a Stick.
- The key only compares policy values, which have not changed.

**Required change.** Do not arm when the kept video codec is `softwareOnly` and the effective video mode is not `SOFTWARE_PREFERRED`. The codec name comes from `onVideoDecoderInitialized`, and `MediaCodecInfo` from the selector.

### S9. The measurement plan cannot see the realistic failures as specified

**Evidence and required changes.**

**(a) Leaks.** `dumpsys meminfo` at (a) and (c) cannot see B1-type leaks, which happen only on timing paths. Add scripted runs of stop/Home/standby/fail **during the resolve window**. Check codec ownership per process with `adb shell dumpsys media.resource_manager`, which lists codec resources by pid; confirm it exists on Fire OS 7. Pass: no codec owned by our pid after Home.

**(b) Stale frames.**
- Every fake-panel channel shares one encode (`tools/fakepanel/README.md:15-17`), so every pair is `YES_WITHOUT_RECONFIGURATION`. The risky in-band reconfiguration path (different SPS/PPS, M6) is never exercised off the real provider.
- We set `FLAG_ALLOW_NON_IDR_KEYFRAMES` (`TsExtractorsFactory.kt:65`), so a new channel can start on a non-IDR I-frame. On a decoder whose `flush()` keeps its reference frames, the following frames would predict from the **old channel's** pictures. That is a silent mixed frame that neither R7 nor the watchdog sees.
- Make WP-4 mandatory, not optional (Q4), with:
  - a distinct label per channel;
  - a second encode (different profile/level, reference frames, `-x264-params` open GOP or intra refresh);
  - a 1080p clip;
  - an A/V sync clip (flash + beep).

**(c) Lip sync.** "Lip sync on two real channels" judged by eye is not a measurement. Use the flash/beep clip with the 240 fps camera, and put a numeric bound in §8.4 (for example |ON − OFF| ≤ 20 ms).

**(d) Runnability.** Instrumented runs uninstall the debug app, which loses the Keystore key and the login (project notes). So the androidTest harness cannot be the vehicle for the real-provider pass on the owner's Stick. Specify:
- fake-panel harness runs, which the owner starts, warned first;
- a manual real-provider pass: debug link `keep_decoders=1|0`, zap by remote, diagnostics export or `adb logcat`.

**(e) Supersedes.** I-17 presses CH+ at 1 Hz (`ZapThroughGuideTest.kt:36-39`), so slow zaps are superseded and drop out of `ok`. Report supersede counts per arm and compare `ok`-rate as well as `firstFrameMs`.

### S10. R3's blocking disarm and the IO-error disarm need their own numbers

Both block the main thread for a full codec release (M2/M3). The IO-error case (a dead channel during a zap, which is common on real providers) happens on every zap to a dead channel. Measure and report these two `fg_off_ms` sources separately from R4 (or from its removal). Keep the "none ≥ 1 s" criterion, and add a P90 bound for the IO-error disarm.

### S11. Facts table C3 is incomplete

`setVideoDecoderOverride` (`:2137-2150`) and `launchReader` (`:2547-2552`) also call `launchSession`. Neither sets `newCycle`, so R2 resets correctly when unbuffered. Both are reader re-prepares when buffered, so §9 and B2 apply to them too. Correct C3 and add the decoder-override case to the ON tests.

---

## Notes

- **N1.** The Fire TV Stick 4K (2018) has 1.5 GB of RAM. The 1 GB devices are the 3rd gen (2020) and the Lite. Pick the 1 GB unit for the memory part of §8.
- **N2.** C19: the existing switch is `capture_ts=`, not `capture=` (`GuideDebugReceiver.kt:194-196`).
- **N3. Flag safety.**
  - A release build cannot flip the flag through the link: the `thebox-debug://` filters live in `app/src/debug/AndroidManifest.xml`, and `player/src/debug` does not exist in release or `benchmark`, since `benchmark` falls back to `release` (`app/build.gradle.kts:56-75`).
  - But "nothing in main writes it" is a convention. Add a guard: the setter ignores writes unless the app is debuggable, or a unit test greps `main` for writers.
  - The variable is read and written on main only, and `@Volatile` is enough.
  - With `const val KEEP_DECODERS_DEFAULT = false` and no writer in release, R8 should strip the ON path.
- **N4.** When ON, `MediaCodecAudioRenderer.onReset` is skipped, so `audioSink.reset()` (processors, `offloadDisabledUntilNextConfiguration`) is deferred (`MediaCodecAudioRenderer.java:793,816-819`; `DefaultAudioSink.java:1612-1625`). `flush()` and the next `configure` cover what we use (`StereoDownmixProcessor`), but add a forced-stereo on → off zap pair to the ON tests.
- **N5.** On a `REUSE_NO` pair, the release now happens just before the new first frame instead of at the start of the gap. On a SoC that blanks the layer when the producer disconnects, that shows up as a new black flash *immediately before* the new picture. Criterion 4 catches it only if the visual check includes NO pairs. Make sure it does.
- **N6.** The reuse rate on mixed providers could be raised by configuring the video codec's maximum size at the display size (a `getCodecMaxValues` override). That costs memory on 1 GB devices. It is a later experiment, not part of this one.
- **N7.** AFR "Always" switches the display mode per channel through our `DisplayModeController`. Media3's frame-rate discard applies only to `ONLY_IF_SEAMLESS` (M6), so a kept codec goes through our mode switch. I expect no problem but could not verify it. Include a 25 → 50 fps pair with AFR Always in one device run.
- **N8.** WP sizes look low. The controller changes (B1 disarms in five places plus `onForegroundChanged`, R3, R7 with its own extension, trust bits in `onPlayerFailure`/`onStall`/`fail`, `Plan`, `endSession`) are more likely 100–150 lines than 50. `StatsCollector` (S6) and WP-0 (S1) are missing. WP-4 should be mandatory (S9). The files remain disjoint. WP-2's `PlayerDebugHooks` depends at compile time on WP-1's `PlaybackExperiments`, as the design says. The `KeepDecodersOffTest` golden journal has to be recorded on `main` before WP-1 touches the controller.
- **N9.** The trust bit must be cleared at the **top** of `onPlayerFailure`, before the ring-exception routing that can `startCycle` (`:1298-1318`). Otherwise the ring relaunch could arm on a suspect codec.

---

## The pre-existing defect (§9): confirmed, and broader than stated, but partly wrong in its list

**Confirmed.** In a buffered cycle, `executeBuffered` returns false for `Reprepare` / `SoftwareVideoAndReprepare` / `DisableTunnelingAndReprepare` / the audio repairs (`:2849`). `execute` then calls `restart` → `launchSession` → reader plan (`:757`) → `endSession(stopPlayer = false)` (`:760`) → `setMediaSource` + `prepare` with no `stop()` (`:2466-2468`). Media3 disables without a reset (M13, verified at `ExoPlayerImplInternal.java:1061-1068`, `:2655`, `:1859-1872`), and the kept codec is reused through `canReuseCodec`. On the same channel that is always a YES.

**Affected:**
- `SoftwareVideoAndReprepare` after a stall: the selector is never asked again (`MediaCodecRenderer.java:600-604`), so the switch does nothing.
- `Reprepare` after `DECODER_STUCK`/`FROZEN_VIDEO`: a flush, not a new decoder.
- **Also** the user's decoder-override picker (`setVideoDecoderOverride`, `:2146-2149`) and a settings change of the video decoder mode via `reapplySettings` (`:596-608`). Both are user-visible no-ops while Pause and rewind is on.

**Not affected** (the design's §9 list is too broad here):
- `DisablePassthroughAndReprepare`: bypass is torn down on disable (`MediaCodecRenderer.java:858-859`), and the next format initialises a codec because the gate now refuses bypass.
- `StereoAndReprepare`: the `AudioTrack` and the sink configuration are rebuilt anyway.
- `SoftwareAudioAndReprepare`: a move to FFmpeg resets the MediaCodec audio renderer (M12).
- `DisableTunnelingAndReprepare`: `onEnabled` releases the codec on a tunnelling change (M8).
- Any repair that follows a renderer exception: Media3 already forced a reset (M11).

**Severity.** Pause and rewind is on by default (`SettingsStore.kt:158`), so this hits the default configuration.

**Recommendation.** Fix it separately, before the flag, with its own small review. For video-decoder-class decisions only (stall-driven `Reprepare`, `SoftwareVideoAndReprepare`, the decoder override, and a reapply that changes the video mode) in a buffered cycle, stop the player before the reader re-prepare, accepting the lost last frame there. It changes OFF behaviour on purpose, so it cannot ride on this feature. B2 depends on it.

---

## The proposal's key Media3 claims

| # | Claim | Status | Evidence / correction |
|---|---|---|---|
| M1 | `stop()` resets the renderers only when foreground mode is off; it always disables them | **Confirmed** | `ExoPlayerImpl.java:1122-1126,2161-2179`; `ExoPlayerImplInternal.java:2041-2050,2072-2104` |
| M2 | `true` is a posted message; `false` blocks until handled, up to `releaseTimeoutMs`; a timeout stops the player with `ERROR_CODE_TIMEOUT`; repeated values are ignored | **Confirmed**, with a consequence the design misses | `ExoPlayerImplInternal.java:612-626,316`; `ExoPlayerImpl.java:1107-1119`. The timeout calls `stopInternal(error)`, which **stops whatever is playing** and fires `onPlayerError`. That is harmless in R2/R3 (no session) but not in R4 (S4). The wait also includes queueing behind the current `doSomeWork`. |
| M3 | `false` resets only renderers that are not enabled | **Confirmed** | `ExoPlayerImplInternal.java:2011-2023`; `RendererHolder.java:755-774` (only renderers with `requiresReset`, set on enable `:532`); `BaseRenderer.java:267-271` |
| M4 | Disable flushes and keeps the codec; reset releases it; the selector is asked only at codec creation | **Confirmed** | `MediaCodecRenderer.java:855-874,890-907,600-604,1041-1065`. The MTK release-instead-of-flush workaround applies only with a `VideoSink` (`MediaCodecVideoRenderer.java:1712-1724`), which we do not use. |
| M5 | Reuse NO → re-initialise at once after a flush; YES keeps the codec | **Confirmed** | `MediaCodecRenderer.java:1871-1925,2235-2242,2628-2631`, `codecReceivedBuffers = false` `:1130`. Consequence: the release moves onto the critical path (S1). |
| M6 | Video reuse rules, `codecMaxValues` from the first format, SPS/PPS → WITH_RECONFIGURATION | **Confirmed** | `MediaCodecInfo.java:466-530`; `MediaCodecVideoRenderer.java:1413-1439,2720-2741` |
| M7 | Audio reuse rules, including "bypass now possible" | **Confirmed** | `MediaCodecInfo.java:531-603`; `MediaCodecAudioRenderer.java:524-546` |
| M8 | A tunnelling change releases the codec | **Confirmed** | `MediaCodecVideoRenderer.java:954-962,1268-1272` |
| M9 | The `AudioTrack` never survives | **Confirmed** | `MediaCodecAudioRenderer.java:792-805`; `DefaultAudioSink.java:1588-1609` |
| M10 | The FFmpeg decoder never survives | **Confirmed** | `DecoderAudioRenderer.java:751-765` |
| M11 | Renderer and runtime errors force a reset; IO errors do not | **Confirmed** | `ExoPlayerImplInternal.java:965-971,1001-1008,1016-1025`. Also relevant to S5. |
| M12 | `enableRenderers` resets renderers that are not selected | **Confirmed** | `ExoPlayerImplInternal.java:3662-3685` |
| M13 | `setMediaSource` without `stop()` disables without a reset, and the codec is reused | **Confirmed** | `ExoPlayerImplInternal.java:1061-1091,2604-2655,1832-1872` |
| M14 | A reused codec still reports first frame and size | **Confirmed** | `MediaCodecVideoRenderer.java:1202-1216` (first-frame flag, size `UNKNOWN`), `:975-993` (`onStreamChanged` on enable) |
| M15 | Javadoc conditions | **Confirmed** | `ExoPlayer.java:1934-1963`. The Javadoc also says that switching without a gap needs no foreground mode, which is option (b). |
| M16 | Analytics callbacks exist | **Confirmed** | used in our `StatsCollector.kt:150-187` and `PlaybackWatchdog.kt:319-326` |
| §3 (a) | Option (a) avoids option (b)'s race with the cancelled transport | **Wrong (overstated)** | The race narrows but remains (S5) |
| §3.1 | Disarm at first media "releases nothing" and is "a single message round trip" | **Partly wrong** | It releases nothing (M3), but it waits behind playback-thread work, and a timeout stops the live session (S4) |
| §5 | "The hold never outlives the zap" | **Wrong as designed** | B1 |
| R6/R7 | Every ladder restart and the R7 fallback get a full reset | **Wrong for buffered cycles** | B2, §9 |
| §4.4 | The last frame should stay "at least as reliably as after a release" | **Unverifiable** | A vendor-flush question; plus N5 for NO pairs |
| §0 | The gain is the video codec init | **Partly wrong** | On YES it is the init plus any release that was not overlapped; on NO it is a likely *loss* of one release (S1) |

---

## Is it worth building?

**Not as designed, and not before a cheap measurement.**

The realistic upside is the video decoder's init time (plus some release time) on the zaps where Media3 accepts reuse:
- same MIME;
- same colour;
- resolution not above the codec's configured maximum;
- an adaptive decoder for size changes.

On a real provider's mix of resolutions and codecs, that is a fraction of zaps. On the rest, ON probably costs one codec release on the critical path. The `AudioTrack`, usually the slowest audio piece, is rebuilt in both arms. A plausible net figure is a few tens of ms at P50 against a ~1 s zap, which is below the design's own 80 ms / 10 % bar. That estimate is unverified.

Against that:
- about 300 lines of production code and 550 of tests;
- a permanent second decoder lifecycle in the controller;
- the two blockers above.

The cheaper path is **WP-0** (S1): about 20 lines, no behaviour change. It records the decoder init durations and the stop → prepare gap per zap, in today's build, on the Stick.
- If the median video + audio init is below about 80 ms, close H1 as measured and do not build.
- If it is above, build with B1, B2 (after the §9 fix), S2–S8 and S11 applied, and run the measurement as amended in S9 and S10.

## What I could not establish

- Codec init and release durations, and whether the OMX AVC/HEVC decoders on the Fire TV Stick report `adaptive`. Both decide the reuse rate and the gain.
- Whether vendor `flush()` clears the reference-frame buffer (the S9(b) mixed-frame risk), and whether the SoC keeps the last frame after flush or release (m4 §1933).
- How often S5's race loses on real hardware.
- Whether `dumpsys media.resource_manager` lists per-pid codecs on Fire OS 7/8.
- Whether Robolectric's shadow codec supports `flush()` well enough for WP-3's init/release counts (the design's own open point).
- The behaviour of a kept codec across an AFR "Always" display-mode switch (N7).
