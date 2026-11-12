# Review: decoder repairs with the rewind buffer on, and per-zap decoder timing (WP-0)

Commit under review: `9c59cba` ("Make video-decoder repairs work with the rewind buffer on; time decoders per zap"), parent `5d1f900`.
Reviewer: independent playback review, 2026-11-11. Checked against our code and the Media3 1.11.1 sources (`media3-exoplayer-1.11.1-sources.jar`, unzipped; line numbers below are from that jar).

**Verdict: merge after fixes.** Part (1) is correct and safe; no blocker. Part (2) changes no behaviour, but as it stands the measurement cannot answer the question its doc asks ("per channel kind"), and the on-TV reading path does not show the figures. Fix those two before the owner runs the Stick session, or the session has to be run again.

Counts: **Blocker 0 · Should-fix 3 · Later 7.**

---

## Blockers

None.

---

## Should-fix

### S1. The zap record cannot be split by channel kind, but the decision rule asks for exactly that
- **Where:** `core/src/main/kotlin/app/thebox/core/playback/diag/ZapDecoders.kt:15-25` (fields), `docs/measure/keep-decoders-wp0.md:43-54`.
- **Scenario:** the owner zaps about 120 times across SD H.264, HD H.264, HEVC and the AAC / MP2 / AC-3 audio kinds, as the doc says, and exports. Each line has the decoder *name* (`OMX.MTK.VIDEO.DECODER.AVC`, `c2.android.aac.decoder`) and the container (`TS`). Nothing says whether a zap was 576i or 1080i, and the export carries no channel name or stable key (correctly, for privacy), so SD and HD H.264 cannot be told apart. MP2 and AAC can be told apart only if the platform uses different decoder names. The "median per kind" in the rule cannot be computed, and neither can the SD→HD / H.264→HEVC "could not be reused" pairs the doc tells the owner to mix in.
- **Fix:** record the video sample MIME and height (and interlaced flag if cheap) and the audio sample MIME at init, for example from `onVideoInputFormatChanged` / `onAudioInputFormatChanged` (`AnalyticsListener`, main thread, once per format) into two meter fields, and print them in `summary()` (`video=avc 576 OMX.MTK… 38 ms`). Neither contains a URL, host or credential. Optionally also print the *previous* zap's video MIME/height, so the reusable pairs can be counted directly.

