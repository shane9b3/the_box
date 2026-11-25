# HLS edge hand-over (the half-hour "reconnected" toast): design

Status: **proposed 2026-11-25** (proposer). Waiting for the adversarial review. Nothing is implemented.

Owner report (2026-11-25): "approx every 30 minutes my channels disconnect and then reconnect about 2-3 seconds later … it
has happened on multiple channels … the reconnected toast keeps showing up".

Inputs:
- The lead's log extract from the debug app on the emulator (owner's provider, addresses masked), and the lead's
  correction from `files/logs/previous.log` + `current.log` (16–25 Nov): the drops follow the **wall clock**, not the
  session's age (§1).
- Code at `e304f15` (line numbers below are at that commit).
- **Media3 1.11.1** (`gradle/libs.versions.toml:22`), sources jars from the Gradle cache
  (`media3-exoplayer-hls-1.11.1-sources.jar`, `media3-exoplayer-1.11.1-sources.jar`, `media3-datasource(-okhttp)-1.11.1-sources.jar`),
  unpacked and read for this document. Media3 paths are relative to `androidx/media3/`. OkHttp 5.5.0 (`libs.versions.toml:36`).
- `docs/design/m2-playback-core.md` §3.4 (HLS transport, one lease per session), `docs/design/m6-timeshift.md` §2 item 7
  (HLS live is not time-shift buffered).

---

## 0. Summary

- **Root cause.** The provider's host answers the HLS URL with a redirect to an edge server (port 443). Media3 keeps the
  **post-redirect** URL as the playlist's identity and base: every later media-playlist reload and every segment goes
  straight to that edge, never back through the provider's URL (§2). The provider retires edge assignments on a
  half-hour wall-clock schedule (≈ :02:35 and :32:35, ±15 s); after that the old edge drops our **new** connections
  (connect timeout). Media3 retries the same edge URL for ≈ 23 s, the buffer runs dry, the ladder (L19) re-resolves,
  and the viewer gets a stall, "Reconnecting", ≈ 2.4 s, "Reconnected". A TS channel does not have this problem because
  `LiveTsDataSource` reconnects through the pre-redirect URL by design (`LiveTsDataSource.kt:36-37`).
- **Not a regression.** Provider behaviour that our HLS path has always handled visibly. The pinning is Media3's, and
  nothing on our HLS request path changed since 2026-10-13 except the speed band and clip branch (§3.2). The same
  boundary appears in the logs on 2026-11-23 and 2026-11-24.
- **Recommendation (staged):**
  - **Stage 1, option A: edge re-entry in the data source.** Live HLS only. When a request to an edge we reached by a
    redirect fails with connect-class / 403 / 404 / 410, re-request the provider's original URL once, adopt the new
    edge, and serve the failed request from it. Media3 never sees the error, and the parsed playlist's base moves to
    the new edge by itself. Edge requests get a 2.5 s connect timeout, so a hand-over costs ≈ 3–5 s of buffer, which
    is less than the 10 s floor of the default profile. No toast and no frame drop when it works. If it fails, the
    path is today's (the ladder sees the original error).
  - **Stage 2, only if the stage-1 logs still show rebuffers at the boundary** (short segments, small live offset): a
    per-playlist **boundary learner** (period + wall-clock phase, hysteresis). Inside the learned window it sends
    media-playlist reloads through the original URL (option B, limited to about 50 s per half hour), so the
    new edge is adopted at the first reload after the boundary, without a failed request.
  - Not recommended: B always on, a proactive re-prepare (C as a re-prepare), or D alone. D's deferred toast is a
    separate owner choice (Q2).

---

## 1. Evidence

| # | Fact | Source |
|---|---|---|
| E1 | The channels play as HLS: `zap 3 HLS resolve=908 lease=1194 firstByte=2260 ready=2847 firstMedia=4846 ok`; `StreamResolver: resolved ResolvedStream(p:1, <provider host>, HLS, base=0/1)`. | lead's log |
| E2 | On 2026-11-25 every `failure Network` falls at about **:02:35 or :32:35** past the hour: 07:32:43, 08:02:32, 08:32:44, 09:02:33, 09:32:36, 10:32:32, 11:02:36, 11:32:38, 12:02:41, 12:32:28, 13:32:30, 14:02:46, 14:32:39, 15:02:37, 15:33:27, 16:03:29, 16:33:32, 17:03:26. Spread ±15 s around the phase; one outlier, 15:33:27, after a 403 at 15:18. | lead (saved logs) |
| E3 | A session fails at the **next** boundary whatever its age: one started 09:01:18 failed at 09:02:33 (79 s), one started 14:57:21 failed at 15:02:37 (320 s). `held≈1,800,000 ms` (`BrokerCore.kt:215`) is just the gap between two boundaries. | lead |
| E4 | Same boundary on earlier days whenever the app was playing across one: 2026-11-23 19:32:36, 2026-11-24 22:02:38. Not new. | lead |
| E5 | Each drop: `ExoPlaybackException: Source error <- HttpDataSourceException <- SocketTimeoutException: failed to connect to /<edge ip> (port 443) … after 5000ms`. All of them are failures to open a **new** connection. | lead |
| E6 | The edge IP is different at every boundary (five distinct IPs in five drops); the resolved provider host stays the same. | lead |
| E7 | Reaction: `failure Network (tune 5, …)` → `ErrorLadder: row=L19 signal=Network -> ReResolve(delayMs=924, …)` → release lease → `state Buffering` → re-resolve → new lease ≈ 1 s later → `state Playing` ≈ 2.4 s after the error, plus the "reconnected" toast. One drop was a `403` after two rebuffers. | lead |

Reading of E2–E6: the provider assigns each viewer an edge at redirect time and **revokes that assignment for
everyone** on a half-hour schedule. After the boundary the old edge silently drops SYNs from us (a timeout, not a
refusal), which looks like a per-IP allow-list on the edge. Whether connections that are **already open** survive the
boundary is unknown. If the allow-list is stateful, established flows usually survive. §6.12 says how to find out.

---

## 2. The request flow today (verified)

### 2.1 Our side

