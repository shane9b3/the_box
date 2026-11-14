# CHL WP-5 (UI) and WP-4 (colours): code review (2026-11-14)

- WP-5: `75dfdc3` "Show the CHL leagues on the Sports tab and in the picker" (diff against `cca5caf`).
- WP-4: `4aea962` "Add the colour tables for the 61 CHL teams" (diff against `ae2a3ea`).
- Code read in `chl-int` (branch `chl`, both merged). Spec: `docs/design/m6-chl-leagues.md` §1.5, §1.6 rows 48–63,
  §3.5, §6, §8; `docs/decisions/2026-11-13-chl-leagues.md`.

## Verdicts

| Package | Verdict |
|---|---|
| **WP-5 UI** | **Fixes needed**: one should-fix (the Stacked fallback capsule, S1) plus small items. NHL / NFL behaviour is unchanged apart from the third chip. |
| **WP-4 colours** | **OK.** The table matches the research note exactly. One owner taste call (PBO and WEN get a near-black glow and bar, R2). One test should-fix (S4). |

No blockers.

## What was verified by running, and what was only read

**Run:**
- Tests in the WP-5 worktree (`:ui:testDebugUnitTest --tests '*Chl*' '*SportsScreen*' '*TeamCode*'`, run fresh at
  06:24 UTC): 66 tests in 9 suites, **0 failures**.
- **Hash gate.** I checked the 1 260 screenshot PNGs in the WP-5 worktree's `ui/build`. All of them are newer than the
  last source edit, and the tree is clean at `75dfdc3`. I checked them with
  `shasum -a 256 -c` against `sports-hashes.txt`: **only `sports-tab/sports-tab.png` fails**. I did not regenerate the
  PNGs: the lead is running the full gate in `chl-int`.
- **Pixel diff of that one image.** I compared it with the pristine `c0aa7099…` copy in the main checkout's `ui/build`:
  - 3 697 pixels changed, all inside the box x 430–510, y 60–111;
  - that box is exactly the new CHL chip;
  - nothing else in the image moved.
- **Stacked capsule measurements.** I measured the rendered `chl-ui/stacked-*.png` sheets with a small PNG reader
  (numbers in S1).
- **Colour derivation re-computed** (WCAG ratios, the `NhlTeamColours.derive` rules) for the SAG / WSR question and the
  NHL comparisons (R1, R2).
- **Mechanical diff of the three Kotlin tables** against the three tables in `docs/research/chl-team-colours.md`:
  - 61 / 61 codes;
  - every base, accent and confidence equal;
  - the only differences are the two documented nudges, NB and KEL.

**Read only:**
- the focus code, the view-model state, the logo path, the pair cache and the remaining tests.

---

## WP-5 findings

### Should-fix

**S1. The fallback capsule breaks the O3 limits in the Stacked rows**

- Where: `TeamLogo.kt:105` (`capsuleTextPx`), `:87` (`scoreInkLeft`); `StackedPainter.kt:615`.
- The problem: `LogoDisc.capsuleTextPx` is solved against the **Pill's** geometry only: the 64 px slot and the Pill
  score's ink. `StackedPainter` uses the same `LogoDisc`. In its 50.4 px logo slot (`LOGO_SLOT_W` 112 × 0.45) the same
  67 px capsule therefore:
  - overhangs the slot by **8.3 px a side** (the ruling allows ≤ 4);
  - sits **about 1 px** from the stripe;
  - sits about 5 px from the code's anchor.
- Who sees it: BRAM always falls back, so every Brampton game in Stacked shows it. FLNT and NIAG show it while their
  logo loads (`LOGO_WAIT_MS`) or when it fails.

Measured on the rendered sheets (device px; Standard = design px, Large = × 1.25):

