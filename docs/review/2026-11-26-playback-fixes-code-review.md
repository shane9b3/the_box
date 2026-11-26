# Code review: `hls-fix` (HLS playlist renewal + quiet reconnect) and `ts-retry` (Auto TS retry + tunneling per visit), 2026-11-26

Reviewer: Opus, read-only. Branches against `main`: `hls-fix` at de16abc (incl. d374e4e probe), `ts-retry` at 0d15f3a.
Media3 claims checked against the 1.11.1 sources (`DefaultHlsPlaylistTracker`, `HlsMediaPlaylist.isNewerThan`,
`OkHttpDataSource`). Line numbers are in each branch's own tree.

What I ran:
- `git merge-tree --write-tree hls-fix ts-retry`: **clean** (no textual conflicts).
- I exported the merged tree to the scratchpad and ran `:player:testDebugUnitTest` (AutoTsRetryControllerTest 10,
  LadderExecutionTest 29, TunnelingVisitTest 6, HlsPlaylistRenewalTest 22, HlsRenewalTrackerTest 2,
  HlsRenewalWiringTest 1) plus `:core:test --tests *StatsFormatterTest*`. It compiles and every test passes.
- A javac check of `okhttp3.HttpUrl.get(s).toString()` (finding A3).

## Verdicts

- **A `hls-fix`: changes requested.** It handles the measured hand-over well, and it is carefully redacted, bounded and
  cancellable. Three paths are worse than today for some provider shapes (A1–A3). Each needs a small fix, and none needs a
  redesign.
- **B `ts-retry`: approve with minor changes.** I found no blocker or major issue. Every failure path ends in a playing
  stream or today's error card. The stored value is safe to read in both directions (old app reading new, new reading old).

Counts: A has 0 Blockers, 3 Majors and 6 Minors. B has 0 Blockers, 0 Majors and 6 Minors.

---

## A. `hls-fix`

### Blocker

None.

### Major

**A1. A short network blip on a playlist reload now restarts playback. Before, Media3 retried it without the viewer noticing.**
`player/src/main/kotlin/app/thebox/player/datasource/HlsPlaylistRenewal.kt:838-842` (`triggerOf` returns `io:*` for any
open-phase failure before the headers), `:146-147` (`Failed` counts as a fallback), `:319`, and `:697`. Confirmed by reading.
- Today a pinned reload that fails before the headers (connect, timeout, DNS) is a transient `HLS_MANIFEST` error.
  `LoadRetryRules` retries it 500/1000/2000 ms, up to 3 times (F8), and playback carries on from the buffer.
- With this branch, the same failure triggers a renewal at once. Renewal fetches O, and O's redirect leads back to the
  same edge that just failed. If that fetch also fails, the outcome is `failed:connect`/`failed:timeout`, which counts
  as a fallback. The result:
  - Media3 gets a fatal `HlsRenewalFailed` (the policy does not retry it);
  - `route.fallback` is set for the rest of the session;
  - the controller gets `HLS_RENEWAL_FALLBACK`, which goes to L19, a re-resolve that empties a buffer of about 60 s.
- Scenario: the edge or the ISP drops for about 1–3 s during a reload (the capture shows 190–350 ms `dns`/`tcp`
  outliers on this edge). Today nothing is visible. With this branch the viewer sees a restart, and the restart may fail
  again because the edge is still down.
- The measured expiry does not need the I/O trigger at all. Because of the no-follow client, the expired URL's 302
  arrives as a response in about 150–250 ms.
- Fix: for an `io:*` trigger, adopt the result only when the outcome is `Renewed`. For any other outcome, rethrow
  `translate(original)`: Media3 retries as today, no `route.fallback` is set and no signal is sent. Alternatively, drop
  `io:*` from the triggers.

**A2. A mid-session 3xx that today's code follows to a working address now ends in a ladder restart.**
`HlsPlaylistRenewal.kt:679-698` (any 3xx on a route that is no longer fresh starts a renewal) and `:332` (`declined:host` is
a fallback). Confirmed by reading. I am unsure how common this provider shape is.
- Today Media3 follows every pinned-reload redirect. Some providers redirect the playlist only now and then, to a
  working target: an edge drain or rebalance, or a token refresh by redirect.
