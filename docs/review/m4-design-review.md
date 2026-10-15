# M4 full-screen player design: adversarial review

Reviewer pass over `docs/design/m4-player-ux.md` (proposed 2026-10-15, 1,720 lines, read in full), done 2026-10-15 on `main` at `2182858`. Nothing in the design doc or the code was edited. Every fix below is text for the lead to apply.

Checked against: `CLAUDE.md`; `00-master-plan.md` §2–§4, §6, §8; `01-design-reference.md`; `review/product-review.md` §2.1–§2.4 (S8 and the overlay table), §3.7–§3.9, §4.4, §4.7, §4.12; `decisions/2026-10-14-settings-and-branding.md`; `design/m4-media3-ui-api-notes.md` (its [unverified] tags are treated as open); `design/m3-guide.md` sign-off, §0, §2.5–§2.13, §3.9, §3.13, §3.18, §7, §8; `design/m2-playback-core.md` §0, §2.3, §2.7, §3.1, §3.5, §3.10–§3.14, §4; `review/m2-code-review.md` and `review/m3-code-review.md` "Later" lists and test gaps; `research/protocols-m3u-xmltv-xtream.md` §2.6, §4.4; `research/tivimate-feature-audit.md`.

Code read:
- `player/.../api/PlaybackApi.kt:1-130`.
- `player/.../controller/PlaybackControllerImpl.kt`: `:225-600` (cycle, tune, `tuneArchive`, `startCycle`, `launchSession`, `runSession`), `:750-760` (`endSession`), `:878-1000` (`onPlayerState`, `onTracks`), `:1373-1500` (`fail`, `enrich`, pause, resume, stop), `:1541-1590`, `:1646-1700` (decoder override, `seekTo`, `position`).
- `player/.../broker/StreamBrokerRegistry.kt`, `core/.../playback/broker/BrokerCore.kt` (all).
- `player/.../resolve/StreamUrlResolverImpl.kt:60-90`, `core/.../playback/errors/ErrorLadderImpl.kt:365-372`.
- `core/.../catchup/CatchupUrlBuilderImpl.kt` (all), `core/.../model/TimeBase.kt` (all).
- `core/.../playback/PlaybackContracts.kt:100-180`.
- `core/.../guide/state/GuideStateMachineImpl.kt`: `:180-320`, `:486-660`, `:836-975`, `:1118-1290`, `:1580-1597`.
- `core/.../guide/GuideModel.kt:195-240`, `ui/.../guide/vm/TunedStateMapper.kt` (all).
- `core/.../input/{InputContracts,DefaultKeyMap}.kt` (all), `ui/.../input/KeyRouterImpl.kt:55-198`.
- `ui/.../root/{AppRoot,ShellHost}.kt`, `ui/.../root/HoleChrome.kt:1-60`, `ui/.../full/FullScreenHost.kt`.
- `core/.../epg/EpgContracts.kt:100-278`, `core/.../epg/store/EpgStoreImpl.kt:66-76`, `core/.../epg/store/GenerationHandle.kt` (all), `EpgReaderImpl.kt` connection use (grep).
- `data/.../db/dao/PlaylistDao.kt:30-74`, `data/.../repo/PlaylistRepository.kt:140-180`.
- `tools/fakepanel/fakepanel.py` timeshift routes and checks (grep, `:634-678`, `:828-870`, `:936-971`), `tools/fakepanel/catalog.py` (grep for correction and archive).

A second pass checked every `file:line` citation in the design. All but one are correct; the exceptions are in S9.

Line numbers for the design are `m4-player-ux.md:N`. No Gradle runs, no device.

## Summary

| | Count |
|---|---|
| A. Blockers | 4 |
| B. Should fix before commit 0 | 11 |
| C. Nice to have | 17 |
| D. Claims verified correct | 22 |
| E. Lead decisions (§7 items 1–18 plus 8 added) | 26 |

The core bets hold:

- a sibling player machine called from inside the guide machine's `act`, so there is still one key sink and one Back ladder;
- a pure planner plus a stateful executor for catch-up;
- one connection per request through the broker;
- open-ended continuation requests;
- `SubtitleView` as a sibling after `HoleChrome`.

The blockers are about what the user sees around a catch-up request, not about the architecture:

- **B1.** While probing, stepping back from archive lag, retrying mid-play or handing off to a continuation, the controller's own `Error` and `Connecting` states reach both machines unfiltered. The error card flashes, open layers close, and the pill reads "Connecting" every few minutes.
- **B2.** `ArchiveFrames.liveEdge` caps the edge with `min(0, correction)`. Combined with the builder's frame-mixed "not started" check, a pause or rewind on a channel with a **positive** `catchup-correction` resumes `correction` hours in the past, under a pill that says −00:20.
- **B3.** Seek completion is detected from signals that never fire: an in-buffer native seek emits no new controller state, and a minute-aligned re-request never lands "within 2 s of the target". The spinner and the preview get stuck.
- **B4.** In archive and time-shift, the quick menu is unreachable with five keys, so audio, subtitles, aspect, favourite and stream info are too. The reachability test starts only from live, so it passes anyway.

---

## A. Blockers

### B1. Controller states that belong to the archive session leak into both machines: error-card flashes, closed layers, "Connecting" on every hand-off

- **Where:** §0.6 (`m4-player-ux.md:19`), §3.1.3 last paragraph (`:998`), §3.2.3 pill table (`:1063-1070`), §3.8.2 (`:1230-1244`), §3.8.6 "the pill keeps its last value" (`:1270`), §3.10.1 forwarding table (`:1334`).
- **Problem (1): no filter exists.** §0.6 says intermediate `Error(CATCHUP_NOT_IN_ARCHIVE)` states "are hidden from both machines". The only mechanism the design specifies is `ArchiveStatus`. But the player VM forwards `controller.state` straight through `TunedStateMapper` as `Tuned` (`:1334`), and so does the guide VM (`GuideViewModel.kt:319-328`).
  - The player machine opens the ERROR layer "when `Tuned.phase == ERROR`" (`:998`), and that layer "closes every other layer except stats".
  - So a probe from `xc-path` to `xc-php` shows the CATCHUP_NOT_IN_ARCHIVE card, closes the scrub bar the user was holding, and then removes the card when the next `Connecting` arrives.
  - The mini player's compact card (`FullScreenHost.kt`, then `MiniPlayerOverlay`) flashes the same way.
- **Problem (2): ordering.** Even with a filter keyed on `ArchiveStatus.phase`, the player VM's collector and `ArchivePlaybackImpl`'s collector on `controller.state` are separate coroutines. The `Error` can reach the machine before the session has moved to PROBING. The builder also runs on IO (§2.6), so the gap is at least one dispatch.
- **Problem (3): same class, other phases.** Every lag step-back, mid-play retry, continuation (every ≤ 3 min on xc, `CatchupUrlBuilderImpl.kt:68`) and seek re-request is a new `startCycle`. Each one emits `Connecting` (`PlaybackControllerImpl.kt:470-476`).
  - Pill row 1 is "`tuned.phase == CONNECTING/BUFFERING` → CONNECTING" and is checked first (`:1065`). That contradicts §3.8.6's "the pill keeps its last value".
  - The M3 guide machine's suspect rule also fires on the raw state (`GuideStateMachineImpl.kt:1137-1142`). The design removes it, but nothing replaces the `Tuned` path that both VMs still use.
