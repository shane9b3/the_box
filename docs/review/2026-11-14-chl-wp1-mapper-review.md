# CHL WP-1 review: `HockeyTechMapper` + `ChlPowerPlay` (commit `4a3c4f8` vs `ae2a3ea`)

Reviewer: independent (Opus). Scope: `core/.../sports/HockeyTechMapper.kt`, `ChlPowerPlay.kt`, their tests, the parity dump and
`tools/chl/pp_parity.py`. Read against design `docs/design/m6-chl-leagues.md` §3, §5, §7, decisions note and the 2026-11-13
reviews.

## Verdict: **merge after fixes** (no blockers)

The code is a faithful, well-tested implementation of the approved §3 / §5 text. It never threw on anything I fed it, and
it never showed a bar that the reference model would not show. Everything below is a gap in the **model rules**, which
the parity test cannot see because it proves Kotlin == Python only. All of them are hockey cases the 40 recorded
finals do not contain. S1 and S2 change the reference model, so the lead must rule on them first. I re-ran
`pp_replay.py all` with S1's rule and the output is byte-identical. S2 cannot change any recorded game, because none of
them has a major chained with a minor. The rest are small.

## Verified by running vs read only

| Item | How |
|---|---|
| `:core:test --tests '*Chl*' --tests '*HockeyTech*'` | **ran**: 6 suites, 100 tests (88 in the five WP-1 / contract suites + 12 `ScoreBugMachineChlViewTest`), 0 failures. Parity: 40 games, 152 797 s, 24 011 advantage s; polled replay 4 718 shown polls, 0 seconds differ |
| Parity dump is regenerable | **ran** `python3 -I tools/chl/pp_parity.py`: byte-identical to `core/src/test/resources/sports/chl/pp-parity-2026-11-13.tsv` |
| `pp_replay.py all` | **ran**: PP 56/57 · SH 5/5 · EV 161/161; ends 124/129 (matches §5.8) |
| Adversarial hockey cases on the **Kotlin** `derive` | **ran**: a Java harness in the scratchpad (outside the repo), compiled against `core/build/classes/kotlin/main` (cases and output quoted below) |
| The same cases on the Python reference, and a modified reference for S1 | **ran** (scratchpad copies; repo untouched) |
| E1–E33 against the design table | **read**: every test asserts the design's exact side / kind / seconds. E30 at (2,4:00) leaves out the 16:50 goal, which is still in the future at that time. That is correct |
| Mapper leniency, status / phase table, overlay, purity, throw paths | **read** (+ the fuzz test ran). No clock read, no I/O, no logging; no `!!`, casts are all `as?`, regexes are linear |

## Blockers

None.

## Should-fix

**S1. A later chain link waits for its own recorded `TimeStart`, so a pre-filled link 2 gives a gap and then a phantom
tail after a PP goal.** `ChlPowerPlay.kt:360` (`if (r.s > t …) continue`) and `:395` (`it.s <= end`), mirroring
`pp_replay.py:158`.
- Failing input: a double minor written in the OHL's two-row form, `A2@5:00` with `TimeStart 05:00` (player 7) and
  `A2@5:00` with `TimeStart 07:00` (player 7, pre-filled), plus `Hg@6:30 pp`. The scorekeeper has not yet edited link 2 to
  06:30 (§7.1 lists "a live double minor before any edit" as still to record, so this form is unverified, not
  excluded).
- Kotlin output at elapsed 6:40 is **None**, while the real game is a power play with 110 s left. The machine announces
  "Power play over." At 7:10 it shows HOME 110 (the real value is 80). At 8:40 it shows **HOME 20** (the real game is at
  even strength, so this is a phantom bar for 30 s).
- Fix: use `s` only to order a chain's links, and start a link that is not the first at its predecessor's end:
  - `step` (`:360`): `if ((r.firstLink && r.s > t) || …)`;
  - `seatEnd` (`:395`): `it.key == key && (it.s <= end || !it.firstLink)`;
  - the same in `pp_replay.py:155-158 / :189`.
- I ran the modified reference: `pp_replay.py all` is byte-identical, because no recorded chain has a gap. H3 already
  skips later links, so the rules stay consistent. Record the change in §5.3 step 3, whose "the next link … starts at t"
  already says so.

