# Sports Hub code review: data, player, app, guide side (2026-11-25)

Branch `sports-hub` @ 85ce40b, `git diff main..HEAD`. Scope: `core/`, `data/`, `player/`, `app/`, `tools/fakepanel/`, and in
`ui/` only `guide/` (GuideStateMachineImpl, GuideViewModel, GuideEffectsImpl, GuideQueries), `full/vm/PlayerViewModel` and
`sports/ClipSession.kt`. The Sports UI composables and `SportsViewModel` belong to the other reviewer. Reference: design
`docs/design/m6-sports-hub.md` (§4–§6, §15) and `docs/decisions/2026-11-24-sports-hub.md`.

Method: I read the code and checked each finding against the source. I ran no tests, used no emulator and edited no source.
Line numbers are at 85ce40b.

## Summary

| Severity | Count |
|---|---|
| Blocker | 0 |
| High | 1 |
| Medium | 4 |
| Low | 7 |

---

## High

### H1. A clip whose first lookup finishes after Home or standby plays in the background

**Where.** `ui/src/main/kotlin/app/thebox/ui/sports/ClipSession.kt:231-258` (`start`), `:293` (`onPlayback` returns early
while `!playedAny`), `:386-389` (`snapshot`). `player/.../controller/PlaybackControllerImpl.kt:137-141` in the diff
(`playClip`) has no foreground check. `SportsViewModel.onLeft()` runs only when `SportsScreen` is disposed
(`SportsScreen.kt:160-163`), and Home or standby does not dispose it.

**Scenario.**
1. On the Sports page the viewer presses OK on a goal.
2. `ClipSession` is in `Resolving`. The lookup can take up to 10 s. On the first video of the process it also reads the
   player script, which allows 20 s more.
3. The viewer presses Home, or the Fire TV goes to standby. `LifecycleCoordinator` calls
   `controller.stopForLifecycle(BACKGROUND/STANDBY)`, and the state becomes `Stopped` on the live channel.
4. `onPlayback` ignores that `Stopped` because `playedAny` is still false.
5. The lookup returns. `snapshot()` sees `Stopped` and gives `ResumePoint.Nothing`.
6. `controller.playClip(...)` then starts a new cycle. The controller does not check `lifecycle.isForeground` for any
   tune.

**Result.** ExoPlayer plays the clip's audio, and fetches its segments, while the app is in the background or the TV is
in standby. A queue of goals keeps going, because each `STATE_ENDED` starts the next clip. When the viewer comes back,
`resumeAfterLifecycle` finds no `Stopped` to resume, so the clip just keeps playing.

The rest of the app treats background playback as a defect: `SessionPlayer` ignores play and next commands in the
background (`SessionPlayer.kt:63,88`). No test covers a lifecycle stop during the lookup. `ClipSessionTest` has only
`aLifecycleStopMidClipEndsWithoutResuming`, which runs after `playClip`.

**Fix (either, preferably both).**
- `PlaybackControllerImpl.playClip`: when `!lifecycle.isForeground`, or when the state is `Stopped` with a reason in
  `LIFECYCLE_REASONS`, return -1 and start nothing. `ClipSession` already handles `id < 0`.
- `ClipSession.start`: just before `playClip`, if `controller.state.value` is `Stopped` with a lifecycle reason, call
  `end()` and do not play. Also end the session on a lifecycle `Stopped` while `Resolving`, not only after `playedAny`.

Add a `ClipSessionTest` for "Home during the lookup → no `playClip`, Idle, no resume".

---

## Medium

### M1. A temporary failure to read the script turns video off for 10 minutes, and the toast says "Try again in a minute"

**Where.** `data/.../sports/NhlVideoKeySource.kt:95-99`. `hold(r.kind)` runs for every `Unavailable`, including
`TIMEOUT`, `IO` (and so offline), `HTTP_5XX` and `HTTP_429`. `NhlClipResolver.kt:101,108` passes the held kind on as
`Failed(kind)`, and `ClipSession` (`:262-268`) maps it to `UNAVAILABLE`.

