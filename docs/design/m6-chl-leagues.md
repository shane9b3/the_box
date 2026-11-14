# CHL leagues (OHL, WHL, QMJHL) on the hockey score bugs: design

Status:
- **Proposed 2026-11-13; reviewed 2026-11-13; revised 2026-11-13 after the lead's rulings.**
- **Commit 0 implemented and reviewed 2026-11-13** (`docs/review/2026-11-13-chl-commit0-review.md`, "Fix pass"). The
  reconciled decisions are written into §1.2, §1.6, §2.3, §3.2, §4.1, §6.4, §8 and §10 below, each marked "(C0 fix
  pass)".
- **WP-1 implemented and reviewed 2026-11-14** (`docs/review/2026-11-14-chl-wp1-mapper-review.md`, "Fix pass"). The
  rulings (S1, S2 model changes; the 11 implementation departures) are written into §3.6, §5.3, §5.5, §5.6 and §5.7, each
  marked "(WP-1 fix pass)".
- Review: `docs/review/2026-11-13-chl-leagues-design-review.md`. Every finding is applied in place and marked (B1),
  (S4), (L7) and so on; the dispositions are in the decision note's "Design review" section.
- Owner request, owner decisions and lead rulings: `docs/decisions/2026-11-13-chl-leagues.md` (binding).

**Line numbers** are at `main` `2c7d5e5`, after the Stacked row mirror merged. Every site in §1.6 was re-checked
there; the merge touched only the Stacked painters, `StackedContracts.kt` and Stacked tests, none of which §1.6
cites. Commit 0 re-checks against the `main` it branches from.

**Feed facts** were checked live between 20:40 and 21:05 ET on 2026-11-13, with OHL, WHL and QMJHL games in
progress. The record is Appendix C. Statements marked **(unverified)** were not settled by a fetch.

**Recorded fixtures** are in the repo now: `core/src/testFixtures/resources/sports/chl/` (§7.1). The keys are
replaced by `KEY_REDACTED`. **Reference scripts**: `tools/chl/pp_replay.py` (the executable form of §5, with the
replay and the duration audit) and `tools/chl/logo_audit.py` (§3.5).

Inputs:
- `CLAUDE.md`; plan §3 and §6; `docs/01-design-reference.md`; architecture review §E.
- `docs/design/m6-score-bug.md` (the league seam, §2 data, §3 logos, §4 the bug, §5 Sports tab, §6 quick bar, §7
  settings).
- `docs/design/m6-nfl.md` (the second league, contracts §2, league switch §7).
- `docs/design/m6-power-play.md` (`PowerPlay`, `GoalFact`, `ppEnded`).
- `docs/design/m6-goal-animation.md` §3 (team colours).
- `docs/design/m6-score-bug-stacked-theme.md` (Stacked; §4.2 fit rules; §5 colours; §4.3 mirrored corners).
- `docs/design/m6-sports-update-interval.md` (poll meter, Auto, intervals).
- Decisions 2026-11-05 (NHL score bug; rulings 1–33) and 2026-11-10 (Stacked, incl. the 2026-11-13 mirror).

Units: sizes are **design px**, 1 design px = window width ÷ 1920 (`BoxDesignScale`). Times are in ms unless marked
s. Game time is in seconds.

Words on screen: the existing set (m6-score-bug.md "Words on screen") plus *CHL*, *OHL*, *WHL*, *QMJHL*. Never
"feed", "API", "key", "Firebase". "HockeyTech" appears only in the credit line and the "Show Sports" description
(Q10).

---

## 0. Decisions at a glance

1. **Three new leagues, one group.**
   - `League` gains `OHL, WHL, QMJHL` (append-only, persisted by name).
   - A derived, unpersisted `LeagueGroup { NHL, NFL, CHL }` drives the Sports tab chips.
   - `League.isHockey` sends every hockey league down the NHL path: Pill, Stacked, power-play tab, goal takeover,
     Style row.
   - **Hockey views carry their league** (B1, commit 0).
2. **One source class, three instances.** `HockeyTechScoreSource(client = ohl | whl | lhjmq)`, bound with
   `@LeagueKey(OHL | WHL | QMJHL)`. Nothing above `ScoreSource` learns about HockeyTech.
3. **Two hosts.**
   - Keyed `lscluster.hockeytech.com` for the day list (`gamesbydate`), teams and seasons; cached 240 s.
   - HockeyTech's Firebase database (real time, no key) for the followed game, and for Today's live clocks and scores.
4. **Followed game: all six Firebase nodes every live poll, in parallel, with `If-None-Match`** (S4): `runningclock`,
   `publishedclock`, `goalssummary`, `shotssummary`, `penalties`, `goals`. `shootouts` is added from the previous
   poll's state.
5. **Poll timing from completion**, like the NFL. Floor **5 s**; choices 5 · 10 · 15 · 20 · 30 s; Auto ≥ 5 s, from
   the **followed** league's meter (P1, L4). One CHL setting.
6. **The power play is derived** by a pure function `ChlPowerPlay.derive(...)` (§5) from penalties, goals and the
   clock.
   - When a hide rule fires, the snapshot says **`powerPlayUnknown`** (B3): the tab holds its last state for up to
     30 s, then closes silently. A hide is never announced as "Power play over".
7. **Breaks read "1st INT"** whenever play can follow (P4). **A decided game at 0:00 reads "End 3rd" / "End OT"** with
   no power play until the scorekeeper finalises (S3).
8. **Team identity is `(League, code)`.** CGY, EDM, OTT, SEA, VAN, CHI and VIC exist in more than one league.
   - Colours come from bundled per-league tables (61 teams).
   - Logos are fetched at runtime, never bundled.
   - CHL logos only (P6, S9): JPEG URLs are swapped for `{id}.png`; logos without transparency fall back. 55 logos
     show; NIAG and CAP are recovered by the swap; BRAM, BDN, KEL, PEN, SAS and WEN fall back (S10).
9. **Errors carry a reason and a next step.**
   - A refused key or closed database is `FailKind.REFUSED`, retried only at `BACKOFF_MAX_MS` (S7).
   - A Firebase `null` during a live game is a visible failure, not a silent board fallback (S5).
   - The keys never reach a log or the diagnostics export (L6).
10. **Sports tab: `NHL · NFL · CHL` chips**, and with CHL selected, an **inline** `OHL · WHL · QMJHL` switch on the
    same row (L7). The quick-bar sheet gets a matching "CHL league" row.

---

## 1. Contracts (commit 0)

### 1.1 `League` and its groupings (`SportsContracts.kt`)

```kotlin
/** Persisted by name ([FollowedGame], [SportsPrefs.league], [SportsPrefs.chlLeague], `SportsFollowDoc.meters`); append-only. */
@Serializable
enum class League { NHL, NFL, OHL, WHL, QMJHL }

/** Not persisted. */
enum class Sport { HOCKEY, FOOTBALL }

/** The Sports tab chip a league sits under (owner decision 3). Not persisted; derived from [League]. */
enum class LeagueGroup { NHL, NFL, CHL;
    val members: List<League> get() = when (this) { NHL -> listOf(League.NHL); NFL -> listOf(League.NFL); CHL -> CHL_LEAGUES }
}

/** How a league's live polls are timed (Update interval §3.5): NHL bucket-aligned; everything else from completion. */
enum class PollTiming { BUCKET, COMPLETION }

val League.sport: Sport get() = if (this == League.NFL) Sport.FOOTBALL else Sport.HOCKEY
val League.isHockey: Boolean get() = sport == Sport.HOCKEY
val League.group: LeagueGroup get() = when (this) {
    League.NHL -> LeagueGroup.NHL; League.NFL -> LeagueGroup.NFL
    League.OHL, League.WHL, League.QMJHL -> LeagueGroup.CHL
}
val League.isChl: Boolean get() = group == LeagueGroup.CHL
val League.timing: PollTiming get() = if (this == League.NHL) PollTiming.BUCKET else PollTiming.COMPLETION
val CHL_LEAGUES: List<League> = listOf(League.OHL, League.WHL, League.QMJHL)
```

Rules:
- **Every new branch is an exhaustive `when` over `League`, `LeagueGroup` or `Sport`**, never `== NHL` / `else`. A
  sixth league then fails to compile at every site. The review greps for any remaining
  `if (league == League.NHL) … else …`.
- Display names are resources:
  - `sports_league_ohl` "OHL", `sports_league_whl` "WHL", `sports_league_qmjhl` "QMJHL" (owner decision 4) via
    `PlayerText.leagueName(league)`;
  - `sports_league_chl` "CHL" via `PlayerText.groupName(group)` (L5).
  - The enum name `QMJHL` is the stored name. The HockeyTech client code `lhjmq` exists only inside `:data`.
- **Team codes are never league names (L2).** The QMJHL's Newfoundland Regiment upper-cases to `NFL`. Consequences:
  - A `League` is read only from a persisted enum name or from the source binding, never from a team code.
  - No code path calls `League.valueOf` / `enumValueOf<League>` on a `TeamLine.abbrev` or `FollowedGame.away/home`,
    and no code compares a team code with a league's name.
  - Test `TeamCodeIsNotALeagueTest`: follow QMJHL NFL – CHA and assert the bug is a hockey bug, the colours come
    from `ChlTeamColours(QMJHL, "NFL")`, and the picker and tab never switch to the NFL league.
  - A static check in the same test: a grep over `core`, `data` and `ui` main sources finds no `valueOf(` with an
    abbreviation argument.
  - Fit cases for "NFL POWER PLAY GOAL" (§6.4).

### 1.2 Snapshot, board and failure additions (append-only, defaulted)

- `GameId(league, id)`: `id` is HockeyTech's numeric `game_id`, unique only within a client. The same id is used by
  `lscluster` and Firebase (verified, `29191`).
- `TeamLine`:
  - `abbrev` = the **upper-cased** code;
  - `name` = "City Nickname" in a followed snapshot, the nickname on a day board (P10);
  - `shortName` = null;
  - `sog` from `shotssummary`;
  - `logo` = the validated URL (§3.5).
- `GameSnapshot`: `powerPlay` from §5, `lastGoal` from `goals` (§3.3), `detail = null`, and:

  ```kotlin
  /**
   * (CHL B3, append-only) True when the source could not decide the power play this poll (a §5.6 hide rule fired, or the
   * penalties / goals node failed). [powerPlay] is then null and means "unknown", not "none". Always false for the NHL
   * and the NFL.
   */
  val powerPlayUnknown: Boolean = false,
  ```
- **(C0 fix pass, commit-0 review B2) `GameSnapshot.startTbd: Boolean = false`**, appended after `powerPlayUnknown`:
  the league lists the time as to be decided (`time_tbd == "1"`). `startUtcMs` is then local midnight of the game day
  in the league's reference zone, a placeholder for ordering and expiry; the card and picker row show "Time TBD"
  (`sports_time_tbd`). CHL only; always false for the NHL and the NFL.
- **(C0 fix pass, commit-0 review B3) `ScoreFacts.chlLeague: League? = null`**: the remembered CHL sub-league
  (`SportsPrefs.chlShown`), filled by `PlayerViewModel` (WP-5, row 50), read by the sheet (WP-2, rows 31–34). The League
  row's CHL step calls `setScoreLeague(chlLeague ?: OHL)`: `setScoreLeague` always takes a concrete league, and for a CHL
  league its implementation also writes `chlLeague`.
- **`DayBoard.league: League? = null`.** Every CHL board sets it. Without it, `SportsViewModel.boardLeague`
  (`SportsViewModel.kt:209-210`) would read an **empty** CHL board as an NHL board, and the tab would show Loading
  forever.
- **`FailKind.REFUSED`** (appended): a refused key, or the database's "Permission denied". Not persisted.

### 1.3 Prefs (`SportsPrefs.kt`, backed-up `sports` domain)

```kotlin
data class SportsPrefs(
    …existing fields unchanged…,
    /** The shown league: now any of the five. An older build reading "OHL" coerces it to NHL (S7). */
    val league: League = League.NHL,
    /** (CHL §1.3) The remembered CHL sub-league; only OHL/WHL/QMJHL are meaningful (anything else reads as OHL). */
    val chlLeague: League = League.OHL,
    /** (CHL P1) The CHL's poll interval in s: 0 = Auto, else one of SportsConstants.INTERVAL_CHOICES_CHL_S. */
    val chlIntervalS: Int = 0,
    /** Unchanged stored name; now "the hockey style": read for every League.isHockey game (P12). */
    val nhlStyle: NhlBugStyle = NhlBugStyle.PILL,
) {
    fun intervalS(league: League): Int = when (league.group) {
        LeagueGroup.NHL -> nhlIntervalS; LeagueGroup.NFL -> nflIntervalS; LeagueGroup.CHL -> chlIntervalS
    }
    val chlShown: League get() = chlLeague.takeIf { it.isChl } ?: League.OHL
}
```

- **Writing:** choosing a CHL sub-league writes `league` and `chlLeague`; the CHL chip writes `league = chlShown`;
  NHL or NFL leave `chlLeague` alone.
- **No `Map<League, …>` in the backed-up domain.** The store coerces an unknown enum *property* but fails the whole
  document on an unknown *map key* (`SettingsStore.kt:17-20`).

### 1.4 Backup, restore and downgrade compatibility

| Case | Result |
|---|---|
| New build reads an old `sports` document | New fields at their defaults. |
| v0.1.7 (and any build since the Stacked `coerceInputValues` change) restores a new backup with `league = "WHL"` | `league` coerces to NHL; `chlLeague` / `chlIntervalS` are unknown keys, ignored; the rest of the domain is kept. |
| **A build older than the Stacked `coerceInputValues` change** restores it (L8) | The `sports` document is "unreadable; kept": the device keeps its own whole `sports` domain; the other domains restore. |
| `BackupContracts.SCHEMA` | Stays 1 (`BackupContracts.kt:32`). |
| `sports_follow` (not backed up) after a **downgrade** while a CHL game is followed or measured | The document fails (`FollowedGame.league` has no default; `meters` is a `Map<League, PollMeasure>`), so the old build starts with no follow and a new poll phase. Accepted (device-only; downgrades unsupported). Never "fix" it with an NHL default, which would follow the wrong game. |