- **Effect:** visible error cards during normal catch-up start, a closed scrub bar mid-gesture, and the pill flipping LIVE/BEHIND → Connecting → BEHIND every few minutes during time-shift.
- **Fix:** one pure rule, used by both VMs, so both machines and the mini player see the same thing. Add the contract text in F2 and F3:
  - `ArchiveStatus.ownedTuneId` is the tuneId of the newest cycle the session issued. `ArchivePlaybackImpl` sets it, together with the next phase (PROBING / CONTINUING / SEEKING), **before** calling `tuneArchive`.
  - `TunedState.tuneId` is filled by `TunedStateMapper`.
  - `ArchiveVisibility.visible(tuned, status)` is a `:core` pure function. Both VMs forward `Tuned(ArchiveVisibility.visible(mapped, archive.status.value))` from `combine(controller.state, archive.status)`.
  - Invariant for WP-B: after any archive `Error` it does not act on, the session goes to FAILED in the same main-thread turn. An owned `Error` is therefore always either about to be replaced by a new request or reported as FAILED.
- **Test:** `ArchiveVisibilityTest` (table). `PlayerViewModelTest` scripts `Connecting → Error(CATCHUP_NOT_IN_ARCHIVE) → Connecting → Playing`, with `ArchiveStatus` lagging the `Error` by one emission, and asserts that `ui.layer` is never ERROR and the pill is never CONNECTING after first media. `GuideFullDelegationTest` asserts the same for `GuideUiState.tuned`. Before the fix, the first assertion fails on the second emission.
- **WPs:** commit 0 (contracts), B (ordering invariant), D (both VMs).

### B2. Positive `catchup-correction` breaks every live-anchored request: pause, RW and time-shift resume `correction` hours in the past

- **Where:** `ArchiveFrames.liveEdge` (`:684`), §3.8.1 (`:1224`), §3.8.7 (`:1274-1277`), §5.2 "liveEdge never beyond the panel's now" (`:1441`).
- **Problem.** The frames are A = archive (panel clock), P = programme = A + corr, and the builder subtracts corr (`TimeBase.kt:58-59`).
  - `liveEdge = deviceNow + skew + min(0, corr)`. For corr > 0 that is the panel's now **as a P value**, which converts back to A = panelNow − corr.
  - `pauseLive` records `pausedAtMs = liveEdge(now)` in P. The resume request's A-frame start is therefore `panelNow − corr`.
  - Example: corr = +2 h. Pause at panel 20:00:00, wait 20 s, resume. The request asks for 18:00 and the user watches content from two hours ago. The pill shows −00:00:20 because `behind = liveEdge − wall` is computed in the same wrong frame.
  - `rewindLive` and TIME_SHIFT → preview commit have the same fault.
- **Problem (2): the builder check is frame-mixed.** The correct edge is `deviceNow + skew + corr`; a live-anchored instant has no EPG component, so only the panel clock matters. But the builder rejects any `programmeStart` (P) later than `nowI` (A) as "the programme has not started yet" (`CatchupUrlBuilderImpl.kt:53-55`). With corr ≥ 30 s, the corrected rewind target would be refused.
  - That check is why the proposer capped the edge. The cap hides a builder bug and moves the error to the user.
- **Effect:** wrong content on every pause/RW on such channels, with a pill that says otherwise. The test row "liveEdge never beyond the panel's now in either frame" would pin the bug.
- **Fix:**
  - Replace `liveEdge` with F1: `deviceNowMs + skewMs + correctionMs`.
  - In `CatchupUrlBuilderImpl.build`, test "not started" on the **corrected** start. This is a commit-0 edit of an M1 file, so it is the lead's:

    ```kotlin
    // replaces CatchupUrlBuilderImpl.kt:55 (move it after correctedStart, :58)
    val correctedStartI = timeBase.copy(paddingBefore = Duration.ZERO).archiveStart(window.programmeStart, corr)
    if (correctedStartI.isAfter(nowI)) return CatchupBuild.Unsupported("the programme has not started yet")
    ```

  - §5.2's frames row becomes: "liveEdge − correction == device now + skew (the panel's now in the archive frame) for corr ∈ {−1.5 h, 0, +2 h}; a request planned from `liveEdge − 30 s` builds with `requestStart == panel now − 30 s` (aligned) for every sign."
  - EPG-anchored requests (Watch, From start, Search) are unchanged; they already pass raw programme times.
- **Test:** `ArchivePlannerTest` and `CatchupUrlBuilderImplTest` with corr = +2 h fail before the fix (request at 18:00 or `Unsupported`). On the panel side, add a fake-panel channel with `catchup-correction="+2"` (S11) and I-M4-13b: pause/resume on it, `timeshift_expect` = the pause instant.
- **Owner of the semantics question:** E24 (does any real provider use a positive correction to mean "the archive clock is behind", rather than "the EPG is ahead"?). The fix is right for the documented meaning (research §4.4: correction maps EPG times to archive times).

### B3. Seek completion is detected from signals that do not fire: stuck SEEKING and stuck previews

- **Where:** §3.8.4 last paragraph (`:1259`), §3.5.2 preview clearing (`:1145`), `ArchivePlayback.seekTo` (`:660`).
- **Problem (1): native seek inside the buffer.** "Native seeks set phase SEEKING until the next `Playing`/`Buffering → Playing` after `seekTo`."
  - `PlaybackControllerImpl.seekTo` (`:1662-1668`) changes no state.
  - An ExoPlayer seek resolved inside already-buffered data (forward, API notes §2.1 last bullet) can stay in `STATE_READY`.
  - `_state` then keeps an equal `Playing` value, the `StateFlow` emits nothing, and SEEKING never ends. The spinner stays on, and the planner keeps a phase that §3.2.3 maps to BEHIND.
- **Problem (2): re-request preview.** The machine clears `preview` "when the next `Archive` status shows `phase ∉ {SEEKING}` with `wall` within 2 s of the target". That condition is never true when:
  - the re-request is xc and minute-aligned on an unseekable file (`startOffsetMs` is ignored, API notes §2.1), so the media starts up to 59 s before the target;
  - the lag rule stepped back 60 s;
  - the target was clamped to the archive edge.
  
  The hollow preview stays drawn, and the next ←/→ steps from the stale preview (`(preview ?: playhead) ± step`) instead of the playhead.
