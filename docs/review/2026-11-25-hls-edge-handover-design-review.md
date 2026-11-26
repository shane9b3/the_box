# HLS edge hand-over design: review (2026-11-25)

Design under review: `docs/design/m6-hls-edge-handover.md` (commit `eabd17f`). Reviewer method: I read the design, our
code at `eabd17f`, and the Media3 1.11.1 sources (`media3-exoplayer-hls`, `media3-exoplayer`, `media3-datasource`,
`media3-datasource-okhttp` sources jars from the Gradle cache, unpacked to the session scratchpad). Media3 paths below
are relative to `androidx/media3/`. The OkHttp 5.5.0 **core** sources jar is not in the Gradle cache (only
`okhttp-android`, `okhttp-dnsoverhttps` and `mockwebserver3` are), so the OkHttp claims below are from my knowledge of
OkHttp, not from the source, and are listed under "Not verified". I ran nothing: no build, no tests, no emulator, no
provider requests.

## Verdict: approve with changes

The core idea works inside Media3's model. The tracker's bundle URL never moves, but the data source can treat it as a
**logical** name and map it to the current edge on every request. The base moves on its own because `ParsingLoadable`
parses with `getUri()`. I checked the root-cause chain in the library and it is right.

The design still commits to a mechanism before three facts are known, and each of them decides whether a silent swap
is possible at all: what the viewer actually sees today, whether the edges agree on media sequence and timestamps, and
whether the edge is identified by host or by IP. Its own evidence (E7) contradicts its §4 stall model. Four spec defects
would also make some cases worse than today: a master body served to a variant loader, eviction of the tracker's
permanent URLs, a "same edge" branch that gives up exactly in the boundary race, and segment re-basing by guesswork.
Ship an instrumentation-only build first (B1), then build stage 1 with the fixes below. Drop stage 2 from this design.

| Severity | Count |
|---|---|
| Blocker | 1 |
| Major | 6 |
| Minor | 10 |

---

## What I verified (the root-cause chain holds)

- `ParsingLoadable.load()` parses with `dataSource.getUri()` after `open()` (`exoplayer/upstream/ParsingLoadable.java:222-225`).
  `OkHttpDataSource.getUri()` is `response.request().url()`, the final hop after OkHttp's redirects
  (`datasource/okhttp/OkHttpDataSource.java:216-218`). Ours forwards it (`StreamHttpDataSourceFactory.kt:137`), and
  OkHttp follows redirects internally (`PlaybackHttpClients.kt:56-57`).
- The parser makes that URI the playlist's `baseUri` (`exoplayer/hls/playlist/HlsPlaylistParser.java:440`, `:800`, `:855`,
  `:1534`). Variant URLs resolve against the master's base at parse time (`:601`, `:608`).
- Shape (a): a media playlist at the first load becomes a single-variant multivariant built from `result.baseUri`
  (`DefaultHlsPlaylistTracker.java:405-409`). That URL is `primaryMediaPlaylistUrl` and the bundle's final `playlistUrl`
  (`:445`, `:744`, `:919`, `:934`). Every reload requests `playlistUrl`, or `playlistUrl` plus directives
  (`:962`, `:1214-1227`, `:1229`). Nothing re-derives it from the `MediaItem`.
- Segments, init segments and keys resolve against the **current snapshot's** base when the chunk is built
  (`exoplayer/hls/HlsChunkSource.java:593-594`, `:1246`, `:1391`). The key cache is keyed by the resolved URI (`:661-662`,
  `:723`), so a base move just means one fresh key fetch.
- A re-prepare restarts the tracker from the `MediaItem` URI, which is O (`HlsMediaSource.java:545-549`). This matters
  for the fallback in M4.

So the pinning table in §2.3 is right for (a), (b) and (c). **What stage 1 changes in Media3's view:**

- `getUri()` reports E2 from the moment the inner `OkHttpDataSource` has its response. That covers the whole parse,
  because `ParsingLoadable` reads it after `open()`.
