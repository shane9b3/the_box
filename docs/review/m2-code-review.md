# M2 "Playback core": code review

Reviewer pass over the merged M2 code (`git diff 0ec021a..abe7b54`), 2026-10-14, in two halves run in parallel and merged here:
- **Network / TS / logic** (tags `N-`): `core/.../playback` (contracts, logic, resolve, broker, errors, buffer, stall, ts, diag, the ladder golden CSV), `core/.../catchup/TemplateExpander.kt`, the credential and redaction paths, `player/.../{resolve,broker,datasource}`, the `app` playback bindings, `library.db` v2, and the fake-panel changes (lighter pass).
- **Engine / controller / lifecycle / UI** (tags `E-`): `player/.../{api,internal,engine,controller}`, the player manifest, `core/.../playback/{afr,surface}`, `ui/.../playback/VideoLayer.kt`, the app wiring and debug harness, and the device suite `app/src/androidTest/.../playback`.

Checked against `CLAUDE.md`, `00-master-plan.md` §3 and §6, `design/m2-playback-core.md` (§0, §2.1–§2.11, §3.1–§3.16, §4, §5.5, §5.8, Appendices A and B), `review/m2-design-review.md` and `design/m2-media3-api-notes.md` (plus the Media3 1.11.1 `-sources.jar` for `DefaultAudioSink`/`AudioSink`).

