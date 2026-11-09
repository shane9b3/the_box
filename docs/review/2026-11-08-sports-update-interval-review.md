# Review: sports update interval (`docs/design/m6-sports-update-interval.md`)

Reviewer: adversarial design review (Opus), 2026-11-08. Against `main` `939f827`, `docs/design/m6-score-bug.md`,
`docs/design/m6-nfl.md`, rulings 1–32 in `docs/decisions/2026-11-05-nhl-score-bug.md`.

**Owner rulings 29–32 apply** (recorded after the proposal): one setting per league, shown for the league on screen;
NHL Auto · 20 · 40 · 60 s; new installs on Auto; **NFL floor 5 s** (no 2/3/4 s choices, Auto never under 5 s). Findings
that only held at 2–4 s NFL cadences are marked as such and are not blockers.

Verdict: **the per-event switch is sound and I support it.** Two blockers before WP start: one NHL timing bug the
proposal misses, and one wiring trap in the contracts. The rest are should-fix items.

---

## 0. Re-verification (curl, 2026-11-09 00:00–00:05 UTC, Mac on home broadband)

Bodies and headers: `/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/e446e45a-e691-488f-a693-1f5ccf5078f2/scratchpad/curl/`
(`ev_*.json` / `ev_*.h`, `dated.json`, `board.json`, `nhl_ev.json`). Sampler:
`.../scratchpad/scripts/mono.py` (1 s per-event polls of three live games, logs each change and flags regressions).
Note: the proposer used the same scratchpad. My curls **re-wrote** `ev_401873045.json` (final) and `ev_401873050.json`
(scheduled) with fresh bodies of the same games (byte sizes unchanged, 17 493 and 18 741 B). `sbEvent.json` was not
touched. The scratchpad is temporary: **the lead should copy the fixtures into `core/src/test/resources/sports/nfl/` now**,
not leave it to WP-2.

| Check | Result |
|---|---|
| Shape | One event object. Top level: `id, uid, date, name, shortName, season, week, competitions, links, weather, status`. A recursive key diff against the same event in `scoreboard?dates=20261108` found **no key missing** to depth 4. No `leagues` (so no calendar), as F6 says |
| Mapper fields | `competitions[0].status.{clock, displayClock, period, type.{id,name,state}}` ✔; competitors `id, homeAway, score, team.{abbreviation, displayName, shortDisplayName, logo, logoDark}` ✔; `situation.{down, distance, yardLine, possession, possessionText, shortDownDistanceText, isRedZone}` ✔; `situation.lastPlay.{id, type.id, type.text, scoreValue, team.id}` ✔ (the two-point/safety tie-break reads `type.text`) |
| Week / calendar | Not needed by `EspnMapper.game()`: `dayKey` comes from the event `date`. The event even carries `season`/`week`. Lost: only the calendar and the source's `remember(events)` of other ids' dates (used only by the fallback, which has the start hint) |
| Logos | `team.logo` present, but unused: `withLogos` builds the combiner URL from the abbreviation. Nothing lost |
| Size | Live 9 036 B raw / **2 206 B** gzip; scheduled 18 741 / 3 716; final 17 493 / 3 047 (pre/final carry `odds`, `tickets`, `leaders`, `headlines`, which are skipped by the reader) |
| Cache | `cache-control: max-age=1` on every 200; no `Age`, no `Via`/`X-Cache` |
| 404 | Unknown id → 404 `application/json` `{"code":404,"message":"No event found for eventId: 999999999"}`, **`max-age=3600`** |
| 400 | Non-digit id → 400 JSON, `max-age=3019`, **gzip-encoded even without `Accept-Encoding`** (OkHttp's transparent gzip handles it; a raw client would not) |
| Freshness | 90 s at 1 s on three live games: content changed only when a play was logged (gaps 1–30 s; one game was frozen for 80 s on an official timeout). **No regression** (period, clock, score sum and `lastPlay.id` never went backwards). `lastPlay.id` is a monotonic number (`…3906 → …3936 → …3958 → …3980`) |
| ESPN NHL | `site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard/{id}` exists, `max-age=2`, 8.9 KB raw (see N6) |
| NHL CDN | `score/now` 307 → dated board, `max-age=19, s-maxage=19`, `age: 7`: F1 confirmed |

---

## Blockers

### B1. `GOAL_FRESH_MS` (100 s) silently kills NHL goal takeovers at the new 60 s choice
**Evidence.** `ScoreBugMachine.kt:400` flags a goal only when `q.receivedMono − prev.receivedMono <= GOAL_FRESH_MS`,
and `GOAL_FRESH_MS = 2 * 40_000L + BACKOFF_BASE_MS` (line 756): "2 × the slowest SEEN cadence plus a failed poll's
back-off". The proposal's §3.5 table never mentions it. With NHL 60 s, intermission becomes 2 × 60 = **120 s** (§3.5), so
a goal in the first two minutes after any intermission is compared with a snapshot ~120 s old → **no takeover, no goal
mark, no PP GOAL**. Also at 60 s live: one failed poll (retry +20 s) then a second failure (+40 s) gives a 120 s gap →
missed goal. Same for NFL 30 s if two back-offs stack (30 + 5 + 10 + 20 + ... ). This is an NHL regression in a
design that claims NHL is unchanged at 20 s and only "scaled" above it.

**Fix.** Make it a function of the effective cadence: `goalFreshMs = 2 × breakMs + backoffMs(2)` where `breakMs` =
the league's intermission interval (NHL 60 → 2 × 120 + 40 = 280 s; NHL 20 → 2 × 40 + 20 = 100 s, i.e. byte-identical
today). Set it in `interval(...)`. WP-1 tests: NHL 60 s, goal on the first poll after a 120 s intermission → flagged;
NHL 20 s keeps 100 s (existing tests unchanged); a genuinely old comparison (> the new window) still silent.