- The bundle's `playlistUrl` (E1) **never** moves for the life of the `HlsMediaSource`. Every later reload asks for E1,
  and the router must map it on every request, forever (M2).
- Chunk URLs already built against E1 exist only for the one loading chunk per sample-stream wrapper and its in-place
  retries (`Loader` retries the same loadable). Loaded chunks are bytes in the sample queue and do not matter.
- ABR in shape (b): every variant bundle keeps its own fixed E1 URL. An inactive variant's snapshot expires after
  `max(30 s, duration)` (`DefaultHlsPlaylistTracker.java:949-959`) and is reloaded on its E1 URL on a switch, so the
  mapping must cover every variant URL, not just the active one.

---

## Blocker

### B1. The mechanism is chosen before the facts that decide it; the design's own evidence contradicts its stall model (§1, §4, §5.5, §6.1)

**Evidence.**
- With our policy, a playlist reload failure reaches the player only once the chunk source is **stuck on that playlist**
  (it has loaded every listed segment) **and** the loader's error count exceeds 3. See `HlsChunkSource.java:241-247`
  (`lastPlaylistErrorUrl.equals(nextChunkStuckOnPlaylistUrl)`), `Loader.java:393-394` (`errorCount > minRetryCount`),
  `BoxLoadErrorHandlingPolicy.kt:68-69`, `:83` (min 3), and `LoadRetryRules.kt:30-31`, `:37-40` (backoff, 3 retries).
- The §4 timeline says the buffer (≈ 2–3 × TD for live HLS) runs dry at ≈ T+10–20 s, before the ≈ 23.5 s chain ends.
  After that, `HLS_NO_PROGRESS` needs a further 8 s of flat bytes **while buffering** (`StallDetectors.kt:12`, `:164-176`).
  That model predicts a frozen picture with a spinner for roughly 8–12 s, then the 2.4 s re-resolve.
- The owner reports 2–3 s. E7 orders the events as `failure Network` → `ErrorLadder L19` → release lease →
  **`state Buffering`**. That order means the error arrived with media still buffered. Either TD is large (≈ 10 s, so the
  buffer outlasts 23.5 s), or established connections to E1 survived the boundary while only the new connection failed
  (U2). Each answer changes the design: the time budget in §5.5/§6.7, which failure is the first one (§6.5 eligibility,
  M5), and whether the 2.5 s timeout is needed at all.
- The design's own reasoning says "Xtream `/live/…m3u8` is normally shape (a)". On Xtream-class load-balancer servers,
  each server often runs its own segmenter for the stream: its own `<id>_<n>.ts` counter and its own PTS base. If E1 and
  E2 are such servers, a silent swap cannot be seamless whatever the data source does (M4), and the right fix becomes a
  fast, quiet re-prepare instead.

**Failure.** Stage 1 costs ≈ 1.5–2 agent-days plus fake-panel work, and it could ship into a world where (i) the visible
part was never a stall (so `stall=0` proves nothing), or (ii) E2 is misaligned, so every hand-over ends in a
`PlaylistStuck`/`BehindLiveWindow` recovery that is no better than today's.

**Change.**
- Before any stage-1 code, the lead checks U7 in the saved logs (zero code): is there a `state Buffering` or
  `BufferingCause.REBUFFER` before each boundary `failure Network`, and how long?
- Then ship the stage-0 instrumentation build in "Recommended first step" and run it across at least four boundaries.
- Rewrite §4/§5.5 from what it measures, and gate stage 1's shape on U1, U2, U4, U5 and U7. Record that gate in §6.1.

---

## Major

### M1. Shape (b): re-entry returns the master body to a variant-playlist loader (§6.5 "Re-entry request")