- **Effect:** a scrub bar that looks permanently busy, and steps that jump from a point that is no longer playing.
- **Fix:** an explicit seek token (F2):
  - `ArchivePlayback.seekTo(programmeMs, seq)`.
  - `ArchiveStatus.settledSeq` echoes the last settled `seq`.
  - Native settle: at the first poll at least `SEEK_SETTLE_MIN_MS` (250) after `controller.seekTo` where the state is `Playing(paused = false)` and position has advanced by ≥ 200 ms since the previous poll, or at `SEEK_SETTLE_MAX_MS` (5,000).
  - Re-request settle: first media of the issued tuneId.
  - The machine clears `preview` and `seeking` when `status.settledSeq ≥` its own last issued seq, wherever the playhead landed.
  - `PlayerEffects.archiveSeek(programmeMs, seq)`.
- **Test:** `ArchivePlaybackImplTest` scripts a native seek with no state emission: phase must leave SEEKING within 5 s. `ScrubModelTest` scripts a re-request whose wall lands 45 s before the target: the preview must clear on `settledSeq`. Both fail before the fix.
- **WPs:** commit 0, A, B.

### B4. Five-key rule broken in archive and time-shift: the quick menu (audio, subtitles, aspect, favourite, stream info, decoder) is unreachable without long-press

- **Where:** §3.1.2 archive column (`:954-981`), §3.1.3 SCRUB rows (`:988-989`), §3.1.6 (`:1019-1029`).
- **Problem.**
  - On bare archive video: UP/DOWN zap, LEFT/RIGHT open the scrub bar, SELECT opens the channel list, BACK leaves.
  - In SCRUB: SELECT toggles pause or commits, UP jumps to live, DOWN goes to the chips. CHIPS only seek.
  - The info bar, whose now-programme OK is the only five-key route to the quick menu (`:1023`), is replaced by the scrub bar in archive (§0.8).
  - So with D-pad/OK/Back only, a user watching catch-up cannot change the audio language, turn on subtitles, change aspect, favourite the channel or see stream info. They have to jump to live first, which loses their place.
  - `PlayerFiveKeyReachabilityTest` searches only "from `enterFull(USER)` on a live archive-capable channel and on a live channel without archive". It counts `QuickItem.AUDIO` as reached on live and passes.
- **Effect:** a plan §2 / product §2.1 rule (also M3 S11) is broken in the mode where multi-language archives are most often watched, and the specified test cannot see it.
- **Fix:**
  - Give the chips row a middle **Options** chip that opens the quick menu (`ScrubState.chipFocused`: 0 = previous, 1 = options, 2 = next; DOWN focuses 1).
  - Replace the §3.1.6 paragraph with F6. The search starts from four states (live with archive, live without archive, archive, time-shift). For each start state, every `QuickItem` that applies **in that state** must be reachable from that state's bare video without passing through a state of another kind (no jump-to-live, no zap).
- **Test:** the amended reachability test fails before the fix (AUDIO is unreachable from the archive start state).
- **WPs:** A (state), D (chip drawing).

---

## B. Should fix before commit 0

### S1. Continuation snaps a shallow time-shift to live and silently drops what the user paused for

- **Where:** §3.8.5 (`:1263`), §3.8.4 rule 2 (`:1255`).
- **Problem.** Continuation uses the seek rule: "`liveEdge − wall ≤ LIVE_SNAP_MS` (30 s) → Live".
  - Pause live for 20 s and resume. The xc request is `⌈(now − S)/60⌉` = 1–2 min long (`CatchupUrlBuilderImpl.kt:68,:123`).
  - At its end, 3 s before, the user is still 20 s behind, so the planner goes live. They skip 20 s without being asked.
  - I-M4-13 asserts the pill right after resume, so it never reaches the continuation and passes.
- **Fix.** Keep 30 s for **user** seeks (snapping a scrub preview is wanted). For continuation, go live only when `liveEdge − wall ≤ CONTINUE_SNAP_MS` (5,000); otherwise re-request. Replacement text in F7. I-M4-13 runs for 150 s after resume and asserts the pill is still −00:20 ± 3 s and one continuation request appears in `timeshift_requests`.

### S2. Every hand-off resets per-cycle state the user set: unremembered aspect, track menu, an open picker, pause

- **Where:** §3.8.6 (`:1270`), §3.4.3 (`:1118`), §3.6.2 (`:1159`).
- **Problem.** `startCycle` resets `VideoInfo` to `aspectMode = settings.defaultAspect` and `TrackInfo()` on every cycle (`PlaybackControllerImpl.kt:454-455`), and a new `Cycle` has `paused = false` and no `decoderOverride`. Each continuation, re-request seek and probe therefore:
  - drops an aspect chosen with "Remember" unchecked, and FILL → FIT → FILL flickers until memory is applied;
  - closes an open AUDIO/SUBTITLES picker ("closes if the focused key vanished (a new tune)") and blanks the 5.1/CC glyphs;
  - starts playing after a seek committed while paused.
  
  The sticky rect (§3.6.2) keys on size only, so the aspect flicker is not covered either.
- **Fix:**
  - (a) `ArchivePlaybackImpl` re-applies the session's last explicit `setAspectMode(mode, remember = false)` after each owned `Connecting`.
  - (b) §3.4.3 becomes: "A picker closes only when the tuned identity changes; during an archive hand-off (`ArchiveVisibility` hand-off) it keeps its choices until the next `Tracks` with entries."
  - (c) `stickyVideo` also holds `aspectMode` across an owned hand-off.
  - (d) State the rule for a seek while paused: re-request seeks stay paused (`ArchivePlaybackImpl` calls `controller.pause()` at first media when the session was PAUSED). TiviMate stays paused.
- **Test:** `ArchivePlaybackImplTest`: "continuation keeps an unremembered aspect" and "re-request while paused stays paused".

### S3. Variant probing only triggers on 404/410; mid-play retry has no code filter; connection-limit on a re-request is not handled

- **Where:** §3.8.2 transition table (`:1240-1242`), §3.8.8 (`:1283-1288`).
- **Problem (1).** The ladder turns only 404/410 on archive into CATCHUP_NOT_IN_ARCHIVE (`ErrorLadderImpl.kt:370-371`). Panels that do not support a form commonly answer 400, 403 (→ FORBIDDEN and the user-agent ladder), 5xx, an HTML page, or an empty 200. `xc-php` on panels without `timeshift.php` is the usual case. Probing then never reaches the next variant, and the user gets a FORBIDDEN or SERVER_ERROR card for a format problem.
- **Problem (2).** "Error(other) after first media → re-request once" includes AUTH_REJECTED, ACCOUNT_EXPIRED and CONNECTION_LIMIT, where another open is wrong. `midPlayRetries` never resets, so the second token expiry in a three-hour film fails.
- **Problem (3).** With `max_connections = 1` and a lingering panel, every continuation and re-request seek is a new open during the linger window. 458/509 → CONNECTION_LIMIT → card.
- **Fix:** replace the `Error(other)` rows with:

  > | `Error(c)` before first media, `c ∈ {CATCHUP_NOT_IN_ARCHIVE, FORMAT_REFUSED, WEB_PAGE_INSTEAD, EMPTY_RESPONSE, UNSUPPORTED_SOURCE, SERVER_ERROR}` | `planner.onNotInArchive` (lag rule first, then next variant) |
  > | `Error(CONNECTION_LIMIT)` on a request issued while the previous one was still open < `ARCHIVE_LINGER_WAIT_MS` (5,000) ago | wait `ARCHIVE_LINGER_WAIT_MS`, re-request once (status CONTINUING, pill unchanged); then FAILED |
  > | `Error(c)` after first media, `c ∈ {STREAM_INTERRUPTED, NO_DATA_TIMEOUT, CONNECT_FAILED, EMPTY_RESPONSE, SERVER_ERROR, CATCHUP_NOT_IN_ARCHIVE}`, `midPlayRetries < 1` | re-request open-ended from the last `wallMs`; `midPlayRetries` resets after 5 min of PLAYING |
  > | any other `Error` | FAILED(PLAYBACK) at once (auth, account, limit-local, codec: another open cannot help, plan §3 "401 → no loop") |

  Fake-panel support: S11 item 2.