- With this branch the reload is not followed and renewal runs. If O now hands out another edge host, the outcome is
  `declined:host`, which is fatal and sends the immediate L19 restart. If O itself is briefly unavailable, the outcome
  is `failed:*`, with the same result. Today that redirect was invisible.
- The design (§6.12, R3) says falling back on `declined:host` is "today's behaviour, made immediate". That holds only
  when the redirect target is dead. When it works, today's behaviour is a seamless follow.
- Fix: when a renewal started by a redirect ends in anything except `Renewed`, re-send Media3's request (`route.current`,
  not the logical URL) with redirects followed, which is exactly today's path. Send the fast signal only if that
  followed request fails too. In the measured case the renewal succeeds, so this costs nothing there. Where the
  target is dead it costs one connect timeout, which is still no worse than today.

**A3. The "O was not redirected" check compares raw strings, so some providers that never redirect become ACTIVE.**
`HlsPlaylistRenewal.kt:211` (`finalUrl == ctx.resolved.url`), the same comparison at `:189` and `:227`, and
`RenewingHlsDataSource.openOrigin:641` (`inner.uri`). Confirmed by running the HttpUrl check and by reading
`OkHttpDataSource.getUri()`, which returns `response.request().url().toString()`.
- OkHttp normalizes URLs. `http://Host.example:80/…` becomes `http://host.example/…`, `https://h:443/…` becomes
  `https://h/…`, and a space becomes `%20`.
- Any M3U HLS entry written that way, or an Xtream base that carries an explicit default port, compares as
  "redirected". The router then turns ACTIVE with the normalized O as the route. Every later reload uses the no-follow
  client, and the provider becomes exposed to A1 and A2.
- The design's promise "not redirected → OFF, strict no-op" does not hold for these providers.
- Fix: decide on the redirect itself, not on string equality. Either compare
  `finalUrl.toHttpUrlOrNull() == ctx.resolved.url.toHttpUrlOrNull()`, or record that the response had a
  `priorResponse` (`Response.isRedirect` is already seen in `BrokeredCallFactory.Handle.responseHeadersEnd`). Add a
  test with an O written as `http://127.0.0.1:<port>` in a different form, for example with an uppercase scheme or an
  explicit default port behind a fake DNS.

  Once A1 and A2 are fixed this drops to Minor, because a no-follow reload that gets 200 is harmless.

### Minor

**A4. Once a route has been renewed, several paths send Media3's reloads to the expired logical URL instead of the
working current one.** `HlsPlaylistRenewal.kt:190` (`budget.disabled` returns `Plan.None`), `:248-254` (`turnOff` sets
the whole router OFF), `:679-685` (the `loop` branch re-opens `dataSpec`, which is L, not `route.current`), and
`:655-659` (`too_large` serves the body without rebasing it). Confirmed by reading.
- Example: after a renewal at 30 min, the adopted URL starts redirecting (`declined:loop`), or the per-playlist
  budget is disabled. Every later reload then goes to the expired L, follows its 302 into the dead address, and stalls.
- This is no worse than today, which also stalls at the expiry, but the working URL is thrown away.
- Fix: "OFF" should mean "never renew again", not "forget `current`". Keep mapping L to `route.current`, with redirects
  followed, for routes that already exist.

**A5. A loader that gives up waiting marks the route as failed for good, even when the renewal it waited on succeeds.**
`HlsPlaylistRenewal.kt:276-279` and `:298`. When a waiter times out after 15 s it gets `declined:busy`, which is a
fallback, and that sets `route.fallback` permanently. If the in-flight renewal then succeeds, nothing clears it. This is
rare: a single-variant source has one playlist loader per route. Confirmed by reading. Fix: do not store `busy` as the
route's fallback, or clear `fallback` when a renewal is adopted.

**A6. The target-duration rule can reject a good playlist and protects little.** `HlsPlaylistRenewal.kt:462-464`
(`TD_TOLERANCE_S = 1`).
- The capture already sits at the limit: 11 became 12 on the same segmenter and the same segment names.
- Media3 does not need the target duration to stay the same. It only uses it to schedule reloads and to decide that a
  playlist is stuck.
