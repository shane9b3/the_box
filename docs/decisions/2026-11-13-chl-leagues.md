# CHL leagues (OHL, WHL, QMJHL) on the hockey score bugs (owner request 2026-11-13)

Status: **design reviewed and revised 2026-11-13; commit 0 and WP-1 to WP-5 implemented and reviewed 2026-11-13/14;
integrated 2026-11-14** (see "Integration" below).
- Design: `docs/design/m6-chl-leagues.md`.
- Review: `docs/review/2026-11-13-chl-leagues-design-review.md`.

## Request
"Can you look into adding the CHL, which includes 3 leagues, OHL, WHL, and QMJHL, they would use the same NHL score
bugs."

A feasibility report came first (2026-11-13): HockeyTech is the only free source with a live clock, shots and goal
flags; the feed has no power-play state; intermission is not flagged. The owner then approved the work: "go ahead with
the chl, use your recommendations".

## Owner decisions (2026-11-13)
1. **Data: the HockeyTech feeds**, including the keys embedded in the league sites. The owner accepts the terms risk.
2. **Show the derived power play**, accepting occasional errors.
3. **Sports tab: one CHL chip** (NHL · NFL · CHL) with a switch between OHL / WHL / QMJHL inside it.
4. **English names, the label "QMJHL", codes upper-cased** (BaC → BAC).
5. **Memorial Cup and CHL-wide events are out of the first version.**
6. **The CHL leagues use the same NHL score bugs**: the Pill and the Stacked bug, with the same "Score bug style"
   setting.

## Data source and why
- **HockeyTech / LeagueStat** runs the stats of all three leagues. Nothing else has a live CHL clock:
  - ESPN has no CHL league;
  - TheSportsDB's free key is disabled and has no live clock;
  - Sportradar is paid.
- Two HockeyTech surfaces, checked live on 2026-11-13:
  - the keyed `lscluster.hockeytech.com` (gzip, `max-age=240` on lists) for the day list, teams and seasons;
  - HockeyTech's Firebase database (no key today, real time, content ETags) for the followed game and Today's live
    clocks and scores.
- The Firebase running clock tracks the real game clock second by second. The keyed lists are cached for 4 minutes,
  so they serve the day list only.

## Accepted degradations
- **The power play is derived** from penalties, goals and the clock (design §5). The design lists the known misses
  (§5.7). When a hide rule fires, the bar holds its last state for at most 30 s and then closes silently; a hide is
  never announced as "Power play over."
  - Replay of the design text on 42 recorded finished games: the derived advantage agrees with the feed's own goal
    flags for **56 / 57** power-play goals, **5 / 5** short-handed and **161 / 161** even-strength. The one miss is a
    data error (a PP goal with no penalty on record), which a hide rule covers.
  - The modelled penalty ends match the recorded release time for **124 / 129** flagged minors; the 5 others are
    scorekeeper artifacts listed in the design.
  - Live behaviour is measured before release by the recorded-game gate (design §7.4).
- **No "End 1st":** a break where play can follow reads "1st INT". A decided game at 0:00 reads "End 3rd" / "End OT"
  until the scorekeeper finalises.
- **No empty-net or 6-on-4 state.**
- **Cached day list** (up to 4 min) except Today's clocks and scores.
- **Logos:** no dark variants.
  - The JPEG swap recovers **2** logos (NIAG, CAP).
  - **6 teams fall back** to the abbreviation: BRAM, BDN, KEL, PEN and SAS have no PNG; WEN's PNG has no
    transparency.
  - 55 of 61 show a logo.
- **No remote kill switch** (as the NHL): a refused key or a closed database shows its reason, and a fix ships as an
  app update.

## Lead rulings (2026-11-13)
From the proposer's P1–P13, as amended by the review:
- **P1.** One CHL update-interval setting, shared by OHL, WHL and QMJHL: 5 · 10 · 15 · 20 · 30 s after each update;
  Auto never under 5 s. The setting shows and runs **the followed league's meter** (L4). Amends ruling 29.