| # | Step | Where |
|---|---|---|
| F1 | HLS live: `MediaItem` URI = `ctx.resolved.url` (the provider URL, credentials in the path, as today), `HlsMediaSource.Factory(DataSource.Factory)`, policy `policyFor(HLS_MANIFEST)`. No `SessionUriDataSource` on HLS. Ring (`RingOpen`) is TS-only. | `MediaSourceBuilder.kt:112-137`, `:92` |
| F2 | `transport.http(ctx)` → `StreamHttpDataSourceFactory` over `clients.segments(…)`, sharing the tune's `TransportSession`. | `TransportFactoryImpl.kt:59-68` |
| F3 | One lease per HLS session, acquired at the first open, kept until `endSession`; every manifest, segment and key call attaches to it. | `TransportFactoryImpl.kt:153-174`, `BrokeredCallFactory.kt:38-49` |
| F4 | `BrokeredHttpDataSource.open`: real URIs pass through untouched; `OkHttpDataSource` over the brokered call factory. | `StreamHttpDataSourceFactory.kt:91-98` |
| F5 | Redirects are followed **inside OkHttp** (`followRedirects`, `followSslRedirects`); connect timeout 5 s, read 8 s. | `PlaybackHttpClients.kt:52-57`, `:82-83` |
| F6 | **`getUri()` returns `inner?.uri`, which for `OkHttpDataSource` is `response.request().url()`, the post-redirect URL.** | `StreamHttpDataSourceFactory.kt:137`; `datasource/okhttp/OkHttpDataSource.java:216-218` |
| F7 | Final host and redirect count are recorded per response (host only). | `BrokeredCallFactory.kt:106-108`, `TransportFactoryImpl.kt:248-252` |
| F8 | Policy: a transient `IOException` on HLS is retried with backoff 500/1000/2000 ms (±20 %), at most 3 times; 403/410 surface at once; 404 on a segment: 2 retries at 500 ms; minimum loadable retry count 3. | `BoxLoadErrorHandlingPolicy.kt:49-69`, `:89`; `LoadRetryRules.kt:17-40`; `ErrorLadderImpl.kt:49-53` |
| F9 | Ladder: `Network` after first byte → **L19** `ReResolve(retryDelay)`; a mid-play 403 → **L7** `ReResolve(0, refreshAccount)`; a segment 404/410 → **L12**. | `ErrorLadderImpl.kt:420-424`, `:354-360`, `:375-378` |
| F10 | `restart` → `Reconnecting(n,max)` notice + `Buffering(RECONNECTING)` → `launchSession` → `endSession()` (transport cancel, then `player.stop()`: buffer dropped, last frame kept) → new resolve, lease, `setMediaSource`, `prepare`. On first media: `Reconnected` notice. | `PlaybackControllerImpl.kt:2004-2026`, `:833-841`, `:1245-1253`, `:1013-1014`, `:1622-1625` |
| F11 | Contrast: `LiveTsDataSource` reconnects to `ctx.resolved.url`, "the pre-redirect URL, so every reconnect follows the redirect again". | `LiveTsDataSource.kt:36-37`, `:239` |

### 2.2 Media3 1.11.1: where the edge gets pinned

| # | Fact | Where (Media3 1.11.1) |
|---|---|---|
| M1 | A playlist is parsed with **`dataSource.getUri()`** (post-redirect, F6) as its URI: `parser.parse(dataSourceUri, inputStream)`. | `exoplayer/upstream/ParsingLoadable.java:224-225` |
| M2 | That URI becomes the playlist's `baseUri` (media and multivariant). | `exoplayer/hls/playlist/HlsPlaylistParser.java:440`, `:800`, `:855`, `:1534` |
| M3 | **Initial load is a media playlist** (no master): the tracker builds a one-variant multivariant playlist from **`result.baseUri`**. That URL becomes `primaryMediaPlaylistUrl` and the `MediaPlaylistBundle.playlistUrl`. | `exoplayer/hls/playlist/DefaultHlsPlaylistTracker.java:405-409`, `:445`, `:744` |
| M4 | Every reload requests `playlistUrl` (or `playlistUrl` + delivery directives). It is never re-derived from the `MediaItem` URI. | `DefaultHlsPlaylistTracker.java:962`, `:1219`, `:1223-1227`, `:1129-1159` |
| M5 | **Initial load is a multivariant playlist**: variant (and rendition) URLs are resolved against the master's `baseUri` at parse time. They are the bundles' fixed `playlistUrl`s. | `HlsPlaylistParser.java:600-608`; `DefaultHlsPlaylistTracker.java:740-745` |
| M6 | Each segment, init segment and key URI is resolved against **the current snapshot's** `baseUri` when the chunk is created: `resolveToUri(mediaPlaylist.baseUri, mediaSegment.url)`. | `exoplayer/hls/HlsMediaChunk.java:115`, `:145`; `HlsChunkSource.java:593`, `:1246` |
| M7 | A failed load is retried **with the same loadable** (same `DataSpec`, same URI): playlist `Loader.createRetryAction(false, delay)`, segment the same. | `DefaultHlsPlaylistTracker.java:1086-1092`; `HlsSampleStreamWrapper.java:1107-1111` |
| M8 | `HlsMediaSource.Factory(HlsDataSourceFactory)` exists. The tracker asks `createDataSource(C.DATA_TYPE_MANIFEST)`, the chunk source `DATA_TYPE_MEDIA` and `DATA_TYPE_DRM`. One data source instance per playlist bundle, one for media, one for keys. | `HlsMediaSource.java:167`; `DefaultHlsPlaylistTracker.java:190`, `:941`; `HlsChunkSource.java:218-222` |
| M9 | Live target offset: `PART-HOLD-BACK`/`HOLD-BACK` if present, else **3 × target duration**. | `HlsMediaSource.java:759-772` |
| M10 | Playlist stuck: no new segment for 3.5 × target duration → `PlaylistStuckException`. | `DefaultHlsPlaylistTracker.java:77`, `:1181` |

### 2.3 Which URL each request uses

O = the provider URL (`ctx.resolved.url`), E1 = the edge the first redirect chose.

| Provider shape | Media-playlist reloads go to | Segments go to | Pinned? |
|---|---|---|---|
| (a) O **is** a media playlist, O → 302 → E1 | E1's playlist URL (M3, M4) | E1 (M6) | **yes, both**: an edge that dies takes the whole session with it |
| (b) O is a master, O → 302 → E1, variants relative | E1-relative variant URL (M5) | E1, or wherever the variant redirects to (M6) | **yes, both** |
| (c) master not redirected (or variants absolute on the provider host), variant → 302 → E1 | the provider-host variant URL; the redirect is followed again on every reload | the edge of the **latest** reload (M6) | no; only an in-flight segment is exposed |

Xtream `/live/u/p/<id>.m3u8` is normally shape (a): the panel redirects to a tokenised media playlist on a streaming
server. That is not verified for the owner's provider (§3.3). The design covers (a) and (b), and (c) needs nothing.

