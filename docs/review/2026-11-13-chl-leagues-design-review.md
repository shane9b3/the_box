# CHL leagues design review (adversarial)

Under review: `docs/design/m6-chl-leagues.md` and `docs/decisions/2026-11-13-chl-leagues.md` (both untracked, main
`2c7d5e5`). Reviewer: Opus. The owner decisions are not re-litigated. None of the findings contradicts them.

## Verdict: ready after changes

The architecture (one source per league behind `ScoreSource`, a pure mapper, a pure power-play function, a
`LeagueGroup` that is never persisted, append-only contracts) is sound. Three blockers have to land in the design
before commit 0, because they change contracts or the commit 0 site list:
1. the machine does not stamp the league on hockey views;
2. the power-play algorithm shows a wrong 5-on-3, and a wrong chain, on games in the design's own fixtures;
3. a hidden power play is indistinguishable from an ended one.

The scripts and variants named below are in
`/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/review/`.

---

## Blockers

### B1. Hockey views never carry their league, so every CHL bug draws with NHL colours (§1.5, §1.6 #13)

- **Where:** `ScoreBugMachine.view()` (`ScoreBugMachine.kt:297`) and the hockey branch of `knownView()` (`:329`) build
  `ScoreBugView` without `league`. The field defaults to `League.NHL` (`SportsContracts.kt:350`). Only the NFL paths
  set it (`:325`, `nflView`).
- **Why it matters:** §1.5 says "The league comes from `ScoreBugView.league`", and `ScoreBug(...)` takes
  `val league = shown ?: …` from the view (`ScoreBug.kt:177`).
- **Failure:** follow OHL OTT – KIT. The view says NHL, so `TeamColourTable.of(NHL, "OTT")` gives the Senators'
  colours. LDN, KIT and the other CHL codes fall to the NHL default pair. The goal takeover, power-play tab and Stacked
  rows are all wrong colours. The same happens for SEA, CGY, EDM, VAN and CHI.
- **Why the tests miss it:** they would pass, because §7.2 checks the tables, not the view's league.
- **Change:**
  - Add a §1.6 row: the hockey `view()` / `knownView()` set `league = s.id.league` (the NHL value is unchanged, so
    NHL output stays identical).
  - Put this in commit 0, next to the hook signature change.
  - Add a machine test: "a CHL snapshot's view has `league == OHL`".
  - Add a UI seam test: "an OHL OTT view resolves to `ChlTeamColours`, not `NhlTeamColours`".

### B2. Two chaining errors give wrong bars on fixture games (§5.3 step 3)

Both were reproduced by running the proposer's prototype and probes on the 42 captured games.

**(a) Same player, two different minors, shown as a 5-on-3.**
- The chain key includes `offence`, so tripping + unsportsmanlike on one player becomes two seats.
- The rule is that one player's two minors are served one after the other (4:00 at 5-on-4).
- **Seen:**
  - QMJHL 32755, P1 16:48 (Tripping + USC, both flagged): the model gives `(a, h) = (2, 0)` from 16:48 to 18:48, so
    HOME "5-ON-3". Then nothing from 18:48 to 20:48, where reality is 5-on-4.
  - QMJHL 32721, P3 03:21 (Roughing + Cross-checking): the same error.
- **Why the replay misses it:** neither window has a goal in it.
- **Change:**
  - Chain key = (side, period, `timeS`, `playerId`), with no offence.
  - Treat `PenalizedPlayerId == 0` as null. The feed uses 0, not null, for bench minors (8 rows seen). Two bench minors
    at one stoppage are served by two players, so they must not chain.
- **Verified:** the replay stays at 56/57 · 5/5 · 161/161 (`pp_nooff.py`).

**(b) Links in "`id` order" are wrong.**
- In the OHL's edited form, the **later** link has the **lower** id:
  - OHL 29179, P2 15:07: id 454471 has `TimeStart` 15:16 (the second link); id 454472 has `TimeStart` 15:07 (the
    first link).
  - The same holds at 15:11 (454473 / 454474).
- **Failure:** in id order, the 15:16 link serves first. The 15:07 link then waits until 17:16 and runs a phantom
  minor to 19:16. The 15:16 goal also releases the wrong row.