**Scenario.** The Fire TV wakes from standby and Wi-Fi takes a few seconds to come back. The panel's automatic `info()`
for a final game's recap is the first video use. The script read fails with an `UnknownHostException`, which counts as
IO, and the hold starts. For the next 10 minutes every Play and every poster lookup fails at once, without a request,
with "The NHL video service didn't answer. Try again in a minute." That reason and next step are wrong for nine of
those ten minutes.

This matches §6.2's wording ("hold after any failed read: … script fetch failure"). §6.8's text assumes a short wait, so
the two sections disagree.

**Fix.** Keep the 10-minute hold for "not found" and "refused". For TIMEOUT, IO, 5xx and 429, back off instead: start at
about 30 s, double each time, cap at 10 minutes, and use `Retry-After` as a floor. Or skip the read while
`SportsSignals.online` is false. Record the change in §6.2.

### M2. The resume after a clip can override a tune made during its wait

**Where.** `ui/.../guide/vm/GuideViewModel.kt`, `onSportsEffect`, `SportsEffect.Resume` branch (the `resumeJob` with
`CLIP_RESUME_VIEWPORT_WAIT_MS`).

`resumeJob` is cancelled only by a newer `Resume`. It is not cancelled by `SportsEffect.Tune` or `PlayArchive`, nor by any
tune from elsewhere: an intent, Watch Next, a PIN success, or `LiveSettingsApplier`. After the wait it also does not check
whether something else now owns the player.

**Scenario.**
1. Back ends a clip, and `Resume(Live(X))` is emitted.
2. The viewport is still FULL, so the job waits.
3. In that window the root does not give the page its viewport back. Usually this lasts one frame. It can last the full
   500 ms if the root is slow, or if it keeps FULL because another FULL request came in at the same moment.
4. A `SportsEffect.Tune(Y)` or an intent tune to Y happens in the window.
5. When the wait ends, `guarded.tune(X)` replaces Y.
6. If the tune came from the page (`SportsClosed`), the guide then waits for Y's Tuned to go FULL and gets X instead.

The window is usually short, so I rate this Medium rather than High. It is still a race with no guard and no test.

**Fix.**
- Cancel `resumeJob` at the top of the `Tune` and `PlayArchive` branches.
- In the job, note `controller.state.value.target?.tuneId` when the effect arrives. After the wait, skip the resume if a
  newer target that is not a clip has started.

### M3. The alias query's `LIMIT 200` comes before the residue rule, so a large playlist can lose the national channel

**Where.** `data/.../sports/GameChannelMatcherImpl.kt:141-152` with `GuideQueries.channelsByNameFts` (no `ORDER BY`,
`LIMIT ?3`).

**Scenario.** One phrase query matches every channel containing the token: `"espn"` also matches ESPN 2, ESPNews,
ESPN Deportes, ESPN Brasil 1–4, ESPN Caribbean and so on. `"sportsnet" OR "sn"` also matches SN World, SN+ and every
region. On a 20 k "world" playlist, across two or more playlists and with HD / FHD / backup variants, these queries
easily pass 200 rows. The rows are then whatever FTS returns first, effectively the earliest-imported docids. The
residue-equality filter runs only on those 200, so "US: ESPN" or "CA| SN ONTARIO" can be cut and the game shows "No
channel found".

The benchmark's synthetic playlist would not show this (see L5).

**Fix.** Page through results (keyset on rowid) until the code has some residue matches or a hard cap such as 1 000
rows, or raise the limit only for national codes. Add a test with more than 200 near-miss channels ahead of the real one.

### M4. The Fire TV cost gate (§5.7, p95 ≤ 250 ms) has not been measured

`GameChannelMatcherBenchmarkTest.kt:188` asserts only a JVM bound of p95 < 3 000 ms, and §15.3 lists the Fire TV Stick
items as not run. This is a gate the release depends on, not a code defect. Run the benchmark on the Stick before
release, or as an androidTest. If p95 is over the gate, switch to the `UNION` form the design describes.