- **P2.** Conditional GETs (`If-None-Match`) on **every** Firebase node, and **all six nodes every live poll** (S4).
  The no-ETag rule A-S1 is scoped to the NHL CDN (its ETag is a bucket id, not a content hash).
- **P3.** The keys are source constants in `:data` (`HockeyTechKeys`), registered with `Redact` **at class
  initialisation** (L6). They appear in no doc, fixture or log.
- **P4, narrowed by S3.**
  - "1st INT" (Stacked "1ST" over "INT") for every break where play can follow, including the "20:00 2nd" form, which
    maps to period P − 1 at 0:00 (S12).
  - A decided game at 0:00 (period ≥ 3, scores differ) reads "End 3rd" / "End OT", with no power play.
  - Amends ruling 33 for the CHL.
- **P5, before puck drop only.**
  - `Final` or 4 → FINAL; 5 (forfeit) → FINAL; 2 → from the clock; 1 → SCHEDULED.
  - An unknown status: SCHEDULED **before the game has started**; afterwards, the clock inference (S6).
- **P6, scoped to the CHL** (S9).
  - CHL logo URLs are validated under `assets.leaguestat.com/{client}/logos/`; a JPEG is swapped for `{teamId}.png`;
    `50x50/` is never used.
  - A CHL logo without transparency (no alpha, or four opaque corners) falls back: a CHL-only check runs before the
    trim.
  - The disc becomes a capsule for 4-letter codes.
  - NHL and NFL code paths are untouched. Their screenshots are held by a SHA-256 hash gate.
- **P7.** "Today" per league reference zone (OHL and QMJHL `America/Toronto`, WHL `America/Vancouver`), rolling over
  at 05:00.
- **P8.** `FailKind.REFUSED` with its own text. The follow and the day list retry only at `BACKOFF_MAX_MS` (300 s),
  at named sites in the machine and repository (S7).
- **P9.** The credit line is the feed's attribution: "Official statistics provided by {league} · Powered by
  HockeyTech.com", with **"LHJMQ" verbatim** for the QMJHL (Q2). "QMJHL" is used everywhere else.
- **P10.** Cards use the nickname. The bug, the live region and the logo descriptions use "City Nickname".
- **P11.** Every new settings row is ADVANCED.
- **P12.** The style setting (`SportsPrefs.nhlStyle`, stored name unchanged) applies to every hockey league.
- **P13.** The `gc` 60 s fallback when Firebase is refused: **not in v1**.
- **Open questions:**
  - **Q1**: SCHEDULED only before the start; a postponed game expires 8 h after its listed start.
  - **Q2**: the attribution "LHJMQ" stays verbatim.
  - **Q3**: no P13.
  - **Q4**: measure the 4-letter fits in commit 0; extend the 2 px step-down rule to codes; never a display alias.
  - **Q5**: accepted ("until the advantage shrinks"; a double minor counts down once from 4:00).
  - **Q6**: Firebase streaming later.
  - **Q7**: 05:00.
  - **Q8**: accepted, with S5 (board mode only for a scheduled game or an evicted final one).
  - **Q9**: accepted; the premise is reworded ("recent games, unbounded age, not guaranteed").
  - **Q10**: the "Show Sports" description names HockeyTech.

## Design review (2026-11-13): dispositions
Verdict "ready after changes". Every item is accepted and applied in the design.