### S4. The info bar's first range can miss "now" entirely

- **Where:** §3.2.2 (`:1059`), `ProgrammeFeed.range` (`:774-780`).
- **Problem.** `range(now − 360, now + 720, limit = 24)` returns the **first** 24 programmes in start order. On a channel with ≤ 15-minute slots (news, kids, the synthetic 1-minute channel of I-M4-9), all 24 are in the past, so `nowIndex = −1`. The bar opens on "No information" or on a programme from six hours ago.
- **Fix.** Make the contract count-based around an anchor:

  ```kotlin
  /** Up to [before] programmes ending after [fromMin] and starting before [atMin], then the one airing at [atMin] and up to [after] later ones (display minutes); [nowIndex] indexes the airing one (−1 = gap). */
  suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int = 12, after: Int = 12): ProgrammeSlice
  data class ProgrammeSlice(val programmes: List<ProgrammeInfo>, val nowIndex: Int)
  ```

  `range` stays for paging further. `INFO_EDGE` paging requests `around(edgeProgramme.startMin, …)`.
- **Test:** `ProgrammeFeedImplTest` with 5-minute slots asserts `nowIndex ≥ 0`.

### S5. `KeyContext.PLAYER_PANEL` leaks a derived state into the persisted, user-remappable key map

- **Where:** §0.7 (`:20`), §2.9 (`:862`), §3.1.7 (`:1033`).
- **Problem.** `KeyMap.short/long/bindings(context, …)` is the contract M5's button-mapping screen edits ("each key can carry a short-press and a long-press action", decision note). A third context means:
  - every user binding has to be written twice, or `DefaultKeyMap`'s `if (context == GUIDE)` silently aliases it;
  - the M5 screen has to hide a context that is not a user concept;
  - a user long-press binding on LEFT/RIGHT in the player is silently ignored whenever a layer is open, without a rule saying so.
- **Fix.** Keep `KeyContext { GUIDE, PLAYER }`. Add one machine member, and have the router drop the long binding when it is set:

  ```kotlin
  // GuideStateMachine (M4, default false)
  /** (M4) A player layer is open or archive video is on screen: D-pad LEFT/RIGHT act on DOWN with auto-repeat (their long bindings are ignored). */
  val horizontalRepeat: Boolean get() = false
  ```

  ```kotlin
  // KeyRouterImpl, first DOWN (replaces the long lookup at :109)
  val long = keyMap.long(context, code)?.takeUnless { m.horizontalRepeat && (code == Keys.DPAD_LEFT || code == Keys.DPAD_RIGHT) }
  ```

  Store the decision per held key next to `heldContext`.
- **Test:** `DefaultKeyMapTest` stays two-context; a `KeyRouterImplTest` row covers "LEFT with `horizontalRepeat` repeats and never fires GROUP_PREV".

### S6. The zap-direction flip is incomplete

- **Where:** §7 item 3 (`:1556`), §3.1.2 (`:954-955`).
- **Problem.** M3 binds `MEDIA_NEXT → ZAP_DOWN` and `MEDIA_PREVIOUS → ZAP_UP` in PLAYER (`DefaultKeyMap.kt:29`, M3 H-L14), and `PAGE_UP → ZAP_UP` (`:27`), because ZAP_UP meant −1. Flipping CH+ to +1 alone makes ⏭ go to the previous channel and leaves PAGE_UP inconsistent with CH+.
- **Fix.** If E3 is accepted:
  - ZAP_UP = +1 everywhere;
  - `MEDIA_NEXT → ZAP_UP`, `MEDIA_PREVIOUS → ZAP_DOWN`, `PAGE_UP → ZAP_UP`;
  - "Invert channel direction" swaps only D-pad UP/DOWN.
  
  Add a `DefaultKeyMapTest` row: CH+, ⏭ and PAGE_UP all give the same delta.

### S7. Two triggers are missing: the passive bar after guide-originated tunes, and "OK on next sets a reminder"

- **Where:** §3.2.1 zap row (`:1051`), §3.1.3 INFO row (`:987`).
- **Problem (1).** The passive bar is promised for "number entry commit" and other tunes the player machine does not issue. Number entry commits through the **guide** machine (`tune(channel)`, `GuideStateMachineImpl.kt:948`), and so does a reminder "Switch now" in FULL (`:856-865`). The player machine only sees `Tuned`, and no rule says a new identity shows the bar.
- **Problem (2).** Product S8 says for the info bar "OK on next sets a reminder" (`product-review.md:279`). The design makes OK on a future programme a no-op: "Remind is a guide action, toast-free".
- **Fix:**
  - Add to §3.2.1: "`Tuned` whose `identityHash` differs from the previous one while in FULL and not owned by the archive session → passive bar for that channel, same timing as a zap."
  - Add `fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo)` to `PlayerEffects`. The VM routes it to the M3 reminder store and toasts REMINDER_SET / REMINDER_CANCELLED; reuse M3's `GuideToast`.
  - The INFO row's future case becomes: "future → `toggleReminder`".

### S8. Error-card rules contradict the table and offer buttons that loop or lose position

- **Where:** §3.7 (`:1172`, `:1180`, `:1187-1210`).
- **Problem (1).** The rule says RETRY is prepended "when the code's `defaultSteps` contain RETRY". FORMAT_REFUSED, DNS_FAILURE, TLS_FAILURE and UNSUPPORTED_SOURCE have no RETRY (`PlaybackContracts.kt:136,141,143,147`), yet the table shows "Retry (prepended)" for all four. WP-A and the `ErrorCardModelTest` author will build different cards. Retrying a DASH-only source or a TLS failure is a loop with no new information.
- **Problem (2).** "Software decoder" is offered when the channel already runs `SOFTWARE_PREFERRED`, and for an **audio** `CODEC_UNSUPPORTED`. Pressing it re-prepares into the same card.
- **Problem (3).** RETRY → `controller.retry()` on an archive error restarts the original request at its original `startOffsetMs`. `fail()` does not record `archiveResumeAtMs` (`PlaybackControllerImpl.kt:1373-1386`, `:1802-1803`), so the user is thrown back to the programme start.
- **Problem (4).** "Stop" (CONNECTION_LIMIT) leaves FULL with a stopped stream and no layer, pill or hint.
- **Fix.** Replace the "Buttons from steps" paragraph with F5, and the four "Retry (prepended)" cells with "Guide" (DNS keeps a Retry only if E decides; see E8).