---

## Low

**L1. The clip switch-timeout card uses the live error code.** `PlaybackControllerImpl.onSwitchTimeout` (diff around
`:1720-1735`) builds the error with `eventualCode(signal)` for clips too. It should use `FailureClassifier.clipCode`, so
diagnostics and the fallback card never name a channel or the archive (for example `CHANNEL_OFFLINE`). Fix: use
`if (c.isClip) clipCode(signal) else eventualCode(signal)`; with a null signal, use `STREAM_INTERRUPTED`.

**L2. Lookups still follow https → https redirects to other hosts.** `NhlClipResolver.kt:45-49` sets only
`followSslRedirects(false)`. `followRedirects` stays true, so a cross-host https redirect keeps the
`Accept: application/json;pk=<key>` header (OkHttp strips only `Authorization` when the host changes). The key is a
public player key, so the impact is small. Fix: `followRedirects(false)` on the lookup client. `classify` already treats
3xx as unexpected.

**L3. A channel renamed by the user is not found by its new name.** `GuideQueries.channelsByNameFts` matches
`channel_fts`, which indexes the provider name and display name but not `channel_prefs.custom_name`. The Kotlin residue
check does look at the custom name (`displayName`), but only for rows the FTS query already found. A channel the user
renamed "Sportsnet Ontario" whose provider name is "CA: SNO HD" is therefore missed. Accept this and document it, or add
a small second query on `custom_name`.

**L4. A refetch whose landing call fails replaces a good cached detail with one that has no goals.**
`GameDetailRepositoryImpl.fetched` (`:272`) stores `Entry(f, landingFailed = true)` over an entry that has a landing. One
landing 5xx during a live game therefore empties the goals, stars and leaders until the next poll. Fix: when the new
answer has `landingFailed` and the old entry had a landing, keep the old landing fields and set `failing`.

**L5. A benchmark assertion that cannot fail.** `GameChannelMatcherBenchmarkTest.kt:187`,
`assertTrue("SN+-only game: no alias candidate", (found["382"] ?: 0) <= 8)`, is always true because the result is capped
at `HUB_MATCH_MAX = 8`. Fix: assert `== 0` for alias-only candidates. Guide-confirmed candidates are legitimate, so
assert on `reasons` having no `Broadcaster`.

**L6. `boxScore` is neither single-flight nor cancelled by a game switch.** `GameDetailRepositoryImpl.boxScore`
(`:115-127`) bypasses the actor. Opening the sheet twice quickly sends two requests. The caller's scope does cancel them,
so the impact is small.

**L7. `seenUnfinished` only shrinks on `trim()`.** `GameDetailRepositoryImpl.kt:144` is a `HashSet` that grows by game
ids. It is tiny in practice, but it is the only unbounded structure in the repository. Use the same LRU as `endedAt`.

---

## Checked and found sound

- **Key handling (NhlVideoKeySource / KeyScan).**
  - The key is held only in a `@Volatile` field. There is no file, preference or export path. `Answer.Ok.toString()`
    prints `***`.
  - `Redact.registerSecret` runs before the key is stored. Log lines are fixed strings with no value, prefix or length.
  - Reads are single-flight behind a `Mutex`. Parallel first lookups share one read (`parallelFirstLookupsShareOneRead`).
  - `invalidate(stale)` only drops the key the caller saw, so two refused lookups cause one re-read. A refused re-read
    leads to `refused()`, a hold and `Refused`.
  - A cancelled read throws `CancellationException` (not IOException), so no hold starts and the lock is released.
  - Only https is fetched outside the `FLAG_DEBUGGABLE` override, and `SportsEndpoints.resolveVideo` reads the override
    file only in a debuggable build.
  - The scan reads `body.source()`, so the 2 MB cap counts decoded bytes. It parses as it reads, in Latin-1 so one
    character is one byte, and finds matches that span chunks. Rejected candidates let the scan continue. The window is
    bounded (`MAX_LENGTH`, `MAX_SPACE`).
