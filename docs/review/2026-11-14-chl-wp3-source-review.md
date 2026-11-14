# CHL WP-3 review: `HockeyTechScoreSource`, endpoints, DI, repository rows 35–37/39, tools

Commit `0da69ee` (against `4a3c4f8`). Cross-package checks on `chl-int` (`73b1a95`). Reviewer: independent, Opus.
Spec: `docs/design/m6-chl-leagues.md` §2, §7, §8; `docs/decisions/2026-11-13-chl-leagues.md`; `CLAUDE.md`.

## Verdict: **fixes needed** (no blockers)

The security and privacy side is sound: the keys live in one file, are registered before any use, never reach a log
line, the export or a stack trace through this code, and a debug override can never send them anywhere. The transport
caps bodies while reading, has a call timeout and leaks no calls. Four should-fix items: a 429's `Retry-After` is now
honoured up to an hour, the CHL meter's MB/h leaves out the headers (about 90 % of the real cost on HTTP/1.1), the S8
gate passes a derivation that hides everything and does not exercise the Kotlin code, and the log-capture test asserts
on lines that have already been redacted.

---

## Blockers

None.

## Should-fix

**S1. A 429 (or 5xx) `Retry-After` is honoured up to 3600 s, so it can freeze a CHL follow and day list for an hour.**
`HockeyTechScoreSource.kt:680-681` (`retryAfterS`, clamp 0..3600) is used for every status (`:477`, `:523`), not only for
REFUSED. The machine (`chl-int` `ScoreBugMachine.kt:234-238`, `SportsTiming.backoffAt` → `maxOf(step, retryAfter)`) and
`DayPollPolicy` both use it as a floor and do not clamp it. The NHL/NFL helper clamps every `Retry-After` to 300 s
(`NhlScoreSource.kt:244-246`).
*Scenario:* Firebase or a CDN in front of `lscluster` answers `429 Retry-After: 1800`. The bug dims and hides, and the
tab stays failing for 30 minutes. With the NHL rules the wait would be 5 minutes.
*Fix:* clamp to `BACKOFF_MAX_MS / 1000` for every kind except REFUSED. For example, `Raw.Status` keeps the raw value
and `statusKind` decides: REFUSED → `min(raw, 3600)`; otherwise → `min(raw, 300)`. Add a test that a 429 with
`Retry-After: 900` carries 300. The lead ruling only covers REFUSED.

**S2. The CHL MB/h figure leaves out the headers, which are most of the CHL's traffic. `EST_WIRE_BYTES_CHL = 2000`
matches neither the measured bodies nor the real wire.**
`WireMeter.kt:23-25` counts `responseBodyEnd` bytes only, which is what NHL and NFL count. `PollMeter.mbPerHour` says
"(headers not counted)", and the NHL/NFL constants are gzip body bytes per poll. That is fine for one HTTP/2 request
with HPACK. The CHL sends six or seven HTTP/1.1 requests per poll. I measured on 2026-11-14 with a generic client:
- each Firebase response carries about 370 B of headers, including a 304, which is `Content-Length: 0` with the full
  header set;
- each request is about 250–300 B (Host, UA, Accept, Accept-Encoding, X-Firebase-ETag, If-None-Match, Connection);
- Firebase never gzips: `goals` answered 7.8 KB raw with `Accept-Encoding: gzip`.

So a typical in-play poll is about 6 × 650 B of headers plus about 0.1–0.5 KB of changed bodies, roughly 4 KB. At 5 s
that is about 3 MB/h. The meter will measure a body median of about 0.1–0.4 KB, so it will show about 0.1–0.3 MB/h once
it has samples, ten times too low, after showing 1.4 MB/h from the estimate.
*Fix (recommended):* for the CHL only, also count header bytes in the `WireMeter` listener:
- `requestHeadersEnd(call, request)` → `request.headers.byteCount()` plus the request line;
- `responseHeadersEnd(call, response)` → `response.headers.byteCount()` plus the status line.

Then set `EST_WIRE_BYTES_CHL = 4_000`. If the lead keeps bodies-only for every league, set it to about 400 so the
estimate and the measurement agree, and say in the MB/h text that headers are not counted. Either way, correct the
"one HTTP/2 connection" comments in `SportsConstants.kt:92` and design §2.5. The CHL is HTTP/1.1 only (confirmed: curl
`--http2` negotiates 1.1).