---

## 3. Root cause, regression check, unknowns

### 3.1 Root cause

Media3 identifies a redirected live playlist by its post-redirect URL (M1–M5). Our `BrokeredHttpDataSource.getUri()`
reports that URL on purpose, so relative segment URLs resolve (F6; `StreamHttpDataSourceFactory.kt:30-31`). From prepare
onward the session talks only to E1. At the provider's half-hour boundary E1 stops accepting new connections from us
(E2–E5). Each request to E1 then fails after the 5 s connect timeout. Media3 retries the same URL three times (M7, F8):
≈ 5 + 0.5 + 5 + 1 + 5 + 2 + 5 ≈ **23.5 s** before the loadable is fatal. The default buffer is 10–20 s (L0,
`BufferProfileFsm.kt:24`), capped by the live offset (M9), so playback stalls first. Then either Media3's error or
`HLS_NO_PROGRESS` (8 s of flat bytes while buffering, `StallDetectors.kt:12`) reaches the ladder. L19 re-resolves:
`stop()` drops the buffer, the next resolve goes through O and is redirected to a live edge E2, and playback resumes
≈ 2.4 s later (E7). A redirect back through O would have reached a live edge at once.

The 403 variant (E7) fits the same picture. A 403 from the edge (token or assignment revoked) surfaces at once (F8) →
L7 → re-resolve.

### 3.2 Regression check: **confirmed, provider behaviour**

- `media3 = "1.11.1"` since M0 (`8217868`, 2026-10-13). The Media3 pinning is upstream behaviour and has not changed
  in our build.
- `PlaybackHttpClients` (redirects, timeouts) and `StreamHttpDataSourceFactory.getUri()` are unchanged since WP-B
  (`5c6f590`, 2026-10-13). `git log -S'LIVE_CONNECT_TIMEOUT_MS = 5_000'` and `-S'followRedirects'` both point only there.
- Later commits on this path: `d957767` (2026-11-23, live speed band, `MediaSourceBuilder.kt:118-129`), `d4edc3f` /
  `85ce40b` (2026-11-24, clip branch and captions switch), `209a368` (ErrorLadderImpl +2 lines, review fix). None of
  them touches redirects, URL identity, pooling, timeouts or the HLS live retry counts.
- E4: the same boundary shows on 2026-11-23 and 2026-11-24 whenever playback crossed one. What is new is that the
  emulator played all day on 2026-11-25.

I agree with the lead's conclusion. One nuance: this is not a defect in the provider's eyes. Edge rotation with
re-entry through the origin is a normal CDN pattern. Our HLS path just lacks the re-entry that our TS path has had
since M2 (F11).

### 3.3 Not verified

| # | Unknown | Why it matters | How to settle |
|---|---|---|---|
| U1 | Is the redirect at the master, the media playlist, or both (shape a/b/c)? | A's rewrite rule (§6.6) | stage-1 log `HlsEdge: learned … shape=` (§6.10) on the owner's provider |
| U2 | Does E1 keep serving **already-open** connections after the boundary? | whether a reused keep-alive connection hides the boundary for a while, and whether a proactive switch can be early | §6.12 connection-reuse log |
| U3 | Is E2 reachable through O **before** the boundary? | only then could a proactive switch avoid the failure entirely | stage-3 debug probe (§6.11.4), lead's run |
| U4 | Do E1 and E2 serve the same media sequence, segment names and timestamps? | continuity across the swap (§7 R4–R6) | stage-1 log `msn=` before and after; watch for `PlaylistReset`/`BehindLiveWindow` |
| U5 | The stream's target duration and live offset | whether stage 1 alone fits in the buffer (§5.5) | stage-1 log `td=` |
| U6 | Does the provider count an extra request to O (while the old session's edge connection is dead or alive) as a second connection? | B and stage-2 cost; a 403/458 risk on accounts with 1 connection | stage-1 runs (A hits O only after E1 is dead); stage 2 only after that is clean |
| U7 | Whether the viewer saw a stall before each `failure Network` (the "2–3 s" the owner reports may be only the re-resolve) | how much of today's gap A removes | lead: check for `state Buffering` before each `failure` in the saved logs |

---

## 4. What the viewer sees today

| t (from the boundary T) | Event |
|---|---|
| T … T + TD | next request to E1 (reload every target duration, segments while the buffer is under max) |
| + 5 s per attempt | connect timeouts; retries after 0.5 / 1 / 2 s (F8) |
| ≈ +10 … +20 s | buffer (10–20 s, or less with a short live offset) empty → spinner, picture frozen |
| ≈ +18 … +23.5 s | `HLS_NO_PROGRESS` or Media3's fatal error → L19 → "Reconnecting 1/n" toast |
| + ≈ 2.4 s | new session on E2 plays → "Reconnected" toast |

---

## 5. Options

### 5.1 A: transparent re-entry in the data source

Mechanics:
- When a request to an edge we reached through a redirect fails with a connect-class error or 403/404/410, re-request
  O once (OkHttp follows O → E2) and adopt E2.
- The failed request is then served from E2: the playlist URL maps to E2's playlist URL; a segment URL is re-based from
  E1's directory to E2's.
- Our `getUri()` reports the E2 URL. Media3 therefore parses the reloaded playlist with base E2 (M1, M2), and every
  later chunk resolves against E2 on its own (M6). Only the bundle's fixed `playlistUrl` (M4) and chunks already
  created (M7) still point at E1, and the data source maps those.

| Aspect | Assessment |
|---|---|
| Viewer | nothing, if the swap fits in the buffer: with a 2.5 s edge connect timeout, ≈ 2.5 + ~0.5 (O redirect) + ~0.5–1 (E2 fetch) ≈ 3–4.5 s against a ≥ 10 s floor (L0 min buffer, live offset permitting) |
| MSN continuity | Media3 decides from the playlist contents. If E2 lists the same MSNs, the swap is seamless. If E2's MSN jumps backwards, the tracker ignores the playlist until it is stuck (M10) → `PlaylistResetException` → ladder (today's visible path, no worse). |
| Segments listed but not yet fetched | they are resolved only when the chunk is created (M6), so after the first E2 reload they come from E2. Only the one already-created chunk (and its retries, M7) needs the re-base rule. |
| Discontinuities | E2 from the same origin packager: continuous PTS. A different packager without `EXT-X-DISCONTINUITY`: a timestamp jump → possibly a short A/V glitch or a stall → ladder (§7 R5). |
| Broker | the re-entry call goes on the session's existing lease (F3): still **one** lease toward `max_connections`. E1's call has already failed (its socket is closed) when O opens, so the old call is gone before the next one. |
| Loops | at most one re-entry per failed request; E1 is marked retired; O redirecting back to a retired edge, or failing itself, surfaces the error. Session budget: 3 re-entries per 120 s (§6.7). |
| Providers without redirects | no edge is ever recorded → the code path is never taken (no-op). |
| Risk | moderate: one class plus a rewrite rule; everything is inside `:player` datasource and unit-testable with fakes. |

