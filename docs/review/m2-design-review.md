# M2 playback-core design: adversarial review

Reviewer pass over `docs/design/m2-playback-core.md` (proposed 2026-10-13), done 2026-10-13. I checked it against `00-master-plan.md` §3/§6, `review/architecture-review.md` B1/B4/B6/D/E, `design/m2-media3-api-notes.md`, the M1 code (`core/`, `data/`), `player/`, `ffmpeg/`, `tools/fakepanel/` and these sources:

- Media3 1.11.1 `-sources.jar`s: common, container, datasource, datasource-okhttp, exoplayer, exoplayer-hls, extractor, session, ui-compose, test-utils, test-utils-robolectric. They are unzipped in the session scratchpad under `src/<module>/`.
- OkHttp `okhttp-jvm-5.5.0-sources.jar` from Maven Central.
- `lifecycle-process` 2.10.0 sources (Gradle cache).
- The FFmpeg tree the AAR is built from (`tools/ffmpeg/work/ffmpeg`).
- `platforms/android-37.0/data/api-versions.xml`.

Media3 paths are relative to `androidx/media3/`, and line numbers are from 1.11.1. I applied every blocker and should-fix item in place in the design doc, and marked each changed passage `(reviewed 2026-10-13: …)`. Appendix A gained A.10 status notes and a new A.12 with the facts verified here. Appendix B adds and removes the matching constants. I did not apply the nice-to-haves, except the few that sat inside a passage I was already editing.

---

## Summary

| | Count |
|---|---|
| Blockers (applied) | 6 |
| Should fix (applied) | 14 |
| Nice to have (not applied unless noted) | 16 |
| Claims verified correct | 33 |
| Lead decisions needed | 16 |

The design is strong. The main bets hold:

- in-source reconnect;
- `LENGTH_UNSET` so that no `Range` is ever sent;
- the session-URI placeholder;
- per-MIME FFmpeg selection through the codec selector;
- the forwarding audio gate;
- a mutable `LoadControl`;
- AFR on API 31 vs the window path below it;
- `PlayerSurface` with a surface-only `ForwardingPlayer`.

The source confirms all of them. The blockers are what the design does not see:

- the TS continuity-counter behaviour at the splice;
- what "first frame" means for radio;
- what a singleton player does to `PlayerSurface`;
- what Android does with `ON_STOP` on standby and with a declared `MediaSessionService` on Fire OS;
- one detector that fires on every slow network.

---

## Blockers (applied)

**B1. The reconnect splice corrupts an access unit on about 40 % of reconnects.**
- *Where:* §3.3.1 ("Each PID's continuity counter jumps, `TsExtractor.read` detects the discontinuity…"), §2.2 `TsPacketAligner`.
- *Problem:* a reconnecting server restarts each PID at an arbitrary CC. `TsExtractor.read` (`extractor/ts/TsExtractor.java:494–505`) handles the two cases differently:
  ```java
  if (previousCounter == continuityCounter) { // Duplicate packet found.
    tsPacketBuffer.setPosition(endOfPacket); return RESULT_CONTINUE;
  } else if (continuityCounter != ((previousCounter + 1) & 0xF)) { // Discontinuity found.
    payloadReader.seek();
  }
  ```
  - With probability 1/16 per PID, the first new packet is dropped as a "duplicate". It is usually the payload-unit-start, so the following packets are appended to the cut PES.
  - With another 1/16, the new data continues the cut PES directly.
  - With PAT, PMT, video and audio, about 1 − (14/16)⁴ ≈ 41 % of splices glue two PES together. The result is a corrupt frame, and on fragile SoC decoders a decoder error that the ladder then treats as a codec problem.
- *Fix applied:*
  - After `resync()`, the aligner forces a CC gap on every PID: the first payload packet gets CC = last emitted + 2, and later packets keep that offset.
  - `TsExtractor` then always calls `payloadReader.seek()`, and the PES reader skips to the next payload-unit-start.
  - Updated: the contract KDoc (§2.2), §3.3.1, key decision 3, and the WP-B tests (all 16 restart CCs, plus a Robolectric splice test through a real `TsExtractor`).

**B2. Radio channels, the HDR poster and HIDDEN tunes hit the 15 s switch timeout.**
- *Where:* §2.4 `streamSwitchTimeoutMs` ("No first frame within this after tune() → error card"), §3.1.1 step 8, §3.1.2 timeout row, §4.3 global rule, `LadderContext.firstFrameRendered`.
- *Problem:* an audio-only TS (radio groups are common in M3U playlists) never calls `onRenderedFirstFrame`. Neither does a tune with the video track disabled (§3.9.5 poster pre-disable via `knownHdr`, or §3.11 HIDDEN). The consequences:
  - The global rule turns the first transient error after 15 s into a Fail.
  - `recordWatched`, the ladder's healthy reset, `previousChannel` and the `ZapRecord` never complete.
  - L2/L7 treat the whole session as "before first frame".
- *Fix applied:* a "first media" milestone (new key decision 16 and D27):
  - `onRenderedFirstFrame` when a video track is selected and enabled;
  - otherwise the first `onAudioPositionAdvancing`.
  - Every "first frame" rule uses it; the `LadderContext` KDoc says so.
  - New WP-D test, and new I-20a (audio-only channel, panel ch 13).

**B3. A re-created `ExoPlayer` never gets the surface back, and the singleton leaks the Activity.**
- *Where:* §0.2, §3.10.1 ("released … after 5 min in the background … The next tune re-creates it"), §3.11, §2.6.
- *Problem:* `surfacePlayer` is a `ForwardingPlayer` and `SessionPlayer` a `ForwardingSimpleBasePlayer`. Both take the player in their constructor (`common/ForwardingPlayer.java:64`, `ForwardingSimpleBasePlayer.java:77`).
  - `PlayerSurface` attaches in `LaunchedEffect(view, player)` (`ui/compose/PlayerSurface.kt`, `PlayerSurfaceInternal`). Its key is the facade, which does not change, so a new hidden `ExoPlayer` never receives `setVideoSurfaceView`: audio plays over a black picture after any return from more than 5 min in the background.
  - The `MediaSession` keeps the released player.
  - Separately, `PlayerSurface` clears the view only when `player` becomes null, not on dispose. A process-lifetime player would therefore hold a destroyed Activity's `SurfaceView`.