- The prototype sorts by start, so its 56/57 does not test the text as written.
- **Change:** order links by `(startS ?: timeS, id)`. Add E29 (different offences, same player) and E30 (OHL 29179's
  edited pair with ids reversed) to §5.5.

### B3. `powerPlay == null` means both "none" and "hidden", so a hide announces "Power play over." (§5.6, §4)

- **The path:** `ppEndedEntry` fires when the previous fresh view had a power play, this one has none, and there was no
  score change (`ScoreBugMachine.kt:543`). `ScoreBugShell` then speaks `sports_live_pp_over` and the tab closes.
- **Failure:**
  - Any H3, H5, H7 or H8 firing during a real power play (the most common is H8: one failed `penalties` GET) gives a
    false "Power play over." and closes the tab.
  - The next poll reopens it and speaks "power play" again.
  - At a 5 s floor, one dropped request is enough.
- The NHL never hit this because its null comes from a feed state, not from a local guess.
- **Change (commit 0 contract, append-only):**
  - `GameSnapshot.powerPlayUnknown: Boolean = false`, set by the CHL mapper whenever a hide rule fired.
  - The machine treats an unknown snapshot as "no change" for `ppEnded` and `lastShownPp`. It draws no tab, says
    nothing and closes nothing.
  - Alternatively, for H8 only: reuse the last good `penalties` body (the source keeps it for the ETag anyway) while it
    is under 30 s old, and hide only after that.
  - Add a machine test: PP → unknown → PP announces nothing.

---

## Should-fix

### S1. Major against an unflagged minor at one stoppage shows a power play during 4-on-4 (§5.3 step 1, §5.7 item 6)

- **Seen:** WHL 1023281, P2 05:16.
  - Home fighting major, flagged 1 (`TimeOn` 10:16).
  - Away interference on the goaltender, 2 min, flagged 0 (`TimeOn` 07:16).
- **Model:** AWAY power play from 5:16, `(0, 1)` at 5:30.
- **Reality:** 4-on-4 for 2:00, then a 5-on-4 for 3:00. So 2 minutes of wrong bar.
- §5.7 item 6 calls this "relies on the flags", but the flags cannot express it.
- **Change, new H9:**
  - Hide while an unflagged 2- or 5-minute row of one side, at the same `(period, Time)` as a counted row of the other
    side, is within its own duration…
  - …after first pairing off unflagged rows of equal minutes across the two sides.
  - The pairing keeps the common "roughing pair + extra minor" visible (QMJHL 32755 P1 07:29 pairs off and still shows
    the double-minor PP).
- Add E31.

### S2. `TimeStart` that equals its own `TimeOn` creates a phantom 2 minutes (§5.3 step 2)

- **Seen:** OHL 29185, P1 05:36 double minor. Both rows have `TimeStart` 07:36, and the first row's `TimeOn` = 07:36
  (zero length).
- **Model:** a power play from 7:36 to 11:36, instead of 5:36 to 9:36. Probed: `(0, 1)` at 10:30 elapsed.
- The 9:36–11:36 bar is wrong.
- **Change:** when `TimeOnSet == 1` and `onS ≤ startS` in the same period, the row is inconsistent. Use `timeS` as its
  start.
- **Verified:** the replay stays at 56/57. This also explains why `TimeStart` cannot simply be dropped: it carries real
  delayed-call starts (OHL 29185 P2: called 10:26, start 10:36, PP goal at 12:36).
- Add E32.

### S3. The end of a decided game reads "3rd INT", with a frozen PP bar, until the scorekeeper finalises (§3.4, P4)

- **The gap:** stopped at 0:00 in P3 with the scores not tied is the end of the game, not a break. Status stays 2 until
  `Final` is set, often minutes later.
- **Seen:** OHL 29179 ended 4–1 with an away minor at 19:08. The bug would show "3rd INT" and a home "POWER PLAY 1:08" tab after
  the game was over.
- **Change:**
  - P ≥ 3 at 0:00, stopped, and scores differ (or OT ended with different scores) → LIVE with `CentreText.EndOf`
    ("End 3rd" / "End OT"), and the power play hidden.
  - "1st INT" stays for every break that can be followed by play.
  - This narrows P4; it is not a reversal.
- Add a mapper test and E33.

### S4. `goals` and `publishedclock` are conditional on answers that arrive in the same parallel batch (§2.4)

