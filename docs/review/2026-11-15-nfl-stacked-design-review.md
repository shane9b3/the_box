# Review: NFL "Stacked" score bug design (`docs/design/m6-nfl-stacked.md`, proposed 2026-11-15)

Reviewer: adversarial design review (Opus), 2026-11-15, an NFL Sunday (16:18–16:27 ET, during the late-afternoon
games). Inputs: the proposal; `design/nfl-stacked/StackedNFL.dc.html` (markup, CSS and the `Component` logic); the
proposer's rendered frames; the NHL baselines (`docs/decisions/2026-11-05-nhl-score-bug.md`,
`docs/decisions/2026-11-10-nhl-score-bug-stacked.md`, `docs/design/m6-score-bug-stacked-theme.md` §4.2, §4.3, §5, §8,
`docs/design/m6-nfl.md`); the code at `412446e` (`EspnMapper`, `ScoreBugMachine`, `ScoreBugPlacement`,
`StackedColourTable`, `NhlStackedColours`, `NflTeamColours`, `ScoreBug.kt`); the NFL fixtures under
`core/src/test/resources/sports/nfl/`; and live ESPN samples I took today (below).

Live samples are in `/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/nfl-stacked/espn/`
(called "espn/" below). That's 14 polls of `https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard`
about 30 s apart (`sb-HHMMSS.json`), one of the per-event endpoint the production follow reads first
(`scoreboard/401873061`, `event-401873061-162614.json`), and two of
`…/summary?event={id}` (`summary-401873061-161926.json` live, `summary-final-401873058-161955.json` final). Two games
were live: SEA @ LV (401873062) and LAR @ ARI (401873061). The others were final or scheduled. The key moments are
copied under descriptive names: `live-penalty-type8-LAR-162405.json`, `live-team-timeout-type21-LAR-162436.json`,
`live-official-timeout-kickoff-redzone-true-161844.json`, `live-kickoff-return-possession-null-162203.json`.

## Verdict

**Approve with required changes.** The proposal follows the mock and the NHL baseline closely. The conversions check
out, and the colour table matches the real Kotlin derivation exactly. The hash-gate plan is sound. But the state model
rests on three feed assumptions that today's samples settle, and two of them come out differently from what the
proposal built. The design also departs from the Stacked baseline in two places: the stale/delayed look in play, and
the footprint. Both are changes, not omissions. All of these are fixable in the design text before commit 0.

---

## Required changes

**RC1. Timeout mark: trigger on `lastPlay` type 21 and confirm with the count. Do not rely on the count alone or on
the clock.**
*Why.* The live sample `live-team-timeout-type21-LAR-162436.json` shows a team timeout on the **scoreboard**. In the
**same poll**, `lastPlay` became type 21 "Timeout" ("Timeout #1 by LA at 06:56.") and `awayTimeouts` went 3 → 2. The
21 stayed the `lastPlay` for two polls (≥ 60 s: `sb-162436`, `sb-162506`), the clock stayed 6:56, and the next snap
replaced it (`sb-162536`, 6:55). So the proposal's "21 unverified" premise is wrong. Its end rule, "the (period, clock)
differs", is also fragile: the same game shows ESPN's clock lagging and catching up independently of plays (10:41
held across a 7-yard pass at `sb-162002`, then jumped). A clock catch-up during a timeout would end the bar after one
poll. Also, `lastPlay.team.id` was 14 (LAR), but LAR was also the possession team, and type 74 official timeouts carry
the possession team too (`sb-161844`). So `team.id` is **not proven to be the caller**. The text names the caller with
ESPN's legacy codes ("LA", "ARZ"), which differ from the scoreboard abbreviations, so the text must never be parsed.
*Proposed text for §3.3 "Timeout mark":*
> On a newly shown fresh entry (SEEN demand) whose `lastPlay` is type **21** with an id newer than any seen in the
> follow, the caller is the side whose count is **below its low-water mark for the current half** (RC2). If neither
> count has dropped yet, the check repeats on each later entry while that 21 is still the `lastPlay`. If both dropped,
> or the follow has no history, no mark is set; the pips just update. The mark is
> `NflTimeoutMark(side, count)` and **lasts while that type-21 play is the shown `lastPlay`**. It ends on a new
> `lastPlay` id, a flagged score, a phase other than LIVE, stale or waiting, or `NFL_TIMEOUT_MARK_MAX_MS` (180 000).
> **Fallback** (a 21 that fell inside a poll gap): a count that drops by exactly 1 below the low-water mark with no 21
> seen gives a mark for `NFL_TIMEOUT_FALLBACK_MS` **8 000**, then ends. Types 74 (Official Timeout), 75 (Two-minute
> warning), 2, 65 and 66 never set a mark. `lastPlay.text` and `lastPlay.team` are never used to name the caller.

