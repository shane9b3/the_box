# Sports update interval: design (proposal)

Status: **proposed** 2026-11-08 (proposer, Opus). Needs a reviewer, then the lead's sign-off.

Owner request (2026-11-08, verbatim): "I would like an option for the sports for the update time between each one with
a recomended depending on how big the data is it is receiving".
Owner answers:
- One setting per league. The Sports settings row shows the value and recommendation for the league on screen
  (`SportsPrefs.league`); NHL and NFL each remember their own.
- "a recomended for the amount of time it takes to grab the data parse it and then display it, we need to have it
  start low like 2-4 seconds so its almost live without causing issue of getting parsing and displaying".

Builds on `docs/design/m6-score-bug.md` ("SB §n"), `docs/design/m6-nfl.md` ("NFL §n"), rulings 1–28 in
`docs/decisions/2026-11-05-nhl-score-bug.md`, and `main` `939f827`.

**Scope:** only the **followed game's** poll (the bug) gets the setting. The Sports tab and picker list (`DayPollPolicy`)
stay as they are (§3.6).

---

## 1. Facts

V = verified today by curl (2026-11-08, 23:48–23:52 UTC, from a Mac on home broadband; four NFL games and one NHL game
live). U = not verified.

| # | Fact | Mark | Evidence |
|---|---|---|---|
| F1 | NHL `api-web.nhle.com/v1/score/{date}`, `gamecenter/{id}/landing`, `/play-by-play`, `/boxscore` and `/right-rail` all answer `cache-control: no-transform, max-age=19, s-maxage=19` with a rising `age:` (3, 7, 16, 17, 18). `score/now` is a 307 to the dated board with the same headers. **The CDN has one new body per 20 s for every NHL endpoint we could use.** A cache miss answered `max-age=0` once | V | curl |
| F2 | NHL landing for a live game (TBL @ WPG, `2026020280`): 2 668 B raw, about 960 B gzip. The lead measured about 2 KB gzip on a later-period landing | V (size varies with the game) | curl |
| F3 | ESPN **week** board `scoreboard`: 231 515 B raw, 22 203 B gzip | V | curl |
| F4 | The NFL follow poll today reads the **dated** board `scoreboard?dates=20261108` (NFL §1.5), not the week board: 203 795 B raw, 19 708 B gzip on a Sunday with 12 games | V | curl, `EspnScoreSource.game()` |
| F5 | The dated board's `max-age` falls by 3 on every 3 s sample and wraps 1 → 10 (…2, 9, 6, 3, 10, 7, 4, 1, 8, 5…). Its content changed only on answers with `max-age` 9 or 10. So it is a **10 s cache bucket**, with no `Age` header. This explains NFL §1.1's "max-age 1..10, varies per answer" | V (pattern over 40 samples, one session) | `sample.py` log |
| F6 | **ESPN has a per-event endpoint:** `site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard/{eventId}`. It returns **one event object**, the same shape as one `events[]` entry (`id`, `date`, `competitions[0].{competitors, status, situation.lastPlay}`), without `leagues` | V | curl, live / pre / final |
| F7 | Per-event size: live 10 072–10 352 B raw, 2 450–2 551 B gzip; scheduled 18 741 B raw; final 17 493 B raw. `cache-control: max-age=1` on every answer, no `Age` | V | curl |
| F8 | Per-event **leads the dated board**: on four changes in a 2-min sample at 3 s (score 34 → 40, 40 → 42, a kick-off `lastPlay`, a clock change), per-event showed it 6–9 s before the board did | V (one game, 2 min) | `sample.py` log |
| F9 | Per-event errors: unknown id → **404 JSON** `{"code":404,"message":"No event found for eventId: …"}`, `max-age=3600`; a non-digit id → 400 JSON | V | curl |
| F10 | Round trip from here: per-event 96–337 ms, dated board 78–322 ms | V (not from a Fire TV) | `sample.py` log |
| F11 | Other ESPN candidates, not proposed: `summary?event=` 486 KB raw / 43 KB gz; `sports.core.api.espn.com/.../competitions/{id}/status` 347 B and `/situation` 612 B, but the score needs more calls (one per competitor) and the `lastPlay` is a `$ref`; `cdn.espn.com/core/nfl/scoreboard?xhr=1` 417 KB, `max-age=53`; `scoreboard?dates=…&limit=1` 15 KB (an arbitrary event, not ours) | V | curl |
| F12 | ESPN's rate limit for polling every 2–4 s from one box | **U** | none published; `Retry-After`/429 handling exists (SB §2.4) |
| F13 | Today the actor fetches inline, so at most one request is in flight (`ScoreRepositoryImpl.loop`); ESPN `game()` may make up to three sequential requests (dated, week, hinted) | V | code |
| F14 | Decoding runs on OkHttp's callback thread (`EspnScoreSource.call`; NHL reads text there too); mapping and the machine run on the actor, `@ApplicationScope` = `Dispatchers.Default`. Nothing sports-related parses on the main thread | V | code, `DispatchersModule` |
| F15 | `Fetch.Ok.receivedMono` is stamped when the **headers** arrive (`classify`), before the body is read and decoded | V | code |
| F16 | Fire TV Stick (low end) time to fetch, decode, map and publish one poll | **U** | device item D1 |

Raw curl bodies and headers are in the proposer's session scratchpad, under `curl/` (`sbEvent.json` live,
`ev_401873050.json` scheduled, `ev_401873045.json` final). The 3 s per-event vs board sampler is `scripts/sample.py`
there: `python3 -I sample.py {eventId} {count} {stepS}`. WP-2 copies the bodies into the fixtures.

---

## 2. Decisions at a glance

1. **The NFL follow poll moves to the per-event endpoint** `scoreboard/{eventId}` (F6–F9), at every cadence. It is
   8× smaller on the wire, 20× less to decode, 6–9 s fresher than the board, and needs no date. The board path stays
   as a fallback (§4.2). This changes NFL §0.1 and §1.5 and needs the reviewer's and lead's agreement.