**Evidence.** §6.5: "If the failed request was the manifest itself, this response **is** the answer: its body is returned
to Media3". In shape (b) the failing `MANIFEST` request is a **variant** media playlist, and O answers with the
**multivariant** playlist. The bundle's `onLoadCompleted` then sets `playlistError = ParserException("Loaded playlist has
unexpected type.")` and schedules no further reload (`DefaultHlsPlaylistTracker.java:1020-1025`; reloads are scheduled
only from `processLoadedPlaylist`, `:1213-1220`). When the chunk source runs out of segments, the error is thrown
(`HlsChunkSource.java:245-247`). We classify it as `FailureSignal.Parsing(manifest = true)` (`FailureClassifier.kt:118-120`),
which goes to the L27 stuck-pipeline rows (`ErrorLadderImpl.kt:269-270`): a "Stalled" state and an escalation counter
instead of a reconnect. That is worse than today.

**Change.** Serve O's body only when the failed URL maps by rule 1 to the route's `playlistUrl`, which is the URL O
itself redirected to. For any other `MANIFEST` URL (rule 2: variants and renditions), use O only to learn E(k+1): cancel
its body after the headers, as for segments, then open the mapped variant URL. Add the unit test
`shapeB_variantReload_neverServesMaster`.

### M2. The tracker's permanent URLs can fall out of the retired-edge LRU, and the mapping is defined only from R1 to R2 (§6.6, §6.7 "retired edges kept 8")

**Evidence.** The bundle URLs are fixed for the life of the source (`DefaultHlsPlaylistTracker.java:744`, `:919`, `:962`).
After hand-overs 1→2→…→9, every reload still asks for **E1's** URL. §6.6 defines mapping from "the retired route R1"
to "the current route R2" only. §6.7 keeps 8 retired edges in an LRU, so on a session that lives 4–5 hours (normal for TV
left on) generation 1 is evicted. From then on the E1 URL is not edge-bound (§6.4): it gets no tag and is not eligible.
It goes to the long-dead E1 with a 5 s timeout ×4, and the ladder re-resolves at a **random** time, not at a boundary.

**Change.**
- Make the model explicit. The URLs Media3 holds (the generation-1 playlist URL, every variant and rendition URL first
  seen, and any URL a still-loading chunk holds) are **logical names**. The router keeps a permanent `logical prefix →
  current route` mapping for them, outside the LRU, and every request on a non-current generation is mapped straight to
  the **current** route, never hop by hop.
- The LRU, if kept, holds only intermediate routes for in-flight chunk retries.
- Test: `tenHandovers_reloadStillMapped` (gen 1 → gen 11, the reload of the gen-1 URL goes to gen 11 without touching
  gen 1..10).

### M3. Edge identity by authority, and the "same authority → retry once untagged" branch, fail in the boundary race (§6.4, §6.5, §7 R3)

**Evidence.**
- E6 says the edge **IP** differs at every boundary. Nothing shows that the **authority** differs. A CDN edge is often a
  hostname whose DNS answer rotates. The E5 message (`failed to connect to /<ip> (port 443)`) is masked and cannot tell
  the two cases apart.
- If the authority stays the same, every re-entry takes the "same authority" branch, which retries the dead edge for
  5 s and then surfaces the error. Stage 1 then never works.
- Even when authorities differ, E2 shows the boundary is ±15 s. A failure detected a moment before the provider's
  redirect table flips gets E1 back from O, and the branch gives up exactly when one more O query 1–2 s later would have
  succeeded.