**S2. A major and a minor to one player at one stoppage are served in `LSEventId` order, not major first.**
`ChlPowerPlay.kt:223` (`compareBy s, id, link`), `pp_replay.py:84`.
- Failing input: away player 9 gets `A2@2:00` (id 1) and `A5@2:00` (id 2), both flagged, then `Hg@3:00 pp`.
- Kotlin serves the minor first. The goal at 3:00 releases it and the major runs 3:00–8:00. Output: 5:00 → HOME 180,
  7:30 → HOME 30, 8:30 → None.
- Under the NHL / Hockey Canada rule (check it against the CHL rule book) the major is served first: 2:00–7:00, not
  released, then the minor 7:00–9:00. The true values are 240, 90, and HOME 30 at 8:30. So the bar shows wrong seconds
  for 5 minutes, and "Power play over." is said a minute early.
- Fix: break a tie on `s` by putting majors first: `compareBy({ it.s }, { !it.minor }…)`, then `id`, `link`. Do the same
  in the reference. No recorded game has a chain that contains a 5, so the replay and the parity are unchanged.

**S3. The overlay can demote a live board game to SCHEDULED.** `HockeyTechMapper.kt:287`.
- `overlayGame` works out `started` from the scores and from statuses {2,4,5} only. It ignores the board's own
  `started` and the board snapshot's phase.
- Failing input: a board entry with `status "3"`, `started "1"`, 0–0 and P1 10:00 maps to LIVE. A Today overlay with
  `{"status_id":3,"Clock":{…running…}}` and 0–0 then maps it to **SCHEDULED** with null scores.
- Status 3 is unverified (§7.1), so this is low impact. The fix is cheap: `started = … || g.phase != GamePhase.SCHEDULED`.

## Low

- **L1. Array-form rows without `LSEventId` all get id 0, so bench minors chain.** `HockeyTechMapper.kt:486` (and `:519`).
  Two flagged bench minors at one stoppage, in Firebase's array form and without `LSEventId`, both become
  `SoloKey(0)`. The Kotlin output is **HOME ONE_MAN 210** at 5:30, against the correct TWO_MAN 90. Fix: fall back to the
  array index (or `-index-1`), not 0. The object form is unaffected because the key is used.
- **L2. `Time` past its period's length is accepted.** `HockeyTechMapper.kt:622` / `buildRows`. A flagged
  `A2@25:00` in P1 is placed at P2 5:00, and P2 6:30 shows HOME 30. Make a counted row with `timeS` or `startS` greater
  than `L(period)` an H2 row.
- **L3. Hostile-size cost.** `Model.seatEnd` is O(P²) per serving row per event, plus `rows.all` per candidate.
  Measured on the desktop JVM (cold): one player with 400 chained minors and 400 goals takes 135 ms; 800 and 800 take
  782 ms. A Fire TV Stick is several times slower, every 5 s. A 128 KB node holds about 180 real rows (about 700 B each)
  but about 1 000 minimal ones. Real games (≤ 40 rows) cost under 10 ms. Fix: with more than about 80 counted rows or
  80 goals, return Unknown before the model runs.
- **L4. Coincident minors flagged 1/1 plus a third minor and a PP goal: the goal releases the coincident minor.**
  `ChlPowerPlay.kt:341`.
  - Input: `A2@4:00`, `H2@4:00` (both flagged), `A2@4:30`, `Hg@5:00 pp`.
  - Kotlin shows **HOME 20 at 6:10**. The real game is at even strength from 6:00, because coincident minors are
    never released.
  - This depends on the flags (§5.3 says coincident pairs are flagged 0/0, and accepted miss 6 covers it). Record the
    case in §5.7 rather than adding a rule.
- **L5. The polled replay only checks Shown polls.** `ChlPowerPlayReplayTest.kt:234`. A regression that returns
  None or Unknown far more often passes as long as more than 1 500 polls show a bar (today 4 718). Assert the exact
  Shown / None / Unknown poll counts. Also assert that every None poll has an even reference: None is the case the
  machine announces.
- **L6. Dead clause.** `ChlPowerPlay.kt:133`: `e − start > 60 × minutes` can never be true, because `e ≤ start + dur` and
  `TimeOn` only caps. Keep it as a guard, but say so in the KDoc.
