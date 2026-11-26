# NFL hub code review: data, core and tools (branch `nfl` @ 2ad75cb)

Scope: `core/.../sports/hub/**`, `EspnMapper.kt`, `EspnDto.kt`, `SportsContracts.kt`, `SportsConstants.kt`,
`data/.../sports/**` (ESPN transport, sources, resolver, catalog, repository, module), `ClipRendition.kt` and the
`PlaybackControllerImpl.applySwitches` lines, `tools/espn/reduce_summary.py`, the fake panel's NFL hub routes, the
`sports/nfl/hub/` fixtures and their tests. `:ui` code and strings are reviewed separately.

**Verdict: approve after one Major fix.** No Blockers. 1 Major, 10 Minor.

The mapping is careful and holds up against the recorded shapes. Sides are matched by team id everywhere, a partial
body never throws, negative and fractional leader values are handled, ties are handled, and the `ClipOffer` boundaries
are consistent. The NHL paths are unchanged in behaviour (cadence, keys, box-score path, resolver signature). The
remaining defect is how "Try again" interacts with stable ESPN URLs.

---

## Blocker

None.

## Major

### 1. Try again can never recover an ESPN clip that failed for a transient reason

- **Where:** `data/src/main/kotlin/app/thebox/data/sports/EspnClipResolver.kt:86-90`. It meets
  `ui/.../ClipSession.kt:215-218` (`retry()` always passes `fresh = true`) and
  `player/.../FailureClassifier.kt:257-258` (5xx, IO and stalls Restart, then fail with a code other than
  `CLIP_EXPIRED`).
- **Scenario:** an NFL highlight from the catalog plays. Wi-Fi drops or the CDN returns 5xx, and the controller uses up
  `HUB_CLIP_RESTARTS`, so the error card appears. The viewer presses Try again, which calls
  `stream(ref, fresh = true)` and makes one record lookup. ESPN's URLs are "unsigned and stable" (§4.2), so the record
  returns the **same** HLS URL. The resolver answers `Failed(HTTP_OTHER)` and the viewer sees "unavailable". Every
  further Try again does the same, and each one costs a record request.
- **Why it happens:** the same-URL rule is meant for the `CLIP_EXPIRED` re-resolve (arch M3: do not replay a dead
  signed URL). It is applied to every `fresh` call, and `retry()` is also `fresh`. For ESPN, the same URL is the normal
  answer, not evidence that the link is dead.
- **The NHL is unaffected:** `NhlClipResolver` ignores `fresh`.
- **How found:** by reading. `EspnClipResolverTest.freshWithTheSameUrlIsAFailureNotAReplay` asserts the current
  behaviour.
- **Fix (pick one):**
  - (a) In the resolver, on `fresh` with the same URL, play the entry. A truly dead URL then 403s once more. That
    becomes `CLIP_EXPIRED`, `ClipSession`'s `expiredRetriedIndex` stops a second re-resolve, and the error card shows.
    No loop and at most one extra master request.
  - (b) Give `stream` a reason (`fresh = EXPIRED | RETRY`) and keep the same-URL refusal for EXPIRED only.
  - (a) is smaller and needs no `:ui` change. Update the test either way.

## Minor

### 2. Open defect #3: the nullable stand-in `mapDetail` seam (judged: real but low risk; fix it)

- **Where:** `EspnDetailSource.kt:58-59, 110-111`.
- **Problem:** production runs `EspnDetailMapper.detail(dto, id, at, httpOk)` and tests with a stand-in run
  `stand(dto, id, at)`. That is two code paths, and the stand-in's signature cannot receive `httpOk`. That is exactly the
  slot in which the integration bug sat (§18.2 a: the source called the three-argument form, so no fake-panel clip was
  ever offered). A test using the stand-in cannot catch a regression of `httpOk` passing.
- **Current coverage:** the pipeline tests now cover the real path, so this is not a live defect.
- **Fix:** make the seam non-null and give it the full signature:
  `mapDetail: (EspnSummaryDto, GameId, Long?, Boolean) -> GameDetail? = EspnDetailMapper::detail` (the four-argument
  overload). Call it as `mapDetail(dto, id, at, httpOk)`, and have `EspnHubTestSupport.detailOf` take and ignore the
  flag. This leaves one code path and no `if (stand != null)`.