Method: every finding was traced through the code. Four were reproduced with throwaway Robolectric tests before the fixes (N-S1, N-S3, E-B2, E-S1; sources in the reviewers' notes, deleted afterwards). Every fix below has a regression test, named next to the finding, that fails on `abe7b54` (behaviourally, or for the tests of new contract members, by not compiling there). **Line numbers refer to `abe7b54`**, the code before the fixes. The `N-`/`E-` tags also appear in the code comments and test names the fixes added.

The lead's decisions where the reviewers left a choice: E-B1 and N-S2 are one defect and get one contract change (`StreamTelemetry.readPendingSinceMs`); E-B2 stops at once when not STARTED (no short wait); O-1 is not implemented (a separate review of the proposal is running).

**Result:**
- Blockers: 2 found (E-B1 = N-S2 counted once, E-B2), 2 fixed.
- Should fix now: 11 found (N-S1, N-S3–N-S6, E-S1–E-S6), 11 fixed.
- Later: 37 items (18 network, 19 engine; the engine half's Later 3 is N-S6). 2 one-line items taken (N-L16, E-L4); 35 not applied.
- O-1 (decoder-output stall detector b3): proposal copied below, **pending** a separate review; not implemented.
- Build (`:core:test :data:testDebugUnitTest :player:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :player:lintDebug :app:lintDebug`): green. `:core` 1,309 tests (was 1,295), `:data` 124 (was 124), `:player` 293 (was 271); 0 failures, 0 skipped. Lint: 0 errors (warnings only, pre-existing kinds: the FFmpeg `.so` in `:player`).
- Fails-before check: with each fix's behaviour reverted in place (new contract members kept so the tests compile), 24 of the new or changed tests fail, which covers every finding with a behavioural test. The rest are new-API tests (they cannot compile on `abe7b54`) or guards (`wakeWithStartKeepsPlaying`, `decodedPcmInitFailureIsNotEncoded`, `credentialsSurviveTokenChurn`, `tsBackstopIsSilentWhileNoReadIsPending`).
- Device (API 30 TV emulator, fake panel, one class per `run-instrumented.sh` call; `range_requests_seen == 0` held in every test's `@After`):

| Class | Pass | Fail | Skipped by assumption / ignored |
|---|---|---|---|
| `MediaAndLifecycleTest` (I-13, I-19, I-20, I-20a, I-21, I-22, new I-22b) | 5 | 0 | 2 (I-22, I-22b: "display has no 50 Hz mode"; MINI and the FULL evaluation asserted first) |
| `SmokeAndZapTest` (I-1, I-2, I-6 ×4, I-7) | 7 | 0 | 0 |
| `ErrorPathTest` (I-10, I-11, I-12 ×2, I-17, I-18) | 6 | 0 | 0 |
| `ReconnectTest` (I-3, new I-3b, I-4, I-5, I-8, I-14, I-15, I-16) | 7 | 0 | 1 (I-3 `@Ignore`, O-1) |

  I-21 (hole punch) is flaky on this emulator **at `abe7b54` too**: 2 of 4 isolated baseline runs and 3 of 5 runs with the fixes saw an unchanged centre region between the two screenshots (`diff=0`). It passed in the final class run. Not addressed here (Test gaps 10).
- Measured (emulator, logged not asserted): I-6 1 tune and 3 stream requests per burst for every linger status, 10/10 single zaps; I-4/I-5 back to Playing 0.56–1.63 s after each `Reprepare`; I-3b 3 reconnects in 50 s, gaps 16–27 ms; I-8 alternating tokenless/tokenised requests.

---

## Blockers (fixed)

### E-B1 = N-S2. The TS "no bytes" backstop fires whenever the loader is parked on a full buffer, so healthy streams get re-resolved in a loop
Found by both reviewers (engine half as a blocker, network half as should-fix); one defect, one fix.
- **Where:** `player/.../engine/PlaybackWatchdog.kt:190-196` filled `lastByteAgeMs = now - telemetry.lastByteAtMs` with no notion of a pending read. Detector (a)'s TS branch is `core/.../stall/StallDetectors.kt:128-130` (only `isLive`, `!inDataSourceReconnect`, age > 8 s). `LiveTsDataSource` knew whether a read was pending (`blockedSinceMs`, `:118, :159, :173`), but `StreamTelemetry` did not expose it.
- **Problem:** §3.3.6 measures silence only while a `read()` is in progress ("a full buffer never trips it"), and §3.7 (a) says its input is measured "as in §3.3.6". `AdaptiveLoadControl` parks the loader in `loadCondition.block()` from `maxBufferMs` down to `minBufferMs` (10 s at L0/L1, 15–20 s at L2/L3), longer than the 8 s backstop.
- **Effect:** any non-archive session whose buffer reaches `maxBufferMs` gets `Stall(NO_BYTES)` about 8 s later, then L19 `ReResolve` with a "Reconnecting n/5" notice and a rebuffer from zero, and the next session loops; after `reconnectAttempts` it ends in `STREAM_INTERRUPTED`. Near certain for VOD entries in M3U playlists (faster than real time), and for panels whose connect burst exceeds `maxBufferMs`. §5.8's O-1 "recovery" was this bug.
- **Fix:** contract amendment (§2.11): `StreamTelemetry.readPendingSinceMs` (default 0). `LiveTsDataSource.read()` sets it when it has to wait on the socket and clears it when the read returns; `SessionTelemetry` carries it; `PlaybackWatchdog` reports `lastByteAgeMs = now − max(lastByteAt, readPendingSince)` only while it is non-zero, else 0. `WatchdogSample.lastByteAgeMs` is documented accordingly; §3.7 (a) wording updated.
- **Tests:** `PlaybackWatchdogTest.parkedLoaderWithAFullBufferIsNotNoBytes` (parked 20 s, real detectors: no verdict), `PlaybackWatchdogTest.readPendingForNineSecondsIsNoBytes` (age 9,000 ms from the start of the pending read, `NO_BYTES`), `LiveTsDataSourceTest.readPendingIsReportedOnlyWhileAReadWaitsOnTheSocket`, `StallDetectorsTest.tsBackstopIsSilentWhileNoReadIsPending`.

### E-B2. Waking from standby within the grace while the launcher is in front leaves live TV playing behind the launcher indefinitely
- **Where:** `player/.../controller/LifecycleCoordinator.kt:107-110` (`onScreenOn`), `:113-122` (`onHdmiAudioPlug(true)`), `:125-131` (`onDisplayStateChanged(true)`).
- **Problem:** sleep stops the Activity while not interactive, so the 30 s STANDBY grace starts (B4). Waking within it cancelled the grace; `resumeAfterLifecycle` was skipped because the process was not STARTED, but nothing stopped playback either. Many Android TV and Fire TV devices return to the launcher on wake, so no `ON_START` follows.
- **Effect:** audio, the provider connection and the wake lock run behind the launcher with no timer left (reproduced: still `Playing` 2 minutes after a background wake). Violates Q1/B4.
- **Fix (lead decision):** a wake-type event (screen on, display on, HDMI re-plug) cancels the grace; if the process is STARTED playback continues (or a lifecycle stop resumes), otherwise it stops at once with `BACKGROUND`, exactly as Home does. A later `ON_START` resumes it with a fresh resolve. The 30 s grace is unchanged.
- **Tests:** `LifecycleTest.wakeWithoutStartStopsAsBackground` (the reviewer's repro, plus the later resume), `LifecycleTest.wakeWithStartKeepsPlaying`, `LifecycleTest.displayOnAndHdmiReplugInTheBackgroundStopToo`.

---

## Should fix now (fixed)

### N-S1. A header value with a non-ASCII or control character escapes `open()` as a `RuntimeException`: the lease is kept, the value lands in the exception message, and the user sees INTERNAL
- **Where:** `player/.../datasource/LiveTsDataSource.kt:139-147, 273-282` (catch only `IOException`); `StreamHttpDataSourceFactory.kt:97-103`; `core/.../resolve/HeaderPolicy.kt:39-45` (no validation). Sources of such values: percent-decoded pipe headers and `#KODIPROP` (`%0D%0A`, `%C3%A9`), playlist `user-agent`/`extra_headers_json`, a raw non-ASCII password expanded by `expandHeaderValue`.
- **Problem:** OkHttp's `Request.Builder.header` throws `IllegalArgumentException("Unexpected char 0x.. at N in <name> value: <value>")`; not an `IOException`, so `shutdown()` never ran.
- **Effect:** the channel never plays (Unknown → L44 → INTERNAL), the lease is held until `endSession`, and the value reaches the Media3 error log line (reproduced: `lease releases=0`, message contains `s3crét`).
- **Fix:** `HeaderPolicy.isValidName` (RFC 7230 token), `isValidValue` (printable ASCII and tab), `sanitize`. `StreamUrlBuilderImpl.build`/`buildArchive` sanitize the **expanded** headers and record the dropped names in `ResolvedStream.droppedHeaders`; the controller logs `RESOLVED headers_dropped <names>` (names only, redacted by the log). Backstop: both data sources map OkHttp's `IllegalArgumentException` to `LiveStreamException(NETWORK, OTHER)` with no cause and no value; `LiveTsDataSource.open()` shuts down (releases the lease) on any throwable.
- **Tests:** `HeaderPolicyTest.sanitizeDropsControlCharactersNonAsciiAndBadNames`, `HeaderPolicyTest.sanitizeKeepsAValidMapAsIs`, `StreamUrlBuilderTest.headersWithInvalidValuesAfterExpansionAreDroppedByName`, `LiveTsDataSourceTest.aBadHeaderValueIsATypedFailureThatReleasesTheLeaseAndHidesTheValue`, `StreamHttpDataSourceFactoryTest.aBadHeaderValueIsATypedFailureWithoutTheValue`, `PlaybackControllerStateTest.droppedHeadersAreLoggedByName`.

### N-S3. A non-HTTP stream URL (`rtmp://`, `rtsp://`, `udp://`, `mms://`) becomes 15 s of "network" retries and CONNECT_FAILED, and the typed failure carries the real `DataSpec`
- **Where:** `core/.../resolve/StreamUrlBuilder.kt:95-96` (only `host.isEmpty()`); `FormatHints.hint` says TS for an extensionless `rtmp://`; `LiveTsDataSource.kt:279-281, 476-478`; `LiveStreamException.kt:83-93` (`rootCause` returns the `DataSourceException` itself when it has no cause).
- **Problem:** `OkHttpDataSource` throws `HttpDataSourceException("Malformed URL", dataSpec, …)`; `ioFailure` made it NETWORK/RESET and kept it as the cause, with the credentialed `DataSpec` (reproduced: `causeSpec=rtmp://…`).
- **Effect:** L17's retries (~15 s), then CONNECT_FAILED instead of UNSUPPORTED_SOURCE ("Open in external player"); a programmatic URL leak against the KDoc contract.
- **Fix:** `MediaSourceBuilder.build` throws `UnsupportedStreamFormatException(format, scheme)` for any scheme but `http`/`https`/`thebox-session`, before any transport; the controller fails with `UNSUPPORTED_SOURCE` (signal `scheme <name>`), no ladder step, no network attempt. `safeCause()` never keeps a `DataSourceException` as a cause, in `LiveTsDataSource.ioFailure` and in `BrokeredHttpDataSource.hide`.
- **Tests:** `MediaSourceBuilderTest.nonHttpSchemesAreUnsupportedBeforeAnyTransport`, `PlaybackControllerStateTest.anUnsupportedSchemeIsUnsupportedSourceWithoutAnOpen`, `LiveTsDataSourceTest.noLiveStreamExceptionCarriesARealDataSpecAsItsCause`.

### N-S4. Detector b1 ("decoder stuck") cannot tell a stuck decoder from a very slow network on a high-bitrate stream (B6 only half closed)
- **Where:** `core/.../stall/StallDetectors.kt:133-150` (absolute 256 KiB and 500 ms). `StallDetectorsTest.kt:99-106` asserted a `DECODER_STUCK` verdict for 50 kB/s turning into 50 ms of media per second: an 8 Mbit/s stream on a 400 kbit/s link.
- **Problem:** b1 fired whenever 256 KiB ≤ bytes in 6 s < bitrate × 0.5 s / 8, e.g. at 350–670 kbit/s for an 8 Mbit/s stream.
- **Effect:** on a congested link with a UHD or sports channel: two re-prepares, a **persisted** compat raise (`FLAG_DETECT_ACCESS_UNITS` off for good), then STREAM_UNDECODABLE for a network problem.
- **Fix:** b1 learns the stream's own rate (bytes per buffered-media second, over spans with ≥ 2 s of buffered-position growth) and fires only when the bytes of the 6 s window are at least the window's worth at that rate (floor 256 KiB), so a network slower than real time never qualifies; before a rate is learned the threshold is 2 MiB (`DECODER_STALL_MIN_BYTES_UNKNOWN_RATE`, a working extractor turns that into ≥ 500 ms for streams below ~33 Mbit/s). The rate is forgotten at every new session (`StallDetectors.newStream`, called by `PlaybackWatchdog.beginSession`). B6's 500 ms growth clause is kept.
- **Tests:** the old firing test is now `StallDetectorsTest.slowNetworkOnAHighBitrateStreamDoesNotFire` and asserts no verdict; plus `slowNetworkOnALearnedHighBitrateStreamDoesNotFire`, `decoderStuckFiresWhenBytesMatchTheStreamRateButMediaDoesNotGrow`, `decoderStuckNeedsTheWindowsWorthAtTheLearnedRate`, `decoderStuckWithoutALearnedRateNeedsTwoMebibytes`, `decoderStuckWithoutALearnedRateFiresAboveTwoMebibytes`, `newStreamForgetsTheLearnedRate`. `repeatedVerdictsAfterCoolDownFeedTheLadderEscalation` now feeds 400 kB/s. Not taken: the reviewer's extra "L27b must not persist a compat raise when throughput is below the stream rate"; with the new threshold such a verdict cannot occur.

### N-S5. `Redact`'s secret set grows by one entry per resolved URL and is never trimmed
- **Where:** `player/.../resolve/StreamUrlResolverImpl.kt:159` → `core/.../resolve/CredentialExpander.kt:120` (`registerUrl` registered the whole URL); `core/.../common/Redact.kt:7-11, 21` (`CopyOnWriteArraySet`, every log line scans every secret).
- **Effect:** a slow memory and CPU leak in a process that lives for weeks: one entry per tune, retry, base rotation and archive seek, and every log line slows down linearly.
- **Fix:** `registerUrl` registers only the URL's userinfo and token-like query values (a fixed name list, or values ≥ 16 characters), never the URL. `Redact` keeps credentials (`CREDENTIAL_CAP` 1,024) and URL token components (`TRANSIENT_CAP` 64) in two bounded LRU sets, masks from an immutable snapshot (longest first) rebuilt only on a new entry, and exposes `secretCount()` for tests.
- **Tests:** `ResolveSecretsTest.tenThousandResolvedArchiveUrlsKeepTheSecretSetBounded`, `ResolveSecretsTest.registerUrlTakesUserinfoAndTokenLikeQueryValuesOnly`, `ResolveSecretsTest.credentialsSurviveTokenChurn`.

### N-S6. `ReResolveLimiter` gives every direct URL one shared bucket, against §4.5, so harness and diagnostic tunes starve each other
Also reported by the engine half as Later 3; one item.
- **Where:** `core/.../errors/ReResolveLimiter.kt:7-8, 17`; the controller passed `c.identity` (null for every `DirectUrl`) at `PlaybackControllerImpl.kt:370, 1061-1062`.
- **Effect:** after two re-resolves on any direct URL, every other direct URL got limiter-refused fallbacks for 60 s; device results could depend on test order.
- **Fix:** `ReResolveKeys.of(identity, brokerKey)`: the identity, or a synthetic identity per broker key (`d:<host>`) for a direct URL. The controller's `limiterKey(c)` uses it for `wouldAllow`, `tryAcquire` and `reset`. The contract is unchanged.
- **Tests:** `ReResolveLimiterTest.directUrlsAreLimitedPerBrokerKey`, `PlaybackControllerStateTest.directUrlsAreLimitedPerBrokerKey`.

### E-S1. A zap whose `neighbour()` is in flight is not cancelled by `tune()`, `stop()` or a lifecycle stop: it starts playback afterwards
- **Where:** `PlaybackControllerImpl.kt:328-359` (the lookup coroutine was untracked); `cancelPendingZap()` (`:374-379`) cancelled only `zapFireJob`.
- **Effect:** Home right after CH± re-tuned in the background (reproduced: `Connecting(ch2)` after `stop(BACKGROUND)`); a guide pick right after CH± landed on the neighbour; a stale pending-channel banner after a stop.
- **Fix:** lookups are tracked and cancelled in `cancelPendingZap()` (called by `tune`, `tuneArchive`, `stop`, so also lifecycle stops), plus a zap generation checked after `neighbour()` returns.
- **Tests:** `PlaybackControllerStateTest.stopDuringZapLookupStaysStopped` (the repro), `PlaybackControllerStateTest.tuneDuringZapLookupWins`.

### E-S2. AFR "Always" never switches unless the viewport changed at least once: `DisplayModeController` starts in `Viewport.NONE`
- **Where:** `engine/DisplayModeController.kt:92`; the controller starts at FULL and `setViewport` returns early on an unchanged value (`PlaybackControllerImpl.kt:1389-1393`); `ensureEngine()` never pushed it.
- **Effect:** with AFR ALWAYS, no `setFrameRate` vote (API 31+) and no `preferredDisplayModeId` (API < 31) in full screen unless the UI went through MINI first.
- **Fix:** `ensureEngine()` calls `parts.displayMode.onViewport(viewport)` once.
- **Tests:** `PlaybackControllerStateTest.theInitialViewportReachesTheDisplayModeController`; device `MediaAndLifecycleTest.i22bAfrInFullFromTheStart`.

### E-S3. A passthrough `AudioTrack` init failure is classified as PCM, so the ladder drops the audio instead of decoding it
- **Where:** `controller/FailureClassifier.kt:94-98, 110-114`: `encoded = mime != AUDIO_RAW && facts.passthroughActive`. `passthroughActive` comes from `onAudioTrackInitialized`, which never fires when the init fails, and is reset per session.
- **Effect:** AC-3/E-AC-3 channels on a sink that advertises passthrough but fails to open it ran L35 (useless for passthrough) and then L36 (NO_AUDIO); L34 (passthrough off) was unreachable.
- **Fix:** `encoded` is true when the exception's `format` (the sink **input**: `audio/raw` when decoding) is a passthrough encoding (AC-3, E-AC-3, E-AC-3 JOC, DTS, DTS-HD, TrueHD). The error-code-only row uses the renderer MIME against the same set (L34 runs once, then L35/L36 as before).
- **Tests:** `FailureClassifierTest.passthroughInitFailureIsEncoded` (all six encodings with `passthroughActive = false`), `FailureClassifierTest.decodedPcmInitFailureIsNotEncoded`.

### E-S4. `FROZEN_VIDEO` fires while the surface is gone (standby/display-off grace), giving a spurious re-prepare that counts toward compat escalation
- **Where:** `PlaybackControllerImpl.kt:1419-1425` (`videoExpected` ignored the surface); `PlaybackWatchdog.kt:203`.
- **Effect:** about 3 s into every standby grace, detector c fired (frames skipped without a surface while audio moves the position): an audio rebuffer, and two of them within 5 minutes persisted MULTI_PMT for the channel; a third ended in STREAM_UNDECODABLE.
- **Fix:** `SurfacePlayer` reports surface validity (its `SurfaceHolder` callbacks plus view attach/detach); `LifecycleCoordinator` reports grace start/end (`Target.onGraceChanged`). The controller's video expectation requires a valid surface and no running grace, and re-takes the watchdog baselines when either comes back. b1 does not depend on rendering and is unchanged.
- **Tests:** `LifecycleTest.theStandbyGraceSuspendsTheVideoExpectation`, `LifecycleTest.aDestroyedSurfaceSuspendsTheVideoExpectation`, `SurfaceAndSessionPlayerTest.surfaceValidityFollowsTheHolderAndTheView`; device I-20 now asserts no `FROZEN_VIDEO` during the grace.

### E-S5. The device suite waits on process-wide logs, so several waits return at once and some assertions can pass on an earlier test's events
- **Where:** `PanelTestBase.kt:195-204` (`awaitFirstMedia(tuneIdAtLeast)` over the global zap history) and `:215` (`events()` over the global ring), used by `awaitFirstMedia(1)` throughout the suite.
- **Effect:** "wait until the channel plays" returned before this test's tune played; I-5 could pass on I-4's `PCR_JUMP`, I-15 on any earlier `NO_BYTES`, I-20a on a stale record.
- **Fix:** `@Before` snapshots a cursor (highest tune id in the event log, zap history and state; and `elapsedRealtime`). `awaitFirstMedia` only accepts tunes after it and `events()` only events after it. States are also recorded with their time (`timedStates`) for I-4/I-5.
- **Test:** the suite itself (device results below).

### E-S6. Several device-suite assertions were weakened or are vacuous on the emulator
Restored to §5.5:
- **I-1:** first frame ≤ 10 s for this test's own tune.
- **I-4/I-5:** back to `Playing` within 5 s of the rebuffer (the `Reprepare` decision) that follows each jump.
- **I-6:** why 3 requests: every press of the burst is a repeat, so the debouncer produces one trailing tune (≤ 2 holds); that tune lands in the 2 s linger, and the ladder adds L2 `RetrySame` at 1.5 s (still refused) and L3 `ReResolve` at 3.0 s. Correct by design; recorded in §2.11. Asserted: ≤ 2 tunes and ≤ 3 stream requests per burst, `10/10` single zaps reach first media, and the final wait is on the final channel.
- **I-8:** every tokenised request directly follows a tokenless `/live/…` request (each reconnect starts from the original URL), with at least two connections in the run.
- **I-13:** the non-FFmpeg branch requires `AUDIO_UNSUPPORTED` or a decoder on the `audio/mpeg-L2` track with no warning, not "any decoder".
- **I-22:** MINI asserts no request; FULL asserts that AFR is ALWAYS and the 50 fps rate is known, then `assumeTrue(display has a 50 Hz mode)` before asserting a request, so on the 60 Hz-only AVD the switch is **skipped with a reason**, not passed. New I-22b does the same from FULL at launch (E-S2).
- **I-3:** stays `@Ignore`d (O-1). New I-3b runs `close_every: 15` on the audio-only ch 13: no error, ≥ 2 reconnects, no `Reconnecting` notice, no `ReResolve`, position advancing.

---
## Later (not applied)

Both lists, deduplicated. Two one-line items sat in code already being changed for the same reason and were taken (marked **taken**); the engine half's Later 3 is N-S6 (fixed above).

### Network, TS and logic (`N-L`)
- **N-L1.** **The ladder's `forbiddenSteps` counter is shared by L1, L7 and L21** (`ErrorLadderImpl.kt:310, 343, 416`). After a mid-playback L7 re-resolve (token expiry), a later HTML splash skips L21's account refresh and goes straight to L21b WEB_PAGE_INSTEAD. A later 401 skips L1 and goes to L1b. Split the counter (`authSteps`, `tokenSteps`, `htmlSteps`) or document the coupling.
- **N-L2.** **`beginChannel` is keyed by `stableKey` alone** (`ErrorLadderImpl.kt:100`; per WP-D's call). Zapping A → B → A clears A's 5-min compat window and its PCR, behind-live and reclaim windows, so escalation restarts on every return to a broken channel. The same stable key in two playlists shares windows. Key by `(playlistId, stableKey)`, and keep a small LRU of per-channel windows instead of one slot.
- **N-L3.** **L7 when the limiter refuses goes to L7b and never reaches L5's user-agent fallback.** The design says "limiter refused → L4/L6 rules", and L6 is preceded by L5. This is minor because the UA fallback is a re-resolve anyway.
- **N-L4.** **The UA override loses to a UA in secret or extra headers.** `HeaderPolicy.kt:46-52` offers secret and playlist headers before the override. If the controller computes `uaFallbackAvailable` only from `channel`/`playlist.userAgent`, the L5 retry sends the same UA again. Make `uaFallbackAvailable` false when any merged layer above the override set a UA.
- **N-L5.** **The byte watchdog can act on stale state** (`LiveTsDataSource.kt:541-554`). It reads `connActive` and the timing fields without a generation counter. If the loader starts a new connection between the watchdog's checks and its `cancelAll()`, the fresh call is cancelled and an attempt is wasted. Add `@Volatile var connGen` and cancel only if it is unchanged.
- **N-L6.** **The PCR PID is fixed for the session** (`PcrTracker.kt:31`). If an encoder restart changes the PCR PID, every later post-splice hold waits out the 500 ms or 256 KiB bound and the check is silently off. Re-arm the PID after a hold times out.
- **N-L7.** **The PCR tolerance against real panels.** A panel that answers a reconnect with its GOP cache (often a whole 6–10 s HLS-derived segment on Xtream-style restreamers) gives a delta of minus several seconds, so every in-source reconnect becomes a re-prepare. That is by design (Q9, D4), but the fake panel's `continue` mode resumes byte-exactly (`fakepanel.py:946-951`), so I-3 cannot see it. See Test gaps.
- **N-L8.** **`LOCAL_LIMIT` is the mapping for any `RuntimeException` from `broker.acquire`** (`LiveTsDataSource.kt:231-233`, `StreamHttpDataSourceFactory.kt:86-87`). A bug would read as CONNECTION_LIMIT_LOCAL. Map an unexpected runtime exception to a distinct kind, or to INTERNAL.
- **N-L9.** **`CatchupTarget`, `XtreamStreamRef` and `ArchiveRequest` are data classes whose `toString()` prints the password and secret header values** (`XtreamContracts.kt:47`; `CatchupTarget.headers` = `headerTemplates`, which holds `secretHeaders` raw). Nothing logs them today. M4 will pass them around, so give them redacted `toString()`s as `ResolvedStream` has.
- **N-L10.** **HLS `InvalidResponseCodeException`s keep their real `DataSpec`, `responseBody` and headers** (`StreamHttpDataSourceFactory.kt:102, 116`). This is by design for real HLS URIs, but an error page that echoes the username sits in memory and reaches `onLoadError` listeners. Consider `hide()` for every origin and keep the real URI only for HLS's own relative resolution, which needs `getUri()`, not the exception.
- **N-L11.** **`ReResolveLimiterImpl.stamps` only shrinks through `wouldAllow`** (`ReResolveLimiter.kt:17-34`). Keys acquired but never peeked again stay forever. This is bounded by channels watched and tiny.
- **N-L12.** **L16 with the limiter refusing after first media** (`ErrorLadderImpl.kt:389-392`) loops `RetrySame(1000)` without consuming `baseRotations`, and the stream-switch timeout does not apply after first media. The loop is bounded by the 60 s limiter window. Count the `RetrySame` against `transientRetries` and go to L16c when attempts run out.
- **N-L13.** **Learned columns can be reverted by M1's whole-row writes.** `ChannelPrefsRepository.edit` (whole-row upsert) racing `upsertPlaybackFlags`/`upsertFpsMilli` can write back the old `playback_flags`. `PlaylistRepository.update` (whole-row) can revert `xtream_output_learned`, and changing `xtream_output` through it does not clear the learned value; only `PlaylistDao.setXtreamOutput` does. M5's settings UI must use the targeted writes. Add a KDoc warning on `edit`/`update`.
- **N-L14.** **`DataPlaybackSourceProvider.refreshAccount` rate-limits on the wall clock** (`:97-100`). A clock step backwards makes `now - atMs` negative. The `in 0 until` check then treats the entry as stale, which is safe, but a forward step can suppress a refresh for 10 s. Use a monotonic clock.
- **N-L15.** **The sniffer does not recognise 192-byte M2TS/BDAV packets or a garbage prefix longer than 187 bytes** (`StreamSniffer.kt:102-112`). These become FORMAT_UNRECOGNIZED → L24 progressive, which also fails. This is rare for IPTV; consider a 192-byte stride test.
- **N-L16.** **`HeaderPolicy` drops `range`, `accept-encoding`, `host`, `connection` and `icy-metadata`, but not `content-length`, `transfer-encoding`, `te`, `upgrade` or `expect`.** OkHttp mostly overrides these, but a user `Transfer-Encoding` can confuse a panel. Extend `DROPPED`. **Taken** (HeaderPolicy was being changed for N-S1): `content-length`, `transfer-encoding`, `te`, `upgrade`, `expect` are dropped; `HeaderPolicyTest.transportOwnedHeadersAreDropped` extended.
- **N-L17.** **`LiveTsDataSource.failure()` defaults `status` to `telemetry.httpStatus`** (`:499`). Non-HTTP failures such as PCR, HTML or TINY on a reconnect report `200`, so the message reads "live pcr_discontinuity 200" and `ErrorDetail.httpStatus` may show 200. Pass `null` unless the failure is HTTP.
- **N-L18.** **The aligner buffer never shrinks** after a post-splice hold has grown it to about 512 KiB (`TsPacketAligner.kt:154-164`). It is per session and garbage after the tune, so this is acceptable. Note it in the §3.15 memory budget.

### Engine, controller and UI (`E-L`)
- **E-L1.** **Healthy reset not re-armed after an in-place decision.** `onFailure` cancels `healthyJob` (`PlaybackControllerImpl.kt:1055`), and it is rescheduled only in `onFirstMedia`. After an in-place `DropAudioAndReprepare` (`:1187-1193`) the counters never reset for the rest of the cycle. Re-arm it in that branch.
- **E-L2.** **`pause()` during Connecting/Stalled reports a false state.** It sets `Playing(paused = true)` (`:1279-1283`) while nothing is prepared. Pausing a Connecting state should stay `Connecting` (or cancel the tune).
- **E-L3.** Same as N-S6 (fixed above).
- **E-L4.** **`MediaSourceBuilder` failures always surface as `UNSUPPORTED_SOURCE`.** Any exception from `mediaSources.build` other than `UnsupportedStreamFormatException` maps to it (`:497-501`). A programming error should be `INTERNAL`. **Taken** (the same `catch` was being changed for N-S3): a builder exception other than `UnsupportedStreamFormatException` is now `INTERNAL`; `PlaybackControllerStateTest.aBuilderBugIsInternalNotUnsupportedSource`.
- **E-L5.** **`UnrecognizedInputFormatException` maps to `Parsing`.** On a PROGRESSIVE_OTHER session (`FailureClassifier.kt:100-102`) that leads to L27a `Reprepare` and then `STREAM_UNDECODABLE`. It should map to `FormatUnrecognized`/`UNSUPPORTED_SOURCE` with the "open in external player" action.
- **E-L6.** **Zap trace sections are left open.** `traceBegin` is paired with `traceEnd` only on first media (`:906`). Failed and superseded cycles leave async sections open in Perfetto. End them in `finishZapRecord`.
- **E-L7.** **`keepScreenOn` is false behind the unlimited-retry card.** With the L19 unlimited card shown (`cardShown`, `:1040-1041`), `setState(Error)` sets it to false while retries continue. The screen may sleep mid-recovery.
- **E-L8.** **Brief flicker between Playing and `Buffering(RECONNECTING)`.** When READY arrives while `telemetry.inReconnect` is still true, `onPlayerState(READY)` → Playing and the next `pollTick` → `Buffering(RECONNECTING)` (`:1560-1567`). This is cosmetic.
- **E-L9.** **Ladder context queries codecs on the main thread.** `ladderContext()` calls `engine.softwareVideoDecoderAvailable` (`MediaCodecUtil.getDecoderInfos`) on main at every failure (`:1101`). It is usually cached, but a cold query can take 100+ ms. Compute it once per MIME off the main thread.
- **E-L10.** **The global FFmpeg toggle is ignored until restart.** `PlayerFactory.create(softwareAudioFallback)` runs once, and B3 forbids rebuilding the player. Changing the setting has no effect until the process restarts. M5's settings UI must say "applies after restart", or the toggle must become a runtime per-MIME switch.
- **E-L11.** **AFR switches can wipe learned passthrough denials.** `routeDeniedPassthrough` is cleared on every `AudioCapabilitiesReceiver` change (`PlaybackControllerImpl.kt:703-706`). On API < 31 an AFR mode switch often re-syncs HDMI and fires an audio-capability change, so every AFR switch forgets L31/L34 denials and the next tune fails again first. Compare the capabilities and clear only when the encodings changed.
- **E-L12.** **`DisplayModeController` keeps a strong `SurfaceView` reference.** It holds `view` (`DisplayModeController.kt:91`, plus a holder callback) while `SurfacePlayer` deliberately holds a weak one. It is cleared on detach, so there is no leak in the normal flow. For symmetry, hold it weakly or clear it in `clear()`.
- **E-L13.** **Two milestone owners.** The watchdog decides its first-media milestone in its own `onTracksChanged`, which runs before the controller's because analytics listeners are dispatched first, so before the HDR-poster disable. It can report a video milestone the controller did not choose (`PlaybackWatchdog.kt:252-263` vs `PlaybackControllerImpl.kt:880-884`). This is harmless today because the controller accepts either milestone. Keep one owner.
- **E-L14.** **The watchdog's audio and passthrough flags come from callbacks of the last `AudioTrack`.** After an audio track switch with no new `AudioTrack` (bypass ↔ decode), `audioPassthrough` can be stale until the next init. This is low impact.
- **E-L15.** **Downmix can clip on loud 5.1 content.** `StereoDownmixProcessor` gains sum up to 1 + 0.707 + 0.707 on each side without normalisation (`StereoDownmixProcessor.kt:84-101`). Saturation is applied, but loud 5.1 content will clip. Consider Media3-style normalisation (divide by the sum of gains), or document the choice.
- **E-L16.** **HDR poster on a video-only stream times out.** In MINI with no audio track, the poster gives `milestoneVideo = false` and no audio ever advances, so the tune ends in NO_DATA_TIMEOUT after 15 s. Fall back to the video milestone when no audio group exists.
- **E-L17.** **The engine is created during composition.** `VideoLayer`/`surfacePlayer` → `ensureEngine()` builds the player and the `MediaSession` as a side effect of the first composition. It works, but `MainActivity.onCreate` should call `controller.prewarm()` so first composition is cheap and the order is deterministic.
- **E-L18.** **`VideoLayer` places the rect relative to its parent.** It places `videoRect` "in window pixels" relative to its parent (`VideoLayer.kt:34`). That is correct only when `VideoLayer` sits at the window origin. Add a debug assertion (`LocalView` position) or document it in the M3 hand-off.
- **E-L19.** **The debug harness's ladder settings are process-wide.** `PlaybackDebugActivity` is exported in debug builds (fine). The settings `update { PlaybackSettings(...) }` in `PanelTestBase` resets every field, which also resets M5's future persisted settings. This is fine in M2; note it for M5.

---

## O-1 decoder-output stall detector (proposal under review)

**Status: pending.** Copied from the engine half unchanged. Not implemented: a separate adversarial review of this proposal is running and the lead decides afterwards. I-3 stays `@Ignore`d. Note that the "recovery from E-B1's false-positive backstop" mentioned below no longer exists after the E-B1 fix; only Media3's 30 s stuck-buffering backstop remains for O-1 until then.

**What O-1 is, from the code.** The four §3.7 detectors gate on state:
- b2 (position stuck) and c (frozen picture) need READY + `isPlaying`;
- b1 needs BUFFERING with buffered-position growth < 500 ms (B6);
- d1 and d2 are about audio.

In O-1 the position freezes while extraction keeps adding media (the buffer fills to `maxBufferMs`), so the player must be in BUFFERING. In READY, b2 would have fired at 4 s and Media3's stuck-playing detector at 10 s on a device (30 s on the emulator). b1 sees growth of about 1 s/s and stays quiet. Recovery today comes from E-B1's false-positive backstop once the loader parks, or from Media3's stuck-buffering backstop (30 s of a flat **buffered** position, which only starts once the buffer is full). Once E-B1 is fixed, only the 30 s Media3 backstop is left.

**Signal.** Use `DecoderCounters` on the 1 Hz tick, which the watchdog already reads with `ensureUpdated()` (zero allocation). Read two more fields:
- `inputCount = queuedInputBufferCount`;
- `outputCount = renderedOutputBufferCount + skippedOutputBufferCount + droppedBufferCount`. Any output counts, whether rendered, skipped (decode-only, late) or dropped, so a slow but working decoder is not flagged.

In a normal BUFFERING (network starvation, I-16, or waiting for `afterRebufferMs`) the playback clock is stopped. Output buffers are then held, not released, and the codec stops taking input once its own buffers are full. Queued input therefore grows by at most the codec's in-flight capacity: DPB ≤ 16 plus about 4–10 input slots, ×2 with the AUD split of O-2. In O-1 the decoder "keeps accepting input but produces no output", so queued input grows without bound while output stays flat. That growth is the discriminator.

**Detector b3 "decoder output stall"** (new `StallKind.DECODER_OUTPUT_STALL`, or reuse `DECODER_STUCK`):

| Item | Value |
|---|---|
| Eligible | state BUFFERING, `playWhenReady`, `videoExpected` (with E-S4's surface check), `firstFrameRendered` in this session, not tunneling (counters are not updated when tunneled), not `inDataSourceReconnect`, past `SEEK_GRACE_MS` |
| Window | `DECODER_OUTPUT_STALL_MS` = 4,000 |
| Fires when | `outputCount` unchanged over the window **and** `inputCount` grew ≥ `DECODER_OUTPUT_STALL_MIN_INPUT` = 64 **and** buffered position grew ≥ `DECODER_STALL_MAX_MEDIA_GROWTH_MS` (500) |
| Verdict → ladder | L27 like b1: `Reprepare`, then the compat escalation |
| `:core` change | `WatchdogSample` gains `videoQueuedInputCount` and `videoOutputCount`, filled in `PlaybackWatchdog.fill` |

The 64-buffer floor is about 2.5 s of 25 fps input, or 1.3 s with O-2's two samples per frame. It sits well above any decoder's in-flight capacity, so it should not fire during a starved rebuffer.

**Double-firing.**
- **b1:** b1 requires growth < 500 ms and b3 requires growth ≥ 500 ms, so they are mutually exclusive in a window. If both map to `DECODER_STUCK` they also share its 15 s cool-down slot (`lastFiredAt[kind]`), so at most one verdict per 15 s either way.
- **b2/c:** they are READY-only, so no overlap.
- **Media3 `StuckPlayerDetector`:** every ladder action re-prepares, which resets its timers.
- **Escalation:** two b3 verdicts within 5 minutes raise the TS compat level. That is right for broadcast streams. For the emulator's soft decoder it is noise, which is one more reason the hardware run is needed before un-ignoring I-3.

**Not recommended:**
- `VideoFrameMetadataListener` as the signal. It fires only for frames about to be released, and it would have to stay attached on the playback thread for the whole session (§3.9.3 attaches it only until the fps estimate).
- `onDroppedFrames`. It reports drops, not absence of output.

**Thresholds to check on hardware (D4 run):** log `inputCount - outputCount` at the start and end of every REBUFFER on the low-end box. The maximum seen plus 50 % should replace 64 if it is higher.

---

## Verified correct (no action)

### Network, TS and logic half

**`LiveTsDataSource` (§3.3):**
- `open()` ignores `dataSpec.position`/`length`, counts a non-zero position, and builds the inner spec with position 0 and `LENGTH_UNSET`. It filters `Range` out of the resolved headers and always returns `C.LENGTH_UNSET` (`:131-154, 203-220`).
- `getUri()` is the placeholder and `getResponseHeaders()` is empty (S9).
- It never returns `RESULT_END_OF_INPUT`.
- `close()` and `closeInner()` cancel the call before closing the body (S2).
- Interruptions (`Loader.cancelLoading`) become a bare `InterruptedIOException`, not a reconnect.
- Pre-emption and the watchdog flag are checked before an IO failure is classified (S3).
- `terminate()` against `acquireLease()` is a correct volatile handshake: `terminated` is written before `calls` is read, and the reverse on the loader side.

**Post-splice hold (S1, plus the WP-B fix):**
- `checkHold` tests `pendingJumpMs()` before any release, including the hold check right after a reconnect, where the sniffed bytes may already hold the first PCR (`:359-376, 427-430`).
- A jump drops the held packets and throws `PCR_DISCONTINUITY`.
- The hold is bounded by 256 KiB and 500 ms.
- Without any earlier PCR, the check is off (`PcrTracker.kt:48-51`).
- The 33-bit signed delta and the adaptation-field length guard (≥ 7) are right.

**`TsPacketAlignerImpl` (B1):**
- It hands out only whole packets for reads of 188 bytes or more.
- A three-sync lock is required after construction and after every `resync()`.
- Sync loss mid-stream re-locks.
- CC values are rewritten at publish time with a forced +2 on the first payload packet of every previously published PID.
- Adaptation-only and null packets keep the offset.
- Dropped held packets never advance `lastCc`.
- I checked this against `TsExtractor.read`'s duplicate (`==`) and discontinuity (`!= prev+1`) rules: a +2 gap always triggers `payloadReader.seek()`.
- The overlapping `System.arraycopy` compaction is safe.

**Reconnect budget:** at most 3 attempts per 3 s burst, with a new burst only after a successful reconnect and bytes flowing again (§3.3.6 as amended).
- Delays are 0/500/1000 ms, and limit statuses and zero-byte 200s wait 1000 ms.
- A non-reconnectable status, HTML, HLS or unrecognised body on a reconnect surfaces at once.
- `RECONNECT_EXHAUSTED` carries the burst's last status and the network kind of the last attempt (`noBytes` = TIMEOUT).

**Byte watchdog:** it fires only while an open or read is in the source (`blockedSinceMs`), with the first-byte timeout (8 s, then 4 s on a reconnect) and the no-bytes timeout (4 s). It cancels through `Call.cancel()`. A parked loader never trips it.

**`StreamSnifferImpl`:**
- `#EXTM3U` and the HTML tags (including `<?xml` with `<html` within 512 bytes) are checked before TS.
- TS needs a three-sync run at an offset below 188.
- TINY is returned below 565 bytes at end of stream, and UNRECOGNIZED at 2048 bytes.
- The sniff buffer is zeroed after a non-TS verdict.

**Broker (`ConnectionBrokerCoreImpl`, `BrokeredCallFactory`):**
- Leases are per broker key, and `count = ACTIVE + CLOSING`.
- Pre-emption cancels outside the lock, each handle once.
- The 300 ms wait is followed by grant-after-wait only while CLOSING leases exist.
- RECORDING is never pre-empted, and PREVIEW is refused at max 1.
- `effectiveMax` treats 0 or negative as unknown (1) and caps at 50.
- Release happens only once the lease has no live call and is finished or pre-empted, driven by `callEnd`/`callFailed`. Those fire after the socket is closed: `RealCall.callDone` runs synchronously when the cancelled body is closed, and before `onFailure` for enqueued calls.
- A call created after pre-emption or `finish()` is cancelled before it reaches the network.
- There is no lock-order problem: no broker call is made while holding the factory lock, and nothing is cancelled under the broker lock.

**HTTP sources:**
- No Media3 default data source is used anywhere. Every stream request goes through `BrokeredCallFactory` (grep for `DefaultHttpDataSource`, `DefaultDataSource`, `newCall`).
- There is no `setUserAgent` (one UA only).
- HLS uses one lease per session, acquired once under `acquireLock`, and `end()` releases it.
- The archive placeholder is swapped for the resolved URL and hidden in failures.
- Unfinished bodies are cancelled before close; finished ones keep the keep-alive connection.

**`BoxLoadErrorHandlingPolicy` and `LoadRetryRulesImpl` against Media3 defaults and §4.2:**
- Every `LiveStreamException` is fatal.
- Media3's non-retriable set is preserved, including 416 via `POSITION_OUT_OF_RANGE`.
- 401/403/405/406/410/416/451/458/509 are fatal on every origin, enforced in both layers.
- An HLS segment 404 is retried twice at 500 ms.
- 408/429/5xx and IO on HLS and archive back off at 500/1000/2000 ms ±20 %, three times.
- IO on PROGRESSIVE is never retried. Because of that, Media3's resume-with-offset path for known-length progressive sources is not reachable on live.
- `getMinimumLoadableRetryCount` is 1 for progressive live, and there is no fallback selection.

**`ErrorLadderImpl`:**
- Every signal type reaches a row; `L44b` is only a catch-all.
- L1–L44 and the global rules match §4.3 as amended in §2.11: the G1 gate after a refresh, the cached-card shortcut, the stream-switch timeout replaced with the row's eventual code, unlimited L19 exempt from the timeout, and L22a normalising `TinyBody(0)` to 509 in the zap window.
- I found no contradictory rows. L8 is reachable for a mid-playback 503 and for a 503 with an account that has a free slot.
- The golden CSV covers L1–L44, L22a and the three global rules. It was written by the implementer, so it pins this interpretation and does not check it independently (see Test gaps).

**Smaller pure pieces:**
- `ReResolveLimiterImpl`: `wouldAllow` peeks without consuming, `tryAcquire` consumes, `reset` clears, and the window is sliding with `>=` pruning.
- `ZapDebouncerImpl` matches §3.5.4.
- `BufferProfileFsmImpl`: the table matches §3.6.1. A rebuffer at L0 goes to L1 at once; at L1–L2 the second rebuffer in 10 min escalates; L3 stays; decay is one level per 10 min stable and silent; manual modes are fixed.
- `StallDetectorsImpl`: thresholds match Appendix B, including B6's 500 ms and S11's 5 s passthrough. Every detector re-bases on broken preconditions, so nothing fires twice inside the cool-down. The global guards are present. Detector b1 is silent during in-source reconnects.
- `PlaybackEventLogImpl`: details are redacted before the 160-character cut. The rings are 200 and 50 entries.

**Resolver:**
- Expansion order: `{{base}}`/`{{ext}}` first, then the catch-up `TemplateExpander` (which now copies `{{…}}` through literally and does not report it as unknown), then `{{u}}`/`{{p}}` last.
- Credentials are path-encoded before the first literal `?` and query-encoded after it.
- The pass is single, so a password containing `{utc}` or `{{p}}` stays literal. Header values are raw.
- The raw, path-encoded and query-encoded forms of each credential are registered with `Redact` (S9).
- `ResolvedStream.toString()` and `StreamCredentials.toString()` are redacted.
- Every resolver log line prints only codes, class names or the redacted `toString()`.
- `xtreamOutput`/`shapedOutput` follow §3.2.3; learned output is read only under AUTO.
- `flipShapedExtension` keeps the short TS form.
- Direct URLs: playlist id `-1`, broker key `d:<host>`, no identity.

**`DataPlaybackSourceProvider`:**
- Writes go only through the targeted `setActiveBaseIndex` and `setXtreamOutputLearned`.
- The account refresh is coalesced per playlist (10 s) and refreshes the panel time base after a re-login.
- `toCredentials` keeps M3U secret URLs out of `baseUrls`.

**`RoomChannelMemory`:**
- It writes only changed columns through the targeted upserts. Bits 0–1 are the compat level and bit 2 is HDR.
- The controller's `remember()` catches failures (`PlaybackControllerImpl.kt:1674-1683`).

**`library.db` v2:**
- `2.json` against `1.json` differs only by `playlist.xtream_output_learned TEXT`, `channel_prefs.playback_flags INTEGER NOT NULL DEFAULT 0` and `channel_prefs.fps_milli INTEGER`.
- The primary key, foreign key and indices are unchanged. The migration is `@AutoMigration(1, 2)` with no table rebuild.
- The upserts conflict on the `(playlist_id, stable_key)` primary key, so they are indexed.
- Everything is keyed by `(playlist_id, stable_key)` as the plan requires.

**Fake panel:**
- A `Range` on live TS is logged, counted and optionally rejected, and the device suite asserts `range_requests_seen == 0` after every test (`PanelTestBase.kt:92`).
- Connection counting is per user with linger and an HLS session timeout. `linger_status` covers 403/458/509 and `close`.
- Client EOF is now detected within 50 ms. Real panels are slower; the `linger` fault models that.

### Engine, controller and UI half

- **One player, one surface:**
  - One `ExoPlayer` per process: created lazily on main in `ensureEngine()` and never released or re-created (B3). `PlaybackControllerImpl` is `@Singleton` and captures only the `@ApplicationContext`.
  - One `SurfaceView` via `PlayerSurface(SURFACE_TYPE_SURFACE_VIEW)` in `VideoLayer`. No `graphicsLayer`, clip or blur on its ancestors in the harness (`PlaybackDebugActivity.Harness`: a plain `BoxWithConstraints`; the semi-transparent overlay is a sibling, not an ancestor).
  - `SurfacePlayer` exposes only `COMMAND_SET_VIDEO_SURFACE`, no-ops every other mutation and clears a detached view (B3).
- **Manifests and permissions:**
  - No `MediaSessionService` and no `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in `player/` or `app/`. Checked in the merged release manifest too: media3-session adds only `BluetoothValidationActivity`, guarded by `BLUETOOTH_PRIVILEGED`.
  - `PlaybackService` is undeclared dead code.
  - The session refuses PLAY/NEXT/PREVIOUS in the background and advertises them only in the foreground (B5).
  - `WAKE_LOCK` is declared for `WAKE_MODE_NETWORK`.
- **Lifecycle:** Home/`ON_STOP` while interactive with the display on stops at once. The grace starts only on non-interactive or display-off, screen off, HDMI 1 → 0 (never the sticky broadcast) or display off. STANDBY renames a pending DISPLAY_OFF (D3). Resume re-resolves, and refreshes the account after 15 minutes.
- **Engine settings:**
  - Tunneling off by default (`PlaybackSettings.tunneling = false`).
  - HDMV DTS off (`TS_FLAGS`).
  - `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)` gated at API 31. The 2-argument clear is at API 30 and `clearFrameRate` at 34.
  - ExoPlayer strategy OFF for ALWAYS and ONLY_IF_SEAMLESS for SEAMLESS.
- **Threading:**
  - All `player.*` calls are on the main (application) looper: the controller's scope is `Dispatchers.Main.immediate`, data-source events are `mainHandler.post`ed, the display and route listeners use main handlers, and `FpsEstimator` posts its result to main.
  - Cross-thread switches are `@Volatile`: `AdaptiveLoadControl.profile`, `BoxMediaCodecSelector.videoMode`/`softwareAudioMimes`, `AudioOutputPolicy.deniedPassthrough`, `StereoDownmixProcessor.enabled`, and the `SessionTelemetry` fields and atomics.
  - `TransportFactoryImpl.endSession` never blocks the main thread.
- **`AdaptiveLoadControl`:**
  - Overrides only the `PlayerId`/`Parameters` overloads (api notes §5).
  - Byte cap 24/48 MB through the delegate's allocator target. Back buffer 0.
  - The `shouldStartPlayback` live-offset clamp matches `DefaultLoadControl`.
- **Codecs and audio:**
  - `BoxMediaCodecSelector`: AUTO is a stable HW-first partition; HARDWARE_ONLY drops software-only decoders; the secure and tunneling flags are passed through; per-MIME FFmpeg preference hides only audio platform decoders, and only when FFmpeg supports the MIME.
  - `PassthroughGateAudioSink` gates both `supportsFormat` and `getFormatSupport`. PCM is never denied.
  - `StereoDownmixProcessor` channel orders match Android's `CHANNEL_OUT_*` bit order for 3–8 channels. It is inactive for 1–2 channels, more than 8, or non-16-bit/float input.
  - `applySettings` resets the switches from settings before the controller unions in what the ladder learned, so session-only fallbacks do not accumulate across cycles.
- **Dead-track guard:** replays pre-format bytes and metadata in order. It drops metadata whose bytes were discarded (`size + offset > realBytes`), caps the buffer at 1 MiB and forwards `endTracks` once.
- **Controller state machine:**
  - `endSession` is idempotent (`session` is nulled first, `TuneSession.end` is guarded), stops the watchdog first and never clears the surface.
  - Re-entrant `watchdog.stop()`/`start()` from inside the watchdog's own tick does not double-schedule (`scheduled` flag).
  - Stale player errors are filtered by `timestampMs` against the session start, and `LiveStreamException` by tune id or PREEMPTED. Session events are checked against the current tune id.
  - The G1 account gate runs after an L3 refresh (D2).
  - `Buffering(RECONNECTING)` has its D1 exits: `RECONNECT_OK`, or the poll with READY.
  - `waitingForLease` maps to WAITING_FOR_SLOT and leaves on `LEASE`.
  - Card Retry resets the limiter and starts a fresh cycle.
- **Debug harness:** `PlaybackDebugActivity`, its `thebox-debug://` deep link and the Hilt entry point are in `app/src/debug` only and absent from release.
- **Logging:** `Media3LogBridge` flattens throwables into redacted `BoxLog` messages. No telemetry was added.

---

## Test gaps that remain

Closed by this pass: the parked-loader backstop (E-B1/N-S2), b1 on a slow network (N-S4), a bad header value and a non-HTTP scheme (N-S1, N-S3), the bounded secret set (N-S5), direct-URL limiter keys (N-S6), wake without `ON_START` (E-B2), zap lookup races with `stop`/`tune` (E-S1), AFR without a viewport change (E-S2, JVM; on the device only up to the evaluation, see I-22b), passthrough init classification (E-S3), watchdog expectations without a surface or during the grace (E-S4), test-scoped device waits (E-S5), and D4a on the device through the audio-only I-3b.

Still open:
1. **`LiveTsDataSource`:** a second reconnect while a post-splice hold is active, end to end through the source; hold release by `PCR_HOLD_MAX_MS` and by `PCR_HOLD_MAX_BYTES`; a stream without PCR across a reconnect; `terminate()` during the reconnect sleep (lease released by `terminate`); a watchdog cancel during the connect phase classified as NO_BYTES.
2. **The ladder golden table is self-referential** (`ladder.csv` was written by the `ErrorLadderImpl` implementer). A short design-derived table by someone else: L7 with the limiter refusing, L20's "no byte ever arrived", the account gate after L21, the shared `forbiddenSteps` counter (N-L1).
3. **The fake panel is lenient:** `reconnect_mode: continue` resumes byte-exact (add `rewind:<s>` and `live_edge`, N-L7), and the 1 s connect burst never fills the 20 s buffer (a `burst:<s>` fault plus a 60 s device check for no `NO_BYTES` would cover E-B1 end to end; the fix is covered on the JVM only). No device test plays a faster-than-real-time progressive URL for 60 s.
4. **Resolver:** a header value containing `{{p}}` with a non-ASCII password through the whole resolver into the transport (the builder and the transport halves are tested separately).
5. **Broker:** a same-owner re-acquire while its previous lease is CLOSING; `configure()` lowering `max` below the active count while a waiter is blocked.
6. **Persistence:** a whole-row `ChannelPrefsRepository.edit` racing `setPlaybackFlags` (N-L13).
7. **Controller:** a deferred zap + `stop` before it fires; a session replaced during a ladder delay (`RetrySame(1500)` → `tune(B)`); `pause()` during a ladder delay; watchdog verdict ordering against a resolving session; `ON_STOP` before `ACTION_SCREEN_OFF` while still interactive; one `RealEngineControllerTest` per audio fallback row (L31–L36) to pin classifier + context + ladder together.
8. **Engine:** `StereoDownmixProcessor` with odd frame remainders and float input; `TsExtractorsFactory` with a format arriving after 1 MiB; `DisplayModeController`, `StatsCollector.bind` ordering and `FpsEstimator` through the controller over the real parts.
9. **Device:** waking with the launcher in front (E-B2) and CH± then Home (E-S1) on the device; a real AFR switch (I-22/I-22b skip it on the 60 Hz-only AVD); I-3 (video D4a) until O-1 is decided and a hardware run exists.
10. **Device flake:** I-21 (hole punch) sometimes sees two identical centre regions 1 s apart on the API 30 emulator, at `abe7b54` as well; more samples or a longer interval would make it deterministic.