| Case | Capsule width / slot | Gap to the stripe | Gap to the code's ink | Disc (KIT), for comparison |
|---|---|---|---|---|
| Std, unmirrored, PP stripe (BRAM–KIT) | 68 / 50.4 | 1 | 7 | stripe 12, code 18 |
| Std, mirrored, PP stripe | 68 / 50.4 | 1 | 6 | stripe 12, code 17 |
| Std, unmirrored, no PP (BRAM–FLNT, both rows) | 68 / 50.4 | 1 | 6 | — |
| Std, mirrored, no PP | 68 / 50.4 | 1 | 5 | — |
| Large, unmirrored, PP | 85 / 63 | 1 | 8 | stripe 15, code 23 |
| Large, mirrored, PP | 85 / 63 | 1 | 7 | stripe 16, code 21 |

- The logo moves with the stripe (`logoCentre(stripe)`). So the gap to the stripe stays at about 1 px whether or not
  the power-play stripe is shown, and also during its width animation. What changes is the stripe's own width: 3.6 or
  6.3 design px.
- Arithmetic (design px): slot centre = stripe + 35.1; capsule half-width 33.5, so its left edge is at stripe + 1.6;
  the code anchor is at stripe + 73.8, so it is 5.2 px past the capsule's right edge.
- To meet "overhang ≤ 4" in a 50.4 slot, the capsule must be ≤ 58.4 wide. BRAM's text is 47 px wide at the 16 px
  floor, so 47 + 2 × 10 padding = 67. Even with the padding cut to 6 it is 59. **No text size at or above the floor
  fits.**
- The tests do not catch it: `ChlFitTest.everyCodeFitsAStackedRowBothOrientations` (`:248`) checks only
  `rowGap(code, score)`, never the fallback against the stripe or the code.

**Recommendation (rule for the lead to record):** "In the Stacked rows the logo fallback stays inside the 50.4 slot
(overhang ≤ 0) and keeps ≥ 6 px from the stripe and from the code's ink."

The Stacked row already prints the code right beside the logo slot, so the text inside the fallback is redundant
there. Pass the slot width into `LogoDisc` (or give `StackedPainter` its own fallback). Then, for a code longer than
3 characters, Stacked draws one of:
- **(a) recommended:** the standard 44 px disc **without text**. It is 3.2 px inside the slot each side. The gaps
  become the disc's own (stripe ≈ 12, code ≈ 17 Std), identical to the 3-letter teams.
- **(b)** the same disc filled with the team's base colour, outlined like the logo-less NHL test discs. It reads as a
  logo stand-in.
- **(c)** the disc with stepped-down text. This needs about 12 px for BRAM, below the 16 floor the lead ruled, so it
  would itself need a new ruling.

(a) or (b) is the owner's taste call; (c) is not recommended.

**The Pill capsule is within the ruling.** It is pinned by `ChlFitTest` on painted ink: overhang 1.5, gap to the score
9.5 (Large 9.3). Keep it.

**The card code plate is fine.** BRAM at 22 SemiBold is about 64 px of ink in the 80 px plate, about 8 a side (seen on
`tab-ohl-cards.png`). The test asserts the advance width ≤ 68, which is adequate.

Add a fit test for the Stacked fallback: its painted extents against the slot, the stripe (both widths) and the code's
ink, unmirrored and mirrored, Std and Large.

**S2. The cards fetch a failed CHL logo again every time a card is composed**

- Where: `GameCard.kt:193-199`.
- The problem: `CardLogoCache` holds only successes. For the 6 fallback teams, every Sports-tab entry, day switch or
  scroll back recomposes `LogoPlate`, which re-runs `coilLogoFetch`:
  - for 404s (BRAM, BDN, KEL, PEN, SAS) that is a network GET each time, because Coil does not disk-cache an error;
  - for WEN it is a disk read, a full decode and the alpha check each time.
- This is bounded by user navigation, so it is not a retry storm. It is still pointless traffic and CPU on a Fire TV
  Stick, and new with the CHL: NHL and NFL logos never fail in steady state.
- Fix: a small negative cache (`url → failed`, process lifetime or about 30 min) beside `CardLogoCache`. The bug's
  `FollowLogos` already fails once per follow and is fine.

### Low

**L1. The CHL check is chosen by sniffing the URL** (`ChlLogoCheck.kt:37`, `TeamLogo.kt:33`)

