# NFL hub design review: architecture and data (2026-11-25)

Reviewer: architecture / data (Opus). Design: `docs/design/m6-nfl-hub.md` (proposed 2026-11-25), against `main` `c333d3c`
and the probe samples in the session scratchpad `nfl/` (paths below are relative to it). Owner ruling (in-app ESPN clip
playback) taken as given.

**Verdict: approve with changes.** The architecture is sound: one summary request per shown game, a streaming decode
into a typed DTO, a league router for clip resolvers, and the clip on the one player under `clip:<host>`. The
`ScoringEvent` / `StatValue` generalisation compiles against the NHL code as written. Eight design gaps have to be
closed before the work packages start. Each one either has no input path in today's code (the nudge, the expiry
re-publish, the expired-link re-resolve), contradicts a rule the design states itself (browsing makes link lookups; two
parses at once), or regresses something that works today (redaction, the ESPN 403 rule). The work-package split also
does not compile package by package yet.

Counts: Blocker 0 · Major 8 · Minor 14.

---

## Major

**M1. The board-score nudge and the halftime hold have no input path into the repository.** (§5.2, §6.3)
- Evidence: `SportsViewModel.kt:823-831` (`followPhase`) calls `detail.show(game)` **only when the phase changes**.
  `GameDetailRepositoryImpl.kt:296-299` (`applyShow`) returns early for the same id without looking at the snapshot.
  The repository never sees a board score change. It also never sees an `INTERMISSION` that turns from END_PERIOD into
  HALFTIME, because the phase stays the same. In addition, `force` (`:400`) bypasses the policy, so a nudge built on
  `force` would ignore back-off.
- Failure: as written, N1 builds a nudge that never fires. If N1 wires it through `force` instead, a score during a
  429 back-off fetches anyway.
