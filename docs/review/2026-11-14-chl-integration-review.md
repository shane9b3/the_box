# CHL leagues: final integration review (`chl` vs `main`, 2026-11-14)

Reviewer: independent final pass (Opus), on `chl` at `7695115` (worktree `chl-int`, clean). Scope: the whole branch
(122 files, +16,303 / −265), with the focus on the seams the seven package reviews could not see. No source edited.

## Verdict

**Ready to merge to `main` as an unreleased feature.** No Blocker found. NHL and NFL behaviour is unchanged on every
shared path I traced, and every Blocker and Should-fix from the seven reviews landed and survived the automatic merges.
**S1 (a "Time TBD" game cannot be followed) and S2 (the negative logo cache keeps transient failures for 6 h) must be
fixed before a release ships the CHL.** `main` has no feature flag, so the next release tag after this merge ships it.

## Blockers

None.

## Should-fix

**S1. A followed "Time TBD" game is dropped at once, without a word.**
- Where: `ScoreBugMachine.kt:930-935` (`expired()`, `startUtcMs + HARD_EXPIRY_MS`) together with `HockeyTechMapper.kt:216-220`.
  For `time_tbd == "1"` the mapper sets `startUtcMs` to local midnight of the game day.
- `ScoreBugMachine.kt:201` copies the snapshot's start, but the board and the facts both keep the midnight placeholder,
  so the follow never learns a real start.
- Scenario: at 14:00 Toronto time the user follows an OHL "Time TBD" game (common for "if necessary" playoff games).
  1. `machine.follow()` calls `step()`. `expired()` is true, because 14:00 is past midnight + 8 h.
  2. The `step()` path at `ScoreBugMachine.kt:249-253` resets the machine with no `Removed` event.
  3. The user sees the "Following LDN – KIT" toast, then the follow disappears.
- Even a morning follow expires at 08:00 local.
- No test covers following a TBD game: the source tests check board mode only, and `ScoreBugMachineChl*` /
  `ScoreRepositoryChlTest` have no TBD case.
- Suggested fix (smallest): for a `startTbd` game, measure the hard expiry from the end of the league day, not from the
  placeholder. Options:
  - The mapper sets the placeholder to the next 05:00 rollover in the reference zone, since the design already calls it
    "a placeholder for ordering and expiry". This also stops the pre-wake from arming at midnight.
  - Or `FollowedGame` carries `startTbd`, and the machine uses `max(startUtcMs, followWall) + HARD_EXPIRY_MS` for it.
- Add a machine test (follow a TBD game at 14:00 local and expect it still followed after 4 h) and a repository test.

**S2. The CHL negative logo cache treats any failure as a 6 h failure.**
- Where: `GameCard.kt:213-217`. `bmp == null` covers a Coil `ErrorResult` from no network, a timeout or a cancelled
  decode, not only a 404 or the no-alpha check. That null result calls `CardLogoCache.markFailed(url)` for
  `FAILED_TTL_MS` = 6 h.
- Scenario: the box wakes and the Sports tab opens before Wi-Fi is up, so every CHL card logo fails. All 61 teams keep
  the code plate for 6 h on a healthy network.
- Review S2 asked for a cap on repeated 404s, not on transient errors.
- Suggested fix:
  - Mark failed only when the result is an `ErrorResult` whose throwable is an HTTP 4xx (Coil's `HttpException` with
    `response.code` in 400..499) or the `ChlLogoCheck` `IllegalStateException`.
  - Or use a short TTL (for example 5 min) for anything else.

**S3. Design §1.6 rows 12 and 37 contradict the code on REFUSED.**
- Where: `docs/design/m6-chl-leagues.md:249` and `:276`. Both still say REFUSED waits `BACKOFF_MAX_MS` "whatever
  Retry-After says".