- *Evidence that the release is not needed:*
  - `stop()` without foreground mode resets the renderers: `resetInternal(/* resetRenderers= */ forceResetRenderers || !foregroundMode, …)` and `loadControl.onStopped(playerId)` (`exoplayer/ExoPlayerImplInternal.java:2041–2048`).
  - The reset releases the codecs: `MediaCodecRenderer.onReset` → `releaseCodec()` (`:867–874`).
  - `DefaultLoadControl.onStopped` → `removePlayer` → `allocator.reset()` (`DefaultLoadControl.java:738–747, 917–923`).
- *Fix applied:*
  - The player lives for the process (no idle release; `IDLE_RELEASE_PLAYER_MS` removed; D26).
  - `SurfacePlayer` clears its view from the player when the view detaches from the window.
  - The re-targetable facade is documented for a later milestone that wants release back.

**B4. `ON_STOP` fires on standby, so the Home vs screen-off split never happens.**
- *Where:* §3.10.2.
- *Problem:* going to sleep stops the top Activity on AOSP, Android TV included (power key, CEC standby, `UiDevice.sleep()`). `ProcessLifecycleOwner` 2.10.0 then dispatches `ON_STOP` 700 ms after the last pause (`TIMEOUT_MS = 700`, `ProcessLifecycleOwner.kt:69`; dispatch `:110–131`). The "ON_STOP → `stop(BACKGROUND)` immediately" row therefore always wins: the 30 s `STANDBY_GRACE_MS` path is dead code, and I-20 would see `Stopped(BACKGROUND)`.
- *Fix applied:*
  - The `ON_STOP` handler checks `PowerManager.isInteractive()` and the display state. Interactive means Home, so stop now. Not interactive, or the display OFF/DOZE, means standby, so start the grace.
  - `MainActivity` gets `supportsPictureInPicture="false"`, since PiP is not in v1 and would change what `ON_STOP` means.
  - I-19 timing is relaxed to 2 s; I-20 asserts no BACKGROUND stop; new unit test.
  - Fire OS on CEC TV-off stays D7 (both paths lead to the grace).