- What happens: `isChlHost` is a case-sensitive `startsWith`. These forms all skip it and take the NHL path, with no
  allow-list and no alpha check:
  - `HTTPS://ASSETS.LEAGUESTAT.COM/…`;
  - `https://assets.leaguestat.com:443/…`;
  - `https://x@assets.leaguestat.com/…`.
- Today this is unreachable: `HockeyTechScoreSource.logoUrl` only returns URLs that start with the exact
  `{logoBase}{client}/logos/` prefix and match the file regex, so host confusion, userinfo, traversal and query
  strings are already rejected at the source.
- The UI regex itself is correct:
  - anchored (`matches`);
  - https only;
  - `.evil.tld`, `@`, `..` and `?` are all rejected.
- Better, if touched again: decide "CHL" from the league (the `TeamLine` or `GameId.league` the caller has), not from
  the URL string.

**L2. The fake panel never exercises the alpha check.** In a debug override the logo base is `{origin}/leaguestat/`.
That is not the CHL host, so CHL logos from the fake panel skip `ChlLogoCheck`, and WEN's fallback cannot be seen on
the emulator. Fixed by L1's league-based flag, or by also treating `endpoints.logoBase` as CHL.

**L3. Redirects and sizes.**
- **Redirects:** the shared OkHttp client follows redirects (`HttpModule.kt:41`), so a 30x from `assets.leaguestat.com`
  to another host would be fetched. The result is still decoded as an image, under the same rules as the NHL path.
  Accepted risk; just noting it.
- **No byte cap for PNG or JPEG**, unlike `GuardedSvg`'s 512 KB. Decoded memory is bounded, because Coil subsamples to
  4 × the slot with `Precision.INEXACT`. The download is bounded only by the disk cache. Same as the NHL's PNG-free
  path; acceptable for a fixed HTTPS host.

**L4. The alpha check's cost is fine.** It runs on Coil's decode thread. It is `hasAlpha()` plus 4 `getPixel` calls on
the already-decoded bitmap, with no allocation beyond two 2-int arrays. The result is cached by the callers on
success: `FollowLogos` per follow, `CardLogoCache` for cards.

**L5. The capsule's one-entry cache thrashes when both teams show a capsule** (`TeamLogo.kt:134-139`)
- What happens: `LogoDisc` remembers one capsule code. One painter draws both rows with one `LogoDisc`. In BRAM vs FLNT
  or BRAM vs NIAG (all OHL) during the logo wait or after a failure, every frame recomputes `capsuleTextPx` twice: a
  `stepDown` lambda allocation and several `measureText` calls.
- That breaks the class's "draw allocates nothing" claim.
- Fix: cache two entries, or compute both in `bind`.

**L6. `focusSub` can fire late** (`SportsScreen.kt:141-156, 202`)
- What happens: OK on CHL sets `focusSub = true` before the store write lands. With the real (asynchronous) store, if
  the user presses Left within that window, focus is pulled back to the sub-chip a frame later.
- Harmless; clearing `focusSub` on any focus move would close it.
- Also, OK on CHL while CHL is already shown moves focus to the sub-chip. I think this is right (the spec says only
  "changes nothing" for the league), but it is untested.

**L7. Back from a sub-chip and RTL are untested.**
- Back from a sub-chip follows the existing ladder; I read the code and found no special handling.
- RTL mirrors the Row automatically.
- The 720p row fit is asserted (`nothingMovesVerticallyAndTheRowEndsBeforeCornerAt1280`).

**L8. `TeamCodeIsNotALeagueTest`'s static grep is narrow but adequate.**
- What it covers:
  - `League.valueOf` and `enumValueOf<League>`;
  - `entries.first{…name}`;
  - `valueOf(` with an abbrev, away, home or code argument.
- What it misses: string comparisons such as `abbrev == "NFL"`. A grep of the main sources for `"NFL"` finds only
  comments and the QMJHL table key, so there is no offender today.
- The behavioural tests cover the real risk:
  - the followed QMJHL NFL – CHA game is a hockey view;
  - its colours come from the CHL table;
  - the picker reads "League · CHL" / "CHL league · QMJHL";
  - the row text is `NFL 2–3 CHA · 2nd 10:22`.
- Suggest adding `"NFL"` string comparisons against code variables to the grep.