2. **Choice lists** (`0` = Auto, stored as seconds):
   - NFL: **Auto · 2 · 3 · 4 · 5 · 10 · 15 · 20 · 30 s**.
   - NHL: **Auto · 20 · 40 · 60 s**. The NHL list **starts at its feed floor** (§3.1).
3. **Auto is the default**, and it follows the recommendation. Before any measurement: NFL 4 s, NHL 20 s.
4. **The recommendation** = the fastest choice ≥ the feed's refresh and ≥ 3 × this box's p90 work time over its last
   20 good polls (§3.2). It is persisted on the device, not backed up.
5. **The machine:** live polls run from **completion** (NFL) or stay bucket-aligned (NHL, multiples of 20 s); breaks =
   2 × live, never under 20 s; pre-game, unseen, stale, hide, NotFound and queue keep their fixed times (§3.5).
6. **Data:** the row shows "about N MB an hour", from measured wire bytes. NFL at 2 s per-event ≈ 4.5 MB/h, so no
   separate warning (§3.7).
7. **Settings:** one ADVANCED row in Sports, after the delay. Per-league prefs are additive to `SportsPrefs` and backed
   up (§5).

---

## 3. Design

### 3.1 The NHL list starts at 20 s

**Choice:** NHL offers Auto · 20 · 40 · 60 s. It offers no sub-20 s choice and no clamping.

**Reason:**
- Every NHL endpoint is in the same 20 s CDN bucket (F1). A 2–10 s poll gets the identical body 2–10 times. That is
  5–10× the requests for zero freshness.
- The bucket-aligned poll (bucket end + 1–2 s phase, SB §2.3) already gets each new body 1–2 s after the CDN has it.
  That is the "almost live" the owner asked for, and it is as live as the NHL feed allows.
- A clamped 2 s choice that really polls at 20 s is a setting that silently does nothing, and the subtitle would have
  to excuse it. An honest list is clearer.
- **Never cache-bust** (a random query to reach the origin). It is abusive to an unofficial feed and would get the app
  blocked.
- Multiples of 20 keep the phase alignment exact (§3.5).

The row's detail says why: "The NHL feed changes every 20 s, so checking more often would get the same score."

### 3.2 The recommendation

Per league, a pure `PollMeter` in `:core` keeps the last `METER_WINDOW = 20` **good** polls (an `Ok` that the machine
accepted). Each sample holds:
- `workMs` = the actor's mono time from just before `source.game(...)` to just after `machine.fetched()`, `step()` and
  `publish()`. That covers fetch, decode, map, the machine and the StateFlow write. The bug's own draw is a single
  node (SB §4.7) and is not timed; its frame time is device item D2.
- `wireBytes`: the compressed body bytes, from an OkHttp `EventListener.responseBodyEnd` on the sports client.
- `maxAgeS`: `Cache-Control` `s-maxage`, else `max-age`.

Derived values:
- `p90Ms`: the nearest-rank p90 of `workMs`. A median would hide a slow box's spikes, and the spikes are what make
  polls late.
- `feedRefreshS` = ceil(max `maxAgeS` in the window), at least 1. This gives NHL 19 → 20, the ESPN board 10, and
  per-event 1 (F1, F5, F7).
- **`recommended`** = the smallest choice `c` in the league's list with `c ≥ feedRefreshS` and
  `c × 1000 ≥ METER_FACTOR (3) × p90Ms`.
  - Work then takes at most a third of each interval, so a GC pause or a Wi-Fi retry up to 3× the usual doesn't push
    the next poll late.
  - Stacking is impossible either way (§3.4).
  - Examples: a 0.4 s p90 on per-event gives 2 s; a 1.2 s p90 (a slow Stick) gives 4 s; a 2 s p90 gives 10 s.
  - Nothing faster than the list's first entry is ever recommended.
- **Before `METER_MIN = 5` samples**, the default is NFL 4 s (the top of the owner's 2–4 s range, safe on an unknown
  box) and NHL 20 s.
- **After a 429** (ESPN asking us to slow down), Auto's floor rises to `AUTO_429_FLOOR_S = 10` for the rest of that
  follow. This lives in memory only.
- **Endpoint change resets the window.** Each meter carries an `endpoint` tag (`"nfl-event"`, `"nfl-board"`,
  `"nhl-landing"`). On the board fallback (§4.2) `feedRefreshS` becomes 10 and the recommendation follows it.

**Where it lives:** in memory in the repository's actor. It is persisted to `SportsFollowDoc.meters` (the
`sports_follow` domain: **device-local, never backed up**, SB §2.6) at most once per `METER_PERSIST_MS = 60 s`, and
when the follow ends.
- Persisted, because the settings row is usually opened when nothing is polling. Without it, the row would show the
  default after every restart.
- Not backed up, because the number describes this box and this network. A restore onto a Stick must not inherit a
  Shield's 2 s.

### 3.3 What the setting does

- **Auto** = `recommended` (live, as the meter updates).
- **A fixed choice** = that value, exactly. If it is faster than `recommended`, the row says so (§5.2) but the choice
  is obeyed. Safety does not depend on it (§3.4).
- **A stored value not in the list** (a restore from a newer build) is read as the next listed value above it, else
  Auto.

### 3.4 Safety at fast cadences

| Rule | How |
|---|---|
| One poll in flight | Unchanged: the actor fetches inline (F13). Per-event is one request, and the board fallback stays sequential |
| Next poll from completion | **New for the NFL:** the machine records `doneMono = mono.nowMs()` in `fetched()` (after the decode, F15) and schedules `doneMono + interval`. A 3 s poll at a 2 s interval waits 2 s more, never 0. The anchor for the delay queue stays `receivedMono − Age` |
| Back-off | `SportsTiming.backoffMs` takes a base. NHL: 20 s (unchanged). NFL: `BACKOFF_FAST_BASE_MS = 5 s` (5, 10, 20, 40, 80, 160, 300 s, ±20 %), so one dropped packet doesn't freeze a 2 s bug for 20 s. A **429 always uses the 20 s base** and `Retry-After` stays a floor (C-S4), plus the Auto floor in §3.2 |
| Off the main thread | Unchanged (F14). No new work enters the main thread; the meter runs on the actor |
| Call timeout | Stays 10 s. A slow poll only delays the next one |
| Wake-up guard | `STUCK_WAKE_HOLD_MS` (1 s) is below the 2 s floor, so it is unchanged |