**Change.**
- Identify a route by its **full final URL** (path and token included, kept in memory only), and log the resolved IP as
  a short hash (`connectStart`'s `InetSocketAddress`) so U1 and E6 can be read.
- "Same route" means the same final URL **and** the same IP.
- When O returns the same route after an eligible connect-class failure, re-query O after 1 s and again after 2 s,
  within the re-entry budget and a total wall bound such as 6 s, before surfacing. A 403/404/410 from the edge with the
  same route returned is a token problem: surface it.

### M4. Segment re-basing (rules 2–3) is a guess that can silently serve the wrong media; continuity outcomes are mis-stated (§5.1, §6.6, §7 R4–R6)

**Evidence.**
- Rule 2 (`dirPrefix1 + rest → dirPrefix2 + rest`) is right only if E2 publishes the **same media under the same name**.
  With independent segmenters, `123.ts` on E2 is different media or a 404.
- The tracker's handling of a second edge is not what §5.1/R4 say:
  - **E2 entirely older** (`msn + size < old msn`): `PlaylistResetException` fires at once (`DefaultHlsPlaylistTracker.java:1172-1176`)
    → `BehindLiveWindow` (`FailureClassifier.kt:87`) → L26 seek-to-live re-prepare.
  - **E2 behind but overlapping**: `isNewerThan` is false (`HlsMediaPlaylist.java:1532-1537`) and the old snapshot is kept
    (`DefaultHlsPlaylistTracker.java:648-656`). The playlist sits stuck until 3.5 × TD (`:1177-1181`) → `HLS_NO_PROGRESS` → L19.
    At TD = 6 s that is ≈ 21 s, about today's cost.
  - **E2 ahead with no overlap and no PDT**: the start time falls back to the old snapshot's start (`:664-683`, comment at
    `:681`). The next chunk's MSN is then below the window → `BehindLiveWindowException` (`HlsChunkSource.java:542-544`) → L26.
  - **Same MSNs, different PTS base, no `EXT-X-DISCONTINUITY`**: no error at all. The timestamp adjuster for that
    discontinuity sequence puts the samples at the wrong time. The result is a freeze or jump until `FROZEN_VIDEO` /
    `POSITION_STUCK` fires. That is **worse** than today's clean re-resolve.

**Change.**
- Let the data source **scan** manifest bodies it serves (tee the bytes into a ≤ 64 kB buffer on `MANIFEST` opens and
  scan on close). It needs `EXT-X-MEDIA-SEQUENCE`, `EXT-X-TARGETDURATION`, `EXT-X-DISCONTINUITY-SEQUENCE`, the first
  `EXT-X-PROGRAM-DATE-TIME` and the segment URI lines. This is a line scan for routing, not a second parser.
- On the first E2 playlist, check alignment: the MSN ranges overlap, the segment name at a shared MSN is the same, and
  the PDT agrees within 1 s if present.
- Map a segment to E2 **only by MSN** (E1's URI at MSN n → E2's URI at MSN n), never by directory swap. Rule 3 (an
  authority-only swap) stays as a sub-case.
- If the edges are not aligned, do not serve E2 silently. Throw a typed `EdgeMisalignedException` (no URL) that the
  classifier maps to an immediate `Reprepare`. That keeps the lease, skips the 924 ms delay and the resolve, and the
  tracker restarts from O (`HlsMediaSource.java:545-549`). The result is a short, honest recovery instead of a
  20-second discovery by Media3.
- Fix the R4–R6 rows to match the outcomes above.

### M5. The eligible-failure set and the time budget miss the reused-connection case; the numbers in §0/§5.6/§6.7 contradict each other (§5.5, §5.6, §6.5, §6.7)

**Evidence.**
- The design makes only connect-phase failures eligible. Live HLS reuses keep-alive connections all the time: one
  segment and one reload every TD, and a 5-minute idle pool shared with the base client (`PlaybackHttpClients.kt:19-20`).
  If E1 black-holes established flows at T (U2 is open), the first failure is a **read timeout before the response
  headers** after `LIVE_READ_TIMEOUT_MS = 8 s` (`PlaybackHttpClients.kt:83`). OkHttp does not retry that on a new
  connection once the request was sent. It is not eligible, so the chain is 8 s + Media3's retry + 2.5 s before re-entry
  even starts.