### Notes (checked, no change needed)

**NHL and NFL behaviour.**
- The three `SportsScreenTest` edits each add one Right press for the CHL chip. That is the only behavioural change.
- The NHL and NFL paths are unchanged:
  - "today": `dateFallback` NHL = Eastern, NFL = none;
  - the period, failure, empty, credit and not-listed texts (re-expressed per group, same strings);
  - `order` (by sport);
  - the 3-letter badge size (always 24);
  - the logo requests (`logoTransformations(false)` = `[LogoTrim]` only, asserted).
- The hash gate confirms all of this: one image changed, and only in the chip's pixels.

**`SportsViewModel` constructor.** The new `nowUtcMs` sits before `easternToday`, with a default. A positional
4-argument call with a `LocalDate` lambda would fail to compile rather than mis-bind, and existing callers pass
`easternToday` named or as the trailing lambda. Safe.

**State.**
- `onGroup(CHL)` writes `league = chlShown`.
- Sub-chip OK and `setScoreChlLeague` write `league` + `chlLeague`.
- NHL and NFL leave `chlLeague` alone (tested).
- `ScoreFacts.chlLeague = p.chlShown`.
- The quick-bar League and CHL rows read the same `prefs.league` as the tab, so they always agree.
- A game being followed is unaffected by a league switch (as with the NFL).
- Process death: both fields are in the persisted, backed-up `sports` domain; only the transient `focusSub` is lost.

**Focus.**
- Up from the day chips goes to the shown sub-chip, or to the group chip when CHL is not shown.
- Down from every chip goes to the selected day chip.
- Left and Right walk `NHL NFL CHL | OHL WHL QMJHL … Corner Delay`; the divider is not focusable.
- When the sub-chips disappear, focus is on the group chip that was just pressed, which stays.
- Nothing moves vertically (asserted at 1920 and 1280).

**Spoken text.** As P10: the cards say the nickname (plus the state), and the bug and live region say "City Nickname"
(asserted: "London Knights 1, Kitchener Rangers 2, 10:22 left in the 2nd").

**Fit tests.**
- They assert real geometry:
  - the capsule and disc on painted ink;
  - the badge on text ink bounds;
  - the PP labels on block width against room;
  - the bar strings on ink edges (both orientations);
  - the takeover on badge, word and tally edges;
  - the picker rows and cards on advance width.
- They are not just crash tests. The one gap is S1.

---

## WP-4 findings

### Should-fix

**S4. The tests would not catch most wrong entries** (`ChlTeamColoursTest`)
- What the tests pin:
  - the keys, which equal the fixture codes (a code typo fails: good);
  - C1 and C4–C6, which are constraints;
  - six spot-checked hex values.
- What they miss: a mistyped hex or a swapped base and accent that still passes C1–C6. `derive(base, accent) == of(…)`
  is tautological.
- Fix: a test that parses the three tables in `docs/research/chl-team-colours.md` and asserts equality, allowing the
  two listed nudges. The doc already has a parsable shape: my 20-line script matched all 61 rows. This also keeps the
  sign-off doc and the code in sync when the owner flips a row.

### Low

**L9. MH's accent and the research note disagree.** MH's accent is `#000000`, but the research note says
"TCC = Wikipedia (#E56B1F/#231F20)". Harmless: MH's glow is its orange base, and its tab is that orange too (white on
it passes C5 at 4.53, itself a knife edge). Make the note or the table say which one is meant.

**L10. Loading the NHL table now also builds the three CHL tables** (`StackedColourTable.kt:26-28`)
- What happens: the `LeagueTable`s are eager object properties. The first `StackedColourTable.pair(NHL, …)` therefore
  also derives the 61 CHL teams (contrast loops; about a millisecond, once) on whatever thread first draws a Stacked bug.
- Not an NHL behaviour change (the NHL results are identical; the hash gate holds). `by lazy` per league would keep
  the NHL path's first-frame cost exactly as before.

### Notes (checked, no change needed)