### S9. Commit 0 does not compile as listed; two seams and one citation are wrong

- **Where:** §1.7 (`:140-147`), §2.9, §3.10.1 (`:1347`), §3.10.3 (`:1372`).
- **Problem (1).** Adding `ShellEvent.Message` breaks the exhaustive `when` in `AppRoot.kt:177-184` (WP-D's file). Adding three `GuideEvent` members breaks `GuideStateMachineImpl.dispatch` (`:199-222`, WP-A's file). Kotlin 2.4 rejects non-exhaustive `when` statements on sealed types. The rule "after commit 0 everything is green" therefore needs commit 0 to add no-op branches in both files, which the list does not say.
- **Problem (2).** `GuideEvent.StartFull` "uses the existing `pendingFull` path". `pendingFull` exists (`:1150`), but its only setter is in `onSearchClosed` (`:1206`), and `StartFull` exists nowhere. That is fine as a proposal, but the commit-0 stub must set `pendingFull = true`.
- **Problem (3).** Nothing specifies who sets `guideMachine.full`, or when relative to `StartFull`. If the guide VM's `startup()` tune reaches FULL before `MainActivity` attaches the player machine, `goFull` calls `full?.enterFull` on null, and FULL runs on the reduced `fullKey` with no zap.
- **Problem (4): citation.** `GuideViewModel.kt:309-322` is the window/generation/reminder collectors. `startup()` is at `:387-400` and the `Tuned` forwarding at `:319-328`.
- **Fix.** Add to §1.7:
  - item 7: no-op branches in `AppRoot.kt` (`ShellEvent.Message -> Unit`) and `GuideStateMachineImpl.dispatch` (`Archive`, `Player`, `StartFull -> Unit`);
  - item 8: `TunedState.tuneId`, `ArchiveStatus.ownedTuneId`/`settledSeq`, `ArchiveVisibility` (B1, B3);
  - item 9: the builder edit of B2;
  - item 10: `GuideViewModel.attachPlayer(machine)` called by `MainActivity` **before** `startup()`, and `goFull` calls `enterFull` on attach when the mode is already FULL.
  
  Fix the citation to `:387-400`.

### S10. Nothing proves the composed guide + player state has no dead ends

- **Where:** §5.1 (`:1428-1437`), §0.1 (`:14`).
- **Problem.** The player machine is enumerable on its own: layers × focus × flags, with `PlayerFixtures`, which answers attack 1. The **composition** is not:
  - guide overlay (Number, Exit hint/dialog) × reminder card × player layer × archive phase.
  
  `GuideFullDelegationTest` lists six hand-picked cases. M3's `stateOnRealNavigator` covers GUIDE. Three FULL properties are asserted nowhere:
  - "BACK from any composed FULL state reaches GUIDE in at most k presses";
  - "no key reaches the player while the card is modal";
  - "a mode change closes every player layer".
- **Fix.** Add `ComposedFullBackLadderTest`: the product of {no overlay, Number(preview), Exit hint, Exit dialog} × {card, no card} × every `PlayerLayer` × {live, archive} × `BackInPlayer`.
  - Assert BACK reaches `mode == GUIDE` (or the exit dialog) within 6 presses.
  - Assert that with the card shown, a key changes neither `PlayerUiState` nor the effects.
  - Assert that after `returnToGuide` the player's `ui.layer == NONE` and stats are off.

### S11. The fake panel and instrumented suite miss the cases above

- **Where:** §5.5 (`:1466-1489`), §5.6 (`:1493-1504`).
- **Add to §5.6:**
  1. a channel with `catchup-correction="+2"` and one with `-1.5` (B2), plus `server_info.timezone` ≠ UTC;
  2. `timeshift_reject_forms` with a configurable status (`400`, `403`, `500`, `200-html`, `200-empty`), not only 404 (S3);
  3. a "Content-Length present, Range ignored (200)" mode for timeshift (API notes §7 gap 5, the `bytesToSkip` path a native seek hits on real panels);
  4. an HLS **EVENT** timeshift playlist without ENDLIST for the `-now.m3u8` forms (gap 6), so §3.8.4's "re-request only" rule is exercised;
  5. `token_ttl` on timeshift URLs (mid-play expiry, S3).
- **Add to §5.5:**
  - I-M4-13b (B2);
  - I-M4-21: probe on a 400 rejection;
  - I-M4-22: native in-buffer seek ends SEEKING (B3);
  - I-M4-23: catch-up audio picker reached with five keys (B4).
  
  Run I-M4-13 for 150 s (S1). I-M4-10's linger variant must assert the refused-reopen behaviour of S3, not only count opens.

---

## C. Nice to have

- **N1. ProgrammeFeed is not independent of the guide's connection.**
  - `openReader()` returns an `EpgReaderImpl` over the generation's shared `GenerationHandle`, and `window`/`at`/`resolve` all run on `handle.guide` under one `ReentrantLock` (`GenerationHandle.kt:66`, `EpgReaderImpl.kt:33,54,104`).
  - So the feed's thread is legal (the `EpgReader` KDoc allows "other features open their own reader", `EpgContracts.kt:233-234`) and swap-safe (`tryRetain`). But it contends with the guide pager's lock and shares its statement cache.
  - Replace "its own `box-player-epg` thread and reader" in §0.11/§3.3.3 with "its own thread and reader handle; queries share the generation's guide connection and lock (`GenerationHandle.guide`), so each call must stay a single short `use` (≤ 2 ms)".
  - `SQLITE_BUSY` cannot arise on that read-only WAL connection except during a checkpoint; keeping the retry is harmless.