Update §3.1's evidence column, §11 risk 1, §12 item 1, and the §9.1 test list to match (add the trimmed
`live-team-timeout` bodies as fixtures).

**RC2. Use a per-half low-water mark, not "fell versus the previous entry".**
*Why.* "Exactly one side fell by exactly 1 compared with the previous shown entry" fires again whenever the feed flaps
(2 → 3 → 2: a correction, a challenge charged then restored). It also misfires across a missed half boundary if the
period grouping is ever wrong. *Text:* "Per half group (1–2, 3–4, 5+), keep each side's lowest count seen. A mark is
possible only when a count goes **below** that low-water mark. Increases never mark and never raise the low-water
mark. A new half group resets it to the first count seen." Test: 3, 2, 3, 2 gives one mark; the 1 → 3 reset at Q3
gives none.

**RC3. Penalty detection: fix the rule and the claim about declined fouls.**
*Why.* The proven forms are:
- type 8 for no-play fouls (`live-penalty-type8-LAR-162405.json`: "…PENALTY on LA, Illegal Formation, 5 yards…");
- the play's own type with "PENALTY on" in capitals for accepted fouls on plays that stand. On the scoreboard
  `lastPlay`: Kickoff 53 and Rush 5 in the repo log `live-sample-2026-11-08.log` (14:36:07, 14:40:27). In the summary:
  Punt 52 and Pass Reception 24 in `summary-final-401873058`;
- **declined fouls are written "Penalty on KC-…, Offensive Holding, declined."** in mixed case, on a type 3 play
  (`summary-final-401873058`, twice).

So the proposal's case-sensitive `"PENALTY"` rule does **not** catch declined fouls, contrary to §3.2 and §11.
*Text:* "`penalty` = `typeId == "8"` or `text` contains `"PENALTY on"` (case-sensitive). A declined foul ('Penalty on
…, declined.') does not show the flag: the play stood as run. Offsetting fouls are unverified and are tested with a
synthetic body." Also require the `lastPlay` id to be **numerically newer** than every id seen in the follow (ids are
event id + sequence, e.g. `401873061503`). Then a play ESPN reverts or re-sends can never re-trigger a flag, and a
replay after a refused poll or a restart cannot either.

**RC4. Fix the precedence line so it matches the state table.**
*Why.* §3.4 says "pinned score > break/final > stale/waiting > flag > timeout > play", but its own rows give
NO_UPDATE / DELAYED in a break and DELAYED, then FINAL, at Final, as ST §8 does. *Text:* "pinned score (runner) >
stale / waiting > break / final > flag > timeout > play. A flag and a timeout never coexist: the newer `lastPlay` ends
the older mark."

**RC5. Stale and waiting in play: follow the Stacked baseline (ST §8), or record the change as a deviation.**
*Why.* For stale and waiting in play, the NHL Stacked shows the period over **"No update" / "Delayed" in the clock
slot** (the two-line fit, ST fix pass) at .55 and **closes** the bar. The NFL Pill closes its tab too: `nfl.down` and
`nfl.tab` are null when not fresh, NFL §3.4. The proposal shows a **frozen clock** with the words in an open neutral
bar, which is a change to the baseline that the proposal does not list. *Text for the two §3.4 rows:* "Stale, in play
→ no ball, clock column open: period over 'No update' (ST two-line fit), column .55; bar **closed**. Waiting, in play
→ the same with 'Delayed'." The break rows are unchanged. If the lead prefers the bar to stay open, that must be
listed in §10 with its reason, and the clock slot must still show the words, not a frozen clock.