- **NhlClipResolver / BrightcoveMapper.**
  - 401 and 403 without `CLIENT_GEO` lead to one re-read and one retry; geo keeps the key; 404 is `NotFound`; 429, 5xx and
    IO are `Failed`. The 64 KB cap is checked on decoded bytes before decoding.
  - Signed query tokens are registered as transient secrets before anything can print them. `ClipStream`, `ClipTune` and
    `ClipMedia` print only the host.
  - The source order is as designed: https HLS, then https MP4 ≤ 720p, then http. DASH, `key_systems` and blank or
    non-http sources are skipped. Fields of the wrong type count as missing.
  - `info()` uses a 10-minute TTL and an LRU of 40. `stream()` is never cached.
- **Secrets in the tree.**
  - Only `FAKE-KEY-FOR-TESTS*` literals appear: `NhlClipResolverTest`, `KeyScanTest`, `ExportRedactionTest` and
    `tools/fakepanel/sports_video.py`.
  - The recorded `clip-*.json` fixtures have every signed query replaced with `?redacted=1` (17 per file).
  - No `BCpk…`, `fastly_token=`, `hdnts`, `Policy=` or `Signature=` value appears outside test placeholders.
  - HockeyTech keys appear only in `HockeyTechEndpoints.kt`. Tests use `$key` variables.
- **The controller's clip path, checked against the per-branch table in §6.5.**
  - `startCycle`: the ring is discarded. `previousIdentity` does not move when either side is a clip. The ladder's
    `beginChannel` is skipped. `clipZapAnchor` makes a zap during a clip, or during a queue of clips, start from the
    channel the clip replaced.
  - Resolving: the clip gets a local `ResolvedStream` on its own broker key `clip:<host>`, configured with
    `HUB_CLIP_MAX_CONNECTIONS`. The lease and `transport.http(ctx)` are the clip key's, so `TuneSession.end()` cancels
    them.
  - Switches: buffer MEDIUM, tunnelling off, no channel AFR, no `onTune` and no `awaitSettled`. `setViewport` leaves the
    display mode alone during a clip, and `syncDisplayViewport` catches up on the next live or archive cycle.
  - `STATE_ENDED`, pause and resume behave as archive. No rebuffer FSM step runs.
  - `onClipFailure` skips the ladder. A 403 or 410 gives `CLIP_EXPIRED` at once; IO, 5xx, 408, 429 and stalls get at
    most `HUB_CLIP_RESTARTS` restarts at the playhead. Nothing is written to channel memory.
  - `retry()`, and `resume()` after a stop, are no-ops for a clip. A stop leaves a clip not resumable, and
    `resumeAfterLifecycle` returns early for one.
  - `peekExternalHandOff` and `handOffExternal` refuse clips. `reapplySettings` and `selectServer` skip VOD.
  - `recordWatched` is keyed by identity, and a clip has none, so it never reaches recents or last-watched.
  - `ArchivePlaybackImpl.owns()` needs matching identities, so a clip ends any catch-up session and is never mistaken for
    archive continuation.
- **The English caption preference does not leak.** `TrackController.applyTuneDefaults` replaces the preferred text
  languages and clears overrides on every cycle's first session. The next live cycle gets the settings' list back. The
  Menu toggle's `selectTextTrack(remember = false)` writes nothing, and its override is cleared on the next tune. This is
  tested by `aClipPrefersEnglishText…` (its final assertion).
- **MediaSourceBuilder clip sources.**
  - HLS uses the master's own rendition; CEA-608 is not exposed when the master does not declare it.
  - MP4 goes through `SessionUriDataSource`, so `Range` passes for VOD and the signed URL hides behind a placeholder.
  - Side-loaded VTT is merged through `SubtitleExtractor`, with its own placeholder URI and the brokered factory.
  - `LiveTsDataSource` is never used for a clip.