**S3. The S8 live gate (`pp_replay.py live`) passes a derivation that hides everything, and it never runs the shipped
Kotlin code.**
`tools/chl/pp_replay.py:367-431`.
- (a) `ok` (`:427`) tests only wrong-bar share and the longest wrong stretch. Hidden and missing seconds are printed
  but not gated, so "always Unknown" scores PASS.
- (b) `live_state` re-implements the app in Python. It applies H3, H6, H8 and H9 only, and H3 without the
  first-link-only rule. It leaves out:
  - H1 and H2, including the S13 and WP-1 departures;
  - H4 and H5;
  - H7, including the `ppAttempts` unknown case.

  The gate therefore measures the Python model against itself on final data, not what `ChlPowerPlay` /
  `HockeyTechMapper.followed` will show. A Kotlin regression, or a Kotlin/Python divergence, passes.
- (c) It compares side and kind only, not the seconds shown.

*Fix:*
- Drive the gate from the Kotlin mapper: a `:core` JVM test or a small `main` reads `polls.jsonl`, rebuilds each poll,
  and calls `HockeyTechMapper.followed(league, facts, ChlNodes(…, penaltiesFailed, goalsFailed))` with the board facts
  from `board-start.json`/`board-end.json`.
- Compare its `powerPlay`/`powerPlayUnknown` per poll with the final-data model. Either Python `Sim` on the last poll,
  or the Kotlin derivation fed the final nodes at each `now`.
- Gate on wrong ≤ 5 % and stretch ≤ 30 s **and** on coverage, for example (hidden + missing) ≤ 25 % of the final
  model's PP seconds (threshold for the lead), plus shown seconds within ±5 s of the model.
- Keep the Python path as a cross-check.

**S4. The log-capture test cannot catch a logged key: it inspects lines after `Redact` has masked them.**
`HockeyTechScoreSourceTest.kt:717-748`. The test sink receives `Redact.text(msg)` (`BoxLog.emit`, `BoxLog.kt:23`), and
the keys are registered, so a regression that logs a URL or the key would still produce `***` and pass the key
assertions. The `"?"`, `"://"` and host checks still catch URLs, but not a bare key, an echoed body excerpt without
braces, or an exception message.
*Fix:* also assert that no captured line contains `***`. Nothing in this source should ever need redaction, so any
mask is a leak caught by the second defence. Alternatively, wrap `BoxLog.sink` capture around a hook that records the
pre-redaction text. Two smaller gaps:
- the test does not exercise the overlay, teams or seasons failure paths, the over-cap path (`"over cap"` cause) or a
  timeout;
- `logThrowables.isEmpty()` is good. Keep it.

## Low

**L1. 304s are only honoured when `X-Firebase-ETag` is sent; the fake panel requires both, the real host does not.**
The real Firebase answers 304 to `If-None-Match` alone (verified). The app always sends both, so this is harmless.
`sports_chl.py` `_firebase` only honours `If-None-Match` with `wants_tag`. Note it in the fake panel docstring, or
accept it, so a future test does not encode the wrong rule.

**L2. Expired board bodies are cached as parsed trees and never reused.**
`HockeyTechScoreSource.kt:117, 223`: `boards` keeps up to 8 `JsonObject`s per league, but an entry older than 240 s is
never read. `assemble` re-fetches. A 39 KB board is a few hundred KB as a `JsonElement` tree. Three leagues × 8 dates
is up to several MB of dead weight on a Stick.
*Fix:* drop the entry when it expires, or cap `BOARDS_MAX` at 3 (yesterday, today, tomorrow).

**L3. `seasons` is loaded once per process and never refreshed.**
`HockeyTechScoreSource.kt:249-259`. On a TV the process can live for weeks across the regular-season → playoffs
switch. A board whose `season_id` is not in `seasons` then falls back to the board's own `playoff` flag, if present,
or regular-season OT rules.
*Fix:* reload `seasons` when a mapped board names a season id missing from the map, at most once per board.