### 3. Embargoed clips are dropped at parse time, so the embargo-lift timer has nothing to act on

- **Where:** `EspnDetailMapper.kt:357-363` and `EspnVideoMapper.kt:63` (`entry(…, atMs)` returns null when not offered);
  also `EspnMapper.highlight` and `EspnDetailSource.offered`.
- **Problem:** a clip embargoed at `now + skew` is absent from `GameDetail.clips`, the catalog and `GameSnapshot.media`.
  The VM's `offerTick` (§4.3) can only expire clips; it can never reveal one when its embargo lifts.
- **Scenario:** a FINAL game whose highlight is already offered (so the 2 h wait is over) and has no PLAY clips, so
  polling has stopped. ESPN then lists a one-play clip with an embargo 10 min ahead. It is never shown while that game
  stays on screen.
- **Impact today:** none in practice. Every recorded clip has `embargoDate` = publish − 1 s. The design text also
  implies the clip appears when its embargo lifts.
- **Fix (pick one):** map with `atMs = null` and keep the expiry check only, filtering at draw time as the UI already
  does with `ClipOffer`. Or drop the "embargo lifting" wording and the `changesAt` embargo branch as dead.
- **How found:** by reading.

### 4. `httpOkFor` fails open

- **Where:** `EspnVideoMapper.kt:47`, `logoBase.trim() != PRODUCTION_LOGO_BASE`.
- **Problem:** any production `EspnEndpoints` whose logo base differs by a single character turns plain-http media on.
  Examples: a future CDN change, a missing trailing slash, an `EspnEndpoints(API_BASE, "https://a.espncdn.com")` built
  anywhere. Today, release builds only ever use `DEFAULT`, so this is not exploitable now.
- **Fix:** make the rule positive. Allow http only when the endpoints came from `fromOverride` (a flag on
  `EspnEndpoints` set there), or when `BuildConfig.DEBUG` is set and the endpoints are not the default.

### 5. The catalog and `GameDetail.clips` choose their clips independently

- **Where:** `EspnDetailSource.kt:130-134` and `EspnDetailMapper.clips`.
- **Problem:** the catalog takes the first 24 offered entries in ESPN's order, with no dedupe. The detail takes the
  highlight first, then plays oldest first, capped at 24. With more than 24 offered videos (the design estimates 12 for
  an OT MNF game, so this is unlikely), the highlight can be in `clips` but not in the catalog. Playing it then costs a
  record lookup, and `info()` for "Up next" misses.
- **Fix:** build the catalog from the same ordered, deduped list: map each entry once and derive both `clips` and the
  catalog entries from it.

### 6. The kill switch still runs the FINAL highlight wait

- **Where:** `GameDetailRepositoryImpl.kt:603-611` (`recapPosted`).
- **Problem:** with `ESPN_VIDEO_ENABLED = false`, a shown final with no highlight is still re-read every 5 min for 2 h,
  up to 24 summaries of ≈ 50 KB gz / 600 KB decoded, waiting for a video the app will not offer.
- **Fix:** treat `recapPosted` as true for the NFL when the switch is off, which also ends the plays wait.

### 7. The NFL final wait starts from when the game was first seen final, not when it ended

- **Where:** `GameDetailRepositoryImpl.kt:620-629` (`noteEnd`). This rule predates the branch, but it costs more for
  the NFL.
- **Scenario:** a game is shown live at 21:00, the viewer leaves the Sports page, the game ends at 23:30, and the viewer
  returns at 01:00. `endedAt` = 01:00, so for a game ESPN never posts a highlight for (MIA @ BUF has none), the panel
  re-reads every 5 min until 03:00 while it is shown.
- **Fix:** clamp the first-seen-final instant to at most `startUtcMs + gameLengthMs + 1 h` when the board's last
  non-final sighting is older than the polling interval, or fall back to the start + length estimate when the game was
  last seen unfinished long ago.

### 8. The pipeline tests' expected value is the mapper's own output