### 5.2 B: always reload the media playlist through O

Mechanics: map E1's playlist URL to O on **every** reload, so the redirect is taken each time. Segments follow each
reload's base (M6).

| Aspect | Assessment |
|---|---|
| Viewer | invisible at the boundary for reloads; a segment in flight on E1 can still fail (needs A's segment re-base anyway) |
| Cost | one extra redirect per reload: every 2–10 s, i.e. 360–1,800 extra panel hits per hour per viewer. The panel's redirect latency adds to every reload, so the time at which new segments become visible slips (closer to "stuck"). |
| Provider counting | Xtream panels log each `/live/…m3u8` hit and issue a new token. Some count each token as connection activity, so the risk of 458/403 "max connections" or rate limits is real and untestable without the owner's account (U6). |
| Edge flapping | a load balancer may pick a different edge on every reload, so segments hop between edges. That is fine only if MSN and PTS agree across edges (U4), and it is unknown. |
| Verdict | **rejected as always-on**. Kept as a narrow tool for stage 2 (a short window per learned boundary). |

### 5.3 C: proactive refresh before the boundary (learned period + phase)

The lead's correction (§1) turns "learned lifetime" into a **wall-clock boundary**: period P ≈ 1,800 s, phase φ ≈ 155 s
past :00/:30, observed jitter ±15 s, one outlier. A learner is easy to build (§6.11). What to do with it is the
open part:

| Variant | Assessment |
|---|---|
| C1: seamless re-prepare (new `MediaSource` at the same position) | **rejected**. `setMediaSource` drops the buffer and re-inits decoders (F10). There is no HLS ring (`m6-timeshift.md` §2 item 7), and live HLS has no "same position" across a new source without a stall. It would add a visible event every half hour instead of removing one. |
| C2: switch edges a few seconds **before** φ (probe O, adopt E2 if it differs) | works only if O already hands out a new edge before the boundary (U3). Before T it probably still returns E1, so the probe gives nothing. It also costs an O hit while E1 is alive (U6). Experiment only (§6.11.4). |
| C3: inside a window [φ − 5 s, φ + 45 s], send reloads through O (B-windowed) and shorten the edge connect timeout | the first reload after T goes through O, gets E2 and moves the base without any failure. Cost: about 5–25 extra O hits per half hour. **Stage 2.** |

The reactive path (A) must work on its own: C3 only shortens the gap, and the learned phase may be wrong, may drift,
or may not apply to another provider.

### 5.4 D: keep the visible re-resolve, make it quieter

| Piece | Assessment |
|---|---|
| Suppress the toast for a sub-3 s recovery | `Reconnecting` is emitted when `restart` starts (`PlaybackControllerImpl.kt:2014-2017`), before the outcome is known. Suppression means deferring it ≈ 3 s and dropping both toasts when first media arrives sooner. Cheap; it helps every provider. A product choice (Q2). |
| Hold the last frame | already true: `endSession` → `stop()` keeps the last frame (`PlaybackControllerImpl.kt:1240-1243`). |
| Start from the buffered position | impossible across `stop()`/`setMediaSource` without an HLS ring. |
| Larger HLS buffer | buffered-ahead is capped by the live offset (M9: 3 × TD, e.g. 18 s at 6 s segments), not by `maxBufferMs`. Raising the profile cannot cover a 23.5 s retry chain. |
| Verdict | does not remove the stall (§4). Optional companion, not the fix. |

### 5.5 Can the buffer bridge the swap?

- Default L0 profile: start 1 s, min 10 s, max 20 s (`BufferProfileFsm.kt:24`, `AdaptiveLoadControl.kt:128-129`).
  Loading resumes below min, so at a random T the buffer is between ≈ 10 s and min(20 s, live distance).
- Live HLS cannot buffer past the newest listed segment. Playback sits at the target offset (M9) behind the live edge,
  so the buffer can never exceed ≈ 3 × TD (TD = 6 s: ≤ 18 s; TD = 2 s: ≤ 6 s).
- Today's chain (≈ 23.5 s) never fits. A's chain (≈ 3–4.5 s) fits whenever 3 × TD ≥ ≈ 6 s **and** the buffer is not
  already low. U5 decides whether stage 2 is needed.
- Escalated levels (L1–L3) do not help much for the same reason (live-offset cap).

### 5.6 Interactions

| Feature | Effect |
|---|---|
| Time-shift ring (`RingOpen`) | none: HLS live is never ring-buffered (`MediaSourceBuilder.kt:92`; `m6-timeshift.md` §2 item 7). |
| Keep up with live (`MediaSourceBuilder.kt:118-129`) | with A, playback is not interrupted, so the live offset does not change and speed control has nothing to correct. If the swap does stall for s seconds: ON catches up at 1.03× (≈ 33 s per stalled second); OFF stays s seconds further behind until the next re-prepare. This is today's behaviour after any stall and needs no change. |
| `BufferProfileFsm` escalation | today a boundary stall can count as a rebuffer and raise the level. With A there is no rebuffer, so no escalation. |
| Stall watchdog `HLS_NO_PROGRESS` (8 s) | A's worst case (connect 2.5 s + O + E2 ≤ ≈ 6.5 s, §6.7) stays under 8 s of flat bytes even if the buffer is already empty. |

### 5.7 Workaround available today

Setting the playlist's output to TS (where the panel allows it) moves the channel onto `LiveTsDataSource`, which
re-enters through O on every reconnect (F11). Tell the owner as an interim measure (Q4).

---

## 6. Recommended design

### 6.1 Stages

| Stage | Contents | Ships when |
|---|---|---|
| 1 | `EdgeRouter` per HLS live session: edge learning, re-entry on failure, playlist and segment mapping, 2.5 s edge connect timeout, logs and stats | now |
| 1b (owner's choice, Q2) | D: defer the `Reconnecting` toast 3 s and drop the pair on a fast recovery (all sources) | with stage 1, if the owner wants it |
| 2 | `EdgeBoundaryLearner` per playlist (period + phase, hysteresis) + window mode: reloads through O inside the window | only if the stage-1 logs show `stall=` > 0 at boundaries on the owner's provider |
| 3 | debug-only probe of O at φ − 10 s, logging whether the edge already changed (U3) | an experiment for the lead, never on in release |

### 6.2 Classes (all `internal`, `:player`, package `app.thebox.player.datasource`)

| Class | Role |
|---|---|
| `EdgeRouter` | one per `TransportSession` that has it enabled. Holds the origin O (a reference to `ctx.resolved.url`, never copied into logs), the current edge generation, retired edges, the playlist map, a single-flight re-entry, the budget, and counters. Thread-safe (Media3 calls from the playlist, media and key loader threads). |
| `EdgeRoute` (value) | `generation: Int`, `authority` (scheme+host+port), `playlistUrl` (the post-redirect URL O led to), `dirPrefix` (the playlist URL up to the last `/`), `query` (the playlist URL's query, if any). In memory only. |
| `BoxHlsDataSourceFactory : HlsDataSourceFactory` | wraps the session's `StreamHttpDataSourceFactory` and tags each source with its `dataType` (M8): `MANIFEST`, `MEDIA` or `DRM`. Used only for live HLS. |
| `BrokeredHttpDataSource` (changed) | new optional `router: EdgeRouter?` and `dataType`. When `router == null` nothing changes. |
| `EdgeTimeoutInterceptor` | OkHttp application interceptor on the `segments` clients: a request tagged `EdgeBound` gets `chain.withConnectTimeout(EDGE_CONNECT_TIMEOUT_MS)`. Untagged requests keep 5 s. |
| `EdgeBoundaryLearner` (stage 2, `:core`, pure) | per `playlist_id`, period + phase with hysteresis (§6.11). |

Wiring: `TransportFactory.http(ctx, edgeHandover: Boolean = false)`. `MediaSourceBuilder` passes `true` only on the live
HLS branch (`!isArchive`, not a clip), and uses `HlsMediaSource.Factory(BoxHlsDataSourceFactory(...))` there
(`MediaSourceBuilder.kt:132`). `TransportSession` creates the `EdgeRouter` lazily on the first `http(ctx, true)`.
Archive HLS, clips, progressive and TS stay as they are.

### 6.3 Where the original URL lives, redaction

- O is `ctx.resolved.url`, already in `StreamSessionContext` and already the HLS `MediaItem` URI (F1). The router
  keeps a reference and stores **no new copy** anywhere persistent. `MediaItem`, `DataSpec` handed back to Media3 and
  `LoadEventInfo` are unchanged.
- Edge URLs (E1, E2 playlist URLs, with their tokens) live only in the `EdgeRouter` of the session and die with
  `TransportSession.end()`. They are never logged, never persisted and never put in exceptions.
- What `getUri()` returns after a re-entry is the E2 URL. That is the same kind of value as today's post-redirect E1
  URL, and it must be, so the base moves (M1).
- Logs name edges only as `#<generation>` plus `Redact.host(...)` (host only, as `FailureInfo.host` already does).
  No path, query or token. The boundary learner stores numbers only.
- Exceptions rethrown after a failed re-entry are the **original** E1 exception (already Media3-visible today) or a
  `LiveStreamException`/`InvalidResponseCodeException` built against the request's `DataSpec`, with no O URL and no
  response body (as in `hide()`, `StreamHttpDataSourceFactory.kt:180-193`).

### 6.4 Learning edges, and which requests are edge-bound

- **Learning.** Whenever an open whose requested URL is O (the initial load, or a re-entry) completes with a final URL
  whose authority differs from O's, the router records `EdgeRoute(gen+1, …)` as current. For a multivariant
  response (shape b) the same applies, and the variant URLs fall under `dirPrefix` (§6.6).
  - Shape detection for the log only: the body content type (`application/vnd.apple.mpegurl` /
    `application/x-mpegurl`), and `MANIFEST` + whether a second `MANIFEST` URL is requested.
  - The data source does not parse playlists.
- **Edge-bound request.** One whose authority equals the current or a retired route's authority. Only these get the
  2.5 s connect timeout tag and are eligible for re-entry. A provider without redirects never records a route, so
  nothing is edge-bound: the no-op.
- **Shape (c).** The variant URL is on the provider host and is redirected on every reload. Reloads re-enter by
  themselves. A segment on an edge that dies is eligible because its authority was learned from a reload's final URL.
  So learning also runs on any `MANIFEST` open whose final authority differs from its requested authority, and it
  stores that request URL as the route's origin for re-entry (not O).

### 6.5 Re-entry state machine (per router)

```
           learn(O→E1)                 eligible failure on Ek (k = current)
  NONE ───────────────▶ ON_EDGE(k) ─────────────────────────────────────▶ REENTERING(k)
                           ▲   ▲                                              │
                           │   └──── O → same authority Ek: retry once ───────┤ (Ek not retired;
                           │         with full 5 s timeout, no tag            │  "slow, not dead")
                           │                                                  │
                           └──── O → new authority E(k+1): adopt, retire Ek ──┤
                                                                              │
          surface original error ◀── O failed / O→retired edge / budget out ──┘
```

- **Eligible failure** (`open()` only; a failure in the middle of a body is never re-entered, because the bytes
  already went to Media3):
  - connect-class: `SocketTimeoutException` with no response and no bytes (connect phase, OkHttp `connectFailed`
    seen), `ConnectException`, `NoRouteToHostException`, `UnknownHostException` of the edge host;
  - an HTTP response 403, 404 or 410 from the edge.
  - Not eligible: 5xx, 408 and 429 (Media3's backoff already handles them), 401, 458, 509, and any failure while the
    lease is pre-empted or the session has ended.
- **Single flight.** The first failing thread runs the re-entry; others that fail on the same generation wait on it (a
  latch, bounded by `REENTRY_WAIT_MS`), then use its result. A request on an already-retired generation does not
  re-enter; it is mapped to the current route at once (§6.6).
- **Re-entry request.** A GET of the route's origin URL (O, or the shape-(c) variant URL) through the session's
  `BrokeredCallFactory`, with the resolved headers and a 5 s connect timeout. If the failed request was the manifest
  itself, this response **is** the answer: its body is returned to Media3 and `getUri()` = its final URL. That costs
  one round trip. For a segment, the body is discarded (cancelled after the headers; a playlist is a few kB), and the
  segment is then opened at its mapped E(k+1) URL.
- **Adopt.** New authority and not retired → `gen = k+1`; retire Ek; log; count. Then serve the request.
- **Same authority.** The provider still assigns Ek: it is slow, not revoked. Retry the original request once untagged
  (5 s). If that fails, surface the original error.
- **Surface.** O itself failed: rethrow O's failure if it was an HTTP status (401/403/458/… carries the account or limit
  reason for the ladder), else the original Ek exception. Ek is not retired in that case.

### 6.6 Mapping rules (E1 URL `u` → E2)

Given the retired route R1 (`playlistUrl1`, `dirPrefix1`, `query1`) and the current route R2:

1. `u` equals `playlistUrl1` (or `playlistUrl1` plus delivery-directive parameters `_HLS_msn`, `_HLS_part`,
   `_HLS_skip`) → `playlistUrl2` with the same directive parameters. This covers every reload of shape (a).
2. `u` starts with `dirPrefix1` → `dirPrefix2 + rest`. The query: if `u`'s query equals `query1` (the playlist token
   copied onto segment URLs, as the fake panel and many panels do), replace it with `query2`; else keep `u`'s own query.
   This covers shape (a) segments, shape (b) variant playlists and their segments, and keys under the same directory.
3. Same authority as R1 but outside `dirPrefix1` (an absolute-path segment such as `/hls/<token>/x.ts`), and
   `playlistUrl1` and `playlistUrl2` differ **only in authority** → swap the authority. Otherwise there is no mapping.
4. No mapping → no re-entry for this request; the original error surfaces (today's path). The next reload, mapped by
   rule 1, still moves the base, so later chunks are fine. Only this one chunk's failure can reach the ladder, and it
   may not even do that if Media3's retries of a **new** chunk succeed.

Rules 1–3 change URLs only between routes **we observed** O redirect to. The router never invents a host.

### 6.7 Limits and timeouts

| Constant | Value | Reason |
|---|---|---|
| `EDGE_CONNECT_TIMEOUT_MS` | 2,500 | a live edge connects in < 500 ms; 2.5 s leaves margin on slow Wi-Fi. A false trigger costs one O hit and one untagged retry (§6.5 "same authority"). |
| re-entry connect timeout (O) | 5,000 (unchanged) | O is the provider host, same as resolve |
| `REENTRY_WAIT_MS` | 8,000 | upper bound for a waiting thread: O connect + headers |
| re-entries per request | 1 | no loops |
| re-entries per session | 3 per 120 s; then the router is disabled for the session (log once) | a provider that redirects into dead edges must not get hammered; the ladder takes over |
| retired edges kept | 8 per session (LRU) | bounded memory |
| worst case before Media3 sees anything | 2.5 + 5 (O) + 5 (E2) ≈ 12.5 s; typical ≈ 3–4.5 s | |

### 6.8 `max_connections` and the broker

- Every request, including the re-entry GET of O, goes through the session's `BrokeredCallFactory` (F3): one lease,
  counted once by `StreamConnectionBroker`, no new acquire, no change to the broker.
- Order: the Ek call has failed and its `callFailed` has run (socket closed) before the router opens O. During a
  re-entry the lease's live calls are at most today's (playlist + segment + O ≤ 3 on one lease, which HLS already does
  in parallel).
- Pre-emption: a pre-empted lease refuses new calls (`BrokeredCallFactory.kt:46-47`). The re-entry then fails with
  PREEMPTED, which is surfaced as today.
- Provider side: A hits O only after Ek is unreachable, which is the same moment today's L19 re-resolve hits O, but
  ≈ 20 s earlier and without ending the session. No new exposure to the provider's connection counting.

### 6.9 What the error ladder sees

- Successful re-entry: **nothing**. No `failure`, no `ReResolve`, no toast; the lease is kept.
- Failed re-entry: exactly what it sees today, the original exception through the Media3 retry chain (F8), or O's
  HTTP status (§6.5 "surface"). The rows (L19, L7, L12) are unchanged. The enriched `ErrorDetail` gains no URL.
- `FailureInfo`/stats get `edgeHandovers` so the error card's details can say "edge hand-overs this session: n" (a
  diagnostic for the details pane, not user copy).

### 6.10 Log lines and local stats (no telemetry)

Tag `HlsEdge` (BoxLog, debug builds and the saved log file as today):

| Line | When |
|---|---|
| `HlsEdge: learned #1 host=<h> shape=media\|master\|variant td=<s>?` | first route. `td` comes from the `X-…`/body only if trivially available; otherwise it is logged by the stats path (`Format`/timeline), not parsed here. |
| `HlsEdge: #1 fail type=manifest\|media\|key cause=connect_timeout\|refused\|403\|404\|410 conn=new\|reused after=<ms>` | eligible failure on an edge |
| `HlsEdge: reentry #1 -> #2 host=<h> in=<ms> served=manifest\|media\|key` | adopted a new edge |
| `HlsEdge: reentry #1 same-edge, retrying untagged` | O returned the same authority |
| `HlsEdge: reentry #1 failed cause=<…> -> surfaced` | path back to the ladder |
| `HlsEdge: disabled (budget)` | budget exhausted |
| `HlsEdge: boundary wall=<HH:MM:SS> phase=<s> stall=<ms> msnGap=<n>` | after each adopted re-entry, once the next chunk loaded: wall-clock time (local), phase mod period, any rebuffer ms between the failure and recovery, and the MSN step (from `HlsManifest` via the player's timeline, logged by the controller) |

Local stats (`SessionTelemetry`, shown in the stats overlay): `edgeGeneration`, `edgeHandovers`, `edgeReentryFailures`,
`lastHandoverMs` (how long the last one took). Not persisted, not sent anywhere.

### 6.11 Stage 2: boundary learner (wall clock)

#### 6.11.1 Model

Per `playlist_id`: period `P ∈ {900, 1800, 3600} s` and phase `φ ∈ [0, P)`, measured on the **device's wall clock
modulo P** (the provider's own clock and zone do not matter; only alignment to :00/:30 does). Inputs: the wall-clock
times of adopted re-entries (stage 1) **and** of past L19 failures on HLS with an edge recorded (so today's history
counts).

#### 6.11.2 Learning and hysteresis

- **Candidate.** Two events whose gap is within ±60 s of k × P for some k ≤ 4, and whose phases mod P agree within
  ±30 s.
- **Learned.** 3 events across at least 2 boundaries (as with other learned provider facts, three agreeing
  observations). φ = median of the phases.
- **Drift.** φ ← median of the last 5 phases (resists the 15:33:27-type outlier). Window half-width
  w = max(20 s, 2 × MAD) capped at 60 s.
- **Forget.** 3 consecutive learned windows in which the window-mode reload through O returned the **same** edge and
  no failure followed within 2 minutes → forget. A failure 2 or more minutes outside the window counts as a contrary
  observation (2 in a row → forget).
- **Persistence.** In the existing per-playlist learned-facts store (`provider.saveLearned…` family, alongside
  `saveLearnedOutput`/`saveActiveBaseIndex`): three numbers, `period_s`, `phase_s`, `confidence`. Keyed by
  `playlist_id` (no channel key; it is a panel property). Never assumed for another playlist.

#### 6.11.3 Window mode

Inside [φ − 5 s, φ + w + 30 s] of each period, while the session is live HLS with a learned route:
- media-playlist reloads to the current route's `playlistUrl` are mapped to the route's origin (O) instead (B, for
  ≈ 50–100 s per half hour). A reload that returns a new authority adopts it exactly as §6.5 "adopt", with no failure;
- edge-bound requests keep the 2.5 s connect timeout (already true from stage 1).
- After the first adoption inside the window, the window closes early for that session.
- Cost: TD = 6 s → ≈ 8–16 extra O hits per half hour, each one a redirect.

#### 6.11.4 Stage 3 experiment (U3): is the new edge available early?

Debug flag `debug.hlsEdgeProbe` (off by default and absent in release). At φ − 10 s, one GET of O on the session lease,
headers only, then cancel. Log `HlsEdge: probe -10s same|new host=<h>`. Two or three nights of logs answer U3. If
"new" is the norm, window mode can start at φ − 10 s and the hand-over becomes fully pre-emptive.

### 6.12 Does the old edge keep open connections? (U2)

The errors are all **new-connection** timeouts (E5). To tell whether E1 still serves established connections after T,
the stage-1 `EventListener` (already per call, `BrokeredCallFactory.kt:100-118`) records for edge-bound calls:
- `connectStart` seen or not (→ `conn=new|reused` in the `fail` line);
- for the last successful call **after T** before the first failure: `conn=reused` and its wall time. One such call
  proves E1 still answered on a pooled connection after the boundary;
- a read error in the middle of a body (`HlsEdge: #k body-error after=<bytes>` without re-entry) near T would show that
  established flows are cut too.

Reading the result: "reused OK after T, then a `conn=new` timeout" means the edge drops only new SYNs (a stateful
allow-list), so a pooled connection would have bridged the gap had it not idled out. Our 5-minute OkHttp idle pool
then matters, not the edge. "body-error at T" means established flows are cut, and pre-emption (U3) is the only way to
avoid a gap. Either way stage 1 is the same.

### 6.13 Scope

| Case | Behaviour |
|---|---|
| Live TS (`LiveTsDataSource`) | **unchanged**. It already re-enters through O (F11). Not touched: no `EdgeRouter`, no timeout tag. |
| Provider without redirects | no route learned → no tag, no re-entry, no `HlsEdge` log lines: a no-op (asserted by a test). |
| Archive HLS, clips, progressive | `edgeHandover = false`: unchanged (archive tokens are time-bound, and re-entering them is a separate design if ever needed). |
| Ring / time-shift | not applicable (HLS live is not ring-buffered). |
| Backup base (`base=0/1`) | the router belongs to the session; a ladder base rotation starts a new session with a new router. |

---

## 7. Failure modes

| # | Failure | Effect | Handling |
|---|---|---|---|
| R1 | O fails (panel down, 5xx, DNS) | E1 dead too: same as today | surface the original error → L19 re-resolve, which also fails → today's card |
| R2 | O returns 403/458/401 (account full or expired) | a real account problem | surface O's status → L7/L1/L4 rows give the right reason and next step |
| R3 | O redirects back to E1 | slow edge, not revoked | one untagged retry (5 s), then surface |
| R4 | E2 lists older MSNs than E1 had | the playlist is ignored until stuck (M10) | Media3 throws `PlaylistResetException`/stuck → ladder (today's visible path, once) |
| R5 | E2 timestamps jump without a discontinuity tag | A/V jump, possibly a stall | existing stall/PCR rows; log `msnGap` and look at it on the device |
| R6 | E2's MSNs are far ahead (behind the live window) | `BehindLiveWindowException` | existing row (re-prepare at live); counts as a visible recovery |
| R7 | Segment outside `dirPrefix`, paths differ (rule 4) | that one chunk fails after Media3's retries; next chunk on E2 | logged `no-map`; most likely invisible if the buffer holds through the next reload |
| R8 | Slow network makes 2.5 s trigger falsely | an extra O hit and an untagged retry; up to ≈ 7.5 s for that request | budget (3 per 120 s) caps it; log shows `same-edge` |
| R9 | Provider counts the O hit as a new viewer | possible 458 on 1-connection accounts | A hits O only when E1 is already dead (same as today's re-resolve). Stage 2 ships only after stage-1 logs are clean (U6). |
| R10 | Many loaders fail at once | duplicate O hits | single flight per generation |
| R11 | Session ends during re-entry | the waiting threads | `SessionScope.isEnded` checked before and after; the lease refuses new calls; the waiter's latch is released on `end()` |
| R12 | Learner wrong or drifted (stage 2) | a few extra O hits in the wrong window | the forget rule; stage 1 still catches the real boundary |

---

## 8. Test plan

### 8.1 Unit (`:player` `src/test`, Robolectric where Media3 needs it; `TransportTestSupport.kt` fakes)

Fake origin O + two fake edges (a scripted `Call.Factory` or `MockWebServer` ×3). O 302s to E1 until T, then to E2. E1
serves until T, then times out on connect (thrown `SocketTimeoutException` in the fake connect) or answers 403.

| Test | Asserts |
|---|---|
| `noRedirect_noop` | an O that serves the playlist directly: no route, no tag, request URLs byte-identical to today, zero `HlsEdge` lines |
| `shapeA_reloadAfterT_reentersOnce` | the manifest open after T returns E2's body; `getUri()` = E2 URL; exactly one O hit; lease count unchanged; no exception |
| `shapeA_segmentAfterT_mapped` | a segment `DataSpec` on E1 (rule 2 and rule 3 variants) is served from E2 |
| `shapeB_master_variantMapped` | a master on E1 with relative variants; the variant reload after T maps via `dirPrefix` |
| `shapeC_variantRedirect_noReentryNeeded` | reloads re-follow; only the in-flight segment is mapped |
| `sameEdge_retriesUntagged` | O → E1 again: one untagged retry, then the original exception |
| `oFails_surfacesOriginal` / `o403_surfacesStatus` | the exception type and status; no URL in the message, cause or `DataSpec` |
| `budget_disablesAfter3` | the 4th failure in 120 s surfaces; one `disabled` log line |
| `singleFlight` | two threads fail on gen 1 → one O hit |
| `midBodyError_notReentered` | a read failure after bytes surfaces as today |
| `preempted_duringReentry` | PREEMPTED surfaced; no O call after pre-emption |
| `edgeTimeoutTag_onlyEdgeBound` | the interceptor lowers the connect timeout only for tagged requests |
| `redaction` | captured log lines contain no path, query, token or credentials (the `Redact` test helper) |
| `HlsMediaSource_integration` | Robolectric `ExoPlayer` + `HlsMediaSource` over the fakes, 2 s segments, E1 dies at T: playback position advances through T with **no player error and no rebuffer**, and the buffered duration stays > 0 |
| `EdgeBoundaryLearnerTest` (`:core`, stage 2) | learns from 3 events at :02:35 ± 15 s; ignores an outlier; drifts; forgets after 3 quiet windows; never generalises across `playlist_id`s |

### 8.2 Fake panel (`tools/fakepanel`)

New stream fault `edge_rotate` (README table + `test_fakepanel.py`):
- `--edge-ports N` (default 2; ports `main+2 … main+1+N`) serve the redirected HLS like the existing redirect port.
- Fault keys: `edge_rotate_s` (period, e.g. 120), `edge_phase_s` (offset from the wall clock, so the test can align the
  boundary with :00 or with "now + 30 s"), `edge_expire_mode` = `403` | `404` | `refuse` (close the old edge's
  listening socket, so connects fail fast) | `hang` (accept, never answer: exercises the read timeout).
  - A true SYN-drop connect timeout cannot be reproduced portably in Python. The unit tests cover it, and `refuse`
    and `hang` cover the two neighbouring branches.
- At each boundary the redirect target moves to the next edge; an edge serves only tokens issued in its epoch;
  `/admin/stats` gains `edge_epoch`, `edge_switches`, and per-edge request counts.
- The HLS session count stays one session per (user, stream) (README line 127), so `max_connections = 1` exercises
  R9-like counting: `--max-connections 1 --stream-fault 8:edge_rotate_s=120`.

Emulator run on the fake panel (targeted, per `feedback-compact-emulator-tests`): channel 8 (live HLS), 2 s segments,
`edge_rotate_s=60`, 3 boundaries in each `edge_expire_mode`. Pass: 3 × `HlsEdge: reentry #k -> #k+1`, zero
`ErrorLadder:` lines, zero `Reconnect` notices, `stall=0` in each `boundary` line.

### 8.3 Owner's provider (lead's run, emulator, ≥ 31 minutes)

Start a channel ≥ 2 minutes before :02:35 or :32:35 and play through **two** boundaries (≈ 31–33 min). Success is proven
by these lines in `files/logs/current.log`:
1. `HlsEdge: learned #1 host=<…> shape=…` once, at the start;
2. at each boundary (±60 s of :02:35/:32:35), `HlsEdge: #k fail … cause=connect_timeout` followed within ≈ 5 s by
   `HlsEdge: reentry #k -> #k+1 … in=<ms>`;
3. `HlsEdge: boundary … stall=0`;
4. **absent** between start and end: `failure Network`, `ErrorLadder: row=`, `StreamBroker: … release lease=` (the
   original lease's `held=` must cover the whole run when the channel is stopped at the end), `state Buffering`, and
   the `Reconnecting`/`Reconnected` notices;
5. the zap record shows one `zap … ok` at the start only.

Also read: `conn=new|reused` around T (U2), `shape=` (U1), `msnGap` (U4), `td` (U5).

---

## 9. Work estimate and files

| Work | Files | Size |
|---|---|---|
| `EdgeRouter`, `EdgeRoute`, mapping rules, single flight, budget | `player/.../datasource/EdgeRouter.kt` (new) | M (≈ 250 lines) |
| `BrokeredHttpDataSource` hook (eligible failure, re-entry, `getUri`) | `StreamHttpDataSourceFactory.kt` | S–M |
| `BoxHlsDataSourceFactory` (data type) | `StreamHttpDataSourceFactory.kt` or new file | S |
| edge timeout tag + interceptor | `PlaybackHttpClients.kt`, `BrokeredCallFactory.kt` (`conn=new` in the listener) | S |
| `http(ctx, edgeHandover)` + session wiring + stats fields | `TransportFactoryImpl.kt`, `internal/Seams.kt`, `MediaSourceBuilder.kt` (HLS live branch only) | S |
| stats overlay row | the stats formatter in `:player`/`:app` | XS |
| unit tests (§8.1) | `player/src/test/.../datasource/EdgeRouterTest.kt`, `StreamHttpDataSourceFactoryTest.kt`, an HLS integration test | M |
| fake panel `edge_rotate` | `tools/fakepanel/fakepanel.py`, `README.md`, `test_fakepanel.py` | S–M |
| decision note | `docs/decisions/2026-11-25-hls-edge-handover.md` | XS |
| stage 1b (if chosen): deferred toast | `PlaybackControllerImpl.kt:2014-2017`, `:1622-1625`, `:2947-2949` + tests | S |
| stage 2 learner + window mode | `core/.../playback/EdgeBoundaryLearner.kt` (new), learned-facts store (`:data`), `EdgeRouter` | M |

Stage 1 ≈ 1.5–2 agent-days including tests and the fake panel, plus the lead's 35-minute run. Stage 2 ≈ 1 day, if
it is needed. No change to `:core`'s ladder, the broker, `LiveTsDataSource` or `library.db` for stage 1.

---

## 10. Open questions for the owner

| # | Question | Default if no answer |
|---|---|---|
| Q1 | Stage 2 sends a few extra playlist requests to your provider around each half-hour mark (≈ 10–20 per half hour, only after stage 1 shows a remaining stall). OK, or stay strictly reactive? | strictly reactive (stage 1 only) |
| Q2 | When the player does have to reconnect and recovers within ≈ 3 s, should it stay silent (no "Reconnecting/Reconnected" toasts), for all providers? | keep the toasts (today) |
| Q3 | Should a successful edge hand-over be visible anywhere besides the stats overlay (e.g. nothing at all)? | stats overlay only |
| Q4 | Until this ships: does your provider let you set the output to TS for this playlist? TS channels already reconnect through the provider's address and should not show the half-hour toast. | tell the owner; no code change |