### 1.5 League-keyed team identity

- Clashes:
  - CGY, EDM, SEA, VAN: NHL and WHL;
  - OTT: NHL and OHL;
  - CHI: NHL, QMJHL and the NFL;
  - VIC: WHL Victoria and QMJHL Victoriaville;
  - SEA: also the NFL;
  - and the QMJHL team code `NFL` (L2).
- **Every lookup takes the league.** The hockey views carry it (B1, §1.6 #13a).
- Colour hooks (commit 0, all callers in one commit):
  - `GoalHooks.colours: (League, String?) -> TeamColours = TeamColourTable::of` (was `NhlTeamColours::of`,
    `ScoreBug.kt:205`);
  - `StackedHooks.colours: (League, String?, String?) -> StackedPair = StackedColourTable::pair` (was
    `NhlStackedColours::pair`, `StackedBug.kt:573`).
  - The league is `ScoreBugView.league`.
- `TeamColourTable.of(league, abbrev)` (`TeamColourTable.kt:9-12`) is exhaustive: NHL → `NhlTeamColours`; NFL →
  `NflTeamColours`; OHL / WHL / QMJHL → `ChlTeamColours.of(league, abbrev)`.
- `StackedColourTable.pair(league, away, home)`: one resolved table and pair cache per hockey league. The NHL's is
  today's `NhlStackedColours` arrays, untouched (`NhlStackedColours.kt:62-70, 214-224`).
- Tests:
  - machine: "a CHL snapshot's `view()` and `knownView()` have `league == OHL`" (B1);
  - UI seam: "an OHL OTT view resolves to `ChlTeamColours`, not the Senators'" (B1).

### 1.6 Every site, and what it becomes (re-checked at `2c7d5e5`)

"Hockey" = `league.isHockey`; "timed" = `league.timing == COMPLETION`. Each row names its owner (C0 = commit 0, or a
work package, §8).

| # | Site | Today | Becomes | Owner |
|---|---|---|---|---|
| 1 | `core/.../sports/SportsContracts.kt:14` | `League { NHL, NFL }` | §1.1 | C0 |
| 2 | `SportsContracts.kt:426-434` (`NhlBugStyle` KDoc "NHL only") | NHL-only style | KDoc: hockey (P12); name unchanged | C0 |
| 3 | `SportsContracts.kt:466-475` (`PlacementInput.league/style`) | Pill unless NHL | Pill unless hockey | C0 |
| 3a | `SportsContracts.kt` `GameSnapshot`, `DayBoard`, `FailKind` | — | §1.2 additions | C0 |
| 4 | `core/.../sports/SportsPrefs.kt:23-33` | two intervals | §1.3 | C0 |
| 5 | `core/.../sports/SportsConstants.kt:59-65` | NHL / NFL choices | `INTERVAL_CHOICES_CHL_S`, `INTERVAL_DEFAULT_CHL_S` (Appendix A) | C0 |
| 6 | `SportsConstants.kt:87-88` | `EST_WIRE_BYTES_NHL/NFL` | `EST_WIRE_BYTES_CHL = 2_000` (§2.5) | C0 |
| 7 | `SportsConstants.kt:102-104` | `if NHL else NFL` | `when (league.group)` | C0 |
| 8 | `core/.../sports/PollMeter.kt:125` | 429 floor for NFL | timed leagues | WP-2 |
| 9 | `PollMeter.kt:131` | NHL else NFL estimate | `when (league.group)` | WP-2 |
| 10 | `PollMeter.kt:141-143` | endpoint ids | `CHL_LIVE = "chl-live"` | C0 |
| 11 | `core/.../sports/ScoreBugMachine.kt:176` | older-body hold under `fast()` | `league == NFL` only | WP-2 |
| 12 | `ScoreBugMachine.kt:200-218` (`fetched`, `Failed`) | fast base under `fast()` | timed; **REFUSED → `backoffAt = receivedMono + BACKOFF_MAX_MS`**, whatever `Retry-After` says (S7) | WP-2 |
| 13 | `ScoreBugMachine.kt:276, 317-325` | NFL view branch | unchanged (CHL takes the hockey path) | — |
| **13a** | **`ScoreBugMachine.kt:297-310` (hockey `view()`), `:329` (hockey `knownView()`)** | `league` defaults to NHL | **`league = s.id.league`** (B1). NHL value unchanged | **C0** |
| **13b** | **`ScoreBugMachine.kt:79-81` (state), `:300` (view `powerPlay`), `:486-487` (delay back-in-time), `:536-544` (advance: `ppEndedEntry` / `lastShownPp`), `:771-772` (`gatedPp`)** | null PP = none | **§4.1 unknown-PP hold** (B3) | **C0** (contract + machine) |
| 14 | `ScoreBugMachine.kt:353-378` (pre-game and live timing) | bucket vs NFL | `timed()` for timing, `nfl()` for NFL rules; CHL pre-game §2.5 | WP-2 |
| 15 | `ScoreBugMachine.kt:396` | `fast() = … == NFL` | `timed() = liveMs != null && league.timing == COMPLETION` | WP-2 |
| 16 | `ScoreBugMachine.kt:399` (`breakMs`) | `max(2 × interval, 20 s)` | **unchanged** (L3) | — |
| 17 | `ScoreBugMachine.kt:415, 461, 488, 515, 534, 589` | NFL rules | unchanged | — |
| 18 | `core/.../sports/ScoreBugPlacement.kt:112-113, 121, 137-138` | `NHL -> …; NFL -> …` | `when (league.sport)` | WP-2 |
| 19 | `ScoreBugPlacement.kt:163, 173` | `league != NHL \|\| PILL` | `!isHockey \|\| PILL` | WP-2 |
| 20 | `core/.../sports/TeamColourTable.kt:9-12` | NHL / NFL | §1.5 | C0 (stub CHL table) / WP-4 |
| 21 | `core/.../settings/state/WpaPages.kt:624-626` | meter of the shown league | **the followed game's league when it is in the shown group, else the shown league** (L4) | WP-2 |
| 22 | `WpaPages.kt:628-629` | NHL "every" vs "after" labels | `when (league.timing)` | WP-2 |
| 23 | `WpaPages.kt:644` | NHL floor note | NHL only | — |
| 24 | `WpaPages.kt:646-647` | NFL slowed / fallback notes | slowed for timed leagues (CHL text §6.5); fallback NFL only | WP-2 |
| 25 | `WpaPages.kt:651-655` | `other = NHL ⇄ NFL` | no "other" (§6.5) | WP-2 |
| 26 | `WpaPages.kt:656` | `SPORTS_INTERVAL, league.name` | `groupName` ("CHL") | WP-2 |
| 27 | `WpaPages.kt:756` | write NHL else NFL | `when (league.group)` | WP-2 |
| 28 | `core/.../settings/state/SettingsTexts.kt`, `ui/.../settings/vm/ResourceSettingsTexts.kt` | text keys | every new `SettingsText` key and its resource (§6.5) | **C0** (S11) |
| 29 | `core/.../full/PlayerContracts.kt:389` | `ScoreLeagueRow` | add `PickerChoice.ScoreChlRow(league)` | C0 |
| 30 | `PlayerContracts.kt:643` | `setScoreLeague` | add `setScoreChlLeague(league)` | C0 |
| 31 | `core/.../full/state/PlayerStateMachineImpl.kt:2083` | League row | then `ScoreChlRow(shown)` when `league.isChl` | WP-2 |
| 32 | `PlayerStateMachineImpl.kt:2089` | Style row if NHL | if hockey | WP-2 |
| 33 | `PlayerStateMachineImpl.kt:2159-2169` | `next = NHL ⇄ NFL` | cycle groups NHL → NFL → CHL (`chlShown`) → NHL | WP-2 |
| 34 | `PlayerStateMachineImpl.kt` (new, after 2169) | — | `ScoreChlRow` OK: OHL → WHL → QMJHL → OHL; focus stays; board reloads under Loading | WP-2 |
| 35 | `data/.../sports/ScoreRepositoryImpl.kt:189` | `?: sources.values.first()` | **no silent fallback**: `Failed(HTTP_OTHER)`, logged once | WP-3 |
| 36 | `ScoreRepositoryImpl.kt:416` | `pushInterval()` on NHL/NFL change | also `chlIntervalS` | WP-3 |
| 37 | `ScoreRepositoryImpl.kt:479-499, 520-529` (day `dayNextAt`, `dayFailed`) | back-off from 20 s | **REFUSED → next day request at `fail + BACKOFF_MAX_MS`** (S7) | WP-3 |
| 38 | `ScoreRepositoryImpl.kt:582-592` (`pushInterval`) | the followed league's meter | unchanged (it already uses the followed league; L4) | — |
| 39 | `ScoreRepositoryImpl.kt:614` | 429 slowed for NFL | timed leagues | WP-3 |
| 40 | `ScoreRepositoryImpl.kt:635-637` | NFL meter reset | NFL only | — |
| 41 | `data/.../sports/SportsModule.kt:45-58` | two providers | plus three `HockeyTechScoreSource` providers | WP-3 |
| 42 | `ui/.../sports/ScoreBug.kt:177-181` | `style = if NHL …` | if hockey | C0 |
| 43 | `ScoreBug.kt:185` | `league == NFL -> NflBug` | `when (league.sport)` | C0 |
| 44 | `ScoreBug.kt:205` | `GoalHooks.colours = NhlTeamColours::of` | §1.5 | C0 |
| 45 | `ui/.../sports/StackedBug.kt:190-191` | `League.NHL` hard-coded | `placed.league` | C0 |
| 46 | `StackedBug.kt:238, 369, 573` | `NhlStackedColours.pair` | `StackedColourTable.pair(league, …)` | C0 |
| 47 | `ui/.../full/PlayerText.kt:479, 500` | league default NHL | unchanged defaults | — |
| 48 | `PlayerText.kt:524-527` (`leagueName`) | NHL / NFL | exhaustive + `groupName(group)`; League row text = `groupName`, CHL row = `leagueName` (L5) | WP-5 |
| 49 | `ui/.../full/vm/PlayerEffectsImpl.kt:102` | `setScoreLeague` | implement `setScoreChlLeague` (writes `league` + `chlLeague`) | **WP-5** (S11) |
| 50 | `ui/.../full/vm/PlayerViewModel.kt:418` | `ScoreFacts` | reports the CHL row state (`league`) | WP-5 |
| 51 | `ui/.../sports/SportsViewModel.kt:92-95, 189-191, 241` | Eastern "today" | `SportsDays.today(league, now)` (§2.3) | WP-5 |
| 52 | `SportsViewModel.kt:110-115, 125, 154, 213, 234` | NFL weeks vs NHL dates | `when (league.group)` | WP-5 |
| 53 | `SportsViewModel.kt:142-148` (`onLeague`) | writes `league` | group chip or sub-league (§6.3) | WP-5 |
| 54 | `SportsViewModel.kt:209-210` | `boardLeague` inference | `b.league ?: inference` | WP-5 |
| 55 | `ui/.../sports/SportsScreen.kt:131, 266-299` (TitleRow chips) | `League.entries` | groups + inline sub-switch (§6.3) | WP-5 |
| 56 | `SportsScreen.kt:242` | empty text | per league | WP-5 |
| 57 | `SportsScreen.kt:361` | credit ESPN / NHL.com | per league (§6.6) | WP-5 |
| 58 | `ui/.../sports/SportsText.kt:45-50` | `period(…, league)` | `when (league.sport)` | WP-5 |
| 59 | `SportsText.kt:125-127` | `lg = NFL else NHL` | keep the league | WP-5 |
| 60 | `SportsText.kt:221-228` | failure texts | OFFLINE; REFUSED; per league (§6.5) | WP-5 |
| 61 | `SportsText.kt:238-241` | not-listed toast | per league | WP-5 |
| 62 | `ui/.../sports/TeamLogo.kt:28-39` (`coilLogoFetch`) | Coil + `LogoTrim` | + `ChlLogoCheck` transformation **for CHL URLs only** (§3.5, S9) | WP-5 |
| 63 | `TeamLogo.kt:44-68` (`LogoDisc`) | 44 px disc | capsule for codes > 3 characters (§6.4) | WP-5 |
| 64 | `tools/fakepanel/` | NHL / ESPN routes | `sports_chl.py` (§7.3) | WP-3 |

Not changed:
- `NhlMapper`, `NhlScoreSource`, `EspnMapper`, `EspnScoreSource`, `NflBug*`;
- `FullScreenHost.kt:282` (reads `prefs.nhlStyle`; row 3 decides its use);
- the Stacked painters (geometry is league-independent).

The reviewer's grep confirmed these fall correctly to the date / NHL branch for the CHL:
`PlayerStateMachineImpl.kt:2091/2096` (`todayDivider`), `SportsScreen.kt:390` (day chips), `SportsViewModel.kt:244`
(`order`). The card focus map keyed by `game.id.id` is per board, so it is fine.

---

## 2. Data source (`HockeyTechScoreSource`, `:data`)

### 2.1 Endpoints

`K` = the league's key, `C` = client code (`ohl`, `whl`, `lhjmq`), `F` = `https://leaguestat-b9523.firebaseio.com/svf/C`.

| Use | Request | Answer (verified 2026-11-13) |
|---|---|---|
| Day list | `GET https://lscluster.hockeytech.com/feed/index.php?feed=modulekit&view=gamesbydate&fetch_date=YYYY-MM-DD&key=K&client_code=C&fmt=json&lang=en` | gzip ~4.7 KB (39 KB raw) for 7 games; `max-age=240`; `SiteKit.Gamesbydate[]`, `SiteKit.Copyright` |
| Teams | `…&view=teamsbyseason&season_id=S` | ~1 KB gzip |
| Seasons | `…&view=seasons` | `season_id`, `season_name`, `playoff` "0/1", `start_date`, `end_date` |
| Live clock | `GET F/runningclock/games/{id}.json` | 108 B, `no-cache`, no gzip |
| Status / progress | `GET F/publishedclock/1/games/{id}.json` | ~250 B; changes at stoppages |
| Score | `GET F/goalssummary/1/games/{id}.json` | ~550 B |
| Shots | `GET F/shotssummary/1/games/{id}.json` | ~300 B |
| Penalties | `GET F/penalties/1/games/{id}.json` | 1–6 KB |
| Goals | `GET F/goals/1/games/{id}.json` | up to ~10 KB late in a game |
| Shootout | `GET F/shootouts/1/games/{id}.json` | WHL, QMJHL (OHL: no node seen; `null`) |
| Today overlay | `GET F/runningclock/games.json?orderBy="DatePlayed"&equalTo="YYYY-MM-DD"`, and the same on `goalssummary/1` | 2–3 KB per league. Also works on `publishedclock/1`, `shotssummary/1`, `goals/1`; **not** on `penalties/1` (its `DatePlayed` is empty) |
| Logo | `GET https://assets.leaguestat.com/C/logos/{file}` | PNG or JPEG (§3.5) |

Facts:
- **Firebase.** The root (`/svf.json`) is closed: 401 `{"error":"Permission denied"}`. Each league's subtree is open.
  It holds **recent games of unbounded age, not guaranteed** (L1): 13–19 entries per league were seen, the oldest
  from 2025-12-06. The design depends on no count.
- **`lscluster` errors:**
  - without a valid key, 200 `text/html` "Invalid key.";
  - a key for another client, "Client access denied.";
  - a missing `gc` game, 404 `{"error": "No such game"}`.
- **`lscluster` echoes the key** in `SiteKit.Parameters.key`.
- **Conditional GETs:** `If-None-Match: <ETag>` on a Firebase node returns 304 with an empty body. The ETag comes
  with `X-Firebase-ETag: true`.

### 2.2 Keys, redaction, telemetry

- **Where the keys live:** `data/src/main/kotlin/app/thebox/data/sports/HockeyTechEndpoints.kt`.

  ```kotlin
  internal object HockeyTechKeys {   // public keys embedded in the leagues' own sites (owner decision 1)
      val OHL = "<from the OHL site>"; val WHL = "<from the WHL site>"; val QMJHL = "<from the QMJHL site>"
      init { listOf(OHL, WHL, QMJHL).forEach(Redact::registerSecret) }   // (L6) at class initialisation
  }
  ```

  - The values are in the feasibility report and the reviewer's notes, not in any doc or fixture.
  - `HockeyTechScoreSource`'s companion touches `HockeyTechKeys` on load, so the keys are registered before any
    request or exception can carry them.
- **Logging:**
  - The source logs only `{kind} → {state}` transitions, never a URL, header or body. The kinds are "board", "teams",
    "seasons", "live", "logo".
  - A failure logs the exception **class name only**, never its message. A `JsonDecodingException` message embeds an
    input excerpt that can include `SiteKit.Parameters.key` (L6).
  - `BoxLog.emit` runs `Redact.text` on every line (`BoxLog.kt:23`): query strings and registered secrets are
    stripped, as a second defence.
- **Bodies:** never logged, never kept after mapping, except the last body per ETag node, in memory. The mapper
  ignores `SiteKit.Parameters`.
- **Diagnostics export:** `ExportRedactionTest` gains cases for:
  - a log line with each key;
  - a full `lscluster` URL;
  - a stack trace whose message carries the key (L6).
- **No telemetry:**
  - requests carry only the IP, the default UA, a date, a game id, a season id and the public key;
  - zero requests unless a game is followed or Sports / the picker is open.
- **Connections:** the sources use the `@ApiClient` OkHttp, not `StreamConnectionBroker` (the broker is for stream
  connections). Every CHL host is HTTPS.

### 2.3 The day list

**"Today"** (P7, `SportsDays`, `:core`, **commit 0** (S11)): for the CHL, `today(league, nowUtc) = (nowUtc at zone − 5 h)
.toLocalDate()`, the 5 h taken on the **local wall clock** so the day turns over at 05:00 local on DST days too. (C0 fix
pass, deviation 4) The NHL and the NFL keep the Eastern calendar date with **no rollover**, exactly today's
`LocalDate.now(EASTERN)`.

| League | Reference zone | Why |
|---|---|---|
| OHL | `America/Toronto` | all Eastern (Toronto, New York, Detroit for Flint / Saginaw) |
| QMJHL | `America/Toronto` | Quebec teams Eastern; Atlantic and Newfoundland games end earlier in the evening |
| WHL | `America/Vancouver` | westernmost games (BC, WA, OR) |
| NHL | `America/New_York` | today's `EASTERN`, as before (used only for the date fallback) |

- The board for date `D` is `gamesbydate&fetch_date=D`. **`scorebar` is not used**: its window follows the server's
  own "today" (verified: an ignored `date` parameter returned 2026-11-14 at 02:00 UTC).
- Yesterday / Tomorrow chips = `today ± 1`. `DayBoard.prevDay/nextDay` = `D ± 1`.
- **Board assembly** (`day(D)`):
  1. `gamesbydate(D)`. The source keeps the last body per date with its `receivedMono`, and does not re-request it
     inside 240 s (OkHttp cache stays off).
  2. `teamsbyseason(season_id)` once per process per (league, season). On failure, cards show the code plate and the
     next board retries.
  3. `seasons` once per process per league (playoff flag).
  4. **Today only**, while a game is live or about to start: the Firebase overlay (`runningclock` + `goalssummary`
     for `DatePlayed == D`) at `DAY_POLL_LIVE_MS` (30 s). (C0 fix pass B4) The mapper's
     `overlay(board: ChlBoard, runningclock, goalssummary): ChlBoard` takes the facts with the board, because §3.4's clock
     table needs each game's playoff flag and OT length. Its clock, status and scores replace the board's for
     matching ids. A failed overlay keeps the board ("Updated 2 min ago").
- REFUSED day failures: §2.6.

### 2.4 The followed game

`game(id, startUtcMs)` has two modes.

**Board mode** (S5): only when the game is **board-SCHEDULED** (status 1, start in the future), or **board-FINAL and
evicted** from Firebase. The game is read from the cached `gamesbydate` board of its date. Board without the game →
`Fetch.NotFound`.

**Live mode** (any game that is not in board mode): each poll requests **all six nodes in parallel**, each with
`If-None-Match` (S4). OkHttp `enqueue`, one coroutine each, all cancelled together; one HTTP/2 connection.

| Node | Required? | On failure |
|---|---|---|
| `runningclock/games/{id}` | yes | poll `Failed(kind)` |
| `goalssummary/1/games/{id}` | yes | poll `Failed(kind)` |
| `publishedclock/1/games/{id}` | no | status from `runningclock.status_id` |
| `shotssummary/1/games/{id}` | no | `sog = null` |
| `penalties/1/games/{id}` | no | `powerPlayUnknown = true` (H8) |
| `goals/1/games/{id}` | no | `lastGoal = null`; `powerPlayUnknown = true` (H6) |
| `shootouts/1/games/{id}` | no | added only when the **previous** snapshot was a possible shootout (§3.3 (b)/(c)), WHL and QMJHL |

- **ETags:** for each (game, node) the source keeps the last 200 body and its ETag; a 304 reuses it.
- **`runningclock == null` in live mode** (S5): the board says status 2 or 4 → `Failed(BAD_BODY)` (the machine dims,
  then hides, as usual). The board says status 1 → board mode for this poll. The board lacks the game → `NotFound`.
- **`Fetch.Ok` metadata:**
  - `receivedMono` / `serverDateMs` come from the `runningclock` answer;
  - `ageS = 0`;
  - `wireBytes` = the sum over the poll (WireMeter);
  - `maxAgeS = null`.
- A 10 s `SPORTS_CALL_TIMEOUT_MS` is kept. Polls never stack (the next waits for completion), and the meter counts
  a timeout's elapsed time (L10).

### 2.5 Poll timing and floor

- **From completion:** next live poll = `doneMono + interval` (`ScoreBugMachine.kt:373`).
- **Floor 5 s; choices 5 · 10 · 15 · 20 · 30; Auto ≥ 5 s**, recommended from the **followed** league's meter (P1,
  L4).