- A different stream is caught by the `msn` and `names` rules anyway.
- I am unsure whether a jump of 2 s or more happens on this provider. Suggestion: allow ±max(1 s, 25 %), or keep the
  `td` rule as a log line only.

**A7. The continuity check can accept a different stream as a continuation.** `HlsPlaylistScanner.kt:212`
(`fileName` keeps only the last path segment) and `HlsPlaylistRenewal.kt:473-479`.
- Segmenters that name files by time or sequence in separate directories, such as `/720/00-06000.ts` and
  `/1080/00-06000.ts`, have equal hashes. A different rendition or stream on the same host then passes the `names` rule.
- The rule also does not use `#EXT-X-PROGRAM-DATE-TIME` when both playlists carry it, although the scanner already
  records it per entry (`Segment.pdtMs`).
- This is the documented residual risk R6, and both edges have to be on the same host. Suggestion: hash the whole path
  without the query. Where both entries have a PDT, also require the PDTs of overlapping media sequence numbers to agree
  within about 1 s.

**A8. The quiet reconnect does not hide a real problem, but the stats overlay does not count the silent restarts.**
`PlaybackControllerImpl.kt:1634-1663` and `StatsFormatter.kt:98`. Checked by reading:
- the pill still shows RECONNECTING n/max, because the state `Buffering(RECONNECTING, ReconnectProgress)` is unchanged
  (`TunedStateMapper`, `PillRules`, 400 ms debounce);
- the ladder's `transientRetries` still escalates to the card, unless `LADDER_HEALTHY_RESET_MS` of healthy play
  separates the failures;
- the delayed job is guarded by the cycle.

  So a channel that drops every 20 s and recovers in 2 s is visible as a pill, not as a toast. Two gaps remain:
- `StreamStats.reconnects` and the new `playlistRenewals` are per-session telemetry, so both reset at every ladder
  restart (a new tune id). Neither the overlay nor anything else shows how many silent restarts a viewing has had.
- If the state is anything but `Buffering(RECONNECTING)` at 3 s, the notice is skipped for that series, and so is
  "Reconnected".

  Suggestion: add a per-cycle restart counter to the overlay. Also keep `playlistRenewals` per cycle, not per session.

**A9. When the device briefly reports no network just as the 302 arrives, the stream stalls with no early signal.**
`HlsPlaylistRenewal.kt:694-695` (`declined:offline` rethrows the translated original) and `:687` (the `Joined` re-request
with `mayRenew = false`).
- When the original failure was the no-follow 3xx, the rethrown error is `InvalidResponseCodeException(302)`.
- `LoadRetryRules` maps any status it does not list to "no retry", so Media3 treats the error as fatal, and no fast
  signal is sent.
- The result is a stall until the buffer drains, which is today's timing. It needs the 302 and the "offline" reading to
  coincide. Confirmed by reading. Fix: for a 3xx original, rethrow it as a transient open `HttpDataSourceException`, or
  follow it as in A2.

### Checked and fine (A)

- **Media3 contract.** The tracker pins `result.baseUri`, which is `getUri()` after the first media playlist
  (`DefaultHlsPlaylistTracker:408`). A body served in SERVE mode reports Media3's own `spec.uri`, so the base URI
  and identity do not move.
  - `msn_back` agrees with `isNewerThan`, so Media3 would have ignored such a body anyway.
  - The stuck and reset checks (`:1169-1181`) see a newer snapshot after a renewal.
  - Empty response headers in SERVE mode: no app code reads them. The controller's `onLoadError` uses only
    `dataType`.
- **Lifetime and threads.**
  - Every ladder restart, zap, Retry and Flip goes through `launchSession`, which issues a new tune id and so a new
    `TransportSession` and router. No router outlives its session.
  - Archive uses `http()` unchanged. HLS never uses the ring. Clips and VOD turn OFF at `endlist` or `multivariant`.
  - Router state is under its lock, `Route.current` is volatile, and `followRedirects` is per data source on one loader
    thread.
  - `terminate()` wakes waiters, and `endSession` cancels the O call.
  - `ctx.onEvent` posts to the main thread.