- The code waits `max(300 s, Retry-After)`, with up to 3,600 s honoured on REFUSED only (lead ruling S1):
  `ScoreBugMachine.kt:234-236`, `ScoreRepositoryImpl.kt:552-554`, `HockeyTechScoreSource.clampRetryAfter`.
- §2.6 (`:490-492`) gives the right rule, but in a sentence that first repeats the old wording. WP-2 L3 asked for these
  rows to be updated; they were not.
- Scenario: a later pass "fixes" the code back to a flat 300 s from row 12, against the ruling and
  `refusedIsRetriedAtThreeHundredSecondsOrLaterNeverFaster`.
- Fix: rewrite both rows and the §2.6 sentence.

## Low

- **L1. The refused card hides which game it is** (`GameCard.kt:157-165`; the implementer flagged this).
  - The 128 px of team rows become four lines of `sports_failed_refused`. The header keeps only the state and the chip, so
    a focused refused card does not say which teams it is. The content description still names them.
  - The board data under it is often still valid: when only Firebase refuses, `lscluster` still answers, and the overlay
    falls back to the plain board.
  - Proposal with existing strings only, inside the same 128 px:
    - one 40 px row with the matchup, `sports_card_live_line` ("%1$s · %2$s") filled with `cardName(away)` and
      `cardName(home)`, 32/600;
    - then `sports_failed_refused` at 24 px, `maxLines = 3` (88 px), in `GuideUi.Warn`.
    - The 128 px is the two 60 px rows plus the 8 px gap; 40 + 88 = 128, so the card height is unchanged.
  - Second choice: keep the away row, and put the reason in the home row's place at `maxLines = 2`. At 528 px this may
    ellipsize the "NHL and NFL scores still work" sentence, so the first option is better.
- **L2. `followFailure` survives a network loss.** After a REFUSED answer, going offline stops the polls (`isOnline`
  gate, `ScoreRepositoryImpl.kt:365`), so the card and the sheet keep saying "stopped letting this app in" until the next
  answer. The text is still true (the last answer was a refusal), so this is cosmetic. Optional fix: clear the flag in
  `Input.Online(false)`, or let `SportsText.failure` prefer OFFLINE.
- **L3. The logo fetch now hops threads for NHL and NFL logos too.**
  - `coilLogoFetch` (`TeamLogo.kt:33-44`) runs `withContext(Dispatchers.IO) { ChlLogoCheck.overrideOrigin(context) }`
    once per `LogoFetch` instance.
  - `LogoPlate` builds a new instance for every card logo, so every NHL and NFL card logo gets one IO hop and, in a debug
    build, one small file read.
  - The request and the pixels are unchanged (the hash gate agrees). Optional fix: cache the origin in a process-wide
    `lazy`.
- **L4. The release-build guard is checked two ways.** `ChlLogoCheck.overrideOrigin` uses
  `ApplicationInfo.FLAG_DEBUGGABLE`; the data sources use `:data`'s `BuildConfig.DEBUG`. Both are false in release
  (debuggable is not forced on). Consider one shared check so they cannot drift.
- **L5. The overlay can run for an hour after midnight for a TBD game.** `needsOverlay` (`HockeyTechScoreSource.kt:358`)
  counts a TBD game's midnight placeholder as "starting". The CHL day already rolls over at 05:00, so this costs one or
  two unneeded overlay queries an hour, from 05:00 to 06:00 only. The S1 fix removes it.
- **L6. Wording in `tools/chl/pp_replay.py`.** `main()` still calls `live` "the S8 live gate"; the gate is
  `ChlLiveGateTest` (the docstring and the output already say so).
- **L7. Downgrade after any CHL follow.** `meters` keeps CHL keys after the follow ends, so a v0.1.7 downgrade loses the
  whole `sports_follow` document even with no CHL game followed, including the NHL/NFL meters and the poll phase. This is
  documented ("followed or measured", §1.4) and accepted; noted so nobody reads "followed" alone.

## Notes: what was traced and found sound