- **The gap:** "`goals` when the goal total differs" needs `goalssummary` first. "`publishedclock` when `status_id ≠ 2`
  or the clock is 0:00" needs `runningclock` first.
- Either way that is a second sequential round trip (which the work-time meter and Auto would count), or a one-poll lag
  in which H6 hides the bar at every goal.
- **Change:**
  - Request all six Firebase nodes every live poll with `If-None-Match`. They are ~0.15 KB each as a 304 on one HTTP/2
    connection.
  - Drop the conditional rows.
  - This also shrinks H6 to genuine cross-node skew.
  - Re-estimate `EST_WIRE_BYTES_CHL` (it goes down: no extra round trip, the same bodies).

### S5. Live-window `runningclock == null` silently falls back to a board up to 4 minutes old (§2.4)

- **Failure:** Firebase moves or empties a path (200 `null`). The follow then runs on the cached `gamesbydate`:
  - the clock and score lag by up to 240 s;
  - the bug is not dimmed;
  - there is no "No update" and no REFUSED text, indefinitely.
- **Change:** in live mode, `null` with the board saying status 2 or 4 → `Failed(BAD_BODY)` (the machine dims, then
  hides as usual). Board mode only for:
  - a board-SCHEDULED game, so the follow is not lost before Firebase lists it;
  - a board-FINAL game evicted from Firebase.

### S6. An unknown status after puck drop hides the bug and switches to pre-game polling (§3.4, P5, Q1)

- **The gap:** SCHEDULED makes `view()` null and `pollAt` use the pre-game cadence. If status 3 turns out to be an
  in-game or unofficial-final state, the bug vanishes mid-game.
- **Change:** unknown status with `Started == true` (publishedclock), or with any score → phase from the clock
  inference (LIVE / INTERMISSION, or S3's end-of-game rule). Before the start → SCHEDULED, as proposed.

### S7. The REFUSED back-off has no site in §1.6 (§2.6)

- **The gap:** "retried only at `BACKOFF_MAX_MS` (300 s)" needs a machine change. Today a timed league backs off from
  `BACKOFF_FAST_BASE_MS` (5 s, `ScoreBugMachine.kt:216`), so REFUSED would retry at 5, 10, 20 s… The day list's retry
  is in the repository.
- **Change:** add rows for `ScoreBugMachine.fetched` (`REFUSED` → `backoffAt = received + BACKOFF_MAX_MS`) and the
  repository's day-list back-off, with tests.

### S8. The validation only tests instants at goals, and only on finished data (§5.5, §7.2, decision note)

- **The gap:** 56/57 compares the derived advantage at goal seconds. None of the B2, S1 or S2 errors falls on a goal, so
  all of them pass. The one duration ground truth available was not used: the OHL/WHL `TimeOn` with `TimeOnSet == 1`.
- **Run with `endaudit.py`:**
  - The model's end matches the recorded `TimeOn` for 124 of 130 flagged minors as designed, and 125 of 130 with S2.
  - The remaining 5 are scorekeeper artifacts: a chain end copied onto link 1, a `TimeOn` not updated after a PP goal,
    and the game end at 20:00.
- **Change:**
  - Make that audit a `ChlPowerPlayTest` replay case with the 5 listed exceptions.
  - Add a pre-release gate: record full node sequences at 5 s for at least one live game per league. Store them as
    fixtures (`tools/` script). Replay the derivation poll by poll against the final data, and report **wrong-bar
    seconds** (shown side or kind ≠ final-data model) separately from hidden seconds.
  - The lead sets a threshold. This is the only way to measure live behaviour: unedited flags, entry order and
    `TimeStart` edits.

### S9. "NHL/NFL byte-identical" is not testable as stated (§3.5, §6.4, §7.2 gate)

- **No goldens:** `ScoreBugScreenshotTest` says its PNGs "are not goldens", so no suite compares pixels.
- **Fake logos:** the suites inject fake logos (`FollowLogos` with drawn ovals), so the all-league no-alpha rule in
  `coilLogoFetch` / `LogoTrim` is never exercised for NHL SVGs or ESPN PNGs.
- **Change:**
  - Scope the alpha rule to the CHL. It can key on the validated `assets.leaguestat.com` base, or be a flag on the
    fetch. NHL and NFL code paths are then untouched by construction.
  - Add a hash gate: run the sports screenshot suites on `main` before commit 0. Commit the SHA-256 list of the PNGs to
    `ui/src/test/resources/golden/sports-hashes.txt`. Assert equality after integration for every NHL, NFL and Stacked
    case.
- **Placement of the check:** the alpha check must run inside the `Transformation` before trimming (a Coil
  transformation cannot return null, so it should throw, which gives `ErrorResult` and a null logo). After the trim,
  corners can be opaque for square badges.

### S10. The logo facts are partly wrong (§3.5, decision "Logos")

- **Verified:** I fetched all 61 URLs, with JPEG URLs swapped to `{id}.png`, and decoded the PNG corners.
  - 56 are 200 `image/png`. 5 are 404 (BRAM, BDN, KEL, PEN, SAS).
  - **WEN's `222.png` is colour type 2 (RGB, no alpha, opaque corners).** The alpha rule rejects it.
  - So the swap recovers 2 logos (NIAG, CAP), not 3, and 6 teams fall back. BRAM is one of them, so the 4-letter
    capsule ships on day one.
  - MON's PNG is interlaced (fine on Android, but keep it in the fixture set).
  - All other PNGs are RGBA with transparent corners.
- **Change:**
  - Correct §3.5 and the decision note.
  - Add the swapped NIAG and CAP PNGs to the owner sign-off sheet. An id-only PNG may be an older mark: the current
    ones carry season suffixes such as `_295`.
  - Add a `ScoreBugLogoTest`-style test over the 61 recorded outcomes.

### S11. The work packages collide, and some work is unassigned (§8)

- **Shared resources:** WP-2 (WpaPages slowed/fallback notes → new `SettingsText` keys) and WP-5 both edit the
  `settings_sports_*` / `sports_*` resources in `strings_sports.xml` and the text-key mapping. **Move every new string
  and `SettingsText` key into commit 0.**
- **Hidden dependency:** `SportsDays` (WP-1) is called by `SportsViewModel` (WP-5), so WP-5 depends on WP-1. Move
  `SportsDays` (about 30 lines) into commit 0.
- **Unassigned:**
  - `PlayerEffects.setScoreChlLeague`'s implementation (`PlayerEffectsImpl`, `PlayerViewModel` reporting the CHL row
    state): assign to WP-5.
  - The B1 machine change: commit 0.
  - The B3 contract: commit 0.
