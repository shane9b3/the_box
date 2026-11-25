# Sports Hub design review: architecture, data, playback and channel matching

Reviewer: architecture / data / playback (adversarial). Subject: `docs/design/m6-sports-hub.md` (proposed 2026-11-24),
against `CLAUDE.md`, the owner rulings of 2026-11-24 (route A; a picked channel tunes only; NHL first, NFL and CHL must not
regress and get no new requests), the lead's feed probe (`FACTS.md` and samples in the session scratchpad) and code on
`main` at `d0a5235`. Screen geometry and key handling are the second reviewer's; they appear here only where they change
the playback or data path. Line numbers are the design's (`l.`) unless a path is given.

Verdict: **not ready for commit 0.** The data half (§3, §4, §7) is sound and close to the existing NHL path. The video
half underestimates the controller: `Spec.Clip` is specified in six lines (l. 676–687) against a 3 437-line state machine in
which every branch is "archive or live", and "what to resume" is held in the wrong place. The channel matcher's alias
rule gives wrong channels on three of the seven recorded games. Three blockers, nine should-fix items.

---

## Blockers

### A-B1. `Spec.Clip` has no behaviour defined for the controller branches it will hit
- **Design:** §6.5 "Start" (l. 676–687): "`HlsMediaSource` … the archive buffer profile; no reconnect ladder (two retries
  on IO, then `Error`)"; Appendix B (l. 977).
- **Evidence:** `Spec` is a two-variant sealed interface (`PlaybackControllerImpl.kt:328-331`) and almost every
  behaviour keys on `c.isArchive` (`:456`) or `spec is Spec.Live`. Walking a clip through the code:
  1. **End of clip.** `onPlayerState(STATE_ENDED)` (`:1325-1331`): not archive → `onFailure(c, FailureSignal.LiveEnded)` →
     the ladder reconnects. The design wants "end of queue → `endClip(true)`"; with `isArchive == false` the clip's end is
     treated as a dead live stream.
  2. **Pause.** `pause()` (`:1943-1962`): not archive and no ring → `livePauseJob` ends the session after
     `LIVE_PAUSE_RELEASE_MS` (10 s) and reports `Stopped(LIVE_PAUSE_TIMEOUT)`. `resume()` then runs
     `startCycle(c.specForRestart())` and emits `LiveResumed` (`:1977-1986`), so a clip paused for more than 10 s restarts at
     0:00 with a "back to live" toast.
  3. **Rebuffer.** `onRebuffer` (`:1337-1349`) bumps the buffer FSM for the clip's broker key and emits
     `BufferIncreased`. The toast reaches the Sports page through `player.events` (`AppRoot.kt:348`).
  4. **Errors and stalls.** `onPlayerFailure` → `FailureClassifier` with `isArchive = false, isHls = true`
     (`:1382-1392`), so live-HLS rows apply. Then `onFailure` → `execute` (`:1714-1830`), which can disable tunnelling and
     **persist** it, switch to software decoders, switch the User-Agent, re-resolve (a `Spec.Clip` has no resolver) and fail
     over to a backup channel (`:1824`). `onStall` (`:1401`) feeds the same ladder from `PlaybackWatchdog`. "No reconnect
     ladder" is not a switch the controller has.
  5. **Pipeline steps.** `runSession` (`:808-968`) needs a `ResolvedStream` (`resolveOutcome` `when` at `:1046`), configures
     the broker (`:879`), builds a `StreamSessionContext` with a broker (`:905-916`), and calls
     `beginSession(isLive = !c.isArchive)` (`:953`). `applySwitches` (`:1088-1122`) picks the buffer mode
     (`if (c.isArchive) MEDIUM`), tunnelling from settings and channel memory, and per-channel AFR (`:1115-1116`).
     `startCycle` (`:711`) closes the ring with `discardFile = spec is Spec.Archive`. The design's "discarded (as on any
     zap)" (l. 680) is wrong: a zap **keeps** the file.
  6. **Lifecycle.** Home or standby mid-clip: `stopForLifecycle` → `stop(BACKGROUND)` (resumable). On return,
     `resumeAfterLifecycle` (`:2057-2079`) replays `c.specForRestart()`, which is the clip with the URL it was signed with.
     `manifest_url_ttl` is 3 600 s in every sample, so a return after an hour is a 403 under a clip layer the user had
     forgotten.
  7. **Display mode.** "display-mode switching ignore isClip targets" (l. 687) has no mechanism. `displayMode.onTune` and
     `awaitSettled` run for every session (`:961`), and viewport FULL turns AFR on (`setViewport`, `:2110-2116`). With AFR
     set to Always, a 29.97 fps clip after a 50 fps channel switches the TV's mode twice per clip, with a black screen of
     1–3 s each time.
- **Why it matters:** these are the defects the M2/M4/M6 reviews spent weeks closing for live and archive, and the clip
  path re-opens them in WP-2's largest file. Leaving "the clip branch" to the implementer means each of them gets decided
  in code review.
- **Fix:** add a `Spec.Clip` row to every branch above as a table in §6.5. A recommended set:
  - `Cycle.isVod = isArchive || isClip`, used by ENDED (→ `Playing(paused, ended = true)`), `pause()`/`resume()` (a real
    pause), `onRebuffer` (no FSM, no notice), the classifier facts, `beginSession(isLive = false)` and the buffer profile.
    Keep `NowPlaying.isArchive = false`, so `ArchivePlayback` and `ArchiveVisibility` never take a clip as catch-up.
  - `onFailure` on a clip skips `ladder.decide`: IO or HTTP 5xx → at most two `launchSession(reuseResolved)` restarts at
    the playhead, then `fail()`. No learning is written to channel memory or settings. A 403/410 → `fail()` with a code
    the clip layer maps to "re-resolve" (the expired-URL case).
  - The watchdog runs with the archive expectations. No `setChannelAfr` or `displayMode.onTune`: the clip keeps the
    current display mode, and on resume the live channel gets its own once. Tunnelling is off for clips (short VOD with
    seeks; no device data either way).
  - `startCycle` with a clip calls `closeTimeshift(old, discardFile = true)`, as archive does: the resume is a fresh live
    start anyway.
  - `stopForLifecycle` during a clip ends the clip session (see A-B2) instead of leaving a resumable clip cycle.
  - Every `when (spec)` (`:704`, `:1046`, `:3250`) gets a `Spec.Clip` arm. `specForRestart` keeps the playhead, as archive
    does.
  - `PlaybackControllerClipTest` covers each row: ENDED, pause over 10 s, rebuffer, IO error twice, 403, stall verdict,
    lifecycle stop, AFR untouched, ring discarded.