**RC6. Leave the disabled YDS column out of the footprint.**
*Why.* The proposal's justification ("turning it on later moves nothing") is void under the 2026-11-13 mirror: the
rows hug the footprint's **outer** edge in all four corners, so a wider footprint only moves the inner edge, which
nothing is anchored to. Meanwhile 63 px of phantom footprint (79 Large) feeds every hide and lift rule, `glowAllowed`
and the place hint, so the bug hides for overlays it would never touch. It is also 63 px of screen promised to a
column that never draws. *Text:* "`STACKED_NFL_W_MAX` = `STACKED_ROWS_W + STACKED_CLOCK_W + (if
(STACKED_NFL_YDS_ENABLED) STACKED_NFL_YDS_W else 0)`: **333 × 141** Standard, **416.25 × 176.25** Large while YDS is
off." Right corners at Standard: (1491, 56)–(1824, 197) and (1491, 883)–(1824, 1024). At Large: x 1407.75–1824. Left
corners: 96–429. Enabling YDS later is a one-constant change with the same tests.

**RC7. OT pips.**
*Why.* The pips always draw 3 slots. Regular-season OT gives 2 timeouts, so a team that has not called one would show
a used pip. *Text:* "Slots = the highest count seen in the current half group, at most `NFL_TIMEOUTS_MAX` (3): 3 in
regulation, and in OT whatever ESPN starts the group at." Not verified live (no OT today), so this stays an emulator
item with a synthetic body.

**RC8. Correct the feed facts and the owner-question wording from the samples.**
*Why.* The design's "not determined" list is mostly settled now (see the table below), and Q2 quotes the summary at
"up to about 550 KB". Measured today: `summary?event=` is **175 KB in Q1** (`summary-401873061-161926.json`) and
**662 KB at final** (`summary-final-401873058-161955.json`). Both carry `boxscore.teams[].statistics` with
`totalYards` (ARI 31 / LAR 0 in Q1; KC 461 / ATL 370 final). The per-event scoreboard the follow reads is 9 KB and has
`statistics: []`. Replace §12 items 1, 3 and 4 and §3.1's "assumed" cells with these facts and file names.

**RC9. State, and test, that the kickoff after a score never reads RED ZONE.**
*Why.* `live-official-timeout-kickoff-redzone-true-161844.json` (SEA @ LV, 7:41 Q1) has `isRedZone: true` with
`down −1`, `distance 0`, `yardLine 35`, and possession on the scoring team during the post-score TV break. That state
held for over 3 minutes (`sb-161844` through `sb-162133`). The mapper already gates it (`redZone = isRedZone &&
play is Down`, `EspnMapper.kt:261`), and the KICKOFF bar must read that gated value and never the raw field. Add the
trimmed body as a fixture to `EspnMapperStackedTest` and assert "LV KICKOFF | LV 35" in LV's colours, with no red
patch.

---

## Recommended changes

1. **Owner questions:** drop Q3 (flag hold). It is a tuning value the lead can default to 8 s and check by eye. That
   keeps the list at two (below).
2. **Near-navy bars: report ΔE76, not the luminance count.** 22 bars are under 1.5 : 1 against the rows (confirmed),
   but 15 of them differ clearly in hue (SF ΔE 82.7, ARI 52.6, WSH 46.9, NYJ 45.7, CLE 42.1, GB 31.7, LV 32.0, …).
   Only **IND (ΔE 3.9), DAL 9.0, NE 9.6, SEA 9.6, TEN 11.4, NYG 14.4, CHI 18.4** (plus the fallback, 18.9, and the
   neutral bar, 7.8) fall under the project's own look-alike threshold of 20. SR3 covers them: the owner accepted
   "the teams' real colours, with a thin light line and bright sliding stripes" for the navy teams. But the NFL bar is
   open in all play, not occasionally as a power-play bar is, so add IND @ NE and DAL @ NYG to the screenshot set and
   to the owner screenshots.
3. **Pip size:** 9.9 × 2.7 is 1.8 px tall on a 720p panel, and the burn flashes a 1.8-px element. Check legibility at
   3 m. A floor of about 3.6 px high (pips 11.7 × 3.6, block about 40.5) still fits above the 54-px row (top 40.05 to
   bottom 43.65).
4. **`StackedColourTable` NFL arm:** add a separate NFL table object (keys from `NflTeamColours.TABLE`, fallback
   `NflTeamColours.FALLBACK`). Do not change `LeagueTable`'s CHL constructor path, so the CHL resolutions are not even
   reordered. The CHL and NHL colour tests and the hash gate guard it anyway.
5. **Mirror wording (§5.3, bar row):** the REKEY text slide `dx +11.7 → 0` starts on the **screen-edge** (outer) side,
   not the "inner side". The value is right; the wording is not.
6. **A lost challenge** charges a timeout with no type 21. The RC1 fallback then shows "TIMEOUT X | n LEFT" for 8 s,
   which is accurate. Record it in §3.4 so nobody "fixes" it.
7. **Fake panel (§9.4):** besides the three windows, add:
   - an accepted foul on a play that stands (type 24 with "PENALTY on");
   - a declined foul (type 3, "Penalty on …, declined."), which must not flag;
   - a type-74 Official Timeout kickoff with `isRedZone: true` (RC9);
   - a count flap 2 → 3 → 2 in Q4 (RC2);
   - an OT window at 2/2 (RC7);
   - a TD with "PENALTY on" in the same body (the score wins).

   Keep each window ≥ 25 s.
8. **§1 carried table:** "possession always on" (R22) is right for the NFL. Also record that, unlike the Pill (tab
   closed when the possession is unknown, NFL §0), Stacked shows a neutral bar with the down: Stacked has no separate
   down column, so this is a justified deviation. List it in §10.
9. **Accessibility:** announce "Timeout, X." only from a type-21 mark, not the fallback, and "Flag on the play." at
   most once per `lastPlay` id.

---

## Verified feed facts

| Fact | Finding | Evidence |
|---|---|---|
| URLs the app uses | `https://site.api.espn.com/apis/site/v2/sports/football/nfl/` + `scoreboard`, `scoreboard?dates=`, `scoreboard?seasontype=&week=`, and per event `scoreboard/{id}` (read first by the follow) | `data/.../EspnScoreSource.kt:58, 139–150, 212` |
| `situation.awayTimeouts/homeTimeouts` present in play | yes, in every in-progress body, including the per-event endpoint | **live**: all `sb-*.json`, `event-401873061-162614.json`; repo fixtures |
| Persist after use | ARI 2 from 13:44 Q1 on (3 → 2 held through every later poll) | **live**: `sb-161844` … `sb-162536` |
| When they decrement | in the **same poll** as the `lastPlay` type 21 | **live**: `live-team-timeout-type21-LAR-162436.json` (away 3 → 2) |
| Team timeout as `lastPlay` on the scoreboard | yes: type 21 "Timeout", text "Timeout #1 by LA at 06:56.", `team.id` 14; held ≥ 60 s until the next snap; clock unchanged (6:56 → next play 6:55) | **live**: `sb-162436`, `sb-162506`, `sb-162536` |
| `lastPlay.team` = caller? | **unproven**: 14 was also the possession team, and type 74 carries the possession team too | live `sb-161844` |
| Text team codes | legacy ("LA", "ARZ"), not the scoreboard's "LAR" / "ARI": never parse | **live** samples, summary |
| Official timeout | type 74 "Official Timeout" is the most common `lastPlay` (43 of the repo log's samples); after a score it held more than 3 min | **live** `sb-161844`–`sb-162133`; repo log |
| Penalty, no play | type 8 "Penalty", text "…PENALTY on LA, Illegal Formation, 5 yards…", shown after the play (clock unchanged, 7:23) | **live** `live-penalty-type8-LAR-162405.json` |
| Penalty, play stands | the play's own type (Kickoff 53, Rush 5 on the scoreboard; Punt 52, Pass Reception 24 in the summary) with "PENALTY on" | **repo log** `live-sample-2026-11-08.log` 14:36:07, 14:40:27; **live** summary final |
| Declined | "Penalty on KC-…, Offensive Holding, declined." (mixed case), on type 3 | **live** summary final (scoreboard `lastPlay.text` is the same play text: inferred, not seen on the scoreboard) |
| Offsetting | — | **unproven** |
| Flag visible mid-play | no: ESPN logs the foul with the resolved play | **live** + repo log |
| Two-minute warning | type 75 | **live** summary final; repo summary excerpt |
| End of quarter | `STATUS_END_PERIOD`, situation kept, `lastPlay` 2 | **repo** `scoreboard-live-end-period-2026-11-08.json` |
| Halftime `situation` | — | **unproven** (no game at halftime during the window) |
| Kickoff after a score | `down −1`, `distance 0`, `yardLine 35`, possession = kicking team, `lastPlay` 74, **`isRedZone: true`** | **live** `live-official-timeout-kickoff-redzone-true-161844.json` |
| Kickoff return poll | `possession` absent while `down` 1 & 10 "SEA 27" is already present; the next poll names SEA | **live** `live-kickoff-return-possession-null-162203.json`, `sb-162234` |
| Clock lag | clock held 10:41 across a completed play, then jumped | **live** `sb-161844` → `sb-162032` |
| Overtime | — | **unproven** |
| Final / not started | no `situation` at all (so no timeouts) | **live** `sb-161844` (final and scheduled events) |
| Team yards on the scoreboard | none: `competitors[].statistics` is `[]` in progress (full board and per-event) and final | **live** all `sb-*`, `event-401873061-162614.json` |
| Team yards in the summary | `boxscore.teams[].statistics` `totalYards` (ARI 31 / LAR 0 Q1; KC 461 / ATL 370 final); 175 KB live, 662 KB final | **live** `summary-401873061-161926.json`, `summary-final-401873058-161955.json` |

## Rulings check

| Carried ruling | Source checked | Verdict |
|---|---|---|
| Nothing before kick-off | R4, NFL lead sign-off 1 | correct |
| Possession always on in play, off in breaks and at Final | R22 | correct; consistent with NHL "tab only for a power play" because R22 is the NFL ruling |
| No local countdown | R6 | correct (the mock's `tick()` dropped) |
| Size × 0.45, Large × 1.25, info 20 / bar 33 | SR1, SR8, ST S5 | correct; conversions re-computed (code 25.2, ball 13.5, pips 9.9 × 2.7 at 40.05, score 35.1 in 45 at 14.4, clock 27.9, word 38.7 / 33.3 / 36, badge 58.5, posts 40.5 × 54 at (234, 22.5), flag icon 15.8 × 17.9) |
| Right-corner mirror, same order from the edge, glyphs never flipped, "/" kept, spin negated | 2026-11-13 decision, ST §4.3 items 1–6 | correct for every new element (pips lit on the edge side, ball rotation negated, posts symmetric); wording nit (Rec 5) |
| Fixed navy rows, one derivation, hairline, stripes ≥ 2 : 1, ΔE76 < 20 swap | ST lead sign-off | correct; derivation **verified bit-for-bit** against the Kotlin (all 32 + fallback; 118 ordered collisions: 116 home swaps, 2 away swaps, DAL @ LV and DAL @ PHI; 22 bars < 1.5 : 1) |
| Dark bars accepted with hairline and stripes | SR3 | covers it in principle; frequency differs (Rec 2) |
| Goal colours = team's own | SR2, R12 | correct |
| Break look (clock folds, neutral bar) | SR4a | correct by analogy |
| "1ST / END" at 0:00 | SR5 | rightly n/a (ESPN goes to END_PERIOD; NFL §1.2) |
| Shootout analogue | SR6 | n/a; OT is a period, "FINAL/OT" is used: correct |
| Stale / delayed look | ST §8, ST lead | **in play, wrongly carried** (RC5); in breaks correct |
| Precedence | ST §8 | **inconsistent** (RC4) |
| Footprint includes the widest box | ST §4.3 | carried literally, but the widest **drawn** box is 333 (RC6) |
| No sound; reduced motion; no low-end path | R9, R20 | correct |
| Old score held until the takeover covers it | NFL D10, GA §5.2 | correct |
| Flash on the 450 grid, pip burn 2.5 Hz | NFL §5.3, GA B3 | correct; panel flash ends at 1 800 where the post hits start (no overlap) |
| Code > 3 letters disc | CHL §6.4 | n/a in practice (all ESPN NFL codes are 2–3 letters: WSH, LAR, LAC, JAX checked); the step-down rule is a safe extra |
| Missing logo → `LogoDisc` | ST | correct |
| One shared style setting | ST "hockey only", CHL P12 | a product change; the owner question stays (Q1) |
| No telemetry; delay applies | R1 | correct (marks start when shown) |

## Geometry notes (checked, no change needed beyond the above)

- **Text fit.** Bar strings at 20 (and 25 at Large) fit in 333 with ≥ 18 px to spare ("WSH KICKOFF | WSH 35" is the
  worst at 243.8 + 63). Break strings fit in 243. The takeover fit rule is right: the mock itself clips "+6" and the
  tally at 1.5 s (`22-td-1500.png`, confirmed). At 333 with tight padding, the room is 163.8, so TOUCHDOWN goes to 34.3
  and EXTRA POINT to 32.2, while FIELD GOAL (156.8) and TWO-POINT (159.0) fit at their mock sizes. The ×1.05 pulse
  overruns by ≤ 4.3 px into the 13.5 gaps.
- **Scores.** NFL scores are realistically 2 digits. The 3-digit step-down (to 24.5) is a safe extra.
- **Safe area.** Margins 96 / 56 hold in all four corners at both sizes (Large bottom 847.75–1024).
- I spot-checked the conversions arithmetically. I did **not** re-measure glyph advances.

## Engineering notes

- **Hash gate.** The claim holds if the edits stay as written:
  - `ScoreBug.kt`: NFL + PILL still takes `NflBug` with the same key;
  - `ScoreBugPlacement.width/height`: the condition must stay `league == NFL && style == STACKED` → the new sizes, and
    everything else unchanged;
  - `NflScoreTimeline`: the PILL spec is guarded by the bitwise legacy test;
  - the new `NflView` fields are trailing and defaulted. Existing tests build `NflView` positionally, and I found no
    whole-view equality assertions in the NFL machine tests;
  - `StackedColourTable`: the NFL arm is not drawn by any existing golden.

  Extra Pill emissions (timeout or flag changes) redraw the same frame.
- **Surface rules.** One leaf layer, `clipRect` only, no blur or `setShadowLayer`, sprites baked: compliant. The flag
  wave (≤ 8 s) and a 550-ms REKEY every play (about every 30–40 s) are the new redraw costs. Put both in the device
  frame test, as proposed.
- **Work packages** are file-disjoint as listed. Commit 0's contracts suffice once RC1 adds `NFL_TIMEOUT_FALLBACK_MS`,
  and RC1/RC2 add the low-water state, which is machine-internal and needs no new contract.

## Owner questions (kept to two)

1. **One "Score bug style" for every sport?** Anyone who picked Stacked for hockey also gets the Stacked football bug.
   *Default: yes, one shared setting* (Pill stays the default, so most users see no change).
2. **The yards column.** ESPN's live scoreboard has no team yards. They exist only in the game summary, a second
   download of about 175 KB early in a game, growing to about 660 KB by the end. *Default: leave the yards column
   out; everything else in the mock-up is shown.*

## Things I could not check

- The halftime `situation` (including when the counts reset to 3/3), overtime counts and the OT final, and the text of
  offsetting fouls: no game was in those states during the window.
- Whether `lastPlay.team` on a type 21 is the caller or the possession team (they were the same team in the one
  sample).
- A declined foul as a **scoreboard** `lastPlay` (seen in the summary only; the text is the same play text).
- Glyph widths and kerning (not re-measured); the proposer's `measure.py` itself.
- The hash gate was not run (no code exists yet). I ran a throwaway `:core` test to print the real colour derivation,
  then deleted it; the worktree is unchanged apart from this file.
- Device items: Fire TV frame time, TalkBack/VoiceView, the dark bars over real video.