**Allocation and GC (the 235 KB question).**
- On the board, every poll lexes about 200 KB of UTF-8 and builds `JsonElement` subtrees (`status`, `situation`,
  `team`, `score`) for 12–15 events. `ignoreUnknownKeys` skips `leaders` and `odds` without building them, but they
  are still read byte by byte.
- Per-event cuts that to about 10 KB lexed and one event's subtrees: about 20× fewer bytes and 12–15× fewer trees
  (F4, F7).
- At 2 s that is about 5 KB/s of JSON, which is negligible on any box. This is the main reason for decision 1.
- No allocation figure for a Stick is claimed (F16). The meter measures it in effect (decode time is in `workMs`), and
  D1 checks it.
- The machine's delay queue holds `QUEUE_KEEP_MS / interval` entries: 65 at 2 s, each one small snapshot. No change
  is needed.

### 3.5 Machine timing: what scales and what stays

`ScoreBugMachine` gets one input, `interval(liveMs: Long?)` (null = today's behaviour, so every existing NHL test is
unchanged). The repository sends it on start, on a pref change, on a follow of the other league, and when Auto's value
moves.

| Item | Now | Proposed | Reason |
|---|---|---|---|
| LIVE poll, NHL | next bucket end + phase | `nextAligned(skip = liveMs / 20 s − 1)`: 20 s → 0, 40 → 1, 60 → 2 | The bucket alignment stays exact; this is why the NHL choices are multiples of 20 |
| LIVE poll, NFL | `received + 20 s + phase` (≈ 21.5 s; no `Age`) | `doneMono + liveMs`, with no alignment | Per-event has `max-age=1` (F7): there is no bucket to align to |
| INTERMISSION (breaks, halftime) | skip one bucket (40 s) | **2 × live, never under `INTERMISSION_MIN_MS = 20 s`**. NHL 20 → 40 (unchanged), 40 → 80, 60 → 120. NFL 2–10 → 20, 15 → 30 | Nothing moves in a break except its end. 20 s still catches the restart of an NFL quarter break (about 2 min) promptly |
| PRE (`PRE_WAKE_MS` 2 min, `PRE_POLL_MS` 40 s) | fixed | **fixed** | It only detects the start; nothing is drawn before puck drop or kick-off (ruling 4). The NFL coin-toss PRE already polls at the live cadence (lead sign-off 1), so it follows `liveMs` |
| `POLL_UNSEEN_MS` 120 s | fixed | **fixed** | The setting is for a bug on screen. Unseen polling is about staying followed, not freshness |
| Rise-to-SEEN "old" test (`CDN_BUCKET_MS`) | 20 s | `liveMs` (NHL: 20 s, unchanged) | Poll at once when the last answer is older than one interval |
| `STALE_AFTER_MS` 60 s, as `max(60 s, 2 × cadence)` | fixed floor | **floor fixed**; `cadence` = the league's live or break interval | Stale means "no `Ok`", not "no change". At 2 s with the 5 s back-off, 60 s is about five failed polls. A shorter floor would flash "No update" on one bad patch of Wi-Fi |
| `HIDE_AFTER_MS` 300 s | fixed | **fixed** | An outage timescale, not a poll timescale |
| `NOT_FOUND_CONFIRM_MS` 20 s | fixed | **fixed** | A time window against a transient 404 from a CDN edge. Two 404s 2 s apart are the same glitch, so scaling it down would drop follows on a blip |
| `QUEUE_KEEP_MS` 130 s | fixed | **fixed** | It covers the 120 s maximum delay plus one 10 s step. Above 10 s intervals, A-N6 (the newest qualifying snapshot is never pruned) already covers it |
| Day list (`DAY_POLL_LIVE_MS` 30 s, idle 5 min) | fixed | **fixed** (§3.6) | |
| NHL phase (`POLL_PHASE_MS` 1–2 s) | per install | unchanged, NHL only | |

**The user's delay (`delaySec`, 0–120 s)** is independent. It shifts what is shown; the interval sets how often a
snapshot enters the queue.
- A shorter interval gives finer steps on the delayed timeline: a 15 s delay at 2 s shows a change 15–17 s after ESPN
  had it, against 15–37 s today.
- The recommendation does not look at the delay, and the delay does not change the cadence.

### 3.6 The Sports tab list stays at 30 s

The tab and picker poll the whole day (NHL) or week (NFL) board (F3: 22 KB gzip, about 230 KB to decode). The owner's
"almost live" is about the bug.
- At 2–4 s the board would cost 20–40 MB/h, plus the decode on a Stick, for a browse screen.
- It stays `DayPollPolicy` as is. This is open question Q3.

### 3.7 Data use

Shown in the row's detail:

> about `3600 / interval × median(wireBytes)` MB an hour while a game is live

Before a measurement, it uses `EST_WIRE_BYTES`: NHL 1 500, NFL 2 500 (F2, F7). Headers are not counted, and the row
says "about".

| League · interval | Per poll (gzip) | Per hour |
|---|---|---|
| NHL · 20 s | ≈ 1–2 KB | ≈ 0.2–0.4 MB |
| NFL · 2 s, per-event | ≈ 2.5 KB | ≈ 4.5 MB |
| NFL · 4 s, per-event | ≈ 2.5 KB | ≈ 2.3 MB |
| NFL · 21.5 s, dated board (today) | ≈ 19.7 KB | ≈ 3.3 MB |
| NFL · 2 s, board fallback | ≈ 19.7 KB | ≈ 35 MB |