### S2. The Diagnostics screen does not show the figures the doc tells the owner to read there
- **Where:** `ui/src/main/kotlin/app/thebox/ui/diag/DiagPages.kt:270-274`, `ui/src/main/kotlin/app/thebox/ui/settings/SettingsRowViews.kt:179`, `docs/measure/keep-decoders-wp0.md:21-25`.
- **Scenario:** a non-storage `RowKind.Info` row joins all its lines with `"  ·  "` into **one** `Line(...)` (`maxLines = 1`, ellipsis), at `RowDesc` = 22 design px. The newest zap's line alone is now about 110 characters (`TS · ready 420 ms · first frame 650 ms · ok · decoder init: video 38 ms, audio 4 ms · prepare to first frame 512 ms`), roughly 1,150–1,250 design px at 22 px sans, wider than the row's label column. The row shows the newest zap truncated, typically cut inside or before "decoder init…", and the other nine zaps are never visible. The doc says "It shows the last 10 changes, newest first". That was already untrue for the old, shorter lines (they were joined and clipped too); WP-0 makes it worse, and the doc now sends the owner there. It is not a layout regression elsewhere: the row height is fixed and nothing overlaps.
- **Fix:** either show the zap list as one line per zap (the storage pages' `isStorageInfo` path already does this: give `diag.zaps` the same per-line treatment and a growing height), or drop the "On the TV" section from the doc and say the export is the only reading path. If kept on screen, shorten the decoder part to `· init v38 a4 · p→f 512` so one zap fits on one line at 22 px.

### S3. The buffered stuck-position repair is still a flush, unlike the unbuffered one
- **Where:** `player/src/main/kotlin/app/thebox/player/controller/PlaybackControllerImpl.kt:1822-1823` (`isStuckDecoderStall`), ladder row L27a `core/.../errors/ErrorLadderImpl.kt:467-468`.
- **Scenario:** Pause and rewind on, a channel plays (READY, `isPlaying`), bytes arrive, but the position stops advancing for 4 s (`checkPositionStuck`, b2). Typical causes are a stuck audio decoder or a sink/clock that stopped consuming; the playback clock is the audio renderer's. The verdict is `POSITION_STUCK` → L27a `Reprepare`. With the buffer off that restart goes through `stop()` and gets new audio and video codecs. With the buffer on, `isStuckDecoderStall` returns false for `POSITION_STUCK`, so the reader re-prepare disables without a reset (`ExoPlayerImplInternal.java:1061-1068`; `MediaCodecRenderer.onDisabled` → `flushOrReleaseCodec`, `:855-864`, `:1041-1054`) and the same codecs are reused. L27b then repeats it with a compat raise, and L27c fails the channel. That is the same defect class the commit fixes, left in place for the third L27 stall kind. The `AudioTrack` is rebuilt either way (review M9), so the remaining difference is the audio (and video) codec.
- **Fix:** include `StallKind.POSITION_STUCK` in `isStuckDecoderStall`, and rename the flag to `freshDecoders` (it resets every renderer anyway; the name now promises video only). The cost is one visible gap on a picture that is already stuck. If the lead prefers to keep the last frame here, record that as a decision in `docs/design/m6-timeshift.md` §6 step 3 together with the reason, because today the two modes repair this stall differently without saying so.

---

## Later

### L1. Late decoder callbacks from a superseded zap are counted in the next zap
`ZapDecoders.kt:105-117`. A codec init is reported by a post from the playback thread. With a fast CH+ CH+, zap A is superseded → `finish(A)` → `begin(B)` → `stop()`; an init of A that the playback thread completed just before the stop arrives on main after `begin(B)` and is added to B (`videoInits = 2`, the time summed). The doc's 5 s pace makes it rare. **Fix:** ignore init callbacks while `firstPrepareAtMs == NONE` (no init of this zap can precede its first `prepare()`), or compare `eventTime.realtimeMs` with the zap's first prepare time.

### L2. Missing audio counts as 0 ms in the decision figure
`ZapDecoders.kt:27`. `initTotalMs` adds `audioInitMs ?: 0`. For passthrough that is correct (no decoder). If an audio init arrives after the first video frame (the meter closes at first media), it is dropped and the zap looks cheaper, which biases the median towards "do not build". I could not make this happen on paper for TS: `ProgressiveMediaPeriod` prepares only when every track has a format, so both renderers initialise in the same work loop before the first frame. Not reproduced. **Fix (cheap):** have `summary()` print `audio=- ` distinctly from `audio=passthrough`, or keep the meter open until first audio as well as first video.

### L3. Ladder repairs inside a zap inflate the summed init
A zap that hits L27/L28/L29 before first frame reports the sum of two or three inits. The median absorbs a few, but the doc should say to drop zaps with `(n inits)` from the median, or the meter should report the first init separately.

### L4. `videoReleaseMs` can belong to the wrong decoder
`ZapDecoders.kt:120`. From an audio-only (radio) channel there is no old video decoder; the first release seen after the stop is then a ladder repair's in the new zap. **Fix:** ignore releases after the zap's first prepare.

### L5. The buffered stop bypasses `stopExo()`
`PlaybackControllerImpl.kt:784` calls `player?.stop()` directly. The meter ignores it anyway (`onStop` counts only the stop before the first prepare), so the numbers are the same; but the wrapper's comment says it is *the* stop. **Fix:** call `stopExo()` there for one code path.

### L6. The override picker always stops, even when the effective mode does not change
`PlaybackControllerImpl.kt:2205`. Picking the mode that is already in effect (for example "Automatic" when the global setting is also automatic, or any choice after L29 set `c.softwareVideo`, which wins in `effectiveVideoMode`) costs a visible gap for nothing. **Fix:** use the same test as `reapplySettings`: `codecPolicy.videoMode != effectiveVideoMode(c)` after the override is stored.

### L7. 120 zaps may not fit in the log ring
`LogLimits.LOG_RING_LINES = 4_000`. A zap writes a few dozen lines (tune, resolve, open, ready, first frame, zap, decoders, display mode and so on), so 120 zaps plus playback logs is near the limit, and the doc says the "Logs" section is "not limited to 50". Not measured. **Fix:** tell the owner to export after every 50 zaps (the doc already suggests it as one option; make it the instruction).

---

## What is sound

**(1) The fix itself.**
- `stop()` releases the video codec at that point. `ExoPlayerImpl.stop()` → `stopInternal(null)` → `internalPlayer.stop()` (`ExoPlayerImpl.java:1122-1126, 2161-2180`) → `MSG_STOP` → `stopInternal(forceResetRenderers = false, …)` → `resetInternal(resetRenderers = false || !foregroundMode, …)` (`ExoPlayerImplInternal.java:801-803, 2041-2050`). `resetInternal` disables every renderer first, then calls `RendererHolder.reset()`, which resets each renderer that was enabled (`requiresReset`, `RendererHolder.java:532, 755-774`). `MediaCodecRenderer.onReset` → `releaseCodec()` (`MediaCodecRenderer.java:867-874`). We never call `setForegroundMode` (grep of `player`, `core`, `app`: no hits), so it is always a reset.
- Without the stop, the codec really is kept: `prepareInternal` and the playlist reset run with `resetRenderers = false` (`:1061-1068`), `onDisabled` flushes (`:855-864, 1041-1054`), and `onInputFormatChanged` with a live codec goes to `canReuseCodec` (`:1843-1875`), never to the selector. The bug the commit describes is real.
- Order: `launchSession` takes the pin (`pinStart`, which reads `player.currentPosition`) **before** the stop (`:771` then `:784`), so the stop's position handling cannot move the viewer. `endSession(stopPlayer = false)` ends the reader transport first, then the stop; the writer, its broker lease, the ring, its generation and `c.ts` are not touched by `player.stop()` (the player holds only the reader's `DataSource`, already ended). One connection is kept; the tests check the writer's single connection and no restart.
- What `stop()` resets and why it does not matter here: `playWhenReady` is kept by Media3 (`resetInternal` copies it) and the reader sets `playWhenReady = !c.paused` after display-mode settle anyway (`:2536-2541`); position, track groups and selection are replaced by `setMediaSource(resetPosition = true)` + `prepare()`; `TrackSelectionParameters`, playback parameters, audio session id and the surface are player-level and survive; audio focus is abandoned and requested again (`ExoPlayerImplInternal.java:2048`, `:1070`), as on every unbuffered restart. Cues: `endSession` already cleared them.
- The IDLE callback: `ExoPlayerImpl.updatePlaybackInfo` flushes listeners synchronously inside `stop()`, while `session` is already null, so `onPlayerState` (`:1281-1283`) returns before reading the state; IDLE is `else -> Unit` anyway. `stop()` raises no `onPlayerError` (error is null; `resetError = false` keeps an existing one, which `prepare` clears). Late playback-thread posts from before the stop (an error, a state change) also land while `session == null` or are masked by Media3's pending-operation acks. Nothing can be read as a user stop or end of stream: `STATE_ENDED` is not emitted by a stop. The watchdog is stopped in `endSession` and restarted by `runReaderSession`; the switch window is not touched.
- The decision list. Every `launchSession` caller: `startCycle` (new cycle, not reader), `selectServer` (unbuffered only), `reapplySettings` (flag when the mode changed), `restart` (ladder), `setVideoDecoderOverride` (flag), `launchReader` (seeks, live, boundaries; no flag, correct). Every ladder action in `execute`: only `Reprepare` (stall kinds DECODER_STUCK / FROZEN_VIDEO) and `SoftwareVideoAndReprepare` (no error) carry the flag; the rest either change renderer configuration that Media3 already acts on (tunnelling releases in `MediaCodecVideoRenderer.onEnabled`, `:954-962`; bypass torn down on disable; FFmpeg is another renderer) or follow a player error. Every player error that reaches `onPlayerError` has already reset the renderers: renderer and runtime errors `stopInternal(true, …)` (`ExoPlayerImplInternal.java:970, 1007`), IO errors `stopInternal(false, …)` which resets with foreground mode off (`:1024`), and Media3's stuck-player detector stops through `ExoPlayerImpl.stopInternal(error)`. Recoverable renderer errors are retried inside Media3 and never reach the ladder unless the retry fails, which then resets. Surface re-creation, AFR and resume from background do not go through a reader re-prepare (Media3 handles the surface; resume is a new cycle), so no flag is missing there, apart from S3.
- The author's claim that `SoftwareVideoAndReprepare` follows only a player error today: confirmed. L29's condition is `videoDecoderSignal` (`ErrorLadderImpl.kt:277-278, 485-488`): `DecoderInit`, `FormatUnsupported`, `DecodingFailed`, all classified from a `PlaybackException`. No stall reaches L29. The `error == null` guard is dead code today and right for later.
- Ladder counting: the extra stop changes no counter. `stallRecoveries` and the 5-minute `decoderWindow` still escalate L27a → L27b → L27c (`ErrorLadderImpl.kt:467-476`), so a truly dead decoder fails the channel after two repairs; no loop.
- Buffer OFF: `readerOnly` is false, so `endSession(stopPlayer = true)` runs as before and the new branch is skipped; one stop per restart, no `fresh_video_decoder` tag. The test `withTheBufferOffEveryDecoderRepairStopsOnceAsBefore` encodes it.
- Tests: the new controller tests fail on the parent's production code (checked by putting `5d1f900`'s `PlaybackControllerImpl.kt` under the new tests; see "Verification"). They assert the order stop → `setMediaSource` → `prepare`, the same RAP, the PLAYHEAD pin, the ring generation, one writer connection and no recorder restart, and a paused viewer staying paused.