- **L7. `seasons()` maps a season with a missing `playoff` field to `false` (regular season), not unknown.**
  `HockeyTechMapper.kt:91`. Harmless while the board entry's own `playoff` is the fallback. Mapping it to null would
  match departure 4.

## Notes

- Purity and allocation: there are no clock reads, no I/O and no logging. Each poll allocates one model with one
  snapshot per event (dozens for a real game). That is fine at a 5 s poll.
- Throw paths: none found.
  - `intOf` guards NaN, infinity and 1e300.
  - A huge period cannot overflow: `maxOf(…) + 1` → `coerceIn(1, 12)`, and the H1 loop exits at the first null length.
  - `GameClockMap.g` bounds its index.
  - All loops terminate: each event step moves `t` strictly forward, and each seat-end or start iteration consumes a row.
- `intOf` also accepts `"2f"`, `"1d"` and hex floats through `toDoubleOrNull`. This is harmless leniency.
- Goal rows carry `PlusPlayers` / `MinusPlayers`, which give the on-ice strength at each goal. They are a possible future
  cross-check alongside H5, for v2.
- H3 and stacked starts: if a scorekeeper pre-fills a stacked third penalty's `TimeStart` (its future seat time), H3
  hides the whole 5-on-3. That is safe, but the S8 live gate should look for it.
- `tools/chl/pp_parity.py` is outside WP-1's listed files. It is acceptable: it is a 47-line read-only wrapper over the
  unchanged reference, and its docstring gives the right output path.

### For the lead: WP-3 contract traps

1. **Pass per-game objects** (`penalties/{gid}` = `{DatePlayed, GamePenalties}`, and likewise `goals`, `runningclock`,
   `shootouts`). If the league-level node is passed, `GamePenalties` is absent and the result is silently **no rows**,
   so `None` rather than Unknown (H7 only fires on a null node). `overlay` is the opposite: it takes the league-level
   date-query object keyed by game id.
2. A 304 means passing the cached body. A failed request means `penaltiesFailed` / `goalsFailed = true`. A `null` node
   means null. Mixing these up turns H8 into H6 / H7 or into None.
3. `followed()` always returns `startTbd = false` (departure 11). For a followed TBD game the source should copy the board
   snapshot's `startTbd` (or the lead should add it to `ChlGameFacts`). Otherwise the pre-game pill shows midnight.
4. `facts` must be refreshed from the latest board: OT lengths appear only once OT starts, and the playoff flag comes from
   `seasons`. With stale facts and `playoff == null`, a live OT or shootout reads as H1 Unknown or "OT INT".
5. `wantsShootouts(prev, …)` needs the previous **mapped** snapshot of the same follow. Reset it on a follow change.
6. `followed()` returns null (BAD_BODY) for a game that has started but has no `goalssummary`. A status-5 forfeit may
   have none, so consider the S5 board fallback there.
7. `teams()` needs the validated prefix with its trailing slash. `board()` needs the ISO day it queried.

## The 11 departures