- **Broker.** O goes through the same lease and `BrokeredCallFactory`, and the pinned call is closed first. No `Range`
  is sent: position 0, length unset. The no-follow twin shares the pool, dispatcher and TLS settings. The `WeakHashMap`
  values do not reference their keys.
  - Not verified: whether an Xtream panel counts an O fetch made while the old token's segments are still loading as a
    second connection. If it does, the result is `failed:status:*`, which goes to L19, which is today's outcome sooner.
- **Redaction.** `translate` drops `Location`, the headers, the body and any cause that carries a `DataSpec`. Exception
  messages carry the outcome label only. Log lines carry hosts (IP literals hashed) and salted hashes. `fetchOrigin`
  never lets O's exception out.
- **Debug probe.** It is gated by `BoxLog.minLevel <= DEBUG`, which only `TheBoxApplication` sets, from
  `BuildConfig.DEBUG`. In release, `begin` returns null and no wrapper is built. What remains is one `isTracked` map
  lookup and one `ThreadLocal` read per call. Release logs from the renewal are about one INFO line per renewal or decline.
- **Permission.** `ACCESS_NETWORK_STATE` is a normal permission, and `activeNetwork` exists from API 23, below
  minSdk 25. Any exception reads as online.

---

## B. `ts-retry`

### Blocker / Major

None.

### Minor

**B1. When TS plays and then fails, the rest of the tune stays on TS, even though HLS is known to work.**
`player/.../PlaybackControllerImpl.kt:3525-3533` (`return if (c.firstMediaReached) decision`).
- After first media, an inconclusive failure lets the ladder's decision stand, and `c.output = TS` survives every
  re-resolve in the cycle.
- Scenario: a provider accepts TS and cuts it after a few seconds, again and again. The tune runs through L19 on TS and
  ends at today's STREAM_INTERRUPTED card, which has a reason and a next step, while HLS would have played.
- Retry from the card starts a new cycle with `c.output = null` and the learned HLS, so the viewer is not stuck.
  Confirmed by reading.
- Fix: on an inconclusive failure after first media whose decision re-resolves, set `c.output = null`, so the next
  session resolves to the learned HLS.

**B2. A channel that stalls under tunneling stalls again on every visit, with nothing to damp it.**
`PlaybackControllerImpl.kt:3591-3606`. This is the owner's ruling, and the decision note's "Cost" section accepts it.
- What the viewer sees on each visit: the stall until L27/L28 detects it, a reprepare, and the "Tunneling is off until
  you leave this channel" toast.
- This hits exactly the case the retired bit was added for: Sony/MediaTek HEVC, where every HEVC channel stalls.
- There is no loop inside a visit, because `tunnelingActive = false` keeps L27 from firing again.
- Suggestion, which needs the owner's agreement: remember it in memory only, for the life of the process. For example,
  after 2 stalled visits to the same channel since start-up, start that channel untunneled for the rest of the process
  and say so in the stats line. Nothing is persisted, so the ruling still holds.

**B3. The stats line says "off (setting)" in cases where no setting turned tunneling off.** `core/.../StatsFormatter.kt:49`.
- The line reads `player.isTunnelingEnabled` (`StatsCollector:137`), the actual state.
- So it says "off (setting)" for a clip, for a device or format the track selector cannot tunnel, and for a channel
  where the Playback setting is on.
- Suggestion: say "off (setting)" only when the global setting or the channel option is off, and plain "off" otherwise.

**B4. Manual "Off" choices from before the upgrade are lost, and the user-only bit is not backed up.**
`app/.../RoomChannelMemory.kt:58-60` and `data/.../BackupRestorer.kt:434`.
- Retiring bit `0b1000` silently drops any "Off for this channel" a viewer picked by hand before this version. The note
  accepts that.
- Bit `0b100000` is now purely a user choice, as is `AFR_ALWAYS_BIT`, but backup treats `playback_flags` as a learned
  column and leaves it out. CLAUDE.md treats prefs as user data.
- Confirmed by reading. Suggestion: export and restore the two user bits, or record the gap in the backup design.