- **Where:** `NflHubPipeline.expected(case)` and its uses in `NflHubPipelineDataTest` and `NflHubPipelineUiTest`.
- **Problem:** the expected detail is computed by the real `EspnDetailMapper` from the same body, so those equality
  assertions pass with a wrong mapper. They prove wiring only.
- **Mitigation:** `theRealPipelinesFactsPerCase` and `EspnDetailMapperTest` pin concrete values, so this is a note,
  not a hole.
- **Still provisional:** the in-game clip and embargo paths (`summary-live-401873079-2/-3`, `…_provisional`) are covered
  only by hand-built bodies; no capture has shown an in-game clip yet. Replace them when a capture of an ESPN-aired game
  in play is in.

### 9. The fake panel writes a 1-point "Safety"

- **Where:** `tools/fakepanel/sports_nfl.py:980, 1018, 1024`.
- **Scenario:** for a non-featured or scripted game, `scoring_changes` splits a score into 7s, 3s and then the
  remainder. A score of 8, 15 or 22 leaves a 1. `scoring_plays` only absorbs a 1 or 2 into an open 6, and a 7 is not
  open, so the remainder becomes an `SF` play worth one point ("… Safety", 15 → 7, 14, 15). The app maps it as
  `SAFETY`, so the Scoring sheet on the fake panel shows an impossible play.
- **Fix:** split the remainder as 6 + 2, so 8 = TD + 2-pt and 15 = 7 + 8, with 2 the only safety value.
- **How found:** by reading.

### 10. The fake panel's `gone` mode does not reach the path it claims

- **Where:** `tools/fakepanel/sports_video.py:43` (doc), `ESPN_GONE_MODE`.
- **Problem:** the doc says a 404 master exercises "the app's CLIP_EXPIRED → fresh lookup path". A 404 is not
  `CLIP_EXPIRED` (`FailureClassifier.kt:257`: only 403 and 410), so it goes to the error card. The fresh lookup then
  happens only through Try again, which reaches the record 404 and shows "ESPN removed this video".
- **Fix:** correct the doc, or serve 403 on the master in `gone` if the `CLIP_EXPIRED` path is what the emulator
  checklist wants.

### 11. The reducer and the fake panel leave some recorded live shapes unexercised

- **Where:** `tools/espn/reduce_summary.py` and `sports_nfl.summary`.
- **Problem:** §18.4 records top-level `odds`, `againstTheSpread`, `standings`, `format`, `meta`, `broadcasts` and
  `wallclockAvailable` in play. The reducer drops them, and the fake panel sends none, so the decoder's skip over those
  keys has no fixture. The fake panel also never sends a negative leader value, the `" 5:34"` possession time or
  `rushingYards "-8"` (the mapper tests cover these from the real capture).
- **Fix:** have the reducer emit one-item blanked stubs of `odds` and `againstTheSpread`, as it does for `pickcenter`
  (no odds values committed). This is optional for the fake panel.

---

## Checked and found correct

- Mapping by team id in header, boxscore, leaders, injuries, lastFiveGames and scoringPlays, with reversed order
  tested.
- Phase mapping: pre → SCHEDULED; in → LIVE; `STATUS_HALFTIME` and `STATUS_END_PERIOD` → INTERMISSION; post → FINAL;
  postponed or cancelled names → POSTPONED; the board's `delayed` comes from the status name.
- Scoring:
  - FG, SF, TD; DEF_TD on type 36 or the interception/fumble return text;
  - OT as period > 4;
  - the side taken from the score when `team.id` is missing;
  - the cap of 60 keeps the latest plays.
- Records with ties, last five with a tie, injuries counted before the cap, a leader's −8.0 value kept and 5.5 floored
  with the line kept, missing sections giving empty fields, and another event's header giving null.
- `ClipOffer`:
  - the embargo instant is offered (`≤`);
  - at `expires − margin` the clip is not offered (strict `<`);
  - `changesAt` agrees with both, and with an embargo that never lifts in time.
- The skew is clamped to ±1 h and the resolver checks at `now + catalog skew`.
- Settop whitelist and blacklist handling, and HLS only, never MP4, cmaf or mobile.
- Board `highlights` tolerance under the **production streaming decoder**: `null`, a string, a number, an object, a
  list of junk and a wrong-typed leaf each leave the board intact. This was confirmed by running a temporary
  `decodeFromStream` test, since deleted; the committed test uses the tree decoder only.