**No data warning dialog.** At 2 s per-event costs about what the bug costs today, and far less than the video it sits
on.
- On the board fallback, the feed refresh is 10 s, so Auto never goes under 10 s (about 7 MB/h).
- A fixed 2–5 s choice on the fallback shows the fallback line in the row's detail (§5.2), with the MB/h figure.

---

## 4. Data layer

### 4.1 Contracts (additive, defaulted)

- `Fetch.Ok`: trailing `maxAgeS: Int? = null` and `wireBytes: Long? = null`. Both sources fill `maxAgeS`, parsed from
  `Cache-Control` (`s-maxage` first). `wireBytes` comes from the client's `EventListener`.
- `SportsFollowDoc`: trailing `meters: Map<League, PollMeasure> = emptyMap()`.
- `PollMeasure(endpoint: String, workMs: List<Int>, wireBytes: List<Int>, maxAgeS: Int?)`: at most 20 of each.
- `FollowMachine.interval(liveMs: Long?)`: a default no-op body, so the loop's test fakes compile unchanged.

### 4.2 `EspnScoreSource.game(id, hint)`

1. Per-event: `GET scoreboard/{id}`. Decode `EspnEventDto` by streaming (it is the same DTO). Wrap it as
   `EspnGameDto(events = listOf(it))` and map with the unchanged `EspnMapper.game`. Remember its ET date as today.
2. A 404 JSON is `NotFound` (F9), so the two-NotFound rule removes a deleted game as before.
3. **Fallback:** two consecutive per-event answers that are `HTTP_OTHER` or `BAD_BODY` (an HTML 404, a 400, a changed
   shape) switch this follow to today's dated-board path (NFL §1.5), unchanged, until the follow ends.
   - 429, 5xx, IO and timeouts never switch; the back-off handles them.
   - The meter's `endpoint` tag changes, which resets its window (§3.2).
4. The body cap stays `ESPN_MAX_BODY_BYTES`.

The NHL source changes only by filling `maxAgeS`. Its endpoints and parsing stay as they are.

### 4.3 `ScoreRepositoryImpl`

- One `PollMeter` per league. It times each follow poll (§3.2) and adds the sample on an accepted `Ok` only.
- It resolves the effective interval (pref → Auto → the 429 floor) and calls `machine.interval(...)`.
- It persists the meters (§3.2).
- The day list is untouched.

---

## 5. Settings

> **CHL amendment (2026-11-14, `docs/design/m6-chl-leagues.md` §2.5, §6.5; lead P1, L4).** One CHL setting shared by OHL, WHL and QMJHL
> (`SportsPrefs.chlIntervalS`): Auto · 5 · 10 · 15 · 20 · 30 s after each update, Auto never under 5 s. The row is titled
> with the group ("CHL score updates") and, when a CHL game is followed, shows and runs **the followed league's** meter.
> The MB/h estimate before a measurement is `EST_WIRE_BYTES_CHL = 4 000` B a poll; the CHL's samples count every request's and
> answer's headers too (six HTTP/1.1 Firebase exchanges with `If-None-Match`, 304s included), about 3 MB an hour at 5 s.
> There is no "other league" line any more.

### 5.1 Where