**B5. Two fast tunes that are both refused can step the back-off twice.** `core/.../AutoTsRetry.kt:119-123` and
`data/.../LearnedOutputStore.kt:30-38`. I am unsure this happens in practice.
- `recordTsRetry` writes in the background. If the next user tune's `playlist()` read runs before the `REFUSED` write
  lands, which needs a very fast zap after a quick 405, that tune retries and is refused again.
- Each refusal adds one step, so the back-off goes 24 h → 7 days in one sitting.
- Fix: in `afterResult(REFUSED)`, keep the step when the previous value was learned less than about a minute ago.

**B6. A device clock that is wrong at boot causes a few extra TS retries.** `AutoTsRetry.kt:92`.
- When the clock is behind at boot (before network time), a stored `t` more than 7 days in the future is ignored, so a
  retry is due. The retry's result is then stored with the bad time, so the next tune with the right clock is due again.
- This is bounded: a few extra refusals, one round trip each. Mention it in the note, or skip recording when
  `now < BUILD_TIME`.

### Checked and fine (B)

- **When a retry may happen.** `userTune` comes only from `tune`, `tuneArchive` and the zap paths. It is consumed by the
  cycle's first session and only for `Spec.Live`, with `c.output == null`.
  - Resume, Retry, backup-source cycles and ladder `startCycle(specForRestart())` all pass `false`.
  - `autoTsRetryDue` is read fresh in each cycle's `readFacts`.
- **Every failure path ends playing or at a card.**
  - A refusal goes through L9 to HLS in the same tune, with the notice suppressed.
  - An inconclusive failure before first media flips to HLS, even over a switch-window `Fail`, with a fresh window and
    `formatFlips + 1`. A 405 on HLS then ends at L10.
  - With no picture after 8 s, the same flip runs from the timer. The timer is guarded by the cycle, the first media,
    the card and the state.
  - The 30 s confirm timer is guarded by the cycle and `failureCount`. Zapping away records nothing.
- **Time-shift.** The timer's `execute(Flip, fromWriter = false)` takes `executeBuffered`'s "close the ring and
  re-decide" path.
- **`allowed_output_formats`.** When the list is known and lacks `ts`/`mpegts`, no retry is ever due.
- **Stored value.** `LearnedOutput.parse` never throws, and anything unparseable reads as plain Auto.
  - The old app reads `HLS;…` through `output()` with `firstOrNull`, which gives null, so it treats the playlist as Auto,
    hits one 405 and relearns a plain `HLS`. A downgrade is safe.
  - The three old `== "HLS"` readers are all updated. `PlaylistSettingsRepository` passes the raw value on to
    `PlaylistPages`, which parses it.
  - The column is not in backups.
- **Tunneling bits.** An old row's bit 8 is ignored on read and dropped on the next write, and the new bit 32 is ignored
  by the old app. The options page shows the user's bit only. Clips clear the visit, and the old `remember(tunnelingOff)`
  is removed.

---

## Merge and interaction notes

- **Textual merge.** `git merge-tree` is clean. The hunks in `PlaybackControllerImpl.kt` (Cycle fields, `onFirstMedia`,
  `onFailure`, `execute`, `publishStats`, companion), `PlaybackApi.StreamStats`, `PlayerContracts.StatsFacts`,
  `StatsFormatter`, `PlayerMappers` and `LadderExecutionTest` are at least 5 lines apart. The merged tree compiles and
  the targeted tests pass (listed at the top).
- **TS-retry fallback to HLS and the renewal router.** The flip goes through `restart` → `launchSession`, which issues
  a new tune id, so the HLS session gets a fresh router and lease. The old TS session ended first, cancelling its old
  call before the next one opens. No lifetime clash. The renewal budget is per `brokerKey` and is unaffected.
- **Notices.**
  - A fallback before first media shows `Connecting`, not `Buffering(RECONNECTING)`: `restart` takes the
    `!firstMediaReached` branch. So the quiet-reconnect code never runs there, and B's suppressed `SwitchedFormat` is the
    only notice involved.
  - A renewal fallback after first media reaches L19, which is transient, so the quiet rule applies. A 1 s ± 20 % delay
    plus the resolve plus the HLS start is close to the 3 s threshold. Expect the toast to be shown on some hand-overs
    and not on others. The pill shows either way.
  - The two notice paths do not share state.
