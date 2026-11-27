# HLS half-hour "Reconnected" toast: playlist-URL renewal (design)

Status: **revised 2026-11-25** after the adversarial review (`docs/review/2026-11-25-hls-edge-handover-design-review.md`)
and the stage-0 instrumentation run it asked for (B1). The first version of this document assumed the provider's edge
server dies at a wall-clock boundary; the measurement shows it does not (§1.2). This version replaces the edge
re-entry design with **playlist-URL renewal**, drops stage 2 (the wall-clock learner) and stage 3 (the probe), and folds
in every review finding (§9). Implemented on branch `hls-fix` (on top of the debug-only `HlsEdge*` probe); the lead
commits. **Revised again 2026-11-26** after the code review (`docs/review/2026-11-26-playback-fixes-code-review.md`):
§10 lists every change; where §6 and §10 disagree, §10 wins. Not yet verified on the owner's provider (§11).

Owner report (2026-11-25): "approx every 30 minutes my channels disconnect and then reconnect about 2-3 seconds later …
it has happened on multiple channels … the reconnected toast keeps showing up".

Inputs: the lead's log extracts (16–25 Nov); one instrumented hand-over on the owner's provider (2026-11-25 19:33, debug
build, `HlsEdge` probe lines, format in `HlsEdgeProbe.kt`); code at `b82d882` + the probe commit; **Media3 1.11.1** and
OkHttp 5.5.0 sources from the Gradle cache. Media3 paths are relative to `androidx/media3/`.

---

## 0. Summary

- **Root cause (measured).** The provider URL O answers the first request with a 302 to a **media playlist** on an edge
  host. Media3 pins that post-redirect URL L and reloads it for the life of the source. After 30 minutes the edge, on
  the same connection and IP, starts answering reloads of L with a **302 to another address that never connects**:
  L's token has expired. Each reload then costs a followed redirect plus a 5 s connect timeout; Media3 retries, the
  buffer drains, the ladder re-resolves, and the viewer sees a stall and "Reconnecting/Reconnected". A fresh request to
  O returns a new playlist URL **on the same edge host and IP** whose media sequence simply continues (§1.2).
- **Fix: playlist-URL renewal in the data source (live HLS only).** L stays Media3's *logical* name; the data source
  keeps, per logical URL, the *current* URL it actually requests. Pinned reloads are sent **without following
  redirects**, so an expired URL shows itself in one round trip (≈ 150 ms, not 5 s). On a 3xx, 401/403/404/410, a
  failure before the response headers, or a body that is not a media playlist, the data source requests O once on the
  same lease and thread, checks that the fresh playlist **continues** the last good one (§6.4), adopts its URL as the
  new current URL and returns the fresh body as the answer to Media3's reload, under Media3's own URI. The tracker sees
  an ordinary reload. Segments come from the fresh body (absolute paths on the unchanged host). No toast, no stall.
  (§10: a failure before the response headers adopts a renewal only when it succeeds; a redirect whose renewal does not
  succeed is followed as today before anything falls back.)
- **Defensive fallback.** Any other shape (the edge host really changes, the sequence jumps or restarts, td changes,
  ENDLIST, multivariant at start, O refuses or times out, budget spent) is detected, never guessed around: the reload
  fails with a typed, URL-free exception that Media3 does not retry, and the controller is told **at once**, so a
  re-resolve (L19p, no initial delay, §10.5) starts at once instead of after ≈ 14–20 s of drained buffer.