### B2. `FollowMachine.interval` with a default no-op body: production can silently never get the interval
**Evidence.** §4.1: "`FollowMachine.interval(liveMs: Long?)`: a default no-op body, so the loop's test fakes compile
unchanged." `FollowMachine.of(m)` (`ScoreRepositoryImpl.kt` ~line 85) is an anonymous object that forwards each call by
hand. If commit 0 adds the interface method with a default body and nobody adds `override fun interval(...) = m.interval(...)`
to `of`, every unit test passes (fakes and the machine tests both work) and the NFL in production stays on today's
`received + 20 s + phase`. No test in §7 would catch it.

**Fix.** Commit 0 adds the `ScoreBugMachine.interval` stub **and** the forwarding override in `FollowMachine.of`. WP-2
adds a wiring test: build the repository with the real `machineFactory` path (or `FollowMachine.of(ScoreBugMachine(...))`)
and a fake NFL source; assert the second poll comes `doneMono + 5 s` later, not ~21.5 s.

---

## Should-fix

### S1. Rulings 29–32 not yet in the design
Choice lists, defaults and examples still say 2/3/4 s. Update: NFL list **Auto · 5 · 10 · 15 · 20 · 30 s**;
`INTERVAL_DEFAULT_NFL_S = 5`; remove "start at 4 s" (§2.3, §3.2, Q2); the §3.2 examples (0.4 s p90 → 5 s, 1.2 s → 5 s,
2 s → 10 s); §3.7 table (NFL 5 s per-event ≈ 2.2 KB × 720 ≈ **1.6 MB/h**; board fallback at 5 s ≈ 14 MB/h, but Auto on
fallback stays ≥ 10 s); `BACKOFF_FAST_BASE_MS` 5 s still makes sense; WP-1 test list ("2 s" cases → 5 s); D2/D3 at 5 s;
Q1/Q2/Q4 answered (link rulings 29–32). Also note the consequence below (N1): at a 5 s floor, the measurement rarely
moves Auto.

### S2. Fixed choices ignore a 429
**Evidence.** §3.2: the 429 floor (10 s) applies to **Auto only**; §3.3: "a fixed choice = that value, exactly". With
"Every 5 s" fixed, a 429 backs off (20 s base, `Retry-After` floor) and then returns to 5 s, inviting the next 429:
a sawtooth against a server that asked us to slow down. Rate-limit is a server instruction, not a preference.
**Fix.** After a 429, the effective interval is `max(choice, AUTO_429_FLOOR_S)` for the rest of the follow, for every
choice. Test: fixed 5 s + 429 → next live gap ≥ 10 s after the back-off.