- Sports section, **ADVANCED** (lead ruling on U-B2: every Sports row is advanced; the simple count stays 35).
- Row `sports.interval`, after **Score bug delay**, with `EffectTiming.LIVE`.
- It shows the league in `SportsPrefs.league` (the Sports tab's chips). The league is switched there, not in this row.
- No quick-bar item.

Prefs (additive, **backed up**, as the rest of `sports`):

```kotlin
val nhlIntervalS: Int = 0,   // 0 = Auto; else one of SportsConstants.INTERVAL_CHOICES_NHL_S (20, 40, 60)
val nflIntervalS: Int = 0,   // 0 = Auto; else one of INTERVAL_CHOICES_NFL_S (2, 3, 4, 5, 10, 15, 20, 30)
```

The user's choice is backed up; the measurement is not (§3.2). Auto restored onto another box re-measures there.

### 5.2 Strings (new `SettingsText` keys → `R.string.settings_sports_interval_*`)

| Key | English |
|---|---|
| `SPORTS_INTERVAL` (title) | "%1$s score updates" (NHL / NFL) |
| `SPORTS_INTERVAL_AUTO` (value, option) | "Auto (every %1$d s)" |
| `SPORTS_INTERVAL_EVERY` (value, option) | "Every %1$d s" |
| `SPORTS_INTERVAL_RECOMMENDED` (option suffix) | "%1$s · Recommended" |
| `SPORTS_INTERVAL_DESC` (detail, measured) | "Recommended: every %1$d s. This box takes about %2$s s to get, read and show an update. About %3$s MB an hour while a game is live." |
| `SPORTS_INTERVAL_DESC_UNMEASURED` | "Recommended: every %1$d s for now. Follow a game and this box will time a few updates. About %2$s MB an hour while a game is live." |
| `SPORTS_INTERVAL_NHL_FLOOR` (appended for the NHL) | "The NHL feed changes every 20 s, so checking more often would get the same score." |
| `SPORTS_INTERVAL_FASTER` (fixed < recommended) | "This is faster than this box keeps up with, so updates may arrive late. Choose Auto to use the recommendation." |
| `SPORTS_INTERVAL_SLOWED` (after a 429) | "ESPN asked the app to check less often, so Auto checks every 10 s until this game ends." |
| `SPORTS_INTERVAL_FALLBACK` | "ESPN's single-game feed isn't answering, so each check downloads the whole day (about %1$s KB). Auto checks every 10 s or slower." |

- **Row value:** "Auto (every 3 s)" or "Every 5 s".
- **Options:** the recommended one carries the suffix, e.g. "Every 3 s · Recommended".
- **Formatting:** work seconds use one decimal; MB below 1 uses one decimal, otherwise none.
- **Refresh:** the page observes `SportsFollowDomain` as well as `SportsDomain` (`WpaPages`, line 166), so the
  recommendation refreshes. Meter writes are at most once a minute.

---

## 6. Fake panel

- **`sports_nfl.py`**:
  - New route `GET /apis/site/v2/sports/football/nfl/scoreboard/{id}`. It returns the `event()` object at the request
    instant, with `Cache-Control: max-age=1`.
  - Unknown digits → 404 JSON `{"code":404,"message":"No event found for eventId: …"}`; non-digits → 400 JSON (F9).
  - Faults:
    - `event_status` (per-event route only; to test the fallback);
    - `max_age` (overrides the header);
    - `stall_ms` (a fractional stall, to fake a slow box: `stall_ms: 1500` should move Auto to 5 s).
  - The board routes are unchanged, so the golden is unchanged.
- **`sports.py`:** the `stall_ms` and `max_age` faults only. The bodies and the 20 s buckets stay as they are.
- **`/admin/stats` `sports.log`:** already has a `unix` per request. The tests and the emulator checklist read the gaps
  between polls from it.
- **README:** the new route and the faults.
- **Note:** the fake panel does not gzip, so `wireBytes` equals the raw size there, and the MB/h figure reads higher
  than in the field.

---

## 7. Work packages (disjoint files)

**Commit 0 (lead, Opus).**
- The contracts of §4.1.
- `SportsConstants`: `INTERVAL_CHOICES_NHL_S`, `INTERVAL_CHOICES_NFL_S`, `INTERVAL_DEFAULT_NHL_S = 20`,
  `INTERVAL_DEFAULT_NFL_S = 4`, `METER_WINDOW = 20`, `METER_MIN = 5`, `METER_FACTOR = 3`, `METER_PERSIST_MS = 60 000`,
  `BACKOFF_FAST_BASE_MS = 5 000`, `INTERMISSION_MIN_MS = 20 000`, `AUTO_429_FLOOR_S = 10`, `EST_WIRE_BYTES_NHL`,
  `EST_WIRE_BYTES_NFL`.
- `SportsPrefs` fields; a `PollMeter` signature stub; the `SettingsText` keys and `SportsRows.INTERVAL`.
- Gate: `./gradlew :core:test :ui:testDebugUnitTest :data:testDebugUnitTest :app:assembleDebug lintAll`.

**WP-1, core timing (Opus).**
- Files: `ScoreBugMachine.kt`, `DayPollPolicy.kt` (`SportsTiming.backoffMs(base)`), `PollMeter.kt`.
- Tests, in new files `ScoreBugMachineIntervalTest`, `PollMeterTest`, `SportsTimingBackoffTest`:
  - `interval(null)` keeps every existing case byte-identical;
  - NHL 20/40/60 aligned and phase-exact;
  - NFL next = done + interval, including a poll slower than the interval;
  - breaks at 2× with the 20 s floor;
  - NFL 5 s back-off steps, and a 429 on the 20 s base with `Retry-After` as a floor;
  - the rise-to-SEEN threshold = interval;
  - stale = max(60 s, 2 × cadence) at 2 s;
  - pre-game and unseen unchanged;
  - the meter: nearest-rank p90, `feedRefresh` from max-age, `recommended` (each example in §3.2), fewer than 5
    samples → default, the 429 floor, an endpoint change resets, a value not in the list.

**WP-2, data (Opus).**
- Files: `EspnScoreSource.kt`, `NhlScoreSource.kt` (`maxAgeS` only), `ScoreRepositoryImpl.kt`, `SportsModule.kt`
  (`EventListener`).
- Tests, in new files `EspnEventSourceTest`, `ScoreRepositoryIntervalTest`:
  - the per-event decode of the live, pre and final fixtures (copied from the scratchpad bodies into
    `core/src/test/resources/sports/nfl/event-*.json`), mapped the same as the board's event;
  - 404 JSON → NotFound; the fallback after two HTML 404s or 400s, never after 429 or 5xx;
  - `maxAgeS` parse;
  - the work timing sample and the persist throttle; Auto resolution; one request in flight under a stalled source;
  - the existing `EspnScoreSourceTest` stays green (the board path is unchanged).

**WP-3, settings (Sonnet).**
- Files: `WpaPages.kt` (the Sports rows and the `SportsFollowDomain` observe), `SettingsTexts.kt`, `SportsRows.kt`,
  `ResourceSettingsTexts.kt`, `values/strings.xml`.
- Tests:
  - the row per league (value, the recommended suffix, each detail variant);
  - the intents write `nhlIntervalS` / `nflIntervalS`;
  - `SimpleRowCountTest` unchanged at 35;
  - backup round-trip of the new prefs with defaults.

**WP-4, fake panel (Sonnet).**
- Files: `tools/fakepanel/sports_nfl.py`, `sports.py`, `test_sports_nfl.py`, `test_sports.py`, `README.md`.
- Tests: the route, 404/400 JSON, the faults, `max-age=1`, and the board golden unchanged.

**Integration (lead).**
- Emulator, with the fake panel `--sports --sports-nfl`, follow KC @ BUF:
  - the log gaps match Auto;
  - `stall_ms: 1500` moves Auto to 5 s;
  - `event_status: 404` with `html_404` switches to the board and the row's fallback detail;
  - `status: 429` → 10 s and the slowed detail;
  - NHL gaps at 20/40/60 s.
- Then a code review pass.

**Device items.**
- D1: a Fire TV Stick's p90 work time on per-event and on the board (from the log of the meter).
- D2: frame time while the bug updates every 2 s.
- D3: whether ESPN 429s a box at 2 s over a whole game.
- D4: per-event at halftime and in OT (not yet seen).

---

## 8. Open questions for the owner

- **Q1. The NHL list starts at 20 s, not 2–4 s.** The NHL feed itself only changes every 20 s (verified). Faster
  checks would download the same score again. Is the honest 20 / 40 / 60 s list OK?
- **Q2. Auto by default.** The NFL starts at 4 s on a new box, then moves to what the box can do (likely 2–3 s on a
  good box, 4–5 s on a slow Stick). Or should the default be a fixed 2 s?
- **Q3. The Sports tab's list keeps its 30 s refresh** (it downloads the whole week). Is the faster setting only for
  the score bug OK?
- **Q4. Polling ESPN every 2–4 s** is about 900–1 800 requests an hour per box to an unofficial API. The app slows
  itself on a "too many requests" answer, but ESPN's limits are unknown. Is that risk acceptable?

**For the reviewer and the lead** (not the owner): decision 1 (per-event replaces the dated board for the follow)
reverses NFL §0.1 / §1.5. The evidence is F6–F9, taken from one session.

## Lead sign-off (2026-11-08)
Owner rulings 29–32 (docs/decisions/2026-11-05-nhl-score-bug.md) override the proposal: **NFL floor 5 s** (choices
Auto · 5 · 10 · 15 · 20 · 30 s; Auto never under 5 s, default before measurement 5 s), NHL Auto · 20 · 40 · 60 s, new
installs on Auto. Review: docs/review/2026-11-08-sports-update-interval-review.md.
- **Accepted:** the per-event NFL follow endpoint with the board as fallback; B1 (scale `GOAL_FRESH_MS` with the break
  interval, unchanged at NHL 20 s); B2 (commit 0 forwards the interval through `FollowMachine.of`, with a test on the real
  wiring); S1–S11 as the review states them. For S7: the strings say "fetch and read" unless a `reportShown` hook is
  added; never a timestamp in `ScoreBugView`. For S10: the row shows the slowed/fallback state if it is cheap to expose
  from the repository; otherwise note it.
- **Lead probe of the NHL feed (TBL–WPG live, 2026-11-08 ~23:50 UTC, 1.5 s polls for 220 s):** the NHL origin itself
  changes about every 15 s (game clock steps 17:22 → 17:07 → 16:51 → 16:36 at ~14.5 s wall intervals); the CDN copy we
  read (`max-age=19`) lags the origin by 0–20 s. A cache-busting query reaches the origin but bypasses the NHL's CDN for
  every box, so it stays ruled out. ESPN's NHL per-event endpoint (`/hockey/nhl/scoreboard/{id}`, 2.3 KB gzip,
  `max-age=1–3`) was sometimes ahead of the NHL CDN and sometimes 10+ s behind the origin; not adopted here (a different
  data source for the NHL is an owner question).