| Item | Disposition |
|---|---|
| B1 hockey views lack their league | Accepted. Commit 0 sets `league = s.id.league` in the hockey `view()` / `knownView()`, with a machine test and a UI colour-seam test (design §1.5, §1.6 #13a). |
| B2 chaining | Accepted as written: chain key `(side, period, Time, PenalizedPlayerId)` with no offence; player id 0 = null (never chained); links ordered by `(start, id)`. E29, E30. The replay was re-run with the revised reference script: 56/57 · 5/5 · 161/161. |
| B3 a hide reads as "over" | Accepted. Append-only `GameSnapshot.powerPlayUnknown`. The machine holds the last tab state for at most 30 s of consecutive unknown, then closes it silently; there is no "Power play over." for a hide (design §4.1, sites §1.6 #13b). |
| S1 major vs unflagged minor | Accepted: hide rule H9 with cross-side pairing; E31. |
| S2 `TimeOn` ≤ `TimeStart` | Accepted; E32. |
| S3 end of a decided game | Accepted: "End 3rd" / "End OT", no power play; E33. |
| S4 conditional nodes | Accepted: all six nodes every poll, with `If-None-Match`; `EST_WIRE_BYTES_CHL` 2 000. |
| S5 silent board fallback | Accepted: a live-mode `null` is `BAD_BODY`; board mode only for scheduled or evicted final games. |
| S6 unknown status after start | Accepted. |
| S7 REFUSED back-off sites | Accepted (machine `fetched`; repository day back-off). |
| S8 validation on goal instants only | Accepted. The duration audit is a unit test (124/129, five listed artifacts). Pre-release gate on recorded live 5 s sequences: **proposed threshold ≤ 5 % wrong-bar seconds per league and no wrong stretch > 30 s**; the lead confirms it at the gate (design §7.4, O1). |
| S9 byte-identical not testable | Accepted: the alpha rule is CHL-only; SHA-256 hash gate for the NHL, NFL and Stacked screenshots. |
| S10 logo facts | Accepted and corrected: 2 recovered, 6 fall back, 55 show; MON interlaced. |
| S11 work-package collisions | Accepted. Strings, `SettingsText` keys, `SportsDays`, B1 and B3 are in commit 0; `PlayerEffectsImpl` / `PlayerViewModel` go to WP-5; file ownership is listed per package (design §8). |
| S12 the "20:00" break fields | Accepted: period P − 1, remaining 0, in intermission. |
| S13 goals without a time | Accepted: H2 covers goals. |
| L1–L11 | All accepted. L7: the inline sub-switch is taken (no vertical shift). L2: the QMJHL team code `NFL` is never read as a league (`TeamCodeIsNotALeagueTest`, plus fit cases). L3: `breakMs()` unchanged. L11: the status lines are fixed. |

## Commit 0 and its review (2026-11-13)
Commit 0 was implemented and reviewed (`docs/review/2026-11-13-chl-commit0-review.md`, verdict "merge after fixes").
Lead rulings, applied in the fix pass and written into the design (§1.2, §2.3, §4.1, §6.4, §8, §10):
- **Deviations accepted:** the hold is strict (`< 30 s`); `goalWasPp` takes the previous (or held) power play; no hold at
  FINAL / SO; NHL and NFL "today" keep the Eastern date with no rollover (the CHL rolls over at 05:00 on the local wall
  clock); a CHL Stacked bug has the Pill footprint until WP-2's row 19.
- **B1:** a goal by the held side inside an unknown run drops the hold silently, and a score change inside the run
  counts as a score change for the first known snapshot after it (no false "Power play over.").
- **B2:** `GameSnapshot.startTbd` (appended). **B3:** `ScoreFacts.chlLeague` (appended); `setScoreLeague` keeps taking a
  concrete league. **B4:** `HockeyTechMapper.overlay` takes and returns a `ChlBoard`. L6: `wantsShootouts(prev, facts)`.
- **S4:** the hold ends by elapsed time, even when polls slow down.
- **O3 (BRAM), S1, S2:** a 4-letter code in a logo fallback (the capsule, the Pill and Stacked goal badges) may step its
  text down to a **16 px** floor; never a display alias. The capsule keeps overhang ≤ 4 and a gap ≥ 6 to the score's ink.
  Measured: BRAM capsule 16 px (overhang 1.5, gap 9.5), badges 22 px; FLNT and NIAG fit at 18 / 24. `GoalPainter` and
  `StackedGoalPainter` (that fallback text only) belong to WP-5.
- **S5:** the hash list records its host; the gate deletes the old PNGs first; the reader test stays with integration.
- **No release from `main` between commit 0 and integration** (lead): commit 0 already changes two settings texts
  (`settings_sports_show_desc` names HockeyTech; `settings_sports_interval_league` drops the "other" league) and adds
  CHL contracts with no source bound.

## WP-5 review fix pass (2026-11-14)
Lead rulings on `docs/review/2026-11-14-chl-wp5-ui-and-colours-review.md`:
- **S1 / R1 (Stacked fallback):** in the Stacked bug the logo fallback stays inside the 50.4 px logo slot with ≥ 6 px to
  the stripe and to the code's ink, in all four geometries and both sizes. For a code longer than 3 characters it is a
  plain 44 px disc **without text**, filled with that team's Stacked stripe colour; the row prints the code beside it.
  3-letter and 2-letter fallbacks and all NHL fallbacks are unchanged. The Pill keeps the capsule. Reason: no capsule
  text size at or above the 16 px floor fits a 50.4 px slot. Written into design §6.4; tested by
  `ChlStackedFallbackTest`.
- **S2 / R3:** a bounded negative cache (128 URLs, 6 h, process lifetime) for failed CHL card logos, so the cards do not
  refetch 404s on every visit; NHL and NFL unchanged.
- **L1 / L2:** the CHL alpha check is chosen by the league where the caller knows it (the cards), and by the URL shape
  (the CHL host in any spelling, or the fake panel's `/leaguestat/` folder) in the bug's hook, so the emulator shows
  WEN's fallback.
- **L5, L6, L7, L8:** two-entry capsule cache; a key press cancels the pending focus pull; Back-from-a-sub-chip and RTL
  tests; the static grep also covers string comparisons of a code with a league's name.
- **L3 (byte cap on CHL logo downloads):** not done; Coil's fetcher gives no hook inside the WP-5 files. Decoded size is
  bounded by subsampling; accepted for a fixed HTTPS host.

## Fixtures and scripts in the repo (2026-11-13)
- **Captures:** `core/src/testFixtures/resources/sports/chl/` (47 files, ~0.9 MB):
  - the 21 Firebase final-data nodes;
  - live clock samples;
  - `gamesbydate`, `teamsbyseason` and `seasons` for the three leagues;
  - the forfeit and playoff-OT excerpts;
  - the `gc` clock tab;
  - the error bodies;
  - the 61-row logo outcome table (no images).
- **Scripts:**
  - `tools/chl/pp_replay.py`: the reference model of design §5, the goal replay, the duration audit and probes;
  - `tools/chl/logo_audit.py`: the logo outcome table.
- **Keys stripped:** the `lscluster` bodies echo the key in `SiteKit.Parameters.key`. Every occurrence of each of the
  four known keys (OHL, WHL, QMJHL, CHL) was replaced with `KEY_REDACTED`, in 12 files. Then checked as follows:
  - a `grep` for each key across the fixtures and `tools/chl` finds 0 files;
  - every remaining `"key"` value is `KEY_REDACTED`;
  - a search for any 16-hex-digit token finds only a ticket-vendor event id in two boards.
- The scripts contain no keys.

## Links
- Design: `docs/design/m6-chl-leagues.md`.
- Review: `docs/review/2026-11-13-chl-leagues-design-review.md`.
- Standing hockey rulings: `docs/decisions/2026-11-05-nhl-score-bug.md`, `docs/decisions/2026-11-10-nhl-score-bug-stacked.md`.

## Integration (2026-11-14)
- **Site keys:** the three leagues' public site keys are embedded in one source file (`HockeyTechKeys` in
  `data/src/main/kotlin/app/thebox/data/sports/HockeyTechEndpoints.kt`) and registered with `Redact` at class
  initialisation, with the **owner's acceptance of the terms risk (owner decision 1, 2026-11-13)**. They appear in no doc,
  fixture, log or diagnostics export; a debug build pointed at the fake panel sends the fake panel's own keys.
- **Refused feed on screen (WP-3 review L10):** `ScoreRepository.followFailure: StateFlow<FailKind?>` carries the followed
  game's last poll failure (cleared by an answer and on a follow change). For `FailKind.REFUSED` the followed card on the
  Sports tab shows `sports_failed_refused` in place of its two team rows, and the quick-bar sheet adds the followed
  league's failure row under the followed game. No new string. Other kinds keep the bug's own dim-and-hide.
- **One debug override name:** `SportsConstants.DEBUG_OVERRIDE_FILE` (`:core`) is read by the three data sources and by
  `ChlLogoCheck`, each only in a debug build.
- **Hash gate:** `./gradlew :ui:sportsHashGate` (`docs/m0-toolchain.md`); skipped with a message on a host other than the
  one in the list's `# host:` line.
- **Live gate:** `ChlLiveGateTest` (with `CHL_LIVE_RECORDINGS` for real recordings) is the S8 gate; `pp_replay.py live` is
  a quick check.
- **Memorial Cup and CHL-wide events** stay out of v1 (owner decision 5).
- Amended for the CHL: `docs/decisions/2026-11-05-nhl-score-bug.md` (rulings 13, 18, 29, 33, A-S1),
  `docs/decisions/2026-11-10-nhl-score-bug-stacked.md` (hockey only), the M6 row of `docs/00-master-plan.md`, the
  score-bug, NFL, power-play, goal-animation, Stacked and update-interval designs, `README.md`.

## Fix pass after the integration review (2026-11-14)
Review: `docs/review/2026-11-14-chl-integration-review.md` (ready to merge as an unreleased feature). Lead rulings:
- **S1 ("Time TBD" follow expired at once).** Chosen: `FollowedGame.startTbd` (appended, defaulted) and, for a TBD
  follow, the hard expiry measured from the end of the league day (next 05:00 local) instead of the midnight placeholder.
  - Why not the other option (a 05:00-next-day placeholder in the mapper): the placeholder is also the pre-game wake
    (`start − PRE_WAKE_MS`), so the machine would sleep through an evening TBD game until the next morning.
  - Why not `max(start, follow time)`: the follow time is not persisted, so a restart would need a second new field; the
    day end needs only the flag, is the same before and after a restart, and is never earlier than a same-day follow.
  - L5 is removed separately: the overlay skips a scheduled TBD game.
- **S2:** a failed CHL card logo is cached for 6 h only on an HTTP 4xx, the no-alpha check or a rejected URL; any other
  failure (no network, timeout, 5xx, decode) for 60 s; a cancelled fetch is not cached.
- **S3:** design §1.6 rows 12 and 37 and §2.6 now say `max(300 s, Retry-After)`, up to 3 600 s on REFUSED only.
- **L1:** the refused card keeps a 40 px matchup row ("Knights · Rangers", `sports_card_live_line`) above the reason
  (24 px, at most 3 lines, warning colour; ink on the focused card's light fill for contrast); the spoken text names the
  teams, then the reason.
- **L4:** one shared debug check, `SportsDebugOverride` (`:core`): the installed app's `FLAG_DEBUGGABLE`, used by the
  three data sources and `ChlLogoCheck`. The data sources no longer read `BuildConfig.DEBUG`.
- **L6:** `pp_replay.py` no longer calls `live` the S8 gate.
- **L2, L3: no change.**
- **L7, accepted and stated here:** `sports_follow.meters` keeps a CHL league's meter after its follow ends. A downgrade
  to v0.1.7 (or any build without the CHL leagues) after **any** CHL follow, not only while one is followed, therefore
  fails to read the whole `sports_follow` document: that build starts with no follow, fresh NHL / NFL meters and a new
  poll phase. Device-only data, downgrades unsupported; never "fixed" with an NHL default (design §1.4).