- §0 says "less than the 10 s floor", but live HLS has no 10 s floor. The buffer ahead is capped by the target offset,
  `3 × TD` or `HOLD-BACK` plus the PDT live-edge offset (`HlsMediaSource.java:759-772`). It also swings by up to one TD
  with the reload phase (`DefaultHlsPlaylistTracker.java:1195-1213`). At TD = 2 s it can be ≈ 4 s.
- §5.6 says the worst case "stays under 8 s", but §6.7's worst case is 12.5 s. Past 8 s with an empty buffer,
  `HLS_NO_PROGRESS` fires and the ladder's `endSession` cancels the re-entry halfway.

**Change.**
- Eligible = any `IOException` on an edge-bound request **before response headers**: connect, TLS, or read-before-headers.
- On edge-bound `MANIFEST` requests only, set a headers deadline of ≈ 4 s through a per-call `Call.timeout()`. A
  playlist is a few kB, so that is safe.
- State one budget table with best, typical and worst case for each first-failure kind (reload or segment, new or
  reused connection) against `2 × TD` and `3 × TD`, using the TD measured by B1.

### M6. A failed re-entry can leak O's request and response into the exception (§6.5 "Surface" vs §6.3)

**Evidence.** §6.5 says "rethrow O's failure if it was an HTTP status". An `InvalidResponseCodeException` thrown by
`OkHttpDataSource` for O carries O's `DataSpec` (credentials in the Xtream path), O's response headers (a `Location` with
the next edge's token) and the error body. Today `hide()` exists exactly for this (`StreamHttpDataSourceFactory.kt:179-193`),
but it applies only to the placeholder case (`:102`, `:120`). §6.3 promises the opposite of §6.5.

**Change.** §6.5 must say "translate": a new `InvalidResponseCodeException(status, null, safeCause, emptyMap(),
failedRequestSpec, ByteArray(0))` built against the **failed edge request's** `DataSpec`, and for non-HTTP failures the
original edge exception. Add the unit test `o403_exceptionCarriesNoOriginUrlHeadersOrBody`, which walks the cause chain,
`dataSpec.uri`, `headerFields` and `responseBody`.

---

## Minor

1. **2.5 s edge connect timeout (§6.7).** It applies to every **new** connection to a learned edge, for every
   redirecting provider, all day, not only at boundaries. Linux/Android TCP retransmits a SYN at ≈ 1 s and ≈ 3 s, so
   2.5 s survives one lost SYN but not two. Use **3.5 s**: it covers the second retransmit and costs 1 s of hand-over
   budget. A per-request tag through an application interceptor is the right scope. Confirm with a unit test that the
   override reaches the route planner (see "Not verified").
2. **404 eligibility (§6.5).** An ordinary segment 404 (packager lag) today costs two 500 ms retries
   (`LoadRetryRules.kt:22-23`). Under the design every such 404 also costs an O hit, which is a new panel token on Xtream.
   Make a segment 404 eligible only on its second occurrence, or only if a reload of the same route failed too.
3. **Budget "then disabled for the session" (§6.7).** Sessions run for hours. One Wi-Fi blip that burns three
   re-entries puts every later boundary back on today's path. Disable for 10 minutes instead.
4. **Device offline.** When the device has no network, the edge fails, O fails, and each Media3 attempt grows from 5 s
   to 2.5 + 5 s, which delays the ladder during a real outage. Skip re-entry when the active network is not validated
   (`DeviceAwakeState`/connectivity) or when O failed with a connect-class error in the last 30 s.
5. **Range.** The re-entry GET of O must strip `Range` (a failed `EXT-X-BYTERANGE` segment carries one). A mapped segment
   keeps its position and length. State it in §6.5.
6. **Cancellation.** `Loader.cancelLoading` interrupts the loader thread (`Loader.java:420-427`). The single-flight
   latch must be an interruptible wait that throws `InterruptedIOException`, and `TransportSession.end()` must release
   it. R11 says "released on end()"; add the interrupt and the test `zapDuringReentry_noOCallAfterEnd_threadReleased`.
   The broker side is fine: `BrokeredCallFactory.newCall` refuses after `finish`/`forceRelease`
   (`BrokeredCallFactory.kt:42-47`), and `end()` cancels the O call with the others (`TransportFactoryImpl.kt:174-183`).
7. **Stage 2 (§6.11): drop it from this design.** It needs stage-1 data that does not exist. Its "past L19 failures with
   an edge recorded" input is not persisted anywhere today. Window mode adds O hits, each a new panel token, on 1-slot
   accounts (U6). If it is ever built, compute the phase on epoch milliseconds (UTC) mod P. A DST shift of 3600 s is a
   multiple of 1800 s, so UTC mod P is immune, but the "local" wall time in the §6.10 log line invites a local-time
   implementation that breaks in +5:30/+5:45 zones. Treat an NTP jump over 60 s as a forget event. Stage 1 plus D
   (quiet toasts, all sources) covers the owner's complaint.
8. **§2.2 M9.** The target offset also includes `EXT-X-START` and the PDT live-edge offset (`HlsMediaSource.java:762-772`).
   That makes the budget larger on PDT streams. Correct the row.
9. **Tests (§8.1/§8.2).**
   - The unit matrix needs the four alignment cases from M4 (older, overlap-behind, ahead-no-overlap, same-MSN-different-PTS),
     each asserting the **outcome class**: seamless, typed re-prepare, or ladder. It must never be a silent freeze.
   - The Robolectric HLS integration test is feasible, because the tracker's timing uses `SystemClock.DEFAULT`
     (`DefaultHlsPlaylistTracker.java:1160`) and Robolectric shadows it. It must script the playlists over time, not
     just the hosts.
   - Fake panel: add `edge_expire_mode = misaligned:<msn offset>|restart|pts_offset`. SYN-drop can often be
     approximated with a listening socket that never `accept()`s and has a full backlog. That is platform-dependent:
     Linux drops, macOS may refuse. Try it before declaring it impossible.
10. **Acceptance run (§8.3).**
    - Two boundaries is a thin sample. Run ≥ 4, or overnight with a summary line `HlsEdge: summary boundaries=n seamless=a reprepare=b ladder=c`.
    - Name the absent lines exactly: `state Buffering` with cause `REBUFFER` (not the ones at zap), `PlaylistStuck`,
      `BehindLiveWindow`, L26/L27 rows, and Media3 `onLoadError` with `wasCanceled = true`.
    - `stall=` must come from the controller's rebuffer accounting with a stated source, not the data source.

### On the alternatives (question 8)

- **`getUri()` = O for playlist loads.** That is B always on: one panel hit and one new token per reload. Relative and
  absolute-path segment URLs would then resolve against the panel host, and whether the panel serves `/hls/…` segment
  paths is unknown (§2.3). It is correctly rejected.
- **A custom tracker or a parser wrapper.** `DefaultHlsPlaylistTracker` is final and the identity comes from
  `result.baseUri` on the first load (`:407`), so a parser cannot split identity from base. The design's router already
  gets the same effect by treating E1's URLs as logical names (see M2). Not worth it.
- **Lower retry counts.** This does not remove the stall: L19 → `endSession` → `player.stop()` drops the buffer
  (`PlaybackControllerImpl.kt:1240-1253`). If B1 shows the error arrives with media still buffered, it would only move
  the 2.4 s gap earlier.
- **Seamless re-prepare.** Correctly rejected. A **fast** re-prepare (no resolve, same lease, tracker restarts from O) is
  the right fallback for the misaligned case (M4).

### Rules check (question 4/5)

- **Connection count.** On our side the count is right: one lease, calls attach to it, no new acquire
  (`TransportFactoryImpl.kt:139-165`, `BrokeredCallFactory.kt:38-49`). On the panel side, an O hit is a new
  `/live/…m3u8` request and a new token. It is no worse than today's L19 re-resolve, which hits O at the same moment.
  A **false** trigger (Minor 1, 2) is a new exposure, and the budget plus Minor 2 bound it.
- **Thread safety.** One data source instance per loader (`DefaultHlsPlaylistTracker.java:941`;
  `HlsChunkSource.java:218-222`). The router is the only shared state. A single lock plus a per-generation latch is
  enough.
- **No-op guarantee.**
  - Holds for providers without redirects: no route, no tag.
  - Holds for TS (untouched), archive and clips (`edgeHandover = false`), and the ring (TS only, `MediaSourceBuilder.kt:92`).
  - It is **not** a no-op for every redirecting provider that does not rotate edges: they get the shorter timeout and
    403/404/410 re-entry. §6.13 should say so and treat it as a behaviour change, for example a mid-play 403
    token expiry that today goes through L7 with an account refresh.

---

## Recommended first step

Build a **stage-0 instrumentation-only debug build**, with zero behaviour change, and run it on the owner's provider
across at least four boundaries. Before that, the lead reads the saved logs for U7, which needs no code. The build adds
three things:

- **Per request**, on live HLS sessions, from the existing per-call `EventListener` (`BrokeredCallFactory.kt:100-118`;
  add `connectStart`/`connectFailed`/`connectionAcquired`): `HlsProbe: req type=manifest|media|key host=#h ip=#i
  hops=<redirects> conn=new|reused connMs ttfbMs ms bytes status err=<class>@connect|headers|body`, with hosts and IPs
  as short hashes.
- **Per manifest**, from a tee of the body or from `player.currentManifest`: `HlsProbe: pl msn= n= td= dseq= pdt0=
  last=#<hash of last segment name> ref=rel|abspath|abs-other keys=none|rel|abs shape=media|master`.
- **On every `failure`**: `bufferedMs` and `liveOffsetMs`.

The re-resolve after each boundary already fetches E2's first playlist, so the logs line up E1's last playlist against
E2's first. That proves, with no mechanism risk:

- U1: the shape, and whether the edge is a different authority or only a different IP (M3);
- U2: whether reused connections succeed after T, or bodies are cut at T (M5);
- U4: whether E1 and E2 agree on MSN, names and PDT (M4), which decides "seamless swap" against "fast re-prepare";
- U5: TD and the real buffer at failure (B1, M5);
- U7: whether the viewer stalls before the error (B1).

Then build stage 1 with M1–M6 folded in, or, if U4 says the edges are not aligned, build the smaller typed fast
re-prepare plus D's quiet toasts.

## Not verified

- **OkHttp 5.5.0 internals** (core sources jar not in the cache):
  - that `chain.withConnectTimeout()` in an application interceptor reaches the route planner's TCP connect for the
    whole call, redirects included;
  - that one connect timeout on a single-IP route is not retried on the same route;
  - multi-IP and fast-fallback timing for a hostname edge;
  - that a read timeout after the request was sent is not retried (M5).
- **Line numbers I did not re-read**: the `HlsMediaChunk` lines cited as M6 (the base-URI resolution is confirmed at
  `HlsChunkSource.java:593`, `:1246`, `:1391`) and `HlsSampleStreamWrapper.java:1107-1111` (M7).
- Whether any listener or logger prints `LoadEventInfo.uri` or an exception's `DataSpec`. `Media3LogBridge` flattens
  messages only; I did not audit every analytics consumer.
- The owner's provider: shape, edge identity, TD, alignment, panel latency of O, and whether an extra O hit counts as a
  connection. I did not read `files/logs/*.log`, so U7 is unchecked by me.
- The SYN-retransmit schedule on Fire OS / Android TV kernels (assumed Linux defaults: initial RTO 1 s), and the
  backlog-full SYN-drop trick on the CI host.
- That the Robolectric HLS live test runs in reasonable time with the real tracker and chunk source.
- The exact L27 path for `FailureSignal.Parsing(manifest = true)` after its first row (M1 cites only the class it
  lands in).