**(2) WP-0.**
- Threading: our player is built on the main looper (`ensureEngine`, `playerFactory.create(Looper.getMainLooper())`), and `AnalyticsCollector` delivers through a `ListenerSet` on the application looper, so every callback reaches the meter on main. The meter is unsynchronised and main-only, which is right.
- No behaviour change: `stopExo()` does exactly what `player?.stop()` did; `onPrepare` is called after `prepare()` and touches no player state; the three `AnalyticsListener` overrides only write meter fields; the extra log line is one `BoxLog.i` per zap. `theCallbacksChangeNoPlayerCall` compares the player journal with and without callbacks.
- Allocation: none per event (field writes); one `ZapDecoders` and one string per zap.
- FFmpeg audio: `DecoderAudioRenderer.maybeInitDecoder` dispatches `decoderInitialized` (`DecoderAudioRenderer.java:881-891`), so `onAudioDecoderInitialized` fires with the FFmpeg decoder's name. Passthrough uses MediaCodec bypass (`initBypass`), so no init is reported, as the doc says. Radio: no video renderer is enabled, video stays `-`.
- `initializationDurationMs` is `createAdapter` (create, configure, start) and nothing else (`MediaCodecRenderer.java:1417-1431, 1459-1464`); the doc describes it correctly.
- Redaction: decoder names and millisecond figures only; no URL, host, credential or channel name. The export line adds nothing beyond `tune=<id>`, which was already there.
- Catch-up and time-shift: an archive tune is a cycle like any other and is metered; a buffered cycle's first session is a normal `runSession` with the ring, so `onPrepare` is called; reader re-prepares inside a zap call `onPrepare` too (the "last prepare" figure follows them).
- Is the measurement meaningful for the decision? Mostly. Today every zap releases and re-creates both codecs (the new cycle's `launchSession` is never reader-only), so the init time is exactly what a successful reuse would save, and "under ~80 ms → do not build" is a fair upper-bound rule for the init part. What it does not capture: the codec's own first-output latency after configure (a fresh hardware decoder may want more input before its first frame than a flushed one; partly visible in "first frame after prepare", but mixed with network and extractor time), and the release of the old codec, which runs on the playback thread at the stop and is mostly hidden behind resolve and connect. The doc names the release; it should name first-output latency as not measured, so a borderline result (70–100 ms) is not read as decisive. The doc is not otherwise misleading, apart from S1 and S2.

---

## Could not verify

- Whether the last frame stays on screen while the codec is released, per SoC (Fire TV Stick MediaTek, Amlogic, Sony MTK). The commit's doc says "expected, unverified"; I agree it is unverified. It is the same picture an unbuffered ladder repair shows today.
- That audio init always precedes the first rendered frame on the device (L2): reasoned from `ProgressiveMediaPeriod`, not observed.
- The real width of the Diagnostics label column at 1920 px (S2): estimated from the 22 px style and the 560 px value cap, not measured on a device or in a screenshot test.
- How many log lines a zap writes on a device (L7).
- The IDLE-callback path is not exercised by the tests: `FakeExoPlayer.stop()` sets `STATE_IDLE` without calling listeners. My conclusion that it is harmless rests on reading `onPlayerState` and Media3's synchronous flush, not on a test. A test where the fake's `stop()` emits `onPlaybackStateChanged(IDLE)` would close this.

## Verification

- `./gradlew --max-workers=2 -q :core:test --tests '*playback*' :player:testDebugUnitTest` at `9c59cba`: passes (exit 0).
- The parent's `PlaybackControllerImpl.kt` (`5d1f900`) placed under the commit's tests (`ControllerTimeshiftTest`, `ZapDecodersRecordTest`): 8 of 56 fail, namely the 7 new or changed fresh-decoder tests (`aDecoderStuckStall…`, `aFrozenVideoStall…`, `aRaisedCompatReprepare…`, `softwareVideoAfterAStall…`, `everyLaunchSessionCallSiteKeepsThePositionBehindLive`, `aReapplyStopsThePlayerOnlyWhenTheDecoderModeChanged`, `aPausedDecoderOverrideComesBackPausedAtThePin`) and `eachZapRecordsItsDecoderInitsAndTheOldDecodersRelease`. `withTheBufferOffEveryDecoderRepairStopsOnceAsBefore` and `repairsThatAlreadyTookEffectStillHoldTheLastFrame` pass on the parent, which is right: they guard against regressions. The file was restored afterwards (`git status`: only this review is untracked).
- No instrumented tests run. Nothing committed.

---

## Fix pass (2026-11-11)

On top of `9c59cba`, uncommitted (the lead commits). Lead rulings applied as below.

| Finding | Disposition |
|---|---|
| **S1** zaps cannot be split by kind | **Fixed.** `ZapDecoderMeter` records the last video MIME, height and frame rate and the audio MIME and channel count from the controller's `onVideoInputFormatChanged` / `onAudioInputFormatChanged` (main thread, once per format). `ZapDecoders.summary()` prints them before the decoder name: `video=avc 576 25fps OMX… 38 ms, audio=mp4a-latm 2ch c2… 4 ms`. No channel name, stable key or URL. The previous zap's kind is the line before, so pairs need no extra field. Tests: `ZapDecodersTest.theLogLineCarriesEveryFigureAndTheChannelKind`, `…theMeterSumsTheZapsInits…`, `ZapDecodersRecordTest.eachZapRecords…`. Measure doc updated. |
| **S2** Diagnostics joins ten zaps into one clipped row | **Fixed.** The `diag.zaps` row now carries one line ("Newest first, one row each" / "No channel changes yet"), followed by one read-only `RowKind.Info(emptyList())` row per zap (`diag.zap.<i>`, not in the registry, like the other Diagnostics rows), newest first. The label is the existing zap line (`TS · ready 420 ms · first frame 650 ms · ok`); the description, one line at `RowDesc`, is `Init: video avc 720 38 ms · audio mp4a-latm 4 ms · frame 512 ms after prepare` (about 75 characters; no decoder names or frame rate). Row height is the standard `ROW_H_DESC`. Test: `DiagPagesTest.aZapRowCarriesTheZapAndItsDecoderCostOnTwoShortLines`. Doc says what the screen shows. |
| **S3** buffered POSITION_STUCK still a flush | **Fixed.** `isStuckPipelineStall` (renamed) includes `POSITION_STUCK`; the flag is renamed `Plan.freshDecoders`, the OPEN tag `fresh_decoders`, the log line "…for new decoders". New test `aPositionStuckStallStopsThePlayerBeforeTheReaderReprepareAndKeepsThePlace`. `repairsThatAlreadyTookEffectStillHoldTheLastFrame` changed: its plain `Reprepare` case used a `POSITION_STUCK` stall and now uses an `AUDIO_NOT_STARTED` stall (not an L27 stall); the list of decisions (tunnelling, stereo, passthrough, software audio, plain Reprepare) is unchanged. `m6-timeshift.md` §6 step 3 and `m2-playback-core.md` §4.3 updated. |
| **L1** late callbacks of a superseded zap | **Fixed.** `begin(cycleId)` / `finish(cycleId, …)` (a finish for another cycle returns null), and decoder and format events count only between the zap's first `prepare()` and `finish`. A post of the old zap delivered after `begin` lands while the new zap resolves (the resolve suspends on IO), before its first prepare, and is dropped. Residual: a new cycle whose session reached `prepare()` without suspending would still count a very late post; every new cycle passes a suspending IO hop (facts and resolve) first, so none does today. Tests: `ZapDecodersTest.aLateCallbackOfTheSupersededZap…`, `ZapDecodersRecordTest.eachZapRecords…` (resolve made to suspend in the fixture). |
| **L2** missing audio counts as 0 ms | **Fixed.** `initTotalMs` is null unless both video and audio inits were seen; the line prints `audio=ac3 6ch - -` and `init total -`. The doc leaves such zaps out of the median. Test: `…missingFiguresAreDashesNeverZero…`. |
| **L3** ladder repairs inflate the sum | **Fixed (doc).** The init count was already printed; the doc now says to leave out zaps with `(2 inits)` or more and to count them. |
| **L4** release attributed after radio | **Fixed** (the lead listed it as open; the fix was one condition): a release counts only between the zap's stop and its first prepare. Test: `…aLateCallbackOfTheSupersededZapAndAReleaseAfterRadioAreNotCounted`. |
| **L5** buffered stop bypasses `stopExo()` | **Fixed.** The buffered fresh-decoder stop now goes through `stopExo()`. It does not couple the two changes' behaviour: the meter's `onStop` ignores any stop after the zap's first prepare, and a buffered re-prepare always follows one. |
| **L6** override picker always stops | **Fixed.** `setVideoDecoderOverride` and `reapplySettings` share `videoModeChanged(c)`: the stop happens only when the effective mode differs from the codec policy's. Picking the mode in effect re-prepares at the pin without a stop (unbuffered still always stops, as before). Test: `aDecoderOverrideToTheModeInEffectDoesNotStopThePlayer`. |
| **L7** log ring at 120 zaps | **Open (not measured).** The measure doc now says to export after every ~30 zaps. The ring size (`LOG_RING_LINES = 4,000`) and lines per zap on the device are unchanged and unmeasured. |
| first-output delay not measured | **Doc.** Added to Limits; a median of 70–100 ms is "not decisive" in the decision rule. |
| IDLE callback path untested with the fake | **Open.** `FakeExoPlayer.stop()` still does not emit `onPlaybackStateChanged(IDLE)`; the reasoning above stands, no test added. |