- **Pre-game:** one poll on Follow (board mode); sleep until `start − PRE_WAKE_MS` (2 min); then every `PRE_POLL_MS`
  (40 s) from completion (live mode, which Firebase answers once it lists the game) until `status_id == 2`.
- **Breaks:** the machine's unchanged `breakMs()` = `max(2 × interval, 20 s)` (L3).
- **Not in FULL:** `max(phase cadence, 120 s)` (unchanged). A final game stops polling (unchanged).
- **Why 5 s:**
  - the running clock is real time and is shown as polled (ruling 6), so the interval is the clock's step;
  - 5 s is the NFL floor the owner already accepted for an undocumented host.
- **Wire cost per poll** (S4: six requests, one HTTP/2 connection, compressed headers):
  - `runningclock` ~0.3 KB;
  - five 304s at ~0.15 KB each;
  - a changed `shotssummary` ~0.5 KB, most polls in play;
  - a changed `penalties` 1–6 KB, or `goals` ≤ 10 KB, a few times a game.
  - Estimated **~1.5–2 KB per poll**, about **1.4 MB an hour at 5 s**. `EST_WIRE_BYTES_CHL = 2_000` until measured.

### 2.6 Errors and their texts

| Answer | `Fetch` | User text (Sports tab / picker; §6.5) |
|---|---|---|
| No default network | `DayState.Failed(OFFLINE)` | existing "No internet connection…" |
| Timeout / IO / 5xx / HTML 404 | `Failed(TIMEOUT / IO / HTTP_5XX / HTTP_OTHER)` | "OHL scores did not answer. Press OK to try again; scores may be down for a few minutes." |
| 429 | `Failed(HTTP_429)` + `Retry-After` | same text; the meter's slowed note |
| `lscluster` 200 non-JSON body starting `Invalid key` / `Client access denied`; Firebase 401 / 403 | `Failed(REFUSED)` | "OHL scores stopped letting this app in. CHL scores need an app update; NHL and NFL scores still work." |
| Non-JSON, over the cap, unmappable; a live-mode `null` (S5) | `Failed(BAD_BODY)` | the "did not answer" text |
| Board without the followed game | `NotFound` | toast "The OHL no longer lists LDN – KIT. Follow another game in Sports." |

- **REFUSED (S7):**
  - The machine's next follow poll is at `receivedMono + BACKOFF_MAX_MS` (row 12); the next day request at
    `fail + BACKOFF_MAX_MS` (row 37). Never faster, whatever `Retry-After` says.
  - A follow keeps its game: the bug dims, then hides on the existing timers.
  - The tab and the sheet show the REFUSED text.
  - One log line per state change.
  - Partial refusals stay partial: `lscluster` refused leaves a live follow working; Firebase refused fails the
    follow and the Today overlay but not the board.
  - Tests: machine and repository back-off at 300 s for REFUSED; 5 s / 20 s bases unchanged for the other kinds.
- **P13 (`gc` fallback when Firebase is refused): not in v1** (lead). S5 makes such an outage visible.

### 2.7 Body limits and parsing

- The streaming-parser rule (CLAUDE.md) is for unbounded playlists and EPGs. These bodies are bounded and decoded
  whole as `JsonObject` (as `NhlScoreSource`).
- Caps are checked on `body.source()` before decoding: `CHL_NODE_MAX_BYTES` (128 KB) per Firebase node and overlay;
  `MAX_BODY_BYTES` (512 KB) for `lscluster`. Over the cap → `BAD_BODY`.
- Never the season `schedule` view (1.7 MB raw).

### 2.8 Debug override and fake panel

- `files/debug/sports_base` (debug builds only, `NhlScoreSource.kt:39-73`) also redirects the CHL:
  - `{origin}/hockeytech/feed/index.php`;
  - `{origin}/firebase/svf/`;
  - `{origin}/leaguestat/`, replacing `https://assets.leaguestat.com/`.
- `HockeyTechEndpoints.fromOverride` mirrors `SportsEndpoints.fromOverride`.
- Fake panel: §7.3.

---

## 3. Mapper (`HockeyTechMapper`, `:core`, pure)

No I/O, no clock. Inputs are `JsonObject`s (or null for a missing node). Every field is optional and lenient. An
unmappable body yields null, and the source turns it into `BAD_BODY`.

### 3.1 Leniency rules

- **Int-or-string:**
  - `"2"`, `2` and `"02"` are all 2;
  - `period` is an int in some answers and a string in others;
  - `status` is a string on `lscluster`, an int on Firebase.
- **Clocks:**
  - `Minutes` / `Seconds` are two-digit strings; `"-"` = unknown (pre-game);
  - `game_clock` `"00:02:04"` and `"02:04"` both parse;
  - remaining seconds outside `0..periodLength` → clock null.
- **Elapsed times** (`Time`, `TimeStart`, `TimeOn`, goal `Time`) are `"mm:ss"`; unparseable → null.
- **Codes:**
  - `trim().uppercase(Locale.ROOT)`;
  - must match `[A-Z0-9]{2,4}`, else the game is dropped from a board, and a followed snapshot is null (BAD_BODY).