**NHL and NFL regressions (item 1):**
- `ScoreBugMachine`:
  - `fast()` is split into `timed()` (NFL and CHL) and `nfl()`. For the NFL both equal the old `fast()`; for the NHL
    `timed()` is false, so the bucket path is unchanged.
  - The pre-game branch `timed() && !nfl()` reaches the CHL only.
  - `olderNfl` lost its league gate, but it is called only under `nfl()`, and `prev.id == s.id` implies the NFL.
  - REFUSED is never produced by the NHL or ESPN sources.
  - The hold (`ppUnknownSince`, `expireHold`, `holdEndsAt`) is inert unless a snapshot has `powerPlayUnknown`, which is
    always false for the NHL and NFL. `goalWasPp` gets `p.powerPlay` for them, as before.
  - `rewindHold` on a known entry is the old `lastShownPp = gatedPp(q.snap)`.
  - The `league` added to `ScoreBugView` is NHL on the NHL path, and the NFL view still sets NFL.
- `ScoreRepositoryImpl`:
  - `sourceOf` no longer falls back to the first source; all five leagues are bound in `SportsModule`.
  - The 429 slow-down is the same for the NFL; `defaultEndpoint` is the same for the NFL and NHL.
  - The day policy, demand, the follow lifecycle and `persist` are unchanged except for the CHL branches and the new
    `followFailure` clear.
- `PollMeter`: `effectiveS` and `bytesPerPoll` give the same values for the NHL and NFL.
- Placement: `when (league.sport)` is the same for the NHL and NFL.
- Sports tab:
  - The NHL and NFL chips keep their tags.
  - Up from the day chips goes to the group chip.
  - The NFL week rules now use `group == NFL`, which is the same set.
  - The NHL empty and credit strings are unchanged.
  - The new `nowUtcMs` parameter sits before `easternToday`, so the trailing lambda still binds as before.
- `TeamLogo`, `GameCard`: the NHL and NFL logo requests use the same transformations; the negative cache is CHL-only.
- Painters: `GoalPainter.begin` and `StackedGoalPainter.begin` now set the badge text size and offset on every begin. For
  a code of 3 characters or fewer this is 24 px and the same `"M"`-bounds offset as the old `init`. It allocates one
  `Rect` per goal, which is negligible.
- `PlayerStateMachineImpl`:
  - The League row cycles NHL → NFL → CHL → NHL. This changes the NFL step (it used to toggle back to the NHL), as
    designed in §1.6 #33.
  - The Style row now shows for any hockey league.
  - The refused row is de-duplicated against a refused day row of the same league.
  - `PlayerText.choice`'s `league` override of `ScoreFailed` looked like a wrong-league bug. It is not: `PickerSheet`
    never passes `league`, so `c.league`, the followed league, is used.
- `WpaPages`: the NHL and NFL interval text is byte-identical (the extra format argument is ignored); a write goes to the
  group's own field.
- Prefs and backup: `chlLeague` and `chlIntervalS` are defaulted and not encoded at their defaults, so an NHL-only
  document is byte-identical to v0.1.7's.

**Seams (item 2):**
- **The follow lifecycle:**
  - Pre-game: board mode until `start − 120 s`, then `PRE_POLL_MS` from completion.
  - Live: six conditional nodes, three in parallel.
  - Unknown power play: held for 30 s or less on the anchors, ends by time, a PP goal drops the hold.
  - Goal, intermission (`timed()` breaks at 2 × live), OT (the board is re-read for the OT length).
  - Shootout: the WHL and QMJHL ask for `shootouts`.
  - Final: `finalSeenUtcMs`, then expiry.
- **Failure paths:**
  - A failed optional node keeps its last body; a failed penalties or goals node means unknown.
  - A failed required node, or Firebase 401/403, makes the poll REFUSED.
  - A refused key fails board mode and the day list. A live follow keeps working on cached facts until a restart.
  - A null `runningclock` goes to the S5 board decision.