**L4. Facts mapped while `teamsbyseason` failed are kept for the whole follow.**
`assemble` stores `Known` facts with code-only names when teams failed. A live follow never re-reads the board (except
for the OT length), so the followed lines keep codes for the whole game, even after a later day refresh. A later
`assemble` does overwrite `known`, so only a follow with the tab closed is affected.
*Fix:* none needed beyond noting it, or re-assemble once when `k.facts` was built without teams.

**L5. The `logo` log kind toggles between ok and rejected per call.**
`HockeyTechScoreSource.kt:197-203, 636-641`. `logState` is per kind, not per URL. A board with one rejected and one
valid logo produces a log line on every alternating call, and the UI calls `logoUrl` per card per composition, which
spams the ring buffer. Also, `logoUrl` will be called from the UI thread: in a debug build its first call resolves
`endpoints` lazily, which reads the override file on main.
*Fix:* log a rejection once per process (or per URL hash); never log "ok" for logos. Resolve `endpoints` eagerly on
the actor's first use, or keep the file read and document it.

**L6. Before the listed start, a followed game is read in board mode only.**
`HockeyTechScoreSource.kt:176`. Design §2.4 says board mode while the start is ahead; §2.5 says live mode from
`start − PRE_WAKE_MS`. The code follows §2.4. A game that starts early would show "pre-game" for up to 240 s (board
cache) plus the 40 s cadence. CHL boards show the actual `start_time` after `schedule_time` (19:00 → 19:12), so early
starts are rare: low. A related case: a `time_tbd` game's start is the start of the day (mapper), so it is in live
mode all day. Every pre-poll is then six Firebase requests that answer `null`, followed by S5 to the board. That is
the machine's cadence × 7 requests while the game is followed. Low, since following a TBD game is rare.
*Fix:* use live mode once `wall() ≥ start − PRE_WAKE_MS` (the design's §2.5). S5 already falls back to the board when
Firebase answers `null`. Or ask the lead to amend §2.5.

**L7. When an optional node fails, the poll shows nothing for it instead of its last body.**
`HockeyTechScoreSource.kt:429-440`. The last confirmed body (`tags[n].body`) is in hand, at most one poll old.
*Scenario:* one dropped `shotssummary` request blanks SOG for a poll, then it comes back. That is a visible flicker at
5 s.
*Fix:* for `shotssummary` and `publishedclock`, fall back to `tags[n]?.body`. Keep H8 (`penaltiesFailed`/`goalsFailed`)
as is: the hide rule exists because a stale penalties body can be wrong.

**L8. `record_live.py --key` puts the key in `ps` output and in shell history.**
`tools/chl/record_live.py:93`.
*Fix:* drop the flag and use `HOCKEYTECH_KEY` only, or warn when `--key` is used.

The recorder otherwise never writes or prints a key:
- errors print the HTTP status or the exception class only;
- the board bodies are redacted before they are written;
- the Firebase recordings carry no key;
- the default `--out` path under `tools/chl/recordings/` holds no secret.

Two small notes on the recorder:
- an over-size body (> 1 MB) is silently dropped with status 200;
- it requests the seven nodes one after another, not in parallel, so its cross-node skew is larger than the app's
  (conservative for the gate).

**L9. A board answer's `receivedMono` is now, even when the body is up to 240 s old (implementer choice).**
`HockeyTechScoreSource.kt:282, 365`. The tab's "Updated" is therefore up to 4 minutes optimistic on a cached board.
This is acceptable: the day policy needs "this answer" to schedule from. Note it in §2.3.

**L10. A Firebase-only refusal leaves the user no reason on the bug.**
The bug dims and hides, and the board still works (no overlay), so nothing on screen says why scores stopped. This is
the design (§2.6). Against `CLAUDE.md` ("errors carry a reason and a next step"), consider a REFUSED line on the
followed game's card in the tab/sheet when the follow's last failure is REFUSED. Ruling below.

**L11. Seven parallel sockets per poll; a sequential plan would cost under 0.5 s.**
`HockeyTechScoreSource.kt:670-671` (dispatcher 8/host, pool 10 × 5 min).
- *Cost on a Stick:* the first poll pays six or seven TLS handshakes (about 4–5 KB each, plus CPU). After that the
  connections are reused.
- *Keep-alive:* I verified that Firebase keeps an idle connection for at least 65 s. See Notes for the 120 s cadence.
- *What it keeps:* the private dispatcher and pool still go through the `@ApiClient`'s interceptors (UA, settings
  timeouts), `BoxDns` and its listener. The app has no redacting or logging interceptor that this would bypass. The
  pool is process-lifetime and shared by the three sources; it never needs a shutdown.