- **Tunneling and a silent reconnect.** Ladder restarts, including those that start a new cycle, carry the visit, so
  tunneling stays off. Only a user tune turns it back on, and the old cycle's deferred "Reconnecting" job is guarded by
  `cycle === c`, so no stray toast appears.
- **Owner's playlist.** If B's retry is confirmed, the owner's Auto playlist moves to TS and A is no longer exercised on
  their device. To validate A on hardware, set Stream format to HLS by hand. If TS is refused again, the next try is
  in 3 days, because the legacy plain `HLS` counts as learned long ago.
- **Stats.** The two new fields are independent. Both are per session (A) or per cycle (B). A's counter resets at
  every restart (A8).

## Not reviewed

- On-device behaviour. Neither branch was run on hardware or the emulator. The A1 and A2 provider shapes are inferred,
  not observed.
- `HlsEdgeProbe.kt` and `HlsEdgeDataSource.kt` internals beyond the release gate, the hashing and the never-log list.
  The probe tests.
- `tools/fakepanel` changes. The UI fit tests and strings (`TunnelingNoticeFitTest`, `StreamFormatRowFitTest`). The
  settings-machine reselect change beyond its one condition.
- The rewritten design doc's §9 reconciliation table line by line, and the earlier design review.
- Lint and the full gate (not run, by instruction).

---

## Resolution (fix pass, 2026-11-26)

Branch `playback` (`hls-fix` + `ts-retry` merged). Design changes: `docs/design/m6-hls-edge-handover.md` §10. Decision
notes updated: `docs/decisions/2026-11-25-auto-format-retries-ts.md` and `2026-11-25-tunneling-off-per-visit.md`
("Code review changes" sections).