- Fixtures: copy `scratchpad/curl/{sbEvent,ev_401873050,ev_401873045}.json` into `core/src/test/resources/sports/nfl/`
  in commit 0.

## Implementation (2026-11-08)

Implemented on `main` (uncommitted, for the lead) in the order of §7: commit 0, WP-1, WP-2, WP-3, WP-4, then the
integration checks. Rulings 29–32 and the lead sign-off win over the proposal text above.

**Commit 0 (contracts, additive with defaults).**
- `Fetch.Ok` gains `maxAgeS: Int? = null`, `wireBytes: Long? = null`. `ScoreSource.followEndpoint(id)` (default: the
  league name). `SportsFollowDoc.meters: Map<League, PollMeasure> = emptyMap()` (device-only, not backed up).
  `PollMeasure(endpoint, workMs, wireBytes, maxAgeS, recommendedS, downStreak, slowed)`.
- `SportsPrefs.nhlIntervalS` / `nflIntervalS` (0 = Auto, backed up with the rest of `sports`).
- `SportsConstants`: NFL **Auto · 5 · 10 · 15 · 20 · 30 s**, NHL **Auto · 20 · 40 · 60 s**, defaults 5 / 20 s,
  `METER_WINDOW` 20, `METER_MIN` **10** (S6), `METER_FACTOR` 3, hysteresis `METER_DOWN_SHARE` 0.8 ×
  `METER_DOWN_STREAK` 10 (S9), `METER_FAIL_CAP_MS` 10 s, `METER_PERSIST_MS` 60 s, `BACKOFF_FAST_BASE_MS` 5 s,
  `INTERMISSION_MIN_MS` 20 s, `AUTO_429_FLOOR_S` 10, `EST_WIRE_BYTES_*`, `PER_EVENT_RETRY_MS` 10 min,
  `PER_EVENT_SWITCH_AFTER` 2.
- B2: `FollowMachine.interval(liveMs)` has a default no-op for the loop's fakes **and** `FollowMachine.of` forwards it;
  `ScoreRepositoryIntervalTest` runs the real machine through `FollowMachine.of` and asserts 5.3 s gaps (5 s + the 0.3 s
  poll), not 21.5 s.

**WP-1, core timing** (`ScoreBugMachine`, `DayPollPolicy`/`SportsTiming`, new `PollMeter`).
- `ScoreBugMachine.interval(liveMs)`; null = today's timing. NHL: live skips `liveMs / 20 s − 1` buckets, breaks
  `max(2 × live, 20 s)` aligned. NFL at a set interval: **from completion** (`doneMono` stamped in `fetched()` after the
  decode) for live, the coin-toss PRE and breaks; the fast back-off (5 s base; a 429 keeps 20 s with `Retry-After` as a
  floor). Rise-to-SEEN threshold and the stale cadence follow the interval; the 60 s stale floor, PRE, UNSEEN,
  NotFound, hide and the queue are unchanged.
- B1: the goal window is `max(100 s, 2 × break + 20 s)` (NHL 20 s: 100 s, byte-identical; NHL 60 s: 260 s).
- S5: at a set NFL interval an older body (a lower `lastPlay.id`, or with equal/no ids an earlier period/clock at an
  equal or lower score sum, both in play) is a no-op `Ok`: it resets stale and the schedule but is never shown.