- Change: either (a) **cut the nudge for v1** (60 s, plus the board's own 30 s score, is enough; the lag is NV anyway),
  or (b) add it to the commit-0 contract: `GameDetailRepository.show()` is called on every board snapshot of the shown
  game (the VM already has them), and `applyShow` records `(scoreKey, breakKind)`. The policy takes `nudgeAt: Long?`
  as a floor-respecting candidate, and back-off still wins (`failures > 0` returns the back-off instant first). The
  halftime hold needs the same snapshot, so either way it falls under (b) or is cut with (a).

**M2. `info()` conflates metadata and the link lookup, so browsing a live ESPN game makes record lookups.** (§4.2,
§4.4, §2.1 media)
- Evidence: §4.2 says `info()` on a PLAY entry with unknown `playIds` "makes the one record lookup". `GameClip` (§6.1)
  carries no image URL, so the live panel's "latest play clip's still" can only get its thumbnail from `ClipInfo`, which
  means calling `info()` from the panel path. `ClipSession.kt:378` also calls `info()` for "Up next". The VM retries
  every non-NotFound failure on the next panel rebuild (`SportsViewModel.kt:899`, `infoRequested.remove`), and rebuilds
  happen on every flow emission (`:301-307`). §4.4 says both that "the view model asks … two at a time" and that the
  resolver test covers "budget 16, concurrency 2", so the location of the budget is ambiguous.
- Failure: this contradicts "Browsing never makes a link lookup". An IO or timeout on a record lookup is re-requested
  on every rebuild. N2 and N3 could both implement the budget, or neither.
- Change: split the call. `info()` is catalog-only for ESPN and never makes a request on a catalog hit. Add
  `GameClip.thumbUrl` (image URLs are already in `GameDetail` through `RecapStory.imageUrl`). Linking becomes a
  separate `EspnClipResolver.links(gameId, ids)`, which the scoring sheet calls through the router or a narrow
  `ClipLinker` seam frozen in commit 0. Budget, concurrency (one `Semaphore(2)`), and the 60 s per-game stop live
  **in the resolver only**. The budget counts **attempts**, not successes, and the per-game counters sit in an LRU
  bounded like `HUB_DETAIL_LRU`. Better still, see "Cut or defer" #1.

**M3. An expired-link re-resolve replays the same dead URL; the proposed "fresh summary read" is an unowned second
parse path.** (§3.4, §4.2)
- Evidence: `ClipSession.kt:358-362` re-resolves `CLIP_EXPIRED` by calling `r.stream(ref)` again. The NHL resolver
  always looks up (`NhlClipResolver.kt:77-78`); ESPN's `stream()` serves the catalog URL. `ClipResolver.stream` has no
  way to say "this is the re-resolve". The design's "catalog refresh from a fresh summary read" would come from
  `:data`'s resolver, outside the detail actor, its back-off, its gate (paused while a clip plays) and its gen check.
  `HubContracts.kt:244` documents `ClipStream` as "never cached", which the catalog now does.
- Failure: the user gets one wasted master request, then the error card, and Try again loops on the same 404. A
  summary-based refresh would run a 600 KB parse next to (or despite) the paused detail loop.
- Change: in commit 0, add `stream(ref, fresh: Boolean = false)` to the interface. `ClipSession` passes
  `fresh = true` on the `CLIP_EXPIRED` retry and on Try again. ESPN `fresh` = the 2 KB record lookup, bypassing the
  catalog: 404 → `NotFound` ("ESPN removed this video"); 200 with an unchanged URL → `Failed(HTTP_OTHER)`, with no
  replay. Never a summary read from the resolver. Update the `ClipStream` KDoc.

**M4. The box score runs a second summary parse outside the actor and mutates shared state from two threads.** (§5.5,
§5.4)
- Evidence: `GameDetailRepositoryImpl.boxScore` runs in the caller's coroutine, off the loop (`:126-155`, the view
  model's `viewModelScope`, `SportsViewModel.kt:707-708`). §5.5: on a slot miss it "fetches the summary once … it also
  refreshes the detail". The one-slot box in `EspnDetailSource` is written by the loop's child job and read and written
  by this path.
- Failure: two 600 KB parses can run at once (the loop's poll and the sheet), which breaks §5.4's "one parse in flight
  (never two)". A detail refresh from outside the loop breaks the class's own rule ("all state is touched on the loop
  only", `:63-64`). The slot is a data race.
- Change: route the NFL box through the loop. `boxScore(id)` for a detailed ESPN game posts a `Box(id, deferred)`
  input. The loop answers from the slot, or sets `force` and completes the deferred from the next `Fetched`. Guard the
  slot with the loop, or make it `@Volatile` immutable. Map DTO to model on `Default`, never on the VM's Main
  continuation.

**M5. Registering clip paths with `Redact.registerTransient` evicts real secrets.** (§4.7)
- Evidence: `Redact.kt:16` `TRANSIENT_CAP = 64`, an access-ordered LRU (`:46-48`). The same set holds the live
  stream's token components (`CredentialExpander.kt:134`), LAN pairing user and password (`PhoneApi.kt:234-235`,
  `:323-324`) and backup secrets (`BackupServiceImpl.kt:226`). The design registers each catalog entry's path segments
  on every summary parse (12 clips on an ESPN game, every 60 s, kept hot by re-registration) plus board highlights, up
  to the catalog's 64.
- Failure: once 64 distinct clip paths are seen, the live channel's token components fall out of the mask. A later
  player error that prints the live URI goes into an export unredacted. That breaks the CLAUDE.md logging rule to fix a
  non-problem: the ESPN paths are unsigned, public and carry nothing about the user. The rule as written is also wrong
  for half the clips: `sum-mnf-401873078.json` `videos[id=50578195|50579770].links.source.HLS.href` are
  `/opp/hls/espn/2026/1123/ss_20261123_…/ss_…/playlist.m3u8`, which are not UUIDs.
- Change: do not register clip paths. Clip logging uses `Redact.host` only (already the controller's rule,
  `PlaybackControllerImpl.kt:799`). If masking the path in player errors is wanted, add a static `Redact` pattern for
  `://cmp-espn.media.dssott.com/\S*` and test both path shapes in `ExportRedactionTest`.

**M6. The summary and record calls lose ESPN's WAF rule (403 is a slow-down) and misclassify a WAF challenge.** (§2.5,
§4.2, §12)
- Evidence: `EspnScoreSource` maps 403 → `HTTP_429` per call (`:301`, `:360`, `:474`), not in `classify`. `classify`
  maps 403 and **202** to `HTTP_OTHER` (`:584`). FACTS §1.1: ESPN's WAF answers **202 +
  `x-amzn-waf-action: challenge`**, with an empty body. §12 claims a WAF ends as `BAD_BODY` through `isHtml`, but
  `isHtml` runs only on 404 (`:580`). §4.2 maps record 401 / 403 → `Failed(REFUSED)`, which `ClipSession.kt:288` turns
  into `Failure.REFUSED`, and the VM turns that into `markVideoOff` (`SportsViewModel.kt:320`), while the info path
  only reacts to `ClipResult.Refused` (`:898`). The same 403 gets two meanings.