- **The guide and player clip filter (`withoutActiveClip`).**
  - Active clip states are dropped before the tuned mapper, the parental watcher and the player machine. Terminal ones
    become `Idle`.
  - Other `controller.state` readers handle a clip correctly: `ChannelOptionsPageSource.isTuned` (identity null), error
    routes (no hand-off) and `PlayerViewModel` lookups by identity.
  - `PlayerViewModel.cues` passes cues while the target is a clip.
- **The archive resume (`GuideEffectsImpl.resumeArchive`).**
  - `ClipSession` computes `playheadMs` correctly as `wallMs + (now − atMs)` while advancing. `atMs` is the device time,
    not the playhead.
  - The seek waits for the first PLAYING of the same `identityHash`. It is skipped on FAILED, on an end before playing, on
    another session taking over, or within 5 s of the start. `close()` cancels the job.
- **ClipSession races.**
  - Each of these ends the session through the `generation` counter and `job.cancel()` on the main thread: Back during
    the lookup (no resume), Back during Up next, a foreign tune mid-clip, a lifecycle stop mid-clip, and a second OK during
    a lookup.
  - `CLIP_EXPIRED` gets one fresh lookup per index at the playhead. A second expiry shows the card.
  - The layer is hidden before `Resume` is emitted.
- **GameDetailRepositoryImpl / DetailPollPolicy.**
  - The actor owns all state. A switch to another game cancels the call in flight and bumps the generation, so a stale
    answer is dropped.
  - Showing the resting game a second time skips the rest (`showingTheRestingGameAgainSkipsTheRest`).
  - `pause` closes the gate. `nextWake` cannot busy-loop: the fetch time is used only when the gate is open and nothing is
    in flight.
  - Backoff with `Retry-After` as a floor works, and 429 or REFUSED waits at least `BACKOFF_MAX_MS`. Stale turns on at
    2 × the interval. A final game waits for the recap until 3 h after the end, with an estimated end when it was first
    seen already final. The loop is guarded against crashes.
- **NhlDetailSource / NhlDetailMapper / NhlMapper.**
  - right-rail and landing are fetched together. A failed landing gives `landingFailed`. The mapper is wrapped in
    `runCatching`-style guards, and every field is optional and range-checked.
  - Period labels are REG n, OT (number − regulation, so 2OT and later work), and SO.
  - Headshots are accepted only over https.
  - Board video ids work as numbers or as the trailing digits of a path. `tvBroadcasts` is trimmed, kept only for N, H or
    A, and de-duplicated.
  - Board facts are attached only to the day list (`landing = false`, `boardFacts = true`), so the score bug's follow
    snapshot is unchanged. The `sportsHashGate` score-bug entries hash equal.
- **ESPN and HockeyTech additions.** They are additive and only on the day board. Records are checked with a regex,
  linescores are bounded (≤ 10 periods, ≤ 99 points), and the CHL shots come from board fields. The existing score bug
  path does not change.
- **GameChannelMatcherImpl.**
  - It runs on the IO dispatcher and checks for cancellation between stages.
  - The FTS `MATCH` text is safe. Known codes use fixed table phrases. An unknown code is folded and tokenised to
    letters and digits joined by single spaces before it is quoted, so a trailing space ("ABTV "), "+", quotes or
    operators cannot reach the query. Codes containing "+" and streaming codes have no aliases.
  - Hidden and locked channels are filtered by `LINEUP_BASE` and `PARENTAL_RAW` in the FTS query and by
    `channelsForRefs(access)`.
  - The cache key includes access, the EPG generation and patch, the phase, the start and the broadcasts, and the cache is
    cleared on page open.
- **GuideStateMachineImpl `SportsClosed`.** It shares `landOnPicked` with Search, closes SPORTS into the grid and goes FULL
  only on the tuned channel's own first Tuned, or the first archive Tuned. `GuideStateMachineSportsTuneTest` covers it.

## Not checked or out of scope

- The Sports UI composables, `SportsViewModel` and `AppRoot` (the other reviewer).
- Behaviour on a device (Fire TV cost, real Brightcove media).
- The full test suite.