- **Names:**
  - full name = `"$city $nickname"`, else `name` with `", "` → `" "` (the QMJHL's "Cape Breton, Eagles"), else the
    code;
  - card name = `nickname`, else the full name (P10);
  - `lang=en` always.
  - Notes: OTT's card name is "67's". SOO's full name is "Sault Ste. Marie Greyhounds" (city + nickname), although the
    feed's `name` says "Soo Greyhounds"; that is fine.
- **Booleans:** `true`/`false`, `"1"`/`"0"`, `1`/`0`.
- **Player ids:** `PenalizedPlayerId` 0 = null (B2; the feed sends 0 for bench minors).

### 3.2 Board game (`gamesbydate` entry) → `GameSnapshot`

| Snapshot field | Source | Rule |
|---|---|---|
| `id` | `game_id` | digits only, else drop |
| `dayKey` | `date_played` | else the board's date |
| `startUtcMs` | `date_played` + `schedule_time` in `timezone` | unknown zone → the league's reference zone; `time_tbd == "1"` → midnight local and "Time TBD" (`startTbd = true`, C0 fix pass B2) |
| `away` / `home` | `visiting_team_*` / `home_team_*`, `*_goal_count`, `*_shots` | score null while status 1; logo by team id (§3.5) |
| `phase` | `status`, `final`, clock | §3.4 |
| `period`, `clock` | `period`, `game_clock` | display only; Today's overlay replaces them |
| `periodKind`, `otNumber`, `finalKind` | `period`, `shootout`, `final` | §3.3 |
| period lengths, playoff | `periods{n}.length`, `playoff` | kept per game in the source for §5 (`"300"` for a regular-season OT) |

### 3.3 Followed snapshot (Firebase nodes) → `GameSnapshot`

| Field | Source | Rule |
|---|---|---|
| scores | `goalssummary.VisitorGoalTotal` / `HomeGoalTotal` | ≥ 0. The shootout winner's +1 is in the total at the final (verified 4–4 → 5–4, `Final SO`). The mapper never adds its own; ruling 11 works unchanged |
| `sog` | `shotssummary.*ShotTotal` | `0..SOG_MAX` |
| `period` | `runningclock.Clock.period`, else `publishedclock.PeriodId` | int-or-string; adjusted by §3.4 for the "20:00" break form (S12) |
| `clock` | `runningclock.Clock` | `GameClock(secs, running, inIntermission = phase == INTERMISSION)` |
| `periodKind` | ≤ 3 → REG; ≥ 4 → OT; a shootout → SO | |
| `otNumber` | `period − 3` (verified "Final OT2" = 5, "OT3" = 6) | |
| shootout | (a) `goalssummary.Shootout == true`; (b) `shootouts.ShootoutAttempts` non-empty; (c) regular season, period 4, 0:00 stopped, status 2, scores tied | (a) is false during a live shootout (verified, QMJHL 32755). No English text is read |
| `finalKind` | FINAL + shootout → SO; FINAL + period ≥ 4 → OT; else REG | |
| `lastGoal` | the `goals` entry with the greatest `(Period, Time, LSEventId)` → `GoalFact(IsHome, totals after it, PowerPlay == 1)` | the per-side counts up to it must equal the snapshot score, else null |
| `powerPlay`, `powerPlayUnknown` | §5 | `powerPlayUnknown = true` when a §5.6 rule fires or `penalties` / `goals` failed |

**Period length:** periods 1–3 = 1200 s. OT = the board's `periods["4"].length` (verified: present once OT starts,
absent before). Fallbacks: 300 when the season is regular (`playoff == "0"`), 1200 in the playoffs. Unknown → H1.

### 3.4 Status, intermission and phase

Status = `publishedclock.StatusId`, else `runningclock.status_id`, else the board's `status`. `Final == true` (or
board `final == "1"`) wins. `Started` = `publishedclock.Started`, or any score, or `status_id ≥ 2`.

| Status | Phase |
|---|---|
| `Final`, or 4 | FINAL |
| 5 (forfeit, "Home Forfeit" seen) | FINAL |
| 2 | from the clock (below) |
| 1 | SCHEDULED |
| 3 or anything else, **not Started** | SCHEDULED (P5), logged once per change |
| 3 or anything else, **Started** (S6) | from the clock (below), as if 2 |

- **Postponed** (unverified, Q1): no flag was seen and no English text is parsed. A postponed game stays SCHEDULED
  and its follow expires 8 h after the listed start (the existing rule). `RemoveReason.POSTPONED` is never raised for
  the CHL in v1.

**From the clock** (`P` = period, `r` = remaining s, `L` = the length of `P`):

| Feed | Snapshot | Centre text |
|---|---|---|
| running | LIVE, `period = P` | clock + "2nd" |
| stopped, `0 < r < L` | LIVE (a whistle) | clock + period |
| stopped, `r == 0`, game **decided**: `P ≥ 3` and scores differ (S3) | LIVE, `period = P`, `clock = 0`, not in intermission; `powerPlay = null`, `powerPlayUnknown = false` | **"End 3rd" / "End OT"** (`CentreText.EndOf`), until `Final` |
| stopped, `r == 0`, regular season, `P == 4`, tied | shootout (§3.3 (c)) | "SO" |
| stopped, `r == 0`, otherwise (play can follow) | INTERMISSION, `period = P`, `clock = 0`, `inIntermission = true` | **"{P} INT"** ("1st INT"; Stacked "1ST" over "INT") (P4) |
| stopped, `r == L`, `P ≥ 2` (the operator advanced the period during the break; seen as "20:00 3rd") | INTERMISSION, **`period = P − 1`, `clock.secondsRemaining = 0`, `inIntermission = true`** (S12) | "{P−1} INT" |
| stopped, `r == L`, `P == 1` | LIVE (status 2 before the opening faceoff) | "20:00" + "1st" |

- S12: with `period = P − 1` and remaining 0, the machine's `label(s)` (`ScoreBugMachine.kt:814`), the card's
  `StateLine` and §5's `nowG` all agree. H2 does not fire on a penalty already logged in period `P` (its period is
  `≤ P`; §5.6 H2 compares against the **raw** feed period, which the mapper passes in `ChlNow.feedPeriod`).
- **The end-of-game power play (S3):** the machine's existing `ppEnded` will say "Power play over." at the final
  buzzer if a power play was running. That is true (the game ended it). The tab then closes with the decided game,
  instead of freezing until the scorekeeper finalises.
- No `EndOf` otherwise: "End 1st" and "1st INT" cannot be told apart in the feed (P4).

### 3.5 Logos (`ScoreSource.logoUrl`, `TeamLogo.kt`; CHL only)

- **URL source:** `teamsbyseason` `team_logo_url` for the team id, held per (league, season).
- **Validation:**
  - must start with `{logoBase}{client}/logos/`;
  - the rest must match `[0-9]+(_[0-9]+)?\.(png|jpg|jpeg)`;
  - no `..`, no query; otherwise there is no logo.
  - `50x50/` is never used.
- **JPEG rule (P6):** a `.jpg`/`.jpeg` URL is replaced by `{logoBase}{client}/logos/{teamId}.png`.
- **No-alpha rule (S9, CHL only):** a `ChlLogoCheck` Coil `Transformation` is added to the request **only when the URL
  is under the CHL logo base**. It runs **before** `LogoTrim`, and **throws** if `!bitmap.hasAlpha()` or all four
  corners have alpha > `LogoTrim.ALPHA_FLOOR`. The request then ends in `ErrorResult`, so the logo is null and the
  disc / capsule shows. A transformation cannot return null, and after trimming the corners of a square badge can be
  opaque. NHL and NFL requests are built exactly as today.
- **Outcomes** (all 61, 2026-11-13; `tools/chl/logo_audit.py` → fixture `logos/logo-outcomes-2026-11-13.tsv`) (S10):

| Outcome | Teams |
|---|---|
| PNG RGBA with transparent corners: **logo** | 55, including **NIAG (`20.png`) and CAP (`3.png`) recovered by the JPEG swap**; MON's PNG is interlaced (Android decodes it) |
| 404 after the swap: **fallback** | BRAM, BDN, KEL, PEN, SAS |
| `222.png` is RGB with no alpha (opaque corners): **fallback** | WEN |

  BRAM (4 letters) falls back, so **the capsule ships on day one** (§6.4). The two recovered id-only PNGs may be an
  older mark than the season-suffixed JPEGs (`_295`), so they go on the owner's sign-off sheet.
- Coil decodes PNG natively. Logos are never bundled; the fixtures hold the outcome table, not the images.


### 3.6 Implementation notes (WP-1 fix pass; the reviewed departures, so this document matches the code)

The WP-1 review (`docs/review/2026-11-14-chl-wp1-mapper-review.md`) accepted these 11 departures from the text above.

| # | Rule as implemented | Why |
|---|---|---|
| 1 | H3 applies only to a chain's first link (§5.6) | The edited two-row form gives link 2 a future `TimeStart` by design (E30, E32); with S1 a later link's own start no longer matters |
| 2 | Period ≥ 4 with unequal scores → no power play at any clock, not only at 0:00 (§3.4 S3) | CHL OT is sudden death (regular season and playoffs): an unequal OT score means the game is over |
| 3 | **Started** = `publishedclock.Started`, or a score > 0, or any status source (`StatusId`, `status_id`, the board) ∈ {2, 4, 5}; the board's own `started == "1"`; status 1 is always SCHEDULED; the overlay also keeps a game the board shows in play started (review S3) | "status ≥ 2" would make the "3, not Started" row unreachable. **Watch item (S8):** `publishedclock` wins over `runningclock`, so a lagging `StatusId 1` would hide a live game; the recorded samples always agree |
| 4 | Playoff flag unknown → regular season iff the board lists a 300 s OT (`periods["4"].length`) | A conservative inference from data; otherwise OT → H1 |
| 5 | Periods 1–3 use the board's listed length when present (1..3600 s), else 1200; OT the board's, else 300 / 1200 by the playoff flag, else unknown (H1) | Strictly more information; identical on every fixture |
| 6 | Extra Unknown cases: no feed period, no clock, a needed row with no side or period, a flagged row with period < 1, `ppAttempts` unknown with `penalties` null (§5.6) | Each one hides; none can show |
| 7 | `goalTotal` = both totals − 1 when `Shootout` is true; `lastGoal` is null when the per-side goal counts differ from the score (so a Final SO has none) | §3.3; the −1 is unreachable in play |
| 8 | Elapsed times strictly `mm:ss`, seconds 0..59 (else unparsable → H2); a call or start past its period's length → H2 | Prefer hiding |
| 9 | Board: an absent or null `Gamesbydate` is an empty day, another shape or an unparsable day is unmappable; board clocks are not running (no flag); `time_tbd == "1"` or an unparsable `schedule_time` → `startTbd`, local midnight in the league's reference zone | The board is a cached string clock |
| 10 | Overlay: the date check is per entry (a missing `DatePlayed` is accepted, so the unfiltered node also works); a missing score falls back to the board's, never 0; scores-only re-classifies the board's (already adjusted) clock | |
| 11 | `followed()` always sets `startTbd = false` | `ChlGameFacts` (frozen) has no TBD flag: the source copies the board snapshot's `startTbd` for a followed TBD game |