- Failure: a WAF block of the summary is retried on the short back-off (`HTTP_OTHER`), against the heaviest ESPN
  request, from the same IP as the score polling. The user reads "Trying again automatically" instead of the 429 text.
- Change: `EspnHttp` (commit 0) maps 403 and 202-with-`x-amzn-waf-action` to `HTTP_429` for every ESPN call (board,
  summary, records), so the `BACKOFF_MAX_MS` refused floor applies. Records: 403 → `Failed(HTTP_429)`, never REFUSED or
  video-off. Optional (I am unsure it is needed): while the NFL score repository is in a 429 back-off, the detail gate
  closes for NFL, and vice versa. The two actors share one host and today have independent back-offs.

**M7. "Never offer an expired clip" has no working re-evaluation: the stated re-publish is a no-op, and the board
paths ignore expiry.** (§4.3, §5.1, §2.3)
- Evidence: §4.3: "the repository re-publishes at the next expiry instant". `publish()` assigns an equal
  `DetailState.Ready` (`GameDetailRepositoryImpl.kt:435-444`). A `StateFlow` drops an equal value, so the VM does not
  rebuild. Staleness works only because `stale` flips. The repository's wake is mono time (`nextWake`, `:451-466`);
  expiry is wall time. The board highlight sits in `GameSnapshot.media`, which the repository does not own. `onSlot`
  plays `p.detail?.recapId ?: game.media?.recapId` without a validity check (`SportsViewModel.kt:683`). The card chip
  is "no change" (§6.7). `GoalsSheet.kt:118` remembers rows by `(detail, catchUp, final, media)`, without time.
- Failure: the poster disc, Watch highlights, the card chip and sheet ▶ stay lit after `expiresMs` until something
  else changes, for hours on a past week's board. The backstop (`stream()` → `Expired` toast) is graceful, but the
  design's invariant is false.
- Change: drop the repository re-publish. The VM owns one `expiryTick`: a single coroutine that delays to the earliest
  `expiresMs − margin` among the shown game's `clips` and the visible cards' `media.recapExpiresMs`, then rebuilds. The
  chip, `HubSlots`, `onSlot(WATCH_RECAP)` and the sheet rows all call `ClipOffer.offered(…, nowMs)`. The sheet's
  `remember` key includes the tick. State whether the NFL mapper sets `GameDetail.recapId`. I recommend it does not:
  the NFL recap is `clips.first { kind == RECAP }`, so the NHL `recapPosted` logic is not silently reused with an
  expired id.