- `PollMeter` is pure: nearest-rank p90 (≥ 10 samples), feed refresh = the largest `max-age` on the endpoint, the
  recommendation (smallest choice ≥ feed refresh with `c × 1000 ≥ 3 × p90`; unmeasured = the default raised to the feed
  refresh), hysteresis, `normalize` (a stored value not in the list → the next above, else Auto), `effectiveS` (the
  choice, Auto = the recommendation, NFL raised to 10 s while slowed, S2: every choice), MB/h from the median wire bytes.

**WP-2, data** (`EspnScoreSource`, `NhlScoreSource`, `ScoreRepositoryImpl`, new `WireMeter`).
- The NFL follow reads `scoreboard/{id}` (`EspnScoreSource.production`, used by `SportsModule`; the internal
  constructor's `perEvent = false` keeps the board-only transport tests unchanged). The body is decoded with the same
  DTO and mapper. S3: only a *shape* failure (a 200 that does not decode or names another event, a JSON 400) counts;
  two in a row switch the follow to the dated board at once; per-event is retried every 10 min (one more shape failure
  goes back). A 403 is reported as a 429 (slow-down, no switch). HTML 404, 30x, 429, 5xx, IO and timeouts never switch.
  S4: a JSON 404 reads the dated board once; absent there too = `NotFound`, present = the board's snapshot (counted
  as a shape failure). The fallback state is per follow (a new id starts on per-event).
- S11: `WireMeter` adds an `EventListener` on each source's own `newBuilder()`, chained after the shared client's
  (BoxDns); each request carries a `WireBytes` tag that `responseBodyEnd` fills with the compressed count. The ESPN
  source drains the stream after the decode so the count is the whole body. `maxAgeS` = `s-maxage`, else `max-age`.
- The repository times `source.game` → `fetched` → `step` → `view()` (never `fetchDay`, S6), skips each league's
  first sample of the process, adds timeouts/IO at their elapsed time (cap 10 s), marks the NFL follow `slowed` on a
  429/403, persists at most once a minute (at once on the first sample, a slowed change or an endpoint change, and when
  a follow ends; `slowed` clears with the follow), and re-sends the interval on start, a pref change, a follow change and
  when Auto moves. `fetched()` still returns `Unit`; the repo checks the followed id instead (S6).

**WP-3, settings** (`SportsRows.INTERVAL`, `WpaPages`, `SettingsTexts`, `ResourceSettingsTexts`, `strings_sports.xml`).
- One ADVANCED, LIVE row after **Score bug delay**, for `SportsPrefs.league`. Title "NHL score updates" /
  "NFL score updates". NHL options read "Every 20 s"; NFL options "5 s after each update" (S8: the lead kept
  completion-based scheduling, so the label says so). The recommended option carries "· Recommended"; Auto reads
  "Auto (every 20 s)" / "Auto (5 s after each update)".
- Detail: "Recommended: N s. This box takes about X s to fetch and read an update. About Y MB an hour while a game is
  live." (S7 option (a): "fetch and read", no `reportShown`), or the unmeasured line; then the NHL floor line, the
  faster-than-recommended line, the slowed line ("for now", S10), the fallback line with the board's KB, and the
  league line (N7). MB/h: one decimal under 10 (N7). Slowed and fallback are cheap to show because the repository
  persists them at once with the meter (S10).
- N8: `WpaPages.changes` observes only `SportsFollowDomain.meters` (distinct), not every follow write.
- Added at integration: a measured p90 under 0.1 s reads "under 0.1 s" (`SPORTS_INTERVAL_DESC_FAST`; the emulator showed
  "about 0.0 s"). The settings row view (`SettingsRowViews`) lets the **focused** interval row wrap its detail to 4 lines
  and grow (with 14 px top/bottom padding); every other row keeps its one-line description and fixed height.

**WP-4, fake panel.** `scoreboard/{id}` (one event, `max-age=1`; unknown digits 404 JSON `max-age=3600`; non-digits
gzip 400 JSON `max-age=3019`); NFL faults `event_status`, `event_older_s` (S5), and `stall_ms`/`max_age` on both
leagues; NFL API requests are noted in `/admin/stats` `sports.log`. Board bodies and the NHL golden are unchanged.

**Tests added.** `PollMeterTest` (13), `ScoreBugMachineIntervalTest` (14; includes a trace test playing one scripted
NHL session on `interval(null)` and `interval(20 s)` and comparing every poll, wake-up and view), `SportsTimingBackoffTest`
(3), `SportsIntervalRowTest` (9), `EspnEventSourceTest` (11), `ScoreRepositoryIntervalTest` (11), Python
`TestPerEvent` (10) and `test_stall_ms_and_max_age`. Changed: `SettingsBackLadderTest` (the Sports row list gains
`sports.interval`). Every other existing test is unchanged.

**Gate** (`lintAll :core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest
:app:testDebugUnitTest :app:assembleDebug`): green, lint no new issues. Final run after the UI
fixes: core 4,356 / data 542 (3 Samba skipped) / player 511 / ui 648 / app 73. Fake panel: `test_sports*.py` 73 OK, `test_fakepanel.py` 145 OK.

**Emulator** (emulator-5554, `fakepanel.py --port 8080 --no-pace --sports --sports-nfl`, `adb install -r`):
- Settings › Sports (advanced): "NHL score updates · Auto (every 20 s)", options Auto / "Every 20 s · Recommended" /
  40 / 60, detail "Recommended: 20 s for now … About 0.3 MB an hour …", NHL floor and league lines. With the Sports tab
  on NFL: "NFL score updates · Auto (5 s after each update)", options Auto / "5 s after each update · Recommended" / 10
  / 15 / 20 / 30, measured detail "Recommended: 5 s. This box takes under 0.1 s to fetch and read an update. About
  1.7 MB an hour …" (the panel does not gzip, so MB/h reads high).
- NHL follow (TOR–BOS, Auto = 20 s), fake-panel log: landing polls at 10.2 s (to the first bucket end), then 20.2,
  20.2, 20.2, 20.2, 19.2 s: unchanged bucket alignment.