**Correctness against the research note.**
- 61 / 61 rows, with base, accent and confidence identical.
- The only departures are NB `#807A11 → #7F7911` (C1 4.46 → 4.52) and KEL `#008394 → #008293` (4.49 → 4.55); I
  recomputed both. Each channel moved by 1, so the change is imperceptible.
- MH is the only C1 exception (3.25 : 1), as in the PHI precedent.
- Codes equal the recorded `teamsbyseason` lists (test).

**Pair cache.**
- Size per league: (n + 1)² references: OHL 441, WHL 576, QMJHL 361, about 5.5 KB of slots in total, with
  `StackedPair`s created lazily.
- Thread-safe: `AtomicReferenceArray` with a CAS (the loser returns the winner's pair).
- The draw path allocates nothing after the first lookup. `trim()` and `uppercase()` return the same instance for an
  already upper-case code; the `HashMap` lookup on a `String` does not box.
- `pair(NHL, …)` still calls `NhlStackedColours.pair` unchanged; the hash gate confirms it.

**R1. SAG and WSR get a red power-play bar: the NHL derivation, working as designed**

There is no table error: navy is correctly the base for both. The rule chain, from `NhlTeamColours.derive` (`:104-118`)
and `NhlStackedColours.derive` (`:98-103`):
1. `glow` is the base only if the base's contrast against `GLASS_DARK #090B10` is ≥ 2.0; otherwise it is the accent.
   Every navy base fails: SAG 1.53, WSR 1.31, ER 1.19, FLNT 1.18, BRAM 1.40. So each team's glow is its accent.
2. `tab` is the glow if white text on it passes C5 ≥ 4.5 (measured at gradient point 0.8); otherwise it is the other
   colour.
   - Red passes: SAG `#BE2F38` 7.60, WSR `#E31B23` 6.42. So the tab is red.
   - Gold, orange and silver fail: ER `#FEC52F` 2.33, FLNT `#FC5D00` **4.40**, BRAM `#A8A9AC` 3.37. So the tab falls
     back to the navy base.
3. The Stacked bar is `mixBlack(tab, 0.172)`.

The NHL behaves the same way:
- **Accent bar:** WSH (`#041E42` / `#C8102E`, red), CBJ (red), WPG (blue), VAN (green), NYR (red).
- **Navy bar:** NSH (gold accent), TOR and TBL (white accent), STL (gold), SEA (aqua).

The rule is "the bar is the team's accent when white text reads on it, else its base". The same holds in the CHL:
KIT, BAR, TC, REG, KAM and NFL get their accent.

**Recommendation:** no change. Note that FLNT is on a knife edge (4.40 against 4.5): any tweak to its orange turns its
bar orange, the way EDM's `#FF4C00` (4.69) already is in the NHL.

---

## Rulings requested

**R1 (lead, owner taste on the look): the Stacked fallback for codes longer than 3 characters (S1).**
- Rule: inside the slot, with ≥ 6 px of air from the stripe and the code.
- Look: (a) an empty neutral 44 px disc (recommended) or (b) a team-base-colour disc.
- The Pill capsule and the badge sizes stay as ruled.

**R2 (owner taste call): PBO and WEN get a near-black glow and power-play bar.**
- **PBO** (maroon `#75263D` + black):
  - the base's contrast against the glass is **1.97**, just under the 2.0 rule;
  - so the glow is black (1.07 against the glass, invisible as a glow);
  - and the tab and bar are black: on the OHL sheet, Peterborough's Pill tab and Stacked bar are black.
- **WEN** (navy `#002F87` + near-black `#231F20`): the glow is `#231F20` (1.21), and the tab and bar are near-black.
- No NHL team hits this: no NHL team has both colours under 2.0 against the glass.
- Options:
  - **PBO:**
    - a 1 % nudge to `#77273E` (the NB/KEL precedent). Contrast against the glass becomes 2.01, so the glow and the
      bar become maroon (C5 11.8; white on it 9.8);
    - or the accent goes to white (the sourced third colour): glow white, bar maroon, the TOR/TBL pattern.
  - **WEN:** the accent goes to its sourced third colour, blue `#026FA8`: glow 3.60 against the glass, a blue bar with
    C5 7.23.
  - **Or keep both** as sourced, as for the black-based teams KGN, SBY, BLB and so on.
- Recommendation: nudge PBO, and switch WEN's accent to blue. Both are one-line changes and both stay on the sign-off
  sheet.

**R3 (lead): whether S2's negative logo cache is wanted for v1.** It is cheap, and it removes repeated 404s for 5
teams on every visit to the tab.

---

## Fix pass (WP-4)

Lead rulings applied on top of `chl` (uncommitted; the lead commits). Files: `ChlTeamColours.kt`,
`docs/research/chl-team-colours.md`, `ChlTeamColoursTest.kt`, the sheet test.

- **R2 (PBO).** Base `#75263D` to `#77273E`. Base against the glass 1.97 to 2.01; glow and tab black (glow 1.07 against the
  glass, C5 21.00) to maroon `#77273E` (2.01; C5 11.77; white on the bar 11.77). Recorded in the note as a lead ruling
  pending the owner's sign-off.
- **R2 (WEN).** Accent `#231F20` to the sourced blue `#026FA8`. Glow and tab near-black `#231F20` (1.21 against the glass,
  C5 17.29) to blue (3.60; C5 7.23; white on the bar 7.23). Same note entry.
- **S4.** `everyBaseAndAccentEqualsTheResearchNoteExceptTheListedNudges` parses the three team tables in the note (61
  rows) and asserts every base and accent equals the table, with the four nudges (NB, KEL, PBO, WEN) listed explicitly
  in the test; a nudge that equals the note fails, so the list cannot go stale. A mistyped hex or a swapped base and
  accent now fails. The spot-check test also pins PBO's and WEN's glow and tab.
- **L9.** MH's accent is `#231F20` (the sourced value in both TCC and Wikipedia); the table said `#000000` by mistake. The
  table and the note now agree. No visible change: MH's glow and tab are the orange base.
- Contact sheets re-rendered.
## Fix pass (WP-5), 2026-11-14

Applied on top of `chl` (uncommitted). Lead rulings as given; each item with what was done.

| Item | Done |
|---|---|
| **S1 / R1** Stacked fallback | `StackedPainter` passes the row's stripe colour to the fallback. A code longer than 3 characters draws a plain 44 px disc without text in that colour (`LogoDisc.drawStripeDisc`); 3- and 2-letter fallbacks and the NHL are unchanged. The Pill keeps the capsule. Rule written into design §6.4 and the decision note. New `ChlStackedFallbackTest` measures the painted pixels for BRAM, FLNT and NIAG: unmirrored and mirrored, with and without the power-play stripe, Standard and Large, both rows. Measured: disc 43 to 44 px, gap to the stripe **12.8 to 14.0**, gap to the code ink **16.8 to 19.0** design px (least: 12.8 and 16.8); all ≥ 6. |
| **S2 / R3** negative cache | `CardLogoCache.markFailed` / `recentlyFailed`: per URL, 128 entries (LRU), 6 h, process lifetime; CHL cards only. Tested for expiry and the bound. |
| **L1** league decides | `coilLogoFetch(loader, context, chlLeague)`: the cards pass the game's league; the bug's hook (URL only) still recognises the CHL host in any spelling (case, port, userinfo), all of which are then validated and rejected unless exact. |
| **L2** fake panel | The fake panel's `{origin}/leaguestat/{client}/logos/{id}.png` is a valid CHL logo URL and takes the alpha check, so WEN's fallback shows on the emulator. |
| **L3** byte cap | Not done: Coil's fetcher offers no hook within the WP-5 files. Decoded memory stays bounded by subsampling. |
| **L5** capsule cache | Two entries (one per team); a hit allocates nothing. |
| **L6** late focus pull | Any key press other than OK cancels a pending pull to the sub-chip. Tested. |
| **L7** tests | Back on a sub-chip; RTL (the row reads right to left on one line). |
| **L8** grep | Also fails on a code compared with a league's name (`==`, `!=`, `equals`, either order). Main sources have none. |

Hash gate: `sports-tab/sports-tab.png` legitimately changed (the third chip); its line in `sports-hashes.txt` is updated and
noted in the file header. Every other hash matches.