- **Commit 0 size:** with the fixtures it is fine. Copy only the 21 `fbdump` files (596 KB) plus trimmed `hist`
  excerpts. Do not copy the 1.7 MB schedules: §7.1 already asks for excerpts, so keep it at that.

### S12. The snapshot fields for the "20:00 3rd" break form are unspecified (§3.4)

- **The gap:**
  - The machine's label comes from `s.period` (`label(s)`, `ScoreBugMachine.kt:814`).
  - The card's `StateLine` uses `periodOf(g)`.
  - The power play uses `ChlNow.period / remainingS`.
- **Change:** state that the mapper emits `period = P − 1` and `clock.secondsRemaining = 0`, `inIntermission = true`
  for that form. Then the centre text, the card text and `nowG` all agree, and H2 ("period > now.period") does not fire
  on a penalty the scorekeeper already logged in period P.

### S13. Goal rows that cannot be placed (§5.1, §5.6)

- **The gap:** `ChlGoal.timeS` is nullable, but no rule covers a goal with no time while a counted row serves (its
  release cannot be placed).
- **Change:** extend H2 to goals. A goal with an unparsable period/time, while any counted row is serving, is a hide.

---

## Low

- **L1 (§2.1, Q9):** "Firebase keeps only the ~15 most recent games" is not a count rule.
  `runningclock` held 19 WHL entries, including 2026-10-23, and 13 QMJHL entries, including 2025-12-06. Reword it as
  "recent games, unbounded age, not guaranteed". The design does not depend on the number.
- **L2 (§6.1):** the QMJHL code `NFL` (Newfoundland) upper-cases to the NFL league's name. Add a test that no code path
  compares a team abbreviation with a league name, and a Stacked/Pill fit case for "NFL POWER PLAY GOAL" (harmless,
  but it will confuse a reviewer).
- **L3 (§2.5):** the design says breaks poll at `max(interval, 20 s)`. The machine's `breakMs()` is
  `max(2 × interval, 20 s)` (`ScoreBugMachine.kt:399`). 15 s gives 30 s, not 20 s. Say "unchanged `breakMs()`", or list
  a change.