**B5. A declared `MediaSessionService` crashes on Fire TV and resumes playback in the background.**
- *Where:* §3.10.4, D16, `player/src/main/AndroidManifest.xml` (today it declares `PlaybackService` exported with the `androidx.media3.session.MediaSessionService` action, `foregroundServiceType="mediaPlayback"`, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`).
- *Problem:*
  1. With a `MediaSessionService` declared and no `MediaButtonReceiver`, media3 gives the platform session a `PendingIntent.getForegroundService(...)` media-button intent on API < 31 (`session/MediaSessionLegacyStub.java:207–245`). After process death, a CEC/Bluetooth PLAY calls `startForegroundService(PlaybackService)`. `onStartCommand` (`MediaSessionService.java:524–555`) gets our session from `onGetSession`. The design's `onUpdateNotification` no-op means nothing calls `startForeground`, so the app crashes with `ForegroundServiceDidNotStartInTimeException` (ANR below 31). Media3 only takes its safe path (`stopSelfSafely`) for a `null` session (`:530–536, 578–608`).
  2. The controller keeps the session active after Home, and media keys go to the last active session. A CEC PLAY then reaches `SessionPlayer.handleSetPlayWhenReady` → `resume()` and re-tunes in the background.
- What holds: the override does stop the notification path, because the manager is only called when the deprecated 1-arg method ran (`MediaSessionService.java:785–786, 863–868`).
- *Fix applied:*
  - No service in the v1 manifest and no FGS permission (commit 0 item 6a). A bare `MediaSession` works: with no service and no receiver, media3 registers a runtime media-button receiver (`MediaSessionLegacyStub.java:224–235`).
  - `SessionPlayer` stops advertising and ignores play/next/previous below STARTED.
  - The session exposes a sanitised `MediaItem` (no URI).
  - v1.1/v2 re-add the service with a real FGS path.
  - Tests and day-0 check 8.

**B6. Detector b1 fires on every network slower than the stream.**
- *Where:* §3.7 b1 ("state BUFFERING for ≥ 6 s while `bytesTotal` grew by ≥ 256 KiB").
- *Problem:* that condition is a normal rebuffer on a slow link. On a link slower than the stream bitrate:
  - b1 fires `DECODER_STUCK` → `Reprepare` (dropping the buffer the user was waiting for);
  - then `Reprepare(raiseTsCompat)`, which persists level 1;
  - then Fail(`STREAM_UNDECODABLE`).
- *Evidence:* the fake panel's `slow: 100000` (I-16) is below the clip's ~265 KB/s rate, so 600 KB arrive in 6 s and the test as written ends in an error.
- *Fix applied:*
  - b1 also requires that the buffered position (`positionMs + bufferedDurationMs`) grew by less than `DECODER_STALL_MAX_MEDIA_GROWTH_MS` (500), i.e. bytes arrive but the extractor produces nothing.
  - I-16 asserts no `DECODER_STUCK`.

---

## Should fix (applied)

**S1. PCR-jump handling: leaks before the verdict, and the reaction time is wrong.**
- *Where:* §3.3.4, §3.3.5, §3.7, D3.
- *Problem:*
  - (a) The design runs the PCR check on packets as they enter the aligner, but step 1 hands out whole packets before the verdict. The audio PID usually precedes the next PCR-carrying video packet, so new-timestamp audio reaches the extractor first.
  - (b) A backward jump is worse than "frozen": `SampleQueue.largestQueuedTimestampUs` is a running max (`exoplayer/source/SampleQueue.java:877`). The buffered position stays high, the `LoadControl` stops loading, and the "not loading" stuck check needs a small buffered duration (`ExoPlayerImplInternal.java:1560–1570`). Only the 30 s backstop ends it.
  - (c) A forward jump is mostly ridden through when audio is present: `DefaultAudioSink.java:1039–1062` re-bases on a > 200 ms discontinuity and calls `onPositionDiscontinuity`, and `MediaCodecAudioRenderer.java:1222–1226` accepts the jump.
  - (d) After prepare, a fatal loader error reaches `onPlayerError` only via `maybeThrowRendererStreamError`, when a renderer cannot play (`ExoPlayerImplInternal.java:1502, 1557`). It arrives when the buffer drains, not in "~100 ms".
- *Fix applied:*
  - A post-splice hold: the aligner gains `heldBytes`/`releaseHeld`/`dropHeld`, the PCR tracker gains `awaitingPcr`, with `PCR_HOLD_MAX_BYTES`/`_MS`.
  - Timing note in §3.3.4; corrected text in §3.3.1, §3.7 and D3.
  - State-machine row for `PCR_JUMP`.
  - I-4/I-5 are measured from the rebuffer.

**S2. OkHttp close order and lease release.**
- *Where:* §3.3.6, §3.3.7, §3.5.2, §1.5.
- *Problem:* "close the body, then `call.cancel()`" costs up to 100 ms per zap and reconnect. For chunked or fake-length bodies, `close()` first drains for `DISCARD_STREAM_TIMEOUT_MILLIS` (`okhttp3/internal/http1/Http1ExchangeCodec.kt:460–467, 530–536`; constant at `internal/http/ExchangeCodec.kt:108`).
- The design never said how the broker learns the body is closed. OkHttp 5.5.0 has `Call.addEventListener` (`Call.kt:92–102`), and `callEnd`/`callFailed` fire after `RealCall.callDone` closed the socket (`RealCall.kt:408–437`).
- Missing caveats:
  - HTTP/2 cancel sends `RST_STREAM` and keeps TCP (`Http2ExchangeCodec.kt:124–127`).
  - DNS lookups do not unblock on cancel (`RealRoutePlanner.kt:168–173`).
- *Fix applied:* cancel first, then close; release from the per-call `EventListener` with the existing 300 ms grant-after-wait fallback; caveats documented.

**S3. Error classification would lose DNS/timeout kinds, and the policy cannot tell HLS from progressive.**
- *Where:* §3.3.3 step 3, §3.3.4 step 3, §4.1, §4.2, §3.1.3.
- *Problem:*
  - `OkHttpDataSource.executeCall` wraps every open failure as `IOException(ExecutionException(cause))` (`datasource/okhttp/OkHttpDataSource.java:429–452`). `HttpDataSourceException.createForIOException` tests only the outer type (`datasource/HttpDataSource.java:220–239`). So `UnknownHostException`, connect timeouts and `SSLException` all arrive as 2001. The §4.1 rows ("caused by `UnknownHostException`") would never match, `DNS_FAILURE` and its "Use secure DNS" step would never appear, and TLS would be retried as a reset.
  - A cancelled read surfaces as `SocketException("Socket closed")` → 2001, identical to a reset (`OkHttpDataSource.java:351–358`).
  - `ProgressiveMediaPeriod.onLoadError` always reports `C.DATA_TYPE_MEDIA` (`exoplayer/source/ProgressiveMediaPeriod.java:857–866`).
  - `Loader` wraps runtime exceptions from `load()` in `UnexpectedLoaderException` (`exoplayer/upstream/Loader.java:470–482`).
- *Fix applied:*
  - A root-cause walk.
  - The pre-emption/watchdog flag check before treating an `IOException` as reconnectable is now a "must".
  - One policy instance per session family.
  - Runtime exceptions mapped in `open()`; `InterruptedIOException` for interrupted broker waits.

**S4. `AdaptiveLoadControl`: the back buffer is read once, and default overloads throw.**
- *Where:* §3.6.1, §3.6.3, §3.14.
- *Problem:*
  - `ExoPlayerImplInternal.java:330–331` reads `getBackBufferDurationUs(playerId)` and `retainBackBufferFromKeyframe(playerId)` once, in its constructor, so "archive: 15 s via the volatile profile" is never honoured.
  - In `LoadControl.java` the `PlayerId`/`Parameters` overloads are `default` and forward to deprecated ones that `throw new IllegalStateException("… not implemented")` (`:148–160, 171–178, 232–243, 288–299, 322–333, 346–359, 392–427`).
- *Fix applied:* back buffer always 0 (`ARCHIVE_BACK_BUFFER_MS` removed); the overloads to override are listed; the delegate's target bytes is the device-class cap; a WP-C test checks that no overload throws.

**S5. Tracks without a format hang prepare.**
- *Where:* §0.8, §3.1.3, §3.8.5, Appendix A.2, Q10.
- *Problem:* `ProgressiveMediaPeriod.maybeFinishPrepare` returns until every sample queue has an upstream format (`ProgressiveMediaPeriod.java:978–986`). Three real cases never produce one:
  1. A PMT entry whose PID carries no data (common in restreams).
  2. Stream type 0x82 with `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS`. It is SCTE-27 subtitles on North American cable, so a `DtsReader` never syncs (`DefaultTsPayloadReaderFactory.java:174–186`).
  3. LATM with `audioMuxVersion == 1`. `LatmReader.parseStreamMuxConfig` calls `output.format` only in the version-0 branch (`extractor/ts/LatmReader.java:207–230`), so the design's "rejected with `ParserException`" is wrong.
- In all three the tune sits in BUFFERING and ends as a misleading error.
- DVB DTS uses stream type 0x06 plus a descriptor, which is mapped without the flag (`TsExtractor.java:878–896`).
- *Fix applied:*
  - A dead-track guard in `TsExtractorsFactory`: lazy track creation; `endTracks` is forwarded when every track has a format or after 1 MiB; late tracks are discarded; a `DEAD_TRACK` event is logged.
  - HDMV DTS flag off by default (Q10 resolved).
  - LATM claim corrected.

**S6. HLS playlist failures were classified as `Unknown`.**
- *Where:* §4.1.
- *Problem:* `PlaylistStuckException` (no update for 3.5 × target duration, `DefaultHlsPlaylistTracker.java:77, 1176–1181`) and `PlaylistResetException` (media sequence went back) are plain `IOException`s (`HlsPlaylistTracker.java:100–116`). They fell to `Unknown` → INTERNAL.
- A related gap: panels hold an HLS session after the last request (fake panel `hls_timeout` 20 s). A zap away from HLS on a 1-connection account therefore fails the limit ladder.
- *Fix applied:*
  - Stuck → `Stall(HLS_NO_PROGRESS)`; Reset → `BehindLiveWindow`.
  - `dataType` refinement within HLS (manifest 4, media 1, init 2, DRM 3).
  - The HLS linger case is documented in §3.5.3.

**S7. Ladder defects that the fake panel exposes.**
- *Where:* §4.3, §3.5.3.
- *Problem:*
  - (a) The panel answers an expired account with 403 on the stream (`fakepanel.py:618–619`) while `player_api` says Expired. L2 → L3 → L6 never check the refreshed card, so I-17 would show FORBIDDEN after three or more requests, not ACCOUNT_EXPIRED within two.
  - (b) "A 200 that closes at once" (`linger_status: "close"`, `fakepanel.py:681–685`) reaches the ladder as `TinyBody(0)` → L22 → EMPTY_RESPONSE, not the limit rows §3.5.3 promises.
- *Fix applied:*
  - A global "account gate after a refresh" rule.
  - L22a: a zero-byte body inside the zap window, or before first media, is handled as a limit status.

**S8. The catch-up seam's units and Range gating were wrong.**
- *Where:* §3.14, §3.1.3.
- *Problem:* `ArchiveRequest.nativeSeekRange` is in epoch seconds (`core/.../catchup/CatchupContracts.kt:101`). It is null for Xtream `.ts` timeshift even though those files are served with `Content-Length`/206 (`CatchupUrlBuilderImpl.kt:129`), so "Range allowed when `nativeSeekRange != null`" would disable seeking exactly where it works.
- *Fix applied:* Range is always allowed for archive; `isSeekable` comes from Media3's seek map; the seconds → player-ms mapping is given.

**S9. Redaction gaps.**
- *Where:* §3.2.8.
- *Problem:*
  - `TinkCredentialStore.register` registers raw values only (`data/.../TinkCredentialStore.kt:121–125`), and `Redact.text` matches raw substrings plus the long `/live/u/p/` form (`core/.../common/Redact.kt:15, 21`). Encoded forms (`p%231`, `a%2Bb`), the short form `http://h/u/p/123` and values under 3 characters leak if logged.
  - `ProgressiveMediaPeriod` copies `dataSource.getLastOpenedUri()` and the response headers into `LoadEventInfo` for every `AnalyticsListener` (`:851–852`), and `IcyHeaders.parse(dataSource.getResponseHeaders())` (`:1308`) can wrap the source.
- *Fix applied:*
  - The resolver registers the path- and query-encoded forms.
  - `LiveTsDataSource.getUri()` returns the placeholder and `getResponseHeaders()` an empty map.
  - The M1 raw-replace template positions (`RefreshRepository.kt:446–451`) are noted.

**S10. Learned-state writes would race user data, and the migration test was in the wrong module.**
- *Where:* §2.4.2, §2.10, §6.5, §6.6, I-23.
- *Problem:*
  - `ChannelPrefsRepository.edit` is a whole-row read-modify-write without a lock (`data/.../repo/ChannelPrefsRepository.kt:26–29`, M1 Later 6). A learned `fps_milli` write can revert a concurrent `setHidden`/`setLocked`.
  - `PlaylistRepository.update` is whole-row (`:83`) and races refresh.
  - Nothing clears `xtream_output_learned` when the user changes the setting.
  - Room's schema assets and `MigrationTestHelper` are wired in `:data` (`data/build.gradle.kts:32–34`; `I1LibraryDatabaseTest.kt:52`), not `:app`.
  - A NOT NULL auto-migrated column needs `defaultValue`.
- *Fix applied:*
  - The exact entity annotations and the three `ALTER TABLE`s.
  - Column-targeted upserts, `setXtreamOutputLearned`, and a `setXtreamOutput` that clears the learned value (lead-owned `:data` in commit 0).
  - Migration tests in `:data`.
  - Learned columns excluded from M5 export.
- Keeping `fps_milli`/`playback_flags` in `channel_prefs` is fine: `recordWatched` already creates a row per watched channel (`ChannelPrefsDao.kt:23–30`), nothing treats row existence as meaningful, and `UserDataRemapper` moves rows whole.

**S11. The "audio never starts" detector measured from the wrong moment.**
- *Where:* §3.7 d1.
- *Problem:* the first frame is rendered before playback starts. An AFR hold (`playWhenReady = false`) or a slow READY therefore counts against audio, and AVRs can need 1–2 s to lock passthrough.
- *Fix applied:* d1 measures from `max(first frame, last isPlaying start)` (`WatchdogSample.msSincePlayStart`), with a 5 s passthrough timeout. Verified that `onPositionAdvancing` fires for passthrough too (playback-head based, `AudioTrackPositionTracker.java:295, 306, 377`).

**S12. AFR details.**
- *Where:* §3.9.2.
- *Problem:*
  - The `Surface` vote is lost when the `SurfaceView`'s surface is recreated (standby).
  - `VideoFrameReleaseHelper` guards both set and clear with `strategy == OFF` (`exoplayer/video/VideoFrameReleaseHelper.java:308–313, 335–341`), so switching SEAMLESS → OFF at runtime leaves ExoPlayer's last vote.
  - API 31 adds the user's "match content frame rate" preference (`DisplayManager.getMatchContentFrameRateUserPreference`, since 31 in `api-versions.xml`).
- *Fix applied:* re-apply the vote in `surfaceCreated`; clear the vote (`setFrameRate(0f, DEFAULT)` or `clearFrameRate()` on 34+) on setting changes; record the preference in diagnostics. Whether ALWAYS overrides "Seamless only" is UNVERIFIED (D6).

**S13. Fake panel and instrumented matrix.**
- *Where:* §5.5, §5.6.
- *Problem:*
  - Most §5.5 configs would get HTTP 400: fault keys (`close_every`, `reconnect_mode`, `linger*`, `stall`, `slow`, `ua_whitelist`, `html_splash`, `405_on_m3u8`, `range_rejects`) must sit under `faults`/`stream_faults` (`fakepanel.py:236–239, 48–51`).
  - I-12 uses ch 5, whose `#EXTVLCOPT` UA is the fallback preset itself (`catalog.py:247`).
  - I-11's "Expired + splash" variant is impossible (status is checked first, `:618` vs `:672`).
  - I-2's "rejections empty" races, because the panel only notices a client close on its next write (`:378–391`).
  - §5.6 assumes a per-request log (today `requests` is an int).
  - The TS rewrite rules were underspecified.
- *Fix applied:*
  - Nesting rule and typed `FakePanelAdmin` builders.
  - I-12 → ch 6; I-11 fixed; I-2 logs rejections.
  - Exact PCR/PTS/DTS/CC rewrite rules: 33-bit base + 9-bit extension; 5-byte PTS with prefix and markers; PES only, not PSI; CC advances only on payload packets; offset from the video PTS span with the ~10 ms audio overlap noted.
  - `stall_after` in `FAULT_DOC`, a `request_log`, prompt EOF detection, an audio-only ch 13.

**S14. Robolectric real-player tests.**
- *Where:* §5.3 WP-C, §5.4, §6.8.
- *Problem:*
  - `ShadowMediaCodecConfig` drops every audio sample (`test/utils/robolectric/ShadowMediaCodecConfig.java:186–190, 330–340`), so d1 would fire in every real-player test.
  - It registers codecs without HW/SW flags (`:254–259`), so the selector-ordering tests prove nothing as written.
  - Its codec list has E-AC-3-JOC but not AMR-NB (`:164–182`).
  - Async codec threads flake without `IdlingMediaCodecAdapterFactory`.
  - An auto-advancing `FakeClock` (the `TestExoPlayerBuilder` default, `TestExoPlayerBuilder.java:73`) races socket I/O.
  - `PlayerFactory` has no seam to inject either.
  - R-4 needs WP-D's controller but sat in WP-C.
- *Fix applied:* the constraints list in §5.4; register flagged codecs with `MediaCodecInfoBuilder` (present in Robolectric 4.17); R-4 moved to WP-D; day-0 check 9.

---

## Nice to have (not applied unless noted)

- **N1.** Zap time: try `ExoPlayer.setForegroundMode(true)` while the app is foreground. `stopInternal` then does not reset renderers (`ExoPlayerImplInternal.java:2043`), so a same-MIME next channel can keep its codec (the `canReuseCodec` path) and save 100–300 ms of codec init per zap. Measure in D3; turn it off on `ON_STOP`.
- **N2.** In-source reconnect attempts inherit the 5 s live connect timeout, which is longer than the 3 s reconnect window, so a hanging connect consumes the whole budget in one attempt. Use `call.timeout()` or a 2 s connect timeout for reconnect opens.
- **N3.** The software-audio gate in `BoxMediaCodecSelector` is per exact MIME. `MediaCodecAudioRenderer` also queries alternative MIMEs (E-AC-3-JOC → E-AC-3). Gate MIME families.
- **N4.** Stale-error filter by `PlaybackException.timestampMs` versus session start, and `mediaId = tuneId` on every `MediaItem` (applied in §3.1.1, since it sat in an edited passage).
- **N5.** Archive progressive URLs (with clear credentials in `/timeshift/u/p/…`) enter `MediaItem`/`LoadEventInfo`. Use a placeholder URI plus a `ResolvingDataSource` for archive TS as for live.
- **N6.** DoH, IPv4 preference and proxy are not covered, yet `NextStep.USE_SECURE_DNS` is offered with no mechanism. Add a `Dns` seam on `PlaybackHttpClients` for M5.
- **N7.** "Ignore TLS errors": the per-playlist trust-all client must be its own `newBuilder()` client. It shares the pool, but OkHttp's `Address` equality includes `sslSocketFactory`/`hostnameVerifier` (recalled, not re-read), so connections are not reused across trust settings. `followSslRedirects(true)` means http→https redirects land on the relaxed client only for that playlist.
- **N8.** API < 31 AFR: if no `WindowDisplayModeBinder` is bound (no resumed Activity), never hold `playWhenReady`.
- **N9.** `ByteWatchdog` must unregister sessions in `close()`/`endSession` (keep weak references), or it leaks one entry per zap.
- **N10.** Instrumented-test waiting: one helper `awaitState(timeout) { predicate }` over `controller.state` with `withTimeout` and no sleeps. No automatic retries (a flaky test is a bug). Statistical tests (I-2, I-6) log rather than assert rates.
- **N11.** M4 seam: add `setPlaybackSpeed` additively when M4 needs it; `PositionSnapshot.archiveStartUtcMs + positionMs` is sufficient for the timeline.
- **N12.** HLS live: leave the target offset to the playlist (3 × target duration). The `shouldStartPlayback` clamp to `targetLiveOffsetUs / 2` is generic, matching `DefaultLoadControl.java:821–823`.
- **N13.** Subtitles: DVB subtitles work through extraction-time transcoding (verified); keep transcoding on (now stated in §3.1.3). Rendering stays M4.
- **N14.** `PlaybackStatsListener` is not used. If M5 adds it, use `keepHistory = false`.
- **N15.** Q12: implement `interlacedHint` for H.264 (§3.9.3 note), a few lines.
- **N16.** The debug harness is exported only in the debug source set, which is acceptable. Day-0 check 4 must also grep the release manifest for the `thebox-debug` scheme.

---

## Verified correct

| # | Claim | Evidence |
|---|---|---|
| V1 | With `open()` returning `C.LENGTH_UNSET`, `ProgressiveMediaPeriod` only ever opens at position 0 | `configureRetry` resumes at the input position only when `isLengthKnown` or the duration is known (`ProgressiveMediaPeriod.java:1093–1098`). Otherwise prepared → deferred retry, and `maybeStartDeferredRetry` sets `pendingResetPositionUs = 0` (`:727–741`); unprepared → `loadable.setLoadPosition(0, 0)` (`:1122`). Seeks into an unseekable map go to 0 (`:589–590`) |
| V2 | `TsExtractor` never seeks without a length | `boolean canReadDuration = inputLength != C.LENGTH_UNSET && !isModeHls;` (`TsExtractor.java:431`); `RESULT_SEEK` otherwise only via `pendingSeekToStart` with `input.getPosition() != 0`, or via the binary-search seeker, which needs a duration |
| V3 | Sniffing needs no replay from the data source | `TsExtractor.sniff` uses `peekFully` + `skipFully(startPosCandidate)` (`:355–373`); peeked bytes are buffered by `DefaultExtractorInput`. With the TS-only factory there is one extractor |
| V4 | Aligned delivery is compatible with `TsExtractor`'s own sync search | `fillBufferWithAtLeastOnePacket`/`findEndOfFirstTsPacketInBuffer` tolerate arbitrary read sizes; alignment matters only at the splice (B1) |
| V5 | `LiveStreamException` reaches the policy and the player unchanged | `onLoadError` passes the thrown `IOException` into `LoadErrorInfo` (`ProgressiveMediaPeriod.java:868–870`); `Loader` keeps it as `fatalError = currentError` (`Loader.java:532`); `catch (DataSourceException e) { handleIoException(e, e.reason); }` → `ExoPlaybackException.createForSource(e, errorCode)` (`ExoPlayerImplInternal.java:992–993, 1016–1017`) |
| V6 | `C.TIME_UNSET` from the policy → `DONT_RETRY_FATAL` | `ProgressiveMediaPeriod.java:869–870`; `HlsSampleStreamWrapper.java:1107–1111`; `DefaultHlsPlaylistTracker.java:1088–1091` |
| V7 | HLS distinguishes manifest from media by `dataType` | Playlist loads use `C.DATA_TYPE_MANIFEST` (`DefaultHlsPlaylistTracker.java:190–192, 941`); segments pass `loadable.type` (`HlsSampleStreamWrapper.java:1077–1078`); `getFallbackSelectionFor` returning null → no exclusion (`:1088–1093`) |
| V8 | `DefaultLoadErrorHandlingPolicy` retries `InvalidResponseCodeException` by default, so overriding is needed | fact sheet §4, consistent with `DefaultLoadErrorHandlingPolicy.java:128–183` |
| V9 | Fatal errors before prepare surface immediately | `playingPeriodHolder.mediaPeriod.maybeThrowPrepareError()` (`ExoPlayerImplInternal.java:1511`) |
| V10 | `LoadControl` method set and `Parameters` fields | `LoadControl.java:41–440` (and S4 for the default-overload trap) |
| V11 | `DefaultLoadControl` start rule with the live clamp, mirrored by the design | `DefaultLoadControl.java:812–829` |
| V12 | Runtime profile changes take effect on the next call | `loadControl.shouldContinueLoading(loadParameters)` is called on every `maybeContinueLoading` (`ExoPlayerImplInternal.java:3553`) and `shouldStartPlayback` per check (`:2566`); no caching |
| V13 | Passthrough check comes before the selector | `MediaCodecAudioRenderer.java:357–366` (`audioSink.supportsFormat(format)` → `FORMAT_HANDLED`), empty decoder list → `FORMAT_UNSUPPORTED_SUBTYPE` (`:376–380`), `shouldUseBypass` = `audioSink.supportsFormat` (`:488–500`) |
| V14 | A runtime gate change needs a new track selection | `supportsFormat` is consulted at selection; the design always re-prepares, which re-selects. No player rebuild needed |
| V15 | Hiding platform decoders sends the track to FFmpeg | `MappingTrackSelector` picks the highest support level, ties to the earlier renderer (`MappingTrackSelector.java:505–524`); `FfmpegAudioRenderer.supportsFormatInternal` → HANDLED when `FfmpegLibrary.supportsFormat` and the sink takes PCM (`FfmpegAudioRenderer.java:133–146`); extension renderers are appended after the platform ones in `ON` mode (`DefaultRenderersFactory.java:556–581, 687–`) |
| V16 | MP2 → FFmpeg `mp3` decodes Layer II | `FfmpegLibrary.getCodecName`: `AUDIO_MPEG_L2` → `"mp3"` (`FfmpegLibrary.java:122–125`); `mpegaudiodec_template.c:1475–1482` `switch(s->layer)` → `mp_decode_layer2`; `ff_mp3_decoder` uses that `decode_frame` (`mpegaudiodec_fixed.c:98–105`) |
| V17 | `LatmReader` output MIME is `audio/mp4a-latm` with the ASC as init data | `LatmReader.java:211–223` (and S5 for `audioMuxVersion` 1) |
| V18 | TS H.264/H.265 readers never set `Format.frameRate` | no `setFrameRate` in `extractor/ts/*` (H262Reader computes a local frame duration only, `:281–290`) |
| V19 | API levels | `api-versions.xml` (android-37.0): `setFrameRate(FI)` 30, `setFrameRate(FII)` 31, `CHANGE_FRAME_RATE_*` 31, `FRAME_RATE_COMPATIBILITY_DEFAULT/FIXED_SOURCE` 30, `clearFrameRate()` 34 |
| V20 | With `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`, ExoPlayer never calls `setFrameRate`, not even to clear on a surface change | `VideoFrameReleaseHelper.java:308–313, 335–341` |
| V21 | `preferredDisplayModeId` from a singleton is workable | the design routes it through `WindowDisplayModeBinder` bound by the Activity; `DisplayManager` listeners work from the application context |
| V22 | `PlayerSurface` requires only `COMMAND_SET_VIDEO_SURFACE`, uses a plain `SurfaceView` in an `AndroidView` with no clip/`graphicsLayer` of its own, and has a real API 34 workaround | `PlayerSurface.kt` (`SDK_INT == 34`, `SurfaceSyncGroup("exo-sync-b-334901521")`, skipped under Robolectric) |
| V23 | A plain `ForwardingPlayer` satisfies `PlayerSurface` | it forwards `setVideoSurfaceView`/`addListener`; `PlayerSurface` only listens to `onSurfaceSizeChanged`. `SimpleBasePlayer` adds nothing unless the delegate must change (B3) |
| V24 | `Call.cancel()` closes the HTTP/1.1 socket synchronously and is thread-safe | `RealCall.kt:168–178` (`exchange` is `@Volatile`, `plansToCancel` is a `CopyOnWriteArrayList`), `RealConnection.kt:294–297` `rawSocket.closeQuietly()`, `ConnectPlan.kt:592–596` |
| V25 | `OkHttpDataSource.close()` closes the body and does not cancel | `OkHttpDataSource.java:362–369, 534–539` |
| V26 | An endless unknown-length body is never pooled | `newUnknownLengthSource` sets `noNewExchanges` (`Http1ExchangeCodec.kt:283–288`); `RealConnectionPool.kt:146–153` |
| V27 | `HttpModule.baseClient()` is safe for streaming | no cache, no body-logging interceptor, no `callTimeout`; UA interceptor only adds when absent; `followRedirects`/`followSslRedirects` true |
| V28 | `setWakeMode(WAKE_MODE_NETWORK)` needs only `WAKE_LOCK` and holds locks only while playing | `common/util/WakeLockManager.java:141–152`, `WifiLockManager.java:136–148`, `ExoPlayerImpl.java:3116–3130` |
| V29 | `lifecycle-process` 2.10.0 is compatible | catalog `androidxLifecycle = "2.10.0"`; media3-session asks for `lifecycle-service:2.8.0`; the resolved graph aligns everything to 2.10.0 |
| V30 | `StuckPlayerDetector` exists with the stated defaults and surfaces `ERROR_CODE_TIMEOUT` + `StuckPlayerException` (types 0–4) | `common/util/StuckPlayerDetector.java:34–262`, `StuckPlayerException.java:40–62`, `ExoPlayer.java:228–260, 1088–1112`; buffering detection keys on the buffered position, playing detection on the position; both off while paused/suppressed |
| V31 | Test deps: `media3-test-utils` pulls `mockwebserver:4.12.0`; excluding it is right and safe | only `WebServerDispatcher`/`HttpDataSourceTestEnv` reference `okhttp3.*`; `mockwebserver3` 5.5.0 is the catalog's; Robolectric 4.17 has `MediaCodecInfoBuilder`, `ShadowMediaCodec`, `ShadowMediaCodecList` |
| V32 | M1 templates and encodings match §3.2 | `"{{base}}/live/{{u}}/{{p}}/$streamId.{{ext}}"` (`XtreamCatalogDecoderImpl.kt:45`); `XtreamUrls.pathSegment` keeps pchar and encodes `/ ? # % space` and non-ASCII; `query` keeps only unreserved (`+` → `%2B`) (`XtreamUrls.kt:77–83`); `TemplateExpander` today emits `{{u}` as unknown (`TemplateExpander.kt:48–60`), so the §2.9 amendment is needed and sufficient |
| V33 | Every `PlaylistPlaybackInfo`/`ChannelStreamInfo`/`ChannelPlaybackMemory` column exists in v1 | `PlaylistEntity.kt` (`needs_reauth`, `user_agent`, `extra_headers_json`, `xtream_output`, `catchup_probe_winner`, `max_connections_override`, `ignore_tls_errors`, `active_base_index`), `ChannelEntity.kt` (`url`, `headers_json`, `xtream_stream_id`, `container_ext`), `ChannelPrefsEntity.kt` (`aspect_mode`, `audio_lang`, `subtitle_lang`, `video_decoder`, `buffer_profile`, `last_watched_at`, `watch_count`) |

Also checked:
- The 350 ms leading-edge plus trailing debounce matches the architecture review ("debounce the actual tune by 350 ms" for held keys, `architecture-review.md:285`) and keeps single presses instant.
- `max_connections` from override → account → 1 is right.
- One lease per HLS session matches how panels count HLS.
- `MediaCodec` exhaustion on zaps is not a risk: `stop()` resets and releases the codecs on the playback thread before the next prepare.

---

## Work-package disjointness

| WP | Packages / files |
|---|---|
| Lead (commit 0) | `core/.../playback/{PlaybackContracts,PlaybackLogic}.kt`, `player/.../api/*`, `player/.../internal/Seams.kt`, `ui/.../playback/VideoLayer.kt` (signature), `:core` catch-up amendments, `:ffmpeg` `FfmpegAvailability`, `:data` entities/DAOs/schema/migration tests, manifests, `MainActivity.kt`, `TheBoxApplication.kt`, build files, TS fixtures |
| A | `core/.../playback/{resolve,broker,errors,buffer,stall,afr,surface,diag}`, `player/.../{resolve,broker}` and their tests, `core/src/test/resources/playback/` |
| B | `core/.../playback/ts`, `player/.../datasource` and their tests, `core/src/test/resources/{ts,http}` |
| C | `player/.../engine` and its tests (R-1 to R-3, R-5) |
| D | `player/.../controller`, `ui/.../playback/VideoLayer.kt` (body), `app/.../playback`, `app/src/debug`, `app/src/androidTest`, `player/src/test/.../controller` (incl. R-4), `tools/fakepanel` |

**Verdict: disjoint after these review changes.**
- R-4 moved from C to D.
- The `:data` DAO additions are now explicitly lead work; previously `RoomChannelMemory` (D) would have needed them.
- `MainActivity`/`TheBoxApplication`/manifest edits are explicitly lead-owned.
- `PlaybackService.kt` is no longer touched in v1. Its old row in WP-D's table is superseded.

No file is edited by two WPs.

---

## Lead decisions needed

1. **Q1 Home vs screen-off.** Agree with the lead (Home stops at once; 30 s grace for standby/HDMI/display-off). It only works with B4's `isInteractive()` check, which is now in the design.
2. **Q2 Fake panel v1.** Agree, as a parallel task that must land before I-3–I-5, I-8, I-15 and I-20a. It is about 300 lines with the S13 additions, not 200. Put the EOF detection (S13 item 6) first: it changes I-2/I-6 results.
3. **Q3 x86 debug AAR.** Agree. FFmpeg Layer II decoding is now source-verified (V16), so I-13 confirms rather than discovers. Keep x86 out of release packaging (it already is).
4. **Q4 VLC-UA one-shot retry on 403, default on, never persisted.** Agree with the lead. It is consistent with the fact-checked research and D22. Two conditions: it must run only after the account gate (S7) shows an active account with a free slot (already L5's condition), and it must never apply to a channel that sets its own UA (already in §3.2.5).
5. **Q5 Decay after 10 min.** Agree (the product baseline wins over the review's 24 h).
6. **Q6 Learned audio facts memory-only in M2.** Agree. When M5 persists them, key by route type + product name: `AudioDeviceInfo.getId()` is not stable across reboots or re-plugs (noted in §8).
7. **Q7 Ship the additive migration in M2.** Agree, with the S10 conditions: column-targeted writes, the exact `ALTER TABLE`s, and tests in `:data`.
8. **Q8 HIDDEN viewport.** My recommendation is to keep "audio continues, connection kept" with no timeout in M2. Leaving a full-screen settings page and coming back to running audio is TiviMate behaviour, and the user never left the app. Revisit with the B4.9 inactivity / "Still watching?" work in M4/M5, which is the right place for a timeout.
9. **Q9 PCR tolerance.** Agree: let D4 decide and document both. The source already shows the asymmetry. Forward jumps with audio are ridden through by `DefaultAudioSink`'s re-base; backward jumps overstate the buffered position and must always re-prepare. Keep symmetric re-prepare in M2; consider "forward and audio present → ride through" after D4.
10. **Q10 HDMV DTS flag.** Change from "keep" to **off by default** (S5, applied). The risk is a never-preparing tune on SCTE-27 restreams; the benefit (Blu-ray DTS over live IPTV) is near zero, and DVB DTS works without the flag.
11. **Q11 No resolution switching.** Agree.
12. **Q12 `interlacedHint`.** Acceptable as "false" for M2, but it is cheap for H.264 (`NalUnitUtil.parseSpsNalUnit(...).frameMbsOnlyFlag`). Recommend doing it in WP-C; HEVC stays false.
13. **(new, B3) No idle player release in v1.** Approve. The alternative is a re-targetable `SimpleBasePlayer` facade plus `MediaSession.setPlayer`, which is extra work for a few hundred KB.
14. **(new, B5) No `MediaSessionService` and no `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in the v1 manifest.** Approve. It also removes a Play Console FGS declaration for targetSdk 35.
15. **(new, S5) Dead-track guard in M2.** Approve it as WP-C scope (about 80 lines plus a fixture), or explicitly defer it and accept that some restreams never start.
16. **(new, B6) b1 threshold.** Approve `DECODER_STALL_MAX_MEDIA_GROWTH_MS = 500` as the initial value; D4/D5 logs will tune it.

---

## Test gaps

| Gap | Where it is now covered |
|---|---|
| CC behaviour across a splice (all 16 restart CCs) and a real `TsExtractor` fed the spliced bytes | §5.3 WP-B (B1) |
| Post-splice hold: no new-timestamp byte readable before the PCR verdict | §5.3 WP-B LiveTs (S1) |
| Radio / poster / HIDDEN first-media milestone | §5.3 WP-D, I-20a (B2) |
| `ON_STOP` while not interactive → grace | §5.3 WP-D, I-20 (B4) |
| Session commands ignored in the background; manifest without a `MediaSessionService` | §5.3 WP-D, day-0 check 8 (B5) |
| Slow network does not fire b1 | I-16 (B6) |
| No `LoadControl` overload throws on a real player | §5.3 WP-C (S4) |
| Dead PID, 0x82 and LATM v1 tracks do not block prepare | §5.3 WP-C (S5) |
| DNS failure at open classified as DNS | §5.3 WP-B LiveTs (S3) |
| Expired account answered with 403 → `ACCOUNT_EXPIRED` | I-17 (S7) |
| `linger_status: "close"` goes through the limit rows | I-6 with L22a (S7) |
| Selector ordering with real HW/SW flags | §5.3 WP-C (S14) |
| Migration test in the module that has the schema assets | `:data` androidTest + day-0 check 7 (S10) |
| Not covered by M2 (hardware) | HDMI AFR re-sync, passthrough on AVRs (d1 5 s), Fire OS CEC standby, `CHANGE_FRAME_RATE_ALWAYS` vs the user's match-content preference, HTTP/2 panels, decoder behaviour on glued PES (now prevented) |

---

## What I could not verify

- Fire OS behaviour on CEC TV-off (sleep and Activity stop, or display-off only). Both paths now lead to the grace.
- That AOSP routes media keys to a backgrounded app's still-active session. This is recalled standard behaviour; B5's fix does not depend on it.
- Compose-UI `AndroidView` internals: I did not read whether its layout node adds a `graphicsLayer`. `PlayerSurface` itself adds none, and a `SurfaceView` hosted as a real child View punches through regardless. The CLAUDE.md rule concerns our ancestors, which `VideoLayer`'s KDoc already forbids.
- OkHttp `Address` equality including `sslSocketFactory`/`hostnameVerifier` (N7). Recalled, not re-read.
- Whether `CHANGE_FRAME_RATE_ALWAYS` overrides a "Seamless only" user preference on API 31+, and whether Fire OS honours `preferredDisplayModeId` for AFR (D6).
- Runtime facts not executed here: FFmpeg Layer II decoding (source-verified only), first frame from TS under `ShadowMediaCodecConfig` (day-0 check 3), and that Room emits exactly the three `ALTER TABLE`s (Room's documented behaviour; day-0 check 7 confirms).
- `WIFI_MODE_FULL_HIGH_PERF` behaviour on API 34+, where it is deprecated.