| # | Departure | Ruling | Reason |
|---|---|---|---|
| 1 | H3 only on a chain's first link | **Accept** | The edited two-row form (E30, E32) gives link 2 a future `TimeStart` by design. Applying H3 to it would hide every double minor's first half. Keep it, and with S1 the later link's `s` stops mattering at all |
| 2 | Period ≥ 4 with unequal scores → None at any clock | **Accept** | CHL OT is sudden death in the regular season and the playoffs, so an unequal score in OT means the game is over. Covered by `followedBreaksAndEnds` |
| 3 | Started = `Started` ∨ score > 0 ∨ any source status ∈ {2,4,5}; status 1 always SCHEDULED | **Accept** | The design's "status ≥ 2" would make its own "3, not Started" row unreachable, and {2,4,5} resolves that. Status 1 → SCHEDULED is the design table. Risk: `publishedclock` wins over `runningclock`, so a lagging `StatusId 1` hides a live game. The recorded samples always agree; watch for it in S8 |
| 4 | Unknown playoff flag → regular season iff the board lists a 300 s OT | **Accept** | A conservative inference from data, not text. Otherwise OT → H1 (hidden) |
| 5 | Board period lengths when listed, else 1200 / 300 / 1200 | **Accept** | Strictly more information (bounded 1..3600), and identical on every fixture |
| 6 | Extra Unknown cases (no feed period, no clock, a needed row without side or period) | **Accept** | Each one hides; none can show |
| 7 | Goal total − 1 when `Shootout`; `lastGoal` null on a count mismatch | **Accept** | The −1 is unreachable in practice (`Shootout` is true only at the final, which is None anyway) but harmless. `lastGoal` follows §3.3 |
| 8 | Strict `mm:ss` | **Accept** | Unparsable values go to H2. Add the bound check from L2 |
| 9 | Board shapes; board clocks not running; TBD → `startTbd`, midnight in the league zone | **Accept** | The board clock is a 4-minute cached string with no running flag. "Midnight local" is ambiguous and `dayKey` comes from `date_played` anyway |
| 10 | Overlay date check per entry (a missing `DatePlayed` is accepted); a missing score falls back to the board's | **Accept** | The query is already filtered by date. See S3 for the `started` gap in the same function |
| 11 | `followed()` always sets `startTbd = false` | **Accept with a note** | `ChlGameFacts` is frozen and carries no TBD flag. WP-3 must carry it over (trap 3) |

## Fix pass (2026-11-14, on `4a3c4f8`)

Lead rulings applied. Public signatures (`HockeyTechMapper`, `ChlPowerPlay.derive`, the §5.1 types,
`HockeyTechContracts.kt`) are unchanged.

| Item | Disposition |
|---|---|
| S1 later chain link waits for its own `TimeStart` | **Fixed in both models.** A link that is not its chain's first starts at its predecessor's end; its `s` only orders the links. Kotlin `Model.step` and `seatEnd`; `pp_replay.py` start loop and `seat_end` (a `first` flag per row). `pp_replay.py all` and the parity dump are byte-identical. Your case is worked example **E34** (`ChlPowerPlayTest.e34_aPreFilledSecondLinkFollowsTheGoal`): 6:40 → HOME 110, 7:10 → 80, 8:40 → None |
| S2 major and minor at one start in id order | **Fixed in both models.** Row order is `(s, major first, id, link)` (`ChlPowerPlay.ORDER`; `pp_replay.order`). Your case is **E35**: 240 / 90 / 30 / None. Design §5.3 notes that no recorded game exercises it |
| S3 overlay demotes a started game | **Fixed:** `started ||= g.phase != SCHEDULED` (`HockeyTechMapperTest.overlayKeepsAStartedGameStarted`) |
| L1 array-form rows without `LSEventId` get id 0 | **Fixed:** the array index is the key; else a negative ordinal (`arrayFormRowsWithoutIdsStayDistinct`: two bench minors → TWO_MAN 90) |
| L2 `Time` past the period | **Fixed:** a counted row whose `Time` or `TimeStart` exceeds its period's length is H2 (`h2_aTimePastThePeriodsEnd`) |
| L3 hostile-size cost | **Fixed:** more than 80 counted rows (links) or 80 goals → Unknown before the model runs (`aHostileSizeNodeIsUnknown`) |
| L4 coincident 1 / 1 minors | **Documented** in design §5.7 (accepted miss 6); no rule |
| L5 polled replay checks Shown only | **Fixed:** exact counts asserted (shown 4 718, none 22 147, unknown 49) and every None poll must be even in the reference |
| L6 dead H4 clause | **Kept as a guard;** commented in code and §5.6 |
| L7 `seasons()` missing `playoff` → false | **Fixed:** the season is absent (unknown), so the board entry's own flag decides (`aSeasonWithoutAPlayoffFlagIsUnknown`) |
| Departures 1–11 and watch item #3 | **Written into the design** (§3.6 table; §5.3, §5.6 marked "WP-1 fix pass" / "WP-1 departure") |

Numbers after the fix pass (unchanged): goal instants PP 56/57 · SH 5/5 · EV 161/161 (the one miss OHL 29182 P3 00:41);
duration audit 124/129 with the five listed artifacts; parity 40 games, 152 797 s, 24 011 advantage s, all equal.
WP-3 contract traps 1–7 are for the lead / WP-3; trap 3 is departure 11 in design §3.6.