- **App restart mid-game:** `start()` pushes the interval before `machine.follow`, so `liveMs` is never null for the CHL.
  The source reloads the facts from the start date's board.
- **League switch mid-follow:** the follow keeps polling its own league; `dayRefused` is per league.
- **Sports turned off:** the follow is removed, which clears `followFailure`.
- **`followFailure`:**
  - Set and cleared only on the repository actor.
  - Cleared by the next answer (`Ok` or `NotFound` give null) and by any follow change in `persist`. That covers
    stopping, another game, expiry and Sports off.
  - Applied only to the followed card id, so it never shows on another game.
  - A REFUSED day state wins on the tab, and the sheet does not show it twice.
- **Day list vs bug:** the card shows the overlay, or the plain board up to 240 s old, while the bug may be delayed by
  the user's delay. This is the same class of disagreement as the NHL's and is accepted.

**Security and privacy (item 4):**
- Each of the three keys is found in exactly one file at HEAD (`HockeyTechEndpoints.kt`), by `git grep` and by a
  working-tree grep. History adds them only in that file. Key values were never printed.
- Logging: `logState` emits `{league} {kind} → {state}` plus an exception class only. `endpoints.toString()` prints hosts.
  There is no logging interceptor on `@ApiClient`.
- The debug override is read under `BuildConfig.DEBUG` (data) or `FLAG_DEBUGGABLE` (UI). The relaxed logo form is
  accepted only for the override origin.
- No manifest or permission change; no telemetry.
- `.gitignore` covers `tools/chl/recordings/`.
- Fixtures: no key, and no e-mail or personal string (grep). `record_live.py` takes the key from an environment variable
  or a file only, and redacts it.

**Resources (item 5):**
- Per live poll: up to six small bodies, often 304s with no body, one JSON parse each, and the mapper and
  `ChlPowerPlay` on cached trees.
- Held per follow: the six last bodies (128 KB cap each).
- Caches are bounded: boards 3, facts 256, seasons 8, logo rejects 256, negative logos 128, and the Stacked pair cache at
  slots² per league.
- Connections: the shared client's pool, three at a time.
- CHL polling stops exactly when NHL polling stops. The same `followDemand`, `isOnline` and `dayDemand == NONE` gates
  apply, the source has no timers of its own, and the hold adds at most one wake-up.

**Backward compatibility (item 6):** `ChlSportsCompatTest` covers the §1.4 table against the store's own JSON;
`RoomSettingsStore` is unchanged from `main`, which is v0.1.7 plus docs and fixtures. The documented behaviour holds.

**Docs (item 7):**
- The README and master plan say "integrated" with an honest open list: emulator pass, one live recording per league,
  the S8 threshold, the 61 colour pairs, the NIAG/CAP logos, API 25 logos.
- The decision record says no more than what was done.
- The only inaccuracy is S3.

**Fix-pass verification (item 3), delegated to a sub-reviewer and spot-checked:**
- Every Blocker and Should-fix of the design review, commit 0, WP-1, WP-2, WP-3 and WP-4/WP-5 is at HEAD with its tests.
- The merges dropped no hunk: each merge's first-parent diffstat equals its side branch's.
- `ScoreBugMachine.kt` and its CHL tests have not changed since the WP-2 fix pass.
- `pp_replay.py` has only gained additions since the WP-1 fix.
- Declared not done and unchanged: WP-5 L3 (byte cap) and WP-3 L9 (a note).

## Still unverified: the emulator pass and the game-night gate (item 8)

**Emulator (fake panel, API 30 TV; nothing has run on an emulator or a TV yet):**
1. Sports tab:
   - `NHL · NFL · CHL` chips; OK on CHL moves focus to the remembered sub-chip.
   - Left and Right walk the whole row; Up from the day chips lands on the sub-chip.
   - Any key before the sub-switch appears cancels the focus pull.