- *Alternative:* sequential on one keep-alive connection is 6 × about 40–60 ms RTT, under 0.5 s per poll, which is
  acceptable against a 5 s interval and gives a seventh of the concurrent-connection footprint on HockeyTech's
  database.

*Suggestion:* `maxRequestsPerHost = 3` gives about 2 RTTs per poll. Not required.

## Notes (checked, fine)

**Keys.** The three keys exist only in `data/src/main/kotlin/app/thebox/data/sports/HockeyTechEndpoints.kt:21-23`.
Checked by `git grep` at `0da69ee` over the whole tree (sources, tests, fixtures, tools, docs, fake panel), by an
8-character-prefix grep, and by a working-tree grep of the WP-3 worktree and `chl-int` (only that file). The history
search (`git log -S`) returns only `0da69ee`. The recorded `gamesbydate` fixtures carry `"key":"KEY_REDACTED"`.

**Redact registration.**
- `HockeyTechKeys`'s `init` runs in its `<clinit>`, so no read of a key can precede the registration on any path. That
  covers `HockeyTechEndpoints.DEFAULT` (the `HockeyTechKeys::of` reference initialises on first call), the day list
  path, the follow path and DI order.
- The companion touch is belt and braces.
- The debug override path (`fromOverride`) sends `fakepanel-{client}` and never touches the real keys.
- *Edge:* `Redact`'s credential set is an LRU of 1 024 entries and the keys are registered first and never refreshed.
  More than 1 024 other registrations, about 170 accounts, would evict them. The query-string pattern still masks URLs.
  This is theoretical.

**Exception text.**
- `onFailure` and the catch blocks keep `e.javaClass.simpleName` only.
- `parse` drops the decoder's message.
- `toHttpUrl()` is only called on endpoint bases and digit ids (never on a keyed URL).
- No `Request`, `HttpUrl` or `Response` is stringified.
- Cancellation raises a plain `CancellationException`.
- The repository's catch-all logs a `Throwable` with its stack trace through `Redact.text`
  (`TheBoxApplication.kt:81`, `LogRingImpl`).
- The poll meter stores endpoint tags (`chl-live`), never URLs.
- `HockeyTechEndpoints.toString()` prints hosts only.
- `ExportRedactionTest` and `HockeyTechExportRedactionTest` would fail without the registration: the plain key in the
  first log line is masked only by it.

**What a request carries.** Every request carries:
- the UA `TheBox/1.0 (Android TV)` (`HttpModule.kt:28`): it names the app, not the user or the device;
- `Accept`, `Accept-Encoding`, and for nodes `X-Firebase-ETag`/`If-None-Match`;
- no cookies (no `CookieJar`) and no referrer.

All three production hosts are HTTPS, with HSTS on Firebase. A debug override may be http for the fake panel.

**Debug override in release.** `resolve` returns `DEFAULT` unless `:data`'s `BuildConfig.DEBUG`. The only input is a
private file under `filesDir` (written by `run-as`). No intent extra, pref or exported component reaches it.

**Logos.** Only `{logoBase}{client}/logos/{digits}(_{digits})?.png` passes (`:197-203`; tests cover `..`, `?`, another
league, subfolders and jpg). The feed cannot point the app at another host. The size of the image is the loader's
concern; Coil downsamples to the target size, and the host is fixed.

**Bodies.**
- The caps are enforced while reading: `source.request(cap + 1)` before `readUtf8`, after a `Content-Length` pre-check
  (`:591-594`).
- Every request uses them, including the date queries (128 KB) and `lscluster` (512 KB).
- With transparent gunzip the cap applies to the decompressed bytes, so a gzip bomb stops at cap + 1.
- The 10 s call timeout covers the body read in `enqueue` mode.
- kotlinx-serialization 1.11's tree reader switches to `DeepRecursiveFunction` past depth 200, so no stack overflow.