- **L4 (§4):** the settings row's Auto shows "the shown CHL league's measurement". The machine runs the **followed**
  league's (`ScoreRepositoryImpl.kt:582`). While browsing OHL and following WHL, the two differ. Show the followed
  league's when a CHL game is followed.
- **L5 (§6.3, #42):** the picker League row must read "CHL" for any CHL league. That is the group's name; `leagueName`
  as written would give "OHL". Spell out `groupName(league.group)` for the League row and `leagueName` for the CHL row.
- **L6 (§2.2):**
  - Register the keys in `HockeyTechKeys`' initialiser (or `Application.onCreate`), not at source construction.
  - `JsonDecodingException` messages embed an input excerpt that can include `SiteKit.Parameters.key`.
  - Add an `ExportRedactionTest` case for a stack trace that carries the key.
  - The broker rule does not apply: the sports sources use `@ApiClient` OkHttp, not `StreamConnectionBroker`. Correct
    as designed. Cleartext is not involved: every CHL host is HTTPS.
- **L7 (§6.3):**
  - The sub-row moves the day chips and the card grid down by 72. Check that the first card row stays fully visible at
    1080p.
  - Consider the sub-switch inline on the chip row (`NHL · NFL · CHL │ OHL · WHL · QMJHL`). There Right from CHL enters
    it, with no vertical shift and a simpler focus graph.
- **L8 (§1.4):** builds older than the Stacked `coerceInputValues` change restore a `league = "WHL"` document as
  "unreadable; kept" (the whole `sports` domain). Add that row to the table.
- **L9 (§5.7 item 3):**
  - Do not add an automatic "delayed-call washout" rule.
  - OHL 29181 P2 17:55 and 29192 P2 12:48 have flagged bench minors at the second of an opponent's even-strength goal,
    with a full 2:00 `TimeOn`, so these were real post-goal minors.
  - The 13:02 example is the other form. The data has both.
- **L10 (§2.6):** at a 5 s cadence a 10 s `SPORTS_CALL_TIMEOUT_MS` is fine (polls never stack), but the meter counts
  it. Keep it as is; note it in §2.5.
- **L11 (§5.5):** the worked-example arithmetic E1–E28 was checked by hand and is correct (E3 70, E9 180, E10 90, E12
  60, E13 20, E14 90, E15 30, E17 90, E18 60, E23 H3). The design status lines say "P1–P12" while P13 exists.

---

## Rulings and open questions

| Item | Agree? | Recommendation and reason |
|---|---|---|
| P1 one CHL interval | Agree | One provider, one cost. Show and run the followed league's meter (L4). |
| P2 conditional GETs | Agree, extend | Use ETags on **every** Firebase node and fetch all of them each poll (S4). The A-S1 scope note is right. |
| P3 keys as constants | Agree | They are public. Register them with `Redact` at class initialisation (L6). |
| P4 one break label | Agree with a narrowing | "1st INT" for every break that can be followed by play. "End 3rd" / "End OT" with no PP when 0:00 ends a decided game (S3). |
| P5 status mapping | Agree before puck drop only | After `Started`, an unknown status goes through the clock inference, not SCHEDULED (S6). |
| P6 logo rule | Agree, scoped to the CHL | Apply the alpha rule to CHL logos only (S9). Facts corrected: 2 PNGs recovered, 6 fallbacks (S10). |
| P7 Today per league | Agree | Test the DST days as listed. |
| P8 REFUSED | Agree | Needs the machine and repository back-off sites (S7). |
| P9 credit line | Agree | It is the feed's own attribution. |
| P10 names | Agree | Note OTT's card name "67's" and SOO's full name "Sault Ste. Marie Greyhounds" (feed `name` is "Soo Greyhounds"). The city+nickname rule picks the former, which is fine. |
| P11 ADVANCED | Agree | |
| P12 shared style | Agree | Owner decision 6. |
| P13 `gc` fallback | Agree: not in v1 | S5 makes a Firebase outage visible (dim, then hide), which is enough. |
| Q1 status 3 / postponed | Partly | SCHEDULED only before the start; after it, see S6. Postponed: accept the 8 h expiry. |
| Q2 "LHJMQ" in credit | Agree | Required attribution, verbatim. "QMJHL" everywhere else. |
| Q3 build P13 | No | As P13. |
| Q4 4-letter code fails a fit | Lead step-down first | Measure in commit 0. If NIAG/BRAM/FLNT fail, extend the existing 2 px step rule to codes. Never an alias: it would not match the league's own code. |
| Q5 seconds "until the advantage shrinks" | Agree | A double minor counting once from 4:00 matches broadcast practice. Pin it with E9/E12/E13 (already there). |
| Q6 Firebase streaming | Not v1 | Note it in §10 for later. A long-lived connection on the Stick needs its own lifecycle review. |
| Q7 05:00 rollover | Agree | |
| Q8 3 h board window | Agree with S5 | Board mode only for SCHEDULED or evicted-FINAL games, never as a silent live fallback. |
| Q9 ~15 games | Agree with the behaviour | The premise is reworded (L1). |
| Q10 naming HockeyTech | Name it | "…from NHL.com, ESPN and HockeyTech…". The credit line already says "Powered by HockeyTech.com", and "the CHL's leagues" is vaguer. |

---

## Verified by running or fetching (2026-11-13)

- **Prototype replay:**
  - `scripts/pp_proto.py` on `fbdump/*_205448.json` reproduces 56/57 PP, 5/5 SH and 161/161 EV, with the one miss at
    OHL 29182 P3 00:41.
  - The same totals hold with `no-timeon`, with the chain key without offence (`pp_nooff.py`), and with the S2 fix
    (`pp_fix.py`).
  - Ignoring `TimeStart` entirely adds a miss (OHL 29185 P2 12:36), so `TimeStart` must stay.
- **Timeline probes (`timeline.py`):**
  - QMJHL 32755 P1 shows 5-on-3 from 16:48 to 18:48 (B2a).
  - OHL 29185 P1 shows a phantom bar from 9:36 to 11:36 (S2).
  - WHL 1023281 P2 5:30 shows a PP during 4-on-4 (S1).
- **Data probes (`probe1.py`, `show.py`):**
  - same-player multi-minor rows;
  - bench `PenalizedPlayerId` = 0;
  - the flagged fighting major;
  - the flagged minors at opposing goal seconds (L9);
  - the reversed link ids in OHL 29179 (B2b).
- **`TimeOn` end audit (`endaudit.py`):** 124/130 rows match as designed, 125/130 with S2 (S8).
- **Logos (`logo_urls.txt`, `pngcorners.py`):** 61 logo GETs (generic UA, nothing about the user or project sent).
  5 are 404; WEN's PNG is RGB and opaque; MON's is interlaced; all others are RGBA with transparent corners (S10).
- **Captures read:**
  - The `gamesbydate` boards (the copyright blocks, the "20:00 3rd" WHL board, the `periods` map holding only 1–3
    before OT).
  - `runningclock` entry dates (L1).

## Read only (not executed)

- **Code:**
  - `ScoreBugMachine.kt` (B1, B3, S7, L3);
  - `SportsContracts.kt`, `SportsPrefs.kt`, `SettingsStore.kt` (coerce limits), `PollMeter.kt`,
    `SportsConstants.kt`;
  - `PlayerStateMachineImpl.kt` 2075–2175, `PlayerText.kt` 479–530, `ScoreBug.kt` 165–215, `SportsScreen.kt` 355–400;
  - `TeamLogo.kt`, `LogoTrim.kt`, `GameCard.kt` caches, `Redact.kt`, `SportsModule.kt`, `ScoreRepositoryImpl.kt`
    league uses;
  - a grep of every `League.NHL` / `League.NFL` / `League.entries` site.
- **The §1.6 table is otherwise complete:**
  - `PlayerStateMachineImpl.kt:2091/2096` (`todayDivider`), `SportsScreen.kt:390` (day chips) and
    `SportsViewModel.kt:244` (`order`) fall correctly to the date/NHL branch for the CHL.
  - Only the machine's hockey views are missing (B1).
  - The card focus map is keyed by `game.id.id` without the league, but it is per board, so it is fine.
- **Not checked:**
  - glyph widths (BRAM capsule, NIAG bars): the design already says "measure";
  - the HockeyTech terms;
  - Firebase behaviour under load or rate limits;
  - status 3;
  - anything that needs a live game.