### S3. A 403 (or other block) switches to the 9× heavier board
**Evidence.** §4.2.3: two consecutive `HTTP_OTHER` or `BAD_BODY` switch to the dated board. `classify` maps every
non-200/404/429/5xx code to `HTTP_OTHER` (`EspnScoreSource.kt`, `else -> failed(FailKind.HTTP_OTHER)`). A CDN/WAF block
of a hot client is typically **403**; a captive portal is an HTML 404 or a 30x. In each case the design moves to the
~20 KB board on the same host.
**Fix.** Switch only on a **shape** signal: `BAD_BODY` from a 200, or a JSON 400/404 that is not "No event found".
403 → treated like 429 (back-off on the 20 s base plus the 10 s floor). HTML 404 / 30x → back-off, no switch (they are
network problems, not endpoint changes). Also retry the per-event path once every N minutes (say 10) instead of
"until the follow ends", so one bad patch does not pin a 3-hour game to the board. Tests: 403 ×2 → no switch, slower;
HTML 404 ×2 → no switch.

### S4. A per-event 404 is cached for an hour and removes the follow with no cross-check
**Evidence.** F9 and my curl: the 404 carries `max-age=3600`. The two-NotFound rule (`NOT_FOUND_CONFIRM_MS` 20 s,
unchanged and correct at faster polls: the machine already holds the retry until `first + 20 s`) was designed against a
*transient* edge 404. If an edge caches a transient 404 for an hour, the second request 20 s later is the same cached
answer, so the rule confirms a glitch. Today's board path confirms absence against the week board (N7) before
NotFound.
**Fix.** On a per-event JSON 404, the source reads the dated board (start hint) once; only "absent there too" is
`NotFound`; "present" returns the board's snapshot (and counts toward the fallback). One ~20 KB request per 404, which is
rare. Test: per-event 404 + board has the event → `Ok`; both absent → `NotFound`.

### S5. Going back in time on the fallback switch (and any older body)
**Evidence.** F8: per-event leads the board by 6–9 s. On the switch, the first board answers can carry the pre-TD
score: the bug's score drops, then rises again (the N5 guard suppresses the second takeover, but the visible drop
stays). `fetched()` inserts by `anchorMono` (= `received`, ESPN sends no `Age`), so an older body becomes the newest.
**Fix.** In the source (or the machine, NFL only): drop an `Ok` whose `lastPlay.id` is numerically lower than the last
accepted one, or whose (period, clock) is earlier with an equal or lower score sum; count it as a no-op `Ok` for stale
purposes. `lastPlay.id` is monotonic in my sample (§0). Test: board body with an older `lastPlay.id` after a per-event
body → shown score unchanged, no takeover.

### S6. What `workMs` times: the day list, cold start and survivorship bias
**Evidence.**
- §3.2 says the sample runs "to just after `machine.fetched()`, `step()` and `publish()`". In `ScoreRepositoryImpl.loop`
  `publish()` runs **after** `if (dayDue(...)) fetchDay()`. Timed literally, every poll that shares a loop turn with the
  Sports tab's week board (230 KB decode, F3) gets that decode added, and the meter moves Auto up whenever the tab is open.
- **Cold start:** the first poll of a process pays DNS + TCP + TLS + serializer class loading and JIT. With
  `METER_MIN = 5`, nearest-rank p90 of 5 samples is the **maximum**, so that one cold sample decides the first
  recommendation; the persisted window then carries it into the next run.
- **Survivorship:** only accepted `Ok`s are sampled. A box on bad Wi-Fi that times out every third poll (10 s each)
  reports only its fast polls and looks healthy.
- "An `Ok` that the machine accepted": `fetched()` returns `Unit` and drops stale-follow answers silently, so the repo
  cannot know. 

**Fix.** Time `source.game(...)` → `machine.fetched()` → `machine.step()` → `machine.view()` (the work the bug needs),
in a block that never includes `fetchDay()`. Skip the first sample of each process (or any call whose `EventListener`
saw `connectStart`). Use p90 only at ≥ 10 samples (`METER_MIN = 10`); below that, the default. Count timeouts/IO
failures in the window at their elapsed time (cap 10 s), so a failing network recommends slower. Make `fetched()` return
`Boolean` (accepted) in commit 0, or compare `f.value.id` to the followed id in the repo.