**304, ETag and the per-node cache.**
- A 200 with an ETag replaces the entry, and a 304 reuses it.
- A failed request keeps the entry, and the next 304 confirms it, so the body is correct, not stale. Tested at
  `:364-381`.
- `null` nodes get `null_etag` (verified on the real host) and cache as `Tagged(etag, null)`.
- The date queries never send ETag headers. I verified the real 400 "Mixing querying parameters and ETag requests".
- The cache resets on a follow change and is bounded at seven entries; it is not reset at game end, which is harmless.
- `known` (256) and `teams` (8 seasons) are bounded.

**Partial failure, consistency and cancellation.**
- The required/optional split matches §2.4. A refusal wins over other failures.
- Snapshots fetched in parallel can disagree for one poll (a new goal in `goalssummary` before `goals`); H6 hides the
  bar for that poll, as designed.
- `receivedMono`/`serverDateMs` come from `runningclock`, which is right for the clock.
- Each call's `invokeOnCancellation` cancels it, `response.use` always closes, and `coroutineScope` cancels the
  siblings. No call can leak. A follow change waits for the in-flight poll, at most 10 s, as for the NHL.

**Implementer choices, as I judge them.**
- **S5 by mapped phase:** fine. The mapper has no POSTPONED, and a forfeit (status 5) maps FINAL.
- **Forfeit → NeedsBoard:** fine.
- **Failed overlay:** `Failed` only after an overlaid board of that date, else the plain board; refused → plain board
  for 300 s. Fine.
- **`receivedMono` = now:** L9.
- **401/403 as REFUSED on `lscluster`:** acceptable. A CDN/WAF 403 is effectively a block. The "needs an app update"
  text is then slightly off, but a 300 s wait is right.
- **REFUSED after a restart:** the follow fails REFUSED because the facts are missing. This is acceptable for v1: a
  refused key needs an app update anyway. Facts from Firebase alone are not enough, because the period lengths, the
  playoff flag, the start time and the team names come from `lscluster`. The machine would still dim and hide, and the
  user sees the REFUSED text in the tab.
- **`followEndpoint` null cases** (board mode, a lookup failure, NotFound): correct. They are never samples.
- **Retry-After 3600:** fine for REFUSED; see S1 for the rest.
- **Row 35** (no fallback; HTTP_OTHER; logged once): fine.
- **Row 39** (429 slows every completion-timed league): fine.

**Repository; NHL and NFL unchanged** (traced).
- `sourceOf` returns the bound source for NHL and NFL as before, and the single-source test constructor maps
  `source.league`.
- `measure` runs whenever a source exists.
- `pushInterval` adds only the CHL field.
- `Retry` and `dayNextAt` are gated only by `dayRefused`, which only a `REFUSED` answer fills, and only CHL sources
  return `REFUSED`; `Nhl`/`Espn` never do.
- 429 → slowed: the NFL is `COMPLETION` as before, and the NHL is `BUCKET`, so it is unchanged.

Day switching during a REFUSED back-off shows `Failed(REFUSED)`, not Loading, and holds the time. A refusal is per
league (tested). The refused text is §2.6's "stopped letting this app in… need an app update; NHL and NFL scores still
work": it gives a reason and a next step.

**Cross-package (`chl-int`).**
- `PollMeter.effectiveS` (`:122-126`) already applies the 429 floor to every `COMPLETION` league, so the implementer's
  caveat ("a slowed CHL meter does not yet raise Auto") is resolved by the WP-2 merge.
- The machine's REFUSED back-off (`ScoreBugMachine.kt:234-236`) is `receivedMono + max(300 s, Retry-After)`.
- The source adds no back-off of its own on the follow, only the overlay's 300 s skip, so nothing compounds.
- `powerPlayUnknown` is set when penalties or goals fail (H8; tested `:364-401`).

**Firebase keep-alive at the 120 s cadence.** See the appendix at the end of this file.

## Rulings requested from the lead

1. **S1:** clamp `Retry-After` to 300 s for every kind but REFUSED. This extends the existing NHL/NFL rule.
2. **S2:** CHL MB/h. Either count header bytes for the CHL with `EST_WIRE_BYTES_CHL = 4_000` (recommended), or keep
   bodies-only everywhere with `EST_WIRE_BYTES_CHL ≈ 400` and the "headers not counted" caveat shown. Also correct
   design §2.5, which assumes HTTP/2.