**M8. The work packages do not compile independently: commit 0 misses several enum and contract additions, and two
files are edited by two packages.** (§9)
- Evidence:
  - `ClipSession.Failure` (`ClipSession.kt:100`, N4) is matched exhaustively in `SportsViewModel.kt:322` (N3). The new
    `Expired` path needs `Failure.EXPIRED`.
  - `HubToastKind` lives in `SportsViewModel.kt:140` (N3) and is matched in `SportsScreen.kt` (N4, 9 arms).
  - `SlotLabel` (`HubPanel.kt:100`) gains 5 members.
  - `StatKind` has an exhaustive `statLabel` `when` (`HubPanel.kt:461-470`), and `ClipResult` has one in
    `NhlClipResolver.kt:163-169` (`relay`). Neither is in commit 0's list of forced arms, and `NhlClipResolver.kt`
    belongs to no package.
  - `goalsSheetRows` and `goalQueue` live in `GoalsSheet.kt:168`, `:190` (N4), but §6.6 moves them "verbatim" into
    `NhlHubUi` (N3), so both packages edit `GoalsSheet.kt`.
  - The summary `videos[]` object is mapped into `GameDetail.clips` by N1 (`EspnDetailMapper`) and into catalog entries
    by N2 (`EspnClipMapper`), from the same DTO. N1 fills N2's catalog. The validity rule has two names
    (`EspnClipMapper.offered`, §4.3, and `ClipOffer.offered`, §6.1).
  - M1, M2 and M3 each need a contract change (`show()` semantics or a board input, a `links` seam, `stream(fresh)`).
- Failure: whichever of N3 or N4 lands an enum member first breaks the other's build. The hash-equal NHL acceptance
  for the sheet then depends on two packages at once.
- Change:
  - Commit 0 adds every new member of `SlotLabel`, `HubToastKind`, `ClipSession.Failure`, `StatKind`, `ClipKind` and
    `ClipResult`, with stub arms in `SportsViewModel.kt`, `SportsScreen.kt`, `HubPanel.kt` (`statLabel`, `value`,
    `num`), `NhlClipResolver.kt`, `ClipSession.kt`, `ClipPlayerLayer.kt` and `GoalsSheet.kt`.
  - Commit 0 moves `goalsSheetRows` / `goalQueue` into `NhlHubUi.kt` itself, the pure move with goldens hash-equal, so
    N3 and N4 start from it.
  - `ClipOffer.offered` is implemented fully in commit 0 (5 lines, pure) under one name.
  - The video-object mapping `EspnVideoMapper.entry(dto, nowMs)` → `{GameClip, CatalogEntry}` is either written in
    commit 0 or owned by N2, with N1 sequenced after it.
  - The interface changes from M1, M2 and M3 are frozen in commit 0.

---

## Minor

**m1. Team order differs between sections; nothing in the DTO plan says to key by team id.** (§5.3) In all 9
summaries, `header.competitions[0].competitors`, `leaders[]` and `injuries[]` are **home first**, while
`boxscore.teams[]` and `boxscore.players[]` are **away first** (for example `sum-mnf-401873078.json`: header
`[WSH, CIN]`, boxscore `[CIN, WSH]`). Change: the DTO declares `team.id` on `boxscore.teams`, `boxscore.players`,
`injuries` and `lastFiveGames`. The mapper resolves sides only through the header's `homeAway` by team id, never by
index. Add a mapper test that fails on index order.

**m2. The record's `plays[].id` is a JSON number; `scoringPlays[].id` is a string.**
`clips-mnf/50578630.json` `videos[0].plays[0].id = 4018730781583` (int); `sum-mnf-401873078.json`
`scoringPlays[].id = "4018730781322"`. A `String` field fails non-lenient kotlinx (`consumeString` expects a quote), so
every record would be `BAD_BODY`. Change: declare `JsonElement?`, normalise to the decimal string (13 digits fit a
`Long`), and test both types.

**m3. Scoring type mapping.** Across all samples `type.abbreviation` ∈ {FG, TD, **SF**}. A safety is "SF", not
"Safety". The defensive TD arrives as `TD` with `type.text` "Interception Return Touchdown" (1 occurrence). `DEF_TD`
cannot come from the abbreviation; map it from `type.text` / `type.id`. `TWO_POINT` was never seen as its own play
(conversions sit in the TD text); keep it only if a sample shows it.