Also (review L1, L7): a row without `LSEventId` takes its collection key (the index in Firebase's array form), else a
negative ordinal, so two rows never share an id; a `seasons` entry without a `playoff` flag is absent (unknown), so the
board entry's own `playoff` decides.

---

## 4. Machine and repository

### 4.1 What changes

- A CHL game is an NHL game to `ScoreBugMachine` (goal detection by score delta, `goalWasPp`, power-play gating,
  Final, expiry, delay queue), except:
  - timing: `timed()` (§1.6 #14–15);
  - the league is stamped on hockey views (B1, #13a);
  - the unknown-PP hold (B3, #13b).
- **Unknown-PP hold (B3, commit 0):**
  - **State:** `ppUnknownSince: Long?`, the `anchorMono` of the first consecutive shown snapshot with
    `powerPlayUnknown`. It resets on any known snapshot, a follow change and the delay back-in-time path (`:486-487`).
  - **`advance` (`:536-544`):** for a shown snapshot `s` with `s.powerPlayUnknown`:
    - while `anchor − ppUnknownSince ≤ PP_UNKNOWN_HOLD_MS` (30 000): `pp = lastShownPp` (unchanged),
      `ppEndedEntry = null`, `lastShownPp` kept;
    - after that: `pp = null`, **`ppEndedEntry = null`** (silent), `lastShownPp = null`.
    - A known snapshot after a held one compares against the held `lastShownPp` as usual. PP → unknown → PP therefore
      announces nothing; PP → unknown → none (known) announces "Power play over." once, which is true.
  - **`view()` (`:300`):** `powerPlay = if (fresh) (if (s.powerPlayUnknown) heldPp else gatedPp(s)) else null`, where
    `heldPp` is `lastShownPp` inside the hold, else null. The held tab keeps its last label and frozen seconds.
  - **`goalWasPp`:** (C0 fix pass, deviation 2) takes the previous power play as a parameter: `p.powerPlay`, or the held
    one when `p` was unknown (an unknown snapshot's own `powerPlay` is null, so "unchanged" could not work). A goal inside
    the hold is judged by `lastGoal` first, then by the held power play. NHL results are identical.
  - **The boundary** (C0 fix pass, deviation 1): the hold lasts while `anchor − ppUnknownSince < PP_UNKNOWN_HOLD_MS`
    (strict), so it is at most 30 s and the 7th unknown poll at 5 s closes it, as the test below says.
  - **Only in play** (deviation 3): an unknown FINAL or SO snapshot holds nothing.
  - **A goal inside the run** (C0 fix pass, commit-0 review B1): when an unknown snapshot raises the score of the side on
    the held power play, the hold is dropped silently (the goal released the minor; the mark's PP GOAL says so). A
    short-handed goal keeps it. Any score change inside the run is carried into the first known snapshot after it as a
    score change, so the known "none" that follows never says "Power play over.".
  - **By elapsed time** (C0 fix pass, review S4): the hold also ends at `ppUnknownSince + delay + PP_UNKNOWN_HOLD_MS` with
    no new snapshot (breaks, slower intervals, back-off). `nextWakeAtMono` wakes for it; `view()` never draws a held tab
    past it.
  - **Delay grown inside a run** (C0 fix pass, review L1): the snapshot shown again restarts the hold from the queue: a
    known one holds its own power play; an unknown one holds the last known power play before its run, unless the held
    side scored inside the run.
  - Tests:
    - PP → unknown → PP: nothing spoken, the tab never closes;
    - PP → unknown × 7 (35 s at 5 s): the tab closes silently at the 30 s anchor;
    - PP → unknown → known none: "Power play over." once;
    - the delay queue: the hold runs on the delayed timeline (anchor ≠ arrival in the test);
    - (fix pass) PP → unknown + PP goal → known none: no "over", no bar after the goal; a short-handed goal keeps the
      hold; the hold ends by time with no new poll; a delay grown inside a run holds the last known power play.
- `ScoreRepositoryImpl`:
  - five-league `Map<League, ScoreSource>` with no silent fallback (#35);
  - a CHL sub-league switch behaves like any league switch;
  - meters are kept per `League`, and the machine runs the followed league's (`:582-592`);
  - the settings row shows the followed league's meter when a CHL game is followed (L4, #21).
- Demand, standby, screen-off and "Show Sports: Off": unchanged.

---

## 5. Power-play derivation (`ChlPowerPlay`, `:core`, pure)

The executable reference is `tools/chl/pp_replay.py` (same rules, one-second steps). The Kotlin implementation must
reproduce its replay numbers (§7.2).

### 5.1 Inputs and output

```kotlin
data class ChlPenalty(
    val id: Long,            // LSEventId
    val home: Boolean,       // Home == 1: the penalised team is the home team
    val period: Int,         // Period
    val timeS: Int?,         // Time (elapsed in period, when called)
    val startS: Int?,        // TimeStart when parsable (and TimeStartSet != 0)
    val onS: Int?,           // TimeOn when TimeOnSet == 1 and parsable
    val minutes: Int?,       // Minutes
    val ppFlag: Boolean,     // PowerPlay == 1
    val penaltyShot: Boolean,// PenaltyShot == 1
    val playerId: Long?,     // PenalizedPlayerId; 0 → null (B2)
)
data class ChlGoal(val id: Long, val home: Boolean, val period: Int?, val timeS: Int?, val pp: Boolean,
                   val sh: Boolean, val penaltyShot: Boolean)
/** [period] / [remainingS] after §3.4's adjustment; [feedPeriod] the raw feed period (S12). */
data class ChlNow(val period: Int, val remainingS: Int, val feedPeriod: Int, val phase: GamePhase, val shootout: Boolean,
                  val decided: Boolean)
sealed interface ChlPp { data object None : ChlPp; data object Unknown : ChlPp; data class Shown(val pp: PowerPlay) : ChlPp }

object ChlPowerPlay {
    fun derive(pens: List<ChlPenalty>?, goals: List<ChlGoal>?, goalTotal: Int, ppAttempts: Int?, now: ChlNow,
               periodLength: (Int) -> Int?): ChlPp
}
```

- The mapper sets `powerPlay` from `Shown`, and `powerPlayUnknown = (result == Unknown)`.
- `PowerPlay(side, kind, secondsRemaining)` keeps the existing meaning: `side` has the advantage; `TWO_MAN` reads
  "5-ON-3".

### 5.2 Game time

- `g(period, elapsed) = Σ_{q < period} periodLength(q) + elapsed`.
- `nowG = g(now.period, periodLength(now.period) − now.remainingS)`.
- A break after P gives `nowG = g(P, L(P)) = g(P+1, 0)`.

### 5.3 Algorithm

(WP-1 fix pass L3) A node with more than 80 counted rows (links) or more than 80 goals is not a real game's (≤ ~40
rows, ≤ ~15 goals): the derivation returns Unknown before the model runs, so a hostile body cannot cost seconds per poll.

1. **Counted rows.** A row counts iff all of these hold:
   - `ppFlag`;
   - `!penaltyShot`;
   - `minutes ∈ {2, 4, 5}`;
   - `period ≥ 1`;
   - a parsable start.
   - Excluded: `minutes` 0, 10, 20 and other values, and every unflagged row. The scorekeeper's flags net coincident
     penalties: the matching 7:55 roughing pair is 0/0; fight majors are 0.
   - A 4 = two chained 2-minute links.
   - Bench minors count when flagged (`Bench` is ignored).
2. **Start.** `startS ?: timeS`, except (S2): if `onS != null && startS != null && onS ≤ startS && startS + 60 ×
   minutes ≤ L(period)` (the penalty would end inside its period, yet its recorded end is not after its start), the
   row is inconsistent and its start is `timeS`. `s = g(period, start)`.
   - `TimeStart` carries real delayed-call and stacked starts (OHL 29185 P2: called 10:26, start 10:36). Dropping it
     adds a replay miss, so it stays.
3. **Chains (B2).** Rows with the same `(side, period, timeS, playerId)`, `playerId` non-null, form a chain:
   - no offence in the key: one player's tripping + unsportsmanlike are served one after the other;
   - `playerId` null (0 in the feed, bench minors) never chains: two bench minors at one stoppage are two seats;
   - **links are ordered by `(s, major first, id)`**, not by id: in the OHL's edited form the later link has the lower
     id (29179 P2 15:07: 454471 starts 15:16, 454472 starts 15:07);
   - (WP-1 fix pass S2) on an equal start, a major is served before a minor (the standard rule: one player's major and
     minor at one stoppage serve the major first). **No recorded game exercises this** (no chain contains a 5); E35;
   - (WP-1 fix pass S1) **a later link starts at the previous link's end**; its own `TimeStart` only orders the links.
     A pre-filled link-2 `TimeStart` (the two-row form before the scorekeeper edits it after a PP goal) therefore gives
     no gap and no phantom tail; E34. The recorded replay is byte-identical (no recorded chain has a gap).
4. **Two seats per side.** At most 2 rows serve at once per side. A further row waits and starts when a seat frees.
5. **Scheduled end** `e = start + 60 × minutes` (per link: 120).
6. **Recorded end.** If `onS` is set, find the first `x ∈ {g(period, onS), g(period + 1, onS)}` with `start < x ≤ e`,
   and set `e = x`. Otherwise ignore `onS`. This is a cap only (the replay is identical without it).
7. **Goals release minors.** Walk game time in order. At each non-penalty-shot goal `t`, evaluated **before**
   expiries at `t`:
   - if the scorer's opponent has more serving rows, release its serving **2-minute** row with the earliest start
     (tie: lowest `id`): `e = t`;
   - the next link or waiting row starts at `t`;
   - majors are never released;
   - the goal's own `pp` flag is not used here (it is checked in H5).
   - Expiry is half-open.
8. **At `nowG`.** `a` / `h` = serving rows for away / home.
   - `a == h` → None;
   - else side = the team with fewer serving;
   - `kind = TWO_MAN` if `|a − h| ≥ 2`, else `ONE_MAN`.
9. **Seconds remaining** = the time until the advantage shrinks:
   - each serving row sits in a seat; the seat's end is extended by any row that follows on in it **with no gap** (the
     next link of its chain; else a waiting row of the side while the other seat is still busy);
   - seconds = `min(seatEnd − nowG)` over the **short-handed** side's seats, kept only in `1..PP_SECS_MAX` (else H4);
   - so a double minor counts down once from 4:00, and a stacked 5-on-3 runs to the first seat that empties (Q5).
10. **Phases.** Only LIVE or INTERMISSION; not a shootout; not FINAL; not `now.decided` (S3) → None.

### 5.4 Overtime, breaks and carry-over

- **Regular-season OT** is 3-on-3 for 300 s (verified). One penalty → 4-on-3 ("POWER PLAY"); two → 5-on-3 ("5-ON-3",
  literally true); one each → None. **Playoff OT** is 1200 s (from the board).
- **Break:** `nowG` is the end of the period, so the seconds are frozen at the buzzer value (ruling 19).
- **Carry-over:** P1 19:30 with 120 s ends at `g(2, 90)`; its `TimeOn` "01:30" resolves to period 2 (step 6).

### 5.5 Worked examples (each becomes a `ChlPowerPlayTest` case)

Notation:
- `A2@9:00` = an away 2-minute, PP-flagged row at 9:00 elapsed; `Hg@9:30 pp` = a home goal with its flag;
- now = (period, remaining);
- periods are 1200 s, OT 300 s.
- E1–E28 were checked by hand by the reviewer (L11); E29–E33 were added by the revision.

| # | Situation (period 1 unless stated) | Now | Expected |
|---|---|---|---|
| E1 | `A2@9:00` | (1, 10:00) | HOME, ONE_MAN, 60 |
| E2 | E1 + `Hg@9:30 pp` | (1, 10:00) | None |
| E3 | `A2@8:30`, `A2@9:00` (different players) | (1, 10:40) | HOME, TWO_MAN, 70 |
| E4 | `A2@5:00` and `H2@5:00`, both flag 0 | (1, 14:30) | None |
| E5 | `A5@2:00` (flag 1) + `Hg@3:00 pp` | (1, 16:00) | HOME, ONE_MAN, 180 |
| E6 | `A2@19:30` | break after P1 (P1, 0:00 stopped) | INTERMISSION; HOME, ONE_MAN, 90 |
| E7 | as E6, feed shows (2, 20:00) stopped | — | INTERMISSION "1st INT", snapshot period 1, remaining 0; HOME, ONE_MAN, 90 |
| E8 | as E6 | (2, 19:40) | HOME, ONE_MAN, 70 |
| E9 | double minor `A2@5:00` × 2, one player (links 5:00–7:00, 7:00–9:00) | (1, 14:00) | HOME, ONE_MAN, 180 |
| E10 | E9 + `Hg@6:30 pp` | (1, 13:00) | HOME, ONE_MAN, 90 |
| E11 | E10 in its edited form (second row `TimeStart` 6:30) | (1, 13:00) | as E10 |
| E12 | stacked `A2@1:00`, `A2@1:30`, `A2@2:00` (three players, `TimeStart` = `Time`) | (1, 17:30) | HOME, TWO_MAN, 60 |
| E13 | E12 | (1, 16:50) | HOME, TWO_MAN, 20 |
| E14 | OT 3-on-3: P4 `A2@1:00` | (4, 3:30) | HOME, ONE_MAN, 90 |
| E15 | P3 `A2@19:00` carried into OT | (4, 4:30) | HOME, ONE_MAN, 30 |
| E16 | `A2@9:00`, `TimeOn` 9:20, no goal row yet | (1, 10:30) | None |
| E17 | flagged bench minor `A2@4:00`, player 0 | (1, 15:30) | HOME, ONE_MAN, 90 |
| E18 | `A10@4:00` (flag 0) + `A2@4:00` (flag 1), one player | (1, 15:00) | HOME, ONE_MAN, 60 |
| E19 | `A2@4:00` with `PenaltyShot` 1 | (1, 15:00) | None |
| E20 | home 2 serving, away 1 | — | AWAY, ONE_MAN, home's earliest seat end |
| E21 | `Hg@8:00 pp`, no away row serving at 8:00 | (1, 11:00) | **Unknown** (H5; real case OHL 29182 P3 00:41) |
| E22 | total 3, `goals` lists 2, `A2@9:00` serving | (1, 10:30) | Unknown (H6) |
| E23 | `A2@15:00` | (1, 6:00) | Unknown (H3) |
| E24 | shootout or FINAL | — | None |
| E25 | P4, OT length unknown | (4, 4:00) | Unknown (H1) |
| E26 | `penalties` failed this poll | — | Unknown (H8) |
| E27 | `penalties` `null`, PP attempts 0 / 0 | (1, 10:00) | None |
| E28 | `penalties` `null`, PP attempts > 0 | — | Unknown (H7) |
| **E29** (B2a) | one player: `A2@16:48` tripping + `A2@16:48` unsportsmanlike (QMJHL 32755 P1) | (1, 2:30) and (1, 0:30) | HOME, **ONE_MAN**, 198 and 78. Never TWO_MAN; still a PP 18:48–20:48 |
| **E30** (B2b) | OHL 29179 P2 15:07 edited pair, ids reversed (454471 `TimeStart` 15:16, 454472 15:07) + `Hg@15:16 pp`; the 15:11 double minor + `Hg@16:50 pp` | (2, 4:50) and (2, 4:00) | (2, 4:50): HOME, ONE_MAN, 237; (2, 4:00): HOME, TWO_MAN, 76. No phantom minor to 19:16 |
| **E31** (S1) | WHL 1023281 P2: home 5-min fighting flagged 1 + away 2-min unflagged, both at 5:16 | (2, 14:30) and (2, 12:00) | (2, 14:30): **Unknown** (H9, 4-on-4); (2, 12:00): AWAY, ONE_MAN, 136 |
| **E32** (S2) | OHL 29185 P1 double minor at 5:36, both `TimeStart` 07:36, first `TimeOn` 07:36 | (1, 13:30) and (1, 9:30) | (1, 13:30): AWAY, ONE_MAN, 186; (1, 9:30): None (no phantom 9:36–11:36 bar) |
| **E33** (S3) | P3 0:00 stopped, status 2, scores 4–1, `A2@19:08` | (3, 0:00) | phase LIVE, "End 3rd"; None (decided) |
| **E34** (WP-1 fix pass S1) | double minor, two-row form: `A2@5:00` `TimeStart` 05:00 and `A2@5:00` `TimeStart` 07:00 (pre-filled), one player, + `Hg@6:30 pp` | (1, 13:20), (1, 12:50), (1, 11:20) | HOME, ONE_MAN, 110; HOME, ONE_MAN, 80; None (link 2 runs 6:30–8:30) |
| **E35** (WP-1 fix pass S2) | one player: `A2@2:00` (lower id) + `A5@2:00`, both flagged, + `Hg@3:00 pp` | (1, 15:00), (1, 12:30), (1, 11:30), (1, 11:00) | HOME, ONE_MAN, 240; 90; 30; None (major 2:00–7:00, never released, then the minor 7:00–9:00) |

E29–E32 match `tools/chl/pp_replay.py probe` on the recorded games (the `all` command prints them). E34 and E35 match
the reference after the WP-1 fix pass (before it, E34 read None / 110 / 20 and E35 180 / 30 / None).

### 5.6 Hide rules (return `Unknown`: "prefer hiding the bar to showing a wrong one")

- **H1** — `now` unknown: no clock, period < 1, or `periodLength` null for any period ≤ `now.period`.
- **H2** (with S13):
  - a counted row has an unparsable time, `minutes` null, or a period > `now.feedPeriod`;
  - or a **goal** has an unparsable period or time while any counted row is serving (its release cannot be placed).
  - (WP-1 fix pass L2) or a counted row's `Time` or `TimeStart` is past its period's length.
  - (WP-1 departure 6) also: a flagged row with period < 1; a row the model needs (flagged, or unflagged 2 / 5 for H9)
    with no parsable `Home` or `Period` (the mapper's check).
- **H3** — a counted row starts after `nowG + 5 s`. (WP-1 departure 1) **Only the first link of a chain**: a later
  link starts where the chain puts it (S1), so its recorded `TimeStart` may be in the future (E30, E32).
- **H4** — computed seconds ≤ 0 or > `PP_SECS_MAX`, or a serving row with `e − start > 60 × minutes`. The second clause
  cannot fire with today's model (`e ≤ start + dur`; `TimeOn` only caps) and stays as a guard.
- **H5** — within the last 120 s of game time, a goal flagged `pp` by a side the model did not give the advantage at
  `t`, or flagged `sh` by a side the model did not make short-handed.
- **H6** — `goals` null, or its count ≠ `goalTotal` (the shootout +1 excluded), while any counted row serves.
- **H7** — `penalties` null and `ppAttempts > 0`, or (WP-1 departure 6) `ppAttempts` unknown.
- **H8** — `penalties` or `goals` failed this poll.
- **H9 (S1)** — at the same `(period, timeS)`:
  1. pair off unflagged 2- or 5-minute rows of equal minutes across the two sides;
  2. a remaining unflagged row of one side, at a stoppage where the other side has a counted row, hides the bar
     while it is within its own duration.
  - The pairing keeps the common "roughing pair + extra minor" visible (QMJHL 32755 P1 07:29 still shows its
    double-minor PP).

### 5.7 Accepted misses (owner decision 2)

1. **Delayed penalties** show only once entered, then with the correct backdated time.
2. **Entry latency:** a PP goal ends the bar only when the goal is entered; a new penalty shows one poll after entry.
3. **Wrong or missing flags.** An unflagged real power play shows nothing; a flagged-then-cancelled one shows a bar
   until corrected. There is **no automatic "delayed-call washout" rule** (L9): the data has both forms.
   - OHL 29191 13:02: a bench minor flagged 0, with a goal on the delayed call at 13:02.
   - OHL 29181 P2 17:55 and 29192 P2 12:48: flagged bench minors at an opponent's even-strength goal second, served a
     full 2:00 (real post-goal minors).
4. **No empty-net or extra-attacker state.** 6-on-4 shows as a plain power play; an empty net at even strength shows
   nothing.
5. **Simultaneous expiry and goal** follow step 7's order (goal first).
6. **Other major / minor combinations** beyond H9 rely on the flags.
   - (WP-1 review L4) Coincident minors flagged 1 / 1 (against §5.3's 0 / 0 netting) plus a third minor and a PP goal:
     `A2@4:00`, `H2@4:00` (both flagged), `A2@4:30`, `Hg@5:00 pp`. The goal releases a coincident minor, so the model
     shows HOME 20 at 6:10 while the real game is at even strength from 6:00 (coincident minors are never released).
     Accepted: it needs a scorekeeper flag error; no rule.
7. **Seconds** = until the advantage shrinks, as computed now. In 4-on-3 (2 vs 1) it is the short-handed side's
   earliest seat; if the other side's single penalty ends first, the advantage grows and the time jumps.
8. **Finished data only.** The replays below use final data. Live behaviour is measured by the S8 gate (§7.4).

### 5.8 Replay numbers (`tools/chl/pp_replay.py all`, design text as written; 2026-11-13)

**Goal instants** (42 recorded games, 40 with a penalties node): the derived advantage at each goal second against
the feed's own flags.

| Goal type | Agreed |
|---|---|
| Power play | **56 / 57** |
| Short-handed | **5 / 5** |
| Even strength | **161 / 161** |

- The one miss is OHL 29182 P3 00:41: a PP goal with no penalty on record. H5 hides it (E21).
- (WP-1 fix pass, 2026-11-14) Unchanged after the S1 / S2 model changes, in the reference and in Kotlin; the audit below
  too. `ChlPowerPlayReplayTest` also asserts second-by-second parity with the reference (`tools/chl/pp_parity.py`) and a
  5 s polled replay: 4 718 polls show a bar, 22 147 none, 49 unknown; every bar agrees with the reference on side, kind
  and seconds, and every "none" poll is even in the reference.
- No goal second falls inside an H9 window.

**Duration audit (S8):** the model run **without** `TimeOn` (so it is independent of it), compared with every
flagged minor whose `TimeOnSet == 1` (OHL and WHL). **124 / 129 ends match exactly.** The 5 exceptions are
scorekeeper artifacts and are listed in the test:

| Game | Row | Artifact |
|---|---|---|
| OHL 29179 | P2 15:07 | chain end copied onto link 1 |
| OHL 29179 | P2 15:11 | chain end copied onto link 1 |
| OHL 29187 | P1 10:10 | chain end copied onto link 1 |
| OHL 29179 | P3 06:29 | `TimeOn` not updated after the 07:40 PP goal |
| OHL 29179 | P3 19:08 | game end at 20:00 |

(The reviewer counted 125 / 130 on an earlier variant. The difference is in the denominator, a row that variant
counted. With the five artifacts excepted, every modelled end agrees.)

---

## 6. Team tables and UI

### 6.1 The 61 teams (2026-27, from `teamsbyseason`; codes upper-cased; HockeyTech team id in brackets)

- **OHL (20):** BAR Barrie Colts (7) · BRAM Brampton Steelheads (18) · BFD Brantford Bulldogs (1) · ER Erie Otters
  (8) · FLNT Flint Firebirds (13) · GUE Guelph Storm (9) · KGN Kingston Frontenacs (2) · KIT Kitchener Rangers (10) ·
  LDN London Knights (14) · NIAG Niagara IceDogs (20) · NB North Bay Battalion (19) · OSH Oshawa Generals (4) · OTT
  Ottawa 67's (5) · OS Owen Sound Attack (11) · PBO Peterborough Petes (6) · SAG Saginaw Spirit (34) · SAR Sarnia
  Sting (15) · SOO Sault Ste. Marie Greyhounds (16) · SBY Sudbury Wolves (12) · WSR Windsor Spitfires (17).
- **WHL (23):** BDN Brandon Wheat Kings (201) · CGY Calgary Hitmen (202) · EDM Edmonton Oil Kings (228) · EVT
  Everett Silvertips (226) · KAM Kamloops Blazers (203) · KEL Kelowna Rockets (204) · LET Lethbridge Hurricanes (205)
  · MH Medicine Hat Tigers (206) · MJ Moose Jaw Warriors (207) · PEN Penticton Vees (277) · POR Portland Winterhawks
  (208) · PA Prince Albert Raiders (209) · PG Prince George Cougars (210) · RD Red Deer Rebels (211) · REG Regina
  Pats (212) · SAS Saskatoon Blades (213) · SEA Seattle Thunderbirds (214) · SPO Spokane Chiefs (215) · SC Swift
  Current Broncos (216) · TC Tri-City Americans (217) · VAN Vancouver Giants (223) · VIC Victoria Royals (227) · WEN
  Wenatchee Wild (222).
- **QMJHL (18; mixed case in the feed):** BAC Baie-Comeau Drakkar (16) · BLB Blainville-Boisbriand Armada (19) · CAP
  Cape Breton Eagles (3) · CHA Charlottetown Islanders (7) · CHI Chicoutimi Saguenéens (10) · DRU Drummondville
  Voltigeurs (14) · GAT Gatineau Olympiques (12) · HAL Halifax Mooseheads (5) · MON Moncton Wildcats (1) · **NFL**
  Newfoundland Regiment (2; a team code, never a league, L2) · QUE Québec Remparts (9) · RIM Rimouski Océanic (18) ·
  ROU Rouyn-Noranda Huskies (11) · SNB Saint John Sea Dogs (8) · SHA Shawinigan Cataractes (13) · SHE Sherbrooke
  Phœnix (60) · VDO Val-d'Or Foreurs (15) · VIC Victoriaville Tigres (17).

Code lengths:
- 2 characters: ER, NB, OS (OHL); MH, MJ, PA, PG, RD, SC, TC (WHL).
- 4 characters: BRAM, FLNT, NIAG.
- Everything else 3.
- No collisions within a league after upper-casing.
- The bundled fonts cover every character (é, œ, ', -; checked against the Instrument Sans and Barlow cmaps).

### 6.2 Colour tables (`ChlTeamColours`, `:core`)

- One `TABLE` per league (base, accent), with **the same `derive()`** as `NhlTeamColours`, ruling 12, and per-team C1
  exceptions.
- **Sourcing, in order**, each line commented with its source and access date:
  1. the team's or league's brand guide;
  2. `teamcolorcodes.com`;
  3. `sportsteamcolorcodes.com`;
  4. last resort, colours **sampled from the official logo PNG**, marked `SAMPLED`.
- The feed has no colour fields. The owner signs off the 61 pairs from a contact sheet (§7.4).
- **Stacked look-alike rule** (ΔE76 < 20 → home swaps, then away) applies **within a league**. Cross-league pairs are
  out of v1 (owner decision 5).
- Tests:
  - `ChlTeamColoursTest`: keys equal the recorded 2026-27 `teamsbyseason` codes (20 / 23 / 18); C1–C6; exceptions;
  - `StackedColourTableTest`: every `NhlStackedColoursTest` rule per league; the collision rule over all ordered
    pairs within each league;
  - `NhlTeamColoursTest` / `NhlStackedColoursTest` unchanged.

### 6.3 Sports tab and quick-bar sheet (owner decision 3; L7)

**Sports tab** (`SportsScreen.kt` TitleRow, `:266-299`):
- The league chips read **`NHL · NFL · CHL`**.
- With CHL selected, the **sub-switch is inline on the same row**: a 1 px `GuideUi.White12` divider (16 px air each
  side), then `OHL · WHL · QMJHL` chips (same style: 22/600, height 52, 10 gap).
  - Nothing moves vertically: the day chips stay at y 156 and the grid at 232.
  - Width check at 1920: title + 3 + 3 chips ≈ 780 px, which ends well before the right-aligned Corner / Delay chips
    (asserted at 1280 × 720 too).
- **Focus and D-pad:**
  - Left / Right walk the whole row (`NHL NFL CHL | OHL WHL QMJHL … Corner Delay`).
  - Down from any of them goes to the day chips; Up from the day chips goes to the chip of the shown league (the
    sub-chip when CHL).
  - OK on CHL writes `league = chlShown` and moves focus to that sub-chip.
  - OK on a sub-chip writes `league` and `chlLeague`; focus stays.
  - Back follows the existing ladder.
- **Selected state:** CHL stays selected while any sub-league is shown; the shown sub-chip is filled. Without CHL the
  sub-chips are absent, and the row is today's.

**Quick-bar Score bug sheet:**
- The League row reads `groupName` ("League · CHL") and cycles NHL → NFL → CHL (L5).
- With CHL shown, the next row is **"CHL league · OHL"** (`leagueName`), cycling OHL → WHL → QMJHL. Focus stays, and
  the board reloads under Loading.
- The Style row shows for any hockey league.

### 6.4 Fit rules (measured through the real painters, Standard and Large)

**Commit 0 measures the 4-letter cases first** (Q4) and records the numbers in this section's test.

- **Pill:**
  - **PP tab labels** `{CODE} POWER PLAY`, `{CODE} 5-ON-3`, `{CODE} PP GOAL`: the §4.1 rule (2 px steps to 18 px,
    then no tracking), for all 61 codes plus "NFL" (L2).
  - **Logo fallback capsule (P6):** a code of more than 3 characters draws a capsule: height 44, width
    `max(44, text + 2 × 10)`, the disc's fill, stroke and text, centred in the slot. Codes of 3 characters or fewer
    keep the disc. Ships for BRAM on day one (S10).
  - If the capsule exceeds the 64 px slot, it may overhang by up to 4 px each side, and the gap to the score is
    asserted ≥ 6 px.
- **Stacked** (rows unmirrored `[stripe][logo][code][score]`; mirrored, as merged at `2c7d5e5`,
  `[score][code][logo][stripe]`, the exact reflection):
  - **Row fit:** `rowGap(code, "19", mirrored) ≥ 0` for every code, both sizes, both orientations.
  - **Bar strings:** `{CODE} POWER PLAY 1:32`, `{CODE} 5-ON-3 1:32`, `{CODE} POWER PLAY GOAL` at 20 px with the
    italic overhang, inside 333 / 301.5, plus "NFL POWER PLAY GOAL".
  - Takeover badge fallback and tally for every within-league pair.
- **(C0 fix pass, lead ruling on O3, review S1–S2) Logo fallbacks of 4-letter codes.** The capsule text and both goal
  badges' fallback text (Pill `GoalPainter`, Stacked `StackedGoalPainter`) step down in 2 px steps from their size (18,
  24, 24) and, **for a 4-character code only**, may go below the general 18 px floor to
  `LOGO_FALLBACK_CODE4_FLOOR_PX` = 16 (`CodeFit.stepDown`, `CodeFit.fallbackFloorPx`). Never a display alias. Limits:
  the capsule overhangs the 64 px slot by ≤ `LOGO_CAPSULE_OVERHANG_MAX` (4) a side and keeps ≥
  `LOGO_CAPSULE_SCORE_GAP_MIN` (6) from the score's **ink** (widest of 0–19); a badge's text **ink** fits its slot (Pill
  64, Stacked 67.5). Measured in commit 0 (`ChlCodeFitMeasureTest`; the sizes WP-5 must draw are pinned there):

  | Code | Capsule px | Pill badge px | Stacked badge px | Notes |
  |---|---|---|---|---|
  | BRAM | **16** | **22** | **22** | 18 px: capsule 73, overhang 4.5 (fails); 16 px: capsule 67, overhang 1.5, gap 9.5 (Large 9.3). Badge ink 70 at 24, 63 at 22 |
  | FLNT | 18 | 24 | 24 | capsule 67, overhang 1.5, gap 9.5 |
  | NIAG | 18 | 24 | 24 | capsule 65, overhang 0.5, gap 10.5 |
  | NFL | 18 (disc) | 24 | 24 | 3 characters: the disc |

  Every 3-letter code keeps today's size, so NHL and NFL drawing is unchanged.
- **(WP-5 fix pass, lead ruling on review S1 / R1) The Stacked rows' fallback for a code longer than 3 characters.** The
  Pill capsule above is right for the Pill (64 px slot) and stays. The Stacked logo slot is 50.4 px (`LOGO_SLOT_W` 112 ×
  0.45) and the logo moves with the stripe, so a 67 px capsule overhung it by 8.3 px a side and sat about 1 px from the
  stripe. **Rule:** in the Stacked bug the logo fallback stays **inside the logo slot** and keeps **≥ 6 design px** from
  the stripe and from the code's ink, unmirrored and mirrored, with and without the power-play stripe, Standard and
  Large. For a code of more than 3 characters Stacked draws a **plain 44 px disc without text**, filled with that team's
  Stacked stripe colour (`StackedColourTable.pair(league, away, home)`, already contrast-safe against the row); the
  code is printed beside it by the row. 3-letter and 2-letter fallbacks and every NHL fallback are unchanged (the disc
  with its text; hash gate). Measured on the painted pixels (`ChlStackedFallbackTest`, BRAM / FLNT / NIAG, all four
  geometries, both rows, both sizes): disc 43 to 44 px wide, gap to the stripe 12.8 to 14.0, gap to the code's ink 16.8
  to 19.0 design px. The code text is never drawn inside the Stacked fallback, so the 16 px floor does not apply there.
- **Step-down extended to codes (Q4):** if a 4-letter code fails a row fit or a bar string at its size, the existing
  2 px step rule applies to the code text (rows) or the bar string, down to the 18 px floor. **Never a display
  alias.** A failure at the floor is a lead escalation, not a silent clip.
- **Cards:** the nickname at 32 SemiBold without an ellipsis (`GameCardFitTest`). The longest are "Thunderbirds",
  "Winterhawks" and "Saguenéens", all shorter than "Golden Knights". The 80 × 56 plate shows the code at 22 SemiBold
  until the logo loads.
- Accessibility: the full "City Nickname" in `contentDescription` and the live region.

### 6.5 Strings (all new keys and resources land in commit 0, S11)

| Existing (`strings_sports.xml` line) | CHL form |
|---|---|
| `sports_empty_yesterday/today/tomorrow/day` (115-119) | `sports_empty_*_league` "No %1$s games today. Pick another day above." (the NHL passes "NHL") |
| `sports_failed` (123) | `sports_failed_league` "%1$s scores did not answer. Press OK to try again; scores may be down for a few minutes." |
| new | `sports_failed_refused` "%1$s scores stopped letting this app in. CHL scores need an app update; NHL and NFL scores still work." |
| `sports_toast_not_listed` (138) | `sports_toast_not_listed_league` "The %3$s no longer lists %1$s – %2$s. Follow another game in Sports." |
| `sports_credit` (94) | §6.6 |
| `settings_sports_show_desc` (167) | "The Sports tab and the score bug. Scores and logos come from NHL.com, ESPN and HockeyTech while you use them." (Q10) |
| `settings_sports_interval` (183) | unchanged; passed `groupName` ("CHL") |
| `settings_sports_interval_slowed` (199) | `settings_sports_interval_slowed_league` "%1$s scores asked the app to check less often, so it checks every 10 s or slower for now." |
| `settings_sports_interval_league` (203) | "Showing %1$s. Pick another league on the Sports tab to change its setting." |
| new | `sports_league_ohl/_whl/_qmjhl/_chl`; `sports_picker_chl_league` "CHL league · %1$s"; `sports_time_tbd` "Time TBD" (if none exists); `sports_credit_chl` (§6.6); the matching `SettingsText` keys |

### 6.6 Credit line (P9, Q2)

- `sports_credit_chl` "Official statistics provided by %1$s · Powered by HockeyTech.com", where %1$s is:
  - "Ontario Hockey League" (OHL);
  - "Western Hockey League" (WHL);
  - **"LHJMQ"** for the QMJHL: the feed's required attribution, verbatim. "QMJHL" is used everywhere else.
- These are the feed's own `required_copyright` + `powered_by` texts, built from resources, so they never change with
  the data.

---

## 7. Testing

### 7.1 Fixtures (in the repo: `core/src/testFixtures/resources/sports/chl/`)

Every `lscluster` body had its echoed key replaced with `KEY_REDACTED` (§ the decision note says how this was
checked).

| Path | Covers |
|---|---|
| `firebase/finals-2026-11-13/{ohl,whl,lhjmq}_{runningclock,publishedclock,goalssummary,shotssummary,penalties,goals,shootouts}.json` (21 files, 596 KB) | penalties (double minors incl. reversed ids, stacked starts, `TimeOn` set / unset, the S2 row, bench, penalty shot, misconducts, the H9 case), goals (pp/sh/en/ps), shootout finals (WHL 1023277, QMJHL 32694, 32917), OT finals (OHL 29181; WHL 1023275/78/79); the §5.8 replay |
| `firebase/live-2026-11-13/*_runningclock-2056.json`, `*_publishedclock-2056.json`, `ohl_runningclock-29191-2042.json`, `whl_clock-samples-5s-2045.log` | running / stopped clock, 0:00 break, "20:00 3rd" break, pre-game "-", a **live shootout** (QMJHL 32755), clock latency |
| `lscluster/gamesbydate-{league}-2026-11-13.json` | boards: mixed-case codes, comma names, `periods` incl. OT 300, statuses 1/2/4, "1st OT" live, copyright |
| `lscluster/teamsbyseason-{league}-2026-27.json`, `lscluster/seasons-{league}.json` | 20/23/18 teams, logos, playoff flags |
| `lscluster/scorebar-lhjmq-forfeit-excerpt.json`, `lscluster/schedule-whl-293-playoff-ot-excerpt.json`, `lscluster/gc-clock-ohl-29191-2042.json` | status 5; "Final OT2/OT3"; the `gc` clock tab |
| `errors/lscluster-invalid-key.txt`, `errors/lscluster-client-denied.txt`, `errors/firebase-permission-denied.json`, `errors/gc-no-such-game.json`, `errors/firebase-missing-game-null.json` | REFUSED / NotFound / S5 mapping |
| `logos/logo-outcomes-2026-11-13.tsv` | the 61 logo outcomes (no images) |

**Still to record live** (the S8 recorder; §7.4):
- a 5-on-4 and a 5-on-3 in progress;
- the polls around a PP goal;
- a live double minor before any edit;
- a 3-on-3 OT power play;
- an OHL shootout;
- a playoff OT (spring);
- a postponed game;
- status 3.

### 7.2 Unit tests (JVM)

- `HockeyTechMapperTest`:
  - §3.1–§3.4 from fixtures: leniency, upper-casing, the code gate, names;
  - status incl. 3 / unknown before and after start (S6), and 5;
  - the clock table incl. "20:00 3rd" → period P−1, remaining 0 (S12), and End 3rd / End OT (S3);
  - shootout (a)/(b)/(c); OT numbers; the +1 not double-counted;
  - `lastGoal`; `powerPlayUnknown`.
- `ChlPowerPlayTest`:
  - E1–E33; H1–H9;
  - **the goal-instant replay** (§5.8: 56/57, 5/5, 161/161; fewer fails);
  - **the duration audit** (124/129 with the five listed artifacts; any other difference fails) (S8);
  - **parity with `tools/chl/pp_replay.py`**: the same probes give the same side, kind and seconds.
- `SportsDaysTest`: zones, 05:00 rollover, DST days (first Sunday of November, second Sunday of March).
- `HockeyTechScoreSourceTest` (MockWebServer):
  - six nodes per live poll with `If-None-Match`; 304 reuse; parallel cancel;
  - board vs live mode; the S5 `null` cases;
  - §2.6 incl. REFUSED from both hosts; caps;
  - logo validation and the JPEG swap;
  - no URL, key, body or exception message in any log line.
- `ExportRedactionTest`: key line, URL and stack-trace cases (L6).
- Settings: §1.4 rows (old ↔ new; a v0.1.7-style decoder coerces `league = "WHL"`).
- Machine:
  - `ScoreBugMachineChlTest`: timed polls, pre-game, `breakMs` unchanged, never an NFL branch, **league on views
    (B1)**, **the unknown-PP hold (B3, §4.1)**, **REFUSED back-off at 300 s (S7)**;
  - repository day back-off on REFUSED (S7).
- Placement: CHL rows equal NHL rows for every corner and size.
- Player sheet: the League row cycle, the CHL row, Style gating.
- Colour tests (§6.2); `TeamCodeIsNotALeagueTest` (L2).
- UI:
  - fit tests (§6.4) for all 61 codes and "NFL";
  - `ChlLogoTest` over the 61 recorded outcomes (S10), and that `ChlLogoCheck` is absent from NHL / NFL requests (S9);
  - screenshots: one Pill and one Stacked set per CHL league (live, PP, 5-on-3, "1st INT", "End 3rd", OT, SO,
    Final/SO, capsule, both corner sides); the Sports tab with the inline sub-switch.

### 7.3 Fake panel (`tools/fakepanel/sports_chl.py`; `--sports` enables it with the NHL and NFL)

- **Routes:**
  - `/hockeytech/feed/index.php` (`modulekit` `gamesbydate|teamsbyseason|seasons`; a wrong key → "Invalid key.", a
    mismatched client → "Client access denied.");
  - `/firebase/svf/{client}/…/{id}.json` for the seven nodes, plus the date queries, with ETag / 304;
  - `/leaguestat/{client}/logos/{id}.png|.jpg` (an RGBA PNG, an RGB PNG, a JPEG, a 404).
- **Scenario, one game per league:**
  - pre-game "-", puck drop, running clock;
  - a minor, a 5-on-3, a PP goal (goal row first, `TimeOn` one poll later);
  - a double minor; the S1 major vs minor;
  - a break at 0:00, then "20:00 2nd";
  - a tie; OT 3-on-3 with a penalty;
  - a shootout (attempts, `Shootout` false, then true with the +1), Final SO;
  - a second game ending 4–1 at 0:00 with status 2 for a minute (S3).
- **Switches:** `--chl-refuse`, `--chl-firebase-refuse`, `--chl-null-live` (S5), `--chl-drop-penalties 1/3` (B3:
  every third `penalties` request 503s).
- `test_sports_chl.py`: golden answers.

### 7.4 Release gates and device items

- `./gradlew :core:test :app:assembleDebug`, `./gradlew lintAll`; the fake-panel run on the API 30 emulator through
  every scenario.
- **Hash gate (S9):**
  - before commit 0, run the sports screenshot suites on `main` and commit the SHA-256 list of every PNG to
    `ui/src/test/resources/golden/sports-hashes.txt`;
  - after integration, every NHL, NFL and Stacked case must hash equal (a test reads the list).
  - New CHL cases are reviewed by eye and then added to the list.
- **Live power-play gate (S8):**
  - `tools/chl/record_live.py` (WP-3) records all seven nodes for a followed game every 5 s, to a fixture folder;
  - record at least **one full live game per league** before release;
  - `pp_replay.py live <folder>` replays the derivation poll by poll and compares it with the model on that game's
    final data. It reports:

| Metric | Definition |
|---|---|
| **wrong-bar seconds** | live seconds where a bar was shown and its side or kind differs from the final-data model, or a bar was shown where the final model has none |
| **hidden seconds** | Unknown |
| **missing seconds** | none shown, while the final model has a PP |

  - **Threshold (proposed for the lead): wrong-bar seconds ≤ 5 % of the final model's power-play seconds per league,
    and no single wrong stretch longer than 30 s.** Hidden and missing seconds are reported, not gated: they are the
    accepted misses 1–2.
- **Owner sign-off:** the 61 colour pairs (contact sheet: Pill goal and PP tab, Stacked rows), `SAMPLED` lines named;
  the NIAG and CAP recovered logos.
- **Only on a live game:**
  - the S8 recordings;
  - derived PP against the league's own game centre (misses classified under §5.7);
  - clock latency against a broadcast; Today overlay freshness;
  - a live shootout per league; an OT 3-on-3 PP;
  - logos on the API 25 Fire TV.

---

## 8. Work packages (file ownership disjoint, S11)

**Commit 0 (lead, Opus; ~700 lines + fixtures already in the repo).** Owns:
- `SportsContracts.kt`, `SportsPrefs.kt`, `SportsConstants.kt` (new constants), `PollMeter.kt` (ids only),
  `TeamColourTable.kt`;
- new `SportsDays.kt` (`:core`, ~30 lines, S11);
- `ScoreBugMachine.kt` rows 13a and 13b (B1 league on views; B3 unknown-PP hold, with tests);
- `PlayerContracts.kt` (rows 29–30);
- `SettingsTexts.kt` and `ResourceSettingsTexts.kt`; `strings_sports.xml` (every new key and string, §6.5);
- `ScoreBug.kt` rows 42–44; `StackedBug.kt` rows 45–46;
- stubs: `HockeyTechMapper`, `ChlPowerPlay`, `ChlTeamColours` (fallback only), `StackedColourTable` (NHL table +
  per-league stubs), `HockeyTechScoreSource`;
- `FakeScoreRepository` (five leagues);
- the hash list (§7.4), and the §6.4 measurements.

| WP | Owns (files) | Model | Depends on | Size |
|---|---|---|---|---|
| **WP-1** mapper + power play | `core/.../sports/HockeyTechMapper.kt`, `ChlPowerPlay.kt`, their tests (`HockeyTechMapperTest`, `ChlPowerPlayTest` with the replay, audit and parity cases) | Opus | C0 | ~900 + ~1 000 tests |
| **WP-2** core sweep | `ScoreBugMachine.kt` rows 11, 12, 14, 15 (timed, REFUSED back-off); `PollMeter.kt` rows 8–9; `ScoreBugPlacement.kt`; `WpaPages.kt` rows 21–27; `PlayerStateMachineImpl.kt` rows 31–34; their tests (`ScoreBugMachineChlTest` for timing / back-off, placement, sheet, settings) | Sonnet (under this spec) | C0 | ~350 + ~450 tests |
| **WP-3** data source | new `data/.../sports/HockeyTechScoreSource.kt`, `HockeyTechEndpoints.kt`; `SportsModule.kt`; `ScoreRepositoryImpl.kt` rows 35–37, 39; `ExportRedactionTest` cases; `HockeyTechScoreSourceTest`; `tools/fakepanel/sports_chl.py`, `test_sports_chl.py`, `fakepanel.py` (flag wiring), `tools/fakepanel/README.md`; `tools/chl/record_live.py` and the `live` command of `pp_replay.py` | Opus | C0 | ~800 Kotlin + ~700 Python |
| **WP-4** team tables | `core/.../sports/ChlTeamColours.kt`, `StackedColourTable.kt` (CHL tables), `ChlTeamColoursTest`, `StackedColourTableTest`, the colour research note | Sonnet (research and table); Opus review of the generalisation | C0 | ~350 + ~300 tests |
| **WP-5** UI | `SportsViewModel.kt`, `SportsScreen.kt`, `SportsText.kt`, `PlayerText.kt`, `TeamLogo.kt` (+ new `ChlLogoCheck.kt`), `GameCard.kt` (if the fit needs it), `PlayerEffectsImpl.kt` (row 49), `PlayerViewModel.kt` (row 50, incl. `ScoreFacts.chlLeague`), **`GoalPainter.kt` and `StackedGoalPainter.kt` (the 4-letter badge fallback text only, §6.4 C0 fix pass)**; UI tests (fit, `ChlLogoTest`, `TeamCodeIsNotALeagueTest`, Sports tab, screenshots) | Sonnet | C0; WP-4 tables for final screenshots (stubs suffice to build) | ~750 + screenshots |
| **Integration + review** | merge; fake-panel emulator pass; hash gate; code reviews (core, UI); S8 recordings; owner sign-offs | Opus | all | — |

- No file is owned by two packages. `ScoreBugMachine.kt` is split by rows: C0 lands 13a and 13b first; WP-2 edits only
  the timing / back-off functions (`fetched`'s Failed branch, `pollAt`, `fast` → `timed`/`nfl`), and rebases on C0.
- `strings_sports.xml` and the `SettingsText` files are frozen after C0. A WP that needs a string asks the lead.
- (C0 fix pass) Commit 0 also owns `HockeyTechContracts.kt` (the mapper ↔ source types), `CodeFit.kt` and the frozen
  stub signatures of `HockeyTechMapper` (incl. `overlay(ChlBoard…): ChlBoard` and `wantsShootouts(prev, facts)`),
  `ChlPowerPlay`, `ChlTeamColours`, `StackedColourTable` and `HockeyTechScoreSource`. `GameSnapshot.startTbd` (WP-1 sets,
  WP-5 reads) and `ScoreFacts.chlLeague` (WP-5 fills, WP-2 reads) are frozen in `SportsContracts.kt`.
- (C0 fix pass, deviation 6, review L9) Until WP-2 lands row 19 (`ScoreBugPlacement.kt:163/173`), `StackedBug` sizes a
  CHL Stacked bug with the Pill footprint (unreachable: no CHL source is bound). **WP-5 must not approve CHL Stacked
  screenshots taken before WP-2 is merged.**
- (C0 fix pass, review S5) WP-4 and WP-5 touch `:ui` drawing: before handing off, run the hash gate one-liner in the
  header of `ui/src/test/resources/golden/sports-hashes.txt` (old PNGs deleted first).

---

## 9. Existing docs to amend at implementation time (not edited by this design)

- `docs/00-master-plan.md` M6 row: a "CHL leagues" entry.
- `docs/decisions/2026-11-05-nhl-score-bug.md`:
  - link to the CHL decision;
  - ruling 33 amended for the CHL (P4, S3);
  - ruling 29 amended (P1);
  - A-S1 scoped to the NHL (P2).
- `docs/decisions/2026-11-10-nhl-score-bug-stacked.md`: "NHL only" → "hockey only".
- `docs/design/m6-score-bug.md`: §1.1 (five leagues, groups), §2.2 (A-S1 scope), §3 (CHL logo rules, the capsule).
- `docs/design/m6-nfl.md`: §2 (`League`) and §7 (three groups).
- `docs/design/m6-power-play.md`: §1.2 and §1.4 (a derived source; `powerPlayUnknown` and the 30 s hold).
- `docs/design/m6-goal-animation.md`: §3.3 (per-league tables).
- `docs/design/m6-score-bug-stacked-theme.md`: §1.1, §5, §10 (per-league tables; fits for 2- and 4-letter codes).
- `docs/design/m6-sports-update-interval.md`: §5 (CHL choices, shared setting, followed meter, ETag bytes).
- `tools/fakepanel/README.md`: CHL routes and switches.

---

## 10. Settled answers and what is still open

Settled by the lead (2026-11-13; the decision note has the wording):
- **Q1** SCHEDULED only before the start; after it, the clock inference (S6). Postponed: the 8 h expiry.
- **Q2** "LHJMQ" in the credit, verbatim.
- **Q3** no P13 in v1.
- **Q4** measure in commit 0; extend the step-down rule; never an alias.
- **Q5** accepted.
- **Q6** later (a long-lived connection on the Stick needs its own lifecycle review).
- **Q7** 05:00.
- **Q8** accepted with S5.
- **Q9** accepted (premise reworded, L1).
- **Q10** name HockeyTech.

Still open:
- **O1.** The S8 gate threshold (proposed: ≤ 5 % wrong-bar seconds per league, no wrong stretch > 30 s). The lead
  sets it.
- **O2.** Status 3 and postponed samples. Recorded when seen; the mapping is revisited then.
- **O3. Settled (C0 fix pass).** BRAM's capsule failed at 18 px (overhang 4.5). The lead's ruling: a 4-letter code's
  logo-fallback text may step down to 16 px; BRAM then fits (capsule 16 px, badges 22 px). See §6.4.

---

## Appendix A: new constants (`SportsConstants`)

| Name | Value | Why |
|---|---|---|
| `INTERVAL_CHOICES_CHL_S` | `[5, 10, 15, 20, 30]` | §2.5, P1 |
| `INTERVAL_DEFAULT_CHL_S` | 5 | Auto's start |
| `EST_WIRE_BYTES_CHL` | 2 000 | §2.5, S4 |
| `CHL_NODE_MAX_BYTES` | 131 072 | §2.7 |
| `CHL_DAY_ROLLOVER_H` | 5 | §2.3 |
| `CHL_REG_PERIOD_S` / `CHL_OT_REG_S` / `CHL_OT_PLAYOFF_S` | 1200 / 300 / 1200 | §3.3 |
| `CHL_PP_FUTURE_TOLERANCE_S` | 5 | H3 |
| `CHL_PP_CONSISTENCY_WINDOW_S` | 120 | H5 |
| `PP_UNKNOWN_HOLD_MS` | 30 000 | B3, §4.1 |
| `LOGO_CAPSULE_PAD` | 10 | §6.4 |

## Appendix B: field names (real, from the 2026-11-13 samples)

- **`runningclock/games/{id}`:**
  `{"Clock":{"Minutes":"01","Running":false,"Seconds":"15","period":2},"DatePlayed":"2026-11-13","status_id":2}`.
  - Pre-game: `"Minutes":"-","Seconds":"-","period":"1"`, `status_id` 1.
  - `ads.endOfAdTime` is ignored.
- **`publishedclock/1/games/{id}`:** `ClockMinutes`, `ClockSeconds`, `DatePlayed`, `Final`, `PeriodId`,
  `PeriodLongName` ("2nd", "OT", "1st OT"), `PeriodShortName` ("2", "OT", "OT1"), `ProgressString` (not read),
  `Started`, `StatusId`, `StatusName`.
- **`goalssummary/1/games/{id}`:** `HomeGoalTotal`, `VisitorGoalTotal`, `*GoalsByPeriod` (leading null),
  `*PowerPlayGoals`, `*PowerPlayAttempts`, `Shootout`, `PeriodsInfo`, `DatePlayed`.
- **`shotssummary/1/games/{id}`:** `HomeShotTotal`, `VisitorShotTotal`, `*ShotsByPeriod`, `PeriodsInfo`, `DatePlayed`.
- **`penalties/1/games/{id}`:** `{"DatePlayed":"","GamePenalties":{"<LSEventId>":{…}}}`. Each entry has `Bench`,
  `Home`, `LSEventId`, `Minutes`, `Offence`, `OffenceDescription`, `PenaltyShot`, `Period`, `PowerPlay`, `Time`,
  `TimeOn`, `TimeOnSet`, `TimeStart`, `TimeStartSet`, `PenalizedPlayerId` (0 for bench), `ServedPlayerId`.
- **`goals/1/games/{id}`:** `GameGoals{"<LSEventId>":{…}}`. Each entry has `IsHome`, `Period`, `Time`, `PowerPlay`,
  `ShortHanded`, `EmptyNet`, `PenaltyShot`, `LSEventId`, and `Scorer…`.
- **`shootouts/1/games/{id}`:** `ShootoutAttempts{"<id>":{…}}`. Each attempt has `Goal`, `IsHome`, `LSEventId`,
  `Shooter`, `Goalie`.
- **`gamesbydate` entry:** `game_id`, `season_id`, `date_played`, `schedule_time`, `timezone`, `time_tbd`, `status`,
  `started`, `final`, `period`, `game_clock`, `game_status`, `intermission` (always "0" seen), `shootout`, `playoff`,
  `home_team` / `visiting_team` (ids), `home_team_code/_city/_nickname/_name`, `home_goal_count`, `home_shots`,
  `visiting_*`, `periods{n}{length}`, `goal_summary[]`.
- **`teamsbyseason` entry:** `id`, `name`, `city`, `code`, `nickname`, `division_*`, `team_logo_url`.
- **`SiteKit.Copyright`:** `required_copyright`, `required_link`, `powered_by`, `powered_by_url`.

## Appendix C: what was verified (2026-11-13, generic client, nothing about the user or project sent)

- **Endpoints and transport:**
  - every §2.1 endpoint, for all three leagues;
  - `lscluster` gzips; Firebase does not;
  - Firebase ETag / 304; root 401; subtrees open;
  - the `lscluster` refusals and the `gc` 404;
  - `gamesbydate&fetch_date` gives the asked date.
- **Clocks:**
  - the running clock tracked real time (5 s samples, WHL 1023288: 0:57 → 0:13 running, then 0:08 stopped);
  - `publishedclock` held 3:00 meanwhile.
- **Breaks:** "00:00 2nd" and "20:00 3rd", both stopped; `intermission` always "0".
- **Shootouts:**
  - live: QMJHL 32755, `Shootout` false, attempts present;
  - "Final SO" carries the +1 (QMJHL 32917: 5–4, by-period 4–4).
- **OT and status codes:** OT 300 (four OT winners; `periods["4"].length`); playoff "Final OT2/OT3" = periods 5/6;
  status 5 "Home Forfeit".
- **Power-play derivation:** the §5.8 replay and audit, run on the repo fixtures with `tools/chl/pp_replay.py`.
  Reviewer probes reproduced: E29 to E32.
- **Teams, logos and fonts:** team lists 20 / 23 / 18; the 61 logo outcomes (`tools/chl/logo_audit.py`, reviewer
  downloads); fonts cover every name.
- **Coverage elsewhere:** ESPN has no CHL; TheSportsDB's free key is disabled.

Not verified:
- status 3; postponed games;
- how early Firebase lists a game;
- a live PP, 5-on-3 or PP-goal sequence (S8 will record them);
- an OHL live shootout;
- the HockeyTech and CHL terms (the owner accepted the risk);
- Firebase behaviour under load.