- **N2. Align in the archive frame.** `alignStart` floors the P-frame target, but the token is formatted from A = P − corr. A correction that is not a whole number of minutes (`0.0083` h) or the builder's edge-margin branch (`s = windowStart + 60`, not minute-aligned, `CatchupUrlBuilderImpl.kt:63`) unaligns it again. Use `programmeOf(floorToMinute(archiveOf(t)))`, and make `ArchiveFrames.correctionMs` match `TimeBase`'s rounding: `Duration.ofSeconds((h * 3600).roundToLong()).toMillis()`.
- **N3. The pause point ignores live latency.** On screen, live is `bufferedMs` plus the panel's own delay behind the panel's now. Record `pausedAtMs = liveEdge(now) − position().bufferedMs` (clamped at 0), or resume skips several seconds.
- **N4. Sticky video size never releases** for an audio-only (radio) channel or a failed tune, because `renderedFirstFrames` never advances. Release it on first media (`Playing`) or `Error` as well.
- **N5. Subtitle padding units.** `(1080 − bottomBarTop + 16) / videoRectHeight` mixes design px and device px and assumes the video reaches the window bottom. Use `((videoRect.bottom − barTopPx + 16·scale) / videoRect.height).coerceIn(0.08f, 0.5f)` in device px.
- **N6. Raw programme end.** `ProgrammeInfo` has no raw end. State `rawEndMin = rawStartMin + (endMin − startMin)` wherever §3.5/§3.8 says `rawEnd`, so WP-A and WP-B do not invent two.
- **N7. SCREEN_OFF is built but unreachable in M4:** no default key, and it is not a `QuickItem`, despite §0.13 ("reachable through the quick menu"). Either add `QuickItem.SCREEN_OFF` (low cost, and useful for radio channels) or move the layer to M5 with the button-mapping screen.
- **N8. Reminder card position in FULL.** At bottom-end 96, it overlaps the info/scrub bar (y 820–1040) and the quick-menu sheet (x ≥ 1376). Lift it to bottom 276 while a bottom bar shows, and move it left of the sheet.
- **N9. Scrub steps.** The step table deviates from product S8 ("±10 s, hold: 30 s, then 2 min"). See E21.
- **N10. §7.1 misses M3 H-L12.** "Cached per identity like `GuideViewModel.channelOf`" (§3.6.1, §3.10.1) copies the pattern that caches failed lookups. Cache only hits.
- **N11. M2 E-L18 is touched.** `SubtitleLayer` places `videoRect` the same way as `VideoLayer`, relative to its parent (`VideoLayer.kt:34`). It must be a direct child of the root `Box`. Say so in §3.6.3.
- **N12. Stop leaves a dead screen.** After "Stop" (S8 problem 4) show a centred hint: "Stopped to free the connection. Press OK to play again." SELECT on a Stopped(USER) bare video → `retry()`.
- **N13. Lifecycle stops.** `Stopped(BACKGROUND/STANDBY)` during an archive session has no row in §3.8.2. Map it to PAUSED with polling off. The controller's `resumeAfterLifecycle` restarts the same request at `archiveResumeAtMs` (`:1802-1803`), so `ownedTuneId` must follow tuneIds of the same spec.
- **N14. Zap diagnostics.** Archive cycles are logged as TUNE and zap records (`PlaybackControllerImpl.kt:458`). Benchmark (d) must filter `isArchive`, or continuations skew zap P50.
- **N15. `SubtitleView` and focus.** Set `isFocusable = false`, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS` and `importantForAccessibility = NO` in the factory, so the classic `View` can never take focus from the key sink (B1 of M3).
- **N16. DST.** When the panel zone is only known as `serverOffset` (`TimeBase.kt:48`), xc tokens for archive on the other side of a DST change are an hour off for up to 7 days. Log `PanelZoneUnknown`-style diagnostics when `effectiveTokenZone` is a fixed offset and the archive span crosses a transition of the device zone. M5 shows it in channel info.
- **N17. A failed launch inside FULL.** `PlayerEffectsImpl` toasts CATCHUP_FAILED for a synchronous `Failed`, but the info bar has already moved its focus to the past programme. Restore focus to now on `Failed`.

---

## D. Verified correct

1. **Citations.** Every `file:line` in §0–§8 and Appendix A.1 was checked. All are right except `GuideViewModel.kt:309-322` (S9). Four are right but describe proposed members (`StartFull`, `keyContext`, PAUSED/BEHIND pills, "LRU 8" outside the cited range), which is acceptable for a proposal.
2. **Root tree.** `VideoLayer → AmbientBackground → HoleChrome → key sink { screens, card, toast }` (`AppRoot.kt:199-227`) matches M3 §3.13.1. Inserting `SubtitleLayer` after `HoleChrome` keeps every overlay a later sibling. No ancestor of `VideoLayer` gains a layer, clip or alpha. `HoleChrome`'s own `graphicsLayer` is on a sibling (`HoleChrome.kt:59`), which is allowed.
3. **Broker.** One request means one lease and never two. `tuneArchive → startCycle → launchSession` calls `endSession()` (transport cancelled, lease released) before the new session acquires (`PlaybackControllerImpl.kt:470-476`, `:754-760`). `acquire` pre-empts every other tuneId's PLAYBACK lease (`BrokerCore.kt` `acquire`). The finite-TS tail/head reopens attach sequentially to the same lease (API notes §2.1). Live → archive (pause resume, RW, start over) and archive → live (`jumpToLive`, `LiveNotReady`) both go through `startCycle`, so the plan §3 broker rule holds client-side. The remaining risk is server-side linger (S3).
4. **What the user sees in the gap.** The last frame (`player.stop()` without clearing the surface, `:754-760`) plus the sticky rect (§3.6.2), under the B1 rule that keeps the pill.
5. **Fast 404.** Archive 404/410 fails at once as CATCHUP_NOT_IN_ARCHIVE through ladder L13 (`ErrorLadderImpl.kt:370-371`), so 404-based probing costs one request per variant.
6. **`VideoInfo` reset.** `VideoInfo` resets to size 0 on every tune while keeping `renderedFirstFrames` (`:454`), so the sticky-size reasoning of §0.14 is right.
7. **Archive end.** `STATE_ENDED` on archive becomes `Playing(paused = true)` and is indistinguishable from a user pause (`:910-915`). `Playing.ended` is needed.
8. **Previous channel.** `previousIdentity` changes only when the identity changes (`:441-444`), so archive hand-offs on one channel keep the channel list's Previous row stable.
9. **Live pause.** `pause()` releases the live connection after `LIVE_PAUSE_RELEASE_MS` and reports `Stopped(LIVE_PAUSE_TIMEOUT, resumable)` (`:1412-1435`, `:1861`). Archive pause is real.
10. **Error codes.** All 25 `PlaybackErrorCode`s appear in §3.7 (`PlaybackContracts.kt:127-151`), apart from the button-rule conflict in S8.
11. **Builder facts.** The open xc cap (`:68`), `nativeSeekRange` null for xc `.ts` (`:129`), minute tokens with second-resolution `requestStart` (`:62,:88`), and the winner moved first (`:23-41`).
12. **Probe winner.** The winner gap is real: the resolver passes `winner = null` (`StreamUrlResolverImpl.kt:83`), `PlaylistDao` has no setter and uses column-targeted updates (`PlaylistDao.kt:36-73`), and the column exists. No migration is needed.
13. **`ProgrammeFeed` threading.** It is legal and generation-safe (N1 corrects the independence claim). It does not violate M3 decision 6: the `EpgReader` contract allows readers per feature.
14. **Suspect marking.** The M3 suspect rule fires on the first archive error (`GuideStateMachineImpl.kt:1137-1142`). The design is right to move it.
15. **Long bindings.** Keys with a long binding fire on UP and never repeat (`KeyGestureDetectorImpl.kt:20-46`, `DefaultKeyMap.kt:21`). Scrub and list holds therefore need the long ←/→ suppressed (the mechanism is S5).
16. **`heldContext`.** It is fixed per key at its first DOWN (`KeyRouterImpl.kt:67,114,118`), so a context change mid-hold cannot turn a held key into a long press.
17. **Dispatch order.** Reminder card → overlay → (player) → global → `fullKey` is consistent with `act` (`:235-257`). Number entry's "any other key commits then runs" (`:913`) gives the right behaviour over an open channel list.
18. **Subtitles.** `media3-ui-compose` 1.11.1 has no cue rendering, and the repo has no cue path (API notes §1.2). `SubtitleView` in an `AndroidView` sibling is the cheapest correct route.
19. **Settings defaults.** `PlayerSettings` matches product §4.4/§4.7: info bar 3/5/8 s with default 5, number wait 1/1.5/2.5 s with default 1.5, Up/Down = change channel, invert off, Back = guide, on launch = guide with last. `PlayerSettingsSource` has the same shape as `GuideSettingsSource` (`GuideFeedContracts.kt:133-136`), so M5 can persist and edit it without a contract change.
20. **Screen-off viewport.** Viewport HIDDEN keeps audio and the connection and disables only the video track (`:1548-1569`). The screen-off cost claim is right.
21. **Aspect ratios.** The ratio aspect modes need no controller work (`VideoRectCalculator.kt:20-47`).
22. **§7.1 dispositions.** The M2 and M3 "Later" dispositions are accurate, with two omissions: H-L12 (N10) and E-L18 (N11). M2 E-L2/E-L7/E-L8/N-L9 and test gap 7 are what the design says.

---

## E. Lead decisions needed

Design §7 items:

1. **Subtitle rendering.** (a) `SubtitleView`. DVB bitmap layout and 608 windows are mature, and the cost is one artifact that is code only after R8. Add N15's focus settings. Keep (b) as the named fallback, decided before WP-D starts.
2. **Sibling player machine.** Accept. The seam is sound for Back, number entry and the card (D17). Two conditions: S5 replaces the third `KeyContext`, and S10 adds the composed proof. The alternative, a 3,000-line machine, would re-baseline 227 navigator tests.
3. **Zap direction.** CH+/⏭/PAGE_UP = next, the higher number (+1). This is universal TV behaviour and costs nothing to match. D-pad Up = +1 by default, inverted by the setting, with S6's binding fixes. The parity session confirms only the D-pad default; CH± is not in question.
4. **←/→ scrub on bare archive video.** Accept, together with B4's Options chip. Otherwise catch-up has no five-key route to the quick menu.
5. **Xtream open-ended duration.** Not in M4. Accept, plus S1: with a 5 s continuation snap the short-file re-requests are a freeze every 1–3 min, not a lost time-shift. Make D10 the trigger, with the I-M4-13 150 s run as the measure.
6. **Gapless hand-off.** Defer to v1.1. With B1 and S2 fixed, a re-request costs only a sub-second frozen frame.
7. **Live pause resumes from the archive after ≥ 3 s.** Accept with B2 and N3. Without B2 it is wrong on positive-correction channels.
8. **Buttons only for M4 actions.** Accept, with F5. Decide DNS_FAILURE: I recommend **no** Retry (`defaultSteps` has none, and the text step "Use secure DNS in Settings" is the real fix). Keep the codes' own lists authoritative.
9. **The channel list does not change the guide's source.** Accept. It is TiviMate behaviour, and the guide keeps the user's place.
10. **OK on now in the info bar opens the quick menu.** Accept. Add S7's "OK on next sets a reminder", which the product requires.
11. **Subtitles in FULL only.** Accept. At 720 px they are unreadable and collide with the pills.
12. **Number entry stays the guide's.** Accept. One preview, one commit, one "No channel N" (D17).
13. **Persist the winner only after first media with a non-first variant.** Accept with S3's wider trigger set. A 400/403 rejection must count as a variant failure, a lag 404 must not.
14. **Speculative CEA-608 shown as "Captions (if broadcast)".** Accept. Hiding them would hide real descriptor-less captions. Sort them last, after real tracks.
15. **FULL consumes Play/Pause and FF/RW.** Accept. The voice/phone pause gap (§8 Q7) is acceptable for v1.
16. **Screen off.** Either add `QuickItem.SCREEN_OFF` in M4 or move the whole layer to M5 (N7). I recommend the quick-menu item: radio channels are its use and it is five lines.
17. **New default bindings.** Accept, with S6.
18. **Player settings in memory.** Accept (D19).

Added by this review:

19. **Archive visibility rule** (B1). Recommend that the archive session owns its errors and hand-off states, through `ArchiveVisibility`, for both machines and the mini player.
20. **Live edge and correction** (B2). Recommend the edge `now + skew + corr` and fixing the builder's not-started check in commit 0.
21. **Scrub step table.** Product says 10 s, 30 s held, then 2 min. The design uses 10/30/60/300 s. Recommend the product's 10/30/120 s plus the design's 300 s only after 10 s of holding: 3 h programmes need it, and 2 min steps take 90 presses.
22. **Continuation snap** (S1). Recommend 5 s for continuation and 30 s for user seeks.
23. **Quick-menu "Sources"** (product S8: "v1: server list"). The design drops it as v1.1, but plan §2 Resilience lists the backup server list as v1 and M2 already fails over. Recommend a read-only "Server: 2 of 3" line in Stream info for M4, and the picker in M5 with the backup editor. A "v1.1" label contradicts the product table.
24. **Meaning of positive `catchup-correction`.** Confirm with a real provider that EPG-to-archive is the only meaning (research §4.4) before M6.
25. **Five-key route to the quick menu in archive** (B4). Recommend the middle Options chip over making bare-archive SELECT open the quick menu. SELECT = channel list is product S8 in every mode.
26. **Re-request seek while paused** (S2 d). Recommend staying paused.

---

## F. Contract text to change

Each block replaces the cited text verbatim. Kotlin is normative as in §2.

### F1. §2.3 `ArchiveFrames.liveEdge` (replaces `m4-player-ux.md:683-684`)

```kotlin
    /**
     * The panel's now in the programme frame (archive-frame now + correction). Live-anchored instants (pause point,
     * RW and time-shift targets) are taken from it; the builder subtracts the correction again, so their requests start
     * at the panel's own clock. The builder's "not started" check compares the corrected start with the panel's now.
     */
    fun liveEdge(deviceNowMs: Long): Long = deviceNowMs + skewMs + correctionMs