- **Quiet reconnect (lead's decision pending the owner, one constant).** A re-resolve that is back to rendering within
  3 s shows neither "Reconnecting" nor "Reconnected" (all providers). Past 3 s the notices show as today; the third quiet
  series within 10 minutes of one visit is announced at once (§10.6).
- **Not a regression.** Provider behaviour that our HLS path has always surfaced visibly (§3.2).

---

## 1. Evidence

### 1.1 From the owner's logs (lead, 16–25 Nov)

| # | Fact |
|---|---|
| E1 | The channels play as HLS (`zap … HLS … ok`); one provider host. |
| E2 | Failures cluster about 30 minutes apart (on 2026-11-25: 07:32:43, 08:02:32, …, 17:03:26), ± 15 s, on every channel, whatever the session age. |
| E5 | Each failure: `HttpDataSourceException <- SocketTimeoutException: failed to connect to /<ip> (port 443) … after 5000ms`: always a **new** connection that never connects. |
| E7 | Reaction: `failure Network` → L19 `ReResolve(≈ 924 ms)` → new lease ≈ 1 s later → Playing ≈ 2.4 s after the error, plus the "Reconnected" toast. |

The earlier reading of E2–E6 ("the provider retires the edge at a wall-clock boundary") is withdrawn: §1.2.

### 1.2 Measured behaviour (2026-11-25)

One captured hand-over on one channel, debug build, `HlsEdge` probe (hosts replaced here by `<edge>` / `<provider>`;
`#…` are per-process salted hashes; `k=media` is a media-playlist request, `k=seg` a segment, `k=pl` a playlist request
that did not complete). Times are the device's.

Steady state, session 1 (all reloads and segments go straight to the edge on reused connections, 200):

```
19:31:28.681 pl t=1 id=#bb6b21 shape=media edge=<edge>@#0bddca msn=3269 n=6 td=11 dseq=- disc=0 dur=60054 pdt0=- pdt1=- last=#e953da ref=abspath key=- keyref=- map=0 end=0 ll=0 dMsn=+1 same=0 why=change
19:33:01.929 fr ago=115232 req t=1 n=281 k=media rq=<edge> fin=<edge> hops=0 st=200 conn=R ip=#0bddca age=10603 use=3 … ttfb=149 tot=166 B=531
19:33:02.127 fr ago=16194 req t=1 n=300 k=media rq=<edge> fin=<edge> hops=0 st=200 conn=R ip=#0bddca age=21739 use=6 … ttfb=179 tot=186 B=529
19:33:02.128 fr ago=16194 pl t=1 id=#bb6b21 shape=media edge=<edge>@#0bddca msn=3277 n=6 td=11 … last=#78d766 ref=abspath …
19:33:02.131 fr ago=14168 req t=1 n=301 k=seg sn=#78d766 rq=<edge> fin=<edge> hops=0 st=200 conn=R ip=#0bddca … B=1169172
```

The 30-minute mark: the **same** pinned playlist URL, same host, same IP, reused connection, now answers 302 to some
other address; following it times out on connect (three reloads in a row, a different target IP each time):

```
19:33:02.131 req t=1 n=302 k=pl rq=<edge> fin=<edge> hops=1 st=302 conn=R>N ip=#0bddca>#593867 … tcp=5006 … tot=5307 B=0 err=HttpDataSourceException<IOException<ExecutionException@connect lerr=SocketTimeoutException
19:33:07.915 req t=1 n=303 k=pl rq=<edge> fin=<edge> hops=1 st=302 conn=R>N ip=#0bddca>#364cce … tcp=5008 … tot=5259 B=0 err=…@connect lerr=SocketTimeoutException
19:33:14.339 req t=1 n=304 k=pl rq=<edge> fin=<edge> hops=1 st=302 conn=R>N ip=#0bddca>#c8b5e0 … tcp=5002 … tot=5280 B=0 err=…@connect lerr=SocketTimeoutException
19:33:16.103 fail t=1 sig=Stall state=BUFFERING pwr=1 buf=97 off=- sincePl=30372 sinceSeg=28346 sinceReqFail=14178 reqFails=3 edge=<edge>@#0bddca msn=3277 td=11 age=1579754
19:33:16.131 decision t=1 d=ReResolve sig=Stall state=BUFFERING pwr=1 buf=97 off=-
```

The re-resolve through the provider URL lands on the **same edge host and IP** (the old pooled connection is reused),
with a new playlist URL (another playlist id hash) whose media sequence continues (3277 → 3280 after 16 s at td ≈ 11 s:
elapsed time only), and the same segment file names:

```
19:33:17.231 start t=2 origin=<provider> chan=#99b3ea after=fail+1129ms
19:33:18.233 req t=2 n=1 k=media rq=<provider> fin=<edge> hops=1 st=200 conn=N>R ip=#90ebd5>#0bddca … ttfb=947 tot=956 B=530
19:33:18.235 pl t=2 id=#479ed5 shape=media edge=<edge>@#0bddca msn=3280 n=6 td=12 dseq=- disc=0 dur=61324 … last=#978402 ref=abspath key=- … why=first
19:33:19.203 req t=2 n=3 k=seg sn=#78d766 rq=<edge> fin=<edge> hops=0 st=200 conn=R ip=#0bddca … B=1169172
```

Reading:
- **Shape:** O → 302 → a single media playlist (no multivariant), td 11–12 s, 6 segments (≈ 60 s window), absolute-path
  segment references on the edge host, no keys, no PDT, no discontinuities.
- **The edge is not dead.** Segment requests listed by the last good playlist answered 200 right up to the failure, on
  the same connection. Only the playlist URL stopped working, and it says so with a 302 at once; the 5 s is ours (we
  follow the redirect into a black hole).
- **This is a playlist-URL token with a 30-minute life**, not an edge switch. Same host, same IP, same segmenter
  (`#78d766` is the same file before and after), continuous media sequence.
- **What the viewer saw:** 14 s of failed reloads drained the buffer (`buf=97` ms at the failure, `BUFFERING`), then the
  re-resolve. On other marks the lead saw `Network` while still playing: either way a restart.
- **Caveat:** one hand-over, one channel. Other shapes (edge really changes, sequence not continuous, multivariant) are
  detected and fall back (§6.6); nothing assumes the captured shape without checking it.

---

## 2. The request flow today (verified)

### 2.1 Our side (unchanged facts from the first version)

| # | Step | Where |
|---|---|---|
| F1 | HLS live: `MediaItem` URI = `ctx.resolved.url` (O, the provider URL); `HlsMediaSource`, policy `policyFor(HLS_MANIFEST)`. Ring (`RingOpen`) is TS-only. | `MediaSourceBuilder.kt` HLS branch |
| F3 | One lease per HLS session, acquired at the first open, kept until `endSession`; every manifest/segment/key call attaches to it. | `TransportFactoryImpl.kt` `StandaloneScope.httpCalls`; `BrokeredCallFactory.kt` |
| F5 | Redirects are followed inside OkHttp; connect timeout 5 s, read 8 s. | `PlaybackHttpClients.kt` |
| F6 | `getUri()` = `OkHttpDataSource.getUri()` = the post-redirect URL. | `StreamHttpDataSourceFactory.kt`; `datasource/okhttp/OkHttpDataSource.java:216-223` |
| F8 | Policy: transient `IOException` on HLS retried 500/1000/2000 ms, ≤ 3 times; 401/403/410/… fatal at once. | `BoxLoadErrorHandlingPolicy.kt`; `LoadRetryRules.kt` |
| F9 | Ladder: `Network` after first byte → L19 `ReResolve(retryDelay)` (first delay 1 s ± 20 %); a mid-play 403 → L7; a segment 404/410 → L12. | `ErrorLadderImpl.kt` |
| F11 | `LiveTsDataSource` reconnects through O by design, so TS never had this. | `LiveTsDataSource.kt` |

### 2.2 Media3 1.11.1: what the mechanism relies on

| # | Fact | Where |
|---|---|---|
| M1 | A playlist is parsed with `dataSource.getUri()` as its URI (its base). | `exoplayer/upstream/ParsingLoadable.java:222-225` |
| M3 | Initial load is a media playlist: the tracker builds a one-variant multivariant from `result.baseUri`; that URL is the primary URL and the bundle's fixed `playlistUrl`. | `exoplayer/hls/playlist/DefaultHlsPlaylistTracker.java:405-409`, `:443-460`, `:744` |
| M4 | Every reload requests `playlistUrl` (plus LL-HLS delivery directives when the server offers them). Never re-derived from the `MediaItem`. | `DefaultHlsPlaylistTracker.java:962`, `:1129-1155`, `:1223-1240` |
| M11 | **The tracker never compares the loaded URI with `playlistUrl`**: `onLoadCompleted` keeps any `HlsMediaPlaylist`, `processLoadedPlaylist` works from its contents (newer-than check, stuck/reset detection, next reload time). So a reload answered with another URL's body under the same `getUri()` is an ordinary reload. | `DefaultHlsPlaylistTracker.java:1003-1026`, `:1157-1221`, `:646-662` |
| M6 | Segments, init segments and keys resolve against the **current snapshot's** `baseUri` when the chunk is created. | `exoplayer/hls/HlsChunkSource.java:593-594`, `:1246`, `:1391` |
| M12 | **A failed chunk is retried with the same loadable** (same `DataSpec`, same URL), never rebuilt from a newer playlist; an unpublished part's 404/410 is a plain `RETRY`. | `exoplayer/hls/HlsSampleStreamWrapper.java:1056-1065`, `:1106-1111` |
| M13 | A fatal playlist error reaches the player only once the chunk source is stuck on that playlist; a fatal chunk error only when a renderer has run out of data (not ready, or BUFFERING). Either way after the buffer drained. | `HlsChunkSource.java:241-247`; `exoplayer/ExoPlayerImplInternal.java:1501-1503`, `:1553-1558` |
| M14 | `OkHttpDataSource` with a no-follow client returns a 3xx as an `InvalidResponseCodeException(3xx)` carrying the `Location` header and the body; an interrupt during the call cancels it and throws `InterruptedIOException`. | `datasource/okhttp/OkHttpDataSource.java:284-310`, `:429-452` |

---

## 3. Root cause, regression check

### 3.1 Root cause

Media3 identifies a redirected live playlist by its post-redirect URL L (M1, M3, M4). L carries a token that the
provider expires after 30 minutes (§1.2). From then on every reload of L is redirected to an address that does not
connect; OkHttp follows it and waits for the 5 s connect timeout; Media3 retries the same URL (F8); after three failures
(≈ 14 s) the buffer has drained and the ladder re-resolves (E7). A fresh request to O would have answered within a
second with a working URL for the same stream.

### 3.2 Regression check: provider behaviour, not ours

Unchanged since the first version: Media3 1.11.1 since M0; `PlaybackHttpClients` (redirects, timeouts) and
`getUri()` unchanged since WP-B; later commits on the HLS path touch the live speed band and clips only. The same
half-hour failures appear on 2026-11-23 and 2026-11-24.

---

## 4. What the viewer sees today (measured, §1.2)

| t from the first failed reload | Event |
|---|---|
| 0 | reload of L → 302 (≈ 0.3 s) → followed → connect timeout 5 s |
| + 5.8 s, + 12.2 s | retries, same result (Media3, F8) |
| ≈ + 14 s | buffer empty (`buf=97`), `Stall` → L19 re-resolve, "Reconnecting" |
| + ≈ 1.1 s | new session; first playlist 1 s later; playback a few seconds later; "Reconnected" |

With renewal (§6): ≈ 0.2 s (the unfollowed 302) + one request to O (≈ 1 s measured, `ttfb=947`) inside a reload,
against a buffer of up to 3 × td ≈ 33 s at this provider. Nothing visible.

---

## 5. Options (short; the first version's §5 is superseded)

| Option | Verdict |
|---|---|
| **R: renew the playlist URL in the data source** (this design) | **chosen**: matches the measurement; one O request per 30 min; no Media3 change. |
| A (v1): edge re-entry on connect failure, segment re-basing | obsolete: the edge is not dead; re-basing segments was guesswork (review M4). |
| B: every reload through O | rejected (unchanged): one panel hit and one new token per reload. |
| C: proactive refresh on a learned wall-clock boundary (v1 stage 2) | **dropped** (review minor 7): renewal on the first unfollowed 302 costs one round trip; nothing to predict. |
| D: quiet toasts for fast re-resolves | adopted as a companion (§6.10), behind one constant. |
| A fast re-prepare instead (review M4's fallback) | not needed for the measured shape; the fallback path uses today's re-resolve, made immediate (§6.6). |

---

## 6. Design (as implemented)

### 6.1 Scope: where the router exists, and where it is a strict no-op

The router (`HlsPlaylistRenewal`) is built only by `TransportFactory.liveHls(ctx)`, which only the **live** HLS branch of
`MediaSourceBuilder` calls. Archive/catch-up HLS, clips, TS (live, archive, ring), progressive and the recorder build
none (the interface default of `liveHls` is Media3's own `DefaultHlsDataSourceFactory` over `http`, i.e. what was built
before). `HlsPlaylistRenewal.ENABLED` is the kill switch.

Inside a live HLS session the router starts UNKNOWN and decides on the first completed load of O:

| First load of O | State | Effect |
|---|---|---|
| not redirected (final URL = O) | OFF (`off:not_redirected`, debug, once) | strict no-op: every request as today |
| a multivariant playlist (review M1) | OFF (`off:multivariant`) | strict no-op |
| not a live media playlist (no `#EXTM3U`, no segments, or ENDLIST) | OFF (`off:not_media` / `off:endlist`) | strict no-op |
| redirected to a live media playlist | ACTIVE, one route L | §6.3 |

In OFF and UNKNOWN every request passes through `BrokeredHttpDataSource` unchanged (redirects followed, the same
exceptions, the same `getUri()` and headers); the only extra work is a line scan of Media3's first load of O.

### 6.2 Classes and wiring (`:player`, `app.thebox.player.datasource`, file `HlsPlaylistRenewal.kt`)

| Class | Role |
|---|---|
| `HlsPlaylistRenewal` | per-session router: state, routes, single flight, outcome logging, the fast signal. A `Terminable` of the `TransportSession` (so `endSession` releases waiters). Holds O only as `ctx.resolved.url` (no new copy). |
| `Route` | `logical` (L, the URL Media3 holds), `current` (the URL requested for it), the last good playlist summary and its time, a `fresh` flag (loop guard), and a sticky `fallback` outcome. In memory only. |
| `HlsRenewalBudget` | per playlist (`brokerKey`), shared by that playlist's sessions in `TransportFactoryImpl`: ≤ 3 renewal attempts per 120 s, then disabled for 10 min. |
| `RenewingHlsDataSourceFactory` / `RenewingHlsDataSource` | the live HLS `HlsDataSourceFactory`: one `RenewingHlsDataSource` per loader, around one `BrokeredHttpDataSource` (same client, lease, telemetry, scope as `http`). Knows its data type. |
| `PlaylistRebase` | rewrites only the references whose resolution against L differs from their resolution against the current URL (§6.12). |
| `HlsRenewalException` (`HlsRenewalMisaligned`, `HlsRenewalFailed`) | typed, URL-free, fatal for Media3 (§6.6). |
| `OnlineCheck` / `DeviceNetwork` | "is there a default network" (no renewal offline). |
| `BrokeredCallFactory.newCall(request, followRedirects)` + `NoFollowClients` | the lease's calls through a no-follow twin of the same `OkHttpClient` (same pool, dispatcher, interceptors, TLS settings), cached per client. |

The debug probe (`HlsEdgeDataSourceFactory`) now wraps the live `HlsDataSourceFactory` and asks it for the same data
type; it stays observe-only and debug-only.

### 6.3 Request handling (per `open`)

| Request | Handling |
|---|---|
| `MANIFEST` for O (Media3's first load, or a later re-prepare) | as today (followed), scanned while Media3 reads it; at close the router learns the final URL as a route (§6.1). |
| `MANIFEST` for a route's L (or L + `_HLS_*` delivery directives) | **pinned reload**: request the route's current URL (+ the same directives), **redirects not followed**, no `Range`; read the body (≤ 1 MiB) and check it is a media playlist (`#EXTM3U`, not multivariant). Good → it becomes the route's last good playlist, is rebased if needed (§6.12) and returned; `getUri()` = Media3's own URI; no response headers. |
| segments, keys, init segments, anything else | today's path (followed). A segment/key refused with 401/403/410 sends the fast signal once (§6.7). |

**Triggers** (a pinned reload that ends before a usable 2xx media playlist): any 3xx; 401, 403, 404, 410; an open-phase
I/O failure before the response headers (connect, DNS, TLS, read timeout before headers); a 2xx body that is not a
media playlist. Not triggers (today's path, the error translated per §6.9): 5xx, 408, 429, 416, a failure inside the
body, an interrupt, a lease pre-emption, the session's end.

**Detecting expiry without the 5 s wait:** the pinned reload goes through the no-follow client, so the expired URL's
302 arrives as the response (≈ the measured `ttfb` of 150–250 ms) instead of being followed into the connect timeout.

**Loop guard:** a 3xx on the *first* reload of a URL we just learned or adopted means this provider redirects every
reload, not that the URL expired: the router turns OFF for the session (`declined:loop`) and that reload is re-sent as
today (followed). Cost on such a provider: nothing (first-reload case) or one O request per session (if the redirect
starts only after a renewal).

### 6.4 Renewal and the continuity rule

On a trigger, on the same thread and lease: request O once (redirects followed as at session start, the session's
headers and user agent, no `Range`), read the body (≤ 1 MiB). Then, in order:

1. O failed → `failed:<phase>` (`status:<code>`, `connect`, `dns`, `timeout`, `tls`, `io`, `body`, `too_large`).
2. The final URL's scheme/host/port differ from L's → `declined:host` (§6.12).
3. Not a playlist → `failed:not_playlist`; a multivariant → `declined:multivariant`.
4. **Continuity** against the route's last good playlist (review M4), else `misaligned:<rule>`:
   - `media`: still a media playlist; `endlist`: no `#EXT-X-ENDLIST`;
   - `td`: target duration within max(2 s, 25 %) (measured: 11 → 12; was ± 1 s, §10.4);
   - `msn_back`: new media sequence ≥ the last good one;
   - `msn_ahead`: new media sequence ≤ last + its segment count + ⌈elapsed since the last good load / td⌉ + 2
     (measured: 3280 ≤ 3277 + 6 + 2 + 2);
   - `dseq`: discontinuity sequence not lower (absent = 0);
   - `names` (added to the brief's list): where the two windows overlap, the same media sequence number names the same
     file (salted hash of the last path segment): the same segmenter. Measured: `#78d766` before and after.
5. Rebase if L and the final URL resolve references differently (§6.12); impossible → `failed:rebase`.
6. **Adopt:** current URL := the final URL; last good := the fresh playlist; count it (`playlistRenewals`); return the
   fresh body as the answer to Media3's reload.

### 6.5 Bounds

| Bound | Value / rule |
|---|---|
| Single flight | per session: one renewal at a time; a second loader waits (interruptibly, ≤ 15 s) and then re-requests the new current URL once without renewing again. |
| Budget | 3 renewal attempts per 120 s per playlist (`brokerKey`, across sessions); the 4th is `declined:budget` and disables renewal for **10 minutes**, not for the session (review minor 3). While disabled the pinned reloads of new sessions are today's (followed). |
| Offline | no default network → `declined:offline`, no fast signal, today's error (review minor 4). |
| Per failure | one renewal; a route that fell back keeps its outcome (no further O requests, no second signal). |
| Cancellation | zap/stop: `TransportSession.end()` terminates the router (waiters wake and report PREEMPTED) and cancels the O call (the fetch reports cancelled); `Loader.cancelLoading` interrupts a waiter (`InterruptedIOException`) or the O call (OkHttp cancels it, M14). Review minor 6. |
| Body size | 1 MiB; a larger pinned body is served as today and turns the router OFF (`declined:too_large`). |

### 6.6 Fallback: typed, URL-free, immediate (review M4, M6)

Every `declined:*` (except offline), `misaligned:*` and `failed:*` outcome:
- throws `HlsRenewalMisaligned(rule)` / `HlsRenewalFailed(outcome)` built against **Media3's own request** (L): message
  `hls renewal <outcome>`, no cause, no headers, no body. `BoxLoadErrorHandlingPolicy` makes it fatal (no Media3 retry:
  each would cost another O request);
- sends the controller `PlaybackEventType.STALL` / `HLS_RENEWAL_FALLBACK` (detail = the outcome label), which maps it to
  `FailureSignal.Network(OTHER, beforeFirstByte = false)` → today's L19 re-resolve **now**, instead of after Media3's
  retries drained the buffer (M13). The re-resolve restarts the tracker from O (`HlsMediaSource` re-prepare).

Done in the code review pass (§10.5): the signal is `FailureSignal.PlaylistExpired`, ladder row **L19p** =
`ReResolve(0)` (limiter fallback `RetrySame(0)`), counted in `transientRetries` like L19, exhausted at L20.

### 6.7 Segments: what the brief asked, what Media3 allows

Brief: on a segment 3xx/401/403/404/410, renew its playlist and "let Media3's normal retry fetch the segment from the
fresh playlist". **Media3 does not do that**: a failed chunk is retried with the same loadable and URL (M12), so the
retry would ask for the old URL again; only chunks created after the next reload use the fresh references. And a fatal
chunk error surfaces only once the buffer is empty (M13). Remapping the failed segment's URL ourselves is what the review
(M4) forbids unless done by exact media sequence, and the measurement shows segments did **not** fail at the expiry.

Implemented instead (the best provable fallback): a segment, key or init segment refused with **401, 403 or 410** on an
ACTIVE router sends the controller `HLS_SEGMENT_REFUSED status=<n>` once per session, mapped to
`FailureSignal.Http(status, HLS_SEGMENT)`: the same ladder row Media3 would reach (L7 for 403), without waiting for the
buffer to drain. The segment's own exception is unchanged. No renewal on segment failures, no O request. 404 is left to
Media3's retries (packager lag, review minor 2). 3xx on segments is followed as today.

### 6.8 `max_connections` and the broker

- Every request, including the O request, goes through the session's one `BrokeredCallFactory` (same lease, F3): the
  broker's count is exactly today's (one lease per HLS session). No acquire, no broker change.
- Order: the pinned call has finished before O opens (the 3xx response is closed by `OkHttpDataSource` before it throws,
  M14; a connect failure has failed the call). Asserted: at the moment O receives the renewal request, the lease has one
  live call (O's).
- Provider side: one O request per 30 minutes per session, which today's re-resolve also makes (later, and with a new
  session). A false trigger is bounded by the loop guard and the budget.

### 6.9 Redaction

- O stays `ctx.resolved.url` (already the `MediaItem` URI). Route URLs (L, current) live in the router, in memory, and
  die with the session.
- Media3 never sees O's response or the current URL: the fresh body comes back under Media3's own URI with no headers;
  every failure of a pinned reload is rebuilt against Media3's `DataSpec` like `hide()` (same type, status and reason; no
  `Location`, headers or body; a cause only when it holds no `DataSpec`); O's exceptions never leave the data source
  (only their phase/status).
- Logs: hosts (`host[:port]`, IP literals hashed) and salted hashes only; no path, query, token or credential.

### 6.10 What the viewer sees

- A successful renewal: nothing. The stats overlay's HTTP row gains "n renewals" (§10.6: per tune and visit; `StreamStats.playlistRenewals`,
  `StatsFormatter`).
- **Quiet reconnect** (`PlaybackControllerImpl.QUIET_FAST_RECONNECT = true`, `QUIET_RECONNECT_MS = 3_000`; lead's decision,
  pending the owner, review question Q2): a transient re-resolve defers "Reconnecting" by 3 s from the first restart of a
  series; if playback renders (first media) before that, neither "Reconnecting" nor "Reconnected" is shown. Past 3 s
  both show as today; a later restart in an announced series announces at once. All providers (TS writer restarts too).
  The waiting state (`Buffering(RECONNECTING)`) is unchanged. `false` restores today's notices exactly.

### 6.11 Log lines (tag `HlsRenewal`, info level, release builds too)

| Line | When |
|---|---|
| `renewed t=<tune> pl=#<id> trigger=<redirect:302\|status:403\|io:timeout\|body> in=<ms>ms edge=<host> msn=<a>><b> td=<a>><b> n=<count>` | adopted |
| `declined:<host\|budget\|disabled\|busy\|multivariant\|offline\|loop\|too_large> t=… pl=#… trigger=… in=…` | not attempted / not adopted |
| `misaligned:<td\|msn_back\|msn_ahead\|dseq\|endlist\|names\|media> t=… pl=#… … msn=<a>>? td=<a>>?` | continuity rule broken |
| `failed:<status:<n>\|connect\|dns\|timeout\|tls\|io\|body\|too_large\|not_playlist\|rebase> t=… pl=#…` | O failed |
| `segment:<status> t=…` | segment fast signal |
| debug, once: `active t=… pl=#… edge=<host> td=<s>`, `off:<reason> t=…` | router decision |

The controller adds `hls playlist renewal <outcome>: asking the ladder` (tag `PlaybackController`) on a fallback. The
debug `HlsEdge` probe stays.

### 6.12 A different host, or a different directory

- **Different scheme/host/port** (the edge really changed): **declined** (`declined:host`) and fall back. Reason: the
  same host is the measured case; a different host may be a different segmenter, and the continuity rule cannot see a
  different timestamp base (review M4's "same MSN, different PTS" case would freeze silently). Falling back is today's
  behaviour, made immediate.
- **Same host, different path** (a token in the directory): Media3 resolves references against L, so a relative
  reference would point into the expired directory. `PlaylistRebase` rewrites exactly the references whose resolution
  against L and against the current URL differ (Media3's own `UriUtil.resolve`), to their absolute form on the current
  URL: URI lines and `URI="…"` of `EXT-X-KEY`, `-MAP`, `-PART`, `-PRELOAD-HINT`, `-RENDITION-REPORT`. The body is handled
  as ISO-8859-1 so every untouched byte round-trips exactly; a non-ASCII reference aborts (`failed:rebase`). In the
  measured case (absolute paths, same host) nothing changes and the same byte array is returned. Applied to the renewal
  answer and to every later reload of that route.

---

## 7. Failure modes

| # | Situation | Outcome |
|---|---|---|
| R1 | O down / 5xx / DNS / timeout | `failed:*` → immediate L19 (whose resolve then fails as today → card) |
| R2 | O 401/403/458 (account) | `failed:status:<n>` → immediate L19; the re-resolve meets the same status and the account rows (L1–L7) decide |
| R3 | O → another edge host | `declined:host` → immediate L19 |
| R4 | fresh sequence older (segmenter restart) | `misaligned:msn_back` → immediate L19 (instead of Media3's `PlaylistReset` or a stuck playlist, review M4) |
| R5 | fresh sequence too far ahead / td changed / ENDLIST / dseq back / other names | `misaligned:*` → immediate L19 |
| R6 | same MSNs, same names, different timestamps | not detectable from the playlist; only on the same host, where the measurement shows one segmenter. Residual risk, watched by the stall/frozen-video detectors |
| R7 | provider redirects every reload | loop guard → OFF for the session, today's behaviour |
| R8 | renewals in a storm | budget: 3 / 120 s per playlist, then 10 min off |
| R9 | zap or stop during a renewal | O call cancelled, waiters released, PREEMPTED, no signal (tested) |
| R10 | offline | no renewal, today's error and retries |
| R11 | multivariant provider | OFF at start: today's behaviour (not covered by renewal; v1's shape (b)) |
| R12 | the controller ignores the signal (stale session) | the typed exception still surfaces through Media3 when the buffer drains → classifier `Network` → L19 (today's path) |

---

## 8. Tests and checks

### 8.1 Unit (`:player`, Robolectric, MockWebServer; `HlsRenewalTestSupport.kt` = fake origin + edge + edge2 reproducing §1.2)

`HlsRenewalTrackerTest` (Media3's real `DefaultHlsPlaylistTracker` 1.11.1 over our data sources, 1 s td, Robolectric clock):
- `theTrackerKeepsItsUrlAndReadsTheRenewedPlaylistAsAReload`: no load error, `maybeThrowPrimaryPlaylistRefreshError`
  clean, primary URL and snapshot `baseUri` = L throughout, sequence monotonic, the latest snapshot's segments resolve to
  the new token's paths on the same edge, every `LoadEventInfo` URI is O or L, two O requests, one lease, one `renewed`.
- `aRestartedSequenceIsATypedFatalErrorAndAnImmediateSignal`: `HlsRenewalMisaligned`, `wasCanceled` (fatal), one
  `HLS_RENEWAL_FALLBACK misaligned:msn_back` event, the new URL never reaches Media3.

`HlsPlaylistRenewalTest` (the tracker's request pattern on the data sources):
- `capturedHandOverIsRenewedInPlace`: `getUri()` stays L, no headers, the fresh body, the expired 302 not followed, the
  lease's only live call at O time is O's, one lease never released before the end, UA kept, no `Range` (renewal and
  reloads), later reloads to the new URL, segments from the new references, td 11 → 12 accepted, log line redacted.
- fallbacks, each asserting the label, the event, no URL/header/body in message, cause chain and `DataSpec`, fatal
  policy, and a second reload failing the same way without a request: `theEdgeReallyChangedIsDeclined`,
  `theMediaSequenceJumpingBackIsMisaligned`, `…TooFarAheadIsMisaligned`, `aTargetDurationChangeIsMisaligned`,
  `anEndedPlaylistIsMisaligned`, `aDiscontinuitySequenceGoingBackIsMisaligned`,
  `otherSegmentNamesAtTheSameSequenceAreMisaligned`, `theProviderRefusingIsAFailureWithoutItsResponse` (403, with a
  secret header and body), `aProviderServerErrorIsAFailure` (503), `aProviderTimeoutIsAFailure`.
- `theBudgetAllowsThreeRenewalsThenDisablesForATime` (shared budget, next session not pinned until 10 min pass),
  `offlineNeverRenewsAndKeepsTodaysError` (302 translated: no `Location`), `aRedirectOnTheFirstReloadMeansThisProvider
  RedirectsEveryReload`, `aMultivariantStartStaysOutEntirely`, `aProviderThatDoesNotRedirectIsANoOp`,
  `aSegmentRefusedSendsTheFastSignalOnceAndNoRenewal` (and 404 sends none), `deliveryDirectivesAreKeptOnTheCurrentUrl`,
  `aZapDuringARenewalReleasesBothLoadersPromptly` (O stalled; both loaders back in < 2 s, single flight, PREEMPTED, no
  signal, lease released), `aWaitingLoaderIsInterruptible`, `aDirectoryChangeOnTheSameHostRebasesRelativeReferences`
  (byte-exact elsewhere, same array in the measured case), `continuityRuleOnTheMeasuredNumbers`.

`HlsRenewalWiringTest`: TS live/archive/ring, HLS archive, HLS and MP4 clips and progressive build no router through the
real `TransportFactoryImpl`; live HLS builds one per session; the `MediaItem` URI is unchanged.
`LadderExecutionTest`: quiet reconnect (no notices within 3 s; both past 3 s), the fallback event → `Network(OTHER)`,
the segment event → `Http(403, HLS_SEGMENT)`.

### 8.2 Fake panel (`tools/fakepanel`, README "HLS playlist-token expiry")

Faults `playlist_token_ttl` (s), `token_expire_mode` (`redirect_dead` default: 302 to `10.255.255.1`, `redirect_refused`,
`403`, `404`), `token_msn` (`continue` default, `restart`). Python tests `TestPlaylistTokenExpiry`.

Emulator check (lead, minutes): `python3 tools/fakepanel/fakepanel.py --port 8080 --set 'allowed_output_formats=["m3u8"]'
--stream-fault 1:playlist_token_ttl=90`, play channel 1 for ≈ 4 minutes. Pass: one `HlsRenewal: renewed … trigger=redirect:302`
per 90 s; no `failure Network`, no `ErrorLadder: row=`, no notices, one `zap … ok`. Then `token_msn=restart`: each expiry
logs `misaligned:msn_back`, the controller's `asking the ladder` follows at once, and the re-resolve is quiet if < 3 s.

### 8.3 Owner's provider (lead's run, ≥ 31 minutes; better overnight)

**Release gate note (§10.7):** to validate HLS renewal on a playlist that the Auto TS retry would move to TS, force
Stream format to HLS first.

Start a channel and play across at least two half-hour marks. Success, per mark: one
`HlsRenewal: renewed t=… trigger=redirect:302 in=<≈1000>ms edge=<edge host> msn=<a>><a+1…3> td=11>11|12`, and absent:
`failure Network`, `ErrorLadder: row=`, `state Buffering` with `REBUFFER`, `Reconnecting`/`Reconnected`, a second
`zap … ok`. The probe's `pl … why=` lines around the mark show the reloads continuing on the same `id` (the logical URL).

---

## 9. Review reconciliation

| Finding | Disposition |
|---|---|
| **B1** mechanism chosen before the facts; stall model contradicted | **Fixed**: the instrumentation build ran (§1.2); §0/§3/§4 rewritten from it. U1 (shape a), U2 (established connections survive: segments 200 until the failure), U4 (same sequence, same names), U5 (td 11–12, 6 segments), U7 (the buffer drained: `BUFFERING buf=97`) answered for one hand-over. |
| **M1** master body served to a variant loader | **Not applicable now**: a multivariant start turns the router OFF (§6.1); renewal serves O's body only to the route O itself redirected to, and only if it is a media playlist (`declined:multivariant` otherwise). Test `aMultivariantStartStaysOutEntirely`. |
| **M2** permanent URLs fall out of an LRU; R1 → R2 mapping only | **Fixed**: L is a permanent logical name; `Route.current` always holds the latest URL (no hop-by-hop chain, no LRU). Test: three successive renewals in `theBudget…`; the tracker test keeps L as primary. |
| **M3** edge identity by authority; same-authority branch gives up in the race | **Not applicable now**: the edge does not die and is not switched; identity is the full URL (route), and "same host" is now the *accept* case. The IP is logged (probe). |
| **M4** segment re-basing is guesswork; continuity outcomes mis-stated | **Fixed**: no segment re-basing; segments come from the fresh body; the continuity rule (§6.4, plus `names`) detects misalignment and falls back with a typed, immediate signal; R4–R6 restated (§7). The segment-retry part of the brief is blocked by Media3 (M12, §6.7). |
| **M5** eligibility misses reused-connection read timeouts; inconsistent budgets | **Fixed**: any open-phase failure before headers is a trigger (incl. read timeout before headers); the measured failure is a 302 detected in one round trip, so the budget is ≈ 0.2 s + one O request (≈ 1 s) against ≤ 33 s of buffer (§4). No edge timeout tag needed (v1's 2.5 s tag dropped). |
| **M6** O's exception could leak | **Fixed**: O's failures never leave the data source; pinned failures rebuilt against Media3's `DataSpec` (§6.9). Test `assertLeaksNothing` walks message, cause chain, `DataSpec`, headers, body. |
| Minor 1 2.5 s edge connect timeout | **Not applicable**: no edge timeout tag (expiry is detected by the unfollowed 3xx). |
| Minor 2 segment 404 costs an O hit | **Fixed**: segments never trigger a renewal; 404 sends no signal. |
| Minor 3 budget disables for the session | **Fixed**: 10-minute time box, per playlist. |
| Minor 4 offline | **Fixed**: `declined:offline`, today's path. |
| Minor 5 `Range` on the re-entry GET | **Fixed**: O is requested with position 0 / unset length; pinned reloads force the same. Asserted. |
| Minor 6 cancellation | **Fixed**: interruptible single-flight wait, router terminated by `end()`, O call cancelled. Tests. |
| Minor 7 drop stage 2 | **Done**: stage 2 and stage 3 removed. |
| Minor 8 §2.2 M9 target offset | **Not applicable**: the buffer arithmetic now uses the measured window; the correction stands (PDT/EXT-X-START also count). |
| Minor 9 tests (alignment classes, Robolectric tracker test, fake panel modes) | **Fixed**: one test per rule, the real-tracker test, fake panel `continue`/`restart` and expiry modes. "Same MSN different PTS" is not testable from playlists (R6). |
| Minor 10 acceptance run | **Partly**: §8.3 names the lines; the run itself is the lead's (≥ 2 marks; an overnight summary line is deferred). |
| Review "not a no-op for every redirecting provider" | **Addressed**: with renewal, a redirecting provider that does not expire tokens sees only unfollowed pinned reloads that answer 200 (no change), or the loop guard on its first reload. The 403-on-reload case now renews before the ladder's L7. |
| Deferred | ~~a `ReResolve(0)` row for the fallback~~ (done, §10.5: L19p); exact-sequence segment remap (needs evidence that segments expire); an overnight summary line. |

---

## 10. Code review changes (2026-11-26)

Review: `docs/review/2026-11-26-playback-fixes-code-review.md` (findings A1–A9, the merge notes). Code in
`HlsPlaylistRenewal.kt` (router + `RenewingHlsDataSource`), `HlsPlaylistScanner.kt`, `ErrorLadderImpl.kt`,
`PlaybackControllerImpl.kt`, `StatsFormatter.kt`. Where this section and §6 disagree, this section wins.

### 10.1 Which failures may end in the typed fallback (A1, A2, A5, A9)

| Pinned reload ended with | Renewal | If the renewal does not succeed |
|---|---|---|
| a failure **before the response headers** (connect, DNS, TLS, timeout, reset): `trigger=io:*` | at most one attempt per route per 30 s (`IO_COOLDOWN_MS`); counted in the budget but never disables it (`tryTakeSoft`); no online re-check | the **original error**, translated as before: Media3's own retries run exactly as on `main`. No fallback stored, no signal. |
| a **3xx** (the measured expiry) | as before | the same reload is **re-sent once with redirects followed** (today's request, at the route's current URL). A media playlist that passes the continuity rule against the last good one is served under Media3's URI, rebased (`PlaylistRebase`) against the URL it really came from. Same host as the logical URL: that URL becomes the current one (`followed … adopted`). Another host: the body is served rebased (absolute references on the other host) and the route **follows redirects from now on**, today's path (`followed … other_host`). Only when the followed request fails too (`follow_failed:<why>`) is the renewal's outcome the typed fallback. |
| a 3xx, but the renewal was declined **offline** (after 3 × 500 ms re-checks, A9), **busy** (a waiter that timed out, A5) or by the I/O cool-down | not attempted | exactly today's request: followed, its errors translated; a connect failure is a transient error Media3 retries (not the unfollowed 302, which the policy treats as fatal). |
| 401, 403, 404, 410, or a 2xx that is not a media playlist | as before | the typed fallback (unchanged). |

So only the unambiguous expiry signals can end in `HlsRenewalException` + `HLS_RENEWAL_FALLBACK`. A fallback is stored
on the route only when it is signalled (`HlsPlaylistRenewal.fallBack`); a successful renewal clears any stored fallback
and the follow mode.

### 10.2 "Off" keeps the current URL (A4)

`turnOff` (`declined:loop`, `declined:too_large`) now means "never renew again in this session"; the routes stay. The
budget's time-boxed disable means the same. A route that was renewed keeps being requested at its **current** URL with
redirects followed (`Plan.Pinned(follow = true)`, data source mode FOLLOW, errors translated); a route that was never
renewed is planned `None` (Media3's own request, exactly today's). An oversized body on a renewed route, or a 200 whose
references cannot be rebased byte-safely, is re-requested the same way instead of being served unrebased. In FOLLOW
mode `getUri()` is the post-redirect URL, as on `main` (Media3 then resolves segments against it); its errors are
still rebuilt against Media3's own `DataSpec` (§6.9), and nothing in the app logs load URIs.

### 10.3 "Redirected at start" from parsed URLs (A3)

O's first load and later loads are compared with `HttpUrl` equality (`HlsPlaylistRenewal.sameUrl`), and Media3's
request for O is recognised the same way, so an M3U entry or Xtream base written as `HTTP://Host:80/…` (OkHttp's normal
form differs from the text) is `off:not_redirected`, a strict no-op. Test `anOriginWrittenInAnotherFormIsNotARedirect`.

### 10.4 Continuity rule (A6, A7)

- **Target duration:** a change up to **max(2 s, 25 %)** of the last value is the same stream. Why: the tag is the longest
  segment rounded up, and the capture shows the same segmenter and the same files reporting 11 then 12 (§1.2); with
  segments cut at ≈ 11–12 s the rounding can move by 2 (11 ↔ 13), which 25 % of 11 s (2.75 s) allows, and the ±2 s floor
  keeps short targets (2 s ↔ 4 s) from tripping on the same rounding. A different stream is still caught by
  `msn_back`/`msn_ahead`/`names`. 11 → 14 and 11 → 8 are `misaligned:td`.
- **Same segmenter:** for every MSN present on both sides the rule now also requires the same `#EXTINF` duration (± 500 ms,
  `misaligned:dur`) and, when **both** entries carry `#EXT-X-PROGRAM-DATE-TIME`, the same PDT (± 1 s, `misaligned:pdt`).
  The file name stays the last path segment (the measured token sits in the directory, so a whole-path hash would reject
  the measured hand-over).
- **Residual false-accept risk (R6, narrowed):** a different rendition or stream on the **same host** whose segmenter
  uses the same file names, the same sequence numbers and the same durations for the overlapping segments, and either
  carries no PDT or the same PDTs, is still accepted: in practice another rendition of the same channel cut by the same
  packager (aligned segments). Its picture would be the same programme at another quality, or, for a different channel
  sharing one packager clock, the wrong channel until the next tune; the frozen-video/stall detectors do not catch the
  latter. No such provider is known; the host must also be the same (`declined:host` otherwise).

### 10.5 The immediate ladder row (merge note)

`FailureSignal.PlaylistExpired` (`:core`), sent by the controller for `HLS_RENEWAL_FALLBACK`. Row **L19p** (before L19):
`ReResolve(0)` while attempts are left (limiter fallback `RetrySame(0)`), `transientRetries + 1`, exempt from the switch
window when attempts are unlimited; exhausted → L20 `STREAM_INTERRUPTED`. The account gate applies as for a network
failure. Transient for the controller (quiet rule, reconnect pill). Why: L19's first delay is 1 s ± 20 %, which put
the restart's first media around the 3 s quiet line at random; with no delay the restart takes the resolve plus the HLS
start every time. Tests: `ladder.csv` (L19p rows, `G-limiter`, L20), `ErrorLadderRulesTest.anExpiredPlaylistReResolvesAtOnceAndCountsLikeL19`.

### 10.6 Stats and the quiet-reconnect cap (A8)

- Renewals are counted by the controller from a `PlaybackEventType.DECISION` / `HLS_PLAYLIST_RENEWED` event per renewal,
  **per tune** (the cycle, across its sessions) and **per visit** (the cycles of one channel since the last user tune).
  Ladder restarts after first media are counted the same way, with the quiet ones (a series that ended without any
  notice). Overlay: HTTP `n renewals (visit m)`; a new **Restarts** row `n (q quiet) · visit m (r quiet)` (label
  `player_stat_restarts`). Values may wrap to a second line instead of being cut (`StatsLineFitTest`, Default and Largest).
- **Cap:** the quiet rule hides at most `QUIET_RECONNECT_MAX = 2` reconnect series per visit within
  `QUIET_RECONNECT_WINDOW_MS = 10 min`; the next one shows "Reconnecting" at once (and "Reconnected" after it). A new
  visit starts with no history. The deferred notice now fires for any waiting state at 3 s (not only
  `Buffering(RECONNECTING)`), except an error card, a stop or playback again.

### 10.7 Release gate note

To validate HLS renewal on a playlist that the Auto TS retry would move to TS, **force Stream format to HLS** (recorded in
`docs/00-master-plan.md` §6). The owner's Auto playlist goes to TS once the retry confirms, and renewal never runs on TS.

### 10.8 Fake panel

New `token_expire_mode: redirect_moved` (A2: the expired playlist redirects to a working token on another port, and the
provider URL hands out that port from then on) and `edge_blip_every` / `edge_blip_s` (A1: a short blip on the token
paths, connections closed before the status line). Commands in `tools/fakepanel/README.md`.

---

## 11. Not verified / open

- **The owner's provider** with renewal: one captured hand-over only. The next captured marks confirm it when each shows
  `renewed … trigger=redirect:302 … msn=a>b` with b − a ≤ 3 and no ladder line. Any `misaligned:`/`declined:host` line
  is the other shape, and its label says which rule.
- Whether every channel of this provider has the same shape (absolute paths, no multivariant): the probe's `pl … ref=`
  and `shape=` lines on other channels.
- Whether the provider counts the renewal request as a new connection on a 1-connection account (it replaces today's
  re-resolve request, so the count should not change; watch for `failed:status:458|403` lines).
- Q2 (owner): keep the quiet reconnect (`QUIET_FAST_RECONNECT`)? Default true until the owner answers.
- Q4 (owner): until this ships, setting the playlist output to TS avoids the toast (TS reconnects through O).