**m4. `Leader.value: Int` truncates half sacks.** `sum-pre.json` LAR `sacks` `value 5.5` (B. Young). Either make the
NFL value `Double` (an appended field) or always draw `displayValue` for sacks.

**m5. A phase change must make the fetch due at once.** "FINAL: once" and "a phase change re-times at once" are not
what `DetailPollPolicy.nextAt` does: after LIVE → FINAL it returns `lastOk + HUB_DETAIL_IDLE_MS`
(`DetailPollPolicy.kt:81-85`), so the final totals and the recap headline can be 5 min late, and `followPhase` only
re-shows. Change: in the NFL cadence, `effectivePhase != fetchedPhase` → due now, still subject to back-off and the
15 s floor.

**m6. Final polling stops at the highlight, but one-plays are posted after it.** `sum-mnf-401873078.json`: the
Final Game Highlight 50579688 was published 04:32:11Z and OnePlay 50579770 at 04:39:03Z. With the §5.2 rule, a viewer
on the panel at 04:33 never gets the later clip (FINAL with a highlight → no interval → never re-read while in the
LRU). Change: for a game whose detail listed any PLAY clip, keep the 5 min re-read until `ended + 30 min` even after
the highlight.

**m7. The headline score must stay the board's for the NFL.** `SportsViewModel.kt:949-952` lets a detail fetched
after the card's anchor override the board's score. The summary's header score lag against the board is NV (§12), and
a 60 s summary against a 30 s board can show a score going backwards. Change: the NFL mapper leaves
`awayScore` / `homeScore` null (or the rule is gated on `ui.detailed && league == NHL`), as §2.1's "headline unchanged
(board)" intends.

**m8. The `league:id` key must cover every per-game map, not only `cache`.** `endedAt` (`:178`), `seenUnfinished`
(`:185`), the trim's `keep` (`:274`), `noteEnd` (`:478`) and `recapPosted` all key by `id.id`. Change them together.

**m9. Cap and length details in the `EspnHttp` extraction.** `classify` hard-codes `ESPN_MAX_BODY_BYTES` (1 MiB,
`EspnScoreSource.kt:554-555`), so the extraction needs a per-call cap ("no behaviour change" only holds with it as a
parameter). The request sets no `Accept-Encoding`, so OkHttp un-gzips transparently and `contentLength()` is −1: the
"Content-Length over the cap fails before reading" path never runs against ESPN (the sample's
`content-length: 58278` is the gzip length). Keep the test, but do not count it as protection. `CappedStream` counts
decoded bytes, which is the right cap.

**m10. The transient parse cost is allocation, not live set; the fallback names a library the project lacks.**
kotlinx 1.11 `AbstractJsonLexer.skipElement` allocates a `String` for every string token it skips (`consumeKeyString`
/ `consumeStringLenient`), so skipping `drives` (336–414 KB across the samples) churns ≈ 1–2 MB per parse. The
256 KB live-set budget is plausible, given the kept sections are ≈ 150 KB of JSON. "Okio-backed `JsonReader`" is
Moshi's; the project has only kotlinx (`libs.versions.toml:39`). Change: add a JVM allocation measurement to
`EspnSummaryDecodeTest` (ThreadMXBean bytes allocated per decode of the padded body) as the yardstick the device
check compares against. Name the fallback concretely (a hand-written skip over `okio.BufferedSource` for the top level,
no new dependency).

**m11. The rendition cap must be undone for live cycles; use the engine's low-RAM flag.**
`trackSelectionParameters` is rebuilt from the player's current value every cycle (`PlaybackControllerImpl.kt:1215`),
so a `setMaxVideoSize` / `setMaxVideoFrameRate` set for a clip persists into the resumed live channel unless non-clip
cycles clear it explicitly (`clearVideoSizeConstraints()`, `setMaxVideoFrameRate(Int.MAX_VALUE)`).
`DeviceClassSource` lives in `core/guide` (`GuideEnvironment.kt`); `:player` already has `engine.isLowRam`
(`:1199`). The controller test as listed covers the live case. Make it a written rule in §4.5.