```

### F2. §2.3 additions to `ArchiveStatus` and `ArchivePlayback`

```kotlin
// ArchiveStatus: new fields, last, with defaults
    /** tuneId of the newest controller cycle this session issued (set before tuneArchive); later tuneIds are ours too. */
    val ownedTuneId: Long = 0,
    /** The last seek sequence number whose seek has settled (native: playing again; re-request: first media). */
    val settledSeq: Long = 0,

// ArchivePlayback: seekTo gains the machine's sequence number
    /** [programmeMs] is clamped to [archiveFromMs, liveEdge]; within LIVE_SNAP_MS of the edge it goes live. [status].settledSeq reaches [seq] when the seek has settled, wherever the playhead landed. */
    fun seekTo(programmeMs: Long, seq: Long)

// PlayerEffects
    fun archiveSeek(programmeMs: Long, seq: Long)
    fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo)
```

`GuideModel.kt`: `TunedState` gains `val tuneId: Long = 0` and `val handoff: Boolean = false` (last fields). `TunedStateMapper` fills `tuneId` from `state.target?.tuneId`.

### F3. New `core/.../catchup/archive/ArchiveVisibility.kt` (commit 0, contract)

```kotlin
// Contract (M4 commit 0, review B1).
package app.thebox.core.catchup.archive