| Finding | Status | What changed | Tests |
|---|---|---|---|
| **A1** blip before the headers restarts playback | **Fixed** | An `io:*` trigger is "soft": one renewal attempt per route per 30 s (`IO_COOLDOWN_MS`), budget counted but never disabled by it (`tryTakeSoft`), adopted only when `Renewed`; otherwise the original error is rethrown (translated as before), no fallback stored, no signal: Media3's retries run as on `main`. | `aBlipBeforeTheHeadersKeepsMedia3sRetriesAndNoFallback`, `aBlipThatARenewalRoutesAroundIsAdopted` |
| **A2** a 3xx to a working address now restarts | **Fixed** | A 3xx whose renewal is not `Renewed` re-sends the reload once with redirects followed (route's current URL); a media playlist that passes the continuity rule is served rebased under Media3's URI; same host → adopted as current; other host → served rebased and the route follows redirects from then on. Only a failed follow (`follow_failed:<why>`) ends in the typed fallback. | `aRedirectToAWorkingPlaylistOnAnotherHostIsStillFollowed`, `aRedirectToAWorkingPlaylistOnTheSameHostIsAdopted`; every existing fallback test now also runs the failed follow |
| **A3** raw string comparison of O | **Fixed** | `HlsPlaylistRenewal.sameUrl` (`HttpUrl` equality) for "not redirected", the ACTIVE-state check and Media3's request for O. | `anOriginWrittenInAnotherFormIsNotARedirect` |
| **A4** "off" forgets the current URL | **Fixed** | `turnOff` (loop, too_large) and the budget's disable mean "no more renewals": a renewed route is still requested at its current URL with redirects followed (`Plan.Pinned(follow = true)`, mode FOLLOW); `too_large` and an un-rebasable 200 on a renewed route re-request the same way. | `aDisabledBudgetAfterARenewalKeepsTheCurrentUrl`, `aRedirectOnTheFirstReloadMeansThisProviderRedirectsEveryReload` (updated) |
| **A5** a timed-out waiter fails the route for good | **Fixed** | `busy` is not a fallback and is never stored; a 3xx waiter gets today's followed request; a successful renewal clears any stored fallback; a waiter takes the finished flight's outcome. | `aWaiterThatTimesOutDoesNotFailTheRoute` |
| **A6** td rule too tight | **Fixed** | max(2 s, 25 %) (`TD_TOLERANCE_S`, `TD_TOLERANCE_PERCENT`); reasoning from the capture in design §10.4. | `continuityRuleOnTheMeasuredNumbers` |
| **A7** continuity can accept another stream | **Fixed (narrowed)** | Overlapping MSNs must also agree on `#EXTINF` (± 500 ms, `misaligned:dur`) and, when both carry one, PDT (± 1 s, `misaligned:pdt`). File name kept as the last path segment (the measured token is in the directory). Residual risk stated in design §10.4. | `continuityRuleOnTheMeasuredNumbers` |
| **A8** silent restarts not counted; quiet rule unbounded | **Fixed** | Renewals (via a `DECISION`/`HLS_PLAYLIST_RENEWED` event) and restarts after first media (with the quiet ones) counted per tune and per visit; overlay HTTP `n renewals (visit m)` and a new Restarts row. Cap: after 2 quiet series in 10 min of one visit the next announces at once (`QUIET_RECONNECT_MAX`, `QUIET_RECONNECT_WINDOW_MS`). The deferred notice fires for any waiting state at 3 s. | `LadderExecutionTest.theThirdQuietReconnectWithinTenMinutesIsAnnounced`, `StatsFormatterTest.renewalsAndRestartsCountPerTuneAndVisit`, `StatsLineFitTest` (Default and Largest) |
| **A9** offline at the 302 stalls | **Fixed** | Online re-checked 3 × 500 ms before declining offline; a declined-offline 3xx takes today's followed request (transient connect error, Media3 retries) instead of the fatal unfollowed 302. | `offlineNeverRenewsAndKeepsTodaysError` (updated), `aMomentaryOfflineReadingAtTheExpiryStillRenews` |
| **B1** TS plays then keeps failing | **Fixed** | Second failure after first media (or one that would end at the card) falls back to HLS for the tune, once; refusal only on 405/406. | `AutoTsRetryControllerTest` (3 new) |
| **B2** stall on every visit | **Fixed (needs the owner's confirmation)** | In-memory damping: 2 consecutive stalled visits (`TUNNELING_STALLED_VISITS`) → the channel's next visits start untunneled for the process, no toast, stats `off for now (stalled before)`; a good tunneled visit resets; nothing persisted. Wording for the owner in the decision note. | `TunnelingVisitTest` (2 new) |
| **B3** "off (setting)" when no setting | **Fixed** | `TunnelingOff` reason: setting / stalled / stalled before / not used for replays / not supported for this stream/device; plain "off" before first media. | `StatsFormatterTest.theTunnelingLineHasThreeStates`, `TunnelingVisitTest` (stats asserts, clip), `StatsLineFitTest` |
| **B4** user bits lost / not backed up | **Documented** | Decision note (tunneling): known backup gap for the two user bits; no change here. | – |
| **B5** double back-off step | **Fixed** | A refusal within 60 s of the previous (re)learn keeps the step (`REFUSAL_DEDUP_MS`). | `AutoTsRetryTest.twoRefusalsWithinAMinuteStepTheBackOffOnce` |
| **B6** wrong clock at boot | **Documented** | Decision note (TS retry): bounded cost, no "clock trusted" signal to gate on. | – |
| Merge: fallback flips quiet/noisy around 3 s | **Fixed** | `FailureSignal.PlaylistExpired` + ladder row **L19p** (`ReResolve(0)`, limiter fallback `RetrySame(0)`, counted like L19, exhausted at L20) in `:core`; the controller sends it for `HLS_RENEWAL_FALLBACK`. | `ladder.csv` L19p rows, `ErrorLadderRulesTest.anExpiredPlaylistReResolvesAtOnceAndCountsLikeL19`, `LadderExecutionTest.anHlsRenewalFallbackReachesTheLadderAtOnceAsTodaysNetworkRow` (updated) |
| Merge: owner's playlist moves to TS | **Recorded** | Release gate in `docs/00-master-plan.md` §6 and design §8.3/§10.7: "to validate HLS renewal on a playlist that the TS retry would move to TS, force Stream format to HLS". | – |