2. A CHL follow through every scenario of `sports_chl.py`: pre-game, live, PP, unknown hold, PP goal, intermission, OT
   3-on-3, shootout, final, expiry. Check each on the Pill and the Stacked bug, Standard and Large.
3. Refused key, then refused Firebase:
   - the card's refusal text and how it reads (L1);
   - the sheet row;
   - the 300 s retry, with Retry and reopening the tab not hurrying it;
   - recovery clears the card.
4. One node failing, a null `runningclock`, and the app killed and relaunched mid-game: the follow resumes, the bug
   returns.
5. A league switch mid-follow (tab and sheet); Sports turned off mid-follow.
6. **A "Time TBD" game followed in the afternoon** (S1; expected to fail today).
7. Logos:
   - the fake `/leaguestat/` logos;
   - the no-alpha logo falls back to the code;
   - the 4-letter capsule (BRAM) on the Pill and the stripe disc on the Stacked bug;
   - logos fail with the network off, then the network returns (S2; expected to stay plates today).
8. Settings → Sports: the "CHL score updates" row, slowed note, meter after a CHL follow; quick-bar League and CHL league
   rows.
9. Background: home button and screen off with a CHL follow give zero requests (the fake panel's log); the same with
   the Sports tab open.
10. A release build pointed at nothing gets no override: put a `debug/sports_base` file in place and confirm it is
    ignored.

**Game night (real HockeyTech, real hardware):**
1. One full recorded live game per league through `CHL_LIVE_RECORDINGS=… ChlLiveGateTest`. The lead still has to set the
   S8 threshold (O1).
2. The derived power play against each league's game centre, with every miss classified under §5.7; a live 3-on-3 OT
   power play; a live shootout per league.
3. Clock latency against a broadcast; Today overlay freshness; the real wire bytes per poll against `EST_WIRE_BYTES_CHL`.
4. Logos on the API 25 Fire TV (`.png` decode, alpha check); the NIAG and CAP logos.
5. Owner sign-off of the 61 colour pairs (contact sheet) and the `SAMPLED` lines.
6. Fire TV Stick: CPU and allocation over a 3 h follow at 5 s, and heap held after the follow ends.

## Verified by running vs read only

**Run on this host (macOS 15.0.1 x86_64, JDK 21.0.2):**
- The full gate:
  `./gradlew --max-workers=2 -q --continue lintAll :core:test :data:testDebugUnitTest :player:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`
  exited 0 in 8 min 39 s.
  - Lint: no new issues in any module; some baseline entries are now fixed.
  - Universal, arm64-v8a and armeabi-v7a debug APKs were built.
  - Counts from the XML results:

| Results folder | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `core/build/test-results/test` | 4,344 | 0 | 0 | 1 |
| `core/build/test-results/stateOnRealNavigator` | 366 | 0 | 0 | 0 |
| `data/build/test-results/testDebugUnitTest` | 713 | 0 | 0 | 4 (opt-in probes, e.g. `HockeyTechWireProbe`) |
| `player/build/test-results/testDebugUnitTest` | 532 | 0 | 0 | 0 |
| `ui/build/test-results/testDebugUnitTest` | 822 | 0 | 0 | 0 |
| `app/build/test-results/testDebugUnitTest` | 86 | 0 | 0 | 0 |

- `./gradlew :ui:sportsHashGate`: BUILD SUCCESSFUL in 6 min 12 s. "all 1260 NHL / NFL / Stacked screenshots hash equal
  on 'Mac OS X 15.0.1 x86_64 jdk 21.0.2'". This host matches the list's `# host:` line, so the gate really compared the
  hashes and did not skip. On any other host it skips with a message and does not fail, so CI does not enforce it.

**Read only:** everything in Notes and in the findings: code tracing, greps and git history. Nothing ran on an emulator
or a device, and no instrumented test ran. `ChlLiveGateTest` ran on the fake recordings only, inside `:core:test`.