### S7. "Display" is claimed in the strings but not measured
**Evidence.** The owner asked for "grab the data parse it and then display it". §3.2 times up to the `StateFlow` write
and leaves the draw to device item D2. `SPORTS_INTERVAL_DESC` says "This box takes about %2$s s to get, read **and
show** an update". That is not what is measured.
**Fix (pick one, lead's call).** (a) Honest strings: "to get and read an update" and drop "show". (b) Measure it without
coupling `:core` to `:ui`: add `ScoreRepository.reportShown(gameId, mono)` (toolkit-agnostic, in `:core`). The bug's
composable calls it once per new `ScoreBugView` instance from a `withFrameNanos` after first composition; the repo
keeps `publishMono` for the last published view **outside** the view and adds `shownMono − publishMono` to the sample.
**Do not** put a timestamp or sequence in `ScoreBugView`: it is a data class behind a `StateFlow`, and today an
unchanged body yields an equal view and no recomposition. A stamp would turn every poll into a redraw (N3). I'd take (b):
it is what the owner asked for, and it is cheap.

### S8. "Every 5 s" means 5 s plus the work time
**Evidence.** §3.4 schedules NFL from completion: `doneMono + liveMs`. On a 1.5 s p90 Stick, "Every 5 s" is really
every ~6.5 s, and the row says otherwise.
**Fix.** Schedule from the **start** of the previous poll, `next = max(startMono + liveMs, doneMono + MIN_GAP_MS)` with
`MIN_GAP_MS = 1 s`. Still no stacking (the actor is inline), and the label is true. If the lead prefers completion-based,
the strings must say "5 s after each update".

### S9. Auto flaps between two choices
**Evidence.** `recommended` is the smallest `c` with `c × 1000 ≥ 3 × p90`. A p90 hovering around 1.67 s flips Auto
5 ↔ 10 s on alternate samples; the machine is re-sent `interval` and the row's text changes while the user reads it.
**Fix.** Hysteresis: move up when `3 × p90 > c`; move down only when `3 × p90 ≤ 0.8 × lower choice` for the whole
window (or ≥ 10 consecutive samples). Test both directions.

### S10. The settings page cannot see in-memory state
**Evidence.** `WpaPages` reads only the store. The 429 floor is "in memory only" (§3.2), so `SPORTS_INTERVAL_SLOWED`
can never be shown; the fallback is visible only via the persisted `endpoint` tag, up to 60 s late.
**Fix.** Either persist a small `PollMeasure.slowedUntilFollowId` / `endpoint` with the meter (written at once on change,
not throttled), or drop the SLOWED/FALLBACK details from the row. Also "until this game ends" is wrong after a restart
(memory is lost): say "for now".

### S11. `wireBytes` from an `EventListener` needs a correlation contract
**Evidence.** §4.1/§7 put an `EventListener` in `SportsModule.kt` on the shared sports client, but the `Fetch.Ok` is built
in `classify` inside the source; nothing ties a listener callback to the `Fetch`. Note `BridgeInterceptor` strips
`Content-Length`/`Content-Encoding` after transparent gunzip, so the source cannot read the compressed size from the
response headers either.
**Fix.** In the source's own `client.newBuilder()`, add an `eventListenerFactory` that writes `responseBodyEnd(byteCount)`
into a per-call holder passed as the request `tag`; `responseBodyEnd` fires inside `response.use` on the same thread
before `classify` returns. This keeps `SportsModule.kt` out of WP-2's file set. Test with MockWebServer gzip bodies.

---

## Notes

**N1. At a 5 s floor the measurement rarely matters.** The recommendation moves off 5 s only when p90 > 1.67 s. With
completion- or start-based scheduling there is no stacking, so the factor 3 is a duty-cycle choice, not a safety one.
That is fine; say it in §3.2, so nobody tunes `METER_FACTOR` expecting it to protect anything.

**N2. Freshness gain is per play, not per second.** In my sample the per-event body changed only when a play was
logged (1–30 s gaps; one 80 s freeze). At 5 s the added latency is ≤ 5 s against ≤ ~21.5 s today, plus the 6–9 s
per-event lead (F8). That is the real "almost live" win; state it in §1.

**N3. CPU, GC, main thread at 5 s on a Stick.** 9–18 KB lexed per poll (pre/final bodies are larger than live), one
event's subtrees, one `GameSnapshot`, a queue of ≤ 26 entries: negligible next to video decode. The main thread only
works when the view changes, i.e. per play (N2), provided `ScoreBugView` equality is kept (S7). Battery is moot on
mains power. Keep D1/D2; add a `ScoreRepositoryIntervalTest` case: two identical bodies → one `bug` emission.

**N4. Rate limit.** Per-event answers are `max-age=1`, so almost every poll reaches ESPN's origin, unlike the board's
10 s edge bucket (F5). 720 requests/h per box per game at 5 s; a household with several boxes multiplies it. Ruling 32
accepted the risk at 5 s; S2 and S3 are what make it survivable. Never send a cache-busting query on ESPN either (as
§3.1 says for the NHL).

**N5. Timing paths I checked and found correct.**
- Stacking: impossible, the actor fetches inline (F13); per-event is one request.
- `NOT_FOUND_CONFIRM_MS` at fast polls: the machine already holds the retry to `first + 20 s`
  (`backoffAt = max(..., notFoundFirst + 20 s)`), so two 404s are never 5 s apart. Keep it fixed (but see S4).
- Stale/hide: `max(60 s, 2 × cadence)`, from the last `Ok`; identical bodies are `Ok`, so no false "No update".
- Back-off: 5 s base for NFL, 20 s for 429, `Retry-After` floor: fine. `failures` is shared across kinds, which is fine.
- The delay queue: anchors stay `received − Age`; ≤ 26 entries at 5 s with `QUEUE_KEEP_MS` 130 s; A-N6 holds.
- The 130 s correction window (N5 in the NFL design) is time-based and does not change with cadence. Faster polls see
  more half-updated bodies (score before play, NFL §1.6 table), which the B2 try rules already handle; add one replay of
  the fake panel's featured game at 5 s (identical consecutive bodies must not clear the try or re-flag).
- Wake/sleep/screen-off: demand NONE stops polling; a rise to SEEN polls at once when the last `Ok` is older than one
  interval (proposal: `liveMs`; use the break interval while in a break). `STUCK_WAKE_HOLD_MS` 1 s is below 5 s.
- League switch: the follow polls its own league (unchanged); the machine needs `interval` re-sent on a follow of the
  other league, as §3.5 says.

**N6. NHL unchanged-ness.** NHL 20 s is byte-identical given B1's formula (100 s) and `interval(null)`/20 s mapping to
skip 0 and the 40 s intermission. Only `maxAgeS` is added to `NhlScoreSource`. The NHL floor string is honest about the
NHL's own feed. For the record: ESPN's hockey per-event endpoint exists with `max-age=2` (§0), so the 20 s floor is a
property of the source we chose, not of hockey. Ruling 30 stands; this is only a future option.

**N7. UX.** One row for the league on screen is ruling 29. Since the row follows the Sports tab's chips, not the
followed game, add one line to its detail: "Showing NHL. Switch to NFL on the Sports tab to change the NFL's." Value
labels "Auto (every 5 s)" / "Every 10 s" and the "· Recommended" suffix are clear. MB/h: show one decimal under 10.

**N8. Settings refresh scope.** WP-3 adds `SportsFollowDomain` to `WpaPages.changes`, which rebuilds **every** section
page that WpaPages owns (Guide, Player, Playback, ...) on each follow write and meter write. Map it to
`.map { it.meters }.distinctUntilChanged()` and keep the throttle.

**N9. Fake panel.** All new routes fit inside `sports_nfl.handle` (`API_PREFIX` prefix match), so WP-4 does not need
`fakepanel.py`. Add a 403 fault (S3) and an "older body" fault (S5). Make the 400 body gzip-encoded, as ESPN's is.

---

## WP split

- **Disjoint files:** yes, given that commit 0 (lead) edits `ScoreBugMachine.kt` (stub), `ScoreRepositoryImpl.kt`
  (`FollowMachine`), `SportsContracts.kt`, `SportsPrefs.kt`, `SportsConstants.kt`, `SportsRows.kt` and `SettingsTexts.kt`
  before the WPs, and the WPs then own them. Move the `EventListener` out of `SportsModule.kt` (S11).
- **Additive contracts:** yes (`Fetch.Ok` trailing defaults, `SportsFollowDoc.meters` default, `SportsPrefs` fields
  default 0; the store is `ignoreUnknownKeys`). Add: `fetched(): Boolean` or an accepted signal (S6); the forwarding
  override (B2); the persisted slowed/endpoint state if S10 keeps those strings; `reportShown` if S7(b).
- **Ordering:** WP-3 needs `PollMeter.recommended(...)` and the estimates from WP-1. Commit 0 must fix that signature
  (pure function of `PollMeasure`, league, interval list, 429 flag) so WP-3 tests against it.
- **Missing tests:** B1 (goal after a 120 s intermission at NHL 60); B2 (production wiring); S2 (fixed + 429);
  S3 (403/HTML 404 no switch); S4 (404 cross-check); S5 (older body dropped); S6 (no `fetchDay` in a sample, cold sample
  skipped, timeouts counted); S9 (hysteresis); N3 (identical body → no extra emission); N5 (5 s replay of the featured
  game).