- NFL follow (KC @ BUF, Auto = 5 s): only `scoreboard/401899001` per-event polls, gaps 5.02–5.09 s (median 5.03, n = 16);
  the 4 board reads in that window are the Sports tab's 30 s week list.
- Fault: `event_status: 400` → two 400s (the second after the 5 s back-off), then the follow reads the dated board at
  once and stays there. The fake board says `max-age=5`, so Auto stays at 5 s on it; ESPN's board (10 s bucket) gives
  10 s.
- Screenshots: the session scratchpad `interval/` folder (`nhl-row-focused.png`, `nhl-row-options.png`,
  `nfl-row-focused.png`, `nfl-row-options.png`, `nfl-followed.png`, `nhl-followed.png`); gaps in `nhl-gaps.txt`,
  `nfl-gaps.txt`, `fault-log.txt`.

**Deferred / notes for the lead.**
- Not run on the emulator: `stall_ms: 1500` → Auto 10 s, the 429 slowed detail, NHL 40/60 s (all covered by unit
  tests: `ScoreRepositoryIntervalTest.aSlowBoxMovesAutoUp`, `…aFixedFiveSecondChoiceAfterA429…`,
  `ScoreBugMachineIntervalTest.nhlFortyAndSixty…`). Note `stall_ms: 1500` gives p90 ≈ 1.5 s → 3 × 1.5 = 4.5 s ≤ 5 s,
  so Auto stays at 5 s; it takes ≥ 1.67 s to reach 10 s (review N1).
- S8: completion-based scheduling kept (per the lead's brief); the NFL labels say "N s after each update".
- S7: option (a), "fetch and read"; no `reportShown`.
- S5 guard: with equal `lastPlay.id`s, a clock corrected upward is treated as older and held until the next play.
- The rise-to-SEEN threshold uses the live interval in breaks too (N5 suggested the break interval), so NHL 20 s stays
  byte-identical.
- `fetched()` still returns `Unit`; the repository compares the followed id instead (S6's alternative), so the loop's
  test fakes are unchanged.
- The fixtures kept their names (`sbEvent.json`, `ev_401873050.json`, `ev_401873045.json`), not `event-*.json`.
- Device items D1–D4 stay open (Stick p90, frame time at 5 s, ESPN 429s over a game, per-event at halftime/OT).

## Fix pass (2026-11-08)

Code review of e62169e: nothing serious; the lead accepted five items. Each fix has a unit test that fails with the fix
reverted. NHL behaviour is unchanged (the NHL source, its meter and the NHL machine paths are untouched).

1. **The board fallback ended with the follow, but its meter did not.** `ScoreRepositoryImpl.persist` now also resets
   the NFL meter from `nfl-board` to a fresh `nfl-event` window on any follow change, and `start()` does the same for a
   meter the last process left on the board (a new process's ESPN source always starts a follow on per-event); slowed is
   kept across a restart mid-follow, cleared with the follow as before. So the row's fallback line, Auto's 10 s and the
   next follow's first gap go with the follow. A sample on a fresh (empty) meter is persisted at once, as the first one
   always was. Tests: `ScoreRepositoryIntervalTest.aFollowThatEndsOnTheBoardLeavesNoFallbackBehind` (meter reset, the
   next follow polls every 5 s from its first gap), `…aBoardMeterFromTheLastProcessIsResetAtStart`.
2. **Only a real switch moves the meter.** `PollMeter.add` drops a *failed* sample on another endpoint (the window and
   the hysteresis stay); only a good `Ok` there starts a fresh window. `ScoreSource.followEndpoint` may now return null:
   the ESPN source returns null after the one-off S4 board check (a per-event JSON 404 read against the board) unless that
   check is the second shape failure that switches; the repository takes no sample (Ok or timeout) for a null endpoint,
   while a 429 still marks slowed. Tests: `PollMeterTest.aFailureOnAnotherEndpointLeavesTheWindowAndTheHysteresis`,
   `EspnEventSourceTest.theOneOffBoardCheckIsNotABoardSample`, `ScoreRepositoryIntervalTest.aOneOffCheckOnAnotherEndpointIsNotASample`.
3. **A 403 on the board path is a 429 too.** `EspnScoreSource.gameFrom` (the follow's board reads, both on the fallback
   and in the board-only source) maps 403 → `HTTP_429` with its `Retry-After`, as `eventGame` does, so the 20 s base, the
   10 s floor and "slowed" apply. The day list (`today`/`day`) keeps 403 = `HTTP_OTHER`. Test:
   `EspnEventSourceTest.a403OnTheBoardFallbackIsA429`.
4. **The focused interval row's detail wraps to 6 lines** (`INTERVAL_DESC_LINES`, now behind `descLines(row, focused)`;
   every other row and the unfocused interval row stay one line), and `WpaPages.intervalRow` drops the league line when
   the slowed or the fallback line shows. Tests: `IntervalRowLinesTest` (ui), `SportsIntervalRowTest.theLeagueLineGivesWayToTheSlowedAndFallbackLines`.
5. **The S5 older-body hold is bounded at 30 s** (`SportsConstants.NFL_OLDER_HOLD_MAX_MS`), timed from the first of a run
   of held bodies; after that, an older body whose score or phase differs from the held one is accepted (ESPN's
   correction, e.g. a deleted overturned TD sending `lastPlay.id` back down); one with the same score and phase stays a
   no-op. A Final body is never held. Why 30 s: a body that merely lags is at most ~20 s old (the board is 6–9 s behind
   per-event and its `max-age` is 10 s), so 30 s never lets a lagging copy through; it is also the longest NFL choice, so
   at any choice the correction shows on the first poll at least 30 s after it first arrived. Tests:
   `ScoreBugMachineIntervalTest.anOlderBodyIsHeldAtMostThirtySeconds`, `…anOlderBodyWithTheSameScoreStaysANoOpAndAFinalIsNeverHeld`.