**m12. A queue whose next clip has expired ends the session.** `ClipSession.onEnded` → `start` → `stream` → `Expired`
→ `fail` → `endWithResume`. Change: skip entries that `ClipOffer.offered` rejects when advancing, and end only when
none remain.

**m13. Clock skew: there is a better reference at no cost.** Both the board and the summary carry
`Fetch.Ok.serverDateMs`. Keep `skew = serverDate − wallAtReceive` (clamped, last good answer) and evaluate
`ClipOffer` at `now + skew`. Board highlights can be evaluated at the board's `serverDateMs` in the pure mapper, so
`EspnMapper.board` needs no wall clock.

**m14. Smaller contract and process points.**
- `StatKind` gains members that §2.2 says are never shown (FOURTH_DOWN, PENALTIES, RED_ZONE, PASS_YARDS_PG,
  RUSH_YARDS_PG). `totalPenaltiesYards` "5-39" is count-yards, not a fraction, so `Fraction(dash)` would be wrong for
  it. Append them when a row uses them.
- The fixture reducer is "not committed", so the fixtures cannot be regenerated. Commit it under `tools/` (it reads
  local samples; no sample is committed).
- §1 / §14.7 "header arrives after drives and videos" holds for `sum-final.json`. In `sum-mnf-401873078.json`,
  `header` precedes `winprobability`, `article` and `videos`. Key order varies between samples (`sum-pre.json` vs
  `sum-pre2-401873079.json`). The conclusion (no early stop) stands; add "no code may depend on key order".
- `EspnClipCatalog` is written from the detail actor, the score actor (board highlights) and the resolver: it must be
  synchronized, and `playIds` links should not sit in an LRU that board puts can churn (see Cut #2).

---

## Cut or defer (first version)

1. **Clip ↔ scoring-play linking (§4.4), entirely.** It serves ESPN-aired games only (MNF: 3 of 10 scoring plays,
   `clips-mnf/`), and it drives most of M2, m2, the budget, concurrency, the 60 s stop, `ClipInfo.playIds` and the
   per-game counters. v1: the sheet lists scoring plays (no ▶), then Game highlights, then **Highlights**: every
   offered PLAY clip by publish time. Add the ▶ later once the live capture shows how often links exist.
2. **Catalog fill from `EspnScoreSource`.** The board highlight only needs `GameMedia(recapId, recapExpiresMs)`.
   `stream()` on a catalog miss is one 2 KB record lookup. This removes the score actor's dependency on N2's class (an
   N1/N2 collision) and the LRU churn.
3. **The board-score nudge (M1a)** and the **halftime hold**. Together they save ~13 requests a game and need new
   repository input.
4. **`ClipRendition` as a policy object.** Until Q3 or the device check produces a reason, the NHL clips run
   uncapped today. If a low-end cap is wanted, one `if (engine.isLowRam)` line with m11's reset is enough.
5. **Redact registration of clip paths (M5)**: drop it.
6. **The repository's expiry re-publish (M7)**: replace it with the VM tick.
7. Unused `StatKind` members (m14) and the JsonReader fallback (already "not built").

---

## Not reviewed

Panel content, wording, focus and layout, slot widths, the §2.6 height budget, and sheet design (the second
reviewer's scope). The legal and terms exposure (owner ruling). The fake-panel code (`sports_nfl.py`,
`sports_video.py`). Live summary shape, the scoring lag and in-game clip arrival (no live sample; the design gates them
on the capture, which I agree with). On-device parse time, GC and 720p59.94 smoothness (device check §8.5). Coil cache
keys and image decode sizes beyond the memory table. I did not build or run anything.