3. **S3:** the S8 gate drives the Kotlin mapper and adds a coverage threshold (proposed: hidden + missing ≤ 25 % of
   the final model's PP seconds). The current gate is not meaningful as a release gate.
4. **L6:** live mode from `start − PRE_WAKE_MS` (§2.5), or amend §2.5 to say board mode until the listed start.
5. **L10:** whether a Firebase-only REFUSED should surface its reason on the followed game's card.
6. **L11:** whether to keep seven parallel connections or cap Firebase at about 3 per host.

## Verified by running vs read only

**Ran:**
- `./gradlew :data:testDebugUnitTest --tests '*HockeyTech*' --tests '*ScoreRepository*' --tests '*SportsModule*'` in
  the WP-3 worktree: all green.

  | Suite | Tests |
  |---|---|
  | `HockeyTechScoreSourceTest` | 37 |
  | `HockeyTechExportRedactionTest` | 1 |
  | `ScoreRepositoryChlTest` | 8 |
  | `ScoreRepositoryImplTest` | 31 |
  | `ScoreRepositoryIntervalTest` | 14 |
  | `ScoreRepositoryLeagueTest` | 10 |
  | `SportsModuleChlTest` | 1 |

- `python3 -I -m unittest discover -s tools/fakepanel -p 'test_sports_chl.py'`: 21 tests OK.
- `python3 -I tools/chl/pp_replay.py all` on `chl-int`: TOTAL PP 56/57 · SH 5/5 · EV 161/161 · ends 124/129, matching
  the mapper fix pass.
- Fake panel `--sports --sports-chl --chl-timeline 200`, then `record_live.py` against it (38 polls), then
  `pp_replay.py live`: PASS (wrong 0 s, hidden 15 s, missing 0 s). The end-to-end tool path works.
- Key grep over the tree, history and both worktrees (above).
- A few real requests to the public Firebase host, with a generic UA and no key:
  - HTTP/1.1 only, even with `--http2`;
  - the ETag/304 semantics, including a 304 to `If-None-Match` without `X-Firebase-ETag`;
  - `null_etag`;
  - the 400 on a date query with `X-Firebase-ETag`;
  - no gzip;
  - header sizes;
  - keep-alive reuse after 5, 20 and 65 s idle.

  No `lscluster` request was made.

**Read only:** the rest of `HockeyTechScoreSource`, `HockeyTechEndpoints`, `SportsModule`, the repository diff,
`Redact`/`BoxLog`/sinks, `HttpModule`, `WireMeter`, `chl-int`'s `PollMeter`/`ScoreBugMachine`/`HockeyTechMapper`
classification, the fake panel module, `record_live.py`, and `pp_replay.py live`.

## Appendix: Firebase keep-alive (measured 2026-11-14)

An idle connection to `leaguestat-b9523.firebaseio.com` is reused after 5, 20 and 65 s. The server closes it before
80 s (`RemoteDisconnected` at 80 and 125 s), consistent with nginx's default `keepalive_timeout 75s`.

**What it means:**
- At the in-play intervals (5–30 s) and in breaks (≥ 20 s), the seven pooled connections are reused. The 5-minute
  pool idle time is harmless: OkHttp health-checks and retries a stale pooled socket.
- At the 120 s not-in-FULL cadence, every poll opens six or seven fresh TLS connections. That is about 4–5 KB and one
  handshake each, so about 30 KB a poll and about 0.9 MB/h.

This strengthens L11: at slow cadences, fewer connections per host (sequential or about 3) save most of that cost. The
meter does not see it either way (S2).

## Fix pass (2026-11-14, WP-3 implementer, on `chl` `262be85`)

| Item | Disposition |
|---|---|
| S1 | Fixed. `Raw.Status` keeps the raw `Retry-After`; `clampRetryAfter` clamps it to 300 s for every kind but REFUSED, which keeps up to 3 600 s (the machine and the repository take `max(300 s, Retry-After)`). Tests: a Firebase 429 with `Retry-After: 1800` and an `lscluster` 503 with 1800 carry 300; a 401 with 900 carries 900, one with 9000 carries 3600. |
| S2 | Fixed (lead ruling: true wire cost). Every CHL answer's `wireBytes` = body bytes (WireMeter) + `headerBytes` of the exchange: the network request's request line and headers (after the interceptors and OkHttp's bridge headers), the status line, the network response's headers and the blank lines; 304s count their headers. NHL / NFL unchanged. `EST_WIRE_BYTES_CHL = 4_000` with a corrected comment (HTTP/1.1, uncompressed). Design §2.1, §2.4, §2.5 and Appendix A corrected. Measured on the real host as the source reports it (`HockeyTechWireProbe`, opt-in, OHL 29191): first poll 26 010 B (six bodies), an all-304 poll 3 878 B (about 645 B per exchange). `ChlContractsTest`'s constant assertion updated to 4 000 (the only line touched outside WP-3's files, besides the constant). |
| S3 | Fixed. `ChlLiveGate` + `ChlLiveGateTest` in `:core`'s tests: rebuilds each poll of a `record_live.py` recording, maps it with `HockeyTechMapper.followed` (and so `ChlPowerPlay`), and compares it with `ChlPowerPlay.Model` on the final data (without `TimeOn`, as the §5.8 duration audit: with it a recorded release would be known before any live poll could know it). Gates per league: wrong ≤ 5 %, longest wrong stretch ≤ 30 s, hidden + missing ≤ 25 %, shown seconds within ±5 s ≥ 95 %. `:core` because the parsers and the model are `internal` there and its tests are plain JVM. The fake panel's recordings (one per league, `core/src/test/resources/sports/chl/live-gate/`) pass; a doctored "always unknown" recording fails on coverage and a wrong-side one fails on wrong bars. Real recordings: `CHL_LIVE_RECORDINGS=<folder>`. `pp_replay.py live` is labelled a quick check. Procedure: `tools/chl/README.md`. |
| S4 | Fixed. The log test asserts no line contains `***`, and now covers the overlay (503 and 401), teams (refused), seasons (bad body), over-cap and timeout paths, with the key echoed in each body. `"http"` became `"http:"` / `"https:"` (`HTTP_5XX` is a state name). |
| L1 | Fixed in the fake panel: a matching `If-None-Match` is a 304 with or without `X-Firebase-ETag`; the `ETag` header still needs it. Docstring, README and a test. |
| L2 | Fixed: expired board bodies are dropped on every assembly; at most 3 dates kept. |
| L3 | Fixed: `seasons` re-read at most once per 24 h (a failed re-read keeps the old map). Test. |
| L4 | Fixed: facts built without teams make the follow re-ask for the board (and so the teams) at most once per 240 s. Test. |
| L5 | Fixed: no "ok" line for logos; a rejected URL is logged once per process (bounded set). `logoUrl` never resolves the endpoints (no file read on the main thread); before the first request it answers no logo. Tests. |
| L6 | Fixed (§2.5): board mode until `start − PRE_WAKE_MS`; a "Time TBD" game stays in board mode until the board reports it started. Tests. |
| L7 | Fixed: a failed `publishedclock` / `shotssummary` request passes its last confirmed body; penalties / goals keep H8. Test. |
| L8 | Fixed: `--key` removed; the key comes from `HOCKEYTECH_KEY` or `--key-file`. |
| L9 | Noted in design §2.3. |
| L10 | **Not done: needs a contract / UI change outside WP-3.** Nothing a source or the repository's rows 35–37/39 can set reaches the bug or the card with a reason: `ScoreBugView` and `DayState.Board` carry no failure kind, and turning the day list into `Failed(REFUSED)` would hide a working board (§2.6). Proposal for the lead: a `ScoreRepository.followFailure: StateFlow<FailKind?>` (or `FollowedGame`-adjacent field) filled from the machine's last `Failed`, read by the Sports tab card / sheet row of the followed game to show `sports_failed_refused`. |
| L11 | Fixed: the private dispatcher and pool are gone (the shared `@ApiClient`'s are used, with its interceptors); a followed poll runs at most 3 requests at once, `runningclock` and `goalssummary` first. Test (six 400 ms answers take 0.8–1.7 s; the clock and the score are among the first three). |