### A-B2. "What to resume" lives in the controller, which cannot resume catch-up correctly and loses ownership races
- **Design:** l. 595–598 (`playClip` "remembers what was playing"; `endClip(resume)` "re-runs what was playing before the
  first playClip of the session"); §6.5 "Resume" (l. 711–714).
- **Evidence:**
  1. **Catch-up resume bypasses `ArchivePlayback`.** A catch-up playing under Sports belongs to an `ArchivePlayback`
     session. The first state it does not own ends that session: `ArchivePlaybackImpl.kt:709-715` (`"is not ours" → end(s)`),
     and ownership is by identity and `isArchive` (`:736-754`). The clip's `identity = null`, so starting a clip ends the
     catch-up session. Re-running `Spec.Archive(tune)` inside the controller then plays one bare request with no session
     behind it: no continuation when the request runs out, no programme-frame scrub, no variant fallback, no pause phase.
     The controller's `ArchiveTune.startOffsetMs` is an offset into one request, not a programme position.
  2. **Other tunes during a clip.** Only the Sports page knows a clip is playing. Anything else that tunes ends the clip
     cycle: a launcher or Watch Next intent, the PIN prompt's success tune, the lifecycle resume, `LiveSettingsApplier`.
     The page still shows `ClipPlayerLayer`, and Back then calls `endClip(resume = true)`, which re-tunes the remembered
     channel over the one the user just chose.
  3. **Stale memory.** If the view model is cleared mid-clip, nobody calls `endClip`. "The first playClip of the session"
     then never resets, and the next session resumes a stale spec.
  4. **The previous channel breaks.** `startCycle` (`:713-717`) sets `previousIdentity = old.identity` when the identity
     changes. Clip start: previous = the live channel X. Resume X: the old (clip) identity is null, so nothing changes.
     KEYCODE_LAST_CHANNEL now re-tunes X itself, and the real previous channel is lost.
  5. **The guide forgets where the channel was tuned from.** `bindTunedFrom` (`GuideStateMachineImpl.kt:941-946`) clears
     `tunedFrom` when the identity changes. The clip's null identity clears the binding, so after the resume the channel
     no longer belongs to the group it was tuned from.
  6. **The 10-second resolve.** `stream()` runs for up to 10 s before `playClip` (l. 675). The design does not say what
     cancels it: Back, leaving the page, a second OK, a lifecycle stop. A late `playClip` after the user has left Sports
     takes over the player from the guide.
- **Why it matters:** an unwanted re-tune or a broken catch-up is the kind of regression the owner reports first, and the
  arrangement is also hard to test. The controller would hold UI-session state ("the first playClip of the session") that
  only the page can scope.
- **Fix:** move the session out of the controller.
  - `PlaybackController` gets only `playClip(tune): Long` (returns the clip's tune id) and nothing for resume. `ClipSession`
    (WP-5) snapshots what to resume **before** `playClip`:
    - live: the target identity and zap list, from `controller.state` plus the lineup zap list `GuideViewModel` builds;
    - catch-up: from `ArchivePlayback.status` (channel, programme and the programme-frame playhead);
    - nothing / `Error` / `Stopped`: nothing.
  - Resume runs through the public paths. Live: `guarded.tune(Channel(id), zapList)`. Catch-up: the
    `ArchivePlayback.playProgramme` + `seekTo(playheadMs)` route that Search's `PlayArchive` already uses. A refusal opens
    the PIN prompt through the existing `onRefused`.
  - **Ownership by tune id**, the `ArchivePlayback.ownedTuneId` pattern. When `controller.state.target.tuneId` moves past the
    clip's ids and the target is not a clip, the session ends **without** resuming and hides the layer.
    `stopForLifecycle` during a clip ends it the same way, and the lifecycle resume then has nothing to replay. Bring the
    resume back on return only if the owner asks.
  - The resolve job belongs to `ClipSession`'s scope and is cancelled on Back, on leaving the page and on a new OK.
    `playClip` is called only while the session is still current.
  - Keep `previousIdentity` and `tunedFrom` untouched by clip cycles: `startCycle` skips the previous-channel update when
    either side is a clip, and the guide machine ignores an `isClip` Tuned for `bindTunedFrom`.
  - Tests: an intent tune mid-clip (no resume), catch-up resume through `ArchivePlayback` at the playhead, Last channel
    after a clip, Back during the resolve, a lifecycle stop mid-clip.

### A-B3. The alias rule matches the wrong channels on the recorded games, and `LIMIT 40` cuts before the filter
- **Design:** §5.2 A (l. 462–490), §5.3 LIKELY (l. 510).
- **Evidence:**
  1. **`SN+` becomes `sn`.** "tokens split on non-letters/digits" turns the aliases "sn+" and "sportsnet+" into "sn" and
     "sportsnet". `SN+` (streaming only, market N, CA) is the **only** Canadian broadcaster for UTA–WPG, CGY–OTT and
     LAK–MTL in `score-y.json` (games 2026020379, 0380, 0382). Every Sportsnet channel in the playlist becomes "LIKELY:
     Listed broadcaster: Sportsnet (national, Canada)" for games that are not on any Sportsnet channel.
  2. **Subsequence instead of equality.** "every token of an alias appears, in order, as a whole token" matches by
     subsequence. "sportsnet" (SN) is contained in "Sportsnet 360", "Sportsnet One" and "Sportsnet World". "espn" is
     contained in "ESPN 2" (the design itself lists "espn 2" as a channel spelling). "msg" is contained in "MSG Sportsnet"
     and "MSG 2". "tnt" is contained in "UK: TNT Sports 1" (a different network) and "TNT Series". "abc" is contained in
     "AU: ABC Kids". Whole tokens stop "SNL"; they do not stop "Sportsnet 360". MIN–SJS lists both SN and SN360 (different
     games can air on them).
  3. **Country prefixes are discarded.** `TvBroadcast.country` is known, but "CA|" and "US:" prefixes are thrown away
     (l. 465), so "UK: TNT Sports" scores like "US: TNT".
  4. **`LIMIT 40` cuts in playlist order.** `GuideQueries.searchChannels` orders by `p.sort_index, c.sort_index LIMIT ?3`
     (`GuideQueries.kt:221-227`), and the design applies the same order with `LIMIT 40` before the Kotlin whole-token check.
     On a 20 k playlist with a large first playlist, the first 40 "sportsnet" rows can all be filtered out, leaving
     nothing. `ChannelRepository.ftsQuery` (`ChannelRepository.kt:152-156`) adds a prefix `*` to every word; if the new
     query reuses it, "sn*" also matches "snooker" and "SNL".
  5. **The fallback hides the error.** "dropped" (l. 512) protects only when the channel has guide data, but LIKELY is by
     definition the no-guide case. So the wrong answer is the one that reaches the user, as the **primary** live button
     ("Watch on SN Ontario", l. 147).
- **Why it matters:** the channel link is half the feature, and its main failure mode is a confident wrong channel.
  Under max_connections 1 that wrong pick also costs the provider slot.
- **Fix:**
  - **Matching.** Fold the name, drop a leading country tag (`^[A-Z]{2,3}\s*[|:\-]`) and trailing quality and noise
    tokens (HD, FHD, UHD, 4K, HEVC, RAW, H265, 50FPS, "(backup)", …), then require the **residue to equal** an alias
    (optionally plus one region word from a per-code list: Ontario, East, West, Pacific). No subsequence matching.
  - **Country.** A channel with a country tag that differs from `TvBroadcast.country` is dropped; with no tag it is kept
    one level lower.
  - **Streaming codes.** They get **no aliases at all**, including `SN+`, Prime Video, HBO MAX, ESPN+ and the team "+"
    apps. The unknown-code fallback is POSSIBLE only, and only when residue equality holds.
  - **Query.** Exact FTS4 phrases (no `*`), `ORDER BY` nothing, a `LIMIT 200`, then the filter. Or skip FTS and do the
    residue check over one indexed scan of names containing the alias's first token.
  - **Tests.** `BroadcastAliasesTest` gets the negative set above ("Sportsnet 360" for SN, "ESPN 2" for ESPN, "UK: TNT
    Sports 1" for TNT, "MSG Sportsnet" for MSG, every Sportsnet channel for `SN+`), plus `score-y.json`'s three SN+ games
    asserting no LIKELY candidate.

---

## Should fix

### A-S1. Clips bypass `StreamConnectionBroker` and the transport session; use them with their own key
- **Design:** l. 681–683 ("over an OkHttp data source **not** through the broker").
- **Evidence:** `CLAUDE.md`: "All upstream stream connections go through `StreamConnectionBroker`". Today HLS already
  runs through `transport.http(ctx)` (`MediaSourceBuilder.kt:101-127`), whose `TransportSession` takes one broker lease
  (`TransportFactoryImpl.kt:153-170`). `TuneSession.end()` → `TransportSession.end()` cancels every open call. A separate
  OkHttp data source gets none of that: nothing cancels a segment download in flight when the user zaps away or Back
  ends the clip.
- **Fix:** keep the existing transport and give clips a broker key of their own, e.g. `clip:<host>`, configured with a
  generous `maxConnections`. It is a separate key, so it never touches the provider's slot. Telemetry, cancellation and
  the session placeholder rules all carry over. If the lead still prefers a bypass, record it as a rule exception in
  `docs/decisions/` with the reason.

### A-S2. AppRoot: the clip flips `beneath` to FULL, shows the live picture before the clip, has no captions and needs MainActivity
- **Design:** §6.5 "Where it plays" (l. 668–673); WP-5 owns `AppRoot.kt` (l. 913).
- **Evidence:**
  1. **`beneath` flips to FULL.** `AppRoot.kt:278-282`: `beneath[0]` is overwritten whenever the hole is not COVERED. A
     SPORTS + clip → FULL hole sets `beneath[0] = FULL`. After the clip the page is COVERED again, and the resumed live
     channel gets `Viewport.FULL` under an opaque page (`viewportFor(hole, screenOff, beneath)`, `:105-106`), not the MINI
     or HIDDEN it had. Video decoding stays on, and AFR evaluates for a channel nobody sees: a display-mode switch blanks
     the Sports page.
  2. **No scrim.** The scrim sequence runs only on GUIDE ↔ FULL (`:244`). SPORTS → clip is not a mode change. If
     `clipActive` turns true before the clip's first frame, the surface shows the live channel full screen, or its last
     frame (`endSession` "keeps the last frame", `PlaybackControllerImpl.kt:1130-1143`). The design does not say when
     `clipActive` turns true.
  3. **No captions.** `SubtitleLayer` is composed only for `shown == ScreenMode.FULL` (`AppRoot.kt:379-386`), so captions
     never draw during a clip in SPORTS, whatever the preference.
  4. **Wiring.** `holeModeFor(mode, tuned)` (`:84-89`) cannot see the Sports view model. `clipActive` has to come through
     an `AppRoot` parameter or `ShellHost`, and `MainActivity` builds both (`MainActivity.kt:109-119`), but `MainActivity`
     is WP-3's file.
  5. **Back.** In SPORTS the router sends BACK to the machine unless the page holds it (`onBackFreeChanged`,
     `SportsScreen.kt:101-104`). If the clip layer does not hold it, `onSportsClosed()` runs. The guide then shows the clip
     in the mini player at viewport MINI, and the clip session never ends.
- **Fix:**
  - Do not update `beneath` while the FULL hole comes from a clip.
  - Draw the hole black (scrim 1) from `playClip` until `renderedFirstFrames` moves or `STILL_TO_FULL_MAX_WAIT_MS`
    passes, and turn `clipActive` true only after `playClip` returns.
  - Compose `SubtitleLayer` for SPORTS + clip too.
  - Commit 0 adds the `AppRoot(sportsClip: State<Boolean>)` parameter and its `MainActivity` line.
  - `ClipPlayerLayer` sets `onBackFreeChanged(false)` for its whole life.
  - Extend `RootTreeRuleTest` with the clip state (no layer, clip or alpha on the surface's ancestors).

### A-S3. Side-loaded captions are ignored by `HlsMediaSource`, and the manifest may already carry them
- **Design:** l. 683–685.
- **Evidence:** `MediaSourceBuilder` builds `HlsMediaSource.Factory(factory).createMediaSource(item)` directly
  (`MediaSourceBuilder.kt:121-125`). Media3 merges `MediaItem.SubtitleConfiguration`s only in `DefaultMediaSourceFactory`.
  A configuration set on the item here is dropped without a word. Brightcove HLS v4 manifests usually list the WebVTT
  track as an `EXT-X-MEDIA TYPE=SUBTITLES` rendition already. The samples show `text_tracks`, but nobody has checked the
  manifest.
- **Fix:** WP-2 checks a recorded master playlist. If the captions are in it, use them, select them through the existing
  `applyTuneDefaults(…, subtitlesEnabled)`, and side-load nothing. If they are not, wrap the clip source in
  `MergingMediaSource(hls, SingleSampleMediaSource(vtt))` in the clip branch. The MP4 fallback always needs the merge.

### A-S4. Key handling: first-match scan, redaction rules aimed at the wrong form, plaintext file
- **Design:** §6.2 (l. 603–617), §8.4 (l. 819–823).
- **Evidence and fix:**
  1. **First match.** "stop reading at the match … anything else = not found". Bundled player scripts often contain a
     default options object (`policyKey:""` or a placeholder) before the account's configuration. The scan should skip
     an invalid candidate and **continue** to the first valid one (still under the 2 MB cap). Accept
     `policyKey\s*:\s*["']` and `"policyKey"\s*:\s*"` as well. The cap must count **decoded** bytes: OkHttp's transparent
     gzip does that only when the limit wraps `body.source()`. `okio`'s `BufferedSource.indexOf(ByteString, from, to)`
     on a source limited to 2 MB does the window and overlap handling for you.
  2. **Redaction.** `Redact.text` already strips every query string (`QUERY`, `Redact.kt:52`), so new rules for
     `boltdns.net` / `brightcovecdn.com` query strings add nothing. The `pk=` value travels in an `Accept` header, which
     `HEADER` (`:53`) does not match, and the real exposure is throwables: `BoxLog.emit` redacts the message but passes `t`
     raw to the sink (`BoxLog.kt:23`), and HLS puts the real URL into `MediaItem` (`MediaSourceBuilder.kt:104`). Fix:
     `Redact.registerSecret(key)` the moment the key is read (masked anywhere, any form), and
     `Redact.registerTransient(fastly_token)` for each `ClipStream` URL and caption URL (the existing per-URL token
     mechanism, `Redact.kt:29`).
  3. **Plaintext file.** `noBackupFilesDir/sports/video_key` in plaintext sits beside a rule that says credentials use Tink
     + Keystore. The key is public, but the design itself treats it as a secret. Simpler: keep it in memory only. The cost
     is one 305 KB read per process, on the first Play or first `info()`. That also drops the export exclusion in §8.4.
     Otherwise store it in the existing credential store.
  4. **Hold.** The 10-minute hold (`HUB_VIDEO_KEY_REREAD_MS`) also applies to "not found" and to script fetch failures,
     not only to `Refused`, so a changed script is read at most once per 10 minutes per process.

### A-S5. Matcher cache key names a counter that does not exist and leaves out parental access
- **Design:** §5.1 (l. 453–454), §5.7 (l. 556–557).
- **Evidence:** no "library change counter" exists (`rowsVersion` lives in guide snapshots, `GuideQueries.versionOf` is per
  source). The key `(game id, counter, EPG generation)` also omits `ParentalAccess`. A result computed while the PIN
  session is unlocked contains locked channels (l. 522–524), and the page keeps showing it after a relock. `channel_prefs`
  changes (hide, lock, rename) would not invalidate it either.
- **Fix:** key on `(game id, ParentalAccess, EPG generation)` and drop the cache on any invalidation of `channel`,
  `channel_prefs`, `playlist` or the group tables (Room `InvalidationTracker`), or simply on every page open.

### A-S6. The work-package split has four overlaps, and commit 0 misses two types
- **Design:** §10 (l. 896–919).
- **Evidence and fix:**
  1. **`SportsEffect`.** It is consumed by WP-3 (`GuideViewModel.onSportsEffect`, `MainActivity` wiring) and produced by
     WP-4's view model, but commit 0 does not list it. Add `SportsEffect` (Tune, PlayArchive) to commit 0.
  2. **`GuideEvent.SportsClosed`.** It does not exist: `GuideStateContracts.kt:148` has `SearchClosed` only, and Sports
     closes through `BoxAction.BACK` → `onSportsClosed()` (`GuideStateMachineImpl.kt:500`, `:971-976`). Commit 0 **adds**
     it (not "gains fields"), and §5.5 says that the BACK path stays.
  3. **The NHL transport.** `NhlDetailSource` (WP-1) needs the transport in `NhlScoreSource.kt`, whose `call`, `classify`,
     `readCapped` and `retryAfterS` are private (`NhlScoreSource.kt:130-244`). That file is WP-2's (for `SportsEndpoints`).
     Commit 0 moves `SportsEndpoints` to its own file (WP-2) and extracts the transport into an internal `NhlHttp` that
     WP-1 owns, or WP-1 owns `NhlScoreSource.kt`.
  4. **`MainActivity.kt`.** Needed by WP-3 (effects) and WP-5 (`clipActive`, A-S2); give WP-5's line to commit 0.
  5. **Fake panel.** `fakepanel.py` (route table at `:1410-1415`, config `DEFAULTS` at `:79-84`) is listed for WP-2 but is
     also needed by WP-3's catalog switches; NFL broadcasts in the fake ESPN board are in `sports_nfl.py`, which nobody
     owns. Assign both.
  6. **Guarded controller.** It is `PlaybackController by inner` (`ParentalGuideLibrary.kt:81-91`), so new members pass
     through by delegation. Remove "the guarded controller's pass-through" from WP-2: it names a `:ui` file and needs no
     change.

### A-S7. Detail repository: stale data looks live, and the score repository keeps polling under a clip
- **Design:** §4.3 (l. 383–403), §4.5 (l. 413–421).
- **Evidence and fix:**
  1. **Stale LRU entries.** A hit "is shown at once" (l. 401). A LIVE detail cached 20 minutes ago (the user left the card
     and came back) shows old stats with no mark until the refetch lands. Mark a `Ready` older than 2 × the phase's poll
     interval as stale ("Stats from 8:12 PM"), the same way the failing case does.
  2. **Two sources for the score.** The headline uses the board snapshot (30 s day poll) and the periods and goals use the
     detail (another 30 s cycle). Right after a goal they disagree. Take the panel's score from whichever is newer by
     server `Date` (both are `Fetch.Ok`).
  3. **Demand under a clip.** `SportsViewModel.onShown` reports `DemandOwner.SPORTS_TAB` (`SportsViewModel.kt:143-148`)
     until the page closes. A clip plays inside SPORTS, so the day list (30 s) and a followed game (SEEN, 6–30 s) keep
     polling through a 10-minute condensed game. The design only stops the detail. Report `SPORTS_TAB` off while
     `clipActive`.
  4. **Failure texts.** The text is "NHL.com did not answer" for every `FailKind`. `BAD_BODY` ("NHL.com changed its stats
     format; scores still update") and `HTTP_429` / `REFUSED` need their own reason and next step, as the score bug's
     texts already do.

### A-S8. Guide-title probe: limits cut duplicates, and titles that name no teams are invisible
- **Design:** §5.2 B (l. 493–503), §5.3 variant collapse (l. 515–516).
- **Evidence:**
  - **The per-text cap.** `EpgReaderImpl.search` caps airings per text (`perText`; `EpgReaderImpl.kt:114-139`). One
    "NHL Hockey: Golden Knights at Oilers" text on 4 Sportsnet regionals × HD/FHD/4K × 2 playlists is 24 airings, and
    `perText 10` keeps an arbitrary 10. The "Also: FHD, 4K" collapse and the two-playlist rows then show only part of the
    lineup.
  - **Columns indexed.** `epg_fts` indexes `title, subtitle` only (`FtsSupport.kt:31-34`). Many provider EPGs title the
    airing "NHL Hockey" or "Hockey Night in Canada" and name the teams only in the description, so B misses them and A's
    fallback (A-B3) decides.
- **Fix:**
  - Raise `perText` to `HUB_MATCH_MAX × variants` (for example 40) for this caller.
  - Add a third query: the sport words ("NHL", "Hockey", "LNH") in the window on channels A matched. An airing whose
    title has a sport word but names no teams **confirms the network, not the game**: raise LIKELY to "In your guide: NHL
    Hockey at 7:00 PM", never to CONFIRMED.
  - Record the measured hit rate against the fake panel's EPG and one real XMLTV in the WP-3 notes.

### A-S9. Tests and fake panel miss the clip failure paths
- **Design:** §9.2–§9.4.
- **Fix:** add the following.
  - `sports_video.py` mode `"expire"`: the manifest answers, then segments 403 after N s, which exercises "Try again
    re-resolves" (l. 741).
  - `PlaybackControllerClipTest` rows from A-B1.
  - `ClipSessionTest` rows from A-B2: an intent tune, a lifecycle stop, Back during the resolve, catch-up resume.
  - An AppRoot test for `beneath` after a clip (A-S2).
  - A `GameChannelMatcherTest` negative set (A-B3) and an access-change invalidation case (A-S5).
  - The live right-rail and landing fixtures are synthetic until WP-1 records a real live game. Gate WP-1's live-phase
    mapper tests on that recording before release, not on the fake panel's shape.

---

## Notes

- **A-N1. Bounded reading.** "Parsing follows `NhlScoreSource`" (l. 378–381) is accurate. The source reads the capped body
  into memory and calls `parseToJsonElement` (`NhlScoreSource.kt:171-175`, `:150-161`); it is bounded, not streaming. The
  streaming rule names M3U, XMLTV and Xtream JSON, and a 21 KB body under a 512 KB cap meets its intent. Fine as written.
- **A-N2. `channel_fts` is FTS4 only.** It is a Room `@Fts4` entity (`ChannelFtsEntity.kt:15`). "FTS5 phrase syntax;
  FTS4 fallback" applies to `epg_fts` (`FtsSupport.kt`), not to channels. Write the channel query for FTS4's standard
  syntax: quoted phrases, upper-case `OR`, no parentheses.
- **A-N3. Coil settings contradict each other.** `allowHardware(true)` and "RGB_565" (l. 633–634) cannot both hold, since
  hardware bitmaps are 8888-class. At 664 × 250 × 4 the poster takes about 650 KB of the 1 MB low-end cache
  (`ImageModule.kt:35-42`). Low-end already falls back to the thumbnail (l. 810), so set `bitmapConfig(RGB_565)` +
  `allowHardware(false)` for the poster on low-end and keep hardware on the others. The custom cache keys are right:
  the signed URLs change per lookup.
- **A-N4. Contract claims that hold.** `GameSnapshot` / `DayBoard` are not `@Serializable` (`SportsContracts.kt:104-139`,
  `:217-227`); the persisted form is `FollowedGame` (`:306-321`), so backups and `sports_follow` are untouched. The
  appended defaulted fields compile for the CHL and ESPN mappers. `EspnDto` reads leaves as `JsonElement?` with
  `ignoreUnknownKeys` (`EspnDto.kt:9-16`, `EspnScoreSource.kt:667`), so reading `broadcasts` cannot fail an NFL board if
  it stays a `JsonElement?` read by path. Keep it that way. The score bug draws only in FULL, so it never sits over a
  clip in SPORTS (true by `AppRoot.kt:401`).
- **A-N5. Kotlin cannot re-export a type.** "`ClipFormat` re-export" (l. 899) means either a `typealias` or using the
  `:core` type directly (`:player` already depends on `:core`). `NowPlaying.isClip` defaulted false is fine. Consumers
  already handle `identity == null` for `DirectUrl`. Pin `TunedStateMapper` and `parentalControls.watching` with an
  `isClip` case.
- **A-N6. Headers and cleartext.** Send no `Origin` or `Referer` to the playback service (the lead's probe worked without
  them; spoofing a browser would change the risk the owner accepted in §0.3). The source order (https HLS, https MP4, then
  http) is right. The key script and the lookup must be https only, never an http fallback: they carry the key.
- **A-N7. Missing Canadian regional codes.** The samples show no TSN or RDS codes for NHL games. Real Canadian regional
  rights (TSN3/TSN5/RDS for WPG, OTT, MTL) are absent from the feed, so those games depend on guide titles. Seed
  `TSN1`–`TSN5`, `RDS`, `RDS2` aliases anyway, POSSIBLE at most, so that one appearing in the feed is handled.
- **A-N8. Cost estimate.** The cost estimate in §5.7 is plausible, and measuring p95 on the Fire TV Stick is the right
  gate. Add the A-B3 query shape to the measurement, since the `ORDER BY` removal and `LIMIT 200` change the plan.
  `searchPast` needs `pastFromMin = now − the channel's archive span` per candidate, not one global span. Use the largest
  `archiveDays` among A's candidates, then filter per channel.
- **A-N9. Time zones.** The game window uses `startTimeUTC` against epoch-minute airings, which is correct. The only
  shift is the channel's `epg_shift_min`, as l. 498 says. Nothing in the matcher should use the panel's or the device's
  zone. That rule applies to Xtream catch-up tokens, and §5.6 hands off to the existing `playArchive` path, which already
  follows it.
- **A-N10. Provider slot.** With max_connections 1, the clip frees the slot (the old session's `TransportSession.end()`).
  The resume re-acquires it through the normal `PREEMPT_RELEASE_WAIT_MS` path. Panels that count a connection for a few
  seconds after close are covered by the existing ladder on the resumed live tune. No change needed.

---

## Re-review (2026-11-24)

Subject: the revised `docs/design/m6-sports-hub.md` (1 267 lines, reconciliation in §12), against code on `main` at
`d0a5235`. Scope: the findings above, plus what the revision introduced in the clip path, the matcher and the work
packages. Line numbers are the revised design's unless a path is given.

### Status of the first-round findings

| Finding | Status | Evidence |
|---|---|---|
| A-B1 `Spec.Clip` branches | **Closed** | The per-branch table (§6.5, l. 801–823) covers ENDED, pause, rebuffer, classifier, ladder, watchdog, lifecycle, AFR, tunnelling, ring discard, `specForRestart` and the `when` arms, against the right code lines (`PlaybackControllerImpl.kt:704-717, :879, :905-916, :953, :1088-1122, :1325-1331, :1337-1349, :1714-1830, :1943-1986, :2057-2079, :2110-2116`). A local `ResolvedStream` is constructible: every field exists in `PlaybackContracts.kt:82-107`. `PlaybackControllerClipTest` lists each row. |
| A-B2 resume ownership | **Partly closed** | `ClipSession` (§6.6) does the snapshot, tune-id ownership, cancellation of the resolve, the guarded / `ArchivePlayback` resume and keeps `previousIdentity`, all as asked. The departure (dropping clip states in the two view models) opens R-S1. Small gaps: R-N1, R-N3. |
| A-B3 alias matching | **Partly closed** | Residue equality, the country check, no streaming aliases, FTS4 phrases, `LIMIT 200` without `ORDER BY` and the negative tests (l. 535–573, l. 1035–1036) close what was raised. Two new defects in the same rule: R-S2 (the country-tag regex) and R-S3 (affiliates can never match). |
| A-S1 broker | **Closed** | `clip:<host>` through `transport.http` and the session lease (table rows at l. 807–809). `StreamBrokerRegistry.configure` clamps per key (`StreamBrokerRegistry.kt:20-23`). `CLAUDE.md` stays unchanged (§11.1). |
| A-S2 AppRoot | **Closed** (one commit-0 gap, R-S5) | `beneath` frozen, the scrim to first frame, `SubtitleLayer` in SPORTS + clip, Back held, `RootTreeRuleTest` and `AppRootClipTest` (l. 786–799, l. 1042). The `ClipUi` type the new parameter uses is defined nowhere (R-S5). |
| A-S3 captions | **Closed** | Manifest check first, then `MergingMediaSource`; MP4 always merges (l. 831–834); the master playlist is recorded in lead task 1 (l. 1092). |
| A-S4 key | **Closed** | Continues past invalid candidates, quote and spacing variants, decoded 2 MB cap, `registerSecret` and `registerTransient` (both exist, `Redact.kt:26, :29`), memory only, a hold after every failure, https only (l. 710–739). `KeyScanTest` and `NhlClipResolverTest` cover them. |
| A-S5 matcher cache | **Closed** | Key `(game, ParentalAccess, EPG generation)`, with `clear()` on page open and on any access change (l. 615–616, `clear()` in the contract l. 529). |
| A-S6 WP split | **Partly closed** | `SportsEffect`, `SportsClosed` (correctly "adds"), the `NhlHttp` / `SportsEndpoints` extraction, `MainActivity` and `fakepanel.py` in commit 0, `sports_nfl.py` to WP-1, the guarded pass-through removed (l. 1101–1130). Commit 0 still does not compile alone: R-S5. |
| A-S7 detail repository | **Closed** (one wrong sentence, R-N2) | `stale` in `DetailState.Ready`, the newest-`Date` score rule with `serverDateMs` in `GameDetail`, `pause()`, `SPORTS_TAB` off during a clip, and per-`FailKind` texts (l. 403–412, l. 463–466, l. 474–476, l. 478–491). |
| A-S8 guide probe | **Partly closed** | `perText` 40 closes the duplicate cut. The new probe C, run through FTS, misses for the reason in R-S4. |
| A-S9 tests | **Closed** (one fixture mismatch, R-N4) | "expire" mode, `ClipSessionTest`, `AppRootClipTest`, `GuideViewModelClipFilterTest`, the negative matcher set, and the live gate on a recording (l. 1021, l. 1032–1042, l. 1056–1057). |
| A-N2, A-N3, A-N5, A-N6, A-N7 | Closed | §5.2, §6.3, §6.1, §6.2–§6.3, §5.2 table |
| A-N8 | **Closed, with the variant accepted** | `GuideQueries.archiveDaysAll` exists (`GuideQueries.kt:90`) and Search already uses `min(PAST_DAYS, archiveDaysAll)` (`GuideSearchImpl.kt:102`). One global span, then the per-channel cut, gives the same rows as "largest among A's candidates" and does not depend on A running first. |
| A-N1, A-N4, A-N9, A-N10 | Unchanged, still true | — |

### New findings

#### R-S1. Dropping clip states in the view models leaves the guide and player believing the pre-clip channel still plays
- **Design:** l. 825–829; §12 row A-B2 (the departure); WP-3 owns the filter.
- **Evidence:**
  - The filter drops **every** state whose target `isClip`, including the terminal ones.
  - The guide machine therefore keeps the last pre-clip `TunedState`, e.g. PLAYING on channel X, while the controller's
    cycle is the clip.
  - This is harmless while the clip plays. It goes wrong when the clip ends in anything other than a resume:
    1. **Home or standby mid-clip.** The clip cycle stops not resumable (l. 819) and `ClipSession` ends without resuming
       (l. 870–871). That `Stopped` is dropped. On return the mini player still says PLAYING X. OK on X in the grid takes
       `tunedLive` (`GuideStateMachineImpl.kt:1905-1906`, based on the stale `tuned`), which goes FULL **without tuning**:
       a black full screen. Before this design, Home on live X resumed X on return (`resumeAfterLifecycle`,
       `PlaybackControllerImpl.kt:2057-2079`). Now nothing plays, and the guide says otherwise.
    2. **`ResumePoint.Nothing` after an error card.** The guide shows ERROR on X (the pre-clip state). The clip ends with
       `controller.stop(USER)` (l. 879), and that state is dropped. The guide's Retry calls `controller.retry()`, which
       restarts `cycle`, now the **clip** cycle (`PlaybackControllerImpl.kt:668-674`). The clip replays invisibly behind
       the guide, on a URL that may have expired.
  - The PlayerViewModel filter has the same effect in FULL.
  - Separately, the recents and watch counts the design says the filter covers (l. 827–828) are recorded inside the
    controller (`recordWatchedJob`, identity-based, `PlaybackControllerImpl.kt:1466`), not in either view model. The
    clip's null identity already excludes them, so the claim is wrong, but nothing breaks.
- **Fix:**
  - Drop only **active** clip states (Connecting, Buffering, Playing, Stalled whose target `isClip`).
  - Forward a terminal clip state (Stopped, Error, Idle after a clip) as `PlaybackState.Idle` to `TunedStateMapper` and
    to the `:664` collector, so the machines see "nothing tuned", which is the truth.
  - After any clip, `controller.retry()` and `resume()` must not restart a clip cycle on their own. Either the clip
    branch of `retry()` / `resume()` is a no-op unless `ClipSession` calls it (it re-resolves anyway), or `ClipSession`
    owns Try again.
  - Add these cases to `GuideViewModelClipFilterTest`: Home mid-clip, `Nothing` after an error card, and Retry after a
    clip.
  - This needs no change to commit 0; it must be in WP-3's spec before WP-3 starts.

#### R-S2. The country-tag regex removes network names, and "SN" is a country code
- **Design:** l. 537 (`^[A-Z]{2,3}\s*[|:\-]` and `[XX]` / `(XX)`), l. 544 (a tag that names another country drops the
  channel).
- **Evidence:** with no list of real tags, any two- or three-letter network name followed by a separator is read as a
  country:
  - "SN - Ontario", "TSN: 3", "CBC - Toronto", "ABC | 7", "TNT: HD", "MSG - HD", "RDS: 2", "CTV | Toronto": the network
    is stripped, so the residue is the region or nothing, and the true match is lost.
  - "SN" is also ISO-3166 Senegal. Read as a country, it differs from `TvBroadcast.country = CA`, so the channel is
    **dropped**, not just missed. The same applies to "CA|"-style tags that are really networks in other playlists.
- **Fix:**
  - Accept a tag only from an explicit list: ISO-2 codes plus the provider spellings `UK`, `USA`, `LAT`, `EU`, `ARB`,
    and so on, seeded from the fake catalog and one real playlist.
  - Never accept as a tag a token that is an alias of any code in `BroadcastAliases`.
  - Add "SN - Ontario", "TSN: 3" and "CBC - Toronto" as positives in `BroadcastAliasesTest`.

#### R-S3. Exact equality makes the affiliate and regional rows of the table unreachable
- **Design:** l. 556 (CBC regionals POSSIBLE), l. 559 (ABC affiliates POSSIBLE), l. 566 (CBS, FOX, NBC, CTV affiliates
  POSSIBLE); the fake catalog's "US: ABC 7 NEW YORK" (l. 1059).
- **Evidence:** affiliates are named by channel number, call sign or city: "ABC 7 New York", "FOX 5 WNYW", "CBS 2",
  "CBC Toronto". The residue ("abc 7 new york") never equals "abc", and only Sportsnet has a region-word list (l. 542).
  The table promises POSSIBLE matches that the rule can never produce. For the NFL that is the main case: Sunday games
  air on CBS and FOX affiliates.
- **Fix:** choose one.
  - **Allow affiliates:** a defined affiliate form, alias + (a number ≤ 3 digits | a 4-letter call sign starting with K
    or W | one city word from a short list), always POSSIBLE with the "May carry this game" wording, behind the same
    country check.
  - **Or state they are guide-only:** delete those rows from the table and say that affiliates are matched only by
    probes B and C.
  - Either way, `GameChannelMatcherTest` asserts the fixture's "US: ABC 7 NEW YORK" one way or the other.

#### R-S4. Probe C via FTS with start-order limits misses the game's window
- **Design:** §5.2 C (l. 588–591), B's limits (l. 576–577).
- **Evidence:**
  - `EpgReader.search` returns texts best-first, then on-now and upcoming airings **in start order**, capped per text
    (`EpgReaderImpl.kt:114-139`).
  - Sport words are generic ("NHL", "Hockey"). "NHL Hockey", "NHL Tonight", "Hockey Central" and their airings across
    every channel fill `limit 80` / `perText 40` with the next airings from now, and the window filter runs only after
    that. For a game later tonight on a busy guide, or a day-strip game days ahead, the window's airing is past the cap
    and C finds nothing.
  - It is also the most expensive of the five queries on a 1 M-programme database.
- **Fix:** do not search for C. Read the window directly for A's candidate channels with the existing
  `EpgReader.window(channels, shiftMin, fromMin, toMin, out)` (`EpgContracts.kt:253`); at most 8 × variants channels,
  one call. Apply the sport-word and team-name tests in Kotlin. The same read can also stand in for B on A's channels;
  B's FTS is still needed for channels A did not find. Measure it in the WP-3 benchmark.

#### R-S5. Commit 0 does not compile alone as listed
- **Design:** §11 commit 0 (l. 1101–1115), l. 704, l. 788, l. 1109–1110.
- **Evidence:**
  1. **`CLIP_EXPIRED` is listed under the wrong file and breaks `:ui`.** It is listed under `PlaybackApi.kt`, but
     `PlaybackErrorCode` is in `:core` `PlaybackContracts.kt:133`. Adding a constant breaks `:ui`'s compile, because
     `ErrorText.headline` is an exhaustive `when` with no `else` (`ErrorText.kt:20-46`). `ErrorTextTest` (distinct
     headline and step per code), `PlayerOverlayBoundsTest` and `PlayerFiveKeyReachabilityTest` iterate
     `PlaybackErrorCode.entries`, so the code also needs `defaultSteps`, two strings and an error-card layout.
  2. **`ClipUi` is defined nowhere.** `AppRoot(sportsClip: State<ClipUi>)` needs a type that is in no contract file.
  3. **MainActivity's two lines need members commit 0 does not add.** `sportsVm.clipUi` and `sportsVm.effects` are in
     `SportsViewModel.kt` (WP-4), and `host.onSportsEffect` is in `GuideViewModel.kt` (WP-3). Commit 0 must add stub
     members to both existing files, but they are not on its list.
- **Fix:** add to commit 0:
  - `PlaybackContracts.kt` (`CLIP_EXPIRED` with `defaultSteps`), the `ErrorText` arm and its strings;
  - `ClipUi` (in `SportsEffect.kt` or its own frozen file);
  - stub members in `SportsViewModel.kt` (`clipUi`, `effects`) and `GuideViewModel.kt` (`onSportsEffect`, a no-op).

  Then each package builds on commit 0 alone. Every other file in the §11 table has one owner after commit 0. I checked
  the table row by row: `AppRoot.kt`, `SportsViewModel.kt` and `GuideViewModel.kt` are touched by commit 0 and then by
  one package each, in sequence, which is fine.

#### R-N1. An effect emitted from `onCleared` is not delivered
`onCleared` "ends the session with resume" (l. 882) through `SportsEffect.Resume`, but `MainActivity`'s collector runs
in `lifecycleScope`, which is gone when the activity-scoped view model is cleared. It is harmless, since the activity's
stop has already stopped the player (`BACKGROUND`). Say "ends without resuming" so the test asserts the real behaviour.

#### R-N2. §4.4's demand sentence is wrong
"A followed game keeps polling only if `FULL_WITH_FOLLOW` is on" (l. 475–476) is wrong. With no owner, the follow drops
to `UNSEEN` and keeps polling at the `POLL_UNSEEN_MS` floor (`ScoreRepositoryImpl.kt:480-486`,
`ScoreBugMachine.kt:1248`). That is today's behaviour everywhere outside FULL and Sports, and it is acceptable. Correct
the text.

#### R-N3. The catch-up snapshot
- **`rawEndMin`.** `ArchiveStatus` has no `rawEndMin`. Derive it from the `ProgrammeInfo` (`rawStartMin` plus
  `endMin − startMin`, `GuideModel.kt:280-290`).
- **`timeShift`.** Carry `status.timeShift`: a session that started from live pause or rewind would otherwise come back
  with the CATCH-UP pill instead of −MM:SS.
- **Playhead.** Re-read `status.atMs` just before `playClip`, not before the resolve. The resolve can take 10 s while
  the catch-up keeps playing, so the resume would land up to 10 s early.
- **Seek.** `ArchivePlayback.seekTo` issued before the new session's first media is planned without a position
  (`ArchivePlaybackImpl.kt:458-469`). WP-3 confirms that path lands on `playheadMs`, or seeks on the first `PLAYING`
  status.

#### R-N4. Emulator check 9 expects a match the rule cannot make
Check 9 (l. 1078) expects "Sportsnet 360 LIKELY (sport listing)". C only looks at channels A matched, and the fake
featured game VGK @ EDM lists TNT, HBO MAX, SN and TVAS, not SN360 (`score-now.json`). Either the fake board lists SN360
for that game, or the expectation moves to a channel A does match.

### Verdict

No new blockers. **Commit 0 can start once its list takes in R-S5**, which concerns commit 0's own compile:
`CLIP_EXPIRED` in `PlaybackContracts.kt` with the `ErrorText` arm, `ClipUi`, and the `SportsViewModel` /
`GuideViewModel` stub members. R-S1 to R-S4 are WP-3 spec fixes. They change no frozen contract (`MatchReason` and
`ResumePoint` are already sufficient) and must be in the design before WP-3 starts.