- NHL regression:
  - `DetailCadence.NHL` equals the old constants;
  - the NHL `recapPosted` is unchanged;
  - the NHL box-score path is unchanged;
  - every per-game map uses `league:id` (no `id.id` keys remain);
  - `EspnHttp` is a verbatim extraction of the transport, plus the per-call cap and the WAF rule;
  - the three removed 403 → 429 mappings were identity copies;
  - the day board's 403 is now `HTTP_429`, as intended by arch M6;
  - `NhlClipResolver` only gains the ignored `fresh` parameter.
- Repository:
  - one parse at a time, with the box score sharing the poll's parse;
  - waiters are released on a move, Sports off, a closed gate, a stopped loop and a failed parse;
  - back-off wins over a forced box read;
  - the lag cap counts only good answers for the shown game and resets on a move.
- The decoded cap counts decoded bytes and drains the body. Mapping runs on `Default`, and cancellation reaches the
  OkHttp call.
- Rendition cap: no other code sets video size or frame-rate constraints, so clearing them on every non-clip cycle
  changes nothing for live.
- Fixtures use `.invalid` hosts only, the `pickcenter` stub is blanked, and no odds field is declared or read. The ESPN
  logs carry a label, a kind, a content type and a length, and clip URLs appear as `Redact.host` only.

## Not reviewed

- All `:ui` code and strings (`SportsViewModel`, `ClipSession` beyond the `fresh` call sites cited in #1, the sheets,
  goldens).
- Device behaviour: parse time and GC on a Fire TV Stick, the rendition cap on real low-RAM hardware, WAF behaviour
  under hours of polling.
- `tools/fakepanel/test_*.py` were read for intent only and not run. No full Gradle gate was run.

---

## Resolution (fix pass, 2026-11-26)

Details and tests in `docs/design/m6-nfl-hub.md` §19.1.

| # | Finding | Resolution |
|---|---|---|
| 1 (Major) | Try again can never recover a transient ESPN failure | **Fixed** (option a): a fresh lookup with the same URL plays it; a dead URL ends after one re-resolve (`ClipSession`), no loop. `NhlClipResolver` ignoring `fresh` is now tested. |
| 2 | Nullable stand-in `mapDetail` seam | **Fixed**: non-null four-argument seam, default `EspnDetailMapper::detail`. |
| 3 | Embargoed clips dropped at parse time | **Fixed**: kept with an expiry-only check (`ClipOffer.listed`); the offer tick reveals them. The board highlight is unchanged (no embargo field in `GameMedia`; the board re-reads). |
| 4 | `httpOkFor` fails open | **Fixed**: `EspnEndpoints.debugOverride` (set only by `fromOverride`) and an http-logo-base rule; fails closed. |
| 5 | Catalog and detail clips chosen independently | **Fixed**: one selection (`EspnDetailMapper.videoEntries`). |
| 6 | Kill switch still runs the FINAL wait | **Fixed**: `recapPosted` true and `playClips` false for the NFL with video off. |
| 7 | Final wait from first-seen-final | **Fixed, NFL only**: the start + length estimate when the last unfinished sighting is > 10 min old; NHL unchanged. |
| 8 | Pipeline expected values are mapper-derived | **Deferred** (known limitation, design §15); hand-written spot values per case added. The provisional `-2` / `-3` live fixtures stay until a capture shows an in-game clip. |
| 9 | Fake panel 1-point "Safety" | **Fixed** (`split_score`). |
| 10 | `gone` mode doc | **Fixed**: `gone` now serves 403 on the media (the `CLIP_EXPIRED` path the doc and the emulator checklist describe), record 404; doc, README, design §8.4 / §8.5 updated. |
| 11 | Recorded live shapes unexercised | **Fixed** for the reducer (blanked `odds` / `againstTheSpread` stubs) and the decoder (live fixture with every §18.4 top-level section injected). The fake panel deliberately sends no odds keys (its shape test and the no-odds rule). |
| checked | Board `highlights` tolerance under the streaming decoder | **Committed** as `EspnBoardHighlightsStreamTest`. |