import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState

/** What both machines and the mini player show while an archive session owns playback (§3.8.2). Pure. */
object ArchiveVisibility {
    private val HANDOFF = setOf(ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)

    fun visible(tuned: TunedState, status: ArchiveStatus?): TunedState {
        if (status == null || status.phase == ArchivePhase.IDLE || status.phase == ArchivePhase.FAILED) return tuned
        if (!tuned.isArchive || tuned.identityHash != status.identityHash || tuned.tuneId < status.ownedTuneId) return tuned
        val waiting = tuned.phase == MiniPhase.ERROR || tuned.phase == MiniPhase.CONNECTING || tuned.phase == MiniPhase.BUFFERING
        if (!waiting) return tuned
        val handoff = status.phase in HANDOFF || (status.phase == ArchivePhase.PLAYING && tuned.phase == MiniPhase.ERROR)
        return tuned.copy(
            phase = if (handoff) MiniPhase.PLAYING else MiniPhase.CONNECTING,
            error = null,
            errorDetail = null,
            handoff = handoff,
        )
    }
}
```

The §3.1.3 sentence at `:998` becomes: "The error layer appears when the **visible** `Tuned.phase == ERROR` (`ArchiveVisibility`) or `status.phase == FAILED`, and closes every other layer except stats; it leaves when the visible phase leaves ERROR." The §3.2.3 pill table gains a first row: "`tuned.handoff` → the pill of the last non-hand-off state (BEHIND/CATCH-UP), `ScrubState.seeking = true`."

### F4. §2.4 `ProgrammeFeed`: add `around` and `ProgrammeSlice` (S4 text, verbatim).

### F5. §3.7 "Buttons from steps" (replaces `:1172`)

> **Buttons from steps** (`ErrorCardModel`): walk `error.actions` in order. A step whose M4 button **applies** adds it; the first step without one becomes `textStep`. A button applies unless:
> - TRY_SOFTWARE_DECODER: the channel already runs `SOFTWARE_PREFERRED`, or `detail.codec` starts with `audio/`;
> - TRY_OTHER_CATCHUP_FORMAT: no variant is left;
> - PREVIOUS_CHANNEL: the ladder already filtered it.
>
> Then always append GUIDE. RETRY appears only when the code's own `actions` contain it; no button is ever prepended. On an archive error, RETRY calls `ArchivePlayback` (re-request open-ended from the last `wallMs`), never `controller.retry()`. STOP calls `stopPlayback()` and then shows the stopped hint (N12).

### F6. §3.1.6 last paragraph (replaces `:1029`)

> `PlayerFiveKeyReachabilityTest` searches the state graph with the six keys only, from four start states: live with archive, live without archive, archive (`playProgramme`), and time-shift (`pauseLive` + resume). It asserts that **from each start state, without passing through a state of another kind** (no zap, no jump to live), every `PlayerLayer`, every `QuickItem` that applies in that kind of state, every `PickerKind`, `ScrubFocus`, the Previous row and the kind's effects (`jumpToLive`/`startOver`/`pauseLive`/`playProgramme`) are reached. It also asserts every `ErrorButton` that applies, with a scripted ERROR per code. In archive and time-shift the quick menu is reached through the scrub bar's Options chip (DOWN from the bar). Stated exclusions: the channel menu (long OK; favourite is in the quick menu) and number entry (digits).

### F7. §3.8.5 first bullet (replaces `:1263`)

> - `req.endMs != null` (fixed range) or the file has a duration: `remaining = durationMs − positionMs`. When `pos.ended` or `remaining ≤ CONTINUE_LEAD_MS` (3,000): if `live.liveEdgeMs − wall ≤ CONTINUE_SNAP_MS` (5,000) → Live, else `Rerequest(start = alignStart(variant, wall), stop = null, padBefore = false, target = wall)` (phase CONTINUING). `LIVE_SNAP_MS` (30 s) applies to user seeks only (§3.8.4); a continuation never drops content the user chose to be behind.

### F8. §3.1.3 SCRUB rows (replaces `:988-989` DOWN and CHIPS cells)

> SCRUB (focused, BAR) DOWN → `focus = CHIPS`, `chipFocused = 1` (Options). SCRUB (CHIPS): LEFT/RIGHT move over [previous, Options, next] (absent chips skipped); SELECT on previous/next seeks to that programme's start (next beyond live → live) and focuses BAR; SELECT on Options opens the quick menu (`fromScrub = true`; BACK from it returns to the bar).

### F9. §2.9 `KeyContext` (replaces `:861-862`): keep `enum class KeyContext { GUIDE, PLAYER }`; add `GuideStateMachine.horizontalRepeat` as in S5.

---

## What I could not verify

- **ExoPlayer in-buffer seeks (B3).** That they stay in `STATE_READY` with no BUFFERING is from my knowledge of `ProgressiveMediaPeriod.seekInsideBufferUs`, not re-read in 1.11.1. The fix is safe either way. Owner: WP-B, with I-M4-22.
- **How real panels reject unsupported timeshift forms (S3).** 400, 403, 500, HTML or empty is folklore, not a capture. Owner: the lead with a real provider (D10).
- **The meaning of positive `catchup-correction` on real providers (B2, E24).** The fix follows research §4.4 and Kodi's code. Owner: the lead, M6.
- **`SubtitleView` focus inside `AndroidView` (N15).** Whether Compose's `AndroidViewHolder` makes the child focusable by default is not read here. Owner: WP-D, Robolectric focus test.
- **Whether Compose's `AndroidView` wrapper adds a layer.** It would be a sibling's layer, which is allowed, but it is not checked. Owner: WP-D, in `RootTreeRuleTest` and I-M4-19.
- **Panel DST behaviour (N16).** Whether panels format timeshift tokens in a DST-aware zone, and what they do with the repeated hour. Owner: the lead with a real provider.
- **TiviMate D-pad direction in the player (E3).** It rests on single-source [K] evidence. Owner: the plan §7 parity session.
